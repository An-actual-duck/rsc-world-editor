#!/usr/bin/env python3
"""Exercise independent final-candidate archive inspection and evidence."""

from __future__ import annotations

import hashlib
import io
import json
import os
import sqlite3
import stat
import subprocess
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
INSPECTOR = ROOT / "scripts/inspect-world-builder-v2-candidate.py"
FOCUSED_SUITE = ROOT / "scripts/test-world-builder-v2-candidate.sh"
NATIVE_PROOF = ROOT / "tests/myworld/test-world-builder-native-runtime-integration.py"
VALIDATION_RECORD = (
    ROOT / "docs/releases/world-builder-v2-v0.2.0-alpha.1-validation.md"
)
CURRENT_VALIDATION_RECORD = (
    ROOT / "docs/releases/world-builder-v2-v0.7.0-alpha.35-validation.md"
)
CURRENT_RELEASE_GATE = ROOT / "release/world-builder-v2/RELEASE-READY"
VERSION = "v0.2.0-alpha.1"
VERSION_NUMBER = VERSION.removeprefix("v")
PRODUCT_ID = "rsc-world-editor-v2"
PACKAGE_ROOT = "World Builder 2"
LINUX_NAME = f"{PRODUCT_ID}-{VERSION_NUMBER}-linux-x64.zip"
WINDOWS_NAME = f"{PRODUCT_ID}-{VERSION_NUMBER}-windows-x64.zip"
RELEASE_MARKER = "spoiled-milk-release-build.marker"
CAPABILITY = (
    json.dumps(
        {
            "schemaVersion": 2,
            "manifestType": "adaptive-world-builder-runtime-capability",
            "capabilityId": "adaptive-world-builder-runtime-capability-v2",
            "profileId": "adaptive-world-builder",
            "serverBuildId": "core-framework-adaptive-builder-server-v2",
            "clientBuildId": "core-framework-adaptive-builder-client-v2",
            "loaderId": "generic-signed-layered-loader-v2-u16-elevation",
            "authoringId": "generic-signed-layered-authoring-v2-u16-elevation",
            "protocolId": "world-builder-native-layered-protocol-v2-u16-elevation",
            "packageSchemaId": "layered-world-package-v1",
            "coordinateModel": "signed-layered-v1",
            "terrainElevation": {
                "storageEncoding": "unsigned-16", "minimum": 0,
                "maximum": 65535, "renderScale": 3,
                "legacyV1Promotion": "unsigned-byte-lossless",
                "operations": ["absolute", "raise", "lower"],
                "atomicMultiTileBounds": True,
            },
            "encodingVersions": [1, 2, 3],
            "authoring": {
                "placementFamilies": ["boundary", "ground-item", "npc", "scenery"]
            },
        },
        sort_keys=True,
    )
    + "\n"
).encode("utf-8")
CLIENT_ENTRIES = (
    RELEASE_MARKER,
    "orsc/AdaptiveWorldBuilderClientSession.class",
    "orsc/WorldBuilderClientProfile.class",
    "linux/x64/org/lwjgl/liblwjgl.so",
    "linux/x64/org/lwjgl/glfw/libglfw.so",
    "linux/x64/org/lwjgl/opengl/liblwjgl_opengl.so",
    "windows/x64/org/lwjgl/lwjgl.dll",
    "windows/x64/org/lwjgl/glfw/glfw.dll",
    "windows/x64/org/lwjgl/opengl/lwjgl_opengl.dll",
)
SERVER_ENTRIES = (
    "com/openrsc/server/content/worldedit/AdaptiveWorldBuilderPackagePublisher.class",
    "com/openrsc/server/content/worldedit/AdaptiveWorldBuilderRuntimeIdentity.class",
    "com/openrsc/server/content/worldedit/AdaptiveWorldBuilderRuntimeSession.class",
    "com/openrsc/server/content/worldedit/WorldEditStorageContext.class",
    "com/openrsc/server/content/worldedit/WorldBuilderRuntimeControl.class",
)
TOOL_ENTRIES = (
    "com/openrsc/worldbuilder/WorldBuilderAdaptiveExporter.class",
    "com/openrsc/worldbuilder/WorldBuilderAdaptiveImporter.class",
    "com/openrsc/worldbuilder/WorldBuilderAdaptiveProjectLifecycle.class",
    "com/openrsc/worldbuilder/WorldBuilderAdaptiveRuntimePreparer.class",
    "com/openrsc/worldbuilder/WorldBuilderAdaptiveRecovery.class",
    "com/openrsc/worldbuilder/WorldBuilderAdaptiveUndo.class",
    "com/openrsc/worldbuilder/WorldBuilderCli.class",
    "com/openrsc/worldbuilder/WorldBuilderDesktopLauncher.class",
    "com/openrsc/worldbuilder/WorldBuilderLauncherModel.class",
    "com/openrsc/worldbuilder/WorldBuilderLayeredPackage.class",
    "com/openrsc/worldbuilder/WorldBuilderProcessSupervisor.class",
)
TOOL_RUNTIME_ALLOWLIST_ENTRY = (
    "com/openrsc/worldbuilder/runtime-asset-allowlist-v1.txt"
)
DEFINITION_FIXTURES = (
    "server/conf/server/defs/TileDef.xml",
    "server/conf/server/defs/extras/ItemCookingDef.xml",
)
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
REQUIRED_DATABASE_QUERIES = (
    ("mysql", "bank_presets.xml"),
    ("mysql", "item.xml"),
    ("mysql", "player.xml"),
    ("sqlite", "bank_presets.xml"),
    ("sqlite", "item.xml"),
    ("sqlite", "patches.xml"),
    ("sqlite", "player.xml"),
)
RUNTIME_CONFIGURATION = (
    b"server_bind_address: 127.0.0.1\n"
    b"client_version: 10048\n"
    b"world_builder_mode: true\n"
    b"world_builder_adaptive_mode: true\n"
    b"layered_native_world_runtime_profile: adaptive-world-builder\n"
    b"restrict_item_id: -1\n"
    b"restrict_scenery_id: -1\n"
)
TOP_FILES = (
    "ASSET-SOURCES.txt",
    "RUNTIME-PROVIDER-COMMIT.txt",
    "EDITOR-ICON-CREDITS.txt",
    "Import Map Changes.cmd",
    "Import Map Changes.sh",
    "LICENSE",
    "PLAYER-ASSET-SOURCES.txt",
    "README.txt",
    "Recover Map Transaction.cmd",
    "Recover Map Transaction.sh",
    "RELEASE-IDENTITY.json",
    "RUNTIME-ASSET-ALLOWLIST.txt",
    "SOURCE-COMMIT.txt",
    "Start World Builder.cmd",
    "Start World Builder.sh",
    "Update World Builder.cmd",
    "Update World Builder.ps1",
    "Update World Builder.sh",
    "VERSION.txt",
)


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def git(root: Path, *arguments: str) -> str:
    result = subprocess.run(
        ["git", *arguments],
        cwd=root,
        text=True,
        capture_output=True,
        check=True,
    )
    return result.stdout.strip()


