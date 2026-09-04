package com.openrsc.worldbuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Transactional current-runtime upgrade engine behind the reviewed CLI surface. */
final class WorldBuilderCurrentRuntimeUpgradeTransaction {
	private static final String OPERATION = "current-runtime-upgrade";
	private static final String ZERO_HASH =
		"0000000000000000000000000000000000000000000000000000000000000000";
	private static final String LEDGER_RELATIVE = ".world-builder/runtime-ledger-v1.json";
	private static final String RELEASE_PREFIX = ".world-builder/current-runtime/releases/";
	private static final String[] OFFLINE_SENTINELS = {
		"server/run/server.pid", "server/run/world-builder.pid",
		"server/server.pid", "server/run/.server.lock"
	};

	interface Observer {
		void observe(String milestone, Path path) throws Exception;
	}

	private static final Observer NO_OP = new Observer() {
		@Override public void observe(String milestone, Path path) {
			// The test seam is inert outside injected regression harnesses.
		}
	};

	private final Observer observer;

	WorldBuilderCurrentRuntimeUpgradeTransaction() {
		this(NO_OP);
	}

	WorldBuilderCurrentRuntimeUpgradeTransaction(Observer observer) {
		this.observer = observer == null ? NO_OP : observer;
	}

	Preview preview(Path targetRoot, Path transactionRoot, Path providerCatalogRoot,
		Path compositionIdentity, Path inputAdapter, Path projectCapability,
		String transactionId) throws IOException, WorldBuilderContractException {
		WorldBuilderCurrentRuntimeContracts.Document adapter =
			WorldBuilderCurrentRuntimeContracts.read(
				WorldBuilderCurrentRuntimeContracts.Kind.INPUT_ADAPTER, inputAdapter);
		return previewInternal(targetRoot, transactionRoot, providerCatalogRoot,
			compositionIdentity, inputAdapter, projectCapability, transactionId,
			WorldBuilderCurrentRuntimeExecutionProfile.synthetic(adapter));
	}

	Preview previewPreservation(Path targetRoot, Path transactionRoot,
		Path providerCatalogRoot, Path compositionIdentity, Path projectCapability,
		String transactionId) throws IOException, WorldBuilderContractException {
		return previewInternal(targetRoot, transactionRoot, providerCatalogRoot,
			compositionIdentity, null, projectCapability, transactionId,
			WorldBuilderCurrentRuntimeExecutionProfile.preservation());
	}

	private Preview previewInternal(Path targetRoot, Path transactionRoot,
		Path providerCatalogRoot, Path compositionIdentity, Path inputAdapter,
		Path projectCapability, String transactionId,
		WorldBuilderCurrentRuntimeExecutionProfile profile)
		throws IOException, WorldBuilderContractException {
		validateTransactionId(transactionId);
		Path target = realDirectory(targetRoot, "target-root");
		Path workspace = realDirectory(transactionRoot, "transaction-root");
		if (workspace.startsWith(target) || target.startsWith(workspace)) throw problem(
			WorldBuilderErrorCodes.UNSAFE_PATH, "transaction-root", false,
			"Transaction staging must be outside the target and its active paths.",
			"Use a real sibling transaction directory on the target filesystem.");
		if (!Files.getFileStore(target).equals(Files.getFileStore(workspace))) throw problem(
			WorldBuilderErrorCodes.MUTATION_FAILED, "transaction-root", false,
			"Side-by-side publication requires staging on the target filesystem.",
			"Use an external sibling transaction directory on the same filesystem.");
		requireOffline(target);

		WorldBuilderProviderCatalog.Composition composition =
			WorldBuilderProviderCatalog.resolve(providerCatalogRoot, compositionIdentity);
		WorldBuilderCurrentRuntimeContracts.Document adapter = profile.adapter;
		WorldBuilderCurrentRuntimeContracts.Document project =
			WorldBuilderCurrentRuntimeContracts.read(
				WorldBuilderCurrentRuntimeContracts.Kind.PROJECT_CAPABILITY, projectCapability);
		if (!LEDGER_RELATIVE.equals(string(adapter.root, "targetLedgerRelativePath"))) {
			throw problem(WorldBuilderErrorCodes.UNSUPPORTED_ADAPTER,
				"targetLedgerRelativePath", false,
				"The synthetic executor requires its single compiled activation-ledger path.",
				"Use the reviewed synthetic adapter contract without path variation.");
		}
		WorldBuilderCurrentRuntimeContracts.Classification classified =
			WorldBuilderCurrentRuntimeContracts.classify(target, composition, adapter, project);
		Map<String,Object> classification = classified.document();
		String status = string(classification, "status");
		String tier = string(classification, "tier");
		boolean inspectOnly = !profile.syntheticOnly && "NOT_INSTALLABLE".equals(status);
		if (!("UPGRADE_READY".equals(status) || inspectOnly)
			|| !Arrays.asList("T0", "T1", "T2A", "T2B", "MANAGED_N").contains(tier)) {
			throw problem("NOT_INSTALLABLE".equals(status)
				? WorldBuilderErrorCodes.RUNTIME_UPGRADE_REQUIRED
				: WorldBuilderErrorCodes.CONVERSION_BLOCKED,
				"classification", false,
				"Current-runtime classification cannot authorize this bounded transaction: "
					+ status + "/" + tier + ".",
				"Resolve PORT_REQUIRED/T5 evidence or select an installable synthetic composition; there is no force mode.");
		}
		if (profile.syntheticOnly && !composition.installable) throw problem(
			WorldBuilderErrorCodes.RUNTIME_UPGRADE_REQUIRED, "destination", false,
			"A non-installable provider composition cannot authorize activation.",
			"Select a released installable bundle; inspection alone is not activation authority.");

		Map<String,Object> plan = buildPlan(target, workspace, composition, adapter,
			project, classification, transactionId, profile);
		return new Preview(target, workspace, providerCatalogRoot, compositionIdentity,
			inputAdapter, projectCapability, profile, plan);
	}

	Result apply(Preview reviewed, String confirmation)
		throws IOException, WorldBuilderContractException {
		if (reviewed == null) throw new IllegalArgumentException("reviewed");
		String expectedConfirmation = string(reviewed.plan, "confirmationIdentity");
		if (!expectedConfirmation.equals(confirmation)) throw problem(
			WorldBuilderErrorCodes.CONTRACT_VALUE_INVALID, "confirmation", false,
			"Upgrade confirmation does not exactly identify the reviewed transaction plan.",
			"Review a fresh preview and provide its complete confirmationIdentity.");
		if (!bool(reviewed.plan, "activationAuthorized")) throw problem(
			WorldBuilderErrorCodes.RUNTIME_UPGRADE_REQUIRED, "destination", false,
			bool(object(reviewed.plan.get("destination")), "installable")
				? string(object(reviewed.plan.get("executionProfile")),
					"executionReadinessReason")
				: "The reviewed provider composition is inspectable but not installable.",
			bool(object(reviewed.plan.get("destination")), "installable")
				? "Keep the target offline; production apply remains disabled until the compiled migrators and executable verifiers are implemented and tested."
				: "Wait for a released installable provider composition and preview again.");
		Preview fresh = refresh(reviewed);
		if (!fresh.fingerprint().equals(reviewed.fingerprint())) throw problem(
			WorldBuilderErrorCodes.TARGET_DRIFT, "upgrade-plan", false,
			"Target, provider, adapter, project, or transaction plan changed after preview.",
			"Review and confirm a fresh plan; there is no force mode.");

		Path transaction = transactionPath(reviewed.transactionRoot,
			string(reviewed.plan, "transactionId"));
		if (Files.exists(transaction, LinkOption.NOFOLLOW_LINKS)) throw problem(
			WorldBuilderErrorCodes.TARGET_DRIFT, "transaction-root", false,
			"The reviewed transaction identity is already in use.",
			"Preserve the existing evidence and request a new transaction identity.");
		Files.createDirectory(transaction);
		Path backup = transaction.resolve("backup");
		Path staging = transaction.resolve("staging");
		Path receipt = transaction.resolve("receipt.json");
		Path planPath = transaction.resolve("upgrade-plan.json");
		boolean releasePublished = false;
		boolean ledgerActivated = false;
		List<Path> createdTargetDirectories = new ArrayList<Path>();
		try {
			writeNew(planPath, reviewed.toJson());
			Files.createDirectory(backup);
			backupPreimage(reviewed, backup);
			writeReceipt(receipt, receipt(reviewed.plan, "pending", false,
				false, "", ""));
			observe("after-backup", backup);

			Files.createDirectory(staging);
			stageRelease(reviewed, staging);
			observe("after-staging", staging);
			fresh = refresh(reviewed);
			if (!fresh.fingerprint().equals(reviewed.fingerprint())) throw problem(
				WorldBuilderErrorCodes.TARGET_DRIFT, "upgrade-plan", false,
				"Target or authority changed after backup and staging.",
				"Keep the target offline and review a fresh transaction.");

			Path release = targetPath(reviewed.targetRoot,
				string(reviewed.plan, "releaseRelativePath"));
			ensureParents(reviewed.targetRoot, release.getParent(), createdTargetDirectories);
			moveNewDirectory(staging, release);
			releasePublished = true;
			observe("after-release-published", release);

			Path ledger = targetPath(reviewed.targetRoot, LEDGER_RELATIVE);
			ensureParents(reviewed.targetRoot, ledger.getParent(), createdTargetDirectories);
			writeActivationLedger(ledger, object(reviewed.plan.get("activationLedger")));
			ledgerActivated = true;
			observe("after-ledger-activated", ledger);
			verifyInstalled(reviewed.targetRoot, reviewed.plan);
			writeReceipt(receipt, receipt(reviewed.plan, "successful", true,
				true, string(reviewed.plan, "verificationEvidenceHash"), ""));
			return new Result(string(reviewed.plan, "transactionId"), "successful",
				receipt, release);
		} catch (Throwable failure) {
			try {
				observe("before-rollback", reviewed.targetRoot);
				rollback(reviewed.targetRoot, reviewed.plan, backup,
					releasePublished, ledgerActivated, createdTargetDirectories);
				observe("after-rollback", reviewed.targetRoot);
				writeReceipt(receipt, receipt(reviewed.plan, "rolled-back",
					releasePublished || ledgerActivated, true, "",
					failure.getClass().getName()));
			} catch (Throwable rollbackFailure) {
				try {
					writeReceipt(receipt, receipt(reviewed.plan, "recovery-required",
						releasePublished || ledgerActivated, false, "",
						rollbackFailure.getClass().getName()));
				} catch (Throwable receiptFailure) {
					rollbackFailure.addSuppressed(receiptFailure);
				}
				rollbackFailure.addSuppressed(failure);
				throw problem(WorldBuilderErrorCodes.RECOVERY_REQUIRED, "rollback", true,
					"Automatic rollback was interrupted; exact recovery evidence was preserved.",
					"Keep the target offline and run exact transaction recovery.", rollbackFailure);
			}
			if (failure instanceof WorldBuilderContractException) {
				throw (WorldBuilderContractException)failure;
			}
			if (failure instanceof IOException) throw (IOException)failure;
			throw problem(WorldBuilderErrorCodes.MUTATION_FAILED, "executor",
				releasePublished || ledgerActivated,
				"Current-runtime transaction was interrupted and rolled back.",
				"Review the preserved receipt and request a fresh preview.", failure);
		}
	}

