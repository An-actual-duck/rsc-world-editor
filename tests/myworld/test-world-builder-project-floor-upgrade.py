#!/usr/bin/env python3
"""Sibling project floor upgrades preserve saved authoring and rollback publication."""
import importlib.util
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
import shutil
import zipfile

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("floor_lifecycle", ROOT / "tests/myworld/test-world-builder-adaptive-project-lifecycle.py")
L = importlib.util.module_from_spec(spec)
spec.loader.exec_module(L)
HARNESS = '''
package com.openrsc.worldbuilder;
import java.nio.file.*;
public final class FloorUpgradeHarness {
 public static void main(String[] a) throws Exception {
  WorldBuilderAdaptiveProjectLifecycle life = new WorldBuilderAdaptiveProjectLifecycle(new WorldBuilderAdaptiveProjectLifecycle.Observer() {
   public void observe(String milestone, Path stage) throws Exception {
    if (milestone.equals(a[4])) throw new java.io.IOException("injected " + milestone);
   }
  });
  Path project=Paths.get(a[2]);
  String fingerprint=(String)WorldBuilderJsonDocuments.readObject(project.resolve("project.json")).get("projectFingerprintSha256");
  if (a[4].equals("stale")) fingerprint="0000000000000000000000000000000000000000000000000000000000000000";
  System.out.print(life.upgradeProjectFloors(Paths.get(a[0]),Paths.get(a[1]),project,fingerprint,a[3],43951).projectRoot);
 }
}
'''

class FloorUpgradeTest(unittest.TestCase):
 @classmethod
 def setUpClass(cls):
  L.AdaptiveProjectLifecycleTest.setUpClass()
  cls.addClassCleanup(L.AdaptiveProjectLifecycleTest.tearDownClass)
  cls.life=L.AdaptiveProjectLifecycleTest()
  cls.classes=cls.life.classes
  source=cls.classes / "FloorUpgradeHarness.java"
  source.write_text(HARNESS)
  subprocess.run(["javac","-cp",str(cls.classes),"-d",str(cls.classes),str(source)],check=True,capture_output=True)
  # The previous release captured target TileDef without appending palette rows.
  # Override only that transform for fixture creation; all sealed project contracts
  # and the upgrade itself use the real production verifier and implementation.
  cls.legacy=cls.classes/"legacy-capture"
  cls.legacy.mkdir()
  stub=cls.legacy/"WorldBuilderStandardFloorDefinitions.java"
  stub.write_text("package com.openrsc.worldbuilder; import java.nio.file.*; final class WorldBuilderStandardFloorDefinitions { static byte[] extend(Path input) throws java.io.IOException { return Files.readAllBytes(input); } }")
  subprocess.run(["javac","-d",str(cls.legacy),str(stub)],check=True,capture_output=True)

 def setUp(self):
  self.temp=tempfile.TemporaryDirectory(prefix="floor-upgrade-"); self.addCleanup(self.temp.cleanup)
  self.root=Path(self.temp.name); self.install=self.root/"install"; self.install.mkdir()
  self.runtime=self.life.make_runtime(self.root)
  self.target=self.life.fixtures.legacy_fixture(str(self.root))
  terrain=self.target/"server/conf/server/data/Custom_Landscape.orsc"
  with zipfile.ZipFile(terrain,"w",zipfile.ZIP_DEFLATED) as archive: archive.writestr("h0x48y37",bytes(48*48*10))
  shutil.copy2(terrain,self.target/"Client_Base/Cache/video/Custom_Landscape.orsc")
  self.report=self.root/"report.json"; self.life.discover(self.target,self.report)
  result=subprocess.run(["java","-cp",str(self.legacy)+":"+str(self.classes),L.MAIN_CLASS,
   "create-project","--installation-root",str(self.install),"--runtime-root",str(self.runtime),"--target-root",str(self.target),
   "--discovery-report",str(self.report),"--display-name","Floor fixture","--port","43931","--confirm","CREATE"],text=True,capture_output=True)
  created=json.loads(result.stdout) if result.returncode == 0 else None
  self.assertEqual(0,result.returncode,result.stderr)
  self.project=Path(created["projectRoot"])
  self.life.change_working_terrain(self.project)
  saved=self.life.run_cli("save-project","--project",self.project)
  self.assertEqual(0,saved.returncode,saved.stderr)
  (self.project/"receipts"/"retained-history.txt").write_text("original project history")

 def invoke(self,milestone="",confirm="UPGRADE PROJECT FLOORS"):
  return subprocess.run(["java","-cp",str(self.classes),"com.openrsc.worldbuilder.FloorUpgradeHarness",str(self.install),str(self.runtime),str(self.project),confirm,milestone],text=True,capture_output=True)

 def test_saved_map_original_and_target_survive_and_copy_verifies(self):
  before=L.tree_bytes(self.project); target=L.tree_bytes(self.target)
  result=self.invoke(); self.assertEqual(0,result.returncode,result.stderr)
  child=Path(result.stdout)
  self.assertNotEqual(child,self.project)
  old_xml=(self.project/"source/content-bundle/files/server/conf/server/defs/TileDef.xml").read_bytes()
  new_xml=(child/"source/content-bundle/files/server/conf/server/defs/TileDef.xml").read_bytes()
  self.assertNotIn(b"worldBuilderMaterial",old_xml)
  self.assertIn(b"base-color-v1",new_xml)
  self.assertTrue(new_xml.startswith(old_xml.split(b"</TileDef-array>")[0]))
  after=L.tree_bytes(self.project)
  # Acquiring the normal lifecycle lock may create its empty coordination file.
  before.pop("run/world-builder.lock",None); after.pop("run/world-builder.lock",None)
  self.assertEqual(before,after); self.assertEqual(target,L.tree_bytes(self.target))
  self.assertEqual(L.tree_bytes(self.project/"working/layered-world/package"),L.tree_bytes(child/"working/layered-world/package"))
  self.assertEqual([],list((child/"receipts").iterdir()))
  self.assertEqual(2,len(json.loads((self.install/"project-registry.json").read_text())["projects"]))
  opened=self.life.run_cli("open-project","--installation-root",self.install)
  self.assertEqual(0,opened.returncode,opened.stderr)
  self.assertEqual(str(child),json.loads(opened.stdout)["projectRoot"])
  self.assertTrue(list((child/"source/floor-upgrade").glob("*.json")))

 def test_failures_restore_registry_active_and_leave_original(self):
  before=L.tree_bytes(self.project)
  for milestone in ("stage-created","source-prepared","working-prepared","before-project-publish","project-published","registry-published","active-published"):
   metadata={p:(self.install/p).read_bytes() for p in ("project-registry.json","active-project.json")}
   result=self.invoke(milestone); self.assertNotEqual(0,result.returncode,milestone); self.assertIn("injected " + milestone,result.stderr)
   for p,b in metadata.items(): self.assertEqual(b,(self.install/p).read_bytes(),milestone)
   self.assertEqual([self.project.name],sorted(p.name for p in (self.install/"projects").iterdir() if p.is_dir()))
  after=L.tree_bytes(self.project); before.pop("run/world-builder.lock",None); after.pop("run/world-builder.lock",None)
  self.assertEqual(before,after)

 def test_stale_preview_and_confirmation_refuse_before_staging(self):
  for milestone,confirm in (("stale","UPGRADE PROJECT FLOORS"),("","NO")):
   result=self.invoke(milestone,confirm); self.assertNotEqual(0,result.returncode)
   self.assertEqual([self.project.name],sorted(p.name for p in (self.install/"projects").iterdir() if p.is_dir()))

if __name__ == "__main__": unittest.main()
