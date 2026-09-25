#!/usr/bin/env python3
"""Exercise standard floor metadata at the real Editor content boundary."""

import gzip
import shutil
import json
import xml.etree.ElementTree as ET
import subprocess
import tempfile
import unittest
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
CLASSES = ROOT / "output/world-builder-tools/classes"
HARNESS = r'''
package com.openrsc.worldbuilder;
import java.nio.file.*;
public final class StandardFloorHarness {
    public static void main(String[] args) throws Exception {
        Path root = Paths.get(args[1]);
        if (args[0].equals("extend")) {
            Files.write(Paths.get(args[2]), WorldBuilderStandardFloorDefinitions.extend(root));
            return;
        }
        if (args[0].equals("extend-bundle")) {
            System.out.println(WorldBuilderProjectContentBundle.extendStandardFloors(root).bundleFingerprintSha256);
            return;
        }
        if (args[0].equals("runtime")) {
            if (WorldBuilderStandardFloorRuntime.required(root.resolve("TileDef.xml"))) {
                WorldBuilderStandardFloorRuntime.require(root.resolve("server.jar"), root.resolve("client.jar"));
            }
            System.out.println("compatible");
            return;
        }
        if (args[0].equals("normalize")) {
            WorldBuilderTerrainMaterialProvider.Result result =
                WorldBuilderTerrainMaterialProvider.normalize(root, null);
            System.out.println(result.references.keySet());
            return;
        }
        WorldBuilderTerrainDefinitionCatalog catalog =
            WorldBuilderTerrainDefinitionCatalog.readTiles(root);
        for (int i = 0; i < catalog.tiles.size(); i++) {
            WorldBuilderTerrainDefinitionCatalog.TileDefinition tile = catalog.tiles.get(i);
            System.out.println((i + 1) + ":" + tile.objectType + ":" +
                (tile.usesBaseColor() ? "palette" : tile.materialResource(i + 1)));
        }
    }
}
'''


def tile(colour=-1, kind=0, blocking=0, metadata=""):
    return (f"<TileDef><colour>{colour}</colour><unknown>{kind}</unknown>"
            f"<objectType>{blocking}</objectType>{metadata}</TileDef>")


BASE = "<worldBuilderMaterial>base-color-v1</worldBuilderMaterial>"


