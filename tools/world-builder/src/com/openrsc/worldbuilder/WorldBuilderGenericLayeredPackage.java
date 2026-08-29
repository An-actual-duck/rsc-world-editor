package com.openrsc.worldbuilder;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** Generic validator for frozen-v1 and wide-elevation-v2 layered packages. */
final class WorldBuilderGenericLayeredPackage {
	private static final int MAX_LEVELS = 4096;
	private static final int MAX_TERRAIN = 65536;
	private static final int MAX_PLACEMENTS_PER_SET = 65536;
	private static final int PREFERRED_INITIAL_LEVEL = 0;
	private static final int PREFERRED_INITIAL_X = 120;
	private static final int PREFERRED_INITIAL_Y = 648;

	final String packageId;
	final String packageVersion;
	final String worldSpace;
	final String fingerprintSha256;
	final String nativeInventorySha256;
	final String manifestSha256;
	final int initialLevel;
	final int initialX;
	final int initialY;
	final int levelCount;
	final int terrainCount;
	final int placementSetCount;
	final long boundaryCount;
	final long groundItemCount;
	final long npcCount;
	final long sceneryCount;
	final List<String> placementSemantics;
	final List<String> placementIdentities;
	final List<WorldBuilderReadOnlyTarget.FileState> files;
	final List<Integer> terrainFloorDefinitionIds;
	final List<Integer> terrainBoundaryDefinitionIds;
	private final Set<String> terrainCoverage;

	private WorldBuilderGenericLayeredPackage(
		String packageId,
		String packageVersion,
		String worldSpace,
		String fingerprintSha256,
		String nativeInventorySha256,
		String manifestSha256,
		int initialLevel,
		int initialX,
		int initialY,
		int levelCount,
		int terrainCount,
		int placementSetCount,
		long boundaryCount,
		long groundItemCount,
		long npcCount,
		long sceneryCount,
		List<String> placementSemantics,
		List<String> placementIdentities,
		List<WorldBuilderReadOnlyTarget.FileState> files,
		Set<Integer> terrainFloorDefinitionIds,
		Set<Integer> terrainBoundaryDefinitionIds,
		Set<String> terrainCoverage) {
		this.packageId = packageId;
		this.packageVersion = packageVersion;
		this.worldSpace = worldSpace;
		this.fingerprintSha256 = fingerprintSha256;
		this.nativeInventorySha256 = nativeInventorySha256;
		this.manifestSha256 = manifestSha256;
		this.initialLevel = initialLevel;
		this.initialX = initialX;
		this.initialY = initialY;
		this.levelCount = levelCount;
		this.terrainCount = terrainCount;
		this.placementSetCount = placementSetCount;
		this.boundaryCount = boundaryCount;
		this.groundItemCount = groundItemCount;
		this.npcCount = npcCount;
		this.sceneryCount = sceneryCount;
		this.placementSemantics = Collections.unmodifiableList(
			new ArrayList<String>(placementSemantics));
		this.placementIdentities = Collections.unmodifiableList(
			new ArrayList<String>(placementIdentities));
		this.files = Collections.unmodifiableList(
			new ArrayList<WorldBuilderReadOnlyTarget.FileState>(files));
		this.terrainFloorDefinitionIds = Collections.unmodifiableList(
			new ArrayList<Integer>(terrainFloorDefinitionIds));
		this.terrainBoundaryDefinitionIds = Collections.unmodifiableList(
			new ArrayList<Integer>(terrainBoundaryDefinitionIds));
		this.terrainCoverage = Collections.unmodifiableSet(
			new HashSet<String>(terrainCoverage));
	}

	WorldBuilderGenericLayeredPackage withInitialLocation(
		int level, int x, int y, String evidencePath)
		throws WorldBuilderContractException {
		if (x < 0 || x > 32767 || y < 0 || y > 32767) {
			throw problem(WorldBuilderErrorCodes.CAPABILITY_MISMATCH, evidencePath,
				"Standalone initial coordinates are outside the client carrier range.",
				"Choose generated terrain and a start coordinate within 0..32767.");
		}
		if (!terrainCoverage.contains(coordinateKey(
			level, Math.floorDiv(x, 48), Math.floorDiv(y, 48)))) {
			throw problem(WorldBuilderErrorCodes.CAPABILITY_MISMATCH, evidencePath,
				"Standalone initial coordinates are not covered by generated terrain.",
				"Generate canonical void terrain containing the configured start coordinate.");
		}
		return new WorldBuilderGenericLayeredPackage(packageId, packageVersion,
			worldSpace, fingerprintSha256, nativeInventorySha256, manifestSha256,
			level, x, y, levelCount, terrainCount, placementSetCount,
			boundaryCount, groundItemCount, npcCount, sceneryCount,
			placementSemantics, placementIdentities, files,
			new TreeSet<Integer>(terrainFloorDefinitionIds),
			new TreeSet<Integer>(terrainBoundaryDefinitionIds), terrainCoverage);
	}

