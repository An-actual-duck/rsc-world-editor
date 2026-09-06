#!/usr/bin/env python3
"""Actual locked decoder and full genuine map conversion; never synthetic intake authority."""

import hashlib
import json
import os
from pathlib import Path
import contextlib
import shutil
import subprocess
import tempfile
import unittest
from collections import Counter

ROOT = Path(__file__).resolve().parents[2]
PROVIDER = ROOT / ".runtime-provider"
JAR = ROOT / "output/world-builder-tools/world-builder-tools.jar"
SOURCE_GIT = os.environ.get("WORLD_BUILDER_PRESERVATION_SOURCE_GIT")
KEEP_PROBE = os.environ.get("WORLD_BUILDER_PRESERVATION_KEEP_MAP_PROBE") == "1"
COMMIT = "c0102e60774ab9c9076aabae49f6f97fb6fc4b00"
TREE = "6db5536d795abf34f303bb03b20c43b8cfb9e3fe"
MAIN = "com.openrsc.worldbuilder.PreservationConversionHarness"
HARNESS = """
package com.openrsc.worldbuilder;
import java.nio.file.*;
public final class PreservationConversionHarness {
  public static void main(String[] args) throws Exception {
    Path stage = Paths.get(args[0]);
    WorldBuilderProviderCatalog.Composition composition = WorldBuilderProviderCatalog.resolve(
      Paths.get(args[1]), Paths.get(args[2]));
    Path cancelled = stage.resolve("source/migration/cancelled");
    try { WorldBuilderPreservationJagDecoder.decode(stage.resolve("source/original"), composition,
      cancelled, () -> true); throw new AssertionError("cancelled decoder executed");
    } catch (WorldBuilderContractException expected) { }
    if (Files.exists(cancelled)) throw new AssertionError("cancelled decoder wrote an attempt");
    Path overlap = stage.resolve("source/original/decoder");
    try { WorldBuilderPreservationJagDecoder.decode(stage.resolve("source/original"), composition,
      overlap, null); throw new AssertionError("decoder accepted original namespace");
    } catch (WorldBuilderContractException expected) { }
    if (Files.exists(overlap)) throw new AssertionError("decoder mutated original namespace");
    WorldBuilderPreservationJagDecoder.Result decoded = WorldBuilderPreservationJagDecoder.decode(
      stage.resolve("source/original"), composition, stage.resolve("source/migration/decoder"), null);
    try { WorldBuilderPreservationJagDecoder.decode(stage.resolve("source/original"), composition,
      stage.resolve("source/migration/decoder"), null); throw new AssertionError("decoder overwrote an attempt");
    } catch (WorldBuilderContractException expected) { }
    WorldBuilderPreservationMapEvidence.Prepared prepared = WorldBuilderPreservationMapEvidence.prepare(stage, decoded);
    WorldBuilderPackedConverter converter = new WorldBuilderPackedConverter();
    for (String unsafe : new String[]{"source/original/new-output", "source/migration/new-output"}) {
      try { converter.convertPreservation(prepared, stage.resolve(unsafe));
        throw new AssertionError("conversion mutated source/provenance namespace");
      } catch (WorldBuilderContractException expected) { }
      if (Files.exists(stage.resolve(unsafe))) throw new AssertionError("unsafe output was created");
    }
    WorldBuilderPackedConverter.Result converted = converter.convertPreservation(prepared, stage.resolve("conversion"));
    try { converter.convertPreservation(prepared, stage.resolve("conversion"));
      throw new AssertionError("existing output was overwritten");
    } catch (WorldBuilderContractException expected) { }
    prepared.reverify();
    System.out.print(converted.toJson());
  }
}
"""


def snapshot(root):
    return {str(path.relative_to(root)): (path.stat().st_mode & 0o777,
            hashlib.sha256(path.read_bytes()).hexdigest())
            for path in root.rglob("*") if path.is_file()}


