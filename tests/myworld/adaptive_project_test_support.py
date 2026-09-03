#!/usr/bin/env python3
"""Shared deterministic fixtures for adaptive project integration tests."""

import hashlib
import importlib.util
import json
import os
import sys
import zipfile
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
DISCOVERY_TEST = ROOT / "tests/myworld/test-world-builder-adaptive-discovery.py"
PACKED_CONVERSION_TEST = ROOT / "tests/myworld/test-world-builder-packed-conversion.py"
RUNTIME_ALLOWLIST = ROOT / "release/world-builder-v2/RUNTIME-ASSET-ALLOWLIST.txt"
CANONICAL_VOID_TILE = bytes((0, 0, 1, 8, 0, 0, 0, 0, 0, 0, 0))
CANONICAL_VOID_SECTOR = CANONICAL_VOID_TILE * (48 * 48)
VISIBLE_FLOOR_TILE = bytes((0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0))
STANDALONE_INITIAL_LOCATION = {"level": 0, "x": 120, "y": 648}


def standalone_seed_sector() -> bytes:
    result = bytearray(CANONICAL_VOID_SECTOR)
    center_x = STANDALONE_INITIAL_LOCATION["x"] % 48
    center_y = STANDALONE_INITIAL_LOCATION["y"] % 48
    for local_x in range(center_x - 1, center_x + 2):
        for local_y in range(center_y - 1, center_y + 2):
            offset = (local_x * 48 + local_y) * 11
            result[offset : offset + 11] = VISIBLE_FLOOR_TILE
    return bytes(result)


STANDALONE_SEED_SECTOR = standalone_seed_sector()
REQUIRED_LANGUAGE_BUNDLES = (
    "AuthenticMessages_en_UK.properties",
    "AuthenticMessages_en_UK_female.properties",
    "AuthenticMessages_en_UK_female_no_misgender.properties",
    "AuthenticMessages_en_UK_gender_neutral.properties",
    "AuthenticMessages_en_UK_male.properties",
    "CustomMessages_en_UK.properties",
    "CustomMessages_en_UK_female.properties",
    "CustomMessages_en_UK_gender_neutral.properties",
    "CustomMessages_en_UK_male.properties",
)
EMPTY_LANGUAGE_BUNDLES = {
    "CustomMessages_en_UK_female.properties",
    "CustomMessages_en_UK_gender_neutral.properties",
    "CustomMessages_en_UK_male.properties",
}
REQUIRED_DATABASE_PATCHES = (
    "2021_05_11_add_db_patches.sql",
    "2023_02_01_former_names.sql",
    "2026_05_14_add_summoning_skill.sql",
    "2026_08_03_add_blessing_skill.sql",
)


def _load_test_fixture(path: Path, module_name: str, class_name: str, method: str):
    spec = importlib.util.spec_from_file_location(module_name, path)
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return getattr(module, class_name)(method)


def load_discovery_fixtures():
    return _load_test_fixture(
        DISCOVERY_TEST,
        "world_builder_adaptive_discovery_fixtures",
        "AdaptiveDiscoveryTest",
        "test_no_server_is_standalone_and_deterministic_without_writes",
    )


def load_packed_fixtures():
    return _load_test_fixture(
        PACKED_CONVERSION_TEST,
        "world_builder_packed_conversion_fixtures",
        "PackedConversionTest",
        "test_deterministic_conversion_reverse_parity_and_portability",
    )


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def canonical_hash(value: dict) -> str:
    return hashlib.sha256(
        json.dumps(value, sort_keys=True, separators=(",", ":")).encode("utf-8")
    ).hexdigest()


def package_fingerprint(package: Path) -> str:
    digest = hashlib.sha256()
    for path in sorted(value for value in package.rglob("*") if value.is_file()):
        relative = path.relative_to(package).as_posix()
        for value in (relative, str(path.stat().st_size), sha256(path)):
            digest.update(value.encode("utf-8"))
            digest.update(b"\0")
    return digest.hexdigest()


