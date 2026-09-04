#!/usr/bin/env python3
"""Executable contracts for adaptable, zero-write current-runtime classification."""

from __future__ import annotations

import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import tempfile
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
PROVIDER = ROOT / ".runtime-provider"
PROVIDER_CATALOG = PROVIDER / "current-platform"
PROVIDER_TOOL = PROVIDER / "scripts/current-platform-composition.py"
EXTENSION = FIXTURE / "provider-extension"
ZERO_HASH = "0" * 64

EDITOR_CONTRACTS = {
    "input-adapter": CONTRACTS / "input-adapter-preservation-v1.json",
    "project-capability": CONTRACTS / "project-capability-v1.json",
    "target-ledger": TARGETS / "managed-n/.world-builder/runtime-ledger-v1.json",
}

EDITOR_SCHEMAS = {
    "current-input-adapter-v1.schema.json": EDITOR_CONTRACTS["input-adapter"],
    "current-project-capability-v1.schema.json": EDITOR_CONTRACTS["project-capability"],
    "current-target-runtime-ledger-v1.schema.json": EDITOR_CONTRACTS["target-ledger"],
}

PROVIDER_SCHEMA_DOCUMENTS = {
    "current-platform-release-v1.schema.json": (
        PROVIDER_CATALOG / "platform/current-platform-r1.json"
    ),
    "current-variant-v1.schema.json": (
        PROVIDER_CATALOG / "variants/current-base-v1.json"
    ),
    "current-bundle-spec-v1.schema.json": (
        PROVIDER_CATALOG / "bundle-specs/current-base-v1.json"
    ),
    "current-module-v1.schema.json": (
        EXTENSION / "modules/community-welcome-v1.json"
    ),
}


