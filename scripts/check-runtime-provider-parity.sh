#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck disable=SC1091
source "$ROOT_DIR/runtime-provider.lock"
RUNTIME_PROVIDER_ROOT="${1:-$ROOT_DIR/.runtime-provider}"
RUNTIME_PROVIDER_REF="${RUNTIME_PROVIDER_REF:-}"

git -C "$RUNTIME_PROVIDER_ROOT" rev-parse --is-inside-work-tree >/dev/null 2>&1 || {
	printf 'FAIL: Runtime provider checkout not found: %s\n' "$RUNTIME_PROVIDER_ROOT" >&2
	exit 1
}

[[ -z "$(git -C "$RUNTIME_PROVIDER_ROOT" status --porcelain --untracked-files=all)" ]] || {
	printf 'FAIL: Runtime provider dependency checkout is dirty: %s\n' "$RUNTIME_PROVIDER_ROOT" >&2
	exit 1
}

actual_commit="$(git -C "$RUNTIME_PROVIDER_ROOT" rev-parse 'HEAD^{commit}')"
[[ "$actual_commit" == "$RUNTIME_PROVIDER_COMMIT" ]] || {
	printf 'FAIL: Runtime provider commit mismatch. Expected %s, found %s.\n' \
		"$RUNTIME_PROVIDER_COMMIT" "$actual_commit" >&2
	exit 1
}

[[ "$RUNTIME_PROVIDER_REF" == refs/heads/main ]] || {
	printf 'FAIL: Runtime provider ref must be refs/heads/main in the independent repository.\n' >&2
	exit 1
}
provider_tracking_ref="refs/remotes/origin/${RUNTIME_PROVIDER_REF#refs/heads/}"
provider_commit="$(git -C "$RUNTIME_PROVIDER_ROOT" rev-parse --verify --quiet \
	"$provider_tracking_ref^{commit}" || true)"
[[ "$provider_commit" == "$RUNTIME_PROVIDER_COMMIT" ]] || {
	printf 'FAIL: Provider ref %s is missing or does not preserve RUNTIME_PROVIDER_COMMIT.\n' \
		"$RUNTIME_PROVIDER_REF" >&2
	exit 1
}

for relative in \
	server/conf/world-builder/adaptive-runtime-capability-v2.json \
	server/conf/world-builder/installed-runtime-capability-v3.json \
	scripts/write-adaptive-world-builder-runtime-evidence.py \
	Client_Base/src/orsc/AdaptiveWorldBuilderClientSession.java \
	Client_Base/src/orsc/ProjectContentBundle.java \
	Client_Base/src/orsc/ProjectNpcAnimationRegistry.java \
	Client_Base/src/orsc/NativeLayeredTerrainChunk.java \
	Client_Base/src/orsc/NativeLayeredTerrainPacketDecoder.java \
	Client_Base/src/com/openrsc/client/model/Tile.java \
	Client_Base/src/orsc/WorldBuilderClientProfile.java \
	Client_Base/src/orsc/WorldBuilderInstalledClientProfile.java \
	Client_Base/src/orsc/WorldBuilderTerrainBootstrap.java \
	Client_Base/src/orsc/WorldBuilderTerrainOverlay.java \
	Client_Base/src/orsc/graphics/three/World.java \
	server/src/com/openrsc/server/io/WorldBuilderInstalledServerProfile.java \
	server/src/com/openrsc/server/content/worldedit/AdaptiveWorldBuilderRuntimeIdentity.java \
	server/src/com/openrsc/server/content/worldedit/AdaptiveWorldBuilderRuntimeSession.java \
	server/src/com/openrsc/server/content/worldedit/AdaptiveWorldBuilderPackagePublisher.java; do
	[[ -f "$RUNTIME_PROVIDER_ROOT/$relative" ]] || {
		printf 'FAIL: Pinned runtime capability file is missing: %s\n' "$relative" >&2
		exit 1
	}
done

python3 - "$RUNTIME_PROVIDER_ROOT/server/conf/world-builder/adaptive-runtime-capability-v2.json" <<'PY'
import json
import pathlib
import sys