def initialize_repository(root: Path, message: str) -> str:
    git(root, "init", "--initial-branch=main")
    git(root, "config", "user.name", "Candidate Validation Test")
    git(root, "config", "user.email", "candidate-validation@example.invalid")
    git(root, "add", "--all")
    git(root, "commit", "-m", message)
    return git(root, "rev-parse", "HEAD")


def jar_bytes(entries: tuple[str, ...], overrides: dict[str, bytes] | None = None) -> bytes:
    output = io.BytesIO()
    with zipfile.ZipFile(output, "w", zipfile.ZIP_DEFLATED) as archive:
        for entry in entries:
            data = (
                b"release-build=true\n"
                if entry == RELEASE_MARKER
                else b"fixture"
            )
            if overrides and entry in overrides:
                data = overrides[entry]
            archive.writestr(entry, data)
    return output.getvalue()


def seed_bytes(*, object_rows: int = 0) -> bytes:
    with tempfile.NamedTemporaryFile(suffix=".db") as temporary:
        with sqlite3.connect(temporary.name) as database:
            for table in ("grounditems", "npclocs", "objects"):
                database.execute(f'CREATE TABLE "{table}" (id INTEGER)')
            database.execute(
                "CREATE TABLE db_patches "
                "(id INTEGER PRIMARY KEY AUTOINCREMENT, patch TEXT)"
            )
            database.execute("INSERT INTO db_patches (patch) VALUES ('base-schema')")
            database.execute(
                "CREATE TABLE recovery_questions (id INTEGER, question TEXT)"
            )
            database.execute(
                "INSERT INTO recovery_questions VALUES (1, 'generic question')"
            )
            database.execute("CREATE TABLE players (id INTEGER, username TEXT)")
            for index in range(object_rows):
                database.execute("INSERT INTO objects VALUES (?)", (index + 1,))
        return Path(temporary.name).read_bytes()


