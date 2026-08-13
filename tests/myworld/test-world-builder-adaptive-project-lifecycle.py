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
import zipfile
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
SOURCE_ROOT = ROOT / "tools/world-builder/src"
MAIN_CLASS = "com.openrsc.worldbuilder.WorldBuilderCli"
DISCOVERY_TEST = ROOT / "tests/myworld/test-world-builder-adaptive-discovery.py"
PACKED_CONVERSION_TEST = ROOT / "tests/myworld/test-world-builder-packed-conversion.py"
RUNTIME_ALLOWLIST = ROOT / "release/world-builder-v2/RUNTIME-ASSET-ALLOWLIST.txt"
RUNTIME_ALLOWLIST_RESOURCE = "com/openrsc/worldbuilder/runtime-asset-allowlist-v1.txt"
CANONICAL_VOID_TILE = bytes((0, 1, 8, 0, 0, 0, 0, 0, 0, 0))
CANONICAL_VOID_SECTOR = CANONICAL_VOID_TILE * (48 * 48)
VISIBLE_FLOOR_TILE = bytes((0, 0, 0, 0, 0, 0, 0, 0, 0, 0))
STANDALONE_INITIAL_LOCATION = {"level": 0, "x": 120, "y": 648}


def standalone_seed_sector() -> bytes:
    result = bytearray(CANONICAL_VOID_SECTOR)
    center_x = STANDALONE_INITIAL_LOCATION["x"] % 48
    center_y = STANDALONE_INITIAL_LOCATION["y"] % 48
    for local_x in range(center_x - 1, center_x + 2):
        for local_y in range(center_y - 1, center_y + 2):
            offset = (local_x * 48 + local_y) * 10
            result[offset : offset + 10] = VISIBLE_FLOOR_TILE
    return bytes(result)


STANDALONE_SEED_SECTOR = standalone_seed_sector()
REQUIRED_LANGUAGE_BUNDLES = (
    "AuthenticMessages_en_UK.properties",
    "AuthenticMessages_en_UK_female.properties",
    "AuthenticMessages_en_UK_female_no_misgender.properties",
    "AuthenticMessages_en_UK_gender_neutral.properties",
    "AuthenticMessages_en_UK_male.properties",
    "CustomMessages_en_UK.properties",
    "CustomMessages_en_UK_female.properties",
    "CustomMessages_en_UK_gender_neutral.properties",
    "CustomMessages_en_UK_male.properties",
)
EMPTY_LANGUAGE_BUNDLES = {
    "CustomMessages_en_UK_female.properties",
    "CustomMessages_en_UK_gender_neutral.properties",
    "CustomMessages_en_UK_male.properties",
}
REQUIRED_DATABASE_PATCHES = (
    "2021_05_11_add_db_patches.sql",
    "2023_02_01_former_names.sql",
    "2026_05_14_add_summoning_skill.sql",
    "2026_08_03_add_blessing_skill.sql",
)


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


