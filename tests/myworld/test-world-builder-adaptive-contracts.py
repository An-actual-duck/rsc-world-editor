#!/usr/bin/env python3
"""Adversarial, read-only coverage for World Builder adaptive contracts."""

from __future__ import annotations

import copy
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
import warnings

try:
    import jsonschema
except ImportError:  # The repository's executable Java validator remains authoritative.
    jsonschema = None


ROOT = Path(__file__).resolve().parents[2]
SOURCE_ROOT = ROOT / "tools" / "world-builder" / "src"
SCHEMA_ROOT = ROOT / "tools" / "world-builder" / "schema"
HASH_A = "a" * 64
HASH_B = "b" * 64
HASH_C = "c" * 64
HASH_D = "d" * 64
PROJECT_ID = "11111111-1111-4111-8111-111111111111"
TRANSACTION_ID = "22222222-2222-4222-8222-222222222222"
UNDO_ID = "33333333-3333-4333-8333-333333333333"

HARNESS = r"""
package com.openrsc.worldbuilder;

import java.nio.file.Paths;

public final class AdaptiveContractHarness {
    public static void main(String[] arguments) throws Exception {
        try {
            if (arguments.length == 3 && "validate".equals(arguments[0])) {
                WorldBuilderAdaptiveContracts.Kind kind =
                    WorldBuilderAdaptiveContracts.Kind.named(arguments[1]);
                WorldBuilderAdaptiveContracts.Document document =
                    WorldBuilderAdaptiveContracts.read(kind, Paths.get(arguments[2]));
                System.out.println(document.canonicalSha256);
                System.out.print(document.canonicalJson);
                return;
            }
            if (arguments.length == 2 && "path".equals(arguments[0])) {
                String value = WorldBuilderPortablePath.require(arguments[1], "test-path");
                System.out.println(value);
                System.out.println(WorldBuilderPortablePath.collisionKey(value, "test-path"));
                return;
            }
            System.err.println("USAGE");
            System.exit(2);
        } catch (WorldBuilderContractException refusal) {
            System.err.println(refusal.code() + "|" + refusal.operation() + "|"
                + refusal.relativePath() + "|" + refusal.mutationOccurred() + "|"
                + refusal.nextStep() + "|" + refusal.getMessage());
            System.exit(3);
        }
    }
}
"""


def present_state(value_hash: str = HASH_A, size: int = 1) -> dict:
    return {"present": True, "size": size, "sha256": value_hash}


def absent_state() -> dict:
    return {"present": False, "size": 0, "sha256": ""}


def state_reference(path: str = "", value_hash: str = "", role: str | None = None) -> dict:
    result = {
        "present": bool(path),
        "relativePath": path,
        "sha256": value_hash if path else "",
    }
    if role is not None:
        result["role"] = role if path else ""
    return result


def file_record(role: str, path: str, value_hash: str = HASH_A, size: int = 1) -> dict:
    return {
        "role": role,
        "relativePath": path,
        "present": True,
        "size": size,
        "sha256": value_hash,
    }


def operations(**overrides: bool) -> dict:
    result = {
        "createProject": True,
        "edit": True,
        "save": True,
        "export": True,
        "import": True,
        "undo": False,
    }
    result.update(overrides)
    return result


def no_operations() -> dict:
    return operations(
        createProject=False,
        edit=False,
        save=False,
        export=False,
        **{"import": False, "undo": False},
    )


def issue(code: str, severity: str, path: str, observed: str) -> dict:
    return {
        "code": code,
        "severity": severity,
        "operation": "discover-target",
        "projectId": "",
        "adapterId": "example-packed-v1",
        "relativePath": path,
        "provenance": "synthetic fixture",
        "recordIndex": 0,
        "recordKey": "fixture-record-0",
        "expected": "A compatible, strictly parseable server layout.",
        "observed": observed,
        "mutationOccurred": False,
        "nextStep": "Correct the server layout or install a supported adapter.",
    }


def capability() -> dict:
    return {
        "schemaVersion": 1,
        "manifestType": "world-builder-target-capability",
        "adapterId": "example-packed-v1",
        "capabilityId": "example-capability-v1",
        "server": {"buildId": "server-build-v1", "loaderId": "layered-loader-v1"},
        "client": {
            "buildId": "client-build-v1",
            "protocolId": "client-protocol-v1",
            "loaderId": "layered-loader-v1",
        },
        "definitions": {"catalogId": "example-catalog-v1", "catalogSha256": HASH_D},
        "map": {
            "formatId": "legacy-packed-orsc-v1",
            "packageSchemaId": "layered-world-package-v1",
            "encodingVersions": [1],
        },
        "discovery": {
            "configurationRoles": ["primary"],
            "sourceRepresentations": ["layered", "packed"],
            "sourceRoles": ["client-terrain", "server-terrain"],
        },
        "authoring": {
            "editExistingLevels": True,
            "createLevels": True,
            "placementFamilies": ["boundary", "ground-item", "npc", "scenery"],
        },
        "install": {
            "enabled": True,
            "serverRoles": ["layered-package", "loader-config"],
            "clientRoles": ["layered-package"],
            "configurationRoles": ["primary"],
            "mutationProfileId": "layered-install-v1",
            "offlineEvidence": ["pid-file", "port-bind"],
        },
    }


def discovery_base(status: str, representation: str) -> dict:
    return {
        "schemaVersion": 2,
        "manifestType": "world-builder-discovery-report",
        "toolVersion": "2.0.0-alpha.2",
        "status": status,
        "targetRootDisplay": "/display/server",
        "adaptersConsidered": ["example-packed-v1", "generic-layered-v1"],
        "descriptor": state_reference("server/world-builder-capabilities.json", HASH_A),
        "configurationCandidates": [
            {"role": "primary", "relativePath": "server/world.conf", "sha256": HASH_B}
        ],
        "selectedConfiguration": state_reference(
            "server/world.conf", HASH_B, role="primary"
        ),
        "representation": representation,
        "capability": {
            "resolved": True,
            "adapterId": "example-packed-v1",
            "capabilityId": "example-capability-v1",
            "evidenceRelativePath": "server/world-builder-capabilities.json",
            "evidenceSha256": HASH_A,
        },
        "files": [],
        "checks": [
            {
                "checkId": "client-server-map-agreement",
                "status": "passed",
                "expected": "The client and server select identical map bytes.",
                "observed": "Both selected the same fixture hash.",
            },
            {
                "checkId": "definition-agreement",
                "status": "passed",
                "expected": "Definitions agree.",
                "observed": "Catalog identities match.",
            },
        ],
        "operations": operations(
            edit=False, save=False, export=False, **{"import": False, "undo": False}
        ),
        "issues": [],
        "discoveryFingerprintSha256": HASH_C,
    }


def packed_discovery() -> dict:
    document = discovery_base("compatible", "packed")
    document["files"] = [
        file_record("client-terrain", "Client_Base/Cache/video/Active_Landscape.orsc"),
        file_record("server-terrain", "server/data/Active_Landscape.orsc"),
    ]
    return document


