#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

"$ROOT_DIR/scripts/build-tools.sh"

for script in "$ROOT_DIR"/release/world-builder/*.sh "$ROOT_DIR"/scripts/*.sh; do
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
