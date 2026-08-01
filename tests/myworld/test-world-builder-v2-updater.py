#!/usr/bin/env python3
"""Exercise the isolated World Builder 2 update transaction and identity gate."""

from __future__ import annotations

import hashlib
import json
import os
import shutil
import subprocess
import tempfile
import unittest
import zipfile
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
UPDATER_ASSETS = ROOT / "release/updater-v2"
UPDATER = UPDATER_ASSETS / "Update World Builder.sh"
WINDOWS_UPDATER = UPDATER_ASSETS / "Update World Builder.ps1"
WINDOWS_START = UPDATER_ASSETS / "Start World Builder.cmd"
PACKAGE_NAME = "Spoiled Milk World Builder 2"
PRODUCT_ID = "rsc-world-editor-v2"


def release_tag(version: str) -> str:
    return f"{PRODUCT_ID}-{version.removeprefix('v')}"


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
        "worldCoordinateModel": "signed-layered-v1",
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
        self.install = self.base / PACKAGE_NAME
        self.install.mkdir()
        self.write_application(self.install, "v0.1.0", "old application\n")
        (self.install / "application.txt").chmod(0o444)
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
        self.workspace_snapshot = self.snapshot(workspace)
        self.personal_snapshot = (self.install / "personal-note.txt").read_bytes()

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
    ) -> None:
        for asset in UPDATER_ASSETS.iterdir():
            if asset.is_file():
                shutil.copy2(asset, package / asset.name)
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
        (package / "application.txt").write_text(application, encoding="utf-8")
        managed = [
            path
            for path in sorted(package.iterdir())
            if path.is_file()
            and path.name not in {"PACKAGE-MANIFEST.sha256", "personal-note.txt"}
        ]
        self.write_manifest(package, managed)

    def make_release(
        self,
        version: str = "v0.1.1",
        *,
        valid_checksum: bool = True,
        product_id: str = PRODUCT_ID,
        durable_manifest_path: bool = False,
        untracked_file: bool = False,
    ) -> tuple[str, str]:
        release_root = self.base / "release"
        package = self.base / "package" / PACKAGE_NAME
        package.mkdir(parents=True)
        self.write_application(
            package,
            version,
            "new application\n",
            product_id=product_id,
        )
        (package / "new-only.txt").write_text("new file\n", encoding="utf-8")
        if untracked_file:
            (package / "untracked.txt").write_text("not in manifest\n", encoding="utf-8")
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
        download = release_root / tag
        download.mkdir(parents=True)
        archive_path = download / asset_name
        with zipfile.ZipFile(archive_path, "w", zipfile.ZIP_DEFLATED) as archive:
            for path in sorted(package.rglob("*")):
                if path.is_file():
                    archive.write(path, path.relative_to(package.parent))
        digest = hashlib.sha256(archive_path.read_bytes()).hexdigest()
        if not valid_checksum:
            digest = "0" * 64
        (download / "SHA256SUMS.txt").write_text(
            f"{digest}  {asset_name}\n", encoding="utf-8"
        )
        api = self.base / "latest.json"
        api.write_text(json.dumps({"tag_name": tag}), encoding="utf-8")
        return api.as_uri(), release_root.as_uri()

    def write_channel_tag(self, tag: str) -> str:
        api = self.base / "latest.json"
        api.write_text(json.dumps({"tag_name": tag}), encoding="utf-8")
        return api.as_uri()

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

    def assert_durable_state_unchanged(self) -> None:
        self.assertEqual(
            self.workspace_snapshot, self.snapshot(self.install / "workspace")
        )
        self.assertEqual(
            self.personal_snapshot, (self.install / "personal-note.txt").read_bytes()
        )
        self.assertFalse((self.install / ".world-builder-v2-update.lock").exists())

    def test_verified_v2_update_replaces_managed_files_only(self) -> None:
        api_url, download_url = self.make_release()
        result = self.run_updater(api_url, download_url)
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("updated successfully to v0.1.1", result.stdout)
        self.assertEqual("v0.1.1", (self.install / "VERSION.txt").read_text().strip())
        self.assertEqual(
            "new application\n",
            (self.install / "application.txt").read_text(encoding="utf-8"),
        )
        self.assertTrue((self.install / "new-only.txt").is_file())
        self.assert_durable_state_unchanged()

    def test_v1_release_tag_is_never_an_eligible_v2_update(self) -> None:
        api_url = self.write_channel_tag("v1.1.0")
        result = self.run_updater(api_url, (self.base / "missing").as_uri())
        self.assertNotEqual(0, result.returncode)
        self.assertIn("not on the rsc-world-editor-v2 update channel", result.stderr)
        self.assertEqual("v0.1.0", (self.install / "VERSION.txt").read_text().strip())
        self.assert_durable_state_unchanged()

    def test_downloaded_wrong_product_identity_is_refused(self) -> None:
        api_url, download_url = self.make_release(product_id="rsc-world-editor-v1")
        result = self.run_updater(api_url, download_url)
        self.assertNotEqual(0, result.returncode)
        self.assertIn("not an exact rsc-world-editor-v2 release", result.stderr)
        self.assertEqual("old application\n", (self.install / "application.txt").read_text())
        self.assert_durable_state_unchanged()

    def test_bad_checksum_refuses_before_installation(self) -> None:
        api_url, download_url = self.make_release(valid_checksum=False)
        result = self.run_updater(api_url, download_url)
        self.assertNotEqual(0, result.returncode)
        self.assertIn("checksum does not match", result.stderr)
        self.assertEqual("old application\n", (self.install / "application.txt").read_text())
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
        self.assertFalse((self.install / "updates").exists())

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

    def test_older_channel_release_does_not_downgrade(self) -> None:
        (self.install / "application.txt").chmod(0o644)
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
            "  if [[ \"$argument\" == */extracted/'Spoiled Milk World Builder 2'/. ]]; then\n"
            "    source_root=\"${argument%/.}\"\n"
            "    destination=\"${@: -1}\"\n"
            "    /bin/cp -a \"$source_root/application.txt\" \"$destination/\"\n"
            "    /bin/cp -a \"$source_root/new-only.txt\" \"$destination/\"\n"
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
        self.assertEqual("old application\n", (self.install / "application.txt").read_text())
        self.assertFalse((self.install / "new-only.txt").exists())
        self.assertEqual(
            before_manifest, (self.install / "PACKAGE-MANIFEST.sha256").read_bytes()
        )
        self.assert_durable_state_unchanged()

    def test_windows_updater_carries_equivalent_identity_and_rollback_guards(self) -> None:
        powershell = WINDOWS_UPDATER.read_text(encoding="utf-8")
        windows_start = WINDOWS_START.read_text(encoding="utf-8")
        for snippet in (
            "Read-ReleaseIdentity",
            "Assert-SafeArchive",
            "Assert-ExactPackageInventory",
            "Remove-ManagedFiles",
            "Get-FileHash",
            "PACKAGE-MANIFEST.sha256",
            "rsc-world-editor-v2",
            "rsc-world-editor-v1",
            "legacyWorkspaceMigration",
            "Close World Builder 2 before updating",
            "RollbackArmed",
        ):
            self.assertIn(snippet, powershell)
        self.assertIn("Update World Builder.cmd", windows_start)
        self.assertIn("WORLD_BUILDER_SKIP_UPDATE", windows_start)


if __name__ == "__main__":
    unittest.main()
