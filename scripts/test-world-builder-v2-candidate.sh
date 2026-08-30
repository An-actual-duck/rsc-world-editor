#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

if (($#)); then
	printf 'Usage: ./scripts/test-world-builder-v2-candidate.sh\n' >&2
	exit 2
fi

git -C "$ROOT_DIR" diff --check
# Candidate selection remains centralized in the normal test runner. Full
# verbose evidence is appropriate at this release boundary; routine focused
# development uses the runner's concise default.
"$ROOT_DIR/scripts/test.sh" --group candidate --verbose

if [[ -z "${WORLD_BUILDER_PWSH:-}" ]] \
	&& ! command -v pwsh >/dev/null 2>&1; then
	printf '%s\n' \
		'NOTICE: Native PowerShell updater fixtures were skipped; set WORLD_BUILDER_PWSH to a reviewed pwsh executable and rerun.'
fi

printf 'PASS: World Builder 2 focused adaptive candidate suites\n'