def runtime_allowlist_records() -> tuple[tuple[str, str, str], ...]:
    records = []
    for raw in RUNTIME_ALLOWLIST.read_text(encoding="utf-8").splitlines():
        if not raw or raw.startswith("#"):
            continue
        fields = tuple(raw.split("\t"))
        if len(fields) != 3:
            raise AssertionError(f"Malformed fixture runtime allowlist line: {raw!r}")
        records.append(fields)
    return tuple(records)


def write_json(path: Path, value: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        json.dumps(value, sort_keys=True, indent=2, ensure_ascii=False) + "\n",
        encoding="utf-8",
    )


def installed_v1_capability() -> dict:
    return {
        "schemaVersion": 1,
        "manifestType": "world-builder-installed-runtime-capability",
        "capabilityId": "world-builder-installed-runtime-capability-v1",
        "profileId": "world-builder-installed",
        "loaderId": "generic-signed-layered-loader-v6-project-content-bundle-v3",
        "protocolId": "world-builder-native-layered-protocol-v2-u16-elevation",
        "mapFormatId": "signed-layered-v1",
        "packageSchemaId": "layered-world-package-v1",
        "coordinateModel": "signed-layered-v1",
        "encodingVersions": [1, 2, 3, 4],
        "placementFamilies": ["boundary", "ground-item", "npc", "scenery"],
        "activation": {
            "runtimeProfile": "world-builder-installed",
            "builderOnly": False,
            "requiresExactManifestSha256": True,
            "requiresExactInventorySha256": False,
            "replacesLegacyTerrain": True,
            "replacesLegacyPlacements": True,
            "requiredBooleanKeys": [],
            "requiredStringKeys": [],
        },
    }


def installed_v2_capability() -> dict:
    return {
        "schemaVersion": 1,
        "manifestType": "world-builder-installed-runtime-capability",
        "capabilityId": "world-builder-installed-runtime-capability-v2",
        "managedRuntimeBundleId": "world-builder-managed-runtime-current",
        "profileId": "world-builder-installed",
        "serverBuildId": "fixture-installed-server-upgrade-v6",
        "clientBuildId": "fixture-installed-client-source-v7",
        "clientBootstrapId": "world-builder-installed-client-profile-v1",
        "loaderId": "generic-signed-layered-loader-v7-blocking-base-color",
        "protocolId": "world-builder-native-layered-protocol-v2-u16-elevation",
        "mapFormatId": "signed-layered-v1",
        "packageSchemaId": "layered-world-package-v1",
        "coordinateModel": "signed-layered-v1",
        "encodingVersions": [1, 2, 3, 4],
        "placementFamilies": ["boundary", "ground-item", "npc", "scenery"],
        "runtimeArchives": {
            "serverRelativePath": (
                "server/world-builder-runtime/world-builder-managed-runtime.jar"
            ),
            "targetFallbackRelativePath": "server/core.jar",
            "clientNames": [
                "Client_Base/Open_RSC_Client.jar",
                "client/Open_RSC_Client.jar",
            ],
        },
        "clientSourceUpgrade": {
            "upgradeId": "world-builder-installed-client-source-upgrade-v5",
            "manifestRelativePath": (
                "server/conf/world-builder/installed-client-source-upgrade-v5.json"
            ),
            "buildPolicy": "atomic-compile-target-client-before-run",
        },
        "activation": {
            "runtimeProfile": "world-builder-installed",
            "builderOnly": False,
            "requiresExactManifestSha256": True,
            "requiresExactInventorySha256": False,
            "replacesLegacyTerrain": True,
            "replacesLegacyPlacements": True,
            "replacesLegacyClientBootstrap": True,
            "requiredBooleanKeys": [],
            "requiredStringKeys": [],
        },
    }


