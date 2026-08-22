package com.openrsc.worldbuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/** Durable same-directory exchange for an editable v1 package and its v2 image. */
final class WorldBuilderWideElevationPromotionTransaction {
	static final String JOURNAL = "working/layered-world/.wide-elevation-promotion-v1.json";
	private static final String PREFIX = ".wide-elevation-";
	private static final int MAX_PARENT_ENTRIES = 64;
	private static final int MAX_TREE_ENTRIES = WorldBuilderContractLimits.MAX_INVENTORY_ENTRIES;
	private static final long MAX_TREE_BYTES = WorldBuilderContractLimits.MAX_INVENTORY_TOTAL_BYTES;

	interface MetadataReconciler {
		void installAfterFingerprint(Path project, String token,
			String beforeProjectSha256,
			String afterProjectSha256, String beforeWorkingSha256,
			String afterWorkingSha256)
			throws IOException, WorldBuilderContractException;
	}

	final Path project;
	final Path parent;
	final Path live;
	final Path stage;
	final Path original;
	final Path stageCleanup;
	final Path originalCleanup;
	final Map<String,Object> value;

	private WorldBuilderWideElevationPromotionTransaction(Path project,
		Map<String,Object> value) {
		this.project = project;
		this.parent = project.resolve("working/layered-world");
		this.live = parent.resolve("package");
		this.value = value;
		this.stage = parent.resolve((String)value.get("stageName"));
		this.original = parent.resolve((String)value.get("originalName"));
		this.stageCleanup = parent.resolve((String)value.get("stageCleanupName"));
		this.originalCleanup = parent.resolve((String)value.get("originalCleanupName"));
	}

	static Path createStage(Path project) throws IOException {
		Path parent = project.resolve("working/layered-world");
		String token = UUID.randomUUID().toString();
		Path stage = parent.resolve(PREFIX + "stage-" + token);
		copyTree(project.resolve("working/layered-world/package"), stage);
		return stage;
	}

	static WorldBuilderWideElevationPromotionTransaction prepare(Path project,
		Path stage, String beforeWorkingSha256, String afterWorkingSha256,
		String afterProjectSha256)
		throws IOException, WorldBuilderContractException {
		Path parent = project.resolve("working/layered-world");
		String stageName = stage.getFileName().toString();
		if (!stage.getParent().equals(parent)
			|| !stageName.matches("\\.wide-elevation-stage-[0-9a-f-]{36}")) {
			throw problem("Promotion stage is outside its bounded transaction directory.");
		}
		String token = stageName.substring((PREFIX + "stage-").length());
		requireMetadataTempsAbsent(project, token);
		Map<String,Object> value = new LinkedHashMap<String,Object>();
		value.put("schemaVersion", Long.valueOf(1L));
		value.put("token", token);
		value.put("stageName", stageName);
		value.put("originalName", PREFIX + "original-" + token);
		value.put("stageCleanupName", PREFIX + "cleanup-stage-" + token);
		value.put("originalCleanupName", PREFIX + "cleanup-original-" + token);
		value.put("beforeTreeSha256", treeFingerprint(
			project.resolve("working/layered-world/package")));
		value.put("afterTreeSha256", treeFingerprint(stage));
		value.put("beforeWorkingSha256", requireHash(beforeWorkingSha256));
		value.put("afterWorkingSha256", requireHash(afterWorkingSha256));
		value.put("beforeProjectSha256", WorldBuilderHashes.sha256(
			project.resolve("project.json")));
		value.put("afterProjectSha256", requireHash(afterProjectSha256));
		WorldBuilderWideElevationPromotionTransaction transaction =
			new WorldBuilderWideElevationPromotionTransaction(project, value);
		transaction.requireNoConflictingArtifacts(false);
		WorldBuilderAdaptiveDurability.forceTree(stage);
		byte[] bytes = WorldBuilderJsonDocuments.pretty(value)
			.getBytes(StandardCharsets.UTF_8);
		Path journal = project.resolve(JOURNAL);
		Files.write(journal, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
		WorldBuilderAdaptiveDurability.forceFile(journal);
		WorldBuilderAdaptiveDurability.forceDirectory(parent);
		return transaction;
	}

	void moveOriginalAside() throws IOException {
		moveNew(live, original);
		WorldBuilderAdaptiveDurability.forceDirectory(parent);
	}

	void installWide() throws IOException {
		moveNew(stage, live);
		WorldBuilderAdaptiveDurability.forceDirectory(parent);
	}

	void finishCommitted() throws IOException, WorldBuilderContractException {
		if (!treeEquals(live, hash("afterTreeSha256"))) {
			throw problem("Installed wide-elevation package does not match its journal.");
		}
		cleanup(original, originalCleanup, hash("beforeTreeSha256"));
		removeJournal();
	}

	static void recover(Path project, MetadataReconciler reconciler)
		throws IOException, WorldBuilderContractException {
		Path parent = project.resolve("working/layered-world");
		Path journal = project.resolve(JOURNAL);
		if (!Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)
			|| Files.isSymbolicLink(parent)) {
			throw problem("Promotion transaction directory is missing or unsafe.");
		}
		boolean hasArtifact = hasArtifact(parent);
		if (!Files.exists(journal, LinkOption.NOFOLLOW_LINKS)) {
			if (hasArtifact) throw problem(
				"Wide-elevation transaction artifact exists without its durable journal.");
			return;
		}
		if (!Files.isRegularFile(journal, LinkOption.NOFOLLOW_LINKS)
			|| Files.isSymbolicLink(journal)
			|| Files.size(journal) > WorldBuilderContractLimits.MAX_JSON_BYTES) {
			throw problem("Wide-elevation promotion journal is not one bounded regular file.");
		}
		Map<String,Object> value;
		try {
			value = WorldBuilderJsonDocuments.readObject(journal);
		} catch (WorldBuilderDiscoveryException malformed) {
			throw problem("Wide-elevation promotion journal is malformed.", malformed);
		}
		validate(value);
		WorldBuilderWideElevationPromotionTransaction transaction =
			new WorldBuilderWideElevationPromotionTransaction(project, value);
		transaction.requireNoConflictingArtifacts(true);
		transaction.requireNoConflictingMetadataArtifacts();
		transaction.recover(reconciler);
	}

