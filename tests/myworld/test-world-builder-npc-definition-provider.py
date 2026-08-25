#!/usr/bin/env python3
"""Neutral NPC provider normalization and placeholder regression coverage."""

import gzip
import hashlib
import json
import os
import struct
import subprocess
import tempfile
import unittest
import zipfile
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
CLASSES = ROOT / "output/world-builder-tools/classes"

HARNESS = r'''
package com.openrsc.worldbuilder;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

public final class NpcDefinitionProviderHarness {
    public static void main(String[] args) throws Exception {
        Map<String,Object> catalog = new LinkedHashMap<String,Object>();
        catalog.put("npcs", Arrays.<Object>asList(Long.valueOf(0L), Long.valueOf(2L)));
        try {
            WorldBuilderNpcDefinitionProvider.Result result =
                WorldBuilderNpcDefinitionProvider.consume(
                    Paths.get(args[1]), Paths.get(args[0]), catalog);
            Files.write(Paths.get(args[2]), result.customDefinitions);
            WorldBuilderNpcDefinitionProvider.writeReport(Paths.get(args[3]), result);
        } catch (WorldBuilderContractException refusal) {
            System.err.println(refusal.code() + ": " + refusal.getMessage());
            System.exit(3);
        }
    }
}
'''


def definition(npc_id: int, name: str) -> dict:
    value = {
        "id": npc_id, "name": name, "description": "provider NPC",
        "command": "", "command2": "", "attack": 1, "strength": 1,
        "hits": 1, "defense": 1, "ranged": False, "combatlvl": 1,
        "isMembers": 0, "attackable": 0, "aggressive": 0,
        "respawnTime": 30, "hairColour": 0, "topColour": 0,
        "bottomColour": 0, "skinColour": 0, "camera1": 145,
        "camera2": 220, "walkModel": 1, "combatModel": 1,
        "combatSprite": 0, "roundMode": 0,
        "meleeDefenseMultiplier": 1.25,
    }
    for index in range(1, 13):
        value[f"sprites{index}"] = 0 if index == 1 else -1
    return value


def producer_definition(npc_id: int, name: str) -> dict:
    return {
        "npcId": npc_id, "definitionId": npc_id, "name": name,
        "description": "authoritative producer NPC", "command1": "Talk-to",
        "command2": None, "attack": 4, "strength": 5, "hits": 6,
        "defense": 7, "attackable": False,
        "spriteAnimationIds": [0] + [-1] * 11,
        "hairColour": 10, "topColour": 11, "bottomColour": 12,
        "skinColour": 13, "cameraWidth": 145, "cameraHeight": 220,
        "walkModel": 6, "combatModel": 6, "combatSprite": 5,
    }


def write_json(path: Path, value: object) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def sprite_entry(frame_count: int) -> bytes:
    payload = bytearray((0, frame_count, 0, 0x12, 0x34, 0x56))
    for _ in range(frame_count):
        payload.extend(struct.pack(">HHBhhHHB", 1, 1, 0, 0, 0, 1, 1, 0))
    return bytes(payload)


def sprite_archive(subspace: str, entry: str, payload: bytes) -> bytes:
    raw = (
        bytes((1,)) + subspace.encode("latin-1") + b"\0"
        + (1).to_bytes(2, "big") + entry.encode("latin-1") + b"\0" + payload
    )
    return gzip.compress(raw, mtime=0)


class NpcDefinitionProviderTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        subprocess.run([str(ROOT / "scripts/build-tools.sh")], check=True)
        cls.temp = tempfile.TemporaryDirectory(prefix="npc-provider-harness-")
        source = Path(cls.temp.name) / "NpcDefinitionProviderHarness.java"
        source.write_text(HARNESS.strip() + "\n", encoding="utf-8")
        cls.classes = Path(cls.temp.name) / "classes"
        cls.classes.mkdir()
        subprocess.run([
            "javac", "-encoding", "UTF-8", "-cp", str(CLASSES),
            "-d", str(cls.classes), str(source),
        ], check=True)

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()

    def fixture(self, base: Path) -> tuple[Path, Path]:
        target = base / "target"
        definitions = target / "server/conf/server/defs"
        write_json(definitions / "NpcDefs.json", {"npcs": [definition(0, "Base")]})
        write_json(definitions / "NpcDefsCustom.json", {"npcs": []})
        write_json(definitions / "NpcDefsMyWorld.json", {"npcs": []})
        write_json(definitions / "NpcDefsPatch18.json", {"npcs": []})
        write_json(definitions / "locs/MyWorldNpcLocs.json", {
            "npclocs": [{
                "id": 2, "start": {"X": 10, "Y": 10},
                "min": {"X": 10, "Y": 10}, "max": {"X": 10, "Y": 10},
            }]
        })
        provider = base / "provider"
        provider.mkdir()
        selected = provider / "item-visuals.json"
        selected.write_text("{}\n", encoding="utf-8")
        return target, selected

    def consume(self, target: Path, selected: Path, stage: Path) -> tuple[dict, dict]:
        output = stage.parent / "custom.json"
        stage.mkdir()
        classpath = os.pathsep.join((str(self.classes), str(CLASSES)))
        result = subprocess.run([
            "java", "-cp", classpath,
            "com.openrsc.worldbuilder.NpcDefinitionProviderHarness",
            str(target), str(selected), str(output), str(stage),
        ], text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        report = json.loads((stage /
            "diagnostics/npc-definition-provider-warnings.json").read_text(encoding="utf-8"))
        return json.loads(output.read_text(encoding="utf-8")), report

    def consume_failure(self, target: Path, selected: Path, stage: Path) -> str:
        output = stage.parent / "custom.json"
        stage.mkdir()
        classpath = os.pathsep.join((str(self.classes), str(CLASSES)))
        result = subprocess.run([
            "java", "-cp", classpath,
            "com.openrsc.worldbuilder.NpcDefinitionProviderHarness",
            str(target), str(selected), str(output), str(stage),
        ], text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        self.assertNotEqual(0, result.returncode)
        return result.stdout + result.stderr

    def bind_package(self, selected: Path) -> None:
        rows = []
        for path, role in (
            (selected, "full-item-visual-manifest"),
            (selected.parent / "npc-definitions-v1.json",
             "full-npc-definition-manifest"),
        ):
            payload = path.read_bytes()
            rows.append({
                "path": path.name, "role": role, "size": len(payload),
                "sha256": hashlib.sha256(payload).hexdigest(),
            })
        write_json(selected.parent / "package-manifest-v1.json", {
            "schemaVersion": 1,
            "manifestType": "world-builder-item-visual-provider-package",
            "providerDirectory": "world-builder-provider",
            "catalogSha256": "a" * 64,
            "files": rows,
        })

    def bind_producer_package(self, selected: Path) -> None:
        root = selected.parent
        roles = {
            "item-visuals.json": "full-item-visual-manifest",
            "npc-definitions-v1.json": "full-npc-definition-manifest",
            "assets/archives/Authentic_Sprites.orsc": "authentic-sprite-archive",
            "assets/archives/Custom_Sprites.osar": "custom-sprite-archive",
        }
        rows = []
        for relative in sorted(roles):
            payload = (root / relative).read_bytes()
            rows.append({
                "path": relative, "role": roles[relative], "size": len(payload),
                "sha256": hashlib.sha256(payload).hexdigest(),
            })
        write_json(root / "package-manifest-v1.json", {
            "schemaVersion": 1,
            "manifestType": "world-builder-item-visual-provider-package",
            "providerDirectory": "world-builder-provider",
            "catalogSha256": "a" * 64,
            "files": rows,
        })

    def producer_package(self, target: Path, selected: Path, npc_id: int = 2,
                         unresolved_animation: bool = False) -> None:
        root = selected.parent
        authentic = root / "assets/archives/Authentic_Sprites.orsc"
        custom = root / "assets/archives/Custom_Sprites.osar"
        authentic.parent.mkdir(parents=True)
        authentic_frames = {
            frame: (f"authentic-npc-frame-{frame}\n").encode("ascii")
            for frame in range(15)
        }
        with zipfile.ZipFile(authentic, "w", zipfile.ZIP_DEFLATED) as archive:
            for frame, payload in authentic_frames.items():
                archive.writestr(str(frame), payload)
        custom_entry = sprite_entry(15)
        custom.write_bytes(sprite_archive("npc", "fixture", custom_entry))
        target_video = target / "Client_Base/Cache/video"
        target_video.mkdir(parents=True)
        (target_video / "Authentic_Sprites.orsc").write_bytes(authentic.read_bytes())
        (target_video / "Custom_Sprites.osar").write_bytes(custom.read_bytes())
        authentic_hash = hashlib.sha256(authentic.read_bytes()).hexdigest()
        custom_hash = hashlib.sha256(custom.read_bytes()).hexdigest()
        npc = producer_definition(npc_id, "Neutral producer NPC")
        if unresolved_animation:
            npc["spriteAnimationIds"][0] = 1
        write_json(root / "npc-definitions-v1.json", {
            "schemaVersion": 1,
            "manifestType": "world-builder-npc-definitions",
            "provider": {
                "identity": "neutral-fixture", "definitionMode": "final",
                "finalClientNpcCount": npc_id + 1,
                "finalClientNpcCatalogSha256": "1" * 64,
                "sources": [
                    {
                        "role": "declarative-npc-registry",
                        "identity": name,
                        "sha256": hashlib.sha256((target /
                            f"server/conf/server/defs/{name}").read_bytes()).hexdigest(),
                    }
                    for name in ("NpcDefs.json", "NpcDefsCustom.json")
                ] + [{
                    "role": "authoritative-npc-placements",
                    "identity": "MyWorldNpcLocs.json",
                    "sha256": hashlib.sha256((target /
                        "server/conf/server/defs/locs/MyWorldNpcLocs.json")
                        .read_bytes()).hexdigest(),
                }],
            },
            "assetProviders": {
                "authenticSpriteArchive": {
                    "path": "assets/archives/Authentic_Sprites.orsc",
                    "sha256": authentic_hash, "numericEntryCount": 15,
                },
                "customSpriteArchive": {
                    "path": "assets/archives/Custom_Sprites.osar",
                    "sha256": custom_hash, "entryCount": 1,
                },
            },
            "selection": {
                "kind": "placed-extension-beyond-declarative-registry",
                "declarativeMaximumNpcId": 0, "placementCount": 1,
                "npcCount": 1, "placedNpcIds": [npc_id],
                "placementCountByNpcId": [{"npcId": npc_id, "count": 1}],
                "npcIdsSha256": "2" * 64, "definitionsSha256": "3" * 64,
                "animationsSha256": "4" * 64,
            },
            "npcDefinitions": [npc],
            "animationDefinitions": [{
                "animationId": 0, "name": "fixture", "category": "npc",
                "charColour": 0, "blueMask": 0, "genderModel": 0,
                "hasCombatFrames": False, "hasSpecialCombatFrames": False,
                "requiredFrameCount": 15,
                "customArchive": {
                    "subspace": "npc", "entry": "fixture", "frameCount": 15,
                    "entrySha256": hashlib.sha256(custom_entry).hexdigest(),
                    "spritepackOverrideKey": "npc:fixture",
                },
                "authenticArchive": {
                    "baseSpriteId": 0,
                    "frames": [
                        {"spriteId": frame,
                         "entrySha256": hashlib.sha256(payload).hexdigest()}
                        for frame, payload in authentic_frames.items()
                    ],
                },
            }],
        })
        self.bind_producer_package(selected)

    def test_exact_sparse_provider_record_is_resolved_with_only_gap_placeholder(self):
        with tempfile.TemporaryDirectory(prefix="npc-provider-resolved-") as temp:
            base = Path(temp)
            target, selected = self.fixture(base)
            write_json(selected.parent / "npc-definitions-v1.json", {
                "schemaVersion": 1,
                "manifestType": "world-builder-npc-definition-mapping",
                "npcs": [{"npcId": 2, "name": "Exact 846 fixture",
                          "definition": definition(2, "Exact 846 fixture")}],
            })
            custom, report = self.consume(target, selected, base / "stage")
            self.assertEqual(2, len(custom["npcs"]))
            self.assertEqual("[Missing NPC 1]", custom["npcs"][0]["name"])
            self.assertEqual("Exact 846 fixture", custom["npcs"][1]["name"])
            self.assertEqual([], report["warnings"])
            self.assertEqual(
                [{"npcId": 1, "status": "gap-placeholder"},
                 {"npcId": 2, "status": "resolved"}],
                report["npcs"],
            )

    def test_absent_or_malformed_provider_uses_deterministic_required_placeholder(self):
        for case in ("absent", "malformed"):
            with self.subTest(case=case), tempfile.TemporaryDirectory(
                    prefix="npc-provider-placeholder-") as temp:
                base = Path(temp)
                target, selected = self.fixture(base)
                if case == "malformed":
                    (selected.parent / "npc-definitions-v1.json").write_text(
                        "{not json}\n", encoding="utf-8")
                custom, report = self.consume(target, selected, base / "stage")
                self.assertEqual("[Missing NPC 2]", custom["npcs"][1]["name"])
                self.assertEqual(
                    [2],
                    [warning["npcId"] for warning in report["warnings"]],
                )
                self.assertEqual("NPC_DEFINITION_PLACEHOLDER",
                                 report["warnings"][0]["code"])

    def test_consumes_catalog_without_integer_only_overlay_reparse(self):
        with tempfile.TemporaryDirectory(prefix="npc-provider-derived-catalog-") as temp:
            base = Path(temp)
            target, selected = self.fixture(base)
            # Discovery owns definition parsing. Its derived catalog already
            # represents overlays with legitimate decimal combat metadata.
            write_json(target / "server/conf/server/defs/NpcDefsMyWorld.json", {
                "npcs": [definition(2, "Decimal overlay")],
            })
            custom, report = self.consume(target, selected, base / "stage")
            self.assertEqual("[Missing NPC 2]", custom["npcs"][1]["name"])
            self.assertEqual(1.25, custom["npcs"][1]["meleeDefenseMultiplier"])
            self.assertEqual([2], [row["npcId"] for row in report["warnings"]])

    def test_versioned_package_inventory_binds_exact_npc_manifest(self):
        with tempfile.TemporaryDirectory(prefix="npc-provider-package-") as temp:
            base = Path(temp)
            target, selected = self.fixture(base)
            write_json(selected.parent / "npc-definitions-v1.json", {
                "schemaVersion": 1,
                "manifestType": "world-builder-npc-definition-mapping",
                "npcs": [{"npcId": 2, "name": "Package NPC",
                          "definition": definition(2, "Package NPC")}],
            })
            self.bind_package(selected)
            custom, report = self.consume(target, selected, base / "stage")
            self.assertEqual("Package NPC", custom["npcs"][1]["name"])
            self.assertEqual([], report["warnings"])

            # Drift after the package inventory was signed must not be opened.
            npc_manifest = selected.parent / "npc-definitions-v1.json"
            npc_manifest.write_bytes(npc_manifest.read_bytes() + b" ")
            custom, report = self.consume(target, selected, base / "drift-stage")
            self.assertEqual("[Missing NPC 2]", custom["npcs"][1]["name"])
            self.assertEqual([2], [row["npcId"] for row in report["warnings"]])

    def test_rich_neutral_producer_contract_normalizes_authoritative_visuals(self):
        with tempfile.TemporaryDirectory(prefix="npc-provider-producer-") as temp:
            base = Path(temp)
            target, selected = self.fixture(base)
            self.producer_package(target, selected)
            target_archives_before = {
                path.name: path.read_bytes()
                for path in (target / "Client_Base/Cache/video").iterdir()
                if path.is_file()
            }
            custom, report = self.consume(target, selected, base / "stage")
            resolved = custom["npcs"][1]
            self.assertEqual("Neutral producer NPC", resolved["name"])
            self.assertEqual("Talk-to", resolved["command"])
            self.assertEqual(0, resolved["sprites1"])
            self.assertEqual(-1, resolved["sprites12"])
            self.assertEqual(145, resolved["camera1"])
            self.assertEqual([], report["warnings"])
            self.assertEqual("resolved", report["npcs"][1]["status"])
            self.assertEqual([{
                "animationId": 0,
                "category": "npc",
                "name": "fixture",
                "requiredFrameCount": 15,
                "customEntrySha256": hashlib.sha256(sprite_entry(15)).hexdigest(),
                "authenticBaseSpriteId": 0,
                "status": "resolved",
            }], report["animations"])
            repeated_custom, repeated_report = self.consume(
                target, selected, base / "repeat-stage"
            )
            self.assertEqual(custom, repeated_custom)
            self.assertEqual(report, repeated_report)
            self.assertEqual(target_archives_before, {
                path.name: path.read_bytes()
                for path in (target / "Client_Base/Cache/video").iterdir()
                if path.is_file()
            })

    def test_rich_animation_assets_and_renderer_shape_fail_soft_with_detail(self):
        def mutate_custom_hash(document: dict) -> None:
            document["animationDefinitions"][0]["customArchive"]["entrySha256"] = "f" * 64

        def mutate_authentic_hash(document: dict) -> None:
            document["animationDefinitions"][0]["authenticArchive"]["frames"][4][
                "entrySha256"
            ] = "e" * 64

        def mutate_renderer_count(document: dict) -> None:
            animation = document["animationDefinitions"][0]
            animation["requiredFrameCount"] = 14
            animation["customArchive"]["frameCount"] = 14
            animation["authenticArchive"]["frames"] = animation[
                "authenticArchive"
            ]["frames"][:14]

        def add_unreferenced_animation(document: dict) -> None:
            extra = json.loads(json.dumps(document["animationDefinitions"][0]))
            extra["animationId"] = 1
            document["animationDefinitions"].append(extra)

        cases = (
            ("custom-hash", mutate_custom_hash, "custom OSAR entry"),
            ("authentic-hash", mutate_authentic_hash, "authentic sprite 4"),
            ("renderer-count", mutate_renderer_count, "requires 15 renderer frames"),
            ("unreferenced", add_unreferenced_animation,
             "missing or unreferenced animation IDs"),
        )
        for label, mutate, expected in cases:
            with self.subTest(label=label), tempfile.TemporaryDirectory(
                prefix="npc-provider-animation-closure-"
            ) as temp:
                base = Path(temp)
                target, selected = self.fixture(base)
                self.producer_package(target, selected)
                manifest = selected.parent / "npc-definitions-v1.json"
                document = json.loads(manifest.read_text(encoding="utf-8"))
                mutate(document)
                write_json(manifest, document)
                self.bind_producer_package(selected)

                custom, report = self.consume(target, selected, base / "stage")

                self.assertEqual("[Missing NPC 2]", custom["npcs"][1]["name"])
                self.assertEqual([], report["animations"])
                self.assertEqual("NPC_ANIMATION_PLACEHOLDER",
                                 report["warnings"][0]["code"])
                self.assertIn(expected, report["warnings"][0]["message"])

    def test_rich_producer_with_unresolved_animation_falls_back_safely(self):
        with tempfile.TemporaryDirectory(prefix="npc-provider-animation-") as temp:
            base = Path(temp)
            target, selected = self.fixture(base)
            self.producer_package(target, selected, unresolved_animation=True)
            custom, report = self.consume(target, selected, base / "stage")
            self.assertEqual("[Missing NPC 2]", custom["npcs"][1]["name"])
            self.assertEqual([2], [row["npcId"] for row in report["warnings"]])

    def test_rich_provider_refuses_target_definition_asset_and_placement_drift(self):
        for case in ("definition", "asset", "placement"):
            with self.subTest(case=case), tempfile.TemporaryDirectory(
                    prefix="npc-provider-target-mismatch-") as temp:
                base = Path(temp)
                target, selected = self.fixture(base)
                self.producer_package(target, selected)
                if case == "definition":
                    path = target / "server/conf/server/defs/NpcDefsCustom.json"
                    path.write_bytes(path.read_bytes() + b" ")
                elif case == "asset":
                    path = target / "Client_Base/Cache/video/Custom_Sprites.osar"
                    path.write_bytes(path.read_bytes() + b"drift")
                else:
                    write_json(target / "server/conf/server/defs/locs/MyWorldNpcLocs.json", {
                        "npclocs": [
                            {"id": 2, "start": {"X": 10, "Y": 10},
                             "min": {"X": 10, "Y": 10}, "max": {"X": 10, "Y": 10}},
                            {"id": 3, "start": {"X": 11, "Y": 10},
                             "min": {"X": 11, "Y": 10}, "max": {"X": 11, "Y": 10}},
                        ]
                    })
                failure = self.consume_failure(target, selected, base / "stage")
                self.assertIn("Selected provider does not match this server revision", failure)
                self.assertIn("CAPABILITY_MISMATCH", failure)


if __name__ == "__main__":
    unittest.main()
