package com.openrsc.worldbuilder;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Strict packed terrain and static-placement inspection for the compiled legacy profile. */
final class WorldBuilderPackedMap {
	private static final int RAW_SECTOR_BYTES = 48 * 48 * 10;
	private static final int MAX_SECTORS = 65536;
	private static final int MAX_RECORDS = 65536;
	private static final Pattern TERRAIN = Pattern.compile("h([0-3])x([0-9]+)y([0-9]+)");

	final int sectorCount;
	final long boundaryCount;
	final long groundItemCount;
	final long npcCount;
	final long sceneryCount;
	final List<WorldBuilderReadOnlyTarget.FileState> files;

	private WorldBuilderPackedMap(
		int sectorCount,
		long boundaryCount,
		long groundItemCount,
		long npcCount,
		long sceneryCount,
		List<WorldBuilderReadOnlyTarget.FileState> files) {
		this.sectorCount = sectorCount;
		this.boundaryCount = boundaryCount;
		this.groundItemCount = groundItemCount;
		this.npcCount = npcCount;
		this.sceneryCount = sceneryCount;
		this.files = files;
	}

	static WorldBuilderPackedMap inspect(
		WorldBuilderReadOnlyTarget target,
		WorldBuilderAdaptiveConfiguration configuration,
		WorldBuilderCompatibilityEvidence.DefinitionCatalog definitions)
		throws WorldBuilderContractException {
		WorldBuilderReadOnlyTarget.FileState server = target.requiredState(
			"server-terrain", configuration.serverMapRelativePath);
		WorldBuilderReadOnlyTarget.FileState client = target.requiredState(
			"client-terrain", configuration.clientMapRelativePath);
		if (server.size != client.size || !server.sha256.equals(client.sha256)) {
			throw problem(WorldBuilderErrorCodes.MAP_MISMATCH,
				configuration.serverMapRelativePath,
				"Server and client packed terrain archives are not byte-identical.",
				"Install one exact active packed archive on the server and client.");
		}
		Set<String> terrain = validateArchive(
			target.requiredFile(configuration.serverMapRelativePath),
			configuration.serverMapRelativePath);
		List<WorldBuilderReadOnlyTarget.FileState> files =
			new ArrayList<WorldBuilderReadOnlyTarget.FileState>();
		files.add(server);
		files.add(client);

		Map<String,Map<String,Placement>> effective =
			new LinkedHashMap<String,Map<String,Placement>>();
		for (String family : Arrays.asList("boundary", "ground-item", "npc", "scenery")) {
			effective.put(family, new LinkedHashMap<String,Placement>());
		}
		Set<String> declaredFamilies = new HashSet<String>();
		for (WorldBuilderAdaptiveConfiguration.PlacementSource source
			: configuration.placements) {
			declaredFamilies.add(source.family);
			String expectedEncoding = "packed-" + source.family + "-"
				+ ("removal".equals(source.kind) ? "removals" : "locations") + "-v1";
			if (!expectedEncoding.equals(source.encoding)) {
				throw problem(WorldBuilderErrorCodes.UNSUPPORTED_FORMAT, source.relativePath,
					"Packed placement encoding does not match its family/kind: " + source.encoding,
					"Use the exact compiled packed placement encoding.");
			}
			WorldBuilderReadOnlyTarget.FileState state = target.requiredState(
				"placement." + source.role, source.relativePath);
			files.add(state);
			List<Placement> records = parse(target, source, definitions);
			Map<String,Placement> family = effective.get(source.family);
			Set<String> sourceKeys = new HashSet<String>();
			for (Placement placement : records) {
				if (!sourceKeys.add(placement.key)) {
					throw problem(WorldBuilderErrorCodes.MALFORMED_SERVER, source.relativePath,
						"Packed placement source contains duplicate effective slots.",
						"Remove duplicate records without discarding distinct placements.");
				}
				if ("removal".equals(source.kind)) {
					Placement removed = family.remove(placement.key);
					if (removed == null || placement.definitionId >= 0
						&& placement.definitionId != removed.definitionId) {
						throw problem(WorldBuilderErrorCodes.MALFORMED_SERVER, source.relativePath,
							"Packed removal does not match an earlier effective placement.",
							"Correct composition order and exact removal identity.");
					}
				} else if ("base".equals(source.kind)
					&& family.put(placement.key, placement) != null) {
					throw problem(WorldBuilderErrorCodes.MALFORMED_SERVER, source.relativePath,
						"Packed base placement sources collide before overlay precedence.",
						"Resolve the duplicate or declare a reviewed overlay source.");
				} else if ("overlay".equals(source.kind)) {
					family.put(placement.key, placement);
				}
			}
		}
		if (!declaredFamilies.equals(new HashSet<String>(
			Arrays.asList("boundary", "ground-item", "npc", "scenery")))) {
			throw problem(WorldBuilderErrorCodes.CAPABILITY_MISMATCH,
				configuration.relativePath,
				"Packed configuration does not declare all four static placement families.",
				"Declare complete boundary, ground-item, NPC, and scenery composition inputs.");
		}

		for (Map<String,Placement> family : effective.values()) {
				for (Placement placement : family.values()) {
					requireCoverage(terrain, placement, placement.path);
				if (placement.minimum != null && placement.maximum != null) {
					requireCoverageRectangle(
						terrain, placement.minimum, placement.maximum, placement.path);
				}
			}
		}
		return new WorldBuilderPackedMap(terrain.size(),
			effective.get("boundary").size(), effective.get("ground-item").size(),
			effective.get("npc").size(), effective.get("scenery").size(), files);
	}

