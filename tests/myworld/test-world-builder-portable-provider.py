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

    def test_versioned_provider_package_selects_inventoried_full_mapping(self):
        with tempfile.TemporaryDirectory(prefix="portable-provider-package-") as temp:
            base = Path(temp)
            installation = base / "World Builder 2"
            provider = base / "world-builder-provider"
            installation.mkdir()
            full = provider / "item-visuals-full-v1.json"
            self.write_json(full, {"schemaVersion": 1,
                "manifestType": "world-builder-item-visual-mapping",
                "provider": {}, "assetProviders": {}, "selection": {},
                "itemVisuals": []})
            self.write_json(provider / "package-manifest-v1.json", {
                "schemaVersion": 1,
                "manifestType": "world-builder-item-visual-provider-package",
                "providerDirectory": "world-builder-provider",
                "catalogSha256": "a" * 64,
                "files": [{"path": full.name, "role": "full-item-visual-manifest",
                    "size": full.stat().st_size,
                    "sha256": hashlib.sha256(full.read_bytes()).hexdigest()}],
            })
            before = snapshot(provider)

            report = self.discover(installation, provider)

            self.assertEqual("explicit", report["status"])
            self.assertEqual(str(full), report["candidates"][0]["itemVisuals"])
            self.assertEqual(before, snapshot(provider))

            server = base / "separate-server"
            server.mkdir()
            imported = self.run_cli(
                "import-item-provider",
                "--installation-root", installation,
                "--source-root", server,
                "--item-visuals", full,
            )
            self.assertEqual(0, imported.returncode, imported.stderr)
            local = json.loads(imported.stdout)
            local_root = Path(local["root"])
            self.assertTrue((local_root / "package-manifest-v1.json").is_file())
            self.assertTrue((local_root / full.name).is_file())
            self.assertEqual(before, snapshot(provider))
            reloaded = self.discover(installation, server)
            self.assertEqual("local", reloaded["status"])
            self.assertEqual(str(local_root / full.name),
                reloaded["candidates"][0]["itemVisuals"])

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

    def test_byte_identical_server_archive_mirror_collapses_to_richer_client_layout(self):
        with tempfile.TemporaryDirectory(prefix="portable-provider-mirror-") as temp:
            base = Path(temp)
            installation = base / "World Builder 2"
            source = base / "server"
            installation.mkdir()
            client = source / "Client_Base/Cache/video"
            server = source / "server/conf/server/data"
            for root in (client, server):
                root.mkdir(parents=True)
                (root / "Authentic_Sprites.orsc").write_bytes(b"same-authentic")
                (root / "Custom_Sprites.osar").write_bytes(b"same-custom")
            spritepacks = client / "spritepacks"
            spritepacks.mkdir()
            (spritepacks / "Menus.osar").write_bytes(b"menus")
            definitions = source / "server/conf/server/defs"
            self.write_json(definitions / "ItemDefs.json", {
                "item": [{"id": 3309, "name": "Mirrored item"}]
            })

            report = self.discover(installation, source)

            self.assertEqual("recognized", report["status"])
            self.assertEqual("legacy-client-base-cache-video",
                report["selectedProfileId"])
            self.assertEqual(1, len(report["candidates"]))
            self.assertEqual(str(spritepacks),
                report["candidates"][0]["spritepacks"])

    def test_conflicting_server_archive_mirror_remains_ambiguous(self):
        with tempfile.TemporaryDirectory(prefix="portable-provider-conflict-") as temp:
            base = Path(temp)
            installation = base / "World Builder 2"
            source = base / "server"
            installation.mkdir()
            client = source / "Client_Base/Cache/video"
            server = source / "server/conf/server/data"
            for root in (client, server):
                root.mkdir(parents=True)
                (root / "Authentic_Sprites.orsc").write_bytes(b"same-authentic")
            (client / "Custom_Sprites.osar").write_bytes(b"client-custom")
            (server / "Custom_Sprites.osar").write_bytes(b"server-custom")
            definitions = source / "server/conf/server/defs"
            self.write_json(definitions / "ItemDefs.json", {
                "item": [{"id": 3309, "name": "Conflicting item"}]
            })

            report = self.discover(installation, source)

            self.assertEqual("ambiguous", report["status"])
            self.assertIsNone(report["selectedProfileId"])
            self.assertEqual(2, len(report["candidates"]))

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
            catalog_document = json.loads(catalog_before)
            self.assertEqual(2, catalog_document["schemaVersion"])
            self.assertRegex(
                catalog_document["providers"][0]["sourceDiscoveryFingerprintSha256"],
                r"^[0-9a-f]{64}$",
            )
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

            exported = self.run_cli(
                "export-item-provider-diagnostic",
                "--installation-root", installation,
                "--source-root", source,
            )
            self.assertEqual(0, exported.returncode, exported.stderr)
            diagnostic_path = Path(json.loads(exported.stdout)["diagnosticPath"])
            diagnostic_before = diagnostic_path.read_bytes()
            diagnostic = json.loads(diagnostic_before)
            self.assertEqual("world-builder-provider-cache-diagnostic",
                             diagnostic["manifestType"])
            self.assertEqual("hit", diagnostic["cacheStatus"])
            self.assertFalse(diagnostic["sourcePathsIncluded"])
            self.assertNotIn(str(source), diagnostic_before.decode("utf-8"))
            self.assertNotIn(str(provider), diagnostic_before.decode("utf-8"))
            repeated_export = self.run_cli(
                "export-item-provider-diagnostic",
                "--installation-root", installation,
                "--source-root", source,
            )
            self.assertEqual(0, repeated_export.returncode, repeated_export.stderr)
            self.assertEqual(exported.stdout, repeated_export.stdout)
            self.assertEqual(diagnostic_before, diagnostic_path.read_bytes())

            refused_reset = self.run_cli(
                "reset-item-provider-cache",
                "--installation-root", installation,
                "--source-root", source,
                "--confirm", "RESET",
            )
            self.assertNotEqual(0, refused_reset.returncode)
            self.assertEqual("local", self.discover(installation, source)["status"])
            reset = self.run_cli(
                "reset-item-provider-cache",
                "--installation-root", installation,
                "--source-root", source,
                "--confirm", "RESET PROVIDER CACHE",
            )
            self.assertEqual(0, reset.returncode, reset.stderr)
            reset_report = json.loads(reset.stdout)
            self.assertTrue(reset_report["changed"])
            self.assertEqual(1, reset_report["removedAssociations"])
            self.assertTrue(Path(reset_report["backup"]).is_file())
            self.assertTrue(provider.is_dir())
            after_reset = self.discover(installation, source)
            self.assertEqual("recognized", after_reset["status"])
            self.assertEqual("miss", after_reset["cacheStatus"])
            rebuilt = self.run_cli(*command)
            self.assertEqual(0, rebuilt.returncode, rebuilt.stderr)
            self.assertEqual(first_summary["providerId"],
                             json.loads(rebuilt.stdout)["providerId"])

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

            isolated_reset = self.run_cli(
                "reset-item-provider-cache",
                "--installation-root", installation,
                "--source-root", source,
                "--confirm", "RESET PROVIDER CACHE",
            )
            self.assertEqual(0, isolated_reset.returncode, isolated_reset.stderr)
            self.assertEqual(1, json.loads(isolated_reset.stdout)["removedAssociations"])
            self.assertEqual("recognized", self.discover(installation, source)["status"])
            self.assertEqual("local", self.discover(installation, second_source)["status"])
            restored = self.run_cli(*command)
            self.assertEqual(0, restored.returncode, restored.stderr)

            # A change to authoritative discovered definition evidence must not
            # reuse the path-associated provider. The old immutable provider is
            # preserved while the recognized source becomes a regeneration input.
            self.write_json(definitions / "ItemDefsCustom.json", {
                "items": [
                    {"id": 3310, "name": "Second item, changed"},
                    {"id": 3311, "name": "New item"},
                ]
            })
            stale = self.discover(installation, source)
            self.assertEqual("recognized", stale["status"])
            self.assertEqual("stale", stale["cacheStatus"])
            self.assertIn("Server content changed", stale["summary"])
            self.assertTrue(provider.is_dir())

            refreshed = self.run_cli(*command)
            self.assertEqual(0, refreshed.returncode, refreshed.stderr)
            refreshed_provider = Path(json.loads(refreshed.stdout)["root"])
            self.assertNotEqual(provider, refreshed_provider)
            self.assertTrue(provider.is_dir())
            self.assertTrue(refreshed_provider.is_dir())
            refreshed_discovery = self.discover(installation, source)
            self.assertEqual("local", refreshed_discovery["status"])
            self.assertEqual("hit", refreshed_discovery["cacheStatus"])
            self.assertEqual(refreshed_provider,
                Path(refreshed_discovery["candidates"][0]["root"]))

    def test_corrupt_and_legacy_cache_records_are_preserved_but_never_selected(self):
        with tempfile.TemporaryDirectory(prefix="portable-provider-cache-safety-") as temp:
            base = Path(temp)
            installation = base / "World Builder 2"
            source = base / "server"
            installation.mkdir()
            definitions = source / "server/conf/server/defs"
            self.write_json(definitions / "ItemDefs.json", {
                "item": [{"id": 3309, "name": "Cache fixture"}]
            })
            video = source / "Client_Base/Cache/video"
            video.mkdir(parents=True)
            authentic = video / "Authentic_Sprites.orsc"
            authentic.write_bytes(b"authentic archive")
            imported = self.run_cli(
                "import-item-provider", "--installation-root", installation,
                "--source-root", source, "--definitions", definitions,
                "--authentic-archive", authentic,
            )
            self.assertEqual(0, imported.returncode, imported.stderr)
            provider = Path(json.loads(imported.stdout)["root"])
            provider_before = snapshot(provider)
            mapping_before = (provider / "item-visuals.json").read_bytes()

            # Provider payload drift makes the cache corrupt. Discovery reports
            # it and falls back to the still-recognizable read-only source.
            (provider / "item-visuals.json").write_bytes(b"corrupt\n")
            corrupt = self.discover(installation, source)
            self.assertEqual("recognized", corrupt["status"])
            self.assertEqual("corrupt", corrupt["cacheStatus"])
            self.assertIn("cache is corrupt", corrupt["summary"])
            self.assertNotEqual(provider_before, snapshot(provider))

            corrupt_before = snapshot(provider)
            repair = self.run_cli(
                "import-item-provider", "--installation-root", installation,
                "--source-root", source, "--definitions", definitions,
                "--authentic-archive", authentic,
            )
            self.assertNotEqual(0, repair.returncode)
            self.assertEqual(corrupt_before, snapshot(provider))

            # A legacy path-only association is intentionally stale even when
            # its payload is restored; it cannot silently regain authority.
            (provider / "item-visuals.json").write_bytes(mapping_before)
            catalog_path = installation / "providers/catalog.json"
            catalog = json.loads(catalog_path.read_text(encoding="utf-8"))
            catalog["schemaVersion"] = 1
            for record in catalog["providers"]:
                record.pop("sourceDiscoveryFingerprintSha256", None)
            self.write_json(catalog_path, catalog)
            legacy = self.discover(installation, source)
            self.assertEqual("recognized", legacy["status"])
            self.assertEqual("stale", legacy["cacheStatus"])
            self.assertTrue(provider.is_dir())

            # An unsafe catalog path is corruption, not an absent cache. It is
            # preserved and cannot be replaced by a subsequent import.
            outside = base / "outside-catalog.json"
            outside.write_bytes(catalog_path.read_bytes())
            catalog_path.unlink()
            catalog_path.symlink_to(outside)
            unsafe = self.discover(installation, source)
            self.assertEqual("recognized", unsafe["status"])
            self.assertEqual("corrupt", unsafe["cacheStatus"])
            repair = self.run_cli(
                "import-item-provider", "--installation-root", installation,
                "--source-root", source, "--definitions", definitions,
                "--authentic-archive", authentic,
            )
            self.assertNotEqual(0, repair.returncode)
            self.assertTrue(catalog_path.is_symlink())
            self.assertEqual(catalog_path.read_bytes(), outside.read_bytes())

            # A malformed regular catalog can be recovered explicitly. Its
            # exact bytes are backed up, while provider directories remain.
            catalog_path.unlink()
            malformed_catalog = b"{malformed provider catalog\n"
            catalog_path.write_bytes(malformed_catalog)
            reset = self.run_cli(
                "reset-item-provider-cache",
                "--installation-root", installation,
                "--source-root", source,
                "--confirm", "RESET PROVIDER CACHE",
            )
            self.assertEqual(0, reset.returncode, reset.stderr)
            reset_report = json.loads(reset.stdout)
            self.assertTrue(reset_report["changed"])
            self.assertTrue(reset_report["corruptCatalogRecovered"])
            self.assertEqual(malformed_catalog,
                             Path(reset_report["backup"]).read_bytes())
            self.assertEqual([], json.loads(catalog_path.read_text())["providers"])
            self.assertTrue(provider.is_dir())


if __name__ == "__main__":
    unittest.main()
