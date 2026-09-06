package com.openrsc.worldbuilder;

import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.FileVisitResult;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
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
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

/** Built-in, data-only Preservation staging. No target byte selects executable code. */
final class WorldBuilderPreservationStagedMigrator {
	static final String SQLITE_SOURCE = "server/inc/sqlite/preservation.db";
	static final String CONFIG_OUTPUT =
		"migration/output/config/current-base-configuration.json";
	static final String SQLITE_OUTPUT = "migration/output/state/current-base.db";
	static final String SQLITE_EVIDENCE =
		"migration/output/state/current-base-migration-evidence.json";
	static final String STATE_CONTRACT_BUNDLE =
		"contracts/runtime/current-base-v1/state-migration.json";
	static final String STATE_TOOL_BUNDLE = "runtime/server/core.jar";
	static final String STATE_MAIN_CLASS =
		"com.openrsc.server.database.CurrentBaseStateMigration";
	private static final String STATE_CONTRACT_SHA256 =
		"fed89bd2add4fdc064d37b28b9332d30de34ec6875f9ec5618844d167bd0974b";
	// Exact reviewed successor contract; invocation still requires the selected
	// inventory-bound provider artifact, never an arbitrary executable or manifest.
	private static final String SUCCESSOR_CONTRACT_SHA256 =
		"f1a055b77fc54aa4157414991972be83a43a5c48da0518da5ac6c91101b5e94a";
	static final String CURRENT_SQLITE_ROW = "current-base-sqlite-to-current-base-v1";
	private static final List<Object> CURRENT_SQLITE_ROWS = java.util.Collections.<Object>singletonList(CURRENT_SQLITE_ROW);
	private static final List<Object> CURRENT_SCHEMA_FINGERPRINTS = java.util.Collections.unmodifiableList(Arrays.<Object>asList(
		"355fa60476c8ed2acee13e895f7ed39dc877df82a267af7e3434552d1fb6934b",
		"4f17364a3d9428efaeb3a349da96b02e75a0cfec41844756d7fd4396ee9e80d7",
		"0a132ad29a0632715296b78e249ef5f51b5cc89bb157ad26bc2bd9d035918cb3"));
	static final List<Object> SQLITE_ROWS = java.util.Collections.unmodifiableList(
		Arrays.<Object>asList("preservation-retro-sqlite-to-current-base-v1",
			"preservation-core-sqlite-to-current-base-v1",
			"preservation-initialized-sqlite-to-current-base-v1"));
	private static final List<Object> MARIA_ROWS = java.util.Collections.<Object>singletonList(
		"preservation-retro-mariadb-to-current-base-v1");
	private static final long MAX_SQLITE_BYTES = 4294967296L;
	static long processTimeoutSeconds = 120L; // package-private sealed test seam
	private static final byte[] SQLITE_MAGIC = new byte[] {
		'S','Q','L','i','t','e',' ','f','o','r','m','a','t',' ','3',0
	};

	private WorldBuilderPreservationStagedMigrator() {}

	static Map<String,Object> plan(Path target, Map<String,Object> typed,
		WorldBuilderProviderCatalog.Composition composition, boolean mapReady,
		WorldBuilderPackedConverter.Inspection mapInspection)
		throws WorldBuilderContractException {
		return planSource(target, typed, composition, mapReady, mapInspection, SQLITE_SOURCE, false);
	}

	/** Read live state only through the authenticated installed generation, not its old migration seal. */
	static Map<String,Object> planManaged(Path target, Map<String,Object> typed,
		WorldBuilderProviderCatalog.Composition composition, boolean mapReady,
		WorldBuilderPackedConverter.Inspection mapInspection, Map<String,Object> installedSpecification)
		throws IOException, WorldBuilderContractException {
		Map<String,Object> verified = WorldBuilderCurrentRuntimeInstalledGeneration.readSpecification(target);
		if (!WorldBuilderJsonDocuments.pretty(verified).equals(WorldBuilderJsonDocuments.pretty(installedSpecification)))
			throw blocked("Installed generation changed before current-state migration planning.");
		Path root = target.toRealPath();
		Path state = Paths.get(string(verified, "serverStateRoot")).resolve("current_base.db");
		if (!state.startsWith(root)) throw blocked("Installed current state is outside the exact target.");
		return planSource(root, typed, composition, mapReady, mapInspection,
			root.relativize(state).toString().replace('\\', '/'), true);
	}

