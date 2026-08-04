package com.openrsc.worldbuilder;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Exact preview/apply engine for adaptive package installation.
 *
 * This class is intentionally separate from the frozen fixed-layout importer.
 * It accepts destinations only from a compiled adaptive mutation profile and
 * keeps package publication ahead of the final configuration activation.
 */
final class WorldBuilderAdaptiveImporter {
	private static final String OPERATION = "import-adaptive-project";

	interface ConfirmationGate {
		String confirm(WorldBuilderAdaptiveMutationProfile.Plan plan) throws Exception;
	}

	interface Observer {
		void observe(String milestone, Path path) throws Exception;
	}

	private static final Observer NO_OP_OBSERVER = new Observer() {
		@Override public void observe(String milestone, Path path) {
			// Production import has no injected observer.
		}
	};

	private final Observer observer;

	WorldBuilderAdaptiveImporter() {
		this(NO_OP_OBSERVER);
	}

	WorldBuilderAdaptiveImporter(Observer observer) {
		this.observer = observer == null ? NO_OP_OBSERVER : observer;
	}

	Preview preview(Path requestedProject,
		Path requestedExport, Path requestedTarget)
		throws IOException, WorldBuilderContractException {
		try {
			ImportOutcome outcome = operate(
				requestedProject, requestedExport, requestedTarget, null, null);
			return new Preview(outcome.plan, requestedProject, requestedExport,
				requestedTarget);
		} catch (WorldBuilderContractException failure) {
			throw failure;
		} catch (IOException failure) {
			throw failure;
		} catch (Exception impossibleGateFailure) {
			throw problem(WorldBuilderErrorCodes.MUTATION_FAILED, "preview", false,
				"Adaptive import preview was interrupted.",
				"Retry after resolving the local interruption.", impossibleGateFailure);
		}
	}

	ImportOutcome apply(Path requestedProject, Path requestedExport,
		Path requestedTarget, final String confirmation)
		throws IOException, WorldBuilderContractException {
		try {
			return operate(requestedProject, requestedExport, requestedTarget,
				new ConfirmationGate() {
					@Override public String confirm(
						WorldBuilderAdaptiveMutationProfile.Plan plan) {
						return confirmation;
					}
				}, null);
		} catch (WorldBuilderContractException failure) {
			throw failure;
		} catch (IOException failure) {
			throw failure;
		} catch (Exception callbackFailure) {
			throw problem(WorldBuilderErrorCodes.MUTATION_FAILED, "confirmation", false,
				"Adaptive import confirmation was interrupted.",
				"Review a fresh preview and confirm again.", callbackFailure);
		}
	}

	ImportResult apply(Preview preview, final String confirmation)
		throws Exception {
		if (preview == null) throw new IllegalArgumentException("preview");
		ImportOutcome outcome = operate(preview.requestedProject,
			preview.requestedExport, preview.requestedTarget,
			new ConfirmationGate() {
				@Override public String confirm(
					WorldBuilderAdaptiveMutationProfile.Plan plan) {
					return confirmation;
				}
			}, preview);
		return outcome.result;
	}

	ImportOutcome applyInteractive(Path requestedProject, Path requestedExport,
		Path requestedTarget, ConfirmationGate confirmation)
		throws Exception {
		if (confirmation == null) throw new IllegalArgumentException("confirmation");
		return operate(requestedProject, requestedExport, requestedTarget,
			confirmation, null);
	}