	/** Definition IDs actually required by the validated effective package. */
	Map<String,List<Integer>> requiredDefinitionIds() {
		Map<String,Set<Integer>> collected = new LinkedHashMap<String,Set<Integer>>();
		for (String family : Arrays.asList(
			"floor", "boundary", "ground-item", "npc", "scenery")) {
			collected.put(family, new TreeSet<Integer>());
		}
		collected.get("floor").addAll(terrainFloorDefinitionIds);
		collected.get("boundary").addAll(terrainBoundaryDefinitionIds);
		for (String semantic : placementSemantics) {
			int first = semantic.indexOf('\u0000');
			int second = first < 0 ? -1 : semantic.indexOf('\u0000', first + 1);
			int third = second < 0 ? -1 : semantic.indexOf('\u0000', second + 1);
			String family = first < 0 ? "" : semantic.substring(0, first);
			if (second < 0 || !collected.containsKey(family)) {
				throw new AssertionError("validated placement semantic is malformed");
			}
			try {
				String id = semantic.substring(second + 1,
					third < 0 ? semantic.length() : third);
				collected.get(family).add(Integer.valueOf(id));
			} catch (NumberFormatException impossible) {
				throw new AssertionError("validated placement definition ID is malformed");
			}
		}
		Map<String,List<Integer>> result =
			new LinkedHashMap<String,List<Integer>>();
		for (Map.Entry<String,Set<Integer>> entry : collected.entrySet()) {
			result.put(entry.getKey(), Collections.unmodifiableList(
				new ArrayList<Integer>(entry.getValue())));
		}
		return Collections.unmodifiableMap(result);
	}

