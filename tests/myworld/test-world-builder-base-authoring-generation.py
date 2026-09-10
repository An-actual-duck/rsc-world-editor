#!/usr/bin/env python3
"""Prepare current authoring with real Base artifacts; never switch or launch a project."""
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import unittest

ROOT = Path(__file__).resolve().parents[2]
JAR = ROOT / "output/world-builder-tools/world-builder-tools.jar"
PROVIDER = ROOT / ".runtime-provider"
SOURCE = os.environ.get("WORLD_BUILDER_PRESERVATION_SOURCE_GIT")
PREDECESSOR = os.environ.get("WORLD_BUILDER_BASE_PREDECESSOR_PROVIDER")
HARNESS = r'''package com.openrsc.worldbuilder;
import java.nio.file.*;
import java.util.*;
public final class AuthoringGenerationProbe {
 public static void main(String[] a) throws Exception {
  Path project=Paths.get(a[1]), generation=Paths.get(a[2]);
  WorldBuilderCurrentBaseAuthoringGeneration.Plan plan=WorldBuilderCurrentBaseAuthoringGeneration.preview(project,Paths.get(a[3]),Paths.get(a[4]));
  if("preview".equals(a[0])) { System.out.print(plan.toJson()); return; }
  if("busy".equals(a[0])) {
   try(WorldBuilderAdaptiveProjectLock lock=WorldBuilderAdaptiveProjectLock.acquire(project,"generation-fixture")) {
    WorldBuilderCurrentBaseAuthoringGeneration.prepare(plan,generation);
   }
   return;
  }
  String seal=a.length>5 ? a[5] : "";
  if("prepare".equals(a[0])) seal=WorldBuilderCurrentBaseAuthoringGeneration.prepare(plan,generation);
  if("drift".equals(a[0])) {
   Path source=Paths.get(a[3]).getParent().resolve("output/current-platform/current-base-v1/server/core.jar"); byte[] before=Files.readAllBytes(source);
   // Used only against an exported disposable catalog, never the provider.
   try { Files.write(source,new byte[]{32},StandardOpenOption.APPEND);
    WorldBuilderCurrentBaseAuthoringGeneration.prepare(plan,generation);
   } finally { Files.write(source,before); }
   return;
  }
  WorldBuilderCurrentBaseAuthoringGeneration.verify(plan,generation,seal);
  Map<String,Object> result=WorldBuilderJsonDocuments.readObject(plan.toJson().getBytes("UTF-8"),"plan");
  result.put("preparedSealSha256",seal);
  System.out.print(WorldBuilderJsonDocuments.pretty(result));
 }
}'''