	static Set<String> validateArchive(Path archive, String relative)
		throws WorldBuilderContractException {
		Set<String> sectors = new HashSet<String>();
		Set<String> names = new HashSet<String>();
		Map<String,String> normalizedNames = new HashMap<String,String>();
		try (ZipFile zip = new ZipFile(archive.toFile())) {
			Enumeration<? extends ZipEntry> entries = zip.entries();
			while (entries.hasMoreElements()) {
				ZipEntry entry = entries.nextElement();
				if (entry.isDirectory()) continue;
				if (sectors.size() >= MAX_SECTORS) {
					throw problem(WorldBuilderErrorCodes.INVENTORY_LIMIT_EXCEEDED, relative,
						"Packed terrain contains more than 65,536 sectors.",
						"Use a bounded archive supported by the packed adapter.");
				}
				Matcher matcher = TERRAIN.matcher(entry.getName());
				if (!matcher.matches() || !names.add(entry.getName())) {
					throw problem(WorldBuilderErrorCodes.UNSUPPORTED_FORMAT, relative,
						"Packed terrain contains an unsupported or duplicate entry: "
							+ entry.getName(),
						"Use unique h<plane>x<x>y<y> raw sector entries only.");
				}
				int plane;
				int archiveX;
				int archiveY;
				try {
					plane = Integer.parseInt(matcher.group(1));
					archiveX = Integer.parseInt(matcher.group(2));
					archiveY = Integer.parseInt(matcher.group(3));
				} catch (NumberFormatException outOfRange) {
					throw problem(WorldBuilderErrorCodes.UNSUPPORTED_FORMAT, relative,
						"Packed terrain entry coordinate is outside 32-bit range: " + entry.getName(),
						"Use bounded legacy archive coordinates.");
				}
				int count = 0;
				try (InputStream input = zip.getInputStream(entry)) {
					byte[] buffer = new byte[8192];
					int read;
					while ((read = input.read(buffer)) >= 0) {
						if (read == 0) continue;
						count += read;
						if (count > RAW_SECTOR_BYTES) break;
					}
				}
				if (count != RAW_SECTOR_BYTES) {
					throw problem(WorldBuilderErrorCodes.UNSUPPORTED_FORMAT, relative,
						"Packed terrain entry is not exactly 23,040 raw bytes: " + entry.getName(),
						"Restore the exact raw packed sector payload.");
				}
				String coordinate = WorldBuilderPackedCoordinateCodec.levelForPlane(plane)
					+ ":" + (archiveX - 48) + ":" + (archiveY - 37);
				String previousName = normalizedNames.put(coordinate, entry.getName());
				if (previousName != null) {
					throw problem(WorldBuilderErrorCodes.UNSUPPORTED_FORMAT, relative,
						"Packed terrain entry " + entry.getName()
							+ " duplicates normalized sector coordinates from " + previousName + ".",
						"Keep exactly one spelling for each h<plane>x<x>y<y> sector coordinate.");
				}
				sectors.add(coordinate);
			}
		} catch (WorldBuilderContractException refusal) {
			throw refusal;
		} catch (IOException malformed) {
			throw problem(WorldBuilderErrorCodes.UNSUPPORTED_FORMAT, relative,
				"Packed terrain archive cannot be read safely: " + malformed.getMessage(),
				"Restore a valid bounded ZIP terrain archive.");
		}
		if (sectors.isEmpty()) {
			throw problem(WorldBuilderErrorCodes.UNSUPPORTED_FORMAT, relative,
				"Packed terrain archive contains no sectors.",
				"Select an active nonempty packed map archive.");
		}
		return sectors;
	}

