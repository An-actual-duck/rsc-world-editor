#!/usr/bin/env python3
"""Generic source-bound NPC visuals through discovery, sealed capture, and reopen."""
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import shutil
import struct
import subprocess
import tempfile
import unittest
import zipfile
import zlib

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("npc_lifecycle", ROOT / "tests/myworld/test-world-builder-adaptive-project-lifecycle.py")
L = importlib.util.module_from_spec(spec)
spec.loader.exec_module(L)
SHEET = "assets/creatures/arbitrary-presentation.png"

def pixel(x, y):
    # More than 256 colors, with real alpha and opaque black edge cases.
    if x == 0: return (0, 0, 0, 0 if y % 2 else 255)
    return (x % 256, y % 256, (x + y) % 256, 255)

def png(path, width=728, height=300):
    def chunk(name, data):
        return struct.pack(">I", len(data)) + name + data + struct.pack(">I", zlib.crc32(name + data))
    raw = b"".join(b"\0" + bytes(v for x in range(width) for v in pixel(x, y)) for y in range(height))
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0)) + chunk(b"IDAT", zlib.compress(raw)) + chunk(b"IEND", b""))

def marker(jar):
    with zipfile.ZipFile(jar) as archive: files = {n: archive.read(n) for n in archive.namelist()}
    files["META-INF/MANIFEST.MF"] = files["META-INF/MANIFEST.MF"].rstrip() + b"\nWorld-Builder-Npc-Rgb: npc-rgb-frames-v1\nWorld-Builder-Npc-Animation-Count: 1080\n\n"
    with zipfile.ZipFile(jar, "w") as archive:
        for name, data in files.items(): archive.writestr(name, data)

class NpcDirectionSheetsTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        L.AdaptiveProjectLifecycleTest.setUpClass()
        cls.addClassCleanup(L.AdaptiveProjectLifecycleTest.tearDownClass)
        cls.life = L.AdaptiveProjectLifecycleTest()

    def fixture(self, root):
        self.root = root
        self.target = self.life.fixtures.legacy_fixture(str(root))
        self.install = root / "install"; self.install.mkdir()
        self.runtime = self.life.make_runtime(root)
        for path in ("server/core.jar", "Client_Base/Open_RSC_Client.jar"): marker(self.runtime / path)
        terrain = self.target / "server/conf/server/data/Custom_Landscape.orsc"
        with zipfile.ZipFile(terrain, "w", zipfile.ZIP_DEFLATED) as archive: archive.writestr("h0x48y37", bytes(48*48*10))
        shutil.copy2(terrain, self.target / "Client_Base/Cache/video/Custom_Landscape.orsc")
        row = {"id": 866, "name": "Naga", "attack": 19, "hits": 47, "meleeDefenseMultiplier": 1.25}
        row.update({f"sprites{i}": 0 if i == 1 else -1 for i in range(1, 13)})
        L.write_json(self.target / "server/conf/server/defs/SlayerMovementPreviewNpcDefs.json", {"npcs": [row]})
        shutil.copyfile(ROOT / "tests/fixtures/project-content-bundle-v2/bundle/files/client/Cache/video/Authentic_Sprites.orsc",
                        self.target / "Client_Base/Cache/video/Authentic_Sprites.orsc")
        png(self.target / SHEET)
        self.write_visual_descriptor(866, "server/conf/server/defs/SlayerMovementPreviewNpcDefs.json", 0)
        self.report = root / "report.json"

    def write_visual_descriptor(self, npc_id, definition_path, definition_index, slot=1):
        record = {
            "npcId": npc_id, "definitionPath": definition_path, "definitionIndex": definition_index,
            "definitionSha256": hashlib.sha256((self.target / definition_path).read_bytes()).hexdigest(),
            "spriteSlot": slot, "alphaThreshold": 64, "cameraWidth": 240, "cameraHeight": 240,
            "charColour": 0, "blueMask": 0, "genderModel": 0,
            "hasCombatFrames": True, "hasSpecialCombatFrames": False,
            "frames": [{"imagePath": SHEET,
                "imageSha256": hashlib.sha256((self.target / SHEET).read_bytes()).hexdigest(),
                "x": frame // 3 * 100, "y": frame % 3 * 100, "width": 100, "height": 100,
                "offsetX": 0, "offsetY": 0, "boundWidth": 100, "boundHeight": 100} for frame in range(18)],
        }
        L.write_json(self.target / "npc-visuals-v1.json", {
            "schemaVersion": 1, "manifestType": "world-builder-npc-visual-sources", "visuals": [record]})

    def effective_npcs(self, bundle):
        root = bundle / "server/conf/server/defs"
        rows = json.loads((root / "NpcDefs.json").read_text())["npcs"] + json.loads((root / "NpcDefsCustom.json").read_text())["npcs"]
        for name in ("NpcDefsPatch18.json", "NpcDefsMyWorld.json"):
            for overlay in json.loads((root / name).read_text())["npcs"]: rows[overlay["id"]].update(overlay)
        return rows

    def discover(self):
        result = self.life.run_cli("discover-adaptive", "--target-root", self.target)
        return result

    def create(self):
        self.life.discover(self.target, self.report)
        result, summary = self.life.create_project(self.install, self.runtime, self.target, self.report, "Directional NPC", 43861)
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        return Path(summary["projectRoot"])

    def test_unplaced_naga_is_captured_losslessly_and_reopens_without_target(self):
        with tempfile.TemporaryDirectory(prefix="npc-direction-") as temp:
            self.fixture(Path(temp)); before = L.tree_bytes(self.target)
            project = self.create()
            self.assertEqual(before, L.tree_bytes(self.target))
            self.assertEqual((self.target / SHEET).read_bytes(), (project / "source/original" / SHEET).read_bytes())
            bundle = project / "source/content-bundle/files"
            custom = json.loads((bundle / "server/conf/server/defs/NpcDefsCustom.json").read_text())["npcs"]
            naga = self.effective_npcs(bundle)[866]
            self.assertEqual((19, 47, 1.25), (naga["attack"], naga["hits"], naga["meleeDefenseMultiplier"]))
            registry = json.loads((bundle / "server/conf/world-builder/npc-animations-v1.json").read_text())["animations"]
            self.assertEqual(1, len(registry)); animation = registry[0]
            self.assertEqual(1080, naga["sprites1"]); self.assertEqual(1080, animation["animationId"])
            self.assertEqual("authentic-rgb", animation["frameSource"])
            with zipfile.ZipFile(bundle / "client/Cache/video/Authentic_Sprites.orsc") as archive:
                for frame, expected_hash in enumerate(animation["authenticFrameSha256s"]):
                    raw = archive.read("sprites/" + str(animation["authenticBaseSpriteId"] + frame) + ".dat")
                    self.assertEqual(expected_hash, hashlib.sha256(raw).hexdigest())
                    self.assertEqual((100, 100, 0, 0, 0, 100, 100), struct.unpack(">IIBIIII", raw[:25]))
                    pixels = struct.unpack(">10000I", raw[25:])
                    expected = []
                    for y in range(100):
                        for x in range(100):
                            r,g,b,a = pixel(frame//3 * 100+x, frame%3 *100+y)
                            rgb = r<<16 | g<<8 | b
                            expected.append(0 if a <64 else rgb or 0x010101)
                    self.assertEqual(expected, list(pixels))
            shutil.move(self.target, self.root / "offline-target")
            opened = self.life.run_cli("open-project", "--installation-root", self.install, "--validate-only")
            self.assertEqual(0, opened.returncode, opened.stderr)

    def test_missing_invalid_and_linked_sheets_refuse_discovery(self):
        for kind in ("missing", "wrong-size", "symlink", "oversized"):
            with self.subTest(kind=kind), tempfile.TemporaryDirectory(prefix="npc-direction-invalid-") as temp:
                self.fixture(Path(temp)); sheet = self.target / SHEET
                if kind == "missing": sheet.unlink()
                elif kind == "wrong-size": png(sheet, width=727)
                elif kind == "oversized": sheet.write_bytes(b"x" * (4*1024*1024+1))
                else:
                    alternate = self.root / "outside.png"; sheet.rename(alternate); sheet.symlink_to(alternate)
                result = self.discover()
                self.assertNotEqual(0, result.returncode, result.stdout)
                self.assertIn("arbitrary-presentation.png", result.stdout + result.stderr)

    def test_discovery_to_capture_detects_sheet_drift(self):
        with tempfile.TemporaryDirectory(prefix="npc-direction-drift-") as temp:
            self.fixture(Path(temp)); self.life.discover(self.target, self.report)
            png(self.target / SHEET, width=727)
            result, _ = self.life.create_project(self.install, self.runtime, self.target, self.report, "Drift", 43861)
            self.assertNotEqual(0, result.returncode)

    def test_old_runtime_refuses_lossless_mode(self):
        with tempfile.TemporaryDirectory(prefix="npc-direction-old-runtime-") as temp:
            self.fixture(Path(temp)); self.runtime = self.life.make_runtime(self.root / "old-runtime")
            self.life.discover(self.target, self.report)
            result, _ = self.life.create_project(self.install, self.runtime, self.target, self.report, "Old runtime", 43861)
            self.assertNotEqual(0, result.returncode)
            self.assertIn("lossless directional NPC", result.stdout + result.stderr)

    def test_preserved_registry_reuses_frames_and_keeps_unrelated_identity(self):
        with tempfile.TemporaryDirectory(prefix="npc-direction-preserved-") as temp:
            self.fixture(Path(temp))
            # Same name elsewhere must not redirect the source family's explicit ID.
            custom_path = self.target / "server/conf/server/defs/NpcDefsCustom.json"
            custom = json.loads(custom_path.read_text())
            custom["npcs"][0]["name"] = "Naga"
            L.write_json(custom_path, custom)
            first = self.create()
            bundle = first / "source/content-bundle/files"
            registry = "server/conf/world-builder/npc-animations-v1.json"
            target_registry = self.target / registry; target_registry.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(bundle / registry, target_registry)
            shutil.copyfile(bundle / "client/Cache/video/Authentic_Sprites.orsc",
                            self.target / "Client_Base/Cache/video/Authentic_Sprites.orsc")
            second = self.create()
            copied = second / "source/content-bundle/files"
            self.assertEqual((bundle / registry).read_bytes(), (copied / registry).read_bytes())
            self.assertEqual((bundle / "client/Cache/video/Authentic_Sprites.orsc").read_bytes(),
                             (copied / "client/Cache/video/Authentic_Sprites.orsc").read_bytes())
            definitions = json.loads((copied / "server/conf/server/defs/NpcDefsCustom.json").read_text())["npcs"]
            self.assertEqual(custom["npcs"][0].get("sprites1"), definitions[0].get("sprites1"))
            self.assertEqual(1080, self.effective_npcs(copied)[866]["sprites1"])
            # A registry copied from an upgraded server still needs the runtime
            # handshake even if the historical source sheets are no longer there.
            (self.target / "server/conf/server/defs/SlayerMovementPreviewNpcDefs.json").unlink()
            (self.target / SHEET).unlink()
            (self.target / "npc-visuals-v1.json").unlink()
            self.runtime = self.life.make_runtime(self.root / "old-preserved-runtime")
            self.life.discover(self.target, self.report)
            result, _ = self.life.create_project(self.install, self.runtime, self.target, self.report, "Old preserved", 43861)
            self.assertNotEqual(0, result.returncode)
            self.assertIn("lossless directional NPC", result.stdout + result.stderr)

    def test_existing_base_id_and_active_overlay_preserve_gameplay_and_other_layers(self):
        with tempfile.TemporaryDirectory(prefix="npc-visual-base-") as temp:
            self.fixture(Path(temp))
            catalog = self.target / "server/conf/server/defs/SlayerMovementPreviewNpcDefs.json"; catalog.unlink()
            base_path = self.target / "server/conf/server/defs/NpcDefs.json"
            doc = json.loads(base_path.read_text()); doc["npcs"][1].update({"name":"Unrelated baseline reuse", "sprites1":7,"sprites2":8,"attack":29})
            L.write_json(base_path,doc)
            patch = self.target / "server/conf/server/defs/NpcDefsPatch18.json"
            L.write_json(patch,{"npcs":[{"id":1,"sprites1":9,"hits":87}]})
            self.write_visual_descriptor(1,"server/conf/server/defs/NpcDefs.json",1)
            before=L.tree_bytes(self.target);project=self.create();bundle=project / "source/content-bundle/files"
            npc=self.effective_npcs(bundle)[1]
            self.assertEqual((1080,8,29,87),(npc["sprites1"],npc["sprites2"],npc["attack"],npc["hits"]))
            self.assertEqual(before,L.tree_bytes(self.target))
            diagnostic=json.loads((project / "diagnostics/npc-visual-resolution-v1.json").read_text())["npcs"][1]
            self.assertEqual([1],diagnostic["resolvedSpriteSlots"])

    def test_arbitrary_id_name_and_descriptor_binding(self):
        for npc_id,name in ((43,"Moss kite"),(43,"Clockwork otter"),(172,"Blue tin creature")):
            with self.subTest(npc_id=npc_id,name=name),tempfile.TemporaryDirectory(prefix="npc-visual-arbitrary-") as temp:
                self.fixture(Path(temp))
                old=self.target / "server/conf/server/defs/SlayerMovementPreviewNpcDefs.json"
                doc=json.loads(old.read_text());old.unlink();doc["npcs"][0].update({"id":npc_id,"name":name})
                relative="server/conf/server/defs/UnrelatedNpcDefs.json";L.write_json(self.target / relative,doc)
                self.write_visual_descriptor(npc_id,relative,0)
                project=self.create();npc=self.effective_npcs(project / "source/content-bundle/files")[npc_id]
                self.assertEqual(name,npc["name"]);self.assertEqual(1080,npc["sprites1"])

    def test_alternate_definition_root_preserves_original_bindings_and_supplemental_aliases(self):
        with tempfile.TemporaryDirectory(prefix="npc-visual-alt-layout-") as temp:
            self.fixture(Path(temp))
            original=self.target / "server/conf/server/defs";alternate=self.target / "server/data/definitions"
            alternate.parent.mkdir(parents=True,exist_ok=True);original.rename(alternate)
            relative="server/data/definitions/SlayerMovementPreviewNpcDefs.json"
            self.write_visual_descriptor(866,relative,0)
            # Descriptor discovery also follows the selected noncanonical definition root.
            (self.target / "npc-visuals-v1.json").rename(alternate / "npc-visuals-v1.json")
            before=L.tree_bytes(self.target);project=self.create()
            self.assertEqual(before,L.tree_bytes(self.target))
            self.assertEqual(1080,self.effective_npcs(project / "source/content-bundle/files")[866]["sprites1"])
            self.assertEqual((self.target / relative).read_bytes(),(project / "source/original" / relative).read_bytes())

    def test_generic_metadata_refuses_bad_identity_camera_conflicts_and_excessive_reused_frames(self):
        for case in ("identity", "hash", "offset", "camera", "budget"):
            with self.subTest(case=case),tempfile.TemporaryDirectory(prefix="npc-visual-invalid-") as temp:
                self.fixture(Path(temp));path=self.target / "npc-visuals-v1.json";doc=json.loads(path.read_text());record=doc["visuals"][0]
                if case=="identity":record["npcId"]=865
                elif case=="hash":record["definitionSha256"]="0"*64
                elif case=="offset":record["frames"][0]["offsetX"]=4097
                elif case=="camera":
                    other=json.loads(json.dumps(record));other["spriteSlot"]=2;other["cameraWidth"]=120;doc["visuals"].append(other)
                else:
                    records=[]
                    for slot in range(1,13):
                        other=json.loads(json.dumps(record));other["spriteSlot"]=slot;other["hasSpecialCombatFrames"]=True
                        frame=other["frames"][0];frame.update({"x":0,"y":0,"width":728,"height":300,"boundWidth":728,"boundHeight":300})
                        other["frames"]=[frame.copy() for _ in range(27)];records.append(other)
                    doc["visuals"]=records
                L.write_json(path,doc);before=L.tree_bytes(self.target)
                discovered=self.discover()
                if case in ("identity","camera"):
                    self.assertEqual(0,discovered.returncode,discovered.stderr);self.report.write_text(discovered.stdout)
                    result,_=self.life.create_project(self.install,self.runtime,self.target,self.report,"Invalid metadata",43861)
                else:result=discovered
                self.assertNotEqual(0,result.returncode)
                if case=="budget":self.assertIn("256 MiB",result.stdout+result.stderr)
                self.assertEqual(before,L.tree_bytes(self.target))

    def test_automatic_reference_source_capture_fixture(self):
        source=os.environ.get("WORLD_BUILDER_NPC_SOURCE_FIXTURE")
        destination=os.environ.get("WORLD_BUILDER_NPC_SOURCE_OUTPUT")
        if not source or not destination:self.skipTest("Optional explicitly exported source metadata fixture")
        root=Path(destination);root.mkdir(parents=True,exist_ok=False);self.fixture(root)
        (self.target / "npc-visuals-v1.json").unlink();(self.target / SHEET).unlink()
        for path in Path(source).rglob("*"):
            if path.is_file():
                target=self.target / path.relative_to(source);target.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(path,target)
        project=self.create();bundle=project / "source/content-bundle/files"
        registry=json.loads((bundle / "server/conf/world-builder/npc-animations-v1.json").read_text())["animations"]
        self.assertEqual(8,len(registry));naga=self.effective_npcs(bundle)[866]
        self.assertGreaterEqual(naga["sprites1"],1080)
        (root / "captured-project-path.txt").write_text(str(project))
        print("AUTOMATIC_SOURCE_PROJECT="+str(project),flush=True)

    def test_reference_asset_capture_fixture(self):
        source = os.environ.get("WORLD_BUILDER_DIRECTION_NPC_PNG")
        destination = os.environ.get("WORLD_BUILDER_DIRECTION_FIXTURE_OUTPUT")
        if not source or not destination: self.skipTest("Optional explicitly exported reference PNG fixture")
        root = Path(destination); root.mkdir(parents=True, exist_ok=False)
        self.fixture(root); shutil.copyfile(source, self.target / SHEET)
        self.write_visual_descriptor(866,"server/conf/server/defs/SlayerMovementPreviewNpcDefs.json",0)
        project = self.create()
        (root / "captured-project-path.txt").write_text(str(project))
        print("CAPTURED_NAGA_PROJECT=" + str(project), flush=True)

if __name__ == "__main__": unittest.main()
