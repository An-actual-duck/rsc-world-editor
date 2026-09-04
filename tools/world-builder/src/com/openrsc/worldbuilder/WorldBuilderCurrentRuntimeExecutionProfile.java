package com.openrsc.worldbuilder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Closed Editor-owned adapter/migrator selection; target bytes never select code. */
final class WorldBuilderCurrentRuntimeExecutionProfile {
	static final String PRESERVATION_ADAPTER_ID = "preservation-family-v1";
	private static final String ZERO_HASH =
		"0000000000000000000000000000000000000000000000000000000000000000";

	final String profileId;
	final WorldBuilderCurrentRuntimeContracts.Document adapter;
	final String migratorId;
	final String serverBuildId;
	final String clientBuildId;
	final String mapPackageId;
	final String configurationMigrationId;
	final String stateMigrationId;
	final String mapMigrationId;
	final String activationManifestType;
	final boolean syntheticOnly;

	private WorldBuilderCurrentRuntimeExecutionProfile(String profileId,
		WorldBuilderCurrentRuntimeContracts.Document adapter, String migratorId,
		String serverBuildId, String clientBuildId, String mapPackageId,
		String configurationMigrationId, String stateMigrationId,
		String mapMigrationId, String activationManifestType, boolean syntheticOnly) {
		this.profileId = profileId; this.adapter = adapter; this.migratorId = migratorId;
		this.serverBuildId = serverBuildId; this.clientBuildId = clientBuildId;
		this.mapPackageId = mapPackageId;
		this.configurationMigrationId = configurationMigrationId;
		this.stateMigrationId = stateMigrationId; this.mapMigrationId = mapMigrationId;
		this.activationManifestType = activationManifestType;
		this.syntheticOnly = syntheticOnly;
	}

	static WorldBuilderCurrentRuntimeExecutionProfile preservation()
		throws WorldBuilderContractException {
		WorldBuilderCurrentRuntimeContracts.Document adapter =
			WorldBuilderCurrentRuntimeContracts.builtIn(
				WorldBuilderCurrentRuntimeContracts.Kind.INPUT_ADAPTER,
				preservationAdapterDocument());
		return new WorldBuilderCurrentRuntimeExecutionProfile(
			"preservation-family-upgrade-v1", adapter,
			"preservation-family-migrator-v1",
			"current-base-server-r1", "current-base-client-r1",
			"current-canonical-map-v1", "preservation-typed-configuration-v1",
			"preservation-durable-state-boundary-v1",
			WorldBuilderPackedTerrainCodec.CONVERSION_PROFILE_ID,
			"world-builder-current-runtime-activation", false);
	}

	static WorldBuilderCurrentRuntimeExecutionProfile synthetic(
		WorldBuilderCurrentRuntimeContracts.Document adapter)
		throws WorldBuilderContractException {
		if (!"preservation-synthetic-v1".equals(string(adapter.root, "adapterId"))
			|| !"synthetic-fixture".equals(string(adapter.root, "evidenceAuthority"))
			|| !"787d692c0b84e664eb7370aee40e6d5e9cc827dec2d9e87ba8be0d89089750e0"
				.equals(string(adapter.root, "adapterManifestHash"))) {
			throw refusal("Only the sealed synthetic adapter may use the test executor profile.");
		}
		return new WorldBuilderCurrentRuntimeExecutionProfile(
			"synthetic-current-upgrade-v1", adapter,
			"synthetic-preservation-migrator-v1",
			"synthetic-current-server-r1", "synthetic-current-client-r1",
			"synthetic-canonical-map-v1", "synthetic-preservation-config-v1",
			"synthetic-preservation-data-v1", "synthetic-preservation-map-v1",
			"world-builder-synthetic-current-activation", true);
	}

	Map<String,Object> identity() {
		Map<String,Object> result = new LinkedHashMap<String,Object>();
		result.put("profileId", profileId);
		result.put("migratorId", migratorId);
		result.put("serverBuildId", serverBuildId);
		result.put("clientBuildId", clientBuildId);
		result.put("mapPackageId", mapPackageId);
		result.put("configurationMigrationId", configurationMigrationId);
		result.put("stateMigrationId", stateMigrationId);
		result.put("mapMigrationId", mapMigrationId);
		result.put("activationManifestType", activationManifestType);
		result.put("syntheticOnly", Boolean.valueOf(syntheticOnly));
		return result;
	}

