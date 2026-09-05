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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Pure, compiled reconciliation of the reviewed historical server and client maps.
 * This proves data derivation only: it grants neither executable-provider authority
 * nor installation readiness. Original inputs and decoder outputs are never edited.
 */
final class WorldBuilderPreservationMapReconciliation {
	static final String ID = "preservation-c0102e-fieldwise-map-v1";
	static final String DECODE_INVENTORY_SHA256 =
		"68776ead9a4487320840c1f88f054b46f342a5b115b05de93d9eace511ff6310";
	static final String CLIENT_MAP = "Client_Base/Cache/video/Authentic_Landscape.orsc";
	private static final String OP = "preservation-map-reconciliation";
	private static final String DEFS = "server/conf/server/defs/";
	private static final int SECTOR_BYTES = 23040;
	static final List<String> DEFINITIONS = Collections.unmodifiableList(Arrays.asList(
		"DoorDef.xml", "GameObjectDef.xml", "ItemDefs.json", "ItemDefsCustom.json",
		"NpcDefs.json", "NpcDefsCustom.json", "TileDef.xml"));
	static final List<String> PLACEMENTS = Collections.unmodifiableList(Arrays.asList(
		"BoundaryLocs.json", "SceneryLocs.json", "SceneryLocsDiscontinued.json",
		"NpcLocs.json", "NpcLocsDiscontinued.json", "GroundItems.json"));

	private WorldBuilderPreservationMapReconciliation() { }

