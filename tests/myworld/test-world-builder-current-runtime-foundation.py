#!/usr/bin/env python3
"""Executable foundation tests for adaptable current-generation upgrades."""

from __future__ import annotations

import hashlib
import json
from pathlib import Path
import subprocess
import unittest
import warnings

try:
    import jsonschema
except ImportError:
    jsonschema = None


ROOT = Path(__file__).resolve().parents[2]
JAR = ROOT / "output/world-builder-tools/world-builder-tools.jar"
SCHEMAS = ROOT / "tools/world-builder/schema"
FIXTURE = ROOT / "tests/fixtures/current-runtime-upgrade-v1"
CONTRACTS = FIXTURE / "contracts"
TARGETS = FIXTURE / "targets"
ZERO_HASH = "0" * 64

CONTRACT_FILES = {
    "platform-release": "platform-release-v2.json",
    "runtime-variant-base": "variant-base-v1.json",
    "runtime-variant-advanced": "variant-advanced-v1.json",
    "module-set-base": "module-set-base-v2.json",
    "module-set-advanced": "module-set-advanced-v2.json",
    "input-adapter": "input-adapter-preservation-v1.json",
    "project-capability": "project-capability-v1.json",
    "target-ledger": "../targets/managed-n/.world-builder/runtime-ledger-v1.json",
}

KIND_BY_CONTRACT = {
    "platform-release": "platform-release",
    "runtime-variant-base": "runtime-variant",
    "runtime-variant-advanced": "runtime-variant",
    "module-set-base": "module-set",
    "module-set-advanced": "module-set",
    "input-adapter": "input-adapter",
    "project-capability": "project-capability",
    "target-ledger": "target-ledger",
}

SCHEMA_FILES = {
    "current-platform-release-v1.schema.json": "platform-release-v2.json",
    "current-runtime-variant-v1.schema.json": "variant-base-v1.json",
    "current-module-set-v1.schema.json": "module-set-base-v2.json",
    "current-input-adapter-v1.schema.json": "input-adapter-preservation-v1.json",
    "current-project-capability-v1.schema.json": "project-capability-v1.json",
    "current-target-runtime-ledger-v1.schema.json": (
        "../targets/managed-n/.world-builder/runtime-ledger-v1.json"
    ),
}


def canonical_hash(value: dict) -> str:
    return hashlib.sha256(
        json.dumps(value, sort_keys=True, separators=(",", ":")).encode("utf-8")
    ).hexdigest()


def tree_hash(root: Path) -> str:
    digest = hashlib.sha256()
    for path in sorted(value for value in root.rglob("*") if value.is_file()):
        for value in (
            path.relative_to(root).as_posix(),
            str(path.stat().st_size),
            hashlib.sha256(path.read_bytes()).hexdigest(),
        ):
            digest.update(value.encode("utf-8"))
            digest.update(b"\0")
    return digest.hexdigest()


def target_snapshot(root: Path) -> dict[str, tuple[int, int, str]]:
    return {
        path.relative_to(root).as_posix(): (
            path.stat().st_size,
            path.stat().st_mtime_ns,
            hashlib.sha256(path.read_bytes()).hexdigest(),
        )
        for path in sorted(value for value in root.rglob("*") if value.is_file())
    }