def canonical_hash(value: object) -> str:
    return hashlib.sha256(
        json.dumps(
            value, ensure_ascii=False, sort_keys=True, separators=(",", ":")
        ).encode("utf-8")
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


def bind_fingerprint(value: dict, field: str) -> dict:
    result = dict(value)
    result[field] = ZERO_HASH
    result[field] = canonical_hash(result)
    return result


class CurrentRuntimeFoundationTest(unittest.TestCase):
    maxDiff = None

    @classmethod
    def setUpClass(cls) -> None:
        if not PROVIDER_TOOL.is_file():
            raise AssertionError(
                "materialize the exact runtime-provider lock before running this test"
            )
        cls.temp_directory = tempfile.TemporaryDirectory(
            prefix="world-builder-current-runtime-"
        )
        cls.production_base_identity = cls.resolve_with(
            PROVIDER_TOOL, PROVIDER_CATALOG, PROVIDER,
            "current-base-v1", "production-current-base-v1.json"
        )
        cls.advanced_identity = cls.resolve_with(
            PROVIDER_TOOL, PROVIDER_CATALOG, PROVIDER,
            "current-advanced-v1", "production-current-advanced-v1.json"
        )
        cls.provider_root = Path(cls.temp_directory.name) / "provider"
        shutil.copytree(PROVIDER_CATALOG, cls.provider_root / "current-platform")
        (cls.provider_root / "scripts").mkdir()
        shutil.copy2(
            PROVIDER_TOOL,
            cls.provider_root / "scripts/current-platform-composition.py",
        )
        modules = cls.provider_root / "current-platform/modules"
        modules.mkdir()
        shutil.copy2(
            EXTENSION / "modules/community-welcome-v1.json",
            modules / "community-welcome-v1.json",
        )
        payload = cls.provider_root / "current-platform/synthetic-fixtures"
        payload.mkdir()
        shutil.copy2(
            EXTENSION / "payload/community-welcome-v1.txt",
            payload / "community-welcome-v1.txt",
        )
        overlay = json.loads(
            (EXTENSION / "synthetic-installable-overlay-v1.json").read_text()
        )
        variant_path = cls.provider_root / "current-platform/variants/current-base-v1.json"
        variant = json.loads(variant_path.read_text())
        variant.update(overlay["variantChanges"])
        variant_path.write_text(json.dumps(variant, indent=2) + "\n", encoding="utf-8")
        bundle_path = cls.provider_root / "current-platform/bundle-specs/current-base-v1.json"
        bundle = json.loads(bundle_path.read_text())
        bundle.update(overlay["bundleChanges"])
        bundle_path.write_text(json.dumps(bundle, indent=2) + "\n", encoding="utf-8")
        cls.catalog_root = cls.provider_root / "current-platform"
        cls.tool = cls.provider_root / "scripts/current-platform-composition.py"
        validated = cls.run_provider("validate")
        if validated.returncode != 0:
            raise AssertionError(validated.stderr)
        cls.base_identity = cls.resolve("current-base-v1")
        cls.module_identity = cls.resolve(
            "current-base-v1", "community-welcome-v1"
        )

    @classmethod
    def tearDownClass(cls) -> None:
        cls.temp_directory.cleanup()

    @classmethod
    def run_provider(cls, command: str, *arguments: str) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            [
                "python3",
                str(cls.tool),
                "--catalog-root",
                str(cls.catalog_root),
                command,
                *arguments,
            ],
            cwd=ROOT,
            text=True,
            capture_output=True,
            check=False,
        )

    @classmethod
    def resolve_with(
        cls, tool: Path, catalog: Path, payload: Path, variant: str, filename: str
    ) -> Path:
        output = Path(cls.temp_directory.name) / filename
        result = subprocess.run(
            [
                "python3", str(tool), "--catalog-root", str(catalog), "resolve",
                "--variant", variant, "--payload-root", str(payload),
                "--output", str(output),
            ],
            cwd=ROOT, text=True, capture_output=True, check=False,
        )
        if result.returncode != 0:
            raise AssertionError(result.stderr)
        return output

    @classmethod
    def resolve(cls, variant: str, module: str | None = None) -> Path:
        output = Path(cls.temp_directory.name) / (
            variant + ("-" + module if module else "") + ".json"
        )
        arguments = [
            "--variant",
            variant,
            "--payload-root",
            str(cls.provider_root),
            "--output",
            str(output),
        ]
        if module:
            arguments.extend(["--module", module])
        result = cls.run_provider("resolve", *arguments)
        if result.returncode != 0:
            raise AssertionError(result.stderr)
        return output

    def run_cli(self, *arguments: str) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            ["java", "-jar", str(JAR), *arguments],
            cwd=ROOT,
            text=True,
            capture_output=True,
            check=False,
        )

    def classify(
        self, target: Path | str, composition: Path | None = None,
        catalog: Path | None = None, adapter: Path | None = None,
    ) -> subprocess.CompletedProcess[str]:
        target_root = target if isinstance(target, Path) else TARGETS / target
        return self.run_cli(
            "classify-current-target",
            "--target-root",
            str(target_root),
            "--provider-catalog-root",
            str(catalog or self.catalog_root),
            "--composition-identity",
            str(composition or self.base_identity),
            "--input-adapter",
            str(adapter or EDITOR_CONTRACTS["input-adapter"]),
            "--project-capability",
            str(EDITOR_CONTRACTS["project-capability"]),
        )

    def clone_provider(self, name: str) -> tuple[Path, Path]:
        root = Path(self.temp_directory.name) / name
        shutil.copytree(self.provider_root, root)
        return root, root / "current-platform"

    def current_target(self, identity_path: Path, name: str) -> Path:
        identity = json.loads(identity_path.read_text())
        predecessor = json.loads(EDITOR_CONTRACTS["target-ledger"].read_text())
        current = dict(predecessor)
        for key in (
            "platformReleaseId", "platformManifestHash", "schemaSetHash",
            "variantId", "variantManifestHash", "moduleSetHash",
            "bundleInventoryHash", "bundleSpecId", "bundleSpecHash",
            "inputAdapterContractId",
        ):
            current[key] = identity[key]
        current["predecessorIdentityHash"] = predecessor["ledgerFingerprintSha256"]
        current["serverBuildId"] = "current-server-r1"
        current["clientBuildId"] = "current-client-r1"
        current["transactionReceiptIds"] = ["upgrade-0001", "upgrade-0002"]
        current = bind_fingerprint(current, "ledgerFingerprintSha256")
        metadata = Path(self.temp_directory.name) / name / ".world-builder"
        metadata.mkdir(parents=True)
        (metadata / "runtime-ledger-v1.json").write_text(
            json.dumps(current), encoding="utf-8"
        )
        return metadata.parent

    def test_fixture_set_is_synthetic_and_sealed(self) -> None:
        manifest = json.loads((FIXTURE / "fixture-set-v1.json").read_text())
        supplied = manifest["fixtureSetFingerprintSha256"]
        fingerprint_input = dict(manifest)
        fingerprint_input["fixtureSetFingerprintSha256"] = ZERO_HASH
        self.assertEqual(supplied, canonical_hash(fingerprint_input))
        self.assertEqual(manifest["contractsInventorySha256"], tree_hash(CONTRACTS))
        self.assertEqual(
            manifest["providerExtensionInventorySha256"], tree_hash(EXTENSION)
        )
        self.assertEqual(
            {entry["id"] for entry in manifest["targets"]},
            {path.name for path in TARGETS.iterdir() if path.is_dir()},
        )
        for entry in manifest["targets"]:
            self.assertEqual(entry["treeSha256"], tree_hash(TARGETS / entry["id"]))
        readme = (FIXTURE / "README.md").read_text(encoding="utf-8").lower()
        self.assertIn("synthetic", readme)
        self.assertIn("non-installable", readme)
        self.assertNotIn(
            "core-framework",
            "\n".join(path.as_posix().lower() for path in FIXTURE.rglob("*")),
        )

    def test_editor_owned_contracts_have_valid_self_fingerprints(self) -> None:
        fingerprint_fields = {
            "input-adapter": "adapterManifestHash",
            "project-capability": "capabilityFingerprintSha256",
            "target-ledger": "ledgerFingerprintSha256",
        }
        for kind, path in EDITOR_CONTRACTS.items():
            with self.subTest(kind=kind):
                document = json.loads(path.read_text(encoding="utf-8"))
                field = fingerprint_fields[kind]
                supplied = document[field]
                document[field] = ZERO_HASH
                self.assertEqual(supplied, canonical_hash(document))
                result = self.run_cli(
                    "validate-current-runtime-contract",
                    "--kind",
                    kind,
                    "--document",
                    str(path),
                )
                self.assertEqual(0, result.returncode, result.stderr)

    @unittest.skipUnless(jsonschema is not None, "optional jsonschema unavailable")
    def test_editor_and_provider_schemas_accept_exact_authority_documents(self) -> None:
        common = json.loads(
            (SCHEMAS / "adaptive-contract-definitions-v1.schema.json").read_text()
        )
        store = {common["$id"]: common}
        for schema_name, document_path in EDITOR_SCHEMAS.items():
            with self.subTest(authority="editor", schema=schema_name):
                schema = json.loads((SCHEMAS / schema_name).read_text())
                jsonschema.Draft202012Validator.check_schema(schema)
                self.assertFalse(schema["additionalProperties"])
                self.assertEqual(set(schema["required"]), set(schema["properties"]))
                with warnings.catch_warnings():
                    warnings.simplefilter("ignore", DeprecationWarning)
                    resolver = jsonschema.RefResolver.from_schema(schema, store=store)
                errors = list(
                    jsonschema.Draft202012Validator(
                        schema, resolver=resolver
                    ).iter_errors(json.loads(document_path.read_text()))
                )
                self.assertEqual([], errors, [error.message for error in errors])

        provider_schema_root = PROVIDER_CATALOG / "schema"
        for schema_name, document_path in PROVIDER_SCHEMA_DOCUMENTS.items():
            with self.subTest(authority="provider", schema=schema_name):
                schema = json.loads((provider_schema_root / schema_name).read_text())
                jsonschema.Draft202012Validator.check_schema(schema)
                errors = list(
                    jsonschema.Draft202012Validator(schema).iter_errors(
                        json.loads(document_path.read_text())
                    )
                )
                self.assertEqual([], errors, [error.message for error in errors])

        composition_schema = json.loads(
            (provider_schema_root / "current-composition-identity-v1.schema.json").read_text()
        )
        for identity in (
            self.production_base_identity,
            self.base_identity,
            self.advanced_identity,
            self.module_identity,
        ):
            with self.subTest(composition=identity.name):
                errors = list(
                    jsonschema.Draft202012Validator(composition_schema).iter_errors(
                        json.loads(identity.read_text())
                    )
                )
                self.assertEqual([], errors, [error.message for error in errors])

        classification_schema = json.loads(
            (SCHEMAS / "current-target-classification-v1.schema.json").read_text()
        )
        jsonschema.Draft202012Validator.check_schema(classification_schema)
        self.assertFalse(classification_schema["additionalProperties"])
        with warnings.catch_warnings():
            warnings.simplefilter("ignore", DeprecationWarning)
            resolver = jsonschema.RefResolver.from_schema(
                classification_schema, store=store
            )
        reports = [
            self.classify("preservation-t0"),
            self.classify(
                "preservation-t0", self.production_base_identity, PROVIDER_CATALOG
            ),
            self.classify("plugin-core-abi-t4"),
            self.classify("unsafe-t5"),
        ]
        for result in reports:
            errors = list(
                jsonschema.Draft202012Validator(
                    classification_schema, resolver=resolver
                ).iter_errors(json.loads(result.stdout))
            )
            self.assertEqual([], errors, [error.message for error in errors])

    def test_provider_identity_tampering_fails_closed_before_target_access(self) -> None:
        identity = json.loads(self.base_identity.read_text())
        identity["platformManifestHash"] = "f" * 64
        tampered = Path(self.temp_directory.name) / "tampered-composition.json"
        tampered.write_text(json.dumps(identity), encoding="utf-8")
        result = self.classify(Path(self.temp_directory.name) / "missing-target", tampered)
        self.assertEqual(3, result.returncode)
        self.assertIn("platformManifestHash", result.stderr)

    def test_provider_identity_and_ledger_cannot_omit_transitive_bindings(self) -> None:
        identity = json.loads(self.base_identity.read_text())
        required = (
            "schemaSetHash", "bundleSpecId", "bundleSpecHash",
            "inputAdapterContractId",
        )
        for field in required:
            with self.subTest(document="composition", field=field):
                omitted = dict(identity)
                del omitted[field]
                path = Path(self.temp_directory.name) / f"identity-without-{field}.json"
                path.write_text(json.dumps(omitted), encoding="utf-8")
                result = self.classify(
                    Path(self.temp_directory.name) / "missing-target", path
                )
                self.assertEqual(3, result.returncode)

            with self.subTest(document="ledger", field=field):
                target = self.current_target(self.base_identity, f"ledger-without-{field}")
                ledger_path = target / ".world-builder/runtime-ledger-v1.json"
                ledger = json.loads(ledger_path.read_text())
                del ledger[field]
                ledger_path.write_text(json.dumps(ledger), encoding="utf-8")
                before = target_snapshot(target)
                result = self.classify(target)
                self.assertEqual(3, result.returncode, result.stderr)
                self.assertEqual(before, target_snapshot(target))
                report = json.loads(result.stdout)
                self.assertEqual("BLOCKED_UNSAFE", report["status"])
                self.assertEqual("T5", report["tier"])

        result = self.classify("preservation-t0")
        self.assertEqual(0, result.returncode, result.stderr)
        destination = json.loads(result.stdout)["destination"]
        for field in (
            "platformReleaseId", "platformManifestHash", "variantId",
            "variantManifestHash", "moduleSetHash", "bundleInventoryHash",
            *required,
        ):
            self.assertEqual(identity[field], destination[field], field)

    def test_adapter_variant_and_provider_boundary_must_match(self) -> None:
        adapter = json.loads(EDITOR_CONTRACTS["input-adapter"].read_text())
        adapter["recommendedVariantId"] = "current-advanced-v1"
        adapter = bind_fingerprint(adapter, "adapterManifestHash")
        adapter_path = Path(self.temp_directory.name) / "wrong-variant-adapter.json"
        adapter_path.write_text(json.dumps(adapter), encoding="utf-8")
        result = self.classify(
            Path(self.temp_directory.name) / "missing-target", adapter=adapter_path
        )
        self.assertEqual(3, result.returncode)
        self.assertIn("recommends a different current variant", result.stderr)

        _root, catalog = self.clone_provider("wrong-adapter-boundary-provider")
        platform_path = catalog / "platform/current-platform-r1.json"
        platform = json.loads(platform_path.read_text())
        platform["inputAdapterBoundary"]["contractId"] = "other-adapter-v1"
        platform_path.write_text(json.dumps(platform), encoding="utf-8")
        identity = json.loads(self.base_identity.read_text())
        identity["platformManifestHash"] = canonical_hash(platform)
        identity_path = Path(self.temp_directory.name) / "wrong-adapter-boundary.json"
        identity_path.write_text(json.dumps(identity), encoding="utf-8")
        result = self.classify(
            Path(self.temp_directory.name) / "missing-target",
            identity_path, catalog,
        )
        self.assertEqual(3, result.returncode)
        self.assertIn("adapter boundary", result.stderr)

    def test_artifact_resolution_matches_provider_and_rejects_unsafe_sources(self) -> None:
        result = self.classify("preservation-t0")
        self.assertEqual(0, result.returncode, result.stderr)
        identity = json.loads(self.base_identity.read_text())
        report = json.loads(result.stdout)
        self.assertEqual(identity["bundleInventoryHash"], report["destination"]["bundleInventoryHash"])

        for field, replacement in (
            ("mode", "0777"),
            ("size", identity["bundleInventory"][0]["size"] + 1),
            ("sha256", "f" * 64),
        ):
            with self.subTest(canonical_field=field):
                tampered = json.loads(self.base_identity.read_text())
                tampered["bundleInventory"][0][field] = replacement
                tampered["bundleInventoryHash"] = canonical_hash(
                    tampered["bundleInventory"]
                )
                path = Path(self.temp_directory.name) / f"tampered-{field}-inventory.json"
                path.write_text(json.dumps(tampered), encoding="utf-8")
                failure = self.classify("preservation-t0", path)
                self.assertEqual(3, failure.returncode)
                self.assertIn("inventory", failure.stderr.lower())

        for case in ("missing", "symlink"):
            with self.subTest(case=case):
                root, catalog = self.clone_provider(f"unsafe-artifact-{case}")
                tool = root / "scripts/current-platform-composition.py"
                tool.unlink()
                if case == "symlink":
                    tool.symlink_to(root / "current-platform/README.md")
                before = target_snapshot(TARGETS / "preservation-t0")
                failure = self.classify(
                    "preservation-t0", self.base_identity, catalog
                )
                self.assertEqual(3, failure.returncode)
                self.assertEqual(before, target_snapshot(TARGETS / "preservation-t0"))
                self.assertRegex(failure.stderr, "missing|symbolic|regular")

        _root, catalog = self.clone_provider("unsafe-artifact-escape")
        bundle_path = catalog / "bundle-specs/current-base-v1.json"
        bundle = json.loads(bundle_path.read_text())
        bundle["artifacts"][-1]["sourcePath"] = "../outside.py"
        bundle_path.write_text(json.dumps(bundle), encoding="utf-8")
        identity = json.loads(self.base_identity.read_text())
        identity["bundleSpecHash"] = canonical_hash(bundle)
        identity_path = Path(self.temp_directory.name) / "escape-composition.json"
        identity_path.write_text(json.dumps(identity), encoding="utf-8")
        failure = self.classify("preservation-t0", identity_path, catalog)
        self.assertEqual(3, failure.returncode)
        self.assertIn("unsafe", failure.stderr.lower())

    def test_foundation_only_provider_identity_never_grants_activation_authority(self) -> None:
        target = TARGETS / "preservation-t0"
        before = target_snapshot(target)
        result = self.classify(
            target, self.production_base_identity, PROVIDER_CATALOG
        )
        self.assertEqual(3, result.returncode, result.stderr)
        self.assertEqual(before, target_snapshot(target))
        report = json.loads(result.stdout)
        self.assertEqual("NOT_INSTALLABLE", report["status"])
        self.assertEqual("T0", report["tier"])
        self.assertFalse(report["mutationOccurred"])
        self.assertFalse(report["destination"]["installable"])
        self.assertTrue(any("foundation-only" in action for action in report["actions"]))

        current_target = self.current_target(
            self.production_base_identity, "foundation-current-target"
        )
        before = target_snapshot(current_target)
        result = self.classify(
            current_target, self.production_base_identity, PROVIDER_CATALOG
        )
        self.assertEqual(3, result.returncode, result.stderr)
        self.assertEqual(before, target_snapshot(current_target))
        report = json.loads(result.stdout)
        self.assertEqual("NOT_INSTALLABLE", report["status"])
        self.assertEqual("CURRENT", report["tier"])
        self.assertFalse(report["mutationOccurred"])

    def test_t0_t1_and_light_customizations_resolve_to_current_base(self) -> None:
        expected = {
            "preservation-t0": ("T0", "replace"),
            "generated-state-t1": ("T1", "discard-generated"),
            "light-config-t2a": ("T2A", "typed-configuration"),
            "portable-data-t2b": ("T2B", "canonical-data"),
        }
        for target, (tier, disposition) in expected.items():
            with self.subTest(target=target):
                before = target_snapshot(TARGETS / target)
                result = self.classify(target)
                self.assertEqual(0, result.returncode, result.stderr)
                self.assertEqual(before, target_snapshot(TARGETS / target))
                report = json.loads(result.stdout)
                self.assertEqual("UPGRADE_READY", report["status"])
                self.assertEqual(tier, report["tier"])
                self.assertFalse(report["mutationOccurred"])
                self.assertEqual("current-base-v1", report["destination"]["variantId"])
                self.assertTrue(report["destination"]["installable"])
                self.assertIn(
                    disposition,
                    {evidence["disposition"] for evidence in report["evidence"]},
                )

    def test_t3_maintained_extension_maps_only_to_selected_current_module(self) -> None:
        target = TARGETS / "maintained-extension-t3"
        before = target_snapshot(target)
        selected = self.classify(target, self.module_identity)
        self.assertEqual(0, selected.returncode, selected.stderr)
        self.assertEqual(before, target_snapshot(target))
        report = json.loads(selected.stdout)
        self.assertEqual("UPGRADE_READY", report["status"])
        self.assertEqual("T3", report["tier"])
        mapped = next(
            item for item in report["evidence"]
            if item["relativePath"] == "server/plugins/Welcome.java"
        )
        self.assertEqual("mapped-to-module", mapped["disposition"])
        self.assertEqual("community-welcome-v1", mapped["moduleId"])
        self.assertTrue(report["destination"]["installable"])

        unselected = self.classify(target, self.base_identity)
        self.assertEqual(3, unselected.returncode)
        self.assertEqual(before, target_snapshot(target))
        self.assertIn("absent from the provider composition", unselected.stderr)

    def test_t3_unported_and_t4_abi_extension_require_actionable_port(self) -> None:
        expected = {
            "unported-extension-t3": "T3",
            "plugin-core-abi-t4": "T4",
        }
        for target_name, tier in expected.items():
            with self.subTest(target=target_name):
                target = TARGETS / target_name
                before = target_snapshot(target)
                result = self.classify(target)
                self.assertEqual(3, result.returncode, result.stderr)
                self.assertEqual(before, target_snapshot(target))
                report = json.loads(result.stdout)
                self.assertEqual("PORT_REQUIRED", report["status"])
                self.assertEqual(tier, report["tier"])
                evidence = next(
                    item for item in report["evidence"]
                    if item["relativePath"] == "server/plugins/Welcome.java"
                )
                self.assertEqual("port-required", evidence["disposition"])
                self.assertTrue(any("register" in action for action in report["actions"]))

    def test_managed_n_and_exact_current_ledgers_preserve_project_identity(self) -> None:
        managed = TARGETS / "managed-n"
        before = target_snapshot(managed)
        result = self.classify(managed)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(before, target_snapshot(managed))
        report = json.loads(result.stdout)
        self.assertEqual("UPGRADE_READY", report["status"])
        self.assertEqual("MANAGED_N", report["tier"])
        self.assertEqual("rsc-current-platform-r0", report["installedLedger"]["platformReleaseId"])
        self.assertEqual("rsc-current-platform-r1", report["destination"]["platformReleaseId"])
        self.assertEqual(
            "11111111-1111-4111-8111-111111111111",
            report["projectCapability"]["projectId"],
        )
        self.assertTrue(any("N-to-N+1" in action for action in report["actions"]))

        current_root = self.current_target(self.base_identity, "current-target")
        before = target_snapshot(current_root)
        result = self.classify(current_root)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(before, target_snapshot(current_root))
        report = json.loads(result.stdout)
        self.assertEqual("CURRENT", report["status"])
        self.assertEqual("CURRENT", report["tier"])
        self.assertTrue(any("map-only import" in action for action in report["actions"]))

    def test_t5_unknown_binary_is_structured_zero_write_refusal(self) -> None:
        target = TARGETS / "unsafe-t5"
        before = target_snapshot(target)
        result = self.classify(target)
        self.assertEqual(3, result.returncode, result.stderr)
        self.assertEqual(before, target_snapshot(target))
        report = json.loads(result.stdout)
        self.assertEqual("BLOCKED_UNSAFE", report["status"])
        self.assertEqual("T5", report["tier"])
        self.assertFalse(report["mutationOccurred"])
        opaque = next(
            item for item in report["evidence"]
            if item["relativePath"] == "server/plugins/opaque-plugin.bin"
        )
        self.assertEqual("unclassified", opaque["role"])
        self.assertEqual("blocker", opaque["disposition"])
        self.assertIn("no reviewed role", opaque["reason"])


if __name__ == "__main__":
    unittest.main()
