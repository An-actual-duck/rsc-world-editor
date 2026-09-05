#!/usr/bin/env python3
"""Verify the packaged metadata-only historical source closure, without a target."""

import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
import zipfile


ROOT = Path(__file__).resolve().parents[2]
JAR = ROOT / "output/world-builder-tools/world-builder-tools.jar"
RESOURCE = "com/openrsc/worldbuilder/preservation-c0102e-source-build-dependencies.json"
MAIN = "com.openrsc.worldbuilder.PreservationClosureHarness"
HARNESS = """
package com.openrsc.worldbuilder;
public final class PreservationClosureHarness {
  public static void main(String[] args) throws Exception {
    if (WorldBuilderPreservationSourceClosure.evidenceRules().size() != 1268)
      throw new AssertionError("incomplete source closure");
    if (!WorldBuilderPreservationSourceClosure.owns("server/build.xml")
        || WorldBuilderPreservationSourceClosure.owns("server/private-secret.txt"))
      throw new AssertionError("unexpected source closure ownership");
    if (!"T3".equals(WorldBuilderPreservationSourceClosure.changedTier(
          "server/plugins/custom/Extra.java"))
        || !"T4".equals(WorldBuilderPreservationSourceClosure.changedTier(
          "server/lib/extra.jar")))
      throw new AssertionError("new executable input lost its classification");
    System.out.print(WorldBuilderJsonDocuments.pretty(
      WorldBuilderPreservationSourceClosure.summary()));
  }
}
"""


class PreservationSourceClosureTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temporary = tempfile.TemporaryDirectory(prefix="preservation-closure-test-")
        cls.addClassCleanup(cls.temporary.cleanup)
        cls.root = Path(cls.temporary.name)
        cls.classes = cls.root / "classes"
        cls.classes.mkdir()
        source = cls.root / "PreservationClosureHarness.java"
        source.write_text(HARNESS, encoding="utf-8")
        built = subprocess.run(
            ["javac", "-source", "8", "-target", "8", "-cp", str(JAR),
             "-d", str(cls.classes), str(source)],
            capture_output=True, text=True,
        )
        if built.returncode:
            raise AssertionError(built.stdout + built.stderr)

    def invoke(self, jar=JAR):
        return subprocess.run(
            ["java", "-cp", os.pathsep.join((str(self.classes), str(jar))), MAIN],
            capture_output=True, text=True, timeout=30,
        )

    def test_packaged_closure_is_exact_metadata_only(self):
        with zipfile.ZipFile(JAR) as archive:
            document = json.loads(archive.read(RESOURCE))
        self.assertEqual(1268, len(document["records"]))
        self.assertTrue(all(set(row) == {"path", "mode", "size", "sha256"}
                            for row in document["records"]))
        digest = hashlib.sha256(json.dumps(
            document["records"], sort_keys=True, separators=(",", ":")
        ).encode()).hexdigest()
        result = self.invoke()
        self.assertEqual(0, result.returncode, result.stderr)
        summary = json.loads(result.stdout)
        self.assertEqual(digest, summary["canonicalRecordsSha256"])
        self.assertEqual(1246, summary["sourceBuildRecordCount"])
        self.assertEqual(22, summary["vendorDependencyRecordCount"])
        self.assertEqual("c0102e60774ab9c9076aabae49f6f97fb6fc4b00",
                         summary["sourceCommit"])

    def test_missing_or_modified_resource_cannot_replace_reviewed_closure(self):
        for operation in ("missing", "modified"):
            with self.subTest(operation=operation):
                copied = self.root / (operation + ".jar")
                with zipfile.ZipFile(JAR) as original, zipfile.ZipFile(copied, "w") as output:
                    for entry in original.infolist():
                        if entry.filename == RESOURCE:
                            if operation == "missing":
                                continue
                            value = json.loads(original.read(entry))
                            value["records"][0]["sha256"] = "0" * 64
                            output.writestr(entry, json.dumps(value).encode())
                        else:
                            output.writestr(entry, original.read(entry))
                result = self.invoke(copied)
                self.assertNotEqual(0, result.returncode)
                self.assertIn("sealed historical source closure", result.stderr)


if __name__ == "__main__":
    unittest.main()
