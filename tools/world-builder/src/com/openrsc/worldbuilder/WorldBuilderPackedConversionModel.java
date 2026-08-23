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
	static final int OUTPUT_ENCODING_VERSION = 2;
	private static final String WORLD_SPACE = "global";
	private static final int MAX_SECTORS = 65536;
	static final int DEFAULT_CUMULATIVE_RECORD_LIMIT = 65536;
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
	final List<String> placementIdentities;
	final List<Object> placementSummaries;
	final List<Object> decisions;
	final int reverseMatched;

	private WorldBuilderPackedConversionModel(
		List<TerrainSector> terrain,
		List<Placement> placements,
		List<Integer> levels,
		List<String> placementSemantics,
		List<String> placementIdentities,
		List<Object> placementSummaries,
		List<Object> decisions) {
		this.terrain = Collections.unmodifiableList(new ArrayList<TerrainSector>(terrain));
		this.placements = Collections.unmodifiableList(new ArrayList<Placement>(placements));
		this.levels = Collections.unmodifiableList(new ArrayList<Integer>(levels));
		this.placementSemantics = Collections.unmodifiableList(
			new ArrayList<String>(placementSemantics));
		this.placementIdentities = Collections.unmodifiableList(
			new ArrayList<String>(placementIdentities));
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
		return read(source, configuration, definitions, HASHED_IDS,
			DEFAULT_CUMULATIVE_RECORD_LIMIT);
	}

	static WorldBuilderPackedConversionModel read(
		WorldBuilderPackedConversionSource source,
		WorldBuilderAdaptiveConfiguration configuration,
		WorldBuilderCompatibilityEvidence.DefinitionCatalog definitions,
		PlacementIdFactory idFactory) throws WorldBuilderContractException {
		return read(source, configuration, definitions, idFactory,
			DEFAULT_CUMULATIVE_RECORD_LIMIT);
	}

	static WorldBuilderPackedConversionModel read(
		WorldBuilderPackedConversionSource source,
		WorldBuilderAdaptiveConfiguration configuration,
		WorldBuilderCompatibilityEvidence.DefinitionCatalog definitions,
		PlacementIdFactory idFactory,
		int cumulativeRecordLimit) throws WorldBuilderContractException {
		if (idFactory == null) idFactory = HASHED_IDS;
		if (cumulativeRecordLimit < 1
			|| cumulativeRecordLimit > DEFAULT_CUMULATIVE_RECORD_LIMIT) {
			throw new IllegalArgumentException("invalid cumulative conversion record limit");
		}
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
		List<Placement> embeddedScenery = embeddedSceneryPlacements(
			source, terrain, definitions, idFactory, generatedIds,
			cumulativeRecordLimit);
		Map<String,Placement> sceneryBase = effective.get("scenery");
		for (Placement placement : embeddedScenery) {
			if (sceneryBase.put(placement.slot, placement) != null) {
				throw placementProblem(placement,
					"Embedded packed scenery repeats an effective anchor slot.");
			}
		}
		int inputRecordCount = embeddedMarkerCount(terrain);
		int effectiveRecordCount = embeddedScenery.size();
		for (WorldBuilderAdaptiveConfiguration.PlacementSource placementSource
			: configuration.placements) {
			declaredFamilies.add(placementSource.family);
			String inputRole = "placement." + placementSource.role;
			source.requireInput(inputRole, placementSource.relativePath);
			requireEncoding(placementSource);
			List<Placement> records = parsePlacementSource(source, placementSource,
				definitions, idFactory, generatedIds,
				cumulativeRecordLimit - inputRecordCount);
			inputRecordCount += records.size();
			Map<String,Placement> family = effective.get(placementSource.family);
			Set<String> sourceSlots = new HashSet<String>();
			for (Placement placement : records) {
				if (!sourceSlots.add(placement.slot)) {
					throw placementProblem(placement,
						"Packed placement source repeats effective slot at record "
							+ placement.recordIndex + ".");
				}
				if ("removal".equals(placementSource.kind)) {
					Placement removed = family.get(placement.slot);
					if (removed == null || !placement.removesExactly(removed)) {
						throw placementProblem(placement,
							"Packed removal at record " + placement.recordIndex
								+ " does not exactly match an earlier effective placement.");
					}
					family.remove(placement.slot);
					effectiveRecordCount--;
					addDecision(decisions, new Decision("removal", inputRole,
						placement.provenance + " removes " + removed.provenance,
						removed.placementId, "removed"), cumulativeRecordLimit,
						placement.sourcePath);
				} else if ("base".equals(placementSource.kind)) {
					if (family.containsKey(placement.slot)) {
						throw placementProblem(placement,
							"Packed base placement collides at record "
								+ placement.recordIndex + ".");
					}
					requireEffectiveCapacity(effectiveRecordCount,
						cumulativeRecordLimit, placement.sourcePath);
					family.put(placement.slot, placement);
					effectiveRecordCount++;
				} else if ("overlay".equals(placementSource.kind)) {
					Placement replaced = family.get(placement.slot);
					if (replaced == null) {
						requireEffectiveCapacity(effectiveRecordCount,
							cumulativeRecordLimit, placement.sourcePath);
						effectiveRecordCount++;
					}
					family.put(placement.slot, placement);
					if (replaced != null) {
						addDecision(decisions, new Decision("replacement", inputRole,
							placement.provenance + " replaces " + replaced.provenance,
							replaced.placementId, "replaced"), cumulativeRecordLimit,
							placement.sourcePath);
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
		List<String> identities = new ArrayList<String>(placements.size());
		Map<SummaryKey,Long> summaries = new TreeMap<SummaryKey,Long>();
		Map<Integer,Integer> perLevel = new HashMap<Integer,Integer>();
		for (Placement placement : placements) {
			requireCoverage(terrainCoverage, placement);
			if (placement.minimum != null) {
				requireCoverageRectangle(terrainCoverage, placement);
			}
			String semantic = placement.semantic();
			semantics.add(semantic);
			identities.add(WorldBuilderPlacementSemantics.identity(
				placement.placementId, semantic));
			SummaryKey summary = new SummaryKey(placement.family, placement.level,
				placement.sourceRole, placement.definitionId);
			Long count = summaries.get(summary);
			summaries.put(summary, Long.valueOf(count == null ? 1L : count.longValue() + 1L));
			Integer levelCount = perLevel.get(Integer.valueOf(placement.level));
			int next = levelCount == null ? 1 : levelCount.intValue() + 1;
			if (next > DEFAULT_CUMULATIVE_RECORD_LIMIT) {
				throw blocked(placement.sourcePath,
					"Converted placement set exceeds 65,536 records on level "
						+ placement.level + ".");
			}
			perLevel.put(Integer.valueOf(placement.level), Integer.valueOf(next));
			addDecision(decisions, new Decision("precedence", placement.sourceRole,
				placement.provenance, placement.placementId, "retained"),
				cumulativeRecordLimit, placement.sourcePath);
		}
		Collections.sort(semantics);
		Collections.sort(identities);
		Collections.sort(decisions);
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
			new ArrayList<Integer>(levelSet), semantics, identities, summaryDocuments,
			decisionDocuments);
	}

	PackageExpectation writePackage(Path packageRoot, String sourceFingerprintSha256)
		throws IOException, WorldBuilderContractException {
		Files.createDirectories(packageRoot);
		Map<String,WorldBuilderReadOnlyTarget.FileState> expectedFiles =
			new TreeMap<String,WorldBuilderReadOnlyTarget.FileState>();
		List<Object> terrainDeclarations = new ArrayList<Object>(terrain.size());
		for (TerrainSector sector : terrain) {
			String relative = terrainPath(sector.coordinate);
			Path payload = packageRoot.resolve(relative).normalize();
			requireContained(packageRoot, payload, relative);
			Files.createDirectories(payload.getParent());
			writeExpected(payload, relative, sector.layeredBytes, expectedFiles);
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
			writeExpected(path, relative, WorldBuilderJsonDocuments.pretty(payload)
				.getBytes(StandardCharsets.UTF_8), expectedFiles);
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
		byte[] manifestBytes = WorldBuilderJsonDocuments.pretty(manifest)
			.getBytes(StandardCharsets.UTF_8);
		writeExpected(packageRoot.resolve("manifest.json"), "manifest.json",
			manifestBytes, expectedFiles);
		return new PackageExpectation(expectedFiles,
			WorldBuilderJsonDocuments.canonical(manifest));
	}

	private static int embeddedMarkerCount(List<TerrainSector> terrain)
		throws WorldBuilderContractException {
		long count = 0L;
		for (TerrainSector sector : terrain) count += sector.embeddedScenery.size();
		if (count > DEFAULT_CUMULATIVE_RECORD_LIMIT) {
			throw blocked("server-terrain",
				"Embedded packed scenery exceeds the 65,536-record conversion bound.");
		}
		return (int)count;
	}

	private static List<Placement> embeddedSceneryPlacements(
		WorldBuilderPackedConversionSource source,
		List<TerrainSector> terrain,
		WorldBuilderCompatibilityEvidence.DefinitionCatalog definitions,
		PlacementIdFactory idFactory,
		Set<String> generatedIds,
		int cumulativeRecordLimit) throws WorldBuilderContractException {
		int markerCount = embeddedMarkerCount(terrain);
		if (markerCount == 0) return Collections.emptyList();
		if (markerCount > cumulativeRecordLimit) {
			throw blocked("server-terrain",
				"Packed terrain and placement inputs exceed the bounded conversion record limit.");
		}
		WorldBuilderPackedSceneryDefinitions footprints =
			WorldBuilderPackedSceneryDefinitions.read(source);
		List<EmbeddedSceneryMarker> markers =
			new ArrayList<EmbeddedSceneryMarker>(markerCount);
		for (TerrainSector sector : terrain) markers.addAll(sector.embeddedScenery);
		Collections.sort(markers);
		Map<String,EmbeddedSceneryMarker> byTile =
			new HashMap<String,EmbeddedSceneryMarker>();
		for (EmbeddedSceneryMarker marker : markers) {
			if (byTile.put(marker.coordinateKey(), marker) != null) {
				throw blocked(marker.sourcePath,
					"Embedded packed scenery repeats one global terrain tile.");
			}
		}

		Set<String> consumed = new HashSet<String>();
		List<Placement> result = new ArrayList<Placement>();
		long footprintWork = 0L;
		for (EmbeddedSceneryMarker marker : markers) {
			if (consumed.contains(marker.coordinateKey())) continue;
			requireDefinition(definitions, "scenery", marker.definitionId,
				marker.sourcePath, marker.tileIndex);
			WorldBuilderPackedSceneryDefinitions.Footprint footprint =
				footprints.require(marker.definitionId);
			long work = footprint.width > 0 && footprint.height > 0
				? (long)footprint.width * (long)footprint.height : 1L;
			footprintWork += work;
			if (footprintWork > 1000000L) {
				throw blocked(marker.sourcePath,
					"Embedded packed scenery footprint work exceeds 1,000,000 tiles.");
			}

			Placement placement = new Placement("scenery", "server-terrain",
				marker.sourcePath, marker.tileIndex, marker.provenance(),
				marker.definitionId, marker.level, marker.x, marker.y,
				0, 0, 0, null, null, "");
			String facts = WorldBuilderPackedLayoutAdapter.ID + "\u0000"
				+ source.sourceFingerprintSha256 + "\u0000server-terrain\u0000"
				+ placement.semantic() + "\u0000" + marker.provenance();
			String placementId = idFactory.create(facts);
			if (placementId == null
				|| !placementId.matches("[A-Za-z0-9][A-Za-z0-9._:+-]{0,127}")) {
				throw placementProblem(placement,
					"Deterministic embedded-scenery ID is not a valid package identifier.");
			}
			if (!generatedIds.add(placementId)) {
				throw placementProblem(placement,
					"Deterministic embedded-scenery placement ID collision.");
			}
			result.add(placement.withId(placementId));
			consumed.add(marker.coordinateKey());
			if (footprint.width <= 0 || footprint.height <= 0) continue;
			for (int offsetX = 0; offsetX < footprint.width; offsetX++) {
				for (int offsetY = 0; offsetY < footprint.height; offsetY++) {
					String key = EmbeddedSceneryMarker.coordinateKey(marker.level,
						(long)marker.x + offsetX, (long)marker.y + offsetY);
					EmbeddedSceneryMarker candidate = byTile.get(key);
					if (candidate != null
						&& candidate.definitionId == marker.definitionId) consumed.add(key);
				}
			}
		}
		return result;
	}

	private static List<EmbeddedSceneryMarker> extractEmbeddedScenery(
		byte[] layered, WorldBuilderPackedCoordinateCodec.Sector coordinate,
		String sourcePath, String sourceEntry) throws WorldBuilderContractException {
		List<EmbeddedSceneryMarker> result = new ArrayList<EmbeddedSceneryMarker>();
		for (int offset = 0, tile = 0; offset < layered.length;
			offset += WorldBuilderRawLayeredTerrainCodec.V2_TILE_BYTES, tile++) {
			int diagonal = ByteBuffer.wrap(layered, offset + 7, 4).getInt();
			if (diagonal <= 48000 || diagonal >= 60000) continue;
			int tileX = tile / WorldBuilderPackedTerrainCodec.SECTOR_SIZE;
			int tileY = tile % WorldBuilderPackedTerrainCodec.SECTOR_SIZE;
			int worldX;
			int worldY;
			try {
				worldX = Math.addExact(Math.multiplyExact(coordinate.sectorX,
					WorldBuilderPackedTerrainCodec.SECTOR_SIZE), tileX);
				worldY = Math.addExact(Math.multiplyExact(coordinate.sectorY,
					WorldBuilderPackedTerrainCodec.SECTOR_SIZE), tileY);
			} catch (ArithmeticException overflow) {
				throw blocked(sourcePath,
					"Embedded packed scenery coordinates overflow signed world space in "
						+ sourceEntry + " at tile " + tile + ".");
			}
			result.add(new EmbeddedSceneryMarker(coordinate.level, worldX, worldY,
				diagonal - 48001, diagonal, tile, sourcePath, sourceEntry));
			for (int index = offset + 7; index < offset + 11; index++) layered[index] = 0;
		}
		return Collections.unmodifiableList(result);
	}

	private static byte[] restoreEmbeddedScenery(
		byte[] layered, List<EmbeddedSceneryMarker> markers) {
		byte[] result = layered.clone();
		for (EmbeddedSceneryMarker marker : markers) {
			int offset = marker.tileIndex
				* WorldBuilderRawLayeredTerrainCodec.V2_TILE_BYTES + 7;
			ByteBuffer.wrap(result, offset, 4).putInt(marker.rawEncoding);
		}
		return result;
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
				List<EmbeddedSceneryMarker> embedded = extractEmbeddedScenery(
					layered, coordinate, relative, entry.getName());
				byte[] restored = restoreEmbeddedScenery(layered, embedded);
				WorldBuilderPackedTerrainCodec.requireExactReverse(legacy, restored);
				WorldBuilderRawLayeredTerrainCodec.requireDecodable(layered);
				validateTerrainDefinitions(layered, definitions, relative, entry.getName());
				result.add(new TerrainSector(coordinate, legacy, layered,
					"server-terrain", relative, entry.getName(), embedded));
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
			offset += WorldBuilderRawLayeredTerrainCodec.V2_TILE_BYTES, tile++) {
			int overlay = layered[offset + 3] & 0xff;
			int effectiveOverlay = overlay == 250 ? 2 : overlay;
			if (effectiveOverlay > 0
				&& !definitions.tiles.contains(Integer.valueOf(effectiveOverlay - 1))) {
				throw definition(path, entry, tile, "tile", effectiveOverlay - 1);
			}
			int vertical = layered[offset + 5] & 0xff;
			int horizontal = layered[offset + 6] & 0xff;
			if (vertical > 0
				&& !definitions.boundaries.contains(Integer.valueOf(vertical - 1))) {
				throw definition(path, entry, tile, "boundary", vertical - 1);
			}
			if (horizontal > 0
				&& !definitions.boundaries.contains(Integer.valueOf(horizontal - 1))) {
				throw definition(path, entry, tile, "boundary", horizontal - 1);
			}
			int diagonal = ByteBuffer.wrap(layered, offset + 7, 4).getInt();
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
		Set<String> generatedIds,
		int remainingRecordCapacity) throws WorldBuilderContractException {
		Map<String,Object> root = conversionSource.target.readObject(source.relativePath);
		String rootKey = rootKey(source.family, source.kind);
		if (root.size() != 1 || !root.containsKey(rootKey)) {
			throw blocked(source.relativePath,
				"Packed placement document must contain only array " + rootKey + ".");
		}
		Object raw = root.get(rootKey);
		if (!(raw instanceof List)
			|| ((List<?>)raw).size() > DEFAULT_CUMULATIVE_RECORD_LIMIT) {
			throw blocked(source.relativePath,
				"Packed placement array is absent or exceeds 65,536 records.");
		}
		List<?> records = (List<?>)raw;
		if (remainingRecordCapacity < 0 || records.size() > remainingRecordCapacity) {
			throw blocked(source.relativePath,
				"Cumulative packed placement inputs exceed the bounded conversion record limit.");
		}
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
					throw placementProblem(placement,
						"Deterministic placement ID is not a valid package identifier at record "
							+ index + ".");
				}
				if (!generatedIds.add(placementId)) {
					throw placementProblem(placement,
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
		if (!removal) requireDefinition(
			definitions, "boundary", id, source.relativePath, index);
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
		if (!removal) requireDefinition(
			definitions, "ground-item", id, source.relativePath, index);
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
		if (!"removal".equals(source.kind)) {
			requireDefinition(definitions, "npc", id, source.relativePath, index);
		}
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
		if (!removal) requireDefinition(
			definitions, "scenery", id, source.relativePath, index);
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

	private static void requireEffectiveCapacity(
		int effectiveRecordCount, int limit, String path)
		throws WorldBuilderContractException {
		if (effectiveRecordCount >= limit) {
			throw blocked(path,
				"Cumulative effective placements exceed the bounded conversion record limit.");
		}
	}

	private static void addDecision(List<Decision> decisions, Decision decision,
		int limit, String path) throws WorldBuilderContractException {
		if (decisions.size() >= limit
			|| decisions.size() >= WorldBuilderContractLimits.MAX_PLACEMENT_SUMMARIES) {
			throw blocked(path,
				"Cumulative conversion decisions exceed the bounded report record limit.");
		}
		decisions.add(decision);
	}

	private static String rootKey(String family, String kind) {
		boolean removal = "removal".equals(kind);
		if ("boundary".equals(family)) return removal ? "boundary_removals" : "boundaries";
		if ("ground-item".equals(family)) return removal ? "ground_item_removals" : "ground_items";
		if ("npc".equals(family)) return removal ? "npc_removals" : "npclocs";
		return removal ? "scenery_removals" : "sceneries";
	}

	private static void requireCoverage(Set<String> terrain, Placement placement)
		throws WorldBuilderContractException {
		String key = placement.level + ":" + Math.floorDiv(placement.x, 48)
			+ ":" + Math.floorDiv(placement.y, 48);
		if (!terrain.contains(key)) {
			throw placementProblem(placement,
				"Placement " + placement.provenance
					+ " is outside converted terrain coverage.");
		}
	}

	private static void requireCoverageRectangle(Set<String> terrain, Placement placement)
		throws WorldBuilderContractException {
		for (long x = Math.floorDiv(placement.minimum.x, 48);
			x <= Math.floorDiv(placement.maximum.x, 48); x++) {
			for (long y = Math.floorDiv(placement.minimum.y, 48);
				y <= Math.floorDiv(placement.maximum.y, 48); y++) {
				if (!terrain.contains(placement.level + ":" + x + ":" + y)) {
					throw placementProblem(placement,
						"NPC roam bounds for " + placement.provenance
							+ " extend outside converted terrain coverage.");
				}
			}
		}
	}

	private static void requireDefinition(
		WorldBuilderCompatibilityEvidence.DefinitionCatalog definitions,
		String family, int id, String path, int index)
		throws WorldBuilderContractException {
		try {
			definitions.require(family, id, path);
		} catch (WorldBuilderContractException refusal) {
			throw new WorldBuilderContractException(refusal.code(), "convert-packed", "",
				WorldBuilderPackedLayoutAdapter.ID, path, path + "#record=" + index,
				refusal.expected(), refusal.observed(), false, refusal.getMessage(),
				refusal.nextStep(), refusal);
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

	private static void writeExpected(Path path, String relative, byte[] bytes,
		Map<String,WorldBuilderReadOnlyTarget.FileState> expectedFiles)
		throws IOException, WorldBuilderContractException {
		Files.write(path, bytes);
		WorldBuilderReadOnlyTarget.FileState previous = expectedFiles.put(relative,
			new WorldBuilderReadOnlyTarget.FileState("converted-package", relative, true,
				bytes.length, WorldBuilderHashes.sha256(bytes)));
		if (previous != null) {
			throw blocked(relative, "Generated package path was written more than once.");
		}
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

	private static WorldBuilderContractException placementProblem(
		Placement placement, String message) {
		return new WorldBuilderContractException(WorldBuilderErrorCodes.CONVERSION_BLOCKED,
			"convert-packed", "", WorldBuilderPackedLayoutAdapter.ID,
			placement.sourcePath, placement.provenance,
			"One unambiguous, exactly representable packed composition record.",
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

	void requireExactPackage(WorldBuilderReadOnlyTarget stageTarget,
		String packageRelative, WorldBuilderGenericLayeredPackage validated,
		PackageExpectation expected) throws IOException, WorldBuilderContractException {
		if (!expected.fingerprintSha256.equals(validated.fingerprintSha256)) {
			throw blocked(packageRelative,
				"Staged package fingerprint differs from the exact package produced by the model.");
		}
		if (!placementSemantics.equals(validated.placementSemantics)
			|| !placementIdentities.equals(validated.placementIdentities)) {
			throw blocked(packageRelative,
				"Staged placement semantics or deterministic placement IDs differ from the model.");
		}
		Map<String,WorldBuilderReadOnlyTarget.FileState> actual =
			new TreeMap<String,WorldBuilderReadOnlyTarget.FileState>();
		String prefix = packageRelative + "/";
		for (WorldBuilderReadOnlyTarget.FileState state : validated.files) {
			if (!state.relativePath.startsWith(prefix)) {
				throw blocked(packageRelative,
					"Staged package inventory escaped its expected package root.");
			}
			String relative = state.relativePath.substring(prefix.length());
			if (actual.put(relative, state) != null) {
				throw blocked(packageRelative,
					"Staged package inventory contains a duplicate relative path.");
			}
		}
		if (!actual.keySet().equals(expected.files.keySet())) {
			throw blocked(packageRelative,
				"Staged package inventory differs from the complete model inventory.");
		}
		for (Map.Entry<String,WorldBuilderReadOnlyTarget.FileState> entry
			: expected.files.entrySet()) {
			WorldBuilderReadOnlyTarget.FileState state = actual.get(entry.getKey());
			WorldBuilderReadOnlyTarget.FileState wanted = entry.getValue();
			if (state.size != wanted.size || !state.sha256.equals(wanted.sha256)) {
				throw blocked(entry.getKey(),
					"Staged package bytes differ from the exact model inventory.");
			}
		}
		Map<String,Object> manifest = stageTarget.readObject(
			packageRelative + "/manifest.json");
		if (!expected.manifestCanonical.equals(WorldBuilderJsonDocuments.canonical(manifest))) {
			throw blocked(packageRelative + "/manifest.json",
				"Staged terrain paths, coordinates, encodings, or hashes differ from the model.");
		}
		for (TerrainSector sector : terrain) {
			String relative = terrainPath(sector.coordinate);
			Path actualPath = stageTarget.requiredFile(packageRelative + "/" + relative);
			byte[] actualBytes = Files.readAllBytes(actualPath);
			if (!Arrays.equals(sector.layeredBytes, actualBytes)) {
				throw blocked(relative,
					"Staged terrain byte sequence differs from the converted model.");
			}
			byte[] restored = restoreEmbeddedScenery(actualBytes,
				sector.embeddedScenery);
			byte[] reversed = WorldBuilderPackedTerrainCodec.toLegacy(restored,
				sector.coordinate.level, sector.coordinate.sectorX,
				sector.coordinate.sectorY);
			if (!Arrays.equals(sector.legacyBytes, reversed)) {
				throw blocked(relative,
					"Staged terrain does not reverse to its exact immutable source ZIP entry.");
			}
		}
	}

	static final class PackageExpectation {
		final Map<String,WorldBuilderReadOnlyTarget.FileState> files;
		final String manifestCanonical;
		final String fingerprintSha256;

		PackageExpectation(Map<String,WorldBuilderReadOnlyTarget.FileState> files,
			String manifestCanonical) {
			this.files = Collections.unmodifiableMap(
				new TreeMap<String,WorldBuilderReadOnlyTarget.FileState>(files));
			this.manifestCanonical = manifestCanonical;
			java.security.MessageDigest digest = WorldBuilderHashes.newDigest();
			for (WorldBuilderReadOnlyTarget.FileState file : this.files.values()) {
				WorldBuilderHashes.updateText(digest, file.relativePath);
				WorldBuilderHashes.updateText(digest, Long.toString(file.size));
				WorldBuilderHashes.updateText(digest, file.sha256);
			}
			this.fingerprintSha256 = WorldBuilderHashes.hex(digest.digest());
		}
	}

	static final class EmbeddedSceneryMarker
		implements Comparable<EmbeddedSceneryMarker> {
		final int level;
		final int x;
		final int y;
		final int definitionId;
		final int rawEncoding;
		final int tileIndex;
		final String sourcePath;
		final String sourceEntry;

		EmbeddedSceneryMarker(int level, int x, int y, int definitionId,
			int rawEncoding, int tileIndex, String sourcePath, String sourceEntry) {
			this.level = level;
			this.x = x;
			this.y = y;
			this.definitionId = definitionId;
			this.rawEncoding = rawEncoding;
			this.tileIndex = tileIndex;
			this.sourcePath = sourcePath;
			this.sourceEntry = sourceEntry;
		}

		String coordinateKey() {
			return coordinateKey(level, x, y);
		}

		static String coordinateKey(int level, long x, long y) {
			return level + ":" + x + ":" + y;
		}

		String provenance() {
			return sourcePath + "#entry=" + sourceEntry + "&tile=" + tileIndex;
		}

		@Override
		public int compareTo(EmbeddedSceneryMarker other) {
			int result = Integer.compare(level, other.level);
			if (result == 0) result = Integer.compare(x, other.x);
			if (result == 0) result = Integer.compare(y, other.y);
			if (result == 0) result = sourceEntry.compareTo(other.sourceEntry);
			if (result == 0) result = Integer.compare(tileIndex, other.tileIndex);
			return result;
		}
	}

	static final class TerrainSector implements Comparable<TerrainSector> {
		final WorldBuilderPackedCoordinateCodec.Sector coordinate;
		final byte[] legacyBytes;
		final byte[] layeredBytes;
		final String sourceRole;
		final String sourcePath;
		final String sourceEntry;
		final List<EmbeddedSceneryMarker> embeddedScenery;

		TerrainSector(WorldBuilderPackedCoordinateCodec.Sector coordinate,
			byte[] legacyBytes, byte[] layeredBytes, String sourceRole,
			String sourcePath, String sourceEntry,
			List<EmbeddedSceneryMarker> embeddedScenery) {
			this.coordinate = coordinate;
			this.legacyBytes = legacyBytes.clone();
			this.layeredBytes = layeredBytes.clone();
			this.sourceRole = sourceRole;
			this.sourcePath = sourcePath;
			this.sourceEntry = sourceEntry;
			this.embeddedScenery = Collections.unmodifiableList(
				new ArrayList<EmbeddedSceneryMarker>(embeddedScenery));
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
			this(family, "placement." + source.role, source.relativePath, recordIndex,
				source.relativePath + "#record=" + recordIndex, definitionId, level,
				x, y, direction, amount, respawn, minimum, maximum, placementId);
		}

		Placement(String family, String sourceRole, String sourcePath,
			int recordIndex, String provenance, int definitionId, int level,
			int x, int y, int direction, int amount, int respawn,
			WorldBuilderPackedCoordinateCodec.Coordinate minimum,
			WorldBuilderPackedCoordinateCodec.Coordinate maximum,
			String placementId) {
			this.family = family;
			this.sourceRole = sourceRole;
			this.sourcePath = sourcePath;
			this.recordIndex = recordIndex;
			this.provenance = provenance;
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
			return new Placement(family, sourceRole, sourcePath, recordIndex,
				provenance, definitionId, level, x, y, direction, amount, respawn,
				minimum, maximum, id);
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
