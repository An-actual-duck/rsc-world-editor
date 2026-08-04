package com.openrsc.worldbuilder;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Durable, exact restoration of one pending/recovery-required transaction. */
final class WorldBuilderAdaptiveRecovery {
	private static final String OPERATION = "recover-adaptive-transaction";
	private static final String ZERO_HASH =
		"0000000000000000000000000000000000000000000000000000000000000000";

	interface Observer {
		void observe(String milestone, Path path) throws Exception;
	}

	private static final Observer NO_OP_OBSERVER = new Observer() {
		@Override public void observe(String milestone, Path path) {
			// Production recovery has no injected observer.
		}
	};

	private final Observer observer;

	WorldBuilderAdaptiveRecovery() {
		this(NO_OP_OBSERVER);
	}

	WorldBuilderAdaptiveRecovery(Observer observer) {
		this.observer = observer == null ? NO_OP_OBSERVER : observer;
	}

	Preview preview(Path requestedProject, Path requestedTarget)
		throws IOException, WorldBuilderContractException {
		try {
			return operate(requestedProject, requestedTarget, null, null).preview;
		} catch (WorldBuilderContractException failure) {
			throw failure;
		} catch (IOException failure) {
			throw failure;
		} catch (Exception callbackFailure) {
			throw problem(WorldBuilderErrorCodes.MUTATION_FAILED, "preview", false,
				"Adaptive recovery preview was interrupted.",
				"Retry while the target remains offline.", callbackFailure);
		}
	}

	RecoveryResult apply(Preview preview, String confirmation) throws Exception {
		if (preview == null) throw new IllegalArgumentException("preview");
		return operate(preview.requestedProject, preview.requestedTarget,
			confirmation, preview).result;
	}

