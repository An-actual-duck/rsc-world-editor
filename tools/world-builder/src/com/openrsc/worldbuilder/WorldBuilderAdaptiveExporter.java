package com.openrsc.worldbuilder;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Publishes one complete, target-independent adaptive project export.
 *
 * The working package is copied under the project lock, reopened through the
 * generic package validator, and atomically published under projects/UUID/exports.
 */
final class WorldBuilderAdaptiveExporter {
	static final String MANIFEST_FILE = "manifest.json";
	static final String VALIDATION_FILE = "validation-report.json";
	static final String PACKAGE_DIRECTORY = "package";
	private static final String OPERATION = "export-adaptive-project";
	private static final String ZERO_HASH =
		"0000000000000000000000000000000000000000000000000000000000000000";

	interface Observer {
		void observe(String milestone, Path stage) throws Exception;
	}

	private static final Observer NO_OP_OBSERVER = new Observer() {
		@Override public void observe(String milestone, Path stage) {
			// Production export has no injected observer.
		}
	};

	private final Observer observer;

	WorldBuilderAdaptiveExporter() {
		this(NO_OP_OBSERVER);
	}

	WorldBuilderAdaptiveExporter(Observer observer) {
		this.observer = observer == null ? NO_OP_OBSERVER : observer;
	}

	ExportResult export(Path requestedProject)
		throws IOException, WorldBuilderContractException {
		Path project = requireProjectRoot(requestedProject);
		try (WorldBuilderAdaptiveProjectLock ignored =
			WorldBuilderAdaptiveProjectLock.acquire(project, OPERATION)) {
			return exportLocked(project);
		}
	}

	private ExportResult exportLocked(Path project)
		throws IOException, WorldBuilderContractException {
		WorldBuilderAdaptiveProjectLifecycle.VerifiedProject verified =
			WorldBuilderAdaptiveProjectLifecycle.verifyProjectDirectory(project, true);
		if (!("ready-attached".equals(verified.state)
			|| "ready-detached".equals(verified.state)
			|| "ready-standalone".equals(verified.state))) {
			throw problem(WorldBuilderErrorCodes.RECOVERY_REQUIRED, "project.json",
				"Only a ready adaptive project can be exported.",
				"Resolve project recovery or source corruption before exporting.");
		}

		Path exports = requireDirectory(project, "exports", "project exports directory");
		Map<String,Object> manifest = manifest(verified);
		Map<String,Object> validation = validationReport(verified, manifest);
		String validationHash = canonicalHash(validation);
		@SuppressWarnings("unchecked") List<Object> validationReports =
			(List<Object>)manifest.get("validationReports");
		@SuppressWarnings("unchecked") Map<String,Object> reportRecord =
			(Map<String,Object>)validationReports.get(0);
		reportRecord.put("sha256", validationHash);
		bindFingerprint(manifest, "exportFingerprintSha256");
		WorldBuilderAdaptiveContracts.validateParsed(
			WorldBuilderAdaptiveContracts.Kind.ADAPTIVE_EXPORT, manifest);

		String fingerprint = string(manifest, "exportFingerprintSha256");
		Path published = uniqueExportPath(exports, fingerprint);
		Path stage = exports.resolve(".staging-" + UUID.randomUUID().toString()).normalize();
		Path incomplete = null;
		OwnedTree owned = new OwnedTree();
		requireContained(exports, stage, "export staging directory");
		try {
			Files.createDirectory(stage);
			incomplete = stage;
			owned.record(stage, stage);
			observe("stage-created", stage);
			copyPackage(verified, stage.resolve(PACKAGE_DIRECTORY), owned);
			observe("package-copied", stage);
			writeNew(stage.resolve(VALIDATION_FILE),
				WorldBuilderJsonDocuments.pretty(validation).getBytes(StandardCharsets.UTF_8));
			owned.record(stage, stage.resolve(VALIDATION_FILE));
			writeNew(stage.resolve(MANIFEST_FILE),
				WorldBuilderJsonDocuments.pretty(manifest).getBytes(StandardCharsets.UTF_8));
			owned.record(stage, stage.resolve(MANIFEST_FILE));
			validate(stage, verified);
			WorldBuilderAdaptiveProjectLifecycle.VerifiedProject finalProject =
				WorldBuilderAdaptiveProjectLifecycle.verifyProjectDirectory(project, true);
			requireSameProject(verified, finalProject);
			observe("before-publish", stage);
			WorldBuilderAdaptiveAtomicFiles.moveNew(
				stage, published, OPERATION, "exports");
			incomplete = published;
			observe("after-publish", published);
			validate(published, verified);
			incomplete = null;
			return new ExportResult(published, verified.projectId, verified.origin,
				fingerprint, verified.working.fingerprintSha256,
				verified.working.files.size());
		} catch (WorldBuilderContractException failure) {
			cleanupAfterFailure(owned, incomplete, failure);
			throw failure;
		} catch (IOException failure) {
			cleanupAfterFailure(owned, incomplete, failure);
			throw failure;
		} catch (RuntimeException failure) {
			cleanupAfterFailure(owned, incomplete, failure);
			throw failure;
		} catch (Exception callbackFailure) {
			cleanupAfterFailure(owned, incomplete, callbackFailure);
			throw problem(WorldBuilderErrorCodes.MUTATION_FAILED, "exports",
				"Adaptive export was interrupted before atomic publication.",
				"Retry after resolving the injected or environmental failure.",
				callbackFailure);
		}
	}

