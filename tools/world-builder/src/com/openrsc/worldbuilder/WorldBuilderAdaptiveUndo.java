package com.openrsc.worldbuilder;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileStore;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Exact changed-after-safe undo for one successful adaptive import. */
final class WorldBuilderAdaptiveUndo {
	private static final String OPERATION = "undo-adaptive-import";

	interface Observer {
		void observe(String milestone, Path path) throws Exception;
	}

	private static final Observer NO_OP = new Observer() {
		@Override public void observe(String milestone, Path path) {
			// Production undo has no injected observer.
		}
	};

	private final Observer observer;

	WorldBuilderAdaptiveUndo() {
		this(NO_OP);
	}

	WorldBuilderAdaptiveUndo(Observer observer) {
		this.observer = observer == null ? NO_OP : observer;
	}

	Preview preview(Path requestedProject, Path requestedTarget)
		throws IOException, WorldBuilderContractException {
		try {
			return operate(requestedProject, requestedTarget, null, null);
		} catch (IOException failure) {
			throw failure;
		} catch (WorldBuilderContractException failure) {
			throw failure;
		} catch (Exception failure) {
			throw problem(WorldBuilderErrorCodes.MUTATION_FAILED, "undo-preview", false,
				"Adaptive undo preview was interrupted.",
				"Retry after resolving the local interruption.", failure);
		}
	}

	UndoResult apply(Preview preview, String confirmation)
		throws IOException, WorldBuilderContractException {
		if (preview == null) throw new IllegalArgumentException("preview");
		try {
			Preview applied = operate(preview.requestedProject, preview.requestedTarget,
				confirmation, preview);
			return applied.result;
		} catch (WorldBuilderContractException failure) {
			throw failure;
		} catch (IOException failure) {
			throw failure;
		} catch (Exception callbackFailure) {
			throw problem(WorldBuilderErrorCodes.MUTATION_FAILED,
				"confirmation", false,
				"Adaptive undo was interrupted before transaction publication.",
				"Request a fresh undo preview and confirm again.", callbackFailure);
		}
	}

	private Preview operate(Path requestedProject, Path requestedTarget,
		String confirmation, Preview expected) throws Exception {
		WorldBuilderAdaptiveProjectLifecycle.VerifiedProject initial =
			verifyTargetBackedProjectBeforeTarget(requestedProject);
		Path projectRoot = initial.projectRoot;
		Path lockPath = WorldBuilderAdaptiveExporter.requireFile(
			projectRoot, "run/world-builder.lock", "project transaction lock");
		try (FileChannel channel = FileChannel.open(lockPath,
			StandardOpenOption.READ, StandardOpenOption.WRITE)) {
			FileLock lock = tryLock(channel);
			if (lock == null) throw problem(
				WorldBuilderErrorCodes.RECOVERY_REQUIRED, "run/world-builder.lock", false,
				"The project is running or another project operation is active.",
				"Close World Builder and wait for the other operation.");
			try {
				WorldBuilderAdaptiveProjectLifecycle.VerifiedProject project =
					WorldBuilderAdaptiveProjectLifecycle.verifyProjectDirectory(
						projectRoot, true);
				requireSameProject(initial, project);
				WorldBuilderAdaptiveReceipt.State authority = undoAuthority(projectRoot);
				WorldBuilderAdaptiveExporter.VerifiedExport export = findExport(
					project, authority.exportFingerprint());
				Path target = WorldBuilderAdaptiveMutationProfile.requireTarget(
					requestedTarget);
				WorldBuilderTargetCapability beforeLease =
					WorldBuilderTargetCapability.read(
						WorldBuilderReadOnlyTarget.open(target));
				try (WorldBuilderAdaptiveOfflineLease offline =
					WorldBuilderAdaptiveOfflineLease.acquire(target, beforeLease)) {
					WorldBuilderAdaptiveMutationProfile.Plan installed =
						WorldBuilderAdaptiveMutationProfile.reconstructInstalled(
							project, export, target, authority.transactionId());
					WorldBuilderAdaptiveReceipt.requireSuccessfulImportMatches(
						installed, authority);
					String undoId = expected == null ? UUID.randomUUID().toString()
						: expected.undoPlan.transactionId();
					WorldBuilderAdaptiveMutationProfile.Plan undo =
						WorldBuilderAdaptiveMutationProfile.reverseForUndo(installed, undoId);
					if (expected != null
						&& (!expected.installedPlan.canonicalSha256.equals(
							installed.canonicalSha256)
						|| !expected.undoPlan.canonicalSha256.equals(
							undo.canonicalSha256))) throw problem(
						WorldBuilderErrorCodes.TARGET_DRIFT, "mutation-plan", false,
						"Target, project, export, receipt, or undo plan changed after preview.",
						"Request and review a fresh undo preview; there is no force mode.");
					ensureFreeSpace(undo);
					List<String> changed = changedAfterPaths(installed);
					if (!changed.isEmpty()) throw problem(
						WorldBuilderErrorCodes.TARGET_DRIFT, changed.get(0), false,
						"Undo refused before mutation because installed-after data changed: "
							+ join(changed) + ".",
						"Restore the exact installed-after bytes or preserve them and do not undo; no force mode exists.");
					if (confirmation == null) return new Preview(requestedProject,
						requestedTarget, installed, undo, authority, null);
					if (!"UNDO".equals(confirmation)) throw problem(
						WorldBuilderErrorCodes.CONTRACT_VALUE_INVALID, "confirmation", false,
						"Adaptive undo requires exact UNDO confirmation for this preview.",
						"Review the exact changed paths and type UNDO, or cancel.");
					observe("undo-plan-confirmed", projectRoot);
					UndoResult result = applyLocked(installed, undo, authority, offline);
					return new Preview(requestedProject, requestedTarget,
						installed, undo, authority, result);
				}
			} finally {
				lock.release();
			}
		}
	}