	private Outcome operate(Path requestedProject, Path requestedTarget,
		String confirmation, Preview expected) throws Exception {
		WorldBuilderAdaptiveProjectLifecycle.VerifiedProject initial =
			WorldBuilderAdaptiveProjectLifecycle.verifyProjectDirectory(
				requestedProject, true);
		if ("standalone-empty".equals(initial.origin)) throw problem(
			WorldBuilderErrorCodes.NO_TARGET, "project.json", false,
			"Standalone project " + initial.projectId
				+ " has no target; recovery stopped before target access.",
			"Standalone projects cannot have target mutation recovery.");
		Path project = initial.projectRoot;
		Path run = WorldBuilderAdaptiveExporter.requireDirectory(
			project, "run", "project run directory");
		try (FileChannel channel = FileChannel.open(run.resolve("world-builder.lock"),
			StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
			FileLock projectLock = tryLock(channel);
			if (projectLock == null) throw problem(
				WorldBuilderErrorCodes.RECOVERY_REQUIRED, "run/world-builder.lock", false,
				"The project is running or another project operation is active.",
				"Close World Builder and wait while the target remains offline.");
			try {
				WorldBuilderAdaptiveProjectLifecycle.VerifiedProject projectState =
					WorldBuilderAdaptiveProjectLifecycle.verifyProjectDirectory(project, true);
				if (!initial.projectId.equals(projectState.projectId)
					|| !WorldBuilderAdaptiveExporter.canonicalHash(initial.snapshot).equals(
						WorldBuilderAdaptiveExporter.canonicalHash(projectState.snapshot))) {
					throw problem(WorldBuilderErrorCodes.SOURCE_CORRUPT,
						"source/snapshot-manifest.json", false,
						"Project source changed before recovery.",
						"Restore the complete project before target recovery.");
				}
				WorldBuilderAdaptiveReceipt.State failed = unresolved(projectState);
				Path target = WorldBuilderAdaptiveMutationProfile.requireTarget(requestedTarget);
				WorldBuilderTargetCapability capability = WorldBuilderTargetCapability.read(
					WorldBuilderReadOnlyTarget.open(target));
				requireCapability(projectState, failed, capability);
				try (WorldBuilderAdaptiveOfflineLease offline =
					WorldBuilderAdaptiveOfflineLease.acquire(target, capability)) {
					validateFailedTransaction(projectState, failed, target);
					String transactionId = expected == null
						? UUID.randomUUID().toString() : expected.plan.transactionId;
					RecoveryPlan plan = buildPlan(projectState, failed, capability,
						target, transactionId, offline.evidence);
					Preview preview = new Preview(plan, requestedProject, requestedTarget);
					if (expected != null && !expected.plan.planFingerprintSha256.equals(
						plan.planFingerprintSha256)) throw problem(
						WorldBuilderErrorCodes.TARGET_DRIFT, "recovery-plan", false,
						"Target, receipt, backups, or recovery plan changed after preview.",
						"Review a fresh recovery preview; there is no force mode.");
					if (confirmation == null) return new Outcome(preview, null);
					if (!"RECOVER".equals(confirmation)) throw problem(
						WorldBuilderErrorCodes.CONTRACT_VALUE_INVALID, "confirmation", false,
						"Adaptive recovery requires exact RECOVER confirmation.",
						"Review the plan and type RECOVER exactly, or leave the target offline.");
					observe("recovery-plan-confirmed", project);
					return new Outcome(preview, applyLocked(plan));
				}
			} finally {
				projectLock.release();
			}
		}
	}

	private static WorldBuilderAdaptiveMutationProfile.Plan validateFailedTransaction(
		WorldBuilderAdaptiveProjectLifecycle.VerifiedProject project,
		WorldBuilderAdaptiveReceipt.State failed, Path target)
		throws IOException, WorldBuilderContractException {
		if ("import".equals(failed.transactionType())) {
			WorldBuilderAdaptiveExporter.VerifiedExport export =
				WorldBuilderAdaptiveUndo.findExport(project, failed.exportFingerprint());
			WorldBuilderAdaptiveMutationProfile.Plan plan =
				WorldBuilderAdaptiveMutationProfile.reconstructInstalled(
					project, export, target, failed.transactionId());
			WorldBuilderAdaptiveReceipt.requireTransactionMatches(plan, failed);
			return plan;
		}
		if ("undo".equals(failed.transactionType())) {
			String importId = failed.revertsTransactionId();
			WorldBuilderAdaptiveReceipt.State authority = receiptById(
				project.projectRoot, importId);
			if (!"import".equals(authority.transactionType())
				|| !"successful".equals(authority.status())) throw problem(
				WorldBuilderErrorCodes.RECOVERY_REQUIRED,
				"receipts/" + importId + ".json", false,
				"Failed undo no longer has its exact successful-import authority.",
				"Restore the complete original receipt before recovery.");
			WorldBuilderAdaptiveExporter.VerifiedExport export =
				WorldBuilderAdaptiveUndo.findExport(project, authority.exportFingerprint());
			WorldBuilderAdaptiveMutationProfile.Plan installed =
				WorldBuilderAdaptiveMutationProfile.reconstructInstalled(
					project, export, target, authority.transactionId());
			WorldBuilderAdaptiveReceipt.requireSuccessfulImportMatches(
				installed, authority);
			WorldBuilderAdaptiveMutationProfile.Plan plan =
				WorldBuilderAdaptiveMutationProfile.reverseForUndo(
					installed, failed.transactionId());
			WorldBuilderAdaptiveMutationProfile.requireDurablePlanMatches(plan);
			WorldBuilderAdaptiveReceipt.requireTransactionMatches(plan, failed);
			return plan;
		}
		throw problem(WorldBuilderErrorCodes.RECOVERY_REQUIRED,
			"receipts/" + failed.transactionId() + ".json", false,
			"Nested recovery transaction requires owner review.",
			"Preserve every backup and receipt; do not derive writable paths from nested recovery data.");
	}

	private static WorldBuilderAdaptiveReceipt.State receiptById(
		Path project, String transactionId)
		throws IOException, WorldBuilderContractException {
		for (WorldBuilderAdaptiveReceipt.State receipt :
			WorldBuilderAdaptiveReceipt.readAll(project)) {
			if (transactionId.equals(receipt.transactionId())) return receipt;
		}
		throw problem(WorldBuilderErrorCodes.RECOVERY_REQUIRED,
			"receipts/" + transactionId + ".json", false,
			"Required transaction receipt is missing.",
			"Restore the complete exact receipt set before recovery.");
	}

	private RecoveryResult applyLocked(RecoveryPlan plan)
		throws IOException, WorldBuilderContractException {
		Path project = plan.project.projectRoot;
		Path backupRoot = WorldBuilderPortablePath.resolveContained(project,
			"backups/" + plan.transactionId, OPERATION);
		String createdAt = WorldBuilderAdaptiveReceipt.now();
		boolean mutation = false;
		try {
			Files.createDirectory(backupRoot);
			writeBytes(backupRoot.resolve("recovery-plan.json"),
				WorldBuilderJsonDocuments.pretty(plan.document)
					.getBytes(StandardCharsets.UTF_8));
			backupCurrent(plan, backupRoot);
			if (!plan.actions.isEmpty()) {
				WorldBuilderAdaptiveReceipt.write(project,
					receipt(plan, "pending", createdAt, false, false, false,
						Collections.<WorldBuilderAdaptiveReceipt.Verification>emptyList()));
			}
			observe("recovery-evidence-written", backupRoot);
			verifyRecoverableStates(plan);
			for (int index = 0; index < plan.actions.size(); index++) {
				RecoveryAction action = plan.actions.get(index);
				verifyState(plan.targetRoot, action.relativePath, action.before);
				Path destination = WorldBuilderAdaptiveMutationProfile.safeDestination(
					plan.targetRoot, action.relativePath);
				if (action.after.present) {
					ensureRealParents(plan.targetRoot, destination.getParent());
					Path source = WorldBuilderPortablePath.resolveContained(
						project, action.restoreSourceRelativePath, OPERATION);
					verifyFile(source, action.after, action.relativePath);
					Path temporary = destination.getParent().resolve("."
						+ destination.getFileName() + ".recover-" + plan.transactionId);
					Files.copy(source, temporary);
					forceFile(temporary);
					verifyFile(temporary, action.after, action.relativePath);
					moveAtomicReplacing(temporary, destination, action.relativePath);
				} else {
					Files.delete(destination);
				}
				mutation = true;
				observe("recovery-action-applied-" + pad(index), destination);
			}
			if ("import".equals(plan.failed.transactionType())) {
				mutation |= removeImportDirectories(plan);
			}
			List<WorldBuilderAdaptiveReceipt.Verification> verified =
				verifyRecoveredBeforeState(plan);
			if (!plan.actions.isEmpty()) {
				WorldBuilderAdaptiveReceipt.write(project, receipt(plan, "successful",
					createdAt, true, true, false, verified));
			}
			WorldBuilderAdaptiveReceipt.write(project,
				WorldBuilderAdaptiveReceipt.markRolledBack(plan.failed));
			return new RecoveryResult(plan.transactionId, "successful",
				plan.failed.transactionId(), plan.actions.isEmpty() ? null
					: project.resolve("receipts").resolve(plan.transactionId + ".json"));
		} catch (Throwable failure) {
			if (!mutation) {
				writeReceiptIfPossible(plan, "failed-no-change", createdAt,
					false, false, false, failure);
				throw asFailure(failure, false,
					"Adaptive recovery stopped before changing target content.",
					"Correct the evidence problem while keeping the target offline.");
			}
			try {
				List<WorldBuilderAdaptiveReceipt.Verification> rollback =
					rollbackRecovery(plan);
				if (!plan.actions.isEmpty()) WorldBuilderAdaptiveReceipt.write(project,
					receipt(plan, "rolled-back", createdAt, true, false, true, rollback));
			} catch (WorldBuilderContractException rollbackFailure) {
				writeReceiptIfPossible(plan, "recovery-required", createdAt,
					true, false, false, rollbackFailure);
				throw problem(WorldBuilderErrorCodes.RECOVERY_REQUIRED,
					"receipts/" + plan.transactionId + ".json", true,
					"Recovery attempt also failed to restore its exact starting state.",
					"Keep the target offline and preserve every receipt/backup for owner review.",
					rollbackFailure);
			} catch (IOException rollbackFailure) {
				writeReceiptIfPossible(plan, "recovery-required", createdAt,
					true, false, false, rollbackFailure);
				throw problem(WorldBuilderErrorCodes.RECOVERY_REQUIRED,
					"receipts/" + plan.transactionId + ".json", true,
					"Recovery attempt also failed to restore its exact starting state.",
					"Keep the target offline and preserve every receipt/backup for owner review.",
					rollbackFailure);
			}
			throw asFailure(failure, true,
				"Recovery attempt failed; its exact starting state was restored.",
				"Review the original recovery-required receipt and try again offline.");
		}
	}

	private static WorldBuilderAdaptiveReceipt.State unresolved(
		WorldBuilderAdaptiveProjectLifecycle.VerifiedProject project)
		throws IOException, WorldBuilderContractException {
		List<WorldBuilderAdaptiveReceipt.State> unresolved =
			new ArrayList<WorldBuilderAdaptiveReceipt.State>();
		for (WorldBuilderAdaptiveReceipt.State receipt :
			WorldBuilderAdaptiveReceipt.readAll(project.projectRoot)) {
			if (!project.projectId.equals(string(receipt.document, "projectId"))) {
				throw problem(WorldBuilderErrorCodes.RECOVERY_REQUIRED, "receipts", false,
					"Receipt belongs to another project UUID.",
					"Restore only this project's exact receipt set.");
			}
			if ("pending".equals(receipt.status())
				|| "recovery-required".equals(receipt.status())) unresolved.add(receipt);
		}
		if (unresolved.size() != 1) throw problem(
			WorldBuilderErrorCodes.RECOVERY_REQUIRED, "receipts", false,
			unresolved.isEmpty()
				? "No pending or recovery-required adaptive transaction exists."
				: "More than one unresolved adaptive transaction requires owner review.",
			unresolved.isEmpty()
				? "Use Import or Undo normally; recovery is not needed."
				: "Preserve every receipt/backup and resolve transaction lineage without force.");
		return unresolved.get(0);
	}

	private static void requireCapability(
		WorldBuilderAdaptiveProjectLifecycle.VerifiedProject project,
		WorldBuilderAdaptiveReceipt.State failed,
		WorldBuilderTargetCapability capability)
		throws WorldBuilderContractException {
		Map<String,Object> target = object(project.manifest.get("target"), "target");
		if (!string(target, "adapterId").equals(string(failed.document, "adapterId"))
			|| !string(target, "capabilityId").equals(
				string(failed.document, "capabilityId"))
			|| !capability.adapterId.equals(string(failed.document, "adapterId"))
			|| !capability.capabilityId.equals(string(failed.document, "capabilityId"))
			|| !capability.installEnabled) throw problem(
			WorldBuilderErrorCodes.CAPABILITY_MISMATCH,
			WorldBuilderTargetCapability.RELATIVE_PATH, false,
			"Recovery target capability differs from the failed transaction.",
			"Restore the exact compatible capability while keeping the target offline.");
	}

	private static RecoveryPlan buildPlan(
		WorldBuilderAdaptiveProjectLifecycle.VerifiedProject project,
		WorldBuilderAdaptiveReceipt.State failed,
		WorldBuilderTargetCapability capability, Path target, String transactionId,
		List<WorldBuilderAdaptiveOfflineLease.Evidence> evidence)
		throws IOException, WorldBuilderContractException {
		List<RecoveryAction> actions = new ArrayList<RecoveryAction>();
		Set<String> configPaths = new HashSet<String>();
		for (Object raw : array(failed.document.get("configurationChanges"),
			"configurationChanges")) {
			configPaths.add(string(object(raw, "configurationChange"),
				"configurationRelativePath"));
		}
		for (Object raw : array(failed.document.get("files"), "files")) {
			Map<String,Object> file = object(raw, "file");
			String relative = string(file, "relativePath");
			WorldBuilderAdaptiveMutationProfile.FileState desired =
				fileState(file.get("before"), relative);
			WorldBuilderAdaptiveMutationProfile.FileState proposed =
				fileState(file.get("after"), relative);
			WorldBuilderAdaptiveMutationProfile.FileState current = currentState(
				target, relative);
			if (same(current, desired)) continue;
			if (!same(current, proposed)) throw problem(
				WorldBuilderErrorCodes.TARGET_DRIFT, relative, false,
				"Recovery path matches neither transaction before nor after state.",
				"Preserve the changed path and request owner review; no force mode exists.");
			String restore = desired.present
				? string(file, "backupRelativePath") : "";
			if (desired.present) {
				Path backup = WorldBuilderPortablePath.resolveContained(
					project.projectRoot, restore, OPERATION);
				verifyFile(backup, desired, relative);
			}
			String transactionBackup = current.present
				? "backups/" + transactionId + "/before/" + relative : "";
			actions.add(new RecoveryAction(string(file, "role"), relative,
				current, desired, restore, transactionBackup,
				configPaths.contains(relative)));
		}
		Collections.sort(actions, actionOrder(failed.transactionType()));
		List<ConfigurationChange> changes = recoveryConfigurationChanges(
			failed, actions);
		List<String> createdDirectories = "import".equals(failed.transactionType())
			? createdDirectories(project.projectRoot, failed.transactionId())
			: Collections.<String>emptyList();
		Map<String,Object> document = planDocument(project, failed, capability,
			target, transactionId, actions, changes, evidence, createdDirectories);
		return new RecoveryPlan(project, failed, capability, target, transactionId,
			actions, changes, evidence, createdDirectories, document,
			string(document, "planFingerprintSha256"));
	}

	private static Comparator<RecoveryAction> actionOrder(final String type) {
		return new Comparator<RecoveryAction>() {
			@Override public int compare(RecoveryAction left, RecoveryAction right) {
				if (left.configuration != right.configuration) {
					boolean configFirst = "import".equals(type);
					return left.configuration == configFirst ? -1 : 1;
				}
				return left.relativePath.compareTo(right.relativePath);
			}
		};
	}

	private static List<ConfigurationChange> recoveryConfigurationChanges(
		WorldBuilderAdaptiveReceipt.State failed, List<RecoveryAction> actions)
		throws WorldBuilderContractException {
		Set<String> actionPaths = new HashSet<String>();
		for (RecoveryAction action : actions) actionPaths.add(action.relativePath);
		List<ConfigurationChange> values = new ArrayList<ConfigurationChange>();
		for (Object raw : array(failed.document.get("configurationChanges"),
			"configurationChanges")) {
			Map<String,Object> value = object(raw, "configurationChange");
			String path = string(value, "configurationRelativePath");
			if (!actionPaths.contains(path)) continue;
			values.add(new ConfigurationChange(path, string(value, "key"),
				bool(value, "afterPresent"), string(value, "afterValue"),
				bool(value, "beforePresent"), string(value, "beforeValue")));
		}
		Collections.sort(values);
		return values;
	}

	private static Map<String,Object> planDocument(
		WorldBuilderAdaptiveProjectLifecycle.VerifiedProject project,
		WorldBuilderAdaptiveReceipt.State failed,
		WorldBuilderTargetCapability capability, Path target, String transactionId,
		List<RecoveryAction> actions, List<ConfigurationChange> changes,
		List<WorldBuilderAdaptiveOfflineLease.Evidence> evidence,
		List<String> createdDirectories) throws WorldBuilderContractException {
		Map<String,Object> value = new LinkedHashMap<String,Object>();
		value.put("schemaVersion", Long.valueOf(1L));
		value.put("manifestType", "world-builder-adaptive-recovery-plan");
		value.put("transactionId", transactionId);
		value.put("recoversTransactionId", failed.transactionId());
		value.put("projectId", project.projectId);
		value.put("adapterId", capability.adapterId);
		value.put("capabilityId", capability.capabilityId);
		value.put("targetLineageSha256",
			string(failed.document, "targetLineageSha256"));
		List<Object> actionValues = new ArrayList<Object>();
		for (int index = 0; index < actions.size(); index++) {
			actionValues.add(actions.get(index).toJson(index));
		}
		value.put("actions", actionValues);
		List<Object> changeValues = new ArrayList<Object>();
		for (int index = 0; index < changes.size(); index++) {
			changeValues.add(changes.get(index).toJson(index, false, false));
		}
		value.put("configurationChanges", changeValues);
		List<Object> evidenceValues = new ArrayList<Object>();
		for (WorldBuilderAdaptiveOfflineLease.Evidence item : evidence) {
			Map<String,Object> record = new LinkedHashMap<String,Object>();
			record.put("kind", item.kind);
			record.put("observed", item.observed);
			record.put("verified", Boolean.valueOf(item.verified));
			evidenceValues.add(record);
		}
		value.put("offlineEvidence", evidenceValues);
		value.put("createdDirectories", new ArrayList<String>(createdDirectories));
		value.put("backupRootRelativePath", "backups/" + transactionId);
		value.put("receiptRelativePath", "receipts/" + transactionId + ".json");
		value.put("confirmation", "RECOVER");
		value.put("planFingerprintSha256", ZERO_HASH);
		WorldBuilderAdaptiveExporter.bindFingerprint(value, "planFingerprintSha256");
		return value;
	}

	private static WorldBuilderAdaptiveReceipt.State receipt(RecoveryPlan plan,
		String status, String createdAt, boolean mutation, boolean afterVerified,
		boolean rollbackVerified,
		List<WorldBuilderAdaptiveReceipt.Verification> verifications)
		throws WorldBuilderContractException {
		Map<String,Object> value = new LinkedHashMap<String,Object>();
		value.put("schemaVersion", Long.valueOf(3L));
		value.put("manifestType", "world-builder-adaptive-import-receipt");
		value.put("transactionId", plan.transactionId);
		value.put("transactionType", "recovery");
		value.put("status", status);
		value.put("createdAtUtc", createdAt);
		value.put("projectId", plan.project.projectId);
		value.put("exportFingerprintSha256",
			string(plan.failed.document, "exportFingerprintSha256"));
		value.put("mutationPlanSha256", plan.planFingerprintSha256);
		value.put("adapterId", plan.capability.adapterId);
		value.put("capabilityId", plan.capability.capabilityId);
		value.put("targetLineageSha256",
			string(plan.failed.document, "targetLineageSha256"));
		value.put("selectedConfiguration", copy(object(
			plan.failed.document.get("selectedConfiguration"),
			"selectedConfiguration")));
		value.put("mutationOccurred", Boolean.valueOf(mutation));
		List<Object> evidenceValues = new ArrayList<Object>();
		for (WorldBuilderAdaptiveOfflineLease.Evidence item : plan.offlineEvidence) {
			Map<String,Object> record = new LinkedHashMap<String,Object>();
			record.put("kind", item.kind);
			record.put("observed", item.observed);
			record.put("verified", Boolean.valueOf(item.verified));
			evidenceValues.add(record);
		}
		value.put("offlineEvidence", evidenceValues);
		List<RecoveryAction> sorted = new ArrayList<RecoveryAction>(plan.actions);
		Collections.sort(sorted);
		List<Object> files = new ArrayList<Object>();
		for (RecoveryAction action : sorted) {
			Map<String,Object> file = new LinkedHashMap<String,Object>();
			file.put("role", action.role);
			file.put("relativePath", action.relativePath);
			file.put("before", action.before.toJson());
			file.put("after", action.after.toJson());
			file.put("backupRelativePath", action.transactionBackupRelativePath);
			file.put("backupSha256", action.before.present ? action.before.sha256 : "");
			file.put("afterVerified", Boolean.valueOf(afterVerified));
			file.put("rollbackVerified", Boolean.valueOf(rollbackVerified));
			files.add(file);
		}
		value.put("files", files);
		List<Object> changeValues = new ArrayList<Object>();
		for (int index = 0; index < plan.configurationChanges.size(); index++) {
			changeValues.add(plan.configurationChanges.get(index).toJson(
				index, afterVerified, rollbackVerified));
		}
		value.put("configurationChanges", changeValues);
		List<WorldBuilderAdaptiveReceipt.Verification> verificationValues =
			new ArrayList<WorldBuilderAdaptiveReceipt.Verification>(verifications);
		Collections.sort(verificationValues);
		List<Object> serialized = new ArrayList<Object>();
		for (WorldBuilderAdaptiveReceipt.Verification verification : verificationValues) {
			serialized.add(verification.toJson());
		}
		value.put("verificationResults", serialized);
		value.put("revertsTransactionId", "");
		value.put("recoveryTransactionId", plan.failed.transactionId());
		value.put("receiptFingerprintSha256", ZERO_HASH);
		WorldBuilderAdaptiveExporter.bindFingerprint(value, "receiptFingerprintSha256");
		WorldBuilderAdaptiveContracts.validateParsed(
			WorldBuilderAdaptiveContracts.Kind.ADAPTIVE_RECEIPT, value);
		return new WorldBuilderAdaptiveReceipt.State(value);
	}

	private static void backupCurrent(RecoveryPlan plan, Path backupRoot)
		throws IOException, WorldBuilderContractException {
		for (RecoveryAction action : plan.actions) {
			if (!action.before.present) continue;
			Path source = WorldBuilderAdaptiveMutationProfile.safeExistingFile(
				plan.targetRoot, action.relativePath, "recovery current file");
			String inside = action.transactionBackupRelativePath.substring(
				("backups/" + plan.transactionId + "/").length());
			Path destination = WorldBuilderPortablePath.resolveContained(
				backupRoot, inside, OPERATION);
			Files.createDirectories(destination.getParent());
			Files.copy(source, destination, StandardCopyOption.COPY_ATTRIBUTES);
			verifyFile(destination, action.before, action.relativePath);
		}
	}

	private static void verifyRecoverableStates(RecoveryPlan plan)
		throws IOException, WorldBuilderContractException {
		for (RecoveryAction action : plan.actions) {
			verifyState(plan.targetRoot, action.relativePath, action.before);
			if (action.after.present) verifyFile(
				WorldBuilderPortablePath.resolveContained(plan.project.projectRoot,
					action.restoreSourceRelativePath, OPERATION),
				action.after, action.relativePath);
		}
	}

	private static List<WorldBuilderAdaptiveReceipt.Verification>
		verifyRecoveredBeforeState(RecoveryPlan plan)
		throws IOException, WorldBuilderContractException {
		List<WorldBuilderAdaptiveReceipt.Verification> values =
			new ArrayList<WorldBuilderAdaptiveReceipt.Verification>();
		for (int index = 0; index < plan.actions.size(); index++) {
			RecoveryAction action = plan.actions.get(index);
			verifyState(plan.targetRoot, action.relativePath, action.after);
			values.add(new WorldBuilderAdaptiveReceipt.Verification(
				"recovery-" + pad(index), true,
				action.after.present ? action.after.sha256 : "absent"));
		}
		if ("import".equals(plan.failed.transactionType())) {
			Map<String,Object> selected = object(
				plan.failed.document.get("selectedConfiguration"),
				"selectedConfiguration");
			WorldBuilderAdaptiveDiscoveryReport discovery =
				new WorldBuilderAdaptiveDiscovery().discover(
					plan.targetRoot, string(selected, "role"));
			if (!"compatible".equals(discovery.status)
				|| !string(plan.failed.document, "targetLineageSha256").equals(
					discovery.fingerprintSha256())) throw problem(
				WorldBuilderErrorCodes.TARGET_DRIFT, "target-root", true,
				"Recovered bytes do not match the exact pre-import target lineage.",
				"Keep the target offline so recovery can roll back its attempt.");
		}
		return values;
	}

	private static List<WorldBuilderAdaptiveReceipt.Verification> rollbackRecovery(
		RecoveryPlan plan) throws IOException, WorldBuilderContractException {
		List<RecoveryAction> reverse = new ArrayList<RecoveryAction>(plan.actions);
		Collections.reverse(reverse);
		for (RecoveryAction action : reverse) {
			Path destination = WorldBuilderAdaptiveMutationProfile.safeDestination(
				plan.targetRoot, action.relativePath);
			if (action.before.present) {
				ensureRealParents(plan.targetRoot, destination.getParent());
				Path source = WorldBuilderPortablePath.resolveContained(
					plan.project.projectRoot,
					action.transactionBackupRelativePath, OPERATION);
				verifyFile(source, action.before, action.relativePath);
				Path temporary = destination.getParent().resolve("."
					+ destination.getFileName() + ".recovery-rollback-"
					+ plan.transactionId);
				Files.copy(source, temporary);
				forceFile(temporary);
				moveAtomicReplacing(temporary, destination, action.relativePath);
			} else if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
				verifyState(plan.targetRoot, action.relativePath, action.after);
				Files.delete(destination);
			}
		}
		List<WorldBuilderAdaptiveReceipt.Verification> values =
			new ArrayList<WorldBuilderAdaptiveReceipt.Verification>();
		for (int index = 0; index < plan.actions.size(); index++) {
			RecoveryAction action = plan.actions.get(index);
			verifyState(plan.targetRoot, action.relativePath, action.before);
			values.add(new WorldBuilderAdaptiveReceipt.Verification(
				"recovery-rollback-" + pad(index), true,
				action.before.present ? action.before.sha256 : "absent"));
		}
		return values;
	}