	static VerifiedExport validate(Path requestedExport,
		WorldBuilderAdaptiveProjectLifecycle.VerifiedProject project)
		throws IOException, WorldBuilderContractException {
		return validate(requestedExport, project, true);
	}

	static VerifiedExport validateHistorical(Path requestedExport,
		WorldBuilderAdaptiveProjectLifecycle.VerifiedProject project)
		throws IOException, WorldBuilderContractException {
		return validate(requestedExport, project, false);
	}

	private static VerifiedExport validate(Path requestedExport,
		WorldBuilderAdaptiveProjectLifecycle.VerifiedProject project,
		boolean requireCurrentWorking)
		throws IOException, WorldBuilderContractException {
		Path export = requireDirectory(requestedExport, "", "adaptive export directory");
		if (!export.startsWith(project.projectRoot.resolve("exports").toRealPath())) {
			throw problem(WorldBuilderErrorCodes.UNSAFE_PATH, "exports",
				"Adaptive export is outside its UUID project.",
				"Use one export below this project's exports directory.");
		}
		Path manifestPath = requireFile(export, MANIFEST_FILE, "adaptive export manifest");
		Map<String,Object> manifest = readObject(manifestPath, MANIFEST_FILE);
		WorldBuilderAdaptiveContracts.validateParsed(
			WorldBuilderAdaptiveContracts.Kind.ADAPTIVE_EXPORT, manifest);
		requireFingerprint(manifest, "exportFingerprintSha256");
		requireManifestLineage(manifest, project, requireCurrentWorking);

		Path validationPath = requireFile(export, VALIDATION_FILE,
			"adaptive package validation report");
		Map<String,Object> report = readObject(validationPath, VALIDATION_FILE);
		String reportHash = canonicalHash(report);
		List<?> reports = array(manifest.get("validationReports"), "validationReports");
		if (reports.size() != 1) throw problem(
			WorldBuilderErrorCodes.CONTRACT_VALUE_INVALID, MANIFEST_FILE,
			"Adaptive export has an unexpected validation-report set.",
			"Restore the exact export produced by World Builder.");
		Map<String,Object> reportRecord = object(reports.get(0), "validationReports");
		if (!"package-validation".equals(string(reportRecord, "role"))
			|| !reportHash.equals(string(reportRecord, "sha256"))) {
			throw problem(WorldBuilderErrorCodes.CONTRACT_VALUE_INVALID, VALIDATION_FILE,
				"Adaptive export validation-report hash does not match its manifest.",
				"Use an unmodified complete export.");
		}

		List<?> records = array(manifest.get("files"), "files");
		Set<String> expected = new HashSet<String>();
		expected.add(MANIFEST_FILE);
		expected.add(VALIDATION_FILE);
		for (Object raw : records) {
			Map<String,Object> record = object(raw, "files");
			String relative = string(record, "relativePath");
			Path path = requireFile(export, relative, "exported package file");
			expected.add(relative);
			if (!bool(record, "present") || Files.size(path) != integer(record, "size")
				|| !string(record, "sha256").equals(WorldBuilderHashes.sha256(path))) {
				throw problem(WorldBuilderErrorCodes.SOURCE_CORRUPT, relative,
					"Adaptive export package inventory changed.",
					"Use the exact immutable export produced by World Builder.");
			}
		}
		Set<String> actual = scanFiles(export);
		if (!actual.equals(expected)) throw problem(
			WorldBuilderErrorCodes.INVENTORY_DUPLICATE, "exports",
			"Adaptive export contains missing, extra, linked, or case-colliding files.",
			"Use one complete unmodified export directory.");

		WorldBuilderGenericLayeredPackage packageValue =
			WorldBuilderGenericLayeredPackage.inspect(
				WorldBuilderReadOnlyTarget.open(export), PACKAGE_DIRECTORY,
				"export", project.definitions);
		String manifestHash = packageManifestHash(packageValue, PACKAGE_DIRECTORY + "/");
		if (!packageValue.fingerprintSha256.equals(
			string(manifest, "packageFingerprintSha256"))
			|| !manifestHash.equals(string(manifest, "packageManifestSha256"))
			|| packageValue.files.size() != records.size()) {
			throw problem(WorldBuilderErrorCodes.SOURCE_CORRUPT, "package",
				"Adaptive export package identity did not independently validate.",
				"Use the exact complete export produced by World Builder.");
		}
		requireValidationReport(report, project, packageValue);
		return new VerifiedExport(export, manifest, packageValue,
			WorldBuilderAdaptiveContracts.validateParsed(
				WorldBuilderAdaptiveContracts.Kind.ADAPTIVE_EXPORT, manifest).canonicalSha256);
	}

