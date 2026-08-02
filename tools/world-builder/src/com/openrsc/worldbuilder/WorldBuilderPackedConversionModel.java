package com.openrsc.worldbuilder;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Exact packed terrain/placement model and content-neutral layered package writer. */
final class WorldBuilderPackedConversionModel {
	static final String PLACEMENT_COMPOSITION_PROFILE_ID =
		"declared-packed-static-composition-v1";
	static final String OUTPUT_PACKAGE_SCHEMA_ID = "layered-world-package-v1";
	static final int OUTPUT_ENCODING_VERSION = 1;
	private static final String WORLD_SPACE = "global";
	private static final int MAX_SECTORS = 65536;
	private static final int MAX_RECORDS = 65536;
	private static final Set<String> ALL_FAMILIES = Collections.unmodifiableSet(
		new HashSet<String>(Arrays.asList(
			"boundary", "ground-item", "npc", "scenery")));

	interface PlacementIdFactory {
		String create(String stableFacts);
	}

	private static final PlacementIdFactory HASHED_IDS = new PlacementIdFactory() {
		@Override
		public String create(String stableFacts) {
			return "p-" + WorldBuilderHashes.sha256(
				stableFacts.getBytes(StandardCharsets.UTF_8));
		}
	};

	final List<TerrainSector> terrain;
	final List<Placement> placements;
	final List<Integer> levels;
	final List<String> placementSemantics;
	final List<Object> placementSummaries;
	final List<Object> decisions;
	final int reverseMatched;

	private WorldBuilderPackedConversionModel(
		List<TerrainSector> terrain,
		List<Placement> placements,
		List<Integer> levels,
		List<String> placementSemantics,
		List<Object> placementSummaries,
		List<Object> decisions) {
		this.terrain = Collections.unmodifiableList(new ArrayList<TerrainSector>(terrain));
		this.placements = Collections.unmodifiableList(new ArrayList<Placement>(placements));
		this.levels = Collections.unmodifiableList(new ArrayList<Integer>(levels));
		this.placementSemantics = Collections.unmodifiableList(
			new ArrayList<String>(placementSemantics));
		this.placementSummaries = Collections.unmodifiableList(
			new ArrayList<Object>(placementSummaries));
		this.decisions = Collections.unmodifiableList(new ArrayList<Object>(decisions));
		this.reverseMatched = terrain.size();
	}

	static WorldBuilderPackedConversionModel read(
		WorldBuilderPackedConversionSource source,
		WorldBuilderAdaptiveConfiguration configuration,
		WorldBuilderCompatibilityEvidence.DefinitionCatalog definitions)
		throws WorldBuilderContractException {
		return read(source, configuration, definitions, HASHED_IDS);
	}

