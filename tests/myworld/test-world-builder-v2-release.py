#!/usr/bin/env python3
"""Validate gated World Builder 2 archives from split standalone/Core sources."""

from __future__ import annotations

import hashlib
import io
import json
import os
import shutil
import sqlite3
import stat
import subprocess
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path


SOURCE_ROOT = Path(__file__).resolve().parents[2]
PACKAGER = SOURCE_ROOT / "scripts/package-world-builder-v2-release.sh"
INSPECTOR = SOURCE_ROOT / "scripts/inspect-world-builder-v2-candidate.py"
VERSION = "v0.1.0-alpha.1"
VERSION_NUMBER = VERSION.removeprefix("v")
PACKAGE_ROOT = "World Builder 2"
PRODUCT_ID = "rsc-world-editor-v2"
WORLD_SOURCE_IDENTITY = "target-adaptive-v1"
RELEASE_MARKER_ENTRY = "spoiled-milk-release-build.marker"
LWJGL_VERSION = "3.3.4"
NATIVE_ENTRIES = (
    "linux/x64/org/lwjgl/liblwjgl.so",
    "linux/x64/org/lwjgl/glfw/libglfw.so",
    "linux/x64/org/lwjgl/opengl/liblwjgl_opengl.so",
    "windows/x64/org/lwjgl/lwjgl.dll",
    "windows/x64/org/lwjgl/glfw/glfw.dll",
    "windows/x64/org/lwjgl/opengl/lwjgl_opengl.dll",
)
ADAPTIVE_CAPABILITY = (
    json.dumps(
        {
            "schemaVersion": 1,
            "manifestType": "adaptive-world-builder-runtime-capability",
            "capabilityId": "adaptive-world-builder-runtime-capability-v1",
            "profileId": "adaptive-world-builder",
            "serverBuildId": "core-framework-adaptive-builder-server-v1",
            "clientBuildId": "core-framework-adaptive-builder-client-v1",
            "loaderId": "generic-signed-layered-loader-v1",
            "authoringId": "generic-signed-layered-authoring-v1",
            "protocolId": "world-builder-native-layered-protocol-v1",
            "packageSchemaId": "layered-world-package-v1",
            "coordinateModel": "signed-layered-v1",
            "authoring": {
                "placementFamilies": ["boundary", "ground-item", "npc", "scenery"]
            },
        },
        sort_keys=True,
    )
    + "\n"
)


