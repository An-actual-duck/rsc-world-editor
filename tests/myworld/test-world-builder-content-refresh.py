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
  Map<String,Object> old=WorldBuilderJsonDocuments.readObject(target.requiredFile(config.serverDefinitionCatalogRelativePath));
  Map<String,Object> catalog=WorldBuilderProjectContentBundle.deriveTargetCatalog(target,
   WorldBuilderPackedSourceLayout.selectContentRoots(target,WorldBuilderPackedSourceLayout.CANONICAL_CONFIGURATION),(String)old.get("catalogId"));
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
        subprocess.run(["javac", "-cp", str(cls.classes), "-d", str(cls.classes), str(harness)], check=True, capture_output=True, text=True)

    def sync_catalogs(self, target):
        result = subprocess.run(["java", "-cp", str(self.classes), "com.openrsc.worldbuilder.RefreshCatalogFixture", str(target)], capture_output=True, text=True)
        self.assertEqual(0, result.returncode, result.stderr)

    def fixture(self, base):
        target, installation, project, export = self.target_project(base, representation="packed", installed_standard_floors=True, target_mutator=self.sync_catalogs)
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
            self.assertEqual("ready", preview["status"])
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


def load_tests(loader, tests, pattern):
    return unittest.TestSuite(ContentRefreshTest(name) for name in ContentRefreshTest.__dict__ if name.startswith("test_"))


if __name__ == "__main__":
    unittest.main(verbosity=2)
