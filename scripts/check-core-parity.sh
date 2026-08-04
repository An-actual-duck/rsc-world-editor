#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck disable=SC1091
source "$ROOT_DIR/core-framework.lock"
CORE_ROOT="${1:-$ROOT_DIR/.core-framework}"
CORE_REF="${CORE_REF:-}"

git -C "$CORE_ROOT" rev-parse --is-inside-work-tree >/dev/null 2>&1 || {
	printf 'FAIL: Core-Framework checkout not found: %s\n' "$CORE_ROOT" >&2
	exit 1
}

[[ -z "$(git -C "$CORE_ROOT" status --porcelain --untracked-files=all)" ]] || {
	printf 'FAIL: Core-Framework dependency checkout is dirty: %s\n' "$CORE_ROOT" >&2
	exit 1
}

actual_commit="$(git -C "$CORE_ROOT" rev-parse 'HEAD^{commit}')"
[[ "$actual_commit" == "$CORE_COMMIT" ]] || {
	printf 'FAIL: Core-Framework commit mismatch. Expected %s, found %s.\n' \
		"$CORE_COMMIT" "$actual_commit" >&2
	exit 1
}

[[ "$CORE_REF" == refs/heads/world-builder/runtime/* ]] || {
	printf 'FAIL: Runtime provider ref must use refs/heads/world-builder/runtime/*.\n' >&2
	exit 1
}
provider_tracking_ref="refs/remotes/origin/${CORE_REF#refs/heads/}"
provider_commit="$(git -C "$CORE_ROOT" rev-parse --verify --quiet \
	"$provider_tracking_ref^{commit}" || true)"
[[ "$provider_commit" == "$CORE_COMMIT" ]] || {
	printf 'FAIL: Provider ref %s is missing or does not preserve CORE_COMMIT.\n' \
		"$CORE_REF" >&2
	exit 1
}

for relative in \
	server/conf/world-builder/adaptive-runtime-capability-v1.json \
	scripts/write-adaptive-world-builder-runtime-evidence.py \
	Client_Base/src/orsc/AdaptiveWorldBuilderClientSession.java \
	server/src/com/openrsc/server/content/worldedit/AdaptiveWorldBuilderRuntimeIdentity.java \
	server/src/com/openrsc/server/content/worldedit/AdaptiveWorldBuilderRuntimeSession.java \
	server/src/com/openrsc/server/content/worldedit/AdaptiveWorldBuilderPackagePublisher.java; do
	[[ -f "$CORE_ROOT/$relative" ]] || {
		printf 'FAIL: Pinned runtime capability file is missing: %s\n' "$relative" >&2
		exit 1
	}
done

python3 - "$CORE_ROOT/server/conf/world-builder/adaptive-runtime-capability-v1.json" <<'PY'
import json
import pathlib
import sys

capability = json.loads(pathlib.Path(sys.argv[1]).read_text(encoding="utf-8"))
expected = {
    "schemaVersion": 1,
    "manifestType": "adaptive-world-builder-runtime-capability",
    "capabilityId": "adaptive-world-builder-runtime-capability-v1",
    "profileId": "adaptive-world-builder",
    "serverBuildId": "core-framework-adaptive-builder-server-v1",
    "clientBuildId": "core-framework-adaptive-builder-client-v1",
    "loaderId": "generic-signed-layered-loader-v1",
    "authoringId": "generic-signed-layered-authoring-v1",
    "protocolId": "world-builder-native-layered-protocol-v1",
    "packageSchemaId": "layered-world-package-v1",
    "coordinateModel": "signed-layered-v1",
}
for key, value in expected.items():
    if capability.get(key) != value:
        raise SystemExit(
            f"FAIL: Adaptive runtime capability mismatch for {key}: "
            f"expected {value!r}, found {capability.get(key)!r}"
        )
families = capability.get("authoring", {}).get("placementFamilies")
if families != ["boundary", "ground-item", "npc", "scenery"]:
    raise SystemExit("FAIL: Adaptive runtime placement-family contract drifted")
PY

client_version="$(sed -n \
	's/.*CLIENT_VERSION[[:space:]]*=[[:space:]]*\([0-9][0-9]*\).*/\1/p' \
	"$CORE_ROOT/Client_Base/src/orsc/Config.java" | head -n 1)"
core_runtime_version="$(sed -n \
	's/^[[:space:]]*client_version:[[:space:]]*\([0-9][0-9]*\).*/\1/p' \
	"$CORE_ROOT/release/world-builder-v2/world-builder-runtime.conf" | head -n 1)"
standalone_runtime_version="$(sed -n \
	's/^[[:space:]]*client_version:[[:space:]]*\([0-9][0-9]*\).*/\1/p' \
	"$ROOT_DIR/release/world-builder-v2/world-builder-runtime.conf" | head -n 1)"
[[ -n "$client_version" && "$client_version" == "$core_runtime_version" \
	&& "$client_version" == "$standalone_runtime_version" ]] || {
	printf 'FAIL: Pinned client and World Builder protocol versions disagree (%s/%s/%s).\n' \
		"${client_version:-missing}" "${core_runtime_version:-missing}" \
		"${standalone_runtime_version:-missing}" >&2
	exit 1
}

printf 'PASS: World Builder owns its source and accepts pinned adaptive runtime %s (%s)\n' \
	"$CORE_COMMIT" "$CORE_REF"
