#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
# shellcheck disable=SC1091
source "$ROOT_DIR/runtime-provider.lock"

STATE_ROOT="${WORLD_BUILDER_TOOL_TEST_ROOT:-$ROOT_DIR/output/development/world-builder-tool-test-environment}"
INSTALLATION="$STATE_ROOT/World Builder 2"
EMPTY_SOURCE="$STATE_ROOT/standalone-source"
RUNTIME_ROOT="$INSTALLATION/builder-runtime"
TOOLS_JAR="$ROOT_DIR/output/world-builder-tools/world-builder-tools.jar"
ALLOWLIST="$ROOT_DIR/release/world-builder-v2/RUNTIME-ASSET-ALLOWLIST.txt"
RUNTIME_CONFIGURATION="$ROOT_DIR/release/world-builder-v2/world-builder-runtime.conf"
PROVIDER_ROOT="${RUNTIME_PROVIDER_DIR:-$ROOT_DIR/.runtime-provider}"
PREBUILT_RUNTIME="${WORLD_BUILDER_TOOL_TEST_PREBUILT_RUNTIME:-}"
PORT="${WORLD_BUILDER_TOOL_TEST_PORT:-43625}"
SEED_ID="development-terrain-v1"
MODE="${1:-prepare}"

fail() {
	printf 'FAIL: %s\n' "$*" >&2
	exit 1
}

usage() {
	cat <<'EOF'
Usage:
  ./scripts/world-builder-tool-test-environment.sh prepare
  ./scripts/world-builder-tool-test-environment.sh launch
  ./scripts/world-builder-tool-test-environment.sh path
  ./scripts/world-builder-tool-test-environment.sh reset --confirm RESET

Environment:
  WORLD_BUILDER_TOOL_TEST_ROOT  Override the ignored development-state root.
  WORLD_BUILDER_TOOL_TEST_PORT  Override loopback port 43625.
  RUNTIME_PROVIDER_DIR          Override the exact locked runtime checkout.
  WORLD_BUILDER_JAVA            Select Java 17+.
EOF
}

[[ "$MODE" == prepare || "$MODE" == launch || "$MODE" == path || "$MODE" == reset ]] \
	|| { usage >&2; fail "Unknown mode: $MODE"; }

