#!/usr/bin/env python3
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
UPDATER = ROOT / "release/updater/Update World Builder.sh"
WINDOWS_UPDATER = ROOT / "release/updater/Update World Builder.ps1"
WINDOWS_START = ROOT / "release/updater/Start World Builder.cmd"
PACKAGER = ROOT / "scripts/package-release.sh"
PACKAGE_NAME = "Spoiled Milk World Builder"


class WorldBuilderUpdaterTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="world-builder-updater-")
        self.base = Path(self.temp.name)
        self.install = self.base / PACKAGE_NAME
        self.install.mkdir()
        shutil.copy2(UPDATER, self.install / UPDATER.name)
        (self.install / UPDATER.name).chmod(0o755)
        (self.install / "VERSION.txt").write_text("v1.1.0\n", encoding="utf-8")
        (self.install / "SOURCE-COMMIT.txt").write_text("a" * 40 + "\n", encoding="utf-8")
        (self.install / "CORE-SOURCE-COMMIT.txt").write_text("b" * 40 + "\n", encoding="utf-8")
        (self.install / "application.txt").write_text("old application\n", encoding="utf-8")
        workspace = self.install / "workspace"
        (workspace / "working/server").mkdir(parents=True)
        (workspace / "credentials").mkdir()
        (workspace / "working/server/map.dat").write_bytes(b"authored map bytes")
        (workspace / "credentials/secret.txt").write_text("private\n", encoding="utf-8")
        self.workspace_snapshot = self.snapshot(workspace)

    def tearDown(self):
        self.temp.cleanup()

    @staticmethod
    def snapshot(root: Path):
        result = {}
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
    def write_manifest(package: Path):
        lines = []
        for path in sorted(package.rglob("*")):
            if not path.is_file() or path.name == "PACKAGE-MANIFEST.sha256":
                continue
            relative = "./" + path.relative_to(package).as_posix()
            lines.append(f"{hashlib.sha256(path.read_bytes()).hexdigest()}  {relative}\n")
        (package / "PACKAGE-MANIFEST.sha256").write_text("".join(lines), encoding="utf-8")

    def make_release(self, version="v1.1.1", valid_checksum=True):
        release_root = self.base / "release"
        package = self.base / "package" / PACKAGE_NAME
        package.mkdir(parents=True)
        shutil.copy2(UPDATER, package / UPDATER.name)
        (package / UPDATER.name).chmod(0o755)
        (package / "VERSION.txt").write_text(version + "\n", encoding="utf-8")
        (package / "SOURCE-COMMIT.txt").write_text("c" * 40 + "\n", encoding="utf-8")
        (package / "CORE-SOURCE-COMMIT.txt").write_text("d" * 40 + "\n", encoding="utf-8")
        (package / "application.txt").write_text("new application\n", encoding="utf-8")
        self.write_manifest(package)

        asset_name = f"rsc-world-editor-{version}-linux-x64.zip"
        download = release_root / version
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
        api.write_text(json.dumps({"tag_name": version}), encoding="utf-8")
        return api.as_uri(), release_root.as_uri()

    def run_updater(self, api_url, download_url, *arguments):
        environment = os.environ.copy()
        environment.update(
            {
                "WORLD_BUILDER_RELEASE_API_URL": api_url,
                "WORLD_BUILDER_RELEASE_DOWNLOAD_URL": download_url,
            }
        )
        return subprocess.run(
            [str(self.install / UPDATER.name), *arguments],
            cwd=self.install,
            env=environment,
            text=True,
            capture_output=True,
        )

    def test_verified_update_replaces_application_and_preserves_workspace(self):
        api_url, download_url = self.make_release()
        result = self.run_updater(api_url, download_url)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("updated successfully to v1.1.1", result.stdout)
        self.assertEqual("v1.1.1", (self.install / "VERSION.txt").read_text().strip())
        self.assertEqual("new application\n", (self.install / "application.txt").read_text())
        self.assertEqual(self.workspace_snapshot, self.snapshot(self.install / "workspace"))
        self.assertFalse((self.install / ".world-builder-update.lock").exists())

    def test_bad_archive_checksum_refuses_without_changing_installation(self):
        api_url, download_url = self.make_release(valid_checksum=False)
        result = self.run_updater(api_url, download_url)
        self.assertNotEqual(0, result.returncode)
        self.assertIn("checksum does not match", result.stderr)
        self.assertEqual("v1.1.0", (self.install / "VERSION.txt").read_text().strip())
        self.assertEqual("old application\n", (self.install / "application.txt").read_text())
        self.assertEqual(self.workspace_snapshot, self.snapshot(self.install / "workspace"))

    def test_active_builder_process_refuses_before_update(self):
        api_url, download_url = self.make_release()
        run = self.install / "workspace/run"
        run.mkdir()
        (run / "server.pid").write_text(str(os.getpid()) + "\n", encoding="utf-8")
        expected_workspace = self.snapshot(self.install / "workspace")
        result = self.run_updater(api_url, download_url)
        self.assertNotEqual(0, result.returncode)
        self.assertIn("Close World Builder before updating", result.stderr)
        self.assertEqual("v1.1.0", (self.install / "VERSION.txt").read_text().strip())
        self.assertEqual(expected_workspace, self.snapshot(self.install / "workspace"))

    def test_windows_and_packaging_contracts_are_present(self):
        powershell = WINDOWS_UPDATER.read_text(encoding="utf-8")
        windows_start = WINDOWS_START.read_text(encoding="utf-8")
        packager = PACKAGER.read_text(encoding="utf-8")
        for snippet in (
            "Get-FileHash",
            "PACKAGE-MANIFEST.sha256",
            '"workspace", "updates"',
            "Close World Builder before updating",
        ):
            self.assertIn(snippet, powershell)
        self.assertIn("Update World Builder.cmd", windows_start)
        self.assertIn("rsc-world-editor-$VERSION-windows-x64.zip", packager)
        self.assertIn("write_package_manifest", packager)
        self.assertIn("CORE-SOURCE-COMMIT.txt", packager)


if __name__ == "__main__":
    unittest.main()
