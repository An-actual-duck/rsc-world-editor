#!/usr/bin/env python3
"""Adversarial temporary-fixture coverage for Phase 1 adaptive discovery."""

import hashlib
import gzip
import json
import os
import shutil
import subprocess
import struct
import tempfile
import unittest
import zipfile
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
SOURCE_ROOT = ROOT / "tools" / "world-builder" / "src"
MAIN_CLASS = "com.openrsc.worldbuilder.WorldBuilderCli"
DESCRIPTOR = "server/world-builder-capabilities.json"
CONFIG_ROOT = "server/world-builder-configs"
FAMILIES = ["boundary", "ground-item", "npc", "scenery"]


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def write_json(path: Path, value: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        json.dumps(value, sort_keys=True, indent=2, ensure_ascii=False) + "\n",
        encoding="utf-8",
    )


def point(x: int, y: int) -> dict:
    return {"x": x, "y": y}


def legacy_point(x: int, y: int) -> dict:
    return {"X": x, "Y": y}


def fixture_sprite_entry(color: int = 0x123456) -> bytes:
    return bytes((0, 1, 0)) + color.to_bytes(3, "big") + struct.pack(
        ">HHBhhHHB", 1, 1, 0, 0, 0, 1, 1, 0,
    )


def fixture_osar(subspaces: list[tuple[str, list[tuple[str, bytes]]]]) -> bytes:
    payload = bytearray((len(subspaces),))
    for subspace, entries in subspaces:
        payload.extend(subspace.encode("latin-1") + b"\0")
        payload.extend(len(entries).to_bytes(2, "big"))
        for name, entry in entries:
            payload.extend(name.encode("latin-1") + b"\0")
            payload.extend(entry)
    return gzip.compress(bytes(payload), mtime=0)


