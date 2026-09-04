package com.openrsc.worldbuilder;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Strict identities and a non-executing target classifier for the managed
 * current-runtime generation.  Target documents are evidence only: this class
 * contains no mutation, process-launch, class-loading, or target build path.
 */
final class WorldBuilderCurrentRuntimeContracts {
	private static final String ZERO_HASH =
		"0000000000000000000000000000000000000000000000000000000000000000";
	private static final int MAX_RULES = 4096;
	private static final int MAX_LIST = 256;

	private WorldBuilderCurrentRuntimeContracts() {
	}

	enum Kind {
		PLATFORM_RELEASE("platform-release", "world-builder-current-platform-release",
			"platformManifestHash"),
		RUNTIME_VARIANT("runtime-variant", "world-builder-current-runtime-variant",
			"variantManifestHash"),
		MODULE_SET("module-set", "world-builder-current-module-set", "moduleSetHash"),
		INPUT_ADAPTER("input-adapter", "world-builder-current-input-adapter",
			"adapterManifestHash"),
		PROJECT_CAPABILITY("project-capability", "world-builder-current-project-capability",
			"capabilityFingerprintSha256"),
		TARGET_LEDGER("target-ledger", "world-builder-current-target-runtime-ledger",
			"ledgerFingerprintSha256"),
		TARGET_CLASSIFICATION("target-classification",
			"world-builder-current-target-classification",
			"classificationFingerprintSha256");

		final String externalName;
		final String manifestType;
		final String fingerprintField;

		Kind(String externalName, String manifestType, String fingerprintField) {
			this.externalName = externalName;
			this.manifestType = manifestType;
			this.fingerprintField = fingerprintField;
		}

		static Kind named(String value) throws WorldBuilderContractException {
			for (Kind kind : values()) if (kind.externalName.equals(value)) return kind;
			throw invalid("select-current-runtime-contract",
				"Unknown current-runtime contract kind: " + value);
		}
	}

	static Document read(Kind kind, Path path)
		throws IOException, WorldBuilderContractException {
		Map<String,Object> root;
		try {
			root = WorldBuilderJsonDocuments.readObject(path);
		} catch (WorldBuilderDiscoveryException malformed) {
			throw new WorldBuilderContractException(WorldBuilderErrorCodes.MALFORMED_JSON,
				"validate-" + kind.externalName, "", false,
				"Current-runtime contract is malformed or unsafe: " + malformed.getMessage(),
				"Supply one bounded UTF-8 JSON object with unique keys.", malformed);
		}
		validate(kind, root, true);
		return new Document(kind, root);
	}

	private static void validate(Kind kind, Map<String,Object> root,
		boolean requireFingerprint) throws WorldBuilderContractException {
		String op = "validate-" + kind.externalName;
		if (integer(root, "schemaVersion", op) != 1L) {
			throw new WorldBuilderContractException(
				WorldBuilderErrorCodes.UNSUPPORTED_CONTRACT_VERSION, op,
				"Current-runtime contract schema version is not exactly 1.");
		}
		if (!kind.manifestType.equals(string(root, "manifestType", op))) {
			throw new WorldBuilderContractException(
				WorldBuilderErrorCodes.CONTRACT_IDENTITY_INVALID, op,
				"Current-runtime contract manifest type is not " + kind.manifestType + ".");
		}
		switch (kind) {
			case PLATFORM_RELEASE: validatePlatform(root, op); break;
			case RUNTIME_VARIANT: validateVariant(root, op); break;
			case MODULE_SET: validateModuleSet(root, op); break;
			case INPUT_ADAPTER: validateAdapter(root, op); break;
			case PROJECT_CAPABILITY: validateProjectCapability(root, op); break;
			case TARGET_LEDGER: validateLedger(root, op); break;
			case TARGET_CLASSIFICATION: validateClassification(root, op); break;
			default: throw new AssertionError(kind);
		}
		if (requireFingerprint) requireFingerprint(root, kind.fingerprintField, op);
	}

	private static void validatePlatform(Map<String,Object> root, String op)
		throws WorldBuilderContractException {
		exact(root, op, "schemaVersion", "manifestType", "strategyId",
			"platformReleaseId", "platformApiVersion", "protocolId", "stateSchemaId",
			"supportedPredecessorReleaseIds", "variants", "inputAdapters",
			"platformManifestHash");
		identifier(root, "strategyId", op); identifier(root, "platformReleaseId", op);
		identifier(root, "platformApiVersion", op); identifier(root, "protocolId", op);
		identifier(root, "stateSchemaId", op);
		identifiers(root.get("supportedPredecessorReleaseIds"), op,
			"supportedPredecessorReleaseIds", 0, 64);
		identifiers(root.get("variants"), op, "variants", 1, 64);
		identifiers(root.get("inputAdapters"), op, "inputAdapters", 1, 64);
		hash(root, "platformManifestHash", op, false);
	}

	private static void validateVariant(Map<String,Object> root, String op)
		throws WorldBuilderContractException {
		exact(root, op, "schemaVersion", "manifestType", "variantId", "variantKind",
			"platformReleaseId", "platformManifestHash", "defaultModuleIds",
			"allowedModuleIds", "semanticContractIds", "advancedOnly",
			"variantManifestHash");
		identifier(root, "variantId", op); identifier(root, "platformReleaseId", op);
		hash(root, "platformManifestHash", op, false);
		String variantKind = enumeration(root, "variantKind", op, "base", "advanced");
		List<String> defaults = identifiers(root.get("defaultModuleIds"), op,
			"defaultModuleIds", 0, 128);
		List<String> allowed = identifiers(root.get("allowedModuleIds"), op,
			"allowedModuleIds", 0, 128);
		identifiers(root.get("semanticContractIds"), op, "semanticContractIds", 1, 128);
		if (!allowed.containsAll(defaults)) invalid(op,
			"Every default module must be allowed by the variant.");
		boolean advancedOnly = bool(root, "advancedOnly", op);
		if (advancedOnly != "advanced".equals(variantKind)) invalid(op,
			"Variant kind and advanced-only identity disagree.");
		hash(root, "variantManifestHash", op, false);
	}