	private void recover(MetadataReconciler reconciler)
		throws IOException, WorldBuilderContractException {
		String beforeTree = hash("beforeTreeSha256");
		String afterTree = hash("afterTreeSha256");
		boolean liveBefore = treeEquals(live, beforeTree);
		boolean liveAfter = treeEquals(live, afterTree);
		boolean stageAfter = treeEquals(stage, afterTree);
		boolean originalBefore = treeEquals(original, beforeTree);
		boolean liveAbsent = !Files.exists(live, LinkOption.NOFOLLOW_LINKS);
		boolean stageAbsent = !Files.exists(stage, LinkOption.NOFOLLOW_LINKS);
		boolean originalAbsent = !Files.exists(original, LinkOption.NOFOLLOW_LINKS);

		if (liveBefore && stageAfter && originalAbsent) {
			requireProjectBefore();
			cleanup(stage, stageCleanup, afterTree);
			removeJournal();
			return;
		}
		if (liveAbsent && originalBefore && stageAfter) {
			requireProjectBefore();
			moveNew(original, live);
			WorldBuilderAdaptiveDurability.forceDirectory(parent);
			cleanup(stage, stageCleanup, afterTree);
			removeJournal();
			return;
		}
		if (liveAfter && originalBefore && stageAbsent) {
			reconciler.installAfterFingerprint(project, text(value, "token"),
				hash("beforeProjectSha256"),
				hash("afterProjectSha256"), hash("beforeWorkingSha256"),
				hash("afterWorkingSha256"));
			cleanup(original, originalCleanup, beforeTree);
			removeJournal();
			return;
		}
		if (liveBefore && stageAbsent && originalAbsent
			&& !Files.exists(stageCleanup, LinkOption.NOFOLLOW_LINKS)) {
			requireProjectBefore();
			removeJournal();
			return;
		}
		if (liveAfter && stageAbsent && originalAbsent
			&& !Files.exists(originalCleanup, LinkOption.NOFOLLOW_LINKS)) {
			reconciler.installAfterFingerprint(project, text(value, "token"),
				hash("beforeProjectSha256"),
				hash("afterProjectSha256"), hash("beforeWorkingSha256"),
				hash("afterWorkingSha256"));
			removeJournal();
			return;
		}
		if (liveBefore && originalAbsent && Files.exists(stageCleanup,
			LinkOption.NOFOLLOW_LINKS)) {
			requireProjectBefore();
			deleteCleanup(stageCleanup);
			removeJournal();
			return;
		}
		if (liveAfter && stageAbsent && Files.exists(originalCleanup,
			LinkOption.NOFOLLOW_LINKS)) {
			reconciler.installAfterFingerprint(project, text(value, "token"),
				hash("beforeProjectSha256"),
				hash("afterProjectSha256"), hash("beforeWorkingSha256"),
				hash("afterWorkingSha256"));
			deleteCleanup(originalCleanup);
			removeJournal();
			return;
		}
		throw problem("Promotion artifacts do not prove one complete v1 or v2 package state.");
	}