def host_runtime_capability() -> dict:
    return {
        "schemaVersion": 1,
        "manifestType": "world-builder-host-runtime-capability",
        "capabilityId": "world-builder-host-runtime-capability-v1",
        "integrationModel": "host-integrated-core-v1",
        "profileId": "world-builder-installed",
        "serverBuildId": "rsc-world-editor-runtime-host-server-v1",
        "clientBuildId": "rsc-world-editor-runtime-host-client-v1",
        "clientBootstrapId": "world-builder-installed-client-profile-v1",
        "serverBootstrapId": "world-builder-installed-server-profile-v1",
        "loaderId": "generic-signed-layered-loader-v7-blocking-base-color",
        "protocolId": "world-builder-native-layered-protocol-v2-u16-elevation",
        "mapFormatId": "signed-layered-v1",
        "packageSchemaId": "layered-world-package-v1",
        "coordinateModel": "signed-layered-v1",
        "encodingVersions": [1, 2, 3, 4],
        "placementFamilies": ["boundary", "ground-item", "npc", "scenery"],
        "activation": {
            "serverProfileRelativePath": (
                "server/world-builder-configs/installed-server.json"
            ),
            "clientProfileRelativePaths": [
                "Client_Base/world-builder-configs/installed-client.json",
                "client/world-builder-configs/installed-client.json",
            ],
            "requiresExactManifestSha256": True,
            "requiresExactInventorySha256": False,
            "replacesLegacyTerrain": False,
            "replacesLegacyPlacements": False,
            "ordinaryImportOwnership": [
                "content-addressed-map-package",
                "world-builder-map-selection",
                "world-builder-owned-activation-profile",
            ],
        },
    }


def managed_runtime_bundle() -> dict:
    return {
        "schemaVersion": 1,
        "manifestType": "world-builder-managed-runtime-bundle",
        "bundleId": "world-builder-managed-runtime-current",
        "runtimeContractId": "world-builder-installed-loader-v12",
        "profileId": "world-builder-installed",
        "loaderId": "generic-signed-layered-loader-v7-blocking-base-color",
        "protocolId": "world-builder-native-layered-protocol-v2-u16-elevation",
        "clientBootstrapId": "world-builder-installed-client-profile-v1",
        "components": [
            {
                "role": "server-runtime-upgrade",
                "sourceRelativePath": (
                    "server/world-builder-runtime/world-builder-managed-runtime.jar"
                ),
                "destinationKind": "target-root",
                "destinationRelativePath": (
                    "server/world-builder-runtime/world-builder-managed-runtime.jar"
                ),
                "replacementPolicy": "replace-with-verified-backup",
            },
            {
                "role": "client-source-upgrade",
                "sourceRelativePath": (
                    "server/conf/world-builder/installed-client-source-upgrade-v5.json"
                ),
                "destinationKind": "selected-client-root",
                "destinationRelativePath": "src",
                "replacementPolicy": "semantic-upgrade-with-verified-backup",
            },
            {
                "role": "runtime-capability",
                "sourceRelativePath": (
                    "server/conf/world-builder/installed-runtime-capability-v2.json"
                ),
                "destinationKind": "target-root",
                "destinationRelativePath": (
                    "server/conf/world-builder/installed-runtime-capability-v2.json"
                ),
                "replacementPolicy": "replace-with-verified-backup",
            },
        ],
        "legacyCapabilityPaths": [
            "server/conf/world-builder/installed-runtime-capability-v1.json"
        ],
        "preservedTargetState": [
            "server configuration values not owned by World Builder",
            "server plugins and game content",
            "definitions and custom assets",
            "databases and player data",
            "credentials, keys, logs, and backups",
        ],
        "serverUpgradeBoundary": [
            "World Builder map loading, signed layered coordinates, placements, collision, and protocol integration",
            "target-owned gameplay, plugins, definitions, database implementations, and third-party libraries remain authoritative",
        ],
        "clientUpgradeBoundary": [
            "target client protocol version and definitions remain authoritative",
            "the target client is compiled by its normal build before launch",
        ],
    }


