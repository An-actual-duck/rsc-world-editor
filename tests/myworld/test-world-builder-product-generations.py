#!/usr/bin/env python3
"""Guard the frozen v1 and active World Builder 2 repository boundary."""

from __future__ import annotations

import re
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
LEGACY = ROOT / "release" / "world-builder"
V2 = ROOT / "release" / "world-builder-v2"
V2_UPDATER = ROOT / "release" / "updater-v2"
V2_PACKAGER = ROOT / "scripts" / "package-world-builder-v2-release.sh"


class WorldBuilderProductGenerationTest(unittest.TestCase):
    def test_product_lines_are_distinct_and_adaptive_v2_gate_is_exact(
        self,
    ) -> None:
        self.assertTrue((LEGACY / "README.txt").is_file())
        self.assertTrue((V2 / "README.txt").is_file())
        gate = (V2 / "RELEASE-READY").read_text(encoding="utf-8")
        self.assertIn("v0.2.0-alpha.1 adaptive production packaging accepted", gate)
        self.assertIn("aaab273663e96683bb0eeab773c7df7921e8cfd2", gate)
        self.assertIn("a2d00ee389761732ce5c8ffca07f430133aca4f5", gate)
        self.assertIn("candidate archives are evidence only", gate)

        v2_readme = (V2 / "README.txt").read_text(encoding="utf-8")
        v2_start_sh = (V2 / "Start World Builder.sh").read_text(encoding="utf-8")
        v2_start_cmd = (V2 / "Start World Builder.cmd").read_text(encoding="utf-8")
        for text in (v2_readme, v2_start_sh, v2_start_cmd):
            self.assertIn("rsc-world-editor-v2", text)
        self.assertIn("v1.1.0", v2_readme)
        self.assertIn("World Builder 2", v2_readme)
        self.assertNotIn("Spoiled Milk World Builder 2", v2_readme)

        manager = (ROOT / "scripts" / "ai-manager.sh").read_text(encoding="utf-8")
        self.assertIn("legacy v1.1.0 release line is frozen", manager)
        self.assertIn("package-world-builder-v2-release.sh", manager)
        self.assertIn("manager_candidate", manager)
        self.assertIn("--candidate-build", manager)

    def test_v2_release_machinery_is_separate_and_marker_gated(self) -> None:
        self.assertTrue(V2_PACKAGER.is_file())
        for relative in (
            "Start World Builder.sh",
            "Start World Builder.cmd",
            "Update World Builder.sh",
            "Update World Builder.cmd",
            "Update World Builder.ps1",
            "README-AUTO-UPDATE.txt",
        ):
            self.assertTrue((V2_UPDATER / relative).is_file(), relative)

        packager = V2_PACKAGER.read_text(encoding="utf-8")
        updater = (V2_UPDATER / "Update World Builder.sh").read_text(
            encoding="utf-8"
        )
        for text in (packager, updater):
            self.assertIn("rsc-world-editor-v2", text)
            self.assertIn("target-adaptive-v1", text)
        self.assertIn('PACKAGE_NAME="World Builder 2"', packager)
        self.assertIn('PACKAGE_NAME="World Builder 2"', updater)
        self.assertNotIn('PACKAGE_NAME="Spoiled Milk World Builder 2"', packager)
        self.assertNotIn('PACKAGE_NAME="Spoiled Milk World Builder 2"', updater)
        self.assertIn("RELEASE-READY", packager)
        self.assertIn("final cross-platform release validation", packager)
        self.assertIn("output/candidates/world-builder-v2", packager)
        self.assertIn("cannot be combined with --skip-build", packager)
        self.assertIn("rsc-world-editor-v1", updater)
        self.assertIn("legacyWorkspaceMigration", updater)

        legacy_packager = (ROOT / "scripts/package-release.sh").read_text(
            encoding="utf-8"
        )
        legacy_updater = (ROOT / "release/updater/Update World Builder.sh").read_text(
            encoding="utf-8"
        )
        self.assertNotIn("target-adaptive-v1", legacy_packager)
        self.assertNotIn("rsc-world-editor-v2", legacy_updater)

    def test_runtime_adoption_preserves_owned_source_and_v1(self) -> None:
        sync = (ROOT / "scripts" / "sync-from-runtime-provider.sh").read_text(
            encoding="utf-8"
        )
        parity = (ROOT / "scripts" / "check-runtime-provider-parity.sh").read_text(
            encoding="utf-8"
        )
        self.assertNotIn("rsync", sync)
        self.assertIn("No World Builder-owned source was copied", sync)
        self.assertIn("release/world-builder-v2", sync)
        self.assertNotIn('diff -qr "$ROOT_DIR/$relative"', parity)
        self.assertIn("adaptive-runtime-capability-v1.json", parity)
        self.assertIn("AdaptiveWorldBuilderRuntimeSession.java", parity)
        self.assertIn("release/world-builder-v2", parity)
        self.assertNotIn(
            '"$RUNTIME_PROVIDER_ROOT/release/world-builder/" "$ROOT_DIR/release/world-builder/"',
            sync,
        )

        lock = (ROOT / "runtime-provider.lock").read_text(encoding="utf-8")
        match = re.search(r"^RUNTIME_PROVIDER_COMMIT=([0-9a-f]{40})$", lock, re.MULTILINE)
        self.assertIsNotNone(match)

    def test_layered_tooling_is_present_in_the_standalone_source(self) -> None:
        source = ROOT / "tools" / "world-builder"
        for relative in (
            "definition-label-overrides.json",
            "generate-definition-catalog.py",
            "src/com/openrsc/worldbuilder/WorldBuilderCanonicalVoidTerrain.java",
            "src/com/openrsc/worldbuilder/WorldBuilderLayeredDraftWriter.java",
            "src/com/openrsc/worldbuilder/WorldBuilderLayeredPackage.java",
            "src/com/openrsc/worldbuilder/WorldBuilderLayeredReview.java",
            "src/com/openrsc/worldbuilder/WorldBuilderLayeredTerrainDraftJournal.java",
        ):
            self.assertTrue((source / relative).is_file(), relative)


if __name__ == "__main__":
    unittest.main()
