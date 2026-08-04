#!/usr/bin/env python3
"""Exercise the isolated World Builder 2 update transaction and identity gate."""

from __future__ import annotations

import hashlib
import http.server
import json
import os
import shutil
import stat
import subprocess
import tempfile
import threading
import unittest
import urllib.parse
import zipfile
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
UPDATER_ASSETS = ROOT / "release/updater-v2"
UPDATER = UPDATER_ASSETS / "Update World Builder.sh"
WINDOWS_UPDATER = UPDATER_ASSETS / "Update World Builder.ps1"
WINDOWS_START = UPDATER_ASSETS / "Start World Builder.cmd"
PACKAGE_NAME = "World Builder 2"
PRODUCT_ID = "rsc-world-editor-v2"
WORLD_SOURCE_IDENTITY = "target-adaptive-v1"
RUNTIME_ALLOWLIST = ROOT / "release/world-builder-v2/RUNTIME-ASSET-ALLOWLIST.txt"
NEW_MANAGED_PATH = "EDITOR-ICON-CREDITS.txt"
POWERSHELL = os.environ.get("WORLD_BUILDER_PWSH") or shutil.which("pwsh")


class QuietHttpHandler(http.server.SimpleHTTPRequestHandler):
    def log_message(self, format: str, *args: object) -> None:
        pass


def release_tag(version: str) -> str:
    return f"{PRODUCT_ID}-{version.removeprefix('v')}"


def channel_release(
    tag: str,
    *,
    draft: bool = False,
    prerelease: bool = False,
    assets: list[dict[str, str]] | None = None,
) -> dict[str, object]:
    return {
        "tag_name": tag,
        "draft": draft,
        "prerelease": prerelease,
        "assets": assets or [],
    }


def channel_decoys() -> list[dict[str, object]]:
    return [
        channel_release("v1.1.0"),
        channel_release(
            f"{PRODUCT_ID}-99.0.0-alpha.1", draft=True, prerelease=True
        ),
        channel_release(f"{PRODUCT_ID}-not-semver", prerelease=True),
        channel_release(f"{PRODUCT_ID.upper()}-100.0.0"),
        channel_release(f"{PRODUCT_ID}-01.0.0"),
        channel_release(f"{PRODUCT_ID}-0.1.2-alpha.01", prerelease=True),
        channel_release(f"{PRODUCT_ID}-0.0.9"),
        channel_release(f"{PRODUCT_ID}-0.1.1-alpha.1", prerelease=True),
        {
            "tag_name": f"{PRODUCT_ID}-101.0.0",
            "prerelease": False,
            "assets": [],
        },
    ]


def identity_text(
    version: str,
    source_commit: str,
    core_commit: str,
    *,
    product_id: str = PRODUCT_ID,
) -> str:
    identity = {
        "schemaVersion": 1,
        "productId": product_id,
        "productGeneration": 2,
        "displayName": PACKAGE_NAME,
        "updateChannel": PRODUCT_ID,
        "releaseTag": release_tag(version),
        "artifactPrefix": PRODUCT_ID,
        "worldSourceIdentity": WORLD_SOURCE_IDENTITY,
        "automaticUpgradeFromProductIds": [PRODUCT_ID],
        "legacyProductId": "rsc-world-editor-v1",
        "legacyFinalTag": "v1.1.0",
        "legacyWorkspaceMigration": False,
        "version": version,
        "sourceCommit": source_commit,
        "coreSourceCommit": core_commit,
    }
    return json.dumps(identity, indent=2, separators=(",", ": ")) + "\n"