	private static void validateModuleSet(Map<String,Object> root, String op)
		throws WorldBuilderContractException {
		exact(root, op, "schemaVersion", "manifestType", "platformReleaseId",
			"platformManifestHash", "variantId", "variantManifestHash", "modules",
			"moduleSetHash", "bundleInventoryHash");
		identifier(root, "platformReleaseId", op); hash(root, "platformManifestHash", op, false);
		identifier(root, "variantId", op); hash(root, "variantManifestHash", op, false);
		List<?> modules = array(root.get("modules"), op, "modules", 0, 128);
		Set<String> moduleIds = new HashSet<String>();
		for (int index = 0; index < modules.size(); index++) {
			Map<String,Object> module = object(modules.get(index), op, "modules");
			exact(module, op, "order", "moduleId", "moduleVersion", "moduleKind",
				"platformApiVersion", "moduleManifestHash", "modulePayloadRootHash",
				"dependencies", "conflicts", "clientPairing", "configurationNamespace",
				"stateMigrationIds", "provenance");
			if (integer(module, "order", op) != index) invalid(op,
				"Module order must be contiguous and canonical.");
			String id = identifier(module, "moduleId", op);
			if (!moduleIds.add(id)) invalid(op, "Module set repeats a module ID.");
			identifier(module, "moduleVersion", op);
			enumeration(module, "moduleKind", op,
				"declarative-data", "code-plugin", "server-client-feature");
			identifier(module, "platformApiVersion", op);
			hash(module, "moduleManifestHash", op, false);
			hash(module, "modulePayloadRootHash", op, false);
			List<String> dependencies = identifiers(module.get("dependencies"), op,
				"dependencies", 0, 128);
			List<String> conflicts = identifiers(module.get("conflicts"), op,
				"conflicts", 0, 128);
			if (dependencies.contains(id) || conflicts.contains(id)) invalid(op,
				"A module cannot depend on or conflict with itself.");
			enumeration(module, "clientPairing", op, "none", "optional", "required");
			optionalIdentifier(module, "configurationNamespace", op);
			identifiers(module.get("stateMigrationIds"), op, "stateMigrationIds", 0, 128);
			enumeration(module, "provenance", op, "provider", "local-reviewed");
		}
		for (Object raw : modules) {
			Map<String,Object> module = object(raw, op, "modules");
			for (String dependency : identifiers(module.get("dependencies"), op,
				"dependencies", 0, 128)) {
				if (!moduleIds.contains(dependency)) invalid(op,
					"Module dependency is absent from the resolved set: " + dependency);
			}
			for (String conflict : identifiers(module.get("conflicts"), op,
				"conflicts", 0, 128)) {
				if (moduleIds.contains(conflict)) invalid(op,
					"Resolved module set contains a declared conflict: " + conflict);
			}
		}
		hash(root, "moduleSetHash", op, false);
		hash(root, "bundleInventoryHash", op, false);
	}

	private static void validateAdapter(Map<String,Object> root, String op)
		throws WorldBuilderContractException {
		exact(root, op, "schemaVersion", "manifestType", "adapterId", "adapterVersion",
			"historicalRuntimeId", "recommendedVariantId", "targetLedgerRelativePath",
			"probeRoots", "evidenceRules", "adapterManifestHash");
		identifier(root, "adapterId", op); identifier(root, "adapterVersion", op);
		identifier(root, "historicalRuntimeId", op);
		identifier(root, "recommendedVariantId", op);
		String ledgerPath = relative(root, "targetLedgerRelativePath", op);
		List<String> roots = relatives(root.get("probeRoots"), op, "probeRoots", 1, 64);
		for (int first = 0; first < roots.size(); first++) {
			for (int second = first + 1; second < roots.size(); second++) {
				if (isAncestor(roots.get(first), roots.get(second))
					|| isAncestor(roots.get(second), roots.get(first))) invalid(op,
					"Input-adapter probe roots overlap.");
			}
		}
		if (containedByAny(ledgerPath, roots)) invalid(op,
			"The target ledger cannot be inside a historical probe root.");
		List<?> rules = array(root.get("evidenceRules"), op, "evidenceRules", 1, MAX_RULES);
		Set<String> paths = new HashSet<String>();
		String previous = null;
		for (Object raw : rules) {
			Map<String,Object> rule = object(raw, op, "evidenceRules");
			exact(rule, op, "role", "relativePath", "required", "baselineSize",
				"baselineSha256", "evidenceKind", "recognizedDeltas");
			identifier(rule, "role", op);
			String path = relative(rule, "relativePath", op);
			if (!containedByAny(path, roots)) invalid(op,
				"Evidence rule is outside every bounded probe root.");
			String collision = WorldBuilderPortablePath.collisionKey(path, op);
			if (!paths.add(collision)) invalid(op, "Evidence rules repeat or case-collide.");
			if (previous != null && previous.compareTo(path) >= 0) invalid(op,
				"Evidence rules are not ordered by relative path.");
			previous = path;
			boolean required = bool(rule, "required", op);
			long baselineSize = boundedSize(rule, "baselineSize", op);
			String baselineHash = hash(rule, "baselineSha256", op, true);
			if (required != !baselineHash.isEmpty()
				|| baselineHash.isEmpty() != (baselineSize == 0L)) invalid(op,
				"Evidence baseline presence, size, and hash disagree.");
			enumeration(rule, "evidenceKind", op, "configuration", "database",
				"generated-state", "portable-data", "map", "plugin-source", "core-source",
				"client-source", "build", "definition", "asset", "dependency", "executable");
			List<?> deltas = array(rule.get("recognizedDeltas"), op,
				"recognizedDeltas", 0, 64);
			Set<String> deltaHashes = new HashSet<String>();
			for (Object deltaRaw : deltas) {
				Map<String,Object> delta = object(deltaRaw, op, "recognizedDeltas");
				exact(delta, op, "size", "sha256", "tier", "disposition", "moduleId", "reason");
				boundedSize(delta, "size", op);
				String deltaHash = hash(delta, "sha256", op, false);
				if (!deltaHashes.add(deltaHash) || deltaHash.equals(baselineHash)) invalid(op,
					"Recognized deltas repeat or duplicate the baseline hash.");
				String tier = enumeration(delta, "tier", op, "T1", "T2A", "T2B", "T3", "T4");
				String disposition = enumeration(delta, "disposition", op, "preserve-state",
					"typed-configuration", "canonical-data", "canonical-map",
					"mapped-to-platform", "mapped-to-module", "retire", "port-required");
				String moduleId = optionalIdentifier(delta, "moduleId", op);
				text(delta, "reason", op, 1, 1024);
				if ("mapped-to-module".equals(disposition) != !moduleId.isEmpty()) invalid(op,
					"Only a mapped-to-module delta may name a current module.");
				if (("T3".equals(tier) || "T4".equals(tier))
					&& !("mapped-to-module".equals(disposition)
						|| "mapped-to-platform".equals(disposition)
						|| "port-required".equals(disposition))) invalid(op,
					"T3/T4 evidence must map to current code or require a port.");
			}
		}
		hash(root, "adapterManifestHash", op, false);
	}

