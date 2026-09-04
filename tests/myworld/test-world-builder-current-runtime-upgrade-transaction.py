#!/usr/bin/env python3
"""Synthetic-only regression coverage for current-runtime upgrade transactions."""

from __future__ import annotations

import hashlib
import json
import shutil
import subprocess
import tempfile
import unittest
import warnings
from pathlib import Path

try:
    import jsonschema
except ImportError:
    jsonschema = None


ROOT = Path(__file__).resolve().parents[2]
SOURCE_ROOT = ROOT / "tools/world-builder/src"
SCHEMAS = ROOT / "tools/world-builder/schema"
FIXTURE = ROOT / "tests/fixtures/current-runtime-upgrade-v1"
TARGETS = FIXTURE / "targets"
CONTRACTS = FIXTURE / "contracts"
EXTENSION = FIXTURE / "provider-extension"
PROVIDER = ROOT / ".runtime-provider"
ZERO_HASH = "0" * 64


def canonical_hash(value: object) -> str:
    return hashlib.sha256(
        json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
        .encode("utf-8")
    ).hexdigest()


def bind(value: dict, field: str) -> dict:
    result = dict(value)
    result[field] = ZERO_HASH
    result[field] = canonical_hash(result)
    return result


def tree_snapshot(root: Path) -> dict[str, tuple[str, int, str]]:
    result = {}
    for path in sorted(root.rglob("*")):
        relative = path.relative_to(root).as_posix()
        if path.is_symlink():
            result[relative] = ("link", 0, str(path.readlink()))
        elif path.is_dir():
            result[relative] = ("directory", 0, "")
        elif path.is_file():
            result[relative] = (
                "file", path.stat().st_size,
                hashlib.sha256(path.read_bytes()).hexdigest(),
            )
    return result


def materialize_synthetic_bundle_payloads(provider_root: Path) -> None:
    """Fill build-only artifact paths inside a disposable provider test copy."""
    catalog = provider_root / "current-platform"
    for spec_path in sorted((catalog / "bundle-specs").glob("*.json")):
        spec = json.loads(spec_path.read_text(encoding="utf-8"))
        for artifact in spec["artifacts"]:
            source = provider_root / artifact["sourcePath"]
            if not source.exists():
                source.parent.mkdir(parents=True, exist_ok=True)
                source.write_text(
                    f"synthetic-only payload for {artifact['sourcePath']}\n",
                    encoding="utf-8",
                )


