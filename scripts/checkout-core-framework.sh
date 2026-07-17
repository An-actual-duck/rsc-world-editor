#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck disable=SC1091
source "$ROOT_DIR/core-framework.lock"
DESTINATION="${1:-$ROOT_DIR/.core-framework}"

[[ "$CORE_COMMIT" =~ ^[0-9a-f]{40}$ ]] || {
	printf 'FAIL: core-framework.lock contains an invalid commit.\n' >&2
	exit 1
}

if [[ ! -d "$DESTINATION/.git" ]]; then
	[[ ! -e "$DESTINATION" ]] || {
		printf 'FAIL: Destination exists but is not a Git checkout: %s\n' "$DESTINATION" >&2
		exit 1
	}
	git clone "$CORE_REPOSITORY" "$DESTINATION"
fi

git -C "$DESTINATION" fetch origin "$CORE_COMMIT"
[[ -z "$(git -C "$DESTINATION" status --porcelain --untracked-files=all)" ]] || {
	printf 'FAIL: Core-Framework checkout is dirty: %s\n' "$DESTINATION" >&2
	exit 1
}
git -C "$DESTINATION" switch --detach "$CORE_COMMIT"

printf 'Core-Framework dependency ready at %s (%s)\n' "$DESTINATION" "$CORE_COMMIT"
