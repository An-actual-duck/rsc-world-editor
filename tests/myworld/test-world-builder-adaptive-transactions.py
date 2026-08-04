#!/usr/bin/env python3
"""Temporary-fixture coverage for adaptive export/import/recovery/undo."""

import importlib.util
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
SOURCE_ROOT = ROOT / "tools/world-builder/src"
MAIN_CLASS = "com.openrsc.worldbuilder.WorldBuilderCli"
LIFECYCLE_TEST = ROOT / "tests/myworld/test-world-builder-adaptive-project-lifecycle.py"


def load_lifecycle():
    spec = importlib.util.spec_from_file_location("adaptive_lifecycle_fixture", LIFECYCLE_TEST)
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


class AdaptiveTransactionTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.lifecycle = load_lifecycle()
        cls.fixtures = cls.lifecycle.load_discovery_fixtures()
        cls.packed_fixtures = cls.lifecycle.load_packed_fixtures()
        cls.compile_temp = tempfile.TemporaryDirectory(prefix="adaptive-transaction-classes-")
        cls.classes = Path(cls.compile_temp.name) / "classes"
        cls.classes.mkdir()
        sources = sorted(str(path) for path in SOURCE_ROOT.rglob("*.java"))
        subprocess.run(
            ["javac", "-source", "8", "-target", "8", "-d", str(cls.classes), *sources],
            check=True,
            cwd=ROOT,
            capture_output=True,
            text=True,
        )
        harness = (
            Path(cls.compile_temp.name)
            / "harness/com/openrsc/worldbuilder/AdaptiveTransactionFailureHarness.java"
        )
        harness.parent.mkdir(parents=True)
        harness.write_text(
            r"""
package com.openrsc.worldbuilder;

import java.nio.file.Path;
import java.nio.file.Paths;

public final class AdaptiveTransactionFailureHarness {
    private static boolean selected(String specification, String milestone) {
        for (String value : specification.split(",")) {
            if (value.equals(milestone)) return true;
        }
        return false;
    }

    public static void main(String[] args) throws Exception {
        final String operation = args[0];
        final String failures = args[1];
        final Path project = Paths.get(args[2]);
        final Path target = Paths.get(args[3]);
        try {
            if ("import".equals(operation)) {
                WorldBuilderAdaptiveImporter.Observer observer =
                    new WorldBuilderAdaptiveImporter.Observer() {
                        @Override public void observe(String milestone, Path path)
                            throws Exception {
                            if (selected(failures, milestone)) {
                                throw new Exception("injected " + milestone);
                            }
                        }
                    };
                WorldBuilderAdaptiveImporter importer =
                    new WorldBuilderAdaptiveImporter(observer);
                WorldBuilderAdaptiveImporter.Preview preview = importer.preview(
                    project, Paths.get(args[4]), target);
                importer.apply(preview, "IMPORT");
            } else if ("undo".equals(operation)) {
                WorldBuilderAdaptiveUndo.Observer observer =
                    new WorldBuilderAdaptiveUndo.Observer() {
                        @Override public void observe(String milestone, Path path)
                            throws Exception {
                            if (selected(failures, milestone)) {
                                throw new Exception("injected " + milestone);
                            }
                        }
                    };
                WorldBuilderAdaptiveUndo undo = new WorldBuilderAdaptiveUndo(observer);
                WorldBuilderAdaptiveUndo.Preview preview = undo.preview(project, target);
                undo.apply(preview, "UNDO");
            } else {
                throw new IllegalArgumentException(operation);
            }
            System.exit(0);
        } catch (WorldBuilderContractException expected) {
            System.err.println(expected.code() + ": " + expected.getMessage());
            System.exit(3);
        }
    }
}
""".strip()
            + "\n",
            encoding="utf-8",
        )
        subprocess.run(
            [
                "javac",
                "-source",
                "8",
                "-target",
                "8",
                "-cp",
                str(cls.classes),
                "-d",
                str(cls.classes),
                str(harness),
            ],
            check=True,
            cwd=ROOT,
            capture_output=True,
            text=True,
        )

    @classmethod
    def tearDownClass(cls):
        cls.compile_temp.cleanup()

    def run_cli(self, *args):
        return subprocess.run(
            ["java", "-cp", str(self.classes), MAIN_CLASS, *map(str, args)],
            cwd=ROOT,
            text=True,
            capture_output=True,
        )

    def run_cli_with_property(self, property_value, *args):
        return subprocess.run(
            [
                "java",
                f"-Dworldbuilder.adaptive.testUsableBytes={property_value}",
                "-cp",
                str(self.classes),
                MAIN_CLASS,
                *map(str, args),
            ],
            cwd=ROOT,
            text=True,
            capture_output=True,
        )

    def run_failure(self, operation, failures, project, target, export=None):
        command = [
            "java",
            "-cp",
            str(self.classes),
            "com.openrsc.worldbuilder.AdaptiveTransactionFailureHarness",
            operation,
            failures,
            str(project),
            str(target),
        ]
        if export is not None:
            command.append(str(export))
        return subprocess.run(command, cwd=ROOT, text=True, capture_output=True)

    def target_project(self, base: Path, representation="layered"):
        target = (
            self.fixtures.descriptor_fixture(str(base))
            if representation == "layered"
            else self.packed_fixtures.fixture(base)
        )
        installation = target / "World Builder 2"
        installation.mkdir()
        runtime = self.lifecycle.AdaptiveProjectLifecycleTest.make_runtime(base)
        discovery = self.run_cli("discover-adaptive", "--target-root", target)
        self.assertEqual(0, discovery.returncode, discovery.stderr)
        report = base / "discovery.json"
        report.write_text(discovery.stdout, encoding="utf-8")
        created = self.run_cli(
            "create-project",
            "--installation-root",
            installation,
            "--runtime-root",
            runtime,
            "--target-root",
            target,
            "--discovery-report",
            report,
            "--display-name",
            "Transaction fixture",
            "--port",
            "43883",
            "--confirm",
            "CREATE",
        )
        self.assertEqual(0, created.returncode, created.stderr)
        project = Path(json.loads(created.stdout)["projectRoot"])
        self.lifecycle.AdaptiveProjectLifecycleTest.change_working_terrain(project)
        saved = self.run_cli("save-project", "--project", project)
        self.assertEqual(0, saved.returncode, saved.stderr)
        exported = self.run_cli("export-adaptive", "--project", project)
        self.assertEqual(0, exported.returncode, exported.stderr)
        export = Path(json.loads(exported.stdout)["exportDirectory"])
        return target, installation, project, export

    def standalone_project(self, base: Path):
        installation = base / "World Builder 2"
        installation.mkdir()
        runtime = self.lifecycle.AdaptiveProjectLifecycleTest.make_runtime(base)
        discovery_root = base / "ordinary-parent"
        discovery_root.mkdir()
        discovery = self.run_cli("discover-adaptive", "--target-root", discovery_root)
        self.assertEqual(0, discovery.returncode, discovery.stderr)
        report = base / "standalone.json"
        report.write_text(discovery.stdout, encoding="utf-8")
        created = self.run_cli(
            "create-project",
            "--installation-root",
            installation,
            "--runtime-root",
            runtime,
            "--target-root",
            discovery_root,
            "--discovery-report",
            report,
            "--display-name",
            "Standalone",
            "--port",
            "43884",
            "--confirm",
            "CREATE",
        )
        self.assertEqual(0, created.returncode, created.stderr)
        return installation, Path(json.loads(created.stdout)["projectRoot"])

    def test_export_preview_import_and_exact_undo(self):
        for representation in ("layered", "packed"):
            with self.subTest(representation=representation), tempfile.TemporaryDirectory(
                prefix=f"adaptive-transaction-{representation}-"
            ) as temp:
                target, installation, project, export = self.target_project(
                    Path(temp), representation
                )
                before = self.lifecycle.tree_bytes(target, installation)
                preview = self.run_cli(
                    "import-adaptive",
                    "--project",
                    project,
                    "--export",
                    export,
                    "--target-root",
                    target,
                )
                self.assertEqual(0, preview.returncode, preview.stderr)
                self.assertEqual(before, self.lifecycle.tree_bytes(target, installation))
                applied = self.run_cli(
                    "import-adaptive",
                    "--project",
                    project,
                    "--export",
                    export,
                    "--target-root",
                    target,
                    "--confirm",
                    "IMPORT",
                )
                self.assertEqual(0, applied.returncode, applied.stderr)
                self.assertNotEqual(before, self.lifecycle.tree_bytes(target, installation))
                undo_preview = self.run_cli(
                    "undo-adaptive", "--project", project, "--target-root", target
                )
                self.assertEqual(0, undo_preview.returncode, undo_preview.stderr)
                undone = self.run_cli(
                    "undo-adaptive",
                    "--project",
                    project,
                    "--target-root",
                    target,
                    "--confirm",
                    "UNDO",
                )
                self.assertEqual(0, undone.returncode, undone.stderr)
                self.assertEqual(before, self.lifecycle.tree_bytes(target, installation))

    def test_changed_after_blocks_undo_before_artifacts(self):
        with tempfile.TemporaryDirectory(prefix="adaptive-changed-after-") as temp:
            target, installation, project, export = self.target_project(Path(temp))
            applied = self.run_cli(
                "import-adaptive",
                "--project",
                project,
                "--export",
                export,
                "--target-root",
                target,
                "--confirm",
                "IMPORT",
            )
            self.assertEqual(0, applied.returncode, applied.stderr)
            receipts_before = sorted(path.name for path in (project / "receipts").iterdir())
            backups_before = sorted(path.name for path in (project / "backups").iterdir())
            config = target / "server/world-builder-configs/primary.json"
            config.write_bytes(config.read_bytes() + b"\n")
            refused = self.run_cli(
                "undo-adaptive", "--project", project, "--target-root", target
            )
            self.assertEqual(3, refused.returncode)
            self.assertIn("changed", refused.stderr.lower())
            self.assertEqual(receipts_before, sorted(path.name for path in (project / "receipts").iterdir()))
            self.assertEqual(backups_before, sorted(path.name for path in (project / "backups").iterdir()))

    def test_standalone_export_and_immediate_no_target_refusals(self):
        with tempfile.TemporaryDirectory(prefix="adaptive-standalone-transaction-") as temp:
            base = Path(temp)
            installation, project = self.standalone_project(base)
            first = self.run_cli("export-adaptive", "--project", project)
            second = self.run_cli("export-adaptive", "--project", project)
            self.assertEqual(0, first.returncode, first.stderr)
            self.assertEqual(0, second.returncode, second.stderr)
            first_value = json.loads(first.stdout)
            second_value = json.loads(second.stdout)
            self.assertEqual(
                first_value["exportFingerprintSha256"],
                second_value["exportFingerprintSha256"],
            )
            self.assertNotEqual(first_value["exportDirectory"], second_value["exportDirectory"])
            forbidden_target = base / "must-not-be-created"
            receipts_before = self.lifecycle.tree_bytes(project / "receipts")
            backups_before = self.lifecycle.tree_bytes(project / "backups")
            for command in ("import-adaptive", "undo-adaptive", "recover-adaptive"):
                arguments = [command, "--project", project]
                if command == "import-adaptive":
                    arguments.extend(["--export", first_value["exportDirectory"]])
                arguments.extend(["--target-root", forbidden_target])
                refused = self.run_cli(*arguments)
                self.assertEqual(3, refused.returncode, refused.stderr)
                self.assertIn("NO_TARGET", refused.stderr)
                self.assertFalse(forbidden_target.exists())
                self.assertEqual(receipts_before, self.lifecycle.tree_bytes(project / "receipts"))
                self.assertEqual(backups_before, self.lifecycle.tree_bytes(project / "backups"))

            active = self.run_cli(
                "import-active-adaptive", "--installation-root", installation
            )
            self.assertEqual(3, active.returncode, active.stderr)
            self.assertIn("NO_TARGET", active.stderr)

    def test_partial_import_rolls_back_exactly(self):
        with tempfile.TemporaryDirectory(prefix="adaptive-import-rollback-") as temp:
            target, installation, project, export = self.target_project(Path(temp))
            before = self.lifecycle.tree_bytes(target, installation)
            failed = self.run_failure(
                "import", "package-file-published-0000", project, target, export
            )
            self.assertEqual(3, failed.returncode, failed.stderr)
            self.assertEqual(before, self.lifecycle.tree_bytes(target, installation))
            statuses = [
                json.loads(path.read_text(encoding="utf-8"))["status"]
                for path in (project / "receipts").glob("*.json")
            ]
            self.assertEqual(["rolled-back"], statuses)

    def test_free_space_and_force_refuse_before_artifacts(self):
        with tempfile.TemporaryDirectory(prefix="adaptive-import-preflight-") as temp:
            target, installation, project, export = self.target_project(Path(temp))
            before = self.lifecycle.tree_bytes(target, installation)
            receipts_before = self.lifecycle.tree_bytes(project / "receipts")
            backups_before = self.lifecycle.tree_bytes(project / "backups")
            refused = self.run_cli_with_property(
                "0",
                "import-adaptive",
                "--project",
                project,
                "--export",
                export,
                "--target-root",
                target,
            )
            self.assertEqual(3, refused.returncode, refused.stderr)
            self.assertIn("space", refused.stderr.lower())
            forced = self.run_cli(
                "import-adaptive",
                "--project",
                project,
                "--export",
                export,
                "--target-root",
                target,
                "--force",
            )
            self.assertEqual(2, forced.returncode)
            self.assertEqual(before, self.lifecycle.tree_bytes(target, installation))
            self.assertEqual(receipts_before, self.lifecycle.tree_bytes(project / "receipts"))
            self.assertEqual(backups_before, self.lifecycle.tree_bytes(project / "backups"))

    def test_recovery_restores_failed_import_rollback(self):
        with tempfile.TemporaryDirectory(prefix="adaptive-import-recovery-") as temp:
            target, installation, project, export = self.target_project(Path(temp))
            before = self.lifecycle.tree_bytes(target, installation)
            failed = self.run_failure(
                "import",
                "package-file-published-0000,rollback-before-0006",
                project,
                target,
                export,
            )
            self.assertEqual(3, failed.returncode, failed.stderr)
            self.assertNotEqual(before, self.lifecycle.tree_bytes(target, installation))
            preview = self.run_cli(
                "recover-adaptive", "--project", project, "--target-root", target
            )
            self.assertEqual(0, preview.returncode, preview.stderr)
            recovered = self.run_cli(
                "recover-adaptive",
                "--project",
                project,
                "--target-root",
                target,
                "--confirm",
                "RECOVER",
            )
            self.assertEqual(0, recovered.returncode, recovered.stderr)
            self.assertEqual(before, self.lifecycle.tree_bytes(target, installation))
            statuses = [
                json.loads(path.read_text(encoding="utf-8"))["status"]
                for path in (project / "receipts").glob("*.json")
            ]
            self.assertNotIn("pending", statuses)
            self.assertNotIn("recovery-required", statuses)

    def test_partial_undo_rolls_back_to_installed_state(self):
        with tempfile.TemporaryDirectory(prefix="adaptive-undo-rollback-") as temp:
            target, installation, project, export = self.target_project(Path(temp))
            applied = self.run_cli(
                "import-adaptive",
                "--project",
                project,
                "--export",
                export,
                "--target-root",
                target,
                "--confirm",
                "IMPORT",
            )
            self.assertEqual(0, applied.returncode, applied.stderr)
            installed = self.lifecycle.tree_bytes(target, installation)
            failed = self.run_failure("undo", "undo-after-0000", project, target)
            self.assertEqual(3, failed.returncode, failed.stderr)
            self.assertEqual(installed, self.lifecycle.tree_bytes(target, installation))
            statuses = sorted(
                json.loads(path.read_text(encoding="utf-8"))["status"]
                for path in (project / "receipts").glob("*.json")
            )
            self.assertEqual(["rolled-back", "successful"], statuses)

    def test_recovery_restores_failed_undo_rollback(self):
        with tempfile.TemporaryDirectory(prefix="adaptive-undo-recovery-") as temp:
            target, installation, project, export = self.target_project(Path(temp))
            applied = self.run_cli(
                "import-adaptive",
                "--project",
                project,
                "--export",
                export,
                "--target-root",
                target,
                "--confirm",
                "IMPORT",
            )
            self.assertEqual(0, applied.returncode, applied.stderr)
            installed = self.lifecycle.tree_bytes(target, installation)
            failed = self.run_failure(
                "undo",
                "undo-after-0000,undo-rollback-before-0006",
                project,
                target,
            )
            self.assertEqual(3, failed.returncode, failed.stderr)
            self.assertNotEqual(installed, self.lifecycle.tree_bytes(target, installation))
            recovered = self.run_cli(
                "recover-adaptive",
                "--project",
                project,
                "--target-root",
                target,
                "--confirm",
                "RECOVER",
            )
            self.assertEqual(0, recovered.returncode, recovered.stderr)
            self.assertEqual(installed, self.lifecycle.tree_bytes(target, installation))
            preview = self.run_cli(
                "undo-adaptive", "--project", project, "--target-root", target
            )
            self.assertEqual(0, preview.returncode, preview.stderr)


if __name__ == "__main__":
    unittest.main()