	static WorldBuilderGenericLayeredPackage inspect(
		WorldBuilderReadOnlyTarget target,
		String packageRelative,
		String side,
		WorldBuilderCompatibilityEvidence.DefinitionCatalog definitions)
		throws WorldBuilderContractException {
		target.requiredDirectory(packageRelative);
		String manifestRelative = child(packageRelative, "manifest.json", packageRelative);
		Map<String,Object> manifest = target.readObject(manifestRelative);
		exact(manifest, manifestRelative, "coordinateModel", "levels", "packageId",
			"packageType", "packageVersion", "placementSets", "schemaVersion",
			"storage", "terrainSectors", "worldSpaces");
		if (integer(manifest, "schemaVersion", manifestRelative) != 1L
			|| !"layered-world".equals(string(manifest, "packageType", manifestRelative))
			|| !"signed-layered-v1".equals(
				string(manifest, "coordinateModel", manifestRelative))) {
			throw problem(WorldBuilderErrorCodes.UNSUPPORTED_FORMAT, manifestRelative,
				"Layered package identity or coordinate model is unsupported.",
				"Use layered-world / signed-layered-v1 schema version 1.");
		}
		String packageId = identifier(manifest, "packageId", manifestRelative);
		String packageVersion = identifier(manifest, "packageVersion", manifestRelative);
		Map<String,Object> storage = object(manifest.get("storage"), manifestRelative, "storage");
		exact(storage, manifestRelative, "presentationChunkSize", "sectorSize");
		if (integer(storage, "sectorSize", manifestRelative) != 48L
			|| integer(storage, "presentationChunkSize", manifestRelative) != 24L) {
			throw problem(WorldBuilderErrorCodes.UNSUPPORTED_FORMAT, manifestRelative,
				"Layered package storage geometry is not 48/24.",
				"Use 48-tile sectors and 24-tile presentation chunks.");
		}

		List<?> worldSpaces = array(
			manifest.get("worldSpaces"), manifestRelative, "worldSpaces", 1, 1);
		Map<String,Object> world = object(worldSpaces.get(0), manifestRelative, "worldSpace");
		exact(world, manifestRelative, "id", "kind");
		String worldSpace = identifier(world, "id", manifestRelative);
		if (!"static".equals(string(world, "kind", manifestRelative))) {
			throw problem(WorldBuilderErrorCodes.UNSUPPORTED_FORMAT, manifestRelative,
				"Layered package world space is not static.",
				"Use one static world space for editable map content.");
		}

		List<?> rawLevels = array(
			manifest.get("levels"), manifestRelative, "levels", 1, MAX_LEVELS);
		Set<Integer> levels = new HashSet<Integer>();
		Integer previousLevel = null;
		for (Object raw : rawLevels) {
			Map<String,Object> level = object(raw, manifestRelative, "level");
			exact(level, manifestRelative, "level", "name", "role", "worldSpace");
			int number = signedInteger(level, "level", manifestRelative);
			String name = string(level, "name", manifestRelative);
			identifier(level, "role", manifestRelative);
			if (!worldSpace.equals(string(level, "worldSpace", manifestRelative))
				|| name.isEmpty() || name.length() > 128 || !levels.add(Integer.valueOf(number))
				|| previousLevel != null && previousLevel.intValue() >= number) {
				throw problem(WorldBuilderErrorCodes.MALFORMED_SERVER, manifestRelative,
					"Layered levels are invalid, duplicated, or not canonically ordered.",
					"Use unique ascending signed levels in the selected world space.");
			}
			previousLevel = Integer.valueOf(number);
		}

		Map<String,WorldBuilderReadOnlyTarget.FileState> referenced =
			new LinkedHashMap<String,WorldBuilderReadOnlyTarget.FileState>();
		WorldBuilderReadOnlyTarget.FileState manifestState = target.requiredState(
			side + "-map-manifest", manifestRelative);
		referenced.put(manifestRelative, manifestState);
		Set<String> terrainCoverage = new HashSet<String>();
		Set<Integer> terrainFloorDefinitionIds = new TreeSet<Integer>();
		Set<Integer> terrainBoundaryDefinitionIds = new TreeSet<Integer>();
		Integer initialLevel = null;
		Integer initialX = null;
		Integer initialY = null;
		List<?> rawTerrain = array(
			manifest.get("terrainSectors"), manifestRelative, "terrainSectors", 1, MAX_TERRAIN);
		String previousTerrain = null;
		for (Object raw : rawTerrain) {
			Map<String,Object> terrain = object(raw, manifestRelative, "terrainSector");
			exact(terrain, manifestRelative, "encoding", "level", "path", "sectorX",
				"sectorY", "sha256", "worldSpace");
			int level = signedInteger(terrain, "level", manifestRelative);
			int sectorX = signedInteger(terrain, "sectorX", manifestRelative);
			int sectorY = signedInteger(terrain, "sectorY", manifestRelative);
			String key = coordinateKey(level, sectorX, sectorY);
			String order = orderedCoordinate(level, sectorX, sectorY);
			String encoding = string(terrain, "encoding", manifestRelative);
			if (!levels.contains(Integer.valueOf(level))
				|| !worldSpace.equals(string(terrain, "worldSpace", manifestRelative))
				|| !WorldBuilderRawLayeredTerrainCodec.supports(encoding)
				|| !terrainCoverage.add(key)
				|| previousTerrain != null && previousTerrain.compareTo(order) >= 0) {
				throw problem(WorldBuilderErrorCodes.MALFORMED_SERVER, manifestRelative,
					"Layered terrain declarations are invalid, duplicated, or not canonical.",
					"Use unique level/sector coordinates sorted in canonical order.");
			}
			previousTerrain = order;
			long minimumX = (long)sectorX * 48L;
			long minimumY = (long)sectorY * 48L;
			long candidateX = Math.max(0L, minimumX);
			long candidateY = Math.max(0L, minimumY);
			if (initialLevel == null
				&& candidateX <= minimumX + 47L
				&& candidateY <= minimumY + 47L
				&& candidateX <= 32767L && candidateY <= 32767L) {
				initialLevel = Integer.valueOf(level);
				initialX = Integer.valueOf((int)candidateX);
				initialY = Integer.valueOf((int)candidateY);
			}
			String packagePath = portableRelative(terrain, "path", manifestRelative);
			String targetPath = child(packageRelative, packagePath, manifestRelative);
			String expectedHash = hash(terrain, "sha256", manifestRelative);
			WorldBuilderReadOnlyTarget.FileState state = target.requiredState(
				side + "-map-terrain", targetPath);
			long expectedSize = WorldBuilderRawLayeredTerrainCodec.byteCount(encoding);
			if (state.size != expectedSize || !expectedHash.equals(state.sha256)) {
				throw problem(WorldBuilderErrorCodes.MALFORMED_SERVER, targetPath,
					"Layered terrain payload size or hash does not match its declaration.",
					"Restore the exact " + expectedSize + "-byte " + encoding
						+ " declared sector payload.");
			}
			try {
				byte[] terrainBytes = Files.readAllBytes(target.requiredFile(targetPath));
				WorldBuilderRawLayeredTerrainCodec.requireDecodable(terrainBytes, encoding);
				collectTerrainDefinitionIds(terrainBytes, encoding,
					terrainFloorDefinitionIds, terrainBoundaryDefinitionIds);
			} catch (IOException failure) {
				throw problem(WorldBuilderErrorCodes.DISCOVERY_DRIFT, targetPath,
					"Layered terrain changed while it was decoded.",
					"Stop package changes and retry validation.");
			}
			register(referenced, state, manifestRelative);
		}
		String preferredInitialSector = coordinateKey(
			PREFERRED_INITIAL_LEVEL,
			Math.floorDiv(PREFERRED_INITIAL_X, 48),
			Math.floorDiv(PREFERRED_INITIAL_Y, 48));
		if (terrainCoverage.contains(preferredInitialSector)) {
			initialLevel = Integer.valueOf(PREFERRED_INITIAL_LEVEL);
			initialX = Integer.valueOf(PREFERRED_INITIAL_X);
			initialY = Integer.valueOf(PREFERRED_INITIAL_Y);
		}

		List<?> rawPlacementSets = array(manifest.get("placementSets"), manifestRelative,
			"placementSets", rawLevels.size(), rawLevels.size());
		Set<Integer> placementLevels = new HashSet<Integer>();
		Set<String> placementSetIds = new HashSet<String>();
		String packagePlacementEncoding = null;
		Integer previousPlacementLevel = null;
		long boundaries = 0L;
		long groundItems = 0L;
		long npcs = 0L;
		long scenery = 0L;
		List<String> placementSemantics = new ArrayList<String>();
		List<String> placementIdentities = new ArrayList<String>();
		for (Object raw : rawPlacementSets) {
			Map<String,Object> placement = object(raw, manifestRelative, "placementSet");
			exact(placement, manifestRelative, "encoding", "id", "level", "path",
				"sha256", "worldSpace");
			int level = signedInteger(placement, "level", manifestRelative);
			String id = identifier(placement, "id", manifestRelative);
			String placementEncoding =
				string(placement, "encoding", manifestRelative);
			if (!levels.contains(Integer.valueOf(level))
				|| !placementLevels.add(Integer.valueOf(level))
				|| !placementSetIds.add(id)
				|| previousPlacementLevel != null && previousPlacementLevel.intValue() >= level
				|| !worldSpace.equals(string(placement, "worldSpace", manifestRelative))
				|| !("layered-world-placements-v3".equals(placementEncoding)
					|| "layered-world-placements-v4".equals(placementEncoding))
				|| packagePlacementEncoding != null
					&& !packagePlacementEncoding.equals(placementEncoding)) {
				throw problem(WorldBuilderErrorCodes.MALFORMED_SERVER, manifestRelative,
					"Layered placement-set declarations are invalid or not canonical.",
					"Declare exactly one ascending v3 or v4 placement set per level.");
			}
			packagePlacementEncoding = placementEncoding;
			previousPlacementLevel = Integer.valueOf(level);
			String packagePath = portableRelative(placement, "path", manifestRelative);
			String targetPath = child(packageRelative, packagePath, manifestRelative);
			String expectedHash = hash(placement, "sha256", manifestRelative);
			WorldBuilderReadOnlyTarget.FileState state = target.requiredState(
				side + "-map-placement-set", targetPath);
			if (!expectedHash.equals(state.sha256)) {
				throw problem(WorldBuilderErrorCodes.MALFORMED_SERVER, targetPath,
					"Layered placement payload hash does not match its declaration.",
					"Restore the exact declared placement payload.");
			}
			register(referenced, state, manifestRelative);
			PlacementCounts counts = validatePlacements(
				target, targetPath, worldSpace, level, terrainCoverage, definitions,
				placementSemantics, placementIdentities, placementEncoding);
			boundaries += counts.boundaries;
			groundItems += counts.groundItems;
			npcs += counts.npcs;
			scenery += counts.scenery;
		}

		Set<String> actual = scanPackage(target, packageRelative);
		if (!actual.equals(referenced.keySet())) {
			Set<String> missing = new TreeSet<String>(referenced.keySet());
			missing.removeAll(actual);
			Set<String> extra = new TreeSet<String>(actual);
			extra.removeAll(referenced.keySet());
			throw problem(WorldBuilderErrorCodes.MALFORMED_SERVER, manifestRelative,
				"Layered package has missing or untracked files; missing=" + missing
					+ ", extra=" + extra + ".",
				"Keep only the manifest and every exactly referenced payload.");
		}
		List<WorldBuilderReadOnlyTarget.FileState> files =
			new ArrayList<WorldBuilderReadOnlyTarget.FileState>(referenced.values());
		Collections.sort(files);
		MessageDigest digest = WorldBuilderHashes.newDigest();
		MessageDigest nativeDigest = WorldBuilderHashes.newDigest();
		for (WorldBuilderReadOnlyTarget.FileState file : files) {
			String inside = file.relativePath.substring(packageRelative.length() + 1);
			WorldBuilderHashes.updateText(digest, inside);
			WorldBuilderHashes.updateText(digest, Long.toString(file.size));
			WorldBuilderHashes.updateText(digest, file.sha256);
			nativeDigest.update(inside.getBytes(
				java.nio.charset.StandardCharsets.UTF_8));
			nativeDigest.update((byte)0);
			nativeDigest.update(Long.toString(file.size).getBytes(
				java.nio.charset.StandardCharsets.US_ASCII));
			nativeDigest.update((byte)0);
			nativeDigest.update(file.sha256.getBytes(
				java.nio.charset.StandardCharsets.US_ASCII));
			nativeDigest.update((byte)'\n');
		}
		Collections.sort(placementSemantics);
		Collections.sort(placementIdentities);
		if (initialLevel == null) {
			throw problem(WorldBuilderErrorCodes.CAPABILITY_MISMATCH, manifestRelative,
				"Layered package terrain has no tile addressable by the adaptive client.",
				"Provide terrain intersecting global client coordinates 0..32767.");
		}
		return new WorldBuilderGenericLayeredPackage(packageId, packageVersion,
			worldSpace, WorldBuilderHashes.hex(digest.digest()),
			WorldBuilderHashes.hex(nativeDigest.digest()), manifestState.sha256,
			initialLevel.intValue(), initialX.intValue(), initialY.intValue(), levels.size(),
			rawTerrain.size(), rawPlacementSets.size(), boundaries, groundItems,
			npcs, scenery, placementSemantics, placementIdentities, files,
			terrainFloorDefinitionIds, terrainBoundaryDefinitionIds,
			terrainCoverage);
	}