@unittest.skipUnless(SOURCE, "genuine public-source project input required")
class BaseAuthoringGenerationTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        spec = importlib.util.spec_from_file_location("authoring_native", ROOT / "tests/myworld/test-world-builder-base-project-lifecycle.py")
        cls.native = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(cls.native)
        if PREDECESSOR:
            expected = "e9cf05c78a6aadde44ecc1a8449dbba2cecdc159"
            subprocess.run(["git", "-C", str(PROVIDER), "merge-base", "--is-ancestor", expected, "HEAD"], check=True)
            cls.native.PROVIDER = Path(PREDECESSOR).resolve(strict=True)
            cls.native.BaseProjectLifecycleTest.expected_provider_commit = expected
        cls.native.BaseProjectLifecycleTest.setUpClass()
        cls.addClassCleanup(cls.native.BaseProjectLifecycleTest.doClassCleanups)
        cls.fixture = cls.native.BaseProjectLifecycleTest()
        cls.fixture.test_real_create_reopen_export_and_native_launch_commands_keep_target_private()
        cls.root = cls.fixture.root
        cls.project = next((cls.root / "installation/projects").glob("*/project.json")).parent
        cls.classes = cls.root / "classes"
        source = cls.root / "AuthoringGenerationProbe.java"
        source.write_text(HARNESS)
        subprocess.run(["javac", "-cp", str(JAR), "-d", str(cls.classes), str(source)], check=True, capture_output=True)
        cls.identity = PROVIDER / "output/current-platform/current-base-v1/composition-identity.json"
        cls.seals = {}

    def invoke(self, operation, generation, success=True, catalog=None, identity=None):
        result = subprocess.run(["java", "-Xmx1536m", "-cp", str(self.classes) + os.pathsep + str(JAR),
            "com.openrsc.worldbuilder.AuthoringGenerationProbe", operation, str(self.project), str(generation),
            str(catalog or PROVIDER / "current-platform"), str(identity or self.identity), self.seals.get(generation, "")], capture_output=True, text=True, timeout=120)
        self.assertEqual(success, result.returncode == 0, result.stdout + result.stderr)
        if not success:
            return result
        value = json.loads(result.stdout)
        if "preparedSealSha256" in value:
            self.seals[generation] = value.pop("preparedSealSha256")
        return value

    def test_current_generation_is_complete_and_never_mutates_the_existing_project(self):
        generation = self.root / "prepared-authoring"
        before = self.native.snapshot(self.project)
        target_before = self.native.snapshot(self.root / "historical-input")
        preview = self.invoke("preview", generation)
        self.assertFalse(generation.exists())
        self.assertFalse(preview["activationAuthorized"])
        self.assertEqual(preview, self.invoke("prepare", generation))
        self.assertEqual(before, self.native.snapshot(self.project))
        self.assertEqual(target_before, self.native.snapshot(self.root / "historical-input"))
        self.assertEqual(preview, self.invoke("verify", generation))
        new_identity = json.loads((generation / "source/provider/composition-identity.json").read_text())
        self.assertEqual(json.loads(self.identity.read_text()), new_identity)
        if PREDECESSOR:
            old_identity = json.loads((self.project / "source/provider/composition-identity.json").read_text())
            self.assertNotEqual(old_identity["bundleInventoryHash"], new_identity["bundleInventoryHash"])
        self.invoke("prepare", generation, success=False)
        for relative in ("working/runtime/server/core.jar", "working/runtime/client/evidence/adaptive-assets.sha256",
                         "source/provider/authoring-catalog.json", "authoring-generation.json"):
            path = generation / relative
            original = path.read_bytes()
            with self.subTest(drift=relative):
                path.write_bytes(original + b" ")
                self.invoke("verify", generation, success=False)
                path.write_bytes(original)
        extra = generation / "unselected.jar"
        extra.write_bytes(b"unbound")
        self.invoke("verify", generation, success=False)
        extra.unlink()
        code = generation / "working/runtime/server/core.jar"
        mode = code.stat().st_mode & 0o7777
        code.chmod(0o777)
        self.invoke("verify", generation, success=False)
        code.chmod(mode)
        alias = self.root / "linked-runtime"
        os.link(code, alias)
        self.invoke("verify", generation, success=False)
        alias.unlink()
        self.invoke("verify", generation)
        self.assertEqual(before, self.native.snapshot(self.project))

    def test_busy_project_and_project_overlap_refuse_before_generation_creation(self):
        before = self.native.snapshot(self.project)
        generation = self.root / "busy-authoring"
        self.invoke("busy", generation, success=False)
        self.assertFalse(generation.exists())
        self.invoke("prepare", self.project / "working/forbidden-generation", success=False)
        self.invoke("prepare", self.root / "historical-input/forbidden-generation", success=False)
        self.invoke("prepare", PROVIDER / "forbidden-generation", success=False)
        self.assertEqual(before, self.native.snapshot(self.project))

    def test_selected_artifact_drift_refuses_before_creating_a_generation(self):
        catalog = self.root / "disposable-catalog"
        subprocess.run(["java", "-Xmx1536m", "-jar", str(JAR), "export-current-base-catalog",
            "--provider-catalog-root", str(PROVIDER / "current-platform"), "--composition-identity", str(self.identity),
            "--destination", str(catalog)], check=True, capture_output=True, timeout=120)
        before = self.native.snapshot(self.project)
        output = self.root / "drift-authoring"
        self.invoke("drift", output, success=False, catalog=catalog / "current-platform",
                    identity=catalog / "current-platform/composition-identity.json")
        self.assertFalse(output.exists())
        self.assertEqual(before, self.native.snapshot(self.project))


if __name__ == "__main__":
    unittest.main()