class CandidateFixture:
    def __init__(
        self,
        base: Path,
        *,
        core_seed_object_rows: int = 0,
        plugin_world_payload: bool = False,
        capability: bytes = CAPABILITY,
        include_native_runtime_records: bool = True,
        include_inherited_query_records: bool = True,
        include_definition_records: bool = True,
        include_locs_record: bool = False,
        include_generated_key_record: bool = False,
    ) -> None:
        self.base = base
        self.source = base / "source"
        self.core = base / "core"
        self.artifacts = base / "external-candidates"
        self.jres = {
            "linux": base / "reviewed-linux-jre",
            "windows": base / "reviewed-windows-jre",
        }
        self.source.mkdir()
        self.core.mkdir()
        self.artifacts.mkdir()
        self.forbidden_world = b"private target terrain bytes\n"
        self.capability = capability

        self.core_seed = seed_bytes(object_rows=core_seed_object_rows)
        self.client_jar = jar_bytes(CLIENT_ENTRIES)
        self.server_jar = jar_bytes(SERVER_ENTRIES)
        self.plugins_jar = (
            jar_bytes(
                ("renamed/library.bin",),
                {"renamed/library.bin": self.forbidden_world},
            )
            if plugin_world_payload
            else jar_bytes(("fixture/Plugin.class",))
        )
        core_paths = {
            "Client_Base/Open_RSC_Client.jar": self.client_jar,
            "server/core.jar": self.server_jar,
            "server/plugins.jar": self.plugins_jar,
            "server/inc/sqlite/myworld_seed.db": self.core_seed,
            "server/conf/world-builder/adaptive-runtime-capability-v2.json": (
                self.capability
            ),
            "server/conf/server/data/private-map.bin": self.forbidden_world,
            "release/player/ASSET-SOURCES.txt": b"fixture\n",
            "dev/myworld/assets/ui/world-editor/CREDITS.md": b"fixture\n",
            DEFINITION_FIXTURES[0]: b"<tiles/>\n",
            DEFINITION_FIXTURES[1]: b"<cooking/>\n",
            "server/conf/server/defs/locs/private-target-locations.xml": (
                b"must-not-ship\n"
            ),
            "server/client.pem": b"generated client key must not ship\n",
            "server/server.pem": b"generated server key must not ship\n",
        }
        for name in REQUIRED_LANGUAGE_BUNDLES:
            core_paths[f"server/conf/server/languages/{name}"] = (
                b"" if name in EMPTY_LANGUAGE_BUNDLES else b"fixture bundle\n"
            )
        for name in REQUIRED_DATABASE_PATCHES:
            core_paths[f"server/database/sqlite/patches/{name}"] = (
                b"-- fixture runtime migration\n"
            )
        for namespace, name in REQUIRED_DATABASE_QUERIES:
            core_paths[f"server/database/{namespace}/queries/{name}"] = (
                b"<queries/>\n"
            )
        for relative, data in core_paths.items():
            path = self.core / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(data)
        self.runtime_provider_commit = initialize_repository(self.core, "Create runtime fixture")

        native_records = ""
        if include_native_runtime_records:
            native_records = "".join(
                f"server/conf/server/languages/{name}\t"
                f"server/conf/server/languages/{name}\truntime-configuration\n"
                for name in REQUIRED_LANGUAGE_BUNDLES
            ) + "".join(
                f"server/database/sqlite/patches/{name}\t"
                f"server/database/sqlite/patches/{name}\truntime-database-contract\n"
                for name in REQUIRED_DATABASE_PATCHES
            ) + "".join(
                f"server/database/{namespace}/queries/{name}\t"
                f"server/database/{namespace}/queries/{name}\t"
                "runtime-database-contract\n"
                for namespace, name in REQUIRED_DATABASE_QUERIES
                if namespace != "mysql" or include_inherited_query_records
            )
        definition_records = (
            "".join(
                f"{relative}\t{relative}\tdefault-definition-catalog\n"
                for relative in DEFINITION_FIXTURES
            )
            if include_definition_records
            else ""
        )
        if include_locs_record:
            locs = "server/conf/server/defs/locs/private-target-locations.xml"
            definition_records += (
                f"{locs}\t{locs}\tdefault-definition-catalog\n"
            )
        if include_generated_key_record:
            definition_records += (
                "server/client.pem\tserver/client.pem\truntime-configuration\n"
            )
        self.allowlist = (
            "# Candidate validation fixture allowlist\n"
            "server/inc/sqlite/myworld_seed.db\t"
            "server/inc/sqlite/world_builder_seed.db\tbuilder-database-seed\n"
            "server/conf/world-builder/adaptive-runtime-capability-v2.json\t"
			"server/conf/world-builder/adaptive-runtime-capability-v2.json\t"
            "runtime-capability\n"
            + native_records
            + definition_records
        ).encode("utf-8")
        self.tools_jar = jar_bytes(
            TOOL_ENTRIES + (TOOL_RUNTIME_ALLOWLIST_ENTRY,),
            {TOOL_RUNTIME_ALLOWLIST_ENTRY: self.allowlist},
        )
        allowlist_path = (
            self.source / "release/world-builder-v2/RUNTIME-ASSET-ALLOWLIST.txt"
        )
        allowlist_path.parent.mkdir(parents=True)
        allowlist_path.write_bytes(self.allowlist)
        schema = self.source / "tools/world-builder/schema/discovery-report-v2.schema.json"
        schema.parent.mkdir(parents=True)
        schema.write_text('{"type":"object"}\n', encoding="utf-8")
        source_files = {
            "LICENSE": b"fixture\n",
            "release/world-builder-v2/ASSET-SOURCES.txt": b"fixture\n",
            "release/world-builder-v2/Import Map Changes.cmd": b"fixture\n",
            "release/world-builder-v2/Import Map Changes.sh": b"fixture\n",
            "release/world-builder-v2/Recover Map Transaction.cmd": b"fixture\n",
            "release/world-builder-v2/Recover Map Transaction.sh": b"fixture\n",
            "release/world-builder-v2/world-builder-runtime.conf": RUNTIME_CONFIGURATION,
            "release/world-builder-v2/README.txt": (
                b"World Builder @VERSION@ from @SOURCE_COMMIT@\n"
            ),
            "release/updater-v2/README-AUTO-UPDATE.txt": b"Updater appendix\n",
            "release/updater-v2/Start World Builder.cmd": b"fixture\n",
            "release/updater-v2/Start World Builder.sh": b"fixture\n",
            "release/updater-v2/Update World Builder.cmd": b"fixture\n",
            "release/updater-v2/Update World Builder.ps1": b"fixture\n",
            "release/updater-v2/Update World Builder.sh": b"fixture\n",
            "output/world-builder-tools/world-builder-tools.jar": self.tools_jar,
        }
        for relative, data in source_files.items():
            path = self.source / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(data)
        (self.source / "runtime-provider.lock").write_text(
            "RUNTIME_PROVIDER_REPOSITORY=https://example.invalid/runtime.git\n"
            "RUNTIME_PROVIDER_REF=refs/heads/runtime/adaptive-v1\n"
            f"RUNTIME_PROVIDER_COMMIT={self.runtime_provider_commit}\n",
            encoding="utf-8",
        )
        self.source_commit = initialize_repository(self.source, "Create source fixture")
        git(self.source, "remote", "add", "origin", "https://example.invalid/editor.git")
        git(
            self.source,
            "update-ref",
            "refs/remotes/origin/main",
            self.source_commit,
        )

        for platform, runtime in self.jres.items():
            release = (
                'JAVA_VERSION="17.0.20"\n'
                f'OS_NAME="{"Windows" if platform == "windows" else "Linux"}"\n'
                'OS_ARCH="x86_64"\n'
            ).encode()
            runtime_files = {
                "release": release,
                "LICENSE": b"runtime redistribution terms\n",
                "lib/runtime-payload.dat": b"reviewed runtime payload\n",
            }
            java = "bin/java.exe" if platform == "windows" else "bin/java"
            runtime_files[java] = b"bundled java\n"
            for relative, data in runtime_files.items():
                path = runtime / relative
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(data)
                path.chmod(0o644)
            (runtime / java).chmod(0o644 if platform == "windows" else 0o755)
            for directory in (runtime, *(path for path in runtime.rglob("*") if path.is_dir())):
                directory.chmod(0o755)
            (runtime / "lib/runtime-payload-link.dat").symlink_to(
                "runtime-payload.dat"
            )

        self.archives = {
            "linux": self.artifacts / LINUX_NAME,
            "windows": self.artifacts / WINDOWS_NAME,
        }
        self.files = {
            platform: self.package_files(platform) for platform in self.archives
        }
        for platform in self.archives:
            self.write_archive(platform)
        self.write_checksums()

    def identity(self) -> bytes:
        value = {
            "schemaVersion": 1,
            "productId": PRODUCT_ID,
            "productGeneration": 2,
            "displayName": PACKAGE_ROOT,
            "updateChannel": PRODUCT_ID,
            "releaseTag": f"{PRODUCT_ID}-{VERSION_NUMBER}",
            "artifactPrefix": PRODUCT_ID,
            "worldSourceIdentity": "target-adaptive-v1",
            "automaticUpgradeFromProductIds": [PRODUCT_ID],
            "legacyProductId": "rsc-world-editor-v1",
            "legacyFinalTag": "v1.1.0",
            "legacyWorkspaceMigration": False,
            "version": VERSION,
            "sourceCommit": self.source_commit,
            "runtimeProviderCommit": self.runtime_provider_commit,
        }
        return (json.dumps(value, indent=2) + "\n").encode("utf-8")

    def package_files(self, platform: str) -> dict[str, tuple[bytes, int]]:
        files = {relative: (b"fixture\n", 0o644) for relative in TOP_FILES}
        for launcher in (
            "Import Map Changes.sh",
            "Recover Map Transaction.sh",
            "Start World Builder.sh",
            "Update World Builder.sh",
        ):
            files[launcher] = (b"fixture\n", 0o755)
        files.update(
            {
                "VERSION.txt": ((VERSION + "\n").encode(), 0o644),
                "SOURCE-COMMIT.txt": ((self.source_commit + "\n").encode(), 0o644),
                "RUNTIME-PROVIDER-COMMIT.txt": ((self.runtime_provider_commit + "\n").encode(), 0o644),
                "RELEASE-IDENTITY.json": (self.identity(), 0o644),
                "RUNTIME-ASSET-ALLOWLIST.txt": (self.allowlist, 0o644),
                "README.txt": (
                    (
                        f"World Builder {VERSION} from {self.source_commit}\n"
                        "Updater appendix\n"
                        f"\nRuntime provider commit: {self.runtime_provider_commit}\n"
                    ).encode(),
                    0o644,
                ),
                "builder-runtime/Client_Base/Open_RSC_Client.jar": (
                    self.client_jar,
                    0o644,
                ),
                "builder-runtime/server/core.jar": (
                    self.server_jar,
                    0o644,
                ),
                "builder-runtime/server/plugins.jar": (
                    self.plugins_jar,
                    0o644,
                ),
                "builder-runtime/launcher/world-builder-tools.jar": (
                    self.tools_jar,
                    0o644,
                ),
                "builder-runtime/server/world-builder.conf": (
                    RUNTIME_CONFIGURATION,
                    0o644,
                ),
                "builder-runtime/server/inc/sqlite/world_builder_seed.db": (
                    self.core_seed,
                    0o644,
                ),
                "builder-runtime/server/conf/world-builder/"
                "adaptive-runtime-capability-v2.json": (
                    self.capability,
                    0o644,
                ),
                "builder-runtime/launcher/schema/discovery-report-v2.schema.json": (
                    b'{"type":"object"}\n',
                    0o644,
                ),
                "runtime/release": (
                    (
                        'JAVA_VERSION="17.0.20"\n'
                        f'OS_NAME="{"Windows" if platform == "windows" else "Linux"}"\n'
                        'OS_ARCH="x86_64"\n'
                    ).encode(),
                    0o644,
                ),
                "runtime/LICENSE": (b"runtime redistribution terms\n", 0o644),
                "runtime/lib/runtime-payload.dat": (
                    b"reviewed runtime payload\n",
                    0o644,
                ),
                "runtime/lib/runtime-payload-link.dat": (
                    b"reviewed runtime payload\n",
                    0o644,
                ),
            }
        )
        java = "runtime/bin/java.exe" if platform == "windows" else "runtime/bin/java"
        files[java] = (b"bundled java\n", 0o644 if platform == "windows" else 0o755)
        for raw in self.allowlist.decode("utf-8").splitlines():
            if not raw or raw.startswith("#"):
                continue
            source, destination, _ = raw.split("\t")
            files.setdefault(
                "builder-runtime/" + destination,
                ((self.core / source).read_bytes(), 0o644),
            )
        return files

    def write_archive(self, platform: str, *, refresh_manifest: bool = True) -> None:
        files = self.files[platform]
        if refresh_manifest:
            manifest = "".join(
                f"{sha256(data)}  ./{relative}\n"
                for relative, (data, _) in sorted(files.items())
                if relative != "PACKAGE-MANIFEST.sha256"
            ).encode("utf-8")
            files["PACKAGE-MANIFEST.sha256"] = (manifest, 0o644)
        with zipfile.ZipFile(
            self.archives[platform], "w", zipfile.ZIP_DEFLATED
        ) as archive:
            directories = {PACKAGE_ROOT}
            for relative in files:
                parts = Path(relative).parts[:-1]
                for index in range(1, len(parts) + 1):
                    directories.add(f"{PACKAGE_ROOT}/" + "/".join(parts[:index]))
            for directory in sorted(directories):
                info = zipfile.ZipInfo(directory + "/")
                info.create_system = 3
                info.external_attr = (stat.S_IFDIR | 0o755) << 16
                archive.writestr(info, b"")
            for relative, (data, mode) in sorted(files.items()):
                info = zipfile.ZipInfo(f"{PACKAGE_ROOT}/{relative}")
                info.create_system = 3
                info.external_attr = (stat.S_IFREG | mode) << 16
                archive.writestr(info, data, compress_type=zipfile.ZIP_DEFLATED)

    def add_raw_entry(self, platform: str, name: str, data: bytes, mode: int) -> None:
        with zipfile.ZipFile(self.archives[platform], "a") as archive:
            info = zipfile.ZipInfo(name)
            info.create_system = 3
            info.external_attr = mode << 16
            archive.writestr(info, data)

    def write_checksums(self) -> None:
        lines = [
            f"{sha256(self.archives[platform].read_bytes())}  "
            f"{self.archives[platform].name}\n"
            for platform in ("linux", "windows")
        ]
        (self.artifacts / "SHA256SUMS.txt").write_text("".join(lines), encoding="utf-8")

    def command(self, **overrides: Path | str) -> list[str]:
        values: dict[str, Path | str] = {
            "source-root": self.source,
            "runtime-provider": self.core,
            "linux-jre": self.jres["linux"],
            "windows-jre": self.jres["windows"],
            "version": VERSION,
            "linux-archive": self.archives["linux"],
            "windows-archive": self.archives["windows"],
            "checksums": self.artifacts / "SHA256SUMS.txt",
        }
        values.update(overrides)
        command = [sys.executable, str(INSPECTOR)]
        for name, value in values.items():
            command.extend((f"--{name}", str(value)))
        return command

    def run(self, **overrides: Path | str) -> subprocess.CompletedProcess[str]:
        return subprocess.run(self.command(**overrides), text=True, capture_output=True)