	private static void collectTerrainDefinitionIds(byte[] payload, String encoding,
		Set<Integer> floors, Set<Integer> boundaries) {
		int tileBytes = WorldBuilderRawLayeredTerrainCodec.tileBytes(encoding);
		int shift = WorldBuilderRawLayeredTerrainCodec.isWide(encoding) ? 1 : 0;
		for (int offset = 0; offset < payload.length; offset += tileBytes) {
			int overlay = payload[offset + shift + 2] & 0xff;
			int effectiveOverlay = overlay == 250 ? 2 : overlay;
			if (effectiveOverlay > 0) floors.add(Integer.valueOf(effectiveOverlay - 1));
			int vertical = payload[offset + shift + 4] & 0xff;
			int horizontal = payload[offset + shift + 5] & 0xff;
			if (vertical > 0) boundaries.add(Integer.valueOf(vertical - 1));
			if (horizontal > 0) boundaries.add(Integer.valueOf(horizontal - 1));
			int diagonal = ByteBuffer.wrap(payload, offset + shift + 6, 4).getInt();
			if (diagonal > 0 && diagonal < 12000) {
				boundaries.add(Integer.valueOf(diagonal - 1));
			} else if (diagonal > 12000 && diagonal < 24000) {
				boundaries.add(Integer.valueOf(diagonal - 12001));
			}
		}
	}