ALPHA70_NATIVE_CHUNK_SOURCE = b"""package orsc;
public final class NativeLayeredTerrainChunk {
    int targetSpecificChunkState;
    void materialize(byte[] tileBytes, int offset, com.openrsc.client.model.Tile tile) {
        tile.groundElevation = tileBytes[offset++] & 0xff;
    }
}
"""


FIXTURE_CLIENT_SOURCES = (
    (
        "client/world-builder-source/orsc/AdaptiveWorldBuilderClientSession.java",
        "src/orsc/AdaptiveWorldBuilderClientSession.java",
        b"package orsc;\npublic final class AdaptiveWorldBuilderClientSession {}\n",
        "add-or-exact",
        None,
    ),
    (
        "client/world-builder-source/orsc/ProjectContentBundle.java",
        "src/orsc/ProjectContentBundle.java",
        b"package orsc;\npublic final class ProjectContentBundle {}\n",
        "add-or-exact",
        None,
    ),
    (
        "client/world-builder-source/orsc/ProjectNpcAnimationRegistry.java",
        "src/orsc/ProjectNpcAnimationRegistry.java",
        b"package orsc;\npublic final class ProjectNpcAnimationRegistry {}\n",
        "add-or-exact",
        None,
    ),
    (
        "client/world-builder-source/orsc/NativeLayeredTerrainChunk.java",
        "src/orsc/NativeLayeredTerrainChunk.java",
        b"package orsc;\npublic final class NativeLayeredTerrainChunk { static final int LEGACY_TILE_WIRE_BYTES = 10; static final int WIDE_TILE_WIRE_BYTES = 11; int materializeWideElevation; }\n",
        "replace-supported-historical",
        ALPHA70_NATIVE_CHUNK_SOURCE,
    ),
    (
        "client/world-builder-source/orsc/NativeLayeredTerrainPacketDecoder.java",
        "src/orsc/NativeLayeredTerrainPacketDecoder.java",
        b"package orsc;\npublic final class NativeLayeredTerrainPacketDecoder { static final int LEGACY_TILE_WIRE_BYTES = 10; static final int WIDE_TILE_WIRE_BYTES = 11; int declaredEncodingVersion; }\n",
        "replace-supported-historical",
        b"package orsc;\npublic final class NativeLayeredTerrainPacketDecoder { static final int TILE_WIRE_BYTES = 10; int fixedWidthInflater; }\n",
    ),
    (
        "client/world-builder-source/com/openrsc/client/model/Tile.java",
        "src/com/openrsc/client/model/Tile.java",
        b"package com.openrsc.client.model;\npublic final class Tile { boolean blockingBaseColor; }\n",
        "replace-supported-historical",
        b"package com.openrsc.client.model;\npublic final class Tile { int targetTileExtension; }\n",
    ),
    (
        "client/world-builder-source/orsc/WorldBuilderClientProfile.java",
        "src/orsc/WorldBuilderClientProfile.java",
        b"package orsc;\npublic final class WorldBuilderClientProfile { boolean strictAdaptiveTerrain; }\n",
        "replace-supported-historical",
        b"package orsc;\npublic final class WorldBuilderClientProfile { boolean layeredTerrainDraft; }\n",
    ),
    (
        "client/world-builder-source/orsc/WorldBuilderInstalledClientProfile.java",
        "src/orsc/WorldBuilderInstalledClientProfile.java",
        b"package orsc;\npublic final class WorldBuilderInstalledClientProfile {}\n",
        "add-or-exact",
        None,
    ),
    (
        "client/world-builder-source/orsc/WorldBuilderTerrainBootstrap.java",
        "src/orsc/WorldBuilderTerrainBootstrap.java",
        b"package orsc;\npublic final class WorldBuilderTerrainBootstrap { static boolean isNativeOnly() { return true; } }\n",
        "add-or-exact",
        None,
    ),
    (
        "client/world-builder-source/orsc/WorldBuilderTerrainOverlay.java",
        "src/orsc/WorldBuilderTerrainOverlay.java",
        b"package orsc;\npublic final class WorldBuilderTerrainOverlay {}\n",
        "add-or-exact",
        None,
    ),
    (
        "client/world-builder-source/orsc/graphics/three/World.java",
        "src/orsc/graphics/three/World.java",
        b"package orsc.graphics.three;\npublic final class World { boolean currentNativeTerrain; }\n",
        "replace-supported-historical",
        b"package orsc.graphics.three;\npublic final class World { boolean historicalLayeredTerrain; }\n",
    ),
)
FIXTURE_JSON_DEPENDENCY = b"fixture pinned JSON dependency\n"
LEGACY_NATIVE_CHUNK_SOURCE = ALPHA70_NATIVE_CHUNK_SOURCE
LEGACY_NATIVE_SNAPSHOT_SOURCE = b"""package orsc;
public final class NativeLayeredTerrainSnapshot {
    int targetSpecificSnapshotState;
    void materialize(int elevation, com.openrsc.client.model.Tile tile) {
        tile.groundElevation = (byte) elevation;
    }
}
"""


