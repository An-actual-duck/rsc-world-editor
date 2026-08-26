package com.openrsc.worldbuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Strict read-only boundary for the adaptive World Builder contract family.
 *
 * This class deliberately validates documents without resolving target paths,
 * creating project state, or invoking any later discovery/conversion/runtime
 * behavior.
 */
final class WorldBuilderAdaptiveContracts {
	private WorldBuilderAdaptiveContracts() {
	}

	enum Kind {
		TARGET_CAPABILITY("target-capability", 1,
			"world-builder-target-capability", "target-capability-v1.schema.json"),
		DISCOVERY_REPORT("discovery-report", 2,
			"world-builder-discovery-report", "discovery-report-v2.schema.json"),
		PROJECT_MANIFEST("project-manifest", 2,
			"world-builder-project", "project-manifest-v2.schema.json"),
		PROJECT_REGISTRY("project-registry", 1,
			"world-builder-project-registry", "project-registry-v1.schema.json"),
		ACTIVE_PROJECT("active-project", 1,
			"world-builder-active-project", "active-project-v1.schema.json"),
		SOURCE_SNAPSHOT("source-snapshot", 2,
			"world-builder-source-snapshot", "source-snapshot-v2.schema.json"),
		CONVERSION_PLAN("conversion-plan", 1,
			"world-builder-conversion-plan", "conversion-plan-v1.schema.json"),
		CONVERSION_REPORT("conversion-report", 1,
			"world-builder-conversion-report", "conversion-report-v1.schema.json"),
		DISCOVERY_RECONCILIATION("discovery-reconciliation", 1,
			"world-builder-discovery-reconciliation",
			"discovery-reconciliation-v1.schema.json"),
		CONTENT_RECONCILIATION("content-reconciliation", 1,
			"world-builder-content-reconciliation",
			"content-reconciliation-v1.schema.json"),
		ADAPTIVE_EXPORT("adaptive-export", 2,
			"world-builder-adaptive-export", "export-manifest-v2.schema.json"),
		MUTATION_PLAN("mutation-plan", 1,
			"world-builder-target-mutation-plan", "target-mutation-plan-v1.schema.json"),
		ADAPTIVE_RECEIPT("adaptive-receipt", 3,
			"world-builder-adaptive-import-receipt", "import-receipt-v3.schema.json");

		final String externalName;
		final long schemaVersion;
		final String manifestType;
		final String schemaFileName;

		Kind(String externalName, long schemaVersion, String manifestType,
			String schemaFileName) {
			this.externalName = externalName;
			this.schemaVersion = schemaVersion;
			this.manifestType = manifestType;
			this.schemaFileName = schemaFileName;
		}

		static Kind named(String value) throws WorldBuilderContractException {
			for (Kind kind : values()) {
				if (kind.externalName.equals(value)) return kind;
			}
			throw new WorldBuilderContractException(
				WorldBuilderErrorCodes.CONTRACT_IDENTITY_INVALID,
				"select-contract", "Unknown adaptive contract kind: " + value);
		}
	}

	static Document read(Kind kind, Path path)
		throws IOException, WorldBuilderContractException {
		Map<String,Object> root;
		try {
			root = WorldBuilderJsonDocuments.readObject(path);
		} catch (WorldBuilderDiscoveryException malformed) {
			String message = malformed.getMessage();
			String code = message != null && (message.contains("invalid size")
				|| message.contains("complexity limit"))
				? WorldBuilderErrorCodes.CONTRACT_LIMIT_EXCEEDED
				: message != null && message.contains("missing or unsafe")
					? WorldBuilderErrorCodes.UNSAFE_PATH
					: WorldBuilderErrorCodes.MALFORMED_JSON;
			throw new WorldBuilderContractException(code,
				"validate-" + kind.externalName, "", false,
				"Adaptive contract JSON is malformed or unsafe: " + malformed.getMessage(),
				"Supply one bounded UTF-8 JSON object with unique keys.", malformed);
		}
		return validateParsed(kind, root);
	}

	/** Validate an already parsed/generated document without touching the filesystem. */
	static Document validateParsed(Kind kind, Map<String,Object> root)
		throws WorldBuilderContractException {
		validateIdentity(kind, root);
		validate(kind, root);
		String canonical;
		try {
			canonical = WorldBuilderJsonDocuments.canonical(root);
		} catch (IllegalArgumentException malformedUnicode) {
			throw new WorldBuilderContractException(WorldBuilderErrorCodes.MALFORMED_JSON,
				"validate-" + kind.externalName, "", false,
				"Adaptive contract contains invalid Unicode.",
				"Use paired Unicode scalar values in every JSON string.", malformedUnicode);
		}
		return new Document(kind, canonical,
			WorldBuilderHashes.sha256(canonical.getBytes(StandardCharsets.UTF_8)));
	}

	private static void validateIdentity(Kind expected, Map<String,Object> root)
		throws WorldBuilderContractException {
		String operation = "validate-" + expected.externalName;
		Object rawVersion = root.get("schemaVersion");
		Object rawType = root.get("manifestType");
		if (!(rawVersion instanceof Long) || !(rawType instanceof String)) {
			throw new WorldBuilderContractException(
				WorldBuilderErrorCodes.CONTRACT_IDENTITY_INVALID, operation,
				"Adaptive contract identity fields are missing or have the wrong type.");
		}
		if (!expected.manifestType.equals(rawType)) {
			throw new WorldBuilderContractException(
				WorldBuilderErrorCodes.CONTRACT_IDENTITY_INVALID, operation,
				"Adaptive contract manifest type is not " + expected.manifestType + ".");
		}
		if (((Long)rawVersion).longValue() != expected.schemaVersion) {
			throw new WorldBuilderContractException(
				WorldBuilderErrorCodes.UNSUPPORTED_CONTRACT_VERSION, operation,
				"Adaptive contract schema version is not exactly "
					+ expected.schemaVersion + ".");
		}
	}

	private static void validate(Kind kind, Map<String,Object> root)
		throws WorldBuilderContractException {
		switch (kind) {
			case TARGET_CAPABILITY: validateCapability(root); return;
			case DISCOVERY_REPORT: validateDiscovery(root); return;
			case PROJECT_MANIFEST: validateProject(root); return;
			case PROJECT_REGISTRY: validateRegistry(root); return;
			case ACTIVE_PROJECT: validateActiveProject(root); return;
			case SOURCE_SNAPSHOT: validateSource(root); return;
			case CONVERSION_PLAN: validateConversionPlan(root); return;
			case CONVERSION_REPORT: validateConversionReport(root); return;
			case DISCOVERY_RECONCILIATION:
				validateDiscoveryReconciliation(root); return;
			case CONTENT_RECONCILIATION:
				validateContentReconciliation(root); return;
			case ADAPTIVE_EXPORT: validateExport(root); return;
			case MUTATION_PLAN: validateMutationPlan(root); return;
			case ADAPTIVE_RECEIPT: validateReceipt(root); return;
			default: throw new AssertionError(kind);
		}
	}

	private static void validateCapability(Map<String,Object> root)
		throws WorldBuilderContractException {
		String op = "validate-target-capability";
		exact(root, op, "schemaVersion", "manifestType", "adapterId", "capabilityId",
			"server", "client", "definitions", "map", "discovery", "authoring", "install");
		identifier(root, "adapterId", op);
		identifier(root, "capabilityId", op);

		Map<String,Object> server = object(root.get("server"), op, "server");
		exact(server, op, "buildId", "loaderId");
		identifier(server, "buildId", op); identifier(server, "loaderId", op);
		Map<String,Object> client = object(root.get("client"), op, "client");
		exact(client, op, "buildId", "protocolId", "loaderId");
		identifier(client, "buildId", op); identifier(client, "protocolId", op);
		identifier(client, "loaderId", op);
		Map<String,Object> definitions = object(root.get("definitions"), op, "definitions");
		exact(definitions, op, "catalogId", "catalogSha256");
		identifier(definitions, "catalogId", op); hash(definitions, "catalogSha256", op);
		Map<String,Object> map = object(root.get("map"), op, "map");
		exact(map, op, "formatId", "packageSchemaId", "encodingVersions");
		identifier(map, "formatId", op); identifier(map, "packageSchemaId", op);
		ascendingPositiveIntegers(map.get("encodingVersions"), op, "encodingVersions", 1, 32);

		Map<String,Object> discovery = object(root.get("discovery"), op, "discovery");
		exact(discovery, op, "configurationRoles", "sourceRepresentations", "sourceRoles");
		identifierList(discovery.get("configurationRoles"), op, "configurationRoles", 1, 64);
		enumList(discovery.get("sourceRepresentations"), op, "sourceRepresentations",
			1, 2, "layered", "packed");
		identifierList(discovery.get("sourceRoles"), op, "sourceRoles", 1, 256);

		Map<String,Object> authoring = object(root.get("authoring"), op, "authoring");
		exact(authoring, op, "editExistingLevels", "createLevels", "placementFamilies");
		bool(authoring, "editExistingLevels", op); bool(authoring, "createLevels", op);
		enumList(authoring.get("placementFamilies"), op, "placementFamilies", 0, 4,
			"boundary", "ground-item", "npc", "scenery");

		Map<String,Object> install = object(root.get("install"), op, "install");
		exact(install, op, "enabled", "serverRoles", "clientRoles", "configurationRoles",
			"mutationProfileId", "offlineEvidence");
		boolean enabled = bool(install, "enabled", op);
		List<String> serverRoles = identifierList(install.get("serverRoles"), op,
			"serverRoles", 0, 256);
		List<String> clientRoles = identifierList(install.get("clientRoles"), op,
			"clientRoles", 0, 256);
		List<String> configurationRoles = identifierList(install.get("configurationRoles"), op,
			"configurationRoles", 0, 64);
		String profile = optionalIdentifier(install, "mutationProfileId", op);
		List<String> offline = identifierList(install.get("offlineEvidence"), op,
			"offlineEvidence", 0, 32);
		if (enabled) {
			if (serverRoles.isEmpty() || clientRoles.isEmpty()
				|| configurationRoles.isEmpty() || profile.isEmpty() || offline.isEmpty()) {
				invalid(op, "Enabled install capability is incomplete.");
			}
		} else if (!serverRoles.isEmpty() || !clientRoles.isEmpty()
			|| !configurationRoles.isEmpty() || !profile.isEmpty() || !offline.isEmpty()) {
			invalid(op, "Disabled install capability must not declare mutation authority.");
		}
	}