	static WorldBuilderPackedConversionModel read(
		WorldBuilderPackedConversionSource source,
		WorldBuilderAdaptiveConfiguration configuration,
		WorldBuilderCompatibilityEvidence.DefinitionCatalog definitions,
		PlacementIdFactory idFactory) throws WorldBuilderContractException {
		if (idFactory == null) idFactory = HASHED_IDS;
		source.requireInput("server-terrain", configuration.serverMapRelativePath);
		source.requireInput("client-terrain", configuration.clientMapRelativePath);
		List<TerrainSector> terrain = readTerrain(
			source.target.requiredFile(configuration.serverMapRelativePath),
			configuration.serverMapRelativePath, definitions);
		Set<String> terrainCoverage = new HashSet<String>();
		Set<Integer> levelSet = new TreeSet<Integer>();
		for (TerrainSector sector : terrain) {
			if (!terrainCoverage.add(sector.coordinate.key())) {
				throw blocked(configuration.serverMapRelativePath,
					"Packed terrain contains duplicate normalized sector coordinates: "
						+ sector.coordinate.key() + ".");
			}
			levelSet.add(Integer.valueOf(sector.coordinate.level));
		}

		Map<String,Map<String,Placement>> effective =
			new LinkedHashMap<String,Map<String,Placement>>();
		for (String family : ALL_FAMILIES) {
			effective.put(family, new LinkedHashMap<String,Placement>());
		}
		Set<String> declaredFamilies = new HashSet<String>();
		Set<String> generatedIds = new HashSet<String>();
		List<Decision> decisions = new ArrayList<Decision>();
		for (WorldBuilderAdaptiveConfiguration.PlacementSource placementSource
			: configuration.placements) {
			declaredFamilies.add(placementSource.family);
			String inputRole = "placement." + placementSource.role;
			source.requireInput(inputRole, placementSource.relativePath);
			requireEncoding(placementSource);
			List<Placement> records = parsePlacementSource(source, placementSource,
				definitions, idFactory, generatedIds);
			Map<String,Placement> family = effective.get(placementSource.family);
			Set<String> sourceSlots = new HashSet<String>();
			for (Placement placement : records) {
				if (!sourceSlots.add(placement.slot)) {
					throw blocked(placementSource.relativePath,
						"Packed placement source repeats effective slot at record "
							+ placement.recordIndex + ".");
				}
				if ("removal".equals(placementSource.kind)) {
					Placement removed = family.get(placement.slot);
					if (removed == null || !placement.removesExactly(removed)) {
						throw blocked(placementSource.relativePath,
							"Packed removal at record " + placement.recordIndex
								+ " does not exactly match an earlier effective placement.");
					}
					family.remove(placement.slot);
					decisions.add(new Decision("removal", inputRole,
						placement.provenance + " removes " + removed.provenance,
						removed.placementId, "removed"));
				} else if ("base".equals(placementSource.kind)) {
					if (family.containsKey(placement.slot)) {
						throw blocked(placementSource.relativePath,
							"Packed base placement collides at record "
								+ placement.recordIndex + ".");
					}
					family.put(placement.slot, placement);
				} else if ("overlay".equals(placementSource.kind)) {
					Placement replaced = family.put(placement.slot, placement);
					if (replaced != null) {
						decisions.add(new Decision("replacement", inputRole,
							placement.provenance + " replaces " + replaced.provenance,
							replaced.placementId, "replaced"));
					}
				} else {
					throw blocked(placementSource.relativePath,
						"Packed placement source has an unsupported composition kind.");
				}
			}
		}
		if (!declaredFamilies.equals(ALL_FAMILIES)) {
			throw blocked(configuration.relativePath,
				"Packed conversion requires explicit inputs for all four placement families.");
		}

		List<Placement> placements = new ArrayList<Placement>();
		for (Map<String,Placement> family : effective.values()) {
			placements.addAll(family.values());
		}
		Collections.sort(placements);
		List<String> semantics = new ArrayList<String>(placements.size());
		Map<SummaryKey,Long> summaries = new TreeMap<SummaryKey,Long>();
		Map<Integer,Integer> perLevel = new HashMap<Integer,Integer>();
		for (Placement placement : placements) {
			requireCoverage(terrainCoverage, placement.level, placement.x, placement.y,
				placement.provenance);
			if (placement.minimum != null) {
				requireCoverageRectangle(terrainCoverage, placement.level,
					placement.minimum, placement.maximum, placement.provenance);
			}
			semantics.add(placement.semantic());
			SummaryKey summary = new SummaryKey(placement.family, placement.level,
				placement.sourceRole, placement.definitionId);
			Long count = summaries.get(summary);
			summaries.put(summary, Long.valueOf(count == null ? 1L : count.longValue() + 1L));
			Integer levelCount = perLevel.get(Integer.valueOf(placement.level));
			int next = levelCount == null ? 1 : levelCount.intValue() + 1;
			if (next > MAX_RECORDS) {
				throw blocked(placement.sourcePath,
					"Converted placement set exceeds 65,536 records on level "
						+ placement.level + ".");
			}
			perLevel.put(Integer.valueOf(placement.level), Integer.valueOf(next));
			decisions.add(new Decision("precedence", placement.sourceRole,
				placement.provenance, placement.placementId, "retained"));
		}
		Collections.sort(semantics);
		Collections.sort(decisions);
		if (decisions.size() > WorldBuilderContractLimits.MAX_PLACEMENT_SUMMARIES) {
			throw blocked(configuration.relativePath,
				"Conversion decisions exceed the bounded report contract.");
		}
		List<Object> decisionDocuments = new ArrayList<Object>(decisions.size());
		Decision previous = null;
		for (Decision decision : decisions) {
			if (previous != null && previous.compareTo(decision) == 0) {
				throw blocked(configuration.relativePath,
					"Conversion produced duplicate provenance decisions.");
			}
			decisionDocuments.add(decision.toJson());
			previous = decision;
		}
		List<Object> summaryDocuments = new ArrayList<Object>(summaries.size());
		for (Map.Entry<SummaryKey,Long> summary : summaries.entrySet()) {
			summaryDocuments.add(summary.getKey().toJson(summary.getValue().longValue()));
		}
		return new WorldBuilderPackedConversionModel(terrain, placements,
			new ArrayList<Integer>(levelSet), semantics, summaryDocuments,
			decisionDocuments);
	}

