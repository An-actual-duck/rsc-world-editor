package com.openrsc.worldbuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Deterministically applies legacy packed terrain sectors over a complete
 * layered base without collapsing signed levels that packed maps cannot
 * represent. Existing layered placement sets remain authoritative.
 */
final class WorldBuilderLayeredTerrainComposer {
	private static final String OPERATION = "layered-terrain-composition";

	Result compose(Path projectRoot, String baseRelative, String legacyRelative,
		String outputRelative,
		WorldBuilderCompatibilityEvidence.DefinitionCatalog definitions)
		throws IOException, WorldBuilderContractException {
		WorldBuilderReadOnlyTarget target = WorldBuilderReadOnlyTarget.open(projectRoot);
		WorldBuilderGenericLayeredPackage base =
			WorldBuilderGenericLayeredPackage.inspect(
				target, baseRelative, "composition-base", definitions);
		WorldBuilderGenericLayeredPackage legacy =
			WorldBuilderGenericLayeredPackage.inspect(
				target, legacyRelative, "composition-legacy", definitions);
		Path outputRoot = projectRoot.resolve(outputRelative).normalize();
		if (!outputRoot.startsWith(projectRoot)
			|| Files.exists(outputRoot, LinkOption.NOFOLLOW_LINKS)) {
			throw problem(WorldBuilderErrorCodes.UNSAFE_PATH, outputRelative,
				"The composed package destination is unsafe or already exists.",
				"Use a new contained project staging destination.");
		}
		Files.createDirectories(outputRoot);

		Map<String,Object> baseManifest = target.readObject(baseRelative + "/manifest.json");
		Map<String,Object> legacyManifest = target.readObject(legacyRelative + "/manifest.json");
		List<Object> baseLevels = list(baseManifest, "levels");
		List<Object> legacyLevels = list(legacyManifest, "levels");
		List<Object> baseTerrain = list(baseManifest, "terrainSectors");
		List<Object> legacyTerrain = list(legacyManifest, "terrainSectors");
		List<Object> basePlacements = list(baseManifest, "placementSets");
		List<Object> legacyPlacements = list(legacyManifest, "placementSets");
		Set<String> occupiedBaseSectors = occupiedPlacementSectors(
			target, baseRelative, basePlacements);

		TreeMap<Integer,Map<String,Object>> levels = byLevel(baseLevels);
		TreeMap<Integer,Map<String,Object>> legacyLevelRecords = byLevel(legacyLevels);
		Set<Integer> baseLevelNumbers = new HashSet<Integer>(levels.keySet());
		for (Map.Entry<Integer,Map<String,Object>> entry : legacyLevelRecords.entrySet()) {
			if (!levels.containsKey(entry.getKey())) {
				Map<String,Object> addedLevel = copy(entry.getValue());
				addedLevel.put("worldSpace", base.worldSpace);
				levels.put(entry.getKey(), addedLevel);
			}
		}

		Map<String,Map<String,Object>> terrain = new LinkedHashMap<String,Map<String,Object>>();
		Map<String,Map<String,Object>> baseTerrainByIdentity =
			new LinkedHashMap<String,Map<String,Object>>();
		Map<String,List<Map<String,Object>>> baseTerrainBySector =
			new LinkedHashMap<String,List<Map<String,Object>>>();
		for (Object raw : baseTerrain) {
			Map<String,Object> record = object(raw, "base terrain declaration");
			String identity = terrainKey(record);
			terrain.put(identity, sourceRecord(record, baseRelative));
			baseTerrainByIdentity.put(identity, record);
			String sector = sectorKey(record);
			List<Map<String,Object>> records = baseTerrainBySector.get(sector);
			if (records == null) {
				records = new ArrayList<Map<String,Object>>();
				baseTerrainBySector.put(sector, records);
			}
			records.add(record);
		}
		int replaced = 0;
		int added = 0;
		int relocatedSectorsMerged = 0;
		long relocatedTilesSuppressed = 0L;
		for (Object raw : legacyTerrain) {
			Map<String,Object> record = object(raw, "legacy terrain declaration");
			String key = terrainKey(record);
			if (terrain.containsKey(key)) replaced++;
			else added++;
			Map<String,Object> normalized = sourceRecord(record, legacyRelative);
			normalized.put("worldSpace", base.worldSpace);
			Map<String,Object> baseAtIdentity = baseTerrainByIdentity.get(key);
			RelocatedTerrainMerge merge = suppressRelocatedLegacyTiles(
				target, baseRelative, legacyRelative, baseAtIdentity, record,
				baseTerrainBySector.get(sectorKey(record)), occupiedBaseSectors);
			if (merge.suppressedTiles > 0) {
				normalized.put("__syntheticPayload", merge.payload);
				normalized.put("encoding", WorldBuilderRawLayeredTerrainCodec.V2_ENCODING);
				normalized.put("sha256", WorldBuilderHashes.sha256(merge.payload));
				relocatedSectorsMerged++;
				relocatedTilesSuppressed += merge.suppressedTiles;
			}
			terrain.put(key, normalized);
		}

		TreeMap<Integer,Map<String,Object>> placements = byLevelWithSource(
			basePlacements, baseRelative);
		TreeMap<Integer,Map<String,Object>> legacyPlacementRecords = byLevelWithSource(
			legacyPlacements, legacyRelative);
		for (Map.Entry<Integer,Map<String,Object>> entry
			: legacyPlacementRecords.entrySet()) {
			if (!placements.containsKey(entry.getKey())) {
				Map<String,Object> normalized = entry.getValue();
				normalized.put("worldSpace", base.worldSpace);
				normalized.put("__rewritePlacementWorldSpace", base.worldSpace);
				placements.put(entry.getKey(), normalized);
			}
		}

		List<Map<String,Object>> terrainRecords =
			new ArrayList<Map<String,Object>>(terrain.values());
		Collections.sort(terrainRecords, TERRAIN_ORDER);
		List<Object> outputTerrain = new ArrayList<Object>(terrainRecords.size());
		Set<String> outputPaths = new HashSet<String>();
		for (Map<String,Object> sourced : terrainRecords) {
			String sourceRoot = text(sourced.remove("__sourceRoot"));
			Object rawSyntheticPayload = sourced.remove("__syntheticPayload");
			byte[] syntheticPayload = rawSyntheticPayload instanceof byte[]
				? (byte[])rawSyntheticPayload : null;
			Map<String,Object> declaration = copy(sourced);
			if (syntheticPayload == null) {
				copyPayload(target, sourceRoot, declaration, outputRoot, outputPaths);
			} else {
				writeSyntheticPayload(
					syntheticPayload, declaration, outputRoot, outputPaths);
			}
			outputTerrain.add(declaration);
		}

		List<Object> outputPlacements = new ArrayList<Object>(placements.size());
		for (Map<String,Object> sourced : placements.values()) {
			String sourceRoot = text(sourced.remove("__sourceRoot"));
			String rewriteWorldSpace = text(
				sourced.remove("__rewritePlacementWorldSpace"));
			Map<String,Object> declaration = copy(sourced);
			copyPayload(target, sourceRoot, declaration, outputRoot, outputPaths,
				rewriteWorldSpace);
			outputPlacements.add(declaration);
		}

		Map<String,Object> manifest = copy(baseManifest);
		manifest.put("levels", new ArrayList<Object>(levels.values()));
		manifest.put("terrainSectors", outputTerrain);
		manifest.put("placementSets", outputPlacements);
		Files.write(outputRoot.resolve("manifest.json"),
			WorldBuilderJsonDocuments.pretty(manifest).getBytes(StandardCharsets.UTF_8),
			StandardOpenOption.CREATE_NEW);

		WorldBuilderGenericLayeredPackage composed =
			WorldBuilderGenericLayeredPackage.inspect(
				WorldBuilderReadOnlyTarget.open(projectRoot), outputRelative,
				"composition-output", definitions);
		List<Integer> preserved = new ArrayList<Integer>(baseLevelNumbers);
		Collections.sort(preserved);
		return new Result(base.fingerprintSha256, legacy.fingerprintSha256,
			composed.fingerprintSha256, replaced, added, relocatedSectorsMerged,
			relocatedTilesSuppressed, preserved);
	}