def compile_harness(case):
    classes = case / "classes"
    classes.mkdir()
    java = case / "PreservationConversionHarness.java"
    java.write_text(HARNESS)
    compiled = subprocess.run(["javac", "-source", "8", "-target", "8", "-cp", str(JAR),
                               "-d", str(classes), str(java)], capture_output=True, text=True)
    if compiled.returncode:
        raise AssertionError(compiled.stdout + compiled.stderr)
    return classes


@contextlib.contextmanager
def conversion_probe():
    # A retained probe is always a new test-owned external directory, never a
    # caller-supplied target. It contains public inputs and invented config only.
    path = Path(tempfile.mkdtemp(prefix="preservation-map-conversion-"))
    try:
        yield path
    finally:
        if KEEP_PROBE:
            print("Retained sanitized Preservation conversion probe: " + str(path), flush=True)
        else:
            shutil.rmtree(path)


class PreservationMapConversionApiTest(unittest.TestCase):
    def test_internal_entrypoints_compile_without_granting_invocation_authority(self):
        with tempfile.TemporaryDirectory(prefix="preservation-conversion-api-") as temporary:
            compile_harness(Path(temporary))


@unittest.skipUnless(SOURCE_GIT, "genuine reviewed public source Git input unavailable")
class PreservationMapConversionTest(unittest.TestCase):
    def test_inventory_bound_decoder_and_full_conversion_preserve_immutable_inputs(self):
        expected = next(line.split("=", 1)[1] for line in (ROOT / "runtime-provider.lock").read_text().splitlines()
                        if line.startswith("RUNTIME_PROVIDER_COMMIT="))
        actual = subprocess.check_output(["git", "-C", str(PROVIDER), "rev-parse", "HEAD"], text=True).strip()
        self.assertEqual(expected, actual, "materialize the exact adopted provider lock before genuine invocation")
        self.assertEqual(0, subprocess.run(["git", "-C", str(PROVIDER), "diff", "--quiet", "HEAD", "--"],
                                         capture_output=True).returncode,
                         "genuine invocation requires unchanged tracked provider inputs")
        subprocess.run(["python3", "scripts/build-current-base.py"], cwd=PROVIDER,
                       check=True, capture_output=True, text=True, timeout=240)
        with conversion_probe() as case:
            stage = case / "project-stage"
            original = stage / "source/original"
            original.mkdir(parents=True)
            (stage / "source/migration").mkdir()
            actual_tree = subprocess.check_output(
                ["git", "-C", SOURCE_GIT, "rev-parse", f"{COMMIT}^{{tree}}"], text=True).strip()
            self.assertEqual(TREE, actual_tree)
            metadata = json.loads((ROOT / "tools/world-builder/resources/com/openrsc/worldbuilder/"
                                   "preservation-c0102e-source-intake.json").read_text())
            for row in metadata["records"]:
                data = subprocess.check_output(["git", "-C", SOURCE_GIT, "cat-file", "blob",
                                                f"{COMMIT}:{row['path']}"])
                self.assertEqual(row["size"], len(data))
                self.assertEqual(row["sha256"], hashlib.sha256(data).hexdigest())
                destination = original / row["path"]
                destination.parent.mkdir(parents=True, exist_ok=True)
                destination.write_bytes(data)
                destination.chmod(int(row["mode"], 8) & 0o777)
            (original / "server/connections.conf").write_text(
                "bind_address: 127.0.0.1\nws_server_port: 43494\ndb_type: sqlite\n")
            before = snapshot(original)
            classes = compile_harness(case)
            result = subprocess.run(
                ["java", "-Xmx768m", "-cp", os.pathsep.join((str(classes), str(JAR))), MAIN,
                 str(stage), str(PROVIDER / "current-platform"),
                 str(PROVIDER / "output/current-platform/current-base-v1/composition-identity.json")],
                capture_output=True, text=True, timeout=180)
            self.assertEqual(0, result.returncode, result.stdout + result.stderr)
            self.assertEqual(before, snapshot(original))
            summary = json.loads(result.stdout)
            self.assertEqual(352, summary["terrainCount"])
            self.assertEqual(32410, summary["placementCount"])
            self.assertFalse(list(original.rglob("world-builder-target.json")))
            derived = stage / "source/migration/input"
            self.assertFalse(list(derived.rglob("world-builder-target.json")))
            derivation = json.loads((derived / "derivation.json").read_text())
            self.assertFalse(derivation["runtimePromotionApproved"])
            self.assertEqual("compiled-historical-data-only", derivation["authority"])
            self.assertEqual(1, len(derivation["placementCorrections"]))
            self.assertEqual("layered-world-placements-v5", derivation["placementEncoding"])
            self.assertEqual("blocked-void", derivation["npcRoamCoverage"])
            invocation = json.loads((derived / "invocation.json").read_text())
            self.assertEqual(0, invocation["exitCode"])
            self.assertFalse(invocation["runtimePromotionApproved"])
            self.assertEqual("server-runtime", invocation["coreArtifact"]["role"])
            proof = json.loads((derived / "reconciliation.json").read_text())
            self.assertEqual(1680, len(proof["decodedInventory"]))
            self.assertEqual(1412, len(proof["excludedClientSectors"]))
            report = json.loads((stage / "conversion/conversion-report.json").read_text())
            self.assertFalse(report["blocked"])
            self.assertEqual(352, report["terrain"]["reverseMatched"])
            self.assertEqual(0, report["terrain"]["reverseMismatches"])
            self.assertTrue(all(value == 0 for value in report["validation"].values()))
            reconciliation = json.loads((stage / "conversion/discovery-reconciliation.json").read_text())
            self.assertEqual("matched", reconciliation["status"])
            families = {row["family"]: row for row in reconciliation["families"]}
            for family, count in {"boundary": 967, "scenery": 26815, "npc": 3609, "ground-item": 1019}.items():
                self.assertEqual(count, families[family]["effectiveRecords"])
                self.assertEqual(count, families[family]["emittedRecords"])
            package = stage / "conversion/package"
            manifest = json.loads((package / "manifest.json").read_text())
            coverage = {(row["level"], row["sectorX"], row["sectorY"])
                        for row in manifest["terrainSectors"]}
            self.assertEqual(352, len(coverage))
            expected_npcs, emitted_npcs, placement_ids = Counter(), Counter(), set()
            for name in ["NpcLocs.json", "NpcLocsDiscontinued.json"]:
                for row in json.loads((derived / "placements" / name).read_text())["npclocs"]:
                    plane = row["start"]["Y"] // 944
                    level = [0, 1, 2, -1][plane]
                    expected_npcs[(level, row["id"], row["start"]["X"], row["start"]["Y"] % 944,
                                   row["min"]["X"], row["min"]["Y"] % 944,
                                   row["max"]["X"], row["max"]["Y"] % 944, -1)] += 1
            void_bounds = 0
            for declaration in manifest["placementSets"]:
                self.assertEqual("layered-world-placements-v5", declaration["encoding"])
                body = json.loads((package / declaration["path"]).read_text())
                self.assertEqual(5, body["schemaVersion"])
                self.assertEqual("blocked-void", body["npcRoamCoverage"])
                level = body["level"]
                for row in body["npcs"]:
                    start, low, high = row["start"], row["roamBounds"]["minimum"], row["roamBounds"]["maximum"]
                    self.assertNotIn(row["placementId"], placement_ids)
                    placement_ids.add(row["placementId"])
                    self.assertIn((level, start["x"] // 48, start["y"] // 48), coverage)
                    self.assertLessEqual(high["x"] - low["x"], 128)
                    self.assertLessEqual(high["y"] - low["y"], 128)
                    absent = any((level, x, y) not in coverage
                                 for x in range(low["x"] // 48, high["x"] // 48 + 1)
                                 for y in range(low["y"] // 48, high["y"] // 48 + 1))
                    void_bounds += int(absent)
                    emitted_npcs[(level, row["npcId"], start["x"], start["y"], low["x"], low["y"],
                                  high["x"], high["y"], row["respawnSeconds"])] += 1
            self.assertEqual(146, void_bounds)
            self.assertEqual(expected_npcs, emitted_npcs, "retain every exact bound and duplicate, never clamp")


if __name__ == "__main__":
    unittest.main()