def layered_discovery() -> dict:
    document = discovery_base("compatible", "layered")
    document["capability"]["adapterId"] = "generic-layered-v1"
    document["files"] = [
        file_record("package-manifest", "server/maps/active/manifest.json")
    ]
    return document


def standalone_discovery() -> dict:
    document = discovery_base("standalone", "none")
    document.update(
        {
            "targetRootDisplay": "/display/ordinary-parent",
            "descriptor": state_reference(),
            "configurationCandidates": [],
            "selectedConfiguration": state_reference(role=""),
            "capability": {
                "resolved": False,
                "adapterId": "",
                "capabilityId": "",
                "evidenceRelativePath": "",
                "evidenceSha256": "",
            },
            "files": [],
            "checks": [],
            "operations": operations(
                edit=False,
                save=False,
                export=False,
                **{"import": False, "undo": False},
            ),
            "issues": [],
        }
    )
    return document


def blocked_discovery() -> dict:
    document = discovery_base("blocked", "unknown")
    document["selectedConfiguration"] = state_reference(role="")
    document["capability"] = {
        "resolved": False,
        "adapterId": "",
        "capabilityId": "",
        "evidenceRelativePath": "server/world-builder-capabilities.json",
        "evidenceSha256": HASH_A,
    }
    document["operations"] = operations(
        **{
            "createProject": False,
            "edit": False,
            "save": False,
            "export": False,
            "import": False,
            "undo": False,
        }
    )
    document["checks"][0]["status"] = "failed"
    document["issues"] = [
        issue(
            "MALFORMED_SERVER",
            "blocker",
            "server/world.conf",
            "The configuration is malformed.",
        )
    ]
    return document


def project_paths(converted: bool = True) -> dict:
    return {
        "sourceSnapshotRelativePath": "source/snapshot-manifest.json",
        "layeredBaselineRelativePath": "source/layered-baseline/package",
        "workingRuntimeRelativePath": "working/runtime",
        "workingPackageRelativePath": "working/layered-world/package",
        "conversionPlanRelativePath": "source/conversion/plan.json" if converted else "",
        "conversionReportRelativePath": "source/conversion/report.json" if converted else "",
        "exportsRelativePath": "exports",
        "backupsRelativePath": "backups",
        "receiptsRelativePath": "receipts",
        "diagnosticsRelativePath": "diagnostics",
        "logsRelativePath": "logs",
        "runRelativePath": "run",
    }


def packed_project() -> dict:
    return {
        "schemaVersion": 2,
        "manifestType": "world-builder-project",
        "projectId": PROJECT_ID,
        "displayName": "Converted server world",
        "origin": "target-packed",
        "state": "ready-attached",
        "creation": {"toolVersion": "2.0.0-alpha.2", "runtimeVersion": "runtime-v1"},
        "target": {
            "targetBacked": True,
            "locatorDisplay": "/display/server",
            "adapterId": "example-packed-v1",
            "capabilityId": "example-capability-v1",
            "selectedConfigurationRelativePath": "server/world.conf",
            "selectedConfigurationSha256": HASH_B,
            "targetFingerprintSha256": HASH_A,
            "importProfileId": "layered-install-v1",
        },
        "standalone": {"generatorId": "", "catalogId": "", "runtimeId": ""},
        "paths": project_paths(),
        "fingerprints": {
            "sourceSha256": HASH_A,
            "layeredBaselineSha256": HASH_B,
            "definitionsSha256": HASH_C,
            "runtimeSha256": HASH_D,
            "conversionSha256": HASH_A,
            "workingSha256": HASH_B,
        },
        "operations": operations(createProject=False),
        "projectFingerprintSha256": HASH_C,
    }


def standalone_project() -> dict:
    document = packed_project()
    document.update(
        {
            "displayName": "Empty world",
            "origin": "standalone-empty",
            "state": "ready-standalone",
            "target": {
                "targetBacked": False,
                "locatorDisplay": "",
                "adapterId": "",
                "capabilityId": "",
                "selectedConfigurationRelativePath": "",
                "selectedConfigurationSha256": "",
                "targetFingerprintSha256": "",
                "importProfileId": "",
            },
            "standalone": {
                "generatorId": "empty-world-v1",
                "catalogId": "default-catalog-v1",
                "runtimeId": "runtime-v1",
            },
            "paths": project_paths(converted=False),
            "operations": operations(
                **{"createProject": False, "import": False, "undo": False}
            ),
        }
    )
    document["fingerprints"]["conversionSha256"] = ""
    return document


def project_registry() -> dict:
    return {
        "schemaVersion": 1,
        "manifestType": "world-builder-project-registry",
        "projects": [
            {
                "projectId": PROJECT_ID,
                "manifestRelativePath": f"projects/{PROJECT_ID}/project.json",
                "manifestSha256": HASH_A,
                "displayName": "Converted server world",
                "origin": "target-packed",
                "state": "ready-attached",
            }
        ],
        "registryFingerprintSha256": HASH_B,
    }


def active_project() -> dict:
    return {
        "schemaVersion": 1,
        "manifestType": "world-builder-active-project",
        "projectId": PROJECT_ID,
        "manifestRelativePath": f"projects/{PROJECT_ID}/project.json",
        "manifestSha256": HASH_A,
    }


def source_snapshot(standalone: bool = False) -> dict:
    origin = "standalone-empty" if standalone else "target-packed"
    descriptor = (
        "source/original/empty-world-v1.json"
        if standalone
        else "source/original/target-capability.json"
    )
    original = [file_record("empty-origin", descriptor)] if standalone else [
        file_record("server-terrain", "source/original/server/data/map.orsc"),
        file_record("configuration", "source/original/server/world.conf", HASH_B),
        file_record("target-capability", descriptor, HASH_A),
    ]
    return {
        "schemaVersion": 2,
        "manifestType": "world-builder-source-snapshot",
        "projectId": PROJECT_ID,
        "origin": origin,
        "adapterId": "" if standalone else "example-packed-v1",
        "capabilityId": "" if standalone else "example-capability-v1",
        "selectedConfiguration": (
            state_reference(role="")
            if standalone
            else state_reference("source/original/server/world.conf", HASH_B, role="primary")
        ),
        "discoveryReport": state_reference("discovery/report.json", HASH_C),
        "originDescriptor": state_reference(descriptor, HASH_A),
        "originalFiles": original,
        "definitionRuntimeFiles": [
            file_record("definitions", "source/definitions/catalog.bin", HASH_C)
        ],
        "conversionEvidenceFiles": [] if standalone else [
            file_record("conversion-plan", "source/conversion/plan.json", HASH_A),
            file_record("conversion-report", "source/conversion/report.json", HASH_B),
        ],
        "layeredBaselineFiles": [
            file_record("package-manifest", "source/layered-baseline/package/manifest.json", HASH_D)
        ],
        "sourceFingerprintSha256": HASH_D,
    }