class WorldBuilderV2CandidateValidationTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory(prefix="world-builder-candidate-")
        self.fixture = CandidateFixture(Path(self.temporary.name))

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def test_external_candidates_bind_exact_clean_commits_and_emit_evidence(self) -> None:
        result = self.fixture.run()
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        evidence = json.loads(result.stdout)
        self.assertEqual("automated-archive-inspection-passed", evidence["status"])
        self.assertFalse(evidence["releaseReady"])
        self.assertFalse(evidence["releaseGateChanged"])
        self.assertEqual(self.fixture.source_commit, evidence["sourceCommit"])
        self.assertEqual(self.fixture.runtime_provider_commit, evidence["runtimeProviderCommit"])
        self.assertEqual(
            {LINUX_NAME, WINDOWS_NAME},
            {artifact["fileName"] for artifact in evidence["artifacts"]},
        )
        self.assertIn("content-neutral-world-and-creator-scan", evidence["assertions"])
        self.assertIn(
            "exact-reviewed-dual-platform-jre-inventory-bytes-and-modes",
            evidence["assertions"],
        )
        self.assertIn("linux-production-launcher-modes", evidence["assertions"])
        for artifact in evidence["artifacts"]:
            self.assertRegex(artifact["reviewedJreInventorySha256"], r"^[0-9a-f]{64}$")
            self.assertGreater(artifact["reviewedJreFileCount"], 3)
        self.assertIn("owner-software-and-opengl-visual-review", evidence["pendingEvidence"])

    def test_inspector_requires_complete_native_server_runtime_contract(self) -> None:
        with tempfile.TemporaryDirectory(prefix="candidate-native-contract-") as temp:
            fixture = CandidateFixture(
                Path(temp), include_native_runtime_records=False
            )

            result = fixture.run()

            self.assertNotEqual(0, result.returncode)
            self.assertIn("missing required native server assets", result.stderr)
            self.assertIn("CustomMessages_en_UK.properties", result.stderr)

        with tempfile.TemporaryDirectory(
            prefix="candidate-inherited-query-contract-"
        ) as temp:
            fixture = CandidateFixture(
                Path(temp), include_inherited_query_records=False
            )

            result = fixture.run()

            self.assertNotEqual(0, result.returncode)
            self.assertIn("missing required native server assets", result.stderr)
            self.assertIn("server/database/mysql/queries/player.xml", result.stderr)

    def test_inspector_requires_neutral_definitions_and_excludes_locs(self) -> None:
        with tempfile.TemporaryDirectory(prefix="candidate-definition-closure-") as temp:
            fixture = CandidateFixture(
                Path(temp), include_definition_records=False
            )

            result = fixture.run()

            self.assertNotEqual(0, result.returncode)
            self.assertIn("content-neutral definition closure", result.stderr)
            self.assertIn(DEFINITION_FIXTURES[0], result.stderr)

        with tempfile.TemporaryDirectory(prefix="candidate-definition-locs-") as temp:
            fixture = CandidateFixture(Path(temp), include_locs_record=True)

            result = fixture.run()

            self.assertNotEqual(0, result.returncode)
            self.assertIn("exclude the complete defs/locs subtree", result.stderr)

    def test_tool_runtime_embedded_allowlist_must_match_release_source(self) -> None:
        self.fixture.files["linux"][
            "builder-runtime/launcher/world-builder-tools.jar"
        ] = (
            jar_bytes(
                TOOL_ENTRIES + (TOOL_RUNTIME_ALLOWLIST_ENTRY,),
                {TOOL_RUNTIME_ALLOWLIST_ENTRY: b"wrong embedded allowlist\n"},
            ),
            0o644,
        )
        self.fixture.write_archive("linux")
        self.fixture.write_checksums()

        result = self.fixture.run()

        self.assertNotEqual(0, result.returncode)
        self.assertIn("embedded allowlist differs", result.stderr)

    def test_inspector_excludes_project_generated_rsa_keys(self) -> None:
        with tempfile.TemporaryDirectory(prefix="candidate-rsa-keys-") as temp:
            fixture = CandidateFixture(
                Path(temp), include_generated_key_record=True
            )

            result = fixture.run()

            self.assertNotEqual(0, result.returncode)
            self.assertIn("project-only generated state", result.stderr)
            self.assertIn("server/client.pem", result.stderr)

    def test_accepted_record_binds_exact_candidate_and_rebuild_rule(self) -> None:
        text = CURRENT_VALIDATION_RECORD.read_text(encoding="utf-8")
        self.assertIn("ACCEPTED — RELEASE READY", text)
        self.assertIn("57441fb1de926b952f3da4be292a8cfcadef9709", text)
        self.assertIn("266c6619d0d85eb7aa1f69c8f96e64c29ff50182", text)
        self.assertIn("Production archives must be rebuilt", text)
        self.assertIn("without screenshots", text)
        self.assertIn("Accepted on", text)
        self.assertIn("packed-map-N", text)
        if CURRENT_RELEASE_GATE.exists():
            gate = json.loads(CURRENT_RELEASE_GATE.read_text(encoding="utf-8"))
            self.assertEqual("v0.7.0-alpha.35", gate["releaseVersion"])
            self.assertEqual(
                "57441fb1de926b952f3da4be292a8cfcadef9709",
                gate["validatedEditorCommit"],
            )
            self.assertEqual(
                "266c6619d0d85eb7aa1f69c8f96e64c29ff50182",
                gate["runtimeProviderCommit"],
            )
        else:
            self.assertIn("Post-publication gate state", text)

    def test_focused_suite_is_noninteractive_and_covers_runtime_supervision(self) -> None:
        text = FOCUSED_SUITE.read_text(encoding="utf-8")
        self.assertIn('"$ROOT_DIR/scripts/test.sh" --group candidate --verbose', text)
        runner = (ROOT / "scripts/test.sh").read_text(encoding="utf-8")
        self.assertIn('if "${command[@]}" </dev/null', runner)
        self.assertIn("test-world-builder-native-runtime-integration.py", runner)
        self.assertIn("test-world-builder-supervision.py", runner)
        self.assertIn("test-world-builder-adaptive-transactions.py", runner)
        self.assertIn("test-world-builder-ai-workspaces.py", runner)
        self.assertIn("test-world-builder-v2-updater.py", runner)

        native = NATIVE_PROOF.read_text(encoding="utf-8")
        self.assertIn("defaultAdaptiveClientCommand(project)", native)
        self.assertIn("ADAPTIVE_WORLD_BUILDER_READY nativeTerrain=true", native)
        self.assertIn("Adaptive World Builder binding accepted", native)
        self.assertNotIn("NoUiClient", native)

    def test_candidate_inputs_inside_either_source_tree_are_refused(self) -> None:
        inside = self.fixture.source / LINUX_NAME
        inside.write_bytes(self.fixture.archives["linux"].read_bytes())
        # The source is dirty too; either condition must prevent candidate acceptance.
        result = self.fixture.run(**{"linux-archive": inside})
        self.assertNotEqual(0, result.returncode)
        self.assertTrue(
            "source must be clean" in result.stderr
            or "outside both source trees" in result.stderr
        )

    def test_reviewed_jres_and_artifacts_must_be_mutually_separate(self) -> None:
        ancestor = self.fixture.base
        overlapping = self.fixture.run(**{"linux-jre": ancestor})
        self.assertNotEqual(0, overlapping.returncode)
        self.assertIn("separate from both source trees", overlapping.stderr)

        inside_jre = self.fixture.jres["linux"] / self.fixture.archives["linux"].name
        inside_jre.write_bytes(self.fixture.archives["linux"].read_bytes())
        nested_artifact = self.fixture.run(**{"linux-archive": inside_jre})
        self.assertNotEqual(0, nested_artifact.returncode)
        self.assertIn("outside reviewed JRE trees", nested_artifact.stderr)

    def test_dirty_or_wrong_locked_runtime_is_refused(self) -> None:
        tracked = self.fixture.core / "server/conf/server/data/private-map.bin"
        tracked.write_bytes(b"changed runtime input\n")
        dirty = self.fixture.run()
        self.assertNotEqual(0, dirty.returncode)
        self.assertIn("runtime source must be clean", dirty.stderr)

        with tempfile.TemporaryDirectory(prefix="candidate-wrong-core-") as temp:
            fixture = CandidateFixture(Path(temp))
            added = fixture.core / "new-runtime-input"
            added.write_text("different clean commit\n", encoding="utf-8")
            git(fixture.core, "add", "new-runtime-input")
            git(fixture.core, "commit", "-m", "Advance fixture runtime")
            wrong = fixture.run()
            self.assertNotEqual(0, wrong.returncode)
            self.assertIn("Locked runtime source mismatch", wrong.stderr)

    def test_wrong_exact_runtime_capability_is_refused(self) -> None:
        with tempfile.TemporaryDirectory(prefix="candidate-capability-") as temp:
            fixture = CandidateFixture(
                Path(temp), capability=b'{"capability":"generic"}\n'
            )
            result = fixture.run()
            self.assertNotEqual(0, result.returncode)
            self.assertIn("capability identity mismatch", result.stderr)

    def test_rebuilt_or_foreign_runtime_binary_is_refused(self) -> None:
        self.fixture.files["linux"][
            "builder-runtime/Client_Base/Open_RSC_Client.jar"
        ] = (jar_bytes(CLIENT_ENTRIES + ("fixture/Unexpected.class",)), 0o644)
        self.fixture.write_archive("linux")
        self.fixture.write_checksums()
        result = self.fixture.run()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("differs from its exact locked source", result.stderr)

    def test_unreviewed_extra_runtime_payload_is_refused_with_refreshed_hashes(
        self,
    ) -> None:
        self.fixture.files["linux"]["runtime/bin/unreviewed-native-payload"] = (
            b"unreviewed executable payload\n",
            0o755,
        )
        self.fixture.write_archive("linux")
        self.fixture.write_checksums()

        result = self.fixture.run()

        self.assertNotEqual(0, result.returncode)
        self.assertIn("outside the exact application allowlist", result.stderr)

    def test_legacy_landscape_archives_are_refused_with_refreshed_hashes(
        self,
    ) -> None:
        for name in ("Authentic_Landscape.orsc", "Custom_Landscape.orsc"):
            with self.subTest(name=name):
                with tempfile.TemporaryDirectory(
                    prefix="candidate-legacy-landscape-"
                ) as temp:
                    fixture = CandidateFixture(Path(temp))
                    fixture.files["linux"][name] = (
                        b"legacy terrain must not ship\n",
                        0o644,
                    )
                    fixture.write_archive("linux")
                    fixture.write_checksums()

                    result = fixture.run()

                    self.assertNotEqual(0, result.returncode)
                    self.assertIn(
                        "forbidden world or operational path", result.stderr
                    )
                    self.assertIn(name, result.stderr)

    def test_changed_or_missing_reviewed_jre_file_is_refused_with_refreshed_hashes(
        self,
    ) -> None:
        self.fixture.files["linux"]["runtime/lib/runtime-payload.dat"] = (
            b"different runtime payload\n",
            0o644,
        )
        self.fixture.write_archive("linux")
        self.fixture.write_checksums()
        changed = self.fixture.run()
        self.assertNotEqual(0, changed.returncode)
        self.assertIn("JRE bytes or relevant mode differ", changed.stderr)

        with tempfile.TemporaryDirectory(prefix="candidate-missing-jre-") as temp:
            fixture = CandidateFixture(Path(temp))
            del fixture.files["windows"]["runtime/lib/runtime-payload-link.dat"]
            fixture.write_archive("windows")
            fixture.write_checksums()
            missing = fixture.run()
            self.assertNotEqual(0, missing.returncode)
            self.assertIn("missing required files", missing.stderr)

    def test_linux_launcher_and_runtime_modes_are_exact_and_nonprivileged(self) -> None:
        self.fixture.files["linux"]["Start World Builder.sh"] = (
            b"fixture\n",
            0o644,
        )
        self.fixture.write_archive("linux")
        self.fixture.write_checksums()
        nonexecutable = self.fixture.run()
        self.assertNotEqual(0, nonexecutable.returncode)
        self.assertIn("exact mode 0755", nonexecutable.stderr)

        with tempfile.TemporaryDirectory(prefix="candidate-setuid-launcher-") as temp:
            fixture = CandidateFixture(Path(temp))
            fixture.files["linux"]["Start World Builder.sh"] = (
                b"fixture\n",
                0o4755,
            )
            fixture.write_archive("linux")
            fixture.write_checksums()
            privileged = fixture.run()
            self.assertNotEqual(0, privileged.returncode)
            self.assertIn("special permission bits", privileged.stderr)

        with tempfile.TemporaryDirectory(prefix="candidate-jre-mode-") as temp:
            fixture = CandidateFixture(Path(temp))
            fixture.files["linux"]["runtime/lib/runtime-payload.dat"] = (
                b"reviewed runtime payload\n",
                0o755,
            )
            fixture.write_archive("linux")
            fixture.write_checksums()
            changed_mode = fixture.run()
            self.assertNotEqual(0, changed_mode.returncode)
            self.assertIn("JRE bytes or relevant mode differ", changed_mode.stderr)

    def test_hard_linked_candidate_input_is_refused(self) -> None:
        alias = self.fixture.artifacts / "linux-candidate-alias.zip"
        os.link(self.fixture.archives["linux"], alias)
        result = self.fixture.run(**{"linux-archive": alias})
        self.assertNotEqual(0, result.returncode)
        self.assertIn("filesystem aliases", result.stderr)

    def test_inner_manifest_mismatch_is_refused_after_outer_checksum_passes(self) -> None:
        self.fixture.files["linux"]["VERSION.txt"] = (b"tampered after manifest\n", 0o644)
        self.fixture.write_archive("linux", refresh_manifest=False)
        self.fixture.write_checksums()
        result = self.fixture.run()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("Package manifest digest mismatch", result.stderr)

    def test_unsafe_and_link_archive_entries_are_refused(self) -> None:
        self.fixture.add_raw_entry(
            "linux", f"{PACKAGE_ROOT}/../escape", b"unsafe", stat.S_IFREG | 0o644
        )
        self.fixture.write_checksums()
        unsafe = self.fixture.run()
        self.assertNotEqual(0, unsafe.returncode)
        self.assertIn("Unsafe archive path component", unsafe.stderr)

        with tempfile.TemporaryDirectory(prefix="candidate-link-") as temp:
            fixture = CandidateFixture(Path(temp))
            fixture.add_raw_entry(
                "linux",
                f"{PACKAGE_ROOT}/linked-runtime",
                b"runtime/bin/java",
                stat.S_IFLNK | 0o777,
            )
            fixture.write_checksums()
            linked = fixture.run()
            self.assertNotEqual(0, linked.returncode)
            self.assertIn("link or special entry", linked.stderr)

        with tempfile.TemporaryDirectory(prefix="candidate-durable-dir-") as temp:
            fixture = CandidateFixture(Path(temp))
            fixture.add_raw_entry(
                "linux",
                f"{PACKAGE_ROOT}/projects/",
                b"",
                stat.S_IFDIR | 0o755,
            )
            fixture.write_checksums()
            durable = fixture.run()
            self.assertNotEqual(0, durable.returncode)
            self.assertIn("creator-state directory", durable.stderr)

        with tempfile.TemporaryDirectory(prefix="candidate-directory-data-") as temp:
            fixture = CandidateFixture(Path(temp))
            fixture.add_raw_entry(
                "linux",
                f"{PACKAGE_ROOT}/runtime/hidden/",
                b'{"packageType":"layered-world"}\n',
                stat.S_IFDIR | 0o755,
            )
            fixture.write_checksums()
            hidden = fixture.run()
            self.assertNotEqual(0, hidden.returncode)
            self.assertIn("directory entry carries data", hidden.stderr)

        with tempfile.TemporaryDirectory(prefix="candidate-case-") as temp:
            fixture = CandidateFixture(Path(temp))
            fixture.add_raw_entry(
                "linux",
                f"{PACKAGE_ROOT}/readme.TXT",
                b"case collision",
                stat.S_IFREG | 0o644,
            )
            fixture.write_checksums()
            collision = fixture.run()
            self.assertNotEqual(0, collision.returncode)
            self.assertIn("case-colliding candidate paths", collision.stderr)

    def test_renamed_structured_world_and_creator_content_is_refused(self) -> None:
        for payload, expected in (
            (b'{"packageType":"layered-world"}\n', "Layered world package content"),
            (
                b'{"manifestType":"world-builder-project"}\n',
                "Creator or transaction state",
            ),
        ):
            with self.subTest(expected=expected):
                with tempfile.TemporaryDirectory(prefix="candidate-content-") as temp:
                    fixture = CandidateFixture(Path(temp))
                    fixture.files["linux"]["PLAYER-ASSET-SOURCES.txt"] = (payload, 0o644)
                    fixture.write_archive("linux")
                    fixture.write_checksums()
                    result = fixture.run()
                    self.assertNotEqual(0, result.returncode)
                    self.assertIn(expected, result.stderr)

    def test_core_world_fingerprint_is_rejected_inside_renamed_nested_entry(self) -> None:
        with tempfile.TemporaryDirectory(prefix="candidate-nested-world-") as temp:
            fixture = CandidateFixture(Path(temp), plugin_world_payload=True)
            result = fixture.run()
            self.assertNotEqual(0, result.returncode)
            self.assertIn("forbidden map terrain copied", result.stderr)

    def test_missing_allowlisted_file_and_nonempty_seed_are_refused(self) -> None:
        del self.fixture.files["linux"][
            "builder-runtime/launcher/schema/discovery-report-v2.schema.json"
        ]
        self.fixture.write_archive("linux")
        self.fixture.write_checksums()
        missing = self.fixture.run()
        self.assertNotEqual(0, missing.returncode)
        self.assertIn("missing required files", missing.stderr)

        with tempfile.TemporaryDirectory(prefix="candidate-seed-") as temp:
            fixture = CandidateFixture(Path(temp), core_seed_object_rows=1)
            seeded = fixture.run()
            self.assertNotEqual(0, seeded.returncode)
            self.assertIn("empty objects table", seeded.stderr)


if __name__ == "__main__":
    unittest.main()