	private static void validateDiscovery(Map<String,Object> root)
		throws WorldBuilderContractException {
		String op = "validate-discovery-report";
		exact(root, op, "schemaVersion", "manifestType", "toolVersion", "status",
			"targetRootDisplay", "adaptersConsidered", "descriptor",
			"configurationCandidates", "selectedConfiguration", "representation",
			"capability", "files", "checks", "operations", "issues",
			"discoveryFingerprintSha256");
		identifier(root, "toolVersion", op);
		String status = enumeration(root, "status", op, "blocked", "compatible", "standalone");
		text(root, "targetRootDisplay", op, 0, WorldBuilderContractLimits.MAX_DETAIL_CHARS);
		List<String> adapters = identifierList(
			root.get("adaptersConsidered"), op, "adaptersConsidered", 0,
			WorldBuilderContractLimits.MAX_ADAPTERS);
		StateReference descriptor = stateReference(root.get("descriptor"), op, "descriptor", false);
		List<?> candidates = array(root.get("configurationCandidates"), op,
			"configurationCandidates", 0, WorldBuilderContractLimits.MAX_CONFIGURATION_CANDIDATES);
		String previousCandidate = null;
		Set<String> candidatePaths = new HashSet<String>();
		List<StateReference> candidateReferences = new ArrayList<StateReference>();
		for (Object raw : candidates) {
			Map<String,Object> candidate = object(raw, op, "configurationCandidate");
			exact(candidate, op, "role", "relativePath", "sha256");
			String role = identifier(candidate, "role", op);
			String path = relative(candidate, "relativePath", op);
			String candidateHash = hash(candidate, "sha256", op);
			String key = path + "\u0000" + role;
			if (previousCandidate != null && previousCandidate.compareTo(key) >= 0
				|| !candidatePaths.add(WorldBuilderPortablePath.collisionKey(path, op))) {
				invalid(op, "Configuration candidates are duplicated or not canonically ordered.");
			}
			previousCandidate = key;
			candidateReferences.add(
				new StateReference(true, role, path, candidateHash));
		}
		StateReference selected = stateReference(root.get("selectedConfiguration"),
			op, "selectedConfiguration", true);
		String representation = enumeration(root, "representation", op,
			"layered", "none", "packed", "unknown");
		CapabilityReference capability = capabilityReference(root.get("capability"), op);
		int minimumFiles = "compatible".equals(status) ? 1 : 0;
		List<WorldBuilderBoundedInventory.Record> files =
			WorldBuilderBoundedInventory.read(root.get("files"), op, minimumFiles, false);
		int failedChecks = validateChecks(root.get("checks"), op);
		Operations operations = operations(root.get("operations"), op);
		List<Issue> issues = issues(root.get("issues"), op);
		hash(root, "discoveryFingerprintSha256", op);
		boolean blockers = hasBlocker(issues);
		boolean mutationClaimed = hasMutationClaim(issues);
		boolean presentSourceEvidence = false;
		for (WorldBuilderBoundedInventory.Record file : files) {
			if (file.present) presentSourceEvidence = true;
		}
		if (failedChecks > 0 && !"blocked".equals(status)) {
			invalid(op, "A failed discovery check requires blocked status.");
		}
		if (mutationClaimed) {
			invalid(op, "Read-only discovery issues cannot claim target mutation.");
		}
		if (capability.resolved && !adapters.contains(capability.adapterId)) {
			invalid(op, "Resolved discovery adapter was not among the adapters considered.");
		}
		if ("standalone".equals(status)) {
			if (descriptor.present || selected.present || capability.resolved
				|| !candidates.isEmpty() || !files.isEmpty() || !"none".equals(representation)
				|| !operations.discoveryOnly() || blockers) {
				invalid(op, "Standalone discovery report contains target state or target mutation authority.");
			}
		} else if ("compatible".equals(status)) {
			boolean selectedCandidate = false;
			for (StateReference candidate : candidateReferences) {
				if (candidate.role.equals(selected.role)
					&& candidate.relativePath.equals(selected.relativePath)
					&& candidate.sha256.equals(selected.sha256)) {
					selectedCandidate = true;
				}
			}
			if (!selected.present || !capability.resolved
				|| !("packed".equals(representation) || "layered".equals(representation))
				|| blockers || !selectedCandidate || !presentSourceEvidence
				|| !operations.discoveryOnly()) {
				invalid(op, "Compatible discovery report is missing proven target evidence.");
			}
		} else if (!blockers || operations.any()) {
			invalid(op, "Blocked discovery report must contain a blocker and no supported operation.");
		}
	}

	private static void validateProject(Map<String,Object> root)
		throws WorldBuilderContractException {
		String op = "validate-project-manifest";
		exact(root, op, "schemaVersion", "manifestType", "projectId", "displayName",
			"origin", "state", "creation", "target", "standalone", "paths",
			"fingerprints", "operations", "projectFingerprintSha256");
		String projectId = uuid(root, "projectId", op, false);
		text(root, "displayName", op, 1, WorldBuilderContractLimits.MAX_DISPLAY_CHARS);
		String origin = origin(root, op);
		String state = enumeration(root, "state", op, "ready-attached", "ready-detached",
			"ready-standalone", "recovery-required", "source-corrupt", "staging");
		Map<String,Object> creation = object(root.get("creation"), op, "creation");
		exact(creation, op, "toolVersion", "runtimeVersion");
		identifier(creation, "toolVersion", op); identifier(creation, "runtimeVersion", op);

		Map<String,Object> target = object(root.get("target"), op, "target");
		exact(target, op, "targetBacked", "locatorDisplay", "adapterId", "capabilityId",
			"selectedConfigurationRelativePath", "selectedConfigurationSha256",
			"targetFingerprintSha256", "importProfileId");
		boolean targetBacked = bool(target, "targetBacked", op);
		text(target, "locatorDisplay", op, 0, WorldBuilderContractLimits.MAX_DETAIL_CHARS);
		String adapter = optionalIdentifier(target, "adapterId", op);
		String capability = optionalIdentifier(target, "capabilityId", op);
		String selected = optionalRelative(target, "selectedConfigurationRelativePath", op);
		String selectedHash = optionalHash(target, "selectedConfigurationSha256", op);
		String targetFingerprint = optionalHash(target, "targetFingerprintSha256", op);
		String importProfile = optionalIdentifier(target, "importProfileId", op);

		Map<String,Object> standalone = object(root.get("standalone"), op, "standalone");
		exact(standalone, op, "generatorId", "catalogId", "runtimeId");
		String generator = optionalIdentifier(standalone, "generatorId", op);
		String catalog = optionalIdentifier(standalone, "catalogId", op);
		String standaloneRuntime = optionalIdentifier(standalone, "runtimeId", op);

		Map<String,Object> paths = object(root.get("paths"), op, "paths");
		exact(paths, op, "sourceSnapshotRelativePath", "layeredBaselineRelativePath",
			"workingRuntimeRelativePath", "workingPackageRelativePath",
			"conversionPlanRelativePath", "conversionReportRelativePath",
			"exportsRelativePath", "backupsRelativePath", "receiptsRelativePath",
			"diagnosticsRelativePath", "logsRelativePath", "runRelativePath");
		String sourcePath = relative(paths, "sourceSnapshotRelativePath", op);
		String baselinePath = relative(paths, "layeredBaselineRelativePath", op);
		String runtimePath = relative(paths, "workingRuntimeRelativePath", op);
		String workingPath = relative(paths, "workingPackageRelativePath", op);
		String conversionPlan = optionalRelative(paths, "conversionPlanRelativePath", op);
		String conversionReport = optionalRelative(paths, "conversionReportRelativePath", op);
		String exportsPath = relative(paths, "exportsRelativePath", op);
		String backupsPath = relative(paths, "backupsRelativePath", op);
		String receiptsPath = relative(paths, "receiptsRelativePath", op);
		String diagnosticsPath = relative(paths, "diagnosticsRelativePath", op);
		String logsPath = relative(paths, "logsRelativePath", op);
		String runPath = relative(paths, "runRelativePath", op);
		if (!"source/snapshot-manifest.json".equals(sourcePath)
			|| !"source/layered-baseline/package".equals(baselinePath)
			|| !"working/runtime".equals(runtimePath)
			|| !"working/layered-world/package".equals(workingPath)
			|| !"exports".equals(exportsPath) || !"backups".equals(backupsPath)
			|| !"receipts".equals(receiptsPath)
			|| !"diagnostics".equals(diagnosticsPath) || !"logs".equals(logsPath)
			|| !"run".equals(runPath)) {
			invalid(op, "Project contract uses a noncanonical internal path.");
		}

		Map<String,Object> fingerprints = object(root.get("fingerprints"), op, "fingerprints");
		exact(fingerprints, op, "sourceSha256", "layeredBaselineSha256",
			"definitionsSha256", "runtimeSha256", "conversionSha256", "workingSha256");
		hash(fingerprints, "sourceSha256", op); hash(fingerprints, "layeredBaselineSha256", op);
		hash(fingerprints, "definitionsSha256", op); hash(fingerprints, "runtimeSha256", op);
		String conversionFingerprint = optionalHash(fingerprints, "conversionSha256", op);
		hash(fingerprints, "workingSha256", op);
		Operations operations = operations(root.get("operations"), op);
		hash(root, "projectFingerprintSha256", op);
		validateOriginState(origin, state, op);
		validateProjectOperations(state, operations, op);

		boolean standaloneOrigin = "standalone-empty".equals(origin);
		if (standaloneOrigin) {
			if (targetBacked || !adapter.isEmpty() || !capability.isEmpty() || !selected.isEmpty()
				|| !selectedHash.isEmpty()
				|| !targetFingerprint.isEmpty() || !importProfile.isEmpty()
				|| generator.isEmpty() || catalog.isEmpty() || standaloneRuntime.isEmpty()
				|| !conversionPlan.isEmpty() || !conversionReport.isEmpty()
				|| !conversionFingerprint.isEmpty() || operations.importTarget || operations.undo
				|| !("ready-standalone".equals(state) || "staging".equals(state)
					|| "source-corrupt".equals(state))) {
				invalid(op, "Standalone project contains target or conversion lineage.");
			}
		} else {
			if (!targetBacked || adapter.isEmpty() || capability.isEmpty() || selected.isEmpty()
				|| selectedHash.isEmpty()
				|| targetFingerprint.isEmpty() || importProfile.isEmpty()
				|| !generator.isEmpty() || !catalog.isEmpty() || !standaloneRuntime.isEmpty()
				|| "ready-standalone".equals(state)) {
				invalid(op, "Target-backed project lineage is incomplete.");
			}
			boolean packed = "target-packed".equals(origin);
			if (packed != (!conversionPlan.isEmpty() && !conversionReport.isEmpty()
				&& !conversionFingerprint.isEmpty())) {
				invalid(op, "Project conversion lineage does not match its origin.");
			}
			if (packed && (!"source/conversion/plan.json".equals(conversionPlan)
				|| !"source/conversion/report.json".equals(conversionReport))) {
				invalid(op, "Packed project conversion paths are noncanonical.");
			}
		}
		if (!projectId.equals(projectId.toLowerCase(java.util.Locale.ROOT))) {
			invalid(op, "Project UUID must use canonical lowercase form.");
		}
	}

	private static void validateRegistry(Map<String,Object> root)
		throws WorldBuilderContractException {
		String op = "validate-project-registry";
		exact(root, op, "schemaVersion", "manifestType", "projects",
			"registryFingerprintSha256");
		List<?> records = array(root.get("projects"), op, "projects", 0,
			WorldBuilderContractLimits.MAX_PROJECTS);
		String previous = null;
		Set<String> paths = new HashSet<String>();
		for (Object raw : records) {
			Map<String,Object> record = object(raw, op, "project");
			exact(record, op, "projectId", "manifestRelativePath", "manifestSha256",
				"displayName", "origin", "state");
			String projectId = uuid(record, "projectId", op, false);
			String path = relative(record, "manifestRelativePath", op);
			if (!("projects/" + projectId + "/project.json").equals(path)
				|| !paths.add(WorldBuilderPortablePath.collisionKey(path, op))) {
				invalid(op, "Project registry contains an unsafe or duplicate manifest path.");
			}
			hash(record, "manifestSha256", op);
			text(record, "displayName", op, 1, WorldBuilderContractLimits.MAX_DISPLAY_CHARS);
			String origin = origin(record, op);
			String state = enumeration(record, "state", op, "ready-attached", "ready-detached",
				"ready-standalone", "recovery-required", "source-corrupt", "staging");
			validateOriginState(origin, state, op);
			if (previous != null && previous.compareTo(projectId) >= 0) {
				invalid(op, "Project registry is not in canonical UUID order.");
			}
			previous = projectId;
		}
		hash(root, "registryFingerprintSha256", op);
	}

