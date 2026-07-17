package com.openrsc.worldbuilder;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.FileStore;
import java.nio.file.FileVisitResult;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Validates an export against its exact offline target and prepares an import plan. */
public final class WorldBuilderImporter {
	private static final Destination[] DESTINATIONS = {
		new Destination("terrain", "serverTerrain",
			"server/conf/server/data/Custom_Landscape.orsc"),
		new Destination("terrain", "clientTerrain",
			"Client_Base/Cache/video/Custom_Landscape.orsc"),
		new Destination("sceneryLocs", "sceneryLocs",
			"server/conf/server/defs/locs/MyWorldSceneryLocs.json"),
		new Destination("sceneryRemovals", "sceneryRemovals",
			"server/conf/server/defs/locs/MyWorldSceneryRemovals.json"),
		new Destination("npcLocs", "npcLocs",
			"server/conf/server/defs/locs/MyWorldNpcLocs.json"),
		new Destination("npcRemovals", "npcRemovals",
			"server/conf/server/defs/locs/MyWorldNpcRemovals.json")
	};

	public ImportPlan preview(Path requestedWorkspace, Path requestedExport, Path requestedTarget)
		throws IOException, WorldBuilderDiscoveryException {
		return operate(requestedWorkspace, requestedExport, requestedTarget, false).plan;
	}

	public ImportResult apply(Path requestedWorkspace, Path requestedExport, Path requestedTarget)
		throws IOException, WorldBuilderDiscoveryException {
		return operate(requestedWorkspace, requestedExport, requestedTarget, true).result;
	}

	public RollbackPlan previewRollback(Path requestedWorkspace, Path requestedTarget)
		throws IOException, WorldBuilderDiscoveryException {
		return operateRollback(requestedWorkspace, requestedTarget, false).plan;
	}

	public RollbackResult rollback(Path requestedWorkspace, Path requestedTarget)
		throws IOException, WorldBuilderDiscoveryException {
		return operateRollback(requestedWorkspace, requestedTarget, true).result;
	}