	void writePackage(Path packageRoot, String sourceFingerprintSha256)
		throws IOException, WorldBuilderContractException {
		Files.createDirectories(packageRoot);
		List<Object> terrainDeclarations = new ArrayList<Object>(terrain.size());
		for (TerrainSector sector : terrain) {
			String relative = terrainPath(sector.coordinate);
			Path payload = packageRoot.resolve(relative).normalize();
			requireContained(packageRoot, payload, relative);
			Files.createDirectories(payload.getParent());
			Files.write(payload, sector.layeredBytes);
			Map<String,Object> declaration = new LinkedHashMap<String,Object>();
			declaration.put("encoding", WorldBuilderPackedTerrainCodec.OUTPUT_ENCODING);
			declaration.put("level", Long.valueOf(sector.coordinate.level));
			declaration.put("path", relative);
			declaration.put("sectorX", Long.valueOf(sector.coordinate.sectorX));
			declaration.put("sectorY", Long.valueOf(sector.coordinate.sectorY));
			declaration.put("sha256", WorldBuilderHashes.sha256(payload));
			declaration.put("worldSpace", WORLD_SPACE);
			terrainDeclarations.add(declaration);
		}

		Map<Integer,List<Placement>> byLevel = new TreeMap<Integer,List<Placement>>();
		for (Integer level : levels) byLevel.put(level, new ArrayList<Placement>());
		for (Placement placement : placements) {
			List<Placement> level = byLevel.get(Integer.valueOf(placement.level));
			if (level == null) throw new AssertionError("placement without terrain level");
			level.add(placement);
		}
		List<Object> placementDeclarations = new ArrayList<Object>(levels.size());
		for (Map.Entry<Integer,List<Placement>> level : byLevel.entrySet()) {
			Collections.sort(level.getValue());
			Map<String,Object> payload = placementPayload(
				level.getKey().intValue(), level.getValue());
			String relative = placementPath(level.getKey().intValue());
			Path path = packageRoot.resolve(relative).normalize();
			requireContained(packageRoot, path, relative);
			Files.createDirectories(path.getParent());
			Files.write(path, WorldBuilderJsonDocuments.pretty(payload)
				.getBytes(StandardCharsets.UTF_8));
			Map<String,Object> declaration = new LinkedHashMap<String,Object>();
			declaration.put("encoding", "layered-world-placements-v3");
			declaration.put("id", "global-l"
				+ WorldBuilderLayeredPackage.signedToken(level.getKey().intValue()));
			declaration.put("level", Long.valueOf(level.getKey().intValue()));
			declaration.put("path", relative);
			declaration.put("sha256", WorldBuilderHashes.sha256(path));
			declaration.put("worldSpace", WORLD_SPACE);
			placementDeclarations.add(declaration);
		}

		Map<String,Object> manifest = new LinkedHashMap<String,Object>();
		manifest.put("schemaVersion", Long.valueOf(1L));
		manifest.put("packageType", "layered-world");
		manifest.put("packageId", "world-builder.converted." + sourceFingerprintSha256);
		manifest.put("packageVersion", "1.0.0");
		manifest.put("coordinateModel", "signed-layered-v1");
		Map<String,Object> storage = new LinkedHashMap<String,Object>();
		storage.put("presentationChunkSize", Long.valueOf(24L));
		storage.put("sectorSize", Long.valueOf(48L));
		manifest.put("storage", storage);
		List<Object> worlds = new ArrayList<Object>();
		Map<String,Object> world = new LinkedHashMap<String,Object>();
		world.put("id", WORLD_SPACE);
		world.put("kind", "static");
		worlds.add(world);
		manifest.put("worldSpaces", worlds);
		List<Object> levelDocuments = new ArrayList<Object>(levels.size());
		for (Integer level : levels) {
			Map<String,Object> value = new LinkedHashMap<String,Object>();
			value.put("level", Long.valueOf(level.intValue()));
			value.put("name", "Level " + level);
			value.put("role", "level-" + WorldBuilderLayeredPackage.signedToken(level.intValue()));
			value.put("worldSpace", WORLD_SPACE);
			levelDocuments.add(value);
		}
		manifest.put("levels", levelDocuments);
		manifest.put("terrainSectors", terrainDeclarations);
		manifest.put("placementSets", placementDeclarations);
		Files.write(packageRoot.resolve("manifest.json"),
			WorldBuilderJsonDocuments.pretty(manifest).getBytes(StandardCharsets.UTF_8));
	}

	private static List<TerrainSector> readTerrain(Path archive, String relative,
		WorldBuilderCompatibilityEvidence.DefinitionCatalog definitions)
		throws WorldBuilderContractException {
		List<TerrainSector> result = new ArrayList<TerrainSector>();
		Set<String> names = new HashSet<String>();
		Set<String> coordinates = new HashSet<String>();
		try (ZipFile zip = new ZipFile(archive.toFile())) {
			Enumeration<? extends ZipEntry> entries = zip.entries();
			while (entries.hasMoreElements()) {
				ZipEntry entry = entries.nextElement();
				if (entry.isDirectory() || entry.getMethod() != ZipEntry.STORED
					&& entry.getMethod() != ZipEntry.DEFLATED) {
					throw blocked(relative,
						"Packed terrain contains an unsupported ZIP record: " + entry.getName() + ".");
				}
				if (result.size() >= MAX_SECTORS || !names.add(entry.getName())) {
					throw blocked(relative,
						"Packed terrain contains too many or duplicate ZIP entries: "
							+ entry.getName() + ".");
				}
				WorldBuilderPackedCoordinateCodec.Sector coordinate;
				try {
					coordinate = WorldBuilderPackedCoordinateCodec.decodeTerrainEntry(entry.getName());
				} catch (WorldBuilderContractException invalid) {
					throw wrap(invalid, relative, "terrain entry " + entry.getName());
				}
				if (!coordinates.add(coordinate.key())) {
					throw blocked(relative,
						"Packed terrain entry duplicates normalized coordinate "
							+ coordinate.key() + ": " + entry.getName() + ".");
				}
				byte[] legacy = readExact(zip, entry, relative);
				byte[] layered = WorldBuilderPackedTerrainCodec.toLayered(legacy);
				WorldBuilderPackedTerrainCodec.requireExactReverse(legacy, layered);
				WorldBuilderRawLayeredTerrainCodec.requireDecodable(layered);
				validateTerrainDefinitions(layered, definitions, relative, entry.getName());
				result.add(new TerrainSector(coordinate, legacy, layered,
					"server-terrain", relative, entry.getName()));
			}
		} catch (WorldBuilderContractException refusal) {
			throw refusal;
		} catch (IOException failure) {
			throw new WorldBuilderContractException(WorldBuilderErrorCodes.UNSUPPORTED_FORMAT,
				"convert-packed", relative, false,
				"Packed terrain archive cannot be decoded exactly.",
				"Restore the immutable valid ZIP bytes and retry conversion.", failure);
		}
		if (result.isEmpty()) throw blocked(relative, "Packed terrain archive contains no sectors.");
		Collections.sort(result);
		return result;
	}