class StandardFloors(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        if not CLASSES.is_dir():
            raise RuntimeError("Run ./scripts/build-tools.sh first")
        cls.temp = tempfile.TemporaryDirectory(prefix="standard-floor-test-")
        cls.root = Path(cls.temp.name)
        source = cls.root / "StandardFloorHarness.java"
        source.write_text(HARNESS)
        subprocess.run(["javac", "-cp", str(CLASSES), "-d", str(cls.root), str(source)], check=True)

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()

    def run_harness(self, rows, normalize=False, valid=True):
        target = self.root / self._testMethodName
        defs = target / "server/conf/server/defs"
        defs.mkdir(parents=True, exist_ok=True)
        xml = defs / "TileDef.xml"
        xml.write_text("<TileDef-array>" + "".join(rows) + "</TileDef-array>")
        if normalize:
            (defs / "DoorDef.xml").write_text("<DoorDef-array><DoorDef>"
                "<modelVar2>-1</modelVar2><modelVar3>-1</modelVar3>"
                "</DoorDef></DoorDef-array>")
            archive = target / "Client_Base/Cache/video/Custom_Sprites.osar"
            archive.parent.mkdir(parents=True, exist_ok=True)
            archive.write_bytes(gzip.compress(b"\x01textures\x00\x00\x00", mtime=0))
        before = {p: p.read_bytes() for p in target.rglob("*") if p.is_file()}
        result = subprocess.run(["java", "-cp", f"{self.root}:{CLASSES}",
            "com.openrsc.worldbuilder.StandardFloorHarness",
            "normalize" if normalize else "parse", str(target if normalize else xml)],
            capture_output=True, text=True)
        self.assertEqual(before, {p: p.read_bytes() for p in before})
        if valid:
            self.assertEqual(result.returncode, 0, result.stderr)
        else:
            self.assertNotEqual(result.returncode, 0, result.stdout)
            self.assertIn("IOException", result.stderr)
        return result.stdout.strip()

    def test_legacy_and_dynamic_color_keep_distinct_materials(self):
        self.assertEqual(self.run_harness([tile(0), tile(0, metadata=BASE),
            tile(0, blocking=1, metadata=BASE)]), "1:0:0\n2:0:palette\n3:1:palette")

    def test_source_identity_preserves_special_water_material(self):
        rows = [tile()] * 11 + [tile(3, 4, 1), tile(3, 4, 0,
            "<worldBuilderSourceOverlay>12</worldBuilderSourceOverlay>")]
        self.assertTrue(self.run_harness(rows).endswith("12:1:31\n13:0:31"))
        self.assertEqual(self.run_harness(rows, normalize=True), "[3, 31]")

    def test_dynamic_color_does_not_require_texture_zero(self):
        self.assertEqual(self.run_harness([tile(0, metadata=BASE)], normalize=True), "[]")

    def test_invalid_metadata_is_refused(self):
        variants = [
            [tile(0, metadata="<worldBuilderMaterial/>")],
            [tile(0, metadata="<worldBuilderMaterial>unknown</worldBuilderMaterial>")],
            [tile(1, metadata=BASE)], [tile(0, 6, metadata=BASE)],
            [tile(0, blocking=2, metadata=BASE)],
            [tile(metadata="<worldBuilderSourceOverlay>0</worldBuilderSourceOverlay>")],
            [tile(metadata="<worldBuilderSourceOverlay>1</worldBuilderSourceOverlay>")],
            [tile(), tile(1, metadata="<worldBuilderSourceOverlay>1</worldBuilderSourceOverlay>")],
            [tile(0, metadata=BASE), tile(0, metadata="<worldBuilderSourceOverlay>1</worldBuilderSourceOverlay>")],
            [tile(), tile(metadata="<worldBuilderSourceOverlay>1</worldBuilderSourceOverlay>"),
             tile(metadata="<worldBuilderSourceOverlay>2</worldBuilderSourceOverlay>")],
            [tile(), tile(0, metadata=BASE + "<worldBuilderSourceOverlay>1</worldBuilderSourceOverlay>")],
            [tile()] * 249 + [tile(0, metadata=BASE)],
            [tile()] * 250 + [tile(0, metadata=BASE)],
            [tile()] * 254 + [tile(0, metadata=BASE)],
        ]
        for rows in variants:
            with self.subTest(rows=rows[-1]):
                self.run_harness(rows, valid=False)

    def test_marked_content_requires_both_runtime_semantic_declarations(self):
        target = self.root / self._testMethodName
        target.mkdir()
        xml = target / "TileDef.xml"
        def invoke():
            return subprocess.run(["java", "-cp", f"{self.root}:{CLASSES}",
                "com.openrsc.worldbuilder.StandardFloorHarness", "runtime", str(target)],
                capture_output=True, text=True)
        # Historical content does not acquire a new runtime requirement.
        xml.write_text("<TileDef-array>" + tile() + "</TileDef-array>")
        self.assertEqual(invoke().returncode, 0)
        xml.write_text("<TileDef-array>" + tile(0, metadata=BASE) + "</TileDef-array>")
        supported = "World-Builder-Floor-Semantics: standard-floors-v1\r\n"
        for server, client, expected in [
            ("", "", False), (supported, "", False), ("", supported, False),
            (supported, "World-Builder-Floor-Semantics: unknown\r\n", False),
            (supported, supported + supported, False),
            (supported, supported, True),
        ]:
            with self.subTest(server=server, client=client):
                for name, attributes in (("server", server), ("client", client)):
                    with zipfile.ZipFile(target / (name + ".jar"), "w") as jar:
                        jar.writestr("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\r\n" + attributes + "\r\n")
                before = {p: p.read_bytes() for p in target.iterdir()}
                result = invoke()
                self.assertEqual(result.returncode == 0, expected, result.stderr)
                if not expected:
                    self.assertIn("standard-floors-v1 in both client and server", result.stderr)
                self.assertEqual(before, {p: p.read_bytes() for p in before})

    def test_imported_extension_preserves_originals_and_completes_materials(self):
        directory = self.root / self._testMethodName
        directory.mkdir()
        original = ("<?xml version=\"1.0\"?>\n<TileDef-array><!-- custom palette -->\n" +
            tile(-19) + tile(42, 4, 1) + tile(12345678) + "</TileDef-array>\n").encode()
        source = directory / "input.xml"
        output = directory / "output.xml"
        source.write_bytes(original)
        def extend(src, dst):
            return subprocess.run(["java", "-cp", f"{self.root}:{CLASSES}",
                "com.openrsc.worldbuilder.StandardFloorHarness", "extend", str(src), str(dst)],
                capture_output=True, text=True)
        result = extend(source, output)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(source.read_bytes(), original)
        self.assertTrue(output.read_bytes().startswith(original.split(b"</TileDef-array>")[0]))
        rows = list(ET.fromstring(output.read_bytes()))
        partners = {(int(r.findtext("worldBuilderSourceOverlay")), int(r.findtext("objectType")))
            for r in rows if r.find("worldBuilderSourceOverlay") is not None}
        self.assertEqual(partners, {(1, 1), (2, 0), (2, 1), (3, 0), (3, 1)})
        for row in rows:
            if row.findtext("worldBuilderSourceOverlay") == "2":
                self.assertEqual(row.findtext("colour"), "42")
                self.assertEqual(row.findtext("unknown"), "4")
        again = directory / "again.xml"
        self.assertEqual(extend(output, again).returncode, 0)
        self.assertEqual(again.read_bytes(), output.read_bytes())
        source.write_text("<TileDef-array>" + tile() * 249 + "</TileDef-array>")
        refused = directory / "refused.xml"
        result = extend(source, refused)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("249 usable slots", result.stderr)
        self.assertFalse(refused.exists())

    def test_bundle_upgrade_is_staged_preserves_other_files_and_is_idempotent(self):
        directory = self.root / self._testMethodName
        bundle = directory / ".staging-test" / "source/content-bundle"
        shutil.copytree(ROOT / "tests/fixtures/project-content-bundle-v1/bundle", bundle)
        before = {str(p.relative_to(bundle)): p.read_bytes() for p in bundle.rglob("*") if p.is_file()}
        def invoke(root):
            return subprocess.run(["java", "-cp", f"{self.root}:{CLASSES}",
                "com.openrsc.worldbuilder.StandardFloorHarness", "extend-bundle", str(root)],
                capture_output=True, text=True)
        result = invoke(bundle)
        self.assertEqual(result.returncode, 0, result.stderr)
        for path, payload in before.items():
            if path not in ("manifest.json", "files/server/conf/server/defs/TileDef.xml"):
                self.assertEqual((bundle / path).read_bytes(), payload)
        manifest = json.loads((bundle / "manifest.json").read_text())
        old = json.loads(before["manifest.json"])
        self.assertEqual(manifest["assetFingerprintSha256"], old["assetFingerprintSha256"])
        self.assertNotEqual(manifest["definitionFingerprintSha256"], old["definitionFingerprintSha256"])
        after = {str(p.relative_to(bundle)): p.read_bytes() for p in bundle.rglob("*") if p.is_file()}
        self.assertEqual(invoke(bundle).returncode, 0)
        self.assertEqual(after, {str(p.relative_to(bundle)): p.read_bytes() for p in bundle.rglob("*") if p.is_file()})
        sealed = directory / "sealed" / "source/content-bundle"
        shutil.copytree(bundle, sealed)
        self.assertNotEqual(invoke(sealed).returncode, 0)


if __name__ == "__main__":
    unittest.main()
