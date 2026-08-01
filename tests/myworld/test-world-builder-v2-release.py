#!/usr/bin/env python3
"""Validate gated World Builder 2 archives from split standalone/Core sources."""

from __future__ import annotations

import hashlib
import io
import json
import os
import shutil
import stat
import subprocess
import tempfile
import unittest
import zipfile
from pathlib import Path


SOURCE_ROOT = Path(__file__).resolve().parents[2]
PACKAGER = SOURCE_ROOT / "scripts/package-world-builder-v2-release.sh"
VERSION = "v0.1.0-alpha.1"
VERSION_NUMBER = VERSION.removeprefix("v")
PACKAGE_ROOT = "Spoiled Milk World Builder 2"
PRODUCT_ID = "rsc-world-editor-v2"
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
        if production_build:
            write(root / "release/world-builder-v2/RELEASE-READY", "fixture only\n")
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
            "orsc/WorldBuilderClientProfile.class",
            "myworld-assets/ui/world-editor/action-save.png",
            *((RELEASE_MARKER_ENTRY,) if not production_build else ()),
            *NATIVE_ENTRIES,
        ),
    )
    make_jar(
        core / "server/core.jar",
        (
            "com/openrsc/server/content/worldedit/WorldEditStorageContext.class",
            "com/openrsc/server/content/worldedit/WorldBuilderRuntimeControl.class",
        ),
    )
    make_jar(core / "server/plugins.jar", ("fixture/Plugin.class",))
    make_jar(
        standalone / "output/world-builder-tools/world-builder-tools.jar",
        (
            "com/openrsc/worldbuilder/WorldBuilderCli.class",
            "com/openrsc/worldbuilder/WorldBuilderLayeredPackage.class",
        ),
    )
    write(core / "Client_Base/Cache/audio/audio.dat", "audio")
    write(core / "Client_Base/Cache/video/library.orsc", "library")
    write(core / "Client_Base/Cache/video/Custom_Landscape.orsc", "terrain")
    write(core / "Client_Base/Cache/config.txt", "Menus:1\n")
    write(core / "Client_Base/Cache/credentials.txt", "must-not-ship")
    write(core / "Client_Base/Cache/uid.dat", "must-not-ship")
    write(core / "Client_Base/src/orsc/Config.java", "CLIENT_VERSION = 10047;\n")
    write(core / "server/lib/runtime.jar", "runtime")
    write(core / "server/conf/server/data/Custom_Landscape.orsc", "terrain")
    write(core / "server/conf/server/defs/TileDef.xml", "<tiles/>\n")
    write(core / "server/database/sqlite/core.sqlite", "queries")
    write(core / "server/inc/sqlite/myworld_seed.db", "clean-seed")
    write(core / "server/myworld.conf", "\tclient_version: 10047\n")
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
            core / "scripts/lib/layered-world-package.sh",
            "layered_world_require_promotion_approved() { return 0; }\n"
            "layered_world_validate_package() { return 0; }\n",
        )
        write(
            core / "tools/layered-maps/layered-maps.sh",
            "#!/usr/bin/env bash\nexit 0\n",
        )
        write(
            standalone / "scripts/build-tools.sh",
            "#!/usr/bin/env bash\nexit 0\n",
        )
        for executable in (
            core / "scripts/build-server.sh",
            core / "scripts/build-client.sh",
            core / "scripts/download-lwjgl.sh",
            core / "tools/layered-maps/layered-maps.sh",
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

    layered_package = base / "layered-world-package"
    write(layered_package / "manifest.json", '{"schemaVersion":1}\n')
    write(layered_package / "terrain/fixture.raw", "layered terrain\n")

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
    return standalone, core, layered_package, linux_runtime, windows_runtime


def run_packager(
    standalone: Path,
    core: Path,
    layered_package: Path,
    linux_runtime: Path,
    windows_runtime: Path,
    *,
    skip_build: bool = True,
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
        "--layered-package",
        str(layered_package),
        "--assets-cleared",
    ]
    if skip_build:
        environment["SPOILED_MILK_WORLD_BUILDER_V2_RELEASE_TEST_MODE"] = "1"
        arguments.append("--skip-build")
    return subprocess.run(
        arguments,
        cwd=SOURCE_ROOT,
        env=environment,
        text=True,
        capture_output=True,
    )


class WorldBuilderV2ReleaseTest(unittest.TestCase):
    def test_public_packaging_remains_locked_pending_final_acceptance(self) -> None:
        with tempfile.TemporaryDirectory(prefix="world-builder-v2-release-gate-") as temp:
            fixture = make_fixture(Path(temp))
            result = run_packager(*fixture, skip_build=False)
            self.assertNotEqual(0, result.returncode)
            self.assertIn("final cross-platform release validation", result.stderr)

    def test_production_build_marks_and_verifies_the_client(self) -> None:
        with tempfile.TemporaryDirectory(prefix="world-builder-v2-production-") as temp:
            fixture = make_fixture(Path(temp), production_build=True)
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
            fixture = make_fixture(Path(temp))
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
            fixture = make_fixture(Path(temp), production_build=True)
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
            fixture = make_fixture(Path(temp), production_build=True)
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
            fixture = make_fixture(Path(temp), resolved_icons=False)
            result = run_packager(*fixture)
            self.assertNotEqual(0, result.returncode)
            self.assertIn("icon provenance is unresolved", result.stderr)

    def test_packager_requires_published_manager_main_and_clean_pinned_core(self) -> None:
        with tempfile.TemporaryDirectory(prefix="world-builder-v2-state-") as temp:
            fixture = make_fixture(Path(temp))
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

    def test_packager_rejects_wrong_runtime_or_unsafe_layered_package(self) -> None:
        with tempfile.TemporaryDirectory(prefix="world-builder-v2-inputs-") as temp:
            fixture = make_fixture(Path(temp), linux_os="Windows")
            wrong_runtime = run_packager(*fixture)
            self.assertNotEqual(0, wrong_runtime.returncode)
            self.assertIn('Linux JRE must report OS_NAME="Linux"', wrong_runtime.stderr)

        with tempfile.TemporaryDirectory(prefix="world-builder-v2-layered-") as temp:
            fixture = make_fixture(Path(temp))
            layered_package = fixture[2]
            (layered_package / "unsafe-link").symlink_to(layered_package / "manifest.json")
            unsafe_layered = run_packager(*fixture)
            self.assertNotEqual(0, unsafe_layered.returncode)
            self.assertIn("must not contain symbolic links", unsafe_layered.stderr)

        with tempfile.TemporaryDirectory(prefix="world-builder-v2-path-") as temp:
            fixture = make_fixture(Path(temp))
            write(fixture[2] / "terrain/CON.txt", "Windows device path\n")
            unsafe_path = run_packager(*fixture)
            self.assertNotEqual(0, unsafe_path.returncode)
            self.assertIn("Windows-unsafe staged package path", unsafe_path.stderr)

        with tempfile.TemporaryDirectory(prefix="world-builder-v2-jre-link-") as temp:
            fixture = make_fixture(Path(temp))
            outside = Path(temp) / "external-runtime-file"
            write(outside, "must not be followed\n")
            (fixture[3] / "lib/external-link").symlink_to(outside)
            unsafe_runtime = run_packager(*fixture)
            self.assertNotEqual(0, unsafe_runtime.returncode)
            self.assertIn("broken or external symbolic link", unsafe_runtime.stderr)

    def test_archives_are_complete_v2_only_verified_and_launchable(self) -> None:
        with tempfile.TemporaryDirectory(prefix="world-builder-v2-package-") as temp:
            base = Path(temp)
            fixture = make_fixture(base)
            standalone, core, _, _, _ = fixture
            result = run_packager(*fixture)
            self.assertEqual(0, result.returncode, result.stdout + result.stderr)

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
                        prefix + "PLAYER-ASSET-SOURCES.txt",
                        prefix + "EDITOR-ICON-CREDITS.txt",
                        prefix + "builder-runtime/Client_Base/Open_RSC_Client.jar",
                        prefix + "builder-runtime/server/core.jar",
                        prefix + "builder-runtime/server/plugins.jar",
                        prefix + "builder-runtime/server/inc/sqlite/myworld_seed.db",
                        prefix + "builder-runtime/launcher/world-builder-tools.jar",
                        prefix + "builder-runtime/layered-world/package/manifest.json",
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
                        "/updates/",
                        "/exports/",
                        "/backups/",
                        "/receipts/",
                        "/logs/",
                        "world_builder.db",
                        "world-builder.credential",
                        "credentials.txt",
                        "uid.dat",
                        "clientSettings.conf",
                        "builder-runtime/server/ipbans.txt",
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
                    self.assertEqual("signed-layered-v1", identity["worldCoordinateModel"])
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
                    self.assertIn("--layered-profile spoiled-milk-replacement", start)
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
            self.assertIn("launch\n", start_call)
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
            self.assertIn("legacy or unidentified", legacy.stderr)

            write(package / "workspace/layered-review.json", "{}\n")
            restarted = subprocess.run(
                [str(package / "Start World Builder.sh")],
                cwd=base,
                env=environment,
                text=True,
                capture_output=True,
            )
            self.assertEqual(0, restarted.returncode, restarted.stdout + restarted.stderr)
            self.assertIn("run\n", calls.read_text(encoding="utf-8"))

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