	private static void validateProjectCapability(Map<String,Object> root, String op)
		throws WorldBuilderContractException {
		exact(root, op, "schemaVersion", "manifestType", "projectId", "projectSchemaId",
			"projectSchemaHash", "authoredDataFingerprintSha256", "allowedVariantIds",
			"requiredCapabilityIds", "requiredModuleIds", "capabilityFingerprintSha256");
		uuid(root, "projectId", op); identifier(root, "projectSchemaId", op);
		hash(root, "projectSchemaHash", op, false);
		hash(root, "authoredDataFingerprintSha256", op, false);
		identifiers(root.get("allowedVariantIds"), op, "allowedVariantIds", 1, 128);
		identifiers(root.get("requiredCapabilityIds"), op, "requiredCapabilityIds", 0, 128);
		identifiers(root.get("requiredModuleIds"), op, "requiredModuleIds", 0, 128);
		hash(root, "capabilityFingerprintSha256", op, false);
	}

	private static void validateLedger(Map<String,Object> root, String op)
		throws WorldBuilderContractException {
		exact(root, op, "schemaVersion", "manifestType", "targetInstallationId",
			"platformReleaseId", "platformManifestHash", "variantId", "variantManifestHash",
			"moduleSetHash", "bundleInventoryHash", "inputAdapterId",
			"predecessorIdentityHash", "configurationMigrationIds", "stateMigrationIds",
			"serverBuildId", "clientBuildId", "activeLauncherRelativePath",
			"activeMapPackageId", "verificationEvidenceHash", "transactionReceiptIds",
			"ledgerFingerprintSha256");
		uuid(root, "targetInstallationId", op);
		identifier(root, "platformReleaseId", op); hash(root, "platformManifestHash", op, false);
		identifier(root, "variantId", op); hash(root, "variantManifestHash", op, false);
		hash(root, "moduleSetHash", op, false); hash(root, "bundleInventoryHash", op, false);
		identifier(root, "inputAdapterId", op); hash(root, "predecessorIdentityHash", op, true);
		identifiers(root.get("configurationMigrationIds"), op,
			"configurationMigrationIds", 0, MAX_LIST);
		identifiers(root.get("stateMigrationIds"), op, "stateMigrationIds", 0, MAX_LIST);
		identifier(root, "serverBuildId", op); identifier(root, "clientBuildId", op);
		relative(root, "activeLauncherRelativePath", op);
		identifier(root, "activeMapPackageId", op);
		hash(root, "verificationEvidenceHash", op, false);
		identifiers(root.get("transactionReceiptIds"), op, "transactionReceiptIds", 0, MAX_LIST);
		hash(root, "ledgerFingerprintSha256", op, false);
	}