	private static RelocatedTerrainMerge suppressRelocatedLegacyTiles(
		WorldBuilderReadOnlyTarget target, String baseRelative, String legacyRelative,
		Map<String,Object> baseAtIdentity, Map<String,Object> legacy,
		List<Map<String,Object>> sameSectorBase, Set<String> occupiedBaseSectors)
		throws IOException, WorldBuilderContractException {
		int level = number(legacy, "level");
		int sectorX = number(legacy, "sectorX");
		int sectorY = number(legacy, "sectorY");
		if (occupiedBaseSectors.contains(level + ":" + sectorX + ":" + sectorY)
			|| sameSectorBase == null) return RelocatedTerrainMerge.none();
		List<Map<String,Object>> relocationLevels =
			new ArrayList<Map<String,Object>>();
		for (Map<String,Object> candidate : sameSectorBase) {
			int candidateLevel = number(candidate, "level");
			if (candidateLevel < -1 || candidateLevel > 2) {
				relocationLevels.add(candidate);
			}
		}
		if (relocationLevels.isEmpty()) return RelocatedTerrainMerge.none();
		byte[] basePayload = baseAtIdentity == null
			? canonicalVoidPayload()
			: v2Payload(terrainPayload(target, baseRelative, baseAtIdentity),
				text(baseAtIdentity.get("encoding")));
		byte[] legacyPayload = v2Payload(terrainPayload(
			target, legacyRelative, legacy), text(legacy.get("encoding")));
		List<byte[]> candidatePayloads = new ArrayList<byte[]>();
		for (Map<String,Object> candidate : relocationLevels) {
			candidatePayloads.add(v2Payload(
				terrainPayload(target, baseRelative, candidate),
				text(candidate.get("encoding"))));
		}
		byte[] output = legacyPayload.clone();
		int suppressed = 0;
		for (int tile = 0; tile < WorldBuilderRawLayeredTerrainCodec.TILE_COUNT; tile++) {
			if (isVoidTile(basePayload, tile) && !isVoidTile(legacyPayload, tile)) {
				for (byte[] candidate : candidatePayloads) {
					if (sameTile(legacyPayload, candidate, tile)) {
						int offset = tile * WorldBuilderRawLayeredTerrainCodec.V2_TILE_BYTES;
						System.arraycopy(basePayload, offset, output, offset,
							WorldBuilderRawLayeredTerrainCodec.V2_TILE_BYTES);
						suppressed++;
						break;
					}
				}
			}
		}
		return suppressed == 0
			? RelocatedTerrainMerge.none()
			: new RelocatedTerrainMerge(output, suppressed);
	}

