#!/usr/bin/env python3
"""Focused immutable project revision history tests."""

from __future__ import annotations

import importlib.util
import json
from pathlib import Path
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]
SOURCE_ROOT = ROOT / "tools" / "world-builder" / "src"
LIFECYCLE_PATH = ROOT / "tests" / "myworld" / "test-world-builder-adaptive-project-lifecycle.py"


def load_lifecycle():
    spec = importlib.util.spec_from_file_location("revision_lifecycle", LIFECYCLE_PATH)
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


LIFECYCLE = load_lifecycle()

HARNESS = r"""
package com.openrsc.worldbuilder;

import java.nio.file.Paths;
import java.util.List;

public final class ProjectRevisionHarness {
    public static void main(String[] args) throws Exception {
        try {
            WorldBuilderProjectRevisionService service =
                new WorldBuilderProjectRevisionService();
            if ("create".equals(args[0])) {
                WorldBuilderProjectRevisionService.Revision value = service.create(
                    Paths.get(args[1]), args[2], args[3], Boolean.parseBoolean(args[4]));
                System.out.print(value.revisionId + "|" + value.workingFingerprint);
            } else if ("list".equals(args[0])) {
                List<WorldBuilderProjectRevisionService.Revision> values =
                    service.list(Paths.get(args[1]));
                for (WorldBuilderProjectRevisionService.Revision value : values) {
                    System.out.println(value.revisionId + "|" + value.reason + "|"
                        + value.workingFingerprint + "|" + value.fileCount + "|"
                        + value.totalBytes);
                }
            } else if ("restore".equals(args[0])) {
                WorldBuilderProjectRevisionService.RestoreResult value =
                    service.restore(Paths.get(args[1]), args[2]);
                System.out.print(value.changed + "|" + value.restored.revisionId + "|"
                    + (value.safeguard == null ? "" : value.safeguard.revisionId));
            } else if ("export".equals(args[0])) {
                System.out.print(service.export(Paths.get(args[1]), args[2]));
            } else throw new IllegalArgumentException(args[0]);
        } catch (WorldBuilderContractException refusal) {
            System.err.println(refusal.code() + "|" + refusal.nextStep() + "|"
                + refusal.getMessage());
            System.exit(3);
        }
    }
}
"""


class ProjectRevisionTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.compiled = tempfile.TemporaryDirectory(prefix="project-revision-classes-")
        cls.classes = Path(cls.compiled.name)
        sources = sorted(str(path) for path in SOURCE_ROOT.rglob("*.java"))
        subprocess.run(
            ["javac", "-source", "8", "-target", "8", "-d", str(cls.classes), *sources],
            cwd=ROOT,
            check=True,
            capture_output=True,
        )
        resource = cls.classes / LIFECYCLE.RUNTIME_ALLOWLIST_RESOURCE
        resource.parent.mkdir(parents=True, exist_ok=True)
        resource.write_bytes(LIFECYCLE.RUNTIME_ALLOWLIST.read_bytes())
        harness = cls.classes / "harness/com/openrsc/worldbuilder/ProjectRevisionHarness.java"
        harness.parent.mkdir(parents=True, exist_ok=True)
        harness.write_text(HARNESS, encoding="utf-8")
        subprocess.run(
            [
                "javac", "-source", "8", "-target", "8", "-cp", str(cls.classes),
                "-d", str(cls.classes), str(harness),
            ],
            cwd=ROOT,
            check=True,
            capture_output=True,
        )

    @classmethod
    def tearDownClass(cls) -> None:
        cls.compiled.cleanup()

    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory(prefix="project-revision-test-")
        self.root = Path(self.temp.name)
        self.installation = self.root / "World Builder 2"
        self.installation.mkdir()
        self.runtime = LIFECYCLE.AdaptiveProjectLifecycleTest.make_runtime(self.root)
        self.source = self.root / "empty-source"
        self.source.mkdir()
        discovered = self.cli("discover-adaptive", "--target-root", self.source)
        self.assertEqual(discovered.returncode, 0, discovered.stderr)
        report = self.root / "discovery.json"
        report.write_text(discovered.stdout, encoding="utf-8")
        created = self.cli(
            "create-project", "--installation-root", self.installation,
            "--runtime-root", self.runtime, "--target-root", self.source,
            "--discovery-report", report, "--display-name", "Revision fixture",
            "--port", "43931", "--confirm", "CREATE",
        )
        self.assertEqual(created.returncode, 0, created.stderr)
        self.project = Path(json.loads(created.stdout)["projectRoot"])

    def tearDown(self) -> None:
        self.temp.cleanup()

    def cli(self, *args: object) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            ["java", "-cp", str(self.classes),
             "com.openrsc.worldbuilder.WorldBuilderCli", *map(str, args)],
            cwd=ROOT, text=True, capture_output=True,
        )

    def revision(self, *args: object) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            ["java", "-cp", str(self.classes),
             "com.openrsc.worldbuilder.ProjectRevisionHarness", *map(str, args)],
            cwd=ROOT, text=True, capture_output=True,
        )

    def test_content_addressed_create_restore_export_and_corruption_refusal(self) -> None:
        first = self.revision(
            "create", self.project, "explicit-backup", "Initial world", "false"
        )
        self.assertEqual(first.returncode, 0, first.stderr)
        first_id, first_fingerprint = first.stdout.split("|")

        LIFECYCLE.AdaptiveProjectLifecycleTest.change_working_terrain(self.project)
        saved = self.cli("save-project", "--project", self.project)
        self.assertEqual(saved.returncode, 0, saved.stderr)
        second = self.revision(
            "create", self.project, "editing-session", "Edited terrain", "true"
        )
        self.assertEqual(second.returncode, 0, second.stderr)
        second_id, second_fingerprint = second.stdout.split("|")
        self.assertNotEqual(first_fingerprint, second_fingerprint)

        listed = self.revision("list", self.project)
        self.assertEqual(listed.returncode, 0, listed.stderr)
        self.assertEqual(len(listed.stdout.strip().splitlines()), 2)
        manifests = sorted((self.project / "revisions/entries").glob("*/revision.json"))
        logical_files = sum(
            json.loads(path.read_text(encoding="utf-8"))["fileCount"]
            for path in manifests
        )
        objects = list((self.project / "revisions/objects").glob("*/*.blob"))
        self.assertLess(len(objects), logical_files)

        restored = self.revision("restore", self.project, first_id)
        self.assertEqual(restored.returncode, 0, restored.stderr)
        changed, restored_id, safeguard_id = restored.stdout.split("|")
        self.assertEqual((changed, restored_id), ("true", first_id))
        self.assertTrue(safeguard_id)
        manifest = json.loads((self.project / "project.json").read_text(encoding="utf-8"))
        self.assertEqual(manifest["fingerprints"]["workingSha256"], first_fingerprint)
        after_restore = self.revision("list", self.project)
        self.assertEqual(after_restore.returncode, 0, after_restore.stderr)
        self.assertEqual(len(after_restore.stdout.strip().splitlines()), 3)
        self.assertIn("|before-restore|", after_restore.stdout)

        exported = self.revision("export", self.project, second_id)
        self.assertEqual(exported.returncode, 0, exported.stderr)
        export_root = Path(exported.stdout)
        self.assertTrue((export_root / "revision.json").is_file())
        self.assertTrue((export_root / "package/manifest.json").is_file())

        record = json.loads(manifests[0].read_text(encoding="utf-8"))["files"][0]
        object_path = (
            self.project / "revisions/objects" / record["sha256"][:2]
            / f'{record["sha256"]}.blob'
        )
        object_path.write_bytes(object_path.read_bytes() + b"corrupt")
        refused = self.revision("list", self.project)
        self.assertEqual(refused.returncode, 3)
        self.assertIn("SOURCE_CORRUPT", refused.stderr)


if __name__ == "__main__":
    unittest.main()
