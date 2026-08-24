#!/usr/bin/env python3
"""Canonical cross-repository project content bundle fixture coverage."""

import hashlib
import gzip
import importlib.util
import json
import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
FIXTURE = ROOT / "tests/fixtures/project-content-bundle-v1/bundle"
FIXTURE_V2 = ROOT / "tests/fixtures/project-content-bundle-v2/bundle"
GENERATOR = ROOT / "scripts/generate-project-content-bundle-v1-fixture.py"
GENERATOR_V2 = ROOT / "scripts/generate-project-content-bundle-v2-fixture.py"
CLASSES = ROOT / "output/world-builder-tools/classes"


def decode_osar(payload: bytes) -> dict[str, dict[str, list[dict]]]:
    """Independent strict decoder for the runtime Unpacker wire format."""
    data = gzip.decompress(payload)
    offset = 0

    def take(count: int) -> bytes:
        nonlocal offset
        if count < 0 or offset + count > len(data):
            raise ValueError("truncated OSAR")
        value = data[offset:offset + count]
        offset += count
        return value

    def u8() -> int:
        return take(1)[0]

    def u16() -> int:
        return int.from_bytes(take(2), "big")

    def i16() -> int:
        return int.from_bytes(take(2), "big", signed=True)

    def name() -> str:
        value = bytearray()
        while (character := u8()) != 0:
            value.append(character)
        return value.decode("latin-1")

    result = {}
    for _ in range(u8()):
        subspace = name()
        entries = {}
        for _ in range(u16()):
            entry_name = name()
            entry_type = u8()
            layer = u8() if entry_type in (1, 2, 3) else None
            frames = []
            frame_count = u8()
            palette = [int.from_bytes(take(3), "big") for _ in range(u8() + 1)]
            for _ in range(frame_count):
                width, height = u16(), u16()
                shifted, offset_x, offset_y = u8(), i16(), i16()
                bound_width, bound_height = u16(), u16()
                indices = list(take(width * height))
                frames.append({
                    "width": width, "height": height, "shifted": shifted,
                    "offsetX": offset_x, "offsetY": offset_y,
                    "boundWidth": bound_width, "boundHeight": bound_height,
                    "palette": palette,
                    "pixels": [palette[index] for index in indices],
                })
            entries[entry_name] = {
                "type": entry_type, "layer": layer, "frames": frames,
            }
        result[subspace] = entries
    if offset != len(data):
        raise ValueError("trailing OSAR data")
    return result


HARNESS = r'''
package com.openrsc.worldbuilder;

import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

public final class ProjectContentBundleFixtureHarness {
    private static void require(boolean condition, String label) {
        if (!condition) throw new IllegalStateException("missing " + label);
    }

    private static boolean contains(Map<String,Object> catalog, String family, long id) {
        @SuppressWarnings("unchecked") List<Object> values =
            (List<Object>)catalog.get(family);
        return values.contains(Long.valueOf(id));
    }

    public static void main(String[] args) throws Exception {
        WorldBuilderProjectContentBundle.Bundle bundle =
            WorldBuilderProjectContentBundle.read(Paths.get(args[0]));
        if (WorldBuilderProjectContentBundle.CAPABILITY_ID.equals(bundle.capabilityId)) {
            require(bundle.files.size() == 17, "closed 17-role successor inventory");
            require(bundle.itemVisuals.size() == 3, "exact item visual closure");
        } else {
            require(bundle.files.size() == 16, "closed 16-role legacy inventory");
        }
        require(contains(bundle.definitionCatalog, "tiles", 31), "floor 31");
        require(contains(bundle.definitionCatalog, "boundaries", 219), "wall 219");
        require(contains(bundle.definitionCatalog, "scenery", 59), "scenery 59");
        require(contains(bundle.definitionCatalog, "npcs", 846), "NPC 846");
        require(contains(bundle.definitionCatalog, "groundItems", 9000),
            "ground item 9000");
        System.out.println(bundle.definitionFingerprintSha256);
        System.out.println(bundle.assetFingerprintSha256);
        System.out.println(bundle.itemVisualFingerprintSha256);
        System.out.println(bundle.bundleFingerprintSha256);
    }
}
'''


class ProjectContentBundleFixtureTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        if not CLASSES.is_dir():
            subprocess.run([str(ROOT / "scripts/build-tools.sh")], check=True)
        cls.temp = tempfile.TemporaryDirectory(
            prefix="world-builder-content-bundle-fixture-"
        )
        source = Path(cls.temp.name) / "ProjectContentBundleFixtureHarness.java"
        source.write_text(HARNESS.strip() + "\n", encoding="utf-8")
        cls.harness_classes = Path(cls.temp.name) / "classes"
        cls.harness_classes.mkdir()
        subprocess.run(
            [
                "javac", "-encoding", "UTF-8", "-cp", str(CLASSES),
                "-d", str(cls.harness_classes), str(source),
            ],
            check=True,
        )

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()

    def read_with_java(self, root: Path) -> subprocess.CompletedProcess:
        classpath = os.pathsep.join((str(self.harness_classes), str(CLASSES)))
        return subprocess.run(
            [
                "java", "-cp", classpath,
                "com.openrsc.worldbuilder.ProjectContentBundleFixtureHarness",
                str(root),
            ],
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
        )

    def test_generator_reproduces_every_checked_in_byte(self):
        result = subprocess.run(
            ["python3", str(GENERATOR), "--check", str(FIXTURE)],
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
        )
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("PASS: canonical project-content-bundle-v1 fixture", result.stdout)

    def test_editor_reader_accepts_exact_fixture_and_expected_id_closure(self):
        manifest = json.loads((FIXTURE / "manifest.json").read_text(encoding="utf-8"))
        result = self.read_with_java(FIXTURE)
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertEqual(
            [
                manifest["definitionFingerprintSha256"],
                manifest["assetFingerprintSha256"],
                "0" * 64,
                manifest["bundleFingerprintSha256"],
            ],
            result.stdout.splitlines(),
        )
        relative_files = {
            path.relative_to(FIXTURE).as_posix()
            for path in FIXTURE.rglob("*") if path.is_file()
        }
        self.assertEqual(17, len(relative_files))
        self.assertFalse(any(path.endswith((".png", ".ob3")) for path in relative_files))

    def test_reader_rejects_fixture_payload_drift(self):
        with tempfile.TemporaryDirectory(prefix="content-bundle-drift-") as temp:
            changed = Path(temp) / "bundle"
            shutil.copytree(FIXTURE, changed)
            model = changed / "files/client/Cache/video/models.orsc"
            model.write_bytes(model.read_bytes() + b"drift")
            result = self.read_with_java(changed)
            self.assertNotEqual(0, result.returncode)
            self.assertIn("Content file differs from the exact manifest inventory", result.stderr)

    def test_successor_generator_and_reader_freeze_visual_mappings_and_masks(self):
        result = subprocess.run(
            ["python3", str(GENERATOR_V2), "--check", str(FIXTURE_V2)],
            text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
        )
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        manifest = json.loads((FIXTURE_V2 / "manifest.json").read_text(encoding="utf-8"))
        read = self.read_with_java(FIXTURE_V2)
        self.assertEqual(0, read.returncode, read.stdout + read.stderr)
        self.assertEqual(
            [manifest[key] for key in (
                "definitionFingerprintSha256", "assetFingerprintSha256",
                "itemVisualFingerprintSha256", "bundleFingerprintSha256",
            )],
            read.stdout.splitlines(),
        )
        visuals = manifest["itemVisuals"]
        self.assertEqual([9000, 9001, 9002], [value["itemId"] for value in visuals])
        self.assertEqual("asset.sprite.custom", visuals[0]["customSpriteAssetRole"])
        self.assertEqual(("items", "0"), (
            visuals[0]["customSpriteSubspace"], visuals[0]["customSpriteEntry"],
        ))
        self.assertEqual(417, visuals[1]["authenticSpriteId"])
        self.assertEqual(-1, visuals[1]["pictureMask"])
        self.assertEqual(("GUI", "0"), (
            visuals[2]["customSpriteSubspace"], visuals[2]["customSpriteEntry"],
        ))
        self.assertEqual(-16776961, visuals[2]["blueMask"])
        media = {record["role"]: record["mediaType"] for record in manifest["files"]}
        self.assertEqual("application/gzip", media["asset.sprite.custom"])
        self.assertEqual("application/gzip", media["asset.spritepack"])

    def test_successor_osar_fixture_independently_decodes_real_nonempty_pixels(self):
        custom = decode_osar((FIXTURE_V2 /
            "files/client/Cache/video/Custom_Sprites.osar").read_bytes())
        spritepack = decode_osar((FIXTURE_V2 /
            "files/client/Cache/video/spritepacks/Menus.osar").read_bytes())
        frames = [custom["items"]["0"]["frames"],
                  spritepack["GUI"]["0"]["frames"]]
        for decoded in frames:
            self.assertEqual(1, len(decoded))
            frame = decoded[0]
            self.assertGreater(frame["width"], 0)
            self.assertGreater(frame["height"], 0)
            self.assertEqual(frame["width"] * frame["height"], len(frame["pixels"]))
            self.assertGreater(len(set(frame["pixels"])), 1)
            self.assertTrue(any(pixel != 0 for pixel in frame["pixels"]))

        import zipfile
        authentic_path = (FIXTURE_V2 /
            "files/client/Cache/video/Authentic_Sprites.orsc")
        with zipfile.ZipFile(authentic_path) as authentic:
            self.assertEqual(["sprites/417.dat"], authentic.namelist())
            entry = authentic.read("sprites/417.dat")
        wrapped = bytes((1,)) + b"sprites\0\0\1" + b"417\0" + entry
        authentic_decoded = decode_osar(gzip.compress(wrapped, mtime=0))
        self.assertTrue(any(
            pixel != 0
            for pixel in authentic_decoded["sprites"]["417"]["frames"][0]["pixels"]
        ))

    @staticmethod
    def rewrite_v2_manifest(root: Path, visuals: list[dict] | None = None) -> None:
        spec = importlib.util.spec_from_file_location("bundle_v2_test_generator", GENERATOR_V2)
        module = importlib.util.module_from_spec(spec)
        assert spec is not None and spec.loader is not None
        spec.loader.exec_module(module)
        manifest_path = root / "manifest.json"
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        if visuals is not None:
            manifest["itemVisuals"] = visuals
        for record in manifest["files"]:
            path = root / record["bundleRelativePath"]
            payload = path.read_bytes()
            record["size"] = len(payload)
            record["sha256"] = hashlib.sha256(payload).hexdigest()
        manifest["definitionFingerprintSha256"] = module.record_fingerprint(
            b"world-builder-project-content-definitions-v2\n", manifest["files"],
            True, manifest["definitionCatalog"]["catalogSha256"],
        )
        manifest["assetFingerprintSha256"] = module.record_fingerprint(
            b"world-builder-project-content-assets-v2\n", manifest["files"], False,
        )
        manifest["itemVisualFingerprintSha256"] = hashlib.sha256(
            b"world-builder-project-content-item-visuals-v1\n"
            + module.legacy.canonical(manifest["itemVisuals"])
        ).hexdigest()
        manifest["bundleFingerprintSha256"] = "0" * 64
        manifest["bundleFingerprintSha256"] = hashlib.sha256(
            b"world-builder-project-content-bundle-v2\n"
            + module.legacy.canonical(manifest)
        ).hexdigest()
        manifest_path.write_bytes(module.legacy.pretty(manifest))

    def test_successor_rejects_malformed_duplicate_and_missing_archive_entry(self):
        cases = {}

        def malformed(root: Path) -> None:
            evidence = root / "files/server/conf/world-builder/item-visuals-v1.json"
            evidence.write_bytes(b"{malformed\n")

        cases["malformed"] = (malformed, None, "malformed JSON")

        def duplicate(root: Path) -> None:
            evidence = root / "files/server/conf/world-builder/item-visuals-v1.json"
            document = json.loads(evidence.read_text(encoding="utf-8"))
            document["itemVisuals"].insert(1, dict(document["itemVisuals"][0]))
            evidence.write_text(json.dumps(document, sort_keys=True, indent=2) + "\n")

        cases["duplicate"] = (duplicate, "evidence", "unique, ascending")

        def missing_entry(root: Path) -> None:
            archive = root / "files/client/Cache/video/Custom_Sprites.osar"
            spec = importlib.util.spec_from_file_location("bundle_v2_mutation", GENERATOR_V2)
            module = importlib.util.module_from_spec(spec)
            assert spec is not None and spec.loader is not None
            spec.loader.exec_module(module)
            archive.write_bytes(module.deterministic_osar([
                ("items", [("different", module.osar_entry(
                    width=1, height=1, pixels=[0x123456],
                ))]),
            ]))

        cases["missing-entry"] = (missing_entry, None, "archive entry is missing")

        for name, (mutation, visual_source, expected) in cases.items():
            with self.subTest(case=name), tempfile.TemporaryDirectory(
                prefix="content-bundle-v2-invalid-"
            ) as temp:
                changed = Path(temp) / "bundle"
                shutil.copytree(FIXTURE_V2, changed)
                mutation(changed)
                visuals = None
                if visual_source == "evidence":
                    visuals = json.loads((changed /
                        "files/server/conf/world-builder/item-visuals-v1.json"
                    ).read_text(encoding="utf-8"))["itemVisuals"]
                self.rewrite_v2_manifest(changed, visuals)
                result = self.read_with_java(changed)
                self.assertNotEqual(0, result.returncode)
                self.assertIn(expected, result.stderr)

    def test_successor_rejects_cross_role_named_mapping_ambiguity(self):
        with tempfile.TemporaryDirectory(prefix="content-bundle-v2-ambiguous-") as temp:
            changed = Path(temp) / "bundle"
            shutil.copytree(FIXTURE_V2, changed)
            custom = changed / "files/client/Cache/video/Custom_Sprites.osar"
            spec = importlib.util.spec_from_file_location("bundle_v2_ambiguity", GENERATOR_V2)
            module = importlib.util.module_from_spec(spec)
            assert spec is not None and spec.loader is not None
            spec.loader.exec_module(module)
            custom.write_bytes(module.deterministic_osar([
                ("items", [("0", module.osar_entry(
                    width=1, height=1, pixels=[0x123456],
                ))]),
                ("GUI", [("0", module.osar_entry(
                    width=1, height=1, pixels=[0x654321],
                ))]),
            ]))
            manifest_path = changed / "manifest.json"
            manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
            manifest["itemVisuals"][2]["customSpriteAssetRole"] = "asset.sprite.custom"
            evidence = changed / "files/server/conf/world-builder/item-visuals-v1.json"
            evidence_document = json.loads(evidence.read_text(encoding="utf-8"))
            evidence_document["itemVisuals"] = manifest["itemVisuals"]
            evidence.write_text(json.dumps(
                evidence_document, sort_keys=True, indent=2,
            ) + "\n", encoding="utf-8")
            self.rewrite_v2_manifest(changed, manifest["itemVisuals"])
            result = self.read_with_java(changed)
            self.assertNotEqual(0, result.returncode)
            self.assertIn("role-ambiguous", result.stderr)

    def test_successor_rejects_malformed_osar_names_frames_and_pixels(self):
        spec = importlib.util.spec_from_file_location("bundle_v2_invalid_osar", GENERATOR_V2)
        module = importlib.util.module_from_spec(spec)
        assert spec is not None and spec.loader is not None
        spec.loader.exec_module(module)
        valid = module.osar_entry(width=1, height=1, pixels=[0x123456])
        cases = {
            "unsafe-name": [("items/nested", [("0", valid)])],
            "empty-frame-set": [("items", [("0", bytes((0, 0)))])],
            "invalid-palette-index": [("items", [("0",
                bytes((0, 1, 0)) + b"\x12\x34\x56"
                + b"\x00\x01\x00\x01\x00\x00\x00\x00\x00\x00\x00\x01\x00\x01"
                + b"\x01")])],
        }
        for name, subspaces in cases.items():
            with self.subTest(case=name), tempfile.TemporaryDirectory(
                prefix="content-bundle-v2-invalid-osar-"
            ) as temp:
                changed = Path(temp) / "bundle"
                shutil.copytree(FIXTURE_V2, changed)
                archive = changed / "files/client/Cache/video/Custom_Sprites.osar"
                archive.write_bytes(module.deterministic_osar(subspaces))
                self.rewrite_v2_manifest(changed)
                result = self.read_with_java(changed)
                self.assertNotEqual(0, result.returncode)
                self.assertTrue(
                    "OSAR" in result.stderr or "portable" in result.stderr,
                    result.stderr,
                )

    def test_successor_accepts_runtime_legacy_case_and_frame_geometry(self):
        spec = importlib.util.spec_from_file_location("bundle_v2_legacy_osar", GENERATOR_V2)
        module = importlib.util.module_from_spec(spec)
        assert spec is not None and spec.loader is not None
        spec.loader.exec_module(module)
        legacy_geometry = (
            bytes((0, 1, 0)) + b"\x12\x34\x56"
            + b"\x00\x02\x00\x01\x01\xff\xff\xff\xfe\x00\x01\x00\x01"
            + b"\x00\x00"
        )
        with tempfile.TemporaryDirectory(prefix="content-bundle-v2-legacy-osar-") as temp:
            changed = Path(temp) / "bundle"
            shutil.copytree(FIXTURE_V2, changed)
            archive = changed / "files/client/Cache/video/Custom_Sprites.osar"
            archive.write_bytes(module.deterministic_osar([
                ("items", [("0", legacy_geometry), ("Entry", module.osar_entry(
                    width=1, height=1, pixels=[0x123456],
                ))]),
                ("ITEMS", [("entry", module.osar_entry(
                    width=1, height=1, pixels=[0x654321],
                ))]),
                ("unused", []),
            ]))
            self.rewrite_v2_manifest(changed)
            result = self.read_with_java(changed)
            self.assertEqual(0, result.returncode, result.stderr)


if __name__ == "__main__":
    unittest.main()