	private static void validateActiveProject(Map<String,Object> root)
		throws WorldBuilderContractException {
		String op = "validate-active-project";
		exact(root, op, "schemaVersion", "manifestType", "projectId",
			"manifestRelativePath", "manifestSha256");
		String id = uuid(root, "projectId", op, true);
		String path = optionalRelative(root, "manifestRelativePath", op);
		String hash = optionalHash(root, "manifestSha256", op);
		if (id.isEmpty() != path.isEmpty() || id.isEmpty() != hash.isEmpty()
			|| (!id.isEmpty() && !("projects/" + id + "/project.json").equals(path))) {
			invalid(op, "Active project pointer is incomplete or noncanonical.");
		}
	}

	private static void validateSource(Map<String,Object> root)
		throws WorldBuilderContractException {
		String op = "validate-source-snapshot";
		exact(root, op, "schemaVersion", "manifestType", "projectId", "origin",
			"adapterId", "capabilityId", "selectedConfiguration", "discoveryReport",
			"originDescriptor", "originalFiles", "definitionRuntimeFiles",
			"conversionEvidenceFiles", "layeredBaselineFiles", "sourceFingerprintSha256");
		uuid(root, "projectId", op, false);
		String origin = origin(root, op);
		String adapter = optionalIdentifier(root, "adapterId", op);
		String capability = optionalIdentifier(root, "capabilityId", op);
		StateReference selected = stateReference(root.get("selectedConfiguration"),
			op, "selectedConfiguration", true);
		StateReference report = stateReference(root.get("discoveryReport"),
			op, "discoveryReport", false);
		StateReference descriptor = stateReference(root.get("originDescriptor"),
			op, "originDescriptor", false);
		if (!report.present || !descriptor.present
			|| !"discovery/report.json".equals(report.relativePath)) {
			invalid(op, "Source snapshot report or origin descriptor is missing.");
		}
		List<WorldBuilderBoundedInventory.Record> original =
			WorldBuilderBoundedInventory.read(root.get("originalFiles"), op, 1, false);
		List<WorldBuilderBoundedInventory.Record> definitions =
			WorldBuilderBoundedInventory.read(root.get("definitionRuntimeFiles"), op, 1, true);
		int conversionMinimum = "target-packed".equals(origin) ? 2 : 0;
		List<WorldBuilderBoundedInventory.Record> conversion =
			WorldBuilderBoundedInventory.read(root.get("conversionEvidenceFiles"), op,
				conversionMinimum, true);
		List<WorldBuilderBoundedInventory.Record> baseline =
			WorldBuilderBoundedInventory.read(root.get("layeredBaselineFiles"), op, 1, true);
		uniqueAcrossInventories(op, original, definitions, conversion, baseline);
		hash(root, "sourceFingerprintSha256", op);
		if (!matchesEvidence(original, descriptor)) {
			invalid(op, "Origin descriptor does not match immutable original-file evidence.");
		}
		if ("standalone-empty".equals(origin)) {
			if (!adapter.isEmpty() || !capability.isEmpty() || selected.present
				|| !conversion.isEmpty()
				|| !(WorldBuilderEmptyWorldGenerator.DESCRIPTOR_PATH.equals(
					descriptor.relativePath)
					|| WorldBuilderEmptyWorldGenerator.DEVELOPMENT_DESCRIPTOR_PATH.equals(
						descriptor.relativePath))) {
				invalid(op, "Standalone source snapshot contains target lineage.");
			}
		} else if (adapter.isEmpty() || capability.isEmpty() || !selected.present) {
			invalid(op, "Target-backed source snapshot lineage is incomplete.");
		} else if (!matchesEvidence(original, selected)) {
			invalid(op, "Selected configuration does not match immutable original-file evidence.");
		} else if (!"target-packed".equals(origin) && !conversion.isEmpty()) {
			invalid(op, "Only packed origins may contain conversion evidence.");
		}
		if ("target-packed".equals(origin)) {
			if (conversion.size() != 2
				|| !hasExactInventoryRecord(conversion, "conversion-plan",
					"source/conversion/plan.json")
				|| !hasExactInventoryRecord(conversion, "conversion-report",
					"source/conversion/report.json")) {
				invalid(op, "Packed source snapshot lacks canonical conversion plan/report evidence.");
			}
		}
		for (WorldBuilderBoundedInventory.Record record : baseline) {
			if (!record.relativePath.startsWith("source/layered-baseline/package/")) {
				invalid(op, "Layered baseline file is outside its immutable directory.");
			}
		}
	}

	private static void validateConversionPlan(Map<String,Object> root)
		throws WorldBuilderContractException {
		String op = "validate-conversion-plan";
		exact(root, op, "schemaVersion", "manifestType", "toolVersion", "adapterId",
			"conversionProfileId", "sourceFingerprintSha256", "definitionFingerprintSha256",
			"coordinateMappingId", "placementCompositionProfileId", "outputPackageSchemaId",
			"outputEncodingVersion", "inputs", "placementSourceOrder", "planFingerprintSha256");
		identifier(root, "toolVersion", op); identifier(root, "adapterId", op);
		identifier(root, "conversionProfileId", op); hash(root, "sourceFingerprintSha256", op);
		hash(root, "definitionFingerprintSha256", op); identifier(root, "coordinateMappingId", op);
		identifier(root, "placementCompositionProfileId", op);
		identifier(root, "outputPackageSchemaId", op);
		long outputEncodingVersion = integer(root, "outputEncodingVersion", op);
		if (outputEncodingVersion < 1L || outputEncodingVersion > Integer.MAX_VALUE) {
			invalid(op, "Conversion output encoding version is invalid.");
		}
		List<WorldBuilderBoundedInventory.Record> inputs =
			WorldBuilderBoundedInventory.read(root.get("inputs"), op, 1, true);
		List<String> order = identifierList(root.get("placementSourceOrder"), op,
			"placementSourceOrder", 0, 256, false);
		Set<String> roles = new HashSet<String>();
		for (WorldBuilderBoundedInventory.Record record : inputs) roles.add(record.role);
		if (!roles.containsAll(order)) {
			invalid(op, "Placement source order references a role outside the input inventory.");
		}
		hash(root, "planFingerprintSha256", op);
	}

	private static void validateConversionReport(Map<String,Object> root)
		throws WorldBuilderContractException {
		String op = "validate-conversion-report";
		exact(root, op, "schemaVersion", "manifestType", "planSha256",
			"outputFingerprintSha256", "terrain", "placements", "decisions",
			"validation", "issues", "blocked", "reportFingerprintSha256");
		hash(root, "planSha256", op);
		String output = optionalHash(root, "outputFingerprintSha256", op);
		Map<String,Object> terrain = object(root.get("terrain"), op, "terrain");
		exact(terrain, op, "entriesRead", "entriesWritten", "reverseMatched",
			"reverseMismatches");
		long read = boundedCount(terrain, "entriesRead", op);
		long written = boundedCount(terrain, "entriesWritten", op);
		long matched = boundedCount(terrain, "reverseMatched", op);
		long mismatches = boundedCount(terrain, "reverseMismatches", op);
		if (read != written || written != safeAdd(matched, mismatches, op,
			"Terrain reverse-result total")) {
			invalid(op, "Terrain conversion counts are inconsistent.");
		}
		validatePlacementSummaries(root.get("placements"), op);
		boolean blockedDecision = validateDecisions(root.get("decisions"), op);
		Map<String,Object> validation = object(root.get("validation"), op, "validation");
		exact(validation, op, "unknownCount", "lossCount", "approximationCount",
			"repairCount", "parityDeltaCount");
		long unknown = boundedCount(validation, "unknownCount", op);
		long loss = boundedCount(validation, "lossCount", op);
		long approximation = boundedCount(validation, "approximationCount", op);
		long repair = boundedCount(validation, "repairCount", op);
		long parity = boundedCount(validation, "parityDeltaCount", op);
		long blockers = 0L;
		for (long count : new long[] {
			unknown, loss, approximation, repair, parity, mismatches
		}) {
			blockers = safeAdd(blockers, count, op, "Conversion blocker total");
		}
		List<Issue> issues = issues(root.get("issues"), op);
		boolean blocked = bool(root, "blocked", op);
		if (blocked != (blockers > 0L || blockedDecision || hasBlocker(issues))
			|| blocked == !output.isEmpty()) {
			invalid(op, "Conversion blocked/output state is inconsistent with parity results.");
		}
		hash(root, "reportFingerprintSha256", op);
	}

	private static void validateDiscoveryReconciliation(Map<String,Object> root)
		throws WorldBuilderContractException {
		String op = "validate-discovery-reconciliation";
		exact(root, op, "schemaVersion", "manifestType", "adapterId",
			"representation", "sourceFingerprintSha256",
			"outputPackageFingerprintSha256", "families", "status", "issues",
			"reconciliationFingerprintSha256");
		identifier(root, "adapterId", op);
		if (!"packed".equals(enumeration(root, "representation", op, "packed"))) {
			invalid(op, "Discovery reconciliation representation is unsupported.");
		}
		hash(root, "sourceFingerprintSha256", op);
		hash(root, "outputPackageFingerprintSha256", op);
		List<?> families = array(root.get("families"), op, "families", 4, 4);
		String[] expectedFamilies = {"boundary", "ground-item", "npc", "scenery"};
		for (int index = 0; index < families.size(); index++) {
			Map<String,Object> family = object(
				families.get(index), op, "family reconciliation");
			exact(family, op, "family", "declaredBaseRecords",
				"declaredOverlayRecords", "declaredRemovalRecords",
				"embeddedMarkersRead", "embeddedPlacementsNormalized",
				"replacementsApplied", "removalsApplied", "effectiveRecords",
				"emittedRecords", "packageRecords", "definitionsResolved",
				"sourceRoles", "sourceProvenanceSha256",
				"effectiveIdentitySha256", "packageIdentitySha256", "status");
			String actualFamily = enumeration(family, "family", op,
				"boundary", "ground-item", "npc", "scenery");
			if (!expectedFamilies[index].equals(actualFamily)) {
				invalid(op, "Discovery reconciliation families are not canonical.");
			}
			long base = boundedCount(family, "declaredBaseRecords", op);
			long overlay = boundedCount(family, "declaredOverlayRecords", op);
			long removals = boundedCount(family, "declaredRemovalRecords", op);
			long markers = boundedCount(family, "embeddedMarkersRead", op);
			long embedded = boundedCount(
				family, "embeddedPlacementsNormalized", op);
			long replacementsApplied = boundedCount(
				family, "replacementsApplied", op);
			long removalsApplied = boundedCount(family, "removalsApplied", op);
			long effective = boundedCount(family, "effectiveRecords", op);
			long emitted = boundedCount(family, "emittedRecords", op);
			long packaged = boundedCount(family, "packageRecords", op);
			long definitions = boundedCount(family, "definitionsResolved", op);
			if (base + overlay + removals + markers + embedded
				+ replacementsApplied + removalsApplied < 0L) {
				invalid(op, "Discovery reconciliation counts overflow.");
			}
			if (!"scenery".equals(actualFamily) && (markers != 0L || embedded != 0L)) {
				invalid(op, "Only scenery may contain embedded terrain markers.");
			}
			if (effective != emitted || emitted != packaged
				|| packaged != definitions) {
				invalid(op, "Discovery reconciliation loses family records between stages.");
			}
			identifierList(family.get("sourceRoles"), op,
				"sourceRoles", 1, 256, true);
			hash(family, "sourceProvenanceSha256", op);
			String effectiveHash = hash(family, "effectiveIdentitySha256", op);
			String packageHash = hash(family, "packageIdentitySha256", op);
			if (!effectiveHash.equals(packageHash)
				|| !"matched".equals(enumeration(family, "status", op, "matched"))) {
				invalid(op, "Discovery reconciliation family parity is not matched.");
			}
		}
		if (!"matched".equals(enumeration(root, "status", op, "matched"))
			|| !issues(root.get("issues"), op).isEmpty()) {
			invalid(op, "A successful discovery reconciliation cannot contain issues.");
		}
		hash(root, "reconciliationFingerprintSha256", op);
	}

