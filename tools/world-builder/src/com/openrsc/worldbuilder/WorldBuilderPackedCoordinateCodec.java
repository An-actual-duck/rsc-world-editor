package com.openrsc.worldbuilder;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Exact coordinate/name codec owned by the initial packed conversion adapter. */
final class WorldBuilderPackedCoordinateCodec {
	static final String COORDINATE_MAPPING_ID = "legacy-packed-y-v1";
	static final String TERRAIN_NAME_ID = "legacy-terrain-sector-name-v1";
	static final int LEVEL_STRIDE = 944;
	static final int LEGACY_PLANE_COUNT = 4;
	static final int MAX_PACKED_Y = LEVEL_STRIDE * LEGACY_PLANE_COUNT - 1;
	static final int MAX_PACKED_X = Short.MAX_VALUE;
	static final int ARCHIVE_SECTOR_X_OFFSET = 48;
	static final int ARCHIVE_SECTOR_Y_OFFSET = 37;

	private static final Pattern TERRAIN_ENTRY =
		Pattern.compile("h([0-3])x([0-9]+)y([0-9]+)");

	private WorldBuilderPackedCoordinateCodec() {
	}

	static Coordinate decode(int packedX, int packedY)
		throws WorldBuilderContractException {
		if (packedX < 0 || packedX > MAX_PACKED_X
			|| packedY < 0 || packedY > MAX_PACKED_Y) {
			throw blocked("Packed placement coordinate is outside the exact legacy range: "
				+ packedX + "," + packedY + ".");
		}
		int plane = Math.floorDiv(packedY, LEVEL_STRIDE);
		return new Coordinate(packedX, Math.floorMod(packedY, LEVEL_STRIDE),
			levelForPlane(plane));
	}

	static PackedCoordinate encode(Coordinate coordinate)
		throws WorldBuilderContractException {
		if (coordinate == null || coordinate.x < 0 || coordinate.x > MAX_PACKED_X
			|| coordinate.y < 0 || coordinate.y >= LEVEL_STRIDE) {
			throw blocked("Layered placement coordinate cannot be represented exactly in packed form.");
		}
		int plane = planeForLevel(coordinate.level);
		return new PackedCoordinate(coordinate.x,
			plane * LEVEL_STRIDE + coordinate.y);
	}

	static Sector decodeTerrainEntry(String entryName)
		throws WorldBuilderContractException {
		Matcher matcher = TERRAIN_ENTRY.matcher(entryName == null ? "" : entryName);
		if (!matcher.matches()) {
			throw blocked("Unsupported packed terrain entry name: " + entryName + ".");
		}
		int plane = parse(matcher.group(1), "plane", entryName);
		int archiveX = parse(matcher.group(2), "archive sector X", entryName);
		int archiveY = parse(matcher.group(3), "archive sector Y", entryName);
		int sectorX;
		int sectorY;
		try {
			sectorX = Math.subtractExact(archiveX, ARCHIVE_SECTOR_X_OFFSET);
			sectorY = Math.subtractExact(archiveY, ARCHIVE_SECTOR_Y_OFFSET);
		} catch (ArithmeticException overflow) {
			throw blocked("Packed terrain coordinates overflow signed conversion: "
				+ entryName + ".");
		}
		Sector result = new Sector(entryName, plane, archiveX, archiveY,
			levelForPlane(plane), sectorX, sectorY);
		if (!entryName.equals(encodeTerrainEntry(result))) {
			throw blocked("Packed terrain entry is not canonically encoded: " + entryName + ".");
		}
		return result;
	}

	static String encodeTerrainEntry(Sector sector)
		throws WorldBuilderContractException {
		if (sector == null) throw blocked("Packed terrain sector identity is missing.");
		int plane = planeForLevel(sector.level);
		int archiveX;
		int archiveY;
		try {
			archiveX = Math.addExact(sector.sectorX, ARCHIVE_SECTOR_X_OFFSET);
			archiveY = Math.addExact(sector.sectorY, ARCHIVE_SECTOR_Y_OFFSET);
		} catch (ArithmeticException overflow) {
			throw blocked("Layered terrain coordinates overflow packed archive conversion.");
		}
		if (archiveX < 0 || archiveY < 0) {
			throw blocked("Layered terrain sector falls outside the non-negative packed archive grid.");
		}
		return "h" + plane + "x" + archiveX + "y" + archiveY;
	}

	static int levelForPlane(int plane) throws WorldBuilderContractException {
		switch (plane) {
			case 0: return 0;
			case 1: return 1;
			case 2: return 2;
			case 3: return -1;
				default: throw blocked("Unsupported packed terrain plane: " + plane + ".");
			}
	}

	static int planeForLevel(int level) throws WorldBuilderContractException {
		switch (level) {
			case 0: return 0;
			case 1: return 1;
			case 2: return 2;
			case -1: return 3;
			default: throw blocked("Signed level is not representable by the packed codec: "
					+ level + ".");
			}
	}

	private static int parse(String value, String label, String entry)
		throws WorldBuilderContractException {
		try {
			return Integer.parseInt(value);
		} catch (NumberFormatException failure) {
			throw blocked("Packed terrain " + label + " exceeds signed 32-bit range in "
				+ entry + ".");
		}
	}

	private static WorldBuilderContractException blocked(String message) {
		return new WorldBuilderContractException(WorldBuilderErrorCodes.CONVERSION_BLOCKED,
			"convert-packed", "", false, message,
			"Use only exact values accepted by " + COORDINATE_MAPPING_ID + ".");
	}

	static final class Coordinate {
		final int x;
		final int y;
		final int level;

		Coordinate(int x, int y, int level) {
			this.x = x;
			this.y = y;
			this.level = level;
		}

		String key() {
			return level + ":" + x + ":" + y;
		}
	}

	static final class PackedCoordinate {
		final int x;
		final int y;

		PackedCoordinate(int x, int y) {
			this.x = x;
			this.y = y;
		}
	}

	static final class Sector implements Comparable<Sector> {
		final String legacyEntry;
		final int legacyPlane;
		final int archiveSectorX;
		final int archiveSectorY;
		final int level;
		final int sectorX;
		final int sectorY;

		Sector(String legacyEntry, int legacyPlane, int archiveSectorX,
			int archiveSectorY, int level, int sectorX, int sectorY) {
			this.legacyEntry = legacyEntry;
			this.legacyPlane = legacyPlane;
			this.archiveSectorX = archiveSectorX;
			this.archiveSectorY = archiveSectorY;
			this.level = level;
			this.sectorX = sectorX;
			this.sectorY = sectorY;
		}

		String key() {
			return level + ":" + sectorX + ":" + sectorY;
		}

		@Override
		public int compareTo(Sector other) {
			int result = Integer.compare(level, other.level);
			if (result == 0) result = Integer.compare(sectorX, other.sectorX);
			if (result == 0) result = Integer.compare(sectorY, other.sectorY);
			return result;
		}
	}
}
