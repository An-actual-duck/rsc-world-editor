#!/usr/bin/env python3
"""Selected native Base projection; no target or synthetic visual authority."""
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
PROVIDER = ROOT / ".runtime-provider"
JAR = ROOT / "output/world-builder-tools/world-builder-tools.jar"
MAIN = "com.openrsc.worldbuilder.BaseProjectContentHarness"
HARNESS = """
package com.openrsc.worldbuilder;
import java.nio.file.*;
import java.util.*;
public final class BaseProjectContentHarness {
  public static void main(String[] args) throws Exception {
    Path project = Paths.get(args[1]);
    if (args[0].equals("capture")) {
      WorldBuilderProviderCatalog.Composition composition = WorldBuilderProviderCatalog.resolve(Paths.get(args[2]), Paths.get(args[3]));
      WorldBuilderCurrentBaseProjectContent.Plan plan = WorldBuilderCurrentBaseProjectContent.inspect(composition);
      WorldBuilderCurrentBaseProjectContent.capture(project, plan);
      boolean authoring = true;
      try { WorldBuilderCurrentBaseProjectContent.requireAuthoring(plan); }
      catch (WorldBuilderContractException absent) { authoring = false; }
      Map<String,Object> result = new LinkedHashMap<String,Object>();
      result.put("authoringAdmitted", authoring);
      result.put("catalog", WorldBuilderJsonDocuments.readObject(project.resolve(WorldBuilderCurrentBaseProjectContent.CATALOG)));
      result.put("binding", WorldBuilderCurrentBaseProjectContent.verify(project));
      System.out.print(WorldBuilderJsonDocuments.pretty(result));
    } else {
      System.out.print(WorldBuilderJsonDocuments.pretty(WorldBuilderCurrentBaseProjectContent.verify(project)));
    }
  }
}
"""


class BaseProjectContentTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temporary = tempfile.TemporaryDirectory(prefix="base-native-project-")
        cls.addClassCleanup(cls.temporary.cleanup)
        cls.root = Path(cls.temporary.name)
        cls.classes = cls.root / "classes"
        cls.classes.mkdir()
        source = cls.root / "BaseProjectContentHarness.java"
        source.write_text(HARNESS)
        subprocess.run(["javac", "-source", "8", "-target", "8", "-cp", str(JAR),
                        "-d", str(cls.classes), str(source)], check=True, capture_output=True)
        expected = next(line.split("=", 1)[1] for line in (ROOT / "runtime-provider.lock").read_text().splitlines()
                        if line.startswith("RUNTIME_PROVIDER_COMMIT="))
        actual = subprocess.check_output(["git", "-C", str(PROVIDER), "rev-parse", "HEAD"], text=True).strip()
        if actual != expected:
            raise AssertionError("native content acceptance requires the exact materialized provider lock")
        if subprocess.run(["git", "-C", str(PROVIDER), "diff", "--quiet", "HEAD", "--"], capture_output=True).returncode:
            raise AssertionError("native content acceptance requires unchanged selected provider inputs")
        cls.identity = PROVIDER / "output/current-platform/current-base-v1/composition-identity.json"
        subprocess.run(["python3", "scripts/build-current-base.py"], cwd=PROVIDER,
                       check=True, capture_output=True, timeout=240)

    def invoke(self, operation, project):
        return subprocess.run(["java", "-cp", os.pathsep.join((str(self.classes), str(JAR))), MAIN,
                               operation, str(project), str(PROVIDER / "current-platform"), str(self.identity)],
                              capture_output=True, text=True, timeout=60)

    def test_full_native_catalog_and_payload_are_bound_without_advanced_overlay(self):
        project = self.root / "project"
        project.mkdir()
        result = self.invoke("capture", project)
        self.assertEqual(0, result.returncode, result.stderr)
        value = json.loads(result.stdout)
        profile = json.loads((PROVIDER / "current-platform/runtime/current-base-v1/profile.json").read_text())
        self.assertEqual("authoringPolicy" in profile, value["authoringAdmitted"])
        catalog = value["catalog"]
        self.assertEqual("current-base-native-authoring-v1", catalog["catalogId"])
        self.assertEqual(list(range(1593)), catalog["groundItems"])
        self.assertEqual(list(range(836)), catalog["npcs"])
        self.assertEqual(list(range(1296)), catalog["scenery"])
        native = project / "source/provider/installed"
        self.assertTrue((native / "client/Cache/video/CurrentBase_Public_Sprites.osar").is_file())
        self.assertFalse((native / "client/Cache/video/Custom_Sprites.osar").exists())
        self.assertFalse((native / "server/conf/server/defs/ItemDefsMyWorld.json").exists())
        self.assertFalse((project / "source/content-bundle").exists())
        self.assertFalse(list(project.rglob("*.db")))
        self.assertEqual(0, self.invoke("verify", project).returncode)
        self.assertNotEqual(0, self.invoke("capture", project).returncode)

        for relative in ("source/provider/authoring-catalog.json", "source/provider/runtime/server/core.jar",
                         "source/provider/installed/client/Cache/video/CurrentBase_Public_Sprites.osar"):
            path = project / relative
            original = path.read_bytes()
            with self.subTest(drift=relative):
                path.write_bytes(original + b" ")
                self.assertNotEqual(0, self.invoke("verify", project).returncode)
                path.write_bytes(original)
        extra = project / "source/provider/foreign.jar"
        extra.write_bytes(b"not allowed")
        self.assertNotEqual(0, self.invoke("verify", project).returncode)
        extra.unlink()
        path = native / "client/Cache/video/models.orsc"
        alias = self.root / "model-alias"
        os.link(path, alias)
        self.assertNotEqual(0, self.invoke("verify", project).returncode)
        alias.unlink()
        self.assertEqual(0, self.invoke("verify", project).returncode)


if __name__ == "__main__":
    unittest.main()
