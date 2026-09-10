#!/usr/bin/env python3
"""Existing desktop and active-terminal upgrade controls use the shipped Base catalog."""
import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import unittest

ROOT = Path(__file__).resolve().parents[2]
JAR = ROOT / "output/world-builder-tools/world-builder-tools.jar"


@unittest.skipUnless(os.environ.get("WORLD_BUILDER_PRESERVATION_SOURCE_GIT"), "explicit public source required")
class BaseUpgradeUserActionsTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        spec = importlib.util.spec_from_file_location("user_native", ROOT / "tests/myworld/test-world-builder-base-project-lifecycle.py")
        cls.native = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(cls.native)
        cls.native.LAUNCH = False  # This test previews/cancels only; it does not occupy the GUI lane.
        cls.native.BaseProjectLifecycleTest.setUpClass()
        cls.addClassCleanup(cls.native.BaseProjectLifecycleTest.doClassCleanups)
        cls.fixture = cls.native.BaseProjectLifecycleTest()

    def test_desktop_preview_and_terminal_cancel_preserve_target(self):
        fixture = self.fixture
        fixture.test_real_create_reopen_export_and_native_launch_commands_keep_target_private()
        root = fixture.root
        installation = root / "installation"
        target = root / "historical-input"
        before = self.native.snapshot(target)
        # Use the exact relocated production catalog layout, not a developer override.
        shipped = root / "shipped"
        exported = fixture.invoke("export-current-base-catalog", "--provider-catalog-root",
            self.native.PROVIDER / "current-platform", "--composition-identity", fixture.identity,
            "--destination", shipped)
        self.assertEqual(0, exported.returncode, exported.stderr)
        shutil.copytree(shipped, installation, dirs_exist_ok=True)
        source = root / "BaseUpgradeActionsProbe.java"
        source.write_text('''package com.openrsc.worldbuilder;
import java.nio.file.*;
public final class BaseUpgradeActionsProbe {
  public static void main(String[] args) throws Exception {
    Path root = Paths.get(args[0]);
    WorldBuilderLauncherModel model = new WorldBuilderLauncherModel(root.resolve("installation"),
      root.resolve("application-runtime"), null, 43595, "preservation");
    WorldBuilderLauncherModel.PreparedImport prepared = model.prepareServerRuntimeUpgrade(model.projects().get(0));
    if (prepared.upgradePlan == null || prepared.importer != null || prepared.mapPlan != null)
      throw new AssertionError("Native Base must route to the guarded upgrade transaction");
    if (!prepared.target.equals(root.resolve("historical-input")))
      throw new AssertionError("Use the project target, not the installation parent");
    if (!prepared.summary().contains(prepared.upgradePlan.confirmationIdentity())
        || !prepared.summary().contains(prepared.upgradePlan.fingerprint()))
      throw new AssertionError("Display the exact reviewed plan and confirmation");
    try {
      model.prepareServerRecovery(model.projects().get(0));
      throw new AssertionError("An empty current workspace cannot offer recovery");
    } catch (java.io.IOException expected) {
      if (!expected.getMessage().contains("No interrupted current transaction")) throw expected;
    }
    System.out.print(prepared.upgradePlan.toJson());
  }
}
''')
        subprocess.run(["javac", "-cp", str(JAR), "-d", str(fixture.classes), str(source)], check=True, capture_output=True)
        preview = subprocess.run(["java", "-Xmx1536m", "-cp", str(fixture.classes) + os.pathsep + str(JAR),
            "com.openrsc.worldbuilder.BaseUpgradeActionsProbe", str(root)], capture_output=True, text=True, timeout=180)
        self.assertEqual(0, preview.returncode, preview.stderr)
        plan = json.loads(preview.stdout)
        self.assertTrue(plan["activationAuthorized"])
        self.assertEqual("current-base-v1", plan["destination"]["variantId"])
        self.assertEqual(before, self.native.snapshot(target))
        cancelled = subprocess.run(["java", "-Xmx1536m", "-jar", str(JAR), "upgrade-active-target-runtime",
            "--installation-root", str(installation)], input="\n", capture_output=True, text=True, timeout=180)
        self.assertEqual(0, cancelled.returncode, cancelled.stderr)
        self.assertIn("Exact confirmation: UPGRADE:", cancelled.stderr)
        self.assertIn("Runtime upgrade cancelled; no target file was changed.", cancelled.stderr)
        self.assertEqual(before, self.native.snapshot(target))
        self.assertFalse((target / ".world-builder/runtime-ledger-v1.json").exists())
        recovery = fixture.invoke("recover-active-adaptive", "--installation-root", installation)
        self.assertNotEqual(0, recovery.returncode)
        self.assertIn("No interrupted current transaction", recovery.stderr)
        self.assertEqual(before, self.native.snapshot(target))


if __name__ == "__main__":
    unittest.main()