	private static void validateClassification(Map<String,Object> root, String op)
		throws WorldBuilderContractException {
		exact(root, op, "schemaVersion", "manifestType", "status", "tier",
			"mutationOccurred", "inputAdapterId", "historicalRuntimeId", "destination",
			"projectCapability", "installedLedger", "evidence", "actions",
			"classificationFingerprintSha256");
		String status = enumeration(root, "status", op,
			"CURRENT", "UPGRADE_READY", "PORT_REQUIRED", "BLOCKED_UNSAFE");
		String tier = enumeration(root, "tier", op, "T0", "T1", "T2A", "T2B",
			"T3", "T4", "T5", "CURRENT", "MANAGED_N");
		if (bool(root, "mutationOccurred", op)) invalid(op,
			"Read-only classification cannot report a mutation.");
		identifier(root, "inputAdapterId", op); identifier(root, "historicalRuntimeId", op);
		Map<String,Object> destination = object(root.get("destination"), op, "destination");
		exact(destination, op, "platformReleaseId", "platformManifestHash", "variantId",
			"variantManifestHash", "moduleSetHash", "bundleInventoryHash");
		identifier(destination, "platformReleaseId", op);
		hash(destination, "platformManifestHash", op, false);
		identifier(destination, "variantId", op); hash(destination, "variantManifestHash", op, false);
		hash(destination, "moduleSetHash", op, false);
		hash(destination, "bundleInventoryHash", op, false);
		Map<String,Object> project = object(root.get("projectCapability"), op, "projectCapability");
		exact(project, op, "projectId", "capabilityFingerprintSha256");
		uuid(project, "projectId", op); hash(project, "capabilityFingerprintSha256", op, false);
		Map<String,Object> ledger = object(root.get("installedLedger"), op, "installedLedger");
		exact(ledger, op, "present", "platformReleaseId", "ledgerFingerprintSha256");
		boolean ledgerPresent = bool(ledger, "present", op);
		String ledgerRelease = optionalIdentifier(ledger, "platformReleaseId", op);
		String ledgerHash = hash(ledger, "ledgerFingerprintSha256", op, true);
		if (ledgerPresent != !ledgerRelease.isEmpty() || ledgerPresent != !ledgerHash.isEmpty()) {
			invalid(op, "Installed-ledger presence and identity disagree.");
		}
		List<?> evidence = array(root.get("evidence"), op, "evidence", 0,
			WorldBuilderContractLimits.MAX_INVENTORY_ENTRIES);
		boolean portRequired = false;
		for (Object raw : evidence) {
			Map<String,Object> item = object(raw, op, "evidence");
			exact(item, op, "role", "relativePath", "tier", "disposition", "reason", "size", "sha256");
			identifier(item, "role", op); relative(item, "relativePath", op);
			enumeration(item, "tier", op, "T0", "T1", "T2A", "T2B", "T3", "T4", "T5");
			String disposition = enumeration(item, "disposition", op, "replace", "preserve-state",
				"typed-configuration", "canonical-data", "canonical-map",
				"mapped-to-platform", "mapped-to-module", "retire", "port-required", "blocker");
			if ("port-required".equals(disposition)) portRequired = true;
			text(item, "reason", op, 1, 1024); boundedSize(item, "size", op);
			hash(item, "sha256", op, true);
		}
		texts(root.get("actions"), op, "actions", 1, MAX_LIST, 1024);
		if ("CURRENT".equals(tier) != "CURRENT".equals(status)
			|| "MANAGED_N".equals(tier) && !"UPGRADE_READY".equals(status)
			|| "T5".equals(tier) != "BLOCKED_UNSAFE".equals(status)
			|| portRequired != "PORT_REQUIRED".equals(status)) invalid(op,
			"Classification tier and outcome disagree.");
		hash(root, "classificationFingerprintSha256", op, false);
	}

	static Classification classify(Path targetRoot, Path platformPath, Path variantPath,
		Path moduleSetPath, Path adapterPath, Path projectCapabilityPath)
		throws IOException, WorldBuilderContractException {
		Document platformDocument = read(Kind.PLATFORM_RELEASE, platformPath);
		Document variantDocument = read(Kind.RUNTIME_VARIANT, variantPath);
		Document moduleDocument = read(Kind.MODULE_SET, moduleSetPath);
		Document adapterDocument = read(Kind.INPUT_ADAPTER, adapterPath);
		Document projectDocument = read(Kind.PROJECT_CAPABILITY, projectCapabilityPath);
		Map<String,Object> platform = platformDocument.root;
		Map<String,Object> variant = variantDocument.root;
		Map<String,Object> modules = moduleDocument.root;
		Map<String,Object> adapter = adapterDocument.root;
		Map<String,Object> project = projectDocument.root;
		crossValidate(platformDocument, variantDocument, moduleDocument,
			adapterDocument, projectDocument);

		WorldBuilderReadOnlyTarget target = WorldBuilderReadOnlyTarget.open(targetRoot);
		String ledgerRelative = string(adapter, "targetLedgerRelativePath", "classify-target");
		if (target.exists(ledgerRelative)) {
			try {
				Document ledger = read(Kind.TARGET_LEDGER, target.requiredFile(ledgerRelative));
				return classifyLedger(platform, variant, modules, adapter, project, ledger);
			} catch (WorldBuilderContractException unsafeLedger) {
				List<Evidence> evidence = Collections.singletonList(new Evidence(
					"target-ledger", ledgerRelative, "T5", "blocker",
					"Installed runtime ledger is malformed, unsafe, or self-inconsistent.", 0L, ""));
				return classification("BLOCKED_UNSAFE", "T5", platform, variant, modules,
					adapter, project, null, evidence,
					Collections.singletonList("Restore exact ledger and runtime evidence before any target mutation."));
			}
		}

		List<Evidence> evidence = classifyHistorical(target, adapter);
		String tier = highestTier(evidence);
		String status = "UPGRADE_READY";
		List<String> actions = new ArrayList<String>();
		if ("T5".equals(tier)) {
			status = "BLOCKED_UNSAFE";
			actions.add("Review the unsafe or opaque evidence and add a bounded adapter rule before any target mutation.");
		} else if ("T3".equals(tier) || "T4".equals(tier)) {
			boolean portRequired = false;
			for (Evidence item : evidence) if ("port-required".equals(item.disposition)) {
				portRequired = true;
			}
			status = portRequired ? "PORT_REQUIRED" : "UPGRADE_READY";
			actions.add(portRequired
				? "Prepare and register the identified current platform/module port, then classify again."
				: "Install only the resolved current module; never load the historical extension binary.");
		} else {
			actions.add("Preview the transactional upgrade to the resolved current composition.");
		}
		if ("T0".equals(tier)) actions.add(
			"Convert the legacy packed map/state to the canonical current package during upgrade.");
		return classification(status, tier, platform, variant, modules,
			adapter, project, null, evidence, actions);
	}

