#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

if (($#)); then
	printf 'Usage: ./scripts/test-world-builder-v2-candidate.sh\n' >&2
	exit 2
fi

git -C "$ROOT_DIR" diff --check

tests=(
	tests/myworld/test-world-builder-v2-candidate-validation.py
	tests/myworld/test-world-builder-adaptive-contracts.py
	tests/myworld/test-world-builder-adaptive-discovery.py
	tests/myworld/test-world-builder-packed-conversion.py
	tests/myworld/test-world-builder-runtime-preparation.py
	tests/myworld/test-world-builder-adaptive-project-lifecycle.py
	tests/myworld/test-world-builder-adaptive-transactions.py
	tests/myworld/test-world-builder-v2-release.py
	tests/myworld/test-world-builder-v2-updater.py
	tests/myworld/test-world-builder-product-generations.py
	tests/myworld/test-world-builder-project-independence.py
)

for relative in "${tests[@]}"; do
	printf 'Candidate validation suite: %s\n' "$relative"
	python3 "$ROOT_DIR/$relative" -v
done

if [[ -z "${WORLD_BUILDER_PWSH:-}" ]] \
	&& ! command -v pwsh >/dev/null 2>&1; then
	printf '%s\n' \
		'NOTICE: Native PowerShell updater fixtures were skipped; set WORLD_BUILDER_PWSH to a reviewed pwsh executable and rerun.'
fi

printf 'PASS: World Builder 2 focused adaptive candidate suites\n'