	static Plan inspect(Path originalRoot, Path decodedRoot, Path evidencePath)
		throws IOException, WorldBuilderContractException {
		WorldBuilderReadOnlyTarget original = canonical(originalRoot);
		WorldBuilderReadOnlyTarget decoded = canonical(decodedRoot);
		WorldBuilderReadOnlyTarget evidenceRoot = canonical(evidencePath.toAbsolutePath().getParent());
		String evidenceName = evidencePath.getFileName().toString();
		WorldBuilderReadOnlyTarget.FileState evidenceState = evidenceRoot.requiredState("decoder-evidence", evidenceName);
		if (evidenceState.size > 2 * 1024 * 1024) throw blocked("Decoder evidence exceeds its bounded size.");
		Map<String,Object> evidence = evidenceRoot.readObject(evidenceName);
		if (!"preservation-jag-decode-evidence".equals(evidence.get("manifestType"))
			|| !"preservation-jag-decode-evidence-v1".equals(evidence.get("schemaId"))
			|| !"preservation-r64-jag-decode-v1".equals(evidence.get("decoderId"))
			|| !"decoded".equals(evidence.get("status"))) throw blocked("Unrecognized historical decoder evidence.");
		List<Object> probes = list(evidence.get("inventory"));
		if (probes.size() != 1680 || !DECODE_INVENTORY_SHA256.equals(hash(probes))
			|| !DECODE_INVENTORY_SHA256.equals(evidence.get("inventorySha256")))
			throw blocked("Decoder outcomes differ from the complete reviewed historical archive inventory.");
		WorldBuilderBoundedInventory.exactKeys(evidence, OP, "contractSha256", "decoderId", "inventory",
			"inventorySha256", "manifestType", "policy", "schemaId", "source", "status", "summary");
		if (!"1dd693a742c5a0a92669feac187015acdd15586073b8d62d544dde1b5dd24f1a".equals(evidence.get("contractSha256"))
			|| !"903700a5e2795df88b0221177c92d42cf07edbcd25e40e5b17c323dec5b0b5b8".equals(hash(evidence.get("policy")))
			|| !"3ba2dce209ffa7897275c3fecfcbe4142fc5cfd452c691eb003b463b3a9da0e9".equals(hash(evidence.get("source")))
			|| !"31728773c285bb0fa3941fe7e968313cf75a2d757cf19d0b72521f51192c31d6".equals(hash(evidence.get("summary"))))
			throw blocked("Decoder contract, policy, source closure, or summary is not the compiled reviewed evidence.");

		List<WorldBuilderReadOnlyTarget.FileState> sources = new ArrayList<WorldBuilderReadOnlyTarget.FileState>();
		for (String archive : Arrays.asList("land64.jag", "land64.mem", "maps64.jag", "maps64.mem"))
			sources.add(WorldBuilderPreservationSourceIntake.requireBaseline(original,
				"server/conf/server/data/maps/" + archive));
		sources.add(WorldBuilderPreservationSourceIntake.requireBaseline(original, CLIENT_MAP));
		sources.add(WorldBuilderPreservationSourceIntake.requireBaseline(original,
			"server/conf/server/data/Authentic_Landscape.orsc"));
		for (String definition : DEFINITIONS)
			sources.add(WorldBuilderPreservationSourceIntake.requireBaseline(original, DEFS + definition));
		for (String placement : PLACEMENTS)
			sources.add(WorldBuilderPreservationSourceIntake.requireBaseline(original, DEFS + "locs/" + placement));
		for (String config : Arrays.asList("server/connections.conf", "server/local.conf", "server/preservation.conf"))
			if (original.exists(config)) original.requiredState("configuration", config);
		Map<String,Object> settings = WorldBuilderCurrentRuntimeExecutionProfile.preservation()
			.typedConfiguration(original.root);
		if (!list(settings.get("configurationBlockers")).isEmpty()
			|| !list(settings.get("untranslatedKeys")).isEmpty())
			throw blocked("The effective source settings do not select the reviewed Preservation map composition.");
		for (Object raw : list(settings.get("sourceInventory"))) {
			Map<String,Object> row = object(raw);
			WorldBuilderReadOnlyTarget.FileState state = original.requiredState("configuration", string(row, "relativePath"));
			if (!state.sha256.equals(row.get("sha256")) || state.size != number(row, "size"))
				throw blocked("Effective configuration changed during reconciliation.");
			sources.add(state);
		}

		Map<String,byte[]> client = readClientArchive(original.requiredFile(CLIENT_MAP));
		Map<String,byte[]> fused = new TreeMap<String,byte[]>();
		List<Object> sectors = new ArrayList<Object>();
		List<WorldBuilderReadOnlyTarget.FileState> decoderFiles = new ArrayList<WorldBuilderReadOnlyTarget.FileState>();
		Set<String> expectedFiles = new HashSet<String>();
		int[] differenceCounts = new int[7];
		long discardedDirections = 0;
		for (Object raw : probes) {
			Map<String,Object> probe = object(raw);
			if (!Boolean.TRUE.equals(probe.get("present"))) continue;
			String name = "h" + number(probe, "plane") + "x" + number(probe, "archiveX") + "y" + number(probe, "archiveY");
			String relative = name + ".raw";
			if (!relative.equals(probe.get("relativePath")) || !expectedFiles.add(relative))
				throw blocked("Decoder sector path does not match its reviewed coordinate.");
			WorldBuilderReadOnlyTarget.FileState state = decoded.requiredState("decoded-sector", relative);
			if (state.size != SECTOR_BYTES || !state.sha256.equals(probe.get("sha256")))
				throw blocked("Decoder sector bytes differ from their reviewed evidence.");
			decoderFiles.add(state);
			byte[] serverBytes = Files.readAllBytes(decoded.requiredFile(relative));
			if (!state.sha256.equals(WorldBuilderHashes.sha256(serverBytes))) throw blocked("Decoder sector drifted during its read.");
			byte[] clientBytes = client.get(name);
			if (clientBytes == null) throw blocked("A server-selected sector has no reviewed client presentation.");
			byte[] result = reconcileSector(name, serverBytes, clientBytes, differenceCounts);
			byte[] rawLayered = WorldBuilderPackedTerrainCodec.toLayered(serverBytes);
			WorldBuilderPackedTerrainCodec.requireExactReverse(serverBytes, rawLayered);
			byte[] fusedLayered = WorldBuilderPackedTerrainCodec.toLayered(result);
			WorldBuilderPackedTerrainCodec.requireExactReverse(result, fusedLayered);
			Map<String,Object> row = new LinkedHashMap<String,Object>();
			row.put("entry", name); row.put("historicalServerSha256", state.sha256);
			row.put("historicalClientSha256", WorldBuilderHashes.sha256(clientBytes));
			row.put("historicalServerReverseSha256", WorldBuilderHashes.sha256(WorldBuilderPackedTerrainCodec.toLegacy(rawLayered)));
			row.put("fusedPackedSha256", WorldBuilderHashes.sha256(result));
			row.put("fusedLayeredSha256", WorldBuilderHashes.sha256(fusedLayered));
			row.put("fusedReverseSha256", WorldBuilderHashes.sha256(WorldBuilderPackedTerrainCodec.toLegacy(fusedLayered)));
			sectors.add(row); fused.put(name, result);
			discardedDirections += number(probe, "discardedNonzeroDirectionTiles");
		}
		try (java.util.stream.Stream<Path> files = Files.walk(decoded.root)) {
			java.util.Iterator<Path> iterator = files.iterator();
			while (iterator.hasNext()) {
				Path path = iterator.next();
				if (path.equals(decoded.root)) continue;
				if (!expectedFiles.contains(decoded.root.relativize(path).toString()))
					throw blocked("Decoder output contains an undeclared file or directory.");
			}
		}
		if (fused.size() != 352 || discardedDirections != 15468
			|| !Arrays.equals(differenceCounts, new int[]{162324, 1, 1, 1, 0, 0, 291}))
			throw blocked("Historical server/client differences are not exactly the reviewed discrepancy set.");
		List<Object> exclusions = new ArrayList<Object>();
		int absentWithin = 0, outside = 0;
		for (Map.Entry<String,byte[]> entry : client.entrySet()) {
			if (fused.containsKey(entry.getKey())) continue;
			WorldBuilderPackedCoordinateCodec.Sector coordinate = WorldBuilderPackedCoordinateCodec.decodeTerrainEntry(entry.getKey());
			boolean within = coordinate.sectorY <= 19;
			if (within) absentWithin++; else outside++;
			Map<String,Object> row = new LinkedHashMap<String,Object>();
			row.put("entry", entry.getKey()); row.put("sha256", WorldBuilderHashes.sha256(entry.getValue()));
			row.put("reason", within ? "server-probed-absent" : "outside-server-probe-domain");
			exclusions.add(row);
		}
		if (absentWithin != 1328 || outside != 84) throw blocked("Client-only sector coverage differs from its reviewed provenance.");
		Map<String,Object> report = new LinkedHashMap<String,Object>();
		report.put("schemaVersion", Long.valueOf(1)); report.put("manifestType", "world-builder-preservation-map-reconciliation");
		report.put("derivationId", ID); report.put("runtimePromotionApproved", Boolean.FALSE);
		report.put("decoderEvidenceSha256", evidenceState.sha256); report.put("decoderInventorySha256", DECODE_INVENTORY_SHA256);
		report.put("sourceSettings", settings); report.put("sourceInventory", inventory(sources));
		report.put("decodedInventory", probes); report.put("sectorDerivations", sectors);
		report.put("excludedClientSectors", exclusions); report.put("fieldPolicy", fieldPolicy());
		report.put("reviewedCorrections", Arrays.<Object>asList(overlayCorrection()));
		report.put("placementSources", new ArrayList<String>(PLACEMENTS));
		report.put("placementPolicy", "historical-ordered-registration-and-npc-multiplicity");
		report.put("loginOnlyMarkersRetainedInClientSource", Long.valueOf(291));
		report.put("discardedDirectionsRetainedInDecoderEvidence", Long.valueOf(discardedDirections));
		report.put("visualLimitations", Arrays.<Object>asList(
			"Known overlay discrepancy changes one historical client tile; pixel-identical preservation is not claimed.",
			"Client-only sector bytes remain immutable provenance, not active terrain; adjacent background appearance is not guaranteed.",
			"Login-only scenery markers remain in the original client map and are not promoted into gameplay placements."));
		WorldBuilderAdaptiveExporter.bindFingerprint(report, "reconciliationFingerprintSha256");
		reverify(original, sources); reverify(decoded, decoderFiles);
		if (!evidenceState.sha256.equals(evidenceRoot.requiredState("decoder-evidence", evidenceName).sha256))
			throw blocked("Decoder evidence changed during reconciliation.");
		return new Plan(fused, report);
	}