	private ImportOutcome operate(Path requestedProject, Path requestedExport,
		Path requestedTarget, ConfirmationGate confirmation, Preview expectedPreview)
		throws Exception {
		/* Verify origin before resolving, opening, or locking any target path. */
		WorldBuilderAdaptiveProjectLifecycle.VerifiedProject initial =
			WorldBuilderAdaptiveProjectLifecycle.verifyProjectDirectory(
				requestedProject, true);
		if ("standalone-empty".equals(initial.origin)) throw problem(
			WorldBuilderErrorCodes.NO_TARGET, "project.json", false,
			"Standalone project " + initial.projectId
				+ " has no target; import stopped before target access.",
			"Continue editing/exporting the standalone project; Import is unavailable.");
		if (!"ready-attached".equals(initial.state)) throw problem(
			WorldBuilderErrorCodes.PROJECT_DETACHED, "project.json", false,
			"Only an exactly attached target-backed project can be imported.",
			"Open the project against its exact compatible target before importing.");

		Path project = initial.projectRoot;
		Path run = WorldBuilderAdaptiveExporter.requireDirectory(
			project, "run", "project run directory");
		Path lockPath = run.resolve("world-builder.lock");
		try (FileChannel channel = FileChannel.open(lockPath,
			StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
			FileLock projectLock = tryLock(channel);
			if (projectLock == null) throw problem(
				WorldBuilderErrorCodes.RECOVERY_REQUIRED, "run/world-builder.lock", false,
				"The project is running or another project operation is active.",
				"Close World Builder and wait for the other operation.");
			try {
				WorldBuilderAdaptiveProjectLifecycle.VerifiedProject verified =
					WorldBuilderAdaptiveProjectLifecycle.verifyProjectDirectory(project, true);
				requireSameProject(initial, verified);
				requireNoUnresolvedTransaction(project);
				Path target = WorldBuilderAdaptiveMutationProfile.requireTarget(requestedTarget);
				WorldBuilderAdaptiveExporter.VerifiedExport export =
					WorldBuilderAdaptiveExporter.validate(requestedExport, verified);
				WorldBuilderReadOnlyTarget readOnly = WorldBuilderReadOnlyTarget.open(target);
				WorldBuilderTargetCapability beforeLease =
					WorldBuilderTargetCapability.read(readOnly);
				try (WorldBuilderAdaptiveOfflineLease offline =
					WorldBuilderAdaptiveOfflineLease.acquire(target, beforeLease)) {
					WorldBuilderTargetCapability lockedCapability =
						WorldBuilderTargetCapability.read(
							WorldBuilderReadOnlyTarget.open(target));
					if (!beforeLease.evidenceSha256.equals(lockedCapability.evidenceSha256)) {
						throw problem(WorldBuilderErrorCodes.TARGET_DRIFT,
							WorldBuilderTargetCapability.RELATIVE_PATH, false,
							"Target capability changed while its transaction lock was acquired.",
							"Stop target updates and request a fresh import preview.");
					}
					String transactionId = expectedPreview == null
						? UUID.randomUUID().toString()
						: expectedPreview.plan.transactionId();
					WorldBuilderAdaptiveMutationProfile.Plan plan =
						WorldBuilderAdaptiveMutationProfile.prepare(
							verified, export, target, transactionId);
					if (expectedPreview != null
						&& !expectedPreview.plan.canonicalSha256.equals(
							plan.canonicalSha256)) throw problem(
						WorldBuilderErrorCodes.TARGET_DRIFT, "mutation-plan", false,
						"Target, project, export, or plan changed after the reviewed preview.",
						"Request and review a fresh preview; there is no force mode.");
					ensureFreeSpace(plan);
					if (confirmation == null) return new ImportOutcome(plan, null);
					String supplied = confirmation.confirm(plan);
					if (!"IMPORT".equals(supplied)) throw problem(
						WorldBuilderErrorCodes.CONTRACT_VALUE_INVALID, "confirmation", false,
						"Adaptive import requires exact IMPORT confirmation for this preview.",
						"Review the complete plan and type IMPORT exactly, or cancel.");
					observe("plan-confirmed", project);
					return new ImportOutcome(plan, applyLocked(plan, offline));
				}
			} finally {
				projectLock.release();
			}
		}
	}

	private ImportResult applyLocked(WorldBuilderAdaptiveMutationProfile.Plan plan,
		WorldBuilderAdaptiveOfflineLease offline)
		throws IOException, WorldBuilderContractException {
		Path project = plan.project.projectRoot;
		Path target = plan.targetRoot;
		Path backupRoot = WorldBuilderPortablePath.resolveContained(project,
			"backups/" + plan.transactionId(), OPERATION);
		String createdAt = WorldBuilderAdaptiveReceipt.now();
		WorldBuilderAdaptiveReceipt.State receipt = null;
		boolean targetMutation = false;
		List<Path> staged = new ArrayList<Path>();
		try {
			ensureFreeSpace(plan);
			Files.createDirectory(backupRoot);
			writeTransactionEvidence(plan, backupRoot);
			backupBeforeState(plan, backupRoot);
			observe("backups-verified", backupRoot);
			receipt = WorldBuilderAdaptiveReceipt.create(plan, "import", "pending",
				createdAt, false, offline.evidence, false, false,
				Collections.<WorldBuilderAdaptiveReceipt.Verification>emptyList(), "", "");
			WorldBuilderAdaptiveReceipt.write(project, receipt);
			observe("pending-receipt-written", receiptPath(project, plan.transactionId()));

			/* Revalidate every immutable input after callbacks and before target writes. */
			WorldBuilderAdaptiveProjectLifecycle.VerifiedProject currentProject =
				WorldBuilderAdaptiveProjectLifecycle.verifyProjectDirectory(project, true);
			requireSameProject(plan.project, currentProject);
			WorldBuilderAdaptiveExporter.VerifiedExport currentExport =
				WorldBuilderAdaptiveExporter.validate(plan.export.root, currentProject);
			if (!plan.export.manifestCanonicalSha256.equals(
				currentExport.manifestCanonicalSha256)) throw problem(
				WorldBuilderErrorCodes.SOURCE_CORRUPT, "exports", false,
				"Adaptive export changed after preview and before mutation.",
				"Create and review a fresh complete export.");
			verifyBeforeState(plan);
			observe("before-first-target-mutation", target);

			int packageIndex = 0;
			for (WorldBuilderAdaptiveMutationProfile.Action action : plan.actions) {
				if (action.activation) continue;
				Path destination = WorldBuilderAdaptiveMutationProfile.safeDestination(
					target, action.destinationRelativePath);
				if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) throw problem(
					WorldBuilderErrorCodes.TARGET_DRIFT,
					action.destinationRelativePath, targetMutation,
					"A planned content-addressed destination appeared after preview.",
					"Do not overwrite it; restore the exact preview state and retry.");
				ensureParentDirectories(plan, destination.getParent());
				Path temporary = destination.getParent().resolve("."
					+ destination.getFileName() + ".stage-" + plan.transactionId());
				staged.add(temporary);
				copyActionContent(plan, action, temporary);
				observe("package-file-staged-" + pad(packageIndex), temporary);
				moveAtomicNew(temporary, destination, action.destinationRelativePath);
				staged.remove(temporary);
				targetMutation = true;
				observe("package-file-published-" + pad(packageIndex), destination);
				packageIndex++;
			}

			for (WorldBuilderAdaptiveMutationProfile.Action action : plan.actions) {
				if (!action.activation) continue;
				verifyState(target, action.destinationRelativePath, action.before);
				Path destination = WorldBuilderAdaptiveMutationProfile.safeExistingFile(
					target, action.destinationRelativePath,
					"activation configuration");
				Path temporary = destination.getParent().resolve("."
					+ destination.getFileName() + ".stage-" + plan.transactionId());
				staged.add(temporary);
				writeBytes(temporary, action.generatedContent);
				verifyFile(temporary, action.after);
				observe("activation-staged", temporary);
				observe("before-activation", destination);
				moveAtomicReplacing(temporary, destination,
					action.destinationRelativePath);
				staged.remove(temporary);
				targetMutation = true;
				observe("activation-published", destination);
			}

			List<WorldBuilderAdaptiveReceipt.Verification> verifications =
				verifyAfterState(plan);
			observe("post-write-verified", target);
			receipt = WorldBuilderAdaptiveReceipt.create(plan, "import", "successful",
				createdAt, true, offline.evidence, true, false,
				verifications, "", "");
			observe("before-success-receipt", receiptPath(project, plan.transactionId()));
			WorldBuilderAdaptiveReceipt.write(project, receipt);
			return new ImportResult(plan.transactionId(), "successful",
				plan.exportFingerprint(), plan.serverPackageRelativePath,
				plan.clientPackageRelativePath, receiptPath(project, plan.transactionId()));
		} catch (Throwable failure) {
			cleanupStaged(staged);
			if (!targetMutation) {
				cleanupCreatedDirectories(plan);
				writeFailureReceiptIfPossible(plan, createdAt, offline, "failed-no-change",
					false, false, failure);
				throw asContractFailure(failure, false,
					"Adaptive import stopped before changing target content.",
					"Correct the reported problem and request a fresh preview.");
			}
			try {
				List<WorldBuilderAdaptiveReceipt.Verification> rollback =
					rollback(plan);
				cleanupCreatedDirectories(plan);
				WorldBuilderAdaptiveReceipt.State rolledBack =
					WorldBuilderAdaptiveReceipt.create(plan, "import", "rolled-back",
						createdAt, true, offline.evidence, false, true,
						rollback, "", "");
				WorldBuilderAdaptiveReceipt.write(project, rolledBack);
			} catch (WorldBuilderContractException rollbackFailure) {
				writeFailureReceiptIfPossible(plan, createdAt, offline,
					"recovery-required", true, false, rollbackFailure);
				throw problem(WorldBuilderErrorCodes.RECOVERY_REQUIRED, "receipts/"
					+ plan.transactionId() + ".json", true,
					"Adaptive import and automatic rollback could not prove the target before state.",
					"Keep the target offline and run adaptive recovery; do not force another import.",
					rollbackFailure);
			} catch (IOException rollbackFailure) {
				writeFailureReceiptIfPossible(plan, createdAt, offline,
					"recovery-required", true, false, rollbackFailure);
				throw problem(WorldBuilderErrorCodes.RECOVERY_REQUIRED, "receipts/"
					+ plan.transactionId() + ".json", true,
					"Adaptive import and automatic rollback could not prove the target before state.",
					"Keep the target offline and run adaptive recovery; do not force another import.",
					rollbackFailure);
			}
			throw asContractFailure(failure, true,
				"Adaptive import failed after mutation; the exact before state was restored and verified.",
				"Review the rolled-back receipt, correct the cause, and make a fresh preview.");
		}
	}