	private Preview refresh(Preview preview)
		throws IOException, WorldBuilderContractException {
		if (preview.profile.syntheticOnly) return preview(preview.targetRoot,
			preview.transactionRoot, preview.providerCatalogRoot,
			preview.compositionIdentity, preview.inputAdapter,
			preview.projectCapability, string(preview.plan, "transactionId"));
		return previewPreservation(preview.targetRoot, preview.transactionRoot,
			preview.providerCatalogRoot, preview.compositionIdentity,
			preview.projectCapability, string(preview.plan, "transactionId"));
	}

	Result recover(Path targetRoot, Path transactionRoot, String transactionId)
		throws IOException, WorldBuilderContractException {
		validateTransactionId(transactionId);
		Path target = realDirectory(targetRoot, "target-root");
		Path workspace = realDirectory(transactionRoot, "transaction-root");
		if (workspace.startsWith(target) || target.startsWith(workspace)
			|| !Files.getFileStore(target).equals(Files.getFileStore(workspace))) {
			throw problem(WorldBuilderErrorCodes.UNSAFE_PATH, "transaction-root", true,
				"Recovery transaction evidence is not in an external same-filesystem directory.",
				"Restore the exact sibling transaction directory used by preview.");
		}
		requireOffline(target);
		Path transaction = transactionPath(workspace, transactionId);
		Path planPath = safeExistingFile(transaction, "upgrade-plan.json");
		Map<String,Object> plan;
		try {
			plan = WorldBuilderJsonDocuments.readObject(planPath);
		} catch (WorldBuilderDiscoveryException malformed) {
			throw problem(WorldBuilderErrorCodes.RECOVERY_REQUIRED, "upgrade-plan", true,
				"Recovery plan is malformed.", "Restore the exact transaction evidence.", malformed);
		}
		validatePlanFingerprint(plan);
		validateRecoveryPlan(plan);
		if (!transactionId.equals(string(plan, "transactionId"))) throw problem(
			WorldBuilderErrorCodes.RECOVERY_REQUIRED, "transactionId", true,
			"Recovery transaction identity does not match its directory.",
			"Restore the exact transaction evidence.");
		Path backup = transaction.resolve("backup");
		Map<String,Object> priorReceipt;
		try {
			priorReceipt = WorldBuilderJsonDocuments.readObject(
				safeExistingFile(transaction, "receipt.json"));
		} catch (WorldBuilderDiscoveryException malformed) {
			throw problem(WorldBuilderErrorCodes.RECOVERY_REQUIRED, "receipt", true,
				"Recovery receipt is malformed.", "Restore the exact receipt.", malformed);
		}
		validateReceiptFingerprint(priorReceipt);
		if (!"recovery-required".equals(string(priorReceipt, "status"))
			|| !string(plan, "planFingerprintSha256").equals(
				string(priorReceipt, "planFingerprintSha256"))) throw problem(
			WorldBuilderErrorCodes.RECOVERY_REQUIRED, "receipt", true,
			"Recovery receipt does not authorize this exact failed plan.",
			"Restore the exact recovery-required receipt and plan.");
		try {
			rollback(target, plan, backup, true, true, Collections.<Path>emptyList());
		} catch (WorldBuilderContractException failure) {
			throw failure;
		} catch (IOException failure) {
			throw failure;
		} catch (Exception interrupted) {
			throw problem(WorldBuilderErrorCodes.RECOVERY_REQUIRED, "rollback", true,
				"Exact recovery was interrupted.",
				"Keep the target offline and retry exact recovery.", interrupted);
		}
		verifyPreimage(target, plan);
		Path receipt = transaction.resolve("receipt.json");
		writeReceipt(receipt, receipt(plan, "rolled-back", true, true, "",
			"recovered-exact-preimage"));
		return new Result(transactionId, "rolled-back", receipt, null);
	}

	boolean mapImportAvailable(Path targetRoot, Path providerCatalogRoot,
		Path compositionIdentity, Path inputAdapter, Path projectCapability)
		throws IOException, WorldBuilderContractException {
		try {
			return mapImportAvailableChecked(targetRoot, providerCatalogRoot,
				compositionIdentity, inputAdapter, projectCapability);
		} catch (IOException unavailable) {
			return false;
		} catch (WorldBuilderContractException invalid) {
			return false;
		}
	}

	boolean mapImportAvailablePreservation(Path targetRoot, Path providerCatalogRoot,
		Path compositionIdentity, Path projectCapability) {
		try {
			return mapImportAvailableChecked(targetRoot, providerCatalogRoot,
				compositionIdentity, null, projectCapability);
		} catch (IOException unavailable) {
			return false;
		} catch (WorldBuilderContractException invalid) {
			return false;
		}
	}

	private boolean mapImportAvailableChecked(Path targetRoot,
		Path providerCatalogRoot, Path compositionIdentity, Path inputAdapter,
		Path projectCapability) throws IOException, WorldBuilderContractException {
		WorldBuilderProviderCatalog.Composition composition =
			WorldBuilderProviderCatalog.resolve(providerCatalogRoot, compositionIdentity);
		if (!composition.installable) return false;
		WorldBuilderCurrentRuntimeExecutionProfile profile;
		if (inputAdapter == null) profile = WorldBuilderCurrentRuntimeExecutionProfile.preservation();
		else profile = WorldBuilderCurrentRuntimeExecutionProfile.synthetic(
			WorldBuilderCurrentRuntimeContracts.read(
				WorldBuilderCurrentRuntimeContracts.Kind.INPUT_ADAPTER, inputAdapter));
		WorldBuilderCurrentRuntimeContracts.Document adapter = profile.adapter;
		WorldBuilderCurrentRuntimeContracts.Document project =
			WorldBuilderCurrentRuntimeContracts.read(
				WorldBuilderCurrentRuntimeContracts.Kind.PROJECT_CAPABILITY, projectCapability);
		WorldBuilderCurrentRuntimeContracts.Classification classification =
			WorldBuilderCurrentRuntimeContracts.classify(targetRoot, composition, adapter, project);
		if (!"CURRENT".equals(classification.status())) return false;
		if (!LEDGER_RELATIVE.equals(string(adapter.root, "targetLedgerRelativePath")))
			return false;
		Path target = realDirectory(targetRoot, "target-root");
		WorldBuilderCurrentRuntimeContracts.Document ledger =
			WorldBuilderCurrentRuntimeContracts.read(
				WorldBuilderCurrentRuntimeContracts.Kind.TARGET_LEDGER,
				safeExistingFile(target, LEDGER_RELATIVE));
		String releaseRelative = RELEASE_PREFIX + composition.string("bundleInventoryHash");
		String activationRelative = releaseRelative + "/activation.json";
		if (!activationRelative.equals(string(ledger.root,
			"activeLauncherRelativePath"))) return false;
		if (!profile.serverBuildId.equals(string(ledger.root, "serverBuildId"))
			|| !profile.clientBuildId.equals(string(ledger.root, "clientBuildId"))
			|| !profile.mapPackageId.equals(
				string(ledger.root, "activeMapPackageId"))) return false;
		Map<String,Object> activation = readObject(
			safeExistingFile(target, activationRelative), activationRelative);
		validateActivation(activation, composition, adapter.root, project.root, ledger.root);
		verifyProviderReleaseTree(target, releaseRelative, composition.artifacts,
			activation, object(activation.get("migrationPlan")));
		return true;
	}

