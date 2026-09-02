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
	server/conf/world-builder/installed-runtime-capability-v2.json \
	server/conf/world-builder/installed-client-source-upgrade-v5.json \
	server/conf/world-builder/managed-runtime-bundle.json \
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

python3 - "$RUNTIME_PROVIDER_ROOT/server/conf/world-builder/installed-runtime-capability-v2.json" <<'PY'
import json
import pathlib
import sys

capability = json.loads(pathlib.Path(sys.argv[1]).read_text(encoding="utf-8"))
expected = {
    "schemaVersion": 1,
    "manifestType": "world-builder-installed-runtime-capability",
    "capabilityId": "world-builder-installed-runtime-capability-v2",
    "managedRuntimeBundleId": "world-builder-managed-runtime-current",
    "profileId": "world-builder-installed",
    "loaderId": "generic-signed-layered-loader-v7-blocking-base-color",
    "protocolId": "world-builder-native-layered-protocol-v2-u16-elevation",
    "packageSchemaId": "layered-world-package-v1",
    "clientBootstrapId": "world-builder-installed-client-profile-v1",
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
if activation.get("builderOnly") is not False:
    raise SystemExit("FAIL: Installed runtime must not require Builder mode")
if (
    not activation.get("replacesLegacyTerrain")
    or not activation.get("replacesLegacyPlacements")
    or not activation.get("replacesLegacyClientBootstrap")
):
    raise SystemExit(
        "FAIL: Installed runtime must replace legacy map and client bootstrap authorities"
    )
if capability.get("clientSourceUpgrade") != {
    "upgradeId": "world-builder-installed-client-source-upgrade-v5",
    "manifestRelativePath": "server/conf/world-builder/installed-client-source-upgrade-v5.json",
    "buildPolicy": "atomic-compile-target-client-before-run",
}:
    raise SystemExit("FAIL: Installed client source-upgrade contract drifted")
PY

python3 - "$RUNTIME_PROVIDER_ROOT/server/conf/world-builder/managed-runtime-bundle.json" <<'PY'
import json
import pathlib
import sys

bundle = json.loads(pathlib.Path(sys.argv[1]).read_text(encoding="utf-8"))
expected_identity = {
    "schemaVersion": 1,
    "manifestType": "world-builder-managed-runtime-bundle",
    "bundleId": "world-builder-managed-runtime-current",
    "runtimeContractId": "world-builder-installed-loader-v12",
    "profileId": "world-builder-installed",
    "loaderId": "generic-signed-layered-loader-v7-blocking-base-color",
    "protocolId": "world-builder-native-layered-protocol-v2-u16-elevation",
    "clientBootstrapId": "world-builder-installed-client-profile-v1",
}
for key, value in expected_identity.items():
    if bundle.get(key) != value:
        raise SystemExit(
            f"FAIL: Managed runtime bundle mismatch for {key}: "
            f"expected {value!r}, found {bundle.get(key)!r}"
        )
expected_components = [
    (
        "server-runtime-upgrade",
        "server/world-builder-runtime/world-builder-managed-runtime.jar",
        "target-root",
        "server/world-builder-runtime/world-builder-managed-runtime.jar",
    ),
    (
        "client-source-upgrade",
        "server/conf/world-builder/installed-client-source-upgrade-v5.json",
        "selected-client-root",
        "src",
    ),
    (
        "runtime-capability",
        "server/conf/world-builder/installed-runtime-capability-v2.json",
        "target-root",
        "server/conf/world-builder/installed-runtime-capability-v2.json",
    ),
]
components = bundle.get("components")
if not isinstance(components, list) or len(components) != len(expected_components):
    raise SystemExit("FAIL: Managed runtime bundle component count drifted")
for component, expected in zip(components, expected_components):
    actual = tuple(component.get(key) for key in (
        "role", "sourceRelativePath", "destinationKind", "destinationRelativePath"
    ))
    policy = (
        "semantic-upgrade-with-verified-backup"
        if component.get("role") == "client-source-upgrade"
        else "replace-with-verified-backup"
    )
    if actual != expected or component.get("replacementPolicy") != policy:
        raise SystemExit(
            f"FAIL: Managed runtime bundle component drifted: {actual!r}"
        )
if bundle.get("legacyCapabilityPaths") != [
    "server/conf/world-builder/installed-runtime-capability-v1.json"
]:
    raise SystemExit("FAIL: Managed runtime legacy retirement set drifted")
boundary = " ".join(bundle.get("serverUpgradeBoundary", []))
if "target-owned gameplay" not in boundary:
    raise SystemExit("FAIL: Managed runtime server upgrade boundary drifted")
PY

python3 - "$RUNTIME_PROVIDER_ROOT/server/conf/world-builder/installed-client-source-upgrade-v5.json" "$RUNTIME_PROVIDER_ROOT" <<'PY'
import hashlib
import json
import pathlib
import sys

manifest = json.loads(pathlib.Path(sys.argv[1]).read_text(encoding="utf-8"))
root = pathlib.Path(sys.argv[2])
if manifest.get("schemaVersion") != 5 or manifest.get("upgradeId") != "world-builder-installed-client-source-upgrade-v5":
    raise SystemExit("FAIL: Installed client source-upgrade identity drifted")
source_files = manifest.get("sourceFiles", [])
if len(source_files) != 11:
    raise SystemExit("FAIL: Installed client source-upgrade file set drifted")
for entry in source_files:
    relative = entry.get("destinationRelativePath", "")
    source = root / "Client_Base" / relative
    if not source.is_file() or hashlib.sha256(source.read_bytes()).hexdigest() != entry.get("sha256"):
        raise SystemExit(f"FAIL: Installed client source-upgrade hash drifted: {relative}")
    policy = entry.get("replacementPolicy")
    if policy not in {"add-or-exact", "replace-supported-historical"}:
        raise SystemExit(f"FAIL: Installed client source-upgrade policy drifted: {relative}")
    before = entry.get("supportedBeforeSha256")
    if policy == "replace-supported-historical":
        if (
            not isinstance(before, list)
            or not 1 <= len(before) <= 8
            or len(set(before)) != len(before)
            or any(not isinstance(value, str) or len(value) != 64 for value in before)
        ):
            raise SystemExit(f"FAIL: Historical client source boundary drifted: {relative}")
    elif before is not None:
        raise SystemExit(f"FAIL: Additive client source boundary drifted: {relative}")
if manifest.get("semanticTransforms") != [
    {
        "transformId": "world-builder-installed-login-world-bootstrap-v2",
        "destinationRelativePath": "src/orsc/mudclient.java",
    },
    {
        "transformId": "world-builder-unsigned-uniform-elevation-v1",
        "destinationRelativePath": "src/orsc/NativeLayeredTerrainSnapshot.java",
    },
]:
    raise SystemExit("FAIL: Installed client semantic transform drifted")
dependencies = manifest.get("dependencies")
if not isinstance(dependencies, list) or len(dependencies) != 1:
    raise SystemExit("FAIL: Installed client dependency set drifted")
dependency = dependencies[0]
dependency_source = root / dependency.get("sourceRelativePath", "")
if (
    dependency.get("destinationRelativePath") != "PC_Client/lib/json-20190722.jar"
    or dependency.get("replacementPolicy") != "add-or-exact"
    or not dependency_source.is_file()
    or hashlib.sha256(dependency_source.read_bytes()).hexdigest()
       != dependency.get("sha256")
):
    raise SystemExit("FAIL: Installed client JSON dependency drifted")
if manifest.get("buildPolicy") != "atomic-compile-target-client-before-run":
    raise SystemExit("FAIL: Installed client build policy drifted")
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
