#!/usr/bin/env python3
"""Focused immutable-evidence tests for the legacy landscape choice producer."""

from __future__ import annotations

import copy
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
import zipfile

import adaptive_project_test_support as project_support


ROOT = Path(__file__).resolve().parents[2]
SOURCE_ROOT = ROOT / "tools" / "world-builder" / "src"
CONTRACT_FIXTURES = ROOT / "tests" / "myworld" / "test-world-builder-adaptive-contracts.py"
DISCOVERY_FIXTURES = ROOT / "tests" / "myworld" / "test-world-builder-adaptive-discovery.py"
LIFECYCLE_FIXTURES = ROOT / "tests" / "myworld" / "test-world-builder-adaptive-project-lifecycle.py"
PACKED_FIXTURES = ROOT / "tests" / "myworld" / "test-world-builder-packed-conversion.py"
ZERO_HASH = "0" * 64


def write_runtime_jar(path: Path, payload: bytes) -> None:
    with zipfile.ZipFile(path, "w") as archive:
        archive.writestr("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\n\n")
        archive.writestr("target/RuntimeMarker.class", payload)
        if path.name == "core.jar":
            for entry, marker in (
                project_support.FIXTURE_HOST_SERVER_ARCHIVE_ENTRIES.items()
            ):
                archive.writestr(entry, marker)
        else:
            for entry, marker in (
                project_support.FIXTURE_HOST_CLIENT_ARCHIVE_ENTRIES.items()
            ):
                archive.writestr(entry, marker)


def add_client_upgrade_source(client_root: Path) -> None:
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
    for _, destination, _, policy, historical in project_support.FIXTURE_CLIENT_SOURCES:
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


def add_current_host_capability(target: Path) -> None:
    project_support.write_json(
        target / "server/conf/world-builder/installed-runtime-capability-v3.json",
        project_support.host_runtime_capability(),
    )
    source = target / "server/src/com/openrsc/server/net/RSCProtocolDecoder.java"
    source.parent.mkdir(parents=True, exist_ok=True)
    source.write_bytes(project_support.FIXTURE_HOST_DECODER_SOURCE)
    (target / "server/build.xml").write_text(
        project_support.FIXTURE_PINNED_SERVER_BUILD, encoding="utf-8"
    )

HARNESS = r"""
package com.openrsc.worldbuilder;

import java.nio.file.Paths;

public final class MapMigrationChoiceHarness {
    public static void main(String[] arguments) throws Exception {
        try {
            WorldBuilderMapMigrationChoice choice = "keep".equals(arguments[3])
                ? WorldBuilderMapMigrationChoice.keepLayered(
                    Paths.get(arguments[0]), Paths.get(arguments[1]),
                    Boolean.parseBoolean(arguments[2]))
                : WorldBuilderMapMigrationChoice.create(
                    Paths.get(arguments[0]), Paths.get(arguments[1]),
                    Boolean.parseBoolean(arguments[2]));
            System.out.print(choice.toJson());
        } catch (WorldBuilderContractException refusal) {
            System.err.println(refusal.code() + "|" + refusal.operation() + "|"
                + refusal.mutationOccurred() + "|" + refusal.nextStep() + "|"
                + refusal.getMessage());
            System.exit(3);
        }
    }
}
"""

ASSESSMENT_HARNESS = r"""
package com.openrsc.worldbuilder;

import java.nio.file.Paths;

public final class LegacyLandscapeAssessmentHarness {
    public static void main(String[] arguments) throws Exception {
        WorldBuilderAdaptiveDiscoveryReport selected =
            new WorldBuilderAdaptiveDiscovery().discover(
                Paths.get(arguments[0]), arguments[1]);
        WorldBuilderAdaptiveDiscoveryReport legacy =
            new WorldBuilderLegacyLandscapeDiscovery().discover(
                Paths.get(arguments[0]), null);
        WorldBuilderLegacyLandscapeAssessment assessment =
            WorldBuilderLegacyLandscapeAssessment.inspect(
                Paths.get(arguments[0]), selected, legacy);
        System.out.print(WorldBuilderJsonDocuments.pretty(assessment.document()));
    }
}
"""

DISCOVERY_HARNESS = r"""
package com.openrsc.worldbuilder;

import java.nio.file.Paths;

public final class LegacyLandscapeDiscoveryHarness {
    public static void main(String[] arguments) throws Exception {
        try {
            WorldBuilderAdaptiveDiscoveryReport report =
                new WorldBuilderLegacyLandscapeDiscovery().discover(
                    Paths.get(arguments[0]),
                    arguments.length > 1 ? arguments[1] : null);
            System.out.print(report.toJson());
        } catch (WorldBuilderContractException refusal) {
            System.err.println(refusal.code() + "|" + refusal.operation() + "|"
                + refusal.mutationOccurred() + "|" + refusal.nextStep() + "|"
                + refusal.getMessage());
            System.exit(3);
        }
    }
}
"""

SCRIPTED_LAUNCHER_HARNESS = r"""
package com.openrsc.worldbuilder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

public final class ScriptedMigrationLauncherHarness {
    public static void main(String[] arguments) throws Exception {
        final Path marker = Paths.get(arguments[4]);
        WorldBuilderDesktopLauncher.Ui ui = new WorldBuilderDesktopLauncher.Ui() {
            @Override public WorldBuilderDesktopLauncher.Action chooseAction(
                List<WorldBuilderDesktopLauncher.ProjectChoice> projects,
                String summary, boolean supported) {
                if (!supported) throw new AssertionError(summary);
                return WorldBuilderDesktopLauncher.Action.DETECTED_SERVER;
            }
            @Override public WorldBuilderDesktopLauncher.ProjectChoice chooseProject(
                List<WorldBuilderDesktopLauncher.ProjectChoice> projects) { return null; }
            @Override public Path chooseSource(Path initial) { return null; }
            @Override public String requestDisplayName(String suggested) {
                return "Scripted migrated project";
            }
            @Override public boolean confirmCreation(String title, String summary) {
                return true;
            }
            @Override public boolean confirmLegacyLandscapeIncorporation() {
                return arguments.length < 6
                    || Boolean.parseBoolean(arguments[5]);
            }
            @Override public void showError(String title, String message) {
                throw new AssertionError(title + ": " + message);
            }
        };
        WorldBuilderDesktopLauncher.ProjectRunner runner =
            new WorldBuilderDesktopLauncher.ProjectRunner() {
                @Override public int run(Path project) throws Exception {
                    Files.write(marker, project.toRealPath().toString()
                        .getBytes(StandardCharsets.UTF_8));
                    return 0;
                }
            };
        int result = new WorldBuilderDesktopLauncher(ui, runner).run(
            new WorldBuilderDesktopLauncher.Options(
                Paths.get(arguments[0]), Paths.get(arguments[1]),
                Paths.get(arguments[2]), null, Integer.parseInt(arguments[3])));
        System.exit(result);
    }
}
"""