	private static Map<String,Object> manifest(
		WorldBuilderAdaptiveProjectLifecycle.VerifiedProject project)
		throws IOException, WorldBuilderContractException {
		Map<String,Object> projectManifest = project.manifest;
		Map<String,Object> fingerprints = object(
			projectManifest.get("fingerprints"), "fingerprints");
		Map<String,Object> target = object(projectManifest.get("target"), "target");
		Map<String,Object> value = new LinkedHashMap<String,Object>();
		value.put("schemaVersion", Long.valueOf(2L));
		value.put("manifestType", "world-builder-adaptive-export");
		value.put("toolVersion", WorldBuilderAdaptiveDiscoveryReport.TOOL_VERSION);
		value.put("projectId", project.projectId);
		value.put("origin", project.origin);
		value.put("adapterId", string(target, "adapterId"));
		value.put("capabilityId", string(target, "capabilityId"));
		value.put("installProfileId", string(target, "importProfileId"));
		Map<String,Object> lineage = new LinkedHashMap<String,Object>();
		lineage.put("sourceSha256", string(fingerprints, "sourceSha256"));
		lineage.put("layeredBaselineSha256",
			string(fingerprints, "layeredBaselineSha256"));
		lineage.put("conversionSha256", string(fingerprints, "conversionSha256"));
		lineage.put("definitionsRuntimeSha256", definitionsRuntimeHash(fingerprints));
		lineage.put("workingSha256", string(fingerprints, "workingSha256"));
		value.put("lineage", lineage);
		value.put("packageManifestSha256",
			packageManifestHash(project.working,
				WorldBuilderAdaptiveProjectLifecycle.WORKING_PACKAGE_DIRECTORY + "/"));
		value.put("packageFingerprintSha256", project.working.fingerprintSha256);
		List<Object> files = new ArrayList<Object>();
		String prefix = WorldBuilderAdaptiveProjectLifecycle.WORKING_PACKAGE_DIRECTORY + "/";
		for (WorldBuilderReadOnlyTarget.FileState file : project.working.files) {
			if (!file.relativePath.startsWith(prefix)) throw problem(
				WorldBuilderErrorCodes.UNSAFE_PATH, file.relativePath,
				"Working package inventory escaped its declared project directory.",
				"Restore the complete saved project before export.");
			String inside = file.relativePath.substring(prefix.length());
			Map<String,Object> record = new LinkedHashMap<String,Object>();
			record.put("role", "manifest.json".equals(inside) ? "package-manifest"
				: inside.endsWith(".raw") ? "package-terrain" : "package-placement-set");
			record.put("relativePath", PACKAGE_DIRECTORY + "/" + inside);
			record.put("present", Boolean.TRUE);
			record.put("size", Long.valueOf(file.size));
			record.put("sha256", file.sha256);
			files.add(record);
		}
		Collections.sort(files, new Comparator<Object>() {
			@Override public int compare(Object left, Object right) {
				try {
					return string(object(left, "file"), "relativePath").compareTo(
						string(object(right, "file"), "relativePath"));
				} catch (WorldBuilderContractException impossible) {
					throw new IllegalStateException(impossible);
				}
			}
		});
		value.put("files", files);
		List<Object> reports = new ArrayList<Object>();
		Map<String,Object> report = new LinkedHashMap<String,Object>();
		report.put("role", "package-validation");
		report.put("sha256", ZERO_HASH);
		reports.add(report);
		value.put("validationReports", reports);
		value.put("exportFingerprintSha256", ZERO_HASH);
		return value;
	}

