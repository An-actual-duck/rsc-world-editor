#!/usr/bin/env python3
"""Sibling project floor upgrades preserve saved authoring and rollback publication."""
import importlib.util
import json
import os
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
  if (a[4].equals("model")) {
   WorldBuilderLauncherModel model=new WorldBuilderLauncherModel(Paths.get(a[0]),Paths.get(a[1]),null,43951,null);
   for(WorldBuilderLauncherModel.ProjectEntry entry:model.projects()) if(entry.projectRoot.equals(project)) {
    System.out.print(model.upgradeProjectFloors(entry,model.previewProjectFloorUpgrade(entry)).projectRoot); return;
   }
   throw new AssertionError("Project not listed");
  }
  if (a[4].equals("busy")) {
   try(WorldBuilderAdaptiveProjectLock held=WorldBuilderAdaptiveProjectLock.acquire(project,"fixture")) {
    life.upgradeProjectFloors(Paths.get(a[0]),Paths.get(a[1]),project,"",a[3],43951);
   }
   throw new AssertionError("Busy project admitted");
  }
  String fingerprint=(String)WorldBuilderJsonDocuments.readObject(project.resolve("project.json")).get("projectFingerprintSha256");
  if (a[4].equals("stale")) fingerprint="0000000000000000000000000000000000000000000000000000000000000000";
  System.out.print(life.upgradeProjectFloors(Paths.get(a[0]),Paths.get(a[1]),project,fingerprint,a[3],43951).projectRoot);
 }
}
'''

UI_HARNESS = r'''
package com.openrsc.worldbuilder;
import java.nio.file.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.lang.reflect.*;
import java.util.concurrent.*;
import javax.swing.*;
import javax.imageio.ImageIO;
public final class FloorUpgradeUiHarness {
 static Object window; static JFrame frame; static JButton button;
 static Object field(String name) throws Exception { Field f=window.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(window); }
 static void capture(java.awt.Window target, String path) throws Exception {
  BufferedImage image=new BufferedImage(target.getWidth(),target.getHeight(),BufferedImage.TYPE_INT_RGB);
  Graphics2D graphics=image.createGraphics(); target.paint(graphics); graphics.dispose(); ImageIO.write(image,"png",Paths.get(path).toFile());
 }
 static JButton find(Container root,String label) {
  for(Component c:root.getComponents()) { if(c instanceof JButton && label.equals(((JButton)c).getText())) return (JButton)c;
   if(c instanceof Container) { JButton found=find((Container)c,label); if(found!=null) return found; } }
  return null;
 }
 public static void main(String[] a) throws Exception {
  try {
   WorldBuilderLauncherModel model=new WorldBuilderLauncherModel(Paths.get(a[0]),Paths.get(a[1]),Paths.get(a[2]),43951,null);
   SwingUtilities.invokeAndWait(()->{try {
    Class<?> type=Class.forName("com.openrsc.worldbuilder.WorldBuilderDesktopLauncher$Window");
    Constructor<?> ctor=type.getDeclaredConstructors()[0]; ctor.setAccessible(true);
    window=ctor.newInstance(model,new CountDownLatch(1),new java.util.concurrent.atomic.AtomicInteger());
    frame=(JFrame)field("frame"); button=(JButton)field("upgradeFloors"); frame.setVisible(true);
   }catch(Exception e){throw new RuntimeException(e);}});
   long until=System.currentTimeMillis()+10000;
   while(!button.isEnabled() && System.currentTimeMillis()<until) Thread.sleep(50);
   if(!button.isEnabled()) throw new AssertionError("Selected imported project upgrade control unavailable");
   SwingUtilities.invokeAndWait(()->{try {capture(frame,a[3]+"-launcher.png");button.doClick();}catch(Exception e){throw new RuntimeException(e);}});
   JDialog dialog=null;
   while(dialog==null && System.currentTimeMillis()<until) {
    for(java.awt.Window candidate:java.awt.Window.getWindows()) if(candidate instanceof JDialog && candidate.isVisible()) dialog=(JDialog)candidate;
    if(dialog==null) Thread.sleep(50);
   }
   if(dialog==null || !"Upgrade Project Floors".equals(dialog.getTitle())) throw new AssertionError("Missing upgrade preview");
   final JDialog preview=dialog;
   SwingUtilities.invokeAndWait(()->{try {capture(preview,a[3]+"-preview.png");JButton cancel=find(preview,"Cancel");if(cancel==null) throw new AssertionError("Missing Cancel");cancel.doClick();}catch(Exception e){throw new RuntimeException(e);}});
   System.out.println("selected project control and preview cancel passed");
  } finally {SwingUtilities.invokeAndWait(()->{for(java.awt.Window target:java.awt.Window.getWindows()) target.dispose();});}
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
  ui=cls.classes/"FloorUpgradeUiHarness.java";ui.write_text(UI_HARNESS)
  subprocess.run(["javac","-cp",str(cls.classes),"-d",str(cls.classes),str(ui)],check=True,capture_output=True)
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
  result=self.invoke("model"); self.assertEqual(0,result.returncode,result.stderr)
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
  current=json.loads((child/"source/floor-upgrade/current.json").read_text())
  self.assertEqual(self.project.name,current["projectId"])
  self.project=child
  result=self.invoke(); self.assertEqual(0,result.returncode,result.stderr)
  grandchild=Path(result.stdout)
  self.assertEqual(child.name,json.loads((grandchild/"source/floor-upgrade/current.json").read_text())["projectId"])
  self.assertEqual(3,len(list((grandchild/"source/floor-upgrade").glob("*.json"))))


 def test_failures_restore_registry_active_and_leave_original(self):
  before=L.tree_bytes(self.project)
  for milestone in ("stage-created","source-prepared","working-prepared","before-project-publish","project-published","registry-published","active-published"):
   metadata={p:(self.install/p).read_bytes() for p in ("project-registry.json","active-project.json")}
   result=self.invoke(milestone); self.assertNotEqual(0,result.returncode,milestone); self.assertIn("injected " + milestone,result.stderr)
   for p,b in metadata.items(): self.assertEqual(b,(self.install/p).read_bytes(),milestone)
   self.assertEqual([self.project.name],sorted(p.name for p in (self.install/"projects").iterdir() if p.is_dir()))
  after=L.tree_bytes(self.project); before.pop("run/world-builder.lock",None); after.pop("run/world-builder.lock",None)
  self.assertEqual(before,after)

 @unittest.skipUnless(os.environ.get("WORLD_BUILDER_FLOOR_UI_ACCEPTANCE") == "1", "opt-in graphical launcher acceptance")
 def test_launcher_button_previews_and_cancel_retains_selection(self):
  before=(self.install/"project-registry.json").read_bytes()
  active=(self.install/"active-project.json").read_bytes()
  prefix=os.environ.get("WORLD_BUILDER_FLOOR_UI_SCREENSHOT",str(self.root/"floor-upgrade"))
  result=subprocess.run(["java","-cp",str(self.classes),"com.openrsc.worldbuilder.FloorUpgradeUiHarness",str(self.install),str(self.runtime),str(self.target),prefix],text=True,capture_output=True,timeout=30)
  self.assertEqual(0,result.returncode,result.stdout+result.stderr)
  self.assertEqual(before,(self.install/"project-registry.json").read_bytes())
  self.assertEqual(active,(self.install/"active-project.json").read_bytes())

 def test_missing_semantics_and_corrupt_source_refuse_without_publication(self):
  jar=self.runtime/"Client_Base/Open_RSC_Client.jar"
  with zipfile.ZipFile(jar) as archive: entries={name:archive.read(name) for name in archive.namelist()}
  entries["META-INF/MANIFEST.MF"]=b"Manifest-Version: 1.0\n\n"
  with zipfile.ZipFile(jar,"w") as archive:
   for name,data in entries.items(): archive.writestr(name,data)
  result=self.invoke();self.assertNotEqual(0,result.returncode);self.assertIn("require standard-floors-v1",result.stderr)
  source=next((self.project/"source/content-bundle").rglob("TileDef.xml"));source.write_bytes(source.read_bytes()+b" ")
  result=self.invoke();self.assertNotEqual(0,result.returncode);self.assertIn("Immutable source bytes changed",result.stderr)
  self.assertEqual([self.project.name],sorted(p.name for p in (self.install/"projects").iterdir() if p.is_dir()))

 def test_busy_project_refuses_before_staging(self):
  result=self.invoke("busy");self.assertNotEqual(0,result.returncode);self.assertIn("another project operation is active",result.stderr)
  self.assertEqual([self.project.name],sorted(p.name for p in (self.install/"projects").iterdir() if p.is_dir()))

 def test_stale_preview_and_confirmation_refuse_before_staging(self):
  for milestone,confirm in (("stale","UPGRADE PROJECT FLOORS"),("","NO")):
   result=self.invoke(milestone,confirm); self.assertNotEqual(0,result.returncode)
   self.assertEqual([self.project.name],sorted(p.name for p in (self.install/"projects").iterdir() if p.is_dir()))

if __name__ == "__main__": unittest.main()
