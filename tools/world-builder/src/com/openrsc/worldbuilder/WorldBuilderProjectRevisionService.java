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
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Immutable, content-addressed creator revision history for one project. */
final class WorldBuilderProjectRevisionService {
	static final String ROOT = "revisions";
	static final String ENTRIES = ROOT + "/entries";
	static final String OBJECTS = ROOT + "/objects";
	static final String EXPORTS = "revision-exports";
	private static final String OPERATION = "project-revision-history";
	private static final String ZERO_HASH =
		"0000000000000000000000000000000000000000000000000000000000000000";

	Revision create(Path requestedProject, String reason, String description,
		boolean onlyWhenChanged) throws IOException, WorldBuilderContractException {
		Path project = requireProject(requestedProject);
		try (WorldBuilderAdaptiveProjectLock ignored =
			WorldBuilderAdaptiveProjectLock.acquire(project, OPERATION)) {
			WorldBuilderAdaptiveProjectLifecycle.VerifiedProject verified =
				WorldBuilderAdaptiveProjectLifecycle.verifyProjectDirectory(project, true);
			return createLocked(verified, reason, description, onlyWhenChanged);
		}
	}

	List<Revision> list(Path requestedProject)
		throws IOException, WorldBuilderContractException {
		Path project = requireProject(requestedProject);
		try (WorldBuilderAdaptiveProjectLock ignored =
			WorldBuilderAdaptiveProjectLock.acquire(project, OPERATION)) {
			WorldBuilderAdaptiveProjectLifecycle.verifyProjectDirectory(project, true);
			return listLocked(project, true);
		}
	}

	RestoreResult restore(Path requestedProject, String revisionId)
		throws IOException, WorldBuilderContractException {
		Path project = requireProject(requestedProject);
		try (WorldBuilderAdaptiveProjectLock ignored =
			WorldBuilderAdaptiveProjectLock.acquire(project, OPERATION)) {
			WorldBuilderAdaptiveProjectLifecycle.VerifiedProject verified =
				WorldBuilderAdaptiveProjectLifecycle.verifyProjectDirectory(project, true);
			Revision revision = requireRevision(project, revisionId, true);
			if (!verified.projectId.equals(revision.projectId)) throw problem(
				WorldBuilderErrorCodes.SOURCE_CORRUPT, revisionId,
				"Project revision belongs to a different project.",
				"Restore a revision listed by this exact selected project.");
			if (verified.working.fingerprintSha256.equals(revision.workingFingerprint)) {
				return new RestoreResult(revision, null, false);
			}
			Revision safeguard = createLocked(verified, "before-restore",
				"Automatic backup before loading revision " + revision.revisionId, false);
			Path live = project.resolve(
				WorldBuilderAdaptiveProjectLifecycle.WORKING_PACKAGE_DIRECTORY);
			Path parent = live.getParent();
			String nonce = UUID.randomUUID().toString();
			Path stage = parent.resolve(".revision-restore-" + nonce);
			Path previous = parent.resolve(".revision-previous-" + nonce);
			materialize(project, revision, stage);
			WorldBuilderGenericLayeredPackage candidate =
				WorldBuilderGenericLayeredPackage.inspect(
					WorldBuilderReadOnlyTarget.open(project),
					project.relativize(stage).toString().replace('\\', '/'),
					"revision-restore", verified.definitions);
			if (!revision.workingFingerprint.equals(candidate.fingerprintSha256)) {
				deleteTree(stage);
				throw problem(WorldBuilderErrorCodes.SOURCE_CORRUPT, revisionId,
					"Materialized revision package does not match its recorded identity.",
					"Keep the current project and restore the exact revision objects.");
			}
			boolean liveMoved = false;
			boolean restoredPublished = false;
			try {
				moveNew(live, previous);
				liveMoved = true;
				moveNew(stage, live);
				restoredPublished = true;
				WorldBuilderAdaptiveProjectLifecycle.ProjectResult saved =
					new WorldBuilderAdaptiveProjectLifecycle()
						.saveAfterRegionPublication(project);
				if (!revision.workingFingerprint.equals(
					saved.workingFingerprintSha256)) throw problem(
					WorldBuilderErrorCodes.RECOVERY_REQUIRED, revisionId,
					"Restored package published with an unexpected project identity.",
					"Keep the project closed while the exact prior package is restored.");
				deleteTree(previous);
				return new RestoreResult(revision, safeguard, true);
			} catch (Throwable failure) {
				try {
					if (restoredPublished && Files.exists(live, LinkOption.NOFOLLOW_LINKS)) {
						deleteTree(live);
					}
					if (liveMoved && Files.exists(previous, LinkOption.NOFOLLOW_LINKS)) {
						moveNew(previous, live);
						new WorldBuilderAdaptiveProjectLifecycle()
							.saveAfterRegionPublication(project);
					}
					if (Files.exists(stage, LinkOption.NOFOLLOW_LINKS)) deleteTree(stage);
				} catch (Exception rollbackFailure) {
					throw problem(WorldBuilderErrorCodes.RECOVERY_REQUIRED, revisionId,
						"Revision restore failed and automatic rollback could not prove the prior project state.",
						"Keep the project closed and preserve revisions plus temporary restore directories.",
						rollbackFailure);
				}
				if (failure instanceof WorldBuilderContractException) {
					throw (WorldBuilderContractException)failure;
				}
				if (failure instanceof IOException) throw (IOException)failure;
				throw new IOException("Revision restore failed before publication", failure);
			}
		}
	}

