#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CORE_ROOT="${1:-}"
PROVIDER_REF="${2:-}"

[[ -n "$CORE_ROOT" && -n "$PROVIDER_REF" ]] || {
	printf 'Usage: %s /path/to/clean-runtime-provider refs/heads/world-builder/runtime/name\n' \
		"$0" >&2
	exit 2
}
git -C "$CORE_ROOT" rev-parse --is-inside-work-tree >/dev/null 2>&1 || {
	printf 'FAIL: Not a Git checkout: %s\n' "$CORE_ROOT" >&2
	exit 1
}
[[ -z "$(git -C "$CORE_ROOT" status --porcelain --untracked-files=all)" ]] || {
	printf 'FAIL: Runtime provider checkout is dirty: %s\n' "$CORE_ROOT" >&2
	exit 1
}
[[ "$PROVIDER_REF" == refs/heads/world-builder/runtime/* ]] \
	|| { printf 'FAIL: Provider ref must use refs/heads/world-builder/runtime/*.\n' >&2; exit 1; }

core_commit="$(git -C "$CORE_ROOT" rev-parse 'HEAD^{commit}')"
core_remote="$(git -C "$CORE_ROOT" remote get-url origin 2>/dev/null \
	|| git -C "$CORE_ROOT" remote get-url spoiled-milk)"
remote_commit="$(git ls-remote "$core_remote" "$PROVIDER_REF" | awk 'NR == 1 { print $1 }')"
[[ "$remote_commit" == "$core_commit" ]] || {
	printf 'FAIL: Provider ref %s does not publish checkout commit %s.\n' \
		"$PROVIDER_REF" "$core_commit" >&2
	exit 1
}

client_version="$(sed -n \
	's/.*CLIENT_VERSION[[:space:]]*=[[:space:]]*\([0-9][0-9]*\).*/\1/p' \
	"$CORE_ROOT/Client_Base/src/orsc/Config.java" | head -n 1)"
[[ -n "$client_version" ]] || {
	printf 'FAIL: Unable to read pinned runtime client version.\n' >&2
	exit 1
}

escaped_remote="${core_remote//&/\\&}"
escaped_ref="${PROVIDER_REF//&/\\&}"
sed -i \
	-e "s|^CORE_REPOSITORY=.*|CORE_REPOSITORY=$escaped_remote|" \
	-e "s|^CORE_REF=.*|CORE_REF=$escaped_ref|" \
	-e "s|^CORE_COMMIT=.*|CORE_COMMIT=$core_commit|" \
	"$ROOT_DIR/core-framework.lock"
sed -i \
	-e "s|^[[:space:]]*client_version:.*|\tclient_version: $client_version|" \
	"$ROOT_DIR/release/world-builder-v2/world-builder-runtime.conf"

printf 'Adopted pinned World Builder runtime %s from %s\n' "$core_commit" "$PROVIDER_REF"
printf 'No World Builder-owned source was copied from the provider repository.\n'
printf 'Materialize the lock, run check-core-parity.sh, then run scripts/test.sh.\n'