	private static byte[] reconcileSector(String name, byte[] server, byte[] client, int[] counts)
		throws WorldBuilderContractException {
		byte[] result = server.clone();
		for (int offset = 0; offset < SECTOR_BYTES; offset += 10) {
			for (int field = 0; field < 7; field++) {
				int a = field == 6 ? ByteBuffer.wrap(server, offset + 6, 4).getInt() : server[offset + field] & 255;
				int b = field == 6 ? ByteBuffer.wrap(client, offset + 6, 4).getInt() : client[offset + field] & 255;
				if (a == b) continue;
				counts[field]++;
				boolean knownTile = "h3x54y47".equals(name) && offset / 10 == 24 * 48 + 36;
				if (field == 0 || field == 1 && knownTile && a == 176 && b == 70
					|| field == 3 && knownTile && a == 0 && b == 1) {
					result[offset + field] = client[offset + field];
				} else if (field == 2 && knownTile && a == 0 && b == 8) {
					// Reviewed correction preserves terrain collision after the ladder is removed.
				} else if (field == 6 && a == 0 && b > 48000 && b < 60000
					&& ("h0x50y49".equals(name) || "h0x50y50".equals(name))) {
					// Historical client consumes these only for login background models.
				} else throw blocked("Unreviewed gameplay/presentation conflict in " + name + " at tile " + offset / 10 + ".");
			}
		}
		return result;
	}