	private UndoResult applyLocked(WorldBuilderAdaptiveMutationProfile.Plan installed,
		WorldBuilderAdaptiveMutationProfile.Plan undo,
		WorldBuilderAdaptiveReceipt.State authority,
		WorldBuilderAdaptiveOfflineLease offline)
		throws IOException, WorldBuilderContractException {
		Path project = undo.project.projectRoot;
		Path backupRoot = WorldBuilderPortablePath.resolveContained(project,
			"backups/" + safeTransactionId(undo), OPERATION);
		String createdAt = WorldBuilderAdaptiveReceipt.now();
		List<Path> staged = new ArrayList<Path>();
		boolean mutation = false;
		try {
			ensureFreeSpace(undo);
			prepareDurableUndo(undo, backupRoot);
			WorldBuilderAdaptiveReceipt.State pending =
				WorldBuilderAdaptiveReceipt.create(undo, "undo", "pending",
					createdAt, false, offline.evidence, false, false,
					Collections.<WorldBuilderAdaptiveReceipt.Verification>emptyList(),
					authority.transactionId(), "");
			WorldBuilderAdaptiveReceipt.write(project, pending);
			observeContract("undo-pending-receipt", receiptPath(project,
				undo.transactionId()), false);
			if (!changedAfterPaths(installed).isEmpty()) throw problem(
				WorldBuilderErrorCodes.TARGET_DRIFT, "target", false,
				"Installed-after data changed after confirmation and before undo mutation.",
				"Request a fresh preview; no target file was changed.");

			int index = 0;
			for (WorldBuilderAdaptiveMutationProfile.Action action : undo.actions) {
				Path destination = WorldBuilderAdaptiveMutationProfile.safeDestination(
					undo.targetRoot, action.destinationRelativePath);
				verifyState(undo.targetRoot, action.destinationRelativePath, action.before);
				observeContract("undo-before-" + pad(index), destination, mutation);
				if (!action.after.present) {
					Files.delete(destination);
				} else {
					Path temporary = destination.getParent().resolve("."
						+ destination.getFileName() + ".undo-" + undo.transactionId());
					staged.add(temporary);
					writeBytes(temporary, action.generatedContent);
					verifyFile(temporary, action.after);
					moveAtomicReplacing(temporary, destination,
						action.destinationRelativePath);
					staged.remove(temporary);
				}
				mutation = true;
				observeContract("undo-after-" + pad(index), destination, true);
				index++;
			}
			observeContract("undo-before-directory-cleanup", undo.targetRoot, true);
			cleanupImportedDirectories(installed);
			observeContract("undo-after-directory-cleanup", undo.targetRoot, true);
			List<WorldBuilderAdaptiveReceipt.Verification> verified =
				verifyUndoAfter(undo, installed);
			WorldBuilderAdaptiveReceipt.State reverted =
				WorldBuilderAdaptiveReceipt.create(undo, "undo", "reverted",
					createdAt, true, offline.evidence, true, true, verified,
					authority.transactionId(), "");
			observeContract("undo-before-success-receipt",
				receiptPath(project, undo.transactionId()), true);
			WorldBuilderAdaptiveReceipt.write(project, reverted);
			return new UndoResult(undo.transactionId(), authority.transactionId(),
				"reverted", receiptPath(project, undo.transactionId()));
		} catch (Throwable original) {
			cleanupStaged(staged);
			if (!mutation) {
				writeFailureReceipt(undo, authority, offline, createdAt,
					"failed-no-change", false, false, original);
				throw asContract(original, false,
					"Adaptive undo stopped before changing target content.",
					"Correct the reported problem and request a fresh undo preview.");
			}
			try {
				List<WorldBuilderAdaptiveReceipt.Verification> rollback =
					restoreInstalledState(undo);
				WorldBuilderAdaptiveReceipt.State rolledBack =
					WorldBuilderAdaptiveReceipt.create(undo, "undo", "rolled-back",
						createdAt, true, offline.evidence, false, true, rollback,
						authority.transactionId(), "");
				WorldBuilderAdaptiveReceipt.write(project, rolledBack);
			} catch (Throwable rollbackFailure) {
				writeFailureReceipt(undo, authority, offline, createdAt,
					"recovery-required", true, false, rollbackFailure);
				throw problem(WorldBuilderErrorCodes.RECOVERY_REQUIRED,
					"receipts/" + safeTransactionId(undo) + ".json", true,
					"Undo failed and automatic rollback could not prove the installed state.",
					"Keep the target offline and run explicit adaptive recovery; do not force another transaction.",
					rollbackFailure);
			}
			throw asContract(original, true,
				"Adaptive undo failed after mutation; the complete installed state was restored and verified.",
				"Review the rolled-back receipt and request a fresh undo preview.");
		}
	}