	private static byte[] terrainPayload(WorldBuilderReadOnlyTarget target,
		String packageRelative, Map<String,Object> declaration)
		throws IOException, WorldBuilderContractException {
		String relative = text(declaration.get("path"));
		byte[] payload = Files.readAllBytes(
			target.requiredFile(packageRelative + "/" + relative));
		if (!WorldBuilderHashes.sha256(payload).equals(text(declaration.get("sha256")))) {
			throw problem(WorldBuilderErrorCodes.TARGET_DRIFT, relative,
				"A layered terrain payload changed during composition.",
				"Stop source changes and repeat project creation.");
		}
		return payload;
	}

	private static byte[] v2Payload(byte[] payload, String encoding)
		throws WorldBuilderContractException {
		if (!WorldBuilderRawLayeredTerrainCodec.isWide(encoding)) {
			return WorldBuilderRawLayeredTerrainCodec.promoteV1(payload);
		}
		WorldBuilderRawLayeredTerrainCodec.requireDecodable(payload, encoding);
		return payload;
	}

	private static boolean isVoidTile(byte[] payload, int tile) {
		int offset = tile * WorldBuilderRawLayeredTerrainCodec.V2_TILE_BYTES;
		return payload[offset] == 0 && payload[offset + 1] == 0
			&& (payload[offset + 3] & 0xff) == 8
			&& payload[offset + 4] == 0 && payload[offset + 5] == 0
			&& payload[offset + 6] == 0 && payload[offset + 7] == 0
			&& payload[offset + 8] == 0 && payload[offset + 9] == 0
			&& payload[offset + 10] == 0;
	}

