#!/usr/bin/env python3
"""Actual locked decoder and full genuine map conversion; never synthetic intake authority."""

import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
PROVIDER = ROOT / ".runtime-provider"
JAR = ROOT / "output/world-builder-tools/world-builder-tools.jar"
SOURCE_GIT = os.environ.get("WORLD_BUILDER_PRESERVATION_SOURCE_GIT")
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


class PreservationMapConversionApiTest(unittest.TestCase):
    def test_internal_entrypoints_compile_without_granting_invocation_authority(self):
        with tempfile.TemporaryDirectory(prefix="preservation-conversion-api-") as temporary:
            compile_harness(Path(temporary))


@unittest.skipUnless(SOURCE_GIT, "genuine reviewed public source Git input unavailable")
class PreservationMapConversionTest(unittest.TestCase):
    def test_inventory_bound_decoder_and_full_conversion_preserve_immutable_inputs(self):
        subprocess.run(["python3", "scripts/build-current-base.py"], cwd=PROVIDER,
                       check=True, capture_output=True, text=True, timeout=240)
        with tempfile.TemporaryDirectory(prefix="preservation-map-conversion-") as temporary:
            case = Path(temporary)
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


if __name__ == "__main__":
    unittest.main()