	private static void ensureFreeSpace(
		WorldBuilderAdaptiveMutationProfile.Plan plan)
		throws IOException, WorldBuilderContractException {
		long backupBytes = 1_048_576L;
		long targetBytes = 0L;
		for (WorldBuilderAdaptiveMutationProfile.Action action : plan.actions) {
			backupBytes = safeAdd(backupBytes, action.before.size);
			targetBytes = safeAdd(targetBytes, action.after.size);
		}
		FileStore targetStore = Files.getFileStore(plan.targetRoot);
		FileStore projectStore = Files.getFileStore(plan.project.projectRoot);
		long override = testUsableBytes();
		long targetUsable = targetStore.getUsableSpace();
		long projectUsable = projectStore.getUsableSpace();
		/* The internal test bound may only make this check stricter. */
		if (override >= 0L) {
			targetUsable = Math.min(targetUsable, override);
			projectUsable = Math.min(projectUsable, override);
		}
		if (targetUsable < targetBytes || projectUsable < backupBytes) throw problem(
			WorldBuilderErrorCodes.MUTATION_FAILED, "free-space", false,
			"Target or project storage lacks space for undo staging and backups.",
			"Free space without deleting project backups, then request a fresh preview.");
	}

	private static long safeAdd(long first, long second)
		throws WorldBuilderContractException {
		try {
			long value = Math.addExact(first, second);
			if (value > WorldBuilderContractLimits.MAX_INVENTORY_TOTAL_BYTES) {
				throw new ArithmeticException("bounded total");
			}
			return value;
		} catch (ArithmeticException overflow) {
			throw problem(WorldBuilderErrorCodes.CONTRACT_LIMIT_EXCEEDED,
				"free-space", false,
				"Adaptive undo byte total exceeds its supported bound.",
				"Use one bounded complete adaptive package.");
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

	private static WorldBuilderAdaptiveProjectLifecycle.VerifiedProject
		verifyTargetBackedProjectBeforeTarget(Path requested)
		throws IOException, WorldBuilderContractException {
		WorldBuilderAdaptiveProjectLifecycle.VerifiedProject project =
			WorldBuilderAdaptiveProjectLifecycle.verifyProjectDirectory(requested, true);
		if ("standalone-empty".equals(project.origin)) throw problem(
			WorldBuilderErrorCodes.NO_TARGET, "target-root", false,
			"Standalone project " + project.projectId
				+ " has no target; Undo stopped before target access.",
			"Continue editing/exporting the standalone project; Undo is unavailable.");
		return project;
	}

	private static WorldBuilderAdaptiveReceipt.State undoAuthority(Path project)
		throws IOException, WorldBuilderContractException {
		List<WorldBuilderAdaptiveReceipt.State> receipts =
			WorldBuilderAdaptiveReceipt.readAll(project);
		Set<String> reverted = new HashSet<String>();
		for (WorldBuilderAdaptiveReceipt.State receipt : receipts) {
			if ("pending".equals(receipt.status())
				|| "recovery-required".equals(receipt.status())) throw problem(
				WorldBuilderErrorCodes.RECOVERY_REQUIRED,
				"receipts/" + receipt.transactionId() + ".json", false,
				"An adaptive transaction requires recovery before undo.",
				"Keep the target offline and complete explicit recovery first.");
			if ("undo".equals(receipt.transactionType())
				&& "reverted".equals(receipt.status())) {
				reverted.add(receipt.revertsTransactionId());
			}
		}
		for (int index = receipts.size() - 1; index >= 0; index--) {
			WorldBuilderAdaptiveReceipt.State receipt = receipts.get(index);
			if ("import".equals(receipt.transactionType())
				&& "successful".equals(receipt.status())
				&& !reverted.contains(receipt.transactionId())) return receipt;
		}
		throw problem(WorldBuilderErrorCodes.CONTRACT_VALUE_INVALID, "receipts", false,
			"There is no successful unreverted adaptive import to undo.",
			"Import a reviewed export first, or retain the already restored target.");
	}

	static WorldBuilderAdaptiveExporter.VerifiedExport findExport(
		WorldBuilderAdaptiveProjectLifecycle.VerifiedProject project,
		String fingerprint) throws IOException, WorldBuilderContractException {
		Path exports = WorldBuilderAdaptiveExporter.requireDirectory(
			project.projectRoot, "exports", "project exports directory");
		List<Path> matches = new ArrayList<Path>();
		try (DirectoryStream<Path> stream = Files.newDirectoryStream(exports)) {
			for (Path candidate : stream) {
				String name = candidate.getFileName().toString();
				if (!Files.isDirectory(candidate, LinkOption.NOFOLLOW_LINKS)
					|| Files.isSymbolicLink(candidate) || name.startsWith(".")) continue;
				Path manifest = candidate.resolve(WorldBuilderAdaptiveExporter.MANIFEST_FILE);
				if (!Files.isRegularFile(manifest, LinkOption.NOFOLLOW_LINKS)
					|| Files.isSymbolicLink(manifest)) continue;
				try {
					Map<String,Object> value = WorldBuilderJsonDocuments.readObject(manifest);
					WorldBuilderAdaptiveContracts.validateParsed(
						WorldBuilderAdaptiveContracts.Kind.ADAPTIVE_EXPORT, value);
					WorldBuilderAdaptiveExporter.requireFingerprint(
						value, "exportFingerprintSha256");
					if (fingerprint.equals(WorldBuilderAdaptiveExporter.string(
						value, "exportFingerprintSha256"))) matches.add(candidate);
				} catch (WorldBuilderDiscoveryException ignored) {
					// An unrelated malformed old export cannot authorize this undo.
				} catch (WorldBuilderContractException ignored) {
					// An unrelated invalid old export cannot authorize this undo.
				}
			}
		}
		Collections.sort(matches);
		if (matches.isEmpty()) throw problem(WorldBuilderErrorCodes.SOURCE_CORRUPT,
			"exports", false,
			"The successful import's exact export is missing from this project.",
			"Restore the complete matching export before undo.");
		return WorldBuilderAdaptiveExporter.validate(matches.get(0), project);
	}

	private static List<String> changedAfterPaths(
		WorldBuilderAdaptiveMutationProfile.Plan installed)
		throws IOException, WorldBuilderContractException {
		List<String> changed = new ArrayList<String>();
		Set<String> expected = new HashSet<String>();
		for (WorldBuilderAdaptiveMutationProfile.Action action : installed.actions) {
			expected.add(action.destinationRelativePath);
			if (!matchesState(installed.targetRoot,
				action.destinationRelativePath, action.after)) {
				changed.add(action.destinationRelativePath);
			}
		}
		collectUnexpectedPackageFiles(installed.targetRoot,
			installed.serverPackageRelativePath, expected, changed);
		collectUnexpectedPackageFiles(installed.targetRoot,
			installed.clientPackageRelativePath, expected, changed);
		Collections.sort(changed);
		return changed;
	}

	private static void collectUnexpectedPackageFiles(Path target, String root,
		final Set<String> expected, final List<String> changed)
		throws IOException, WorldBuilderContractException {
		final Path packageRoot = WorldBuilderAdaptiveMutationProfile.safeDestination(
			target, root);
		final Set<String> expectedDirectories = new HashSet<String>();
		for (String file : expected) {
			if (!file.startsWith(root + "/")) continue;
			String parent = file;
			while (parent.lastIndexOf('/') > root.length()) {
				parent = parent.substring(0, parent.lastIndexOf('/'));
				expectedDirectories.add(parent);
			}
		}
		if (!Files.isDirectory(packageRoot, LinkOption.NOFOLLOW_LINKS)
			|| Files.isSymbolicLink(packageRoot)) {
			if (!changed.contains(root)) changed.add(root);
			return;
		}
		Files.walkFileTree(packageRoot, new SimpleFileVisitor<Path>() {
			@Override public FileVisitResult preVisitDirectory(Path dir,
				BasicFileAttributes attrs) throws IOException {
				if (Files.isSymbolicLink(dir)) throw new IOException(
					"linked package directory: " + dir.getFileName());
				if (!dir.equals(packageRoot)) {
					String relative = target.relativize(dir).toString().replace('\\', '/');
					if (!expectedDirectories.contains(relative)
						&& !changed.contains(relative)) changed.add(relative);
				}
				return FileVisitResult.CONTINUE;
			}
			@Override public FileVisitResult visitFile(Path file,
				BasicFileAttributes attrs) throws IOException {
				String relative = target.relativize(file).toString().replace('\\', '/');
				if (!attrs.isRegularFile() || Files.isSymbolicLink(file)
					|| !expected.contains(relative)) changed.add(relative);
				return FileVisitResult.CONTINUE;
			}
		});
	}

	private static void prepareDurableUndo(
		WorldBuilderAdaptiveMutationProfile.Plan plan, Path backupRoot)
		throws IOException, WorldBuilderContractException {
		if (Files.exists(backupRoot, LinkOption.NOFOLLOW_LINKS)
			|| Files.exists(receiptPath(plan.project.projectRoot,
				plan.transactionId()), LinkOption.NOFOLLOW_LINKS)) throw problem(
			WorldBuilderErrorCodes.RECOVERY_REQUIRED, "backups", false,
			"Undo transaction UUID already has durable state.",
			"Retain existing evidence and request a fresh preview.");
		Files.createDirectory(backupRoot);
		writeBytes(backupRoot.resolve("mutation-plan.json"),
			plan.toJson().getBytes(StandardCharsets.UTF_8));
		Map<String,Object> directories = new LinkedHashMap<String,Object>();
		directories.put("schemaVersion", Long.valueOf(1L));
		directories.put("manifestType", "world-builder-created-directories");
		directories.put("transactionId", plan.transactionId());
		directories.put("planFingerprintSha256",
			WorldBuilderAdaptiveExporter.string(plan.document,
				"planFingerprintSha256"));
		directories.put("relativePaths", Collections.emptyList());
		writeBytes(backupRoot.resolve("created-directories.json"),
			WorldBuilderJsonDocuments.pretty(directories)
				.getBytes(StandardCharsets.UTF_8));
		Path content = backupRoot.resolve("content/activation/selected-configuration.json");
		Files.createDirectories(content.getParent());
		writeBytes(content, plan.configurationBytes);
		for (WorldBuilderAdaptiveMutationProfile.Action action : plan.actions) {
			Path source = WorldBuilderAdaptiveMutationProfile.safeExistingFile(
				plan.targetRoot, action.destinationRelativePath, "undo before state");
			Path destination = WorldBuilderPortablePath.resolveContained(
				plan.project.projectRoot, action.backupRelativePath, OPERATION);
			Files.createDirectories(destination.getParent());
			Files.copy(source, destination, StandardCopyOption.COPY_ATTRIBUTES);
			forceFile(destination);
			verifyFile(destination, action.before);
		}
	}

	private static List<WorldBuilderAdaptiveReceipt.Verification> verifyUndoAfter(
		WorldBuilderAdaptiveMutationProfile.Plan undo,
		WorldBuilderAdaptiveMutationProfile.Plan installed)
		throws IOException, WorldBuilderContractException {
		List<WorldBuilderAdaptiveReceipt.Verification> values =
			new ArrayList<WorldBuilderAdaptiveReceipt.Verification>();
		for (int index = 0; index < undo.actions.size(); index++) {
			WorldBuilderAdaptiveMutationProfile.Action action = undo.actions.get(index);
			verifyState(undo.targetRoot, action.destinationRelativePath, action.after);
			values.add(new WorldBuilderAdaptiveReceipt.Verification(
				"undo-post-" + pad(index), true,
				action.after.present ? action.after.sha256 : "absent"));
		}
		WorldBuilderAdaptiveDiscoveryReport report =
			new WorldBuilderAdaptiveDiscovery().discover(undo.targetRoot,
				installed.configuration.configurationId);
		if (!"compatible".equals(report.status)
			|| !installed.targetLineage().equals(report.fingerprintSha256())) {
			throw problem(WorldBuilderErrorCodes.RECOVERY_REQUIRED, "target-root", true,
				"Undo bytes do not reproduce the complete original target lineage.",
				"Keep the target offline while automatic rollback restores the installed state.");
		}
		return values;
	}

	private List<WorldBuilderAdaptiveReceipt.Verification> restoreInstalledState(
		WorldBuilderAdaptiveMutationProfile.Plan undo)
		throws IOException, WorldBuilderContractException {
		List<WorldBuilderAdaptiveMutationProfile.Action> reverse =
			new ArrayList<WorldBuilderAdaptiveMutationProfile.Action>(undo.actions);
		Collections.reverse(reverse);
		for (int index = 0; index < reverse.size(); index++) {
			WorldBuilderAdaptiveMutationProfile.Action action = reverse.get(index);
			if (matchesState(undo.targetRoot,
				action.destinationRelativePath, action.before)) continue;
			verifyState(undo.targetRoot, action.destinationRelativePath, action.after);
			Path destination = WorldBuilderAdaptiveMutationProfile.safeDestination(
				undo.targetRoot, action.destinationRelativePath);
			ensureParents(undo.targetRoot, destination.getParent());
			Path backup = WorldBuilderPortablePath.resolveContained(
				undo.project.projectRoot, action.backupRelativePath, OPERATION);
			verifyFile(backup, action.before);
			Path temporary = destination.getParent().resolve("."
				+ destination.getFileName() + ".undo-rollback-" + undo.transactionId());
			try {
				Files.copy(backup, temporary);
				forceFile(temporary);
				verifyFile(temporary, action.before);
				observeContract("undo-rollback-before-" + pad(index), destination, true);
				moveAtomicReplacing(temporary, destination,
					action.destinationRelativePath);
			} finally {
				if (Files.isRegularFile(temporary, LinkOption.NOFOLLOW_LINKS)
					&& !Files.isSymbolicLink(temporary)) Files.delete(temporary);
			}
		}
		List<WorldBuilderAdaptiveReceipt.Verification> values =
			new ArrayList<WorldBuilderAdaptiveReceipt.Verification>();
		for (int index = 0; index < undo.actions.size(); index++) {
			WorldBuilderAdaptiveMutationProfile.Action action = undo.actions.get(index);
			verifyState(undo.targetRoot, action.destinationRelativePath, action.before);
			values.add(new WorldBuilderAdaptiveReceipt.Verification(
				"undo-rollback-" + pad(index), true, action.before.sha256));
		}
		return values;
	}

	private static void cleanupImportedDirectories(
		WorldBuilderAdaptiveMutationProfile.Plan installed) throws IOException {
		List<String> reverse = new ArrayList<String>(installed.directoriesToCreate);
		Collections.sort(reverse, new Comparator<String>() {
			@Override public int compare(String left, String right) {
				int depth = right.split("/").length - left.split("/").length;
				return depth == 0 ? right.compareTo(left) : depth;
			}
		});
		for (String relative : reverse) {
			Path path = installed.targetRoot.resolve(relative).normalize();
			if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
				&& !Files.isSymbolicLink(path)) Files.delete(path);
		}
	}

	private static void ensureParents(Path root, Path parent)
		throws IOException, WorldBuilderContractException {
		Path relative = root.relativize(parent.toAbsolutePath().normalize());
		Path cursor = root;
		for (Path segment : relative) {
			cursor = cursor.resolve(segment.toString());
			if (!Files.exists(cursor, LinkOption.NOFOLLOW_LINKS)) {
				Files.createDirectory(cursor);
			} else if (!Files.isDirectory(cursor, LinkOption.NOFOLLOW_LINKS)
				|| Files.isSymbolicLink(cursor)) throw problem(
				WorldBuilderErrorCodes.UNSAFE_PATH,
				root.relativize(cursor).toString().replace('\\', '/'), true,
				"Rollback parent is linked or not a directory.",
				"Keep the target offline and use explicit adaptive recovery.");
		}
	}

	private static void writeFailureReceipt(
		WorldBuilderAdaptiveMutationProfile.Plan undo,
		WorldBuilderAdaptiveReceipt.State authority,
		WorldBuilderAdaptiveOfflineLease offline, String createdAt,
		String status, boolean mutation, boolean rollback, Throwable failure) {
		try {
			List<WorldBuilderAdaptiveReceipt.Verification> values =
				new ArrayList<WorldBuilderAdaptiveReceipt.Verification>();
			if ("recovery-required".equals(status)) values.add(
				new WorldBuilderAdaptiveReceipt.Verification(
					"undo-recovery-required", false, bounded(failure.getMessage())));
			WorldBuilderAdaptiveReceipt.write(undo.project.projectRoot,
				WorldBuilderAdaptiveReceipt.create(undo, "undo", status, createdAt,
					mutation, offline.evidence, false, rollback, values,
					authority.transactionId(), ""));
		} catch (Exception ignored) {
			// Primary recovery-required failure remains authoritative.
		}
	}

	private static boolean matchesState(Path target, String relative,
		WorldBuilderAdaptiveMutationProfile.FileState expected)
		throws IOException, WorldBuilderContractException {
		Path path = WorldBuilderAdaptiveMutationProfile.safeDestination(target, relative);
		if (!expected.present) return !Files.exists(path, LinkOption.NOFOLLOW_LINKS);
		return Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
			&& !Files.isSymbolicLink(path) && Files.size(path) == expected.size
			&& expected.sha256.equals(WorldBuilderHashes.sha256(path));
	}

	private static void verifyState(Path target, String relative,
		WorldBuilderAdaptiveMutationProfile.FileState expected)
		throws IOException, WorldBuilderContractException {
		if (!matchesState(target, relative, expected)) throw problem(
			WorldBuilderErrorCodes.TARGET_DRIFT, relative, false,
			"Target bytes do not match the exact transaction state.",
			"Do not force the transaction; restore the exact expected bytes.");
	}

	private static void verifyFile(Path path,
		WorldBuilderAdaptiveMutationProfile.FileState expected)
		throws IOException, WorldBuilderContractException {
		if (!expected.present || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
			|| Files.isSymbolicLink(path) || Files.size(path) != expected.size
			|| !expected.sha256.equals(WorldBuilderHashes.sha256(path))) throw problem(
			WorldBuilderErrorCodes.RECOVERY_REQUIRED, path.getFileName().toString(), false,
			"Durable undo backup does not match its expected bytes.",
			"Retain and restore the complete exact transaction backup.");
	}

	private static void moveAtomicReplacing(Path source, Path destination,
		String relative) throws IOException, WorldBuilderContractException {
		try {
			Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE,
				StandardCopyOption.REPLACE_EXISTING);
		} catch (AtomicMoveNotSupportedException unsupported) {
			throw problem(WorldBuilderErrorCodes.MUTATION_FAILED, relative, true,
				"Target filesystem cannot atomically restore configuration.",
				"Keep the target offline and use an atomic local filesystem.", unsupported);
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

	private static void cleanupStaged(List<Path> staged) {
		for (Path path : staged) try {
			if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
				&& !Files.isSymbolicLink(path)) Files.delete(path);
		} catch (IOException ignored) {
			// Subsequent verification decides whether recovery is required.
		}
	}

	private void observe(String milestone, Path path) throws Exception {
		observer.observe(milestone, path);
	}

	private void observeContract(String milestone, Path path, boolean mutation)
		throws WorldBuilderContractException {
		try {
			observe(milestone, path);
		} catch (WorldBuilderContractException failure) {
			throw failure;
		} catch (Exception failure) {
			throw problem(WorldBuilderErrorCodes.MUTATION_FAILED,
				path.getFileName().toString(), mutation,
				"Injected or external failure interrupted adaptive undo.",
				mutation ? "Keep the target offline while rollback runs."
					: "Request a fresh undo preview.", failure);
		}
	}

	private static FileLock tryLock(FileChannel channel) throws IOException {
		try {
			return channel.tryLock();
		} catch (OverlappingFileLockException busy) {
			return null;
		}
	}

	private static Path receiptPath(Path project, String transactionId)
		throws WorldBuilderContractException {
		return WorldBuilderPortablePath.resolveContained(project,
			"receipts/" + transactionId + ".json", OPERATION);
	}

	private static String safeTransactionId(
		WorldBuilderAdaptiveMutationProfile.Plan plan) {
		try {
			return plan.transactionId();
		} catch (WorldBuilderContractException impossible) {
			throw new IllegalStateException(impossible);
		}
	}

	private static String join(List<String> values) {
		StringBuilder result = new StringBuilder();
		for (int index = 0; index < values.size() && index < 32; index++) {
			if (index > 0) result.append(", ");
			result.append(values.get(index));
		}
		if (values.size() > 32) result.append(", and ")
			.append(values.size() - 32).append(" more paths");
		return result.toString();
	}

	private static String bounded(String message) {
		if (message == null || message.trim().isEmpty()) return "unspecified failure";
		return message.length() <= 2048 ? message : message.substring(0, 2048);
	}

	private static String pad(int value) {
		return String.format(java.util.Locale.ROOT, "%04d", Integer.valueOf(value));
	}

	private static void requireSameProject(
		WorldBuilderAdaptiveProjectLifecycle.VerifiedProject first,
		WorldBuilderAdaptiveProjectLifecycle.VerifiedProject second)
		throws WorldBuilderContractException {
		if (!first.projectId.equals(second.projectId)
			|| !first.origin.equals(second.origin)
			|| !first.working.fingerprintSha256.equals(
				second.working.fingerprintSha256)
			|| !WorldBuilderAdaptiveExporter.canonicalHash(first.manifest).equals(
				WorldBuilderAdaptiveExporter.canonicalHash(second.manifest))
			|| !WorldBuilderAdaptiveExporter.canonicalHash(first.snapshot).equals(
				WorldBuilderAdaptiveExporter.canonicalHash(second.snapshot))) throw problem(
			WorldBuilderErrorCodes.SOURCE_CORRUPT, "project.json", false,
			"Project/source/working state changed during undo.",
			"Close the editor and request a fresh undo preview.");
	}

	private static WorldBuilderContractException asContract(Throwable failure,
		boolean mutation, String message, String nextStep) {
		if (failure instanceof WorldBuilderContractException
			&& ((WorldBuilderContractException)failure).mutationOccurred() == mutation) {
			return (WorldBuilderContractException)failure;
		}
		return problem(WorldBuilderErrorCodes.MUTATION_FAILED, "target", mutation,
			message + " Cause: " + bounded(failure.getMessage()), nextStep, failure);
	}

	private static WorldBuilderContractException problem(String code, String path,
		boolean mutation, String message, String nextStep) {
		return new WorldBuilderContractException(code, OPERATION, path,
			mutation, message, nextStep);
	}

	private static WorldBuilderContractException problem(String code, String path,
		boolean mutation, String message, String nextStep, Throwable cause) {
		return new WorldBuilderContractException(code, OPERATION, path,
			mutation, message, nextStep, cause);
	}

	static final class Preview {
		final Path requestedProject;
		final Path requestedTarget;
		final WorldBuilderAdaptiveMutationProfile.Plan installedPlan;
		final WorldBuilderAdaptiveMutationProfile.Plan undoPlan;
		final WorldBuilderAdaptiveReceipt.State authority;
		final UndoResult result;

		Preview(Path requestedProject, Path requestedTarget,
			WorldBuilderAdaptiveMutationProfile.Plan installedPlan,
			WorldBuilderAdaptiveMutationProfile.Plan undoPlan,
			WorldBuilderAdaptiveReceipt.State authority, UndoResult result) {
			this.requestedProject = requestedProject;
			this.requestedTarget = requestedTarget;
			this.installedPlan = installedPlan;
			this.undoPlan = undoPlan;
			this.authority = authority;
			this.result = result;
		}

		String toJson() {
			return undoPlan.toJson();
		}

		String humanSummary() {
			StringBuilder value = new StringBuilder(2048);
			value.append("Undo preview (no target files changed)\n")
				.append("Transaction: ").append(undoPlan.document.get("transactionId"))
				.append("\nReverts import: ").append(authority.document.get("transactionId"))
				.append("\nServer package removed: ")
				.append(installedPlan.serverPackageRelativePath)
				.append("\nClient package removed: ")
				.append(installedPlan.clientPackageRelativePath)
				.append("\nConfiguration restored: ")
				.append(installedPlan.configuration.relativePath)
				.append("\nAffected files: ").append(undoPlan.actions.size())
				.append("\nConfirmation required: UNDO\n");
			return value.toString();
		}
	}

	static final class UndoResult {
		final String transactionId;
		final String revertedTransactionId;
		final String status;
		final Path receiptPath;

		UndoResult(String transactionId, String revertedTransactionId,
			String status, Path receiptPath) {
			this.transactionId = transactionId;
			this.revertedTransactionId = revertedTransactionId;
			this.status = status;
			this.receiptPath = receiptPath;
		}

		String toJson() {
			Map<String,Object> value = new LinkedHashMap<String,Object>();
			value.put("status", status);
			value.put("transactionId", transactionId);
			value.put("revertedTransactionId", revertedTransactionId);
			value.put("receiptPath", receiptPath.toString());
			return WorldBuilderJsonDocuments.pretty(value);
		}
	}
}