	private static Map<String,byte[]> readClientArchive(Path file) throws IOException, WorldBuilderContractException {
		Map<String,byte[]> result = new TreeMap<String,byte[]>();
		try (ZipFile archive = new ZipFile(file.toFile())) {
			Enumeration<? extends ZipEntry> entries = archive.entries();
			while (entries.hasMoreElements()) {
				ZipEntry entry = entries.nextElement();
				String name = entry.getName();
				if (entry.isDirectory() || entry.getSize() != SECTOR_BYTES || result.size() >= 1764
					|| result.containsKey(name)) throw blocked("Client map archive inventory is malformed or unbounded.");
				WorldBuilderPackedCoordinateCodec.Sector coordinate = WorldBuilderPackedCoordinateCodec.decodeTerrainEntry(name);
				if (coordinate.sectorX < 0 || coordinate.sectorX > 20 || coordinate.sectorY < 0 || coordinate.sectorY > 20)
					throw blocked("Client map entry is outside the reviewed archive grid.");
				try (InputStream input = archive.getInputStream(entry)) {
					ByteArrayOutputStream output = new ByteArrayOutputStream(SECTOR_BYTES);
					byte[] buffer = new byte[4096]; int count;
					while ((count = input.read(buffer)) != -1) {
						if (output.size() + count > SECTOR_BYTES) throw blocked("Client sector expanded beyond its exact bound.");
						output.write(buffer, 0, count);
					}
					if (output.size() != SECTOR_BYTES) throw blocked("Client sector is truncated.");
					result.put(name, output.toByteArray());
				}
			}
		}
		if (result.size() != 1764) throw blocked("Client map is not the complete reviewed archive.");
		return result;
	}

	private static Map<String,Object> fieldPolicy() {
		Map<String,Object> value = new LinkedHashMap<String,Object>();
		value.put("elevation", "historical-client-visual"); value.put("texture", "historical-client-visual");
		value.put("roof", "historical-client-visual"); value.put("overlay", "historical-server-gameplay");
		value.put("walls", "historical-server-gameplay"); value.put("diagonal", "historical-server-gameplay");
		value.put("sectorCoverage", "historical-server-present-only"); return value;
	}

