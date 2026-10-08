#!/usr/bin/env python3
"""Producer provenance may evolve without relaxing retained runtime authority."""
import copy
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
import warnings
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
  if("preview-note".equals(args[0])){System.out.print(WorldBuilderNpcProducerFrames.projectPreviewSummary(Paths.get(args[1])));return;}
  if("snapshot-baseline".equals(args[0])){WorldBuilderSnapshotRuntimeBaseline.validate(WorldBuilderJsonDocuments.readObject(Paths.get(args[1])));return;}
  if("reverification-shape".equals(args[0])){WorldBuilderRuntimeReverification.validateShape(WorldBuilderJsonDocuments.readObject(Paths.get(args[1])));return;}
  if("producer-binding".equals(args[0])){WorldBuilderProducerArchiveReverification.validate(WorldBuilderJsonDocuments.readObject(Paths.get(args[1])));return;}
  Path root=Paths.get(args[0]);
  WorldBuilderPackedConversionSource source=WorldBuilderPackedConversionSource.open(root,Paths.get(args[1]));
  if(args.length>2){Path candidate=root.resolve(args[2]);Files.createDirectories(candidate.getParent());
   if(args.length>3)Files.createDirectory(candidate);else Files.write(candidate,new byte[]{1});}
  source.reverify();System.out.println(WorldBuilderJsonDocuments.pretty(source.inputDocuments()));
 }
}''')
        subprocess.run(["javac", "-cp", str(cls.classes), "-d", str(cls.classes), str(source)],
                       check=True, capture_output=True)

    def producer_fixture(self, base, frame_count=15):
        from npc_producer_v2_test_support import install_v2_fixture

        def content(target):
            (target / "server/myworld.conf").write_text(
                "client_version: 10046\nmember_world: true\nbased_map_data: 64\n"
                "based_config_data: 18\nwant_myworld: true\ncustom_landscape: true\n")
            self.complete_content(target)
            configuration = json.loads((target / "server/world-builder-configs/primary.json").read_text())
            catalog = json.loads((target / configuration["serverDefinitionCatalogRelativePath"]).read_text())
            manifest, document = install_v2_fixture(target, catalog["npcs"], frame_count=frame_count)
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

    def test_intentional_preview_limits_are_bound_and_shown_separately_from_missing_visuals(self):
        with tempfile.TemporaryDirectory(prefix="producer-preview-notes-") as temporary:
            target, install, parent, export, runtime = self.producer_fixture(Path(temporary), frame_count=21)
            parent_before = support.tree_bytes(parent)
            manifest, document = self.add_producer_evidence(target)
            document["npcDefinitions"][0]["hairColour"] += 1
            support.write_json(manifest, document)
            target_before = support.tree_bytes(target, install)
            reviewed = self.refresh(parent, runtime, target)
            self.assertEqual(0, reviewed.returncode, reviewed.stderr)
            preview = json.loads(reviewed.stdout)
            limitations = preview["authoringPreviewLimitations"]
            self.assertTrue(limitations)
            self.assertTrue(all(row["resolvedFrameCount"] == 21 and len(row["authoringFrameIndices"]) == 18
                                for row in limitations))
            self.assertTrue(all(row["name"] and row["limitation"] == "source-secondary-attack-not-previewed"
                                for row in limitations))
            self.assertFalse([row for row in preview["visualWarnings"] if row["family"] == "npc"])
            display = subprocess.run(["java", "-cp", str(self.classes), "com.openrsc.worldbuilder.RefreshReviewFixture",
                                      str(parent), str(runtime), str(target)], capture_output=True, text=True)
            self.assertEqual(0, display.returncode, display.stderr)
            visible = json.loads(display.stdout)
            self.assertIn("NPC authoring preview notes", visible["summary"])
            self.assertIn("secondary attack is not animated", visible["summary"])
            self.assertIn("visual evidence changes", visible["summary"])
            self.assertNotIn("verified visual changes", visible["summary"])
            self.assertIn("do not prove every entry looks different", visible["summary"])
            self.assertEqual(limitations, json.loads(visible["details"])["authoringPreviewLimitations"])
            repeated = self.refresh(parent, runtime, target)
            self.assertEqual(0, repeated.returncode, repeated.stderr)
            self.assertEqual(preview["previewFingerprintSha256"], json.loads(repeated.stdout)["previewFingerprintSha256"])
            applied = self.refresh(parent, runtime, target, "--confirm", "REFRESH",
                                   "--expected-preview", preview["previewFingerprintSha256"])
            self.assertEqual(0, applied.returncode, applied.stderr)
            successor = Path(json.loads(applied.stdout)["projectRoot"])
            report = json.loads((successor / "diagnostics/npc-producer-v2-resolution.json").read_text())
            self.assertEqual(limitations, report["previewLimitations"])
            note = subprocess.run(["java", "-cp", str(self.classes), "com.openrsc.worldbuilder.ProducerConversionProbe",
                                   "preview-note", str(successor)], capture_output=True, text=True)
            self.assertEqual(0, note.returncode, note.stderr)
            self.assertIn("secondary attacks", note.stdout)
            self.assertIn(str(successor / "diagnostics/npc-producer-v2-resolution.json"), note.stdout)
            self.assertEqual(parent_before, support.tree_bytes(parent))
            self.assertEqual(target_before, support.tree_bytes(target, install))

    def test_producer_archive_binding_java_and_json_schema_agree(self):
        try:
            import jsonschema
        except ImportError:
            self.skipTest("optional jsonschema module unavailable")
        schemas = Path(__file__).resolve().parents[2] / "tools/world-builder/schema"
        full = json.loads((schemas / "target-mutation-plan-v1.schema.json").read_text())
        common = json.loads(
            (schemas / "adaptive-contract-definitions-v1.schema.json").read_text()
        )
        schema = full["properties"]["runtimeReverification"]["properties"][
            "producerArchiveBinding"
        ]
        with warnings.catch_warnings():
            warnings.simplefilter("ignore", DeprecationWarning)
            resolver = jsonschema.RefResolver.from_schema(
                full, store={common["$id"]: common}
            )
        validator = jsonschema.Draft202012Validator(schema, resolver=resolver)
        binding = {
            "relativePath": "world-builder-provider/npc-definitions-v2.json",
            "before": {"present": True, "size": 2, "sha256": "a" * 64},
            "after": {"present": True, "size": 3, "sha256": "b" * 64},
            "beforeContentRelativePath": "source/original/world-builder-provider/npc-definitions-v2.json",
            "contentRelativePath": "backups/11111111-1111-4111-8111-111111111111/content/producer/archive-binding.json",
        }
        variants = [(binding, True)]
        for field, value in [
            ("present", False),
            ("size", 1),
            ("size", 16777217),
            ("sha256", "bad"),
        ]:
            for side in ["before", "after"]:
                invalid = copy.deepcopy(binding)
                invalid[side][field] = value
                variants.append((invalid, False))
        invalid = copy.deepcopy(binding)
        invalid["unexpected"] = 1
        variants.append((invalid, False))
        with tempfile.TemporaryDirectory(
            prefix="producer-binding-schema-"
        ) as temporary:
            path = Path(temporary) / "binding.json"
            for document, valid in variants:
                support.write_json(path, document)
                java = subprocess.run(
                    [
                        "java",
                        "-cp",
                        str(self.classes),
                        "com.openrsc.worldbuilder.ProducerConversionProbe",
                        "producer-binding",
                        str(path),
                    ],
                    capture_output=True,
                    text=True,
                )
                self.assertEqual(valid, java.returncode == 0, java.stderr)
                self.assertEqual(valid, not list(validator.iter_errors(document)), document)

    def producer_rebuild_fixture(self, base, alternate_configuration=False):
        from npc_producer_v2_test_support import install_v2_fixture

        original = self.target_project

        def prepared(*args, **kwargs):
            previous = kwargs.get("target_mutator")

            def content(target):
                [
                    path.rename(target / "Client_Base" / path.name)
                    for path in (target / "client").iterdir()
                ]
                (target / "client").rmdir()
                for path in target.rglob("*.json"):
                    path.write_text(path.read_text().replace("client/", "Client_Base/"))
                conf = target / "server/myworld.conf"
                conf.write_text(
                    "client_version: 10046\nmember_world: true\nbased_map_data: 64\nbased_config_data: 18\nwant_myworld: true\ncustom_landscape: true\ncustom_sprites: false\nallow_bearded_ladies: false\n"
                )
                self.complete_content(target)
                for path, package, name in [
                    (
                        "server/src/com/openrsc/server/external/EntityHandler.java",
                        "com.openrsc.server.external",
                        "EntityHandler",
                    ),
                    ("Client_Base/src/orsc/mudclient.java", "orsc", "mudclient"),
                    (
                        "Client_Base/src/orsc/graphics/two/GraphicsController.java",
                        "orsc.graphics.two",
                        "GraphicsController",
                    ),
                ]:
                    p = target / path
                    if not p.exists():
                        p.parent.mkdir(parents=True, exist_ok=True)
                        p.write_text(
                            "package " + package + "; public class " + name + " {}"
                        )
                previous(target)
                compiled = base / "producer-client-classes"
                compiled.mkdir()
                subprocess.run(
                    [
                        "javac",
                        "-source",
                        "8",
                        "-target",
                        "8",
                        "-d",
                        str(compiled),
                        *map(str, (target / "Client_Base/src").rglob("*.java")),
                    ],
                    check=True,
                    capture_output=True,
                )
                for file in compiled.rglob("*.class"):
                    self.rewrite_runtime_entry(
                        target / "Client_Base/Open_RSC_Client.jar",
                        file.relative_to(compiled).as_posix(),
                        file.read_bytes(),
                    )
                for index in range(6):
                    self.rewrite_runtime_entry(
                        target / "Client_Base/Open_RSC_Client.jar",
                        f"myworld-assets/npc/probe-{index}.dat",
                        bytes([index, 21, 42]),
                    )
                support.declare_effective_content_sources(target, item_sources=())
                if alternate_configuration:
                    conf.rename(target / "server/maintained.conf")
                    support.declare_effective_content_sources(
                        target, item_sources=(), configuration="server/maintained.conf"
                    )

            def runtime(runtime):
                for jar in [
                    runtime / "server/core.jar",
                    runtime / "Client_Base/Open_RSC_Client.jar",
                ]:
                    with zipfile.ZipFile(jar) as arc:
                        manifest = arc.read("META-INF/MANIFEST.MF").rstrip(b"\n")
                    manifest += b"\nWorld-Builder-Npc-Rgb: npc-rgb-frames-v1\nWorld-Builder-Npc-Mask-Policy: npc-mask-policy-v1\nWorld-Builder-Npc-Animation-Count: 1080\n\n"
                    self.rewrite_runtime_entry(jar, "META-INF/MANIFEST.MF", manifest)

            kwargs.update(target_mutator=content, runtime_mutator=runtime)
            return original(*args, **kwargs)

        self.target_project = prepared
        try:
            target, install, parent, export = self.reverification_fixture(
                base, packed_floors=True
            )
        finally:
            self.target_project = original
        # Synthetic exporter uses canonical input paths; restore alternate
        # configuration and its exact descriptor before any tool operation.
        selector = (
            target / "server/conf/world-builder/effective-content-sources-v1.json"
        )
        selector_bytes = selector.read_bytes()
        if alternate_configuration:
            (target / "server/myworld.conf").write_bytes(
                (target / "server/maintained.conf").read_bytes()
            )
            support.declare_effective_content_sources(target, item_sources=())
        self.sync_catalogs(target)
        config = json.loads(
            (target / "server/world-builder-configs/primary.json").read_text()
        )
        catalog = json.loads(
            (target / config["serverDefinitionCatalogRelativePath"]).read_text()
        )
        preserved = {p: p.read_bytes() for p in target.rglob("*.java")}
        configbytes = (target / "server/myworld.conf").read_bytes()
        manifest, doc = install_v2_fixture(target, catalog["npcs"])
        for p, data in preserved.items():
            p.write_bytes(data)
        assert (target / "server/myworld.conf").read_bytes() == configbytes
        for row in doc["provider"]["sources"]:
            row["sha256"] = hashlib.sha256(
                (target / row["relativePath"]).read_bytes()
            ).hexdigest()
        for animation in doc["animationDefinitions"]:
            animation["resolution"]["resolverSourceSha256"] = next(
                row["sha256"]
                for row in doc["provider"]["sources"]
                if row["sourceId"] == animation["resolution"]["resolverSourceId"]
            )
        jar = target / "Client_Base/Open_RSC_Client.jar"
        doc["provider"]["sources"].append(
            {
                "sourceId": "active-client",
                "role": "client-visual-archive",
                "relativePath": "Client_Base/Open_RSC_Client.jar",
                "sha256": hashlib.sha256(jar.read_bytes()).hexdigest(),
            }
        )
        for index in range(11):
            probe = {
                "probeId": f"embedded-{index}",
                "kind": "archive-entry",
                "archiveRelativePath": "Client_Base/Open_RSC_Client.jar",
                "archiveSha256": self.file_hash(jar),
                "entryPath": f"myworld-assets/npc/probe-{index}.dat",
                "present": index < 6,
            }
            if index < 6:
                probe["entrySha256"] = hashlib.sha256(
                    bytes([index, 21, 42])
                ).hexdigest()
            doc["provider"]["resolutionProbes"].append(probe)
        for animation in doc["animationDefinitions"]:
            animation["resolution"]["probeIds"] = [
                row["probeId"] for row in doc["provider"]["resolutionProbes"]
            ]
            animation["resolution"]["inputSourceIds"].append("active-client")
            animation["resolution"]["precedenceSourceIds"].append("active-client")
        if alternate_configuration:
            (target / "server/myworld.conf").unlink()
            selector.write_bytes(selector_bytes)
            doc["provider"]["configuration"]["relativePath"] = "server/maintained.conf"
        support.write_json(manifest, doc)
        runtime = base / "builder-runtime"
        preview = self.refresh(parent, runtime, target)
        self.assertEqual(0, preview.returncode, preview.stderr)
        data = json.loads(preview.stdout)
        self.assertEqual("ready", data["status"], data)
        created = self.refresh(
            parent,
            runtime,
            target,
            "--confirm",
            "REFRESH",
            "--expected-preview",
            data["previewFingerprintSha256"],
        )
        self.assertEqual(0, created.returncode, created.stderr)
        project = Path(json.loads(created.stdout)["projectRoot"])
        return target, install, project, runtime, manifest

    def repack_producer_client(self, target, manifest, generation, refresh=True):
        jar = target / "Client_Base/Open_RSC_Client.jar"
        with zipfile.ZipFile(jar) as archive:
            before = {name: archive.read(name) for name in archive.namelist()}
        with zipfile.ZipFile(jar, "w") as archive:
            for name, data in sorted(before.items(), reverse=True):
                info = zipfile.ZipInfo(
                    name, date_time=(2001 + generation, 2, 3, 4, 5, 6)
                )
                archive.writestr(info, data)
        with zipfile.ZipFile(jar) as archive:
            self.assertEqual(
                before, {name: archive.read(name) for name in archive.namelist()}
            )
        if refresh:
            self.refresh_producer_archive_hash(manifest, jar)
        return jar.read_bytes()

    def refresh_producer_archive_hash(self, manifest, jar):
        document = json.loads(manifest.read_text())
        for row in document["provider"]["sources"]:
            if row["role"] == "client-visual-archive":
                row["sha256"] = self.file_hash(jar)
        for row in document["provider"]["resolutionProbes"]:
            if row["kind"] == "archive-entry":
                row["archiveSha256"] = self.file_hash(jar)
        support.write_json(manifest, document)

    def apply_rebuild(self, project, target):
        result = self.run_reviewed_apply(
            "reverify-target-runtime",
            "REVERIFY",
            "--project",
            project,
            "--target-root",
            target,
        )
        self.assertEqual(0, result.returncode, result.stderr)
        return json.loads(result.stdout)

    def test_snapshot_baseline_java_and_json_schema_variants_agree(self):
        import jsonschema
        schemas = Path(__file__).resolve().parents[2] / "tools/world-builder/schema"
        full = json.loads((schemas / "target-mutation-plan-v1.schema.json").read_text())
        common = json.loads((schemas / "adaptive-contract-definitions-v1.schema.json").read_text())
        with warnings.catch_warnings():
            warnings.simplefilter("ignore", DeprecationWarning)
            resolver = jsonschema.RefResolver.from_schema(full, store={common["$id"]: common})
        schema = full["properties"]["runtimeReverification"]
        validator = jsonschema.Draft202012Validator(schema, resolver=resolver)
        identity = {"projectId": "11111111-1111-4111-8111-111111111111", "sourceFingerprintSha256": "a" * 64}
        binding = dict(identity, proofRelativePath="source/original/server/conf/world-builder/installed-target-map-integration-v1.json",
            proofSha256="b" * 64, archives=[{"relativePath": "source/original/server/core.jar", "size": 200, "sha256": "c" * 64}])
        ref = {"transactionId": "22222222-2222-4222-8222-222222222222", "receiptSha256": "d" * 64, "mutationPlanSha256": "e" * 64}
        snapshot = {"snapshotBaseline": binding, "snapshotPredecessor": identity, "inputs": {"server/core.jar": {"present": True, "size": 200, "sha256": "f" * 64}}, "inventories": []}
        variants = [(snapshot, True)]
        previous = copy.deepcopy(snapshot)
        previous.pop("snapshotPredecessor")
        previous["predecessor"] = ref
        variants.append((previous, True))
        legacy = copy.deepcopy(previous)
        legacy.pop("snapshotBaseline")
        legacy["baseline"] = ref
        variants.append((legacy, True))
        for field, value in [("proofRelativePath", "server/proof.json"), ("proofSha256", "bad"),
                             ("projectId", "not-a-uuid"), ("archives", []), ("unexpected", 1)]:
            invalid = copy.deepcopy(snapshot)
            invalid["snapshotBaseline"][field] = value
            variants.append((invalid, False))
        for field, value in [("relativePath", "source/original/../core.jar"), ("relativePath", "source/original/core.txt"),
                             ("size", -1), ("size", 268435457), ("sha256", "bad")]:
            invalid = copy.deepcopy(snapshot)
            invalid["snapshotBaseline"]["archives"][0][field] = value
            variants.append((invalid, False))
        invalid = copy.deepcopy(snapshot)
        invalid["snapshotBaseline"]["archives"] *= 2
        variants.append((invalid, False))
        for field, value in [("baseline", ref), ("predecessor", ref)]:
            invalid = copy.deepcopy(snapshot)
            invalid[field] = value
            variants.append((invalid, False))
        invalid = copy.deepcopy(snapshot)
        invalid["snapshotPredecessor"]["originSha256"] = "a" * 64
        variants.append((invalid, False))
        with tempfile.TemporaryDirectory(prefix="snapshot-baseline-schema-") as temporary:
            path = Path(temporary) / "shape.json"
            for document, valid in variants:
                support.write_json(path, document)
                java = subprocess.run(["java", "-cp", str(self.classes), "com.openrsc.worldbuilder.ProducerConversionProbe",
                    "reverification-shape", str(path)], capture_output=True, text=True)
                self.assertEqual(valid, java.returncode == 0, java.stderr)
                self.assertEqual(valid, not list(validator.iter_errors(document)), document)

    def fresh_integrated_producer_project(self, base):
        target, old_install, parent, runtime, manifest = self.producer_rebuild_fixture(base)
        before = support.tree_bytes(target, old_install)
        old_history = support.tree_bytes(parent)
        discovery = self.run_cli("discover-adaptive", "--target-root", target)
        self.assertEqual(0, discovery.returncode, discovery.stderr)
        report = base / "fresh-integrated-discovery.json"
        report.write_text(discovery.stdout)
        installation = base / "fresh-editor"
        installation.mkdir()
        created = self.run_cli("create-project", "--installation-root", installation,
            "--runtime-root", runtime, "--target-root", target, "--discovery-report", report,
            "--display-name", "Fresh already integrated", "--port", "43894", "--confirm", "CREATE")
        self.assertEqual(0, created.returncode, created.stderr)
        self.assertEqual(before, support.tree_bytes(target, old_install))
        self.assertEqual(old_history, support.tree_bytes(parent))
        project = Path(json.loads(created.stdout)["projectRoot"])
        snapshot = json.loads((project / "source/snapshot-manifest.json").read_text())
        build_row = next(row for family in ("originalFiles", "definitionRuntimeFiles") for row in snapshot[family]
                         if row["relativePath"] == "source/original/server/build.xml")
        self.assertEqual("installed-map-integration-build", build_row["role"])
        self.assertEqual((target / "server/build.xml").read_bytes(), (project / build_row["relativePath"]).read_bytes())
        proof = json.loads((target / "server/conf/world-builder/installed-target-map-integration-v1.json").read_text())
        self.assertNotEqual(proof["beforeInputs"]["server/build.xml"], build_row["sha256"])
        return target, installation, project, runtime, manifest

    def test_captured_installed_baseline_rebuilds_after_maps_and_through_content_successor(self):
        with tempfile.TemporaryDirectory(prefix="snapshot-baseline-chain-") as temporary:
            target, install, project, runtime, manifest = self.fresh_integrated_producer_project(Path(temporary))
            original = support.tree_bytes(project / "source")
            for _ in range(2):
                export = self.next_history_export(project)
                imported = self.run_reviewed_apply("import-adaptive", "IMPORT", "--project", project,
                    "--export", export, "--target-root", target)
                self.assertEqual(0, imported.returncode, imported.stderr)
            self.next_history_export(project)
            saved = support.tree_bytes(project / "working")
            first_binding = None
            for generation in range(2):
                rebuilt = self.repack_producer_client(target, manifest, generation)
                applied = self.apply_rebuild(project, target)
                plan = json.loads((project / "backups" / applied["transactionId"] / "mutation-plan.json").read_text())
                evidence = plan["runtimeReverification"]
                self.assertIn("predecessor", evidence)
                self.assertNotIn("baseline", evidence)
                self.assertNotIn("baselineProject", evidence)
                binding = evidence["snapshotBaseline"]
                self.assertEqual(project.name, binding["projectId"])
                if first_binding is None: first_binding = binding
                self.assertEqual(first_binding, binding)
                self.assertEqual(original, support.tree_bytes(project / "source"))
                self.assertEqual(saved, support.tree_bytes(project / "working"))
                self.assertEqual(rebuilt, (target / "Client_Base/Open_RSC_Client.jar").read_bytes())
                for _ in range(2):
                    export = self.next_history_export(project)
                    imported = self.run_reviewed_apply("import-adaptive", "IMPORT", "--project", project,
                        "--export", export, "--target-root", target)
                    self.assertEqual(0, imported.returncode, imported.stderr)
                saved = support.tree_bytes(project / "working")
            history = support.tree_bytes(project)
            successor, _ = self.accept_refresh(project, runtime, target)
            rebuilt = self.repack_producer_client(target, manifest, 2)
            applied = self.apply_rebuild(successor, target)
            evidence = json.loads((successor / "backups" / applied["transactionId"] / "mutation-plan.json").read_text())["runtimeReverification"]
            self.assertEqual(first_binding, evidence["snapshotBaseline"])
            self.assertEqual(project.name, evidence["baselineProject"]["projectId"])
            self.assertIn("snapshotPredecessor", evidence)
            for elevation in (181, 182):
                self.promote_fixture_terrain_to_v2(successor / "working/layered-world/package", elevation)
                self.assertEqual(0, self.run_cli("save-project", "--project", successor).returncode)
                exported = self.run_cli("export-adaptive", "--project", successor)
                self.assertEqual(0, exported.returncode, exported.stderr)
                imported = self.run_reviewed_apply("import-adaptive", "IMPORT", "--project", successor,
                    "--export", json.loads(exported.stdout)["exportDirectory"], "--target-root", target)
                self.assertEqual(0, imported.returncode, imported.stderr)
            self.assertEqual(history, support.tree_bytes(project))
            opened = self.run_cli("open-project", "--installation-root", install, "--target-root", target)
            self.assertEqual(0, opened.returncode, opened.stderr)
            self.assertEqual(rebuilt, (target / "Client_Base/Open_RSC_Client.jar").read_bytes())

    def test_captured_installed_baseline_first_reverification_and_undo_boundary(self):
        with tempfile.TemporaryDirectory(prefix="snapshot-baseline-first-") as temporary:
            target, install, project, runtime, manifest = self.fresh_integrated_producer_project(Path(temporary))
            self.next_history_export(project)
            original = support.tree_bytes(project / "source")
            saved = support.tree_bytes(project / "working")
            rebuilt = self.repack_producer_client(target, manifest, 0)
            applied = self.apply_rebuild(project, target)
            evidence = json.loads((project / "backups" / applied["transactionId"] / "mutation-plan.json").read_text())["runtimeReverification"]
            self.assertEqual({"projectId", "sourceFingerprintSha256"}, set(evidence["snapshotPredecessor"]))
            self.assertNotIn("baseline", evidence)
            self.assertEqual(original, support.tree_bytes(project / "source"))
            self.assertEqual(saved, support.tree_bytes(project / "working"))
            refused = self.run_cli("undo-adaptive", "--project", project, "--target-root", target)
            self.assertEqual(3, refused.returncode, refused.stderr)
            self.assertIn("re-verification boundary", refused.stderr)
            for _ in range(2):
                export = self.next_history_export(project)
                imported = self.run_reviewed_apply("import-adaptive", "IMPORT", "--project", project,
                    "--export", export, "--target-root", target)
                self.assertEqual(0, imported.returncode, imported.stderr)
            undone = self.run_reviewed_apply("undo-adaptive", "UNDO", "--project", project, "--target-root", target)
            self.assertEqual(0, undone.returncode, undone.stderr)
            self.assertEqual(rebuilt, (target / "Client_Base/Open_RSC_Client.jar").read_bytes())

    def test_captured_installed_baseline_missing_tampered_evidence_and_preview_drift_refuse(self):
        with tempfile.TemporaryDirectory(prefix="snapshot-baseline-refusal-") as temporary:
            target, install, project, runtime, manifest = self.fresh_integrated_producer_project(Path(temporary))
            export = self.next_history_export(project)
            imported = self.run_reviewed_apply("import-adaptive", "IMPORT", "--project", project,
                "--export", export, "--target-root", target)
            self.assertEqual(0, imported.returncode, imported.stderr)
            self.repack_producer_client(target, manifest, 0)
            saved = support.tree_bytes(project / "working")
            proof_path = "server/conf/world-builder/installed-target-map-integration-v1.json"
            proof = json.loads((project / "source/original" / proof_path).read_text())
            for relative in [proof_path, "server/build.xml"] + [row["relativePath"] for row in proof["archives"]]:
                file = project / "source/original" / relative
                original = file.read_bytes()
                for changed in (None, original + b" "):
                    if changed is None: file.unlink()
                    else: file.write_bytes(changed)
                    before = support.tree_bytes(target)
                    rejected = self.run_cli("reverify-target-runtime", "--project", project, "--target-root", target)
                    self.assertEqual(3, rejected.returncode, rejected.stderr)
                    self.assertEqual(before, support.tree_bytes(target))
                    self.assertEqual(saved, support.tree_bytes(project / "working"))
                    file.write_bytes(original)
            preview = self.run_cli("reverify-target-runtime", "--project", project, "--target-root", target)
            self.assertEqual(0, preview.returncode, preview.stderr)
            self.repack_producer_client(target, manifest, 1)
            before = support.tree_bytes(target)
            refused = self.run_reviewed_apply("reverify-target-runtime", "REVERIFY", "--project", project,
                "--target-root", target, preview=preview)
            self.assertEqual(3, refused.returncode, refused.stderr)
            self.assertEqual(before, support.tree_bytes(target))

    def test_captured_installed_baseline_interrupted_recovery_retains_rebuilt_runtime(self):
        with tempfile.TemporaryDirectory(prefix="snapshot-baseline-recovery-") as temporary:
            target, install, project, runtime, manifest = self.fresh_integrated_producer_project(Path(temporary))
            self.next_history_export(project)
            rebuilt = self.repack_producer_client(target, manifest, 0)
            before = support.tree_bytes(target)
            original = support.tree_bytes(project / "source")
            saved = support.tree_bytes(project / "working")
            failed = self.run_failure("reverify", "activation-published,any-rollback", project, target, project / "exports")
            self.assertEqual(3, failed.returncode, failed.stderr)
            self.assertIn("RECOVERY_REQUIRED", failed.stderr)
            recovered = self.run_reviewed_apply("recover-adaptive", "RECOVER", "--project", project, "--target-root", target)
            self.assertEqual(0, recovered.returncode, recovered.stderr)
            self.assertEqual(before, support.tree_bytes(target))
            self.assertEqual(original, support.tree_bytes(project / "source"))
            self.assertEqual(saved, support.tree_bytes(project / "working"))
            self.assertEqual(rebuilt, (target / "Client_Base/Open_RSC_Client.jar").read_bytes())
            self.apply_rebuild(project, target)

    def test_rebuild_export_reverification_repeats_and_preserves_content_history(self):
        with tempfile.TemporaryDirectory(prefix="producer-rebuild-chain-") as temporary:
            target, install, project, runtime, manifest = self.producer_rebuild_fixture(
                Path(temporary)
            )
            self.next_history_export(project)  # Saved but not imported editor work.
            saved = support.tree_bytes(project / "working")
            original = support.tree_bytes(project / "source")
            old = manifest.read_bytes()
            prior_binding = None
            for generation in range(3):
                rebuilt = self.repack_producer_client(target, manifest, generation)
                before = support.tree_bytes(target, install)
                applied = self.apply_rebuild(project, target)
                plan = json.loads(
                    (
                        project
                        / "backups"
                        / applied["transactionId"]
                        / "mutation-plan.json"
                    ).read_text()
                )
                binding = plan["runtimeReverification"]["producerArchiveBinding"]
                self.assertEqual(
                    hashlib.sha256(old).hexdigest(), binding["before"]["sha256"]
                )
                self.assertEqual(
                    manifest.read_bytes(),
                    (project / binding["contentRelativePath"]).read_bytes(),
                )
                self.assertEqual(prior_binding is not None, "predecessor" in binding)
                self.assertEqual(
                    [
                        "server/conf/world-builder/installed-target-map-integration-v1.json"
                    ],
                    [a["destinationRelativePath"] for a in plan["actions"]],
                )
                after = support.tree_bytes(target, install)
                self.assertEqual(
                    [
                        "server/conf/world-builder/installed-target-map-integration-v1.json"
                    ],
                    sorted(k for k in before if before[k] != after[k]),
                )
                self.assertEqual(original, support.tree_bytes(project / "source"))
                self.assertEqual(saved, support.tree_bytes(project / "working"))
                self.assertEqual(
                    rebuilt, (target / "Client_Base/Open_RSC_Client.jar").read_bytes()
                )
                refused = self.run_cli(
                    "undo-adaptive", "--project", project, "--target-root", target
                )
                self.assertEqual(3, refused.returncode, refused.stderr)
                self.assertIn("re-verification boundary", refused.stderr)
                old = manifest.read_bytes()
                prior_binding = binding
                if (
                    generation == 1
                ):  # Cover runtime→map→runtime as well as runtime→runtime.
                    export = self.next_history_export(project)
                    saved = support.tree_bytes(project / "working")
                    imported = self.run_reviewed_apply(
                        "import-adaptive",
                        "IMPORT",
                        "--project",
                        project,
                        "--export",
                        export,
                        "--target-root",
                        target,
                    )
                    self.assertEqual(0, imported.returncode, imported.stderr)
            history = support.tree_bytes(project)
            reviewed = self.refresh(project, runtime, target)
            self.assertEqual(0, reviewed.returncode, reviewed.stderr)
            preview = json.loads(reviewed.stdout)
            accepted = self.refresh(
                project,
                runtime,
                target,
                "--confirm",
                "REFRESH",
                "--expected-preview",
                preview["previewFingerprintSha256"],
            )
            self.assertEqual(0, accepted.returncode, accepted.stderr)
            successor = Path(json.loads(accepted.stdout)["projectRoot"])
            self.assertEqual(
                support.tree_bytes(project / "working/layered-world/package"),
                support.tree_bytes(successor / "working/layered-world/package"),
            )
            # Fresh content carries new manifest provenance, but its saved terrain
            # and placements are copied exactly. Rebuild immediately once more.
            rebuilt = self.repack_producer_client(target, manifest, 3)
            self.apply_rebuild(successor, target)
            for generation in range(2):
                self.promote_fixture_terrain_to_v2(
                    successor / "working/layered-world/package", 160 + generation
                )
                saved_result = self.run_cli("save-project", "--project", successor)
                self.assertEqual(0, saved_result.returncode, saved_result.stderr)
                exported = self.run_cli("export-adaptive", "--project", successor)
                self.assertEqual(0, exported.returncode, exported.stderr)
                export = Path(json.loads(exported.stdout)["exportDirectory"])
                imported = self.run_reviewed_apply(
                    "import-adaptive",
                    "IMPORT",
                    "--project",
                    successor,
                    "--export",
                    export,
                    "--target-root",
                    target,
                )
                self.assertEqual(0, imported.returncode, imported.stderr)
            self.assertEqual(history, support.tree_bytes(project))
            reopened = self.run_cli(
                "open-project", "--installation-root", install, "--target-root", target
            )
            self.assertEqual(0, reopened.returncode, reopened.stderr)
            self.assertEqual(
                rebuilt, (target / "Client_Base/Open_RSC_Client.jar").read_bytes()
            )

    def test_rebuild_producer_uses_authenticated_alternate_content_configuration(self):
        with tempfile.TemporaryDirectory(
            prefix="producer-rebuild-alt-config-"
        ) as temporary:
            target, install, project, runtime, manifest = self.producer_rebuild_fixture(
                Path(temporary), alternate_configuration=True
            )
            snapshot = json.loads(
                (project / "source/snapshot-manifest.json").read_text()
            )
            self.assertIn(
                "source/original/server/maintained.conf",
                [
                    row["relativePath"]
                    for group in ("originalFiles", "definitionRuntimeFiles")
                    for row in snapshot[group]
                    if row["role"] == "server-runtime-config"
                ],
            )
            self.assertFalse((target / "server/myworld.conf").exists())
            self.repack_producer_client(target, manifest, 0)
            self.apply_rebuild(project, target)
            self.assertFalse((target / "server/myworld.conf").exists())

    def test_reverify_before_producer_export_then_refresh_and_import(self):
        with tempfile.TemporaryDirectory(
            prefix="producer-reverify-first-"
        ) as temporary:
            target, install, project, runtime, manifest = self.producer_rebuild_fixture(
                Path(temporary)
            )
            old = manifest.read_bytes()
            self.repack_producer_client(target, manifest, 0, refresh=False)
            result = self.apply_rebuild(project, target)
            plan = json.loads(
                (
                    project / "backups" / result["transactionId"] / "mutation-plan.json"
                ).read_text()
            )
            self.assertNotIn("producerArchiveBinding", plan["runtimeReverification"])
            self.assertEqual(old, manifest.read_bytes())
            self.refresh_producer_archive_hash(
                manifest, target / "Client_Base/Open_RSC_Client.jar"
            )
            reviewed = self.refresh(project, runtime, target)
            self.assertEqual(0, reviewed.returncode, reviewed.stderr)
            accepted = self.refresh(
                project,
                runtime,
                target,
                "--confirm",
                "REFRESH",
                "--expected-preview",
                json.loads(reviewed.stdout)["previewFingerprintSha256"],
            )
            self.assertEqual(0, accepted.returncode, accepted.stderr)
            successor = Path(json.loads(accepted.stdout)["projectRoot"])
            for generation in range(2):
                export = self.next_history_export(successor)
                imported = self.run_reviewed_apply(
                    "import-adaptive",
                    "IMPORT",
                    "--project",
                    successor,
                    "--export",
                    export,
                    "--target-root",
                    target,
                )
                self.assertEqual(0, imported.returncode, imported.stderr)

    def test_rebuilt_producer_rejects_other_document_changes_and_preview_drift(self):
        with tempfile.TemporaryDirectory(
            prefix="producer-rebuild-refusal-"
        ) as temporary:
            target, install, project, runtime, manifest = self.producer_rebuild_fixture(
                Path(temporary)
            )
            self.repack_producer_client(target, manifest, 0)
            valid = manifest.read_bytes()
            document = json.loads(valid)
            mutations = {
                "npc-vector": lambda d: d["npcDefinitions"][0].__setitem__(
                    "hairColour", 33
                ),
                "frame-hash": lambda d: d["animationDefinitions"][0]["frames"][
                    "frameSha256s"
                ].__setitem__(0, "a" * 64),
                "frame-key": lambda d: d["animationDefinitions"][0]["frames"][
                    "frameKeys"
                ].__setitem__(0, "frames/1.dat"),
                "source-order": lambda d: d["provider"]["sources"].reverse(),
                "source-path": lambda d: d["provider"]["sources"][-1].__setitem__(
                    "relativePath", "server/core.jar"
                ),
                "source-hash": lambda d: d["provider"]["sources"][-1].__setitem__(
                    "sha256", "a" * 64
                ),
                "probe-order": lambda d: d["provider"]["resolutionProbes"].reverse(),
                "probe-entry": lambda d: d["provider"]["resolutionProbes"][
                    -1
                ].__setitem__("entryPath", "myworld-assets/other.dat"),
                "probe-outcome": lambda d: d["provider"]["resolutionProbes"][
                    -1
                ].__setitem__("present", True),
                "probe-hash": lambda d: d["provider"]["resolutionProbes"][
                    2
                ].__setitem__("entrySha256", "a" * 64),
                "configuration": lambda d: d["provider"]["configuration"].__setitem__(
                    "sha256", "a" * 64
                ),
                "flags": lambda d: d["provider"]["clientFlags"].__setitem__(
                    "Config.S_ALLOW_BEARDED_LADIES", True
                ),
            }
            for label, mutate in mutations.items():
                with self.subTest(change=label):
                    changed = copy.deepcopy(document)
                    mutate(changed)
                    support.write_json(manifest, changed)
                    before = support.tree_bytes(target, install)
                    rejected = self.run_cli(
                        "reverify-target-runtime",
                        "--project",
                        project,
                        "--target-root",
                        target,
                    )
                    self.assertEqual(3, rejected.returncode, rejected.stderr)
                    self.assertEqual(before, support.tree_bytes(target, install))
                    self.assertFalse(list((project / "receipts").glob("*.json")))
            # A coherent re-export of changed pixels is valid visual content,
            # but is not an equivalent-archive provenance update.
            artifact = target / "server/conf/world-builder/npc-frames.zip"
            packaged = target / "world-builder-provider/npc-frames.zip"
            original_artifact = artifact.read_bytes()
            with zipfile.ZipFile(artifact) as archive:
                frames = {name: archive.read(name) for name in archive.namelist()}
            pixel = bytearray(frames["frames/0.dat"])
            pixel[-1] ^= 1
            frames["frames/0.dat"] = bytes(pixel)
            with zipfile.ZipFile(artifact, "w") as archive:
                for name, data in frames.items():
                    archive.writestr(name, data)
            packaged.write_bytes(artifact.read_bytes())
            changed = copy.deepcopy(document)
            changed["assetProviders"][0]["sha256"] = self.file_hash(artifact)
            for source in changed["provider"]["sources"]:
                if source["sourceId"] == "frames":
                    source["sha256"] = self.file_hash(artifact)
            changed["animationDefinitions"][0]["frames"]["frameSha256s"][0] = (
                hashlib.sha256(pixel).hexdigest()
            )
            support.write_json(manifest, changed)
            before = support.tree_bytes(target, install)
            rejected = self.run_cli(
                "reverify-target-runtime", "--project", project, "--target-root", target
            )
            self.assertEqual(3, rejected.returncode, rejected.stderr)
            self.assertIn(
                "more than independently verified archive hash bindings",
                rejected.stderr,
            )
            self.assertEqual(before, support.tree_bytes(target, install))
            artifact.write_bytes(original_artifact)
            packaged.write_bytes(original_artifact)
            manifest.write_bytes(valid)
            reviewed = self.run_cli(
                "reverify-target-runtime", "--project", project, "--target-root", target
            )
            self.assertEqual(0, reviewed.returncode, reviewed.stderr)
            plan = json.loads(reviewed.stdout)
            manifest.write_bytes(valid + b" ")
            before = support.tree_bytes(target, install)
            rejected = self.run_cli(
                "reverify-target-runtime",
                "--project",
                project,
                "--target-root",
                target,
                "--confirm",
                "REVERIFY",
                "--transaction-id",
                plan["transactionId"],
                "--plan-sha256",
                plan["planFingerprintSha256"],
            )
            self.assertEqual(3, rejected.returncode, rejected.stderr)
            self.assertEqual(before, support.tree_bytes(target, install))
            self.assertFalse(list((project / "receipts").glob("*.json")))

    def test_producer_reverification_recovery_retains_external_files_and_retry(self):
        with tempfile.TemporaryDirectory(
            prefix="producer-rebuild-recovery-"
        ) as temporary:
            target, install, project, runtime, manifest = self.producer_rebuild_fixture(
                Path(temporary)
            )
            self.next_history_export(project)
            self.repack_producer_client(target, manifest, 0)
            before = support.tree_bytes(target, install)
            saved = support.tree_bytes(project / "working")
            original = support.tree_bytes(project / "source")
            failed = self.run_failure(
                "reverify",
                "activation-published,any-rollback",
                project,
                target,
                project / "exports",
            )
            self.assertEqual(3, failed.returncode, failed.stderr)
            self.assertIn("RECOVERY_REQUIRED", failed.stderr)
            pending = next(
                json.loads(path.read_text())
                for path in (project / "backups").glob("*/mutation-plan.json")
                if "producerArchiveBinding"
                in json.loads(path.read_text()).get("runtimeReverification", {})
            )
            artifact = (
                project
                / pending["runtimeReverification"]["producerArchiveBinding"][
                    "contentRelativePath"
                ]
            )
            artifact_bytes = artifact.read_bytes()
            artifact.unlink()
            refused = self.run_cli(
                "recover-adaptive", "--project", project, "--target-root", target
            )
            self.assertEqual(3, refused.returncode, refused.stderr)
            artifact.write_bytes(artifact_bytes + b" ")
            refused = self.run_cli(
                "recover-adaptive", "--project", project, "--target-root", target
            )
            self.assertEqual(3, refused.returncode, refused.stderr)
            artifact.write_bytes(artifact_bytes)
            valid = manifest.read_bytes()
            manifest.write_bytes(valid + b" ")
            refused = self.run_cli(
                "recover-adaptive", "--project", project, "--target-root", target
            )
            self.assertEqual(3, refused.returncode, refused.stderr)
            self.assertEqual(valid + b" ", manifest.read_bytes())
            manifest.write_bytes(valid)
            recovered = self.run_reviewed_apply(
                "recover-adaptive",
                "RECOVER",
                "--project",
                project,
                "--target-root",
                target,
            )
            self.assertEqual(0, recovered.returncode, recovered.stderr)
            self.assertEqual(before, support.tree_bytes(target, install))
            self.assertEqual(saved, support.tree_bytes(project / "working"))
            self.assertEqual(original, support.tree_bytes(project / "source"))
            applied = self.apply_rebuild(project, target)
            plan = json.loads(
                (
                    project
                    / "backups"
                    / applied["transactionId"]
                    / "mutation-plan.json"
                ).read_text()
            )
            self.assertNotIn(
                "predecessor", plan["runtimeReverification"]["producerArchiveBinding"]
            )
            retained = (
                project
                / plan["runtimeReverification"]["producerArchiveBinding"][
                    "contentRelativePath"
                ]
            )
            accepted = retained.read_bytes()
            retained.write_bytes(accepted + b" ")
            export = self.next_history_export(project)
            rejected = self.run_cli(
                "import-adaptive",
                "--project",
                project,
                "--export",
                export,
                "--target-root",
                target,
            )
            self.assertEqual(3, rejected.returncode, rejected.stderr)
            self.assertIn("producer", rejected.stderr.lower())
            retained.write_bytes(accepted)
            imported = self.run_reviewed_apply(
                "import-adaptive",
                "IMPORT",
                "--project",
                project,
                "--export",
                export,
                "--target-root",
                target,
            )
            self.assertEqual(0, imported.returncode, imported.stderr)


def load_tests(loader, tests, pattern):
    return unittest.TestSuite(
        ProducerRefreshTest(name)
        for name in ProducerRefreshTest.__dict__
        if name.startswith("test_")
    )


if __name__ == "__main__":
    unittest.main(verbosity=2)
