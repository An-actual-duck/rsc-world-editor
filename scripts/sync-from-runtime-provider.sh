#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RUNTIME_PROVIDER_ROOT="${1:-}"
PROVIDER_REF="${2:-}"

[[ -n "$RUNTIME_PROVIDER_ROOT" && -n "$PROVIDER_REF" ]] || {
	printf 'Usage: %s /path/to/clean-runtime-provider refs/heads/main\n' \
		"$0" >&2
	exit 2
}
git -C "$RUNTIME_PROVIDER_ROOT" rev-parse --is-inside-work-tree >/dev/null 2>&1 || {
	printf 'FAIL: Not a Git checkout: %s\n' "$RUNTIME_PROVIDER_ROOT" >&2
	exit 1
}
[[ -z "$(git -C "$RUNTIME_PROVIDER_ROOT" status --porcelain --untracked-files=all)" ]] || {
	printf 'FAIL: Runtime provider checkout is dirty: %s\n' "$RUNTIME_PROVIDER_ROOT" >&2
	exit 1
}
[[ "$PROVIDER_REF" == refs/heads/main ]] \
	|| { printf 'FAIL: Provider ref must be refs/heads/main in the independent repository.\n' >&2; exit 1; }

runtime_provider_commit="$(git -C "$RUNTIME_PROVIDER_ROOT" rev-parse 'HEAD^{commit}')"
runtime_provider_remote="$(git -C "$RUNTIME_PROVIDER_ROOT" remote get-url origin)"
[[ "$runtime_provider_remote" == "https://github.com/An-actual-duck/rsc-world-editor-runtime.git" ]] || {
	printf 'FAIL: Provider must be the independent rsc-world-editor-runtime repository.\n' >&2
	exit 1
}
remote_commit="$(git ls-remote "$runtime_provider_remote" "$PROVIDER_REF" | awk 'NR == 1 { print $1 }')"
[[ "$remote_commit" == "$runtime_provider_commit" ]] || {
	printf 'FAIL: Provider ref %s does not publish checkout commit %s.\n' \
		"$PROVIDER_REF" "$runtime_provider_commit" >&2
	exit 1
}

client_version="$(sed -n \
	's/.*CLIENT_VERSION[[:space:]]*=[[:space:]]*\([0-9][0-9]*\).*/\1/p' \
	"$RUNTIME_PROVIDER_ROOT/Client_Base/src/orsc/Config.java" | head -n 1)"
[[ -n "$client_version" ]] || {
	printf 'FAIL: Unable to read pinned runtime client version.\n' >&2
	exit 1
}

escaped_remote="${runtime_provider_remote//&/\\&}"
escaped_ref="${PROVIDER_REF//&/\\&}"
sed -i \
	-e "s|^RUNTIME_PROVIDER_REPOSITORY=.*|RUNTIME_PROVIDER_REPOSITORY=$escaped_remote|" \
	-e "s|^RUNTIME_PROVIDER_REF=.*|RUNTIME_PROVIDER_REF=$escaped_ref|" \
	-e "s|^RUNTIME_PROVIDER_COMMIT=.*|RUNTIME_PROVIDER_COMMIT=$runtime_provider_commit|" \
	"$ROOT_DIR/runtime-provider.lock"
sed -i \
	-e "s|^[[:space:]]*client_version:.*|\tclient_version: $client_version|" \
	"$ROOT_DIR/release/world-builder-v2/world-builder-runtime.conf"

printf 'Adopted pinned World Builder runtime %s from %s\n' "$runtime_provider_commit" "$PROVIDER_REF"
printf 'No World Builder-owned source was copied from the provider repository.\n'
printf 'Materialize the lock, run check-runtime-provider-parity.sh, then run scripts/test.sh.\n'
