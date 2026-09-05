#!/usr/bin/env python3
"""Synthetic-only regression coverage for current-runtime upgrade transactions."""

from __future__ import annotations

import hashlib
import importlib.util
import json
import os
import shutil
import socket
import sqlite3
import subprocess
import tempfile
import unittest
import warnings
import zipfile
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


def build_provider_state_migration_core(provider_root: Path, build_root: Path) -> Path:
    """Build only the locked provider migrator into a deterministic fixture fat JAR."""
    source = (
        PROVIDER / "server/src/com/openrsc/server/database/CurrentBaseStateMigration.java"
    )
    json_jar = PROVIDER / "server/lib/json-20190722.jar"
    sqlite_jar = PROVIDER / "server/lib/sqlite-jdbc-3.34.0.jar"
    classes = build_root / "provider-migrator-classes"
    classes.mkdir()
    subprocess.run(
        ["javac", "-source", "8", "-target", "8", "-cp",
         f"{json_jar}:{sqlite_jar}", "-d", str(classes), str(source)],
        cwd=ROOT, check=True, capture_output=True, text=True,
    )
    destination = (
        provider_root / "output/current-platform/current-base-v1/server/core.jar"
    )
    destination.parent.mkdir(parents=True, exist_ok=True)
    entries: dict[str, bytes] = {}
    for path in sorted(classes.rglob("*")):
        if path.is_file():
            entries[path.relative_to(classes).as_posix()] = path.read_bytes()
    for dependency in (json_jar, sqlite_jar):
        with zipfile.ZipFile(dependency) as archive:
            for name in sorted(archive.namelist()):
                upper = name.upper()
                if name.endswith("/") or name in entries:
                    continue
                if upper == "META-INF/MANIFEST.MF" or upper.endswith((".SF", ".RSA", ".DSA")):
                    continue
                entries[name] = archive.read(name)
    with zipfile.ZipFile(destination, "w", zipfile.ZIP_DEFLATED) as archive:
        for name in sorted(entries):
            info = zipfile.ZipInfo(name, (2024, 1, 2, 3, 4, 6))
            info.compress_type = zipfile.ZIP_DEFLATED
            archive.writestr(info, entries[name])
    return destination


