#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck disable=SC1091
source "$ROOT_DIR/runtime-provider.lock"
DESTINATION="${1:-$ROOT_DIR/.runtime-provider}"
RUNTIME_PROVIDER_REF="${RUNTIME_PROVIDER_REF:-}"

[[ "$RUNTIME_PROVIDER_REPOSITORY" == "https://github.com/An-actual-duck/rsc-world-editor-runtime.git" ]] || {
	printf 'FAIL: runtime-provider.lock must name the independent runtime repository.\n' >&2
	exit 1
}
[[ "$RUNTIME_PROVIDER_REF" == "refs/heads/main" ]] || {
	printf 'FAIL: runtime-provider.lock must name the runtime repository main ref.\n' >&2
	exit 1
}
[[ "$RUNTIME_PROVIDER_COMMIT" =~ ^[0-9a-f]{40}$ ]] || {
	printf 'FAIL: runtime-provider.lock contains an invalid commit.\n' >&2
	exit 1
}

if [[ ! -d "$DESTINATION/.git" ]]; then
	[[ ! -e "$DESTINATION" ]] || {
		printf 'FAIL: Destination exists but is not a Git checkout: %s\n' "$DESTINATION" >&2
		exit 1
	}
	git clone "$RUNTIME_PROVIDER_REPOSITORY" "$DESTINATION"
fi

if [[ -n "$RUNTIME_PROVIDER_REF" ]]; then
	git -C "$DESTINATION" check-ref-format "$RUNTIME_PROVIDER_REF" >/dev/null 2>&1 || {
		printf 'FAIL: runtime-provider.lock contains an invalid provider ref.\n' >&2
		exit 1
	}
	git -C "$DESTINATION" fetch origin "$RUNTIME_PROVIDER_REF"
	[[ "$(git -C "$DESTINATION" rev-parse 'FETCH_HEAD^{commit}')" == "$RUNTIME_PROVIDER_COMMIT" ]] || {
		printf 'FAIL: Locked provider ref no longer resolves to RUNTIME_PROVIDER_COMMIT.\n' >&2
		exit 1
	}
else
	git -C "$DESTINATION" fetch origin "$RUNTIME_PROVIDER_COMMIT"
fi
[[ "$(git -C "$DESTINATION" remote get-url origin)" == "$RUNTIME_PROVIDER_REPOSITORY" ]] || {
	printf 'FAIL: Existing checkout origin does not match the locked runtime provider: %s\n' "$DESTINATION" >&2
	exit 1
}
[[ -z "$(git -C "$DESTINATION" status --porcelain --untracked-files=all)" ]] || {
	printf 'FAIL: Runtime provider checkout is dirty: %s\n' "$DESTINATION" >&2
	exit 1
}
git -C "$DESTINATION" switch --detach "$RUNTIME_PROVIDER_COMMIT"

printf 'Runtime provider dependency ready at %s (%s)\n' "$DESTINATION" "$RUNTIME_PROVIDER_COMMIT"