	private static void validateContentReconciliation(Map<String,Object> root)
		throws WorldBuilderContractException {
		String op = "validate-content-reconciliation";
		exact(root, op, "schemaVersion", "manifestType",
			"contentBundleFingerprintSha256", "outputPackageFingerprintSha256",
			"families", "modelArchive", "sceneryModels", "status", "issues",
			"reconciliationFingerprintSha256");
		hash(root, "contentBundleFingerprintSha256", op);
		hash(root, "outputPackageFingerprintSha256", op);
		List<?> families = array(root.get("families"), op, "families", 5, 5);
		String[] expected = {"floor", "boundary", "ground-item", "npc", "scenery"};
		for (int index = 0; index < families.size(); index++) {
			Map<String,Object> family = object(families.get(index), op, "family");
			exact(family, op, "family", "catalogDefinitionCount",
				"catalogDefinitionIdsSha256", "requiredPlacementDefinitionIds",
				"requiredPlacementDefinitionIdsSha256", "resolvedDefinitionCount",
				"resolvedDefinitionIdsSha256", "definitionRoles", "assets", "status");
			String name = enumeration(family, "family", op,
				"floor", "boundary", "ground-item", "npc", "scenery");
			if (!expected[index].equals(name)) invalid(op,
				"Content reconciliation families are not canonical.");
			long catalogCount = boundedCount(family, "catalogDefinitionCount", op);
			hash(family, "catalogDefinitionIdsSha256", op);
			List<?> ids = array(family.get("requiredPlacementDefinitionIds"), op,
				"requiredPlacementDefinitionIds", 0, 65536);
			long previous = -1L;
			for (Object raw : ids) {
				if (!(raw instanceof Long)) invalid(op, "Required definition ID is not an integer.");
				long id = ((Long)raw).longValue();
				if (id <= previous || id > 65535L) invalid(op,
					"Required definition IDs are duplicated, unordered, or out of range.");
				previous = id;
			}
			String requiredHash = hash(family,
				"requiredPlacementDefinitionIdsSha256", op);
			long resolved = boundedCount(family, "resolvedDefinitionCount", op);
			String resolvedHash = hash(family, "resolvedDefinitionIdsSha256", op);
			if (resolved != ids.size() || resolved > catalogCount
				|| !requiredHash.equals(resolvedHash)) invalid(op,
				"Content definition closure counts or fingerprints disagree.");
			List<String> definitionRoles = identifierList(family.get("definitionRoles"), op,
				"definitionRoles", 1, 4, true);
			List<String> expectedDefinitions;
			if ("floor".equals(name)) expectedDefinitions = Arrays.asList("definition.tile");
			else if ("boundary".equals(name)) expectedDefinitions =
				Arrays.asList("definition.boundary");
			else if ("ground-item".equals(name)) expectedDefinitions = Arrays.asList(
				"definition.item.base", "definition.item.custom",
				"definition.item.patch", "definition.item.world");
			else if ("npc".equals(name)) expectedDefinitions = Arrays.asList(
				"definition.npc.base", "definition.npc.custom",
				"definition.npc.patch", "definition.npc.world");
			else expectedDefinitions = Arrays.asList("definition.scenery");
			if (!expectedDefinitions.equals(definitionRoles)) invalid(op,
				"Content definition roles do not match their family.");
			List<?> assets = array(family.get("assets"), op, "assets", 1, 4);
			String previousRole = null;
			List<String> assetRoles = new ArrayList<String>();
			for (Object raw : assets) {
				Map<String,Object> asset = object(raw, op, "asset");
				exact(asset, op, "role", "size", "sha256");
				String role = identifier(asset, "role", op);
				if (previousRole != null && previousRole.compareTo(role) >= 0) {
					invalid(op, "Content asset roles are duplicated or unordered.");
				}
				previousRole = role;
				assetRoles.add(role);
				if (boundedCount(asset, "size", op) < 1L) invalid(op,
					"Content asset evidence cannot be empty.");
				hash(asset, "sha256", op);
			}
			List<String> expectedAssets;
			if ("floor".equals(name) || "boundary".equals(name)) {
				expectedAssets = Arrays.asList("asset.sprite.custom");
			} else if ("scenery".equals(name)) {
				expectedAssets = Arrays.asList(
					"asset.library", "asset.model", "asset.sprite.custom");
			} else {
				expectedAssets = Arrays.asList("asset.library", "asset.sprite.authentic",
					"asset.sprite.custom", "asset.spritepack");
			}
			if (!expectedAssets.equals(assetRoles)) invalid(op,
				"Content asset roles do not match their family.");
			if (!"matched".equals(enumeration(family, "status", op, "matched"))) {
				invalid(op, "Content family definition closure is not matched.");
			}
		}
		Map<String,Object> archive = object(root.get("modelArchive"), op, "modelArchive");
		exact(archive, op, "role", "size", "sha256", "indexStatus", "entryCount");
		if (!"asset.model".equals(identifier(archive, "role", op))) {
			invalid(op, "Content model archive role is invalid.");
		}
		if (boundedCount(archive, "size", op) < 1L) invalid(op,
			"Content model archive cannot be empty.");
		hash(archive, "sha256", op);
		String indexStatus = enumeration(archive, "indexStatus", op,
			"indexed", "compressed-unverified", "malformed");
		long entryCount = boundedCount(archive, "entryCount", op);
		if (entryCount > 8192L || (!"indexed".equals(indexStatus) && entryCount != 0L)) {
			invalid(op, "Content model archive index count is inconsistent.");
		}
		List<?> scenery = array(root.get("sceneryModels"), op, "sceneryModels", 0, 65536);
		long previousScenery = -1L;
		for (Object raw : scenery) {
			Map<String,Object> model = object(raw, op, "sceneryModel");
			exact(model, op, "sceneryId", "name", "modelName", "modelFileHash", "resolution");
			long id = integer(model, "sceneryId", op);
			if (id <= previousScenery || id > 65535L) invalid(op,
				"Scenery model records are duplicated, unordered, or out of range.");
			previousScenery = id;
			text(model, "name", op, 0, 256); text(model, "modelName", op, 0, 256);
			String fileHash = text(model, "modelFileHash", op, 0, 8);
			if (!fileHash.matches("([0-9a-f]{8})?")) invalid(op,
				"Scenery model filename hash is invalid.");
			enumeration(model, "resolution", op, "packaged-runtime", "project-archive",
				"generated-or-unspecified", "archive-unverified", "missing");
		}
		List<?> issues = array(root.get("issues"), op, "issues", 0, 4096);
		String previousIssue = null;
		for (Object raw : issues) {
			Map<String,Object> issue = object(raw, op, "issue");
			exact(issue, op, "code", "family", "definitionId", "assetRole",
				"message", "nextStep");
			String code = enumeration(issue, "code", op, "MODEL_ARCHIVE_UNVERIFIED",
				"PACKAGED_SCENERY_BASELINE_UNVERIFIED",
				"SCENERY_DEFINITION_DETAILS_UNVERIFIED", "SCENERY_MODEL_MISSING",
				"SCENERY_MODEL_UNSPECIFIED");
			if (!"scenery".equals(enumeration(issue, "family", op, "scenery"))) {
				invalid(op, "Content issue family is invalid.");
			}
			long id = integer(issue, "definitionId", op);
			if (id < -1L || id > 65535L) invalid(op, "Content issue definition ID is invalid.");
			String role = enumeration(issue, "assetRole", op,
				"asset.model", "definition.scenery");
			text(issue, "message", op, 1, 2048); text(issue, "nextStep", op, 1, 2048);
			String key = code + "\u0000" + String.format("%06d", id) + "\u0000" + role;
			if (previousIssue != null && previousIssue.compareTo(key) >= 0) invalid(op,
				"Content issues are duplicated or unordered.");
			previousIssue = key;
		}
		String status = enumeration(root, "status", op, "matched", "matched-with-warnings");
		if (issues.isEmpty() != "matched".equals(status)) invalid(op,
			"Content reconciliation warning status disagrees with its issues.");
		hash(root, "reconciliationFingerprintSha256", op);
	}

	private static void validateExport(Map<String,Object> root)
		throws WorldBuilderContractException {
		String op = "validate-adaptive-export";
		exact(root, op, "schemaVersion", "manifestType", "toolVersion", "projectId",
			"origin", "adapterId", "capabilityId", "installProfileId", "lineage",
			"packageManifestSha256", "packageFingerprintSha256", "files",
			"validationReports", "exportFingerprintSha256");
		identifier(root, "toolVersion", op); uuid(root, "projectId", op, false);
		String origin = origin(root, op);
		String adapter = optionalIdentifier(root, "adapterId", op);
		String capability = optionalIdentifier(root, "capabilityId", op);
		String profile = optionalIdentifier(root, "installProfileId", op);
		Map<String,Object> lineage = object(root.get("lineage"), op, "lineage");
		exact(lineage, op, "sourceSha256", "layeredBaselineSha256", "conversionSha256",
			"definitionsRuntimeSha256", "workingSha256");
		hash(lineage, "sourceSha256", op); hash(lineage, "layeredBaselineSha256", op);
		String conversion = optionalHash(lineage, "conversionSha256", op);
		hash(lineage, "definitionsRuntimeSha256", op); hash(lineage, "workingSha256", op);
		String packageManifest = hash(root, "packageManifestSha256", op);
		hash(root, "packageFingerprintSha256", op);
		List<WorldBuilderBoundedInventory.Record> files =
			WorldBuilderBoundedInventory.read(root.get("files"), op, 1, true);
		boolean manifestEvidence = false;
		for (WorldBuilderBoundedInventory.Record record : files) {
			if (!record.relativePath.startsWith("package/")) {
				invalid(op, "Adaptive export file is outside the complete package directory.");
			}
			if ("package-manifest".equals(record.role)
				|| "package/manifest.json".equals(record.relativePath)) {
				if (!"package-manifest".equals(record.role)
					|| !"package/manifest.json".equals(record.relativePath)
					|| !packageManifest.equals(record.sha256)) {
					invalid(op, "Adaptive export package-manifest evidence is contradictory.");
				}
				manifestEvidence = true;
			}
		}
		Set<String> validationRoles = validateHashRecords(
			root.get("validationReports"), op, "validationReports", 1, 256);
		if (!manifestEvidence || !validationRoles.contains("package-validation")) {
			invalid(op, "Adaptive export lacks package manifest or package validation evidence.");
		}
		hash(root, "exportFingerprintSha256", op);
		if ("standalone-empty".equals(origin)) {
			if (!adapter.isEmpty() || !capability.isEmpty() || !profile.isEmpty()
				|| !conversion.isEmpty()) {
				invalid(op, "Standalone export contains target or conversion lineage.");
			}
		} else if (adapter.isEmpty() || capability.isEmpty() || profile.isEmpty()
			|| ("target-packed".equals(origin) != !conversion.isEmpty())) {
			invalid(op, "Target-backed export lineage does not match its origin.");
		}
	}

