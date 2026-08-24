#!/usr/bin/env python3
"""Neutral NPC provider normalization and placeholder regression coverage."""

import json
import hashlib
import os
import subprocess
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
CLASSES = ROOT / "output/world-builder-tools/classes"

HARNESS = r'''
package com.openrsc.worldbuilder;

import java.nio.file.Files;
import java.nio.file.Paths;

public final class NpcDefinitionProviderHarness {
    public static void main(String[] args) throws Exception {
        WorldBuilderNpcDefinitionProvider.Result result =
            WorldBuilderNpcDefinitionProvider.consume(
                Paths.get(args[1]), Paths.get(args[0]));
        Files.write(Paths.get(args[2]), result.customDefinitions);
        WorldBuilderNpcDefinitionProvider.writeReport(Paths.get(args[3]), result);
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
    }
    for index in range(1, 13):
        value[f"sprites{index}"] = 0 if index == 1 else -1
    return value


def write_json(path: Path, value: object) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2, sort_keys=True) + "\n", encoding="utf-8")


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


if __name__ == "__main__":
    unittest.main()