	private static boolean sameTile(byte[] left, byte[] right, int tile) {
		int offset = tile * WorldBuilderRawLayeredTerrainCodec.V2_TILE_BYTES;
		for (int index = 0;
			index < WorldBuilderRawLayeredTerrainCodec.V2_TILE_BYTES; index++) {
			if (left[offset + index] != right[offset + index]) return false;
		}
		return true;
	}

	private static byte[] canonicalVoidPayload() {
		byte[] tile = WorldBuilderRawLayeredTerrainCodec.encodeV2Tile(
			0, 1, 8, 0, 0, 0, 0);
		byte[] result = new byte[WorldBuilderRawLayeredTerrainCodec.BYTE_COUNT];
		for (int index = 0; index < WorldBuilderRawLayeredTerrainCodec.TILE_COUNT; index++) {
			System.arraycopy(tile, 0, result,
				index * WorldBuilderRawLayeredTerrainCodec.V2_TILE_BYTES, tile.length);
		}
		return result;
	}

	private static Set<String> occupiedPlacementSectors(
		WorldBuilderReadOnlyTarget target, String packageRelative,
		List<Object> declarations)
		throws WorldBuilderContractException {
		Set<String> result = new HashSet<String>();
		for (Object raw : declarations) {
			Map<String,Object> declaration = object(raw, "base placement declaration");
			int level = number(declaration, "level");
			String path = text(declaration.get("path"));
			Map<String,Object> payload = target.readObject(packageRelative + "/" + path);
			for (String family : Arrays.asList(
				"boundaries", "groundItems", "npcs", "scenery")) {
				for (Object placementRaw : list(payload, family)) {
					Map<String,Object> placement = object(placementRaw, family);
					String pointName = "npcs".equals(family) ? "start" : "position";
					Map<String,Object> point = object(placement.get(pointName), pointName);
					int x = Math.floorDiv(number(point, "x"), 48);
					int y = Math.floorDiv(number(point, "y"), 48);
					result.add(level + ":" + x + ":" + y);
				}
			}
		}
		return result;
	}

	private static void writeSyntheticPayload(byte[] payload,
		Map<String,Object> declaration, Path outputRoot, Set<String> outputPaths)
		throws IOException, WorldBuilderContractException {
		String relative = text(declaration.get("path"));
		if (relative.isEmpty() || relative.startsWith("/")
			|| relative.indexOf('\\') >= 0 || !outputPaths.add(relative)) {
			throw problem(WorldBuilderErrorCodes.UNSAFE_PATH, relative,
				"A composed package payload path is unsafe or duplicated.",
				"Use complete canonical layered packages without path collisions.");
		}
		Path output = outputRoot.resolve(relative).normalize();
		if (!output.startsWith(outputRoot)) {
			throw problem(WorldBuilderErrorCodes.UNSAFE_PATH, relative,
				"A composed package payload escapes its package root.",
				"Use complete canonical layered packages with contained paths.");
		}
		Files.createDirectories(output.getParent());
		Files.write(output, payload, StandardOpenOption.CREATE_NEW);
		declaration.put("sha256", WorldBuilderHashes.sha256(payload));
	}

	private static void copyPayload(WorldBuilderReadOnlyTarget target,
		String sourceRoot, Map<String,Object> declaration, Path outputRoot,
		Set<String> outputPaths)
		throws IOException, WorldBuilderContractException {
		copyPayload(target, sourceRoot, declaration, outputRoot, outputPaths, "");
	}

