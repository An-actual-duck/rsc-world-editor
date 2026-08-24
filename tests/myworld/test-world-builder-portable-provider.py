#!/usr/bin/env python3
"""Focused neutral provider discovery and local guided-import coverage."""

import hashlib
import json
import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
JAR = ROOT / "output/world-builder-tools/world-builder-tools.jar"


def snapshot(root: Path) -> dict[str, tuple]:
    result = {}
    for path in sorted(root.rglob("*")):
        relative = path.relative_to(root).as_posix()
        if path.is_symlink():
            result[relative] = ("link", os.readlink(path))
        elif path.is_dir():
            result[relative] = ("dir",)
        else:
            stat = path.stat()
            result[relative] = (
                "file",
                stat.st_size,
                stat.st_mtime_ns,
                hashlib.sha256(path.read_bytes()).hexdigest(),
            )
    return result


def mapping(records=None) -> dict:
    return {
        "schemaVersion": 1,
        "manifestType": "world-builder-item-visual-mapping",
        "itemVisuals": records or [
            {
                "itemId": 3309,
                "name": "Portable item",
                "logicalSpriteLocation": None,
                "sourceRole": "unresolved",
                "sourceAsset": None,
                "sourceAssetSha256": None,
                "authenticSpriteId": None,
                "customSpriteSubspace": None,
                "customSpriteEntry": None,
                "externalPng": None,
                "pictureMask": 0,
                "blueMask": 0,
            }
        ],
    }


class PortableProviderTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        subprocess.run([str(ROOT / "scripts/build-tools.sh")], check=True, cwd=ROOT)

    def run_cli(self, *args) -> subprocess.CompletedProcess:
        return subprocess.run(
            ["java", "-jar", str(JAR), *map(str, args)],
            cwd=ROOT,
            text=True,
            capture_output=True,
        )

    def discover(self, installation: Path, source: Path) -> dict:
        result = self.run_cli(
            "discover-item-provider",
            "--installation-root", installation,
            "--source-root", source,
        )
        self.assertEqual(0, result.returncode, result.stderr)
        return json.loads(result.stdout)

    @staticmethod
    def write_json(path: Path, value: dict):
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(value, sort_keys=True) + "\n", encoding="utf-8")

    def test_explicit_portable_provider_is_preferred_and_missing_assets_are_fail_soft(self):
        with tempfile.TemporaryDirectory(prefix="portable-provider-explicit-") as temp:
            base = Path(temp)
            installation = base / "World Builder 2"
            source = base / "server"
            installation.mkdir()
            package = source / "world-builder-provider"
            self.write_json(package / "item-visuals.json", mapping())
            # A competing recognized layout must not override the explicit package.
            video = source / "Client_Base/Cache/video"
            video.mkdir(parents=True)
            (video / "Custom_Sprites.osar").write_bytes(b"malformed-but-discoverable")
            before = snapshot(source)

            report = self.discover(installation, source)

            self.assertEqual("explicit", report["status"])
            self.assertEqual("explicit-provider", report["selectedProfileId"])
            self.assertEqual(before, snapshot(source))

    def test_common_openrsc_layouts_are_recognized_without_execution(self):
        for relative in ("Cache/video", "Client_Base/Cache/video"):
            with self.subTest(relative=relative), tempfile.TemporaryDirectory(
                prefix="portable-provider-layout-"
            ) as temp:
                base = Path(temp)
                installation = base / "World Builder 2"
                source = base / "server"
                installation.mkdir()
                video = source / relative
                video.mkdir(parents=True)
                (video / "Authentic_Sprites.orsc").write_bytes(b"archive")
                definitions = source / "server/conf/server/defs"
                self.write_json(definitions / "ItemDefs.json", {
                    "item": [{"id": 3309, "name": "Recognized item"}]
                })
                # Executables are inert evidence and are never invoked.
                marker = source / "server.jar"
                marker.write_text("not executable data", encoding="utf-8")
                before = snapshot(source)

                report = self.discover(installation, source)

                self.assertEqual("recognized", report["status"])
                self.assertEqual(1, len(report["candidates"]))
                self.assertEqual(before, snapshot(source))

    def test_ambiguous_automatic_layout_lists_every_candidate_without_selecting(self):
        with tempfile.TemporaryDirectory(prefix="portable-provider-ambiguous-") as temp:
            base = Path(temp)
            installation = base / "World Builder 2"
            source = base / "server"
            installation.mkdir()
            for relative in ("Cache/video", "Client_Base/Cache/video"):
                video = source / relative
                video.mkdir(parents=True)
                (video / "Custom_Sprites.osar").write_bytes(relative.encode())
            before = snapshot(source)

            report = self.discover(installation, source)

            self.assertEqual("ambiguous", report["status"])
            self.assertIsNone(report["selectedProfileId"])
            self.assertEqual(
                ["legacy-cache-video", "legacy-client-base-cache-video"],
                [candidate["profileId"] for candidate in report["candidates"]],
            )
            self.assertEqual(before, snapshot(source))

    def test_multiple_definition_roots_are_reported_as_ambiguous(self):
        with tempfile.TemporaryDirectory(prefix="portable-provider-definitions-") as temp:
            base = Path(temp)
            installation = base / "World Builder 2"
            source = base / "server"
            installation.mkdir()
            video = source / "Client_Base/Cache/video"
            video.mkdir(parents=True)
            (video / "Authentic_Sprites.orsc").write_bytes(b"archive")
            for relative in ("server/conf/server/defs", "server/data/definitions"):
                self.write_json(source / relative / "ItemDefs.json", {
                    "items": [{"id": 3309, "name": relative}]
                })

            report = self.discover(installation, source)

            self.assertEqual("ambiguous", report["status"])
            self.assertIsNone(report["selectedProfileId"])
            self.assertEqual(2, len(report["candidates"]))
            self.assertEqual(
                ["legacy-client-base-cache-video-definitions-1",
                 "legacy-client-base-cache-video-definitions-2"],
                [candidate["profileId"] for candidate in report["candidates"]],
            )

    def test_guided_import_generates_unresolved_records_and_reloads_deterministically(self):
        with tempfile.TemporaryDirectory(prefix="portable-provider-guided-") as temp:
            base = Path(temp)
            installation = base / "World Builder 2"
            source = base / "server"
            installation.mkdir()
            definitions = source / "server/data/definitions"
            self.write_json(definitions / "ItemDefs.json", {
                "item": [
                    {"id": 3310, "name": "Second item"},
                    {"id": 3309, "name": "First item"},
                ]
            })
            self.write_json(definitions / "ItemDefsCustom.json", {
                "items": [{"id": 3310, "name": "Second item, final"}]
            })
            authentic = source / "Client_Base/Cache/video/Authentic_Sprites.orsc"
            authentic.parent.mkdir(parents=True)
            authentic.write_bytes(b"authentic archive bytes")
            custom = authentic.parent / "Custom_Sprites.osar"
            custom.write_bytes(b"custom archive bytes")
            spritepacks = authentic.parent / "spritepacks"
            spritepacks.mkdir()
            (spritepacks / "Items.osar").write_bytes(b"spritepack bytes")
            external = authentic.parent / "external-items"
            external.mkdir()
            (external / "3309.png").write_bytes(b"png placeholder bytes")
            before = snapshot(source)

            command = (
                "import-item-provider",
                "--installation-root", installation,
                "--source-root", source,
                "--definitions", definitions,
                "--authentic-archive", authentic,
                "--custom-archive", custom,
                "--spritepacks", spritepacks,
                "--external-items", external,
            )
            first = self.run_cli(*command)
            self.assertEqual(0, first.returncode, first.stderr)
            first_summary = json.loads(first.stdout)
            provider = Path(first_summary["root"])
            generated = json.loads((provider / "item-visuals.json").read_text())
            self.assertEqual([3309, 3310], [item["itemId"] for item in generated["itemVisuals"]])
            self.assertEqual("Second item, final", generated["itemVisuals"][1]["name"])
            for item in generated["itemVisuals"]:
                self.assertEqual("unresolved", item["sourceRole"])
                self.assertIsNone(item["sourceAsset"])
                self.assertIsNone(item["externalPng"])
                self.assertEqual(0, item["pictureMask"])
                self.assertEqual(0, item["blueMask"])
            self.assertEqual(b"authentic archive bytes", (
                provider / "assets/Authentic_Sprites.orsc").read_bytes())
            self.assertEqual(before, snapshot(source))

            catalog = installation / "providers/catalog.json"
            catalog_before = catalog.read_bytes()
            second = self.run_cli(*command)
            self.assertEqual(0, second.returncode, second.stderr)
            second_summary = json.loads(second.stdout)
            self.assertEqual(first_summary["providerId"], second_summary["providerId"])
            self.assertEqual(catalog_before, catalog.read_bytes())
            self.assertEqual(before, snapshot(source))

            discovered = self.discover(installation, source)
            self.assertEqual("local", discovered["status"])
            self.assertEqual("explicit-provider", discovered["selectedProfileId"])
            self.assertEqual(provider, Path(discovered["candidates"][0]["root"]))

            # One content-addressed provider can remain associated with more than
            # one equivalent local source; importing the second must not evict the first.
            second_source = base / "equivalent-server"
            shutil.copytree(source, second_source)
            third_command = tuple(
                second_source if value == source else
                second_source / value.relative_to(source)
                if isinstance(value, Path) and value.is_relative_to(source)
                else value
                for value in command
            )
            third = self.run_cli(*third_command)
            self.assertEqual(0, third.returncode, third.stderr)
            self.assertEqual(first_summary["providerId"], json.loads(third.stdout)["providerId"])
            self.assertEqual("local", self.discover(installation, source)["status"])
            self.assertEqual("local", self.discover(installation, second_source)["status"])


if __name__ == "__main__":
    unittest.main()