	private static PlacementCounts validatePlacements(
		WorldBuilderReadOnlyTarget target,
		String path,
		String worldSpace,
		int level,
		Set<String> terrainCoverage,
		WorldBuilderCompatibilityEvidence.DefinitionCatalog definitions,
		List<String> semantics,
		List<String> identities,
		String declaredEncoding)
		throws WorldBuilderContractException {
		Map<String,Object> payload = target.readObject(path);
		exact(payload, path, "boundaries", "encoding", "groundItems", "level",
			"npcs", "scenery", "schemaVersion", "worldSpace");
		long schemaVersion = integer(payload, "schemaVersion", path);
		String placementEncoding = string(payload, "encoding", path);
		if (!(schemaVersion == 3L
				&& "layered-world-placements-v3".equals(placementEncoding)
				|| schemaVersion == 4L
				&& "layered-world-placements-v4".equals(placementEncoding))
			|| !declaredEncoding.equals(placementEncoding)
			|| level != signedInteger(payload, "level", path)
			|| !worldSpace.equals(string(payload, "worldSpace", path))) {
			throw problem(WorldBuilderErrorCodes.UNSUPPORTED_FORMAT, path,
				"Layered placement payload identity does not match its declaration.",
				"Use one exact layered-world-placements-v3 or v4 payload for the declared level.");
		}
		Set<String> placementIds = new HashSet<String>();
		long boundaryCount = validateBoundaries(array(payload.get("boundaries"), path,
			"boundaries", 0, MAX_PLACEMENTS_PER_SET), path, level, terrainCoverage,
			definitions, placementIds, semantics, identities);
		long groundItemCount = validateGroundItems(array(payload.get("groundItems"), path,
			"groundItems", 0, MAX_PLACEMENTS_PER_SET), path, level, terrainCoverage,
			definitions, placementIds, semantics, identities);
		long npcCount = validateNpcs(array(payload.get("npcs"), path,
			"npcs", 0, MAX_PLACEMENTS_PER_SET), path, level, terrainCoverage,
			definitions, placementIds, semantics, identities, schemaVersion >= 4L);
		long sceneryCount = validateScenery(array(payload.get("scenery"), path,
			"scenery", 0, MAX_PLACEMENTS_PER_SET), path, level, terrainCoverage,
			definitions, placementIds, semantics, identities);
		long total = boundaryCount + groundItemCount + npcCount + sceneryCount;
		if (total > MAX_PLACEMENTS_PER_SET) {
			throw problem(WorldBuilderErrorCodes.INVENTORY_LIMIT_EXCEEDED, path,
				"Layered placement set exceeds 65,536 total records.",
				"Split content across supported levels without dropping records.");
		}
		return new PlacementCounts(boundaryCount, groundItemCount, npcCount, sceneryCount);
	}

	private static long validateBoundaries(
		List<?> records, String path, int level, Set<String> terrain,
		WorldBuilderCompatibilityEvidence.DefinitionCatalog definitions,
		Set<String> placementIds, List<String> semantics, List<String> identities)
		throws WorldBuilderContractException {
		Set<String> slots = new HashSet<String>();
		String previous = null;
		for (Object raw : records) {
			Map<String,Object> record = object(raw, path, "boundary");
			exact(record, path, "boundaryId", "direction", "placementId", "position");
			int id = nonnegativeInteger(record, "boundaryId", path);
			int direction = nonnegativeInteger(record, "direction", path);
			if (direction > 3) invalid(path, "Boundary direction is outside 0..3.");
			Point point = point(record.get("position"), path);
			String placement = placementId(record, path, placementIds);
			String key = orderedPoint(point.x, point.y) + "\u0000" + direction + "\u0000" + placement;
			if (!slots.add(point.x + ":" + point.y + ":" + direction)
				|| previous != null && previous.compareTo(key) >= 0) {
				invalid(path, "Boundary placements collide, duplicate, or are not canonical.");
			}
			previous = key;
			definitions.require("boundary", id, path);
			requireCoverage(terrain, level, point.x, point.y, path);
			String semantic = WorldBuilderPlacementSemantics.boundary(
				level, id, point.x, point.y, direction);
			semantics.add(semantic);
			identities.add(WorldBuilderPlacementSemantics.identity(placement, semantic));
		}
		return records.size();
	}