	Path export(Path requestedProject, String revisionId)
		throws IOException, WorldBuilderContractException {
		Path project = requireProject(requestedProject);
		try (WorldBuilderAdaptiveProjectLock ignored =
			WorldBuilderAdaptiveProjectLock.acquire(project, OPERATION)) {
			WorldBuilderAdaptiveProjectLifecycle.verifyProjectDirectory(project, true);
			Revision revision = requireRevision(project, revisionId, true);
			Path exports = project.resolve(EXPORTS);
			requireOrCreateDirectory(exports);
			Path stage = exports.resolve(".revision-export-" + UUID.randomUUID());
			Path destination = exports.resolve(revision.revisionId + "-" + UUID.randomUUID());
			Files.createDirectory(stage);
			try {
				materialize(project, revision, stage.resolve("package"));
				Files.copy(revision.manifestPath, stage.resolve("revision.json"));
				WorldBuilderAdaptiveDurability.forceTreeDirectories(stage);
				moveNew(stage, destination);
				return destination;
			} catch (Throwable failure) {
				if (Files.exists(stage, LinkOption.NOFOLLOW_LINKS)) deleteTree(stage);
				if (failure instanceof IOException) throw (IOException)failure;
				if (failure instanceof WorldBuilderContractException) {
					throw (WorldBuilderContractException)failure;
				}
				throw new IOException("Revision export failed", failure);
			}
		}
	}

