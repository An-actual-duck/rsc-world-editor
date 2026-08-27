#!/usr/bin/env python3
"""Focused immutable-evidence tests for the legacy landscape choice producer."""

from __future__ import annotations

import copy
import hashlib
import importlib.util
import json
from pathlib import Path
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]
SOURCE_ROOT = ROOT / "tools" / "world-builder" / "src"
CONTRACT_FIXTURES = ROOT / "tests" / "myworld" / "test-world-builder-adaptive-contracts.py"
DISCOVERY_FIXTURES = ROOT / "tests" / "myworld" / "test-world-builder-adaptive-discovery.py"
ZERO_HASH = "0" * 64

HARNESS = r"""
package com.openrsc.worldbuilder;

import java.nio.file.Paths;

public final class MapMigrationChoiceHarness {
    public static void main(String[] arguments) throws Exception {
        try {
            WorldBuilderMapMigrationChoice choice = WorldBuilderMapMigrationChoice.create(
                Paths.get(arguments[0]), Paths.get(arguments[1]),
                Boolean.parseBoolean(arguments[2]));
            System.out.print(choice.toJson());
        } catch (WorldBuilderContractException refusal) {
            System.err.println(refusal.code() + "|" + refusal.operation() + "|"
                + refusal.mutationOccurred() + "|" + refusal.nextStep() + "|"
                + refusal.getMessage());
            System.exit(3);
        }
    }
}
"""

DISCOVERY_HARNESS = r"""
package com.openrsc.worldbuilder;

import java.nio.file.Paths;

public final class LegacyLandscapeDiscoveryHarness {
    public static void main(String[] arguments) throws Exception {
        try {
            WorldBuilderAdaptiveDiscoveryReport report =
                new WorldBuilderLegacyLandscapeDiscovery().discover(
                    Paths.get(arguments[0]),
                    arguments.length > 1 ? arguments[1] : null);
            System.out.print(report.toJson());
        } catch (WorldBuilderContractException refusal) {
            System.err.println(refusal.code() + "|" + refusal.operation() + "|"
                + refusal.mutationOccurred() + "|" + refusal.nextStep() + "|"
                + refusal.getMessage());
            System.exit(3);
        }
    }
}
"""


def load_fixtures(name: str, path: Path):
    spec = importlib.util.spec_from_file_location(name, path)
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


FIXTURES = load_fixtures("adaptive_contract_fixtures", CONTRACT_FIXTURES)
DISCOVERY = load_fixtures("adaptive_discovery_fixtures", DISCOVERY_FIXTURES)


def bind_report(report: dict) -> None:
    display = report["targetRootDisplay"]
    report["targetRootDisplay"] = ""
    report["discoveryFingerprintSha256"] = ZERO_HASH
    canonical = json.dumps(
        report, ensure_ascii=False, separators=(",", ":"), sort_keys=True
    ).encode("utf-8")
    report["targetRootDisplay"] = display
    report["discoveryFingerprintSha256"] = hashlib.sha256(canonical).hexdigest()


def reports() -> tuple[dict, dict]:
    selected = FIXTURES.layered_discovery()
    legacy = FIXTURES.packed_discovery()
    legacy["files"] = [
        FIXTURES.file_record(
            "client-terrain",
            "Client_Base/Cache/video/Custom_Landscape.orsc",
            "e" * 64,
            4096,
        ),
        FIXTURES.file_record(
            "server-terrain",
            "server/conf/server/data/Custom_Landscape.orsc",
            "e" * 64,
            4096,
        ),
    ]
    bind_report(selected)
    bind_report(legacy)
    return selected, legacy


class MapMigrationChoiceTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.compile_temp = tempfile.TemporaryDirectory(
            prefix="world-builder-map-migration-choice-classes-"
        )
        cls.classes = Path(cls.compile_temp.name)
        sources = sorted(str(path) for path in SOURCE_ROOT.rglob("*.java"))
        subprocess.run(
            ["javac", "-source", "8", "-target", "8", "-d", str(cls.classes), *sources],
            check=True,
            cwd=ROOT,
            capture_output=True,
        )
        discovery_harness = (
            cls.classes / "harness/com/openrsc/worldbuilder/LegacyLandscapeDiscoveryHarness.java"
        )
        discovery_harness.parent.mkdir(parents=True)
        discovery_harness.write_text(DISCOVERY_HARNESS, encoding="utf-8")
        subprocess.run(
            [
                "javac", "-source", "8", "-target", "8", "-cp", str(cls.classes),
                "-d", str(cls.classes), str(discovery_harness),
            ],
            check=True,
            cwd=ROOT,
            capture_output=True,
        )
        harness = cls.classes / "harness/com/openrsc/worldbuilder/MapMigrationChoiceHarness.java"
        harness.parent.mkdir(parents=True, exist_ok=True)
        harness.write_text(HARNESS, encoding="utf-8")
        subprocess.run(
            [
                "javac", "-source", "8", "-target", "8", "-cp", str(cls.classes),
                "-d", str(cls.classes), str(harness),
            ],
            check=True,
            cwd=ROOT,
            capture_output=True,
        )

    @classmethod
    def tearDownClass(cls) -> None:
        cls.compile_temp.cleanup()

    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory(prefix="world-builder-map-migration-choice-")
        self.root = Path(self.temp.name)

    def tearDown(self) -> None:
        self.temp.cleanup()

    def run_choice(
        self, selected: dict, legacy: dict, retirement: bool = True
    ) -> tuple[subprocess.CompletedProcess[str], bytes, bytes]:
        selected_path = self.root / "selected.json"
        legacy_path = self.root / "legacy.json"
        selected_path.write_text(json.dumps(selected, indent=2) + "\n", encoding="utf-8")
        legacy_path.write_text(json.dumps(legacy, indent=2) + "\n", encoding="utf-8")
        selected_before = selected_path.read_bytes()
        legacy_before = legacy_path.read_bytes()
        result = subprocess.run(
            [
                "java", "-cp", str(self.classes),
                "com.openrsc.worldbuilder.MapMigrationChoiceHarness",
                str(selected_path), str(legacy_path), str(retirement).lower(),
            ],
            cwd=ROOT,
            text=True,
            capture_output=True,
        )
        return result, selected_before, legacy_before

    def legacy_target(self) -> Path:
        fixture = DISCOVERY.AdaptiveDiscoveryTest(
            methodName="test_narrow_legacy_fallback_probe_remains_read_only"
        )
        return fixture.legacy_fixture(str(self.root / "fixture"))

    def run_legacy_discovery(
        self, target: Path, *extra: str
    ) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            [
                "java", "-cp", str(self.classes),
                "com.openrsc.worldbuilder.LegacyLandscapeDiscoveryHarness",
                str(target), *extra,
            ],
            cwd=ROOT,
            text=True,
            capture_output=True,
        )

    def test_deterministic_portable_choice_binds_both_reports(self) -> None:
        selected, legacy = reports()
        first, selected_before, legacy_before = self.run_choice(selected, legacy)
        self.assertEqual(first.returncode, 0, first.stderr)
        choice = json.loads(first.stdout)
        self.assertEqual(
            choice["selectedTargetDiscoveryFingerprintSha256"],
            selected["discoveryFingerprintSha256"],
        )
        self.assertEqual(
            choice["legacyPackedDiscoveryFingerprintSha256"],
            legacy["discoveryFingerprintSha256"],
        )
        self.assertTrue(choice["retirementRequested"])
        self.assertEqual(choice["legacyTerrain"]["server"]["sha256"], "e" * 64)
        self.assertNotIn("/display/server", first.stdout)
        self.assertEqual((self.root / "selected.json").read_bytes(), selected_before)
        self.assertEqual((self.root / "legacy.json").read_bytes(), legacy_before)

        second, _, _ = self.run_choice(selected, legacy)
        self.assertEqual(second.returncode, 0, second.stderr)
        self.assertEqual(first.stdout, second.stdout)

    def test_retirement_intent_is_explicit(self) -> None:
        selected, legacy = reports()
        result, _, _ = self.run_choice(selected, legacy, retirement=False)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertFalse(json.loads(result.stdout)["retirementRequested"])

    def test_reports_must_name_same_target(self) -> None:
        selected, legacy = reports()
        legacy["targetRootDisplay"] = "/display/another-server"
        result, _, _ = self.run_choice(selected, legacy)
        self.assertEqual(result.returncode, 3)
        self.assertIn("do not identify the same target root", result.stderr)
        self.assertIn("|false|", result.stderr)

    def test_selected_report_must_be_layered(self) -> None:
        selected, legacy = reports()
        selected["representation"] = "packed"
        bind_report(selected)
        result, _, _ = self.run_choice(selected, legacy)
        self.assertEqual(result.returncode, 3)
        self.assertIn("not a compatible layered discovery", result.stderr)

    def test_edited_report_fingerprint_is_rejected(self) -> None:
        selected, legacy = reports()
        legacy["files"][0]["size"] = 8192
        result, _, _ = self.run_choice(selected, legacy)
        self.assertEqual(result.returncode, 3)
        self.assertIn("fingerprint does not match", result.stderr)

    def test_legacy_terrain_must_be_byte_identical(self) -> None:
        selected, legacy = reports()
        legacy["files"][0]["sha256"] = "d" * 64
        bind_report(legacy)
        result, _, _ = self.run_choice(selected, legacy)
        self.assertEqual(result.returncode, 3)
        self.assertIn("Custom_Landscape bytes differ", result.stderr)

    def test_both_legacy_terrain_roles_are_required(self) -> None:
        selected, legacy = reports()
        legacy["files"] = legacy["files"][:1]
        bind_report(legacy)
        result, _, _ = self.run_choice(selected, legacy)
        self.assertEqual(result.returncode, 3)
        self.assertIn("lacks present client/server terrain evidence", result.stderr)

    def test_non_custom_landscape_paths_are_rejected(self) -> None:
        selected, legacy = reports()
        legacy["files"][0]["relativePath"] = "Client_Base/Cache/video/Other.orsc"
        legacy["files"][1]["relativePath"] = "server/conf/server/data/Other.orsc"
        bind_report(legacy)
        result, _, _ = self.run_choice(selected, legacy)
        self.assertEqual(result.returncode, 3)
        self.assertIn("outside the compiled client/server landscape roots", result.stderr)

    def test_secondary_discovery_ignores_normal_target_reserved_evidence(self) -> None:
        target = self.legacy_target()
        descriptor = target / "server/world-builder-capabilities.json"
        configuration = target / "server/world-builder-configs/primary.json"
        descriptor.parent.mkdir(parents=True, exist_ok=True)
        configuration.parent.mkdir(parents=True, exist_ok=True)
        descriptor.write_text("{}\n", encoding="utf-8")
        configuration.write_text("{}\n", encoding="utf-8")
        before = DISCOVERY.AdaptiveDiscoveryTest.snapshot(target)

        first = self.run_legacy_discovery(target)
        second = self.run_legacy_discovery(target)
        self.assertEqual(first.returncode, 0, first.stderr)
        self.assertEqual(first.stdout, second.stdout)
        self.assertEqual(before, DISCOVERY.AdaptiveDiscoveryTest.snapshot(target))
        report = json.loads(first.stdout)
        self.assertEqual(report["representation"], "packed")
        self.assertFalse(report["descriptor"]["present"])
        self.assertEqual(
            report["capability"]["capabilityId"],
            "spoiled-milk-packed-fallback-v1",
        )
        paths = {entry["relativePath"] for entry in report["files"]}
        self.assertNotIn("server/world-builder-capabilities.json", paths)
        self.assertNotIn("server/world-builder-configs/primary.json", paths)
        self.assertIn(
            "server/conf/server/data/Custom_Landscape.orsc", paths
        )

    def test_secondary_discovery_requires_both_legacy_archives(self) -> None:
        target = self.legacy_target()
        (target / "Client_Base/Cache/video/Custom_Landscape.orsc").unlink()
        result = self.run_legacy_discovery(target)
        self.assertEqual(result.returncode, 3)
        self.assertIn("Custom_Landscape", result.stderr)
        self.assertIn("|false|", result.stderr)


if __name__ == "__main__":
    unittest.main()
