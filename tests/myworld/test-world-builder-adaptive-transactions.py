#!/usr/bin/env python3
"""Temporary-fixture coverage for adaptive export/import/recovery/undo."""

import importlib.util
import json
import os
import shutil
import socket
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
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;

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
            if ("export".equals(operation)) {
                WorldBuilderAdaptiveExporter.Observer observer =
                    new WorldBuilderAdaptiveExporter.Observer() {
                        @Override public void observe(String milestone, Path path)
                            throws Exception {
                            if (selected(failures, milestone)) {
                                throw new Exception("injected " + milestone);
                            }
                        }
                    };
                new WorldBuilderAdaptiveExporter(observer).export(project);
            } else if ("export-tamper".equals(operation)) {
                WorldBuilderAdaptiveExporter.Observer observer =
                    new WorldBuilderAdaptiveExporter.Observer() {
                        @Override public void observe(String milestone, Path path)
                            throws Exception {
                            if ("after-publish".equals(milestone)) {
                                Files.write(path.resolve(
                                    "package/terrain/creator/lp0/xp0-yp0.raw"),
                                    new byte[] {1}, StandardOpenOption.APPEND);
                            }
                        }
                    };
                new WorldBuilderAdaptiveExporter(observer).export(project);
            } else if ("import".equals(operation)) {
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
            } else if ("import-stale".equals(operation)) {
                WorldBuilderAdaptiveImporter importer =
                    new WorldBuilderAdaptiveImporter();
                WorldBuilderAdaptiveImporter.Preview preview = importer.preview(
                    project, Paths.get(args[4]), target);
                Files.write(target.resolve("server/evidence/render-assets.bin"),
                    new byte[] {1}, StandardOpenOption.APPEND);
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
            } else if ("undo-stale".equals(operation)) {
                WorldBuilderAdaptiveUndo undo = new WorldBuilderAdaptiveUndo();
                WorldBuilderAdaptiveUndo.Preview preview = undo.preview(project, target);
                Path changed = target.resolve(
                    preview.installedPlan.actions.get(0).destinationRelativePath);
                Files.write(changed, new byte[] {1}, StandardOpenOption.APPEND);
                undo.apply(preview, "UNDO");
            } else if ("recovery".equals(operation)) {
                WorldBuilderAdaptiveRecovery.Observer observer =
                    new WorldBuilderAdaptiveRecovery.Observer() {
                        @Override public void observe(String milestone, Path path)
                            throws Exception {
                            if (selected(failures, milestone)) {
                                throw new Exception("injected " + milestone);
                            }
                        }
                    };
                WorldBuilderAdaptiveRecovery recovery =
                    new WorldBuilderAdaptiveRecovery(observer);
                WorldBuilderAdaptiveRecovery.Preview preview =
                    recovery.preview(project, target);
                recovery.apply(preview, "RECOVER");
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

    def transaction_artifacts(self, project: Path):
        return (
            self.lifecycle.tree_bytes(project / "backups"),
            self.lifecycle.tree_bytes(project / "receipts"),
        )

    def assert_no_transaction_stage(self, target: Path):
        for path in target.rglob("*"):
            self.assertNotIn(".stage-", path.name, path)
            self.assertNotIn(".rollback-", path.name, path)
            self.assertNotIn(".undo-", path.name, path)
            self.assertNotIn(".recover-", path.name, path)

    def assert_windows_safe_plan_paths(self, value: dict):
        invalid = set('<>:"|?*')
        paths = [value["backupRootRelativePath"], value["receiptRelativePath"]]
        for action in value["actions"]:
            paths.extend(
                [
                    action["destinationRelativePath"],
                    action["contentRelativePath"],
                    action["backupRelativePath"],
                ]
            )
        for change in value["configurationChanges"]:
            paths.append(change["configurationRelativePath"])
        for verification in (
            value["postWriteVerifications"] + value["rollbackVerifications"]
        ):
            paths.append(verification["relativePath"])
        for relative in filter(None, paths):
            self.assertTrue(invalid.isdisjoint(relative), relative)
            for component in relative.split("/"):
                self.assertFalse(component.endswith((" ", ".")), relative)

    def target_project(
        self, base: Path, representation="layered", install_enabled=True,
        port_evidence=False,
    ):
        target = (
            self.fixtures.descriptor_fixture(str(base))
            if representation == "layered"
            else self.packed_fixtures.fixture(base)
        )
        capability_path = target / "server/world-builder-capabilities.json"
        capability = json.loads(capability_path.read_text(encoding="utf-8"))
        if not install_enabled:
            capability["install"] = {
                "enabled": False,
                "serverRoles": [],
                "clientRoles": [],
                "configurationRoles": [],
                "mutationProfileId": "",
                "offlineEvidence": [],
            }
        elif not port_evidence:
            capability["install"]["offlineEvidence"] = ["pid-file"]
        self.lifecycle.write_json(capability_path, capability)
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
                source_before = self.lifecycle.tree_bytes(project / "source")
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
                self.assert_windows_safe_plan_paths(json.loads(preview.stdout))
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
                self.assertIn("administratorAction", applied.stdout)
                self.assertIn("Distribute the exact installed client package", applied.stdout)
                self.assertNotEqual(before, self.lifecycle.tree_bytes(target, installation))
                self.assertEqual(source_before, self.lifecycle.tree_bytes(project / "source"))
                undo_preview = self.run_cli(
                    "undo-adaptive", "--project", project, "--target-root", target
                )
                self.assertEqual(0, undo_preview.returncode, undo_preview.stderr)
                self.assert_windows_safe_plan_paths(json.loads(undo_preview.stdout))
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
                self.assertEqual(source_before, self.lifecycle.tree_bytes(project / "source"))

    def test_export_is_portable_deterministic_and_failure_atomic(self):
        with tempfile.TemporaryDirectory(prefix="adaptive-export-portable-") as temp:
            base = Path(temp)
            target, installation, project, _ = self.target_project(base)
            source_before = self.lifecycle.tree_bytes(project / "source")

            # Export is project-local and must not re-read a target that has drifted.
            evidence = target / "server/evidence/render-assets.bin"
            evidence.write_bytes(evidence.read_bytes() + b"target drift")
            target_before = self.lifecycle.tree_bytes(target, installation)
            portable = base / "portable-builder"
            shutil.copytree(installation, portable)
            project_id = json.loads((project / "project.json").read_text())["projectId"]
            portable_project = portable / "projects" / project_id

            local = self.run_cli("export-adaptive", "--project", project)
            copied = self.run_cli("export-adaptive", "--project", portable_project)
            self.assertEqual(0, local.returncode, local.stderr)
            self.assertEqual(0, copied.returncode, copied.stderr)
            local_value = json.loads(local.stdout)
            copied_value = json.loads(copied.stdout)
            self.assertEqual(
                local_value["exportFingerprintSha256"],
                copied_value["exportFingerprintSha256"],
            )
            self.assertEqual(
                self.lifecycle.tree_bytes(Path(local_value["exportDirectory"])),
                self.lifecycle.tree_bytes(Path(copied_value["exportDirectory"])),
            )
            self.assertEqual(source_before, self.lifecycle.tree_bytes(project / "source"))
            self.assertEqual(target_before, self.lifecycle.tree_bytes(target, installation))

            exports_before = self.lifecycle.tree_bytes(project / "exports")
            for milestone in (
                "stage-created", "package-copied", "before-publish", "after-publish"
            ):
                with self.subTest(milestone=milestone):
                    failed = self.run_failure(
                        "export", milestone, project, target
                    )
                    self.assertNotEqual(0, failed.returncode)
                    self.assertEqual(
                        exports_before, self.lifecycle.tree_bytes(project / "exports")
                    )
                    self.assertEqual(
                        source_before, self.lifecycle.tree_bytes(project / "source")
                    )
                    self.assertEqual(
                        target_before, self.lifecycle.tree_bytes(target, installation)
                    )

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

    def test_confirmation_capability_and_corruption_refuse_before_target_writes(self):
        with tempfile.TemporaryDirectory(prefix="adaptive-import-confirmation-") as temp:
            target, installation, project, export = self.target_project(Path(temp))
            target_before = self.lifecycle.tree_bytes(target, installation)
            artifacts_before = self.transaction_artifacts(project)
            refused = self.run_cli(
                "import-adaptive",
                "--project",
                project,
                "--export",
                export,
                "--target-root",
                target,
                "--confirm",
                "import",
            )
            self.assertEqual(3, refused.returncode, refused.stderr)
            self.assertIn("exact IMPORT", refused.stderr)
            self.assertEqual(target_before, self.lifecycle.tree_bytes(target, installation))
            self.assertEqual(artifacts_before, self.transaction_artifacts(project))

        with tempfile.TemporaryDirectory(prefix="adaptive-import-no-loader-") as temp:
            target, installation, project, export = self.target_project(
                Path(temp), install_enabled=False
            )
            target_before = self.lifecycle.tree_bytes(target, installation)
            artifacts_before = self.transaction_artifacts(project)
            refused = self.run_cli(
                "import-adaptive",
                "--project",
                project,
                "--export",
                export,
                "--target-root",
                target,
            )
            self.assertEqual(3, refused.returncode, refused.stderr)
            self.assertIn("LOADER_INCOMPATIBLE", refused.stderr)
            self.assertIn("server/client install capability", refused.stderr)
            self.assertEqual(target_before, self.lifecycle.tree_bytes(target, installation))
            self.assertEqual(artifacts_before, self.transaction_artifacts(project))

        with tempfile.TemporaryDirectory(prefix="adaptive-import-source-corrupt-") as temp:
            target, installation, project, export = self.target_project(Path(temp))
            target_before = self.lifecycle.tree_bytes(target, installation)
            artifacts_before = self.transaction_artifacts(project)
            source_file = next(
                path
                for path in (project / "source/original").rglob("*")
                if path.is_file()
            )
            source_file.write_bytes(source_file.read_bytes() + b"corrupt")
            refused = self.run_cli(
                "import-adaptive",
                "--project",
                project,
                "--export",
                export,
                "--target-root",
                target,
            )
            self.assertEqual(3, refused.returncode, refused.stderr)
            self.assertIn("SOURCE_CORRUPT", refused.stderr)
            self.assertEqual(target_before, self.lifecycle.tree_bytes(target, installation))
            self.assertEqual(artifacts_before, self.transaction_artifacts(project))

        with tempfile.TemporaryDirectory(prefix="adaptive-import-export-corrupt-") as temp:
            target, installation, project, export = self.target_project(Path(temp))
            target_before = self.lifecycle.tree_bytes(target, installation)
            artifacts_before = self.transaction_artifacts(project)
            terrain = next((export / "package/terrain").rglob("*.raw"))
            terrain.write_bytes(terrain.read_bytes() + b"corrupt")
            refused = self.run_cli(
                "import-adaptive",
                "--project",
                project,
                "--export",
                export,
                "--target-root",
                target,
            )
            self.assertEqual(3, refused.returncode, refused.stderr)
            self.assertIn("SOURCE_CORRUPT", refused.stderr)
            self.assertEqual(target_before, self.lifecycle.tree_bytes(target, installation))
            self.assertEqual(artifacts_before, self.transaction_artifacts(project))

    def test_offline_and_unsafe_target_evidence_refuse_without_side_effects(self):
        with tempfile.TemporaryDirectory(prefix="adaptive-import-pid-") as temp:
            target, installation, project, export = self.target_project(Path(temp))
            pid = target / "server/run/server.pid"
            pid.parent.mkdir(parents=True)
            pid.write_text("12345\n", encoding="utf-8")
            target_before = self.lifecycle.tree_bytes(target, installation)
            artifacts_before = self.transaction_artifacts(project)
            refused = self.run_cli(
                "import-adaptive", "--project", project, "--export", export,
                "--target-root", target
            )
            self.assertEqual(3, refused.returncode, refused.stderr)
            self.assertIn("OFFLINE_REQUIRED", refused.stderr)
            self.assertIn("server.pid", refused.stderr)
            self.assertEqual(target_before, self.lifecycle.tree_bytes(target, installation))
            self.assertEqual(artifacts_before, self.transaction_artifacts(project))

        with tempfile.TemporaryDirectory(prefix="adaptive-import-symlink-") as temp:
            base = Path(temp)
            target, installation, project, export = self.target_project(base)
            outside = base / "outside"
            outside.mkdir()
            (target / "server/world-builder").symlink_to(
                outside, target_is_directory=True
            )
            target_before = self.lifecycle.tree_bytes(target, installation)
            artifacts_before = self.transaction_artifacts(project)
            refused = self.run_cli(
                "import-adaptive", "--project", project, "--export", export,
                "--target-root", target
            )
            self.assertEqual(3, refused.returncode, refused.stderr)
            self.assertIn("UNSAFE_PATH", refused.stderr)
            self.assertEqual({}, self.lifecycle.tree_bytes(outside))
            self.assertEqual(target_before, self.lifecycle.tree_bytes(target, installation))
            self.assertEqual(artifacts_before, self.transaction_artifacts(project))

        if hasattr(os, "link"):
            with tempfile.TemporaryDirectory(prefix="adaptive-import-hardlink-") as temp:
                base = Path(temp)
                target, installation, project, export = self.target_project(base)
                config = target / "server/world-builder-configs/primary.json"
                alias = base / "configuration-alias.json"
                shutil.copy2(config, alias)
                config.unlink()
                os.link(alias, config)
                target_before = self.lifecycle.tree_bytes(target, installation)
                artifacts_before = self.transaction_artifacts(project)
                refused = self.run_cli(
                    "import-adaptive", "--project", project, "--export", export,
                    "--target-root", target
                )
                self.assertEqual(3, refused.returncode, refused.stderr)
                self.assertIn("UNSAFE_PATH", refused.stderr)
                self.assertEqual(target_before, self.lifecycle.tree_bytes(target, installation))
                self.assertEqual(artifacts_before, self.transaction_artifacts(project))

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

    def test_stale_import_and_undo_previews_fail_closed(self):
        with tempfile.TemporaryDirectory(prefix="adaptive-import-stale-preview-") as temp:
            target, installation, project, export = self.target_project(Path(temp))
            target_before = self.lifecycle.tree_bytes(target, installation)
            artifacts_before = self.transaction_artifacts(project)
            refused = self.run_failure(
                "import-stale", "none", project, target, export
            )
            self.assertEqual(3, refused.returncode, refused.stderr)
            self.assertIn("TARGET_DRIFT", refused.stderr)
            target_after = self.lifecycle.tree_bytes(target, installation)
            changed = sorted(
                path
                for path in set(target_before) | set(target_after)
                if target_before.get(path) != target_after.get(path)
            )
            self.assertEqual(["server/evidence/render-assets.bin"], changed)
            self.assertEqual(artifacts_before, self.transaction_artifacts(project))

        with tempfile.TemporaryDirectory(prefix="adaptive-undo-stale-preview-") as temp:
            target, installation, project, export = self.target_project(Path(temp))
            applied = self.run_cli(
                "import-adaptive", "--project", project, "--export", export,
                "--target-root", target, "--confirm", "IMPORT"
            )
            self.assertEqual(0, applied.returncode, applied.stderr)
            installed_before = self.lifecycle.tree_bytes(target, installation)
            artifacts_before = self.transaction_artifacts(project)
            refused = self.run_failure("undo-stale", "none", project, target)
            self.assertEqual(3, refused.returncode, refused.stderr)
            self.assertIn("TARGET_DRIFT", refused.stderr)
            installed_after = self.lifecycle.tree_bytes(target, installation)
            changed = sorted(
                path
                for path in set(installed_before) | set(installed_after)
                if installed_before.get(path) != installed_after.get(path)
            )
            self.assertEqual(1, len(changed), changed)
            self.assertIn("world-builder/packages", changed[0])
            self.assertEqual(artifacts_before, self.transaction_artifacts(project))

    def test_partial_import_rolls_back_exactly(self):
        milestones = [
            "plan-confirmed",
            "backups-verified",
            "pending-receipt-written",
            "before-first-target-mutation",
            *[f"package-file-staged-{index:04d}" for index in range(6)],
            *[f"package-file-published-{index:04d}" for index in range(6)],
            "activation-staged",
            "before-activation",
            "activation-published",
            "post-write-verified",
            "before-success-receipt",
        ]
        for milestone in milestones:
            with self.subTest(milestone=milestone), tempfile.TemporaryDirectory(
                prefix="adaptive-import-rollback-"
            ) as temp:
                target, installation, project, export = self.target_project(Path(temp))
                before = self.lifecycle.tree_bytes(target, installation)
                source_before = self.lifecycle.tree_bytes(project / "source")
                failed = self.run_failure("import", milestone, project, target, export)
                self.assertEqual(3, failed.returncode, failed.stderr)
                self.assertEqual(before, self.lifecycle.tree_bytes(target, installation))
                self.assertEqual(source_before, self.lifecycle.tree_bytes(project / "source"))
                statuses = [
                    json.loads(path.read_text(encoding="utf-8"))["status"]
                    for path in (project / "receipts").glob("*.json")
                ]
                if milestone == "plan-confirmed":
                    self.assertEqual([], statuses)
                else:
                    expected = (
                        "failed-no-change"
                        if milestone in (
                            "backups-verified",
                            "pending-receipt-written",
                            "before-first-target-mutation",
                            "package-file-staged-0000",
                        )
                        else "rolled-back"
                    )
                    self.assertEqual([expected], statuses)
                self.assert_no_transaction_stage(target)

    def test_export_publication_failures_leave_no_partial_output(self):
        with tempfile.TemporaryDirectory(prefix="adaptive-export-failures-") as temp:
            target, installation, project, export = self.target_project(Path(temp))
            target_before = self.lifecycle.tree_bytes(target, installation)
            exports_before = self.lifecycle.tree_bytes(project / "exports")
            for milestone in (
                "stage-created", "package-copied", "before-publish", "after-publish"
            ):
                with self.subTest(milestone=milestone):
                    failed = self.run_failure("export", milestone, project, target)
                    self.assertEqual(3, failed.returncode, failed.stderr)
                    self.assertEqual(exports_before, self.lifecycle.tree_bytes(project / "exports"))
                    self.assertEqual(target_before, self.lifecycle.tree_bytes(target, installation))
                    self.assertFalse(list((project / "exports").glob(".staging-*")))
            tampered = self.run_failure("export-tamper", "none", project, target)
            self.assertEqual(3, tampered.returncode, tampered.stderr)
            self.assertEqual(exports_before, self.lifecycle.tree_bytes(project / "exports"))
            self.assertEqual(target_before, self.lifecycle.tree_bytes(target, installation))

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

            for command, token in (("undo-adaptive", "UNDO"), ("recover-adaptive", "RECOVER")):
                forced = self.run_cli(
                    command,
                    "--project",
                    project,
                    "--target-root",
                    target,
                    "--confirm",
                    token,
                    "--force",
                )
                self.assertEqual(2, forced.returncode)
                self.assertEqual(before, self.lifecycle.tree_bytes(target, installation))
                self.assertEqual(
                    receipts_before, self.lifecycle.tree_bytes(project / "receipts")
                )
                self.assertEqual(
                    backups_before, self.lifecycle.tree_bytes(project / "backups")
                )

    def test_receipt_and_backup_tampering_block_undo(self):
        for evidence in ("receipt", "backup"):
            with self.subTest(evidence=evidence), tempfile.TemporaryDirectory(
                prefix=f"adaptive-undo-{evidence}-tamper-"
            ) as temp:
                target, installation, project, export = self.target_project(Path(temp))
                applied = self.run_cli(
                    "import-adaptive", "--project", project, "--export", export,
                    "--target-root", target, "--confirm", "IMPORT"
                )
                self.assertEqual(0, applied.returncode, applied.stderr)
                receipt_path = next((project / "receipts").glob("*.json"))
                receipt = json.loads(receipt_path.read_text(encoding="utf-8"))
                if evidence == "receipt":
                    receipt["capabilityId"] = "tampered-capability-v1"
                    self.lifecycle.write_json(receipt_path, receipt)
                else:
                    backup = (
                        project
                        / "backups"
                        / receipt["transactionId"]
                        / "before/server/world-builder-configs/primary.json"
                    )
                    backup.write_bytes(backup.read_bytes() + b"\n")
                installed = self.lifecycle.tree_bytes(target, installation)
                artifacts = self.transaction_artifacts(project)
                refused = self.run_cli(
                    "undo-adaptive", "--project", project, "--target-root", target
                )
                self.assertEqual(3, refused.returncode, refused.stderr)
                self.assertEqual(installed, self.lifecycle.tree_bytes(target, installation))
                self.assertEqual(artifacts, self.transaction_artifacts(project))

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

    def test_recovery_finalizes_file_complete_failure_without_orphan_artifacts(self):
        with tempfile.TemporaryDirectory(prefix="adaptive-import-finalize-") as temp:
            base = Path(temp)
            target, installation, project, export = self.target_project(base)
            before = self.lifecycle.tree_bytes(target, installation)
            failed = self.run_failure(
                "import",
                "package-file-published-0000,rollback-after-0006",
                project,
                target,
                export,
            )
            self.assertEqual(3, failed.returncode, failed.stderr)
            uncertain = self.lifecycle.tree_bytes(target, installation)
            self.assertNotEqual(before, uncertain)
            self.assertEqual(
                {path: value for path, value in before.items() if value[0] == "file"},
                {path: value for path, value in uncertain.items() if value[0] == "file"},
            )
            original_backups = sorted(path.name for path in (project / "backups").iterdir())
            original_receipts = sorted(path.name for path in (project / "receipts").iterdir())
            self.assertEqual(1, len(original_backups))
            self.assertEqual(1, len(original_receipts))
            original_receipt = project / "receipts" / original_receipts[0]
            self.assertEqual(
                "recovery-required",
                json.loads(original_receipt.read_text(encoding="utf-8"))["status"],
            )

            project_id = json.loads(
                (project / "project.json").read_text(encoding="utf-8")
            )["projectId"]
            interrupted_target = base / "interrupted-target"
            shutil.copytree(target, interrupted_target)
            interrupted_installation = interrupted_target / "World Builder 2"
            interrupted_project = (
                interrupted_installation / "projects" / project_id
            )
            interrupted = self.run_failure(
                "recovery",
                "recovery-after-directory-cleanup",
                interrupted_project,
                interrupted_target,
            )
            self.assertEqual(3, interrupted.returncode, interrupted.stderr)
            self.assertEqual(
                uncertain,
                self.lifecycle.tree_bytes(
                    interrupted_target, interrupted_installation
                ),
            )
            self.assertEqual(
                self.transaction_artifacts(project),
                self.transaction_artifacts(interrupted_project),
            )

            preview = self.run_cli(
                "recover-adaptive", "--project", project, "--target-root", target
            )
            self.assertEqual(0, preview.returncode, preview.stderr)
            self.assertEqual([], json.loads(preview.stdout)["actions"])
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
            self.assertEqual(
                original_backups,
                sorted(path.name for path in (project / "backups").iterdir()),
            )
            self.assertEqual(
                original_receipts,
                sorted(path.name for path in (project / "receipts").iterdir()),
            )
            self.assertEqual(
                "rolled-back",
                json.loads(original_receipt.read_text(encoding="utf-8"))["status"],
            )
            self.assert_no_transaction_stage(target)

    def test_every_recovery_boundary_rolls_back_to_uncertain_start(self):
        milestones = (
            "recovery-plan-confirmed",
            "recovery-evidence-written",
            "recovery-action-applied-0000",
            "recovery-before-directory-cleanup",
            "recovery-after-directory-cleanup",
            "recovery-before-success-receipt",
            "recovery-before-original-finalize",
        )
        with tempfile.TemporaryDirectory(prefix="adaptive-recovery-boundaries-") as temp:
            base = Path(temp)
            template = base / "template"
            template.mkdir()
            target, installation, project, export = self.target_project(template)
            failed_import = self.run_failure(
                "import",
                "package-file-published-0000,rollback-before-0006",
                project,
                target,
                export,
            )
            self.assertEqual(3, failed_import.returncode, failed_import.stderr)
            project_id = json.loads((project / "project.json").read_text())["projectId"]
            uncertain = self.lifecycle.tree_bytes(target, installation)
            source = self.lifecycle.tree_bytes(project / "source")

            for index, milestone in enumerate(milestones):
                with self.subTest(milestone=milestone):
                    copied_target = base / f"target-{index:02d}"
                    shutil.copytree(target, copied_target)
                    copied_installation = copied_target / "World Builder 2"
                    copied_project = copied_installation / "projects" / project_id
                    failed = self.run_failure(
                        "recovery", milestone, copied_project, copied_target
                    )
                    self.assertEqual(3, failed.returncode, failed.stderr)
                    self.assertEqual(
                        uncertain,
                        self.lifecycle.tree_bytes(copied_target, copied_installation),
                    )
                    self.assertEqual(
                        source, self.lifecycle.tree_bytes(copied_project / "source")
                    )
                    statuses = [
                        json.loads(path.read_text(encoding="utf-8"))["status"]
                        for path in (copied_project / "receipts").glob("*.json")
                    ]
                    self.assertIn("recovery-required", statuses)
                    self.assertNotIn("pending", statuses)
                    if milestone != "recovery-plan-confirmed":
                        self.assertTrue(
                            "failed-no-change" in statuses or "rolled-back" in statuses,
                            statuses,
                        )
                    self.assert_no_transaction_stage(copied_target)

    def test_partial_undo_rolls_back_to_installed_state(self):
        milestones = [
            "undo-plan-confirmed",
            "undo-pending-receipt",
            *[f"undo-before-{index:04d}" for index in range(7)],
            *[f"undo-after-{index:04d}" for index in range(7)],
            "undo-before-directory-cleanup",
            "undo-after-directory-cleanup",
            "undo-before-success-receipt",
        ]
        for milestone in milestones:
            with self.subTest(milestone=milestone), tempfile.TemporaryDirectory(
                prefix="adaptive-undo-rollback-"
            ) as temp:
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
                source_before = self.lifecycle.tree_bytes(project / "source")
                failed = self.run_failure("undo", milestone, project, target)
                self.assertEqual(3, failed.returncode, failed.stderr)
                self.assertEqual(installed, self.lifecycle.tree_bytes(target, installation))
                self.assertEqual(source_before, self.lifecycle.tree_bytes(project / "source"))
                statuses = sorted(
                    json.loads(path.read_text(encoding="utf-8"))["status"]
                    for path in (project / "receipts").glob("*.json")
                )
                if milestone == "undo-plan-confirmed":
                    self.assertEqual(["successful"], statuses)
                else:
                    expected = (
                        "failed-no-change"
                        if milestone in ("undo-pending-receipt", "undo-before-0000")
                        else "rolled-back"
                    )
                    self.assertEqual([expected, "successful"], statuses)
                self.assert_no_transaction_stage(target)

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

    def test_z_port_bind_offline_evidence_refuses_and_releases_cleanly(self):
        with tempfile.TemporaryDirectory(prefix="adaptive-import-port-") as temp:
            target, installation, project, export = self.target_project(
                Path(temp), port_evidence=True
            )
            target_before = self.lifecycle.tree_bytes(target, installation)
            artifacts_before = self.transaction_artifacts(project)
            with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as held:
                held.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 0)
                held.bind(("0.0.0.0", 43594))
                held.listen(1)
                refused = self.run_cli(
                    "import-adaptive", "--project", project, "--export", export,
                    "--target-root", target
                )
            self.assertEqual(3, refused.returncode, refused.stderr)
            self.assertIn("OFFLINE_REQUIRED", refused.stderr)
            self.assertIn("43594", refused.stderr)
            self.assertEqual(target_before, self.lifecycle.tree_bytes(target, installation))
            self.assertEqual(artifacts_before, self.transaction_artifacts(project))

            released = self.run_cli(
                "import-adaptive", "--project", project, "--export", export,
                "--target-root", target
            )
            self.assertEqual(0, released.returncode, released.stderr)
            self.assertEqual(target_before, self.lifecycle.tree_bytes(target, installation))
            self.assertEqual(artifacts_before, self.transaction_artifacts(project))


if __name__ == "__main__":
    unittest.main()