	private Revision createLocked(
		WorldBuilderAdaptiveProjectLifecycle.VerifiedProject project,
		String reason, String description, boolean onlyWhenChanged)
		throws IOException, WorldBuilderContractException {
		if (!("editing-session".equals(reason) || "before-restore".equals(reason)
			|| "explicit-backup".equals(reason))) throw problem(
			WorldBuilderErrorCodes.CONTRACT_VALUE_INVALID, "reason",
			"Project revision reason is unsupported.",
			"Use editing-session, before-restore, or explicit-backup.");
		String detail = description == null ? "" : description.trim();
		if (detail.length() > 1024) throw problem(
			WorldBuilderErrorCodes.CONTRACT_LIMIT_EXCEEDED, "description",
			"Project revision description is too long.",
			"Use at most 1024 characters.");
		List<Revision> existing = listLocked(project.projectRoot, true);
		Revision parent = existing.isEmpty() ? null : existing.get(0);
		if (onlyWhenChanged && parent != null
			&& parent.workingFingerprint.equals(project.working.fingerprintSha256)) {
			return parent;
		}
		Path working = project.projectRoot.resolve(
			WorldBuilderAdaptiveProjectLifecycle.WORKING_PACKAGE_DIRECTORY);
		List<FileRecord> files = inventory(working);
		publishObjects(project.projectRoot, working, files);
		String revisionId = UUID.randomUUID().toString();
		Map<String,Object> document = new LinkedHashMap<String,Object>();
		document.put("schemaVersion", Long.valueOf(1L));
		document.put("manifestType", "world-builder-project-revision");
		document.put("revisionId", revisionId);
		document.put("projectId", project.projectId);
		document.put("createdAt", Instant.now().toString());
		document.put("reason", reason);
		document.put("description", detail);
		document.put("parentRevisionId", parent == null ? "" : parent.revisionId);
		Map<String,Object> fingerprints = WorldBuilderAdaptiveExporter.object(
			project.manifest.get("fingerprints"), "fingerprints");
		document.put("sourceSha256", WorldBuilderAdaptiveExporter.string(
			fingerprints, "sourceSha256"));
		document.put("definitionsSha256", WorldBuilderAdaptiveExporter.string(
			fingerprints, "definitionsSha256"));
		document.put("runtimeSha256", WorldBuilderAdaptiveExporter.string(
			fingerprints, "runtimeSha256"));
		document.put("workingPackageFingerprintSha256",
			project.working.fingerprintSha256);
		long total = 0L;
		List<Object> records = new ArrayList<Object>();
		for (int index = 0; index < files.size(); index++) {
			FileRecord file = files.get(index);
			total = Math.addExact(total, file.size);
			records.add(file.toJson(index));
		}
		document.put("fileCount", Long.valueOf(files.size()));
		document.put("totalBytes", Long.valueOf(total));
		document.put("files", records);
		document.put("revisionFingerprintSha256", ZERO_HASH);
		WorldBuilderAdaptiveExporter.bindFingerprint(
			document, "revisionFingerprintSha256");
		WorldBuilderAdaptiveContracts.validateParsed(
			WorldBuilderAdaptiveContracts.Kind.PROJECT_REVISION, document);

		Path entries = project.projectRoot.resolve(ENTRIES);
		requireOrCreateDirectory(project.projectRoot.resolve(ROOT));
		requireOrCreateDirectory(entries);
		Path stage = entries.resolve(".revision-stage-" + revisionId);
		Path destination = entries.resolve(revisionId);
		Files.createDirectory(stage);
		try {
			writeNew(stage.resolve("revision.json"),
				WorldBuilderJsonDocuments.pretty(document).getBytes(StandardCharsets.UTF_8));
			WorldBuilderAdaptiveDurability.forceTreeDirectories(stage);
			moveNew(stage, destination);
		} catch (Throwable failure) {
			if (Files.exists(stage, LinkOption.NOFOLLOW_LINKS)) deleteTree(stage);
			if (failure instanceof IOException) throw (IOException)failure;
			if (failure instanceof WorldBuilderContractException) {
				throw (WorldBuilderContractException)failure;
			}
			throw new IOException("Revision publication failed", failure);
		}
		return requireRevision(project.projectRoot, revisionId, true);
	}

	private List<Revision> listLocked(Path project, boolean verifyObjects)
		throws IOException, WorldBuilderContractException {
		Path entries = project.resolve(ENTRIES);
		if (!Files.exists(entries, LinkOption.NOFOLLOW_LINKS)) {
			return Collections.emptyList();
		}
		if (!Files.isDirectory(entries, LinkOption.NOFOLLOW_LINKS)
			|| Files.isSymbolicLink(entries)) throw problem(
			WorldBuilderErrorCodes.UNSAFE_PATH, ENTRIES,
			"Project revision entries directory is unsafe.",
			"Restore the complete real revisions directory.");
		List<Revision> result = new ArrayList<Revision>();
		try (java.nio.file.DirectoryStream<Path> stream = Files.newDirectoryStream(entries)) {
			for (Path entry : stream) {
				String name = entry.getFileName().toString();
				if (name.startsWith(".revision-stage-")) continue;
				if (!name.matches("[0-9a-f-]{36}")) throw problem(
					WorldBuilderErrorCodes.UNSAFE_PATH, ENTRIES + "/" + name,
					"Unexpected content exists in project revision entries.",
					"Preserve and inspect the unexpected path before using revision history.");
				result.add(requireRevision(project, name, verifyObjects));
			}
		}
		Collections.sort(result, new Comparator<Revision>() {
			@Override public int compare(Revision left, Revision right) {
				int created = right.createdAt.compareTo(left.createdAt);
				return created != 0 ? created : right.revisionId.compareTo(left.revisionId);
			}
		});
		return Collections.unmodifiableList(result);
	}

