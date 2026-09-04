#!/usr/bin/env python3
"""Temporary-fixture coverage for adaptive export/import/recovery/undo."""

import hashlib
import json
import os
import shutil
import socket
import subprocess
import tempfile
import unittest
import zipfile
import xml.etree.ElementTree as ET
from pathlib import Path

import adaptive_project_test_support as project_support


ROOT = Path(__file__).resolve().parents[2]
SOURCE_ROOT = ROOT / "tools/world-builder/src"
MAIN_CLASS = "com.openrsc.worldbuilder.WorldBuilderCli"
class AdaptiveTransactionTest(unittest.TestCase):
    @staticmethod
    def write_runtime_jar(path: Path, payload: bytes):
        with zipfile.ZipFile(path, "w") as archive:
            archive.writestr("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\n\n")
            archive.writestr("target/RuntimeMarker.class", payload)
            if path.name == "core.jar":
                archive.writestr(
                    "com/openrsc/server/io/WorldBuilderInstalledServerProfile.class",
                    payload,
                )
                archive.writestr(
                    "com/openrsc/server/io/NativeLayeredWorldPackage.class",
                    payload,
                )
                archive.writestr(
                    "com/openrsc/server/net/RSCProtocolDecoder.class",
                    project_support.FIXTURE_HOST_DECODER_CLASS,
                )
            else:
                archive.writestr(
                    "orsc/WorldBuilderInstalledClientProfile.class", payload
                )
                archive.writestr("orsc/WorldBuilderTerrainBootstrap.class", payload)

    @classmethod
    def setUpClass(cls):
        cls.fixtures = project_support.load_discovery_fixtures()
        cls.packed_fixtures = project_support.load_packed_fixtures()
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
        current_address = """return PACKED_PROFILE.equals(profile)
			? packageValue.fingerprintSha256
			: packageValue.nativeInventorySha256;"""
        historical_address = "return packageValue.fingerprintSha256;"
        if legacy_source.count(current_address) != 1:
            raise AssertionError("historical address fixture requires one address policy")
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
        final Path target = "-".equals(args[3]) ? null : Paths.get(args[3]);
        try {
            if ("undo-preview".equals(operation)) {
                WorldBuilderAdaptiveUndo undo = new WorldBuilderAdaptiveUndo();
                System.out.print(undo.preview(project, target).toJson());
            } else if ("undo-apply".equals(operation)) {
                WorldBuilderAdaptiveUndo undo = new WorldBuilderAdaptiveUndo();
                WorldBuilderAdaptiveUndo.Preview preview =
                    undo.preview(project, target, failures);
                if (!preview.planFingerprintSha256().equals(args[4])) {
                    System.err.println("REVIEWED_PLAN_MISMATCH: undo preview fingerprint changed");
                    System.exit(3);
                }
                System.out.print(undo.apply(preview, "UNDO").toJson());
            } else if ("reserved-stage-copy".equals(operation)) {
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
            } else if ("import".equals(operation)
                || "runtime-upgrade".equals(operation)) {
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
                                Files.deleteIfExists(path);
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
                boolean runtimeUpgrade = "runtime-upgrade".equals(operation);
                WorldBuilderAdaptiveImporter.Preview preview = runtimeUpgrade
                    ? importer.previewRuntimeUpgrade(
                        project, Paths.get(args[4]), target)
                    : importer.preview(project, Paths.get(args[4]), target);
                importer.apply(preview, runtimeUpgrade ? "UPGRADE" : "IMPORT");
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
                                if ("undo-rollback-before-0008".equals(milestone)
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
                                && milestone.startsWith("undo-rollback-before-")
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
        if args and args[0] == "undo-adaptive":
            return self.run_internal_undo_cli(*args[1:])
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
        if command == "undo-adaptive":
            if preview is None:
                preview = self.run_internal_undo_cli(*args)
                self.assertEqual(0, preview.returncode, preview.stderr)
            plan = json.loads(preview.stdout)
            return self.run_internal_undo_cli(
                *args,
                "--confirm", confirmation,
                "--transaction-id", plan["transactionId"],
                "--plan-sha256", plan["planFingerprintSha256"],
            )
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
        if args and args[0] == "undo-adaptive":
            return self.run_internal_undo_cli(*args[1:], properties=properties)
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

    def run_internal_undo_cli(self, *args, properties=None):
        options = {}
        index = 0
        while index < len(args):
            option = str(args[index])
            if option not in {
                "--project", "--target-root", "--confirm",
                "--transaction-id", "--plan-sha256",
            } or option in options or index + 1 >= len(args):
                return subprocess.CompletedProcess(
                    args, 2, "", "invalid internal undo test arguments"
                )
            options[option] = str(args[index + 1])
            index += 2
        if "--project" not in options:
            return subprocess.CompletedProcess(args, 2, "", "missing project")
        confirmation = options.get("--confirm")
        reviewed = ("--transaction-id", "--plan-sha256")
        if confirmation is None and any(option in options for option in reviewed):
            return subprocess.CompletedProcess(args, 2, "", "incomplete reviewed plan")
        if confirmation is not None and (
            confirmation != "UNDO" or not all(option in options for option in reviewed)
        ):
            return subprocess.CompletedProcess(args, 2, "", "invalid confirmation")
        operation = "undo-apply" if confirmation is not None else "undo-preview"
        value = options.get("--transaction-id", "preview")
        command = [
            "java",
            *[f"-D{key}={item}" for key, item in (properties or {}).items()],
            "-cp", str(self.classes),
            "com.openrsc.worldbuilder.AdaptiveTransactionFailureHarness",
            operation, value, options["--project"], options.get("--target-root", "-"),
        ]
        if confirmation is not None:
            command.append(options["--plan-sha256"])
        return subprocess.run(command, cwd=ROOT, text=True, capture_output=True)

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
            project_support.tree_bytes(project / "backups"),
            project_support.tree_bytes(project / "receipts"),
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
            target_before = project_support.tree_bytes(target, installation)
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
            self.assertEqual(target_before, project_support.tree_bytes(target, installation))

    def test_chained_import_adopts_exact_historical_address_correction(self):
        with tempfile.TemporaryDirectory(prefix="adaptive-corrected-chain-") as temp:
            target, installation, project, export_a = self.target_project(Path(temp))
            target_before = project_support.tree_bytes(target, installation)
            imported_a = self.run_legacy_reviewed_apply(
                "import-adaptive", "IMPORT", "--project", project,
                "--export", export_a, "--target-root", target,
            )
            self.assertEqual(0, imported_a.returncode, imported_a.stderr)

            configuration_path = target / "server/world-builder-configs/primary.json"
            configuration = json.loads(configuration_path.read_text(encoding="utf-8"))
            legacy_address = json.loads(
                (export_a / "manifest.json").read_text(encoding="utf-8")
            )["packageFingerprintSha256"]
            native_address = self.native_package_inventory_sha256(export_a / "package")
            for key in ("serverMapRelativePath", "clientMapRelativePath"):
                old_package = configuration[key]
                self.assertIn(legacy_address, old_package)
                new_package = old_package.replace(legacy_address, native_address)
                old_root = target / Path(old_package).parent
                new_root = target / Path(new_package).parent
                self.assertFalse(new_root.exists())
                shutil.copytree(old_root, new_root)
                configuration[key] = new_package
            project_support.write_json(configuration_path, configuration)
            corrected_a = project_support.tree_bytes(target, installation)

            project_support.change_working_terrain(project)
            saved = self.run_cli("save-project", "--project", project)
            self.assertEqual(0, saved.returncode, saved.stderr)
            exported_b = self.run_cli("export-adaptive", "--project", project)
            self.assertEqual(0, exported_b.returncode, exported_b.stderr)
            export_b = Path(json.loads(exported_b.stdout)["exportDirectory"])
            reopened = self.run_cli(
                "open-project", "--installation-root", installation,
                "--target-root", target,
            )
            self.assertEqual(0, reopened.returncode, reopened.stderr)
            self.assertEqual("ready-detached", json.loads(reopened.stdout)["state"])

            retained_manifest = (
                target / "server/world-builder/packages" / legacy_address
                / "package/manifest.json"
            )
            retained_bytes = retained_manifest.read_bytes()
            retained_manifest.unlink()
            refused = self.run_cli(
                "import-adaptive", "--project", project,
                "--export", export_b, "--target-root", target,
            )
            self.assertEqual(3, refused.returncode, refused.stderr)
            self.assertIn("TARGET_DRIFT", refused.stderr)
            retained_manifest.write_bytes(retained_bytes)

            imported_b = self.run_reviewed_apply(
                "import-adaptive", "IMPORT", "--project", project,
                "--export", export_b, "--target-root", target,
            )
            self.assertEqual(0, imported_b.returncode, imported_b.stderr)
            self.assertNotEqual(corrected_a, project_support.tree_bytes(target, installation))

            undone_b = self.run_reviewed_apply(
                "undo-adaptive", "UNDO", "--project", project,
                "--target-root", target,
            )
            self.assertEqual(0, undone_b.returncode, undone_b.stderr)
            self.assertEqual(corrected_a, project_support.tree_bytes(target, installation))

            undone_a = self.run_reviewed_apply(
                "undo-adaptive", "UNDO", "--project", project,
                "--target-root", target,
            )
            self.assertEqual(0, undone_a.returncode, undone_a.stderr)
            self.assertEqual(target_before, project_support.tree_bytes(target, installation))

    def test_chained_import_adopts_asymmetric_historical_address_correction(self):
        with tempfile.TemporaryDirectory(prefix="adaptive-asymmetric-chain-") as temp:
            target, installation, project, export_a = self.target_project(Path(temp))
            target_before = project_support.tree_bytes(target, installation)
            imported_a = self.run_legacy_reviewed_apply(
                "import-adaptive", "IMPORT", "--project", project,
                "--export", export_a, "--target-root", target,
            )
            self.assertEqual(0, imported_a.returncode, imported_a.stderr)

            configuration_path = target / "server/world-builder-configs/primary.json"
            configuration = json.loads(configuration_path.read_text(encoding="utf-8"))
            legacy_address = json.loads(
                (export_a / "manifest.json").read_text(encoding="utf-8")
            )["packageFingerprintSha256"]
            native_address = self.native_package_inventory_sha256(export_a / "package")
            for key in ("serverMapRelativePath", "clientMapRelativePath"):
                old_package = configuration[key]
                self.assertIn(legacy_address, old_package)
                new_package = old_package.replace(legacy_address, native_address)
                old_root = target / Path(old_package).parent
                new_root = target / Path(new_package).parent
                self.assertFalse(new_root.exists())
                if key == "serverMapRelativePath":
                    old_root.rename(new_root)
                else:
                    shutil.copytree(old_root, new_root)
                configuration[key] = new_package
            project_support.write_json(configuration_path, configuration)
            corrected_a = project_support.tree_bytes(target, installation)

            project_support.change_working_terrain(project)
            saved = self.run_cli("save-project", "--project", project)
            self.assertEqual(0, saved.returncode, saved.stderr)
            exported_b = self.run_cli("export-adaptive", "--project", project)
            self.assertEqual(0, exported_b.returncode, exported_b.stderr)
            export_b = Path(json.loads(exported_b.stdout)["exportDirectory"])

            imported_b = self.run_reviewed_apply(
                "import-adaptive", "IMPORT", "--project", project,
                "--export", export_b, "--target-root", target,
            )
            self.assertEqual(0, imported_b.returncode, imported_b.stderr)
            self.assertNotEqual(corrected_a, project_support.tree_bytes(target, installation))

            undone_b = self.run_reviewed_apply(
                "undo-adaptive", "UNDO", "--project", project,
                "--target-root", target,
            )
            self.assertEqual(0, undone_b.returncode, undone_b.stderr)
            self.assertEqual(corrected_a, project_support.tree_bytes(target, installation))

            undone_a = self.run_reviewed_apply(
                "undo-adaptive", "UNDO", "--project", project,
                "--target-root", target,
            )
            self.assertEqual(0, undone_a.returncode, undone_a.stderr)
            self.assertEqual(target_before, project_support.tree_bytes(target, installation))

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
            project_support.write_json(payload_path, payload)
            declaration["encoding"] = "layered-world-placements-v4"
            declaration["sha256"] = hashlib.sha256(payload_path.read_bytes()).hexdigest()
        project_support.write_json(manifest_path, manifest)

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
        project_support.write_json(manifest_path, manifest)

    def set_fixture_ground_overlay(self, package: Path, overlay: int) -> None:
        manifest_path = package / "manifest.json"
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        declaration = manifest["terrainSectors"][0]
        payload_path = package / declaration["path"]
        payload = bytearray(payload_path.read_bytes())
        overlay_offset = (
            3 if declaration["encoding"] == "raw-layered-sector-v2-u16" else 2
        )
        payload[overlay_offset] = overlay
        payload_path.write_bytes(payload)
        declaration["sha256"] = hashlib.sha256(payload).hexdigest()
        project_support.write_json(manifest_path, manifest)

    @staticmethod
    def add_client_upgrade_source(client_root):
        source = client_root / "src/orsc"
        (source / "graphics/three").mkdir(parents=True, exist_ok=True)
        (client_root / "src/com/openrsc/client/model").mkdir(
            parents=True, exist_ok=True
        )
        (source / "Config.java").write_text(
            "package orsc; public final class Config { "
            "public static final int CLIENT_VERSION = 10052; }\n",
            encoding="utf-8",
        )
        for _, destination, _, policy, historical in (
            project_support.FIXTURE_CLIENT_SOURCES
        ):
            if policy != "replace-supported-historical":
                continue
            path = client_root / destination
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(historical)
        (source / "mudclient.java").write_text(
            """package orsc;
public final class mudclient {
    private void renderLoginScreenViewports(int tick) {
        try {
            if (!worldComponentsLoaded) loadWorldComponents();
        } catch (RuntimeException failure) {
            throw failure;
        }
    }
    private boolean worldComponentsLoaded;
    private void loadWorldComponents() {}
    private Surface getSurface() { return null; }
    private int getGameWidth() { return 512; }
    private int halfGameHeight() { return 192; }
    static final class Surface {
        void blackScreen(boolean full) {}
        void storeSpriteVert(int index, int x, int y, int width, int height) {}
    }
    static boolean targetSpecificBehavior() { return true; }
}
""",
            encoding="utf-8",
        )
        (source / "NativeLayeredTerrainChunk.java").write_bytes(
            project_support.LEGACY_NATIVE_CHUNK_SOURCE
        )
        (source / "NativeLayeredTerrainSnapshot.java").write_bytes(
            project_support.LEGACY_NATIVE_SNAPSHOT_SOURCE
        )

    def target_project(
        self, base: Path, representation="layered", install_enabled=True,
        port_evidence=False, offline_evidence=None,
        supported_encodings=(1, 2, 3, 4),
        source_placement_v4=False,
        working_elevation=None,
        working_npc_respawn=None,
        target_runtime_archives=False,
        preserved_installed_v1=False,
        preserved_installed_v2=False,
        target_build_file=False,
        target_client_build_file=False,
        missing_client_runtime=False,
        alpha58_build_guard=False,
        alpha59_managed_first=False,
        target_mutator=None,
        runtime_mutator=None,
    ):
        target = (
            self.fixtures.descriptor_fixture(str(base))
            if representation == "layered"
            else self.packed_fixtures.fixture(base)
        )
        project_support.write_json(
            target / "server/conf/world-builder/installed-runtime-capability-v3.json",
            project_support.host_runtime_capability(),
        )
        selected_configuration = json.loads(
            (target / "server/world-builder-configs/primary.json").read_text(
                encoding="utf-8"
            )
        )
        client_root = target / Path(
            selected_configuration["clientRuntimeRelativePath"]
        ).parts[0]
        for archive in (
            target / "server/core.jar",
            client_root / "Open_RSC_Client.jar",
        ):
            archive.parent.mkdir(parents=True, exist_ok=True)
            if not archive.exists():
                self.write_runtime_jar(archive, b"host-integrated-runtime")
        decoder_source = (
            target / "server/src/com/openrsc/server/net/RSCProtocolDecoder.java"
        )
        decoder_source.parent.mkdir(parents=True, exist_ok=True)
        if not decoder_source.exists():
            decoder_source.write_bytes(project_support.FIXTURE_HOST_DECODER_SOURCE)
        if source_placement_v4:
            self.upgrade_fixture_placements_to_v4(target / "server/maps/active")
            self.upgrade_fixture_placements_to_v4(target / "client/maps/active")
        if target_runtime_archives:
            self.write_runtime_jar(
                target / "server/core.jar", b"target-server-runtime"
            )
            (target / "server/myworld.conf").write_text(
                "want_sync_scene_baseline: false\ncustom_landscape: true\n",
                encoding="utf-8",
            )
            self.write_runtime_jar(
                client_root / "Open_RSC_Client.jar", b"target-client-runtime"
            )
            if missing_client_runtime:
                (client_root / "Open_RSC_Client.jar").unlink()
            self.add_client_upgrade_source(client_root)
            if target_client_build_file:
                (target / "PC_Client/lib").mkdir(parents=True, exist_ok=True)
                (client_root / "build.xml").write_text(
                    """<project name="target-client" default="compile-and-run" basedir=".">
    <property name="build" location="build"/>
    <property name="lib" location="../PC_Client/lib"/>
    <property name="jar" location="Open_RSC_Client.jar"/>
    <target name="compile">
        <delete file="${jar}"/>
        <delete dir="${build}"/>
        <mkdir dir="${build}"/>
        <jar basedir="${build}" destfile="${jar}">
            <zipgroupfileset dir="${lib}" includes="**/*.jar"/>
        </jar>
    </target>
    <target name="runclient">
        <java jar="Open_RSC_Client.jar" fork="true"/>
    </target>
    <target name="compile-and-run">
        <antcall target="compile"/>
        <antcall target="runclient"/>
    </target>
</project>
""",
                    encoding="utf-8",
                )
            if target_build_file:
                build_text = """<project name="target-server" default="compile-and-run" basedir=".">
    <target name="compile_core">
        <delete file="core.jar"/>
        <echo file="core.jar" message="legacy source rebuild"/>
    </target>
    <target name="compile_plugins">
        <javac srcdir="plugins" destdir="buildplugins">
            <classpath>
                <pathelement location="core.jar"/>
            </classpath>
        </javac>
    </target>
    <target name="runserver">
        <java classname="com.openrsc.server.Server">
            <classpath>
                <pathelement location="${lib}/*"/>
                <pathelement path="${jar}/"/>
            </classpath>
        </java>
    </target>
    <target name="runserverzgc">
        <java classname="com.openrsc.server.Server">
            <classpath>
                <pathelement location="${lib}/*"/>
                <pathelement path="${jar}/"/>
            </classpath>
        </java>
    </target>
    <target name="compile-and-run">
        <antcall target="compile_core"/>
        <antcall target="compile_plugins"/>
        <antcall target="runserver"/>
    </target>
</project>
"""
                if alpha58_build_guard:
                    build_text = build_text.replace(
                        ">\n    <target name=\"compile_core\">",
                        ">\n    <!-- Preserve the verified World Builder core.jar during target launches. -->\n"
                        "    <available file=\"conf/world-builder/installed-runtime-capability-v2.json\" "
                        "property=\"world.builder.installed.runtime\"/>\n"
                        "    <target name=\"compile_core\" "
                        "unless=\"world.builder.installed.runtime\">",
                        1,
                    )
                if alpha59_managed_first:
                    build_text = build_text.replace(
                        '                <pathelement location="core.jar"/>',
                        '                <pathelement location="lib/world-builder-managed-runtime.jar"/>\n'
                        '                <pathelement location="core.jar"/>',
                        1,
                    )
                (target / "server/build.xml").write_text(
                    build_text, encoding="utf-8"
                )
            if preserved_installed_v1:
                project_support.write_json(
                    target
                    / "server/conf/world-builder/installed-runtime-capability-v1.json",
                    project_support.installed_v1_capability(),
                )
            if preserved_installed_v2:
                project_support.write_json(
                    target
                    / "server/conf/world-builder/installed-runtime-capability-v2.json",
                    project_support.installed_v2_capability(),
                )
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
                project_support.write_json(runtime_path, evidence)
        if install_enabled and offline_evidence is not None:
            capability["install"]["offlineEvidence"] = list(offline_evidence)
        elif install_enabled and not port_evidence:
            capability["install"]["offlineEvidence"] = ["pid-file"]
        project_support.write_json(capability_path, capability)
        if target_mutator is not None:
            target_mutator(target)
        installation = target / "World Builder 2"
        installation.mkdir()
        runtime = project_support.make_runtime(base)
        if runtime_mutator is not None:
            runtime_mutator(runtime)
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
        project_support.change_working_terrain(project)
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

    def test_import_accepts_discovered_supplemental_npc_registry(self):
        with tempfile.TemporaryDirectory(prefix="adaptive-import-supplemental-npcs-") as temp:
            def add_supplemental_npcs(target):
                definitions = target / "server/conf/server/defs"
                project_support.write_json(
                    definitions / "QuestGreenDragonNpcDefs.json",
                    {"npcs": [{"id": 1, "name": "Quest green dragon"}]},
                )
                project_support.write_json(
                    definitions / "StandardGreenDragonNpcDefs.json",
                    {"npcs": [{"id": 2, "name": "Green dragon"}]},
                )

            target, installation, project, export = self.target_project(
                Path(temp), representation="packed",
                target_mutator=add_supplemental_npcs,
            )
            custom = json.loads((
                project / "source/content-bundle/files/server/conf/server/defs/"
                "NpcDefsCustom.json"
            ).read_text(encoding="utf-8"))["npcs"]
            self.assertEqual([1, 2], [row["id"] for row in custom[:2]])
            self.assertEqual(
                ["Quest green dragon", "Green dragon"],
                [row["name"] for row in custom[:2]],
            )

            imported = self.run_reviewed_apply(
                "import-adaptive", "IMPORT", "--project", project,
                "--export", export, "--target-root", target,
            )
            self.assertEqual(0, imported.returncode, imported.stderr)

    def test_import_mutates_only_map_and_owned_activation_state(self):
        with tempfile.TemporaryDirectory(prefix="adaptive-narrow-import-") as temp:
            target, installation, project, export = self.target_project(
                Path(temp), target_runtime_archives=True, target_build_file=True,
                target_client_build_file=True,
            )
            custom_plugin = target / "server/plugins/custom-game-content.jar"
            custom_plugin.parent.mkdir(parents=True, exist_ok=True)
            custom_plugin.write_bytes(b"preserve target-owned game content\n")
            before = project_support.tree_bytes(target, installation)

            imported = self.run_reviewed_apply(
                "import-adaptive", "IMPORT", "--project", project,
                "--export", export, "--target-root", target,
            )
            self.assertEqual(0, imported.returncode, imported.stderr)
            result = json.loads(imported.stdout)
            plan = json.loads((
                project / "backups" / result["transactionId"] / "mutation-plan.json"
            ).read_text(encoding="utf-8"))
            allowed_exact = {
                "server/world-builder-configs/primary.json",
                "server/world-builder-configs/installed-server.json",
                "client/world-builder-configs/installed-client.json",
                "Client_Base/world-builder-configs/installed-client.json",
            }
            for action in plan["actions"]:
                destination = action["destinationRelativePath"]
                self.assertTrue(
                    destination in allowed_exact
                    or destination.startswith("server/world-builder/packages/")
                    or destination.startswith("client/world-builder/packages/")
                    or destination.startswith("Client_Base/world-builder/packages/"),
                    destination,
                )
            self.assertEqual(before["server/core.jar"], project_support.tree_bytes(
                target, installation
            )["server/core.jar"])
            self.assertEqual(
                b"preserve target-owned game content\n", custom_plugin.read_bytes()
            )
            self.assertNotIn(
                "server/world-builder-runtime/world-builder-managed-runtime.jar",
                project_support.tree_bytes(target, installation),
            )

            undone = self.run_reviewed_apply(
                "undo-adaptive", "UNDO", "--project", project,
                "--target-root", target,
            )
            self.assertEqual(0, undone.returncode, undone.stderr)
            self.assertEqual(before, project_support.tree_bytes(target, installation))

    def test_import_rejects_retired_shadow_runtime_before_mutation(self):
        with tempfile.TemporaryDirectory(prefix="adaptive-shadow-runtime-") as temp:
            target, installation, project, export = self.target_project(Path(temp))
            retired = (
                target
                / "server/world-builder-runtime/world-builder-managed-runtime.jar"
            )
            retired.parent.mkdir(parents=True)
            with zipfile.ZipFile(retired, "w") as archive:
                for name in (
                    "Player", "Skills", "Inventory", "World", "Mob", "Npc",
                    "ActionSender", "OpcodeOut",
                ):
                    archive.writestr(f"com/openrsc/server/{name}.class", b"stale")
            before = project_support.tree_bytes(target, installation)

            refused = self.run_cli(
                "import-adaptive", "--project", project,
                "--export", export, "--target-root", target,
            )
            self.assertEqual(3, refused.returncode, refused.stderr)
            self.assertIn("RUNTIME_UPGRADE_REQUIRED", refused.stderr)
            self.assertIn("retired class-shadowing runtime", refused.stderr)
            self.assertIn("Player, Skills, Inventory, World", refused.stderr)
            self.assertEqual(before, project_support.tree_bytes(target, installation))

    def test_explicit_runtime_upgrade_repairs_affected_backup_then_imports_map(self):
        with tempfile.TemporaryDirectory(prefix="adaptive-explicit-runtime-upgrade-") as temp:
            def make_affected(target):
                retired = (
                    target
                    / "server/world-builder-runtime/world-builder-managed-runtime.jar"
                )
                retired.parent.mkdir(parents=True)
                with zipfile.ZipFile(retired, "w") as archive:
                    for name in (
                        "Player", "Skills", "Inventory", "World", "Mob", "Npc",
                        "ActionSender", "OpcodeOut",
                    ):
                        archive.writestr(f"com/openrsc/server/{name}.class", b"stale")
                (target / "server/core.jar").write_bytes(b"affected-old-core\n")
                (target / (
                    "server/src/com/openrsc/server/net/RSCProtocolDecoder.java"
                )).write_bytes(project_support.FIXTURE_HOST_DECODER_LEGACY_SOURCE)
                (target / "server/plugins.jar").write_bytes(
                    b"affected-target-plugins\n"
                )
                selected = json.loads((
                    target / "server/world-builder-configs/primary.json"
                ).read_text(encoding="utf-8"))
                client_root = target / Path(
                    selected["clientRuntimeRelativePath"]
                ).parts[0]
                (client_root / "Open_RSC_Client.jar").write_bytes(
                    b"affected-old-client\n"
                )
                (target / "server/plugins/custom-game-content.jar").parent.mkdir(
                    parents=True, exist_ok=True
                )
                (target / "server/plugins/custom-game-content.jar").write_bytes(
                    b"target-authored-plugin\n"
                )
                (target / "server/inc/sqlite/live.db").parent.mkdir(
                    parents=True, exist_ok=True
                )
                (target / "server/inc/sqlite/live.db").write_bytes(
                    b"target-player-data\n"
                )
                (target / "server/src/TargetCustomization.java").parent.mkdir(
                    parents=True, exist_ok=True
                )
                (target / "server/src/TargetCustomization.java").write_text(
                    "final class TargetCustomization {}\n", encoding="utf-8"
                )
                (target / "server/build.xml").write_text(
                    "<project name=\"affected-target-build\"/>\n", encoding="utf-8"
                )

            target, installation, project, export = self.target_project(
                Path(temp), target_mutator=make_affected,
            )
            selected = json.loads((
                target / "server/world-builder-configs/primary.json"
            ).read_text(encoding="utf-8"))
            client_root = target / Path(
                selected["clientRuntimeRelativePath"]
            ).parts[0]
            preserved = {
                relative: (target / relative).read_bytes()
                for relative in (
                    "server/plugins/custom-game-content.jar",
                    "server/plugins.jar",
                    "server/inc/sqlite/live.db",
                    "server/src/TargetCustomization.java",
                    "server/build.xml",
                )
            }

            refused = self.run_cli(
                "import-adaptive", "--project", project,
                "--export", export, "--target-root", target,
            )
            self.assertEqual(3, refused.returncode, refused.stderr)
            self.assertIn("RUNTIME_UPGRADE_REQUIRED", refused.stderr)

            upgraded = self.run_reviewed_apply(
                "upgrade-target-runtime", "UPGRADE", "--project", project,
                "--export", export, "--target-root", target,
            )
            self.assertEqual(0, upgraded.returncode, upgraded.stderr)
            result = json.loads(upgraded.stdout)
            plan = json.loads((
                project / "backups" / result["transactionId"] / "mutation-plan.json"
            ).read_text(encoding="utf-8"))
            self.assertEqual([], plan["configurationChanges"])
            self.assertTrue(all(
                action["role"].startswith("runtime-compatibility-")
                for action in plan["actions"]
            ))
            self.assertFalse((
                target
                / "server/world-builder-runtime/world-builder-managed-runtime.jar"
            ).exists())
            self.assertEqual(
                (project / "working/runtime/server/core.jar").read_bytes(),
                (target / "server/core.jar").read_bytes(),
            )
            self.assertEqual(
                (project / "working/runtime/client/Open_RSC_Client.jar").read_bytes(),
                (client_root / "Open_RSC_Client.jar").read_bytes(),
            )
            self.assertEqual(
                project_support.FIXTURE_HOST_DECODER_SOURCE,
                (target / (
                    "server/src/com/openrsc/server/net/RSCProtocolDecoder.java"
                )).read_bytes(),
            )
            for relative, expected in preserved.items():
                self.assertEqual(expected, (target / relative).read_bytes(), relative)

            imported = self.run_reviewed_apply(
                "import-adaptive", "IMPORT", "--project", project,
                "--export", export, "--target-root", target,
            )
            self.assertEqual(0, imported.returncode, imported.stderr)
            for relative, expected in preserved.items():
                self.assertEqual(expected, (target / relative).read_bytes(), relative)
            self.assert_no_transaction_stage(target)

    def test_explicit_runtime_upgrade_failure_restores_affected_backup(self):
        with tempfile.TemporaryDirectory(prefix="adaptive-runtime-upgrade-rollback-") as temp:
            def make_affected(target):
                retired = (
                    target
                    / "server/world-builder-runtime/world-builder-managed-runtime.jar"
                )
                retired.parent.mkdir(parents=True)
                retired.write_bytes(b"affected-shadow-runtime\n")
                (target / "server/core.jar").write_bytes(b"affected-core\n")
                (target / (
                    "server/src/com/openrsc/server/net/RSCProtocolDecoder.java"
                )).write_bytes(project_support.FIXTURE_HOST_DECODER_LEGACY_SOURCE)
                selected = json.loads((
                    target / "server/world-builder-configs/primary.json"
                ).read_text(encoding="utf-8"))
                client = target / Path(
                    selected["clientRuntimeRelativePath"]
                ).parts[0] / "Open_RSC_Client.jar"
                client.write_bytes(b"affected-client\n")

            target, installation, project, export = self.target_project(
                Path(temp), target_mutator=make_affected,
            )
            before = project_support.tree_bytes(target, installation)
            failed = self.run_failure(
                "runtime-upgrade", "before-success-receipt",
                project, target, export,
            )
            self.assertEqual(3, failed.returncode, failed.stderr)
            self.assertEqual(before, project_support.tree_bytes(target, installation))
            receipts = [
                json.loads(path.read_text(encoding="utf-8"))
                for path in (project / "receipts").glob("*.json")
            ]
            self.assertEqual(["rolled-back"], [item["status"] for item in receipts])
            self.assert_no_transaction_stage(target)

    def test_import_refuses_recompiled_login_decoder_regression(self):
        with tempfile.TemporaryDirectory(prefix="adaptive-login-rebuild-repair-") as temp:
            def make_affected(target):
                retired = (
                    target
                    / "server/world-builder-runtime/world-builder-managed-runtime.jar"
                )
                retired.parent.mkdir(parents=True)
                retired.write_bytes(b"retired-shadow\n")
                (target / (
                    "server/src/com/openrsc/server/net/RSCProtocolDecoder.java"
                )).write_bytes(project_support.FIXTURE_HOST_DECODER_LEGACY_SOURCE)

            target, installation, project, export = self.target_project(
                Path(temp), target_mutator=make_affected,
            )
            upgraded = self.run_reviewed_apply(
                "upgrade-target-runtime", "UPGRADE", "--project", project,
                "--export", export, "--target-root", target,
            )
            self.assertEqual(0, upgraded.returncode, upgraded.stderr)

            # Reproduce the incident: a target startup rebuild emits a readable
            # core with the World Builder boot classes but an old decoder.
            core = target / "server/core.jar"
            with zipfile.ZipFile(core, "w") as archive:
                archive.writestr(
                    "com/openrsc/server/io/WorldBuilderInstalledServerProfile.class",
                    b"rebuilt-host",
                )
                archive.writestr(
                    "com/openrsc/server/io/NativeLayeredWorldPackage.class",
                    b"rebuilt-host",
                )
                archive.writestr(
                    "com/openrsc/server/net/RSCProtocolDecoder.class",
                    b"legacy-decoder-without-framing-guard",
                )
            before_refusal = project_support.tree_bytes(target, installation)
            refused = self.run_cli(
                "import-adaptive", "--project", project,
                "--export", export, "--target-root", target,
            )
            self.assertEqual(3, refused.returncode, refused.stderr)
            self.assertIn("TARGET_DRIFT", refused.stderr)
            self.assertIn("server/core.jar", refused.stderr)
            self.assertIn("run Upgrade Target Runtime again", refused.stderr)
            self.assertEqual(
                before_refusal, project_support.tree_bytes(target, installation)
            )

    def test_runtime_upgrade_refuses_conflicting_login_decoder_source(self):
        with tempfile.TemporaryDirectory(prefix="adaptive-login-source-conflict-") as temp:
            def customize_decoder(target):
                (target / (
                    "server/src/com/openrsc/server/net/RSCProtocolDecoder.java"
                )).write_bytes(
                    b"package com.openrsc.server.net;\n"
                    b"public final class RSCProtocolDecoder {\n"
                    b"  void ownerCustomizedDecoder() {}\n"
                    b"}\n"
                )

            target, installation, project, export = self.target_project(
                Path(temp), target_mutator=customize_decoder,
            )
            before = project_support.tree_bytes(target, installation)
            refused = self.run_cli(
                "upgrade-target-runtime", "--project", project,
                "--export", export, "--target-root", target,
            )
            self.assertEqual(3, refused.returncode, refused.stderr)
            self.assertIn("unsupported or conflicting modification", refused.stderr)
            self.assertEqual(before, project_support.tree_bytes(target, installation))

    def test_import_requires_host_integrated_runtime_before_mutation(self):
        with tempfile.TemporaryDirectory(prefix="adaptive-runtime-required-") as temp:
            def remove_host_capability(target):
                (target / (
                    "server/conf/world-builder/installed-runtime-capability-v3.json"
                )).unlink()

            target, installation, project, export = self.target_project(
                Path(temp), target_mutator=remove_host_capability,
            )
            before = project_support.tree_bytes(target, installation)

            refused = self.run_cli(
                "import-adaptive", "--project", project,
                "--export", export, "--target-root", target,
            )
            self.assertEqual(3, refused.returncode, refused.stderr)
            self.assertIn("RUNTIME_UPGRADE_REQUIRED", refused.stderr)
            self.assertIn("run Upgrade Target Runtime", refused.stderr)
            self.assertEqual(before, project_support.tree_bytes(target, installation))

    def test_import_refuses_mismatched_host_runtime_capability(self):
        with tempfile.TemporaryDirectory(prefix="adaptive-runtime-mismatch-") as temp:
            def change_host_capability(target):
                path = target / (
                    "server/conf/world-builder/installed-runtime-capability-v3.json"
                )
                capability = json.loads(path.read_text(encoding="utf-8"))
                capability["serverBuildId"] = "different-host-build"
                project_support.write_json(path, capability)

            target, installation, project, export = self.target_project(
                Path(temp), target_mutator=change_host_capability,
            )
            before = project_support.tree_bytes(target, installation)

            refused = self.run_cli(
                "import-adaptive", "--project", project,
                "--export", export, "--target-root", target,
            )
            self.assertEqual(3, refused.returncode, refused.stderr)
            self.assertIn("RUNTIME_UPGRADE_REQUIRED", refused.stderr)
            self.assertIn("differs from the pinned project runtime", refused.stderr)
            self.assertEqual(before, project_support.tree_bytes(target, installation))

    def test_import_preserves_v1_metadata_across_repeated_map_updates(self):
        with tempfile.TemporaryDirectory(prefix="adaptive-runtime-v1-upgrade-") as temp:
            target, _, project, export = self.target_project(
                Path(temp), target_runtime_archives=True,
                preserved_installed_v1=True,
                target_build_file=True,
                target_client_build_file=True,
            )
            server = target / "server/core.jar"
            client = target / "client/Open_RSC_Client.jar"
            if not client.is_file():
                client = target / "Client_Base/Open_RSC_Client.jar"
            before_server = server.read_bytes()
            before_client = client.read_bytes()
            capability = (
                target
                / "server/conf/world-builder/installed-runtime-capability-v1.json"
            )
            unrelated = target / "server/plugins/custom-game-content.jar"
            unrelated.parent.mkdir(parents=True, exist_ok=True)
            unrelated.write_bytes(b"target-owned game content\n")
            client_build_file = client.parent / "build.xml"
            unguarded_client_build = client_build_file.read_text(encoding="utf-8")
            guarded_client_build = unguarded_client_build.replace(
                '<project name="target-client" default="compile-and-run" basedir=".">',
                '<project name="target-client" default="compile-and-run" basedir=".">\n'
                '    <!-- Preserve the verified World Builder client runtime during target launches. -->\n'
                '    <available file="world-builder-configs/installed-client.json" '
                'property="world.builder.installed.client"/>',
            ).replace(
                '<target name="compile">',
                '<target name="compile" unless="world.builder.installed.client">',
            )
            client_build_file.write_text(guarded_client_build, encoding="utf-8")

            preview = self.run_cli(
                "import-adaptive", "--project", project, "--export", export,
                "--target-root", target,
            )
            self.assertEqual(0, preview.returncode, preview.stderr)
            compatibility_roles = {
                action["role"] for action in json.loads(preview.stdout)["actions"]
                if action["role"].startswith("runtime-compatibility-")
            }
            self.assertEqual(
                {
                    "runtime-compatibility-client-profile",
                    "runtime-compatibility-server-profile",
                },
                compatibility_roles,
            )
            imported = self.run_reviewed_apply(
                "import-adaptive", "IMPORT", "--project", project,
                "--export", export, "--target-root", target, preview=preview,
            )
            self.assertEqual(0, imported.returncode, imported.stderr)
            self.assertEqual(before_server, server.read_bytes())
            self.assertEqual(before_client, client.read_bytes())
            self.assertTrue(capability.exists())
            installed_v2 = (
                target
                / "server/conf/world-builder/installed-runtime-capability-v2.json"
            )
            self.assertFalse(installed_v2.exists())
            self.assertFalse((
                target
                / "server/world-builder-runtime/world-builder-managed-runtime.jar"
            ).exists())
            guarded_build = (target / "server/build.xml").read_bytes()
            upgraded_client_build = client_build_file.read_bytes()
            client_build_root = ET.fromstring(upgraded_client_build)
            self.assertEqual(
                "world.builder.installed.client",
                client_build_root.find("./target[@name='compile']").attrib["unless"],
            )
            self.assertIsNotNone(client_build_root.find("./available"))
            self.assertTrue(
                (client.parent / "world-builder-configs/installed-client.json").is_file()
            )
            self.assertEqual(b"target-owned game content\n", unrelated.read_bytes())

            project_support.change_working_terrain(project)
            saved = self.run_cli("save-project", "--project", project)
            self.assertEqual(0, saved.returncode, saved.stderr)
            exported = self.run_cli("export-adaptive", "--project", project)
            self.assertEqual(0, exported.returncode, exported.stderr)
            second_export = Path(json.loads(exported.stdout)["exportDirectory"])
            repeated = self.run_reviewed_apply(
                "import-adaptive", "IMPORT", "--project", project,
                "--export", second_export, "--target-root", target,
            )
            self.assertEqual(0, repeated.returncode, repeated.stderr)
            self.assertEqual(before_server, server.read_bytes())
            self.assertEqual(before_client, client.read_bytes())
            self.assertTrue(capability.exists())
            self.assertFalse(installed_v2.exists())
            self.assertEqual(guarded_build, (target / "server/build.xml").read_bytes())
            self.assertEqual(
                upgraded_client_build,
                (client.parent / "build.xml").read_bytes(),
            )
            self.assertEqual(b"target-owned game content\n", unrelated.read_bytes())

    def test_import_refuses_target_with_missing_client_runtime(self):
        with tempfile.TemporaryDirectory(prefix="adaptive-client-repair-") as temp:
            target, _, project, export = self.target_project(
                Path(temp), target_runtime_archives=True,
                preserved_installed_v2=True, target_client_build_file=True,
                missing_client_runtime=True,
            )
            client_root = target / "client"
            if not client_root.is_dir():
                client_root = target / "Client_Base"
            client = client_root / "Open_RSC_Client.jar"
            self.assertFalse(client.exists())
            before = project_support.tree_bytes(target)

            refused = self.run_cli(
                "import-adaptive", "--project", project,
                "--export", export, "--target-root", target,
            )
            self.assertEqual(3, refused.returncode, refused.stderr)
            self.assertIn("RUNTIME_UPGRADE_REQUIRED", refused.stderr)
            self.assertIn("client runtime is missing", refused.stderr)
            self.assertEqual(before, project_support.tree_bytes(target))

    def test_import_preserves_custom_client_json_dependency(self):
        with tempfile.TemporaryDirectory(prefix="adaptive-client-json-drift-") as temp:
            target, _, project, export = self.target_project(
                Path(temp), target_runtime_archives=True,
                target_client_build_file=True,
            )
            dependency = target / "PC_Client/lib/json-20190722.jar"
            dependency.write_bytes(b"target-specific incompatible JSON library\n")
            before = project_support.tree_bytes(target)

            preview = self.run_cli(
                "import-adaptive", "--project", project, "--export", export,
                "--target-root", target,
            )
            self.assertEqual(0, preview.returncode, preview.stderr)
            self.assertEqual(before, project_support.tree_bytes(target))
            imported = self.run_reviewed_apply(
                "import-adaptive", "IMPORT", "--project", project,
                "--export", export, "--target-root", target, preview=preview,
            )
            self.assertEqual(0, imported.returncode, imported.stderr)
            self.assertEqual(
                b"target-specific incompatible JSON library\n",
                dependency.read_bytes(),
            )

    def test_import_preserves_custom_client_protocol_source(self):
        with tempfile.TemporaryDirectory(prefix="adaptive-client-elevation-drift-") as temp:
            target, _, project, export = self.target_project(
                Path(temp), target_runtime_archives=True,
                target_client_build_file=True,
            )
            client_root = target / "client"
            if not client_root.is_dir():
                client_root = target / "Client_Base"
            chunk = client_root / "src/orsc/NativeLayeredTerrainChunk.java"
            source = chunk.read_text(encoding="utf-8")
            source += "// unrecognized target protocol customization\n"
            chunk.write_text(source, encoding="utf-8")
            before = project_support.tree_bytes(target)

            preview = self.run_cli(
                "import-adaptive", "--project", project, "--export", export,
                "--target-root", target,
            )
            self.assertEqual(0, preview.returncode, preview.stderr)
            self.assertEqual(before, project_support.tree_bytes(target))
            imported = self.run_reviewed_apply(
                "import-adaptive", "IMPORT", "--project", project,
                "--export", export, "--target-root", target, preview=preview,
            )
            self.assertEqual(0, imported.returncode, imported.stderr)
            self.assertEqual(source, chunk.read_text(encoding="utf-8"))

    def test_host_runtime_imports_blocking_base_color_without_v1_replacement(self):
        with tempfile.TemporaryDirectory(prefix="adaptive-runtime-v1-overlay-255-") as temp:
            target, installation, project, _ = self.target_project(
                Path(temp), target_runtime_archives=True,
                preserved_installed_v1=True,
            )
            self.set_fixture_ground_overlay(
                project / "working/layered-world/package", 255
            )
            saved = self.run_cli("save-project", "--project", project)
            self.assertEqual(0, saved.returncode, saved.stderr)
            exported = self.run_cli("export-adaptive", "--project", project)
            self.assertEqual(0, exported.returncode, exported.stderr)
            export = Path(json.loads(exported.stdout)["exportDirectory"])
            imported = self.run_reviewed_apply(
                "import-adaptive", "IMPORT", "--project", project,
                "--export", export, "--target-root", target,
            )
            self.assertEqual(0, imported.returncode, imported.stderr)
            self.assertFalse((
                target
                / "server/world-builder-runtime/world-builder-managed-runtime.jar"
            ).exists())
            self.assertTrue((
                target
                / "server/conf/world-builder/installed-runtime-capability-v1.json"
            ).is_file())
            self.assertFalse((
                target
                / "server/conf/world-builder/installed-runtime-capability-v2.json"
            ).exists())
            self.assertTrue((
                target
                / "server/conf/world-builder/installed-runtime-capability-v3.json"
            ).is_file())

    def test_import_preserves_v2_metadata_and_runtime_archives(self):
        with tempfile.TemporaryDirectory(prefix="adaptive-runtime-v2-upgrade-") as temp:
            target, _, project, _ = self.target_project(
                Path(temp), target_runtime_archives=True,
                preserved_installed_v1=True,
                preserved_installed_v2=True,
            )
            server = target / "server/core.jar"
            client = target / "client/Open_RSC_Client.jar"
            if not client.is_file():
                client = target / "Client_Base/Open_RSC_Client.jar"
            capability = (
                target
                / "server/conf/world-builder/installed-runtime-capability-v2.json"
            )
            before_server = server.read_bytes()
            before_client = client.read_bytes()

            self.set_fixture_ground_overlay(
                project / "working/layered-world/package", 255
            )
            saved = self.run_cli("save-project", "--project", project)
            self.assertEqual(0, saved.returncode, saved.stderr)
            exported = self.run_cli("export-adaptive", "--project", project)
            self.assertEqual(0, exported.returncode, exported.stderr)
            export = Path(json.loads(exported.stdout)["exportDirectory"])

            preview = self.run_cli(
                "import-adaptive", "--project", project, "--export", export,
                "--target-root", target,
            )
            self.assertEqual(0, preview.returncode, preview.stderr)
            compatibility_roles = {
                action["role"] for action in json.loads(preview.stdout)["actions"]
                if action["role"].startswith("runtime-compatibility-")
            }
            self.assertEqual(
                {
                    "runtime-compatibility-client-profile",
                    "runtime-compatibility-server-profile",
                },
                compatibility_roles,
            )

            imported = self.run_reviewed_apply(
                "import-adaptive", "IMPORT", "--project", project,
                "--export", export, "--target-root", target, preview=preview,
            )
            self.assertEqual(0, imported.returncode, imported.stderr)
            self.assertEqual(before_server, server.read_bytes())
            self.assertFalse((
                target
                / "server/world-builder-runtime/world-builder-managed-runtime.jar"
            ).exists())
            self.assertEqual(before_client, client.read_bytes())
            self.assertEqual(
                project_support.installed_v2_capability(),
                json.loads(capability.read_text(encoding="utf-8")),
            )

            project_support.change_working_terrain(project)
            saved = self.run_cli("save-project", "--project", project)
            self.assertEqual(0, saved.returncode, saved.stderr)
            exported = self.run_cli("export-adaptive", "--project", project)
            self.assertEqual(0, exported.returncode, exported.stderr)
            second_export = Path(json.loads(exported.stdout)["exportDirectory"])
            repeated = self.run_reviewed_apply(
                "import-adaptive", "IMPORT", "--project", project,
                "--export", second_export, "--target-root", target,
            )
            self.assertEqual(0, repeated.returncode, repeated.stderr)
            self.assertEqual(before_server, server.read_bytes())
            self.assertEqual(before_client, client.read_bytes())

    def test_import_preserves_custom_host_runtime_archives(self):
        with tempfile.TemporaryDirectory(prefix="adaptive-runtime-v1-v2-") as temp:
            target, _, project, _ = self.target_project(
                Path(temp), target_runtime_archives=True,
                preserved_installed_v1=True,
            )
            server = target / "server/core.jar"
            client = target / "client/Open_RSC_Client.jar"
            if not client.is_file():
                client = target / "Client_Base/Open_RSC_Client.jar"
            self.write_runtime_jar(
                server, b"custom target loader-v7 server runtime\n"
            )
            self.write_runtime_jar(
                client, b"custom target loader-v7 client runtime\n"
            )
            before_server = server.read_bytes()
            before_client = client.read_bytes()
            capability = (
                target
                / "server/conf/world-builder/installed-runtime-capability-v2.json"
            )
            project_support.write_json(
                capability, project_support.installed_v2_capability()
            )

            self.set_fixture_ground_overlay(
                project / "working/layered-world/package", 255
            )
            saved = self.run_cli("save-project", "--project", project)
            self.assertEqual(0, saved.returncode, saved.stderr)
            exported = self.run_cli("export-adaptive", "--project", project)
            self.assertEqual(0, exported.returncode, exported.stderr)
            export = Path(json.loads(exported.stdout)["exportDirectory"])

            preview = self.run_cli(
                "import-adaptive", "--project", project, "--export", export,
                "--target-root", target,
            )
            self.assertEqual(0, preview.returncode, preview.stderr)
            compatibility_roles = {
                action["role"] for action in json.loads(preview.stdout)["actions"]
                if action["role"].startswith("runtime-compatibility-")
            }
            self.assertEqual(
                {
                    "runtime-compatibility-client-profile",
                    "runtime-compatibility-server-profile",
                },
                compatibility_roles,
            )
            imported = self.run_reviewed_apply(
                "import-adaptive", "IMPORT", "--project", project,
                "--export", export, "--target-root", target, preview=preview,
            )
            self.assertEqual(0, imported.returncode, imported.stderr)
            self.assertEqual(before_server, server.read_bytes())
            self.assertEqual(before_client, client.read_bytes())

    def test_import_writes_only_host_activation_profiles_before_map_selection(self):
        with tempfile.TemporaryDirectory(prefix="adaptive-runtime-bootstrap-") as temp:
            target, installation, project, export = self.target_project(
                Path(temp), representation="packed", supported_encodings=(1,),
                target_runtime_archives=True,
            )
            capability_path = target / "server/world-builder-capabilities.json"
            before_capability = capability_path.read_bytes()

            preview = self.run_cli(
                "import-adaptive", "--project", project, "--export", export,
                "--target-root", target,
            )
            self.assertEqual(0, preview.returncode, preview.stderr)
            plan = json.loads(preview.stdout)
            canonical_address = json.loads(
                (export / "manifest.json").read_text(encoding="utf-8")
            )["packageFingerprintSha256"]
            server_map_change = next(
                change for change in plan["configurationChanges"]
                if change["key"] == "serverMapRelativePath"
            )
            self.assertEqual(
                f"server/world-builder/packages/{canonical_address}/package",
                server_map_change["afterValue"],
            )
            compatibility = {
                action["role"]: action for action in plan["actions"]
                if action["role"].startswith("runtime-compatibility-")
            }
            self.assertEqual(
                {
                    "runtime-compatibility-client-profile",
                    "runtime-compatibility-server-profile",
                },
                set(compatibility),
            )
            self.assertEqual(
                "server/world-builder-configs/installed-server.json",
                compatibility["runtime-compatibility-server-profile"][
                    "destinationRelativePath"
                ],
            )
            self.assertEqual(
                "client/world-builder-configs/installed-client.json",
                compatibility["runtime-compatibility-client-profile"][
                    "destinationRelativePath"
                ],
            )
            self.assertEqual(
                before_capability,
                capability_path.read_bytes(),
                "preview must not change target capability evidence",
            )

            imported = self.run_reviewed_apply(
                "import-adaptive", "IMPORT", "--project", project,
                "--export", export, "--target-root", target, preview=preview,
            )
            self.assertEqual(0, imported.returncode, imported.stderr)
            capability = json.loads(
                (
                    target
                    / "server/conf/world-builder/installed-runtime-capability-v3.json"
                ).read_text(encoding="utf-8")
            )
            self.assertEqual([1, 2, 3, 4], capability["encodingVersions"])
            self.assertEqual(
                "rsc-world-editor-runtime-host-server-v2",
                capability["serverBuildId"],
            )
            self.assertEqual(
                "rsc-world-editor-runtime-host-client-v1",
                capability["clientBuildId"],
            )
            configuration = json.loads(
                (target / "server/world-builder-configs/primary.json").read_text(
                    encoding="utf-8"
                )
            )
            self.assertEqual("layered", configuration["representation"])
            self.assertTrue((target / configuration["serverMapRelativePath"]).is_dir())
            self.assertTrue((target / configuration["clientMapRelativePath"]).is_dir())
            client_profile = json.loads((
                target / "client/world-builder-configs/installed-client.json"
            ).read_text(encoding="utf-8"))
            self.assertTrue(client_profile["active"])
            self.assertEqual(
                configuration["clientMapRelativePath"],
                "client/" + client_profile["packageRelativePath"],
            )

            project_support.change_working_terrain(project)
            saved = self.run_cli("save-project", "--project", project)
            self.assertEqual(0, saved.returncode, saved.stderr)
            exported = self.run_cli("export-adaptive", "--project", project)
            self.assertEqual(0, exported.returncode, exported.stderr)
            second_export = Path(json.loads(exported.stdout)["exportDirectory"])
            second = self.run_reviewed_apply(
                "import-adaptive", "IMPORT", "--project", project,
                "--export", second_export, "--target-root", target,
            )
            self.assertEqual(0, second.returncode, second.stderr)

    def test_imported_packed_target_can_be_detected_and_adopted_from_nested_installation(self):
        with tempfile.TemporaryDirectory(prefix="adaptive-imported-redetection-") as temp:
            base = Path(temp)
            target, _, project, export = self.target_project(
                base / "first", representation="packed",
                target_runtime_archives=True,
            )
            imported = self.run_reviewed_apply(
                "import-adaptive", "IMPORT", "--project", project,
                "--export", export, "--target-root", target,
            )
            self.assertEqual(0, imported.returncode, imported.stderr)

            installed_target = base / "installed-target"
            shutil.copytree(
                target, installed_target,
                ignore=shutil.ignore_patterns("World Builder 2"),
            )
            client_root = installed_target / "client"
            if not client_root.is_dir():
                client_root = installed_target / "Client_Base"
            downgraded_sources = []
            for index in (3, 4):
                _, destination, current, policy, historical = (
                    project_support.FIXTURE_CLIENT_SOURCES[index]
                )
                self.assertEqual("replace-supported-historical", policy)
                source = client_root / destination
                self.assertEqual(historical, source.read_bytes())
                downgraded_sources.append((source, current))
            selected_content_configuration = installed_target / "server/myworld.conf"
            alternate_content_configuration = (
                installed_target / "server/myworld-host.conf"
            )
            shutil.copy2(
                selected_content_configuration, alternate_content_configuration
            )
            # Keep the installation below the server tree so its creation stage
            # temporarily contains a second copy of the approved .conf evidence.
            # Creation must verify the approved inventory, not rediscover its own
            # project files as another target configuration.
            installation = installed_target / "server/World Builder 2"
            installation.mkdir()
            runtime = project_support.make_runtime(base / "second")
            discovery = self.run_cli(
                "discover-adaptive", "--target-root", installed_target,
                "--configuration-role", "server/myworld.conf",
            )
            self.assertEqual(0, discovery.returncode, discovery.stderr)
            report = base / "installed-discovery.json"
            report.write_text(discovery.stdout, encoding="utf-8")
            created = self.run_cli(
                "create-project",
                "--installation-root", installation,
                "--runtime-root", runtime,
                "--target-root", installed_target,
                "--discovery-report", report,
                "--display-name", "Imported packed target",
                "--port", "43894",
                "--confirm", "CREATE",
            )
            self.assertEqual(0, created.returncode, created.stderr)
            reopened = self.run_cli(
                "open-project",
                "--installation-root", installation,
                "--target-root", installed_target,
            )
            self.assertEqual(0, reopened.returncode, reopened.stderr)
            self.assertEqual("ready-attached", json.loads(reopened.stdout)["state"])

            fresh_project = Path(json.loads(created.stdout)["projectRoot"])
            exported = self.run_cli(
                "export-adaptive", "--project", fresh_project,
            )
            self.assertEqual(0, exported.returncode, exported.stderr)
            fresh_export = Path(json.loads(exported.stdout)["exportDirectory"])
            active_configuration = json.loads(
                (
                    installed_target
                    / "server/world-builder-configs/primary.json"
                ).read_text(encoding="utf-8")
            )
            active_manifest = (
                installed_target
                / active_configuration["serverMapRelativePath"]
                / "manifest.json"
            )
            exact_manifest = active_manifest.read_bytes()
            active_manifest.write_bytes(exact_manifest + b"\n")
            rejected = self.run_cli(
                "import-adaptive", "--project", fresh_project,
                "--export", fresh_export, "--target-root", installed_target,
            )
            self.assertEqual(3, rejected.returncode, rejected.stderr)
            self.assertIn("TARGET_DRIFT", rejected.stderr)
            for source, _ in downgraded_sources:
                self.assertNotIn(
                    b"WIDE_TILE_WIRE_BYTES = 11", source.read_bytes(),
                )
            active_manifest.write_bytes(exact_manifest)
            preview = self.run_cli(
                "import-adaptive", "--project", fresh_project,
                "--export", fresh_export, "--target-root", installed_target,
            )
            self.assertEqual(3, preview.returncode, preview.stderr)
            self.assertIn("TARGET_DRIFT", preview.stderr)
            for source, _ in downgraded_sources:
                self.assertNotIn(
                    b"WIDE_TILE_WIRE_BYTES = 11", source.read_bytes(),
                )

    def test_same_map_import_does_not_repair_incompatible_runtime(self):
        with tempfile.TemporaryDirectory(prefix="adaptive-runtime-completion-") as temp:
            target, installation, project, export = self.target_project(Path(temp))
            imported = self.run_reviewed_apply(
                "import-adaptive", "IMPORT", "--project", project,
                "--export", export, "--target-root", target,
            )
            self.assertEqual(0, imported.returncode, imported.stderr)

            server = target / "server/core.jar"
            client = target / "client/Open_RSC_Client.jar"
            if not client.parent.is_dir():
                client = target / "Client_Base/Open_RSC_Client.jar"
            self.write_runtime_jar(
                server, b"incompatible installed server runtime\n"
            )
            before_server = server.read_bytes()
            client.write_bytes(b"incompatible installed client runtime\n")
            self.add_client_upgrade_source(client.parent)
            (target / "server/myworld.conf").write_text(
                "want_sync_scene_baseline: false\ncustom_landscape: true\n",
                encoding="utf-8",
            )

            before_completion_attempt = project_support.tree_bytes(target, installation)
            completed = self.run_cli(
                "import-adaptive", "--project", project,
                "--export", export, "--target-root", target,
            )
            self.assertEqual(3, completed.returncode, completed.stderr)
            self.assertIn("RUNTIME_UPGRADE_REQUIRED", completed.stderr)
            self.assertEqual(
                before_completion_attempt,
                project_support.tree_bytes(target, installation),
            )

            undone = self.run_reviewed_apply(
                "undo-adaptive", "UNDO", "--project", project,
                "--target-root", target,
            )
            self.assertEqual(0, undone.returncode, undone.stderr)
            self.assertEqual(before_server, server.read_bytes())
            self.assertEqual(b"incompatible installed client runtime\n", client.read_bytes())
            configuration = json.loads(
                (target / "server/world-builder-configs/primary.json").read_text(
                    encoding="utf-8"
                )
            )
            self.assertTrue((target / configuration["serverMapRelativePath"]).is_dir())
            self.assertTrue((target / configuration["clientMapRelativePath"]).is_dir())

    def test_import_refuses_drift_in_inactive_historical_package(self):
        with tempfile.TemporaryDirectory(prefix="adaptive-runtime-drift-completion-") as temp:
            target, installation, project, export = self.target_project(Path(temp))
            imported = self.run_legacy_reviewed_apply(
                "import-adaptive", "IMPORT", "--project", project,
                "--export", export, "--target-root", target,
            )
            self.assertEqual(0, imported.returncode, imported.stderr)

            configuration_path = target / "server/world-builder-configs/primary.json"
            configuration = json.loads(configuration_path.read_text(encoding="utf-8"))
            legacy_address = json.loads(
                (export / "manifest.json").read_text(encoding="utf-8")
            )["packageFingerprintSha256"]
            native_address = self.native_package_inventory_sha256(export / "package")
            for key in ("serverMapRelativePath", "clientMapRelativePath"):
                old_package = configuration[key]
                new_package = old_package.replace(legacy_address, native_address)
                shutil.copytree(
                    target / Path(old_package).parent,
                    target / Path(new_package).parent,
                )
                configuration[key] = new_package
            project_support.write_json(configuration_path, configuration)

            inactive_manifest = (
                target / "server/world-builder/packages" / legacy_address
                / "package/manifest.json"
            )
            inactive_manifest.write_bytes(inactive_manifest.read_bytes() + b"\n")
            server = target / "server/core.jar"
            client = target / "client/Open_RSC_Client.jar"
            if not client.parent.is_dir():
                client = target / "Client_Base/Open_RSC_Client.jar"
            self.write_runtime_jar(
                server, b"incompatible installed server runtime\n"
            )
            client.write_bytes(b"incompatible installed client runtime\n")
            self.add_client_upgrade_source(client.parent)
            (target / "server/myworld.conf").write_text(
                "want_sync_scene_baseline: false\ncustom_landscape: true\n",
                encoding="utf-8",
            )

            before_completion_attempt = project_support.tree_bytes(target, installation)
            completed = self.run_cli(
                "import-adaptive", "--project", project,
                "--export", export, "--target-root", target,
            )
            self.assertEqual(3, completed.returncode, completed.stderr)
            self.assertIn("TARGET_DRIFT", completed.stderr)
            self.assertEqual(
                before_completion_attempt,
                project_support.tree_bytes(target, installation),
            )

    def standalone_project(self, base: Path):
        installation = base / "World Builder 2"
        installation.mkdir()
        runtime = project_support.make_runtime(base)
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
            before = project_support.tree_bytes(target, installation)
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
            self.assertEqual(before, project_support.tree_bytes(target, installation))

    def test_host_runtime_supports_current_wide_and_placement_encodings(self):
        with tempfile.TemporaryDirectory(prefix="adaptive-import-wide-loader-") as temp:
            target, installation, project, export = self.target_project(
                Path(temp), supported_encodings=(1, 3), working_elevation=300
            )
            before = project_support.tree_bytes(target, installation)
            preview = self.run_cli(
                "import-adaptive", "--project", project, "--export", export,
                "--target-root", target,
            )
            self.assertEqual(0, preview.returncode, preview.stderr)
            manifest = json.loads(
                (export / "package/manifest.json").read_text(encoding="utf-8")
            )
            self.assertEqual(
                {"raw-layered-sector-v2-u16"},
                {entry["encoding"] for entry in manifest["terrainSectors"]},
            )
            self.assertEqual(before, project_support.tree_bytes(target, installation))

        with tempfile.TemporaryDirectory(prefix="adaptive-import-old-placement-") as temp:
            target, installation, project, export = self.target_project(
                Path(temp), supported_encodings=(1, 2, 3), working_npc_respawn=30
            )
            before = project_support.tree_bytes(target, installation)
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
                {"layered-world-placements-v4"},
                {entry["encoding"] for entry in manifest["placementSets"]},
            )
            self.assertEqual(before, project_support.tree_bytes(target, installation))

    def test_export_preview_import_and_exact_undo(self):
        for representation in ("layered", "packed"):
            with self.subTest(representation=representation), tempfile.TemporaryDirectory(
                prefix=f"adaptive-transaction-{representation}-"
            ) as temp:
                target, installation, project, export = self.target_project(
                    Path(temp), representation
                )
                before = project_support.tree_bytes(target, installation)
                source_before = project_support.tree_bytes(project / "source")
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
                package_address = self.native_package_inventory_sha256(
                    export / "package"
                )
                if representation == "packed":
                    package_address = json.loads(
                        (export / "manifest.json").read_text(encoding="utf-8")
                    )["packageFingerprintSha256"]
                configured_paths = {
                    change["afterValue"]
                    for change in preview_value["configurationChanges"]
                    if change["key"] in {
                        "serverMapRelativePath", "clientMapRelativePath"
                    }
                }
                self.assertEqual(2, len(configured_paths))
                self.assertTrue(all(
                    f"/world-builder/packages/{package_address}/package" in path
                    for path in configured_paths
                ))
                self.assertEqual(before, project_support.tree_bytes(target, installation))
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
                self.assertNotEqual(before, project_support.tree_bytes(target, installation))
                self.assertEqual(source_before, project_support.tree_bytes(project / "source"))
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
                self.assertEqual(before, project_support.tree_bytes(target, installation))
                self.assertEqual(source_before, project_support.tree_bytes(project / "source"))

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
            source_before = project_support.tree_bytes(project / "source")

            # Export is project-local and must not re-read a target that has drifted.
            evidence = target / "server/evidence/render-assets.bin"
            evidence.write_bytes(evidence.read_bytes() + b"target drift")
            target_before = project_support.tree_bytes(target, installation)
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
                project_support.tree_bytes(Path(local_value["exportDirectory"])),
                project_support.tree_bytes(Path(copied_value["exportDirectory"])),
            )
            self.assertEqual(source_before, project_support.tree_bytes(project / "source"))
            self.assertEqual(target_before, project_support.tree_bytes(target, installation))

            exports_before = project_support.tree_bytes(project / "exports")
            for milestone in (
                "stage-created", "package-copied", "before-publish", "after-publish"
            ):
                with self.subTest(milestone=milestone):
                    failed = self.run_failure(
                        "export", milestone, project, target
                    )
                    self.assertNotEqual(0, failed.returncode)
                    self.assertEqual(
                        exports_before, project_support.tree_bytes(project / "exports")
                    )
                    self.assertEqual(
                        source_before, project_support.tree_bytes(project / "source")
                    )
                    self.assertEqual(
                        target_before, project_support.tree_bytes(target, installation)
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
            target_before = project_support.tree_bytes(target, installation)
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
            self.assertEqual(target_before, project_support.tree_bytes(target, installation))
            self.assertEqual(artifacts_before, self.transaction_artifacts(project))

        with tempfile.TemporaryDirectory(prefix="adaptive-import-no-loader-") as temp:
            target, installation, project, export = self.target_project(
                Path(temp), install_enabled=False
            )
            target_before = project_support.tree_bytes(target, installation)
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
            self.assertEqual(target_before, project_support.tree_bytes(target, installation))
            self.assertEqual(artifacts_before, self.transaction_artifacts(project))

        with tempfile.TemporaryDirectory(prefix="adaptive-import-source-corrupt-") as temp:
            target, installation, project, export = self.target_project(Path(temp))
            target_before = project_support.tree_bytes(target, installation)
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
            self.assertEqual(target_before, project_support.tree_bytes(target, installation))
            self.assertEqual(artifacts_before, self.transaction_artifacts(project))

        with tempfile.TemporaryDirectory(prefix="adaptive-import-export-corrupt-") as temp:
            target, installation, project, export = self.target_project(Path(temp))
            target_before = project_support.tree_bytes(target, installation)
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
            self.assertEqual(target_before, project_support.tree_bytes(target, installation))
            self.assertEqual(artifacts_before, self.transaction_artifacts(project))

    def test_offline_and_unsafe_target_evidence_refuse_without_side_effects(self):
        with tempfile.TemporaryDirectory(prefix="adaptive-import-pid-") as temp:
            target, installation, project, export = self.target_project(Path(temp))
            pid = target / "server/run/server.pid"
            pid.parent.mkdir(parents=True)
            pid.write_text("12345\n", encoding="utf-8")
            target_before = project_support.tree_bytes(target, installation)
            artifacts_before = self.transaction_artifacts(project)
            refused = self.run_cli(
                "import-adaptive", "--project", project, "--export", export,
                "--target-root", target
            )
            self.assertEqual(3, refused.returncode, refused.stderr)
            self.assertIn("OFFLINE_REQUIRED", refused.stderr)
            self.assertIn("server.pid", refused.stderr)
            self.assertEqual(target_before, project_support.tree_bytes(target, installation))
            self.assertEqual(artifacts_before, self.transaction_artifacts(project))

        with tempfile.TemporaryDirectory(prefix="adaptive-import-symlink-") as temp:
            base = Path(temp)
            target, installation, project, export = self.target_project(base)
            outside = base / "outside"
            outside.mkdir()
            (target / "server/world-builder").symlink_to(
                outside, target_is_directory=True
            )
            target_before = project_support.tree_bytes(target, installation)
            artifacts_before = self.transaction_artifacts(project)
            refused = self.run_cli(
                "import-adaptive", "--project", project, "--export", export,
                "--target-root", target
            )
            self.assertEqual(3, refused.returncode, refused.stderr)
            self.assertIn("UNSAFE_PATH", refused.stderr)
            self.assertEqual({}, project_support.tree_bytes(outside))
            self.assertEqual(target_before, project_support.tree_bytes(target, installation))
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
                target_before = project_support.tree_bytes(target, installation)
                artifacts_before = self.transaction_artifacts(project)
                refused = self.run_cli(
                    "import-adaptive", "--project", project, "--export", export,
                    "--target-root", target
                )
                self.assertEqual(3, refused.returncode, refused.stderr)
                self.assertIn("UNSAFE_PATH", refused.stderr)
                self.assertEqual(target_before, project_support.tree_bytes(target, installation))
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
            receipts_before = project_support.tree_bytes(project / "receipts")
            backups_before = project_support.tree_bytes(project / "backups")
            for command in ("import-adaptive", "recover-adaptive"):
                arguments = [command, "--project", project]
                if command == "import-adaptive":
                    arguments.extend(["--export", first_value["exportDirectory"]])
                arguments.extend(["--target-root", forbidden_target])
                refused = self.run_cli(*arguments)
                self.assertEqual(3, refused.returncode, refused.stderr)
                self.assertIn("NO_TARGET", refused.stderr)
                self.assertFalse(forbidden_target.exists())
                self.assertEqual(receipts_before, project_support.tree_bytes(project / "receipts"))
                self.assertEqual(backups_before, project_support.tree_bytes(project / "backups"))

            active = self.run_cli(
                "import-active-adaptive", "--installation-root", installation
            )
            self.assertEqual(3, active.returncode, active.stderr)
            self.assertIn("NO_TARGET", active.stderr)

    def test_stale_import_and_undo_previews_fail_closed(self):
        with tempfile.TemporaryDirectory(prefix="adaptive-import-stale-preview-") as temp:
            target, installation, project, export = self.target_project(Path(temp))
            target_before = project_support.tree_bytes(target, installation)
            artifacts_before = self.transaction_artifacts(project)
            refused = self.run_failure(
                "import-stale", "none", project, target, export
            )
            self.assertEqual(3, refused.returncode, refused.stderr)
            self.assertIn("TARGET_DRIFT", refused.stderr)
            target_after = project_support.tree_bytes(target, installation)
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
            installed_before = project_support.tree_bytes(target, installation)
            artifacts_before = self.transaction_artifacts(project)
            refused = self.run_failure("undo-stale", "none", project, target)
            self.assertEqual(3, refused.returncode, refused.stderr)
            self.assertIn("TARGET_DRIFT", refused.stderr)
            installed_after = project_support.tree_bytes(target, installation)
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
                before = project_support.tree_bytes(target, installation)
                source_before = project_support.tree_bytes(project / "source")
                failed = self.run_failure("import", milestone, project, target, export)
                self.assertEqual(3, failed.returncode, failed.stderr)
                self.assertEqual(before, project_support.tree_bytes(target, installation))
                self.assertEqual(source_before, project_support.tree_bytes(project / "source"))
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
            target_before = project_support.tree_bytes(target, installation)
            exports_before = project_support.tree_bytes(project / "exports")
            for milestone in (
                "stage-created", "package-copied", "before-publish", "after-publish"
            ):
                with self.subTest(milestone=milestone):
                    failed = self.run_failure("export", milestone, project, target)
                    self.assertEqual(3, failed.returncode, failed.stderr)
                    self.assertEqual(exports_before, project_support.tree_bytes(project / "exports"))
                    self.assertEqual(target_before, project_support.tree_bytes(target, installation))
                    self.assertFalse(list((project / "exports").glob(".staging-*")))
            tampered = self.run_failure("export-tamper", "none", project, target)
            self.assertEqual(3, tampered.returncode, tampered.stderr)
            self.assertEqual(exports_before, project_support.tree_bytes(project / "exports"))
            self.assertEqual(target_before, project_support.tree_bytes(target, installation))

    def test_free_space_and_force_refuse_before_artifacts(self):
        with tempfile.TemporaryDirectory(prefix="adaptive-import-preflight-") as temp:
            target, installation, project, export = self.target_project(Path(temp))
            before = project_support.tree_bytes(target, installation)
            receipts_before = project_support.tree_bytes(project / "receipts")
            backups_before = project_support.tree_bytes(project / "backups")
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
            self.assertEqual(before, project_support.tree_bytes(target, installation))
            self.assertEqual(receipts_before, project_support.tree_bytes(project / "receipts"))
            self.assertEqual(backups_before, project_support.tree_bytes(project / "backups"))

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
            self.assertEqual(before, project_support.tree_bytes(target, installation))
            self.assertEqual(receipts_before, project_support.tree_bytes(project / "receipts"))
            self.assertEqual(backups_before, project_support.tree_bytes(project / "backups"))

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
                self.assertEqual(before, project_support.tree_bytes(target, installation))
                self.assertEqual(
                    receipts_before, project_support.tree_bytes(project / "receipts")
                )
                self.assertEqual(
                    backups_before, project_support.tree_bytes(project / "backups")
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
                    project_support.write_json(receipt_path, receipt)
                else:
                    backup = (
                        project
                        / "backups"
                        / receipt["transactionId"]
                        / "before/server/world-builder-configs/primary.json"
                    )
                    backup.write_bytes(backup.read_bytes() + b"\n")
                installed = project_support.tree_bytes(target, installation)
                artifacts = self.transaction_artifacts(project)
                refused = self.run_cli(
                    "undo-adaptive", "--project", project, "--target-root", target
                )
                self.assertEqual(3, refused.returncode, refused.stderr)
                self.assertEqual(
                    installed,
                    project_support.tree_bytes(target, installation),
                    refused.stderr,
                )
                self.assertEqual(artifacts, self.transaction_artifacts(project))

    def test_recovery_restores_failed_import_rollback(self):
        with tempfile.TemporaryDirectory(prefix="adaptive-import-recovery-") as temp:
            target, installation, project, export = self.target_project(Path(temp))
            before = project_support.tree_bytes(target, installation)
            failed = self.run_failure(
                "import",
                "package-file-published-0000,rollback-before-0008",
                project,
                target,
                export,
            )
            self.assertEqual(3, failed.returncode, failed.stderr)
            self.assertNotEqual(before, project_support.tree_bytes(target, installation))
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
            self.assertEqual(before, project_support.tree_bytes(target, installation))
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
            before = project_support.tree_bytes(target, installation)
            failed = self.run_failure(
                "import",
                "package-file-published-0000,rollback-after-0008",
                project,
                target,
                export,
            )
            self.assertEqual(3, failed.returncode, failed.stderr)
            uncertain = project_support.tree_bytes(target, installation)
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
                project_support.tree_bytes(
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
            self.assertEqual(before, project_support.tree_bytes(target, installation))
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
                "package-file-published-0000,rollback-before-0008",
                project,
                target,
                export,
            )
            self.assertEqual(3, failed_import.returncode, failed_import.stderr)
            project_id = json.loads((project / "project.json").read_text())["projectId"]
            uncertain = project_support.tree_bytes(target, installation)
            source = project_support.tree_bytes(project / "source")

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
                        project_support.tree_bytes(copied_target, copied_installation),
                    )
                    self.assertEqual(
                        source, project_support.tree_bytes(copied_project / "source")
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
            *[f"undo-before-{index:04d}" for index in range(9)],
            *[f"undo-after-{index:04d}" for index in range(9)],
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
                installed = project_support.tree_bytes(target, installation)
                source_before = project_support.tree_bytes(project / "source")
                failed = self.run_failure("undo", milestone, project, target)
                self.assertEqual(3, failed.returncode, failed.stderr)
                self.assertEqual(
                    installed,
                    project_support.tree_bytes(target, installation),
                    failed.stderr,
                )
                self.assertEqual(source_before, project_support.tree_bytes(project / "source"))
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
            installed = project_support.tree_bytes(target, installation)
            failed = self.run_failure(
                "undo",
                "undo-after-0000,undo-rollback-before-0008",
                project,
                target,
            )
            self.assertEqual(3, failed.returncode, failed.stderr)
            self.assertNotEqual(installed, project_support.tree_bytes(target, installation))
            recovered = self.run_reviewed_apply(
                "recover-adaptive",
                "RECOVER",
                "--project",
                project,
                "--target-root",
                target,
            )
            self.assertEqual(0, recovered.returncode, recovered.stderr)
            self.assertEqual(installed, project_support.tree_bytes(target, installation))
            preview = self.run_cli(
                "undo-adaptive", "--project", project, "--target-root", target
            )
            self.assertEqual(0, preview.returncode, preview.stderr)

    def test_reviewed_plan_binding_stdout_and_literal_active_confirmation(self):
        invalid_inputs = ("import\n", " IMPORT\n", "IMPORT \n", "\n", "")
        with tempfile.TemporaryDirectory(prefix="adaptive-reviewed-binding-") as temp:
            target, installation, project, export = self.target_project(Path(temp))
            target_before = project_support.tree_bytes(target, installation)
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
                    target_before, project_support.tree_bytes(target, installation)
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
            self.assertEqual(target_before, project_support.tree_bytes(target, installation))
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

            installed = project_support.tree_bytes(target, installation)
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
            self.assertEqual(installed, project_support.tree_bytes(target, installation))
            self.assertEqual(installed_artifacts, self.transaction_artifacts(project))
            removed = self.run_cli_input(
                "UNDO\n", "undo-active-adaptive", "--installation-root", installation
            )
            self.assertEqual(2, removed.returncode, removed.stderr)
            self.assertIn("Unsupported World Builder command", removed.stderr)
            self.assertEqual(installed, project_support.tree_bytes(target, installation))
            self.assertEqual(installed_artifacts, self.transaction_artifacts(project))

            failed = self.run_failure(
                "undo", "undo-after-0000,undo-rollback-before-0008", project, target
            )
            self.assertEqual(3, failed.returncode, failed.stderr)
            uncertain = project_support.tree_bytes(target, installation)
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
            self.assertEqual(uncertain, project_support.tree_bytes(target, installation))
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
                self.assertEqual(uncertain, project_support.tree_bytes(target, installation))
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
            target_after_drift = project_support.tree_bytes(target, installation)
            refused = self.run_reviewed_apply(
                "import-adaptive", "IMPORT", "--project", project,
                "--export", export, "--target-root", target, preview=preview
            )
            self.assertEqual(3, refused.returncode, refused.stderr)
            self.assertEqual("", refused.stdout)
            self.assertEqual(
                target_after_drift, project_support.tree_bytes(target, installation)
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
        self.assertFalse(
            (ROOT / "release/world-builder-v2/Undo Last Map Import.cmd").exists()
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
            before = project_support.tree_bytes(target, installation)
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
            self.assertEqual(before, project_support.tree_bytes(target, installation))
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
                    project_support.write_json(evidence_path, altered)
                    installed = project_support.tree_bytes(target, installation)
                    artifacts = self.transaction_artifacts(project)
                    refused = self.run_cli(
                        "undo-adaptive", "--project", project,
                        "--target-root", target,
                    )
                    self.assertEqual(3, refused.returncode, refused.stderr)
                    self.assertEqual(installed, project_support.tree_bytes(target, installation))
                    self.assertEqual(artifacts, self.transaction_artifacts(project))
                    self.assertTrue(preexisting_ancestor.is_dir())
                    self.assertTrue(preexisting_arbitrary.is_dir())
                    project_support.write_json(evidence_path, original)

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
                    installed = project_support.tree_bytes(target, installation)
                    artifacts = self.transaction_artifacts(project)
                    refused = self.run_cli(
                        "undo-adaptive", "--project", project,
                        "--target-root", target,
                    )
                    self.assertEqual(3, refused.returncode, refused.stderr)
                    self.assertEqual(installed, project_support.tree_bytes(target, installation))
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

    def test_saved_working_edits_chain_imports_and_undo_one_generation(self):
        with tempfile.TemporaryDirectory(prefix="adaptive-historical-undo-") as temp:
            target, installation, project, export_a = self.target_project(Path(temp))
            target_before = project_support.tree_bytes(target, installation)
            imported = self.run_reviewed_apply(
                "import-adaptive", "IMPORT", "--project", project,
                "--export", export_a, "--target-root", target,
            )
            self.assertEqual(0, imported.returncode, imported.stderr)
            installed_a = project_support.tree_bytes(target, installation)
            project_support.change_working_terrain(project)
            saved = self.run_cli("save-project", "--project", project)
            self.assertEqual(0, saved.returncode, saved.stderr)
            working_b = project_support.tree_bytes(project / "working")
            exported_b = self.run_cli("export-adaptive", "--project", project)
            self.assertEqual(0, exported_b.returncode, exported_b.stderr)
            export_b = Path(json.loads(exported_b.stdout)["exportDirectory"])

            reopened = self.run_cli(
                "open-project", "--installation-root", installation,
                "--target-root", target,
            )
            self.assertEqual(0, reopened.returncode, reopened.stderr)
            self.assertEqual("ready-detached", json.loads(reopened.stdout)["state"])

            second = self.run_reviewed_apply(
                "import-adaptive", "IMPORT", "--project", project,
                "--export", export_b, "--target-root", target,
            )
            self.assertEqual(0, second.returncode, second.stderr)
            self.assertNotEqual(installed_a, project_support.tree_bytes(target, installation))

            undone = self.run_reviewed_apply(
                "undo-adaptive", "UNDO", "--project", project,
                "--target-root", target,
            )
            self.assertEqual(0, undone.returncode, undone.stderr)
            self.assertEqual(installed_a, project_support.tree_bytes(target, installation))
            self.assertEqual(working_b, project_support.tree_bytes(project / "working"))

            reattached = self.run_cli(
                "open-project", "--installation-root", installation,
                "--target-root", target,
            )
            self.assertEqual(0, reattached.returncode, reattached.stderr)
            self.assertEqual("ready-detached", json.loads(reattached.stdout)["state"])
            undone_original = self.run_reviewed_apply(
                "undo-adaptive", "UNDO", "--project", project,
                "--target-root", target,
            )
            self.assertEqual(0, undone_original.returncode, undone_original.stderr)
            self.assertEqual(target_before, project_support.tree_bytes(target, installation))

    def test_failed_chained_import_rolls_back_to_latest_installed_generation(self):
        with tempfile.TemporaryDirectory(prefix="adaptive-chain-rollback-") as temp:
            target, installation, project, export_a = self.target_project(Path(temp))
            imported_a = self.run_reviewed_apply(
                "import-adaptive", "IMPORT", "--project", project,
                "--export", export_a, "--target-root", target,
            )
            self.assertEqual(0, imported_a.returncode, imported_a.stderr)
            installed_a = project_support.tree_bytes(target, installation)

            project_support.change_working_terrain(project)
            saved = self.run_cli("save-project", "--project", project)
            self.assertEqual(0, saved.returncode, saved.stderr)
            exported_b = self.run_cli("export-adaptive", "--project", project)
            self.assertEqual(0, exported_b.returncode, exported_b.stderr)
            export_b = Path(json.loads(exported_b.stdout)["exportDirectory"])
            reopened = self.run_cli(
                "open-project", "--installation-root", installation,
                "--target-root", target,
            )
            self.assertEqual(0, reopened.returncode, reopened.stderr)

            failed = self.run_failure(
                "import", "activation-published", project, target, export_b
            )
            self.assertEqual(3, failed.returncode, failed.stderr)
            self.assertEqual(installed_a, project_support.tree_bytes(target, installation))
            self.assert_no_transaction_stage(target)

    def test_final_boundary_drift_and_appeared_paths_are_preserved(self):
        with tempfile.TemporaryDirectory(prefix="adaptive-activation-drift-") as temp:
            target, installation, project, export = self.target_project(Path(temp))
            failed = self.run_failure(
                "import", "activation-final-drift", project, target, export
            )
            self.assertEqual(3, failed.returncode, failed.stderr)
            self.assertTrue(any(
                path.is_file() and path.read_bytes() == b"\x04\x02\x04\x02"
                for path in target.rglob("*")
            ))

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
                "undo", "undo-after-0001,undo-rollback-before-0006",
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
            before = project_support.tree_bytes(target, installation)
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
            self.assertEqual(before, project_support.tree_bytes(target, installation))
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
            installed = project_support.tree_bytes(target, installation)
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
            self.assertEqual(installed, project_support.tree_bytes(target, installation))
            self.assertEqual(undo_artifacts, self.transaction_artifacts(project))

            interrupted = self.run_failure(
                "undo", "undo-after-0000,undo-rollback-before-0008", project, target
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
            uncertain = project_support.tree_bytes(target, installation)
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
            self.assertEqual(uncertain, project_support.tree_bytes(target, installation))
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
                target_with_collision = project_support.tree_bytes(target, installation)
                artifacts = self.transaction_artifacts(project)
                refused = self.run_reviewed_apply(
                    "import-adaptive", "IMPORT", "--project", project,
                    "--export", export, "--target-root", target, preview=preview
                )
                self.assertEqual(3, refused.returncode, refused.stderr)
                self.assertEqual(
                    target_with_collision,
                    project_support.tree_bytes(target, installation),
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
                before = project_support.tree_bytes(project / "run")
                refused = self.run_cli("export-adaptive", "--project", project)
                self.assertEqual(3, refused.returncode, refused.stderr)
                self.assertEqual(before, project_support.tree_bytes(project / "run"))
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
                target_before = project_support.tree_bytes(target, installation)
                occupied_before = (
                    project_support.tree_bytes(occupied)
                    if occupied.is_dir()
                    else occupied.read_bytes()
                )
                refused = self.run_reviewed_apply(
                    "import-adaptive", "IMPORT", "--project", project,
                    "--export", export, "--target-root", target, preview=preview
                )
                self.assertEqual(3, refused.returncode, refused.stderr)
                self.assertEqual(
                    target_before, project_support.tree_bytes(target, installation)
                )
                if occupied.is_dir():
                    self.assertEqual(occupied_before, project_support.tree_bytes(occupied))
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
            installed = project_support.tree_bytes(target, installation)
            refused = self.run_reviewed_apply(
                "undo-adaptive", "UNDO", "--project", project,
                "--target-root", target, preview=preview
            )
            self.assertEqual(3, refused.returncode, refused.stderr)
            self.assertEqual(installed, project_support.tree_bytes(target, installation))
            self.assertEqual(b"undo-collision", (occupied / "marker").read_bytes())

        with tempfile.TemporaryDirectory(prefix="adaptive-id-recovery-") as temp:
            target, installation, project, export = self.target_project(Path(temp))
            failed = self.run_failure(
                "import", "package-file-published-0000,rollback-before-0008",
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
            uncertain = project_support.tree_bytes(target, installation)
            refused = self.run_reviewed_apply(
                "recover-adaptive", "RECOVER", "--project", project,
                "--target-root", target, preview=preview
            )
            self.assertEqual(3, refused.returncode, refused.stderr)
            self.assertEqual(uncertain, project_support.tree_bytes(target, installation))
            self.assertEqual(b"recovery-collision", (occupied / "marker").read_bytes())

        with tempfile.TemporaryDirectory(prefix="adaptive-lock-replacement-") as temp:
            target, installation, project, export = self.target_project(Path(temp))
            project_bytes = project_support.tree_bytes(project / "run")
            replaced_project = self.run_failure(
                "project-lock-replacement", "none", project, target
            )
            self.assertEqual(3, replaced_project.returncode, replaced_project.stderr)
            self.assertEqual(project_bytes, project_support.tree_bytes(project / "run"))

            target_bytes = project_support.tree_bytes(target, installation)
            replaced_target = self.run_failure(
                "target-lock-replacement", "none", project, target
            )
            self.assertEqual(3, replaced_target.returncode, replaced_target.stderr)
            self.assertEqual(target_bytes, project_support.tree_bytes(target, installation))

        with tempfile.TemporaryDirectory(
            prefix="adaptive-lock-absent-replacement-"
        ) as temp:
            target, installation, project, export = self.target_project(Path(temp))
            lock = project / "run/world-builder.lock"
            lock.unlink()
            target_bytes = project_support.tree_bytes(target, installation)
            artifacts = self.transaction_artifacts(project)
            replaced_absent = self.run_failure(
                "project-lock-replacement", "none", project, target
            )
            self.assertEqual(3, replaced_absent.returncode, replaced_absent.stderr)
            self.assertIn("UNSAFE_PATH", replaced_absent.stderr)
            self.assertTrue(lock.is_file())
            self.assertEqual(b"", lock.read_bytes())
            self.assertEqual(target_bytes, project_support.tree_bytes(target, installation))
            self.assertEqual(artifacts, self.transaction_artifacts(project))

        with tempfile.TemporaryDirectory(prefix="adaptive-lock-aba-") as temp:
            target, installation, project, export = self.target_project(Path(temp))
            project_bytes = project_support.tree_bytes(project / "run")
            replaced_aba = self.run_failure(
                "project-lock-aba-replacement", "none", project, target
            )
            self.assertEqual(3, replaced_aba.returncode, replaced_aba.stderr)
            self.assertIn("UNSAFE_PATH", replaced_aba.stderr)
            self.assertEqual(project_bytes, project_support.tree_bytes(project / "run"))

    def test_manifest_and_hardlinked_authorities_are_independently_rejected(self):
        with tempfile.TemporaryDirectory(prefix="adaptive-report-binding-") as temp:
            target, installation, project, export = self.target_project(Path(temp))
            report_path = export / "validation-report.json"
            manifest_path = export / "manifest.json"
            report = json.loads(report_path.read_text(encoding="utf-8"))
            manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
            report["packageManifestSha256"] = "0" * 64
            project_support.write_json(report_path, report)
            manifest["validationReports"][0]["sha256"] = self.canonical_sha256(report)
            self.bind_fingerprint(manifest, "exportFingerprintSha256")
            project_support.write_json(manifest_path, manifest)
            before = project_support.tree_bytes(target, installation)
            refused = self.run_cli(
                "import-adaptive", "--project", project, "--export", export,
                "--target-root", target
            )
            self.assertEqual(3, refused.returncode, refused.stderr)
            self.assertIn("validation", refused.stderr.lower())
            self.assertEqual(before, project_support.tree_bytes(target, installation))

        with tempfile.TemporaryDirectory(prefix="adaptive-export-hardlink-") as temp:
            base = Path(temp)
            target, installation, project, export = self.target_project(base)
            package_file = export / "package/manifest.json"
            external = base / "linked-export-file"
            os.link(package_file, external)
            before = project_support.tree_bytes(target, installation)
            refused = self.run_cli(
                "import-adaptive", "--project", project, "--export", export,
                "--target-root", target
            )
            self.assertEqual(3, refused.returncode, refused.stderr)
            self.assertEqual(before, project_support.tree_bytes(target, installation))
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
                installed = project_support.tree_bytes(target, installation)
                artifacts = self.transaction_artifacts(project)
                refused = self.run_cli(
                    "undo-adaptive", "--project", project, "--target-root", target
                )
                self.assertEqual(3, refused.returncode, refused.stderr)
                self.assertEqual(installed, project_support.tree_bytes(target, installation))
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
            installed = project_support.tree_bytes(target, installation)
            failed = self.run_failure("undo", "assert-safe-order", project, target)
            self.assertEqual(3, failed.returncode, failed.stderr)
            self.assertIn("injected safe-order rollback", failed.stderr)
            self.assertNotIn("order assertion", failed.stderr)
            self.assertEqual(installed, project_support.tree_bytes(target, installation))
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
                before = project_support.tree_bytes(target, installation)
                failed = self.run_failure(
                    "import",
                    "package-file-published-0000,rollback-before-0008",
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
                self.assertEqual(before, project_support.tree_bytes(target, installation))

        with tempfile.TemporaryDirectory(prefix="adaptive-recovery-unknown-stage-") as temp:
            target, installation, project, export = self.target_project(Path(temp))
            failed = self.run_failure(
                "import", "package-file-published-0000,rollback-before-0008",
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
            target_before = project_support.tree_bytes(target, installation)
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
            self.assertEqual(target_before, project_support.tree_bytes(target, installation))
            self.assertEqual(artifacts_before, self.transaction_artifacts(project))

            released = self.run_cli(
                "import-adaptive", "--project", project, "--export", export,
                "--target-root", target
            )
            self.assertEqual(0, released.returncode, released.stderr)
            self.assertEqual(target_before, project_support.tree_bytes(target, installation))
            self.assertEqual(artifacts_before, self.transaction_artifacts(project))


if __name__ == "__main__":
    unittest.main()
