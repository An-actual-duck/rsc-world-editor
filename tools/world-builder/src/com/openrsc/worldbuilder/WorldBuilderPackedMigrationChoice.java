package com.openrsc.worldbuilder;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Immutable retirement decision for a primary packed Custom_Landscape source. */
final class WorldBuilderPackedMigrationChoice {
	private static final String OPERATION = "create-packed-map-migration-choice";
	private static final String ZERO_HASH =
		"0000000000000000000000000000000000000000000000000000000000000000";

	private final Map<String,Object> document;

	private WorldBuilderPackedMigrationChoice(Map<String,Object> document) {
		this.document = document;
	}

	static boolean applies(WorldBuilderAdaptiveDiscoveryReport report)
		throws WorldBuilderContractException {
		Map<String,Object> value = parseReport(report);
		if (!"compatible".equals(string(value, "status"))
			|| !"packed".equals(string(value, "representation"))) return false;
		return terrain(value, false) != null;
	}

	static WorldBuilderPackedMigrationChoice create(
		WorldBuilderAdaptiveDiscoveryReport report, boolean retirementRequested)
		throws WorldBuilderContractException {
		return create(parseReport(report), retirementRequested);
	}

	static WorldBuilderPackedMigrationChoice create(
		Map<String,Object> report, boolean retirementRequested)
		throws WorldBuilderContractException {
		if (!"compatible".equals(string(report, "status"))
			|| !"packed".equals(string(report, "representation"))) {
			throw problem("The selected target is not a compatible packed discovery.",
				"Select one supported packed Custom_Landscape map and retry.");
		}
		Map<String,Object> legacyTerrain = terrain(report, true);
		Map<String,Object> value = new LinkedHashMap<String,Object>();
		value.put("schemaVersion", Long.valueOf(1L));
		value.put("manifestType", "world-builder-packed-map-migration-choice");
		value.put("toolVersion", WorldBuilderAdaptiveDiscoveryReport.TOOL_VERSION);
		value.put("decision", "incorporate-primary-legacy-landscape");
		value.put("selectedTargetDiscoveryFingerprintSha256",
			requireFingerprint(report));
		value.put("selectedConfiguration", configuration(report));
		value.put("legacyTerrain", legacyTerrain);
		value.put("retirementRequested", Boolean.valueOf(retirementRequested));
		value.put("migrationChoiceFingerprintSha256", ZERO_HASH);
		WorldBuilderAdaptiveExporter.bindFingerprint(
			value, "migrationChoiceFingerprintSha256");
		WorldBuilderAdaptiveContracts.validateParsed(
			WorldBuilderAdaptiveContracts.Kind.PACKED_MAP_MIGRATION_CHOICE, value);
		return new WorldBuilderPackedMigrationChoice(value);
	}

	String toJson() {
		return WorldBuilderJsonDocuments.pretty(document);
	}

	private static Map<String,Object> parseReport(
		WorldBuilderAdaptiveDiscoveryReport report)
		throws WorldBuilderContractException {
		if (report == null) throw problem("Packed discovery was not supplied.",
			"Run read-only discovery before recording the migration choice.");
		try {
			Map<String,Object> value = WorldBuilderJsonDocuments.readObject(
				report.toJson().getBytes(StandardCharsets.UTF_8), "packed discovery report");
			WorldBuilderAdaptiveContracts.validateParsed(
				WorldBuilderAdaptiveContracts.Kind.DISCOVERY_REPORT, value);
			return value;
		} catch (WorldBuilderDiscoveryException malformed) {
			throw new WorldBuilderContractException(WorldBuilderErrorCodes.MALFORMED_JSON,
				OPERATION, "", false, "Generated packed discovery JSON is malformed.",
				"Run read-only discovery again.", malformed);
		}
	}

	private static String requireFingerprint(Map<String,Object> report)
		throws WorldBuilderContractException {
		String supplied = string(report, "discoveryFingerprintSha256");
		String display = string(report, "targetRootDisplay");
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
		if (!supplied.equals(calculated)) throw problem(
			"The packed discovery fingerprint does not match its content.",
			"Run read-only discovery again and do not edit its report.");
		return supplied;
	}

	private static Map<String,Object> configuration(Map<String,Object> report)
		throws WorldBuilderContractException {
		Map<String,Object> source = object(report.get("selectedConfiguration"));
		if (!Boolean.TRUE.equals(source.get("present"))) throw problem(
			"Packed discovery has no selected configuration.",
			"Select one exact detected configuration and rediscover.");
		Map<String,Object> copy = new LinkedHashMap<String,Object>();
		copy.put("present", Boolean.TRUE);
		copy.put("role", string(source, "role"));
		copy.put("relativePath", string(source, "relativePath"));
		copy.put("sha256", string(source, "sha256"));
		return copy;
	}

	private static Map<String,Object> terrain(Map<String,Object> report, boolean required)
		throws WorldBuilderContractException {
		List<WorldBuilderBoundedInventory.Record> files =
			WorldBuilderBoundedInventory.read(report.get("files"), OPERATION, 1, false);
		WorldBuilderBoundedInventory.Record client = null;
		WorldBuilderBoundedInventory.Record server = null;
		for (WorldBuilderBoundedInventory.Record file : files) {
			if ("client-terrain".equals(file.role)) client = file;
			if ("server-terrain".equals(file.role)) server = file;
		}
		boolean named = client != null && server != null
			&& client.relativePath.endsWith("/Custom_Landscape.orsc")
			&& server.relativePath.endsWith("/Custom_Landscape.orsc");
		if (!named) {
			if (!required) return null;
			throw problem("Packed discovery is not backed by matching Custom_Landscape files.",
				"Select the exact legacy landscape source and rediscover.");
		}
		if (!client.present || !server.present || client.size != server.size
			|| !client.sha256.equals(server.sha256)) throw problem(
			"Legacy client and server Custom_Landscape bytes differ.",
			"Resolve the conflicting landscape copies before incorporation.");
		Map<String,Object> value = new LinkedHashMap<String,Object>();
		value.put("server", inventory(server));
		value.put("client", inventory(client));
		value.put("byteIdentical", Boolean.TRUE);
		return value;
	}

	private static Map<String,Object> inventory(
		WorldBuilderBoundedInventory.Record source) {
		Map<String,Object> value = new LinkedHashMap<String,Object>();
		value.put("role", source.role);
		value.put("relativePath", source.relativePath);
		value.put("present", Boolean.valueOf(source.present));
		value.put("size", Long.valueOf(source.size));
		value.put("sha256", source.sha256);
		return value;
	}

	private static Map<String,Object> object(Object raw)
		throws WorldBuilderContractException {
		if (!(raw instanceof Map)) throw problem("Packed discovery is malformed.",
			"Run read-only discovery again.");
		@SuppressWarnings("unchecked") Map<String,Object> value = (Map<String,Object>)raw;
		return value;
	}

	private static String string(Map<String,Object> value, String key)
		throws WorldBuilderContractException {
		Object raw = value.get(key);
		if (!(raw instanceof String)) throw problem("Packed discovery is malformed.",
			"Run read-only discovery again.");
		return (String)raw;
	}

	private static WorldBuilderContractException problem(String message, String nextStep) {
		return new WorldBuilderContractException(
			WorldBuilderErrorCodes.CONTRACT_VALUE_INVALID, OPERATION, "", false,
			message, nextStep);
	}
}
