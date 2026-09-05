package com.openrsc.worldbuilder;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Creates a data-only conversion source in a separate, unpublished migration namespace. */
final class WorldBuilderPreservationMapEvidence {
	private static final String OP = "prepare-preservation-map";
	private static final String LOCATIONS = "server/conf/server/defs/locs/";
	private WorldBuilderPreservationMapEvidence() { }

	static Prepared prepare(Path projectStage, WorldBuilderPreservationJagDecoder.Result decoder)
		throws IOException, WorldBuilderContractException {
		if (projectStage == null || !projectStage.isAbsolute() || !projectStage.equals(projectStage.normalize())
			|| !projectStage.equals(projectStage.toRealPath())) throw blocked("Project staging path is noncanonical.");
		Path original = projectStage.resolve("source/original");
		Path migration = projectStage.resolve("source/migration");
		if (!original.equals(original.toRealPath()) || !migration.equals(migration.toRealPath())
			|| !decoder.attempt.equals(migration.resolve("decoder")))
			throw blocked("Historical derivation requires the exact unpublished source/original and source/migration namespaces.");
		WorldBuilderPreservationMapReconciliation.Plan fresh = WorldBuilderPreservationMapReconciliation.inspect(
			original, decoder.attempt.resolve("sectors"), decoder.attempt.resolve("evidence.json"));
		if (!fresh.reportJson().equals(decoder.plan.reportJson())
			|| !matchesJson(decoder.attempt, "invocation.json", decoder.invocationJson))
			throw blocked("Inventory-bound provider invocation no longer agrees with the immutable map sources.");
		Path input = migration.resolve("input");
		if (Files.exists(input, LinkOption.NOFOLLOW_LINKS)) throw blocked("Derived map input already exists; it cannot be overwritten.");
		Files.createDirectory(input, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
		List<WorldBuilderBoundedInventory.Record> inputs = new ArrayList<WorldBuilderBoundedInventory.Record>();
		write(input, "reconciliation.json", fresh.reportJson(), "historical-map-reconciliation", inputs);
		write(input, "invocation.json", decoder.invocationJson, "provider-decoder-invocation", inputs);
		write(input, "catalog.json", WorldBuilderJsonDocuments.pretty(WorldBuilderProjectContentBundle.preservationMapCatalog(original)),
			"historical-definition-catalog", inputs);
		String scenery = "server/conf/server/defs/GameObjectDef.xml";
		byte[] sceneryBytes = Files.readAllBytes(WorldBuilderReadOnlyTarget.open(original).requiredFile(scenery));
		write(input, scenery, sceneryBytes, "server-definition.scenery", inputs);
		Path terrain = input.resolve("server/fused.orsc");
		Files.createDirectories(terrain.getParent());
		try (OutputStream raw = Files.newOutputStream(terrain, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
			ZipOutputStream archive = new ZipOutputStream(raw)) {
			for (Map.Entry<String,byte[]> sector : fresh.packedSectors().entrySet()) {
				ZipEntry entry = new ZipEntry(sector.getKey());
				entry.setMethod(ZipEntry.STORED); entry.setSize(sector.getValue().length); entry.setCompressedSize(sector.getValue().length);
				CRC32 crc = new CRC32(); crc.update(sector.getValue()); entry.setCrc(crc.getValue()); entry.setTime(0L);
				archive.putNextEntry(entry); archive.write(sector.getValue()); archive.closeEntry();
			}
		}
		add(input, "server/fused.orsc", "server-terrain", inputs);
		Files.createDirectory(input.resolve("client"));
		Files.copy(terrain, input.resolve("client/fused.orsc"));
		add(input, "client/fused.orsc", "client-terrain", inputs);
		List<WorldBuilderAdaptiveConfiguration.PlacementSource> placements = new ArrayList<WorldBuilderAdaptiveConfiguration.PlacementSource>();
		List<Object> corrections = new ArrayList<Object>();
		String[][] selected = {
			{"BoundaryLocs.json", "boundary", "base", "boundaries", "boundaries"},
			{"SceneryLocs.json", "scenery", "base", "sceneries", "sceneries"},
			{"SceneryLocsDiscontinued.json", "scenery", "overlay", "sceneries", "sceneries"},
			{"NpcLocs.json", "npc", "base", "npclocs", "npclocs"},
			{"NpcLocsDiscontinued.json", "npc", "base", "npclocs", "npclocs"},
			{"GroundItems.json", "ground-item", "base", "grounditems", "ground_items"}
		};
		for (int index = 0; index < selected.length; index++) {
			String[] spec = selected[index];
			Map<String,Object> source;
			try { source = WorldBuilderJsonDocuments.readTargetDefinitionObject(original.resolve(LOCATIONS + spec[0])); }
			catch (WorldBuilderDiscoveryException malformed) { throw blocked("Selected historical placements are malformed."); }
			if (source.size() != 1 || !(source.get(spec[3]) instanceof List)) throw blocked("Selected placement root is unsupported.");
			List<?> records = (List<?>)source.get(spec[3]);
			if ("NpcLocs.json".equals(spec[0])) {
				WorldBuilderPackedCompatibilityCorrections.Result normalized =
					WorldBuilderPackedCompatibilityCorrections.normalizeBaseNpcs(LOCATIONS + spec[0], records);
				records = normalized.records;
				for (WorldBuilderPackedCompatibilityCorrections.Correction correction : normalized.corrections)
					corrections.add(correction.toJson());
			}
			Map<String,Object> value = new LinkedHashMap<String,Object>(); value.put(spec[4], records);
			String role = spec[1] + "-historical-" + index;
			String path = "placements/" + spec[0];
			write(input, path, WorldBuilderJsonDocuments.pretty(value), "placement." + role, inputs);
			placements.add(new WorldBuilderAdaptiveConfiguration.PlacementSource(role, spec[1], spec[2], index,
				"packed-" + spec[1] + "-locations-v1", path));
		}
		Map<String,Object> derivation = new LinkedHashMap<String,Object>();
		derivation.put("schemaVersion", Long.valueOf(1)); derivation.put("manifestType", "world-builder-preservation-data-source");
		derivation.put("derivationId", WorldBuilderPreservationMapReconciliation.ID);
		derivation.put("authority", "compiled-historical-data-only"); derivation.put("runtimePromotionApproved", Boolean.FALSE);
		derivation.put("placementCorrections", corrections);
		derivation.put("inputInventory", documents(inputs));
		WorldBuilderAdaptiveExporter.bindFingerprint(derivation, "sourceFingerprintSha256");
		write(input, "derivation.json", WorldBuilderJsonDocuments.pretty(derivation), "historical-data-derivation", inputs);
		Collections.sort(inputs, ORDER);
		Prepared prepared = new Prepared(projectStage, original, input, decoder.attempt,
			(String)derivation.get("sourceFingerprintSha256"), WorldBuilderHashes.sha256(input.resolve("catalog.json")),
			WorldBuilderHashes.sha256(input.resolve("derivation.json")), inputs, placements,
			fresh.reportJson(), decoder.invocationJson);
		prepared.reverify();
		WorldBuilderAdaptiveDurability.forceDirectory(input);
		return prepared;
	}

	private static final Comparator<WorldBuilderBoundedInventory.Record> ORDER = new Comparator<WorldBuilderBoundedInventory.Record>() {
		@Override public int compare(WorldBuilderBoundedInventory.Record a, WorldBuilderBoundedInventory.Record b) {
			int result = a.relativePath.compareTo(b.relativePath); return result == 0 ? a.role.compareTo(b.role) : result;
		}
	};
	private static List<Object> documents(List<WorldBuilderBoundedInventory.Record> inputs) {
		List<WorldBuilderBoundedInventory.Record> sorted = new ArrayList<WorldBuilderBoundedInventory.Record>(inputs);
		Collections.sort(sorted, ORDER); List<Object> result = new ArrayList<Object>();
		for (WorldBuilderBoundedInventory.Record record : sorted) {
			Map<String,Object> value = new LinkedHashMap<String,Object>();
			value.put("role", record.role); value.put("relativePath", record.relativePath); value.put("size", Long.valueOf(record.size));
			value.put("sha256", record.sha256); value.put("present", Boolean.TRUE); result.add(value);
		}
		return result;
	}
	private static void write(Path root, String relative, String value, String role, List<WorldBuilderBoundedInventory.Record> inputs)
		throws IOException, WorldBuilderContractException { write(root, relative, value.getBytes(StandardCharsets.UTF_8), role, inputs); }
	private static void write(Path root, String relative, byte[] value, String role, List<WorldBuilderBoundedInventory.Record> inputs)
		throws IOException, WorldBuilderContractException {
		Path file = root.resolve(relative); Files.createDirectories(file.getParent());
		Files.write(file, value, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE); add(root, relative, role, inputs);
	}
	private static void add(Path root, String relative, String role, List<WorldBuilderBoundedInventory.Record> inputs)
		throws IOException, WorldBuilderContractException {
		Path file = WorldBuilderReadOnlyTarget.open(root).requiredFile(relative);
		Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
		WorldBuilderAdaptiveDurability.forceFile(file);
		WorldBuilderReadOnlyTarget.FileState state = WorldBuilderReadOnlyTarget.open(root).requiredState(role, relative);
		inputs.add(new WorldBuilderBoundedInventory.Record(role, relative, true, state.size, state.sha256));
	}
	private static WorldBuilderContractException blocked(String message) {
		return new WorldBuilderContractException(WorldBuilderErrorCodes.CONVERSION_BLOCKED, OP, "source/migration", false,
			message, "Retain immutable source evidence; use a fresh unpublished project stage and the reviewed provider decoder.");
	}
	private static boolean matchesJson(Path root, String relative, String expected) throws WorldBuilderContractException {
		WorldBuilderReadOnlyTarget.FileState state = WorldBuilderReadOnlyTarget.open(root).requiredState("bound-json", relative);
		byte[] bytes = expected.getBytes(StandardCharsets.UTF_8);
		return state.size == bytes.length && state.sha256.equals(WorldBuilderHashes.sha256(bytes));
	}

	static final class Prepared {
		final Path projectStage, originalRoot, inputRoot, decoderAttempt;
		final String fingerprintSha256, catalogSha256, derivationSha256;
		final List<WorldBuilderBoundedInventory.Record> inputs;
		final List<WorldBuilderAdaptiveConfiguration.PlacementSource> placements;
		private final String reconciliationJson, invocationJson;
		private Prepared(Path projectStage, Path originalRoot, Path inputRoot, Path decoderAttempt,
			String fingerprintSha256, String catalogSha256, String derivationSha256,
			List<WorldBuilderBoundedInventory.Record> inputs, List<WorldBuilderAdaptiveConfiguration.PlacementSource> placements,
			String reconciliationJson, String invocationJson) {
			this.projectStage = projectStage; this.originalRoot = originalRoot; this.inputRoot = inputRoot; this.decoderAttempt = decoderAttempt;
			this.fingerprintSha256 = fingerprintSha256; this.catalogSha256 = catalogSha256; this.derivationSha256 = derivationSha256;
			this.inputs = Collections.unmodifiableList(new ArrayList<WorldBuilderBoundedInventory.Record>(inputs));
			this.placements = Collections.unmodifiableList(new ArrayList<WorldBuilderAdaptiveConfiguration.PlacementSource>(placements));
			this.reconciliationJson = reconciliationJson; this.invocationJson = invocationJson;
		}
		void reverify() throws IOException, WorldBuilderContractException {
			if (!inputRoot.equals(projectStage.resolve("source/migration/input")) || !inputRoot.equals(inputRoot.toRealPath()))
				throw blocked("Derived source namespace changed or became aliased.");
			WorldBuilderReadOnlyTarget root = WorldBuilderReadOnlyTarget.open(inputRoot);
			for (WorldBuilderBoundedInventory.Record expected : inputs) {
				WorldBuilderReadOnlyTarget.FileState actual = root.requiredState(expected.role, expected.relativePath);
				if (actual.size != expected.size || !actual.sha256.equals(expected.sha256)) throw blocked("Derived input drifted: " + expected.relativePath);
			}
			if (!reconciliationJson.equals(WorldBuilderPreservationMapReconciliation.inspect(originalRoot,
				decoderAttempt.resolve("sectors"), decoderAttempt.resolve("evidence.json")).reportJson()))
				throw blocked("Historical source/provenance no longer reconstructs the exact derived map.");
			if (!matchesJson(decoderAttempt, "invocation.json", invocationJson))
				throw blocked("Provider invocation proof drifted.");
		}
	}
}
