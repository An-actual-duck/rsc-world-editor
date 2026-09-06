#!/usr/bin/env python3
"""Sealed source-layout intake; optional exact Git input is never built or launched."""

import hashlib
import json
import os
from pathlib import Path
import shutil
import sqlite3
import subprocess
import tempfile
import unittest
import warnings
import zipfile

try:
    import jsonschema
except ImportError:
    jsonschema = None

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
    } else if ("private-inputs".equals(args[0])) {
      result = WorldBuilderPreservationPersistentInputs.inspect(WorldBuilderReadOnlyTarget.open(Paths.get(args[1])), true);
      WorldBuilderPreservationPersistentInputs.validateEvidence(result);
      WorldBuilderPreservationPersistentInputs.reverify(Paths.get(args[1]), result);
      for (Object raw : (List<?>)result.get("inputs")) {
        Map<?,?> row = (Map<?,?>)raw;
        String relative = (String)row.get("relativePath");
        if (Boolean.TRUE.equals(row.get("present"))) {
          if (!Paths.get(args[1]).resolve(relative).equals(WorldBuilderPreservationPersistentInputs.source(Paths.get(args[1]), result, relative)))
            throw new AssertionError("persistent source resolver returned another path");
        } else {
          try { WorldBuilderPreservationPersistentInputs.source(Paths.get(args[1]), result, relative);
            throw new AssertionError("absent persistent input acquired source authority");
          } catch (WorldBuilderContractException refused) { }
        }
      }
      try { WorldBuilderPreservationPersistentInputs.source(Paths.get(args[1]), result, "../foreign-private-key");
        throw new AssertionError("unadmitted private source accepted");
      } catch (WorldBuilderContractException refused) { }
    } else if ("validate-private-inputs".equals(args[0])) {
      result = WorldBuilderJsonDocuments.readObject(Paths.get(args[1]));
      WorldBuilderPreservationPersistentInputs.validateEvidence(result);
    } else if ("migration-preview".equals(args[0])) {
      Map<String,Object> classification = new LinkedHashMap<String,Object>();
      classification.put("evidence", WorldBuilderCurrentRuntimeContracts.inspectPreservationSource(Paths.get(args[1])));
      WorldBuilderProviderCatalog.Composition composition = WorldBuilderProviderCatalog.resolve(Paths.get(args[2]), Paths.get(args[3]));
      result = p.migrationPlan(Paths.get(args[1]), classification, composition, null, null);
      p.validateMigrationPlan(result);
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
        self.assertEqual(1280, len(value["adapter"]["evidenceRules"]))
        self.assertFalse(value["profile"]["executionReady"])
        self.assertFalse(value["fixture"]["executionReady"])
        self.assertIn("JAG map migration/parity", value["profile"]["executionReadinessReason"])
        self.assertIn("exact Current Base definition equivalence", value["profile"]["executionReadinessReason"])
        self.assertIn("ladder-removal/client-void", value["profile"]["executionReadinessReason"])
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

    def test_private_input_policy_binds_only_metadata_and_keeps_schema_pending(self):
        target = Path(tempfile.mkdtemp(prefix="private-inputs-", dir=self.root))
        database = target / "server/inc/sqlite/preservation.db"
        database.parent.mkdir(parents=True)
        with sqlite3.connect(database) as connection:
            connection.execute("CREATE TABLE invented_secret(value TEXT)")
            connection.execute("INSERT INTO invented_secret VALUES ('never-copy-private-player-state')")
        database.chmod(0o600)
        private, public = target / "server/server.pem", target / "server/client.pem"
        subprocess.run(["openssl", "genpkey", "-algorithm", "RSA", "-pkeyopt", "rsa_keygen_bits:512", "-out", str(private)], check=True, capture_output=True)
        subprocess.run(["openssl", "pkey", "-in", str(private), "-pubout", "-out", str(public)], check=True, capture_output=True)
        private.chmod(0o600); public.chmod(0o600)
        before = {str(path.relative_to(target)): hashlib.sha256(path.read_bytes()).hexdigest()
                  for path in target.rglob("*") if path.is_file()}
        value = self.invoke("private-inputs", target)
        self.assertTrue(value["requiredInputsPresent"])
        self.assertFalse(value["activationApproved"])
        self.assertEqual("pending-provider-sealed-migration", value["schemaValidation"])
        self.assertEqual(9, len(value["inputs"]))
        self.assertTrue(all(not row["copyIntoProject"] for row in value["inputs"]))
        self.assertEqual(6, sum(not row["present"] for row in value["inputs"]))
        self.assertNotIn("never-copy-private-player-state", json.dumps(value))
        self.assertNotIn("BEGIN PRIVATE KEY", json.dumps(value))
        self.assertEqual(before, {str(path.relative_to(target)): hashlib.sha256(path.read_bytes()).hexdigest()
                                 for path in target.rglob("*") if path.is_file()})
        def refused():
            result = subprocess.run(["java", "-cp", os.pathsep.join((str(self.classes), str(JAR))), MAIN,
                                     "private-inputs", str(target)], capture_output=True, text=True)
            self.assertNotEqual(0, result.returncode)
            self.assertNotIn("never-copy-private-player-state", result.stderr)
            self.assertNotIn("BEGIN PRIVATE KEY", result.stderr)
        for mode in (0o644, 0o660):
            with self.subTest(database_mode=mode):
                database.chmod(mode); refused()
        database.chmod(0o600)
        sidecar = database.with_name(database.name + "-wal")
        sidecar.write_bytes(b"invented incomplete state"); refused(); sidecar.unlink()
        saved = public.read_bytes()
        public.write_bytes(b"not a public key"); refused(); public.write_bytes(saved)
        public.unlink(); refused(); public.write_bytes(saved); public.chmod(0o600)
        alias = target / "linked-private-key"
        os.link(private, alias); refused(); alias.unlink()
        private.rename(alias); private.symlink_to(alias); refused(); private.unlink(); alias.rename(private)
        self.assertTrue(self.invoke("private-inputs", target)["requiredInputsPresent"])

        evidence = self.root / "private-evidence.json"
        def validate_metadata(document):
            evidence.write_text(json.dumps(document))
            return subprocess.run(["java", "-cp", os.pathsep.join((str(self.classes), str(JAR))),
                                   MAIN, "validate-private-inputs", str(evidence)], capture_output=True, text=True)
        self.assertEqual(0, validate_metadata(value).returncode)
        schema_validator = None
        if jsonschema is not None:
            schema_path = ROOT / "tools/world-builder/schema/current-runtime-upgrade-plan-v1.schema.json"
            schema = json.loads(schema_path.read_text())
            with warnings.catch_warnings():
                warnings.simplefilter("ignore", DeprecationWarning)
                resolver = jsonschema.RefResolver(base_uri=schema_path.as_uri(), referrer=schema)
            schema_validator = jsonschema.Draft202012Validator({"$ref": "#/$defs/persistentInputs"}, resolver=resolver)
            schema_validator.validate(value)
        def bound(document):
            document["fingerprintSha256"] = "0" * 64
            document["fingerprintSha256"] = hashlib.sha256(json.dumps(document, sort_keys=True, separators=(",", ":")).encode()).hexdigest()
            return document
        for change in ("extra", "copy", "private-path", "absent-required", "ready", "unknown-schema", "oversized", "missing", "order", "foreign-role"):
            altered = json.loads(json.dumps(value))
            rows = altered["inputs"]
            with self.subTest(metadata=change):
                if change == "extra": altered["unreviewed"] = True
                elif change == "copy": rows[0]["copyIntoProject"] = True
                elif change == "private-path": rows[0]["relativePath"] = "../../private-key"
                elif change == "absent-required":
                    row = next(r for r in rows if r["requiredForNormalInstance"])
                    row.update(present=False, size=0, sha256="")
                elif change == "ready": altered["activationApproved"] = True
                elif change == "unknown-schema": altered["schemaValidation"] = "validated"
                elif change == "oversized": rows[0]["size"] = 4294967297
                elif change == "missing": rows.pop()
                elif change == "order": rows.reverse()
                elif change == "foreign-role": rows[0]["role"] = "executable"
                self.assertNotEqual(0, validate_metadata(bound(altered)).returncode)
                if schema_validator is not None:
                    self.assertTrue(list(schema_validator.iter_errors(altered)))

    @unittest.skipUnless(SOURCE_GIT, "Exact historical source Git input required for genuine intake acceptance")
    def test_exact_historical_source_map_and_configuration_are_recognized(self):
        target = self.target()
        rows = self.evidence(target)
        self.assertEqual(1270, len(rows))
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

    @unittest.skipUnless(SOURCE_GIT, "Exact historical source Git input required for production discovery")
    def test_production_discovery_admits_jag_without_private_file_copy_authority(self):
        target = self.target()
        database = target / "server/inc/sqlite/preservation.db"
        database.parent.mkdir(parents=True)
        with sqlite3.connect(database) as connection:
            connection.executescript((ROOT / ".runtime-provider/server/database/sqlite/retro.sqlite").read_text())
        database.chmod(0o600)
        private, public = target / "server/server.pem", target / "server/client.pem"
        subprocess.run(["openssl", "genpkey", "-algorithm", "RSA", "-pkeyopt", "rsa_keygen_bits:512", "-out", str(private)], check=True, capture_output=True)
        subprocess.run(["openssl", "pkey", "-in", str(private), "-pubout", "-out", str(public)], check=True, capture_output=True)
        private.chmod(0o600); public.chmod(0o600)
        def discover():
            return subprocess.run(["java", "-jar", str(JAR), "discover-adaptive", "--target-root", str(target)],
                                  capture_output=True, text=True, timeout=40)
        accepted = discover()
        self.assertEqual(0, accepted.returncode, accepted.stdout + accepted.stderr)
        report = json.loads(accepted.stdout)
        self.assertEqual("compatible", report["status"])
        self.assertEqual("historical-jag", report["representation"])
        self.assertEqual("preservation-source-jag-v1", report["capability"]["adapterId"])
        self.assertFalse(report["descriptor"]["present"])
        self.assertFalse((target / "world-builder-target.json").exists())
        paths = [row["relativePath"] for row in report["files"]]
        self.assertNotIn("server/server.pem", paths)
        self.assertNotIn("server/client.pem", paths)
        self.assertNotIn("server/inc/sqlite/preservation.db", paths)
        self.assertIn("pending-provider-sealed-migration", accepted.stdout)
        # The real selected provider supplies migration/tool identity. This is a
        # read-only plan, not a claim that the database schema or project is ready.
        provider = ROOT / ".runtime-provider"
        expected_pin = next(line.split("=", 1)[1] for line in (ROOT / "runtime-provider.lock").read_text().splitlines()
                            if line.startswith("RUNTIME_PROVIDER_COMMIT="))
        self.assertEqual(expected_pin, subprocess.check_output(["git", "-C", str(provider), "rev-parse", "HEAD"], text=True).strip())
        def migration_preview():
            return subprocess.run(["java", "-cp", os.pathsep.join((str(self.classes), str(JAR))), MAIN,
                                   "migration-preview", str(target), str(provider / "current-platform"),
                                   str(provider / "output/current-platform/current-base-v1/composition-identity.json")],
                                  capture_output=True, text=True, timeout=40)
        previewed = migration_preview()
        self.assertEqual(0, previewed.returncode, previewed.stderr)
        plan = json.loads(previewed.stdout)
        self.assertFalse(plan["persistentInputs"]["activationApproved"])
        self.assertEqual("pending-provider-sealed-migration", plan["persistentInputs"]["schemaValidation"])
        self.assertFalse(plan["stagedExecution"]["sqliteSchemaMigrationReady"])
        self.assertTrue(plan["stagedExecution"]["sqliteSnapshotReady"])
        self.assertIn("sqlite-schema-validation-pending-provider-sealed-migration", plan["stagedExecution"]["readinessBlockers"])
        self.assertFalse(plan["mapMigration"]["packageReady"])
        self.assertFalse((target / ".world-builder").exists())
        original = database.read_bytes()
        with sqlite3.connect(database) as connection:
            connection.execute("INSERT INTO players(username,pass,salt) VALUES ('inventedstate','private-password','')")
        changed = discover()
        self.assertEqual(0, changed.returncode, changed.stdout + changed.stderr)
        self.assertNotEqual(report["discoveryFingerprintSha256"], json.loads(changed.stdout)["discoveryFingerprintSha256"])
        self.assertNotIn("private-password", changed.stdout)
        changed_plan = migration_preview()
        self.assertEqual(0, changed_plan.returncode, changed_plan.stderr)
        self.assertNotEqual(plan["migrationPlanFingerprintSha256"], json.loads(changed_plan.stdout)["migrationPlanFingerprintSha256"])
        self.assertNotIn("private-password", changed_plan.stdout)
        database.write_bytes(original)
        for relative, payload in (("server/unknown-private.bin", b"private-password"),
                                  ("server/inc/sqlite/preservation.db-wal", b"unclosed"),
                                  ("server/local.conf", b"custom_landscape: true\n")):
            path = target / relative
            path.write_bytes(payload); path.chmod(0o600)
            with self.subTest(refusal=relative):
                refused = discover()
                self.assertNotEqual(0, refused.returncode)
                self.assertEqual("blocked", json.loads(refused.stdout)["status"])
                self.assertNotIn("private-password", refused.stdout)
                self.assertEqual(payload, path.read_bytes())
            path.unlink()

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
    def test_private_state_is_preserved_or_blocked_never_discarded(self):
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
                portable = path.endswith(("badwords.txt", "goodwords.txt", "alertwords.txt", "clientSettings.conf"))
                self.assertEqual("T2B" if portable else "T5", row["tier"], row)
                self.assertEqual("preserve-state" if portable else "blocker", row["disposition"])
                self.assertEqual(b"invented non-user side-state sentinel\n", side_state.read_bytes())


if __name__ == "__main__":
    unittest.main()