	private static byte[] readExact(ZipFile zip, ZipEntry entry, String relative)
		throws IOException, WorldBuilderContractException {
		ByteArrayOutputStream output = new ByteArrayOutputStream(
			WorldBuilderPackedTerrainCodec.BYTE_COUNT);
		try (InputStream input = zip.getInputStream(entry)) {
			byte[] buffer = new byte[8192];
			int read;
			while ((read = input.read(buffer)) >= 0) {
				if (read == 0) continue;
				if (output.size() + read > WorldBuilderPackedTerrainCodec.BYTE_COUNT) {
					throw blocked(relative, "Packed terrain entry exceeds 23,040 bytes: "
						+ entry.getName() + ".");
				}
				output.write(buffer, 0, read);
			}
		}
		if (output.size() != WorldBuilderPackedTerrainCodec.BYTE_COUNT) {
			throw blocked(relative, "Packed terrain entry is not exactly 23,040 bytes: "
				+ entry.getName() + ".");
		}
		return output.toByteArray();
	}

	private static void validateTerrainDefinitions(byte[] layered,
		WorldBuilderCompatibilityEvidence.DefinitionCatalog definitions,
		String path, String entry) throws WorldBuilderContractException {
		for (int offset = 0, tile = 0; offset < layered.length;
			offset += WorldBuilderPackedTerrainCodec.TILE_BYTES, tile++) {
			int overlay = layered[offset + 2] & 0xff;
			int effectiveOverlay = overlay == 250 ? 2 : overlay;
			if (effectiveOverlay > 0
				&& !definitions.tiles.contains(Integer.valueOf(effectiveOverlay - 1))) {
				throw definition(path, entry, tile, "tile", effectiveOverlay - 1);
			}
			int vertical = layered[offset + 4] & 0xff;
			int horizontal = layered[offset + 5] & 0xff;
			if (vertical > 0
				&& !definitions.boundaries.contains(Integer.valueOf(vertical - 1))) {
				throw definition(path, entry, tile, "boundary", vertical - 1);
			}
			if (horizontal > 0
				&& !definitions.boundaries.contains(Integer.valueOf(horizontal - 1))) {
				throw definition(path, entry, tile, "boundary", horizontal - 1);
			}
			int diagonal = ByteBuffer.wrap(layered, offset + 6, 4).getInt();
			if (diagonal != 0) {
				int definitionId;
				if (diagonal > 0 && diagonal < 12000) definitionId = diagonal - 1;
				else if (diagonal > 12000 && diagonal < 24000) definitionId = diagonal - 12001;
				else throw blocked(path, "Unsupported diagonal boundary encoding in "
					+ entry + " at tile " + tile + ": " + diagonal + ".");
				if (!definitions.boundaries.contains(Integer.valueOf(definitionId))) {
					throw definition(path, entry, tile, "boundary", definitionId);
				}
			}
		}
	}

	private static List<Placement> parsePlacementSource(
		WorldBuilderPackedConversionSource conversionSource,
		WorldBuilderAdaptiveConfiguration.PlacementSource source,
		WorldBuilderCompatibilityEvidence.DefinitionCatalog definitions,
		PlacementIdFactory idFactory,
		Set<String> generatedIds) throws WorldBuilderContractException {
		Map<String,Object> root = conversionSource.target.readObject(source.relativePath);
		String rootKey = rootKey(source.family, source.kind);
		if (root.size() != 1 || !root.containsKey(rootKey)) {
			throw blocked(source.relativePath,
				"Packed placement document must contain only array " + rootKey + ".");
		}
		Object raw = root.get(rootKey);
		if (!(raw instanceof List) || ((List<?>)raw).size() > MAX_RECORDS) {
			throw blocked(source.relativePath,
				"Packed placement array is absent or exceeds 65,536 records.");
		}
		List<?> records = (List<?>)raw;
		List<Placement> result = new ArrayList<Placement>(records.size());
		for (int index = 0; index < records.size(); index++) {
			Map<String,Object> record = object(records.get(index), source.relativePath, index);
			Placement placement;
			if ("boundary".equals(source.family)) {
				placement = boundary(record, source, definitions, index);
			} else if ("ground-item".equals(source.family)) {
				placement = groundItem(record, source, definitions, index);
			} else if ("npc".equals(source.family)) {
				placement = npc(record, source, definitions, index);
			} else if ("scenery".equals(source.family)) {
				placement = scenery(record, source, definitions, index);
			} else {
				throw blocked(source.relativePath, "Unsupported placement family: " + source.family + ".");
			}
			if (!"removal".equals(source.kind)) {
				String facts = WorldBuilderPackedLayoutAdapter.ID + "\u0000"
					+ conversionSource.sourceFingerprintSha256 + "\u0000"
					+ source.role + "\u0000" + placement.semantic() + "\u0000" + index;
				String placementId = idFactory.create(facts);
				if (placementId == null
					|| !placementId.matches("[A-Za-z0-9][A-Za-z0-9._:+-]{0,127}")) {
					throw blocked(source.relativePath,
						"Deterministic placement ID is not a valid package identifier at record "
							+ index + ".");
				}
				if (!generatedIds.add(placementId)) {
					throw blocked(source.relativePath,
						"Deterministic placement ID collision at record " + index + ".");
				}
				placement = placement.withId(placementId);
			}
			result.add(placement);
		}
		return result;
	}