	private Revision requireRevision(Path project, String requestedId,
		boolean verifyObjects) throws IOException, WorldBuilderContractException {
		String id;
		try {
			id = UUID.fromString(requestedId).toString();
		} catch (Exception malformed) {
			throw problem(WorldBuilderErrorCodes.CONTRACT_VALUE_INVALID, "revisionId",
				"Project revision ID is invalid.", "Choose a listed project revision.");
		}
		Path manifest = WorldBuilderPortablePath.resolveContained(project,
			ENTRIES + "/" + id + "/revision.json", OPERATION);
		WorldBuilderAdaptiveContracts.read(
			WorldBuilderAdaptiveContracts.Kind.PROJECT_REVISION, manifest);
		Map<String,Object> document;
		try {
			document = WorldBuilderJsonDocuments.readObject(manifest);
		} catch (WorldBuilderDiscoveryException malformed) {
			throw problem(WorldBuilderErrorCodes.SOURCE_CORRUPT,
				ENTRIES + "/" + id + "/revision.json",
				"Project revision manifest is malformed.",
				"Restore the exact revision manifest and objects.");
		}
		WorldBuilderAdaptiveExporter.requireFingerprint(
			document, "revisionFingerprintSha256");
		if (!id.equals(WorldBuilderAdaptiveExporter.string(document, "revisionId"))) {
			throw problem(WorldBuilderErrorCodes.SOURCE_CORRUPT, id,
				"Project revision directory and manifest identity disagree.",
				"Restore the exact revision entry.");
		}
		List<WorldBuilderBoundedInventory.Record> files =
			WorldBuilderBoundedInventory.read(document.get("files"), OPERATION, 1, false);
		if (verifyObjects) {
			for (WorldBuilderBoundedInventory.Record file : files) {
				Path object = objectPath(project, file.sha256);
				requireObject(object, file.size, file.sha256);
			}
		}
		return new Revision(manifest,
			WorldBuilderAdaptiveExporter.string(document, "revisionId"),
			WorldBuilderAdaptiveExporter.string(document, "projectId"),
			WorldBuilderAdaptiveExporter.string(document, "createdAt"),
			WorldBuilderAdaptiveExporter.string(document, "reason"),
			WorldBuilderAdaptiveExporter.string(document, "description"),
			WorldBuilderAdaptiveExporter.string(document, "parentRevisionId"),
			WorldBuilderAdaptiveExporter.string(
				document, "workingPackageFingerprintSha256"),
			WorldBuilderAdaptiveExporter.integer(document, "fileCount"),
			WorldBuilderAdaptiveExporter.integer(document, "totalBytes"), files);
	}

