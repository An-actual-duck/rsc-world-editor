package com.openrsc.worldbuilder;

import java.util.Arrays;

/** Exact legacy-ORSC to raw-layered terrain payload codec. */
final class WorldBuilderPackedTerrainCodec {
	static final String CONVERSION_PROFILE_ID = "exact-packed-to-layered-v1";
	static final String OUTPUT_ENCODING = "raw-layered-sector-v1";
	static final int SECTOR_SIZE = 48;
	static final int TILE_BYTES = 10;
	static final int BYTE_COUNT = SECTOR_SIZE * SECTOR_SIZE * TILE_BYTES;

	private WorldBuilderPackedTerrainCodec() {
	}

	static byte[] toLayered(byte[] legacy) throws WorldBuilderContractException {
		return swapWalls(legacy, "legacy packed");
	}

	static byte[] toLegacy(byte[] layered) throws WorldBuilderContractException {
		return swapWalls(layered, "raw layered");
	}

	static void requireExactReverse(byte[] legacy, byte[] layered)
		throws WorldBuilderContractException {
		if (!Arrays.equals(legacy, toLegacy(layered))) {
			throw new WorldBuilderContractException(
				WorldBuilderErrorCodes.CONVERSION_BLOCKED, "convert-packed", "", false,
				"Terrain reverse conversion did not reproduce the exact source bytes.",
				"Do not publish the conversion; inspect the adapter terrain codec.");
		}
	}

	private static byte[] swapWalls(byte[] source, String label)
		throws WorldBuilderContractException {
		if (source == null || source.length != BYTE_COUNT) {
			throw new WorldBuilderContractException(
				WorldBuilderErrorCodes.UNSUPPORTED_FORMAT, "convert-packed", "", false,
				"The " + label + " terrain sector is not exactly " + BYTE_COUNT + " bytes.",
				"Restore one exact 48x48x10-byte terrain sector.");
		}
		byte[] result = source.clone();
		for (int offset = 0; offset < result.length; offset += TILE_BYTES) {
			byte horizontal = result[offset + 4];
			result[offset + 4] = result[offset + 5];
			result[offset + 5] = horizontal;
		}
		return result;
	}
}