capability = json.loads(pathlib.Path(sys.argv[1]).read_text(encoding="utf-8"))
expected = {
    "schemaVersion": 2,
    "manifestType": "adaptive-world-builder-runtime-capability",
    "capabilityId": "adaptive-world-builder-runtime-capability-v2",
    "profileId": "adaptive-world-builder",
    "serverBuildId": "core-framework-adaptive-builder-server-v2",
    "clientBuildId": "core-framework-adaptive-builder-client-v2",
    "loaderId": "generic-signed-layered-loader-v2-u16-elevation",
    "authoringId": "generic-signed-layered-authoring-v2-u16-elevation",
    "protocolId": "world-builder-native-layered-protocol-v2-u16-elevation",
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
elevation = capability.get("terrainElevation")
if elevation != {
    "storageEncoding": "unsigned-16",
    "minimum": 0,
    "maximum": 65535,
    "renderScale": 3,
    "legacyV1Promotion": "unsigned-byte-lossless",
    "operations": ["absolute", "raise", "lower"],
    "atomicMultiTileBounds": True,
}:
    raise SystemExit("FAIL: Adaptive runtime wide-elevation contract drifted")
PY

python3 - "$RUNTIME_PROVIDER_ROOT/server/conf/world-builder/installed-runtime-capability-v3.json" <<'PY'
import json
import pathlib
import sys

capability = json.loads(pathlib.Path(sys.argv[1]).read_text(encoding="utf-8"))
expected = {
    "schemaVersion": 1,
    "manifestType": "world-builder-host-runtime-capability",
    "capabilityId": "world-builder-host-runtime-capability-v1",
    "integrationModel": "host-integrated-core-v1",
    "profileId": "world-builder-installed",
    "loaderId": "generic-signed-layered-loader-v7-blocking-base-color",
    "protocolId": "world-builder-native-layered-protocol-v2-u16-elevation",
    "packageSchemaId": "layered-world-package-v1",
    "clientBootstrapId": "world-builder-installed-client-profile-v1",
    "serverBootstrapId": "world-builder-installed-server-profile-v1",
}
for key, value in expected.items():
    if capability.get(key) != value:
        raise SystemExit(
            f"FAIL: Installed runtime capability mismatch for {key}: "
            f"expected {value!r}, found {capability.get(key)!r}"
        )
if capability.get("encodingVersions") != [1, 2, 3, 4]:
    raise SystemExit("FAIL: Installed runtime encoding contract drifted")
activation = capability.get("activation", {})
if activation.get("serverProfileRelativePath") != (
    "server/world-builder-configs/installed-server.json"
):
    raise SystemExit("FAIL: Host server activation-profile path drifted")
if activation.get("clientProfileRelativePaths") != [
    "Client_Base/world-builder-configs/installed-client.json",
    "client/world-builder-configs/installed-client.json",
]:
    raise SystemExit("FAIL: Host client activation-profile paths drifted")
if activation.get("replacesLegacyTerrain") is not False or (
    activation.get("replacesLegacyPlacements") is not False
):
    raise SystemExit("FAIL: Ordinary Import must not retire legacy target data")
if activation.get("ordinaryImportOwnership") != [
    "content-addressed-map-package",
    "world-builder-map-selection",
    "world-builder-owned-activation-profile",
]:
    raise SystemExit("FAIL: Ordinary Import ownership boundary drifted")
PY

client_version="$(sed -n \
	's/.*CLIENT_VERSION[[:space:]]*=[[:space:]]*\([0-9][0-9]*\).*/\1/p' \
	"$RUNTIME_PROVIDER_ROOT/Client_Base/src/orsc/Config.java" | head -n 1)"
provider_runtime_version="$(sed -n \
	's/^[[:space:]]*client_version:[[:space:]]*\([0-9][0-9]*\).*/\1/p' \
	"$RUNTIME_PROVIDER_ROOT/release/world-builder-v2/world-builder-runtime.conf" | head -n 1)"
standalone_runtime_version="$(sed -n \
	's/^[[:space:]]*client_version:[[:space:]]*\([0-9][0-9]*\).*/\1/p' \
	"$ROOT_DIR/release/world-builder-v2/world-builder-runtime.conf" | head -n 1)"
[[ -n "$client_version" && "$client_version" == "$provider_runtime_version" \
	&& "$client_version" == "$standalone_runtime_version" ]] || {
	printf 'FAIL: Pinned client and World Builder protocol versions disagree (%s/%s/%s).\n' \
		"${client_version:-missing}" "${provider_runtime_version:-missing}" \
		"${standalone_runtime_version:-missing}" >&2
	exit 1
}

printf 'PASS: World Builder owns its source and accepts pinned adaptive runtime %s (%s)\n' \
	"$RUNTIME_PROVIDER_COMMIT" "$RUNTIME_PROVIDER_REF"
