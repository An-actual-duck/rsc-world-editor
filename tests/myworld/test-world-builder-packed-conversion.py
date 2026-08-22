#!/usr/bin/env python3
"""Temporary-fixture coverage for exact deterministic Phase 2 packed conversion."""

import hashlib
import json
import os
import re
import shutil
import subprocess
import tempfile
import unittest
import zipfile
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
SOURCE_ROOT = ROOT / "tools" / "world-builder" / "src"
MAIN_CLASS = "com.openrsc.worldbuilder.WorldBuilderCli"
DESCRIPTOR = "server/world-builder-capabilities.json"
CONFIG = "server/world-builder-configs/primary.json"
FAMILIES = ["boundary", "ground-item", "npc", "scenery"]
ZERO_HASH = "0" * 64
TERRAIN_ENTRIES = [
    "h2x730y37",  # maximum packed X, level +2
    "h0x48y37",   # minimum packed X/Y, level 0
    "h3x48y56",   # maximum packed Y, level -1
    "h1x48y37",   # level +1
    "h3x48y37",   # level -1 origin sector
]


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def write_json(path: Path, value: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        json.dumps(value, sort_keys=True, indent=2, ensure_ascii=False) + "\n",
        encoding="utf-8",
    )


def legacy_point(x: int, y: int) -> dict:
    return {"X": x, "Y": y}


def terrain_bytes(entry: str, *, overlay: int = 1, length: int = 48 * 48 * 10) -> bytes:
    raw = bytearray(length)
    if length >= 10:
        raw[0] = sum(entry.encode("ascii")) & 0x7F
        raw[1] = 7
        raw[2] = overlay
        raw[3] = 0
        raw[4] = 11  # legacy horizontal wall, definition 10
        raw[5] = 12  # legacy vertical wall, definition 11
        raw[6:10] = (11).to_bytes(4, byteorder="big", signed=True)
    return bytes(raw)


