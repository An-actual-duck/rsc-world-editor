package com.openrsc.worldbuilder;

import java.nio.ByteBuffer;
import java.util.Arrays;

/** Content-neutral raw-layered-sector-v1 decoder/encoder contract. */
final class WorldBuilderRawLayeredTerrainCodec {
	static final int SECTOR_SIZE = 48;
	static final int TILE_BYTES = 10;
	static final int BYTE_COUNT = SECTOR_SIZE * SECTOR_SIZE * TILE_BYTES;

	private WorldBuilderRawLayeredTerrainCodec() {
	}

	static void requireDecodable(byte[] payload) throws WorldBuilderContractException {
		if (payload == null || payload.length != BYTE_COUNT) {
			throw invalid("Raw layered terrain payload is not exactly " + BYTE_COUNT + " bytes.");
		}
		ByteBuffer source = ByteBuffer.wrap(payload);
		ByteBuffer reversed = ByteBuffer.allocate(BYTE_COUNT);
		while (source.hasRemaining()) {
			reversed.put(source.get()); // elevation
			reversed.put(source.get()); // ground texture
			reversed.put(source.get()); // ground overlay
			reversed.put(source.get()); // roof texture
			reversed.put(source.get()); // vertical wall
			reversed.put(source.get()); // horizontal wall
			reversed.putInt(source.getInt()); // diagonal wall
		}
		if (!Arrays.equals(payload, reversed.array())) {
			throw invalid("Raw layered terrain decoder could not reproduce its input exactly.");
		}
	}

	private static WorldBuilderContractException invalid(String message) {
		return new WorldBuilderContractException(WorldBuilderErrorCodes.UNSUPPORTED_FORMAT,
			"validate-layered-package", "", false, message,
			"Use an exact raw-layered-sector-v1 payload accepted by the isolated runtime contract.");
	}
}
