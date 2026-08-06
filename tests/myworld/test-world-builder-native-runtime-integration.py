#!/usr/bin/env python3
"""Opt-in, no-UI startup proof for an exact packaged adaptive runtime."""

from __future__ import annotations

import hashlib
import json
import os
import shutil
import socket
import sqlite3
import subprocess
import tempfile
import unittest
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
    for raw in path.read_text(encoding="utf-8").splitlines():
        if not raw or raw.startswith("#"):
            continue
        fields = raw.split("\t")
        if len(fields) != 3:
            raise AssertionError(f"Malformed runtime allowlist line: {raw!r}")
        records.append((fields[0], fields[1], fields[2]))
    return records


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
        destinations = {destination for _, destination, _ in records}
        required_destinations = {
            f"server/conf/server/languages/{name}"
            for name in REQUIRED_LANGUAGE_BUNDLES
        } | {
            f"server/database/sqlite/patches/{name}"
            for name in REQUIRED_DATABASE_PATCHES
        }
        missing = required_destinations - destinations
        if missing:
            raise AssertionError(
                "Packaged runtime allowlist omits native server assets: "
                + ", ".join(sorted(missing))
            )

        cls.bound_inputs: dict[Path, str] = {}
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

        for name in EMPTY_LANGUAGE_BUNDLES:
            path = cls.runtime / "server/conf/server/languages" / name
            if path.stat().st_size != 0:
                raise AssertionError(f"Expected exact empty fallback bundle: {name}")

        cls.tools = cls.runtime / "launcher/world-builder-tools.jar"
        if not cls.tools.is_file() or cls.tools.is_symlink():
            raise AssertionError("Native runtime is missing world-builder-tools.jar")
        cls.bound_inputs[cls.tools] = sha256(cls.tools)

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

public final class NativeAdaptiveServerHarness {
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] args) throws Exception {
        Path project = Paths.get(args[0]);
        String classes = args[1];
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

            for name in REQUIRED_LANGUAGE_BUNDLES:
                packaged = self.runtime / "server/conf/server/languages" / name
                project_copy = (
                    project
                    / "working/runtime/server/conf/server/languages"
                    / name
                )
                self.assertEqual(packaged.read_bytes(), project_copy.read_bytes())

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
            for fatal in (
                "MissingResourceException",
                "PatchApplicationException",
                "Unable to apply database patches",
                "Exception starting server with a configuration file",
            ):
                self.assertNotIn(fatal, log_text)

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

            outside_installation = []
            for path in target.rglob("*"):
                try:
                    path.relative_to(installation)
                except ValueError:
                    outside_installation.append(path)
            self.assertEqual([], outside_installation)
            for path, before_hash in self.bound_inputs.items():
                self.assertEqual(before_hash, sha256(path), path)


if __name__ == "__main__":
    unittest.main()
