package com.openrsc.worldbuilder;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Closed runnable-layout plan derived only from provider-inventoried artifact roles. */
final class WorldBuilderCurrentRuntimeLayout {
	private static final String OPERATION = "current-runtime-layout";
	private static final int MAX_ARCHIVE_ENTRIES = 8192;

	private WorldBuilderCurrentRuntimeLayout() { }

	static Map<String,Object> inspect(WorldBuilderProviderCatalog.Composition composition)
		throws IOException, WorldBuilderContractException {
		Map<String,WorldBuilderProviderCatalog.Artifact> roles = requiredRoles(composition);
		for (WorldBuilderProviderCatalog.Artifact artifact : roles.values())
			if (!Files.getFileStore(artifact.source).supportsFileAttributeView("posix"))
				throw failure(artifact.sourcePath,
					"Runnable layout requires reviewed POSIX private-mode enforcement.");
		List<Object> outputs = new ArrayList<Object>();
		addFile(outputs, roles.get("server-runtime"),
			"installed/server/core.jar", "server-runtime");
		addFile(outputs, roles.get("server-plugins"),
			"installed/server/plugins.jar", "server-plugins");
		addFile(outputs, roles.get("client-runtime"),
			"installed/client/Open_RSC_Client.jar", "client-runtime");
		inspectArchive(outputs, roles.get("server-content"),
			"installed/server", "server-content");
		inspectArchive(outputs, roles.get("client-content"),
			"installed/client", "client-content");
		sortAndValidate(outputs);
		Map<String,Object> result = new LinkedHashMap<String,Object>();
		result.put("layoutId", "current-base-runnable-layout-v1");
		result.put("serverRootRelativePath", "installed/server");
		result.put("clientRootRelativePath", "installed/client");
		result.put("outputs", outputs);
		result.put("outputInventoryHash", WorldBuilderHashes.sha256(
			WorldBuilderJsonDocuments.canonical(outputs).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
		result.put("ready", Boolean.TRUE);
		return result;
	}

	static Map<String,Object> unavailable(String reason) {
		Map<String,Object> result = new LinkedHashMap<String,Object>();
		result.put("layoutId", "current-base-runnable-layout-v1");
		result.put("serverRootRelativePath", "installed/server");
		result.put("clientRootRelativePath", "installed/client");
		result.put("outputs", new ArrayList<Object>());
		result.put("outputInventoryHash", "");
		result.put("ready", Boolean.FALSE);
		result.put("reason", reason == null ? "provider-runtime-layout-unavailable" : reason);
		return result;
	}

	static void materialize(Path release, Map<String,Object> plan)
		throws IOException, WorldBuilderContractException {
		validatePlan(plan);
		if (Files.exists(release.resolve("installed"), LinkOption.NOFOLLOW_LINKS))
			throw failure("installed", "Runnable layout destination already exists.");
		Files.createDirectory(release.resolve("installed"));
		Files.createDirectory(release.resolve("installed/server"));
		Files.createDirectory(release.resolve("installed/client"));
		Map<String,Map<String,Object>> expected = byKind(plan);
		copyPlanned(release, expected.get("server-runtime"),
			"runtime/server/core.jar");
		copyPlanned(release, expected.get("server-plugins"),
			"runtime/server/plugins.jar");
		copyPlanned(release, expected.get("client-runtime"),
			"runtime/client/Open_RSC_Client.jar");
		extractPlanned(release, release.resolve("runtime/server/content.zip"),
			"server-content", expected);
		extractPlanned(release, release.resolve("runtime/client/content.zip"),
			"client-content", expected);
		verify(release, plan);
		WorldBuilderAdaptiveDurability.forceTree(release.resolve("installed"));
	}

	static void verify(Path release, Map<String,Object> plan)
		throws IOException, WorldBuilderContractException {
		validatePlan(plan);
		Set<String> expected = new HashSet<String>();
		Set<String> expectedDirectories = new HashSet<String>();
		expectedDirectories.add("installed");
		for (Object raw : array(plan.get("outputs"))) {
			Map<String,Object> record = object(raw);
			String relative = string(record, "relativePath");
			expected.add(relative);
			addParentDirectories(relative, expectedDirectories);
			Path path = WorldBuilderPortablePath.resolveContained(release, relative, OPERATION);
			BasicFileAttributes attributes = Files.readAttributes(path,
				BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
			if (!attributes.isRegularFile() || attributes.isSymbolicLink()
				|| attributes.size() != integer(record, "size")
				|| !WorldBuilderHashes.sha256(path).equals(string(record, "sha256"))
				|| !fileMode(path).equals(string(record, "mode")))
				throw failure(relative, "Runnable layout output differs from its reviewed bytes.");
		}
		final Path installed = release.resolve("installed");
		final Set<String> seen = new HashSet<String>();
		final Set<String> seenDirectories = new HashSet<String>();
		Files.walkFileTree(installed, new java.nio.file.SimpleFileVisitor<Path>() {
			@Override public java.nio.file.FileVisitResult preVisitDirectory(Path directory,
				BasicFileAttributes attributes) throws IOException {
				if (!attributes.isDirectory() || attributes.isSymbolicLink())
					throw new IOException("unsafe runnable layout directory");
				seenDirectories.add(release.relativize(directory).toString().replace('\\', '/'));
				return java.nio.file.FileVisitResult.CONTINUE;
			}
			@Override public java.nio.file.FileVisitResult visitFile(Path file,
				BasicFileAttributes attributes) throws IOException {
				if (!attributes.isRegularFile() || attributes.isSymbolicLink())
					throw new IOException("unsafe runnable layout file");
				seen.add(release.relativize(file).toString().replace('\\', '/'));
				return java.nio.file.FileVisitResult.CONTINUE;
			}
		});
		if (!seen.equals(expected) || !seenDirectories.equals(expectedDirectories))
			throw failure("installed",
			"Runnable layout has missing or extra files.");
	}

	private static Map<String,WorldBuilderProviderCatalog.Artifact> requiredRoles(
		WorldBuilderProviderCatalog.Composition composition)
		throws WorldBuilderContractException {
		Map<String,WorldBuilderProviderCatalog.Artifact> result =
			new LinkedHashMap<String,WorldBuilderProviderCatalog.Artifact>();
		Set<String> wanted = new HashSet<String>(java.util.Arrays.asList(
			"server-runtime", "server-plugins", "server-content",
			"client-runtime", "client-content"));
		for (WorldBuilderProviderCatalog.Artifact artifact : composition.artifacts) {
			String role = string(artifact.inventory, "role");
			if (!wanted.contains(role)) continue;
			if (result.put(role, artifact) != null) throw failure(role,
				"Provider composition repeats a compiled runnable-layout role.");
		}
		if (!result.keySet().equals(wanted)) throw failure("provider-artifacts",
			"Provider composition omits a compiled runnable-layout role.");
		return result;
	}

	private static void addFile(List<Object> outputs,
		WorldBuilderProviderCatalog.Artifact artifact, String relative, String kind)
		throws IOException, WorldBuilderContractException {
		BasicFileAttributes attributes = Files.readAttributes(artifact.source,
			BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
		if (!attributes.isRegularFile() || attributes.isSymbolicLink())
			throw failure(artifact.sourcePath, "Provider runtime artifact is unsafe.");
		outputs.add(record(relative, kind, artifact.bundlePath, attributes.size(),
			WorldBuilderHashes.sha256(artifact.source), string(artifact.inventory, "mode")));
	}

	private static void inspectArchive(List<Object> outputs,
		WorldBuilderProviderCatalog.Artifact artifact, String destinationRoot,
		String kind) throws IOException, WorldBuilderContractException {
		Set<String> names = new HashSet<String>();
		Set<String> files = new HashSet<String>();
		Map<String,String> folded = new LinkedHashMap<String,String>();
		long total = 0L;
		int count = 0;
		try (ZipFile archive = new ZipFile(artifact.source.toFile())) {
			Enumeration<? extends ZipEntry> entries = archive.entries();
			while (entries.hasMoreElements()) {
				ZipEntry entry = entries.nextElement();
				if (++count > MAX_ARCHIVE_ENTRIES || entry.isDirectory())
					throw failure(artifact.bundlePath, "Provider content archive shape is unbounded.");
				String name = WorldBuilderPortablePath.require(entry.getName(), OPERATION);
				if (!names.add(name)) throw failure(name,
					"Provider content archive repeats a path.");
				registerArchivePath(name, files, folded);
				Digest digest = digest(archive.getInputStream(entry), entry.getSize());
				total = Math.addExact(total, digest.size);
				if (total > WorldBuilderContractLimits.MAX_INVENTORY_TOTAL_BYTES)
					throw failure(artifact.bundlePath, "Provider content archive is too large.");
				outputs.add(record(destinationRoot + "/" + name, kind,
					artifact.bundlePath + "!" + name, digest.size, digest.sha256, "0600"));
			}
		}
		if (count == 0) throw failure(artifact.bundlePath,
			"Provider content archive is empty.");
	}

	private static void registerArchivePath(String name, Set<String> files,
		Map<String,String> folded) throws WorldBuilderContractException {
		String[] segments = name.split("/");
		String current = "";
		for (int index = 0; index < segments.length; index++) {
			current = current.isEmpty() ? segments[index] : current + "/" + segments[index];
			String key = WorldBuilderPortablePath.collisionKey(current, OPERATION);
			String prior = folded.put(key, current);
			if (prior != null && !prior.equals(current)) throw failure(name,
				"Provider archive contains a case-colliding path segment.");
			if (index < segments.length - 1 && files.contains(key)) throw failure(name,
				"Provider archive places a child below a file path.");
			if (index == segments.length - 1) {
				for (String existing : folded.keySet()) if (existing.startsWith(key + "/"))
					throw failure(name, "Provider archive file collides with a directory path.");
				files.add(key);
			}
		}
	}

	private static Digest digest(InputStream raw, long declared)
		throws IOException, WorldBuilderContractException {
		if (declared < 0L || declared > WorldBuilderContractLimits.MAX_INVENTORY_FILE_BYTES)
			throw failure("archive-entry", "Provider archive entry size is invalid.");
		java.security.MessageDigest digest = WorldBuilderHashes.newDigest();
		byte[] buffer = new byte[8192]; long size = 0L;
		try (InputStream input = raw) {
			for (int read; (read = input.read(buffer)) >= 0;) {
				if (read == 0) continue;
				size = Math.addExact(size, read);
				if (size > declared || size > WorldBuilderContractLimits.MAX_INVENTORY_FILE_BYTES)
					throw failure("archive-entry", "Provider archive entry exceeds its declared bound.");
				digest.update(buffer, 0, read);
			}
		}
		if (size != declared) throw failure("archive-entry",
			"Provider archive entry size differs from its directory record.");
		return new Digest(size, WorldBuilderHashes.hex(digest.digest()));
	}

	private static Map<String,Object> record(String relative, String kind,
		String source, long size, String hash, String mode) {
		Map<String,Object> result = new LinkedHashMap<String,Object>();
		result.put("relativePath", relative); result.put("kind", kind);
		result.put("source", source); result.put("size", Long.valueOf(size));
		result.put("sha256", hash); result.put("mode", mode); return result;
	}

	private static void sortAndValidate(List<Object> outputs)
		throws WorldBuilderContractException {
		Collections.sort(outputs, new Comparator<Object>() {
			@Override public int compare(Object left, Object right) {
				return ((String)objectUnchecked(left).get("relativePath")).compareTo(
					(String)objectUnchecked(right).get("relativePath"));
			}
		});
		String prior = "";
		for (Object raw : outputs) {
			Map<String,Object> record = object(raw);
			String relative = string(record, "relativePath");
			if (!prior.isEmpty() && prior.compareTo(relative) >= 0)
				throw failure(relative, "Runnable layout output path repeats.");
			prior = relative;
		}
	}

	static void validatePlan(Map<String,Object> plan)
		throws WorldBuilderContractException {
		boolean ready = bool(plan, "ready");
		if (ready) WorldBuilderBoundedInventory.exactKeys(plan, OPERATION, "layoutId",
			"serverRootRelativePath", "clientRootRelativePath", "outputs",
			"outputInventoryHash", "ready");
		else WorldBuilderBoundedInventory.exactKeys(plan, OPERATION, "layoutId",
			"serverRootRelativePath", "clientRootRelativePath", "outputs",
			"outputInventoryHash", "ready", "reason");
		if (!"current-base-runnable-layout-v1".equals(string(plan, "layoutId"))
			|| !"installed/server".equals(string(plan, "serverRootRelativePath"))
			|| !"installed/client".equals(string(plan, "clientRootRelativePath"))
			|| !ready && (!array(plan.get("outputs")).isEmpty()
				|| !string(plan, "outputInventoryHash").isEmpty())) throw failure("layout-plan",
				"Runnable layout plan identity changed.");
		List<Object> outputs = array(plan.get("outputs"));
		if (ready && outputs.isEmpty() || outputs.size() > MAX_ARCHIVE_ENTRIES)
			throw failure("layout-plan", "Runnable layout inventory is unbounded.");
		if (!ready) return;
		String prior = "";
		for (Object raw : outputs) {
			Map<String,Object> record = object(raw);
			WorldBuilderBoundedInventory.exactKeys(record, OPERATION,
				"relativePath", "kind", "source", "size", "sha256", "mode");
			String relative = WorldBuilderPortablePath.require(
				string(record, "relativePath"), OPERATION);
			if (!relative.startsWith("installed/")
				|| !prior.isEmpty() && prior.compareTo(relative) >= 0
				|| integer(record, "size") < 0L
				|| !WorldBuilderBoundedInventory.isHash(string(record, "sha256")))
				throw failure(relative, "Runnable layout inventory is invalid.");
			prior = relative;
		}
		String expected = WorldBuilderHashes.sha256(
			WorldBuilderJsonDocuments.canonical(outputs).getBytes(java.nio.charset.StandardCharsets.UTF_8));
		if (!expected.equals(string(plan, "outputInventoryHash")))
			throw failure("layout-plan", "Runnable layout inventory hash differs.");
	}

	private static Map<String,Map<String,Object>> byKind(Map<String,Object> plan)
		throws WorldBuilderContractException {
		Map<String,Map<String,Object>> result = new LinkedHashMap<String,Map<String,Object>>();
		for (Object raw : array(plan.get("outputs"))) {
			Map<String,Object> record = object(raw);
			String kind = string(record, "kind");
			if (kind.endsWith("-content")) result.put(string(record, "source"), record);
			else if (result.put(kind, record) != null) throw failure(kind,
				"Runnable layout repeats a singleton runtime role.");
		}
		return result;
	}

	private static void copyPlanned(Path release, Map<String,Object> record,
		String sourceRelative) throws IOException, WorldBuilderContractException {
		if (record == null || !sourceRelative.equals(string(record, "source")))
			throw failure(sourceRelative, "Runnable layout runtime role is missing.");
		Path source = release.resolve(sourceRelative);
		Path destination = release.resolve(string(record, "relativePath"));
		Files.copy(source, destination, StandardCopyOption.COPY_ATTRIBUTES);
		setMode(destination, string(record, "mode"));
		requireRecord(destination, record);
	}

	private static void extractPlanned(Path release, Path archivePath, String kind,
		Map<String,Map<String,Object>> expected)
		throws IOException, WorldBuilderContractException {
		try (ZipFile archive = new ZipFile(archivePath.toFile())) {
			Enumeration<? extends ZipEntry> entries = archive.entries();
			while (entries.hasMoreElements()) {
				ZipEntry entry = entries.nextElement();
				String source = release.relativize(archivePath).toString().replace('\\', '/')
					+ "!" + entry.getName();
				Map<String,Object> record = expected.get(source);
				if (record == null || !kind.equals(string(record, "kind")))
					throw failure(entry.getName(), "Archive output was not reviewed.");
				Path destination = WorldBuilderPortablePath.resolveContained(release,
					string(record, "relativePath"), OPERATION);
				Files.createDirectories(destination.getParent());
				copyBounded(archive.getInputStream(entry), destination,
					integer(record, "size"));
				setMode(destination, string(record, "mode"));
				requireRecord(destination, record);
			}
		}
	}

	private static void copyBounded(InputStream raw, Path destination, long expected)
		throws IOException, WorldBuilderContractException {
		byte[] buffer = new byte[8192]; long total = 0L;
		try (InputStream input = raw;
			java.io.OutputStream output = Files.newOutputStream(destination,
				StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
			for (int read; (read = input.read(buffer)) >= 0;) {
				if (read == 0) continue;
				total = Math.addExact(total, read);
				if (total > expected) throw failure(destination.getFileName().toString(),
					"Provider archive output exceeded its reviewed size.");
				output.write(buffer, 0, read);
			}
		}
		if (total != expected) throw failure(destination.getFileName().toString(),
			"Provider archive output ended before its reviewed size.");
	}

	private static void requireRecord(Path path, Map<String,Object> record)
		throws IOException, WorldBuilderContractException {
		if (Files.size(path) != integer(record, "size")
			|| !WorldBuilderHashes.sha256(path).equals(string(record, "sha256")))
			throw failure(string(record, "relativePath"),
				"Materialized runtime bytes differ from the reviewed plan.");
	}

	private static void setMode(Path path, String mode) throws IOException {
		int bits = Integer.parseInt(mode, 8);
		Set<PosixFilePermission> permissions = java.util.EnumSet.noneOf(
			PosixFilePermission.class);
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

	private static void addParentDirectories(String relative, Set<String> values) {
		int slash = relative.lastIndexOf('/');
		while (slash > 0) {
			values.add(relative.substring(0, slash));
			slash = relative.lastIndexOf('/', slash - 1);
		}
	}

	@SuppressWarnings("unchecked") private static Map<String,Object> object(Object value)
		throws WorldBuilderContractException {
		if (!(value instanceof Map)) throw failure("layout-plan", "Expected an object.");
		return (Map<String,Object>)value;
	}
	@SuppressWarnings("unchecked") private static Map<String,Object> objectUnchecked(Object value) {
		return (Map<String,Object>)value;
	}
	@SuppressWarnings("unchecked") private static List<Object> array(Object value)
		throws WorldBuilderContractException {
		if (!(value instanceof List)) throw failure("layout-plan", "Expected an array.");
		return (List<Object>)value;
	}
	private static String string(Map<String,Object> value, String key)
		throws WorldBuilderContractException {
		return WorldBuilderBoundedInventory.string(value.get(key), OPERATION, key);
	}
	private static long integer(Map<String,Object> value, String key)
		throws WorldBuilderContractException {
		return WorldBuilderBoundedInventory.integer(value.get(key), OPERATION, key);
	}
	private static boolean bool(Map<String,Object> value, String key)
		throws WorldBuilderContractException {
		return WorldBuilderBoundedInventory.bool(value.get(key), OPERATION, key);
	}
	private static WorldBuilderContractException failure(String path, String message) {
		return new WorldBuilderContractException(WorldBuilderErrorCodes.CONVERSION_BLOCKED,
			OPERATION, path, false, message,
			"Use only the exact bounded provider Current Base runtime bundle.");
	}

	private static final class Digest {
		final long size; final String sha256;
		Digest(long size, String sha256) { this.size = size; this.sha256 = sha256; }
	}
}
