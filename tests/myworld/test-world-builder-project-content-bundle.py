#!/usr/bin/env python3
"""Canonical cross-repository project content bundle fixture coverage."""

import hashlib
import importlib.util
import json
import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
FIXTURE = ROOT / "tests/fixtures/project-content-bundle-v1/bundle"
FIXTURE_V2 = ROOT / "tests/fixtures/project-content-bundle-v2/bundle"
GENERATOR = ROOT / "scripts/generate-project-content-bundle-v1-fixture.py"
GENERATOR_V2 = ROOT / "scripts/generate-project-content-bundle-v2-fixture.py"
CLASSES = ROOT / "output/world-builder-tools/classes"


HARNESS = r'''
package com.openrsc.worldbuilder;

import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

public final class ProjectContentBundleFixtureHarness {
    private static void require(boolean condition, String label) {
        if (!condition) throw new IllegalStateException("missing " + label);
    }

    private static boolean contains(Map<String,Object> catalog, String family, long id) {
        @SuppressWarnings("unchecked") List<Object> values =
            (List<Object>)catalog.get(family);
        return values.contains(Long.valueOf(id));
    }

    public static void main(String[] args) throws Exception {
        WorldBuilderProjectContentBundle.Bundle bundle =
            WorldBuilderProjectContentBundle.read(Paths.get(args[0]));
        if (WorldBuilderProjectContentBundle.CAPABILITY_ID.equals(bundle.capabilityId)) {
            require(bundle.files.size() == 17, "closed 17-role successor inventory");
            require(bundle.itemVisuals.size() == 3, "exact item visual closure");
        } else {
            require(bundle.files.size() == 16, "closed 16-role legacy inventory");
        }
        require(contains(bundle.definitionCatalog, "tiles", 31), "floor 31");
        require(contains(bundle.definitionCatalog, "boundaries", 219), "wall 219");
        require(contains(bundle.definitionCatalog, "scenery", 59), "scenery 59");
        require(contains(bundle.definitionCatalog, "npcs", 846), "NPC 846");
        require(contains(bundle.definitionCatalog, "groundItems", 9000),
            "ground item 9000");
        System.out.println(bundle.definitionFingerprintSha256);
        System.out.println(bundle.assetFingerprintSha256);
        System.out.println(bundle.itemVisualFingerprintSha256);
        System.out.println(bundle.bundleFingerprintSha256);
    }
}
'''


class ProjectContentBundleFixtureTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        if not CLASSES.is_dir():
            subprocess.run([str(ROOT / "scripts/build-tools.sh")], check=True)
        cls.temp = tempfile.TemporaryDirectory(
            prefix="world-builder-content-bundle-fixture-"
        )
        source = Path(cls.temp.name) / "ProjectContentBundleFixtureHarness.java"
        source.write_text(HARNESS.strip() + "\n", encoding="utf-8")
        cls.harness_classes = Path(cls.temp.name) / "classes"
        cls.harness_classes.mkdir()
        subprocess.run(
            [
                "javac", "-encoding", "UTF-8", "-cp", str(CLASSES),
                "-d", str(cls.harness_classes), str(source),
            ],
            check=True,
        )

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()

    def read_with_java(self, root: Path) -> subprocess.CompletedProcess:
        classpath = os.pathsep.join((str(self.harness_classes), str(CLASSES)))
        return subprocess.run(
            [
                "java", "-cp", classpath,
                "com.openrsc.worldbuilder.ProjectContentBundleFixtureHarness",
                str(root),
            ],
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
        )

    def test_generator_reproduces_every_checked_in_byte(self):
        result = subprocess.run(
            ["python3", str(GENERATOR), "--check", str(FIXTURE)],
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
        )
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("PASS: canonical project-content-bundle-v1 fixture", result.stdout)

    def test_editor_reader_accepts_exact_fixture_and_expected_id_closure(self):
        manifest = json.loads((FIXTURE / "manifest.json").read_text(encoding="utf-8"))
        result = self.read_with_java(FIXTURE)
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertEqual(
            [
                manifest["definitionFingerprintSha256"],
                manifest["assetFingerprintSha256"],
                "0" * 64,
                manifest["bundleFingerprintSha256"],
            ],
            result.stdout.splitlines(),
        )
        relative_files = {
            path.relative_to(FIXTURE).as_posix()
            for path in FIXTURE.rglob("*") if path.is_file()
        }
        self.assertEqual(17, len(relative_files))
        self.assertFalse(any(path.endswith((".png", ".ob3")) for path in relative_files))

    def test_reader_rejects_fixture_payload_drift(self):
        with tempfile.TemporaryDirectory(prefix="content-bundle-drift-") as temp:
            changed = Path(temp) / "bundle"
            shutil.copytree(FIXTURE, changed)
            model = changed / "files/client/Cache/video/models.orsc"
            model.write_bytes(model.read_bytes() + b"drift")
            result = self.read_with_java(changed)
            self.assertNotEqual(0, result.returncode)
            self.assertIn("Content file differs from the exact manifest inventory", result.stderr)

    def test_successor_generator_and_reader_freeze_visual_mappings_and_masks(self):
        result = subprocess.run(
            ["python3", str(GENERATOR_V2), "--check", str(FIXTURE_V2)],
            text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
        )
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        manifest = json.loads((FIXTURE_V2 / "manifest.json").read_text(encoding="utf-8"))
        read = self.read_with_java(FIXTURE_V2)
        self.assertEqual(0, read.returncode, read.stdout + read.stderr)
        self.assertEqual(
            [manifest[key] for key in (
                "definitionFingerprintSha256", "assetFingerprintSha256",
                "itemVisualFingerprintSha256", "bundleFingerprintSha256",
            )],
            read.stdout.splitlines(),
        )
        visuals = manifest["itemVisuals"]
        self.assertEqual([9000, 9001, 9002], [value["itemId"] for value in visuals])
        self.assertEqual("asset.sprite.custom", visuals[0]["customSpriteAssetRole"])
        self.assertEqual(417, visuals[1]["authenticSpriteId"])
        self.assertEqual(-1, visuals[1]["pictureMask"])
        self.assertEqual(-16776961, visuals[2]["blueMask"])

    @staticmethod
    def rewrite_v2_manifest(root: Path, visuals: list[dict] | None = None) -> None:
        spec = importlib.util.spec_from_file_location("bundle_v2_test_generator", GENERATOR_V2)
        module = importlib.util.module_from_spec(spec)
        assert spec is not None and spec.loader is not None
        spec.loader.exec_module(module)
        manifest_path = root / "manifest.json"
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        if visuals is not None:
            manifest["itemVisuals"] = visuals
        for record in manifest["files"]:
            path = root / record["bundleRelativePath"]
            payload = path.read_bytes()
            record["size"] = len(payload)
            record["sha256"] = hashlib.sha256(payload).hexdigest()
        manifest["definitionFingerprintSha256"] = module.record_fingerprint(
            b"world-builder-project-content-definitions-v2\n", manifest["files"],
            True, manifest["definitionCatalog"]["catalogSha256"],
        )
        manifest["assetFingerprintSha256"] = module.record_fingerprint(
            b"world-builder-project-content-assets-v2\n", manifest["files"], False,
        )
        manifest["itemVisualFingerprintSha256"] = hashlib.sha256(
            b"world-builder-project-content-item-visuals-v1\n"
            + module.legacy.canonical(manifest["itemVisuals"])
        ).hexdigest()
        manifest["bundleFingerprintSha256"] = "0" * 64
        manifest["bundleFingerprintSha256"] = hashlib.sha256(
            b"world-builder-project-content-bundle-v2\n"
            + module.legacy.canonical(manifest)
        ).hexdigest()
        manifest_path.write_bytes(module.legacy.pretty(manifest))

    def test_successor_rejects_malformed_duplicate_and_missing_archive_entry(self):
        cases = {}

        def malformed(root: Path) -> None:
            evidence = root / "files/server/conf/world-builder/item-visuals-v1.json"
            evidence.write_bytes(b"{malformed\n")

        cases["malformed"] = (malformed, None, "malformed JSON")

        def duplicate(root: Path) -> None:
            evidence = root / "files/server/conf/world-builder/item-visuals-v1.json"
            document = json.loads(evidence.read_text(encoding="utf-8"))
            document["itemVisuals"].insert(1, dict(document["itemVisuals"][0]))
            evidence.write_text(json.dumps(document, sort_keys=True, indent=2) + "\n")

        cases["duplicate"] = (duplicate, "evidence", "unique, ascending")

        def missing_entry(root: Path) -> None:
            archive = root / "files/client/Cache/video/Custom_Sprites.osar"
            import zipfile
            with zipfile.ZipFile(archive, "w") as output:
                output.writestr("items/different.dat", b"wrong entry")

        cases["missing-entry"] = (missing_entry, None, "archive entry is missing")

        for name, (mutation, visual_source, expected) in cases.items():
            with self.subTest(case=name), tempfile.TemporaryDirectory(
                prefix="content-bundle-v2-invalid-"
            ) as temp:
                changed = Path(temp) / "bundle"
                shutil.copytree(FIXTURE_V2, changed)
                mutation(changed)
                visuals = None
                if visual_source == "evidence":
                    visuals = json.loads((changed /
                        "files/server/conf/world-builder/item-visuals-v1.json"
                    ).read_text(encoding="utf-8"))["itemVisuals"]
                self.rewrite_v2_manifest(changed, visuals)
                result = self.read_with_java(changed)
                self.assertNotEqual(0, result.returncode)
                self.assertIn(expected, result.stderr)


if __name__ == "__main__":
    unittest.main()
