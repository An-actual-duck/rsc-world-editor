#!/usr/bin/env python3
"""Canonical cross-repository project-content-bundle-v1 fixture coverage."""

import json
import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
FIXTURE = ROOT / "tests/fixtures/project-content-bundle-v1/bundle"
GENERATOR = ROOT / "scripts/generate-project-content-bundle-v1-fixture.py"
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
        require(bundle.files.size() == 16, "closed 16-role inventory");
        require(contains(bundle.definitionCatalog, "tiles", 31), "floor 31");
        require(contains(bundle.definitionCatalog, "boundaries", 219), "wall 219");
        require(contains(bundle.definitionCatalog, "scenery", 59), "scenery 59");
        require(contains(bundle.definitionCatalog, "npcs", 846), "NPC 846");
        require(contains(bundle.definitionCatalog, "groundItems", 9000),
            "ground item 9000");
        System.out.println(bundle.definitionFingerprintSha256);
        System.out.println(bundle.assetFingerprintSha256);
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


if __name__ == "__main__":
    unittest.main()