	private static long validateGroundItems(
		List<?> records, String path, int level, Set<String> terrain,
		WorldBuilderCompatibilityEvidence.DefinitionCatalog definitions,
		Set<String> placementIds, List<String> semantics, List<String> identities)
		throws WorldBuilderContractException {
		Set<String> slots = new HashSet<String>();
		String previous = null;
		for (Object raw : records) {
			Map<String,Object> record = object(raw, path, "groundItem");
			exact(record, path, "amount", "itemId", "placementId", "position",
				"respawnSeconds");
			int id = nonnegativeInteger(record, "itemId", path);
			int amount = nonnegativeInteger(record, "amount", path);
			int respawn = nonnegativeInteger(record, "respawnSeconds", path);
			if (amount < 1 || respawn > 86400) {
				invalid(path, "Ground-item amount or respawn is outside its supported range.");
			}
			Point point = point(record.get("position"), path);
			String placement = placementId(record, path, placementIds);
			String key = orderedPoint(point.x, point.y) + "\u0000" + placement;
			if (!slots.add(point.x + ":" + point.y)
				|| previous != null && previous.compareTo(key) >= 0) {
				invalid(path, "Ground-item placements collide, duplicate, or are not canonical.");
			}
			previous = key;
			definitions.require("ground-item", id, path);
			requireCoverage(terrain, level, point.x, point.y, path);
			String semantic = WorldBuilderPlacementSemantics.groundItem(
				level, id, point.x, point.y, amount, respawn);
			semantics.add(semantic);
			identities.add(WorldBuilderPlacementSemantics.identity(placement, semantic));
		}
		return records.size();
	}

	private static long validateNpcs(
		List<?> records, String path, int level, Set<String> terrain,
		WorldBuilderCompatibilityEvidence.DefinitionCatalog definitions,
		Set<String> placementIds, List<String> semantics, List<String> identities,
		boolean placementRespawn)
		throws WorldBuilderContractException {
		String previous = null;
		for (Object raw : records) {
			Map<String,Object> record = object(raw, path, "npc");
			if (placementRespawn) {
				exact(record, path, "npcId", "placementId", "respawnSeconds",
					"roamBounds", "start");
			} else {
				exact(record, path, "npcId", "placementId", "roamBounds", "start");
			}
			int id = nonnegativeInteger(record, "npcId", path);
			int respawn = placementRespawn
				? signedInteger(record, "respawnSeconds", path) : -1;
			if (respawn < -1 || respawn > 86400) {
				invalid(path, "NPC respawn time is outside -1..86400 seconds.");
			}
			Point start = point(record.get("start"), path);
			Map<String,Object> bounds = object(record.get("roamBounds"), path, "roamBounds");
			exact(bounds, path, "maximum", "minimum");
			Point minimum = point(bounds.get("minimum"), path);
			Point maximum = point(bounds.get("maximum"), path);
			if (minimum.x > start.x || start.x > maximum.x
				|| minimum.y > start.y || start.y > maximum.y
				|| (long)maximum.x - minimum.x > 128L
				|| (long)maximum.y - minimum.y > 128L) {
				invalid(path, "NPC roaming bounds are invalid or exceed 128 tiles.");
			}
			String placement = placementId(record, path, placementIds);
			String key = orderedPoint(start.x, start.y) + "\u0000" + placement;
			if (previous != null && previous.compareTo(key) >= 0) {
				invalid(path, "NPC placements are not canonical.");
			}
			previous = key;
			definitions.require("npc", id, path);
			requireCoverage(terrain, level, start.x, start.y, path);
			requireCoverageRectangle(terrain, level, minimum, maximum, path);
			String semantic = placementRespawn
				? WorldBuilderPlacementSemantics.npc(level, id,
					start.x, start.y, minimum.x, minimum.y,
					maximum.x, maximum.y, respawn)
				: WorldBuilderPlacementSemantics.npc(level, id,
					start.x, start.y, minimum.x, minimum.y,
					maximum.x, maximum.y);
			semantics.add(semantic);
			identities.add(WorldBuilderPlacementSemantics.identity(placement, semantic));
		}
		return records.size();
	}

	private static long validateScenery(
		List<?> records, String path, int level, Set<String> terrain,
		WorldBuilderCompatibilityEvidence.DefinitionCatalog definitions,
		Set<String> placementIds, List<String> semantics, List<String> identities)
		throws WorldBuilderContractException {
		Set<String> slots = new HashSet<String>();
		String previous = null;
		for (Object raw : records) {
			Map<String,Object> record = object(raw, path, "scenery");
			exact(record, path, "direction", "placementId", "position", "sceneryId");
			int id = nonnegativeInteger(record, "sceneryId", path);
			int direction = nonnegativeInteger(record, "direction", path);
			if (direction > 8) invalid(path,
				"Scenery direction is outside the legacy range 0..8.");
			Point point = point(record.get("position"), path);
			String placement = placementId(record, path, placementIds);
			String key = orderedPoint(point.x, point.y) + "\u0000" + placement;
			if (!slots.add(point.x + ":" + point.y)
				|| previous != null && previous.compareTo(key) >= 0) {
				invalid(path, "Scenery placements collide, duplicate, or are not canonical.");
			}
			previous = key;
			definitions.require("scenery", id, path);
			requireCoverage(terrain, level, point.x, point.y, path);
			String semantic = WorldBuilderPlacementSemantics.scenery(
				level, id, point.x, point.y, direction);
			semantics.add(semantic);
			identities.add(WorldBuilderPlacementSemantics.identity(placement, semantic));
		}
		return records.size();
	}