	static WorldBuilderCurrentRuntimeExecutionProfile fromIdentity(Map<String,Object> identity)
		throws WorldBuilderContractException {
		WorldBuilderCurrentRuntimeExecutionProfile preservation = preservation();
		if (canonical(preservation.identity()).equals(canonical(identity))) return preservation;
		String profileId = string(identity, "profileId");
		if ("synthetic-current-upgrade-v1".equals(profileId)) {
			Map<String,Object> expected = new LinkedHashMap<String,Object>();
			expected.put("profileId", "synthetic-current-upgrade-v1");
			expected.put("migratorId", "synthetic-preservation-migrator-v1");
			expected.put("serverBuildId", "synthetic-current-server-r1");
			expected.put("clientBuildId", "synthetic-current-client-r1");
			expected.put("mapPackageId", "synthetic-canonical-map-v1");
			expected.put("configurationMigrationId", "synthetic-preservation-config-v1");
			expected.put("stateMigrationId", "synthetic-preservation-data-v1");
			expected.put("mapMigrationId", "synthetic-preservation-map-v1");
			expected.put("activationManifestType", "world-builder-synthetic-current-activation");
			expected.put("syntheticOnly", Boolean.TRUE);
			if (canonical(expected).equals(canonical(identity))) return new WorldBuilderCurrentRuntimeExecutionProfile(
				"synthetic-current-upgrade-v1", null, "synthetic-preservation-migrator-v1",
				"synthetic-current-server-r1", "synthetic-current-client-r1",
				"synthetic-canonical-map-v1", "synthetic-preservation-config-v1",
				"synthetic-preservation-data-v1", "synthetic-preservation-map-v1",
				"world-builder-synthetic-current-activation", true);
		}
		throw refusal("Transaction execution profile is not a compiled reviewed identity.");
	}

	private static String canonical(Object value) {
		return WorldBuilderJsonDocuments.canonical(value);
	}

	Map<String,Object> migrationPlan(Path target, Map<String,Object> classification)
		throws WorldBuilderContractException {
		Map<String,Object> result = new LinkedHashMap<String,Object>();
		result.put("schemaVersion", Long.valueOf(1));
		result.put("manifestType", "world-builder-current-runtime-migration-plan");
		result.put("migratorId", migratorId);
		result.put("configurationMigrationId", configurationMigrationId);
		result.put("typedConfiguration", typedConfiguration(target));
		result.put("durableStateMigrationId", stateMigrationId);
		List<Object> durable = new ArrayList<Object>();
		for (Object raw : array(classification.get("evidence"))) {
			Map<String,Object> evidence = object(raw);
			String role = string(evidence, "role");
			if (role.contains("configuration") || role.contains("locations")
				|| role.contains("map") || "preserve-state".equals(
					string(evidence, "disposition"))) {
				Map<String,Object> record = new LinkedHashMap<String,Object>();
				record.put("role", role);
				record.put("relativePath", string(evidence, "relativePath"));
				record.put("sourceSha256", string(evidence, "sha256"));
				record.put("policy", "copy-to-staged-durable-state-and-verify-before-cutover");
				durable.add(record);
			}
		}
		result.put("durableState", durable);
		Map<String,Object> map = new LinkedHashMap<String,Object>();
		map.put("migrationId", mapMigrationId);
		map.put("sourceRelativePath", "client/cache/landscape.pack");
		map.put("destinationRole", "canonical-signed-layered-map");
		map.put("executionBoundary", syntheticOnly
			? "synthetic-plan-only" : "existing-packed-converter-after-capability-validation");
		result.put("mapMigration", map);
		result.put("migrationPlanFingerprintSha256", ZERO_HASH);
		WorldBuilderAdaptiveExporter.bindFingerprint(result,
			"migrationPlanFingerprintSha256");
		return result;
	}