	private Operation operate(Path requestedWorkspace, Path requestedExport,
		Path requestedTarget, boolean apply) throws IOException, WorldBuilderDiscoveryException {
		Path workspace = canonicalDirectory(requestedWorkspace, "Builder workspace");
		Path target = canonicalDirectory(requestedTarget, "target private-server root");
		Path workspaceLockPath = workspace.getParent()
			.resolve("." + workspace.getFileName() + ".world-builder.lock");
		try (FileChannel workspaceChannel = FileChannel.open(workspaceLockPath,
			StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
			FileLock workspaceLock = tryLock(workspaceChannel,
				"Close World Builder before previewing or importing map changes.");
			try {
				WorldBuilderSourceSnapshot.verify(workspace);
				WorldBuilderProjectSource project = WorldBuilderProjectSource.read(
					workspace.resolve("source/project-source.json"));
				Path configPath = containedFile(target, target.resolve(project.selectedConfig),
					"selected target configuration");
				try (FileChannel configChannel = apply
					? FileChannel.open(configPath, StandardOpenOption.READ, StandardOpenOption.WRITE)
					: FileChannel.open(configPath, StandardOpenOption.READ)) {
					FileLock targetLock = tryLock(configChannel, !apply,
						"Another import or rollback is already using the target configuration.");
					try (WorldBuilderTargetOfflineLease offline =
						WorldBuilderTargetOfflineLease.acquire(target, project.selectedConfig)) {
						ImportPlan plan = planLocked(workspace, requestedExport, target, project);
						return apply
							? new Operation(plan, applyLocked(plan, project))
							: new Operation(plan, null);
					} finally {
						targetLock.release();
					}
				}
			} finally {
				workspaceLock.release();
			}
		}
	}

	private RollbackOperation operateRollback(Path requestedWorkspace,
		Path requestedTarget, boolean apply) throws IOException, WorldBuilderDiscoveryException {
		Path workspace = canonicalDirectory(requestedWorkspace, "Builder workspace");
		Path target = canonicalDirectory(requestedTarget, "target private-server root");
		Path workspaceLockPath = workspace.getParent()
			.resolve("." + workspace.getFileName() + ".world-builder.lock");
		try (FileChannel workspaceChannel = FileChannel.open(workspaceLockPath,
			StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
			FileLock workspaceLock = tryLock(workspaceChannel,
				"Close World Builder before previewing or undoing map changes.");
			try {
				WorldBuilderSourceSnapshot.verify(workspace);
				WorldBuilderProjectSource project = WorldBuilderProjectSource.read(
					workspace.resolve("source/project-source.json"));
				Path configPath = containedFile(target, target.resolve(project.selectedConfig),
					"selected target configuration");
				try (FileChannel configChannel = apply
					? FileChannel.open(configPath, StandardOpenOption.READ, StandardOpenOption.WRITE)
					: FileChannel.open(configPath, StandardOpenOption.READ)) {
					FileLock targetLock = tryLock(configChannel, !apply,
						"Another import or rollback is already using the target configuration.");
					try (WorldBuilderTargetOfflineLease offline =
						WorldBuilderTargetOfflineLease.acquire(target, project.selectedConfig)) {
						verifySelectedConfig(target, project);
						WorldBuilderImportReceipt imported = latestUndoableReceipt(
							workspace, target, project);
						RollbackPlan plan = rollbackPlan(workspace, target, imported);
						return apply
							? new RollbackOperation(plan, applyRollbackLocked(plan, project))
							: new RollbackOperation(plan, null);
					} finally {
						targetLock.release();
					}
				}
			} finally {
				workspaceLock.release();
			}
		}
	}

	private WorldBuilderImportReceipt latestUndoableReceipt(Path workspace, Path target,
		WorldBuilderProjectSource project) throws IOException, WorldBuilderDiscoveryException {
		Path receipts = workspace.resolve("receipts").normalize();
		if (!Files.isDirectory(receipts, LinkOption.NOFOLLOW_LINKS)
			|| Files.isSymbolicLink(receipts) || !receipts.toRealPath().startsWith(workspace)) {
			throw new WorldBuilderDiscoveryException("No verified import receipt is available to undo.");
		}
		String targetIdentity = WorldBuilderImportReceipt.targetIdentity(target);
		List<Path> paths = new ArrayList<Path>();
		try (java.nio.file.DirectoryStream<Path> stream = Files.newDirectoryStream(receipts, "*.json")) {
			for (Path path : stream) {
				if (Files.isSymbolicLink(path) || paths.size() >= 10_000) {
					throw new WorldBuilderDiscoveryException("Receipt directory is unsafe or unbounded.");
				}
				paths.add(path);
			}
		}
		java.util.Collections.sort(paths, java.util.Collections.reverseOrder());
		List<WorldBuilderImportReceipt> receiptsForTarget =
			new ArrayList<WorldBuilderImportReceipt>();
		java.util.Set<String> reverted = new java.util.HashSet<String>();
		for (Path path : paths) {
			WorldBuilderImportReceipt receipt = WorldBuilderImportReceipt.read(path);
			if (!targetIdentity.equals(receipt.targetIdentitySha256)) {
				continue;
			}
			if (!project.layoutAdapter.equals(receipt.layoutAdapter)
				|| !project.selectedConfig.equals(receipt.selectedConfig)) {
				throw new WorldBuilderDiscoveryException(
					"A target receipt does not match this Builder project layout.");
			}
			if ("pending".equals(receipt.status)) {
				throw new WorldBuilderDiscoveryException(
					"A pending target transaction requires recovery before another rollback.");
			}
			if ("rollback".equals(receipt.transactionType)
				&& "successful".equals(receipt.status)) {
				reverted.add(receipt.revertsTransactionId);
			}
			receiptsForTarget.add(receipt);
		}
		for (WorldBuilderImportReceipt receipt : receiptsForTarget) {
			if ("import".equals(receipt.transactionType)
				&& "successful".equals(receipt.status)
				&& !reverted.contains(receipt.transactionId)) {
				validateReceiptDestinations(receipt);
				return receipt;
			}
		}
		throw new WorldBuilderDiscoveryException("No successful unreverted import is available to undo.");
	}

	private static void validateReceiptDestinations(WorldBuilderImportReceipt receipt)
		throws WorldBuilderDiscoveryException {
		java.util.Set<String> allowed = new java.util.HashSet<String>();
		for (Destination destination : DESTINATIONS) {
			allowed.add(destination.relativePath);
		}
		for (WorldBuilderImportReceipt.FileRecord file : receipt.files) {
			if (!allowed.contains(file.relativePath)) {
				throw new WorldBuilderDiscoveryException(
					"Import receipt contains an unsupported destination.");
			}
		}
	}

	private RollbackPlan rollbackPlan(Path workspace, Path target,
		WorldBuilderImportReceipt imported) throws IOException, WorldBuilderDiscoveryException {
		List<RollbackAction> actions = new ArrayList<RollbackAction>();
		for (WorldBuilderImportReceipt.FileRecord file : imported.files) {
			Path destination = destination(target, file.relativePath);
			verifyCurrent(destination, file.installedPresent, file.installedSha256);
			Path sourceBackup = null;
			if (file.existedBefore) {
				sourceBackup = workspace.resolve(file.backupRelativePath).normalize();
				if (!sourceBackup.startsWith(workspace)
					|| !Files.isRegularFile(sourceBackup, LinkOption.NOFOLLOW_LINKS)
					|| Files.isSymbolicLink(sourceBackup)
					|| !file.beforeSha256.equals(WorldBuilderHashes.sha256(sourceBackup))) {
					throw new WorldBuilderDiscoveryException(
						"Import backup is missing or invalid: " + file.relativePath);
				}
			}
			actions.add(new RollbackAction(file.relativePath,
				file.existedBefore ? "restore" : "remove", file.installedPresent,
				file.installedSha256, file.existedBefore, file.beforeSha256, sourceBackup));
		}
		return new RollbackPlan(workspace, target, imported, actions);
	}

	private ImportResult applyLocked(ImportPlan plan, WorldBuilderProjectSource project)
		throws IOException, WorldBuilderDiscoveryException {
		WorldBuilderImportReceipt pending = WorldBuilderImportReceipt.pendingImport(
			plan.targetRoot, plan);
		Path backupRoot = safeWorkspaceDirectory(plan.workspace, "backups")
			.resolve(pending.transactionId).normalize();
		Path staging = plan.targetRoot.resolve(
			".world-builder-import-staging-" + pending.transactionId).normalize();
		if (!backupRoot.startsWith(plan.workspace) || Files.exists(backupRoot, LinkOption.NOFOLLOW_LINKS)
			|| !staging.startsWith(plan.targetRoot) || Files.exists(staging, LinkOption.NOFOLLOW_LINKS)) {
			throw new WorldBuilderDiscoveryException(
				"Import transaction paths already exist or are unsafe.");
		}
		ensureSpace(plan, backupRoot, staging);
		pending.write(plan.workspace);
		boolean targetReplacementBegan = false;
		try {
			Files.createDirectories(backupRoot.resolve("before"));
			verifyCreatedTransactionDirectory(plan.workspace, backupRoot);
			Files.createDirectory(staging);
			for (int index = 0; index < plan.actions.size(); index++) {
				Action action = plan.actions.get(index);
				Path destination = destination(plan.targetRoot, action.relativePath);
				verifyCurrent(destination, action.existedBefore, action.beforeSha256);
				if (action.existedBefore) {
					Path backup = backupRoot.resolve("before").resolve(action.relativePath).normalize();
					if (!backup.startsWith(backupRoot)) {
						throw new WorldBuilderDiscoveryException("Backup path escapes its transaction.");
					}
					Files.createDirectories(backup.getParent());
					Files.copy(destination, backup);
					if (!action.beforeSha256.equals(WorldBuilderHashes.sha256(backup))) {
						throw new IOException("Backup verification failed for " + action.relativePath);
					}
				}
				Path staged = staging.resolve(action.relativePath).normalize();
				if (!staged.startsWith(staging)) {
					throw new WorldBuilderDiscoveryException("Staged path escapes its transaction.");
				}
				Files.createDirectories(staged.getParent());
				Files.copy(action.exportedPath, staged);
				verifyInstalled(staged, action.relativePath, action.installedSha256);
			}

			WorldBuilderDiscoveryResult current = new WorldBuilderDiscovery().discover(
				plan.targetRoot, plan.selectedConfig, project.contentFingerprint);
			if (!plan.sourceFingerprint.equals(current.sourceFingerprintSha256)) {
				throw new WorldBuilderDiscoveryException(
					"Target changed after import planning; no files were installed.");
			}

			int replaced = 0;
			for (Action action : plan.actions) {
				verifySelectedConfig(plan.targetRoot, project);
				Path destination = destination(plan.targetRoot, action.relativePath);
				verifyCurrent(destination, action.existedBefore, action.beforeSha256);
				Path staged = staging.resolve(action.relativePath).normalize();
				targetReplacementBegan = true;
				moveReplacing(staged, destination);
				verifyInstalled(destination, action.relativePath, action.installedSha256);
				replaced++;
				failIfInjected(replaced);
			}
			verifyInstalledPlan(plan);
			WorldBuilderImportReceipt successful = pending.withStatus("successful");
			successful.write(plan.workspace);
			deleteTree(staging);
			return new ImportResult(successful.transactionId,
				successful.receiptPath(plan.workspace), backupRoot, plan.actions.size());
		} catch (Exception failure) {
			if (targetReplacementBegan) {
				try {
					restoreBeforeState(plan, pending, backupRoot);
					pending.withStatus("rolled-back").write(plan.workspace);
				} catch (Exception recoveryFailure) {
					failure.addSuppressed(recoveryFailure);
					throw new IOException("Import failed and automatic recovery did not verify; "
						+ "retain pending receipt " + pending.receiptPath(plan.workspace), failure);
				}
			} else {
				pending.withStatus("rolled-back").write(plan.workspace);
			}
			deleteTree(staging);
			if (failure instanceof WorldBuilderDiscoveryException) {
				throw (WorldBuilderDiscoveryException)failure;
			}
			if (failure instanceof IOException) {
				throw (IOException)failure;
			}
			throw new IOException("Import transaction failed: " + failure.getMessage(), failure);
		}
	}

	private RollbackResult applyRollbackLocked(RollbackPlan plan,
		WorldBuilderProjectSource project)
		throws IOException, WorldBuilderDiscoveryException {
		WorldBuilderImportReceipt pending = WorldBuilderImportReceipt.pendingRollback(
			plan.targetRoot, plan.importedReceipt);
		Path backupRoot = safeWorkspaceDirectory(plan.workspace, "backups")
			.resolve(pending.transactionId).normalize();
		Path staging = plan.targetRoot.resolve(
			".world-builder-rollback-staging-" + pending.transactionId).normalize();
		if (!backupRoot.startsWith(plan.workspace) || Files.exists(backupRoot, LinkOption.NOFOLLOW_LINKS)
			|| !staging.startsWith(plan.targetRoot) || Files.exists(staging, LinkOption.NOFOLLOW_LINKS)) {
			throw new WorldBuilderDiscoveryException(
				"Rollback transaction paths already exist or are unsafe.");
		}
		ensureRollbackSpace(plan);
		pending.write(plan.workspace);
		boolean replacementBegan = false;
		try {
			Files.createDirectories(backupRoot.resolve("before"));
			verifyCreatedTransactionDirectory(plan.workspace, backupRoot);
			Files.createDirectory(staging);
			for (RollbackAction action : plan.actions) {
				Path destination = destination(plan.targetRoot, action.relativePath);
				verifyCurrent(destination, action.currentPresent, action.currentSha256);
				if (action.currentPresent) {
					Path backup = backupRoot.resolve("before").resolve(action.relativePath).normalize();
					if (!backup.startsWith(backupRoot)) {
						throw new WorldBuilderDiscoveryException("Rollback backup escapes its transaction.");
					}
					Files.createDirectories(backup.getParent());
					Files.copy(destination, backup);
					if (!action.currentSha256.equals(WorldBuilderHashes.sha256(backup))) {
						throw new IOException("Rollback backup verification failed: " + action.relativePath);
					}
				}
				if (action.restoredPresent) {
					Path staged = staging.resolve(action.relativePath).normalize();
					Files.createDirectories(staged.getParent());
					Files.copy(action.restoreSource, staged);
					verifyInstalled(staged, action.relativePath, action.restoredSha256);
				}
			}

			for (RollbackAction action : plan.actions) {
				verifyCurrent(destination(plan.targetRoot, action.relativePath),
					action.currentPresent, action.currentSha256);
			}
			int replaced = 0;
			for (RollbackAction action : plan.actions) {
				verifySelectedConfig(plan.targetRoot, project);
				Path destination = destination(plan.targetRoot, action.relativePath);
				replacementBegan = true;
				if (action.restoredPresent) {
					moveReplacing(staging.resolve(action.relativePath).normalize(), destination);
					verifyInstalled(destination, action.relativePath, action.restoredSha256);
				} else {
					Files.delete(destination);
					verifyCurrent(destination, false, "");
				}
				replaced++;
				failRollbackIfInjected(replaced);
			}
			verifyRollbackState(plan, false);
			WorldBuilderImportReceipt successful = pending.withStatus("successful");
			successful.write(plan.workspace);
			plan.importedReceipt.withStatus("reverted").write(plan.workspace);
			deleteTree(staging);
			return new RollbackResult(successful.transactionId,
				successful.receiptPath(plan.workspace), backupRoot,
				plan.importedReceipt.transactionId, plan.actions.size());
		} catch (Exception failure) {
			if (replacementBegan) {
				try {
					restoreRollbackBeforeState(plan, backupRoot);
					pending.withStatus("rolled-back").write(plan.workspace);
					plan.importedReceipt.withStatus("successful").write(plan.workspace);
				} catch (Exception recoveryFailure) {
					failure.addSuppressed(recoveryFailure);
					throw new IOException("Rollback failed and automatic recovery did not verify; "
						+ "retain pending receipt " + pending.receiptPath(plan.workspace), failure);
				}
			} else {
				pending.withStatus("rolled-back").write(plan.workspace);
			}
			deleteTree(staging);
			if (failure instanceof WorldBuilderDiscoveryException) {
				throw (WorldBuilderDiscoveryException)failure;
			}
			if (failure instanceof IOException) {
				throw (IOException)failure;
			}
			throw new IOException("Rollback transaction failed: " + failure.getMessage(), failure);
		}
	}

	private static void ensureRollbackSpace(RollbackPlan plan)
		throws IOException, WorldBuilderDiscoveryException {
		long backupBytes = 0L;
		long stagedBytes = 0L;
		for (RollbackAction action : plan.actions) {
			if (action.currentPresent) {
				backupBytes = safeAdd(backupBytes,
					Files.size(destination(plan.targetRoot, action.relativePath)));
			}
			if (action.restoredPresent) {
				stagedBytes = safeAdd(stagedBytes, Files.size(action.restoreSource));
			}
		}
		long reserve = 1_048_576L;
		if (Files.getFileStore(plan.workspace).getUsableSpace() < safeAdd(backupBytes, reserve)
			|| Files.getFileStore(plan.targetRoot).getUsableSpace()
				< safeAdd(stagedBytes, reserve)
			|| !Files.isWritable(plan.workspace) || !Files.isWritable(plan.targetRoot)) {
			throw new WorldBuilderDiscoveryException(
				"Insufficient space or permissions for a recoverable rollback.");
		}
	}

	private static void verifyRollbackState(RollbackPlan plan, boolean currentState)
		throws IOException, WorldBuilderDiscoveryException {
		for (RollbackAction action : plan.actions) {
			boolean present = currentState ? action.currentPresent : action.restoredPresent;
			String hash = currentState ? action.currentSha256 : action.restoredSha256;
			Path destination = destination(plan.targetRoot, action.relativePath);
			verifyCurrent(destination, present, hash);
			if (present) {
				verifyInstalled(destination, action.relativePath, hash);
			}
		}
		Path serverTerrain = plan.targetRoot.resolve(
			"server/conf/server/data/Custom_Landscape.orsc");
		Path clientTerrain = plan.targetRoot.resolve(
			"Client_Base/Cache/video/Custom_Landscape.orsc");
		if (!WorldBuilderHashes.sha256(serverTerrain).equals(
			WorldBuilderHashes.sha256(clientTerrain))) {
			throw new IOException("Rollback left server and client terrain archives different.");
		}
	}

	private static void restoreRollbackBeforeState(RollbackPlan plan, Path backupRoot)
		throws IOException, WorldBuilderDiscoveryException {
		for (RollbackAction action : plan.actions) {
			Path target = destination(plan.targetRoot, action.relativePath);
			if (!action.currentPresent) {
				Files.deleteIfExists(target);
				continue;
			}
			Path backup = backupRoot.resolve("before").resolve(action.relativePath).normalize();
			if (!backup.startsWith(backupRoot)
				|| !Files.isRegularFile(backup, LinkOption.NOFOLLOW_LINKS)
				|| Files.isSymbolicLink(backup)
				|| !action.currentSha256.equals(WorldBuilderHashes.sha256(backup))) {
				throw new IOException("Rollback safeguard is invalid: " + action.relativePath);
			}
			Path restore = target.resolveSibling("." + target.getFileName()
				+ ".rollback-restore-" + UUID.randomUUID());
			Files.copy(backup, restore);
			moveReplacing(restore, target);
		}
		verifyRollbackState(plan, true);
	}

	private static void failRollbackIfInjected(int replacements) throws IOException {
		String configured = System.getProperty("worldbuilder.rollback.failAfterReplacements", "");
		if (configured.isEmpty()) {
			return;
		}
		try {
			if (Integer.parseInt(configured) == replacements) {
				throw new IOException(
					"Injected rollback failure after replacement " + replacements + ".");
			}
		} catch (NumberFormatException invalid) {
			throw new IOException("Invalid rollback failure-injection setting.");
		}
	}

	private ImportPlan planLocked(Path workspace, Path requestedExport, Path target,
		WorldBuilderProjectSource project) throws IOException, WorldBuilderDiscoveryException {
		WorldBuilderExportBundle bundle = WorldBuilderExportBundle.open(requestedExport);
		Path exports = workspace.resolve("exports").normalize();
		if (!Files.isDirectory(exports, LinkOption.NOFOLLOW_LINKS)
			|| Files.isSymbolicLink(exports) || !bundle.root.getParent().equals(exports.toRealPath())) {
			throw new WorldBuilderDiscoveryException(
				"Import requires one immutable export directory from this Builder workspace.");
		}
		if (!project.layoutAdapter.equals(bundle.manifest.layoutAdapter)
			|| !project.sourceFingerprint.equals(bundle.manifest.sourceFingerprint)
			|| !project.contentFingerprint.equals(bundle.manifest.contentFingerprint)) {
			throw new WorldBuilderDiscoveryException(
				"Export provenance does not match this Builder project source.");
		}

		WorldBuilderDiscoveryResult discovered = new WorldBuilderDiscovery().discover(
			target, project.selectedConfig, project.contentFingerprint);
		if (!project.layoutAdapter.equals(discovered.layoutAdapter)
			|| !project.sourceFingerprint.equals(discovered.sourceFingerprintSha256)) {
			throw new WorldBuilderDiscoveryException(
				"Target world files or configuration changed since this Builder project was created.");
		}

		List<Action> actions = new ArrayList<Action>();
		for (Destination destination : DESTINATIONS) {
			WorldBuilderExportBundle.ExportedFile exported = bundle.required(destination.exportLogical);
			WorldBuilderProjectSource.FileState source = project.required(destination.sourceLogical);
			WorldBuilderDiscoveryResult.SourceFile current = sourceFile(discovered, destination.sourceLogical);
			if (source.present != current.present || source.size != current.size
				|| !source.sha256.equals(current.sha256)
				|| source.present != exported.record.sourcePresent
				|| !source.sha256.equals(exported.record.sourceSha256)) {
				throw new WorldBuilderDiscoveryException(
					"Target/source state is inconsistent for " + destination.relativePath + ".");
			}
			if (!exported.record.changed) {
				continue;
			}
			actions.add(new Action(source.present ? "replace" : "add", destination.relativePath,
				source.present, source.sha256, exported.record.sha256, exported.path));
		}
		if (actions.isEmpty()) {
			throw new WorldBuilderDiscoveryException("Export contains no installable changes.");
		}
		return new ImportPlan(workspace, target, bundle.root, project.selectedConfig,
			project.sourceFingerprint, bundle.manifestSha256, bundle.manifest.builderVersion,
			bundle.manifest.sourceCommit, actions);
	}

	private static void ensureSpace(ImportPlan plan, Path backupRoot, Path staging)
		throws IOException, WorldBuilderDiscoveryException {
		long backupBytes = 0L;
		long stagedBytes = 0L;
		for (Action action : plan.actions) {
			if (action.existedBefore) {
				backupBytes = safeAdd(backupBytes, Files.size(
					destination(plan.targetRoot, action.relativePath)));
			}
			stagedBytes = safeAdd(stagedBytes, Files.size(action.exportedPath));
		}
		FileStore workspaceStore = Files.getFileStore(plan.workspace);
		FileStore targetStore = Files.getFileStore(plan.targetRoot);
		long reserve = 1_048_576L;
		if (workspaceStore.getUsableSpace() < safeAdd(backupBytes, reserve)
			|| targetStore.getUsableSpace() < safeAdd(stagedBytes, reserve)) {
			throw new WorldBuilderDiscoveryException(
				"Insufficient free space for verified import staging and backups.");
		}
		Path backupParent = backupRoot.getParent();
		Path stageParent = staging.getParent();
		if (backupParent == null || stageParent == null
			|| !Files.isWritable(plan.workspace) || !Files.isWritable(plan.targetRoot)) {
			throw new WorldBuilderDiscoveryException(
				"Workspace or target is not writable for a recoverable import.");
		}
	}

	private static long safeAdd(long first, long second) throws WorldBuilderDiscoveryException {
		if (first < 0 || second < 0 || first > Long.MAX_VALUE - second) {
			throw new WorldBuilderDiscoveryException("Import size calculation overflowed.");
		}
		return first + second;
	}

	private static Path destination(Path target, String relative)
		throws IOException, WorldBuilderDiscoveryException {
		Path path = target.resolve(relative).normalize();
		if (!path.startsWith(target)) {
			throw new WorldBuilderDiscoveryException("Import destination escapes the target.");
		}
		Path parent = path.getParent();
		if (parent == null || !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)
			|| Files.isSymbolicLink(parent) || !parent.toRealPath().startsWith(target)
			|| containsSymbolicLink(target, parent)) {
			throw new WorldBuilderDiscoveryException(
				"Import destination directory is missing or unsafe: " + relative);
		}
		if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)
			&& (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)
				|| !path.toRealPath().startsWith(target))) {
			throw new WorldBuilderDiscoveryException(
				"Import destination is unsafe: " + relative);
		}
		return path;
	}

	private static Path safeWorkspaceDirectory(Path workspace, String relative)
		throws IOException, WorldBuilderDiscoveryException {
		Path directory = workspace.resolve(relative).normalize();
		if (!directory.startsWith(workspace)) {
			throw new WorldBuilderDiscoveryException("Transaction directory escapes the workspace.");
		}
		if (Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
			if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)
				|| Files.isSymbolicLink(directory) || !directory.toRealPath().startsWith(workspace)
				|| containsSymbolicLink(workspace, directory)) {
				throw new WorldBuilderDiscoveryException("Transaction directory is unsafe: " + relative);
			}
			return directory.toRealPath();
		}
		return directory;
	}

	private static boolean containsSymbolicLink(Path root, Path descendant) {
		Path current = root;
		for (Path component : root.relativize(descendant)) {
			current = current.resolve(component);
			if (Files.isSymbolicLink(current)) {
				return true;
			}
		}
		return false;
	}

	private static void verifyCreatedTransactionDirectory(Path workspace, Path directory)
		throws IOException, WorldBuilderDiscoveryException {
		if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)
			|| Files.isSymbolicLink(directory) || !directory.toRealPath().startsWith(workspace)
			|| containsSymbolicLink(workspace, directory)) {
			throw new WorldBuilderDiscoveryException("Created transaction directory is unsafe.");
		}
	}

	private static void verifyCurrent(Path path, boolean expectedPresent, String expectedSha256)
		throws IOException, WorldBuilderDiscoveryException {
		boolean present = Files.exists(path, LinkOption.NOFOLLOW_LINKS);
		if (present != expectedPresent) {
			throw new WorldBuilderDiscoveryException(
				"Target changed during import: " + path.getFileName());
		}
		if (present && (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
			|| Files.isSymbolicLink(path)
			|| !expectedSha256.equals(WorldBuilderHashes.sha256(path)))) {
			throw new WorldBuilderDiscoveryException(
				"Target changed during import: " + path.getFileName());
		}
	}

	private static void verifySelectedConfig(Path target, WorldBuilderProjectSource project)
		throws IOException, WorldBuilderDiscoveryException {
		Path config = containedFile(target, target.resolve(project.selectedConfig),
			"selected target configuration");
		if (!project.selectedConfigSha256.equals(WorldBuilderHashes.sha256(config))) {
			throw new WorldBuilderDiscoveryException(
				"Target configuration changed during the transaction.");
		}
	}

	private static void verifyInstalled(Path path, String relative, String expectedSha256)
		throws IOException, WorldBuilderDiscoveryException {
		if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)
			|| !expectedSha256.equals(WorldBuilderHashes.sha256(path))) {
			throw new IOException("Installed file verification failed for " + relative);
		}
		if (relative.endsWith(".orsc")) {
			WorldBuilderDiscovery.validateTerrainArchive(path);
		} else if (relative.endsWith("MyWorldSceneryLocs.json")) {
			WorldBuilderJsonDocuments.validateSceneryLocs(path);
		} else if (relative.endsWith("MyWorldSceneryRemovals.json")) {
			WorldBuilderJsonDocuments.validateSceneryRemovals(path);
		} else if (relative.endsWith("MyWorldNpcLocs.json")) {
			WorldBuilderJsonDocuments.validateNpcLocs(path);
		} else if (relative.endsWith("MyWorldNpcRemovals.json")) {
			WorldBuilderJsonDocuments.validateNpcRemovals(path);
		} else {
			throw new WorldBuilderDiscoveryException(
				"Import plan contains an unsupported destination: " + relative);
		}
	}

	private static void verifyInstalledPlan(ImportPlan plan)
		throws IOException, WorldBuilderDiscoveryException {
		for (Action action : plan.actions) {
			verifyInstalled(destination(plan.targetRoot, action.relativePath),
				action.relativePath, action.installedSha256);
		}
		Path serverTerrain = plan.targetRoot.resolve(
			"server/conf/server/data/Custom_Landscape.orsc");
		Path clientTerrain = plan.targetRoot.resolve(
			"Client_Base/Cache/video/Custom_Landscape.orsc");
		if (!WorldBuilderHashes.sha256(serverTerrain).equals(
			WorldBuilderHashes.sha256(clientTerrain))) {
			throw new IOException("Installed server and client terrain archives differ.");
		}
	}

	private static void restoreBeforeState(ImportPlan plan,
		WorldBuilderImportReceipt pending, Path backupRoot)
		throws IOException, WorldBuilderDiscoveryException {
		for (Action action : plan.actions) {
			Path target = destination(plan.targetRoot, action.relativePath);
			if (!action.existedBefore) {
				Files.deleteIfExists(target);
				continue;
			}
			Path backup = backupRoot.resolve("before").resolve(action.relativePath).normalize();
			if (!backup.startsWith(backupRoot)
				|| !Files.isRegularFile(backup, LinkOption.NOFOLLOW_LINKS)
				|| Files.isSymbolicLink(backup)
				|| !action.beforeSha256.equals(WorldBuilderHashes.sha256(backup))) {
				throw new IOException("Import backup is missing or invalid: " + action.relativePath);
			}
			Path restore = target.resolveSibling("." + target.getFileName()
				+ ".restore-" + UUID.randomUUID());
			Files.copy(backup, restore);
			if (!action.beforeSha256.equals(WorldBuilderHashes.sha256(restore))) {
				Files.deleteIfExists(restore);
				throw new IOException("Restoration staging failed: " + action.relativePath);
			}
			moveReplacing(restore, target);
		}
		for (Action action : plan.actions) {
			verifyCurrent(destination(plan.targetRoot, action.relativePath),
				action.existedBefore, action.beforeSha256);
		}
		Path serverTerrain = plan.targetRoot.resolve(
			"server/conf/server/data/Custom_Landscape.orsc");
		Path clientTerrain = plan.targetRoot.resolve(
			"Client_Base/Cache/video/Custom_Landscape.orsc");
		if (!WorldBuilderHashes.sha256(serverTerrain).equals(
			WorldBuilderHashes.sha256(clientTerrain))) {
			throw new IOException("Restored server and client terrain archives differ.");
		}
	}

	private static void moveReplacing(Path source, Path destination) throws IOException {
		try {
			Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING,
				StandardCopyOption.ATOMIC_MOVE);
		} catch (AtomicMoveNotSupportedException unsupported) {
			Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	private static void failIfInjected(int replacements) throws IOException {
		String configured = System.getProperty("worldbuilder.import.failAfterReplacements", "");
		if (configured.isEmpty()) {
			return;
		}
		try {
			if (Integer.parseInt(configured) == replacements) {
				throw new IOException("Injected import failure after replacement " + replacements + ".");
			}
		} catch (NumberFormatException invalid) {
			throw new IOException("Invalid import failure-injection setting.");
		}
	}

	private static void deleteTree(Path root) {
		if (root == null || !Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
			return;
		}
		try {
			Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
				@Override
				public FileVisitResult visitFile(Path file, BasicFileAttributes attributes)
					throws IOException {
					Files.deleteIfExists(file);
					return FileVisitResult.CONTINUE;
				}

				@Override
				public FileVisitResult postVisitDirectory(Path directory, IOException failure)
					throws IOException {
					if (failure != null) {
						throw failure;
					}
					Files.deleteIfExists(directory);
					return FileVisitResult.CONTINUE;
				}
			});
		} catch (IOException ignored) {
		}
	}

	private static WorldBuilderDiscoveryResult.SourceFile sourceFile(
		WorldBuilderDiscoveryResult discovered, String logicalName)
		throws WorldBuilderDiscoveryException {
		for (WorldBuilderDiscoveryResult.SourceFile file : discovered.files) {
			if (logicalName.equals(file.logicalName)) {
				return file;
			}
		}
		throw new WorldBuilderDiscoveryException(
			"Discovered target is missing source state for " + logicalName + ".");
	}

	private static FileLock tryLock(FileChannel channel, String message)
		throws IOException, WorldBuilderDiscoveryException {
		return tryLock(channel, false, message);
	}

	private static FileLock tryLock(FileChannel channel, boolean shared, String message)
		throws IOException, WorldBuilderDiscoveryException {
		FileLock lock;
		try {
			lock = channel.tryLock(0L, Long.MAX_VALUE, shared);
		} catch (OverlappingFileLockException busy) {
			lock = null;
		}
		if (lock == null) {
			throw new WorldBuilderDiscoveryException(message);
		}
		return lock;
	}

	private static Path canonicalDirectory(Path requested, String label)
		throws IOException, WorldBuilderDiscoveryException {
		if (requested == null) {
			throw new WorldBuilderDiscoveryException(label + " is required.");
		}
		Path normalized = requested.toAbsolutePath().normalize();
		if (!Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS)
			|| Files.isSymbolicLink(normalized)) {
			throw new WorldBuilderDiscoveryException(label + " is missing or unsafe: " + normalized);
		}
		return normalized.toRealPath();
	}

	private static Path containedFile(Path root, Path requested, String label)
		throws IOException, WorldBuilderDiscoveryException {
		Path normalized = requested.toAbsolutePath().normalize();
		if (!normalized.startsWith(root) || !Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS)
			|| Files.isSymbolicLink(normalized) || !normalized.toRealPath().startsWith(root)) {
			throw new WorldBuilderDiscoveryException(label + " is missing or unsafe.");
		}
		return normalized.toRealPath();
	}

	private static String escape(String value) {
		return value.replace("\\", "\\\\").replace("\"", "\\\"")
			.replace("\n", "\\n").replace("\r", "\\r");
	}

	private static final class Destination {
		final String exportLogical;
		final String sourceLogical;
		final String relativePath;

		Destination(String exportLogical, String sourceLogical, String relativePath) {
			this.exportLogical = exportLogical;
			this.sourceLogical = sourceLogical;
			this.relativePath = relativePath;
		}
	}

	private static final class Operation {
		final ImportPlan plan;
		final ImportResult result;

		Operation(ImportPlan plan, ImportResult result) {
			this.plan = plan;
			this.result = result;
		}
	}

	private static final class RollbackOperation {
		final RollbackPlan plan;
		final RollbackResult result;

		RollbackOperation(RollbackPlan plan, RollbackResult result) {
			this.plan = plan;
			this.result = result;
		}
	}

	static final class Action {
		final String operation;
		final String relativePath;
		final boolean existedBefore;
		final String beforeSha256;
		final String installedSha256;
		final Path exportedPath;

		Action(String operation, String relativePath, boolean existedBefore,
			String beforeSha256, String installedSha256, Path exportedPath) {
			this.operation = operation;
			this.relativePath = relativePath;
			this.existedBefore = existedBefore;
			this.beforeSha256 = beforeSha256;
			this.installedSha256 = installedSha256;
			this.exportedPath = exportedPath;
		}
	}

	static final class RollbackAction {
		final String relativePath;
		final String operation;
		final boolean currentPresent;
		final String currentSha256;
		final boolean restoredPresent;
		final String restoredSha256;
		final Path restoreSource;

		RollbackAction(String relativePath, String operation, boolean currentPresent,
			String currentSha256, boolean restoredPresent, String restoredSha256,
			Path restoreSource) {
			this.relativePath = relativePath;
			this.operation = operation;
			this.currentPresent = currentPresent;
			this.currentSha256 = currentSha256;
			this.restoredPresent = restoredPresent;
			this.restoredSha256 = restoredSha256;
			this.restoreSource = restoreSource;
		}
	}

	public static final class ImportPlan {
		public final Path workspace;
		public final Path targetRoot;
		public final Path exportDirectory;
		public final String selectedConfig;
		public final String sourceFingerprint;
		public final String exportManifestSha256;
		public final String builderVersion;
		public final String sourceCommit;
		final List<Action> actions;

		ImportPlan(Path workspace, Path targetRoot, Path exportDirectory,
			String selectedConfig, String sourceFingerprint, String exportManifestSha256,
			String builderVersion, String sourceCommit, List<Action> actions) {
			this.workspace = workspace;
			this.targetRoot = targetRoot;
			this.exportDirectory = exportDirectory;
			this.selectedConfig = selectedConfig;
			this.sourceFingerprint = sourceFingerprint;
			this.exportManifestSha256 = exportManifestSha256;
			this.builderVersion = builderVersion;
			this.sourceCommit = sourceCommit;
			this.actions = java.util.Collections.unmodifiableList(
				new ArrayList<Action>(actions));
		}

		public String toJson() {
			StringBuilder json = new StringBuilder(2048);
			json.append("{\n  \"status\": \"ready\",\n  \"dryRun\": true,\n")
				.append("  \"targetRoot\": \"").append(escape(targetRoot.toString())).append("\",\n")
				.append("  \"selectedConfig\": \"").append(escape(selectedConfig)).append("\",\n")
				.append("  \"exportDirectory\": \"").append(escape(exportDirectory.toString())).append("\",\n")
				.append("  \"builderVersion\": \"").append(escape(builderVersion)).append("\",\n")
				.append("  \"sourceCommit\": \"").append(sourceCommit).append("\",\n")
				.append("  \"sourceFingerprintSha256\": \"").append(sourceFingerprint).append("\",\n")
				.append("  \"exportManifestSha256\": \"").append(exportManifestSha256).append("\",\n")
				.append("  \"backupDestination\": \"")
				.append(escape(workspace.resolve("backups/<transaction-id>").toString())).append("\",\n")
				.append("  \"configurationChanges\": [],\n  \"actions\": [\n");
			for (int index = 0; index < actions.size(); index++) {
				Action action = actions.get(index);
				json.append("    {\"operation\": \"").append(action.operation)
					.append("\", \"relativePath\": \"").append(action.relativePath)
					.append("\", \"beforeSha256\": \"").append(action.beforeSha256)
					.append("\", \"installedSha256\": \"").append(action.installedSha256)
					.append("\"}").append(index + 1 < actions.size() ? "," : "").append('\n');
			}
			json.append("  ]\n}\n");
			return json.toString();
		}
	}

	public static final class ImportResult {
		public final String transactionId;
		public final Path receiptPath;
		public final Path backupDirectory;
		public final int changedFileCount;

		ImportResult(String transactionId, Path receiptPath, Path backupDirectory,
			int changedFileCount) {
			this.transactionId = transactionId;
			this.receiptPath = receiptPath;
			this.backupDirectory = backupDirectory;
			this.changedFileCount = changedFileCount;
		}

		public String toJson() {
			return "{\n"
				+ "  \"status\": \"imported\",\n"
				+ "  \"transactionId\": \"" + transactionId + "\",\n"
				+ "  \"changedFileCount\": " + changedFileCount + ",\n"
				+ "  \"receiptPath\": \"" + escape(receiptPath.toString()) + "\",\n"
				+ "  \"backupDirectory\": \"" + escape(backupDirectory.toString()) + "\"\n"
				+ "}\n";
		}
	}

	public static final class RollbackPlan {
		public final Path workspace;
		public final Path targetRoot;
		final WorldBuilderImportReceipt importedReceipt;
		final List<RollbackAction> actions;

		RollbackPlan(Path workspace, Path targetRoot,
			WorldBuilderImportReceipt importedReceipt, List<RollbackAction> actions) {
			this.workspace = workspace;
			this.targetRoot = targetRoot;
			this.importedReceipt = importedReceipt;
			this.actions = java.util.Collections.unmodifiableList(
				new ArrayList<RollbackAction>(actions));
		}

		public String toJson() {
			StringBuilder json = new StringBuilder(1536);
			json.append("{\n  \"status\": \"ready\",\n  \"dryRun\": true,\n")
				.append("  \"targetRoot\": \"").append(escape(targetRoot.toString())).append("\",\n")
				.append("  \"revertsTransactionId\": \"")
				.append(importedReceipt.transactionId).append("\",\n")
				.append("  \"safeguardDestination\": \"")
				.append(escape(workspace.resolve("backups/<rollback-transaction-id>").toString()))
				.append("\",\n  \"actions\": [\n");
			for (int index = 0; index < actions.size(); index++) {
				RollbackAction action = actions.get(index);
				json.append("    {\"operation\": \"").append(action.operation)
					.append("\", \"relativePath\": \"").append(action.relativePath)
					.append("\", \"currentSha256\": \"").append(action.currentSha256)
					.append("\", \"restoredSha256\": \"").append(action.restoredSha256)
					.append("\"}").append(index + 1 < actions.size() ? "," : "").append('\n');
			}
			json.append("  ]\n}\n");
			return json.toString();
		}
	}

	public static final class RollbackResult {
		public final String transactionId;
		public final Path receiptPath;
		public final Path safeguardDirectory;
		public final String revertedTransactionId;
		public final int changedFileCount;

		RollbackResult(String transactionId, Path receiptPath, Path safeguardDirectory,
			String revertedTransactionId, int changedFileCount) {
			this.transactionId = transactionId;
			this.receiptPath = receiptPath;
			this.safeguardDirectory = safeguardDirectory;
			this.revertedTransactionId = revertedTransactionId;
			this.changedFileCount = changedFileCount;
		}

		public String toJson() {
			return "{\n"
				+ "  \"status\": \"rolled-back\",\n"
				+ "  \"transactionId\": \"" + transactionId + "\",\n"
				+ "  \"revertedTransactionId\": \"" + revertedTransactionId + "\",\n"
				+ "  \"changedFileCount\": " + changedFileCount + ",\n"
				+ "  \"receiptPath\": \"" + escape(receiptPath.toString()) + "\",\n"
				+ "  \"safeguardDirectory\": \""
				+ escape(safeguardDirectory.toString()) + "\"\n"
				+ "}\n";
		}
	}
}
