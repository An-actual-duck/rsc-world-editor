#!/usr/bin/env python3
"""Content evolution acceptance uses disposable targets and complete saved projects."""
import importlib.util
import json
import subprocess
import tempfile
import unittest
from pathlib import Path

import adaptive_project_test_support as support

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("content_refresh_transactions", Path(__file__).with_name("test-world-builder-adaptive-transactions.py"))
transactions = importlib.util.module_from_spec(spec)
spec.loader.exec_module(transactions)


class ContentRefreshTest(transactions.AdaptiveTransactionTest):
    @classmethod
    def setUpClass(cls):
        super().setUpClass()
        harness = Path(cls.compile_temp.name) / "RefreshCatalogFixture.java"
        harness.write_text('''package com.openrsc.worldbuilder;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
public final class RefreshCatalogFixture {
 public static void main(String[] args) throws Exception {
  Path root=Paths.get(args[0]); WorldBuilderReadOnlyTarget target=WorldBuilderReadOnlyTarget.open(root);
  WorldBuilderTargetCapability capability=WorldBuilderTargetCapability.read(target);
  WorldBuilderAdaptiveConfiguration config=WorldBuilderAdaptiveConfiguration.select(target,capability,null).selected;
  if(args.length>1 && "floors".equals(args[1])) {
   Path floor=root.resolve("server/conf/server/defs/TileDef.xml");
   byte[] tiles=WorldBuilderStandardFloorDefinitions.extend(floor);Files.write(floor,tiles);
   Path client=root.resolve(WorldBuilderInstalledFloorContent.clientRoot(config));
   Files.write(client.resolve(WorldBuilderInstalledFloorContent.CLIENT_TILES),tiles);
   Files.write(client.resolve(WorldBuilderInstalledFloorContent.CLIENT_DESCRIPTOR),WorldBuilderInstalledFloorContent.descriptor(tiles));
  }
  Map<String,Object> old=WorldBuilderJsonDocuments.readObject(target.requiredFile(config.serverDefinitionCatalogRelativePath));
  Map<String,Object> catalog=WorldBuilderProjectContentBundle.deriveTargetCatalog(target,
   WorldBuilderPackedSourceLayout.selectContentRoots(target),(String)old.get("catalogId"));
  catalog.remove("catalogSha256"); byte[] bytes=WorldBuilderJsonDocuments.pretty(catalog).getBytes(StandardCharsets.UTF_8);
  Files.write(root.resolve(config.serverDefinitionCatalogRelativePath),bytes);
  Files.write(root.resolve(config.clientDefinitionCatalogRelativePath),bytes);
  String hash=WorldBuilderHashes.sha256(bytes);
  for(String path:Arrays.asList(config.serverRuntimeRelativePath,config.clientRuntimeRelativePath)) {
   Map<String,Object> doc=WorldBuilderJsonDocuments.readObject(root.resolve(path));doc.put("definitionCatalogSha256",hash);
   Files.write(root.resolve(path),WorldBuilderJsonDocuments.pretty(doc).getBytes(StandardCharsets.UTF_8));
  }
  Path descriptor=root.resolve("server/world-builder-capabilities.json");
  Map<String,Object> doc=WorldBuilderJsonDocuments.readObject(descriptor);
  ((Map<String,Object>)doc.get("definitions")).put("catalogSha256",hash);
  Files.write(descriptor,WorldBuilderJsonDocuments.pretty(doc).getBytes(StandardCharsets.UTF_8));
 }
}
''')
        result = subprocess.run(["javac", "-cp", str(cls.classes), "-d", str(cls.classes), str(harness)], capture_output=True, text=True)
        if result.returncode: raise AssertionError(result.stderr)
        failure_harness = Path(cls.compile_temp.name) / "RefreshPublicationFailure.java"
        failure_harness.write_text('''package com.openrsc.worldbuilder;
import java.nio.file.*;import java.nio.charset.StandardCharsets;
public final class RefreshPublicationFailure {
 public static void main(String[] args) throws Exception {
  Path project=Paths.get(args[0]),runtime=Paths.get(args[1]),target=Paths.get(args[2]);
  WorldBuilderProjectContentRefresh.Preview preview=new WorldBuilderProjectContentRefresh().preview(project,runtime,target,43883);
  Path report=Files.createTempFile(project.getParent().getParent(),".failure-refresh-",".json");
  try(WorldBuilderAdaptiveProjectLock ignored=WorldBuilderAdaptiveProjectLock.acquire(project,"refresh-failure-test")) {
   Files.write(report,WorldBuilderJsonDocuments.pretty(preview.report).getBytes(StandardCharsets.UTF_8));
   WorldBuilderAdaptiveProjectLifecycle lifecycle=new WorldBuilderAdaptiveProjectLifecycle((milestone,stage)-> {
    if(args[3].equals(milestone)) throw new java.io.IOException("injected content publication failure");
   });
   try { lifecycle.createContentRevision(project.getParent().getParent(),runtime,target,report,preview.parent,preview.authority,preview.newContentSha256,43883); }
   catch(Exception expected) { System.err.println(expected.getMessage()); throw expected; }
  } finally { Files.deleteIfExists(report); }
 }
}
''')
        result = subprocess.run(["javac", "-cp", str(cls.classes), "-d", str(cls.classes), str(failure_harness)], capture_output=True, text=True)
        if result.returncode: raise AssertionError(result.stderr)


        desktop_harness = Path(cls.compile_temp.name) / "RefreshDesktopModel.java"
        desktop_harness.write_text('''package com.openrsc.worldbuilder;
import java.nio.file.*;import java.util.*;
public final class RefreshDesktopModel {
 public static void main(String[] args) throws Exception {
  WorldBuilderLauncherModel model=new WorldBuilderLauncherModel(Paths.get(args[0]),Paths.get(args[1]),Paths.get(args[2]),43883,null);
  WorldBuilderLauncherModel.ProjectEntry selected=model.projects().get(0);
  List<String> phases=new ArrayList<>();
  WorldBuilderProjectContentRefresh.Observer progress=(phase,millis)->phases.add(phase);
  WorldBuilderProjectContentRefresh.Preview preview=model.previewContentRefresh(selected,progress);
  if(!preview.blockers.isEmpty())throw new IllegalStateException(preview.summary());
  WorldBuilderAdaptiveProjectLifecycle.ProjectResult result=model.applyContentRefresh(selected,preview,progress);
  WorldBuilderLauncherModel.ProjectEntry current=model.projects().get(0);
  if(!current.active||!result.projectId.equals(current.projectId)||!selected.projectId.equals(current.previousContentProjectId))
   throw new IllegalStateException("Desktop selection did not continue in the verified content revision");
  if(!selected.displayName.equals(current.displayName)||phases.size()<8)throw new IllegalStateException("Name or progress missing");
  System.out.print(result.toJson());
 }
}
''')
        result = subprocess.run(["javac", "-cp", str(cls.classes), "-d", str(cls.classes), str(desktop_harness)], capture_output=True, text=True)
        if result.returncode: raise AssertionError(result.stderr)

    def sync_catalogs(self, target, *options):
        result = subprocess.run(["java", "-cp", str(self.classes), "com.openrsc.worldbuilder.RefreshCatalogFixture", str(target), *options], capture_output=True, text=True)
        self.assertEqual(0, result.returncode, result.stderr)

    def fixture(self, base):
        def complete_content(target):
            support.write_json(target / "server/conf/server/defs/NpcDefs.json", {
                "npcs": [{"id": index, "name": "fixture-" + str(index)} for index in range(36)]
            })
            patch = target / "server/conf/server/defs/NpcDefsPatch18.json"
            value = json.loads(patch.read_text())
            value["npcs"].append({"id": 35, "name": "placed-fixture-35"})
            support.write_json(patch, value)
            self.sync_catalogs(target)
        target, installation, project, export = self.target_project(base, representation="packed", installed_standard_floors=True, target_mutator=complete_content)
        return target, installation, project, export, base / "builder-runtime"

    def refresh(self, project, runtime, target, *args):
        return self.run_cli("refresh-project-content", "--project", project, "--runtime-root", runtime, "--target-root", target, "--port", "43883", *args)

    def add_npc(self, target):
        path = target / "server/conf/server/defs/NpcDefsCustom.json"
        data = json.loads(path.read_text())
        # Sequential custom definitions retain all existing positions and identities.
        data["npcs"].append({"name": "new content revision monster", "sprites": [0] * 12})
        support.write_json(path, data)
        self.sync_catalogs(target)

    def test_additive_refresh_preserves_map_history_reopens_and_repeats_import(self):
        with tempfile.TemporaryDirectory(prefix="content-refresh-additive-") as temp:
            base = Path(temp)
            target, install, parent, export, runtime = self.fixture(base)
            first = self.run_reviewed_apply("import-adaptive", "IMPORT", "--project", parent, "--export", export, "--target-root", target)
            self.assertEqual(0, first.returncode, first.stderr)
            self.next_history_export(parent)  # Keep a saved edit that has not been imported.
            parent_before = support.tree_bytes(parent)
            working_before = support.tree_bytes(parent / "working/layered-world/package")
            self.add_npc(target)
            target_before = support.tree_bytes(target, install)
            reviewed = self.refresh(parent, runtime, target)
            self.assertEqual(0, reviewed.returncode, reviewed.stderr)
            preview = json.loads(reviewed.stdout)
            self.assertEqual("ready", preview["status"], preview["blockers"])
            self.assertTrue(next(row for row in preview["families"] if row["family"] == "npc")["added"])
            accepted = self.refresh(parent, runtime, target, "--confirm", "REFRESH", "--expected-preview", preview["previewFingerprintSha256"])
            self.assertEqual(0, accepted.returncode, accepted.stderr)
            successor = Path(json.loads(accepted.stdout)["projectRoot"])
            self.assertNotEqual(parent, successor)
            self.assertEqual(working_before, support.tree_bytes(successor / "working/layered-world/package"))
            self.assertEqual(parent_before, support.tree_bytes(parent))
            self.assertEqual(target_before, support.tree_bytes(target, install))
            self.assertEqual(successor.name, json.loads((install / "active-project.json").read_text())["projectId"])
            lineage = json.loads((successor / "source/content-refresh/origin.json").read_text())
            self.assertEqual(parent.name, lineage["projectId"])
            package = successor / "working/layered-world/package"
            manifest_path = package / "manifest.json"
            manifest = json.loads(manifest_path.read_text())
            for declaration in manifest["placementSets"]:
                placement_path = package / declaration["path"]
                placement = json.loads(placement_path.read_text())
                if placement["npcs"]:
                    placement["npcs"][0]["npcId"] = 36
                    support.write_json(placement_path, placement)
                    declaration["sha256"] = support.sha256(placement_path)
                    break
            support.write_json(manifest_path, manifest)
            for _ in range(2):
                exported = self.next_history_export(successor)
                applied = self.run_reviewed_apply("import-adaptive", "IMPORT", "--project", successor, "--export", exported, "--target-root", target)
                self.assertEqual(0, applied.returncode, applied.stderr)
            # Opening without a target must retain complete editor content.
            target.rename(base / "offline-target")
            moved_install = base / "offline-target/World Builder 2"
            opened = self.run_cli("open-project", "--installation-root", moved_install)
            self.assertEqual(0, opened.returncode, opened.stderr)

    def test_changed_used_identity_is_reviewed_and_cannot_be_accepted(self):
        with tempfile.TemporaryDirectory(prefix="content-refresh-conflict-") as temp:
            target, install, project, _, runtime = self.fixture(Path(temp))
            path = target / "server/conf/server/defs/NpcDefsPatch18.json"
            document = json.loads(path.read_text())
            document["npcs"][0]["name"] = "replacement with a different identity"
            support.write_json(path, document)
            before = support.tree_bytes(project)
            reviewed = self.refresh(project, runtime, target)
            self.assertEqual(0, reviewed.returncode, reviewed.stderr)
            preview = json.loads(reviewed.stdout)
            self.assertEqual("blocked", preview["status"])
            accepted = self.refresh(project, runtime, target, "--confirm", "REFRESH", "--expected-preview", preview["previewFingerprintSha256"])
            self.assertEqual(3, accepted.returncode, accepted.stderr)
            self.assertIn("conflicts", accepted.stderr)
            self.assertEqual(before, support.tree_bytes(project))

    def test_preview_to_apply_change_is_refused_without_publication(self):
        with tempfile.TemporaryDirectory(prefix="content-refresh-drift-") as temp:
            target, install, project, _, runtime = self.fixture(Path(temp))
            self.add_npc(target)
            reviewed = self.refresh(project, runtime, target)
            self.assertEqual(0, reviewed.returncode, reviewed.stderr)
            preview = json.loads(reviewed.stdout)
            self.add_npc(target)
            before = support.tree_bytes(install)
            accepted = self.refresh(project, runtime, target, "--confirm", "REFRESH", "--expected-preview", preview["previewFingerprintSha256"])
            self.assertEqual(3, accepted.returncode, accepted.stderr)
            self.assertIn("changed after", accepted.stderr)
            self.assertEqual(before, support.tree_bytes(install))


    def test_publication_failures_restore_registry_selection_and_keep_history(self):
        with tempfile.TemporaryDirectory(prefix="content-refresh-publication-") as temp:
            target, install, project, _, runtime = self.fixture(Path(temp))
            self.add_npc(target)
            before = support.tree_bytes(install)
            target_before = support.tree_bytes(target, install)
            for phase in ("source-prepared", "working-prepared", "before-project-publish", "project-published", "registry-published", "active-published"):
                with self.subTest(phase=phase):
                    failed = subprocess.run(["java", "-cp", str(self.classes), "com.openrsc.worldbuilder.RefreshPublicationFailure",
                        str(project), str(runtime), str(target), phase], capture_output=True, text=True)
                    self.assertNotEqual(0, failed.returncode)
                    self.assertIn("injected content publication failure", failed.stderr)
                    self.assertEqual(before, support.tree_bytes(install))
                    self.assertEqual(target_before, support.tree_bytes(target, install))

    def test_removed_available_definition_is_reported_without_erasing_library(self):
        with tempfile.TemporaryDirectory(prefix="content-refresh-remove-") as temp:
            target, install, project, _, runtime = self.fixture(Path(temp))
            path = target / "server/conf/server/defs/NpcDefs.json"
            value = json.loads(path.read_text())
            value["npcs"].pop()  # ID35 remains an overlay; remove unplaced34 explicitly instead.
            value["npcs"].pop()
            support.write_json(path, value)
            self.sync_catalogs(target)
            before = support.tree_bytes(project)
            reviewed = self.refresh(project, runtime, target)
            self.assertEqual(0, reviewed.returncode, reviewed.stderr)
            preview = json.loads(reviewed.stdout)
            self.assertEqual("blocked", preview["status"])
            self.assertTrue(preview["blockers"])
            self.assertEqual(before, support.tree_bytes(project))


    def add_floor(self, target):
        path = target / "server/conf/server/defs/TileDef.xml"
        value = path.read_text().replace("</TileDef-array>",
            "<TileDef><colour>-12345</colour><unknown>0</unknown><objectType>0</objectType></TileDef></TileDef-array>")
        path.write_text(value)
        self.sync_catalogs(target, "floors")

    def test_maintained_append_only_floor_content_refresh(self):
        with tempfile.TemporaryDirectory(prefix="content-refresh-floors-") as temp:
            target, install, project, _, runtime = self.fixture(Path(temp))
            self.add_floor(target)
            before = support.tree_bytes(target, install)
            reviewed = self.refresh(project, runtime, target)
            self.assertEqual(0, reviewed.returncode, reviewed.stderr)
            preview = json.loads(reviewed.stdout)
            self.assertEqual("ready", preview["status"], preview["blockers"])
            self.assertTrue(next(row for row in preview["families"] if row["family"] == "floor")["added"])
            accepted = self.refresh(project, runtime, target, "--confirm", "REFRESH", "--expected-preview", preview["previewFingerprintSha256"])
            self.assertEqual(0, accepted.returncode, accepted.stderr)
            self.assertEqual(before, support.tree_bytes(target, install))

    def test_changed_existing_floor_missing_pair_and_bad_descriptor_refused(self):
        for mode in ("existing-floor", "missing-pair", "bad-descriptor"):
            with self.subTest(mode=mode), tempfile.TemporaryDirectory(prefix="content-refresh-floor-refusal-") as temp:
                target, install, project, _, runtime = self.fixture(Path(temp))
                self.add_floor(target)
                if mode == "existing-floor":
                    path = target / "server/conf/server/defs/TileDef.xml"
                    path.write_text(path.read_text().replace("<TileDef><colour>0</colour>", "<TileDef><objectType>1</objectType><colour>0</colour>", 1))
                    self.sync_catalogs(target, "floors")
                elif mode == "missing-pair":
                    (target / "client/world-builder-configs/TileDef.xml").unlink()
                else:
                    path = target / "client/world-builder-configs/installed-floors.json"
                    value = json.loads(path.read_text()); value["tileDefinitionsSha256"] = "0" * 64
                    support.write_json(path, value)
                before = support.tree_bytes(target, install)
                reviewed = self.refresh(project, runtime, target)
                self.assertEqual(3, reviewed.returncode, reviewed.stderr)
                self.assertEqual(before, support.tree_bytes(target, install))


    def test_desktop_model_refresh_continues_selected_project_and_reports_progress(self):
        with tempfile.TemporaryDirectory(prefix="content-refresh-desktop-") as temp:
            target, install, project, _, runtime = self.fixture(Path(temp))
            self.add_npc(target)
            before = support.tree_bytes(project)
            result = subprocess.run(["java", "-cp", str(self.classes), "com.openrsc.worldbuilder.RefreshDesktopModel",
                str(install), str(runtime), str(target)], capture_output=True, text=True)
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual(before, support.tree_bytes(project))
            self.assertNotEqual(project.name, json.loads(result.stdout)["projectId"])


def load_tests(loader, tests, pattern):
    return unittest.TestSuite(ContentRefreshTest(name) for name in ContentRefreshTest.__dict__ if name.startswith("test_"))


if __name__ == "__main__":
    unittest.main(verbosity=2)
