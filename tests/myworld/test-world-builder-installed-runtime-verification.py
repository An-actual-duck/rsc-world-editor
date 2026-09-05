#!/usr/bin/env python3
"""Editor-owned supervised invocation and closed runtime evidence acceptance."""
from __future__ import annotations

import copy
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import shutil
import sqlite3
import subprocess
import tempfile
import time
import unittest

ROOT = Path(__file__).resolve().parents[2]
PROVIDER = ROOT / ".runtime-provider"
HASH = "a" * 64


def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


class InstalledRuntimeVerificationTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.shared = tempfile.TemporaryDirectory(prefix="editor-installed-verifier-")
        cls.root = Path(cls.shared.name)
        cls.classes = cls.root / "classes"
        cls.classes.mkdir()
        harness = cls.root / "InstalledVerifierHarness.java"
        harness.write_text(r'''package com.openrsc.worldbuilder;
import java.nio.file.*;
import java.util.*;
public final class InstalledVerifierHarness {
  public static void main(String[] args) throws Exception {
    Path root = Paths.get(args[1]);
    try {
      if ("process".equals(args[0])) {
        final Path cancel = root.resolve("cancel");
        List<String> command = Arrays.asList(Paths.get(System.getProperty("java.home"),"bin","java").toString(),
          "-cp", System.getProperty("java.class.path"), "com.openrsc.worldbuilder.VerifierProcessFixture", args[2]);
        WorldBuilderInstalledRuntimeVerifier.runCommand(command, root,
          new java.util.function.BooleanSupplier() { public boolean getAsBoolean() { return Files.exists(cancel); } },
          Long.parseLong(args[3]), 5L);
        System.out.print("verified");
      } else if ("evidence".equals(args[0])) {
        Map<String,Object> evidence = WorldBuilderJsonDocuments.readObject(root.resolve("evidence.json"));
        Map<String,Object> identity = WorldBuilderJsonDocuments.readObject(root.resolve("identity.json"));
        WorldBuilderInstalledRuntimeVerifier.validateEvidence(evidence, identity, args[2], args[2], args[2], args[2], args[2],
          44594, 44494, root);
        System.out.print("verified");
      } else if ("contract-hash".equals(args[0])) {
        System.out.print(WorldBuilderInstalledRuntimeVerifier.CONTRACT_HASH);
      } else if ("capture-state".equals(args[0])) {
        Map<String,Object> inventory = new LinkedHashMap<String,Object>();
        inventory.put("outputs", WorldBuilderCurrentRuntimeGeneratedState.capture(root));
        System.out.print(WorldBuilderJsonDocuments.pretty(inventory));
      } else if ("verify".equals(args[0])) {
        Path release = Paths.get(args[2]);
        Map<String,Object> migration = WorldBuilderJsonDocuments.readObject(Paths.get(args[3]));
        WorldBuilderProviderCatalog.Composition composition = WorldBuilderProviderCatalog.resolve(Paths.get(args[4]), Paths.get(args[5]));
        List<Object> generated = (List<Object>)WorldBuilderJsonDocuments.readObject(Paths.get(args[6])).get("outputs");
        Map<String,Object> result = WorldBuilderInstalledRuntimeVerifier.verify(release, composition, migration,
          generated, root, null);
        System.out.print(WorldBuilderJsonDocuments.pretty(result));
      } else throw new IllegalArgumentException(args[0]);
    } catch (WorldBuilderContractException e) { System.err.println("CODE="+e.code()); throw e; }
  }
}
class VerifierProcessFixture {
  public static void main(String[] args) throws Exception {
    if (System.getenv("JAVA_TOOL_OPTIONS") != null || System.getenv("_JAVA_OPTIONS") != null
        || System.getenv("JDK_JAVA_OPTIONS") != null || System.getenv("CLASSPATH") != null) System.exit(9);
    Files.write(Paths.get("ready"), new byte[]{1}, StandardOpenOption.CREATE_NEW);
    if ("success".equals(args[0])) return;
    if ("refuse".equals(args[0])) System.exit(2);
    if ("output-limit".equals(args[0])) {
      byte[] bytes = new byte[70000]; Arrays.fill(bytes, (byte)'x'); System.out.write(bytes); System.out.flush();
    }
    System.in.read();
    Files.write(Paths.get("cleaned"), new byte[]{1}, StandardOpenOption.CREATE_NEW);
    System.exit(2);
  }
}
''', encoding="utf-8")
        sources = sorted((ROOT / "tools/world-builder/src").rglob("*.java"))
        subprocess.run(["javac", "-source", "8", "-target", "8", "-d", str(cls.classes),
                        *map(str, sources), str(harness)], check=True, capture_output=True, text=True)
        shutil.copytree(ROOT / "tools/world-builder/resources", cls.classes, dirs_exist_ok=True)

    @classmethod
    def tearDownClass(cls) -> None:
        cls.shared.cleanup()

    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory(prefix="editor-verifier-case-")
        self.case = Path(self.temp.name)

    def tearDown(self) -> None:
        self.temp.cleanup()

    def command(self, operation: str, root: Path, *args: str) -> list[str]:
        return ["java", "-cp", str(self.classes), "com.openrsc.worldbuilder.InstalledVerifierHarness",
                operation, str(root), *map(str, args)]

    def test_supervision_success_refusal_timeout_and_bounded_output(self) -> None:
        for mode in ("success", "refuse", "wait", "output-limit"):
            with self.subTest(mode=mode):
                root = self.case / mode
                root.mkdir()
                completed = subprocess.run(self.command("process", root, mode, "1"),
                                           capture_output=True, text=True, timeout=15)
                self.assertEqual(mode == "success", completed.returncode == 0, completed.stderr)
                if mode in ("wait", "output-limit"):
                    self.assertTrue((root / "cleaned").is_file())
                    self.assertIn("CODE=CONVERSION_BLOCKED", completed.stderr)
                self.assertLess(len(completed.stdout + completed.stderr), 8192)
                diagnostic = root / "verifier-output.log"
                self.assertLessEqual(diagnostic.stat().st_size, 65536)
                self.assertEqual(0o600, diagnostic.stat().st_mode & 0o777)

    def test_editor_cancellation_closes_lifetime_pipe_and_waits_for_cleanup(self) -> None:
        root = self.case / "cancelled"
        root.mkdir()
        process = subprocess.Popen(self.command("process", root, "wait", "30"),
                                   stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        try:
            deadline = time.monotonic() + 10
            while not (root / "ready").exists() and time.monotonic() < deadline:
                self.assertIsNone(process.poll())
                time.sleep(0.02)
            self.assertTrue((root / "ready").exists())
            (root / "cancel").write_bytes(b"cancel")
            stdout, stderr = process.communicate(timeout=10)
            self.assertNotEqual(0, process.returncode)
            self.assertIn("CODE=CONVERSION_BLOCKED", stderr)
            self.assertTrue((root / "cleaned").exists())
            self.assertNotIn("verified", stdout)
        finally:
            if process.poll() is None:
                (root / "cancel").touch(exist_ok=True)
                process.communicate(timeout=10)

    def evidence_fixture(self) -> dict:
        identity = {"platformReleaseId": "rsc-current-platform-r1", "variantId": "current-base-v1",
                    **{key: HASH for key in ("platformManifestHash", "variantManifestHash", "moduleSetHash", "bundleInventoryHash")}}
        (self.case / "identity.json").write_text(json.dumps(identity))
        (self.case / "state").mkdir()
        state = self.case / "state/current_base.db"
        state.write_bytes(b"invented evidence fixture; not a database or runtime proof")
        (self.case / "logs").mkdir()
        logs = []
        for run in (1, 2):
            for role in ("server", "client"):
                path = self.case / f"logs/{role}-{run}.log"
                path.write_bytes(b"invented bounded log")
                logs.append({"run": run, "role": role, "sha256": digest(path), "size": path.stat().st_size, "truncated": False})
        contract = subprocess.run(self.command("contract-hash", self.case), capture_output=True, text=True, check=True).stdout
        return {
            "schemaId": "current-base-installed-execution-evidence-v1", "manifestType": "current-base-installed-execution-evidence",
            "verifierId": "current-base-installed-execution-v1", "verifierContractSha256": contract, "status": "verified",
            "composition": {**identity, "identitySha256": HASH},
            "source": {**{key: HASH for key in ("serverTreeBeforeSha256", "serverTreeAfterSha256", "clientTreeBeforeSha256",
                                                  "clientTreeAfterSha256", "inputSetBeforeSha256", "inputSetAfterSha256")}, "unchanged": True},
            "execution": {"endpoint": "127.0.0.1", "serverPort": 44594, "websocketPort": 44494, "launchCount": 2,
                          "mapPackageFingerprint": HASH, "disposableAccountId": 1, "disposableUsernameSha256": HASH,
                          "workingStateSeededSha256": HASH, "workingStateFinalSha256": digest(state),
                          **{key: True for key in ("disposableStateChanged", "stateOutsideRuntimeRoots", "mapOutsideRuntimeRoots",
                                                   "mapUnchanged", "persistenceVerified", "credentialDeleted")}},
            "runs": [{"run": run, "worldX": 120, "worldY": 648, "coins": 102, "prayer": 12, "magic": 17, "woodcut": 22,
                      "questStage": 3, **{key: True for key in ("handshakeAccepted", "loginAccepted", "canonicalMap", "initialRegion",
                                                              "advancedExcluded", "logoutPersisted")}} for run in (1, 2)],
            "logs": logs,
        }

    def test_closed_evidence_binds_identity_inputs_observations_and_actual_logs(self) -> None:
        evidence = self.evidence_fixture()
        def run(value: dict) -> subprocess.CompletedProcess:
            (self.case / "evidence.json").write_text(json.dumps(value))
            return subprocess.run(self.command("evidence", self.case, HASH), capture_output=True, text=True)
        positive = run(evidence)
        self.assertEqual(0, positive.returncode, positive.stderr)
        changes = [("composition", "identitySha256", "b" * 64), ("composition", "variantId", "current-advanced-v1"),
                   ("source", "inputSetAfterSha256", "b" * 64), ("source", "unchanged", False),
                   ("execution", "endpoint", "0.0.0.0"), ("execution", "launchCount", 1),
                   ("execution", "mapPackageFingerprint", "b" * 64), ("execution", "serverPort", 43594),
                   ("execution", "mapUnchanged", False), ("execution", "credentialDeleted", False),
                   ("execution", "workingStateFinalSha256", HASH), ("execution", "extra", "unknown")]
        for section, key, value in changes:
            with self.subTest(section=section, key=key):
                changed = copy.deepcopy(evidence)
                changed[section][key] = value
                self.assertNotEqual(0, run(changed).returncode)
        for mutation in ("extra-field", "duplicate-run", "restart-state", "duplicate-log", "log-hash", "missing-run"):
            changed = copy.deepcopy(evidence)
            if mutation == "extra-field": changed["force"] = True
            elif mutation == "duplicate-run": changed["runs"][1]["run"] = 1
            elif mutation == "restart-state": changed["runs"][1]["coins"] += 1
            elif mutation == "duplicate-log": changed["logs"][1] = changed["logs"][0]
            elif mutation == "log-hash": changed["logs"][0]["sha256"] = HASH
            else: changed["runs"].pop()
            with self.subTest(mutation=mutation): self.assertNotEqual(0, run(changed).returncode)
        secret = self.case / "execution/credential.json"
        secret.parent.mkdir()
        secret.write_bytes(b"invented leftover credential")
        self.assertNotEqual(0, run(evidence).returncode)
        self.assertTrue(secret.exists(), "evidence refusal must not erase unverified credentials")

    def test_editor_runs_built_pair_against_its_staged_inputs(self) -> None:
        """Real process proof over invented intake data, not public-intake acceptance."""
        self.assertTrue(os.environ.get("DISPLAY"),
                        "Installed runtime verification requires the non-headless GUI test lane")
        subprocess.run(["python3", "scripts/build-current-base.py"], cwd=PROVIDER,
                       check=True, capture_output=True, text=True, timeout=240)
        specification = importlib.util.spec_from_file_location(
            "installed_verifier_upgrade_fixture",
            ROOT / "tests/myworld/test-world-builder-current-runtime-upgrade-transaction.py")
        assert specification is not None and specification.loader is not None
        module = importlib.util.module_from_spec(specification)
        specification.loader.exec_module(module)
        helper_class = module.CurrentRuntimeUpgradeTransactionTest
        helper_class.setUpClass()
        self.addCleanup(helper_class.tearDownClass)
        helper = helper_class()
        helper.setUp()
        self.addCleanup(helper.tearDown)
        target, source, report = helper.complete_packed_target_source()
        database = target / "server/inc/sqlite/preservation.db"
        database.parent.mkdir(parents=True)
        with sqlite3.connect(database) as writable:
            writable.executescript((PROVIDER / "server/database/sqlite/retro.sqlite").read_text())
        original_target, original_source = module.tree_snapshot(target), module.tree_snapshot(source)
        workspace = helper.workspace()
        identity = PROVIDER / "output/current-platform/current-base-v1/composition-identity.json"
        catalog = PROVIDER / "current-platform"
        staged = helper.run_harness("launch-inputs-stage", target, workspace, "real-pair",
                                    identity=identity, catalog=catalog,
                                    packed_source=source, packed_report=report)
        self.assertEqual(0, staged.returncode, staged.stderr)
        release = workspace / "real-pair"
        inventory = workspace / "reviewed-state.json"
        captured = subprocess.run(self.command("capture-state", release), capture_output=True, text=True, check=True)
        inventory.write_text(captured.stdout)
        original_release = module.tree_snapshot(release)
        attempt = self.case / "real-execution"
        verified = subprocess.run(self.command("verify", attempt, release,
                                    workspace / "real-pair.migration.json", catalog, identity, inventory),
                                  capture_output=True, text=True, timeout=550)
        diagnostic = attempt / "verifier-output.log"
        self.assertEqual(0, verified.returncode, verified.stderr +
                         (diagnostic.read_text(errors="replace")[-4000:] if diagnostic.exists() else ""))
        evidence = json.loads(verified.stdout)
        self.assertEqual("verified", evidence["status"])
        self.assertEqual(2, evidence["execution"]["launchCount"])
        self.assertTrue(evidence["execution"]["persistenceVerified"])
        self.assertTrue(evidence["execution"]["credentialDeleted"])
        selected = json.loads(identity.read_text())
        for key in ("platformReleaseId", "platformManifestHash", "variantId", "variantManifestHash",
                    "moduleSetHash", "bundleInventoryHash"):
            self.assertEqual(selected[key], evidence["composition"][key])
        self.assertEqual(original_release, module.tree_snapshot(release))
        self.assertEqual(original_target, module.tree_snapshot(target))
        self.assertEqual(original_source, module.tree_snapshot(source))
        retry = subprocess.run(self.command("verify", attempt, release,
                                 workspace / "real-pair.migration.json", catalog, identity, inventory),
                               capture_output=True, text=True, timeout=15)
        self.assertNotEqual(0, retry.returncode)
        self.assertIn("already exists", retry.stderr)
        self.assertEqual(evidence, json.loads((attempt / "evidence.json").read_text()))
        for index, relative in enumerate((
                "contracts/runtime/current-base-v1/installed-execution-verifier.json",
                "runtime/server/core.jar",
                "migration/output/launch/current-base.conf",
                "migration/output/launch/installed-client.json",
                "migration/output/map/conversion/package/manifest.json",
                "migration/output/state/current-base.db")):
            with self.subTest(changed_input=relative):
                path = release / relative
                saved = path.read_bytes()
                refused_attempt = self.case / f"input-drift-{index}"
                try:
                    path.write_bytes(saved + b" ")
                    refused = subprocess.run(self.command("verify", refused_attempt, release,
                                               workspace / "real-pair.migration.json", catalog, identity, inventory),
                                             capture_output=True, text=True, timeout=30)
                    self.assertNotEqual(0, refused.returncode)
                    self.assertFalse(refused_attempt.exists(), "input drift must refuse before execution")
                    self.assertEqual(saved + b" ", path.read_bytes(), "refusal must not repair inputs")
                finally:
                    path.write_bytes(saved)
        self.assertEqual(original_release, module.tree_snapshot(release))


if __name__ == "__main__":
    unittest.main()
