#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TARGET_ROOT="$(cd "$ROOT_DIR/.." && pwd)"
RUNTIME_ROOT="$ROOT_DIR/builder-runtime"
WORKSPACE="$ROOT_DIR/workspace"
TOOLS_JAR="$RUNTIME_ROOT/launcher/world-builder-tools.jar"
PROJECT_REGISTRY="$ROOT_DIR/project-registry.json"
RELEASE_IDENTITY="$ROOT_DIR/RELEASE-IDENTITY.json"
UPDATE_LOCK="$ROOT_DIR/.world-builder-v2-update.lock"

fail() {
	printf 'World Builder 2 could not start: %s\n' "$*" >&2
	exit 1
}

update_lock_exists() {
	[[ -e "$UPDATE_LOCK" || -L "$UPDATE_LOCK" ]]
}

if update_lock_exists; then
	fail "An application update is already in progress. Wait for it to finish, then start again."
fi
if [[ "${WORLD_BUILDER_SKIP_UPDATE:-0}" != 1 \
	&& -x "$ROOT_DIR/Update World Builder.sh" ]]; then
	if ! "$ROOT_DIR/Update World Builder.sh" --automatic; then
		if update_lock_exists; then
			fail "An application update is already in progress. Wait for it to finish, then start again."
		fi
		printf 'WARNING: The World Builder 2 automatic update check failed; continuing with the installed v2 version.\n' >&2
	fi
fi
if update_lock_exists; then
	fail "An application update is already in progress. Wait for it to finish, then start again."
fi

if [[ -n "${WORLD_BUILDER_JAVA:-}" ]]; then
	JAVA_EXE="$WORLD_BUILDER_JAVA"
elif [[ -x "$ROOT_DIR/runtime/bin/java" ]]; then
	JAVA_EXE="$ROOT_DIR/runtime/bin/java"
else
	JAVA_EXE="$(command -v java || true)"
fi

[[ -n "$JAVA_EXE" ]] || fail "Java 17 or newer was not found."
[[ -f "$TOOLS_JAR" ]] || fail "The packaged launcher is missing: $TOOLS_JAR"
[[ -f "$RELEASE_IDENTITY" ]] \
	|| fail "World Builder 2 release identity is missing."
grep -F '"productId": "rsc-world-editor-v2"' "$RELEASE_IDENTITY" >/dev/null \
	|| fail "This launcher is not inside a World Builder 2 release."
"$JAVA_EXE" -version >/dev/null 2>&1 \
	|| fail "Java could not be executed: $JAVA_EXE"

if [[ -e "$WORKSPACE" && ! -f "$PROJECT_REGISTRY" ]]; then
	fail "A historical World Builder 2 workspace is present. It was preserved, but the adaptive launcher will not migrate or replace it. Keep this installation intact for matching-version recovery, or move the complete adaptive installation to a separate folder."
fi

PORT="${WORLD_BUILDER_PORT:-43615}"
[[ "$PORT" =~ ^[0-9]+$ ]] && ((PORT >= 1 && PORT < 65535)) \
	|| fail "WORLD_BUILDER_PORT must be between 1 and 65534."

ADAPTIVE_ARGUMENTS=(
	launch-adaptive
	--installation-root "$ROOT_DIR"
	--runtime-root "$RUNTIME_ROOT"
	--target-root "$TARGET_ROOT"
	--port "$PORT"
)
if [[ -n "${WORLD_BUILDER_CONFIGURATION_ROLE:-}" ]]; then
	ADAPTIVE_ARGUMENTS+=(
		--configuration-role "$WORLD_BUILDER_CONFIGURATION_ROLE"
	)
fi
exec "$JAVA_EXE" -jar "$TOOLS_JAR" "${ADAPTIVE_ARGUMENTS[@]}"