	private static Map<String,Object> planSource(Path target, Map<String,Object> typed,
		WorldBuilderProviderCatalog.Composition composition, boolean mapReady,
		WorldBuilderPackedConverter.Inspection mapInspection, String sourceRelative, boolean current)
		throws WorldBuilderContractException {
		List<Object> outputs = new ArrayList<Object>();
		byte[] config = WorldBuilderJsonDocuments.pretty(typed)
			.getBytes(StandardCharsets.UTF_8);
		outputs.add(output(CONFIG_OUTPUT, "typed-configuration", "", "",
			config.length, WorldBuilderHashes.sha256(config), "0600"));

		Map<String,Object> database = object(typed.get("databaseMigration"));
		String engine = string(database, "engine");
		if (current && !"sqlite".equals(engine)) throw blocked("Managed Current Base successors require SQLite state.");
		Path sqlite = target.resolve(sourceRelative);
		boolean sqlitePresent = "sqlite".equals(engine)
			&& Files.exists(sqlite, LinkOption.NOFOLLOW_LINKS);
		if (sqlitePresent) requireClosedSqliteSnapshot(target, sqlite);
		Map<String,Object> provider = providerStateBinding(composition);
		if (current && !SUCCESSOR_CONTRACT_SHA256.equals(string(provider, "contractSha256")))
			throw blocked("Selected provider does not support current-to-current state migration.");
		provider.put("engine", engine);
		provider.put("migrationRowIds", current ? CURRENT_SQLITE_ROWS : migrationRows(engine));
		provider.put("sourceRelativePath", "sqlite".equals(engine) ? sourceRelative : "");
		provider.put("sourceSha256", sqlitePresent ? fileHash(sqlite, sourceRelative) : "");
		provider.put("stageRelativePath", "sqlite".equals(engine) ? SQLITE_OUTPUT : "");
		provider.put("evidenceRelativePath", SQLITE_EVIDENCE);
		provider.put("evidenceSchemaId", "current-base-state-migration-evidence-v1");
		provider.put("host", database.get("host"));
		provider.put("port", database.get("port"));
		provider.put("sourceSchema", database.get("sourceSchema"));
		provider.put("stageSchema", database.get("stageSchema"));
		provider.put("userEnvironmentName", database.get("userEnvironmentName"));
		provider.put("passwordEnvironmentName", database.get("passwordEnvironmentName"));

		Map<String,Object> result = new LinkedHashMap<String,Object>();
		result.put("implementationId", "preservation-staged-data-migrator-v1");
		result.put("requiredStateMigrationContractId", "current-base-state-migration-v1");
		result.put("requiredStateMigrationRowIds", provider.get("migrationRowIds"));
		List<Object> roles = new ArrayList<Object>();
		roles.add("state-migration-manifest"); roles.add("contract-schema");
		roles.add("server-runtime");
		result.put("requiredProviderArtifactRoles", roles);
		result.put("providerStateMigration", provider);
		result.put("typedConfigurationReady", Boolean.TRUE);
		result.put("sqliteSnapshotReady", Boolean.valueOf(sqlitePresent));
		result.put("sqliteSchemaMigrationReady", Boolean.valueOf(sqlitePresent));
		result.put("mariaDbMigrationReady", Boolean.FALSE);
		result.put("canonicalMapPackageReady", Boolean.valueOf(mapReady));
		result.put("stagedOutputs", outputs);
		Map<String,Object> runtimeLayout;
		try {
			runtimeLayout = WorldBuilderCurrentRuntimeLayout.inspect(composition);
		} catch (IOException unavailable) {
			runtimeLayout = WorldBuilderCurrentRuntimeLayout.unavailable(
				"provider-runtime-artifacts-not-built");
		} catch (WorldBuilderContractException unavailable) {
			runtimeLayout = WorldBuilderCurrentRuntimeLayout.unavailable(
				"provider-runtime-layout-contract-not-satisfied");
		}
		result.put("runtimeLayout", runtimeLayout);
		List<Object> blockers = new ArrayList<Object>();
		try {
			outputs.addAll(WorldBuilderCurrentRuntimeLaunchInputs.plan(composition,
				typed, runtimeLayout, mapInspection));
		} catch (IOException unavailable) {
			throw drift("runtime-launch-inputs", "Provider launch defaults could not be read.", unavailable);
		}
		if (outputs.size() == 1) blockers.add("runtime-launch-inputs-not-ready");
		blockers.addAll(array(typed.get("configurationBlockers")));
		if ("sqlite".equals(engine) && !sqlitePresent)
			blockers.add("reviewed-offline-sqlite-snapshot-not-found");
		if ("mariadb".equals(engine))
			blockers.add("mariadb-external-stage-rollback-not-implemented");
		if (!mapReady) blockers.add("complete-canonical-map-package-conversion-required");
		blockers.add("live-instance-installation-and-recovery-required");
		if (!WorldBuilderBoundedInventory.bool(runtimeLayout.get("ready"),
				"preservation-migration", "ready"))
			blockers.add("runnable-current-runtime-layout-materialization-required");
		blockers.add("editor-installed-execution-verifier-integration-required");
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
			if ("typed-configuration".equals(kind)
				|| WorldBuilderCurrentRuntimeLaunchInputs.paths().containsKey(kind)) {
				if (!Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) throw blocked(
					"Configuration and launch bytes must be supplied by the bound migration plan.");
			} else throw blocked("Migration output kind is not compiled into this migrator.");
			WorldBuilderReadOnlyTarget.open(stage).requiredFile(relative);
			requireOutput(destination, record);
		}
		Map<String,Object> state = object(execution.get("providerStateMigration"));
		if ("sqlite".equals(string(state, "engine"))) invokeSqlite(target, stage, state);
		else throw blocked(
			"MariaDB migration is previewable but not mutation-authorized until external-stage rollback is transactional.");
	}

	static void writeTypedConfiguration(Path stage, Map<String,Object> typed,
		Map<String,Object> execution, Map<String,Object> mapMigration) throws IOException, WorldBuilderContractException {
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
		WorldBuilderCurrentRuntimeLaunchInputs.write(stage, typed, execution, mapMigration);
	}

	static void verify(Path target, Path stage, Map<String,Object> execution,
		Map<String,Object> mapMigration)
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
			WorldBuilderReadOnlyTarget.open(stage).requiredFile(stagedRelative);
			requireOutput(output, record);
		}
		Map<String,Object> state = object(execution.get("providerStateMigration"));
		List<String> statePaths = new ArrayList<String>();
		if (!string(state, "stageRelativePath").isEmpty())
			statePaths.add(string(state, "stageRelativePath"));
		statePaths.add(string(state, "evidenceRelativePath"));
		for (String stagedRelative : statePaths) {
			if (!stagedRelative.startsWith("migration/output/")) throw blocked(
				"Provider state-migration output escaped its compiled namespace.");
			String relative = stagedRelative.substring("migration/output/".length());
			expectedFiles.add(relative);
			int slash = relative.lastIndexOf('/');
			while (slash > 0) {
				expectedDirectories.add(relative.substring(0, slash));
				slash = relative.lastIndexOf('/', slash - 1);
			}
		}
		final Set<String> actualFiles = new HashSet<String>();
		final Set<String> actualDirectories = new HashSet<String>();
		final boolean mapPackageReady = WorldBuilderBoundedInventory.bool(
			execution.get("canonicalMapPackageReady"), "preservation-migration",
			"canonicalMapPackageReady");
		if (mapPackageReady) {
			for (Object raw : array(mapMigration.get("outputInventory"))) {
				Map<String,Object> record = object(raw);
				String relative = string(record, "relativePath");
				expectedFiles.add(relative.substring("migration/output/".length()));
				addParentDirectories(relative.substring("migration/output/".length()),
					expectedDirectories);
			}
		}
		Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
			@Override public FileVisitResult preVisitDirectory(Path directory,
				BasicFileAttributes attributes) throws IOException {
				if (!attributes.isDirectory() || Files.isSymbolicLink(directory))
					throw new IOException("linked staged migration directory");
				String relative = root.equals(directory) ? ""
					: root.relativize(directory).toString().replace('\\', '/');
				actualDirectories.add(relative);
				return FileVisitResult.CONTINUE;
			}
			@Override public FileVisitResult visitFile(Path file,
				BasicFileAttributes attributes) throws IOException {
				if (!attributes.isRegularFile() || Files.isSymbolicLink(file))
					throw new IOException("linked staged migration file");
				String relative = root.relativize(file).toString().replace('\\', '/');
				actualFiles.add(relative);
				return FileVisitResult.CONTINUE;
			}
		});
		if (!expectedFiles.equals(actualFiles)
			|| !expectedDirectories.equals(actualDirectories)) throw blocked(
			"Staged migration output tree has extra or missing paths.");
		verifySqlite(target, stage, object(execution.get("providerStateMigration")));
		if (mapPackageReady) {
			for (Object raw : array(mapMigration.get("outputInventory"))) {
				Map<String,Object> record = object(raw);
				String relative = string(record, "relativePath");
				Path output = stage.resolve(relative);
				requireOutput(output, record);
				if (!string(record, "mode").equals(fileMode(output))) throw blocked(
					"Canonical map output mode differs from its reviewed inventory.");
			}
		}
	}

	private static void addParentDirectories(String relative, Set<String> values) {
		int slash = relative.lastIndexOf('/');
		while (slash > 0) {
			values.add(relative.substring(0, slash));
			slash = relative.lastIndexOf('/', slash - 1);
		}
	}

	private static Map<String,Object> providerStateBinding(
		WorldBuilderProviderCatalog.Composition composition)
		throws WorldBuilderContractException {
		if (composition == null) throw blocked(
			"Provider composition is required for production state migration.");
		WorldBuilderProviderCatalog.Artifact contract = null;
		WorldBuilderProviderCatalog.Artifact tool = null;
		for (WorldBuilderProviderCatalog.Artifact artifact : composition.artifacts) {
			String role = string(artifact.inventory, "role");
			if ("state-migration-manifest".equals(role)) {
				if (contract != null || !STATE_CONTRACT_BUNDLE.equals(artifact.bundlePath))
					throw blocked("Provider state-migration manifest role is ambiguous.");
				contract = artifact;
			} else if ("server-runtime".equals(role)) {
				if (tool != null || !STATE_TOOL_BUNDLE.equals(artifact.bundlePath))
					throw blocked("Provider state-migration tool role is ambiguous.");
				tool = artifact;
			}
		}
		if (contract == null || tool == null) throw blocked(
			"Provider composition omits the closed state-migration manifest or server runtime.");
		String contractHash = string(contract.inventory, "sha256");
		if (!reviewedStateContract(contractHash)) throw blocked(
			"Provider state-migration manifest is not the compiled reviewed contract.");
		validateStateContract(contract.source);
		Map<String,Object> result = new LinkedHashMap<String,Object>();
		result.put("contractBundlePath", contract.bundlePath);
		result.put("contractSha256", contractHash);
		result.put("toolBundlePath", tool.bundlePath);
		result.put("toolSha256", string(tool.inventory, "sha256"));
		result.put("toolArtifactRole", "server-runtime");
		result.put("mainClass", STATE_MAIN_CLASS);
		return result;
	}

	static List<Object> migrationRows(String engine) throws WorldBuilderContractException {
		if ("sqlite".equals(engine)) return SQLITE_ROWS;
		if ("mariadb".equals(engine)) return MARIA_ROWS;
		throw blocked("Provider state-migration engine is unsupported.");
	}

	private static boolean reviewedStateContract(String hash) {
		return STATE_CONTRACT_SHA256.equals(hash) || SUCCESSOR_CONTRACT_SHA256.equals(hash);
	}

	private static boolean currentStateBinding(Map<String,Object> binding) throws WorldBuilderContractException {
		return CURRENT_SQLITE_ROWS.equals(array(binding.get("migrationRowIds")));
	}

	private static boolean admittedSchema(String row, String fingerprint) throws WorldBuilderContractException {
		return CURRENT_SQLITE_ROW.equals(row) ? CURRENT_SCHEMA_FINGERPRINTS.contains(fingerprint)
			: sqliteSchemaFingerprint(row).equals(fingerprint);
	}

	private static String sqliteSchemaFingerprint(String row)
		throws WorldBuilderContractException {
		int index = SQLITE_ROWS.indexOf(row);
		if (index < 0) throw blocked("Provider SQLite migration row is unsupported.");
		return new String[] {
			"e5e320d210ec34e832650e50c23d3aef787c47798afe79cfe061d5cf4c1a6657",
			"373648e4f9192ca29d0dda613b6807724776299e5919d93d8af894289ae67296",
			"71a3804a2482a78fc96f79c0a3082e38a28d4098748160c9d7bff81ab6bdfe00"
		}[index];
	}

	private static void validateStateContract(Path path)
		throws WorldBuilderContractException {
		Map<String,Object> contract;
		try {
			contract = WorldBuilderJsonDocuments.readObject(path);
		} catch (IOException failure) {
			throw drift(STATE_CONTRACT_BUNDLE,
				"Provider state-migration manifest could not be read.", failure);
		} catch (WorldBuilderDiscoveryException failure) {
			throw drift(STATE_CONTRACT_BUNDLE,
				"Provider state-migration manifest is malformed.", failure);
		}
		WorldBuilderBoundedInventory.exactKeys(contract, "preservation-migration",
			"schemaId", "manifestType", "migrationRows", "targetStateContractId",
			"supportedSources", "transformations", "resourceLimits", "invocation", "evidenceContract");
		List<Object> allRows = new ArrayList<Object>(SQLITE_ROWS);
		allRows.addAll(MARIA_ROWS);
		if (SUCCESSOR_CONTRACT_SHA256.equals(fileHash(path, STATE_CONTRACT_BUNDLE))) allRows.add(CURRENT_SQLITE_ROW);
		if (!"current-base-state-migration-v1".equals(string(contract, "schemaId"))
			|| !"current-base-state-migration".equals(string(contract, "manifestType"))
			|| !allRows.equals(array(contract.get("migrationRows")))
			|| !"canonical-public-state-v1".equals(
				string(contract, "targetStateContractId"))) throw blocked(
			"Provider state-migration manifest identity changed.");
		Map<String,Object> invocation = object(contract.get("invocation"));
		WorldBuilderBoundedInventory.exactKeys(invocation, "preservation-migration",
			"toolArtifactRole", "mainClass", "arguments");
		if (!"server-runtime".equals(string(invocation, "toolArtifactRole"))
			|| !STATE_MAIN_CLASS.equals(string(invocation, "mainClass"))) throw blocked(
			"Provider state-migration invocation is not compiled into the Editor.");
		Map<String,Object> arguments = object(invocation.get("arguments"));
		WorldBuilderBoundedInventory.exactKeys(arguments, "preservation-migration",
			"common", "sqlite", "mariadb");
		if (!array(arguments.get("common")).equals(
				Arrays.<Object>asList("--contract", "--engine", "--evidence"))
			|| !array(arguments.get("sqlite")).equals(
				Arrays.<Object>asList("--source", "--stage"))
			|| !array(arguments.get("mariadb")).equals(Arrays.<Object>asList(
				"--host", "--port", "--source-schema", "--stage-schema",
				"--user-env", "--password-env"))) throw blocked(
			"Provider state-migration argument contract changed.");
		List<Object> observedRows = new ArrayList<Object>();
		for (Object raw : array(contract.get("supportedSources"))) {
			Map<String,Object> engine = object(raw);
			String row = string(engine, "migrationRowId");
			boolean current = CURRENT_SQLITE_ROW.equals(row);
			if (!(current && "sqlite".equals(string(engine, "engine")))
				&& !migrationRows(string(engine, "engine")).contains(row))
				throw blocked("Provider source row is bound to the wrong database engine.");
			observedRows.add(row);
			if ("sqlite".equals(string(engine, "engine"))) {
				boolean sqlite = "new-database-file".equals(string(engine, "stageMode"))
					&& "forbidden-read-only".equals(string(engine, "sourceMutation"))
					&& "none".equals(string(engine, "credentialPolicy"))
					&& (current ? CURRENT_SCHEMA_FINGERPRINTS.equals(array(engine.get("sourceSchemaFingerprints")))
						&& "current-base-byte-copy-v1".equals(string(engine, "transformationId"))
						: sqliteSchemaFingerprint(row).equals(string(engine, "sourceSchemaFingerprint")));
				if (!sqlite) throw blocked("Provider SQLite source migration row is incomplete.");
			}
		}
		if (!allRows.equals(observedRows)) throw blocked("Provider source migration rows changed.");
	}

	private static void invokeSqlite(Path target, Path stage, Map<String,Object> binding)
		throws IOException, WorldBuilderContractException {
		validateStateBinding(binding);
		Path source = WorldBuilderReadOnlyTarget.open(target).requiredFile(
			string(binding, "sourceRelativePath"));
		requireClosedSqliteSnapshot(target, source);
		if (!WorldBuilderHashes.sha256(source).equals(string(binding, "sourceSha256")))
			throw blocked("SQLite source changed after preview.");
		Path contract = WorldBuilderPortablePath.resolveContained(stage,
			string(binding, "contractBundlePath"), "preservation-migration");
		Path tool = WorldBuilderPortablePath.resolveContained(stage,
			string(binding, "toolBundlePath"), "preservation-migration");
		requireBoundProviderFile(contract, string(binding, "contractSha256"),
			"state-migration manifest");
		requireBoundProviderFile(tool, string(binding, "toolSha256"), "server runtime");
		Path output = WorldBuilderPortablePath.resolveContained(stage,
			string(binding, "stageRelativePath"), "preservation-migration");
		Path evidence = WorldBuilderPortablePath.resolveContained(stage,
			string(binding, "evidenceRelativePath"), "preservation-migration");
		Files.createDirectories(output.getParent());
		if (Files.exists(output, LinkOption.NOFOLLOW_LINKS)
			|| Files.exists(evidence, LinkOption.NOFOLLOW_LINKS)) throw blocked(
			"Provider state-migration outputs already exist.");
		Path java = Paths.get(System.getProperty("java.home"), "bin",
			System.getProperty("os.name", "").toLowerCase().contains("win")
				? "java.exe" : "java");
		List<String> command = Arrays.asList(java.toString(), "-cp", tool.toString(),
			STATE_MAIN_CLASS, "--contract", contract.toString(), "--engine", "sqlite",
			"--source", source.toString(), "--stage", output.toString(),
			"--evidence", evidence.toString());
		Process process = new ProcessBuilder(command).directory(stage.toFile())
			.redirectErrorStream(true).start();
		BoundedProcessOutput outputCapture = new BoundedProcessOutput(
			process.getInputStream(), process);
		Thread outputThread = new Thread(outputCapture,
			"world-builder-state-migration-output");
		outputThread.setDaemon(true); outputThread.start();
		boolean finished;
		try {
			finished = process.waitFor(processTimeoutSeconds, TimeUnit.SECONDS);
		} catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
			if (!terminate(process)) throw blocked(
				"Interrupted provider state migration could not be stopped; preserve staging.");
			joinOutput(outputThread);
			Files.deleteIfExists(output); Files.deleteIfExists(evidence);
			throw blocked("Provider state migration was interrupted.");
		}
		if (!finished) {
			if (!terminate(process)) throw blocked(
				"Timed-out provider state migration could not be stopped; preserve staging.");
			joinOutput(outputThread);
			Files.deleteIfExists(output); Files.deleteIfExists(evidence);
			throw blocked("Provider state migration exceeded its bounded timeout.");
		}
		joinOutput(outputThread);
		if (outputCapture.failure != null) {
			Files.deleteIfExists(output); Files.deleteIfExists(evidence);
			throw blocked("Provider state migrator output could not be read safely.");
		}
		if (outputCapture.exceeded) {
			Files.deleteIfExists(output); Files.deleteIfExists(evidence);
			throw blocked("Provider state migrator exceeded its output bound.");
		}
		byte[] captured = outputCapture.bytes();
		if (process.exitValue() != 0) {
			Files.deleteIfExists(output); Files.deleteIfExists(evidence);
			String diagnostic = new String(captured, StandardCharsets.UTF_8)
				.replace('\n', ' ').replace('\r', ' ').trim();
			throw blocked("Provider state migration refused the source"
				+ (diagnostic.isEmpty() ? "." : ": " + diagnostic));
		}
		setMode(output, "0600"); setMode(evidence, "0600");
		if (!WorldBuilderHashes.sha256(source).equals(string(binding, "sourceSha256")))
			throw blocked("Provider state migration changed its read-only source.");
	}

	private static boolean terminate(Process process) {
		process.destroyForcibly();
		try {
			return process.waitFor(10L, TimeUnit.SECONDS);
		} catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt(); return !process.isAlive();
		}
	}

	private static void joinOutput(Thread outputThread)
		throws WorldBuilderContractException {
		try {
			outputThread.join(10_000L);
		} catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt(); throw blocked(
				"Provider state migration output collection was interrupted.");
		}
		if (outputThread.isAlive()) throw blocked(
			"Provider state migration output collector did not stop.");
	}

	private static final class BoundedProcessOutput implements Runnable {
		private static final int LIMIT = 65536;
		private final InputStream input;
		private final Process process;
		private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		volatile boolean exceeded;
		volatile IOException failure;

		BoundedProcessOutput(InputStream input, Process process) {
			this.input = input; this.process = process;
		}

		@Override public void run() {
			byte[] buffer = new byte[4096];
			try (InputStream stream = input) {
				for (int read; (read = stream.read(buffer)) >= 0;) {
					if (read == 0) continue;
					if (bytes.size() + read > LIMIT) {
						exceeded = true; process.destroyForcibly(); return;
					}
					bytes.write(buffer, 0, read);
				}
			} catch (IOException problem) {
				if (!exceeded) failure = problem;
			}
		}

		synchronized byte[] bytes() { return bytes.toByteArray(); }
	}

	private static void verifySqlite(Path target, Path stage, Map<String,Object> binding)
		throws IOException, WorldBuilderContractException {
		validateStateBinding(binding);
		Path source = WorldBuilderReadOnlyTarget.open(target).requiredFile(
			string(binding, "sourceRelativePath"));
		Path output = WorldBuilderPortablePath.resolveContained(stage,
			string(binding, "stageRelativePath"), "preservation-migration");
		Path evidencePath = WorldBuilderPortablePath.resolveContained(stage,
			string(binding, "evidenceRelativePath"), "preservation-migration");
		requireSqliteFile(output);
		if (!"0600".equals(fileMode(output)) || !"0600".equals(fileMode(evidencePath)))
			throw blocked("Provider state-migration output permissions changed.");
		Map<String,Object> evidence;
		try {
			evidence = WorldBuilderJsonDocuments.readObject(evidencePath);
		} catch (WorldBuilderDiscoveryException malformed) {
			throw blocked("Provider state-migration evidence is malformed.");
		}
		WorldBuilderBoundedInventory.exactKeys(evidence, "preservation-migration",
			"schemaId", "manifestType", "migrationRowId", "engine", "contractSha256",
			"sourceSchemaFingerprint", "sourceStateSha256",
			"stagedSourceProjectionSha256", "sourceBeforeSha256", "sourceAfterSha256",
			"sourceUnchanged", "stageLocation", "rollbackPolicy", "status");
		String sourceHash = string(binding, "sourceSha256");
		if (!"current-base-state-migration-evidence-v1".equals(
				string(evidence, "schemaId"))
			|| !"current-base-state-migration-evidence".equals(
				string(evidence, "manifestType"))
			|| !array(binding.get("migrationRowIds")).contains(string(evidence, "migrationRowId"))
			|| !admittedSchema(string(evidence, "migrationRowId"), string(evidence, "sourceSchemaFingerprint"))
			|| !"sqlite".equals(string(evidence, "engine"))
			|| !string(binding, "contractSha256").equals(
				string(evidence, "contractSha256"))
			|| !sourceHash.equals(string(evidence, "sourceBeforeSha256"))
			|| !sourceHash.equals(string(evidence, "sourceAfterSha256"))
			|| !WorldBuilderHashes.sha256(source).equals(sourceHash)
			|| !WorldBuilderBoundedInventory.bool(evidence.get("sourceUnchanged"),
				"preservation-migration", "sourceUnchanged")
			|| !string(evidence, "sourceStateSha256").equals(
				string(evidence, "stagedSourceProjectionSha256"))
			|| !output.toString().equals(string(evidence, "stageLocation"))
			|| !"discard-stage-only".equals(string(evidence, "rollbackPolicy"))
			|| !"verified".equals(string(evidence, "status"))) throw blocked(
			"Provider state-migration evidence failed its closed verification contract.");
		if (currentStateBinding(binding) && !sourceHash.equals(WorldBuilderHashes.sha256(output)))
			throw blocked("Current-to-current state migration must preserve every source byte.");
		for (String key : Arrays.asList("sourceSchemaFingerprint", "sourceStateSha256",
			"stagedSourceProjectionSha256")) requireHash(string(evidence, key), key);
	}

	static void validateStateBinding(Map<String,Object> binding)
		throws WorldBuilderContractException {
		WorldBuilderBoundedInventory.exactKeys(binding, "preservation-migration",
			"contractBundlePath", "contractSha256", "toolBundlePath", "toolSha256",
			"toolArtifactRole", "mainClass", "migrationRowIds", "engine",
			"sourceRelativePath", "sourceSha256", "stageRelativePath",
			"evidenceRelativePath", "evidenceSchemaId", "host", "port",
			"sourceSchema", "stageSchema", "userEnvironmentName",
			"passwordEnvironmentName");
		boolean current = currentStateBinding(binding);
		if (!STATE_CONTRACT_BUNDLE.equals(string(binding, "contractBundlePath"))
			|| !(current ? SUCCESSOR_CONTRACT_SHA256.equals(string(binding, "contractSha256"))
				: reviewedStateContract(string(binding, "contractSha256")))
			|| !STATE_TOOL_BUNDLE.equals(string(binding, "toolBundlePath"))
			|| !"server-runtime".equals(string(binding, "toolArtifactRole"))
			|| !STATE_MAIN_CLASS.equals(string(binding, "mainClass"))
			|| !(current ? "sqlite".equals(string(binding, "engine"))
				: migrationRows(string(binding, "engine")).equals(array(binding.get("migrationRowIds"))))
			|| !SQLITE_EVIDENCE.equals(string(binding, "evidenceRelativePath"))
			|| !"current-base-state-migration-evidence-v1".equals(
				string(binding, "evidenceSchemaId"))) throw blocked(
			"Provider state-migration binding changed from the compiled profile.");
		requireHash(string(binding, "toolSha256"), "toolSha256");
		String sourceHash = string(binding, "sourceSha256");
		if (!sourceHash.isEmpty()) requireHash(sourceHash, "sourceSha256");
		String engine = string(binding, "engine");
		if ("sqlite".equals(engine)) {
			if (!(current ? currentStatePath(string(binding, "sourceRelativePath"))
				: SQLITE_SOURCE.equals(string(binding, "sourceRelativePath")))
				|| !SQLITE_OUTPUT.equals(string(binding, "stageRelativePath"))
				|| !string(binding, "host").isEmpty()
				|| integer(binding, "port") != 0L
				|| !string(binding, "sourceSchema").isEmpty()
				|| !string(binding, "stageSchema").isEmpty()
				|| !string(binding, "userEnvironmentName").isEmpty()
				|| !string(binding, "passwordEnvironmentName").isEmpty()) throw blocked(
				"Provider SQLite state-migration binding changed.");
		} else if ("mariadb".equals(engine)) {
			if (!string(binding, "sourceRelativePath").isEmpty() || !sourceHash.isEmpty()
				|| !string(binding, "stageRelativePath").isEmpty()
				|| !"127.0.0.1".equals(string(binding, "host"))
				|| integer(binding, "port") < 1L || integer(binding, "port") > 65535L
				|| !string(binding, "sourceSchema").matches("[A-Za-z_][A-Za-z0-9_]{0,63}")
				|| !string(binding, "stageSchema").matches("[A-Za-z_][A-Za-z0-9_]{0,63}")
				|| string(binding, "sourceSchema").equals(string(binding, "stageSchema"))
				|| !string(binding, "userEnvironmentName").matches("[A-Z][A-Z0-9_]{0,127}")
				|| !string(binding, "passwordEnvironmentName").matches("[A-Z][A-Z0-9_]{0,127}")
				|| string(binding, "userEnvironmentName").equals(
					string(binding, "passwordEnvironmentName"))) throw blocked(
				"Provider MariaDB state-migration binding changed.");
		} else throw blocked("Provider state-migration engine is unsupported.");
	}

	private static boolean currentStatePath(String relative) {
		// Initial state and fresh successor branches only. This validates persisted
		// evidence; planManaged separately authenticates the active descriptor.
		return relative.equals(".world-builder/current-runtime/instance/state/server/current_base.db")
			|| relative.matches("\\.world-builder/current-runtime/instance/state/[A-Za-z0-9][A-Za-z0-9._-]{0,127}/server/current_base\\.db");
	}

	private static void requireBoundProviderFile(Path path, String hash, String role)
		throws IOException, WorldBuilderContractException {
		if (!safeRegular(path) || !WorldBuilderHashes.sha256(path).equals(hash))
			throw blocked("Staged provider " + role + " differs from the reviewed inventory.");
	}

	private static void requireHash(String value, String label)
		throws WorldBuilderContractException {
		if (!value.matches("[0-9a-f]{64}")) throw blocked(
			"Provider state-migration " + label + " is not a SHA-256 value.");
	}

	private static String fileHash(Path path, String relative)
		throws WorldBuilderContractException {
		try {
			return WorldBuilderHashes.sha256(path);
		} catch (IOException failure) {
			throw drift(relative, "Migration source could not be hashed.", failure);
		}
	}

	private static void requireClosedSqliteSnapshot(Path target, Path source)
		throws WorldBuilderContractException {
		if (!safeRegular(source)) throw blocked("SQLite state is missing, linked, or non-regular.");
		for (String suffix : new String[] {"-journal", "-wal", "-shm"}) {
			if (Files.exists(target.resolve(SQLITE_SOURCE + suffix), LinkOption.NOFOLLOW_LINKS))
				throw blocked("SQLite sidecar state exists; obtain one closed offline snapshot.");
		}
		try {
			requireSqliteFile(source);
		} catch (IOException failure) {
			throw drift(SQLITE_SOURCE, "SQLite snapshot could not be read.", failure);
		}
	}

	private static void requireSqliteFile(Path source)
		throws IOException, WorldBuilderContractException {
		if (!safeRegular(source)) throw blocked("SQLite state is missing, linked, or non-regular.");
		long size = Files.size(source);
		if (size < 512L || size > MAX_SQLITE_BYTES || size % 512L != 0L)
			throw blocked("SQLite snapshot size is unsupported or incomplete.");
		byte[] header = new byte[100];
		try (InputStream input = Files.newInputStream(source)) {
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