class AdaptiveDiscoveryTest(unittest.TestCase):
    fixture_osar = staticmethod(fixture_osar)
    fixture_sprite_entry = staticmethod(fixture_sprite_entry)

    @classmethod
    def setUpClass(cls):
        cls.compile_temp = tempfile.TemporaryDirectory(
            prefix="world-builder-adaptive-discovery-classes-"
        )
        cls.classes = Path(cls.compile_temp.name)
        sources = sorted(str(path) for path in SOURCE_ROOT.rglob("*.java"))
        subprocess.run(
            ["javac", "-source", "8", "-target", "8", "-d", str(cls.classes), *sources],
            check=True,
            cwd=ROOT,
        )
        harness_root = Path(cls.compile_temp.name) / "harness"
        harness_path = (
            harness_root
            / "com"
            / "openrsc"
            / "worldbuilder"
            / "AdaptiveDiscoveryDriftHarness.java"
        )
        harness_path.parent.mkdir(parents=True)
        harness_path.write_text(
            """
package com.openrsc.worldbuilder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

public final class AdaptiveDiscoveryDriftHarness {
    public static void main(final String[] args) throws Exception {
        final String mode = args[1];
        WorldBuilderAdaptiveDiscovery.Observer observer =
            new WorldBuilderAdaptiveDiscovery.Observer() {
                private int calls;
                @Override
                public void betweenVerificationPasses(Path root, int attempt)
                    throws Exception {
                    if ("throw-path".equals(mode)) {
                        throw new Exception(
                            root.resolve("server/maps/active").toString()
                                + ": callback failure");
                    }
					if ("profile-always".equals(mode)) {
						Path anchor = root.resolve(
							"Client_Base/Cache/video/Custom_Landscape.orsc");
						if (Files.exists(anchor)) {
							Files.delete(anchor);
						} else {
							Files.createDirectories(anchor.getParent());
							Files.write(anchor, new byte[] {0});
						}
						return;
					}
                    if ("always".equals(mode) || calls++ == 0) {
                        Files.write(
                            root.resolve("server/world-builder-configs/primary.json"),
                            "\\n".getBytes(StandardCharsets.UTF_8),
                            StandardOpenOption.APPEND);
                    }
                }
            };
        WorldBuilderAdaptiveDiscovery discovery = new WorldBuilderAdaptiveDiscovery(
            WorldBuilderLayoutAdapterRegistry.standard(), observer);
        WorldBuilderAdaptiveDiscoveryReport report = discovery.discover(
            Paths.get(args[0]), null);
        System.out.print(report.toJson());
        System.err.println(report.summary());
        if ("blocked".equals(report.status)) System.exit(3);
    }
}
""".strip()
            + "\n",
            encoding="utf-8",
        )
        subprocess.run(
            [
                "javac",
                "-source",
                "8",
                "-target",
                "8",
                "-cp",
                str(cls.classes),
                "-d",
                str(cls.classes),
                str(harness_path),
            ],
            check=True,
            cwd=ROOT,
        )

    @classmethod
    def tearDownClass(cls):
        cls.compile_temp.cleanup()

    def run_discovery(self, root: Path, *extra: str) -> subprocess.CompletedProcess:
        return subprocess.run(
            [
                "java",
                "-cp",
                str(self.classes),
                MAIN_CLASS,
                "discover-adaptive",
                "--target-root",
                str(root),
                *extra,
            ],
            cwd=ROOT,
            text=True,
            capture_output=True,
        )

    def run_drift(self, root: Path, mode: str) -> subprocess.CompletedProcess:
        return subprocess.run(
            [
                "java",
                "-cp",
                str(self.classes),
                "com.openrsc.worldbuilder.AdaptiveDiscoveryDriftHarness",
                str(root),
                mode,
            ],
            cwd=ROOT,
            text=True,
            capture_output=True,
        )

    @staticmethod
    def snapshot(root: Path) -> dict:
        result = {}
        for path in sorted(root.rglob("*")):
            relative = path.relative_to(root).as_posix()
            if path.is_symlink():
                result[relative] = ("link", os.readlink(path))
            elif path.is_dir():
                result[relative] = ("dir",)
            else:
                stat = path.stat()
                result[relative] = (
                    "file",
                    stat.st_size,
                    stat.st_mtime_ns,
                    hashlib.sha256(path.read_bytes()).hexdigest(),
                )
        return result

    def assert_read_only(self, root: Path, *extra: str) -> tuple[subprocess.CompletedProcess, dict]:
        before = self.snapshot(root)
        result = self.run_discovery(root, *extra)
        self.assertEqual(before, self.snapshot(root))
        return result, json.loads(result.stdout)

    def assert_blocked(self, root: Path, code: str, *extra: str) -> dict:
        result, report = self.assert_read_only(root, *extra)
        self.assertEqual(3, result.returncode, result.stderr)
        self.assertEqual("blocked", report["status"])
        self.assertEqual(code, report["issues"][0]["code"])
        self.assertFalse(report["issues"][0]["mutationOccurred"])
        self.assertTrue(report["issues"][0]["nextStep"])
        self.assertIn("Target discovery is blocked", result.stderr)
        return report

    def assert_root_is_display_only(
        self, root: Path, result: subprocess.CompletedProcess, report: dict
    ) -> None:
        root_display = str(root.resolve())
        self.assertEqual(root_display, report["targetRootDisplay"])
        portable_report = dict(report)
        portable_report.pop("targetRootDisplay")
        serialized = json.dumps(portable_report)
        for forbidden in {root_display, root_display.replace("\\", "/")}:
            self.assertNotIn(forbidden, serialized)
            self.assertNotIn(forbidden, result.stderr)

    def definition_catalog(self, catalog_id: str = "fixture-catalog-v1") -> dict:
        return {
            "schemaVersion": 1,
            "manifestType": "world-builder-definition-catalog",
            "catalogId": catalog_id,
            "tiles": [0, 1],
            "boundaries": [10, 11],
            "scenery": [20, 21],
            "npcs": [30, 31],
            "groundItems": [40, 41],
        }

    def write_package(
        self,
        package: Path,
        *,
        terrain_seed: int = 0,
        scenery_id: int = 20,
        world_space: str = "creator-space",
    ):
        terrain_relative = "terrain/creator/lp0/xp0-yp0.raw"
        placement_relative = "placements/creator/lp0.json"
        terrain = package / terrain_relative
        terrain.parent.mkdir(parents=True, exist_ok=True)
        terrain.write_bytes(bytes([terrain_seed]) * (48 * 48 * 10))
        placements = {
            "schemaVersion": 3,
            "encoding": "layered-world-placements-v3",
            "worldSpace": world_space,
            "level": 0,
            "boundaries": [
                {
                    "boundaryId": 10,
                    "direction": 0,
                    "placementId": "boundary-1",
                    "position": point(1, 1),
                }
            ],
            "groundItems": [
                {
                    "amount": 1,
                    "itemId": 40,
                    "placementId": "ground-item-1",
                    "position": point(2, 2),
                    "respawnSeconds": 30,
                }
            ],
            "npcs": [
                {
                    "npcId": 30,
                    "placementId": "npc-1",
                    "roamBounds": {"minimum": point(2, 2), "maximum": point(4, 4)},
                    "start": point(3, 3),
                }
            ],
            "scenery": [
                {
                    "direction": 2,
                    "placementId": "scenery-1",
                    "position": point(5, 5),
                    "sceneryId": scenery_id,
                }
            ],
        }
        placement = package / placement_relative
        write_json(placement, placements)
        manifest = {
            "schemaVersion": 1,
            "packageType": "layered-world",
            "packageId": "creator.example-world",
            "packageVersion": "9.2.0-alpha.1",
            "coordinateModel": "signed-layered-v1",
            "storage": {"presentationChunkSize": 24, "sectorSize": 48},
            "worldSpaces": [{"id": world_space, "kind": "static"}],
            "levels": [
                {
                    "level": 0,
                    "name": "Surface",
                    "role": "surface",
                    "worldSpace": world_space,
                }
            ],
            "terrainSectors": [
                {
                    "encoding": "raw-layered-sector-v1",
                    "level": 0,
                    "path": terrain_relative,
                    "sectorX": 0,
                    "sectorY": 0,
                    "sha256": sha256(terrain),
                    "worldSpace": world_space,
                }
            ],
            "placementSets": [
                {
                    "encoding": "layered-world-placements-v3",
                    "id": "creator-surface",
                    "level": 0,
                    "path": placement_relative,
                    "sha256": sha256(placement),
                    "worldSpace": world_space,
                }
            ],
        }
        write_json(package / "manifest.json", manifest)

    @staticmethod
    def write_archive(
        path: Path, seed: int = 0, entry_names: tuple[str, ...] = ("h0x48y37",)
    ):
        path.parent.mkdir(parents=True, exist_ok=True)
        raw = bytes((seed + index * 7) & 0xFF for index in range(48 * 48 * 10))
        with zipfile.ZipFile(path, "w", compression=zipfile.ZIP_DEFLATED) as archive:
            for entry_name in entry_names:
                info = zipfile.ZipInfo(entry_name, (2024, 1, 2, 3, 4, 6))
                info.compress_type = zipfile.ZIP_DEFLATED
                archive.writestr(info, raw)

    def write_common_evidence(self, root: Path, *, representation: str) -> dict:
        server_catalog = root / "server/evidence/definitions.json"
        client_catalog = root / "client/evidence/definitions.json"
        write_json(server_catalog, self.definition_catalog())
        client_catalog.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(server_catalog, client_catalog)
        catalog_hash = sha256(server_catalog)

        server_asset = root / "server/evidence/render-assets.bin"
        client_asset = root / "client/evidence/render-assets.bin"
        server_asset.parent.mkdir(parents=True, exist_ok=True)
        client_asset.parent.mkdir(parents=True, exist_ok=True)
        server_asset.write_bytes(b"matching-render-assets\n")
        shutil.copyfile(server_asset, client_asset)

        if representation == "layered":
            map_format = "signed-layered-v1"
            encodings = [1, 3]
        else:
            map_format = "legacy-packed-orsc-v1"
            encodings = [1]
        authoring = {
            "editExistingLevels": True,
            "createLevels": True,
            "placementFamilies": FAMILIES,
        }
        for side, build in (("server", "server-build-v9"), ("client", "client-build-v9")):
            evidence = {
                "schemaVersion": 1,
                "manifestType": "world-builder-runtime-evidence",
                "side": side,
                "buildId": build,
                "loaderId": "layered-loader-v2",
                "protocolId": "rsc-protocol-v9",
                "definitionCatalogId": "fixture-catalog-v1",
                "definitionCatalogSha256": catalog_hash,
                "mapFormatId": map_format,
                "packageSchemaId": "layered-world-package-v1",
                "encodingVersions": encodings,
                "authoring": authoring,
            }
            write_json(root / f"{side}/evidence/runtime.json", evidence)
        return {"catalog_hash": catalog_hash, "map_format": map_format, "encodings": encodings}

    def packed_placements(self, root: Path) -> list[dict]:
        sources = []

        def add(role, family, kind, payload):
            path = root / f"server/maps/placements/{role}.json"
            write_json(path, payload)
            sources.append(
                {
                    "role": role,
                    "family": family,
                    "kind": kind,
                    "compositionOrder": len(sources),
                    "encoding": f"packed-{family}-{'removals' if kind == 'removal' else 'locations'}-v1",
                    "relativePath": path.relative_to(root).as_posix(),
                }
            )

        add(
            "boundary-base",
            "boundary",
            "base",
            {
                "boundaries": [
                    {"id": 10, "pos": legacy_point(1, 1), "direction": 0},
                    {"id": 11, "pos": legacy_point(6, 6), "direction": 1},
                ]
            },
        )
        add(
            "boundary-removal",
            "boundary",
            "removal",
            {"boundary_removals": [{"pos": legacy_point(6, 6), "direction": 1}]},
        )
        add(
            "ground-base",
            "ground-item",
            "base",
            {
                "ground_items": [
                    {"id": 40, "pos": legacy_point(2, 2), "amount": 1, "respawn": 30},
                    {"id": 41, "pos": legacy_point(7, 7), "amount": 2, "respawn": 60},
                ]
            },
        )
        add(
            "ground-removal",
            "ground-item",
            "removal",
            {"ground_item_removals": [{"id": 41, "pos": legacy_point(7, 7)}]},
        )
        add(
            "npc-base",
            "npc",
            "base",
            {
                "npclocs": [
                    {
                        "id": 30,
                        "start": legacy_point(3, 3),
                        "min": legacy_point(2, 2),
                        "max": legacy_point(4, 4),
                    },
                    {
                        "id": 31,
                        "start": legacy_point(8, 8),
                        "min": legacy_point(8, 8),
                        "max": legacy_point(9, 9),
                    },
                ]
            },
        )
        add(
            "npc-removal",
            "npc",
            "removal",
            {
                "npc_removals": [
                    {
                        "id": 31,
                        "start": legacy_point(8, 8),
                        "min": legacy_point(8, 8),
                        "max": legacy_point(9, 9),
                    }
                ]
            },
        )
        add(
            "scenery-base",
            "scenery",
            "base",
            {
                "sceneries": [
                    {"id": 20, "pos": legacy_point(5, 5), "direction": 2},
                    {"id": 21, "pos": legacy_point(10, 10), "direction": 4},
                ]
            },
        )
        add(
            "scenery-removal",
            "scenery",
            "removal",
            {"scenery_removals": [{"pos": legacy_point(10, 10)}]},
        )
        return sources

    def descriptor_fixture(
        self,
        base: str,
        *,
        representation: str = "layered",
        world_space: str = "creator-space",
    ) -> Path:
        root = Path(base) / "target"
        common = self.write_common_evidence(root, representation=representation)
        if representation == "layered":
            server_map = root / "server/maps/active"
            client_map = root / "client/maps/active"
            self.write_package(server_map, world_space=world_space)
            client_map.parent.mkdir(parents=True, exist_ok=True)
            shutil.copytree(server_map, client_map)
            placements = []
            adapter_id = "generic-layered-v1"
            mutation_profile = "generic-layered-install-v1"
        else:
            server_map = root / "server/maps/active.orsc"
            client_map = root / "client/maps/active.orsc"
            self.write_archive(server_map)
            client_map.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(server_map, client_map)
            placements = self.packed_placements(root)
            adapter_id = "spoiled-milk-packed-v1"
            mutation_profile = "spoiled-milk-layered-install-v1"

        configuration = {
            "schemaVersion": 1,
            "manifestType": "world-builder-map-configuration",
            "configurationId": "primary",
            "active": True,
            "representation": representation,
            "serverMapRelativePath": server_map.relative_to(root).as_posix(),
            "clientMapRelativePath": client_map.relative_to(root).as_posix(),
            "serverRuntimeRelativePath": "server/evidence/runtime.json",
            "clientRuntimeRelativePath": "client/evidence/runtime.json",
            "serverDefinitionCatalogRelativePath": "server/evidence/definitions.json",
            "clientDefinitionCatalogRelativePath": "client/evidence/definitions.json",
            "assets": [
                {
                    "role": "library",
                    "serverRelativePath": "server/evidence/render-assets.bin",
                    "clientRelativePath": "client/evidence/render-assets.bin",
                }
            ],
            "placements": placements,
        }
        write_json(root / CONFIG_ROOT / "primary.json", configuration)

        source_roles = {
            "server-definition-catalog",
            "client-definition-catalog",
            "server-asset.library",
            "client-asset.library",
            "server-runtime",
            "client-runtime",
            "server-terrain" if representation == "packed" else "server-map-manifest",
            "client-terrain" if representation == "packed" else "client-map-manifest",
        }
        if representation == "layered":
            source_roles.update(
                {
                    "server-map-terrain",
                    "client-map-terrain",
                    "server-map-placement-set",
                    "client-map-placement-set",
                }
            )
        else:
            source_roles.update(f"placement.{source['role']}" for source in placements)
        descriptor = {
            "schemaVersion": 1,
            "manifestType": "world-builder-target-capability",
            "adapterId": adapter_id,
            "capabilityId": f"fixture-{representation}-capability-v1",
            "server": {"buildId": "server-build-v9", "loaderId": "layered-loader-v2"},
            "client": {
                "buildId": "client-build-v9",
                "protocolId": "rsc-protocol-v9",
                "loaderId": "layered-loader-v2",
            },
            "definitions": {
                "catalogId": "fixture-catalog-v1",
                "catalogSha256": common["catalog_hash"],
            },
            "map": {
                "formatId": common["map_format"],
                "packageSchemaId": "layered-world-package-v1",
                "encodingVersions": common["encodings"],
            },
            "discovery": {
                "configurationRoles": ["primary"],
                "sourceRepresentations": [representation],
                "sourceRoles": sorted(source_roles),
            },
            "authoring": {
                "editExistingLevels": True,
                "createLevels": True,
                "placementFamilies": FAMILIES,
            },
            "install": {
                "enabled": True,
                "serverRoles": ["layered-package"],
                "clientRoles": ["layered-package"],
                "configurationRoles": ["primary"],
                "mutationProfileId": mutation_profile,
                "offlineEvidence": ["pid-file", "port-bind"],
            },
        }
        write_json(root / DESCRIPTOR, descriptor)
        return root

    def legacy_fixture(self, base: str) -> Path:
        root = Path(base) / "legacy-target"
        config = root / "server/myworld.conf"
        config.parent.mkdir(parents=True)
        config.write_text(
            "client_version: 10046\n"
            "member_world: true\n"
            "based_map_data: 64\n"
            "want_myworld: true\n"
            "custom_landscape: true\n",
            encoding="utf-8",
        )
        server_map = root / "server/conf/server/data/Custom_Landscape.orsc"
        client_map = root / "Client_Base/Cache/video/Custom_Landscape.orsc"
        self.write_archive(server_map)
        client_map.parent.mkdir(parents=True)
        shutil.copyfile(server_map, client_map)
        overlays = {
            "BoundaryLocs.json": {
                "boundaries": [
                    {"id": 1, "pos": {"X": 10, "Y": 10}, "direction": 0}
                ]
            },
            "GroundItems.json": {
                "grounditems": [
                    {
                        "id": 7,
                        "pos": {"X": 11, "Y": 10},
                        "amount": 1,
                        "respawn": 60,
                    }
                ]
            },
            "NpcLocs.json": {
                "npclocs": [
                    {
                        "id": 1,
                        "start": {"X": 12, "Y": 10},
                        "min": {"X": 12, "Y": 10},
                        "max": {"X": 12, "Y": 10},
                    },
                    {
                        "id": 1,
                        "start": {"X": 12, "Y": 10},
                        "min": {"X": 12, "Y": 10},
                        "max": {"X": 12, "Y": 10},
                    },
                ]
            },
            "SceneryLocs.json": {
                "sceneries": [
                    {"id": 1, "pos": {"X": 13, "Y": 10}, "direction": 0}
                ]
            },
            "MyWorldSceneryLocs.json": {"sceneries": []},
            "MyWorldSceneryRemovals.json": {"scenery_removals": []},
            "MyWorldNpcLocs.json": {"npclocs": []},
            "MyWorldNpcRemovals.json": {"npc_removals": []},
        }
        for name, payload in overlays.items():
            write_json(root / "server/conf/server/defs/locs" / name, payload)
        definitions = root / "server/conf/server/defs"
        definitions.mkdir(parents=True, exist_ok=True)
        (definitions / "TileDef.xml").write_text(
            "<TileDef-array>"
            + "".join("<TileDef><colour>0</colour></TileDef>" for _ in range(32))
            + "</TileDef-array>\n",
            encoding="utf-8",
        )
        (definitions / "DoorDef.xml").write_text(
            "<DoorDef-array>"
            + "".join("<DoorDef><name>wall</name></DoorDef>" for _ in range(220))
            + "</DoorDef-array>\n",
            encoding="utf-8",
        )
        (definitions / "GameObjectDef.xml").write_text(
            "<GameObjectDef-array>"
            + "".join(
                "<GameObjectDef><name>fixture</name><width>1</width><height>1</height>"
                "</GameObjectDef>"
                for _ in range(55)
            )
            + "</GameObjectDef-array>\n",
            encoding="utf-8",
        )
        write_json(
            definitions / "NpcDefs.json",
            {"npcs": [{"id": 0, "name": "base-0"}, {"id": 1, "name": "base-1"}]},
        )
        write_json(
            definitions / "NpcDefsCustom.json",
            {"npcs": [{"id": 2, "name": "custom-appended"}]},
        )
        write_json(
            definitions / "NpcDefsMyWorld.json",
            {"npcs": [{"id": 846, "name": "target-owned-846"}]},
        )
        write_json(
            definitions / "NpcDefsPatch18.json",
            {"npcs": [{"id": 100, "name": "patched-100"}]},
        )
        write_json(definitions / "ItemDefs.json", {"item": [{"id": 0}, {"id": 7}]})
        write_json(definitions / "ItemDefsCustom.json", {"items": [{"id": 9000}]})
        write_json(definitions / "ItemDefsMyWorld.json", {"items": [{"id": 9001}]})
        write_json(definitions / "ItemDefsPatch18.json", {"items": [{"id": 9002}]})
        write_json(
            root / "server/conf/world-builder/item-visuals-v1.json",
            {
                "schemaVersion": 1,
                "manifestType": "world-builder-item-visual-evidence",
                "itemVisuals": [
                    {
                        "itemId": 9000,
                        "authenticSpriteId": None,
                        "customSpriteAssetRole": "asset.sprite.custom",
                        "customSpriteSubspace": "items",
                        "customSpriteEntry": "0",
                        "pictureMask": 0x336699,
                        "blueMask": 0x112233,
                    },
                    {
                        "itemId": 9001,
                        "authenticSpriteId": 417,
                        "customSpriteAssetRole": None,
                        "customSpriteSubspace": None,
                        "customSpriteEntry": None,
                        "pictureMask": -1,
                        "blueMask": 0,
                    },
                    {
                        "itemId": 9002,
                        "authenticSpriteId": None,
                        "customSpriteAssetRole": "asset.spritepack",
                        "customSpriteSubspace": "GUI",
                        "customSpriteEntry": "0",
                        "pictureMask": 0x445566,
                        "blueMask": -16776961,
                    },
                ],
            },
        )
        video = root / "Client_Base/Cache/video"
        video.mkdir(parents=True, exist_ok=True)
        (video / "library.orsc").write_bytes(b"fixture target library archive\n")
        (video / "models.orsc").write_bytes(b"fixture target model archive\n")
        with zipfile.ZipFile(video / "Authentic_Sprites.orsc", "w", zipfile.ZIP_DEFLATED) as archive:
            archive.writestr("sprites/base.bin", b"fixture authentic sprites")
        (video / "Custom_Sprites.osar").write_bytes(fixture_osar([
            ("items", [("0", fixture_sprite_entry(0x336699))]),
        ]))
        menus = video / "spritepacks/Menus.osar"
        menus.parent.mkdir(parents=True)
        menus.write_bytes(fixture_osar([
            ("GUI", [("0", fixture_sprite_entry(0x445566))]),
        ]))
        return root

    def test_no_server_is_standalone_and_deterministic_without_writes(self):
        with tempfile.TemporaryDirectory(prefix="adaptive-no-server-") as temp:
            root = Path(temp) / "empty-parent"
            root.mkdir()
            before = self.snapshot(root)
            first = self.run_discovery(root)
            second = self.run_discovery(root)
            self.assertEqual(0, first.returncode, first.stderr)
            self.assertEqual(first.stdout, second.stdout)
            self.assertEqual(before, self.snapshot(root))
            report = json.loads(first.stdout)
            self.assertEqual("standalone", report["status"])
            self.assertEqual("none", report["representation"])
            self.assertEqual("NO_SERVER", report["issues"][0]["code"])
            self.assertTrue(report["operations"]["createProject"])
            self.assertFalse(report["capability"]["resolved"])
            probe_checks = {
                check["checkId"]: check for check in report["checks"]
                if check["checkId"].startswith("format-profile-probe:")
            }
            self.assertEqual(
                {
                    "format-profile-probe:openrsc-packed-source-tree-v1",
                    "format-profile-probe:signed-layered-config-root-v1",
                },
                set(probe_checks),
            )
            self.assertEqual(
                {"not-applicable"},
                {check["status"] for check in probe_checks.values()},
            )

    def test_descriptor_layered_map_is_generic_complete_and_read_only(self):
        with tempfile.TemporaryDirectory(prefix="adaptive-layered-") as temp:
            root = self.descriptor_fixture(temp)
            result, report = self.assert_read_only(root)
            self.assertEqual(0, result.returncode, result.stderr)
            repeated, repeated_report = self.assert_read_only(root)
            self.assertEqual(result.stdout, repeated.stdout)
            self.assertEqual(
                report["discoveryFingerprintSha256"],
                repeated_report["discoveryFingerprintSha256"],
            )
            self.assertEqual("compatible", report["status"])
            self.assertEqual("layered", report["representation"])
            self.assertEqual("generic-layered-v1", report["capability"]["adapterId"])
            self.assertEqual("primary", report["selectedConfiguration"]["role"])
            roles = {item["role"] for item in report["files"]}
            for role in (
                "server-map-terrain",
                "client-map-terrain",
                "server-map-placement-set",
                "client-map-placement-set",
                "server-definition-catalog",
                "client-definition-catalog",
                "server-runtime",
                "client-runtime",
            ):
                self.assertIn(role, roles)
            placement = next(
                check for check in report["checks"] if check["checkId"] == "placement-validation"
            )
            self.assertIn("1 boundary", placement["observed"])
            self.assertIn("1 ground-item", placement["observed"])
            self.assertIn("1 NPC", placement["observed"])
            self.assertIn("1 scenery", placement["observed"])
            self.assertIn("strictly read-only", result.stderr)

    def test_descriptor_packed_map_inventories_composition_and_all_families(self):
        with tempfile.TemporaryDirectory(prefix="adaptive-packed-") as temp:
            root = self.descriptor_fixture(temp, representation="packed")
            result, report = self.assert_read_only(root)
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual("compatible", report["status"])
            self.assertEqual("packed", report["representation"])
            self.assertEqual("spoiled-milk-packed-v1", report["capability"]["adapterId"])
            roles = {item["role"] for item in report["files"]}
            for role in (
                "placement.boundary-base",
                "placement.boundary-removal",
                "placement.ground-base",
                "placement.ground-removal",
                "placement.npc-base",
                "placement.npc-removal",
                "placement.scenery-base",
                "placement.scenery-removal",
            ):
                self.assertIn(role, roles)
            placement = next(
                check for check in report["checks"] if check["checkId"] == "placement-validation"
            )
            self.assertIn("1 boundary", placement["observed"])
            self.assertIn("1 ground-item", placement["observed"])

    def test_narrow_legacy_fallback_probe_remains_read_only(self):
        with tempfile.TemporaryDirectory(prefix="adaptive-fallback-") as temp:
            root = self.legacy_fixture(temp)
            result, report = self.assert_read_only(root)
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual("compatible", report["status"])
            self.assertEqual("packed", report["representation"])
            self.assertFalse(report["descriptor"]["present"])
            self.assertEqual("spoiled-milk-packed-v1", report["capability"]["adapterId"])
            self.assertEqual(
                "spoiled-milk-packed-fallback-v1", report["capability"]["capabilityId"]
            )
            roles = {item["role"] for item in report["files"]}
            for role in (
                "server-definition.tile",
                "content.definition.boundary",
                "server-definition.scenery",
                "server-definition.npc.world",
                "content.definition.item.custom",
                "client-asset.library",
                "content.asset.model",
                "content.asset.sprite.authentic",
                "content.asset.sprite.custom",
                "content.asset.spritepack",
                "placement.boundary-base-source",
                "placement.ground-item-base-source",
                "placement.npc-base-source",
                "placement.scenery-base-source",
            ):
                self.assertIn(role, roles)

    def test_packed_fallback_inventories_only_configuration_active_auxiliary_scenery(self):
        with tempfile.TemporaryDirectory(prefix="adaptive-active-scenery-") as temp:
            root = self.legacy_fixture(temp)
            config = root / "server/myworld.conf"
            config.write_text(
                config.read_text(encoding="utf-8")
                + "location_data: 2\n"
                + "want_runecraft: true\n"
                + "want_custom_quests: true\n"
                + "want_fixed_broken_mechanics: false\n",
                encoding="utf-8",
            )
            locations = root / "server/conf/server/defs/locs"
            write_json(
                locations / "SceneryLocsRunecraft.json",
                {"sceneries": [
                    {"id": 2, "pos": {"X": 14, "Y": 10}, "direction": 0}
                ]},
            )
            write_json(locations / "SceneryLocsOther.json", {"sceneries": []})
            write_json(
                locations / "SceneryLocsCustomQuest.json",
                {"sceneries": [
                    {"id": 2, "pos": {"X": 16, "Y": 10, "plane": 0},
                     "direction": 0, "legacyNote": "superseded"},
                    {"id": 4, "pos": {"X": 16, "Y": 10, "plane": 0},
                     "direction": 0, "legacyNote": "retained"},
                ]},
            )
            write_json(locations / "SceneryLocsExpansion.json", {"sceneries": []})
            write_json(
                locations / "SceneryLocsDiscontinued.json",
                {"sceneries": [
                    {"id": 3, "pos": {"X": 15, "Y": 10}, "direction": 0}
                ]},
            )

            result, report = self.assert_read_only(root)
            self.assertEqual(0, result.returncode, result.stderr)
            files = {item["role"]: item for item in report["files"]}
            self.assertTrue(
                files["placement.scenery-auxiliary-runecraft"]["present"]
            )
            self.assertTrue(files["placement.scenery-auxiliary-other"]["present"])
            self.assertTrue(
                files["placement.scenery-auxiliary-custom-quest"]["present"]
            )
            self.assertNotIn(
                "placement.scenery-auxiliary-discontinued", files
            )

            write_json(
                locations / "MyWorldSceneryLocs.json",
                {"sceneries": [
                    {"id": 2, "pos": {"X": 17, "Y": 10}, "direction": 0},
                    {"id": 4, "pos": {"X": 17, "Y": 10}, "direction": 0},
                ]},
            )
            self.assert_blocked(root, "MALFORMED_SERVER")

    def test_scenery_definition_semantics_are_validated_before_project_creation(self):
        malformed_records = (
            (
                "non-numeric-width",
                "<GameObjectDef><name>bad width</name><width>wide</width>"
                "<height>1</height><objectModel>tree</objectModel></GameObjectDef>",
                "malformed width",
            ),
            (
                "oversized-footprint",
                "<GameObjectDef><name>too wide</name><width>129</width>"
                "<height>1</height><objectModel>tree</objectModel></GameObjectDef>",
                "out-of-range width",
            ),
            (
                "duplicate-model",
                "<GameObjectDef><name>two models</name><width>1</width>"
                "<height>1</height><objectModel>tree</objectModel>"
                "<objectModel>rock</objectModel></GameObjectDef>",
                "repeats objectModel",
            ),
        )
        for label, record, expected in malformed_records:
            with self.subTest(label=label), tempfile.TemporaryDirectory(
                prefix="adaptive-scenery-semantics-"
            ) as temp:
                root = self.legacy_fixture(temp)
                path = root / "server/conf/server/defs/GameObjectDef.xml"
                path.write_text(
                    "<GameObjectDef-array>" + record + "</GameObjectDef-array>\n",
                    encoding="utf-8",
                )

                report = self.assert_blocked(root, "DEFINITION_MISMATCH")

                self.assertIn(expected, report["issues"][0]["observed"])
                self.assertEqual(
                    "definition.scenery", report["issues"][0]["relativePath"]
                )

    def test_floor_and_boundary_semantics_are_validated_before_project_creation(self):
        malformed_catalogs = (
            (
                "floor-non-numeric-colour",
                "TileDef.xml",
                "<TileDef-array><TileDef><colour>blue</colour>"
                "</TileDef></TileDef-array>\n",
                "definition.tile",
                "malformed colour",
            ),
            (
                "floor-duplicate-object-type",
                "TileDef.xml",
                "<TileDef-array><TileDef><objectType>0</objectType>"
                "<objectType>1</objectType></TileDef></TileDef-array>\n",
                "definition.tile",
                "repeats objectType",
            ),
            (
                "boundary-overflowing-height",
                "DoorDef.xml",
                "<DoorDef-array><DoorDef><name>wall</name>"
                "<modelVar1>2147483648</modelVar1></DoorDef></DoorDef-array>\n",
                "definition.boundary",
                "overflowing modelVar1",
            ),
            (
                "boundary-duplicate-material",
                "DoorDef.xml",
                "<DoorDef-array><DoorDef><name>wall</name>"
                "<modelVar2>1</modelVar2><modelVar2>2</modelVar2>"
                "</DoorDef></DoorDef-array>\n",
                "definition.boundary",
                "repeats modelVar2",
            ),
            (
                "boundary-oversized-command",
                "DoorDef.xml",
                "<DoorDef-array><DoorDef><name>wall</name><command1>"
                + "x" * 257
                + "</command1></DoorDef></DoorDef-array>\n",
                "definition.boundary",
                "oversized command1",
            ),
        )
        for label, filename, content, role, expected in malformed_catalogs:
            with self.subTest(label=label), tempfile.TemporaryDirectory(
                prefix="adaptive-terrain-definition-semantics-"
            ) as temp:
                root = self.legacy_fixture(temp)
                path = root / "server/conf/server/defs" / filename
                path.write_text(content, encoding="utf-8")

                report = self.assert_blocked(root, "DEFINITION_MISMATCH")

                self.assertEqual(role, report["issues"][0]["relativePath"])
                self.assertIn(expected, report["issues"][0]["observed"])

        with tempfile.TemporaryDirectory(
            prefix="adaptive-custom-terrain-definition-semantics-"
        ) as temp:
            root = self.legacy_fixture(temp)
            definitions = root / "server/conf/server/defs"
            tile_path = definitions / "TileDef.xml"
            tile_path.write_text(
                tile_path.read_text(encoding="utf-8").replace(
                    "<TileDef><colour>0</colour></TileDef>",
                    "<TileDef><colour>-2147483648</colour>"
                    "<unknown>2147483647</unknown>"
                    "<objectType>-7</objectType></TileDef>",
                    1,
                ),
                encoding="utf-8",
            )
            boundary_path = definitions / "DoorDef.xml"
            boundary_path.write_text(
                boundary_path.read_text(encoding="utf-8").replace(
                    "<DoorDef><name>wall</name></DoorDef>",
                    "<DoorDef><name>custom wall</name><description>custom</description>"
                    "<command1>Build</command1><command2>Remove</command2>"
                    "<modelVar1>2147483647</modelVar1>"
                    "<modelVar2>-2147483648</modelVar2>"
                    "<modelVar3>12345678</modelVar3>"
                    "<doorType>-3</doorType><unknown>9</unknown></DoorDef>",
                    1,
                ),
                encoding="utf-8",
            )

            result, report = self.assert_read_only(root)

            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual("compatible", report["status"])

        with tempfile.TemporaryDirectory(
            prefix="adaptive-terrain-definition-limit-"
        ) as temp:
            root = self.legacy_fixture(temp)
            path = root / "server/conf/server/defs/TileDef.xml"
            path.write_text(
                "<TileDef-array>"
                + "".join(
                    "<TileDef><colour>0</colour></TileDef>" for _ in range(256)
                )
                + "</TileDef-array>\n",
                encoding="utf-8",
            )

            report = self.assert_blocked(root, "CONTRACT_LIMIT_EXCEEDED")

            self.assertEqual("definition.tile", report["issues"][0]["relativePath"])
            self.assertIn("one-byte raw ID domain", report["issues"][0]["observed"])

    def test_packed_client_cache_layout_variants_are_selected_read_only(self):
        for video_root in ("client/Cache/video", "Cache/video"):
            with self.subTest(video_root=video_root), tempfile.TemporaryDirectory(
                prefix="adaptive-packed-cache-variant-"
            ) as temp:
                root = self.legacy_fixture(temp)
                original = root / "Client_Base/Cache/video"
                selected = root / video_root
                selected.parent.mkdir(parents=True, exist_ok=True)
                shutil.move(str(original), str(selected))
                before = self.snapshot(root)

                result, report = self.assert_read_only(root)

                self.assertEqual(0, result.returncode, result.stderr)
                self.assertEqual("compatible", report["status"])
                paths = {item["relativePath"] for item in report["files"]}
                self.assertIn(f"{video_root}/Custom_Landscape.orsc", paths)
                self.assertIn(f"{video_root}/models.orsc", paths)
                self.assertNotIn(
                    "Client_Base/Cache/video/Custom_Landscape.orsc", paths
                )
                profile = next(
                    check for check in report["checks"]
                    if check["checkId"] == "source-layout-profile"
                )
                expected_profile = (
                    "openrsc-packaged-client-cache-v1"
                    if video_root.startswith("client/")
                    else "openrsc-flat-client-cache-v1"
                )
                self.assertIn(expected_profile, profile["observed"])
                self.assertIn(video_root, profile["observed"])
                self.assertEqual(before, self.snapshot(root))

    def test_multiple_packed_client_cache_roots_are_ambiguous(self):
        with tempfile.TemporaryDirectory(prefix="adaptive-packed-cache-ambiguous-") as temp:
            root = self.legacy_fixture(temp)
            shutil.copytree(
                root / "Client_Base/Cache/video", root / "client/Cache/video"
            )
            before = self.snapshot(root)

            report = self.assert_blocked(root, "AMBIGUOUS_CONFIGURATION")

            self.assertIn("more than one client cache root", report["issues"][0]["observed"].lower())
            self.assertIn("Client_Base/Cache/video", report["issues"][0]["observed"])
            self.assertIn("client/Cache/video", report["issues"][0]["observed"])
            self.assertEqual(before, self.snapshot(root))

    def test_packed_server_content_layout_variants_are_selected_read_only(self):
        layouts = (
            ("server/data/definitions", "server/data"),
            ("server/data/defs", "server/data"),
            ("conf/server/defs", "conf/server/data"),
            ("data/definitions", "data"),
        )
        for definition_root, data_root in layouts:
            with self.subTest(
                definition_root=definition_root, data_root=data_root
            ), tempfile.TemporaryDirectory(
                prefix="adaptive-packed-server-variant-"
            ) as temp:
                root = self.legacy_fixture(temp)
                selected_definitions = root / definition_root
                selected_definitions.parent.mkdir(parents=True, exist_ok=True)
                shutil.move(
                    str(root / "server/conf/server/defs"),
                    str(selected_definitions),
                )
                selected_terrain = root / data_root / "Custom_Landscape.orsc"
                selected_terrain.parent.mkdir(parents=True, exist_ok=True)
                shutil.move(
                    str(root / "server/conf/server/data/Custom_Landscape.orsc"),
                    str(selected_terrain),
                )
                before = self.snapshot(root)

                result, report = self.assert_read_only(root)

                self.assertEqual(0, result.returncode, result.stderr)
                self.assertEqual("compatible", report["status"])
                paths = {item["relativePath"] for item in report["files"]}
                self.assertIn(f"{definition_root}/TileDef.xml", paths)
                self.assertIn(f"{definition_root}/locs/NpcLocs.json", paths)
                self.assertIn(f"{data_root}/Custom_Landscape.orsc", paths)
                self.assertNotIn("server/conf/server/defs/TileDef.xml", paths)
                profile = next(
                    check for check in report["checks"]
                    if check["checkId"] == "source-layout-profile"
                )
                self.assertIn(definition_root, profile["observed"])
                self.assertIn(data_root, profile["observed"])
                self.assertEqual(before, self.snapshot(root))

    def test_multiple_packed_server_definition_roots_are_ambiguous(self):
        with tempfile.TemporaryDirectory(prefix="adaptive-packed-server-ambiguous-") as temp:
            root = self.legacy_fixture(temp)
            duplicate = root / "server/data/definitions/TileDef.xml"
            duplicate.parent.mkdir(parents=True)
            shutil.copy2(
                root / "server/conf/server/defs/TileDef.xml", duplicate
            )
            before = self.snapshot(root)

            report = self.assert_blocked(root, "AMBIGUOUS_CONFIGURATION")

            self.assertIn(
                "more than one server definition root",
                report["issues"][0]["observed"].lower(),
            )
            self.assertIn("server/conf/server/defs", report["issues"][0]["observed"])
            self.assertIn("server/data/definitions", report["issues"][0]["observed"])
            self.assertEqual(before, self.snapshot(root))

    def test_multiple_packed_server_terrain_roots_are_ambiguous(self):
        with tempfile.TemporaryDirectory(prefix="adaptive-packed-terrain-ambiguous-") as temp:
            root = self.legacy_fixture(temp)
            duplicate = root / "server/data/Custom_Landscape.orsc"
            duplicate.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(
                root / "server/conf/server/data/Custom_Landscape.orsc", duplicate
            )
            before = self.snapshot(root)

            report = self.assert_blocked(root, "AMBIGUOUS_CONFIGURATION")

            self.assertIn(
                "more than one server terrain root",
                report["issues"][0]["observed"].lower(),
            )
            self.assertIn("server/conf/server/data", report["issues"][0]["observed"])
            self.assertIn("server/data", report["issues"][0]["observed"])
            self.assertEqual(before, self.snapshot(root))

    def test_packed_configuration_path_variants_are_selected_read_only(self):
        for configuration_path in (
            "myworld.conf",
            "conf/server/myworld.conf",
            "server/conf/server/myworld.conf",
        ):
            with self.subTest(configuration_path=configuration_path), tempfile.TemporaryDirectory(
                prefix="adaptive-packed-config-variant-"
            ) as temp:
                root = self.legacy_fixture(temp)
                selected = root / configuration_path
                selected.parent.mkdir(parents=True, exist_ok=True)
                shutil.move(str(root / "server/myworld.conf"), str(selected))
                before = self.snapshot(root)

                result, report = self.assert_read_only(root)

                self.assertEqual(0, result.returncode, result.stderr)
                self.assertEqual("compatible", report["status"])
                self.assertEqual(
                    configuration_path,
                    report["selectedConfiguration"]["relativePath"],
                )
                config = next(
                    item for item in report["files"]
                    if item["role"] == "server-runtime-config"
                )
                self.assertEqual(configuration_path, config["relativePath"])
                profile = next(
                    check for check in report["checks"]
                    if check["checkId"] == "source-layout-profile"
                )
                self.assertIn(configuration_path, profile["observed"])
                self.assertEqual(before, self.snapshot(root))

    def test_multiple_packed_configuration_paths_are_ambiguous(self):
        with tempfile.TemporaryDirectory(prefix="adaptive-packed-config-ambiguous-") as temp:
            root = self.legacy_fixture(temp)
            shutil.copy2(root / "server/myworld.conf", root / "myworld.conf")
            before = self.snapshot(root)

            report = self.assert_blocked(root, "AMBIGUOUS_CONFIGURATION")

            self.assertIn(
                "more than one supported server map configuration",
                report["issues"][0]["observed"].lower(),
            )
            self.assertIn("server/myworld.conf", report["issues"][0]["observed"])
            self.assertIn("myworld.conf", report["issues"][0]["observed"])
            self.assertEqual(
                ["packed-map-1", "packed-map-2"],
                [candidate["role"] for candidate in report["configurationCandidates"]],
            )
            selected_result, selected = self.assert_read_only(
                root, "--configuration-role", "packed-map-2"
            )
            self.assertEqual(0, selected_result.returncode, selected_result.stderr)
            self.assertEqual("server/myworld.conf",
                selected["selectedConfiguration"]["relativePath"])
            self.assertEqual(before, self.snapshot(root))

    def test_legacy_fallback_selects_base_placement_profile_from_configuration(self):
        with tempfile.TemporaryDirectory(prefix="adaptive-fallback-profile-") as temp:
            root = self.legacy_fixture(temp)
            config = root / "server/myworld.conf"
            config.write_text(
                config.read_text(encoding="utf-8").replace(
                    "based_map_data: 64", "based_map_data: 14"
                ),
                encoding="utf-8",
            )
            locs = root / "server/conf/server/defs/locs"
            for stem in ("BoundaryLocs", "GroundItems", "NpcLocs", "SceneryLocs"):
                source = locs / f"{stem}.json"
                (locs / f"{stem}14.json").write_bytes(source.read_bytes())

            result, report = self.assert_read_only(root)

            self.assertEqual(0, result.returncode, result.stderr)
            selected = {
                value["role"]: value["relativePath"]
                for value in report["files"]
                if value["role"].endswith("-base-source")
            }
            self.assertEqual(
                {
                    "placement.boundary-base-source":
                        "server/conf/server/defs/locs/BoundaryLocs14.json",
                    "placement.ground-item-base-source":
                        "server/conf/server/defs/locs/GroundItems14.json",
                    "placement.npc-base-source":
                        "server/conf/server/defs/locs/NpcLocs14.json",
                    "placement.scenery-base-source":
                        "server/conf/server/defs/locs/SceneryLocs14.json",
                },
                selected,
            )

    def test_legacy_fallback_refuses_partial_project_local_descriptor_evidence(self):
        with tempfile.TemporaryDirectory(prefix="adaptive-fallback-conflict-") as temp:
            root = self.legacy_fixture(temp)
            conflict = root / "server/world-builder-configs/primary.json"
            write_json(conflict, {"partial": True})
            before = self.snapshot(root)

            report = self.assert_blocked(root, "AMBIGUOUS_CONFIGURATION")

            self.assertIn("more than one", report["issues"][0]["observed"].lower())
            profile_checks = {
                check["checkId"]: check for check in report["checks"]
                if check["checkId"].startswith("format-profile-probe:")
            }
            self.assertEqual(2, len(profile_checks))
            self.assertEqual(
                {"failed", "passed"},
                {check["status"] for check in profile_checks.values()},
            )
            self.assertEqual(
                "passed",
                profile_checks[
                    "format-profile-probe:openrsc-packed-source-tree-v1"
                ]["status"],
            )
            self.assertEqual(
                "failed",
                profile_checks[
                    "format-profile-probe:signed-layered-config-root-v1"
                ]["status"],
            )
            self.assertIn(
                "server/world-builder-configs=present(signal)",
                profile_checks["format-profile-probe:signed-layered-config-root-v1"]["observed"],
            )
            self.assertIn(
                "server/world-builder-capabilities.json=absent(required)",
                profile_checks[
                    "format-profile-probe:signed-layered-config-root-v1"
                ]["observed"],
            )
            self.assertEqual(before, self.snapshot(root))

    def test_legacy_custom_content_closure_failures_are_read_only(self):
        cases = {}

        def missing(root: Path) -> None:
            (root / "Client_Base/Cache/video/Custom_Sprites.osar").unlink()

        cases["missing-asset"] = (missing, None)

        def malformed(root: Path) -> None:
            (root / "Client_Base/Cache/video/Custom_Sprites.osar").write_bytes(
                b"not a supported sprite archive\n"
            )

        cases["malformed-asset"] = (malformed, "UNSUPPORTED_FORMAT")

        def unsafe(root: Path) -> None:
            asset = root / "Client_Base/Cache/video/Authentic_Sprites.orsc"
            with zipfile.ZipFile(asset, "w", zipfile.ZIP_DEFLATED) as archive:
                archive.writestr("../escape.bin", b"unsafe")

        cases["unsafe-archive"] = (unsafe, "UNSAFE_PATH")

        def linked(root: Path) -> None:
            asset = root / "Client_Base/Cache/video/models.orsc"
            outside = root / "outside-models.orsc"
            outside.write_bytes(asset.read_bytes())
            asset.unlink()
            asset.symlink_to(outside)

        cases["linked-asset"] = (linked, "UNSAFE_PATH")

        def duplicate_definition(root: Path) -> None:
            write_json(
                root / "server/conf/server/defs/ItemDefsCustom.json",
                {"items": [{"id": 9000}, {"id": 9000}]},
            )

        cases["duplicate-definition"] = (duplicate_definition, "DEFINITION_MISMATCH")

        def malformed_visuals(root: Path) -> None:
            (root / "server/conf/world-builder/item-visuals-v1.json").write_bytes(
                b"{malformed\n"
            )

        cases["malformed-item-visuals"] = (malformed_visuals, "MALFORMED_JSON")

        def duplicate_visuals(root: Path) -> None:
            path = root / "server/conf/world-builder/item-visuals-v1.json"
            value = json.loads(path.read_text(encoding="utf-8"))
            value["itemVisuals"].insert(1, dict(value["itemVisuals"][0]))
            write_json(path, value)

        cases["duplicate-item-visuals"] = (duplicate_visuals, "DEFINITION_MISMATCH")

        for name, (mutation, expected_code) in cases.items():
            with self.subTest(name=name), tempfile.TemporaryDirectory(
                prefix=f"adaptive-content-{name}-"
            ) as temp:
                root = self.legacy_fixture(temp)
                mutation(root)
                before = self.snapshot(root)
                result = self.run_discovery(root)
                self.assertEqual(3, result.returncode, result.stdout + result.stderr)
                report = json.loads(result.stdout)
                self.assertEqual("blocked", report["status"])
                if expected_code is not None:
                    self.assertEqual(expected_code, report["issues"][0]["code"])
                self.assertEqual(before, self.snapshot(root))

    def test_missing_item_visual_evidence_is_inventoried_read_only_for_staged_migration(self):
        with tempfile.TemporaryDirectory(prefix="adaptive-visual-migration-discovery-") as temp:
            root = self.legacy_fixture(temp)
            evidence = root / "server/conf/world-builder/item-visuals-v1.json"
            evidence.unlink()
            before = self.snapshot(root)
            result = self.run_discovery(root)
            self.assertEqual(0, result.returncode, result.stderr)
            report = json.loads(result.stdout)
            self.assertEqual("compatible", report["status"])
            record = next(item for item in report["files"]
                if item["role"] == "content.metadata.item-visuals")
            self.assertFalse(record["present"])
            self.assertEqual(before, self.snapshot(root))

    def test_packed_sector_coordinate_aliases_are_rejected(self):
        with tempfile.TemporaryDirectory(prefix="adaptive-packed-alias-") as temp:
            root = self.descriptor_fixture(temp, representation="packed")
            server_map = root / "server/maps/active.orsc"
            client_map = root / "client/maps/active.orsc"
            self.write_archive(
                server_map, entry_names=("h0x48y37", "h0x048y037")
            )
            shutil.copyfile(server_map, client_map)

            report = self.assert_blocked(root, "UNSUPPORTED_FORMAT")
            self.assertIn("duplicate", report["issues"][0]["observed"].lower())
            self.assertIn("h0x048y037", report["issues"][0]["observed"])

    def test_custom_content_definition_ids_enforce_exact_runtime_domains(self):
        cases = {}

        def too_many_floors(root: Path) -> None:
            path = root / "server/conf/server/defs/TileDef.xml"
            path.write_text("<TileDef-array>" +
                "<TileDef><colour>0</colour></TileDef>" * 256 +
                "</TileDef-array>\n", encoding="utf-8")

        cases["floor-255-reserved"] = too_many_floors

        def too_many_boundaries(root: Path) -> None:
            path = root / "server/conf/server/defs/DoorDef.xml"
            path.write_text("<DoorDef-array>" +
                "<DoorDef><name>wall</name></DoorDef>" * 256 +
                "</DoorDef-array>\n", encoding="utf-8")

        cases["boundary-255-reserved"] = too_many_boundaries

        def item_outside_runtime(root: Path) -> None:
            write_json(root / "server/conf/server/defs/ItemDefsCustom.json",
                {"items": [{"id": 65536}]})

        cases["item-65536"] = item_outside_runtime

        for name, mutation in cases.items():
            with self.subTest(case=name), tempfile.TemporaryDirectory(
                prefix="adaptive-definition-bound-"
            ) as temp:
                root = self.legacy_fixture(temp)
                mutation(root)
                before = self.snapshot(root)
                result = self.run_discovery(root)
                self.assertEqual(3, result.returncode, result.stdout + result.stderr)
                self.assertEqual("blocked", json.loads(result.stdout)["status"])
                self.assertEqual(before, self.snapshot(root))

    def test_target_definition_reader_accepts_decimal_metadata(self):
        with tempfile.TemporaryDirectory(
            prefix="adaptive-definition-decimals-"
        ) as temp:
            root = self.legacy_fixture(temp)
            write_json(
                root / "server/conf/server/defs/NpcDefsMyWorld.json",
                {
                    "npcs": [
                        {
                            "id": 846,
                            "meleeDefenseMultiplier": 0.25,
                            "rangedDefenseMultiplier": 1.0,
                            "magicDefenseMultiplier": 1.25e-1,
                        }
                    ]
                },
            )
            write_json(
                root / "server/conf/server/defs/ItemDefsPatch18.json",
                {"item": [{"id": 2, "basePriceMultiplier": 1.25}]},
            )
            before = self.snapshot(root)

            result, report = self.assert_read_only(root)

            self.assertEqual(0, result.returncode, result.stdout + result.stderr)
            self.assertEqual("compatible", report["status"])
            self.assertEqual(before, self.snapshot(root))

    def test_malformed_legacy_errors_are_portable_and_path_independent(self):
        with tempfile.TemporaryDirectory(prefix="adaptive-legacy-portable-") as temp:
            first = self.legacy_fixture(str(Path(temp) / "first"))
            second = self.legacy_fixture(str(Path(temp) / "second"))
            relative = "server/conf/server/defs/locs/MyWorldSceneryLocs.json"
            malformed = {"sceneries": [{"id": "not-a-valid-placement"}]}
            write_json(first / relative, malformed)
            write_json(second / relative, malformed)

            reports = []
            for root in (first, second):
                result, report = self.assert_read_only(root)
                self.assertEqual(3, result.returncode, result.stderr)
                self.assertEqual("MALFORMED_SERVER", report["issues"][0]["code"])
                self.assertEqual(relative, report["issues"][0]["relativePath"])
                self.assert_root_is_display_only(root, result, report)
                reports.append(report)

            self.assertEqual(
                reports[0]["discoveryFingerprintSha256"],
                reports[1]["discoveryFingerprintSha256"],
            )

    def test_descriptor_filesystem_errors_are_portable_and_path_independent(self):
        with tempfile.TemporaryDirectory(prefix="adaptive-filesystem-portable-") as temp:
            roots = [
                self.descriptor_fixture(str(Path(temp) / name))
                for name in ("first", "second")
            ]
            reports = []
            for root in roots:
                maps = root / "server/maps"
                shutil.rmtree(maps)
                maps.write_bytes(b"regular file blocks the configured map directory\n")
                result, report = self.assert_read_only(root)
                self.assertEqual(3, result.returncode, result.stderr)
                self.assertEqual("MALFORMED_SERVER", report["issues"][0]["code"])
                self.assert_root_is_display_only(root, result, report)
                self.assertIn(
                    "server/maps/active", report["issues"][0]["observed"]
                )
                reports.append(report)

            self.assertEqual(
                reports[0]["discoveryFingerprintSha256"],
                reports[1]["discoveryFingerprintSha256"],
            )

    def test_zip_errors_are_portable_and_path_independent(self):
        with tempfile.TemporaryDirectory(prefix="adaptive-zip-portable-") as temp:
            roots = [
                self.descriptor_fixture(str(Path(temp) / name), representation="packed")
                for name in ("first", "second")
            ]
            reports = []
            for root in roots:
                malformed = b"identical malformed packed archive\n"
                (root / "server/maps/active.orsc").write_bytes(malformed)
                (root / "client/maps/active.orsc").write_bytes(malformed)
                result, report = self.assert_read_only(root)
                self.assertEqual(3, result.returncode, result.stderr)
                self.assertEqual("UNSUPPORTED_FORMAT", report["issues"][0]["code"])
                self.assert_root_is_display_only(root, result, report)
                reports.append(report)

            self.assertEqual(
                reports[0]["discoveryFingerprintSha256"],
                reports[1]["discoveryFingerprintSha256"],
            )

    def test_callback_errors_are_portable_and_path_independent(self):
        with tempfile.TemporaryDirectory(prefix="adaptive-callback-portable-") as temp:
            roots = [
                self.descriptor_fixture(str(Path(temp) / name))
                for name in ("first", "second")
            ]
            reports = []
            for root in roots:
                before = self.snapshot(root)
                result = self.run_drift(root, "throw-path")
                self.assertEqual(before, self.snapshot(root))
                self.assertEqual(3, result.returncode, result.stderr)
                report = json.loads(result.stdout)
                self.assertEqual("DISCOVERY_DRIFT", report["issues"][0]["code"])
                self.assert_root_is_display_only(root, result, report)
                self.assertIn(
                    "server/maps/active", report["issues"][0]["observed"]
                )
                reports.append(report)

            self.assertEqual(
                reports[0]["discoveryFingerprintSha256"],
                reports[1]["discoveryFingerprintSha256"],
            )

    def test_recognizable_broken_server_is_not_misclassified_as_standalone(self):
        with tempfile.TemporaryDirectory(prefix="adaptive-broken-") as temp:
            root = Path(temp) / "target"
            terrain = root / "server/conf/server/data/Custom_Landscape.orsc"
            self.write_archive(terrain)
            report = self.assert_blocked(root, "UNSUPPORTED_ADAPTER")
            self.assertFalse(report["operations"]["createProject"])
            packed_probe = next(
                check for check in report["checks"]
                if check["checkId"]
                == "format-profile-probe:openrsc-packed-source-tree-v1"
            )
            self.assertEqual("failed", packed_probe["status"])
            self.assertIn(
                "server/conf/server/data/Custom_Landscape.orsc=present(signal)",
                packed_probe["observed"],
            )
            self.assertIn(
                "active-configuration-candidate:server/myworld.conf=absent(signal)",
                packed_probe["observed"],
            )

    def test_profile_anchor_drift_is_detected_even_when_probe_state_is_unchanged(self):
        with tempfile.TemporaryDirectory(prefix="adaptive-profile-drift-") as temp:
            root = Path(temp) / "target"
            self.write_archive(
                root / "server/conf/server/data/Custom_Landscape.orsc"
            )
            result = self.run_drift(root, "profile-always")
            self.assertEqual(3, result.returncode, result.stderr)
            report = json.loads(result.stdout)
            self.assertEqual("DISCOVERY_DRIFT", report["issues"][0]["code"])

    def test_malformed_and_unknown_descriptors_fail_with_reports(self):
        with tempfile.TemporaryDirectory(prefix="adaptive-descriptor-") as temp:
            root = Path(temp) / "malformed"
            descriptor = root / DESCRIPTOR
            descriptor.parent.mkdir(parents=True)
            descriptor.write_text('{"schemaVersion":', encoding="utf-8")
            malformed = self.assert_blocked(root, "MALFORMED_JSON")
            self.assertTrue(malformed["descriptor"]["present"])

            unknown = self.descriptor_fixture(str(Path(temp) / "unknown"))
            document = json.loads((unknown / DESCRIPTOR).read_text(encoding="utf-8"))
            document["adapterId"] = "unregistered-adapter-v1"
            write_json(unknown / DESCRIPTOR, document)
            report = self.assert_blocked(unknown, "UNSUPPORTED_ADAPTER")
            self.assertFalse(report["capability"]["resolved"])
            self.assertEqual("unregistered-adapter-v1", report["issues"][0]["adapterId"])

    def test_multiple_active_configurations_block_and_explicit_choice_succeeds(self):
        with tempfile.TemporaryDirectory(prefix="adaptive-config-choice-") as temp:
            root = self.descriptor_fixture(temp)
            secondary = json.loads((root / CONFIG_ROOT / "primary.json").read_text())
            secondary["configurationId"] = "secondary"
            write_json(root / CONFIG_ROOT / "secondary.json", secondary)
            descriptor = json.loads((root / DESCRIPTOR).read_text())
            descriptor["discovery"]["configurationRoles"] = ["primary", "secondary"]
            descriptor["install"]["configurationRoles"] = ["primary", "secondary"]
            write_json(root / DESCRIPTOR, descriptor)

            blocked = self.assert_blocked(root, "AMBIGUOUS_CONFIGURATION")
            self.assertEqual(
                ["primary", "secondary"],
                [candidate["role"] for candidate in blocked["configurationCandidates"]],
            )
            result, report = self.assert_read_only(
                root, "--configuration-role", "secondary"
            )
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual("compatible", report["status"])
            self.assertEqual("secondary", report["selectedConfiguration"]["role"])

    def test_zero_or_malformed_configuration_candidates_fail_closed(self):
        with tempfile.TemporaryDirectory(prefix="adaptive-config-errors-") as temp:
            missing = self.descriptor_fixture(str(Path(temp) / "missing"))
            (missing / CONFIG_ROOT / "primary.json").unlink()
            report = self.assert_blocked(missing, "MALFORMED_SERVER")
            self.assertEqual([], report["configurationCandidates"])

            malformed = self.descriptor_fixture(str(Path(temp) / "malformed"))
            path = malformed / CONFIG_ROOT / "primary.json"
            document = json.loads(path.read_text())
            document["unexpected"] = True
            write_json(path, document)
            self.assert_blocked(malformed, "CONTRACT_KEYS_INVALID")

    def test_discovery_fingerprint_is_path_independent(self):
        with tempfile.TemporaryDirectory(prefix="adaptive-portable-identity-") as temp:
            first = self.descriptor_fixture(str(Path(temp) / "first"))
            second = self.descriptor_fixture(str(Path(temp) / "second"))
            first_result, first_report = self.assert_read_only(first)
            second_result, second_report = self.assert_read_only(second)
            self.assertEqual(0, first_result.returncode, first_result.stderr)
            self.assertEqual(0, second_result.returncode, second_result.stderr)
            self.assertNotEqual(
                first_report["targetRootDisplay"], second_report["targetRootDisplay"]
            )
            self.assertEqual(
                first_report["discoveryFingerprintSha256"],
                second_report["discoveryFingerprintSha256"],
            )

    def test_windows_invalid_and_traversal_paths_are_rejected_before_access(self):
        with tempfile.TemporaryDirectory(prefix="adaptive-unsafe-path-") as temp:
            root = self.descriptor_fixture(temp)
            outside = Path(temp) / "outside.orsc"
            outside.write_bytes(b"do not read or change")
            config_path = root / CONFIG_ROOT / "primary.json"
            config = json.loads(config_path.read_text())
            config["serverMapRelativePath"] = "../outside.orsc"
            write_json(config_path, config)
            outside_before = outside.read_bytes()
            report = self.assert_blocked(root, "UNSAFE_PATH")
            self.assertEqual(outside_before, outside.read_bytes())
            self.assertNotIn("<", report["issues"][0]["relativePath"])
            self.assertNotIn(":", report["issues"][0]["relativePath"])

            config["serverMapRelativePath"] = "server/maps/CON.json"
            write_json(config_path, config)
            self.assert_blocked(root, "UNSAFE_PATH")

    @unittest.skipUnless(hasattr(os, "symlink"), "symbolic links unavailable")
    def test_symlink_map_escape_is_rejected_without_touching_external_data(self):
        with tempfile.TemporaryDirectory(prefix="adaptive-link-") as temp:
            root = self.descriptor_fixture(temp)
            package = root / "server/maps/active"
            external = Path(temp) / "external-package"
            shutil.copytree(package, external)
            shutil.rmtree(package)
            package.symlink_to(external, target_is_directory=True)
            before = self.snapshot(external)
            self.assert_blocked(root, "UNSAFE_PATH")
            self.assertEqual(before, self.snapshot(external))

    def test_server_client_map_definition_and_runtime_mismatches_fail_closed(self):
        with tempfile.TemporaryDirectory(prefix="adaptive-mismatches-") as temp:
            map_root = self.descriptor_fixture(str(Path(temp) / "map"))
            client_package = map_root / "client/maps/active"
            shutil.rmtree(client_package)
            self.write_package(client_package, terrain_seed=1)
            self.assert_blocked(map_root, "MAP_MISMATCH")

            definition_root = self.descriptor_fixture(str(Path(temp) / "definition"))
            catalog = json.loads(
                (definition_root / "client/evidence/definitions.json").read_text()
            )
            catalog["scenery"].append(99)
            write_json(definition_root / "client/evidence/definitions.json", catalog)
            self.assert_blocked(definition_root, "DEFINITION_MISMATCH")

            runtime_root = self.descriptor_fixture(str(Path(temp) / "runtime"))
            runtime = json.loads((runtime_root / "client/evidence/runtime.json").read_text())
            runtime["buildId"] = "different-client-build"
            write_json(runtime_root / "client/evidence/runtime.json", runtime)
            self.assert_blocked(runtime_root, "CAPABILITY_MISMATCH")

    def test_undefined_layered_placement_and_malformed_packed_record_are_blocked(self):
        with tempfile.TemporaryDirectory(prefix="adaptive-placement-errors-") as temp:
            layered = self.descriptor_fixture(str(Path(temp) / "layered"))
            payload = layered / "server/maps/active/placements/creator/lp0.json"
            document = json.loads(payload.read_text())
            document["scenery"][0]["sceneryId"] = 999
            write_json(payload, document)
            manifest_path = layered / "server/maps/active/manifest.json"
            manifest = json.loads(manifest_path.read_text())
            manifest["placementSets"][0]["sha256"] = sha256(payload)
            write_json(manifest_path, manifest)
            self.assert_blocked(layered, "DEFINITION_MISMATCH")

            packed = self.descriptor_fixture(str(Path(temp) / "packed"), representation="packed")
            boundary = packed / "server/maps/placements/boundary-base.json"
            document = json.loads(boundary.read_text())
            document["boundaries"][0]["direction"] = 9
            write_json(boundary, document)
            self.assert_blocked(packed, "MALFORMED_SERVER")

    def test_single_mid_discovery_change_restarts_and_persistent_drift_blocks(self):
        with tempfile.TemporaryDirectory(prefix="adaptive-drift-") as temp:
            stable_after_restart = self.descriptor_fixture(str(Path(temp) / "once"))
            once = self.run_drift(stable_after_restart, "once")
            self.assertEqual(0, once.returncode, once.stderr)
            self.assertEqual("compatible", json.loads(once.stdout)["status"])

            persistent = self.descriptor_fixture(str(Path(temp) / "always"))
            always = self.run_drift(persistent, "always")
            self.assertEqual(3, always.returncode, always.stderr)
            report = json.loads(always.stdout)
            self.assertEqual("blocked", report["status"])
            self.assertEqual("DISCOVERY_DRIFT", report["issues"][0]["code"])
            self.assertFalse(report["issues"][0]["mutationOccurred"])


if __name__ == "__main__":
    unittest.main()