LAUNCHER_TRANSACTION_HARNESS = r"""
package com.openrsc.worldbuilder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public final class LauncherMigrationTransactionHarness {
    public static void main(String[] arguments) throws Exception {
        WorldBuilderLauncherModel model = new WorldBuilderLauncherModel(
            Paths.get(arguments[0]), Paths.get(arguments[1]),
            Paths.get(arguments[2]), Integer.parseInt(arguments[4]), null);
        WorldBuilderLauncherModel.ProjectEntry selected = null;
        for (WorldBuilderLauncherModel.ProjectEntry entry : model.projects()) {
            if (entry.projectId.equals(arguments[3])) selected = entry;
        }
        if (selected == null) throw new AssertionError("project not found");
        WorldBuilderLauncherModel.PreparedImport prepared =
            model.prepareServerImport(selected);
        Files.write(Paths.get(arguments[5]),
            prepared.preview.toJson().getBytes(StandardCharsets.UTF_8));
        boolean retirementSummary = prepared.summary().contains(
            "Legacy Custom_Landscape retirement: 2 exact files");
        if (retirementSummary) throw new AssertionError(prepared.summary());
        System.out.println(model.applyServerImport(prepared));
        Path target = Paths.get(arguments[2]);
        boolean serverLegacyPresent = Files.isRegularFile(target.resolve(
            "server/conf/server/data/Custom_Landscape.orsc"));
        boolean clientLegacyPresent = Files.isRegularFile(target.resolve(
            "Client_Base/Cache/video/Custom_Landscape.orsc"));
        if (!serverLegacyPresent || !clientLegacyPresent) throw new AssertionError(
            "legacy landscape was not preserved");
        if (!Files.isRegularFile(target.resolve(
                "client/world-builder-configs/installed-client.json"))) {
            throw new AssertionError("client activation profile was not installed");
        }
        if (!Files.isRegularFile(target.resolve(
                "server/world-builder-configs/installed-server.json"))) {
            throw new AssertionError("server activation profile was not installed");
        }
    }
}
"""

LAYERED_BASE_DISCOVERY_HARNESS = r"""
package com.openrsc.worldbuilder;

import java.nio.file.Paths;

public final class LayeredBaseDiscoveryHarness {
    public static void main(String[] arguments) throws Exception {
        WorldBuilderLayeredBaseDiscovery.Discovery discovery =
            new WorldBuilderLayeredBaseDiscovery().discover(
                Paths.get(arguments[0]), arguments.length > 1 ? arguments[1] : null);
        for (WorldBuilderLayeredBaseDiscovery.Candidate candidate
                : discovery.candidates) {
            System.out.println(candidate.packageRoot + "|" + candidate.configuration
                + "|" + candidate.manifestSha256);
        }
    }
}
"""


def load_fixtures(name: str, path: Path):
    spec = importlib.util.spec_from_file_location(name, path)
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


FIXTURES = load_fixtures("adaptive_contract_fixtures", CONTRACT_FIXTURES)
DISCOVERY = load_fixtures("adaptive_discovery_fixtures", DISCOVERY_FIXTURES)
LIFECYCLE = load_fixtures("adaptive_lifecycle_fixtures", LIFECYCLE_FIXTURES)
PACKED = load_fixtures("packed_conversion_fixtures", PACKED_FIXTURES)


def bind_report(report: dict) -> None:
    display = report["targetRootDisplay"]
    report["targetRootDisplay"] = ""
    report["discoveryFingerprintSha256"] = ZERO_HASH
    canonical = json.dumps(
        report, ensure_ascii=False, separators=(",", ":"), sort_keys=True
    ).encode("utf-8")
    report["targetRootDisplay"] = display
    report["discoveryFingerprintSha256"] = hashlib.sha256(canonical).hexdigest()


def reports() -> tuple[dict, dict]:
    selected = FIXTURES.layered_discovery()
    legacy = FIXTURES.packed_discovery()
    legacy["files"] = [
        FIXTURES.file_record(
            "client-terrain",
            "Client_Base/Cache/video/Custom_Landscape.orsc",
            "e" * 64,
            4096,
        ),
        FIXTURES.file_record(
            "server-terrain",
            "server/conf/server/data/Custom_Landscape.orsc",
            "e" * 64,
            4096,
        ),
    ]
    bind_report(selected)
    bind_report(legacy)
    return selected, legacy


class MapMigrationChoiceTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.compile_temp = tempfile.TemporaryDirectory(
            prefix="world-builder-map-migration-choice-classes-"
        )
        cls.classes = Path(cls.compile_temp.name)
        sources = sorted(str(path) for path in SOURCE_ROOT.rglob("*.java"))
        subprocess.run(
            ["javac", "-source", "8", "-target", "8", "-d", str(cls.classes), *sources],
            check=True,
            cwd=ROOT,
            capture_output=True,
        )
        layered_base_harness = (
            cls.classes
            / "harness/com/openrsc/worldbuilder/LayeredBaseDiscoveryHarness.java"
        )
        layered_base_harness.parent.mkdir(parents=True, exist_ok=True)
        layered_base_harness.write_text(
            LAYERED_BASE_DISCOVERY_HARNESS, encoding="utf-8"
        )
        subprocess.run(
            [
                "javac", "-source", "8", "-target", "8", "-cp", str(cls.classes),
                "-d", str(cls.classes), str(layered_base_harness),
            ],
            check=True,
            cwd=ROOT,
            capture_output=True,
        )
        transaction_harness = (
            cls.classes
            / "harness/com/openrsc/worldbuilder/LauncherMigrationTransactionHarness.java"
        )
        transaction_harness.parent.mkdir(parents=True, exist_ok=True)
        transaction_harness.write_text(LAUNCHER_TRANSACTION_HARNESS, encoding="utf-8")
        subprocess.run(
            [
                "javac", "-source", "8", "-target", "8", "-cp", str(cls.classes),
                "-d", str(cls.classes), str(transaction_harness),
            ],
            check=True,
            cwd=ROOT,
            capture_output=True,
        )
        launcher_harness = (
            cls.classes / "harness/com/openrsc/worldbuilder/ScriptedMigrationLauncherHarness.java"
        )
        launcher_harness.parent.mkdir(parents=True, exist_ok=True)
        launcher_harness.write_text(SCRIPTED_LAUNCHER_HARNESS, encoding="utf-8")
        subprocess.run(
            [
                "javac", "-source", "8", "-target", "8", "-cp", str(cls.classes),
                "-d", str(cls.classes), str(launcher_harness),
            ],
            check=True,
            cwd=ROOT,
            capture_output=True,
        )
        allowlist_resource = cls.classes / LIFECYCLE.RUNTIME_ALLOWLIST_RESOURCE
        allowlist_resource.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(LIFECYCLE.RUNTIME_ALLOWLIST, allowlist_resource)
        discovery_harness = (
            cls.classes / "harness/com/openrsc/worldbuilder/LegacyLandscapeDiscoveryHarness.java"
        )
        discovery_harness.parent.mkdir(parents=True, exist_ok=True)
        discovery_harness.write_text(DISCOVERY_HARNESS, encoding="utf-8")
        subprocess.run(
            [
                "javac", "-source", "8", "-target", "8", "-cp", str(cls.classes),
                "-d", str(cls.classes), str(discovery_harness),
            ],
            check=True,
            cwd=ROOT,
            capture_output=True,
        )
        harness = cls.classes / "harness/com/openrsc/worldbuilder/MapMigrationChoiceHarness.java"
        harness.parent.mkdir(parents=True, exist_ok=True)
        harness.write_text(HARNESS, encoding="utf-8")
        subprocess.run(
            [
                "javac", "-source", "8", "-target", "8", "-cp", str(cls.classes),
                "-d", str(cls.classes), str(harness),
            ],
            check=True,
            cwd=ROOT,
            capture_output=True,
        )
        assessment_harness = (
            cls.classes
            / "harness/com/openrsc/worldbuilder/LegacyLandscapeAssessmentHarness.java"
        )
        assessment_harness.write_text(ASSESSMENT_HARNESS, encoding="utf-8")
        subprocess.run(
            [
                "javac", "-source", "8", "-target", "8", "-cp", str(cls.classes),
                "-d", str(cls.classes), str(assessment_harness),
            ],
            check=True,
            cwd=ROOT,
            capture_output=True,
        )

    @classmethod
    def tearDownClass(cls) -> None:
        cls.compile_temp.cleanup()

    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory(prefix="world-builder-map-migration-choice-")
        self.root = Path(self.temp.name)

    def tearDown(self) -> None:
        self.temp.cleanup()

    def run_choice(
        self, selected: dict, legacy: dict, retirement: bool = True,
        decision: str = "incorporate",
    ) -> tuple[subprocess.CompletedProcess[str], bytes, bytes]:
        selected_path = self.root / "selected.json"
        legacy_path = self.root / "legacy.json"
        selected_path.write_text(json.dumps(selected, indent=2) + "\n", encoding="utf-8")
        legacy_path.write_text(json.dumps(legacy, indent=2) + "\n", encoding="utf-8")
        selected_before = selected_path.read_bytes()
        legacy_before = legacy_path.read_bytes()
        result = subprocess.run(
            [
                "java", "-cp", str(self.classes),
                "com.openrsc.worldbuilder.MapMigrationChoiceHarness",
                str(selected_path), str(legacy_path), str(retirement).lower(),
                "keep" if decision == "keep" else "incorporate",
            ],
            cwd=ROOT,
            text=True,
            capture_output=True,
        )
        return result, selected_before, legacy_before

    def run_assessment(
        self, target: Path, selected_role: str = "primary"
    ) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            [
                "java", "-cp", str(self.classes),
                "com.openrsc.worldbuilder.LegacyLandscapeAssessmentHarness",
                str(target), selected_role,
            ],
            cwd=ROOT,
            text=True,
            capture_output=True,
        )

    def legacy_target(self, name: str = "fixture") -> Path:
        fixture = DISCOVERY.AdaptiveDiscoveryTest(
            methodName="test_narrow_legacy_fallback_probe_remains_read_only"
        )
        return fixture.legacy_fixture(str(self.root / name))

    def run_legacy_discovery(
        self, target: Path, *extra: str
    ) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            [
                "java", "-cp", str(self.classes),
                "com.openrsc.worldbuilder.LegacyLandscapeDiscoveryHarness",
                str(target), *extra,
            ],
            cwd=ROOT,
            text=True,
            capture_output=True,
        )

    def run_cli(self, *arguments: object) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            [
                "java", "-cp", str(self.classes),
                "com.openrsc.worldbuilder.WorldBuilderCli",
                *map(str, arguments),
            ],
            cwd=ROOT,
            text=True,
            capture_output=True,
        )

    def discover_layered_bases(
        self, target: Path, configuration: str = "server/conf/world-builder.conf",
        data_home: Path | None = None,
    ) -> subprocess.CompletedProcess[str]:
        environment = os.environ.copy()
        environment["XDG_DATA_HOME"] = str(data_home or (self.root / "empty-xdg"))
        return subprocess.run(
            [
                "java", "-cp", str(self.classes),
                "com.openrsc.worldbuilder.LayeredBaseDiscoveryHarness",
                str(target), configuration,
            ],
            cwd=ROOT,
            text=True,
            capture_output=True,
            env=environment,
        )

    def write_layered_launch_marker(
        self,
        target: Path,
        package: Path,
        port: int,
        configuration: str = "world-builder.conf",
        manifest_hash: str | None = None,
    ) -> Path:
        manifest_hash = manifest_hash or hashlib.sha256(
            (package / "manifest.json").read_bytes()
        ).hexdigest()
        marker = target / f"server/run/server-{port}.env"
        marker.parent.mkdir(parents=True, exist_ok=True)
        escaped_package = str(package.resolve()).replace(" ", "\\ ")
        marker.write_text(
            "\n".join(
                [
                    f"marker_root={target.resolve()}",
                    f"marker_config={configuration}",
                    f"marker_layered_package_path={escaped_package}",
                    f"marker_layered_manifest_sha256={manifest_hash}",
                    "",
                ]
            ),
            encoding="utf-8",
        )
        return marker

    def add_layered_authority(
        self, target: Path, catalog: dict, world_space: str = "creator-space"
    ) -> None:
        server_catalog = target / "server/evidence/definitions.json"
        client_catalog = target / "client/evidence/definitions.json"
        server_catalog.parent.mkdir(parents=True, exist_ok=True)
        client_catalog.parent.mkdir(parents=True, exist_ok=True)
        server_catalog.write_text(json.dumps(catalog, indent=2) + "\n", encoding="utf-8")
        shutil.copy2(server_catalog, client_catalog)
        catalog_hash = hashlib.sha256(server_catalog.read_bytes()).hexdigest()

        server_asset = target / "server/evidence/render-assets.bin"
        client_asset = target / "client/evidence/render-assets.bin"
        server_asset.parent.mkdir(parents=True, exist_ok=True)
        client_asset.parent.mkdir(parents=True, exist_ok=True)
        server_asset.write_bytes(b"migration fixture render assets\n")
        shutil.copy2(server_asset, client_asset)

        fixture = DISCOVERY.AdaptiveDiscoveryTest(
            methodName="test_descriptor_layered_map_is_generic_complete_and_read_only"
        )
        server_package = target / "server/maps/active"
        fixture.write_package(
            server_package, terrain_seed=91, scenery_id=1,
            world_space=world_space,
        )
        placements_path = server_package / "placements/creator/lp0.json"
        placements = json.loads(placements_path.read_text(encoding="utf-8"))
        placements["boundaries"][0]["boundaryId"] = 1
        placements["groundItems"][0]["itemId"] = 7
        placements["npcs"][0]["npcId"] = 1
        placements_path.write_text(json.dumps(placements, indent=2) + "\n", encoding="utf-8")
        manifest_path = server_package / "manifest.json"
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        manifest["placementSets"][0]["sha256"] = hashlib.sha256(
            placements_path.read_bytes()
        ).hexdigest()
        manifest_path.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
        client_package = target / "client/maps/active"
        client_package.parent.mkdir(parents=True, exist_ok=True)
        shutil.copytree(server_package, client_package)

        authoring = {
            "editExistingLevels": True,
            "createLevels": True,
            "placementFamilies": ["boundary", "ground-item", "npc", "scenery"],
        }
        for side, build in (("server", "migration-server-v1"), ("client", "migration-client-v1")):
            runtime = {
                "schemaVersion": 1,
                "manifestType": "world-builder-runtime-evidence",
                "side": side,
                "buildId": build,
                "loaderId": "layered-loader-v2",
                "protocolId": "migration-protocol-v1",
                "definitionCatalogId": catalog["catalogId"],
                "definitionCatalogSha256": catalog_hash,
                "mapFormatId": "signed-layered-v1",
                "packageSchemaId": "layered-world-package-v1",
                "encodingVersions": [1, 2, 3, 4],
                "authoring": authoring,
            }
            runtime_path = target / f"{side}/evidence/runtime.json"
            runtime_path.parent.mkdir(parents=True, exist_ok=True)
            runtime_path.write_text(json.dumps(runtime, indent=2) + "\n", encoding="utf-8")

        configuration = {
            "schemaVersion": 1,
            "manifestType": "world-builder-map-configuration",
            "configurationId": "primary",
            "active": True,
            "representation": "layered",
            "serverMapRelativePath": "server/maps/active",
            "clientMapRelativePath": "client/maps/active",
            "serverRuntimeRelativePath": "server/evidence/runtime.json",
            "clientRuntimeRelativePath": "client/evidence/runtime.json",
            "serverDefinitionCatalogRelativePath": "server/evidence/definitions.json",
            "clientDefinitionCatalogRelativePath": "client/evidence/definitions.json",
            "assets": [{
                "role": "library",
                "serverRelativePath": "server/evidence/render-assets.bin",
                "clientRelativePath": "client/evidence/render-assets.bin",
            }],
            "placements": [],
        }
        configuration_path = target / "server/world-builder-configs/primary.json"
        configuration_path.parent.mkdir(parents=True, exist_ok=True)
        configuration_path.write_text(
            json.dumps(configuration, indent=2) + "\n", encoding="utf-8"
        )
        descriptor = {
            "schemaVersion": 1,
            "manifestType": "world-builder-target-capability",
            "adapterId": "generic-layered-v1",
            "capabilityId": "migration-layered-target-v1",
            "server": {"buildId": "migration-server-v1", "loaderId": "layered-loader-v2"},
            "client": {
                "buildId": "migration-client-v1",
                "protocolId": "migration-protocol-v1",
                "loaderId": "layered-loader-v2",
            },
            "definitions": {
                "catalogId": catalog["catalogId"],
                "catalogSha256": catalog_hash,
            },
            "map": {
                "formatId": "signed-layered-v1",
                "packageSchemaId": "layered-world-package-v1",
                "encodingVersions": [1, 2, 3, 4],
            },
            "discovery": {
                "configurationRoles": ["primary"],
                "sourceRepresentations": ["layered"],
                "sourceRoles": [
                    "client-asset.library", "client-definition-catalog",
                    "client-map-manifest", "client-map-placement-set",
                    "client-map-terrain", "client-runtime",
                    "server-asset.library", "server-definition-catalog",
                    "server-map-manifest", "server-map-placement-set",
                    "server-map-terrain", "server-runtime",
                ],
            },
            "authoring": authoring,
            "install": {
                "enabled": True,
                "serverRoles": ["layered-package"],
                "clientRoles": ["layered-package"],
                "configurationRoles": ["primary"],
                "mutationProfileId": "generic-layered-install-v1",
                "offlineEvidence": ["pid-file", "port-bind"],
            },
        }
        descriptor_path = target / "server/world-builder-capabilities.json"
        descriptor_path.write_text(json.dumps(descriptor, indent=2) + "\n", encoding="utf-8")

    def test_deterministic_portable_choice_binds_both_reports(self) -> None:
        selected, legacy = reports()
        first, selected_before, legacy_before = self.run_choice(selected, legacy)
        self.assertEqual(first.returncode, 0, first.stderr)
        choice = json.loads(first.stdout)
        self.assertEqual(
            choice["selectedTargetDiscoveryFingerprintSha256"],
            selected["discoveryFingerprintSha256"],
        )
        self.assertEqual(
            choice["legacyPackedDiscoveryFingerprintSha256"],
            legacy["discoveryFingerprintSha256"],
        )
        self.assertTrue(choice["retirementRequested"])
        self.assertEqual(choice["legacyTerrain"]["server"]["sha256"], "e" * 64)
        self.assertNotIn("/display/server", first.stdout)
        self.assertEqual((self.root / "selected.json").read_bytes(), selected_before)
        self.assertEqual((self.root / "legacy.json").read_bytes(), legacy_before)

        second, _, _ = self.run_choice(selected, legacy)
        self.assertEqual(second.returncode, 0, second.stderr)
        self.assertEqual(first.stdout, second.stdout)

    def test_keep_layered_choice_binds_the_same_exact_evidence(self) -> None:
        selected, legacy = reports()
        result, selected_before, legacy_before = self.run_choice(
            selected, legacy, decision="keep"
        )
        self.assertEqual(result.returncode, 0, result.stderr)
        choice = json.loads(result.stdout)
        self.assertEqual(
            choice["decision"], "keep-selected-layered-landscape"
        )
        self.assertTrue(choice["retirementRequested"])
        self.assertEqual(
            choice["selectedTargetDiscoveryFingerprintSha256"],
            selected["discoveryFingerprintSha256"],
        )
        self.assertEqual(
            choice["legacyPackedDiscoveryFingerprintSha256"],
            legacy["discoveryFingerprintSha256"],
        )
        self.assertEqual((self.root / "selected.json").read_bytes(), selected_before)
        self.assertEqual((self.root / "legacy.json").read_bytes(), legacy_before)

    def test_assessment_classifies_legacy_sectors_missing_from_layered_map(self) -> None:
        target = self.legacy_target()
        server_legacy = target / "server/conf/server/data/Custom_Landscape.orsc"
        DISCOVERY.AdaptiveDiscoveryTest.write_archive(server_legacy)
        shutil.copy2(
            server_legacy,
            target / "Client_Base/Cache/video/Custom_Landscape.orsc",
        )
        fixture = DISCOVERY.AdaptiveDiscoveryTest(methodName="runTest")
        catalog = fixture.definition_catalog()
        catalog.update({
            "boundaries": [1],
            "scenery": [1],
            "npcs": [1],
            "groundItems": [7],
        })
        self.add_layered_authority(target, catalog)

        before = DISCOVERY.AdaptiveDiscoveryTest.snapshot(target)
        result = self.run_assessment(target)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(before, DISCOVERY.AdaptiveDiscoveryTest.snapshot(target))
        assessment = json.loads(result.stdout)
        self.assertEqual(assessment["status"], "incomplete")
        self.assertEqual(assessment["legacySectorCount"], 1)
        self.assertEqual(assessment["missingSectorCount"], 1)
        self.assertEqual(assessment["layeredOnlySectorCount"], 1)
        self.assertEqual(assessment["missingSectors"], ["global:0:0:0"])

    def test_assessment_classifies_conflicting_and_equivalent_legacy_sectors(self) -> None:
        fixture = DISCOVERY.AdaptiveDiscoveryTest(methodName="runTest")
        catalog = fixture.definition_catalog()
        catalog.update({
            "boundaries": [1],
            "scenery": [1],
            "npcs": [1],
            "groundItems": [7],
        })
        for equivalent in (False, True):
            with self.subTest(equivalent=equivalent):
                target = self.legacy_target(
                    "equivalent" if equivalent else "conflicting"
                )
                server_legacy = (
                    target / "server/conf/server/data/Custom_Landscape.orsc"
                )
                DISCOVERY.AdaptiveDiscoveryTest.write_archive(server_legacy)
                shutil.copy2(
                    server_legacy,
                    target / "Client_Base/Cache/video/Custom_Landscape.orsc",
                )
                self.add_layered_authority(target, catalog, world_space="global")
                if equivalent:
                    with zipfile.ZipFile(server_legacy) as archive:
                        packed = archive.read("h0x48y37")
                    converted = bytearray()
                    for offset in range(0, len(packed), 10):
                        tile = bytearray(packed[offset:offset + 10])
                        tile[4], tile[5] = tile[5], tile[4]
                        converted.extend(b"\x00")
                        converted.extend(tile)
                    for package in (
                        target / "server/maps/active",
                        target / "client/maps/active",
                    ):
                        terrain = package / "terrain/creator/lp0/xp0-yp0.raw"
                        terrain.write_bytes(converted)
                        manifest_path = package / "manifest.json"
                        manifest = json.loads(
                            manifest_path.read_text(encoding="utf-8")
                        )
                        manifest["terrainSectors"][0]["encoding"] = (
                            "raw-layered-sector-v2-u16"
                        )
                        manifest["terrainSectors"][0]["sha256"] = hashlib.sha256(
                            terrain.read_bytes()
                        ).hexdigest()
                        manifest_path.write_text(
                            json.dumps(manifest, indent=2) + "\n",
                            encoding="utf-8",
                        )
                result = self.run_assessment(target)
                self.assertEqual(result.returncode, 0, result.stderr)
                assessment = json.loads(result.stdout)
                self.assertEqual(
                    assessment["status"],
                    "equivalent" if equivalent else "conflicting",
                )
                self.assertEqual(assessment["missingSectorCount"], 0)
                self.assertEqual(
                    assessment["equivalentSectorCount"], 1 if equivalent else 0
                )
                self.assertEqual(
                    assessment["conflictingSectorCount"], 0 if equivalent else 1
                )

    def test_active_layered_base_is_resolved_from_target_launch_metadata(self) -> None:
        target = self.root / "server root"
        target.mkdir()
        package = self.root / "active layered package"
        package.mkdir()
        (package / "manifest.json").write_text(
            '{"manifestType":"layered-world-package"}\n', encoding="utf-8"
        )
        expected = hashlib.sha256((package / "manifest.json").read_bytes()).hexdigest()
        self.write_layered_launch_marker(target, package, 43615)

        result = self.discover_layered_bases(target)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(
            f"{package.resolve()}|world-builder.conf|{expected}\n", result.stdout
        )

    def test_layered_base_discovery_rejects_stale_hash_and_wrong_root(self) -> None:
        target = self.root / "target"
        target.mkdir()
        package = self.root / "package"
        package.mkdir()
        (package / "manifest.json").write_text("{}\n", encoding="utf-8")
        self.write_layered_launch_marker(
            target, package, 43615, manifest_hash="0" * 64
        )
        wrong_root = self.write_layered_launch_marker(target, package, 43616)
        wrong_root.write_text(
            wrong_root.read_text(encoding="utf-8").replace(
                f"marker_root={target.resolve()}", f"marker_root={self.root.resolve()}"
            ),
            encoding="utf-8",
        )

        result = self.discover_layered_bases(target)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual("", result.stdout)

    def test_layered_base_discovery_prefers_selected_configuration(self) -> None:
        target = self.root / "target"
        target.mkdir()
        primary = self.root / "primary"
        alternate = self.root / "alternate"
        primary.mkdir()
        alternate.mkdir()
        (primary / "manifest.json").write_text("{\"package\":1}\n", encoding="utf-8")
        (alternate / "manifest.json").write_text("{\"package\":2}\n", encoding="utf-8")
        self.write_layered_launch_marker(target, primary, 43615, "world-builder.conf")
        self.write_layered_launch_marker(target, alternate, 43616, "other.conf")

        result = self.discover_layered_bases(
            target, "server/conf/world-builder.conf"
        )
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(1, len(result.stdout.splitlines()))
        self.assertTrue(result.stdout.startswith(str(primary.resolve()) + "|"))

    def test_content_addressed_external_layered_installation_is_detected(self) -> None:
        target = self.root / "target"
        target.mkdir()
        data_home = self.root / "platform-data"
        staging = self.root / "package-staging"
        staging.mkdir()
        manifest = {
            "schemaVersion": 1,
            "packageType": "layered-world",
            "packageId": "example.creator-world",
            "packageVersion": "1.2.3",
            "coordinateModel": "signed-layered-v1",
            "storage": {"sectorSize": 48, "presentationChunkSize": 24},
            "worldSpaces": [{"id": "global", "kind": "static"}],
            "levels": [],
            "terrainSectors": [],
            "placementSets": [],
        }
        (staging / "manifest.json").write_text(
            json.dumps(manifest, indent=2) + "\n", encoding="utf-8"
        )
        manifest_hash = hashlib.sha256(
            (staging / "manifest.json").read_bytes()
        ).hexdigest()
        package = (
            data_home / "example-game/live/layered-worlds" / manifest_hash / "package"
        )
        package.parent.mkdir(parents=True)
        shutil.move(str(staging), str(package))

        result = self.discover_layered_bases(target, data_home=data_home)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(
            f"{package.resolve()}|example.creator-world 1.2.3|{manifest_hash}\n",
            result.stdout,
        )

        # A same-shaped directory that is not named by its exact manifest hash
        # is not an automatic authority.
        misplaced = data_home / "other-game/live/layered-worlds/not-a-hash/package"
        misplaced.parent.mkdir(parents=True)
        shutil.copytree(package, misplaced)
        second = self.discover_layered_bases(target, data_home=data_home)
        self.assertEqual(result.stdout, second.stdout)

    def test_retirement_intent_is_explicit(self) -> None:
        selected, legacy = reports()
        result, _, _ = self.run_choice(selected, legacy, retirement=False)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertFalse(json.loads(result.stdout)["retirementRequested"])

    def test_primary_packed_choice_preserves_v1_runtime_and_legacy_map(self) -> None:
        base = self.root / "primary-packed-retirement"
        base.mkdir()
        packed_fixture = PACKED.PackedConversionTest(methodName="runTest")
        target = packed_fixture.fixture(base)
        discovery_fixture = DISCOVERY.AdaptiveDiscoveryTest(methodName="runTest")
        packed_content = base / "descriptor-packed-content"
        discovery_fixture.add_packed_content(target, packed_content)
        shutil.copy2(
            packed_content / "legacy-target/server/myworld.conf",
            target / "server/myworld.conf",
        )
        capability_path = target / "server/world-builder-capabilities.json"
        capability = json.loads(capability_path.read_text(encoding="utf-8"))
        capability["map"]["encodingVersions"] = [1, 2, 3, 4]
        DISCOVERY.write_json(capability_path, capability)
        for runtime_path in (
            target / "server/evidence/runtime.json",
            target / "client/evidence/runtime.json",
        ):
            evidence = json.loads(runtime_path.read_text(encoding="utf-8"))
            evidence["encodingVersions"] = [1, 2, 3, 4]
            DISCOVERY.write_json(runtime_path, evidence)
        server_source = target / "server/maps/active.orsc"
        client_source = target / "client/maps/active.orsc"
        server_legacy = target / "server/conf/server/data/Custom_Landscape.orsc"
        client_legacy = target / "Client_Base/Cache/video/Custom_Landscape.orsc"
        server_legacy.parent.mkdir(parents=True, exist_ok=True)
        client_legacy.parent.mkdir(parents=True, exist_ok=True)
        shutil.move(server_source, server_legacy)
        shutil.move(client_source, client_legacy)
        configuration_path = target / "server/world-builder-configs/primary.json"
        configuration = json.loads(configuration_path.read_text(encoding="utf-8"))
        configuration["serverMapRelativePath"] = (
            "server/conf/server/data/Custom_Landscape.orsc"
        )
        configuration["clientMapRelativePath"] = (
            "Client_Base/Cache/video/Custom_Landscape.orsc"
        )
        configuration_path.write_text(
            json.dumps(configuration, indent=2) + "\n", encoding="utf-8"
        )
        write_runtime_jar(
            target / "server/core.jar", b"target-specific v1 server runtime\n"
        )
        write_runtime_jar(
            target / "client/Open_RSC_Client.jar",
            b"target-specific v1 client runtime\n"
        )
        add_client_upgrade_source(target / "client")
        project_support.write_json(
            target
            / "server/conf/world-builder/installed-runtime-capability-v1.json",
            project_support.installed_v1_capability(),
        )
        add_current_host_capability(target)
        installation = base / "World Builder 2"
        installation.mkdir()
        runtime = LIFECYCLE.AdaptiveProjectLifecycleTest.make_runtime(base)
        marker = self.root / "primary-packed-retirement-project.txt"
        target_before = LIFECYCLE.tree_bytes(target)
        created = subprocess.run(
            [
                "java", "-cp", str(self.classes),
                "com.openrsc.worldbuilder.ScriptedMigrationLauncherHarness",
                str(installation), str(runtime), str(target), "43904", str(marker),
            ],
            cwd=ROOT,
            text=True,
            capture_output=True,
        )
        self.assertEqual(created.returncode, 0, created.stderr)
        project = Path(marker.read_text(encoding="utf-8"))
        source_content = project / "source/content-bundle"
        working_content = project / "working/content-bundle"
        content_manifest = json.loads(
            (source_content / "manifest.json").read_text(encoding="utf-8")
        )
        self.assertIn(846, content_manifest["definitionCatalog"]["npcs"])
        self.assertEqual(
            content_manifest["bundleFingerprintSha256"],
            json.loads(
                (working_content / "manifest.json").read_text(encoding="utf-8")
            )["bundleFingerprintSha256"],
        )
        self.assertEqual(
            LIFECYCLE.tree_bytes(source_content),
            LIFECYCLE.tree_bytes(working_content),
        )
        project_id = json.loads((project / "project.json").read_text(
            encoding="utf-8"
        ))["projectId"]
        target_after_creation = LIFECYCLE.tree_bytes(target)
        self.assertEqual(target_before, target_after_creation)

        exported = self.run_cli("export-adaptive", "--project", project)
        self.assertEqual(exported.returncode, 0, exported.stderr)
        export_root = json.loads(exported.stdout)["exportDirectory"]
        refused = self.run_cli(
            "import-adaptive", "--project", project,
            "--export", export_root, "--target-root", target,
        )
        self.assertEqual(3, refused.returncode, refused.stderr)
        self.assertIn("RUNTIME_UPGRADE_REQUIRED", refused.stderr)
        upgrade_preview = self.run_cli(
            "upgrade-target-runtime", "--project", project,
            "--export", export_root, "--target-root", target,
        )
        self.assertEqual(0, upgrade_preview.returncode, upgrade_preview.stderr)
        upgrade_plan = json.loads(upgrade_preview.stdout)
        upgraded = self.run_cli(
            "upgrade-target-runtime", "--project", project,
            "--export", export_root, "--target-root", target,
            "--confirm", "UPGRADE",
            "--transaction-id", upgrade_plan["transactionId"],
            "--plan-sha256", upgrade_plan["planFingerprintSha256"],
        )
        self.assertEqual(0, upgraded.returncode, upgraded.stderr)
        target_after_upgrade = LIFECYCLE.tree_bytes(target)
        preview = self.run_cli(
            "import-adaptive", "--project", project,
            "--export", export_root,
            "--target-root", target,
        )
        self.assertEqual(preview.returncode, 0, preview.stderr)
        preview_plan = json.loads(preview.stdout)
        self.assertEqual(
            {
                "runtime-compatibility-client-profile",
                "runtime-compatibility-server-profile",
            },
            {
                action["role"] for action in preview_plan["actions"]
                if action["role"].startswith("runtime-compatibility-")
            },
        )
        self.assertFalse(any(
            action["role"].startswith("retire-legacy-landscape-")
            for action in preview_plan["actions"]
        ))
        self.assertEqual(target_after_upgrade, LIFECYCLE.tree_bytes(target))

        plan_path = self.root / "primary-packed-retirement-plan.json"
        target_server_runtime = (target / "server/core.jar").read_bytes()
        target_client_runtime = (target / "client/Open_RSC_Client.jar").read_bytes()
        transaction = subprocess.run(
            [
                "java", "-cp", str(self.classes),
                "com.openrsc.worldbuilder.LauncherMigrationTransactionHarness",
                str(installation), str(runtime), str(target), project_id,
                "43904", str(plan_path),
            ],
            cwd=ROOT,
            text=True,
            capture_output=True,
        )
        self.assertEqual(transaction.returncode, 0, transaction.stderr)
        self.assertNotEqual(target_after_upgrade, LIFECYCLE.tree_bytes(target))
        self.assertEqual(
            target_server_runtime,
            (target / "server/core.jar").read_bytes(),
        )
        self.assertEqual(
            target_client_runtime,
            (target / "client/Open_RSC_Client.jar").read_bytes(),
        )
        self.assertFalse((
            target / "server/conf/world-builder/installed-runtime-capability-v1.json"
        ).is_file())
        self.assertFalse((
            target / "server/conf/world-builder/installed-runtime-capability-v2.json"
        ).exists())
        self.assertTrue(server_legacy.is_file())
        self.assertTrue(client_legacy.is_file())
        plan = json.loads(plan_path.read_text(encoding="utf-8"))
        self.assertEqual(0, len([
            action for action in plan["actions"]
            if action["role"].startswith("retire-legacy-landscape-")
        ]))
        self.assertEqual(
            {
                "runtime-compatibility-client-profile",
                "runtime-compatibility-server-profile",
            },
            {
                action["role"] for action in plan["actions"]
                if action["role"].startswith("runtime-compatibility-")
            },
        )

    def test_reports_must_name_same_target(self) -> None:
        selected, legacy = reports()
        legacy["targetRootDisplay"] = "/display/another-server"
        result, _, _ = self.run_choice(selected, legacy)
        self.assertEqual(result.returncode, 3)
        self.assertIn("do not identify the same target root", result.stderr)
        self.assertIn("|false|", result.stderr)

    def test_selected_report_must_be_layered(self) -> None:
        selected, legacy = reports()
        selected["representation"] = "packed"
        bind_report(selected)
        result, _, _ = self.run_choice(selected, legacy)
        self.assertEqual(result.returncode, 3)
        self.assertIn("not a compatible layered discovery", result.stderr)

    def test_edited_report_fingerprint_is_rejected(self) -> None:
        selected, legacy = reports()
        legacy["files"][0]["size"] = 8192
        result, _, _ = self.run_choice(selected, legacy)
        self.assertEqual(result.returncode, 3)
        self.assertIn("fingerprint does not match", result.stderr)

    def test_legacy_terrain_must_be_byte_identical(self) -> None:
        selected, legacy = reports()
        legacy["files"][0]["sha256"] = "d" * 64
        bind_report(legacy)
        result, _, _ = self.run_choice(selected, legacy)
        self.assertEqual(result.returncode, 3)
        self.assertIn("Custom_Landscape bytes differ", result.stderr)

    def test_both_legacy_terrain_roles_are_required(self) -> None:
        selected, legacy = reports()
        legacy["files"] = legacy["files"][:1]
        bind_report(legacy)
        result, _, _ = self.run_choice(selected, legacy)
        self.assertEqual(result.returncode, 3)
        self.assertIn("lacks present client/server terrain evidence", result.stderr)

    def test_non_custom_landscape_paths_are_rejected(self) -> None:
        selected, legacy = reports()
        legacy["files"][0]["relativePath"] = "Client_Base/Cache/video/Other.orsc"
        legacy["files"][1]["relativePath"] = "server/conf/server/data/Other.orsc"
        bind_report(legacy)
        result, _, _ = self.run_choice(selected, legacy)
        self.assertEqual(result.returncode, 3)
        self.assertIn("outside the compiled client/server landscape roots", result.stderr)

    def test_secondary_discovery_ignores_normal_target_reserved_evidence(self) -> None:
        target = self.legacy_target()
        descriptor = target / "server/world-builder-capabilities.json"
        configuration = target / "server/world-builder-configs/primary.json"
        descriptor.parent.mkdir(parents=True, exist_ok=True)
        configuration.parent.mkdir(parents=True, exist_ok=True)
        descriptor.write_text("{}\n", encoding="utf-8")
        configuration.write_text("{}\n", encoding="utf-8")
        before = DISCOVERY.AdaptiveDiscoveryTest.snapshot(target)

        first = self.run_legacy_discovery(target)
        second = self.run_legacy_discovery(target)
        self.assertEqual(first.returncode, 0, first.stderr)
        self.assertEqual(first.stdout, second.stdout)
        self.assertEqual(before, DISCOVERY.AdaptiveDiscoveryTest.snapshot(target))
        report = json.loads(first.stdout)
        self.assertEqual(report["representation"], "packed")
        self.assertFalse(report["descriptor"]["present"])
        self.assertEqual(
            report["capability"]["capabilityId"],
            "spoiled-milk-packed-fallback-v1",
        )
        paths = {entry["relativePath"] for entry in report["files"]}
        self.assertNotIn("server/world-builder-capabilities.json", paths)
        self.assertNotIn("server/world-builder-configs/primary.json", paths)
        self.assertIn(
            "server/conf/server/data/Custom_Landscape.orsc", paths
        )

    def test_secondary_discovery_requires_both_legacy_archives(self) -> None:
        target = self.legacy_target()
        (target / "Client_Base/Cache/video/Custom_Landscape.orsc").unlink()
        result = self.run_legacy_discovery(target)
        self.assertEqual(result.returncode, 3)
        self.assertIn("Custom_Landscape", result.stderr)
        self.assertIn("|false|", result.stderr)

    def test_migrated_project_uses_isolated_legacy_input_and_layered_attachment(self) -> None:
        target = self.legacy_target()
        server_terrain = target / "server/conf/server/data/Custom_Landscape.orsc"
        with zipfile.ZipFile(server_terrain, "w", zipfile.ZIP_DEFLATED) as archive:
            archive.writestr("h0x48y37", bytes(48 * 48 * 10))
        shutil.copy2(
            server_terrain,
            target / "Client_Base/Cache/video/Custom_Landscape.orsc",
        )
        bootstrap = self.root / "bootstrap"
        bootstrap.mkdir()
        bootstrap_runtime = LIFECYCLE.AdaptiveProjectLifecycleTest.make_runtime(bootstrap)
        legacy_report_path = self.root / "legacy-before-layered.json"
        legacy_discovery = self.run_cli(
            "discover-adaptive", "--target-root", target
        )
        self.assertEqual(legacy_discovery.returncode, 0, legacy_discovery.stderr)
        legacy_report_path.write_text(legacy_discovery.stdout, encoding="utf-8")
        bootstrap_create = self.run_cli(
            "create-project",
            "--installation-root", bootstrap,
            "--runtime-root", bootstrap_runtime,
            "--target-root", target,
            "--discovery-report", legacy_report_path,
            "--display-name", "Catalog bootstrap",
            "--port", 43901,
            "--confirm", "CREATE",
        )
        self.assertEqual(bootstrap_create.returncode, 0, bootstrap_create.stderr)
        bootstrap_project = Path(json.loads(bootstrap_create.stdout)["projectRoot"])
        generated_catalog = json.loads((
            bootstrap_project
            / "source/original/server/world-builder-fallback/definitions.json"
        ).read_text(encoding="utf-8"))

        # A primary packed Custom_Landscape is itself an explicit migration
        # choice: Yes keeps the same exact conversion input while binding later
        # retirement intent. The target remains read-only during creation.
        direct_installation = self.root / "Direct Packed World Builder 2"
        direct_installation.mkdir()
        direct_runtime = LIFECYCLE.AdaptiveProjectLifecycleTest.make_runtime(
            direct_installation
        )
        direct_marker = self.root / "direct-packed-project.txt"
        direct_before = DISCOVERY.AdaptiveDiscoveryTest.snapshot(target)
        direct = subprocess.run(
            [
                "java", "-cp", str(self.classes),
                "com.openrsc.worldbuilder.ScriptedMigrationLauncherHarness",
                str(direct_installation), str(direct_runtime), str(target),
                "43900", str(direct_marker),
            ],
            cwd=ROOT,
            text=True,
            capture_output=True,
        )
        self.assertEqual(direct.returncode, 0, direct.stderr)
        self.assertEqual(direct_before, DISCOVERY.AdaptiveDiscoveryTest.snapshot(target))
        direct_project = Path(direct_marker.read_text(encoding="utf-8"))
        direct_choice = json.loads((
            direct_project / "source/migration/choice.json"
        ).read_text(encoding="utf-8"))
        self.assertEqual(
            "world-builder-packed-map-migration-choice",
            direct_choice["manifestType"],
        )
        self.assertTrue(direct_choice["retirementRequested"])
        direct_source_content = direct_project / "source/content-bundle"
        direct_working_content = direct_project / "working/content-bundle"
        direct_content_manifest = json.loads(
            (direct_source_content / "manifest.json").read_text(encoding="utf-8")
        )
        self.assertIn(846, direct_content_manifest["definitionCatalog"]["npcs"])
        self.assertEqual(
            direct_content_manifest["bundleFingerprintSha256"],
            json.loads(
                (direct_working_content / "manifest.json").read_text(encoding="utf-8")
            )["bundleFingerprintSha256"],
        )
        self.assertEqual(
            LIFECYCLE.tree_bytes(direct_source_content),
            LIFECYCLE.tree_bytes(direct_working_content),
        )
        self.assertEqual(
            direct_choice["selectedTargetDiscoveryFingerprintSha256"],
            json.loads(legacy_report_path.read_text(encoding="utf-8"))[
                "discoveryFingerprintSha256"
            ],
        )
        self.add_layered_authority(target, generated_catalog)
        write_runtime_jar(
            target / "server/core.jar", b"old target server runtime\n"
        )
        write_runtime_jar(
            target / "client/Open_RSC_Client.jar",
            b"old target client runtime\n"
        )
        add_current_host_capability(target)
        add_client_upgrade_source(target / "client")

        selected_report_path = self.root / "selected-layered.json"
        selected_discovery = self.run_cli(
            "discover-adaptive", "--target-root", target
        )
        self.assertEqual(selected_discovery.returncode, 0, selected_discovery.stderr)
        selected = json.loads(selected_discovery.stdout)
        self.assertEqual(selected["representation"], "layered")
        selected_report_path.write_text(selected_discovery.stdout, encoding="utf-8")

        secondary_report_path = self.root / "legacy-secondary.json"
        secondary_discovery = self.run_cli(
            "discover-legacy-landscape", "--target-root", target
        )
        self.assertEqual(secondary_discovery.returncode, 0, secondary_discovery.stderr)
        secondary = json.loads(secondary_discovery.stdout)
        self.assertEqual(secondary["representation"], "packed")
        secondary_report_path.write_text(secondary_discovery.stdout, encoding="utf-8")

        keep_installation = self.root / "Keep Layered World Builder 2"
        keep_installation.mkdir()
        keep_runtime = LIFECYCLE.AdaptiveProjectLifecycleTest.make_runtime(
            keep_installation
        )
        keep_marker = self.root / "keep-layered-project.txt"
        kept = subprocess.run(
            [
                "java", "-cp", str(self.classes),
                "com.openrsc.worldbuilder.ScriptedMigrationLauncherHarness",
                str(keep_installation), str(keep_runtime), str(target),
                "43905", str(keep_marker), "false",
            ],
            cwd=ROOT,
            text=True,
            capture_output=True,
        )
        self.assertEqual(kept.returncode, 0, kept.stderr)
        kept_project = Path(keep_marker.read_text(encoding="utf-8"))
        kept_manifest = json.loads(
            (kept_project / "project.json").read_text(encoding="utf-8")
        )
        self.assertEqual(kept_manifest["origin"], "target-layered")
        kept_choice = json.loads((
            kept_project / "source/migration/choice.json"
        ).read_text(encoding="utf-8"))
        self.assertEqual(
            kept_choice["decision"], "keep-selected-layered-landscape"
        )
        self.assertTrue(kept_choice["retirementRequested"])
        self.assertFalse((
            kept_project / "source/migration/input"
        ).exists())

        installation = self.root / "World Builder 2"
        installation.mkdir()
        runtime = LIFECYCLE.AdaptiveProjectLifecycleTest.make_runtime(installation)
        before = DISCOVERY.AdaptiveDiscoveryTest.snapshot(target)
        created = self.run_cli(
            "create-migrated-project",
            "--installation-root", installation,
            "--runtime-root", runtime,
            "--target-root", target,
            "--discovery-report", selected_report_path,
            "--legacy-discovery-report", secondary_report_path,
            "--display-name", "Migrated terrain",
            "--port", 43902,
            "--retire-legacy-landscape",
            "--confirm", "CREATE",
        )
        self.assertEqual(created.returncode, 0, created.stderr)
        self.assertEqual(before, DISCOVERY.AdaptiveDiscoveryTest.snapshot(target))
        project = Path(json.loads(created.stdout)["projectRoot"])
        manifest = json.loads((project / "project.json").read_text(encoding="utf-8"))
        self.assertEqual(manifest["origin"], "target-packed")
        self.assertEqual(manifest["state"], "ready-attached")
        self.assertEqual(manifest["target"]["adapterId"], "generic-layered-v1")
        self.assertTrue(manifest["operations"]["import"])
        choice = json.loads((
            project / "source/migration/choice.json"
        ).read_text(encoding="utf-8"))
        self.assertTrue(choice["retirementRequested"])
        self.assertNotIn(str(target), json.dumps(choice))
        stored_secondary = json.loads((
            project / "source/migration/discovery-report.json"
        ).read_text(encoding="utf-8"))
        self.assertEqual(stored_secondary["targetRootDisplay"], "")
        self.assertTrue((project /
            "source/migration/input/server/world-builder-capabilities.json").is_file())
        self.assertTrue((project /
            "source/original/server/world-builder-capabilities.json").is_file())
        self.assertNotEqual(
            (project / "source/migration/input/server/world-builder-capabilities.json").read_bytes(),
            (project / "source/original/server/world-builder-capabilities.json").read_bytes(),
        )
        snapshot = json.loads((
            project / "source/snapshot-manifest.json"
        ).read_text(encoding="utf-8"))
        migration_paths = {
            record["relativePath"] for record in snapshot["originalFiles"]
            if record["relativePath"].startswith("source/migration/")
        }
        self.assertIn("source/migration/choice.json", migration_paths)
        self.assertIn("source/migration/discovery-report.json", migration_paths)
        reopened = self.run_cli(
            "open-project", "--installation-root", installation,
            "--target-root", target, "--validate-only",
        )
        self.assertEqual(reopened.returncode, 0, reopened.stderr)

        scripted_installation = self.root / "Scripted World Builder 2"
        scripted_installation.mkdir()
        scripted_runtime = LIFECYCLE.AdaptiveProjectLifecycleTest.make_runtime(
            scripted_installation
        )
        marker = self.root / "scripted-project.txt"
        scripted = subprocess.run(
            [
                "java", "-cp", str(self.classes),
                "com.openrsc.worldbuilder.ScriptedMigrationLauncherHarness",
                str(scripted_installation), str(scripted_runtime), str(target),
                "43903", str(marker),
            ],
            cwd=ROOT,
            text=True,
            capture_output=True,
        )
        self.assertEqual(scripted.returncode, 0, scripted.stderr)
        self.assertEqual(before, DISCOVERY.AdaptiveDiscoveryTest.snapshot(target))
        scripted_project = Path(marker.read_text(encoding="utf-8"))
        scripted_manifest = json.loads((
            scripted_project / "project.json"
        ).read_text(encoding="utf-8"))
        self.assertEqual(scripted_manifest["origin"], "target-packed")
        self.assertEqual(scripted_manifest["state"], "ready-attached")
        scripted_choice = json.loads((
            scripted_project / "source/migration/choice.json"
        ).read_text(encoding="utf-8"))
        self.assertTrue(scripted_choice["retirementRequested"])

        # Ordinary Import installs and selects the layered package but keeps legacy
        # rollback assets even when the earlier migration choice requested retirement.
        target_bytes_before_import = LIFECYCLE.tree_bytes(target)
        project_id = manifest["projectId"]
        plan_path = self.root / "launcher-import-plan.json"
        transaction = subprocess.run(
            [
                "java", "-cp", str(self.classes),
                "com.openrsc.worldbuilder.LauncherMigrationTransactionHarness",
                str(installation), str(runtime), str(target), project_id,
                "43902", str(plan_path),
            ],
            cwd=ROOT,
            text=True,
            capture_output=True,
        )
        self.assertEqual(transaction.returncode, 0, transaction.stderr)
        self.assertIn("Map changes were imported successfully", transaction.stdout)
        plan = json.loads(plan_path.read_text(encoding="utf-8"))
        retirements = [
            action for action in plan["actions"]
            if action["role"].startswith("retire-legacy-landscape-")
        ]
        self.assertEqual(len(retirements), 0)
        self.assertTrue(server_terrain.is_file())
        self.assertTrue((
            target / "Client_Base/Cache/video/Custom_Landscape.orsc"
        ).is_file())
        self.assertNotEqual(target_bytes_before_import, LIFECYCLE.tree_bytes(target))


if __name__ == "__main__":
    unittest.main()
