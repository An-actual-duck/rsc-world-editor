package com.openrsc.worldbuilder;

/** Canonical pinned-runtime void terrain used for new layered world space. */
final class WorldBuilderCanonicalVoidTerrain {
	static final String WORLD_SPACE = "global";
	static final int GROUND_TEXTURE = 1;
	static final int GROUND_OVERLAY = 8;
	static final int GROUND_OVERLAY_DEFINITION_ID = GROUND_OVERLAY - 1;

	private WorldBuilderCanonicalVoidTerrain() {
	}

	static byte[] sector() {
		byte[] tile = new byte[] {
			0, (byte)GROUND_TEXTURE, (byte)GROUND_OVERLAY, 0, 0, 0, 0, 0, 0, 0
		};
		byte[] result = new byte[WorldBuilderRawLayeredTerrainCodec.BYTE_COUNT];
		for (int offset = 0; offset < result.length;
			offset += WorldBuilderRawLayeredTerrainCodec.TILE_BYTES) {
			System.arraycopy(tile, 0, result, offset, tile.length);
		}
		return result;
	}
}