	private Map<String,Object> typedConfiguration(Path target)
		throws WorldBuilderContractException {
		Path local = target.resolve("server/conf/local.conf");
		Path named = target.resolve("server/conf/preservation.conf");
		Path selected = Files.exists(local, LinkOption.NOFOLLOW_LINKS) ? local : named;
		if (syntheticOnly && !Files.exists(selected, LinkOption.NOFOLLOW_LINKS)) {
			Map<String,Object> empty = new LinkedHashMap<String,Object>();
			empty.put("schemaVersion", Long.valueOf(1));
			empty.put("manifestType", "world-builder-current-base-configuration");
			empty.put("sourceRelativePath", "");
			empty.put("precedence", "managed-ledger-no-legacy-configuration");
			empty.put("duplicatePolicy", "first-value-wins");
			empty.put("serverName", "Preservation");
			empty.put("experienceRate", Long.valueOf(1));
			empty.put("bindAddress", "127.0.0.1");
			empty.put("gamePort", Long.valueOf(43594));
			empty.put("externalSecretReferences", new ArrayList<Object>());
			empty.put("translations", new ArrayList<Object>());
			return empty;
		}
		if (!Files.isRegularFile(selected, LinkOption.NOFOLLOW_LINKS)
			|| Files.isSymbolicLink(selected)) throw refusal(
			"Preservation effective configuration is missing or unsafe.");
		List<String> lines;
		try {
			if (Files.size(selected) > 262144L) throw refusal(
				"Preservation effective configuration exceeds its bounded size.");
			lines = Files.readAllLines(selected, StandardCharsets.UTF_8);
		} catch (java.io.IOException failure) {
			throw new WorldBuilderContractException(WorldBuilderErrorCodes.DISCOVERY_DRIFT,
				"preservation-configuration", "server/conf", false,
				"Effective configuration could not be read safely.",
				"Keep the target offline and retry preview.", failure);
		}
		Map<String,String> values = new LinkedHashMap<String,String>();
		List<Object> translations = new ArrayList<Object>();
		for (int index = 0; index < lines.size(); index++) {
			String line = lines.get(index).trim();
			if (line.isEmpty() || line.startsWith("#")) continue;
			int equals = line.indexOf('=');
			if (equals <= 0) throw refusal("Legacy configuration contains a malformed line.");
			String legacy = line.substring(0, equals).trim();
			String canonical = alias(legacy);
			if (canonical == null) throw refusal(
				"Legacy configuration contains an unsupported key: " + legacy);
			if (values.containsKey(canonical)) continue; // reviewed first-value-wins rule
			String value = line.substring(equals + 1).trim();
			values.put(canonical, value);
			Map<String,Object> translation = new LinkedHashMap<String,Object>();
			translation.put("legacyKey", legacy); translation.put("currentKey", canonical);
			translation.put("sourceLine", Long.valueOf(index + 1));
			translations.add(translation);
		}
		String name = values.containsKey("serverName")
			? values.get("serverName") : "Preservation";
		if (name.isEmpty() || name.length() > 80) throw refusal(
			"Translated server name is empty or exceeds 80 characters.");
		long experience = integer(values, "experienceRate", 1L, 100L, 1L);
		long port = integer(values, "gamePort", 1L, 65535L, 43594L);
		String bind = values.containsKey("bindAddress")
			? values.get("bindAddress") : "127.0.0.1";
		if (!("127.0.0.1".equals(bind) || "::1".equals(bind)
			|| "localhost".equals(bind))) throw refusal(
			"Production preview refuses a non-loopback legacy bind address.");
		Map<String,Object> typed = new LinkedHashMap<String,Object>();
		typed.put("schemaVersion", Long.valueOf(1));
		typed.put("manifestType", "world-builder-current-base-configuration");
		typed.put("sourceRelativePath", target.relativize(selected).toString().replace('\\', '/'));
		typed.put("precedence", Files.exists(local, LinkOption.NOFOLLOW_LINKS)
			? "local-replaces-named-profile" : "named-profile");
		typed.put("duplicatePolicy", "first-value-wins");
		typed.put("serverName", name); typed.put("experienceRate", Long.valueOf(experience));
		typed.put("bindAddress", bind); typed.put("gamePort", Long.valueOf(port));
		typed.put("externalSecretReferences", new ArrayList<Object>());
		typed.put("translations", translations);
		return typed;
	}

	private static long integer(Map<String,String> values, String key,
		long minimum, long maximum, long fallback) throws WorldBuilderContractException {
		if (!values.containsKey(key)) return fallback;
		try {
			long value = Long.parseLong(values.get(key));
			if (value < minimum || value > maximum) throw new NumberFormatException();
			return value;
		} catch (NumberFormatException invalid) {
			throw refusal("Legacy configuration integer is outside its supported range: " + key);
		}
	}

	private static String alias(String key) {
		if (Arrays.asList("server_name", "serverName", "name").contains(key)) return "serverName";
		if (Arrays.asList("experience_rate", "exp_rate", "experiance_rate").contains(key)) return "experienceRate";
		if (Arrays.asList("bind_address", "bindAddress").contains(key)) return "bindAddress";
		if (Arrays.asList("port", "game_port", "gamePort").contains(key)) return "gamePort";
		return null;
	}