	private static void requireNoUnresolvedTransaction(Path project)
		throws IOException, WorldBuilderContractException {
		for (WorldBuilderAdaptiveReceipt.State receipt :
			WorldBuilderAdaptiveReceipt.readAll(project)) {
			if ("pending".equals(receipt.status())
				|| "recovery-required".equals(receipt.status())) {
				throw problem(WorldBuilderErrorCodes.RECOVERY_REQUIRED,
					"receipts/" + receipt.transactionId() + ".json", false,
					"An earlier adaptive transaction requires recovery.",
					"Keep the target offline and complete recovery before another transaction.");
			}
		}
	}

	private static void ensureFreeSpace(WorldBuilderAdaptiveMutationProfile.Plan plan)
		throws IOException, WorldBuilderContractException {
		long targetBytes = 0L;
		long backupBytes = 0L;
		for (WorldBuilderAdaptiveMutationProfile.Action action : plan.actions) {
			targetBytes = safeAdd(targetBytes, action.after.size);
			backupBytes = safeAdd(backupBytes, action.before.size);
		}
		FileStore targetStore = Files.getFileStore(plan.targetRoot);
		FileStore projectStore = Files.getFileStore(plan.project.projectRoot);
		long override = testUsableBytes();
		long targetUsable = override < 0L ? targetStore.getUsableSpace() : override;
		long projectUsable = override < 0L ? projectStore.getUsableSpace() : override;
		if (targetUsable < targetBytes
			|| projectUsable < backupBytes + 1_048_576L) {
			throw problem(WorldBuilderErrorCodes.MUTATION_FAILED, "free-space", false,
				"Target or project storage lacks space for content, backups, and receipts.",
				"Free space without deleting project backups, then request a fresh preview.");
		}
	}