	private Map<String,Object> buildPlan(Path target, Path workspace,
		WorldBuilderProviderCatalog.Composition composition,
		WorldBuilderCurrentRuntimeContracts.Document adapter,
		WorldBuilderCurrentRuntimeContracts.Document project,
		Map<String,Object> classification, String transactionId,
		WorldBuilderCurrentRuntimeExecutionProfile profile)
		throws IOException, WorldBuilderContractException {
		Map<String,Object> plan = new LinkedHashMap<String,Object>();
		plan.put("schemaVersion", Long.valueOf(1));
		plan.put("manifestType", "world-builder-current-runtime-upgrade-plan");
		plan.put("transactionId", transactionId);
		plan.put("classificationStatus", string(classification, "status"));
		plan.put("classificationTier", string(classification, "tier"));
		plan.put("classificationFingerprintSha256",
			string(classification, "classificationFingerprintSha256"));
		Map<String,Object> adapterReference = new LinkedHashMap<String,Object>();
		adapterReference.put("adapterId", string(adapter.root, "adapterId"));
		adapterReference.put("adapterManifestHash", string(adapter.root, "adapterManifestHash"));
		adapterReference.put("inputAdapterContractId",
			composition.string("inputAdapterContractId"));
		adapterReference.put("evidenceAuthority", string(adapter.root, "evidenceAuthority"));
		plan.put("inputAdapter", adapterReference);
		plan.put("executionProfile", profile.identity());
		plan.put("migrationPlan", profile.migrationPlan(target, classification));
		Map<String,Object> projectReference = new LinkedHashMap<String,Object>();
		projectReference.put("projectId", string(project.root, "projectId"));
		projectReference.put("capabilityFingerprintSha256",
			string(project.root, "capabilityFingerprintSha256"));
		plan.put("projectCapability", projectReference);
		plan.put("destination", copyObject(classification.get("destination")));

		List<Object> preimage = preimageInventory(target, classification, adapter.root);
		plan.put("preimageInventory", preimage);
		plan.put("preimageInventoryHash", canonicalHash(preimage));
		List<Object> semantic = semanticActions(classification);
		plan.put("semanticActions", semantic);
		plan.put("semanticActionsHash", canonicalHash(semantic));
		List<Object> artifacts = new ArrayList<Object>();
		for (WorldBuilderProviderCatalog.Artifact artifact : composition.artifacts) {
			Map<String,Object> action = new LinkedHashMap<String,Object>();
			action.put("sourcePath", artifact.sourcePath);
			action.put("bundlePath", artifact.bundlePath);
			action.put("installRelativePath", RELEASE_PREFIX
				+ composition.string("bundleInventoryHash") + "/" + artifact.bundlePath);
			action.put("mode", string(artifact.inventory, "mode"));
			action.put("size", artifact.inventory.get("size"));
			action.put("sha256", string(artifact.inventory, "sha256"));
			artifacts.add(action);
		}
		plan.put("artifactPlan", artifacts);
		plan.put("artifactPlanHash", canonicalHash(artifacts));
		String releaseRelative = RELEASE_PREFIX + composition.string("bundleInventoryHash");
		plan.put("releaseRelativePath", releaseRelative);
		plan.put("stagingPolicy", "external-same-filesystem-outside-active-target");
		plan.put("activationLedgerRelativePath", LEDGER_RELATIVE);
		String activationPlanBindingHash = activationPlanBindingHash(plan);
		Map<String,Object> ledger = activationLedger(target, composition, adapter,
			project, classification, preimage, semantic, canonicalHash(artifacts),
			activationPlanBindingHash, transactionId, releaseRelative, profile);
		plan.put("activationLedger", ledger);
		plan.put("verificationEvidenceHash", string(ledger, "verificationEvidenceHash"));
		plan.put("mapImportAvailableBeforeApply", Boolean.FALSE);
		plan.put("mutationOccurred", Boolean.FALSE);
		plan.put("activationAuthorized", Boolean.valueOf(composition.installable
			&& "UPGRADE_READY".equals(string(classification, "status"))
			&& profile.executionReady));
		plan.put("confirmationIdentity", "UPGRADE:" + transactionId + ":"
			+ string(classification, "classificationFingerprintSha256") + ":"
			+ canonicalHash(artifacts));
		plan.put("planFingerprintSha256", ZERO_HASH);
		bindFingerprint(plan, "planFingerprintSha256");
		return plan;
	}

	private static List<Object> preimageInventory(Path target,
		Map<String,Object> classification, Map<String,Object> adapter)
		throws WorldBuilderContractException {
		Map<String,Map<String,Object>> records = new LinkedHashMap<String,Map<String,Object>>();
		for (Object raw : array(classification.get("evidence"))) {
			Map<String,Object> evidence = object(raw);
			String relative = string(evidence, "relativePath");
			if (string(evidence, "sha256").isEmpty()) continue;
			Map<String,Object> record = new LinkedHashMap<String,Object>();
			record.put("relativePath", relative); record.put("present", Boolean.TRUE);
			record.put("size", evidence.get("size"));
			record.put("sha256", evidence.get("sha256"));
			record.put("backupRelativePath", "files/" + relative);
			records.put(relative, record);
		}
		String ledgerRelative = string(adapter, "targetLedgerRelativePath");
		WorldBuilderReadOnlyTarget readOnly = WorldBuilderReadOnlyTarget.open(target);
		WorldBuilderReadOnlyTarget.FileState ledger =
			readOnly.optionalState("target-ledger", ledgerRelative);
		Map<String,Object> ledgerRecord = new LinkedHashMap<String,Object>();
		ledgerRecord.put("relativePath", ledgerRelative);
		ledgerRecord.put("present", Boolean.valueOf(ledger.present));
		ledgerRecord.put("size", Long.valueOf(ledger.size));
		ledgerRecord.put("sha256", ledger.sha256);
		ledgerRecord.put("backupRelativePath", ledger.present
			? "files/" + ledgerRelative : "");
		records.put(ledgerRelative, ledgerRecord);
		List<String> paths = new ArrayList<String>(records.keySet());
		Collections.sort(paths);
		List<Object> result = new ArrayList<Object>();
		for (String path : paths) result.add(records.get(path));
		return result;
	}

	private static List<Object> semanticActions(Map<String,Object> classification)
		throws WorldBuilderContractException {
		List<Object> result = new ArrayList<Object>();
		for (Object raw : array(classification.get("evidence"))) {
			Map<String,Object> evidence = object(raw);
			Map<String,Object> action = new LinkedHashMap<String,Object>();
			action.put("relativePath", string(evidence, "relativePath"));
			action.put("tier", string(evidence, "tier"));
			action.put("disposition", string(evidence, "disposition"));
			action.put("moduleId", string(evidence, "moduleId"));
			action.put("sourceSha256", string(evidence, "sha256"));
			action.put("execution", "preserve-preimage-and-activate-independent-current-bundle");
			result.add(action);
		}
		if (result.isEmpty()) {
			Map<String,Object> action = new LinkedHashMap<String,Object>();
			action.put("relativePath", LEDGER_RELATIVE);
			action.put("tier", "MANAGED_N"); action.put("disposition", "replace-ledger");
			action.put("moduleId", ""); action.put("sourceSha256", "");
			action.put("execution", "advance-side-by-side-composition-and-activate-last");
			result.add(action);
		}
		return result;
	}

	private static Map<String,Object> activationLedger(Path target,
		WorldBuilderProviderCatalog.Composition composition,
		WorldBuilderCurrentRuntimeContracts.Document adapter,
		WorldBuilderCurrentRuntimeContracts.Document project,
		Map<String,Object> classification, List<Object> preimage, List<Object> semantic,
		String artifactPlanHash, String activationPlanBindingHash,
		String transactionId, String releaseRelative,
		WorldBuilderCurrentRuntimeExecutionProfile profile)
		throws WorldBuilderContractException {
		Map<String,Object> ledger = new LinkedHashMap<String,Object>();
		ledger.put("schemaVersion", Long.valueOf(1));
		ledger.put("manifestType", "world-builder-current-target-runtime-ledger");
		String seed = string(project.root, "projectId") + ":" + canonicalHash(preimage);
		ledger.put("targetInstallationId", UUID.nameUUIDFromBytes(
			seed.getBytes(StandardCharsets.UTF_8)).toString());
		for (String key : Arrays.asList("platformReleaseId", "platformManifestHash",
			"schemaSetHash", "variantId", "variantManifestHash", "moduleSetHash",
			"bundleInventoryHash", "bundleSpecId", "bundleSpecHash",
			"inputAdapterContractId")) ledger.put(key, composition.string(key));
		ledger.put("inputAdapterId", string(adapter.root, "adapterId"));
		Map<String,Object> installed = object(classification.get("installedLedger"));
		String predecessor = string(installed, "ledgerFingerprintSha256");
		ledger.put("predecessorIdentityHash", predecessor.isEmpty()
			? canonicalHash(preimage) : predecessor);
		Set<String> configurations = new HashSet<String>();
		Set<String> states = new HashSet<String>();
		for (Object raw : semantic) {
			String disposition = string(object(raw), "disposition");
			if ("typed-configuration".equals(disposition))
				configurations.add(profile.configurationMigrationId);
			if ("canonical-data".equals(disposition))
				states.add(profile.stateMigrationId);
			if ("canonical-map".equals(disposition) || "replace".equals(disposition))
				states.add(profile.mapMigrationId);
		}
		List<String> configIds = new ArrayList<String>(configurations);
		List<String> stateIds = new ArrayList<String>(states);
		Collections.sort(configIds); Collections.sort(stateIds);
		ledger.put("configurationMigrationIds", new ArrayList<Object>(configIds));
		ledger.put("stateMigrationIds", new ArrayList<Object>(stateIds));
		ledger.put("serverBuildId", profile.serverBuildId);
		ledger.put("clientBuildId", profile.clientBuildId);
		ledger.put("activeLauncherRelativePath", releaseRelative + "/activation.json");
		ledger.put("activeMapPackageId", profile.mapPackageId);
		Map<String,Object> verification = new LinkedHashMap<String,Object>();
		verification.put("classificationFingerprintSha256",
			string(classification, "classificationFingerprintSha256"));
		verification.put("projectCapabilityFingerprintSha256",
			string(project.root, "capabilityFingerprintSha256"));
		verification.put("adapterManifestHash", string(adapter.root, "adapterManifestHash"));
		verification.put("artifactPlanHash", artifactPlanHash);
		verification.put("semanticActionsHash", canonicalHash(semantic));
		verification.put("planBindingHash", activationPlanBindingHash);
		ledger.put("verificationEvidenceHash", canonicalHash(verification));
		List<String> receipts = new ArrayList<String>();
		if (bool(installed, "present")) {
			try {
				WorldBuilderCurrentRuntimeContracts.Document old =
					WorldBuilderCurrentRuntimeContracts.read(
						WorldBuilderCurrentRuntimeContracts.Kind.TARGET_LEDGER,
						targetPath(target, LEDGER_RELATIVE));
				for (Object raw : array(old.root.get("transactionReceiptIds")))
					receipts.add((String)raw);
			} catch (IOException failure) {
				throw problem(WorldBuilderErrorCodes.TARGET_DRIFT, LEDGER_RELATIVE, false,
					"Managed predecessor ledger changed during plan construction.",
					"Keep the target offline and retry preview.", failure);
			}
		}
		receipts.add(transactionId); Collections.sort(receipts);
		ledger.put("transactionReceiptIds", new ArrayList<Object>(receipts));
		ledger.put("ledgerFingerprintSha256", ZERO_HASH);
		bindFingerprint(ledger, "ledgerFingerprintSha256");
		return ledger;
	}

