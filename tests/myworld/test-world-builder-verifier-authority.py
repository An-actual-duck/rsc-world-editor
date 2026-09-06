#!/usr/bin/env python3
"""Editor authority primitives only: invented metadata is never runtime execution proof."""
import copy
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
import jsonschema

ROOT = Path(__file__).resolve().parents[2]
CONTRACT = "227abec8c5180b80a504591083c2d4d000152d2047dba5fa27202cdce56659da"
HASH = "a" * 64


class VerifierAuthorityTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.shared = tempfile.TemporaryDirectory(prefix="editor-authority-primitives-")
        cls.classes = Path(cls.shared.name) / "classes"
        cls.classes.mkdir()
        harness = Path(cls.shared.name) / "AuthorityHarness.java"
        harness.write_text(r'''package com.openrsc.worldbuilder;
import java.nio.file.*;
import java.util.*;
public final class AuthorityHarness {
  public static void main(String[] args) throws Exception {
    try {
      Path root = Paths.get(args[1]);
      if ("retain".equals(args[0])) {
        WorldBuilderCurrentRuntimeVerifierAuthority.retainTools(root, Paths.get(args[2]), Paths.get(args[3]));
      } else if ("private-file".equals(args[0])) {
        java.lang.reflect.Method method = WorldBuilderCurrentRuntimeVerifierAuthority.class.getDeclaredMethod("privateFile", Path.class, long.class);
        method.setAccessible(true); method.invoke(null, root, 65536L);
      } else {
        Map<String,Object> record = WorldBuilderJsonDocuments.readObject(root.resolve("record.json"));
        if ("validate".equals(args[0])) WorldBuilderCurrentRuntimeVerifierAuthority.validate(record, false);
        else if ("snapshot".equals(args[0])) {
          Map<String,Object> frozen = WorldBuilderCurrentRuntimeVerifierAuthority.snapshot(record);
          ((Map<String,Object>)record.get("options")).put("state-db", "changed");
          if ("changed".equals(((Map<?,?>)frozen.get("options")).get("state-db"))) throw new AssertionError("shallow snapshot");
          try { ((Map<String,Object>)frozen.get("options")).put("state-db", "changed"); throw new AssertionError("mutable map"); }
          catch (UnsupportedOperationException expected) { }
          try { ((List<Object>)frozen.get("retainedTools")).clear(); throw new AssertionError("mutable list"); }
          catch (UnsupportedOperationException expected) { }
          try { ((Map<String,Object>)((List<?>)frozen.get("retainedTools")).get(0)).put("size", 2L); throw new AssertionError("mutable nested row"); }
          catch (UnsupportedOperationException expected) { }
        } else if ("receipt".equals(args[0])) {
          Map<String,Object> plan = WorldBuilderJsonDocuments.readObject(root.resolve("plan.json"));
          Map<String,Object> execution = new LinkedHashMap<String,Object>(plan);
          execution.put("runtimeVerificationAttempt", record);
          execution.put("generatedStateOutputs", WorldBuilderJsonDocuments.readObject(root.resolve("generated.json")).get("outputs"));
          System.out.print(WorldBuilderJsonDocuments.pretty(WorldBuilderCurrentRuntimeUpgradeTransaction.receipt(
            execution, "pending", false, false, "", args[2]))); return;
        } else if ("restore".equals(args[0])) {
          Map<String,Object> plan = WorldBuilderJsonDocuments.readObject(root.resolve("plan.json"));
          Map<String,Object> receipt = WorldBuilderJsonDocuments.readObject(root.resolve("receipt.json"));
          Path pending = root.resolve("pending.json");
          System.out.print(WorldBuilderJsonDocuments.pretty(WorldBuilderCurrentRuntimeUpgradeTransaction.restoreExecutionPlan(
            plan, receipt, Files.exists(pending) ? WorldBuilderJsonDocuments.readObject(pending) : null))); return;
        }
        else if ("closure".equals(args[0])) WorldBuilderCurrentRuntimeVerifierAuthority.validateClosure(record,
          WorldBuilderJsonDocuments.readObject(root.resolve("closure.json")));
        else if ("authenticate".equals(args[0])) WorldBuilderCurrentRuntimeVerifierAuthority.authenticate(
          root.resolve("attempt"), record, Collections.emptyList());
        else throw new IllegalArgumentException();
      }
      System.out.println("primitive-accepted");
    } catch (WorldBuilderContractException failure) { System.err.println("CODE=" + failure.code()); throw failure; }
  }
}
''')
        result = subprocess.run(["javac", "-source", "8", "-target", "8", "-d", str(cls.classes),
                                 *map(str, sorted((ROOT / "tools/world-builder/src").rglob("*.java"))), str(harness)],
                                text=True, capture_output=True)
        if result.returncode:
            raise AssertionError(result.stdout + result.stderr)

    @classmethod
    def tearDownClass(cls):
        cls.shared.cleanup()

    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="editor-authority-case-")
        self.root = Path(self.temporary.name)

    def tearDown(self):
        self.temporary.cleanup()

    def run_case(self, mode, *args):
        return subprocess.run(["java", "-cp", str(self.classes), "com.openrsc.worldbuilder.AuthorityHarness",
                               mode, str(self.root), *map(str, args)], text=True, capture_output=True)

    def fixture(self):
        attempt = self.root / "attempt"
        names = ("client-profile", "composition-identity", "installed-client-root", "installed-server-root",
                 "map-package", "runtime-profile", "server-config", "server-profile", "state-db")
        options = {name: str(self.root / "staging" / name) for name in names}
        options.update({"contract": str(attempt / "provider-tools/contract.json"),
                        "composition-identity": str(attempt / "composition-identity.json"),
                        "workspace": str(attempt / "execution"), "evidence": str(attempt / "evidence.json"),
                        "server-port": "44594", "websocket-port": "44494"})
        record = {"schemaVersion": 1, "attemptRoot": str(attempt), "authoritySha256": HASH,
                  "invocationId": "12345678-1234-4234-9234-123456789abc", "invocationSha256": self.invocation_hash(options),
                  "options": options, "retainedTools": [
                      {"relativePath": "provider-tools/core.jar", "size": 1, "sha256": HASH, "mode": "0600"},
                      {"relativePath": "provider-tools/contract.json", "size": 1, "sha256": CONTRACT, "mode": "0600"}]}
        self.write_record(record)
        return record

    @staticmethod
    def invocation_hash(options):
        framed = "".join(name + "\0" + options[name] + "\0" for name in sorted(options))
        return hashlib.sha256(framed.encode()).hexdigest()

    def write_record(self, record):
        (self.root / "record.json").write_text(json.dumps(record))

    def test_exact_shape_and_sorted_nul_framing(self):
        record = self.fixture()
        result = self.run_case("validate")
        self.assertEqual(0, result.returncode, result.stderr)
        for change in ("missing-option", "extra-option", "unknown-field", "wrong-hash", "bad-uuid", "tool-order", "empty"):
            bad = copy.deepcopy(record)
            if change == "missing-option": del bad["options"]["state-db"]
            elif change == "extra-option": bad["options"]["force"] = "true"
            elif change == "unknown-field": bad["pid"] = 123
            elif change == "wrong-hash": bad["invocationSha256"] = HASH
            elif change == "bad-uuid": bad["invocationId"] = "1234"
            elif change == "tool-order": bad["retainedTools"].reverse()
            else: bad = {}
            self.write_record(bad)
            with self.subTest(change=change): self.assertNotEqual(0, self.run_case("validate").returncode)

    def test_paths_ports_and_contract_cannot_be_invented_by_recovery(self):
        record = self.fixture()
        for name, value in (("state-db", "relative.db"), ("state-db", str(self.root / "a/../b")),
                            ("state-db", str(self.root / "attempt/control/inside")),
                            ("server-port", "044594"), ("server-port", "65536"),
                            ("server-port", "44494"), ("workspace", str(self.root / "another"))):
            bad = copy.deepcopy(record)
            bad["options"][name] = value
            bad["invocationSha256"] = self.invocation_hash(bad["options"])
            self.write_record(bad)
            with self.subTest(name=name, value=value): self.assertNotEqual(0, self.run_case("validate").returncode)
        record["retainedTools"][1]["sha256"] = HASH
        self.write_record(record)
        self.assertNotEqual(0, self.run_case("validate").returncode)

    def test_recovery_proof_binding_empty_prestart_intent_and_closed_semantics(self):
        record = self.fixture()
        proof = {"schemaVersion": 1, "manifestType": "current-base-verifier-recovery-evidence", "status": "closed",
                 "verifierContractSha256": CONTRACT, "invocationId": record["invocationId"],
                 "supervisionSha256": HASH, "invocationSha256": record["invocationSha256"],
                 "intentSha256": "", "revocationSha256": HASH, "credentialDeleted": True}
        path = self.root / "closure.json"
        path.write_text(json.dumps(proof))
        result = self.run_case("closure")
        self.assertEqual(0, result.returncode, result.stderr)
        for key, value in (("status", "busy"), ("credentialDeleted", False), ("invocationSha256", HASH),
                           ("supervisionSha256", "b" * 64), ("revocationSha256", ""), ("pid", 1)):
            bad = copy.deepcopy(proof); bad[key] = value; path.write_text(json.dumps(bad))
            with self.subTest(key=key): self.assertNotEqual(0, self.run_case("closure").returncode)

    def test_retention_is_private_exact_and_never_overwrites(self):
        source = self.root / "source"
        source.mkdir()
        core, contract = source / "invented-core", source / "invented-contract"
        core.write_bytes(b"invented bytes, never executable")
        contract.write_bytes(b"invented bytes, never a verifier contract")
        result = self.run_case("retain", core, contract)
        self.assertEqual(0, result.returncode, result.stderr)
        for name, original in (("core.jar", core), ("contract.json", contract)):
            retained = self.root / "provider-tools" / name
            self.assertEqual(original.read_bytes(), retained.read_bytes())
            self.assertEqual(0o600, retained.stat().st_mode & 0o777)
            self.assertEqual(1, retained.stat().st_nlink)
        self.assertEqual(0o700, (self.root / "provider-tools").stat().st_mode & 0o777)
        self.assertNotEqual(0, self.run_case("retain", core, contract).returncode)

    def test_private_file_checks_reject_links_aliases_modes_and_oversize(self):
        file = self.root / "tool"
        file.write_bytes(b"invented"); file.chmod(0o600)
        def inspect(path):
            return subprocess.run(["java", "-cp", str(self.classes), "com.openrsc.worldbuilder.AuthorityHarness",
                                   "private-file", str(path)], text=True, capture_output=True)
        self.assertEqual(0, inspect(file).returncode)
        file.chmod(0o644); self.assertNotEqual(0, inspect(file).returncode); file.chmod(0o600)
        alias = self.root / "alias"; alias.symlink_to(file)
        self.assertNotEqual(0, inspect(alias).returncode)
        hardlink = self.root / "hardlink"; os.link(file, hardlink)
        self.assertNotEqual(0, inspect(file).returncode); hardlink.unlink()
        file.write_bytes(bytes(65537)); self.assertNotEqual(0, inspect(file).returncode)

    def test_unbound_or_missing_tools_never_start_recovery(self):
        self.fixture()
        result = self.run_case("authenticate")
        self.assertNotEqual(0, result.returncode)
        self.assertFalse((self.root / "attempt").exists())

    def test_receipt_schema_closes_each_positional_retained_tool(self):
        record = self.fixture()
        schema_path = ROOT / "tools/world-builder/schema/current-runtime-upgrade-receipt-v1.schema.json"
        schema = json.loads(schema_path.read_text())
        document = {"$ref": "#/$defs/runtimeVerificationAttempt", "$defs": schema["$defs"]}
        resolver = jsonschema.RefResolver(base_uri=schema_path.parent.as_uri() + "/", referrer=document)
        validator = jsonschema.Draft202012Validator(document, resolver=resolver)
        self.assertEqual([], list(validator.iter_errors(record)))
        for index in (0, 1):
            for mutation in ("string", "unknown", "missing-mode", "missing-size", "missing-hash", "oversize"):
                bad = copy.deepcopy(record)
                row = bad["retainedTools"][index]
                if mutation == "string": bad["retainedTools"][index] = "not an object"
                elif mutation == "unknown": row["executable"] = True
                elif mutation == "oversize": row["size"] = 268435457
                else: del row[{"missing-mode": "mode", "missing-size": "size", "missing-hash": "sha256"}[mutation]]
                with self.subTest(index=index, mutation=mutation):
                    self.assertTrue(list(validator.iter_errors(bad)))

    def test_prepared_metadata_is_a_deep_immutable_snapshot(self):
        self.fixture()
        result = self.run_case("snapshot")
        self.assertEqual(0, result.returncode, result.stderr)

    def test_phase_receipts_preserve_authority_without_rewriting_confirmed_plan(self):
        record = self.fixture()
        plan = {"transactionId": "invented-journal", "planFingerprintSha256": HASH,
                "preimageInventoryHash": HASH, "artifactPlanHash": HASH, "verificationEvidenceHash": HASH,
                "executionProfile": {"syntheticOnly": False},
                "activationLedger": {"ledgerFingerprintSha256": HASH, "verificationEvidenceHash": HASH}}
        plan_path = self.root / "plan.json"; plan_path.write_text(json.dumps(plan))
        generated = [{"relativePath": "migration/output/state/" + name, "size": 1, "sha256": HASH, "mode": "0600"}
                     for name in ("current-base-migration-evidence.json", "current-base.db")]
        (self.root / "generated.json").write_text(json.dumps({"outputs": generated}))
        prepared = self.run_case("receipt", "verification-prepared")
        self.assertEqual(0, prepared.returncode, prepared.stderr)
        receipt = json.loads(prepared.stdout)
        (self.root / "receipt.json").write_text(prepared.stdout)
        restored = self.run_case("restore")
        self.assertEqual(0, restored.returncode, restored.stderr)
        self.assertEqual(record, json.loads(restored.stdout)["runtimeVerificationAttempt"])
        self.assertEqual(generated, json.loads(restored.stdout)["generatedStateOutputs"])
        self.assertEqual(plan, json.loads(plan_path.read_text()))
        self.assertEqual(HASH, json.loads(restored.stdout)["planFingerprintSha256"])
        # A prior migration seal may acquire the first authority from an interrupted
        # prepared receipt, but an already-bound phase may not drop or replace it.
        self.write_record({})
        migration = self.run_case("receipt", "migration-staged")
        self.assertEqual(0, migration.returncode, migration.stderr)
        (self.root / "receipt.json").write_text(migration.stdout)
        (self.root / "pending.json").write_text(prepared.stdout)
        restored = self.run_case("restore")
        self.assertEqual(0, restored.returncode, restored.stderr)
        self.assertEqual(record, json.loads(restored.stdout)["runtimeVerificationAttempt"])
        (self.root / "receipt.json").write_text(prepared.stdout)
        (self.root / "pending.json").write_text(migration.stdout)
        self.assertNotEqual(0, self.run_case("restore").returncode)
        (self.root / "pending.json").unlink()
        unbound = self.run_case("receipt", "verification-prepared")
        (self.root / "receipt.json").write_text(unbound.stdout)
        self.assertNotEqual(0, self.run_case("restore").returncode)


if __name__ == "__main__":
    unittest.main()