def installed_client_source_roles() -> set[str]:
    return {
        f"runtime-compatibility-client-source-upgrade-{index}"
        for index in range(len(FIXTURE_CLIENT_SOURCES))
    }


def installed_client_transform_roles() -> set[str]:
    return {
        "runtime-compatibility-client-source-login-transform",
        "runtime-compatibility-client-source-native-uniform-elevation-transform",
    }


def installed_client_source_upgrade() -> dict:
    source_files = []
    for source, destination, current, policy, historical in FIXTURE_CLIENT_SOURCES:
        entry = {
            "sourceRelativePath": source,
            "destinationRelativePath": destination,
            "sha256": hashlib.sha256(current).hexdigest(),
            "replacementPolicy": policy,
        }
        if historical is not None:
            entry["supportedBeforeSha256"] = [hashlib.sha256(historical).hexdigest()]
        source_files.append(entry)
    return {
        "schemaVersion": 5,
        "manifestType": "world-builder-installed-client-source-upgrade",
        "upgradeId": "world-builder-installed-client-source-upgrade-v5",
        "clientBootstrapId": "world-builder-installed-client-profile-v1",
        "sourceFiles": source_files,
        "dependencies": [
            {
                "sourceRelativePath": "server/lib/json-20190722.jar",
                "destinationRelativePath": "PC_Client/lib/json-20190722.jar",
                "sha256": hashlib.sha256(FIXTURE_JSON_DEPENDENCY).hexdigest(),
                "replacementPolicy": "add-or-exact",
            }
        ],
        "semanticTransforms": [
            {
                "transformId": "world-builder-installed-login-world-bootstrap-v2",
                "destinationRelativePath": "src/orsc/mudclient.java",
            },
            {
                "transformId": "world-builder-unsigned-uniform-elevation-v1",
                "destinationRelativePath": "src/orsc/NativeLayeredTerrainSnapshot.java",
            },
        ],
        "buildPolicy": "atomic-compile-target-client-before-run",
        "preservedTargetAuthorities": [
            "client protocol version",
            "entity definitions and advertised limits",
            "custom client behavior and assets",
        ],
    }


def tree_bytes(root: Path, excluded: Path | None = None) -> dict[str, tuple]:
    result = {}
    if not root.exists():
        return result
    excluded_resolved = excluded.resolve() if excluded and excluded.exists() else None
    for path in sorted(root.rglob("*")):
        if excluded_resolved is not None:
            try:
                path.resolve().relative_to(excluded_resolved)
                continue
            except ValueError:
                pass
        relative = path.relative_to(root).as_posix()
        if path.is_symlink():
            result[relative] = ("link", os.readlink(path))
        elif path.is_dir():
            result[relative] = ("dir",)
        else:
            result[relative] = ("file", path.stat().st_size, sha256(path))
    return result


