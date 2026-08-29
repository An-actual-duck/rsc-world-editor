package com.openrsc.worldbuilder;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Strict portable region contracts with readable v1/v2 and NPC-respawn v3 snapshots. */
final class WorldBuilderRegionContracts {
	static final long VERSION = 1L;
	static final long SNAPSHOT_VERSION = 3L;
	static final String ZERO_HASH =
		"0000000000000000000000000000000000000000000000000000000000000000";
	static final int MAX_MARKERS = 256;
	static final int MAX_LEVELS = 64;
	static final int MAX_TILES = 65536;
	static final int MAX_PLACEMENTS = 65536;
	static final int MAX_DEPENDENCIES = 65536;

	private WorldBuilderRegionContracts() {
	}

	static Selection selection(Map<String,Object> root)
		throws WorldBuilderContractException {
		String op = "validate-region-selection";
		identity(root, "world-builder-region-selection", op);
		exact(root, op, "schemaVersion", "manifestType", "worldSpace", "markers",
			"levels", "selectionFingerprintSha256");
		String worldSpace = identifier(root, "worldSpace", op);
		List<?> rawMarkers = array(root.get("markers"), op, "markers", 3, MAX_MARKERS);
		List<Point> markers = new ArrayList<Point>();
		Set<String> coordinates = new HashSet<String>();
		for (int index = 0; index < rawMarkers.size(); index++) {
			Map<String,Object> marker = object(rawMarkers.get(index), op, "marker");
			exact(marker, op, "marker", "x", "y");
			long number = integer(marker, "marker", op);
			if (number != index + 1L) invalid(op,
				"Selection markers must be numbered consecutively from marker 1.");
			Point point = new Point(signed(marker, "x", op), signed(marker, "y", op));
			if (!coordinates.add(point.x + ":" + point.y)) invalid(op,
				"Selection markers cannot repeat a coordinate.");
			markers.add(point);
		}
		List<?> rawLevels = array(root.get("levels"), op, "levels", 1, MAX_LEVELS);
		List<Integer> levels = new ArrayList<Integer>();
		Integer previous = null;
		for (Object raw : rawLevels) {
			if (!(raw instanceof Long)) invalid(op, "Selection level is not an integer.");
			long value = ((Long)raw).longValue();
			if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) invalid(op,
				"Selection level is outside the signed 32-bit range.");
			int level = (int)value;
			if (previous != null && previous.intValue() >= level) invalid(op,
				"Selection levels must be unique and ascending.");
			levels.add(Integer.valueOf(level));
			previous = Integer.valueOf(level);
		}
		requireFingerprint(root, "selectionFingerprintSha256", op);
		Geometry geometry = Geometry.create(markers, op);
		if ((long)geometry.width * geometry.height * levels.size() > MAX_TILES * 4L) {
			invalid(op, "Selection bounding inventory exceeds its bounded search area.");
		}
		return new Selection(root, worldSpace, markers, levels, geometry);
	}

	static Snapshot snapshot(Map<String,Object> root)
		throws WorldBuilderContractException {
		String op = "validate-region-snapshot";
		long snapshotVersion = version(root, "world-builder-region-snapshot", op,
			VERSION, SNAPSHOT_VERSION);
		exact(root, op, "schemaVersion", "manifestType", "snapshotId", "name",
			"worldSpace", "anchor", "polygon", "levels", "placements",
			"footprintBoundaryReports", "catalog", "sourceEvidence", "dependencies",
			"snapshotFingerprintSha256");
		String id = hash(root, "snapshotId", op);
		text(root, "name", op, 1, 128);
		String worldSpace = identifier(root, "worldSpace", op);
		Map<String,Object> anchor = object(root.get("anchor"), op, "anchor");
		exact(anchor, op, "level", "x", "y");
		int anchorLevel = signed(anchor, "level", op);
		signed(anchor, "x", op); signed(anchor, "y", op);
		List<?> polygon = array(root.get("polygon"), op, "polygon", 3, MAX_MARKERS);
		List<Point> relative = new ArrayList<Point>();
		for (int index = 0; index < polygon.size(); index++) {
			Map<String,Object> marker = object(polygon.get(index), op, "polygon marker");
			exact(marker, op, "marker", "xOffset", "yOffset");
			if (integer(marker, "marker", op) != index + 1L) invalid(op,
				"Snapshot polygon markers are not consecutive from marker 1.");
			relative.add(new Point(signed(marker, "xOffset", op),
				signed(marker, "yOffset", op)));
		}
		if (relative.get(0).x != 0 || relative.get(0).y != 0) invalid(op,
			"Snapshot marker 1 must be the zero-offset paste anchor.");
		Geometry relativeGeometry = Geometry.create(relative, op);
		if ((long)relativeGeometry.width * relativeGeometry.height
			> MAX_TILES * 4L) invalid(op,
			"Snapshot polygon bounding inventory exceeds its bounded search area.");
		Set<String> expectedTiles = new HashSet<String>();
		for (long x = relativeGeometry.minimumX; x <= (long)relativeGeometry.maximumX; x++) {
			for (long y = relativeGeometry.minimumY; y <= (long)relativeGeometry.maximumY; y++) {
				if (relativeGeometry.owns((int)x, (int)y)) expectedTiles.add(x + ":" + y);
			}
		}
		if (expectedTiles.isEmpty()) invalid(op, "Snapshot polygon owns no tile centers.");
		List<?> levels = array(root.get("levels"), op, "levels", 1, MAX_LEVELS);
		int tileCount = 0;
		Integer previousLevel = null;
		Set<Integer> levelOffsets = new HashSet<Integer>();
		for (Object raw : levels) {
			Map<String,Object> level = object(raw, op, "snapshot level");
			exact(level, op, "levelOffset", "tiles");
			int offset = signed(level, "levelOffset", op);
			if (previousLevel != null && previousLevel.intValue() >= offset) invalid(op,
				"Snapshot level offsets must be unique and ascending.");
			previousLevel = Integer.valueOf(offset);
			levelOffsets.add(Integer.valueOf(offset));
			List<?> tiles = array(level.get("tiles"), op, "tiles", 1, MAX_TILES);
			String previousTile = null;
			Set<String> actualTiles = new HashSet<String>();
			for (Object rawTile : tiles) {
				Map<String,Object> tile = object(rawTile, op, "tile");
				exact(tile, op, "xOffset", "yOffset", "elevation", "groundTexture",
					"groundOverlay", "roofTexture", "verticalWall", "horizontalWall",
					"diagonalWall", "canonicalVoid");
				int x = signed(tile, "xOffset", op);
				int y = signed(tile, "yOffset", op);
				long elevation = integer(tile, "elevation", op);
				if (elevation < 0L
					|| elevation > (snapshotVersion == VERSION ? 255L : 65535L)) invalid(op,
					"Snapshot elevation is outside its schema range.");
				for (String field : Arrays.asList("groundTexture",
					"groundOverlay", "roofTexture", "verticalWall", "horizontalWall")) {
					long value = integer(tile, field, op);
					if (value < 0L || value > 255L) invalid(op,
						"Snapshot byte terrain field is outside 0..255.");
				}
				signed(tile, "diagonalWall", op);
				boolean canonicalVoid = bool(tile, "canonicalVoid", op);
				if (canonicalVoid != isCanonicalVoid(tile, op)) invalid(op,
					"Snapshot canonical-void flag disagrees with exact terrain fields.");
				String key = ordered(x) + ":" + ordered(y);
				if (previousTile != null && previousTile.compareTo(key) >= 0) invalid(op,
					"Snapshot tiles are duplicated or not canonically ordered.");
				previousTile = key;
				actualTiles.add(x + ":" + y);
				if (++tileCount > MAX_TILES) invalid(op,
					"Snapshot exceeds its total tile limit.");
			}
			if (!actualTiles.equals(expectedTiles)) invalid(op,
				"Every snapshot level must contain exactly the polygon-owned tiles.");
		}
		Map<String,Object> placements = object(root.get("placements"), op, "placements");
		exact(placements, op, "boundaries", "groundItems", "npcs", "scenery");
		int placementCount = 0;
		Set<String> placementIds = new HashSet<String>();
		Set<String> expectedReports = new HashSet<String>();
		Set<String> requiredDefinitions = new HashSet<String>();
		for (String family : Arrays.asList("boundaries", "groundItems", "npcs", "scenery")) {
			List<?> records = array(placements.get(family), op, family, 0, MAX_PLACEMENTS);
			String previous = null;
			for (Object raw : records) {
				Map<String,Object> record = object(raw, op, family + " record");
				String key = canonical(record);
				if (previous != null && previous.compareTo(key) >= 0) invalid(op,
					"Snapshot placement records are duplicated or not canonically ordered.");
				previous = key;
				if (++placementCount > MAX_PLACEMENTS) invalid(op,
					"Snapshot exceeds its total placement limit.");
				validatePlacementRecord(
					family, record, op, snapshotVersion >= SNAPSHOT_VERSION);
				String placementId = identifier(record, "placementId", op);
				if (!placementIds.add(placementId)) invalid(op,
					"Snapshot placement IDs must be unique across all families.");
				int levelOffset = signed(record, "levelOffset", op);
				if (!levelOffsets.contains(Integer.valueOf(levelOffset))) invalid(op,
					"Snapshot placement references a level offset absent from terrain.");
				Point owner = placementOwner(family, record, op);
				if (!relativeGeometry.owns(owner.x, owner.y)) invalid(op,
					"Snapshot placement owner lies outside the selection polygon.");
				expectedReports.add(singular(family) + "\u0000" + placementId);
				requiredDefinitions.add(singular(family) + ":"
					+ placementDefinitionId(family, record, op));
			}
		}
		List<?> reports = array(root.get("footprintBoundaryReports"), op,
			"footprintBoundaryReports", 0, MAX_PLACEMENTS);
		String previousReport = null;
		for (Object raw : reports) {
			Map<String,Object> report = object(raw, op, "footprint report");
			exact(report, op, "family", "placementId", "ownership", "crossesBoundary",
				"detail");
			identifier(report, "family", op); identifier(report, "placementId", op);
			String ownership = string(report, "ownership", op);
			if (!("anchor-point".equals(ownership) || "boundary-origin".equals(ownership))) {
				invalid(op, "Footprint report ownership rule is unsupported.");
			}
			bool(report, "crossesBoundary", op); text(report, "detail", op, 1, 256);
			String key = string(report, "family", op) + "\u0000"
				+ string(report, "placementId", op);
			if (previousReport != null && previousReport.compareTo(key) >= 0) invalid(op,
				"Footprint reports are duplicated or not canonically ordered.");
			previousReport = key;
			if (!expectedReports.remove(key)) invalid(op,
				"Footprint report does not bind one captured placement.");
		}
		if (!expectedReports.isEmpty()) invalid(op,
			"Every captured placement requires one footprint-boundary report.");
		Map<String,Object> catalog = object(root.get("catalog"), op, "catalog");
		exact(catalog, op, "catalogId", "sha256");
		identifier(catalog, "catalogId", op); hash(catalog, "sha256", op);
		Map<String,Object> source = object(root.get("sourceEvidence"), op, "sourceEvidence");
		exact(source, op, "projectId", "packageSchemaId", "coordinateModel",
			"workingSha256", "runtimeSha256");
		uuid(source, "projectId", op); identifier(source, "packageSchemaId", op);
		identifier(source, "coordinateModel", op); hash(source, "workingSha256", op);
		hash(source, "runtimeSha256", op);
		validateDependencies(root.get("dependencies"), op,
			string(catalog, "catalogId", op), string(catalog, "sha256", op),
			requiredDefinitions);
		requireDualFingerprint(root, "snapshotId", "snapshotFingerprintSha256", op);
		if (!id.equals(string(root, "snapshotFingerprintSha256", op))) invalid(op,
			"Snapshot ID and content fingerprint must match.");
		return new Snapshot(root, id, worldSpace, anchorLevel, tileCount, placementCount);
	}

	static void bundleManifest(Map<String,Object> root, String snapshotHash,
		long snapshotSize) throws WorldBuilderContractException {
		String op = "validate-region-bundle";
		identity(root, "world-builder-region-bundle", op);
		exact(root, op, "schemaVersion", "manifestType", "formatId", "snapshotId",
			"files", "bundleFingerprintSha256");
		if (!"portable-region-bundle-v1".equals(identifier(root, "formatId", op))) {
			invalid(op, "Region bundle format identity is unsupported.");
		}
		hash(root, "snapshotId", op);
		List<?> files = array(root.get("files"), op, "files", 1, 1);
		Map<String,Object> file = object(files.get(0), op, "bundle file");
		exact(file, op, "role", "relativePath", "size", "sha256");
		if (!"snapshot".equals(identifier(file, "role", op))
			|| !"snapshot.json".equals(relative(file, "relativePath", op))
			|| integer(file, "size", op) != snapshotSize
			|| !snapshotHash.equals(hash(file, "sha256", op))) {
			invalid(op, "Bundle inventory does not exactly bind snapshot.json.");
		}
		requireFingerprint(root, "bundleFingerprintSha256", op);
	}

	static void compatibility(Map<String,Object> root)
		throws WorldBuilderContractException {
		String op = "validate-region-compatibility";
		identity(root, "world-builder-region-compatibility-report", op);
		exact(root, op, "schemaVersion", "manifestType", "snapshotId", "projectId",
			"compatible", "issues", "reportFingerprintSha256");
		hash(root, "snapshotId", op); uuid(root, "projectId", op);
		boolean compatible = bool(root, "compatible", op);
		List<?> issues = array(root.get("issues"), op, "issues", 0, 1024);
		if (compatible != issues.isEmpty()) invalid(op,
			"Compatibility state and issue inventory disagree.");
		String previousIssue = null;
		for (Object raw : issues) {
			Map<String,Object> issue = object(raw, op, "compatibility issue");
			exact(issue, op, "code", "dependency", "message");
			identifier(issue, "code", op); text(issue, "dependency", op, 0, 256);
			text(issue, "message", op, 1, 512);
			String canonical = canonical(issue);
			if (previousIssue != null && previousIssue.compareTo(canonical) >= 0) invalid(op,
				"Compatibility issues are duplicated or not canonically ordered.");
			previousIssue = canonical;
		}
		requireFingerprint(root, "reportFingerprintSha256", op);
	}

	static void operationPlan(Map<String,Object> root)
		throws WorldBuilderContractException {
		String op = "validate-region-operation-plan";
		identity(root, "world-builder-region-operation-plan", op);
		exact(root, op, "schemaVersion", "manifestType", "operation", "snapshotId",
			"projectId", "workingBeforeSha256", "destinationAnchor", "files",
			"placementIdMappings", "collisions", "overwriteRequired", "blocked",
			"planFingerprintSha256");
		String operation = string(root, "operation", op);
		if (!("cut".equals(operation) || "paste".equals(operation))) invalid(op,
			"Region operation must be cut or paste.");
		hash(root, "snapshotId", op); uuid(root, "projectId", op);
		hash(root, "workingBeforeSha256", op);
		Map<String,Object> anchor = object(root.get("destinationAnchor"), op,
			"destinationAnchor");
		exact(anchor, op, "level", "x", "y");
		signed(anchor, "level", op); signed(anchor, "x", op); signed(anchor, "y", op);
		List<?> files = array(root.get("files"), op, "files", 0, 1024);
		String previous = null;
		for (Object raw : files) {
			Map<String,Object> file = object(raw, op, "planned file");
			exact(file, op, "relativePath", "beforeSha256", "afterSha256");
			String path = relative(file, "relativePath", op);
			hash(file, "beforeSha256", op); hash(file, "afterSha256", op);
			if (previous != null && previous.compareTo(path) >= 0) invalid(op,
				"Planned files are duplicated or not canonically ordered.");
			previous = path;
		}
		List<?> mappings = array(root.get("placementIdMappings"), op,
			"placementIdMappings", 0, MAX_PLACEMENTS);
		String previousMapping = null;
		Set<String> destinationIds = new HashSet<String>();
		for (Object raw : mappings) {
			Map<String,Object> mapping = object(raw, op, "placement ID mapping");
			exact(mapping, op, "family", "sourcePlacementId", "destinationPlacementId");
			String family = identifier(mapping, "family", op);
			String sourceId = identifier(mapping, "sourcePlacementId", op);
			String destinationId = identifier(mapping, "destinationPlacementId", op);
			String key = family + "\u0000" + sourceId;
			if (previousMapping != null && previousMapping.compareTo(key) >= 0) invalid(op,
				"Placement ID mappings are duplicated or not canonically ordered.");
			previousMapping = key;
			if (!destinationIds.add(destinationId)) invalid(op,
				"Destination placement IDs must be unique across all families.");
		}
		if ("cut".equals(operation) && !mappings.isEmpty()) invalid(op,
			"Cut plans cannot contain destination placement ID mappings.");
		List<?> collisions = array(root.get("collisions"), op, "collisions", 0, 65536);
		String previousCollision = null;
		for (Object raw : collisions) {
			Map<String,Object> collision = object(raw, op, "collision");
			exact(collision, op, "kind", "level", "x", "y", "detail");
			identifier(collision, "kind", op); signed(collision, "level", op);
			signed(collision, "x", op); signed(collision, "y", op);
			text(collision, "detail", op, 1, 256);
			String canonical = canonical(collision);
			if (previousCollision != null
				&& previousCollision.compareTo(canonical) >= 0) invalid(op,
				"Collision records are duplicated or not canonically ordered.");
			previousCollision = canonical;
		}
		boolean overwrite = bool(root, "overwriteRequired", op);
		boolean blocked = bool(root, "blocked", op);
		if ("cut".equals(operation) && overwrite) invalid(op,
			"Cut plans cannot require destination overwrite.");
		if (blocked && !files.isEmpty()) invalid(op,
			"Blocked operation plans cannot authorize file mutations.");
		requireFingerprint(root, "planFingerprintSha256", op);
	}

	static String bindFingerprint(Map<String,Object> root, String key) {
		root.put(key, ZERO_HASH);
		String value = WorldBuilderHashes.sha256(
			canonical(root).getBytes(StandardCharsets.UTF_8));
		root.put(key, value);
		return value;
	}

	static String bindDualFingerprint(Map<String,Object> root, String first,
		String second) {
		root.put(first, ZERO_HASH);
		root.put(second, ZERO_HASH);
		String value = WorldBuilderHashes.sha256(
			canonical(root).getBytes(StandardCharsets.UTF_8));
		root.put(first, value);
		root.put(second, value);
		return value;
	}

	private static void validateDependencies(Object raw, String op,
		String catalogId, String catalogHash, Set<String> requiredDefinitions)
		throws WorldBuilderContractException {
		List<?> values = array(raw, op, "dependencies", 1, MAX_DEPENDENCIES);
		String previous = null;
		boolean catalogSeen = false;
		Set<String> definitionsSeen = new HashSet<String>();
		for (Object value : values) {
			Map<String,Object> dependency = object(value, op, "dependency");
			exact(dependency, op, "kind", "family", "logicalId", "numericId",
				"catalogId", "contentSha256", "resolution", "bundled");
			String kind = string(dependency, "kind", op);
			if (!("definition-catalog".equals(kind) || "definition".equals(kind)
				|| "material".equals(kind) || "sprite".equals(kind))) invalid(op,
				"Snapshot dependency kind is unsupported.");
			String family = string(dependency, "family", op);
			if (family.length() > 64) invalid(op, "Dependency family is too long.");
			text(dependency, "logicalId", op, 1, 256);
			long numeric = integer(dependency, "numericId", op);
			if (numeric < -1L || numeric > Integer.MAX_VALUE) invalid(op,
				"Dependency numeric ID is outside its supported range.");
			identifier(dependency, "catalogId", op);
			String content = string(dependency, "contentSha256", op);
			if (!content.isEmpty() && !WorldBuilderBoundedInventory.isHash(content)) invalid(op,
				"Dependency content hash is invalid.");
			String resolution = string(dependency, "resolution", op);
			if (!("catalog".equals(resolution) || "unsupported".equals(resolution))) invalid(op,
				"Dependency resolution is unsupported.");
			if (bool(dependency, "bundled", op)) invalid(op,
				"Region snapshot v1 cannot bundle executable or custom dependencies.");
			String dependencyCatalog = string(dependency, "catalogId", op);
			if (!catalogId.equals(dependencyCatalog)) invalid(op,
				"Dependency catalog identity disagrees with snapshot catalog evidence.");
			if ("definition-catalog".equals(kind)) {
				if (catalogSeen || !"catalog".equals(family) || numeric != -1L
					|| !("catalog:" + catalogId).equals(
						string(dependency, "logicalId", op))
					|| !catalogHash.equals(content) || !"catalog".equals(resolution)) {
					invalid(op, "Definition-catalog dependency is incomplete or duplicated.");
				}
				catalogSeen = true;
			} else if ("definition".equals(kind)) {
				String expected = family + ":" + numeric;
				if (!("catalog:" + catalogId + ":" + expected).equals(
						string(dependency, "logicalId", op))
					|| numeric < 0L || !content.isEmpty()
					|| !"catalog".equals(resolution)
					|| !definitionsSeen.add(expected)) invalid(op,
					"Definition dependency identity or resolution is inconsistent.");
			} else if (numeric != -1L || !content.isEmpty()
				|| !"unsupported".equals(resolution)) {
				invalid(op, "Future material/sprite dependency must fail closed as unsupported.");
			}
			String key = kind + "\u0000" + family + "\u0000"
				+ string(dependency, "logicalId", op);
			if (previous != null && previous.compareTo(key) >= 0) invalid(op,
				"Dependencies are duplicated or not canonically ordered.");
			previous = key;
		}
		if (!catalogSeen || !definitionsSeen.equals(requiredDefinitions)) invalid(op,
			"Snapshot dependency inventory does not exactly cover captured definitions.");
	}

	private static int placementDefinitionId(String family,
		Map<String,Object> record, String op) throws WorldBuilderContractException {
		if ("boundaries".equals(family)) return nonnegative(record, "boundaryId", op);
		if ("groundItems".equals(family)) return nonnegative(record, "itemId", op);
		if ("npcs".equals(family)) return nonnegative(record, "npcId", op);
		return nonnegative(record, "sceneryId", op);
	}

	private static void validatePlacementRecord(String family,
		Map<String,Object> record, String op, boolean npcRespawn)
		throws WorldBuilderContractException {
		if ("boundaries".equals(family)) {
			exact(record, op, "levelOffset", "placementId", "boundaryId", "direction",
				"position");
			nonnegative(record, "boundaryId", op); range(record, "direction", 0, 3, op);
			pointOffset(record.get("position"), op);
		} else if ("groundItems".equals(family)) {
			exact(record, op, "levelOffset", "placementId", "itemId", "amount",
				"respawnSeconds", "position");
			nonnegative(record, "itemId", op); range(record, "amount", 1,
				Integer.MAX_VALUE, op); range(record, "respawnSeconds", 0, 86400, op);
			pointOffset(record.get("position"), op);
		} else if ("npcs".equals(family)) {
			if (npcRespawn) {
				exact(record, op, "levelOffset", "placementId", "npcId",
					"respawnSeconds", "start", "roamBounds");
				range(record, "respawnSeconds", -1, 86400, op);
			} else {
				exact(record, op, "levelOffset", "placementId", "npcId", "start",
					"roamBounds");
			}
			nonnegative(record, "npcId", op); pointOffset(record.get("start"), op);
			Map<String,Object> bounds = object(record.get("roamBounds"), op, "roamBounds");
			exact(bounds, op, "minimum", "maximum");
			Point start = offsetPoint(record.get("start"), op);
			Point minimum = offsetPoint(bounds.get("minimum"), op);
			Point maximum = offsetPoint(bounds.get("maximum"), op);
			if (minimum.x > start.x || start.x > maximum.x
				|| minimum.y > start.y || start.y > maximum.y
				|| (long)maximum.x - minimum.x > 128L
				|| (long)maximum.y - minimum.y > 128L) invalid(op,
				"Snapshot NPC roam bounds are invalid or exceed 128 tiles.");
		} else if ("scenery".equals(family)) {
			exact(record, op, "levelOffset", "placementId", "sceneryId", "direction",
				"position");
			nonnegative(record, "sceneryId", op); range(record, "direction", 0, 8, op);
			pointOffset(record.get("position"), op);
		} else throw new AssertionError(family);
		signed(record, "levelOffset", op); identifier(record, "placementId", op);
	}

	private static void pointOffset(Object raw, String op)
		throws WorldBuilderContractException {
		offsetPoint(raw, op);
	}

	private static Point offsetPoint(Object raw, String op)
		throws WorldBuilderContractException {
		Map<String,Object> point = object(raw, op, "point offset");
		exact(point, op, "xOffset", "yOffset");
		return new Point(signed(point, "xOffset", op), signed(point, "yOffset", op));
	}

	private static Point placementOwner(String family, Map<String,Object> record,
		String op) throws WorldBuilderContractException {
		return offsetPoint(record.get("npcs".equals(family) ? "start" : "position"), op);
	}

	private static String singular(String family) {
		if ("boundaries".equals(family)) return "boundary";
		if ("groundItems".equals(family)) return "ground-item";
		if ("npcs".equals(family)) return "npc";
		if ("scenery".equals(family)) return "scenery";
		throw new AssertionError(family);
	}

	private static boolean isCanonicalVoid(Map<String,Object> tile, String op)
		throws WorldBuilderContractException {
		return integer(tile, "elevation", op) == 0L
			&& integer(tile, "groundTexture", op)
				== WorldBuilderCanonicalVoidTerrain.GROUND_TEXTURE
			&& integer(tile, "groundOverlay", op)
				== WorldBuilderCanonicalVoidTerrain.GROUND_OVERLAY
			&& integer(tile, "roofTexture", op) == 0L
			&& integer(tile, "verticalWall", op) == 0L
			&& integer(tile, "horizontalWall", op) == 0L
			&& integer(tile, "diagonalWall", op) == 0L;
	}

	private static long version(Map<String,Object> root, String type, String op,
		long first, long second) throws WorldBuilderContractException {
		if (!(root.get("schemaVersion") instanceof Long)
			|| !type.equals(root.get("manifestType"))) invalid(op,
			"Region contract identity or schema version is unsupported.");
		long value = ((Long)root.get("schemaVersion")).longValue();
		if (value != first && value != second) invalid(op,
			"Region contract identity or schema version is unsupported.");
		return value;
	}

	private static void identity(Map<String,Object> root, String type, String op)
		throws WorldBuilderContractException {
		if (!(root.get("schemaVersion") instanceof Long)
			|| ((Long)root.get("schemaVersion")).longValue() != VERSION
			|| !type.equals(root.get("manifestType"))) invalid(op,
			"Region contract identity or schema version is unsupported.");
	}

	private static void requireFingerprint(Map<String,Object> root, String key,
		String op) throws WorldBuilderContractException {
		String expected = hash(root, key, op);
		root.put(key, ZERO_HASH);
		String actual = WorldBuilderHashes.sha256(
			canonical(root).getBytes(StandardCharsets.UTF_8));
		root.put(key, expected);
		if (!expected.equals(actual)) invalid(op,
			"Region contract canonical fingerprint does not match its content.");
	}

	private static void requireDualFingerprint(Map<String,Object> root,
		String first, String second, String op) throws WorldBuilderContractException {
		String expected = hash(root, first, op);
		if (!expected.equals(hash(root, second, op))) invalid(op,
			"Region snapshot identity fields disagree.");
		root.put(first, ZERO_HASH); root.put(second, ZERO_HASH);
		String actual = WorldBuilderHashes.sha256(
			canonical(root).getBytes(StandardCharsets.UTF_8));
		root.put(first, expected); root.put(second, expected);
		if (!expected.equals(actual)) invalid(op,
			"Region snapshot canonical fingerprint does not match its content.");
	}

	static String canonical(Object value) {
		return WorldBuilderJsonDocuments.canonical(value);
	}

	static Map<String,Object> object(Object raw, String op, String label)
		throws WorldBuilderContractException {
		if (!(raw instanceof Map)) invalid(op, "Region " + label + " is not an object.");
		@SuppressWarnings("unchecked") Map<String,Object> value = (Map<String,Object>)raw;
		return value;
	}

	static List<?> array(Object raw, String op, String label, int minimum, int maximum)
		throws WorldBuilderContractException {
		if (!(raw instanceof List) || ((List<?>)raw).size() < minimum
			|| ((List<?>)raw).size() > maximum) invalid(op,
			"Region " + label + " array is missing or outside its bound.");
		return (List<?>)raw;
	}

	static void exact(Map<String,Object> value, String op, String... keys)
		throws WorldBuilderContractException {
		Set<String> expected = new HashSet<String>(Arrays.asList(keys));
		if (value.size() != expected.size() || !value.keySet().equals(expected)) {
			invalid(op, "Region contract contains missing or unexpected fields.");
		}
	}

	static String string(Map<String,Object> value, String key, String op)
		throws WorldBuilderContractException {
		Object raw = value.get(key);
		if (!(raw instanceof String)) invalid(op, "Region field is not a string: " + key);
		return (String)raw;
	}

	static String identifier(Map<String,Object> value, String key, String op)
		throws WorldBuilderContractException {
		String result = string(value, key, op);
		if (!result.matches("[A-Za-z0-9][A-Za-z0-9._:+-]{0,127}")) invalid(op,
			"Region identifier is invalid: " + key);
		return result;
	}

	static String hash(Map<String,Object> value, String key, String op)
		throws WorldBuilderContractException {
		String result = string(value, key, op);
		if (!WorldBuilderBoundedInventory.isHash(result)) invalid(op,
			"Region hash is invalid: " + key);
		return result;
	}

	static String relative(Map<String,Object> value, String key, String op)
		throws WorldBuilderContractException {
		return WorldBuilderPortablePath.require(string(value, key, op), op);
	}

	static long integer(Map<String,Object> value, String key, String op)
		throws WorldBuilderContractException {
		Object raw = value.get(key);
		if (!(raw instanceof Long)) invalid(op, "Region field is not an integer: " + key);
		return ((Long)raw).longValue();
	}

	static int signed(Map<String,Object> value, String key, String op)
		throws WorldBuilderContractException {
		long result = integer(value, key, op);
		if (result < Integer.MIN_VALUE || result > Integer.MAX_VALUE) invalid(op,
			"Region signed integer is outside its range: " + key);
		return (int)result;
	}

	static int nonnegative(Map<String,Object> value, String key, String op)
		throws WorldBuilderContractException {
		return range(value, key, 0, Integer.MAX_VALUE, op);
	}

	static int range(Map<String,Object> value, String key, int minimum, int maximum,
		String op) throws WorldBuilderContractException {
		int result = signed(value, key, op);
		if (result < minimum || result > maximum) invalid(op,
			"Region integer is outside its supported range: " + key);
		return result;
	}

	static boolean bool(Map<String,Object> value, String key, String op)
		throws WorldBuilderContractException {
		Object raw = value.get(key);
		if (!(raw instanceof Boolean)) invalid(op, "Region field is not boolean: " + key);
		return ((Boolean)raw).booleanValue();
	}

	static String text(Map<String,Object> value, String key, String op,
		int minimum, int maximum) throws WorldBuilderContractException {
		String result = string(value, key, op);
		if (result.length() < minimum || result.length() > maximum) invalid(op,
			"Region text is outside its supported length: " + key);
		return result;
	}

	static String uuid(Map<String,Object> value, String key, String op)
		throws WorldBuilderContractException {
		String result = string(value, key, op);
		try {
			if (!java.util.UUID.fromString(result).toString().equals(result)) invalid(op,
				"Region UUID is not canonical: " + key);
		} catch (IllegalArgumentException invalid) {
			invalid(op, "Region UUID is invalid: " + key);
		}
		return result;
	}

	static void invalid(String op, String message) throws WorldBuilderContractException {
		throw new WorldBuilderContractException(WorldBuilderErrorCodes.CONTRACT_VALUE_INVALID,
			op, "", false, message,
			"Use one exact, bounded region-snapshot-v1, v2, or v3 contract.");
	}

	private static String ordered(int value) {
		return String.format(java.util.Locale.ROOT, "%010d",
			(long)value - Integer.MIN_VALUE);
	}

	static final class Selection {
		final Map<String,Object> root;
		final String worldSpace;
		final List<Point> markers;
		final List<Integer> levels;
		final Geometry geometry;
		Selection(Map<String,Object> root, String worldSpace, List<Point> markers,
			List<Integer> levels, Geometry geometry) {
			this.root = root; this.worldSpace = worldSpace; this.markers = markers;
			this.levels = levels; this.geometry = geometry;
		}
	}

	static final class Snapshot {
		final Map<String,Object> root;
		final String id;
		final String worldSpace;
		final int anchorLevel;
		final int tileCount;
		final int placementCount;
		Snapshot(Map<String,Object> root, String id, String worldSpace,
			int anchorLevel, int tileCount, int placementCount) {
			this.root = root; this.id = id; this.worldSpace = worldSpace;
			this.anchorLevel = anchorLevel; this.tileCount = tileCount;
			this.placementCount = placementCount;
		}
	}

	static final class Point {
		final int x;
		final int y;
		Point(int x, int y) { this.x = x; this.y = y; }
	}

	/** Integer-only tile-center ownership with edge inclusion and simple polygons. */
	static final class Geometry {
		final List<Point> points;
		final int minimumX;
		final int maximumX;
		final int minimumY;
		final int maximumY;
		final int width;
		final int height;

		private Geometry(List<Point> points, int minimumX, int maximumX,
			int minimumY, int maximumY) {
			this.points = points; this.minimumX = minimumX; this.maximumX = maximumX;
			this.minimumY = minimumY; this.maximumY = maximumY;
			this.width = maximumX - minimumX + 1;
			this.height = maximumY - minimumY + 1;
		}

		static Geometry create(List<Point> points, String op)
			throws WorldBuilderContractException {
			int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
			int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
			for (int index = 0; index < points.size(); index++) {
				Point a = points.get(index);
				minX = Math.min(minX, a.x); maxX = Math.max(maxX, a.x);
				minY = Math.min(minY, a.y); maxY = Math.max(maxY, a.y);
			}
			if ((long)maxX - minX > 4096L || (long)maxY - minY > 4096L) {
				invalid(op, "Selection polygon is degenerate or exceeds 4,096 tiles per axis.");
			}
			long area = 0L;
			for (int index = 0; index < points.size(); index++) {
				Point a = points.get(index);
				Point b = points.get((index + 1) % points.size());
				area += ((long)a.x - minX) * ((long)b.y - minY)
					- ((long)b.x - minX) * ((long)a.y - minY);
			}
			if (area == 0L) invalid(op, "Selection polygon is degenerate.");
			for (int first = 0; first < points.size(); first++) {
				int firstNext = (first + 1) % points.size();
				for (int second = first + 1; second < points.size(); second++) {
					int secondNext = (second + 1) % points.size();
					if (first == second || firstNext == second || secondNext == first) continue;
					if (segmentsIntersect(points.get(first), points.get(firstNext),
						points.get(second), points.get(secondNext))) invalid(op,
						"Selection polygon self-intersects or has overlapping edges.");
				}
			}
			return new Geometry(new ArrayList<Point>(points), minX, maxX, minY, maxY);
		}

		boolean owns(int x, int y) {
			long px = 2L * x + 1L;
			long py = 2L * y + 1L;
			boolean inside = false;
			for (int index = 0, previous = points.size() - 1;
				index < points.size(); previous = index++) {
				long ax = 2L * points.get(previous).x + 1L;
				long ay = 2L * points.get(previous).y + 1L;
				long bx = 2L * points.get(index).x + 1L;
				long by = 2L * points.get(index).y + 1L;
				if (onSegment(ax, ay, bx, by, px, py)) return true;
				if ((ay > py) != (by > py)) {
					long left = (px - ax) * (by - ay);
					long right = (bx - ax) * (py - ay);
					if (by > ay ? left < right : left > right) inside = !inside;
				}
			}
			return inside;
		}

		private static boolean segmentsIntersect(Point a, Point b, Point c, Point d) {
			long o1 = orient(a, b, c), o2 = orient(a, b, d);
			long o3 = orient(c, d, a), o4 = orient(c, d, b);
			if (o1 == 0 && between(a, b, c) || o2 == 0 && between(a, b, d)
				|| o3 == 0 && between(c, d, a) || o4 == 0 && between(c, d, b)) return true;
			return (o1 < 0) != (o2 < 0) && (o3 < 0) != (o4 < 0);
		}

		private static long orient(Point a, Point b, Point c) {
			return ((long)b.x - a.x) * ((long)c.y - a.y)
				- ((long)b.y - a.y) * ((long)c.x - a.x);
		}

		private static boolean between(Point a, Point b, Point p) {
			return p.x >= Math.min(a.x, b.x) && p.x <= Math.max(a.x, b.x)
				&& p.y >= Math.min(a.y, b.y) && p.y <= Math.max(a.y, b.y);
		}

		private static boolean onSegment(long ax, long ay, long bx, long by,
			long px, long py) {
			return (bx - ax) * (py - ay) == (by - ay) * (px - ax)
				&& px >= Math.min(ax, bx) && px <= Math.max(ax, bx)
				&& py >= Math.min(ay, by) && py <= Math.max(ay, by);
		}
	}
}