	private static Map<String,Object> validationReport(
		WorldBuilderAdaptiveProjectLifecycle.VerifiedProject project,
		Map<String,Object> manifest) throws WorldBuilderContractException {
		Map<String,Object> value = new LinkedHashMap<String,Object>();
		value.put("schemaVersion", Long.valueOf(1L));
		value.put("manifestType", "world-builder-adaptive-export-validation");
		value.put("projectId", project.projectId);
		value.put("origin", project.origin);
		value.put("packageManifestSha256", string(manifest, "packageManifestSha256"));
		value.put("packageFingerprintSha256",
			string(manifest, "packageFingerprintSha256"));
		value.put("packageId", project.working.packageId);
		value.put("packageVersion", project.working.packageVersion);
		value.put("worldSpace", project.working.worldSpace);
		value.put("fileCount", Long.valueOf(project.working.files.size()));
		value.put("levelCount", Long.valueOf(project.working.levelCount));
		value.put("terrainCount", Long.valueOf(project.working.terrainCount));
		value.put("placementSetCount", Long.valueOf(project.working.placementSetCount));
		value.put("boundaryCount", Long.valueOf(project.working.boundaryCount));
		value.put("groundItemCount", Long.valueOf(project.working.groundItemCount));
		value.put("npcCount", Long.valueOf(project.working.npcCount));
		value.put("sceneryCount", Long.valueOf(project.working.sceneryCount));
		return value;
	}

	private static void requireValidationReport(Map<String,Object> value,
		WorldBuilderAdaptiveProjectLifecycle.VerifiedProject project,
		WorldBuilderGenericLayeredPackage packageValue)
		throws WorldBuilderContractException {
		Set<String> expected = new HashSet<String>(Arrays.asList(
			"schemaVersion", "manifestType", "projectId", "origin",
			"packageManifestSha256", "packageFingerprintSha256", "packageId",
			"packageVersion", "worldSpace", "fileCount", "levelCount",
			"terrainCount", "placementSetCount", "boundaryCount", "groundItemCount",
			"npcCount", "sceneryCount"));
		if (!value.keySet().equals(expected)
			|| integer(value, "schemaVersion") != 1L
			|| !"world-builder-adaptive-export-validation".equals(
				string(value, "manifestType"))
			|| !project.projectId.equals(string(value, "projectId"))
			|| !project.origin.equals(string(value, "origin"))
			|| !packageValue.fingerprintSha256.equals(
				string(value, "packageFingerprintSha256"))
			|| !packageManifestHash(packageValue, PACKAGE_DIRECTORY + "/")
					.equals(string(value, "packageManifestSha256"))
			|| !packageValue.packageId.equals(string(value, "packageId"))
			|| !packageValue.packageVersion.equals(string(value, "packageVersion"))
			|| !packageValue.worldSpace.equals(string(value, "worldSpace"))
			|| integer(value, "fileCount") != packageValue.files.size()
			|| integer(value, "levelCount") != packageValue.levelCount
			|| integer(value, "terrainCount") != packageValue.terrainCount
			|| integer(value, "placementSetCount") != packageValue.placementSetCount
			|| integer(value, "boundaryCount") != packageValue.boundaryCount
			|| integer(value, "groundItemCount") != packageValue.groundItemCount
			|| integer(value, "npcCount") != packageValue.npcCount
			|| integer(value, "sceneryCount") != packageValue.sceneryCount) {
			throw problem(WorldBuilderErrorCodes.CONTRACT_VALUE_INVALID, VALIDATION_FILE,
				"Adaptive package validation report is incomplete or inconsistent.",
				"Use the exact report generated with this export.");
		}
	}

	private static void requireManifestLineage(Map<String,Object> value,
		WorldBuilderAdaptiveProjectLifecycle.VerifiedProject project,
		boolean requireCurrentWorking)
		throws IOException, WorldBuilderContractException {
		Map<String,Object> projectManifest = project.manifest;
		Map<String,Object> fingerprints = object(
			projectManifest.get("fingerprints"), "fingerprints");
		Map<String,Object> target = object(projectManifest.get("target"), "target");
		Map<String,Object> lineage = object(value.get("lineage"), "lineage");
		if (!project.projectId.equals(string(value, "projectId"))
			|| !project.origin.equals(string(value, "origin"))
			|| !string(target, "adapterId").equals(string(value, "adapterId"))
			|| !string(target, "capabilityId").equals(string(value, "capabilityId"))
			|| !string(target, "importProfileId").equals(
				string(value, "installProfileId"))
			|| !string(fingerprints, "sourceSha256").equals(
				string(lineage, "sourceSha256"))
			|| !string(fingerprints, "layeredBaselineSha256").equals(
				string(lineage, "layeredBaselineSha256"))
			|| !string(fingerprints, "conversionSha256").equals(
				string(lineage, "conversionSha256"))
			|| !definitionsRuntimeHash(fingerprints).equals(
				string(lineage, "definitionsRuntimeSha256"))
			|| requireCurrentWorking && (!string(fingerprints, "workingSha256").equals(
					string(lineage, "workingSha256"))
				|| !project.working.fingerprintSha256.equals(
					string(value, "packageFingerprintSha256"))
				|| !packageManifestHash(project.working,
					WorldBuilderAdaptiveProjectLifecycle.WORKING_PACKAGE_DIRECTORY + "/")
					.equals(string(value, "packageManifestSha256")))) {
			throw problem(WorldBuilderErrorCodes.SOURCE_CORRUPT, MANIFEST_FILE,
				"Adaptive export no longer matches its project/source/working lineage.",
				"Create a fresh export from the saved project.");
		}
	}