	private void publishObjects(Path project, Path working, List<FileRecord> files)
		throws IOException, WorldBuilderContractException {
		requireOrCreateDirectory(project.resolve(ROOT));
		requireOrCreateDirectory(project.resolve(OBJECTS));
		for (FileRecord file : files) {
			Path source = WorldBuilderPortablePath.resolveContained(
				working, file.relativePath, OPERATION);
			Path destination = objectPath(project, file.sha256);
			requireOrCreateDirectory(destination.getParent());
			if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
				requireObject(destination, file.size, file.sha256);
				continue;
			}
			Path temporary = destination.getParent().resolve(
				"." + file.sha256 + ".stage-" + UUID.randomUUID());
			Files.copy(source, temporary);
			WorldBuilderAdaptiveDurability.forceFile(temporary);
			requireObject(temporary, file.size, file.sha256);
			try {
				WorldBuilderAdaptiveAtomicFiles.moveNew(
					temporary, destination, OPERATION, file.relativePath);
			} catch (WorldBuilderContractException collision) {
				if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
					Files.deleteIfExists(temporary);
					requireObject(destination, file.size, file.sha256);
				} else throw collision;
			}
		}
	}

	private void materialize(Path project, Revision revision, Path destination)
		throws IOException, WorldBuilderContractException {
		if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) throw problem(
			WorldBuilderErrorCodes.UNSAFE_PATH, destination.getFileName().toString(),
			"Revision materialization destination already exists.",
			"Preserve the unexpected path and retry with a fresh operation.");
		Files.createDirectory(destination);
		try {
			for (WorldBuilderBoundedInventory.Record file : revision.files) {
				Path output = WorldBuilderPortablePath.resolveContained(
					destination, file.relativePath, OPERATION);
				Files.createDirectories(output.getParent());
				Path object = objectPath(project, file.sha256);
				requireObject(object, file.size, file.sha256);
				Files.copy(object, output);
				WorldBuilderAdaptiveDurability.forceFile(output);
				requireObject(output, file.size, file.sha256);
			}
			WorldBuilderAdaptiveDurability.forceTreeDirectories(destination);
		} catch (Throwable failure) {
			deleteTree(destination);
			if (failure instanceof IOException) throw (IOException)failure;
			if (failure instanceof WorldBuilderContractException) {
				throw (WorldBuilderContractException)failure;
			}
			throw new IOException("Revision materialization failed", failure);
		}
	}

	private static List<FileRecord> inventory(Path working)
		throws IOException, WorldBuilderContractException {
		final List<FileRecord> result = new ArrayList<FileRecord>();
		Files.walkFileTree(working, new SimpleFileVisitor<Path>() {
			@Override public FileVisitResult preVisitDirectory(Path directory,
				BasicFileAttributes attributes) throws IOException {
				if (attributes.isSymbolicLink()) throw new IOException(
					"Working package contains a linked directory");
				return FileVisitResult.CONTINUE;
			}
			@Override public FileVisitResult visitFile(Path file,
				BasicFileAttributes attributes) throws IOException {
				if (!attributes.isRegularFile() || attributes.isSymbolicLink()) {
					throw new IOException("Working package contains an unsafe file");
				}
				try {
					String relative = working.relativize(file).toString().replace('\\', '/');
					WorldBuilderPortablePath.require(relative, OPERATION);
					WorldBuilderAdaptiveExporter.rejectHardLink(file, relative);
					result.add(new FileRecord(relative, attributes.size(),
						WorldBuilderHashes.sha256(file)));
				} catch (WorldBuilderContractException invalid) {
					throw new RevisionIOException(invalid);
				}
				return FileVisitResult.CONTINUE;
			}
		});
		Collections.sort(result);
		if (result.isEmpty()) throw problem(WorldBuilderErrorCodes.SOURCE_CORRUPT,
			WorldBuilderAdaptiveProjectLifecycle.WORKING_PACKAGE_DIRECTORY,
			"Working package has no files to revise.",
			"Restore a complete valid working package.");
		return result;
	}

	private static Path objectPath(Path project, String sha256)
		throws WorldBuilderContractException {
		if (!WorldBuilderBoundedInventory.isHash(sha256)) throw problem(
			WorldBuilderErrorCodes.CONTRACT_VALUE_INVALID, "object",
			"Revision object hash is invalid.", "Restore exact revision evidence.");
		return WorldBuilderPortablePath.resolveContained(project,
			OBJECTS + "/" + sha256.substring(0, 2) + "/" + sha256 + ".blob",
			OPERATION);
	}

	private static void requireObject(Path path, long size, String sha256)
		throws IOException, WorldBuilderContractException {
		if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
			|| Files.isSymbolicLink(path) || Files.size(path) != size
			|| !sha256.equals(WorldBuilderHashes.sha256(path))) throw problem(
			WorldBuilderErrorCodes.SOURCE_CORRUPT, path.getFileName().toString(),
			"Project revision object is missing, unsafe, or corrupt.",
			"Restore the exact content-addressed revision object.");
		WorldBuilderAdaptiveExporter.rejectHardLink(path, path.getFileName().toString());
	}

	private static Path requireProject(Path requested) throws IOException {
		if (requested == null) throw new IOException("Project was not supplied");
		Path project = requested.toAbsolutePath().normalize();
		if (!Files.isDirectory(project, LinkOption.NOFOLLOW_LINKS)
			|| Files.isSymbolicLink(project)) throw new IOException(
			"Project is missing or unsafe: " + project);
		return project.toRealPath();
	}

	private static void requireOrCreateDirectory(Path path) throws IOException {
		if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
			if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
				|| Files.isSymbolicLink(path)) throw new IOException(
				"Revision directory is unsafe: " + path);
		} else {
			Files.createDirectory(path);
			WorldBuilderAdaptiveDurability.forceDirectory(path.getParent());
		}
	}

	private static void writeNew(Path path, byte[] content) throws IOException {
		Files.write(path, content, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
		WorldBuilderAdaptiveDurability.forceFile(path);
	}

	private static void moveNew(Path source, Path destination) throws IOException {
		try {
			Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE);
		} catch (AtomicMoveNotSupportedException unsupported) {
			throw new IOException("Revision storage requires atomic same-filesystem moves",
				unsupported);
		}
		WorldBuilderAdaptiveDurability.forceDirectory(destination.getParent());
	}

	private static void deleteTree(Path root) throws IOException {
		if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return;
		if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)
			|| Files.isSymbolicLink(root)) throw new IOException(
			"Refusing to remove unsafe revision temporary path: " + root);
		Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
			@Override public FileVisitResult visitFile(Path file,
				BasicFileAttributes attributes) throws IOException {
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
		WorldBuilderAdaptiveDurability.forceDirectory(root.getParent());
	}

	private static WorldBuilderContractException problem(
		String code, String path, String message, String nextStep) {
		return new WorldBuilderContractException(
			code, OPERATION, path, false, message, nextStep);
	}

	private static WorldBuilderContractException problem(
		String code, String path, String message, String nextStep, Throwable cause) {
		return new WorldBuilderContractException(
			code, OPERATION, path, false, message, nextStep, cause);
	}

	static final class Revision {
		final Path manifestPath;
		final String revisionId;
		final String projectId;
		final String createdAt;
		final String reason;
		final String description;
		final String parentRevisionId;
		final String workingFingerprint;
		final long fileCount;
		final long totalBytes;
		final List<WorldBuilderBoundedInventory.Record> files;

		Revision(Path manifestPath, String revisionId, String projectId,
			String createdAt, String reason, String description, String parentRevisionId,
			String workingFingerprint, long fileCount, long totalBytes,
			List<WorldBuilderBoundedInventory.Record> files) {
			this.manifestPath = manifestPath;
			this.revisionId = revisionId;
			this.projectId = projectId;
			this.createdAt = createdAt;
			this.reason = reason;
			this.description = description;
			this.parentRevisionId = parentRevisionId;
			this.workingFingerprint = workingFingerprint;
			this.fileCount = fileCount;
			this.totalBytes = totalBytes;
			this.files = files;
		}
	}

	static final class RestoreResult {
		final Revision restored;
		final Revision safeguard;
		final boolean changed;

		RestoreResult(Revision restored, Revision safeguard, boolean changed) {
			this.restored = restored;
			this.safeguard = safeguard;
			this.changed = changed;
		}
	}

	private static final class FileRecord implements Comparable<FileRecord> {
		final String relativePath;
		final long size;
		final String sha256;

		FileRecord(String relativePath, long size, String sha256) {
			this.relativePath = relativePath;
			this.size = size;
			this.sha256 = sha256;
		}

		Map<String,Object> toJson(int index) {
			Map<String,Object> value = new LinkedHashMap<String,Object>();
			value.put("role", String.format(java.util.Locale.ROOT,
				"file-%06d", Integer.valueOf(index)));
			value.put("relativePath", relativePath);
			value.put("present", Boolean.TRUE);
			value.put("size", Long.valueOf(size));
			value.put("sha256", sha256);
			return value;
		}

		@Override public int compareTo(FileRecord other) {
			return relativePath.compareTo(other.relativePath);
		}
	}

	private static final class RevisionIOException extends IOException {
		RevisionIOException(WorldBuilderContractException cause) {
			super(cause);
		}
	}
}