	private void backupPreimage(Preview preview, Path backup)
		throws IOException, WorldBuilderContractException {
		for (Object raw : array(preview.plan.get("preimageInventory"))) {
			Map<String,Object> record = object(raw);
			if (!bool(record, "present")) continue;
			String relative = string(record, "relativePath");
			Path source = safeExistingFile(preview.targetRoot, relative);
			requireFileMatches(source, record, relative);
			Path destination = targetPath(backup, string(record, "backupRelativePath"));
			Files.createDirectories(destination.getParent());
			Files.copy(source, destination, StandardCopyOption.COPY_ATTRIBUTES);
			requireFileMatches(destination, record, relative);
		}
		writeNew(backup.resolve("preimage-inventory.json"),
			WorldBuilderJsonDocuments.pretty(preview.plan.get("preimageInventory")));
	}

	private void stageRelease(Preview preview, Path staging)
		throws IOException, WorldBuilderContractException {
		WorldBuilderProviderCatalog.Composition composition =
			WorldBuilderProviderCatalog.resolve(preview.providerCatalogRoot,
				preview.compositionIdentity);
		Map<String,WorldBuilderProviderCatalog.Artifact> byBundle =
			new LinkedHashMap<String,WorldBuilderProviderCatalog.Artifact>();
		for (WorldBuilderProviderCatalog.Artifact artifact : composition.artifacts)
			byBundle.put(artifact.bundlePath, artifact);
		for (Object raw : array(preview.plan.get("artifactPlan"))) {
			Map<String,Object> action = object(raw);
			String bundlePath = string(action, "bundlePath");
			WorldBuilderProviderCatalog.Artifact artifact = byBundle.get(bundlePath);
			if (artifact == null) throw problem(WorldBuilderErrorCodes.SOURCE_CORRUPT,
				bundlePath, false, "Provider artifact disappeared after preview.",
				"Restore the exact provider and request a fresh preview.");
			requireFileMatches(artifact.source, action, artifact.sourcePath);
			Path destination = targetPath(staging, bundlePath);
			Files.createDirectories(destination.getParent());
			Files.copy(artifact.source, destination);
			setMode(destination, string(action, "mode"));
			requireFileMatches(destination, action, bundlePath);
		}
		Map<String,Object> activation = activationDocument(preview.plan);
		writeNew(staging.resolve("activation.json"),
			WorldBuilderJsonDocuments.pretty(activation));
		Files.createDirectory(staging.resolve("migration"));
		writeNew(staging.resolve("migration/migration-plan.json"),
			WorldBuilderJsonDocuments.pretty(preview.plan.get("migrationPlan")));
		verifyProviderReleaseTree(staging, "", composition.artifacts, activation,
			object(preview.plan.get("migrationPlan")));
	}

	private static void writeActivationLedger(Path ledger, Map<String,Object> document)
		throws IOException, WorldBuilderContractException {
		Path temporary = ledger.getParent().resolve(".runtime-ledger-v1.json.upgrade");
		if (Files.exists(temporary, LinkOption.NOFOLLOW_LINKS)) throw problem(
			WorldBuilderErrorCodes.TARGET_DRIFT, LEDGER_RELATIVE, false,
			"A prior activation staging file exists.",
			"Preserve and review the existing transaction evidence.");
		writeNew(temporary, WorldBuilderJsonDocuments.pretty(document));
		try {
			Files.move(temporary, ledger, StandardCopyOption.ATOMIC_MOVE,
				StandardCopyOption.REPLACE_EXISTING);
		} catch (AtomicMoveNotSupportedException unsupported) {
			Files.deleteIfExists(temporary);
			throw problem(WorldBuilderErrorCodes.MUTATION_FAILED, LEDGER_RELATIVE, true,
				"Filesystem cannot atomically activate the target ledger.",
				"Use a local filesystem with atomic same-directory replacement.", unsupported);
		}
	}

	private static void verifyInstalled(Path target, Map<String,Object> plan)
		throws IOException, WorldBuilderContractException {
		Path ledger = safeExistingFile(target, LEDGER_RELATIVE);
		Map<String,Object> expected = object(plan.get("activationLedger"));
		Map<String,Object> actual;
		try {
			actual = WorldBuilderJsonDocuments.readObject(ledger);
		} catch (WorldBuilderDiscoveryException malformed) {
			throw problem(WorldBuilderErrorCodes.MUTATION_FAILED, LEDGER_RELATIVE, true,
				"Activated ledger cannot be reread.", "Run exact recovery.", malformed);
		}
		if (!WorldBuilderJsonDocuments.canonical(expected).equals(
			WorldBuilderJsonDocuments.canonical(actual))) throw problem(
			WorldBuilderErrorCodes.MUTATION_FAILED, LEDGER_RELATIVE, true,
			"Activated ledger differs from the reviewed plan.", "Run exact recovery.");
		verifyOwnedReleaseTree(target, plan);
	}