def make_runtime(root: Path, scenery_count: int = 4) -> Path:
    runtime = root / "builder-runtime"
    launcher = runtime / "launcher/world-builder-tools.jar"
    launcher.parent.mkdir(parents=True)
    launcher.write_bytes(b"content-neutral-fixture-tools\n")
    server = runtime / "server"
    client = runtime / "Client_Base"
    (server / "inc/sqlite").mkdir(parents=True)
    (server / "conf/world-builder").mkdir(parents=True)
    client.mkdir(parents=True)
    (server / "world-builder.conf").write_text(
        "server_name: Fixture World Builder\n"
        "server_bind_address: 0.0.0.0\n"
        "custom_landscape: true\n"
        "unbound_fixture_setting: must-not-enter-a-project\n",
        encoding="utf-8",
    )
    (server / "inc/sqlite/world_builder_seed.db").write_bytes(
        b"fixture-project-local-database-seed\n"
    )
    capability = {
        "schemaVersion": 2,
        "manifestType": "adaptive-world-builder-runtime-capability",
        "capabilityId": "adaptive-world-builder-runtime-capability-v2",
        "profileId": "adaptive-world-builder",
        "serverBuildId": "core-framework-adaptive-builder-server-v2",
        "clientBuildId": "core-framework-adaptive-builder-client-v2",
        "loaderId": "generic-signed-layered-loader-v2-u16-elevation",
        "authoringId": "generic-signed-layered-authoring-v2-u16-elevation",
        "definitionContractId": "world-builder-definition-catalog-binding-v1",
        "assetContractId": "world-builder-client-asset-binding-v1",
        "protocolId": "world-builder-native-layered-protocol-v2-u16-elevation",
        "effectiveCompositionId": "world-builder-effective-static-composition-v1",
        "mapFormatId": "signed-layered-v1",
        "packageSchemaId": "layered-world-package-v1",
        "coordinateModel": "signed-layered-v1",
        "terrainElevation": {
            "storageEncoding": "unsigned-16",
            "minimum": 0,
            "maximum": 65535,
            "renderScale": 3,
            "legacyV1Promotion": "unsigned-byte-lossless",
            "operations": ["absolute", "raise", "lower"],
            "atomicMultiTileBounds": True,
        },
        "encodingVersions": [1, 2, 3],
        "authoring": {
            "editExistingLevels": True,
            "createLevels": True,
            "placementFamilies": ["boundary", "ground-item", "npc", "scenery"],
        },
        "activation": {
            "worldBuilderMode": True,
            "adaptiveMode": True,
            "runtimeProfile": "adaptive-world-builder",
            "builderOnly": True,
            "loopbackOnly": True,
        },
        "canonicalVoidTile": [0, 1, 8, 0, 0, 0, 0, 0, 0, 0],
    }
    write_json(server / "conf/world-builder/adaptive-runtime-capability-v2.json", capability)
    write_json(
        server / "conf/world-builder/installed-runtime-capability-v2.json",
        installed_v2_capability(),
    )
    write_json(
        server / "conf/world-builder/installed-runtime-capability-v3.json",
        host_runtime_capability(),
    )
    write_json(
        server / "conf/world-builder/managed-runtime-bundle.json",
        managed_runtime_bundle(),
    )
    write_json(
        server / "conf/world-builder/installed-client-source-upgrade-v5.json",
        installed_client_source_upgrade(),
    )
    json_dependency = server / "lib/json-20190722.jar"
    json_dependency.parent.mkdir(parents=True, exist_ok=True)
    json_dependency.write_bytes(FIXTURE_JSON_DEPENDENCY)
    for source, _, current, _, _ in FIXTURE_CLIENT_SOURCES:
        path = client / Path(source).relative_to("client")
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(current)
    for name in REQUIRED_LANGUAGE_BUNDLES:
        path = server / "conf/server/languages" / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(b"" if name in EMPTY_LANGUAGE_BUNDLES else b"fixture bundle\n")
    for name in REQUIRED_DATABASE_PATCHES:
        path = server / "database/sqlite/patches" / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(b"-- fixture runtime migration\n")
    for _, destination, role in runtime_allowlist_records():
        path = runtime / destination
        if path.exists():
            continue
        path.parent.mkdir(parents=True, exist_ok=True)
        if path.name in EMPTY_LANGUAGE_BUNDLES:
            path.write_bytes(b"")
        elif path.name == "TileDef.xml":
            path.write_text(
                "<TileDef-array>"
                + "".join("<TileDef><colour>0</colour></TileDef>" for _ in range(10))
                + "</TileDef-array>\n",
                encoding="utf-8",
            )
        elif path.name == "DoorDef.xml":
            path.write_text(
                "<DoorDef-array>"
                + "".join("<DoorDef><name>wall</name></DoorDef>" for _ in range(3))
                + "</DoorDef-array>\n",
                encoding="utf-8",
            )
        elif path.name == "GameObjectDef.xml":
            path.write_text(
                "<GameObjectDef-array>"
                + "".join(
                    "<GameObjectDef><name>fixture</name>"
                    + (
                        "<width>2</width><height>1</height>"
                        if index == 54
                        else "<width>1</width><height>1</height>"
                    )
                    + "</GameObjectDef>"
                    for index in range(scenery_count)
                )
                + "</GameObjectDef-array>\n",
                encoding="utf-8",
            )
        elif path.name == "NpcDefs.json":
            write_json(path, {"npcs": [{"id": 0, "name": "base-0"}, {"id": 1, "name": "base-1"}]})
        elif path.name == "NpcDefsCustom.json":
            write_json(path, {"npcs": [{"id": 12, "name": "custom-12"}]})
        elif path.name == "NpcDefsMyWorld.json":
            write_json(path, {"npcs": [{"id": 99, "name": "inactive-world"}]})
        elif path.name == "NpcDefsPatch18.json":
            write_json(path, {"npcs": [{"id": 100, "name": "inactive-patch"}]})
        elif path.name == "ItemDefs.json":
            write_json(path, {"item": [{"id": 0}, {"id": 7}]})
        elif path.name == "ItemDefsCustom.json":
            write_json(path, {"items": [{"id": 42}]})
        elif path.name == "ItemDefsPatch18.json":
            write_json(path, {"items": [{"id": 99}]})
        elif path.name == "ItemDefsMyWorld.json":
            write_json(path, {"items": [{"id": 100}]})
        elif path.suffix == ".jar":
            with zipfile.ZipFile(path, "w") as archive:
                entry = zipfile.ZipInfo("fixture/Runtime.class", (2024, 1, 2, 3, 4, 6))
                archive.writestr(entry, b"fixture")
        else:
            path.write_bytes(("fixture " + role + "\n").encode("utf-8"))
    for jar in (server / "core.jar", server / "plugins.jar", client / "Open_RSC_Client.jar"):
        with zipfile.ZipFile(jar, "w") as archive:
            entry = zipfile.ZipInfo("META-INF/MANIFEST.MF", (2024, 1, 2, 3, 4, 6))
            archive.writestr(entry, "Manifest-Version: 1.0\n\n")
    return runtime


def change_working_terrain(project: Path) -> None:
    manifest_path = project / "working/layered-world/package/manifest.json"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    declaration = manifest["terrainSectors"][0]
    terrain = manifest_path.parent / declaration["path"]
    payload = bytearray(terrain.read_bytes())
    payload[0] ^= 1
    terrain.write_bytes(payload)
    declaration["sha256"] = sha256(terrain)
    write_json(manifest_path, manifest)