def conversion_plan() -> dict:
    return {
        "schemaVersion": 1,
        "manifestType": "world-builder-conversion-plan",
        "toolVersion": "converter-v1",
        "adapterId": "example-packed-v1",
        "conversionProfileId": "exact-packed-to-layered-v1",
        "sourceFingerprintSha256": HASH_A,
        "definitionFingerprintSha256": HASH_B,
        "coordinateMappingId": "legacy-signed-v1",
        "placementCompositionProfileId": "static-composition-v1",
        "outputPackageSchemaId": "layered-world-package-v1",
        "outputEncodingVersion": 1,
        "inputs": [file_record("server-terrain", "source/original/server/data/map.orsc")],
        "placementSourceOrder": [],
        "planFingerprintSha256": HASH_C,
    }


def conversion_report() -> dict:
    return {
        "schemaVersion": 1,
        "manifestType": "world-builder-conversion-report",
        "planSha256": HASH_A,
        "outputFingerprintSha256": HASH_B,
        "terrain": {
            "entriesRead": 1,
            "entriesWritten": 1,
            "reverseMatched": 1,
            "reverseMismatches": 0,
        },
        "placements": [
            {
                "family": family,
                "level": 0,
                "sourceRole": "base-placements",
                "definitionId": 0,
                "count": 0,
            }
            for family in ("boundary", "ground-item", "npc", "scenery")
        ],
        "decisions": [],
        "validation": {
            "unknownCount": 0,
            "lossCount": 0,
            "approximationCount": 0,
            "repairCount": 0,
            "parityDeltaCount": 0,
        },
        "issues": [],
        "blocked": False,
        "reportFingerprintSha256": HASH_C,
    }


def export_manifest(standalone: bool = False) -> dict:
    return {
        "schemaVersion": 2,
        "manifestType": "world-builder-adaptive-export",
        "toolVersion": "2.0.0-alpha.2",
        "projectId": PROJECT_ID,
        "origin": "standalone-empty" if standalone else "target-packed",
        "adapterId": "" if standalone else "example-packed-v1",
        "capabilityId": "" if standalone else "example-capability-v1",
        "installProfileId": "" if standalone else "layered-install-v1",
        "lineage": {
            "sourceSha256": HASH_A,
            "layeredBaselineSha256": HASH_B,
            "conversionSha256": "" if standalone else HASH_C,
            "definitionsRuntimeSha256": HASH_D,
            "workingSha256": HASH_A,
        },
        "packageManifestSha256": HASH_B,
        "packageFingerprintSha256": HASH_C,
        "files": [file_record("package-manifest", "package/manifest.json", HASH_B)],
        "validationReports": [{"role": "package-validation", "sha256": HASH_D}],
        "exportFingerprintSha256": HASH_A,
    }


def mutation_plan() -> dict:
    return {
        "schemaVersion": 1,
        "manifestType": "world-builder-target-mutation-plan",
        "transactionId": TRANSACTION_ID,
        "projectId": PROJECT_ID,
        "exportFingerprintSha256": HASH_A,
        "adapterId": "example-packed-v1",
        "capabilityId": "example-capability-v1",
        "mutationProfileId": "layered-install-v1",
        "targetLineageSha256": HASH_B,
        "selectedConfiguration": state_reference(
            "server/world.conf", HASH_C, role="primary"
        ),
        "requirements": {
            "loaderId": "layered-loader-v1",
            "protocolId": "client-protocol-v1",
            "definitionCatalogId": "example-catalog-v1",
            "clientBuildId": "client-build-v1",
            "offlineEvidence": ["pid-file", "port-bind"],
            "requiredFreeSpaceBytes": 1024,
        },
        "actions": [
            {
                "sequence": 0,
                "role": "package-manifest",
                "destinationRelativePath": "server/maps/packages/example/manifest.json",
                "before": absent_state(),
                "after": present_state(HASH_A, 1),
                "contentRelativePath": "package/manifest.json",
                "backupRelativePath": "",
                "activation": False,
            },
            {
                "sequence": 1,
                "role": "primary-configuration",
                "destinationRelativePath": "server/world.conf",
                "before": present_state(HASH_B, 1),
                "after": present_state(HASH_C, 1),
                "contentRelativePath": "package/config/server-world.conf",
                "backupRelativePath": f"backups/{TRANSACTION_ID}/before/server/world.conf",
                "activation": True,
            }
        ],
        "createdDirectories": [],
        "configurationChanges": [
            {
                "sequence": 0,
                "configurationRelativePath": "server/world.conf",
                "key": "layered-package",
                "beforePresent": False,
                "beforeValue": "",
                "afterPresent": True,
                "afterValue": "maps/packages/example",
            }
        ],
        "backupRootRelativePath": f"backups/{TRANSACTION_ID}",
        "receiptRelativePath": f"receipts/{TRANSACTION_ID}.json",
        "postWriteVerifications": [
            {
                "verificationId": "configuration-installed-hash",
                "relativePath": "server/world.conf",
                "expected": HASH_C,
            },
            {
                "verificationId": "package-installed-hash",
                "relativePath": "server/maps/packages/example/manifest.json",
                "expected": HASH_A,
            },
        ],
        "rollbackVerifications": [
            {
                "verificationId": "configuration-restored-hash",
                "relativePath": "server/world.conf",
                "expected": HASH_B,
            },
            {
                "verificationId": "package-restored-absence",
                "relativePath": "server/maps/packages/example/manifest.json",
                "expected": "absent",
            },
        ],
        "planFingerprintSha256": HASH_D,
    }


def import_receipt() -> dict:
    return {
        "schemaVersion": 3,
        "manifestType": "world-builder-adaptive-import-receipt",
        "transactionId": TRANSACTION_ID,
        "transactionType": "import",
        "status": "successful",
        "createdAtUtc": "2026-08-02T00:00:00Z",
        "projectId": PROJECT_ID,
        "exportFingerprintSha256": HASH_A,
        "mutationPlanSha256": HASH_B,
        "adapterId": "example-packed-v1",
        "capabilityId": "example-capability-v1",
        "targetLineageSha256": HASH_C,
        "selectedConfiguration": state_reference(
            "server/world.conf", HASH_D, role="primary"
        ),
        "mutationOccurred": True,
        "offlineEvidence": [
            {"kind": "pid-file", "observed": "absent", "verified": True},
            {"kind": "port-bind", "observed": "unbound", "verified": True},
        ],
        "files": [
            {
                "role": "package-manifest",
                "relativePath": "server/maps/packages/example/manifest.json",
                "before": absent_state(),
                "after": present_state(HASH_A),
                "backupRelativePath": "",
                "backupSha256": "",
                "afterVerified": True,
                "rollbackVerified": False,
            },
            {
                "role": "primary-configuration",
                "relativePath": "server/world.conf",
                "before": present_state(HASH_B),
                "after": present_state(HASH_C),
                "backupRelativePath": f"backups/{TRANSACTION_ID}/before/server/world.conf",
                "backupSha256": HASH_B,
                "afterVerified": True,
                "rollbackVerified": False,
            },
        ],
        "configurationChanges": [
            {
                "sequence": 0,
                "configurationRelativePath": "server/world.conf",
                "key": "layered-package",
                "beforePresent": False,
                "beforeValue": "",
                "afterPresent": True,
                "afterValue": "maps/packages/example",
                "afterVerified": True,
                "rollbackVerified": False,
            }
        ],
        "verificationResults": [
            {"verificationId": "installed-hash", "success": True, "observed": HASH_A}
        ],
        "revertsTransactionId": "",
        "recoveryTransactionId": "",
        "receiptFingerprintSha256": HASH_D,
    }


