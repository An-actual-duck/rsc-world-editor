#!/usr/bin/env python3
"""Absent historical filters retain empty semantics; no owner input is rewritten."""
from pathlib import Path
import json
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
JAR = ROOT / "output/world-builder-tools/world-builder-tools.jar"


class InitialSideStateTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory(prefix="initial-side-state-")
        cls.addClassCleanup(cls.temp.cleanup)
        cls.root = Path(cls.temp.name)
        source = cls.root / "InitialSideStateProbe.java"
        source.write_text('''package com.openrsc.worldbuilder;
import java.nio.file.*;
import java.util.*;
public final class InitialSideStateProbe {
  public static void main(String[] args) throws Exception {
    Map<String,Path> inputs = new LinkedHashMap<String,Path>();
    for (String name : Arrays.asList("server.pem", "client.pem", "badwords.txt", "goodwords.txt", "alertwords.txt", "ipbans.txt", "ipbans.temp"))
      inputs.put(name, Paths.get(args[0], name));
    Map<String,Object> outputs = new LinkedHashMap<String,Object>();
    for (Map.Entry<String,Path> entry : WorldBuilderCurrentRuntimeUpgradeTransaction.initialServerSideSources(inputs, Paths.get(args[1])).entrySet())
      outputs.put(entry.getKey(), entry.getValue().toString());
    System.out.print(WorldBuilderJsonDocuments.pretty(outputs));
  }
}
''')
        subprocess.run(["javac", "-cp", str(JAR), "-d", str(cls.root), str(source)], check=True, capture_output=True)

    def test_missing_filters_are_private_empty_and_existing_inputs_are_preserved(self):
        target = self.root / "target"
        target.mkdir()
        transaction = self.root / "transaction"
        transaction.mkdir(mode=0o700)
        (target / "goodwords.txt").write_bytes(b"owner-custom-word\n")
        (target / "server.pem").write_bytes(b"untouched owner-key sentinel")
        before = {p.name: p.read_bytes() for p in target.iterdir()}
        result = self.invoke(target, transaction)
        self.assertEqual(0, result.returncode, result.stderr)
        outputs = json.loads(result.stdout)
        for name in ("badwords.txt", "alertwords.txt"):
            path = Path(outputs[name])
            self.assertEqual(transaction / "initial-empty-filters" / name, path)
            self.assertEqual(b"", path.read_bytes())
            self.assertEqual(0o600, path.stat().st_mode & 0o777)
            self.assertEqual(0o700, path.parent.stat().st_mode & 0o777)
        for name in ("goodwords.txt", "server.pem", "client.pem", "ipbans.txt", "ipbans.temp"):
            self.assertEqual(str(target / name), outputs[name])
        self.assertEqual(before, {p.name: p.read_bytes() for p in target.iterdir()})
        self.assertNotEqual(0, self.invoke(target, transaction).returncode)
        self.assertEqual(before, {p.name: p.read_bytes() for p in target.iterdir()})

    def test_existing_complete_filters_need_no_default_directory(self):
        target = self.root / "complete"
        target.mkdir()
        transaction = self.root / "complete-transaction"
        transaction.mkdir(mode=0o700)
        for name in ("badwords.txt", "goodwords.txt", "alertwords.txt"):
            (target / name).write_bytes(b"owner\n")
        self.assertEqual(0, self.invoke(target, transaction).returncode)
        self.assertEqual([], list(transaction.iterdir()))

    def invoke(self, target, transaction):
        return subprocess.run(["java", "-cp", str(self.root) + ":" + str(JAR),
            "com.openrsc.worldbuilder.InitialSideStateProbe", str(target), str(transaction)],
            capture_output=True, text=True, timeout=20)


if __name__ == "__main__":
    unittest.main()