	private static void copyPackage(
		WorldBuilderAdaptiveProjectLifecycle.VerifiedProject project, Path destination,
		OwnedTree owned)
		throws IOException, WorldBuilderContractException {
		Files.createDirectory(destination);
		Path stage = destination.getParent();
		owned.record(stage, destination);
		String prefix = WorldBuilderAdaptiveProjectLifecycle.WORKING_PACKAGE_DIRECTORY + "/";
		for (WorldBuilderReadOnlyTarget.FileState file : project.working.files) {
			if (!file.relativePath.startsWith(prefix)) throw problem(
				WorldBuilderErrorCodes.UNSAFE_PATH, file.relativePath,
				"Working package inventory escaped its project package root.",
				"Restore the complete saved project before export.");
			String inside = file.relativePath.substring(prefix.length());
			Path source = requireFile(project.projectRoot, file.relativePath,
				"working package file");
			Path target = WorldBuilderPortablePath.resolveContained(
				destination, inside, OPERATION);
			requireContained(destination, target, inside);
			ensureOwnedDirectories(stage, destination, target.getParent(), owned);
			Files.copy(source, target, StandardCopyOption.COPY_ATTRIBUTES);
			owned.record(stage, target);
			if (Files.size(target) != file.size
				|| !file.sha256.equals(WorldBuilderHashes.sha256(target))) {
				throw problem(WorldBuilderErrorCodes.MUTATION_FAILED,
					PACKAGE_DIRECTORY + "/" + inside,
					"Staged export copy did not verify byte-for-byte.",
					"Check storage health and retry export.");
			}
		}
	}

	private static void ensureOwnedDirectories(Path stage, Path packageRoot,
		Path parent, OwnedTree owned) throws IOException {
		Path relative = packageRoot.relativize(parent);
		Path cursor = packageRoot;
		for (Path segment : relative) {
			cursor = cursor.resolve(segment.toString());
			if (!Files.exists(cursor, LinkOption.NOFOLLOW_LINKS)) {
				Files.createDirectory(cursor);
				owned.record(stage, cursor);
			} else if (!owned.matches(stage, cursor,
				Files.readAttributes(cursor, BasicFileAttributes.class,
					LinkOption.NOFOLLOW_LINKS))) {
				throw new IOException("Export package parent identity changed");
			}
		}
	}

	private static Path uniqueExportPath(Path exports, String fingerprint)
		throws IOException, WorldBuilderContractException {
		String prefix = "export-" + fingerprint.substring(0, 16) + "-";
		for (int sequence = 1; sequence <= 999999; sequence++) {
			String name = prefix + String.format(java.util.Locale.ROOT, "%06d", sequence);
			Path candidate = exports.resolve(name).normalize();
			requireContained(exports, candidate, name);
			if (!Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) return candidate;
		}
		throw problem(WorldBuilderErrorCodes.CONTRACT_LIMIT_EXCEEDED, "exports",
			"No unique adaptive export name remains for this project state.",
			"Archive complete old exports outside the closed project and retry.");
	}

	private static void requireSameProject(
		WorldBuilderAdaptiveProjectLifecycle.VerifiedProject before,
		WorldBuilderAdaptiveProjectLifecycle.VerifiedProject after)
		throws WorldBuilderContractException {
		if (!before.projectId.equals(after.projectId)
			|| !before.origin.equals(after.origin)
			|| !before.working.fingerprintSha256.equals(after.working.fingerprintSha256)
			|| !canonicalHash(before.manifest).equals(canonicalHash(after.manifest))
			|| !canonicalHash(before.snapshot).equals(canonicalHash(after.snapshot))) {
			throw problem(WorldBuilderErrorCodes.SOURCE_CORRUPT, "project.json",
				"Project source or working state changed while export was staged.",
				"Close the editor, save the project, and export again.");
		}
	}

	static String definitionsRuntimeHash(Map<String,Object> fingerprints)
		throws WorldBuilderContractException {
		MessageDigest digest = WorldBuilderHashes.newDigest();
		WorldBuilderHashes.updateText(digest, string(fingerprints, "definitionsSha256"));
		WorldBuilderHashes.updateText(digest, string(fingerprints, "runtimeSha256"));
		return WorldBuilderHashes.hex(digest.digest());
	}

