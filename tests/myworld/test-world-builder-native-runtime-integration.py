#!/usr/bin/env python3
"""Opt-in, no-UI startup proof for an exact packaged adaptive runtime."""

from __future__ import annotations

import hashlib
import json
import os
import shutil
import socket
import sqlite3
import stat
import subprocess
import tempfile
import unittest
import zipfile
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
MAIN_CLASS = "com.openrsc.worldbuilder.WorldBuilderCli"
RUNTIME_ENV = "WORLD_BUILDER_NATIVE_RUNTIME_ROOT"
CORE_ENV = "WORLD_BUILDER_EXACT_CORE_RUNTIME"
JAVA_ENV = "WORLD_BUILDER_NATIVE_JAVA"
RUNTIME_TEXT = os.environ.get(RUNTIME_ENV, "")
CORE_TEXT = os.environ.get(CORE_ENV, "")
REQUIRED_LANGUAGE_BUNDLES = {
    "AuthenticMessages_en_UK.properties",
    "AuthenticMessages_en_UK_female.properties",
    "AuthenticMessages_en_UK_female_no_misgender.properties",
    "AuthenticMessages_en_UK_gender_neutral.properties",
    "AuthenticMessages_en_UK_male.properties",
    "CustomMessages_en_UK.properties",
    "CustomMessages_en_UK_female.properties",
    "CustomMessages_en_UK_gender_neutral.properties",
    "CustomMessages_en_UK_male.properties",
}
EMPTY_LANGUAGE_BUNDLES = {
    "CustomMessages_en_UK_female.properties",
    "CustomMessages_en_UK_gender_neutral.properties",
    "CustomMessages_en_UK_male.properties",
}
REQUIRED_DATABASE_PATCHES = {
    "2021_05_11_add_db_patches.sql",
    "2023_02_01_former_names.sql",
    "2026_05_14_add_summoning_skill.sql",
    "2026_08_03_add_blessing_skill.sql",
}
DEFINITION_PREFIX = "server/conf/server/defs/"
TOOLS_ALLOWLIST_RESOURCE = (
    "com/openrsc/worldbuilder/runtime-asset-allowlist-v1.txt"
)
MAX_GENERATED_PEM_BYTES = 1024 * 1024
EMPTY_ZIP_ARCHIVE = b"PK\x05\x06" + (b"\x00" * 18)
CLIENT_TERRAIN_BOOTSTRAP = Path(
    "working/runtime/client/Cache/video/Authentic_Landscape.orsc"
)


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def git(root: Path, *arguments: str) -> str:
    result = subprocess.run(
        ["git", "-C", str(root), *arguments],
        text=True,
        capture_output=True,
    )
    if result.returncode:
        raise AssertionError(result.stderr.strip() or result.stdout.strip())
    return result.stdout.strip()


def locked_core_commit() -> str:
    for line in (ROOT / "core-framework.lock").read_text(encoding="utf-8").splitlines():
        if line.startswith("CORE_COMMIT="):
            return line.split("=", 1)[1]
    raise AssertionError("core-framework.lock has no CORE_COMMIT")


def parse_allowlist(path: Path) -> list[tuple[str, str, str]]:
    records = []
    sources = set()
    destinations = set()
    for raw in path.read_text(encoding="utf-8").splitlines():
        if not raw or raw.startswith("#"):
            continue
        fields = raw.split("\t")
        if len(fields) != 3:
            raise AssertionError(f"Malformed runtime allowlist line: {raw!r}")
        source_key = fields[0].casefold()
        destination_key = fields[1].casefold()
        if (
            source_key in sources
            or destination_key in destinations
            or any(character in fields[0] + fields[1] for character in "*?[]")
        ):
            raise AssertionError(f"Non-exact runtime allowlist line: {raw!r}")
        sources.add(source_key)
        destinations.add(destination_key)
        records.append((fields[0], fields[1], fields[2]))
    return records


def tree_inventory(root: Path, excluded: Path | None = None) -> dict[str, tuple]:
    inventory = {}
    excluded_path = excluded.absolute() if excluded is not None else None
    for path in sorted(root.rglob("*")):
        if excluded_path is not None:
            try:
                path.absolute().relative_to(excluded_path)
                continue
            except ValueError:
                pass
        relative = path.relative_to(root).as_posix()
        metadata = path.lstat()
        if stat.S_ISLNK(metadata.st_mode):
            inventory[relative] = ("link", os.readlink(path))
        elif stat.S_ISDIR(metadata.st_mode):
            inventory[relative] = ("directory",)
        elif stat.S_ISREG(metadata.st_mode):
            inventory[relative] = (
                "file",
                metadata.st_size,
                metadata.st_nlink,
                sha256(path),
            )
        else:
            inventory[relative] = ("special", stat.S_IFMT(metadata.st_mode))
    return inventory