	private static long testUsableBytes() throws WorldBuilderContractException {
		String value = System.getProperty(
			"worldbuilder.adaptive.testUsableBytes", "");
		if (value.isEmpty()) return -1L;
		try {
			long parsed = Long.parseLong(value);
			if (parsed < 0L) throw new NumberFormatException();
			return parsed;
		} catch (NumberFormatException invalid) {
			throw problem(WorldBuilderErrorCodes.CONTRACT_VALUE_INVALID,
				"free-space", false,
				"Internal adaptive free-space test override is invalid.",
				"Remove the invalid internal test property.");
		}
	}

	private static void writeTransactionEvidence(
		WorldBuilderAdaptiveMutationProfile.Plan plan, Path backupRoot)
		throws IOException, WorldBuilderContractException {
		Path planPath = backupRoot.resolve("mutation-plan.json");
		writeBytes(planPath, plan.toJson().getBytes(StandardCharsets.UTF_8));
		WorldBuilderAdaptiveContracts.Document read = WorldBuilderAdaptiveContracts.read(
			WorldBuilderAdaptiveContracts.Kind.MUTATION_PLAN, planPath);
		if (!plan.canonicalSha256.equals(read.canonicalSha256)) throw problem(
			WorldBuilderErrorCodes.RECOVERY_REQUIRED, "mutation-plan.json", false,
			"Durable mutation plan did not verify.",
			"Stop before target mutation and inspect project storage health.");
		Path activation = WorldBuilderPortablePath.resolveContained(backupRoot,
			"content/activation/selected-configuration.json", OPERATION);
		Files.createDirectories(activation.getParent());
		writeBytes(activation, plan.configurationBytes);
		Map<String,Object> directories = new LinkedHashMap<String,Object>();
		directories.put("schemaVersion", Long.valueOf(1L));
		directories.put("manifestType", "world-builder-created-directories");
		directories.put("transactionId", plan.transactionId());
		directories.put("planFingerprintSha256",
			WorldBuilderAdaptiveExporter.string(
				plan.document, "planFingerprintSha256"));
		directories.put("relativePaths", new ArrayList<String>(plan.directoriesToCreate));
		writeBytes(backupRoot.resolve("created-directories.json"),
			WorldBuilderJsonDocuments.pretty(directories).getBytes(StandardCharsets.UTF_8));
	}