	private static List<Placement> parse(
		WorldBuilderReadOnlyTarget target,
		WorldBuilderAdaptiveConfiguration.PlacementSource source,
		WorldBuilderCompatibilityEvidence.DefinitionCatalog definitions)
		throws WorldBuilderContractException {
		Map<String,Object> root = target.readObject(source.relativePath);
		String rootKey = rootKey(source.family, source.kind);
		if (root.size() != 1 || !root.containsKey(rootKey)) {
			throw problem(WorldBuilderErrorCodes.MALFORMED_SERVER, source.relativePath,
				"Packed placement document must contain only array " + rootKey + ".",
				"Use the exact compiled packed placement document shape.");
		}
		List<?> records = array(root.get(rootKey), source.relativePath, rootKey);
		List<Placement> result = new ArrayList<Placement>(records.size());
		for (Object raw : records) {
			Map<String,Object> record = object(raw, source.relativePath);
			if ("boundary".equals(source.family)) {
				result.add(boundary(record, source, definitions));
			} else if ("ground-item".equals(source.family)) {
				result.add(groundItem(record, source, definitions));
			} else if ("npc".equals(source.family)) {
				result.add(npc(record, source, definitions));
			} else {
				result.add(scenery(record, source, definitions));
			}
		}
		return result;
	}

	private static Placement boundary(
		Map<String,Object> value,
		WorldBuilderAdaptiveConfiguration.PlacementSource source,
		WorldBuilderCompatibilityEvidence.DefinitionCatalog definitions)
		throws WorldBuilderContractException {
		boolean removal = "removal".equals(source.kind);
		exact(value, source.relativePath, removal
			? new String[] {"direction", "pos"}
			: new String[] {"direction", "id", "pos"});
		Point point = legacyPoint(value.get("pos"), source.relativePath);
		int direction = nonnegative(value, "direction", source.relativePath);
		if (direction > 3) invalid(source.relativePath, "Boundary direction is outside 0..3.");
		int id = removal ? -1 : nonnegative(value, "id", source.relativePath);
		if (!removal) definitions.require("boundary", id, source.relativePath);
		return new Placement(point.level + ":" + point.x + ":" + point.y + ":" + direction,
			id, point.level, point.x, point.y, source.relativePath, null, null);
	}