def write(path: Path, contents: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(contents, encoding="utf-8")


def make_jar(path: Path, entries: tuple[str, ...]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(path, "w") as archive:
        for entry in entries:
            contents = (
                b"release-build=true\n"
                if entry == RELEASE_MARKER_ENTRY
                else b"fixture"
            )
            archive.writestr(entry, contents)


def git(root: Path, *args: str, check: bool = True) -> str:
    result = subprocess.run(
        ["git", *args],
        cwd=root,
        text=True,
        capture_output=True,
        check=check,
    )
    return result.stdout.strip()


def initialize_repository(root: Path, message: str) -> str:
    git(root, "init", "--initial-branch=main")
    git(root, "config", "user.name", "World Builder Release Test")
    git(root, "config", "user.email", "world-builder-test@example.invalid")
    git(root, "add", "--all")
    git(root, "commit", "-m", message)
    return git(root, "rev-parse", "HEAD")


def make_fixture(
    base: Path,
    *,
    resolved_icons: bool = True,
    linux_os: str = "Linux",
    production_build: bool = False,
    release_ready: bool = False,
    disguised_world: bool = False,
    seeded_placement: bool = False,
    seeded_user_state: bool = False,
) -> tuple[Path, Path, Path, Path, Path]:
    standalone = base / "standalone"
    core = base / "core"
    standalone.mkdir()
    core.mkdir()

    for root in (standalone, core):
        shutil.copytree(
            SOURCE_ROOT / "tools/world-builder", root / "tools/world-builder"
        )
        shutil.copytree(
            SOURCE_ROOT / "release/world-builder-v2",
            root / "release/world-builder-v2",
        )
        marker = root / "release/world-builder-v2/RELEASE-READY"
        if release_ready:
            write(marker, "accepted fixture\n")
        elif marker.exists():
            marker.unlink()
    shutil.copytree(
        SOURCE_ROOT / "release/updater-v2", standalone / "release/updater-v2"
    )
    (standalone / "scripts").mkdir()
    shutil.copy2(SOURCE_ROOT / "scripts/check-core-parity.sh", standalone / "scripts/check-core-parity.sh")
    shutil.copy2(SOURCE_ROOT / "LICENSE", standalone / "LICENSE")
    write(standalone / ".gitignore", "/output/\n")

    make_jar(
        core / "Client_Base/Open_RSC_Client.jar",
        (
            "orsc/AdaptiveWorldBuilderClientSession.class",
            "orsc/WorldBuilderClientProfile.class",
            "myworld-assets/ui/world-editor/action-save.png",
            *((RELEASE_MARKER_ENTRY,) if not production_build else ()),
            *NATIVE_ENTRIES,
        ),
    )
    make_jar(
        core / "server/core.jar",
        (
            "com/openrsc/server/content/worldedit/AdaptiveWorldBuilderPackagePublisher.class",
            "com/openrsc/server/content/worldedit/AdaptiveWorldBuilderRuntimeIdentity.class",
            "com/openrsc/server/content/worldedit/AdaptiveWorldBuilderRuntimeSession.class",
            "com/openrsc/server/content/worldedit/WorldEditStorageContext.class",
            "com/openrsc/server/content/worldedit/WorldBuilderRuntimeControl.class",
        ),
    )
    make_jar(core / "server/plugins.jar", ("fixture/Plugin.class",))
    make_jar(
        standalone / "output/world-builder-tools/world-builder-tools.jar",
        (
            "com/openrsc/worldbuilder/WorldBuilderAdaptiveExporter.class",
            "com/openrsc/worldbuilder/WorldBuilderAdaptiveImporter.class",
            "com/openrsc/worldbuilder/WorldBuilderAdaptiveProjectLifecycle.class",
            "com/openrsc/worldbuilder/WorldBuilderAdaptiveRuntimePreparer.class",
            "com/openrsc/worldbuilder/WorldBuilderAdaptiveRecovery.class",
            "com/openrsc/worldbuilder/WorldBuilderAdaptiveUndo.class",
            "com/openrsc/worldbuilder/WorldBuilderCli.class",
            "com/openrsc/worldbuilder/WorldBuilderLayeredPackage.class",
            "com/openrsc/worldbuilder/WorldBuilderProcessSupervisor.class",
        ),
    )
    write(core / "Client_Base/Cache/audio/audio.dat", "audio")
    write(core / "Client_Base/Cache/video/library.orsc", "library")
    write(core / "Client_Base/Cache/video/Custom_Landscape.orsc", "terrain")
    write(core / "Client_Base/Cache/config.txt", "Menus:1\n")
    write(core / "Client_Base/Cache/credentials.txt", "must-not-ship")
    write(core / "Client_Base/Cache/uid.dat", "must-not-ship")
    write(core / "Client_Base/src/orsc/Config.java", "CLIENT_VERSION = 10048;\n")
    write(core / "server/lib/runtime.jar", "runtime")
    write(core / "server/conf/server/data/Custom_Landscape.orsc", "terrain")
    write(core / "server/conf/server/defs/TileDef.xml", "<tiles/>\n")
    write(core / "server/database/sqlite/core.sqlite", "queries")
    write(core / "server/inc/sqlite/myworld_seed.db", "clean-seed")
    write(core / "server/myworld.conf", "\tclient_version: 10048\n")
    for name in ("alertwords.txt", "badwords.txt", "goodwords.txt"):
        write(core / "server" / name, "\n")
    write(core / "server/ipbans.txt", "ignored generated bans must not ship\n")
    write(core / "server/globalrules.txt", "rules\n")
    write(core / "release/player/ASSET-SOURCES.txt", "player assets resolved\n")
    credits = (
        "All editor icons | Project owner | Confirmed original work | redistribution permitted\n"
        if resolved_icons
        else "All editor icons | Pending confirmation | not release-ready\n"
    )
    write(core / "dev/myworld/assets/ui/world-editor/CREDITS.md", credits)

    allowlist = (
        standalone / "release/world-builder-v2/RUNTIME-ASSET-ALLOWLIST.txt"
    ).read_text(encoding="utf-8")
    for raw in allowlist.splitlines():
        if not raw or raw.startswith("#"):
            continue
        source, _, role = raw.split("\t")
        path = core / source
        if role == "builder-database-seed":
            path.parent.mkdir(parents=True, exist_ok=True)
            if path.exists():
                path.unlink()
            with sqlite3.connect(path) as database:
                for table in ("grounditems", "npclocs", "objects"):
                    database.execute(f'CREATE TABLE "{table}" (id INTEGER)')
                database.execute(
                    "CREATE TABLE db_patches "
                    "(id INTEGER PRIMARY KEY AUTOINCREMENT, patch TEXT)"
                )
                database.execute(
                    "INSERT INTO db_patches (patch) VALUES ('base-schema')"
                )
                database.execute(
                    "CREATE TABLE recovery_questions (id INTEGER, question TEXT)"
                )
                database.execute(
                    "INSERT INTO recovery_questions VALUES (1, 'generic question')"
                )
                database.execute("CREATE TABLE players (id INTEGER, username TEXT)")
                if seeded_placement:
                    database.execute("INSERT INTO objects VALUES (1)")
                if seeded_user_state:
                    database.execute("INSERT INTO players VALUES (1, 'private-user')")
            continue
        if role == "runtime-capability":
            write(path, ADAPTIVE_CAPABILITY)
            continue
        if not path.exists():
            if path.suffix == ".jar":
                make_jar(path, ("fixture/RuntimeLibrary.class",))
            else:
                write(path, f"fixture {role}\n")
    if disguised_world:
        (core / "Client_Base/Cache/video/library.orsc").write_bytes(
            (core / "Client_Base/Cache/video/Custom_Landscape.orsc").read_bytes()
        )
    write(
        core / ".gitignore",
        "/output/\n"
        "/tools/layered-maps/workspace/\n"
        "/Client_Base/Open_RSC_Client.jar\n"
        "/server/core.jar\n"
        "/server/plugins.jar\n"
        "/server/ipbans.txt\n"
        "/PC_Client/lib/lwjgl/*.jar\n",
    )

    if production_build:
        lwjgl_inputs = {
            f"lwjgl-{LWJGL_VERSION}.jar": "org/lwjgl/Version.class",
            f"lwjgl-glfw-{LWJGL_VERSION}.jar": "org/lwjgl/glfw/GLFW.class",
            f"lwjgl-opengl-{LWJGL_VERSION}.jar": "org/lwjgl/opengl/GL.class",
            f"lwjgl-{LWJGL_VERSION}-natives-linux.jar": NATIVE_ENTRIES[0],
            f"lwjgl-glfw-{LWJGL_VERSION}-natives-linux.jar": NATIVE_ENTRIES[1],
            f"lwjgl-opengl-{LWJGL_VERSION}-natives-linux.jar": NATIVE_ENTRIES[2],
            f"lwjgl-{LWJGL_VERSION}-natives-windows.jar": NATIVE_ENTRIES[3],
            f"lwjgl-glfw-{LWJGL_VERSION}-natives-windows.jar": NATIVE_ENTRIES[4],
            f"lwjgl-opengl-{LWJGL_VERSION}-natives-windows.jar": NATIVE_ENTRIES[5],
        }
        for jar_name, entry in lwjgl_inputs.items():
            make_jar(core / "PC_Client/lib/lwjgl" / jar_name, (entry,))

        write(core / "scripts/build-server.sh", "#!/usr/bin/env bash\nexit 0\n")
        write(
            core / "scripts/build-client.sh",
            "#!/usr/bin/env bash\n"
            "set -euo pipefail\n"
            "core_root=\"$(cd \"$(dirname \"${BASH_SOURCE[0]}\")/..\" && pwd)\"\n"
            "[[ \"${SPOILED_MILK_RELEASE_BUILD:-0}\" == 1 ]] || {\n"
            "  printf 'release marker environment was not enabled\\n' >&2\n"
            "  exit 31\n"
            "}\n"
            "marker_dir=\"$core_root/output/release-marker-fixture\"\n"
            "mkdir -p \"$marker_dir\"\n"
            "printf 'release-build=true\\n' > \"$marker_dir/spoiled-milk-release-build.marker\"\n"
            "jar uf \"$core_root/Client_Base/Open_RSC_Client.jar\" "
            "-C \"$marker_dir\" spoiled-milk-release-build.marker\n",
        )
        write(core / "scripts/download-lwjgl.sh", "#!/usr/bin/env bash\nexit 0\n")
        write(
            standalone / "scripts/build-tools.sh",
            "#!/usr/bin/env bash\nexit 0\n",
        )
        for executable in (
            core / "scripts/build-server.sh",
            core / "scripts/build-client.sh",
            core / "scripts/download-lwjgl.sh",
            standalone / "scripts/build-tools.sh",
        ):
            executable.chmod(0o755)

    core_commit = initialize_repository(core, "Create pinned Core fixture")
    write(
        standalone / "core-framework.lock",
        "# Test runtime source.\n"
        "CORE_REPOSITORY=https://example.invalid/core.git\n"
        f"CORE_COMMIT={core_commit}\n",
    )
    standalone_commit = initialize_repository(
        standalone, "Create standalone release fixture"
    )
    git(standalone, "remote", "add", "origin", "https://example.invalid/editor.git")
    git(standalone, "update-ref", "refs/remotes/origin/main", standalone_commit)

    unbundled_world = base / "must-never-be-packaged"
    write(unbundled_world / "manifest.json", '{"packageType":"layered-world"}\n')
    write(unbundled_world / "terrain/fixture.raw", "layered terrain\n")

    linux_runtime = base / "temurin-linux-jre"
    write(
        linux_runtime / "bin/java",
        "#!/usr/bin/env bash\n"
        "if [[ \"${1:-}\" == -version ]]; then exit 0; fi\n"
        "printf '%s\\n' \"$@\" > \"$FAKE_JAVA_CALLS\"\n",
    )
    (linux_runtime / "bin/java").chmod(0o755)
    write(
        linux_runtime / "release",
        f'JAVA_VERSION="17.0.13"\nOS_NAME="{linux_os}"\nOS_ARCH="x86_64"\n',
    )
    write(linux_runtime / "NOTICE", "Linux runtime notice\n")
    write(linux_runtime / "legal/java.base/LICENSE", "Linux runtime license\n")
    write(linux_runtime / "lib/runtime-fixture.dat", "runtime payload\n")
    (linux_runtime / "lib/runtime-fixture-link.dat").symlink_to(
        "runtime-fixture.dat"
    )
    windows_runtime = base / "temurin-windows-jre"
    write(windows_runtime / "bin/java.exe", "runtime")
    write(
        windows_runtime / "release",
        'JAVA_VERSION="17.0.13"\nOS_NAME="Windows"\nOS_ARCH="x86_64"\n',
    )
    write(windows_runtime / "NOTICE", "Windows runtime notice\n")
    write(windows_runtime / "legal/java.base/LICENSE", "Windows runtime license\n")
    return standalone, core, unbundled_world, linux_runtime, windows_runtime


def run_packager(
    standalone: Path,
    core: Path,
    _unbundled_world: Path,
    linux_runtime: Path,
    windows_runtime: Path,
    *,
    skip_build: bool = True,
    candidate_build: bool = False,
    manager_candidate_authorized: bool = True,
) -> subprocess.CompletedProcess[str]:
    environment = dict(os.environ)
    environment["ROOT_DIR"] = str(standalone)
    arguments = [
        "bash",
        str(PACKAGER),
        "--version",
        VERSION,
        "--core-framework",
        str(core),
        "--linux-jre",
        str(linux_runtime),
        "--windows-jre",
        str(windows_runtime),
        "--assets-cleared",
    ]
    if skip_build:
        environment["WORLD_BUILDER_V2_RELEASE_TEST_MODE"] = "1"
        arguments.append("--skip-build")
    if candidate_build:
        arguments.append("--candidate-build")
        if manager_candidate_authorized:
            environment["WORLD_BUILDER_V2_MANAGER_CANDIDATE"] = "1"
    return subprocess.run(
        arguments,
        cwd=SOURCE_ROOT,
        env=environment,
        text=True,
        capture_output=True,
    )


class WorldBuilderV2ReleaseTest(unittest.TestCase):
    def test_public_packaging_refuses_without_acceptance_marker(self) -> None:
        with tempfile.TemporaryDirectory(prefix="world-builder-v2-release-gate-") as temp:
            fixture = make_fixture(Path(temp), release_ready=False)
            result = run_packager(*fixture, skip_build=False)
            self.assertNotEqual(0, result.returncode)
            self.assertIn("final cross-platform release validation", result.stderr)

    def test_real_pre_gate_candidate_build_is_restricted_and_does_not_weaken_release(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory(prefix="world-builder-v2-candidate-") as temp:
            fixture = make_fixture(
                Path(temp), production_build=True, release_ready=False
            )
            standalone = fixture[0]
            candidate_sibling = (
                standalone
                / "output/candidates/world-builder-v2/v9.9.9/keep.txt"
            )
            release_sibling = (
                standalone
                / "output/releases/world-builder-v2/v9.9.9/keep.txt"
            )
            write(candidate_sibling, "preserve candidate sibling\n")
            write(release_sibling, "preserve release sibling\n")

            candidate = run_packager(
                *fixture, skip_build=False, candidate_build=True
            )

            self.assertEqual(
                0, candidate.returncode, candidate.stdout + candidate.stderr
            )
            self.assertIn("restricted", candidate.stdout)
            candidate_output = (
                standalone / "output/candidates/world-builder-v2" / VERSION
            )
            self.assertTrue(
                (
                    candidate_output
                    / f"{PRODUCT_ID}-{VERSION_NUMBER}-linux-x64.zip"
                ).is_file()
            )
            self.assertFalse(
                (standalone / "output/releases/world-builder-v2" / VERSION).exists()
            )
            self.assertFalse(
                (standalone / "release/world-builder-v2/RELEASE-READY").exists()
            )
            self.assertEqual(
                "preserve candidate sibling\n",
                candidate_sibling.read_text(encoding="utf-8"),
            )
            self.assertEqual(
                "preserve release sibling\n",
                release_sibling.read_text(encoding="utf-8"),
            )

            external = Path(temp) / "external-review"
            external.mkdir()
            for name in (
                f"{PRODUCT_ID}-{VERSION_NUMBER}-linux-x64.zip",
                f"{PRODUCT_ID}-{VERSION_NUMBER}-windows-x64.zip",
                "SHA256SUMS.txt",
            ):
                shutil.copy2(candidate_output / name, external / name)
            inspected = subprocess.run(
                [
                    sys.executable,
                    str(INSPECTOR),
                    "--source-root",
                    str(standalone),
                    "--core-framework",
                    str(fixture[1]),
                    "--linux-jre",
                    str(fixture[3]),
                    "--windows-jre",
                    str(fixture[4]),
                    "--version",
                    VERSION,
                    "--linux-archive",
                    str(external / f"{PRODUCT_ID}-{VERSION_NUMBER}-linux-x64.zip"),
                    "--windows-archive",
                    str(external / f"{PRODUCT_ID}-{VERSION_NUMBER}-windows-x64.zip"),
                    "--checksums",
                    str(external / "SHA256SUMS.txt"),
                ],
                text=True,
                capture_output=True,
            )
            self.assertEqual(
                0, inspected.returncode, inspected.stdout + inspected.stderr
            )
            evidence = json.loads(inspected.stdout)
            self.assertEqual(
                "automated-archive-inspection-passed", evidence["status"]
            )

            production = run_packager(*fixture, skip_build=False)
            self.assertNotEqual(0, production.returncode)
            self.assertIn("final cross-platform release validation", production.stderr)

    def test_pre_gate_candidate_mode_refuses_fixture_builds_open_gate_and_bad_inputs(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory(prefix="world-builder-v2-candidate-direct-") as temp:
            fixture = make_fixture(
                Path(temp), production_build=True, release_ready=False
            )
            direct = run_packager(
                *fixture,
                skip_build=False,
                candidate_build=True,
                manager_candidate_authorized=False,
            )
            self.assertNotEqual(0, direct.returncode)
            self.assertIn("use ./scripts/ai-manager.sh candidate", direct.stderr)

        with tempfile.TemporaryDirectory(prefix="world-builder-v2-candidate-skip-") as temp:
            fixture = make_fixture(Path(temp), release_ready=False)
            skipped = run_packager(*fixture, candidate_build=True)
            self.assertNotEqual(0, skipped.returncode)
            self.assertIn("requires a real build", skipped.stderr)

        with tempfile.TemporaryDirectory(prefix="world-builder-v2-candidate-gate-") as temp:
            fixture = make_fixture(
                Path(temp), production_build=True, release_ready=True
            )
            opened = run_packager(
                *fixture, skip_build=False, candidate_build=True
            )
            self.assertNotEqual(0, opened.returncode)
            self.assertIn("forbidden after", opened.stderr)

        with tempfile.TemporaryDirectory(prefix="world-builder-v2-candidate-native-") as temp:
            fixture = make_fixture(
                Path(temp), production_build=True, release_ready=False
            )
            missing_name = f"lwjgl-glfw-{LWJGL_VERSION}-natives-windows.jar"
            (fixture[1] / "PC_Client/lib/lwjgl" / missing_name).unlink()
            missing_native = run_packager(
                *fixture, skip_build=False, candidate_build=True
            )
            self.assertNotEqual(0, missing_native.returncode)
            self.assertIn(missing_name, missing_native.stderr)

        with tempfile.TemporaryDirectory(prefix="world-builder-v2-candidate-dirty-") as temp:
            fixture = make_fixture(
                Path(temp), production_build=True, release_ready=False
            )
            write(fixture[1] / "dirty.txt", "unreviewed runtime input\n")
            dirty = run_packager(
                *fixture, skip_build=False, candidate_build=True
            )
            self.assertNotEqual(0, dirty.returncode)
            self.assertIn("release checkout must be clean", dirty.stderr)

        with tempfile.TemporaryDirectory(prefix="world-builder-v2-candidate-output-link-") as temp:
            fixture = make_fixture(
                Path(temp), production_build=True, release_ready=False
            )
            outside = Path(temp) / "outside-candidate-output"
            outside.mkdir()
            sentinel = outside / "preserve.txt"
            write(sentinel, "outside output must remain unchanged\n")
            candidate_parent = fixture[0] / "output/candidates"
            candidate_parent.parent.mkdir(parents=True, exist_ok=True)
            candidate_parent.symlink_to(outside, target_is_directory=True)
            linked_output = run_packager(
                *fixture, skip_build=False, candidate_build=True
            )
            self.assertNotEqual(0, linked_output.returncode)
            self.assertIn("output path contains a symbolic link", linked_output.stderr)
            self.assertEqual(
                "outside output must remain unchanged\n",
                sentinel.read_text(encoding="utf-8"),
            )

    def test_production_build_marks_and_verifies_the_client(self) -> None:
        with tempfile.TemporaryDirectory(prefix="world-builder-v2-production-") as temp:
            fixture = make_fixture(
                Path(temp), production_build=True, release_ready=True
            )
            standalone = fixture[0]
            result = run_packager(*fixture, skip_build=False)
            self.assertEqual(0, result.returncode, result.stdout + result.stderr)

            archive_path = (
                standalone
                / "output/releases/world-builder-v2"
                / VERSION
                / f"{PRODUCT_ID}-{VERSION_NUMBER}-linux-x64.zip"
            )
            with zipfile.ZipFile(archive_path) as archive:
                client_bytes = archive.read(
                    f"{PACKAGE_ROOT}/builder-runtime/Client_Base/Open_RSC_Client.jar"
                )
            with zipfile.ZipFile(io.BytesIO(client_bytes)) as client:
                self.assertEqual(
                    b"release-build=true\n", client.read(RELEASE_MARKER_ENTRY)
                )

    def test_packager_requires_exact_release_marker_and_dual_platform_natives(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory(prefix="world-builder-v2-marker-") as temp:
            fixture = make_fixture(Path(temp), release_ready=True)
            core = fixture[1]
            make_jar(
                core / "Client_Base/Open_RSC_Client.jar",
                (
                    "orsc/WorldBuilderClientProfile.class",
                    "myworld-assets/ui/world-editor/action-save.png",
                    *NATIVE_ENTRIES,
                ),
            )
            missing_marker = run_packager(*fixture)
            self.assertNotEqual(0, missing_marker.returncode)
            self.assertIn(RELEASE_MARKER_ENTRY, missing_marker.stderr)

        with tempfile.TemporaryDirectory(prefix="world-builder-v2-natives-") as temp:
            fixture = make_fixture(
                Path(temp), production_build=True, release_ready=True
            )
            core = fixture[1]
            missing_name = f"lwjgl-glfw-{LWJGL_VERSION}-natives-windows.jar"
            (core / "PC_Client/lib/lwjgl" / missing_name).unlink()
            missing_native = run_packager(*fixture, skip_build=False)
            self.assertNotEqual(0, missing_native.returncode)
            self.assertIn(missing_name, missing_native.stderr)
            self.assertIn(
                "LWJGL_NATIVE_CLASSIFIERS='natives-linux natives-windows'",
                missing_native.stderr,
            )
            self.assertIn("scripts/download-lwjgl.sh", missing_native.stderr)

        with tempfile.TemporaryDirectory(prefix="world-builder-v2-lwjgl-set-") as temp:
            fixture = make_fixture(
                Path(temp), production_build=True, release_ready=True
            )
            core = fixture[1]
            invalid_name = f"lwjgl-{LWJGL_VERSION}.jar"
            unexpected_name = "lwjgl-3.2.3.jar"
            make_jar(core / "PC_Client/lib/lwjgl" / invalid_name, ("wrong/Entry.class",))
            make_jar(
                core / "PC_Client/lib/lwjgl" / unexpected_name,
                ("org/lwjgl/Version.class",),
            )
            invalid_set = run_packager(*fixture, skip_build=False)
            self.assertNotEqual(0, invalid_set.returncode)
            self.assertIn("Invalid pinned LWJGL release inputs", invalid_set.stderr)
            self.assertIn(invalid_name, invalid_set.stderr)
            self.assertIn("non-reproducible", invalid_set.stderr)
            self.assertIn(unexpected_name, invalid_set.stderr)
            self.assertIn("downloader does not overwrite", invalid_set.stderr)

    def test_packager_rejects_unresolved_asset_provenance(self) -> None:
        with tempfile.TemporaryDirectory(prefix="world-builder-v2-credits-") as temp:
            fixture = make_fixture(
                Path(temp), resolved_icons=False, release_ready=True
            )
            result = run_packager(*fixture)
            self.assertNotEqual(0, result.returncode)
            self.assertIn("icon provenance is unresolved", result.stderr)

    def test_packager_accepts_standalone_owned_tooling_difference(self) -> None:
        with tempfile.TemporaryDirectory(prefix="world-builder-v2-independent-") as temp:
            fixture = make_fixture(Path(temp), release_ready=True)
            standalone = fixture[0]
            importer = (
                standalone
                / "tools/world-builder/src/com/openrsc/worldbuilder/WorldBuilderImporter.java"
            )
            importer.write_text(
                importer.read_text(encoding="utf-8")
                + "\n// Standalone-owned packaging regression fixture.\n",
                encoding="utf-8",
            )
            git(standalone, "add", str(importer.relative_to(standalone)))
            git(standalone, "commit", "-m", "Add standalone-owned tooling fix")
            git(
                standalone,
                "update-ref",
                "refs/remotes/origin/main",
                git(standalone, "rev-parse", "HEAD"),
            )

            result = run_packager(*fixture)

            self.assertEqual(0, result.returncode, result.stdout + result.stderr)

    def test_packager_requires_published_manager_main_and_exact_clean_pinned_core(self) -> None:
        with tempfile.TemporaryDirectory(prefix="world-builder-v2-state-") as temp:
            fixture = make_fixture(Path(temp), release_ready=True)
            standalone, core, *_ = fixture
            git(standalone, "switch", "-c", "feature-test")
            wrong_branch = run_packager(*fixture)
            self.assertNotEqual(0, wrong_branch.returncode)
            self.assertIn("manager branch main", wrong_branch.stderr)

            git(standalone, "switch", "main")
            write(core / "dirty.txt", "not a release input\n")
            dirty_core = run_packager(*fixture)
            self.assertNotEqual(0, dirty_core.returncode)
            self.assertIn("Core-Framework release checkout must be clean", dirty_core.stderr)

        with tempfile.TemporaryDirectory(prefix="world-builder-v2-core-commit-") as temp:
            fixture = make_fixture(Path(temp), release_ready=True)
            core = fixture[1]
            write(core / "wrong-commit.txt", "not the locked dependency\n")
            git(core, "add", "wrong-commit.txt")
            git(core, "commit", "-m", "Move dependency beyond locked commit")

            wrong_core = run_packager(*fixture)

            self.assertNotEqual(0, wrong_core.returncode)
            self.assertIn("Core-Framework must be at locked commit", wrong_core.stderr)

    def test_packager_rejects_wrong_runtime_and_content_disguised_as_allowed_assets(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory(prefix="world-builder-v2-inputs-") as temp:
            fixture = make_fixture(
                Path(temp), linux_os="Windows", release_ready=True
            )
            wrong_runtime = run_packager(*fixture)
            self.assertNotEqual(0, wrong_runtime.returncode)
            self.assertIn('Linux JRE must report OS_NAME="Linux"', wrong_runtime.stderr)

        with tempfile.TemporaryDirectory(prefix="world-builder-v2-disguised-") as temp:
            fixture = make_fixture(
                Path(temp), release_ready=True, disguised_world=True
            )
            disguised = run_packager(*fixture)
            self.assertNotEqual(0, disguised.returncode)
            self.assertIn("forbidden map terrain", disguised.stderr)

        with tempfile.TemporaryDirectory(prefix="world-builder-v2-seed-") as temp:
            fixture = make_fixture(
                Path(temp), release_ready=True, seeded_placement=True
            )
            seeded = run_packager(*fixture)
            self.assertNotEqual(0, seeded.returncode)
            self.assertIn("forbidden generated/static objects state", seeded.stderr)

        with tempfile.TemporaryDirectory(prefix="world-builder-v2-user-seed-") as temp:
            fixture = make_fixture(
                Path(temp), release_ready=True, seeded_user_state=True
            )
            seeded = run_packager(*fixture)
            self.assertNotEqual(0, seeded.returncode)
            self.assertIn("forbidden user/operational players state", seeded.stderr)

        with tempfile.TemporaryDirectory(prefix="world-builder-v2-path-") as temp:
            fixture = make_fixture(Path(temp), release_ready=True)
            write(fixture[3] / "lib/CON.txt", "Windows device path\n")
            unsafe_path = run_packager(*fixture)
            self.assertNotEqual(0, unsafe_path.returncode)
            self.assertIn("Windows-unsafe staged package path", unsafe_path.stderr)

        with tempfile.TemporaryDirectory(prefix="world-builder-v2-jre-link-") as temp:
            fixture = make_fixture(Path(temp), release_ready=True)
            outside = Path(temp) / "external-runtime-file"
            write(outside, "must not be followed\n")
            (fixture[3] / "lib/external-link").symlink_to(outside)
            unsafe_runtime = run_packager(*fixture)
            self.assertNotEqual(0, unsafe_runtime.returncode)
            self.assertIn("broken or external symbolic link", unsafe_runtime.stderr)

    def test_archives_are_complete_v2_only_verified_and_launchable(self) -> None:
        with tempfile.TemporaryDirectory(prefix="world-builder-v2-package-") as temp:
            base = Path(temp)
            fixture = make_fixture(base, release_ready=True)
            standalone, core, _, _, _ = fixture
            result = run_packager(*fixture)
            self.assertEqual(0, result.returncode, result.stdout + result.stderr)
            packager_source = PACKAGER.read_text(encoding="utf-8")
            self.assertNotIn("--layered-package", packager_source)
            self.assertNotIn("layered-maps.sh", packager_source)
            self.assertNotIn("spoiled-milk-package", packager_source)

            source_commit = git(standalone, "rev-parse", "HEAD")
            core_commit = git(core, "rev-parse", "HEAD")
            output = standalone / "output/releases/world-builder-v2" / VERSION
            linux_archive = output / f"{PRODUCT_ID}-{VERSION_NUMBER}-linux-x64.zip"
            windows_archive = output / f"{PRODUCT_ID}-{VERSION_NUMBER}-windows-x64.zip"
            checksums = output / "SHA256SUMS.txt"
            for artifact in (linux_archive, windows_archive, checksums):
                self.assertTrue(artifact.is_file(), artifact)

            checksum_text = checksums.read_text(encoding="utf-8")
            for archive_path in (linux_archive, windows_archive):
                digest = hashlib.sha256(archive_path.read_bytes()).hexdigest()
                self.assertIn(f"{digest}  {archive_path.name}", checksum_text)

            for archive_path, windows in (
                (linux_archive, False),
                (windows_archive, True),
            ):
                with zipfile.ZipFile(archive_path) as archive:
                    names = set(archive.namelist())
                    prefix = f"{PACKAGE_ROOT}/"
                    required = {
                        prefix + "Start World Builder.sh",
                        prefix + "Start World Builder.cmd",
                        prefix + "Update World Builder.sh",
                        prefix + "Update World Builder.cmd",
                        prefix + "Update World Builder.ps1",
                        prefix + "Import Map Changes.sh",
                        prefix + "Import Map Changes.cmd",
                        prefix + "Recover Map Transaction.sh",
                        prefix + "Recover Map Transaction.cmd",
                        prefix + "Undo Last Map Import.sh",
                        prefix + "Undo Last Map Import.cmd",
                        prefix + "README.txt",
                        prefix + "RELEASE-IDENTITY.json",
                        prefix + "PACKAGE-MANIFEST.sha256",
                        prefix + "VERSION.txt",
                        prefix + "SOURCE-COMMIT.txt",
                        prefix + "CORE-SOURCE-COMMIT.txt",
                        prefix + "LICENSE",
                        prefix + "ASSET-SOURCES.txt",
                        prefix + "RUNTIME-ASSET-ALLOWLIST.txt",
                        prefix + "PLAYER-ASSET-SOURCES.txt",
                        prefix + "EDITOR-ICON-CREDITS.txt",
                        prefix + "builder-runtime/Client_Base/Open_RSC_Client.jar",
                        prefix + "builder-runtime/server/core.jar",
                        prefix + "builder-runtime/server/plugins.jar",
                        prefix + "builder-runtime/server/inc/sqlite/world_builder_seed.db",
                        prefix + "builder-runtime/server/world-builder.conf",
                        prefix
                        + "builder-runtime/server/conf/world-builder/"
                        + "adaptive-runtime-capability-v1.json",
                        prefix + "builder-runtime/launcher/world-builder-tools.jar",
                    }
                    self.assertFalse(required - names, required - names)
                    runtime_java = (
                        prefix + "runtime/bin/java.exe"
                        if windows
                        else prefix + "runtime/bin/java"
                    )
                    self.assertIn(runtime_java, names)
                    if not windows:
                        java_mode = archive.getinfo(runtime_java).external_attr >> 16
                        self.assertTrue(java_mode & 0o111, oct(java_mode))
                        flattened_link = prefix + "runtime/lib/runtime-fixture-link.dat"
                        link_mode = archive.getinfo(flattened_link).external_attr >> 16
                        self.assertNotEqual(stat.S_IFLNK, stat.S_IFMT(link_mode))
                        self.assertEqual(
                            b"runtime payload\n", archive.read(flattened_link)
                        )

                    forbidden = (
                        "/workspace/",
                        "/projects/",
                        "/updates/",
                        "/exports/",
                        "/backups/",
                        "/receipts/",
                        "/diagnostics/",
                        "/logs/",
                        "world_builder.db",
                        "world-builder.credential",
                        "credentials.txt",
                        "uid.dat",
                        "clientSettings.conf",
                        "builder-runtime/server/ipbans.txt",
                        "builder-runtime/layered-world/",
                        "Custom_Landscape.orsc",
                        "/defs/locs/",
                        "/ip.txt",
                        "/port.txt",
                    )
                    self.assertFalse(
                        [
                            name
                            for name in names
                            if any(fragment in name for fragment in forbidden)
                        ]
                    )
                    self.assertEqual(
                        f"{VERSION}\n", archive.read(prefix + "VERSION.txt").decode()
                    )
                    self.assertEqual(
                        f"{source_commit}\n",
                        archive.read(prefix + "SOURCE-COMMIT.txt").decode(),
                    )
                    self.assertEqual(
                        f"{core_commit}\n",
                        archive.read(prefix + "CORE-SOURCE-COMMIT.txt").decode(),
                    )
                    identity = json.loads(
                        archive.read(prefix + "RELEASE-IDENTITY.json").decode()
                    )
                    self.assertEqual(PRODUCT_ID, identity["productId"])
                    self.assertEqual(PRODUCT_ID, identity["updateChannel"])
                    self.assertEqual(2, identity["productGeneration"])
                    self.assertEqual([PRODUCT_ID], identity["automaticUpgradeFromProductIds"])
                    self.assertEqual("rsc-world-editor-v1", identity["legacyProductId"])
                    self.assertEqual("v1.1.0", identity["legacyFinalTag"])
                    self.assertFalse(identity["legacyWorkspaceMigration"])
                    self.assertEqual(
                        WORLD_SOURCE_IDENTITY, identity["worldSourceIdentity"]
                    )
                    self.assertNotIn("worldCoordinateModel", identity)
                    self.assertEqual(PACKAGE_ROOT, identity["displayName"])
                    self.assertEqual(
                        f"{PRODUCT_ID}-{VERSION_NUMBER}", identity["releaseTag"]
                    )
                    self.assertEqual(source_commit, identity["sourceCommit"])
                    self.assertEqual(core_commit, identity["coreSourceCommit"])

                    manifest = archive.read(prefix + "PACKAGE-MANIFEST.sha256").decode()
                    manifest_paths = {
                        line.split("  ./", 1)[1]
                        for line in manifest.splitlines()
                    }
                    actual_files = {
                        name.removeprefix(prefix)
                        for name in names
                        if not name.endswith("/")
                        and name != prefix + "PACKAGE-MANIFEST.sha256"
                    }
                    self.assertEqual(actual_files, manifest_paths)
                    self.assertFalse(
                        {
                            path
                            for path in manifest_paths
                            if path.startswith(
                                (
                                    "projects/",
                                    "workspace/",
                                    "builder-runtime/layered-world/",
                                )
                            )
                        }
                    )
                    for line in manifest.splitlines():
                        digest, relative = line.split("  ./", 1)
                        self.assertEqual(
                            digest,
                            hashlib.sha256(archive.read(prefix + relative)).hexdigest(),
                        )

                    readme = archive.read(prefix + "README.txt").decode()
                    self.assertIn("WORLD BUILDER 2 AUTOMATIC UPDATES", readme)
                    self.assertIn("never treats the frozen World Editor v1.1.0", readme)
                    start = archive.read(prefix + "Start World Builder.sh").decode()
                    self.assertIn("Update World Builder.sh", start)
                    self.assertIn("launch-adaptive", start)
                    self.assertIn("--installation-root", start)
                    self.assertIn("--target-root", start)
                    self.assertNotIn("--layered-package", start)
                    self.assertNotIn("server/myworld.conf", start)
                    windows_start = archive.read(
                        prefix + "Start World Builder.cmd"
                    ).decode()
                    for expected in (
                        "launch-adaptive",
                        "--installation-root",
                        "--runtime-root",
                        "--target-root",
                        "--port",
                        "WORLD_BUILDER_CONFIGURATION_ROLE",
                    ):
                        self.assertIn(expected, start)
                        self.assertIn(expected, windows_start)
                    self.assertNotIn("--layered-package", windows_start)
                    self.assertNotIn("server/myworld.conf", windows_start)
                    for script_name, command in (
                        ("Import Map Changes.sh", "import-active-adaptive"),
                        ("Import Map Changes.cmd", "import-active-adaptive"),
                        ("Recover Map Transaction.sh", "recover-active-adaptive"),
                        ("Recover Map Transaction.cmd", "recover-active-adaptive"),
                        ("Undo Last Map Import.sh", "undo-active-adaptive"),
                        ("Undo Last Map Import.cmd", "undo-active-adaptive"),
                    ):
                        script = archive.read(prefix + script_name).decode()
                        self.assertIn("project-registry.json", script)
                        self.assertIn(command, script)
                    with zipfile.ZipFile(
                        io.BytesIO(
                            archive.read(
                                prefix
                                + "builder-runtime/Client_Base/Open_RSC_Client.jar"
                            )
                        )
                    ) as client:
                        self.assertEqual(
                            b"release-build=true\n",
                            client.read(RELEASE_MARKER_ENTRY),
                        )

            extracted = base / "private-server"
            extracted.mkdir()
            subprocess.run(
                ["unzip", "-q", str(linux_archive), "-d", str(extracted)],
                check=True,
            )
            package = extracted / PACKAGE_ROOT
            calls = base / "java-calls.txt"
            environment = dict(os.environ)
            environment.update(
                {
                    "WORLD_BUILDER_SKIP_UPDATE": "1",
                    "WORLD_BUILDER_PORT": "44600",
                    "WORLD_BUILDER_NO_TERMINAL": "1",
                    "FAKE_JAVA_CALLS": str(calls),
                }
            )
            update_lock = package / ".world-builder-v2-update.lock"
            update_lock.mkdir()
            blocked_start = subprocess.run(
                ["bash", str(package / "Start World Builder.sh")],
                cwd=base,
                env=environment,
                text=True,
                capture_output=True,
            )
            self.assertNotEqual(0, blocked_start.returncode)
            self.assertIn("update is already in progress", blocked_start.stderr)
            update_lock.rmdir()
            update_lock.symlink_to(base / "missing-update-lock-target")
            dangling_lock_start = subprocess.run(
                [str(package / "Start World Builder.sh")],
                cwd=base,
                env=environment,
                text=True,
                capture_output=True,
            )
            self.assertNotEqual(0, dangling_lock_start.returncode)
            self.assertIn(
                "update is already in progress", dangling_lock_start.stderr
            )
            update_lock.unlink()
            started = subprocess.run(
                [str(package / "Start World Builder.sh")],
                cwd=base,
                env=environment,
                text=True,
                capture_output=True,
            )
            self.assertEqual(0, started.returncode, started.stdout + started.stderr)
            start_call = calls.read_text(encoding="utf-8")
            self.assertIn("launch-adaptive\n", start_call)
            self.assertIn(str(package), start_call)
            self.assertIn(str(extracted), start_call)
            self.assertIn("44600\n", start_call)

            write(package / "workspace/project-source.json", "{}\n")
            legacy = subprocess.run(
                [str(package / "Start World Builder.sh")],
                cwd=base,
                env=environment,
                text=True,
                capture_output=True,
            )
            self.assertNotEqual(0, legacy.returncode)
            self.assertIn("historical World Builder 2 workspace", legacy.stderr)

            write(package / "workspace/layered-review.json", "{}\n")
            restarted = subprocess.run(
                [str(package / "Start World Builder.sh")],
                cwd=base,
                env=environment,
                text=True,
                capture_output=True,
            )
            self.assertNotEqual(0, restarted.returncode)
            self.assertIn("will not migrate or replace it", restarted.stderr)

            imported = subprocess.run(
                [str(package / "Import Map Changes.sh")],
                cwd=base,
                env=environment,
                text=True,
                capture_output=True,
            )
            self.assertEqual(0, imported.returncode, imported.stdout + imported.stderr)
            import_call = calls.read_text(encoding="utf-8")
            self.assertIn("export-import\n", import_call)
            self.assertIn(VERSION, import_call)
            self.assertIn(source_commit, import_call)

            undone = subprocess.run(
                [str(package / "Undo Last Map Import.sh")],
                cwd=base,
                env=environment,
                text=True,
                capture_output=True,
            )
            self.assertEqual(0, undone.returncode, undone.stdout + undone.stderr)
            self.assertIn("undo-latest-import\n", calls.read_text(encoding="utf-8"))


if __name__ == "__main__":
    unittest.main()
