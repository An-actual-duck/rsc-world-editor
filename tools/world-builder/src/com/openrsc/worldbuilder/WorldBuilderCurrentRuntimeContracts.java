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
		INPUT_ADAPTER("input-adapter", "world-builder-input-adapter-v1",
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

	static Document builtIn(Kind kind, Map<String,Object> root)
		throws WorldBuilderContractException {
		validate(kind, root, false);
		WorldBuilderAdaptiveExporter.bindFingerprint(root, kind.fingerprintField);
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
			case INPUT_ADAPTER: validateAdapter(root, op); break;
			case PROJECT_CAPABILITY: validateProjectCapability(root, op); break;
			case TARGET_LEDGER: validateLedger(root, op); break;
			case TARGET_CLASSIFICATION: validateClassification(root, op); break;
			default: throw new AssertionError(kind);
		}
		if (requireFingerprint) requireFingerprint(root, kind.fingerprintField, op);
	}

	private static void validateAdapter(Map<String,Object> root, String op)
		throws WorldBuilderContractException {
		exact(root, op, "schemaVersion", "manifestType", "adapterId", "adapterVersion",
			"historicalRuntimeId", "evidenceAuthority", "installable",
			"recommendedVariantId", "supportedManagedPredecessorReleaseIds",
			"targetLedgerRelativePath",
			"probeRoots", "evidenceRules", "adapterManifestHash");
		identifier(root, "adapterId", op); identifier(root, "adapterVersion", op);
		identifier(root, "historicalRuntimeId", op);
		enumeration(root, "evidenceAuthority", op, "production-reviewed", "synthetic-fixture");
		if (bool(root, "installable", op)) invalid(op,
			"Input adapters are migration evidence and are never installed into targets.");
		identifier(root, "recommendedVariantId", op);
		identifiers(root.get("supportedManagedPredecessorReleaseIds"), op,
			"supportedManagedPredecessorReleaseIds", 0, 64);
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
					"mapped-to-platform", "mapped-to-module", "retire", "discard-generated", "port-required");
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
			"platformReleaseId", "platformManifestHash", "schemaSetHash", "variantId", "variantManifestHash",
			"moduleSetHash", "bundleInventoryHash", "bundleSpecId", "bundleSpecHash",
			"inputAdapterContractId", "inputAdapterId",
			"predecessorIdentityHash", "configurationMigrationIds", "stateMigrationIds",
			"serverBuildId", "clientBuildId", "activeLauncherRelativePath",
			"activeMapPackageId", "verificationEvidenceHash", "transactionReceiptIds",
			"ledgerFingerprintSha256");
		uuid(root, "targetInstallationId", op);
		identifier(root, "platformReleaseId", op); hash(root, "platformManifestHash", op, false);
		hash(root, "schemaSetHash", op, false);
		identifier(root, "variantId", op); hash(root, "variantManifestHash", op, false);
		hash(root, "moduleSetHash", op, false); hash(root, "bundleInventoryHash", op, false);
		identifier(root, "bundleSpecId", op); hash(root, "bundleSpecHash", op, false);
		if (!"world-builder-input-adapter-v1".equals(
			identifier(root, "inputAdapterContractId", op))) invalid(op,
			"Target ledger does not bind the current Editor input-adapter contract.");
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
			"CURRENT", "UPGRADE_READY", "PORT_REQUIRED", "BLOCKED_UNSAFE",
			"NOT_INSTALLABLE");
		String tier = enumeration(root, "tier", op, "T0", "T1", "T2A", "T2B",
			"T3", "T4", "T5", "CURRENT", "MANAGED_N");
		if (bool(root, "mutationOccurred", op)) invalid(op,
			"Read-only classification cannot report a mutation.");
		identifier(root, "inputAdapterId", op); identifier(root, "historicalRuntimeId", op);
		Map<String,Object> destination = object(root.get("destination"), op, "destination");
		exact(destination, op, "platformReleaseId", "platformManifestHash", "schemaSetHash", "variantId",
			"variantManifestHash", "moduleSetHash", "bundleInventoryHash", "bundleSpecId",
			"bundleSpecHash", "inputAdapterContractId", "installable");
		identifier(destination, "platformReleaseId", op);
		hash(destination, "platformManifestHash", op, false);
		hash(destination, "schemaSetHash", op, false);
		identifier(destination, "variantId", op); hash(destination, "variantManifestHash", op, false);
		hash(destination, "moduleSetHash", op, false);
		hash(destination, "bundleInventoryHash", op, false);
		identifier(destination, "bundleSpecId", op); hash(destination, "bundleSpecHash", op, false);
		if (!"world-builder-input-adapter-v1".equals(
			identifier(destination, "inputAdapterContractId", op))) invalid(op,
			"Destination does not bind the current input-adapter contract.");
		bool(destination, "installable", op);
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
			exact(item, op, "role", "relativePath", "tier", "disposition", "moduleId", "reason", "size", "sha256");
			identifier(item, "role", op); relative(item, "relativePath", op);
			enumeration(item, "tier", op, "T0", "T1", "T2A", "T2B", "T3", "T4", "T5");
			String disposition = enumeration(item, "disposition", op, "replace", "preserve-state",
				"typed-configuration", "canonical-data", "canonical-map",
				"mapped-to-platform", "mapped-to-module", "retire", "discard-generated", "port-required", "blocker");
			if ("port-required".equals(disposition)) portRequired = true;
			String moduleId = optionalIdentifier(item, "moduleId", op);
			if ("mapped-to-module".equals(disposition) != !moduleId.isEmpty()) invalid(op,
				"Only mapped-to-module classification evidence may name a current module.");
			text(item, "reason", op, 1, 1024); boundedSize(item, "size", op);
			hash(item, "sha256", op, true);
		}
		texts(root.get("actions"), op, "actions", 1, MAX_LIST, 1024);
		if ("CURRENT".equals(status) && !"CURRENT".equals(tier)
			|| "MANAGED_N".equals(tier)
				&& !("UPGRADE_READY".equals(status) || "NOT_INSTALLABLE".equals(status))
			|| "T5".equals(tier) != "BLOCKED_UNSAFE".equals(status)
			|| portRequired != "PORT_REQUIRED".equals(status)
			|| "NOT_INSTALLABLE".equals(status) && "T5".equals(tier)) invalid(op,
			"Classification tier and outcome disagree.");
		hash(root, "classificationFingerprintSha256", op, false);
	}

	static Classification classify(Path targetRoot, Path providerCatalogRoot,
		Path compositionIdentityPath, Path adapterPath, Path projectCapabilityPath)
		throws IOException, WorldBuilderContractException {
		WorldBuilderProviderCatalog.Composition composition =
			WorldBuilderProviderCatalog.resolve(providerCatalogRoot, compositionIdentityPath);
		Document adapterDocument = read(Kind.INPUT_ADAPTER, adapterPath);
		Document projectDocument = read(Kind.PROJECT_CAPABILITY, projectCapabilityPath);
		return classify(targetRoot, composition, adapterDocument, projectDocument);
	}

	static Classification classify(Path targetRoot,
		WorldBuilderProviderCatalog.Composition composition, Document adapterDocument,
		Document projectDocument) throws IOException, WorldBuilderContractException {
		Map<String,Object> adapter = adapterDocument.root;
		Map<String,Object> project = projectDocument.root;
		crossValidate(composition, adapter, project);

		WorldBuilderReadOnlyTarget target = WorldBuilderReadOnlyTarget.open(targetRoot);
		String ledgerRelative = string(adapter, "targetLedgerRelativePath", "classify-target");
		if (target.exists(ledgerRelative)) {
			try {
				Document ledger = read(Kind.TARGET_LEDGER, target.requiredFile(ledgerRelative));
				return classifyLedger(composition, adapter, project, ledger);
			} catch (WorldBuilderContractException unsafeLedger) {
				List<Evidence> evidence = Collections.singletonList(new Evidence(
					"target-ledger", ledgerRelative, "T5", "blocker",
					"", "Installed runtime ledger is malformed, unsafe, or self-inconsistent.", 0L, ""));
				return classification("BLOCKED_UNSAFE", "T5", composition,
					adapter, project, null, evidence,
					Collections.singletonList("Restore exact ledger and runtime evidence before any target mutation."));
			}
		}

		List<Evidence> evidence = classifyHistorical(target, adapter);
		for (Evidence item : evidence) {
			if (!item.moduleId.isEmpty() && !composition.moduleIds.contains(item.moduleId)) {
				throw invalid("resolve-current-composition",
					"Input adapter maps target evidence to a module absent from the provider composition: "
						+ item.moduleId);
			}
		}
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
		if (!composition.installable && "UPGRADE_READY".equals(status)) {
			status = "NOT_INSTALLABLE";
			actions.clear();
			actions.add("The selected provider composition is non-installable; select a released installable bundle before preview or mutation.");
		}
		return classification(status, tier, composition,
			adapter, project, null, evidence, actions);
	}

	private static void crossValidate(WorldBuilderProviderCatalog.Composition composition,
		Map<String,Object> adapter, Map<String,Object> project)
		throws WorldBuilderContractException {
		String op = "resolve-current-composition";
		String variantId = composition.string("variantId");
		if (!variantId.equals(string(adapter, "recommendedVariantId", op))) invalid(op,
			"Input adapter recommends a different current variant.");
		String adapterId = string(adapter, "adapterId", op);
		if (!composition.admittedAdapterIds.contains(adapterId)) invalid(op,
			"Selected current variant does not admit this input adapter: " + adapterId);
		List<String> allowedVariants = identifiers(project.get("allowedVariantIds"), op,
			"allowedVariantIds", 1, 128);
		if (!allowedVariants.contains(variantId)) invalid(op,
			"Project capability does not permit the resolved current variant.");
		List<String> requiredModules = identifiers(project.get("requiredModuleIds"), op,
			"requiredModuleIds", 0, 128);
		if (!composition.moduleIds.containsAll(requiredModules)) invalid(op,
			"Resolved module set omits a project-required module.");
		List<String> requiredCapabilities = identifiers(project.get("requiredCapabilityIds"), op,
			"requiredCapabilityIds", 0, 128);
		if (!composition.availableCapabilities.containsAll(requiredCapabilities)) invalid(op,
			"Project requires a capability absent from the selected platform and variant.");
	}

	private static Classification classifyLedger(WorldBuilderProviderCatalog.Composition composition,
		Map<String,Object> adapter,
		Map<String,Object> project, Document ledgerDocument)
		throws WorldBuilderContractException {
		Map<String,Object> ledger = ledgerDocument.root;
		String release = string(ledger, "platformReleaseId", "classify-ledger");
		String currentRelease = composition.string("platformReleaseId");
		boolean sameVariant = string(ledger, "variantId", "classify-ledger")
			.equals(composition.string("variantId"));
		boolean sameAdapter = string(ledger, "inputAdapterId", "classify-ledger")
			.equals(string(adapter, "adapterId", "classify-ledger"));
		boolean exactCurrent = release.equals(currentRelease) && sameVariant
			&& sameAdapter
			&& string(ledger, "platformManifestHash", "classify-ledger")
				.equals(composition.string("platformManifestHash"))
			&& string(ledger, "schemaSetHash", "classify-ledger")
				.equals(composition.string("schemaSetHash"))
			&& string(ledger, "variantManifestHash", "classify-ledger")
				.equals(composition.string("variantManifestHash"))
			&& string(ledger, "moduleSetHash", "classify-ledger")
				.equals(composition.string("moduleSetHash"))
			&& string(ledger, "bundleInventoryHash", "classify-ledger")
				.equals(composition.string("bundleInventoryHash"))
			&& string(ledger, "bundleSpecId", "classify-ledger")
				.equals(composition.string("bundleSpecId"))
			&& string(ledger, "bundleSpecHash", "classify-ledger")
				.equals(composition.string("bundleSpecHash"))
			&& string(ledger, "inputAdapterContractId", "classify-ledger")
				.equals(composition.string("inputAdapterContractId"));
		List<String> predecessors = identifiers(adapter.get("supportedManagedPredecessorReleaseIds"),
			"classify-ledger", "supportedManagedPredecessorReleaseIds", 0, 64);
		if (exactCurrent) return classification(
			composition.installable ? "CURRENT" : "NOT_INSTALLABLE", "CURRENT", composition,
			adapter, project, ledgerDocument, Collections.<Evidence>emptyList(),
			Collections.singletonList(composition.installable
				? "Permit map-only import after normal ledger and artifact revalidation."
				: "The exact ledger names a non-installable composition; refuse activation until an installable provider bundle is selected."));
		if (predecessors.contains(release) && sameVariant && sameAdapter) return classification(
			composition.installable ? "UPGRADE_READY" : "NOT_INSTALLABLE",
			"MANAGED_N", composition, adapter, project,
			ledgerDocument, Collections.<Evidence>emptyList(), Collections.singletonList(
				composition.installable
					? "Advance the trusted managed predecessor within its selected variant using a reviewed N-to-N+1 plan."
					: "The selected destination is non-installable; select an installable provider bundle before N-to-N+1 preview."));
		List<Evidence> evidence = Collections.singletonList(new Evidence(
			"target-ledger", string(adapter, "targetLedgerRelativePath", "classify-ledger"),
			"T5", "blocker", "", "Installed ledger is not the exact current composition or a trusted same-variant predecessor.",
			0L, ledgerDocument.canonicalSha256));
		return classification("BLOCKED_UNSAFE", "T5", composition,
			adapter, project, ledgerDocument, evidence, Collections.singletonList(
				"Resolve the mixed, unknown, or variant-changing ledger state before any mutation."));
	}

	/** Read-only source evidence, with no composition resolution or activation authority. */
	static List<Map<String,Object>> inspectPreservationSource(Path targetRoot)
		throws WorldBuilderContractException {
		List<Map<String,Object>> result = new ArrayList<Map<String,Object>>();
		for (Evidence item : classifyHistorical(WorldBuilderReadOnlyTarget.open(targetRoot),
			WorldBuilderCurrentRuntimeExecutionProfile.preservation().adapter.root)) result.add(item.toJson());
		return result;
	}

	private static List<Evidence> classifyHistorical(WorldBuilderReadOnlyTarget target,
		Map<String,Object> adapter) throws WorldBuilderContractException {
		String op = "classify-historical-target";
		boolean sourceIntake = WorldBuilderPreservationSourceIntake.matchesAdapter(adapter);
		Map<String,Object> sourceConfiguration = null;
		if (sourceIntake) {
			try {
				target.requiredFile("server/preservation.conf");
				target.requiredFile("server/connections.conf");
				if (target.exists("server/local.conf")) target.requiredFile("server/local.conf");
				sourceConfiguration = WorldBuilderCurrentRuntimeExecutionProfile.preservation()
					.typedConfiguration(target.root);
			} catch (WorldBuilderContractException unsafe) {
				// Per-file classification below retains a bounded, value-free blocker.
			}
		}
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
				result.add(new Evidence(string(rule, "role", op), path, "T5", "blocker", "",
					"Adapter-declared evidence is unsafe or unreadable.", 0L, ""));
				continue;
			}
			String baselineHash = string(rule, "baselineSha256", op);
			long baselineSize = integer(rule, "baselineSize", op);
			if (sourceIntake && state.present) {
				if (!WorldBuilderPreservationSourceIntake.modeMatches(target.requiredFile(path), path)) {
					result.add(new Evidence(state.role, path, "T5", "blocker", "",
						"Historical source input mode differs from the reviewed source-layout policy.",
						state.size, state.sha256));
					continue;
				}
				if (Arrays.asList("server/preservation.conf", "server/local.conf", "server/connections.conf")
					.contains(path)) {
					boolean ready = sourceConfiguration != null && ((List<?>)sourceConfiguration
						.get("configurationBlockers")).isEmpty();
					String tier = sourceConfiguration == null ? "T5" : ready ? "T2A" : "T3";
					if (ready && state.sha256.equals(baselineHash)) tier = "T0";
					result.add(new Evidence(state.role, path, tier,
						sourceConfiguration == null ? "blocker" : ready ? "typed-configuration" : "port-required",
						"", sourceConfiguration == null ? "Effective configuration is missing, unsafe or unrepresentable."
						: ready ? "Reviewed historical defaults and supported effective configuration translations."
						: "Effective configuration changes or omits historical behavior without a reviewed current port.",
						state.size, state.sha256));
					continue;
				}
				if (!state.sha256.equals(baselineHash) && !WorldBuilderPreservationSourceClosure
					.changedTier(path).isEmpty() && !path.endsWith(".jar")) {
					result.add(new Evidence(state.role, path,
						WorldBuilderPreservationSourceClosure.changedTier(path), "port-required", "",
						"Historical source/build behavior changed; review and port it to the current composition.",
						state.size, state.sha256));
					continue;
				}
			}
			if ((!state.present && baselineHash.isEmpty())
				|| state.present && state.sha256.equals(baselineHash) && state.size == baselineSize) {
				if (state.present) result.add(new Evidence(state.role, path, "T0", "replace", "",
					"Exact sealed baseline evidence.", state.size, state.sha256));
				continue;
			}
			Map<String,Object> recognized = findDelta(rule.get("recognizedDeltas"), state, op);
			if (recognized != null) {
				result.add(new Evidence(state.role, path, string(recognized, "tier", op),
					string(recognized, "disposition", op), string(recognized, "moduleId", op),
					string(recognized, "reason", op),
					state.size, state.sha256));
			} else {
				result.add(new Evidence(state.role, path, "T5", "blocker", "",
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
				result.add(new Evidence("unclassified", rootRelative, "T5", "blocker", "",
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
				result.add(new Evidence("unclassified", rootRelative, "T5", "blocker", "",
					"Bounded adapter probe could not be completed safely.", 0L, ""));
				continue;
			}
			for (Path path : discovered) {
				String relative;
				try {
					if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
						|| Files.isSymbolicLink(path)) {
						relative = target.relative(path);
						result.add(new Evidence("unclassified", relative, "T5", "blocker", "",
							"Probe contains a symbolic link or non-regular file.", 0L, ""));
						continue;
					}
					relative = target.relative(path);
					if (rules.containsKey(WorldBuilderPortablePath.collisionKey(relative, op))) continue;
					WorldBuilderReadOnlyTarget.FileState state = target.requiredState("unclassified", relative);
					if (sourceIntake && WorldBuilderPreservationSourceIntake.modeMatches(
						target.requiredFile(relative), relative)) {
						if (WorldBuilderPreservationSourceIntake.knownVendor(state)) {
							result.add(new Evidence("historical-vendor-dependency", relative, "T0", "retire", "",
								"Exact historical dependency input; never adopted as a current runtime dependency.",
								state.size, state.sha256));
							continue;
						}
						String changedTier = WorldBuilderPreservationSourceClosure.changedTier(relative);
						if (!changedTier.isEmpty() && relative.endsWith(".java")) {
							result.add(new Evidence("historical-source-customization", relative, changedTier,
								"port-required", "", "Additional historical source requires a reviewed current port.",
								state.size, state.sha256));
							continue;
						}
					}
					result.add(new Evidence("unclassified", relative, "T5", "blocker", "",
						"File is inside a bounded probe root but has no reviewed role or policy.",
						state.size, state.sha256));
				} catch (WorldBuilderContractException unsafe) {
					result.add(new Evidence("unclassified", rootRelative, "T5", "blocker", "",
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
		WorldBuilderProviderCatalog.Composition composition,
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
		for (String key : Arrays.asList("platformReleaseId", "platformManifestHash",
			"schemaSetHash", "variantId", "variantManifestHash", "moduleSetHash",
			"bundleInventoryHash", "bundleSpecId", "bundleSpecHash", "inputAdapterContractId")) {
			destination.put(key, composition.string(key));
		}
		destination.put("installable", Boolean.valueOf(composition.installable));
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

	private static WorldBuilderContractException invalid(String operation, String message)
		throws WorldBuilderContractException {
		throw new WorldBuilderContractException(
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

		Map<String,Object> document() {
			return root;
		}
	}

	private static final class Evidence {
		final String role;
		final String relativePath;
		final String tier;
		final String disposition;
		final String moduleId;
		final String reason;
		final long size;
		final String sha256;

		Evidence(String role, String relativePath, String tier, String disposition, String moduleId,
			String reason, long size, String sha256) {
			this.role = role;
			this.relativePath = relativePath;
			this.tier = tier;
			this.disposition = disposition;
			this.moduleId = moduleId;
			this.reason = reason;
			this.size = size;
			this.sha256 = sha256;
		}

		Map<String,Object> toJson() {
			Map<String,Object> result = new LinkedHashMap<String,Object>();
			result.put("role", role); result.put("relativePath", relativePath);
			result.put("tier", tier); result.put("disposition", disposition);
			result.put("moduleId", moduleId);
			result.put("reason", reason); result.put("size", Long.valueOf(size));
			result.put("sha256", sha256);
			return result;
		}
	}
}
