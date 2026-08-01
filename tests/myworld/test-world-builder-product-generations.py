#!/usr/bin/env python3
"""Guard the frozen v1 and active World Builder 2 repository boundary."""

from __future__ import annotations

import re
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
LEGACY = ROOT / "release" / "world-builder"
V2 = ROOT / "release" / "world-builder-v2"


class WorldBuilderProductGenerationTest(unittest.TestCase):
    def test_product_lines_are_distinct_and_v2_is_still_gated(self) -> None:
        self.assertTrue((LEGACY / "README.txt").is_file())
        self.assertTrue((V2 / "README.txt").is_file())
        self.assertFalse((V2 / "RELEASE-READY").exists())

        v2_readme = (V2 / "README.txt").read_text(encoding="utf-8")
        v2_start_sh = (V2 / "Start World Builder.sh").read_text(encoding="utf-8")
        v2_start_cmd = (V2 / "Start World Builder.cmd").read_text(encoding="utf-8")
        for text in (v2_readme, v2_start_sh, v2_start_cmd):
            self.assertIn("rsc-world-editor-v2", text)
        self.assertIn("v1.1.0", v2_readme)
        self.assertIn("Spoiled Milk World Builder 2", v2_readme)

        manager = (ROOT / "scripts" / "ai-manager.sh").read_text(encoding="utf-8")
        self.assertIn("legacy v1.1.0 release line is frozen", manager)
        self.assertIn("World Builder 2 packaging is not release-ready", manager)

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
