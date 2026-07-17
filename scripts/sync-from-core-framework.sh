#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CORE_ROOT="${1:-}"

[[ -n "$CORE_ROOT" ]] || {
	printf 'Usage: %s /path/to/open-rsc-spoiled-milk\n' "$0" >&2
	exit 2
}
[[ -d "$CORE_ROOT/.git" ]] || {
	printf 'FAIL: Not a Git checkout: %s\n' "$CORE_ROOT" >&2
	exit 1
}
command -v rsync >/dev/null 2>&1 || {
	printf 'FAIL: rsync is required for bounded source synchronization.\n' >&2
	exit 1
}

for relative in tools/world-builder release/world-builder; do
	[[ -d "$CORE_ROOT/$relative" ]] || {
		printf 'FAIL: Core-Framework path is missing: %s\n' "$relative" >&2
		exit 1
	}
	[[ -z "$(git -C "$CORE_ROOT" status --porcelain --untracked-files=all -- "$relative")" ]] || {
		printf 'FAIL: Refusing to synchronize dirty Core-Framework path: %s\n' "$relative" >&2
		exit 1
	}
done

core_commit="$(git -C "$CORE_ROOT" rev-parse 'HEAD^{commit}')"
core_remote="$(git -C "$CORE_ROOT" remote get-url spoiled-milk 2>/dev/null \
	|| git -C "$CORE_ROOT" remote get-url origin)"

rsync -a --delete "$CORE_ROOT/tools/world-builder/" "$ROOT_DIR/tools/world-builder/"
rsync -a --delete "$CORE_ROOT/release/world-builder/" "$ROOT_DIR/release/world-builder/"

escaped_remote="${core_remote//&/\\&}"
sed -i \
	-e "s|^CORE_REPOSITORY=.*|CORE_REPOSITORY=$escaped_remote|" \
	-e "s|^CORE_COMMIT=.*|CORE_COMMIT=$core_commit|" \
	"$ROOT_DIR/core-framework.lock"

printf 'Synchronized World Builder source from Core-Framework %s\n' "$core_commit"
printf 'Run ./scripts/test.sh and review the complete diff before committing.\n'
