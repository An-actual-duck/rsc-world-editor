package com.openrsc.worldbuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.FileVisitResult;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Built-in, data-only Preservation staging. No target byte selects executable code. */
final class WorldBuilderPreservationStagedMigrator {
	static final String SQLITE_SOURCE = "server/inc/sqlite/preservation.db";
	static final String CONFIG_OUTPUT =
		"migration/output/config/current-base-configuration.json";
	static final String SQLITE_OUTPUT = "migration/output/state/preservation.db";
	static final String MAP_OUTPUT = "migration/output/map/0_0_0.terrain";
	private static final long MAX_SQLITE_BYTES = 4294967296L;
	private static final byte[] SQLITE_MAGIC = new byte[] {
		'S','Q','L','i','t','e',' ','f','o','r','m','a','t',' ','3',0
	};

	private WorldBuilderPreservationStagedMigrator() {}

	static Map<String,Object> plan(Path target, Map<String,Object> typed,
		String mapRelative) throws WorldBuilderContractException {
		List<Object> outputs = new ArrayList<Object>();
		byte[] config = WorldBuilderJsonDocuments.pretty(typed)
			.getBytes(StandardCharsets.UTF_8);
		outputs.add(output(CONFIG_OUTPUT, "typed-configuration", "", "",
			config.length, WorldBuilderHashes.sha256(config), "0600"));

		Path map = target.resolve(mapRelative);
		boolean mapReady = false;
		if (safeRegular(map)) {
			try {
				if (Files.size(map) == WorldBuilderPackedTerrainCodec.BYTE_COUNT) {
					byte[] source = Files.readAllBytes(map);
					byte[] layered = WorldBuilderPackedTerrainCodec.toLayered(source);
					WorldBuilderPackedTerrainCodec.requireExactReverse(source, layered);
					outputs.add(output(MAP_OUTPUT, "canonical-terrain-sector", mapRelative,
						WorldBuilderHashes.sha256(source), layered.length,
						WorldBuilderHashes.sha256(layered), "0600"));
					mapReady = true;
				}
			} catch (IOException failure) {
				throw drift(mapRelative, "Legacy map could not be inventoried.", failure);
			}
		}

		Path sqlite = target.resolve(SQLITE_SOURCE);
		boolean sqlitePresent = Files.exists(sqlite, LinkOption.NOFOLLOW_LINKS);
		if (sqlitePresent) {
			requireClosedSqliteSnapshot(target, sqlite);
			try {
				outputs.add(output(SQLITE_OUTPUT, "sqlite-state-snapshot", SQLITE_SOURCE,
					WorldBuilderHashes.sha256(sqlite), Files.size(sqlite),
					WorldBuilderHashes.sha256(sqlite), "0600"));
			} catch (IOException failure) {
				throw drift(SQLITE_SOURCE, "SQLite snapshot could not be inventoried.", failure);
			}
		}

		Map<String,Object> result = new LinkedHashMap<String,Object>();
		result.put("implementationId", "preservation-staged-data-migrator-v1");
		result.put("requiredStateMigrationContractId", "current-base-state-migration-v1");
		result.put("requiredStateMigrationRowId", "preservation-retro-to-current-base-v1");
		List<Object> roles = new ArrayList<Object>();
		roles.add("state-migration-manifest"); roles.add("contract-schema");
		roles.add("state-migration-tool");
		result.put("requiredProviderArtifactRoles", roles);
		result.put("typedConfigurationReady", Boolean.TRUE);
		result.put("sqliteSnapshotReady", Boolean.valueOf(sqlitePresent));
		result.put("sqliteSchemaMigrationReady", Boolean.FALSE);
		result.put("mariaDbMigrationReady", Boolean.FALSE);
		result.put("canonicalMapSectorReady", Boolean.valueOf(mapReady));
		result.put("stagedOutputs", outputs);
		List<Object> blockers = new ArrayList<Object>();
		if (!sqlitePresent) blockers.add("reviewed-offline-sqlite-snapshot-not-found");
		blockers.add("provider-current-base-state-migration-v1-row-required");
		blockers.add("closed-mariadb-snapshot-and-restore-contract-required");
		if (!mapReady) blockers.add("complete-canonical-map-package-conversion-required");
		blockers.add("staged-and-installed-executable-verification-required");
		result.put("readinessBlockers", blockers);
		return result;
	}