class WorldBuilderV2UpdaterTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory(prefix="world-builder-v2-updater-")
        self.base = Path(self.temp.name)
        self.compatibility_command = self.base / "compatibility-ok"
        self.compatibility_command.write_text(
            "#!/usr/bin/env bash\nexit 0\n", encoding="utf-8"
        )
        self.compatibility_command.chmod(0o755)
        self.install = self.base / PACKAGE_NAME
        self.install.mkdir()
        self.write_application(self.install, "v0.1.0", "old application\n")
        (self.install / "README.txt").chmod(0o444)
        (self.install / "personal-note.txt").write_text(
            "unmanaged and preserved\n", encoding="utf-8"
        )
        workspace = self.install / "workspace"
        (workspace / "working/server").mkdir(parents=True)
        (workspace / "credentials").mkdir()
        (workspace / "working/server/map.dat").write_bytes(b"authored layered map")
        (workspace / "credentials/secret.txt").write_text(
            "private\n", encoding="utf-8"
        )
        project_ids = (
            "11111111-1111-4111-8111-111111111111",
            "22222222-2222-4222-8222-222222222222",
        )
        for index, project_id in enumerate(project_ids, 1):
            project = self.install / "projects" / project_id
            (project / "source/layered-baseline/package").mkdir(parents=True)
            (project / "working/layered-world/package").mkdir(parents=True)
            for directory in (
                "exports", "backups", "receipts", "diagnostics", "logs", "run"
            ):
                (project / directory).mkdir()
            (project / "project.json").write_text(
                json.dumps({"project": index}) + "\n", encoding="utf-8"
            )
            (project / "working/layered-world/package/terrain.bin").write_bytes(
                f"project-{index}-terrain".encode()
            )
        (self.install / "project-registry.json").write_text(
            json.dumps({"projects": list(project_ids)}) + "\n", encoding="utf-8"
        )
        (self.install / "active-project.json").write_text(
            json.dumps({"projectId": project_ids[0]}) + "\n", encoding="utf-8"
        )
        for relative in (
            "exports/global.keep", "backups/global.keep", "receipts/global.keep",
            "diagnostics/global.keep", "logs/global.keep", "settings/editor.keep",
            "recovery/pending.keep", "updates/user-recovery/keep.txt",
            "unknown-user-folder/keep.bin",
        ):
            path = self.install / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(("durable:" + relative).encode())
        self.durable_targets = (
            "workspace", "projects", "project-registry.json", "active-project.json",
            "exports", "backups", "receipts", "diagnostics", "logs", "settings",
            "recovery", "updates", "unknown-user-folder", "personal-note.txt",
        )
        self.durable_snapshot = self.snapshot_targets()

    def tearDown(self) -> None:
        self.temp.cleanup()

    @staticmethod
    def snapshot(root: Path) -> dict[str, tuple[str, ...]]:
        result: dict[str, tuple[str, ...]] = {}
        for path in sorted(root.rglob("*")):
            relative = path.relative_to(root).as_posix()
            if path.is_dir():
                result[relative] = ("dir",)
            else:
                result[relative] = (
                    "file",
                    hashlib.sha256(path.read_bytes()).hexdigest(),
                )
        return result

    def snapshot_targets(self) -> dict[str, object]:
        result: dict[str, object] = {}
        for relative in self.durable_targets:
            path = self.install / relative
            result[relative] = self.snapshot(path) if path.is_dir() else path.read_bytes()
        return result

    @staticmethod
    def write_manifest(package: Path, paths: list[Path] | None = None) -> None:
        if paths is None:
            paths = [
                path
                for path in sorted(package.rglob("*"))
                if path.is_file() and path.name != "PACKAGE-MANIFEST.sha256"
            ]
        lines = []
        for path in paths:
            relative = "./" + path.relative_to(package).as_posix()
            lines.append(
                f"{hashlib.sha256(path.read_bytes()).hexdigest()}  {relative}\n"
            )
        (package / "PACKAGE-MANIFEST.sha256").write_text(
            "".join(lines), encoding="utf-8"
        )

    def write_application(
        self,
        package: Path,
        version: str,
        application: str,
        *,
        product_id: str = PRODUCT_ID,
        compatibility_exit: int = 0,
    ) -> None:
        for name in (
            "Start World Builder.sh", "Start World Builder.cmd",
            "Update World Builder.sh", "Update World Builder.cmd",
            "Update World Builder.ps1",
        ):
            shutil.copy2(UPDATER_ASSETS / name, package / name)
        (package / "Update World Builder.sh").chmod(0o755)
        (package / "Start World Builder.sh").chmod(0o755)
        source_commit = "a" * 40 if application.startswith("old") else "c" * 40
        core_commit = "b" * 40 if application.startswith("old") else "d" * 40
        (package / "VERSION.txt").write_text(version + "\n", encoding="utf-8")
        (package / "SOURCE-COMMIT.txt").write_text(
            source_commit + "\n", encoding="utf-8"
        )
        (package / "CORE-SOURCE-COMMIT.txt").write_text(
            core_commit + "\n", encoding="utf-8"
        )
        (package / "RELEASE-IDENTITY.json").write_text(
            identity_text(
                version,
                source_commit,
                core_commit,
                product_id=product_id,
            ),
            encoding="utf-8",
        )
        required_payloads = {
            "Import Map Changes.sh": "#!/usr/bin/env bash\nexit 0\n",
            "Import Map Changes.cmd": "@exit /b 0\r\n",
            "Recover Map Transaction.sh": "#!/usr/bin/env bash\nexit 0\n",
            "Recover Map Transaction.cmd": "@exit /b 0\r\n",
            "Undo Last Map Import.sh": "#!/usr/bin/env bash\nexit 0\n",
            "Undo Last Map Import.cmd": "@exit /b 0\r\n",
            "builder-runtime/Client_Base/Open_RSC_Client.jar": "client\n",
            "builder-runtime/server/core.jar": "server\n",
            "builder-runtime/server/plugins.jar": "plugins\n",
            "builder-runtime/server/inc/sqlite/world_builder_seed.db": "seed\n",
            "builder-runtime/server/world-builder.conf": "server_name: World Builder 2 Runtime\n",
            "builder-runtime/server/conf/world-builder/adaptive-runtime-capability-v1.json": "{}\n",
            "builder-runtime/launcher/world-builder-tools.jar": "tools\n",
            "runtime/bin/java": f"#!/usr/bin/env bash\nexit {compatibility_exit}\n",
            "runtime/bin/java.exe": "runtime\n",
        }
        for relative, contents in required_payloads.items():
            path = package / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(contents, encoding="utf-8")
        for relative in (
            "Import Map Changes.sh",
            "Recover Map Transaction.sh",
            "Undo Last Map Import.sh",
            "runtime/bin/java",
        ):
            (package / relative).chmod(0o755)
        (package / "README.txt").write_text(application, encoding="utf-8")
        shutil.copy2(RUNTIME_ALLOWLIST, package / "RUNTIME-ASSET-ALLOWLIST.txt")
        managed_relatives = {
            "Start World Builder.sh", "Start World Builder.cmd",
            "Update World Builder.sh", "Update World Builder.cmd",
            "Update World Builder.ps1",
        }
        managed_relatives.update(required_payloads)
        managed_relatives.update(
            {
                "VERSION.txt",
                "SOURCE-COMMIT.txt",
                "CORE-SOURCE-COMMIT.txt",
                "RELEASE-IDENTITY.json",
                "README.txt",
                "RUNTIME-ASSET-ALLOWLIST.txt",
            }
        )
        managed = [package / relative for relative in sorted(managed_relatives)]
        self.write_manifest(package, managed)

    def make_release(
        self,
        version: str = "v0.1.1",
        *,
        valid_checksum: bool = True,
        product_id: str = PRODUCT_ID,
        durable_manifest_path: bool = False,
        untracked_file: bool = False,
        non_executable_launcher: bool = False,
        renamed_world_payload: bool = False,
        compatibility_exit: int = 0,
    ) -> tuple[str, str]:
        release_root = self.base / "release"
        package = self.base / "package" / PACKAGE_NAME
        package.mkdir(parents=True)
        self.write_application(
            package,
            version,
            "new application\n",
            product_id=product_id,
            compatibility_exit=compatibility_exit,
        )
        if non_executable_launcher:
            (package / "Start World Builder.sh").chmod(0o644)
        (package / NEW_MANAGED_PATH).write_text("new managed file\n", encoding="utf-8")
        if untracked_file:
            (package / "untracked.txt").write_text("not in manifest\n", encoding="utf-8")
        if renamed_world_payload:
            disguised = package / "builder-runtime/launcher/schema/disguised.schema.json"
            disguised.parent.mkdir(parents=True, exist_ok=True)
            disguised.write_text(
                '{"packageType":"layered-world","levels":[]}\n', encoding="utf-8"
            )
        managed = [
            path
            for path in sorted(package.rglob("*"))
            if path.is_file()
            and path.name not in {"PACKAGE-MANIFEST.sha256", "untracked.txt"}
        ]
        if durable_manifest_path:
            durable = package / "workspace/replace-me.txt"
            durable.parent.mkdir()
            durable.write_text("unsafe\n", encoding="utf-8")
            managed.append(durable)
        self.write_manifest(package, managed)

        tag = release_tag(version)
        asset_name = f"{PRODUCT_ID}-{version.removeprefix('v')}-linux-x64.zip"
        windows_asset_name = (
            f"{PRODUCT_ID}-{version.removeprefix('v')}-windows-x64.zip"
        )
        download = release_root / tag
        download.mkdir(parents=True)
        archive_path = download / asset_name
        windows_archive_path = download / windows_asset_name
        for target in (archive_path, windows_archive_path):
            with zipfile.ZipFile(target, "w", zipfile.ZIP_DEFLATED) as archive:
                for path in sorted(package.rglob("*")):
                    if path.is_file():
                        archive.write(path, path.relative_to(package.parent))
        digest = hashlib.sha256(archive_path.read_bytes()).hexdigest()
        windows_digest = hashlib.sha256(windows_archive_path.read_bytes()).hexdigest()
        if not valid_checksum:
            digest = "0" * 64
        (download / "SHA256SUMS.txt").write_text(
            f"{digest}  {asset_name}\n"
            f"{windows_digest}  {windows_asset_name}\n",
            encoding="utf-8",
        )
        api = self.base / "latest.json"
        api.write_text(
            json.dumps(
                [
                    channel_release(
                        tag,
                        prerelease="-alpha." in version,
                        assets=[
                            {
                                "name": windows_asset_name,
                                "browser_download_url": windows_archive_path.as_uri(),
                            },
                            {
                                "name": "SHA256SUMS.txt",
                                "browser_download_url": (
                                    download / "SHA256SUMS.txt"
                                ).as_uri(),
                            },
                        ],
                    )
                ],
                indent=2,
            ),
            encoding="utf-8",
        )
        return api.as_uri(), release_root.as_uri()

    def write_channel_releases(self, releases: list[dict[str, object]]) -> str:
        api = self.base / "latest.json"
        api.write_text(json.dumps(releases, indent=2), encoding="utf-8")
        return api.as_uri()

    def write_channel_tag(self, tag: str) -> str:
        return self.write_channel_releases([channel_release(tag)])

    def start_local_release_service(
        self,
        version: str = "v0.1.1",
        decoys: list[dict[str, object]] | None = None,
    ) -> tuple[http.server.ThreadingHTTPServer, threading.Thread, str]:
        handler = lambda *args, **kwargs: QuietHttpHandler(  # noqa: E731
            *args, directory=str(self.base), **kwargs
        )
        server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), handler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        port = server.server_address[1]
        tag = release_tag(version)
        windows_asset_name = (
            f"{PRODUCT_ID}-{version.removeprefix('v')}-windows-x64.zip"
        )
        release_path = f"release/{tag}"
        base_url = f"http://127.0.0.1:{port}"
        selected = channel_release(
            tag,
            prerelease="-alpha." in version,
            assets=[
                {
                    "name": windows_asset_name,
                    "browser_download_url": (
                        f"{base_url}/{release_path}/"
                        f"{urllib.parse.quote(windows_asset_name)}"
                    ),
                },
                {
                    "name": "SHA256SUMS.txt",
                    "browser_download_url": (
                        f"{base_url}/{release_path}/SHA256SUMS.txt"
                    ),
                },
            ],
        )
        api = [*(decoys or []), selected]
        (self.base / "latest.json").write_text(
            json.dumps(api, indent=2), encoding="utf-8"
        )
        return server, thread, f"{base_url}/latest.json"

    def run_updater(
        self,
        api_url: str,
        download_url: str,
        *arguments: str,
        path_prefix: Path | None = None,
    ) -> subprocess.CompletedProcess[str]:
        environment = os.environ.copy()
        environment.update(
            {
                "WORLD_BUILDER_V2_RELEASE_API_URL": api_url,
                "WORLD_BUILDER_V2_RELEASE_DOWNLOAD_URL": download_url,
            }
        )
        if path_prefix is not None:
            environment["PATH"] = f"{path_prefix}:{environment['PATH']}"
        return subprocess.run(
            [str(self.install / UPDATER.name), *arguments],
            cwd=self.install,
            env=environment,
            text=True,
            capture_output=True,
        )

    def assert_durable_state_unchanged(self, *, allow_recovery_stage: bool = False) -> None:
        expected = dict(self.durable_snapshot)
        actual = self.snapshot_targets()
        if allow_recovery_stage:
            expected.pop("updates")
            actual.pop("updates")
        self.assertEqual(expected, actual)
        if not allow_recovery_stage:
            self.assertFalse((self.install / ".world-builder-v2-update.lock").exists())

    def test_verified_v2_update_replaces_managed_files_only(self) -> None:
        api_url, download_url = self.make_release()
        result = self.run_updater(api_url, download_url)
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("updated successfully to v0.1.1", result.stdout)
        self.assertEqual("v0.1.1", (self.install / "VERSION.txt").read_text().strip())
        self.assertEqual(
            "new application\n",
            (self.install / "README.txt").read_text(encoding="utf-8"),
        )
        self.assertTrue((self.install / NEW_MANAGED_PATH).is_file())
        self.assert_durable_state_unchanged()

    def test_historical_pre_adaptive_workspace_refuses_automatic_migration(
        self,
    ) -> None:
        workspace = self.snapshot(self.install / "workspace")
        personal = (self.install / "personal-note.txt").read_bytes()
        for relative in ("projects", "project-registry.json", "active-project.json"):
            path = self.install / relative
            if path.is_dir():
                shutil.rmtree(path)
            else:
                path.unlink()

        result = self.run_updater(
            (self.base / "must-not-be-read.json").as_uri(),
            (self.base / "must-not-be-read").as_uri(),
        )

        self.assertNotEqual(0, result.returncode)
        self.assertIn("historical pre-adaptive World Builder 2", result.stderr)
        self.assertIn("cannot be relabelled or migrated automatically", result.stderr)
        self.assertEqual(workspace, self.snapshot(self.install / "workspace"))
        self.assertEqual(personal, (self.install / "personal-note.txt").read_bytes())
        self.assertEqual("v0.1.0", (self.install / "VERSION.txt").read_text().strip())

    def test_downloaded_renamed_world_payload_is_outside_exact_allowlist(self) -> None:
        api_url, download_url = self.make_release(renamed_world_payload=True)
        result = self.run_updater(api_url, download_url)
        self.assertNotEqual(0, result.returncode)
        self.assertIn("content-neutral application allowlist", result.stderr)
        self.assertEqual("old application\n", (self.install / "README.txt").read_text())
        self.assert_durable_state_unchanged()

    def test_selected_project_incompatibility_rolls_back_application_only(
        self,
    ) -> None:
        before_manifest = (self.install / "PACKAGE-MANIFEST.sha256").read_bytes()
        api_url, download_url = self.make_release(compatibility_exit=37)
        result = self.run_updater(api_url, download_url)
        self.assertNotEqual(0, result.returncode)
        self.assertIn("selected adaptive project is incompatible", result.stderr)
        self.assertIn("previous World Builder 2 application files were restored", result.stderr)
        self.assertEqual("old application\n", (self.install / "README.txt").read_text())
        self.assertEqual(
            before_manifest, (self.install / "PACKAGE-MANIFEST.sha256").read_bytes()
        )
        self.assert_durable_state_unchanged()

    def test_v2_channel_selects_newer_alpha_beside_frozen_v1_and_decoys(
        self,
    ) -> None:
        version = "v0.1.1-alpha.2"
        _, download_url = self.make_release(version)
        selected = channel_release(release_tag(version), prerelease=True)
        api_url = self.write_channel_releases([selected, *channel_decoys()])

        result = self.run_updater(api_url, download_url)
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn(f"updated successfully to {version}", result.stdout)
        self.assertEqual(version, (self.install / "VERSION.txt").read_text().strip())
        self.assertEqual(
            "new application\n",
            (self.install / "README.txt").read_text(encoding="utf-8"),
        )
        self.assert_durable_state_unchanged()

    def test_v1_release_tag_is_never_an_eligible_v2_update(self) -> None:
        api_url = self.write_channel_tag("v1.1.0")
        result = self.run_updater(api_url, (self.base / "missing").as_uri())
        self.assertNotEqual(0, result.returncode)
        self.assertIn("contains no published valid rsc-world-editor-v2", result.stderr)
        self.assertEqual("v0.1.0", (self.install / "VERSION.txt").read_text().strip())
        self.assert_durable_state_unchanged()

    def test_downloaded_wrong_product_identity_is_refused(self) -> None:
        api_url, download_url = self.make_release(product_id="rsc-world-editor-v1")
        result = self.run_updater(api_url, download_url)
        self.assertNotEqual(0, result.returncode)
        self.assertIn("not an exact rsc-world-editor-v2 release", result.stderr)
        self.assertEqual("old application\n", (self.install / "README.txt").read_text())
        self.assert_durable_state_unchanged()

    def test_bad_checksum_refuses_before_installation(self) -> None:
        api_url, download_url = self.make_release(valid_checksum=False)
        result = self.run_updater(api_url, download_url)
        self.assertNotEqual(0, result.returncode)
        self.assertIn("checksum does not match", result.stderr)
        self.assertEqual("old application\n", (self.install / "README.txt").read_text())
        self.assert_durable_state_unchanged()

    def test_active_builder_process_refuses_before_network_or_update_state(self) -> None:
        run = self.install / "workspace/run"
        run.mkdir()
        (run / "server.pid").write_text(str(os.getpid()) + "\n", encoding="utf-8")
        expected_workspace = self.snapshot(self.install / "workspace")
        result = self.run_updater(
            (self.base / "missing.json").as_uri(),
            (self.base / "missing").as_uri(),
        )
        self.assertNotEqual(0, result.returncode)
        self.assertIn("Close World Builder 2 before updating", result.stderr)
        self.assertEqual(expected_workspace, self.snapshot(self.install / "workspace"))
        self.assertEqual(
            self.durable_snapshot["updates"], self.snapshot(self.install / "updates")
        )

    def test_installed_manifest_path_cannot_cross_a_symbolic_link(self) -> None:
        external = self.base / "external"
        external.mkdir()
        sentinel = external / "sentinel.txt"
        sentinel.write_text("outside installation\n", encoding="utf-8")
        (self.install / "unsafe-runtime-link").symlink_to(
            external, target_is_directory=True
        )
        digest = hashlib.sha256(sentinel.read_bytes()).hexdigest()
        with (self.install / "PACKAGE-MANIFEST.sha256").open(
            "a", encoding="utf-8"
        ) as manifest:
            manifest.write(f"{digest}  ./unsafe-runtime-link/sentinel.txt\n")

        result = self.run_updater(
            (self.base / "missing.json").as_uri(),
            (self.base / "missing").as_uri(),
        )
        self.assertNotEqual(0, result.returncode)
        self.assertIn("application manifest is missing or does not verify", result.stderr)
        self.assertEqual("outside installation\n", sentinel.read_text(encoding="utf-8"))
        self.assertEqual(
            self.durable_snapshot["updates"], self.snapshot(self.install / "updates")
        )

    def test_installed_manifest_must_own_the_complete_application(self) -> None:
        manifest = self.install / "PACKAGE-MANIFEST.sha256"
        lines = [
            line
            for line in manifest.read_text(encoding="utf-8").splitlines()
            if not line.endswith("./builder-runtime/server/plugins.jar")
        ]
        manifest.write_text("\n".join(lines) + "\n", encoding="utf-8")

        result = self.run_updater(
            (self.base / "missing.json").as_uri(),
            (self.base / "missing").as_uri(),
        )
        self.assertNotEqual(0, result.returncode)
        self.assertIn("Installed package manifest omits", result.stderr)
        self.assertEqual(
            self.durable_snapshot["updates"], self.snapshot(self.install / "updates")
        )
        self.assert_durable_state_unchanged()

    def test_durable_or_untracked_downloaded_content_is_refused(self) -> None:
        api_url, download_url = self.make_release(durable_manifest_path=True)
        durable = self.run_updater(api_url, download_url)
        self.assertNotEqual(0, durable.returncode)
        self.assertIn("manifest, inventory, or file verification failed", durable.stderr)
        self.assert_durable_state_unchanged()

        shutil.rmtree(self.base / "package")
        shutil.rmtree(self.base / "release")
        api_url, download_url = self.make_release(untracked_file=True)
        untracked = self.run_updater(api_url, download_url)
        self.assertNotEqual(0, untracked.returncode)
        self.assertIn("manifest, inventory, or file verification failed", untracked.stderr)
        self.assert_durable_state_unchanged()

    def test_downloaded_linux_launcher_must_remain_executable(self) -> None:
        api_url, download_url = self.make_release(non_executable_launcher=True)
        result = self.run_updater(api_url, download_url)
        self.assertNotEqual(0, result.returncode)
        self.assertIn("package file is not executable", result.stderr)
        self.assertEqual("v0.1.0", (self.install / "VERSION.txt").read_text().strip())
        self.assertEqual("old application\n", (self.install / "README.txt").read_text())
        self.assert_durable_state_unchanged()

    def test_archive_symbolic_link_is_refused_before_extraction(self) -> None:
        api_url, download_url = self.make_release()
        tag = release_tag("v0.1.1")
        archive_path = (
            self.base
            / "release"
            / tag
            / f"{PRODUCT_ID}-0.1.1-linux-x64.zip"
        )
        link = zipfile.ZipInfo(f"{PACKAGE_NAME}/unsafe-link")
        link.create_system = 3
        link.external_attr = (stat.S_IFLNK | 0o777) << 16
        with zipfile.ZipFile(archive_path, "a") as archive:
            archive.writestr(link, "../../outside-installation")
        checksums = archive_path.parent / "SHA256SUMS.txt"
        lines = checksums.read_text(encoding="utf-8").splitlines()
        lines[0] = (
            f"{hashlib.sha256(archive_path.read_bytes()).hexdigest()}  "
            f"{archive_path.name}"
        )
        checksums.write_text("\n".join(lines) + "\n", encoding="utf-8")

        result = self.run_updater(api_url, download_url)
        self.assertNotEqual(0, result.returncode)
        self.assertIn("unsafe or unexpected directory layout", result.stderr)
        self.assertFalse((self.base / "outside-installation").exists())
        self.assert_durable_state_unchanged()

    def test_older_channel_release_does_not_downgrade(self) -> None:
        (self.install / "README.txt").chmod(0o644)
        self.write_application(self.install, "v0.2.0", "old application\n")
        api_url = self.write_channel_tag(release_tag("v0.1.9"))
        result = self.run_updater(api_url, (self.base / "missing").as_uri())
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("no downgrade was performed", result.stdout)
        self.assertEqual("v0.2.0", (self.install / "VERSION.txt").read_text().strip())
        self.assert_durable_state_unchanged()

    def test_partial_install_failure_restores_exact_previous_managed_state(self) -> None:
        api_url, download_url = self.make_release()
        fake_bin = self.base / "fake-bin"
        fake_bin.mkdir()
        fake_cp = fake_bin / "cp"
        fake_cp.write_text(
            "#!/usr/bin/env bash\n"
            "set -euo pipefail\n"
            "for argument in \"$@\"; do\n"
            f"  if [[ \"$argument\" == */extracted/'{PACKAGE_NAME}'/{NEW_MANAGED_PATH} ]]; then\n"
            "    destination=\"${@: -1}\"\n"
            "    /bin/cp -a \"$argument\" \"$destination\"\n"
            "    exit 19\n"
            "  fi\n"
            "done\n"
            "exec /bin/cp \"$@\"\n",
            encoding="utf-8",
        )
        fake_cp.chmod(0o755)
        before_manifest = (self.install / "PACKAGE-MANIFEST.sha256").read_bytes()

        result = self.run_updater(
            api_url, download_url, path_prefix=fake_bin
        )
        self.assertNotEqual(0, result.returncode)
        self.assertIn("Unable to install", result.stderr)
        self.assertIn("previous World Builder 2 application files were restored", result.stderr)
        self.assertEqual("v0.1.0", (self.install / "VERSION.txt").read_text().strip())
        self.assertEqual("old application\n", (self.install / "README.txt").read_text())
        self.assertFalse((self.install / NEW_MANAGED_PATH).exists())
        self.assertEqual(
            before_manifest, (self.install / "PACKAGE-MANIFEST.sha256").read_bytes()
        )
        self.assert_durable_state_unchanged()

    def test_failed_emergency_restore_retains_recovery_state_and_blocks_launch(self) -> None:
        api_url, download_url = self.make_release()
        fake_bin = self.base / "rollback-failure-bin"
        fake_bin.mkdir()
        fake_cp = fake_bin / "cp"
        fake_cp.write_text(
            "#!/usr/bin/env bash\n"
            "set -euo pipefail\n"
            "for argument in \"$@\"; do\n"
            f"  if [[ \"$argument\" == */extracted/'{PACKAGE_NAME}'/{NEW_MANAGED_PATH} ]]; then\n"
            "    /bin/cp -a \"$argument\" \"${@: -1}\"\n"
            "    exit 19\n"
            "  fi\n"
            "  if [[ \"$argument\" == */backup/. ]]; then\n"
            "    destination=\"${@: -1}\"\n"
            "    /bin/cp -a \"$argument\" \"$destination\"\n"
            "    /bin/rm -f \"$destination/README.txt\"\n"
            "    exit 23\n"
            "  fi\n"
            "done\n"
            "exec /bin/cp \"$@\"\n",
            encoding="utf-8",
        )
        fake_cp.chmod(0o755)

        result = self.run_updater(api_url, download_url, path_prefix=fake_bin)
        self.assertNotEqual(0, result.returncode)
        normalized_error = " ".join(result.stderr.split())
        self.assertIn(
            "automatic rollback could not fully restore", normalized_error
        )
        lock = self.install / ".world-builder-v2-update.lock"
        self.assertTrue(lock.is_dir())
        recovery_stages = list((self.install / "updates").glob(".update-*/backup"))
        self.assertEqual(1, len(recovery_stages))
        self.assertTrue((recovery_stages[0] / "VERSION.txt").is_file())

        environment = {**os.environ, "WORLD_BUILDER_SKIP_UPDATE": "1"}
        launched = subprocess.run(
            [str(self.install / "Start World Builder.sh")],
            cwd=self.install,
            env=environment,
            text=True,
            capture_output=True,
        )
        self.assertNotEqual(0, launched.returncode)
        self.assertIn("update is already in progress", launched.stderr)
        self.assert_durable_state_unchanged(allow_recovery_stage=True)

    def test_windows_updater_carries_equivalent_identity_and_rollback_guards(self) -> None:
        powershell = WINDOWS_UPDATER.read_text(encoding="utf-8")
        windows_start = WINDOWS_START.read_text(encoding="utf-8")
        for snippet in (
            "Read-ReleaseIdentity",
            "Assert-SafeArchive",
            "Assert-ExactPackageInventory",
            "Assert-ApplicationAllowlist",
            "Remove-ManagedFiles",
            "Get-FileHash",
            "PACKAGE-MANIFEST.sha256",
            "rsc-world-editor-v2",
            "rsc-world-editor-v1",
            "legacyWorkspaceMigration",
            "target-adaptive-v1",
            "project-registry.json",
            "active-project.json",
            "--validate-only",
            "selected adaptive project is incompatible",
            "historical pre-adaptive World Builder 2",
            "Close World Builder 2 before updating",
            "RollbackArmed",
            "Select-NewestV2Release",
            "releases?per_page=100",
        ):
            self.assertIn(snippet, powershell)
        linux = UPDATER.read_text(encoding="utf-8")
        for snippet in (
            "target-adaptive-v1", "project-registry.json", "active-project.json",
            "--validate-only",
            "selected adaptive project is incompatible",
            "historical pre-adaptive World Builder 2",
            "validate_application_paths",
        ):
            self.assertIn(snippet, linux)
        self.assertNotIn("Spoiled Milk World Builder 2", powershell)
        self.assertNotIn("Spoiled Milk World Builder 2", linux)
        self.assertIn("Update World Builder.cmd", windows_start)
        self.assertIn("WORLD_BUILDER_SKIP_UPDATE", windows_start)
        self.assertIn(".world-builder-v2-update.lock", windows_start)
        self.assertIn(":update_in_progress", windows_start)

    @unittest.skipUnless(POWERSHELL, "set WORLD_BUILDER_PWSH to test PowerShell")
    def test_powershell_transaction_runs_against_local_release_service(self) -> None:
        self.make_release()
        server, thread, api_url = self.start_local_release_service()
        try:
            environment = {
                **os.environ,
                "WORLD_BUILDER_V2_RELEASE_API_URL": api_url,
                "WORLD_BUILDER_V2_COMPATIBILITY_JAVA": str(self.compatibility_command),
            }
            result = subprocess.run(
                [
                    str(POWERSHELL),
                    "-NoProfile",
                    "-NonInteractive",
                    "-File",
                    str(self.install / "Update World Builder.ps1"),
                ],
                cwd=self.install,
                env=environment,
                text=True,
                capture_output=True,
            )
        finally:
            server.shutdown()
            server.server_close()
            thread.join(timeout=5)

        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("updated successfully to v0.1.1", result.stdout)
        self.assertEqual("v0.1.1", (self.install / "VERSION.txt").read_text().strip())
        self.assertEqual("new application\n", (self.install / "README.txt").read_text())
        self.assertTrue((self.install / NEW_MANAGED_PATH).is_file())
        self.assert_durable_state_unchanged()

    @unittest.skipUnless(POWERSHELL, "set WORLD_BUILDER_PWSH to test PowerShell")
    def test_powershell_selects_newer_alpha_beside_frozen_v1_and_decoys(
        self,
    ) -> None:
        version = "v0.1.1-alpha.2"
        self.make_release(version)
        server, thread, api_url = self.start_local_release_service(
            version, channel_decoys()
        )
        try:
            environment = {
                **os.environ,
                "WORLD_BUILDER_V2_RELEASE_API_URL": api_url,
                "WORLD_BUILDER_V2_COMPATIBILITY_JAVA": str(self.compatibility_command),
            }
            result = subprocess.run(
                [
                    str(POWERSHELL),
                    "-NoProfile",
                    "-NonInteractive",
                    "-File",
                    str(self.install / "Update World Builder.ps1"),
                ],
                cwd=self.install,
                env=environment,
                text=True,
                capture_output=True,
            )
        finally:
            server.shutdown()
            server.server_close()
            thread.join(timeout=5)

        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn(f"updated successfully to {version}", result.stdout)
        self.assertEqual(version, (self.install / "VERSION.txt").read_text().strip())
        self.assertEqual(
            "new application\n",
            (self.install / "README.txt").read_text(encoding="utf-8"),
        )
        self.assert_durable_state_unchanged()

    @unittest.skipUnless(POWERSHELL, "set WORLD_BUILDER_PWSH to test PowerShell")
    def test_powershell_channel_does_not_downgrade(self) -> None:
        (self.install / "README.txt").chmod(0o644)
        self.write_application(self.install, "v0.2.0", "old application\n")
        version = "v0.1.9"
        self.make_release(version)
        server, thread, api_url = self.start_local_release_service(version)
        try:
            environment = {
                **os.environ,
                "WORLD_BUILDER_V2_RELEASE_API_URL": api_url,
                "WORLD_BUILDER_V2_COMPATIBILITY_JAVA": str(self.compatibility_command),
            }
            result = subprocess.run(
                [
                    str(POWERSHELL),
                    "-NoProfile",
                    "-NonInteractive",
                    "-File",
                    str(self.install / "Update World Builder.ps1"),
                ],
                cwd=self.install,
                env=environment,
                text=True,
                capture_output=True,
            )
        finally:
            server.shutdown()
            server.server_close()
            thread.join(timeout=5)

        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("no downgrade was performed", result.stdout)
        self.assertEqual("v0.2.0", (self.install / "VERSION.txt").read_text().strip())
        self.assertEqual(
            "old application\n",
            (self.install / "README.txt").read_text(encoding="utf-8"),
        )
        self.assert_durable_state_unchanged()

    @unittest.skipUnless(POWERSHELL, "set WORLD_BUILDER_PWSH to test PowerShell")
    def test_powershell_partial_failure_restores_previous_managed_files(self) -> None:
        self.make_release()
        server, thread, api_url = self.start_local_release_service()
        harness = self.base / "inject-copy-failure.ps1"
        harness.write_text(
            "param([string]$Updater)\n"
            "$script:Injected = $false\n"
            "function Copy-Item {\n"
            "  [CmdletBinding()]\n"
            "  param(\n"
            "    [Parameter(Mandatory=$true)][string[]]$LiteralPath,\n"
            "    [Parameter(Mandatory=$true)][string]$Destination,\n"
            "    [switch]$Recurse,\n"
            "    [switch]$Force\n"
            "  )\n"
            "  Microsoft.PowerShell.Management\\Copy-Item @PSBoundParameters\n"
            f"  if (-not $script:Injected -and ($LiteralPath -join '') -like '*extracted*{NEW_MANAGED_PATH}') {{\n"
            "    $script:Injected = $true\n"
            "    throw 'injected copy failure'\n"
            "  }\n"
            "}\n"
            "& $Updater\n",
            encoding="utf-8",
        )
        before_manifest = (self.install / "PACKAGE-MANIFEST.sha256").read_bytes()
        try:
            environment = {
                **os.environ,
                "WORLD_BUILDER_V2_RELEASE_API_URL": api_url,
                "WORLD_BUILDER_V2_COMPATIBILITY_JAVA": str(self.compatibility_command),
            }
            result = subprocess.run(
                [
                    str(POWERSHELL),
                    "-NoProfile",
                    "-NonInteractive",
                    "-File",
                    str(harness),
                    str(self.install / "Update World Builder.ps1"),
                ],
                cwd=self.install,
                env=environment,
                text=True,
                capture_output=True,
            )
        finally:
            server.shutdown()
            server.server_close()
            thread.join(timeout=5)

        self.assertNotEqual(0, result.returncode)
        self.assertIn("injected copy failure", result.stderr)
        self.assertIn("previous World Builder 2 application files were restored", result.stdout)
        self.assertEqual("v0.1.0", (self.install / "VERSION.txt").read_text().strip())
        self.assertEqual("old application\n", (self.install / "README.txt").read_text())
        self.assertFalse((self.install / NEW_MANAGED_PATH).exists())
        self.assertEqual(
            before_manifest, (self.install / "PACKAGE-MANIFEST.sha256").read_bytes()
        )
        self.assert_durable_state_unchanged()

    @unittest.skipUnless(POWERSHELL, "set WORLD_BUILDER_PWSH to test PowerShell")
    def test_powershell_failed_restore_retains_recovery_state(self) -> None:
        self.make_release()
        server, thread, api_url = self.start_local_release_service()
        harness = self.base / "inject-rollback-failure.ps1"
        harness.write_text(
            "param([string]$Updater)\n"
            "$script:InstallFailed = $false\n"
            "function Copy-Item {\n"
            "  [CmdletBinding()]\n"
            "  param(\n"
            "    [Parameter(Mandatory=$true)][string[]]$LiteralPath,\n"
            "    [Parameter(Mandatory=$true)][string]$Destination,\n"
            "    [switch]$Recurse,\n"
            "    [switch]$Force\n"
            "  )\n"
            "  Microsoft.PowerShell.Management\\Copy-Item @PSBoundParameters\n"
            "  $Source = $LiteralPath -join ''\n"
            f"  if (-not $script:InstallFailed -and $Source -like '*extracted*{NEW_MANAGED_PATH}') {{\n"
            "    $script:InstallFailed = $true\n"
            "    throw 'injected installation failure'\n"
            "  }\n"
            "  if ($script:InstallFailed -and $Source -like '*backup*') {\n"
            "    throw 'injected rollback failure'\n"
            "  }\n"
            "}\n"
            "& $Updater\n",
            encoding="utf-8",
        )
        try:
            environment = {
                **os.environ,
                "WORLD_BUILDER_V2_RELEASE_API_URL": api_url,
                "WORLD_BUILDER_V2_COMPATIBILITY_JAVA": str(self.compatibility_command),
            }
            result = subprocess.run(
                [
                    str(POWERSHELL),
                    "-NoProfile",
                    "-NonInteractive",
                    "-File",
                    str(harness),
                    str(self.install / "Update World Builder.ps1"),
                ],
                cwd=self.install,
                env=environment,
                text=True,
                capture_output=True,
            )
        finally:
            server.shutdown()
            server.server_close()
            thread.join(timeout=5)

        self.assertNotEqual(0, result.returncode)
        normalized_error = " ".join(result.stderr.split())
        self.assertIn("automatic rollback could not fully", normalized_error)
        self.assertIn("restore the previous application", normalized_error)
        self.assertTrue((self.install / ".world-builder-v2-update.lock").is_dir())
        recovery_stages = list((self.install / "updates").glob(".update-*/backup"))
        self.assertEqual(1, len(recovery_stages))
        self.assertTrue((recovery_stages[0] / "VERSION.txt").is_file())
        self.assert_durable_state_unchanged(allow_recovery_stage=True)


if __name__ == "__main__":
    unittest.main()
