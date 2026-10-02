#!/usr/bin/env python3
"""Producer provenance may evolve without relaxing retained runtime authority."""
import gzip
import hashlib
import importlib.util
import json
import shutil
import struct
import subprocess
import tempfile
import unittest
import zipfile
from pathlib import Path

import adaptive_project_test_support as support

spec = importlib.util.spec_from_file_location(
    "producer_refresh", Path(__file__).with_name("test-world-builder-content-refresh.py"))
refresh = importlib.util.module_from_spec(spec)
spec.loader.exec_module(refresh)


class ProducerRefreshTest(refresh.ContentRefreshTest):
    @classmethod
    def setUpClass(cls):
        super().setUpClass()
        source = Path(cls.compile_temp.name) / "ProducerConversionProbe.java"
        source.write_text('''package com.openrsc.worldbuilder;
import java.nio.file.*;
public final class ProducerConversionProbe {
 public static void main(String[] args)throws Exception {
  Path root=Paths.get(args[0]);
  WorldBuilderPackedConversionSource source=WorldBuilderPackedConversionSource.open(root,Paths.get(args[1]));
  if(args.length>2){Path candidate=root.resolve(args[2]);Files.createDirectories(candidate.getParent());
   if(args.length>3)Files.createDirectory(candidate);else Files.write(candidate,new byte[]{1});}
  source.reverify();System.out.println(WorldBuilderJsonDocuments.pretty(source.inputDocuments()));
 }
}''')
        subprocess.run(["javac", "-cp", str(cls.classes), "-d", str(cls.classes), str(source)],
                       check=True, capture_output=True)

    def producer_fixture(self, base):
        from npc_producer_v2_test_support import install_v2_fixture

        def content(target):
            (target / "server/myworld.conf").write_text(
                "client_version: 10046\nmember_world: true\nbased_map_data: 64\n"
                "based_config_data: 18\nwant_myworld: true\ncustom_landscape: true\n")
            self.complete_content(target)
            configuration = json.loads((target / "server/world-builder-configs/primary.json").read_text())
            catalog = json.loads((target / configuration["serverDefinitionCatalogRelativePath"]).read_text())
            manifest, document = install_v2_fixture(target, catalog["npcs"])
            # Sources and flags are installed before the original snapshot.
            # The later exporter is new inert provenance, never a game update.
            document["provider"]["sources"] = [row for row in document["provider"]["sources"]
                                                if row["role"] != "producer-helper-source"]
            (target / "tools/item-visual-provider/FixtureExporter.java").unlink()
            support.write_json(manifest, document)

        def private_runtime(runtime):
            # Synthetic capability fixture only; production runtime parity is
            # independently tested in the provider repository.
            for jar in [runtime / "server/core.jar", runtime / "Client_Base/Open_RSC_Client.jar"]:
                with zipfile.ZipFile(jar) as archive:
                    manifest = archive.read("META-INF/MANIFEST.MF").rstrip(b"\n")
                manifest += (b"\nWorld-Builder-Npc-Rgb: npc-rgb-frames-v1\n"
                             b"World-Builder-Npc-Mask-Policy: npc-mask-policy-v1\n"
                             b"World-Builder-Npc-Animation-Count: 1080\n\n")
                self.rewrite_runtime_entry(jar, "META-INF/MANIFEST.MF", manifest)

        target, install, project, export = self.target_project(
            base, representation="packed", installed_standard_floors=True,
            target_mutator=content, runtime_mutator=private_runtime)
        return target, install, project, export, base / "builder-runtime"

    @staticmethod
    def file_hash(path):
        return hashlib.sha256(path.read_bytes()).hexdigest()

    def add_producer_evidence(self, target):
        manifest = target / "world-builder-provider/npc-definitions-v2.json"
        document = json.loads(manifest.read_text())
        sources = document["provider"]["sources"]

        def add_source(identity, role, relative, data):
            path = target / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(data)
            sources.append({"sourceId": identity, "role": role,
                            "relativePath": relative, "sha256": self.file_hash(path)})

        add_source("new-exporter", "producer-helper-source",
                   "tools/item-visual-provider/export-npc-visuals-v2.py", b"# inert exporter provenance\n")
        add_source("contract-helper", "producer-helper-source",
                   "scripts/generate-world-builder-target-contract.py", b"# inert catalog exporter provenance\n")
        add_source("visual-selector", "configuration-input",
                   "Client_Base/Cache/config.txt", b"Menus:1\n")
        sprite = bytes((0, 1, 0, 0x12, 0x34, 0x56)) + struct.pack(">HHBhhHHB", 1, 1, 0, 0, 0, 1, 1, 0)
        menu = gzip.compress(b"\x01GUI\0\0\x010\0" + sprite, mtime=0)
        add_source("menu-pack", "sprite-input",
                   "Client_Base/Cache/video/spritepacks/Menus.osar", menu)
        document["provider"]["resolutionProbes"] = [row for row in document["provider"]["resolutionProbes"]
                                                      if row["probeId"] != "pack-config"]
        for animation in document["animationDefinitions"]:
            resolution = animation["resolution"]
            resolution["probeIds"].remove("pack-config")
            resolution["inputSourceIds"].append("menu-pack")
            resolution["precedenceSourceIds"].append("menu-pack")
        # New immutable artifact paths hold the same final frames. This changes
        # provenance without inventing an appearance or touching old artifacts.
        asset = document["assetProviders"][0]
        old_artifact = target / asset["targetRelativePath"]
        artifact = "server/conf/world-builder/npc-frames-reviewed.zip"
        (target / artifact).write_bytes(old_artifact.read_bytes())
        (manifest.parent / "npc-frames-reviewed.zip").write_bytes(old_artifact.read_bytes())
        next(row for row in sources if row["sourceId"] == asset["sourceId"])["relativePath"] = artifact
        asset["targetRelativePath"] = artifact
        asset["packageRelativePath"] = "npc-frames-reviewed.zip"
        support.write_json(manifest, document)
        return manifest, document

    def accept_refresh(self, parent, runtime, target):
        reviewed = self.refresh(parent, runtime, target)
        self.assertEqual(0, reviewed.returncode, reviewed.stderr)
        preview = json.loads(reviewed.stdout)
        self.assertEqual("ready", preview["status"], preview.get("blockers"))
        applied = self.refresh(parent, runtime, target, "--confirm", "REFRESH",
                               "--expected-preview", preview["previewFingerprintSha256"])
        self.assertEqual(0, applied.returncode, applied.stderr)
        return Path(json.loads(applied.stdout)["projectRoot"]), preview

    def test_inert_producer_additions_and_visual_selector_preserve_work_and_import_history(self):
        with tempfile.TemporaryDirectory(prefix="producer-refresh-chain-") as temporary:
            base = Path(temporary)
            target, install, parent, export, runtime = self.producer_fixture(base)
            imported = self.run_reviewed_apply("import-adaptive", "IMPORT", "--project", parent,
                                               "--export", export, "--target-root", target)
            self.assertEqual(0, imported.returncode, imported.stderr)
            self.next_history_export(parent)
            parent_before = support.tree_bytes(parent)
            saved = support.tree_bytes(parent / "working/layered-world/package")
            self.add_producer_evidence(target)
            target_before = support.tree_bytes(target, install)
            successor, preview = self.accept_refresh(parent, runtime, target)
            self.assertEqual(parent_before, support.tree_bytes(parent))
            self.assertEqual(saved, support.tree_bytes(successor / "working/layered-world/package"))
            self.assertEqual(target_before, support.tree_bytes(target, install))
            origin = json.loads((successor / "source/content-refresh/origin.json").read_text())
            states = origin["targetAuthority"]["targetStates"]
            self.assertTrue(states["tools/item-visual-provider/export-npc-visuals-v2.py"]["present"])
            self.assertTrue(states["Client_Base/Cache/config.txt"]["present"])
            self.assertFalse(states["Client_Base/dev/myworld/assets/npcs/optional.png"]["present"])
            snapshot = json.loads((successor / "source/snapshot-manifest.json").read_text())
            probe_rows = [row for group in ["originalFiles", "definitionRuntimeFiles"]
                          for row in snapshot[group]
                          if row["relativePath"] == "source/original/Client_Base/dev/myworld/assets/npcs/optional.png"]
            self.assertEqual(1, len(probe_rows))
            self.assertFalse(probe_rows[0]["present"])
            for elevation in [71, 72]:
                self.promote_fixture_terrain_to_v2(successor / "working/layered-world/package", elevation)
                saved_result = self.run_cli("save-project", "--project", successor)
                self.assertEqual(0, saved_result.returncode, saved_result.stderr)
                exported = self.run_cli("export-adaptive", "--project", successor)
                self.assertEqual(0, exported.returncode, exported.stderr)
                imported = self.run_reviewed_apply("import-adaptive", "IMPORT", "--project", successor,
                    "--export", json.loads(exported.stdout)["exportDirectory"], "--target-root", target)
                self.assertEqual(0, imported.returncode, imported.stderr)
            # Maintained selector changes require a new reviewed revision even
            # when a disjoint menu pack cannot change an NPC's resolved pixels.
            manifest = target / "world-builder-provider/npc-definitions-v2.json"
            document = json.loads(manifest.read_text())
            selector = target / "Client_Base/Cache/config.txt"
            selector.write_bytes(b"Menus:0\n")
            next(row for row in document["provider"]["sources"]
                 if row["sourceId"] == "visual-selector")["sha256"] = self.file_hash(selector)
            support.write_json(manifest, document)
            previous_revision = support.tree_bytes(successor)
            revised, _ = self.accept_refresh(successor, runtime, target)
            self.assertNotEqual(revised, successor)
            self.assertEqual(previous_revision, support.tree_bytes(successor))
            self.assertEqual(parent_before, support.tree_bytes(parent))

    def test_absent_candidate_appearance_rejects_preview_confirmation_and_later_import(self):
        with tempfile.TemporaryDirectory(prefix="producer-refresh-probe-") as temporary:
            target, install, parent, export, runtime = self.producer_fixture(Path(temporary))
            self.add_producer_evidence(target)
            reviewed = self.refresh(parent, runtime, target)
            self.assertEqual(0, reviewed.returncode, reviewed.stderr)
            preview = json.loads(reviewed.stdout)
            self.assertEqual("ready", preview["status"])
            optional = target / "Client_Base/dev/myworld/assets/npcs/optional.png"
            optional.parent.mkdir(parents=True, exist_ok=True)
            optional.write_bytes(b"new earlier loader candidate")
            installed_before = support.tree_bytes(install)
            target_before = support.tree_bytes(target, install)
            rejected = self.refresh(parent, runtime, target, "--confirm", "REFRESH",
                                    "--expected-preview", preview["previewFingerprintSha256"])
            self.assertNotEqual(0, rejected.returncode, rejected.stdout)
            self.assertIn("candidate", rejected.stderr)
            self.assertEqual(installed_before, support.tree_bytes(install))
            self.assertEqual(target_before, support.tree_bytes(target, install))
            optional.unlink()
            successor, _ = self.accept_refresh(parent, runtime, target)
            fresh_export = self.next_history_export(successor)
            optional.write_bytes(b"appeared after successor publication")
            target_before = support.tree_bytes(target, install)
            project_before = support.tree_bytes(successor)
            rejected = self.run_cli("import-adaptive", "--project", successor,
                                    "--export", fresh_export, "--target-root", target)
            self.assertNotEqual(0, rejected.returncode, rejected.stdout)
            self.assertEqual(target_before, support.tree_bytes(target, install))
            self.assertEqual(project_before, support.tree_bytes(successor))

    def test_regenerated_producer_hashes_do_not_authorize_runtime_source_or_jar_drift(self):
        with tempfile.TemporaryDirectory(prefix="producer-refresh-runtime-") as temporary:
            target, install, parent, export, runtime = self.producer_fixture(Path(temporary))
            manifest, document = self.add_producer_evidence(target)
            pristine_manifest = manifest.read_bytes()
            for relative in ["server/src/com/openrsc/server/external/EntityHandler.java",
                             "Client_Base/src/com/openrsc/client/entityhandling/EntityHandler.java",
                             "server/core.jar", "server/myworld.conf"]:
                with self.subTest(path=relative):
                    path = target / relative
                    original = path.read_bytes()
                    comment = b"\n// independently changed target input\n" if relative.endswith(".java") else b"\n# independently changed target input\n"
                    path.write_bytes(original + comment)
                    document = json.loads(pristine_manifest)
                    for source in document["provider"]["sources"]:
                        if source["relativePath"] == relative:
                            source["sha256"] = self.file_hash(path)
                    if relative == "server/myworld.conf":
                        document["provider"]["configuration"]["sha256"] = self.file_hash(path)
                    support.write_json(manifest, document)
                    installed_before = support.tree_bytes(install)
                    target_before = support.tree_bytes(target, install)
                    rejected = self.refresh(parent, runtime, target)
                    self.assertNotEqual(0, rejected.returncode, rejected.stdout)
                    self.assertEqual(installed_before, support.tree_bytes(install))
                    self.assertEqual(target_before, support.tree_bytes(target, install))
                    path.write_bytes(original)
                    manifest.write_bytes(pristine_manifest)

    def test_conversion_accepts_only_manifest_bound_absence_and_rechecks_it(self):
        with tempfile.TemporaryDirectory(prefix="producer-conversion-absence-") as temporary:
            base = Path(temporary)
            target, install, parent, export, runtime = self.producer_fixture(base)
            original_report = json.loads((parent / "discovery/report.json").read_text())
            parent_before = support.tree_bytes(parent)
            counter = 0

            def run(document, *appearance):
                nonlocal counter
                counter += 1
                source = base / ("isolated-source-" + str(counter))
                shutil.copytree(parent / "source/original", source)
                report = base / ("report-" + str(counter) + ".json")
                display = document["targetRootDisplay"]
                document["targetRootDisplay"] = ""
                document["files"].sort(key=lambda row: (row["relativePath"], row["role"]))
                self.bind_fingerprint(document, "discoveryFingerprintSha256")
                document["targetRootDisplay"] = display
                support.write_json(report, document)
                return subprocess.run(["java", "-cp", str(self.classes),
                    "com.openrsc.worldbuilder.ProducerConversionProbe", str(source), str(report), *appearance],
                    capture_output=True, text=True)

            accepted = run(json.loads(json.dumps(original_report)))
            self.assertEqual(0, accepted.returncode, accepted.stderr)
            self.assertTrue(all(row["present"] for row in json.loads(accepted.stdout)))
            for role in ["npc-producer-v2-probe", "server-runtime"]:
                document = json.loads(json.dumps(original_report))
                document["files"].append({"role": role, "relativePath": "server/undeclared-required-input.bin",
                                          "present": False, "size": 0, "sha256": ""})
                rejected = run(document)
                self.assertNotEqual(0, rejected.returncode, rejected.stdout)
                self.assertIn("absence evidence", rejected.stderr)
            for kind in [(), ("directory",)]:
                rejected = run(json.loads(json.dumps(original_report)),
                               "Client_Base/dev/myworld/assets/npcs/optional.png", *kind)
                self.assertNotEqual(0, rejected.returncode, rejected.stdout)
                self.assertIn("previously absent visual candidate", rejected.stderr)
            self.assertEqual(parent_before, support.tree_bytes(parent))


def load_tests(loader, tests, pattern):
    return unittest.TestSuite(ProducerRefreshTest(name) for name in ProducerRefreshTest.__dict__
                              if name.startswith("test_"))


if __name__ == "__main__":
    unittest.main(verbosity=2)