	static void stage(Path target, Path stage, Map<String,Object> execution)
		throws IOException, WorldBuilderContractException {
		Path output = stage.resolve("migration/output");
		if (!Files.exists(output, LinkOption.NOFOLLOW_LINKS)) Files.createDirectories(output);
		if (!Files.isDirectory(output, LinkOption.NOFOLLOW_LINKS)
			|| Files.isSymbolicLink(output)) throw blocked(
			"Migration output staging is linked or not a directory.");
		for (Object raw : array(execution.get("stagedOutputs"))) {
			Map<String,Object> record = object(raw);
			String relative = string(record, "relativePath");
			Path destination = WorldBuilderPortablePath.resolveContained(stage, relative,
				"preservation-migration");
			Files.createDirectories(destination.getParent());
			String kind = string(record, "kind");
			if ("typed-configuration".equals(kind)) {
				if (!Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) throw blocked(
					"Typed configuration bytes must be supplied by the bound migration plan.");
				continue;
			} else if ("canonical-terrain-sector".equals(kind)) {
				Path source = WorldBuilderReadOnlyTarget.open(target).requiredFile(
					string(record, "sourceRelativePath"));
				requireSource(source, record);
				byte[] legacy = Files.readAllBytes(source);
				byte[] layered = WorldBuilderPackedTerrainCodec.toLayered(legacy);
				WorldBuilderPackedTerrainCodec.requireExactReverse(legacy, layered);
				Files.write(destination, layered, StandardOpenOption.CREATE_NEW,
					StandardOpenOption.WRITE);
			} else if ("sqlite-state-snapshot".equals(kind)) {
				Path source = WorldBuilderReadOnlyTarget.open(target).requiredFile(
					string(record, "sourceRelativePath"));
				requireClosedSqliteSnapshot(target, source);
				requireSource(source, record);
				Files.copy(source, destination, StandardCopyOption.COPY_ATTRIBUTES);
			} else throw blocked("Migration output kind is not compiled into this migrator.");
			setMode(destination, string(record, "mode"));
			requireOutput(destination, record);
		}
	}

	static void writeTypedConfiguration(Path stage, Map<String,Object> typed,
		Map<String,Object> execution) throws IOException, WorldBuilderContractException {
		Map<String,Object> record = null;
		for (Object raw : array(execution.get("stagedOutputs"))) {
			Map<String,Object> candidate = object(raw);
			if ("typed-configuration".equals(string(candidate, "kind"))) record = candidate;
		}
		if (record == null) throw blocked("Typed configuration output is absent.");
		Path destination = WorldBuilderPortablePath.resolveContained(stage,
			string(record, "relativePath"), "preservation-migration");
		Files.createDirectories(destination.getParent());
		Files.write(destination, WorldBuilderJsonDocuments.pretty(typed)
			.getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE_NEW,
			StandardOpenOption.WRITE);
		setMode(destination, string(record, "mode"));
		requireOutput(destination, record);
	}