	static String packageManifestHash(WorldBuilderGenericLayeredPackage packageValue,
		String prefix) throws WorldBuilderContractException {
		for (WorldBuilderReadOnlyTarget.FileState file : packageValue.files) {
			if ((prefix + "manifest.json").equals(file.relativePath)) return file.sha256;
		}
		throw problem(WorldBuilderErrorCodes.SOURCE_CORRUPT, prefix + "manifest.json",
			"Validated package inventory has no manifest file.",
			"Restore one complete layered package.");
	}

	static void bindFingerprint(Map<String,Object> value, String field)
		throws WorldBuilderContractException {
		value.put(field, ZERO_HASH);
		value.put(field, canonicalHash(value));
	}

	static void requireFingerprint(Map<String,Object> value, String field)
		throws WorldBuilderContractException {
		String supplied = string(value, field);
		value.put(field, ZERO_HASH);
		String calculated;
		try {
			calculated = canonicalHash(value);
		} finally {
			value.put(field, supplied);
		}
		if (!supplied.equals(calculated)) throw problem(
			WorldBuilderErrorCodes.SOURCE_CORRUPT, field,
			"Contract self-fingerprint does not match its content.",
			"Restore the exact generated contract.");
	}

	static String canonicalHash(Map<String,Object> value) {
		return WorldBuilderHashes.sha256(
			WorldBuilderJsonDocuments.canonical(value).getBytes(StandardCharsets.UTF_8));
	}