class CurrentRuntimeUpgradeTransactionTest(unittest.TestCase):
    maxDiff = None

    @classmethod
    def setUpClass(cls) -> None:
        provider_tool = PROVIDER / "scripts/current-platform-composition.py"
        if not provider_tool.is_file():
            raise AssertionError("materialize the exact runtime-provider lock first")
        cls.shared = tempfile.TemporaryDirectory(prefix="current-upgrade-transaction-")
        cls.shared_root = Path(cls.shared.name)
        cls.classes = cls.shared_root / "classes"
        cls.classes.mkdir()
        sources = sorted(str(path) for path in SOURCE_ROOT.rglob("*.java"))
        subprocess.run(
            ["javac", "-source", "8", "-target", "8", "-d", str(cls.classes), *sources],
            cwd=ROOT, check=True, capture_output=True, text=True,
        )
        harness = cls.shared_root / "harness/com/openrsc/worldbuilder/CurrentUpgradeHarness.java"
        harness.parent.mkdir(parents=True)
        harness.write_text(
            r'''
package com.openrsc.worldbuilder;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.Files;
import java.nio.file.DirectoryStream;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

public final class CurrentUpgradeHarness {
    private static boolean selected(String values, String milestone) {
        for (String value : values.split(",")) if (value.equals(milestone)) return true;
        return false;
    }

    public static void main(String[] args) throws Exception {
        String operation = args[0];
        final String failures = args[1];
        Path target = Paths.get(args[2]);
        Path transactions = Paths.get(args[3]);
        Path catalog = Paths.get(args[4]);
        Path identity = Paths.get(args[5]);
        Path adapter = Paths.get(args[6]);
        Path project = Paths.get(args[7]);
        String transactionId = args[8];
        WorldBuilderCurrentRuntimeUpgradeTransaction.Observer observer =
            new WorldBuilderCurrentRuntimeUpgradeTransaction.Observer() {
                @Override public void observe(String milestone, Path path) throws Exception {
                    if ("during-rollback".equals(milestone)
                        && selected(failures, "tamper-ledger-during-rollback")) {
                        Files.write(path.resolve(".world-builder/runtime-ledger-v1.json"),
                            new byte[] {10, 32, 32, 32, 32, 32, 32, 32, 32},
                            StandardOpenOption.APPEND);
                    }
                    if ("during-rollback".equals(milestone)
                        && selected(failures, "tamper-release-during-rollback")) {
                        Path releases = path.resolve(".world-builder/current-runtime/releases");
                        try (DirectoryStream<Path> entries = Files.newDirectoryStream(releases)) {
                            Path release = entries.iterator().next();
                            Files.write(release.resolve("unexpected.bin"), new byte[] {1},
                                StandardOpenOption.CREATE_NEW);
                        }
                    }
                    if (selected(failures, milestone)) {
                        throw new Exception("injected-" + milestone);
                    }
                }
            };
        WorldBuilderCurrentRuntimeUpgradeTransaction transaction =
            new WorldBuilderCurrentRuntimeUpgradeTransaction(observer);
        try {
        if ("profile-migration".equals(operation)) {
            Map<String,Object> classification = new LinkedHashMap<String,Object>();
            classification.put("evidence", new ArrayList<Object>());
            System.out.print(WorldBuilderJsonDocuments.pretty(
                WorldBuilderCurrentRuntimeExecutionProfile.preservation()
                    .migrationPlan(target, classification)));
        } else if ("preview".equals(operation)) {
            System.out.print(transaction.preview(target, transactions, catalog,
                identity, adapter, project, transactionId).toJson());
        } else if ("preview-production".equals(operation)
            || "apply-production".equals(operation)) {
            WorldBuilderCurrentRuntimeUpgradeTransaction.Preview preview =
                transaction.previewPreservation(target, transactions, catalog,
                    identity, project, transactionId);
            if ("preview-production".equals(operation)) {
                System.out.print(preview.toJson());
            } else {
                System.out.print(transaction.apply(preview,
                    preview.confirmationIdentity()).toJson());
            }
        } else if ("apply".equals(operation) || "apply-wrong".equals(operation)) {
            WorldBuilderCurrentRuntimeUpgradeTransaction.Preview preview =
                transaction.preview(target, transactions, catalog, identity,
                    adapter, project, transactionId);
            String confirmation = "apply-wrong".equals(operation)
                ? "UPGRADE:wrong" : preview.confirmationIdentity();
            System.out.print(transaction.apply(preview, confirmation).toJson());
        } else if ("recover".equals(operation)) {
            System.out.print(transaction.recover(target, transactions, transactionId).toJson());
        } else if ("map-gate".equals(operation)) {
            System.out.print(transaction.mapImportAvailable(target, catalog,
                identity, adapter, project) ? "true" : "false");
        } else {
            throw new IllegalArgumentException(operation);
        }
        } catch (WorldBuilderContractException failure) {
            System.err.println("CODE=" + failure.code());
            throw failure;
        }
    }
}
''', encoding="utf-8"
        )
        subprocess.run(
            ["javac", "-source", "8", "-target", "8", "-cp", str(cls.classes),
             "-d", str(cls.classes), str(harness)],
            cwd=ROOT, check=True, capture_output=True, text=True,
        )

        cls.provider_root = cls.shared_root / "provider"
        shutil.copytree(PROVIDER / "current-platform", cls.provider_root / "current-platform")
        (cls.provider_root / "scripts").mkdir()
        shutil.copy2(provider_tool, cls.provider_root / "scripts/current-platform-composition.py")
        materialize_synthetic_bundle_payloads(cls.provider_root)
        shutil.copytree(EXTENSION / "modules", cls.provider_root / "current-platform/modules")
        shutil.copytree(EXTENSION / "payload", cls.provider_root / "current-platform/synthetic-fixtures")
        overlay = json.loads((EXTENSION / "synthetic-installable-overlay-v1.json").read_text())
        variant_path = cls.provider_root / "current-platform/variants/current-base-v1.json"
        variant = json.loads(variant_path.read_text())
        variant.update(overlay["variantChanges"])
        variant_path.write_text(json.dumps(variant, indent=2) + "\n")
        bundle_path = cls.provider_root / "current-platform/bundle-specs/current-base-v1.json"
        bundle = json.loads(bundle_path.read_text())
        bundle.update(overlay["bundleChanges"])
        bundle_path.write_text(json.dumps(bundle, indent=2) + "\n")
        cls.catalog = cls.provider_root / "current-platform"
        cls.provider_script = cls.provider_root / "scripts/current-platform-composition.py"
        cls.identity = cls._resolve(cls.provider_script, cls.catalog, cls.provider_root,
                                    cls.shared_root / "synthetic-base.json")
        cls.candidate_root = cls.shared_root / "noninstallable-artifact-candidate"
        shutil.copytree(cls.provider_root, cls.candidate_root)
        candidate_variant_path = (
            cls.candidate_root / "current-platform/variants/current-base-v1.json"
        )
        candidate_variant = json.loads(candidate_variant_path.read_text())
        candidate_variant["releaseStatus"] = "artifact-candidate"
        candidate_variant["installable"] = False
        candidate_variant_path.write_text(json.dumps(candidate_variant, indent=2) + "\n")
        candidate_bundle_path = (
            cls.candidate_root / "current-platform/bundle-specs/current-base-v1.json"
        )
        candidate_bundle = json.loads(candidate_bundle_path.read_text())
        candidate_bundle["installable"] = False
        candidate_bundle_path.write_text(json.dumps(candidate_bundle, indent=2) + "\n")
        cls.candidate_catalog = cls.candidate_root / "current-platform"
        cls.candidate_identity = cls._resolve(
            cls.candidate_root / "scripts/current-platform-composition.py",
            cls.candidate_catalog, cls.candidate_root,
            cls.shared_root / "noninstallable-artifact-candidate.json",
        )

    @classmethod
    def _resolve(cls, tool: Path, catalog: Path, payload: Path, output: Path) -> Path:
        result = subprocess.run(
            ["python3", str(tool), "--catalog-root", str(catalog), "resolve",
             "--variant", "current-base-v1", "--payload-root", str(payload),
             "--output", str(output)],
            cwd=ROOT, text=True, capture_output=True, check=False,
        )
        if result.returncode:
            raise AssertionError(result.stderr)
        return output

    @classmethod
    def tearDownClass(cls) -> None:
        cls.shared.cleanup()

    def setUp(self) -> None:
        self.case = tempfile.TemporaryDirectory(prefix="current-upgrade-case-")
        self.case_root = Path(self.case.name)

    def tearDown(self) -> None:
        self.case.cleanup()

    def target(self, fixture: str) -> Path:
        destination = self.case_root / "target"
        shutil.copytree(TARGETS / fixture, destination)
        return destination

    def workspace(self) -> Path:
        path = self.case_root / "transactions"
        path.mkdir()
        return path

    def run_harness(
        self, operation: str, target: Path, workspace: Path, txid: str,
        failures: str = "-", identity: Path | None = None,
        catalog: Path | None = None, adapter: Path | None = None,
    ) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            ["java", "-cp", str(self.classes),
             "com.openrsc.worldbuilder.CurrentUpgradeHarness", operation, failures,
             str(target), str(workspace), str(catalog or self.catalog),
             str(identity or self.identity),
             str(adapter or CONTRACTS / "input-adapter-preservation-v1.json"),
             str(CONTRACTS / "project-capability-v1.json"), txid],
            cwd=ROOT, text=True, capture_output=True, check=False,
        )

    def assert_plan_schema(self, plan: dict) -> None:
        if jsonschema is None:
            return
        common = json.loads((SCHEMAS / "adaptive-contract-definitions-v1.schema.json").read_text())
        classification = json.loads((SCHEMAS / "current-target-classification-v1.schema.json").read_text())
        ledger = json.loads((SCHEMAS / "current-target-runtime-ledger-v1.schema.json").read_text())
        schema = json.loads((SCHEMAS / "current-runtime-upgrade-plan-v1.schema.json").read_text())
        store = {value["$id"]: value for value in (common, classification, ledger, schema)}
        with warnings.catch_warnings():
            warnings.simplefilter("ignore", DeprecationWarning)
            resolver = jsonschema.RefResolver.from_schema(schema, store=store)
        errors = list(jsonschema.Draft202012Validator(schema, resolver=resolver).iter_errors(plan))
        self.assertEqual([], errors, [error.message for error in errors])

    def assert_receipt_schema(self, receipt: dict) -> None:
        if jsonschema is None:
            return
        common = json.loads((SCHEMAS / "adaptive-contract-definitions-v1.schema.json").read_text())
        schema = json.loads((SCHEMAS / "current-runtime-upgrade-receipt-v1.schema.json").read_text())
        with warnings.catch_warnings():
            warnings.simplefilter("ignore", DeprecationWarning)
            resolver = jsonschema.RefResolver.from_schema(
                schema, store={common["$id"]: common, schema["$id"]: schema}
            )
        errors = list(jsonschema.Draft202012Validator(schema, resolver=resolver).iter_errors(receipt))
        self.assertEqual([], errors, [error.message for error in errors])

    def test_preview_is_zero_write_and_semantic_for_bounded_intake_tiers(self) -> None:
        expected = {
            "preservation-t0": ("T0", "replace"),
            "generated-state-t1": ("T1", "discard-generated"),
            "light-config-t2a": ("T2A", "typed-configuration"),
            "portable-data-t2b": ("T2B", "canonical-data"),
            "managed-n": ("MANAGED_N", "replace-ledger"),
        }
        identity = json.loads(self.identity.read_text())
        adapter = json.loads((CONTRACTS / "input-adapter-preservation-v1.json").read_text())
        project = json.loads((CONTRACTS / "project-capability-v1.json").read_text())
        for index, (fixture, (tier, disposition)) in enumerate(expected.items()):
            with self.subTest(fixture=fixture):
                target = self.target(fixture)
                workspace = self.workspace()
                before_target = tree_snapshot(target)
                before_workspace = tree_snapshot(workspace)
                result = self.run_harness("preview", target, workspace, f"preview-{index}")
                self.assertEqual(0, result.returncode, result.stderr)
                self.assertEqual(before_target, tree_snapshot(target))
                self.assertEqual(before_workspace, tree_snapshot(workspace))
                plan = json.loads(result.stdout)
                self.assert_plan_schema(plan)
                self.assertEqual(tier, plan["classificationTier"])
                self.assertIn(disposition, {action["disposition"] for action in plan["semanticActions"]})
                for field in (
                    "platformReleaseId", "platformManifestHash", "schemaSetHash",
                    "variantId", "variantManifestHash", "moduleSetHash",
                    "bundleInventoryHash", "bundleSpecId", "bundleSpecHash",
                    "inputAdapterContractId",
                ):
                    self.assertEqual(identity[field], plan["destination"][field])
                self.assertEqual(adapter["adapterManifestHash"], plan["inputAdapter"]["adapterManifestHash"])
                self.assertEqual(project["capabilityFingerprintSha256"],
                                 plan["projectCapability"]["capabilityFingerprintSha256"])
                self.assertFalse(plan["mapImportAvailableBeforeApply"])
                self.assertFalse(plan["mutationOccurred"])
                self.case.cleanup()
                self.setUp()

    def test_successful_upgrade_backs_up_exact_preimage_and_unlocks_map_import(self) -> None:
        for index, fixture in enumerate((
            "preservation-t0", "generated-state-t1", "light-config-t2a",
            "portable-data-t2b", "managed-n",
        )):
            with self.subTest(fixture=fixture):
                target = self.target(fixture)
                workspace = self.workspace()
                before = tree_snapshot(target)
                txid = f"apply-{index}"
                gated = self.run_harness("map-gate", target, workspace, txid)
                self.assertEqual("false", gated.stdout)
                result = self.run_harness("apply", target, workspace, txid)
                self.assertEqual(0, result.returncode, result.stderr)
                receipt = json.loads((workspace / txid / "receipt.json").read_text())
                self.assert_receipt_schema(receipt)
                self.assertEqual("successful", receipt["status"])
                self.assertTrue(receipt["mutationOccurred"])
                plan = json.loads((workspace / txid / "upgrade-plan.json").read_text())
                self.assert_plan_schema(plan)
                for record in plan["preimageInventory"]:
                    if record["present"]:
                        backup = workspace / txid / "backup" / record["backupRelativePath"]
                        self.assertEqual(record["sha256"], hashlib.sha256(backup.read_bytes()).hexdigest())
                self.assertEqual("external-same-filesystem-outside-active-target", plan["stagingPolicy"])
                self.assertFalse((workspace / txid / "staging").exists())
                self.assertTrue((target / plan["releaseRelativePath"]).is_dir())
                gated = self.run_harness("map-gate", target, workspace, txid)
                self.assertEqual(0, gated.returncode, gated.stderr)
                self.assertEqual("true", gated.stdout)
                artifact = target / plan["artifactPlan"][0]["installRelativePath"]
                artifact.write_bytes(artifact.read_bytes() + b"tamper")
                gated = self.run_harness("map-gate", target, workspace, txid)
                self.assertNotEqual("true", gated.stdout)
                self.assertNotEqual(before, tree_snapshot(target))
                self.case.cleanup()
                self.setUp()

    def test_confirmation_offline_and_blocked_outcomes_are_zero_write(self) -> None:
        cases = (
            ("wrong-confirmation", "apply-wrong", "preservation-t0", self.identity,
             self.catalog, CONTRACTS / "input-adapter-preservation-v1.json", False),
            ("offline", "preview", "preservation-t0", self.identity,
             self.catalog, CONTRACTS / "input-adapter-preservation-v1.json", True),
            ("port-required", "preview", "unported-extension-t3", self.identity,
             self.catalog, CONTRACTS / "input-adapter-preservation-v1.json", False),
            ("unsafe", "preview", "unsafe-t5", self.identity,
             self.catalog, CONTRACTS / "input-adapter-preservation-v1.json", False),
            ("artifact-candidate-noninstallable", "preview", "preservation-t0",
             self.candidate_identity, self.candidate_catalog,
             CONTRACTS / "input-adapter-preservation-v1.json", False),
        )
        for index, (name, operation, fixture, identity, catalog, adapter, offline) in enumerate(cases):
            with self.subTest(case=name):
                target = self.target(fixture)
                workspace = self.workspace()
                if offline:
                    sentinel = target / "server/run/server.pid"
                    sentinel.parent.mkdir(parents=True)
                    sentinel.write_text("synthetic\n")
                before_target = tree_snapshot(target)
                before_workspace = tree_snapshot(workspace)
                result = self.run_harness(operation, target, workspace, f"blocked-{index}",
                                          identity=identity, catalog=catalog, adapter=adapter)
                self.assertNotEqual(0, result.returncode)
                self.assertEqual(before_target, tree_snapshot(target))
                self.assertEqual(before_workspace, tree_snapshot(workspace))
                self.case.cleanup(); self.setUp()

    def test_reviewed_preservation_profile_previews_candidate_but_cannot_activate(self) -> None:
        target = self.target("preservation-t0")
        workspace = self.workspace()
        before_target = tree_snapshot(target)
        before_workspace = tree_snapshot(workspace)
        previewed = self.run_harness(
            "preview-production", target, workspace, "production-preview-1",
            identity=self.candidate_identity, catalog=self.candidate_catalog,
        )
        self.assertEqual(0, previewed.returncode, previewed.stderr)
        plan = json.loads(previewed.stdout)
        self.assert_plan_schema(plan)
        self.assertEqual("NOT_INSTALLABLE", plan["classificationStatus"])
        self.assertFalse(plan["activationAuthorized"])
        self.assertEqual("production-reviewed", plan["inputAdapter"]["evidenceAuthority"])
        self.assertEqual("preservation-family-upgrade-v1",
                         plan["executionProfile"]["profileId"])
        self.assertEqual("Preservation",
                         plan["migrationPlan"]["typedConfiguration"]["serverName"])
        self.assertEqual("named-profile",
                         plan["migrationPlan"]["typedConfiguration"]["precedence"])
        self.assertEqual("exact-packed-to-layered-v2-u16",
                         plan["migrationPlan"]["mapMigration"]["migrationId"])
        self.assertTrue(plan["migrationPlan"]["durableState"])
        self.assertEqual(before_target, tree_snapshot(target))
        self.assertEqual(before_workspace, tree_snapshot(workspace))

        unsafe = self.case_root / "unsafe-target"
        shutil.copytree(TARGETS / "unsafe-t5", unsafe)
        unsafe_workspace = self.case_root / "unsafe-transactions"
        unsafe_workspace.mkdir()
        before_unsafe = tree_snapshot(unsafe)
        blocked = self.run_harness(
            "preview-production", unsafe, unsafe_workspace, "production-unsafe",
            identity=self.candidate_identity, catalog=self.candidate_catalog,
        )
        self.assertNotEqual(0, blocked.returncode)
        self.assertIn("CODE=CONVERSION_BLOCKED", blocked.stderr)
        self.assertEqual(before_unsafe, tree_snapshot(unsafe))
        self.assertEqual({}, tree_snapshot(unsafe_workspace))

        applied = self.run_harness(
            "apply-production", target, workspace, "production-preview-1",
            identity=self.candidate_identity, catalog=self.candidate_catalog,
        )
        self.assertNotEqual(0, applied.returncode)
        self.assertIn("CODE=RUNTIME_UPGRADE_REQUIRED", applied.stderr)
        self.assertEqual(before_target, tree_snapshot(target))
        self.assertEqual(before_workspace, tree_snapshot(workspace))

    def test_supported_cli_uses_only_the_built_in_preservation_profile(self) -> None:
        target = self.target("preservation-t0")
        workspace = self.workspace()
        common = [
            "--target-root", str(target),
            "--transaction-root", str(workspace),
            "--provider-catalog-root", str(self.candidate_catalog),
            "--composition-identity", str(self.candidate_identity),
            "--project-capability", str(CONTRACTS / "project-capability-v1.json"),
            "--transaction-id", "cli-production-preview",
        ]
        before_target = tree_snapshot(target)
        before_workspace = tree_snapshot(workspace)
        previewed = subprocess.run(
            ["java", "-cp", str(self.classes),
             "com.openrsc.worldbuilder.WorldBuilderCli",
             "preview-current-runtime-upgrade", *common,
             "--adapter", "preservation-family-v1"],
            cwd=ROOT, text=True, capture_output=True, check=False,
        )
        self.assertEqual(0, previewed.returncode, previewed.stderr)
        plan = json.loads(previewed.stdout)
        self.assertFalse(plan["activationAuthorized"])
        self.assertEqual(before_target, tree_snapshot(target))
        self.assertEqual(before_workspace, tree_snapshot(workspace))

        rejected = subprocess.run(
            ["java", "-cp", str(self.classes),
             "com.openrsc.worldbuilder.WorldBuilderCli",
             "preview-current-runtime-upgrade", *common,
             "--adapter", str(CONTRACTS / "input-adapter-preservation-v1.json")],
            cwd=ROOT, text=True, capture_output=True, check=False,
        )
        self.assertEqual(2, rejected.returncode)
        self.assertIn("target-supplied code are rejected", rejected.stderr)
        self.assertEqual(before_target, tree_snapshot(target))
        self.assertEqual(before_workspace, tree_snapshot(workspace))

        applied = subprocess.run(
            ["java", "-cp", str(self.classes),
             "com.openrsc.worldbuilder.WorldBuilderCli",
             "apply-current-runtime-upgrade", *common,
             "--adapter", "preservation-family-v1",
             "--confirmation-identity", plan["confirmationIdentity"]],
            cwd=ROOT, text=True, capture_output=True, check=False,
        )
        self.assertEqual(3, applied.returncode)
        self.assertIn("RUNTIME_UPGRADE_REQUIRED", applied.stderr)
        self.assertEqual(before_target, tree_snapshot(target))
        self.assertEqual(before_workspace, tree_snapshot(workspace))

    def test_sealed_configuration_fixture_uses_local_precedence_and_aliases(self) -> None:
        fixture_root = ROOT / "tests/fixtures/preservation-production-migration-v1"
        sealed = json.loads((fixture_root / "fixture-set-v1.json").read_text())
        self.assertTrue(sealed["syntheticOnly"])
        self.assertFalse(sealed["containsUserData"])
        for record in sealed["files"]:
            path = fixture_root / record["relativePath"]
            self.assertEqual(record["size"], path.stat().st_size)
            self.assertEqual(record["sha256"], hashlib.sha256(path.read_bytes()).hexdigest())
        target = self.case_root / "migration-target"
        shutil.copytree(fixture_root / "targets/local-precedence", target)
        workspace = self.workspace()
        result = self.run_harness("profile-migration", target, workspace, "profile-only")
        self.assertEqual(0, result.returncode, result.stderr)
        typed = json.loads(result.stdout)["typedConfiguration"]
        self.assertEqual("server/conf/local.conf", typed["sourceRelativePath"])
        self.assertEqual("local-replaces-named-profile", typed["precedence"])
        self.assertEqual("Local Realm", typed["serverName"])
        self.assertEqual(3, typed["experienceRate"])
        self.assertEqual(43595, typed["gamePort"])
        self.assertEqual("localhost", typed["bindAddress"])
        self.assertEqual("first-value-wins", typed["duplicatePolicy"])
        self.assertEqual([], typed["externalSecretReferences"])

    def test_interruption_and_activation_failure_roll_back_exact_target(self) -> None:
        for milestone in ("after-staging", "after-release-published", "after-ledger-activated"):
            with self.subTest(milestone=milestone):
                target = self.target("portable-data-t2b")
                workspace = self.workspace()
                before = tree_snapshot(target)
                txid = "failure-" + milestone
                result = self.run_harness("apply", target, workspace, txid, milestone)
                self.assertNotEqual(0, result.returncode)
                self.assertEqual(before, tree_snapshot(target))
                receipt = json.loads((workspace / txid / "receipt.json").read_text())
                self.assert_receipt_schema(receipt)
                self.assertEqual("rolled-back", receipt["status"])
                self.assertTrue(receipt["rollbackComplete"])
                self.case.cleanup(); self.setUp()

    def test_interrupted_rollback_preserves_recovery_evidence_and_recovers(self) -> None:
        target = self.target("managed-n")
        workspace = self.workspace()
        before = tree_snapshot(target)
        txid = "recovery-required-1"
        result = self.run_harness(
            "apply", target, workspace, txid,
            "after-ledger-activated,during-rollback",
        )
        self.assertNotEqual(0, result.returncode)
        receipt_path = workspace / txid / "receipt.json"
        receipt = json.loads(receipt_path.read_text())
        self.assert_receipt_schema(receipt)
        self.assertEqual("recovery-required", receipt["status"])
        self.assertTrue(receipt["recoveryRequired"])
        self.assertTrue((workspace / txid / "upgrade-plan.json").is_file())
        self.assertNotEqual(before, tree_snapshot(target))
        recovered = subprocess.run(
            ["java", "-cp", str(self.classes),
             "com.openrsc.worldbuilder.WorldBuilderCli",
             "recover-current-runtime-upgrade",
             "--target-root", str(target), "--transaction-root", str(workspace),
             "--transaction-id", txid],
            cwd=ROOT, text=True, capture_output=True, check=False,
        )
        self.assertEqual(0, recovered.returncode, recovered.stderr)
        self.assertEqual(before, tree_snapshot(target))
        receipt = json.loads(receipt_path.read_text())
        self.assert_receipt_schema(receipt)
        self.assertEqual("rolled-back", receipt["status"])
        self.assertTrue(receipt["rollbackComplete"])

    def test_rollback_preserves_observer_drift_without_destructive_cleanup(self) -> None:
        for index, tamper in enumerate((
            "tamper-ledger-during-rollback", "tamper-release-during-rollback",
        )):
            with self.subTest(tamper=tamper):
                if index:
                    self.case.cleanup(); self.setUp()
                target = self.target("managed-n")
                workspace = self.workspace()
                txid = f"rollback-drift-{index}"
                result = self.run_harness(
                    "apply", target, workspace, txid,
                    "after-ledger-activated," + tamper,
                )
                self.assertNotEqual(0, result.returncode)
                self.assertIn("CODE=RECOVERY_REQUIRED", result.stderr)
                receipt = json.loads((workspace / txid / "receipt.json").read_text())
                self.assertEqual("recovery-required", receipt["status"])
                plan = json.loads((workspace / txid / "upgrade-plan.json").read_text())
                self.assertTrue((target / plan["releaseRelativePath"]).is_dir())
                ledger = target / ".world-builder/runtime-ledger-v1.json"
                predecessor = TARGETS / "managed-n/.world-builder/runtime-ledger-v1.json"
                self.assertNotEqual(ledger.read_bytes(), predecessor.read_bytes())
                if "release" in tamper:
                    self.assertTrue(
                        (target / plan["releaseRelativePath"] / "unexpected.bin").is_file()
                    )

    def test_persisted_recovery_refuses_ledger_or_release_drift_zero_write(self) -> None:
        for index, drift in enumerate(("ledger", "release")):
            with self.subTest(drift=drift):
                if index:
                    self.case.cleanup(); self.setUp()
                target = self.target("managed-n")
                workspace = self.workspace()
                txid = f"persisted-drift-{index}"
                failed = self.run_harness(
                    "apply", target, workspace, txid,
                    "after-ledger-activated,during-rollback",
                )
                self.assertNotEqual(0, failed.returncode)
                plan = json.loads((workspace / txid / "upgrade-plan.json").read_text())
                if drift == "ledger":
                    ledger = target / ".world-builder/runtime-ledger-v1.json"
                    ledger.write_bytes(ledger.read_bytes() + b"\n ")
                else:
                    (target / plan["releaseRelativePath"] / "unexpected.bin").write_bytes(b"x")
                before = tree_snapshot(target)
                recovered = self.run_harness("recover", target, workspace, txid)
                self.assertNotEqual(0, recovered.returncode)
                self.assertIn("CODE=RECOVERY_REQUIRED", recovered.stderr)
                self.assertEqual(before, tree_snapshot(target))
                receipt = json.loads((workspace / txid / "receipt.json").read_text())
                self.assertEqual("recovery-required", receipt["status"])

    def test_map_import_gate_rejects_marker_ledger_and_release_drift_zero_write(self) -> None:
        mutations = (
            ("marker-destination", "activation", "destination"),
            ("marker-project", "activation", "project"),
            ("marker-adapter", "activation", "adapter"),
            ("marker-plan", "activation", "plan"),
            ("marker-execution-profile", "activation", "execution-profile"),
            ("marker-migration", "activation", "migration"),
            ("ledger-launcher", "ledger", "activeLauncherRelativePath"),
            ("ledger-server-build", "ledger", "serverBuildId"),
            ("ledger-map", "ledger", "activeMapPackageId"),
            ("extra-release-file", "release", "extra"),
            ("missing-release-file", "release", "missing"),
            ("tampered-release-file", "release", "tampered"),
            ("tampered-migration-plan", "release", "migration"),
            ("extra-release-directory", "release", "extra-directory"),
            ("linked-release-file", "release", "symlink"),
        )
        for index, (name, location, field) in enumerate(mutations):
            with self.subTest(case=name):
                target = self.target("preservation-t0")
                workspace = self.workspace()
                txid = f"map-gate-{index}"
                applied = self.run_harness("apply", target, workspace, txid)
                self.assertEqual(0, applied.returncode, applied.stderr)
                plan = json.loads((workspace / txid / "upgrade-plan.json").read_text())
                release = target / plan["releaseRelativePath"]
                if location == "activation":
                    marker_path = release / "activation.json"
                    marker = json.loads(marker_path.read_text())
                    if field == "destination":
                        marker["destination"]["platformManifestHash"] = "f" * 64
                    elif field == "project":
                        marker["projectCapability"]["projectId"] = (
                            "00000000-0000-0000-0000-000000000001"
                        )
                    elif field == "adapter":
                        marker["inputAdapter"]["adapterId"] = "different-synthetic-v1"
                    elif field == "execution-profile":
                        marker["executionProfile"]["migratorId"] = "target-selected-migrator"
                    elif field == "migration":
                        marker["migrationPlan"]["migratorId"] = "target-selected-migrator"
                    else:
                        marker["planBindingHash"] = "f" * 64
                    marker_path.write_text(json.dumps(marker))
                elif location == "ledger":
                    ledger_path = target / ".world-builder/runtime-ledger-v1.json"
                    ledger = json.loads(ledger_path.read_text())
                    replacements = {
                        "activeLauncherRelativePath": ".world-builder/other-launcher.json",
                        "serverBuildId": "unexpected-server-build",
                        "activeMapPackageId": "unexpected-map-package",
                    }
                    ledger[field] = replacements[field]
                    ledger_path.write_text(json.dumps(bind(ledger, "ledgerFingerprintSha256")))
                else:
                    artifact = target / plan["artifactPlan"][0]["installRelativePath"]
                    if field == "extra":
                        (release / "unexpected.bin").write_bytes(b"unexpected")
                    elif field == "missing":
                        artifact.unlink()
                    elif field == "tampered":
                        artifact.write_bytes(artifact.read_bytes() + b"tampered")
                    elif field == "migration":
                        migration = release / "migration/migration-plan.json"
                        migration.write_bytes(migration.read_bytes() + b" ")
                    elif field == "extra-directory":
                        (release / "unexpected-directory").mkdir()
                    else:
                        artifact.unlink()
                        artifact.symlink_to(release / "activation.json")
                before = tree_snapshot(target)
                gated = self.run_harness("map-gate", target, workspace, txid)
                self.assertEqual(0, gated.returncode, gated.stderr)
                self.assertEqual("false", gated.stdout)
                self.assertEqual(before, tree_snapshot(target))
                self.case.cleanup(); self.setUp()


if __name__ == "__main__":
    unittest.main()