	private static Map<String,Object> preservationAdapterDocument() {
		Map<String,Object> root = new LinkedHashMap<String,Object>();
		root.put("schemaVersion", Long.valueOf(1));
		root.put("manifestType", "world-builder-input-adapter-v1");
		root.put("adapterId", PRESERVATION_ADAPTER_ID); root.put("adapterVersion", "v1");
		root.put("historicalRuntimeId", "preservation-family-reviewed-v1");
		root.put("evidenceAuthority", "production-reviewed"); root.put("installable", Boolean.FALSE);
		root.put("recommendedVariantId", "current-base-v1");
		root.put("supportedManagedPredecessorReleaseIds",
			Collections.<Object>singletonList("rsc-current-platform-r0"));
		root.put("targetLedgerRelativePath", ".world-builder/runtime-ledger-v1.json");
		root.put("probeRoots", Arrays.<Object>asList("client", "server"));
		List<Object> rules = new ArrayList<Object>();
		rules.add(rule("legacy-map-client", "client/cache/landscape.pack", true, 30L,
			"3ab420b175819030d487ef6bd47959c5818684b11999ba9fd8bd21d29ce7b589", "map"));
		rules.add(rule("legacy-configuration-local", "server/conf/local.conf", false, 0L, "", "configuration",
			delta(43L, "cc5d0e317ed6cd936724a9b7b24dfebe665674f21e378986cf76ee34938e2384", "T1", "typed-configuration", "Baseline-equivalent local replacement translates without semantic change."),
			delta(43L, "f7f14767a1a15bf09db43177aaea7145d01c7a80850485ca7d214207b5aa40bd", "T2A", "typed-configuration", "Supported local replacement translates to typed Current Base configuration.")));
		rules.add(rule("legacy-configuration", "server/conf/preservation.conf", true, 43L,
			"cc5d0e317ed6cd936724a9b7b24dfebe665674f21e378986cf76ee34938e2384", "configuration",
			delta(43L, "f7f14767a1a15bf09db43177aaea7145d01c7a80850485ca7d214207b5aa40bd", "T2A", "typed-configuration", "Supported named profile translates to typed Current Base configuration.")));
		rules.add(rule("legacy-locations", "server/data/locations.json", true, 41L,
			"a90adab3ba2a9cb24606b2f7666b993e3a69639af932c13edec0cb134ce952cc", "portable-data",
			delta(66L, "65d5f2b8165e39a33562ab5c89e334db9587062cc7dec7e08aad6b82adce537a", "T2B", "canonical-data", "Reviewed existing-ID locations migrate as canonical data.")));
		rules.add(rule("legacy-generated-state", "server/generated/runtime.cache", false, 0L, "", "generated-state",
			delta(39L, "a8f2aab06f39f9ffeed028b9bdbf5a691e7a18e0900e5613fd5fb272be0d5a5f", "T1", "discard-generated", "Reviewed generated cache is reproducible and discarded.")));
		rules.add(rule("legacy-plugin-source", "server/plugins/Welcome.java", true, 63L,
			"1b18e7c7c80198d069c8001944526a7d6411a45dbd36ce944530246d6f68b66f", "plugin-source"));
		root.put("evidenceRules", rules); root.put("adapterManifestHash", ZERO_HASH);
		return root;
	}

	private static Map<String,Object> rule(String role, String path, boolean required,
		long size, String hash, String kind, Map<String,Object>... deltas) {
		Map<String,Object> result = new LinkedHashMap<String,Object>();
		result.put("role", role); result.put("relativePath", path);
		result.put("required", Boolean.valueOf(required)); result.put("baselineSize", Long.valueOf(size));
		result.put("baselineSha256", hash); result.put("evidenceKind", kind);
		result.put("recognizedDeltas", new ArrayList<Object>(Arrays.<Object>asList(deltas)));
		return result;
	}

	private static Map<String,Object> delta(long size, String hash, String tier,
		String disposition, String reason) {
		Map<String,Object> result = new LinkedHashMap<String,Object>();
		result.put("size", Long.valueOf(size)); result.put("sha256", hash);
		result.put("tier", tier); result.put("disposition", disposition);
		result.put("moduleId", ""); result.put("reason", reason); return result;
	}

	@SuppressWarnings("unchecked") private static Map<String,Object> object(Object value) {
		return (Map<String,Object>)value;
	}
	@SuppressWarnings("unchecked") private static List<Object> array(Object value) {
		return (List<Object>)value;
	}
	private static String string(Map<String,Object> root, String key)
		throws WorldBuilderContractException {
		return WorldBuilderBoundedInventory.string(root.get(key),
			"current-runtime-execution-profile", key);
	}
	private static WorldBuilderContractException refusal(String message) {
		return new WorldBuilderContractException(WorldBuilderErrorCodes.CONVERSION_BLOCKED,
			"current-runtime-execution-profile", "migration-profile", false, message,
			"Use only the reviewed built-in Preservation family or sealed synthetic profile.");
	}
}