VALID_CONTRACTS = {
    "target-capability": capability,
    "discovery-report": packed_discovery,
    "project-manifest": packed_project,
    "project-registry": project_registry,
    "active-project": active_project,
    "source-snapshot": source_snapshot,
    "conversion-plan": conversion_plan,
    "conversion-report": conversion_report,
    "adaptive-export": export_manifest,
    "mutation-plan": mutation_plan,
    "adaptive-receipt": import_receipt,
}

SCHEMA_CONTRACTS = {
    "target-capability-v1.schema.json": (1, "world-builder-target-capability"),
    "discovery-report-v2.schema.json": (2, "world-builder-discovery-report"),
    "project-manifest-v2.schema.json": (2, "world-builder-project"),
    "project-registry-v1.schema.json": (1, "world-builder-project-registry"),
    "active-project-v1.schema.json": (1, "world-builder-active-project"),
    "source-snapshot-v2.schema.json": (2, "world-builder-source-snapshot"),
    "conversion-plan-v1.schema.json": (1, "world-builder-conversion-plan"),
    "conversion-report-v1.schema.json": (1, "world-builder-conversion-report"),
    "export-manifest-v2.schema.json": (2, "world-builder-adaptive-export"),
    "target-mutation-plan-v1.schema.json": (1, "world-builder-target-mutation-plan"),
    "import-receipt-v3.schema.json": (3, "world-builder-adaptive-import-receipt"),
}


class AdaptiveContractTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.build = tempfile.TemporaryDirectory(prefix="world-builder-contract-classes-")
        cls.classes = Path(cls.build.name) / "classes"
        cls.classes.mkdir()
        harness = Path(cls.build.name) / "AdaptiveContractHarness.java"
        harness.write_text(HARNESS, encoding="utf-8")
        sources = sorted(str(path) for path in SOURCE_ROOT.rglob("*.java"))
        subprocess.run(
            [
                "javac", "-source", "8", "-target", "8", "-d", str(cls.classes),
                *sources, str(harness),
            ],
            check=True,
            capture_output=True,
            text=True,
        )

    @classmethod
    def tearDownClass(cls) -> None:
        cls.build.cleanup()

    def run_harness(self, *arguments: str):
        return subprocess.run(
            [
                "java", "-cp", str(self.classes),
                "com.openrsc.worldbuilder.AdaptiveContractHarness", *arguments,
            ],
            capture_output=True,
            text=True,
        )

    def validate(self, kind: str, document: dict, raw: bytes | None = None):
        with tempfile.TemporaryDirectory(prefix="world-builder-contract-fixture-") as temp:
            root = Path(temp)
            path = root / "contract.json"
            if raw is None:
                path.write_text(
                    json.dumps(document, ensure_ascii=False, indent=2) + "\n",
                    encoding="utf-8",
                )
            else:
                path.write_bytes(raw)
            sentinel = root / "sentinel.bin"
            sentinel.write_bytes(b"must remain unchanged")
            before = {item.name: item.read_bytes() for item in root.iterdir()}
            result = self.run_harness("validate", kind, str(path))
            after = {item.name: item.read_bytes() for item in root.iterdir()}
            self.assertEqual(before, after, "contract validation changed fixture state")
            return result

    def assert_valid(self, kind: str, document: dict):
        result = self.validate(kind, document)
        self.assertEqual(0, result.returncode, result.stderr)
        fingerprint, canonical = result.stdout.split("\n", 1)
        expected = json.dumps(
            document, ensure_ascii=False, sort_keys=True, separators=(",", ":")
        )
        self.assertEqual(expected, canonical)
        self.assertEqual(hashlib.sha256(expected.encode()).hexdigest(), fingerprint)

    def assert_refused(self, kind: str, document: dict, code: str):
        result = self.validate(kind, document)
        self.assertEqual(3, result.returncode, result.stdout)
        self.assertTrue(result.stderr.startswith(code + "|"), result.stderr)
        self.assertIn("|false|", result.stderr)

    def test_all_contracts_validate_and_canonicalize(self):
        for kind, factory in VALID_CONTRACTS.items():
            with self.subTest(kind=kind):
                self.assert_valid(kind, factory())
        self.assert_valid("discovery-report", layered_discovery())
        self.assert_valid("discovery-report", standalone_discovery())
        self.assert_valid("discovery-report", blocked_discovery())
        self.assert_valid("project-manifest", standalone_project())
        self.assert_valid("source-snapshot", source_snapshot(standalone=True))
        self.assert_valid("adaptive-export", export_manifest(standalone=True))

    def test_synthetic_layout_fixtures_are_read_only(self):
        with tempfile.TemporaryDirectory(prefix="world-builder-layout-contracts-") as temp:
            root = Path(temp)
            fixtures = {
                "packed-server/server/world.conf": b"map=packed\n",
                "packed-server/server/data/map.orsc": b"packed-map",
                "layered-server/server/maps/active/manifest.json": b"{}\n",
                "malformed-server/server/world.conf": b"not=a=supported=config\n",
                "no-server/World Builder/.keep": b"",
            }
            for relative, data in fixtures.items():
                path = root / relative
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(data)

            contracts = {
                "packed.json": ("discovery-report", packed_discovery()),
                "layered.json": ("discovery-report", layered_discovery()),
                "malformed.json": ("discovery-report", blocked_discovery()),
                "no-server.json": ("discovery-report", standalone_discovery()),
            }
            for name, (_, document) in contracts.items():
                (root / name).write_text(json.dumps(document), encoding="utf-8")

            before = {
                path.relative_to(root).as_posix(): path.read_bytes()
                for path in root.rglob("*") if path.is_file()
            }
            for name, (kind, _) in contracts.items():
                result = self.run_harness("validate", kind, str(root / name))
                self.assertEqual(0, result.returncode, result.stderr)
            after = {
                path.relative_to(root).as_posix(): path.read_bytes()
                for path in root.rglob("*") if path.is_file()
            }
            self.assertEqual(before, after)

    def test_schemas_are_strict_versioned_and_v1_remains_frozen(self):
        expected_refs = set(SCHEMA_CONTRACTS) | {
            "adaptive-contract-definitions-v1.schema.json"
        }

        def inspect(value):
            if isinstance(value, dict):
                if value.get("type") == "object" and "properties" in value:
                    self.assertFalse(value.get("additionalProperties", True))
                for key, child in value.items():
                    if key == "$ref" and not child.startswith("#"):
                        referenced = child.split("#", 1)[0]
                        self.assertIn(referenced, expected_refs)
                        self.assertTrue((SCHEMA_ROOT / referenced).is_file())
                    inspect(child)
            elif isinstance(value, list):
                for child in value:
                    inspect(child)

        for name, (version, manifest_type) in SCHEMA_CONTRACTS.items():
            with self.subTest(schema=name):
                schema = json.loads((SCHEMA_ROOT / name).read_text(encoding="utf-8"))
                self.assertFalse(schema["additionalProperties"])
                self.assertEqual(version, schema["properties"]["schemaVersion"]["const"])
                self.assertEqual(
                    manifest_type, schema["properties"]["manifestType"]["const"]
                )
                self.assertEqual(set(schema["required"]), set(schema["properties"]))
                self.assertNotIn("spoiled-milk", schema["$id"])
                inspect(schema)

        for name in (
            "project-manifest-v1.schema.json",
            "export-manifest-v1.schema.json",
            "import-receipt-v1.schema.json",
        ):
            legacy = json.loads((SCHEMA_ROOT / name).read_text(encoding="utf-8"))
            self.assertEqual(1, legacy["properties"]["schemaVersion"]["const"])

    @unittest.skipUnless(jsonschema is not None, "optional jsonschema module unavailable")
    def test_json_schemas_are_valid_and_accept_production_valid_vectors(self):
        common = json.loads(
            (SCHEMA_ROOT / "adaptive-contract-definitions-v1.schema.json").read_text(
                encoding="utf-8"
            )
        )
        store = {common["$id"]: common}
        factories_by_type = {
            factory()["manifestType"]: factory for factory in VALID_CONTRACTS.values()
        }
        for name, (_, manifest_type) in SCHEMA_CONTRACTS.items():
            with self.subTest(schema=name):
                schema = json.loads((SCHEMA_ROOT / name).read_text(encoding="utf-8"))
                jsonschema.Draft202012Validator.check_schema(schema)
                with warnings.catch_warnings():
                    warnings.simplefilter("ignore", DeprecationWarning)
                    resolver = jsonschema.RefResolver.from_schema(schema, store=store)
                validator = jsonschema.Draft202012Validator(
                    schema, resolver=resolver
                )
                errors = list(validator.iter_errors(factories_by_type[manifest_type]()))
                self.assertEqual([], errors, [error.message for error in errors])

        alternates = (
            ("discovery-report-v2.schema.json", layered_discovery()),
            ("discovery-report-v2.schema.json", standalone_discovery()),
            ("discovery-report-v2.schema.json", blocked_discovery()),
            ("project-manifest-v2.schema.json", standalone_project()),
            ("source-snapshot-v2.schema.json", source_snapshot(standalone=True)),
            ("export-manifest-v2.schema.json", export_manifest(standalone=True)),
        )
        for name, document in alternates:
            schema = json.loads((SCHEMA_ROOT / name).read_text(encoding="utf-8"))
            with warnings.catch_warnings():
                warnings.simplefilter("ignore", DeprecationWarning)
                resolver = jsonschema.RefResolver.from_schema(schema, store=store)
            errors = list(
                jsonschema.Draft202012Validator(
                    schema, resolver=resolver
                ).iter_errors(document)
            )
            self.assertEqual([], errors, [error.message for error in errors])

    def test_every_contract_rejects_unknown_keys_versions_and_types(self):
        for kind, factory in VALID_CONTRACTS.items():
            with self.subTest(kind=kind, case="unknown-key"):
                document = factory()
                document["unexpected"] = True
                self.assert_refused(kind, document, "CONTRACT_KEYS_INVALID")
            with self.subTest(kind=kind, case="version"):
                document = factory()
                document["schemaVersion"] = 999
                self.assert_refused(kind, document, "UNSUPPORTED_CONTRACT_VERSION")
            with self.subTest(kind=kind, case="identity"):
                document = factory()
                document["manifestType"] = "world-builder-wrong-contract"
                self.assert_refused(kind, document, "CONTRACT_IDENTITY_INVALID")

    def test_canonical_fingerprint_ignores_json_format_and_key_order(self):
        document = capability()
        with tempfile.TemporaryDirectory(prefix="world-builder-canonical-") as temp:
            root = Path(temp)
            compact = root / "compact.json"
            reversed_keys = root / "reversed.json"
            compact.write_text(json.dumps(document, separators=(",", ":")))
            reversed_keys.write_text(json.dumps(dict(reversed(list(document.items()))), indent=4))
            outputs = [
                self.run_harness("validate", "target-capability", str(path)).stdout
                for path in (compact, reversed_keys)
            ]
            self.assertEqual(outputs[0], outputs[1])

    def test_malformed_json_links_and_resource_limits_fail_closed(self):
        for raw in (
            b'{"schemaVersion":1,"schemaVersion":1}',
            b'{"schemaVersion":1.5}',
            b'{"value":"\\ud800"}',
            b'{not-json}',
        ):
            with self.subTest(raw=raw):
                result = self.validate("target-capability", {}, raw=raw)
                self.assertEqual(3, result.returncode)
                self.assertTrue(result.stderr.startswith("MALFORMED_JSON|"))

        oversized = b"{" + b" " * (16 * 1024 * 1024) + b"}"
        result = self.validate("target-capability", {}, raw=oversized)
        self.assertTrue(result.stderr.startswith("CONTRACT_LIMIT_EXCEEDED|"))

        nested = ("[" * 34 + "0" + "]" * 34).encode()
        result = self.validate("target-capability", {}, raw=nested)
        self.assertTrue(result.stderr.startswith("CONTRACT_LIMIT_EXCEEDED|"))

        too_many = source_snapshot()
        too_many["originalFiles"] = [
            file_record("file", f"source/original/files/{index:05d}")
            for index in range(8193)
        ]
        self.assert_refused("source-snapshot", too_many, "INVENTORY_LIMIT_EXCEEDED")

        grouped_total = source_snapshot()
        grouped_total["originalFiles"] = [
            file_record(
                f"original-{index:02d}",
                f"source/original/files/{index:02d}.bin",
                size=4 * 1024 * 1024 * 1024,
            )
            for index in range(8)
        ]
        grouped_total["definitionRuntimeFiles"] = [
            file_record(
                f"definition-{index:02d}",
                f"source/definitions/files/{index:02d}.bin",
                size=4 * 1024 * 1024 * 1024,
            )
            for index in range(8)
        ]
        self.assert_refused(
            "source-snapshot", grouped_total, "CONTRACT_LIMIT_EXCEEDED"
        )

        with tempfile.TemporaryDirectory(prefix="world-builder-contract-link-") as temp:
            root = Path(temp)
            target = root / "target.json"
            target.write_text(json.dumps(capability()))
            link = root / "link.json"
            os.symlink(target.name, link)
            result = self.run_harness("validate", "target-capability", str(link))
            self.assertEqual(3, result.returncode)
            self.assertTrue(result.stderr.startswith("UNSAFE_PATH|"), result.stderr)

    def test_portable_paths_cover_linux_windows_unicode_and_collisions(self):
        for path in ("package/manifest.json", "maps/My World/map.json", "maps/café.json"):
            result = self.run_harness("path", path)
            self.assertEqual(0, result.returncode, result.stderr)

        for path in (
            "/absolute", "../escape", "maps/../escape", "maps\\windows",
            "C:/absolute", "maps//file", "maps/./file", "maps/CON", "maps/con.txt",
            "maps/COM1.json", "maps/file.", "maps/file ", "maps/<transaction-id>",
            "maps/file?.json", "maps/cafe\u0301.json",
        ):
            with self.subTest(path=path):
                result = self.run_harness("path", path)
                self.assertEqual(3, result.returncode)
                self.assertTrue(result.stderr.startswith("UNSAFE_PATH|"))

        collision = source_snapshot()
        collision["originalFiles"] = [
            file_record("one", "source/original/Map.json"),
            file_record("two", "source/original/map.json"),
        ]
        self.assert_refused("source-snapshot", collision, "INVENTORY_DUPLICATE")

        unsorted = source_snapshot()
        unsorted["originalFiles"] = [
            file_record("two", "source/original/z.json"),
            file_record("one", "source/original/a.json"),
        ]
        self.assert_refused("source-snapshot", unsorted, "CONTRACT_VALUE_INVALID")

    def test_capability_discovery_project_and_source_compatibility(self):
        disabled = capability()
        disabled["install"]["enabled"] = False
        self.assert_refused("target-capability", disabled, "CONTRACT_VALUE_INVALID")

        unsafe_candidate = capability()
        unsafe_candidate["discovery"]["configurationRoles"] = ["primary"]
        unsafe_candidate["install"]["configurationRoles"] = ["primary"]
        unsafe_candidate["discovery"]["sourceRoles"] = ["server-terrain"]
        self.assert_valid("target-capability", unsafe_candidate)

        missing_adapter = packed_discovery()
        missing_adapter["capability"]["resolved"] = False
        self.assert_refused("discovery-report", missing_adapter, "CONTRACT_VALUE_INVALID")

        standalone_import = standalone_discovery()
        standalone_import["operations"]["import"] = True
        self.assert_refused("discovery-report", standalone_import, "CONTRACT_VALUE_INVALID")

        no_blocker = blocked_discovery()
        no_blocker["issues"] = []
        self.assert_refused("discovery-report", no_blocker, "CONTRACT_VALUE_INVALID")

        bad_provenance = blocked_discovery()
        bad_provenance["issues"][0]["recordKey"] = ""
        self.assert_refused(
            "discovery-report", bad_provenance, "CONTRACT_VALUE_INVALID"
        )

        unknown_code = blocked_discovery()
        unknown_code["issues"][0]["code"] = "UNREVIEWED_FAILURE"
        self.assert_refused("discovery-report", unknown_code, "CONTRACT_VALUE_INVALID")

        standalone_target = standalone_project()
        standalone_target["target"]["adapterId"] = "example-packed-v1"
        self.assert_refused("project-manifest", standalone_target, "CONTRACT_VALUE_INVALID")

        missing_conversion = packed_project()
        missing_conversion["fingerprints"]["conversionSha256"] = ""
        self.assert_refused("project-manifest", missing_conversion, "CONTRACT_VALUE_INVALID")

        bad_registry = project_registry()
        bad_registry["projects"][0]["manifestRelativePath"] = "projects/not-the-id/project.json"
        self.assert_refused("project-registry", bad_registry, "CONTRACT_VALUE_INVALID")

        empty_active = active_project()
        empty_active.update({"projectId": "", "manifestRelativePath": "", "manifestSha256": ""})
        self.assert_valid("active-project", empty_active)

        bad_active = active_project()
        bad_active["manifestRelativePath"] = f"projects/{PROJECT_ID}/other.json"
        self.assert_refused("active-project", bad_active, "CONTRACT_VALUE_INVALID")

        contaminated = source_snapshot(standalone=True)
        contaminated["adapterId"] = "example-packed-v1"
        self.assert_refused("source-snapshot", contaminated, "CONTRACT_VALUE_INVALID")

    def test_project_state_matrix_and_registry_origin_state_fail_closed(self):
        attached = packed_project()
        attached["operations"]["undo"] = True
        self.assert_valid("project-manifest", attached)

        detached = packed_project()
        detached["state"] = "ready-detached"
        detached["operations"] = operations(
            createProject=False, **{"import": False, "undo": False}
        )
        self.assert_valid("project-manifest", detached)

        for state in ("staging", "source-corrupt", "recovery-required"):
            safe = packed_project()
            safe["state"] = state
            safe["operations"] = no_operations()
            self.assert_valid("project-manifest", safe)
            for operation_name in safe["operations"]:
                unsafe = copy.deepcopy(safe)
                unsafe["operations"][operation_name] = True
                with self.subTest(state=state, operation=operation_name):
                    self.assert_refused(
                        "project-manifest", unsafe, "CONTRACT_VALUE_INVALID"
                    )

        missing_edit = packed_project()
        missing_edit["operations"]["edit"] = False
        self.assert_refused(
            "project-manifest", missing_edit, "CONTRACT_VALUE_INVALID"
        )

        detached_import = copy.deepcopy(detached)
        detached_import["operations"]["import"] = True
        self.assert_refused(
            "project-manifest", detached_import, "CONTRACT_VALUE_INVALID"
        )

        standalone_attached = project_registry()
        standalone_attached["projects"][0].update(
            {"origin": "standalone-empty", "state": "ready-attached"}
        )
        self.assert_refused(
            "project-registry", standalone_attached, "CONTRACT_VALUE_INVALID"
        )

        target_standalone = project_registry()
        target_standalone["projects"][0]["state"] = "ready-standalone"
        self.assert_refused(
            "project-registry", target_standalone, "CONTRACT_VALUE_INVALID"
        )

    def test_discovery_evidence_checks_and_read_only_operations_are_coherent(self):
        for field, wrong in (
            ("role", "secondary"),
            ("relativePath", "server/other.conf"),
            ("sha256", HASH_D),
        ):
            missing_candidate = packed_discovery()
            missing_candidate["selectedConfiguration"][field] = wrong
            with self.subTest(selected_field=field):
                self.assert_refused(
                    "discovery-report", missing_candidate, "CONTRACT_VALUE_INVALID"
                )

        unconsidered = packed_discovery()
        unconsidered["adaptersConsidered"] = ["generic-layered-v1"]
        self.assert_refused(
            "discovery-report", unconsidered, "CONTRACT_VALUE_INVALID"
        )

        absent_only = packed_discovery()
        absent_only["files"] = [
            {
                "role": "server-terrain",
                "relativePath": "server/data/Active_Landscape.orsc",
                **absent_state(),
            }
        ]
        self.assert_refused(
            "discovery-report", absent_only, "CONTRACT_VALUE_INVALID"
        )

        failed_check = packed_discovery()
        failed_check["checks"][0]["status"] = "failed"
        self.assert_refused(
            "discovery-report", failed_check, "CONTRACT_VALUE_INVALID"
        )

        mutation_claim = blocked_discovery()
        mutation_claim["issues"][0]["mutationOccurred"] = True
        self.assert_refused(
            "discovery-report", mutation_claim, "CONTRACT_VALUE_INVALID"
        )

        for factory in (packed_discovery, standalone_discovery):
            premature = factory()
            premature["operations"]["edit"] = True
            with self.subTest(factory=factory.__name__):
                self.assert_refused(
                    "discovery-report", premature, "CONTRACT_VALUE_INVALID"
                )

    def test_source_snapshot_references_exact_original_and_conversion_evidence(self):
        selected_hash = source_snapshot()
        selected_hash["selectedConfiguration"]["sha256"] = HASH_D
        self.assert_refused(
            "source-snapshot", selected_hash, "CONTRACT_VALUE_INVALID"
        )

        selected_path = source_snapshot()
        selected_path["selectedConfiguration"]["relativePath"] = (
            "source/original/server/other.conf"
        )
        self.assert_refused(
            "source-snapshot", selected_path, "CONTRACT_VALUE_INVALID"
        )

        descriptor_hash = source_snapshot()
        descriptor_hash["originDescriptor"]["sha256"] = HASH_D
        self.assert_refused(
            "source-snapshot", descriptor_hash, "CONTRACT_VALUE_INVALID"
        )

        arbitrary_conversion = source_snapshot()
        arbitrary_conversion["conversionEvidenceFiles"] = [
            file_record("other-a", "source/conversion/a.json"),
            file_record("other-b", "source/conversion/b.json", HASH_B),
        ]
        self.assert_refused(
            "source-snapshot", arbitrary_conversion, "CONTRACT_VALUE_INVALID"
        )

        swapped_role = source_snapshot()
        swapped_role["conversionEvidenceFiles"][0]["role"] = "conversion-report"
        self.assert_refused(
            "source-snapshot", swapped_role, "CONTRACT_VALUE_INVALID"
        )

    def test_conversion_numbers_and_blocker_semantics_are_bounded(self):
        huge_version = conversion_plan()
        huge_version["outputEncodingVersion"] = 2**63 - 1
        self.assert_refused(
            "conversion-plan", huge_version, "CONTRACT_VALUE_INVALID"
        )

        overflow_blockers = conversion_report()
        overflow_blockers["validation"]["unknownCount"] = 2**63 - 1
        overflow_blockers["validation"]["lossCount"] = 2**63 - 1
        self.assert_refused(
            "conversion-report", overflow_blockers, "CONTRACT_LIMIT_EXCEEDED"
        )

        huge_terrain = conversion_report()
        huge_terrain["terrain"].update(
            {
                "entriesRead": 2**63 - 1,
                "entriesWritten": 2**63 - 1,
                "reverseMatched": 2**63 - 1,
            }
        )
        self.assert_refused(
            "conversion-report", huge_terrain, "CONTRACT_LIMIT_EXCEEDED"
        )

        blocked_decision = conversion_report()
        blocked_decision["decisions"] = [
            {
                "kind": "collision",
                "sourceRole": "base-placements",
                "provenance": "fixture record 1",
                "placementId": "placement-1",
                "outcome": "blocked",
            }
        ]
        self.assert_refused(
            "conversion-report", blocked_decision, "CONTRACT_VALUE_INVALID"
        )

        downgraded = conversion_report()
        downgraded["issues"] = [
            issue(
                "CONVERSION_BLOCKED",
                "warning",
                "source/original/server/data/map.orsc",
                "The record is not representable.",
            )
        ]
        self.assert_refused(
            "conversion-report", downgraded, "CONTRACT_VALUE_INVALID"
        )

    def test_export_binds_manifest_and_package_validation_evidence(self):
        wrong_hash = export_manifest()
        wrong_hash["packageManifestSha256"] = HASH_A
        self.assert_refused(
            "adaptive-export", wrong_hash, "CONTRACT_VALUE_INVALID"
        )

        missing_manifest = export_manifest()
        missing_manifest["files"] = [
            file_record("unrelated", "package/unrelated.bin", HASH_B)
        ]
        self.assert_refused(
            "adaptive-export", missing_manifest, "CONTRACT_VALUE_INVALID"
        )

        no_validation = export_manifest()
        no_validation["validationReports"] = []
        self.assert_refused(
            "adaptive-export", no_validation, "CONTRACT_LIMIT_EXCEEDED"
        )

        unrelated_validation = export_manifest()
        unrelated_validation["validationReports"] = [
            {"role": "unrelated-check", "sha256": HASH_D}
        ]
        self.assert_refused(
            "adaptive-export", unrelated_validation, "CONTRACT_VALUE_INVALID"
        )

    def test_mutation_plan_actions_and_verification_sets_are_exact(self):
        no_op = mutation_plan()
        no_op["actions"][1]["after"] = copy.deepcopy(no_op["actions"][1]["before"])
        self.assert_refused("mutation-plan", no_op, "CONTRACT_VALUE_INVALID")

        hash_no_op = mutation_plan()
        hash_no_op["actions"][1]["after"]["sha256"] = (
            hash_no_op["actions"][1]["before"]["sha256"]
        )
        self.assert_refused(
            "mutation-plan", hash_no_op, "CONTRACT_VALUE_INVALID"
        )

        incomplete = mutation_plan()
        incomplete["postWriteVerifications"].pop()
        self.assert_refused(
            "mutation-plan", incomplete, "CONTRACT_VALUE_INVALID"
        )

        unrelated = mutation_plan()
        unrelated["postWriteVerifications"][0]["relativePath"] = "server/unrelated.conf"
        self.assert_refused(
            "mutation-plan", unrelated, "CONTRACT_VALUE_INVALID"
        )

        wrong_expected = mutation_plan()
        wrong_expected["rollbackVerifications"][0]["expected"] = HASH_D
        self.assert_refused(
            "mutation-plan", wrong_expected, "CONTRACT_VALUE_INVALID"
        )

        extra = mutation_plan()
        extra["rollbackVerifications"].append(
            {
                "verificationId": "unrelated-restored-hash",
                "relativePath": "server/unrelated.conf",
                "expected": HASH_A,
            }
        )
        self.assert_refused("mutation-plan", extra, "CONTRACT_VALUE_INVALID")

        config_not_activation = mutation_plan()
        config_not_activation["actions"][1]["activation"] = False
        self.assert_refused(
            "mutation-plan", config_not_activation, "CONTRACT_VALUE_INVALID"
        )

        created = mutation_plan()
        created["createdDirectories"] = [
            "server/maps",
            "server/maps/packages",
            "server/maps/packages/example",
        ]
        self.assertEqual(0, self.validate("mutation-plan", created).returncode)

        arbitrary = mutation_plan()
        arbitrary["createdDirectories"] = ["owner/empty"]
        self.assert_refused(
            "mutation-plan", arbitrary, "CONTRACT_VALUE_INVALID"
        )

        reordered = mutation_plan()
        reordered["createdDirectories"] = [
            "server/maps/packages",
            "server/maps",
        ]
        self.assert_refused(
            "mutation-plan", reordered, "CONTRACT_VALUE_INVALID"
        )

    def test_receipt_backup_offline_and_success_evidence_are_exact(self):
        no_op = import_receipt()
        no_op["files"][1]["after"] = copy.deepcopy(no_op["files"][1]["before"])
        self.assert_refused("adaptive-receipt", no_op, "CONTRACT_VALUE_INVALID")

        hash_no_op = import_receipt()
        hash_no_op["files"][1]["after"]["sha256"] = (
            hash_no_op["files"][1]["before"]["sha256"]
        )
        self.assert_refused(
            "adaptive-receipt", hash_no_op, "CONTRACT_VALUE_INVALID"
        )

        wrong_backup = import_receipt()
        wrong_backup["files"][1]["backupSha256"] = HASH_D
        self.assert_refused(
            "adaptive-receipt", wrong_backup, "CONTRACT_VALUE_INVALID"
        )

        unverified_recovery = import_receipt()
        unverified_recovery["status"] = "recovery-required"
        unverified_recovery["offlineEvidence"][0]["verified"] = False
        unverified_recovery["files"][1]["afterVerified"] = False
        unverified_recovery["configurationChanges"][0]["afterVerified"] = False
        unverified_recovery["verificationResults"][0]["success"] = False
        self.assert_refused(
            "adaptive-receipt", unverified_recovery, "CONTRACT_VALUE_INVALID"
        )

        no_verification = import_receipt()
        no_verification["verificationResults"] = []
        self.assert_refused(
            "adaptive-receipt", no_verification, "CONTRACT_VALUE_INVALID"
        )

        pending = import_receipt()
        pending.update({"status": "pending", "mutationOccurred": False})
        pending["offlineEvidence"][0]["verified"] = False
        pending["files"][0]["afterVerified"] = False
        pending["files"][1]["afterVerified"] = False
        pending["configurationChanges"][0]["afterVerified"] = False
        pending["verificationResults"] = []
        self.assert_valid("adaptive-receipt", pending)

        failed_no_change = copy.deepcopy(pending)
        failed_no_change["status"] = "failed-no-change"
        self.assert_valid("adaptive-receipt", failed_no_change)

        rolled_back = import_receipt()
        rolled_back["status"] = "rolled-back"
        for record in rolled_back["files"]:
            record.update({"afterVerified": False, "rollbackVerified": True})
        rolled_back["configurationChanges"][0].update(
            {"afterVerified": False, "rollbackVerified": True}
        )
        rolled_back["verificationResults"] = []
        self.assert_valid("adaptive-receipt", rolled_back)

    def test_conversion_mutation_receipt_and_recovery_rules(self):
        lossy = conversion_report()
        lossy["validation"]["lossCount"] = 1
        self.assert_refused("conversion-report", lossy, "CONTRACT_VALUE_INVALID")

        blocked = conversion_report()
        blocked.update(
            {
                "outputFingerprintSha256": "",
                "blocked": True,
                "issues": [
                    issue(
                        "CONVERSION_BLOCKED",
                        "blocker",
                        "source/original/server/data/map.orsc",
                        "An input record cannot be represented exactly.",
                    )
                ],
            }
        )
        blocked["validation"]["lossCount"] = 1
        self.assert_valid("conversion-report", blocked)

        free_space = mutation_plan()
        free_space["requirements"]["requiredFreeSpaceBytes"] = 2
        self.assert_refused("mutation-plan", free_space, "CONTRACT_VALUE_INVALID")

        placeholder = mutation_plan()
        placeholder["backupRootRelativePath"] = "backups/<transaction-id>"
        self.assert_refused("mutation-plan", placeholder, "UNSAFE_PATH")

        wrong_transaction_path = mutation_plan()
        wrong_transaction_path["receiptRelativePath"] = f"receipts/{UNDO_ID}.json"
        self.assert_refused("mutation-plan", wrong_transaction_path, "CONTRACT_VALUE_INVALID")

        wrong_backup = mutation_plan()
        wrong_backup["actions"][1]["backupRelativePath"] = (
            f"backups/{UNDO_ID}/before/server/world.conf"
        )
        self.assert_refused("mutation-plan", wrong_backup, "CONTRACT_VALUE_INVALID")

        missing_config_bytes = mutation_plan()
        missing_config_bytes["actions"].pop()
        self.assert_refused(
            "mutation-plan", missing_config_bytes, "CONTRACT_VALUE_INVALID"
        )

        unsafe_mutation = mutation_plan()
        unsafe_mutation["actions"][0]["destinationRelativePath"] = "../server.jar"
        self.assert_refused("mutation-plan", unsafe_mutation, "UNSAFE_PATH")

        unverified = import_receipt()
        unverified["configurationChanges"][0]["afterVerified"] = False
        self.assert_refused("adaptive-receipt", unverified, "CONTRACT_VALUE_INVALID")

        missing_config_receipt = import_receipt()
        missing_config_receipt["files"].pop()
        self.assert_refused(
            "adaptive-receipt", missing_config_receipt, "CONTRACT_VALUE_INVALID"
        )

        no_mutation = import_receipt()
        no_mutation["mutationOccurred"] = False
        self.assert_refused("adaptive-receipt", no_mutation, "CONTRACT_VALUE_INVALID")

        recovery_required = import_receipt()
        recovery_required["status"] = "recovery-required"
        recovery_required["files"][1]["afterVerified"] = False
        recovery_required["configurationChanges"][0]["afterVerified"] = False
        recovery_required["verificationResults"][0]["success"] = False
        self.assert_valid("adaptive-receipt", recovery_required)

        undo = import_receipt()
        undo.update(
            {
                "transactionId": UNDO_ID,
                "transactionType": "undo",
                "status": "reverted",
                "revertsTransactionId": TRANSACTION_ID,
            }
        )
        undo["files"][0].update(
            {
                "before": present_state(HASH_A),
                "after": absent_state(),
                "backupRelativePath": f"backups/{UNDO_ID}/before/manifest.json",
                "backupSha256": HASH_A,
                "afterVerified": False,
                "rollbackVerified": True,
            }
        )
        undo["files"][1].update(
            {
                "before": present_state(HASH_C),
                "after": present_state(HASH_B),
                "backupRelativePath": f"backups/{UNDO_ID}/before/server/world.conf",
                "backupSha256": HASH_C,
                "afterVerified": False,
                "rollbackVerified": True,
            }
        )
        undo["configurationChanges"][0].update(
            {
                "beforePresent": True,
                "beforeValue": "maps/packages/example",
                "afterPresent": False,
                "afterValue": "",
                "afterVerified": False,
                "rollbackVerified": True,
            }
        )
        self.assert_valid("adaptive-receipt", undo)


if __name__ == "__main__":
    unittest.main()