def choose_port() -> int:
    for _ in range(20):
        with socket.socket() as listener:
            listener.bind(("127.0.0.1", 0))
            port = listener.getsockname()[1]
        if 1024 <= port < 65534:
            return port
    raise AssertionError("Unable to reserve a bounded loopback test port")


@unittest.skipUnless(
    RUNTIME_TEXT and CORE_TEXT,
    f"set {RUNTIME_ENV} and {CORE_ENV} for the exact native runtime check",
)
class NativeRuntimeIntegrationTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.runtime = Path(RUNTIME_TEXT).resolve(strict=True)
        cls.core = Path(CORE_TEXT).resolve(strict=True)
        if not cls.runtime.is_dir() or not cls.core.is_dir():
            raise AssertionError("Native runtime and exact Core inputs must be directories")

        expected_core = locked_core_commit()
        actual_core = git(cls.core, "rev-parse", "HEAD")
        if actual_core != expected_core:
            raise AssertionError(
                f"Exact runtime checkout is {actual_core}; expected {expected_core}"
            )
        if git(cls.core, "status", "--porcelain=v1", "--untracked-files=all"):
            raise AssertionError("Exact runtime checkout is dirty")

        cls.allowlist = cls.runtime.parent / "RUNTIME-ASSET-ALLOWLIST.txt"
        if not cls.allowlist.is_file() or cls.allowlist.is_symlink():
            raise AssertionError(
                "Native runtime must have its packaged RUNTIME-ASSET-ALLOWLIST.txt sibling"
            )
        records = parse_allowlist(cls.allowlist)
        cls.records = records
        required_records = {
            (
                f"server/conf/server/languages/{name}",
                f"server/conf/server/languages/{name}",
                "runtime-configuration",
            )
            for name in REQUIRED_LANGUAGE_BUNDLES
        } | {
            (
                f"server/database/sqlite/patches/{name}",
                f"server/database/sqlite/patches/{name}",
                "runtime-database-contract",
            )
            for name in REQUIRED_DATABASE_PATCHES
        }
        missing = required_records - set(records)
        if missing:
            raise AssertionError(
                "Packaged runtime allowlist omits native server assets: "
                + ", ".join(sorted(destination for _, destination, _ in missing))
            )

        definition_root = cls.core / "server/conf/server/defs"
        if not definition_root.is_dir() or definition_root.is_symlink():
            raise AssertionError("Exact provider definition root is missing or unsafe")
        provider_definitions = set()
        for path in definition_root.rglob("*"):
            relative = path.relative_to(definition_root)
            if relative.parts and relative.parts[0] == "locs":
                continue
            if path.is_symlink() or (not path.is_dir() and not path.is_file()):
                raise AssertionError(
                    f"Exact provider definition entry is unsafe: {relative.as_posix()}"
                )
            if path.is_file():
                provider_definitions.add(
                    DEFINITION_PREFIX + relative.as_posix()
                )
        allowlisted_definitions = {
            (source, destination, role)
            for source, destination, role in records
            if source.startswith(DEFINITION_PREFIX)
            or destination.startswith(DEFINITION_PREFIX)
        }
        required_definitions = {
            (relative, relative, "default-definition-catalog")
            for relative in provider_definitions
        }
        if allowlisted_definitions != required_definitions:
            missing_definitions = sorted(
                source
                for source, _, _ in required_definitions - allowlisted_definitions
            )
            extra_definitions = sorted(
                source
                for source, _, _ in allowlisted_definitions - required_definitions
            )
            raise AssertionError(
                "Packaged runtime definition closure differs from exact provider; "
                f"missing={missing_definitions}, extra={extra_definitions}"
            )
        if not provider_definitions:
            raise AssertionError("Exact provider definition closure is empty")
        cls.provider_definitions = provider_definitions

        for path in cls.runtime.rglob("*"):
            if path.is_dir() and not path.is_symlink():
                continue
            relative = path.relative_to(cls.runtime).as_posix()
            if path.is_symlink() or not path.is_file():
                raise AssertionError(f"Packaged runtime entry is unsafe: {relative}")
            lower = relative.casefold()
            if (
                lower.startswith("server/conf/server/defs/locs/")
                or lower.startswith("server/conf/server/data/")
                or (
                    lower.startswith("client_base/cache/video/")
                    and "landscape" in lower
                )
                or lower in {"server/client.pem", "server/server.pem"}
            ):
                raise AssertionError(
                    f"Packaged runtime contains forbidden world/generated content: {relative}"
                )

        cls.bound_inputs: dict[Path, str] = {}
        cls.provider_inputs: dict[Path, str] = {}
        for source, destination, _ in records:
            source_path = cls.core / source
            runtime_path = cls.runtime / destination
            if not source_path.is_file() or source_path.is_symlink():
                raise AssertionError(f"Exact provider input is missing: {source}")
            if not runtime_path.is_file() or runtime_path.is_symlink():
                raise AssertionError(f"Packaged runtime input is missing: {destination}")
            source_hash = sha256(source_path)
            if sha256(runtime_path) != source_hash:
                raise AssertionError(
                    f"Packaged runtime differs from exact provider: {destination}"
                )
            cls.bound_inputs[runtime_path] = source_hash
            cls.provider_inputs[source_path] = source_hash

        for relative in (
            "Client_Base/Open_RSC_Client.jar",
            "server/core.jar",
            "server/plugins.jar",
        ):
            provider_path = cls.core / relative
            runtime_path = cls.runtime / relative
            if not provider_path.is_file() or not runtime_path.is_file():
                raise AssertionError(f"Native runtime binary is missing: {relative}")
            provider_hash = sha256(provider_path)
            if sha256(runtime_path) != provider_hash:
                raise AssertionError(
                    f"Native runtime binary differs from exact provider: {relative}"
                )
            cls.bound_inputs[runtime_path] = provider_hash
            cls.provider_inputs[provider_path] = provider_hash

        for name in EMPTY_LANGUAGE_BUNDLES:
            path = cls.runtime / "server/conf/server/languages" / name
            if path.stat().st_size != 0:
                raise AssertionError(f"Expected exact empty fallback bundle: {name}")

        cls.tools = cls.runtime / "launcher/world-builder-tools.jar"
        if not cls.tools.is_file() or cls.tools.is_symlink():
            raise AssertionError("Native runtime is missing world-builder-tools.jar")
        cls.bound_inputs[cls.tools] = sha256(cls.tools)
        with zipfile.ZipFile(cls.tools) as tools:
            if tools.read(TOOLS_ALLOWLIST_RESOURCE) != cls.allowlist.read_bytes():
                raise AssertionError(
                    "Native tools jar embedded allowlist differs from packaged allowlist"
                )

        cls.runtime_before = tree_inventory(cls.runtime)
        cls.allowlist_before = sha256(cls.allowlist)
        cls.core_head_before = actual_core
        cls.core_status_before = git(
            cls.core, "status", "--porcelain=v1", "--untracked-files=all"
        )

        configured_java = os.environ.get(JAVA_ENV, "")
        if configured_java:
            cls.java = Path(configured_java).resolve(strict=True)
        else:
            discovered = shutil.which("java")
            if not discovered:
                raise AssertionError(f"Set {JAVA_ENV} to a reviewed Java 17+ executable")
            cls.java = Path(discovered).resolve(strict=True)
        version = subprocess.run(
            [str(cls.java), "-version"], text=True, capture_output=True
        )
        if version.returncode:
            raise AssertionError(version.stdout + version.stderr)

        javac = shutil.which("javac")
        if not javac:
            raise AssertionError("The opt-in native integration test requires javac")
        cls.compile_temp = tempfile.TemporaryDirectory(
            prefix="world-builder-native-runtime-harness-"
        )
        cls.classes = Path(cls.compile_temp.name) / "classes"
        cls.classes.mkdir()
        source = (
            Path(cls.compile_temp.name)
            / "src/com/openrsc/worldbuilder/NativeAdaptiveServerHarness.java"
        )
        source.parent.mkdir(parents=True)
        source.write_text(
            r'''
package com.openrsc.worldbuilder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.zip.ZipFile;

public final class NativeAdaptiveServerHarness {
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] args) throws Exception {
        Path project = Paths.get(args[0]);
        String classes = args[1];
        try (ZipFile archive = new ZipFile(project.resolve(
                "working/runtime/client/Cache/video/Authentic_Landscape.orsc")
                .toFile())) {
            require(archive.size() == 0, "project-local empty terrain bootstrap");
        }
        List<String> server =
            WorldBuilderProcessSupervisor.defaultAdaptiveServerCommand(project);
        List<String> client = Arrays.asList(
            server.get(0), "-cp", classes,
            "com.openrsc.worldbuilder.NativeAdaptiveServerHarness$NoUiClient",
            project.toString());
        int exit = new WorldBuilderProcessSupervisor().superviseAdaptiveWithCommands(
            project, server, client, 180000L);
        require(exit == 0, "native adaptive supervision exit " + exit);
        Path run = project.resolve("run");
        require(!Files.exists(run.resolve("server.pid")), "server PID cleanup");
        require(!Files.exists(run.resolve("client.pid")), "client PID cleanup");
        require(!Files.exists(run.resolve("world-builder/ready")), "ready cleanup");
        String receipt = new String(
            Files.readAllBytes(run.resolve("last-run.json")), StandardCharsets.UTF_8);
        require(receipt.contains("\"serverExit\": 0"), "server clean exit receipt");
        require(receipt.contains("\"clientExit\": 0"), "client clean exit receipt");
        require(receipt.contains("\"serverFailedFirst\": false"),
            "server remained ready through client exit");
        System.out.println("native-adaptive-server-readiness-shutdown-ok");
    }

    public static final class NoUiClient {
        public static void main(String[] args) throws Exception {
            try (ZipFile archive = new ZipFile(Paths.get(
                    "Cache/video/Authentic_Landscape.orsc").toFile())) {
                require(archive.size() == 0,
                    "client working-directory empty terrain bootstrap");
            }
            System.out.println("native-client-empty-bootstrap-ok");
            Thread.sleep(1000L);
        }
    }
}
'''.strip()
            + "\n",
            encoding="utf-8",
        )
        compiled = subprocess.run(
            [
                javac,
                "-source",
                "8",
                "-target",
                "8",
                "-cp",
                str(cls.tools),
                "-d",
                str(cls.classes),
                str(source),
            ],
            cwd=ROOT,
            text=True,
            capture_output=True,
        )
        if compiled.returncode:
            raise AssertionError(compiled.stdout + compiled.stderr)

    @classmethod
    def tearDownClass(cls) -> None:
        cls.compile_temp.cleanup()

    def run_cli(self, *arguments: object) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            [
                str(self.java),
                "-cp",
                str(self.tools),
                MAIN_CLASS,
                *map(str, arguments),
            ],
            cwd=ROOT,
            text=True,
            capture_output=True,
            timeout=240,
        )

    def test_exact_server_reaches_readiness_applies_migrations_and_shuts_down(self) -> None:
        with tempfile.TemporaryDirectory(
            prefix="world-builder-native-server-integration-"
        ) as temp:
            base = Path(temp)
            target = base / "ordinary-parent"
            target.mkdir()
            installation = target / "World Builder 2"
            installation.mkdir()
            target_outside_before = tree_inventory(target, installation)
            report = base / "discovery-report.json"

            discovered = self.run_cli(
                "discover-adaptive", "--target-root", target
            )
            self.assertEqual(
                0, discovered.returncode, discovered.stdout + discovered.stderr
            )
            discovery = json.loads(discovered.stdout)
            self.assertEqual("standalone", discovery["status"])
            report.write_text(discovered.stdout, encoding="utf-8")

            port = choose_port()
            created = self.run_cli(
                "create-project",
                "--installation-root",
                installation,
                "--runtime-root",
                self.runtime,
                "--target-root",
                target,
                "--discovery-report",
                report,
                "--display-name",
                "Exact Native Server",
                "--port",
                port,
                "--confirm",
                "CREATE",
            )
            self.assertEqual(0, created.returncode, created.stdout + created.stderr)
            summary = json.loads(created.stdout)
            self.assertEqual("standalone-empty", summary["origin"])
            project = Path(summary["projectRoot"])
            bootstrap = project / CLIENT_TERRAIN_BOOTSTRAP
            self.assertTrue(bootstrap.is_file(), bootstrap)
            self.assertFalse(bootstrap.is_symlink(), bootstrap)
            self.assertEqual(1, bootstrap.stat().st_nlink)
            self.assertEqual(EMPTY_ZIP_ARCHIVE, bootstrap.read_bytes())
            with zipfile.ZipFile(bootstrap) as archive:
                self.assertEqual([], archive.namelist())
            self.assertFalse(
                (
                    self.runtime
                    / "Client_Base/Cache/video/Authentic_Landscape.orsc"
                ).exists()
            )
            self.assertNotIn(
                "Landscape.orsc",
                (project / "working/runtime/runtime-assets.sha256").read_text(
                    encoding="utf-8"
                ),
            )
            source_before = tree_inventory(project / "source")
            working_package_before = tree_inventory(
                project / "working/layered-world/package"
            )

            for name in REQUIRED_LANGUAGE_BUNDLES:
                packaged = self.runtime / "server/conf/server/languages" / name
                project_copy = (
                    project
                    / "working/runtime/server/conf/server/languages"
                    / name
                )
                self.assertEqual(packaged.read_bytes(), project_copy.read_bytes())
            for relative in self.provider_definitions:
                self.assertEqual(
                    (self.core / relative).read_bytes(),
                    (project / "working/runtime" / relative).read_bytes(),
                    relative,
                )

            classpath = os.pathsep.join((str(self.classes), str(self.tools)))
            supervised = subprocess.run(
                [
                    str(self.java),
                    "-cp",
                    classpath,
                    "com.openrsc.worldbuilder.NativeAdaptiveServerHarness",
                    str(project),
                    str(self.classes),
                ],
                cwd=ROOT,
                text=True,
                capture_output=True,
                timeout=240,
            )
            server_log = project / "logs/server.log"
            log_text = (
                server_log.read_text(encoding="utf-8", errors="replace")
                if server_log.is_file()
                else "<server log missing>"
            )
            self.assertEqual(
                0,
                supervised.returncode,
                supervised.stdout + supervised.stderr + "\n" + log_text[-12000:],
            )
            self.assertIn(
                "native-adaptive-server-readiness-shutdown-ok", supervised.stdout
            )
            client_log = project / "logs/client.log"
            self.assertIn(
                "native-client-empty-bootstrap-ok",
                client_log.read_text(encoding="utf-8", errors="replace"),
            )
            for fatal in (
                "MissingResourceException",
                "PatchApplicationException",
                "Unable to apply database patches",
                "Exception starting server with a configuration file",
            ):
                self.assertNotIn(fatal, log_text)
            verified = self.run_cli(
                "open-project",
                "--installation-root",
                installation,
                "--target-root",
                target,
                "--validate-only",
            )
            self.assertEqual(0, verified.returncode, verified.stdout + verified.stderr)
            self.assertEqual("ready-standalone", json.loads(verified.stdout)["state"])

            runtime_server = project / "working/runtime/server"
            for name in ("create_db.log", "create_db_error.log"):
                self.assertFalse((runtime_server / name).exists())
                relocated = runtime_server / "logs" / name
                self.assertTrue(relocated.is_file(), relocated)
                self.assertFalse(relocated.is_symlink(), relocated)
                self.assertEqual(1, relocated.stat().st_nlink, relocated)
            for name in ("client.pem", "server.pem"):
                key = runtime_server / name
                metadata = key.lstat()
                self.assertTrue(stat.S_ISREG(metadata.st_mode), key)
                self.assertFalse(key.is_symlink(), key)
                self.assertEqual(1, metadata.st_nlink, key)
                self.assertGreater(metadata.st_size, 0, key)
                self.assertLessEqual(metadata.st_size, MAX_GENERATED_PEM_BYTES, key)
                self.assertEqual(runtime_server.resolve(), key.resolve().parent)
                self.assertFalse((self.runtime / "server" / name).exists())

            database_path = (
                project / "working/runtime/server/inc/sqlite/world_builder.db"
            )
            with sqlite3.connect(database_path) as database:
                applied = {
                    row[0]
                    for row in database.execute(
                        "SELECT patch_name FROM db_patches ORDER BY patch_name"
                    )
                }
                curstats_columns = {
                    row[1] for row in database.execute("PRAGMA table_info(curstats)")
                }
            self.assertTrue(REQUIRED_DATABASE_PATCHES <= applied, applied)
            self.assertTrue({"summoning", "blessing"} <= curstats_columns)

            self.assertEqual(source_before, tree_inventory(project / "source"))
            self.assertEqual(
                working_package_before,
                tree_inventory(project / "working/layered-world/package"),
            )
            self.assertEqual(
                target_outside_before,
                tree_inventory(target, installation),
            )
            self.assertEqual(self.runtime_before, tree_inventory(self.runtime))
            self.assertEqual(self.allowlist_before, sha256(self.allowlist))
            for path, before_hash in self.bound_inputs.items():
                self.assertEqual(before_hash, sha256(path), path)
            for path, before_hash in self.provider_inputs.items():
                self.assertEqual(before_hash, sha256(path), path)
            self.assertEqual(self.core_head_before, git(self.core, "rev-parse", "HEAD"))
            self.assertEqual(
                self.core_status_before,
                git(
                    self.core,
                    "status",
                    "--porcelain=v1",
                    "--untracked-files=all",
                ),
            )


if __name__ == "__main__":
    unittest.main()
