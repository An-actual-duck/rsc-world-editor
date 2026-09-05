#!/usr/bin/env python3
"""Pure genuine map reconciliation; external decoder fixtures grant no execution authority."""

import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
JAR = ROOT / "output/world-builder-tools/world-builder-tools.jar"
SOURCE_GIT = os.environ.get("WORLD_BUILDER_PRESERVATION_SOURCE_GIT")
DECODED = os.environ.get("WORLD_BUILDER_PRESERVATION_DECODED_MAP")
COMMIT = "c0102e60774ab9c9076aabae49f6f97fb6fc4b00"
TREE = "6db5536d795abf34f303bb03b20c43b8cfb9e3fe"
MAIN = "com.openrsc.worldbuilder.PreservationReconciliationHarness"
HARNESS = """
package com.openrsc.worldbuilder;
import java.nio.file.Paths;
public final class PreservationReconciliationHarness {
  public static void main(String[] args) throws Exception {
    WorldBuilderPreservationMapReconciliation.Plan plan =
      WorldBuilderPreservationMapReconciliation.inspect(
        Paths.get(args[0]), Paths.get(args[1]), Paths.get(args[2]));
    if (plan.packedSectors().size() != 352) throw new AssertionError("incomplete fused terrain");
    System.out.print(plan.reportJson());
  }
}
"""


@unittest.skipUnless(SOURCE_GIT and DECODED,
                     "genuine public Git source and separately verified decoder fixture unavailable")
class PreservationMapReconciliationTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temporary = tempfile.TemporaryDirectory(prefix="preservation-map-reconcile-")
        cls.addClassCleanup(cls.temporary.cleanup)
        cls.root = Path(cls.temporary.name)
        cls.original = cls.root / "original"
        cls.original.mkdir()
        actual_tree = subprocess.check_output(
            ["git", "-C", SOURCE_GIT, "rev-parse", f"{COMMIT}^{{tree}}"], text=True).strip()
        if actual_tree != TREE:
            raise AssertionError("historical input tree identity mismatch")
        metadata = json.loads((ROOT / "tools/world-builder/resources/com/openrsc/worldbuilder/"
                               "preservation-c0102e-source-intake.json").read_text())
        for row in metadata["records"]:
            data = subprocess.check_output(["git", "-C", SOURCE_GIT, "cat-file", "blob",
                                            f"{COMMIT}:{row['path']}"])
            if len(data) != row["size"] or hashlib.sha256(data).hexdigest() != row["sha256"]:
                raise AssertionError("unreviewed historical input: " + row["path"])
            destination = cls.original / row["path"]
            destination.parent.mkdir(parents=True, exist_ok=True)
            destination.write_bytes(data)
            destination.chmod(int(row["mode"], 8) & 0o777)
        (cls.original / "server/connections.conf").write_text(
            "bind_address: 127.0.0.1\nws_server_port: 43494\ndb_type: sqlite\n")
        cls.classes = cls.root / "classes"
        cls.classes.mkdir()
        source = cls.root / "PreservationReconciliationHarness.java"
        source.write_text(HARNESS)
        compiled = subprocess.run(["javac", "-source", "8", "-target", "8", "-cp", str(JAR),
                                   "-d", str(cls.classes), str(source)], capture_output=True, text=True)
        if compiled.returncode:
            raise AssertionError(compiled.stdout + compiled.stderr)

    def invoke(self, original=None, decoded=None, evidence=None, succeeds=True):
        result = subprocess.run(
            ["java", "-Xmx512m", "-cp", os.pathsep.join((str(self.classes), str(JAR))), MAIN,
             str(original or self.original), str(decoded or Path(DECODED) / "sectors"),
             str(evidence or Path(DECODED) / "evidence.json")],
            text=True, capture_output=True, timeout=60)
        if succeeds:
            self.assertEqual(0, result.returncode, result.stderr)
            return json.loads(result.stdout)
        self.assertNotEqual(0, result.returncode)
        self.assertIn("Exception", result.stderr)

    def test_complete_fieldwise_provenance_and_distinct_reverse_proofs(self):
        report = self.invoke()
        self.assertFalse(report["runtimePromotionApproved"])
        self.assertEqual(1680, len(report["decodedInventory"]))
        self.assertEqual(352, len(report["sectorDerivations"]))
        self.assertEqual(1412, len(report["excludedClientSectors"]))
        self.assertEqual(1328, sum(row["reason"] == "server-probed-absent"
                                   for row in report["excludedClientSectors"]))
        self.assertEqual(291, report["loginOnlyMarkersRetainedInClientSource"])
        self.assertEqual(15468, report["discardedDirectionsRetainedInDecoderEvidence"])
        self.assertEqual("historical-client-visual", report["fieldPolicy"]["elevation"])
        self.assertEqual("historical-server-gameplay", report["fieldPolicy"]["overlay"])
        correction = report["reviewedCorrections"][0]
        self.assertEqual((312, 516, -1), (correction["x"], correction["y"], correction["level"]))
        self.assertEqual((8, 0), (correction["historicalClientOverlay"], correction["canonicalOverlay"]))
        self.assertIn("proof-required", correction["interactionVerification"])
        for row in report["sectorDerivations"]:
            self.assertEqual(row["historicalServerSha256"], row["historicalServerReverseSha256"])
            self.assertEqual(row["fusedPackedSha256"], row["fusedReverseSha256"])
        self.assertTrue(any(row["historicalServerSha256"] != row["fusedPackedSha256"]
                            for row in report["sectorDerivations"]))
        self.assertEqual(6, len(report["placementSources"]))

    def test_decoder_policy_inventory_and_unknown_fields_cannot_be_forged(self):
        baseline = json.loads((Path(DECODED) / "evidence.json").read_text())
        for operation in ("promote", "drop", "unknown"):
            value = json.loads(json.dumps(baseline))
            if operation == "promote":
                value["policy"]["runtimePromotionApproved"] = True
            elif operation == "drop":
                value["inventory"].pop()
            else:
                value["claimedTrusted"] = True
            with self.subTest(operation=operation):
                path = self.root / (operation + ".json")
                path.write_text(json.dumps(value))
                self.invoke(evidence=path, succeeds=False)

    def test_raw_output_drift_extra_files_and_aliases_are_refused(self):
        for operation in ("drift", "extra", "alias"):
            with self.subTest(operation=operation):
                copied = Path(tempfile.mkdtemp(prefix=operation, dir=self.root)) / "sectors"
                shutil.copytree(Path(DECODED) / "sectors", copied)
                raw = next(copied.iterdir())
                if operation == "drift":
                    data = bytearray(raw.read_bytes())
                    data[0] ^= 1
                    raw.write_bytes(data)
                elif operation == "extra":
                    (copied / "unreviewed.raw").write_bytes(b"extra")
                else:
                    name = raw.name
                    raw.unlink()
                    raw.symlink_to(Path(DECODED) / "sectors" / name)
                self.invoke(decoded=copied, succeeds=False)

    def test_missing_selected_content_and_changed_configuration_are_blockers(self):
        for operation in ("definition", "placement", "configuration"):
            with self.subTest(operation=operation):
                copied = Path(tempfile.mkdtemp(prefix=operation, dir=self.root)) / "original"
                shutil.copytree(self.original, copied)
                if operation == "definition":
                    (copied / "server/conf/server/defs/NpcDefsCustom.json").unlink()
                elif operation == "placement":
                    (copied / "server/conf/server/defs/locs/SceneryLocsDiscontinued.json").unlink()
                else:
                    config = copied / "server/preservation.conf"
                    config.write_text(config.read_text().replace("based_map_data: 64", "based_map_data: 63"))
                self.invoke(original=copied, succeeds=False)


if __name__ == "__main__":
    unittest.main()