	private void rollback(Path target, Map<String,Object> plan, Path backup,
		boolean releasePublished, boolean ledgerActivated,
		List<Path> createdTargetDirectories) throws Exception {
		observe("during-rollback", target);
		Map<String,Object> ledgerRecord = null;
		for (Object raw : array(plan.get("preimageInventory"))) {
			Map<String,Object> record = object(raw);
			if (LEDGER_RELATIVE.equals(string(record, "relativePath"))) ledgerRecord = record;
		}
		if (ledgerRecord == null) throw new IOException("ledger preimage missing");
		Path ledger = targetPath(target, LEDGER_RELATIVE);
		Path release = targetPath(target, string(plan, "releaseRelativePath"));
		int ledgerState = ledgerActivated
			? requireRollbackLedgerState(ledger, ledgerRecord,
				object(plan.get("activationLedger"))) : 0;
		boolean releaseExists = releasePublished
			&& Files.exists(release, LinkOption.NOFOLLOW_LINKS);
		if (releaseExists) {
			try {
				verifyOwnedReleaseTree(target, plan);
			} catch (IOException drift) {
				throw recoveryDrift("release", drift);
			} catch (WorldBuilderContractException drift) {
				throw recoveryDrift("release", drift);
			}
		}

		if (ledgerState == 1 && bool(ledgerRecord, "present")) {
			requireRollbackLedgerState(ledger, ledgerRecord,
				object(plan.get("activationLedger")));
			Path source = safeExistingFile(backup, string(ledgerRecord, "backupRelativePath"));
			requireFileMatches(source, ledgerRecord, LEDGER_RELATIVE);
			Files.createDirectories(ledger.getParent());
			Path temporary = ledger.getParent().resolve(".runtime-ledger-v1.json.rollback");
			Files.copy(source, temporary);
			Files.move(temporary, ledger, StandardCopyOption.ATOMIC_MOVE,
				StandardCopyOption.REPLACE_EXISTING);
		} else if (ledgerState == 1) {
			requireRollbackLedgerState(ledger, ledgerRecord,
				object(plan.get("activationLedger")));
			Files.delete(ledger);
		}
		if (releaseExists) {
			verifyOwnedReleaseTree(target, plan);
			deleteOwnedTree(release);
		}
		List<Path> reversed = new ArrayList<Path>(createdTargetDirectories);
		Collections.reverse(reversed);
		for (Path directory : reversed) if (Files.isDirectory(directory,
			LinkOption.NOFOLLOW_LINKS) && isEmpty(directory)) Files.delete(directory);
		Path parent = release.getParent();
		while (parent != null && !parent.equals(target)) {
			if (!Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)
				|| Files.isSymbolicLink(parent) || !isEmpty(parent)) break;
			Files.delete(parent); parent = parent.getParent();
		}
		verifyPreimage(target, plan);
	}

	private static void verifyPreimage(Path target, Map<String,Object> plan)
		throws IOException, WorldBuilderContractException {
		for (Object raw : array(plan.get("preimageInventory"))) {
			Map<String,Object> record = object(raw);
			Path path = targetPath(target, string(record, "relativePath"));
			if (!bool(record, "present")) {
				if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException(
					"absent preimage was not restored: " + path.getFileName());
			} else {
				requireFileMatches(safeExistingFile(target,
					string(record, "relativePath")), record,
					string(record, "relativePath"));
			}
		}
	}

	/** Returns 1 for exact planned activation and 2 for exact preimage. */
	private static int requireRollbackLedgerState(Path ledger,
		Map<String,Object> preimage, Map<String,Object> activation)
		throws IOException, WorldBuilderContractException {
		if (!Files.exists(ledger, LinkOption.NOFOLLOW_LINKS)) {
			if (!bool(preimage, "present")) return 2;
			throw recoveryDrift("ledger", new IOException("expected preimage ledger is missing"));
		}
		BasicFileAttributes attributes = Files.readAttributes(ledger,
			BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
		if (!attributes.isRegularFile() || attributes.isSymbolicLink()) throw recoveryDrift(
			"ledger", new IOException("ledger is linked or non-regular"));
		if (bool(preimage, "present")
			&& attributes.size() == integer(preimage, "size")
			&& WorldBuilderHashes.sha256(ledger).equals(string(preimage, "sha256"))) return 2;
		byte[] expectedActivation = WorldBuilderJsonDocuments.pretty(activation)
			.getBytes(StandardCharsets.UTF_8);
		if (attributes.size() == expectedActivation.length
			&& WorldBuilderHashes.sha256(ledger).equals(
				WorldBuilderHashes.sha256(expectedActivation))) return 1;
		throw recoveryDrift("ledger", new IOException(
			"ledger is neither exact planned activation nor exact preimage"));
	}

	private static WorldBuilderContractException recoveryDrift(String relative,
		Throwable cause) {
		return problem(WorldBuilderErrorCodes.RECOVERY_REQUIRED, relative, true,
			"Rollback found target state outside the exact transaction-owned activation or preimage.",
			"Preserve the drifted target and transaction evidence for reviewed recovery; no force cleanup is allowed.",
			cause);
	}

	private static Map<String,Object> activationDocument(Map<String,Object> plan)
		throws WorldBuilderContractException {
		Map<String,Object> activation = new LinkedHashMap<String,Object>();
		activation.put("schemaVersion", Long.valueOf(1));
		activation.put("manifestType", "world-builder-synthetic-current-activation");
		activation.put("transactionId", string(plan, "transactionId"));
		activation.put("planBindingHash", activationPlanBindingHash(plan));
		activation.put("classificationFingerprintSha256",
			string(plan, "classificationFingerprintSha256"));
		activation.put("preimageInventoryHash", string(plan, "preimageInventoryHash"));
		activation.put("destination", plan.get("destination"));
		activation.put("projectCapability", plan.get("projectCapability"));
		activation.put("inputAdapter", plan.get("inputAdapter"));
		activation.put("executionProfile", plan.get("executionProfile"));
		activation.put("migrationPlan", plan.get("migrationPlan"));
		activation.put("artifactPlanHash", string(plan, "artifactPlanHash"));
		activation.put("semanticActionsHash", string(plan, "semanticActionsHash"));
		activation.put("verificationEvidenceHash",
			string(plan, "verificationEvidenceHash"));
		Map<String,Object> ledger = object(plan.get("activationLedger"));
		activation.put("serverBuildId", string(ledger, "serverBuildId"));
		activation.put("clientBuildId", string(ledger, "clientBuildId"));
		activation.put("activeMapPackageId", string(ledger, "activeMapPackageId"));
		activation.put("syntheticOnly", object(plan.get("executionProfile")).get("syntheticOnly"));
		return activation;
	}

	private static String activationPlanBindingHash(Map<String,Object> source)
		throws WorldBuilderContractException {
		Map<String,Object> binding = new LinkedHashMap<String,Object>();
		binding.put("transactionId", string(source, "transactionId"));
		binding.put("classificationFingerprintSha256",
			string(source, "classificationFingerprintSha256"));
		binding.put("preimageInventoryHash", string(source, "preimageInventoryHash"));
		binding.put("semanticActionsHash", string(source, "semanticActionsHash"));
		binding.put("artifactPlanHash", string(source, "artifactPlanHash"));
		binding.put("destination", source.get("destination"));
		binding.put("projectCapability", source.get("projectCapability"));
		binding.put("inputAdapter", source.get("inputAdapter"));
		binding.put("executionProfile", source.get("executionProfile"));
		binding.put("migrationPlan", source.get("migrationPlan"));
		Map<String,Object> destination = object(source.get("destination"));
		binding.put("releaseRelativePath", RELEASE_PREFIX
			+ string(destination, "bundleInventoryHash"));
		return canonicalHash(binding);
	}

	private static void validateActivation(Map<String,Object> activation,
		WorldBuilderProviderCatalog.Composition composition,
		Map<String,Object> adapter, Map<String,Object> project,
		Map<String,Object> ledger) throws WorldBuilderContractException {
		WorldBuilderBoundedInventory.exactKeys(activation, OPERATION,
			"schemaVersion", "manifestType", "transactionId", "planBindingHash",
			"classificationFingerprintSha256", "preimageInventoryHash",
			"destination", "projectCapability",
			"inputAdapter", "artifactPlanHash",
			"semanticActionsHash", "verificationEvidenceHash", "serverBuildId",
			"clientBuildId", "activeMapPackageId", "syntheticOnly",
			"executionProfile", "migrationPlan");
		Map<String,Object> executionProfile = object(activation.get("executionProfile"));
		WorldBuilderCurrentRuntimeExecutionProfile compiledProfile =
			WorldBuilderCurrentRuntimeExecutionProfile.fromIdentity(executionProfile);
		compiledProfile.validateMigrationPlan(object(activation.get("migrationPlan")));
		if (integer(activation, "schemaVersion") != 1L
			|| !string(executionProfile, "activationManifestType").equals(
				string(activation, "manifestType"))
			|| bool(executionProfile, "syntheticOnly") != bool(activation, "syntheticOnly")) throw problem(
			WorldBuilderErrorCodes.CAPABILITY_MISMATCH, "activation.json", false,
			"Installed activation marker has no exact synthetic identity.",
			"Keep map import disabled and recover/reinstall the exact composition.");
		validateTransactionId(string(activation, "transactionId"));
		requireHash(string(activation, "planBindingHash"), "planBindingHash");
		requireHash(string(activation, "classificationFingerprintSha256"),
			"classificationFingerprintSha256");
		requireHash(string(activation, "preimageInventoryHash"),
			"preimageInventoryHash");
		requireHash(string(activation, "artifactPlanHash"), "artifactPlanHash");
		requireHash(string(activation, "semanticActionsHash"), "semanticActionsHash");
		if (!providerArtifactPlanHash(composition).equals(
			string(activation, "artifactPlanHash")))
			throw activationMismatch("artifactPlanHash");
		Map<String,Object> destination = object(activation.get("destination"));
		WorldBuilderBoundedInventory.exactKeys(destination, OPERATION,
			"platformReleaseId", "platformManifestHash", "schemaSetHash", "variantId",
			"variantManifestHash", "moduleSetHash", "bundleInventoryHash",
			"bundleSpecId", "bundleSpecHash", "inputAdapterContractId", "installable");
		for (String field : Arrays.asList("platformReleaseId", "platformManifestHash",
			"schemaSetHash", "variantId", "variantManifestHash", "moduleSetHash",
			"bundleInventoryHash", "bundleSpecId", "bundleSpecHash",
			"inputAdapterContractId")) if (!composition.string(field).equals(
				string(destination, field))) throw activationMismatch(field);
		if (!bool(destination, "installable")) throw activationMismatch("installable");
		Map<String,Object> projectReference = object(activation.get("projectCapability"));
		WorldBuilderBoundedInventory.exactKeys(projectReference, OPERATION,
			"projectId", "capabilityFingerprintSha256");
		if (!string(project, "projectId").equals(string(projectReference, "projectId"))
			|| !string(project, "capabilityFingerprintSha256").equals(
				string(projectReference, "capabilityFingerprintSha256")))
			throw activationMismatch("projectCapability");
		Map<String,Object> adapterReference = object(activation.get("inputAdapter"));
		WorldBuilderBoundedInventory.exactKeys(adapterReference, OPERATION,
			"adapterId", "adapterManifestHash", "inputAdapterContractId",
			"evidenceAuthority");
		if (!string(adapter, "adapterId").equals(string(adapterReference, "adapterId"))
			|| !string(adapter, "adapterManifestHash").equals(
				string(adapterReference, "adapterManifestHash"))
			|| !composition.string("inputAdapterContractId").equals(
				string(adapterReference, "inputAdapterContractId"))
			|| !string(adapter, "evidenceAuthority").equals(
				string(adapterReference, "evidenceAuthority")))
			throw activationMismatch("inputAdapter");
		if (compiledProfile.syntheticOnly
				!= "synthetic-fixture".equals(string(adapter, "evidenceAuthority"))
			|| !compiledProfile.syntheticOnly
				&& !WorldBuilderCurrentRuntimeExecutionProfile.PRESERVATION_ADAPTER_ID.equals(
					string(adapter, "adapterId"))) throw activationMismatch("executionProfile");
		if (!string(executionProfile, "serverBuildId").equals(
				string(ledger, "serverBuildId"))
			|| !string(executionProfile, "clientBuildId").equals(
					string(ledger, "clientBuildId"))
			|| !string(executionProfile, "mapPackageId").equals(
					string(ledger, "activeMapPackageId"))
			|| !string(executionProfile, "migratorId").equals(
					string(object(activation.get("migrationPlan")), "migratorId")))
			throw activationMismatch("executionProfile");
		for (String field : Arrays.asList("verificationEvidenceHash", "serverBuildId",
			"clientBuildId", "activeMapPackageId")) if (!string(ledger, field).equals(
				string(activation, field))) throw activationMismatch(field);
		if (!array(ledger.get("transactionReceiptIds")).contains(
			string(activation, "transactionId"))) throw activationMismatch("transactionId");
		Map<String,Object> verification = new LinkedHashMap<String,Object>();
		verification.put("classificationFingerprintSha256",
			string(activation, "classificationFingerprintSha256"));
		verification.put("projectCapabilityFingerprintSha256",
			string(projectReference, "capabilityFingerprintSha256"));
		verification.put("adapterManifestHash",
			string(adapterReference, "adapterManifestHash"));
		verification.put("artifactPlanHash", string(activation, "artifactPlanHash"));
		verification.put("semanticActionsHash", string(activation, "semanticActionsHash"));
		verification.put("planBindingHash", string(activation, "planBindingHash"));
		if (!canonicalHash(verification).equals(
			string(ledger, "verificationEvidenceHash")))
			throw activationMismatch("verificationEvidenceHash");
		if (!activationPlanBindingHash(activation).equals(
			string(activation, "planBindingHash")))
			throw activationMismatch("planBindingHash");
	}

	private static String providerArtifactPlanHash(
		WorldBuilderProviderCatalog.Composition composition)
		throws WorldBuilderContractException {
		List<Object> artifacts = new ArrayList<Object>();
		for (WorldBuilderProviderCatalog.Artifact artifact : composition.artifacts) {
			Map<String,Object> action = new LinkedHashMap<String,Object>();
			action.put("sourcePath", artifact.sourcePath);
			action.put("bundlePath", artifact.bundlePath);
			action.put("installRelativePath", RELEASE_PREFIX
				+ composition.string("bundleInventoryHash") + "/" + artifact.bundlePath);
			action.put("mode", artifact.inventory.get("mode"));
			action.put("size", artifact.inventory.get("size"));
			action.put("sha256", artifact.inventory.get("sha256"));
			artifacts.add(action);
		}
		return canonicalHash(artifacts);
	}

	private static WorldBuilderContractException activationMismatch(String field) {
		return problem(WorldBuilderErrorCodes.CAPABILITY_MISMATCH, field, false,
			"Installed activation marker does not match the selected ledger or authority.",
			"Keep map import disabled and recover/reinstall the exact composition.");
	}

	private static void requireHash(String value, String field)
		throws WorldBuilderContractException {
		if (!WorldBuilderBoundedInventory.isHash(value)) throw activationMismatch(field);
	}

	private static void verifyOwnedReleaseTree(Path target, Map<String,Object> plan)
		throws IOException, WorldBuilderContractException {
		List<Map<String,Object>> artifacts = new ArrayList<Map<String,Object>>();
		for (Object raw : array(plan.get("artifactPlan"))) artifacts.add(object(raw));
		verifyReleaseTree(target, string(plan, "releaseRelativePath"), artifacts,
			activationDocument(plan), object(plan.get("migrationPlan")));
	}

	private static void verifyProviderReleaseTree(Path target, String releaseRelative,
		List<WorldBuilderProviderCatalog.Artifact> providerArtifacts,
		Map<String,Object> expectedActivation, Map<String,Object> expectedMigration)
		throws IOException, WorldBuilderContractException {
		List<Map<String,Object>> artifacts = new ArrayList<Map<String,Object>>();
		for (WorldBuilderProviderCatalog.Artifact artifact : providerArtifacts) {
			Map<String,Object> record = new LinkedHashMap<String,Object>();
			record.put("bundlePath", artifact.bundlePath);
			record.put("mode", artifact.inventory.get("mode"));
			record.put("size", artifact.inventory.get("size"));
			record.put("sha256", artifact.inventory.get("sha256"));
			artifacts.add(record);
		}
		verifyReleaseTree(target, releaseRelative, artifacts, expectedActivation,
			expectedMigration);
	}

	private static void verifyReleaseTree(Path root, String releaseRelative,
		List<Map<String,Object>> artifacts, Map<String,Object> expectedActivation,
		Map<String,Object> expectedMigration)
		throws IOException, WorldBuilderContractException {
		final Path release = releaseRelative.isEmpty() ? root
			: targetPath(root, releaseRelative);
		if (!Files.isDirectory(release, LinkOption.NOFOLLOW_LINKS)
			|| Files.isSymbolicLink(release)) throw problem(
			WorldBuilderErrorCodes.TARGET_DRIFT, "release", false,
			"Installed release root is missing, linked, or not a directory.",
			"Preserve the target and transaction evidence for reviewed recovery.");
		final Set<String> expectedFiles = new HashSet<String>();
		final Set<String> expectedDirectories = new HashSet<String>();
		expectedDirectories.add("");
		for (Map<String,Object> artifact : artifacts) {
			String bundle = string(artifact, "bundlePath");
			expectedFiles.add(bundle);
			addParentDirectories(bundle, expectedDirectories);
		}
		expectedFiles.add("activation.json");
		expectedFiles.add("migration/migration-plan.json");
		expectedDirectories.add("migration");
		final Set<String> actualFiles = new HashSet<String>();
		final Set<String> actualDirectories = new HashSet<String>();
		Files.walkFileTree(release, new SimpleFileVisitor<Path>() {
			@Override public FileVisitResult preVisitDirectory(Path directory,
				BasicFileAttributes attributes) throws IOException {
				if (!attributes.isDirectory() || Files.isSymbolicLink(directory))
					throw new IOException("linked or non-directory release entry");
				String relative = release.equals(directory) ? ""
					: release.relativize(directory).toString().replace('\\', '/');
				actualDirectories.add(relative);
				return FileVisitResult.CONTINUE;
			}
			@Override public FileVisitResult visitFile(Path file,
				BasicFileAttributes attributes) throws IOException {
				if (!attributes.isRegularFile() || Files.isSymbolicLink(file))
					throw new IOException("linked or non-regular release entry");
				actualFiles.add(release.relativize(file).toString().replace('\\', '/'));
				return FileVisitResult.CONTINUE;
			}
		});
		if (!expectedFiles.equals(actualFiles)
			|| !expectedDirectories.equals(actualDirectories)) throw problem(
			WorldBuilderErrorCodes.TARGET_DRIFT, "release", false,
			"Release tree has extra, missing, or unexpected files/directories.",
			"Preserve the drifted release and transaction evidence; no cleanup is authorized.");
		for (Map<String,Object> artifact : artifacts) {
			String relative = string(artifact, "bundlePath");
			Path installed = safeExistingFile(release, relative);
			requireFileMatches(installed, artifact, relative);
			String expectedMode = string(artifact, "mode");
			if (!expectedMode.equals(fileMode(installed))) throw problem(
				WorldBuilderErrorCodes.SOURCE_CORRUPT, relative, false,
				"Installed artifact mode differs from provider inventory.",
				"Keep map import disabled and recover/reinstall the exact composition.");
		}
		Path activation = safeExistingFile(release, "activation.json");
		if (expectedActivation != null) {
			byte[] expected = WorldBuilderJsonDocuments.pretty(expectedActivation)
				.getBytes(StandardCharsets.UTF_8);
			if (Files.size(activation) != expected.length
				|| !WorldBuilderHashes.sha256(activation).equals(
					WorldBuilderHashes.sha256(expected))) throw problem(
				WorldBuilderErrorCodes.TARGET_DRIFT, "activation.json", false,
				"Activation document bytes differ from the transaction-owned document.",
				"Preserve the drifted release and transaction evidence; no cleanup is authorized.");
		}
		Path migration = safeExistingFile(release, "migration/migration-plan.json");
		if (expectedMigration != null) {
			byte[] expected = WorldBuilderJsonDocuments.pretty(expectedMigration)
				.getBytes(StandardCharsets.UTF_8);
			if (Files.size(migration) != expected.length
				|| !WorldBuilderHashes.sha256(migration).equals(
					WorldBuilderHashes.sha256(expected))) throw problem(
				WorldBuilderErrorCodes.TARGET_DRIFT, "migration/migration-plan.json", false,
				"Migration plan bytes differ from the transaction-owned document.",
				"Preserve the drifted release and transaction evidence; no cleanup is authorized.");
		}
	}

	private static void addParentDirectories(String relative, Set<String> values) {
		int slash = relative.lastIndexOf('/');
		while (slash > 0) {
			values.add(relative.substring(0, slash));
			slash = relative.lastIndexOf('/', slash - 1);
		}
	}

	private static void ensureParents(Path root, Path wanted, List<Path> created)
		throws IOException, WorldBuilderContractException {
		Path normalizedRoot = root.toAbsolutePath().normalize();
		Path normalizedWanted = wanted.toAbsolutePath().normalize();
		if (!normalizedWanted.startsWith(normalizedRoot)) throw new IOException("parent escape");
		Path current = normalizedRoot;
		for (Path segment : normalizedRoot.relativize(normalizedWanted)) {
			current = current.resolve(segment);
			if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
				if (!Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)
					|| Files.isSymbolicLink(current)) throw problem(
					WorldBuilderErrorCodes.UNSAFE_PATH, root.relativize(current).toString(), false,
					"Activation parent is not a real directory.",
					"Restore a safe target layout before retrying.");
			} else {
				Files.createDirectory(current); created.add(current);
			}
		}
	}

	private static void moveNewDirectory(Path staging, Path release)
		throws IOException, WorldBuilderContractException {
		if (Files.exists(release, LinkOption.NOFOLLOW_LINKS)) throw problem(
			WorldBuilderErrorCodes.TARGET_DRIFT, release.getFileName().toString(), false,
			"Content-addressed release destination already exists.",
			"Revalidate the installed release or use exact recovery.");
		try {
			Files.move(staging, release, StandardCopyOption.ATOMIC_MOVE);
		} catch (AtomicMoveNotSupportedException unsupported) {
			throw problem(WorldBuilderErrorCodes.MUTATION_FAILED,
				release.getFileName().toString(), false,
				"Filesystem cannot atomically publish the staged release.",
				"Use same-filesystem external staging on a local filesystem.", unsupported);
		}
	}

	private static void requireOffline(Path target)
		throws WorldBuilderContractException {
		for (String relative : OFFLINE_SENTINELS) {
			Path path = targetPath(target, relative);
			if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) throw problem(
				WorldBuilderErrorCodes.OFFLINE_REQUIRED, relative, false,
				"A compiled target PID/lock sentinel is present.",
				"Stop the synthetic target and remove stale run evidence through its shutdown procedure.");
		}
	}

	private static void requireFileMatches(Path path, Map<String,Object> record,
		String relative) throws IOException, WorldBuilderContractException {
		BasicFileAttributes attributes = Files.readAttributes(path,
			BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
		if (!attributes.isRegularFile() || attributes.isSymbolicLink()) throw problem(
			WorldBuilderErrorCodes.UNSAFE_PATH, relative, false,
			"Transaction evidence is not a regular no-follow file.",
			"Restore exact contained file evidence.");
		if (attributes.size() != integer(record, "size")
			|| !WorldBuilderHashes.sha256(path).equals(string(record, "sha256"))) throw problem(
			WorldBuilderErrorCodes.TARGET_DRIFT, relative, false,
			"Transaction file bytes changed from the reviewed inventory.",
			"Keep the target offline and request a fresh preview.");
	}

	private static void setMode(Path path, String mode) throws IOException {
		int bits = Integer.parseInt(mode, 8);
		Set<PosixFilePermission> permissions = EnumSet.noneOf(PosixFilePermission.class);
		PosixFilePermission[] flags = {
			PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
			PosixFilePermission.OWNER_EXECUTE, PosixFilePermission.GROUP_READ,
			PosixFilePermission.GROUP_WRITE, PosixFilePermission.GROUP_EXECUTE,
			PosixFilePermission.OTHERS_READ, PosixFilePermission.OTHERS_WRITE,
			PosixFilePermission.OTHERS_EXECUTE
		};
		int[] masks = {0400,0200,0100,0040,0020,0010,0004,0002,0001};
		for (int index = 0; index < masks.length; index++)
			if ((bits & masks[index]) != 0) permissions.add(flags[index]);
		Files.setPosixFilePermissions(path, permissions);
	}

	private static String fileMode(Path path) throws IOException {
		Object raw = Files.getAttribute(path, "unix:mode", LinkOption.NOFOLLOW_LINKS);
		return String.format("%04o", Integer.valueOf(((Number)raw).intValue() & 0777));
	}

	private static void deleteOwnedTree(final Path root) throws IOException {
		Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
			@Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
				throws IOException {
				if (!attrs.isRegularFile() || Files.isSymbolicLink(file))
					throw new IOException("unsafe owned release file");
				Files.delete(file); return FileVisitResult.CONTINUE;
			}
			@Override public FileVisitResult postVisitDirectory(Path directory,
				IOException failure) throws IOException {
				if (failure != null) throw failure;
				if (Files.isSymbolicLink(directory)) throw new IOException("unsafe release directory");
				Files.delete(directory); return FileVisitResult.CONTINUE;
			}
		});
	}

	private static boolean isEmpty(Path directory) throws IOException {
		try (java.nio.file.DirectoryStream<Path> entries = Files.newDirectoryStream(directory)) {
			return !entries.iterator().hasNext();
		}
	}

	private static Map<String,Object> receipt(Map<String,Object> plan, String status,
		boolean mutationOccurred, boolean rollbackComplete, String verification,
		String failureType) throws WorldBuilderContractException {
		Map<String,Object> receipt = new LinkedHashMap<String,Object>();
		receipt.put("schemaVersion", Long.valueOf(1));
		receipt.put("manifestType", "world-builder-current-runtime-upgrade-receipt");
		receipt.put("transactionId", string(plan, "transactionId"));
		receipt.put("planFingerprintSha256", string(plan, "planFingerprintSha256"));
		receipt.put("status", status);
		receipt.put("mutationOccurred", Boolean.valueOf(mutationOccurred));
		receipt.put("rollbackComplete", Boolean.valueOf(rollbackComplete));
		receipt.put("recoveryRequired", Boolean.valueOf("recovery-required".equals(status)));
		receipt.put("preimageInventoryHash", string(plan, "preimageInventoryHash"));
		receipt.put("artifactPlanHash", string(plan, "artifactPlanHash"));
		receipt.put("verificationEvidenceHash", verification);
		receipt.put("failureType", failureType == null ? "" : failureType);
		receipt.put("receiptFingerprintSha256", ZERO_HASH);
		bindFingerprint(receipt, "receiptFingerprintSha256");
		return receipt;
	}

	private static void writeReceipt(Path path, Map<String,Object> receipt)
		throws IOException {
		Path temporary = path.getParent().resolve(".receipt.json.tmp");
		Files.write(temporary, WorldBuilderJsonDocuments.pretty(receipt)
			.getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE,
			StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
		try {
			Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE,
				StandardCopyOption.REPLACE_EXISTING);
		} catch (AtomicMoveNotSupportedException unsupported) {
			Files.deleteIfExists(temporary); throw unsupported;
		}
	}

	private static void writeNew(Path path, String value) throws IOException {
		Files.write(path, value.getBytes(StandardCharsets.UTF_8),
			StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
	}

	private void observe(String milestone, Path path) throws Exception {
		observer.observe(milestone, path);
	}

	private static Path transactionPath(Path root, String transactionId)
		throws WorldBuilderContractException {
		return targetPath(root, transactionId);
	}

	private static Path targetPath(Path root, String relative)
		throws WorldBuilderContractException {
		return WorldBuilderPortablePath.resolveContained(root, relative, OPERATION);
	}

	private static Path safeExistingFile(Path root, String relative)
		throws WorldBuilderContractException {
		return WorldBuilderReadOnlyTarget.open(root).requiredFile(relative);
	}

	private static Map<String,Object> readObject(Path path, String relative)
		throws IOException, WorldBuilderContractException {
		try {
			return WorldBuilderJsonDocuments.readObject(path);
		} catch (WorldBuilderDiscoveryException malformed) {
			throw problem(WorldBuilderErrorCodes.MALFORMED_JSON, relative, false,
				"Installed transaction evidence is malformed.",
				"Keep map import disabled and recover/reinstall exact evidence.", malformed);
		}
	}

	private static Path realDirectory(Path requested, String label)
		throws IOException, WorldBuilderContractException {
		Path path = WorldBuilderReadOnlyTarget.open(requested).root;
		return path.toRealPath(LinkOption.NOFOLLOW_LINKS);
	}

	private static void validateTransactionId(String value)
		throws WorldBuilderContractException {
		WorldBuilderBoundedInventory.identifier(value, OPERATION, "transactionId");
	}

	private static String canonicalHash(Object value) {
		return WorldBuilderHashes.sha256(WorldBuilderJsonDocuments.canonical(value)
			.getBytes(StandardCharsets.UTF_8));
	}

	private static void bindFingerprint(Map<String,Object> value, String field) {
		value.put(field, ZERO_HASH); value.put(field, canonicalHash(value));
	}

	private static void validatePlanFingerprint(Map<String,Object> plan)
		throws WorldBuilderContractException {
		String supplied = string(plan, "planFingerprintSha256");
		plan.put("planFingerprintSha256", ZERO_HASH);
		String expected = canonicalHash(plan);
		plan.put("planFingerprintSha256", supplied);
		if (!supplied.equals(expected)) throw problem(
			WorldBuilderErrorCodes.SOURCE_CORRUPT, "planFingerprintSha256", true,
			"Recovery plan fingerprint does not match its content.",
			"Restore the exact sealed transaction plan.");
	}

	@SuppressWarnings("unchecked")
	private static Map<String,Object> object(Object raw)
		throws WorldBuilderContractException {
		if (!(raw instanceof Map)) throw problem(WorldBuilderErrorCodes.CONTRACT_VALUE_INVALID,
			"contract", false, "Expected an object in current-runtime transaction evidence.",
			"Restore the exact sealed contract.");
		return (Map<String,Object>)raw;
	}

	private static Map<String,Object> copyObject(Object raw)
		throws WorldBuilderContractException {
		return new LinkedHashMap<String,Object>(object(raw));
	}

	private static List<?> array(Object raw) throws WorldBuilderContractException {
		if (!(raw instanceof List)) throw problem(WorldBuilderErrorCodes.CONTRACT_VALUE_INVALID,
			"contract", false, "Expected an array in current-runtime transaction evidence.",
			"Restore the exact sealed contract.");
		return (List<?>)raw;
	}

	private static String string(Map<String,Object> root, String key)
		throws WorldBuilderContractException {
		return WorldBuilderBoundedInventory.string(root.get(key), OPERATION, key);
	}

	private static boolean bool(Map<String,Object> root, String key)
		throws WorldBuilderContractException {
		return WorldBuilderBoundedInventory.bool(root.get(key), OPERATION, key);
	}

	private static long integer(Map<String,Object> root, String key)
		throws WorldBuilderContractException {
		return WorldBuilderBoundedInventory.integer(root.get(key), OPERATION, key);
	}

	private static WorldBuilderContractException problem(String code, String relative,
		boolean mutation, String message, String nextStep) {
		return new WorldBuilderContractException(code, OPERATION, relative, mutation,
			message, nextStep);
	}

	private static WorldBuilderContractException problem(String code, String relative,
		boolean mutation, String message, String nextStep, Throwable cause) {
		return new WorldBuilderContractException(code, OPERATION, relative, mutation,
			message, nextStep, cause);
	}

	static final class Preview {
		final Path targetRoot;
		final Path transactionRoot;
		final Path providerCatalogRoot;
		final Path compositionIdentity;
		final Path inputAdapter;
		final Path projectCapability;
		final WorldBuilderCurrentRuntimeExecutionProfile profile;
		final Map<String,Object> plan;

		Preview(Path targetRoot, Path transactionRoot, Path providerCatalogRoot,
			Path compositionIdentity, Path inputAdapter, Path projectCapability,
			WorldBuilderCurrentRuntimeExecutionProfile profile, Map<String,Object> plan) {
			this.targetRoot = targetRoot; this.transactionRoot = transactionRoot;
			this.providerCatalogRoot = providerCatalogRoot;
			this.compositionIdentity = compositionIdentity;
			this.inputAdapter = inputAdapter; this.projectCapability = projectCapability;
			this.profile = profile;
			this.plan = plan;
		}

		String toJson() { return WorldBuilderJsonDocuments.pretty(plan); }
		String fingerprint() throws WorldBuilderContractException {
			return string(plan, "planFingerprintSha256");
		}
		String confirmationIdentity() throws WorldBuilderContractException {
			return string(plan, "confirmationIdentity");
		}
	}

	private static void validateRecoveryPlan(Map<String,Object> plan)
		throws WorldBuilderContractException {
		WorldBuilderBoundedInventory.exactKeys(plan, OPERATION,
			"schemaVersion", "manifestType", "transactionId", "classificationStatus",
			"classificationTier", "classificationFingerprintSha256", "inputAdapter",
			"executionProfile", "migrationPlan", "projectCapability", "destination", "preimageInventory",
			"preimageInventoryHash", "semanticActions", "semanticActionsHash",
			"artifactPlan", "artifactPlanHash", "releaseRelativePath", "stagingPolicy",
			"activationLedgerRelativePath", "activationLedger",
			"verificationEvidenceHash", "mapImportAvailableBeforeApply",
			"mutationOccurred", "activationAuthorized", "confirmationIdentity", "planFingerprintSha256");
		WorldBuilderCurrentRuntimeExecutionProfile profile =
			WorldBuilderCurrentRuntimeExecutionProfile.fromIdentity(
				object(plan.get("executionProfile")));
		if (integer(plan, "schemaVersion") != 1L
			|| !"world-builder-current-runtime-upgrade-plan".equals(
				string(plan, "manifestType"))
			|| !"UPGRADE_READY".equals(string(plan, "classificationStatus"))
			|| !Arrays.asList("T0", "T1", "T2A", "T2B", "MANAGED_N").contains(
				string(plan, "classificationTier"))
			|| !"external-same-filesystem-outside-active-target".equals(
				string(plan, "stagingPolicy"))
			|| !LEDGER_RELATIVE.equals(string(plan, "activationLedgerRelativePath"))
			|| bool(plan, "mapImportAvailableBeforeApply")
			|| bool(plan, "mutationOccurred") || !bool(plan, "activationAuthorized")) throw problem(
			WorldBuilderErrorCodes.RECOVERY_REQUIRED, "upgrade-plan", true,
			"Recovery plan has unsupported execution authority.",
			"Restore the exact synthetic transaction plan.");
		Map<String,Object> destination = object(plan.get("destination"));
		String release = RELEASE_PREFIX + string(destination, "bundleInventoryHash");
		if (!release.equals(string(plan, "releaseRelativePath"))) throw problem(
			WorldBuilderErrorCodes.RECOVERY_REQUIRED, "releaseRelativePath", true,
			"Recovery release path is not the content-addressed destination.",
			"Restore the exact synthetic transaction plan.");
		Set<String> preimagePaths = new HashSet<String>();
		boolean ledgerPresent = false;
		for (Object raw : array(plan.get("preimageInventory"))) {
			Map<String,Object> record = object(raw);
			WorldBuilderBoundedInventory.exactKeys(record, OPERATION,
				"relativePath", "present", "size", "sha256", "backupRelativePath");
			String relative = WorldBuilderPortablePath.require(
				string(record, "relativePath"), OPERATION);
			if (!preimagePaths.add(WorldBuilderPortablePath.collisionKey(relative, OPERATION)))
				throw problem(WorldBuilderErrorCodes.RECOVERY_REQUIRED, relative, true,
					"Recovery preimage repeats or case-collides.",
					"Restore the exact synthetic transaction plan.");
			boolean present = bool(record, "present");
			String backup = string(record, "backupRelativePath");
			if (present != !backup.isEmpty()
				|| present && !("files/" + relative).equals(backup)) throw problem(
				WorldBuilderErrorCodes.RECOVERY_REQUIRED, relative, true,
				"Recovery backup path does not exactly derive from its preimage path.",
				"Restore the exact synthetic transaction plan.");
			if (LEDGER_RELATIVE.equals(relative)) ledgerPresent = true;
		}
		if (!ledgerPresent) throw problem(WorldBuilderErrorCodes.RECOVERY_REQUIRED,
			LEDGER_RELATIVE, true, "Recovery plan omits the activation-ledger preimage.",
			"Restore the exact synthetic transaction plan.");
		Set<String> bundlePaths = new HashSet<String>();
		for (Object raw : array(plan.get("artifactPlan"))) {
			Map<String,Object> artifact = object(raw);
			WorldBuilderBoundedInventory.exactKeys(artifact, OPERATION,
				"sourcePath", "bundlePath", "installRelativePath", "mode", "size", "sha256");
			String bundle = WorldBuilderPortablePath.require(
				string(artifact, "bundlePath"), OPERATION);
			if (!bundlePaths.add(WorldBuilderPortablePath.collisionKey(bundle, OPERATION))
				|| !(release + "/" + bundle).equals(
					string(artifact, "installRelativePath"))) throw problem(
				WorldBuilderErrorCodes.RECOVERY_REQUIRED, bundle, true,
				"Recovery artifact path is duplicated or not derived from the release root.",
				"Restore the exact synthetic transaction plan.");
		}
		Map<String,Object> ledger = object(plan.get("activationLedger"));
		Map<String,Object> migration = object(plan.get("migrationPlan"));
		profile.validateMigrationPlan(migration);
		if (!profile.migratorId.equals(string(migration, "migratorId"))
			|| !profile.serverBuildId.equals(string(ledger, "serverBuildId"))
			|| !profile.clientBuildId.equals(string(ledger, "clientBuildId"))
			|| !profile.mapPackageId.equals(string(ledger, "activeMapPackageId")))
			throw problem(WorldBuilderErrorCodes.RECOVERY_REQUIRED, "executionProfile", true,
				"Recovery evidence does not bind its compiled execution profile.",
				"Restore the exact transaction plan; target documents cannot select executable migration code.");
		for (String field : Arrays.asList("platformReleaseId", "platformManifestHash",
			"schemaSetHash", "variantId", "variantManifestHash", "moduleSetHash",
			"bundleInventoryHash", "bundleSpecId", "bundleSpecHash",
			"inputAdapterContractId")) if (!string(destination, field).equals(
				string(ledger, field))) throw problem(
			WorldBuilderErrorCodes.RECOVERY_REQUIRED, field, true,
			"Activation ledger does not bind the plan destination.",
			"Restore the exact synthetic transaction plan.");
		if (!canonicalHash(plan.get("preimageInventory")).equals(
				string(plan, "preimageInventoryHash"))
			|| !canonicalHash(plan.get("semanticActions")).equals(
				string(plan, "semanticActionsHash"))
			|| !canonicalHash(plan.get("artifactPlan")).equals(
				string(plan, "artifactPlanHash"))) throw problem(
			WorldBuilderErrorCodes.RECOVERY_REQUIRED, "upgrade-plan", true,
			"Recovery plan nested inventory hashes do not match.",
			"Restore the exact synthetic transaction plan.");
	}

	private static void validateReceiptFingerprint(Map<String,Object> receipt)
		throws WorldBuilderContractException {
		WorldBuilderBoundedInventory.exactKeys(receipt, OPERATION,
			"schemaVersion", "manifestType", "transactionId", "planFingerprintSha256",
			"status", "mutationOccurred", "rollbackComplete", "recoveryRequired",
			"preimageInventoryHash", "artifactPlanHash", "verificationEvidenceHash",
			"failureType", "receiptFingerprintSha256");
		String supplied = string(receipt, "receiptFingerprintSha256");
		receipt.put("receiptFingerprintSha256", ZERO_HASH);
		String expected = canonicalHash(receipt);
		receipt.put("receiptFingerprintSha256", supplied);
		if (!supplied.equals(expected)) throw problem(
			WorldBuilderErrorCodes.RECOVERY_REQUIRED, "receiptFingerprintSha256", true,
			"Recovery receipt fingerprint does not match its content.",
			"Restore the exact sealed recovery receipt.");
	}

	static final class Result {
		final String transactionId;
		final String status;
		final Path receipt;
		final Path release;

		Result(String transactionId, String status, Path receipt, Path release) {
			this.transactionId = transactionId; this.status = status;
			this.receipt = receipt; this.release = release;
		}

		String toJson() {
			Map<String,Object> value = new LinkedHashMap<String,Object>();
			value.put("transactionId", transactionId); value.put("status", status);
			value.put("receipt", receipt == null ? "" : receipt.toString());
			value.put("release", release == null ? "" : release.toString());
			return WorldBuilderJsonDocuments.pretty(value);
		}
	}
	}