	private static void validateMutationPlan(Map<String,Object> root)
		throws WorldBuilderContractException {
		String op = "validate-mutation-plan";
		exact(root, op, "schemaVersion", "manifestType", "transactionId", "projectId",
			"exportFingerprintSha256", "adapterId", "capabilityId", "mutationProfileId",
			"targetLineageSha256", "selectedConfiguration", "requirements",
			"actions", "createdDirectories", "configurationChanges", "backupRootRelativePath",
			"receiptRelativePath", "postWriteVerifications", "rollbackVerifications",
			"planFingerprintSha256");
		String transactionId = uuid(root, "transactionId", op, false);
		uuid(root, "projectId", op, false); hash(root, "exportFingerprintSha256", op);
		identifier(root, "adapterId", op); identifier(root, "capabilityId", op);
		identifier(root, "mutationProfileId", op); hash(root, "targetLineageSha256", op);
		StateReference selected = stateReference(
			root.get("selectedConfiguration"), op, "selectedConfiguration", true);
		if (!selected.present) invalid(op, "Mutation plan has no selected configuration.");
		Map<String,Object> requirements = object(root.get("requirements"), op, "requirements");
		exact(requirements, op, "loaderId", "protocolId", "definitionCatalogId",
			"clientBuildId", "offlineEvidence", "requiredFreeSpaceBytes");
		identifier(requirements, "loaderId", op); identifier(requirements, "protocolId", op);
		identifier(requirements, "definitionCatalogId", op);
		identifier(requirements, "clientBuildId", op);
		identifierList(requirements.get("offlineEvidence"), op, "offlineEvidence", 1, 32);
		long freeSpace = integer(requirements, "requiredFreeSpaceBytes", op);
		if (freeSpace < 0L || freeSpace > WorldBuilderContractLimits.MAX_INVENTORY_TOTAL_BYTES) {
			invalid(op, "Mutation free-space requirement is invalid.");
		}
		MutationSummary mutations = validateMutationActions(
			root.get("actions"), op, transactionId);
		validateCreatedDirectories(root.get("createdDirectories"), mutations, op);
		if (freeSpace < mutations.requiredBytes) {
			invalid(op, "Mutation free-space requirement omits planned content or backups.");
		}
		ConfigurationSummary configurationSummary = validateConfigurationChanges(
			root.get("configurationChanges"), op, false);
		if (!mutations.relativePaths.containsAll(configurationSummary.relativePaths)
			|| !mutations.activationPaths.containsAll(configurationSummary.relativePaths)) {
			invalid(op, "Configuration change has no matching byte-level mutation.");
		}
		String backup = relative(root, "backupRootRelativePath", op);
		String receipt = relative(root, "receiptRelativePath", op);
		if (!("backups/" + transactionId).equals(backup)
			|| !("receipts/" + transactionId + ".json").equals(receipt)) {
			invalid(op, "Mutation backup or receipt path does not bind the transaction UUID.");
		}
		Map<String,String> postWrite = validateVerifications(
			root.get("postWriteVerifications"), op, "postWriteVerifications", 1);
		Map<String,String> rollback = validateVerifications(
			root.get("rollbackVerifications"), op, "rollbackVerifications", 1);
		validateVerificationCoverage(mutations.afterExpectations, postWrite,
			op, "Post-write verification");
		validateVerificationCoverage(mutations.beforeExpectations, rollback,
			op, "Rollback verification");
		hash(root, "planFingerprintSha256", op);
	}

	private static void validateCreatedDirectories(Object raw,
		MutationSummary mutations, String op) throws WorldBuilderContractException {
		List<?> values = array(raw, op, "createdDirectories", 0,
			WorldBuilderContractLimits.MAX_MUTATIONS);
		Set<String> seen = new HashSet<String>();
		String previous = null;
		for (Object rawValue : values) {
			String path = WorldBuilderBoundedInventory.string(
				rawValue, op, "createdDirectories");
			path = WorldBuilderPortablePath.require(path, op);
			String collision = WorldBuilderPortablePath.collisionKey(path, op);
			if (!seen.add(collision)) {
				invalid(op, "Created-directory authority repeats a portable path.");
			}
			boolean ancestor = false;
			for (String destination : mutations.destinationPaths) {
				if (destination.startsWith(path + "/")) {
					ancestor = true;
					break;
				}
			}
			if (!ancestor) {
				invalid(op, "Created-directory authority is not an action ancestor.");
			}
			if (previous != null) {
				int depth = path.split("/").length - previous.split("/").length;
				if (depth < 0 || depth == 0 && previous.compareTo(path) >= 0) {
					invalid(op, "Created-directory authority is not canonically ordered.");
				}
			}
			previous = path;
		}
	}

	private static void validateReceipt(Map<String,Object> root)
		throws WorldBuilderContractException {
		String op = "validate-adaptive-receipt";
		exact(root, op, "schemaVersion", "manifestType", "transactionId",
			"transactionType", "status", "createdAtUtc", "projectId",
			"exportFingerprintSha256", "mutationPlanSha256", "adapterId", "capabilityId",
			"targetLineageSha256", "selectedConfiguration", "mutationOccurred", "offlineEvidence",
			"files", "configurationChanges", "verificationResults", "revertsTransactionId",
			"recoveryTransactionId", "receiptFingerprintSha256");
		String transactionId = uuid(root, "transactionId", op, false);
		String type = enumeration(root, "transactionType", op, "import", "recovery", "undo");
		String status = enumeration(root, "status", op, "failed-no-change", "pending",
			"recovery-required", "reverted", "rolled-back", "successful");
		String created = text(root, "createdAtUtc", op, 1, 64);
		try {
			if (!Instant.parse(created).toString().equals(created)) {
				invalid(op, "Receipt timestamp is not canonical UTC.");
			}
		} catch (RuntimeException invalidTimestamp) {
			invalid(op, "Receipt timestamp is not an ISO-8601 UTC instant.");
		}
		uuid(root, "projectId", op, false); hash(root, "exportFingerprintSha256", op);
		hash(root, "mutationPlanSha256", op); identifier(root, "adapterId", op);
		identifier(root, "capabilityId", op); hash(root, "targetLineageSha256", op);
		StateReference selected = stateReference(
			root.get("selectedConfiguration"), op, "selectedConfiguration", true);
		if (!selected.present) invalid(op, "Receipt has no selected configuration.");
		boolean mutationOccurred = bool(root, "mutationOccurred", op);
		int unverifiedOffline = validateOfflineEvidence(root.get("offlineEvidence"), op);
		ReceiptFileSummary fileSummary = validateReceiptFiles(
			root.get("files"), op, transactionId, status);
		ConfigurationSummary configurationSummary = validateConfigurationChanges(
			root.get("configurationChanges"), op, true);
		if (!fileSummary.relativePaths.containsAll(configurationSummary.relativePaths)) {
			invalid(op, "Receipt configuration change has no matching file evidence.");
		}
		VerificationSummary verifications = validateReceiptVerifications(
			root.get("verificationResults"), op);
		String reverts = uuid(root, "revertsTransactionId", op, true);
		String recovery = uuid(root, "recoveryTransactionId", op, true);
		if (("import".equals(type) && !reverts.isEmpty())
			|| ("undo".equals(type) && reverts.isEmpty())
			|| ("recovery".equals(type) && recovery.isEmpty())
			|| (!"recovery".equals(type) && !recovery.isEmpty())
			|| transactionId.equals(reverts) || transactionId.equals(recovery)) {
			invalid(op, "Receipt transaction lineage is invalid.");
		}
		if ("reverted".equals(status) && !"undo".equals(type)) {
			invalid(op, "Only an undo transaction may have reverted status.");
		}
		if (("failed-no-change".equals(status) && mutationOccurred)
			|| (("successful".equals(status) || "rolled-back".equals(status)
				|| "reverted".equals(status) || "recovery-required".equals(status))
				&& !mutationOccurred)) {
			invalid(op, "Receipt status and mutation state are inconsistent.");
		}
		if (mutationOccurred && unverifiedOffline > 0) {
			invalid(op, "A receipt with target mutation has unverified offline evidence.");
		}
		if ("successful".equals(status)
			&& (fileSummary.afterUnverified > 0
				|| configurationSummary.afterUnverified > 0
				|| verifications.total == 0 || verifications.failed > 0)) {
			invalid(op, "Successful receipt contains unverified target state.");
		}
		if (("rolled-back".equals(status) || "reverted".equals(status))
			&& (fileSummary.rollbackUnverified > 0
				|| configurationSummary.rollbackUnverified > 0)) {
			invalid(op, "Rolled-back receipt contains unverified restored state.");
		}
		if ("recovery-required".equals(status) && verifications.failed == 0
			&& fileSummary.afterUnverified == 0
			&& configurationSummary.afterUnverified == 0) {
			invalid(op, "Recovery-required receipt has no failed verification evidence.");
		}
		hash(root, "receiptFingerprintSha256", op);
	}

	private static int validateChecks(Object raw, String op)
		throws WorldBuilderContractException {
		List<?> checks = array(raw, op, "checks", 0, 512);
		String previous = null;
		int failed = 0;
		for (Object value : checks) {
			Map<String,Object> check = object(value, op, "check");
			exact(check, op, "checkId", "status", "expected", "observed");
			String id = identifier(check, "checkId", op);
			String status = enumeration(
				check, "status", op, "failed", "not-applicable", "passed");
			if ("failed".equals(status)) failed++;
			text(check, "expected", op, 0, WorldBuilderContractLimits.MAX_DETAIL_CHARS);
			text(check, "observed", op, 0, WorldBuilderContractLimits.MAX_DETAIL_CHARS);
			if (previous != null && previous.compareTo(id) >= 0) {
				invalid(op, "Discovery checks are duplicated or not canonically ordered.");
			}
			previous = id;
		}
		return failed;
	}

	private static Operations operations(Object raw, String op)
		throws WorldBuilderContractException {
		Map<String,Object> value = object(raw, op, "operations");
		exact(value, op, "createProject", "edit", "save", "export", "import", "undo");
		return new Operations(bool(value, "createProject", op), bool(value, "edit", op),
			bool(value, "save", op), bool(value, "export", op),
			bool(value, "import", op), bool(value, "undo", op));
	}