	static void verify(Path stage, Map<String,Object> execution)
		throws IOException, WorldBuilderContractException {
		final Path root = stage.resolve("migration/output");
		final Set<String> expectedFiles = new HashSet<String>();
		final Set<String> expectedDirectories = new HashSet<String>();
		expectedDirectories.add("");
		for (Object raw : array(execution.get("stagedOutputs"))) {
			Map<String,Object> record = object(raw);
			String stagedRelative = string(record, "relativePath");
			if (!stagedRelative.startsWith("migration/output/")) throw blocked(
				"Staged migration output escaped its compiled namespace.");
			String relative = stagedRelative.substring("migration/output/".length());
			expectedFiles.add(relative);
			int slash = relative.lastIndexOf('/');
			while (slash > 0) {
				expectedDirectories.add(relative.substring(0, slash));
				slash = relative.lastIndexOf('/', slash - 1);
			}
			Path output = WorldBuilderPortablePath.resolveContained(stage, stagedRelative,
				"preservation-migration");
			if (!Files.exists(output, LinkOption.NOFOLLOW_LINKS)) throw blocked(
				"Staged migration output is missing.");
			requireOutput(output, record);
		}
		final Set<String> actualFiles = new HashSet<String>();
		final Set<String> actualDirectories = new HashSet<String>();
		Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
			@Override public FileVisitResult preVisitDirectory(Path directory,
				BasicFileAttributes attributes) throws IOException {
				if (!attributes.isDirectory() || Files.isSymbolicLink(directory))
					throw new IOException("linked staged migration directory");
				actualDirectories.add(root.equals(directory) ? ""
					: root.relativize(directory).toString().replace('\\', '/'));
				return FileVisitResult.CONTINUE;
			}
			@Override public FileVisitResult visitFile(Path file,
				BasicFileAttributes attributes) throws IOException {
				if (!attributes.isRegularFile() || Files.isSymbolicLink(file))
					throw new IOException("linked staged migration file");
				actualFiles.add(root.relativize(file).toString().replace('\\', '/'));
				return FileVisitResult.CONTINUE;
			}
		});
		if (!expectedFiles.equals(actualFiles)
			|| !expectedDirectories.equals(actualDirectories)) throw blocked(
			"Staged migration output tree has extra or missing paths.");
	}

	private static void requireClosedSqliteSnapshot(Path target, Path source)
		throws WorldBuilderContractException {
		if (!safeRegular(source)) throw blocked("SQLite state is missing, linked, or non-regular.");
		for (String suffix : new String[] {"-journal", "-wal", "-shm"}) {
			if (Files.exists(target.resolve(SQLITE_SOURCE + suffix), LinkOption.NOFOLLOW_LINKS))
				throw blocked("SQLite sidecar state exists; obtain one closed offline snapshot.");
		}
		try {
			long size = Files.size(source);
			if (size < 512L || size > MAX_SQLITE_BYTES || size % 512L != 0L)
				throw blocked("SQLite snapshot size is unsupported or incomplete.");
			byte[] header = new byte[100];
			try (java.io.InputStream input = Files.newInputStream(source)) {
				int offset = 0;
				while (offset < header.length) {
					int count = input.read(header, offset, header.length - offset);
					if (count < 0) throw blocked("SQLite snapshot header is truncated.");
					offset += count;
				}
			}
			for (int index = 0; index < SQLITE_MAGIC.length; index++)
				if (header[index] != SQLITE_MAGIC[index]) throw blocked(
					"Durable state is not an exact SQLite 3 snapshot.");
			int pageSize = (header[16] & 255) * 256 + (header[17] & 255);
			if (pageSize == 1) pageSize = 65536;
			if (pageSize < 512 || pageSize > 65536
				|| (pageSize & (pageSize - 1)) != 0 || size % pageSize != 0)
				throw blocked("SQLite page size or file length is inconsistent.");
		} catch (IOException failure) {
			throw drift(SQLITE_SOURCE, "SQLite snapshot could not be read.", failure);
		}
	}

	private static void requireSource(Path source, Map<String,Object> record)
		throws IOException, WorldBuilderContractException {
		if (!WorldBuilderHashes.sha256(source).equals(string(record, "sourceSha256")))
			throw blocked("Migration source changed after preview.");
	}

	private static void requireOutput(Path output, Map<String,Object> record)
		throws IOException, WorldBuilderContractException {
		BasicFileAttributes attributes = Files.readAttributes(output,
			BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
		if (!attributes.isRegularFile() || attributes.isSymbolicLink()
			|| attributes.size() != integer(record, "size")
			|| !WorldBuilderHashes.sha256(output).equals(string(record, "sha256"))
			|| !fileMode(output).equals(string(record, "mode")))
			throw blocked("Staged migration output differs from its reviewed inventory.");
	}

	private static String fileMode(Path path) throws IOException {
		Object raw = Files.getAttribute(path, "unix:mode", LinkOption.NOFOLLOW_LINKS);
		return String.format("%04o", Integer.valueOf(((Number)raw).intValue() & 0777));
	}

	private static Map<String,Object> output(String relative, String kind,
		String source, String sourceHash, long size, String hash, String mode) {
		Map<String,Object> result = new LinkedHashMap<String,Object>();
		result.put("relativePath", relative); result.put("kind", kind);
		result.put("sourceRelativePath", source); result.put("sourceSha256", sourceHash);
		result.put("size", Long.valueOf(size)); result.put("sha256", hash);
		result.put("mode", mode); return result;
	}

	private static boolean safeRegular(Path path) {
		return Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
			&& !Files.isSymbolicLink(path);
	}

	private static void setMode(Path path, String mode) throws IOException {
		int bits = Integer.parseInt(mode, 8);
		Set<PosixFilePermission> values = EnumSet.noneOf(PosixFilePermission.class);
		PosixFilePermission[] flags = {PosixFilePermission.OWNER_READ,
			PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE,
			PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_WRITE,
			PosixFilePermission.GROUP_EXECUTE, PosixFilePermission.OTHERS_READ,
			PosixFilePermission.OTHERS_WRITE, PosixFilePermission.OTHERS_EXECUTE};
		int[] masks = {0400,0200,0100,0040,0020,0010,0004,0002,0001};
		for (int index = 0; index < masks.length; index++)
			if ((bits & masks[index]) != 0) values.add(flags[index]);
		Files.setPosixFilePermissions(path, values);
	}

	@SuppressWarnings("unchecked") private static Map<String,Object> object(Object value) {
		return (Map<String,Object>)value;
	}
	@SuppressWarnings("unchecked") private static List<Object> array(Object value) {
		return (List<Object>)value;
	}
	private static String string(Map<String,Object> value, String key)
		throws WorldBuilderContractException {
		return WorldBuilderBoundedInventory.string(value.get(key),
			"preservation-migration", key);
	}
	private static long integer(Map<String,Object> value, String key)
		throws WorldBuilderContractException {
		return WorldBuilderBoundedInventory.integer(value.get(key),
			"preservation-migration", key);
	}
	private static WorldBuilderContractException blocked(String message) {
		return new WorldBuilderContractException(WorldBuilderErrorCodes.CONVERSION_BLOCKED,
			"preservation-migration", "migration/output", false, message,
			"Keep the target offline and use only a reviewed closed migration input.");
	}
	private static WorldBuilderContractException drift(String relative, String message,
		Throwable cause) {
		return new WorldBuilderContractException(WorldBuilderErrorCodes.DISCOVERY_DRIFT,
			"preservation-migration", relative, false, message,
			"Keep the target offline and preview a fresh migration.", cause);
	}
}