	private static Placement boundary(Map<String,Object> record,
		WorldBuilderAdaptiveConfiguration.PlacementSource source,
		WorldBuilderCompatibilityEvidence.DefinitionCatalog definitions, int index)
		throws WorldBuilderContractException {
		boolean removal = "removal".equals(source.kind);
		exact(record, source.relativePath, index, removal
			? new String[] {"direction", "pos"}
			: new String[] {"direction", "id", "pos"});
		WorldBuilderPackedCoordinateCodec.Coordinate point =
			point(record.get("pos"), source.relativePath, index);
		int direction = nonnegative(record, "direction", source.relativePath, index);
		if (direction > 3) throw recordError(source.relativePath, index,
			"Boundary direction is outside 0..3.");
		int id = removal ? -1 : nonnegative(record, "id", source.relativePath, index);
		if (!removal) definitions.require("boundary", id, source.relativePath);
		return new Placement("boundary", source, index, id, point.level,
			point.x, point.y, direction, 0, 0, null, null, "");
	}

	private static Placement groundItem(Map<String,Object> record,
		WorldBuilderAdaptiveConfiguration.PlacementSource source,
		WorldBuilderCompatibilityEvidence.DefinitionCatalog definitions, int index)
		throws WorldBuilderContractException {
		boolean removal = "removal".equals(source.kind);
		exact(record, source.relativePath, index, removal
			? new String[] {"id", "pos"}
			: new String[] {"amount", "id", "pos", "respawn"});
		WorldBuilderPackedCoordinateCodec.Coordinate point =
			point(record.get("pos"), source.relativePath, index);
		int id = nonnegative(record, "id", source.relativePath, index);
		int amount = removal ? 0 : nonnegative(record, "amount", source.relativePath, index);
		int respawn = removal ? 0 : nonnegative(record, "respawn", source.relativePath, index);
		if (!removal && (amount < 1 || respawn > 86400)) {
			throw recordError(source.relativePath, index,
				"Ground-item amount or respawn is outside the exact supported range.");
		}
		if (!removal) definitions.require("ground-item", id, source.relativePath);
		return new Placement("ground-item", source, index, id, point.level,
			point.x, point.y, 0, amount, respawn, null, null, "");
	}

	private static Placement npc(Map<String,Object> record,
		WorldBuilderAdaptiveConfiguration.PlacementSource source,
		WorldBuilderCompatibilityEvidence.DefinitionCatalog definitions, int index)
		throws WorldBuilderContractException {
		exact(record, source.relativePath, index, "id", "max", "min", "start");
		int id = nonnegative(record, "id", source.relativePath, index);
		WorldBuilderPackedCoordinateCodec.Coordinate start =
			point(record.get("start"), source.relativePath, index);
		WorldBuilderPackedCoordinateCodec.Coordinate minimum =
			point(record.get("min"), source.relativePath, index);
		WorldBuilderPackedCoordinateCodec.Coordinate maximum =
			point(record.get("max"), source.relativePath, index);
		if (start.level != minimum.level || start.level != maximum.level
			|| minimum.x > start.x || start.x > maximum.x
			|| minimum.y > start.y || start.y > maximum.y
			|| (long)maximum.x - minimum.x > 128L
			|| (long)maximum.y - minimum.y > 128L) {
			throw recordError(source.relativePath, index,
				"NPC roam bounds are invalid, cross levels, or exceed 128 tiles.");
		}
		if (!"removal".equals(source.kind)) definitions.require("npc", id, source.relativePath);
		return new Placement("npc", source, index, id, start.level, start.x, start.y,
			0, 0, 0, minimum, maximum, "");
	}

	private static Placement scenery(Map<String,Object> record,
		WorldBuilderAdaptiveConfiguration.PlacementSource source,
		WorldBuilderCompatibilityEvidence.DefinitionCatalog definitions, int index)
		throws WorldBuilderContractException {
		boolean removal = "removal".equals(source.kind);
		exact(record, source.relativePath, index, removal
			? new String[] {"pos"}
			: new String[] {"direction", "id", "pos"});
		WorldBuilderPackedCoordinateCodec.Coordinate point =
			point(record.get("pos"), source.relativePath, index);
		int id = removal ? -1 : nonnegative(record, "id", source.relativePath, index);
		int direction = removal ? 0
			: nonnegative(record, "direction", source.relativePath, index);
		if (!removal && direction > 7) throw recordError(source.relativePath, index,
			"Scenery direction is outside 0..7.");
		if (!removal) definitions.require("scenery", id, source.relativePath);
		return new Placement("scenery", source, index, id, point.level,
			point.x, point.y, direction, 0, 0, null, null, "");
	}