	private static Placement groundItem(
		Map<String,Object> value,
		WorldBuilderAdaptiveConfiguration.PlacementSource source,
		WorldBuilderCompatibilityEvidence.DefinitionCatalog definitions)
		throws WorldBuilderContractException {
		boolean removal = "removal".equals(source.kind);
		exact(value, source.relativePath, removal
			? new String[] {"id", "pos"}
			: new String[] {"amount", "id", "pos", "respawn"});
		Point point = legacyPoint(value.get("pos"), source.relativePath);
		int id = nonnegative(value, "id", source.relativePath);
		if (!removal) {
			int amount = nonnegative(value, "amount", source.relativePath);
			int respawn = nonnegative(value, "respawn", source.relativePath);
			if (amount < 1 || respawn > 86400) {
				invalid(source.relativePath, "Ground-item amount or respawn is invalid.");
			}
			definitions.require("ground-item", id, source.relativePath);
		}
		return new Placement(point.level + ":" + point.x + ":" + point.y, id,
			point.level, point.x, point.y, source.relativePath, null, null);
	}

	private static Placement npc(
		Map<String,Object> value,
		WorldBuilderAdaptiveConfiguration.PlacementSource source,
		WorldBuilderCompatibilityEvidence.DefinitionCatalog definitions)
		throws WorldBuilderContractException {
		exact(value, source.relativePath, "id", "max", "min", "start");
		int id = nonnegative(value, "id", source.relativePath);
		Point start = legacyPoint(value.get("start"), source.relativePath);
		Point minimum = legacyPoint(value.get("min"), source.relativePath);
		Point maximum = legacyPoint(value.get("max"), source.relativePath);
		if (minimum.level != start.level || maximum.level != start.level
			|| minimum.x > start.x || start.x > maximum.x
			|| minimum.y > start.y || start.y > maximum.y
			|| (long)maximum.x - minimum.x > 128L
			|| (long)maximum.y - minimum.y > 128L) {
			invalid(source.relativePath, "Packed NPC roaming bounds are invalid.");
		}
		if (!"removal".equals(source.kind)) definitions.require("npc", id, source.relativePath);
		return new Placement(start.level + ":" + id + ":" + start.x + ":" + start.y, id,
			start.level, start.x, start.y, source.relativePath, minimum, maximum);
	}

	private static Placement scenery(
		Map<String,Object> value,
		WorldBuilderAdaptiveConfiguration.PlacementSource source,
		WorldBuilderCompatibilityEvidence.DefinitionCatalog definitions)
		throws WorldBuilderContractException {
		boolean removal = "removal".equals(source.kind);
		exact(value, source.relativePath, removal
			? new String[] {"pos"}
			: new String[] {"direction", "id", "pos"});
		Point point = legacyPoint(value.get("pos"), source.relativePath);
		int id = -1;
		if (!removal) {
			id = nonnegative(value, "id", source.relativePath);
			int direction = nonnegative(value, "direction", source.relativePath);
			if (direction > 8) invalid(source.relativePath,
				"Scenery direction is outside the legacy range 0..8.");
			definitions.require("scenery", id, source.relativePath);
		}
		return new Placement(point.level + ":" + point.x + ":" + point.y, id,
			point.level, point.x, point.y, source.relativePath, null, null);
	}

	private static void requireCoverage(Set<String> terrain, Placement placement, String path)
		throws WorldBuilderContractException {
		String sector = placement.level + ":" + Math.floorDiv(placement.x, 48)
			+ ":" + Math.floorDiv(placement.y, 48);
		if (!terrain.contains(sector)) {
			invalid(path, "Packed placement coordinate is outside terrain coverage.");
		}
	}

	private static void requireCoverageRectangle(
		Set<String> terrain, Point minimum, Point maximum, String path)
		throws WorldBuilderContractException {
		if (minimum.level != maximum.level) {
			invalid(path, "Packed NPC roaming bounds cross signed levels.");
		}
		for (long x = Math.floorDiv(minimum.x, 48);
			x <= Math.floorDiv(maximum.x, 48); x++) {
			for (long y = Math.floorDiv(minimum.y, 48);
				y <= Math.floorDiv(maximum.y, 48); y++) {
				if (!terrain.contains(minimum.level + ":" + x + ":" + y)) {
					invalid(path, "Packed NPC roaming bounds extend beyond terrain coverage.");
				}
			}
		}
	}