	private static List<Issue> issues(Object raw, String op)
		throws WorldBuilderContractException {
		List<?> values = array(raw, op, "issues", 0, WorldBuilderContractLimits.MAX_ISSUES);
		List<Issue> issues = new ArrayList<Issue>(values.size());
		String previous = null;
		for (Object rawIssue : values) {
			Map<String,Object> issue = object(rawIssue, op, "issue");
			exact(issue, op, "code", "severity", "operation", "projectId", "adapterId",
				"relativePath", "provenance", "recordIndex", "recordKey", "expected",
				"observed", "mutationOccurred", "nextStep");
			String code = string(issue, "code", op);
			if (!WorldBuilderErrorCodes.isStable(code)) {
				invalid(op, "Issue uses an unknown stable error code.");
			}
			String severity = enumeration(issue, "severity", op, "blocker", "warning");
			if (WorldBuilderErrorCodes.requiresBlockerSeverity(code)
				&& !"blocker".equals(severity)) {
				invalid(op, "Blocker-class issue code cannot be downgraded to a warning.");
			}
			String operation = identifier(issue, "operation", op);
			String projectId = uuid(issue, "projectId", op, true);
			String adapterId = optionalIdentifier(issue, "adapterId", op);
			String path = optionalRelative(issue, "relativePath", op);
			if (WorldBuilderErrorCodes.UNSAFE_PATH.equals(code) && path.isEmpty()) {
				invalid(op, "Unsafe-path issue has no relative-path provenance.");
			}
			String provenance = text(issue, "provenance", op, 0,
				WorldBuilderContractLimits.MAX_DETAIL_CHARS);
			long recordIndex = integer(issue, "recordIndex", op);
			if (recordIndex < -1L || recordIndex > Integer.MAX_VALUE) {
				invalid(op, "Issue record index is outside its supported range.");
			}
			String recordKey = text(issue, "recordKey", op, 0,
				WorldBuilderContractLimits.MAX_DETAIL_CHARS);
			if ((recordIndex < 0L) != recordKey.isEmpty()) {
				invalid(op, "Issue record index and key must be present together.");
			}
			text(issue, "expected", op, 0, WorldBuilderContractLimits.MAX_DETAIL_CHARS);
			text(issue, "observed", op, 0, WorldBuilderContractLimits.MAX_DETAIL_CHARS);
			boolean mutationOccurred = bool(issue, "mutationOccurred", op);
			text(issue, "nextStep", op, 1, WorldBuilderContractLimits.MAX_DETAIL_CHARS);
			String key = code + "\u0000" + operation + "\u0000" + path + "\u0000"
				+ String.format("%011d", recordIndex) + "\u0000" + recordKey
				+ "\u0000" + provenance;
			if (previous != null && previous.compareTo(key) >= 0) {
				invalid(op, "Issues are duplicated or not in canonical order.");
			}
			previous = key;
			issues.add(new Issue(
				code, severity, projectId, adapterId, path, mutationOccurred));
		}
		return Collections.unmodifiableList(issues);
	}

	private static boolean hasBlocker(List<Issue> issues) {
		for (Issue issue : issues) if ("blocker".equals(issue.severity)) return true;
		return false;
	}

	private static boolean hasMutationClaim(List<Issue> issues) {
		for (Issue issue : issues) if (issue.mutationOccurred) return true;
		return false;
	}

	private static StateReference stateReference(Object raw, String op, String name,
		boolean includeRole) throws WorldBuilderContractException {
		Map<String,Object> value = object(raw, op, name);
		if (includeRole) exact(value, op, "present", "role", "relativePath", "sha256");
		else exact(value, op, "present", "relativePath", "sha256");
		boolean present = bool(value, "present", op);
		String role = includeRole ? optionalIdentifier(value, "role", op) : "";
		String path = optionalRelative(value, "relativePath", op);
		String hash = optionalHash(value, "sha256", op);
		if (present != !path.isEmpty() || present != !hash.isEmpty()
			|| (includeRole && present != !role.isEmpty())) {
			invalid(op, "Contract file reference has inconsistent presence state: " + name);
		}
		return new StateReference(present, role, path, hash);
	}

	private static CapabilityReference capabilityReference(Object raw, String op)
		throws WorldBuilderContractException {
		Map<String,Object> value = object(raw, op, "capability");
		exact(value, op, "resolved", "adapterId", "capabilityId",
			"evidenceRelativePath", "evidenceSha256");
		boolean resolved = bool(value, "resolved", op);
		String adapter = optionalIdentifier(value, "adapterId", op);
		String capability = optionalIdentifier(value, "capabilityId", op);
		String path = optionalRelative(value, "evidenceRelativePath", op);
		String hash = optionalHash(value, "evidenceSha256", op);
		if (resolved != !adapter.isEmpty() || resolved != !capability.isEmpty()
			|| path.isEmpty() != hash.isEmpty()) {
			invalid(op, "Discovery capability reference is incomplete.");
		}
		return new CapabilityReference(resolved, adapter, capability);
	}

	private static void validatePlacementSummaries(Object raw, String op)
		throws WorldBuilderContractException {
		List<?> values = array(raw, op, "placements", 0,
			WorldBuilderContractLimits.MAX_PLACEMENT_SUMMARIES);
		String previous = null;
		for (Object rawValue : values) {
			Map<String,Object> value = object(rawValue, op, "placementSummary");
			exact(value, op, "family", "level", "sourceRole", "definitionId", "count");
			String family = enumeration(value, "family", op,
				"boundary", "ground-item", "npc", "scenery");
			long level = signedInt(value, "level", op);
			String role = identifier(value, "sourceRole", op);
			long definition = boundedCount(value, "definitionId", op);
			long count = boundedCount(value, "count", op);
			String key = family + String.format("\u0000%011d\u0000", level)
				+ role + String.format("\u0000%020d", definition);
			if (previous != null && previous.compareTo(key) >= 0) {
				invalid(op, "Placement summaries are invalid, duplicated, or not canonically ordered.");
			}
			previous = key;
		}
	}

	private static boolean validateDecisions(Object raw, String op)
		throws WorldBuilderContractException {
		List<?> values = array(raw, op, "decisions", 0,
			WorldBuilderContractLimits.MAX_PLACEMENT_SUMMARIES);
		String previous = null;
		boolean blocked = false;
		for (Object rawValue : values) {
			Map<String,Object> value = object(rawValue, op, "decision");
			exact(value, op, "kind", "sourceRole", "provenance", "placementId", "outcome");
			String kind = enumeration(value, "kind", op,
				"collision", "precedence", "removal", "replacement");
			String role = identifier(value, "sourceRole", op);
			String provenance = text(value, "provenance", op, 1,
				WorldBuilderContractLimits.MAX_DETAIL_CHARS);
			String placement = identifier(value, "placementId", op);
			String outcome = enumeration(value, "outcome", op,
				"blocked", "removed", "replaced", "retained");
			if ("blocked".equals(outcome)) blocked = true;
			String key = kind + "\u0000" + role + "\u0000" + provenance
				+ "\u0000" + placement + "\u0000" + outcome;
			if (previous != null && previous.compareTo(key) >= 0) {
				invalid(op, "Conversion decisions are duplicated or not canonically ordered.");
			}
			previous = key;
		}
		return blocked;
	}

	private static Set<String> validateHashRecords(Object raw, String op, String name,
		int minimum, int maximum) throws WorldBuilderContractException {
		List<?> values = array(raw, op, name, minimum, maximum);
		String previous = null;
		Set<String> roles = new HashSet<String>();
		for (Object rawValue : values) {
			Map<String,Object> value = object(rawValue, op, name);
			exact(value, op, "role", "sha256");
			String role = identifier(value, "role", op); hash(value, "sha256", op);
			if (previous != null && previous.compareTo(role) >= 0) {
				invalid(op, name + " records are duplicated or not canonically ordered.");
			}
			previous = role;
			roles.add(role);
		}
		return Collections.unmodifiableSet(roles);
	}

	private static MutationSummary validateMutationActions(
		Object raw, String op, String transactionId)
		throws WorldBuilderContractException {
		List<?> actions = array(raw, op, "actions", 1, WorldBuilderContractLimits.MAX_MUTATIONS);
		Set<String> destinations = new HashSet<String>();
		Set<String> backupPaths = new HashSet<String>();
		Set<String> roles = new HashSet<String>();
		Map<String,String> beforeExpectations = new LinkedHashMap<String,String>();
		Map<String,String> afterExpectations = new LinkedHashMap<String,String>();
		Set<String> activationPaths = new HashSet<String>();
		Set<String> destinationPaths = new HashSet<String>();
		boolean activationStarted = false;
		long requiredBytes = 0L;
		for (int index = 0; index < actions.size(); index++) {
			Map<String,Object> action = object(actions.get(index), op, "action");
			exact(action, op, "sequence", "role", "destinationRelativePath", "before",
				"after", "contentRelativePath", "backupRelativePath", "activation");
			if (integer(action, "sequence", op) != index) {
				invalid(op, "Mutation action sequence is not contiguous and canonical.");
			}
			String role = identifier(action, "role", op);
			if (!roles.add(role)) invalid(op, "Mutation plan repeats a logical role.");
			String destination = relative(action, "destinationRelativePath", op);
			destinationPaths.add(destination);
			String destinationKey = WorldBuilderPortablePath.collisionKey(destination, op);
			if (!destinations.add(destinationKey)) {
				throw new WorldBuilderContractException(
					WorldBuilderErrorCodes.INVENTORY_DUPLICATE, op,
					"Mutation plan repeats a case-colliding destination.");
			}
			FileState before = fileState(action.get("before"), op, "before");
			FileState after = fileState(action.get("after"), op, "after");
			if (before.sameContent(after)) {
				invalid(op, "Mutation action proposes no content change to its destination.");
			}
			String content = optionalRelative(action, "contentRelativePath", op);
			String backup = optionalRelative(action, "backupRelativePath", op);
			if (after.present != !content.isEmpty()
				|| (!content.isEmpty() && !content.startsWith("package/"))) {
				invalid(op, "Mutation content path does not match its proposed after state.");
			}
			if (before.present != !backup.isEmpty()
				|| (!backup.isEmpty() && (!backup.startsWith(
					"backups/" + transactionId + "/before/")
					|| !backupPaths.add(WorldBuilderPortablePath.collisionKey(backup, op))))) {
				invalid(op, "Mutation backup path does not match its before state or transaction.");
			}
			boolean activation = bool(action, "activation", op);
			if (activation) {
				activationStarted = true;
				activationPaths.add(destinationKey);
			}
			else if (activationStarted) invalid(op, "Mutation activation actions must be last.");
			if (!before.present && !after.present) {
				invalid(op, "Mutation action has neither before nor after content.");
			}
			requiredBytes = boundedByteTotal(
				requiredBytes, before.size, op, "Mutation backup and content byte total");
			requiredBytes = boundedByteTotal(
				requiredBytes, after.size, op, "Mutation backup and content byte total");
			beforeExpectations.put(destinationKey, before.expectation());
			afterExpectations.put(destinationKey, after.expectation());
		}
		return new MutationSummary(destinations, destinationPaths, activationPaths,
			beforeExpectations, afterExpectations, requiredBytes);
	}

	private static ConfigurationSummary validateConfigurationChanges(
		Object raw, String op, boolean receipt)
		throws WorldBuilderContractException {
		List<?> changes = array(raw, op, "configurationChanges", 0,
			WorldBuilderContractLimits.MAX_MUTATIONS);
		Set<String> keys = new HashSet<String>();
		Set<String> relativePaths = new HashSet<String>();
		int afterUnverified = 0;
		int rollbackUnverified = 0;
		for (int index = 0; index < changes.size(); index++) {
			Map<String,Object> change = object(changes.get(index), op, "configurationChange");
			if (receipt) {
				exact(change, op, "sequence", "configurationRelativePath", "key",
					"beforePresent", "beforeValue", "afterPresent", "afterValue",
					"afterVerified", "rollbackVerified");
			} else {
				exact(change, op, "sequence", "configurationRelativePath", "key",
					"beforePresent", "beforeValue", "afterPresent", "afterValue");
			}
			if (integer(change, "sequence", op) != index) {
				invalid(op, "Configuration change sequence is not contiguous and canonical.");
			}
			String path = relative(change, "configurationRelativePath", op);
			String key = identifier(change, "key", op);
			boolean beforePresent = bool(change, "beforePresent", op);
			String beforeValue = text(
				change, "beforeValue", op, 0, WorldBuilderContractLimits.MAX_DETAIL_CHARS);
			boolean afterPresent = bool(change, "afterPresent", op);
			String afterValue = text(
				change, "afterValue", op, 0, WorldBuilderContractLimits.MAX_DETAIL_CHARS);
			if (!beforePresent && !beforeValue.isEmpty()
				|| !afterPresent && !afterValue.isEmpty()
				|| !beforePresent && !afterPresent
				|| beforePresent == afterPresent && beforeValue.equals(afterValue)) {
				invalid(op, "Configuration change presence/value state is inconsistent or unchanged.");
			}
			String pathKey = WorldBuilderPortablePath.collisionKey(path, op);
			relativePaths.add(pathKey);
			if (!keys.add(pathKey + "\u0000"
				+ key.toLowerCase(java.util.Locale.ROOT))) {
				invalid(op, "Configuration changes repeat a path/key pair.");
			}
			if (receipt) {
				if (!bool(change, "afterVerified", op)) afterUnverified++;
				if (!bool(change, "rollbackVerified", op)) rollbackUnverified++;
			}
		}
		return new ConfigurationSummary(
			relativePaths, afterUnverified, rollbackUnverified);
	}

