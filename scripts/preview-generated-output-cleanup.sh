#!/usr/bin/env bash
set -euo pipefail

SCRIPT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ROOT_DIR="${ROOT_DIR:-$SCRIPT_ROOT}"
verbose=false

usage() {
	cat <<'USAGE'
Usage: ./scripts/preview-generated-output-cleanup.sh [--verbose]

Produces a read-only retention report for Editor-owned generated output.
It never deletes, moves, archives, or modifies a file. --verbose prints the
exact per-directory classification behind the summary.
USAGE
}

while (($#)); do
	case "$1" in
		--verbose|-v)
			verbose=true
			shift
			;;
		-h|--help)
			usage
			exit 0
			;;
		*)
			printf 'FAIL: Unknown cleanup-preview option: %s\n' "$1" >&2
			usage >&2
			exit 2
			;;
	esac
done

[[ -d "$ROOT_DIR" ]] || {
	printf 'FAIL: Repository root does not exist: %s\n' "$ROOT_DIR" >&2
	exit 1
}
ROOT_DIR="$(cd "$ROOT_DIR" && pwd -P)"
OUTPUT_ROOT="$ROOT_DIR/output"
case "$OUTPUT_ROOT" in
	"$ROOT_DIR"/output) ;;
	*)
		printf 'FAIL: Generated output root escaped the repository: %s\n' "$OUTPUT_ROOT" >&2
		exit 1
		;;
esac

human_kib() {
	local kib="$1"
	if command -v numfmt >/dev/null 2>&1; then
		numfmt --to=iec --suffix=B $((kib * 1024))
	else
		printf '%s KiB\n' "$kib"
	fi
}

protected_marker() {
	local root="$1" marker
	marker="$(find "$root" \
		\( -type d \( -name projects -o -name workspace -o -name exports \
			-o -name backups -o -name receipts -o -name recovery \
			-o -name credentials \) \
		-o -type f \( -name world_builder.db -o -name world-builder.credential \
			-o -name '*.pem' -o -name '*.log' \) \) \
		-print -quit 2>/dev/null || true)"
	[[ -n "$marker" ]] || return 1
	printf '%s\n' "${marker#"$ROOT_DIR/"}"
}

cleanup_count=0
cleanup_kib=0
gate_open=false
[[ -f "$ROOT_DIR/release/world-builder-v2/RELEASE-READY" ]] && gate_open=true

scan_versioned() {
	local label="$1" root="$2" keep_recent="$3" gate_sensitive="$4"
	local count=0 size_kib=0 eligible_count=0 eligible_kib=0 retained_count=0
	local rank=0 record mtime path kib marker classification age
	local -a details=()
	if [[ ! -d "$root" ]]; then
		printf '%-18s entries=0 size=0B review-disposable=0\n' "$label"
		return 0
	fi
	while IFS= read -r record; do
		[[ -n "$record" ]] || continue
		mtime="${record%% *}"
		path="${record#* }"
		count=$((count + 1))
		rank=$((rank + 1))
		kib="$(du -sk -- "$path" | awk '{print $1}')"
		size_kib=$((size_kib + kib))
		marker="$(protected_marker "$path" || true)"
		if [[ -n "$marker" ]]; then
			classification="BLOCKED-DURABLE:$marker"
			retained_count=$((retained_count + 1))
		elif [[ "$gate_sensitive" == true && "$gate_open" == true ]]; then
			classification="KEEP-OPEN-GATE"
			retained_count=$((retained_count + 1))
		elif ((rank <= keep_recent)); then
			classification="KEEP-RECENT"
			retained_count=$((retained_count + 1))
		else
			classification="REVIEW-DISPOSABLE"
			eligible_count=$((eligible_count + 1))
			eligible_kib=$((eligible_kib + kib))
		fi
		age="$(date -d "@$mtime" '+%Y-%m-%d' 2>/dev/null || printf 'unknown')"
		details+=("  $classification size=$(human_kib "$kib") modified=$age path=${path#"$ROOT_DIR/"}")
	done < <(find "$root" -mindepth 1 -maxdepth 1 -type d \
		-printf '%T@ %p\n' | sort -nr)
	cleanup_count=$((cleanup_count + eligible_count))
	cleanup_kib=$((cleanup_kib + eligible_kib))
	printf '%-18s entries=%-3s size=%-8s review-disposable=%-3s potential=%s retained/blocked=%s\n' \
		"$label" "$count" "$(human_kib "$size_kib")" "$eligible_count" \
		"$(human_kib "$eligible_kib")" "$retained_count"
	if [[ "$verbose" == true && ${#details[@]} -gt 0 ]]; then
		printf '%s\n' "${details[@]}"
	fi
}

scan_manual() {
	local label="$1" root="$2" count=0 kib=0
	if [[ -d "$root" ]]; then
		count="$(find "$root" -mindepth 1 -maxdepth 1 -type d | wc -l | tr -d ' ')"
		kib="$(du -sk -- "$root" | awk '{print $1}')"
	fi
	printf '%-18s entries=%-3s size=%-8s policy=MANUAL-REVIEW-ONLY\n' \
		"$label" "$count" "$(human_kib "$kib")"
	if [[ "$verbose" == true && -d "$root" ]]; then
		find "$root" -mindepth 1 -maxdepth 1 -type d -printf '  KEEP-MANUAL path=%p\n' \
			| sed "s#path=$ROOT_DIR/#path=#" | sort
	fi
}

printf 'Generated output cleanup preview (read-only)\n'
printf '  Repository: %s\n' "$ROOT_DIR"
printf '  Release gate: %s\n' "$([[ "$gate_open" == true ]] && printf open || printf closed)"
if [[ ! -d "$OUTPUT_ROOT" ]]; then
	printf '  output/ is absent; nothing to review.\n'
	exit 0
fi

scan_versioned candidates "$OUTPUT_ROOT/candidates/world-builder-v2" 2 true
scan_versioned test-builds "$OUTPUT_ROOT/test-builds" 1 false
scan_versioned local-releases "$OUTPUT_ROOT/releases/world-builder-v2" 1 false
scan_manual development "$OUTPUT_ROOT/development"

legacy_release_kib=0
[[ ! -d "$OUTPUT_ROOT/releases/world-builder" ]] \
	|| legacy_release_kib="$(du -sk -- "$OUTPUT_ROOT/releases/world-builder" | awk '{print $1}')"
printf '%-18s size=%-8s policy=KEEP-FROZEN-V1\n' \
	frozen-v1-release "$(human_kib "$legacy_release_kib")"

tool_kib=0
[[ ! -d "$OUTPUT_ROOT/world-builder-tools" ]] \
	|| tool_kib="$(du -sk -- "$OUTPUT_ROOT/world-builder-tools" | awk '{print $1}')"
printf '%-18s size=%-8s policy=KEEP-CURRENT-REBUILDABLE\n' \
	world-builder-tools "$(human_kib "$tool_kib")"

printf 'Potential review set: %s directories / %s\n' \
	"$cleanup_count" "$(human_kib "$cleanup_kib")"
printf 'No files changed. REVIEW-DISPOSABLE is not deletion authorization; inspect the exact --verbose inventory first.\n'