	private static String rootKey(String family, String kind) {
		boolean removal = "removal".equals(kind);
		if ("boundary".equals(family)) return removal ? "boundary_removals" : "boundaries";
		if ("ground-item".equals(family)) {
			return removal ? "ground_item_removals" : "ground_items";
		}
		if ("npc".equals(family)) return removal ? "npc_removals" : "npclocs";
		return removal ? "scenery_removals" : "sceneries";
	}

	private static Point legacyPoint(Object raw, String path)
		throws WorldBuilderContractException {
		Map<String,Object> value = object(raw, path);
		exact(value, path, "X", "Y");
		int packedX = signed(value, "X", path);
		int packedY = signed(value, "Y", path);
		try {
			WorldBuilderPackedCoordinateCodec.Coordinate decoded =
				WorldBuilderPackedCoordinateCodec.decode(packedX, packedY);
			return new Point(decoded.level, decoded.x, decoded.y);
		} catch (WorldBuilderContractException unsupported) {
			invalid(path, unsupported.getMessage());
			throw new AssertionError("invalid always throws");
		}
	}

	private static Map<String,Object> object(Object raw, String path)
		throws WorldBuilderContractException {
		if (!(raw instanceof Map)) invalid(path, "Packed placement record is not an object.");
		@SuppressWarnings("unchecked") Map<String,Object> result = (Map<String,Object>)raw;
		return result;
	}

	private static List<?> array(Object raw, String path, String label)
		throws WorldBuilderContractException {
		if (!(raw instanceof List) || ((List<?>)raw).size() > MAX_RECORDS) {
			throw problem(WorldBuilderErrorCodes.INVENTORY_LIMIT_EXCEEDED, path,
				"Packed placement array is missing or exceeds 65,536 records: " + label,
				"Use a bounded exact static placement source.");
		}
		return (List<?>)raw;
	}

	private static void exact(Map<String,Object> value, String path, String... keys)
		throws WorldBuilderContractException {
		Set<String> expected = new HashSet<String>(Arrays.asList(keys));
		if (value.size() != expected.size() || !value.keySet().equals(expected)) {
			invalid(path, "Packed placement record has missing or unexpected fields.");
		}
	}

	private static int nonnegative(Map<String,Object> value, String key, String path)
		throws WorldBuilderContractException {
		int result = signed(value, key, path);
		if (result < 0) invalid(path, "Packed placement value is negative: " + key);
		return result;
	}

	private static int signed(Map<String,Object> value, String key, String path)
		throws WorldBuilderContractException {
		Object raw = value.get(key);
		if (!(raw instanceof Long) || ((Long)raw).longValue() < Integer.MIN_VALUE
			|| ((Long)raw).longValue() > Integer.MAX_VALUE) {
			invalid(path, "Packed placement field is not a signed 32-bit integer: " + key);
		}
		return ((Long)raw).intValue();
	}

	private static void invalid(String path, String message)
		throws WorldBuilderContractException {
		throw problem(WorldBuilderErrorCodes.MALFORMED_SERVER, path, message,
			"Correct the exact packed placement source and retry discovery.");
	}

	private static WorldBuilderContractException problem(
		String code, String path, String message, String nextStep) {
		return WorldBuilderReadOnlyTarget.problem(code, path, message, nextStep);
	}

	private static final class Point {
		final int level;
		final int x;
		final int y;
		Point(int level, int x, int y) {
			this.level = level;
			this.x = x;
			this.y = y;
		}
	}

	private static final class Placement {
		final String key;
		final int definitionId;
		final int level;
		final int x;
		final int y;
		final String path;
		final Point minimum;
		final Point maximum;

		Placement(String key, int definitionId, int level, int x, int y, String path,
			Point minimum, Point maximum) {
			this.key = key;
			this.definitionId = definitionId;
			this.level = level;
			this.x = x;
			this.y = y;
			this.path = path;
			this.minimum = minimum;
			this.maximum = maximum;
		}
	}
}
