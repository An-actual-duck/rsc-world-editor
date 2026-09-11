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
			WorldBuilderCurrentRuntimeExecutionProfile.synthetic(adapter), null, null, null, true);
	}

	Preview previewPreservation(Path targetRoot, Path transactionRoot,
		Path providerCatalogRoot, Path compositionIdentity, Path projectCapability,
		String transactionId) throws IOException, WorldBuilderContractException {
		return previewPreservation(targetRoot, transactionRoot, providerCatalogRoot,
			compositionIdentity, projectCapability, transactionId, null, null);
	}

	Preview previewPreservation(Path targetRoot, Path transactionRoot,
		Path providerCatalogRoot, Path compositionIdentity, Path projectCapability,
		String transactionId, Path packedSourceRoot, Path packedDiscoveryReport)
		throws IOException, WorldBuilderContractException {
		return previewPreservation(targetRoot, transactionRoot, providerCatalogRoot,
			compositionIdentity, projectCapability, transactionId, packedSourceRoot, packedDiscoveryReport, null);
	}

	Preview previewPreservation(Path targetRoot, Path transactionRoot,
		Path providerCatalogRoot, Path compositionIdentity, Path projectCapability,
		String transactionId, Path packedSourceRoot, Path packedDiscoveryReport, Path preservationProject)
		throws IOException, WorldBuilderContractException {
		return previewInternal(targetRoot, transactionRoot, providerCatalogRoot,
			compositionIdentity, null, projectCapability, transactionId,
			WorldBuilderCurrentRuntimeExecutionProfile.preservation(),
			packedSourceRoot, packedDiscoveryReport, preservationProject, true);
	}

	/** Non-production topology for isolated migration regressions; activation remains disabled. */
	Preview previewPreservationFixture(Path targetRoot, Path transactionRoot,
		Path providerCatalogRoot, Path compositionIdentity, Path projectCapability,
		String transactionId, Path packedSourceRoot, Path packedDiscoveryReport)
		throws IOException, WorldBuilderContractException {
		return previewInternal(targetRoot, transactionRoot, providerCatalogRoot,
			compositionIdentity, null, projectCapability, transactionId,
			WorldBuilderCurrentRuntimeExecutionProfile.preservationFixture(),
			packedSourceRoot, packedDiscoveryReport, null, true);
	}

	private Preview previewInternal(Path targetRoot, Path transactionRoot,
		Path providerCatalogRoot, Path compositionIdentity, Path inputAdapter,
		Path projectCapability, String transactionId,
		WorldBuilderCurrentRuntimeExecutionProfile profile, Path packedSourceRoot,
		Path packedDiscoveryReport, Path preservationProject, boolean inspectOffline)
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
			projectCapability == null ? nativeProjectCapability(preservationProject)
				: WorldBuilderCurrentRuntimeContracts.read(
					WorldBuilderCurrentRuntimeContracts.Kind.PROJECT_CAPABILITY, projectCapability);
		if (preservationProject != null) {
			Map<String,Object> manifest = WorldBuilderAdaptiveProjectLifecycle.verifyProjectDirectory(preservationProject, false).manifest;
			if (!string(project.root, "projectId").equals(string(manifest, "projectId")))
				throw activationMismatch("projectCapability");
		}
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
			project, classification, transactionId, profile, packedSourceRoot,
			packedDiscoveryReport, preservationProject);
		if (inspectOffline) try (WorldBuilderCurrentRuntimeOfflineLease ignored = previewLease(target, plan, profile.syntheticOnly)) { }
		return new Preview(target, workspace, providerCatalogRoot, compositionIdentity,
			inputAdapter, projectCapability, profile, packedSourceRoot,
			packedDiscoveryReport, preservationProject, plan);
	}

	/** Derived in memory from an already verified native project; preview writes no capability file. */
	private static WorldBuilderCurrentRuntimeContracts.Document nativeProjectCapability(Path path)
		throws IOException, WorldBuilderContractException {
		if (path == null) throw activationMismatch("projectCapability");
		WorldBuilderAdaptiveProjectLifecycle.VerifiedProject project = WorldBuilderAdaptiveProjectLifecycle.verifyProjectDirectory(path, false);
		WorldBuilderCurrentBaseProjectContent.verifiedIdentity(path);
		if (!WorldBuilderCurrentRuntimeUserActions.isNativeBase(project))
			throw activationMismatch("native-project");
		Map<String,Object> capability = new LinkedHashMap<String,Object>();
		capability.put("schemaVersion", Long.valueOf(1)); capability.put("manifestType", "world-builder-current-project-capability");
		capability.put("projectId", project.projectId); capability.put("projectSchemaId", "world-builder-project-v2");
		// Exact project-manifest-v2 schema implemented by the compiled lifecycle validator.
		capability.put("projectSchemaHash", "68f538f0ed211b2993f0d23f188808e836d9f1a91dd95886a73d9cf3280d0653");
		capability.put("authoredDataFingerprintSha256", project.working.fingerprintSha256);
		capability.put("allowedVariantIds", Collections.<Object>singletonList("current-base-v1"));
		capability.put("requiredCapabilityIds", Collections.<Object>singletonList("canonical-signed-layered-map-v1"));
		capability.put("requiredModuleIds", new ArrayList<Object>());
		WorldBuilderAdaptiveExporter.bindFingerprint(capability, "capabilityFingerprintSha256");
		return WorldBuilderCurrentRuntimeContracts.builtIn(WorldBuilderCurrentRuntimeContracts.Kind.PROJECT_CAPABILITY, capability);
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

		try (WorldBuilderCurrentRuntimeOfflineLease offline =
			previewLease(reviewed.targetRoot, reviewed.plan, reviewed.profile.syntheticOnly)) {
		Path transaction = transactionPath(reviewed.transactionRoot,
			string(reviewed.plan, "transactionId"));
		if (Files.exists(transaction, LinkOption.NOFOLLOW_LINKS)) throw problem(
			WorldBuilderErrorCodes.TARGET_DRIFT, "transaction-root", false,
			"The reviewed transaction identity is already in use.",
			"Preserve the existing evidence and request a new transaction identity.");
		Files.createDirectory(transaction);
		WorldBuilderAdaptiveDurability.forceDirectory(transaction);
		WorldBuilderAdaptiveDurability.forceDirectory(transaction.getParent());
		Path backup = transaction.resolve("backup");
		Path staging = transaction.resolve("staging");
		Path receipt = transaction.resolve("receipt.json");
		Path planPath = transaction.resolve("upgrade-plan.json");
		boolean releasePublished = false;
		boolean ledgerActivated = false;
		Map<String,Object> executionPlan = reviewed.plan;
		List<Path> createdTargetDirectories = new ArrayList<Path>();
		try {
			writeNew(planPath, reviewed.toJson());
			Files.createDirectory(backup);
			WorldBuilderAdaptiveDurability.forceDirectory(backup);
			WorldBuilderAdaptiveDurability.forceDirectory(transaction);
			backupPreimage(reviewed, backup);
			writeReceipt(receipt, receipt(reviewed.plan, "pending", false,
				false, "", "backup-complete"));
			observe("after-backup", backup);

			Files.createDirectory(staging);
			WorldBuilderAdaptiveDurability.forceDirectory(staging);
			WorldBuilderAdaptiveDurability.forceDirectory(transaction);
			executionPlan = stageMigration(reviewed, staging);
			writeReceipt(receipt, receipt(executionPlan, "pending", false, false, "", "migration-staged"));
			if (!reviewed.profile.syntheticOnly) {
				WorldBuilderProviderCatalog.Composition composition = WorldBuilderProviderCatalog.resolve(
					reviewed.providerCatalogRoot, reviewed.compositionIdentity);
				WorldBuilderInstalledRuntimeVerifier.Prepared prepared = WorldBuilderInstalledRuntimeVerifier.prepare(
					staging, composition, object(executionPlan.get("migrationPlan")), generatedStateOutputs(executionPlan),
					transaction.resolve("runtime-verification"), null);
				// Keep this binding in the catch path even if the durable receipt publication fails.
				executionPlan = bindRuntimeAttempt(executionPlan, prepared.authority);
				WorldBuilderCurrentRuntimeVerifierAuthority.authenticate(transaction.resolve("runtime-verification"),
					prepared.authority, array(executionPlan.get("artifactPlan")));
				writeReceipt(receipt, receipt(executionPlan, "pending", false, false, "", "verification-prepared"));
				observe("before-runtime-verification", transaction.resolve("runtime-verification"));
				executionPlan = bindRuntimeExecution(executionPlan, WorldBuilderCurrentRuntimeExecutionEvidence.execute(prepared));
			}
			finishStaging(reviewed, staging, executionPlan);
			writeReceipt(receipt, receipt(executionPlan, "pending", false,
				false, "", "staging-verified"));
			observe("after-staging", staging);
			fresh = refresh(reviewed);
			if (!fresh.fingerprint().equals(reviewed.fingerprint())) throw problem(
				WorldBuilderErrorCodes.TARGET_DRIFT, "upgrade-plan", false,
				"Target or authority changed after backup and staging.",
				"Keep the target offline and review a fresh transaction.");
			verifyReviewedRelease(reviewed, staging, executionPlan);
			if (!reviewed.profile.syntheticOnly) {
				boolean successor = "MANAGED_N".equals(reviewed.plan.get("classificationTier"));
				InitialActivation prepared = successor ? prepareSuccessorActivation(reviewed, staging, executionPlan, transaction)
					: prepareInitialActivation(reviewed, staging, executionPlan, transaction,
						preservedSideSources(reviewed, true), preservedSideSources(reviewed, false));
				executionPlan = prepared.execution;
				// Outer receipt is durable before any target instance or startup guard exists.
				writeReceipt(receipt, receipt(executionPlan, "pending", false, false, "", "installed-cutover-prepared"));
				observe("after-installed-cutover-prepared", transaction);
				Path instance = targetPath(reviewed.targetRoot, WorldBuilderCurrentRuntimeInstalledGeneration.INSTANCE);
				Path release = targetPath(reviewed.targetRoot, string(executionPlan, "releaseRelativePath"));
				ensureParents(reviewed.targetRoot, instance.getParent(), createdTargetDirectories);
				if (!successor) WorldBuilderCurrentRuntimeInstance.materializeGuarded(prepared.construction, instance, prepared.cutover);
				observe("after-instance-constructed", instance);
				WorldBuilderCurrentRuntimeInstanceLease roles = successor ? offline.installedLease()
					: WorldBuilderCurrentRuntimeInstanceLease.acquire(instance.resolve("installation"));
				try {
					WorldBuilderCurrentRuntimeCutover.guard(prepared.cutover, transaction.resolve("cutover"), roles);
					if (successor) WorldBuilderCurrentRuntimeSuccessor.materialize(prepared.successor, reviewed.targetRoot, staging, roles);
					observe("after-installed-outputs-published", instance);
					ensureParents(reviewed.targetRoot, release.getParent(), createdTargetDirectories);
					releasePublished = true;
					moveNewDirectory(staging, release);
					writeReceipt(receipt, receipt(executionPlan, "pending", true, false, "", "release-published"));
					observe("after-release-published", release);
					verifyOwnedReleaseTree(reviewed.targetRoot, executionPlan);
					if (successor) WorldBuilderCurrentRuntimeSuccessor.verify(prepared.successor, reviewed.targetRoot, true);
					else WorldBuilderCurrentRuntimeInstance.verifyGuardedInitialOutputs(initialOutputPlan(transaction, executionPlan), instance, prepared.cutover);
					if (!successor) WorldBuilderPreservationPersistentInputs.reverify(reviewed.targetRoot,
						object(object(reviewed.plan.get("migrationPlan")).get("persistentInputs")));
					new WorldBuilderCurrentRuntimeCutover(milestone -> observeCutover(milestone, transaction)).apply(prepared.cutover, transaction.resolve("cutover"), roles);
					WorldBuilderCurrentRuntimeInstalledGeneration.readSpecification(reviewed.targetRoot);
					writeReceipt(receipt, receipt(executionPlan, "successful", true, true,
						string(executionPlan, "verificationEvidenceHash"), ""));
					return new Result(string(executionPlan, "transactionId"), "successful", receipt, release);
				} finally { if (!successor) roles.close(); }
			}

			Path release = targetPath(reviewed.targetRoot,
				string(reviewed.plan, "releaseRelativePath"));
			ensureParents(reviewed.targetRoot, release.getParent(), createdTargetDirectories);
			releasePublished = true;
			moveNewDirectory(staging, release);
			writeReceipt(receipt, receipt(executionPlan, "pending", true,
				false, "", "release-published"));
			observe("after-release-published", release);

			Path ledger = targetPath(reviewed.targetRoot, LEDGER_RELATIVE);
			ensureParents(reviewed.targetRoot, ledger.getParent(), createdTargetDirectories);
			ledgerActivated = true;
			writeActivationLedger(ledger, object(executionPlan.get("activationLedger")));
			writeReceipt(receipt, receipt(executionPlan, "pending", true,
				false, "", "ledger-activated"));
			observe("after-ledger-activated", ledger);
			verifyInstalled(reviewed.targetRoot, executionPlan);
			writeReceipt(receipt, receipt(executionPlan, "successful", true,
				true, string(executionPlan, "verificationEvidenceHash"), ""));
			return new Result(string(reviewed.plan, "transactionId"), "successful",
				receipt, release);
		} catch (Throwable failure) {
			if (!installedActivation(executionPlan).isEmpty()
				&& Files.exists(transaction.resolve("cutover/commit.json"), LinkOption.NOFOLLOW_LINKS)) {
				// Any durable-decision entry is a one-way boundary, including unreadable or corrupt entries.
				try { writeReceipt(receipt, receipt(executionPlan, "recovery-required", true, false, "", "committed-finalization-required")); }
				catch (Throwable receiptFailure) { failure.addSuppressed(receiptFailure); }
				throw problem(WorldBuilderErrorCodes.RECOVERY_REQUIRED, "cutover", true,
					"Installed activation reached its irreversible commit; state rollback is forbidden.",
					"Run exact recovery to validate and finalize the committed activation.", failure);
			}
			if (!runtimeAttempt(executionPlan).isEmpty()) {
				try { WorldBuilderCurrentRuntimeVerifierAuthority.close(runtimeAttempt(executionPlan), array(executionPlan.get("artifactPlan"))); }
				catch (Throwable unproven) {
					WorldBuilderContractException retained = WorldBuilderCurrentRuntimeVerifierAuthority.unsafe(
						"Transaction failure has no authentic provider closure; retain all verifier evidence.");
					retained.addSuppressed(failure); retained.addSuppressed(unproven); failure = retained;
				}
			}
			if (failure instanceof WorldBuilderContractException
				&& WorldBuilderErrorCodes.RECOVERY_REQUIRED.equals(((WorldBuilderContractException)failure).code())) {
				// In particular, an unfinished verifier may still own processes and writable
				// disposable state. An unchanged target is not completed cleanup authority.
				try {
					writeReceipt(receipt, receipt(executionPlan, "recovery-required",
						releasePublished || ledgerActivated, false, "", "execution-cleanup-unproven"));
				} catch (Throwable receiptFailure) { failure.addSuppressed(receiptFailure); }
				throw (WorldBuilderContractException)failure;
			}
			try {
				observe("before-rollback", reviewed.targetRoot);
				if (!installedActivation(executionPlan).isEmpty()) {
					if ("successor".equals(installedActivation(executionPlan).get("mode")))
						rollbackInitialInstanceHeld(reviewed.targetRoot, transaction, executionPlan, offline.installedLease());
					else rollbackInitialInstance(reviewed.targetRoot, transaction, executionPlan);
				}
				rollback(reviewed.targetRoot, executionPlan, backup,
					releasePublished, ledgerActivated, createdTargetDirectories);
				observe("after-rollback", reviewed.targetRoot);
				writeReceipt(receipt, receipt(executionPlan, "rolled-back",
					releasePublished || ledgerActivated, true, "",
					failure.getClass().getName()));
			} catch (Throwable rollbackFailure) {
				try {
					writeReceipt(receipt, receipt(executionPlan, "recovery-required",
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
	}

	private Preview refresh(Preview preview)
		throws IOException, WorldBuilderContractException {
		return previewInternal(preview.targetRoot, preview.transactionRoot,
			preview.providerCatalogRoot, preview.compositionIdentity,
			preview.inputAdapter, preview.projectCapability,
			string(preview.plan, "transactionId"), preview.profile,
			preview.packedSourceRoot, preview.packedDiscoveryReport, preview.preservationProject, false);
	}

	Result recover(Path targetRoot, Path transactionRoot, String transactionId)
		throws IOException, WorldBuilderContractException {
		return recover(targetRoot, transactionRoot, transactionId, null);
	}

	RecoveryPreview previewRecovery(Path targetRoot, Path transactionRoot, String transactionId)
		throws IOException, WorldBuilderContractException {
		RecoveryPreview preview = readRecovery(targetRoot, transactionRoot, transactionId);
		try (WorldBuilderCurrentRuntimeOfflineLease ignored = recoveryLease(preview.target, preview.plan)) {
			return preview;
		}
	}

	private RecoveryPreview readRecovery(Path targetRoot, Path transactionRoot, String transactionId)
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
		Map<String,Object> priorReceipt;
		try {
			priorReceipt = WorldBuilderJsonDocuments.readObject(
				safeExistingFile(transaction, "receipt.json"));
		} catch (WorldBuilderDiscoveryException malformed) {
			throw problem(WorldBuilderErrorCodes.RECOVERY_REQUIRED, "receipt", true,
				"Recovery receipt is malformed.", "Restore the exact receipt.", malformed);
		}
		validateReceiptFingerprint(priorReceipt);
		if (!("recovery-required".equals(string(priorReceipt, "status"))
			|| "pending".equals(string(priorReceipt, "status")))
			|| !string(plan, "planFingerprintSha256").equals(
				string(priorReceipt, "planFingerprintSha256"))) throw problem(
			WorldBuilderErrorCodes.RECOVERY_REQUIRED, "receipt", true,
			"Recovery receipt does not authorize this exact interrupted plan.",
			"Restore the exact pending/recovery-required receipt and plan.");
		PendingReceiptTemporary pendingReceiptTemporary = validatePendingReceiptTemporary(
			transaction, plan);
		plan = restoreExecutionPlan(plan, priorReceipt,
			pendingReceiptTemporary == null ? null : pendingReceiptTemporary.document);
		return new RecoveryPreview(target, workspace, transactionId, plan, pendingReceiptTemporary,
			canonicalHash(Arrays.asList(target.toString(), workspace.toString(), transactionId, plan,
				priorReceipt, pendingReceiptTemporary == null ? null : pendingReceiptTemporary.document)));
	}

	Result recover(Path targetRoot, Path transactionRoot, String transactionId, String confirmedEvidenceHash)
		throws IOException, WorldBuilderContractException {
		RecoveryPreview reviewed = readRecovery(targetRoot, transactionRoot, transactionId);
		if (confirmedEvidenceHash != null && !reviewed.fingerprint.equals(confirmedEvidenceHash))
			throw problem(WorldBuilderErrorCodes.RECOVERY_REQUIRED, "recovery-preview", false,
				"Recovery evidence changed after preview.", "Review the exact interrupted transaction again.");
		Path target = reviewed.target;
		Path transaction = reviewed.workspace.resolve(transactionId);
		Path backup = transaction.resolve("backup");
		Map<String,Object> plan = reviewed.plan;
		PendingReceiptTemporary pendingReceiptTemporary = reviewed.pending;
		try (WorldBuilderCurrentRuntimeOfflineLease offline =
			recoveryLease(target, plan)) {
			Map<String,Object> installed = installedActivation(plan);
			if (!installed.isEmpty() && Files.exists(transaction.resolve("cutover/commit.json"), LinkOption.NOFOLLOW_LINKS)) {
				WorldBuilderCurrentRuntimeCutover.Plan cutover = WorldBuilderCurrentRuntimeCutover.read(
					transaction.resolve("cutover"), target, string(installed, "cutoverPlanSha256"));
				new WorldBuilderCurrentRuntimeCutover().recover(cutover, transaction.resolve("cutover"), offline.installedLease());
				// Only immutable current launch inputs are read. No initial DB seal or backup is reopened.
				WorldBuilderCurrentRuntimeInstalledGeneration.readSpecification(target);
				if (pendingReceiptTemporary != null) {
					Files.delete(pendingReceiptTemporary.path);
					WorldBuilderAdaptiveDurability.forceDirectory(transaction);
				}
				writeReceipt(transaction.resolve("receipt.json"), receipt(plan, "successful", true, true,
					string(plan, "verificationEvidenceHash"), ""));
				return new Result(transactionId, "successful", transaction.resolve("receipt.json"),
					targetPath(target, string(plan, "releaseRelativePath")));
			}
			Map<String,Object> attempt = runtimeAttempt(plan);
			if (!attempt.isEmpty()) {
				WorldBuilderCurrentRuntimeVerifierAuthority.authenticate(transaction.resolve("runtime-verification"),
					attempt, array(plan.get("artifactPlan")));
				WorldBuilderCurrentRuntimeVerifierAuthority.close(attempt, array(plan.get("artifactPlan")));
			} else if (Files.exists(transaction.resolve("runtime-verification"), LinkOption.NOFOLLOW_LINKS)) {
				throw WorldBuilderCurrentRuntimeVerifierAuthority.unsafe(
					"Unbound historical verifier attempt cannot grant process cleanup authority.");
			}
			if (pendingReceiptTemporary != null
				&& "successful".equals(pendingReceiptTemporary.status)) {
				verifyInstalled(target, plan);
				publishReceiptTemporary(pendingReceiptTemporary.path,
					transaction.resolve("receipt.json"));
				return new Result(transactionId, "successful",
					transaction.resolve("receipt.json"), targetPath(target,
						string(plan, "releaseRelativePath")));
			}
			if (pendingReceiptTemporary != null
				&& "rolled-back".equals(pendingReceiptTemporary.status)) {
				verifyPreimage(target, plan);
				publishReceiptTemporary(pendingReceiptTemporary.path,
					transaction.resolve("receipt.json"));
				return new Result(transactionId, "rolled-back",
					transaction.resolve("receipt.json"), null);
			}
			if (!installed.isEmpty() && Files.exists(target.resolve(WorldBuilderCurrentRuntimeInstalledGeneration.INSTANCE), LinkOption.NOFOLLOW_LINKS))
				rollbackInitialInstanceHeld(target, transaction, plan, offline.installedLease());
			rollback(target, plan, backup, true, true, Collections.<Path>emptyList());
			verifyPreimage(target, plan);
			if (pendingReceiptTemporary != null) {
				Files.delete(pendingReceiptTemporary.path);
				WorldBuilderAdaptiveDurability.forceDirectory(
					pendingReceiptTemporary.path.getParent());
			}
			Path receipt = transaction.resolve("receipt.json");
			writeReceipt(receipt, receipt(plan, "rolled-back", true, true, "",
				"recovered-exact-preimage"));
			return new Result(transactionId, "rolled-back", receipt, null);
		} catch (WorldBuilderContractException failure) {
			throw failure;
		} catch (IOException failure) {
			throw failure;
		} catch (Exception interrupted) {
			throw problem(WorldBuilderErrorCodes.RECOVERY_REQUIRED, "rollback", true,
				"Exact recovery was interrupted.",
				"Keep the target offline and retry exact recovery.", interrupted);
		}
	}

	private static WorldBuilderCurrentRuntimeOfflineLease recoveryLease(Path target, Map<String,Object> plan)
		throws IOException, WorldBuilderContractException {
		Map<String,Object> typed = object(object(plan.get("migrationPlan")).get("typedConfiguration"));
		Path installation = target.resolve(WorldBuilderCurrentRuntimeInstalledGeneration.INSTANCE + "/installation");
		if (!installedActivation(plan).isEmpty() && Files.exists(installation, LinkOption.NOFOLLOW_LINKS))
			return WorldBuilderCurrentRuntimeOfflineLease.acquireInstalled(installation, typed);
		return WorldBuilderCurrentRuntimeOfflineLease.acquire(target, typed,
			bool(object(plan.get("executionProfile")), "syntheticOnly"));
	}

	static final class RecoveryPreview {
		final Path target, workspace;
		final String transactionId, fingerprint;
		final Map<String,Object> plan;
		private final PendingReceiptTemporary pending;
		RecoveryPreview(Path target, Path workspace, String transactionId, Map<String,Object> plan,
			PendingReceiptTemporary pending, String fingerprint) {
			this.target = target; this.workspace = workspace; this.transactionId = transactionId;
			this.plan = plan; this.pending = pending; this.fingerprint = fingerprint;
		}
	}

	private static WorldBuilderCurrentRuntimeOfflineLease previewLease(Path target, Map<String,Object> plan, boolean synthetic)
		throws IOException, WorldBuilderContractException {
		Map<String,Object> typed = object(object(plan.get("migrationPlan")).get("typedConfiguration"));
		if (!synthetic && "MANAGED_N".equals(plan.get("classificationTier")))
			return WorldBuilderCurrentRuntimeOfflineLease.acquireInstalled(target.resolve(WorldBuilderCurrentRuntimeInstalledGeneration.INSTANCE + "/installation"), typed);
		return WorldBuilderCurrentRuntimeOfflineLease.acquire(target, typed, synthetic);
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
		String activationRelative = string(ledger.root, "activeLauncherRelativePath");
		String releasePrefix = RELEASE_PREFIX + composition.string("bundleInventoryHash") + "/";
		if (!activationRelative.startsWith(releasePrefix)
			|| !activationRelative.endsWith("/activation.json")) return false;
		String activationTransaction = activationRelative.substring(releasePrefix.length(),
			activationRelative.length() - "/activation.json".length());
		if (!activationTransaction.matches("[A-Za-z0-9._-]+")
			|| !array(ledger.root.get("transactionReceiptIds")).contains(
				activationTransaction)) return false;
		String releaseRelative = activationRelative.substring(0,
			activationRelative.length() - "/activation.json".length());
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
		WorldBuilderCurrentRuntimeExecutionProfile profile, Path packedSourceRoot,
		Path packedDiscoveryReport, Path preservationProject)
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
		plan.put("migrationPlan", profile.migrationPlan(target, classification, composition,
			packedSourceRoot, packedDiscoveryReport, preservationProject));
		Map<String,Object> projectReference = new LinkedHashMap<String,Object>();
		projectReference.put("projectId", string(project.root, "projectId"));
		projectReference.put("capabilityFingerprintSha256",
			string(project.root, "capabilityFingerprintSha256"));
		plan.put("projectCapability", projectReference);
		plan.put("destination", copyObject(classification.get("destination")));
		plan.put("compositionIdentitySha256", WorldBuilderHashes.sha256(
			WorldBuilderJsonDocuments.pretty(composition.identity).getBytes(StandardCharsets.UTF_8)));

		List<Object> preimage = preimageInventory(target, classification, adapter.root);
		if (!profile.syntheticOnly && "MANAGED_N".equals(classification.get("tier"))) {
			for (Object raw : array(object(plan.get("migrationPlan")).get("durableState"))) {
				Map<String,Object> state = object(raw); String relative = string(state, "relativePath");
				Path source = safeExistingFile(target, relative);
				Map<String,Object> record = new LinkedHashMap<String,Object>(); record.put("relativePath", relative);
				record.put("present", Boolean.TRUE); record.put("size", Long.valueOf(Files.size(source)));
				record.put("sha256", string(state, "sourceSha256")); record.put("backupRelativePath", "files/" + relative);
				requireFileMatches(source, record, relative); preimage.add(record);
			}
			preimage.sort((left, right) -> ((String)((Map<?,?>)left).get("relativePath")).compareTo((String)((Map<?,?>)right).get("relativePath")));
		}
		plan.put("preimageInventory", preimage);
		plan.put("preimageInventoryHash", canonicalHash(preimage));
		List<Object> semantic = semanticActions(classification);
		plan.put("semanticActions", semantic);
		plan.put("semanticActionsHash", canonicalHash(semantic));
		List<Object> artifacts = new ArrayList<Object>();
		String releaseRelative = RELEASE_PREFIX + composition.string("bundleInventoryHash")
			+ "/" + transactionId;
		for (WorldBuilderProviderCatalog.Artifact artifact : composition.artifacts) {
			Map<String,Object> action = new LinkedHashMap<String,Object>();
			action.put("sourcePath", artifact.sourcePath);
			action.put("bundlePath", artifact.bundlePath);
			action.put("installRelativePath", releaseRelative + "/" + artifact.bundlePath);
			action.put("mode", string(artifact.inventory, "mode"));
			action.put("size", artifact.inventory.get("size"));
			action.put("sha256", string(artifact.inventory, "sha256"));
			artifacts.add(action);
		}
		plan.put("artifactPlan", artifacts);
		validateProviderMigrationArtifactBinding(plan, artifacts, profile);
		plan.put("artifactPlanHash", canonicalHash(artifacts));
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
			&& profile.activationReady(object(plan.get("migrationPlan")))));
		plan.put("confirmationIdentity", "");
		plan.put("planFingerprintSha256", ZERO_HASH);
		plan.put("confirmationIdentity", "UPGRADE:" + transactionId + ":"
			+ reviewedInputHash(plan));
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

	private static void validateProviderMigrationArtifactBinding(Map<String,Object> plan,
		List<?> artifacts, WorldBuilderCurrentRuntimeExecutionProfile profile)
		throws WorldBuilderContractException {
		if (profile.syntheticOnly) return;
		Map<String,Object> migration = object(plan.get("migrationPlan"));
		Map<String,Object> execution = object(migration.get("stagedExecution"));
		Map<String,Object> binding = object(execution.get("providerStateMigration"));
		Map<String,Object> contract = null;
		Map<String,Object> tool = null;
		for (Object raw : artifacts) {
			Map<String,Object> artifact = object(raw);
			String bundle = string(artifact, "bundlePath");
			if (WorldBuilderPreservationStagedMigrator.STATE_CONTRACT_BUNDLE.equals(bundle)) {
				if (contract != null) throw problem(WorldBuilderErrorCodes.SOURCE_CORRUPT,
					bundle, false, "Provider migration manifest artifact is duplicated.",
					"Restore and resolve the exact provider composition.");
				contract = artifact;
			}
			if (WorldBuilderPreservationStagedMigrator.STATE_TOOL_BUNDLE.equals(bundle)) {
				if (tool != null) throw problem(WorldBuilderErrorCodes.SOURCE_CORRUPT,
					bundle, false, "Provider server-runtime artifact is duplicated.",
					"Restore and resolve the exact provider composition.");
				tool = artifact;
			}
		}
		if (contract == null || tool == null
			|| !string(contract, "sha256").equals(string(binding, "contractSha256"))
			|| !string(tool, "sha256").equals(string(binding, "toolSha256"))) throw problem(
			WorldBuilderErrorCodes.SOURCE_CORRUPT, "providerStateMigration", false,
			"Migration invocation is not hash-bound to the selected provider artifact plan.",
			"Restore the exact provider composition and preview a fresh transaction.");
	}

	private static Map<String,Object> activationLedger(Path target,
		WorldBuilderProviderCatalog.Composition composition,
		WorldBuilderCurrentRuntimeContracts.Document adapter,
		WorldBuilderCurrentRuntimeContracts.Document project,
		Map<String,Object> classification, List<Object> preimage, List<Object> semantic,
		String artifactPlanHash, String activationPlanBindingHash,
		String transactionId, String releaseRelative,
		WorldBuilderCurrentRuntimeExecutionProfile profile)
		throws IOException, WorldBuilderContractException {
		Map<String,Object> ledger = new LinkedHashMap<String,Object>();
		ledger.put("schemaVersion", Long.valueOf(1));
		ledger.put("manifestType", "world-builder-current-target-runtime-ledger");
		Map<String,Object> installed = object(classification.get("installedLedger"));
		String predecessor = string(installed, "ledgerFingerprintSha256");
		if (bool(installed, "present")) {
			String relative = string(adapter.root, "targetLedgerRelativePath");
			Path priorPath = WorldBuilderReadOnlyTarget.open(target).requiredFile(relative);
			Map<String,Object> prior = WorldBuilderCurrentRuntimeContracts.read(
				WorldBuilderCurrentRuntimeContracts.Kind.TARGET_LEDGER, priorPath).root;
			boolean exactPreimage = false;
			for (Object raw : preimage) {
				Map<String,Object> row = object(raw);
				if (relative.equals(string(row, "relativePath")) && bool(row, "present"))
					exactPreimage = string(row, "sha256").equals(WorldBuilderHashes.sha256(priorPath));
			}
			if (!predecessor.equals(string(prior, "ledgerFingerprintSha256")) || !exactPreimage)
				throw problem(WorldBuilderErrorCodes.TARGET_DRIFT, relative, false,
					"The installation identity differs from the classified ledger preimage.",
					"Keep the target offline and obtain a fresh upgrade preview.");
			ledger.put("targetInstallationId", string(prior, "targetInstallationId"));
		} else {
			String seed = string(project.root, "projectId") + ":" + canonicalHash(preimage);
			ledger.put("targetInstallationId", UUID.nameUUIDFromBytes(
				seed.getBytes(StandardCharsets.UTF_8)).toString());
		}
		for (String key : Arrays.asList("platformReleaseId", "platformManifestHash",
			"schemaSetHash", "variantId", "variantManifestHash", "moduleSetHash",
			"bundleInventoryHash", "bundleSpecId", "bundleSpecHash",
			"inputAdapterContractId")) ledger.put(key, composition.string(key));
		ledger.put("inputAdapterId", string(adapter.root, "adapterId"));
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
			WorldBuilderAdaptiveDurability.forceFile(destination);
			requireFileMatches(destination, record, relative);
		}
		writeNew(backup.resolve("preimage-inventory.json"),
			WorldBuilderJsonDocuments.pretty(preview.plan.get("preimageInventory")));
		WorldBuilderAdaptiveDurability.forceTreeDirectories(backup);
	}

	private Map<String,Object> stageRelease(Preview preview, Path staging)
		throws IOException, WorldBuilderContractException {
		return stageRelease(preview, staging, null);
	}

	private Map<String,Object> stageRelease(Preview preview, Path staging, Path verificationAttempt)
		throws IOException, WorldBuilderContractException {
		Map<String,Object> executionPlan = stageMigration(preview, staging);
		if (verificationAttempt != null) {
			WorldBuilderProviderCatalog.Composition composition = WorldBuilderProviderCatalog.resolve(
				preview.providerCatalogRoot, preview.compositionIdentity);
			executionPlan = bindRuntimeExecution(executionPlan,
				WorldBuilderCurrentRuntimeExecutionEvidence.run(staging, composition,
					object(executionPlan.get("migrationPlan")), generatedStateOutputs(executionPlan), verificationAttempt));
		}
		finishStaging(preview, staging, executionPlan);
		return executionPlan;
	}

	private Map<String,Object> stageMigration(Preview preview, Path staging)
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
		Files.createDirectory(staging.resolve("migration"));
		writeNew(staging.resolve("migration/migration-plan.json"),
			WorldBuilderJsonDocuments.pretty(preview.plan.get("migrationPlan")));
		if (!preview.profile.syntheticOnly) {
			Map<String,Object> migration = object(preview.plan.get("migrationPlan"));
			Map<String,Object> execution = object(migration.get("stagedExecution"));
			Map<String,Object> runtimeLayout = object(execution.get("runtimeLayout"));
			if (bool(runtimeLayout, "ready"))
				WorldBuilderCurrentRuntimeLayout.materialize(staging, runtimeLayout);
			Map<String,Object> map = object(migration.get("mapMigration"));
			if (bool(map, "packageReady")) {
				if (WorldBuilderCurrentBaseManagedInputs.BOUNDARY.equals(map.get("executionBoundary"))) {
					WorldBuilderCurrentBaseManagedInputs.stageMap(preview.targetRoot, staging, migration);
				} else if (WorldBuilderPreservationProjectEvidence.BOUNDARY.equals(map.get("executionBoundary"))) {
					if (preview.preservationProject == null) throw activationMismatch("preservation-project");
					WorldBuilderPreservationProjectEvidence.Verified genuine = WorldBuilderPreservationProjectEvidence.open(
						preview.preservationProject, preview.targetRoot, composition);
					WorldBuilderPreservationProjectEvidence.requireInspection(genuine.inspectConversion(), map);
					// Public forensic input is retained outside the immutable runnable release.
					Path retained = staging.resolveSibling(staging.getFileName() + "-preservation-source");
					genuine.stageSource(retained);
					WorldBuilderPreservationProjectEvidence.Verified reopened = WorldBuilderPreservationProjectEvidence.reopenStaged(retained, composition, map);
					Files.createDirectories(staging.resolve("migration/output/map"));
					reopened.convert(staging.resolve("migration/output/map/conversion"));
					WorldBuilderPackedConverter.normalizePrivateModes(staging.resolve("migration/output/map/conversion"));
					verifyReviewedPreservationMap(staging, map);
				} else {
				if (preview.packedSourceRoot == null || preview.packedDiscoveryReport == null)
					throw problem(WorldBuilderErrorCodes.SOURCE_CORRUPT, "mapMigration", false,
						"Reviewed packed conversion paths are absent from the in-memory preview.",
						"Review a fresh production transaction.");
				stageReviewedPreservationMap(preview.packedSourceRoot,
					preview.packedDiscoveryReport, staging, map);
				}
			}
			WorldBuilderPreservationStagedMigrator.writeTypedConfiguration(staging,
				object(migration.get("typedConfiguration")), execution, map);
			WorldBuilderPreservationStagedMigrator.stage(preview.targetRoot, staging,
				execution);
			WorldBuilderPreservationStagedMigrator.verify(preview.targetRoot, staging,
				execution, map);
		}
		return bindGeneratedState(preview.plan,
			preview.profile.syntheticOnly ? Collections.<Object>emptyList()
				: WorldBuilderCurrentRuntimeGeneratedState.capture(staging));
	}

	private void finishStaging(Preview preview, Path staging, Map<String,Object> executionPlan)
		throws IOException, WorldBuilderContractException {
		WorldBuilderProviderCatalog.Composition composition = WorldBuilderProviderCatalog.resolve(
			preview.providerCatalogRoot, preview.compositionIdentity);
		Map<String,Object> activation = activationDocument(executionPlan);
		writeNew(staging.resolve("activation.json"),
			WorldBuilderJsonDocuments.pretty(activation));
		verifyProviderReleaseTree(staging, "", composition.artifacts, activation,
			object(preview.plan.get("migrationPlan")));
	}

	/** Prepare and persist the exact initial cutover before any launchable target output. */
	private InitialActivation prepareInitialActivation(Preview preview, Path staging,
		Map<String,Object> executionPlan, Path transaction, Map<String,Path> serverSources,
		Map<String,Path> clientSources) throws IOException, WorldBuilderContractException {
		if (preview.profile.syntheticOnly || runtimeExecutionOutputs(executionPlan).isEmpty())
			throw activationMismatch("installedActivation");
		serverSources = initialServerSideSources(serverSources, transaction);
		WorldBuilderProviderCatalog.Composition composition = WorldBuilderProviderCatalog.resolve(
			preview.providerCatalogRoot, preview.compositionIdentity);
		Map<String,Object> migration = object(executionPlan.get("migrationPlan"));
		Map<String,Object> typed = object(migration.get("typedConfiguration"));
		Path instance = targetPath(preview.targetRoot, WorldBuilderCurrentRuntimeInstalledGeneration.INSTANCE);
		Path release = targetPath(preview.targetRoot, string(executionPlan, "releaseRelativePath"));
		WorldBuilderCurrentRuntimeInstance.Plan construction = WorldBuilderCurrentRuntimeInstance.inspectInitial(
			staging, release, instance, string(object(executionPlan.get("activationLedger")), "targetInstallationId"),
			string(executionPlan, "transactionId"), composition.identity,
			object(object(migration.get("stagedExecution")).get("runtimeLayout")), generatedStateOutputs(executionPlan),
			serverSources, clientSources, "127.0.0.1", (int)integer(typed, "gamePort"));
		Map<String,Object> constructionDocument = construction.document();
		Map<String,Object> specification = object(constructionDocument.get("specification"));
		Map<String,Object> mapManifest = readObject(staging.resolve(
			"migration/output/map/conversion/package/manifest.json"), "canonical-map");
		Map<String,Object> ledger = new LinkedHashMap<String,Object>(object(executionPlan.get("activationLedger")));
		ledger.put("activeMapPackageId", string(mapManifest, "packageId"));
		ledger.put("activeLauncherRelativePath", WorldBuilderCurrentRuntimeInstalledGeneration.INSTANCE
			+ "/installation/active-launch.json");
		ledger = WorldBuilderCurrentRuntimeInstalledGeneration.bind(ledger, preview.targetRoot,
			specification, composition.identity, mapManifest, string(object(executionPlan.get("projectCapability")), "projectId"));
		WorldBuilderCurrentRuntimeCutover.Plan cutover = WorldBuilderCurrentRuntimeCutover.inspect(
			preview.targetRoot, string(executionPlan, "transactionId"), "", "",
			WorldBuilderJsonDocuments.pretty(object(constructionDocument.get("generation")).get("activeSelection"))
				.getBytes(StandardCharsets.UTF_8), WorldBuilderJsonDocuments.pretty(ledger).getBytes(StandardCharsets.UTF_8));
		Path instancePlan = transaction.resolve("instance-plan.json");
		writeNew(instancePlan, WorldBuilderJsonDocuments.pretty(constructionDocument));
		WorldBuilderCurrentRuntimeCutover.journal(cutover, transaction.resolve("cutover"));
		Map<String,Object> binding = new LinkedHashMap<String,Object>();
		binding.put("policyId", "current-base-installed-upgrade-v1"); binding.put("mode", "initial");
		binding.put("instancePlanSha256", WorldBuilderHashes.sha256(instancePlan));
		binding.put("cutoverPlanSha256", cutover.fingerprint);
		return new InitialActivation(construction, cutover, bindInstalledActivation(executionPlan, binding));
	}

	private static final class InitialActivation {
		final WorldBuilderCurrentRuntimeInstance.Plan construction;
		final Map<String,Object> successor;
		final WorldBuilderCurrentRuntimeCutover.Plan cutover;
		final Map<String,Object> execution;
		InitialActivation(WorldBuilderCurrentRuntimeInstance.Plan construction,
			WorldBuilderCurrentRuntimeCutover.Plan cutover, Map<String,Object> execution) {
			this.construction = construction; this.cutover = cutover; this.execution = execution;
			this.successor = null;
		}
		InitialActivation(Map<String,Object> successor, WorldBuilderCurrentRuntimeCutover.Plan cutover, Map<String,Object> execution) {
			this.construction = null; this.successor = successor; this.cutover = cutover; this.execution = execution;
		}
	}

	private InitialActivation prepareSuccessorActivation(Preview preview, Path staging, Map<String,Object> executionPlan, Path transaction)
		throws IOException, WorldBuilderContractException {
		WorldBuilderProviderCatalog.Composition selected = WorldBuilderProviderCatalog.resolve(preview.providerCatalogRoot, preview.compositionIdentity);
		Path release = targetPath(preview.targetRoot, string(executionPlan, "releaseRelativePath"));
		Map<String,Object> construction = WorldBuilderCurrentRuntimeSuccessor.inspect(preview.targetRoot, staging, release,
			string(executionPlan, "transactionId"), selected.identity,
			object(object(object(executionPlan.get("migrationPlan")).get("stagedExecution")).get("runtimeLayout")), generatedStateOutputs(executionPlan));
		Map<String,Object> spec = object(construction.get("specification"));
		Map<String,Object> previous = WorldBuilderCurrentRuntimeContracts.read(WorldBuilderCurrentRuntimeContracts.Kind.TARGET_LEDGER,
			safeExistingFile(preview.targetRoot, LEDGER_RELATIVE)).root;
		String projectId = string(object(previous.get("installedInstance")), "projectId");
		if (!projectId.equals(string(object(executionPlan.get("projectCapability")), "projectId"))) throw activationMismatch("installed-projectId");
		Map<String,Object> manifest = readObject(staging.resolve("migration/output/map/conversion/package/manifest.json"), "retained-map");
		Map<String,Object> ledger = new LinkedHashMap<String,Object>(object(executionPlan.get("activationLedger")));
		ledger.put("activeMapPackageId", string(manifest, "packageId"));
		ledger.put("activeLauncherRelativePath", WorldBuilderCurrentRuntimeInstalledGeneration.INSTANCE + "/installation/active-launch.json");
		ledger = WorldBuilderCurrentRuntimeInstalledGeneration.bind(ledger, preview.targetRoot, spec, selected.identity, manifest, projectId);
		Path selection = preview.targetRoot.resolve(WorldBuilderCurrentRuntimeInstalledGeneration.INSTANCE + "/installation/active-launch.json");
		WorldBuilderCurrentRuntimeCutover.Plan cutover = WorldBuilderCurrentRuntimeCutover.inspect(preview.targetRoot,
			string(executionPlan, "transactionId"), WorldBuilderHashes.sha256(selection), WorldBuilderHashes.sha256(preview.targetRoot.resolve(LEDGER_RELATIVE)),
			WorldBuilderJsonDocuments.pretty(WorldBuilderCurrentRuntimeInstance.renderGeneration(spec).get("activeSelection")).getBytes(StandardCharsets.UTF_8),
			WorldBuilderJsonDocuments.pretty(ledger).getBytes(StandardCharsets.UTF_8));
		Path instancePlan = transaction.resolve("instance-plan.json"); writeNew(instancePlan, WorldBuilderJsonDocuments.pretty(construction));
		WorldBuilderCurrentRuntimeCutover.journal(cutover, transaction.resolve("cutover"));
		Map<String,Object> binding = new LinkedHashMap<String,Object>(); binding.put("policyId", "current-base-installed-upgrade-v1");
		binding.put("mode", "successor"); binding.put("instancePlanSha256", WorldBuilderHashes.sha256(instancePlan)); binding.put("cutoverPlanSha256", cutover.fingerprint);
		return new InitialActivation(construction, cutover, bindInstalledActivation(executionPlan, binding));
	}

	private void observeCutover(String milestone, Path transaction) throws IOException {
		try { observe("cutover-" + milestone, transaction); }
		catch (IOException failure) { throw failure; }
		catch (Exception failure) { throw new IOException("Interrupted installed cutover", failure); }
	}

	private static Map<String,Path> preservedSideSources(Preview preview, boolean server)
		throws IOException, WorldBuilderContractException {
		Map<String,Object> persistent = object(object(preview.plan.get("migrationPlan")).get("persistentInputs"));
		WorldBuilderPreservationPersistentInputs.reverify(preview.targetRoot, persistent);
		Map<String,Path> result = new LinkedHashMap<String,Path>();
		if (server) {
			for (String name : Arrays.asList("server.pem", "client.pem", "badwords.txt", "goodwords.txt", "alertwords.txt", "ipbans.txt", "ipbans.temp"))
				result.put(name, preview.targetRoot.resolve("server/" + name));
		} else {
			result.put("client.pem", preview.targetRoot.resolve("server/client.pem"));
			result.put("clientSettings.conf", preview.targetRoot.resolve("Client_Base/clientSettings.conf"));
			for (String name : Arrays.asList("uid.dat", "hideIp.txt", "credentials.txt")) {
				Path absent = preview.targetRoot.resolve("Client_Base/" + name);
				if (Files.exists(absent, LinkOption.NOFOLLOW_LINKS)) throw activationMismatch("unreviewed-client-side-state");
				result.put(name, absent);
			}
		}
		return result;
	}

	/** Missing historical filters mean empty filter lists, not the provider's defaults. */
	static Map<String,Path> initialServerSideSources(Map<String,Path> reviewed, Path transaction)
		throws IOException, WorldBuilderContractException {
		Map<String,Path> result = new LinkedHashMap<String,Path>(reviewed);
		Path defaults = transaction.resolve("initial-empty-filters");
		boolean created = false;
		for (String name : Arrays.asList("badwords.txt", "goodwords.txt", "alertwords.txt")) {
			Path source = reviewed.get(name);
			if (source == null) throw activationMismatch("unbound-filter-source");
			if (Files.exists(source, LinkOption.NOFOLLOW_LINKS)) continue;
			if (!created) {
				Files.createDirectory(defaults, java.nio.file.attribute.PosixFilePermissions.asFileAttribute(
					java.nio.file.attribute.PosixFilePermissions.fromString("rwx------")));
				created = true;
			}
			Path empty = defaults.resolve(name);
			Files.createFile(empty, java.nio.file.attribute.PosixFilePermissions.asFileAttribute(
				java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")));
			result.put(name, empty);
		}
		if (created) {
			WorldBuilderAdaptiveDurability.forceTree(defaults);
			WorldBuilderAdaptiveDurability.forceDirectory(transaction);
		}
		return result;
	}

	private static WorldBuilderCurrentRuntimeInstance.InitialOutputPlan initialOutputPlan(Path transaction, Map<String,Object> plan)
		throws IOException, WorldBuilderContractException {
		Path path = safeExistingFile(transaction, "instance-plan.json");
		if (!WorldBuilderHashes.sha256(path).equals(string(installedActivation(plan), "instancePlanSha256")))
			throw recoveryDrift("instance-plan", new IOException("Construction bytes differ from durable receipt authority"));
		Map<String,Object> document = readObject(path, "instance-plan");
		return WorldBuilderCurrentRuntimeInstance.validateInitialOutputPlan(document, string(document, "planFingerprintSha256"));
	}

	private static void rollbackInitialInstance(Path target, Path transaction, Map<String,Object> plan)
		throws IOException, WorldBuilderContractException {
		Path instance = targetPath(target, WorldBuilderCurrentRuntimeInstalledGeneration.INSTANCE);
		if (!Files.exists(instance, LinkOption.NOFOLLOW_LINKS)) return;
		try (WorldBuilderCurrentRuntimeInstanceLease roles = WorldBuilderCurrentRuntimeInstanceLease.acquire(instance.resolve("installation"))) {
			rollbackInitialInstanceHeld(target, transaction, plan, roles);
		}
	}

	private static void rollbackInitialInstanceHeld(Path target, Path transaction, Map<String,Object> plan,
		WorldBuilderCurrentRuntimeInstanceLease roles) throws IOException, WorldBuilderContractException {
		Map<String,Object> binding = installedActivation(plan);
		WorldBuilderCurrentRuntimeCutover.Plan cutover = WorldBuilderCurrentRuntimeCutover.read(
			transaction.resolve("cutover"), target, string(binding, "cutoverPlanSha256"));
		if (Files.exists(transaction.resolve("cutover/commit.json"), LinkOption.NOFOLLOW_LINKS))
			throw recoveryDrift("cutover", new IOException("Committed activation can never authorize deletion"));
		Path instance = targetPath(target, WorldBuilderCurrentRuntimeInstalledGeneration.INSTANCE);
		if (!Files.exists(instance, LinkOption.NOFOLLOW_LINKS)) return;
		if ("successor".equals(string(binding, "mode"))) {
			Path path = safeExistingFile(transaction, "instance-plan.json");
			if (!WorldBuilderHashes.sha256(path).equals(string(binding, "instancePlanSha256"))) throw activationMismatch("successor-plan");
			Map<String,Object> successor = readObject(path, "successor-plan");
			WorldBuilderCurrentRuntimeSuccessor.verify(successor, target, false);
			new WorldBuilderCurrentRuntimeCutover().recover(cutover, transaction.resolve("cutover"), roles);
			WorldBuilderCurrentRuntimeSuccessor.removeNeverStarted(successor, target, roles);
			return;
		}
		if (!"initial".equals(string(binding, "mode"))) throw activationMismatch("installedActivation");
		WorldBuilderCurrentRuntimeInstance.InitialOutputPlan initial = initialOutputPlan(transaction, plan);
		roles.verifyHeld(instance.resolve("installation"));
			if (Files.exists(transaction.resolve("cutover/rollback.json"), LinkOption.NOFOLLOW_LINKS)) {
				WorldBuilderCurrentRuntimeInstance.verifyRolledBackInitialOutputs(initial, instance, cutover);
			} else {
				WorldBuilderCurrentRuntimeInstance.verifyGuardedInitialOutputs(initial, instance, cutover);
				new WorldBuilderCurrentRuntimeCutover().recover(cutover, transaction.resolve("cutover"), roles);
				WorldBuilderCurrentRuntimeInstance.verifyRolledBackInitialOutputs(initial, instance, cutover);
			}
			// Complete detached initial seal and no commit prove this is never-started transaction output.
			deleteOwnedTree(instance);
			WorldBuilderAdaptiveDurability.forceDirectory(instance.getParent());
	}

	Map<String,Object> inspectReviewedPreservationMigration(Path target,
		WorldBuilderProviderCatalog.Composition composition, Path packedSource,
		Path packedReport) throws WorldBuilderContractException {
		Map<String,Object> classification = new LinkedHashMap<String,Object>();
		classification.put("evidence", new ArrayList<Object>());
		return WorldBuilderCurrentRuntimeExecutionProfile.preservation().migrationPlan(
			target, classification, composition, packedSource, packedReport);
	}

	void stageReviewedPreservationMap(Path packedSource, Path packedReport,
		Path staging, Map<String,Object> map)
		throws IOException, WorldBuilderContractException {
		Path mapParent = staging.resolve("migration/output/map");
		Files.createDirectories(mapParent);
		WorldBuilderPackedConverter.Result converted =
			new WorldBuilderPackedConverter().convert(packedSource,
				packedReport, mapParent.resolve("conversion"));
		Path convertedRoot = mapParent.resolve("conversion");
		WorldBuilderPackedConverter.normalizePrivateModes(convertedRoot);
		Map<String,Object> convertedPlan;
		try {
			convertedPlan = WorldBuilderJsonDocuments.readObject(
				convertedRoot.resolve("conversion-plan.json"));
		} catch (WorldBuilderDiscoveryException malformed) {
			throw problem(WorldBuilderErrorCodes.CONVERSION_BLOCKED,
				"mapMigration", false,
				"Packed conversion plan became malformed after conversion.",
				"Discard staging and preview the immutable source again.", malformed);
		}
		List<Object> convertedInventory = WorldBuilderPackedConverter.outputInventory(
			convertedRoot, "migration/output/map/conversion");
		if (!converted.sourceFingerprintSha256.equals(
				string(map, "preparedSourceFingerprintSha256"))
			|| !string(convertedPlan, "planFingerprintSha256").equals(
				string(map, "conversionPlanFingerprintSha256"))
			|| !converted.planSha256.equals(string(map, "conversionPlanSha256"))
			|| !converted.reportSha256.equals(string(map, "conversionReportSha256"))
			|| !converted.reconciliationSha256.equals(
				string(map, "discoveryReconciliationSha256"))
			|| !converted.outputFingerprintSha256.equals(
				string(map, "outputPackageFingerprintSha256"))
			|| converted.terrainCount != integer(map, "terrainCount")
			|| converted.placementCount != integer(map, "placementCount")
			|| !WorldBuilderJsonDocuments.canonical(convertedInventory).equals(
				WorldBuilderJsonDocuments.canonical(map.get("outputInventory"))))
			throw problem(WorldBuilderErrorCodes.CONVERSION_BLOCKED,
				"mapMigration", false,
				"Packed conversion result differs from the reviewed semantic preview.",
				"Discard staging and preview the immutable source again.");
		verifyReviewedPreservationMap(staging, map);
	}

	void verifyReviewedPreservationMap(Path staging, Map<String,Object> map)
		throws IOException, WorldBuilderContractException {
		Path convertedRoot = staging.resolve("migration/output/map/conversion");
		List<Object> actual = WorldBuilderPackedConverter.outputInventory(convertedRoot,
			"migration/output/map/conversion");
		if (!WorldBuilderJsonDocuments.canonical(actual).equals(
			WorldBuilderJsonDocuments.canonical(map.get("outputInventory"))))
			throw problem(WorldBuilderErrorCodes.TARGET_DRIFT, "mapMigration", false,
				"Canonical map output tree differs from the reviewed exact inventory.",
				"Discard staging and repeat conversion from exact target evidence.");
	}

	/** Package-private verification seam for an unpublished, externally staged release. */
	Map<String,Object> verifyStagedExecution(Preview preview, Path staging,
		Map<String,Object> executionPlan, Path attempt, java.util.function.BooleanSupplier cancellation)
		throws IOException, WorldBuilderContractException {
		if (preview.profile.syntheticOnly) throw problem(WorldBuilderErrorCodes.CONVERSION_BLOCKED,
			"execution-profile", false, "Synthetic transactions cannot claim real runtime execution.",
			"Use a reviewed Current Base staging composition.");
		Path attemptPath = attempt.toAbsolutePath();
		if (attemptPath.startsWith(preview.targetRoot) || preview.targetRoot.startsWith(attemptPath))
			throw problem(WorldBuilderErrorCodes.UNSAFE_PATH, "verification-attempt", false,
				"Disposable verification must remain outside the target.", "Select an external transaction workspace.");
		verifyReviewedRelease(preview, staging, executionPlan);
		WorldBuilderProviderCatalog.Composition composition = WorldBuilderProviderCatalog.resolve(
			preview.providerCatalogRoot, preview.compositionIdentity);
		Map<String,Object> evidence = WorldBuilderInstalledRuntimeVerifier.verify(staging, composition,
			object(executionPlan.get("migrationPlan")), generatedStateOutputs(executionPlan), attempt, cancellation);
		verifyReviewedRelease(preview, staging, executionPlan);
		return evidence;
	}

	/** Package-private staging seam; does not authorize target activation. */
	Map<String,Object> stageReviewedRelease(Preview preview, Path staging)
		throws IOException, WorldBuilderContractException {
		requireNewExternalStaging(preview, staging);
		Files.createDirectory(staging);
		return stageRelease(preview, staging);
	}

	/** Integration seam exercising the same execution sealing used before production publication. */
	Map<String,Object> stageReviewedVerifiedRelease(Preview preview, Path staging, Path attempt)
		throws IOException, WorldBuilderContractException {
		requireNewExternalStaging(preview, staging);
		requireNewExternalStaging(preview, attempt);
		if (preview.profile.syntheticOnly || staging.startsWith(attempt) || attempt.startsWith(staging))
			throw problem(WorldBuilderErrorCodes.UNSAFE_PATH, "verified-staging", false,
				"Verified staging requires a new external destination and a real provider execution profile.",
				"Keep the target unchanged and choose a fresh external verification workspace.");
		Files.createDirectory(staging);
		return stageRelease(preview, staging, attempt);
	}

	private static void requireNewExternalStaging(Preview preview, Path output)
		throws IOException, WorldBuilderContractException {
		if (output == null || !output.isAbsolute() || !output.equals(output.normalize())
			|| output.getParent() == null || Files.exists(output, LinkOption.NOFOLLOW_LINKS)
			|| !output.getParent().equals(output.getParent().toRealPath())
			|| !Files.isDirectory(output.getParent(), LinkOption.NOFOLLOW_LINKS))
			throw problem(WorldBuilderErrorCodes.UNSAFE_PATH, "staging", false,
				"Staging requires a literal new absolute path with a canonical existing parent.",
				"Choose fresh external staging paths without aliases; no inputs have been changed.");
		List<Path> inputs = new ArrayList<Path>(Arrays.asList(preview.targetRoot,
			preview.providerCatalogRoot, preview.compositionIdentity, preview.inputAdapter,
			preview.projectCapability, preview.packedSourceRoot, preview.packedDiscoveryReport));
		WorldBuilderProviderCatalog.Composition composition = WorldBuilderProviderCatalog.resolve(
			preview.providerCatalogRoot, preview.compositionIdentity);
		for (WorldBuilderProviderCatalog.Artifact artifact : composition.artifacts) {
			Path root = artifact.source;
			for (int i = 0; i < java.nio.file.Paths.get(artifact.sourcePath).getNameCount(); i++) root = root.getParent();
			inputs.add(root);
		}
		for (Path input : inputs) if (input != null && (output.startsWith(input) || input.startsWith(output)))
			throw problem(WorldBuilderErrorCodes.UNSAFE_PATH, "staging", false,
				"Staging overlaps a target, preserved source, or selected provider input.",
				"Choose a new external staging destination; no inputs have been changed.");
	}

	void verifyReviewedRelease(Preview preview, Path staging, Map<String,Object> executionPlan)
		throws IOException, WorldBuilderContractException {
		WorldBuilderProviderCatalog.Composition composition =
			WorldBuilderProviderCatalog.resolve(preview.providerCatalogRoot,
				preview.compositionIdentity);
		verifyProviderReleaseTree(staging, "", composition.artifacts,
			activationDocument(bindRuntimeExecution(bindGeneratedState(preview.plan,
				generatedStateOutputs(executionPlan)), runtimeExecutionOutputs(executionPlan))),
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
			if (Boolean.parseBoolean(System.getProperty(
				"worldbuilder.currentRuntime.testLedgerPostMoveForceFailure", "false")))
				throw new IOException("injected ledger post-move force failure");
			WorldBuilderAdaptiveDurability.forceFile(ledger);
			WorldBuilderAdaptiveDurability.forceDirectory(ledger.getParent());
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
		Path ledgerTemporary = ledger.getParent().resolve(".runtime-ledger-v1.json.upgrade");
		boolean exactTemporary = false;
		if (Files.exists(ledgerTemporary, LinkOption.NOFOLLOW_LINKS)) {
			BasicFileAttributes temporaryAttributes = Files.readAttributes(ledgerTemporary,
				BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
			byte[] expectedTemporary = WorldBuilderJsonDocuments.pretty(
				object(plan.get("activationLedger"))).getBytes(StandardCharsets.UTF_8);
			if (!temporaryAttributes.isRegularFile() || temporaryAttributes.isSymbolicLink()
				|| temporaryAttributes.size() != expectedTemporary.length
				|| !WorldBuilderHashes.sha256(ledgerTemporary).equals(
					WorldBuilderHashes.sha256(expectedTemporary))) throw recoveryDrift(
					"ledger-upgrade-temporary", new IOException(
						"temporary ledger is not exact transaction-owned activation bytes"));
			exactTemporary = true;
		}
		Path release = targetPath(target, string(plan, "releaseRelativePath"));
		int ledgerState = ledgerActivated
			? requireRollbackLedgerState(ledger, ledgerRecord,
				object(plan.get("activationLedger"))) : 0;
		Path rollbackTemporary = ledger.getParent().resolve(
			".runtime-ledger-v1.json.rollback");
		if (Files.exists(rollbackTemporary, LinkOption.NOFOLLOW_LINKS)) {
			if (!bool(ledgerRecord, "present")) throw recoveryDrift(
				"ledger-rollback-temporary", new IOException(
					"rollback temporary exists for an absent preimage"));
			prepareRollbackTemporary(safeExistingFile(backup,
				string(ledgerRecord, "backupRelativePath")), rollbackTemporary,
				ledgerRecord);
			if (ledgerState == 2) {
				Files.delete(rollbackTemporary);
				WorldBuilderAdaptiveDurability.forceDirectory(
					rollbackTemporary.getParent());
			}
		}
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
			prepareRollbackTemporary(source, rollbackTemporary, ledgerRecord);
			Files.move(rollbackTemporary, ledger, StandardCopyOption.ATOMIC_MOVE,
				StandardCopyOption.REPLACE_EXISTING);
			WorldBuilderAdaptiveDurability.forceFile(ledger);
			WorldBuilderAdaptiveDurability.forceDirectory(ledger.getParent());
		} else if (ledgerState == 1) {
			requireRollbackLedgerState(ledger, ledgerRecord,
				object(plan.get("activationLedger")));
			Files.delete(ledger);
			WorldBuilderAdaptiveDurability.forceDirectory(ledger.getParent());
		}
		if (exactTemporary) {
			Files.delete(ledgerTemporary);
			WorldBuilderAdaptiveDurability.forceDirectory(ledgerTemporary.getParent());
		}
		if (releaseExists) {
			verifyOwnedReleaseTree(target, plan);
			deleteOwnedTree(release);
			WorldBuilderAdaptiveDurability.forceDirectory(release.getParent());
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

	private static void prepareRollbackTemporary(Path source, Path temporary,
		Map<String,Object> preimage) throws IOException, WorldBuilderContractException {
		if (Files.exists(temporary, LinkOption.NOFOLLOW_LINKS)) {
			BasicFileAttributes attributes = Files.readAttributes(temporary,
				BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
			if (!attributes.isRegularFile() || attributes.isSymbolicLink())
				throw recoveryDrift("ledger-rollback-temporary",
					new IOException("rollback temporary is linked or unsafe"));
			WorldBuilderAdaptiveExporter.rejectHardLink(temporary,
				".runtime-ledger-v1.json.rollback");
			requireFileMatches(temporary, preimage, LEDGER_RELATIVE);
			return;
		}
		Files.copy(source, temporary);
		WorldBuilderAdaptiveDurability.forceFile(temporary);
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
		activation.put("manifestType", string(
			object(plan.get("executionProfile")), "activationManifestType"));
		activation.put("transactionId", string(plan, "transactionId"));
		activation.put("planBindingHash", activationPlanBindingHash(plan));
		activation.put("classificationFingerprintSha256",
			string(plan, "classificationFingerprintSha256"));
		activation.put("preimageInventoryHash", string(plan, "preimageInventoryHash"));
		activation.put("destination", plan.get("destination"));
		activation.put("compositionIdentitySha256", plan.get("compositionIdentitySha256"));
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
		activation.put("generatedStateOutputs", generatedStateOutputs(plan));
		activation.put("runtimeExecutionOutputs", runtimeExecutionOutputs(plan));
		return activation;
	}

	/** In-memory execution view only; never serialize this as a confirmed upgrade plan. */
	private static Map<String,Object> bindGeneratedState(Map<String,Object> plan, List<Object> generated)
		throws WorldBuilderContractException {
		WorldBuilderCurrentRuntimeGeneratedState.validate(generated, true);
		if (bool(object(plan.get("executionProfile")), "syntheticOnly") && !generated.isEmpty())
			throw activationMismatch("generatedStateOutputs");
		Map<String,Object> result = new LinkedHashMap<String,Object>(plan);
		List<Object> snapshot = new ArrayList<Object>();
		for (Object raw : generated) snapshot.add(Collections.unmodifiableMap(
			new LinkedHashMap<String,Object>(object(raw))));
		result.put("generatedStateOutputs", Collections.unmodifiableList(snapshot));
		String verification = generatedVerificationHash(
			string(plan, "verificationEvidenceHash"), generated);
		result.put("verificationEvidenceHash", verification);
		Map<String,Object> ledger = new LinkedHashMap<String,Object>(object(plan.get("activationLedger")));
		ledger.put("verificationEvidenceHash", verification);
		bindFingerprint(ledger, "ledgerFingerprintSha256");
		result.put("activationLedger", ledger);
		return result;
	}

	private static List<Object> runtimeExecutionOutputs(Map<String,Object> document)
		throws WorldBuilderContractException {
		return document.containsKey("runtimeExecutionOutputs")
			? new ArrayList<Object>(array(document.get("runtimeExecutionOutputs"))) : Collections.<Object>emptyList();
	}

	private static Map<String,Object> runtimeAttempt(Map<String,Object> document) throws WorldBuilderContractException {
		return document.containsKey("runtimeVerificationAttempt")
			? object(document.get("runtimeVerificationAttempt")) : Collections.<String,Object>emptyMap();
	}

	/** External receipt authority for the two-file irreversible cutover. Empty before preparation. */
	private static Map<String,Object> installedActivation(Map<String,Object> document)
		throws WorldBuilderContractException {
		return document.containsKey("installedActivation") ? object(document.get("installedActivation"))
			: Collections.<String,Object>emptyMap();
	}

	private static void validateInstalledActivation(Map<String,Object> binding)
		throws WorldBuilderContractException {
		if (binding.isEmpty()) return;
		WorldBuilderBoundedInventory.exactKeys(binding, OPERATION,
			"policyId", "mode", "instancePlanSha256", "cutoverPlanSha256");
		if (!"current-base-installed-upgrade-v1".equals(string(binding, "policyId"))
			|| !Arrays.asList("initial", "successor").contains(string(binding, "mode")))
			throw recoveryDrift("installedActivation", new IOException("Unsupported installed cutover policy"));
		requireHash(string(binding, "instancePlanSha256"), "instancePlanSha256");
		requireHash(string(binding, "cutoverPlanSha256"), "cutoverPlanSha256");
	}

	private static Map<String,Object> bindInstalledActivation(Map<String,Object> plan, Map<String,Object> binding)
		throws WorldBuilderContractException {
		validateInstalledActivation(binding);
		if (!binding.isEmpty() && (bool(object(plan.get("executionProfile")), "syntheticOnly")
			|| generatedStateOutputs(plan).isEmpty() || runtimeExecutionOutputs(plan).isEmpty()))
			throw activationMismatch("installedActivation");
		Map<String,Object> result = new LinkedHashMap<String,Object>(plan);
		result.put("installedActivation", Collections.unmodifiableMap(new LinkedHashMap<String,Object>(binding)));
		return result;
	}

	private static Map<String,Object> bindRuntimeAttempt(Map<String,Object> plan, Map<String,Object> attempt)
		throws WorldBuilderContractException {
		WorldBuilderCurrentRuntimeVerifierAuthority.validate(attempt, true);
		if (bool(object(plan.get("executionProfile")), "syntheticOnly") && !attempt.isEmpty())
			throw activationMismatch("runtimeVerificationAttempt");
		Map<String,Object> result = new LinkedHashMap<String,Object>(plan);
		result.put("runtimeVerificationAttempt", attempt); return result;
	}

	private static Map<String,Object> bindRuntimeExecution(Map<String,Object> execution,
		List<Object> records) throws WorldBuilderContractException {
		WorldBuilderCurrentRuntimeExecutionEvidence.validate(records, true);
		if (records.isEmpty()) return execution;
		if (bool(object(execution.get("executionProfile")), "syntheticOnly"))
			throw activationMismatch("runtimeExecutionOutputs");
		Map<String,Object> result = new LinkedHashMap<String,Object>(execution);
		result.put("runtimeExecutionOutputs", Collections.unmodifiableList(new ArrayList<Object>(records)));
		String verification = executionVerificationHash(string(execution, "verificationEvidenceHash"), records);
		result.put("verificationEvidenceHash", verification);
		Map<String,Object> ledger = new LinkedHashMap<String,Object>(object(execution.get("activationLedger")));
		ledger.put("verificationEvidenceHash", verification);
		bindFingerprint(ledger, "ledgerFingerprintSha256");
		result.put("activationLedger", ledger);
		return result;
	}

	private static String executionVerificationHash(String generatedVerification, List<Object> records) {
		if (records.isEmpty()) return generatedVerification;
		Map<String,Object> binding = new LinkedHashMap<String,Object>();
		binding.put("generatedVerificationEvidenceHash", generatedVerification);
		binding.put("runtimeExecutionOutputsHash", canonicalHash(records));
		return canonicalHash(binding);
	}

	static Map<String,Object> restoreExecutionPlan(Map<String,Object> plan,
		Map<String,Object> priorReceipt, Map<String,Object> pendingReceipt)
		throws WorldBuilderContractException {
		validateExecutionReceipt(plan, priorReceipt);
		List<Object> generated = generatedStateOutputs(priorReceipt);
		List<Object> runtime = runtimeExecutionOutputs(priorReceipt);
		Map<String,Object> attempt = runtimeAttempt(priorReceipt);
		Map<String,Object> activation = installedActivation(priorReceipt);
		if (pendingReceipt != null) {
			validateExecutionReceipt(plan, pendingReceipt);
			List<Object> pendingGenerated = generatedStateOutputs(pendingReceipt);
			if (!generated.isEmpty() && !canonicalHash(generated).equals(canonicalHash(pendingGenerated)))
				throw recoveryDrift("generatedStateOutputs", new IOException(
					"phase receipts disagree about sealed generated state"));
			if (generated.isEmpty()) generated = pendingGenerated;
			List<Object> pendingRuntime = runtimeExecutionOutputs(pendingReceipt);
			if (!runtime.isEmpty() && !canonicalHash(runtime).equals(canonicalHash(pendingRuntime)))
				throw recoveryDrift("runtimeExecutionOutputs", new IOException("phase receipts disagree about execution proof"));
			if (runtime.isEmpty()) runtime = pendingRuntime;
			Map<String,Object> pendingAttempt = runtimeAttempt(pendingReceipt);
			if (!attempt.isEmpty() && !canonicalHash(attempt).equals(canonicalHash(pendingAttempt)))
				throw recoveryDrift("runtimeVerificationAttempt", new IOException("phase receipts disagree about live authority"));
			if (attempt.isEmpty()) attempt = pendingAttempt;
			Map<String,Object> pendingActivation = installedActivation(pendingReceipt);
			if (!activation.isEmpty() && !canonicalHash(activation).equals(canonicalHash(pendingActivation)))
				throw recoveryDrift("installedActivation", new IOException("phase receipts disagree about cutover authority"));
			if (activation.isEmpty()) activation = pendingActivation;
		}
		return bindInstalledActivation(bindRuntimeAttempt(bindRuntimeExecution(bindGeneratedState(plan, generated), runtime), attempt), activation);
	}

	private static void validateExecutionReceipt(Map<String,Object> plan, Map<String,Object> receipt)
		throws WorldBuilderContractException {
		validateReceiptFingerprint(receipt);
		for (String field : Arrays.asList("transactionId", "planFingerprintSha256",
			"preimageInventoryHash", "artifactPlanHash"))
			if (!string(plan, field).equals(string(receipt, field)))
				throw recoveryDrift(field, new IOException("receipt does not bind the confirmed plan"));
		String status = string(receipt, "status");
		boolean synthetic = bool(object(plan.get("executionProfile")), "syntheticOnly");
		List<Object> generated = generatedStateOutputs(receipt);
		boolean requiresSeal = bool(receipt, "mutationOccurred") || "successful".equals(status)
			|| "pending".equals(status) && !"backup-complete".equals(string(receipt, "failureType"));
		WorldBuilderCurrentRuntimeGeneratedState.validate(generated, synthetic || !requiresSeal);
		List<Object> runtime = runtimeExecutionOutputs(receipt);
		WorldBuilderCurrentRuntimeExecutionEvidence.validate(runtime,
			synthetic || !requiresSeal || "pending".equals(status)
				&& Arrays.asList("migration-staged", "verification-prepared").contains(string(receipt, "failureType")));
		Map<String,Object> attempt = runtimeAttempt(receipt);
		WorldBuilderCurrentRuntimeVerifierAuthority.validate(attempt, true);
		if (synthetic && !attempt.isEmpty()) throw activationMismatch("runtimeVerificationAttempt");
		if (!synthetic && "pending".equals(status) && "verification-prepared".equals(string(receipt, "failureType")) && attempt.isEmpty())
			throw recoveryDrift("runtimeVerificationAttempt", new IOException("prepared phase lacks its authority binding"));
		Map<String,Object> execution = bindRuntimeExecution(bindGeneratedState(plan, generated), runtime);
		bindInstalledActivation(execution, installedActivation(receipt));
		if ("successful".equals(status) && !string(execution, "verificationEvidenceHash").equals(
			string(receipt, "verificationEvidenceHash")))
			throw recoveryDrift("verificationEvidenceHash", new IOException(
				"successful receipt does not bind generated state"));
	}

	private static String generatedVerificationHash(String plannedVerification, List<Object> generated) {
		if (generated.isEmpty()) return plannedVerification;
		Map<String,Object> binding = new LinkedHashMap<String,Object>();
		binding.put("plannedVerificationEvidenceHash", plannedVerification);
		binding.put("generatedStateOutputsHash", canonicalHash(generated));
		return canonicalHash(binding);
	}

	private static List<Object> generatedStateOutputs(Map<String,Object> document)
		throws WorldBuilderContractException {
		return document.containsKey("generatedStateOutputs")
			? new ArrayList<Object>(array(document.get("generatedStateOutputs")))
			: Collections.<Object>emptyList();
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
		binding.put("compositionIdentitySha256", string(source, "compositionIdentitySha256"));
		binding.put("projectCapability", source.get("projectCapability"));
		binding.put("inputAdapter", source.get("inputAdapter"));
		binding.put("executionProfile", source.get("executionProfile"));
		binding.put("migrationPlan", source.get("migrationPlan"));
		Map<String,Object> destination = object(source.get("destination"));
		binding.put("releaseRelativePath", RELEASE_PREFIX
			+ string(destination, "bundleInventoryHash") + "/"
			+ string(source, "transactionId"));
		return canonicalHash(binding);
	}

	private static void validateActivation(Map<String,Object> activation,
		WorldBuilderProviderCatalog.Composition composition,
		Map<String,Object> adapter, Map<String,Object> project,
		Map<String,Object> ledger) throws WorldBuilderContractException {
		WorldBuilderBoundedInventory.exactKeys(activation, OPERATION,
			"schemaVersion", "manifestType", "transactionId", "planBindingHash",
			"classificationFingerprintSha256", "preimageInventoryHash",
			"destination", "compositionIdentitySha256", "projectCapability",
			"inputAdapter", "artifactPlanHash",
			"semanticActionsHash", "verificationEvidenceHash", "serverBuildId",
			"clientBuildId", "activeMapPackageId", "syntheticOnly",
			"executionProfile", "migrationPlan", "generatedStateOutputs", "runtimeExecutionOutputs");
		Map<String,Object> executionProfile = object(activation.get("executionProfile"));
		if (!WorldBuilderHashes.sha256(WorldBuilderJsonDocuments.pretty(composition.identity)
			.getBytes(StandardCharsets.UTF_8)).equals(string(activation, "compositionIdentitySha256")))
			throw activationMismatch("compositionIdentitySha256");
		WorldBuilderCurrentRuntimeExecutionProfile compiledProfile =
			WorldBuilderCurrentRuntimeExecutionProfile.fromIdentity(executionProfile);
		List<Object> generated = generatedStateOutputs(activation);
		List<Object> runtime = runtimeExecutionOutputs(activation);
		WorldBuilderCurrentRuntimeExecutionEvidence.validate(runtime, compiledProfile.syntheticOnly);
		if (compiledProfile.syntheticOnly && !runtime.isEmpty()) throw activationMismatch("runtimeExecutionOutputs");
		WorldBuilderCurrentRuntimeGeneratedState.validate(generated, compiledProfile.syntheticOnly);
		if (compiledProfile.syntheticOnly && !generated.isEmpty())
			throw activationMismatch("generatedStateOutputs");
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
		if (!providerArtifactPlanHash(composition,
			string(activation, "transactionId")).equals(
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
		if (!executionVerificationHash(generatedVerificationHash(canonicalHash(verification), generated), runtime).equals(
			string(ledger, "verificationEvidenceHash")))
			throw activationMismatch("verificationEvidenceHash");
		if (!activationPlanBindingHash(activation).equals(
			string(activation, "planBindingHash")))
			throw activationMismatch("planBindingHash");
	}

	private static String providerArtifactPlanHash(
		WorldBuilderProviderCatalog.Composition composition, String transactionId)
		throws WorldBuilderContractException {
		List<Object> artifacts = new ArrayList<Object>();
		for (WorldBuilderProviderCatalog.Artifact artifact : composition.artifacts) {
			Map<String,Object> action = new LinkedHashMap<String,Object>();
			action.put("sourcePath", artifact.sourcePath);
			action.put("bundlePath", artifact.bundlePath);
			action.put("installRelativePath", RELEASE_PREFIX
				+ composition.string("bundleInventoryHash") + "/" + transactionId
				+ "/" + artifact.bundlePath);
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
		verifyExecutionRelease(targetPath(target, string(plan, "releaseRelativePath")), plan);
	}

	static void verifyExecutionRelease(Path release, Map<String,Object> plan)
		throws IOException, WorldBuilderContractException {
		List<Map<String,Object>> artifacts = new ArrayList<Map<String,Object>>();
		for (Object raw : array(plan.get("artifactPlan"))) artifacts.add(object(raw));
		verifyReleaseTree(release, "", artifacts,
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
		if (expectedActivation != null) for (Object raw : runtimeExecutionOutputs(expectedActivation)) {
			String relative = string(object(raw), "relativePath");
			expectedFiles.add(relative); addParentDirectories(relative, expectedDirectories);
		}
		List<Map<String,Object>> migrationOutputs = new ArrayList<Map<String,Object>>();
		if (expectedMigration != null) {
			Map<String,Object> execution = object(expectedMigration.get("stagedExecution"));
			if (execution.containsKey("runtimeLayout")) {
				Map<String,Object> layout = object(execution.get("runtimeLayout"));
				if (bool(layout, "ready")) for (Object raw : array(layout.get("outputs"))) {
					Map<String,Object> output = object(raw);
					String relative = string(output, "relativePath");
					expectedFiles.add(relative);
					addParentDirectories(relative, expectedDirectories);
					migrationOutputs.add(output);
				}
			}
			for (Object raw : array(execution.get("stagedOutputs"))) {
				Map<String,Object> output = object(raw);
				String relative = string(output, "relativePath");
				expectedFiles.add(relative);
				addParentDirectories(relative, expectedDirectories);
				migrationOutputs.add(output);
			}
			Map<String,Object> state = execution.containsKey("providerStateMigration")
				? object(execution.get("providerStateMigration")) : null;
			if (state != null && "sqlite".equals(string(state, "engine"))) {
				for (String relative : new String[] {string(state, "stageRelativePath"),
					string(state, "evidenceRelativePath")}) {
					expectedFiles.add(relative);
					addParentDirectories(relative, expectedDirectories);
				}
			}
			Map<String,Object> map = object(expectedMigration.get("mapMigration"));
			for (Object raw : array(map.get("outputInventory"))) {
				Map<String,Object> output = object(raw);
				String relative = string(output, "relativePath");
				expectedFiles.add(relative);
				addParentDirectories(relative, expectedDirectories);
				migrationOutputs.add(output);
			}
		}
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
		for (Map<String,Object> output : migrationOutputs) {
			String relative = string(output, "relativePath");
			Path installed = safeExistingFile(release, relative);
			requireFileMatches(installed, output, relative);
			if (!string(output, "mode").equals(fileMode(installed))) throw problem(
				WorldBuilderErrorCodes.SOURCE_CORRUPT, relative, false,
				"Installed migration output mode differs from its reviewed inventory.",
				"Keep map import disabled and recover/reinstall the exact composition.");
		}
		if (expectedActivation != null) {
			List<Object> generated = generatedStateOutputs(expectedActivation);
			boolean synthetic = bool(expectedActivation, "syntheticOnly");
			WorldBuilderCurrentRuntimeGeneratedState.validate(generated, synthetic);
			if (synthetic && !generated.isEmpty()) throw activationMismatch("generatedStateOutputs");
			WorldBuilderCurrentRuntimeGeneratedState.verify(release, generated);
			WorldBuilderCurrentRuntimeExecutionEvidence.verify(release, runtimeExecutionOutputs(expectedActivation),
				object(expectedActivation.get("destination")), string(expectedActivation, "compositionIdentitySha256"), expectedMigration);
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
				WorldBuilderAdaptiveDurability.forceDirectory(current);
				WorldBuilderAdaptiveDurability.forceDirectory(current.getParent());
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
			WorldBuilderAdaptiveDurability.forceTree(staging);
			Files.move(staging, release, StandardCopyOption.ATOMIC_MOVE);
			if (Boolean.parseBoolean(System.getProperty(
				"worldbuilder.currentRuntime.testReleasePostMoveForceFailure", "false")))
				throw new IOException("injected release post-move force failure");
			WorldBuilderAdaptiveDurability.forceDirectory(release.getParent());
			WorldBuilderAdaptiveDurability.forceDirectory(staging.getParent());
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

	static Map<String,Object> receipt(Map<String,Object> plan, String status,
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
		receipt.put("generatedStateOutputs", generatedStateOutputs(plan));
		receipt.put("runtimeExecutionOutputs", runtimeExecutionOutputs(plan));
		receipt.put("runtimeVerificationAttempt", runtimeAttempt(plan));
		receipt.put("installedActivation", installedActivation(plan));
		receipt.put("failureType", failureType == null ? "" : failureType);
		receipt.put("receiptFingerprintSha256", ZERO_HASH);
		bindFingerprint(receipt, "receiptFingerprintSha256");
		return receipt;
	}

	private static void writeReceipt(Path path, Map<String,Object> receipt)
		throws IOException, WorldBuilderContractException {
		Path temporary = path.getParent().resolve(".receipt.json.tmp");
		if (Files.exists(temporary, LinkOption.NOFOLLOW_LINKS)) throw problem(
			WorldBuilderErrorCodes.RECOVERY_REQUIRED, "receipt", true,
			"A prior receipt staging file exists and was preserved.",
			"Keep the transaction offline and inspect its exact durable evidence.");
		Files.write(temporary, WorldBuilderJsonDocuments.pretty(receipt)
			.getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE_NEW,
			StandardOpenOption.WRITE);
		WorldBuilderAdaptiveDurability.forceFile(temporary);
		if (string(receipt, "status").equals(System.getProperty(
			"worldbuilder.currentRuntime.testReceiptHaltStatus", "")))
			Runtime.getRuntime().halt(92);
		try {
			if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
				BasicFileAttributes current = Files.readAttributes(path,
					BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
				if (!current.isRegularFile() || current.isSymbolicLink()
					|| current.fileKey() == null) throw problem(
						WorldBuilderErrorCodes.RECOVERY_REQUIRED, "receipt", true,
						"Durable receipt destination is not one stable regular file.",
						"Preserve the transaction and request exact recovery.");
				WorldBuilderAdaptiveExporter.rejectHardLink(path, "receipt.json");
				Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE,
					StandardCopyOption.REPLACE_EXISTING);
			} else {
				WorldBuilderAdaptiveAtomicFiles.moveNew(temporary, path,
					OPERATION, "receipt.json");
			}
			WorldBuilderAdaptiveDurability.forceFile(path);
			WorldBuilderAdaptiveDurability.forceDirectory(path.getParent());
		} catch (AtomicMoveNotSupportedException unsupported) {
			Files.deleteIfExists(temporary); throw unsupported;
		}
	}

	private static void writeNew(Path path, String value)
		throws IOException, WorldBuilderContractException {
		Files.write(path, value.getBytes(StandardCharsets.UTF_8),
			StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
		WorldBuilderAdaptiveDurability.forceFile(path);
		WorldBuilderAdaptiveDurability.forceDirectory(path.getParent());
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
		String confirmation = string(plan, "confirmationIdentity");
		String expectedConfirmation = "UPGRADE:" + string(plan, "transactionId") + ":"
			+ reviewedInputHash(plan);
		if (!confirmation.equals(expectedConfirmation)) throw problem(
			WorldBuilderErrorCodes.SOURCE_CORRUPT, "confirmationIdentity", true,
			"Recovery confirmation identity does not bind the complete reviewed plan.",
			"Restore the exact sealed transaction plan.");
	}

	private static String reviewedInputHash(Map<String,Object> plan) {
		Map<String,Object> reviewed = new LinkedHashMap<String,Object>(plan);
		reviewed.put("confirmationIdentity", "");
		reviewed.put("planFingerprintSha256", ZERO_HASH);
		return canonicalHash(reviewed);
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
		final Path packedSourceRoot;
		final Path packedDiscoveryReport;
		final Path preservationProject;
		final WorldBuilderCurrentRuntimeExecutionProfile profile;
		final Map<String,Object> plan;

		Preview(Path targetRoot, Path transactionRoot, Path providerCatalogRoot,
			Path compositionIdentity, Path inputAdapter, Path projectCapability,
			WorldBuilderCurrentRuntimeExecutionProfile profile, Path packedSourceRoot,
			Path packedDiscoveryReport, Path preservationProject, Map<String,Object> plan) {
			this.targetRoot = targetRoot; this.transactionRoot = transactionRoot;
			this.providerCatalogRoot = providerCatalogRoot;
			this.compositionIdentity = compositionIdentity;
			this.inputAdapter = inputAdapter; this.projectCapability = projectCapability;
			this.packedSourceRoot = packedSourceRoot;
			this.packedDiscoveryReport = packedDiscoveryReport;
			this.preservationProject = preservationProject;
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
			"executionProfile", "migrationPlan", "projectCapability", "destination", "compositionIdentitySha256", "preimageInventory",
			"preimageInventoryHash", "semanticActions", "semanticActionsHash",
			"artifactPlan", "artifactPlanHash", "releaseRelativePath", "stagingPolicy",
			"activationLedgerRelativePath", "activationLedger",
			"verificationEvidenceHash", "mapImportAvailableBeforeApply",
			"mutationOccurred", "activationAuthorized", "confirmationIdentity", "planFingerprintSha256");
		WorldBuilderCurrentRuntimeExecutionProfile profile =
			WorldBuilderCurrentRuntimeExecutionProfile.fromIdentity(
				object(plan.get("executionProfile")));
		requireHash(string(plan, "compositionIdentitySha256"), "compositionIdentitySha256");
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
		String release = RELEASE_PREFIX + string(destination, "bundleInventoryHash")
			+ "/" + string(plan, "transactionId");
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
		validateProviderMigrationArtifactBinding(plan,
			array(plan.get("artifactPlan")), profile);
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
		// Legacy unbound receipts remain readable, but can never authorize verifier cleanup.
		List<String> receiptKeys = new ArrayList<String>(Arrays.asList(
			"schemaVersion", "manifestType", "transactionId", "planFingerprintSha256",
			"status", "mutationOccurred", "rollbackComplete", "recoveryRequired",
			"preimageInventoryHash", "artifactPlanHash", "verificationEvidenceHash",
			"failureType", "receiptFingerprintSha256", "generatedStateOutputs", "runtimeExecutionOutputs"));
		if (receipt.containsKey("runtimeVerificationAttempt")) receiptKeys.add("runtimeVerificationAttempt");
		if (receipt.containsKey("installedActivation")) receiptKeys.add("installedActivation");
		WorldBuilderBoundedInventory.exactKeys(receipt, OPERATION, receiptKeys.toArray(new String[0]));
		WorldBuilderCurrentRuntimeVerifierAuthority.validate(runtimeAttempt(receipt), true);
		validateInstalledActivation(installedActivation(receipt));
		WorldBuilderCurrentRuntimeGeneratedState.validate(generatedStateOutputs(receipt), true);
		WorldBuilderCurrentRuntimeExecutionEvidence.validate(runtimeExecutionOutputs(receipt), true);
		String status = string(receipt, "status");
		if (integer(receipt, "schemaVersion") != 1L
			|| !"world-builder-current-runtime-upgrade-receipt".equals(string(receipt, "manifestType"))
			|| !Arrays.asList("pending", "successful", "rolled-back", "recovery-required").contains(status)
			|| bool(receipt, "recoveryRequired") != "recovery-required".equals(status)
			|| bool(receipt, "rollbackComplete") != ("successful".equals(status) || "rolled-back".equals(status)))
			throw recoveryDrift("receipt", new IOException("receipt has unsupported phase semantics"));
		String supplied = string(receipt, "receiptFingerprintSha256");
		receipt.put("receiptFingerprintSha256", ZERO_HASH);
		String expected = canonicalHash(receipt);
		receipt.put("receiptFingerprintSha256", supplied);
		if (!supplied.equals(expected)) throw problem(
			WorldBuilderErrorCodes.RECOVERY_REQUIRED, "receiptFingerprintSha256", true,
			"Recovery receipt fingerprint does not match its content.",
			"Restore the exact sealed recovery receipt.");
	}

	private static PendingReceiptTemporary validatePendingReceiptTemporary(Path transaction,
		Map<String,Object> plan) throws IOException, WorldBuilderContractException {
		Path temporary = transaction.resolve(".receipt.json.tmp");
		if (!Files.exists(temporary, LinkOption.NOFOLLOW_LINKS)) return null;
		BasicFileAttributes attributes = Files.readAttributes(temporary,
			BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
		if (!attributes.isRegularFile() || attributes.isSymbolicLink()) throw problem(
			WorldBuilderErrorCodes.RECOVERY_REQUIRED, "receipt-temporary", true,
			"Interrupted receipt staging evidence is linked or unsafe.",
			"Preserve the transaction directory for reviewed recovery.");
		WorldBuilderAdaptiveExporter.rejectHardLink(temporary, ".receipt.json.tmp");
		Map<String,Object> receipt;
		try {
			receipt = WorldBuilderJsonDocuments.readObject(temporary);
		} catch (WorldBuilderDiscoveryException malformed) {
			throw problem(WorldBuilderErrorCodes.RECOVERY_REQUIRED,
				"receipt-temporary", true,
				"Interrupted receipt staging evidence is malformed.",
				"Preserve the transaction directory for reviewed recovery.", malformed);
		}
		validateReceiptFingerprint(receipt);
		String status = string(receipt, "status");
		if (!("pending".equals(status) || "recovery-required".equals(status)
				|| "successful".equals(status) || "rolled-back".equals(status))
			|| !string(plan, "transactionId").equals(string(receipt, "transactionId"))
			|| !string(plan, "planFingerprintSha256").equals(
				string(receipt, "planFingerprintSha256"))
			|| !string(plan, "preimageInventoryHash").equals(
				string(receipt, "preimageInventoryHash"))
			|| !string(plan, "artifactPlanHash").equals(
				string(receipt, "artifactPlanHash"))
			|| "successful".equals(status) && !string(bindRuntimeExecution(bindGeneratedState(plan,
				generatedStateOutputs(receipt)), runtimeExecutionOutputs(receipt)),
				"verificationEvidenceHash").equals(
					string(receipt, "verificationEvidenceHash"))) throw problem(
				WorldBuilderErrorCodes.RECOVERY_REQUIRED, "receipt-temporary", true,
				"Interrupted receipt staging does not belong to this recoverable plan.",
				"Preserve both receipt files for reviewed recovery; no overwrite is authorized.");
		return new PendingReceiptTemporary(temporary, status, receipt);
	}

	private static void publishReceiptTemporary(Path temporary, Path receipt)
		throws IOException, WorldBuilderContractException {
		try {
			Files.move(temporary, receipt, StandardCopyOption.ATOMIC_MOVE,
				StandardCopyOption.REPLACE_EXISTING);
		} catch (AtomicMoveNotSupportedException unsupported) {
			throw problem(WorldBuilderErrorCodes.RECOVERY_REQUIRED,
				"receipt-temporary", true,
				"Filesystem cannot atomically reconcile the exact final receipt.",
				"Preserve both receipt states for reviewed recovery.", unsupported);
		}
		WorldBuilderAdaptiveDurability.forceFile(receipt);
		WorldBuilderAdaptiveDurability.forceDirectory(receipt.getParent());
	}

	private static final class PendingReceiptTemporary {
		final Path path; final String status;
		final Map<String,Object> document;
		PendingReceiptTemporary(Path path, String status, Map<String,Object> document) {
			this.path = path; this.status = status;
			this.document = document;
		}
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
