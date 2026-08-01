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
    def test_product_lines_are_distinct_and_v2_is_release_ready(self) -> None:
        self.assertTrue((LEGACY / "README.txt").is_file())
        self.assertTrue((V2 / "README.txt").is_file())
        self.assertTrue((V2 / "RELEASE-READY").is_file())

        v2_readme = (V2 / "README.txt").read_text(encoding="utf-8")
        v2_start_sh = (V2 / "Start World Builder.sh").read_text(encoding="utf-8")
        v2_start_cmd = (V2 / "Start World Builder.cmd").read_text(encoding="utf-8")
        for text in (v2_readme, v2_start_sh, v2_start_cmd):
            self.assertIn("rsc-world-editor-v2", text)
        self.assertIn("v1.1.0", v2_readme)
        self.assertIn("Spoiled Milk World Builder 2", v2_readme)

        manager = (ROOT / "scripts" / "ai-manager.sh").read_text(encoding="utf-8")
        self.assertIn("legacy v1.1.0 release line is frozen", manager)
        self.assertIn("package-world-builder-v2-release.sh", manager)

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
            self.assertIn("Spoiled Milk World Builder 2", text)
        self.assertIn("RELEASE-READY", packager)
        self.assertIn("final cross-platform release validation", packager)
        self.assertIn("rsc-world-editor-v1", updater)
        self.assertIn("legacyWorkspaceMigration", updater)

        legacy_packager = (ROOT / "scripts/package-release.sh").read_text(
            encoding="utf-8"
        )
        legacy_updater = (ROOT / "release/updater/Update World Builder.sh").read_text(
            encoding="utf-8"
        )
        self.assertNotIn("Spoiled Milk World Builder 2", legacy_packager)
        self.assertNotIn("rsc-world-editor-v2", legacy_updater)

    def test_sync_contract_preserves_v1_and_tracks_v2(self) -> None:
        sync = (ROOT / "scripts" / "sync-from-core-framework.sh").read_text(
            encoding="utf-8"
        )
        parity = (ROOT / "scripts" / "check-core-parity.sh").read_text(
            encoding="utf-8"
        )
        for script in (sync, parity):
            self.assertIn("tools/world-builder", script)
            self.assertIn("release/world-builder-v2", script)
        self.assertNotIn(
            '"$CORE_ROOT/release/world-builder/" "$ROOT_DIR/release/world-builder/"',
            sync,
        )

        lock = (ROOT / "core-framework.lock").read_text(encoding="utf-8")
        match = re.search(r"^CORE_COMMIT=([0-9a-f]{40})$", lock, re.MULTILINE)
        self.assertIsNotNone(match)

    def test_layered_tooling_is_present_in_the_standalone_source(self) -> None:
        source = ROOT / "tools" / "world-builder"
        for relative in (
            "definition-label-overrides.json",
            "generate-definition-catalog.py",
            "src/com/openrsc/worldbuilder/WorldBuilderLayeredDraftWriter.java",
            "src/com/openrsc/worldbuilder/WorldBuilderLayeredPackage.java",
            "src/com/openrsc/worldbuilder/WorldBuilderLayeredReview.java",
            "src/com/openrsc/worldbuilder/WorldBuilderLayeredTerrainDraftJournal.java",
        ):
            self.assertTrue((source / relative).is_file(), relative)


if __name__ == "__main__":
    unittest.main()
