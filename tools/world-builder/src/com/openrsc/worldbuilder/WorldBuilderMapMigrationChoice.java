package com.openrsc.worldbuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Immutable user decision binding the normal layered target discovery to a
 * separate packed Custom_Landscape discovery.
 *
 * This producer is intentionally read-only.  It does not copy source files,
 * create a project, stage conversion output, or authorize target mutation.
 */
final class WorldBuilderMapMigrationChoice {
	private static final String OPERATION = "create-map-migration-choice";
	private static final String ZERO_HASH =
		"0000000000000000000000000000000000000000000000000000000000000000";

	private final Map<String,Object> document;

	private WorldBuilderMapMigrationChoice(Map<String,Object> document) {
		this.document = document;
	}

	static WorldBuilderMapMigrationChoice create(
		Path selectedTargetReportPath,
		Path legacyPackedReportPath,
		boolean retirementRequested)
		throws IOException, WorldBuilderContractException {
		Map<String,Object> selected = readReport(selectedTargetReportPath);
		Map<String,Object> legacy = readReport(legacyPackedReportPath);
		return create(selected, legacy, retirementRequested);
	}

	static WorldBuilderMapMigrationChoice create(
		WorldBuilderAdaptiveDiscoveryReport selectedTargetReport,
		WorldBuilderAdaptiveDiscoveryReport legacyPackedReport,
		boolean retirementRequested)
		throws WorldBuilderContractException {
		return create(parseReport(selectedTargetReport, "selected target"),
			parseReport(legacyPackedReport, "legacy landscape"), retirementRequested);
	}

	private static WorldBuilderMapMigrationChoice create(
		Map<String,Object> selected, Map<String,Object> legacy,
		boolean retirementRequested)
		throws WorldBuilderContractException {

		requireCompatibleRepresentation(selected, "layered", "selected target");
		requireCompatibleRepresentation(legacy, "packed", "legacy landscape");
		String selectedFingerprint = requireFingerprint(selected, "selected target");
		String legacyFingerprint = requireFingerprint(legacy, "legacy landscape");
		if (selectedFingerprint.equals(legacyFingerprint)) {
			throw problem("Selected-target and legacy-landscape discovery reports are not distinct.",
				"Rediscover the normal target and Custom_Landscape candidate separately.");
		}

		String selectedDisplay = string(selected, "targetRootDisplay", "selected target");
		String legacyDisplay = string(legacy, "targetRootDisplay", "legacy landscape");
		if (selectedDisplay.isEmpty() || !selectedDisplay.equals(legacyDisplay)) {
			throw problem("Selected-target and legacy-landscape reports do not identify the same target root.",
				"Run both read-only discovery passes against the same selected server root.");
		}

		Map<String,Object> value = new LinkedHashMap<String,Object>();
		value.put("schemaVersion", Long.valueOf(1L));
		value.put("manifestType", "world-builder-map-migration-choice");
		value.put("toolVersion", WorldBuilderAdaptiveDiscoveryReport.TOOL_VERSION);
		value.put("decision", "incorporate-legacy-landscape");
		value.put("selectedTargetDiscoveryFingerprintSha256", selectedFingerprint);
		value.put("legacyPackedDiscoveryFingerprintSha256", legacyFingerprint);
		value.put("selectedConfiguration", configuration(selected, "selected target"));
		value.put("legacyConfiguration", configuration(legacy, "legacy landscape"));
		value.put("legacyTerrain", legacyTerrain(legacy));
		value.put("retirementRequested", Boolean.valueOf(retirementRequested));
		value.put("migrationChoiceFingerprintSha256", ZERO_HASH);
		WorldBuilderAdaptiveExporter.bindFingerprint(
			value, "migrationChoiceFingerprintSha256");
		WorldBuilderAdaptiveContracts.validateParsed(
			WorldBuilderAdaptiveContracts.Kind.MAP_MIGRATION_CHOICE, value);
		return new WorldBuilderMapMigrationChoice(value);
	}

	private static Map<String,Object> parseReport(
		WorldBuilderAdaptiveDiscoveryReport report, String label)
		throws WorldBuilderContractException {
		if (report == null) {
			throw problem("The " + label + " discovery report was not supplied.",
				"Run both read-only discovery passes before recording the choice.");
		}
		try {
			Map<String,Object> value = WorldBuilderJsonDocuments.readObject(
				report.toJson().getBytes(StandardCharsets.UTF_8), label + " discovery report");
			WorldBuilderAdaptiveContracts.validateParsed(
				WorldBuilderAdaptiveContracts.Kind.DISCOVERY_REPORT, value);
			return value;
		} catch (WorldBuilderDiscoveryException malformed) {
			throw new WorldBuilderContractException(WorldBuilderErrorCodes.MALFORMED_JSON,
				OPERATION, "", false,
				"Generated " + label + " discovery JSON is malformed.",
				"Run both read-only discovery passes again.", malformed);
		}
	}

	String toJson() {
		return WorldBuilderJsonDocuments.pretty(document);
	}

	String fingerprintSha256() {
		return (String)document.get("migrationChoiceFingerprintSha256");
	}