case "$STATE_ROOT" in
	"$ROOT_DIR"/output/development/*) ;;
	*) [[ -n "${WORLD_BUILDER_TOOL_TEST_ROOT:-}" ]] \
		|| fail "Default development state escaped output/development" ;;
esac

if [[ "$MODE" == path ]]; then
	printf '%s\n' "$INSTALLATION"
	exit 0
fi

JAVA_EXE="${WORLD_BUILDER_JAVA:-$(command -v java || true)}"
[[ -n "$JAVA_EXE" ]] || fail "Java 17+ was not found"
"$JAVA_EXE" -version >/dev/null 2>&1 || fail "Java could not be executed: $JAVA_EXE"
[[ "$PORT" =~ ^[0-9]+$ ]] && ((PORT >= 1 && PORT < 65535)) \
	|| fail "WORLD_BUILDER_TOOL_TEST_PORT must be between 1 and 65534"

if [[ "$MODE" == reset ]]; then
	[[ "${2:-}" == --confirm && "${3:-}" == RESET && $# -eq 3 ]] \
		|| fail "Reset requires: reset --confirm RESET"
	if [[ -e "$INSTALLATION" || -L "$INSTALLATION" ]]; then
		[[ -d "$INSTALLATION" && ! -L "$INSTALLATION" ]] \
			|| fail "Development installation is not one safe directory: $INSTALLATION"
		mkdir -p "$STATE_ROOT/retired"
		stamp="$(date -u +%Y%m%dT%H%M%SZ)"
		retired="$STATE_ROOT/retired/World Builder 2-$stamp"
		[[ ! -e "$retired" && ! -L "$retired" ]] \
			|| fail "Retired reset destination already exists: $retired"
		mv "$INSTALLATION" "$retired"
		printf 'Retired the previous development sandbox to %s\n' "$retired"
	fi
	MODE=prepare
fi

for command_name in cp date find git javac jar mktemp mv sha256sum; do
	command -v "$command_name" >/dev/null 2>&1 \
		|| fail "Missing required command: $command_name"
done

provider_commit="$RUNTIME_PROVIDER_COMMIT"
if [[ -n "$PREBUILT_RUNTIME" ]]; then
	[[ "${WORLD_BUILDER_TOOL_TEST_HARNESS:-0}" == 1 ]] \
		|| fail "A prebuilt runtime is restricted to the automated test harness"
	[[ -d "$PREBUILT_RUNTIME" && ! -L "$PREBUILT_RUNTIME" ]] \
		|| fail "Prebuilt test runtime is missing or unsafe: $PREBUILT_RUNTIME"
else
	[[ -d "$PROVIDER_ROOT" && ! -L "$PROVIDER_ROOT" ]] \
		|| fail "Runtime provider checkout is missing or unsafe: $PROVIDER_ROOT"
	provider_commit="$(git -C "$PROVIDER_ROOT" rev-parse --verify 'HEAD^{commit}')"
	[[ "$provider_commit" == "$RUNTIME_PROVIDER_COMMIT" ]] \
		|| fail "Runtime provider must be the exact locked commit $RUNTIME_PROVIDER_COMMIT; found $provider_commit"
	[[ -z "$(git -C "$PROVIDER_ROOT" status --porcelain --untracked-files=all)" ]] \
		|| fail "Runtime provider checkout has uncommitted or untracked state"
fi
[[ -f "$ALLOWLIST" && ! -L "$ALLOWLIST" ]] || fail "Runtime allowlist is missing or unsafe"
[[ -f "$RUNTIME_CONFIGURATION" && ! -L "$RUNTIME_CONFIGURATION" ]] \
	|| fail "Runtime configuration is missing or unsafe"

"$ROOT_DIR/scripts/build-tools.sh"

mkdir -p "$STATE_ROOT" "$EMPTY_SOURCE" "$INSTALLATION"
runtime_marker="$RUNTIME_ROOT/.development-runtime-source"
if [[ -d "$RUNTIME_ROOT" && ! -L "$RUNTIME_ROOT" ]]; then
	[[ -f "$runtime_marker" && ! -L "$runtime_marker" ]] \
		|| fail "Existing development runtime has no source marker; reset the sandbox"
	read -r staged_provider staged_seed < "$runtime_marker" \
		|| fail "Existing development runtime marker is malformed"
	[[ "$staged_provider" == "$provider_commit" && "$staged_seed" == "$SEED_ID" ]] \
		|| fail "Development runtime or seed changed; run the explicit reset command"
elif [[ -e "$RUNTIME_ROOT" || -L "$RUNTIME_ROOT" ]]; then
	fail "Development runtime path is unsafe: $RUNTIME_ROOT"
else
	stage="$(mktemp -d "$STATE_ROOT/.runtime-stage.XXXXXX")"
	if [[ -n "$PREBUILT_RUNTIME" ]]; then
		cp -a "$PREBUILT_RUNTIME"/. "$stage"/
	else
		"$PROVIDER_ROOT/scripts/build-server.sh"
		SPOILED_MILK_RELEASE_BUILD=1 "$PROVIDER_ROOT/scripts/build-client.sh"
		for artifact in \
			"$PROVIDER_ROOT/Client_Base/Open_RSC_Client.jar" \
			"$PROVIDER_ROOT/server/core.jar" \
			"$PROVIDER_ROOT/server/plugins.jar"; do
			[[ -f "$artifact" && ! -L "$artifact" ]] \
				|| fail "Runtime build did not produce: $artifact"
		done
		mkdir -p "$stage/Client_Base" "$stage/server" "$stage/launcher/schema"
		cp "$PROVIDER_ROOT/Client_Base/Open_RSC_Client.jar" \
			"$stage/Client_Base/Open_RSC_Client.jar"
		cp "$PROVIDER_ROOT/server/core.jar" "$stage/server/core.jar"
		cp "$PROVIDER_ROOT/server/plugins.jar" "$stage/server/plugins.jar"
		while IFS=$'\t' read -r source destination role || [[ -n "$source$destination$role" ]]; do
			[[ -n "$source" && "$source" != \#* ]] || continue
			[[ -n "$destination" && -n "$role" ]] || fail "Malformed runtime allowlist record"
			case "$source:$destination" in
				/*:*|*:\/*|*../*|*/..:*|*:*../*|*:*/..) fail "Unsafe runtime allowlist path" ;;
			esac
			input="$PROVIDER_ROOT/$source"
			output="$stage/$destination"
			[[ -f "$input" && ! -L "$input" ]] \
				|| fail "Runtime allowlist input is missing or unsafe: $source"
			mkdir -p "${output%/*}"
			cp "$input" "$output"
		done < "$ALLOWLIST"
		cp "$RUNTIME_CONFIGURATION" "$stage/server/world-builder.conf"
		cp "$TOOLS_JAR" "$stage/launcher/world-builder-tools.jar"
		while IFS= read -r -d '' schema; do
			relative="${schema#"$ROOT_DIR/tools/world-builder/schema/"}"
			mkdir -p "$stage/launcher/schema/${relative%/*}"
			cp "$schema" "$stage/launcher/schema/$relative"
		done < <(find "$ROOT_DIR/tools/world-builder/schema" -type f -print0)
	fi
	printf '%s %s\n' "$provider_commit" "$SEED_ID" \
		> "$stage/.development-runtime-source"
	mv "$stage" "$RUNTIME_ROOT"
fi

mapfile -t projects < <(find "$INSTALLATION/projects" -mindepth 1 -maxdepth 1 \
	-type d ! -name '.*' -print 2>/dev/null | sort)
if ((${#projects[@]} == 0)); then
	report="$STATE_ROOT/standalone-discovery.json"
	"$JAVA_EXE" -jar "$TOOLS_JAR" discover-adaptive \
		--target-root "$EMPTY_SOURCE" > "$report"
	"$JAVA_EXE" -jar "$TOOLS_JAR" create-project \
		--installation-root "$INSTALLATION" \
		--runtime-root "$RUNTIME_ROOT" \
		--target-root "$EMPTY_SOURCE" \
		--discovery-report "$report" \
		--display-name "Tool Test Environment" \
		--port "$PORT" \
		--development-terrain-seed \
		--confirm CREATE
	mapfile -t projects < <(find "$INSTALLATION/projects" -mindepth 1 -maxdepth 1 \
		-type d ! -name '.*' -print | sort)
fi
[[ ${#projects[@]} -eq 1 ]] \
	|| fail "Development installation must contain exactly one project; found ${#projects[@]}"
project="${projects[0]}"
"$JAVA_EXE" -jar "$TOOLS_JAR" open-project \
	--installation-root "$INSTALLATION" --validate-only >/dev/null

printf 'World Builder tool test environment ready.\n'
printf '  installation: %s\n' "$INSTALLATION"
printf '  project:      %s\n' "$project"
printf '  seed:         %s (1 sector, 48x48 tiles, centered spawn 120,648)\n' "$SEED_ID"
printf '  reset:        %s reset --confirm RESET\n' "$0"

if [[ "$MODE" == launch ]]; then
	exec "$JAVA_EXE" -jar "$TOOLS_JAR" run-adaptive-project --project "$project"
fi