	private static void requireCoverageRectangle(
		Set<String> terrain, int level, Point minimum, Point maximum, String path)
		throws WorldBuilderContractException {
		int minimumX = Math.floorDiv(minimum.x, 48);
		int maximumX = Math.floorDiv(maximum.x, 48);
		int minimumY = Math.floorDiv(minimum.y, 48);
		int maximumY = Math.floorDiv(maximum.y, 48);
		for (long x = minimumX; x <= maximumX; x++) {
			for (long y = minimumY; y <= maximumY; y++) {
				if (!terrain.contains(coordinateKey(level, (int)x, (int)y))) {
					invalid(path, "NPC roaming bounds extend beyond declared terrain coverage.");
				}
			}
		}
	}

	private static void requireCoverage(
		Set<String> terrain, int level, int x, int y, String path)
		throws WorldBuilderContractException {
		if (!terrain.contains(coordinateKey(level, Math.floorDiv(x, 48), Math.floorDiv(y, 48)))) {
			invalid(path, "Placement coordinate is outside declared terrain coverage.");
		}
	}

	private static String placementId(
		Map<String,Object> value, String path, Set<String> ids)
		throws WorldBuilderContractException {
		String id = identifier(value, "placementId", path);
		if (!ids.add(id)) invalid(path, "Placement ID is duplicated across placement families.");
		return id;
	}

	private static Point point(Object raw, String path) throws WorldBuilderContractException {
		Map<String,Object> point = object(raw, path, "point");
		exact(point, path, "x", "y");
		return new Point(signedInteger(point, "x", path), signedInteger(point, "y", path));
	}

	private static Set<String> scanPackage(
		WorldBuilderReadOnlyTarget target, String packageRelative)
		throws WorldBuilderContractException {
		Set<String> files = new TreeSet<String>();
		int[] counts = new int[] {0};
		long[] bytes = new long[] {0L};
		scanDirectory(
			target, target.requiredDirectory(packageRelative), files, counts, bytes, 0);
		return files;
	}