class CurrentRuntimeFoundationTest(unittest.TestCase):
    maxDiff = None

    def run_cli(self, *arguments: str) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            ["java", "-jar", str(JAR), *arguments],
            cwd=ROOT,
            text=True,
            capture_output=True,
            check=False,
        )

    def classify(self, target: str) -> subprocess.CompletedProcess[str]:
        return self.run_cli(
            "classify-current-target",
            "--target-root",
            str(TARGETS / target),
            "--platform-release",
            str(CONTRACTS / "platform-release-v2.json"),
            "--variant",
            str(CONTRACTS / "variant-base-v1.json"),
            "--module-set",
            str(CONTRACTS / "module-set-base-v2.json"),
            "--input-adapter",
            str(CONTRACTS / "input-adapter-preservation-v1.json"),
            "--project-capability",
            str(CONTRACTS / "project-capability-v1.json"),
        )

    def test_fixture_set_is_synthetic_and_sealed(self) -> None:
        manifest = json.loads((FIXTURE / "fixture-set-v1.json").read_text())
        supplied = manifest["fixtureSetFingerprintSha256"]
        fingerprint_input = dict(manifest)
        fingerprint_input["fixtureSetFingerprintSha256"] = ZERO_HASH
        self.assertEqual(supplied, canonical_hash(fingerprint_input))
        self.assertEqual(
            manifest["contractsInventorySha256"], tree_hash(CONTRACTS)
        )
        self.assertEqual(
            {entry["id"] for entry in manifest["targets"]},
            {path.name for path in TARGETS.iterdir() if path.is_dir()},
        )
        for entry in manifest["targets"]:
            self.assertEqual(entry["treeSha256"], tree_hash(TARGETS / entry["id"]))

        readme = (FIXTURE / "README.md").read_text(encoding="utf-8")
        self.assertIn("synthetic", readme.lower())
        self.assertNotIn("Core-Framework", "\n".join(
            path.as_posix() for path in FIXTURE.rglob("*")
        ))

    def test_all_identity_contracts_have_authoritative_self_fingerprints(self) -> None:
        fingerprint_fields = {
            "platform-release": "platformManifestHash",
            "runtime-variant-base": "variantManifestHash",
            "runtime-variant-advanced": "variantManifestHash",
            "module-set-base": "moduleSetHash",
            "module-set-advanced": "moduleSetHash",
            "input-adapter": "adapterManifestHash",
            "project-capability": "capabilityFingerprintSha256",
            "target-ledger": "ledgerFingerprintSha256",
        }
        for contract, relative in CONTRACT_FILES.items():
            with self.subTest(contract=contract):
                path = CONTRACTS / relative
                document = json.loads(path.read_text(encoding="utf-8"))
                field = fingerprint_fields[contract]
                supplied = document[field]
                document[field] = ZERO_HASH
                self.assertEqual(supplied, canonical_hash(document))
                result = self.run_cli(
                    "validate-current-runtime-contract",
                    "--kind",
                    KIND_BY_CONTRACT[contract],
                    "--document",
                    str(path),
                )
                self.assertEqual(0, result.returncode, result.stderr)

        project = json.loads(
            (CONTRACTS / "project-capability-v1.json").read_text(encoding="utf-8")
        )
        for forbidden in (
            "platformReleaseId",
            "platformManifestHash",
            "variantManifestHash",
            "bundleInventoryHash",
        ):
            self.assertNotIn(forbidden, project)

    @unittest.skipUnless(jsonschema is not None, "optional jsonschema unavailable")
    def test_new_schemas_are_strict_and_accept_the_sealed_vectors(self) -> None:
        common = json.loads(
            (SCHEMAS / "adaptive-contract-definitions-v1.schema.json").read_text()
        )
        store = {common["$id"]: common}
        for schema_name, document_name in SCHEMA_FILES.items():
            with self.subTest(schema=schema_name):
                schema = json.loads((SCHEMAS / schema_name).read_text())
                jsonschema.Draft202012Validator.check_schema(schema)
                self.assertFalse(schema["additionalProperties"])
                self.assertEqual(set(schema["required"]), set(schema["properties"]))
                with warnings.catch_warnings():
                    warnings.simplefilter("ignore", DeprecationWarning)
                    resolver = jsonschema.RefResolver.from_schema(schema, store=store)
                validator = jsonschema.Draft202012Validator(schema, resolver=resolver)
                document = json.loads((CONTRACTS / document_name).read_text())
                errors = list(validator.iter_errors(document))
                self.assertEqual([], errors, [error.message for error in errors])

        classification_schema = json.loads(
            (SCHEMAS / "current-target-classification-v1.schema.json").read_text()
        )
        jsonschema.Draft202012Validator.check_schema(classification_schema)
        result = self.classify("preservation-t0")
        self.assertEqual(0, result.returncode, result.stderr)
        with warnings.catch_warnings():
            warnings.simplefilter("ignore", DeprecationWarning)
            resolver = jsonschema.RefResolver.from_schema(
                classification_schema, store=store
            )
        errors = list(
            jsonschema.Draft202012Validator(
                classification_schema, resolver=resolver
            ).iter_errors(json.loads(result.stdout))
        )
        self.assertEqual([], errors, [error.message for error in errors])

    def test_preservation_and_light_customizations_resolve_to_current_base(self) -> None:
        expected = {
            "preservation-t0": ("UPGRADE_READY", "T0", "replace"),
            "light-config-t2a": (
                "UPGRADE_READY",
                "T2A",
                "typed-configuration",
            ),
            "portable-data-t2b": ("UPGRADE_READY", "T2B", "canonical-data"),
        }
        for target, (status, tier, disposition) in expected.items():
            with self.subTest(target=target):
                before = target_snapshot(TARGETS / target)
                result = self.classify(target)
                after = target_snapshot(TARGETS / target)
                self.assertEqual(0, result.returncode, result.stderr)
                self.assertEqual(before, after)
                report = json.loads(result.stdout)
                self.assertEqual(status, report["status"])
                self.assertEqual(tier, report["tier"])
                self.assertFalse(report["mutationOccurred"])
                self.assertEqual("current-base-v1", report["destination"]["variantId"])
                self.assertIn(disposition, {
                    evidence["disposition"] for evidence in report["evidence"]
                })

    def test_plugin_core_abi_coupling_is_actionable_port_required(self) -> None:
        target = TARGETS / "plugin-core-abi-t4"
        before = target_snapshot(target)
        result = self.classify("plugin-core-abi-t4")
        self.assertEqual(3, result.returncode, result.stderr)
        self.assertEqual(before, target_snapshot(target))
        report = json.loads(result.stdout)
        self.assertEqual("PORT_REQUIRED", report["status"])
        self.assertEqual("T4", report["tier"])
        self.assertFalse(report["mutationOccurred"])
        coupled = next(
            item for item in report["evidence"]
            if item["relativePath"] == "server/plugins/Welcome.java"
        )
        self.assertEqual("port-required", coupled["disposition"])
        self.assertIn("core ABI", coupled["reason"])
        self.assertTrue(any("register" in action for action in report["actions"]))

    def test_managed_n_identity_advances_without_project_recreation(self) -> None:
        target = TARGETS / "managed-n"
        before = target_snapshot(target)
        result = self.classify("managed-n")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(before, target_snapshot(target))
        report = json.loads(result.stdout)
        self.assertEqual("UPGRADE_READY", report["status"])
        self.assertEqual("MANAGED_N", report["tier"])
        self.assertEqual("current-platform-v1", report["installedLedger"]["platformReleaseId"])
        self.assertEqual("current-platform-v2", report["destination"]["platformReleaseId"])
        self.assertEqual("current-base-v1", report["destination"]["variantId"])
        self.assertEqual(
            "11111111-1111-4111-8111-111111111111",
            report["projectCapability"]["projectId"],
        )
        self.assertTrue(any("N-to-N+1" in action for action in report["actions"]))

    def test_t5_unknown_binary_is_structured_zero_write_refusal(self) -> None:
        target = TARGETS / "unsafe-t5"
        before = target_snapshot(target)
        result = self.classify("unsafe-t5")
        self.assertEqual(3, result.returncode, result.stderr)
        self.assertEqual(before, target_snapshot(target))
        report = json.loads(result.stdout)
        self.assertEqual("BLOCKED_UNSAFE", report["status"])
        self.assertEqual("T5", report["tier"])
        self.assertFalse(report["mutationOccurred"])
        opaque = next(
            item for item in report["evidence"]
            if item["relativePath"] == "server/plugins/opaque-plugin.jar"
        )
        self.assertEqual("unclassified", opaque["role"])
        self.assertEqual("blocker", opaque["disposition"])
        self.assertIn("no reviewed role", opaque["reason"])


if __name__ == "__main__":
    unittest.main()