	private static Map<String,String> validateVerifications(Object raw, String op, String name,
		int minimum) throws WorldBuilderContractException {
		List<?> values = array(raw, op, name, minimum, WorldBuilderContractLimits.MAX_MUTATIONS);
		String previous = null;
		Map<String,String> expectations = new LinkedHashMap<String,String>();
		for (Object rawValue : values) {
			Map<String,Object> value = object(rawValue, op, name);
			exact(value, op, "verificationId", "relativePath", "expected");
			String id = identifier(value, "verificationId", op);
			String path = relative(value, "relativePath", op);
			String expected = text(
				value, "expected", op, 1, WorldBuilderContractLimits.MAX_DETAIL_CHARS);
			if (!("absent".equals(expected)
				|| WorldBuilderBoundedInventory.isHash(expected))) {
				invalid(op, name + " expected state must be a SHA-256 or exact absence.");
			}
			String key = id + "\u0000" + path;
			if (previous != null && previous.compareTo(key) >= 0) {
				invalid(op, name + " are duplicated or not canonically ordered.");
			}
			previous = key;
			String pathKey = WorldBuilderPortablePath.collisionKey(path, op);
			if (expectations.put(pathKey, expected) != null) {
				invalid(op, name + " repeats a target destination.");
			}
		}
		return Collections.unmodifiableMap(expectations);
	}

	private static int validateOfflineEvidence(Object raw, String op)
		throws WorldBuilderContractException {
		List<?> values = array(raw, op, "offlineEvidence", 1, 32);
		String previous = null;
		int unverified = 0;
		for (Object rawValue : values) {
			Map<String,Object> value = object(rawValue, op, "offlineEvidence");
			exact(value, op, "kind", "observed", "verified");
			String kind = identifier(value, "kind", op);
			text(value, "observed", op, 1, WorldBuilderContractLimits.MAX_DETAIL_CHARS);
			if (!bool(value, "verified", op)) unverified++;
			if (previous != null && previous.compareTo(kind) >= 0) {
				invalid(op, "Offline evidence is duplicated or not canonically ordered.");
			}
			previous = kind;
		}
		return unverified;
	}

	private static ReceiptFileSummary validateReceiptFiles(
		Object raw, String op, String transactionId,
		String status) throws WorldBuilderContractException {
		List<?> values = array(raw, op, "files", 1, WorldBuilderContractLimits.MAX_MUTATIONS);
		Set<String> paths = new HashSet<String>();
		String previous = null;
		int afterUnverified = 0;
		int rollbackUnverified = 0;
		long recordedBytes = 0L;
		for (Object rawValue : values) {
			Map<String,Object> value = object(rawValue, op, "file");
			exact(value, op, "role", "relativePath", "before", "after",
				"backupRelativePath", "backupSha256", "afterVerified", "rollbackVerified");
			String role = identifier(value, "role", op);
			String path = relative(value, "relativePath", op);
			if (!paths.add(WorldBuilderPortablePath.collisionKey(path, op))) {
				invalid(op, "Receipt repeats a case-colliding target path.");
			}
			FileState before = fileState(value.get("before"), op, "before");
			FileState after = fileState(value.get("after"), op, "after");
			if (before.sameContent(after)) {
				invalid(op, "Receipt file record describes no target content change.");
			}
			recordedBytes = boundedByteTotal(
				recordedBytes, before.size, op, "Receipt file-state byte total");
			recordedBytes = boundedByteTotal(
				recordedBytes, after.size, op, "Receipt file-state byte total");
			String backup = optionalRelative(value, "backupRelativePath", op);
			String backupHash = optionalHash(value, "backupSha256", op);
			if (before.present != !backup.isEmpty() || before.present != !backupHash.isEmpty()
				|| (!backup.isEmpty() && !backup.startsWith(
					"backups/" + transactionId + "/before/"))
				|| (before.present && !before.sha256.equals(backupHash))) {
				invalid(op, "Receipt backup evidence does not match its before state.");
			}
			boolean afterVerified = bool(value, "afterVerified", op);
			boolean rollbackVerified = bool(value, "rollbackVerified", op);
			if (!afterVerified) afterUnverified++;
			if (!rollbackVerified) rollbackUnverified++;
			if ("successful".equals(status) && !afterVerified
				|| "rolled-back".equals(status) && !rollbackVerified) {
				invalid(op, "Receipt status is inconsistent with file verification.");
			}
			String key = path + "\u0000" + role;
			if (previous != null && previous.compareTo(key) >= 0) {
				invalid(op, "Receipt files are not in canonical path and role order.");
			}
			previous = key;
		}
		return new ReceiptFileSummary(paths, afterUnverified, rollbackUnverified);
	}

	private static VerificationSummary validateReceiptVerifications(Object raw, String op)
		throws WorldBuilderContractException {
		List<?> values = array(raw, op, "verificationResults", 0,
			WorldBuilderContractLimits.MAX_MUTATIONS);
		String previous = null;
		int failed = 0;
		for (Object rawValue : values) {
			Map<String,Object> value = object(rawValue, op, "verificationResult");
			exact(value, op, "verificationId", "success", "observed");
			String id = identifier(value, "verificationId", op);
			if (!bool(value, "success", op)) failed++;
			text(value, "observed", op, 1, WorldBuilderContractLimits.MAX_DETAIL_CHARS);
			if (previous != null && previous.compareTo(id) >= 0) {
				invalid(op, "Receipt verifications are duplicated or not canonically ordered.");
			}
			previous = id;
		}
		return new VerificationSummary(values.size(), failed);
	}

	private static FileState fileState(Object raw, String op, String name)
		throws WorldBuilderContractException {
		Map<String,Object> value = object(raw, op, name);
		exact(value, op, "present", "size", "sha256");
		boolean present = bool(value, "present", op);
		long size = integer(value, "size", op);
		String hash = string(value, "sha256", op);
		if (size < 0L || size > WorldBuilderContractLimits.MAX_INVENTORY_FILE_BYTES
			|| present != WorldBuilderBoundedInventory.isHash(hash)
			|| (!present && (size != 0L || !hash.isEmpty()))) {
			invalid(op, "File state is inconsistent or exceeds its size limit: " + name);
		}
		return new FileState(present, size, hash);
	}

	private static void validateVerificationCoverage(
		Map<String,String> expected, Map<String,String> observed,
		String op, String label) throws WorldBuilderContractException {
		if (!expected.equals(observed)) {
			invalid(op, label
				+ " set must exactly cover every mutation destination and expected state.");
		}
	}

	private static boolean matchesEvidence(
		List<WorldBuilderBoundedInventory.Record> inventory, StateReference reference) {
		for (WorldBuilderBoundedInventory.Record record : inventory) {
			if (record.present && record.relativePath.equals(reference.relativePath)
				&& record.sha256.equals(reference.sha256)) return true;
		}
		return false;
	}

	private static boolean hasExactInventoryRecord(
		List<WorldBuilderBoundedInventory.Record> inventory,
		String role, String path) {
		for (WorldBuilderBoundedInventory.Record record : inventory) {
			if (record.present && role.equals(record.role)
				&& path.equals(record.relativePath)) return true;
		}
		return false;
	}

	private static void validateOriginState(String origin, String state, String op)
		throws WorldBuilderContractException {
		boolean standalone = "standalone-empty".equals(origin);
		if (standalone) {
			if (!("ready-standalone".equals(state) || "staging".equals(state)
				|| "source-corrupt".equals(state))) {
				invalid(op, "Standalone project origin and state are inconsistent.");
			}
		} else if ("ready-standalone".equals(state)) {
			invalid(op, "Target-backed project cannot use standalone-ready state.");
		}
	}

	private static void validateProjectOperations(
		String state, Operations operations, String op)
		throws WorldBuilderContractException {
		if (operations.createProject) {
			invalid(op, "An existing project manifest cannot advertise project creation.");
		}
		if ("ready-attached".equals(state)) {
			if (!operations.edit || !operations.save || !operations.export
				|| !operations.importTarget) {
				invalid(op, "Attached-ready project operations do not match the state matrix.");
			}
		} else if ("ready-detached".equals(state)
			|| "ready-standalone".equals(state)) {
			if (!operations.edit || !operations.save || !operations.export
				|| operations.importTarget || operations.undo) {
				invalid(op, "Isolated-ready project operations do not match the state matrix.");
			}
		} else if (operations.any()) {
			invalid(op, "Unsafe project state cannot advertise any operation.");
		}
	}

	private static long boundedCount(Map<String,Object> value, String key, String op)
		throws WorldBuilderContractException {
		long count = nonnegative(value, key, op);
		if (count > Integer.MAX_VALUE) {
			throw new WorldBuilderContractException(
				WorldBuilderErrorCodes.CONTRACT_LIMIT_EXCEEDED, op,
				"Conversion count exceeds its supported range: " + key);
		}
		return count;
	}

	private static long safeAdd(long first, long second, String op, String label)
		throws WorldBuilderContractException {
		try {
			return Math.addExact(first, second);
		} catch (ArithmeticException overflow) {
			throw new WorldBuilderContractException(
				WorldBuilderErrorCodes.CONTRACT_LIMIT_EXCEEDED, op,
				label + " overflowed.");
		}
	}

	private static long boundedByteTotal(long current, long added,
		String op, String label) throws WorldBuilderContractException {
		long result;
		try {
			result = Math.addExact(current, added);
		} catch (ArithmeticException overflow) {
			throw new WorldBuilderContractException(
				WorldBuilderErrorCodes.CONTRACT_LIMIT_EXCEEDED, op,
				label + " overflowed.");
		}
		if (result > WorldBuilderContractLimits.MAX_INVENTORY_TOTAL_BYTES) {
			throw new WorldBuilderContractException(
				WorldBuilderErrorCodes.CONTRACT_LIMIT_EXCEEDED, op,
				label + " exceeds the supported limit.");
		}
		return result;
	}

	@SafeVarargs
	private static void uniqueAcrossInventories(String op,
		List<WorldBuilderBoundedInventory.Record>... inventories)
		throws WorldBuilderContractException {
		Set<String> paths = new HashSet<String>();
		long totalBytes = 0L;
		for (List<WorldBuilderBoundedInventory.Record> inventory : inventories) {
			for (WorldBuilderBoundedInventory.Record record : inventory) {
				totalBytes = boundedByteTotal(
					totalBytes, record.size, op, "Source inventory byte total");
				if (!paths.add(WorldBuilderPortablePath.collisionKey(record.relativePath, op))) {
					throw new WorldBuilderContractException(
						WorldBuilderErrorCodes.INVENTORY_DUPLICATE, op,
						"Source snapshot inventories overlap or case-collide.");
				}
			}
		}
	}