	private static void copyPayload(WorldBuilderReadOnlyTarget target,
		String sourceRoot, Map<String,Object> declaration, Path outputRoot,
		Set<String> outputPaths, String rewriteWorldSpace)
		throws IOException, WorldBuilderContractException {
		String relative = text(declaration.get("path"));
		if (relative.isEmpty() || relative.startsWith("/")
			|| relative.indexOf('\\') >= 0 || !outputPaths.add(relative)) {
			throw problem(WorldBuilderErrorCodes.UNSAFE_PATH, relative,
				"A composed package payload path is unsafe or duplicated.",
				"Use complete canonical layered packages without path collisions.");
		}
		Path output = outputRoot.resolve(relative).normalize();
		if (!output.startsWith(outputRoot)) {
			throw problem(WorldBuilderErrorCodes.UNSAFE_PATH, relative,
				"A composed package payload escapes its package root.",
				"Use complete canonical layered packages with contained paths.");
		}
		Path source = target.requiredFile(sourceRoot + "/" + relative);
		Files.createDirectories(output.getParent());
		Files.copy(source, output, StandardCopyOption.COPY_ATTRIBUTES);
		if (!rewriteWorldSpace.isEmpty()) {
			try {
				Map<String,Object> payload = WorldBuilderJsonDocuments.readObject(output);
				payload.put("worldSpace", rewriteWorldSpace);
				Files.write(output, WorldBuilderJsonDocuments.pretty(payload)
					.getBytes(StandardCharsets.UTF_8), StandardOpenOption.TRUNCATE_EXISTING);
				declaration.put("sha256", WorldBuilderHashes.sha256(output));
			} catch (WorldBuilderDiscoveryException malformed) {
				throw problem(WorldBuilderErrorCodes.MALFORMED_SERVER, relative,
					"A legacy placement payload could not be normalized to the layered base world space.",
					"Use strictly validated matching map packages.");
			}
		}
		String hash = WorldBuilderHashes.sha256(output);
		if (!hash.equals(text(declaration.get("sha256")))) {
			throw problem(WorldBuilderErrorCodes.TARGET_DRIFT, relative,
				"A layered payload changed during composition.",
				"Stop source changes and repeat project creation.");
		}
	}

	private static TreeMap<Integer,Map<String,Object>> byLevel(List<Object> values)
		throws WorldBuilderContractException {
		TreeMap<Integer,Map<String,Object>> result =
			new TreeMap<Integer,Map<String,Object>>();
		for (Object raw : values) {
			Map<String,Object> record = object(raw, "level declaration");
			result.put(Integer.valueOf(number(record, "level")), copy(record));
		}
		return result;
	}

	private static TreeMap<Integer,Map<String,Object>> byLevelWithSource(
		List<Object> values, String sourceRoot) throws WorldBuilderContractException {
		TreeMap<Integer,Map<String,Object>> result =
			new TreeMap<Integer,Map<String,Object>>();
		for (Object raw : values) {
			Map<String,Object> record = sourceRecord(
				object(raw, "placement declaration"), sourceRoot);
			result.put(Integer.valueOf(number(record, "level")), record);
		}
		return result;
	}

	private static Map<String,Object> sourceRecord(
		Map<String,Object> value, String sourceRoot) {
		Map<String,Object> result = copy(value);
		result.put("__sourceRoot", sourceRoot);
		return result;
	}

	private static String terrainKey(Map<String,Object> value)
		throws WorldBuilderContractException {
		return number(value, "level") + ":" + number(value, "sectorX")
			+ ":" + number(value, "sectorY");
	}

	private static String sectorKey(Map<String,Object> value)
		throws WorldBuilderContractException {
		return number(value, "sectorX") + ":" + number(value, "sectorY");
	}

	private static final Comparator<Map<String,Object>> TERRAIN_ORDER =
		new Comparator<Map<String,Object>>() {
			@Override public int compare(Map<String,Object> left,
				Map<String,Object> right) {
				int result = Integer.compare(uncheckedNumber(left, "level"),
					uncheckedNumber(right, "level"));
				if (result == 0) result = Integer.compare(
					uncheckedNumber(left, "sectorX"), uncheckedNumber(right, "sectorX"));
				if (result == 0) result = Integer.compare(
					uncheckedNumber(left, "sectorY"), uncheckedNumber(right, "sectorY"));
				return result;
			}
		};

	private static int uncheckedNumber(Map<String,Object> value, String key) {
		return ((Number)value.get(key)).intValue();
	}

	private static int number(Map<String,Object> value, String key)
		throws WorldBuilderContractException {
		Object raw = value.get(key);
		if (!(raw instanceof Number)) {
			throw problem(WorldBuilderErrorCodes.MALFORMED_SERVER, key,
				"A layered declaration is missing its signed coordinate.",
				"Use a strictly validated layered package.");
		}
		return ((Number)raw).intValue();
	}

