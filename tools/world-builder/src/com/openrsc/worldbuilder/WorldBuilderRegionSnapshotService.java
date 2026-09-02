package com.openrsc.worldbuilder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/** Project-local copy/cut/paste foundation for portable region snapshots. */
final class WorldBuilderRegionSnapshotService {
	private static final String OPERATION = "region-snapshot";
	private static final String PACKAGE = "working/layered-world/package";
	private static final String LIBRARY = "snapshot-library/v1";
	private static final String ACTIVE_CLIPBOARD = "snapshot-library/active-v1.json";
	private static final String ACTIVE_CLIPBOARD_TYPE =
		"world-builder-region-active-clipboard";
	private static final String BUNDLE_EXTENSION = ".wbr";
	private static final String TRANSACTION = "working/layered-world/.region-transaction-v1.json";
	private static final String JOURNAL_TEMP_PREFIX = ".region-transaction-v1.json.new-";
	private static final String PASTE_UNDO_ROOT = "region-history/v1";
	private static final String PASTE_UNDO_POINTER = PASTE_UNDO_ROOT + "/last-paste-undo.json";
	private static final String PASTE_UNDO_STAGE_PREFIX = ".paste-undo-stage-";
	private static final String PASTE_UNDO_ENTRY_PREFIX = "paste-undo-";
	private static final long MAX_BUNDLE_BYTES = 32L * 1024L * 1024L;
	private static final long MAX_ENTRY_BYTES = 16L * 1024L * 1024L;
	private static final long MAX_REPRESENTED_FOOTPRINT_TILES = 1_000_000L;
	private static final int MAX_SPATIAL_INDEX_ENTRIES = 1_000_000;
	private static final int MAX_LIBRARY_ENTRIES = 1024;
	private static final int MAX_LIBRARY_DIRECTORY_ENTRIES = MAX_LIBRARY_ENTRIES + 1;
	private static final long MAX_LIBRARY_LIST_BYTES = 512L * 1024L * 1024L;
	private static final int MAX_RECOVERY_DIRECTORY_ENTRIES =
		WorldBuilderContractLimits.MAX_INVENTORY_ENTRIES;
	private static final long MAX_RECOVERY_TREE_BYTES = 512L * 1024L * 1024L;
	private static final long ZIP_TIME = 315532800000L;
	private final Observer observer;

	interface Observer {
		void observe(String milestone, Path project) throws Exception;
	}

	private static final Observer NO_OP_OBSERVER = new Observer() {
		@Override public void observe(String milestone, Path project) {
			// Production region operations do not inject failures.
		}
	};

	WorldBuilderRegionSnapshotService() {
		this(NO_OP_OBSERVER);
	}

	WorldBuilderRegionSnapshotService(Observer observer) {
		this.observer = observer == null ? NO_OP_OBSERVER : observer;
	}

	static void recoverProject(Path requestedProject)
		throws IOException, WorldBuilderContractException {
		Path project = requestedProject.toAbsolutePath().normalize();
		Path recoveryRoot = project.resolve("working/layered-world");
		if (!Files.isDirectory(recoveryRoot, LinkOption.NOFOLLOW_LINKS)
			|| !hasRegionRecoveryArtifact(recoveryRoot)) return;
		try (WorldBuilderAdaptiveProjectLock ignored =
			WorldBuilderAdaptiveProjectLock.acquire(project, "region-recovery")) {
			recoverRegionTransaction(project);
		}
	}