	private static WorldBuilderPackedCoordinateCodec.Coordinate point(
		Object raw, String path, int index) throws WorldBuilderContractException {
		Map<String,Object> point = object(raw, path, index);
		exact(point, path, index, "X", "Y");
		int x = signed(point, "X", path, index);
		int y = signed(point, "Y", path, index);
		WorldBuilderPackedCoordinateCodec.Coordinate decoded =
			WorldBuilderPackedCoordinateCodec.decode(x, y);
		WorldBuilderPackedCoordinateCodec.PackedCoordinate reversed =
			WorldBuilderPackedCoordinateCodec.encode(decoded);
		if (reversed.x != x || reversed.y != y) {
			throw recordError(path, index,
				"Placement coordinate did not reverse to the exact packed values.");
		}
		return decoded;
	}

	private static void requireEncoding(
		WorldBuilderAdaptiveConfiguration.PlacementSource source)
		throws WorldBuilderContractException {
		String expected = "packed-" + source.family + "-"
			+ ("removal".equals(source.kind) ? "removals" : "locations") + "-v1";
		if (!expected.equals(source.encoding)) {
			throw blocked(source.relativePath,
				"Unsupported packed placement encoding: " + source.encoding + ".");
		}
	}

	private static String rootKey(String family, String kind) {
		boolean removal = "removal".equals(kind);
		if ("boundary".equals(family)) return removal ? "boundary_removals" : "boundaries";
		if ("ground-item".equals(family)) return removal ? "ground_item_removals" : "ground_items";
		if ("npc".equals(family)) return removal ? "npc_removals" : "npclocs";
		return removal ? "scenery_removals" : "sceneries";
	}

	private static void requireCoverage(Set<String> terrain, int level, int x, int y,
		String provenance) throws WorldBuilderContractException {
		String key = level + ":" + Math.floorDiv(x, 48) + ":" + Math.floorDiv(y, 48);
		if (!terrain.contains(key)) {
			throw blocked(provenance.substring(0, provenance.indexOf("#record=")),
				"Placement " + provenance + " is outside converted terrain coverage.");
		}
	}

	private static void requireCoverageRectangle(Set<String> terrain, int level,
		WorldBuilderPackedCoordinateCodec.Coordinate minimum,
		WorldBuilderPackedCoordinateCodec.Coordinate maximum, String provenance)
		throws WorldBuilderContractException {
		for (long x = Math.floorDiv(minimum.x, 48); x <= Math.floorDiv(maximum.x, 48); x++) {
			for (long y = Math.floorDiv(minimum.y, 48); y <= Math.floorDiv(maximum.y, 48); y++) {
				if (!terrain.contains(level + ":" + x + ":" + y)) {
					throw blocked(provenance.substring(0, provenance.indexOf("#record=")),
						"NPC roam bounds for " + provenance
							+ " extend outside converted terrain coverage.");
				}
			}
		}
	}

	private static Map<String,Object> placementPayload(int level, List<Placement> placements) {
		Map<String,Object> payload = new LinkedHashMap<String,Object>();
		payload.put("schemaVersion", Long.valueOf(3L));
		payload.put("encoding", "layered-world-placements-v3");
		payload.put("worldSpace", WORLD_SPACE);
		payload.put("level", Long.valueOf(level));
		for (String family : Arrays.asList("boundary", "ground-item", "npc", "scenery")) {
			List<Object> records = new ArrayList<Object>();
			for (Placement placement : placements) {
				if (family.equals(placement.family)) records.add(placement.toJson());
			}
			String key = "boundary".equals(family) ? "boundaries"
				: "ground-item".equals(family) ? "groundItems"
					: "npc".equals(family) ? "npcs" : "scenery";
			payload.put(key, records);
		}
		return payload;
	}

	private static String terrainPath(WorldBuilderPackedCoordinateCodec.Sector sector) {
		return "terrain/global/l" + WorldBuilderLayeredPackage.signedToken(sector.level)
			+ "/x" + WorldBuilderLayeredPackage.signedToken(sector.sectorX)
			+ "-y" + WorldBuilderLayeredPackage.signedToken(sector.sectorY) + ".raw";
	}

	private static String placementPath(int level) {
		return "placements/global/l" + WorldBuilderLayeredPackage.signedToken(level) + ".json";
	}

	private static void requireContained(Path root, Path child, String relative)
		throws WorldBuilderContractException {
		WorldBuilderPortablePath.require(relative, "convert-packed");
		if (!child.startsWith(root)) throw blocked(relative, "Generated package path escaped staging.");
	}

	private static Map<String,Object> object(Object raw, String path, int index)
		throws WorldBuilderContractException {
		if (!(raw instanceof Map)) throw recordError(path, index,
			"Packed placement record is not an object.");
		@SuppressWarnings("unchecked") Map<String,Object> result = (Map<String,Object>)raw;
		return result;
	}

	private static void exact(Map<String,Object> value, String path, int index,
		String... keys) throws WorldBuilderContractException {
		Set<String> expected = new HashSet<String>(Arrays.asList(keys));
		if (value.size() != expected.size() || !value.keySet().equals(expected)) {
			throw recordError(path, index,
				"Packed placement record contains missing or unexpected fields.");
		}
	}