	private static Map<String,Object> overlayCorrection() {
		Map<String,Object> value = new LinkedHashMap<String,Object>();
		value.put("correctionId", "preservation-discontinued-ladder-overlay-v1");
		value.put("worldSpace", "global"); value.put("level", Long.valueOf(-1));
		value.put("x", Long.valueOf(312)); value.put("y", Long.valueOf(516));
		value.put("historicalClientOverlay", Long.valueOf(8)); value.put("canonicalOverlay", Long.valueOf(0));
		value.put("ladderDefinitionId", Long.valueOf(199));
		value.put("reason", "Client overlay 8 adds terrain blocking; server overlay 0 does not. Keep server collision when the independently blocking ladder is removed.");
		value.put("interactionVerification", "provider-ladder-interaction-and-removal-proof-required"); return value;
	}

	private static WorldBuilderReadOnlyTarget canonical(Path path) throws IOException, WorldBuilderContractException {
		if (path == null || !path.isAbsolute() || !path.equals(path.normalize()) || !path.equals(path.toRealPath()))
			throw blocked("Reconciliation inputs must use literal canonical directories without aliases.");
		return WorldBuilderReadOnlyTarget.open(path);
	}
	private static void reverify(WorldBuilderReadOnlyTarget root, List<WorldBuilderReadOnlyTarget.FileState> states)
		throws WorldBuilderContractException {
		for (WorldBuilderReadOnlyTarget.FileState state : states) {
			WorldBuilderReadOnlyTarget.FileState current = root.requiredState(state.role, state.relativePath);
			if (current.size != state.size || !current.sha256.equals(state.sha256)) throw blocked("Reconciliation input drifted: " + state.relativePath);
		}
	}
	private static List<Object> inventory(List<WorldBuilderReadOnlyTarget.FileState> states) {
		List<Object> result = new ArrayList<Object>();
		for (WorldBuilderReadOnlyTarget.FileState state : states) {
			Map<String,Object> row = new LinkedHashMap<String,Object>();
			row.put("relativePath", state.relativePath); row.put("size", Long.valueOf(state.size)); row.put("sha256", state.sha256);
			result.add(row);
		}
		return result;
	}
	private static String hash(Object value) { return WorldBuilderHashes.sha256(WorldBuilderJsonDocuments.canonical(value).getBytes(StandardCharsets.UTF_8)); }
	@SuppressWarnings("unchecked") private static Map<String,Object> object(Object value) throws WorldBuilderContractException {
		if (!(value instanceof Map)) throw blocked("Expected a bounded evidence object."); return (Map<String,Object>)value;
	}
	@SuppressWarnings("unchecked") private static List<Object> list(Object value) throws WorldBuilderContractException {
		if (!(value instanceof List)) throw blocked("Expected a bounded evidence array."); return (List<Object>)value;
	}
	private static String string(Map<String,Object> row, String key) throws WorldBuilderContractException {
		return WorldBuilderBoundedInventory.string(row.get(key), OP, key);
	}
	private static long number(Map<String,Object> row, String key) throws WorldBuilderContractException {
		return WorldBuilderBoundedInventory.integer(row.get(key), OP, key);
	}
	private static WorldBuilderContractException blocked(String message) {
		return new WorldBuilderContractException(WorldBuilderErrorCodes.CONVERSION_BLOCKED, OP, "historical-map", false,
			message, "Preserve the source inputs and resolve the exact migration evidence before conversion or activation.");
	}

	static final class Plan {
		private final Map<String,byte[]> sectors;
		private final String reportJson;
		private Plan(Map<String,byte[]> sectors, Map<String,Object> report) {
			this.sectors = sectors; this.reportJson = WorldBuilderJsonDocuments.pretty(report);
		}
		String reportJson() { return reportJson; }
		Map<String,byte[]> packedSectors() {
			Map<String,byte[]> result = new TreeMap<String,byte[]>();
			for (Map.Entry<String,byte[]> entry : sectors.entrySet()) result.put(entry.getKey(), entry.getValue().clone());
			return Collections.unmodifiableMap(result);
		}
	}
}