	static void recoverProjects(Path projects)
		throws IOException, WorldBuilderContractException {
		int count = 0;
		try (DirectoryStream<Path> entries = Files.newDirectoryStream(projects)) {
			for (Path project : entries) {
				if (++count > WorldBuilderContractLimits.MAX_PROJECTS + 1) throw problem(
					WorldBuilderErrorCodes.INVENTORY_LIMIT_EXCEEDED, "projects",
					"Adaptive project directory scan exceeds its bound.",
					"Remove unexpected project-directory entries after review.");
				if (!Files.isDirectory(project, LinkOption.NOFOLLOW_LINKS)
					|| Files.isSymbolicLink(project)) continue;
				Path parent = project.resolve("working/layered-world");
				if (Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)
					&& hasRegionRecoveryArtifact(parent)) {
					recoverProject(project);
				}
			}
		}
	}

	String copy(Path project, Path selectionPath, String name)
		throws IOException, WorldBuilderContractException {
		Path root = project.toAbsolutePath().normalize();
		try (WorldBuilderAdaptiveProjectLock ignored =
			WorldBuilderAdaptiveProjectLock.acquire(root, "region-copy")) {
			return copyUnderProjectLock(root, readSelection(selectionPath), name);
		}
	}

	/** Used only by the running Editor supervisor which already owns the project lock. */
	String copyUnderProjectLock(Path project,
		WorldBuilderRegionContracts.Selection selection, String name)
		throws IOException, WorldBuilderContractException {
		Path root = project.toAbsolutePath().normalize();
		recoverRegionTransaction(root);
		WorldBuilderAdaptiveProjectLifecycle.VerifiedProject verified =
			WorldBuilderAdaptiveProjectLifecycle.verifyProjectDirectory(root, false);
		Capture capture = capture(verified, selection, name);
		LibraryRecord library = publishToLibrary(root, capture.snapshot);
		Map<String,Object> result = baseResult("copy", capture.snapshot.id, library);
		result.put("name", name);
		result.put("workingSha256", verified.working.fingerprintSha256);
		result.put("tileCount", Long.valueOf(capture.snapshot.tileCount));
		result.put("placementCount", Long.valueOf(capture.snapshot.placementCount));
		result.put("footprintBoundaryReports",
			capture.snapshot.root.get("footprintBoundaryReports"));
		return WorldBuilderJsonDocuments.pretty(result);
	}

	String cutPreview(Path project, Path selectionPath, String name)
		throws IOException, WorldBuilderContractException {
		Path root = project.toAbsolutePath().normalize();
		try (WorldBuilderAdaptiveProjectLock ignored =
			WorldBuilderAdaptiveProjectLock.acquire(root, "region-cut-preview")) {
			return cutPreviewUnderProjectLock(
				root, readSelection(selectionPath), name);
		}
	}

	/** Used only by the running Editor supervisor which already owns the project lock. */
	String cutPreviewUnderProjectLock(Path project,
		WorldBuilderRegionContracts.Selection selection, String name)
		throws IOException, WorldBuilderContractException {
		Path root = project.toAbsolutePath().normalize();
		recoverRegionTransaction(root);
		WorldBuilderAdaptiveProjectLifecycle.VerifiedProject verified =
			WorldBuilderAdaptiveProjectLifecycle.verifyProjectDirectory(root, true);
		Capture capture = capture(verified, selection, name);
		LibraryRecord library = publishToLibrary(root, capture.snapshot);
		PreparedMutation prepared = prepareCut(verified, capture.snapshot);
		try {
			Map<String,Object> result = baseResult("cut-preview",
				capture.snapshot.id, library);
			result.put("name", name);
			result.put("tileCount", Long.valueOf(capture.snapshot.tileCount));
			result.put("placementCount", Long.valueOf(capture.snapshot.placementCount));
			result.put("footprintBoundaryReports",
				capture.snapshot.root.get("footprintBoundaryReports"));
			result.put("operationPlan", prepared.plan);
			return WorldBuilderJsonDocuments.pretty(result);
		} finally {
			prepared.discard();
		}
	}

	String applyCut(Path project, String snapshotId, String expectedPlan,
		String confirmation) throws IOException, WorldBuilderContractException {
		return apply(project, snapshotId, null, "cut", expectedPlan, confirmation);
	}

	/** Used only by the running Editor supervisor which already owns the project lock. */
	String applyCutUnderProjectLock(Path project, String snapshotId,
		String expectedPlan, String confirmation)
		throws IOException, WorldBuilderContractException {
		return applyUnderProjectLock(project.toAbsolutePath().normalize(), snapshotId,
			null, "cut", expectedPlan, confirmation);
	}

	String importBundle(Path project, Path requestedBundle)
		throws IOException, WorldBuilderContractException {
		Path root = project.toAbsolutePath().normalize();
		try (WorldBuilderAdaptiveProjectLock ignored =
			WorldBuilderAdaptiveProjectLock.acquire(root, "region-import")) {
			return importBundleUnderProjectLock(root, requestedBundle);
		}
	}

	/** Used only by the running Editor supervisor which already owns the project lock. */
	String importBundleUnderProjectLock(Path project, Path requestedBundle)
		throws IOException, WorldBuilderContractException {
		Path root = project.toAbsolutePath().normalize();
		recoverRegionTransaction(root);
		WorldBuilderAdaptiveProjectLifecycle.VerifiedProject verified =
			WorldBuilderAdaptiveProjectLifecycle.verifyProjectDirectory(root, true);
		Bundle bundle = readBundle(requestedBundle);
		LibraryRecord library = publishBundle(root, bundle);
		Map<String,Object> compatibility = compatibility(verified, bundle.snapshot);
		Map<String,Object> result = baseResult("import", bundle.snapshot.id, library);
		result.put("compatibilityReport", compatibility);
		result.put("worldModified", Boolean.FALSE);
		return WorldBuilderJsonDocuments.pretty(result);
	}

	String exportBundle(Path project, String snapshotId, Path requestedOutput)
		throws IOException, WorldBuilderContractException {
		Path root = project.toAbsolutePath().normalize();
		try (WorldBuilderAdaptiveProjectLock ignored =
			WorldBuilderAdaptiveProjectLock.acquire(root, "region-export")) {
			return exportBundleUnderProjectLock(root, snapshotId, requestedOutput);
		}
	}

	/** Used only by the running Editor supervisor which already owns the project lock. */
	String exportBundleUnderProjectLock(Path project, String snapshotId,
		Path requestedOutput) throws IOException, WorldBuilderContractException {
		Path root = project.toAbsolutePath().normalize();
		recoverRegionTransaction(root);
		WorldBuilderAdaptiveProjectLifecycle.verifyProjectDirectory(root, true);
		Bundle bundle = loadLibrary(root, snapshotId);
		Path output = safeNewOutput(requestedOutput, root);
		Path stage = output.resolveSibling("." + output.getFileName().toString()
			+ ".staging-" + UUID.randomUUID().toString());
		try {
			Files.write(stage, bundle.bytes, StandardOpenOption.CREATE_NEW,
				StandardOpenOption.WRITE);
			WorldBuilderAdaptiveDurability.forceFile(stage);
			WorldBuilderAdaptiveAtomicFiles.moveNew(stage, output,
				"region-export", output.getFileName().toString());
			WorldBuilderAdaptiveDurability.forceDirectory(output.getParent());
		} finally {
			Files.deleteIfExists(stage);
		}
		Map<String,Object> result = new LinkedHashMap<String,Object>();
		result.put("operation", "export");
		result.put("snapshotId", snapshotId);
		result.put("outputPath", output.toString());
		result.put("bundleSha256", WorldBuilderHashes.sha256(bundle.bytes));
		return WorldBuilderJsonDocuments.pretty(result);
	}

	String pastePreview(Path project, String snapshotId, int level, int x, int y)
		throws IOException, WorldBuilderContractException {
		Path root = project.toAbsolutePath().normalize();
		try (WorldBuilderAdaptiveProjectLock ignored =
			WorldBuilderAdaptiveProjectLock.acquire(root, "region-paste-preview")) {
			return pastePreviewUnderProjectLock(root, snapshotId, level, x, y);
		}
	}

	/** Used only by the running Editor supervisor which already owns the project lock. */
	String pastePreviewUnderProjectLock(Path project, String snapshotId,
		int level, int x, int y) throws IOException, WorldBuilderContractException {
		Path root = project.toAbsolutePath().normalize();
		recoverRegionTransaction(root);
		WorldBuilderAdaptiveProjectLifecycle.VerifiedProject verified =
			WorldBuilderAdaptiveProjectLifecycle.verifyProjectDirectory(root, true);
		Bundle bundle = loadLibrary(root, snapshotId);
		Map<String,Object> report = compatibility(verified, bundle.snapshot);
		PreparedMutation prepared = preparePaste(verified, bundle.snapshot,
			level, x, y, report);
		try {
			Map<String,Object> result = new LinkedHashMap<String,Object>();
			result.put("operation", "paste-preview");
			result.put("snapshotId", snapshotId);
			result.put("name", text(bundle.snapshot.root, "name"));
			result.put("tileCount", Long.valueOf(bundle.snapshot.tileCount));
			result.put("placementCount", Long.valueOf(bundle.snapshot.placementCount));
			result.put("previewFootprint", previewFootprint(bundle.snapshot, level, x, y));
			result.put("compatibilityReport", report);
			result.put("operationPlan", prepared.plan);
			result.put("worldModified", Boolean.FALSE);
			return WorldBuilderJsonDocuments.pretty(result);
		} finally {
			prepared.discard();
		}
	}

	String applyPaste(Path project, String snapshotId, int level, int x, int y,
		String expectedPlan, String confirmation)
		throws IOException, WorldBuilderContractException {
		return apply(project, snapshotId, new Destination(level, x, y), "paste",
			expectedPlan, confirmation);
	}

	/** Used only by the running Editor supervisor which already owns the project lock. */
	String applyPasteUnderProjectLock(Path project, String snapshotId,
		int level, int x, int y, String expectedPlan, String confirmation)
		throws IOException, WorldBuilderContractException {
		return applyUnderProjectLock(project.toAbsolutePath().normalize(), snapshotId,
			new Destination(level, x, y), "paste", expectedPlan, confirmation);
	}

	String undoLastPaste(Path requestedProject)
		throws IOException, WorldBuilderContractException {
		Path project = requestedProject.toAbsolutePath().normalize();
		try (WorldBuilderAdaptiveProjectLock ignored =
			WorldBuilderAdaptiveProjectLock.acquire(project, "region-paste-undo")) {
			return undoLastPasteUnderProjectLock(project);
		}
	}

	/** Used only by the running Editor supervisor which already owns the project lock. */
	String undoLastPasteUnderProjectLock(Path requestedProject)
		throws IOException, WorldBuilderContractException {
		Path project = requestedProject.toAbsolutePath().normalize();
		recoverRegionTransaction(project);
		WorldBuilderAdaptiveProjectLifecycle.VerifiedProject verified =
			WorldBuilderAdaptiveProjectLifecycle.verifyProjectDirectory(project, true);
		PasteUndoRecord undo = readPasteUndo(project);
		String liveTree = treeFingerprint(project.resolve(PACKAGE));
		if (!undo.afterTreeSha256.equals(liveTree)
			|| !undo.afterWorkingSha256.equals(verified.working.fingerprintSha256)) {
			throw problem(WorldBuilderErrorCodes.TARGET_DRIFT, PASTE_UNDO_POINTER,
				"The project changed after the recorded Paste; exact Undo is no longer safe.",
				"Keep the later edits and create a new snapshot instead of forcing Undo.");
		}
		if (!treeEquals(undo.packageRoot, undo.beforeTreeSha256)) {
			throw problem(WorldBuilderErrorCodes.RECOVERY_REQUIRED, PASTE_UNDO_POINTER,
				"The retained pre-Paste package no longer matches its exact receipt.",
				"Preserve region-history and restore the verified project backup; do not force Undo.");
		}
		Path stage = project.resolve("working/layered-world/.region-stage-" + UUID.randomUUID());
		copyTree(undo.packageRoot, stage);
		PreparedMutation prepared = new PreparedMutation(stage,
			new LinkedHashMap<String,Object>(), undo.beforeWorkingSha256);
		boolean published = false;
		boolean saved = false;
		RegionTransaction transaction = null;
		try {
			transaction = RegionTransaction.prepare(project, prepared,
				verified.working.fingerprintSha256);
			publishWorkingPackage(project, prepared.stage, transaction);
			published = true;
			WorldBuilderAdaptiveProjectLifecycle.ProjectResult result =
				new WorldBuilderAdaptiveProjectLifecycle().saveAfterRegionPublication(project);
			saved = true;
			transaction.phase("manifest-saved");
			completeWorkingPublication(project, transaction);
			consumePasteUndo(project, undo);
			WorldBuilderAdaptiveProjectLifecycle.VerifiedProject restored =
				WorldBuilderAdaptiveProjectLifecycle.verifyProjectDirectory(project, true);
			Map<String,Object> output = new LinkedHashMap<String,Object>();
			output.put("operation", "undo");
			output.put("snapshotId", undo.snapshotId);
			output.put("planFingerprintSha256", undo.planFingerprintSha256);
			output.put("workingSha256", result.workingFingerprintSha256);
			output.put("packageManifestSha256", restored.working.manifestSha256);
			output.put("packageInventorySha256", restored.working.nativeInventorySha256);
			output.put("worldModified", Boolean.TRUE);
			return WorldBuilderJsonDocuments.pretty(output);
		} catch (Exception failure) {
			if (published && !saved) rollbackWorkingPublication(project, transaction, failure);
			if (failure instanceof IOException) throw (IOException)failure;
			if (failure instanceof WorldBuilderContractException) {
				throw (WorldBuilderContractException)failure;
			}
			throw new IOException("Region Paste Undo failed: " + failure.getMessage(), failure);
		} finally {
			if (!published && transaction == null) prepared.discard();
		}
	}

	private String apply(Path requestedProject, String snapshotId,
		Destination destination, String operation, String expectedPlan,
		String confirmation) throws IOException, WorldBuilderContractException {
		Path project = requestedProject.toAbsolutePath().normalize();
		try (WorldBuilderAdaptiveProjectLock ignored =
			WorldBuilderAdaptiveProjectLock.acquire(project, "region-" + operation)) {
			return applyUnderProjectLock(project, snapshotId, destination, operation,
				expectedPlan, confirmation);
		}
	}

	private String applyUnderProjectLock(Path project, String snapshotId,
		Destination destination, String operation, String expectedPlan,
		String confirmation) throws IOException, WorldBuilderContractException {
			recoverRegionTransaction(project);
			WorldBuilderAdaptiveProjectLifecycle.VerifiedProject verified =
				WorldBuilderAdaptiveProjectLifecycle.verifyProjectDirectory(project, true);
			Bundle bundle = loadLibrary(project, snapshotId);
			PreparedMutation prepared;
			if ("cut".equals(operation)) {
				prepared = prepareCut(verified, bundle.snapshot);
			} else {
				Map<String,Object> report = compatibility(verified, bundle.snapshot);
				prepared = preparePaste(verified, bundle.snapshot, destination.level,
					destination.x, destination.y, report);
			}
			String planHash = WorldBuilderRegionContracts.string(prepared.plan,
				"planFingerprintSha256", "region-" + operation);
			boolean blocked = WorldBuilderRegionContracts.bool(prepared.plan,
				"blocked", "region-" + operation);
			boolean overwrite = WorldBuilderRegionContracts.bool(prepared.plan,
				"overwriteRequired", "region-" + operation);
			String required = (overwrite ? "OVERWRITE " : operation.toUpperCase(
				java.util.Locale.ROOT) + " ") + planHash;
			if (blocked) {
				prepared.discard();
				throw problem(WorldBuilderErrorCodes.CAPABILITY_MISMATCH, operation,
					"Region operation plan is blocked by compatibility or coverage issues.",
					"Review the complete compatibility and collision preview; do not force it.");
			}
			if (!planHash.equals(expectedPlan) || !required.equals(confirmation)) {
				prepared.discard();
				throw problem(WorldBuilderErrorCodes.TARGET_DRIFT, operation,
					"Region operation confirmation does not bind the current exact plan.",
					"Generate a fresh preview and enter its exact confirmation text.");
			}
			boolean published = false;
			boolean saved = false;
			RegionTransaction transaction = null;
			try {
				transaction = RegionTransaction.prepare(project,
					prepared, verified.working.fingerprintSha256);
				observer.observe("staged-package-durable", project);
				observer.observe("before-package-publication", project);
				publishWorkingPackage(project, prepared.stage, transaction);
				published = true;
				observer.observe("package-published", project);
				if ("paste".equals(operation)) preservePasteUndo(project, transaction,
					snapshotId, planHash, verified.working.fingerprintSha256,
					prepared.afterWorkingSha256);
				WorldBuilderAdaptiveProjectLifecycle.ProjectResult result =
					new WorldBuilderAdaptiveProjectLifecycle()
						.saveAfterRegionPublication(project);
				saved = true;
				transaction.phase("manifest-saved");
				observer.observe("project-manifest-saved", project);
				observer.observe("before-cleanup", project);
				completeWorkingPublication(project, transaction);
				observer.observe("cleanup-complete", project);
				Map<String,Object> output = new LinkedHashMap<String,Object>();
				output.put("operation", operation);
				output.put("snapshotId", snapshotId);
				output.put("planFingerprintSha256", planHash);
				output.put("workingSha256", result.workingFingerprintSha256);
				WorldBuilderAdaptiveProjectLifecycle.VerifiedProject publishedProject =
					WorldBuilderAdaptiveProjectLifecycle.verifyProjectDirectory(project, true);
				output.put("packageManifestSha256", publishedProject.working.manifestSha256);
				output.put("packageInventorySha256",
					publishedProject.working.nativeInventorySha256);
				output.put("worldModified", Boolean.TRUE);
				return WorldBuilderJsonDocuments.pretty(output);
			} catch (Exception failure) {
				if (published && !saved) rollbackWorkingPublication(project, transaction,
					failure);
				if (failure instanceof IOException) throw (IOException)failure;
				if (failure instanceof WorldBuilderContractException) {
					throw (WorldBuilderContractException)failure;
				}
				throw new IOException("Region publication failed: "
					+ failure.getMessage(), failure);
			} finally {
				if (!published && transaction == null) prepared.discard();
			}
	}

	/** Lists every verified content-addressed project-local snapshot deterministically. */
	String listLibraryUnderProjectLock(Path project)
		throws IOException, WorldBuilderContractException {
		Path root = project.toAbsolutePath().normalize();
		recoverRegionTransaction(root);
		WorldBuilderAdaptiveProjectLifecycle.verifyProjectDirectory(root, true);
		List<Object> records = new ArrayList<Object>();
		Set<String> snapshotIds = new HashSet<String>();
		Path requestedLibrary = root.resolve(LIBRARY).normalize();
		if (Files.exists(requestedLibrary, LinkOption.NOFOLLOW_LINKS)) {
			Path verifiedLibrary = library(root, false);
			List<Path> entries = new ArrayList<Path>();
			long representedBytes = 0L;
			try (DirectoryStream<Path> inventory = Files.newDirectoryStream(verifiedLibrary)) {
				for (Path entry : inventory) {
					if (entries.size() >= MAX_LIBRARY_DIRECTORY_ENTRIES) throw problem(
						WorldBuilderErrorCodes.INVENTORY_LIMIT_EXCEEDED, LIBRARY,
						"Snapshot library inventory exceeds its bound.",
						"Archive reviewed bundles outside the project before continuing.");
					if (Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)) {
						representedBytes += Files.size(entry);
						if (representedBytes > MAX_LIBRARY_LIST_BYTES) throw problem(
							WorldBuilderErrorCodes.INVENTORY_LIMIT_EXCEEDED, LIBRARY,
							"Snapshot library exceeds the 512 MiB interactive browsing bound.",
							"Archive reviewed bundles outside the project before browsing in-game.");
					}
					entries.add(entry);
				}
			}
			Collections.sort(entries, new Comparator<Path>() {
				@Override public int compare(Path left, Path right) {
					return left.getFileName().toString().compareTo(right.getFileName().toString());
				}
			});
			for (Path entry : entries) {
				String filename = entry.getFileName().toString();
				if (!filename.endsWith(BUNDLE_EXTENSION)
					|| filename.length() != 64 + BUNDLE_EXTENSION.length()) throw problem(
						WorldBuilderErrorCodes.UNSAFE_PATH, LIBRARY + "/" + filename,
						"Snapshot library contains a non-canonical entry.",
						"Keep only exact content-addressed .wbr bundles in the library.");
				String snapshotId = filename.substring(0, 64);
				Bundle bundle = loadLibrary(root, snapshotId);
				snapshotIds.add(bundle.snapshot.id);
				Map<String,Object> record = new LinkedHashMap<String,Object>();
				record.put("snapshotId", bundle.snapshot.id);
				record.put("name", text(bundle.snapshot.root, "name"));
				record.put("tileCount", Long.valueOf(bundle.snapshot.tileCount));
				record.put("placementCount", Long.valueOf(bundle.snapshot.placementCount));
				record.put("levelCount", Long.valueOf(list(bundle.snapshot.root, "levels").size()));
				record.put("bundleSha256", WorldBuilderHashes.sha256(bundle.bytes));
				records.add(record);
			}
		}
		Map<String,Object> result = new LinkedHashMap<String,Object>();
		result.put("operation", "library");
		result.put("snapshots", records);
		String activeSnapshotId = readActiveClipboard(root);
		if (!activeSnapshotId.isEmpty() && !snapshotIds.contains(activeSnapshotId)) {
			throw problem(WorldBuilderErrorCodes.SOURCE_CORRUPT, ACTIVE_CLIPBOARD,
				"Active Region clipboard points to a missing snapshot.",
				"Restore the referenced .wbr entry or copy/import another region.");
		}
		result.put("activeSnapshotId", activeSnapshotId);
		result.put("worldModified", Boolean.FALSE);
		return WorldBuilderJsonDocuments.pretty(result);
	}

	private static Map<String,Object> previewFootprint(
		WorldBuilderRegionContracts.Snapshot snapshot, int level, int x, int y)
		throws WorldBuilderContractException {
		Map<String,Object> result = new LinkedHashMap<String,Object>();
		List<Object> markers = new ArrayList<Object>();
		for (Object raw : list(snapshot.root, "polygon")) {
			Map<String,Object> relative = map(raw);
			Map<String,Object> marker = new LinkedHashMap<String,Object>();
			marker.put("marker", relative.get("marker"));
			marker.put("x", Long.valueOf(checkedAdd(x,
				integer(relative, "xOffset"), "preview x")));
			marker.put("y", Long.valueOf(checkedAdd(y,
				integer(relative, "yOffset"), "preview y")));
			markers.add(marker);
		}
		List<Object> levels = new ArrayList<Object>();
		for (Object raw : list(snapshot.root, "levels")) {
			levels.add(Long.valueOf(checkedAdd(level,
				integer(map(raw), "levelOffset"), "preview level")));
		}
		result.put("markers", markers);
		result.put("levels", levels);
		return result;
	}

	private WorldBuilderRegionContracts.Selection readSelection(Path requested)
		throws IOException, WorldBuilderContractException {
		Path path = safeExternalFile(requested, "selection JSON", 0L,
			WorldBuilderContractLimits.MAX_JSON_BYTES);
		Map<String,Object> root;
		try {
			root = WorldBuilderJsonDocuments.readObject(path);
		} catch (WorldBuilderDiscoveryException malformed) {
			throw problem(WorldBuilderErrorCodes.MALFORMED_JSON, "selection",
				"Region selection JSON is malformed or unsafe.",
				"Use one strict region-selection-v1 document.", malformed);
		}
		return WorldBuilderRegionContracts.selection(root);
	}

	private Capture capture(
		WorldBuilderAdaptiveProjectLifecycle.VerifiedProject verified,
		WorldBuilderRegionContracts.Selection selection, String name)
		throws IOException, WorldBuilderContractException {
		if (name == null || name.isEmpty() || name.length() > 128) {
			throw problem(WorldBuilderErrorCodes.CONTRACT_VALUE_INVALID, "name",
				"Snapshot name must contain 1..128 characters.",
				"Choose one short creator-facing snapshot name.");
		}
		PackageState state = PackageState.read(verified.projectRoot, PACKAGE);
		requirePackageFootprintBudget(state.placements, "source package");
		if (!selection.worldSpace.equals(state.worldSpace)) {
			throw problem(WorldBuilderErrorCodes.MAP_MISMATCH, "selection",
				"Selection world space does not match the working package.",
				"Select content in the project's exact static world space.");
		}
		int anchorX = selection.markers.get(0).x;
		int anchorY = selection.markers.get(0).y;
		int anchorLevel = selection.levels.get(0).intValue();
		Map<String,Object> root = new LinkedHashMap<String,Object>();
		root.put("schemaVersion", Long.valueOf(
			WorldBuilderRegionContracts.SNAPSHOT_VERSION));
		root.put("manifestType", "world-builder-region-snapshot");
		root.put("snapshotId", WorldBuilderRegionContracts.ZERO_HASH);
		root.put("name", name);
		root.put("worldSpace", selection.worldSpace);
		root.put("anchor", anchor(anchorLevel, anchorX, anchorY));
		List<Object> polygon = new ArrayList<Object>();
		for (int index = 0; index < selection.markers.size(); index++) {
			WorldBuilderRegionContracts.Point point = selection.markers.get(index);
			Map<String,Object> marker = new LinkedHashMap<String,Object>();
			marker.put("marker", Long.valueOf(index + 1L));
			marker.put("xOffset", Long.valueOf((long)point.x - anchorX));
			marker.put("yOffset", Long.valueOf((long)point.y - anchorY));
			polygon.add(marker);
		}
		root.put("polygon", polygon);
		List<Object> levelRecords = new ArrayList<Object>();
		int selectedTiles = 0;
		for (Integer level : selection.levels) {
			if (!state.levels.contains(level)) throw problem(
				WorldBuilderErrorCodes.MAP_MISMATCH, "selection",
				"Selection references a level absent from the working package: " + level + ".",
				"Choose only existing supported levels.");
			Map<String,Object> levelRecord = new LinkedHashMap<String,Object>();
			levelRecord.put("levelOffset", Long.valueOf((long)level - anchorLevel));
			List<Object> tiles = new ArrayList<Object>();
			for (long x = selection.geometry.minimumX; x <= (long)selection.geometry.maximumX; x++) {
				for (long y = selection.geometry.minimumY; y <= (long)selection.geometry.maximumY; y++) {
					int tileX = (int)x, tileY = (int)y;
					if (!selection.geometry.owns(tileX, tileY)) continue;
					byte[] tile = state.tile(level.intValue(), tileX, tileY);
					if (tile == null) throw problem(WorldBuilderErrorCodes.MAP_MISMATCH,
						"selection", "Selected tile has no declared terrain coverage at "
							+ level + ":" + tileX + "," + tileY + ".",
						"Reduce the polygon to complete working terrain coverage.");
					tiles.add(tileRecord(tile, tileX - anchorX, tileY - anchorY));
					if (++selectedTiles > WorldBuilderRegionContracts.MAX_TILES) {
						throw problem(WorldBuilderErrorCodes.INVENTORY_LIMIT_EXCEEDED,
							"selection", "Region snapshot exceeds 65,536 selected tiles.",
							"Use a smaller polygon or fewer levels.");
					}
				}
			}
			if (tiles.isEmpty()) throw problem(WorldBuilderErrorCodes.CONTRACT_VALUE_INVALID,
				"selection", "Selection polygon owns no tile centers.",
				"Move the markers so the polygon encloses at least one tile.");
			levelRecord.put("tiles", tiles);
			levelRecords.add(levelRecord);
		}
		root.put("levels", levelRecords);

		Map<String,Object> placements = emptyPlacements();
		List<Object> reports = new ArrayList<Object>();
		Set<Dependency> dependencies = new TreeSet<Dependency>();
		dependencies.add(Dependency.catalog(verified.definitions.catalogId,
			fingerprint(verified.manifest, "definitionsSha256")));
		for (Integer level : selection.levels) {
			Map<String,Object> payload = state.placements.get(level);
			capturePlacements(payload, level.intValue(), anchorLevel, anchorX, anchorY,
				selection.geometry, placements, reports, dependencies,
				verified.definitions.catalogId);
		}
		for (String family : placementFamilies()) sortCanonical(list(placements, family));
		Collections.sort(reports, new Comparator<Object>() {
			@Override public int compare(Object left, Object right) {
				Map<String,Object> a = map(left), b = map(right);
				return (text(a, "family") + "\u0000" + text(a, "placementId"))
					.compareTo(text(b, "family") + "\u0000" + text(b, "placementId"));
			}
		});
		root.put("placements", placements);
		root.put("footprintBoundaryReports", reports);
		Map<String,Object> catalog = new LinkedHashMap<String,Object>();
		catalog.put("catalogId", verified.definitions.catalogId);
		catalog.put("sha256", fingerprint(verified.manifest, "definitionsSha256"));
		root.put("catalog", catalog);
		Map<String,Object> source = new LinkedHashMap<String,Object>();
		source.put("projectId", verified.projectId);
		source.put("packageSchemaId", "layered-world-package-v1");
		source.put("coordinateModel", state.coordinateModel);
		source.put("workingSha256", verified.working.fingerprintSha256);
		source.put("runtimeSha256", fingerprint(verified.manifest, "runtimeSha256"));
		root.put("sourceEvidence", source);
		List<Object> dependencyRecords = new ArrayList<Object>();
		for (Dependency dependency : dependencies) dependencyRecords.add(dependency.toJson());
		root.put("dependencies", dependencyRecords);
		root.put("snapshotFingerprintSha256", WorldBuilderRegionContracts.ZERO_HASH);
		WorldBuilderRegionContracts.bindDualFingerprint(root, "snapshotId",
			"snapshotFingerprintSha256");
		return new Capture(WorldBuilderRegionContracts.snapshot(root));
	}

	private static Map<String,Object> tileRecord(byte[] tile, int x, int y) {
		ByteBuffer input = ByteBuffer.wrap(tile);
		Map<String,Object> result = new LinkedHashMap<String,Object>();
		result.put("xOffset", Long.valueOf(x));
		result.put("yOffset", Long.valueOf(y));
		result.put("elevation", Long.valueOf(input.getShort() & 0xffff));
		result.put("groundTexture", Long.valueOf(input.get() & 0xff));
		result.put("groundOverlay", Long.valueOf(input.get() & 0xff));
		result.put("roofTexture", Long.valueOf(input.get() & 0xff));
		result.put("verticalWall", Long.valueOf(input.get() & 0xff));
		result.put("horizontalWall", Long.valueOf(input.get() & 0xff));
		result.put("diagonalWall", Long.valueOf(input.getInt()));
		result.put("canonicalVoid", Boolean.valueOf(
			Arrays.equals(tile, WorldBuilderCanonicalVoidTerrain.tile())));
		return result;
	}

	private static void capturePlacements(Map<String,Object> payload, int level,
		int anchorLevel, int anchorX, int anchorY,
		WorldBuilderRegionContracts.Geometry geometry, Map<String,Object> output,
		List<Object> reports, Set<Dependency> dependencies, String catalogId)
		throws WorldBuilderContractException {
		for (String family : placementFamilies()) {
			for (Object raw : list(payload, family)) {
				Map<String,Object> record = map(raw);
				Point owner = owner(family, record);
				if (!geometry.owns(owner.x, owner.y)) continue;
				Map<String,Object> relative = relativePlacement(family, record,
					level - anchorLevel, anchorX, anchorY);
				list(output, family).add(relative);
				String placementId = text(record, "placementId");
				boolean crossing = crossesBoundary(family, record, geometry);
				Map<String,Object> report = new LinkedHashMap<String,Object>();
				report.put("family", singularFamily(family));
				report.put("placementId", placementId);
				report.put("ownership", "boundaries".equals(family)
					? "boundary-origin" : "anchor-point");
				report.put("crossesBoundary", Boolean.valueOf(crossing));
				report.put("detail", footprintDetail(family, crossing));
				reports.add(report);
				int id = definitionId(family, record);
				dependencies.add(Dependency.definition(catalogId,
					singularFamily(family), id));
			}
		}
	}

	private PreparedMutation prepareCut(
		WorldBuilderAdaptiveProjectLifecycle.VerifiedProject verified,
		WorldBuilderRegionContracts.Snapshot snapshot)
		throws IOException, WorldBuilderContractException {
		Map<String,Object> source = map(snapshot.root.get("sourceEvidence"));
		if (!verified.projectId.equals(text(source, "projectId"))
			|| !verified.working.fingerprintSha256.equals(text(source, "workingSha256"))) {
			return blockedPlan(verified, snapshot, "cut", snapshot.anchorLevel,
				anchorX(snapshot), anchorY(snapshot), "source-drift",
				"Cut snapshot no longer binds the exact source working package.");
		}
		Path stage = stagePackage(verified.projectRoot);
		try {
			PackageState state = PackageState.read(verified.projectRoot,
				relativeStage(verified.projectRoot, stage));
			applySnapshotTiles(state, snapshot, snapshot.anchorLevel,
				anchorX(snapshot), anchorY(snapshot), true);
			removeOwnedPlacements(state, snapshot, snapshot.anchorLevel,
				anchorX(snapshot), anchorY(snapshot));
			String afterWorking = state.writeAndValidate(verified);
			Map<String,Object> plan = plan(verified, snapshot, "cut",
				snapshot.anchorLevel, anchorX(snapshot), anchorY(snapshot), stage,
				new ArrayList<Object>(), new ArrayList<Object>(), false, false);
			return new PreparedMutation(stage, plan, afterWorking);
		} catch (Exception failure) {
			deleteTree(stage);
			if (failure instanceof IOException) throw (IOException)failure;
			throw (WorldBuilderContractException)failure;
		}
	}

	private PreparedMutation preparePaste(
		WorldBuilderAdaptiveProjectLifecycle.VerifiedProject verified,
		WorldBuilderRegionContracts.Snapshot snapshot, int level, int x, int y,
		Map<String,Object> compatibility)
		throws IOException, WorldBuilderContractException {
		List<Object> collisions = new ArrayList<Object>();
		boolean compatible = WorldBuilderRegionContracts.bool(compatibility,
			"compatible", "region-paste");
		if (!compatible) {
			return blockedPlan(verified, snapshot, "paste", level, x, y,
				"incompatible-dependency", "Snapshot dependencies are incompatible.");
		}
		PackageState live = PackageState.read(verified.projectRoot, PACKAGE);
		requirePackageFootprintBudget(live.placements, "destination package");
		requireFootprintBudget(map(snapshot.root.get("placements")), "incoming snapshot");
		WorldBuilderRegionContracts.Geometry destination = translatedGeometry(snapshot, x, y);
		Set<Integer> destinationLevels = new HashSet<Integer>();
		boolean blocked = false;
		boolean overwrite = false;
		for (Object rawLevel : list(snapshot.root, "levels")) {
			Map<String,Object> levelRecord = map(rawLevel);
			int targetLevel = checkedAdd(level,
				integer(levelRecord, "levelOffset"), "paste level");
			destinationLevels.add(Integer.valueOf(targetLevel));
			if (!live.levels.contains(Integer.valueOf(targetLevel))) {
				addCollision(collisions, "unavailable-level", targetLevel, x, y,
					"Destination level is absent from the working package.");
				blocked = true;
				continue;
			}
			for (Object rawTile : list(levelRecord, "tiles")) {
				Map<String,Object> tile = map(rawTile);
				int targetX = checkedAdd(x, integer(tile, "xOffset"), "paste x");
				int targetY = checkedAdd(y, integer(tile, "yOffset"), "paste y");
				byte[] existing = live.tile(targetLevel, targetX, targetY);
				if (existing == null) {
					addCollision(collisions, "unavailable-terrain", targetLevel,
						targetX, targetY, "Destination tile has no declared terrain sector.");
					blocked = true;
				} else if (!Arrays.equals(existing, WorldBuilderCanonicalVoidTerrain.tile())) {
					addCollision(collisions, "non-void-terrain", targetLevel,
						targetX, targetY, "Destination tile is not canonical structural void.");
					overwrite = true;
				}
			}
		}
		SpatialIndex preserved = new SpatialIndex();
		for (Integer targetLevel : destinationLevels) {
			Map<String,Object> payload = live.placements.get(targetLevel);
			if (payload == null) continue;
			for (String family : placementFamilies()) {
				for (Object raw : list(payload, family)) {
					Map<String,Object> record = map(raw);
					Point owner = owner(family, record);
					if (destination.owns(owner.x, owner.y)) {
						addCollision(collisions, "occupied-" + singularFamily(family),
							targetLevel.intValue(), owner.x, owner.y,
							"Destination selection owns an existing placement.");
						overwrite = true;
					} else if (("boundaries".equals(family) || "npcs".equals(family))
						&& footprintIntersects(family, record, destination)) {
						addCollision(collisions, "represented-" + singularFamily(family)
							+ "-crossing", targetLevel.intValue(), owner.x, owner.y,
							"Preserved " + singularFamily(family) + " "
								+ text(record, "placementId")
								+ " is anchored outside but represented inside the destination.");
						blocked = true;
					}
					if (!destination.owns(owner.x, owner.y)) {
						preserved.add(targetLevel.intValue(), family, record);
					}
				}
			}
		}
		Map<String,Object> incoming = map(snapshot.root.get("placements"));
		for (String family : placementFamilies()) {
			for (Object raw : list(incoming, family)) {
				Map<String,Object> absolute = absolutePlacement(family, map(raw), x, y);
				int targetLevel = checkedAdd(level,
					integer(map(raw), "levelOffset"), "incoming footprint level");
				Footprint incomingFootprint = footprint(family, absolute);
				Point unavailable = live.firstUnavailable(targetLevel, incomingFootprint);
				if (unavailable != null) {
						addCollision(collisions, "incoming-footprint-unavailable", targetLevel,
							unavailable.x, unavailable.y, "Incoming " + singularFamily(family)
								+ " footprint extends beyond declared terrain coverage.");
						blocked = true;
				}
				for (PlacementRef occupied : preserved.query(targetLevel, incomingFootprint)) {
					if (!incomingFootprint.intersects(occupied.footprint)) continue;
					Point overlap = incomingFootprint.firstIntersection(occupied.footprint);
					addCollision(collisions, "incoming-footprint-occupied", targetLevel,
						overlap.x, overlap.y, "Incoming " + singularFamily(family)
							+ " footprint overlaps preserved "
							+ singularFamily(occupied.family) + " "
							+ occupied.placementId + ".");
					blocked = true;
				}
			}
		}
		List<Object> idMappings = allocatePlacementIds(verified, snapshot, live,
			destinationLevels, destination);
		Collections.sort(collisions, canonicalComparator());
		if (blocked) return planOnly(verified, snapshot, "paste", level, x, y,
			collisions, idMappings, overwrite, true);

		Path stage = stagePackage(verified.projectRoot);
		try {
			PackageState state = PackageState.read(verified.projectRoot,
				relativeStage(verified.projectRoot, stage));
			removeDestinationPlacements(state, destinationLevels, destination);
			applySnapshotTiles(state, snapshot, level, x, y, false);
			addSnapshotPlacements(state, snapshot, level, x, y, idMappings);
			String afterWorking = state.writeAndValidate(verified);
			Map<String,Object> plan = plan(verified, snapshot, "paste", level, x, y,
				stage, collisions, idMappings, overwrite, false);
			return new PreparedMutation(stage, plan, afterWorking);
		} catch (Exception failure) {
			deleteTree(stage);
			if (failure instanceof IOException) throw (IOException)failure;
			throw (WorldBuilderContractException)failure;
		}
	}

	private static void applySnapshotTiles(PackageState state,
		WorldBuilderRegionContracts.Snapshot snapshot, int destinationLevel,
		int destinationX, int destinationY, boolean voidTiles)
		throws WorldBuilderContractException {
		for (Object rawLevel : list(snapshot.root, "levels")) {
			Map<String,Object> level = map(rawLevel);
			int targetLevel = checkedAdd(destinationLevel,
				integer(level, "levelOffset"), "target level");
			for (Object rawTile : list(level, "tiles")) {
				Map<String,Object> tile = map(rawTile);
				int x = checkedAdd(destinationX, integer(tile, "xOffset"), "target x");
				int y = checkedAdd(destinationY, integer(tile, "yOffset"), "target y");
				byte[] encoded = voidTiles ? WorldBuilderCanonicalVoidTerrain.tile()
					: encodeTile(tile);
				if (!state.setTile(targetLevel, x, y, encoded)) throw problem(
					WorldBuilderErrorCodes.MAP_MISMATCH, "terrain",
					"Region operation reached unavailable terrain after planning.",
					"Discard the stale plan and preview again.");
			}
		}
	}

	private static void removeOwnedPlacements(PackageState state,
		WorldBuilderRegionContracts.Snapshot snapshot, int level, int x, int y)
		throws WorldBuilderContractException {
		WorldBuilderRegionContracts.Geometry geometry = translatedGeometry(snapshot, x, y);
		Set<Integer> levels = snapshotLevels(snapshot, level);
		removeDestinationPlacements(state, levels, geometry);
	}

	private static void removeDestinationPlacements(PackageState state,
		Set<Integer> levels, WorldBuilderRegionContracts.Geometry geometry)
		throws WorldBuilderContractException {
		for (Integer level : levels) {
			Map<String,Object> payload = state.placements.get(level);
			if (payload == null) continue;
			for (String family : placementFamilies()) {
				List<Object> kept = new ArrayList<Object>();
				for (Object raw : list(payload, family)) {
					Map<String,Object> record = map(raw);
					Point point = owner(family, record);
					if (!geometry.owns(point.x, point.y)) kept.add(record);
				}
				payload.put(family, kept);
			}
		}
	}

	private static void addSnapshotPlacements(PackageState state,
		WorldBuilderRegionContracts.Snapshot snapshot, int level, int x, int y,
		List<Object> mappings)
		throws WorldBuilderContractException {
		Map<String,String> destinationIds = new HashMap<String,String>();
		for (Object raw : mappings) {
			Map<String,Object> mapping = map(raw);
			destinationIds.put(text(mapping, "family") + "\u0000"
				+ text(mapping, "sourcePlacementId"),
				text(mapping, "destinationPlacementId"));
		}
		Map<String,Object> placements = map(snapshot.root.get("placements"));
		for (String family : placementFamilies()) {
			for (Object raw : list(placements, family)) {
				Map<String,Object> relative = map(raw);
				int targetLevel = checkedAdd(level,
					integer(relative, "levelOffset"), "placement level");
				Map<String,Object> payload = state.placements.get(Integer.valueOf(targetLevel));
				if (payload == null) throw problem(WorldBuilderErrorCodes.MAP_MISMATCH,
					"placements", "Pasted placement level is unavailable.",
					"Preview against the current complete destination package.");
				Map<String,Object> absolute = absolutePlacement(family, relative, x, y);
				String mapped = destinationIds.get(singularFamily(family) + "\u0000"
					+ text(relative, "placementId"));
				if (mapped != null) absolute.put("placementId", mapped);
				list(payload, family).add(absolute);
			}
		}
		for (Map<String,Object> payload : state.placements.values()) {
			for (String family : placementFamilies()) sortPlacements(family,
				list(payload, family));
		}
	}

	private Map<String,Object> compatibility(
		WorldBuilderAdaptiveProjectLifecycle.VerifiedProject verified,
		WorldBuilderRegionContracts.Snapshot snapshot)
		throws WorldBuilderContractException {
		List<Object> issues = new ArrayList<Object>();
		Map<String,Object> source = map(snapshot.root.get("sourceEvidence"));
		if (!snapshot.worldSpace.equals(verified.working.worldSpace)) {
			issues.add(issue("world-space-mismatch", snapshot.worldSpace,
				"Snapshot and project static world spaces differ."));
		}
		if (!"layered-world-package-v1".equals(text(source, "packageSchemaId"))
			|| !"signed-layered-v1".equals(text(source, "coordinateModel"))) {
			issues.add(issue("package-contract-mismatch", text(source, "packageSchemaId"),
				"Snapshot package schema or coordinate model is unsupported."));
		}
		Map<String,Object> catalog = map(snapshot.root.get("catalog"));
		String projectCatalogHash = fingerprint(verified.manifest, "definitionsSha256");
		if (!verified.definitions.catalogId.equals(text(catalog, "catalogId"))
			|| !projectCatalogHash.equals(text(catalog, "sha256"))) {
			issues.add(issue("catalog-mismatch", "definition-catalog",
				"Snapshot and project definition catalogs are not byte-identical."));
		}
		for (Object raw : list(snapshot.root, "dependencies")) {
			Map<String,Object> dependency = map(raw);
			String logical = text(dependency, "logicalId");
			if ("unsupported".equals(text(dependency, "resolution"))) {
				issues.add(issue("unsupported-dependency", logical,
					"Custom material/sprite content is not bundleable in region snapshot v1."));
				continue;
			}
			if ("definition".equals(text(dependency, "kind"))) {
				String family = text(dependency, "family");
				int id = integer(dependency, "numericId");
				if (!hasDefinition(verified.definitions, family, id)) {
					issues.add(issue("missing-definition", logical,
						"Project catalog does not contain the required logical definition."));
				}
			}
		}
		Collections.sort(issues, canonicalComparator());
		Map<String,Object> report = new LinkedHashMap<String,Object>();
		report.put("schemaVersion", Long.valueOf(1L));
		report.put("manifestType", "world-builder-region-compatibility-report");
		report.put("snapshotId", snapshot.id);
		report.put("projectId", verified.projectId);
		report.put("compatible", Boolean.valueOf(issues.isEmpty()));
		report.put("issues", issues);
		report.put("reportFingerprintSha256", WorldBuilderRegionContracts.ZERO_HASH);
		WorldBuilderRegionContracts.bindFingerprint(report, "reportFingerprintSha256");
		WorldBuilderRegionContracts.compatibility(report);
		return report;
	}

	private PreparedMutation blockedPlan(
		WorldBuilderAdaptiveProjectLifecycle.VerifiedProject verified,
		WorldBuilderRegionContracts.Snapshot snapshot, String operation,
		int level, int x, int y, String kind, String detail)
		throws WorldBuilderContractException {
		List<Object> collisions = new ArrayList<Object>();
		collisions.add(collision(kind, level, x, y, detail));
		return planOnly(verified, snapshot, operation, level, x, y,
			collisions, false, true);
	}

	private PreparedMutation planOnly(
		WorldBuilderAdaptiveProjectLifecycle.VerifiedProject verified,
		WorldBuilderRegionContracts.Snapshot snapshot, String operation,
		int level, int x, int y, List<Object> collisions, boolean overwrite,
		boolean blocked) throws WorldBuilderContractException {
		return planOnly(verified, snapshot, operation, level, x, y, collisions,
			new ArrayList<Object>(), overwrite, blocked);
	}

	private PreparedMutation planOnly(
		WorldBuilderAdaptiveProjectLifecycle.VerifiedProject verified,
		WorldBuilderRegionContracts.Snapshot snapshot, String operation,
		int level, int x, int y, List<Object> collisions, List<Object> idMappings,
		boolean overwrite, boolean blocked) throws WorldBuilderContractException {
		Map<String,Object> plan = planBase(verified, snapshot, operation, level, x, y);
		plan.put("files", new ArrayList<Object>());
		plan.put("placementIdMappings", idMappings);
		plan.put("collisions", collisions);
		plan.put("overwriteRequired", Boolean.valueOf(overwrite));
		plan.put("blocked", Boolean.valueOf(blocked));
		plan.put("planFingerprintSha256", WorldBuilderRegionContracts.ZERO_HASH);
		WorldBuilderRegionContracts.bindFingerprint(plan, "planFingerprintSha256");
		WorldBuilderRegionContracts.operationPlan(plan);
		return new PreparedMutation(null, plan, "");
	}

	private static List<Object> allocatePlacementIds(
		WorldBuilderAdaptiveProjectLifecycle.VerifiedProject verified,
		WorldBuilderRegionContracts.Snapshot snapshot, PackageState live,
		Set<Integer> destinationLevels, WorldBuilderRegionContracts.Geometry destination)
		throws WorldBuilderContractException {
		Set<String> used = new HashSet<String>();
		for (Map.Entry<Integer,Map<String,Object>> entry : live.placements.entrySet()) {
			for (String family : placementFamilies()) {
				for (Object raw : list(entry.getValue(), family)) {
					Map<String,Object> record = map(raw);
					Point owner = owner(family, record);
					if (!(destinationLevels.contains(entry.getKey())
						&& destination.owns(owner.x, owner.y))) {
						used.add(text(record, "placementId"));
					}
				}
			}
		}
		List<Object> mappings = new ArrayList<Object>();
		Map<String,Object> placements = map(snapshot.root.get("placements"));
		for (String family : placementFamilies()) {
			for (Object raw : list(placements, family)) {
				String sourceId = text(map(raw), "placementId");
				String destinationId = sourceId;
				if (used.contains(destinationId)) {
					String seed = verified.projectId + "\u0000" + snapshot.id + "\u0000"
						+ singularFamily(family) + "\u0000" + sourceId;
					for (int attempt = 0; attempt <= WorldBuilderRegionContracts.MAX_PLACEMENTS;
						attempt++) {
						destinationId = "region-" + WorldBuilderHashes.sha256(
							(seed + "\u0000" + attempt).getBytes(StandardCharsets.UTF_8));
						if (!used.contains(destinationId)) break;
					}
					if (used.contains(destinationId)) throw problem(
						WorldBuilderErrorCodes.INVENTORY_LIMIT_EXCEEDED, "placementId",
						"No deterministic destination-local placement ID remains available.",
						"Reduce adversarial placement-ID occupancy and preview again.");
				}
				used.add(destinationId);
				Map<String,Object> mapping = new LinkedHashMap<String,Object>();
				mapping.put("family", singularFamily(family));
				mapping.put("sourcePlacementId", sourceId);
				mapping.put("destinationPlacementId", destinationId);
				mappings.add(mapping);
			}
		}
		Collections.sort(mappings, new Comparator<Object>() {
			@Override public int compare(Object left, Object right) {
				Map<String,Object> a = map(left), b = map(right);
				return (text(a, "family") + "\u0000" + text(a, "sourcePlacementId"))
					.compareTo(text(b, "family") + "\u0000"
						+ text(b, "sourcePlacementId"));
			}
		});
		return mappings;
	}

	private Map<String,Object> plan(
		WorldBuilderAdaptiveProjectLifecycle.VerifiedProject verified,
		WorldBuilderRegionContracts.Snapshot snapshot, String operation,
		int level, int x, int y, Path stage, List<Object> collisions,
		List<Object> idMappings,
		boolean overwrite, boolean blocked)
		throws IOException, WorldBuilderContractException {
		Map<String,Object> plan = planBase(verified, snapshot, operation, level, x, y);
		Path live = verified.projectRoot.resolve(PACKAGE);
		List<Object> files = changedFiles(live, stage);
		plan.put("files", blocked ? new ArrayList<Object>() : files);
		plan.put("placementIdMappings", idMappings);
		plan.put("collisions", collisions);
		plan.put("overwriteRequired", Boolean.valueOf(overwrite));
		plan.put("blocked", Boolean.valueOf(blocked));
		plan.put("planFingerprintSha256", WorldBuilderRegionContracts.ZERO_HASH);
		WorldBuilderRegionContracts.bindFingerprint(plan, "planFingerprintSha256");
		WorldBuilderRegionContracts.operationPlan(plan);
		return plan;
	}

	private static Map<String,Object> planBase(
		WorldBuilderAdaptiveProjectLifecycle.VerifiedProject verified,
		WorldBuilderRegionContracts.Snapshot snapshot, String operation,
		int level, int x, int y) {
		Map<String,Object> plan = new LinkedHashMap<String,Object>();
		plan.put("schemaVersion", Long.valueOf(1L));
		plan.put("manifestType", "world-builder-region-operation-plan");
		plan.put("operation", operation);
		plan.put("snapshotId", snapshot.id);
		plan.put("projectId", verified.projectId);
		plan.put("workingBeforeSha256", verified.working.fingerprintSha256);
		plan.put("destinationAnchor", anchor(level, x, y));
		return plan;
	}

	private static List<Object> changedFiles(Path live, Path stage)
		throws IOException, WorldBuilderContractException {
		Map<String,Path> before = files(live);
		Map<String,Path> after = files(stage);
		if (!before.keySet().equals(after.keySet())) throw problem(
			WorldBuilderErrorCodes.MUTATION_FAILED, "package",
			"Region operation unexpectedly changed the package inventory.",
			"Keep v1 region edits within existing declared terrain and placement files.");
		List<Object> records = new ArrayList<Object>();
		for (String relative : before.keySet()) {
			String beforeHash = WorldBuilderHashes.sha256(before.get(relative));
			String afterHash = WorldBuilderHashes.sha256(after.get(relative));
			if (beforeHash.equals(afterHash)) continue;
			Map<String,Object> record = new LinkedHashMap<String,Object>();
			record.put("relativePath", relative);
			record.put("beforeSha256", beforeHash);
			record.put("afterSha256", afterHash);
			records.add(record);
		}
		if (records.isEmpty()) throw problem(WorldBuilderErrorCodes.CONTRACT_VALUE_INVALID,
			"operation", "Region operation would make no working-package change.",
			"Choose content or a destination whose exact result differs.");
		return records;
	}

	private LibraryRecord publishToLibrary(Path project,
		WorldBuilderRegionContracts.Snapshot snapshot)
		throws IOException, WorldBuilderContractException {
		byte[] snapshotBytes = WorldBuilderJsonDocuments.pretty(snapshot.root)
			.getBytes(StandardCharsets.UTF_8);
		Map<String,Object> manifest = bundleManifest(snapshot, snapshotBytes);
		byte[] bundle = writeBundle(manifest, snapshotBytes);
		return publishBundle(project, new Bundle(bundle, manifest, snapshot));
	}

	private LibraryRecord publishBundle(Path project, Bundle bundle)
		throws IOException, WorldBuilderContractException {
		Path library = library(project, true);
		String name = bundle.snapshot.id + BUNDLE_EXTENSION;
		Path destination = library.resolve(name);
		LibraryRecord published;
		if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
			Path verified = requireLibraryFile(project, bundle.snapshot.id);
			if (!fileEquals(verified, bundle.bytes)) throw problem(
				WorldBuilderErrorCodes.INVENTORY_DUPLICATE, LIBRARY + "/" + name,
				"Snapshot ID already exists with different bundle bytes.",
				"Preserve the existing library entry and investigate the collision.");
			published = new LibraryRecord(LIBRARY + "/" + name,
				WorldBuilderHashes.sha256(verified), false);
			writeActiveClipboard(project, bundle.snapshot.id);
			return published;
		}
		requireLibraryCapacity(library);
		String bundleHash = WorldBuilderHashes.sha256(bundle.bytes);
		Path stage = library.resolve("." + name + ".staging-" + bundleHash);
		try {
			Files.write(stage, bundle.bytes, StandardOpenOption.CREATE_NEW,
				StandardOpenOption.WRITE);
			WorldBuilderAdaptiveDurability.forceFile(stage);
			WorldBuilderAdaptiveAtomicFiles.moveNew(stage, destination,
				"region-library", LIBRARY + "/" + name);
			WorldBuilderAdaptiveDurability.forceDirectory(library);
		} finally {
			Files.deleteIfExists(stage);
		}
		Bundle reread = readBundle(destination);
		if (!reread.snapshot.id.equals(bundle.snapshot.id)
			|| !Arrays.equals(reread.bytes, bundle.bytes)) throw problem(
			WorldBuilderErrorCodes.MUTATION_FAILED, LIBRARY + "/" + name,
			"Published snapshot library bundle did not verify byte-for-byte.",
			"Preserve the library and request filesystem recovery.");
		published = new LibraryRecord(LIBRARY + "/" + name,
			bundleHash, true);
		writeActiveClipboard(project, bundle.snapshot.id);
		return published;
	}

	private static void writeActiveClipboard(Path project, String snapshotId)
		throws IOException, WorldBuilderContractException {
		if (!WorldBuilderBoundedInventory.isHash(snapshotId)) throw problem(
			WorldBuilderErrorCodes.CONTRACT_VALUE_INVALID, ACTIVE_CLIPBOARD,
			"Active Region clipboard snapshot ID is invalid.",
			"Use the exact ID returned by copy or import.");
		Path root = project.toAbsolutePath().normalize();
		Path pointer = root.resolve(ACTIVE_CLIPBOARD).normalize();
		Path parent = pointer.getParent();
		if (!pointer.startsWith(root) || parent == null
			|| !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)
			|| Files.isSymbolicLink(parent)
			|| !parent.toRealPath().startsWith(root.toRealPath())) throw problem(
			WorldBuilderErrorCodes.UNSAFE_PATH, ACTIVE_CLIPBOARD,
			"Active Region clipboard path is unsafe.",
			"Restore the real project-local snapshot-library directory.");
		if (Files.exists(pointer, LinkOption.NOFOLLOW_LINKS)
			&& (!Files.isRegularFile(pointer, LinkOption.NOFOLLOW_LINKS)
				|| Files.isSymbolicLink(pointer))) throw problem(
			WorldBuilderErrorCodes.UNSAFE_PATH, ACTIVE_CLIPBOARD,
			"Active Region clipboard is linked or not a regular file.",
			"Restore a real project-local clipboard pointer.");
		Map<String,Object> value = new LinkedHashMap<String,Object>();
		value.put("schemaVersion", Long.valueOf(1L));
		value.put("manifestType", ACTIVE_CLIPBOARD_TYPE);
		value.put("snapshotId", snapshotId);
		byte[] bytes = WorldBuilderJsonDocuments.pretty(value)
			.getBytes(StandardCharsets.UTF_8);
		Path temporary = parent.resolve(".active-v1.json.new-"
			+ WorldBuilderHashes.sha256(bytes));
		Files.write(temporary, bytes, StandardOpenOption.CREATE_NEW,
			StandardOpenOption.WRITE);
		try {
			WorldBuilderAdaptiveDurability.forceFile(temporary);
			Files.move(temporary, pointer, StandardCopyOption.ATOMIC_MOVE,
				StandardCopyOption.REPLACE_EXISTING);
			WorldBuilderAdaptiveDurability.forceDirectory(parent);
		} finally { Files.deleteIfExists(temporary); }
	}

	private static String readActiveClipboard(Path project)
		throws IOException, WorldBuilderContractException {
		Path root = project.toAbsolutePath().normalize();
		Path pointer = root.resolve(ACTIVE_CLIPBOARD).normalize();
		if (!Files.exists(pointer, LinkOption.NOFOLLOW_LINKS)) return "";
		if (!pointer.startsWith(root)
			|| !Files.isRegularFile(pointer, LinkOption.NOFOLLOW_LINKS)
			|| Files.isSymbolicLink(pointer)
			|| Files.size(pointer) < 2L || Files.size(pointer) > 4096L) throw problem(
			WorldBuilderErrorCodes.UNSAFE_PATH, ACTIVE_CLIPBOARD,
			"Active Region clipboard is missing, linked, or outside its size bound.",
			"Restore a real bounded project-local clipboard pointer.");
		Map<String,Object> value;
		try { value = WorldBuilderJsonDocuments.readObject(pointer); }
		catch (WorldBuilderDiscoveryException malformed) { throw problem(
			WorldBuilderErrorCodes.SOURCE_CORRUPT, ACTIVE_CLIPBOARD,
			"Active Region clipboard is malformed.",
			"Copy or import another region to replace the pointer.", malformed); }
		Set<String> keys = new HashSet<String>(Arrays.asList(
			"schemaVersion", "manifestType", "snapshotId"));
		Object snapshotId = value.get("snapshotId");
		if (!value.keySet().equals(keys)
			|| !Long.valueOf(1L).equals(value.get("schemaVersion"))
			|| !ACTIVE_CLIPBOARD_TYPE.equals(value.get("manifestType"))
			|| !(snapshotId instanceof String)
			|| !WorldBuilderBoundedInventory.isHash((String)snapshotId)) throw problem(
			WorldBuilderErrorCodes.SOURCE_CORRUPT, ACTIVE_CLIPBOARD,
			"Active Region clipboard contract is invalid.",
			"Copy or import another region to replace the pointer.");
		return (String)snapshotId;
	}

	private static void requireLibraryCapacity(Path library)
		throws IOException, WorldBuilderContractException {
		int entries = 0;
		try (DirectoryStream<Path> inventory = Files.newDirectoryStream(library)) {
			for (Path ignored : inventory) {
				if (++entries >= MAX_LIBRARY_ENTRIES) throw problem(
					WorldBuilderErrorCodes.INVENTORY_LIMIT_EXCEEDED, LIBRARY,
					"Snapshot library has reached its 1,024-entry capacity.",
					"Archive reviewed bundles outside the project before adding another.");
			}
		}
	}

	private Bundle loadLibrary(Path project, String snapshotId)
		throws IOException, WorldBuilderContractException {
		if (!WorldBuilderBoundedInventory.isHash(snapshotId)) throw problem(
			WorldBuilderErrorCodes.CONTRACT_VALUE_INVALID, "snapshotId",
			"Snapshot ID is not one lowercase SHA-256 value.",
			"Use the exact ID returned by copy or import.");
		Bundle bundle = readBundle(requireLibraryFile(project, snapshotId));
		if (!snapshotId.equals(bundle.snapshot.id)) throw problem(
			WorldBuilderErrorCodes.SOURCE_CORRUPT, LIBRARY,
			"Library filename and snapshot identity disagree.",
			"Restore the exact content-addressed library entry.");
		return bundle;
	}

	private static Path requireLibraryFile(Path project, String snapshotId)
		throws IOException, WorldBuilderContractException {
		Path library = library(project, false);
		Path path = library.resolve(snapshotId + BUNDLE_EXTENSION).normalize();
		if (!path.getParent().equals(library)
			|| !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
			|| Files.isSymbolicLink(path)) throw problem(
			WorldBuilderErrorCodes.UNSAFE_PATH, LIBRARY,
			"Snapshot library entry is missing, linked, or escaped.",
			"Restore one real content-addressed .wbr file.");
		rejectHardLink(path, LIBRARY + "/" + path.getFileName());
		if (Files.size(path) < 1L || Files.size(path) > MAX_BUNDLE_BYTES) throw problem(
			WorldBuilderErrorCodes.CONTRACT_LIMIT_EXCEEDED, LIBRARY,
			"Snapshot library entry size is outside its bound.",
			"Restore one canonical bundle no larger than 32 MiB.");
		return path;
	}

	private static Path library(Path project, boolean create)
		throws IOException, WorldBuilderContractException {
		Path root = project.toAbsolutePath().normalize();
		Path library = root.resolve(LIBRARY).normalize();
		if (!library.startsWith(root)) throw problem(WorldBuilderErrorCodes.UNSAFE_PATH,
			LIBRARY, "Snapshot library path escaped the project.",
			"Restore the canonical project-local library path.");
		Path parent = library.getParent();
		if (create && !Files.exists(parent, LinkOption.NOFOLLOW_LINKS)) {
			Files.createDirectory(parent);
			WorldBuilderAdaptiveDurability.forceDirectory(parent.getParent());
		}
		if (!Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)
			|| Files.isSymbolicLink(parent)) throw problem(WorldBuilderErrorCodes.UNSAFE_PATH,
			LIBRARY, "Snapshot library parent is linked or not a directory.",
			"Restore one real project-local snapshot-library directory.");
		if (create && !Files.exists(library, LinkOption.NOFOLLOW_LINKS)) {
			Files.createDirectory(library);
			WorldBuilderAdaptiveDurability.forceDirectory(parent);
		}
		if (!Files.isDirectory(library, LinkOption.NOFOLLOW_LINKS)
			|| Files.isSymbolicLink(library) || !library.toRealPath().startsWith(root.toRealPath())) {
			throw problem(WorldBuilderErrorCodes.UNSAFE_PATH, LIBRARY,
				"Snapshot library is linked, missing, or outside the project.",
				"Restore one real contained project-local library.");
		}
		recoverLibraryStages(library);
		int count = 0;
		try (DirectoryStream<Path> entries = Files.newDirectoryStream(library)) {
			for (Path entry : entries) {
				if (++count > MAX_LIBRARY_ENTRIES) throw problem(
					WorldBuilderErrorCodes.INVENTORY_LIMIT_EXCEEDED, LIBRARY,
					"Snapshot library exceeds 1,024 entries.",
					"Move reviewed bundles to a separate archive before adding more.");
				String name = entry.getFileName().toString();
				if (!name.matches("[0-9a-f]{64}\\.wbr")
					|| !Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)
					|| Files.isSymbolicLink(entry)) throw problem(
					WorldBuilderErrorCodes.UNSAFE_PATH, LIBRARY,
					"Snapshot library contains an unsafe or untracked entry: " + name + ".",
					"Keep only content-addressed regular .wbr bundles in library v1.");
				rejectHardLink(entry, LIBRARY + "/" + name);
				long size = Files.size(entry);
				if (size < 1L || size > MAX_BUNDLE_BYTES) throw problem(
					WorldBuilderErrorCodes.CONTRACT_LIMIT_EXCEEDED, LIBRARY + "/" + name,
					"Snapshot library entry size is outside its bound.",
					"Remove the oversized entry only after preserving recovery evidence.");
			}
		}
		return library;
	}

	private static void recoverLibraryStages(Path library)
		throws IOException, WorldBuilderContractException {
		List<Path> stages = new ArrayList<Path>();
		int count = 0;
		try (DirectoryStream<Path> entries = Files.newDirectoryStream(library)) {
			for (Path entry : entries) {
				if (++count > MAX_LIBRARY_DIRECTORY_ENTRIES) throw problem(
					WorldBuilderErrorCodes.INVENTORY_LIMIT_EXCEEDED, LIBRARY,
					"Snapshot library recovery scan exceeds its bounded inventory.",
					"Preserve and review unexpected library entries before recovery.");
				if (entry.getFileName().toString().matches(
					"\\.[0-9a-f]{64}\\.wbr\\.staging-[0-9a-f]{64}")) {
					if (!stages.isEmpty()) throw problem(
						WorldBuilderErrorCodes.RECOVERY_REQUIRED, LIBRARY,
						"Multiple snapshot publication stages are ambiguous.",
						"Preserve the library and request exact recovery.");
					stages.add(entry);
				}
			}
		}
		for (Path stage : stages) {
			String name = stage.getFileName().toString();
			String snapshotId = name.substring(1, 65);
			String expectedHash = name.substring(name.length() - 64);
			long stageSize = Files.isRegularFile(stage, LinkOption.NOFOLLOW_LINKS)
				&& !Files.isSymbolicLink(stage) ? Files.size(stage) : -1L;
			if (stageSize < 1L || stageSize > MAX_BUNDLE_BYTES
				|| !expectedHash.equals(WorldBuilderHashes.sha256(stage))) throw problem(
				WorldBuilderErrorCodes.RECOVERY_REQUIRED, LIBRARY,
				"Snapshot publication stage does not match its durable identity.",
				"Preserve the stage and library; recovery refuses ambiguous bytes.");
			Path destination = library.resolve(snapshotId + BUNDLE_EXTENSION);
			if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
				if (!Files.isRegularFile(destination, LinkOption.NOFOLLOW_LINKS)
					|| Files.isSymbolicLink(destination)
					|| Files.size(destination) > MAX_BUNDLE_BYTES
					|| !filesEqual(stage, destination, MAX_BUNDLE_BYTES)) {
					throw problem(WorldBuilderErrorCodes.RECOVERY_REQUIRED, LIBRARY,
						"Staged and published snapshot identities disagree.",
						"Preserve both files; recovery refuses an identity collision.");
				}
				Files.delete(stage);
				WorldBuilderAdaptiveDurability.forceDirectory(library);
				Bundle published = readBundle(destination);
				if (!snapshotId.equals(published.snapshot.id)
					|| !expectedHash.equals(WorldBuilderHashes.sha256(published.bytes))) {
					throw problem(WorldBuilderErrorCodes.RECOVERY_REQUIRED, LIBRARY,
						"Published snapshot is noncanonical or has the wrong identity.",
						"Preserve the published entry and restore exact canonical bytes.");
				}
				continue;
			}
			Bundle staged = readBundle(stage);
			if (!snapshotId.equals(staged.snapshot.id)
				|| !expectedHash.equals(WorldBuilderHashes.sha256(staged.bytes))) throw problem(
				WorldBuilderErrorCodes.RECOVERY_REQUIRED, LIBRARY,
				"Snapshot publication stage is noncanonical or has the wrong snapshot ID.",
				"Preserve the stage and library; recovery refuses ambiguous authority.");
			Files.delete(stage);
			WorldBuilderAdaptiveDurability.forceDirectory(library);
		}
	}

	private static Bundle readBundle(Path requested)
		throws IOException, WorldBuilderContractException {
		Path path = safeExternalFile(requested, "region bundle", 1L, MAX_BUNDLE_BYTES);
		Map<String,byte[]> entries = new TreeMap<String,byte[]>();
		try (ZipFile archive = new ZipFile(path.toFile())) {
			Enumeration<? extends ZipEntry> rawEntries = archive.entries();
			while (rawEntries.hasMoreElements()) {
				ZipEntry entry = rawEntries.nextElement();
				if (entry.isDirectory()) throw unsafeBundle("Bundle contains a directory entry.");
				String name = WorldBuilderPortablePath.require(entry.getName(),
					"region-bundle");
				if (!("manifest.json".equals(name) || "snapshot.json".equals(name))
					|| entries.containsKey(name)) throw unsafeBundle(
					"Bundle inventory contains an unexpected or duplicate entry: " + name + ".");
				long declared = entry.getSize();
				if (declared < 2L || declared > MAX_ENTRY_BYTES) throw unsafeBundle(
					"Bundle entry size is outside its bound: " + name + ".");
				try (InputStream input = archive.getInputStream(entry)) {
					entries.put(name, readBounded(input, MAX_ENTRY_BYTES));
				}
			}
		} catch (WorldBuilderContractException refusal) {
			throw refusal;
		} catch (IOException malformed) {
			throw problem(WorldBuilderErrorCodes.UNSUPPORTED_FORMAT, "bundle",
				"Region bundle ZIP is malformed or unreadable.",
				"Use an exact portable-region-bundle-v1 archive.", malformed);
		}
		if (!entries.keySet().equals(new TreeSet<String>(
			Arrays.asList("manifest.json", "snapshot.json")))) throw unsafeBundle(
			"Bundle must contain exactly manifest.json and snapshot.json.");
		Map<String,Object> manifest = readJson(entries.get("manifest.json"), "manifest.json");
		Map<String,Object> snapshotRoot = readJson(entries.get("snapshot.json"), "snapshot.json");
		WorldBuilderRegionContracts.Snapshot snapshot =
			WorldBuilderRegionContracts.snapshot(snapshotRoot);
		WorldBuilderRegionContracts.bundleManifest(manifest,
			WorldBuilderHashes.sha256(entries.get("snapshot.json")),
			entries.get("snapshot.json").length);
		if (!snapshot.id.equals(text(manifest, "snapshotId"))) throw unsafeBundle(
			"Bundle manifest and snapshot identities disagree.");
		byte[] canonicalSnapshot = WorldBuilderJsonDocuments.pretty(snapshot.root)
			.getBytes(StandardCharsets.UTF_8);
		Map<String,Object> canonicalManifest = bundleManifest(snapshot, canonicalSnapshot);
		byte[] canonicalBundle;
		try {
			canonicalBundle = writeBundle(canonicalManifest, canonicalSnapshot);
		} catch (IOException impossible) {
			throw problem(WorldBuilderErrorCodes.MUTATION_FAILED, "bundle",
				"Canonical region bundle encoding failed in memory.",
				"Preserve the source archive and inspect the local Java ZIP provider.",
				impossible);
		}
		return new Bundle(canonicalBundle, canonicalManifest, snapshot);
	}

	private static Map<String,Object> bundleManifest(
		WorldBuilderRegionContracts.Snapshot snapshot, byte[] snapshotBytes)
		throws WorldBuilderContractException {
		Map<String,Object> file = new LinkedHashMap<String,Object>();
		file.put("role", "snapshot");
		file.put("relativePath", "snapshot.json");
		file.put("size", Long.valueOf(snapshotBytes.length));
		file.put("sha256", WorldBuilderHashes.sha256(snapshotBytes));
		Map<String,Object> manifest = new LinkedHashMap<String,Object>();
		manifest.put("schemaVersion", Long.valueOf(1L));
		manifest.put("manifestType", "world-builder-region-bundle");
		manifest.put("formatId", "portable-region-bundle-v1");
		manifest.put("snapshotId", snapshot.id);
		manifest.put("files", Arrays.<Object>asList(file));
		manifest.put("bundleFingerprintSha256", WorldBuilderRegionContracts.ZERO_HASH);
		WorldBuilderRegionContracts.bindFingerprint(manifest, "bundleFingerprintSha256");
		WorldBuilderRegionContracts.bundleManifest(manifest,
			WorldBuilderHashes.sha256(snapshotBytes), snapshotBytes.length);
		return manifest;
	}

	private static byte[] writeBundle(Map<String,Object> manifest, byte[] snapshot)
		throws IOException {
		byte[] manifestBytes = WorldBuilderJsonDocuments.pretty(manifest)
			.getBytes(StandardCharsets.UTF_8);
		ByteArrayOutputStream output = new ByteArrayOutputStream(
			manifestBytes.length + snapshot.length + 512);
		try (ZipOutputStream archive = new ZipOutputStream(output)) {
			writeStored(archive, "manifest.json", manifestBytes);
			writeStored(archive, "snapshot.json", snapshot);
		}
		return output.toByteArray();
	}

	private static void writeStored(ZipOutputStream archive, String name, byte[] bytes)
		throws IOException {
		CRC32 crc = new CRC32(); crc.update(bytes);
		ZipEntry entry = new ZipEntry(name);
		entry.setMethod(ZipEntry.STORED); entry.setTime(ZIP_TIME);
		entry.setSize(bytes.length); entry.setCompressedSize(bytes.length);
		entry.setCrc(crc.getValue());
		archive.putNextEntry(entry); archive.write(bytes); archive.closeEntry();
	}

	private static Map<String,Object> readJson(byte[] bytes, String label)
		throws WorldBuilderContractException {
		try {
			return WorldBuilderJsonDocuments.readObject(bytes, label);
		} catch (WorldBuilderDiscoveryException malformed) {
			throw problem(WorldBuilderErrorCodes.MALFORMED_JSON, label,
				"Region bundle JSON is malformed.",
				"Use canonical bounded UTF-8 region contract JSON.", malformed);
		}
	}

	private static Path stagePackage(Path project)
		throws IOException, WorldBuilderContractException {
		Path parent = project.resolve("working/layered-world");
		Path stage = parent.resolve(".region-stage-" + UUID.randomUUID());
		copyTree(project.resolve(PACKAGE), stage);
		return stage;
	}

	private void publishWorkingPackage(Path project, Path stage,
		RegionTransaction transaction)
		throws Exception {
		if (stage == null) throw problem(WorldBuilderErrorCodes.MUTATION_FAILED,
			"package", "Blocked plan has no publishable staged package.",
			"Resolve every blocker and preview again.");
		Path live = project.resolve(PACKAGE);
		Path rollback = rollbackPath(project);
		if (Files.exists(rollback, LinkOption.NOFOLLOW_LINKS)) throw problem(
			WorldBuilderErrorCodes.RECOVERY_REQUIRED, "working/layered-world",
			"A prior region operation retains rollback state.",
			"Recover or inspect the exact retained package before retrying.");
		try {
			Files.move(live, rollback, StandardCopyOption.ATOMIC_MOVE);
			WorldBuilderAdaptiveDurability.forceDirectory(live.getParent());
			transaction.phase("rollback-ready");
			observer.observe("rollback-package-durable", project);
			try {
				Files.move(stage, live, StandardCopyOption.ATOMIC_MOVE);
				WorldBuilderAdaptiveDurability.forceDirectory(live.getParent());
				transaction.phase("package-published");
			} catch (Exception failure) {
				Files.move(rollback, live, StandardCopyOption.ATOMIC_MOVE);
				WorldBuilderAdaptiveDurability.forceDirectory(live.getParent());
				throw failure;
			}
		} catch (AtomicMoveNotSupportedException unsupported) {
			throw problem(WorldBuilderErrorCodes.MUTATION_FAILED, PACKAGE,
				"Filesystem cannot atomically exchange the working package.",
				"Use a local filesystem with same-directory atomic moves.", unsupported);
		}
	}

	private void rollbackWorkingPublication(Path project,
		RegionTransaction transaction, Exception original)
		throws IOException, WorldBuilderContractException {
		Path live = project.resolve(PACKAGE);
		Path rollback = rollbackPath(project);
		Path failed = transaction.armRollback();
		try {
			Files.move(live, failed, StandardCopyOption.ATOMIC_MOVE);
			Files.move(rollback, live, StandardCopyOption.ATOMIC_MOVE);
			WorldBuilderAdaptiveDurability.forceDirectory(live.getParent());
			cleanupArtifact(transaction, failed, "failed",
				(String)transaction.value.get("afterTreeSha256"),
				"failed-quarantined", "failed-cleanup-tree-deleted");
		} catch (Exception rollbackFailure) {
			rollbackFailure.addSuppressed(original);
			throw problem(WorldBuilderErrorCodes.RECOVERY_REQUIRED, PACKAGE,
				"Region operation failed and exact working-package rollback did not complete.",
				"Preserve .region-original-v1 and request exact recovery.", rollbackFailure);
		}
	}

	private void completeWorkingPublication(Path project,
		RegionTransaction transaction)
		throws Exception {
		cleanupArtifact(transaction, rollbackPath(project), "rollback",
			(String)transaction.value.get("beforeTreeSha256"),
			"rollback-quarantined", "cleanup-tree-deleted");
	}

	private void preservePasteUndo(Path project, RegionTransaction transaction,
		String snapshotId, String planFingerprintSha256, String beforeWorkingSha256,
		String afterWorkingSha256)
		throws IOException, WorldBuilderContractException {
		Path historyParent = project.resolve("region-history").normalize();
		Path history = project.resolve(PASTE_UNDO_ROOT).normalize();
		if (!history.startsWith(project)) throw problem(WorldBuilderErrorCodes.UNSAFE_PATH,
			PASTE_UNDO_ROOT, "Region history escaped the project.",
			"Restore the standard project-local region-history layout.");
		if (!Files.exists(historyParent, LinkOption.NOFOLLOW_LINKS)) {
			Files.createDirectory(historyParent);
		}
		if (!Files.isDirectory(historyParent, LinkOption.NOFOLLOW_LINKS)
			|| Files.isSymbolicLink(historyParent)) throw problem(
			WorldBuilderErrorCodes.UNSAFE_PATH, "region-history",
			"Region history root is linked or not a real directory.",
			"Replace it with a real project-local directory before pasting.");
		if (!Files.exists(history, LinkOption.NOFOLLOW_LINKS)) Files.createDirectory(history);
		if (!Files.isDirectory(history, LinkOption.NOFOLLOW_LINKS)
			|| Files.isSymbolicLink(history)) throw problem(WorldBuilderErrorCodes.UNSAFE_PATH,
			PASTE_UNDO_ROOT, "Region history is linked or not a real directory.",
			"Replace it with a real project-local directory before pasting.");
		if (!history.toRealPath().startsWith(project.toRealPath())) throw problem(
			WorldBuilderErrorCodes.UNSAFE_PATH, PASTE_UNDO_ROOT,
			"Region history resolves outside the project.",
			"Restore the standard project-local region-history layout.");
		PasteUndoRecord previous = Files.exists(project.resolve(PASTE_UNDO_POINTER),
			LinkOption.NOFOLLOW_LINKS) ? readPasteUndo(project) : null;
		String identity = UUID.randomUUID().toString();
		Path stage = history.resolve(PASTE_UNDO_STAGE_PREFIX + identity);
		Path entry = history.resolve(PASTE_UNDO_ENTRY_PREFIX + identity);
		try {
			Files.createDirectory(stage);
			Path retained = stage.resolve("package");
			copyTreeDurable(rollbackPath(project), retained);
			Map<String,Object> receipt = new LinkedHashMap<String,Object>();
			receipt.put("schemaVersion", Long.valueOf(1L));
			receipt.put("manifestType", "world-builder-region-paste-undo");
			receipt.put("snapshotId", snapshotId);
			receipt.put("planFingerprintSha256", planFingerprintSha256);
			receipt.put("beforeTreeSha256", transaction.value.get("beforeTreeSha256"));
			receipt.put("afterTreeSha256", transaction.value.get("afterTreeSha256"));
			receipt.put("beforeWorkingSha256", beforeWorkingSha256);
			receipt.put("afterWorkingSha256", afterWorkingSha256);
			byte[] receiptBytes = WorldBuilderJsonDocuments.pretty(receipt)
				.getBytes(StandardCharsets.UTF_8);
			Path receiptPath = stage.resolve("receipt.json");
			Files.write(receiptPath, receiptBytes, StandardOpenOption.CREATE_NEW,
				StandardOpenOption.WRITE);
			WorldBuilderAdaptiveDurability.forceFile(receiptPath);
			WorldBuilderAdaptiveDurability.forceDirectory(stage);
			Files.move(stage, entry, StandardCopyOption.ATOMIC_MOVE);
			WorldBuilderAdaptiveDurability.forceDirectory(history);
			Map<String,Object> pointer = new LinkedHashMap<String,Object>();
			pointer.put("schemaVersion", Long.valueOf(1L));
			pointer.put("manifestType", "world-builder-region-paste-undo-pointer");
			pointer.put("entry", entry.getFileName().toString());
			pointer.put("receiptSha256", WorldBuilderHashes.sha256(receiptBytes));
			writePasteUndoPointer(project.resolve(PASTE_UNDO_POINTER), pointer);
			if (previous != null && !previous.entryRoot.equals(entry)) {
				try { deleteTreeBounded(previous.entryRoot); }
				catch (IOException ignored) { /* The new exact pointer remains authoritative. */ }
			}
		} catch (AtomicMoveNotSupportedException unsupported) {
			deleteTreeBounded(stage);
			if (!pasteUndoPointerReferences(project, entry)) deleteTreeBounded(entry);
			throw problem(WorldBuilderErrorCodes.MUTATION_FAILED, PASTE_UNDO_ROOT,
				"Filesystem cannot atomically publish exact Region Paste Undo state.",
				"Use a local filesystem with same-directory atomic moves.", unsupported);
		} catch (IOException | WorldBuilderContractException failure) {
			deleteTreeBounded(stage);
			if (!pasteUndoPointerReferences(project, entry)) deleteTreeBounded(entry);
			throw failure;
		}
	}

	private static boolean pasteUndoPointerReferences(Path project, Path entry) {
		try { return readPasteUndo(project).entryRoot.equals(entry); }
		catch (Exception ignored) { return false; }
	}

	private static PasteUndoRecord readPasteUndo(Path project)
		throws IOException, WorldBuilderContractException {
		Path pointerPath = project.resolve(PASTE_UNDO_POINTER).normalize();
		if (!Files.exists(pointerPath, LinkOption.NOFOLLOW_LINKS)) throw problem(
			WorldBuilderErrorCodes.NO_TARGET, PASTE_UNDO_POINTER,
			"There is no completed Region Paste available to undo.",
			"Paste a region first; Undo only restores the exact latest Paste.");
		Path historyParent = project.resolve("region-history").normalize();
		Path history = project.resolve(PASTE_UNDO_ROOT).normalize();
		if (!Files.isDirectory(historyParent, LinkOption.NOFOLLOW_LINKS)
			|| Files.isSymbolicLink(historyParent)
			|| !Files.isDirectory(history, LinkOption.NOFOLLOW_LINKS)
			|| Files.isSymbolicLink(history)
			|| !history.toRealPath().startsWith(project.toRealPath())) throw problem(
			WorldBuilderErrorCodes.RECOVERY_REQUIRED, PASTE_UNDO_ROOT,
			"Region Paste Undo history is missing, linked, or escaped.",
			"Preserve region-history and restore its exact project-local directories.");
		if (!Files.isRegularFile(pointerPath, LinkOption.NOFOLLOW_LINKS)
			|| Files.isSymbolicLink(pointerPath)
			|| Files.size(pointerPath) > WorldBuilderContractLimits.MAX_JSON_BYTES) {
			throw problem(WorldBuilderErrorCodes.RECOVERY_REQUIRED, PASTE_UNDO_POINTER,
				"Region Paste Undo pointer is unsafe.",
				"Preserve region-history and restore its exact regular-file metadata.");
		}
		Map<String,Object> pointer;
		try { pointer = WorldBuilderJsonDocuments.readObject(pointerPath); }
		catch (WorldBuilderDiscoveryException malformed) { throw problem(
			WorldBuilderErrorCodes.RECOVERY_REQUIRED, PASTE_UNDO_POINTER,
			"Region Paste Undo pointer is malformed.",
			"Preserve region-history and restore its exact receipt.", malformed); }
		Set<String> pointerKeys = new HashSet<String>(Arrays.asList(
			"schemaVersion", "manifestType", "entry", "receiptSha256"));
		String entryName = pointer.get("entry") instanceof String
			? (String)pointer.get("entry") : "";
		String receiptHash = pointer.get("receiptSha256") instanceof String
			? (String)pointer.get("receiptSha256") : "";
		if (!pointer.keySet().equals(pointerKeys)
			|| !Long.valueOf(1L).equals(pointer.get("schemaVersion"))
			|| !"world-builder-region-paste-undo-pointer".equals(pointer.get("manifestType"))
			|| !entryName.matches("paste-undo-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
			|| !WorldBuilderBoundedInventory.isHash(receiptHash)) throw problem(
			WorldBuilderErrorCodes.RECOVERY_REQUIRED, PASTE_UNDO_POINTER,
			"Region Paste Undo pointer contract is invalid.",
			"Preserve region-history and restore its exact v1 pointer.");
		Path entry = project.resolve(PASTE_UNDO_ROOT).resolve(entryName).normalize();
		if (!entry.getParent().equals(history)
			|| !Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)
			|| Files.isSymbolicLink(entry)) throw problem(
			WorldBuilderErrorCodes.RECOVERY_REQUIRED, PASTE_UNDO_POINTER,
			"Region Paste Undo entry is missing, linked, or escaped.",
			"Preserve region-history and restore the exact retained entry.");
		Path receiptPath = entry.resolve("receipt.json");
		Path packageRoot = entry.resolve("package");
		Set<String> entryNames = new HashSet<String>();
		try (DirectoryStream<Path> entries = Files.newDirectoryStream(entry)) {
			for (Path child : entries) entryNames.add(child.getFileName().toString());
		}
		if (!entryNames.equals(new HashSet<String>(Arrays.asList("receipt.json", "package")))) {
			throw problem(WorldBuilderErrorCodes.RECOVERY_REQUIRED, PASTE_UNDO_POINTER,
				"Region Paste Undo entry inventory is invalid.",
				"Preserve region-history and restore the exact retained entry.");
		}
		if (!Files.isRegularFile(receiptPath, LinkOption.NOFOLLOW_LINKS)
			|| Files.isSymbolicLink(receiptPath)
			|| Files.size(receiptPath) > WorldBuilderContractLimits.MAX_JSON_BYTES
			|| !receiptHash.equals(WorldBuilderHashes.sha256(receiptPath))) throw problem(
			WorldBuilderErrorCodes.RECOVERY_REQUIRED, PASTE_UNDO_POINTER,
			"Region Paste Undo receipt does not match its pointer.",
			"Preserve region-history and restore the exact retained receipt.");
		Map<String,Object> receipt;
		try { receipt = WorldBuilderJsonDocuments.readObject(receiptPath); }
		catch (WorldBuilderDiscoveryException malformed) { throw problem(
			WorldBuilderErrorCodes.RECOVERY_REQUIRED, PASTE_UNDO_POINTER,
			"Region Paste Undo receipt is malformed.",
			"Preserve region-history and restore the exact retained receipt.", malformed); }
		Set<String> receiptKeys = new HashSet<String>(Arrays.asList("schemaVersion",
			"manifestType", "snapshotId", "planFingerprintSha256", "beforeTreeSha256",
			"afterTreeSha256", "beforeWorkingSha256", "afterWorkingSha256"));
		if (!receipt.keySet().equals(receiptKeys)
			|| !Long.valueOf(1L).equals(receipt.get("schemaVersion"))
			|| !"world-builder-region-paste-undo".equals(receipt.get("manifestType"))) {
			throw problem(WorldBuilderErrorCodes.RECOVERY_REQUIRED, PASTE_UNDO_POINTER,
				"Region Paste Undo receipt contract is invalid.",
				"Preserve region-history and restore the exact v1 receipt.");
		}
		return new PasteUndoRecord(entry, packageRoot,
			undoHash(receipt, "snapshotId"), undoHash(receipt, "planFingerprintSha256"),
			undoHash(receipt, "beforeTreeSha256"), undoHash(receipt, "afterTreeSha256"),
			undoHash(receipt, "beforeWorkingSha256"), undoHash(receipt, "afterWorkingSha256"));
	}

	private static String undoHash(Map<String,Object> receipt, String key)
		throws WorldBuilderContractException {
		Object value = receipt.get(key);
		if (!(value instanceof String)
			|| !WorldBuilderBoundedInventory.isHash((String)value)) throw problem(
			WorldBuilderErrorCodes.RECOVERY_REQUIRED, PASTE_UNDO_POINTER,
			"Region Paste Undo receipt has an invalid " + key + ".",
			"Preserve region-history and restore the exact retained receipt.");
		return (String)value;
	}

	private static void writePasteUndoPointer(Path pointer, Map<String,Object> value)
		throws IOException {
		byte[] bytes = WorldBuilderJsonDocuments.pretty(value).getBytes(StandardCharsets.UTF_8);
		Path temporary = pointer.resolveSibling(".last-paste-undo.json.new-"
			+ WorldBuilderHashes.sha256(bytes));
		Files.write(temporary, bytes, StandardOpenOption.CREATE_NEW,
			StandardOpenOption.WRITE);
		try {
			WorldBuilderAdaptiveDurability.forceFile(temporary);
			Files.move(temporary, pointer, StandardCopyOption.ATOMIC_MOVE,
				StandardCopyOption.REPLACE_EXISTING);
			WorldBuilderAdaptiveDurability.forceDirectory(pointer.getParent());
		} finally { Files.deleteIfExists(temporary); }
	}

	private static void copyTreeDurable(Path source, Path destination)
		throws IOException, WorldBuilderContractException {
		copyTree(source, destination);
		for (Path file : files(destination).values()) {
			WorldBuilderAdaptiveDurability.forceFile(file);
			WorldBuilderAdaptiveDurability.forceDirectory(file.getParent());
		}
		WorldBuilderAdaptiveDurability.forceDirectory(destination);
	}

	private static void consumePasteUndo(Path project, PasteUndoRecord undo) {
		try {
			Path pointer = project.resolve(PASTE_UNDO_POINTER);
			Files.deleteIfExists(pointer);
			WorldBuilderAdaptiveDurability.forceDirectory(pointer.getParent());
			deleteTreeBounded(undo.entryRoot);
			WorldBuilderAdaptiveDurability.forceDirectory(undo.entryRoot.getParent());
		} catch (IOException ignored) {
			// A stale exact receipt cannot reapply because its after-state drift check fails.
		}
	}

	private void cleanupArtifact(RegionTransaction transaction, Path source,
		String kind, String expectedHash, String quarantinedMilestone,
		String deletedMilestone) throws Exception {
		Path cleanup = transaction.armCleanup(source, kind, expectedHash);
		moveCleanupSource(source, cleanup, expectedHash);
		observer.observe(quarantinedMilestone, transaction.project);
		deleteTreeBounded(cleanup);
		WorldBuilderAdaptiveDurability.forceDirectory(cleanup.getParent());
		observer.observe(deletedMilestone, transaction.project);
		transaction.phase("cleanup-complete");
		observer.observe("before-journal-delete", transaction.project);
		RegionTransaction.remove(transaction.project);
		observer.observe("journal-deleted", transaction.project);
	}

	private static Path rollbackPath(Path project) {
		return project.resolve("working/layered-world/.region-original-v1");
	}

	private static String relativeStage(Path project, Path stage) {
		return project.relativize(stage).toString().replace('\\', '/');
	}

	private static void copyTree(Path source, Path destination)
		throws IOException, WorldBuilderContractException {
		Files.createDirectory(destination);
		for (Map.Entry<String,Path> entry : files(source).entrySet()) {
			Path target = destination.resolve(entry.getKey());
			Files.createDirectories(target.getParent());
			Files.copy(entry.getValue(), target);
		}
	}

	private static Map<String,Path> files(Path root)
		throws IOException, WorldBuilderContractException {
		if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)
			|| Files.isSymbolicLink(root)) throw problem(WorldBuilderErrorCodes.UNSAFE_PATH,
			"package", "Working package is missing, linked, or not a directory.",
			"Restore the complete real project-local working package.");
		Map<String,Path> result = new TreeMap<String,Path>();
		final long[] total = new long[] {0L};
		java.nio.file.Files.walkFileTree(root,
			java.util.EnumSet.noneOf(java.nio.file.FileVisitOption.class), 16,
			new java.nio.file.SimpleFileVisitor<Path>() {
				@Override public java.nio.file.FileVisitResult preVisitDirectory(
					Path directory, BasicFileAttributes attributes) throws IOException {
					if (attributes.isSymbolicLink()) throw new IOException("linked directory");
					return java.nio.file.FileVisitResult.CONTINUE;
				}
				@Override public java.nio.file.FileVisitResult visitFile(
					Path file, BasicFileAttributes attributes) throws IOException {
					if (!attributes.isRegularFile() || attributes.isSymbolicLink()) {
						throw new IOException("unsafe package file");
					}
					String relative = root.relativize(file).toString().replace('\\', '/');
					if (result.size() >= WorldBuilderContractLimits.MAX_INVENTORY_ENTRIES
						|| attributes.size() < 0L || attributes.size() > MAX_ENTRY_BYTES
						|| total[0] > MAX_RECOVERY_TREE_BYTES - attributes.size()) {
						throw new IOException("region package inventory exceeds recovery bounds");
					}
					total[0] += attributes.size();
					try {
						WorldBuilderPortablePath.require(relative, OPERATION);
					} catch (WorldBuilderContractException unsafe) {
						throw new IOException(unsafe);
					}
					result.put(relative, file);
					return java.nio.file.FileVisitResult.CONTINUE;
				}
			});
		return result;
	}

	private static void deleteTree(Path root) throws IOException {
		deleteTreeBounded(root);
	}

	private static void deleteTreeBounded(Path root) throws IOException {
		if (root == null || !Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return;
		final int[] entries = new int[] {0};
		final long[] total = new long[] {0L};
		Files.walkFileTree(root,
			java.util.EnumSet.noneOf(java.nio.file.FileVisitOption.class), 16,
			new java.nio.file.SimpleFileVisitor<Path>() {
				private void count(BasicFileAttributes attributes) throws IOException {
					if (++entries[0] > MAX_RECOVERY_DIRECTORY_ENTRIES) {
						throw new IOException("region cleanup inventory exceeds its bound");
					}
					long size = attributes.isRegularFile() ? attributes.size() : 0L;
					if (attributes.isSymbolicLink() || size < 0L || size > MAX_ENTRY_BYTES
						|| total[0] > MAX_RECOVERY_TREE_BYTES - size) {
						throw new IOException("region cleanup entry exceeds its bound");
					}
					total[0] += size;
				}
				@Override public java.nio.file.FileVisitResult preVisitDirectory(
					Path directory, BasicFileAttributes attributes) throws IOException {
					count(attributes); return java.nio.file.FileVisitResult.CONTINUE;
				}
				@Override public java.nio.file.FileVisitResult visitFile(Path file,
					BasicFileAttributes attributes) throws IOException {
					count(attributes); return java.nio.file.FileVisitResult.CONTINUE;
				}
			});
		final int[] deleted = new int[] {0};
		Files.walkFileTree(root, new java.nio.file.SimpleFileVisitor<Path>() {
			@Override public java.nio.file.FileVisitResult visitFile(Path file,
				BasicFileAttributes attributes) throws IOException {
				if (++deleted[0] > MAX_RECOVERY_DIRECTORY_ENTRIES) {
					throw new IOException("region cleanup deletion exceeds its bound");
				}
				Files.delete(file); return java.nio.file.FileVisitResult.CONTINUE;
			}
			@Override public java.nio.file.FileVisitResult postVisitDirectory(
				Path directory, IOException failure) throws IOException {
				if (failure != null) throw failure;
				if (++deleted[0] > MAX_RECOVERY_DIRECTORY_ENTRIES) {
					throw new IOException("region cleanup deletion exceeds its bound");
				}
				Files.delete(directory); return java.nio.file.FileVisitResult.CONTINUE;
			}
		});
	}

	private static void moveCleanupSource(Path source, Path cleanup,
		String expectedHash) throws IOException, WorldBuilderContractException {
		if (Files.exists(cleanup, LinkOption.NOFOLLOW_LINKS)
			|| !treeEquals(source, expectedHash)) throw problem(
			WorldBuilderErrorCodes.RECOVERY_REQUIRED, TRANSACTION,
			"Cleanup source does not match its journaled durable identity.",
			"Preserve the transaction artifacts; recovery refuses ambiguous cleanup.");
		Files.move(source, cleanup, StandardCopyOption.ATOMIC_MOVE);
		WorldBuilderAdaptiveDurability.forceDirectory(cleanup.getParent());
	}

	private static Path safeExternalFile(Path requested, String label,
		long minimum, long maximum) throws IOException, WorldBuilderContractException {
		if (requested == null) throw problem(WorldBuilderErrorCodes.UNSAFE_PATH, label,
			"Required external file path is absent.", "Choose one real bounded file.");
		Path path = requested.toAbsolutePath().normalize();
		if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
			|| Files.isSymbolicLink(path)) throw problem(WorldBuilderErrorCodes.UNSAFE_PATH,
			label, "External file is missing, linked, or not regular.",
			"Choose one real independent file.");
		rejectHardLink(path, label);
		long size = Files.size(path);
		if (size < minimum || size > maximum) throw problem(
			WorldBuilderErrorCodes.CONTRACT_LIMIT_EXCEEDED, label,
			"External file size is outside its bound.", "Choose one bounded file.");
		return path;
	}

	private static Path safeNewOutput(Path requested, Path project)
		throws IOException, WorldBuilderContractException {
		if (requested == null) throw problem(WorldBuilderErrorCodes.UNSAFE_PATH, "output",
			"Region export output path is absent.", "Choose a new .wbr output path.");
		Path lexical = requested.toAbsolutePath().normalize();
		Path parent = lexical.getParent();
		if (parent == null || !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) {
			throw problem(WorldBuilderErrorCodes.UNSAFE_PATH, "output",
				"Region export parent is missing or not a directory.",
				"Choose a real existing output directory.");
		}
		Path realParent = parent.toRealPath();
		Path output = realParent.resolve(lexical.getFileName().toString());
		Path realProject = project.toRealPath();
		if (realParent.startsWith(realProject)) throw problem(
			WorldBuilderErrorCodes.UNSAFE_PATH, "output",
			"Region export output cannot be inside the adaptive project.",
			"Choose a separate creator-owned export directory.");
		if (!output.getFileName().toString().endsWith(BUNDLE_EXTENSION)
			|| Files.exists(output, LinkOption.NOFOLLOW_LINKS)) throw problem(
			WorldBuilderErrorCodes.TARGET_DRIFT, "output",
			"Region export destination exists or is not a .wbr path.",
			"Choose a new portable bundle filename.");
		return output;
	}

	private static void rejectHardLink(Path path, String label)
		throws IOException, WorldBuilderContractException {
		try {
			Object links = Files.getAttribute(path, "unix:nlink", LinkOption.NOFOLLOW_LINKS);
			if (links instanceof Number && ((Number)links).longValue() != 1L) {
				throw problem(WorldBuilderErrorCodes.UNSAFE_PATH, label,
					"Region file is hard linked.", "Use one independent regular file.");
			}
		} catch (UnsupportedOperationException ignored) {
			path.toRealPath();
		} catch (IllegalArgumentException ignored) {
			path.toRealPath();
		}
	}

	private static byte[] readBounded(InputStream input, long maximum)
		throws IOException, WorldBuilderContractException {
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		byte[] buffer = new byte[8192];
		int read;
		while ((read = input.read(buffer)) >= 0) {
			if (read == 0) continue;
			if ((long)output.size() + read > maximum) throw unsafeBundle(
				"Bundle entry expands beyond its limit.");
			output.write(buffer, 0, read);
		}
		return output.toByteArray();
	}

	private static boolean fileEquals(Path path, byte[] expected) throws IOException {
		if (Files.size(path) != expected.length || expected.length > MAX_BUNDLE_BYTES) {
			return false;
		}
		try (InputStream input = Files.newInputStream(path)) {
			byte[] buffer = new byte[8192];
			for (int offset = 0; offset < expected.length;) {
				int wanted = Math.min(buffer.length, expected.length - offset);
				int read = readChunk(input, buffer, wanted);
				if (read != wanted) return false;
				for (int index = 0; index < wanted; index++) {
					if (buffer[index] != expected[offset + index]) return false;
				}
				offset += wanted;
			}
			return input.read() < 0;
		}
	}

	private static boolean filesEqual(Path first, Path second, long maximum)
		throws IOException {
		long size = Files.size(first);
		if (size < 0L || size > maximum || Files.size(second) != size) return false;
		try (InputStream left = Files.newInputStream(first);
			InputStream right = Files.newInputStream(second)) {
			byte[] leftBuffer = new byte[8192];
			byte[] rightBuffer = new byte[8192];
			long compared = 0L;
			while (compared < size) {
				int wanted = (int)Math.min(leftBuffer.length, size - compared);
				int leftRead = readChunk(left, leftBuffer, wanted);
				int rightRead = readChunk(right, rightBuffer, wanted);
				if (leftRead != wanted || rightRead != wanted) return false;
				for (int index = 0; index < wanted; index++) {
					if (leftBuffer[index] != rightBuffer[index]) return false;
				}
				compared += wanted;
			}
			return left.read() < 0 && right.read() < 0;
		}
	}

	private static int readChunk(InputStream input, byte[] buffer, int wanted)
		throws IOException {
		int offset = 0;
		while (offset < wanted) {
			int read = input.read(buffer, offset, wanted - offset);
			if (read < 0) break;
			if (read == 0) continue;
			offset += read;
		}
		return offset;
	}

	private static WorldBuilderContractException unsafeBundle(String message) {
		return problem(WorldBuilderErrorCodes.UNSUPPORTED_FORMAT, "bundle", message,
			"Use a strict two-entry portable-region-bundle-v1 archive.");
	}

	private static Map<String,Object> baseResult(String operation, String id,
		LibraryRecord library) {
		Map<String,Object> result = new LinkedHashMap<String,Object>();
		result.put("operation", operation);
		result.put("snapshotId", id);
		result.put("libraryRelativePath", library.relativePath);
		result.put("bundleSha256", library.sha256);
		result.put("libraryEntryCreated", Boolean.valueOf(library.created));
		result.put("worldModified", Boolean.FALSE);
		return result;
	}

	private static Map<String,Object> anchor(int level, int x, int y) {
		Map<String,Object> result = new LinkedHashMap<String,Object>();
		result.put("level", Long.valueOf(level));
		result.put("x", Long.valueOf(x));
		result.put("y", Long.valueOf(y));
		return result;
	}

	private static Map<String,Object> emptyPlacements() {
		Map<String,Object> result = new LinkedHashMap<String,Object>();
		for (String family : placementFamilies()) result.put(family,
			new ArrayList<Object>());
		return result;
	}

	private static List<String> placementFamilies() {
		return Arrays.asList("boundaries", "groundItems", "npcs", "scenery");
	}

	private static String singularFamily(String family) {
		if ("boundaries".equals(family)) return "boundary";
		if ("groundItems".equals(family)) return "ground-item";
		if ("npcs".equals(family)) return "npc";
		if ("scenery".equals(family)) return "scenery";
		throw new AssertionError(family);
	}

	private static int definitionId(String family, Map<String,Object> record) {
		if ("boundaries".equals(family)) return integer(record, "boundaryId");
		if ("groundItems".equals(family)) return integer(record, "itemId");
		if ("npcs".equals(family)) return integer(record, "npcId");
		return integer(record, "sceneryId");
	}

	private static Point owner(String family, Map<String,Object> record) {
		if ("npcs".equals(family)) return point(record.get("start"));
		return point(record.get("position"));
	}

	private static boolean crossesBoundary(String family, Map<String,Object> record,
		WorldBuilderRegionContracts.Geometry geometry) {
		Footprint footprint = footprint(family, record);
		for (long x = footprint.minimumX; x <= (long)footprint.maximumX; x++) {
			for (long y = footprint.minimumY; y <= (long)footprint.maximumY; y++) {
				if (!geometry.owns((int)x, (int)y)) return true;
			}
		}
		return false;
	}

	private static boolean footprintIntersects(String family,
		Map<String,Object> record, WorldBuilderRegionContracts.Geometry geometry) {
		return footprint(family, record).intersects(geometry);
	}

	private static Footprint footprint(String family, Map<String,Object> record) {
		Point origin = owner(family, record);
		if ("boundaries".equals(family)) {
			int direction = integer(record, "direction");
			int[] dx = {0, 1, 0, -1};
			int[] dy = {-1, 0, 1, 0};
			long adjacentX = (long)origin.x + dx[direction];
			long adjacentY = (long)origin.y + dy[direction];
			if (adjacentX < Integer.MIN_VALUE || adjacentX > Integer.MAX_VALUE
				|| adjacentY < Integer.MIN_VALUE || adjacentY > Integer.MAX_VALUE) {
				return new Footprint(origin.x, origin.x, origin.y, origin.y);
			}
			return new Footprint(Math.min(origin.x, (int)adjacentX),
				Math.max(origin.x, (int)adjacentX), Math.min(origin.y, (int)adjacentY),
				Math.max(origin.y, (int)adjacentY));
		}
		if ("npcs".equals(family)) {
			Map<String,Object> bounds = map(record.get("roamBounds"));
			Point minimum = point(bounds.get("minimum"));
			Point maximum = point(bounds.get("maximum"));
			return new Footprint(minimum.x, maximum.x, minimum.y, maximum.y);
		}
		return new Footprint(origin.x, origin.x, origin.y, origin.y);
	}

	private static void requirePackageFootprintBudget(
		Map<Integer,Map<String,Object>> placements,
		String label) throws WorldBuilderContractException {
		long total = 0L;
		for (Map<String,Object> payload : placements.values()) {
			total = addFootprintBudget(payload, total, label);
		}
	}

	private static void requireFootprintBudget(Map<String,Object> placements,
		String label) throws WorldBuilderContractException {
		addFootprintBudget(placements, 0L, label);
	}

	private static long addFootprintBudget(Map<String,Object> placements, long total,
		String label) throws WorldBuilderContractException {
		for (String family : placementFamilies()) {
			for (Object raw : list(placements, family)) {
				total += representedArea(family, map(raw));
				if (total > MAX_REPRESENTED_FOOTPRINT_TILES) throw problem(
					WorldBuilderErrorCodes.INVENTORY_LIMIT_EXCEEDED, "placements",
					"Aggregate represented footprint work exceeds 1,000,000 tiles in "
						+ label + ".",
					"Reduce NPC roam footprints or placement count before preview.");
			}
		}
		return total;
	}

	private static long representedArea(String family, Map<String,Object> record) {
		if ("boundaries".equals(family)) return 2L;
		if (!"npcs".equals(family)) return 1L;
		Map<String,Object> bounds = map(record.get("roamBounds"));
		Map<String,Object> minimum = map(bounds.get("minimum"));
		Map<String,Object> maximum = map(bounds.get("maximum"));
		String xKey = minimum.containsKey("x") ? "x" : "xOffset";
		String yKey = minimum.containsKey("y") ? "y" : "yOffset";
		return ((long)integer(maximum, xKey) - integer(minimum, xKey) + 1L)
			* ((long)integer(maximum, yKey) - integer(minimum, yKey) + 1L);
	}

	private static String footprintDetail(String family, boolean crossing) {
		String rule;
		if ("boundaries".equals(family)) rule =
			"boundary direction maps to one adjacent edge tile";
		else if ("npcs".equals(family)) rule = "complete roam rectangle";
		else rule = "one represented anchor tile";
		return rule + (crossing ? "; footprint crosses polygon" : "; footprint contained");
	}

	private static Map<String,Object> relativePlacement(String family,
		Map<String,Object> record, int levelOffset, int anchorX, int anchorY) {
		Map<String,Object> output = new LinkedHashMap<String,Object>();
		output.put("levelOffset", Long.valueOf(levelOffset));
		output.put("placementId", text(record, "placementId"));
		if ("boundaries".equals(family)) {
			output.put("boundaryId", Long.valueOf(integer(record, "boundaryId")));
			output.put("direction", Long.valueOf(integer(record, "direction")));
			output.put("position", relativePoint(record.get("position"), anchorX, anchorY));
		} else if ("groundItems".equals(family)) {
			output.put("itemId", Long.valueOf(integer(record, "itemId")));
			output.put("amount", Long.valueOf(integer(record, "amount")));
			output.put("respawnSeconds", Long.valueOf(integer(record, "respawnSeconds")));
			output.put("position", relativePoint(record.get("position"), anchorX, anchorY));
		} else if ("npcs".equals(family)) {
			output.put("npcId", Long.valueOf(integer(record, "npcId")));
			output.put("respawnSeconds", Long.valueOf(
				optionalInteger(record, "respawnSeconds", -1)));
			output.put("start", relativePoint(record.get("start"), anchorX, anchorY));
			Map<String,Object> bounds = map(record.get("roamBounds"));
			Map<String,Object> relativeBounds = new LinkedHashMap<String,Object>();
			relativeBounds.put("minimum", relativePoint(bounds.get("minimum"), anchorX, anchorY));
			relativeBounds.put("maximum", relativePoint(bounds.get("maximum"), anchorX, anchorY));
			output.put("roamBounds", relativeBounds);
		} else {
			output.put("sceneryId", Long.valueOf(integer(record, "sceneryId")));
			output.put("direction", Long.valueOf(integer(record, "direction")));
			output.put("position", relativePoint(record.get("position"), anchorX, anchorY));
		}
		return output;
	}

	private static Map<String,Object> absolutePlacement(String family,
		Map<String,Object> relative, int x, int y) throws WorldBuilderContractException {
		Map<String,Object> output = new LinkedHashMap<String,Object>();
		if ("boundaries".equals(family)) {
			output.put("boundaryId", Long.valueOf(integer(relative, "boundaryId")));
			output.put("direction", Long.valueOf(integer(relative, "direction")));
			output.put("placementId", text(relative, "placementId"));
			output.put("position", absolutePoint(relative.get("position"), x, y));
		} else if ("groundItems".equals(family)) {
			output.put("amount", Long.valueOf(integer(relative, "amount")));
			output.put("itemId", Long.valueOf(integer(relative, "itemId")));
			output.put("placementId", text(relative, "placementId"));
			output.put("position", absolutePoint(relative.get("position"), x, y));
			output.put("respawnSeconds", Long.valueOf(integer(relative, "respawnSeconds")));
		} else if ("npcs".equals(family)) {
			output.put("npcId", Long.valueOf(integer(relative, "npcId")));
			output.put("placementId", text(relative, "placementId"));
			output.put("respawnSeconds", Long.valueOf(
				optionalInteger(relative, "respawnSeconds", -1)));
			Map<String,Object> bounds = map(relative.get("roamBounds"));
			Map<String,Object> absoluteBounds = new LinkedHashMap<String,Object>();
			absoluteBounds.put("maximum", absolutePoint(bounds.get("maximum"), x, y));
			absoluteBounds.put("minimum", absolutePoint(bounds.get("minimum"), x, y));
			output.put("roamBounds", absoluteBounds);
			output.put("start", absolutePoint(relative.get("start"), x, y));
		} else {
			output.put("direction", Long.valueOf(integer(relative, "direction")));
			output.put("placementId", text(relative, "placementId"));
			output.put("position", absolutePoint(relative.get("position"), x, y));
			output.put("sceneryId", Long.valueOf(integer(relative, "sceneryId")));
		}
		return output;
	}

	private static Map<String,Object> relativePoint(Object raw, int anchorX, int anchorY) {
		Point point = point(raw);
		Map<String,Object> result = new LinkedHashMap<String,Object>();
		result.put("xOffset", Long.valueOf((long)point.x - anchorX));
		result.put("yOffset", Long.valueOf((long)point.y - anchorY));
		return result;
	}

	private static Map<String,Object> absolutePoint(Object raw, int x, int y)
		throws WorldBuilderContractException {
		Map<String,Object> point = map(raw);
		Map<String,Object> result = new LinkedHashMap<String,Object>();
		result.put("x", Long.valueOf(checkedAdd(x, integer(point, "xOffset"), "point x")));
		result.put("y", Long.valueOf(checkedAdd(y, integer(point, "yOffset"), "point y")));
		return result;
	}

	private static byte[] encodeTile(Map<String,Object> tile) {
		return WorldBuilderRawLayeredTerrainCodec.encodeV2Tile(
			integer(tile, "elevation"), integer(tile, "groundTexture"),
			integer(tile, "groundOverlay"), integer(tile, "roofTexture"),
			integer(tile, "verticalWall"), integer(tile, "horizontalWall"),
			integer(tile, "diagonalWall"));
	}

	private static Set<Integer> snapshotLevels(
		WorldBuilderRegionContracts.Snapshot snapshot, int destinationLevel)
		throws WorldBuilderContractException {
		Set<Integer> result = new HashSet<Integer>();
		for (Object raw : list(snapshot.root, "levels")) {
			result.add(Integer.valueOf(checkedAdd(destinationLevel,
				integer(map(raw), "levelOffset"), "snapshot level")));
		}
		return result;
	}

	private static WorldBuilderRegionContracts.Geometry translatedGeometry(
		WorldBuilderRegionContracts.Snapshot snapshot, int x, int y)
		throws WorldBuilderContractException {
		List<WorldBuilderRegionContracts.Point> points =
			new ArrayList<WorldBuilderRegionContracts.Point>();
		for (Object raw : list(snapshot.root, "polygon")) {
			Map<String,Object> marker = map(raw);
			points.add(new WorldBuilderRegionContracts.Point(
				checkedAdd(x, integer(marker, "xOffset"), "polygon x"),
				checkedAdd(y, integer(marker, "yOffset"), "polygon y")));
		}
		return WorldBuilderRegionContracts.Geometry.create(points, "region-paste");
	}

	private static boolean hasDefinition(
		WorldBuilderCompatibilityEvidence.DefinitionCatalog definitions,
		String family, int id) {
		if ("boundary".equals(family)) return definitions.boundaries.contains(id);
		if ("ground-item".equals(family)) return definitions.groundItems.contains(id);
		if ("npc".equals(family)) return definitions.npcs.contains(id);
		if ("scenery".equals(family)) return definitions.scenery.contains(id);
		return false;
	}

	private static Map<String,Object> issue(String code, String dependency,
		String message) {
		Map<String,Object> result = new LinkedHashMap<String,Object>();
		result.put("code", code); result.put("dependency", dependency);
		result.put("message", message); return result;
	}

	private static Map<String,Object> collision(String kind, int level, int x,
		int y, String detail) {
		Map<String,Object> result = new LinkedHashMap<String,Object>();
		result.put("kind", kind); result.put("level", Long.valueOf(level));
		result.put("x", Long.valueOf(x)); result.put("y", Long.valueOf(y));
		result.put("detail", detail); return result;
	}

	private static String fingerprint(Map<String,Object> manifest, String key) {
		return text(map(manifest.get("fingerprints")), key);
	}

	private static int anchorX(WorldBuilderRegionContracts.Snapshot snapshot) {
		return integer(map(snapshot.root.get("anchor")), "x");
	}

	private static int anchorY(WorldBuilderRegionContracts.Snapshot snapshot) {
		return integer(map(snapshot.root.get("anchor")), "y");
	}

	private static int checkedAdd(int base, int offset, String label)
		throws WorldBuilderContractException {
		long value = (long)base + offset;
		if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) throw problem(
			WorldBuilderErrorCodes.CONTRACT_VALUE_INVALID, label,
			"Translated region coordinate exceeds signed 32-bit range.",
			"Choose a destination whose complete snapshot remains representable.");
		return (int)value;
	}

	@SuppressWarnings("unchecked")
	private static Map<String,Object> map(Object raw) {
		return (Map<String,Object>)raw;
	}

	@SuppressWarnings("unchecked")
	private static List<Object> list(Map<String,Object> value, String key) {
		return (List<Object>)value.get(key);
	}

	private static String text(Map<String,Object> value, String key) {
		return (String)value.get(key);
	}

	private static int integer(Map<String,Object> value, String key) {
		return (int)((Long)value.get(key)).longValue();
	}

	private static int optionalInteger(
		Map<String,Object> value, String key, int defaultValue) {
		Object raw = value.get(key);
		return raw == null ? defaultValue : (int)((Long)raw).longValue();
	}

	private static Point point(Object raw) {
		Map<String,Object> value = map(raw);
		return new Point(integer(value, "x"), integer(value, "y"));
	}

	private static void sortCanonical(List<Object> values) {
		Collections.sort(values, canonicalComparator());
	}

	private static Comparator<Object> canonicalComparator() {
		return new Comparator<Object>() {
			@Override public int compare(Object left, Object right) {
				return WorldBuilderRegionContracts.canonical(left).compareTo(
					WorldBuilderRegionContracts.canonical(right));
			}
		};
	}

	private static void sortPlacements(String family, List<Object> records) {
		Collections.sort(records, new Comparator<Object>() {
			@Override public int compare(Object left, Object right) {
				Map<String,Object> a = map(left), b = map(right);
				Point ap = owner(family, a), bp = owner(family, b);
				int result = Integer.compare(ap.x, bp.x);
				if (result == 0) result = Integer.compare(ap.y, bp.y);
				if (result == 0 && "boundaries".equals(family)) {
					result = Integer.compare(integer(a, "direction"), integer(b, "direction"));
				}
				if (result == 0) result = text(a, "placementId").compareTo(
					text(b, "placementId"));
				return result;
			}
		});
	}

	private static void addCollision(List<Object> collisions, String kind, int level,
		int x, int y, String detail) throws WorldBuilderContractException {
		if (collisions.size() >= WorldBuilderRegionContracts.MAX_PLACEMENTS) throw problem(
			WorldBuilderErrorCodes.INVENTORY_LIMIT_EXCEEDED, "collisions",
			"Region collision preview exceeds 65,536 represented records.",
			"Reduce the destination region or occupied footprint density.");
		collisions.add(collision(kind, level, x, y, detail));
	}

	private static WorldBuilderContractException problem(String code, String path,
		String message, String nextStep) {
		return new WorldBuilderContractException(code, OPERATION, path, false,
			message, nextStep);
	}

	private static WorldBuilderContractException problem(String code, String path,
		String message, String nextStep, Throwable cause) {
		return new WorldBuilderContractException(code, OPERATION, path, false,
			message, nextStep, cause);
	}

	static void recoverRegionTransaction(Path project)
		throws IOException, WorldBuilderContractException {
		Path journal = project.resolve(TRANSACTION);
		Path parent = project.resolve("working/layered-world");
		Path rollback = rollbackPath(project);
		recoverJournalTemps(project, journal, parent);
		if (!Files.exists(journal, LinkOption.NOFOLLOW_LINKS)) {
			if (Files.exists(rollback, LinkOption.NOFOLLOW_LINKS)
				|| hasRegionRecoveryArtifact(parent)) throw problem(
				WorldBuilderErrorCodes.RECOVERY_REQUIRED, PACKAGE,
				"Region rollback package exists without its durable transaction journal.",
				"Preserve every region artifact; the last complete state is ambiguous.");
			return;
		}
		if (!Files.isRegularFile(journal, LinkOption.NOFOLLOW_LINKS)
			|| Files.isSymbolicLink(journal)) throw problem(
			WorldBuilderErrorCodes.RECOVERY_REQUIRED, TRANSACTION,
			"Region transaction journal is linked or not a regular file.",
			"Preserve every region artifact and request exact recovery.");
		Map<String,Object> value;
		try {
			value = WorldBuilderJsonDocuments.readObject(journal);
		} catch (WorldBuilderDiscoveryException malformed) {
			throw problem(WorldBuilderErrorCodes.RECOVERY_REQUIRED, TRANSACTION,
				"Region transaction journal is malformed.",
				"Preserve every region artifact; recovery cannot guess transaction state.",
				malformed);
		}
		Set<String> expected = new HashSet<String>(Arrays.asList("schemaVersion", "phase",
			"stageName", "failedName", "cleanupSourceName", "cleanupName",
			"beforeTreeSha256", "afterTreeSha256",
			"beforeWorkingSha256", "afterWorkingSha256"));
		String stageName = value.get("stageName") instanceof String
			? (String)value.get("stageName") : "";
		if (!value.keySet().equals(expected) || !Long.valueOf(1L).equals(value.get("schemaVersion"))
			|| !stageName.matches("\\.region-stage-[0-9a-fA-F-]{36}")) {
			throw problem(WorldBuilderErrorCodes.RECOVERY_REQUIRED, TRANSACTION,
				"Region transaction journal fields are not the exact v1 contract.",
				"Preserve every region artifact; recovery refuses ambiguous authority.");
		}
		Path stage = parent.resolve(stageName).normalize();
		String failedName = value.get("failedName") instanceof String
			? (String)value.get("failedName") : "";
		if (!failedName.isEmpty() && !failedName.matches("\\.region-failed-[0-9a-f]{64}")) {
			throw problem(WorldBuilderErrorCodes.RECOVERY_REQUIRED, TRANSACTION,
				"Region transaction rollback path is invalid.",
				"Preserve every artifact; recovery refuses escaped authority.");
		}
		Path failed = failedName.isEmpty() ? null : parent.resolve(failedName);
		String cleanupSourceName = value.get("cleanupSourceName") instanceof String
			? (String)value.get("cleanupSourceName") : "";
		String cleanupName = value.get("cleanupName") instanceof String
			? (String)value.get("cleanupName") : "";
		if (cleanupSourceName.isEmpty() != cleanupName.isEmpty()
			|| !cleanupName.isEmpty() && !cleanupName.matches(
				"\\.region-cleanup-(stage|failed|rollback)-[0-9a-f]{64}")) throw problem(
			WorldBuilderErrorCodes.RECOVERY_REQUIRED, TRANSACTION,
			"Region transaction cleanup path is invalid.",
			"Preserve every artifact; recovery refuses escaped authority.");
		Path cleanup = cleanupName.isEmpty() ? null : parent.resolve(cleanupName);
		requireExactRegionArtifacts(parent, stage, failed, rollback, cleanup);
		String beforeTree = requiredHash(value, "beforeTreeSha256");
		String afterTree = requiredHash(value, "afterTreeSha256");
		String beforeWorking = requiredHash(value, "beforeWorkingSha256");
		String afterWorking = requiredHash(value, "afterWorkingSha256");
		Path live = project.resolve(PACKAGE);
		if (!cleanupName.isEmpty()) {
			resumeJournaledCleanup(project, value, parent, live, stage, failed,
				rollback, cleanupSourceName, cleanup, beforeTree, afterTree,
				beforeWorking, afterWorking);
			return;
		}
		boolean liveBefore = treeEquals(live, beforeTree);
		boolean liveAfter = treeEquals(live, afterTree);
		boolean rollbackBefore = treeEquals(rollback, beforeTree);
		boolean stageAfter = treeEquals(stage, afterTree);
		boolean rollbackAbsent = !Files.exists(rollback, LinkOption.NOFOLLOW_LINKS);
		boolean stageAbsent = !Files.exists(stage, LinkOption.NOFOLLOW_LINKS);
		boolean failedAfter = failed != null && treeEquals(failed, afterTree);
		boolean cleanupPresent = cleanup != null
			&& Files.exists(cleanup, LinkOption.NOFOLLOW_LINKS);
		if (liveBefore && stageAfter && rollbackAbsent) {
			finishBeforeState(project, value, stage, "stage", afterTree); return;
		}
		if (!Files.exists(live, LinkOption.NOFOLLOW_LINKS)
			&& rollbackBefore && stageAfter) {
			Files.move(rollback, live, StandardCopyOption.ATOMIC_MOVE);
			WorldBuilderAdaptiveDurability.forceDirectory(parent);
			finishBeforeState(project, value, stage, "stage", afterTree); return;
		}
		if (liveAfter && rollbackBefore && stageAbsent) {
			String manifestWorking = projectManifestWorking(project);
			if (beforeWorking.equals(manifestWorking)) {
				RegionTransaction transaction = new RegionTransaction(project, value);
				Path displaced = transaction.armRollback();
				Files.move(live, displaced, StandardCopyOption.ATOMIC_MOVE);
				Files.move(rollback, live, StandardCopyOption.ATOMIC_MOVE);
				WorldBuilderAdaptiveDurability.forceDirectory(parent);
				finishBeforeState(project, value, displaced, "failed", afterTree); return;
			}
			if (afterWorking.equals(manifestWorking)) {
				finishAfterState(project, value, parent, rollback); return;
			}
		}
		if (!Files.exists(live, LinkOption.NOFOLLOW_LINKS) && rollbackBefore
			&& stageAbsent && failedAfter) {
			Files.move(rollback, live, StandardCopyOption.ATOMIC_MOVE);
			WorldBuilderAdaptiveDurability.forceDirectory(parent);
			finishBeforeState(project, value, failed, "failed", afterTree); return;
		}
		if (liveBefore && rollbackAbsent && stageAbsent && failedAfter) {
			finishBeforeState(project, value, failed, "failed", afterTree); return;
		}
		if (liveAfter && rollbackAbsent && stageAbsent
			&& (failed == null || !Files.exists(failed, LinkOption.NOFOLLOW_LINKS))
			&& afterWorking.equals(projectManifestWorking(project))) {
			RegionTransaction.remove(project); return;
		}
		throw problem(WorldBuilderErrorCodes.RECOVERY_REQUIRED, TRANSACTION,
			"Region transaction artifacts do not prove one complete before or after state.",
			"Preserve the journal, staged package, rollback package, live package, and manifest; do not force cleanup.");
	}

	private static void finishAfterState(Path project, Map<String,Object> value,
		Path parent, Path rollback)
		throws IOException, WorldBuilderContractException {
		RegionTransaction transaction = new RegionTransaction(project, value);
		Path cleanup = transaction.armCleanup(rollback,
			"rollback", (String)value.get("beforeTreeSha256"));
		moveCleanupSource(rollback, cleanup, (String)value.get("beforeTreeSha256"));
		requireRealDirectory(cleanup, "cleanup quarantine");
		deleteTreeBounded(cleanup);
		WorldBuilderAdaptiveDurability.forceDirectory(parent);
		transaction.phase("cleanup-complete");
		RegionTransaction.remove(project);
	}

	private static void finishBeforeState(Path project, Map<String,Object> value,
		Path source, String kind, String expectedHash)
		throws IOException, WorldBuilderContractException {
		RegionTransaction transaction = new RegionTransaction(project, value);
		Path cleanup = transaction.armCleanup(source, kind, expectedHash);
		moveCleanupSource(source, cleanup, expectedHash);
		deleteTreeBounded(cleanup);
		WorldBuilderAdaptiveDurability.forceDirectory(cleanup.getParent());
		transaction.phase("cleanup-complete");
		RegionTransaction.remove(project);
	}

	private static void resumeJournaledCleanup(Path project, Map<String,Object> value,
		Path parent, Path live, Path stage, Path failed, Path rollback,
		String sourceName, Path cleanup, String beforeTree, String afterTree,
		String beforeWorking, String afterWorking)
		throws IOException, WorldBuilderContractException {
		Path source;
		String kind;
		String expectedHash;
		boolean beforeState;
		if (sourceName.equals(stage.getFileName().toString())) {
			source = stage; kind = "stage"; expectedHash = afterTree; beforeState = true;
		} else if (failed != null && sourceName.equals(failed.getFileName().toString())) {
			source = failed; kind = "failed"; expectedHash = afterTree; beforeState = true;
		} else if (sourceName.equals(rollback.getFileName().toString())) {
			source = rollback; kind = "rollback"; expectedHash = beforeTree; beforeState = false;
		} else throw problem(WorldBuilderErrorCodes.RECOVERY_REQUIRED, TRANSACTION,
			"Journaled cleanup source is not a transaction artifact.",
			"Preserve every artifact; recovery refuses unknown cleanup authority.");
		String expectedCleanup = ".region-cleanup-" + kind + "-" + expectedHash;
		if (!cleanup.getFileName().toString().equals(expectedCleanup)) throw problem(
			WorldBuilderErrorCodes.RECOVERY_REQUIRED, TRANSACTION,
			"Journaled cleanup identity does not match its source artifact.",
			"Preserve every artifact; recovery refuses mismatched cleanup authority.");
		boolean sourcePresent = Files.exists(source, LinkOption.NOFOLLOW_LINKS);
		boolean cleanupPresent = Files.exists(cleanup, LinkOption.NOFOLLOW_LINKS);
		if (sourcePresent && cleanupPresent) throw problem(
			WorldBuilderErrorCodes.RECOVERY_REQUIRED, TRANSACTION,
			"Cleanup source and quarantine both exist.",
			"Preserve both artifacts; recovery refuses ambiguous authority.");
		String manifestWorking = projectManifestWorking(project);
		boolean stateProved = beforeState
			? treeEquals(live, beforeTree) && beforeWorking.equals(manifestWorking)
				&& !Files.exists(rollback, LinkOption.NOFOLLOW_LINKS)
				&& ("stage".equals(kind)
					? failed == null || !Files.exists(failed, LinkOption.NOFOLLOW_LINKS)
					: !Files.exists(stage, LinkOption.NOFOLLOW_LINKS))
			: treeEquals(live, afterTree) && afterWorking.equals(manifestWorking)
				&& !Files.exists(stage, LinkOption.NOFOLLOW_LINKS)
				&& (failed == null || !Files.exists(failed, LinkOption.NOFOLLOW_LINKS));
		if (!stateProved) throw problem(WorldBuilderErrorCodes.RECOVERY_REQUIRED, TRANSACTION,
			"Live package and manifest do not prove the journaled cleanup state.",
			"Preserve every artifact; recovery refuses cleanup without a complete state.");
		if (sourcePresent) {
			moveCleanupSource(source, cleanup, expectedHash);
			cleanupPresent = true;
		}
		if (cleanupPresent) {
			requireRealDirectory(cleanup, "cleanup quarantine");
			deleteTreeBounded(cleanup);
			WorldBuilderAdaptiveDurability.forceDirectory(parent);
		}
		RegionTransaction transaction = new RegionTransaction(project, value);
		transaction.phase("cleanup-complete");
		RegionTransaction.remove(project);
	}

	private static void recoverJournalTemps(Path project, Path journal, Path parent)
		throws IOException, WorldBuilderContractException {
		List<Path> temporary = new ArrayList<Path>();
		int count = 0;
		try (DirectoryStream<Path> entries = Files.newDirectoryStream(parent)) {
			for (Path entry : entries) {
				if (++count > MAX_RECOVERY_DIRECTORY_ENTRIES) throw problem(
					WorldBuilderErrorCodes.INVENTORY_LIMIT_EXCEEDED, TRANSACTION,
					"Region recovery directory scan exceeds its bound.",
					"Preserve and review unexpected transaction-directory entries.");
				String name = entry.getFileName().toString();
				if (name.startsWith(JOURNAL_TEMP_PREFIX)) {
					if (!temporary.isEmpty()) throw problem(
						WorldBuilderErrorCodes.RECOVERY_REQUIRED, TRANSACTION,
						"Multiple orphan region journal writes make publication authority ambiguous.",
						"Preserve every journal file and request exact recovery.");
					temporary.add(entry);
				}
			}
		}
		if (temporary.isEmpty()) return;
		Path candidate = temporary.get(0);
		String name = candidate.getFileName().toString();
		if (!name.matches("\\.region-transaction-v1\\.json\\.new-[0-9a-f]{64}")
			|| !Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)
			|| Files.isSymbolicLink(candidate)
			|| Files.size(candidate) > WorldBuilderContractLimits.MAX_JSON_BYTES
			|| !name.substring(JOURNAL_TEMP_PREFIX.length()).equals(
				WorldBuilderHashes.sha256(candidate))) throw problem(
			WorldBuilderErrorCodes.RECOVERY_REQUIRED, TRANSACTION,
			"Orphan region journal write is oversized, linked, or has the wrong identity.",
			"Preserve the orphan file; recovery refuses unproved journal bytes.");
		Map<String,Object> candidateValue;
		try {
			candidateValue = WorldBuilderJsonDocuments.readObject(candidate);
		} catch (WorldBuilderDiscoveryException malformed) {
			throw problem(WorldBuilderErrorCodes.RECOVERY_REQUIRED, TRANSACTION,
				"Orphan region journal write is malformed.",
				"Preserve the orphan file; recovery refuses malformed authority.", malformed);
		}
		if (!Files.exists(journal, LinkOption.NOFOLLOW_LINKS)) {
			Files.move(candidate, journal, StandardCopyOption.ATOMIC_MOVE);
			WorldBuilderAdaptiveDurability.forceDirectory(parent);
			return;
		}
		Map<String,Object> current;
		try {
			current = WorldBuilderJsonDocuments.readObject(journal);
		} catch (WorldBuilderDiscoveryException malformed) {
			throw problem(WorldBuilderErrorCodes.RECOVERY_REQUIRED, TRANSACTION,
				"Published region journal is malformed beside an orphan write.",
				"Preserve both journal files and request exact recovery.", malformed);
		}
		for (String key : Arrays.asList("schemaVersion", "stageName",
			"beforeTreeSha256", "afterTreeSha256", "beforeWorkingSha256",
			"afterWorkingSha256")) {
			if (!java.util.Objects.equals(current.get(key), candidateValue.get(key))) {
				throw problem(WorldBuilderErrorCodes.RECOVERY_REQUIRED, TRANSACTION,
					"Published and orphan region journals identify different transactions.",
					"Preserve both journal files; recovery refuses ambiguity.");
			}
		}
		Files.delete(candidate);
		WorldBuilderAdaptiveDurability.forceDirectory(parent);
	}

	private static boolean hasRegionRecoveryArtifact(Path parent)
		throws IOException, WorldBuilderContractException {
		int count = 0;
		boolean found = false;
		try (DirectoryStream<Path> entries = Files.newDirectoryStream(parent)) {
			for (Path entry : entries) {
				if (++count > MAX_RECOVERY_DIRECTORY_ENTRIES) {
					throw problem(WorldBuilderErrorCodes.INVENTORY_LIMIT_EXCEEDED, TRANSACTION,
						"Region recovery directory scan exceeds its bound.",
						"Preserve and review unexpected transaction-directory entries.");
				}
				String name = entry.getFileName().toString();
				if (name.equals(".region-transaction-v1.json")
					|| name.equals(".region-original-v1")
					|| name.startsWith(".region-stage-") || name.startsWith(".region-failed-")
					|| name.startsWith(".region-cleanup-")
					|| name.startsWith(JOURNAL_TEMP_PREFIX)) found = true;
			}
		}
		return found;
	}

	private static void requireExactRegionArtifacts(Path parent, Path stage, Path failed,
		Path rollback, Path cleanup) throws IOException, WorldBuilderContractException {
		int count = 0;
		Path journal = parent.resolve(".region-transaction-v1.json");
		try (DirectoryStream<Path> entries = Files.newDirectoryStream(parent)) {
			for (Path entry : entries) {
				if (++count > MAX_RECOVERY_DIRECTORY_ENTRIES) throw problem(
					WorldBuilderErrorCodes.INVENTORY_LIMIT_EXCEEDED, TRANSACTION,
					"Region transaction artifact scan exceeds its bound.",
					"Preserve and review unexpected transaction-directory entries.");
				String name = entry.getFileName().toString();
				if (!name.startsWith(".region-")) continue;
				Path normalized = entry.normalize();
				if (normalized.equals(journal) || normalized.equals(stage)
					|| normalized.equals(rollback)
					|| failed != null && normalized.equals(failed)
					|| cleanup != null && normalized.equals(cleanup)) continue;
				throw problem(WorldBuilderErrorCodes.RECOVERY_REQUIRED, TRANSACTION,
					"Region transaction directory contains an unjournaled artifact: "
						+ name + ".",
					"Preserve every artifact; recovery refuses unbounded or ambiguous authority.");
			}
		}
	}

	private static void requireRealDirectory(Path path, String label)
		throws IOException, WorldBuilderContractException {
		if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
			|| Files.isSymbolicLink(path)) throw problem(
			WorldBuilderErrorCodes.RECOVERY_REQUIRED, TRANSACTION,
			"Region " + label + " is linked or not a real directory.",
			"Preserve every artifact; recovery refuses unsafe cleanup.");
	}

	private static String requiredHash(Map<String,Object> value, String key)
		throws WorldBuilderContractException {
		Object raw = value.get(key);
		if (!(raw instanceof String) || !WorldBuilderBoundedInventory.isHash((String)raw)) {
			throw problem(WorldBuilderErrorCodes.RECOVERY_REQUIRED, TRANSACTION,
				"Region journal contains an invalid identity: " + key + ".",
				"Preserve every artifact; recovery refuses invalid authority.");
		}
		return (String)raw;
	}

	private static String projectManifestWorking(Path project)
		throws IOException, WorldBuilderContractException {
		try {
			Map<String,Object> manifest = WorldBuilderJsonDocuments.readObject(
				project.resolve("project.json"));
			return requiredHash(map(manifest.get("fingerprints")), "workingSha256");
		} catch (WorldBuilderDiscoveryException malformed) {
			throw problem(WorldBuilderErrorCodes.RECOVERY_REQUIRED, "project.json",
				"Project manifest cannot prove the region transaction state.",
				"Preserve all transaction artifacts and restore exact metadata.", malformed);
		}
	}

	private static boolean treeEquals(Path path, String expected) throws IOException {
		return Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
			&& !Files.isSymbolicLink(path) && treeFingerprint(path).equals(expected);
	}

	private static String treeFingerprint(Path root) throws IOException {
		try {
			StringBuilder inventory = new StringBuilder();
			for (Map.Entry<String,Path> entry : files(root).entrySet()) {
				long size = Files.size(entry.getValue());
				if (size < 0L || size > MAX_ENTRY_BYTES) {
					throw new IOException("region package file exceeds its hash bound");
				}
				inventory.append(entry.getKey()).append('\0')
					.append(size).append('\0')
					.append(WorldBuilderHashes.sha256(entry.getValue())).append('\n');
			}
			return WorldBuilderHashes.sha256(
				inventory.toString().getBytes(StandardCharsets.UTF_8));
		} catch (WorldBuilderContractException unsafe) {
			throw new IOException(unsafe);
		}
	}

	private static final class RegionTransaction {
		final Path project;
		final Map<String,Object> value;
		RegionTransaction(Path project, Map<String,Object> value) {
			this.project = project; this.value = value;
		}
		static RegionTransaction prepare(Path project, PreparedMutation prepared,
			String beforeWorking) throws IOException, WorldBuilderContractException {
			Map<String,Object> value = new LinkedHashMap<String,Object>();
			value.put("schemaVersion", Long.valueOf(1L)); value.put("phase", "prepared");
			value.put("stageName", prepared.stage.getFileName().toString());
			value.put("failedName", "");
			value.put("cleanupSourceName", "");
			value.put("cleanupName", "");
			value.put("beforeTreeSha256", treeFingerprint(project.resolve(PACKAGE)));
			value.put("afterTreeSha256", treeFingerprint(prepared.stage));
			value.put("beforeWorkingSha256", beforeWorking);
			value.put("afterWorkingSha256", prepared.afterWorkingSha256);
			RegionTransaction result = new RegionTransaction(project, value);
			result.write(); return result;
		}
		void phase(String phase) throws IOException { value.put("phase", phase); write(); }
		Path armRollback() throws IOException {
			String failedName = ".region-failed-" + (String)value.get("afterTreeSha256");
			value.put("failedName", failedName); phase("rolling-back");
			return project.resolve("working/layered-world").resolve(failedName);
		}
		Path armCleanup(Path source, String kind, String identity) throws IOException {
			String cleanupName = ".region-cleanup-" + kind + "-" + identity;
			value.put("cleanupSourceName", source.getFileName().toString());
			value.put("cleanupName", cleanupName); phase("cleaning-up");
			return project.resolve("working/layered-world").resolve(cleanupName);
		}
		void write() throws IOException {
			Path destination = project.resolve(TRANSACTION);
			byte[] bytes = WorldBuilderJsonDocuments.pretty(value)
				.getBytes(StandardCharsets.UTF_8);
			Path temporary = destination.resolveSibling(JOURNAL_TEMP_PREFIX
				+ WorldBuilderHashes.sha256(bytes));
			Files.write(temporary, bytes, StandardOpenOption.CREATE_NEW,
				StandardOpenOption.WRITE);
			WorldBuilderAdaptiveDurability.forceFile(temporary);
			if (String.valueOf(value.get("phase")).equals(System.getProperty(
				"worldbuilder.region.testJournalWriteFailurePhase", ""))) {
				throw new IOException("injected region journal write failure");
			}
			try {
				Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE,
					StandardCopyOption.REPLACE_EXISTING);
			} catch (AtomicMoveNotSupportedException unsupported) {
				Files.deleteIfExists(temporary); throw unsupported;
			}
			WorldBuilderAdaptiveDurability.forceDirectory(destination.getParent());
		}
		static void remove(Path project) throws IOException {
			Path journal = project.resolve(TRANSACTION);
			if (Boolean.parseBoolean(System.getProperty(
				"worldbuilder.region.testJournalDeleteFailure", "false"))) {
				throw new IOException("injected region journal delete failure");
			}
			Files.deleteIfExists(journal);
			WorldBuilderAdaptiveDurability.forceDirectory(journal.getParent());
		}
	}

	private static final class Capture {
		final WorldBuilderRegionContracts.Snapshot snapshot;
		Capture(WorldBuilderRegionContracts.Snapshot snapshot) { this.snapshot = snapshot; }
	}

	private static final class Bundle {
		final byte[] bytes;
		final Map<String,Object> manifest;
		final WorldBuilderRegionContracts.Snapshot snapshot;
		Bundle(byte[] bytes, Map<String,Object> manifest,
			WorldBuilderRegionContracts.Snapshot snapshot) {
			this.bytes = bytes; this.manifest = manifest; this.snapshot = snapshot;
		}
	}

	private static final class LibraryRecord {
		final String relativePath;
		final String sha256;
		final boolean created;
		LibraryRecord(String relativePath, String sha256, boolean created) {
			this.relativePath = relativePath; this.sha256 = sha256;
			this.created = created;
		}
	}

	private static final class PreparedMutation {
		final Path stage;
		final Map<String,Object> plan;
		final String afterWorkingSha256;
		PreparedMutation(Path stage, Map<String,Object> plan, String afterWorkingSha256) {
			this.stage = stage; this.plan = plan;
			this.afterWorkingSha256 = afterWorkingSha256;
		}
		void discard() throws IOException { deleteTree(stage); }
	}

	private static final class PasteUndoRecord {
		final Path entryRoot;
		final Path packageRoot;
		final String snapshotId;
		final String planFingerprintSha256;
		final String beforeTreeSha256;
		final String afterTreeSha256;
		final String beforeWorkingSha256;
		final String afterWorkingSha256;
		PasteUndoRecord(Path entryRoot, Path packageRoot, String snapshotId,
			String planFingerprintSha256, String beforeTreeSha256,
			String afterTreeSha256, String beforeWorkingSha256,
			String afterWorkingSha256) {
			this.entryRoot = entryRoot; this.packageRoot = packageRoot;
			this.snapshotId = snapshotId;
			this.planFingerprintSha256 = planFingerprintSha256;
			this.beforeTreeSha256 = beforeTreeSha256;
			this.afterTreeSha256 = afterTreeSha256;
			this.beforeWorkingSha256 = beforeWorkingSha256;
			this.afterWorkingSha256 = afterWorkingSha256;
		}
	}

	private static final class Destination {
		final int level; final int x; final int y;
		Destination(int level, int x, int y) { this.level = level; this.x = x; this.y = y; }
	}

	private static final class Point {
		final int x; final int y;
		Point(int x, int y) { this.x = x; this.y = y; }
	}

	private static final class Footprint {
		final int minimumX, maximumX, minimumY, maximumY;
		Footprint(int minimumX, int maximumX, int minimumY, int maximumY) {
			this.minimumX = minimumX; this.maximumX = maximumX;
			this.minimumY = minimumY; this.maximumY = maximumY;
		}
		long area() {
			return ((long)maximumX - minimumX + 1L)
				* ((long)maximumY - minimumY + 1L);
		}
		boolean intersects(Footprint other) {
			return minimumX <= other.maximumX && other.minimumX <= maximumX
				&& minimumY <= other.maximumY && other.minimumY <= maximumY;
		}
		Point firstIntersection(Footprint other) {
			return new Point(Math.max(minimumX, other.minimumX),
				Math.max(minimumY, other.minimumY));
		}
		boolean contains(Point point) {
			return minimumX <= point.x && point.x <= maximumX
				&& minimumY <= point.y && point.y <= maximumY;
		}
		boolean intersects(WorldBuilderRegionContracts.Geometry geometry) {
			if (maximumX < geometry.minimumX || geometry.maximumX < minimumX
				|| maximumY < geometry.minimumY || geometry.maximumY < minimumY) return false;
			for (Point corner : Arrays.asList(new Point(minimumX, minimumY),
				new Point(minimumX, maximumY), new Point(maximumX, minimumY),
				new Point(maximumX, maximumY))) {
				if (geometry.owns(corner.x, corner.y)) return true;
			}
			for (WorldBuilderRegionContracts.Point raw : geometry.points) {
				Point point = new Point(raw.x, raw.y);
				if (contains(point)) return true;
			}
			for (int index = 0; index < geometry.points.size(); index++) {
				WorldBuilderRegionContracts.Point a = geometry.points.get(index);
				WorldBuilderRegionContracts.Point b = geometry.points.get(
					(index + 1) % geometry.points.size());
				if (segmentIntersectsRectangle(a.x, a.y, b.x, b.y, this)) return true;
			}
			return false;
		}
	}

	private static boolean segmentIntersectsRectangle(int ax, int ay, int bx, int by,
		Footprint rectangle) {
		if (rectangle.contains(new Point(ax, ay)) || rectangle.contains(new Point(bx, by))) {
			return true;
		}
		return segmentsIntersect(ax, ay, bx, by, rectangle.minimumX, rectangle.minimumY,
			rectangle.maximumX, rectangle.minimumY)
			|| segmentsIntersect(ax, ay, bx, by, rectangle.maximumX, rectangle.minimumY,
				rectangle.maximumX, rectangle.maximumY)
			|| segmentsIntersect(ax, ay, bx, by, rectangle.maximumX, rectangle.maximumY,
				rectangle.minimumX, rectangle.maximumY)
			|| segmentsIntersect(ax, ay, bx, by, rectangle.minimumX, rectangle.maximumY,
				rectangle.minimumX, rectangle.minimumY);
	}

	private static boolean segmentsIntersect(int ax, int ay, int bx, int by,
		int cx, int cy, int dx, int dy) {
		long abC = cross(ax, ay, bx, by, cx, cy);
		long abD = cross(ax, ay, bx, by, dx, dy);
		long cdA = cross(cx, cy, dx, dy, ax, ay);
		long cdB = cross(cx, cy, dx, dy, bx, by);
		return (abC == 0L && between(ax, bx, cx) && between(ay, by, cy))
			|| (abD == 0L && between(ax, bx, dx) && between(ay, by, dy))
			|| (cdA == 0L && between(cx, dx, ax) && between(cy, dy, ay))
			|| (cdB == 0L && between(cx, dx, bx) && between(cy, dy, by))
			|| (abC < 0L) != (abD < 0L) && (cdA < 0L) != (cdB < 0L);
	}

	private static long cross(int ax, int ay, int bx, int by, int px, int py) {
		return ((long)bx - ax) * ((long)py - ay)
			- ((long)by - ay) * ((long)px - ax);
	}

	private static boolean between(int first, int second, int value) {
		return Math.min(first, second) <= value && value <= Math.max(first, second);
	}

	private static final class PlacementRef {
		final String family;
		final String placementId;
		final Footprint footprint;
		PlacementRef(String family, Map<String,Object> record) {
			this.family = family; this.placementId = text(record, "placementId");
			this.footprint = footprint(family, record);
		}
	}

	private static final class SpatialIndex {
		final Map<String,List<PlacementRef>> cells =
			new HashMap<String,List<PlacementRef>>();
		int entries;
		long queryScans;
		void add(int level, String family, Map<String,Object> record)
			throws WorldBuilderContractException {
			PlacementRef reference = new PlacementRef(family, record);
			for (long cellX = Math.floorDiv(reference.footprint.minimumX, 48);
				cellX <= (long)Math.floorDiv(reference.footprint.maximumX, 48); cellX++) {
				for (long cellY = Math.floorDiv(reference.footprint.minimumY, 48);
					cellY <= (long)Math.floorDiv(reference.footprint.maximumY, 48); cellY++) {
					if (++entries > MAX_SPATIAL_INDEX_ENTRIES) throw problem(
						WorldBuilderErrorCodes.INVENTORY_LIMIT_EXCEEDED, "placements",
						"Represented-footprint spatial index exceeds 1,000,000 entries.",
						"Reduce destination placement or NPC roam inventory.");
					String key = level + ":" + cellX + ":" + cellY;
					List<PlacementRef> values = cells.get(key);
					if (values == null) {
						values = new ArrayList<PlacementRef>(); cells.put(key, values);
					}
					values.add(reference);
				}
			}
		}
		Set<PlacementRef> query(int level, Footprint footprint)
			throws WorldBuilderContractException {
			Set<PlacementRef> result = new HashSet<PlacementRef>();
			for (long cellX = Math.floorDiv(footprint.minimumX, 48);
				cellX <= (long)Math.floorDiv(footprint.maximumX, 48); cellX++) {
				for (long cellY = Math.floorDiv(footprint.minimumY, 48);
					cellY <= (long)Math.floorDiv(footprint.maximumY, 48); cellY++) {
					List<PlacementRef> values = cells.get(level + ":" + cellX + ":" + cellY);
					if (values == null) continue;
					for (PlacementRef value : values) {
						if (++queryScans > MAX_REPRESENTED_FOOTPRINT_TILES) throw problem(
							WorldBuilderErrorCodes.INVENTORY_LIMIT_EXCEEDED, "placements",
							"Represented-footprint collision candidates exceed their bound.",
							"Reduce overlapping destination footprint density.");
						result.add(value);
					}
				}
			}
			return result;
		}
	}

	private static final class Dependency implements Comparable<Dependency> {
		final String kind, family, logicalId, catalogId, contentSha256, resolution;
		final int numericId;
		Dependency(String kind, String family, String logicalId, int numericId,
			String catalogId, String contentSha256, String resolution) {
			this.kind = kind; this.family = family; this.logicalId = logicalId;
			this.numericId = numericId; this.catalogId = catalogId;
			this.contentSha256 = contentSha256; this.resolution = resolution;
		}
		static Dependency catalog(String id, String sha256) {
			return new Dependency("definition-catalog", "catalog", "catalog:" + id,
				-1, id, sha256, "catalog");
		}
		static Dependency definition(String catalog, String family, int id) {
			return new Dependency("definition", family,
				"catalog:" + catalog + ":" + family + ":" + id,
				id, catalog, "", "catalog");
		}
		Map<String,Object> toJson() {
			Map<String,Object> value = new LinkedHashMap<String,Object>();
			value.put("kind", kind); value.put("family", family);
			value.put("logicalId", logicalId); value.put("numericId", Long.valueOf(numericId));
			value.put("catalogId", catalogId); value.put("contentSha256", contentSha256);
			value.put("resolution", resolution); value.put("bundled", Boolean.FALSE);
			return value;
		}
		@Override public int compareTo(Dependency other) {
			return (kind + "\u0000" + family + "\u0000" + logicalId).compareTo(
				other.kind + "\u0000" + other.family + "\u0000" + other.logicalId);
		}
	}

	private static final class PackageState {
		final Path project;
		final String relative;
		final Path root;
		final Map<String,Object> manifest;
		final String worldSpace;
		final String coordinateModel;
		final Set<Integer> levels = new HashSet<Integer>();
		final Map<String,Sector> sectors = new HashMap<String,Sector>();
		final Map<Integer,Map<String,Object>> placements =
			new TreeMap<Integer,Map<String,Object>>();
		final Map<Integer,Map<String,Object>> placementDeclarations =
			new HashMap<Integer,Map<String,Object>>();

		private PackageState(Path project, String relative, Map<String,Object> manifest,
			String worldSpace, String coordinateModel) {
			this.project = project; this.relative = relative;
			this.root = project.resolve(relative); this.manifest = manifest;
			this.worldSpace = worldSpace; this.coordinateModel = coordinateModel;
		}

		static PackageState read(Path project, String relative)
			throws IOException, WorldBuilderContractException {
			Path root = project.resolve(relative);
			Map<String,Object> manifest;
			try {
				manifest = WorldBuilderJsonDocuments.readObject(root.resolve("manifest.json"));
			} catch (WorldBuilderDiscoveryException malformed) {
				throw problem(WorldBuilderErrorCodes.MALFORMED_JSON, relative,
					"Working package manifest changed or is malformed.",
					"Restore the last complete saved working package.", malformed);
			}
			String worldSpace = text(map(list(manifest, "worldSpaces").get(0)), "id");
			PackageState state = new PackageState(project, relative, manifest,
				worldSpace, text(manifest, "coordinateModel"));
			for (Object raw : list(manifest, "levels")) {
				state.levels.add(Integer.valueOf(integer(map(raw), "level")));
			}
			for (Object raw : list(manifest, "terrainSectors")) {
				Map<String,Object> declaration = map(raw);
				int level = integer(declaration, "level");
				int sx = integer(declaration, "sectorX");
				int sy = integer(declaration, "sectorY");
				Path path = root.resolve(text(declaration, "path"));
				String encoding = text(declaration, "encoding");
				byte[] bytes = Files.readAllBytes(path);
				if (WorldBuilderRawLayeredTerrainCodec.V1_ENCODING.equals(encoding)) {
					bytes = WorldBuilderRawLayeredTerrainCodec.promoteV1(bytes);
					declaration.put("encoding",
						WorldBuilderRawLayeredTerrainCodec.V2_ENCODING);
				}
				state.sectors.put(key(level, sx, sy),
					new Sector(declaration, path, bytes));
			}
			for (Object raw : list(manifest, "placementSets")) {
				Map<String,Object> declaration = map(raw);
				int level = integer(declaration, "level");
				try {
					state.placements.put(Integer.valueOf(level),
						WorldBuilderJsonDocuments.readObject(
							root.resolve(text(declaration, "path"))));
				} catch (WorldBuilderDiscoveryException malformed) {
					throw problem(WorldBuilderErrorCodes.MALFORMED_JSON, relative,
						"Working placement payload changed or is malformed.",
						"Restore the last complete saved working package.", malformed);
				}
				state.placementDeclarations.put(Integer.valueOf(level), declaration);
			}
			return state;
		}

		byte[] tile(int level, int x, int y) {
			Sector sector = sectors.get(key(level, Math.floorDiv(x, 48), Math.floorDiv(y, 48)));
			if (sector == null) return null;
			int offset = (Math.floorMod(x, 48) * 48 + Math.floorMod(y, 48))
				* WorldBuilderRawLayeredTerrainCodec.V2_TILE_BYTES;
			return Arrays.copyOfRange(sector.bytes, offset,
				offset + WorldBuilderRawLayeredTerrainCodec.V2_TILE_BYTES);
		}

		boolean setTile(int level, int x, int y, byte[] value) {
			Sector sector = sectors.get(key(level, Math.floorDiv(x, 48), Math.floorDiv(y, 48)));
			if (sector == null) return false;
			if (value.length != WorldBuilderRawLayeredTerrainCodec.V2_TILE_BYTES) {
				throw new IllegalArgumentException("Region tile must use exact v2 width.");
			}
			int offset = (Math.floorMod(x, 48) * 48 + Math.floorMod(y, 48))
				* WorldBuilderRawLayeredTerrainCodec.V2_TILE_BYTES;
			System.arraycopy(value, 0, sector.bytes, offset,
				WorldBuilderRawLayeredTerrainCodec.V2_TILE_BYTES);
			return true;
		}

		Point firstUnavailable(int level, Footprint footprint) {
			for (long sectorX = Math.floorDiv(footprint.minimumX, 48);
				sectorX <= (long)Math.floorDiv(footprint.maximumX, 48); sectorX++) {
				for (long sectorY = Math.floorDiv(footprint.minimumY, 48);
					sectorY <= (long)Math.floorDiv(footprint.maximumY, 48); sectorY++) {
					if (sectors.containsKey(key(level, (int)sectorX, (int)sectorY))) continue;
					long firstX = Math.max((long)footprint.minimumX, sectorX * 48L);
					long firstY = Math.max((long)footprint.minimumY, sectorY * 48L);
					return new Point((int)firstX, (int)firstY);
				}
			}
			return null;
		}

		String writeAndValidate(
			WorldBuilderAdaptiveProjectLifecycle.VerifiedProject verified)
			throws IOException, WorldBuilderContractException {
			for (Sector sector : sectors.values()) {
				Files.write(sector.path, sector.bytes);
				sector.declaration.put("sha256", WorldBuilderHashes.sha256(sector.path));
			}
			for (Map.Entry<Integer,Map<String,Object>> entry : placements.entrySet()) {
				Map<String,Object> declaration = placementDeclarations.get(entry.getKey());
				upgradeNpcPlacementEncoding(entry.getValue(), declaration);
				Path path = root.resolve(text(declaration, "path"));
				Files.write(path, WorldBuilderJsonDocuments.pretty(entry.getValue())
					.getBytes(StandardCharsets.UTF_8));
				declaration.put("sha256", WorldBuilderHashes.sha256(path));
			}
			Files.write(root.resolve("manifest.json"),
				WorldBuilderJsonDocuments.pretty(manifest).getBytes(StandardCharsets.UTF_8));
			WorldBuilderGenericLayeredPackage inspected = WorldBuilderGenericLayeredPackage.inspect(
				WorldBuilderReadOnlyTarget.open(project), relative, "region-stage",
				verified.definitions);
			for (Path path : files(root).values()) {
				WorldBuilderAdaptiveDurability.forceFile(path);
			}
			WorldBuilderAdaptiveDurability.forceTreeDirectories(root);
			return inspected.fingerprintSha256;
		}

		private static void upgradeNpcPlacementEncoding(
			Map<String,Object> payload, Map<String,Object> declaration) {
			if (!"layered-world-placements-v3".equals(text(payload, "encoding"))) {
				return;
			}
			for (Object raw : list(payload, "npcs")) {
				Map<String,Object> npc = map(raw);
				if (!npc.containsKey("respawnSeconds")) {
					npc.put("respawnSeconds", Long.valueOf(-1));
				}
			}
			payload.put("encoding", "layered-world-placements-v4");
			payload.put("schemaVersion", Long.valueOf(4));
			declaration.put("encoding", "layered-world-placements-v4");
		}

		private static String key(int level, int x, int y) {
			return level + ":" + x + ":" + y;
		}
	}

	private static final class Sector {
		final Map<String,Object> declaration;
		final Path path;
		final byte[] bytes;
		Sector(Map<String,Object> declaration, Path path, byte[] bytes) {
			this.declaration = declaration; this.path = path; this.bytes = bytes;
		}
	}
}