	private static Map<String,Object> object(Object raw, String op, String name)
		throws WorldBuilderContractException {
		if (!(raw instanceof Map)) invalid(op, "Contract field is not an object: " + name);
		@SuppressWarnings("unchecked") Map<String,Object> value = (Map<String,Object>)raw;
		return value;
	}

	private static List<?> array(Object raw, String op, String name, int minimum, int maximum)
		throws WorldBuilderContractException {
		if (!(raw instanceof List)) invalid(op, "Contract field is not an array: " + name);
		List<?> value = (List<?>)raw;
		if (value.size() < minimum || value.size() > maximum) {
			throw new WorldBuilderContractException(
				WorldBuilderErrorCodes.CONTRACT_LIMIT_EXCEEDED, op,
				"Contract array count is outside its limit: " + name);
		}
		return value;
	}

	private static void exact(Map<String,Object> value, String op, String... keys)
		throws WorldBuilderContractException {
		WorldBuilderBoundedInventory.exactKeys(value, op, keys);
	}

	private static String string(Map<String,Object> value, String key, String op)
		throws WorldBuilderContractException {
		return WorldBuilderBoundedInventory.string(value.get(key), op, key);
	}

	private static String identifier(Map<String,Object> value, String key, String op)
		throws WorldBuilderContractException {
		return WorldBuilderBoundedInventory.identifier(value.get(key), op, key);
	}

	private static String optionalIdentifier(Map<String,Object> value, String key, String op)
		throws WorldBuilderContractException {
		String result = string(value, key, op);
		if (!result.isEmpty()) WorldBuilderBoundedInventory.identifier(result, op, key);
		return result;
	}

	private static String text(Map<String,Object> value, String key, String op,
		int minimum, int maximum) throws WorldBuilderContractException {
		String result = string(value, key, op);
		if (result.length() < minimum || result.length() > maximum
			|| containsInvalidUnicode(result)) {
			invalid(op, "Contract text field is outside its bounds: " + key);
		}
		return result;
	}

	private static boolean containsInvalidUnicode(String value) {
		for (int index = 0; index < value.length(); index++) {
			char c = value.charAt(index);
			if (Character.isHighSurrogate(c)) {
				if (index + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(index + 1))) {
					return true;
				}
				index++;
			} else if (Character.isLowSurrogate(c)) return true;
		}
		return false;
	}

	private static boolean bool(Map<String,Object> value, String key, String op)
		throws WorldBuilderContractException {
		return WorldBuilderBoundedInventory.bool(value.get(key), op, key);
	}

	private static long integer(Map<String,Object> value, String key, String op)
		throws WorldBuilderContractException {
		return WorldBuilderBoundedInventory.integer(value.get(key), op, key);
	}

	private static long nonnegative(Map<String,Object> value, String key, String op)
		throws WorldBuilderContractException {
		long result = integer(value, key, op);
		if (result < 0L) invalid(op, "Contract count is negative: " + key);
		return result;
	}

	private static long signedInt(Map<String,Object> value, String key, String op)
		throws WorldBuilderContractException {
		long result = integer(value, key, op);
		if (result < Integer.MIN_VALUE || result > Integer.MAX_VALUE) {
			invalid(op, "Contract signed coordinate is out of range: " + key);
		}
		return result;
	}

	private static String hash(Map<String,Object> value, String key, String op)
		throws WorldBuilderContractException {
		String result = string(value, key, op);
		if (!WorldBuilderBoundedInventory.isHash(result)) invalid(op, "Invalid SHA-256: " + key);
		return result;
	}

	private static String optionalHash(Map<String,Object> value, String key, String op)
		throws WorldBuilderContractException {
		String result = string(value, key, op);
		if (!result.isEmpty() && !WorldBuilderBoundedInventory.isHash(result)) {
			invalid(op, "Invalid optional SHA-256: " + key);
		}
		return result;
	}

	private static String relative(Map<String,Object> value, String key, String op)
		throws WorldBuilderContractException {
		return WorldBuilderPortablePath.require(string(value, key, op), op);
	}

	private static String optionalRelative(Map<String,Object> value, String key, String op)
		throws WorldBuilderContractException {
		String result = string(value, key, op);
		if (!result.isEmpty()) WorldBuilderPortablePath.require(result, op);
		return result;
	}

	private static String uuid(Map<String,Object> value, String key, String op,
		boolean optional) throws WorldBuilderContractException {
		String result = string(value, key, op);
		if (optional && result.isEmpty()) return result;
		if (!result.matches("[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")) {
			invalid(op, "Contract UUID is not canonical: " + key);
		}
		try {
			if (!UUID.fromString(result).toString().equals(result)) {
				invalid(op, "Contract UUID is not canonical: " + key);
			}
		} catch (IllegalArgumentException malformed) {
			invalid(op, "Contract UUID is invalid: " + key);
		}
		return result;
	}

	private static String origin(Map<String,Object> value, String op)
		throws WorldBuilderContractException {
		return enumeration(value, "origin", op,
			"standalone-empty", "target-layered", "target-packed");
	}

	private static String enumeration(Map<String,Object> value, String key, String op,
		String... allowed) throws WorldBuilderContractException {
		String result = string(value, key, op);
		if (!Arrays.asList(allowed).contains(result)) {
			invalid(op, "Contract enum value is invalid: " + key);
		}
		return result;
	}

	private static List<String> identifierList(Object raw, String op, String name,
		int minimum, int maximum) throws WorldBuilderContractException {
		return identifierList(raw, op, name, minimum, maximum, true);
	}

	private static List<String> identifierList(Object raw, String op, String name,
		int minimum, int maximum, boolean sorted) throws WorldBuilderContractException {
		List<?> values = array(raw, op, name, minimum, maximum);
		List<String> result = new ArrayList<String>(values.size());
		Set<String> seen = new HashSet<String>();
		String previous = null;
		for (Object value : values) {
			String identifier = WorldBuilderBoundedInventory.identifier(value, op, name);
			if (!seen.add(identifier) || sorted && previous != null
				&& previous.compareTo(identifier) >= 0) {
				invalid(op, "Identifier list is duplicated or not canonical: " + name);
			}
			previous = identifier;
			result.add(identifier);
		}
		return Collections.unmodifiableList(result);
	}

	private static List<String> enumList(Object raw, String op, String name,
		int minimum, int maximum, String... allowed) throws WorldBuilderContractException {
		List<?> values = array(raw, op, name, minimum, maximum);
		List<String> result = new ArrayList<String>(values.size());
		String previous = null;
		for (Object value : values) {
			String item = WorldBuilderBoundedInventory.string(value, op, name);
			if (!Arrays.asList(allowed).contains(item)
				|| previous != null && previous.compareTo(item) >= 0) {
				invalid(op, "Enum list is invalid or not canonical: " + name);
			}
			previous = item;
			result.add(item);
		}
		return Collections.unmodifiableList(result);
	}

	private static void ascendingPositiveIntegers(Object raw, String op, String name,
		int minimumCount, int maximumCount) throws WorldBuilderContractException {
		List<?> values = array(raw, op, name, minimumCount, maximumCount);
		long previous = 0L;
		for (Object value : values) {
			long current = WorldBuilderBoundedInventory.integer(value, op, name);
			if (current <= previous || current > Integer.MAX_VALUE) {
				invalid(op, "Integer version list is invalid or not ascending: " + name);
			}
			previous = current;
		}
	}

	private static void invalid(String operation, String message)
		throws WorldBuilderContractException {
		throw new WorldBuilderContractException(
			WorldBuilderErrorCodes.CONTRACT_VALUE_INVALID, operation, message);
	}

	static final class Document {
		final Kind kind;
		final String canonicalJson;
		final String canonicalSha256;

		Document(Kind kind, String canonicalJson, String canonicalSha256) {
			this.kind = kind;
			this.canonicalJson = canonicalJson;
			this.canonicalSha256 = canonicalSha256;
		}
	}

	private static final class Operations {
		final boolean createProject, edit, save, export, importTarget, undo;
		Operations(boolean createProject, boolean edit, boolean save, boolean export,
			boolean importTarget, boolean undo) {
			this.createProject = createProject; this.edit = edit; this.save = save;
			this.export = export; this.importTarget = importTarget; this.undo = undo;
		}
		boolean any() {
			return createProject || edit || save || export || importTarget || undo;
		}
		boolean discoveryOnly() {
			return createProject && !edit && !save && !export && !importTarget && !undo;
		}
	}

	private static final class Issue {
		final String code, severity, projectId, adapterId, relativePath;
		final boolean mutationOccurred;
		Issue(String code, String severity, String projectId, String adapterId,
			String relativePath, boolean mutationOccurred) {
			this.code = code; this.severity = severity; this.projectId = projectId;
			this.adapterId = adapterId; this.relativePath = relativePath;
			this.mutationOccurred = mutationOccurred;
		}
	}

	private static final class StateReference {
		final boolean present;
		final String role, relativePath, sha256;
		StateReference(boolean present, String role, String relativePath, String sha256) {
			this.present = present; this.role = role; this.relativePath = relativePath;
			this.sha256 = sha256;
		}
	}

	private static final class CapabilityReference {
		final boolean resolved;
		final String adapterId, capabilityId;
		CapabilityReference(boolean resolved, String adapterId, String capabilityId) {
			this.resolved = resolved; this.adapterId = adapterId;
			this.capabilityId = capabilityId;
		}
	}

	private static final class FileState {
		final boolean present;
		final long size;
		final String sha256;
		FileState(boolean present, long size, String sha256) {
			this.present = present; this.size = size; this.sha256 = sha256;
		}
		boolean sameContent(FileState other) {
			if (present != other.present) return false;
			return !present || sha256.equals(other.sha256);
		}
		String expectation() {
			return present ? sha256 : "absent";
		}
	}

	private static final class ConfigurationSummary {
		final Set<String> relativePaths;
		final int afterUnverified;
		final int rollbackUnverified;

		ConfigurationSummary(Set<String> relativePaths, int afterUnverified,
			int rollbackUnverified) {
			this.relativePaths = relativePaths;
			this.afterUnverified = afterUnverified;
			this.rollbackUnverified = rollbackUnverified;
		}
	}

	private static final class ReceiptFileSummary {
		final Set<String> relativePaths;
		final int afterUnverified;
		final int rollbackUnverified;

		ReceiptFileSummary(Set<String> relativePaths, int afterUnverified,
			int rollbackUnverified) {
			this.relativePaths = relativePaths;
			this.afterUnverified = afterUnverified;
			this.rollbackUnverified = rollbackUnverified;
		}
	}

	private static final class MutationSummary {
		final Set<String> relativePaths;
		final Set<String> destinationPaths;
		final Set<String> activationPaths;
		final Map<String,String> beforeExpectations;
		final Map<String,String> afterExpectations;
		final long requiredBytes;

		MutationSummary(Set<String> relativePaths, Set<String> destinationPaths,
			Set<String> activationPaths,
			Map<String,String> beforeExpectations, Map<String,String> afterExpectations,
			long requiredBytes) {
			this.relativePaths = relativePaths;
			this.destinationPaths = destinationPaths;
			this.activationPaths = activationPaths;
			this.beforeExpectations = beforeExpectations;
			this.afterExpectations = afterExpectations;
			this.requiredBytes = requiredBytes;
		}
	}

	private static final class VerificationSummary {
		final int total;
		final int failed;
		VerificationSummary(int total, int failed) {
			this.total = total;
			this.failed = failed;
		}
	}
}
