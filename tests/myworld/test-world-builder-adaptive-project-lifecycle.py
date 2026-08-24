#!/usr/bin/env python3
"""Temporary-fixture coverage for the Phase 3 adaptive project lifecycle."""

import hashlib
import importlib.util
import json
import os
import shutil
import struct
import subprocess
import sys
import tempfile
import unittest
import zipfile
import zlib
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
SOURCE_ROOT = ROOT / "tools/world-builder/src"
MAIN_CLASS = "com.openrsc.worldbuilder.WorldBuilderCli"
DISCOVERY_TEST = ROOT / "tests/myworld/test-world-builder-adaptive-discovery.py"
PACKED_CONVERSION_TEST = ROOT / "tests/myworld/test-world-builder-packed-conversion.py"
RUNTIME_ALLOWLIST = ROOT / "release/world-builder-v2/RUNTIME-ASSET-ALLOWLIST.txt"
RUNTIME_ALLOWLIST_RESOURCE = "com/openrsc/worldbuilder/runtime-asset-allowlist-v1.txt"
CANONICAL_VOID_TILE = bytes((0, 0, 1, 8, 0, 0, 0, 0, 0, 0, 0))
CANONICAL_VOID_SECTOR = CANONICAL_VOID_TILE * (48 * 48)
VISIBLE_FLOOR_TILE = bytes((0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0))
STANDALONE_INITIAL_LOCATION = {"level": 0, "x": 120, "y": 648}


def standalone_seed_sector() -> bytes:
    result = bytearray(CANONICAL_VOID_SECTOR)
    center_x = STANDALONE_INITIAL_LOCATION["x"] % 48
    center_y = STANDALONE_INITIAL_LOCATION["y"] % 48
    for local_x in range(center_x - 1, center_x + 2):
        for local_y in range(center_y - 1, center_y + 2):
            offset = (local_x * 48 + local_y) * 11
            result[offset : offset + 11] = VISIBLE_FLOOR_TILE
    return bytes(result)


STANDALONE_SEED_SECTOR = standalone_seed_sector()
REQUIRED_LANGUAGE_BUNDLES = (
    "AuthenticMessages_en_UK.properties",
    "AuthenticMessages_en_UK_female.properties",
    "AuthenticMessages_en_UK_female_no_misgender.properties",
    "AuthenticMessages_en_UK_gender_neutral.properties",
    "AuthenticMessages_en_UK_male.properties",
    "CustomMessages_en_UK.properties",
    "CustomMessages_en_UK_female.properties",
    "CustomMessages_en_UK_gender_neutral.properties",
    "CustomMessages_en_UK_male.properties",
)
EMPTY_LANGUAGE_BUNDLES = {
    "CustomMessages_en_UK_female.properties",
    "CustomMessages_en_UK_gender_neutral.properties",
    "CustomMessages_en_UK_male.properties",
}
REQUIRED_DATABASE_PATCHES = (
    "2021_05_11_add_db_patches.sql",
    "2023_02_01_former_names.sql",
    "2026_05_14_add_summoning_skill.sql",
    "2026_08_03_add_blessing_skill.sql",
)


def load_discovery_fixtures():
    spec = importlib.util.spec_from_file_location(
        "world_builder_adaptive_discovery_fixtures", DISCOVERY_TEST
    )
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module.AdaptiveDiscoveryTest(
        "test_no_server_is_standalone_and_deterministic_without_writes"
    )


def load_packed_fixtures():
    spec = importlib.util.spec_from_file_location(
        "world_builder_packed_conversion_fixtures", PACKED_CONVERSION_TEST
    )
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module.PackedConversionTest(
        "test_deterministic_conversion_reverse_parity_and_portability"
    )


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def canonical_hash(value: dict) -> str:
    return hashlib.sha256(
        json.dumps(value, sort_keys=True, separators=(",", ":")).encode("utf-8")
    ).hexdigest()


def package_fingerprint(package: Path) -> str:
    digest = hashlib.sha256()
    for path in sorted(value for value in package.rglob("*") if value.is_file()):
        relative = path.relative_to(package).as_posix()
        for value in (relative, str(path.stat().st_size), sha256(path)):
            digest.update(value.encode("utf-8"))
            digest.update(b"\0")
    return digest.hexdigest()


def runtime_allowlist_records() -> tuple[tuple[str, str, str], ...]:
    records = []
    for raw in RUNTIME_ALLOWLIST.read_text(encoding="utf-8").splitlines():
        if not raw or raw.startswith("#"):
            continue
        fields = tuple(raw.split("\t"))
        if len(fields) != 3:
            raise AssertionError(f"Malformed fixture runtime allowlist line: {raw!r}")
        records.append(fields)
    return tuple(records)


def write_json(path: Path, value: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        json.dumps(value, sort_keys=True, indent=2, ensure_ascii=False) + "\n",
        encoding="utf-8",
    )


def one_pixel_png(color: int) -> bytes:
    def chunk(kind: bytes, data: bytes) -> bytes:
        return (
            struct.pack(">I", len(data)) + kind + data
            + struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF)
        )

    rgb = bytes((color >> 16 & 0xFF, color >> 8 & 0xFF, color & 0xFF))
    return (
        b"\x89PNG\r\n\x1a\n"
        + chunk(b"IHDR", struct.pack(">IIBBBBB", 1, 1, 8, 2, 0, 0, 0))
        + chunk(b"IDAT", zlib.compress(b"\0" + rgb))
        + chunk(b"IEND", b"")
    )


def provider_visual(
    item_id: int,
    name: str,
    role: str,
    source_asset: str | None,
    source_hash: str | None,
    logical: str | None,
    *,
    authentic: int | None = None,
    subspace: str | None = None,
    entry: str | None = None,
    external: dict | None = None,
    picture_mask: int = 0,
    blue_mask: int = 0,
) -> dict:
    return {
        "itemId": item_id,
        "name": name,
        "logicalSpriteLocation": logical,
        "sourceRole": role,
        "sourceAsset": source_asset,
        "sourceAssetSha256": source_hash,
        "authenticSpriteId": authentic,
        "customSpriteSubspace": subspace,
        "customSpriteEntry": entry,
        "externalPng": external,
        "pictureMask": picture_mask,
        "blueMask": blue_mask,
    }


def tree_bytes(root: Path, excluded: Path | None = None) -> dict[str, tuple]:
    result = {}
    if not root.exists():
        return result
    excluded_resolved = excluded.resolve() if excluded and excluded.exists() else None
    for path in sorted(root.rglob("*")):
        if excluded_resolved is not None:
            try:
                path.resolve().relative_to(excluded_resolved)
                continue
            except ValueError:
                pass
        relative = path.relative_to(root).as_posix()
        if path.is_symlink():
            result[relative] = ("link", os.readlink(path))
        elif path.is_dir():
            result[relative] = ("dir",)
        else:
            result[relative] = ("file", path.stat().st_size, sha256(path))
    return result


class AdaptiveProjectLifecycleTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.compile_temp = tempfile.TemporaryDirectory(
            prefix="world-builder-adaptive-project-classes-"
        )
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
        allowlist_resource = cls.classes / RUNTIME_ALLOWLIST_RESOURCE
        allowlist_resource.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(RUNTIME_ALLOWLIST, allowlist_resource)
        harness = (
            Path(cls.compile_temp.name)
            / "harness/com/openrsc/worldbuilder/AdaptiveProjectFailureHarness.java"
        )
        harness.parent.mkdir(parents=True)
        harness.write_text(
            """
package com.openrsc.worldbuilder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

public final class AdaptiveProjectFailureHarness {
    public static void main(String[] args) throws Exception {
        final String mode = args[6];
        final Path target = Paths.get(args[2]);
        WorldBuilderAdaptiveProjectLifecycle.Observer observer =
            new WorldBuilderAdaptiveProjectLifecycle.Observer() {
                @Override
                public void observe(String milestone, Path stage) throws Exception {
                    if ("drift-source-prepared".equals(mode)
                        && "source-prepared".equals(milestone)) {
                        Files.write(
                            target.resolve("server/world-builder-configs/primary.json"),
                            "\\n".getBytes(StandardCharsets.UTF_8),
                            StandardOpenOption.APPEND);
                        return;
                    }
                    if (mode.equals(milestone)) {
                        throw new Exception("injected failure at " + milestone);
                    }
                }
            };
        try {
            new WorldBuilderAdaptiveProjectLifecycle(observer).create(
                Paths.get(args[0]), Paths.get(args[1]), target,
                Paths.get(args[3]), args[4], Integer.parseInt(args[5]), "CREATE");
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
        promotion_harness = (
            Path(cls.compile_temp.name)
            / "harness/com/openrsc/worldbuilder/WidePromotionCrashHarness.java"
        )
        promotion_harness.write_text(
            """
package com.openrsc.worldbuilder;

import java.nio.file.Path;
import java.nio.file.Paths;

public final class WidePromotionCrashHarness {
    public static void main(String[] args) throws Exception {
        final String injected = args[1];
        WorldBuilderAdaptiveProjectLifecycle.Observer observer =
            new WorldBuilderAdaptiveProjectLifecycle.Observer() {
                @Override
                public void observe(String milestone, Path project) {
                    if (injected.equals(milestone)) {
                        Runtime.getRuntime().halt(71);
                    }
                }
            };
        new WorldBuilderAdaptiveProjectLifecycle(observer).save(Paths.get(args[0]));
    }
}
""".strip()
            + "\n",
            encoding="utf-8",
        )
        subprocess.run(
            [
                "javac", "-source", "8", "-target", "8",
                "-cp", str(cls.classes), "-d", str(cls.classes),
                str(promotion_harness),
            ],
            check=True,
            cwd=ROOT,
            capture_output=True,
            text=True,
        )
        desktop_harness = (
            Path(cls.compile_temp.name)
            / "harness/com/openrsc/worldbuilder/DesktopLauncherHarness.java"
        )
        desktop_harness.write_text(
            """
package com.openrsc.worldbuilder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.List;

public final class DesktopLauncherHarness {
    public static void main(String[] args) throws Exception {
        if (WorldBuilderDesktopLauncher.closeDisposition(true, false)
                != WorldBuilderDesktopLauncher.CloseDisposition.WAIT_FOR_EDITOR
            || WorldBuilderDesktopLauncher.closeDisposition(true, true)
                != WorldBuilderDesktopLauncher.CloseDisposition.WAIT_FOR_EDITOR
            || WorldBuilderDesktopLauncher.closeDisposition(false, true)
                != WorldBuilderDesktopLauncher.CloseDisposition.WAIT_FOR_TASK
            || WorldBuilderDesktopLauncher.closeDisposition(false, false)
                != WorldBuilderDesktopLauncher.CloseDisposition.CLOSE) {
            throw new AssertionError("desktop close policy");
        }
        if ("PACKAGE_SELECTION".equals(args[4])) {
            WorldBuilderLauncherModel model = new WorldBuilderLauncherModel(
                Paths.get(args[0]), Paths.get(args[1]), Paths.get(args[2]),
                Integer.parseInt(args[3]), null);
            WorldBuilderPortableProvider.GuidedSelection selection =
                WorldBuilderDesktopLauncher.completeProviderSelection(
                    model.inspectPortableProvider(Paths.get(args[5])));
            WorldBuilderPortableProvider.Provider provider =
                model.importPortableProvider(Paths.get(args[2]), selection);
            Files.write(Paths.get(args[6]),
                (provider.itemVisuals.toRealPath().toString() + "\\n")
                    .getBytes(StandardCharsets.UTF_8));
            return;
        }
        if ("MODEL_MAPPING".equals(args[4])) {
            WorldBuilderLauncherModel model = new WorldBuilderLauncherModel(
                Paths.get(args[0]), Paths.get(args[1]), Paths.get(args[2]),
                Integer.parseInt(args[3]), null);
            WorldBuilderLauncherModel.DiscoveryPreview preview =
                model.inspectSource(Paths.get(args[5]));
            WorldBuilderAdaptiveProjectLifecycle.ProjectResult created =
                model.create(preview, "Mapped Model Test", Paths.get(args[7]));
            Files.write(Paths.get(args[6]),
                (created.projectRoot.toRealPath().toString() + "\\n")
                    .getBytes(StandardCharsets.UTF_8));
            return;
        }
        final WorldBuilderDesktopLauncher.Action action =
            WorldBuilderDesktopLauncher.Action.valueOf(args[4]);
        final Path selected = "-".equals(args[5]) ? null : Paths.get(args[5]);
        final Path marker = Paths.get(args[6]);
        WorldBuilderDesktopLauncher.Ui ui = new WorldBuilderDesktopLauncher.Ui() {
            @Override public WorldBuilderDesktopLauncher.Action chooseAction(
                List<WorldBuilderDesktopLauncher.ProjectChoice> projects,
                String summary, boolean supported) {
                return action;
            }
            @Override public WorldBuilderDesktopLauncher.ProjectChoice chooseProject(
                List<WorldBuilderDesktopLauncher.ProjectChoice> projects) {
                return projects.isEmpty() ? null : projects.get(0);
            }
            @Override public Path chooseSource(Path initial) { return selected; }
            @Override public String requestDisplayName(String suggested) {
                return suggested + " Test";
            }
            @Override public boolean confirmCreation(String title, String summary) {
                return true;
            }
            @Override public void showError(String title, String message) {
                throw new IllegalStateException(title + ": " + message);
            }
        };
        WorldBuilderDesktopLauncher.ProjectRunner runner =
            new WorldBuilderDesktopLauncher.ProjectRunner() {
                @Override public int run(Path project) throws Exception {
                    Files.write(marker,
                        (project.toRealPath().toString() + "\\n").getBytes(StandardCharsets.UTF_8),
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                        StandardOpenOption.WRITE);
                    return 0;
                }
            };
        int result = new WorldBuilderDesktopLauncher(ui, runner).run(
            new WorldBuilderDesktopLauncher.Options(
                Paths.get(args[0]), Paths.get(args[1]), Paths.get(args[2]),
                null, Integer.parseInt(args[3])));
        System.exit(result);
    }
}
""".strip()
            + "\n",
            encoding="utf-8",
        )
        subprocess.run(
            [
                "javac", "-source", "8", "-target", "8",
                "-cp", str(cls.classes), "-d", str(cls.classes),
                str(desktop_harness),
            ],
            check=True,
            cwd=ROOT,
            capture_output=True,
            text=True,
        )
        region_harness = (
            Path(cls.compile_temp.name)
            / "harness/com/openrsc/worldbuilder/RegionOperationFailureHarness.java"
        )
        region_harness.write_text(
            r"""
package com.openrsc.worldbuilder;

import java.nio.file.Path;
import java.nio.file.Paths;

public final class RegionOperationFailureHarness {
    public static void main(String[] args) throws Exception {
        final String milestone = args[4];
        if ("journal-write-failed".equals(milestone)) {
            System.setProperty("worldbuilder.region.testJournalWriteFailurePhase", "manifest-saved");
        }
        if ("journal-delete-failed".equals(milestone)) {
            System.setProperty("worldbuilder.region.testJournalDeleteFailure", "true");
        }
        WorldBuilderRegionSnapshotService.Observer observer =
            new WorldBuilderRegionSnapshotService.Observer() {
                @Override public void observe(String current, Path project) throws Exception {
                    if ("failed-quarantined".equals(milestone)
                        && "package-published".equals(current)) {
                        throw new Exception("injected failure before rollback cleanup");
                    }
                    if (milestone.equals(current)) {
                        throw new Exception("injected region failure at " + current);
                    }
                }
            };
        try {
            new WorldBuilderRegionSnapshotService(observer).applyCut(
                Paths.get(args[0]), args[1], args[2], args[3]);
            System.exit(0);
        } catch (WorldBuilderContractException expected) {
            System.err.println(expected.code() + ": " + expected.getMessage());
            System.exit(3);
        } catch (Exception expected) {
            System.err.println("INJECTED: " + expected.getMessage());
            System.exit(4);
        }
    }
}
""".strip()
            + "\n",
            encoding="utf-8",
        )
        subprocess.run(
            [
                "javac", "-source", "8", "-target", "8", "-cp", str(cls.classes),
                "-d", str(cls.classes), str(region_harness),
            ],
            check=True,
            cwd=ROOT,
            capture_output=True,
            text=True,
        )
        supervisor_harness = (
            Path(cls.compile_temp.name)
            / "harness/com/openrsc/worldbuilder/AdaptiveProjectSupervisorHarness.java"
        )
        supervisor_harness.write_text(
            r"""
package com.openrsc.worldbuilder;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

public final class AdaptiveProjectSupervisorHarness {
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static boolean contains(List<String> command, String value) {
        return command.contains(value);
    }

    private static List<String> command(String classes, String nested,
        Path project, int port) {
        return Arrays.asList(
            Paths.get(System.getProperty("java.home"), "bin", "java").toString(),
            "-cp", classes,
            "com.openrsc.worldbuilder.AdaptiveProjectSupervisorHarness$" + nested,
            project.toString(), Integer.toString(port));
    }

    private static List<String> commandWithMode(String classes, String nested,
        Path project, int port, String mode) {
        List<String> result = new ArrayList<String>(
            command(classes, nested, project, port));
        result.add(mode);
        return result;
    }

    public static void main(String[] args) throws Exception {
        Path project = Paths.get(args[0]);
        String classes = args[1];
        boolean finalizationMode = args.length > 2 && "finalization".equals(args[2]);
        int port = WorldBuilderAdaptiveProjectLifecycle.readRuntimePort(project);
        WorldBuilderProcessSupervisor supervisor = new WorldBuilderProcessSupervisor();
        String manifest = new String(Files.readAllBytes(project.resolve("project.json")),
            StandardCharsets.UTF_8);
        Map<String,Object> projectManifest = WorldBuilderJsonDocuments.readObject(
            manifest.getBytes(StandardCharsets.UTF_8), "supervised project manifest");
        @SuppressWarnings("unchecked") Map<String,Object> fingerprints =
            (Map<String,Object>)projectManifest.get("fingerprints");
        String workingFingerprintBefore = (String)fingerprints.get("workingSha256");
        if (!(args.length > 2 && "unsafe".equals(args[2]))) {
            String expectedOrigin = manifest.contains("\"origin\": \"target-layered\"")
                ? "target-layered" : manifest.contains("\"origin\": \"target-packed\"")
                    ? "target-packed" : "standalone-empty";
            List<String> productionServer =
                WorldBuilderProcessSupervisor.defaultAdaptiveServerCommand(project);
            List<String> productionClient =
                WorldBuilderProcessSupervisor.defaultAdaptiveClientCommand(project);
            require(contains(productionServer,
                "-Dopenrsc.worldBuilderWorkspaceRoot=" + project), "server project root");
            require(contains(productionServer,
                "-Dopenrsc.worldBuilderProjectOrigin=" + expectedOrigin), "server origin");
            require(contains(productionServer,
                "-Dopenrsc.worldBuilderAdaptiveMode=true"), "server adaptive mode");
            require(contains(productionServer,
                "-Dopenrsc.layeredNativeWorldRuntimeProfile=adaptive-world-builder"),
                "server runtime profile");
            require(contains(productionServer,
                "-Dopenrsc.layeredNativeTerrainPackagePath="
                    + project.resolve("working/layered-world/package")), "server package");
            require(contains(productionServer,
                "-Dopenrsc.worldBuilderControlDirectory="
                    + project.resolve("run/world-builder")), "server control");
            if ("standalone-empty".equals(expectedOrigin)) {
                require(contains(productionServer,
                    "-Dopenrsc.worldBuilderInitialWorldSpace=global"),
                    "standalone initial world space");
                require(contains(productionServer,
                    "-Dopenrsc.worldBuilderInitialLevel=0"),
                    "standalone initial level");
                require(contains(productionServer,
                    "-Dopenrsc.worldBuilderInitialX=120"),
                    "standalone initial x");
                require(contains(productionServer,
                    "-Dopenrsc.worldBuilderInitialY=648"),
                    "standalone initial y");
            }
            require(contains(productionClient,
                "-Dopenrsc.worldBuilderRuntimeBindingFile="
                    + project.resolve("run/world-builder/runtime-binding.properties")),
                "client binding");
            require(contains(productionClient,
                "-Dopenrsc.worldBuilderDefinitionEvidenceFile="
                    + project.resolve("working/runtime/client/evidence/adaptive-definitions.json")),
                "client definitions");
            require(contains(productionClient,
                "-Dopenrsc.worldBuilderAssetEvidenceFile="
                    + project.resolve("working/runtime/client/evidence/adaptive-assets.sha256")),
                "client assets");
            for (String property : Arrays.asList(
                    "openrsc.worldBuilderDefinitionId",
                    "openrsc.worldBuilderDefinitionSha256",
                    "openrsc.worldBuilderAssetId",
                    "openrsc.worldBuilderAssetSha256")) {
                String serverValue = propertyValue(productionServer, property);
                String clientValue = propertyValue(productionClient, property);
                require(!serverValue.isEmpty(), "server " + property);
                require(serverValue.equals(clientValue), "shared " + property);
            }
            Path contentRoot = project.resolve("working/content-bundle");
            if (Files.isDirectory(contentRoot)) {
                WorldBuilderProjectContentBundle.Bundle content =
                    WorldBuilderProjectContentBundle.read(contentRoot);
                for (List<String> command : Arrays.asList(
                        productionServer, productionClient)) {
                    require(contains(command,
                        "-Dopenrsc.worldBuilderContentBundle=" + contentRoot),
                        "custom content path");
                    require(contains(command,
                        "-Dopenrsc.worldBuilderContentCapabilityId="
                            + WorldBuilderProjectContentBundle.CAPABILITY_ID),
                        "custom content capability");
                    require(contains(command,
                        "-Dopenrsc.worldBuilderContentBundleSha256="
                            + content.bundleFingerprintSha256),
                        "custom content bundle fingerprint");
                    require(contains(command,
                        "-Dopenrsc.worldBuilderContentDefinitionSha256="
                            + content.definitionFingerprintSha256),
                        "custom content definition fingerprint");
                    require(contains(command,
                        "-Dopenrsc.worldBuilderContentAssetSha256="
                            + content.assetFingerprintSha256),
                        "custom content asset fingerprint");
                    require(contains(command,
                        "-Dopenrsc.worldBuilderContentItemVisualSha256="
                            + content.itemVisualFingerprintSha256),
                        "custom content item visual fingerprint");
                }
            }
            require(contains(productionClient,
                "-Dspoiledmilk.clientLog="
                    + project.resolve("logs/client-runtime.log")),
                "client runtime log confinement");
        }

        List<String> server = command(classes, "FakeServer", project, port);
        List<String> client = finalizationMode
            ? commandWithMode(classes, "FakeClient", project, port, "mutate")
            : command(classes, "FakeClient", project, port);
        if (args.length > 2 && "unsafe".equals(args[2])) {
            boolean refused = false;
            try {
                supervisor.superviseAdaptiveWithCommands(
                    project, server, client, 5000L);
            } catch (WorldBuilderContractException expected) {
                refused = WorldBuilderErrorCodes.UNSAFE_PATH.equals(expected.code());
            }
            require(refused, "unsafe adaptive mutable layout");
            System.out.println("unsafe-adaptive-supervision-refused");
            return;
        }
        if (args.length > 2 && "failures".equals(args[2])) {
            byte[] manifestBefore = Files.readAllBytes(project.resolve("project.json"));
            boolean timeout = false;
            try {
                supervisor.superviseAdaptiveWithCommands(
                    project, command(classes, "NeverReadyServer", project, port),
                    client, 350L);
            } catch (WorldBuilderDiscoveryException expected) {
                timeout = expected.getMessage().contains("did not become ready");
            }
            require(timeout, "adaptive readiness timeout");
            requireCleanFailure(project, manifestBefore, "readiness timeout");

            boolean early = false;
            try {
                supervisor.superviseAdaptiveWithCommands(
                    project, command(classes, "EarlyServer", project, port),
                    client, 5000L);
            } catch (WorldBuilderDiscoveryException expected) {
                early = expected.getMessage().contains("exited before it became ready");
            }
            require(early, "adaptive server early exit");
            requireCleanFailure(project, manifestBefore, "server early exit");

            int clientFailure = supervisor.superviseAdaptiveWithCommands(
                project, server, command(classes, "FailingClient", project, port),
                5000L);
            require(clientFailure == 7, "adaptive client failure exit");
            requireCleanFailure(project, manifestBefore, "client failure");
            String receipt = new String(Files.readAllBytes(
                project.resolve("run/last-run.json")), StandardCharsets.UTF_8);
            require(receipt.contains("\"clientExit\": 7"), "client failure receipt");
            System.out.println("adaptive-supervision-failures-ok");
            return;
        }
        Path lockPath = project.resolve("run/world-builder.lock");
        try (FileChannel channel = FileChannel.open(lockPath,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock lock = channel.lock()) {
            boolean saveRefused = false;
            try {
                new WorldBuilderAdaptiveProjectLifecycle().save(project);
            } catch (WorldBuilderContractException expected) {
                saveRefused = WorldBuilderErrorCodes.RECOVERY_REQUIRED.equals(
                    expected.code());
            }
            require(saveRefused, "external save must share the adaptive run lock");
            boolean refused = false;
            try {
                supervisor.superviseAdaptiveWithCommands(
                    project, server, client, 5000L);
            } catch (WorldBuilderContractException expected) {
                refused = WorldBuilderErrorCodes.RECOVERY_REQUIRED.equals(expected.code());
            }
            require(refused, "adaptive project lock");
        }

        int result = supervisor.superviseAdaptiveWithCommands(
            project, server, client, 5000L);
        require(result == 0, "adaptive isolated run");
        WorldBuilderAdaptiveProjectLifecycle.VerifiedProject finalized =
            WorldBuilderAdaptiveProjectLifecycle.verifyProjectDirectory(project, true);
        if (finalizationMode) {
            require(!workingFingerprintBefore.equals(
                    finalized.working.fingerprintSha256),
                "normal client close must save the changed working fingerprint");
        }
        require(!Files.exists(project.resolve("run/server.pid")), "server PID cleanup");
        require(!Files.exists(project.resolve("run/client.pid")), "client PID cleanup");
        require(Files.isRegularFile(project.resolve(
            "run/world-builder/fake-server-stopped")), "server stopped normally");
        require(Files.isRegularFile(project.resolve(
            "run/world-builder/fake-client-stopped")), "client stopped normally");
        require(!Files.exists(project.resolve(
            "run/world-builder/ready")), "ready cleanup");
        require(Files.isRegularFile(project.resolve("run/last-run.json")),
            "bounded run receipt");
        System.out.println("adaptive-supervision-ok");
    }

    private static String propertyValue(List<String> command, String property) {
        String prefix = "-D" + property + "=";
        for (String argument : command) {
            if (argument.startsWith(prefix)) return argument.substring(prefix.length());
        }
        return "";
    }

    private static void requireCleanFailure(Path project, byte[] manifestBefore,
        String label) throws Exception {
        require(!Files.exists(project.resolve("run/server.pid")), label + " server PID");
        require(!Files.exists(project.resolve("run/client.pid")), label + " client PID");
        require(!Files.exists(project.resolve("run/world-builder/ready")),
            label + " ready cleanup");
        require(Files.isRegularFile(project.resolve("run/last-run.json")),
            label + " run receipt");
        require(Arrays.equals(manifestBefore,
            Files.readAllBytes(project.resolve("project.json"))),
            label + " must not save project metadata");
    }

    public static final class FakeServer {
        public static void main(String[] args) throws Exception {
            Path project = Paths.get(args[0]);
            int port = Integer.parseInt(args[1]);
            Path server = project.resolve("working/runtime/server");
            Path control = project.resolve("run/world-builder");
            Path credential = server.resolve("inc/sqlite/world-builder.credential");
            Files.createDirectories(control);
            Files.createDirectories(credential.getParent());
            Files.write(credential,
                "Abcdefghijk23456789Z".getBytes(StandardCharsets.US_ASCII));
            Files.write(server.resolve("ipbans.txt"), new byte[0]);
            Files.write(server.resolve("client.pem"),
                "fixture client key\n".getBytes(StandardCharsets.US_ASCII));
            Files.write(server.resolve("server.pem"),
                "fixture server key\n".getBytes(StandardCharsets.US_ASCII));
            Files.write(server.resolve("create_db.log"),
                "fixture database setup log\n".getBytes(StandardCharsets.US_ASCII));
            Files.write(server.resolve("create_db_error.log"),
                "fixture database error log\n".getBytes(StandardCharsets.US_ASCII));
            try (ServerSocket listener = new ServerSocket(
                    port, 1, InetAddress.getByName("127.0.0.1"))) {
                listener.setSoTimeout(100);
                Files.write(control.resolve("ready"),
                    "ready\n".getBytes(StandardCharsets.US_ASCII));
                while (!Files.exists(control.resolve("shutdown.request"))) {
                    try (Socket ignored = listener.accept()) {
                        // Readiness probes connect and immediately close.
                    } catch (java.net.SocketTimeoutException expected) {
                        // Poll the project-local shutdown request again.
                    }
                }
            }
            Files.write(control.resolve("fake-server-stopped"),
                "stopped\n".getBytes(StandardCharsets.US_ASCII));
        }
    }

    public static final class FakeClient {
        public static void main(String[] args) throws Exception {
            Path project = Paths.get(args[0]);
            Path client = project.resolve("working/runtime/client");
            Files.write(client.resolve("clientSettings.conf"),
                "generated=true\n".getBytes(StandardCharsets.UTF_8));
            if (args.length > 2 && "mutate".equals(args[2])) {
                Path packageRoot = project.resolve("working/layered-world/package");
                Path manifestPath = packageRoot.resolve("manifest.json");
                Map<String,Object> packageManifest = WorldBuilderJsonDocuments.readObject(
                    Files.readAllBytes(manifestPath), "fake client working package");
                @SuppressWarnings("unchecked") Map<String,Object> declaration =
                    (Map<String,Object>)((List<?>)packageManifest.get(
                        "terrainSectors")).get(0);
                Path terrain = packageRoot.resolve((String)declaration.get("path"));
                byte[] payload = Files.readAllBytes(terrain);
                payload[1] ^= 1;
                Files.write(terrain, payload);
                declaration.put("sha256", WorldBuilderHashes.sha256(terrain));
                Files.write(manifestPath, WorldBuilderJsonDocuments.pretty(packageManifest)
                    .getBytes(StandardCharsets.UTF_8));
            }
            Thread.sleep(250L);
            Files.write(project.resolve("run/world-builder/fake-client-stopped"),
                "stopped\n".getBytes(StandardCharsets.US_ASCII));
        }
    }

    public static final class NeverReadyServer {
        public static void main(String[] args) throws Exception {
            Path project = Paths.get(args[0]);
            Path control = project.resolve("run/world-builder");
            Path credential = project.resolve(
                "working/runtime/server/inc/sqlite/world-builder.credential");
            Files.createDirectories(control);
            Files.createDirectories(credential.getParent());
            Files.write(credential,
                "Abcdefghijk23456789Z".getBytes(StandardCharsets.US_ASCII));
            while (!Files.exists(control.resolve("shutdown.request"))) {
                Thread.sleep(25L);
            }
        }
    }

    public static final class EarlyServer {
        public static void main(String[] args) {
            System.exit(9);
        }
    }

    public static final class FailingClient {
        public static void main(String[] args) {
            System.exit(7);
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
                str(supervisor_harness),
            ],
            check=True,
            cwd=ROOT,
            capture_output=True,
            text=True,
        )
        cls.fixtures = load_discovery_fixtures()
        cls.packed_fixtures = load_packed_fixtures()

    @classmethod
    def tearDownClass(cls):
        cls.compile_temp.cleanup()

    def run_cli(self, *args: object) -> subprocess.CompletedProcess:
        return subprocess.run(
            ["java", "-cp", str(self.classes), MAIN_CLASS, *map(str, args)],
            cwd=ROOT,
            text=True,
            capture_output=True,
        )

    @staticmethod
    def make_runtime(root: Path, scenery_count: int = 4) -> Path:
        runtime = root / "builder-runtime"
        launcher = runtime / "launcher/world-builder-tools.jar"
        launcher.parent.mkdir(parents=True)
        launcher.write_bytes(b"content-neutral-fixture-tools\n")
        server = runtime / "server"
        client = runtime / "Client_Base"
        (server / "inc/sqlite").mkdir(parents=True)
        (server / "conf/world-builder").mkdir(parents=True)
        client.mkdir(parents=True)
        (server / "world-builder.conf").write_text(
            "server_name: Fixture World Builder\n"
            "server_bind_address: 0.0.0.0\n"
            "custom_landscape: true\n"
            "unbound_fixture_setting: must-not-enter-a-project\n",
            encoding="utf-8",
        )
        (server / "inc/sqlite/world_builder_seed.db").write_bytes(
            b"fixture-project-local-database-seed\n"
        )
        capability = {
            "schemaVersion": 2,
            "manifestType": "adaptive-world-builder-runtime-capability",
            "capabilityId": "adaptive-world-builder-runtime-capability-v2",
            "profileId": "adaptive-world-builder",
            "serverBuildId": "core-framework-adaptive-builder-server-v2",
            "clientBuildId": "core-framework-adaptive-builder-client-v2",
            "loaderId": "generic-signed-layered-loader-v2-u16-elevation",
            "authoringId": "generic-signed-layered-authoring-v2-u16-elevation",
            "definitionContractId": "world-builder-definition-catalog-binding-v1",
            "assetContractId": "world-builder-client-asset-binding-v1",
            "protocolId": "world-builder-native-layered-protocol-v2-u16-elevation",
            "effectiveCompositionId": "world-builder-effective-static-composition-v1",
            "mapFormatId": "signed-layered-v1",
            "packageSchemaId": "layered-world-package-v1",
            "coordinateModel": "signed-layered-v1",
            "terrainElevation": {
                "storageEncoding": "unsigned-16",
                "minimum": 0,
                "maximum": 65535,
                "renderScale": 3,
                "legacyV1Promotion": "unsigned-byte-lossless",
                "operations": ["absolute", "raise", "lower"],
                "atomicMultiTileBounds": True,
            },
            "encodingVersions": [1, 2, 3],
            "authoring": {
                "editExistingLevels": True,
                "createLevels": True,
                "placementFamilies": ["boundary", "ground-item", "npc", "scenery"],
            },
            "activation": {
                "worldBuilderMode": True,
                "adaptiveMode": True,
                "runtimeProfile": "adaptive-world-builder",
                "builderOnly": True,
                "loopbackOnly": True,
            },
            "canonicalVoidTile": [0, 1, 8, 0, 0, 0, 0, 0, 0, 0],
        }
        write_json(
            server / "conf/world-builder/adaptive-runtime-capability-v2.json",
            capability,
        )
        for name in REQUIRED_LANGUAGE_BUNDLES:
            path = server / "conf/server/languages" / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(
                b"" if name in EMPTY_LANGUAGE_BUNDLES else b"fixture bundle\n"
            )
        for name in REQUIRED_DATABASE_PATCHES:
            path = server / "database/sqlite/patches" / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(b"-- fixture runtime migration\n")
        for _, destination, role in runtime_allowlist_records():
            path = runtime / destination
            if path.exists():
                continue
            path.parent.mkdir(parents=True, exist_ok=True)
            if path.name in EMPTY_LANGUAGE_BUNDLES:
                path.write_bytes(b"")
            elif path.name == "TileDef.xml":
                path.write_text(
                    "<TileDef-array>"
                    + "".join("<TileDef><colour>0</colour></TileDef>" for _ in range(10))
                    + "</TileDef-array>\n",
                    encoding="utf-8",
                )
            elif path.name == "DoorDef.xml":
                path.write_text(
                    "<DoorDef-array>"
                    + "".join("<DoorDef><name>wall</name></DoorDef>" for _ in range(3))
                    + "</DoorDef-array>\n",
                    encoding="utf-8",
                )
            elif path.name == "GameObjectDef.xml":
                path.write_text(
                    "<GameObjectDef-array>"
                    + "".join(
                        "<GameObjectDef><name>fixture</name>"
                        + ("<width>2</width><height>1</height>" if index == 54
                           else "<width>1</width><height>1</height>")
                        + "</GameObjectDef>"
                        for index in range(scenery_count)
                    )
                    + "</GameObjectDef-array>\n",
                    encoding="utf-8",
                )
            elif path.name == "NpcDefs.json":
                write_json(
                    path,
                    {"npcs": [{"id": 0, "name": "base-0"}, {"id": 1, "name": "base-1"}]},
                )
            elif path.name == "NpcDefsCustom.json":
                write_json(path, {"npcs": [{"id": 12, "name": "custom-12"}]})
            elif path.name == "NpcDefsMyWorld.json":
                write_json(path, {"npcs": [{"id": 99, "name": "inactive-world"}]})
            elif path.name == "NpcDefsPatch18.json":
                write_json(path, {"npcs": [{"id": 100, "name": "inactive-patch"}]})
            elif path.name == "ItemDefs.json":
                write_json(path, {"item": [{"id": 0}, {"id": 7}]})
            elif path.name == "ItemDefsCustom.json":
                write_json(path, {"items": [{"id": 42}]})
            elif path.name == "ItemDefsPatch18.json":
                write_json(path, {"items": [{"id": 99}]})
            elif path.name == "ItemDefsMyWorld.json":
                write_json(path, {"items": [{"id": 100}]})
            elif path.suffix == ".jar":
                with zipfile.ZipFile(path, "w") as archive:
                    entry = zipfile.ZipInfo(
                        "fixture/Runtime.class", (2024, 1, 2, 3, 4, 6)
                    )
                    archive.writestr(entry, b"fixture")
            else:
                path.write_bytes(("fixture " + role + "\n").encode("utf-8"))
        for jar in (
            server / "core.jar",
            server / "plugins.jar",
            client / "Open_RSC_Client.jar",
        ):
            with zipfile.ZipFile(jar, "w") as archive:
                entry = zipfile.ZipInfo(
                    "META-INF/MANIFEST.MF", (2024, 1, 2, 3, 4, 6)
                )
                archive.writestr(entry, "Manifest-Version: 1.0\n\n")
        return runtime

    @classmethod
    def make_executable_runtime(cls, root: Path) -> Path:
        runtime = cls.make_runtime(root)
        (runtime / "server/ipbans.txt").write_text(
            "must-not-enter-a-project\n", encoding="utf-8"
        )
        source = Path(cls.compile_temp.name) / "fake-native-runtime"
        classes = source / "classes"
        classes.mkdir(parents=True, exist_ok=True)
        server_source = source / "src/com/openrsc/server/Server.java"
        client_source = source / "src/fixture/FakeAdaptiveClient.java"
        server_source.parent.mkdir(parents=True, exist_ok=True)
        client_source.parent.mkdir(parents=True, exist_ok=True)
        server_source.write_text(
            r'''
package com.openrsc.server;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public final class Server {
    public static void main(String[] args) throws Exception {
        Path project = Paths.get(System.getProperty("openrsc.worldBuilderWorkspaceRoot"));
        Path control = Paths.get(System.getProperty("openrsc.worldBuilderControlDirectory"));
        Path credential = Paths.get(System.getProperty("openrsc.worldBuilderCredentialFile"));
        int port = Integer.parseInt(System.getProperty("openrsc.worldBuilderPort"));
        Files.createDirectories(control);
        Files.createDirectories(credential.getParent());
        Files.write(credential, "Abcdefghijk23456789Z".getBytes(StandardCharsets.US_ASCII));
        Path serverLogs = project.resolve("working/runtime/server/logs");
        Files.createDirectories(serverLogs);
        Files.write(serverLogs.resolve("default.log"),
            "project-local server log\n".getBytes(StandardCharsets.UTF_8));
        require("true".equals(System.getProperty("openrsc.worldBuilderAdaptiveMode")),
            "adaptive activation");
        require("adaptive-world-builder".equals(System.getProperty(
            "openrsc.layeredNativeWorldRuntimeProfile")), "runtime profile");
        require(project.resolve("working/layered-world/package").toString().equals(
            System.getProperty("openrsc.layeredNativeTerrainPackagePath")),
            "working package binding");
        require(Files.isRegularFile(Paths.get(System.getProperty(
            "openrsc.worldBuilderDefinitionEvidencePath"))), "server definitions");
        require(Files.isRegularFile(Paths.get(System.getProperty(
            "openrsc.worldBuilderAssetEvidencePath"))), "server assets");
        Files.write(project.resolve("working/runtime/server/ipbans.txt"), new byte[0]);
        Files.write(project.resolve("working/runtime/server/client.pem"),
            "fixture client key\n".getBytes(StandardCharsets.US_ASCII));
        Files.write(project.resolve("working/runtime/server/server.pem"),
            "fixture server key\n".getBytes(StandardCharsets.US_ASCII));
        Files.write(project.resolve("working/runtime/server/create_db.log"),
            "fixture database setup log\n".getBytes(StandardCharsets.US_ASCII));
        Files.write(project.resolve("working/runtime/server/create_db_error.log"),
            "fixture database error log\n".getBytes(StandardCharsets.US_ASCII));
        Files.write(control.resolve("runtime-binding.properties"),
            "fixture-binding=true\n".getBytes(StandardCharsets.US_ASCII));
        try (ServerSocket listener = new ServerSocket(
                port, 1, InetAddress.getByName("127.0.0.1"))) {
            listener.setSoTimeout(100);
            Files.write(control.resolve("ready"), "ready\n".getBytes(StandardCharsets.US_ASCII));
            while (!Files.exists(control.resolve("shutdown.request"))) {
                try (Socket ignored = listener.accept()) {
                    // The supervisor readiness probe connects and closes.
                } catch (java.net.SocketTimeoutException expected) {
                    // Poll the project-local shutdown request again.
                }
            }
        }
    }

    private static void require(boolean condition, String label) {
        if (!condition) throw new IllegalStateException("missing " + label);
    }
}
'''.strip()
            + "\n",
            encoding="utf-8",
        )
        client_source.write_text(
            r'''
package fixture;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;

public final class FakeAdaptiveClient {
    public static void main(String[] args) throws Exception {
        Path project = Paths.get(System.getProperty("openrsc.worldBuilderWorkspaceRoot"));
        Path clientLog = Paths.get(System.getProperty("spoiledmilk.clientLog"));
        require(clientLog.equals(project.resolve("logs/client-runtime.log")),
            "client runtime log confinement");
        Files.write(clientLog,
            "project-local client log\n".getBytes(StandardCharsets.UTF_8));
        require("true".equals(System.getProperty("openrsc.worldBuilderAdaptiveMode")),
            "adaptive activation");
        require(Files.isRegularFile(Paths.get(System.getProperty(
            "openrsc.worldBuilderRuntimeBindingFile"))), "runtime binding");
        require(Files.isRegularFile(Paths.get(System.getProperty(
            "openrsc.worldBuilderDefinitionEvidenceFile"))), "client definitions");
        require(Files.isRegularFile(Paths.get(System.getProperty(
            "openrsc.worldBuilderAssetEvidenceFile"))), "client assets");
        Path packageRoot = project.resolve("working/layered-world/package");
        Path terrain = packageRoot.resolve("terrain/global/lp0/xp2-yp13.raw");
        byte[] payload = Files.readAllBytes(terrain);
        for (int localX = 22; localX <= 26; localX++) {
            for (int localY = 22; localY <= 26; localY++) {
                int offset = (localX * 48 + localY) * 11;
                boolean seed = localX >= 23 && localX <= 25
                    && localY >= 23 && localY <= 25;
                require((payload[offset + 2] & 0xff) == (seed ? 0 : 1),
                    "standalone initial floor color");
                require((payload[offset + 3] & 0xff) == (seed ? 0 : 8),
                    "standalone initial floor overlay");
            }
        }
        // Simulate an authored elevation edit without changing the generated
        // visibility seed's floor color/overlay identity.
        payload[0] ^= 1;
        Files.write(terrain, payload);
        Path manifestPath = packageRoot.resolve("manifest.json");
        String manifest = new String(Files.readAllBytes(manifestPath),
            StandardCharsets.UTF_8);
        String terrainDeclaration = "\"path\": \"terrain/global/lp0/xp2-yp13.raw\"";
        int declaration = manifest.indexOf(terrainDeclaration);
        int hashStart = manifest.indexOf("\"sha256\": \"", declaration)
            + "\"sha256\": \"".length();
        require(declaration >= 0 && hashStart >= "\"sha256\": \"".length(),
            "terrain declaration");
        String updated = manifest.substring(0, hashStart) + sha256(payload)
            + manifest.substring(hashStart + 64);
        Files.write(manifestPath, updated.getBytes(StandardCharsets.UTF_8));
        Files.write(project.resolve("working/runtime/client/clientSettings.conf"),
            "generated=true\n".getBytes(StandardCharsets.UTF_8));
        Thread.sleep(250L);
    }

    private static String sha256(byte[] value) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(value);
        StringBuilder text = new StringBuilder(64);
        for (byte item : digest) text.append(String.format("%02x", item & 0xff));
        return text.toString();
    }

    private static void require(boolean condition, String label) {
        if (!condition) throw new IllegalStateException("missing " + label);
    }
}
'''.strip()
            + "\n",
            encoding="utf-8",
        )
        subprocess.run(
            [
                "javac", "-source", "8", "-target", "8", "-d", str(classes),
                str(server_source), str(client_source),
            ],
            check=True,
            cwd=ROOT,
            capture_output=True,
            text=True,
        )
        subprocess.run(
            [
                "jar", "cf", str(runtime / "server/core.jar"),
                "-C", str(classes), "com/openrsc/server/Server.class",
            ],
            check=True,
            cwd=ROOT,
            capture_output=True,
            text=True,
        )
        manifest = source / "client-manifest.mf"
        manifest.write_text(
            "Manifest-Version: 1.0\nMain-Class: fixture.FakeAdaptiveClient\n\n",
            encoding="utf-8",
        )
        subprocess.run(
            [
                "jar", "cfm", str(runtime / "Client_Base/Open_RSC_Client.jar"),
                str(manifest), "-C", str(classes), "fixture/FakeAdaptiveClient.class",
            ],
            check=True,
            cwd=ROOT,
            capture_output=True,
            text=True,
        )
        return runtime

    def discover(self, target: Path, destination: Path) -> dict:
        result = self.run_cli(
            "discover-adaptive", "--target-root", target
        )
        self.assertEqual(0, result.returncode, result.stderr)
        destination.write_text(result.stdout, encoding="utf-8")
        return json.loads(result.stdout)

    def create_project(
        self,
        installation: Path,
        runtime: Path,
        target: Path,
        report: Path,
        name: str,
        port: int,
        confirmation: str = "CREATE",
    ) -> tuple[subprocess.CompletedProcess, dict | None]:
        result = self.run_cli(
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
            name,
            "--port",
            port,
            "--confirm",
            confirmation,
        )
        return result, json.loads(result.stdout) if result.returncode == 0 else None

    def install_legacy_working_package(
        self, installation: Path, project: Path
    ) -> tuple[dict[str, tuple], dict[str, bytes]]:
        source = project / "source/layered-baseline/package"
        working = project / "working/layered-world/package"
        shutil.rmtree(working)
        shutil.copytree(source, working)
        package_manifest_path = working / "manifest.json"
        package_manifest = json.loads(package_manifest_path.read_text(encoding="utf-8"))
        terrain_expected = {}
        for index, sector in enumerate(package_manifest["terrainSectors"]):
            terrain = working / sector["path"]
            payload = bytearray(terrain.read_bytes())
            if index == 0:
                payload[0:10] = bytes((0, 31, 32, 33, 34, 35, 36, 37, 38, 39))
                payload[10:20] = bytes((255, 41, 42, 43, 44, 45, 46, 47, 48, 49))
            terrain.write_bytes(payload)
            sector["sha256"] = sha256(terrain)
            terrain_expected[sector["path"]] = bytes(payload)
        write_json(package_manifest_path, package_manifest)

        manifest_path = project / "project.json"
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        manifest["fingerprints"]["workingSha256"] = package_fingerprint(working)
        manifest["projectFingerprintSha256"] = "0" * 64
        locator = manifest["target"]["locatorDisplay"]
        manifest["target"]["locatorDisplay"] = ""
        manifest["projectFingerprintSha256"] = canonical_hash(manifest)
        manifest["target"]["locatorDisplay"] = locator
        write_json(manifest_path, manifest)
        manifest_hash = sha256(manifest_path)

        registry_path = installation / "project-registry.json"
        registry = json.loads(registry_path.read_text(encoding="utf-8"))
        record = next(
            item for item in registry["projects"]
            if item["projectId"] == manifest["projectId"]
        )
        record["manifestSha256"] = manifest_hash
        registry["registryFingerprintSha256"] = "0" * 64
        registry["registryFingerprintSha256"] = canonical_hash(registry)
        write_json(registry_path, registry)
        active_path = installation / "active-project.json"
        active = json.loads(active_path.read_text(encoding="utf-8"))
        active["manifestSha256"] = manifest_hash
        write_json(active_path, active)

        placements = {
            item["path"]: (working / item["path"]).read_bytes()
            for item in package_manifest["placementSets"]
        }
        return tree_bytes(working), {**terrain_expected, **placements}

    def run_promotion_crash(
        self, project: Path, milestone: str
    ) -> subprocess.CompletedProcess:
        return subprocess.run(
            [
                "java", "-cp", str(self.classes),
                "com.openrsc.worldbuilder.WidePromotionCrashHarness",
                str(project), milestone,
            ],
            cwd=ROOT,
            text=True,
            capture_output=True,
        )

    def run_desktop_launcher(
        self,
        installation: Path,
        runtime: Path,
        detected_source: Path,
        port: int,
        action: str,
        selected_source: Path | None,
        marker: Path,
    ) -> subprocess.CompletedProcess:
        return subprocess.run(
            [
                "java", "-Djava.awt.headless=true", "-cp", str(self.classes),
                "com.openrsc.worldbuilder.DesktopLauncherHarness",
                str(installation), str(runtime), str(detected_source), str(port),
                action, str(selected_source) if selected_source else "-", str(marker),
            ],
            cwd=ROOT,
            text=True,
            capture_output=True,
        )

    def run_injected(
        self,
        installation: Path,
        runtime: Path,
        target: Path,
        report: Path,
        mode: str,
    ) -> subprocess.CompletedProcess:
        return subprocess.run(
            [
                "java",
                "-cp",
                str(self.classes),
                "com.openrsc.worldbuilder.AdaptiveProjectFailureHarness",
                str(installation),
                str(runtime),
                str(target),
                str(report),
                "Injected project",
                "43821",
                mode,
            ],
            cwd=ROOT,
            text=True,
            capture_output=True,
        )

    def run_supervision(
        self, project: Path, mode: str | None = None
    ) -> subprocess.CompletedProcess:
        arguments = [
            "java",
            "-cp",
            str(self.classes),
            "com.openrsc.worldbuilder.AdaptiveProjectSupervisorHarness",
            str(project),
            str(self.classes),
        ]
        if mode is not None:
            arguments.append(mode)
        return subprocess.run(
            arguments,
            cwd=ROOT,
            text=True,
            capture_output=True,
            timeout=20,
        )

    def run_region_failure(
        self, project: Path, snapshot: str, plan: str, confirmation: str, milestone: str
    ) -> subprocess.CompletedProcess:
        return subprocess.run(
            [
                "java", "-cp", str(self.classes),
                "com.openrsc.worldbuilder.RegionOperationFailureHarness",
                str(project), snapshot, plan, confirmation, milestone,
            ],
            cwd=ROOT,
            text=True,
            capture_output=True,
            timeout=20,
        )

    @staticmethod
    def change_working_terrain(project: Path) -> None:
        manifest_path = project / "working/layered-world/package/manifest.json"
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        declaration = manifest["terrainSectors"][0]
        terrain = manifest_path.parent / declaration["path"]
        payload = bytearray(terrain.read_bytes())
        payload[0] ^= 1
        terrain.write_bytes(payload)
        declaration["sha256"] = sha256(terrain)
        write_json(manifest_path, manifest)

    @staticmethod
    def place_representative_definitions(project: Path) -> dict:
        manifest_path = project / "working/layered-world/package/manifest.json"
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        declaration = next(
            value for value in manifest["placementSets"] if value["level"] == 0
        )
        placement_path = manifest_path.parent / declaration["path"]
        placement = json.loads(placement_path.read_text(encoding="utf-8"))
        placement["boundaries"] = [
            {
                "boundaryId": 1,
                "direction": 0,
                "placementId": "boundary-1",
                "position": {"x": 119, "y": 648},
            }
        ]
        placement["groundItems"] = [
            {
                "amount": 1,
                "itemId": 42,
                "placementId": "ground-item-1",
                "position": {"x": 120, "y": 648},
                "respawnSeconds": 30,
            }
        ]
        placement["npcs"] = [
            {
                "npcId": 2,
                "placementId": "npc-1",
                "roamBounds": {
                    "maximum": {"x": 121, "y": 649},
                    "minimum": {"x": 120, "y": 648},
                },
                "start": {"x": 120, "y": 648},
            }
        ]
        placement["scenery"] = [
            {
                "direction": 2,
                "placementId": "scenery-1",
                "position": {"x": 121, "y": 648},
                "sceneryId": 3,
            }
        ]
        write_json(placement_path, placement)
        declaration["sha256"] = sha256(placement_path)
        write_json(manifest_path, manifest)
        return placement

    @staticmethod
    def add_empty_level(project: Path, level: int) -> None:
        """Add one content-identical signed level using canonical package order."""
        manifest_path = project / "working/layered-world/package/manifest.json"
        package = manifest_path.parent
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        source_terrain = package / manifest["terrainSectors"][0]["path"]
        token = f"m{-level}" if level < 0 else f"p{level}"
        terrain_relative = f"terrain/global/l{token}/xp2-yp13.raw"
        terrain_path = package / terrain_relative
        terrain_path.parent.mkdir(parents=True)
        terrain_path.write_bytes(source_terrain.read_bytes())
        placement_relative = f"placements/global/l{token}.json"
        placement_path = package / placement_relative
        placement = {
            "schemaVersion": 3,
            "encoding": "layered-world-placements-v3",
            "worldSpace": "global",
            "level": level,
            "boundaries": [],
            "groundItems": [],
            "npcs": [],
            "scenery": [],
        }
        write_json(placement_path, placement)
        manifest["levels"].append(
            {
                "level": level,
                "name": f"Level {level}",
                "role": f"level-{token}",
                "worldSpace": "global",
            }
        )
        manifest["levels"].sort(key=lambda value: value["level"])
        manifest["terrainSectors"].append(
            {
                "encoding": "raw-layered-sector-v2-u16",
                "level": level,
                "path": terrain_relative,
                "sectorX": 2,
                "sectorY": 13,
                "sha256": sha256(terrain_path),
                "worldSpace": "global",
            }
        )
        manifest["terrainSectors"].sort(
            key=lambda value: (value["level"], value["sectorX"], value["sectorY"])
        )
        manifest["placementSets"].append(
            {
                "encoding": "layered-world-placements-v3",
                "id": f"region-fixture-level-{token}",
                "level": level,
                "path": placement_relative,
                "sha256": sha256(placement_path),
                "worldSpace": "global",
            }
        )
        manifest["placementSets"].sort(key=lambda value: value["level"])
        write_json(manifest_path, manifest)

    @staticmethod
    def write_region_selection(
        path: Path, markers: list[tuple[int, int]], levels: list[int]
    ) -> dict:
        selection = {
            "schemaVersion": 1,
            "manifestType": "world-builder-region-selection",
            "worldSpace": "global",
            "markers": [
                {"marker": index, "x": x, "y": y}
                for index, (x, y) in enumerate(markers, start=1)
            ],
            "levels": levels,
            "selectionFingerprintSha256": "0" * 64,
        }
        selection["selectionFingerprintSha256"] = canonical_hash(selection)
        write_json(path, selection)
        return selection

    @staticmethod
    def selected_tile_bytes(package: Path, x: int, y: int, level: int = 0) -> bytes:
        manifest = json.loads(
            (package / "manifest.json").read_text(encoding="utf-8")
        )
        declaration = next(
            value
            for value in manifest["terrainSectors"]
            if value["level"] == level
            and value["sectorX"] == x // 48
            and value["sectorY"] == y // 48
        )
        sector = (package / declaration["path"]).read_bytes()
        width = 11 if declaration["encoding"] == "raw-layered-sector-v2-u16" else 10
        offset = ((x % 48) * 48 + (y % 48)) * width
        return sector[offset : offset + width]

    @staticmethod
    def set_tile_elevation(package: Path, x: int, y: int, elevation: int,
                           level: int = 0) -> None:
        manifest_path = package / "manifest.json"
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        declaration = next(
            value for value in manifest["terrainSectors"]
            if value["level"] == level
            and value["sectorX"] == x // 48
            and value["sectorY"] == y // 48
        )
        if declaration["encoding"] != "raw-layered-sector-v2-u16":
            raise AssertionError("wide-elevation fixture requires v2 terrain")
        terrain = package / declaration["path"]
        payload = bytearray(terrain.read_bytes())
        offset = ((x % 48) * 48 + (y % 48)) * 11
        payload[offset : offset + 2] = elevation.to_bytes(2, "big")
        terrain.write_bytes(payload)
        declaration["sha256"] = sha256(terrain)
        write_json(manifest_path, manifest)

    @staticmethod
    def rewrite_region_bundle(bundle: Path, mutate) -> None:
        with zipfile.ZipFile(bundle, "r") as archive:
            snapshot = json.loads(archive.read("snapshot.json"))
        mutate(snapshot)
        snapshot["snapshotId"] = "0" * 64
        snapshot["snapshotFingerprintSha256"] = "0" * 64
        snapshot_hash = canonical_hash(snapshot)
        snapshot["snapshotId"] = snapshot_hash
        snapshot["snapshotFingerprintSha256"] = snapshot_hash
        snapshot_bytes = (
            json.dumps(snapshot, sort_keys=True, indent=2, ensure_ascii=False) + "\n"
        ).encode("utf-8")
        manifest = {
            "schemaVersion": 1,
            "manifestType": "world-builder-region-bundle",
            "formatId": "portable-region-bundle-v1",
            "snapshotId": snapshot_hash,
            "files": [
                {
                    "role": "snapshot",
                    "relativePath": "snapshot.json",
                    "size": len(snapshot_bytes),
                    "sha256": hashlib.sha256(snapshot_bytes).hexdigest(),
                }
            ],
            "bundleFingerprintSha256": "0" * 64,
        }
        manifest["bundleFingerprintSha256"] = canonical_hash(manifest)
        manifest_bytes = (
            json.dumps(manifest, sort_keys=True, indent=2, ensure_ascii=False) + "\n"
        ).encode("utf-8")
        with zipfile.ZipFile(bundle, "w", compression=zipfile.ZIP_STORED) as archive:
            archive.writestr("manifest.json", manifest_bytes)
            archive.writestr("snapshot.json", snapshot_bytes)

    @staticmethod
    def rewrite_as_legacy_standalone(installation: Path, project: Path) -> None:
        catalog_path = project / "source/runtime/default-definition-catalog.json"
        catalog = {
            "schemaVersion": 1,
            "manifestType": "world-builder-definition-catalog",
            "catalogId": "world-builder-empty-default-v1",
            "tiles": [0, 7],
            "boundaries": [],
            "scenery": [],
            "npcs": [],
            "groundItems": [],
        }
        write_json(catalog_path, catalog)
        catalog_hash = sha256(catalog_path)
        for evidence in (
            project / "working/runtime/server/evidence/adaptive-definitions.json",
            project / "working/runtime/client/evidence/adaptive-definitions.json",
        ):
            evidence.write_bytes(catalog_path.read_bytes())

        runtime_path = project / "source/runtime/default-runtime-evidence.json"
        runtime = json.loads(runtime_path.read_text(encoding="utf-8"))
        runtime["definitionCatalogId"] = catalog["catalogId"]
        runtime["definitionCatalogSha256"] = catalog_hash
        write_json(runtime_path, runtime)
        runtime_hash = sha256(runtime_path)

        descriptor_path = project / "source/original/empty-world-v1.json"
        descriptor = json.loads(descriptor_path.read_text(encoding="utf-8"))
        descriptor["catalog"] = {
            "catalogId": catalog["catalogId"],
            "sha256": catalog_hash,
        }
        descriptor["runtime"]["sha256"] = runtime_hash
        write_json(descriptor_path, descriptor)
        descriptor_hash = sha256(descriptor_path)

        snapshot_path = project / "source/snapshot-manifest.json"
        snapshot = json.loads(snapshot_path.read_text(encoding="utf-8"))
        snapshot["originDescriptor"]["sha256"] = descriptor_hash
        for record in snapshot["originalFiles"]:
            if record["role"] == "empty-origin":
                record["size"] = descriptor_path.stat().st_size
                record["sha256"] = descriptor_hash
        for record in snapshot["definitionRuntimeFiles"]:
            if record["role"] == "default-definition-catalog":
                record["size"] = catalog_path.stat().st_size
                record["sha256"] = catalog_hash
            elif record["role"] == "default-runtime-evidence":
                record["size"] = runtime_path.stat().st_size
                record["sha256"] = runtime_hash
        snapshot["sourceFingerprintSha256"] = "0" * 64
        snapshot["sourceFingerprintSha256"] = canonical_hash(snapshot)
        write_json(snapshot_path, snapshot)

        manifest_path = project / "project.json"
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        manifest["standalone"]["catalogId"] = catalog["catalogId"]
        manifest["fingerprints"]["definitionsSha256"] = catalog_hash
        manifest["fingerprints"]["sourceSha256"] = snapshot["sourceFingerprintSha256"]
        manifest["projectFingerprintSha256"] = "0" * 64
        manifest["projectFingerprintSha256"] = canonical_hash(manifest)
        write_json(manifest_path, manifest)
        manifest_hash = sha256(manifest_path)

        registry_path = installation / "project-registry.json"
        registry = json.loads(registry_path.read_text(encoding="utf-8"))
        record = next(
            value
            for value in registry["projects"]
            if value["projectId"] == manifest["projectId"]
        )
        record["manifestSha256"] = manifest_hash
        registry["registryFingerprintSha256"] = "0" * 64
        registry["registryFingerprintSha256"] = canonical_hash(registry)
        write_json(registry_path, registry)

        active_path = installation / "active-project.json"
        active = json.loads(active_path.read_text(encoding="utf-8"))
        active["manifestSha256"] = manifest_hash
        write_json(active_path, active)

    def assert_canonical_empty_package(self, package: Path) -> None:
        manifest = json.loads((package / "manifest.json").read_text(encoding="utf-8"))
        self.assertEqual(
            [{"id": "global", "kind": "static"}], manifest["worldSpaces"]
        )
        self.assertEqual(1, len(manifest["levels"]))
        self.assertEqual(0, manifest["levels"][0]["level"])
        self.assertEqual("global", manifest["levels"][0]["worldSpace"])

        self.assertEqual(1, len(manifest["terrainSectors"]))
        sector = manifest["terrainSectors"][0]
        self.assertEqual(
            {
                "encoding": "raw-layered-sector-v2-u16",
                "level": 0,
                "path": "terrain/global/lp0/xp2-yp13.raw",
                "sectorX": 2,
                "sectorY": 13,
                "worldSpace": "global",
            },
            {key: sector[key] for key in (
                "encoding", "level", "path", "sectorX", "sectorY", "worldSpace"
            )},
        )
        terrain = package / sector["path"]
        payload = terrain.read_bytes()
        self.assertEqual(STANDALONE_SEED_SECTOR, payload)
        self.assertEqual(sha256(terrain), sector["sha256"])
        minimum_x = sector["sectorX"] * 48
        minimum_y = sector["sectorY"] * 48
        self.assertGreaterEqual(STANDALONE_INITIAL_LOCATION["x"] - minimum_x, 23)
        self.assertGreaterEqual(
            minimum_x + 47 - STANDALONE_INITIAL_LOCATION["x"], 23
        )
        self.assertGreaterEqual(STANDALONE_INITIAL_LOCATION["y"] - minimum_y, 23)
        self.assertGreaterEqual(
            minimum_y + 47 - STANDALONE_INITIAL_LOCATION["y"], 23
        )
        tiles = [payload[offset : offset + 11] for offset in range(0, len(payload), 11)]
        self.assertEqual(9, tiles.count(VISIBLE_FLOOR_TILE))
        self.assertEqual(48 * 48 - 9, tiles.count(CANONICAL_VOID_TILE))

        self.assertEqual(1, len(manifest["placementSets"]))
        placement_set = manifest["placementSets"][0]
        self.assertEqual(0, placement_set["level"])
        self.assertEqual("global", placement_set["worldSpace"])
        self.assertEqual("placements/global/lp0.json", placement_set["path"])
        placement_path = package / placement_set["path"]
        placement = json.loads(placement_path.read_text(encoding="utf-8"))
        self.assertEqual(0, placement["level"])
        self.assertEqual("global", placement["worldSpace"])
        self.assertEqual([], placement["boundaries"])
        self.assertEqual([], placement["groundItems"])
        self.assertEqual([], placement["npcs"])
        self.assertEqual([], placement["scenery"])
        self.assertEqual(sha256(placement_path), placement_set["sha256"])

    def test_standalone_empty_create_save_reopen_and_no_target_mutation(self):
        with tempfile.TemporaryDirectory(prefix="adaptive-project-empty-") as temp:
            base = Path(temp)
            installation = base / "World Builder 2"
            installation.mkdir()
            runtime = self.make_executable_runtime(installation)
            target = base / "ordinary-parent"
            target.mkdir()
            report = base / "standalone-report.json"
            discovery = self.discover(target, report)
            before_target = tree_bytes(target)

            refused, _ = self.create_project(
                installation, runtime, target, report, "Empty project", 43801, "create"
            )
            self.assertEqual(3, refused.returncode)
            self.assertIn("CREATE", refused.stderr)
            self.assertFalse((installation / "project-registry.json").exists())

            created, summary = self.create_project(
                installation, runtime, target, report, "Empty project", 43801
            )
            self.assertEqual(0, created.returncode, created.stderr)
            self.assertEqual("standalone-empty", summary["origin"])
            self.assertEqual("ready-standalone", summary["state"])
            self.assertEqual(before_target, tree_bytes(target))
            project = Path(summary["projectRoot"])
            source_before = tree_bytes(project / "source")
            baseline_before = tree_bytes(project / "source/layered-baseline/package")
            self.assertEqual(
                "must-not-enter-a-project\n",
                (runtime / "server/ipbans.txt").read_text(encoding="utf-8"),
            )
            self.assertFalse(
                (project / "working/runtime/server/ipbans.txt").exists()
            )
            isolated_config = (
                project / "working/runtime/server/world-builder.conf"
            ).read_text(encoding="utf-8")
            self.assertIn("server_bind_address: 127.0.0.1\n", isolated_config)
            self.assertIn("custom_landscape: false\n", isolated_config)
            for editor_presentation in (
                "want_custom_ui: true\n",
                "side_menu_toggle: true\n",
                "fog_toggle: true\n",
                "ground_item_toggle: true\n",
                "ground_item_names: true\n",
                "auto_message_switch_toggle: true\n",
                "inventory_count_toggle: true\n",
                "zoom_view_toggle: true\n",
                "show_roof_toggle: true\n",
                "show_underground_flicker_toggle: true\n",
                "allow_resize: true\n",
            ):
                self.assertIn(editor_presentation, isolated_config)
            self.assertIn("want_sync_scene_baseline: true\n", isolated_config)
            self.assertIn("want_discord_bot: false\n", isolated_config)
            self.assertIn("restrict_item_id: -1\n", isolated_config)
            self.assertIn("restrict_scenery_id: -1\n", isolated_config)
            self.assertNotIn("unbound_fixture_setting", isolated_config)

            stored_report = json.loads(
                (project / "discovery/report.json").read_text(encoding="utf-8")
            )
            self.assertEqual("", stored_report["targetRootDisplay"])
            self.assertEqual(
                discovery["discoveryFingerprintSha256"],
                stored_report["discoveryFingerprintSha256"],
            )
            descriptor = json.loads(
                (project / "source/original/empty-world-v1.json").read_text(
                    encoding="utf-8"
                )
            )
            self.assertEqual(
                STANDALONE_INITIAL_LOCATION, descriptor["initialLocation"]
            )
            runtime_evidence = json.loads(
                (
                    project / "source/runtime/default-runtime-evidence.json"
                ).read_text(encoding="utf-8")
            )
            self.assertEqual(
                {
                    "createFromVoid": True,
                    "initialLayer": STANDALONE_INITIAL_LOCATION["level"],
                    "initialX": STANDALONE_INITIAL_LOCATION["x"],
                    "initialY": STANDALONE_INITIAL_LOCATION["y"],
                },
                runtime_evidence["authoring"],
            )
            runtime_metadata = json.loads(
                (project / "working/runtime/runtime.json").read_text(encoding="utf-8")
            )
            self.assertEqual(
                STANDALONE_INITIAL_LOCATION["level"],
                runtime_metadata["initialLayer"],
            )
            self.assertEqual(
                STANDALONE_INITIAL_LOCATION["x"], runtime_metadata["initialX"]
            )
            self.assertEqual(
                STANDALONE_INITIAL_LOCATION["y"], runtime_metadata["initialY"]
            )
            package = project / "working/layered-world/package"
            self.assert_canonical_empty_package(
                project / "source/layered-baseline/package"
            )
            self.assert_canonical_empty_package(package)
            catalog = json.loads(
                (
                    project / "source/runtime/default-definition-catalog.json"
                ).read_text(encoding="utf-8")
            )
            self.assertEqual(list(range(10)), catalog["tiles"])
            self.assertEqual([0, 1, 2], catalog["boundaries"])
            self.assertEqual([0, 1, 2, 3], catalog["scenery"])
            self.assertEqual([0, 1, 2], catalog["npcs"])
            self.assertEqual([0, 7, 42], catalog["groundItems"])

            self.change_working_terrain(project)
            unsaved = self.run_cli(
                "open-project", "--installation-root", installation, "--target-root", target
            )
            self.assertEqual(3, unsaved.returncode)
            self.assertIn("save-project", unsaved.stderr)
            saved = self.run_cli("save-project", "--project", project)
            self.assertEqual(0, saved.returncode, saved.stderr)
            reopened = self.run_cli(
                "open-project", "--installation-root", installation, "--target-root", target
            )
            self.assertEqual(0, reopened.returncode, reopened.stderr)
            self.assertEqual(before_target, tree_bytes(target))
            self.assertEqual(source_before, tree_bytes(project / "source"))

            for command in ("import-active-adaptive", "undo-active-adaptive"):
                refused_active = self.run_cli(
                    command, "--installation-root", installation
                )
                self.assertEqual(3, refused_active.returncode)
                self.assertIn("NO_TARGET", refused_active.stderr)
                self.assertEqual(before_target, tree_bytes(target))
            working_before_native = json.loads(
                (project / "project.json").read_text(encoding="utf-8")
            )["fingerprints"]["workingSha256"]
            native = self.run_cli("run-adaptive-project", "--project", project)
            self.assertEqual(0, native.returncode, native.stderr)
            working_after_native = json.loads(
                (project / "project.json").read_text(encoding="utf-8")
            )["fingerprints"]["workingSha256"]
            self.assertNotEqual(working_before_native, working_after_native)
            self.assertTrue((project / "working/runtime/server/ipbans.txt").is_file())
            for name in ("client.pem", "server.pem"):
                source_key = runtime / "server" / name
                project_key = project / "working/runtime/server" / name
                self.assertFalse(source_key.exists(), source_key)
                self.assertTrue(project_key.is_file(), project_key)
                self.assertFalse(project_key.is_symlink(), project_key)
                self.assertEqual(1, project_key.stat().st_nlink)
                self.assertGreater(project_key.stat().st_size, 0)
            for name in ("create_db.log", "create_db_error.log"):
                self.assertFalse((project / "working/runtime/server" / name).exists())
                self.assertTrue(
                    (project / "working/runtime/server/logs" / name).is_file()
                )
            self.assertTrue(
                (project / "working/runtime/client/clientSettings.conf").is_file()
            )
            self.assertTrue(
                (project / "working/runtime/server/logs/default.log").is_file()
            )
            self.assertTrue((project / "logs/client-runtime.log").is_file())
            supervised = self.run_supervision(project, "finalization")
            self.assertEqual(0, supervised.returncode, supervised.stdout + supervised.stderr)
            self.assertEqual("adaptive-supervision-ok\n", supervised.stdout)
            self.assertTrue((project / "working/runtime/server/ipbans.txt").is_file())
            self.assertTrue(
                (project / "working/runtime/client/clientSettings.conf").is_file()
            )
            self.assertEqual(before_target, tree_bytes(target))
            self.assertEqual(source_before, tree_bytes(project / "source"))
            self.assertEqual(
                baseline_before,
                tree_bytes(project / "source/layered-baseline/package"),
            )

            missing_target = base / "must-not-be-created-or-read"
            import_result = self.run_cli(
                "import",
                "--workspace",
                project,
                "--export",
                base / "missing-export",
                "--target-root",
                missing_target,
                "--dry-run",
            )
            self.assertEqual(3, import_result.returncode)
            self.assertIn("NO_TARGET", import_result.stderr)
            self.assertFalse(missing_target.exists())
            undo_result = self.run_cli(
                "undo-import",
                "--workspace",
                project,
                "--target-root",
                missing_target,
                "--dry-run",
            )
            self.assertEqual(3, undo_result.returncode)
            self.assertIn("NO_TARGET", undo_result.stderr)
            self.assertFalse(missing_target.exists())

    def test_standalone_runtime_catalog_persists_all_placement_families(self):
        with tempfile.TemporaryDirectory(prefix="adaptive-placement-catalog-") as temp:
            base = Path(temp)
            installation = base / "World Builder 2"
            installation.mkdir()
            runtime = self.make_runtime(installation)
            target = base / "ordinary-parent"
            target.mkdir()
            report = base / "report.json"
            self.discover(target, report)
            target_before = tree_bytes(target)
            runtime_before = tree_bytes(runtime)

            created, summary = self.create_project(
                installation, runtime, target, report, "Placement lifecycle", 43842
            )
            self.assertEqual(0, created.returncode, created.stderr)
            project = Path(summary["projectRoot"])
            source_before = tree_bytes(project / "source")
            catalog_path = project / "source/runtime/default-definition-catalog.json"
            catalog_hash = sha256(catalog_path)
            catalog = json.loads(catalog_path.read_text(encoding="utf-8"))
            self.assertEqual("world-builder-runtime-default-v1", catalog["catalogId"])
            self.assertEqual(list(range(10)), catalog["tiles"])
            self.assertEqual([0, 1, 2], catalog["boundaries"])
            self.assertEqual([0, 1, 2, 3], catalog["scenery"])
            self.assertEqual([0, 1, 2], catalog["npcs"])
            self.assertEqual([0, 7, 42], catalog["groundItems"])

            snapshot = json.loads(
                (project / "source/snapshot-manifest.json").read_text(encoding="utf-8")
            )
            catalog_record = next(
                record
                for record in snapshot["definitionRuntimeFiles"]
                if record["role"] == "default-definition-catalog"
            )
            self.assertEqual(catalog_hash, catalog_record["sha256"])
            project_manifest = json.loads(
                (project / "project.json").read_text(encoding="utf-8")
            )
            self.assertEqual(
                catalog_hash, project_manifest["fingerprints"]["definitionsSha256"]
            )
            runtime_evidence = json.loads(
                (project / "source/runtime/default-runtime-evidence.json").read_text(
                    encoding="utf-8"
                )
            )
            self.assertEqual(catalog_hash, runtime_evidence["definitionCatalogSha256"])
            for evidence in (
                project / "working/runtime/server/evidence/adaptive-definitions.json",
                project / "working/runtime/client/evidence/adaptive-definitions.json",
            ):
                self.assertEqual(catalog_path.read_bytes(), evidence.read_bytes())

            expected = self.place_representative_definitions(project)
            saved = self.run_cli("save-project", "--project", project)
            self.assertEqual(0, saved.returncode, saved.stderr)
            supervised = self.run_supervision(project)
            self.assertEqual(
                0, supervised.returncode, supervised.stdout + supervised.stderr
            )
            reopened = self.run_cli(
                "open-project",
                "--installation-root",
                installation,
                "--target-root",
                target,
            )
            self.assertEqual(0, reopened.returncode, reopened.stderr)

            exported = self.run_cli("export-adaptive", "--project", project)
            self.assertEqual(0, exported.returncode, exported.stderr)
            export_root = Path(json.loads(exported.stdout)["exportDirectory"])
            exported_manifest = json.loads(
                (export_root / "manifest.json").read_text(encoding="utf-8")
            )
            definitions_runtime = hashlib.sha256(
                (catalog_hash + "\0" + project_manifest["fingerprints"]["runtimeSha256"] + "\0")
                .encode("utf-8")
            ).hexdigest()
            self.assertEqual(
                definitions_runtime,
                exported_manifest["lineage"]["definitionsRuntimeSha256"],
            )
            exported_placement = json.loads(
                (
                    export_root / "package/placements/global/lp0.json"
                ).read_text(encoding="utf-8")
            )
            for family in ("boundaries", "groundItems", "npcs", "scenery"):
                self.assertEqual(expected[family], exported_placement[family])
            validation = json.loads(
                (export_root / "validation-report.json").read_text(encoding="utf-8")
            )
            self.assertEqual(1, validation["boundaryCount"])
            self.assertEqual(1, validation["groundItemCount"])
            self.assertEqual(1, validation["npcCount"])
            self.assertEqual(1, validation["sceneryCount"])
            self.assertEqual(source_before, tree_bytes(project / "source"))
            self.assertEqual(runtime_before, tree_bytes(runtime))
            self.assertEqual(target_before, tree_bytes(target))

    def test_region_copy_cut_paste_round_trip_is_exact_and_project_local(self):
        with tempfile.TemporaryDirectory(prefix="adaptive-region-round-trip-") as temp:
            base = Path(temp)
            installation = base / "World Builder 2"
            installation.mkdir()
            runtime = self.make_runtime(installation)
            target = base / "ordinary-parent"
            target.mkdir()
            report = base / "report.json"
            self.discover(target, report)
            created, summary = self.create_project(
                installation, runtime, target, report, "Region round trip", 43860
            )
            self.assertEqual(0, created.returncode, created.stderr)
            project = Path(summary["projectRoot"])
            self.add_empty_level(project, -1)
            expected_placements = self.place_representative_definitions(project)
            package = project / "working/layered-world/package"
            wide_values = [0, 255, 256, 12000, 65535, 12345]
            for (x, y), elevation in zip(
                ((119, 648), (119, 649), (120, 648), (120, 649),
                 (121, 648), (121, 649)),
                wide_values,
            ):
                self.set_tile_elevation(package, x, y, elevation)
            saved = self.run_cli("save-project", "--project", project)
            self.assertEqual(0, saved.returncode, saved.stderr)
            reopened = self.run_cli(
                "open-project", "--installation-root", installation, "--validate-only"
            )
            self.assertEqual(0, reopened.returncode, reopened.stderr)
            package_before = tree_bytes(package)
            source_before = tree_bytes(project / "source")
            target_before = tree_bytes(target)
            project_manifest_before = (project / "project.json").read_bytes()

            selection = base / "selection.json"
            self.write_region_selection(
                selection,
                [(119, 648), (121, 648), (121, 649), (119, 649)],
                [-1, 0],
            )
            copied = self.run_cli(
                "region-copy",
                "--project",
                project,
                "--selection",
                selection,
                "--name",
                "Portable structure",
            )
            self.assertEqual(0, copied.returncode, copied.stderr)
            copy_result = json.loads(copied.stdout)
            self.assertFalse(copy_result["worldModified"])
            self.assertEqual(12, copy_result["tileCount"])
            self.assertEqual(4, copy_result["placementCount"])
            self.assertTrue(
                any(
                    value["family"] == "boundary"
                    and value["crossesBoundary"]
                    for value in copy_result["footprintBoundaryReports"]
                )
            )
            self.assertEqual(package_before, tree_bytes(package))
            self.assertEqual(project_manifest_before, (project / "project.json").read_bytes())
            self.assertEqual(source_before, tree_bytes(project / "source"))
            self.assertEqual(target_before, tree_bytes(target))

            bundle = project / copy_result["libraryRelativePath"]
            self.assertTrue(bundle.is_file())
            self.assertNotIn(str(base).encode("utf-8"), bundle.read_bytes())
            with zipfile.ZipFile(bundle, "r") as archive:
                self.assertEqual(
                    ["manifest.json", "snapshot.json"], archive.namelist()
                )
                snapshot = json.loads(archive.read("snapshot.json"))
            self.assertEqual(copy_result["snapshotId"], snapshot["snapshotId"])
            self.assertEqual(2, snapshot["schemaVersion"])
            self.assertEqual("global", snapshot["worldSpace"])
            self.assertEqual(
                {0, 1}, {value["levelOffset"] for value in snapshot["levels"]}
            )
            surface = next(
                value for value in snapshot["levels"] if value["levelOffset"] == 1
            )
            captured = {
                (tile["xOffset"], tile["yOffset"]): tile["elevation"]
                for tile in surface["tiles"]
            }
            self.assertEqual(
                dict(zip(
                    ((0, 0), (0, 1), (1, 0), (1, 1), (2, 0), (2, 1)),
                    wide_values,
                )),
                captured,
            )
            self.assertEqual(
                {
                    "boundaries": 1,
                    "groundItems": 1,
                    "npcs": 1,
                    "scenery": 1,
                },
                {key: len(value) for key, value in snapshot["placements"].items()},
            )
            self.assertTrue(
                any(
                    dependency["kind"] == "definition-catalog"
                    for dependency in snapshot["dependencies"]
                )
            )
            self.assertTrue(all(not value["bundled"] for value in snapshot["dependencies"]))

            copied_again = self.run_cli(
                "region-copy",
                "--project",
                project,
                "--selection",
                selection,
                "--name",
                "Portable structure",
            )
            self.assertEqual(0, copied_again.returncode, copied_again.stderr)
            second = json.loads(copied_again.stdout)
            self.assertEqual(copy_result["snapshotId"], second["snapshotId"])
            self.assertEqual(copy_result["bundleSha256"], second["bundleSha256"])
            self.assertFalse(second["libraryEntryCreated"])

            library_stage = bundle.with_name(
                "." + bundle.name + ".staging-" + copy_result["bundleSha256"]
            )
            os.link(bundle, library_stage)
            recovered_published = self.run_cli(
                "region-copy", "--project", project, "--selection", selection,
                "--name", "Portable structure"
            )
            self.assertEqual(0, recovered_published.returncode, recovered_published.stderr)
            self.assertFalse(library_stage.exists())
            self.assertEqual(1, bundle.stat().st_nlink)
            bundle.rename(library_stage)
            recovered_staged = self.run_cli(
                "region-copy", "--project", project, "--selection", selection,
                "--name", "Portable structure"
            )
            self.assertEqual(0, recovered_staged.returncode, recovered_staged.stderr)
            self.assertFalse(library_stage.exists())
            self.assertTrue(bundle.is_file())
            self.assertTrue(json.loads(recovered_staged.stdout)["libraryEntryCreated"])

            cut_preview = self.run_cli(
                "region-cut-preview",
                "--project",
                project,
                "--selection",
                selection,
                "--name",
                "Portable structure",
            )
            self.assertEqual(0, cut_preview.returncode, cut_preview.stderr)
            cut = json.loads(cut_preview.stdout)
            plan = cut["operationPlan"]
            self.assertFalse(plan["blocked"])
            self.assertFalse(plan["overwriteRequired"])
            plan_hash = plan["planFingerprintSha256"]

            refused = self.run_cli(
                "region-cut-apply",
                "--project",
                project,
                "--snapshot",
                copy_result["snapshotId"],
                "--expected-plan",
                plan_hash,
                "--confirm",
                "CUT stale",
            )
            self.assertEqual(3, refused.returncode, refused.stderr)
            self.assertEqual(package_before, tree_bytes(package))
            self.assertTrue(bundle.is_file())

            injected = self.run_region_failure(
                project,
                copy_result["snapshotId"],
                plan_hash,
                "CUT " + plan_hash,
                "package-published",
            )
            self.assertEqual(4, injected.returncode, injected.stderr)
            self.assertIn("Region publication failed", injected.stderr)
            self.assertEqual(package_before, tree_bytes(package))
            self.assertEqual(
                project_manifest_before, (project / "project.json").read_bytes()
            )
            self.assertFalse(
                (project / "working/layered-world/.region-original-v1").exists()
            )
            self.assertFalse(
                list((project / "working/layered-world").glob(".region-stage-*"))
            )
            self.assertTrue(bundle.is_file())

            cut_apply = self.run_cli(
                "region-cut-apply",
                "--project",
                project,
                "--snapshot",
                copy_result["snapshotId"],
                "--expected-plan",
                plan_hash,
                "--confirm",
                "CUT " + plan_hash,
            )
            self.assertEqual(0, cut_apply.returncode, cut_apply.stderr)
            void_tile = CANONICAL_VOID_TILE
            for level in (-1, 0):
                for x in range(119, 122):
                    for y in range(648, 650):
                        self.assertEqual(
                            void_tile,
                            self.selected_tile_bytes(package, x, y, level),
                        )
            cut_placements = json.loads(
                (package / "placements/global/lp0.json").read_text(encoding="utf-8")
            )
            for family in ("boundaries", "groundItems", "npcs", "scenery"):
                self.assertEqual([], cut_placements[family])
            self.assertTrue(bundle.is_file())
            self.assertEqual(source_before, tree_bytes(project / "source"))
            self.assertEqual(target_before, tree_bytes(target))

            paste_preview = self.run_cli(
                "region-paste-preview",
                "--project",
                project,
                "--snapshot",
                copy_result["snapshotId"],
                "--level",
                "-1",
                "--x",
                "119",
                "--y",
                "648",
            )
            self.assertEqual(0, paste_preview.returncode, paste_preview.stderr)
            paste = json.loads(paste_preview.stdout)
            self.assertTrue(paste["compatibilityReport"]["compatible"])
            self.assertEqual([], paste["operationPlan"]["collisions"])
            paste_hash = paste["operationPlan"]["planFingerprintSha256"]
            paste_apply = self.run_cli(
                "region-paste-apply",
                "--project",
                project,
                "--snapshot",
                copy_result["snapshotId"],
                "--level",
                "-1",
                "--x",
                "119",
                "--y",
                "648",
                "--expected-plan",
                paste_hash,
                "--confirm",
                "PASTE " + paste_hash,
            )
            self.assertEqual(0, paste_apply.returncode, paste_apply.stderr)
            self.assertEqual(package_before, tree_bytes(package))
            restored = json.loads(
                (package / "placements/global/lp0.json").read_text(encoding="utf-8")
            )
            for family in ("boundaries", "groundItems", "npcs", "scenery"):
                self.assertEqual(expected_placements[family], restored[family])
            self.assertEqual(source_before, tree_bytes(project / "source"))
            self.assertEqual(target_before, tree_bytes(target))

    def test_region_collision_preview_overwrite_and_unavailable_terrain_fail_closed(self):
        with tempfile.TemporaryDirectory(prefix="adaptive-region-collision-") as temp:
            base = Path(temp)
            installation = base / "World Builder 2"
            installation.mkdir()
            runtime = self.make_runtime(installation)
            target = base / "ordinary-parent"
            target.mkdir()
            report = base / "report.json"
            self.discover(target, report)
            created, summary = self.create_project(
                installation, runtime, target, report, "Collision preview", 43861
            )
            self.assertEqual(0, created.returncode, created.stderr)
            project = Path(summary["projectRoot"])
            package = project / "working/layered-world/package"
            selection = base / "selection.json"
            self.write_region_selection(
                selection,
                [(119, 647), (121, 647), (121, 649), (119, 649)],
                [0],
            )
            copied = self.run_cli(
                "region-copy", "--project", project, "--selection", selection,
                "--name", "Terrain only"
            )
            self.assertEqual(0, copied.returncode, copied.stderr)
            snapshot_id = json.loads(copied.stdout)["snapshotId"]
            before = tree_bytes(package)

            preview = self.run_cli(
                "region-paste-preview", "--project", project, "--snapshot",
                snapshot_id, "--level", "0", "--x", "120", "--y", "647"
            )
            self.assertEqual(0, preview.returncode, preview.stderr)
            plan = json.loads(preview.stdout)["operationPlan"]
            self.assertFalse(plan["blocked"])
            self.assertTrue(plan["overwriteRequired"])
            self.assertTrue(
                any(value["kind"] == "non-void-terrain" for value in plan["collisions"])
            )
            plan_hash = plan["planFingerprintSha256"]
            refused = self.run_cli(
                "region-paste-apply", "--project", project, "--snapshot",
                snapshot_id, "--level", "0", "--x", "120", "--y", "647",
                "--expected-plan", plan_hash, "--confirm", "PASTE " + plan_hash
            )
            self.assertEqual(3, refused.returncode, refused.stderr)
            self.assertEqual(before, tree_bytes(package))
            applied = self.run_cli(
                "region-paste-apply", "--project", project, "--snapshot",
                snapshot_id, "--level", "0", "--x", "120", "--y", "647",
                "--expected-plan", plan_hash, "--confirm", "OVERWRITE " + plan_hash
            )
            self.assertEqual(0, applied.returncode, applied.stderr)

            after = tree_bytes(package)
            unavailable = self.run_cli(
                "region-paste-preview", "--project", project, "--snapshot",
                snapshot_id, "--level", "0", "--x", "2000", "--y", "2000"
            )
            self.assertEqual(0, unavailable.returncode, unavailable.stderr)
            unavailable_plan = json.loads(unavailable.stdout)["operationPlan"]
            self.assertTrue(unavailable_plan["blocked"])
            self.assertEqual([], unavailable_plan["files"])
            self.assertTrue(
                any(value["kind"] == "unavailable-terrain" for value in unavailable_plan["collisions"])
            )
            self.assertEqual(after, tree_bytes(package))
            extreme = self.run_cli(
                "region-paste-preview", "--project", project, "--snapshot",
                snapshot_id, "--level", "2147483647", "--x", "2147483647",
                "--y", "2147483647"
            )
            self.assertEqual(3, extreme.returncode, extreme.stderr)
            self.assertIn("signed 32-bit range", extreme.stderr)
            self.assertEqual(after, tree_bytes(package))

    def test_region_copy_pastes_all_placement_families_with_local_id_mapping(self):
        with tempfile.TemporaryDirectory(prefix="adaptive-region-copy-paste-") as temp:
            base = Path(temp)
            installation = base / "World Builder 2"
            installation.mkdir()
            runtime = self.make_runtime(installation)
            target = base / "ordinary-parent"
            target.mkdir()
            report = base / "report.json"
            self.discover(target, report)
            created, summary = self.create_project(
                installation, runtime, target, report, "Copy and paste", 43865
            )
            self.assertEqual(0, created.returncode, created.stderr)
            project = Path(summary["projectRoot"])
            source = self.place_representative_definitions(project)
            saved = self.run_cli("save-project", "--project", project)
            self.assertEqual(0, saved.returncode, saved.stderr)
            selection = base / "selection.json"
            self.write_region_selection(
                selection, [(119, 648), (121, 648), (121, 649), (119, 649)], [0]
            )
            copied = self.run_cli(
                "region-copy", "--project", project, "--selection", selection,
                "--name", "Copy with provenance"
            )
            self.assertEqual(0, copied.returncode, copied.stderr)
            snapshot_id = json.loads(copied.stdout)["snapshotId"]
            package = project / "working/layered-world/package"
            source_before = json.loads(
                (package / "placements/global/lp0.json").read_text(encoding="utf-8")
            )
            preview = self.run_cli(
                "region-paste-preview", "--project", project, "--snapshot", snapshot_id,
                "--level", "0", "--x", "125", "--y", "648"
            )
            self.assertEqual(0, preview.returncode, preview.stderr)
            plan = json.loads(preview.stdout)["operationPlan"]
            self.assertFalse(plan["blocked"])
            mappings = plan["placementIdMappings"]
            self.assertEqual(
                {"boundary", "ground-item", "npc", "scenery"},
                {value["family"] for value in mappings},
            )
            self.assertEqual(4, len(mappings))
            self.assertTrue(
                all(value["sourcePlacementId"] != value["destinationPlacementId"]
                    for value in mappings)
            )
            plan_hash = plan["planFingerprintSha256"]
            applied = self.run_cli(
                "region-paste-apply", "--project", project, "--snapshot", snapshot_id,
                "--level", "0", "--x", "125", "--y", "648",
                "--expected-plan", plan_hash, "--confirm", "PASTE " + plan_hash
            )
            self.assertEqual(0, applied.returncode, applied.stderr)
            result = json.loads(
                (package / "placements/global/lp0.json").read_text(encoding="utf-8")
            )
            mapping_by_source = {
                value["sourcePlacementId"]: value["destinationPlacementId"]
                for value in mappings
            }
            for family in ("boundaries", "groundItems", "npcs", "scenery"):
                self.assertEqual(2, len(result[family]))
                self.assertIn(source[family][0], result[family])
                self.assertEqual(source_before[family][0], source[family][0])
                self.assertIn(
                    mapping_by_source[source[family][0]["placementId"]],
                    {record["placementId"] for record in result[family]},
                )

    def test_region_publication_recovers_every_durable_exchange_milestone(self):
        milestones = {
            "staged-package-durable": "before",
            "rollback-package-durable": "before",
            "package-published": "before",
            "failed-quarantined": "before",
            "project-manifest-saved": "after",
            "before-cleanup": "after",
            "rollback-quarantined": "after",
            "cleanup-tree-deleted": "after",
            "before-journal-delete": "after",
            "journal-write-failed": "after",
            "journal-delete-failed": "after",
            "journal-deleted": "after",
            "cleanup-complete": "after",
        }
        for offset, (milestone, expected_state) in enumerate(milestones.items()):
            with self.subTest(milestone=milestone), tempfile.TemporaryDirectory(
                prefix="adaptive-region-recovery-"
            ) as temp:
                base = Path(temp)
                installation = base / "World Builder 2"
                installation.mkdir()
                runtime = self.make_runtime(installation)
                target = base / "ordinary-parent"
                target.mkdir()
                report = base / "report.json"
                self.discover(target, report)
                created, summary = self.create_project(
                    installation, runtime, target, report,
                    "Recovery " + milestone, 43900 + offset
                )
                self.assertEqual(0, created.returncode, created.stderr)
                project = Path(summary["projectRoot"])
                self.place_representative_definitions(project)
                saved = self.run_cli("save-project", "--project", project)
                self.assertEqual(0, saved.returncode, saved.stderr)
                package = project / "working/layered-world/package"
                package_before = tree_bytes(package)
                manifest_before = (project / "project.json").read_bytes()
                selection = base / "selection.json"
                self.write_region_selection(
                    selection,
                    [(119, 648), (121, 648), (121, 649), (119, 649)], [0]
                )
                copied = self.run_cli(
                    "region-copy", "--project", project, "--selection", selection,
                    "--name", "Recovery source"
                )
                self.assertEqual(0, copied.returncode, copied.stderr)
                snapshot_id = json.loads(copied.stdout)["snapshotId"]
                preview = self.run_cli(
                    "region-cut-preview", "--project", project,
                    "--selection", selection, "--name", "Recovery source"
                )
                self.assertEqual(0, preview.returncode, preview.stderr)
                plan_hash = json.loads(preview.stdout)["operationPlan"][
                    "planFingerprintSha256"
                ]
                interrupted = self.run_region_failure(
                    project, snapshot_id, plan_hash, "CUT " + plan_hash, milestone
                )
                self.assertEqual(
                    3 if milestone == "failed-quarantined" else 4,
                    interrupted.returncode,
                    interrupted.stderr,
                )
                if milestone in ("rollback-quarantined", "failed-quarantined"):
                    cleanup = next(
                        (project / "working/layered-world").glob(".region-cleanup-*")
                    )
                    victim = next(path for path in cleanup.rglob("*") if path.is_file())
                    victim.unlink()
                if milestone == "staged-package-durable":
                    parent = project / "working/layered-world"
                    journal_path = parent / ".region-transaction-v1.json"
                    journal = json.loads(journal_path.read_text(encoding="utf-8"))
                    stage = parent / journal["stageName"]
                    cleanup = parent / (
                        ".region-cleanup-stage-" + journal["afterTreeSha256"]
                    )
                    journal["phase"] = "cleaning-up"
                    journal["cleanupSourceName"] = stage.name
                    journal["cleanupName"] = cleanup.name
                    journal_path.write_text(
                        json.dumps(journal, sort_keys=True) + "\n", encoding="utf-8"
                    )
                    stage.rename(cleanup)
                    victim = next(path for path in cleanup.rglob("*") if path.is_file())
                    victim.unlink()

                recovered = self.run_cli(
                    "open-project", "--installation-root", installation
                )
                self.assertEqual(0, recovered.returncode, recovered.stderr)
                self.assertFalse(
                    (project / "working/layered-world/.region-transaction-v1.json").exists()
                )
                self.assertFalse(
                    (project / "working/layered-world/.region-original-v1").exists()
                )
                self.assertFalse(
                    list((project / "working/layered-world").glob(".region-stage-*"))
                )
                self.assertFalse(
                    list((project / "working/layered-world").glob(".region-failed-*"))
                )
                self.assertFalse(
                    list((project / "working/layered-world").glob(".region-cleanup-*"))
                )
                if expected_state == "before":
                    self.assertEqual(package_before, tree_bytes(package))
                    self.assertEqual(manifest_before, (project / "project.json").read_bytes())
                else:
                    self.assertNotEqual(package_before, tree_bytes(package))
                    self.assertNotEqual(manifest_before, (project / "project.json").read_bytes())
                    placement = json.loads(
                        (package / "placements/global/lp0.json").read_text(encoding="utf-8")
                    )
                    for family in ("boundaries", "groundItems", "npcs", "scenery"):
                        self.assertEqual([], placement[family])

    def test_ordinary_reopen_recovers_or_refuses_orphan_region_journal_writes(self):
        for offset, scenario in enumerate(
            ("alongside", "only", "ambiguous", "bounded-scan", "orphan-rollback")
        ):
            with self.subTest(scenario=scenario), tempfile.TemporaryDirectory(
                prefix="adaptive-region-journal-"
            ) as temp:
                base = Path(temp)
                installation = base / "World Builder 2"
                installation.mkdir()
                runtime = self.make_runtime(installation)
                target = base / "ordinary-parent"
                target.mkdir()
                report = base / "report.json"
                self.discover(target, report)
                created, summary = self.create_project(
                    installation, runtime, target, report,
                    "Journal " + scenario, 43920 + offset
                )
                self.assertEqual(0, created.returncode, created.stderr)
                project = Path(summary["projectRoot"])
                self.place_representative_definitions(project)
                saved = self.run_cli("save-project", "--project", project)
                self.assertEqual(0, saved.returncode, saved.stderr)
                package_before = tree_bytes(project / "working/layered-world/package")
                selection = base / "selection.json"
                self.write_region_selection(
                    selection, [(119, 648), (121, 648), (121, 649), (119, 649)], [0]
                )
                copied = self.run_cli(
                    "region-copy", "--project", project, "--selection", selection,
                    "--name", "Journal source"
                )
                snapshot_id = json.loads(copied.stdout)["snapshotId"]
                preview = self.run_cli(
                    "region-cut-preview", "--project", project,
                    "--selection", selection, "--name", "Journal source"
                )
                plan_hash = json.loads(preview.stdout)["operationPlan"][
                    "planFingerprintSha256"
                ]
                interrupted = self.run_region_failure(
                    project, snapshot_id, plan_hash, "CUT " + plan_hash,
                    "staged-package-durable"
                )
                self.assertEqual(4, interrupted.returncode, interrupted.stderr)
                journal = project / "working/layered-world/.region-transaction-v1.json"
                journal_bytes = journal.read_bytes()
                if scenario == "ambiguous":
                    value = json.loads(journal_bytes)
                    value["afterTreeSha256"] = "f" * 64
                    candidate_bytes = (
                        json.dumps(value, sort_keys=True, indent=2, ensure_ascii=False) + "\n"
                    ).encode("utf-8")
                else:
                    candidate_bytes = journal_bytes
                candidate = journal.with_name(
                    ".region-transaction-v1.json.new-"
                    + hashlib.sha256(candidate_bytes).hexdigest()
                )
                candidate.write_bytes(candidate_bytes)
                if scenario == "only":
                    journal.unlink()
                if scenario == "bounded-scan":
                    for index in range(8193):
                        (journal.parent / f"unexpected-recovery-entry-{index:05d}").touch()
                if scenario == "orphan-rollback":
                    candidate.unlink()
                    journal.unlink()
                    stage = next(journal.parent.glob(".region-stage-*"))
                    shutil.rmtree(stage)
                    (journal.parent / "package").rename(
                        journal.parent / ".region-original-v1"
                    )
                reopened = self.run_cli(
                    "open-project", "--installation-root", installation
                )
                if scenario in ("ambiguous", "bounded-scan", "orphan-rollback"):
                    self.assertEqual(3, reopened.returncode, reopened.stderr)
                    self.assertIn(
                        "RECOVERY_REQUIRED" if scenario != "bounded-scan"
                        else "INVENTORY_LIMIT_EXCEEDED",
                        reopened.stderr,
                    )
                    if scenario == "orphan-rollback":
                        self.assertTrue(
                            (journal.parent / ".region-original-v1").exists()
                        )
                    else:
                        self.assertTrue(journal.exists())
                        self.assertTrue(candidate.exists())
                else:
                    self.assertEqual(0, reopened.returncode, reopened.stderr)
                    self.assertFalse(journal.exists())
                    self.assertFalse(candidate.exists())
                    self.assertEqual(
                        package_before,
                        tree_bytes(project / "working/layered-world/package"),
                    )

    def test_normal_save_and_launch_recover_pending_region_publication(self):
        for offset, entry_point in enumerate(("save", "launch")):
            with self.subTest(entry_point=entry_point), tempfile.TemporaryDirectory(
                prefix="adaptive-region-entry-recovery-"
            ) as temp:
                base = Path(temp)
                installation = base / "World Builder 2"
                installation.mkdir()
                runtime = self.make_runtime(installation)
                target = base / "ordinary-parent"
                target.mkdir()
                report = base / "report.json"
                self.discover(target, report)
                created, summary = self.create_project(
                    installation, runtime, target, report,
                    "Entry recovery " + entry_point, 43930 + offset
                )
                self.assertEqual(0, created.returncode, created.stderr)
                project = Path(summary["projectRoot"])
                self.place_representative_definitions(project)
                saved = self.run_cli("save-project", "--project", project)
                self.assertEqual(0, saved.returncode, saved.stderr)
                package_before = tree_bytes(project / "working/layered-world/package")
                selection = base / "selection.json"
                self.write_region_selection(
                    selection, [(119, 648), (121, 648), (121, 649), (119, 649)], [0]
                )
                copied = self.run_cli(
                    "region-copy", "--project", project, "--selection", selection,
                    "--name", "Entry source"
                )
                snapshot_id = json.loads(copied.stdout)["snapshotId"]
                preview = self.run_cli(
                    "region-cut-preview", "--project", project,
                    "--selection", selection, "--name", "Entry source"
                )
                plan_hash = json.loads(preview.stdout)["operationPlan"][
                    "planFingerprintSha256"
                ]
                interrupted = self.run_region_failure(
                    project, snapshot_id, plan_hash, "CUT " + plan_hash,
                    "staged-package-durable"
                )
                self.assertEqual(4, interrupted.returncode, interrupted.stderr)
                result = (
                    self.run_cli("save-project", "--project", project)
                    if entry_point == "save"
                    else self.run_supervision(project)
                )
                self.assertEqual(0, result.returncode, result.stderr)
                self.assertEqual(
                    package_before, tree_bytes(project / "working/layered-world/package")
                )
                self.assertFalse(
                    (project / "working/layered-world/.region-transaction-v1.json").exists()
                )

    def test_region_bundle_import_export_dependencies_and_traversal_are_safe(self):
        with tempfile.TemporaryDirectory(prefix="adaptive-region-bundle-") as temp:
            base = Path(temp)
            installation = base / "World Builder 2"
            installation.mkdir()
            runtime = self.make_runtime(installation)
            target = base / "ordinary-parent"
            target.mkdir()
            report = base / "report.json"
            self.discover(target, report)
            first_created, first_summary = self.create_project(
                installation, runtime, target, report, "Bundle source", 43862
            )
            self.assertEqual(0, first_created.returncode, first_created.stderr)
            first = Path(first_summary["projectRoot"])
            selection = base / "selection.json"
            self.write_region_selection(
                selection,
                [(119, 647), (121, 647), (121, 649), (119, 649)],
                [0],
            )
            copied = self.run_cli(
                "region-copy", "--project", first, "--selection", selection,
                "--name", "Shared terrain"
            )
            self.assertEqual(0, copied.returncode, copied.stderr)
            snapshot_id = json.loads(copied.stdout)["snapshotId"]
            first_export = base / "shared-a.wbr"
            second_export = base / "shared-b.wbr"
            for output in (first_export, second_export):
                exported = self.run_cli(
                    "region-export", "--project", first, "--snapshot", snapshot_id,
                    "--output", output
                )
                self.assertEqual(0, exported.returncode, exported.stderr)
            self.assertEqual(first_export.read_bytes(), second_export.read_bytes())
            redirected = base / "redirected-into-project"
            redirected.symlink_to(first / "exports", target_is_directory=True)
            unsafe_export = self.run_cli(
                "region-export", "--project", first, "--snapshot", snapshot_id,
                "--output", redirected / "escaped.wbr"
            )
            self.assertEqual(3, unsafe_export.returncode, unsafe_export.stderr)
            self.assertIn("UNSAFE_PATH", unsafe_export.stderr)
            self.assertFalse((first / "exports/escaped.wbr").exists())

            second_created, second_summary = self.create_project(
                installation, runtime, target, report, "Bundle target", 43863
            )
            self.assertEqual(0, second_created.returncode, second_created.stderr)
            second = Path(second_summary["projectRoot"])
            second_package_before = tree_bytes(second / "working/layered-world/package")
            imported = self.run_cli(
                "region-import", "--project", second, "--bundle", first_export
            )
            self.assertEqual(0, imported.returncode, imported.stderr)
            import_result = json.loads(imported.stdout)
            self.assertEqual(snapshot_id, import_result["snapshotId"])
            self.assertTrue(import_result["compatibilityReport"]["compatible"])
            self.assertFalse(import_result["worldModified"])
            self.assertEqual(
                second_package_before, tree_bytes(second / "working/layered-world/package")
            )
            canonical_library = second / import_result["libraryRelativePath"]
            canonical_bytes = canonical_library.read_bytes()
            with zipfile.ZipFile(first_export, "r") as archive:
                manifest_bytes = archive.read("manifest.json")
                snapshot_bytes = archive.read("snapshot.json")
            variants = []
            prefixed = base / "prefixed.wbr"
            prefixed.write_bytes(b"hidden-prefix" + first_export.read_bytes())
            variants.append(prefixed)
            trailing = base / "trailing.wbr"
            trailing.write_bytes(first_export.read_bytes() + b"hidden-trailer")
            variants.append(trailing)
            alternate = base / "alternate-zip.wbr"
            with zipfile.ZipFile(alternate, "w", compression=zipfile.ZIP_DEFLATED) as archive:
                archive.comment = b"noncanonical comment"
                snapshot_info = zipfile.ZipInfo("snapshot.json", (2025, 1, 2, 3, 4, 6))
                snapshot_info.compress_type = zipfile.ZIP_DEFLATED
                snapshot_info.extra = b"\x0a\x00\x01\x00x"
                archive.writestr(snapshot_info, snapshot_bytes)
                archive.writestr("manifest.json", manifest_bytes)
            variants.append(alternate)
            for variant in variants:
                with self.subTest(canonicalized=variant.name):
                    accepted = self.run_cli(
                        "region-import", "--project", second, "--bundle", variant
                    )
                    self.assertEqual(0, accepted.returncode, accepted.stderr)
                    self.assertFalse(json.loads(accepted.stdout)["libraryEntryCreated"])
                    self.assertEqual(canonical_bytes, canonical_library.read_bytes())

            catalog_mismatch = base / "catalog-mismatch.wbr"
            shutil.copy2(first_export, catalog_mismatch)

            def replace_catalog(snapshot):
                old_catalog = snapshot["catalog"]["catalogId"]
                replacement = "foreign-neutral-catalog-v1"
                replacement_hash = "f" * 64
                snapshot["catalog"] = {
                    "catalogId": replacement,
                    "sha256": replacement_hash,
                }
                for dependency in snapshot["dependencies"]:
                    dependency["catalogId"] = replacement
                    dependency["logicalId"] = dependency["logicalId"].replace(
                        f"catalog:{old_catalog}", f"catalog:{replacement}", 1
                    )
                    if dependency["kind"] == "definition-catalog":
                        dependency["contentSha256"] = replacement_hash
                snapshot["dependencies"].sort(
                    key=lambda value: (
                        value["kind"], value["family"], value["logicalId"]
                    )
                )

            self.rewrite_region_bundle(catalog_mismatch, replace_catalog)
            mismatch_import = self.run_cli(
                "region-import", "--project", second, "--bundle", catalog_mismatch
            )
            self.assertEqual(0, mismatch_import.returncode, mismatch_import.stderr)
            mismatch_result = json.loads(mismatch_import.stdout)
            self.assertFalse(mismatch_result["compatibilityReport"]["compatible"])
            self.assertTrue(
                any(
                    issue["code"] == "catalog-mismatch"
                    for issue in mismatch_result["compatibilityReport"]["issues"]
                )
            )
            self.assertEqual(
                second_package_before, tree_bytes(second / "working/layered-world/package")
            )

            unsupported = base / "unsupported-material.wbr"
            shutil.copy2(first_export, unsupported)

            def add_material(snapshot):
                snapshot["dependencies"].append(
                    {
                        "kind": "material",
                        "family": "floor",
                        "logicalId": "creator.example:marble-floor-v1",
                        "numericId": -1,
                        "catalogId": snapshot["catalog"]["catalogId"],
                        "contentSha256": "",
                        "resolution": "unsupported",
                        "bundled": False,
                    }
                )
                snapshot["dependencies"].sort(
                    key=lambda value: (
                        value["kind"], value["family"], value["logicalId"]
                    )
                )

            self.rewrite_region_bundle(unsupported, add_material)
            unsupported_import = self.run_cli(
                "region-import", "--project", second, "--bundle", unsupported
            )
            self.assertEqual(0, unsupported_import.returncode, unsupported_import.stderr)
            unsupported_result = json.loads(unsupported_import.stdout)
            self.assertFalse(unsupported_result["compatibilityReport"]["compatible"])
            self.assertTrue(
                any(
                    issue["code"] == "unsupported-dependency"
                    for issue in unsupported_result["compatibilityReport"]["issues"]
                )
            )
            self.assertTrue(
                (second / unsupported_result["libraryRelativePath"]).is_file()
            )
            blocked = self.run_cli(
                "region-paste-preview", "--project", second, "--snapshot",
                unsupported_result["snapshotId"], "--level", "0", "--x", "119",
                "--y", "647"
            )
            self.assertEqual(0, blocked.returncode, blocked.stderr)
            self.assertTrue(json.loads(blocked.stdout)["operationPlan"]["blocked"])
            self.assertEqual(
                second_package_before, tree_bytes(second / "working/layered-world/package")
            )

            traversal = base / "traversal.wbr"
            with zipfile.ZipFile(traversal, "w") as archive:
                archive.writestr("manifest.json", b"{}")
                archive.writestr("../snapshot.json", b"{}")
            library_before = tree_bytes(second / "snapshot-library")
            refused = self.run_cli(
                "region-import", "--project", second, "--bundle", traversal
            )
            self.assertEqual(3, refused.returncode, refused.stderr)
            self.assertIn("UNSAFE_PATH", refused.stderr)
            self.assertEqual(library_before, tree_bytes(second / "snapshot-library"))
            self.assertFalse((base / "snapshot.json").exists())

            with zipfile.ZipFile(first_export, "r") as original:
                original_manifest = original.read("manifest.json")
                original_snapshot = original.read("snapshot.json")
            malformed_bundles = {}
            extra_entry = base / "extra-executable.wbr"
            with zipfile.ZipFile(extra_entry, "w") as archive:
                archive.writestr("manifest.json", original_manifest)
                archive.writestr("snapshot.json", original_snapshot)
                archive.writestr("run.cmd", b"exit /b 0\n")
            malformed_bundles[extra_entry] = "UNSUPPORTED_FORMAT"
            hash_mismatch = base / "hash-mismatch.wbr"
            with zipfile.ZipFile(hash_mismatch, "w") as archive:
                archive.writestr("manifest.json", original_manifest)
                archive.writestr("snapshot.json", original_snapshot + b" ")
            malformed_bundles[hash_mismatch] = "CONTRACT"
            for malformed, expected_error in malformed_bundles.items():
                with self.subTest(bundle=malformed.name):
                    library_before = tree_bytes(second / "snapshot-library")
                    refused = self.run_cli(
                        "region-import", "--project", second, "--bundle", malformed
                    )
                    self.assertEqual(3, refused.returncode, refused.stderr)
                    self.assertIn(expected_error, refused.stderr)
                    self.assertEqual(
                        library_before, tree_bytes(second / "snapshot-library")
                    )

            oversized = second / "snapshot-library/v1" / (("e" * 64) + ".wbr")
            with oversized.open("wb") as output:
                output.seek(32 * 1024 * 1024)
                output.write(b"x")
            bounded = self.run_cli(
                "region-export", "--project", second, "--snapshot", snapshot_id,
                "--output", base / "must-not-export.wbr"
            )
            self.assertEqual(3, bounded.returncode, bounded.stderr)
            self.assertIn("CONTRACT_LIMIT_EXCEEDED", bounded.stderr)
            oversized.unlink()

            oversized_stage = second / "snapshot-library/v1" / (
                "." + ("a" * 64) + ".wbr.staging-" + ("b" * 64)
            )
            with oversized_stage.open("wb") as output:
                output.seek(32 * 1024 * 1024)
                output.write(b"x")
            bounded_stage = self.run_cli(
                "region-export", "--project", second, "--snapshot", snapshot_id,
                "--output", base / "must-not-recover-stage.wbr"
            )
            self.assertEqual(3, bounded_stage.returncode, bounded_stage.stderr)
            self.assertIn("RECOVERY_REQUIRED", bounded_stage.stderr)
            self.assertTrue(oversized_stage.exists())
            oversized_stage.unlink()

            junk_entries = []
            for index in range(1026):
                junk = second / "snapshot-library/v1" / f".unexpected-{index:04d}"
                junk.touch()
                junk_entries.append(junk)
            bounded_scan = self.run_cli(
                "region-export", "--project", second, "--snapshot", snapshot_id,
                "--output", base / "must-not-scan-unbounded-library.wbr"
            )
            self.assertEqual(3, bounded_scan.returncode, bounded_scan.stderr)
            self.assertIn("INVENTORY_LIMIT_EXCEEDED", bounded_scan.stderr)
            for junk in junk_entries:
                junk.unlink()

            library_directory = second / "snapshot-library/v1"
            capacity_fillers = []
            filler_index = 0
            entries_needed = 1024 - len(list(library_directory.glob("*.wbr")))
            while len(capacity_fillers) < entries_needed:
                identity = hashlib.sha256(
                    f"capacity-filler-{filler_index}".encode("ascii")
                ).hexdigest()
                filler_index += 1
                filler = library_directory / f"{identity}.wbr"
                if filler.exists():
                    continue
                filler.write_bytes(b"x")
                capacity_fillers.append(filler)
            capacity_bundle = base / "capacity-new.wbr"
            shutil.copy2(first_export, capacity_bundle)
            self.rewrite_region_bundle(
                capacity_bundle,
                lambda snapshot: snapshot.__setitem__(
                    "name", "Distinct snapshot at library capacity"
                ),
            )
            library_at_capacity = tree_bytes(library_directory)
            capacity_refused = self.run_cli(
                "region-import", "--project", second, "--bundle", capacity_bundle
            )
            self.assertEqual(3, capacity_refused.returncode, capacity_refused.stderr)
            self.assertIn("INVENTORY_LIMIT_EXCEEDED", capacity_refused.stderr)
            self.assertEqual(library_at_capacity, tree_bytes(library_directory))
            for filler in capacity_fillers:
                filler.unlink()

            canonical_bytes = canonical_library.read_bytes()
            canonical_library.write_bytes(canonical_bytes[:-1] + b"x")
            tampered = self.run_cli(
                "region-export", "--project", second, "--snapshot", snapshot_id,
                "--output", base / "tampered-export.wbr"
            )
            self.assertEqual(3, tampered.returncode, tampered.stderr)
            self.assertFalse((base / "tampered-export.wbr").exists())
            canonical_library.write_bytes(canonical_bytes)

    def test_region_preview_blocks_external_and_incoming_crossing_footprints(self):
        with tempfile.TemporaryDirectory(prefix="adaptive-region-footprints-") as temp:
            base = Path(temp)
            installation = base / "World Builder 2"
            installation.mkdir()
            runtime = self.make_runtime(installation)
            target = base / "ordinary-parent"
            target.mkdir()
            report = base / "report.json"
            self.discover(target, report)
            created, summary = self.create_project(
                installation, runtime, target, report, "Footprints", 43866
            )
            self.assertEqual(0, created.returncode, created.stderr)
            project = Path(summary["projectRoot"])
            package = project / "working/layered-world/package"
            self.place_representative_definitions(project)
            saved = self.run_cli("save-project", "--project", project)
            self.assertEqual(0, saved.returncode, saved.stderr)
            selection = base / "selection.json"
            self.write_region_selection(
                selection, [(119, 648), (121, 648), (121, 649), (119, 649)], [0]
            )
            copied = self.run_cli(
                "region-copy", "--project", project, "--selection", selection,
                "--name", "Crossing footprints"
            )
            self.assertEqual(0, copied.returncode, copied.stderr)
            snapshot_id = json.loads(copied.stdout)["snapshotId"]

            placement_path = package / "placements/global/lp0.json"
            placement = json.loads(placement_path.read_text(encoding="utf-8"))
            placement["boundaries"].append(
                {"boundaryId": 1, "direction": 1, "placementId": "outside-boundary",
                 "position": {"x": 118, "y": 648}}
            )
            placement["npcs"].append(
                {"npcId": 2, "placementId": "outside-npc",
                 "roamBounds": {"minimum": {"x": 118, "y": 648},
                                "maximum": {"x": 119, "y": 649}},
                 "start": {"x": 118, "y": 648}}
            )
            placement["boundaries"].sort(
                key=lambda value: (value["position"]["x"], value["position"]["y"],
                                   value["direction"], value["placementId"])
            )
            placement["npcs"].sort(
                key=lambda value: (value["start"]["x"], value["start"]["y"],
                                   value["placementId"])
            )
            write_json(placement_path, placement)
            manifest_path = package / "manifest.json"
            manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
            next(value for value in manifest["placementSets"] if value["level"] == 0)[
                "sha256"
            ] = sha256(placement_path)
            write_json(manifest_path, manifest)
            saved = self.run_cli("save-project", "--project", project)
            self.assertEqual(0, saved.returncode, saved.stderr)

            external = self.run_cli(
                "region-paste-preview", "--project", project, "--snapshot", snapshot_id,
                "--level", "0", "--x", "119", "--y", "648"
            )
            self.assertEqual(0, external.returncode, external.stderr)
            external_plan = json.loads(external.stdout)["operationPlan"]
            self.assertTrue(external_plan["blocked"])
            kinds = {value["kind"] for value in external_plan["collisions"]}
            self.assertIn("represented-boundary-crossing", kinds)
            self.assertIn("represented-npc-crossing", kinds)

            incoming = self.run_cli(
                "region-paste-preview", "--project", project, "--snapshot", snapshot_id,
                "--level", "0", "--x", "119", "--y", "624"
            )
            self.assertEqual(0, incoming.returncode, incoming.stderr)
            incoming_plan = json.loads(incoming.stdout)["operationPlan"]
            self.assertTrue(incoming_plan["blocked"])
            self.assertTrue(any(
                value["kind"] == "incoming-footprint-unavailable"
                for value in incoming_plan["collisions"]
            ))

    def test_region_footprint_work_is_bounded_for_snapshot_and_destination(self):
        with tempfile.TemporaryDirectory(prefix="adaptive-region-footprint-limit-") as temp:
            base = Path(temp)
            installation = base / "World Builder 2"
            installation.mkdir()
            runtime = self.make_runtime(installation)
            target = base / "ordinary-parent"
            target.mkdir()
            report = base / "report.json"
            self.discover(target, report)
            created, summary = self.create_project(
                installation, runtime, target, report, "Footprint limits", 43867
            )
            self.assertEqual(0, created.returncode, created.stderr)
            project = Path(summary["projectRoot"])
            self.place_representative_definitions(project)
            saved = self.run_cli("save-project", "--project", project)
            self.assertEqual(0, saved.returncode, saved.stderr)
            selection = base / "selection.json"
            self.write_region_selection(
                selection, [(119, 648), (121, 648), (121, 649), (119, 649)], [0]
            )
            copied = self.run_cli(
                "region-copy", "--project", project, "--selection", selection,
                "--name", "Bounded footprints"
            )
            self.assertEqual(0, copied.returncode, copied.stderr)
            original_id = json.loads(copied.stdout)["snapshotId"]
            exported_path = base / "large-footprints.wbr"
            exported = self.run_cli(
                "region-export", "--project", project, "--snapshot", original_id,
                "--output", exported_path
            )
            self.assertEqual(0, exported.returncode, exported.stderr)

            def expand_snapshot(snapshot):
                prototype = snapshot["placements"]["npcs"][0]
                snapshot["placements"]["npcs"] = []
                snapshot["footprintBoundaryReports"] = [
                    value for value in snapshot["footprintBoundaryReports"]
                    if value["family"] != "npc"
                ]
                for index in range(61):
                    npc = json.loads(json.dumps(prototype))
                    npc["placementId"] = f"large-npc-{index:03d}"
                    npc["roamBounds"] = {
                        "minimum": {"xOffset": -64, "yOffset": -64},
                        "maximum": {"xOffset": 64, "yOffset": 64},
                    }
                    snapshot["placements"]["npcs"].append(npc)
                    snapshot["footprintBoundaryReports"].append(
                        {"family": "npc", "placementId": npc["placementId"],
                         "ownership": "anchor-point", "crossesBoundary": True,
                         "detail": "complete roam rectangle; footprint crosses polygon"}
                    )
                snapshot["placements"]["npcs"].sort(
                    key=lambda value: json.dumps(value, sort_keys=True, separators=(",", ":"))
                )
                snapshot["footprintBoundaryReports"].sort(
                    key=lambda value: (value["family"], value["placementId"])
                )

            self.rewrite_region_bundle(exported_path, expand_snapshot)
            imported = self.run_cli(
                "region-import", "--project", project, "--bundle", exported_path
            )
            self.assertEqual(0, imported.returncode, imported.stderr)
            large_id = json.loads(imported.stdout)["snapshotId"]
            limited_snapshot = self.run_cli(
                "region-paste-preview", "--project", project, "--snapshot", large_id,
                "--level", "0", "--x", "125", "--y", "648"
            )
            self.assertEqual(3, limited_snapshot.returncode, limited_snapshot.stderr)
            self.assertIn("INVENTORY_LIMIT_EXCEEDED", limited_snapshot.stderr)

            package = project / "working/layered-world/package"
            placement_path = package / "placements/global/lp0.json"
            placement = json.loads(placement_path.read_text(encoding="utf-8"))
            prototype = placement["npcs"][0]
            placement["npcs"] = []
            for index in range(435):
                npc = json.loads(json.dumps(prototype))
                npc["placementId"] = f"dense-npc-{index:03d}"
                npc["start"] = {"x": 96 + index // 48, "y": 624 + index % 48}
                npc["roamBounds"] = {
                    "minimum": {"x": 96, "y": 624},
                    "maximum": {"x": 143, "y": 671},
                }
                placement["npcs"].append(npc)
            placement["npcs"].sort(
                key=lambda value: (value["start"]["x"], value["start"]["y"],
                                   value["placementId"])
            )
            write_json(placement_path, placement)
            manifest_path = package / "manifest.json"
            manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
            next(value for value in manifest["placementSets"] if value["level"] == 0)[
                "sha256"
            ] = sha256(placement_path)
            write_json(manifest_path, manifest)
            saved = self.run_cli("save-project", "--project", project)
            self.assertEqual(0, saved.returncode, saved.stderr)
            limited_destination = self.run_cli(
                "region-paste-preview", "--project", project, "--snapshot", original_id,
                "--level", "0", "--x", "125", "--y", "648"
            )
            self.assertEqual(3, limited_destination.returncode, limited_destination.stderr)
            self.assertIn("INVENTORY_LIMIT_EXCEEDED", limited_destination.stderr)

    def test_region_selection_geometry_and_contracts_fail_closed(self):
        with tempfile.TemporaryDirectory(prefix="adaptive-region-contract-") as temp:
            base = Path(temp)
            installation = base / "World Builder 2"
            installation.mkdir()
            runtime = self.make_runtime(installation)
            target = base / "ordinary-parent"
            target.mkdir()
            report = base / "report.json"
            self.discover(target, report)
            created, summary = self.create_project(
                installation, runtime, target, report, "Geometry", 43864
            )
            self.assertEqual(0, created.returncode, created.stderr)
            project = Path(summary["projectRoot"])
            package_before = tree_bytes(project / "working/layered-world/package")
            source_before = tree_bytes(project / "source")

            cases = {
                "self-intersecting": [(119, 647), (121, 649), (119, 649), (121, 647)],
                "degenerate": [(119, 647), (120, 648), (121, 649)],
                "duplicate": [(119, 647), (121, 647), (119, 647)],
            }
            for label, markers in cases.items():
                with self.subTest(label=label):
                    selection = base / f"{label}.json"
                    self.write_region_selection(selection, markers, [0])
                    refused = self.run_cli(
                        "region-copy", "--project", project, "--selection",
                        selection, "--name", label
                    )
                    self.assertEqual(3, refused.returncode, refused.stderr)
                    self.assertIn("CONTRACT_VALUE_INVALID", refused.stderr)
            stale = base / "stale.json"
            value = self.write_region_selection(
                stale, [(119, 647), (121, 647), (121, 649), (119, 649)], [0]
            )
            value["markers"][0]["x"] = 118
            write_json(stale, value)
            refused = self.run_cli(
                "region-copy", "--project", project, "--selection", stale,
                "--name", "stale"
            )
            self.assertEqual(3, refused.returncode, refused.stderr)
            self.assertIn("fingerprint", refused.stderr.lower())
            self.assertEqual(package_before, tree_bytes(project / "working/layered-world/package"))
            self.assertEqual(source_before, tree_bytes(project / "source"))

    def test_standalone_catalog_rejects_malformed_definition_shapes(self):
        mutations = {
            "wrong-xml-root": lambda runtime: (
                runtime / "server/conf/server/defs/DoorDef.xml"
            ).write_text(
                "<NotDoorDef-array><DoorDef/></NotDoorDef-array>\n",
                encoding="utf-8",
            ),
            "wrong-json-root": lambda runtime: write_json(
                runtime / "server/conf/server/defs/NpcDefsCustom.json",
                {"notNpcs": [{"id": 12}]},
            ),
            "missing-explicit-id": lambda runtime: write_json(
                runtime / "server/conf/server/defs/ItemDefsCustom.json",
                {"items": [{"name": "missing-id"}]},
            ),
        }
        with tempfile.TemporaryDirectory(
            prefix="adaptive-placement-catalog-invalid-"
        ) as temp:
            base = Path(temp)
            target = base / "ordinary-parent"
            target.mkdir()
            target_before = tree_bytes(target)
            report = base / "report.json"
            self.discover(target, report)
            for index, (label, mutate) in enumerate(mutations.items()):
                with self.subTest(label=label):
                    installation = base / label / "World Builder 2"
                    installation.mkdir(parents=True)
                    runtime = self.make_runtime(installation)
                    mutate(runtime)
                    refused, _ = self.create_project(
                        installation,
                        runtime,
                        target,
                        report,
                        "Invalid catalog fixture",
                        43850 + index,
                    )
                    self.assertEqual(3, refused.returncode, refused.stderr)
                    self.assertIn("DEFINITION_MISMATCH", refused.stderr)
                    self.assertFalse(
                        (installation / "project-registry.json").exists()
                    )
                    self.assertEqual(target_before, tree_bytes(target))

    def test_existing_legacy_standalone_catalog_reopens_without_silent_migration(self):
        with tempfile.TemporaryDirectory(prefix="adaptive-legacy-catalog-") as temp:
            base = Path(temp)
            installation = base / "World Builder 2"
            installation.mkdir()
            runtime = self.make_runtime(installation)
            target = base / "ordinary-parent"
            target.mkdir()
            report = base / "report.json"
            self.discover(target, report)
            created, summary = self.create_project(
                installation, runtime, target, report, "Legacy catalog", 43843
            )
            self.assertEqual(0, created.returncode, created.stderr)
            project = Path(summary["projectRoot"])
            self.rewrite_as_legacy_standalone(installation, project)
            source_before = tree_bytes(project / "source")
            target_before = tree_bytes(target)

            reopened = self.run_cli(
                "open-project",
                "--installation-root",
                installation,
                "--target-root",
                target,
            )
            self.assertEqual(0, reopened.returncode, reopened.stderr)
            self.assertEqual(source_before, tree_bytes(project / "source"))
            legacy = json.loads(
                (
                    project / "source/runtime/default-definition-catalog.json"
                ).read_text(encoding="utf-8")
            )
            self.assertEqual("world-builder-empty-default-v1", legacy["catalogId"])
            self.assertEqual([], legacy["scenery"])

            self.place_representative_definitions(project)
            refused = self.run_cli("save-project", "--project", project)
            self.assertEqual(3, refused.returncode, refused.stderr)
            self.assertIn("DEFINITION_MISMATCH", refused.stderr)
            self.assertEqual(source_before, tree_bytes(project / "source"))
            self.assertEqual(target_before, tree_bytes(target))

    def test_native_runtime_bundles_preserve_bounded_empty_fallbacks(self):
        with tempfile.TemporaryDirectory(prefix="adaptive-native-runtime-assets-") as temp:
            base = Path(temp)
            installation = base / "World Builder 2"
            installation.mkdir()
            runtime = self.make_runtime(installation)
            target = base / "ordinary-parent"
            target.mkdir()
            report = base / "report.json"
            self.discover(target, report)
            target_before = tree_bytes(target)

            created, summary = self.create_project(
                installation,
                runtime,
                target,
                report,
                "Native runtime assets",
                43813,
            )
            self.assertEqual(0, created.returncode, created.stderr)
            project = Path(summary["projectRoot"])
            inventory = (
                project / "working/runtime/runtime-assets.sha256"
            ).read_text(encoding="utf-8")
            empty_digest = hashlib.sha256(b"").hexdigest()
            for bundle in EMPTY_LANGUAGE_BUNDLES:
                relative = "server/conf/server/languages/" + bundle
                copied = project / "working/runtime" / relative
                self.assertTrue(copied.is_file(), copied)
                self.assertEqual(b"", copied.read_bytes())
                self.assertIn(f"{empty_digest}\t0\t{relative}\n", inventory)
            for patch in REQUIRED_DATABASE_PATCHES:
                self.assertTrue(
                    (
                        project
                        / "working/runtime/server/database/sqlite/patches"
                        / patch
                    ).is_file()
                )
            definition_destinations = {
                destination
                for _, destination, role in runtime_allowlist_records()
                if role == "default-definition-catalog"
            }
            self.assertTrue(definition_destinations)
            for relative in definition_destinations:
                self.assertEqual(
                    (runtime / relative).read_bytes(),
                    (project / "working/runtime" / relative).read_bytes(),
                    relative,
                )
            self.assertFalse(
                (project / "working/runtime/server/conf/server/defs/locs").exists()
            )

            validated = self.run_cli(
                "open-project",
                "--installation-root",
                installation,
                "--validate-only",
            )
            self.assertEqual(0, validated.returncode, validated.stderr)

            changed = (
                project
                / "working/runtime/server/conf/server/languages/"
                "CustomMessages_en_UK_female.properties"
            )
            changed.write_bytes(b"changed\n")
            refused = self.run_cli(
                "open-project",
                "--installation-root",
                installation,
                "--validate-only",
            )
            self.assertEqual(3, refused.returncode, refused.stderr)
            self.assertIn("SOURCE_CORRUPT", refused.stderr)
            changed.write_bytes(b"")

            missing = (
                project
                / "working/runtime/server/database/sqlite/patches/"
                "2026_08_03_add_blessing_skill.sql"
            )
            missing.unlink()
            refused = self.run_cli(
                "open-project",
                "--installation-root",
                installation,
                "--validate-only",
            )
            self.assertEqual(3, refused.returncode, refused.stderr)
            self.assertIn("UNSAFE_PATH", refused.stderr)
            self.assertIn("2026_08_03_add_blessing_skill.sql", refused.stderr)
            self.assertEqual(target_before, tree_bytes(target))

            incomplete_installation = base / "Incomplete World Builder 2"
            incomplete_installation.mkdir()
            incomplete_runtime = self.make_runtime(incomplete_installation)
            (
                incomplete_runtime
                / "server/conf/server/languages/CustomMessages_en_UK.properties"
            ).unlink()
            refused, _ = self.create_project(
                incomplete_installation,
                incomplete_runtime,
                target,
                report,
                "Incomplete native runtime",
                43814,
            )
            self.assertEqual(3, refused.returncode, refused.stderr)
            self.assertIn("LOADER_INCOMPATIBLE", refused.stderr)
            self.assertIn("CustomMessages_en_UK.properties", refused.stderr)
            self.assertFalse(
                (incomplete_installation / "project-registry.json").exists()
            )
            self.assertEqual(target_before, tree_bytes(target))

            keyed_installation = base / "Keyed World Builder 2"
            keyed_installation.mkdir()
            keyed_runtime = self.make_runtime(keyed_installation)
            (keyed_runtime / "server/client.pem").write_text(
                "shared key material must not be imported\n", encoding="utf-8"
            )
            refused, _ = self.create_project(
                keyed_installation,
                keyed_runtime,
                target,
                report,
                "Shared-key runtime",
                43815,
            )
            self.assertEqual(3, refused.returncode, refused.stderr)
            self.assertIn("LOADER_INCOMPATIBLE", refused.stderr)
            self.assertIn("project-only generated", refused.stderr)
            self.assertFalse((keyed_installation / "project-registry.json").exists())
            self.assertEqual(target_before, tree_bytes(target))

            located_installation = base / "Located World Builder 2"
            located_installation.mkdir()
            located_runtime = self.make_runtime(located_installation)
            located_definition = (
                located_runtime
                / "server/conf/server/defs/locs/private-target-locations.xml"
            )
            located_definition.parent.mkdir(parents=True)
            located_definition.write_text("must not enter a project\n", encoding="utf-8")
            refused, _ = self.create_project(
                located_installation,
                located_runtime,
                target,
                report,
                "Located definition runtime",
                43817,
            )
            self.assertEqual(3, refused.returncode, refused.stderr)
            self.assertIn("LOADER_INCOMPATIBLE", refused.stderr)
            self.assertIn("exact allowlist", refused.stderr)
            self.assertFalse((located_installation / "project-registry.json").exists())
            self.assertEqual(target_before, tree_bytes(target))

    def test_project_local_pem_links_refuse_reopen(self):
        with tempfile.TemporaryDirectory(prefix="adaptive-project-pem-safety-") as temp:
            base = Path(temp)
            installation = base / "World Builder 2"
            installation.mkdir()
            runtime = self.make_runtime(installation)
            target = base / "ordinary-parent"
            target.mkdir()
            report = base / "report.json"
            self.discover(target, report)
            target_before = tree_bytes(target)
            created, summary = self.create_project(
                installation, runtime, target, report, "PEM safety", 43816
            )
            self.assertEqual(0, created.returncode, created.stderr)
            project = Path(summary["projectRoot"])
            server = project / "working/runtime/server"
            for name in ("client.pem", "server.pem"):
                (server / name).write_text("project-local key\n", encoding="utf-8")

            accepted = self.run_cli(
                "open-project",
                "--installation-root",
                installation,
                "--validate-only",
            )
            self.assertEqual(0, accepted.returncode, accepted.stderr)

            external = base / "external-key.pem"
            external.write_text("external key must remain unchanged\n", encoding="utf-8")
            before = external.read_bytes()
            client_key = server / "client.pem"
            client_key.unlink()
            client_key.symlink_to(external)
            refused = self.run_cli(
                "open-project",
                "--installation-root",
                installation,
                "--validate-only",
            )
            self.assertEqual(3, refused.returncode, refused.stderr)
            self.assertIn("SOURCE_CORRUPT", refused.stderr)
            self.assertEqual(before, external.read_bytes())

            client_key.unlink()
            os.link(external, client_key)
            refused = self.run_cli(
                "open-project",
                "--installation-root",
                installation,
                "--validate-only",
            )
            self.assertEqual(3, refused.returncode, refused.stderr)
            self.assertIn("SOURCE_CORRUPT", refused.stderr)
            self.assertEqual(before, external.read_bytes())
            self.assertEqual(target_before, tree_bytes(target))

    def test_wide_promotion_recovers_every_process_crash_swap_milestone(self):
        milestones = (
            "wide-promotion-staged",
            "wide-promotion-v1-aside",
            "wide-promotion-v2-installed",
            "wide-promotion-before-cleanup",
        )
        for index, milestone in enumerate(milestones):
            with self.subTest(milestone=milestone), tempfile.TemporaryDirectory(
                prefix="adaptive-wide-promotion-crash-"
            ) as temp:
                base = Path(temp)
                target = self.fixtures.descriptor_fixture(
                    str(base), world_space="global"
                )
                installation = target / "World Builder 2"
                installation.mkdir()
                runtime = self.make_runtime(installation)
                report = base / "layered-report.json"
                self.discover(target, report)
                created, summary = self.create_project(
                    installation, runtime, target, report,
                    "Promotion recovery", 44020 + index
                )
                self.assertEqual(0, created.returncode, created.stderr)
                project = Path(summary["projectRoot"])
                target_before = tree_bytes(target, installation)
                source_before = tree_bytes(project / "source")
                legacy_tree, expected = self.install_legacy_working_package(
                    installation, project
                )
                self.assertTrue(legacy_tree)

                crashed = self.run_promotion_crash(project, milestone)
                self.assertEqual(71, crashed.returncode, crashed.stderr)
                self.assertEqual(source_before, tree_bytes(project / "source"))
                self.assertEqual(target_before, tree_bytes(target, installation))

                opened = self.run_cli(
                    "open-project", "--installation-root", installation,
                    "--validate-only"
                )
                self.assertEqual(0, opened.returncode, opened.stderr)
                saved = self.run_cli("save-project", "--project", project)
                self.assertEqual(0, saved.returncode, saved.stderr)
                reopened = self.run_cli(
                    "open-project", "--installation-root", installation,
                    "--validate-only"
                )
                self.assertEqual(0, reopened.returncode, reopened.stderr)

                working = project / "working/layered-world/package"
                manifest = json.loads(
                    (working / "manifest.json").read_text(encoding="utf-8")
                )
                self.assertTrue(all(
                    item["encoding"] == "raw-layered-sector-v2-u16"
                    for item in manifest["terrainSectors"]
                ))
                for item in manifest["terrainSectors"]:
                    legacy = expected[item["path"]]
                    promoted = b"".join(
                        b"\0" + legacy[offset : offset + 10]
                        for offset in range(0, len(legacy), 10)
                    )
                    self.assertEqual(promoted, (working / item["path"]).read_bytes())
                for item in manifest["placementSets"]:
                    self.assertEqual(
                        expected[item["path"]],
                        (working / item["path"]).read_bytes(),
                    )
                self.assertFalse(any(
                    path.name.startswith(".wide-elevation-")
                    for path in installation.rglob("*")
                ))
                self.assertEqual(source_before, tree_bytes(project / "source"))
                self.assertEqual(target_before, tree_bytes(target, installation))

    def test_wide_promotion_recovery_refuses_unjournaled_and_malformed_state(self):
        for mode in ("unjournaled", "malformed"):
            with self.subTest(mode=mode), tempfile.TemporaryDirectory(
                prefix="adaptive-wide-promotion-refusal-"
            ) as temp:
                base = Path(temp)
                target = self.fixtures.descriptor_fixture(
                    str(base), world_space="global"
                )
                installation = target / "World Builder 2"
                installation.mkdir()
                runtime = self.make_runtime(installation)
                report = base / "layered-report.json"
                self.discover(target, report)
                created, summary = self.create_project(
                    installation, runtime, target, report,
                    "Promotion refusal", 44030
                )
                self.assertEqual(0, created.returncode, created.stderr)
                project = Path(summary["projectRoot"])
                source_before = tree_bytes(project / "source")
                target_before = tree_bytes(target, installation)
                parent = project / "working/layered-world"
                if mode == "unjournaled":
                    (parent / (
                        ".wide-elevation-stage-"
                        "11111111-1111-1111-1111-111111111111"
                    )).mkdir()
                else:
                    (parent / ".wide-elevation-promotion-v1.json").write_text(
                        "{}\n", encoding="utf-8"
                    )
                refused = self.run_cli(
                    "open-project", "--installation-root", installation,
                    "--validate-only"
                )
                self.assertEqual(3, refused.returncode)
                self.assertIn("RECOVERY_REQUIRED", refused.stderr)
                self.assertEqual(source_before, tree_bytes(project / "source"))
                self.assertEqual(target_before, tree_bytes(target, installation))

    def test_read_only_verification_does_not_recover_pending_wide_promotion(self):
        with tempfile.TemporaryDirectory(
            prefix="adaptive-wide-promotion-read-only-"
        ) as temp:
            base = Path(temp)
            target = self.fixtures.descriptor_fixture(str(base), world_space="global")
            installation = target / "World Builder 2"
            installation.mkdir()
            runtime = self.make_runtime(installation)
            report = base / "layered-report.json"
            self.discover(target, report)
            created, summary = self.create_project(
                installation, runtime, target, report,
                "Read-only promotion refusal", 44032
            )
            self.assertEqual(0, created.returncode, created.stderr)
            project = Path(summary["projectRoot"])
            self.install_legacy_working_package(installation, project)
            crashed = self.run_promotion_crash(project, "wide-promotion-staged")
            self.assertEqual(71, crashed.returncode, crashed.stderr)
            before = tree_bytes(installation)

            refused = self.run_cli("export-adaptive", "--project", project)
            self.assertEqual(3, refused.returncode, refused.stderr)
            self.assertIn("RECOVERY_REQUIRED", refused.stderr)
            self.assertEqual(before, tree_bytes(installation))

            recovered = self.run_cli(
                "open-project", "--installation-root", installation,
                "--validate-only"
            )
            self.assertEqual(0, recovered.returncode, recovered.stderr)
            self.assertFalse(any(
                path.name.startswith(".wide-elevation-")
                for path in installation.rglob("*")
            ))

    def test_save_entry_recovers_promotion_with_missing_working_package(self):
        with tempfile.TemporaryDirectory(
            prefix="adaptive-wide-promotion-save-recovery-"
        ) as temp:
            base = Path(temp)
            target = self.fixtures.descriptor_fixture(str(base), world_space="global")
            installation = target / "World Builder 2"
            installation.mkdir()
            runtime = self.make_runtime(installation)
            report = base / "layered-report.json"
            self.discover(target, report)
            created, summary = self.create_project(
                installation, runtime, target, report,
                "Save promotion recovery", 44031
            )
            self.assertEqual(0, created.returncode, created.stderr)
            project = Path(summary["projectRoot"])
            self.install_legacy_working_package(installation, project)
            source_before = tree_bytes(project / "source")
            target_before = tree_bytes(target, installation)
            crashed = self.run_promotion_crash(project, "wide-promotion-v1-aside")
            self.assertEqual(71, crashed.returncode, crashed.stderr)
            self.assertFalse((project / "working/layered-world/package").exists())
            saved = self.run_cli("save-project", "--project", project)
            self.assertEqual(0, saved.returncode, saved.stderr)
            self.assertEqual(source_before, tree_bytes(project / "source"))
            self.assertEqual(target_before, tree_bytes(target, installation))
            self.assertFalse(any(
                path.name.startswith(".wide-elevation-")
                for path in installation.rglob("*")
            ))

    def test_layered_adoption_save_and_portable_detached_reopen(self):
        with tempfile.TemporaryDirectory(prefix="adaptive-project-layered-") as temp:
            base = Path(temp)
            target = self.fixtures.descriptor_fixture(
                str(base), world_space="global"
            )
            installation = target / "World Builder 2"
            installation.mkdir()
            runtime = self.make_runtime(installation)
            report = base / "layered-report.json"
            discovery = self.discover(target, report)
            target_before = tree_bytes(target, installation)
            created, summary = self.create_project(
                installation, runtime, target, report, "Adopted map", 43802
            )
            self.assertEqual(0, created.returncode, created.stderr)
            self.assertEqual("target-layered", summary["origin"])
            self.assertEqual(target_before, tree_bytes(target, installation))
            project = Path(summary["projectRoot"])
            manifest = json.loads((project / "project.json").read_text(encoding="utf-8"))
            self.assertEqual(str(target.resolve()), manifest["target"]["locatorDisplay"])
            self.assertEqual("", manifest["fingerprints"]["conversionSha256"])
            self.assertFalse((project / "source/conversion").exists())
            baseline_package = project / "source/layered-baseline/package"
            working_package = project / "working/layered-world/package"
            baseline_manifest = json.loads(
                (baseline_package / "manifest.json").read_text(encoding="utf-8")
            )
            working_manifest = json.loads(
                (working_package / "manifest.json").read_text(encoding="utf-8")
            )
            self.assertTrue(all(
                item["encoding"] == "raw-layered-sector-v1"
                for item in baseline_manifest["terrainSectors"]
            ))
            self.assertTrue(all(
                item["encoding"] == "raw-layered-sector-v2-u16"
                for item in working_manifest["terrainSectors"]
            ))
            for source_sector, editable_sector in zip(
                baseline_manifest["terrainSectors"], working_manifest["terrainSectors"]
            ):
                legacy = (baseline_package / source_sector["path"]).read_bytes()
                promoted = b"".join(
                    b"\0" + legacy[offset : offset + 10]
                    for offset in range(0, len(legacy), 10)
                )
                self.assertEqual(
                    promoted, (working_package / editable_sector["path"]).read_bytes()
                )
            install_before_validation = tree_bytes(installation)
            validated = self.run_cli(
                "open-project",
                "--installation-root",
                installation,
                "--validate-only",
            )
            self.assertEqual(0, validated.returncode, validated.stderr)
            self.assertEqual("ready-attached", json.loads(validated.stdout)["state"])
            self.assertEqual(install_before_validation, tree_bytes(installation))
            target_display = str(target.resolve()).encode()
            for path in project.rglob("*"):
                if path.is_file() and path != project / "project.json":
                    self.assertNotIn(target_display, path.read_bytes(), path)

            baseline_before = tree_bytes(project / "source/layered-baseline/package")
            source_before = tree_bytes(project / "source")
            self.change_working_terrain(project)
            saved = self.run_cli("save-project", "--project", project)
            self.assertEqual(0, saved.returncode, saved.stderr)
            self.assertEqual(baseline_before, tree_bytes(project / "source/layered-baseline/package"))
            self.assertEqual(source_before, tree_bytes(project / "source"))
            self.assertEqual(target_before, tree_bytes(target, installation))

            supervised = self.run_supervision(project)
            self.assertEqual(0, supervised.returncode, supervised.stdout + supervised.stderr)
            self.assertEqual(source_before, tree_bytes(project / "source"))
            self.assertEqual(target_before, tree_bytes(target, installation))

            import_preview = self.run_cli(
                "import-active-adaptive", "--installation-root", installation
            )
            self.assertEqual(0, import_preview.returncode, import_preview.stderr)
            self.assertIn("Import preview", import_preview.stderr)
            self.assertEqual("", import_preview.stdout)
            self.assertIn("Import cancelled", import_preview.stderr)
            self.assertEqual(target_before, tree_bytes(target, installation))

            undo_without_import = self.run_cli(
                "undo-active-adaptive", "--installation-root", installation
            )
            self.assertEqual(3, undo_without_import.returncode)
            self.assertIn("no successful unreverted", undo_without_import.stderr.lower())
            self.assertEqual(target_before, tree_bytes(target, installation))

            portable = base / "portable-copy"
            shutil.copytree(installation, portable)
            copied_project = portable / "projects" / summary["projectId"]
            copied_source = tree_bytes(copied_project / "source")
            opened = self.run_cli("open-project", "--installation-root", portable)
            self.assertEqual(0, opened.returncode, opened.stderr)
            opened_summary = json.loads(opened.stdout)
            self.assertEqual("ready-detached", opened_summary["state"])
            self.assertEqual(copied_source, tree_bytes(copied_project / "source"))
            self.assertEqual(target_before, tree_bytes(target, installation))
            copied_manifest = json.loads(
                (copied_project / "project.json").read_text(encoding="utf-8")
            )
            self.assertEqual(
                str(target.resolve()), copied_manifest["target"]["locatorDisplay"]
            )

            wrong_target = base / "wrong-target"
            wrong_target.mkdir()
            wrong = self.run_cli(
                "open-project",
                "--installation-root",
                portable,
                "--target-root",
                wrong_target,
            )
            self.assertEqual(0, wrong.returncode, wrong.stderr)
            copied_manifest = json.loads(
                (copied_project / "project.json").read_text(encoding="utf-8")
            )
            self.assertEqual(
                str(target.resolve()), copied_manifest["target"]["locatorDisplay"]
            )

            moved_target = base / "moved-target"
            shutil.copytree(
                target,
                moved_target,
                ignore=shutil.ignore_patterns("World Builder 2"),
            )
            moved_before = tree_bytes(moved_target)
            reattached = self.run_cli(
                "open-project",
                "--installation-root",
                portable,
                "--target-root",
                moved_target,
            )
            self.assertEqual(0, reattached.returncode, reattached.stderr)
            self.assertEqual("ready-attached", json.loads(reattached.stdout)["state"])
            copied_manifest = json.loads(
                (copied_project / "project.json").read_text(encoding="utf-8")
            )
            self.assertEqual(
                str(moved_target.resolve()),
                copied_manifest["target"]["locatorDisplay"],
            )
            self.assertEqual(moved_before, tree_bytes(moved_target))

    def test_validate_only_refuses_missing_registry_lock_without_creating_it(self):
        with tempfile.TemporaryDirectory(prefix="adaptive-project-read-only-lock-") as temp:
            base = Path(temp)
            target = self.fixtures.descriptor_fixture(str(base))
            installation = target / "World Builder 2"
            installation.mkdir()
            runtime = self.make_runtime(installation)
            report = base / "layered-report.json"
            self.discover(target, report)
            created, _ = self.create_project(
                installation, runtime, target, report, "Read-only validation", 43812
            )
            self.assertEqual(0, created.returncode, created.stderr)

            registry_lock = installation / "projects/.registry.lock"
            self.assertTrue(registry_lock.is_file())
            registry_lock.unlink()
            installation_before = tree_bytes(installation)

            refused = self.run_cli(
                "open-project",
                "--installation-root",
                installation,
                "--target-root",
                target,
                "--validate-only",
            )
            self.assertEqual(3, refused.returncode, refused.stderr)
            self.assertIn("RECOVERY_REQUIRED", refused.stderr)
            self.assertIn("registry lock is missing or unsafe", refused.stderr)
            self.assertFalse(registry_lock.exists())
            self.assertEqual(installation_before, tree_bytes(installation))

    def test_packed_conversion_is_project_local_and_preserves_target(self):
        with tempfile.TemporaryDirectory(prefix="adaptive-project-packed-") as temp:
            base = Path(temp)
            target = self.packed_fixtures.fixture(base)
            installation = base / "World Builder 2"
            installation.mkdir()
            runtime = self.make_runtime(installation)
            report = base / "packed-report.json"
            discovery = self.discover(target, report)
            target_before = tree_bytes(target)
            created, summary = self.create_project(
                installation, runtime, target, report, "Converted map", 43803
            )
            self.assertEqual(0, created.returncode, created.stderr)
            self.assertEqual("target-packed", summary["origin"])
            self.assertEqual(target_before, tree_bytes(target))
            project = Path(summary["projectRoot"])
            self.assertTrue((project / "source/conversion/plan.json").is_file())
            self.assertTrue((project / "source/conversion/report.json").is_file())
            conversion = json.loads(
                (project / "source/conversion/report.json").read_text(encoding="utf-8")
            )
            self.assertFalse(conversion["blocked"])
            self.assertEqual(0, conversion["validation"]["parityDeltaCount"])
            self.assertEqual(
                conversion["terrain"]["entriesRead"],
                conversion["terrain"]["reverseMatched"],
            )
            self.assertFalse(list(project.rglob(".conversion-output")))
            self.assertFalse(list((installation / "projects").glob(".staging-*")))
            source_before = tree_bytes(project / "source")
            supervised = self.run_supervision(project)
            self.assertEqual(0, supervised.returncode, supervised.stdout + supervised.stderr)
            self.assertEqual(source_before, tree_bytes(project / "source"))
            self.assertEqual(target_before, tree_bytes(target))
            opened = self.run_cli(
                "open-project", "--installation-root", installation, "--target-root", target
            )
            self.assertEqual(0, opened.returncode, opened.stderr)
            self.assertEqual(discovery["representation"], "packed")

    def test_descriptorless_packed_fallback_creates_project_local_evidence(self):
        with tempfile.TemporaryDirectory(prefix="adaptive-project-packed-fallback-") as temp:
            base = Path(temp)
            target = self.fixtures.legacy_fixture(str(base))
            write_json(
                target / "server/conf/server/defs/locs/MyWorldSceneryRemovals.json",
                {"scenery_removals": [{"pos": {"X": 1, "Y": 1}}]},
            )
            write_json(
                target / "server/conf/server/defs/locs/MyWorldNpcLocs.json",
                {
                    "npclocs": [
                        {
                            "id": 846,
                            "start": {"X": 2, "Y": 2},
                            "min": {"X": 1, "Y": 1},
                            "max": {"X": 3, "Y": 3},
                        }
                    ]
                },
            )
            # The selected source intentionally has no declarative definition
            # for placed NPC 846. Project creation must preserve the placement
            # with a project-local placeholder instead of blocking conversion.
            write_json(
                target / "server/conf/server/defs/NpcDefsMyWorld.json",
                {"npcs": []},
            )
            write_json(
                target / "server/conf/server/defs/locs/MyWorldGroundItemLocs.json",
                {
                    "ground_items": [
                        {
                            "id": 9000,
                            "pos": {"X": 3, "Y": 3},
                            "amount": 2,
                            "respawn": 90,
                        }
                    ]
                },
            )
            base_scenery_path = (
                target / "server/conf/server/defs/locs/SceneryLocs.json"
            )
            base_scenery = json.loads(
                base_scenery_path.read_text(encoding="utf-8")
            )
            base_scenery["sceneries"].append({
                "id": 54,
                "pos": {"X": 0, "Y": 6},
                "direction": 4,
            })
            write_json(base_scenery_path, base_scenery)
            (target / "server/conf/server/defs/GameObjectDef.xml").write_text(
                "<GameObjectDef-array>"
                + "".join(
                    "<GameObjectDef><name>fixture</name>"
                    + ("<width>2</width><height>1</height>" if index == 54
                       else "<width>1</width><height>1</height>")
                    + "</GameObjectDef>"
                    for index in range(60)
                )
                + "</GameObjectDef-array>\n",
                encoding="utf-8",
            )
            server_terrain = (
                target / "server/conf/server/data/Custom_Landscape.orsc"
            )
            packed_sector = bytearray(48 * 48 * 10)
            for tile in (6, 54):
                offset = tile * 10 + 6
                packed_sector[offset : offset + 4] = (48055).to_bytes(
                    4, byteorder="big", signed=True
                )
            custom_scenery_tile = 100
            custom_scenery_offset = custom_scenery_tile * 10 + 6
            packed_sector[
                custom_scenery_offset : custom_scenery_offset + 4
            ] = (48060).to_bytes(4, byteorder="big", signed=True)
            custom_floor_tile = 200
            packed_sector[custom_floor_tile * 10 + 2] = 32
            custom_wall_tile = 201
            packed_sector[custom_wall_tile * 10 + 4] = 220
            sentinel_tile = 653
            sentinel_offset = sentinel_tile * 10 + 6
            packed_sector[sentinel_offset : sentinel_offset + 4] = (12000).to_bytes(
                4, byteorder="big", signed=True
            )
            with zipfile.ZipFile(server_terrain, "w", zipfile.ZIP_DEFLATED) as archive:
                archive.writestr("h0x48y37", packed_sector)
                archive.writestr("h0x50y50", bytes(48 * 48 * 10))
            shutil.copy2(
                server_terrain,
                target / "Client_Base/Cache/video/Custom_Landscape.orsc",
            )
            installation = base / "World Builder 2"
            installation.mkdir()
            runtime = self.make_runtime(installation, scenery_count=55)
            report = base / "fallback-report.json"
            discovery = self.discover(target, report)
            self.assertEqual("compatible", discovery["status"])
            self.assertFalse(discovery["descriptor"]["present"])
            target_before = tree_bytes(target)

            created, summary = self.create_project(
                installation, runtime, target, report,
                "Descriptorless packed fallback", 43804,
            )
            self.assertEqual(0, created.returncode, created.stderr)
            project = Path(summary["projectRoot"])
            runtime_metadata = json.loads(
                (project / "working/runtime/runtime.json").read_text(
                    encoding="utf-8"
                )
            )
            self.assertEqual(
                STANDALONE_INITIAL_LOCATION["level"],
                runtime_metadata["initialLayer"],
            )
            self.assertEqual(
                STANDALONE_INITIAL_LOCATION["x"], runtime_metadata["initialX"]
            )
            self.assertEqual(
                STANDALONE_INITIAL_LOCATION["y"], runtime_metadata["initialY"]
            )
            conversion = json.loads(
                (project / "source/conversion/report.json").read_text(encoding="utf-8")
            )
            inert_removals = [
                value for value in conversion["decisions"]
                if value["kind"] == "removal" and value["outcome"] == "retained"
            ]
            self.assertEqual(1, len(inert_removals))
            self.assertIn(
                "MyWorldSceneryRemovals.json#record=0 matched no effective placement",
                inert_removals[0]["provenance"],
            )
            embedded_completions = [
                value for value in conversion["decisions"]
                if value["kind"] == "replacement"
                and "supplies direction for" in value["provenance"]
            ]
            self.assertEqual(1, len(embedded_completions))
            self.assertIn(
                "server/world-builder-fallback/scenery.json#record=1 "
                "supplies direction for server/conf/server/data/Custom_Landscape.orsc"
                "#entry=h0x48y37&tile=6",
                embedded_completions[0]["provenance"],
            )
            manifest = json.loads((project / "project.json").read_text(encoding="utf-8"))
            snapshot = json.loads(
                (project / "source/snapshot-manifest.json").read_text(encoding="utf-8")
            )
            self.assertEqual("target-packed", manifest["origin"])
            self.assertEqual("ready-detached", manifest["state"])
            self.assertFalse(manifest["operations"]["import"])
            self.assertEqual("no-import-v1", manifest["target"]["importProfileId"])
            self.assertEqual(
                "spoiled-milk-packed-fallback-v1", snapshot["capabilityId"]
            )
            self.assertEqual(
                "source/original/server/world-builder-configs/primary.json",
                snapshot["selectedConfiguration"]["relativePath"],
            )
            self.assertTrue(
                (project / "source/original/server/world-builder-capabilities.json").is_file()
            )
            source_content = project / "source/content-bundle"
            working_content = project / "working/content-bundle"
            source_bundle = json.loads(
                (source_content / "manifest.json").read_text(encoding="utf-8")
            )
            working_bundle = json.loads(
                (working_content / "manifest.json").read_text(encoding="utf-8")
            )
            self.assertEqual(
                "project-local-custom-content-v2", source_bundle["capabilityId"]
            )
            self.assertEqual(
                source_bundle["bundleFingerprintSha256"],
                working_bundle["bundleFingerprintSha256"],
            )
            self.assertIn(846, source_bundle["definitionCatalog"]["npcs"])
            self.assertIn(9000, source_bundle["definitionCatalog"]["groundItems"])
            self.assertIn(31, source_bundle["definitionCatalog"]["tiles"])
            self.assertIn(219, source_bundle["definitionCatalog"]["boundaries"])
            self.assertEqual(
                [9000, 9001, 9002],
                [value["itemId"] for value in source_bundle["itemVisuals"]],
            )
            self.assertEqual(
                tree_bytes(source_content), tree_bytes(working_content)
            )
            package = project / "working/layered-world/package"
            package_manifest = json.loads(
                (package / "manifest.json").read_text(encoding="utf-8")
            )
            terrain_record = next(
                value for value in package_manifest["terrainSectors"]
                if value["level"] == 0 and value["sectorX"] == 0
                and value["sectorY"] == 0
            )
            layered = (package / terrain_record["path"]).read_bytes()
            for tile in (6, 54, sentinel_tile):
                offset = tile * 11 + 7
                self.assertEqual(bytes(4), layered[offset : offset + 4])
            self.assertEqual(32, layered[custom_floor_tile * 11 + 3])
            self.assertEqual(220, layered[custom_wall_tile * 11 + 6])
            placement_record = next(
                value for value in package_manifest["placementSets"]
                if value["level"] == 0
            )
            placements = json.loads(
                (package / placement_record["path"]).read_text(encoding="utf-8")
            )
            self.assertEqual(
                [
                    {"direction": 4, "position": {"x": 0, "y": 6}, "sceneryId": 54},
                    {"direction": 0, "position": {"x": 2, "y": 4}, "sceneryId": 59},
                    {"direction": 0, "position": {"x": 13, "y": 10}, "sceneryId": 1},
                ],
                [
                    {
                        "direction": value["direction"],
                        "position": value["position"],
                        "sceneryId": value["sceneryId"],
                    }
                    for value in placements["scenery"]
                ],
            )
            self.assertEqual(
                [846, 1, 1], [value["npcId"] for value in placements["npcs"]]
            )
            self.assertEqual(
                [1], [value["boundaryId"] for value in placements["boundaries"]]
            )
            npc_warnings = json.loads(
                (project / "diagnostics/npc-definition-provider-warnings.json")
                .read_text(encoding="utf-8")
            )
            self.assertIn(
                846,
                [
                    value["npcId"] for value in npc_warnings["warnings"]
                    if value["code"] == "NPC_DEFINITION_PLACEHOLDER"
                ],
            )
            self.assertEqual(
                [9000, 7], [value["itemId"] for value in placements["groundItems"]]
            )
            copied_archive = (
                project
                / "source/original/server/conf/server/data/Custom_Landscape.orsc"
            )
            with zipfile.ZipFile(copied_archive) as archive:
                self.assertEqual(bytes(packed_sector), archive.read("h0x48y37"))
            self.assertEqual(target_before, tree_bytes(target))

            source_before = tree_bytes(project / "source")
            supervised = self.run_supervision(project)
            self.assertEqual(
                0, supervised.returncode, supervised.stdout + supervised.stderr
            )
            self.assertEqual(source_before, tree_bytes(project / "source"))
            self.assertEqual(target_before, tree_bytes(target))

            working_model = (
                working_content / "files/client/Cache/video/models.orsc"
            )
            model_before = working_model.read_bytes()
            working_model.write_bytes(model_before + b"drift")
            mismatched = self.run_cli("save-project", "--project", project)
            self.assertEqual(3, mismatched.returncode, mismatched.stdout)
            self.assertIn("SOURCE_CORRUPT", mismatched.stderr)
            self.assertEqual(target_before, tree_bytes(target))
            working_model.write_bytes(model_before)

            saved = self.run_cli("save-project", "--project", project)
            self.assertEqual(0, saved.returncode, saved.stderr)
            reopened = self.run_cli(
                "open-project", "--installation-root", installation,
                "--target-root", target,
            )
            self.assertEqual(0, reopened.returncode, reopened.stderr)
            self.assertEqual("ready-detached", json.loads(reopened.stdout)["state"])
            refused_import = self.run_cli(
                "import-active-adaptive", "--installation-root", installation
            )
            self.assertEqual(3, refused_import.returncode)
            self.assertIn("LOADER_INCOMPATIBLE", refused_import.stderr)
            self.assertEqual(target_before, tree_bytes(target))

    def test_known_legacy_npc_roam_typo_is_corrected_only_in_project_evidence(self):
        with tempfile.TemporaryDirectory(prefix="adaptive-known-npc-roam-typo-") as temp:
            base = Path(temp)
            target = self.fixtures.legacy_fixture(str(base))
            npc_path = target / "server/conf/server/defs/locs/NpcLocs.json"
            npc_document = json.loads(npc_path.read_text(encoding="utf-8"))
            npc_document["npclocs"].append({
                "id": 67,
                "start": {"X": 647, "Y": 3534},
                "min": {"X": 632, "Y": 3519},
                "max": {"X": 662, "Y": 6549},
            })
            write_json(npc_path, npc_document)

            server_terrain = target / "server/conf/server/data/Custom_Landscape.orsc"
            with zipfile.ZipFile(server_terrain, "w", zipfile.ZIP_DEFLATED) as archive:
                archive.writestr("h0x48y37", bytes(48 * 48 * 10))
                archive.writestr("h3x61y51", bytes(48 * 48 * 10))
            shutil.copy2(
                server_terrain,
                target / "Client_Base/Cache/video/Custom_Landscape.orsc",
            )

            installation = base / "World Builder 2"
            installation.mkdir()
            runtime = self.make_runtime(installation)
            discovery_report = base / "report.json"
            self.discover(target, discovery_report)
            target_before = tree_bytes(target)

            created, summary = self.create_project(
                installation, runtime, target, discovery_report,
                "Known NPC roam typo", 43829,
            )
            self.assertEqual(0, created.returncode, created.stderr)
            project = Path(summary["projectRoot"])
            self.assertEqual(target_before, tree_bytes(target))

            copied_original = json.loads((
                project / "source/original/server/conf/server/defs/locs/NpcLocs.json"
            ).read_text(encoding="utf-8"))
            self.assertEqual(6549, copied_original["npclocs"][-1]["max"]["Y"])
            normalized = json.loads((
                project / "source/original/server/world-builder-fallback/npcs.json"
            ).read_text(encoding="utf-8"))
            self.assertEqual(3549, normalized["npclocs"][-1]["max"]["Y"])

            correction = json.loads((
                project / "diagnostics/packed-compatibility-corrections.json"
            ).read_text(encoding="utf-8"))
            self.assertEqual(
                "world-builder-packed-compatibility-corrections",
                correction["manifestType"],
            )
            self.assertEqual([{
                "profileId": "openrsc-npc-67-max-y-transposition-v1",
                "sourceRelativePath":
                    "server/conf/server/defs/locs/NpcLocs.json",
                "recordIndex": 2,
                "npcId": 67,
                "field": "max.Y",
                "originalValue": 6549,
                "correctedValue": 3549,
                "reason": (
                    "Exact known legacy transposition: the original bound is outside every "
                    "supported packed plane; 3549 restores the symmetric 30x30 roam box."
                ),
            }], correction["corrections"])

            package = project / "working/layered-world/package"
            manifest = json.loads((package / "manifest.json").read_text(encoding="utf-8"))
            npcs = []
            for declaration in manifest["placementSets"]:
                placements = json.loads(
                    (package / declaration["path"]).read_text(encoding="utf-8")
                )
                npcs.extend(placements["npcs"])
            corrected = next(value for value in npcs if value["npcId"] == 67)
            self.assertEqual({"x": 647, "y": 702}, corrected["start"])
            self.assertEqual(
                {"x": 632, "y": 687}, corrected["roamBounds"]["minimum"]
            )
            self.assertEqual(
                {"x": 662, "y": 717}, corrected["roamBounds"]["maximum"]
            )

    def test_near_match_legacy_npc_roam_typo_remains_blocked(self):
        with tempfile.TemporaryDirectory(prefix="adaptive-near-npc-roam-typo-") as temp:
            base = Path(temp)
            target = self.fixtures.legacy_fixture(str(base))
            npc_path = target / "server/conf/server/defs/locs/NpcLocs.json"
            npc_document = json.loads(npc_path.read_text(encoding="utf-8"))
            npc_document["npclocs"].append({
                "id": 67,
                "start": {"X": 647, "Y": 3534},
                "min": {"X": 632, "Y": 3519},
                "max": {"X": 662, "Y": 6550},
            })
            write_json(npc_path, npc_document)

            server_terrain = target / "server/conf/server/data/Custom_Landscape.orsc"
            with zipfile.ZipFile(server_terrain, "w", zipfile.ZIP_DEFLATED) as archive:
                archive.writestr("h0x48y37", bytes(48 * 48 * 10))
            shutil.copy2(
                server_terrain,
                target / "Client_Base/Cache/video/Custom_Landscape.orsc",
            )

            installation = base / "World Builder 2"
            installation.mkdir()
            runtime = self.make_runtime(installation)
            discovery_report = base / "report.json"
            self.discover(target, discovery_report)
            target_before = tree_bytes(target)

            created, summary = self.create_project(
                installation, runtime, target, discovery_report,
                "Near NPC roam typo", 43830,
            )
            self.assertEqual(3, created.returncode, created.stdout)
            self.assertIsNone(summary)
            self.assertIn(
                "Packed placement coordinate is outside the exact legacy range: 662,6550",
                created.stderr,
            )
            self.assertEqual(target_before, tree_bytes(target))
            self.assertFalse(list((installation / "projects").glob("[0-9a-f]*")))

    def test_embedded_and_explicit_scenery_id_mismatch_remains_blocked(self):
        with tempfile.TemporaryDirectory(prefix="adaptive-scenery-id-collision-") as temp:
            base = Path(temp)
            target = self.fixtures.legacy_fixture(str(base))
            scenery_path = target / "server/conf/server/defs/locs/SceneryLocs.json"
            scenery = json.loads(scenery_path.read_text(encoding="utf-8"))
            scenery["sceneries"].append({
                "id": 53,
                "pos": {"X": 0, "Y": 6},
                "direction": 4,
            })
            write_json(scenery_path, scenery)

            packed_sector = bytearray(48 * 48 * 10)
            packed_sector[6 * 10 + 6 : 6 * 10 + 10] = (48055).to_bytes(
                4, byteorder="big", signed=True
            )
            server_terrain = target / "server/conf/server/data/Custom_Landscape.orsc"
            with zipfile.ZipFile(server_terrain, "w", zipfile.ZIP_DEFLATED) as archive:
                archive.writestr("h0x48y37", packed_sector)
            shutil.copy2(
                server_terrain,
                target / "Client_Base/Cache/video/Custom_Landscape.orsc",
            )

            installation = base / "World Builder 2"
            installation.mkdir()
            runtime = self.make_runtime(installation, scenery_count=55)
            discovery_report = base / "report.json"
            self.discover(target, discovery_report)
            target_before = tree_bytes(target)

            created, summary = self.create_project(
                installation, runtime, target, discovery_report,
                "Mismatched scenery collision", 43832,
            )
            self.assertEqual(3, created.returncode, created.stdout)
            self.assertIsNone(summary)
            self.assertIn("Packed base placement collides at record 1", created.stderr)
            self.assertEqual(target_before, tree_bytes(target))
            self.assertFalse(list((installation / "projects").glob("[0-9a-f]*")))

    def test_beyond_packaged_item_visual_archive_blockers_preserve_target_bytes(self):
        cases = {}

        def missing_entry(target: Path) -> None:
            archive = target / "Client_Base/Cache/video/Custom_Sprites.osar"
            archive.write_bytes(self.fixtures.fixture_osar([
                ("items", [("different", self.fixtures.fixture_sprite_entry())]),
            ]))

        cases["missing-entry"] = (missing_entry, "archive entry is missing")

        for name, (mutation, expected) in cases.items():
            with self.subTest(case=name), tempfile.TemporaryDirectory(
                prefix="adaptive-item-visual-blocker-"
            ) as temp:
                base = Path(temp)
                target = self.fixtures.legacy_fixture(str(base))
                mutation(target)
                installation = base / "World Builder 2"
                installation.mkdir()
                runtime = self.make_runtime(installation)
                report = base / "report.json"
                discovered = self.discover(target, report)
                self.assertEqual("compatible", discovered["status"])
                before = tree_bytes(target)
                created, _ = self.create_project(
                    installation, runtime, target, report,
                    "Blocked item visual", 43816,
                )
                self.assertEqual(3, created.returncode, created.stdout)
                self.assertIn(expected, created.stderr)
                self.assertEqual(before, tree_bytes(target))
                self.assertFalse(list((installation / "projects").glob("[0-9a-f]*")))

    def test_item_visuals_migrate_from_inert_definitions_inside_project_only(self):
        with tempfile.TemporaryDirectory(prefix="adaptive-item-visual-derived-") as temp:
            base = Path(temp)
            target = self.fixtures.legacy_fixture(str(base))
            evidence = target / "server/conf/world-builder/item-visuals-v1.json"
            original_visuals = json.loads(evidence.read_text(encoding="utf-8"))["itemVisuals"]
            evidence.unlink()
            definitions = target / "server/conf/server/defs"
            write_json(definitions / "ItemDefsCustom.json", {"items": [{
                "id": 9000, "sprite": "items/0",
                "pictureMask": 0x336699, "blueMask": 0x112233,
            }]})
            write_json(definitions / "ItemDefsMyWorld.json", {"items": [{
                "id": 9001, "authenticSpriteId": 417,
                "pictureMask": -1, "blueMask": 0,
            }]})
            write_json(definitions / "ItemDefsPatch18.json", {"items": [{
                "id": 9002, "sprite": "GUI/0",
                "pictureMask": 0x445566, "blueMask": -16776961,
            }]})
            authentic = target / "Client_Base/Cache/video/Authentic_Sprites.orsc"
            with zipfile.ZipFile(authentic, "w", zipfile.ZIP_DEFLATED) as archive:
                archive.writestr("sprites/417.dat", b"captured authentic sprite")
            server_terrain = target / "server/conf/server/data/Custom_Landscape.orsc"
            with zipfile.ZipFile(server_terrain, "w", zipfile.ZIP_DEFLATED) as archive:
                archive.writestr("h0x48y37", bytes(48 * 48 * 10))
            shutil.copy2(server_terrain,
                target / "Client_Base/Cache/video/Custom_Landscape.orsc")
            installation = base / "World Builder 2"
            installation.mkdir()
            runtime = self.make_runtime(installation)
            report = base / "report.json"
            self.discover(target, report)
            before = tree_bytes(target)
            created, summary = self.create_project(
                installation, runtime, target, report, "Derived visuals", 43818,
            )
            self.assertEqual(0, created.returncode, created.stderr)
            project = Path(summary["projectRoot"])
            generated = json.loads((project /
                "source/content-bundle/files/server/conf/world-builder/item-visuals-v1.json"
            ).read_text(encoding="utf-8"))
            self.assertEqual(original_visuals, generated["itemVisuals"])
            self.assertFalse((project /
                "source/original/server/conf/world-builder/item-visuals-v1.json").exists())
            self.assertEqual(before, tree_bytes(target))

    def test_explicit_item_visual_mapping_is_validated_and_target_preserving(self):
        with tempfile.TemporaryDirectory(prefix="adaptive-item-visual-explicit-") as temp:
            base = Path(temp)
            target = self.fixtures.legacy_fixture(str(base))
            evidence = target / "server/conf/world-builder/item-visuals-v1.json"
            visuals = json.loads(evidence.read_text(encoding="utf-8"))["itemVisuals"]
            evidence.unlink()
            server_terrain = target / "server/conf/server/data/Custom_Landscape.orsc"
            with zipfile.ZipFile(server_terrain, "w", zipfile.ZIP_DEFLATED) as archive:
                archive.writestr("h0x48y37", bytes(48 * 48 * 10))
            shutil.copy2(server_terrain,
                target / "Client_Base/Cache/video/Custom_Landscape.orsc")
            mapping = base / "mapping.json"
            write_json(mapping, {
                "schemaVersion": 1,
                "manifestType": "world-builder-item-visual-mapping",
                "itemVisuals": visuals,
            })
            installation = base / "World Builder 2"
            installation.mkdir()
            runtime = self.make_runtime(installation)
            report = base / "report.json"
            self.discover(target, report)
            before = tree_bytes(target)
            created = self.run_cli(
                "create-project", "--installation-root", installation,
                "--runtime-root", runtime, "--target-root", target,
                "--discovery-report", report, "--display-name", "Mapped visuals",
                "--port", 43819, "--item-visual-mappings", mapping,
                "--confirm", "CREATE",
            )
            self.assertEqual(0, created.returncode, created.stderr)
            project = Path(json.loads(created.stdout)["projectRoot"])
            generated = json.loads((project /
                "source/content-bundle/files/server/conf/world-builder/item-visuals-v1.json"
            ).read_text(encoding="utf-8"))
            self.assertEqual(visuals, generated["itemVisuals"])
            self.assertEqual(before, tree_bytes(target))

    def test_neutral_provider_resolves_all_asset_roles_and_preserves_masks(self):
        with tempfile.TemporaryDirectory(prefix="adaptive-neutral-provider-") as temp:
            base = Path(temp)
            target = self.fixtures.legacy_fixture(str(base))
            (target / "server/conf/world-builder/item-visuals-v1.json").unlink()
            (target / "Client_Base/Cache/video/Custom_Sprites.osar").write_bytes(
                self.fixtures.fixture_osar([
                    ("items", [("0", self.fixtures.fixture_sprite_entry(0x336699))]),
                    ("world_builder_provider", [
                        ("existing", self.fixtures.fixture_sprite_entry(0x010203)),
                    ]),
                ])
            )
            definitions = target / "server/conf/server/defs"
            write_json(definitions / "ItemDefsPatch18.json", {
                "items": [{"id": 9002}, {"id": 9003}],
            })
            server_terrain = target / "server/conf/server/data/Custom_Landscape.orsc"
            with zipfile.ZipFile(server_terrain, "w", zipfile.ZIP_DEFLATED) as archive:
                archive.writestr("h0x48y37", bytes(48 * 48 * 10))
            shutil.copy2(server_terrain,
                target / "Client_Base/Cache/video/Custom_Landscape.orsc")

            provider = base / "world-builder-provider"
            assets = provider / "assets"
            assets.mkdir(parents=True)
            authentic = assets / "Authentic_Sprites.orsc"
            with zipfile.ZipFile(authentic, "w", zipfile.ZIP_DEFLATED) as archive:
                archive.writestr("sprites/417.dat", b"authentic-417")
            custom = assets / "Custom_Sprites.osar"
            custom.write_bytes(self.fixtures.fixture_osar([
                ("items", [("custom_9000", self.fixtures.fixture_sprite_entry(0x123456))]),
            ]))
            spritepack = assets / "spritepacks/Items.osar"
            spritepack.parent.mkdir()
            spritepack.write_bytes(self.fixtures.fixture_osar([
                ("pack", [("sprite_9002", self.fixtures.fixture_sprite_entry(0x654321))]),
            ]))
            external = assets / "external-items/9003.png"
            external.parent.mkdir()
            external.write_bytes(one_pixel_png(0xABCDEF))
            records = [
                provider_visual(42, "Unrelated packaged item", "unresolved", None,
                    None, None),
                provider_visual(9000, "Custom item", "asset.sprite.custom",
                    "assets/Custom_Sprites.osar", sha256(custom),
                    "custom/items/custom_9000", subspace="items", entry="custom_9000",
                    picture_mask=0x102030, blue_mask=-1),
                provider_visual(9001, "Authentic item", "asset.sprite.authentic",
                    "assets/Authentic_Sprites.orsc", sha256(authentic),
                    "authentic/417", authentic=417, picture_mask=-2, blue_mask=3),
                provider_visual(9002, "Spritepack item", "asset.spritepack",
                    "assets/spritepacks/Items.osar", sha256(spritepack),
                    "spritepack/pack/sprite_9002", subspace="pack", entry="sprite_9002",
                    picture_mask=4, blue_mask=5),
                provider_visual(9003, "External item", "asset.sprite.external",
                    "assets/external-items/9003.png", sha256(external),
                    "external/assets/external-items/9003.png",
                    external={"relativePath": "assets/external-items/9003.png",
                              "sha256": sha256(external), "width": 1, "height": 1},
                    picture_mask=6, blue_mask=-7),
            ]
            manifest = provider / "item-visuals.json"
            write_json(manifest, {"schemaVersion": 1,
                "manifestType": "world-builder-item-visual-mapping",
                "itemVisuals": records})
            installation = base / "World Builder 2"
            installation.mkdir()
            runtime = self.make_runtime(installation)
            report = base / "report.json"
            self.discover(target, report)
            before = tree_bytes(target)
            created = self.run_cli(
                "create-project", "--installation-root", installation,
                "--runtime-root", runtime, "--target-root", target,
                "--discovery-report", report, "--display-name", "Neutral provider",
                "--port", 43823, "--item-visual-mappings", manifest,
                "--confirm", "CREATE",
            )
            self.assertEqual(0, created.returncode, created.stderr)
            project = Path(json.loads(created.stdout)["projectRoot"])
            evidence = json.loads((project /
                "source/content-bundle/files/server/conf/world-builder/item-visuals-v1.json"
            ).read_text(encoding="utf-8"))["itemVisuals"]
            self.assertEqual([9000, 9001, 9002, 9003],
                [record["itemId"] for record in evidence])
            self.assertEqual([(0x102030, -1), (-2, 3), (4, 5), (6, -7)],
                [(record["pictureMask"], record["blueMask"]) for record in evidence])
            self.assertEqual(417, evidence[1]["authenticSpriteId"])
            self.assertEqual("world_builder_provider_2", evidence[0]["customSpriteSubspace"])
            self.assertEqual("world_builder_provider_2", evidence[2]["customSpriteSubspace"])
            self.assertEqual("world_builder_provider_2", evidence[3]["customSpriteSubspace"])
            merged_authentic = (project /
                "source/content-bundle/files/client/Cache/video/Authentic_Sprites.orsc")
            with zipfile.ZipFile(merged_authentic) as archive:
                self.assertEqual(
                    {"sprites/base.bin", "sprites/417.dat"}, set(archive.namelist())
                )
                self.assertEqual(b"fixture authentic sprites",
                    archive.read("sprites/base.bin"))
                self.assertEqual(b"authentic-417", archive.read("sprites/417.dat"))
            provider_report = json.loads((project /
                "diagnostics/item-visual-provider-warnings.json").read_text(encoding="utf-8"))
            self.assertEqual([], provider_report["warnings"])
            self.assertEqual(["Custom item", "Authentic item", "Spritepack item", "External item"],
                [record["name"] for record in provider_report["items"]])
            self.assertEqual(before, tree_bytes(target))

    def test_versioned_provider_package_adapter_resolves_without_target_code(self):
        with tempfile.TemporaryDirectory(prefix="adaptive-versioned-provider-") as temp:
            base = Path(temp)
            target = self.fixtures.legacy_fixture(str(base))
            (target / "server/conf/world-builder/item-visuals-v1.json").unlink()
            server_terrain = target / "server/conf/server/data/Custom_Landscape.orsc"
            with zipfile.ZipFile(server_terrain, "w", zipfile.ZIP_DEFLATED) as archive:
                archive.writestr("h0x48y37", bytes(48 * 48 * 10))
            shutil.copy2(server_terrain,
                target / "Client_Base/Cache/video/Custom_Landscape.orsc")

            provider = base / "world-builder-provider"
            archives = provider / "assets/archives"
            archives.mkdir(parents=True)
            authentic = archives / "Authentic_Sprites.orsc"
            with zipfile.ZipFile(authentic, "w", zipfile.ZIP_DEFLATED) as archive:
                archive.writestr("sprites/417.dat", b"packaged-authentic-417")
            custom = archives / "Custom_Sprites.osar"
            custom.write_bytes(self.fixtures.fixture_osar([
                ("items", [("0", self.fixtures.fixture_sprite_entry(0x123456))]),
            ]))
            external = provider / "assets/external-png/items/9002.png"
            external.parent.mkdir(parents=True)
            external.write_bytes(one_pixel_png(0xABCDEF))
            catalog_hash = "a" * 64
            records = [
                {
                    "itemId": 9000, "diagnosticName": "Packaged custom",
                    "spriteLocation": "items:0", "spriteId": 0,
                    "resolvedBaseSourceRole": "custom-sprite-archive",
                    "authenticArchive": None,
                    "customOrSpritepack": {"subspace": "items", "entry": "0",
                        "baseArchiveEntrySha256": "b" * 64,
                        "spritepackOverrideKey": "items:0"},
                    "externalPng": None, "pictureMask": 1, "blueMask": 2,
                },
                {
                    "itemId": 9001, "diagnosticName": "Packaged authentic",
                    "spriteLocation": "authentic:417", "spriteId": 417,
                    "resolvedBaseSourceRole": "authentic-archive-fallback",
                    "authenticArchive": {"archiveId": 417,
                        "entrySha256": "c" * 64},
                    "customOrSpritepack": None, "externalPng": None,
                    "pictureMask": -3, "blueMask": 4,
                },
                {
                    "itemId": 9002, "diagnosticName": "Packaged external",
                    "spriteLocation": "external-png:9002@1x1", "spriteId": -1,
                    "resolvedBaseSourceRole": "external-png",
                    "authenticArchive": None, "customOrSpritepack": None,
                    "externalPng": {"specification": "9002@1x1",
                        "assetName": "9002", "targetWidth": 1, "targetHeight": 1,
                        "providerPath": "assets/external-png/items/9002.png",
                        "sha256": sha256(external)},
                    "pictureMask": 5, "blueMask": -6,
                },
            ]
            mapping = provider / "item-visuals-full-v1.json"
            write_json(mapping, {
                "schemaVersion": 1,
                "manifestType": "world-builder-item-visual-mapping",
                "provider": {"identity": "neutral-fixture", "definitionMode": "final",
                    "catalogItemCount": 3, "catalogSha256": catalog_hash, "inputs": []},
                "assetProviders": {
                    "customSpriteArchive": {"path": "assets/archives/Custom_Sprites.osar",
                        "sha256": sha256(custom), "entryCount": 1},
                    "authenticSpriteArchive": {"path": "assets/archives/Authentic_Sprites.orsc",
                        "sha256": sha256(authentic), "numericEntryCount": 1,
                        "itemArchiveBaseId": 0},
                    "externalPngPackaging": {"providerRoot": "assets/external-png",
                        "referencedPngCount": 1},
                },
                "selection": {"kind": "complete-final-catalog", "itemCount": 3,
                    "itemIdsSha256": "d" * 64, "mappingSha256": "e" * 64,
                    "minimumItemId": 9000, "maximumItemId": 9002},
                "itemVisuals": records,
            })
            package_files = []
            for path, role in (
                (authentic, "authentic-sprite-archive"),
                (custom, "custom-sprite-archive"),
                (external, "external-png"),
                (mapping, "full-item-visual-manifest"),
            ):
                package_files.append({"path": path.relative_to(provider).as_posix(),
                    "role": role, "size": path.stat().st_size, "sha256": sha256(path)})
            package_files.sort(key=lambda value: value["path"])
            write_json(provider / "package-manifest-v1.json", {
                "schemaVersion": 1,
                "manifestType": "world-builder-item-visual-provider-package",
                "providerDirectory": "world-builder-provider",
                "catalogSha256": catalog_hash,
                "files": package_files,
            })

            installation = base / "World Builder 2"
            installation.mkdir()
            runtime = self.make_runtime(installation)
            report = base / "report.json"
            self.discover(target, report)
            before = tree_bytes(target)
            created = self.run_cli(
                "create-project", "--installation-root", installation,
                "--runtime-root", runtime, "--target-root", target,
                "--discovery-report", report, "--display-name", "Versioned provider",
                "--port", 43828, "--item-visual-mappings", mapping,
                "--confirm", "CREATE",
            )
            self.assertEqual(0, created.returncode, created.stderr)
            project = Path(json.loads(created.stdout)["projectRoot"])
            evidence = json.loads((project /
                "source/content-bundle/files/server/conf/world-builder/item-visuals-v1.json"
            ).read_text(encoding="utf-8"))["itemVisuals"]
            self.assertEqual([9000, 9001, 9002],
                [record["itemId"] for record in evidence])
            self.assertEqual([(1, 2), (-3, 4), (5, -6)],
                [(record["pictureMask"], record["blueMask"]) for record in evidence])
            warning = json.loads((project /
                "diagnostics/item-visual-provider-warnings.json").read_text(encoding="utf-8"))
            self.assertEqual([], warning["warnings"])
            self.assertTrue(all(item["status"] == "resolved" for item in warning["items"]))
            self.assertEqual(before, tree_bytes(target))

            custom.write_bytes(custom.read_bytes() + b"package-drift")
            fallback_installation = base / "World Builder 2 fallback"
            fallback_installation.mkdir()
            fallback_runtime = self.make_runtime(fallback_installation)
            fallback_report = base / "fallback-report.json"
            self.discover(target, fallback_report)
            fallback = self.run_cli(
                "create-project", "--installation-root", fallback_installation,
                "--runtime-root", fallback_runtime, "--target-root", target,
                "--discovery-report", fallback_report,
                "--display-name", "Versioned provider fallback", "--port", 43829,
                "--item-visual-mappings", mapping, "--confirm", "CREATE",
            )
            self.assertEqual(0, fallback.returncode, fallback.stderr)
            fallback_project = Path(json.loads(fallback.stdout)["projectRoot"])
            fallback_warning = json.loads((fallback_project /
                "diagnostics/item-visual-provider-warnings.json").read_text(encoding="utf-8"))
            self.assertIn("PROVIDER_PACKAGE_HASH_MISMATCH",
                [record["code"] for record in fallback_warning["warnings"]])
            self.assertTrue(all(item["status"] == "placeholder"
                for item in fallback_warning["items"]))
            self.assertEqual(before, tree_bytes(target))

    def test_neutral_provider_invalid_and_unknown_visuals_fall_back_deterministically(self):
        cases = {
            "malformed": b"{malformed\n",
            "unknown-role": None,
            "unsafe-path": None,
            "hash-mismatch": None,
            "duplicate": None,
            "unresolved": None,
            "nested-symlink-escape": None,
        }
        for case, raw_manifest in cases.items():
            with self.subTest(case=case), tempfile.TemporaryDirectory(
                prefix=f"adaptive-neutral-provider-{case}-"
            ) as temp:
                base = Path(temp)
                target = self.fixtures.legacy_fixture(str(base))
                (target / "server/conf/world-builder/item-visuals-v1.json").unlink()
                server_terrain = target / "server/conf/server/data/Custom_Landscape.orsc"
                with zipfile.ZipFile(server_terrain, "w", zipfile.ZIP_DEFLATED) as archive:
                    archive.writestr("h0x48y37", bytes(48 * 48 * 10))
                shutil.copy2(server_terrain,
                    target / "Client_Base/Cache/video/Custom_Landscape.orsc")
                provider = base / "world-builder-provider"
                asset = provider / "assets/Custom_Sprites.osar"
                asset.parent.mkdir(parents=True)
                asset.write_bytes(self.fixtures.fixture_osar([
                    ("items", [("0", self.fixtures.fixture_sprite_entry())]),
                ]))
                asset_hash = sha256(asset)
                outside_asset = None
                if case == "nested-symlink-escape":
                    outside = base / "outside-provider-root"
                    outside.mkdir()
                    outside_asset = outside / "Custom_Sprites.osar"
                    asset.replace(outside_asset)
                    asset.parent.rmdir()
                    os.symlink(outside, asset.parent, target_is_directory=True)
                    outside_asset.chmod(0)
                manifest = provider / "item-visuals.json"
                if raw_manifest is not None:
                    manifest.write_bytes(raw_manifest)
                else:
                    record = provider_visual(9000, "Target 9000",
                        "asset.sprite.custom", "assets/Custom_Sprites.osar",
                        asset_hash, "custom/items/0", subspace="items", entry="0")
                    records = [record]
                    if case == "unknown-role":
                        record["sourceRole"] = "future.sprite.role"
                    elif case == "unsafe-path":
                        record["sourceAsset"] = "../outside.osar"
                    elif case == "hash-mismatch":
                        record["sourceAssetSha256"] = "0" * 64
                    elif case == "duplicate":
                        changed = dict(record)
                        changed["name"] = "Contradictory duplicate"
                        records.append(changed)
                    elif case == "unresolved":
                        record.update({
                            "logicalSpriteLocation": None,
                            "sourceRole": "unresolved",
                            "sourceAsset": None,
                            "sourceAssetSha256": None,
                            "authenticSpriteId": None,
                            "customSpriteSubspace": None,
                            "customSpriteEntry": None,
                            "externalPng": None,
                            "pictureMask": 0,
                            "blueMask": 0,
                        })
                    write_json(manifest, {"schemaVersion": 1,
                        "manifestType": "world-builder-item-visual-mapping",
                        "itemVisuals": records})
                installation = base / "World Builder 2"
                installation.mkdir()
                runtime = self.make_runtime(installation)
                report = base / "report.json"
                self.discover(target, report)
                target_before = tree_bytes(target)
                generated = []
                for index in range(2):
                    created = self.run_cli(
                        "create-project", "--installation-root", installation,
                        "--runtime-root", runtime, "--target-root", target,
                        "--discovery-report", report,
                        "--display-name", f"Fallback {case} {index}",
                        "--port", 43824 + index,
                        "--item-visual-mappings", manifest, "--confirm", "CREATE",
                    )
                    self.assertEqual(0, created.returncode, created.stderr)
                    project = Path(json.loads(created.stdout)["projectRoot"])
                    evidence = (project /
                        "source/content-bundle/files/server/conf/world-builder/item-visuals-v1.json"
                    ).read_bytes()
                    custom = (project /
                        "source/content-bundle/files/client/Cache/video/Custom_Sprites.osar"
                    ).read_bytes()
                    warning = json.loads((project /
                        "diagnostics/item-visual-provider-warnings.json"
                    ).read_text(encoding="utf-8"))
                    self.assertEqual([9000, 9001, 9002],
                        [item["itemId"] for item in warning["items"]])
                    self.assertTrue(all(item["status"] == "placeholder"
                        for item in warning["items"]))
                    self.assertTrue(warning["warnings"])
                    if case == "unresolved":
                        self.assertIn("PROVIDER_UNRESOLVED",
                            [item["code"] for item in warning["warnings"]])
                    if case == "nested-symlink-escape":
                        self.assertIn("PROVIDER_ASSET_PATH_UNSAFE",
                            [item["code"] for item in warning["warnings"]])
                    generated.append((evidence, custom))
                self.assertEqual(generated[0], generated[1])
                self.assertEqual(target_before, tree_bytes(target))
                if outside_asset is not None:
                    outside_asset.chmod(0o600)

    def test_item_visual_mapping_malformed_and_ambiguous_inputs_fail_closed(self):
        cases = {}

        def contradictory(base: Path, target: Path, visuals: list[dict]) -> Path:
            mapping = base / "mapping.json"
            duplicate = list(visuals)
            changed = dict(duplicate[0])
            changed["pictureMask"] += 1
            duplicate.insert(1, changed)
            write_json(mapping, {
                "schemaVersion": 1,
                "manifestType": "world-builder-item-visual-mapping",
                "itemVisuals": duplicate,
            })
            return mapping

        cases["duplicate"] = (contradictory, "DEFINITION_MISMATCH")

        def ambiguous_archive(base: Path, target: Path, visuals: list[dict]) -> Path:
            spritepack = target / "Client_Base/Cache/video/spritepacks/Menus.osar"
            spritepack.write_bytes(self.fixtures.fixture_osar([
                ("items", [("0", self.fixtures.fixture_sprite_entry())]),
                ("GUI", [("0", self.fixtures.fixture_sprite_entry())]),
            ]))
            mapping = base / "mapping.json"
            write_json(mapping, {
                "schemaVersion": 1,
                "manifestType": "world-builder-item-visual-mapping",
                "itemVisuals": visuals,
            })
            return mapping

        cases["archive-ambiguity"] = (ambiguous_archive, "role-ambiguous")

        def unsafe_path(base: Path, target: Path, visuals: list[dict]) -> Path:
            mapping = base / "mapping.json"
            changed = [dict(item) for item in visuals]
            changed[0]["customSpriteSubspace"] = "../items"
            write_json(mapping, {
                "schemaVersion": 1,
                "manifestType": "world-builder-item-visual-mapping",
                "itemVisuals": changed,
            })
            return mapping

        cases["unsafe-path"] = (unsafe_path, "UNSAFE_PATH")

        for name, (prepare, expected) in cases.items():
            with self.subTest(name=name), tempfile.TemporaryDirectory(
                prefix=f"adaptive-item-visual-{name}-"
            ) as temp:
                base = Path(temp)
                target = self.fixtures.legacy_fixture(str(base))
                evidence = target / "server/conf/world-builder/item-visuals-v1.json"
                visuals = json.loads(evidence.read_text(encoding="utf-8"))["itemVisuals"]
                evidence.unlink()
                mapping = prepare(base, target, visuals)
                installation = base / "World Builder 2"
                installation.mkdir()
                runtime = self.make_runtime(installation)
                report = base / "report.json"
                self.discover(target, report)
                before = tree_bytes(target)
                created = self.run_cli(
                    "create-project", "--installation-root", installation,
                    "--runtime-root", runtime, "--target-root", target,
                    "--discovery-report", report, "--display-name", "Bad mapping",
                    "--port", 43820, "--item-visual-mappings", mapping,
                    "--confirm", "CREATE",
                )
                self.assertEqual(3, created.returncode, created.stdout)
                self.assertIn(expected, created.stderr)
                self.assertEqual(before, tree_bytes(target))
                self.assertFalse(list((installation / "projects").glob("[0-9a-f]*")))

    def test_item_visual_mapping_creation_cancellation_publishes_nothing(self):
        with tempfile.TemporaryDirectory(prefix="adaptive-item-visual-cancel-") as temp:
            base = Path(temp)
            target = self.fixtures.legacy_fixture(str(base))
            evidence = target / "server/conf/world-builder/item-visuals-v1.json"
            visuals = json.loads(evidence.read_text(encoding="utf-8"))["itemVisuals"]
            evidence.unlink()
            mapping = base / "mapping.json"
            write_json(mapping, {
                "schemaVersion": 1,
                "manifestType": "world-builder-item-visual-mapping",
                "itemVisuals": visuals,
            })
            installation = base / "World Builder 2"
            installation.mkdir()
            runtime = self.make_runtime(installation)
            report = base / "report.json"
            self.discover(target, report)
            target_before = tree_bytes(target)
            mapping_before = mapping.read_bytes()
            cancelled = self.run_cli(
                "create-project", "--installation-root", installation,
                "--runtime-root", runtime, "--target-root", target,
                "--discovery-report", report, "--display-name", "Cancelled mapping",
                "--port", 43821, "--item-visual-mappings", mapping,
                "--confirm", "",
            )
            self.assertEqual(3, cancelled.returncode, cancelled.stdout)
            self.assertIn("exact CREATE confirmation", cancelled.stderr)
            self.assertEqual(target_before, tree_bytes(target))
            self.assertEqual(mapping_before, mapping.read_bytes())
            self.assertFalse((installation / "project-registry.json").exists())
            self.assertFalse(list((installation / "projects").glob("[0-9a-f]*")))

    def test_launcher_model_accepts_explicit_item_visual_mapping(self):
        with tempfile.TemporaryDirectory(prefix="adaptive-item-visual-model-") as temp:
            base = Path(temp)
            target = self.fixtures.legacy_fixture(str(base))
            evidence = target / "server/conf/world-builder/item-visuals-v1.json"
            visuals = json.loads(evidence.read_text(encoding="utf-8"))["itemVisuals"]
            evidence.unlink()
            server_terrain = target / "server/conf/server/data/Custom_Landscape.orsc"
            with zipfile.ZipFile(server_terrain, "w", zipfile.ZIP_DEFLATED) as archive:
                archive.writestr("h0x48y37", bytes(48 * 48 * 10))
            shutil.copy2(server_terrain,
                target / "Client_Base/Cache/video/Custom_Landscape.orsc")
            mapping = base / "mapping.json"
            write_json(mapping, {
                "schemaVersion": 1,
                "manifestType": "world-builder-item-visual-mapping",
                "itemVisuals": visuals,
            })
            installation = base / "World Builder 2"
            installation.mkdir()
            runtime = self.make_runtime(installation)
            marker = base / "model-created.txt"
            result = subprocess.run([
                "java", "-Djava.awt.headless=true", "-cp", str(self.classes),
                "com.openrsc.worldbuilder.DesktopLauncherHarness",
                str(installation), str(runtime), str(target), "43822",
                "MODEL_MAPPING", str(target), str(marker), str(mapping),
            ], cwd=ROOT, text=True, capture_output=True)
            self.assertEqual(0, result.returncode, result.stderr)
            project = Path(marker.read_text(encoding="utf-8").strip())
            self.assertTrue((project /
                "source/content-bundle/files/server/conf/world-builder/item-visuals-v1.json"
            ).is_file())

    def test_packaged_item_only_fallback_retains_bundle_v1_without_visual_evidence(self):
        with tempfile.TemporaryDirectory(prefix="adaptive-content-v1-compatible-") as temp:
            base = Path(temp)
            target = self.fixtures.legacy_fixture(str(base))
            definitions = target / "server/conf/server/defs"
            write_json(definitions / "ItemDefsCustom.json", {"items": [{"id": 42}]})
            write_json(definitions / "ItemDefsPatch18.json", {"items": []})
            write_json(definitions / "ItemDefsMyWorld.json", {"items": []})
            (target / "server/conf/world-builder/item-visuals-v1.json").unlink()
            server_terrain = target / "server/conf/server/data/Custom_Landscape.orsc"
            with zipfile.ZipFile(server_terrain, "w", zipfile.ZIP_DEFLATED) as archive:
                archive.writestr("h0x48y37", bytes(48 * 48 * 10))
            shutil.copy2(server_terrain,
                target / "Client_Base/Cache/video/Custom_Landscape.orsc")
            installation = base / "World Builder 2"
            installation.mkdir()
            runtime = self.make_runtime(installation)
            report = base / "report.json"
            self.discover(target, report)
            before = tree_bytes(target)
            created, summary = self.create_project(
                installation, runtime, target, report, "Bundle v1 compatible", 43817,
            )
            self.assertEqual(0, created.returncode, created.stderr)
            manifest = json.loads((Path(summary["projectRoot"]) /
                "source/content-bundle/manifest.json").read_text(encoding="utf-8"))
            self.assertEqual(1, manifest["schemaVersion"])
            self.assertEqual("project-local-custom-content-v1", manifest["capabilityId"])
            self.assertNotIn("itemVisuals", manifest)
            self.assertEqual(before, tree_bytes(target))

    def test_multiple_projects_selection_and_existing_project_preservation(self):
        with tempfile.TemporaryDirectory(prefix="adaptive-project-multiple-") as temp:
            base = Path(temp)
            installation = base / "World Builder 2"
            installation.mkdir()
            runtime = self.make_runtime(installation)
            targets = []
            summaries = []
            for index in range(2):
                target = base / f"empty-{index}"
                target.mkdir()
                report = base / f"report-{index}.json"
                self.discover(target, report)
                created, summary = self.create_project(
                    installation,
                    runtime,
                    target,
                    report,
                    f"Empty {index}",
                    43810 + index,
                )
                self.assertEqual(0, created.returncode, created.stderr)
                targets.append(target)
                summaries.append(summary)
            listed = self.run_cli(
                "list-projects", "--installation-root", installation
            )
            self.assertEqual(0, listed.returncode, listed.stderr)
            listing = json.loads(listed.stdout)
            self.assertEqual(2, len(listing["projects"]))
            self.assertEqual(summaries[1]["projectId"], listing["activeProjectId"])
            selected = self.run_cli(
                "select-project",
                "--installation-root",
                installation,
                "--project-id",
                summaries[0]["projectId"],
            )
            self.assertEqual(0, selected.returncode, selected.stderr)
            first = installation / "projects" / summaries[0]["projectId"]
            second = installation / "projects" / summaries[1]["projectId"]
            first_before = tree_bytes(first)
            second_before = tree_bytes(second)
            registry_before = (installation / "project-registry.json").read_bytes()
            active_before = (installation / "active-project.json").read_bytes()

            failed = self.run_injected(
                installation,
                runtime,
                targets[0],
                base / "report-0.json",
                "registry-published",
            )
            self.assertEqual(3, failed.returncode, failed.stderr)
            self.assertIn("MUTATION_FAILED", failed.stderr)
            self.assertEqual(first_before, tree_bytes(first))
            self.assertEqual(second_before, tree_bytes(second))
            self.assertEqual(registry_before, (installation / "project-registry.json").read_bytes())
            self.assertEqual(active_before, (installation / "active-project.json").read_bytes())
            self.assertFalse(list((installation / "projects").glob(".staging-*")))
            uuid_directories = [
                path
                for path in (installation / "projects").iterdir()
                if path.is_dir() and not path.name.startswith(".")
            ]
            self.assertEqual(2, len(uuid_directories))

    def test_all_publication_failures_leave_no_partial_project(self):
        with tempfile.TemporaryDirectory(prefix="adaptive-project-failures-") as temp:
            base = Path(temp)
            target = base / "empty-target"
            target.mkdir()
            report = base / "report.json"
            self.discover(target, report)
            for mode in (
                "stage-created",
                "source-prepared",
                "working-prepared",
                "before-project-publish",
                "project-published",
                "registry-published",
                "active-published",
            ):
                with self.subTest(mode=mode):
                    installation = base / f"install-{mode}"
                    installation.mkdir()
                    runtime = self.make_runtime(installation)
                    failed = self.run_injected(
                        installation, runtime, target, report, mode
                    )
                    self.assertEqual(3, failed.returncode, failed.stderr)
                    self.assertFalse((installation / "project-registry.json").exists())
                    self.assertFalse((installation / "active-project.json").exists())
                    projects = installation / "projects"
                    self.assertFalse(
                        [path for path in projects.iterdir() if path.is_dir()], mode
                    )
                    self.assertFalse(list(projects.glob(".staging-*")), mode)

    def test_desktop_launcher_choices_and_existing_project_start_are_headless_testable(self):
        launcher_source = (
            SOURCE_ROOT
            / "com/openrsc/worldbuilder/WorldBuilderDesktopLauncher.java"
        ).read_text(encoding="utf-8")
        self.assertNotIn("System.exit(", launcher_source)
        self.assertIn("Close the editor normally first", launcher_source)
        self.assertIn("Choose complete provider package…", launcher_source)
        self.assertIn("Advanced provider import…", launcher_source)

        with tempfile.TemporaryDirectory(prefix="adaptive-desktop-launcher-") as temp:
            base = Path(temp)
            installation = base / "World Builder 2"
            installation.mkdir()
            runtime = self.make_runtime(installation)
            detected = base / "ordinary-parent"
            detected.mkdir()
            marker = base / "runner.txt"
            detected_before = tree_bytes(detected)

            cancelled = self.run_desktop_launcher(
                installation, runtime, detected, 44100, "CANCEL", None, marker
            )
            self.assertEqual(0, cancelled.returncode, cancelled.stderr)
            self.assertFalse(marker.exists())
            self.assertFalse((installation / "project-registry.json").exists())
            self.assertEqual(detected_before, tree_bytes(detected))

            provider = base / "external/world-builder-provider"
            provider.mkdir(parents=True)
            full_mapping = provider / "item-visuals-full-v1.json"
            write_json(full_mapping, {
                "schemaVersion": 1,
                "manifestType": "world-builder-item-visual-mapping",
                "itemVisuals": [],
            })
            write_json(provider / "package-manifest-v1.json", {
                "schemaVersion": 1,
                "manifestType": "world-builder-item-visual-provider-package",
                "providerDirectory": "world-builder-provider",
                "catalogSha256": "a" * 64,
                "files": [{
                    "path": full_mapping.name,
                    "role": "full-item-visual-manifest",
                    "size": full_mapping.stat().st_size,
                    "sha256": hashlib.sha256(full_mapping.read_bytes()).hexdigest(),
                }],
            })
            package_marker = base / "package-selection.txt"
            package_selection = subprocess.run(
                [
                    "java", "-cp", str(self.classes),
                    "com.openrsc.worldbuilder.DesktopLauncherHarness",
                    str(installation), str(runtime), str(detected), "44100",
                    "PACKAGE_SELECTION", str(provider), str(package_marker),
                ],
                cwd=ROOT,
                text=True,
                capture_output=True,
            )
            self.assertEqual(0, package_selection.returncode, package_selection.stderr)
            imported_mapping = Path(
                package_marker.read_text(encoding="utf-8").strip()
            )
            self.assertEqual(full_mapping.name, imported_mapping.name)
            self.assertTrue((imported_mapping.parent / "package-manifest-v1.json").is_file())
            self.assertEqual(detected_before, tree_bytes(detected))

            created_empty = self.run_desktop_launcher(
                installation, runtime, detected, 44100, "NEW_EMPTY", None, marker
            )
            self.assertEqual(0, created_empty.returncode, created_empty.stderr)
            empty_project = Path(marker.read_text(encoding="utf-8").strip())
            empty_manifest = json.loads(
                (empty_project / "project.json").read_text(encoding="utf-8")
            )
            self.assertEqual("standalone-empty", empty_manifest["origin"])
            self.assertEqual(detected_before, tree_bytes(detected))
            self.assertFalse(list(installation.glob(".desktop-discovery-*")))

            marker.unlink()
            reopened = self.run_desktop_launcher(
                installation, runtime, detected, 44100,
                "OPEN_EXISTING", None, marker
            )
            self.assertEqual(0, reopened.returncode, reopened.stderr)
            self.assertEqual(empty_project, Path(marker.read_text().strip()))
            self.assertEqual(detected_before, tree_bytes(detected))

        with tempfile.TemporaryDirectory(prefix="adaptive-desktop-detected-") as temp:
            base = Path(temp)
            detected = self.fixtures.descriptor_fixture(
                str(base), world_space="global"
            )
            installation = detected / "World Builder 2"
            installation.mkdir()
            runtime = self.make_runtime(installation)
            marker = base / "detected-runner.txt"
            target_before = tree_bytes(detected, installation)
            created = self.run_desktop_launcher(
                installation, runtime, detected, 44101,
                "DETECTED_SERVER", None, marker
            )
            self.assertEqual(0, created.returncode, created.stderr)
            project = Path(marker.read_text().strip())
            self.assertEqual(
                "target-layered",
                json.loads((project / "project.json").read_text())["origin"],
            )
            self.assertEqual(target_before, tree_bytes(detected, installation))

        with tempfile.TemporaryDirectory(prefix="adaptive-desktop-selected-") as temp:
            base = Path(temp)
            installation = base / "World Builder 2"
            installation.mkdir()
            runtime = self.make_runtime(installation)
            detected = base / "no-server"
            detected.mkdir()
            selected = self.fixtures.descriptor_fixture(
                str(base / "selected"), world_space="global"
            )
            marker = base / "selected-runner.txt"
            detected_before = tree_bytes(detected)
            selected_before = tree_bytes(selected)
            created = self.run_desktop_launcher(
                installation, runtime, detected, 44102,
                "SELECT_SOURCE", selected, marker
            )
            self.assertEqual(0, created.returncode, created.stderr)
            selected_project = Path(marker.read_text(encoding="utf-8").strip())

            marker.unlink()
            reopened = self.run_desktop_launcher(
                installation, runtime, detected, 44102,
                "OPEN_EXISTING", None, marker
            )
            self.assertEqual(0, reopened.returncode, reopened.stderr)
            self.assertEqual(
                selected_project,
                Path(marker.read_text(encoding="utf-8").strip()),
            )
            self.assertEqual(detected_before, tree_bytes(detected))
            self.assertEqual(selected_before, tree_bytes(selected))

    def test_adaptive_launch_creates_once_and_reopens_active_project(self):
        with tempfile.TemporaryDirectory(prefix="adaptive-project-launch-") as temp:
            base = Path(temp)
            installation = base / "World Builder 2"
            installation.mkdir()
            runtime = self.make_executable_runtime(installation)
            target = base / "ordinary-parent"
            target.mkdir()
            target_before = tree_bytes(target)
            arguments = (
                "launch-adaptive",
                "--installation-root",
                installation,
                "--runtime-root",
                runtime,
                "--target-root",
                target,
                "--port",
                "43831",
            )

            cancelled = subprocess.run(
                ["java", "-cp", str(self.classes), MAIN_CLASS, *map(str, arguments)],
                cwd=ROOT,
                input="\n",
                text=True,
                capture_output=True,
            )
            self.assertEqual(0, cancelled.returncode, cancelled.stderr)
            self.assertIn("creation cancelled", cancelled.stdout)
            self.assertFalse((installation / "project-registry.json").exists())
            self.assertEqual(target_before, tree_bytes(target))

            first = self.run_cli(*arguments, "--confirm", "CREATE")
            self.assertEqual(0, first.returncode, first.stderr)
            listing = self.run_cli(
                "list-projects", "--installation-root", installation
            )
            self.assertEqual(0, listing.returncode, listing.stderr)
            projects = json.loads(listing.stdout)["projects"]
            self.assertEqual(1, len(projects))
            first_project = projects[0]["projectId"]
            project = installation / "projects" / first_project
            generated_keys = {
                "client.pem": b"fixture client key\n",
                "server.pem": b"fixture server key\n",
            }
            for name, expected in generated_keys.items():
                self.assertEqual(
                    expected,
                    (project / "working/runtime/server" / name).read_bytes(),
                )
                self.assertFalse((runtime / "server" / name).exists())
            self.assertFalse(list(installation.glob(".adaptive-discovery-*")))
            self.assertEqual(target_before, tree_bytes(target))

            reopened = self.run_cli(*arguments)
            self.assertEqual(0, reopened.returncode, reopened.stderr)
            for name, expected in generated_keys.items():
                self.assertEqual(
                    expected,
                    (project / "working/runtime/server" / name).read_bytes(),
                )
            listing = self.run_cli(
                "list-projects", "--installation-root", installation
            )
            projects = json.loads(listing.stdout)["projects"]
            self.assertEqual([first_project], [project["projectId"] for project in projects])
            self.assertEqual(target_before, tree_bytes(target))

    def test_adaptive_supervision_rejects_linked_or_shared_mutable_state(self):
        with tempfile.TemporaryDirectory(prefix="adaptive-project-runtime-paths-") as temp:
            base = Path(temp)
            installation = base / "World Builder 2"
            installation.mkdir()
            runtime = self.make_runtime(installation)
            target = base / "ordinary-parent"
            target.mkdir()
            report = base / "report.json"
            self.discover(target, report)
            created, summary = self.create_project(
                installation, runtime, target, report, "Unsafe runtime fixture", 43832
            )
            self.assertEqual(0, created.returncode, created.stderr)
            project = Path(summary["projectRoot"])
            source_before = tree_bytes(project / "source")
            target_before = tree_bytes(target)

            external = base / "must-not-be-runtime"
            external.mkdir()
            (external / "preserve.txt").write_text("preserve\n", encoding="utf-8")
            external_before = tree_bytes(external)
            server = project / "working/runtime/server"
            preserved_server = base / "preserved-project-server"
            shutil.move(server, preserved_server)
            server.symlink_to(external, target_is_directory=True)
            linked = self.run_supervision(project, "unsafe")
            self.assertEqual(0, linked.returncode, linked.stdout + linked.stderr)
            self.assertEqual(external_before, tree_bytes(external))
            self.assertEqual(target_before, tree_bytes(target))
            self.assertEqual(source_before, tree_bytes(project / "source"))
            self.assertFalse((project / "run/world-builder.lock").exists())

            server.unlink()
            shutil.move(preserved_server, server)
            shared = external / "shared.log"
            shared.write_text("shared\n", encoding="utf-8")
            os.link(shared, project / "logs/shared.log")
            shared_before = tree_bytes(external)
            hard_linked = self.run_supervision(project, "unsafe")
            self.assertEqual(
                0, hard_linked.returncode, hard_linked.stdout + hard_linked.stderr
            )
            self.assertEqual(shared_before, tree_bytes(external))
            self.assertEqual(target_before, tree_bytes(target))
            self.assertEqual(source_before, tree_bytes(project / "source"))
            self.assertFalse((project / "run/world-builder.lock").exists())

            (project / "logs/shared.log").unlink()
            linked_key = server / "client.pem"
            linked_key.symlink_to(external / "preserve.txt")
            refused = self.run_cli(
                "open-project",
                "--installation-root",
                installation,
                "--target-root",
                target,
                "--validate-only",
            )
            self.assertEqual(3, refused.returncode, refused.stderr)
            self.assertIn("SOURCE_CORRUPT", refused.stderr)
            self.assertIn("working/runtime/server/client.pem", refused.stderr)
            self.assertEqual(shared_before, tree_bytes(external))
            self.assertEqual(target_before, tree_bytes(target))
            self.assertEqual(source_before, tree_bytes(project / "source"))

    def test_adaptive_supervision_failure_cleanup_never_saves_or_touches_target(self):
        with tempfile.TemporaryDirectory(prefix="adaptive-project-runtime-failures-") as temp:
            base = Path(temp)
            installation = base / "World Builder 2"
            installation.mkdir()
            runtime = self.make_runtime(installation)
            target = base / "ordinary-parent"
            target.mkdir()
            report = base / "report.json"
            self.discover(target, report)
            target_before = tree_bytes(target)
            created, summary = self.create_project(
                installation, runtime, target, report, "Failure cleanup", 43833
            )
            self.assertEqual(0, created.returncode, created.stderr)
            project = Path(summary["projectRoot"])
            source_before = tree_bytes(project / "source")
            baseline_before = tree_bytes(
                project / "source/layered-baseline/package"
            )
            failed = self.run_supervision(project, "failures")
            self.assertEqual(0, failed.returncode, failed.stdout + failed.stderr)
            self.assertEqual(
                "adaptive-supervision-failures-ok\n", failed.stdout
            )
            reopened = self.run_cli(
                "open-project",
                "--installation-root",
                installation,
                "--target-root",
                target,
            )
            self.assertEqual(0, reopened.returncode, reopened.stderr)
            self.assertEqual(target_before, tree_bytes(target))
            self.assertEqual(source_before, tree_bytes(project / "source"))
            self.assertEqual(
                baseline_before,
                tree_bytes(project / "source/layered-baseline/package"),
            )

    def test_runtime_capability_mismatches_fail_before_project_publication(self):
        mutations = {
            "definition-contract": lambda value: value.__setitem__(
                "definitionContractId", "wrong-definition-contract-v1"
            ),
            "placement-order": lambda value: value["authoring"].__setitem__(
                "placementFamilies", ["scenery", "npc", "ground-item", "boundary"]
            ),
            "non-loopback": lambda value: value["activation"].__setitem__(
                "loopbackOnly", False
            ),
            "wrong-void": lambda value: value.__setitem__(
                "canonicalVoidTile", [0] * 10
            ),
            "unexpected-field": lambda value: value.__setitem__(
                "unexpected", True
            ),
        }
        with tempfile.TemporaryDirectory(prefix="adaptive-runtime-capability-") as temp:
            base = Path(temp)
            target = base / "ordinary-parent"
            target.mkdir()
            report = base / "report.json"
            self.discover(target, report)
            target_before = tree_bytes(target)
            for index, (label, mutate) in enumerate(mutations.items()):
                with self.subTest(label=label):
                    installation = base / label / "World Builder 2"
                    installation.mkdir(parents=True)
                    runtime = self.make_runtime(installation)
                    capability_path = (
                        runtime
                        / "server/conf/world-builder/"
                        "adaptive-runtime-capability-v2.json"
                    )
                    capability = json.loads(
                        capability_path.read_text(encoding="utf-8")
                    )
                    mutate(capability)
                    write_json(capability_path, capability)
                    runtime_before = tree_bytes(runtime)
                    refused, _ = self.create_project(
                        installation,
                        runtime,
                        target,
                        report,
                        f"Rejected {label}",
                        43900 + index,
                    )
                    self.assertEqual(3, refused.returncode, refused.stderr)
                    self.assertIn("LOADER_INCOMPATIBLE", refused.stderr)
                    self.assertFalse(
                        (installation / "project-registry.json").exists()
                    )
                    self.assertFalse(
                        list((installation / "projects").glob(".staging-*"))
                    )
                    self.assertEqual(runtime_before, tree_bytes(runtime))
                    self.assertEqual(target_before, tree_bytes(target))

    def test_unbound_project_runtime_entry_refuses_reopen(self):
        with tempfile.TemporaryDirectory(prefix="adaptive-runtime-closure-") as temp:
            base = Path(temp)
            installation = base / "World Builder 2"
            installation.mkdir()
            runtime = self.make_runtime(installation)
            target = base / "ordinary-parent"
            target.mkdir()
            report = base / "report.json"
            self.discover(target, report)
            target_before = tree_bytes(target)
            created, summary = self.create_project(
                installation, runtime, target, report, "Runtime closure", 43834
            )
            self.assertEqual(0, created.returncode, created.stderr)
            project = Path(summary["projectRoot"])
            source_before = tree_bytes(project / "source")
            injected = project / "working/runtime/server/lib/injected.jar"
            injected.parent.mkdir(parents=True, exist_ok=True)
            injected.write_bytes(b"unbound runtime entry\n")
            refused = self.run_cli(
                "open-project",
                "--installation-root",
                installation,
                "--target-root",
                target,
                "--validate-only",
            )
            self.assertEqual(3, refused.returncode, refused.stderr)
            self.assertIn("SOURCE_CORRUPT", refused.stderr)
            self.assertIn("working/runtime/server/lib/injected.jar", refused.stderr)
            self.assertEqual(target_before, tree_bytes(target))
            self.assertEqual(source_before, tree_bytes(project / "source"))

    def test_empty_origin_is_deterministic_across_absolute_roots(self):
        generated = []
        with tempfile.TemporaryDirectory(prefix="adaptive-empty-portable-a-") as first:
            with tempfile.TemporaryDirectory(
                prefix="adaptive-empty-portable-b-"
            ) as second:
                for index, location in enumerate((first, second)):
                    base = Path(location)
                    installation = base / "World Builder 2"
                    installation.mkdir()
                    runtime = self.make_runtime(installation)
                    target = base / "ordinary-parent"
                    target.mkdir()
                    report = base / "report.json"
                    self.discover(target, report)
                    created, summary = self.create_project(
                        installation,
                        runtime,
                        target,
                        report,
                        "Portable empty",
                        43840 + index,
                    )
                    self.assertEqual(0, created.returncode, created.stderr)
                    project = Path(summary["projectRoot"])
                    package = project / "source/layered-baseline/package"
                    self.assert_canonical_empty_package(package)
                    generated.append(
                        {
                            "package": tree_bytes(package),
                            "descriptor": (
                                project / "source/original/empty-world-v1.json"
                            ).read_bytes(),
                            "catalog": (
                                project
                                / "source/runtime/default-definition-catalog.json"
                            ).read_bytes(),
                            "runtime": (
                                project
                                / "source/runtime/default-runtime-evidence.json"
                            ).read_bytes(),
                        }
                    )
                    for path in package.rglob("*"):
                        if path.is_file():
                            self.assertNotIn(b"Spoiled Milk", path.read_bytes())
                self.assertEqual(generated[0], generated[1])

    def test_mid_creation_drift_and_source_corruption_fail_closed(self):
        with tempfile.TemporaryDirectory(prefix="adaptive-project-drift-") as temp:
            base = Path(temp)
            target = self.fixtures.descriptor_fixture(str(base))
            report = base / "report.json"
            self.discover(target, report)
            installation = base / "World Builder 2"
            installation.mkdir()
            runtime = self.make_runtime(installation)
            failed = self.run_injected(
                installation, runtime, target, report, "drift-source-prepared"
            )
            self.assertEqual(3, failed.returncode, failed.stderr)
            self.assertIn("TARGET_DRIFT", failed.stderr)
            self.assertFalse((installation / "project-registry.json").exists())
            self.assertFalse(list((installation / "projects").glob(".staging-*")))

            refreshed = base / "refreshed-report.json"
            self.discover(target, refreshed)
            created, summary = self.create_project(
                installation, runtime, target, refreshed, "Corruption fixture", 43822
            )
            self.assertEqual(0, created.returncode, created.stderr)
            project = Path(summary["projectRoot"])
            source_file = next(
                path
                for path in (project / "source/original").rglob("*")
                if path.is_file()
            )
            source_file.write_bytes(source_file.read_bytes() + b"corrupt")
            opened = self.run_cli(
                "open-project", "--installation-root", installation, "--target-root", target
            )
            self.assertEqual(3, opened.returncode)
            self.assertIn("SOURCE_CORRUPT", opened.stderr)


if __name__ == "__main__":
    unittest.main()