	private static void crossValidate(Document platformDocument, Document variantDocument,
		Document moduleDocument, Document adapterDocument, Document projectDocument)
		throws WorldBuilderContractException {
		String op = "resolve-current-composition";
		Map<String,Object> platform = platformDocument.root;
		Map<String,Object> variant = variantDocument.root;
		Map<String,Object> modules = moduleDocument.root;
		Map<String,Object> adapter = adapterDocument.root;
		Map<String,Object> project = projectDocument.root;
		String platformId = string(platform, "platformReleaseId", op);
		String platformHash = string(platform, "platformManifestHash", op);
		String variantId = string(variant, "variantId", op);
		String variantHash = string(variant, "variantManifestHash", op);
		if (!platformId.equals(string(variant, "platformReleaseId", op))
			|| !platformHash.equals(string(variant, "platformManifestHash", op))
			|| !platformId.equals(string(modules, "platformReleaseId", op))
			|| !platformHash.equals(string(modules, "platformManifestHash", op))
			|| !variantId.equals(string(modules, "variantId", op))
			|| !variantHash.equals(string(modules, "variantManifestHash", op))) invalid(op,
			"Platform, variant, and module-set identities do not form one composition.");
		if (!identifiers(platform.get("variants"), op, "variants", 1, 64).contains(variantId)
			|| !identifiers(platform.get("inputAdapters"), op, "inputAdapters", 1, 64)
				.contains(string(adapter, "adapterId", op))) invalid(op,
			"Platform release does not bind the selected variant or input adapter.");
		if (!variantId.equals(string(adapter, "recommendedVariantId", op))) invalid(op,
			"Input adapter recommends a different current variant.");
		List<String> allowedVariants = identifiers(project.get("allowedVariantIds"), op,
			"allowedVariantIds", 1, 128);
		if (!allowedVariants.contains(variantId)) invalid(op,
			"Project capability does not permit the resolved current variant.");
		List<String> allowedModules = identifiers(variant.get("allowedModuleIds"), op,
			"allowedModuleIds", 0, 128);
		Set<String> resolvedModules = moduleIds(modules, op);
		if (!allowedModules.containsAll(resolvedModules)) invalid(op,
			"Resolved module set contains a module not allowed by the variant.");
		List<String> defaultModules = identifiers(variant.get("defaultModuleIds"), op,
			"defaultModuleIds", 0, 128);
		if (!resolvedModules.containsAll(defaultModules)) invalid(op,
			"Resolved module set omits a variant-default module.");
		String platformApi = string(platform, "platformApiVersion", op);
		for (Object raw : array(modules.get("modules"), op, "modules", 0, 128)) {
			Map<String,Object> module = object(raw, op, "modules");
			if (!platformApi.equals(string(module, "platformApiVersion", op))) invalid(op,
				"Resolved module targets a different platform API version.");
		}
		List<String> requiredModules = identifiers(project.get("requiredModuleIds"), op,
			"requiredModuleIds", 0, 128);
		if (!resolvedModules.containsAll(requiredModules)) invalid(op,
			"Resolved module set omits a project-required module.");
		List<String> requiredCapabilities = identifiers(project.get("requiredCapabilityIds"),
			op, "requiredCapabilityIds", 0, 128);
		List<String> variantCapabilities = identifiers(variant.get("semanticContractIds"),
			op, "semanticContractIds", 1, 128);
		if (!variantCapabilities.containsAll(requiredCapabilities)) invalid(op,
			"Resolved variant omits a project-required capability.");
	}

	private static Classification classifyLedger(Map<String,Object> platform,
		Map<String,Object> variant, Map<String,Object> modules, Map<String,Object> adapter,
		Map<String,Object> project, Document ledgerDocument)
		throws WorldBuilderContractException {
		Map<String,Object> ledger = ledgerDocument.root;
		String release = string(ledger, "platformReleaseId", "classify-ledger");
		String currentRelease = string(platform, "platformReleaseId", "classify-ledger");
		boolean sameVariant = string(ledger, "variantId", "classify-ledger")
			.equals(string(variant, "variantId", "classify-ledger"));
		boolean sameAdapter = string(ledger, "inputAdapterId", "classify-ledger")
			.equals(string(adapter, "adapterId", "classify-ledger"));
		boolean exactCurrent = release.equals(currentRelease) && sameVariant
			&& sameAdapter
			&& string(ledger, "platformManifestHash", "classify-ledger")
				.equals(string(platform, "platformManifestHash", "classify-ledger"))
			&& string(ledger, "variantManifestHash", "classify-ledger")
				.equals(string(variant, "variantManifestHash", "classify-ledger"))
			&& string(ledger, "moduleSetHash", "classify-ledger")
				.equals(string(modules, "moduleSetHash", "classify-ledger"))
			&& string(ledger, "bundleInventoryHash", "classify-ledger")
				.equals(string(modules, "bundleInventoryHash", "classify-ledger"));
		List<String> predecessors = identifiers(platform.get("supportedPredecessorReleaseIds"),
			"classify-ledger", "supportedPredecessorReleaseIds", 0, 64);
		if (exactCurrent) return classification("CURRENT", "CURRENT", platform,
			variant, modules, adapter, project, ledgerDocument,
			Collections.<Evidence>emptyList(),
			Collections.singletonList("Permit map-only import after normal ledger and artifact revalidation."));
		if (predecessors.contains(release) && sameVariant && sameAdapter) return classification(
			"UPGRADE_READY", "MANAGED_N", platform, variant, modules, adapter, project,
			ledgerDocument, Collections.<Evidence>emptyList(), Collections.singletonList(
				"Advance the trusted managed predecessor within its selected variant using a reviewed N-to-N+1 plan."));
		List<Evidence> evidence = Collections.singletonList(new Evidence(
			"target-ledger", string(adapter, "targetLedgerRelativePath", "classify-ledger"),
			"T5", "blocker", "Installed ledger is not the exact current composition or a trusted same-variant predecessor.",
			0L, ledgerDocument.canonicalSha256));
		return classification("BLOCKED_UNSAFE", "T5", platform, variant, modules,
			adapter, project, ledgerDocument, evidence, Collections.singletonList(
				"Resolve the mixed, unknown, or variant-changing ledger state before any mutation."));
	}