def write_archive(
    path: Path,
    entries: list[str] = TERRAIN_ENTRIES,
    *,
    overlay: int = 1,
    length: int = 48 * 48 * 10,
    directory_entry: bool = False,
) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(path, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        for entry in entries:
            info = zipfile.ZipInfo(entry, (2024, 1, 2, 3, 4, 6))
            info.compress_type = zipfile.ZIP_DEFLATED
            archive.writestr(info, terrain_bytes(entry, overlay=overlay, length=length))
        if directory_entry:
            directory = zipfile.ZipInfo("unsupported-metadata/", (2024, 1, 2, 3, 4, 6))
            directory.external_attr = 0o40755 << 16
            archive.writestr(directory, b"")


def tree_bytes(root: Path) -> dict[str, tuple]:
    result = {}
    if not root.exists():
        return result
    for path in sorted(root.rglob("*")):
        relative = path.relative_to(root).as_posix()
        if path.is_symlink():
            result[relative] = ("link", os.readlink(path))
        elif path.is_dir():
            result[relative] = ("dir",)
        else:
            result[relative] = (
                "file",
                len(path.read_bytes()),
                hashlib.sha256(path.read_bytes()).hexdigest(),
            )
    return result


class PackedConversionTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.compile_temp = tempfile.TemporaryDirectory(
            prefix="world-builder-packed-conversion-classes-"
        )
        cls.classes = Path(cls.compile_temp.name) / "classes"
        cls.classes.mkdir()
        sources = sorted(str(path) for path in SOURCE_ROOT.rglob("*.java"))
        subprocess.run(
            ["javac", "-source", "8", "-target", "8", "-d", str(cls.classes), *sources],
            check=True,
            cwd=ROOT,
        )
        harness = (
            Path(cls.compile_temp.name)
            / "harness/com/openrsc/worldbuilder/PackedConversionFailureHarness.java"
        )
        harness.parent.mkdir(parents=True)
        harness.write_text(
            """
package com.openrsc.worldbuilder;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;

public final class PackedConversionFailureHarness {
    @SuppressWarnings("unchecked")
    private static Map<String,Object> object(Object value) {
        return (Map<String,Object>)value;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> array(Object value) {
        return (List<Object>)value;
    }

    private static void writeJson(Path path, Map<String,Object> value) throws Exception {
        Files.write(path, WorldBuilderJsonDocuments.pretty(value)
            .getBytes(StandardCharsets.UTF_8));
    }

    private static void tamperTerrainAndDeclaration(Path stage) throws Exception {
        Path manifestPath = stage.resolve("package/manifest.json");
        Map<String,Object> manifest = WorldBuilderJsonDocuments.readObject(manifestPath);
        Map<String,Object> declaration = object(array(manifest.get("terrainSectors")).get(0));
        Path payload = stage.resolve("package").resolve((String)declaration.get("path"));
        byte[] bytes = Files.readAllBytes(payload);
        bytes[0] ^= 1;
        Files.write(payload, bytes);
        declaration.put("sha256", WorldBuilderHashes.sha256(bytes));
        writeJson(manifestPath, manifest);
    }

    private static void tamperPlacementIdAndDeclaration(Path stage) throws Exception {
        Path manifestPath = stage.resolve("package/manifest.json");
        Map<String,Object> manifest = WorldBuilderJsonDocuments.readObject(manifestPath);
        for (Object rawDeclaration : array(manifest.get("placementSets"))) {
            Map<String,Object> declaration = object(rawDeclaration);
            Path payloadPath = stage.resolve("package").resolve(
                (String)declaration.get("path"));
            Map<String,Object> payload = WorldBuilderJsonDocuments.readObject(payloadPath);
            for (String family : new String[] {"boundaries", "groundItems", "npcs", "scenery"}) {
                List<Object> records = array(payload.get(family));
                if (records.isEmpty()) continue;
                object(records.get(0)).put("placementId", "p-tampered-deterministic-id");
                byte[] bytes = WorldBuilderJsonDocuments.pretty(payload)
                    .getBytes(StandardCharsets.UTF_8);
                Files.write(payloadPath, bytes);
                declaration.put("sha256", WorldBuilderHashes.sha256(bytes));
                writeJson(manifestPath, manifest);
                return;
            }
        }
        throw new Exception("fixture has no placement ID to tamper");
    }

    public static void main(String[] args) throws Exception {
        final String mode = args[3];
        WorldBuilderPackedConverter.Observer observer =
            new WorldBuilderPackedConverter.Observer() {
                @Override
                public void observe(String milestone, Path stage) throws Exception {
                    if (mode.equals(milestone)) {
                        throw new Exception("injected failure at " + milestone);
                    }
                    if ("tamper-before-publish".equals(mode)
                        && "before-publish".equals(milestone)) {
                        Files.write(stage.resolve("package/manifest.json"),
                            new byte[] {' ', '\\n'}, StandardOpenOption.APPEND);
                    }
                    if ("extra-directory-before-publish".equals(mode)
                        && "before-publish".equals(milestone)) {
                        Files.createDirectory(stage.resolve("unexpected-empty"));
                    }
                    if ("tamper-terrain-and-manifest".equals(mode)
                        && "package-written".equals(milestone)) {
                        tamperTerrainAndDeclaration(stage);
                    }
                    if ("tamper-placement-id-and-manifest".equals(mode)
                        && "package-written".equals(milestone)) {
                        tamperPlacementIdAndDeclaration(stage);
                    }
                }
            };
        WorldBuilderPackedConversionModel.PlacementIdFactory ids = null;
        if ("id-collision".equals(mode)) {
            ids = new WorldBuilderPackedConversionModel.PlacementIdFactory() {
                @Override
                public String create(String stableFacts) {
                    return "p-injected-collision";
                }
            };
        }
        try {
            int recordLimit = "aggregate-limit".equals(mode) ? 8 : 65536;
            new WorldBuilderPackedConverter(observer, ids, recordLimit).convert(
                Paths.get(args[0]), Paths.get(args[1]), Paths.get(args[2]));
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
        )

    @classmethod
    def tearDownClass(cls):
        cls.compile_temp.cleanup()

    def run_cli(self, *args: str) -> subprocess.CompletedProcess:
        return subprocess.run(
            ["java", "-cp", str(self.classes), MAIN_CLASS, *map(str, args)],
            cwd=ROOT,
            text=True,
            capture_output=True,
        )

    def run_conversion(
        self, source: Path, report: Path, output: Path
    ) -> subprocess.CompletedProcess:
        return self.run_cli(
            "convert-packed",
            "--source-root",
            source,
            "--discovery-report",
            report,
            "--output",
            output,
        )

    def run_injected(
        self, source: Path, report: Path, output: Path, mode: str
    ) -> subprocess.CompletedProcess:
        return subprocess.run(
            [
                "java",
                "-cp",
                str(self.classes),
                "com.openrsc.worldbuilder.PackedConversionFailureHarness",
                str(source),
                str(report),
                str(output),
                mode,
            ],
            cwd=ROOT,
            text=True,
            capture_output=True,
        )

    @staticmethod
    def add_placement(
        root: Path,
        sources: list[dict],
        role: str,
        family: str,
        kind: str,
        payload: dict,
    ) -> None:
        path = root / f"server/maps/placements/{role}.json"
        write_json(path, payload)
        sources.append(
            {
                "role": role,
                "family": family,
                "kind": kind,
                "compositionOrder": len(sources),
                "encoding": (
                    f"packed-{family}-"
                    f"{'removals' if kind == 'removal' else 'locations'}-v1"
                ),
                "relativePath": path.relative_to(root).as_posix(),
            }
        )

    def write_placements(self, root: Path) -> list[dict]:
        sources = []
        self.add_placement(
            root,
            sources,
            "boundary-base",
            "boundary",
            "base",
            {
                "boundaries": [
                    {"id": 10, "pos": legacy_point(0, 0), "direction": 0},
                    {"id": 11, "pos": legacy_point(6, 6), "direction": 1},
                    {"id": 10, "pos": legacy_point(9, 9), "direction": 2},
                ]
            },
        )
        self.add_placement(
            root,
            sources,
            "boundary-overlay",
            "boundary",
            "overlay",
            {"boundaries": [{"id": 11, "pos": legacy_point(9, 9), "direction": 2}]},
        )
        self.add_placement(
            root,
            sources,
            "boundary-removal",
            "boundary",
            "removal",
            {"boundary_removals": [{"pos": legacy_point(6, 6), "direction": 1}]},
        )
        self.add_placement(
            root,
            sources,
            "ground-base",
            "ground-item",
            "base",
            {
                "ground_items": [
                    {
                        "id": 40,
                        "pos": legacy_point(32767, 1888),
                        "amount": 1,
                        "respawn": 30,
                    },
                    {"id": 41, "pos": legacy_point(7, 7), "amount": 2, "respawn": 60},
                ]
            },
        )
        self.add_placement(
            root,
            sources,
            "ground-overlay",
            "ground-item",
            "overlay",
            {
                "ground_items": [
                    {
                        "id": 41,
                        "pos": legacy_point(32767, 1888),
                        "amount": 3,
                        "respawn": 90,
                    }
                ]
            },
        )
        self.add_placement(
            root,
            sources,
            "ground-removal",
            "ground-item",
            "removal",
            {"ground_item_removals": [{"id": 41, "pos": legacy_point(7, 7)}]},
        )
        npc_retained = {
            "id": 30,
            "start": legacy_point(3, 947),
            "min": legacy_point(2, 946),
            "max": legacy_point(4, 948),
        }
        npc_removed = {
            "id": 31,
            "start": legacy_point(8, 8),
            "min": legacy_point(8, 8),
            "max": legacy_point(9, 9),
        }
        self.add_placement(
            root,
            sources,
            "npc-base",
            "npc",
            "base",
            {"npclocs": [npc_retained, npc_removed]},
        )
        self.add_placement(
            root,
            sources,
            "npc-overlay",
            "npc",
            "overlay",
            {
                "npclocs": [
                    {
                        "id": 30,
                        "start": legacy_point(3, 947),
                        "min": legacy_point(1, 945),
                        "max": legacy_point(5, 949),
                    }
                ]
            },
        )
        self.add_placement(
            root,
            sources,
            "npc-removal",
            "npc",
            "removal",
            {"npc_removals": [npc_removed]},
        )
        self.add_placement(
            root,
            sources,
            "scenery-base",
            "scenery",
            "base",
            {
                "sceneries": [
                    {"id": 20, "pos": legacy_point(1, 3775), "direction": 2},
                    {"id": 21, "pos": legacy_point(10, 10), "direction": 4},
                ]
            },
        )
        self.add_placement(
            root,
            sources,
            "scenery-overlay",
            "scenery",
            "overlay",
            {"sceneries": [{"id": 21, "pos": legacy_point(1, 3775), "direction": 6}]},
        )
        self.add_placement(
            root,
            sources,
            "scenery-removal",
            "scenery",
            "removal",
            {"scenery_removals": [{"pos": legacy_point(10, 10)}]},
        )
        return sources

    def fixture(self, parent: Path) -> Path:
        root = parent / "target"
        catalog = {
            "schemaVersion": 1,
            "manifestType": "world-builder-definition-catalog",
            "catalogId": "portable-fixture-catalog-v1",
            "tiles": [0, 1],
            "boundaries": [10, 11],
            "scenery": [20, 21],
            "npcs": [30, 31],
            "groundItems": [40, 41],
        }
        server_catalog = root / "server/evidence/definitions.json"
        client_catalog = root / "client/evidence/definitions.json"
        write_json(server_catalog, catalog)
        client_catalog.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(server_catalog, client_catalog)
        catalog_hash = sha256(server_catalog)

        server_asset = root / "server/evidence/render-assets.bin"
        client_asset = root / "client/evidence/render-assets.bin"
        server_asset.parent.mkdir(parents=True, exist_ok=True)
        client_asset.parent.mkdir(parents=True, exist_ok=True)
        server_asset.write_bytes(b"portable matching render assets\n")
        shutil.copyfile(server_asset, client_asset)

        authoring = {
            "editExistingLevels": True,
            "createLevels": True,
            "placementFamilies": FAMILIES,
        }
        for side, build in (("server", "server-build-v9"), ("client", "client-build-v9")):
            write_json(
                root / f"{side}/evidence/runtime.json",
                {
                    "schemaVersion": 1,
                    "manifestType": "world-builder-runtime-evidence",
                    "side": side,
                    "buildId": build,
                    "loaderId": "layered-loader-v2",
                    "protocolId": "rsc-protocol-v9",
                    "definitionCatalogId": "portable-fixture-catalog-v1",
                    "definitionCatalogSha256": catalog_hash,
                    "mapFormatId": "legacy-packed-orsc-v1",
                    "packageSchemaId": "layered-world-package-v1",
                    "encodingVersions": [1],
                    "authoring": authoring,
                },
            )

        server_map = root / "server/maps/active.orsc"
        client_map = root / "client/maps/active.orsc"
        write_archive(server_map)
        client_map.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(server_map, client_map)
        placements = self.write_placements(root)
        configuration = {
            "schemaVersion": 1,
            "manifestType": "world-builder-map-configuration",
            "configurationId": "primary",
            "active": True,
            "representation": "packed",
            "serverMapRelativePath": "server/maps/active.orsc",
            "clientMapRelativePath": "client/maps/active.orsc",
            "serverRuntimeRelativePath": "server/evidence/runtime.json",
            "clientRuntimeRelativePath": "client/evidence/runtime.json",
            "serverDefinitionCatalogRelativePath": "server/evidence/definitions.json",
            "clientDefinitionCatalogRelativePath": "client/evidence/definitions.json",
            "assets": [
                {
                    "role": "library",
                    "serverRelativePath": "server/evidence/render-assets.bin",
                    "clientRelativePath": "client/evidence/render-assets.bin",
                }
            ],
            "placements": placements,
        }
        write_json(root / CONFIG, configuration)
        source_roles = {
            "server-definition-catalog",
            "client-definition-catalog",
            "server-asset.library",
            "client-asset.library",
            "server-runtime",
            "client-runtime",
            "server-terrain",
            "client-terrain",
        }
        source_roles.update(f"placement.{source['role']}" for source in placements)
        descriptor = {
            "schemaVersion": 1,
            "manifestType": "world-builder-target-capability",
            "adapterId": "spoiled-milk-packed-v1",
            "capabilityId": "portable-packed-capability-v1",
            "server": {"buildId": "server-build-v9", "loaderId": "layered-loader-v2"},
            "client": {
                "buildId": "client-build-v9",
                "protocolId": "rsc-protocol-v9",
                "loaderId": "layered-loader-v2",
            },
            "definitions": {
                "catalogId": "portable-fixture-catalog-v1",
                "catalogSha256": catalog_hash,
            },
            "map": {
                "formatId": "legacy-packed-orsc-v1",
                "packageSchemaId": "layered-world-package-v1",
                "encodingVersions": [1],
            },
            "discovery": {
                "configurationRoles": ["primary"],
                "sourceRepresentations": ["packed"],
                "sourceRoles": sorted(source_roles),
            },
            "authoring": authoring,
            "install": {
                "enabled": True,
                "serverRoles": ["layered-package"],
                "clientRoles": ["layered-package"],
                "configurationRoles": ["primary"],
                "mutationProfileId": "spoiled-milk-layered-install-v1",
                "offlineEvidence": ["pid-file", "port-bind"],
            },
        }
        write_json(root / DESCRIPTOR, descriptor)
        return root

    def discover_and_copy(self, target: Path, parent: Path) -> tuple[Path, Path, dict]:
        before = tree_bytes(target)
        discovered = self.run_cli("discover-adaptive", "--target-root", target)
        self.assertEqual(0, discovered.returncode, discovered.stderr)
        self.assertEqual(before, tree_bytes(target))
        report = parent / "discovery-report.json"
        report.write_text(discovered.stdout, encoding="utf-8")
        document = json.loads(discovered.stdout)
        source = parent / "immutable-source"
        source.mkdir()
        for record in [
            document["descriptor"],
            document["selectedConfiguration"],
            *document["files"],
        ]:
            if not record["present"]:
                continue
            destination = source / record["relativePath"]
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(target / record["relativePath"], destination)
        return source, report, document

    @staticmethod
    def refresh_report(source: Path, report: Path) -> dict:
        document = json.loads(report.read_text(encoding="utf-8"))
        for key in ("descriptor", "selectedConfiguration"):
            record = document[key]
            record["sha256"] = sha256(source / record["relativePath"])
        for record in document["files"]:
            path = source / record["relativePath"]
            record["size"] = path.stat().st_size
            record["sha256"] = sha256(path)
        display = document["targetRootDisplay"]
        document["targetRootDisplay"] = ""
        document["discoveryFingerprintSha256"] = ZERO_HASH
        canonical = json.dumps(
            document, sort_keys=True, separators=(",", ":"), ensure_ascii=False
        ).encode("utf-8")
        document["discoveryFingerprintSha256"] = hashlib.sha256(canonical).hexdigest()
        document["targetRootDisplay"] = display
        write_json(report, document)
        return document

    def clone_source_case(
        self, source: Path, report: Path, parent: Path
    ) -> tuple[Path, Path]:
        cloned_source = parent / "source"
        shutil.copytree(source, cloned_source)
        cloned_report = parent / "report.json"
        shutil.copyfile(report, cloned_report)
        return cloned_source, cloned_report

    def assert_failed_without_publication(
        self,
        source: Path,
        report: Path,
        output: Path,
        *,
        expected: str | None = None,
    ) -> subprocess.CompletedProcess:
        source_before = tree_bytes(source)
        result = self.run_conversion(source, report, output)
        self.assertEqual(3, result.returncode, result.stderr)
        self.assertFalse(output.exists())
        self.assertEqual(source_before, tree_bytes(source))
        self.assertFalse(list(output.parent.glob(f".{output.name}.staging-*")))
        if expected:
            self.assertIn(expected.lower(), result.stderr.lower())
        return result

    def test_deterministic_conversion_reverse_parity_and_portability(self):
        with tempfile.TemporaryDirectory(prefix="packed-conversion-portable-") as temp:
            temp_root = Path(temp)
            outputs = []
            reports = []
            target_snapshots = []
            source_roots = []
            for name in ("first-root", "second-root"):
                case = temp_root / name
                case.mkdir()
                target = self.fixture(case)
                source, report_path, discovery = self.discover_and_copy(target, case)
                self.assertEqual("compatible", discovery["status"])
                target_before = tree_bytes(target)
                source_before = tree_bytes(source)
                output = case / "converted"
                converted = self.run_conversion(source, report_path, output)
                self.assertEqual(0, converted.returncode, converted.stderr)
                self.assertEqual(target_before, tree_bytes(target))
                self.assertEqual(source_before, tree_bytes(source))
                outputs.append(tree_bytes(output))
                reports.append(json.loads((output / "conversion-report.json").read_text()))
                target_snapshots.append(target_before)
                source_roots.append(source)

            self.assertEqual(outputs[0], outputs[1])
            self.assertEqual(reports[0], reports[1])
            report = reports[0]
            self.assertFalse(report["blocked"])
            self.assertEqual(
                {
                    "unknownCount": 0,
                    "lossCount": 0,
                    "approximationCount": 0,
                    "repairCount": 0,
                    "parityDeltaCount": 0,
                },
                report["validation"],
            )
            self.assertEqual(5, report["terrain"]["entriesRead"])
            self.assertEqual(5, report["terrain"]["reverseMatched"])
            self.assertEqual(0, report["terrain"]["reverseMismatches"])
            families = {summary["family"] for summary in report["placements"]}
            self.assertEqual(set(FAMILIES), families)
            decision_kinds = {(item["kind"], item["outcome"]) for item in report["decisions"]}
            self.assertIn(("replacement", "replaced"), decision_kinds)
            self.assertIn(("removal", "removed"), decision_kinds)
            self.assertIn(("precedence", "retained"), decision_kinds)
            for decision in report["decisions"]:
                self.assertIn("server/maps/placements/", decision["provenance"])
                self.assertIn("#record=", decision["provenance"])

            first_output = temp_root / "first-root/converted"
            manifest = json.loads((first_output / "package/manifest.json").read_text())
            self.assertTrue(manifest["packageId"].startswith("world-builder.converted."))
            self.assertNotEqual(
                "rsc-remastered.spoiled-milk-layered-world", manifest["packageId"]
            )
            self.assertEqual([-1, 0, 1, 2], [level["level"] for level in manifest["levels"]])
            self.assertTrue(all(
                item["encoding"] == "raw-layered-sector-v2-u16"
                for item in manifest["terrainSectors"]
            ))
            package_files = [
                path.relative_to(first_output / "package").as_posix()
                for path in (first_output / "package").rglob("*")
                if path.is_file()
            ]
            self.assertFalse(any("ipbans" in path.lower() for path in package_files))
            self.assertFalse(any("workspace" in path.lower() for path in package_files))

            source_archive = source_roots[0] / "server/maps/active.orsc"
            with zipfile.ZipFile(source_archive) as archive:
                for declaration in manifest["terrainSectors"]:
                    plane = {0: 0, 1: 1, 2: 2, -1: 3}[declaration["level"]]
                    entry = (
                        f"h{plane}x{declaration['sectorX'] + 48}"
                        f"y{declaration['sectorY'] + 37}"
                    )
                    layered = bytearray(
                        (first_output / "package" / declaration["path"]).read_bytes()
                    )
                    legacy = bytearray()
                    for offset in range(0, len(layered), 11):
                        self.assertEqual(0, layered[offset])
                        tile = bytearray(layered[offset + 1 : offset + 11])
                        tile[4], tile[5] = tile[5], tile[4]
                        legacy.extend(tile)
                    self.assertEqual(archive.read(entry), bytes(legacy))

            placement_payloads = [
                json.loads((first_output / "package" / item["path"]).read_text())
                for item in manifest["placementSets"]
            ]
            all_ground = [item for payload in placement_payloads for item in payload["groundItems"]]
            self.assertEqual(1, len(all_ground))
            self.assertEqual(
                (41, 32767, 0, 3, 90),
                (
                    all_ground[0]["itemId"],
                    all_ground[0]["position"]["x"],
                    all_ground[0]["position"]["y"],
                    all_ground[0]["amount"],
                    all_ground[0]["respawnSeconds"],
                ),
            )
            all_npcs = [item for payload in placement_payloads for item in payload["npcs"]]
            self.assertEqual(
                ({"x": 1, "y": 1}, {"x": 5, "y": 5}),
                (
                    all_npcs[0]["roamBounds"]["minimum"],
                    all_npcs[0]["roamBounds"]["maximum"],
                ),
            )
            serialized = b"".join(
                path.read_bytes() for path in first_output.rglob("*") if path.is_file()
            ).decode("utf-8", errors="ignore")
            for source in source_roots:
                self.assertNotIn(str(source), serialized)

    def test_composition_record_and_format_failures_are_closed(self):
        with tempfile.TemporaryDirectory(prefix="packed-conversion-record-errors-") as temp:
            base = Path(temp)
            target = self.fixture(base / "base")
            source, report, _ = self.discover_and_copy(target, base / "base")
            target_before = tree_bytes(target)

            def duplicate_record(case_source: Path):
                path = case_source / "server/maps/placements/boundary-base.json"
                value = json.loads(path.read_text())
                value["boundaries"].append(dict(value["boundaries"][0]))
                write_json(path, value)

            def bad_removal(case_source: Path):
                path = case_source / "server/maps/placements/boundary-removal.json"
                value = json.loads(path.read_text())
                value["boundary_removals"][0]["direction"] = 3
                write_json(path, value)

            def missing_coverage(case_source: Path):
                path = case_source / "server/maps/placements/boundary-base.json"
                value = json.loads(path.read_text())
                value["boundaries"][0]["pos"] = legacy_point(48, 0)
                write_json(path, value)

            def invalid_definition(case_source: Path):
                path = case_source / "server/maps/placements/boundary-base.json"
                value = json.loads(path.read_text())
                value["boundaries"][0]["id"] = 999
                write_json(path, value)

            def malformed_record(case_source: Path):
                path = case_source / "server/maps/placements/scenery-base.json"
                value = json.loads(path.read_text())
                del value["sceneries"][0]["direction"]
                write_json(path, value)

            def coordinate_out_of_range(case_source: Path):
                path = case_source / "server/maps/placements/ground-base.json"
                value = json.loads(path.read_text())
                value["ground_items"][0]["pos"] = legacy_point(32768, 1888)
                write_json(path, value)

            def unsupported_encoding(case_source: Path):
                path = case_source / CONFIG
                value = json.loads(path.read_text())
                value["placements"][0]["encoding"] = "packed-boundary-locations-v2"
                write_json(path, value)

            def base_precedence_collision(case_source: Path):
                config_path = case_source / CONFIG
                config = json.loads(config_path.read_text())
                overlay = next(
                    item for item in config["placements"] if item["role"] == "boundary-overlay"
                )
                overlay["kind"] = "base"
                write_json(config_path, config)

            def removal_before_base(case_source: Path):
                config_path = case_source / CONFIG
                config = json.loads(config_path.read_text())
                placements = config["placements"]
                removal = next(
                    item for item in placements if item["role"] == "boundary-removal"
                )
                placements.remove(removal)
                placements.insert(0, removal)
                for index, item in enumerate(placements):
                    item["compositionOrder"] = index
                write_json(config_path, config)

            mutations = {
                "duplicate-record": duplicate_record,
                "unmatched-removal": bad_removal,
                "missing-terrain": missing_coverage,
                "invalid-definition": invalid_definition,
                "malformed-record": malformed_record,
                "coordinate-edge": coordinate_out_of_range,
                "unsupported-encoding": unsupported_encoding,
                "base-precedence-collision": base_precedence_collision,
                "ambiguous-source-order": removal_before_base,
            }
            for name, mutate in mutations.items():
                with self.subTest(name=name):
                    case = base / name
                    case.mkdir()
                    case_source, case_report = self.clone_source_case(source, report, case)
                    mutate(case_source)
                    self.refresh_report(case_source, case_report)
                    self.assert_failed_without_publication(
                        case_source, case_report, case / "output"
                    )
                    self.assertEqual(target_before, tree_bytes(target))

    def test_terrain_alias_definition_and_zip_failures_are_closed(self):
        with tempfile.TemporaryDirectory(prefix="packed-conversion-terrain-errors-") as temp:
            base = Path(temp)
            target = self.fixture(base / "base")
            source, report, _ = self.discover_and_copy(target, base / "base")
            target_before = tree_bytes(target)

            variants = {
                "coordinate-alias": {
                    "entries": [*TERRAIN_ENTRIES, "h0x048y037"],
                },
                "undefined-terrain-definition": {"overlay": 3},
                "unsupported-zip-record": {"directory_entry": True},
                "malformed-sector": {"length": 127},
            }
            for name, options in variants.items():
                with self.subTest(name=name):
                    case = base / name
                    case.mkdir()
                    case_source, case_report = self.clone_source_case(source, report, case)
                    server = case_source / "server/maps/active.orsc"
                    client = case_source / "client/maps/active.orsc"
                    write_archive(server, **options)
                    shutil.copyfile(server, client)
                    self.refresh_report(case_source, case_report)
                    result = self.assert_failed_without_publication(
                        case_source, case_report, case / "output"
                    )
                    if name == "undefined-terrain-definition":
                        self.assertIn("definition", result.stderr.lower())
                    self.assertEqual(target_before, tree_bytes(target))

    def test_isolation_existing_output_and_operational_data_are_preserved(self):
        with tempfile.TemporaryDirectory(prefix="packed-conversion-isolation-") as temp:
            base = Path(temp)
            target = self.fixture(base / "fixture")
            source, report, _ = self.discover_and_copy(target, base / "fixture")
            target_before = tree_bytes(target)
            workspace = base / "existing-workspace"
            (workspace / "working").mkdir(parents=True)
            (workspace / "working/creator-state.bin").write_bytes(b"preserve exactly\n")
            workspace_before = tree_bytes(workspace)

            live_output = base / "live-target-output"
            live = self.run_conversion(target, report, live_output)
            self.assertEqual(3, live.returncode, live.stderr)
            self.assertFalse(live_output.exists())

            extra_source, extra_report = self.clone_source_case(
                source, report, base / "extra-operational"
            )
            operational = extra_source / "server/ipbans.txt"
            operational.parent.mkdir(parents=True, exist_ok=True)
            operational.write_text("generated operational state\n", encoding="utf-8")
            self.assert_failed_without_publication(
                extra_source,
                extra_report,
                base / "extra-operational/output",
                expected="extra",
            )

            existing_output = base / "existing-output"
            existing_output.mkdir()
            marker = existing_output / "do-not-replace.txt"
            marker.write_text("existing output\n", encoding="utf-8")
            existing_before = tree_bytes(existing_output)
            refused = self.run_conversion(source, report, existing_output)
            self.assertEqual(3, refused.returncode, refused.stderr)
            self.assertEqual(existing_before, tree_bytes(existing_output))

            self.assertEqual(target_before, tree_bytes(target))
            self.assertEqual(workspace_before, tree_bytes(workspace))

    def test_reported_target_and_canonical_aliases_are_rejected(self):
        with tempfile.TemporaryDirectory(prefix="packed-conversion-target-boundary-") as temp:
            base = Path(temp)
            target = self.fixture(base / "fixture")
            source, report, _ = self.discover_and_copy(target, base / "fixture")
            target_before = tree_bytes(target)
            source_before = tree_bytes(source)

            inside_target = target / "conversion-result"
            direct = self.run_conversion(source, report, inside_target)
            self.assertEqual(3, direct.returncode, direct.stderr)
            self.assertFalse(inside_target.exists())
            self.assertEqual(target_before, tree_bytes(target))
            self.assertEqual(source_before, tree_bytes(source))
            self.assertFalse(list(target.glob(".conversion-result.staging-*")))

            alias_parent = base / "target-parent-alias"
            try:
                alias_parent.symlink_to(target.parent, target_is_directory=True)
            except OSError:
                return

            aliased_target = alias_parent / target.name
            aliased_output = aliased_target / "conversion-result"
            output_alias = self.run_conversion(source, report, aliased_output)
            self.assertEqual(3, output_alias.returncode, output_alias.stderr)
            self.assertFalse(aliased_output.exists())

            source_alias_output = base / "source-alias-output"
            source_alias = self.run_conversion(aliased_target, report, source_alias_output)
            self.assertEqual(3, source_alias.returncode, source_alias.stderr)
            self.assertFalse(source_alias_output.exists())
            self.assertFalse(list(base.glob(".source-alias-output.staging-*")))
            self.assertEqual(target_before, tree_bytes(target))
            self.assertEqual(source_before, tree_bytes(source))

    def test_injected_failures_and_id_collisions_publish_nothing(self):
        with tempfile.TemporaryDirectory(prefix="packed-conversion-injected-") as temp:
            base = Path(temp)
            target = self.fixture(base / "fixture")
            source, report, _ = self.discover_and_copy(target, base / "fixture")
            target_before = tree_bytes(target)
            source_before = tree_bytes(source)
            workspace = base / "existing-workspace"
            workspace.mkdir()
            (workspace / "state.json").write_text('{"preserve":true}\n', encoding="utf-8")
            workspace_before = tree_bytes(workspace)

            for mode in (
                "stage-created",
                "package-written",
                "package-validated",
                "before-publish",
                "tamper-before-publish",
                "extra-directory-before-publish",
                "tamper-terrain-and-manifest",
                "tamper-placement-id-and-manifest",
                "id-collision",
                "aggregate-limit",
            ):
                with self.subTest(mode=mode):
                    output = base / f"output-{mode}"
                    result = self.run_injected(source, report, output, mode)
                    self.assertEqual(3, result.returncode, result.stderr)
                    if mode in (
                        "tamper-terrain-and-manifest",
                        "tamper-placement-id-and-manifest",
                    ):
                        self.assertIn("fingerprint", result.stderr.lower())
                    if mode == "aggregate-limit":
                        self.assertIn(
                            "cumulative packed placement inputs", result.stderr.lower()
                        )
                    self.assertFalse(output.exists())
                    self.assertFalse(list(base.glob(f".{output.name}.staging-*")))
                    self.assertEqual(target_before, tree_bytes(target))
                    self.assertEqual(source_before, tree_bytes(source))
                    self.assertEqual(workspace_before, tree_bytes(workspace))


if __name__ == "__main__":
    unittest.main()