	private void requireProjectBefore() throws IOException, WorldBuilderContractException {
		if (!WorldBuilderHashes.sha256(project.resolve("project.json"))
			.equals(hash("beforeProjectSha256"))) {
			throw problem("Project metadata changed while recovery requires the original v1 package.");
		}
	}

	private void cleanup(Path source, Path cleanup, String expectedHash)
		throws IOException, WorldBuilderContractException {
		boolean sourcePresent = Files.exists(source, LinkOption.NOFOLLOW_LINKS);
		boolean cleanupPresent = Files.exists(cleanup, LinkOption.NOFOLLOW_LINKS);
		if (sourcePresent && cleanupPresent) throw problem(
			"Promotion cleanup source and quarantine both exist.");
		if (sourcePresent) {
			if (!treeEquals(source, expectedHash)) throw problem(
				"Promotion cleanup source does not match its journal identity.");
			moveNew(source, cleanup);
			WorldBuilderAdaptiveDurability.forceDirectory(parent);
			cleanupPresent = true;
		}
		if (cleanupPresent) deleteCleanup(cleanup);
	}

	private void deleteCleanup(Path cleanup)
		throws IOException, WorldBuilderContractException {
		if (!cleanup.equals(stageCleanup) && !cleanup.equals(originalCleanup)) {
			throw problem("Promotion cleanup path is outside journal authority.");
		}
		deleteTree(cleanup);
		WorldBuilderAdaptiveDurability.forceDirectory(parent);
	}

	private void removeJournal() throws IOException {
		Files.delete(project.resolve(JOURNAL));
		WorldBuilderAdaptiveDurability.forceDirectory(parent);
	}

	private void requireNoConflictingArtifacts(boolean includeJournal)
		throws IOException, WorldBuilderContractException {
		Set<Path> allowed = new HashSet<Path>(Arrays.asList(stage, original,
			stageCleanup, originalCleanup));
		if (includeJournal) allowed.add(project.resolve(JOURNAL));
		int count = 0;
		try (DirectoryStream<Path> entries = Files.newDirectoryStream(parent)) {
			for (Path entry : entries) {
				if (++count > MAX_PARENT_ENTRIES) throw problem(
					"Promotion transaction directory exceeds its recovery scan bound.");
				if (!entry.getFileName().toString().startsWith(PREFIX)) continue;
				if (!allowed.contains(entry.normalize())) throw problem(
					"Unjournaled wide-elevation transaction artifact makes recovery ambiguous.");
			}
		}
	}

	private static boolean hasArtifact(Path parent)
		throws IOException, WorldBuilderContractException {
		int count = 0;
		try (DirectoryStream<Path> entries = Files.newDirectoryStream(parent)) {
			for (Path entry : entries) {
				if (++count > MAX_PARENT_ENTRIES) throw problem(
					"Promotion transaction directory exceeds its recovery scan bound.");
				if (entry.getFileName().toString().startsWith(PREFIX)) return true;
			}
		}
		return false;
	}

	private static void requireMetadataTempsAbsent(Path project, String token)
		throws IOException, WorldBuilderContractException {
		Path install = project.getParent() == null ? null : project.getParent().getParent();
		if (install == null || hasMetadataArtifact(project, null, 128)
			|| hasMetadataArtifact(install, null,
				WorldBuilderContractLimits.MAX_PROJECTS + 32)) {
			throw problem("Promotion metadata staging path already exists.");
		}
	}

	private void requireNoConflictingMetadataArtifacts()
		throws IOException, WorldBuilderContractException {
		String token = text(value, "token");
		Path install = project.getParent() == null ? null : project.getParent().getParent();
		if (install == null) throw problem("Promotion install root is missing.");
		Set<Path> projectAllowed = new HashSet<Path>(Arrays.asList(
			project.resolve(PREFIX + "project-" + token + ".new")));
		Set<Path> installAllowed = new HashSet<Path>(Arrays.asList(
			install.resolve(PREFIX + "registry-" + token + ".new"),
			install.resolve(PREFIX + "active-" + token + ".new")));
		hasMetadataArtifact(project, projectAllowed, 128);
		hasMetadataArtifact(install, installAllowed,
			WorldBuilderContractLimits.MAX_PROJECTS + 32);
	}

