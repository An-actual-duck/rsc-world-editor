#!/usr/bin/env python3
"""Selected Base catalog is relocatable without its source checkout or build commands."""
import hashlib
import importlib.util
import json
import os
import re
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
PROVIDER = ROOT / ".runtime-provider"
JAR = ROOT / "output/world-builder-tools/world-builder-tools.jar"
sys.path.insert(0, str(ROOT / "scripts"))
from world_builder_base_catalog import selected_base_files
HARNESS = """
package com.openrsc.worldbuilder;
import java.nio.file.*;
public final class RelocatedBaseCatalogHarness {
  public static void main(String[] args) throws Exception {
    System.out.print(WorldBuilderJsonDocuments.pretty(WorldBuilderProviderCatalog.resolve(
      Paths.get(args[0], "current-platform"), Paths.get(args[0], "current-platform/composition-identity.json")).identity));
  }
}
"""


def inventory(root):
    return {str(path.relative_to(root)): (path.stat().st_mode & 0o777, hashlib.sha256(path.read_bytes()).hexdigest())
            for path in root.rglob("*") if path.is_file()}


class BaseCatalogExportTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        expected = next(line.split("=", 1)[1] for line in (ROOT / "runtime-provider.lock").read_text().splitlines()
                        if line.startswith("RUNTIME_PROVIDER_COMMIT="))
        actual = subprocess.check_output(["git", "-C", str(PROVIDER), "rev-parse", "HEAD"], text=True).strip()
        if expected != actual or subprocess.run(["git", "-C", str(PROVIDER), "diff", "--quiet", "HEAD", "--"]).returncode:
            raise AssertionError("catalog relocation requires the exact unchanged selected provider")
        subprocess.run(["python3", "scripts/build-current-base.py"], cwd=PROVIDER, check=True, capture_output=True, timeout=240)
        cls.identity = PROVIDER / "output/current-platform/current-base-v1/composition-identity.json"
        cls.temporary = tempfile.TemporaryDirectory(prefix="base-catalog-export-")
        cls.addClassCleanup(cls.temporary.cleanup)
        cls.root = Path(cls.temporary.name)
        source = cls.root / "RelocatedBaseCatalogHarness.java"
        source.write_text(HARNESS)
        cls.classes = cls.root / "classes"
        cls.classes.mkdir()
        subprocess.run(["javac", "-source", "8", "-target", "8", "-cp", str(JAR), "-d", str(cls.classes), str(source)],
                       check=True, capture_output=True)

    def export(self, destination):
        return subprocess.run(["java", "-jar", str(JAR), "export-current-base-catalog", "--provider-catalog-root",
            str(PROVIDER / "current-platform"), "--composition-identity", str(self.identity), "--destination", str(destination)],
            capture_output=True, text=True, timeout=90)

    def resolve(self, destination):
        return subprocess.run(["java", "-cp", os.pathsep.join((str(self.classes), str(JAR))),
            "com.openrsc.worldbuilder.RelocatedBaseCatalogHarness", str(destination)], cwd=destination,
            env={**os.environ, "PATH": "/usr/bin:/bin"}, capture_output=True, text=True, timeout=60)

    def test_exact_selected_source_paths_and_platform_schemas_survive_relocation(self):
        destination = self.root / "new-installation"
        result = self.export(destination)
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        report = json.loads(result.stdout)
        self.assertEqual(json.loads(self.identity.read_text()), report["composition"])
        records = {row["relativePath"]: (int(row["mode"], 8), row["sha256"]) for row in report["files"]}
        self.assertEqual(records, inventory(destination))
        independently_selected = selected_base_files(PROVIDER)
        self.assertEqual(records, {relative: (mode, hashlib.sha256(data).hexdigest())
                                  for relative, (data, mode) in independently_selected.items()})
        spec = importlib.util.spec_from_file_location("candidate_inspector", ROOT / "scripts/inspect-world-builder-v2-candidate.py")
        inspector = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(inspector)
        forbidden = inspector.forbidden_core_hashes(PROVIDER)
        for relative, (data, _) in independently_selected.items():
            if relative.endswith((".jar", ".zip")):
                inspector.validate_nested_archive(data, relative, forbidden, True)
        for updater in ("Update World Builder.sh", "Update World Builder.ps1"):
            source = (ROOT / "release/updater-v2" / updater).read_text()
            selected_block = source.split("# Exact selected Base projection.", 1)[1]
            # The first shell loop is the projection; the next loop lists Editor schemas.
            if updater.endswith(".sh"):
                selected_block = selected_block.split('\n\tdone', 1)[0]
            else:
                selected_block = selected_block.split('\n    foreach ($Schema in @(', 1)[0]
            self.assertEqual(set(records), set(re.findall(r'"([^"\n]+/[^"\n]+)"', selected_block)), updater)
        for relative, (mode, digest) in records.items():
            source = self.identity if relative == "current-platform/composition-identity.json" else PROVIDER / relative
            self.assertEqual(mode, source.stat().st_mode & 0o777, relative)
            self.assertEqual(digest, hashlib.sha256(source.read_bytes()).hexdigest(), relative)
        expected = {row["sourcePath"] for row in json.loads((destination / "current-platform/bundle-specs/current-base-v1.json").read_text())["artifacts"]}
        platform = json.loads((destination / "current-platform/platform/current-platform-r1.json").read_text())
        expected.update("current-platform/" + row["relativePath"] for row in platform["schemaContracts"])
        expected.add("current-platform/composition-identity.json")
        self.assertEqual(expected, set(records))
        self.assertFalse((destination / "server/src").exists())
        self.assertFalse((destination / ".git").exists())
        self.assertFalse((destination / "current-platform/variants/current-advanced-v1.json").exists())
        self.assertFalse((destination / "output/current-platform/current-base-v1/server/core.jar").is_symlink())
        moved = self.root / "relocated installation"
        destination.rename(moved)
        self.assertFalse(destination.exists())
        resolved = self.resolve(moved)
        self.assertEqual(0, resolved.returncode, resolved.stdout + resolved.stderr)
        self.assertEqual(report["composition"], json.loads(resolved.stdout))
        self.assertEqual(records, inventory(moved))
        # Ordinary Java resolution is the proof; copied source-build scripts are never invoked.
        for relative in ("current-platform/schema/current-module-v1.schema.json",
                         "current-platform/composition-identity.json", "output/current-platform/current-base-v1/server/plugins.jar"):
            file = moved / relative
            original = file.read_bytes()
            with self.subTest(changed=relative):
                file.write_bytes(original + b" ")
                if relative.endswith("composition-identity.json"):
                    changed = json.loads(original)
                    changed["bundleInventoryHash"] = "0" * 64
                    file.write_text(json.dumps(changed))
                self.assertNotEqual(0, self.resolve(moved).returncode)
                file.write_bytes(original)
        self.assertEqual(0, self.resolve(moved).returncode)
        before = inventory(moved)
        self.assertNotEqual(0, self.export(moved).returncode)
        self.assertEqual(before, inventory(moved))

    def test_existing_linked_and_provider_overlapping_outputs_are_zero_write_refusals(self):
        existing = self.root / "existing"
        existing.mkdir()
        (existing / "keep.txt").write_text("preserved")
        alias = self.root / "alias"
        alias.symlink_to(existing, target_is_directory=True)
        before = inventory(existing)
        for destination in (existing, alias, PROVIDER / "output/forbidden-catalog-export"):
            with self.subTest(destination=str(destination)):
                self.assertNotEqual(0, self.export(destination).returncode)
                self.assertEqual(before, inventory(existing))
        self.assertFalse((PROVIDER / "output/forbidden-catalog-export").exists())
        self.assertNotEqual(0, self.export(alias / "new-output").returncode)
        self.assertFalse((existing / "new-output").exists())


if __name__ == "__main__":
    unittest.main()
