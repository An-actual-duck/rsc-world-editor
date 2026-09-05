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
	final boolean executionReady;
	final String executionReadinessStatus;
	final String executionReadinessReason;

	private WorldBuilderCurrentRuntimeExecutionProfile(String profileId,
		WorldBuilderCurrentRuntimeContracts.Document adapter, String migratorId,
		String serverBuildId, String clientBuildId, String mapPackageId,
		String configurationMigrationId, String stateMigrationId,
		String mapMigrationId, String activationManifestType, boolean syntheticOnly,
		boolean executionReady, String executionReadinessStatus,
		String executionReadinessReason) {
		this.profileId = profileId; this.adapter = adapter; this.migratorId = migratorId;
		this.serverBuildId = serverBuildId; this.clientBuildId = clientBuildId;
		this.mapPackageId = mapPackageId;
		this.configurationMigrationId = configurationMigrationId;
		this.stateMigrationId = stateMigrationId; this.mapMigrationId = mapMigrationId;
		this.activationManifestType = activationManifestType;
		this.syntheticOnly = syntheticOnly;
		this.executionReady = executionReady;
		this.executionReadinessStatus = executionReadinessStatus;
		this.executionReadinessReason = executionReadinessReason;
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
			"preservation-state-to-current-base-v1",
			WorldBuilderPackedTerrainCodec.CONVERSION_PROFILE_ID,
			"world-builder-current-runtime-activation", false, false,
			"migration-and-verification-not-implemented",
			"Production activation remains disabled pending activation-bound generated state, live-instance installation, and Editor integration of the provider-owned staged/installed launch, handshake, login, map, state, restart and gameplay verifier.");
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
			"world-builder-synthetic-current-activation", true, true,
			"synthetic-regression-ready",
			"Only the sealed synthetic transaction harness implements and verifies this profile.");
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
		result.put("executionReady", Boolean.valueOf(executionReady));
		result.put("executionReadinessStatus", executionReadinessStatus);
		result.put("executionReadinessReason", executionReadinessReason);
		result.put("executionReadinessConditions", executionReadinessConditions(syntheticOnly));
		return result;
	}

	private static List<Object> executionReadinessConditions(boolean synthetic) {
		List<Object> result = new ArrayList<Object>();
		if (synthetic) {
			result.add(readiness("sealed-synthetic-transaction-executor", true));
			result.add(readiness("synthetic-failure-rollback-recovery", true));
			return result;
		}
		result.add(readiness("typed-configuration-staging", true));
		result.add(readiness("provider-state-schema-migration-row", true));
		result.add(readiness("closed-sqlite-current-schema-migration", true));
		result.add(readiness("complete-canonical-map-package", true));
		result.add(readiness("activation-bound-generated-state-inventory", false));
		result.add(readiness("runnable-current-runtime-layout", false));
		result.add(readiness("editor-installed-execution-verifier-integration", false));
		result.add(readiness("staged-runtime-launch-handshake-login-gameplay", false));
		result.add(readiness("installed-runtime-launch-handshake-login-gameplay", false));
		return result;
	}

	boolean activationReady(Map<String,Object> migrationPlan)
		throws WorldBuilderContractException {
		if (!executionReady) return false;
		if (syntheticOnly) return true;
		Map<String,Object> staged = object(migrationPlan.get("stagedExecution"));
		if (!array(staged.get("readinessBlockers")).isEmpty()
			|| !WorldBuilderBoundedInventory.bool(staged.get("typedConfigurationReady"),
				"current-runtime-migration", "typedConfigurationReady")
			|| !WorldBuilderBoundedInventory.bool(staged.get("canonicalMapPackageReady"),
				"current-runtime-migration", "canonicalMapPackageReady")) return false;
		String engine = string(object(staged.get("providerStateMigration")), "engine");
		return "sqlite".equals(engine)
			? WorldBuilderBoundedInventory.bool(staged.get("sqliteSchemaMigrationReady"),
				"current-runtime-migration", "sqliteSchemaMigrationReady")
			: "mariadb".equals(engine)
				&& WorldBuilderBoundedInventory.bool(staged.get("mariaDbMigrationReady"),
					"current-runtime-migration", "mariaDbMigrationReady");
	}

	private static Map<String,Object> readiness(String id, boolean ready) {
		Map<String,Object> result = new LinkedHashMap<String,Object>();
		result.put("conditionId", id); result.put("ready", Boolean.valueOf(ready));
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
			expected.put("executionReady", Boolean.TRUE);
			expected.put("executionReadinessStatus", "synthetic-regression-ready");
			expected.put("executionReadinessReason", "Only the sealed synthetic transaction harness implements and verifies this profile.");
			expected.put("executionReadinessConditions", executionReadinessConditions(true));
			if (canonical(expected).equals(canonical(identity))) return new WorldBuilderCurrentRuntimeExecutionProfile(
				"synthetic-current-upgrade-v1", null, "synthetic-preservation-migrator-v1",
				"synthetic-current-server-r1", "synthetic-current-client-r1",
				"synthetic-canonical-map-v1", "synthetic-preservation-config-v1",
				"synthetic-preservation-data-v1", "synthetic-preservation-map-v1",
				"world-builder-synthetic-current-activation", true, true,
				"synthetic-regression-ready",
				"Only the sealed synthetic transaction harness implements and verifies this profile.");
		}
		throw refusal("Transaction execution profile is not a compiled reviewed identity.");
	}

	private static String canonical(Object value) {
		return WorldBuilderJsonDocuments.canonical(value);
	}

	Map<String,Object> migrationPlan(Path target, Map<String,Object> classification,
		WorldBuilderProviderCatalog.Composition composition, Path packedSourceRoot,
		Path packedDiscoveryReport)
		throws WorldBuilderContractException {
		Map<String,Object> result = new LinkedHashMap<String,Object>();
		result.put("schemaVersion", Long.valueOf(1));
		result.put("manifestType", "world-builder-current-runtime-migration-plan");
		result.put("migratorId", migratorId);
		result.put("configurationMigrationId", configurationMigrationId);
		Map<String,Object> typed = typedConfiguration(target);
		result.put("typedConfiguration", typed);
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
		map.put("executionBoundary", syntheticOnly ? "synthetic-plan-only"
			: "descriptor-backed-world-builder-packed-converter");
		String sourceFingerprint = "";
		String reportHash = "";
		String conversionPlanFingerprint = "";
		String conversionPlanSha256 = "";
		String conversionReportSha256 = "";
		String discoveryReconciliationSha256 = "";
		String outputPackageFingerprint = "";
		List<Object> outputInventory = new ArrayList<Object>();
		long terrainCount = 0L;
		long placementCount = 0L;
		boolean mapReady = false;
		if (!syntheticOnly && packedSourceRoot != null && packedDiscoveryReport != null) {
			try {
				WorldBuilderPackedConversionSource prepared =
					WorldBuilderPackedConversionSource.open(
						packedSourceRoot, packedDiscoveryReport);
				Path reviewedTarget = target.toRealPath();
				if (prepared.canonicalReportedTargetRoot == null
					|| !reviewedTarget.equals(prepared.canonicalReportedTargetRoot))
					throw refusal("Packed map evidence was not discovered from the exact target being upgraded.");
				WorldBuilderReadOnlyTarget reviewed = WorldBuilderReadOnlyTarget.open(target);
				for (WorldBuilderBoundedInventory.Record input : prepared.inputs) {
					WorldBuilderReadOnlyTarget.FileState state = reviewed.requiredState(
						input.role, input.relativePath);
					if (state.size != input.size || !state.sha256.equals(input.sha256))
						throw refusal("Packed map source copy no longer matches the selected target at "
							+ input.relativePath + ".");
				}
				WorldBuilderPackedConverter.Inspection inspected =
					new WorldBuilderPackedConverter().inspect(
						packedSourceRoot, packedDiscoveryReport);
				sourceFingerprint = inspected.sourceFingerprintSha256;
				conversionPlanFingerprint = inspected.planFingerprintSha256;
				conversionPlanSha256 = inspected.planSha256;
				conversionReportSha256 = inspected.reportSha256;
				discoveryReconciliationSha256 = inspected.reconciliationSha256;
				outputPackageFingerprint = inspected.outputFingerprintSha256;
				outputInventory.addAll(inspected.outputInventory);
				terrainCount = inspected.terrainCount;
				placementCount = inspected.placementCount;
				reportHash = WorldBuilderHashes.sha256(packedDiscoveryReport);
				mapReady = true;
			} catch (java.io.IOException failure) {
				throw new WorldBuilderContractException(WorldBuilderErrorCodes.DISCOVERY_DRIFT,
					"current-runtime-migration", "packed-discovery-report", false,
					"Packed conversion preview evidence could not be read.",
					"Keep the target offline and recreate the immutable packed source.", failure);
			}
		} else if (!syntheticOnly && (packedSourceRoot != null
			|| packedDiscoveryReport != null)) throw refusal(
			"Packed conversion source and discovery report must be supplied together.");
		map.put("preparedSourceFingerprintSha256", sourceFingerprint);
		map.put("discoveryReportSha256", reportHash);
		map.put("conversionPlanFingerprintSha256", conversionPlanFingerprint);
		map.put("conversionPlanSha256", conversionPlanSha256);
		map.put("conversionReportSha256", conversionReportSha256);
		map.put("discoveryReconciliationSha256", discoveryReconciliationSha256);
		map.put("outputPackageFingerprintSha256", outputPackageFingerprint);
		map.put("terrainCount", Long.valueOf(terrainCount));
		map.put("placementCount", Long.valueOf(placementCount));
		map.put("outputInventory", outputInventory);
		map.put("packageReady", Boolean.valueOf(mapReady));
		result.put("mapMigration", map);
		result.put("stagedExecution", syntheticOnly
			? syntheticStagedExecution()
			: WorldBuilderPreservationStagedMigrator.plan(target, typed,
				composition, mapReady));
		result.put("migrationPlanFingerprintSha256", ZERO_HASH);
		WorldBuilderAdaptiveExporter.bindFingerprint(result,
			"migrationPlanFingerprintSha256");
		return result;
	}

	void validateMigrationPlan(Map<String,Object> plan)
		throws WorldBuilderContractException {
		WorldBuilderBoundedInventory.exactKeys(plan, "current-runtime-migration",
			"schemaVersion", "manifestType", "migratorId", "configurationMigrationId",
			"typedConfiguration", "durableStateMigrationId", "durableState",
			"mapMigration", "stagedExecution", "migrationPlanFingerprintSha256");
		if (WorldBuilderBoundedInventory.integer(plan.get("schemaVersion"),
				"current-runtime-migration", "schemaVersion") != 1L
			|| !"world-builder-current-runtime-migration-plan".equals(
				string(plan, "manifestType"))
			|| !migratorId.equals(string(plan, "migratorId"))
			|| !configurationMigrationId.equals(
				string(plan, "configurationMigrationId"))
			|| !stateMigrationId.equals(string(plan, "durableStateMigrationId")))
			throw refusal("Migration plan does not match its compiled profile.");
		Map<String,Object> typed = object(plan.get("typedConfiguration"));
		WorldBuilderBoundedInventory.exactKeys(typed, "current-runtime-migration",
			"schemaVersion", "manifestType", "sourceRelativePath", "precedence",
			"duplicatePolicy", "serverName", "experienceRate", "combatExperienceRate",
			"skillingExperienceRate", "bindAddress",
			"gamePort", "websocketPort", "databaseMigration", "externalSecretReferences",
			"sourceInventory", "untranslatedKeys", "configurationBlockers", "translations");
		if (!"world-builder-current-base-configuration".equals(
				string(typed, "manifestType"))
			|| !"first-value-wins".equals(string(typed, "duplicatePolicy")))
			throw refusal("Typed configuration has unsupported execution semantics.");
		String priorSource = "";
		for (Object raw : array(typed.get("sourceInventory"))) {
			Map<String,Object> source = object(raw);
			WorldBuilderBoundedInventory.exactKeys(source, "current-runtime-migration",
				"relativePath", "size", "sha256");
			String relative = WorldBuilderPortablePath.require(
				string(source, "relativePath"), "current-runtime-migration");
			if (!priorSource.isEmpty() && priorSource.compareTo(relative) >= 0
				|| WorldBuilderBoundedInventory.integer(source.get("size"),
					"current-runtime-migration", "size") < 0L
				|| !WorldBuilderBoundedInventory.isHash(string(source, "sha256")))
				throw refusal("Typed configuration source inventory is not exact and sorted.");
			priorSource = relative;
		}
		String priorUntranslated = "";
		for (Object raw : array(typed.get("untranslatedKeys"))) {
			if (!(raw instanceof String) || !((String)raw).matches("[A-Za-z0-9_]{1,128}")
				|| !priorUntranslated.isEmpty()
					&& priorUntranslated.compareTo((String)raw) >= 0) throw refusal(
						"Typed configuration untranslated-key inventory is invalid.");
			priorUntranslated = (String)raw;
		}
		for (Object raw : array(typed.get("configurationBlockers")))
			WorldBuilderBoundedInventory.identifier(raw, "current-runtime-migration",
				"configurationBlockers");
		Map<String,Object> map = object(plan.get("mapMigration"));
		WorldBuilderBoundedInventory.exactKeys(map, "current-runtime-migration",
			"migrationId", "sourceRelativePath", "destinationRole", "executionBoundary",
			"preparedSourceFingerprintSha256", "discoveryReportSha256",
			"conversionPlanFingerprintSha256", "conversionPlanSha256",
			"conversionReportSha256", "discoveryReconciliationSha256",
			"outputPackageFingerprintSha256", "terrainCount", "placementCount",
			"outputInventory", "packageReady");
		if (!mapMigrationId.equals(string(map, "migrationId")))
			throw refusal("Map migration does not match its compiled profile.");
		List<String> mapPaths = new ArrayList<String>();
		for (Object raw : array(map.get("outputInventory"))) {
			Map<String,Object> record = object(raw);
			WorldBuilderBoundedInventory.exactKeys(record, "current-runtime-migration",
				"relativePath", "size", "sha256", "mode");
			String relative = string(record, "relativePath");
			if (!relative.startsWith("migration/output/map/conversion/")
				|| relative.contains("..") || mapPaths.contains(relative)
				|| (!mapPaths.isEmpty() && mapPaths.get(mapPaths.size() - 1)
					.compareTo(relative) >= 0)
				|| WorldBuilderBoundedInventory.integer(record.get("size"),
					"current-runtime-migration", "size") < 0L
				|| !WorldBuilderBoundedInventory.isHash(string(record, "sha256"))
				|| !"0600".equals(string(record, "mode"))) throw refusal(
				"Canonical map inventory is not a closed private output set.");
			mapPaths.add(relative);
		}
		boolean packageReady = WorldBuilderBoundedInventory.bool(map.get("packageReady"),
			"current-runtime-migration", "packageReady");
		for (String field : Arrays.asList("preparedSourceFingerprintSha256",
			"discoveryReportSha256", "conversionPlanFingerprintSha256",
			"conversionPlanSha256", "conversionReportSha256",
			"discoveryReconciliationSha256", "outputPackageFingerprintSha256")) {
			String value = string(map, field);
			if (packageReady ? !WorldBuilderBoundedInventory.isHash(value) : !value.isEmpty())
				throw refusal("Canonical map readiness and " + field + " disagree.");
		}
		if (!packageReady && !mapPaths.isEmpty()) throw refusal(
			"Unavailable canonical map migration cannot carry an output inventory.");
		if (packageReady && (mapPaths.isEmpty()
			|| !mapPaths.contains("migration/output/map/conversion/conversion-plan.json")
			|| !mapPaths.contains("migration/output/map/conversion/conversion-report.json")
			|| !mapPaths.contains("migration/output/map/conversion/"
				+ WorldBuilderDiscoveryReconciliation.FILE_NAME)
			|| !mapPaths.contains("migration/output/map/conversion/package/manifest.json")))
			throw refusal("Canonical map inventory omits required conversion evidence.");
		Map<String,Object> staged = object(plan.get("stagedExecution"));
		if (syntheticOnly) {
			WorldBuilderBoundedInventory.exactKeys(staged, "current-runtime-migration",
				"implementationId", "stagedOutputs", "readinessBlockers");
			if (!"synthetic-plan-only".equals(string(staged, "implementationId")))
				throw refusal("Synthetic staged execution identity changed.");
		} else validateProductionStagedExecution(staged);
		for (Object raw : array(plan.get("durableState"))) {
			Map<String,Object> record = object(raw);
			WorldBuilderBoundedInventory.exactKeys(record, "current-runtime-migration",
				"role", "relativePath", "sourceSha256", "policy");
		}
		String supplied = string(plan, "migrationPlanFingerprintSha256");
		Map<String,Object> copy = new LinkedHashMap<String,Object>(plan);
		copy.put("migrationPlanFingerprintSha256", ZERO_HASH);
		String expected = WorldBuilderHashes.sha256(canonical(copy)
			.getBytes(StandardCharsets.UTF_8));
		if (!expected.equals(supplied)) throw refusal(
			"Migration plan fingerprint does not match its content.");
	}

	private static Map<String,Object> syntheticStagedExecution() {
		Map<String,Object> result = new LinkedHashMap<String,Object>();
		result.put("implementationId", "synthetic-plan-only");
		result.put("stagedOutputs", new ArrayList<Object>());
		result.put("readinessBlockers", new ArrayList<Object>());
		return result;
	}

	private static void validateProductionStagedExecution(Map<String,Object> staged)
		throws WorldBuilderContractException {
		WorldBuilderBoundedInventory.exactKeys(staged, "current-runtime-migration",
			"implementationId", "requiredStateMigrationContractId",
			"requiredStateMigrationRowIds", "requiredProviderArtifactRoles",
			"providerStateMigration",
			"typedConfigurationReady", "sqliteSnapshotReady",
			"sqliteSchemaMigrationReady", "mariaDbMigrationReady",
			"canonicalMapPackageReady", "stagedOutputs", "runtimeLayout",
			"readinessBlockers");
		if (!"preservation-staged-data-migrator-v1".equals(
			string(staged, "implementationId"))
			|| !"current-base-state-migration-v1".equals(
				string(staged, "requiredStateMigrationContractId"))
			|| !WorldBuilderPreservationStagedMigrator.migrationRows(
				string(object(staged.get("providerStateMigration")), "engine")).equals(
					array(staged.get("requiredStateMigrationRowIds")))) throw refusal(
			"Production staged migrator identity changed.");
		if (!array(staged.get("requiredProviderArtifactRoles")).equals(
			Arrays.<Object>asList("state-migration-manifest", "contract-schema",
				"server-runtime"))) throw refusal(
			"Production state-migration artifact roles changed.");
		WorldBuilderPreservationStagedMigrator.validateStateBinding(
			object(staged.get("providerStateMigration")));
		WorldBuilderCurrentRuntimeLayout.validatePlan(object(staged.get("runtimeLayout")));
		List<String> kinds = new ArrayList<String>();
		for (Object raw : array(staged.get("stagedOutputs"))) {
			Map<String,Object> output = object(raw);
			WorldBuilderBoundedInventory.exactKeys(output, "current-runtime-migration",
				"relativePath", "kind", "sourceRelativePath", "sourceSha256",
				"size", "sha256", "mode");
			String kind = string(output, "kind");
			if (kinds.contains(kind)) throw refusal(
				"Production staged migration repeats an output kind.");
			kinds.add(kind);
			String relative = string(output, "relativePath");
			String source = string(output, "sourceRelativePath");
			if ("typed-configuration".equals(kind)) {
				if (!WorldBuilderPreservationStagedMigrator.CONFIG_OUTPUT.equals(relative)
					|| !source.isEmpty()) throw refusal("Typed configuration path changed.");
			} else throw refusal("Production staged output kind is not compiled.");
		}
		if (!kinds.contains("typed-configuration")) throw refusal(
			"Production staged migration omits typed configuration.");
	}

	private Map<String,Object> typedConfiguration(Path target)
		throws WorldBuilderContractException {
		boolean productionLayout = Files.exists(target.resolve("server/preservation.conf"),
			LinkOption.NOFOLLOW_LINKS)
			|| Files.exists(target.resolve("server/local.conf"), LinkOption.NOFOLLOW_LINKS);
		Path local = target.resolve(productionLayout
			? "server/local.conf" : "server/conf/local.conf");
		Path named = target.resolve(productionLayout
			? "server/preservation.conf" : "server/conf/preservation.conf");
		Path connections = target.resolve("server/connections.conf");
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
			empty.put("combatExperienceRate", Long.valueOf(1));
			empty.put("skillingExperienceRate", Long.valueOf(1));
			empty.put("bindAddress", "127.0.0.1");
			empty.put("gamePort", Long.valueOf(43594));
			empty.put("websocketPort", Long.valueOf(43494));
			empty.put("databaseMigration", sqliteDatabaseMigration());
			empty.put("externalSecretReferences", new ArrayList<Object>());
			empty.put("sourceInventory", new ArrayList<Object>());
			empty.put("untranslatedKeys", new ArrayList<Object>());
			empty.put("configurationBlockers", new ArrayList<Object>());
			empty.put("translations", new ArrayList<Object>());
			return empty;
		}
		if (!Files.isRegularFile(selected, LinkOption.NOFOLLOW_LINKS)
			|| Files.isSymbolicLink(selected)) throw refusal(
			"Preservation effective configuration is missing or unsafe.");
		Map<String,String> values = new LinkedHashMap<String,String>();
		List<Object> translations = new ArrayList<Object>();
		List<Object> sourceInventory = new ArrayList<Object>();
		List<Object> untranslated = new ArrayList<Object>();
		boolean ambiguousColonValue = false;
		boolean nullOverwriteValue = false;
		List<Path> sources = new ArrayList<Path>();
		if (productionLayout) {
			if (!Files.isRegularFile(connections, LinkOption.NOFOLLOW_LINKS)
				|| Files.isSymbolicLink(connections)) throw refusal(
					"Preservation connections.conf is missing or unsafe.");
			sources.add(connections);
		}
		sources.add(selected);
		for (Path source : sources) {
		List<String> lines;
		try {
			if (Files.size(source) > 262144L) throw refusal(
				"Preservation effective configuration exceeds its bounded size.");
			lines = Files.readAllLines(source, StandardCharsets.UTF_8);
			Map<String,Object> inventory = new LinkedHashMap<String,Object>();
			inventory.put("relativePath", target.relativize(source).toString().replace('\\', '/'));
			inventory.put("size", Long.valueOf(Files.size(source)));
			inventory.put("sha256", WorldBuilderHashes.sha256(source));
			sourceInventory.add(inventory);
		} catch (java.io.IOException failure) {
			throw new WorldBuilderContractException(WorldBuilderErrorCodes.DISCOVERY_DRIFT,
				"preservation-configuration", "server", false,
				"Effective configuration could not be read safely.",
				"Keep the target offline and retry preview.", failure);
		}
		String sourceRelative = target.relativize(source).toString().replace('\\', '/');
		for (int index = 0; index < lines.size(); index++) {
			String line = lines.get(index).trim();
			if (line.isEmpty() || line.startsWith("#")) continue;
			int equals = line.indexOf('=');
			int colon = line.indexOf(':');
			int separator = equals > 0 && colon > 0 ? Math.min(equals, colon)
				: Math.max(equals, colon);
			if (separator <= 0) throw refusal("Legacy configuration contains a malformed line.");
			String legacy = line.substring(0, separator).trim();
			String value = line.substring(separator + 1).trim();
			int comment = value.indexOf('#');
			if (comment >= 0) value = value.substring(0, comment).trim();
			if (value.isEmpty() && line.endsWith(":")) continue; // section heading
			if (colon == separator && value.indexOf(':') >= 0)
				ambiguousColonValue = true;
			String canonical = alias(legacy);
			if (canonical == null) {
				if (value.isEmpty() || "null".equalsIgnoreCase(value))
					nullOverwriteValue = true;
				if (!untranslated.contains(legacy)) untranslated.add(legacy);
				continue;
			}
			if (value.isEmpty() || "null".equalsIgnoreCase(value)) throw refusal(
				"Supported legacy configuration key has null/empty overwrite semantics: "
					+ legacy);
			if (values.containsKey(canonical)) continue; // reviewed first-value-wins rule
			values.put(canonical, value);
			Map<String,Object> translation = new LinkedHashMap<String,Object>();
			translation.put("legacyKey", legacy); translation.put("currentKey", canonical);
			translation.put("sourceRelativePath", sourceRelative);
			translation.put("sourceLine", Long.valueOf(index + 1));
			translations.add(translation);
		}
		}
		String name = values.containsKey("serverName")
			? values.get("serverName") : "Preservation";
		if (name.isEmpty() || name.length() > 80) throw refusal(
			"Translated server name is empty or exceeds 80 characters.");
		long legacyExperience = integer(values, "experienceRate", 1L, 100L, 1L);
		long combatExperience = integer(values, "combatExperienceRate", 1L, 100L,
			legacyExperience);
		long skillingExperience = integer(values, "skillingExperienceRate", 1L, 100L,
			legacyExperience);
		long port = integer(values, "gamePort", 1L, 65535L, 43594L);
		long websocketPort = integer(values, "websocketPort", 1L, 65535L, 43494L);
		if (port == websocketPort) throw refusal(
			"Translated game and websocket ports must be distinct.");
		String bind = values.containsKey("bindAddress")
			? values.get("bindAddress") : "127.0.0.1";
		if (bind.isEmpty() || bind.length() > 253 || !bind.matches("[A-Za-z0-9.:%_-]+"))
			throw refusal("Translated server bind address is malformed or unbounded.");
		Map<String,Object> typed = new LinkedHashMap<String,Object>();
		typed.put("schemaVersion", Long.valueOf(1));
		typed.put("manifestType", "world-builder-current-base-configuration");
		typed.put("sourceRelativePath", target.relativize(selected).toString().replace('\\', '/'));
		typed.put("precedence", productionLayout
			? (Files.exists(local, LinkOption.NOFOLLOW_LINKS)
				? "connections-first-then-local-profile"
				: "connections-first-then-named-profile")
			: (Files.exists(local, LinkOption.NOFOLLOW_LINKS)
				? "local-replaces-named-profile" : "named-profile"));
		typed.put("duplicatePolicy", "first-value-wins");
		typed.put("serverName", name);
		typed.put("experienceRate", Long.valueOf(legacyExperience));
		typed.put("combatExperienceRate", Long.valueOf(combatExperience));
		typed.put("skillingExperienceRate", Long.valueOf(skillingExperience));
		typed.put("bindAddress", bind); typed.put("gamePort", Long.valueOf(port));
		typed.put("websocketPort", Long.valueOf(websocketPort));
		Map<String,Object> database = databaseMigration(values);
		typed.put("databaseMigration", database);
		List<Object> secrets = new ArrayList<Object>();
		if ("mariadb".equals(stringUnchecked(database, "engine"))) {
			secrets.add(database.get("userEnvironmentName"));
			secrets.add(database.get("passwordEnvironmentName"));
		}
		typed.put("externalSecretReferences", secrets);
		Collections.sort(untranslated, new java.util.Comparator<Object>() {
			@Override public int compare(Object left, Object right) {
				return ((String)left).compareTo((String)right);
			}
		});
		typed.put("sourceInventory", sourceInventory);
		typed.put("untranslatedKeys", untranslated);
		List<Object> configurationBlockers = new ArrayList<Object>();
		if (!untranslated.isEmpty())
			configurationBlockers.add("untranslated-legacy-configuration-keys");
		if (ambiguousColonValue)
			configurationBlockers.add("ambiguous-three-part-colon-values");
		if (nullOverwriteValue)
			configurationBlockers.add("legacy-null-overwrite-semantics");
		typed.put("configurationBlockers", configurationBlockers);
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
		if ("combat_exp_rate".equals(key)) return "combatExperienceRate";
		if ("skilling_exp_rate".equals(key)) return "skillingExperienceRate";
		if (Arrays.asList("bind_address", "server_bind_address", "bindAddress").contains(key)) return "bindAddress";
		if (Arrays.asList("port", "game_port", "server_port", "gamePort").contains(key)) return "gamePort";
		if (Arrays.asList("ws_server_port", "websocket_port", "websocketPort").contains(key)) return "websocketPort";
		if (Arrays.asList("db_engine", "database_engine", "db_type").contains(key)) return "databaseEngine";
		if (Arrays.asList("db_host", "database_host").contains(key)) return "databaseHost";
		if (Arrays.asList("db_port", "database_port").contains(key)) return "databasePort";
		if (Arrays.asList("db_name", "database_name", "source_schema").contains(key)) return "databaseSourceSchema";
		if (Arrays.asList("db_stage_name", "stage_schema").contains(key)) return "databaseStageSchema";
		if (Arrays.asList("db_user_env", "database_user_env").contains(key)) return "databaseUserEnvironment";
		if (Arrays.asList("db_password_env", "database_password_env").contains(key)) return "databasePasswordEnvironment";
		return null;
	}

	private static Map<String,Object> databaseMigration(Map<String,String> values)
		throws WorldBuilderContractException {
		if (!values.containsKey("databaseEngine")
			|| "sqlite".equals(values.get("databaseEngine"))) return sqliteDatabaseMigration();
		if (!"mariadb".equals(values.get("databaseEngine"))) throw refusal(
			"Only sqlite or the closed MariaDB migration pathway is supported.");
		for (String key : Arrays.asList("databaseHost", "databasePort",
			"databaseSourceSchema", "databaseStageSchema", "databaseUserEnvironment",
			"databasePasswordEnvironment")) if (!values.containsKey(key)) throw refusal(
			"MariaDB configuration omits required credential-reference field: " + key);
		if (!"127.0.0.1".equals(values.get("databaseHost"))) throw refusal(
			"MariaDB migration endpoint must be literal IPv4 loopback 127.0.0.1.");
		long port = integer(values, "databasePort", 1L, 65535L, 0L);
		String source = safeDatabaseName(values.get("databaseSourceSchema"), "source schema");
		String stage = safeDatabaseName(values.get("databaseStageSchema"), "stage schema");
		if (source.equals(stage)) throw refusal(
			"MariaDB staged schema must differ from the read-only source schema.");
		String user = safeEnvironmentName(values.get("databaseUserEnvironment"));
		String password = safeEnvironmentName(values.get("databasePasswordEnvironment"));
		if (user.equals(password)) throw refusal(
			"MariaDB user and password must use distinct environment references.");
		Map<String,Object> result = new LinkedHashMap<String,Object>();
		result.put("engine", "mariadb"); result.put("host", "127.0.0.1");
		result.put("port", Long.valueOf(port)); result.put("sourceSchema", source);
		result.put("stageSchema", stage); result.put("userEnvironmentName", user);
		result.put("passwordEnvironmentName", password); return result;
	}

	private static Map<String,Object> sqliteDatabaseMigration() {
		Map<String,Object> result = new LinkedHashMap<String,Object>();
		result.put("engine", "sqlite"); result.put("host", "");
		result.put("port", Long.valueOf(0)); result.put("sourceSchema", "");
		result.put("stageSchema", ""); result.put("userEnvironmentName", "");
		result.put("passwordEnvironmentName", ""); return result;
	}

	private static String safeDatabaseName(String value, String label)
		throws WorldBuilderContractException {
		if (value == null || !value.matches("[A-Za-z_][A-Za-z0-9_]{0,63}"))
			throw refusal("MariaDB " + label + " is not a bounded SQL identifier.");
		return value;
	}

	private static String safeEnvironmentName(String value)
		throws WorldBuilderContractException {
		if (value == null || !value.matches("[A-Z][A-Z0-9_]{0,127}")) throw refusal(
			"MariaDB credentials must be uppercase environment-name references.");
		return value;
	}

	private static String stringUnchecked(Map<String,Object> value, String key) {
		return (String)value.get(key);
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
			"3ab420b175819030d487ef6bd47959c5818684b11999ba9fd8bd21d29ce7b589", "map",
			delta(23040L, "46e2096b907947368d310929303a04005b39c4a278e3a7de2225c355b4522694", "T2B", "canonical-map", "Reviewed single-sector packed terrain converts with exact reverse parity.")));
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
		rules.add(rule("legacy-sqlite-state",
			WorldBuilderPreservationStagedMigrator.SQLITE_SOURCE, false, 0L, "", "database",
			delta(290816L, "301063f734b269573782995b1aa8ea32edba569dd95276bc9a35db680692f623", "T2B", "preserve-state", "Reviewed closed invented Preservation SQLite schema migrates through the provider row into Current Base state.")));
		rules.add(rule("legacy-plugin-source", "server/plugins/Welcome.java", true, 63L,
			"1b18e7c7c80198d069c8001944526a7d6411a45dbd36ce944530246d6f68b66f", "plugin-source"));
		root.put("evidenceRules", rules); root.put("adapterManifestHash", ZERO_HASH);
		return root;
	}

	@SafeVarargs
	private static Map<String,Object> rule(String role, String path, boolean required,
		long size, String hash, String kind, Map<String,Object>... deltas) {
		Map<String,Object> result = new LinkedHashMap<String,Object>();
		result.put("role", role); result.put("relativePath", path);
		result.put("required", Boolean.valueOf(required)); result.put("baselineSize", Long.valueOf(size));
		result.put("baselineSha256", hash); result.put("evidenceKind", kind);
		List<Object> recognized = new ArrayList<Object>();
		for (Map<String,Object> delta : deltas) recognized.add(delta);
		result.put("recognizedDeltas", recognized);
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