def runtime_allowlist_records() -> tuple[tuple[str, str, str], ...]:
    records = []
    for raw in RUNTIME_ALLOWLIST.read_text(encoding="utf-8").splitlines():
        if not raw or raw.startswith("#"):
            continue
        fields = tuple(raw.split("\t"))
        if len(fields) != 3:
            raise AssertionError(f"Malformed fixture runtime allowlist line: {raw!r}")
        records.append(fields)
    return tuple(records)


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
        allowlist_resource = cls.classes / RUNTIME_ALLOWLIST_RESOURCE
        allowlist_resource.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(RUNTIME_ALLOWLIST, allowlist_resource)
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

    private static boolean contains(List<String> command, String value) {
        return command.contains(value);
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
        String manifest = new String(Files.readAllBytes(project.resolve("project.json")),
            StandardCharsets.UTF_8);
        if (!(args.length > 2 && "unsafe".equals(args[2]))) {
            String expectedOrigin = manifest.contains("\"origin\": \"target-layered\"")
                ? "target-layered" : manifest.contains("\"origin\": \"target-packed\"")
                    ? "target-packed" : "standalone-empty";
            List<String> productionServer =
                WorldBuilderProcessSupervisor.defaultAdaptiveServerCommand(project);
            List<String> productionClient =
                WorldBuilderProcessSupervisor.defaultAdaptiveClientCommand(project);
            require(contains(productionServer,
                "-Dopenrsc.worldBuilderWorkspaceRoot=" + project), "server project root");
            require(contains(productionServer,
                "-Dopenrsc.worldBuilderProjectOrigin=" + expectedOrigin), "server origin");
            require(contains(productionServer,
                "-Dopenrsc.worldBuilderAdaptiveMode=true"), "server adaptive mode");
            require(contains(productionServer,
                "-Dopenrsc.layeredNativeWorldRuntimeProfile=adaptive-world-builder"),
                "server runtime profile");
            require(contains(productionServer,
                "-Dopenrsc.layeredNativeTerrainPackagePath="
                    + project.resolve("working/layered-world/package")), "server package");
            require(contains(productionServer,
                "-Dopenrsc.worldBuilderControlDirectory="
                    + project.resolve("run/world-builder")), "server control");
            if ("standalone-empty".equals(expectedOrigin)) {
                require(contains(productionServer,
                    "-Dopenrsc.worldBuilderInitialWorldSpace=global"),
                    "standalone initial world space");
                require(contains(productionServer,
                    "-Dopenrsc.worldBuilderInitialLevel=0"),
                    "standalone initial level");
                require(contains(productionServer,
                    "-Dopenrsc.worldBuilderInitialX=120"),
                    "standalone initial x");
                require(contains(productionServer,
                    "-Dopenrsc.worldBuilderInitialY=648"),
                    "standalone initial y");
            }
            require(contains(productionClient,
                "-Dopenrsc.worldBuilderRuntimeBindingFile="
                    + project.resolve("run/world-builder/runtime-binding.properties")),
                "client binding");
            require(contains(productionClient,
                "-Dopenrsc.worldBuilderDefinitionEvidenceFile="
                    + project.resolve("working/runtime/client/evidence/adaptive-definitions.json")),
                "client definitions");
            require(contains(productionClient,
                "-Dopenrsc.worldBuilderAssetEvidenceFile="
                    + project.resolve("working/runtime/client/evidence/adaptive-assets.sha256")),
                "client assets");
            require(contains(productionClient,
                "-Dspoiledmilk.clientLog="
                    + project.resolve("logs/client-runtime.log")),
                "client runtime log confinement");
        }

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
        if (args.length > 2 && "failures".equals(args[2])) {
            byte[] manifestBefore = Files.readAllBytes(project.resolve("project.json"));
            boolean timeout = false;
            try {
                supervisor.superviseAdaptiveWithCommands(
                    project, command(classes, "NeverReadyServer", project, port),
                    client, 350L);
            } catch (WorldBuilderDiscoveryException expected) {
                timeout = expected.getMessage().contains("did not become ready");
            }
            require(timeout, "adaptive readiness timeout");
            requireCleanFailure(project, manifestBefore, "readiness timeout");

            boolean early = false;
            try {
                supervisor.superviseAdaptiveWithCommands(
                    project, command(classes, "EarlyServer", project, port),
                    client, 5000L);
            } catch (WorldBuilderDiscoveryException expected) {
                early = expected.getMessage().contains("exited before it became ready");
            }
            require(early, "adaptive server early exit");
            requireCleanFailure(project, manifestBefore, "server early exit");

            int clientFailure = supervisor.superviseAdaptiveWithCommands(
                project, server, command(classes, "FailingClient", project, port),
                5000L);
            require(clientFailure == 7, "adaptive client failure exit");
            requireCleanFailure(project, manifestBefore, "client failure");
            String receipt = new String(Files.readAllBytes(
                project.resolve("run/last-run.json")), StandardCharsets.UTF_8);
            require(receipt.contains("\"clientExit\": 7"), "client failure receipt");
            System.out.println("adaptive-supervision-failures-ok");
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
            "run/world-builder/ready")), "ready cleanup");
        require(Files.isRegularFile(project.resolve("run/last-run.json")),
            "bounded run receipt");
        System.out.println("adaptive-supervision-ok");
    }

    private static void requireCleanFailure(Path project, byte[] manifestBefore,
        String label) throws Exception {
        require(!Files.exists(project.resolve("run/server.pid")), label + " server PID");
        require(!Files.exists(project.resolve("run/client.pid")), label + " client PID");
        require(!Files.exists(project.resolve("run/world-builder/ready")),
            label + " ready cleanup");
        require(Files.isRegularFile(project.resolve("run/last-run.json")),
            label + " run receipt");
        require(Arrays.equals(manifestBefore,
            Files.readAllBytes(project.resolve("project.json"))),
            label + " must not save project metadata");
    }

    public static final class FakeServer {
        public static void main(String[] args) throws Exception {
            Path project = Paths.get(args[0]);
            int port = Integer.parseInt(args[1]);
            Path server = project.resolve("working/runtime/server");
            Path control = project.resolve("run/world-builder");
            Path credential = server.resolve("inc/sqlite/world-builder.credential");
            Files.createDirectories(control);
            Files.createDirectories(credential.getParent());
            Files.write(credential,
                "Abcdefghijk23456789Z".getBytes(StandardCharsets.US_ASCII));
            Files.write(server.resolve("ipbans.txt"), new byte[0]);
            Files.write(server.resolve("client.pem"),
                "fixture client key\n".getBytes(StandardCharsets.US_ASCII));
            Files.write(server.resolve("server.pem"),
                "fixture server key\n".getBytes(StandardCharsets.US_ASCII));
            Files.write(server.resolve("create_db.log"),
                "fixture database setup log\n".getBytes(StandardCharsets.US_ASCII));
            Files.write(server.resolve("create_db_error.log"),
                "fixture database error log\n".getBytes(StandardCharsets.US_ASCII));
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

    public static final class NeverReadyServer {
        public static void main(String[] args) throws Exception {
            Path project = Paths.get(args[0]);
            Path control = project.resolve("run/world-builder");
            Path credential = project.resolve(
                "working/runtime/server/inc/sqlite/world-builder.credential");
            Files.createDirectories(control);
            Files.createDirectories(credential.getParent());
            Files.write(credential,
                "Abcdefghijk23456789Z".getBytes(StandardCharsets.US_ASCII));
            while (!Files.exists(control.resolve("shutdown.request"))) {
                Thread.sleep(25L);
            }
        }
    }

    public static final class EarlyServer {
        public static void main(String[] args) {
            System.exit(9);
        }
    }

    public static final class FailingClient {
        public static void main(String[] args) {
            System.exit(7);
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
        server = runtime / "server"
        client = runtime / "Client_Base"
        (server / "inc/sqlite").mkdir(parents=True)
        (server / "conf/world-builder").mkdir(parents=True)
        client.mkdir(parents=True)
        (server / "world-builder.conf").write_text(
            "server_name: Fixture World Builder\n"
            "server_bind_address: 0.0.0.0\n"
            "custom_landscape: true\n"
            "unbound_fixture_setting: must-not-enter-a-project\n",
            encoding="utf-8",
        )
        (server / "inc/sqlite/world_builder_seed.db").write_bytes(
            b"fixture-project-local-database-seed\n"
        )
        capability = {
            "schemaVersion": 1,
            "manifestType": "adaptive-world-builder-runtime-capability",
            "capabilityId": "adaptive-world-builder-runtime-capability-v1",
            "profileId": "adaptive-world-builder",
            "serverBuildId": "core-framework-adaptive-builder-server-v1",
            "clientBuildId": "core-framework-adaptive-builder-client-v1",
            "loaderId": "generic-signed-layered-loader-v1",
            "authoringId": "generic-signed-layered-authoring-v1",
            "definitionContractId": "world-builder-definition-catalog-binding-v1",
            "assetContractId": "world-builder-client-asset-binding-v1",
            "protocolId": "world-builder-native-layered-protocol-v1",
            "effectiveCompositionId": "world-builder-effective-static-composition-v1",
            "mapFormatId": "signed-layered-v1",
            "packageSchemaId": "layered-world-package-v1",
            "coordinateModel": "signed-layered-v1",
            "encodingVersions": [1, 3],
            "authoring": {
                "editExistingLevels": True,
                "createLevels": True,
                "placementFamilies": ["boundary", "ground-item", "npc", "scenery"],
            },
            "activation": {
                "worldBuilderMode": True,
                "adaptiveMode": True,
                "runtimeProfile": "adaptive-world-builder",
                "builderOnly": True,
                "loopbackOnly": True,
            },
            "canonicalVoidTile": [0, 1, 8, 0, 0, 0, 0, 0, 0, 0],
        }
        write_json(
            server / "conf/world-builder/adaptive-runtime-capability-v1.json",
            capability,
        )
        for name in REQUIRED_LANGUAGE_BUNDLES:
            path = server / "conf/server/languages" / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(
                b"" if name in EMPTY_LANGUAGE_BUNDLES else b"fixture bundle\n"
            )
        for name in REQUIRED_DATABASE_PATCHES:
            path = server / "database/sqlite/patches" / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(b"-- fixture runtime migration\n")
        for _, destination, role in runtime_allowlist_records():
            path = runtime / destination
            if path.exists():
                continue
            path.parent.mkdir(parents=True, exist_ok=True)
            if path.name in EMPTY_LANGUAGE_BUNDLES:
                path.write_bytes(b"")
            elif path.suffix == ".jar":
                with zipfile.ZipFile(path, "w") as archive:
                    entry = zipfile.ZipInfo(
                        "fixture/Runtime.class", (2024, 1, 2, 3, 4, 6)
                    )
                    archive.writestr(entry, b"fixture")
            else:
                path.write_bytes(("fixture " + role + "\n").encode("utf-8"))
        for jar in (
            server / "core.jar",
            server / "plugins.jar",
            client / "Open_RSC_Client.jar",
        ):
            with zipfile.ZipFile(jar, "w") as archive:
                entry = zipfile.ZipInfo(
                    "META-INF/MANIFEST.MF", (2024, 1, 2, 3, 4, 6)
                )
                archive.writestr(entry, "Manifest-Version: 1.0\n\n")
        return runtime

    @classmethod
    def make_executable_runtime(cls, root: Path) -> Path:
        runtime = cls.make_runtime(root)
        (runtime / "server/ipbans.txt").write_text(
            "must-not-enter-a-project\n", encoding="utf-8"
        )
        source = Path(cls.compile_temp.name) / "fake-native-runtime"
        classes = source / "classes"
        classes.mkdir(parents=True, exist_ok=True)
        server_source = source / "src/com/openrsc/server/Server.java"
        client_source = source / "src/fixture/FakeAdaptiveClient.java"
        server_source.parent.mkdir(parents=True, exist_ok=True)
        client_source.parent.mkdir(parents=True, exist_ok=True)
        server_source.write_text(
            r'''
package com.openrsc.server;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public final class Server {
    public static void main(String[] args) throws Exception {
        Path project = Paths.get(System.getProperty("openrsc.worldBuilderWorkspaceRoot"));
        Path control = Paths.get(System.getProperty("openrsc.worldBuilderControlDirectory"));
        Path credential = Paths.get(System.getProperty("openrsc.worldBuilderCredentialFile"));
        int port = Integer.parseInt(System.getProperty("openrsc.worldBuilderPort"));
        Files.createDirectories(control);
        Files.createDirectories(credential.getParent());
        Files.write(credential, "Abcdefghijk23456789Z".getBytes(StandardCharsets.US_ASCII));
        Path serverLogs = project.resolve("working/runtime/server/logs");
        Files.createDirectories(serverLogs);
        Files.write(serverLogs.resolve("default.log"),
            "project-local server log\n".getBytes(StandardCharsets.UTF_8));
        require("true".equals(System.getProperty("openrsc.worldBuilderAdaptiveMode")),
            "adaptive activation");
        require("adaptive-world-builder".equals(System.getProperty(
            "openrsc.layeredNativeWorldRuntimeProfile")), "runtime profile");
        require(project.resolve("working/layered-world/package").toString().equals(
            System.getProperty("openrsc.layeredNativeTerrainPackagePath")),
            "working package binding");
        require(Files.isRegularFile(Paths.get(System.getProperty(
            "openrsc.worldBuilderDefinitionEvidencePath"))), "server definitions");
        require(Files.isRegularFile(Paths.get(System.getProperty(
            "openrsc.worldBuilderAssetEvidencePath"))), "server assets");
        Files.write(project.resolve("working/runtime/server/ipbans.txt"), new byte[0]);
        Files.write(project.resolve("working/runtime/server/client.pem"),
            "fixture client key\n".getBytes(StandardCharsets.US_ASCII));
        Files.write(project.resolve("working/runtime/server/server.pem"),
            "fixture server key\n".getBytes(StandardCharsets.US_ASCII));
        Files.write(project.resolve("working/runtime/server/create_db.log"),
            "fixture database setup log\n".getBytes(StandardCharsets.US_ASCII));
        Files.write(project.resolve("working/runtime/server/create_db_error.log"),
            "fixture database error log\n".getBytes(StandardCharsets.US_ASCII));
        Files.write(control.resolve("runtime-binding.properties"),
            "fixture-binding=true\n".getBytes(StandardCharsets.US_ASCII));
        try (ServerSocket listener = new ServerSocket(
                port, 1, InetAddress.getByName("127.0.0.1"))) {
            listener.setSoTimeout(100);
            Files.write(control.resolve("ready"), "ready\n".getBytes(StandardCharsets.US_ASCII));
            while (!Files.exists(control.resolve("shutdown.request"))) {
                try (Socket ignored = listener.accept()) {
                    // The supervisor readiness probe connects and closes.
                } catch (java.net.SocketTimeoutException expected) {
                    // Poll the project-local shutdown request again.
                }
            }
        }
    }

    private static void require(boolean condition, String label) {
        if (!condition) throw new IllegalStateException("missing " + label);
    }
}
'''.strip()
            + "\n",
            encoding="utf-8",
        )
        client_source.write_text(
            r'''
package fixture;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;

public final class FakeAdaptiveClient {
    public static void main(String[] args) throws Exception {
        Path project = Paths.get(System.getProperty("openrsc.worldBuilderWorkspaceRoot"));
        Path clientLog = Paths.get(System.getProperty("spoiledmilk.clientLog"));
        require(clientLog.equals(project.resolve("logs/client-runtime.log")),
            "client runtime log confinement");
        Files.write(clientLog,
            "project-local client log\n".getBytes(StandardCharsets.UTF_8));
        require("true".equals(System.getProperty("openrsc.worldBuilderAdaptiveMode")),
            "adaptive activation");
        require(Files.isRegularFile(Paths.get(System.getProperty(
            "openrsc.worldBuilderRuntimeBindingFile"))), "runtime binding");
        require(Files.isRegularFile(Paths.get(System.getProperty(
            "openrsc.worldBuilderDefinitionEvidenceFile"))), "client definitions");
        require(Files.isRegularFile(Paths.get(System.getProperty(
            "openrsc.worldBuilderAssetEvidenceFile"))), "client assets");
        Path packageRoot = project.resolve("working/layered-world/package");
        Path terrain = packageRoot.resolve("terrain/global/lp0/xp2-yp13.raw");
        byte[] payload = Files.readAllBytes(terrain);
        for (int localX = 22; localX <= 26; localX++) {
            for (int localY = 22; localY <= 26; localY++) {
                int offset = (localX * 48 + localY) * 10;
                boolean seed = localX >= 23 && localX <= 25
                    && localY >= 23 && localY <= 25;
                require((payload[offset + 1] & 0xff) == (seed ? 0 : 1),
                    "standalone initial floor color");
                require((payload[offset + 2] & 0xff) == (seed ? 0 : 8),
                    "standalone initial floor overlay");
            }
        }
        payload[1] ^= 1;
        Files.write(terrain, payload);
        Path manifestPath = packageRoot.resolve("manifest.json");
        String manifest = new String(Files.readAllBytes(manifestPath),
            StandardCharsets.UTF_8);
        String terrainDeclaration = "\"path\": \"terrain/global/lp0/xp2-yp13.raw\"";
        int declaration = manifest.indexOf(terrainDeclaration);
        int hashStart = manifest.indexOf("\"sha256\": \"", declaration)
            + "\"sha256\": \"".length();
        require(declaration >= 0 && hashStart >= "\"sha256\": \"".length(),
            "terrain declaration");
        String updated = manifest.substring(0, hashStart) + sha256(payload)
            + manifest.substring(hashStart + 64);
        Files.write(manifestPath, updated.getBytes(StandardCharsets.UTF_8));
        Files.write(project.resolve("working/runtime/client/clientSettings.conf"),
            "generated=true\n".getBytes(StandardCharsets.UTF_8));
        Thread.sleep(250L);
    }

    private static String sha256(byte[] value) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(value);
        StringBuilder text = new StringBuilder(64);
        for (byte item : digest) text.append(String.format("%02x", item & 0xff));
        return text.toString();
    }

    private static void require(boolean condition, String label) {
        if (!condition) throw new IllegalStateException("missing " + label);
    }
}
'''.strip()
            + "\n",
            encoding="utf-8",
        )
        subprocess.run(
            [
                "javac", "-source", "8", "-target", "8", "-d", str(classes),
                str(server_source), str(client_source),
            ],
            check=True,
            cwd=ROOT,
            capture_output=True,
            text=True,
        )
        subprocess.run(
            [
                "jar", "cf", str(runtime / "server/core.jar"),
                "-C", str(classes), "com/openrsc/server/Server.class",
            ],
            check=True,
            cwd=ROOT,
            capture_output=True,
            text=True,
        )
        manifest = source / "client-manifest.mf"
        manifest.write_text(
            "Manifest-Version: 1.0\nMain-Class: fixture.FakeAdaptiveClient\n\n",
            encoding="utf-8",
        )
        subprocess.run(
            [
                "jar", "cfm", str(runtime / "Client_Base/Open_RSC_Client.jar"),
                str(manifest), "-C", str(classes), "fixture/FakeAdaptiveClient.class",
            ],
            check=True,
            cwd=ROOT,
            capture_output=True,
            text=True,
        )
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
                "path": "terrain/global/lp0/xp2-yp13.raw",
                "sectorX": 2,
                "sectorY": 13,
                "worldSpace": "global",
            },
            {key: sector[key] for key in (
                "encoding", "level", "path", "sectorX", "sectorY", "worldSpace"
            )},
        )
        terrain = package / sector["path"]
        payload = terrain.read_bytes()
        self.assertEqual(STANDALONE_SEED_SECTOR, payload)
        self.assertEqual(sha256(terrain), sector["sha256"])
        minimum_x = sector["sectorX"] * 48
        minimum_y = sector["sectorY"] * 48
        self.assertGreaterEqual(STANDALONE_INITIAL_LOCATION["x"] - minimum_x, 23)
        self.assertGreaterEqual(
            minimum_x + 47 - STANDALONE_INITIAL_LOCATION["x"], 23
        )
        self.assertGreaterEqual(STANDALONE_INITIAL_LOCATION["y"] - minimum_y, 23)
        self.assertGreaterEqual(
            minimum_y + 47 - STANDALONE_INITIAL_LOCATION["y"], 23
        )
        tiles = [payload[offset : offset + 10] for offset in range(0, len(payload), 10)]
        self.assertEqual(9, tiles.count(VISIBLE_FLOOR_TILE))
        self.assertEqual(48 * 48 - 9, tiles.count(CANONICAL_VOID_TILE))

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
            runtime = self.make_executable_runtime(installation)
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
            self.assertEqual(
                "must-not-enter-a-project\n",
                (runtime / "server/ipbans.txt").read_text(encoding="utf-8"),
            )
            self.assertFalse(
                (project / "working/runtime/server/ipbans.txt").exists()
            )
            isolated_config = (
                project / "working/runtime/server/world-builder.conf"
            ).read_text(encoding="utf-8")
            self.assertIn("server_bind_address: 127.0.0.1\n", isolated_config)
            self.assertIn("custom_landscape: false\n", isolated_config)
            self.assertIn("want_sync_scene_baseline: true\n", isolated_config)
            self.assertIn("want_discord_bot: false\n", isolated_config)
            self.assertNotIn("unbound_fixture_setting", isolated_config)

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
                STANDALONE_INITIAL_LOCATION, descriptor["initialLocation"]
            )
            runtime_evidence = json.loads(
                (
                    project / "source/runtime/default-runtime-evidence.json"
                ).read_text(encoding="utf-8")
            )
            self.assertEqual(
                {
                    "createFromVoid": True,
                    "initialLayer": STANDALONE_INITIAL_LOCATION["level"],
                    "initialX": STANDALONE_INITIAL_LOCATION["x"],
                    "initialY": STANDALONE_INITIAL_LOCATION["y"],
                },
                runtime_evidence["authoring"],
            )
            runtime_metadata = json.loads(
                (project / "working/runtime/runtime.json").read_text(encoding="utf-8")
            )
            self.assertEqual(
                STANDALONE_INITIAL_LOCATION["level"],
                runtime_metadata["initialLayer"],
            )
            self.assertEqual(
                STANDALONE_INITIAL_LOCATION["x"], runtime_metadata["initialX"]
            )
            self.assertEqual(
                STANDALONE_INITIAL_LOCATION["y"], runtime_metadata["initialY"]
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
            working_before_native = json.loads(
                (project / "project.json").read_text(encoding="utf-8")
            )["fingerprints"]["workingSha256"]
            native = self.run_cli("run-adaptive-project", "--project", project)
            self.assertEqual(0, native.returncode, native.stderr)
            working_after_native = json.loads(
                (project / "project.json").read_text(encoding="utf-8")
            )["fingerprints"]["workingSha256"]
            self.assertNotEqual(working_before_native, working_after_native)
            self.assertTrue((project / "working/runtime/server/ipbans.txt").is_file())
            for name in ("client.pem", "server.pem"):
                source_key = runtime / "server" / name
                project_key = project / "working/runtime/server" / name
                self.assertFalse(source_key.exists(), source_key)
                self.assertTrue(project_key.is_file(), project_key)
                self.assertFalse(project_key.is_symlink(), project_key)
                self.assertEqual(1, project_key.stat().st_nlink)
                self.assertGreater(project_key.stat().st_size, 0)
            for name in ("create_db.log", "create_db_error.log"):
                self.assertFalse((project / "working/runtime/server" / name).exists())
                self.assertTrue(
                    (project / "working/runtime/server/logs" / name).is_file()
                )
            self.assertTrue(
                (project / "working/runtime/client/clientSettings.conf").is_file()
            )
            self.assertTrue(
                (project / "working/runtime/server/logs/default.log").is_file()
            )
            self.assertTrue((project / "logs/client-runtime.log").is_file())
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

    def test_native_runtime_bundles_preserve_bounded_empty_fallbacks(self):
        with tempfile.TemporaryDirectory(prefix="adaptive-native-runtime-assets-") as temp:
            base = Path(temp)
            installation = base / "World Builder 2"
            installation.mkdir()
            runtime = self.make_runtime(installation)
            target = base / "ordinary-parent"
            target.mkdir()
            report = base / "report.json"
            self.discover(target, report)
            target_before = tree_bytes(target)

            created, summary = self.create_project(
                installation,
                runtime,
                target,
                report,
                "Native runtime assets",
                43813,
            )
            self.assertEqual(0, created.returncode, created.stderr)
            project = Path(summary["projectRoot"])
            inventory = (
                project / "working/runtime/runtime-assets.sha256"
            ).read_text(encoding="utf-8")
            empty_digest = hashlib.sha256(b"").hexdigest()
            for bundle in EMPTY_LANGUAGE_BUNDLES:
                relative = "server/conf/server/languages/" + bundle
                copied = project / "working/runtime" / relative
                self.assertTrue(copied.is_file(), copied)
                self.assertEqual(b"", copied.read_bytes())
                self.assertIn(f"{empty_digest}\t0\t{relative}\n", inventory)
            for patch in REQUIRED_DATABASE_PATCHES:
                self.assertTrue(
                    (
                        project
                        / "working/runtime/server/database/sqlite/patches"
                        / patch
                    ).is_file()
                )
            definition_destinations = {
                destination
                for _, destination, role in runtime_allowlist_records()
                if role == "default-definition-catalog"
            }
            self.assertTrue(definition_destinations)
            for relative in definition_destinations:
                self.assertEqual(
                    (runtime / relative).read_bytes(),
                    (project / "working/runtime" / relative).read_bytes(),
                    relative,
                )
            self.assertFalse(
                (project / "working/runtime/server/conf/server/defs/locs").exists()
            )

            validated = self.run_cli(
                "open-project",
                "--installation-root",
                installation,
                "--validate-only",
            )
            self.assertEqual(0, validated.returncode, validated.stderr)

            changed = (
                project
                / "working/runtime/server/conf/server/languages/"
                "CustomMessages_en_UK_female.properties"
            )
            changed.write_bytes(b"changed\n")
            refused = self.run_cli(
                "open-project",
                "--installation-root",
                installation,
                "--validate-only",
            )
            self.assertEqual(3, refused.returncode, refused.stderr)
            self.assertIn("SOURCE_CORRUPT", refused.stderr)
            changed.write_bytes(b"")

            missing = (
                project
                / "working/runtime/server/database/sqlite/patches/"
                "2026_08_03_add_blessing_skill.sql"
            )
            missing.unlink()
            refused = self.run_cli(
                "open-project",
                "--installation-root",
                installation,
                "--validate-only",
            )
            self.assertEqual(3, refused.returncode, refused.stderr)
            self.assertIn("UNSAFE_PATH", refused.stderr)
            self.assertIn("2026_08_03_add_blessing_skill.sql", refused.stderr)
            self.assertEqual(target_before, tree_bytes(target))

            incomplete_installation = base / "Incomplete World Builder 2"
            incomplete_installation.mkdir()
            incomplete_runtime = self.make_runtime(incomplete_installation)
            (
                incomplete_runtime
                / "server/conf/server/languages/CustomMessages_en_UK.properties"
            ).unlink()
            refused, _ = self.create_project(
                incomplete_installation,
                incomplete_runtime,
                target,
                report,
                "Incomplete native runtime",
                43814,
            )
            self.assertEqual(3, refused.returncode, refused.stderr)
            self.assertIn("LOADER_INCOMPATIBLE", refused.stderr)
            self.assertIn("CustomMessages_en_UK.properties", refused.stderr)
            self.assertFalse(
                (incomplete_installation / "project-registry.json").exists()
            )
            self.assertEqual(target_before, tree_bytes(target))

            keyed_installation = base / "Keyed World Builder 2"
            keyed_installation.mkdir()
            keyed_runtime = self.make_runtime(keyed_installation)
            (keyed_runtime / "server/client.pem").write_text(
                "shared key material must not be imported\n", encoding="utf-8"
            )
            refused, _ = self.create_project(
                keyed_installation,
                keyed_runtime,
                target,
                report,
                "Shared-key runtime",
                43815,
            )
            self.assertEqual(3, refused.returncode, refused.stderr)
            self.assertIn("LOADER_INCOMPATIBLE", refused.stderr)
            self.assertIn("project-only generated", refused.stderr)
            self.assertFalse((keyed_installation / "project-registry.json").exists())
            self.assertEqual(target_before, tree_bytes(target))

            located_installation = base / "Located World Builder 2"
            located_installation.mkdir()
            located_runtime = self.make_runtime(located_installation)
            located_definition = (
                located_runtime
                / "server/conf/server/defs/locs/private-target-locations.xml"
            )
            located_definition.parent.mkdir(parents=True)
            located_definition.write_text("must not enter a project\n", encoding="utf-8")
            refused, _ = self.create_project(
                located_installation,
                located_runtime,
                target,
                report,
                "Located definition runtime",
                43817,
            )
            self.assertEqual(3, refused.returncode, refused.stderr)
            self.assertIn("LOADER_INCOMPATIBLE", refused.stderr)
            self.assertIn("exact allowlist", refused.stderr)
            self.assertFalse((located_installation / "project-registry.json").exists())
            self.assertEqual(target_before, tree_bytes(target))

    def test_project_local_pem_links_refuse_reopen(self):
        with tempfile.TemporaryDirectory(prefix="adaptive-project-pem-safety-") as temp:
            base = Path(temp)
            installation = base / "World Builder 2"
            installation.mkdir()
            runtime = self.make_runtime(installation)
            target = base / "ordinary-parent"
            target.mkdir()
            report = base / "report.json"
            self.discover(target, report)
            target_before = tree_bytes(target)
            created, summary = self.create_project(
                installation, runtime, target, report, "PEM safety", 43816
            )
            self.assertEqual(0, created.returncode, created.stderr)
            project = Path(summary["projectRoot"])
            server = project / "working/runtime/server"
            for name in ("client.pem", "server.pem"):
                (server / name).write_text("project-local key\n", encoding="utf-8")

            accepted = self.run_cli(
                "open-project",
                "--installation-root",
                installation,
                "--validate-only",
            )
            self.assertEqual(0, accepted.returncode, accepted.stderr)

            external = base / "external-key.pem"
            external.write_text("external key must remain unchanged\n", encoding="utf-8")
            before = external.read_bytes()
            client_key = server / "client.pem"
            client_key.unlink()
            client_key.symlink_to(external)
            refused = self.run_cli(
                "open-project",
                "--installation-root",
                installation,
                "--validate-only",
            )
            self.assertEqual(3, refused.returncode, refused.stderr)
            self.assertIn("SOURCE_CORRUPT", refused.stderr)
            self.assertEqual(before, external.read_bytes())

            client_key.unlink()
            os.link(external, client_key)
            refused = self.run_cli(
                "open-project",
                "--installation-root",
                installation,
                "--validate-only",
            )
            self.assertEqual(3, refused.returncode, refused.stderr)
            self.assertIn("SOURCE_CORRUPT", refused.stderr)
            self.assertEqual(before, external.read_bytes())
            self.assertEqual(target_before, tree_bytes(target))

    def test_layered_adoption_save_and_portable_detached_reopen(self):
        with tempfile.TemporaryDirectory(prefix="adaptive-project-layered-") as temp:
            base = Path(temp)
            target = self.fixtures.descriptor_fixture(
                str(base), world_space="global"
            )
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
            runtime = self.make_executable_runtime(installation)
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
            self.assertEqual(0, first.returncode, first.stderr)
            listing = self.run_cli(
                "list-projects", "--installation-root", installation
            )
            self.assertEqual(0, listing.returncode, listing.stderr)
            projects = json.loads(listing.stdout)["projects"]
            self.assertEqual(1, len(projects))
            first_project = projects[0]["projectId"]
            project = installation / "projects" / first_project
            generated_keys = {
                "client.pem": b"fixture client key\n",
                "server.pem": b"fixture server key\n",
            }
            for name, expected in generated_keys.items():
                self.assertEqual(
                    expected,
                    (project / "working/runtime/server" / name).read_bytes(),
                )
                self.assertFalse((runtime / "server" / name).exists())
            self.assertFalse(list(installation.glob(".adaptive-discovery-*")))
            self.assertEqual(target_before, tree_bytes(target))

            reopened = self.run_cli(*arguments)
            self.assertEqual(0, reopened.returncode, reopened.stderr)
            for name, expected in generated_keys.items():
                self.assertEqual(
                    expected,
                    (project / "working/runtime/server" / name).read_bytes(),
                )
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
            preserved_server = base / "preserved-project-server"
            shutil.move(server, preserved_server)
            server.symlink_to(external, target_is_directory=True)
            linked = self.run_supervision(project, "unsafe")
            self.assertEqual(0, linked.returncode, linked.stdout + linked.stderr)
            self.assertEqual(external_before, tree_bytes(external))
            self.assertEqual(target_before, tree_bytes(target))
            self.assertEqual(source_before, tree_bytes(project / "source"))
            self.assertFalse((project / "run/world-builder.lock").exists())

            server.unlink()
            shutil.move(preserved_server, server)
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

            (project / "logs/shared.log").unlink()
            linked_key = server / "client.pem"
            linked_key.symlink_to(external / "preserve.txt")
            refused = self.run_cli(
                "open-project",
                "--installation-root",
                installation,
                "--target-root",
                target,
                "--validate-only",
            )
            self.assertEqual(3, refused.returncode, refused.stderr)
            self.assertIn("SOURCE_CORRUPT", refused.stderr)
            self.assertIn("working/runtime/server/client.pem", refused.stderr)
            self.assertEqual(shared_before, tree_bytes(external))
            self.assertEqual(target_before, tree_bytes(target))
            self.assertEqual(source_before, tree_bytes(project / "source"))

    def test_adaptive_supervision_failure_cleanup_never_saves_or_touches_target(self):
        with tempfile.TemporaryDirectory(prefix="adaptive-project-runtime-failures-") as temp:
            base = Path(temp)
            installation = base / "World Builder 2"
            installation.mkdir()
            runtime = self.make_runtime(installation)
            target = base / "ordinary-parent"
            target.mkdir()
            report = base / "report.json"
            self.discover(target, report)
            target_before = tree_bytes(target)
            created, summary = self.create_project(
                installation, runtime, target, report, "Failure cleanup", 43833
            )
            self.assertEqual(0, created.returncode, created.stderr)
            project = Path(summary["projectRoot"])
            source_before = tree_bytes(project / "source")
            baseline_before = tree_bytes(
                project / "source/layered-baseline/package"
            )
            failed = self.run_supervision(project, "failures")
            self.assertEqual(0, failed.returncode, failed.stdout + failed.stderr)
            self.assertEqual(
                "adaptive-supervision-failures-ok\n", failed.stdout
            )
            reopened = self.run_cli(
                "open-project",
                "--installation-root",
                installation,
                "--target-root",
                target,
            )
            self.assertEqual(0, reopened.returncode, reopened.stderr)
            self.assertEqual(target_before, tree_bytes(target))
            self.assertEqual(source_before, tree_bytes(project / "source"))
            self.assertEqual(
                baseline_before,
                tree_bytes(project / "source/layered-baseline/package"),
            )

    def test_runtime_capability_mismatches_fail_before_project_publication(self):
        mutations = {
            "definition-contract": lambda value: value.__setitem__(
                "definitionContractId", "wrong-definition-contract-v1"
            ),
            "placement-order": lambda value: value["authoring"].__setitem__(
                "placementFamilies", ["scenery", "npc", "ground-item", "boundary"]
            ),
            "non-loopback": lambda value: value["activation"].__setitem__(
                "loopbackOnly", False
            ),
            "wrong-void": lambda value: value.__setitem__(
                "canonicalVoidTile", [0] * 10
            ),
            "unexpected-field": lambda value: value.__setitem__(
                "unexpected", True
            ),
        }
        with tempfile.TemporaryDirectory(prefix="adaptive-runtime-capability-") as temp:
            base = Path(temp)
            target = base / "ordinary-parent"
            target.mkdir()
            report = base / "report.json"
            self.discover(target, report)
            target_before = tree_bytes(target)
            for index, (label, mutate) in enumerate(mutations.items()):
                with self.subTest(label=label):
                    installation = base / label / "World Builder 2"
                    installation.mkdir(parents=True)
                    runtime = self.make_runtime(installation)
                    capability_path = (
                        runtime
                        / "server/conf/world-builder/"
                        "adaptive-runtime-capability-v1.json"
                    )
                    capability = json.loads(
                        capability_path.read_text(encoding="utf-8")
                    )
                    mutate(capability)
                    write_json(capability_path, capability)
                    runtime_before = tree_bytes(runtime)
                    refused, _ = self.create_project(
                        installation,
                        runtime,
                        target,
                        report,
                        f"Rejected {label}",
                        43900 + index,
                    )
                    self.assertEqual(3, refused.returncode, refused.stderr)
                    self.assertIn("LOADER_INCOMPATIBLE", refused.stderr)
                    self.assertFalse(
                        (installation / "project-registry.json").exists()
                    )
                    self.assertFalse(
                        list((installation / "projects").glob(".staging-*"))
                    )
                    self.assertEqual(runtime_before, tree_bytes(runtime))
                    self.assertEqual(target_before, tree_bytes(target))

    def test_unbound_project_runtime_entry_refuses_reopen(self):
        with tempfile.TemporaryDirectory(prefix="adaptive-runtime-closure-") as temp:
            base = Path(temp)
            installation = base / "World Builder 2"
            installation.mkdir()
            runtime = self.make_runtime(installation)
            target = base / "ordinary-parent"
            target.mkdir()
            report = base / "report.json"
            self.discover(target, report)
            target_before = tree_bytes(target)
            created, summary = self.create_project(
                installation, runtime, target, report, "Runtime closure", 43834
            )
            self.assertEqual(0, created.returncode, created.stderr)
            project = Path(summary["projectRoot"])
            source_before = tree_bytes(project / "source")
            injected = project / "working/runtime/server/lib/injected.jar"
            injected.parent.mkdir(parents=True, exist_ok=True)
            injected.write_bytes(b"unbound runtime entry\n")
            refused = self.run_cli(
                "open-project",
                "--installation-root",
                installation,
                "--target-root",
                target,
                "--validate-only",
            )
            self.assertEqual(3, refused.returncode, refused.stderr)
            self.assertIn("SOURCE_CORRUPT", refused.stderr)
            self.assertIn("working/runtime/server/lib/injected.jar", refused.stderr)
            self.assertEqual(target_before, tree_bytes(target))
            self.assertEqual(source_before, tree_bytes(project / "source"))

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