	private static List<Object> list(Map<String,Object> value, String key)
		throws WorldBuilderContractException {
		Object raw = value.get(key);
		if (!(raw instanceof List)) {
			throw problem(WorldBuilderErrorCodes.MALFORMED_SERVER, key,
				"A layered manifest array is missing.",
				"Use a strictly validated layered package.");
		}
		@SuppressWarnings("unchecked") List<Object> result = (List<Object>)raw;
		return result;
	}

	private static Map<String,Object> object(Object raw, String label)
		throws WorldBuilderContractException {
		if (!(raw instanceof Map)) {
			throw problem(WorldBuilderErrorCodes.MALFORMED_SERVER, label,
				"A layered manifest record is malformed.",
				"Use a strictly validated layered package.");
		}
		@SuppressWarnings("unchecked") Map<String,Object> result = (Map<String,Object>)raw;
		return result;
	}

	private static Map<String,Object> copy(Map<String,Object> value) {
		return new LinkedHashMap<String,Object>(value);
	}

	private static String text(Object value) {
		return value instanceof String ? (String)value : "";
	}

	private static WorldBuilderContractException problem(String code, String path,
		String message, String nextStep) {
		return new WorldBuilderContractException(
			code, OPERATION, path, false, message, nextStep);
	}

	static final class Result {
		final String baseFingerprintSha256;
		final String legacyFingerprintSha256;
		final String outputFingerprintSha256;
		final int replacedTerrainSectors;
		final int addedTerrainSectors;
		final int relocatedTerrainSectorsMerged;
		final long relocatedLegacyTilesSuppressed;
		final List<Integer> preservedBaseLevels;

		Result(String baseFingerprintSha256, String legacyFingerprintSha256,
			String outputFingerprintSha256, int replacedTerrainSectors,
			int addedTerrainSectors, int relocatedTerrainSectorsMerged,
			long relocatedLegacyTilesSuppressed, List<Integer> preservedBaseLevels) {
			this.baseFingerprintSha256 = baseFingerprintSha256;
			this.legacyFingerprintSha256 = legacyFingerprintSha256;
			this.outputFingerprintSha256 = outputFingerprintSha256;
			this.replacedTerrainSectors = replacedTerrainSectors;
			this.addedTerrainSectors = addedTerrainSectors;
			this.relocatedTerrainSectorsMerged = relocatedTerrainSectorsMerged;
			this.relocatedLegacyTilesSuppressed = relocatedLegacyTilesSuppressed;
			this.preservedBaseLevels = Collections.unmodifiableList(
				new ArrayList<Integer>(preservedBaseLevels));
		}

		String toJson() {
			Map<String,Object> value = new LinkedHashMap<String,Object>();
			value.put("schemaVersion", Long.valueOf(1L));
			value.put("manifestType", "world-builder-layered-terrain-composition-report");
			value.put("baseFingerprintSha256", baseFingerprintSha256);
			value.put("legacyFingerprintSha256", legacyFingerprintSha256);
			value.put("outputFingerprintSha256", outputFingerprintSha256);
			value.put("replacedTerrainSectors", Long.valueOf(replacedTerrainSectors));
			value.put("addedTerrainSectors", Long.valueOf(addedTerrainSectors));
			value.put("relocatedTerrainSectorsMerged",
				Long.valueOf(relocatedTerrainSectorsMerged));
			value.put("relocatedLegacyTilesSuppressed",
				Long.valueOf(relocatedLegacyTilesSuppressed));
			List<Object> levels = new ArrayList<Object>();
			for (Integer level : preservedBaseLevels) {
				levels.add(Long.valueOf(level.longValue()));
			}
			value.put("preservedBaseLevels", levels);
			return WorldBuilderJsonDocuments.pretty(value);
		}
	}

	private static final class RelocatedTerrainMerge {
		final byte[] payload;
		final int suppressedTiles;

		RelocatedTerrainMerge(byte[] payload, int suppressedTiles) {
			this.payload = payload;
			this.suppressedTiles = suppressedTiles;
		}

		static RelocatedTerrainMerge none() {
			return new RelocatedTerrainMerge(new byte[0], 0);
		}
	}
}