	private static Set<String> scanFiles(Path root)
		throws IOException, WorldBuilderContractException {
		final Set<String> values = new HashSet<String>();
		final Set<String> collision = new HashSet<String>();
		Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
			@Override public FileVisitResult preVisitDirectory(Path directory,
				BasicFileAttributes attributes) throws IOException {
				if (Files.isSymbolicLink(directory) || !attributes.isDirectory()) {
					throw new IOException("Unsafe export directory entry");
				}
				return FileVisitResult.CONTINUE;
			}
			@Override public FileVisitResult visitFile(Path file,
				BasicFileAttributes attributes) throws IOException {
				if (!attributes.isRegularFile() || Files.isSymbolicLink(file)) {
					throw new IOException("Unsafe export file entry");
				}
				try {
					rejectHardLink(file, root.relativize(file).toString());
					String relative = root.relativize(file).toString().replace('\\', '/');
					WorldBuilderPortablePath.require(relative, OPERATION);
					if (!collision.add(WorldBuilderPortablePath.collisionKey(relative, OPERATION))) {
						throw new IOException("Case-colliding export file");
					}
					values.add(relative);
				} catch (WorldBuilderContractException invalid) {
					throw new IOException(invalid);
				}
				return FileVisitResult.CONTINUE;
			}
		});
		return values;
	}

	static void rejectHardLink(Path path, String relative)
		throws IOException, WorldBuilderContractException {
		try {
			Object links = Files.getAttribute(path, "unix:nlink", LinkOption.NOFOLLOW_LINKS);
			if (links instanceof Number && ((Number)links).longValue() > 1L) {
				throw problem(WorldBuilderErrorCodes.UNSAFE_PATH, relative,
					"Export file is hard-linked and containment cannot be proven.",
					"Use distinct regular files in the export.");
			}
		} catch (UnsupportedOperationException ignored) {
			// No portable link-count view; no-follow checks remain authoritative.
		} catch (IllegalArgumentException ignored) {
			// No portable link-count view; no-follow checks remain authoritative.
		}
	}

	private static Path requireProjectRoot(Path requested)
		throws IOException, WorldBuilderContractException {
		if (requested == null) throw problem(WorldBuilderErrorCodes.UNSAFE_PATH,
			"project", "Adaptive project path was not supplied.",
			"Select one complete projects/<uuid> directory.");
		Path project = requested.toAbsolutePath().normalize();
		if (!Files.isDirectory(project, LinkOption.NOFOLLOW_LINKS)
			|| Files.isSymbolicLink(project)) throw problem(
			WorldBuilderErrorCodes.UNSAFE_PATH, "project",
			"Adaptive project directory is missing or unsafe.",
			"Select one complete real projects/<uuid> directory.");
		return project.toRealPath();
	}

	static Path requireDirectory(Path root, String relative, String label)
		throws IOException, WorldBuilderContractException {
		Path path = relative.isEmpty() ? root.toAbsolutePath().normalize()
			: WorldBuilderPortablePath.resolveContained(root, relative, OPERATION);
		if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
			|| Files.isSymbolicLink(path)) throw problem(
			WorldBuilderErrorCodes.UNSAFE_PATH, relative.isEmpty() ? "exports" : relative,
			label + " is missing, linked, or not a directory.",
			"Restore the complete contained adaptive project/export.");
		Path real = path.toRealPath();
		if (!real.startsWith(root.toAbsolutePath().normalize())) throw problem(
			WorldBuilderErrorCodes.UNSAFE_PATH, relative,
			label + " resolves outside its declared root.",
			"Use one contained real directory.");
		return real;
	}

	static Path requireFile(Path root, String relative, String label)
		throws IOException, WorldBuilderContractException {
		Path path = WorldBuilderPortablePath.resolveContained(root, relative, OPERATION);
		if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
			|| Files.isSymbolicLink(path)) throw problem(
			WorldBuilderErrorCodes.UNSAFE_PATH, relative,
			label + " is missing, linked, or not a regular file.",
			"Restore the exact contained regular file.");
		Path real = path.toRealPath();
		if (!real.startsWith(root.toAbsolutePath().normalize())) throw problem(
			WorldBuilderErrorCodes.UNSAFE_PATH, relative,
			label + " resolves outside its declared root.",
			"Use one contained regular file.");
		rejectHardLink(real, relative);
		return real;
	}

	private static void requireContained(Path root, Path child, String label)
		throws WorldBuilderContractException {
		if (!child.toAbsolutePath().normalize().startsWith(root.toAbsolutePath().normalize())) {
			throw problem(WorldBuilderErrorCodes.UNSAFE_PATH, label,
				"Adaptive export path escaped its project directory.",
				"Use only normalized project-relative paths.");
		}
	}

	private static void writeNew(Path path, byte[] bytes) throws IOException {
		Files.write(path, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
		try (FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE)) {
			channel.force(true);
		}
	}

	private static Map<String,Object> readObject(Path path, String relative)
		throws IOException, WorldBuilderContractException {
		try {
			return WorldBuilderJsonDocuments.readObject(path);
		} catch (WorldBuilderDiscoveryException malformed) {
			throw problem(WorldBuilderErrorCodes.MALFORMED_JSON, relative,
				"Adaptive export JSON is malformed: " + malformed.getMessage(),
				"Use one exact bounded generated export document.", malformed);
		}
	}

	@SuppressWarnings("unchecked")
	static Map<String,Object> object(Object value, String label)
		throws WorldBuilderContractException {
		if (!(value instanceof Map)) throw problem(
			WorldBuilderErrorCodes.CONTRACT_VALUE_INVALID, label,
			"Expected a contract object.", "Restore the exact generated contract.");
		return (Map<String,Object>)value;
	}

	static List<?> array(Object value, String label)
		throws WorldBuilderContractException {
		if (!(value instanceof List)) throw problem(
			WorldBuilderErrorCodes.CONTRACT_VALUE_INVALID, label,
			"Expected a contract array.", "Restore the exact generated contract.");
		return (List<?>)value;
	}

	static String string(Map<String,Object> value, String key)
		throws WorldBuilderContractException {
		Object raw = value.get(key);
		if (!(raw instanceof String)) throw problem(
			WorldBuilderErrorCodes.CONTRACT_VALUE_INVALID, key,
			"Expected a string contract field.", "Restore the exact generated contract.");
		return (String)raw;
	}

	static long integer(Map<String,Object> value, String key)
		throws WorldBuilderContractException {
		Object raw = value.get(key);
		if (!(raw instanceof Long)) throw problem(
			WorldBuilderErrorCodes.CONTRACT_VALUE_INVALID, key,
			"Expected an integer contract field.", "Restore the exact generated contract.");
		return ((Long)raw).longValue();
	}

	static boolean bool(Map<String,Object> value, String key)
		throws WorldBuilderContractException {
		Object raw = value.get(key);
		if (!(raw instanceof Boolean)) throw problem(
			WorldBuilderErrorCodes.CONTRACT_VALUE_INVALID, key,
			"Expected a boolean contract field.", "Restore the exact generated contract.");
		return ((Boolean)raw).booleanValue();
	}

	private void observe(String milestone, Path stage) throws Exception {
		observer.observe(milestone, stage);
	}

	private static void cleanupAfterFailure(OwnedTree owned, Path root,
		Throwable original) throws WorldBuilderContractException {
		try {
			owned.delete(root);
		} catch (IOException cleanupFailure) {
			cleanupFailure.addSuppressed(original);
			throw problem(WorldBuilderErrorCodes.RECOVERY_REQUIRED, "exports",
				"Failed export cleanup could not prove exclusive ownership of every path.",
				"Preserve the incomplete export for owner review; do not delete unknown content.",
				cleanupFailure);
		}
	}

	private static final class OwnedTree {
		private final Map<String,OwnedEntry> entries =
			new LinkedHashMap<String,OwnedEntry>();

		void record(Path root, Path path) throws IOException {
			BasicFileAttributes attributes = Files.readAttributes(path,
				BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
			String relative = root.relativize(path).toString().replace('\\', '/');
			entries.put(relative, new OwnedEntry(attributes.isDirectory(),
				attributes.isRegularFile(), attributes.fileKey()));
		}

		boolean matches(Path root, Path path, BasicFileAttributes attributes) {
			String relative = root.relativize(path).toString().replace('\\', '/');
			OwnedEntry expected = entries.get(relative);
			return expected != null
				&& expected.directory == attributes.isDirectory()
				&& expected.regularFile == attributes.isRegularFile()
				&& expected.fileKey != null
				&& Objects.equals(expected.fileKey, attributes.fileKey());
		}

		void delete(final Path root) throws IOException {
			if (root == null || !Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return;
			Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
				@Override public FileVisitResult preVisitDirectory(Path directory,
					BasicFileAttributes attributes) throws IOException {
					if (!matches(root, directory, attributes)) throw new IOException(
						"Refusing export cleanup after directory identity changed");
					return FileVisitResult.CONTINUE;
				}
				@Override public FileVisitResult visitFile(Path file,
					BasicFileAttributes attributes) throws IOException {
					if (!matches(root, file, attributes)) throw new IOException(
						"Refusing export cleanup after file identity changed");
					return FileVisitResult.CONTINUE;
				}
			});
			Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
				@Override public FileVisitResult visitFile(Path file,
					BasicFileAttributes attributes) throws IOException {
					BasicFileAttributes current = Files.readAttributes(file,
						BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
					if (!matches(root, file, current)) throw new IOException(
						"Refusing export cleanup after file identity changed");
					Files.delete(file);
					return FileVisitResult.CONTINUE;
				}
				@Override public FileVisitResult postVisitDirectory(Path directory,
					IOException failure) throws IOException {
					if (failure != null) throw failure;
					BasicFileAttributes current = Files.readAttributes(directory,
						BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
					if (!matches(root, directory, current)) throw new IOException(
						"Refusing export cleanup after directory identity changed");
					Files.delete(directory);
					return FileVisitResult.CONTINUE;
				}
			});
		}
	}

	private static final class OwnedEntry {
		final boolean directory;
		final boolean regularFile;
		final Object fileKey;

		OwnedEntry(boolean directory, boolean regularFile, Object fileKey) {
			this.directory = directory;
			this.regularFile = regularFile;
			this.fileKey = fileKey;
		}
	}

	private static WorldBuilderContractException problem(
		String code, String path, String message, String nextStep) {
		return new WorldBuilderContractException(code, OPERATION, path, false,
			message, nextStep);
	}

	private static WorldBuilderContractException problem(
		String code, String path, String message, String nextStep, Throwable cause) {
		return new WorldBuilderContractException(code, OPERATION, path, false,
			message, nextStep, cause);
	}

	static final class VerifiedExport {
		final Path root;
		final Map<String,Object> manifest;
		final WorldBuilderGenericLayeredPackage packageValue;
		final String manifestCanonicalSha256;

		VerifiedExport(Path root, Map<String,Object> manifest,
			WorldBuilderGenericLayeredPackage packageValue,
			String manifestCanonicalSha256) {
			this.root = root;
			this.manifest = manifest;
			this.packageValue = packageValue;
			this.manifestCanonicalSha256 = manifestCanonicalSha256;
		}
	}

	static final class ExportResult {
		final Path exportDirectory;
		final String projectId;
		final String origin;
		final String exportFingerprintSha256;
		final String packageFingerprintSha256;
		final int packageFileCount;

		ExportResult(Path exportDirectory, String projectId, String origin,
			String exportFingerprintSha256, String packageFingerprintSha256,
			int packageFileCount) {
			this.exportDirectory = exportDirectory;
			this.projectId = projectId;
			this.origin = origin;
			this.exportFingerprintSha256 = exportFingerprintSha256;
			this.packageFingerprintSha256 = packageFingerprintSha256;
			this.packageFileCount = packageFileCount;
		}

		String toJson() {
			Map<String,Object> value = new LinkedHashMap<String,Object>();
			value.put("status", "exported");
			value.put("projectId", projectId);
			value.put("origin", origin);
			value.put("exportDirectory", exportDirectory.toString());
			value.put("exportFingerprintSha256", exportFingerprintSha256);
			value.put("packageFingerprintSha256", packageFingerprintSha256);
			value.put("packageFileCount", Long.valueOf(packageFileCount));
			return WorldBuilderJsonDocuments.pretty(value);
		}
	}
}
