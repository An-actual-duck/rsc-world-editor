#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck disable=SC1091
source "$ROOT_DIR/core-framework.lock"
CORE_ROOT="${1:-$ROOT_DIR/.core-framework}"

[[ -d "$CORE_ROOT/.git" ]] || {
	printf 'FAIL: Core-Framework checkout not found: %s\n' "$CORE_ROOT" >&2
	exit 1
}

actual_commit="$(git -C "$CORE_ROOT" rev-parse 'HEAD^{commit}')"
[[ "$actual_commit" == "$CORE_COMMIT" ]] || {
	printf 'FAIL: Core-Framework commit mismatch. Expected %s, found %s.\n' \
		"$CORE_COMMIT" "$actual_commit" >&2
	exit 1
}

for relative in tools/world-builder release/world-builder; do
	diff -qr "$ROOT_DIR/$relative" "$CORE_ROOT/$relative" || {
		printf 'FAIL: Synchronized source differs from locked Core-Framework path: %s\n' \
			"$relative" >&2
		exit 1
	}
done

printf 'PASS: World Builder source matches Core-Framework %s\n' "$CORE_COMMIT"