def build_fake_migration_core(provider_root: Path, build_root: Path) -> Path:
    source = build_root / "fake-provider/com/openrsc/server/database/CurrentBaseStateMigration.java"
    source.parent.mkdir(parents=True)
    source.write_text(
        """package com.openrsc.server.database;
public final class CurrentBaseStateMigration {
  public static void main(String[] args) throws Exception {
    String mode = System.getenv("WORLD_BUILDER_FAKE_MIGRATOR_MODE");
    if ("timeout".equals(mode)) { Thread.sleep(5000L); return; }
    if ("oversized".equals(mode)) {
      StringBuilder value = new StringBuilder();
      for (int i = 0; i < 70000; i++) value.append('x');
      System.out.print(value.toString()); return;
    }
    System.exit(2);
  }
}
""",
        encoding="utf-8",
    )
    classes = build_root / "fake-provider-classes"
    classes.mkdir()
    subprocess.run(
        ["javac", "-source", "8", "-target", "8", "-d", str(classes), str(source)],
        cwd=ROOT, check=True, capture_output=True, text=True,
    )
    destination = (
        provider_root / "output/current-platform/current-base-v1/server/core.jar"
    )
    with zipfile.ZipFile(destination, "w", zipfile.ZIP_DEFLATED) as archive:
        for path in sorted(classes.rglob("*.class")):
            info = zipfile.ZipInfo(path.relative_to(classes).as_posix(),
                                   (2024, 1, 2, 3, 4, 6))
            info.compress_type = zipfile.ZIP_DEFLATED
            archive.writestr(info, path.read_bytes())
    return destination


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
import java.nio.charset.StandardCharsets;
import java.nio.file.StandardOpenOption;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.EnumSet;
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
        Path packedSource = args.length > 9 && !"-".equals(args[9])
            ? Paths.get(args[9]) : null;
        Path packedReport = args.length > 10 && !"-".equals(args[10])
            ? Paths.get(args[10]) : null;
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
                            Path bundle = entries.iterator().next();
                            try (DirectoryStream<Path> transactions =
                                Files.newDirectoryStream(bundle)) {
                                Path release = transactions.iterator().next();
                                Files.write(release.resolve("unexpected.bin"), new byte[] {1},
                                    StandardOpenOption.CREATE_NEW);
                            }
                        }
                    }
                    if (selected(failures, milestone)) {
                        throw new Exception("injected-" + milestone);
                    }
                    if (selected(failures, "halt-" + milestone)) {
                        Runtime.getRuntime().halt(91);
                    }
                }
            };
        WorldBuilderCurrentRuntimeUpgradeTransaction transaction =
            new WorldBuilderCurrentRuntimeUpgradeTransaction(observer);
        try {
        if ("launch-inputs-stage".equals(operation) || "verify-launch-inputs-stage".equals(operation)) {
            Path stage = transactions.resolve(transactionId);
            Map<String,Object> migration;
            if ("launch-inputs-stage".equals(operation)) {
                WorldBuilderProviderCatalog.Composition composition = WorldBuilderProviderCatalog.resolve(catalog, identity);
                Map<String,Object> fixtureClassification = new LinkedHashMap<String,Object>();
                fixtureClassification.put("evidence", new ArrayList<Object>());
                migration = WorldBuilderCurrentRuntimeExecutionProfile.preservationFixture()
                    .migrationPlan(target, fixtureClassification, composition, packedSource, packedReport);
                Files.createDirectory(stage);
                for (WorldBuilderProviderCatalog.Artifact artifact : composition.artifacts) {
                    Path destination = stage.resolve(artifact.bundlePath);
                    Files.createDirectories(destination.getParent());
                    Files.copy(artifact.source, destination);
                }
                Map<String,Object> execution = (Map<String,Object>)migration.get("stagedExecution");
                Map<String,Object> map = (Map<String,Object>)migration.get("mapMigration");
                WorldBuilderCurrentRuntimeLayout.materialize(stage, (Map<String,Object>)execution.get("runtimeLayout"));
                transaction.stageReviewedPreservationMap(packedSource, packedReport, stage, map);
                if ("default-drift".equals(failures)) {
                    Files.write(stage.resolve("installed/server/current-base.conf"), new byte[] {32}, StandardOpenOption.APPEND);
                } else if ("map-drift".equals(failures)) {
                    Files.write(stage.resolve("migration/output/map/conversion/package/manifest.json"), new byte[] {32}, StandardOpenOption.APPEND);
                } else if ("partial-set".equals(failures)) {
                    ((java.util.List<Object>)execution.get("stagedOutputs")).remove(3);
                } else if ("output-hash".equals(failures)) {
                    ((Map<String,Object>)((java.util.List<Object>)execution.get("stagedOutputs")).get(1)).put("sha256", "changed");
                } else if ("existing-output".equals(failures)) {
                    Files.createDirectory(stage.resolve("migration/output/launch"));
                    Files.write(stage.resolve("migration/output/launch/user.txt"), new byte[] {42}, StandardOpenOption.CREATE_NEW);
                }
                WorldBuilderPreservationStagedMigrator.writeTypedConfiguration(stage,
                    (Map<String,Object>)migration.get("typedConfiguration"), execution, map);
                WorldBuilderPreservationStagedMigrator.stage(target, stage, execution);
                Files.write(transactions.resolve(transactionId + ".migration.json"),
                    WorldBuilderJsonDocuments.pretty(migration).getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE_NEW);
            } else migration = WorldBuilderJsonDocuments.readObject(transactions.resolve(transactionId + ".migration.json"));
            WorldBuilderPreservationStagedMigrator.verify(target, stage,
                (Map<String,Object>)migration.get("stagedExecution"), (Map<String,Object>)migration.get("mapMigration"));
            System.out.print(WorldBuilderJsonDocuments.pretty(migration));
        } else if ("render-launch-config".equals(operation)) {
            Map<String,Object> typed = WorldBuilderJsonDocuments.readObject(target.resolve("typed.json"));
            System.out.print(WorldBuilderCurrentRuntimeLaunchInputs.render(
                new String(Files.readAllBytes(target.resolve("defaults.conf")), StandardCharsets.UTF_8), typed));
        } else if ("verify-sealed-stage".equals(operation)) {
            Map<String,Object> plan = WorldBuilderJsonDocuments.readObject(
                transactions.resolve(transactionId + ".plan.json"));
            Map<String,Object> receipt = WorldBuilderJsonDocuments.readObject(
                transactions.resolve(transactionId + ".checkpoint.json"));
            Path pendingPath = transactions.resolve(transactionId + ".pending.json");
            Map<String,Object> pending = Files.exists(pendingPath)
                ? WorldBuilderJsonDocuments.readObject(pendingPath) : null;
            Map<String,Object> execution = WorldBuilderCurrentRuntimeUpgradeTransaction
                .restoreExecutionPlan(plan, receipt, pending);
            WorldBuilderCurrentRuntimeUpgradeTransaction.verifyExecutionRelease(
                transactions.resolve(transactionId), execution);
            System.out.print(WorldBuilderJsonDocuments.pretty(execution));
        } else if ("profile-migration".equals(operation)) {
            Map<String,Object> classification = new LinkedHashMap<String,Object>();
            classification.put("evidence", new ArrayList<Object>());
            WorldBuilderProviderCatalog.Composition composition =
                WorldBuilderProviderCatalog.resolve(catalog, identity);
            System.out.print(WorldBuilderJsonDocuments.pretty(
                WorldBuilderCurrentRuntimeExecutionProfile.preservationFixture()
                    .migrationPlan(target, classification, composition, null, null)));
        } else if ("profile-migration-stage".equals(operation)
            || "profile-migration-stage-tamper".equals(operation)
            || "profile-migration-provider-refusal".equals(operation)) {
            Map<String,Object> classification = new LinkedHashMap<String,Object>();
            classification.put("evidence", new ArrayList<Object>());
            WorldBuilderCurrentRuntimeExecutionProfile profile =
                WorldBuilderCurrentRuntimeExecutionProfile.preservationFixture();
            WorldBuilderProviderCatalog.Composition composition =
                WorldBuilderProviderCatalog.resolve(catalog, identity);
            Map<String,Object> migration = profile.migrationPlan(
                target, classification, composition, null, null);
            Path stage = transactions.resolve(transactionId);
            Files.createDirectory(stage);
            Files.createDirectory(stage.resolve("migration"));
            for (WorldBuilderProviderCatalog.Artifact artifact : composition.artifacts) {
                if (!("runtime/server/core.jar".equals(artifact.bundlePath)
                    || "contracts/runtime/current-base-v1/state-migration.json"
                        .equals(artifact.bundlePath))) continue;
                Path destination = stage.resolve(artifact.bundlePath);
                Files.createDirectories(destination.getParent());
                Files.copy(artifact.source, destination, StandardCopyOption.COPY_ATTRIBUTES);
            }
            if ("profile-migration-provider-refusal".equals(operation)) {
                if ("contract-tamper".equals(failures)) {
                    Files.write(stage.resolve(
                        "contracts/runtime/current-base-v1/state-migration.json"),
                        new byte[] {32}, StandardOpenOption.APPEND);
                } else if ("tool-tamper".equals(failures)) {
                    Files.write(stage.resolve("runtime/server/core.jar"),
                        new byte[] {32}, StandardOpenOption.APPEND);
                } else if ("provider-timeout".equals(failures)) {
                    WorldBuilderPreservationStagedMigrator.processTimeoutSeconds = 1L;
                }
            }
            Map<String,Object> execution = (Map<String,Object>)migration.get("stagedExecution");
            WorldBuilderPreservationStagedMigrator.writeTypedConfiguration(stage,
                (Map<String,Object>)migration.get("typedConfiguration"), execution,
                (Map<String,Object>)migration.get("mapMigration"));
            WorldBuilderPreservationStagedMigrator.stage(target, stage, execution);
            if ("profile-migration-stage-tamper".equals(operation)) {
                Path config = stage.resolve(
                    WorldBuilderPreservationStagedMigrator.CONFIG_OUTPUT);
                if ("path".equals(failures)) {
                    Files.move(config, config.getParent().resolve("target-selected.json"));
                } else if ("hash".equals(failures)) {
                    Files.write(config, new byte[] {32}, StandardOpenOption.APPEND);
                } else if ("mode".equals(failures)) {
                    Files.setPosixFilePermissions(config, EnumSet.of(
                        PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_WRITE,
                        PosixFilePermission.GROUP_READ,
                        PosixFilePermission.OTHERS_READ));
                } else if ("row-schema".equals(failures)) {
                    Path evidencePath = stage.resolve(
                        "migration/output/state/current-base-migration-evidence.json");
                    Map<String,Object> evidence = WorldBuilderJsonDocuments.readObject(evidencePath);
                    evidence.put("migrationRowId", "preservation-core-sqlite-to-current-base-v1");
                    Files.write(evidencePath, WorldBuilderJsonDocuments.pretty(evidence)
                        .getBytes(StandardCharsets.UTF_8), StandardOpenOption.TRUNCATE_EXISTING);
                }
            }
            WorldBuilderPreservationStagedMigrator.verify(target, stage, execution,
                (Map<String,Object>)migration.get("mapMigration"));
            System.out.print(WorldBuilderJsonDocuments.pretty(migration));
        } else if ("runtime-layout".equals(operation)
            || "runtime-layout-tamper".equals(operation)
            || "runtime-layout-extra".equals(operation)
            || "runtime-layout-empty-directory".equals(operation)
            || "runtime-layout-state-policy".equals(operation)
            || "runtime-layout-map-policy".equals(operation)
            || "runtime-layout-mode".equals(operation)) {
            WorldBuilderProviderCatalog.Composition composition =
                WorldBuilderProviderCatalog.resolve(catalog, identity);
            Map<String,Object> layout = WorldBuilderCurrentRuntimeLayout.inspect(composition);
            Path release = transactions.resolve(transactionId);
            Files.createDirectory(release);
            for (WorldBuilderProviderCatalog.Artifact artifact : composition.artifacts) {
                String role = (String)artifact.inventory.get("role");
                if (!(role.equals("server-runtime") || role.equals("server-plugins")
                    || role.equals("server-content") || role.equals("client-runtime")
                    || role.equals("client-content"))) continue;
                Path destination = release.resolve(artifact.bundlePath);
                Files.createDirectories(destination.getParent());
                Files.copy(artifact.source, destination);
            }
            WorldBuilderCurrentRuntimeLayout.materialize(release, layout);
            if ("runtime-layout-tamper".equals(operation)) {
                Files.write(release.resolve("installed/server/conf/server/settings.txt"),
                    new byte[] {32}, StandardOpenOption.APPEND);
            } else if ("runtime-layout-extra".equals(operation)) {
                Files.write(release.resolve("installed/client/unexpected.bin"),
                    new byte[] {1}, StandardOpenOption.CREATE_NEW);
            } else if ("runtime-layout-empty-directory".equals(operation)) {
                Files.createDirectory(release.resolve("installed/client/unexpected-empty"));
            } else if ("runtime-layout-mode".equals(operation)) {
                Files.setPosixFilePermissions(
                    release.resolve("installed/server/conf/server/settings.txt"),
                    EnumSet.of(PosixFilePermission.OWNER_READ,
                        PosixFilePermission.GROUP_READ));
            } else if ("runtime-layout-state-policy".equals(operation)) {
                ((Map<String,Object>)layout.get("statePolicy")).put("sqliteFile", "replacement.db");
            } else if ("runtime-layout-map-policy".equals(operation)) {
                ((Map<String,Object>)layout.get("mapPolicy")).put("rootProperty", "unreviewed.mapRoot");
            }
            WorldBuilderCurrentRuntimeLayout.verify(release, layout);
            System.out.print(WorldBuilderJsonDocuments.pretty(layout));
        } else if ("map-boundary".equals(operation)
            || "map-boundary-tamper".equals(operation)
            || "map-boundary-extra".equals(operation)
            || "map-boundary-source-drift".equals(operation)) {
            WorldBuilderProviderCatalog.Composition composition =
                WorldBuilderProviderCatalog.resolve(catalog, identity);
            Map<String,Object> migration = transaction.inspectReviewedPreservationMigration(
                target, composition, packedSource, packedReport);
            Map<String,Object> map = (Map<String,Object>)migration.get("mapMigration");
            Path stage = transactions.resolve(transactionId);
            Files.createDirectory(stage);
            if ("map-boundary-source-drift".equals(operation)) {
                try (java.util.stream.Stream<Path> paths = Files.walk(packedSource)) {
                    Path changed = paths.filter(Files::isRegularFile).sorted().findFirst().get();
                    Files.write(changed, new byte[] {32}, StandardOpenOption.APPEND);
                }
            }
            transaction.stageReviewedPreservationMap(
                packedSource, packedReport, stage, map);
            if ("map-boundary-tamper".equals(operation)) {
                Files.write(stage.resolve(
                    "migration/output/map/conversion/package/manifest.json"),
                    new byte[] {32}, StandardOpenOption.APPEND);
            } else if ("map-boundary-extra".equals(operation)) {
                Files.write(stage.resolve(
                    "migration/output/map/conversion/unexpected.bin"),
                    new byte[] {1}, StandardOpenOption.CREATE_NEW);
            }
            transaction.verifyReviewedPreservationMap(stage, map);
            System.out.print(WorldBuilderJsonDocuments.pretty(migration));
        } else if ("lease-anchor-replaced".equals(operation)) {
            WorldBuilderCurrentRuntimeUpgradeTransaction.Preview preview =
                transaction.preview(target, transactions, catalog,
                    identity, adapter, project, transactionId);
            Map<String,Object> migration =
                (Map<String,Object>)preview.plan.get("migrationPlan");
            Map<String,Object> typed =
                (Map<String,Object>)migration.get("typedConfiguration");
            try (WorldBuilderCurrentRuntimeOfflineLease ignored =
                WorldBuilderCurrentRuntimeOfflineLease.acquire(target, typed, true,
                    new WorldBuilderCurrentRuntimeOfflineLease.IdentityObserver() {
                        @Override public void observe(String milestone, Path anchor)
                            throws java.io.IOException {
                            Path displaced = anchor.resolveSibling("displaced-ledger.json");
                            Files.move(anchor, displaced);
                            Files.copy(displaced, anchor);
                        }
                    })) { }
        } else if ("preview".equals(operation)) {
            System.out.print(transaction.preview(target, transactions, catalog,
                identity, adapter, project, transactionId).toJson());
        } else if ("preview-production".equals(operation)
            || "preview-production-packed".equals(operation)
            || "stage-production-packed".equals(operation)
            || "verify-production-packed-tamper".equals(operation)
            || "verify-production-packed-extra".equals(operation)
            || "stage-production-packed-source-drift".equals(operation)
            || "apply-production".equals(operation)) {
            WorldBuilderCurrentRuntimeUpgradeTransaction.Preview preview =
                transaction.previewPreservationFixture(target, transactions, catalog,
                    identity, project, transactionId, packedSource, packedReport);
            if ("preview-production".equals(operation)
                || "preview-production-packed".equals(operation)) {
                System.out.print(preview.toJson());
            } else if ("stage-production-packed".equals(operation)
                || "verify-production-packed-tamper".equals(operation)
                || "verify-production-packed-extra".equals(operation)
                || "stage-production-packed-source-drift".equals(operation)) {
                Path stage = transactions.resolve(transactionId);
                if ("stage-production-packed-source-drift".equals(operation)) {
                    try (java.util.stream.Stream<Path> paths = Files.walk(packedSource)) {
                        Path changed = paths.filter(Files::isRegularFile).sorted().findFirst().get();
                        Files.write(changed, new byte[] {32}, StandardOpenOption.APPEND);
                    }
                }
                Map<String,Object> executionPlan = transaction.stageReviewedRelease(preview, stage);
                Files.write(transactions.resolve(transactionId + ".plan.json"),
                    preview.toJson().getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE_NEW);
                Files.write(transactions.resolve(transactionId + ".checkpoint.json"),
                    WorldBuilderJsonDocuments.pretty(WorldBuilderCurrentRuntimeUpgradeTransaction.receipt(
                        executionPlan, "pending", false, false, "", "staging-verified"))
                        .getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE_NEW);
                if ("verify-production-packed-tamper".equals(operation)) {
                    Path map = stage.resolve(
                        "migration/output/map/conversion/package/manifest.json");
                    Files.write(map, new byte[] {32}, StandardOpenOption.APPEND);
                    transaction.verifyReviewedRelease(preview, stage, executionPlan);
                } else if ("verify-production-packed-extra".equals(operation)) {
                    Files.write(stage.resolve(
                        "migration/output/map/conversion/unexpected.bin"),
                        new byte[] {1}, StandardOpenOption.CREATE_NEW);
                    transaction.verifyReviewedRelease(preview, stage, executionPlan);
                }
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
        harness_build = subprocess.run(
            ["javac", "-source", "8", "-target", "8", "-cp", str(cls.classes),
             "-d", str(cls.classes), str(harness)],
            cwd=ROOT, capture_output=True, text=True,
        )
        if harness_build.returncode:
            raise AssertionError(harness_build.stdout + harness_build.stderr)

        shutil.copytree(ROOT / "tools/world-builder/resources", cls.classes, dirs_exist_ok=True)
        # Exercise the actual pinned runtime parser, without building or launching a server.
        parser = cls.shared_root / "RuntimeConfigHarness.java"
        parser.write_text('''package com.openrsc.worldbuilder;
public final class RuntimeConfigHarness {
  public static void main(String[] args) throws Exception {
    com.openrsc.server.util.YMLReader reader = new com.openrsc.server.util.YMLReader();
    reader.loadFromYML(args[0]);
    java.util.Map<String,Object> values = new java.util.LinkedHashMap<String,Object>();
    for (int i = 1; i < args.length; i++) values.put(args[i], reader.getAttribute(args[i]));
    System.out.print(WorldBuilderJsonDocuments.pretty(values));
  }
}
''', encoding="utf-8")
        cls.parser_classpath = os.pathsep.join((
            str(cls.classes), str(PROVIDER / "server/lib/log4j-api-2.17.0.jar"),
        ))
        subprocess.run([
            "javac", "-source", "8", "-target", "8", "-cp", cls.parser_classpath,
            "-d", str(cls.classes), str(parser),
            str(PROVIDER / "server/src/com/openrsc/server/util/YMLReader.java"),
        ], check=True, capture_output=True, text=True)

        cls.provider_root = cls.shared_root / "provider"
        shutil.copytree(PROVIDER / "current-platform", cls.provider_root / "current-platform")
        (cls.provider_root / "scripts").mkdir()
        shutil.copy2(provider_tool, cls.provider_root / "scripts/current-platform-composition.py")
        materialize_synthetic_bundle_payloads(cls.provider_root)
        cls.migration_core = build_provider_state_migration_core(
            cls.provider_root, cls.shared_root
        )
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
        cls.behavior_root = cls.shared_root / "provider-behavior"
        shutil.copytree(cls.provider_root, cls.behavior_root)
        build_fake_migration_core(cls.behavior_root, cls.shared_root)
        cls.behavior_catalog = cls.behavior_root / "current-platform"
        cls.behavior_identity = cls._resolve(
            cls.behavior_root / "scripts/current-platform-composition.py",
            cls.behavior_catalog, cls.behavior_root,
            cls.shared_root / "provider-behavior.json",
        )
        cls.layout_root = cls.shared_root / "provider-layout"
        shutil.copytree(cls.provider_root, cls.layout_root)
        for relative, records in (
            ("output/current-platform/current-base-v1/server/content.zip", {
                "connections.conf": b"db_type: sqlite\n",
                "current-base.conf": (PROVIDER / "current-platform/runtime/current-base-v1/server/current-base.conf").read_bytes(),
                "conf/server/settings.txt": b"current base server content\n",
            }),
            ("output/current-platform/current-base-v1/client/content.zip", {
                "Cache/config.txt": b"current base client content\n",
                "Cache/audio/silence.dat": b"invented audio fixture\n",
            }),
        ):
            archive = cls.layout_root / relative
            with zipfile.ZipFile(archive, "w", compression=zipfile.ZIP_DEFLATED) as output:
                for name, payload in sorted(records.items()):
                    output.writestr(name, payload)
        cls.layout_catalog = cls.layout_root / "current-platform"
        cls.layout_identity = cls._resolve(
            cls.layout_root / "scripts/current-platform-composition.py",
            cls.layout_catalog, cls.layout_root,
            cls.shared_root / "provider-layout.json",
        )
        cls.layout_collision_contracts = []
        cls.layout_policy_root = cls.shared_root / "provider-layout-unreviewed-state"
        shutil.copytree(cls.layout_root, cls.layout_policy_root)
        profile_path = cls.layout_policy_root / "current-platform/runtime/current-base-v1/profile.json"
        profile = json.loads(profile_path.read_text())
        profile["statePolicy"]["sqliteRootPolicy"] = "allow-state-inside-code"
        profile_path.write_text(json.dumps(profile, indent=2) + "\n")
        cls.layout_policy_catalog = cls.layout_policy_root / "current-platform"
        cls.layout_policy_identity = cls._resolve(
            cls.layout_policy_root / "scripts/current-platform-composition.py",
            cls.layout_policy_catalog, cls.layout_policy_root,
            cls.shared_root / "provider-layout-unreviewed-state.json",
        )
        cls.layout_map_policy_root = cls.shared_root / "provider-layout-unreviewed-map"
        shutil.copytree(cls.layout_root, cls.layout_map_policy_root)
        map_profile_path = cls.layout_map_policy_root / "current-platform/runtime/current-base-v1/profile.json"
        map_profile = json.loads(map_profile_path.read_text())
        map_profile["mapPolicy"]["externalRootPolicy"] = "allow-aliased-map-roots"
        map_profile_path.write_text(json.dumps(map_profile, indent=2) + "\n")
        cls.layout_map_policy_catalog = cls.layout_map_policy_root / "current-platform"
        cls.layout_map_policy_identity = cls._resolve(
            cls.layout_map_policy_root / "scripts/current-platform-composition.py",
            cls.layout_map_policy_catalog, cls.layout_map_policy_root,
            cls.shared_root / "provider-layout-unreviewed-map.json",
        )
        for collision_name, entries in (
            ("parent-case", {"Dir/a.txt": b"a", "dir/b.txt": b"b"}),
            ("file-prefix", {"node": b"file", "node/child.txt": b"child"}),
        ):
            collision_root = cls.shared_root / ("provider-layout-" + collision_name)
            shutil.copytree(cls.layout_root, collision_root)
            archive = collision_root / (
                "output/current-platform/current-base-v1/server/content.zip"
            )
            with zipfile.ZipFile(archive, "w", compression=zipfile.ZIP_DEFLATED) as output:
                for name, payload in entries.items():
                    output.writestr(name, payload)
            collision_catalog = collision_root / "current-platform"
            collision_identity = cls._resolve(
                collision_root / "scripts/current-platform-composition.py",
                collision_catalog, collision_root,
                cls.shared_root / ("provider-layout-" + collision_name + ".json"),
            )
            cls.layout_collision_contracts.append(
                (collision_catalog, collision_identity)
            )
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

    def complete_packed_source(self) -> tuple[Path, Path]:
        target, source, report = self.complete_packed_target_source()
        return source, report

    def complete_packed_target_source(self) -> tuple[Path, Path, Path]:
        module_path = ROOT / "tests/myworld/test-world-builder-packed-conversion.py"
        specification = importlib.util.spec_from_file_location(
            "current_upgrade_packed_fixture", module_path
        )
        assert specification is not None and specification.loader is not None
        module = importlib.util.module_from_spec(specification)
        specification.loader.exec_module(module)
        helper = module.PackedConversionTest()
        helper.classes = self.classes
        parent = self.case_root / "packed-evidence"
        parent.mkdir()
        target = helper.fixture(parent)
        source, report, _ = helper.discover_and_copy(target, parent)
        configuration = target / "server/conf/preservation.conf"
        configuration.parent.mkdir(parents=True, exist_ok=True)
        configuration.write_text(
            "server_name: Preservation Map Fixture\n"
            "server_port: 43594\n"
            "ws_server_port: 43494\n"
            "db_type: sqlite\n",
            encoding="utf-8",
        )
        return target, source, report

    def run_harness(
        self, operation: str, target: Path, workspace: Path, txid: str,
        failures: str = "-", identity: Path | None = None,
        catalog: Path | None = None, adapter: Path | None = None,
        environment: dict[str, str] | None = None,
        packed_source: Path | None = None, packed_report: Path | None = None,
    ) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            ["java", "-cp", str(self.classes),
             "com.openrsc.worldbuilder.CurrentUpgradeHarness", operation, failures,
             str(target), str(workspace), str(catalog or self.catalog),
             str(identity or self.identity),
             str(adapter or CONTRACTS / "input-adapter-preservation-v1.json"),
             str(CONTRACTS / "project-capability-v1.json"), txid,
             str(packed_source) if packed_source else "-",
             str(packed_report) if packed_report else "-"],
            cwd=ROOT, text=True, capture_output=True, check=False,
            env=environment,
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

    def test_preview_requires_both_target_ports_available(self) -> None:
        for port in (43594, 43494):
            with self.subTest(port=port):
                target = self.target("preservation-t0")
                workspace = self.workspace()
                before = tree_snapshot(target)
                with socket.socket() as listener:
                    listener.bind(("0.0.0.0", port))
                    listener.listen(1)
                    refused = self.run_harness(
                        "preview", target, workspace, f"occupied-{port}"
                    )
                self.assertNotEqual(0, refused.returncode)
                self.assertIn("CODE=OFFLINE_REQUIRED", refused.stderr)
                self.assertEqual(before, tree_snapshot(target))
                self.assertEqual({}, tree_snapshot(workspace))
                self.case.cleanup(); self.setUp()

    def test_offline_lease_rejects_same_byte_anchor_replacement(self) -> None:
        target = self.target("managed-n")
        workspace = self.workspace()
        refused = self.run_harness(
            "lease-anchor-replaced", target, workspace, "anchor-replaced"
        )
        self.assertNotEqual(0, refused.returncode)
        self.assertIn("CODE=OFFLINE_REQUIRED", refused.stderr)
        self.assertIn("identity changed", refused.stderr)

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
        self.assertEqual("synthetic-fixture", plan["inputAdapter"]["evidenceAuthority"])
        self.assertEqual("preservation-staging-fixture-v1",
                         plan["executionProfile"]["profileId"])
        self.assertFalse(plan["executionProfile"]["executionReady"])
        self.assertEqual("migration-and-verification-not-implemented",
                         plan["executionProfile"]["executionReadinessStatus"])
        readiness = {
            item["conditionId"]: item["ready"]
            for item in plan["executionProfile"]["executionReadinessConditions"]
        }
        self.assertTrue(readiness["typed-configuration-staging"])
        self.assertTrue(readiness["closed-sqlite-current-schema-migration"])
        self.assertTrue(readiness["provider-state-schema-migration-row"])
        self.assertTrue(readiness["complete-canonical-map-package"])
        self.assertTrue(readiness["activation-bound-sqlite-migration-inventory"])
        self.assertFalse(readiness["live-instance-installation-and-recovery"])
        self.assertFalse(readiness["runnable-current-runtime-layout"])
        self.assertFalse(readiness["editor-installed-execution-verifier-integration"])
        self.assertFalse(readiness["staged-runtime-launch-handshake-login-gameplay"])
        self.assertEqual("Preservation",
                         plan["migrationPlan"]["typedConfiguration"]["serverName"])
        self.assertEqual("named-profile",
                         plan["migrationPlan"]["typedConfiguration"]["precedence"])
        self.assertEqual("exact-packed-to-layered-v2-u16",
                         plan["migrationPlan"]["mapMigration"]["migrationId"])
        self.assertTrue(plan["migrationPlan"]["durableState"])
        self.assertEqual(before_target, tree_snapshot(target))
        self.assertEqual(before_workspace, tree_snapshot(workspace))

        installable_preview = self.run_harness(
            "preview-production", target, workspace, "production-installable-provider",
            identity=self.identity, catalog=self.catalog,
        )
        self.assertEqual(0, installable_preview.returncode, installable_preview.stderr)
        installable_plan = json.loads(installable_preview.stdout)
        self.assertEqual("UPGRADE_READY", installable_plan["classificationStatus"])
        self.assertTrue(installable_plan["destination"]["installable"])
        self.assertFalse(installable_plan["executionProfile"]["executionReady"])
        self.assertFalse(installable_plan["activationAuthorized"])
        refused = self.run_harness(
            "apply-production", target, workspace, "production-installable-provider",
            identity=self.identity, catalog=self.catalog,
        )
        self.assertNotEqual(0, refused.returncode)
        self.assertIn("CODE=RUNTIME_UPGRADE_REQUIRED", refused.stderr)
        self.assertIn("live-instance installation/recovery",
                      refused.stderr)
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
        # The public production adapter must no longer recognize this invented topology.
        self.assertEqual(3, previewed.returncode, previewed.stderr)
        self.assertIn("CONVERSION_BLOCKED", previewed.stderr)
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

        incomplete_map = subprocess.run(
            ["java", "-cp", str(self.classes),
             "com.openrsc.worldbuilder.WorldBuilderCli",
             "preview-current-runtime-upgrade", *common,
             "--adapter", "preservation-family-v1",
             "--packed-source-root", str(target)],
            cwd=ROOT, text=True, capture_output=True, check=False,
        )
        self.assertEqual(2, incomplete_map.returncode)
        self.assertIn("must be supplied together", incomplete_map.stderr)
        self.assertEqual(before_target, tree_snapshot(target))
        self.assertEqual(before_workspace, tree_snapshot(workspace))

        applied = subprocess.run(
            ["java", "-cp", str(self.classes),
             "com.openrsc.worldbuilder.WorldBuilderCli",
             "apply-current-runtime-upgrade", *common,
             "--adapter", "preservation-family-v1",
             "--confirmation-identity", "not-production-authority"],
            cwd=ROOT, text=True, capture_output=True, check=False,
        )
        self.assertEqual(3, applied.returncode)
        self.assertIn("CONVERSION_BLOCKED", applied.stderr)
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
        self.assertEqual(3, typed["combatExperienceRate"])
        self.assertEqual(3, typed["skillingExperienceRate"])
        self.assertEqual(43595, typed["gamePort"])
        self.assertEqual("localhost", typed["bindAddress"])
        self.assertEqual("first-value-wins", typed["duplicatePolicy"])
        self.assertEqual([], typed["externalSecretReferences"])

    def test_colon_configuration_preserves_public_binding_and_explicit_sqlite(self) -> None:
        fixture_root = ROOT / "tests/fixtures/preservation-production-migration-v1"
        target = self.case_root / "colon-config-target"
        shutil.copytree(fixture_root / "targets/local-precedence", target)
        (target / "server/conf/local.conf").write_text(
            "world:\n"
            "  server_name: Public Preservation\n"
            "  server_bind_address: 0.0.0.0\n"
            "  server_port: 43595\n"
            "  ws_server_port: 43495\n"
            "database:\n"
            "  db_engine: sqlite\n",
            encoding="utf-8",
        )
        workspace = self.workspace()
        result = self.run_harness("profile-migration", target, workspace, "colon-config")
        self.assertEqual(0, result.returncode, result.stderr)
        typed = json.loads(result.stdout)["typedConfiguration"]
        self.assertEqual("Public Preservation", typed["serverName"])
        self.assertEqual("0.0.0.0", typed["bindAddress"])
        self.assertEqual(43595, typed["gamePort"])
        self.assertEqual(43495, typed["websocketPort"])
        self.assertEqual("sqlite", typed["databaseMigration"]["engine"])

    def test_real_layout_connections_then_named_profile_are_hash_bound(self) -> None:
        fixture_root = ROOT / "tests/fixtures/preservation-production-migration-v1"
        target = self.case_root / "real-config-target"
        shutil.copytree(fixture_root / "targets/local-precedence", target)
        (target / "server/connections.conf").write_text(
            "db_type: sqlite\nmonitor_ip: localhost\n", encoding="utf-8"
        )
        (target / "server/preservation.conf").write_text(
            "world:\n"
            "  server_name: Public Preservation\n"
            "  server_bind_address: 0.0.0.0\n"
            "  server_port: 43596\n"
            "  ws_server_port: 43496\n"
            "  combat_exp_rate: 1\n"
            "  skilling_exp_rate: 2\n"
            "  member_world: true\n",
            encoding="utf-8",
        )
        workspace = self.workspace()
        result = self.run_harness(
            "profile-migration", target, workspace, "real-config"
        )
        self.assertEqual(0, result.returncode, result.stderr)
        typed = json.loads(result.stdout)["typedConfiguration"]
        self.assertEqual("server/preservation.conf", typed["sourceRelativePath"])
        self.assertEqual(
            "connections-first-then-named-profile", typed["precedence"]
        )
        self.assertEqual("sqlite", typed["databaseMigration"]["engine"])
        self.assertEqual("0.0.0.0", typed["bindAddress"])
        self.assertEqual(1, typed["combatExperienceRate"])
        self.assertEqual(2, typed["skillingExperienceRate"])
        self.assertEqual(["member_world", "monitor_ip"], typed["untranslatedKeys"])
        self.assertIn(
            "untranslated-legacy-configuration-keys",
            typed["configurationBlockers"],
        )
        self.assertEqual(
            ["server/connections.conf", "server/preservation.conf"],
            [item["relativePath"] for item in typed["sourceInventory"]],
        )
        for item in typed["sourceInventory"]:
            source = target / item["relativePath"]
            self.assertEqual(source.stat().st_size, item["size"])
            self.assertEqual(hashlib.sha256(source.read_bytes()).hexdigest(), item["sha256"])

    def test_provider_runtime_layout_is_exactly_materialized_and_verified(self) -> None:
        target = self.target("preservation-t0")
        for operation in (
            "runtime-layout", "runtime-layout-tamper", "runtime-layout-extra",
            "runtime-layout-empty-directory", "runtime-layout-mode", "runtime-layout-state-policy",
            "runtime-layout-map-policy",
        ):
            with self.subTest(operation=operation):
                workspace = self.case_root / (operation + "-transactions")
                workspace.mkdir()
                result = self.run_harness(
                    operation, target, workspace, operation,
                    identity=self.layout_identity, catalog=self.layout_catalog,
                )
                if operation == "runtime-layout":
                    self.assertEqual(0, result.returncode, result.stderr)
                    layout = json.loads(result.stdout)
                    self.assertTrue(layout["ready"])
                    self.assertEqual("openrsc.currentBaseStateRoot", layout["statePolicy"]["sqliteRootProperty"])
                    self.assertEqual("current_base.db", layout["statePolicy"]["sqliteFile"])
                    self.assertEqual("outside-code-runtime", layout["statePolicy"]["durableLocation"])
                    self.assertEqual({
                        "rootProperty": "openrsc.worldBuilderInstalledMapRoot",
                        "externalRootPolicy": "canonical-absolute-directory-disjoint-from-runtime",
                        "profileBinding": "manifest-sha256-and-package-identity",
                        "defaultLocation": "profile-relative-package",
                    }, layout["mapPolicy"])
                    self.assertGreater(len(layout["outputs"]), 5)
                    paths = {item["relativePath"] for item in layout["outputs"]}
                    self.assertIn("installed/server/core.jar", paths)
                    self.assertIn("installed/server/conf/server/settings.txt", paths)
                    self.assertIn("installed/client/Cache/config.txt", paths)
                else:
                    self.assertNotEqual(0, result.returncode)
                    self.assertIn("CODE=CONVERSION_BLOCKED", result.stderr)
        policy_workspace = self.case_root / "unreviewed-state-transactions"
        policy_workspace.mkdir()
        refused_policy = self.run_harness(
            "runtime-layout", target, policy_workspace, "unreviewed-state",
            identity=self.layout_policy_identity, catalog=self.layout_policy_catalog,
        )
        self.assertNotEqual(0, refused_policy.returncode)
        self.assertIn("reviewed external-state contract", refused_policy.stderr)
        self.assertEqual({}, tree_snapshot(policy_workspace))
        map_policy_workspace = self.case_root / "unreviewed-map-transactions"
        map_policy_workspace.mkdir()
        refused_map_policy = self.run_harness(
            "runtime-layout", target, map_policy_workspace, "unreviewed-map",
            identity=self.layout_map_policy_identity, catalog=self.layout_map_policy_catalog,
        )
        self.assertNotEqual(0, refused_map_policy.returncode)
        self.assertIn("reviewed external-map contract", refused_map_policy.stderr)
        self.assertEqual({}, tree_snapshot(map_policy_workspace))
        for index, (catalog, identity) in enumerate(self.layout_collision_contracts):
            workspace = self.case_root / f"runtime-layout-collision-{index}"
            workspace.mkdir()
            refused = self.run_harness(
                "runtime-layout", target, workspace, "collision",
                identity=identity, catalog=catalog,
            )
            self.assertNotEqual(0, refused.returncode)
            self.assertIn("CODE=CONVERSION_BLOCKED", refused.stderr)

    def test_production_staged_migrator_renders_config_snapshots_sqlite_and_converts_map(self) -> None:
        target = self.target("preservation-t0")
        map_source = target / "client/cache/landscape.pack"
        map_source.write_bytes(bytes(48 * 48 * 10))
        database = target / "server/inc/sqlite/preservation.db"
        database.parent.mkdir(parents=True)
        schema = (PROVIDER / "server/database/sqlite/retro.sqlite").read_text()
        with sqlite3.connect(database) as writable:
            writable.executescript(schema)
        connection = sqlite3.connect(f"file:{database}?mode=ro", uri=True)
        self.assertEqual("ok", connection.execute("PRAGMA integrity_check").fetchone()[0])
        connection.close()
        self.assertEqual(
            "301063f734b269573782995b1aa8ea32edba569dd95276bc9a35db680692f623",
            hashlib.sha256(database.read_bytes()).hexdigest(),
        )
        workspace = self.workspace()
        before = tree_snapshot(target)
        previewed = self.run_harness(
            "preview-production", target, workspace, "staged-production-preview",
            identity=self.identity, catalog=self.catalog,
        )
        self.assertEqual(0, previewed.returncode, previewed.stderr)
        plan = json.loads(previewed.stdout)
        self.assertEqual("T2B", plan["classificationTier"])
        self.assertFalse(plan["activationAuthorized"])
        execution = plan["migrationPlan"]["stagedExecution"]
        self.assertEqual("current-base-state-migration-v1",
                         execution["requiredStateMigrationContractId"])
        self.assertEqual([
            "preservation-retro-sqlite-to-current-base-v1",
            "preservation-core-sqlite-to-current-base-v1",
            "preservation-initialized-sqlite-to-current-base-v1",
        ], execution["requiredStateMigrationRowIds"])
        self.assertEqual(
            ["state-migration-manifest", "contract-schema", "server-runtime"],
            execution["requiredProviderArtifactRoles"],
        )
        self.assertTrue(execution["typedConfigurationReady"])
        self.assertTrue(execution["sqliteSnapshotReady"])
        self.assertTrue(execution["sqliteSchemaMigrationReady"])
        self.assertFalse(execution["mariaDbMigrationReady"])
        self.assertFalse(execution["canonicalMapPackageReady"])
        self.assertNotIn(
            "mariadb-external-stage-rollback-not-implemented",
            execution["readinessBlockers"],
        )
        self.assertEqual(1, len(execution["stagedOutputs"]))
        self.assertIn(
            "server/inc/sqlite/preservation.db",
            {record["relativePath"] for record in plan["preimageInventory"]},
        )
        self.assertEqual(before, tree_snapshot(target))
        self.assertEqual({}, tree_snapshot(workspace))

        staged = self.run_harness(
            "profile-migration-stage", target, workspace, "staged-output",
        )
        self.assertEqual(0, staged.returncode, staged.stderr)
        staged_plan = json.loads(staged.stdout)
        for record in staged_plan["stagedExecution"]["stagedOutputs"]:
            output = workspace / "staged-output" / record["relativePath"]
            self.assertEqual(record["size"], output.stat().st_size)
            self.assertEqual(record["sha256"], hashlib.sha256(output.read_bytes()).hexdigest())
            self.assertEqual(0o600, output.stat().st_mode & 0o777)
        migrated = workspace / "staged-output/migration/output/state/current-base.db"
        evidence_path = workspace / (
            "staged-output/migration/output/state/"
            "current-base-migration-evidence.json"
        )
        self.assertNotEqual(database.read_bytes(), migrated.read_bytes())
        evidence = json.loads(evidence_path.read_text())
        self.assertEqual("verified", evidence["status"])
        self.assertTrue(evidence["sourceUnchanged"])
        self.assertEqual(evidence["sourceStateSha256"],
                         evidence["stagedSourceProjectionSha256"])
        with sqlite3.connect(migrated) as migrated_db:
            self.assertEqual(
                "preservation-retro-sqlite-to-current-base-v1",
                migrated_db.execute(
                    "SELECT migration_row_id FROM current_base_migrations"
                ).fetchone()[0],
            )
        self.assertEqual(before, tree_snapshot(target))
        for tamper in ("path", "hash", "mode", "row-schema"):
            with self.subTest(staged_output_tamper=tamper):
                tamper_workspace = self.case_root / f"tamper-{tamper}-transactions"
                tamper_workspace.mkdir()
                result = self.run_harness(
                    "profile-migration-stage-tamper", target, tamper_workspace,
                    f"tampered-{tamper}", failures=tamper,
                )
                self.assertNotEqual(0, result.returncode)
                self.assertIn("CODE=CONVERSION_BLOCKED", result.stderr)
                self.assertEqual(before, tree_snapshot(target))

        for suffix in ("-wal", "-shm"):
            with self.subTest(sqlite_sidecar=suffix):
                refused_target = self.case_root / ("sidecar-target" + suffix)
                shutil.copytree(target, refused_target)
                (refused_target / ("server/inc/sqlite/preservation.db" + suffix)).write_bytes(b"unsafe")
                refused_workspace = self.case_root / ("sidecar-transactions" + suffix)
                refused_workspace.mkdir()
                before_refused = tree_snapshot(refused_target)
                refused = self.run_harness(
                    "profile-migration-stage", refused_target, refused_workspace, "refused",
                )
                self.assertNotEqual(0, refused.returncode)
                self.assertIn("SQLite sidecar state exists", refused.stderr)
                self.assertEqual(before_refused, tree_snapshot(refused_target))
                self.assertEqual({}, tree_snapshot(refused_workspace))

    def test_provider_additional_sqlite_rows_preserve_populated_state(self) -> None:
        for layout, fingerprint in (
            ("core", "373648e4f9192ca29d0dda613b6807724776299e5919d93d8af894289ae67296"),
            ("initialized", "71a3804a2482a78fc96f79c0a3082e38a28d4098748160c9d7bff81ab6bdfe00"),
        ):
            with self.subTest(layout=layout):
                target = self.case_root / (layout + " target #?é")
                shutil.copytree(TARGETS / "preservation-t0", target)
                database = target / "server/inc/sqlite/preservation.db"
                database.parent.mkdir(parents=True)
                if layout == "initialized":
                    shutil.copy2(
                        PROVIDER / "legacy/docs/inherited-openrsc/sqlite-seeds/preservation.db",
                        database,
                    )
                with sqlite3.connect(database) as writable:
                    if layout == "core":
                        writable.executescript(
                            (PROVIDER / "server/database/sqlite/core.sqlite").read_text()
                        )
                    writable.execute(
                        "INSERT INTO players(id,username,pass,salt,creation_date,creation_ip,"
                        "banned,offences,muted,kills,npc_kills,x,y) "
                        "VALUES(913,'editor_fixture','fixture','',0,'0.0.0.0','0',0,'0',0,0,333,444)"
                    )
                    writable.execute(
                        "INSERT INTO curstats(playerID,prayer,magic,woodcut) VALUES(913,31,32,33)"
                    )
                workspace = self.case_root / (layout + " transactions #?é")
                workspace.mkdir()
                before = tree_snapshot(target)
                staged = self.run_harness(
                    "profile-migration-stage", target, workspace, "state-output",
                )
                self.assertEqual(0, staged.returncode, staged.stderr)
                state = workspace / "state-output/migration/output/state"
                evidence = json.loads((state / "current-base-migration-evidence.json").read_text())
                self.assertEqual(
                    f"preservation-{layout}-sqlite-to-current-base-v1", evidence["migrationRowId"]
                )
                self.assertEqual(fingerprint, evidence["sourceSchemaFingerprint"])
                self.assertEqual(evidence["sourceStateSha256"], evidence["stagedSourceProjectionSha256"])
                self.assertEqual(before, tree_snapshot(target))
                with sqlite3.connect(state / "current-base.db") as migrated:
                    self.assertEqual(("editor_fixture", 333, 444), migrated.execute(
                        "SELECT username,x,y FROM players WHERE id=913"
                    ).fetchone())
                    self.assertEqual((31, 32, 33, 1), migrated.execute(
                        "SELECT prayer,magic,woodcut,summoning FROM curstats WHERE playerID=913"
                    ).fetchone())

    def test_descriptor_packed_map_from_unrelated_target_is_refused_zero_write(self) -> None:
        target = self.target("preservation-t0")
        database = target / "server/inc/sqlite/preservation.db"
        database.parent.mkdir(parents=True)
        with sqlite3.connect(database) as writable:
            writable.executescript(
                (PROVIDER / "server/database/sqlite/retro.sqlite").read_text()
            )
        packed_source, packed_report = self.complete_packed_source()
        workspace = self.workspace()
        target_before = tree_snapshot(target)
        source_before = tree_snapshot(packed_source)
        workspace_before = tree_snapshot(workspace)
        previewed = self.run_harness(
            "preview-production-packed", target, workspace, "packed-preview",
            packed_source=packed_source, packed_report=packed_report,
        )
        self.assertNotEqual(0, previewed.returncode)
        self.assertIn("exact target being upgraded", previewed.stderr)
        self.assertEqual(target_before, tree_snapshot(target))
        self.assertEqual(source_before, tree_snapshot(packed_source))
        self.assertEqual(workspace_before, tree_snapshot(workspace))

    def test_exact_target_packed_map_boundary_stages_and_rejects_drift(self) -> None:
        target, packed_source, packed_report = self.complete_packed_target_source()
        target_before = tree_snapshot(target)
        source_before = tree_snapshot(packed_source)
        workspace = self.workspace()
        staged = self.run_harness(
            "map-boundary", target, workspace, "map-positive",
            packed_source=packed_source, packed_report=packed_report,
        )
        self.assertEqual(0, staged.returncode, staged.stderr)
        map_plan = json.loads(staged.stdout)["mapMigration"]
        self.assertTrue(map_plan["packageReady"])
        self.assertGreater(map_plan["terrainCount"], 1)
        self.assertGreater(map_plan["placementCount"], 0)
        self.assertGreater(len(map_plan["outputInventory"]), 8)
        for record in map_plan["outputInventory"]:
            output = workspace / "map-positive" / record["relativePath"]
            self.assertEqual(record["size"], output.stat().st_size)
            self.assertEqual(
                record["sha256"], hashlib.sha256(output.read_bytes()).hexdigest()
            )
            self.assertEqual(0o600, output.stat().st_mode & 0o777)
        self.assertEqual(target_before, tree_snapshot(target))
        self.assertEqual(source_before, tree_snapshot(packed_source))

        for operation in ("map-boundary-tamper", "map-boundary-extra"):
            failure_workspace = self.case_root / (operation + "-transactions")
            failure_workspace.mkdir()
            failed = self.run_harness(
                operation, target, failure_workspace, operation,
                packed_source=packed_source, packed_report=packed_report,
            )
            self.assertNotEqual(0, failed.returncode)
            self.assertIn("CODE=TARGET_DRIFT", failed.stderr)
            self.assertEqual(target_before, tree_snapshot(target))
            self.assertEqual(source_before, tree_snapshot(packed_source))

        drift_source = self.case_root / "map-drift-source"
        shutil.copytree(packed_source, drift_source)
        drift_workspace = self.case_root / "map-drift-transactions"
        drift_workspace.mkdir()
        drift = self.run_harness(
            "map-boundary-source-drift", target, drift_workspace, "map-drift",
            packed_source=drift_source, packed_report=packed_report,
        )
        self.assertNotEqual(0, drift.returncode)
        self.assertIn("CODE=", drift.stderr)
        self.assertEqual(target_before, tree_snapshot(target))

    def test_runtime_launch_inputs_bind_translations_and_converted_map(self) -> None:
        target, source, report = self.complete_packed_target_source()
        (target / "server/conf/local.conf").write_text(
            "server_name: Public É World\nserver_bind_address: 0.0.0.0\n"
            "server_port: 44594\nws_server_port: 44494\n"
            "combat_exp_rate: 3\nskilling_exp_rate: 2\ndb_type: sqlite\n",
            encoding="utf-8",
        )
        database = target / "server/inc/sqlite/preservation.db"
        database.parent.mkdir(parents=True, exist_ok=True)
        with sqlite3.connect(database) as writable:
            writable.executescript((PROVIDER / "server/database/sqlite/retro.sqlite").read_text())
        before_target, before_source = tree_snapshot(target), tree_snapshot(source)
        workspace = self.workspace()
        result = self.run_harness(
            "launch-inputs-stage", target, workspace, "launch-inputs",
            identity=self.layout_identity, catalog=self.layout_catalog,
            packed_source=source, packed_report=report,
        )
        self.assertEqual(0, result.returncode, result.stderr)
        migration = json.loads(result.stdout)
        stage = workspace / "launch-inputs"
        outputs = migration["stagedExecution"]["stagedOutputs"]
        self.assertEqual(4, len(outputs))
        for record in outputs:
            path = stage / record["relativePath"]
            self.assertEqual(record["sha256"], hashlib.sha256(path.read_bytes()).hexdigest())
            self.assertEqual(record["size"], path.stat().st_size)
            self.assertEqual(0o600, path.stat().st_mode & 0o777)
        launch = stage / "migration/output/launch"
        rendered = (launch / "current-base.conf").read_text()
        for line in ("server_name: Public É World", "server_name_welcome: Public É World",
                     "server_bind_address: 0.0.0.0", "server_port: 44594",
                     "ws_server_port: 44494", "combat_exp_rate: 3", "skilling_exp_rate: 2",
                     "db_name: current_base", "want_myworld: false", "want_custom_ui: false"):
            self.assertIn(line, rendered)
        expected = {"server_name": "Public É World", "server_name_welcome": "Public É World",
                    "server_bind_address": "0.0.0.0", "server_port": "44594",
                    "ws_server_port": "44494", "combat_exp_rate": "3", "skilling_exp_rate": "2",
                    "db_name": "current_base", "want_myworld": "false", "want_custom_ui": "false"}
        parsed = subprocess.run([
            "java", "-cp", self.parser_classpath, "com.openrsc.worldbuilder.RuntimeConfigHarness",
            str(launch / "current-base.conf"), *expected,
        ], capture_output=True, text=True, check=True)
        self.assertEqual(expected, json.loads(parsed.stdout))
        self.assertEqual(
            (PROVIDER / "current-platform/runtime/current-base-v1/server/current-base.conf").read_bytes(),
            (stage / "installed/server/current-base.conf").read_bytes(),
        )
        manifest_path = stage / "migration/output/map/conversion/package/manifest.json"
        manifest = json.loads(manifest_path.read_text())
        fingerprint = migration["mapMigration"]["outputPackageFingerprintSha256"]
        for role in ("server", "client"):
            profile = json.loads((launch / f"installed-{role}.json").read_text())
            self.assertEqual(manifest["packageId"], profile["packageId"])
            self.assertEqual(manifest["packageVersion"], profile["packageVersion"])
            self.assertEqual(hashlib.sha256(manifest_path.read_bytes()).hexdigest(), profile["manifestSha256"])
            self.assertEqual(fingerprint, profile["packageFingerprintSha256"])
            self.assertEqual(f"world-builder/packages/{fingerprint}/package", profile["packageRelativePath"])
            self.assertTrue(profile["active"])
        for changed_file in ("current-base.conf", "installed-server.json", "installed-client.json"):
            for tamper in ("bytes", "mode", "missing", "symlink", "hardlink"):
                with self.subTest(changed_file=changed_file, tamper=tamper):
                    changed = self.case_root / (changed_file + "-" + tamper)
                    shutil.copytree(workspace, changed)
                    path = changed / "launch-inputs/migration/output/launch" / changed_file
                    if tamper == "bytes":
                        path.write_bytes(path.read_bytes() + b" ")
                    elif tamper == "mode":
                        path.chmod(0o644)
                    elif tamper == "missing":
                        path.unlink()
                    else:
                        other = changed / "unowned"
                        path.rename(other)
                        if tamper == "symlink":
                            path.symlink_to(other)
                        else:
                            os.link(other, path)
                    before = tree_snapshot(changed)
                    refused = self.run_harness("verify-launch-inputs-stage", target, changed, "launch-inputs")
                    self.assertNotEqual(0, refused.returncode, refused.stdout)
                    self.assertEqual(before, tree_snapshot(changed))
        self.assertEqual(before_target, tree_snapshot(target))
        self.assertEqual(before_source, tree_snapshot(source))
        for failure in ("default-drift", "map-drift", "partial-set", "output-hash", "existing-output"):
            with self.subTest(before_launch_write=failure):
                failed_workspace = self.case_root / failure
                failed_workspace.mkdir()
                refused = self.run_harness(
                    "launch-inputs-stage", target, failed_workspace, "refused", failures=failure,
                    identity=self.layout_identity, catalog=self.layout_catalog,
                    packed_source=source, packed_report=report,
                )
                self.assertNotEqual(0, refused.returncode)
                launch_path = failed_workspace / "refused/migration/output/launch"
                if failure == "existing-output":
                    self.assertEqual(["user.txt"], [p.name for p in launch_path.iterdir()])
                    self.assertEqual(b"*", (launch_path / "user.txt").read_bytes())
                else:
                    self.assertFalse(launch_path.exists())
                self.assertEqual(before_target, tree_snapshot(target))
                self.assertEqual(before_source, tree_snapshot(source))

    def test_runtime_configuration_renderer_refuses_ambiguous_or_partial_inputs(self) -> None:
        target = self.case_root / "render-inputs"
        target.mkdir()
        workspace = self.workspace()
        defaults = (PROVIDER / "current-platform/runtime/current-base-v1/server/current-base.conf").read_text()
        typed = {"serverName": "Public É", "bindAddress": "0.0.0.0", "gamePort": 44594,
                 "websocketPort": 44494, "combatExperienceRate": 3, "skillingExperienceRate": 2,
                 "databaseMigration": {"engine": "sqlite"}, "configurationBlockers": []}
        cases = [("serverName", "name:other"), ("serverName", "line\nserver_port: 1"),
                 ("serverName", "name#comment"), ("serverName", "null"),
                 ("bindAddress", "::1"), ("gamePort", 0), ("gamePort", 44494),
                 ("combatExperienceRate", 101), ("configurationBlockers", ["unknown"]),
                 ("databaseMigration", {"engine": "mariadb"})]
        for index, (field, value) in enumerate(cases):
            with self.subTest(field=field, value=value):
                (target / "typed.json").write_text(json.dumps({**typed, field: value}))
                (target / "defaults.conf").write_text(defaults)
                before = tree_snapshot(target)
                refused = self.run_harness("render-launch-config", target, workspace, f"bad-{index}")
                self.assertNotEqual(0, refused.returncode)
                self.assertIn("CODE=CONVERSION_BLOCKED", refused.stderr)
                self.assertEqual(before, tree_snapshot(target))
                self.assertEqual({}, tree_snapshot(workspace))
        for altered in (defaults + "server_port: 44595\n",
                        defaults.replace("server_port: 43594", "unreviewed_port: 43594")):
            (target / "typed.json").write_text(json.dumps(typed))
            (target / "defaults.conf").write_text(altered)
            refused = self.run_harness("render-launch-config", target, workspace, "bad-defaults")
            self.assertNotEqual(0, refused.returncode)
            self.assertEqual({}, tree_snapshot(workspace))

    def test_generated_state_seal_survives_reload_and_rejects_drift_without_writes(self) -> None:
        target = self.target("preservation-t0")
        (target / "client/cache/landscape.pack").write_bytes(bytes(48 * 48 * 10))
        database = target / "server/inc/sqlite/preservation.db"
        database.parent.mkdir(parents=True)
        with sqlite3.connect(database) as writable:
            writable.executescript((PROVIDER / "server/database/sqlite/retro.sqlite").read_text())
        before_target = tree_snapshot(target)
        workspace = self.workspace()
        txid = "sealed-state"
        staged = self.run_harness(
            "stage-production-packed", target, workspace, txid,
            identity=self.layout_identity, catalog=self.layout_catalog,
        )
        self.assertEqual(0, staged.returncode, staged.stderr)
        planned = json.loads(staged.stdout)
        self.assertFalse(planned["activationAuthorized"])
        self.assertNotIn("generatedStateOutputs", planned)
        receipt = json.loads((workspace / f"{txid}.checkpoint.json").read_text())
        self.assert_receipt_schema(receipt)
        records = receipt["generatedStateOutputs"]
        self.assertEqual(2, len(records))
        for record in records:
            output = workspace / txid / record["relativePath"]
            self.assertEqual(record["sha256"], hashlib.sha256(output.read_bytes()).hexdigest())
            self.assertEqual(record["size"], output.stat().st_size)
            self.assertEqual("0600", record["mode"])
        original_workspace = tree_snapshot(workspace)
        verified = self.run_harness("verify-sealed-stage", target, workspace, txid)
        self.assertEqual(0, verified.returncode, verified.stderr)
        execution = json.loads(verified.stdout)
        self.assertEqual(planned["planFingerprintSha256"], execution["planFingerprintSha256"])
        self.assertEqual(planned["confirmationIdentity"], execution["confirmationIdentity"])
        self.assertNotEqual(planned["verificationEvidenceHash"], execution["verificationEvidenceHash"])
        self.assertEqual(execution["verificationEvidenceHash"],
                         execution["activationLedger"]["verificationEvidenceHash"])
        self.assertEqual(original_workspace, tree_snapshot(workspace))

        # Reload after relocation: evidence retains its historical stageLocation,
        # but exact-byte ownership derives from portable, receipt-bound paths.
        relocated = self.case_root / "relocated #?é"
        shutil.copytree(workspace, relocated)
        relocated_before = tree_snapshot(relocated)
        result = self.run_harness("verify-sealed-stage", target, relocated, txid)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(relocated_before, tree_snapshot(relocated))

        for mutation in (
            "database-bytes", "evidence-bytes", "database-mode", "database-link",
            "database-hardlink", "missing-database", "extra-sidecar", "extra-directory",
            "missing-seal", "empty-seal", "unbound-seal", "unknown-field", "duplicate-path",
            "outside-path", "oversized", "receipt-transaction", "receipt-status",
            "receipt-version", "activation-seal",
            "contradictory-pending", "pending-drops-seal",
        ):
            with self.subTest(mutation=mutation):
                changed = self.case_root / mutation
                shutil.copytree(workspace, changed)
                release = changed / txid
                checkpoint = changed / f"{txid}.checkpoint.json"
                altered = json.loads(checkpoint.read_text())
                db = release / records[1]["relativePath"]
                if mutation == "database-bytes":
                    payload = bytearray(db.read_bytes())
                    payload[200] ^= 1
                    db.write_bytes(payload)
                elif mutation == "evidence-bytes":
                    evidence = release / records[0]["relativePath"]
                    evidence.write_bytes(evidence.read_bytes() + b" ")
                elif mutation == "database-mode":
                    db.chmod(0o644)
                elif mutation in ("database-link", "database-hardlink"):
                    alias = changed / "unowned.db"
                    db.rename(alias)
                    if mutation == "database-link":
                        db.symlink_to(alias)
                    else:
                        os.link(alias, db)
                elif mutation == "missing-database":
                    db.unlink()
                elif mutation == "extra-sidecar":
                    db.with_name(db.name + "-wal").write_bytes(b"unowned")
                elif mutation == "extra-directory":
                    (release / "unowned").mkdir()
                elif mutation == "missing-seal":
                    del altered["generatedStateOutputs"]
                elif mutation == "empty-seal":
                    altered["generatedStateOutputs"] = []
                elif mutation == "unbound-seal":
                    altered["generatedStateOutputs"][1]["sha256"] = "f" * 64
                elif mutation == "unknown-field":
                    altered["generatedStateOutputs"][1]["force"] = True
                elif mutation == "duplicate-path":
                    altered["generatedStateOutputs"][1] = dict(altered["generatedStateOutputs"][0])
                elif mutation == "outside-path":
                    altered["generatedStateOutputs"][1]["relativePath"] = "../user.db"
                elif mutation == "oversized":
                    altered["generatedStateOutputs"][1]["size"] = 1073741825
                elif mutation == "receipt-transaction":
                    altered["transactionId"] = "different-transaction"
                elif mutation == "receipt-status":
                    altered["status"] = "unreviewed-phase"
                elif mutation == "receipt-version":
                    altered["schemaVersion"] = 2
                elif mutation == "activation-seal":
                    marker = release / "activation.json"
                    activation = json.loads(marker.read_text())
                    activation["generatedStateOutputs"][1]["sha256"] = "f" * 64
                    marker.write_text(json.dumps(activation))
                elif mutation in ("contradictory-pending", "pending-drops-seal"):
                    pending = json.loads(checkpoint.read_text())
                    if mutation == "contradictory-pending":
                        pending["generatedStateOutputs"][1]["sha256"] = "f" * 64
                    else:
                        pending["generatedStateOutputs"] = []
                        pending["failureType"] = "backup-complete"
                    (changed / f"{txid}.pending.json").write_text(json.dumps(
                        bind(pending, "receiptFingerprintSha256")))
                if mutation != "unbound-seal":
                    altered = bind(altered, "receiptFingerprintSha256")
                checkpoint.write_text(json.dumps(altered))
                changed_before = tree_snapshot(changed)
                refused = self.run_harness("verify-sealed-stage", target, changed, txid)
                self.assertNotEqual(0, refused.returncode, mutation)
                self.assertIn("linked or non-regular release entry" if mutation == "database-link"
                              else "CODE=", refused.stderr)
                self.assertEqual(changed_before, tree_snapshot(changed))
                self.assertEqual(before_target, tree_snapshot(target))

        # A crash while publishing the first sealed phase receipt may leave the
        # backup receipt authoritative and the complete new receipt temporary.
        interrupted = self.case_root / "first-sealed-receipt-interrupted"
        shutil.copytree(workspace, interrupted)
        backup_receipt = dict(receipt, generatedStateOutputs=[], failureType="backup-complete")
        (interrupted / f"{txid}.checkpoint.json").write_text(json.dumps(
            bind(backup_receipt, "receiptFingerprintSha256")))
        (interrupted / f"{txid}.pending.json").write_text(json.dumps(receipt))
        interrupted_before = tree_snapshot(interrupted)
        result = self.run_harness("verify-sealed-stage", target, interrupted, txid)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(interrupted_before, tree_snapshot(interrupted))
        self.assertEqual(before_target, tree_snapshot(target))

    def test_mariadb_preview_binds_only_loopback_schema_and_environment_references(self) -> None:
        fixture_root = ROOT / "tests/fixtures/preservation-production-migration-v1"
        target = self.case_root / "mariadb-target"
        shutil.copytree(fixture_root / "targets/local-precedence", target)
        local = target / "server/conf/local.conf"
        local.write_text(
            "server_name=Maria Preview\n"
            "db_engine=mariadb\n"
            "db_host=127.0.0.1\n"
            "db_port=3307\n"
            "db_name=preservation_source\n"
            "db_stage_name=current_base_stage\n"
            "db_user_env=CURRENT_BASE_DB_USER\n"
            "db_password_env=CURRENT_BASE_DB_PASSWORD\n",
            encoding="utf-8",
        )
        workspace = self.workspace()
        before = tree_snapshot(target)
        result = self.run_harness("profile-migration", target, workspace, "maria-preview")
        self.assertEqual(0, result.returncode, result.stderr)
        migration = json.loads(result.stdout)
        database = migration["typedConfiguration"]["databaseMigration"]
        self.assertEqual({
            "engine": "mariadb", "host": "127.0.0.1", "port": 3307,
            "sourceSchema": "preservation_source",
            "stageSchema": "current_base_stage",
            "userEnvironmentName": "CURRENT_BASE_DB_USER",
            "passwordEnvironmentName": "CURRENT_BASE_DB_PASSWORD",
        }, database)
        self.assertEqual(
            ["CURRENT_BASE_DB_USER", "CURRENT_BASE_DB_PASSWORD"],
            migration["typedConfiguration"]["externalSecretReferences"],
        )
        binding = migration["stagedExecution"]["providerStateMigration"]
        self.assertEqual("mariadb", binding["engine"])
        self.assertEqual("", binding["sourceRelativePath"])
        self.assertEqual("", binding["sourceSha256"])
        self.assertEqual("", binding["stageRelativePath"])
        self.assertIn(
            "mariadb-external-stage-rollback-not-implemented",
            migration["stagedExecution"]["readinessBlockers"],
        )
        self.assertEqual(before, tree_snapshot(target))
        self.assertEqual({}, tree_snapshot(workspace))

        for index, replacement in enumerate((
            ("db_host=127.0.0.1", "db_host=10.0.0.3"),
            ("db_user_env=CURRENT_BASE_DB_USER", "db_user_env=not-a-safe-reference"),
            ("db_stage_name=current_base_stage", "db_stage_name=preservation_source"),
        )):
            with self.subTest(invalid_mariadb=index):
                local.write_text(local.read_text().replace(*replacement), encoding="utf-8")
                invalid_before = tree_snapshot(target)
                refused = self.run_harness(
                    "profile-migration", target, workspace, f"maria-refused-{index}"
                )
                self.assertNotEqual(0, refused.returncode)
                self.assertIn("CODE=CONVERSION_BLOCKED", refused.stderr)
                self.assertEqual(invalid_before, tree_snapshot(target))
                self.assertEqual({}, tree_snapshot(workspace))
                local.write_text(
                    local.read_text().replace(replacement[1], replacement[0]),
                    encoding="utf-8",
                )

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

    def test_provider_migrator_binding_timeout_output_and_refusal_are_fail_closed(self) -> None:
        cases = (
            ("contract-tamper", self.identity, self.catalog, None,
             "manifest differs from the reviewed inventory"),
            ("tool-tamper", self.identity, self.catalog, None,
             "server runtime differs from the reviewed inventory"),
            ("provider-timeout", self.behavior_identity, self.behavior_catalog,
             "timeout", "bounded timeout"),
            ("provider-oversized", self.behavior_identity, self.behavior_catalog,
             "oversized", "exceeded its output bound"),
            ("provider-schema-refusal", self.identity, self.catalog, None,
             "unsupported or customized sqlite source schema"),
        )
        for index, (failure, identity, catalog, behavior, diagnostic) in enumerate(cases):
            with self.subTest(failure=failure):
                if index:
                    self.case.cleanup(); self.setUp()
                target = self.target("preservation-t0")
                database = target / "server/inc/sqlite/preservation.db"
                database.parent.mkdir(parents=True)
                if failure == "provider-schema-refusal":
                    with sqlite3.connect(database) as connection:
                        connection.execute("CREATE TABLE custom_state(value INTEGER)")
                else:
                    schema = (PROVIDER / "server/database/sqlite/retro.sqlite").read_text()
                    with sqlite3.connect(database) as connection:
                        connection.executescript(schema)
                before = tree_snapshot(target)
                workspace = self.workspace()
                environment = dict(os.environ)
                if behavior:
                    environment["WORLD_BUILDER_FAKE_MIGRATOR_MODE"] = behavior
                failures = failure
                if failure == "provider-oversized":
                    failures = "provider-oversized"
                result = self.run_harness(
                    "profile-migration-provider-refusal", target, workspace,
                    "provider-refusal", failures=failures, identity=identity,
                    catalog=catalog, environment=environment,
                )
                self.assertNotEqual(0, result.returncode)
                self.assertIn(diagnostic, result.stderr)
                stage = workspace / "provider-refusal"
                self.assertFalse((stage / "migration/output/state/current-base.db").exists())
                self.assertFalse((stage / (
                    "migration/output/state/current-base-migration-evidence.json"
                )).exists())
                self.assertFalse((stage / "migration/provider-state-migration-output.log").exists())
                self.assertEqual(before, tree_snapshot(target))

    def test_production_preview_rejects_target_selected_state_paths_zero_write(self) -> None:
        target = self.target("preservation-t0")
        custom = target / "server/inc/sqlite/custom-selected.db"
        custom.parent.mkdir(parents=True)
        custom.write_bytes(b"target-selected-state")
        workspace = self.workspace()
        before = tree_snapshot(target)
        result = self.run_harness(
            "preview-production", target, workspace, "custom-state-path",
            identity=self.candidate_identity, catalog=self.candidate_catalog,
        )
        self.assertNotEqual(0, result.returncode)
        self.assertIn("CODE=CONVERSION_BLOCKED", result.stderr)
        self.assertEqual(before, tree_snapshot(target))
        self.assertEqual({}, tree_snapshot(workspace))

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

    def test_process_halt_after_publication_recovers_pending_receipt(self) -> None:
        target = self.target("managed-n")
        workspace = self.workspace()
        before = tree_snapshot(target)
        txid = "halt-after-publication"
        halted = self.run_harness(
            "apply", target, workspace, txid,
            "halt-after-release-published",
        )
        self.assertEqual(91, halted.returncode)
        receipt = json.loads((workspace / txid / "receipt.json").read_text())
        self.assertEqual("pending", receipt["status"])
        self.assertEqual("release-published", receipt["failureType"])
        receipt_temporary = workspace / txid / ".receipt.json.tmp"
        receipt_temporary.write_bytes((workspace / txid / "receipt.json").read_bytes())
        self.assertNotEqual(before, tree_snapshot(target))
        recovered = self.run_harness("recover", target, workspace, txid)
        self.assertEqual(0, recovered.returncode, recovered.stderr)
        self.assertEqual(before, tree_snapshot(target))
        self.assertFalse(receipt_temporary.exists())
        receipt = json.loads((workspace / txid / "receipt.json").read_text())
        self.assertEqual("rolled-back", receipt["status"])

    def test_post_publication_force_failures_roll_back_exact_target(self) -> None:
        properties = (
            "worldbuilder.currentRuntime.testReleasePostMoveForceFailure",
            "worldbuilder.currentRuntime.testLedgerPostMoveForceFailure",
        )
        for index, property_name in enumerate(properties):
            with self.subTest(property_name=property_name):
                if index:
                    self.case.cleanup(); self.setUp()
                target = self.target("managed-n")
                workspace = self.workspace()
                before = tree_snapshot(target)
                environment = dict(os.environ)
                environment["JAVA_TOOL_OPTIONS"] = "-D" + property_name + "=true"
                result = self.run_harness(
                    "apply", target, workspace, f"post-move-force-{index}",
                    environment=environment,
                )
                self.assertNotEqual(0, result.returncode)
                self.assertEqual(before, tree_snapshot(target))
                receipt = json.loads(
                    (workspace / f"post-move-force-{index}" / "receipt.json").read_text()
                )
                self.assertEqual("rolled-back", receipt["status"])

    def test_recovery_reconciles_exact_final_receipt_temporaries(self) -> None:
        for index, status in enumerate(("successful", "rolled-back")):
            with self.subTest(status=status):
                if index:
                    self.case.cleanup(); self.setUp()
                target = self.target("managed-n")
                workspace = self.workspace()
                before = tree_snapshot(target)
                txid = "final-temp-" + status
                environment = dict(os.environ)
                environment["JAVA_TOOL_OPTIONS"] = (
                    "-Dworldbuilder.currentRuntime.testReceiptHaltStatus=" + status
                )
                failures = "-" if status == "successful" else "after-ledger-activated"
                halted = self.run_harness(
                    "apply", target, workspace, txid, failures=failures,
                    environment=environment,
                )
                self.assertEqual(92, halted.returncode)
                self.assertTrue((workspace / txid / ".receipt.json.tmp").is_file())
                recovered = self.run_harness("recover", target, workspace, txid)
                self.assertEqual(0, recovered.returncode, recovered.stderr)
                receipt = json.loads((workspace / txid / "receipt.json").read_text())
                self.assertEqual(status, receipt["status"])
                self.assertFalse((workspace / txid / ".receipt.json.tmp").exists())
                if status == "rolled-back":
                    self.assertEqual(before, tree_snapshot(target))

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