	private static List<Evidence> classifyHistorical(WorldBuilderReadOnlyTarget target,
		Map<String,Object> adapter) throws WorldBuilderContractException {
		String op = "classify-historical-target";
		List<?> rawRules = array(adapter.get("evidenceRules"), op, "evidenceRules", 1, MAX_RULES);
		Map<String,Map<String,Object>> rules = new LinkedHashMap<String,Map<String,Object>>();
		List<Evidence> result = new ArrayList<Evidence>();
		for (Object raw : rawRules) {
			Map<String,Object> rule = object(raw, op, "evidenceRules");
			String path = string(rule, "relativePath", op);
			rules.put(WorldBuilderPortablePath.collisionKey(path, op), rule);
			WorldBuilderReadOnlyTarget.FileState state;
			try {
				state = target.optionalState(string(rule, "role", op), path);
			} catch (WorldBuilderContractException unsafe) {
				result.add(new Evidence(string(rule, "role", op), path, "T5", "blocker",
					"Adapter-declared evidence is unsafe or unreadable.", 0L, ""));
				continue;
			}
			String baselineHash = string(rule, "baselineSha256", op);
			long baselineSize = integer(rule, "baselineSize", op);
			if ((!state.present && baselineHash.isEmpty())
				|| state.present && state.sha256.equals(baselineHash) && state.size == baselineSize) {
				if (state.present) result.add(new Evidence(state.role, path, "T0", "replace",
					"Exact sealed baseline evidence.", state.size, state.sha256));
				continue;
			}
			Map<String,Object> recognized = findDelta(rule.get("recognizedDeltas"), state, op);
			if (recognized != null) {
				result.add(new Evidence(state.role, path, string(recognized, "tier", op),
					string(recognized, "disposition", op), string(recognized, "reason", op),
					state.size, state.sha256));
			} else {
				result.add(new Evidence(state.role, path, "T5", "blocker",
					state.present
						? "File bytes do not match the sealed baseline or any reviewed semantic delta."
						: "Required sealed baseline evidence is missing.",
					state.size, state.sha256));
			}
		}
		for (String rootRelative : relatives(adapter.get("probeRoots"), op, "probeRoots", 1, 64)) {
			Path probe;
			try {
				if (!target.exists(rootRelative)) continue;
				probe = target.requiredDirectory(rootRelative);
			} catch (WorldBuilderContractException unsafe) {
				result.add(new Evidence("unclassified", rootRelative, "T5", "blocker",
					"Adapter probe root is unsafe or unreadable.", 0L, ""));
				continue;
			}
			final List<Path> discovered = new ArrayList<Path>();
			try {
			{
				Files.walkFileTree(probe, new SimpleFileVisitor<Path>() {
					@Override public FileVisitResult preVisitDirectory(Path directory,
						BasicFileAttributes attributes) throws IOException {
						if (Files.isSymbolicLink(directory)) {
							discovered.add(directory);
							return FileVisitResult.SKIP_SUBTREE;
						}
						return FileVisitResult.CONTINUE;
					}
					@Override public FileVisitResult visitFile(Path file,
						BasicFileAttributes attributes) throws IOException {
						discovered.add(file);
						if (discovered.size() > WorldBuilderContractLimits.MAX_INVENTORY_ENTRIES) {
							throw new IOException("bounded classifier inventory limit exceeded");
						}
						return FileVisitResult.CONTINUE;
					}
				});
			}
			} catch (IOException failure) {
				result.add(new Evidence("unclassified", rootRelative, "T5", "blocker",
					"Bounded adapter probe could not be completed safely.", 0L, ""));
				continue;
			}
			for (Path path : discovered) {
				String relative;
				try {
					if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
						|| Files.isSymbolicLink(path)) {
						relative = target.relative(path);
						result.add(new Evidence("unclassified", relative, "T5", "blocker",
							"Probe contains a symbolic link or non-regular file.", 0L, ""));
						continue;
					}
					relative = target.relative(path);
					if (rules.containsKey(WorldBuilderPortablePath.collisionKey(relative, op))) continue;
					WorldBuilderReadOnlyTarget.FileState state = target.requiredState("unclassified", relative);
					result.add(new Evidence("unclassified", relative, "T5", "blocker",
						"File is inside a bounded probe root but has no reviewed role or policy.",
						state.size, state.sha256));
				} catch (WorldBuilderContractException unsafe) {
					result.add(new Evidence("unclassified", rootRelative, "T5", "blocker",
						"Unclassified target evidence is unsafe or unreadable.", 0L, ""));
				}
			}
		}
		Collections.sort(result, new Comparator<Evidence>() {
			@Override public int compare(Evidence first, Evidence second) {
				int path = first.relativePath.compareTo(second.relativePath);
				return path != 0 ? path : first.role.compareTo(second.role);
			}
		});
		return result;
	}

	private static Map<String,Object> findDelta(Object raw,
		WorldBuilderReadOnlyTarget.FileState state, String op)
		throws WorldBuilderContractException {
		if (!state.present) return null;
		for (Object value : array(raw, op, "recognizedDeltas", 0, 64)) {
			Map<String,Object> delta = object(value, op, "recognizedDeltas");
			if (state.size == integer(delta, "size", op)
				&& state.sha256.equals(string(delta, "sha256", op))) return delta;
		}
		return null;
	}

	private static String highestTier(List<Evidence> evidence) {
		String result = "T0";
		for (Evidence item : evidence) if (tierRank(item.tier) > tierRank(result)) result = item.tier;
		return result;
	}

	private static int tierRank(String tier) {
		if ("T0".equals(tier)) return 0;
		if ("T1".equals(tier)) return 1;
		if ("T2A".equals(tier)) return 2;
		if ("T2B".equals(tier)) return 3;
		if ("T3".equals(tier)) return 4;
		if ("T4".equals(tier)) return 5;
		return 6;
	}

	private static Classification classification(String status, String tier,
		Map<String,Object> platform, Map<String,Object> variant, Map<String,Object> modules,
		Map<String,Object> adapter, Map<String,Object> project, Document ledger,
		List<Evidence> evidence, List<String> actions) throws WorldBuilderContractException {
		Map<String,Object> root = new LinkedHashMap<String,Object>();
		root.put("schemaVersion", Long.valueOf(1L));
		root.put("manifestType", Kind.TARGET_CLASSIFICATION.manifestType);
		root.put("status", status); root.put("tier", tier);
		root.put("mutationOccurred", Boolean.FALSE);
		root.put("inputAdapterId", string(adapter, "adapterId", "classify-target"));
		root.put("historicalRuntimeId", string(adapter, "historicalRuntimeId", "classify-target"));
		Map<String,Object> destination = new LinkedHashMap<String,Object>();
		destination.put("platformReleaseId", string(platform, "platformReleaseId", "classify-target"));
		destination.put("platformManifestHash", string(platform, "platformManifestHash", "classify-target"));
		destination.put("variantId", string(variant, "variantId", "classify-target"));
		destination.put("variantManifestHash", string(variant, "variantManifestHash", "classify-target"));
		destination.put("moduleSetHash", string(modules, "moduleSetHash", "classify-target"));
		destination.put("bundleInventoryHash", string(modules, "bundleInventoryHash", "classify-target"));
		root.put("destination", destination);
		Map<String,Object> projectReference = new LinkedHashMap<String,Object>();
		projectReference.put("projectId", string(project, "projectId", "classify-target"));
		projectReference.put("capabilityFingerprintSha256",
			string(project, "capabilityFingerprintSha256", "classify-target"));
		root.put("projectCapability", projectReference);
		Map<String,Object> ledgerReference = new LinkedHashMap<String,Object>();
		ledgerReference.put("present", Boolean.valueOf(ledger != null));
		ledgerReference.put("platformReleaseId", ledger == null ? ""
			: string(ledger.root, "platformReleaseId", "classify-target"));
		ledgerReference.put("ledgerFingerprintSha256", ledger == null ? ""
			: string(ledger.root, "ledgerFingerprintSha256", "classify-target"));
		root.put("installedLedger", ledgerReference);
		List<Object> evidenceValues = new ArrayList<Object>();
		for (Evidence item : evidence) evidenceValues.add(item.toJson());
		root.put("evidence", evidenceValues);
		root.put("actions", new ArrayList<Object>(new LinkedHashSet<String>(actions)));
		root.put("classificationFingerprintSha256", ZERO_HASH);
		WorldBuilderAdaptiveExporter.bindFingerprint(root, "classificationFingerprintSha256");
		validate(Kind.TARGET_CLASSIFICATION, root, true);
		return new Classification(root);
	}

	private static Set<String> moduleIds(Map<String,Object> modules, String op)
		throws WorldBuilderContractException {
		Set<String> ids = new HashSet<String>();
		for (Object value : array(modules.get("modules"), op, "modules", 0, 128)) {
			ids.add(identifier(object(value, op, "modules"), "moduleId", op));
		}
		return ids;
	}

	private static List<String> identifiers(Object raw, String op, String name,
		int minimum, int maximum) throws WorldBuilderContractException {
		List<?> values = array(raw, op, name, minimum, maximum);
		List<String> result = new ArrayList<String>();
		String previous = null;
		for (Object value : values) {
			String item = WorldBuilderBoundedInventory.identifier(value, op, name);
			if (previous != null && previous.compareTo(item) >= 0) invalid(op,
				"Identifier list repeats or is not canonically ordered: " + name);
			result.add(item); previous = item;
		}
		return result;
	}

	private static List<String> relatives(Object raw, String op, String name,
		int minimum, int maximum) throws WorldBuilderContractException {
		List<?> values = array(raw, op, name, minimum, maximum);
		List<String> result = new ArrayList<String>();
		String previous = null;
		for (Object value : values) {
			String item = WorldBuilderPortablePath.require(
				WorldBuilderBoundedInventory.string(value, op, name), op);
			if (previous != null && previous.compareTo(item) >= 0) invalid(op,
				"Path list repeats or is not canonically ordered: " + name);
			result.add(item); previous = item;
		}
		return result;
	}

	private static void texts(Object raw, String op, String name,
		int minimum, int maximum, int maxChars) throws WorldBuilderContractException {
		List<?> values = array(raw, op, name, minimum, maximum);
		Set<String> seen = new HashSet<String>();
		for (Object value : values) {
			String item = WorldBuilderBoundedInventory.string(value, op, name);
			if (item.isEmpty() || item.length() > maxChars || !seen.add(item)) invalid(op,
				"Text list is empty, repeated, or outside bounds: " + name);
		}
	}

	private static List<?> array(Object raw, String op, String name, int minimum, int maximum)
		throws WorldBuilderContractException {
		if (!(raw instanceof List)) invalid(op, "Contract field is not an array: " + name);
		List<?> values = (List<?>)raw;
		if (values.size() < minimum || values.size() > maximum) {
			throw new WorldBuilderContractException(
				WorldBuilderErrorCodes.CONTRACT_LIMIT_EXCEEDED, op,
				"Contract array count is outside its limit: " + name);
		}
		return values;
	}

	private static Map<String,Object> object(Object raw, String op, String name)
		throws WorldBuilderContractException {
		if (!(raw instanceof Map)) invalid(op, "Contract field is not an object: " + name);
		@SuppressWarnings("unchecked") Map<String,Object> result = (Map<String,Object>)raw;
		return result;
	}

	private static void exact(Map<String,Object> root, String op, String... keys)
		throws WorldBuilderContractException {
		WorldBuilderBoundedInventory.exactKeys(root, op, keys);
	}

	private static String string(Map<String,Object> root, String key, String op)
		throws WorldBuilderContractException {
		return WorldBuilderBoundedInventory.string(root.get(key), op, key);
	}

	private static String identifier(Map<String,Object> root, String key, String op)
		throws WorldBuilderContractException {
		return WorldBuilderBoundedInventory.identifier(root.get(key), op, key);
	}

	private static String optionalIdentifier(Map<String,Object> root, String key, String op)
		throws WorldBuilderContractException {
		String value = string(root, key, op);
		if (!value.isEmpty()) WorldBuilderBoundedInventory.identifier(value, op, key);
		return value;
	}

	private static String relative(Map<String,Object> root, String key, String op)
		throws WorldBuilderContractException {
		return WorldBuilderPortablePath.require(string(root, key, op), op);
	}

	private static String hash(Map<String,Object> root, String key, String op, boolean optional)
		throws WorldBuilderContractException {
		String value = string(root, key, op);
		if (!optional || !value.isEmpty()) if (!WorldBuilderBoundedInventory.isHash(value)) {
			invalid(op, "Contract SHA-256 is invalid: " + key);
		}
		return value;
	}

	private static long boundedSize(Map<String,Object> root, String key, String op)
		throws WorldBuilderContractException {
		long value = integer(root, key, op);
		if (value < 0L || value > WorldBuilderContractLimits.MAX_INVENTORY_FILE_BYTES) invalid(op,
			"Contract byte size is outside bounds: " + key);
		return value;
	}

	private static long integer(Map<String,Object> root, String key, String op)
		throws WorldBuilderContractException {
		return WorldBuilderBoundedInventory.integer(root.get(key), op, key);
	}

	private static boolean bool(Map<String,Object> root, String key, String op)
		throws WorldBuilderContractException {
		return WorldBuilderBoundedInventory.bool(root.get(key), op, key);
	}

	private static String enumeration(Map<String,Object> root, String key, String op,
		String... accepted) throws WorldBuilderContractException {
		String value = string(root, key, op);
		if (!Arrays.asList(accepted).contains(value)) invalid(op,
			"Contract enum value is unsupported: " + key);
		return value;
	}

	private static String text(Map<String,Object> root, String key, String op,
		int minimum, int maximum) throws WorldBuilderContractException {
		String value = string(root, key, op);
		if (value.length() < minimum || value.length() > maximum) invalid(op,
			"Contract text is outside bounds: " + key);
		return value;
	}

	private static void uuid(Map<String,Object> root, String key, String op)
		throws WorldBuilderContractException {
		String value = string(root, key, op);
		try {
		{
			if (!UUID.fromString(value).toString().equals(value)) invalid(op,
				"Contract UUID is not canonical: " + key);
		}
		} catch (IllegalArgumentException malformed) {
			invalid(op, "Contract UUID is invalid: " + key);
		}
	}

	private static void requireFingerprint(Map<String,Object> root, String key, String op)
		throws WorldBuilderContractException {
		String supplied = hash(root, key, op, false);
		root.put(key, ZERO_HASH);
		String expected;
		try {
		{
			expected = WorldBuilderAdaptiveExporter.canonicalHash(root);
		}
		} finally {
			root.put(key, supplied);
		}
		if (!supplied.equals(expected)) throw new WorldBuilderContractException(
			WorldBuilderErrorCodes.SOURCE_CORRUPT, op, key, false,
			"Current-runtime contract self-fingerprint does not match its content.",
			"Restore or regenerate the exact sealed contract.");
	}

	private static boolean containedByAny(String path, List<String> roots) {
		for (String root : roots) if (path.equals(root) || path.startsWith(root + "/")) return true;
		return false;
	}

	private static boolean isAncestor(String first, String second) {
		return second.equals(first) || second.startsWith(first + "/");
	}

	private static WorldBuilderContractException invalid(String operation, String message) {
		return new WorldBuilderContractException(
			WorldBuilderErrorCodes.CONTRACT_VALUE_INVALID, operation, message);
	}

	static final class Document {
		final Kind kind;
		final Map<String,Object> root;
		final String canonicalJson;
		final String canonicalSha256;

		Document(Kind kind, Map<String,Object> root) {
			this.kind = kind;
			this.root = root;
			this.canonicalJson = WorldBuilderJsonDocuments.canonical(root);
			this.canonicalSha256 = WorldBuilderHashes.sha256(
				canonicalJson.getBytes(java.nio.charset.StandardCharsets.UTF_8));
		}
	}

	static final class Classification {
		private final Map<String,Object> root;

		Classification(Map<String,Object> root) {
			this.root = root;
		}

		String toJson() {
			return WorldBuilderJsonDocuments.pretty(root);
		}

		String status() {
			return (String)root.get("status");
		}
	}

	private static final class Evidence {
		final String role;
		final String relativePath;
		final String tier;
		final String disposition;
		final String reason;
		final long size;
		final String sha256;

		Evidence(String role, String relativePath, String tier, String disposition,
			String reason, long size, String sha256) {
			this.role = role;
			this.relativePath = relativePath;
			this.tier = tier;
			this.disposition = disposition;
			this.reason = reason;
			this.size = size;
			this.sha256 = sha256;
		}

		Map<String,Object> toJson() {
			Map<String,Object> result = new LinkedHashMap<String,Object>();
			result.put("role", role); result.put("relativePath", relativePath);
			result.put("tier", tier); result.put("disposition", disposition);
			result.put("reason", reason); result.put("size", Long.valueOf(size));
			result.put("sha256", sha256);
			return result;
		}
	}
}