	private static boolean hasMetadataArtifact(Path directory, Set<Path> allowed,
		int bound) throws IOException, WorldBuilderContractException {
		int count = 0;
		boolean found = false;
		try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory)) {
			for (Path entry : entries) {
				if (++count > bound) throw problem(
					"Promotion metadata directory exceeds its recovery scan bound.");
				if (!entry.getFileName().toString().startsWith(PREFIX)) continue;
				found = true;
				if (allowed == null || !allowed.contains(entry.normalize())) throw problem(
					"Unjournaled promotion metadata artifact makes recovery ambiguous.");
				if (!Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)
					|| Files.isSymbolicLink(entry)) throw problem(
					"Journaled promotion metadata artifact is unsafe.");
			}
		}
		return found;
	}

	private String hash(String key) throws WorldBuilderContractException {
		return requireHash(value.get(key));
	}

	private static void validate(Map<String,Object> value)
		throws WorldBuilderContractException {
		Set<String> expected = new HashSet<String>(Arrays.asList("schemaVersion", "token",
			"stageName", "originalName", "stageCleanupName", "originalCleanupName",
			"beforeTreeSha256", "afterTreeSha256", "beforeWorkingSha256",
			"afterWorkingSha256", "beforeProjectSha256", "afterProjectSha256"));
		if (!value.keySet().equals(expected)
			|| !Long.valueOf(1L).equals(value.get("schemaVersion"))) {
			throw problem("Promotion journal fields are not the exact v1 contract.");
		}
		String token = text(value, "token");
		if (!token.matches("[0-9a-f-]{36}")
			|| !(PREFIX + "stage-" + token).equals(text(value, "stageName"))
			|| !(PREFIX + "original-" + token).equals(text(value, "originalName"))
			|| !(PREFIX + "cleanup-stage-" + token).equals(text(value, "stageCleanupName"))
			|| !(PREFIX + "cleanup-original-" + token).equals(
				text(value, "originalCleanupName"))) {
			throw problem("Promotion journal artifact names are invalid.");
		}
		for (String key : Arrays.asList("beforeTreeSha256", "afterTreeSha256",
			"beforeWorkingSha256", "afterWorkingSha256", "beforeProjectSha256",
			"afterProjectSha256")) {
			requireHash(value.get(key));
		}
	}

	private static String text(Map<String,Object> value, String key)
		throws WorldBuilderContractException {
		Object raw = value.get(key);
		if (!(raw instanceof String)) throw problem("Promotion journal text field is invalid.");
		return (String)raw;
	}

	private static String requireHash(Object value)
		throws WorldBuilderContractException {
		if (!(value instanceof String) || !WorldBuilderBoundedInventory.isHash((String)value)) {
			throw problem("Promotion journal hash identity is invalid.");
		}
		return (String)value;
	}

	private static boolean treeEquals(Path root, String expected) throws IOException {
		return Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)
			&& !Files.isSymbolicLink(root) && expected.equals(treeFingerprint(root));
	}

	private static String treeFingerprint(final Path root) throws IOException {
		final Map<String,String> inventory = new TreeMap<String,String>();
		final long[] total = new long[] {0L};
		final int[] count = new int[] {0};
		Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
			@Override public FileVisitResult preVisitDirectory(Path directory,
				BasicFileAttributes attributes) throws IOException {
				if (!attributes.isDirectory() || Files.isSymbolicLink(directory)
					|| ++count[0] > MAX_TREE_ENTRIES) {
					throw new IOException("unsafe promotion package directory");
				}
				return FileVisitResult.CONTINUE;
			}
			@Override public FileVisitResult visitFile(Path file,
				BasicFileAttributes attributes) throws IOException {
				if (!attributes.isRegularFile() || Files.isSymbolicLink(file)
					|| ++count[0] > MAX_TREE_ENTRIES
					|| attributes.size() > WorldBuilderContractLimits.MAX_INVENTORY_FILE_BYTES
					|| (total[0] += attributes.size()) > MAX_TREE_BYTES) {
					throw new IOException("promotion package inventory exceeds its safety bound");
				}
				String relative = root.relativize(file).toString().replace('\\', '/');
				inventory.put(relative, attributes.size() + "\0"
					+ WorldBuilderHashes.sha256(file));
				return FileVisitResult.CONTINUE;
			}
		});
		StringBuilder value = new StringBuilder();
		for (Map.Entry<String,String> entry : inventory.entrySet()) {
			value.append(entry.getKey()).append('\0').append(entry.getValue()).append('\n');
		}
		return WorldBuilderHashes.sha256(value.toString().getBytes(StandardCharsets.UTF_8));
	}

	private static void copyTree(final Path source, final Path destination)
		throws IOException {
		if (!Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)
			|| Files.isSymbolicLink(source)
			|| Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
			throw new IOException("promotion source or stage is unsafe");
		}
		final int[] count = new int[] {0};
		final long[] total = new long[] {0L};
		Files.walkFileTree(source, new SimpleFileVisitor<Path>() {
			@Override public FileVisitResult preVisitDirectory(Path directory,
				BasicFileAttributes attributes) throws IOException {
				if (!attributes.isDirectory() || Files.isSymbolicLink(directory)
					|| ++count[0] > MAX_TREE_ENTRIES) {
					throw new IOException("unsafe promotion package directory");
				}
				Files.createDirectory(destination.resolve(source.relativize(directory)));
				return FileVisitResult.CONTINUE;
			}
			@Override public FileVisitResult visitFile(Path file,
				BasicFileAttributes attributes) throws IOException {
				if (!attributes.isRegularFile() || Files.isSymbolicLink(file)
					|| ++count[0] > MAX_TREE_ENTRIES
					|| attributes.size() > WorldBuilderContractLimits.MAX_INVENTORY_FILE_BYTES
					|| (total[0] += attributes.size()) > MAX_TREE_BYTES) {
					throw new IOException("unsafe promotion package file");
				}
				Files.copy(file, destination.resolve(source.relativize(file)),
					StandardCopyOption.COPY_ATTRIBUTES);
				return FileVisitResult.CONTINUE;
			}
		});
	}

	private static void moveNew(Path source, Path destination) throws IOException {
		if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
			throw new IOException("promotion destination already exists");
		}
		try {
			Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE);
		} catch (AtomicMoveNotSupportedException unsupported) {
			throw new IOException("promotion requires same-directory atomic moves", unsupported);
		}
	}

	private static void deleteTree(Path root)
		throws IOException, WorldBuilderContractException {
		if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)
			|| Files.isSymbolicLink(root)) throw problem(
			"Promotion cleanup quarantine is not a real directory.");
		final int[] count = new int[] {0};
		final long[] total = new long[] {0L};
		Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
			@Override public FileVisitResult preVisitDirectory(Path directory,
				BasicFileAttributes attributes) throws IOException {
				if (!attributes.isDirectory() || Files.isSymbolicLink(directory)
					|| ++count[0] > MAX_TREE_ENTRIES) {
					throw new IOException("promotion cleanup exceeds its safety bound");
				}
				return FileVisitResult.CONTINUE;
			}
			@Override public FileVisitResult visitFile(Path file,
				BasicFileAttributes attributes) throws IOException {
				if (!attributes.isRegularFile() || Files.isSymbolicLink(file)
					|| ++count[0] > MAX_TREE_ENTRIES
					|| (total[0] += attributes.size()) > MAX_TREE_BYTES) {
					throw new IOException("promotion cleanup exceeds its safety bound");
				}
				Files.delete(file);
				return FileVisitResult.CONTINUE;
			}
			@Override public FileVisitResult postVisitDirectory(Path directory,
				IOException failure) throws IOException {
				if (failure != null) throw failure;
				Files.delete(directory);
				return FileVisitResult.CONTINUE;
			}
		});
	}

	private static WorldBuilderContractException problem(String message) {
		return new WorldBuilderContractException(WorldBuilderErrorCodes.RECOVERY_REQUIRED,
			"wide-elevation-promotion", JOURNAL, false, message,
			"Preserve the journal and its exact artifacts; recovery refuses ambiguous state.");
	}

	private static WorldBuilderContractException problem(String message, Throwable cause) {
		return new WorldBuilderContractException(WorldBuilderErrorCodes.RECOVERY_REQUIRED,
			"wide-elevation-promotion", JOURNAL, false, message,
			"Preserve the journal and its exact artifacts; recovery refuses ambiguous state.",
			cause);
	}
}