	private static void backupBeforeState(WorldBuilderAdaptiveMutationProfile.Plan plan,
		Path backupRoot) throws IOException, WorldBuilderContractException {
		for (WorldBuilderAdaptiveMutationProfile.Action action : plan.actions) {
			if (!action.before.present) continue;
			Path source = WorldBuilderAdaptiveMutationProfile.safeExistingFile(
				plan.targetRoot, action.destinationRelativePath, "planned target file");
			String inside = action.backupRelativePath.substring(
				("backups/" + plan.transactionId() + "/").length());
			Path destination = WorldBuilderPortablePath.resolveContained(
				backupRoot, inside, OPERATION);
			Files.createDirectories(destination.getParent());
			Files.copy(source, destination, StandardCopyOption.COPY_ATTRIBUTES);
			verifyFile(destination, action.before);
		}
	}

	private static void verifyBeforeState(WorldBuilderAdaptiveMutationProfile.Plan plan)
		throws IOException, WorldBuilderContractException {
		for (WorldBuilderAdaptiveMutationProfile.Action action : plan.actions) {
			verifyState(plan.targetRoot, action.destinationRelativePath, action.before);
		}
	}

	private static void copyActionContent(WorldBuilderAdaptiveMutationProfile.Plan plan,
		WorldBuilderAdaptiveMutationProfile.Action action, Path temporary)
		throws IOException, WorldBuilderContractException {
		if (action.generatedContent != null) {
			writeBytes(temporary, action.generatedContent);
		} else {
			Path source = WorldBuilderAdaptiveExporter.requireFile(
				plan.export.root, action.contentRelativePath, "adaptive export content");
			Files.copy(source, temporary);
			forceFile(temporary);
		}
		verifyFile(temporary, action.after);
	}

	private static List<WorldBuilderAdaptiveReceipt.Verification> verifyAfterState(
		WorldBuilderAdaptiveMutationProfile.Plan plan)
		throws IOException, WorldBuilderContractException {
		List<WorldBuilderAdaptiveReceipt.Verification> values =
			new ArrayList<WorldBuilderAdaptiveReceipt.Verification>();
		for (int index = 0; index < plan.actions.size(); index++) {
			WorldBuilderAdaptiveMutationProfile.Action action = plan.actions.get(index);
			verifyState(plan.targetRoot, action.destinationRelativePath, action.after);
			values.add(new WorldBuilderAdaptiveReceipt.Verification(
				"post-" + pad(index), true,
				action.after.present ? action.after.sha256 : "absent"));
		}
		verifyInstalledSemantics(plan);
		return values;
	}

	private static void verifyInstalledSemantics(
		WorldBuilderAdaptiveMutationProfile.Plan plan)
		throws IOException, WorldBuilderContractException {
		WorldBuilderReadOnlyTarget target = WorldBuilderReadOnlyTarget.open(plan.targetRoot);
		WorldBuilderTargetCapability capability = WorldBuilderTargetCapability.read(target);
		if (!plan.capability.evidenceSha256.equals(capability.evidenceSha256)
			|| !plan.capability.capabilityId.equals(capability.capabilityId)
			|| !plan.profileId.equals(capability.mutationProfileId)) throw problem(
			WorldBuilderErrorCodes.CAPABILITY_MISMATCH,
			WorldBuilderTargetCapability.RELATIVE_PATH, true,
			"Target capability changed during installation.",
			"Keep the target offline so the transaction can roll back.");
		WorldBuilderAdaptiveConfiguration.Selection selected =
			WorldBuilderAdaptiveConfiguration.select(target, capability,
				plan.configuration.configurationId);
		WorldBuilderAdaptiveConfiguration configuration = selected.selected;
		if (!"layered".equals(configuration.representation)
			|| !plan.serverPackageRelativePath.equals(
				configuration.serverMapRelativePath)
			|| !plan.clientPackageRelativePath.equals(
				configuration.clientMapRelativePath)) throw problem(
			WorldBuilderErrorCodes.MAP_MISMATCH, configuration.relativePath, true,
			"Installed configuration does not select both planned layered packages.",
			"Keep the target offline so the transaction can roll back.");
		WorldBuilderCompatibilityEvidence common =
			WorldBuilderCompatibilityEvidence.inspect(target, capability, configuration);
		WorldBuilderGenericLayeredPackage server =
			WorldBuilderGenericLayeredPackage.inspect(target,
				plan.serverPackageRelativePath, "installed-server", common.definitions);
		WorldBuilderGenericLayeredPackage client =
			WorldBuilderGenericLayeredPackage.inspect(target,
				plan.clientPackageRelativePath, "installed-client", common.definitions);
		if (!plan.export.packageValue.fingerprintSha256.equals(
			server.fingerprintSha256)
			|| !server.fingerprintSha256.equals(client.fingerprintSha256)
			|| !server.packageId.equals(client.packageId)
			|| !server.packageVersion.equals(client.packageVersion)) throw problem(
			WorldBuilderErrorCodes.MAP_MISMATCH, "installed-package", true,
			"Installed server/client packages do not match the validated export.",
			"Keep the target offline so the transaction can roll back.");
	}