	private static Map<String,Object> readReport(Path path)
		throws IOException, WorldBuilderContractException {
		WorldBuilderAdaptiveContracts.read(
			WorldBuilderAdaptiveContracts.Kind.DISCOVERY_REPORT, path);
		try {
			return WorldBuilderJsonDocuments.readObject(path);
		} catch (WorldBuilderDiscoveryException malformed) {
			throw new WorldBuilderContractException(WorldBuilderErrorCodes.MALFORMED_JSON,
				OPERATION, "", false,
				"Discovery report JSON could not be read safely.",
				"Run read-only discovery again and use its exact report.", malformed);
		}
	}

	private static void requireCompatibleRepresentation(
		Map<String,Object> report, String expected, String label)
		throws WorldBuilderContractException {
		if (!"compatible".equals(string(report, "status", label))
			|| !expected.equals(string(report, "representation", label))) {
			throw problem("The " + label + " report is not a compatible "
				+ expected + " discovery.",
				"Select a compatible " + expected + " discovery report and retry.");
		}
	}

	private static String requireFingerprint(Map<String,Object> report, String label)
		throws WorldBuilderContractException {
		String supplied = string(report, "discoveryFingerprintSha256", label);
		String display = string(report, "targetRootDisplay", label);
		report.put("targetRootDisplay", "");
		report.put("discoveryFingerprintSha256", ZERO_HASH);
		String calculated;
		try {
			calculated = WorldBuilderHashes.sha256(
				WorldBuilderJsonDocuments.canonical(report).getBytes(StandardCharsets.UTF_8));
		} finally {
			report.put("targetRootDisplay", display);
			report.put("discoveryFingerprintSha256", supplied);
		}
		if (!supplied.equals(calculated)) {
			throw problem("The " + label + " discovery fingerprint does not match its content.",
				"Run read-only discovery again and do not edit its report.");
		}
		return supplied;
	}

	private static Map<String,Object> configuration(
		Map<String,Object> report, String label) throws WorldBuilderContractException {
		Map<String,Object> source = object(report.get("selectedConfiguration"), label);
		if (!Boolean.TRUE.equals(source.get("present"))) {
			throw problem("The " + label + " report has no selected configuration.",
				"Select one exact detected configuration and rediscover.");
		}
		Map<String,Object> copy = new LinkedHashMap<String,Object>();
		copy.put("present", Boolean.TRUE);
		copy.put("role", string(source, "role", label));
		copy.put("relativePath", string(source, "relativePath", label));
		copy.put("sha256", string(source, "sha256", label));
		return copy;
	}

	private static Map<String,Object> legacyTerrain(Map<String,Object> report)
		throws WorldBuilderContractException {
		List<WorldBuilderBoundedInventory.Record> files =
			WorldBuilderBoundedInventory.read(report.get("files"), OPERATION,
				1, false);
		WorldBuilderBoundedInventory.Record client = null;
		WorldBuilderBoundedInventory.Record server = null;
		for (WorldBuilderBoundedInventory.Record file : files) {
			if ("client-terrain".equals(file.role)) client = file;
			if ("server-terrain".equals(file.role)) server = file;
		}
		if (client == null || server == null || !client.present || !server.present) {
			throw problem("Legacy landscape discovery lacks present client/server terrain evidence.",
				"Rediscover both Custom_Landscape copies before incorporation.");
		}
		if (client.size != server.size || !client.sha256.equals(server.sha256)) {
			throw problem("Legacy client and server Custom_Landscape bytes differ.",
				"Resolve the conflicting legacy landscape copies before incorporation.");
		}
		Map<String,Object> terrain = new LinkedHashMap<String,Object>();
		terrain.put("server", inventory(server));
		terrain.put("client", inventory(client));
		terrain.put("byteIdentical", Boolean.TRUE);
		return terrain;
	}

	private static Map<String,Object> inventory(WorldBuilderBoundedInventory.Record source) {
		Map<String,Object> value = new LinkedHashMap<String,Object>();
		value.put("role", source.role);
		value.put("relativePath", source.relativePath);
		value.put("present", Boolean.valueOf(source.present));
		value.put("size", Long.valueOf(source.size));
		value.put("sha256", source.sha256);
		return value;
	}

	private static Map<String,Object> object(Object value, String label)
		throws WorldBuilderContractException {
		if (!(value instanceof Map)) {
			throw problem("The " + label + " discovery report is malformed.",
				"Run read-only discovery again and use its exact report.");
		}
		@SuppressWarnings("unchecked") Map<String,Object> object = (Map<String,Object>)value;
		return object;
	}

	private static String string(Map<String,Object> value, String key, String label)
		throws WorldBuilderContractException {
		Object raw = value.get(key);
		if (!(raw instanceof String)) {
			throw problem("The " + label + " discovery report is malformed.",
				"Run read-only discovery again and use its exact report.");
		}
		return (String)raw;
	}

	private static WorldBuilderContractException problem(String message, String nextStep) {
		return new WorldBuilderContractException(
			WorldBuilderErrorCodes.CONTRACT_VALUE_INVALID, OPERATION, "", false,
			message, nextStep);
	}
}
