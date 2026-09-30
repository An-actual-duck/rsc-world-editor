#!/usr/bin/env python3
"""Legacy whole-composition UI/CLI entry points refuse before target mutation."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
JAR = ROOT / "output/world-builder-tools/world-builder-tools.jar"


class BaseUpgradeUserActionsTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temporary = tempfile.TemporaryDirectory(prefix="targeted-upgrade-user-actions-")
        cls.addClassCleanup(cls.temporary.cleanup)
        cls.root = Path(cls.temporary.name)
        source = cls.root / "BaseUpgradeActionsProbe.java"
        source.write_text('''package com.openrsc.worldbuilder;
import java.nio.file.*;
public final class BaseUpgradeActionsProbe {
 public static void main(String[] args) throws Exception {
  try {
   WorldBuilderCurrentRuntimeUserActions.previewUpgrade(Paths.get(args[0]), null);
   throw new AssertionError("Generic composition replacement was allowed");
  } catch (WorldBuilderContractException expected) {
   if (!expected.getMessage().contains("generic game composition")) throw expected;
   System.out.println(expected.getMessage());
  }
 }
}''')
        subprocess.run(["javac", "-cp", str(JAR), "-d", str(cls.root), str(source)], check=True, capture_output=True)

    def fixture(self):
        temporary = tempfile.TemporaryDirectory(prefix="targeted-upgrade-refusal-")
        self.addCleanup(temporary.cleanup)
        root = Path(temporary.name)
        target = root / "target"
        target.mkdir()
        (target / "custom-npc-dialogue.txt").write_bytes(b"owner dialogue and content must stay active")
        (target / "core.jar").write_bytes(b"owner binary must never be replaced by generic composition")
        return root, target

    def snapshot(self, root):
        return {str(p.relative_to(root)): p.read_bytes() if p.is_file() else None for p in root.rglob('*')}

    def test_desktop_shared_upgrade_refuses_composition_before_target_access(self):
        root, target = self.fixture()
        before = self.snapshot(root)
        result = subprocess.run(["java", "-cp", str(self.root) + os.pathsep + str(JAR),
                                 "com.openrsc.worldbuilder.BaseUpgradeActionsProbe", str(root)], capture_output=True, text=True)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("would not preserve its custom content", result.stdout)
        self.assertEqual(before, self.snapshot(root))
        self.assertFalse((target / ".world-builder").exists())

    def test_cli_preview_and_apply_refuse_legacy_whole_composition(self):
        root, target = self.fixture()
        before = self.snapshot(root)
        for operation in ("preview-current-runtime-upgrade", "apply-current-runtime-upgrade"):
            args = ["java", "-jar", str(JAR), operation, "--target-root", str(target),
                    "--transaction-root", str(root / "transactions"), "--transaction-id", "fixture-target-upgrade",
                    "--provider-catalog-root", str(root / "catalog"), "--composition-identity", str(root / "identity.json"),
                    "--adapter", "preservation-family-v1", "--project-capability", str(root / "project-capability.json")]
            if operation.startswith("apply"):
                args += ["--confirmation-identity", "UPGRADE:fixture-target-upgrade"]
            result = subprocess.run(args, capture_output=True, text=True)
            self.assertEqual(3, result.returncode, result.stderr)
            self.assertIn("RUNTIME_UPGRADE_REQUIRED", result.stderr)
            self.assertIn("generic game composition", result.stderr)
            self.assertEqual(before, self.snapshot(root))
            self.assertFalse((root / "transactions").exists())

    def test_historical_recovery_dispatch_remains_available(self):
        root, target = self.fixture()
        before = self.snapshot(root)
        result = subprocess.run(["java", "-jar", str(JAR), "recover-current-runtime-upgrade",
                                 "--target-root", str(target), "--transaction-root", str(root / "transactions"),
                                 "--transaction-id", "missing-historical-transaction"], capture_output=True, text=True)
        self.assertNotEqual(0, result.returncode)
        self.assertNotIn("generic game composition", result.stderr)
        self.assertIn("UNSAFE_PATH", result.stderr)
        self.assertEqual(before, self.snapshot(root))


if __name__ == "__main__":
    unittest.main()
