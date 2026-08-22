package com.openrsc.worldbuilder;

import java.nio.ByteBuffer;
import java.util.Arrays;

/** Exact frozen-v1 and unsigned-16 v2 raw layered terrain codec. */
final class WorldBuilderRawLayeredTerrainCodec {
	static final String V1_ENCODING = "raw-layered-sector-v1";
	static final String V2_ENCODING = "raw-layered-sector-v2-u16";
	static final int SECTOR_SIZE = 48;
	static final int TILE_COUNT = SECTOR_SIZE * SECTOR_SIZE;
	static final int V1_TILE_BYTES = 10;
	static final int V2_TILE_BYTES = 11;
	/** Editable packages always use v2. */
	static final int TILE_BYTES = V2_TILE_BYTES;
	static final int BYTE_COUNT = TILE_COUNT * V2_TILE_BYTES;
	static final int V1_BYTE_COUNT = TILE_COUNT * V1_TILE_BYTES;

	private WorldBuilderRawLayeredTerrainCodec() {
	}

	static void requireDecodable(byte[] payload) throws WorldBuilderContractException {
		requireDecodable(payload, V2_ENCODING);
	}

	static boolean supports(String encoding) {
		return V1_ENCODING.equals(encoding) || V2_ENCODING.equals(encoding);
	}

	static boolean isWide(String encoding) {
		if (!supports(encoding)) throw new IllegalArgumentException(
			"Unsupported raw layered terrain encoding: " + encoding);
		return V2_ENCODING.equals(encoding);
	}

	static int tileBytes(String encoding) {
		return isWide(encoding) ? V2_TILE_BYTES : V1_TILE_BYTES;
	}

	static int byteCount(String encoding) {
		return TILE_COUNT * tileBytes(encoding);
	}

	static void requireDecodable(byte[] payload, String encoding)
		throws WorldBuilderContractException {
		if (!supports(encoding)) throw invalid(
			"Raw layered terrain encoding is unsupported: " + encoding + ".");
		int expected = byteCount(encoding);
		if (payload == null || payload.length != expected) {
			throw invalid("Raw layered terrain payload for " + encoding
				+ " is not exactly " + expected + " bytes.");
		}
		ByteBuffer source = ByteBuffer.wrap(payload);
		ByteBuffer reversed = ByteBuffer.allocate(expected);
		while (source.hasRemaining()) {
			if (isWide(encoding)) reversed.putShort(source.getShort()); // elevation
			else reversed.put(source.get()); // elevation
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

	static byte[] promoteV1(byte[] payload) throws WorldBuilderContractException {
		requireDecodable(payload, V1_ENCODING);
		byte[] result = new byte[BYTE_COUNT];
		for (int tile = 0; tile < TILE_COUNT; tile++) {
			int source = tile * V1_TILE_BYTES;
			int target = tile * V2_TILE_BYTES;
			result[target] = 0;
			System.arraycopy(payload, source, result, target + 1, V1_TILE_BYTES);
		}
		return result;
	}

	static byte[] toV1(byte[] payload, String encoding, int level,
		int sectorX, int sectorY) throws WorldBuilderContractException {
		requireDecodable(payload, encoding);
		if (V1_ENCODING.equals(encoding)) return payload.clone();
		StringBuilder blocked = new StringBuilder();
		for (int tile = 0; tile < TILE_COUNT; tile++) {
			int offset = tile * V2_TILE_BYTES;
			int elevation = ((payload[offset] & 0xff) << 8)
				| (payload[offset + 1] & 0xff);
			if (elevation <= 255) continue;
			int localX = tile / SECTOR_SIZE;
			int localY = tile % SECTOR_SIZE;
			if (blocked.length() > 0) blocked.append("; ");
			blocked.append("level ").append(level).append(" tile (")
				.append((long)sectorX * SECTOR_SIZE + localX).append(',')
				.append((long)sectorY * SECTOR_SIZE + localY).append(")=")
				.append(elevation);
		}
		if (blocked.length() > 0) {
			throw new WorldBuilderContractException(
				WorldBuilderErrorCodes.CONVERSION_BLOCKED, "export-legacy-terrain", "", false,
				"Legacy terrain export cannot represent these elevations: " + blocked + ".",
				"Lower every reported tile to 0..255 or export the exact layered v2 package.");
		}
		byte[] result = new byte[V1_BYTE_COUNT];
		for (int tile = 0; tile < TILE_COUNT; tile++) {
			int source = tile * V2_TILE_BYTES;
			int target = tile * V1_TILE_BYTES;
			System.arraycopy(payload, source + 1, result, target, V1_TILE_BYTES);
		}
		return result;
	}

	static int elevation(byte[] payload, String encoding, int tile) {
		int offset = tile * tileBytes(encoding);
		return isWide(encoding)
			? ((payload[offset] & 0xff) << 8) | (payload[offset + 1] & 0xff)
			: payload[offset] & 0xff;
	}

	static byte[] tile(byte[] payload, String encoding, int tile) {
		int width = tileBytes(encoding);
		int offset = tile * width;
		return Arrays.copyOfRange(payload, offset, offset + width);
	}

	static byte[] encodeV2Tile(int elevation, int texture, int overlay, int roof,
		int verticalWall, int horizontalWall, int diagonalWall) {
		if (elevation < 0 || elevation > 65535) throw new IllegalArgumentException(
			"Elevation must be an unsigned 16-bit value.");
		ByteBuffer output = ByteBuffer.allocate(V2_TILE_BYTES);
		output.putShort((short)elevation);
		output.put((byte)texture).put((byte)overlay).put((byte)roof);
		output.put((byte)verticalWall).put((byte)horizontalWall);
		output.putInt(diagonalWall);
		return output.array();
	}

	private static WorldBuilderContractException invalid(String message) {
		return new WorldBuilderContractException(WorldBuilderErrorCodes.UNSUPPORTED_FORMAT,
			"validate-layered-package", "", false, message,
			"Use an exact raw-layered-sector-v1 or raw-layered-sector-v2-u16 payload accepted by the isolated runtime contract.");
	}
}
