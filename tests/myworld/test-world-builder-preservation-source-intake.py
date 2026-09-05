#!/usr/bin/env python3
"""Sealed source-layout intake; optional exact Git input is never built or launched."""

import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
import zipfile

ROOT = Path(__file__).resolve().parents[2]
JAR = ROOT / "output/world-builder-tools/world-builder-tools.jar"
RESOURCES = ROOT / "tools/world-builder/resources/com/openrsc/worldbuilder"
COMMIT = "c0102e60774ab9c9076aabae49f6f97fb6fc4b00"
TREE = "6db5536d795abf34f303bb03b20c43b8cfb9e3fe"
SOURCE_GIT = os.environ.get("WORLD_BUILDER_PRESERVATION_SOURCE_GIT")
MAIN = "com.openrsc.worldbuilder.PreservationIntakeHarness"
HARNESS = """
package com.openrsc.worldbuilder;
import java.nio.file.Paths;
import java.util.*;
public final class PreservationIntakeHarness {
  public static void main(String[] args) throws Exception {
    WorldBuilderCurrentRuntimeExecutionProfile p = WorldBuilderCurrentRuntimeExecutionProfile.preservation();
    Map<String,Object> result = new LinkedHashMap<String,Object>();
    if ("identity".equals(args[0])) {
      result.put("profile", p.identity()); result.put("adapter", p.adapter.root);
      result.put("fixture", WorldBuilderCurrentRuntimeExecutionProfile.preservationFixture().identity());
      Map<String,Object> forged = WorldBuilderCurrentRuntimeExecutionProfile.preservationFixture().identity();
      forged.put("profileId", "preservation-family-upgrade-v1");
      try {
        WorldBuilderCurrentRuntimeExecutionProfile.fromIdentity(forged);
        throw new AssertionError("renamed fixture became production authority");
      } catch (WorldBuilderContractException expected) { result.put("fixturePromotionRefused", true); }
    } else if ("config".equals(args[0])) {
      result = p.typedConfiguration(Paths.get(args[1]));
    } else if ("reject-zip".equals(args[0])) {
      Map<String,Object> classification = new LinkedHashMap<String,Object>();
      classification.put("evidence", new ArrayList<Object>());
      try {
        p.migrationPlan(Paths.get(args[1]), classification, null, Paths.get(args[1]), Paths.get(args[1]));
        throw new AssertionError("ZIP evidence bypassed JAG migration");
      } catch (WorldBuilderContractException expected) { result.put("refusal", expected.getMessage()); }
    } else {
      result.put("evidence", WorldBuilderCurrentRuntimeContracts.inspectPreservationSource(Paths.get(args[1])));
    }
    System.out.print(WorldBuilderJsonDocuments.pretty(result));
  }
}
"""


def git_bytes(repo, path):
    return subprocess.check_output(["git", "-C", repo, "cat-file", "blob", f"{COMMIT}:{path}"])


class PreservationSourceIntakeTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temporary = tempfile.TemporaryDirectory(prefix="preservation-intake-")
        cls.addClassCleanup(cls.temporary.cleanup)
        cls.root = Path(cls.temporary.name)
        cls.classes = cls.root / "classes"
        cls.classes.mkdir()
        source = cls.root / "PreservationIntakeHarness.java"
        source.write_text(HARNESS, encoding="utf-8")
        built = subprocess.run(["javac", "-source", "8", "-target", "8", "-cp", str(JAR),
                                "-d", str(cls.classes), str(source)], capture_output=True, text=True)
        if built.returncode:
            raise AssertionError(built.stdout + built.stderr)
        cls.metadata = json.loads((RESOURCES / "preservation-c0102e-source-intake.json").read_text())
        cls.baseline = cls.root / "historical-source-input"
        if SOURCE_GIT:
            actual_tree = subprocess.check_output(
                ["git", "-C", SOURCE_GIT, "rev-parse", f"{COMMIT}^{{tree}}"], text=True).strip()
            if actual_tree != TREE:
                raise AssertionError("historical input tree identity mismatch")
            closure = json.loads((RESOURCES / "preservation-c0102e-source-build-dependencies.json").read_text())
            records = [r for r in closure["records"]
                       if not r["path"].startswith(("server/lib/", "PC_Client/lib/"))]
            records += cls.metadata["records"]
            cls.baseline.mkdir()
            for row in records:
                # These are the sealed public source/build/map/definition/template paths only.
                data = git_bytes(SOURCE_GIT, row["path"])
                if len(data) != row["size"] or hashlib.sha256(data).hexdigest() != row["sha256"]:
                    raise AssertionError("historical input bytes mismatch: " + row["path"])
                destination = cls.baseline / row["path"]
                destination.parent.mkdir(parents=True, exist_ok=True)
                destination.write_bytes(data)
                destination.chmod(int(row["mode"], 8) & 0o777)
            # Deliberately invented connection settings, never historical credentials.
            connections = cls.baseline / "server/connections.conf"
            connections.write_text("bind_address: 127.0.0.1\nws_server_port: 43494\ndb_type: sqlite\n")
            connections.chmod(0o600)

    def invoke(self, operation, target=None, jar=JAR):
        result = subprocess.run(["java", "-cp", os.pathsep.join((str(self.classes), str(jar))),
                                 MAIN, operation, str(target or self.root)],
                                text=True, capture_output=True, timeout=30)
        self.assertEqual(0, result.returncode, result.stderr)
        return json.loads(result.stdout)

    def target(self):
        target = Path(tempfile.mkdtemp(prefix="case-", dir=self.root)) / "target"
        shutil.copytree(self.baseline, target)
        return target

    def evidence(self, target):
        return self.invoke("inspect", target)["evidence"]

    def test_production_identity_is_real_source_layout_not_staging_fixture(self):
        value = self.invoke("identity")
        self.assertEqual("production-reviewed", value["adapter"]["evidenceAuthority"])
        self.assertEqual("preservation-c0102e-source-layout-v1", value["adapter"]["historicalRuntimeId"])
        self.assertEqual(1264, len(value["adapter"]["evidenceRules"]))
        self.assertFalse(value["profile"]["executionReady"])
        self.assertFalse(value["fixture"]["executionReady"])
        self.assertIn("JAG map migration/parity", value["profile"]["executionReadinessReason"])
        self.assertNotEqual(value["profile"]["profileId"], value["fixture"]["profileId"])
        self.assertTrue(value["fixturePromotionRefused"])
        evidence = self.evidence(ROOT / "tests/fixtures/current-runtime-upgrade-v1/targets/preservation-t0")
        self.assertTrue(any(r["tier"] == "T5" for r in evidence))

    def test_missing_or_changed_metadata_is_not_authority(self):
        resource = "com/openrsc/worldbuilder/preservation-c0102e-source-intake.json"
        for change in ("missing", "changed"):
            with self.subTest(change=change):
                jar = self.root / (change + ".jar")
                with zipfile.ZipFile(JAR) as original, zipfile.ZipFile(jar, "w") as output:
                    for entry in original.infolist():
                        data = original.read(entry)
                        if entry.filename == resource:
                            if change == "missing":
                                continue
                            data += b" "
                        output.writestr(entry, data)
                result = subprocess.run(["java", "-cp", os.pathsep.join((str(self.classes), str(jar))),
                                         MAIN, "identity"], text=True, capture_output=True)
                self.assertNotEqual(0, result.returncode)
                self.assertIn("sealed historical source intake metadata", result.stderr)

    @unittest.skipUnless(SOURCE_GIT, "Exact historical source Git input required for genuine intake acceptance")
    def test_exact_historical_source_map_and_configuration_are_recognized(self):
        target = self.target()
        rows = self.evidence(target)
        self.assertEqual(1263, len(rows))
        self.assertTrue(all(row["tier"] in ("T0", "T2A") for row in rows),
                        [row for row in rows if row["tier"] not in ("T0", "T2A")])
        typed = self.invoke("config", target)
        self.assertEqual([], typed["configurationBlockers"])
        self.assertEqual([], typed["untranslatedKeys"])
        self.assertEqual("RSC Preservation", typed["serverName"])
        self.assertEqual(43596, typed["gamePort"])
        self.assertEqual("sqlite", typed["databaseMigration"]["engine"])
        self.assertIn("JAG/MEM server maps", self.invoke("reject-zip", target)["refusal"])
        # Default Preservation selects JAG/MEM on the server, not the ZIP fallback.
        for archive in ("maps64.jag", "maps64.mem", "land64.jag", "land64.mem"):
            self.assertTrue(any(r["relativePath"] == "server/conf/server/data/maps/" + archive
                                and r["tier"] == "T0" for r in rows))

    @unittest.skipUnless(SOURCE_GIT, "Exact historical source Git input required for genuine intake acceptance")
    def test_light_effective_configuration_preserves_defaults_and_unknown_behavior_blocks(self):
        for change, expected in (("name", "T2A"), ("gameplay", "T3"), ("missing", "T3"),
                                 ("unknown", "T3"), ("null", "T3")):
            with self.subTest(change=change):
                target = self.target()
                local = target / "server/local.conf"
                text = (target / "server/preservation.conf").read_text()
                if change == "name":
                    text = text.replace("server_name: RSC Preservation", "server_name: Public Custom Server")
                elif change == "gameplay":
                    text = text.replace("custom_landscape: false", "custom_landscape: true")
                elif change == "missing":
                    text = "\n".join(line for line in text.splitlines() if "custom_landscape:" not in line)
                elif change == "unknown":
                    text += "\nnew_unported_behavior: true\n"
                else:
                    text = text.replace("custom_landscape: false", "custom_landscape: null")
                local.write_text(text)
                local.chmod(0o600)
                rows = self.evidence(target)
                row = next(r for r in rows if r["relativePath"] == "server/local.conf")
                self.assertEqual(expected, row["tier"], row)
                if change == "name":
                    self.assertEqual("Public Custom Server", self.invoke("config", target)["serverName"])
                else:
                    self.assertEqual("port-required", row["disposition"])

    @unittest.skipUnless(SOURCE_GIT, "Exact historical source Git input required for genuine intake acceptance")
    def test_source_customizations_and_opaque_artifacts_are_not_accepted(self):
        for path, expected in (("server/src/com/openrsc/server/Server.java", "T4"),
                               ("server/plugins/custom/New.java", "T3"),
                               ("server/core.jar", "T5"), ("server/lib/unreviewed.jar", "T5")):
            with self.subTest(path=path):
                target = self.target()
                changed = target / path
                changed.parent.mkdir(parents=True, exist_ok=True)
                with changed.open("ab") as output:
                    output.write(b"// invented unported delta\n")
                changed.chmod(0o644)
                row = next(r for r in self.evidence(target) if r["relativePath"] == path)
                self.assertEqual(expected, row["tier"], row)

    @unittest.skipUnless(SOURCE_GIT, "Exact historical source Git input required for genuine intake acceptance")
    def test_missing_linked_or_permission_changed_sources_are_blocked(self):
        path = "server/src/com/openrsc/server/Server.java"
        for change in ("missing", "symlink", "hardlink", "mode", "parent-alias"):
            with self.subTest(change=change):
                target = self.target()
                source = target / path
                if change == "mode":
                    source.chmod(0o666)
                elif change == "parent-alias":
                    parent = source.parent
                    moved = target / "server/src-renamed"
                    parent.rename(moved)
                    parent.symlink_to(moved, target_is_directory=True)
                else:
                    data = source.read_bytes()
                    source.unlink()
                    if change != "missing":
                        other = target.parent / "external-evidence.java"
                        other.write_bytes(data)
                        if change == "symlink":
                            source.symlink_to(other)
                        else:
                            os.link(other, source)
                self.assertTrue(any(r["tier"] == "T5" for r in self.evidence(target)))

    @unittest.skipUnless(SOURCE_GIT, "Exact historical source Git input required for genuine intake acceptance")
    def test_unmigrated_side_state_is_not_discardable(self):
        for path in ("server/client.pem", "server/server.pem", "server/badwords.txt",
                     "server/goodwords.txt", "server/alertwords.txt", "Client_Base/clientSettings.conf",
                     "Client_Base/Cache/uid.dat", "server/inc/sqlite/preservation.db"):
            with self.subTest(path=path):
                target = self.target()
                side_state = target / path
                side_state.parent.mkdir(parents=True, exist_ok=True)
                side_state.write_bytes(b"invented non-user side-state sentinel\n")
                side_state.chmod(0o600)
                row = next(r for r in self.evidence(target) if r["relativePath"] == path)
                self.assertEqual("T5", row["tier"], row)
                self.assertEqual("blocker", row["disposition"])


if __name__ == "__main__":
    unittest.main()