	private static boolean removeImportDirectories(RecoveryPlan plan)
		throws IOException, WorldBuilderContractException {
		boolean changed = false;
		List<String> reverse = new ArrayList<String>(plan.createdDirectories);
		Collections.sort(reverse, deepestFirst());
		for (String relative : reverse) {
			Path path = WorldBuilderPortablePath.resolveContained(
				plan.targetRoot, relative, OPERATION);
			if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) continue;
			if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
				|| Files.isSymbolicLink(path)) throw problem(
				WorldBuilderErrorCodes.TARGET_DRIFT, relative, true,
				"Import-created directory is no longer safely removable.",
				"Keep the target offline and preserve changed content for owner review.");
			Files.delete(path);
			changed = true;
		}
		return changed;
	}

	private static List<String> createdDirectories(Path project, String transactionId)
		throws IOException, WorldBuilderContractException {
		Path path = WorldBuilderPortablePath.resolveContained(project,
			"backups/" + transactionId + "/created-directories.json", OPERATION);
		Map<String,Object> value = readObject(path, "created-directories.json");
		Set<String> exact = new HashSet<String>(Arrays.asList("schemaVersion",
			"manifestType", "transactionId", "planFingerprintSha256", "relativePaths"));
		if (!value.keySet().equals(exact) || integer(value, "schemaVersion") != 1L
			|| !"world-builder-created-directories".equals(
				string(value, "manifestType"))
			|| !transactionId.equals(string(value, "transactionId"))) throw problem(
			WorldBuilderErrorCodes.RECOVERY_REQUIRED, "created-directories.json", false,
			"Import created-directory evidence is invalid.",
			"Restore the exact transaction backup before recovery.");
		List<String> result = new ArrayList<String>();
		String previous = null;
		for (Object raw : array(value.get("relativePaths"), "relativePaths")) {
			if (!(raw instanceof String)) throw problem(
				WorldBuilderErrorCodes.RECOVERY_REQUIRED,
				"created-directories.json", false,
				"Created-directory evidence contains a non-string path.",
				"Restore the exact transaction backup.");
			String relative = WorldBuilderPortablePath.require((String)raw, OPERATION);
			if (previous != null && shallowFirst().compare(previous, relative) >= 0) {
				throw problem(WorldBuilderErrorCodes.RECOVERY_REQUIRED,
					"created-directories.json", false,
					"Created-directory evidence is duplicated or not canonical.",
					"Restore the exact transaction backup.");
			}
			previous = relative;
			result.add(relative);
		}
		return result;
	}

	private static Comparator<String> shallowFirst() {
		return new Comparator<String>() {
			@Override public int compare(String left, String right) {
				int depth = left.split("/").length - right.split("/").length;
				return depth == 0 ? left.compareTo(right) : depth;
			}
		};
	}

	private static Comparator<String> deepestFirst() {
		return new Comparator<String>() {
			@Override public int compare(String left, String right) {
				int depth = right.split("/").length - left.split("/").length;
				return depth == 0 ? right.compareTo(left) : depth;
			}
		};
	}

	private static void ensureRealParents(Path target, Path parent)
		throws IOException, WorldBuilderContractException {
		Path relative = target.relativize(parent.toAbsolutePath().normalize());
		Path current = target;
		for (Path segment : relative) {
			current = current.resolve(segment.toString());
			if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
				Files.createDirectory(current);
			} else if (!Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)
				|| Files.isSymbolicLink(current)) throw problem(
				WorldBuilderErrorCodes.UNSAFE_PATH,
				target.relativize(current).toString().replace('\\', '/'), true,
				"Recovery parent is linked or not a directory.",
				"Keep the target offline and restore a real contained directory layout.");
		}
	}

	private static WorldBuilderAdaptiveMutationProfile.FileState currentState(
		Path target, String relative) throws IOException, WorldBuilderContractException {
		Path path = WorldBuilderAdaptiveMutationProfile.safeDestination(target, relative);
		if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
			return WorldBuilderAdaptiveMutationProfile.FileState.absent();
		}
		Path file = WorldBuilderAdaptiveMutationProfile.safeExistingFile(
			target, relative, "recovery target file");
		return WorldBuilderAdaptiveMutationProfile.FileState.present(
			Files.size(file), WorldBuilderHashes.sha256(file));
	}

	private static void verifyState(Path target, String relative,
		WorldBuilderAdaptiveMutationProfile.FileState expected)
		throws IOException, WorldBuilderContractException {
		WorldBuilderAdaptiveMutationProfile.FileState actual = currentState(target, relative);
		if (!same(actual, expected)) throw problem(
			WorldBuilderErrorCodes.TARGET_DRIFT, relative, false,
			"Recovery path changed after preview.",
			"Preserve the path and request a fresh recovery preview; no force mode exists.");
	}

	private static boolean same(WorldBuilderAdaptiveMutationProfile.FileState first,
		WorldBuilderAdaptiveMutationProfile.FileState second) {
		return first.present == second.present && first.size == second.size
			&& first.sha256.equals(second.sha256);
	}

	private static WorldBuilderAdaptiveMutationProfile.FileState fileState(
		Object raw, String relative) throws WorldBuilderContractException {
		Map<String,Object> value = object(raw, relative);
		return new WorldBuilderAdaptiveMutationProfile.FileState(
			bool(value, "present"), integer(value, "size"), string(value, "sha256"));
	}

	private static void verifyFile(Path path,
		WorldBuilderAdaptiveMutationProfile.FileState expected, String relative)
		throws IOException, WorldBuilderContractException {
		if (!expected.present || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
			|| Files.isSymbolicLink(path) || Files.size(path) != expected.size
			|| !expected.sha256.equals(WorldBuilderHashes.sha256(path))) throw problem(
			WorldBuilderErrorCodes.TARGET_DRIFT, relative, false,
			"Recovery backup/file bytes do not match their receipt state.",
			"Restore exact transaction evidence; no force mode exists.");
	}

	private static void moveAtomicReplacing(Path source, Path destination,
		String relative) throws IOException, WorldBuilderContractException {
		try {
			Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE,
				StandardCopyOption.REPLACE_EXISTING);
		} catch (AtomicMoveNotSupportedException unsupported) {
			throw problem(WorldBuilderErrorCodes.MUTATION_FAILED, relative, false,
				"Target filesystem cannot atomically recover a file.",
				"Use a local filesystem with atomic same-directory moves.", unsupported);
		}
	}

	private static void writeBytes(Path path, byte[] bytes) throws IOException {
		Files.write(path, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
		forceFile(path);
	}

	private static void forceFile(Path path) throws IOException {
		try (FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE)) {
			channel.force(true);
		}
	}

	private static void writeReceiptIfPossible(RecoveryPlan plan, String status,
		String createdAt, boolean mutation, boolean afterVerified,
		boolean rollbackVerified, Throwable failure) {
		if (plan.actions.isEmpty()) return;
		try {
			List<WorldBuilderAdaptiveReceipt.Verification> values =
				new ArrayList<WorldBuilderAdaptiveReceipt.Verification>();
			if ("recovery-required".equals(status)) values.add(
				new WorldBuilderAdaptiveReceipt.Verification(
					"recovery-required", false, bounded(failure.getMessage())));
			WorldBuilderAdaptiveReceipt.write(plan.project.projectRoot,
				receipt(plan, status, createdAt, mutation,
					afterVerified, rollbackVerified, values));
		} catch (Exception ignored) {
			// Primary recovery failure remains authoritative.
		}
	}

	private static WorldBuilderContractException asFailure(Throwable failure,
		boolean mutation, String message, String nextStep) {
		if (failure instanceof WorldBuilderContractException
			&& ((WorldBuilderContractException)failure).mutationOccurred() == mutation) {
			return (WorldBuilderContractException)failure;
		}
		return problem(WorldBuilderErrorCodes.MUTATION_FAILED, "target", mutation,
			message + " Cause: " + bounded(failure.getMessage()), nextStep, failure);
	}

	private static String bounded(String value) {
		if (value == null || value.trim().isEmpty()) return "unspecified recovery failure";
		return value.length() <= 2048 ? value : value.substring(0, 2048);
	}

	private static Map<String,Object> readObject(Path path, String relative)
		throws IOException, WorldBuilderContractException {
		try {
			return WorldBuilderJsonDocuments.readObject(path);
		} catch (WorldBuilderDiscoveryException malformed) {
			throw problem(WorldBuilderErrorCodes.RECOVERY_REQUIRED, relative, false,
				"Recovery evidence JSON is malformed: " + malformed.getMessage(),
				"Restore exact transaction evidence before recovery.", malformed);
		}
	}

	@SuppressWarnings("unchecked")
	private static Map<String,Object> object(Object value, String label)
		throws WorldBuilderContractException {
		if (!(value instanceof Map)) throw problem(
			WorldBuilderErrorCodes.RECOVERY_REQUIRED, label, false,
			"Expected a recovery object.", "Restore exact transaction evidence.");
		return (Map<String,Object>)value;
	}

	private static List<?> array(Object value, String label)
		throws WorldBuilderContractException {
		if (!(value instanceof List)) throw problem(
			WorldBuilderErrorCodes.RECOVERY_REQUIRED, label, false,
			"Expected a recovery array.", "Restore exact transaction evidence.");
		return (List<?>)value;
	}

	private static String string(Map<String,Object> value, String key)
		throws WorldBuilderContractException {
		Object raw = value.get(key);
		if (!(raw instanceof String)) throw problem(
			WorldBuilderErrorCodes.RECOVERY_REQUIRED, key, false,
			"Expected a recovery string.", "Restore exact transaction evidence.");
		return (String)raw;
	}

	private static long integer(Map<String,Object> value, String key)
		throws WorldBuilderContractException {
		Object raw = value.get(key);
		if (!(raw instanceof Long)) throw problem(
			WorldBuilderErrorCodes.RECOVERY_REQUIRED, key, false,
			"Expected a recovery integer.", "Restore exact transaction evidence.");
		return ((Long)raw).longValue();
	}

	private static boolean bool(Map<String,Object> value, String key)
		throws WorldBuilderContractException {
		Object raw = value.get(key);
		if (!(raw instanceof Boolean)) throw problem(
			WorldBuilderErrorCodes.RECOVERY_REQUIRED, key, false,
			"Expected a recovery boolean.", "Restore exact transaction evidence.");
		return ((Boolean)raw).booleanValue();
	}

	@SuppressWarnings("unchecked")
	private static Object copy(Object value) {
		if (value instanceof Map) {
			Map<String,Object> result = new LinkedHashMap<String,Object>();
			for (Map.Entry<String,Object> entry : ((Map<String,Object>)value).entrySet()) {
				result.put(entry.getKey(), copy(entry.getValue()));
			}
			return result;
		}
		if (value instanceof List) {
			List<Object> result = new ArrayList<Object>();
			for (Object child : (List<Object>)value) result.add(copy(child));
			return result;
		}
		return value;
	}

	private void observe(String milestone, Path path) throws Exception {
		observer.observe(milestone, path);
	}

	private static FileLock tryLock(FileChannel channel) throws IOException {
		try {
			return channel.tryLock();
		} catch (OverlappingFileLockException busy) {
			return null;
		}
	}

	private static String pad(int value) {
		return String.format(java.util.Locale.ROOT, "%04d", Integer.valueOf(value));
	}

	private static WorldBuilderContractException problem(String code, String path,
		boolean mutation, String message, String nextStep) {
		return new WorldBuilderContractException(
			code, OPERATION, path, mutation, message, nextStep);
	}

	private static WorldBuilderContractException problem(String code, String path,
		boolean mutation, String message, String nextStep, Throwable cause) {
		return new WorldBuilderContractException(
			code, OPERATION, path, mutation, message, nextStep, cause);
	}

	static final class Preview {
		final RecoveryPlan plan;
		final Path requestedProject;
		final Path requestedTarget;

		Preview(RecoveryPlan plan, Path requestedProject, Path requestedTarget) {
			this.plan = plan;
			this.requestedProject = requestedProject;
			this.requestedTarget = requestedTarget;
		}

		String humanSummary() {
			return "Recovery preview (no target files changed)\n"
				+ "Transaction: " + plan.transactionId + "\n"
				+ "Recovers: " + plan.document.get("recoversTransactionId") + "\n"
				+ "Project: " + plan.project.projectId + "\n"
				+ "Files to restore: " + plan.actions.size() + "\n"
				+ "Created directories to remove if empty: "
				+ plan.createdDirectories.size() + "\n"
				+ "Confirmation required: RECOVER\n";
		}

		String toJson() {
			return WorldBuilderJsonDocuments.pretty(plan.document);
		}
	}

	private static final class Outcome {
		final Preview preview;
		final RecoveryResult result;

		Outcome(Preview preview, RecoveryResult result) {
			this.preview = preview;
			this.result = result;
		}
	}

	static final class RecoveryResult {
		final String transactionId;
		final String status;
		final String recoveredTransactionId;
		final Path receiptPath;

		RecoveryResult(String transactionId, String status,
			String recoveredTransactionId, Path receiptPath) {
			this.transactionId = transactionId;
			this.status = status;
			this.recoveredTransactionId = recoveredTransactionId;
			this.receiptPath = receiptPath;
		}

		String toJson() {
			Map<String,Object> value = new LinkedHashMap<String,Object>();
			value.put("status", status);
			value.put("transactionId", transactionId);
			value.put("recoveredTransactionId", recoveredTransactionId);
			value.put("receiptPath", receiptPath == null ? "" : receiptPath.toString());
			return WorldBuilderJsonDocuments.pretty(value);
		}
	}

	private static final class RecoveryPlan {
		final WorldBuilderAdaptiveProjectLifecycle.VerifiedProject project;
		final WorldBuilderAdaptiveReceipt.State failed;
		final WorldBuilderTargetCapability capability;
		final Path targetRoot;
		final String transactionId;
		final List<RecoveryAction> actions;
		final List<ConfigurationChange> configurationChanges;
		final List<WorldBuilderAdaptiveOfflineLease.Evidence> offlineEvidence;
		final List<String> createdDirectories;
		final Map<String,Object> document;
		final String planFingerprintSha256;

		RecoveryPlan(WorldBuilderAdaptiveProjectLifecycle.VerifiedProject project,
			WorldBuilderAdaptiveReceipt.State failed,
			WorldBuilderTargetCapability capability, Path targetRoot,
			String transactionId, List<RecoveryAction> actions,
			List<ConfigurationChange> configurationChanges,
			List<WorldBuilderAdaptiveOfflineLease.Evidence> offlineEvidence,
			List<String> createdDirectories, Map<String,Object> document,
			String planFingerprintSha256) {
			this.project = project;
			this.failed = failed;
			this.capability = capability;
			this.targetRoot = targetRoot;
			this.transactionId = transactionId;
			this.actions = Collections.unmodifiableList(
				new ArrayList<RecoveryAction>(actions));
			this.configurationChanges = Collections.unmodifiableList(
				new ArrayList<ConfigurationChange>(configurationChanges));
			this.offlineEvidence = Collections.unmodifiableList(
				new ArrayList<WorldBuilderAdaptiveOfflineLease.Evidence>(offlineEvidence));
			this.createdDirectories = Collections.unmodifiableList(
				new ArrayList<String>(createdDirectories));
			this.document = document;
			this.planFingerprintSha256 = planFingerprintSha256;
		}
	}

	private static final class RecoveryAction implements Comparable<RecoveryAction> {
		final String role;
		final String relativePath;
		final WorldBuilderAdaptiveMutationProfile.FileState before;
		final WorldBuilderAdaptiveMutationProfile.FileState after;
		final String restoreSourceRelativePath;
		final String transactionBackupRelativePath;
		final boolean configuration;

		RecoveryAction(String role, String relativePath,
			WorldBuilderAdaptiveMutationProfile.FileState before,
			WorldBuilderAdaptiveMutationProfile.FileState after,
			String restoreSourceRelativePath,
			String transactionBackupRelativePath, boolean configuration) {
			this.role = role;
			this.relativePath = relativePath;
			this.before = before;
			this.after = after;
			this.restoreSourceRelativePath = restoreSourceRelativePath;
			this.transactionBackupRelativePath = transactionBackupRelativePath;
			this.configuration = configuration;
		}

		Map<String,Object> toJson(int sequence) {
			Map<String,Object> value = new LinkedHashMap<String,Object>();
			value.put("sequence", Long.valueOf(sequence));
			value.put("role", role);
			value.put("destinationRelativePath", relativePath);
			value.put("before", before.toJson());
			value.put("after", after.toJson());
			value.put("restoreSourceRelativePath", restoreSourceRelativePath);
			value.put("backupRelativePath", transactionBackupRelativePath);
			value.put("configuration", Boolean.valueOf(configuration));
			return value;
		}

		@Override public int compareTo(RecoveryAction other) {
			int result = relativePath.compareTo(other.relativePath);
			return result == 0 ? role.compareTo(other.role) : result;
		}
	}

	private static final class ConfigurationChange
		implements Comparable<ConfigurationChange> {
		final String path;
		final String key;
		final boolean beforePresent;
		final String beforeValue;
		final boolean afterPresent;
		final String afterValue;

		ConfigurationChange(String path, String key, boolean beforePresent,
			String beforeValue, boolean afterPresent, String afterValue) {
			this.path = path;
			this.key = key;
			this.beforePresent = beforePresent;
			this.beforeValue = beforeValue;
			this.afterPresent = afterPresent;
			this.afterValue = afterValue;
		}

		Map<String,Object> toJson(int sequence, boolean afterVerified,
			boolean rollbackVerified) {
			Map<String,Object> value = new LinkedHashMap<String,Object>();
			value.put("sequence", Long.valueOf(sequence));
			value.put("configurationRelativePath", path);
			value.put("key", key);
			value.put("beforePresent", Boolean.valueOf(beforePresent));
			value.put("beforeValue", beforeValue);
			value.put("afterPresent", Boolean.valueOf(afterPresent));
			value.put("afterValue", afterValue);
			value.put("afterVerified", Boolean.valueOf(afterVerified));
			value.put("rollbackVerified", Boolean.valueOf(rollbackVerified));
			return value;
		}

		@Override public int compareTo(ConfigurationChange other) {
			int result = path.compareTo(other.path);
			return result == 0 ? key.compareTo(other.key) : result;
		}
	}
}