	private static int nonnegative(Map<String,Object> value, String key,
		String path, int index) throws WorldBuilderContractException {
		int result = signed(value, key, path, index);
		if (result < 0) throw recordError(path, index,
			"Packed placement value is negative: " + key + ".");
		return result;
	}

	private static int signed(Map<String,Object> value, String key,
		String path, int index) throws WorldBuilderContractException {
		Object raw = value.get(key);
		if (!(raw instanceof Long) || ((Long)raw).longValue() < Integer.MIN_VALUE
			|| ((Long)raw).longValue() > Integer.MAX_VALUE) {
			throw recordError(path, index,
				"Packed placement field is not a signed 32-bit integer: " + key + ".");
		}
		return ((Long)raw).intValue();
	}

	private static WorldBuilderContractException recordError(
		String path, int index, String message) {
		return new WorldBuilderContractException(WorldBuilderErrorCodes.CONVERSION_BLOCKED,
			"convert-packed", "", WorldBuilderPackedLayoutAdapter.ID, path,
			path + "#record=" + index, "One exactly representable packed placement record.",
			message, false, message,
			"Correct the named source record and run discovery/conversion again.", null);
	}

	private static WorldBuilderContractException definition(String path, String entry,
		int tile, String family, int id) {
		String message = "Terrain " + entry + " tile " + tile
			+ " references undefined " + family + " ID " + id + ".";
		return new WorldBuilderContractException(WorldBuilderErrorCodes.DEFINITION_MISMATCH,
			"convert-packed", path, false, message,
			"Install the exact matching definition catalog or correct the packed sector.");
	}

	private static WorldBuilderContractException blocked(String path, String message) {
		return new WorldBuilderContractException(WorldBuilderErrorCodes.CONVERSION_BLOCKED,
			"convert-packed", path, false, message,
			"Correct the exact immutable packed evidence; conversion has no force or approximation mode.");
	}

	private static WorldBuilderContractException wrap(WorldBuilderContractException failure,
		String path, String provenance) {
		return new WorldBuilderContractException(failure.code(), "convert-packed", "",
			WorldBuilderPackedLayoutAdapter.ID, path, provenance,
			"One exact adapter-owned packed coordinate or terrain identity.",
			failure.getMessage(), false, failure.getMessage(), failure.nextStep(), failure);
	}

	static final class TerrainSector implements Comparable<TerrainSector> {
		final WorldBuilderPackedCoordinateCodec.Sector coordinate;
		final byte[] legacyBytes;
		final byte[] layeredBytes;
		final String sourceRole;
		final String sourcePath;
		final String sourceEntry;

		TerrainSector(WorldBuilderPackedCoordinateCodec.Sector coordinate,
			byte[] legacyBytes, byte[] layeredBytes, String sourceRole,
			String sourcePath, String sourceEntry) {
			this.coordinate = coordinate;
			this.legacyBytes = legacyBytes.clone();
			this.layeredBytes = layeredBytes.clone();
			this.sourceRole = sourceRole;
			this.sourcePath = sourcePath;
			this.sourceEntry = sourceEntry;
		}

		@Override
		public int compareTo(TerrainSector other) {
			return coordinate.compareTo(other.coordinate);
		}
	}

	static final class Placement implements Comparable<Placement> {
		final String family;
		final String sourceRole;
		final String sourcePath;
		final int recordIndex;
		final String provenance;
		final int definitionId;
		final int level;
		final int x;
		final int y;
		final int direction;
		final int amount;
		final int respawn;
		final WorldBuilderPackedCoordinateCodec.Coordinate minimum;
		final WorldBuilderPackedCoordinateCodec.Coordinate maximum;
		final String placementId;
		final String slot;

		Placement(String family,
			WorldBuilderAdaptiveConfiguration.PlacementSource source,
			int recordIndex, int definitionId, int level, int x, int y,
			int direction, int amount, int respawn,
			WorldBuilderPackedCoordinateCodec.Coordinate minimum,
			WorldBuilderPackedCoordinateCodec.Coordinate maximum,
			String placementId) {
			this.family = family;
			this.sourceRole = "placement." + source.role;
			this.sourcePath = source.relativePath;
			this.recordIndex = recordIndex;
			this.provenance = source.relativePath + "#record=" + recordIndex;
			this.definitionId = definitionId;
			this.level = level;
			this.x = x;
			this.y = y;
			this.direction = direction;
			this.amount = amount;
			this.respawn = respawn;
			this.minimum = minimum;
			this.maximum = maximum;
			this.placementId = placementId;
			this.slot = slot();
		}

		Placement withId(String id) {
			WorldBuilderAdaptiveConfiguration.PlacementSource source =
				new WorldBuilderAdaptiveConfiguration.PlacementSource(
					sourceRole.substring("placement.".length()), family, "base", 0,
					"placeholder", sourcePath);
			return new Placement(family, source, recordIndex, definitionId, level,
				x, y, direction, amount, respawn, minimum, maximum, id);
		}