	private static void scanDirectory(
		WorldBuilderReadOnlyTarget target,
		Path directory,
		Set<String> files,
		int[] counts,
		long[] bytes,
		int depth) throws WorldBuilderContractException {
		if (depth > WorldBuilderContractLimits.MAX_JSON_DEPTH) {
			throw problem(WorldBuilderErrorCodes.INVENTORY_LIMIT_EXCEEDED,
				target.relative(directory), "Layered package nesting exceeds 32 directories.",
				"Use a bounded layered package layout.");
		}
		List<Path> entries = new ArrayList<Path>();
		try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
			for (Path entry : stream) entries.add(entry);
		} catch (IOException failure) {
			throw problem(WorldBuilderErrorCodes.DISCOVERY_DRIFT,
				target.relative(directory), "Layered package changed during enumeration.",
				"Stop package updates and retry discovery.");
		}
		Collections.sort(entries);
		for (Path entry : entries) {
			String relative = target.relative(entry);
			if (++counts[0] > WorldBuilderContractLimits.MAX_INVENTORY_ENTRIES) {
				throw problem(WorldBuilderErrorCodes.INVENTORY_LIMIT_EXCEEDED, relative,
					"Layered package entry count exceeds 8,192.",
					"Use a package within the bounded discovery inventory.");
			}
			if (Files.isSymbolicLink(entry)) {
				throw problem(WorldBuilderErrorCodes.UNSAFE_PATH, relative,
					"Layered package contains a symbolic link.",
					"Replace links with contained regular files or directories.");
			}
			if (Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)) {
				scanDirectory(target, entry, files, counts, bytes, depth + 1);
			} else if (Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)) {
				WorldBuilderReadOnlyTarget.FileState state =
					target.requiredState("package-scan", relative);
				if (state.size > WorldBuilderContractLimits.MAX_INVENTORY_TOTAL_BYTES
					- bytes[0]) {
					throw problem(WorldBuilderErrorCodes.INVENTORY_LIMIT_EXCEEDED, relative,
						"Layered package byte total exceeds 64 GiB.",
						"Use a package within the bounded discovery inventory.");
				}
				bytes[0] += state.size;
				files.add(relative);
			} else {
				throw problem(WorldBuilderErrorCodes.UNSAFE_PATH, relative,
					"Layered package contains an unsupported filesystem entry.",
					"Keep only contained regular files and directories.");
			}
		}
	}

	private static void register(
		Map<String,WorldBuilderReadOnlyTarget.FileState> referenced,
		WorldBuilderReadOnlyTarget.FileState state,
		String manifest) throws WorldBuilderContractException {
		if (referenced.put(state.relativePath, state) != null) {
			throw problem(WorldBuilderErrorCodes.INVENTORY_DUPLICATE, manifest,
				"Layered package references one payload path more than once: "
					+ state.relativePath,
				"Give every package payload one declaration and logical role.");
		}
	}

	private static String child(String root, String relative, String provenance)
		throws WorldBuilderContractException {
		String combined = root + "/" + relative;
		try {
			WorldBuilderPortablePath.require(combined, "discover-target");
		} catch (WorldBuilderContractException unsafe) {
			throw problem(WorldBuilderErrorCodes.UNSAFE_PATH, provenance,
				"Layered package declares an unsafe payload path: " + relative,
				"Use a normalized portable path contained by the package root.");
		}
		return combined;
	}

	private static String portableRelative(Map<String,Object> value, String key, String path)
		throws WorldBuilderContractException {
		String result = string(value, key, path);
		try {
			return WorldBuilderPortablePath.require(result, "discover-target");
		} catch (WorldBuilderContractException unsafe) {
			throw problem(WorldBuilderErrorCodes.UNSAFE_PATH, path,
				"Layered manifest payload path is unsafe: " + result,
				"Use a normalized portable package-relative path.");
		}
	}

	private static Map<String,Object> object(Object raw, String path, String label)
		throws WorldBuilderContractException {
		if (!(raw instanceof Map)) {
			throw problem(WorldBuilderErrorCodes.MALFORMED_SERVER, path,
				"Layered package field is not an object: " + label,
				"Correct the exact layered package structure.");
		}
		@SuppressWarnings("unchecked") Map<String,Object> result = (Map<String,Object>)raw;
		return result;
	}

	private static List<?> array(Object raw, String path, String label, int minimum, int maximum)
		throws WorldBuilderContractException {
		if (!(raw instanceof List) || ((List<?>)raw).size() < minimum
			|| ((List<?>)raw).size() > maximum) {
			throw problem(WorldBuilderErrorCodes.INVENTORY_LIMIT_EXCEEDED, path,
				"Layered package array is missing or outside its limit: " + label,
				"Use a bounded package accepted by layered-world-package-v1.");
		}
		return (List<?>)raw;
	}

	private static void exact(Map<String,Object> value, String path, String... keys)
		throws WorldBuilderContractException {
		Set<String> expected = new TreeSet<String>(Arrays.asList(keys));
		if (value.size() != expected.size() || !value.keySet().equals(expected)) {
			Set<String> missing = new TreeSet<String>(expected);
			missing.removeAll(value.keySet());
			Set<String> unexpected = new TreeSet<String>(value.keySet());
			unexpected.removeAll(expected);
			throw problem(WorldBuilderErrorCodes.MALFORMED_SERVER, path,
				"Layered package fields do not match the declared schema; missing="
					+ missing + ", unexpected=" + unexpected + ".",
				"Use the exact declared layered package/payload schema.");
		}
	}

	private static String string(Map<String,Object> value, String key, String path)
		throws WorldBuilderContractException {
		Object raw = value.get(key);
		if (!(raw instanceof String)) {
			throw problem(WorldBuilderErrorCodes.MALFORMED_SERVER, path,
				"Layered package field is not a string: " + key,
				"Correct the field type and retry.");
		}
		return (String)raw;
	}

	private static String identifier(Map<String,Object> value, String key, String path)
		throws WorldBuilderContractException {
		String result = string(value, key, path);
		if (!result.matches("[A-Za-z0-9][A-Za-z0-9._:+-]{0,127}")) {
			invalid(path, "Layered package identifier is invalid: " + key);
		}
		return result;
	}

	private static String hash(Map<String,Object> value, String key, String path)
		throws WorldBuilderContractException {
		String result = string(value, key, path);
		if (!WorldBuilderBoundedInventory.isHash(result)) {
			invalid(path, "Layered package hash is invalid: " + key);
		}
		return result;
	}

	private static long integer(Map<String,Object> value, String key, String path)
		throws WorldBuilderContractException {
		Object raw = value.get(key);
		if (!(raw instanceof Long)) invalid(path, "Layered package field is not integer: " + key);
		return ((Long)raw).longValue();
	}

	private static int signedInteger(Map<String,Object> value, String key, String path)
		throws WorldBuilderContractException {
		long result = integer(value, key, path);
		if (result < Integer.MIN_VALUE || result > Integer.MAX_VALUE) {
			invalid(path, "Layered package coordinate is outside signed 32-bit range: " + key);
		}
		return (int)result;
	}

	private static int nonnegativeInteger(Map<String,Object> value, String key, String path)
		throws WorldBuilderContractException {
		int result = signedInteger(value, key, path);
		if (result < 0) invalid(path, "Layered placement value is negative: " + key);
		return result;
	}

	private static void invalid(String path, String message)
		throws WorldBuilderContractException {
		throw problem(WorldBuilderErrorCodes.MALFORMED_SERVER, path, message,
			"Correct the exact layered package data and retry discovery.");
	}

	private static String coordinateKey(int level, int x, int y) {
		return level + ":" + x + ":" + y;
	}

	private static String orderedCoordinate(int level, int x, int y) {
		return ordered(level) + "\u0000" + ordered(x) + "\u0000" + ordered(y);
	}

	private static String orderedPoint(int x, int y) {
		return ordered(x) + "\u0000" + ordered(y);
	}

	private static String ordered(int value) {
		return String.format(Locale.ROOT, "%010d", (long)value - Integer.MIN_VALUE);
	}

	private static WorldBuilderContractException problem(
		String code, String path, String message, String nextStep) {
		return WorldBuilderReadOnlyTarget.problem(code, path, message, nextStep);
	}

	private static final class Point {
		final int x;
		final int y;
		Point(int x, int y) { this.x = x; this.y = y; }
	}

	private static final class PlacementCounts {
		final long boundaries;
		final long groundItems;
		final long npcs;
		final long scenery;

		PlacementCounts(long boundaries, long groundItems, long npcs, long scenery) {
			this.boundaries = boundaries;
			this.groundItems = groundItems;
			this.npcs = npcs;
			this.scenery = scenery;
		}
	}
}
