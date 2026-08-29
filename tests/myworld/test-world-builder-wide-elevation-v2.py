#!/usr/bin/env python3
"""Exact Editor codec and promotion coverage for runtime wide elevation v2."""

import subprocess
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
SOURCE_ROOT = ROOT / "tools/world-builder/src"


class WideElevationV2Test(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        subprocess.run([str(ROOT / "scripts/build-tools.sh")], cwd=ROOT, check=True)

    def test_boundaries_widths_promotion_and_downgrade_report(self):
        source = r'''
package com.openrsc.worldbuilder;

import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.lang.reflect.*;
import java.util.*;

public final class WideElevationEditorProbe {
  private static void ok(boolean value, String message) {
    if (!value) throw new AssertionError(message);
  }

  public static void main(String[] args) throws Exception {
    byte[] legacy = new byte[WorldBuilderRawLayeredTerrainCodec.V1_BYTE_COUNT];
    for (int tile = 0; tile < WorldBuilderRawLayeredTerrainCodec.TILE_COUNT; tile++) {
      int offset = tile * 10;
      legacy[offset] = (byte)(tile % 2 == 0 ? 0 : 255);
      legacy[offset + 1] = 10;
      legacy[offset + 2] = 11;
      legacy[offset + 3] = 12;
      legacy[offset + 4] = 13;
      legacy[offset + 5] = 14;
      legacy[offset + 6] = 0x10;
      legacy[offset + 7] = 0x20;
      legacy[offset + 8] = 0x30;
      legacy[offset + 9] = 0x40;
    }
    byte[] promoted = WorldBuilderRawLayeredTerrainCodec.promoteV1(legacy);
    ok(promoted.length == 48 * 48 * 11, "v2 width");
    ok(Arrays.equals(legacy, WorldBuilderRawLayeredTerrainCodec.toV1(
      promoted, WorldBuilderRawLayeredTerrainCodec.V2_ENCODING, 0, 0, 0)),
      "lossless promotion");

    int[] elevations = {0, 255, 256, 12000, 65535};
    for (int tile = 0; tile < elevations.length; tile++) {
      byte[] encoded = WorldBuilderRawLayeredTerrainCodec.encodeV2Tile(
        elevations[tile], 10, 11, 12, 13, 14, 0x10203040);
      System.arraycopy(encoded, 0, promoted, tile * 11, 11);
      ok(WorldBuilderRawLayeredTerrainCodec.elevation(
        promoted, WorldBuilderRawLayeredTerrainCodec.V2_ENCODING, tile)
        == elevations[tile], "elevation " + elevations[tile]);
      ok((promoted[tile * 11 + 2] & 255) == 10
        && (promoted[tile * 11 + 6] & 255) == 14,
        "non-elevation fields " + tile);
    }
    WorldBuilderRawLayeredTerrainCodec.requireDecodable(
      promoted, WorldBuilderRawLayeredTerrainCodec.V2_ENCODING);
    for (int width : new int[] {23039, 23041, 25343, 25345}) {
      boolean refused = false;
      try {
        WorldBuilderRawLayeredTerrainCodec.requireDecodable(
          new byte[width], width < 25000
            ? WorldBuilderRawLayeredTerrainCodec.V1_ENCODING
            : WorldBuilderRawLayeredTerrainCodec.V2_ENCODING);
      } catch (WorldBuilderContractException expected) { refused = true; }
      ok(refused, "malformed width " + width);
    }
    try {
      WorldBuilderRawLayeredTerrainCodec.toV1(promoted,
        WorldBuilderRawLayeredTerrainCodec.V2_ENCODING, 7, -3, 4);
      throw new AssertionError("wide downgrade was accepted");
    } catch (WorldBuilderContractException blocked) {
      String message = blocked.getMessage();
      ok(message.contains("level 7 tile (-144,194)=256"), "256 report");
      ok(message.contains("level 7 tile (-144,195)=12000"), "mountain report");
      ok(message.contains("level 7 tile (-144,196)=65535"), "65535 report");
    }

    Path root = Paths.get(args[0]);
    Files.createDirectories(root);
    Path terrain = root.resolve("terrain.raw");
    Files.write(terrain, legacy);
    Map<String,Object> declaration = new LinkedHashMap<String,Object>();
    declaration.put("encoding", WorldBuilderRawLayeredTerrainCodec.V1_ENCODING);
    declaration.put("level", Long.valueOf(0));
    declaration.put("path", "terrain.raw");
    declaration.put("sectorX", Long.valueOf(0));
    declaration.put("sectorY", Long.valueOf(0));
    declaration.put("sha256", WorldBuilderHashes.sha256(terrain));
    declaration.put("worldSpace", "global");
    Map<String,Object> manifest = new LinkedHashMap<String,Object>();
    manifest.put("terrainSectors", Arrays.<Object>asList(declaration));
    Files.write(root.resolve("manifest.json"),
      WorldBuilderJsonDocuments.pretty(manifest).getBytes("UTF-8"));
    ok(WorldBuilderWideElevationPromotion.promoteInPlace(root) == 1,
      "package promotion count");
    ok(WorldBuilderWideElevationPromotion.promoteInPlace(root) == 0,
      "promotion idempotence");
    ok(Files.size(terrain) == 48L * 48L * 11L, "promoted package width");
    ok(new String(Files.readAllBytes(root.resolve("manifest.json")), "UTF-8")
      .contains(WorldBuilderRawLayeredTerrainCodec.V2_ENCODING),
      "promoted manifest encoding");

    Path journal = root.resolve("wide-journal.tsv");
    String base = String.join("\n",
      "world-builder-layered-draft-v6-u16-elevation",
      "base-manifest-sha256\t" + String.join("", Collections.nCopies(64, "a")),
      "level-count\t0", "tile-count\t1", "sector-count\t0",
      "scenery-count\t0", "npc-count\t0", "ground-item-count\t0",
      "tile\t0\t120\t648\t65535\t10\t11\t12\t13\t14\t270544960") + "\n";
    Files.write(journal, base.getBytes(StandardCharsets.US_ASCII));
    Method read = WorldBuilderLayeredTerrainDraftJournal.class
      .getDeclaredMethod("read", Path.class);
    read.setAccessible(true);
    Object parsed = read.invoke(null, journal);
    Field tilesField = parsed.getClass().getDeclaredField("tiles");
    tilesField.setAccessible(true);
    Object edit = ((List<?>)tilesField.get(parsed)).get(0);
    Field elevationField = edit.getClass().getDeclaredField("elevation");
    elevationField.setAccessible(true);
    ok(elevationField.getInt(edit) == 65535, "v6 journal elevation");

    String npcJournal = String.join("\n",
      "world-builder-layered-draft-v7-npc-respawn",
      "base-manifest-sha256\t" + String.join("", Collections.nCopies(64, "b")),
      "level-count\t0", "tile-count\t0", "sector-count\t0",
      "scenery-count\t0", "npc-count\t1", "ground-item-count\t0",
      "npc\tupsert\t0\t120\t648\tbuilder.npc.1\t2\t119\t647\t121\t649\t45") + "\n";
    Files.write(journal, npcJournal.getBytes(StandardCharsets.US_ASCII));
    parsed = read.invoke(null, journal);
    Field npcsField = parsed.getClass().getDeclaredField("npcs");
    npcsField.setAccessible(true);
    Object npcEdit = ((List<?>)npcsField.get(parsed)).get(0);
    Field respawnField = npcEdit.getClass().getDeclaredField("respawnSeconds");
    respawnField.setAccessible(true);
    ok(respawnField.getInt(npcEdit) == 45, "v7 NPC respawn");

    String legacyJournal = base.replace(
      "world-builder-layered-draft-v6-u16-elevation",
      "world-builder-layered-draft-v5");
    Files.write(journal, legacyJournal.getBytes(StandardCharsets.US_ASCII));
    boolean legacyRefused = false;
    try { read.invoke(null, journal); }
    catch (InvocationTargetException expected) { legacyRefused = true; }
    ok(legacyRefused, "v5 journal byte range remains frozen");
  }
}
'''
        with tempfile.TemporaryDirectory(prefix="wide-elevation-editor-") as temp:
            temp_root = Path(temp)
            source_path = temp_root / "WideElevationEditorProbe.java"
            source_path.write_text(source, encoding="utf-8")
            classes = temp_root / "classes"
            classes.mkdir()
            subprocess.run(
                [
                    "javac", "-source", "8", "-target", "8",
                    "-cp", str(ROOT / "output/world-builder-tools/classes"),
                    "-d", str(classes), str(source_path),
                ],
                cwd=ROOT,
                check=True,
                capture_output=True,
                text=True,
            )
            package = temp_root / "package"
            result = subprocess.run(
                [
                    "java", "-cp",
                    f"{classes}:{ROOT / 'output/world-builder-tools/classes'}",
                    "com.openrsc.worldbuilder.WideElevationEditorProbe",
                    str(package),
                ],
                cwd=ROOT,
                text=True,
                capture_output=True,
            )
            self.assertEqual(0, result.returncode, result.stderr)


if __name__ == "__main__":
    unittest.main()
