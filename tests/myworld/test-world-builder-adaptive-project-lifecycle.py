#!/usr/bin/env python3
"""Temporary-fixture coverage for the Phase 3 adaptive project lifecycle."""

import hashlib
import importlib.util
import json
import os
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
SOURCE_ROOT = ROOT / "tools/world-builder/src"
MAIN_CLASS = "com.openrsc.worldbuilder.WorldBuilderCli"
DISCOVERY_TEST = ROOT / "tests/myworld/test-world-builder-adaptive-discovery.py"
PACKED_CONVERSION_TEST = ROOT / "tests/myworld/test-world-builder-packed-conversion.py"
CANONICAL_VOID_TILE = bytes((0, 1, 8, 0, 0, 0, 0, 0, 0, 0))
CANONICAL_VOID_SECTOR = CANONICAL_VOID_TILE * (48 * 48)


def load_discovery_fixtures():
    spec = importlib.util.spec_from_file_location(
        "world_builder_adaptive_discovery_fixtures", DISCOVERY_TEST
    )
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module.AdaptiveDiscoveryTest(
        "test_no_server_is_standalone_and_deterministic_without_writes"
    )


def load_packed_fixtures():
    spec = importlib.util.spec_from_file_location(
        "world_builder_packed_conversion_fixtures", PACKED_CONVERSION_TEST
    )
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module.PackedConversionTest(
        "test_deterministic_conversion_reverse_parity_and_portability"
    )


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def write_json(path: Path, value: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        json.dumps(value, sort_keys=True, indent=2, ensure_ascii=False) + "\n",
        encoding="utf-8",
    )


def tree_bytes(root: Path, excluded: Path | None = None) -> dict[str, tuple]:
    result = {}
    if not root.exists():
        return result
    excluded_resolved = excluded.resolve() if excluded and excluded.exists() else None
    for path in sorted(root.rglob("*")):
        if excluded_resolved is not None:
            try:
                path.resolve().relative_to(excluded_resolved)
                continue
            except ValueError:
                pass
        relative = path.relative_to(root).as_posix()
        if path.is_symlink():
            result[relative] = ("link", os.readlink(path))
        elif path.is_dir():
            result[relative] = ("dir",)
        else:
            result[relative] = ("file", path.stat().st_size, sha256(path))
    return result


class AdaptiveProjectLifecycleTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.compile_temp = tempfile.TemporaryDirectory(
            prefix="world-builder-adaptive-project-classes-"
        )
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
            / "harness/com/openrsc/worldbuilder/AdaptiveProjectFailureHarness.java"
        )
        harness.parent.mkdir(parents=True)
        harness.write_text(
            """
package com.openrsc.worldbuilder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

public final class AdaptiveProjectFailureHarness {
    public static void main(String[] args) throws Exception {
        final String mode = args[6];
        final Path target = Paths.get(args[2]);
        WorldBuilderAdaptiveProjectLifecycle.Observer observer =
            new WorldBuilderAdaptiveProjectLifecycle.Observer() {
                @Override
                public void observe(String milestone, Path stage) throws Exception {
                    if ("drift-source-prepared".equals(mode)
                        && "source-prepared".equals(milestone)) {
                        Files.write(
                            target.resolve("server/world-builder-configs/primary.json"),
                            "\\n".getBytes(StandardCharsets.UTF_8),
                            StandardOpenOption.APPEND);
                        return;
                    }
                    if (mode.equals(milestone)) {
                        throw new Exception("injected failure at " + milestone);
                    }
                }
            };
        try {
            new WorldBuilderAdaptiveProjectLifecycle(observer).create(
                Paths.get(args[0]), Paths.get(args[1]), target,
                Paths.get(args[3]), args[4], Integer.parseInt(args[5]), "CREATE");
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
        supervisor_harness = (
            Path(cls.compile_temp.name)
            / "harness/com/openrsc/worldbuilder/AdaptiveProjectSupervisorHarness.java"
        )
        supervisor_harness.write_text(
            r"""
package com.openrsc.worldbuilder;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.List;

public final class AdaptiveProjectSupervisorHarness {
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static List<String> command(String classes, String nested,
        Path project, int port) {
        return Arrays.asList(
            Paths.get(System.getProperty("java.home"), "bin", "java").toString(),
            "-cp", classes,
            "com.openrsc.worldbuilder.AdaptiveProjectSupervisorHarness$" + nested,
            project.toString(), Integer.toString(port));
    }

    public static void main(String[] args) throws Exception {
        Path project = Paths.get(args[0]);
        String classes = args[1];
        int port = WorldBuilderAdaptiveProjectLifecycle.readRuntimePort(project);
        WorldBuilderProcessSupervisor supervisor = new WorldBuilderProcessSupervisor();
        boolean unavailable = false;
        try {
            supervisor.runAdaptiveProject(project);
        } catch (WorldBuilderContractException expected) {
            unavailable = WorldBuilderErrorCodes.LOADER_INCOMPATIBLE.equals(expected.code())
                && expected.getMessage().contains("owner-native validation");
        }
        require(unavailable,
            "native adaptive runtime must fail closed pending owner validation");

        List<String> server = command(classes, "FakeServer", project, port);
        List<String> client = command(classes, "FakeClient", project, port);
        if (args.length > 2 && "unsafe".equals(args[2])) {
            boolean refused = false;
            try {
                supervisor.superviseAdaptiveWithCommands(
                    project, server, client, 5000L);
            } catch (WorldBuilderContractException expected) {
                refused = WorldBuilderErrorCodes.UNSAFE_PATH.equals(expected.code());
            }
            require(refused, "unsafe adaptive mutable layout");
            System.out.println("unsafe-adaptive-supervision-refused");
            return;
        }
        Path lockPath = project.resolve("run/world-builder.lock");
        try (FileChannel channel = FileChannel.open(lockPath,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock lock = channel.lock()) {
            boolean saveRefused = false;
            try {
                new WorldBuilderAdaptiveProjectLifecycle().save(project);
            } catch (WorldBuilderContractException expected) {
                saveRefused = WorldBuilderErrorCodes.RECOVERY_REQUIRED.equals(
                    expected.code());
            }
            require(saveRefused, "external save must share the adaptive run lock");
            boolean refused = false;
            try {
                supervisor.superviseAdaptiveWithCommands(
                    project, server, client, 5000L);
            } catch (WorldBuilderContractException expected) {
                refused = WorldBuilderErrorCodes.RECOVERY_REQUIRED.equals(expected.code());
            }
            require(refused, "adaptive project lock");
        }

        int result = supervisor.superviseAdaptiveWithCommands(
            project, server, client, 5000L);
        require(result == 0, "adaptive isolated run");
        require(!Files.exists(project.resolve("run/server.pid")), "server PID cleanup");
        require(!Files.exists(project.resolve("run/client.pid")), "client PID cleanup");
        require(!Files.exists(project.resolve(
            "working/runtime/server/run/world-builder/ready")), "ready cleanup");
        require(Files.isRegularFile(project.resolve("run/last-run.json")),
            "bounded run receipt");
        System.out.println("adaptive-supervision-ok");
    }

    public static final class FakeServer {
        public static void main(String[] args) throws Exception {
            Path project = Paths.get(args[0]);
            int port = Integer.parseInt(args[1]);
            Path server = project.resolve("working/runtime/server");
            Path control = server.resolve("run/world-builder");
            Path credential = server.resolve("inc/sqlite/world-builder.credential");
            Files.createDirectories(control);
            Files.createDirectories(credential.getParent());
            Files.write(credential,
                "Abcdefghijk23456789Z".getBytes(StandardCharsets.US_ASCII));
            Files.write(server.resolve("ipbans.txt"), new byte[0]);
            try (ServerSocket listener = new ServerSocket(
                    port, 1, InetAddress.getByName("127.0.0.1"))) {
                listener.setSoTimeout(100);
                Files.write(control.resolve("ready"),
                    "ready\n".getBytes(StandardCharsets.US_ASCII));
                while (!Files.exists(control.resolve("shutdown.request"))) {
                    try (Socket ignored = listener.accept()) {
                        // Readiness probes connect and immediately close.
                    } catch (java.net.SocketTimeoutException expected) {
                        // Poll the project-local shutdown request again.
                    }
                }
            }
        }
    }

    public static final class FakeClient {
        public static void main(String[] args) throws Exception {
            Path project = Paths.get(args[0]);
            Path client = project.resolve("working/runtime/client");
            Files.write(client.resolve("clientSettings.conf"),
                "generated=true\n".getBytes(StandardCharsets.UTF_8));
            Thread.sleep(250L);
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
                str(supervisor_harness),
            ],
            check=True,
            cwd=ROOT,
            capture_output=True,
            text=True,
        )
        cls.fixtures = load_discovery_fixtures()
        cls.packed_fixtures = load_packed_fixtures()

    @classmethod
    def tearDownClass(cls):
        cls.compile_temp.cleanup()

    def run_cli(self, *args: object) -> subprocess.CompletedProcess:
        return subprocess.run(
            ["java", "-cp", str(self.classes), MAIN_CLASS, *map(str, args)],
            cwd=ROOT,
            text=True,
            capture_output=True,
        )

    @staticmethod
    def make_runtime(root: Path) -> Path:
        runtime = root / "builder-runtime"
        launcher = runtime / "launcher/world-builder-tools.jar"
        launcher.parent.mkdir(parents=True)
        launcher.write_bytes(b"content-neutral-fixture-tools\n")
        return runtime

    def discover(self, target: Path, destination: Path) -> dict:
        result = self.run_cli(
            "discover-adaptive", "--target-root", target
        )
        self.assertEqual(0, result.returncode, result.stderr)
        destination.write_text(result.stdout, encoding="utf-8")
        return json.loads(result.stdout)

    def create_project(
        self,
        installation: Path,
        runtime: Path,
        target: Path,
        report: Path,
        name: str,
        port: int,
        confirmation: str = "CREATE",
    ) -> tuple[subprocess.CompletedProcess, dict | None]:
        result = self.run_cli(
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
            name,
            "--port",
            port,
            "--confirm",
            confirmation,
        )
        return result, json.loads(result.stdout) if result.returncode == 0 else None

    def run_injected(
        self,
        installation: Path,
        runtime: Path,
        target: Path,
        report: Path,
        mode: str,
    ) -> subprocess.CompletedProcess:
        return subprocess.run(
            [
                "java",
                "-cp",
                str(self.classes),
                "com.openrsc.worldbuilder.AdaptiveProjectFailureHarness",
                str(installation),
                str(runtime),
                str(target),
                str(report),
                "Injected project",
                "43821",
                mode,
            ],
            cwd=ROOT,
            text=True,
            capture_output=True,
        )

    def run_supervision(
        self, project: Path, mode: str | None = None
    ) -> subprocess.CompletedProcess:
        arguments = [
            "java",
            "-cp",
            str(self.classes),
            "com.openrsc.worldbuilder.AdaptiveProjectSupervisorHarness",
            str(project),
            str(self.classes),
        ]
        if mode is not None:
            arguments.append(mode)
        return subprocess.run(
            arguments,
            cwd=ROOT,
            text=True,
            capture_output=True,
            timeout=20,
        )

    @staticmethod
    def change_working_terrain(project: Path) -> None:
        manifest_path = project / "working/layered-world/package/manifest.json"
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        declaration = manifest["terrainSectors"][0]
        terrain = manifest_path.parent / declaration["path"]
        payload = bytearray(terrain.read_bytes())
        payload[0] ^= 1
        terrain.write_bytes(payload)
        declaration["sha256"] = sha256(terrain)
        write_json(manifest_path, manifest)

    def assert_canonical_empty_package(self, package: Path) -> None:
        manifest = json.loads((package / "manifest.json").read_text(encoding="utf-8"))
        self.assertEqual(
            [{"id": "global", "kind": "static"}], manifest["worldSpaces"]
        )
        self.assertEqual(1, len(manifest["levels"]))
        self.assertEqual(0, manifest["levels"][0]["level"])
        self.assertEqual("global", manifest["levels"][0]["worldSpace"])

        self.assertEqual(1, len(manifest["terrainSectors"]))
        sector = manifest["terrainSectors"][0]
        self.assertEqual(
            {
                "encoding": "raw-layered-sector-v1",
                "level": 0,
                "path": "terrain/global/lp0/xp0-yp0.raw",
                "sectorX": 0,
                "sectorY": 0,
                "worldSpace": "global",
            },
            {key: sector[key] for key in (
                "encoding", "level", "path", "sectorX", "sectorY", "worldSpace"
            )},
        )
        terrain = package / sector["path"]
        self.assertEqual(CANONICAL_VOID_SECTOR, terrain.read_bytes())
        self.assertEqual(sha256(terrain), sector["sha256"])

        self.assertEqual(1, len(manifest["placementSets"]))
        placement_set = manifest["placementSets"][0]
        self.assertEqual(0, placement_set["level"])
        self.assertEqual("global", placement_set["worldSpace"])
        self.assertEqual("placements/global/lp0.json", placement_set["path"])
        placement_path = package / placement_set["path"]
        placement = json.loads(placement_path.read_text(encoding="utf-8"))
        self.assertEqual(0, placement["level"])
        self.assertEqual("global", placement["worldSpace"])
        self.assertEqual([], placement["boundaries"])
        self.assertEqual([], placement["groundItems"])
        self.assertEqual([], placement["npcs"])
        self.assertEqual([], placement["scenery"])
        self.assertEqual(sha256(placement_path), placement_set["sha256"])

    def test_standalone_empty_create_save_reopen_and_no_target_mutation(self):
        with tempfile.TemporaryDirectory(prefix="adaptive-project-empty-") as temp:
            base = Path(temp)
            installation = base / "World Builder 2"
            installation.mkdir()
            runtime = self.make_runtime(installation)
            target = base / "ordinary-parent"
            target.mkdir()
            report = base / "standalone-report.json"
            discovery = self.discover(target, report)
            before_target = tree_bytes(target)

            refused, _ = self.create_project(
                installation, runtime, target, report, "Empty project", 43801, "create"
            )
            self.assertEqual(3, refused.returncode)
            self.assertIn("CREATE", refused.stderr)
            self.assertFalse((installation / "project-registry.json").exists())

            created, summary = self.create_project(
                installation, runtime, target, report, "Empty project", 43801
            )
            self.assertEqual(0, created.returncode, created.stderr)
            self.assertEqual("standalone-empty", summary["origin"])
            self.assertEqual("ready-standalone", summary["state"])
            self.assertEqual(before_target, tree_bytes(target))
            project = Path(summary["projectRoot"])
            source_before = tree_bytes(project / "source")
            baseline_before = tree_bytes(project / "source/layered-baseline/package")

            stored_report = json.loads(
                (project / "discovery/report.json").read_text(encoding="utf-8")
            )
            self.assertEqual("", stored_report["targetRootDisplay"])
            self.assertEqual(
                discovery["discoveryFingerprintSha256"],
                stored_report["discoveryFingerprintSha256"],
            )
            descriptor = json.loads(
                (project / "source/original/empty-world-v1.json").read_text(
                    encoding="utf-8"
                )
            )
            self.assertEqual(
                {"level": 0, "x": 0, "y": 0}, descriptor["initialLocation"]
            )
            package = project / "working/layered-world/package"
            self.assert_canonical_empty_package(
                project / "source/layered-baseline/package"
            )
            self.assert_canonical_empty_package(package)
            catalog = json.loads(
                (
                    project / "source/runtime/default-definition-catalog.json"
                ).read_text(encoding="utf-8")
            )
            self.assertEqual([0, 7], catalog["tiles"])

            self.change_working_terrain(project)
            unsaved = self.run_cli(
                "open-project", "--installation-root", installation, "--target-root", target
            )
            self.assertEqual(3, unsaved.returncode)
            self.assertIn("save-project", unsaved.stderr)
            saved = self.run_cli("save-project", "--project", project)
            self.assertEqual(0, saved.returncode, saved.stderr)
            reopened = self.run_cli(
                "open-project", "--installation-root", installation, "--target-root", target
            )
            self.assertEqual(0, reopened.returncode, reopened.stderr)
            self.assertEqual(before_target, tree_bytes(target))
            self.assertEqual(source_before, tree_bytes(project / "source"))

            for command in ("import-active-adaptive", "undo-active-adaptive"):
                refused_active = self.run_cli(
                    command, "--installation-root", installation
                )
                self.assertEqual(3, refused_active.returncode)
                self.assertIn("NO_TARGET", refused_active.stderr)
                self.assertEqual(before_target, tree_bytes(target))
            native = self.run_cli("run-adaptive-project", "--project", project)
            self.assertEqual(3, native.returncode)
            self.assertIn("LOADER_INCOMPATIBLE", native.stderr)
            self.assertFalse((project / "working/runtime/server/ipbans.txt").exists())
            supervised = self.run_supervision(project)
            self.assertEqual(0, supervised.returncode, supervised.stdout + supervised.stderr)
            self.assertEqual("adaptive-supervision-ok\n", supervised.stdout)
            self.assertTrue((project / "working/runtime/server/ipbans.txt").is_file())
            self.assertTrue(
                (project / "working/runtime/client/clientSettings.conf").is_file()
            )
            self.assertEqual(before_target, tree_bytes(target))
            self.assertEqual(source_before, tree_bytes(project / "source"))
            self.assertEqual(
                baseline_before,
                tree_bytes(project / "source/layered-baseline/package"),
            )

            missing_target = base / "must-not-be-created-or-read"
            import_result = self.run_cli(
                "import",
                "--workspace",
                project,
                "--export",
                base / "missing-export",
                "--target-root",
                missing_target,
                "--dry-run",
            )
            self.assertEqual(3, import_result.returncode)
            self.assertIn("NO_TARGET", import_result.stderr)
            self.assertFalse(missing_target.exists())
            undo_result = self.run_cli(
                "undo-import",
                "--workspace",
                project,
                "--target-root",
                missing_target,
                "--dry-run",
            )
            self.assertEqual(3, undo_result.returncode)
            self.assertIn("NO_TARGET", undo_result.stderr)
            self.assertFalse(missing_target.exists())

    def test_layered_adoption_save_and_portable_detached_reopen(self):
        with tempfile.TemporaryDirectory(prefix="adaptive-project-layered-") as temp:
            base = Path(temp)
            target = self.fixtures.descriptor_fixture(str(base))
            installation = target / "World Builder 2"
            installation.mkdir()
            runtime = self.make_runtime(installation)
            report = base / "layered-report.json"
            discovery = self.discover(target, report)
            target_before = tree_bytes(target, installation)
            created, summary = self.create_project(
                installation, runtime, target, report, "Adopted map", 43802
            )
            self.assertEqual(0, created.returncode, created.stderr)
            self.assertEqual("target-layered", summary["origin"])
            self.assertEqual(target_before, tree_bytes(target, installation))
            project = Path(summary["projectRoot"])
            manifest = json.loads((project / "project.json").read_text(encoding="utf-8"))
            self.assertEqual(str(target.resolve()), manifest["target"]["locatorDisplay"])
            self.assertEqual("", manifest["fingerprints"]["conversionSha256"])
            self.assertFalse((project / "source/conversion").exists())
            self.assertEqual(
                tree_bytes(project / "source/layered-baseline/package"),
                tree_bytes(project / "working/layered-world/package"),
            )
            install_before_validation = tree_bytes(installation)
            validated = self.run_cli(
                "open-project",
                "--installation-root",
                installation,
                "--validate-only",
            )
            self.assertEqual(0, validated.returncode, validated.stderr)
            self.assertEqual("ready-attached", json.loads(validated.stdout)["state"])
            self.assertEqual(install_before_validation, tree_bytes(installation))
            target_display = str(target.resolve()).encode()
            for path in project.rglob("*"):
                if path.is_file() and path != project / "project.json":
                    self.assertNotIn(target_display, path.read_bytes(), path)

            baseline_before = tree_bytes(project / "source/layered-baseline/package")
            source_before = tree_bytes(project / "source")
            self.change_working_terrain(project)
            saved = self.run_cli("save-project", "--project", project)
            self.assertEqual(0, saved.returncode, saved.stderr)
            self.assertEqual(baseline_before, tree_bytes(project / "source/layered-baseline/package"))
            self.assertEqual(source_before, tree_bytes(project / "source"))
            self.assertEqual(target_before, tree_bytes(target, installation))

            supervised = self.run_supervision(project)
            self.assertEqual(0, supervised.returncode, supervised.stdout + supervised.stderr)
            self.assertEqual(source_before, tree_bytes(project / "source"))
            self.assertEqual(target_before, tree_bytes(target, installation))

            import_preview = self.run_cli(
                "import-active-adaptive", "--installation-root", installation
            )
            self.assertEqual(0, import_preview.returncode, import_preview.stderr)
            self.assertIn("Import preview", import_preview.stderr)
            self.assertEqual("", import_preview.stdout)
            self.assertIn("Import cancelled", import_preview.stderr)
            self.assertEqual(target_before, tree_bytes(target, installation))

            undo_without_import = self.run_cli(
                "undo-active-adaptive", "--installation-root", installation
            )
            self.assertEqual(3, undo_without_import.returncode)
            self.assertIn("no successful unreverted", undo_without_import.stderr.lower())
            self.assertEqual(target_before, tree_bytes(target, installation))

            portable = base / "portable-copy"
            shutil.copytree(installation, portable)
            copied_project = portable / "projects" / summary["projectId"]
            copied_source = tree_bytes(copied_project / "source")
            opened = self.run_cli("open-project", "--installation-root", portable)
            self.assertEqual(0, opened.returncode, opened.stderr)
            opened_summary = json.loads(opened.stdout)
            self.assertEqual("ready-detached", opened_summary["state"])
            self.assertEqual(copied_source, tree_bytes(copied_project / "source"))
            self.assertEqual(target_before, tree_bytes(target, installation))
            copied_manifest = json.loads(
                (copied_project / "project.json").read_text(encoding="utf-8")
            )
            self.assertEqual(
                str(target.resolve()), copied_manifest["target"]["locatorDisplay"]
            )

            wrong_target = base / "wrong-target"
            wrong_target.mkdir()
            wrong = self.run_cli(
                "open-project",
                "--installation-root",
                portable,
                "--target-root",
                wrong_target,
            )
            self.assertEqual(0, wrong.returncode, wrong.stderr)
            copied_manifest = json.loads(
                (copied_project / "project.json").read_text(encoding="utf-8")
            )
            self.assertEqual(
                str(target.resolve()), copied_manifest["target"]["locatorDisplay"]
            )

            moved_target = base / "moved-target"
            shutil.copytree(
                target,
                moved_target,
                ignore=shutil.ignore_patterns("World Builder 2"),
            )
            moved_before = tree_bytes(moved_target)
            reattached = self.run_cli(
                "open-project",
                "--installation-root",
                portable,
                "--target-root",
                moved_target,
            )
            self.assertEqual(0, reattached.returncode, reattached.stderr)
            self.assertEqual("ready-attached", json.loads(reattached.stdout)["state"])
            copied_manifest = json.loads(
                (copied_project / "project.json").read_text(encoding="utf-8")
            )
            self.assertEqual(
                str(moved_target.resolve()),
                copied_manifest["target"]["locatorDisplay"],
            )
            self.assertEqual(moved_before, tree_bytes(moved_target))

    def test_validate_only_refuses_missing_registry_lock_without_creating_it(self):
        with tempfile.TemporaryDirectory(prefix="adaptive-project-read-only-lock-") as temp:
            base = Path(temp)
            target = self.fixtures.descriptor_fixture(str(base))
            installation = target / "World Builder 2"
            installation.mkdir()
            runtime = self.make_runtime(installation)
            report = base / "layered-report.json"
            self.discover(target, report)
            created, _ = self.create_project(
                installation, runtime, target, report, "Read-only validation", 43812
            )
            self.assertEqual(0, created.returncode, created.stderr)

            registry_lock = installation / "projects/.registry.lock"
            self.assertTrue(registry_lock.is_file())
            registry_lock.unlink()
            installation_before = tree_bytes(installation)

            refused = self.run_cli(
                "open-project",
                "--installation-root",
                installation,
                "--target-root",
                target,
                "--validate-only",
            )
            self.assertEqual(3, refused.returncode, refused.stderr)
            self.assertIn("RECOVERY_REQUIRED", refused.stderr)
            self.assertIn("registry lock is missing or unsafe", refused.stderr)
            self.assertFalse(registry_lock.exists())
            self.assertEqual(installation_before, tree_bytes(installation))

    def test_packed_conversion_is_project_local_and_preserves_target(self):
        with tempfile.TemporaryDirectory(prefix="adaptive-project-packed-") as temp:
            base = Path(temp)
            target = self.packed_fixtures.fixture(base)
            installation = base / "World Builder 2"
            installation.mkdir()
            runtime = self.make_runtime(installation)
            report = base / "packed-report.json"
            discovery = self.discover(target, report)
            target_before = tree_bytes(target)
            created, summary = self.create_project(
                installation, runtime, target, report, "Converted map", 43803
            )
            self.assertEqual(0, created.returncode, created.stderr)
            self.assertEqual("target-packed", summary["origin"])
            self.assertEqual(target_before, tree_bytes(target))
            project = Path(summary["projectRoot"])
            self.assertTrue((project / "source/conversion/plan.json").is_file())
            self.assertTrue((project / "source/conversion/report.json").is_file())
            conversion = json.loads(
                (project / "source/conversion/report.json").read_text(encoding="utf-8")
            )
            self.assertFalse(conversion["blocked"])
            self.assertEqual(0, conversion["validation"]["parityDeltaCount"])
            self.assertEqual(
                conversion["terrain"]["entriesRead"],
                conversion["terrain"]["reverseMatched"],
            )
            self.assertFalse(list(project.rglob(".conversion-output")))
            self.assertFalse(list((installation / "projects").glob(".staging-*")))
            source_before = tree_bytes(project / "source")
            supervised = self.run_supervision(project)
            self.assertEqual(0, supervised.returncode, supervised.stdout + supervised.stderr)
            self.assertEqual(source_before, tree_bytes(project / "source"))
            self.assertEqual(target_before, tree_bytes(target))
            opened = self.run_cli(
                "open-project", "--installation-root", installation, "--target-root", target
            )
            self.assertEqual(0, opened.returncode, opened.stderr)
            self.assertEqual(discovery["representation"], "packed")

    def test_multiple_projects_selection_and_existing_project_preservation(self):
        with tempfile.TemporaryDirectory(prefix="adaptive-project-multiple-") as temp:
            base = Path(temp)
            installation = base / "World Builder 2"
            installation.mkdir()
            runtime = self.make_runtime(installation)
            targets = []
            summaries = []
            for index in range(2):
                target = base / f"empty-{index}"
                target.mkdir()
                report = base / f"report-{index}.json"
                self.discover(target, report)
                created, summary = self.create_project(
                    installation,
                    runtime,
                    target,
                    report,
                    f"Empty {index}",
                    43810 + index,
                )
                self.assertEqual(0, created.returncode, created.stderr)
                targets.append(target)
                summaries.append(summary)
            listed = self.run_cli(
                "list-projects", "--installation-root", installation
            )
            self.assertEqual(0, listed.returncode, listed.stderr)
            listing = json.loads(listed.stdout)
            self.assertEqual(2, len(listing["projects"]))
            self.assertEqual(summaries[1]["projectId"], listing["activeProjectId"])
            selected = self.run_cli(
                "select-project",
                "--installation-root",
                installation,
                "--project-id",
                summaries[0]["projectId"],
            )
            self.assertEqual(0, selected.returncode, selected.stderr)
            first = installation / "projects" / summaries[0]["projectId"]
            second = installation / "projects" / summaries[1]["projectId"]
            first_before = tree_bytes(first)
            second_before = tree_bytes(second)
            registry_before = (installation / "project-registry.json").read_bytes()
            active_before = (installation / "active-project.json").read_bytes()

            failed = self.run_injected(
                installation,
                runtime,
                targets[0],
                base / "report-0.json",
                "registry-published",
            )
            self.assertEqual(3, failed.returncode, failed.stderr)
            self.assertIn("MUTATION_FAILED", failed.stderr)
            self.assertEqual(first_before, tree_bytes(first))
            self.assertEqual(second_before, tree_bytes(second))
            self.assertEqual(registry_before, (installation / "project-registry.json").read_bytes())
            self.assertEqual(active_before, (installation / "active-project.json").read_bytes())
            self.assertFalse(list((installation / "projects").glob(".staging-*")))
            uuid_directories = [
                path
                for path in (installation / "projects").iterdir()
                if path.is_dir() and not path.name.startswith(".")
            ]
            self.assertEqual(2, len(uuid_directories))

    def test_all_publication_failures_leave_no_partial_project(self):
        with tempfile.TemporaryDirectory(prefix="adaptive-project-failures-") as temp:
            base = Path(temp)
            target = base / "empty-target"
            target.mkdir()
            report = base / "report.json"
            self.discover(target, report)
            for mode in (
                "stage-created",
                "source-prepared",
                "working-prepared",
                "before-project-publish",
                "project-published",
                "registry-published",
                "active-published",
            ):
                with self.subTest(mode=mode):
                    installation = base / f"install-{mode}"
                    installation.mkdir()
                    runtime = self.make_runtime(installation)
                    failed = self.run_injected(
                        installation, runtime, target, report, mode
                    )
                    self.assertEqual(3, failed.returncode, failed.stderr)
                    self.assertFalse((installation / "project-registry.json").exists())
                    self.assertFalse((installation / "active-project.json").exists())
                    projects = installation / "projects"
                    self.assertFalse(
                        [path for path in projects.iterdir() if path.is_dir()], mode
                    )
                    self.assertFalse(list(projects.glob(".staging-*")), mode)

    def test_adaptive_launch_creates_once_and_reopens_active_project(self):
        with tempfile.TemporaryDirectory(prefix="adaptive-project-launch-") as temp:
            base = Path(temp)
            installation = base / "World Builder 2"
            installation.mkdir()
            runtime = self.make_runtime(installation)
            target = base / "ordinary-parent"
            target.mkdir()
            target_before = tree_bytes(target)
            arguments = (
                "launch-adaptive",
                "--installation-root",
                installation,
                "--runtime-root",
                runtime,
                "--target-root",
                target,
                "--port",
                "43831",
            )

            cancelled = subprocess.run(
                ["java", "-cp", str(self.classes), MAIN_CLASS, *map(str, arguments)],
                cwd=ROOT,
                input="\n",
                text=True,
                capture_output=True,
            )
            self.assertEqual(0, cancelled.returncode, cancelled.stderr)
            self.assertIn("creation cancelled", cancelled.stdout)
            self.assertFalse((installation / "project-registry.json").exists())
            self.assertEqual(target_before, tree_bytes(target))

            first = self.run_cli(*arguments, "--confirm", "CREATE")
            self.assertEqual(3, first.returncode)
            self.assertIn("LOADER_INCOMPATIBLE", first.stderr)
            listing = self.run_cli(
                "list-projects", "--installation-root", installation
            )
            self.assertEqual(0, listing.returncode, listing.stderr)
            projects = json.loads(listing.stdout)["projects"]
            self.assertEqual(1, len(projects))
            first_project = projects[0]["projectId"]
            self.assertFalse(list(installation.glob(".adaptive-discovery-*")))
            self.assertEqual(target_before, tree_bytes(target))

            reopened = self.run_cli(*arguments)
            self.assertEqual(3, reopened.returncode)
            self.assertIn("LOADER_INCOMPATIBLE", reopened.stderr)
            listing = self.run_cli(
                "list-projects", "--installation-root", installation
            )
            projects = json.loads(listing.stdout)["projects"]
            self.assertEqual([first_project], [project["projectId"] for project in projects])
            self.assertEqual(target_before, tree_bytes(target))

    def test_adaptive_supervision_rejects_linked_or_shared_mutable_state(self):
        with tempfile.TemporaryDirectory(prefix="adaptive-project-runtime-paths-") as temp:
            base = Path(temp)
            installation = base / "World Builder 2"
            installation.mkdir()
            runtime = self.make_runtime(installation)
            target = base / "ordinary-parent"
            target.mkdir()
            report = base / "report.json"
            self.discover(target, report)
            created, summary = self.create_project(
                installation, runtime, target, report, "Unsafe runtime fixture", 43832
            )
            self.assertEqual(0, created.returncode, created.stderr)
            project = Path(summary["projectRoot"])
            source_before = tree_bytes(project / "source")
            target_before = tree_bytes(target)

            external = base / "must-not-be-runtime"
            external.mkdir()
            (external / "preserve.txt").write_text("preserve\n", encoding="utf-8")
            external_before = tree_bytes(external)
            server = project / "working/runtime/server"
            server.rmdir()
            server.symlink_to(external, target_is_directory=True)
            linked = self.run_supervision(project, "unsafe")
            self.assertEqual(0, linked.returncode, linked.stdout + linked.stderr)
            self.assertEqual(external_before, tree_bytes(external))
            self.assertEqual(target_before, tree_bytes(target))
            self.assertEqual(source_before, tree_bytes(project / "source"))
            self.assertFalse((project / "run/world-builder.lock").exists())

            server.unlink()
            server.mkdir()
            shared = external / "shared.log"
            shared.write_text("shared\n", encoding="utf-8")
            os.link(shared, project / "logs/shared.log")
            shared_before = tree_bytes(external)
            hard_linked = self.run_supervision(project, "unsafe")
            self.assertEqual(
                0, hard_linked.returncode, hard_linked.stdout + hard_linked.stderr
            )
            self.assertEqual(shared_before, tree_bytes(external))
            self.assertEqual(target_before, tree_bytes(target))
            self.assertEqual(source_before, tree_bytes(project / "source"))
            self.assertFalse((project / "run/world-builder.lock").exists())

    def test_empty_origin_is_deterministic_across_absolute_roots(self):
        generated = []
        with tempfile.TemporaryDirectory(prefix="adaptive-empty-portable-a-") as first:
            with tempfile.TemporaryDirectory(
                prefix="adaptive-empty-portable-b-"
            ) as second:
                for index, location in enumerate((first, second)):
                    base = Path(location)
                    installation = base / "World Builder 2"
                    installation.mkdir()
                    runtime = self.make_runtime(installation)
                    target = base / "ordinary-parent"
                    target.mkdir()
                    report = base / "report.json"
                    self.discover(target, report)
                    created, summary = self.create_project(
                        installation,
                        runtime,
                        target,
                        report,
                        "Portable empty",
                        43840 + index,
                    )
                    self.assertEqual(0, created.returncode, created.stderr)
                    project = Path(summary["projectRoot"])
                    package = project / "source/layered-baseline/package"
                    self.assert_canonical_empty_package(package)
                    generated.append(
                        {
                            "package": tree_bytes(package),
                            "descriptor": (
                                project / "source/original/empty-world-v1.json"
                            ).read_bytes(),
                            "catalog": (
                                project
                                / "source/runtime/default-definition-catalog.json"
                            ).read_bytes(),
                            "runtime": (
                                project
                                / "source/runtime/default-runtime-evidence.json"
                            ).read_bytes(),
                        }
                    )
                    for path in package.rglob("*"):
                        if path.is_file():
                            self.assertNotIn(b"Spoiled Milk", path.read_bytes())
                self.assertEqual(generated[0], generated[1])

    def test_mid_creation_drift_and_source_corruption_fail_closed(self):
        with tempfile.TemporaryDirectory(prefix="adaptive-project-drift-") as temp:
            base = Path(temp)
            target = self.fixtures.descriptor_fixture(str(base))
            report = base / "report.json"
            self.discover(target, report)
            installation = base / "World Builder 2"
            installation.mkdir()
            runtime = self.make_runtime(installation)
            failed = self.run_injected(
                installation, runtime, target, report, "drift-source-prepared"
            )
            self.assertEqual(3, failed.returncode, failed.stderr)
            self.assertIn("TARGET_DRIFT", failed.stderr)
            self.assertFalse((installation / "project-registry.json").exists())
            self.assertFalse(list((installation / "projects").glob(".staging-*")))

            refreshed = base / "refreshed-report.json"
            self.discover(target, refreshed)
            created, summary = self.create_project(
                installation, runtime, target, refreshed, "Corruption fixture", 43822
            )
            self.assertEqual(0, created.returncode, created.stderr)
            project = Path(summary["projectRoot"])
            source_file = next(
                path
                for path in (project / "source/original").rglob("*")
                if path.is_file()
            )
            source_file.write_bytes(source_file.read_bytes() + b"corrupt")
            opened = self.run_cli(
                "open-project", "--installation-root", installation, "--target-root", target
            )
            self.assertEqual(3, opened.returncode)
            self.assertIn("SOURCE_CORRUPT", opened.stderr)


if __name__ == "__main__":
    unittest.main()
