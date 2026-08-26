#!/usr/bin/env bash
set -euo pipefail

# Repository checks must remain headless even when a graphical desktop is
# available. Individual launcher tests can still exercise their direct fallback
# paths without opening a terminal window on the developer's desktop.
export WORLD_BUILDER_NO_TERMINAL=1

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

"$ROOT_DIR/scripts/build-tools.sh"

for script in \
	"$ROOT_DIR"/release/world-builder/*.sh \
	"$ROOT_DIR"/release/world-builder-v2/*.sh \
	"$ROOT_DIR"/release/updater/*.sh \
	"$ROOT_DIR"/release/updater-v2/*.sh \
	"$ROOT_DIR"/scripts/*.sh; do
	bash -n "$script"
done

test_count=0
for test_file in "$ROOT_DIR"/tests/myworld/test-world-builder-*.py; do
	python3 "$test_file" -v
	((test_count += 1))
done
(( test_count > 0 )) || {
	printf 'FAIL: No World Builder tests were found.\n' >&2
	exit 1
}

printf 'PASS: RSC World Editor repository checks\n'
