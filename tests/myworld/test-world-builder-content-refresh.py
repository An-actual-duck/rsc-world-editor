#!/usr/bin/env python3
"""Content evolution acceptance uses disposable targets and complete saved projects."""
import importlib.util
import json
import subprocess
import struct
import tempfile
import unittest
import xml.etree.ElementTree as ET
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

        review_harness = Path(cls.compile_temp.name) / "RefreshReviewFixture.java"
        review_harness.write_text('''package com.openrsc.worldbuilder;
import java.nio.file.*;import java.util.*;
public final class RefreshReviewFixture {
 public static void main(String[] args) throws Exception {
  WorldBuilderProjectContentRefresh.Preview preview=new WorldBuilderProjectContentRefresh().preview(
   Paths.get(args[0]),Paths.get(args[1]),Paths.get(args[2]),43883);
  Map<String,Object> result=new LinkedHashMap<>();result.put("summary",preview.summary());
  result.put("details",preview.reviewDetails());
  result.put("projectWarning",WorldBuilderEffectiveContent.projectWarningSummary(Paths.get(args[0])));
  System.out.print(WorldBuilderJsonDocuments.pretty(result));
 }
}
''')
        result = subprocess.run(["javac", "-cp", str(cls.classes), "-d", str(cls.classes), str(review_harness)], capture_output=True, text=True)
        if result.returncode: raise AssertionError(result.stderr)

    def sync_catalogs(self, target, *options):
        result = subprocess.run(["java", "-cp", str(self.classes), "com.openrsc.worldbuilder.RefreshCatalogFixture", str(target), *options], capture_output=True, text=True)
        self.assertEqual(0, result.returncode, result.stderr)

    def complete_content(self, target):
        support.write_json(target / "server/conf/server/defs/NpcDefs.json", {
            "npcs": [{"id": index, "name": "fixture-" + str(index)} for index in range(36)]
        })
        patch = target / "server/conf/server/defs/NpcDefsPatch18.json"
        value = json.loads(patch.read_text())
        value["npcs"].append({"id": 35, "name": "placed-fixture-35"})
        support.write_json(patch, value)
        self.sync_catalogs(target)

    def fixture(self, base):
        target, installation, project, export = self.target_project(base, representation="packed", installed_standard_floors=True, target_mutator=self.complete_content)
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
            # Repeat content detection on the selected revision, preserving both predecessors.
            first_revision = support.tree_bytes(successor)
            self.add_npc(target)
            reviewed = self.refresh(successor, runtime, target)
            self.assertEqual(0, reviewed.returncode, reviewed.stderr)
            preview = json.loads(reviewed.stdout)
            self.assertEqual("ready", preview["status"])
            accepted = self.refresh(successor, runtime, target, "--confirm", "REFRESH",
                "--expected-preview", preview["previewFingerprintSha256"])
            self.assertEqual(0, accepted.returncode, accepted.stderr)
            latest = Path(json.loads(accepted.stdout)["projectRoot"])
            self.assertEqual(first_revision, support.tree_bytes(successor))
            self.assertEqual(parent_before, support.tree_bytes(parent))
            self.promote_fixture_terrain_to_v2(latest / "working/layered-world/package", 77)
            saved = self.run_cli("save-project", "--project", latest)
            self.assertEqual(0, saved.returncode, saved.stderr)
            exported_result = self.run_cli("export-adaptive", "--project", latest)
            self.assertEqual(0, exported_result.returncode, exported_result.stderr)
            exported = Path(json.loads(exported_result.stdout)["exportDirectory"])
            imported = self.run_reviewed_apply("import-adaptive", "IMPORT", "--project", latest, "--export", exported, "--target-root", target)
            self.assertEqual(0, imported.returncode, imported.stderr)
            # Opening without a target must retain complete editor content.
            target.rename(base / "offline-target")
            moved_install = base / "offline-target/World Builder 2"
            opened = self.run_cli("open-project", "--installation-root", moved_install)
            self.assertEqual(0, opened.returncode, opened.stderr)

    def test_visual_only_model_refresh_preserves_identities_map_and_target_assets(self):
        def model_archive(vertex):
            # One valid native OB3 triangle, with solid face materials and a changed vertex.
            payload = (struct.pack(">HH", 3, 1) + struct.pack(">hhh", 0, 64, 0)
                + struct.pack(">hhh", 0, 0, -64) + struct.pack(">hhh", 0, 0, vertex)
                + bytes((3,)) + struct.pack(">hh", -1, -1) + bytes((0, 0, 1, 2)))
            name_hash = 0
            for character in "sample.ob3".upper(): name_hash = (name_hash * 61 + ord(character) - 32) & 0xffffffff
            raw = b"\x00\x01" + struct.pack(">I", name_hash) + len(payload).to_bytes(3, "big") * 2 + payload
            return len(raw).to_bytes(3, "big") * 2 + raw
        def content(target):
            self.complete_content(target)
            path = target / "server/conf/server/defs/GameObjectDef.xml"
            path.write_text(path.read_text().replace("</GameObjectDef>", "<objectModel>sample</objectModel></GameObjectDef>"))
            (target / "Client_Base/Cache/video/models.orsc").write_bytes(model_archive(64))
        with tempfile.TemporaryDirectory(prefix="content-refresh-model-visual-") as temp:
            base = Path(temp)
            target, install, parent, export = self.target_project(base, representation="packed",
                installed_standard_floors=True, target_mutator=content)
            runtime = base / "builder-runtime"
            before = support.tree_bytes(parent)
            saved = support.tree_bytes(parent / "working/layered-world/package")
            archive = target / "Client_Base/Cache/video/models.orsc"
            archive.write_bytes(model_archive(96))
            target_before = support.tree_bytes(target, install)
            reviewed = self.refresh(parent, runtime, target)
            self.assertEqual(0, reviewed.returncode, reviewed.stderr)
            preview = json.loads(reviewed.stdout)
            self.assertEqual("ready", preview["status"])
            scenery = next(row for row in preview["families"] if row["family"] == "scenery")
            self.assertTrue(scenery["visualsChanged"])
            self.assertFalse(scenery["added"] or scenery["changed"] or scenery["removed"])
            self.assertTrue(any(row["mapReferenceCount"] > 0 for row in scenery["details"]))
            for row in scenery["details"]:
                self.assertEqual("visuals-changed", row["status"])
                self.assertEqual(row["previousSemanticSha256"], row["semanticSha256"])
                self.assertNotEqual(row["previousVisualSha256"], row["visualSha256"])
            accepted = self.refresh(parent, runtime, target, "--confirm", "REFRESH",
                "--expected-preview", preview["previewFingerprintSha256"])
            self.assertEqual(0, accepted.returncode, accepted.stderr)
            successor = Path(json.loads(accepted.stdout)["projectRoot"])
            self.assertEqual(before, support.tree_bytes(parent))
            self.assertEqual(saved, support.tree_bytes(successor / "working/layered-world/package"))
            self.assertEqual(target_before, support.tree_bytes(target, install))
            captured = list((successor / "source").rglob("models.orsc"))
            self.assertTrue(captured)
            for path in captured: self.assertEqual(model_archive(96), path.read_bytes())
            exported = self.next_history_export(successor)
            imported = self.run_reviewed_apply("import-adaptive", "IMPORT", "--project", successor,
                "--export", exported, "--target-root", target)
            self.assertEqual(0, imported.returncode, imported.stderr)
            self.assertEqual(model_archive(96), archive.read_bytes())
            self.assertEqual(before, support.tree_bytes(parent))

    def test_unresolved_visual_dependencies_are_diagnosed_without_claiming_visual_changes(self):
        with tempfile.TemporaryDirectory(prefix="content-refresh-unresolved-visual-") as temp:
            target, install, parent, _, runtime = self.fixture(Path(temp))
            before = support.tree_bytes(parent)
            archive = target / "Client_Base/Cache/video/models.orsc"
            archive.write_bytes(archive.read_bytes() + b"\nchanged opaque archive")
            target_before = support.tree_bytes(target, install)
            reviewed = self.refresh(parent, runtime, target)
            self.assertEqual(0, reviewed.returncode, reviewed.stderr)
            preview = json.loads(reviewed.stdout)
            self.assertEqual("ready", preview["status"])
            scenery = next(row for row in preview["families"] if row["family"] == "scenery")
            self.assertFalse(scenery["visualsChanged"])
            self.assertTrue(scenery["visualDependenciesChanged"])
            for row in scenery["details"]:
                self.assertEqual("visuals-unverified", row["status"])
                self.assertTrue(row["visualWarnings"])
                self.assertEqual(row["previousSemanticSha256"], row["semanticSha256"])
            warnings = [row for row in preview["visualWarnings"] if row["family"] == "scenery"]
            self.assertEqual(len(scenery["visualDependenciesChanged"]), len(warnings))
            self.assertTrue(all(row["name"] and row["messages"] for row in warnings))
            report = subprocess.run(["java", "-cp", str(self.classes), "com.openrsc.worldbuilder.RefreshReviewFixture",
                str(parent), str(runtime), str(target)], capture_output=True, text=True)
            self.assertEqual(0, report.returncode, report.stderr)
            display = json.loads(report.stdout)
            self.assertIn("not confirmed appearance changes", display["summary"])
            self.assertIn("appearance could not be verified", display["summary"])
            self.assertEqual(preview["visualWarnings"], json.loads(display["details"])["unresolvedAppearanceDependencies"])
            self.assertIn("content-visual-resolution-v1.json", display["projectWarning"])
            accepted = self.refresh(parent, runtime, target, "--confirm", "REFRESH",
                "--expected-preview", preview["previewFingerprintSha256"])
            self.assertEqual(0, accepted.returncode, accepted.stderr)
            successor = Path(json.loads(accepted.stdout)["projectRoot"])
            diagnostic = json.loads((successor / "diagnostics/content-visual-resolution-v1.json").read_text())
            self.assertEqual(preview["visualWarnings"], diagnostic["unresolved"])
            self.assertEqual(before, support.tree_bytes(parent))
            self.assertEqual(target_before, support.tree_bytes(target, install))

    def test_exact_unchanged_library_is_noop_but_raw_evidence_updates_are_retained(self):
        with tempfile.TemporaryDirectory(prefix="content-refresh-unchanged-") as temp:
            target, install, parent, _, runtime = self.fixture(Path(temp))
            before = support.tree_bytes(install)
            reviewed = self.refresh(parent, runtime, target)
            self.assertEqual(0, reviewed.returncode, reviewed.stderr)
            preview = json.loads(reviewed.stdout)
            self.assertEqual("unchanged", preview["status"])
            accepted = self.refresh(parent, runtime, target, "--confirm", "REFRESH",
                "--expected-preview", preview["previewFingerprintSha256"])
            self.assertEqual(0, accepted.returncode, accepted.stderr)
            self.assertEqual(parent.name, json.loads(accepted.stdout)["projectId"])
            self.assertEqual(before, support.tree_bytes(install))
            path = target / "server/conf/server/defs/NpcDefs.json"
            path.write_text(json.dumps(json.loads(path.read_text()), separators=(",", ":")))
            reviewed = self.refresh(parent, runtime, target)
            self.assertEqual(0, reviewed.returncode, reviewed.stderr)
            preview = json.loads(reviewed.stdout)
            self.assertEqual("ready", preview["status"])
            self.assertEqual(preview["previousContentSha256"], preview["contentSha256"])
            self.assertEqual(before, support.tree_bytes(install))

    def test_registered_successor_blocks_parent_mutations_but_preserves_editing(self):
        with tempfile.TemporaryDirectory(prefix="content-refresh-historical-boundary-") as temp:
            target, install, parent, export, runtime = self.fixture(Path(temp))
            imported = self.run_reviewed_apply("import-adaptive", "IMPORT", "--project", parent, "--export", export, "--target-root", target)
            self.assertEqual(0, imported.returncode, imported.stderr)
            self.add_npc(target)
            reviewed = self.refresh(parent, runtime, target)
            self.assertEqual(0, reviewed.returncode, reviewed.stderr)
            accepted = self.refresh(parent, runtime, target, "--confirm", "REFRESH",
                "--expected-preview", json.loads(reviewed.stdout)["previewFingerprintSha256"])
            self.assertEqual(0, accepted.returncode, accepted.stderr)
            successor = Path(json.loads(accepted.stdout)["projectRoot"])
            edited = self.next_history_export(parent)  # Historical editing/export remains available.
            target_before = support.tree_bytes(target, install)
            for command in ("import-adaptive", "undo-adaptive", "reverify-target-runtime"):
                with self.subTest(command=command):
                    args = ("--export", edited) if command == "import-adaptive" else ()
                    refused = self.run_cli(command, "--project", parent, "--target-root", target, *args)
                    self.assertEqual(3, refused.returncode, refused.stderr)
                    self.assertIn("registered content successor", refused.stderr)
                    self.assertEqual(target_before, support.tree_bytes(target, install))
            origin = successor / "source/content-refresh/origin.json"
            original = origin.read_bytes()
            for changed in (None, original + b" "):
                with self.subTest(origin="missing" if changed is None else "altered"):
                    if changed is None: origin.unlink()
                    else: origin.write_bytes(changed)
                    refused = self.run_cli("import-adaptive", "--project", parent, "--export", edited, "--target-root", target)
                    self.assertEqual(3, refused.returncode, refused.stderr)
                    self.assertIn("origin.json", refused.stderr)
                    self.assertEqual(target_before, support.tree_bytes(target, install))
                    origin.write_bytes(original)

    def test_changed_used_identity_is_reviewed_and_cannot_be_accepted(self):
        with tempfile.TemporaryDirectory(prefix="content-refresh-conflict-") as temp:
            target, install, project, _, runtime = self.fixture(Path(temp))
            path = target / "server/conf/server/defs/NpcDefsPatch18.json"
            document = json.loads(path.read_text())
            document["npcs"][-1]["name"] = "replacement with a different identity"
            support.write_json(path, document)
            before = support.tree_bytes(project)
            reviewed = self.refresh(project, runtime, target)
            self.assertEqual(0, reviewed.returncode, reviewed.stderr)
            preview = json.loads(reviewed.stdout)
            self.assertEqual("blocked", preview["status"])
            affected = next(row for row in preview["families"] if row["family"] == "npc")["details"]
            self.assertTrue(any(row["mapReferenceCount"] > 0 and row["previousName"] for row in affected))
            accepted = self.refresh(project, runtime, target, "--confirm", "REFRESH", "--expected-preview", preview["previewFingerprintSha256"])
            self.assertEqual(3, accepted.returncode, accepted.stderr)
            self.assertIn("conflicts", accepted.stderr)
            self.assertEqual(before, support.tree_bytes(project))

    def test_used_item_scenery_boundary_changes_report_identity_and_references(self):
        for family, key, identity_key in (("ground-item", "groundItems", "itemId"),
                ("scenery", "scenery", "sceneryId"), ("boundary", "boundaries", "boundaryId")):
            with self.subTest(family=family), tempfile.TemporaryDirectory(prefix="content-refresh-family-conflict-") as temp:
                target, install, parent, _, runtime = self.fixture(Path(temp))
                package = parent / "working/layered-world/package"
                manifest = json.loads((package / "manifest.json").read_text())
                identity = next(row[identity_key] for placement in manifest["placementSets"]
                    for row in json.loads((package / placement["path"]).read_text())[key])
                defs = target / "server/conf/server/defs"
                if family == "ground-item":
                    path = defs / "ItemDefsMyWorld.json"
                    support.write_json(path, {"items": [{"id": identity, "name": "changed placed item"}]})
                else:
                    path = defs / ("GameObjectDef.xml" if family == "scenery" else "DoorDef.xml")
                    document = ET.fromstring(path.read_text())
                    document[identity].find("name").text = "changed placed " + family
                    path.write_text(ET.tostring(document, encoding="unicode"))
                before = support.tree_bytes(parent)
                reviewed = self.refresh(parent, runtime, target)
                self.assertEqual(0, reviewed.returncode, reviewed.stderr)
                preview = json.loads(reviewed.stdout)
                self.assertEqual("blocked", preview["status"])
                details = next(row for row in preview["families"] if row["family"] == family)["details"]
                changed = next(row for row in details if row["id"] == identity)
                self.assertEqual("changed", changed["status"])
                self.assertGreater(changed["mapReferenceCount"], 0)
                self.assertTrue(changed["previousName"])
                self.assertIn("changed placed", changed["name"])
                accepted = self.refresh(parent, runtime, target, "--confirm", "REFRESH",
                    "--expected-preview", preview["previewFingerprintSha256"])
                self.assertEqual(3, accepted.returncode, accepted.stderr)
                self.assertIn("conflicts", accepted.stderr)
                self.assertEqual(before, support.tree_bytes(parent))

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
            additions = next(row for row in preview["families"] if row["family"] == "floor")["details"]
            floor_id = next(row["id"] for row in additions if row["status"] == "added")
            accepted = self.refresh(project, runtime, target, "--confirm", "REFRESH", "--expected-preview", preview["previewFingerprintSha256"])
            self.assertEqual(0, accepted.returncode, accepted.stderr)
            self.assertEqual(before, support.tree_bytes(target, install))
            successor = Path(json.loads(accepted.stdout)["projectRoot"])
            self.set_fixture_ground_overlay(successor / "working/layered-world/package", floor_id + 1)
            exported = self.next_history_export(successor)
            imported = self.run_reviewed_apply("import-adaptive", "IMPORT", "--project", successor, "--export", exported, "--target-root", target)
            self.assertEqual(0, imported.returncode, imported.stderr)

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


    def test_added_item_scenery_boundary_content_can_be_placed_and_imported(self):
        with tempfile.TemporaryDirectory(prefix="content-refresh-families-") as temp:
            target, install, project, _, runtime = self.fixture(Path(temp))
            defs = target / "server/conf/server/defs"
            path = defs / "ItemDefsCustom.json"
            value = json.loads(path.read_text()); value["items"].append({"id": 42, "name": "new custom item", "sprite": "items/0", "pictureMask": 0, "blueMask": 0})
            support.write_json(path, value)
            path = defs / "GameObjectDef.xml"
            path.write_text(path.read_text().replace("</GameObjectDef-array>",
                "<GameObjectDef><name>new collision scenery</name><width>2</width><height>2</height></GameObjectDef></GameObjectDef-array>"))
            path = defs / "DoorDef.xml"
            path.write_text(path.read_text().replace("</DoorDef-array>", "<DoorDef><name>new wall</name></DoorDef></DoorDef-array>"))
            self.sync_catalogs(target)
            reviewed = self.refresh(project, runtime, target)
            self.assertEqual(0, reviewed.returncode, reviewed.stderr)
            preview = json.loads(reviewed.stdout)
            self.assertEqual("ready", preview["status"], preview["blockers"])
            for family in ("ground-item", "scenery", "boundary"):
                self.assertTrue(next(row for row in preview["families"] if row["family"] == family)["added"])
            accepted = self.refresh(project, runtime, target, "--confirm", "REFRESH", "--expected-preview", preview["previewFingerprintSha256"])
            self.assertEqual(0, accepted.returncode, accepted.stderr)
            successor = Path(json.loads(accepted.stdout)["projectRoot"])
            package = successor / "working/layered-world/package"
            manifest = json.loads((package / "manifest.json").read_text())
            pending = {"groundItems": ("itemId", 42), "scenery": ("sceneryId", 22), "boundaries": ("boundaryId", 12)}
            for declaration in manifest["placementSets"]:
                path = package / declaration["path"]; value = json.loads(path.read_text())
                for family, (field, identity) in list(pending.items()):
                    if value[family]: value[family][0][field] = identity; del pending[family]
                support.write_json(path, value); declaration["sha256"] = support.sha256(path)
            self.assertFalse(pending)
            support.write_json(package / "manifest.json", manifest)
            exported = self.next_history_export(successor)
            imported = self.run_reviewed_apply("import-adaptive", "IMPORT", "--project", successor, "--export", exported, "--target-root", target)
            self.assertEqual(0, imported.returncode, imported.stderr)



    def test_refresh_tracks_selected_path_after_packed_alias_changes(self):
        with tempfile.TemporaryDirectory(prefix="content-refresh-role-alias-") as temp:
            base = Path(temp)
            target, install, _, _, runtime = self.fixture(base)
            configuration = "client_version: 10046\nmember_world: true\nbased_map_data: 64\nbased_config_data: 18\nwant_myworld: true\ncustom_landscape: true\n"
            (target / "myworld.conf").write_text(configuration)
            (target / "server/myworld.conf").write_text(configuration)
            selected = self.run_cli("discover-adaptive", "--target-root", target, "--configuration-role", "packed-map-2")
            self.assertEqual(0, selected.returncode, selected.stderr)
            report = base / "alias-discovery.json"; report.write_text(selected.stdout)
            created = self.run_cli("create-project", "--installation-root", install, "--runtime-root", runtime,
                "--target-root", target, "--discovery-report", report, "--display-name", "Aliased project", "--port", "43883", "--confirm", "CREATE")
            self.assertEqual(0, created.returncode, created.stderr)
            parent = Path(json.loads(created.stdout)["projectRoot"])
            exported = self.next_history_export(parent)
            installed = self.run_reviewed_apply("import-adaptive", "IMPORT", "--project", parent, "--export", exported, "--target-root", target)
            self.assertEqual(0, installed.returncode, installed.stderr)
            (target / "myworld.conf").unlink()  # Remove an unselected candidate, never the selected source config.
            self.add_npc(target)
            reviewed = self.refresh(parent, runtime, target)
            self.assertEqual(0, reviewed.returncode, reviewed.stderr)
            self.assertEqual("ready", json.loads(reviewed.stdout)["status"])

    def reverified_content_successor(self, base):
        target_project = self.target_project
        def maintained_target(*args, **kwargs):
            previous = kwargs.get("target_mutator")
            def capture_content(target):
                if previous is not None: previous(target)
                self.complete_content(target)
            kwargs["target_mutator"] = capture_content
            kwargs["installed_standard_floors"] = False
            return target_project(*args, **kwargs)
        self.target_project = maintained_target
        try:
            target, install, parent, export = self.reverification_fixture(base, packed_floors=True)
        finally:
            self.target_project = target_project
        runtime = base / "builder-runtime"
        self.rebuild_fixture(base, target, "none")
        checked = self.run_reviewed_apply("reverify-target-runtime", "REVERIFY", "--project", parent, "--target-root", target)
        self.assertEqual(0, checked.returncode, checked.stderr)
        baseline_receipt_id = next(json.loads(path.read_text())["runtimeReverification"]["baseline"]["transactionId"]
            for path in (parent / "backups").glob("*/mutation-plan.json")
            if "runtimeReverification" in json.loads(path.read_text()))
        history = support.tree_bytes(parent)
        self.add_npc(target)
        reviewed = self.refresh(parent, runtime, target)
        self.assertEqual(0, reviewed.returncode, reviewed.stderr)
        preview = json.loads(reviewed.stdout)
        self.assertEqual("ready", preview["status"], preview["blockers"])
        accepted = self.refresh(parent, runtime, target, "--confirm", "REFRESH", "--expected-preview", preview["previewFingerprintSha256"])
        self.assertEqual(0, accepted.returncode, accepted.stderr)
        successor = Path(json.loads(accepted.stdout)["projectRoot"])
        self.assertEqual(history, support.tree_bytes(parent))
        self.next_history_export(parent)  # Local historical edits do not invalidate immutable runtime provenance.
        history = support.tree_bytes(parent)
        return target, install, parent, runtime, successor, history, baseline_receipt_id

    def test_immediate_reverification_binds_saved_work_and_snapshot_authority(self):
        with tempfile.TemporaryDirectory(prefix="content-refresh-immediate-reverify-") as temp:
            base = Path(temp)
            target, install, parent, runtime, successor, history, _ = self.reverified_content_successor(base)
            self.assertFalse(list((successor / "receipts").glob("*.json")))
            self.assertFalse(list((successor / "exports").iterdir()))
            self.promote_fixture_terrain_to_v2(successor / "working/layered-world/package", 95)
            saved = self.run_cli("save-project", "--project", successor)
            self.assertEqual(0, saved.returncode, saved.stderr)
            saved_before = support.tree_bytes(successor / "working")
            source_before = support.tree_bytes(successor / "source")
            archives = self.rebuild_fixture(base, target, "source,lines,vars")
            target_before = support.tree_bytes(target, install)
            reviewed = self.run_cli("reverify-target-runtime", "--project", successor, "--target-root", target)
            self.assertEqual(0, reviewed.returncode, reviewed.stderr)
            plan = json.loads(reviewed.stdout)
            self.assertIn("snapshotPredecessor", plan["runtimeReverification"])
            self.assertNotIn("predecessor", plan["runtimeReverification"])
            self.assertEqual(successor.name, plan["runtimeReverification"]["snapshotPredecessor"]["projectId"])
            self.assertEqual(parent.name, plan["runtimeReverification"]["baselineProject"]["projectId"])
            exports = list((successor / "exports").iterdir())
            self.assertEqual(1, len(exports))
            repeated = self.run_cli("reverify-target-runtime", "--project", successor, "--target-root", target)
            self.assertEqual(0, repeated.returncode, repeated.stderr)
            self.assertEqual(exports, list((successor / "exports").iterdir()))
            self.assertEqual(saved_before, support.tree_bytes(successor / "working"))
            self.assertEqual(target_before, support.tree_bytes(target, install))
            self.assertFalse(list((successor / "receipts").glob("*.json")))
            self.assertEqual(history, support.tree_bytes(parent))
            # A newer saved map is never silently accepted under the previous confirmation.
            self.promote_fixture_terrain_to_v2(successor / "working/layered-world/package", 96)
            saved = self.run_cli("save-project", "--project", successor)
            self.assertEqual(0, saved.returncode, saved.stderr)
            refused = self.run_cli("reverify-target-runtime", "--project", successor, "--target-root", target,
                "--confirm", "REVERIFY", "--transaction-id", plan["transactionId"], "--plan-sha256", plan["planFingerprintSha256"])
            self.assertEqual(3, refused.returncode, refused.stderr)
            self.assertEqual(target_before, support.tree_bytes(target, install))
            saved_before = support.tree_bytes(successor / "working")
            configuration = target / "server/world-builder-configs/primary.json"
            selected = json.loads(configuration.read_text())
            # Snapshot authority still binds the selected configuration and installed map exactly.
            for path in (configuration, target / selected["serverMapRelativePath"] / "manifest.json"):
                with self.subTest(path=path.relative_to(target).as_posix()):
                    original = path.read_bytes()
                    path.write_bytes(original + b" ")
                    drifted = support.tree_bytes(target, install)
                    rejected = self.run_cli("reverify-target-runtime", "--project", successor, "--target-root", target)
                    self.assertEqual(3, rejected.returncode, rejected.stderr)
                    self.assertEqual(drifted, support.tree_bytes(target, install))
                    self.assertFalse(list((successor / "receipts").glob("*.json")))
                    path.write_bytes(original)
            checked = self.run_reviewed_apply("reverify-target-runtime", "REVERIFY", "--project", successor, "--target-root", target)
            self.assertEqual(0, checked.returncode, checked.stderr)
            self.assertEqual(saved_before, support.tree_bytes(successor / "working"))
            self.assertEqual(source_before, support.tree_bytes(successor / "source"))
            exported = self.run_cli("export-adaptive", "--project", successor)
            self.assertEqual(0, exported.returncode, exported.stderr)
            imported = self.run_reviewed_apply("import-adaptive", "IMPORT", "--project", successor,
                "--export", json.loads(exported.stdout)["exportDirectory"], "--target-root", target)
            self.assertEqual(0, imported.returncode, imported.stderr)
            undone = self.run_reviewed_apply("undo-adaptive", "UNDO", "--project", successor, "--target-root", target)
            self.assertEqual(0, undone.returncode, undone.stderr)
            refused = self.run_cli("undo-adaptive", "--project", successor, "--target-root", target)
            self.assertEqual(3, refused.returncode, refused.stderr)
            self.assertIn("re-verification boundary", refused.stderr)
            for path, value in archives.items(): self.assertEqual(value, (target / path).read_bytes())
            self.assertEqual(history, support.tree_bytes(parent))

    def test_immediate_reverification_interruption_recovery_keeps_rebuilt_archives(self):
        with tempfile.TemporaryDirectory(prefix="content-refresh-immediate-recovery-") as temp:
            base = Path(temp)
            target, install, parent, runtime, successor, history, _ = self.reverified_content_successor(base)
            archives = self.rebuild_fixture(base, target, "source,lines,vars")
            before = support.tree_bytes(target, install)
            saved = support.tree_bytes(successor / "working")
            # The operation has no successful local receipt and must prepare its genuine saved-map export.
            failed = self.run_failure("reverify", "activation-published,any-rollback", successor, target, successor / "exports")
            self.assertEqual(3, failed.returncode, failed.stderr)
            self.assertIn("RECOVERY_REQUIRED", failed.stderr)
            jar = target / "server/core.jar"
            jar.write_bytes(jar.read_bytes() + b"independent-drift")
            rejected = self.run_cli("recover-adaptive", "--project", successor, "--target-root", target)
            self.assertEqual(3, rejected.returncode, rejected.stderr)
            self.assertTrue(jar.read_bytes().endswith(b"independent-drift"))
            jar.write_bytes(archives["server/core.jar"])
            recovered = self.run_reviewed_apply("recover-adaptive", "RECOVER", "--project", successor, "--target-root", target)
            self.assertEqual(0, recovered.returncode, recovered.stderr)
            self.assertEqual(before, support.tree_bytes(target, install))
            self.assertEqual(saved, support.tree_bytes(successor / "working"))
            self.assertEqual(history, support.tree_bytes(parent))
            checked = self.run_reviewed_apply("reverify-target-runtime", "REVERIFY", "--project", successor, "--target-root", target)
            self.assertEqual(0, checked.returncode, checked.stderr)
            exported = self.run_cli("export-adaptive", "--project", successor)
            self.assertEqual(0, exported.returncode, exported.stderr)
            imported = self.run_reviewed_apply("import-adaptive", "IMPORT", "--project", successor,
                "--export", json.loads(exported.stdout)["exportDirectory"], "--target-root", target)
            self.assertEqual(0, imported.returncode, imported.stderr)
            for path, value in archives.items(): self.assertEqual(value, (target / path).read_bytes())
            self.assertEqual(history, support.tree_bytes(parent))

    def test_refresh_preserves_reverification_boundary_then_rebuilds_successor(self):
        with tempfile.TemporaryDirectory(prefix="content-refresh-reverify-chain-") as temp:
            base = Path(temp)
            target, install, parent, runtime, successor, history, baseline_receipt_id = self.reverified_content_successor(base)
            for stage in range(3):
                exported = self.next_history_export(successor)
                imported = self.run_reviewed_apply("import-adaptive", "IMPORT", "--project", successor, "--export", exported, "--target-root", target)
                self.assertEqual(0, imported.returncode, imported.stderr)
                if stage < 2:
                    archives = self.rebuild_fixture(base, target, "source,lines,vars" if stage == 0 else "none")
                    rechecked = self.run_reviewed_apply("reverify-target-runtime", "REVERIFY", "--project", successor, "--target-root", target)
                    self.assertEqual(0, rechecked.returncode, rechecked.stderr)
            for path, value in archives.items(): self.assertEqual(value, (target / path).read_bytes())
            self.assertEqual(history, support.tree_bytes(parent))
            # Missing retained ancestor authority must never be replaced by target-provided hashes.
            receipt = parent / "receipts" / (baseline_receipt_id + ".json")
            original_receipt = receipt.read_bytes()
            receipt.unlink()
            archives = self.rebuild_fixture(base, target, "source,lines,vars")
            refused = self.run_cli("reverify-target-runtime", "--project", successor, "--target-root", target)
            self.assertEqual(3, refused.returncode, refused.stderr)
            for path, value in archives.items(): self.assertEqual(value, (target / path).read_bytes())
            receipt.write_bytes(original_receipt)
            self.assertEqual(history, support.tree_bytes(parent))


def load_tests(loader, tests, pattern):
    return unittest.TestSuite(ContentRefreshTest(name) for name in ContentRefreshTest.__dict__ if name.startswith("test_"))


if __name__ == "__main__":
    unittest.main(verbosity=2)