	private List<WorldBuilderAdaptiveReceipt.Verification> rollback(
		WorldBuilderAdaptiveMutationProfile.Plan plan)
		throws IOException, WorldBuilderContractException {
		List<WorldBuilderAdaptiveMutationProfile.Action> reverse =
			new ArrayList<WorldBuilderAdaptiveMutationProfile.Action>(plan.actions);
		Collections.reverse(reverse);
		for (int index = 0; index < reverse.size(); index++) {
			WorldBuilderAdaptiveMutationProfile.Action action = reverse.get(index);
			Path destination = WorldBuilderAdaptiveMutationProfile.safeDestination(
				plan.targetRoot, action.destinationRelativePath);
			if (action.before.present) {
				if (matchesState(plan.targetRoot, action.destinationRelativePath,
					action.before)) continue;
				verifyState(plan.targetRoot, action.destinationRelativePath, action.after);
				Path backup = WorldBuilderPortablePath.resolveContained(
					plan.project.projectRoot, action.backupRelativePath, OPERATION);
				verifyFile(backup, action.before);
				Path temporary = destination.getParent().resolve("."
					+ destination.getFileName() + ".rollback-" + plan.transactionId());
				Files.copy(backup, temporary);
				forceFile(temporary);
				verifyFile(temporary, action.before);
				observeRollback("rollback-before-" + pad(index), destination);
				moveAtomicReplacing(temporary, destination,
					action.destinationRelativePath);
			} else if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
				verifyState(plan.targetRoot, action.destinationRelativePath, action.after);
				observeRollback("rollback-before-" + pad(index), destination);
				Files.delete(destination);
			}
			observeRollback("rollback-after-" + pad(index), destination);
		}
		List<WorldBuilderAdaptiveReceipt.Verification> values =
			new ArrayList<WorldBuilderAdaptiveReceipt.Verification>();
		for (int index = 0; index < plan.actions.size(); index++) {
			WorldBuilderAdaptiveMutationProfile.Action action = plan.actions.get(index);
			verifyState(plan.targetRoot, action.destinationRelativePath, action.before);
			values.add(new WorldBuilderAdaptiveReceipt.Verification(
				"rollback-" + pad(index), true,
				action.before.present ? action.before.sha256 : "absent"));
		}
		return values;
	}

	private static boolean matchesState(Path target, String relative,
		WorldBuilderAdaptiveMutationProfile.FileState expected)
		throws IOException, WorldBuilderContractException {
		Path path = WorldBuilderAdaptiveMutationProfile.safeDestination(target, relative);
		if (!expected.present) return !Files.exists(path, LinkOption.NOFOLLOW_LINKS);
		if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
			|| Files.isSymbolicLink(path) || Files.size(path) != expected.size) return false;
		return expected.sha256.equals(WorldBuilderHashes.sha256(path));
	}

	private void observeRollback(String milestone, Path path)
		throws WorldBuilderContractException {
		try {
			observe(milestone, path);
		} catch (WorldBuilderContractException failure) {
			throw failure;
		} catch (Exception failure) {
			throw problem(WorldBuilderErrorCodes.RECOVERY_REQUIRED,
				path.getFileName().toString(), true,
				"Injected or external failure interrupted automatic rollback.",
				"Keep the target offline and run explicit adaptive recovery.", failure);
		}
	}

	private static void ensureParentDirectories(
		WorldBuilderAdaptiveMutationProfile.Plan plan, Path parent)
		throws IOException, WorldBuilderContractException {
		Path target = plan.targetRoot;
		Path relative = target.relativize(parent.toAbsolutePath().normalize());
		Path cursor = target;
		for (Path segment : relative) {
			cursor = cursor.resolve(segment.toString());
			if (!Files.exists(cursor, LinkOption.NOFOLLOW_LINKS)) {
				Files.createDirectory(cursor);
			} else if (!Files.isDirectory(cursor, LinkOption.NOFOLLOW_LINKS)
				|| Files.isSymbolicLink(cursor)) {
				throw problem(WorldBuilderErrorCodes.UNSAFE_PATH,
					target.relativize(cursor).toString().replace('\\', '/'), false,
					"Mutation parent is linked or not a directory.",
					"Restore one real contained target directory layout.");
			}
		}
	}

	private static void cleanupCreatedDirectories(
		WorldBuilderAdaptiveMutationProfile.Plan plan) throws IOException {
		List<String> reverse = new ArrayList<String>(plan.directoriesToCreate);
		Collections.sort(reverse, new Comparator<String>() {
			@Override public int compare(String left, String right) {
				int depth = right.split("/").length - left.split("/").length;
				return depth == 0 ? right.compareTo(left) : depth;
			}
		});
		for (String relative : reverse) {
			Path path = plan.targetRoot.resolve(relative).normalize();
			if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
				&& !Files.isSymbolicLink(path)) Files.delete(path);
		}
	}

	private static void cleanupStaged(List<Path> staged) {
		for (Path path : staged) {
			try {
				if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
					&& !Files.isSymbolicLink(path)) Files.delete(path);
			} catch (IOException ignored) {
				// Rollback verification determines whether recovery is required.
			}
		}
	}

	private static void verifyState(Path target, String relative,
		WorldBuilderAdaptiveMutationProfile.FileState expected)
		throws IOException, WorldBuilderContractException {
		Path path = WorldBuilderAdaptiveMutationProfile.safeDestination(target, relative);
		if (!expected.present) {
			if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) throw problem(
				WorldBuilderErrorCodes.TARGET_DRIFT, relative, false,
				"Expected target path absence does not match current state.",
				"Do not force the transaction; restore the exact expected state.");
			return;
		}
		Path file = WorldBuilderAdaptiveMutationProfile.safeExistingFile(
			target, relative, "planned target file");
		verifyFile(file, expected);
	}

	private static void verifyFile(Path path,
		WorldBuilderAdaptiveMutationProfile.FileState expected)
		throws IOException, WorldBuilderContractException {
		if (!expected.present || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
			|| Files.isSymbolicLink(path) || Files.size(path) != expected.size
			|| !expected.sha256.equals(WorldBuilderHashes.sha256(path))) throw problem(
			WorldBuilderErrorCodes.TARGET_DRIFT, path.getFileName().toString(), false,
			"File bytes do not match the exact planned state.",
			"Do not force the transaction; restore the exact expected bytes.");
	}

	private static void moveAtomicNew(Path source, Path destination, String relative)
		throws IOException, WorldBuilderContractException {
		try {
			Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE);
		} catch (AtomicMoveNotSupportedException unsupported) {
			throw problem(WorldBuilderErrorCodes.MUTATION_FAILED, relative, false,
				"Target filesystem cannot atomically publish package content.",
				"Use a local filesystem with atomic same-directory moves.", unsupported);
		}
	}

	private static void moveAtomicReplacing(Path source, Path destination,
		String relative) throws IOException, WorldBuilderContractException {
		try {
			Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE,
				StandardCopyOption.REPLACE_EXISTING);
		} catch (AtomicMoveNotSupportedException unsupported) {
			throw problem(WorldBuilderErrorCodes.MUTATION_FAILED, relative, false,
				"Target filesystem cannot atomically activate or restore configuration.",
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

	private static void writeFailureReceiptIfPossible(
		WorldBuilderAdaptiveMutationProfile.Plan plan, String createdAt,
		WorldBuilderAdaptiveOfflineLease offline, String status,
		boolean mutationOccurred, boolean rollbackVerified, Throwable failure) {
		try {
			List<WorldBuilderAdaptiveReceipt.Verification> values =
				new ArrayList<WorldBuilderAdaptiveReceipt.Verification>();
			if ("recovery-required".equals(status)) {
				values.add(new WorldBuilderAdaptiveReceipt.Verification(
					"recovery-required", false,
					bounded(failure.getMessage())));
			}
			WorldBuilderAdaptiveReceipt.State receipt =
				WorldBuilderAdaptiveReceipt.create(plan, "import", status, createdAt,
					mutationOccurred, offline.evidence, false, rollbackVerified,
					values, "", "");
			WorldBuilderAdaptiveReceipt.write(plan.project.projectRoot, receipt);
		} catch (Exception ignored) {
			// The primary failure remains authoritative; missing durable recovery
			// evidence is reported as RECOVERY_REQUIRED by the caller.
		}
	}

	private static WorldBuilderContractException asContractFailure(
		Throwable failure, boolean mutationOccurred, String message, String nextStep) {
		if (failure instanceof WorldBuilderContractException
			&& ((WorldBuilderContractException)failure).mutationOccurred()
				== mutationOccurred) return (WorldBuilderContractException)failure;
		return problem(WorldBuilderErrorCodes.MUTATION_FAILED, "target", mutationOccurred,
			message + " Cause: " + bounded(failure.getMessage()), nextStep, failure);
	}

	private static String bounded(String value) {
		if (value == null || value.trim().isEmpty()) return "unspecified transaction failure";
		return value.length() <= 2048 ? value : value.substring(0, 2048);
	}

	private static Path receiptPath(Path project, String transactionId)
		throws WorldBuilderContractException {
		return WorldBuilderPortablePath.resolveContained(project,
			"receipts/" + transactionId + ".json", OPERATION);
	}

	private static long safeAdd(long first, long second)
		throws WorldBuilderContractException {
		try {
			long result = Math.addExact(first, second);
			if (result > WorldBuilderContractLimits.MAX_INVENTORY_TOTAL_BYTES) {
				throw new ArithmeticException("bounded total");
			}
			return result;
		} catch (ArithmeticException overflow) {
			throw problem(WorldBuilderErrorCodes.CONTRACT_LIMIT_EXCEEDED,
				"free-space", false,
				"Adaptive import byte total exceeds its supported bound.",
				"Use a smaller complete validated package.");
		}
	}

	private static String pad(int value) {
		return String.format(java.util.Locale.ROOT, "%04d", Integer.valueOf(value));
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

	private static void requireSameProject(
		WorldBuilderAdaptiveProjectLifecycle.VerifiedProject before,
		WorldBuilderAdaptiveProjectLifecycle.VerifiedProject after)
		throws WorldBuilderContractException {
		if (!before.projectId.equals(after.projectId)
			|| !before.origin.equals(after.origin)
			|| !before.state.equals(after.state)
			|| !before.working.fingerprintSha256.equals(
				after.working.fingerprintSha256)
			|| !WorldBuilderAdaptiveExporter.canonicalHash(before.manifest).equals(
				WorldBuilderAdaptiveExporter.canonicalHash(after.manifest))
			|| !WorldBuilderAdaptiveExporter.canonicalHash(before.snapshot).equals(
				WorldBuilderAdaptiveExporter.canonicalHash(after.snapshot))) {
			throw problem(WorldBuilderErrorCodes.SOURCE_CORRUPT, "project.json", false,
				"Adaptive project/source/working state changed during import.",
				"Close the editor, save one complete project, and request a fresh preview.");
		}
	}

	private static WorldBuilderContractException problem(String code, String path,
		boolean mutationOccurred, String message, String nextStep) {
		return new WorldBuilderContractException(code, OPERATION, path,
			mutationOccurred, message, nextStep);
	}

	private static WorldBuilderContractException problem(String code, String path,
		boolean mutationOccurred, String message, String nextStep, Throwable cause) {
		return new WorldBuilderContractException(code, OPERATION, path,
			mutationOccurred, message, nextStep, cause);
	}

	static final class ImportOutcome {
		final WorldBuilderAdaptiveMutationProfile.Plan plan;
		final ImportResult result;

		ImportOutcome(WorldBuilderAdaptiveMutationProfile.Plan plan, ImportResult result) {
			this.plan = plan;
			this.result = result;
		}
	}

	static final class Preview {
		final WorldBuilderAdaptiveMutationProfile.Plan plan;
		final Path requestedProject;
		final Path requestedExport;
		final Path requestedTarget;

		Preview(WorldBuilderAdaptiveMutationProfile.Plan plan, Path requestedProject,
			Path requestedExport, Path requestedTarget) {
			this.plan = plan;
			this.requestedProject = requestedProject;
			this.requestedExport = requestedExport;
			this.requestedTarget = requestedTarget;
		}

		String humanSummary() {
			return plan.humanSummary();
		}

		String toJson() {
			return plan.toJson();
		}
	}

	static final class ImportResult {
		final String transactionId;
		final String status;
		final String exportFingerprintSha256;
		final String serverPackageRelativePath;
		final String clientPackageRelativePath;
		final Path receiptPath;

		ImportResult(String transactionId, String status,
			String exportFingerprintSha256, String serverPackageRelativePath,
			String clientPackageRelativePath, Path receiptPath) {
			this.transactionId = transactionId;
			this.status = status;
			this.exportFingerprintSha256 = exportFingerprintSha256;
			this.serverPackageRelativePath = serverPackageRelativePath;
			this.clientPackageRelativePath = clientPackageRelativePath;
			this.receiptPath = receiptPath;
		}

		String toJson() {
			Map<String,Object> value = new LinkedHashMap<String,Object>();
			value.put("status", status);
			value.put("transactionId", transactionId);
			value.put("exportFingerprintSha256", exportFingerprintSha256);
			value.put("serverPackageRelativePath", serverPackageRelativePath);
			value.put("clientPackageRelativePath", clientPackageRelativePath);
			value.put("receiptPath", receiptPath.toString());
			value.put("administratorAction",
				"Distribute the exact installed client package to every player before restart.");
			return WorldBuilderJsonDocuments.pretty(value);
		}
	}
}
