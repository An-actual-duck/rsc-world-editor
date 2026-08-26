package com.openrsc.worldbuilder;

/** Canonical pinned-runtime void terrain used for new layered world space. */
final class WorldBuilderCanonicalVoidTerrain {
	static final String WORLD_SPACE = "global";
	static final int GROUND_TEXTURE = 1;
	static final int GROUND_OVERLAY = 8;
	static final int GROUND_OVERLAY_DEFINITION_ID = GROUND_OVERLAY - 1;
	static final int VISIBLE_FLOOR_COLOR = 0;
	static final int VISIBLE_FLOOR_OVERLAY = 0;

	private WorldBuilderCanonicalVoidTerrain() {
	}

	static byte[] tile() {
		return new byte[] {
			0, 0, (byte)GROUND_TEXTURE, (byte)GROUND_OVERLAY, 0, 0, 0, 0, 0, 0, 0
		};
	}

	static byte[] sector() {
		byte[] tile = tile();
		byte[] result = new byte[WorldBuilderRawLayeredTerrainCodec.BYTE_COUNT];
		for (int offset = 0; offset < result.length;
			offset += WorldBuilderRawLayeredTerrainCodec.TILE_BYTES) {
			System.arraycopy(tile, 0, result, offset, tile.length);
		}
		return result;
	}

	static byte[] sectorWithVisibleFloorPatch(int centerX, int centerY) {
		if (centerX < 1 || centerX >= WorldBuilderRawLayeredTerrainCodec.SECTOR_SIZE - 1
			|| centerY < 1
			|| centerY >= WorldBuilderRawLayeredTerrainCodec.SECTOR_SIZE - 1) {
			throw new IllegalArgumentException(
				"Visible floor patch center must be inside the sector edge");
		}
		byte[] result = sector();
		for (int x = centerX - 1; x <= centerX + 1; x++) {
			for (int y = centerY - 1; y <= centerY + 1; y++) {
				int offset = (x * WorldBuilderRawLayeredTerrainCodec.SECTOR_SIZE + y)
					* WorldBuilderRawLayeredTerrainCodec.TILE_BYTES;
				result[offset + 2] = (byte)VISIBLE_FLOOR_COLOR;
				result[offset + 3] = (byte)VISIBLE_FLOOR_OVERLAY;
			}
		}
		return result;
	}

	static byte[] visibleFloorSector() {
		byte[] result = sector();
		for (int offset = 0; offset < result.length;
			offset += WorldBuilderRawLayeredTerrainCodec.TILE_BYTES) {
			result[offset + 2] = (byte)VISIBLE_FLOOR_COLOR;
			result[offset + 3] = (byte)VISIBLE_FLOOR_OVERLAY;
		}
		return result;
	}
}