		String semantic() {
			if ("boundary".equals(family)) {
				return WorldBuilderPlacementSemantics.boundary(
					level, definitionId, x, y, direction);
			}
			if ("ground-item".equals(family)) {
				return WorldBuilderPlacementSemantics.groundItem(
					level, definitionId, x, y, amount, respawn);
			}
			if ("npc".equals(family)) {
				return WorldBuilderPlacementSemantics.npc(level, definitionId, x, y,
					minimum.x, minimum.y, maximum.x, maximum.y);
			}
			return WorldBuilderPlacementSemantics.scenery(
				level, definitionId, x, y, direction);
		}

		boolean removesExactly(Placement existing) {
			if (!family.equals(existing.family) || !slot.equals(existing.slot)) return false;
			if ("ground-item".equals(family)) return definitionId == existing.definitionId;
			if ("npc".equals(family)) {
				return definitionId == existing.definitionId
					&& minimum.x == existing.minimum.x && minimum.y == existing.minimum.y
					&& maximum.x == existing.maximum.x && maximum.y == existing.maximum.y;
			}
			return true;
		}

		Map<String,Object> toJson() {
			Map<String,Object> value = new LinkedHashMap<String,Object>();
			if ("boundary".equals(family)) {
				value.put("boundaryId", Long.valueOf(definitionId));
				value.put("direction", Long.valueOf(direction));
				value.put("placementId", placementId);
				value.put("position", pointJson(x, y));
			} else if ("ground-item".equals(family)) {
				value.put("amount", Long.valueOf(amount));
				value.put("itemId", Long.valueOf(definitionId));
				value.put("placementId", placementId);
				value.put("position", pointJson(x, y));
				value.put("respawnSeconds", Long.valueOf(respawn));
			} else if ("npc".equals(family)) {
				value.put("npcId", Long.valueOf(definitionId));
				value.put("placementId", placementId);
				Map<String,Object> bounds = new LinkedHashMap<String,Object>();
				bounds.put("minimum", pointJson(minimum.x, minimum.y));
				bounds.put("maximum", pointJson(maximum.x, maximum.y));
				value.put("roamBounds", bounds);
				value.put("start", pointJson(x, y));
			} else {
				value.put("direction", Long.valueOf(direction));
				value.put("placementId", placementId);
				value.put("position", pointJson(x, y));
				value.put("sceneryId", Long.valueOf(definitionId));
			}
			return value;
		}

		private String slot() {
			String base = family + "\u0000" + level + "\u0000" + x + "\u0000" + y;
			if ("boundary".equals(family)) return base + "\u0000" + direction;
			if ("npc".equals(family)) return base + "\u0000" + definitionId;
			return base;
		}

		@Override
		public int compareTo(Placement other) {
			int result = family.compareTo(other.family);
			if (result == 0) result = Integer.compare(level, other.level);
			if (result == 0) result = Integer.compare(x, other.x);
			if (result == 0) result = Integer.compare(y, other.y);
			if (result == 0 && "boundary".equals(family)) {
				result = Integer.compare(direction, other.direction);
			}
			if (result == 0) result = placementId.compareTo(other.placementId);
			return result;
		}
	}

	private static Map<String,Object> pointJson(int x, int y) {
		Map<String,Object> point = new LinkedHashMap<String,Object>();
		point.put("x", Long.valueOf(x));
		point.put("y", Long.valueOf(y));
		return point;
	}

	private static final class SummaryKey implements Comparable<SummaryKey> {
		final String family;
		final int level;
		final String sourceRole;
		final int definitionId;

		SummaryKey(String family, int level, String sourceRole, int definitionId) {
			this.family = family;
			this.level = level;
			this.sourceRole = sourceRole;
			this.definitionId = definitionId;
		}

		Map<String,Object> toJson(long count) {
			Map<String,Object> value = new LinkedHashMap<String,Object>();
			value.put("family", family);
			value.put("level", Long.valueOf(level));
			value.put("sourceRole", sourceRole);
			value.put("definitionId", Long.valueOf(definitionId));
			value.put("count", Long.valueOf(count));
			return value;
		}

		@Override
		public int compareTo(SummaryKey other) {
			int result = family.compareTo(other.family);
			if (result == 0) result = Integer.compare(level, other.level);
			if (result == 0) result = sourceRole.compareTo(other.sourceRole);
			if (result == 0) result = Integer.compare(definitionId, other.definitionId);
			return result;
		}
	}

	private static final class Decision implements Comparable<Decision> {
		final String kind;
		final String sourceRole;
		final String provenance;
		final String placementId;
		final String outcome;

		Decision(String kind, String sourceRole, String provenance,
			String placementId, String outcome) {
			this.kind = kind;
			this.sourceRole = sourceRole;
			this.provenance = provenance;
			this.placementId = placementId;
			this.outcome = outcome;
		}

		Map<String,Object> toJson() {
			Map<String,Object> value = new LinkedHashMap<String,Object>();
			value.put("kind", kind);
			value.put("sourceRole", sourceRole);
			value.put("provenance", provenance);
			value.put("placementId", placementId);
			value.put("outcome", outcome);
			return value;
		}

		@Override
		public int compareTo(Decision other) {
			int result = kind.compareTo(other.kind);
			if (result == 0) result = sourceRole.compareTo(other.sourceRole);
			if (result == 0) result = provenance.compareTo(other.provenance);
			if (result == 0) result = placementId.compareTo(other.placementId);
			if (result == 0) result = outcome.compareTo(other.outcome);
			return result;
		}
	}
}
