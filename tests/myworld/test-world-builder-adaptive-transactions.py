#!/usr/bin/env python3
"""Temporary-fixture coverage for adaptive export/import/recovery/undo."""

import importlib.util
import hashlib
import json
import os
import shutil
import socket
import subprocess
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
SOURCE_ROOT = ROOT / "tools/world-builder/src"
MAIN_CLASS = "com.openrsc.worldbuilder.WorldBuilderCli"
LIFECYCLE_TEST = ROOT / "tests/myworld/test-world-builder-adaptive-project-lifecycle.py"


def load_lifecycle():
    spec = importlib.util.spec_from_file_location("adaptive_lifecycle_fixture", LIFECYCLE_TEST)
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


class AdaptiveTransactionTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.lifecycle = load_lifecycle()
        cls.fixtures = cls.lifecycle.load_discovery_fixtures()
        cls.packed_fixtures = cls.lifecycle.load_packed_fixtures()
        cls.compile_temp = tempfile.TemporaryDirectory(prefix="adaptive-transaction-classes-")
        cls.classes = Path(cls.compile_temp.name) / "classes"
        cls.classes.mkdir()
        sources = sorted(str(path) for path in SOURCE_ROOT.rglob("*.java"))
        subprocess.run(
            ["javac", "-source", "8", "-target", "8", "-d", str(cls.classes), *sources],
            check=True,
            cwd=ROOT,
            capture_output=True,
            text=True,
        )
        allowlist_resource = (
            cls.classes
            / "com/openrsc/worldbuilder/runtime-asset-allowlist-v1.txt"
        )
        allowlist_resource.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(
            ROOT / "release/world-builder-v2/RUNTIME-ASSET-ALLOWLIST.txt",
            allowlist_resource,
        )
        cls.legacy_classes = Path(cls.compile_temp.name) / "legacy-classes"
        cls.legacy_classes.mkdir()
        legacy_source = (
            SOURCE_ROOT
            / "com/openrsc/worldbuilder/WorldBuilderAdaptiveMutationProfile.java"
        ).read_text(encoding="utf-8")
        current_address = (
            "String packageContentAddress = "
            "export.packageValue.nativeInventorySha256;"
        )
        historical_address = (
            "String packageContentAddress = "
            "export.packageValue.fingerprintSha256;"
        )
        if legacy_source.count(current_address) != 1:
            raise AssertionError("historical address fixture requires one prepare address")
        legacy_source = legacy_source.replace(
            current_address, historical_address, 1
        )
        legacy_file = (
            Path(cls.compile_temp.name)
            / "legacy/com/openrsc/worldbuilder/WorldBuilderAdaptiveMutationProfile.java"
        )
        legacy_file.parent.mkdir(parents=True)
        legacy_file.write_text(legacy_source, encoding="utf-8")
        subprocess.run(
            [
                "javac", "-source", "8", "-target", "8",
                "-cp", str(cls.classes), "-d", str(cls.legacy_classes),
                str(legacy_file),
            ],
            check=True,
            cwd=ROOT,
            capture_output=True,
            text=True,
        )
        harness = (
            Path(cls.compile_temp.name)
            / "harness/com/openrsc/worldbuilder/AdaptiveTransactionFailureHarness.java"
        )
        harness.parent.mkdir(parents=True)
        harness.write_text(
            r"""
package com.openrsc.worldbuilder;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.Files;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.net.URI;
import java.util.HashMap;

public final class AdaptiveTransactionFailureHarness {
    private static boolean selected(String specification, String milestone) {
        for (String value : specification.split(",")) {
            if (value.equals(milestone)) return true;
        }
        return false;
    }

    private static WorldBuilderAdaptiveReceipt.State pending(Path project)
        throws Exception {
        for (WorldBuilderAdaptiveReceipt.State receipt :
            WorldBuilderAdaptiveReceipt.readAll(project)) {
            if ("pending".equals(receipt.status())
                || "recovery-required".equals(receipt.status())) return receipt;
        }
        throw new Exception("pending transaction receipt not found");
    }

    public static void main(String[] args) throws Exception {
        final String operation = args[0];
        final String failures = args[1];
        final Path project = Paths.get(args[2]);
        final Path target = Paths.get(args[3]);
        try {
            if ("reserved-stage-copy".equals(operation)) {
                Files.createDirectories(target);
                Path source = target.resolve("source.bin");
                Path stage = target.resolve("stage.bin");
                Files.write(source, new byte[] {1, 2, 3, 4},
                    StandardOpenOption.CREATE_NEW);
                WorldBuilderAdaptiveOwnedFiles owned =
                    new WorldBuilderAdaptiveOwnedFiles();
                owned.reserve(stage);
                Object before = Files.readAttributes(stage,
                    BasicFileAttributes.class).fileKey();
                WorldBuilderAdaptiveOwnedFiles.copyReserved(source, stage);
                owned.seal(stage);
                Object after = Files.readAttributes(stage,
                    BasicFileAttributes.class).fileKey();
                if (before == null || !before.equals(after)) {
                    throw new Exception("reserved stage identity changed");
                }
                owned.cleanupOrThrow();
                if (Files.exists(stage)) throw new Exception(
                    "reserved stage cleanup failed");
            } else if ("project-lock-replacement".equals(operation)) {
                WorldBuilderAdaptiveProjectLock.IdentityObserver observer =
                    new WorldBuilderAdaptiveProjectLock.IdentityObserver() {
                        @Override public void observe(String milestone, Path path)
                            throws java.io.IOException {
                            if (!"after-open".equals(milestone)) return;
                            byte[] bytes = Files.readAllBytes(path);
                            Files.delete(path);
                            Files.write(path, bytes, StandardOpenOption.CREATE_NEW);
                        }
                    };
                try (WorldBuilderAdaptiveProjectLock ignored =
                    WorldBuilderAdaptiveProjectLock.acquire(
                        project, "project-lock-replacement-test", observer)) {
                    // Replacement must prevent acquisition.
                }
            } else if ("project-lock-aba-replacement".equals(operation)) {
                final Path held = project.resolve("run/.world-builder.lock.aba");
                WorldBuilderAdaptiveProjectLock.IdentityObserver observer =
                    new WorldBuilderAdaptiveProjectLock.IdentityObserver() {
                        @Override public void observe(String milestone, Path path)
                            throws java.io.IOException {
                            if ("before-open".equals(milestone)) {
                                byte[] bytes = Files.readAllBytes(path);
                                Files.move(path, held);
                                Files.write(path, bytes, StandardOpenOption.CREATE_NEW);
                            } else if ("after-open".equals(milestone)
                                && Files.exists(held)) {
                                Files.delete(path);
                                Files.move(held, path);
                            }
                        }
                    };
                try (WorldBuilderAdaptiveProjectLock ignored =
                    WorldBuilderAdaptiveProjectLock.acquire(
                        project, "project-lock-aba-test", observer)) {
                    // Opening the transient identity must prevent acquisition.
                }
            } else if ("target-lock-replacement".equals(operation)) {
                WorldBuilderTargetCapability capability = WorldBuilderTargetCapability.read(
                    WorldBuilderReadOnlyTarget.open(target));
                WorldBuilderAdaptiveOfflineLease.IdentityObserver observer =
                    new WorldBuilderAdaptiveOfflineLease.IdentityObserver() {
                        @Override public void observe(String milestone, Path path)
                            throws java.io.IOException {
                            if (!"after-open".equals(milestone)) return;
                            byte[] bytes = Files.readAllBytes(path);
                            Files.delete(path);
                            Files.write(path, bytes, StandardOpenOption.CREATE_NEW);
                        }
                    };
                try (WorldBuilderAdaptiveOfflineLease ignored =
                    WorldBuilderAdaptiveOfflineLease.acquire(target, capability, observer)) {
                    // Replacement must prevent acquisition.
                }
            } else if ("unsupported-atomic-provider".equals(operation)) {
                Path archive = project.resolve("unsupported-provider.zip");
                java.util.Map<String,String> environment = new HashMap<String,String>();
                environment.put("create", "true");
                try (FileSystem zip = FileSystems.newFileSystem(
                    URI.create("jar:" + archive.toUri()), environment)) {
                    Path directory = zip.getPath("/publish");
                    Files.createDirectory(directory);
                    Path source = directory.resolve("source.bin");
                    Files.write(source, new byte[] {3}, StandardOpenOption.CREATE_NEW);
                    WorldBuilderAdaptiveAtomicFiles.moveNew(
                        source, directory.resolve("published.bin"),
                        "atomic-provider-test", "published.bin");
                }
            } else if ("historical-address".equals(operation)) {
                String nativeAddress = "1111111111111111111111111111111111111111111111111111111111111111";
                String legacyAddress = "2222222222222222222222222222222222222222222222222222222222222222";
                String selectedAddress = "native".equals(failures)
                    ? nativeAddress : "legacy".equals(failures)
                        ? legacyAddress
                        : "3333333333333333333333333333333333333333333333333333333333333333";
                java.util.Map<String,Object> plan = new java.util.LinkedHashMap<String,Object>();
                java.util.List<Object> changes = new java.util.ArrayList<Object>();
                for (String key : new String[] {
                    "clientMapRelativePath", "serverMapRelativePath"
                }) {
                    java.util.Map<String,Object> change =
                        new java.util.LinkedHashMap<String,Object>();
                    change.put("key", key);
                    String prefix = key.startsWith("client")
                        ? "Client_Base/world-builder/packages/"
                        : "server/world-builder/packages/";
                    change.put("afterValue", prefix + selectedAddress + "/package");
                    changes.add(change);
                }
                plan.put("configurationChanges", changes);
                System.out.print(
                    WorldBuilderAdaptiveMutationProfile.reconstructedPackageContentAddress(
                        plan, "Client_Base", nativeAddress, legacyAddress));
            } else if ("export".equals(operation)) {
                WorldBuilderAdaptiveExporter.Observer observer =
                    new WorldBuilderAdaptiveExporter.Observer() {
                        @Override public void observe(String milestone, Path path)
                            throws Exception {
                            if ("publish-destination-collision".equals(failures)
                                && "before-publish".equals(milestone)) {
                                java.util.Map<String,Object> manifest =
                                    WorldBuilderJsonDocuments.readObject(
                                        path.resolve("manifest.json"));
                                String fingerprint = WorldBuilderAdaptiveExporter.string(
                                    manifest, "exportFingerprintSha256");
                                String prefix = "export-" + fingerprint.substring(0, 16)
                                    + "-";
                                for (int sequence = 1; sequence <= 999999; sequence++) {
                                    Path collision = path.getParent().resolve(prefix
                                        + String.format(java.util.Locale.ROOT,
                                            "%06d", Integer.valueOf(sequence)));
                                    if (!Files.exists(collision)) {
                                        Files.createDirectory(collision);
                                        Files.write(collision.resolve("external-marker"),
                                            new byte[] {9}, StandardOpenOption.CREATE_NEW);
                                        break;
                                    }
                                }
                            }
                            if ("injected-published-path".equals(failures)
                                && "after-publish".equals(milestone)) {
                                Files.write(path.resolve("external-marker"),
                                    new byte[] {4, 4}, StandardOpenOption.CREATE_NEW);
                            }
                            if (selected(failures, milestone)) {
                                throw new Exception("injected " + milestone);
                            }
                        }
                    };
                new WorldBuilderAdaptiveExporter(observer).export(project);
            } else if ("export-tamper".equals(operation)) {
                WorldBuilderAdaptiveExporter.Observer observer =
                    new WorldBuilderAdaptiveExporter.Observer() {
                        @Override public void observe(String milestone, Path path)
                            throws Exception {
                            if ("after-publish".equals(milestone)) {
                                Files.write(path.resolve(
                                    "package/terrain/creator/lp0/xp0-yp0.raw"),
                                    new byte[] {1}, StandardOpenOption.APPEND);
                            }
                        }
                    };
                new WorldBuilderAdaptiveExporter(observer).export(project);
            } else if ("import".equals(operation)) {
                WorldBuilderAdaptiveImporter.Observer observer =
                    new WorldBuilderAdaptiveImporter.Observer() {
                        @Override public void observe(String milestone, Path path)
                            throws Exception {
                            if ("stage-collision".equals(failures)
                                && "before-first-target-mutation".equals(milestone)) {
                                WorldBuilderAdaptiveReceipt.State receipt = pending(project);
                                for (Object raw : WorldBuilderAdaptiveExporter.array(
                                    receipt.document.get("files"), "files")) {
                                    java.util.Map<String,Object> file =
                                        WorldBuilderAdaptiveExporter.object(raw, "file");
                                    if (!WorldBuilderAdaptiveExporter.string(
                                        file, "role").startsWith("server-package")) continue;
                                    String relative = WorldBuilderAdaptiveExporter.string(
                                        file, "relativePath");
                                    Path destination = target.resolve(relative);
                                    Files.createDirectories(destination.getParent());
                                    Path collision = destination.getParent().resolve("."
                                        + destination.getFileName() + ".stage-"
                                        + receipt.transactionId());
                                    Files.write(collision, new byte[] {7, 7},
                                        StandardOpenOption.CREATE_NEW);
                                    break;
                                }
                            }
                            if ("publication-collision".equals(failures)
                                && "package-file-staged-0000".equals(milestone)) {
                                WorldBuilderAdaptiveReceipt.State receipt = pending(project);
                                for (Object raw : WorldBuilderAdaptiveExporter.array(
                                    receipt.document.get("files"), "files")) {
                                    java.util.Map<String,Object> file =
                                        WorldBuilderAdaptiveExporter.object(raw, "file");
                                    if (!WorldBuilderAdaptiveExporter.string(
                                        file, "role").startsWith("server-package")) continue;
                                    Files.write(target.resolve(
                                        WorldBuilderAdaptiveExporter.string(
                                            file, "relativePath")),
                                        new byte[] {5, 5}, StandardOpenOption.CREATE_NEW);
                                    break;
                                }
                            }
                            if ("replaced-owned-stage".equals(failures)
                                && "package-file-staged-0000".equals(milestone)) {
                                Files.delete(path);
                                Files.write(path, new byte[] {8, 8},
                                    StandardOpenOption.CREATE_NEW);
                                throw new Exception("injected stage replacement");
                            }
                            if ("activation-final-drift".equals(failures)
                                && "before-activation".equals(milestone)) {
                                Files.delete(path);
                                Files.write(path, new byte[] {4, 2, 4, 2},
                                    StandardOpenOption.CREATE_NEW);
                            }
                            if (selected(failures, milestone)) {
                                throw new Exception("injected " + milestone);
                            }
                        }
                    };
                WorldBuilderAdaptiveImporter importer =
                    new WorldBuilderAdaptiveImporter(observer);
                WorldBuilderAdaptiveImporter.Preview preview = importer.preview(
                    project, Paths.get(args[4]), target);
                importer.apply(preview, "IMPORT");
            } else if ("import-stale".equals(operation)) {
                WorldBuilderAdaptiveImporter importer =
                    new WorldBuilderAdaptiveImporter();
                WorldBuilderAdaptiveImporter.Preview preview = importer.preview(
                    project, Paths.get(args[4]), target);
                Files.write(target.resolve("server/evidence/render-assets.bin"),
                    new byte[] {1}, StandardOpenOption.APPEND);
                importer.apply(preview, "IMPORT");
            } else if ("undo".equals(operation)) {
                WorldBuilderAdaptiveUndo.Observer observer =
                    new WorldBuilderAdaptiveUndo.Observer() {
                        @Override public void observe(String milestone, Path path)
                            throws Exception {
                            if ("sibling-after-confirm".equals(failures)
                                && "undo-plan-confirmed".equals(milestone)) {
                                for (WorldBuilderAdaptiveReceipt.State receipt :
                                    WorldBuilderAdaptiveReceipt.readAll(project)) {
                                    if (!"import".equals(receipt.transactionType())
                                        || !"successful".equals(receipt.status())) continue;
                                    for (Object raw : WorldBuilderAdaptiveExporter.array(
                                        receipt.document.get("files"), "files")) {
                                        java.util.Map<String,Object> file =
                                            WorldBuilderAdaptiveExporter.object(raw, "file");
                                        if (!WorldBuilderAdaptiveExporter.string(
                                            file, "role").startsWith("server-package")) continue;
                                        String relative = WorldBuilderAdaptiveExporter.string(
                                            file, "relativePath");
                                        int packageIndex = relative.indexOf("/package/");
                                        Path marker = target.resolve(
                                            relative.substring(0, packageIndex))
                                            .resolve("after-confirm.bin");
                                        Files.write(marker, new byte[] {9, 3},
                                            StandardOpenOption.CREATE_NEW);
                                        break;
                                    }
                                    break;
                                }
                            }
                            if ("assert-safe-order".equals(failures)) {
                                boolean configuration = path.getFileName().toString()
                                    .equals("primary.json");
                                if ("undo-before-0000".equals(milestone)
                                    && !configuration) throw new Exception(
                                        "order assertion: configuration was not first");
                                if ("undo-rollback-before-0000".equals(milestone)
                                    && configuration) throw new Exception(
                                        "order assertion: package rollback was not first");
                                if ("undo-rollback-before-0006".equals(milestone)
                                    && !configuration) throw new Exception(
                                        "order assertion: configuration rollback was not last");
                                if ("undo-after-0001".equals(milestone)) {
                                    throw new Exception("injected safe-order rollback");
                                }
                            }
                            if ("undo-rollback-temp-collision".equals(failures)
                                && "undo-after-0001".equals(milestone)) {
                                WorldBuilderAdaptiveReceipt.State receipt = pending(project);
                                Path collision = path.getParent().resolve("."
                                    + path.getFileName() + ".undo-rollback-"
                                    + receipt.transactionId());
                                Files.write(collision, new byte[] {6, 6},
                                    StandardOpenOption.CREATE_NEW);
                                throw new Exception("injected rollback temp collision");
                            }
                            if ("undo-final-replacement".equals(failures)
                                && "undo-before-0001".equals(milestone)) {
                                Files.delete(path);
                                Files.write(path, new byte[] {3, 1, 4, 1},
                                    StandardOpenOption.CREATE_NEW);
                            }
                            if ("appeared-undo-rollback".equals(failures)
                                && "undo-after-0001".equals(milestone)) {
                                throw new Exception("start appeared-path rollback");
                            }
                            if ("appeared-undo-rollback".equals(failures)
                                && "undo-rollback-before-0000".equals(milestone)
                                && !Files.exists(path)) {
                                Files.write(path, new byte[] {2, 7, 1, 8},
                                    StandardOpenOption.CREATE_NEW);
                            }
                            if (selected(failures, milestone)) {
                                throw new Exception("injected " + milestone);
                            }
                        }
                    };
                WorldBuilderAdaptiveUndo undo = new WorldBuilderAdaptiveUndo(observer);
                WorldBuilderAdaptiveUndo.Preview preview = undo.preview(project, target);
                undo.apply(preview, "UNDO");
            } else if ("undo-stale".equals(operation)) {
                WorldBuilderAdaptiveUndo undo = new WorldBuilderAdaptiveUndo();
                WorldBuilderAdaptiveUndo.Preview preview = undo.preview(project, target);
                Path changed = target.resolve(
                    preview.installedPlan.actions.get(0).destinationRelativePath);
                Files.write(changed, new byte[] {1}, StandardOpenOption.APPEND);
                undo.apply(preview, "UNDO");
            } else if ("recovery".equals(operation)) {
                WorldBuilderAdaptiveRecovery.Observer observer =
                    new WorldBuilderAdaptiveRecovery.Observer() {
                        @Override public void observe(String milestone, Path path)
                            throws Exception {
                            if ("appeared-recovery".equals(failures)
                                && milestone.startsWith("recovery-before-action-")
                                && !Files.exists(path)) {
                                Files.createDirectories(path.getParent());
                                Files.write(path, new byte[] {1, 6, 1, 8},
                                    StandardOpenOption.CREATE_NEW);
                            }
                            if (selected(failures, milestone)) {
                                throw new Exception("injected " + milestone);
                            }
                        }
                    };
                WorldBuilderAdaptiveRecovery recovery =
                    new WorldBuilderAdaptiveRecovery(observer);
                WorldBuilderAdaptiveRecovery.Preview preview =
                    recovery.preview(project, target);
                recovery.apply(preview, "RECOVER");
            } else if ("process-observation".equals(operation)) {
                if ("partial-unreadable".equals(failures)) {
                    WorldBuilderAdaptiveOfflineLease.requireProcessObservationSafe(
                        target, "4242", true, false, null,
                        false, "", false, null);
                } else if ("exited".equals(failures)) {
                    WorldBuilderAdaptiveOfflineLease.requireProcessObservationSafe(
                        target, "4242", false, false, null,
                        false, "", false, null);
                } else if ("readable-command".equals(failures)) {
                    WorldBuilderAdaptiveOfflineLease.requireProcessObservationSafe(
                        target, "4242", true, true,
                        "/usr/bin/unrelated\0--flag".getBytes("UTF-8"),
                        true, "unrelated", true, Paths.get("/"));
                } else if ("command-only".equals(failures)) {
                    WorldBuilderAdaptiveOfflineLease.requireProcessObservationSafe(
                        target, "4242", true, true,
                        "/sbin/init\0splash".getBytes("UTF-8"),
                        true, "systemd", false, null);
                } else if ("java-command-only".equals(failures)) {
                    WorldBuilderAdaptiveOfflineLease.requireProcessObservationSafe(
                        target, "4242", true, true,
                        "/usr/bin/java\0-server\0-cp\0core.jar".getBytes("UTF-8"),
                        true, "java", false, null);
                } else if ("hidden-java-command-only".equals(failures)) {
                    WorldBuilderAdaptiveOfflineLease.requireProcessObservationSafe(
                        target, "4242", true, true,
                        "/usr/bin/runtime\0-server\0-cp\0core.jar".getBytes("UTF-8"),
                        true, "java", false, null);
                } else if ("target-command-only".equals(failures)) {
                    WorldBuilderAdaptiveOfflineLease.requireProcessObservationSafe(
                        target, "4242", true, true,
                        ("/usr/bin/unrelated\0" + target.toString()).getBytes("UTF-8"),
                        true, "unrelated", false, null);
                } else if ("target-java-command".equals(failures)) {
                    WorldBuilderAdaptiveOfflineLease.requireProcessObservationSafe(
                        target, "4242", true, true,
                        ("/usr/bin/java\0-Dtarget=" + target.toString()).getBytes("UTF-8"),
                        true, "java", true, Paths.get("/"));
                } else if ("target-cwd-non-java".equals(failures)) {
                    WorldBuilderAdaptiveOfflineLease.requireProcessObservationSafe(
                        target, "4242", true, true,
                        "/usr/bin/bash\0--norc".getBytes("UTF-8"),
                        true, "bash", true, target);
                } else if ("target-cwd-java".equals(failures)) {
                    WorldBuilderAdaptiveOfflineLease.requireProcessObservationSafe(
                        target, "4242", true, true,
                        "/usr/bin/java\0-cp\0core.jar".getBytes("UTF-8"),
                        true, "java", true, target);
                } else if ("kernel-thread".equals(failures)) {
                    WorldBuilderAdaptiveOfflineLease.requireProcessObservationSafe(
                        target, "4242", true, true, new byte[0],
                        true, "kthreadd", false, null);
                } else {
                    throw new IllegalArgumentException(failures);
                }
            } else {
                throw new IllegalArgumentException(operation);
            }
            System.exit(0);
        } catch (WorldBuilderContractException expected) {
            System.err.println(expected.code() + ": " + expected.getMessage());
            System.exit(3);
        }
    }
}
""".strip()
            + "\n",
            encoding="utf-8",
        )
        subprocess.run(
            [
                "javac",
                "-source",
                "8",
                "-target",
                "8",
                "-cp",
                str(cls.classes),
                "-d",
                str(cls.classes),
                str(harness),
            ],
            check=True,
            cwd=ROOT,
            capture_output=True,
            text=True,
        )

    @classmethod
    def tearDownClass(cls):
        cls.compile_temp.cleanup()

    def run_cli(self, *args):
        return subprocess.run(
            ["java", "-cp", str(self.classes), MAIN_CLASS, *map(str, args)],
            cwd=ROOT,
            text=True,
            capture_output=True,
        )

    def run_legacy_cli(self, *args):
        classpath = os.pathsep.join((str(self.legacy_classes), str(self.classes)))
        return subprocess.run(
            ["java", "-cp", classpath, MAIN_CLASS, *map(str, args)],
            cwd=ROOT,
            text=True,
            capture_output=True,
        )

    def run_legacy_reviewed_apply(self, command, confirmation, *args):
        preview = self.run_legacy_cli(command, *args)
        self.assertEqual(0, preview.returncode, preview.stderr)
        plan = json.loads(preview.stdout)
        return self.run_legacy_cli(
            command,
            *args,
            "--confirm", confirmation,
            "--transaction-id", plan["transactionId"],
            "--plan-sha256", plan["planFingerprintSha256"],
        )

    def run_reviewed_apply(self, command, confirmation, *args, preview=None):
        if preview is None:
            preview = self.run_cli(command, *args)
            self.assertEqual(0, preview.returncode, preview.stderr)
        plan = json.loads(preview.stdout)
        applied = self.run_cli(
            command,
            *args,
            "--confirm",
            confirmation,
            "--transaction-id",
            plan["transactionId"],
            "--plan-sha256",
            plan["planFingerprintSha256"],
        )
        return applied

    def run_cli_with_property(self, property_value, *args):
        return subprocess.run(
            [
                "java",
                f"-Dworldbuilder.adaptive.testUsableBytes={property_value}",
                "-cp",
                str(self.classes),
                MAIN_CLASS,
                *map(str, args),
            ],
            cwd=ROOT,
            text=True,
            capture_output=True,
        )

    def run_cli_with_properties(self, properties, *args):
        return subprocess.run(
            [
                "java",
                *[f"-D{key}={value}" for key, value in properties.items()],
                "-cp",
                str(self.classes),
                MAIN_CLASS,
                *map(str, args),
            ],
            cwd=ROOT,
            text=True,
            capture_output=True,
        )

    def run_cli_input(self, input_value, *args):
        return subprocess.run(
            ["java", "-cp", str(self.classes), MAIN_CLASS, *map(str, args)],
            cwd=ROOT,
            text=True,
            input=input_value,
            capture_output=True,
        )

    def run_failure(self, operation, failures, project, target, export=None):
        command = [
            "java",
            "-cp",
            str(self.classes),
            "com.openrsc.worldbuilder.AdaptiveTransactionFailureHarness",
            operation,
            failures,
            str(project),
            str(target),
        ]
        if export is not None:
            command.append(str(export))
        return subprocess.run(command, cwd=ROOT, text=True, capture_output=True)

    def transaction_artifacts(self, project: Path):
        return (
            self.lifecycle.tree_bytes(project / "backups"),
            self.lifecycle.tree_bytes(project / "receipts"),
        )

    @staticmethod
    def canonical_sha256(value):
        canonical = json.dumps(
            value, ensure_ascii=False, sort_keys=True, separators=(",", ":")
        ).encode("utf-8")
        return hashlib.sha256(canonical).hexdigest()

    @classmethod
    def bind_fingerprint(cls, value, field):
        value[field] = "0" * 64
        value[field] = cls.canonical_sha256(value)

    def assert_no_transaction_stage(self, target: Path):
        for path in target.rglob("*"):
            self.assertNotIn(".stage-", path.name, path)
            self.assertNotIn(".rollback-", path.name, path)
            self.assertNotIn(".undo-", path.name, path)
            self.assertNotIn(".recover-", path.name, path)

    def assert_windows_safe_plan_paths(self, value: dict):
        invalid = set('<>:"|?*')
        paths = [value["backupRootRelativePath"], value["receiptRelativePath"]]
        paths.extend(value.get("createdDirectories", []))
        for action in value["actions"]:
            paths.extend(
                [
                    action["destinationRelativePath"],
                    action["contentRelativePath"],
                    action["backupRelativePath"],
                ]
            )
        for change in value["configurationChanges"]:
            paths.append(change["configurationRelativePath"])
        for verification in (
            value["postWriteVerifications"] + value["rollbackVerifications"]
        ):
            paths.append(verification["relativePath"])
        for relative in filter(None, paths):
            self.assertTrue(invalid.isdisjoint(relative), relative)
            for component in relative.split("/"):
                self.assertFalse(component.endswith((" ", ".")), relative)

    def test_historical_package_addresses_are_reconstructed_from_durable_plan(self):
        for lineage, expected in (
            ("native", "1" * 64),
            ("legacy", "2" * 64),
        ):
            with self.subTest(lineage=lineage):
                selected = self.run_failure(
                    "historical-address", lineage, Path("."), Path(".")
                )
                self.assertEqual(0, selected.returncode, selected.stderr)
                self.assertEqual(expected, selected.stdout)

        refused = self.run_failure(
            "historical-address", "unknown", Path("."), Path(".")
        )
        self.assertEqual(3, refused.returncode, refused.stderr)
        self.assertIn("RECOVERY_REQUIRED", refused.stderr)
        self.assertIn("unrecognized package content-address", refused.stderr)

    def test_current_undo_accepts_exact_historical_package_address_plan(self):
        with tempfile.TemporaryDirectory(prefix="adaptive-historical-address-") as temp:
            target, installation, project, export = self.target_project(Path(temp))
            target_before = self.lifecycle.tree_bytes(target, installation)
            imported = self.run_legacy_reviewed_apply(
                "import-adaptive", "IMPORT", "--project", project,
                "--export", export, "--target-root", target,
            )
            self.assertEqual(0, imported.returncode, imported.stderr)
            legacy_address = json.loads(
                (export / "manifest.json").read_text(encoding="utf-8")
            )["packageFingerprintSha256"]
            native_address = self.native_package_inventory_sha256(export / "package")
            self.assertNotEqual(native_address, legacy_address)
            transaction_id = json.loads(imported.stdout)["transactionId"]
            durable_plan = json.loads(
                (project / "backups" / transaction_id / "mutation-plan.json").read_text(
                    encoding="utf-8"
                )
            )
            installed_paths = {
                change["afterValue"]
                for change in durable_plan["configurationChanges"]
                if change["key"] in {
                    "serverMapRelativePath", "clientMapRelativePath"
                }
            }
            self.assertEqual(2, len(installed_paths))
            self.assertTrue(all(legacy_address in path for path in installed_paths))
            self.assertTrue(all(native_address not in path for path in installed_paths))

            undone = self.run_reviewed_apply(
                "undo-adaptive", "UNDO", "--project", project,
                "--target-root", target,
            )
            self.assertEqual(0, undone.returncode, undone.stderr)
            self.assertEqual(target_before, self.lifecycle.tree_bytes(target, installation))

    @staticmethod
    def native_package_inventory_sha256(package: Path) -> str:
        canonical = bytearray()
        for path in sorted(candidate for candidate in package.rglob("*") if candidate.is_file()):
            relative = path.relative_to(package).as_posix()
            payload = path.read_bytes()
            canonical.extend(relative.encode("utf-8"))
            canonical.append(0)
            canonical.extend(str(len(payload)).encode("ascii"))
            canonical.append(0)
            canonical.extend(hashlib.sha256(payload).hexdigest().encode("ascii"))
            canonical.extend(b"\n")
        return hashlib.sha256(canonical).hexdigest()

    def upgrade_fixture_placements_to_v4(
        self, package: Path, respawn_seconds: int = -1
    ) -> None:
        manifest_path = package / "manifest.json"
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        for declaration in manifest["placementSets"]:
            payload_path = package / declaration["path"]
            payload = json.loads(payload_path.read_text(encoding="utf-8"))
            payload["schemaVersion"] = 4
            payload["encoding"] = "layered-world-placements-v4"
            for npc in payload["npcs"]:
                npc["respawnSeconds"] = respawn_seconds
            self.lifecycle.write_json(payload_path, payload)
            declaration["encoding"] = "layered-world-placements-v4"
            declaration["sha256"] = hashlib.sha256(payload_path.read_bytes()).hexdigest()
        self.lifecycle.write_json(manifest_path, manifest)

    def promote_fixture_terrain_to_v2(self, package: Path, elevation: int) -> None:
        manifest_path = package / "manifest.json"
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        declaration = manifest["terrainSectors"][0]
        payload_path = package / declaration["path"]
        payload = payload_path.read_bytes()
        if declaration["encoding"] == "raw-layered-sector-v1":
            promoted = bytearray()
            for offset in range(0, len(payload), 10):
                promoted.extend(b"\0")
                promoted.extend(payload[offset : offset + 10])
            payload = bytes(promoted)
        payload = bytearray(payload)
        payload[0:2] = elevation.to_bytes(2, "big")
        payload_path.write_bytes(payload)
        declaration["encoding"] = "raw-layered-sector-v2-u16"
        declaration["sha256"] = hashlib.sha256(payload).hexdigest()
        self.lifecycle.write_json(manifest_path, manifest)

    def target_project(
        self, base: Path, representation="layered", install_enabled=True,
        port_evidence=False, offline_evidence=None,
        supported_encodings=(1, 2, 3, 4),
        source_placement_v4=False,
        working_elevation=None,
        working_npc_respawn=None,
    ):
        target = (
            self.fixtures.descriptor_fixture(str(base))
            if representation == "layered"
            else self.packed_fixtures.fixture(base)
        )
        if source_placement_v4:
            self.upgrade_fixture_placements_to_v4(target / "server/maps/active")
            self.upgrade_fixture_placements_to_v4(target / "client/maps/active")
        capability_path = target / "server/world-builder-capabilities.json"
        capability = json.loads(capability_path.read_text(encoding="utf-8"))
        if not install_enabled:
            capability["install"] = {
                "enabled": False,
                "serverRoles": [],
                "clientRoles": [],
                "configurationRoles": [],
                "mutationProfileId": "",
                "offlineEvidence": [],
            }
        else:
            capability["map"]["encodingVersions"] = list(supported_encodings)
            for runtime_path in (
                target / "server/evidence/runtime.json",
                target / "client/evidence/runtime.json",
            ):
                evidence = json.loads(runtime_path.read_text(encoding="utf-8"))
                evidence["encodingVersions"] = list(supported_encodings)
                self.lifecycle.write_json(runtime_path, evidence)
        if install_enabled and offline_evidence is not None:
            capability["install"]["offlineEvidence"] = list(offline_evidence)
        elif install_enabled and not port_evidence:
            capability["install"]["offlineEvidence"] = ["pid-file"]
        self.lifecycle.write_json(capability_path, capability)
        installation = target / "World Builder 2"
        installation.mkdir()
        runtime = self.lifecycle.AdaptiveProjectLifecycleTest.make_runtime(base)
        discovery = self.run_cli("discover-adaptive", "--target-root", target)
        self.assertEqual(0, discovery.returncode, discovery.stderr)
        report = base / "discovery.json"
        report.write_text(discovery.stdout, encoding="utf-8")
        created = self.run_cli(
            "create-project",
            "--installation-root",
            installation,
            "--runtime-root",
            runtime,
            "--target-root",
            target,
            "--discovery-report",
            report,
            "--display-name",
            "Transaction fixture",
            "--port",
            "43883",
            "--confirm",
            "CREATE",
        )
        self.assertEqual(0, created.returncode, created.stderr)
        project = Path(json.loads(created.stdout)["projectRoot"])
        self.lifecycle.AdaptiveProjectLifecycleTest.change_working_terrain(project)
        working_package = project / "working/layered-world/package"
        if working_elevation is not None:
            self.promote_fixture_terrain_to_v2(working_package, working_elevation)
        if working_npc_respawn is not None:
            self.upgrade_fixture_placements_to_v4(
                working_package, working_npc_respawn
            )
        saved = self.run_cli("save-project", "--project", project)
        self.assertEqual(0, saved.returncode, saved.stderr)
        exported = self.run_cli("export-adaptive", "--project", project)
        self.assertEqual(0, exported.returncode, exported.stderr)
        export = Path(json.loads(exported.stdout)["exportDirectory"])
        return target, installation, project, export

    def standalone_project(self, base: Path):
        installation = base / "World Builder 2"
        installation.mkdir()
        runtime = self.lifecycle.AdaptiveProjectLifecycleTest.make_runtime(base)
        discovery_root = base / "ordinary-parent"
        discovery_root.mkdir()
        discovery = self.run_cli("discover-adaptive", "--target-root", discovery_root)
        self.assertEqual(0, discovery.returncode, discovery.stderr)
        report = base / "standalone.json"
        report.write_text(discovery.stdout, encoding="utf-8")
        created = self.run_cli(
            "create-project",
            "--installation-root",
            installation,
            "--runtime-root",
            runtime,
            "--target-root",
            discovery_root,
            "--discovery-report",
            report,
            "--display-name",
            "Standalone",
            "--port",
            "43884",
            "--confirm",
            "CREATE",
        )
        self.assertEqual(0, created.returncode, created.stderr)
        return installation, Path(json.loads(created.stdout)["projectRoot"])

    def test_import_projects_the_lowest_lossless_target_encodings(self):
        with tempfile.TemporaryDirectory(prefix="adaptive-import-old-loader-") as temp:
            target, installation, project, export = self.target_project(
                Path(temp), supported_encodings=(1, 3),
                working_elevation=200, working_npc_respawn=-1,
            )
            before = self.lifecycle.tree_bytes(target, installation)
            preview = self.run_cli(
                "import-adaptive",
                "--project", project,
                "--export", export,
                "--target-root", target,
            )
            self.assertEqual(0, preview.returncode, preview.stderr)
            manifest = json.loads(
                (export / "package/manifest.json").read_text(encoding="utf-8")
            )
            self.assertEqual(
                {"raw-layered-sector-v1"},
                {entry["encoding"] for entry in manifest["terrainSectors"]},
            )
            self.assertEqual(
                {"layered-world-placements-v3"},
                {entry["encoding"] for entry in manifest["placementSets"]},
            )
            self.assertEqual(before, self.lifecycle.tree_bytes(target, installation))

    def test_import_refuses_genuinely_required_new_encodings_before_mutation(self):
        with tempfile.TemporaryDirectory(prefix="adaptive-import-wide-loader-") as temp:
            target, installation, project, export = self.target_project(
                Path(temp), supported_encodings=(1, 3), working_elevation=300
            )
            before = self.lifecycle.tree_bytes(target, installation)
            refused = self.run_cli(
                "import-adaptive", "--project", project, "--export", export,
                "--target-root", target,
            )
            self.assertEqual(3, refused.returncode, refused.stderr)
            self.assertIn("LOADER_INCOMPATIBLE", refused.stderr)
            self.assertIn("encoding version(s) [2]", refused.stderr)
            self.assertEqual(before, self.lifecycle.tree_bytes(target, installation))

        with tempfile.TemporaryDirectory(prefix="adaptive-import-old-placement-") as temp:
            target, installation, project, export = self.target_project(
                Path(temp), supported_encodings=(1, 2, 3), working_npc_respawn=30
            )
            before = self.lifecycle.tree_bytes(target, installation)
            refused = self.run_cli(
                "import-adaptive",
                "--project", project,
                "--export", export,
                "--target-root", target,
            )
            self.assertEqual(3, refused.returncode, refused.stderr)
            self.assertIn("LOADER_INCOMPATIBLE", refused.stderr)
            self.assertIn("encoding version(s) [4]", refused.stderr)
            self.assertEqual(before, self.lifecycle.tree_bytes(target, installation))

    def test_export_preview_import_and_exact_undo(self):
        for representation in ("layered", "packed"):
            with self.subTest(representation=representation), tempfile.TemporaryDirectory(
                prefix=f"adaptive-transaction-{representation}-"
            ) as temp:
                target, installation, project, export = self.target_project(
                    Path(temp), representation
                )
                before = self.lifecycle.tree_bytes(target, installation)
                source_before = self.lifecycle.tree_bytes(project / "source")
                preview = self.run_cli(
                    "import-adaptive",
                    "--project",
                    project,
                    "--export",
                    export,
                    "--target-root",
                    target,
                )
                self.assertEqual(0, preview.returncode, preview.stderr)
                preview_value = json.loads(preview.stdout)
                self.assert_windows_safe_plan_paths(preview_value)
                native_address = self.native_package_inventory_sha256(export / "package")
                configured_paths = {
                    change["afterValue"]
                    for change in preview_value["configurationChanges"]
                    if change["key"] in {
                        "serverMapRelativePath", "clientMapRelativePath"
                    }
                }
                self.assertEqual(2, len(configured_paths))
                self.assertTrue(all(
                    f"/world-builder/packages/{native_address}/package" in path
                    for path in configured_paths
                ))
                self.assertEqual(before, self.lifecycle.tree_bytes(target, installation))
                applied = self.run_reviewed_apply(
                    "import-adaptive",
                    "IMPORT",
                    "--project",
                    project,
                    "--export",
                    export,
                    "--target-root",
                    target,
                    preview=preview,
                )
                self.assertEqual(0, applied.returncode, applied.stderr)
                self.assertIn("administratorAction", applied.stdout)
                self.assertIn("Distribute the exact installed client package", applied.stdout)
                self.assertNotEqual(before, self.lifecycle.tree_bytes(target, installation))
                self.assertEqual(source_before, self.lifecycle.tree_bytes(project / "source"))
                rediscovered = self.run_cli(
                    "discover-adaptive", "--target-root", target
                )
                self.assertEqual(0, rediscovered.returncode, rediscovered.stderr)
                rediscovered_report = json.loads(rediscovered.stdout)
                self.assertEqual("compatible", rediscovered_report["status"])
                self.assertEqual("layered", rediscovered_report["representation"])
                if representation == "packed":
                    rediscovery_report = Path(temp) / "post-import-discovery.json"
                    rediscovery_report.write_text(rediscovered.stdout, encoding="utf-8")
                    recreated = self.run_cli(
                        "create-project",
                        "--installation-root",
                        installation,
                        "--runtime-root",
                        Path(temp) / "builder-runtime",
                        "--target-root",
                        target,
                        "--discovery-report",
                        rediscovery_report,
                        "--display-name",
                        "Post-import layered fixture",
                        "--port",
                        "43884",
                        "--confirm",
                        "CREATE",
                    )
                    self.assertEqual(0, recreated.returncode, recreated.stderr)
                    self.assertEqual("target-layered", json.loads(recreated.stdout)["origin"])
                undo_preview = self.run_cli(
                    "undo-adaptive", "--project", project, "--target-root", target
                )
                self.assertEqual(0, undo_preview.returncode, undo_preview.stderr)
                self.assert_windows_safe_plan_paths(json.loads(undo_preview.stdout))
                undone = self.run_reviewed_apply(
                    "undo-adaptive",
                    "UNDO",
                    "--project",
                    project,
                    "--target-root",
                    target,
                    preview=undo_preview,
                )
                self.assertEqual(0, undone.returncode, undone.stderr)
                self.assertEqual(before, self.lifecycle.tree_bytes(target, installation))
                self.assertEqual(source_before, self.lifecycle.tree_bytes(project / "source"))

    def test_reserved_stage_copy_preserves_identity_and_cleanup(self):
        with tempfile.TemporaryDirectory(prefix="adaptive-reserved-copy-") as temp:
            base = Path(temp)
            project = base / "project"
            target = base / "target"
            project.mkdir()
            copied = self.run_failure(
                "reserved-stage-copy", "none", project, target
            )
            self.assertEqual(0, copied.returncode, copied.stderr)
            self.assertEqual(b"\x01\x02\x03\x04", (target / "source.bin").read_bytes())
            self.assertFalse((target / "stage.bin").exists())

    def test_export_is_portable_deterministic_and_failure_atomic(self):
        with tempfile.TemporaryDirectory(prefix="adaptive-export-portable-") as temp:
            base = Path(temp)
            target, installation, project, _ = self.target_project(base)
            source_before = self.lifecycle.tree_bytes(project / "source")

            # Export is project-local and must not re-read a target that has drifted.
            evidence = target / "server/evidence/render-assets.bin"
            evidence.write_bytes(evidence.read_bytes() + b"target drift")
            target_before = self.lifecycle.tree_bytes(target, installation)
            portable = base / "portable-builder"
            shutil.copytree(installation, portable)
            project_id = json.loads((project / "project.json").read_text())["projectId"]
            portable_project = portable / "projects" / project_id

            local = self.run_cli("export-adaptive", "--project", project)
            copied = self.run_cli("export-adaptive", "--project", portable_project)
            self.assertEqual(0, local.returncode, local.stderr)
            self.assertEqual(0, copied.returncode, copied.stderr)
            local_value = json.loads(local.stdout)
            copied_value = json.loads(copied.stdout)
            self.assertEqual(
                local_value["exportFingerprintSha256"],
                copied_value["exportFingerprintSha256"],
            )
            self.assertEqual(
                self.lifecycle.tree_bytes(Path(local_value["exportDirectory"])),
                self.lifecycle.tree_bytes(Path(copied_value["exportDirectory"])),
            )
            self.assertEqual(source_before, self.lifecycle.tree_bytes(project / "source"))
            self.assertEqual(target_before, self.lifecycle.tree_bytes(target, installation))

            exports_before = self.lifecycle.tree_bytes(project / "exports")
            for milestone in (
                "stage-created", "package-copied", "before-publish", "after-publish"
            ):
                with self.subTest(milestone=milestone):
                    failed = self.run_failure(
                        "export", milestone, project, target
                    )
                    self.assertNotEqual(0, failed.returncode)
                    self.assertEqual(
                        exports_before, self.lifecycle.tree_bytes(project / "exports")
                    )
                    self.assertEqual(
                        source_before, self.lifecycle.tree_bytes(project / "source")
                    )
                    self.assertEqual(
                        target_before, self.lifecycle.tree_bytes(target, installation)
                    )

    def test_changed_after_blocks_undo_before_artifacts(self):
        with tempfile.TemporaryDirectory(prefix="adaptive-changed-after-") as temp:
            target, installation, project, export = self.target_project(Path(temp))
            applied = self.run_reviewed_apply(
                "import-adaptive",
                "IMPORT",
                "--project",
                project,
                "--export",
                export,
                "--target-root",
                target,
            )
            self.assertEqual(0, applied.returncode, applied.stderr)
            receipts_before = sorted(path.name for path in (project / "receipts").iterdir())
            backups_before = sorted(path.name for path in (project / "backups").iterdir())
            config = target / "server/world-builder-configs/primary.json"
            config.write_bytes(config.read_bytes() + b"\n")
            refused = self.run_cli(
                "undo-adaptive", "--project", project, "--target-root", target
            )
            self.assertEqual(3, refused.returncode)
            self.assertIn("changed", refused.stderr.lower())
            self.assertEqual(receipts_before, sorted(path.name for path in (project / "receipts").iterdir()))
            self.assertEqual(backups_before, sorted(path.name for path in (project / "backups").iterdir()))

    def test_confirmation_capability_and_corruption_refuse_before_target_writes(self):
        with tempfile.TemporaryDirectory(prefix="adaptive-import-confirmation-") as temp:
            target, installation, project, export = self.target_project(Path(temp))
            target_before = self.lifecycle.tree_bytes(target, installation)
            artifacts_before = self.transaction_artifacts(project)
            confirmation_preview = self.run_cli(
                "import-adaptive",
                "--project",
                project,
                "--export",
                export,
                "--target-root",
                target,
            )
            self.assertEqual(0, confirmation_preview.returncode, confirmation_preview.stderr)
            refused = self.run_reviewed_apply(
                "import-adaptive",
                "import",
                "--project",
                project,
                "--export",
                export,
                "--target-root",
                target,
                preview=confirmation_preview,
            )
            self.assertEqual(3, refused.returncode, refused.stderr)
            self.assertIn("exact IMPORT", refused.stderr)
            self.assertEqual(target_before, self.lifecycle.tree_bytes(target, installation))
            self.assertEqual(artifacts_before, self.transaction_artifacts(project))

        with tempfile.TemporaryDirectory(prefix="adaptive-import-no-loader-") as temp:
            target, installation, project, export = self.target_project(
                Path(temp), install_enabled=False
            )
            target_before = self.lifecycle.tree_bytes(target, installation)
            artifacts_before = self.transaction_artifacts(project)
            refused = self.run_cli(
                "import-adaptive",
                "--project",
                project,
                "--export",
                export,
                "--target-root",
                target,
            )
            self.assertEqual(3, refused.returncode, refused.stderr)
            self.assertIn("LOADER_INCOMPATIBLE", refused.stderr)
            self.assertIn("server/client install capability", refused.stderr)
            self.assertEqual(target_before, self.lifecycle.tree_bytes(target, installation))
            self.assertEqual(artifacts_before, self.transaction_artifacts(project))

        with tempfile.TemporaryDirectory(prefix="adaptive-import-source-corrupt-") as temp:
            target, installation, project, export = self.target_project(Path(temp))
            target_before = self.lifecycle.tree_bytes(target, installation)
            artifacts_before = self.transaction_artifacts(project)
            source_file = next(
                path
                for path in (project / "source/original").rglob("*")
                if path.is_file()
            )
            source_file.write_bytes(source_file.read_bytes() + b"corrupt")
            refused = self.run_cli(
                "import-adaptive",
                "--project",
                project,
                "--export",
                export,
                "--target-root",
                target,
            )
            self.assertEqual(3, refused.returncode, refused.stderr)
            self.assertIn("SOURCE_CORRUPT", refused.stderr)
            self.assertEqual(target_before, self.lifecycle.tree_bytes(target, installation))
            self.assertEqual(artifacts_before, self.transaction_artifacts(project))

        with tempfile.TemporaryDirectory(prefix="adaptive-import-export-corrupt-") as temp:
            target, installation, project, export = self.target_project(Path(temp))
            target_before = self.lifecycle.tree_bytes(target, installation)
            artifacts_before = self.transaction_artifacts(project)
            terrain = next((export / "package/terrain").rglob("*.raw"))
            terrain.write_bytes(terrain.read_bytes() + b"corrupt")
            refused = self.run_cli(
                "import-adaptive",
                "--project",
                project,
                "--export",
                export,
                "--target-root",
                target,
            )
            self.assertEqual(3, refused.returncode, refused.stderr)
            self.assertIn("SOURCE_CORRUPT", refused.stderr)
            self.assertEqual(target_before, self.lifecycle.tree_bytes(target, installation))
            self.assertEqual(artifacts_before, self.transaction_artifacts(project))

    def test_offline_and_unsafe_target_evidence_refuse_without_side_effects(self):
        with tempfile.TemporaryDirectory(prefix="adaptive-import-pid-") as temp:
            target, installation, project, export = self.target_project(Path(temp))
            pid = target / "server/run/server.pid"
            pid.parent.mkdir(parents=True)
            pid.write_text("12345\n", encoding="utf-8")
            target_before = self.lifecycle.tree_bytes(target, installation)
            artifacts_before = self.transaction_artifacts(project)
            refused = self.run_cli(
                "import-adaptive", "--project", project, "--export", export,
                "--target-root", target
            )
            self.assertEqual(3, refused.returncode, refused.stderr)
            self.assertIn("OFFLINE_REQUIRED", refused.stderr)
            self.assertIn("server.pid", refused.stderr)
            self.assertEqual(target_before, self.lifecycle.tree_bytes(target, installation))
            self.assertEqual(artifacts_before, self.transaction_artifacts(project))

        with tempfile.TemporaryDirectory(prefix="adaptive-import-symlink-") as temp:
            base = Path(temp)
            target, installation, project, export = self.target_project(base)
            outside = base / "outside"
            outside.mkdir()
            (target / "server/world-builder").symlink_to(
                outside, target_is_directory=True
            )
            target_before = self.lifecycle.tree_bytes(target, installation)
            artifacts_before = self.transaction_artifacts(project)
            refused = self.run_cli(
                "import-adaptive", "--project", project, "--export", export,
                "--target-root", target
            )
            self.assertEqual(3, refused.returncode, refused.stderr)
            self.assertIn("UNSAFE_PATH", refused.stderr)
            self.assertEqual({}, self.lifecycle.tree_bytes(outside))
            self.assertEqual(target_before, self.lifecycle.tree_bytes(target, installation))
            self.assertEqual(artifacts_before, self.transaction_artifacts(project))

        if hasattr(os, "link"):
            with tempfile.TemporaryDirectory(prefix="adaptive-import-hardlink-") as temp:
                base = Path(temp)
                target, installation, project, export = self.target_project(base)
                config = target / "server/world-builder-configs/primary.json"
                alias = base / "configuration-alias.json"
                shutil.copy2(config, alias)
                config.unlink()
                os.link(alias, config)
                target_before = self.lifecycle.tree_bytes(target, installation)
                artifacts_before = self.transaction_artifacts(project)
                refused = self.run_cli(
                    "import-adaptive", "--project", project, "--export", export,
                    "--target-root", target
                )
                self.assertEqual(3, refused.returncode, refused.stderr)
                self.assertIn("UNSAFE_PATH", refused.stderr)
                self.assertEqual(target_before, self.lifecycle.tree_bytes(target, installation))
                self.assertEqual(artifacts_before, self.transaction_artifacts(project))

    def test_standalone_export_and_immediate_no_target_refusals(self):
        with tempfile.TemporaryDirectory(prefix="adaptive-standalone-transaction-") as temp:
            base = Path(temp)
            installation, project = self.standalone_project(base)
            first = self.run_cli("export-adaptive", "--project", project)
            second = self.run_cli("export-adaptive", "--project", project)
            self.assertEqual(0, first.returncode, first.stderr)
            self.assertEqual(0, second.returncode, second.stderr)
            first_value = json.loads(first.stdout)
            second_value = json.loads(second.stdout)
            self.assertEqual(
                first_value["exportFingerprintSha256"],
                second_value["exportFingerprintSha256"],
            )
            self.assertNotEqual(first_value["exportDirectory"], second_value["exportDirectory"])
            forbidden_target = base / "must-not-be-created"
            receipts_before = self.lifecycle.tree_bytes(project / "receipts")
            backups_before = self.lifecycle.tree_bytes(project / "backups")
            for command in ("import-adaptive", "undo-adaptive", "recover-adaptive"):
                arguments = [command, "--project", project]
                if command == "import-adaptive":
                    arguments.extend(["--export", first_value["exportDirectory"]])
                arguments.extend(["--target-root", forbidden_target])
                refused = self.run_cli(*arguments)
                self.assertEqual(3, refused.returncode, refused.stderr)
                self.assertIn("NO_TARGET", refused.stderr)
                self.assertFalse(forbidden_target.exists())
                self.assertEqual(receipts_before, self.lifecycle.tree_bytes(project / "receipts"))
                self.assertEqual(backups_before, self.lifecycle.tree_bytes(project / "backups"))

            active = self.run_cli(
                "import-active-adaptive", "--installation-root", installation
            )
            self.assertEqual(3, active.returncode, active.stderr)
            self.assertIn("NO_TARGET", active.stderr)

    def test_stale_import_and_undo_previews_fail_closed(self):
        with tempfile.TemporaryDirectory(prefix="adaptive-import-stale-preview-") as temp:
            target, installation, project, export = self.target_project(Path(temp))
            target_before = self.lifecycle.tree_bytes(target, installation)
            artifacts_before = self.transaction_artifacts(project)
            refused = self.run_failure(
                "import-stale", "none", project, target, export
            )
            self.assertEqual(3, refused.returncode, refused.stderr)
            self.assertIn("TARGET_DRIFT", refused.stderr)
            target_after = self.lifecycle.tree_bytes(target, installation)
            changed = sorted(
                path
                for path in set(target_before) | set(target_after)
                if target_before.get(path) != target_after.get(path)
            )
            self.assertEqual(["server/evidence/render-assets.bin"], changed)
            self.assertEqual(artifacts_before, self.transaction_artifacts(project))

        with tempfile.TemporaryDirectory(prefix="adaptive-undo-stale-preview-") as temp:
            target, installation, project, export = self.target_project(Path(temp))
            applied = self.run_reviewed_apply(
                "import-adaptive", "IMPORT", "--project", project, "--export", export,
                "--target-root", target
            )
            self.assertEqual(0, applied.returncode, applied.stderr)
            installed_before = self.lifecycle.tree_bytes(target, installation)
            artifacts_before = self.transaction_artifacts(project)
            refused = self.run_failure("undo-stale", "none", project, target)
            self.assertEqual(3, refused.returncode, refused.stderr)
            self.assertIn("TARGET_DRIFT", refused.stderr)
            installed_after = self.lifecycle.tree_bytes(target, installation)
            changed = sorted(
                path
                for path in set(installed_before) | set(installed_after)
                if installed_before.get(path) != installed_after.get(path)
            )
            self.assertEqual(1, len(changed), changed)
            self.assertIn("world-builder/packages", changed[0])
            self.assertEqual(artifacts_before, self.transaction_artifacts(project))

    def test_partial_import_rolls_back_exactly(self):
        milestones = [
            "plan-confirmed",
            "backups-verified",
            "pending-receipt-written",
            "before-first-target-mutation",
            *[f"package-file-staged-{index:04d}" for index in range(6)],
            *[f"package-file-published-{index:04d}" for index in range(6)],
            "activation-staged",
            "before-activation",
            "activation-published",
            "post-write-verified",
            "before-success-receipt",
        ]
        for milestone in milestones:
            with self.subTest(milestone=milestone), tempfile.TemporaryDirectory(
                prefix="adaptive-import-rollback-"
            ) as temp:
                target, installation, project, export = self.target_project(Path(temp))
                before = self.lifecycle.tree_bytes(target, installation)
                source_before = self.lifecycle.tree_bytes(project / "source")
                failed = self.run_failure("import", milestone, project, target, export)
                self.assertEqual(3, failed.returncode, failed.stderr)
                self.assertEqual(before, self.lifecycle.tree_bytes(target, installation))
                self.assertEqual(source_before, self.lifecycle.tree_bytes(project / "source"))
                statuses = [
                    json.loads(path.read_text(encoding="utf-8"))["status"]
                    for path in (project / "receipts").glob("*.json")
                ]
                if milestone == "plan-confirmed":
                    self.assertEqual([], statuses)
                else:
                    expected = (
                        "failed-no-change"
                        if milestone in (
                            "backups-verified",
                            "pending-receipt-written",
                            "before-first-target-mutation",
                        )
                        else "rolled-back"
                    )
                    self.assertEqual([expected], statuses)
                self.assert_no_transaction_stage(target)

    def test_export_publication_failures_leave_no_partial_output(self):
        with tempfile.TemporaryDirectory(prefix="adaptive-export-failures-") as temp:
            target, installation, project, export = self.target_project(Path(temp))
            target_before = self.lifecycle.tree_bytes(target, installation)
            exports_before = self.lifecycle.tree_bytes(project / "exports")
            for milestone in (
                "stage-created", "package-copied", "before-publish", "after-publish"
            ):
                with self.subTest(milestone=milestone):
                    failed = self.run_failure("export", milestone, project, target)
                    self.assertEqual(3, failed.returncode, failed.stderr)
                    self.assertEqual(exports_before, self.lifecycle.tree_bytes(project / "exports"))
                    self.assertEqual(target_before, self.lifecycle.tree_bytes(target, installation))
                    self.assertFalse(list((project / "exports").glob(".staging-*")))
            tampered = self.run_failure("export-tamper", "none", project, target)
            self.assertEqual(3, tampered.returncode, tampered.stderr)
            self.assertEqual(exports_before, self.lifecycle.tree_bytes(project / "exports"))
            self.assertEqual(target_before, self.lifecycle.tree_bytes(target, installation))

    def test_free_space_and_force_refuse_before_artifacts(self):
        with tempfile.TemporaryDirectory(prefix="adaptive-import-preflight-") as temp:
            target, installation, project, export = self.target_project(Path(temp))
            before = self.lifecycle.tree_bytes(target, installation)
            receipts_before = self.lifecycle.tree_bytes(project / "receipts")
            backups_before = self.lifecycle.tree_bytes(project / "backups")
            refused = self.run_cli_with_property(
                "0",
                "import-adaptive",
                "--project",
                project,
                "--export",
                export,
                "--target-root",
                target,
            )
            self.assertEqual(3, refused.returncode, refused.stderr)
            self.assertIn("space", refused.stderr.lower())
            forced = self.run_cli(
                "import-adaptive",
                "--project",
                project,
                "--export",
                export,
                "--target-root",
                target,
                "--force",
            )
            self.assertEqual(2, forced.returncode)
            self.assertEqual(before, self.lifecycle.tree_bytes(target, installation))
            self.assertEqual(receipts_before, self.lifecycle.tree_bytes(project / "receipts"))
            self.assertEqual(backups_before, self.lifecycle.tree_bytes(project / "backups"))

            preview = self.run_cli(
                "import-adaptive", "--project", project, "--export", export,
                "--target-root", target,
            )
            self.assertEqual(0, preview.returncode, preview.stderr)
            plan = json.loads(preview.stdout)
            unsupported_durability = self.run_cli_with_properties(
                {"worldbuilder.adaptive.testDirectoryForceUnsupported": "true"},
                "import-adaptive", "--project", project, "--export", export,
                "--target-root", target, "--confirm", "IMPORT",
                "--transaction-id", plan["transactionId"],
                "--plan-sha256", plan["planFingerprintSha256"],
            )
            self.assertEqual(3, unsupported_durability.returncode,
                             unsupported_durability.stderr)
            self.assertIn("durably order", unsupported_durability.stderr)
            self.assertEqual(before, self.lifecycle.tree_bytes(target, installation))
            self.assertEqual(receipts_before, self.lifecycle.tree_bytes(project / "receipts"))
            self.assertEqual(backups_before, self.lifecycle.tree_bytes(project / "backups"))

            for command, token in (("undo-adaptive", "UNDO"), ("recover-adaptive", "RECOVER")):
                forced = self.run_cli(
                    command,
                    "--project",
                    project,
                    "--target-root",
                    target,
                    "--confirm",
                    token,
                    "--force",
                )
                self.assertEqual(2, forced.returncode)
                self.assertEqual(before, self.lifecycle.tree_bytes(target, installation))
                self.assertEqual(
                    receipts_before, self.lifecycle.tree_bytes(project / "receipts")
                )
                self.assertEqual(
                    backups_before, self.lifecycle.tree_bytes(project / "backups")
                )

    def test_receipt_and_backup_tampering_block_undo(self):
        for evidence in ("receipt", "backup"):
            with self.subTest(evidence=evidence), tempfile.TemporaryDirectory(
                prefix=f"adaptive-undo-{evidence}-tamper-"
            ) as temp:
                target, installation, project, export = self.target_project(Path(temp))
                applied = self.run_reviewed_apply(
                    "import-adaptive", "IMPORT", "--project", project,
                    "--export", export, "--target-root", target
                )
                self.assertEqual(0, applied.returncode, applied.stderr)
                receipt_path = next((project / "receipts").glob("*.json"))
                receipt = json.loads(receipt_path.read_text(encoding="utf-8"))
                if evidence == "receipt":
                    receipt["capabilityId"] = "tampered-capability-v1"
                    self.lifecycle.write_json(receipt_path, receipt)
                else:
                    backup = (
                        project
                        / "backups"
                        / receipt["transactionId"]
                        / "before/server/world-builder-configs/primary.json"
                    )
                    backup.write_bytes(backup.read_bytes() + b"\n")
                installed = self.lifecycle.tree_bytes(target, installation)
                artifacts = self.transaction_artifacts(project)
                refused = self.run_cli(
                    "undo-adaptive", "--project", project, "--target-root", target
                )
                self.assertEqual(3, refused.returncode, refused.stderr)
                self.assertEqual(
                    installed,
                    self.lifecycle.tree_bytes(target, installation),
                    refused.stderr,
                )
                self.assertEqual(artifacts, self.transaction_artifacts(project))

    def test_recovery_restores_failed_import_rollback(self):
        with tempfile.TemporaryDirectory(prefix="adaptive-import-recovery-") as temp:
            target, installation, project, export = self.target_project(Path(temp))
            before = self.lifecycle.tree_bytes(target, installation)
            failed = self.run_failure(
                "import",
                "package-file-published-0000,rollback-before-0006",
                project,
                target,
                export,
            )
            self.assertEqual(3, failed.returncode, failed.stderr)
            self.assertNotEqual(before, self.lifecycle.tree_bytes(target, installation))
            preview = self.run_cli(
                "recover-adaptive", "--project", project, "--target-root", target
            )
            self.assertEqual(0, preview.returncode, preview.stderr)
            recovered = self.run_reviewed_apply(
                "recover-adaptive",
                "RECOVER",
                "--project",
                project,
                "--target-root",
                target,
                preview=preview,
            )
            self.assertEqual(0, recovered.returncode, recovered.stderr)
            self.assertEqual(before, self.lifecycle.tree_bytes(target, installation))
            statuses = [
                json.loads(path.read_text(encoding="utf-8"))["status"]
                for path in (project / "receipts").glob("*.json")
            ]
            self.assertNotIn("pending", statuses)
            self.assertNotIn("recovery-required", statuses)

    def test_recovery_finalizes_file_complete_failure_without_orphan_artifacts(self):
        with tempfile.TemporaryDirectory(prefix="adaptive-import-finalize-") as temp:
            base = Path(temp)
            target, installation, project, export = self.target_project(base)
            before = self.lifecycle.tree_bytes(target, installation)
            failed = self.run_failure(
                "import",
                "package-file-published-0000,rollback-after-0006",
                project,
                target,
                export,
            )
            self.assertEqual(3, failed.returncode, failed.stderr)
            uncertain = self.lifecycle.tree_bytes(target, installation)
            self.assertNotEqual(before, uncertain)
            self.assertEqual(
                {path: value for path, value in before.items() if value[0] == "file"},
                {path: value for path, value in uncertain.items() if value[0] == "file"},
            )
            original_backups = sorted(path.name for path in (project / "backups").iterdir())
            original_receipts = sorted(path.name for path in (project / "receipts").iterdir())
            self.assertEqual(1, len(original_backups))
            self.assertEqual(1, len(original_receipts))
            original_receipt = project / "receipts" / original_receipts[0]
            self.assertEqual(
                "recovery-required",
                json.loads(original_receipt.read_text(encoding="utf-8"))["status"],
            )

            project_id = json.loads(
                (project / "project.json").read_text(encoding="utf-8")
            )["projectId"]
            interrupted_target = base / "interrupted-target"
            shutil.copytree(target, interrupted_target)
            interrupted_installation = interrupted_target / "World Builder 2"
            interrupted_project = (
                interrupted_installation / "projects" / project_id
            )
            interrupted = self.run_failure(
                "recovery",
                "recovery-after-directory-cleanup",
                interrupted_project,
                interrupted_target,
            )
            self.assertEqual(3, interrupted.returncode, interrupted.stderr)
            self.assertEqual(
                uncertain,
                self.lifecycle.tree_bytes(
                    interrupted_target, interrupted_installation
                ),
            )
            self.assertEqual(
                self.transaction_artifacts(project),
                self.transaction_artifacts(interrupted_project),
            )

            preview = self.run_cli(
                "recover-adaptive", "--project", project, "--target-root", target
            )
            self.assertEqual(0, preview.returncode, preview.stderr)
            self.assertEqual([], json.loads(preview.stdout)["actions"])
            recovered = self.run_reviewed_apply(
                "recover-adaptive",
                "RECOVER",
                "--project",
                project,
                "--target-root",
                target,
                preview=preview,
            )
            self.assertEqual(0, recovered.returncode, recovered.stderr)
            self.assertEqual(before, self.lifecycle.tree_bytes(target, installation))
            self.assertEqual(
                original_backups,
                sorted(path.name for path in (project / "backups").iterdir()),
            )
            self.assertEqual(
                original_receipts,
                sorted(path.name for path in (project / "receipts").iterdir()),
            )
            self.assertEqual(
                "rolled-back",
                json.loads(original_receipt.read_text(encoding="utf-8"))["status"],
            )
            self.assert_no_transaction_stage(target)

    def test_every_recovery_boundary_rolls_back_to_uncertain_start(self):
        milestones = (
            "recovery-plan-confirmed",
            "recovery-evidence-written",
            "recovery-action-applied-0000",
            "recovery-before-directory-cleanup",
            "recovery-after-directory-cleanup",
            "recovery-before-success-receipt",
            "recovery-before-original-finalize",
        )
        with tempfile.TemporaryDirectory(prefix="adaptive-recovery-boundaries-") as temp:
            base = Path(temp)
            template = base / "template"
            template.mkdir()
            target, installation, project, export = self.target_project(template)
            failed_import = self.run_failure(
                "import",
                "package-file-published-0000,rollback-before-0006",
                project,
                target,
                export,
            )
            self.assertEqual(3, failed_import.returncode, failed_import.stderr)
            project_id = json.loads((project / "project.json").read_text())["projectId"]
            uncertain = self.lifecycle.tree_bytes(target, installation)
            source = self.lifecycle.tree_bytes(project / "source")

            for index, milestone in enumerate(milestones):
                with self.subTest(milestone=milestone):
                    copied_target = base / f"target-{index:02d}"
                    shutil.copytree(target, copied_target)
                    copied_installation = copied_target / "World Builder 2"
                    copied_project = copied_installation / "projects" / project_id
                    failed = self.run_failure(
                        "recovery", milestone, copied_project, copied_target
                    )
                    self.assertEqual(3, failed.returncode, failed.stderr)
                    self.assertEqual(
                        uncertain,
                        self.lifecycle.tree_bytes(copied_target, copied_installation),
                    )
                    self.assertEqual(
                        source, self.lifecycle.tree_bytes(copied_project / "source")
                    )
                    statuses = [
                        json.loads(path.read_text(encoding="utf-8"))["status"]
                        for path in (copied_project / "receipts").glob("*.json")
                    ]
                    self.assertIn("recovery-required", statuses)
                    self.assertNotIn("pending", statuses)
                    if milestone != "recovery-plan-confirmed":
                        self.assertTrue(
                            "failed-no-change" in statuses or "rolled-back" in statuses,
                            statuses,
                        )
                    self.assert_no_transaction_stage(copied_target)

    def test_partial_undo_rolls_back_to_installed_state(self):
        milestones = [
            "undo-plan-confirmed",
            "undo-pending-receipt",
            *[f"undo-before-{index:04d}" for index in range(7)],
            *[f"undo-after-{index:04d}" for index in range(7)],
            "undo-before-directory-cleanup",
            "undo-after-directory-cleanup",
            "undo-before-success-receipt",
        ]
        for milestone in milestones:
            with self.subTest(milestone=milestone), tempfile.TemporaryDirectory(
                prefix="adaptive-undo-rollback-"
            ) as temp:
                target, installation, project, export = self.target_project(Path(temp))
                applied = self.run_reviewed_apply(
                    "import-adaptive",
                    "IMPORT",
                    "--project",
                    project,
                    "--export",
                    export,
                    "--target-root",
                    target,
                )
                self.assertEqual(0, applied.returncode, applied.stderr)
                installed = self.lifecycle.tree_bytes(target, installation)
                source_before = self.lifecycle.tree_bytes(project / "source")
                failed = self.run_failure("undo", milestone, project, target)
                self.assertEqual(3, failed.returncode, failed.stderr)
                self.assertEqual(
                    installed,
                    self.lifecycle.tree_bytes(target, installation),
                    failed.stderr,
                )
                self.assertEqual(source_before, self.lifecycle.tree_bytes(project / "source"))
                statuses = sorted(
                    json.loads(path.read_text(encoding="utf-8"))["status"]
                    for path in (project / "receipts").glob("*.json")
                )
                if milestone == "undo-plan-confirmed":
                    self.assertEqual(["successful"], statuses)
                else:
                    expected = (
                        "failed-no-change"
                        if milestone in ("undo-pending-receipt", "undo-before-0000")
                        else "rolled-back"
                    )
                    self.assertEqual([expected, "successful"], statuses)
                self.assert_no_transaction_stage(target)

    def test_recovery_restores_failed_undo_rollback(self):
        with tempfile.TemporaryDirectory(prefix="adaptive-undo-recovery-") as temp:
            target, installation, project, export = self.target_project(Path(temp))
            applied = self.run_reviewed_apply(
                "import-adaptive",
                "IMPORT",
                "--project",
                project,
                "--export",
                export,
                "--target-root",
                target,
            )
            self.assertEqual(0, applied.returncode, applied.stderr)
            installed = self.lifecycle.tree_bytes(target, installation)
            failed = self.run_failure(
                "undo",
                "undo-after-0000,undo-rollback-before-0006",
                project,
                target,
            )
            self.assertEqual(3, failed.returncode, failed.stderr)
            self.assertNotEqual(installed, self.lifecycle.tree_bytes(target, installation))
            recovered = self.run_reviewed_apply(
                "recover-adaptive",
                "RECOVER",
                "--project",
                project,
                "--target-root",
                target,
            )
            self.assertEqual(0, recovered.returncode, recovered.stderr)
            self.assertEqual(installed, self.lifecycle.tree_bytes(target, installation))
            preview = self.run_cli(
                "undo-adaptive", "--project", project, "--target-root", target
            )
            self.assertEqual(0, preview.returncode, preview.stderr)

    def test_reviewed_plan_binding_stdout_and_literal_active_confirmation(self):
        invalid_inputs = ("import\n", " IMPORT\n", "IMPORT \n", "\n", "")
        with tempfile.TemporaryDirectory(prefix="adaptive-reviewed-binding-") as temp:
            target, installation, project, export = self.target_project(Path(temp))
            target_before = self.lifecycle.tree_bytes(target, installation)
            artifacts_before = self.transaction_artifacts(project)

            for response in invalid_inputs:
                refused = self.run_cli_input(
                    response,
                    "import-active-adaptive",
                    "--installation-root",
                    installation,
                )
                self.assertEqual(0, refused.returncode, refused.stderr)
                self.assertEqual("", refused.stdout)
                self.assertIn("cancelled", refused.stderr.lower())
                self.assertEqual(
                    target_before, self.lifecycle.tree_bytes(target, installation)
                )
                self.assertEqual(artifacts_before, self.transaction_artifacts(project))

            preview = self.run_cli(
                "import-adaptive",
                "--project",
                project,
                "--export",
                export,
                "--target-root",
                target,
            )
            self.assertEqual(0, preview.returncode, preview.stderr)
            plan = json.loads(preview.stdout)
            missing_binding = self.run_cli(
                "import-adaptive",
                "--project",
                project,
                "--export",
                export,
                "--target-root",
                target,
                "--confirm",
                "IMPORT",
            )
            self.assertEqual(2, missing_binding.returncode, missing_binding.stderr)
            mismatch = self.run_cli(
                "import-adaptive",
                "--project",
                project,
                "--export",
                export,
                "--target-root",
                target,
                "--confirm",
                "IMPORT",
                "--transaction-id",
                plan["transactionId"],
                "--plan-sha256",
                "0" * 64,
            )
            self.assertEqual(3, mismatch.returncode, mismatch.stderr)
            self.assertEqual("", mismatch.stdout)
            self.assertEqual(target_before, self.lifecycle.tree_bytes(target, installation))
            self.assertEqual(artifacts_before, self.transaction_artifacts(project))

            applied = self.run_reviewed_apply(
                "import-adaptive",
                "IMPORT",
                "--project",
                project,
                "--export",
                export,
                "--target-root",
                target,
                preview=preview,
            )
            self.assertEqual(0, applied.returncode, applied.stderr)
            result = json.loads(applied.stdout)
            self.assertEqual(plan["transactionId"], result["transactionId"])
            self.assertEqual("successful", result["status"])

            installed = self.lifecycle.tree_bytes(target, installation)
            installed_artifacts = self.transaction_artifacts(project)
            undo_preview = self.run_cli(
                "undo-adaptive", "--project", project, "--target-root", target
            )
            self.assertEqual(0, undo_preview.returncode, undo_preview.stderr)
            undo_plan = json.loads(undo_preview.stdout)
            undo_missing = self.run_cli(
                "undo-adaptive", "--project", project, "--target-root", target,
                "--confirm", "UNDO"
            )
            self.assertEqual(2, undo_missing.returncode, undo_missing.stderr)
            undo_mismatch = self.run_cli(
                "undo-adaptive", "--project", project, "--target-root", target,
                "--confirm", "UNDO", "--transaction-id", undo_plan["transactionId"],
                "--plan-sha256", "0" * 64
            )
            self.assertEqual(3, undo_mismatch.returncode, undo_mismatch.stderr)
            self.assertEqual("", undo_mismatch.stdout)
            self.assertEqual(installed, self.lifecycle.tree_bytes(target, installation))
            self.assertEqual(installed_artifacts, self.transaction_artifacts(project))
            for response in ("undo\n", " UNDO\n", "UNDO \n", "\n", ""):
                refused = self.run_cli_input(
                    response, "undo-active-adaptive", "--installation-root", installation
                )
                self.assertEqual(0, refused.returncode, refused.stderr)
                self.assertEqual("", refused.stdout)
                self.assertIn("cancelled", refused.stderr.lower())
                self.assertEqual(installed, self.lifecycle.tree_bytes(target, installation))
                self.assertEqual(installed_artifacts, self.transaction_artifacts(project))

            failed = self.run_failure(
                "undo", "undo-after-0000,undo-rollback-before-0006", project, target
            )
            self.assertEqual(3, failed.returncode, failed.stderr)
            uncertain = self.lifecycle.tree_bytes(target, installation)
            recovery_artifacts = self.transaction_artifacts(project)
            recovery_preview = self.run_cli(
                "recover-adaptive", "--project", project, "--target-root", target
            )
            self.assertEqual(0, recovery_preview.returncode, recovery_preview.stderr)
            recovery_plan = json.loads(recovery_preview.stdout)
            recovery_missing = self.run_cli(
                "recover-adaptive", "--project", project, "--target-root", target,
                "--confirm", "RECOVER"
            )
            self.assertEqual(2, recovery_missing.returncode, recovery_missing.stderr)
            recovery_mismatch = self.run_cli(
                "recover-adaptive", "--project", project, "--target-root", target,
                "--confirm", "RECOVER",
                "--transaction-id", recovery_plan["transactionId"],
                "--plan-sha256", "0" * 64
            )
            self.assertEqual(3, recovery_mismatch.returncode, recovery_mismatch.stderr)
            self.assertEqual("", recovery_mismatch.stdout)
            self.assertEqual(uncertain, self.lifecycle.tree_bytes(target, installation))
            self.assertEqual(recovery_artifacts, self.transaction_artifacts(project))
            for response in ("recover\n", " RECOVER\n", "RECOVER \n", "\n", ""):
                refused = self.run_cli_input(
                    response,
                    "recover-active-adaptive",
                    "--installation-root",
                    installation,
                )
                self.assertEqual(0, refused.returncode, refused.stderr)
                self.assertEqual("", refused.stdout)
                self.assertIn("pending", refused.stderr.lower())
                self.assertEqual(uncertain, self.lifecycle.tree_bytes(target, installation))
                self.assertEqual(recovery_artifacts, self.transaction_artifacts(project))

        with tempfile.TemporaryDirectory(prefix="adaptive-reviewed-stale-") as temp:
            target, installation, project, export = self.target_project(Path(temp))
            preview = self.run_cli(
                "import-adaptive", "--project", project, "--export", export,
                "--target-root", target
            )
            self.assertEqual(0, preview.returncode, preview.stderr)
            artifacts_before = self.transaction_artifacts(project)
            evidence = target / "server/evidence/render-assets.bin"
            evidence.write_bytes(evidence.read_bytes() + b"drift")
            target_after_drift = self.lifecycle.tree_bytes(target, installation)
            refused = self.run_reviewed_apply(
                "import-adaptive", "IMPORT", "--project", project,
                "--export", export, "--target-root", target, preview=preview
            )
            self.assertEqual(3, refused.returncode, refused.stderr)
            self.assertEqual("", refused.stdout)
            self.assertEqual(
                target_after_drift, self.lifecycle.tree_bytes(target, installation)
            )
            self.assertEqual(artifacts_before, self.transaction_artifacts(project))

    def test_phase6_parser_process_scan_and_windows_launcher_control_flow(self):
        with tempfile.TemporaryDirectory(prefix="adaptive-parser-") as temp:
            missing = Path(temp) / "missing"
            duplicate_cases = (
                (
                    "import-adaptive", "--project", missing, "--project", missing,
                    "--export", missing,
                ),
                (
                    "undo-adaptive", "--project", missing, "--project", missing,
                ),
                (
                    "recover-adaptive", "--project", missing, "--project", missing,
                ),
                (
                    "import-adaptive", "--project", missing, "--export", missing,
                    "--export", missing,
                ),
                (
                    "undo-adaptive", "--project", missing, "--target-root", missing,
                    "--target-root", missing,
                ),
                (
                    "recover-adaptive", "--project", missing, "--confirm", "RECOVER",
                    "--confirm", "RECOVER",
                ),
                (
                    "import-adaptive", "--project", missing, "--export", missing,
                    "--confirm", "IMPORT", "--transaction-id",
                    "00000000-0000-0000-0000-000000000001", "--transaction-id",
                    "00000000-0000-0000-0000-000000000001",
                    "--plan-sha256", "0" * 64,
                ),
                (
                    "undo-adaptive", "--project", missing, "--confirm", "UNDO",
                    "--transaction-id", "00000000-0000-0000-0000-000000000001",
                    "--plan-sha256", "0" * 64, "--plan-sha256", "0" * 64,
                ),
                (
                    "import-active-adaptive", "--installation-root", missing,
                    "--installation-root", missing,
                ),
                (
                    "undo-active-adaptive", "--installation-root", missing,
                    "--confirm", "UNDO",
                ),
                (
                    "recover-active-adaptive", "--installation-root", missing,
                    "--confirm", "RECOVER",
                ),
            )
            for arguments in duplicate_cases:
                with self.subTest(arguments=arguments):
                    refused = self.run_cli(*arguments)
                    self.assertEqual(2, refused.returncode, refused.stderr)

            scratch = Path(temp)
            unsupported = self.run_failure(
                "unsupported-atomic-provider", "none", scratch, scratch
            )
            self.assertEqual(3, unsupported.returncode, unsupported.stderr)
            with zipfile.ZipFile(scratch / "unsupported-provider.zip") as archive:
                self.assertIn("publish/source.bin", archive.namelist())
                self.assertNotIn("publish/published.bin", archive.namelist())
            partial = self.run_failure(
                "process-observation", "partial-unreadable", scratch, scratch
            )
            self.assertEqual(3, partial.returncode, partial.stderr)
            self.assertIn("could not be completely examined", partial.stderr)
            for observation in (
                "java-command-only", "hidden-java-command-only", "target-java-command",
                "target-cwd-java",
            ):
                refused = self.run_failure(
                    "process-observation", observation, scratch, scratch
                )
                self.assertEqual(3, refused.returncode, refused.stderr)
                self.assertIn("OFFLINE_REQUIRED", refused.stderr)
            for observation in (
                "exited", "readable-command", "command-only", "kernel-thread",
                "target-command-only", "target-cwd-non-java",
            ):
                accepted = self.run_failure(
                    "process-observation", observation, scratch, scratch
                )
                self.assertEqual(0, accepted.returncode, accepted.stderr)

        for name, command in (
            ("Import Map Changes.cmd", "import-active-adaptive"),
            ("Undo Last Map Import.cmd", "undo-active-adaptive"),
            ("Recover Map Transaction.cmd", "recover-active-adaptive"),
        ):
            text = (ROOT / "release/world-builder-v2" / name).read_text(
                encoding="utf-8"
            )
            invocation = next(line for line in text.splitlines() if command in line)
            suffix = text[text.index(invocation) + len(invocation):]
            self.assertRegex(
                suffix,
                r"(?s)^\s*if errorlevel 1 goto failed\s*exit /b 0",
                name,
            )

        atomic_source = (
            ROOT
            / "tools/world-builder/src/com/openrsc/worldbuilder/WorldBuilderAdaptiveAtomicFiles.java"
        ).read_text(encoding="utf-8")
        self.assertIn("sun.nio.fs.WindowsFileSystemProvider", atomic_source)
        self.assertIn("Files.move(source, destination);", atomic_source)
        self.assertIn("Files.createLink(destination, source);", atomic_source)
        self.assertIn("StandardCopyOption.ATOMIC_MOVE", atomic_source)
        cli_source = (
            ROOT / "tools/world-builder/src/com/openrsc/worldbuilder/WorldBuilderCli.java"
        ).read_text(encoding="utf-8")
        self.assertIn('response == null ? "" : response.trim()', cli_source)
        self.assertIn('expected.equals(response == null ? "" : response);', cli_source)

        with tempfile.TemporaryDirectory(prefix="adaptive-process-view-") as temp:
            target, installation, project, export = self.target_project(
                Path(temp), offline_evidence=["process-scan"]
            )
            before = self.lifecycle.tree_bytes(target, installation)
            artifacts = self.transaction_artifacts(project)
            refused = self.run_cli_with_properties(
                {"worldbuilder.adaptive.testProcessViewUnavailable": "true"},
                "import-adaptive",
                "--project",
                project,
                "--export",
                export,
                "--target-root",
                target,
            )
            self.assertEqual(3, refused.returncode, refused.stderr)
            self.assertIn("/proc", refused.stderr)
            self.assertEqual(before, self.lifecycle.tree_bytes(target, installation))
            self.assertEqual(artifacts, self.transaction_artifacts(project))

    def test_created_directory_authority_is_exact_and_action_bounded(self):
        with tempfile.TemporaryDirectory(prefix="adaptive-directory-authority-") as temp:
            target, installation, project, export = self.target_project(Path(temp))
            preexisting_ancestor = target / "server/world-builder"
            preexisting_arbitrary = target / "server/owner-empty"
            preexisting_ancestor.mkdir()
            preexisting_arbitrary.mkdir()
            applied = self.run_reviewed_apply(
                "import-adaptive", "IMPORT", "--project", project,
                "--export", export, "--target-root", target,
            )
            self.assertEqual(0, applied.returncode, applied.stderr)
            receipt = next(
                json.loads(path.read_text(encoding="utf-8"))
                for path in (project / "receipts").glob("*.json")
                if json.loads(path.read_text(encoding="utf-8"))["transactionType"]
                == "import"
            )
            transaction = receipt["transactionId"]
            evidence_path = (
                project / "backups" / transaction / "created-directories.json"
            )
            plan = json.loads(
                (project / "backups" / transaction / "mutation-plan.json").read_text(
                    encoding="utf-8"
                )
            )
            original = json.loads(evidence_path.read_text(encoding="utf-8"))
            self.assertEqual(plan["createdDirectories"], original["relativePaths"])
            self.assertNotIn("server/world-builder", original["relativePaths"])

            def canonical(paths):
                return sorted(paths, key=lambda value: (len(value.split("/")), value))

            attacks = {
                "added-preexisting-ancestor": canonical(
                    original["relativePaths"] + ["server/world-builder"]
                ),
                "added-arbitrary-target": canonical(
                    original["relativePaths"] + ["server/owner-empty"]
                ),
                "removed": original["relativePaths"][:-1],
                "reordered": list(reversed(original["relativePaths"])),
            }
            for name, paths in attacks.items():
                with self.subTest(name=name):
                    altered = dict(original)
                    altered["relativePaths"] = paths
                    self.lifecycle.write_json(evidence_path, altered)
                    installed = self.lifecycle.tree_bytes(target, installation)
                    artifacts = self.transaction_artifacts(project)
                    refused = self.run_cli(
                        "undo-adaptive", "--project", project,
                        "--target-root", target,
                    )
                    self.assertEqual(3, refused.returncode, refused.stderr)
                    self.assertEqual(installed, self.lifecycle.tree_bytes(target, installation))
                    self.assertEqual(artifacts, self.transaction_artifacts(project))
                    self.assertTrue(preexisting_ancestor.is_dir())
                    self.assertTrue(preexisting_arbitrary.is_dir())
                    self.lifecycle.write_json(evidence_path, original)

            undone = self.run_reviewed_apply(
                "undo-adaptive", "UNDO", "--project", project,
                "--target-root", target,
            )
            self.assertEqual(0, undone.returncode, undone.stderr)
            self.assertTrue(preexisting_ancestor.is_dir())
            self.assertTrue(preexisting_arbitrary.is_dir())

    def test_fingerprint_container_siblings_block_undo_at_both_boundaries(self):
        with tempfile.TemporaryDirectory(prefix="adaptive-fingerprint-container-") as temp:
            target, installation, project, export = self.target_project(Path(temp))
            applied = self.run_reviewed_apply(
                "import-adaptive", "IMPORT", "--project", project,
                "--export", export, "--target-root", target,
            )
            self.assertEqual(0, applied.returncode, applied.stderr)
            configuration = json.loads(
                (target / "server/world-builder-configs/primary.json").read_text(
                    encoding="utf-8"
                )
            )
            for side, package in (
                ("server", configuration["serverMapRelativePath"]),
                ("client", configuration["clientMapRelativePath"]),
            ):
                with self.subTest(side=side):
                    marker = target / Path(package).parent / "untracked.bin"
                    marker.write_bytes((side + "-owner-data").encode("utf-8"))
                    installed = self.lifecycle.tree_bytes(target, installation)
                    artifacts = self.transaction_artifacts(project)
                    refused = self.run_cli(
                        "undo-adaptive", "--project", project,
                        "--target-root", target,
                    )
                    self.assertEqual(3, refused.returncode, refused.stderr)
                    self.assertEqual(installed, self.lifecycle.tree_bytes(target, installation))
                    self.assertEqual(artifacts, self.transaction_artifacts(project))
                    marker.unlink()

            artifacts = self.transaction_artifacts(project)
            refused = self.run_failure(
                "undo", "sibling-after-confirm", project, target
            )
            self.assertEqual(3, refused.returncode, refused.stderr)
            self.assertEqual(artifacts, self.transaction_artifacts(project))
            marker = next(target.rglob("after-confirm.bin"))
            self.assertEqual(b"\x09\x03", marker.read_bytes())
            marker.unlink()

    def test_saved_working_edits_preserve_historical_undo_authority(self):
        with tempfile.TemporaryDirectory(prefix="adaptive-historical-undo-") as temp:
            target, installation, project, export_a = self.target_project(Path(temp))
            target_before = self.lifecycle.tree_bytes(target, installation)
            imported = self.run_reviewed_apply(
                "import-adaptive", "IMPORT", "--project", project,
                "--export", export_a, "--target-root", target,
            )
            self.assertEqual(0, imported.returncode, imported.stderr)
            self.lifecycle.AdaptiveProjectLifecycleTest.change_working_terrain(project)
            saved = self.run_cli("save-project", "--project", project)
            self.assertEqual(0, saved.returncode, saved.stderr)
            working_b = self.lifecycle.tree_bytes(project / "working")
            exported_b = self.run_cli("export-adaptive", "--project", project)
            self.assertEqual(0, exported_b.returncode, exported_b.stderr)
            export_b = Path(json.loads(exported_b.stdout)["exportDirectory"])

            reopened = self.run_cli(
                "open-project", "--installation-root", installation,
                "--target-root", target,
            )
            self.assertEqual(0, reopened.returncode, reopened.stderr)
            self.assertEqual("ready-detached", json.loads(reopened.stdout)["state"])

            installed = self.lifecycle.tree_bytes(target, installation)
            artifacts = self.transaction_artifacts(project)
            second = self.run_cli(
                "import-adaptive", "--project", project, "--export", export_b,
                "--target-root", target,
            )
            self.assertEqual(3, second.returncode, second.stderr)
            self.assertIn("last successful server map import is still installed", second.stderr)
            self.assertIn("Undo Last Server Import", second.stderr)
            self.assertEqual(installed, self.lifecycle.tree_bytes(target, installation))
            self.assertEqual(artifacts, self.transaction_artifacts(project))

            undone = self.run_reviewed_apply(
                "undo-adaptive", "UNDO", "--project", project,
                "--target-root", target,
            )
            self.assertEqual(0, undone.returncode, undone.stderr)
            self.assertEqual(target_before, self.lifecycle.tree_bytes(target, installation))
            self.assertEqual(working_b, self.lifecycle.tree_bytes(project / "working"))

            reattached = self.run_cli(
                "open-project", "--installation-root", installation,
                "--target-root", target,
            )
            self.assertEqual(0, reattached.returncode, reattached.stderr)
            self.assertEqual("ready-attached", json.loads(reattached.stdout)["state"])
            current_import = self.run_reviewed_apply(
                "import-adaptive", "IMPORT", "--project", project,
                "--export", export_b, "--target-root", target,
            )
            self.assertEqual(0, current_import.returncode, current_import.stderr)

    def test_final_boundary_drift_and_appeared_paths_are_preserved(self):
        with tempfile.TemporaryDirectory(prefix="adaptive-activation-drift-") as temp:
            target, installation, project, export = self.target_project(Path(temp))
            failed = self.run_failure(
                "import", "activation-final-drift", project, target, export
            )
            self.assertEqual(3, failed.returncode, failed.stderr)
            self.assertEqual(
                b"\x04\x02\x04\x02",
                (target / "server/world-builder-configs/primary.json").read_bytes(),
            )

        with tempfile.TemporaryDirectory(prefix="adaptive-undo-final-drift-") as temp:
            target, installation, project, export = self.target_project(Path(temp))
            imported = self.run_reviewed_apply(
                "import-adaptive", "IMPORT", "--project", project,
                "--export", export, "--target-root", target,
            )
            self.assertEqual(0, imported.returncode, imported.stderr)
            failed = self.run_failure("undo", "undo-final-replacement", project, target)
            self.assertEqual(3, failed.returncode, failed.stderr)
            self.assertTrue(any(
                path.is_file() and path.read_bytes() == b"\x03\x01\x04\x01"
                for path in target.rglob("*")
            ))

        with tempfile.TemporaryDirectory(prefix="adaptive-undo-appeared-") as temp:
            target, installation, project, export = self.target_project(Path(temp))
            imported = self.run_reviewed_apply(
                "import-adaptive", "IMPORT", "--project", project,
                "--export", export, "--target-root", target,
            )
            self.assertEqual(0, imported.returncode, imported.stderr)
            failed = self.run_failure("undo", "appeared-undo-rollback", project, target)
            self.assertEqual(3, failed.returncode, failed.stderr)
            self.assertTrue(any(
                path.is_file() and path.read_bytes() == b"\x02\x07\x01\x08"
                for path in target.rglob("*")
            ))

        with tempfile.TemporaryDirectory(prefix="adaptive-recovery-appeared-") as temp:
            target, installation, project, export = self.target_project(Path(temp))
            imported = self.run_reviewed_apply(
                "import-adaptive", "IMPORT", "--project", project,
                "--export", export, "--target-root", target,
            )
            self.assertEqual(0, imported.returncode, imported.stderr)
            failed_undo = self.run_failure(
                "undo", "undo-after-0001,undo-rollback-before-0000",
                project, target,
            )
            self.assertEqual(3, failed_undo.returncode, failed_undo.stderr)
            failed_recovery = self.run_failure(
                "recovery", "appeared-recovery", project, target
            )
            self.assertEqual(3, failed_recovery.returncode, failed_recovery.stderr)
            self.assertTrue(any(
                path.is_file() and path.read_bytes() == b"\x01\x06\x01\x08"
                for path in target.rglob("*")
            ))

    def test_same_store_capacity_is_aggregated_before_artifacts(self):
        with tempfile.TemporaryDirectory(prefix="adaptive-combined-space-") as temp:
            target, installation, project, export = self.target_project(Path(temp))
            preview = self.run_cli(
                "import-adaptive", "--project", project, "--export", export,
                "--target-root", target
            )
            self.assertEqual(0, preview.returncode, preview.stderr)
            plan = json.loads(preview.stdout)
            target_bytes = sum(action["after"]["size"] for action in plan["actions"])
            project_bytes = (
                sum(action["before"]["size"] for action in plan["actions"])
                + 1_048_576
            )
            override = max(target_bytes, project_bytes)
            self.assertLess(override, target_bytes + project_bytes)
            before = self.lifecycle.tree_bytes(target, installation)
            artifacts = self.transaction_artifacts(project)
            refused = self.run_cli_with_properties(
                {"worldbuilder.adaptive.testUsableBytes": str(override)},
                "import-adaptive",
                "--project",
                project,
                "--export",
                export,
                "--target-root",
                target,
                "--confirm",
                "IMPORT",
                "--transaction-id",
                plan["transactionId"],
                "--plan-sha256",
                plan["planFingerprintSha256"],
            )
            self.assertEqual(3, refused.returncode, refused.stderr)
            self.assertIn("space", refused.stderr.lower())
            self.assertEqual(before, self.lifecycle.tree_bytes(target, installation))
            self.assertEqual(artifacts, self.transaction_artifacts(project))

            applied = self.run_reviewed_apply(
                "import-adaptive", "IMPORT", "--project", project,
                "--export", export, "--target-root", target, preview=preview
            )
            self.assertEqual(0, applied.returncode, applied.stderr)
            undo_preview = self.run_cli(
                "undo-adaptive", "--project", project, "--target-root", target
            )
            self.assertEqual(0, undo_preview.returncode, undo_preview.stderr)
            undo_plan = json.loads(undo_preview.stdout)
            undo_target = sum(
                action["after"]["size"] for action in undo_plan["actions"]
            )
            undo_project = (
                sum(action["before"]["size"] for action in undo_plan["actions"])
                + 1_048_576
            )
            undo_override = max(undo_target, undo_project)
            installed = self.lifecycle.tree_bytes(target, installation)
            undo_artifacts = self.transaction_artifacts(project)
            undo_refused = self.run_cli_with_properties(
                {"worldbuilder.adaptive.testUsableBytes": str(undo_override)},
                "undo-adaptive",
                "--project",
                project,
                "--target-root",
                target,
                "--confirm",
                "UNDO",
                "--transaction-id",
                undo_plan["transactionId"],
                "--plan-sha256",
                undo_plan["planFingerprintSha256"],
            )
            self.assertEqual(3, undo_refused.returncode, undo_refused.stderr)
            self.assertIn("space", undo_refused.stderr.lower())
            self.assertEqual(installed, self.lifecycle.tree_bytes(target, installation))
            self.assertEqual(undo_artifacts, self.transaction_artifacts(project))

            interrupted = self.run_failure(
                "undo", "undo-after-0000,undo-rollback-before-0006", project, target
            )
            self.assertEqual(3, interrupted.returncode, interrupted.stderr)
            recovery_preview = self.run_cli(
                "recover-adaptive", "--project", project, "--target-root", target
            )
            self.assertEqual(0, recovery_preview.returncode, recovery_preview.stderr)
            recovery_plan = json.loads(recovery_preview.stdout)
            recovery_target = sum(
                action["after"]["size"] for action in recovery_plan["actions"]
            )
            recovery_project = (
                sum(action["before"]["size"] for action in recovery_plan["actions"])
                + 1_048_576
            )
            recovery_override = max(recovery_target, recovery_project)
            uncertain = self.lifecycle.tree_bytes(target, installation)
            recovery_artifacts = self.transaction_artifacts(project)
            recovery_refused = self.run_cli_with_properties(
                {"worldbuilder.adaptive.testUsableBytes": str(recovery_override)},
                "recover-adaptive",
                "--project",
                project,
                "--target-root",
                target,
                "--confirm",
                "RECOVER",
                "--transaction-id",
                recovery_plan["transactionId"],
                "--plan-sha256",
                recovery_plan["planFingerprintSha256"],
            )
            self.assertEqual(3, recovery_refused.returncode, recovery_refused.stderr)
            self.assertIn("space", recovery_refused.stderr.lower())
            self.assertEqual(uncertain, self.lifecycle.tree_bytes(target, installation))
            self.assertEqual(recovery_artifacts, self.transaction_artifacts(project))

    def test_content_addressed_roots_are_wholly_absent_and_preserved(self):
        cases = ("server-empty", "client-extra", "server-link", "client-case")
        for case in cases:
            with self.subTest(case=case), tempfile.TemporaryDirectory(
                prefix=f"adaptive-root-{case}-"
            ) as temp:
                base = Path(temp)
                target, installation, project, export = self.target_project(base)
                preview = self.run_cli(
                    "import-adaptive", "--project", project, "--export", export,
                    "--target-root", target
                )
                self.assertEqual(0, preview.returncode, preview.stderr)
                plan = json.loads(preview.stdout)
                role_prefix = "server-package" if case.startswith("server") else "client-package"
                destination = next(
                    action["destinationRelativePath"]
                    for action in plan["actions"]
                    if action["role"].startswith(role_prefix)
                )
                root_relative = destination.split("/package/", 1)[0]
                root = target / root_relative
                root.parent.mkdir(parents=True, exist_ok=True)
                if case.endswith("empty"):
                    root.mkdir()
                elif case.endswith("extra"):
                    root.mkdir()
                    (root / "untracked.bin").write_bytes(b"preserve-extra")
                elif case.endswith("link"):
                    outside = base / "outside-package"
                    outside.mkdir()
                    (outside / "marker").write_bytes(b"outside")
                    root.symlink_to(outside, target_is_directory=True)
                else:
                    alias = root.with_name(root.name.upper())
                    self.assertNotEqual(root.name, alias.name)
                    alias.mkdir()
                    (alias / "marker").write_bytes(b"case-alias")
                target_with_collision = self.lifecycle.tree_bytes(target, installation)
                artifacts = self.transaction_artifacts(project)
                refused = self.run_reviewed_apply(
                    "import-adaptive", "IMPORT", "--project", project,
                    "--export", export, "--target-root", target, preview=preview
                )
                self.assertEqual(3, refused.returncode, refused.stderr)
                self.assertEqual(
                    target_with_collision,
                    self.lifecycle.tree_bytes(target, installation),
                )
                self.assertEqual(artifacts, self.transaction_artifacts(project))

    def test_project_lock_and_transaction_identity_collisions_fail_closed(self):
        with tempfile.TemporaryDirectory(prefix="adaptive-lock-missing-") as temp:
            target, installation, project, export = self.target_project(Path(temp))
            lock = project / "run/world-builder.lock"
            lock.unlink()
            exported = self.run_cli("export-adaptive", "--project", project)
            self.assertEqual(0, exported.returncode, exported.stderr)
            self.assertTrue(lock.is_file())
            self.assertFalse(lock.is_symlink())
            self.assertEqual(1, lock.stat().st_nlink)

        for kind in ("symlink", "hardlink", "case-alias"):
            with self.subTest(lock=kind), tempfile.TemporaryDirectory(
                prefix=f"adaptive-lock-{kind}-"
            ) as temp:
                base = Path(temp)
                target, installation, project, export = self.target_project(base)
                lock = project / "run/world-builder.lock"
                lock.unlink()
                outside = base / "outside-lock"
                outside.write_bytes(b"preserve-lock")
                if kind == "symlink":
                    lock.symlink_to(outside)
                elif kind == "hardlink":
                    os.link(outside, lock)
                else:
                    (project / "run/World-Builder.lock").write_bytes(b"alias")
                before = self.lifecycle.tree_bytes(project / "run")
                refused = self.run_cli("export-adaptive", "--project", project)
                self.assertEqual(3, refused.returncode, refused.stderr)
                self.assertEqual(before, self.lifecycle.tree_bytes(project / "run"))
                self.assertEqual(b"preserve-lock", outside.read_bytes())

        for collision in ("backup", "receipt"):
            with self.subTest(collision=collision), tempfile.TemporaryDirectory(
                prefix=f"adaptive-id-{collision}-"
            ) as temp:
                target, installation, project, export = self.target_project(Path(temp))
                preview = self.run_cli(
                    "import-adaptive", "--project", project, "--export", export,
                    "--target-root", target
                )
                self.assertEqual(0, preview.returncode, preview.stderr)
                plan = json.loads(preview.stdout)
                if collision == "backup":
                    occupied = project / "backups" / plan["transactionId"]
                    occupied.mkdir()
                    (occupied / "marker").write_bytes(b"existing-backup")
                else:
                    occupied = project / "receipts" / f'{plan["transactionId"]}.json'
                    occupied.write_bytes(b"existing-receipt")
                target_before = self.lifecycle.tree_bytes(target, installation)
                occupied_before = (
                    self.lifecycle.tree_bytes(occupied)
                    if occupied.is_dir()
                    else occupied.read_bytes()
                )
                refused = self.run_reviewed_apply(
                    "import-adaptive", "IMPORT", "--project", project,
                    "--export", export, "--target-root", target, preview=preview
                )
                self.assertEqual(3, refused.returncode, refused.stderr)
                self.assertEqual(
                    target_before, self.lifecycle.tree_bytes(target, installation)
                )
                if occupied.is_dir():
                    self.assertEqual(occupied_before, self.lifecycle.tree_bytes(occupied))
                else:
                    self.assertEqual(occupied_before, occupied.read_bytes())

        with tempfile.TemporaryDirectory(prefix="adaptive-id-undo-") as temp:
            target, installation, project, export = self.target_project(Path(temp))
            applied = self.run_reviewed_apply(
                "import-adaptive", "IMPORT", "--project", project,
                "--export", export, "--target-root", target
            )
            self.assertEqual(0, applied.returncode, applied.stderr)
            preview = self.run_cli(
                "undo-adaptive", "--project", project, "--target-root", target
            )
            self.assertEqual(0, preview.returncode, preview.stderr)
            plan = json.loads(preview.stdout)
            occupied = project / "backups" / plan["transactionId"]
            occupied.mkdir()
            (occupied / "marker").write_bytes(b"undo-collision")
            installed = self.lifecycle.tree_bytes(target, installation)
            refused = self.run_reviewed_apply(
                "undo-adaptive", "UNDO", "--project", project,
                "--target-root", target, preview=preview
            )
            self.assertEqual(3, refused.returncode, refused.stderr)
            self.assertEqual(installed, self.lifecycle.tree_bytes(target, installation))
            self.assertEqual(b"undo-collision", (occupied / "marker").read_bytes())

        with tempfile.TemporaryDirectory(prefix="adaptive-id-recovery-") as temp:
            target, installation, project, export = self.target_project(Path(temp))
            failed = self.run_failure(
                "import", "package-file-published-0000,rollback-before-0006",
                project, target, export
            )
            self.assertEqual(3, failed.returncode, failed.stderr)
            preview = self.run_cli(
                "recover-adaptive", "--project", project, "--target-root", target
            )
            self.assertEqual(0, preview.returncode, preview.stderr)
            plan = json.loads(preview.stdout)
            occupied = project / "backups" / plan["transactionId"]
            occupied.mkdir()
            (occupied / "marker").write_bytes(b"recovery-collision")
            uncertain = self.lifecycle.tree_bytes(target, installation)
            refused = self.run_reviewed_apply(
                "recover-adaptive", "RECOVER", "--project", project,
                "--target-root", target, preview=preview
            )
            self.assertEqual(3, refused.returncode, refused.stderr)
            self.assertEqual(uncertain, self.lifecycle.tree_bytes(target, installation))
            self.assertEqual(b"recovery-collision", (occupied / "marker").read_bytes())

        with tempfile.TemporaryDirectory(prefix="adaptive-lock-replacement-") as temp:
            target, installation, project, export = self.target_project(Path(temp))
            project_bytes = self.lifecycle.tree_bytes(project / "run")
            replaced_project = self.run_failure(
                "project-lock-replacement", "none", project, target
            )
            self.assertEqual(3, replaced_project.returncode, replaced_project.stderr)
            self.assertEqual(project_bytes, self.lifecycle.tree_bytes(project / "run"))

            target_bytes = self.lifecycle.tree_bytes(target, installation)
            replaced_target = self.run_failure(
                "target-lock-replacement", "none", project, target
            )
            self.assertEqual(3, replaced_target.returncode, replaced_target.stderr)
            self.assertEqual(target_bytes, self.lifecycle.tree_bytes(target, installation))

        with tempfile.TemporaryDirectory(
            prefix="adaptive-lock-absent-replacement-"
        ) as temp:
            target, installation, project, export = self.target_project(Path(temp))
            lock = project / "run/world-builder.lock"
            lock.unlink()
            target_bytes = self.lifecycle.tree_bytes(target, installation)
            artifacts = self.transaction_artifacts(project)
            replaced_absent = self.run_failure(
                "project-lock-replacement", "none", project, target
            )
            self.assertEqual(3, replaced_absent.returncode, replaced_absent.stderr)
            self.assertIn("UNSAFE_PATH", replaced_absent.stderr)
            self.assertTrue(lock.is_file())
            self.assertEqual(b"", lock.read_bytes())
            self.assertEqual(target_bytes, self.lifecycle.tree_bytes(target, installation))
            self.assertEqual(artifacts, self.transaction_artifacts(project))

        with tempfile.TemporaryDirectory(prefix="adaptive-lock-aba-") as temp:
            target, installation, project, export = self.target_project(Path(temp))
            project_bytes = self.lifecycle.tree_bytes(project / "run")
            replaced_aba = self.run_failure(
                "project-lock-aba-replacement", "none", project, target
            )
            self.assertEqual(3, replaced_aba.returncode, replaced_aba.stderr)
            self.assertIn("UNSAFE_PATH", replaced_aba.stderr)
            self.assertEqual(project_bytes, self.lifecycle.tree_bytes(project / "run"))

    def test_manifest_and_hardlinked_authorities_are_independently_rejected(self):
        with tempfile.TemporaryDirectory(prefix="adaptive-report-binding-") as temp:
            target, installation, project, export = self.target_project(Path(temp))
            report_path = export / "validation-report.json"
            manifest_path = export / "manifest.json"
            report = json.loads(report_path.read_text(encoding="utf-8"))
            manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
            report["packageManifestSha256"] = "0" * 64
            self.lifecycle.write_json(report_path, report)
            manifest["validationReports"][0]["sha256"] = self.canonical_sha256(report)
            self.bind_fingerprint(manifest, "exportFingerprintSha256")
            self.lifecycle.write_json(manifest_path, manifest)
            before = self.lifecycle.tree_bytes(target, installation)
            refused = self.run_cli(
                "import-adaptive", "--project", project, "--export", export,
                "--target-root", target
            )
            self.assertEqual(3, refused.returncode, refused.stderr)
            self.assertIn("validation", refused.stderr.lower())
            self.assertEqual(before, self.lifecycle.tree_bytes(target, installation))

        with tempfile.TemporaryDirectory(prefix="adaptive-export-hardlink-") as temp:
            base = Path(temp)
            target, installation, project, export = self.target_project(base)
            package_file = export / "package/manifest.json"
            external = base / "linked-export-file"
            os.link(package_file, external)
            before = self.lifecycle.tree_bytes(target, installation)
            refused = self.run_cli(
                "import-adaptive", "--project", project, "--export", export,
                "--target-root", target
            )
            self.assertEqual(3, refused.returncode, refused.stderr)
            self.assertEqual(before, self.lifecycle.tree_bytes(target, installation))
            self.assertEqual(package_file.read_bytes(), external.read_bytes())

        for authority in ("receipt", "plan", "directories", "backup", "installed"):
            with self.subTest(authority=authority), tempfile.TemporaryDirectory(
                prefix=f"adaptive-hardlink-{authority}-"
            ) as temp:
                base = Path(temp)
                target, installation, project, export = self.target_project(base)
                applied = self.run_reviewed_apply(
                    "import-adaptive", "IMPORT", "--project", project,
                    "--export", export, "--target-root", target
                )
                self.assertEqual(0, applied.returncode, applied.stderr)
                transaction = json.loads(applied.stdout)["transactionId"]
                if authority == "receipt":
                    source = project / "receipts" / f"{transaction}.json"
                elif authority == "plan":
                    source = project / "backups" / transaction / "mutation-plan.json"
                elif authority == "directories":
                    source = (
                        project / "backups" / transaction / "created-directories.json"
                    )
                elif authority == "backup":
                    source = (
                        project / "backups" / transaction / "before/server/"
                        "world-builder-configs/primary.json"
                    )
                else:
                    receipt = json.loads(
                        (project / "receipts" / f"{transaction}.json").read_text()
                    )
                    source = target / next(
                        item["relativePath"]
                        for item in receipt["files"]
                        if item["role"].startswith("server-package")
                    )
                external = base / f"linked-{authority}"
                os.link(source, external)
                installed = self.lifecycle.tree_bytes(target, installation)
                artifacts = self.transaction_artifacts(project)
                refused = self.run_cli(
                    "undo-adaptive", "--project", project, "--target-root", target
                )
                self.assertEqual(3, refused.returncode, refused.stderr)
                self.assertEqual(installed, self.lifecycle.tree_bytes(target, installation))
                self.assertEqual(artifacts, self.transaction_artifacts(project))
                self.assertEqual(source.read_bytes(), external.read_bytes())

    def test_appeared_publication_and_staging_destinations_are_preserved(self):
        with tempfile.TemporaryDirectory(prefix="adaptive-export-race-") as temp:
            target, installation, project, export = self.target_project(Path(temp))
            failed = self.run_failure(
                "export", "publish-destination-collision", project, target
            )
            self.assertEqual(3, failed.returncode, failed.stderr)
            markers = list((project / "exports").glob("export-*/external-marker"))
            self.assertEqual(1, len(markers), markers)
            self.assertEqual(b"\x09", markers[0].read_bytes())
            self.assertFalse(list((project / "exports").glob(".staging-*")))

        with tempfile.TemporaryDirectory(prefix="adaptive-export-ownership-") as temp:
            target, installation, project, export = self.target_project(Path(temp))
            failed = self.run_failure(
                "export", "injected-published-path", project, target
            )
            self.assertEqual(3, failed.returncode, failed.stderr)
            markers = list((project / "exports").glob("export-*/external-marker"))
            self.assertEqual(1, len(markers), markers)
            self.assertEqual(b"\x04\x04", markers[0].read_bytes())

        with tempfile.TemporaryDirectory(prefix="adaptive-stage-race-") as temp:
            target, installation, project, export = self.target_project(Path(temp))
            failed = self.run_failure(
                "import", "stage-collision", project, target, export
            )
            self.assertEqual(3, failed.returncode, failed.stderr)
            stages = list(target.rglob("*.stage-*"))
            self.assertEqual(1, len(stages), stages)
            self.assertEqual(b"\x07\x07", stages[0].read_bytes())
            statuses = [
                json.loads(path.read_text(encoding="utf-8"))["status"]
                for path in (project / "receipts").glob("*.json")
            ]
            self.assertEqual(["failed-no-change"], statuses)

        for failure, expected_bytes in (
            ("publication-collision", b"\x05\x05"),
            ("replaced-owned-stage", b"\x08\x08"),
        ):
            with self.subTest(failure=failure), tempfile.TemporaryDirectory(
                prefix=f"adaptive-owned-stage-{failure}-"
            ) as temp:
                target, installation, project, export = self.target_project(Path(temp))
                failed = self.run_failure("import", failure, project, target, export)
                self.assertEqual(3, failed.returncode, failed.stderr)
                preserved = [
                    path
                    for path in target.rglob("*")
                    if path.is_file() and path.read_bytes() == expected_bytes
                ]
                self.assertEqual(1, len(preserved), preserved)
                statuses = [
                    json.loads(path.read_text(encoding="utf-8"))["status"]
                    for path in (project / "receipts").glob("*.json")
                ]
                self.assertEqual(["recovery-required"], statuses)
                recovery = self.run_cli(
                    "recover-adaptive", "--project", project, "--target-root", target
                )
                self.assertEqual(3, recovery.returncode, recovery.stderr)
                self.assertEqual(expected_bytes, preserved[0].read_bytes())

        with tempfile.TemporaryDirectory(prefix="adaptive-undo-temp-race-") as temp:
            target, installation, project, export = self.target_project(Path(temp))
            applied = self.run_reviewed_apply(
                "import-adaptive", "IMPORT", "--project", project,
                "--export", export, "--target-root", target
            )
            self.assertEqual(0, applied.returncode, applied.stderr)
            failed = self.run_failure(
                "undo", "undo-rollback-temp-collision", project, target
            )
            self.assertEqual(3, failed.returncode, failed.stderr)
            collisions = list(target.rglob("*.undo-rollback-*"))
            self.assertEqual(1, len(collisions), collisions)
            self.assertEqual(b"\x06\x06", collisions[0].read_bytes())
            recovery = self.run_cli(
                "recover-adaptive", "--project", project, "--target-root", target
            )
            self.assertEqual(3, recovery.returncode, recovery.stderr)
            self.assertEqual(b"\x06\x06", collisions[0].read_bytes())

    def test_undo_deactivates_first_and_rollback_reactivates_last(self):
        with tempfile.TemporaryDirectory(prefix="adaptive-undo-order-") as temp:
            target, installation, project, export = self.target_project(Path(temp))
            applied = self.run_reviewed_apply(
                "import-adaptive", "IMPORT", "--project", project,
                "--export", export, "--target-root", target
            )
            self.assertEqual(0, applied.returncode, applied.stderr)
            installed = self.lifecycle.tree_bytes(target, installation)
            failed = self.run_failure("undo", "assert-safe-order", project, target)
            self.assertEqual(3, failed.returncode, failed.stderr)
            self.assertIn("injected safe-order rollback", failed.stderr)
            self.assertNotIn("order assertion", failed.stderr)
            self.assertEqual(installed, self.lifecycle.tree_bytes(target, installation))
            statuses = sorted(
                json.loads(path.read_text(encoding="utf-8"))["status"]
                for path in (project / "receipts").glob("*.json")
            )
            self.assertEqual(["rolled-back", "successful"], statuses)

    def test_recovery_cleans_only_exact_derivable_transaction_stages(self):
        for exact in (True, False):
            with self.subTest(exact=exact), tempfile.TemporaryDirectory(
                prefix=f"adaptive-recovery-stage-{exact}-"
            ) as temp:
                target, installation, project, export = self.target_project(Path(temp))
                before = self.lifecycle.tree_bytes(target, installation)
                failed = self.run_failure(
                    "import",
                    "package-file-published-0000,rollback-before-0006",
                    project,
                    target,
                    export,
                )
                self.assertEqual(3, failed.returncode, failed.stderr)
                receipt_path = next((project / "receipts").glob("*.json"))
                receipt = json.loads(receipt_path.read_text(encoding="utf-8"))
                configuration = next(
                    item for item in receipt["files"]
                    if item["role"] == "activation-configuration"
                )
                destination = target / configuration["relativePath"]
                stage = destination.parent / (
                    f".{destination.name}.rollback-{receipt['transactionId']}"
                )
                backup = project / configuration["backupRelativePath"]
                stage.write_bytes(backup.read_bytes() if exact else b"not-exact")
                preview = self.run_cli(
                    "recover-adaptive", "--project", project, "--target-root", target
                )
                if not exact:
                    self.assertEqual(3, preview.returncode, preview.stderr)
                    self.assertEqual(b"not-exact", stage.read_bytes())
                    continue
                self.assertEqual(0, preview.returncode, preview.stderr)
                recovery_plan = json.loads(preview.stdout)
                self.assertTrue(
                    any(
                        action["destinationRelativePath"].endswith(
                            f".rollback-{receipt['transactionId']}"
                        )
                        for action in recovery_plan["actions"]
                    )
                )
                recovered = self.run_reviewed_apply(
                    "recover-adaptive", "RECOVER", "--project", project,
                    "--target-root", target, preview=preview
                )
                self.assertEqual(0, recovered.returncode, recovered.stderr)
                self.assertFalse(stage.exists())
                self.assertEqual(before, self.lifecycle.tree_bytes(target, installation))

        with tempfile.TemporaryDirectory(prefix="adaptive-recovery-unknown-stage-") as temp:
            target, installation, project, export = self.target_project(Path(temp))
            failed = self.run_failure(
                "import", "package-file-published-0000,rollback-before-0006",
                project, target, export
            )
            self.assertEqual(3, failed.returncode, failed.stderr)
            receipt = json.loads(next((project / "receipts").glob("*.json")).read_text())
            configuration = next(
                item for item in receipt["files"]
                if item["role"] == "activation-configuration"
            )
            destination = target / configuration["relativePath"]
            unknown = destination.parent / (
                f".{destination.name}.rollback-{receipt['transactionId']}-unknown"
            )
            unknown.write_bytes(b"preserve-unknown")
            refused = self.run_cli(
                "recover-adaptive", "--project", project, "--target-root", target
            )
            self.assertEqual(3, refused.returncode, refused.stderr)
            self.assertEqual(b"preserve-unknown", unknown.read_bytes())

    def test_z_port_bind_offline_evidence_refuses_and_releases_cleanly(self):
        with tempfile.TemporaryDirectory(prefix="adaptive-import-port-") as temp:
            target, installation, project, export = self.target_project(
                Path(temp), port_evidence=True
            )
            target_before = self.lifecycle.tree_bytes(target, installation)
            artifacts_before = self.transaction_artifacts(project)
            with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as held:
                held.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 0)
                held.bind(("0.0.0.0", 43594))
                held.listen(1)
                refused = self.run_cli(
                    "import-adaptive", "--project", project, "--export", export,
                    "--target-root", target
                )
            self.assertEqual(3, refused.returncode, refused.stderr)
            self.assertIn("OFFLINE_REQUIRED", refused.stderr)
            self.assertIn("43594", refused.stderr)
            self.assertEqual(target_before, self.lifecycle.tree_bytes(target, installation))
            self.assertEqual(artifacts_before, self.transaction_artifacts(project))

            released = self.run_cli(
                "import-adaptive", "--project", project, "--export", export,
                "--target-root", target
            )
            self.assertEqual(0, released.returncode, released.stderr)
            self.assertEqual(target_before, self.lifecycle.tree_bytes(target, installation))
            self.assertEqual(artifacts_before, self.transaction_artifacts(project))


if __name__ == "__main__":
    unittest.main()
