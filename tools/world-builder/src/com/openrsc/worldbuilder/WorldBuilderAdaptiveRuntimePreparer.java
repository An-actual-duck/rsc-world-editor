package com.openrsc.worldbuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Prepares and verifies the immutable part of one project-local adaptive runtime. */
final class WorldBuilderAdaptiveRuntimePreparer {
	static final String INVENTORY_FILE = "working/runtime/runtime-assets.sha256";
	static final String SERVER_DEFINITION_EVIDENCE =
		"working/runtime/server/evidence/adaptive-definitions.json";
	static final String CLIENT_DEFINITION_EVIDENCE =
		"working/runtime/client/evidence/adaptive-definitions.json";
	static final String SERVER_ASSET_EVIDENCE =
		"working/runtime/server/evidence/adaptive-assets.sha256";
	static final String CLIENT_ASSET_EVIDENCE =
		"working/runtime/client/evidence/adaptive-assets.sha256";
	static final String ASSET_ID = "adaptive-project-assets-v1";
	private static final String INVENTORY_HEADER =
		"adaptive-world-builder-runtime-assets-v1";
	private static final String ASSET_HEADER =
		"adaptive-world-builder-asset-evidence-v1";
	private static final String OPERATION = "adaptive-runtime-preparation";
	private static final Set<String> GENERATED_SOURCE_PATHS =
		Collections.unmodifiableSet(new HashSet<String>(Arrays.asList(
			"server/world-builder.conf",
			"server/connections.conf",
			"server/inc/sqlite/world_builder.db",
			"server/inc/sqlite/world-builder.credential",
			"client/clientSettings.conf",
			"client/Cache/credentials.txt",
			"client/Cache/uid.dat",
			"client/Cache/ip.txt",
			"client/Cache/port.txt",
			"client/Cache/discord_inuse.txt")));

	private WorldBuilderAdaptiveRuntimePreparer() {
	}

	static SourceRuntime inspect(Path requestedRuntime)
		throws IOException, WorldBuilderContractException {
		Path runtime = realDirectory(requestedRuntime, "application runtime");
		Path server = realDirectory(runtime.resolve("server"), "runtime server");
		Path client = realDirectory(runtime.resolve("Client_Base"), "runtime client");
		requireFile(server.resolve("world-builder.conf"), "server/world-builder.conf");
		final Map<String,Entry> entries = new TreeMap<String,Entry>();
		final Set<String> folded = new HashSet<String>();
		final long[] total = new long[] {0L};
		collect(server, "server", entries, folded, total);
		collect(client, "client", entries, folded, total);
		for (String required : Arrays.asList(
			"server/core.jar", "server/plugins.jar",
			"server/conf/world-builder/adaptive-runtime-capability-v1.json",
			"server/inc/sqlite/world_builder_seed.db",
			"client/Open_RSC_Client.jar")) {
			if (!entries.containsKey(required)) {
				throw problem(WorldBuilderErrorCodes.LOADER_INCOMPATIBLE, required,
					"Application runtime is missing a required adaptive asset.",
					"Restore the exact content-neutral builder-runtime directory.");
			}
		}
		byte[] inventory = inventoryBytes(entries);
		return new SourceRuntime(runtime, entries, inventory,
			WorldBuilderHashes.sha256(inventory));
	}

	static void prepare(Path projectStage, SourceRuntime source,
		Map<String,Object> snapshot, String origin, int port)
		throws IOException, WorldBuilderContractException {
		Path runtime = projectStage.resolve("working/runtime");
		if (Files.exists(runtime, LinkOption.NOFOLLOW_LINKS)) {
			throw problem(WorldBuilderErrorCodes.UNSAFE_PATH, "working/runtime",
				"Project runtime destination already exists.",
				"Discard the unpublished project stage and retry.");
		}
		Files.createDirectories(runtime);
		for (Map.Entry<String,Entry> item : source.entries.entrySet()) {
			Path input = source.sourcePath(item.getKey());
			Path output = runtime.resolve(item.getKey()).normalize();
			copyVerified(input, output, item.getValue());
		}

		Path template = requireFile(
			source.root.resolve("server/world-builder.conf"),
			"server/world-builder.conf");
		Path config = runtime.resolve("server/world-builder.conf");
		Files.createDirectories(config.getParent());
		try {
			WorldBuilderConfigWriter.write(template, config, overrides(port));
		} catch (WorldBuilderDiscoveryException invalid) {
			throw problem(WorldBuilderErrorCodes.LOADER_INCOMPATIBLE,
				"working/runtime/server/world-builder.conf",
				"Adaptive runtime configuration template is incompatible: "
					+ invalid.getMessage(),
				"Restore the exact candidate runtime configuration.");
		}
		Files.write(runtime.resolve("server/connections.conf"),
			"db_type: sqlite\n".getBytes(StandardCharsets.UTF_8));
		copyVerified(
			runtime.resolve("server/inc/sqlite/world_builder_seed.db"),
			runtime.resolve("server/inc/sqlite/world_builder.db"),
			source.entries.get("server/inc/sqlite/world_builder_seed.db"));

		copyDefinitionEvidence(projectStage, snapshot, origin,
			runtime.resolve("server/evidence/adaptive-definitions.json"), true);
		copyDefinitionEvidence(projectStage, snapshot, origin,
			runtime.resolve("client/evidence/adaptive-definitions.json"), false);
		byte[] assets = assetEvidence(snapshot, origin, source.entries);
		Files.createDirectories(runtime.resolve("server/evidence"));
		Files.createDirectories(runtime.resolve("client/evidence"));
		Files.write(runtime.resolve("server/evidence/adaptive-assets.sha256"), assets);
		Files.write(runtime.resolve("client/evidence/adaptive-assets.sha256"), assets);
		Files.write(projectStage.resolve(INVENTORY_FILE), source.inventoryBytes);
		verify(projectStage, source.fingerprintSha256, snapshot, origin, port);
	}

	static RuntimeEvidence verify(Path project, String expectedFingerprint,
		Map<String,Object> snapshot, String origin, int port)
		throws IOException, WorldBuilderContractException {
		Path runtime = realDirectory(project.resolve("working/runtime"),
			"project-local adaptive runtime");
		Path inventoryPath = requireFile(project.resolve(INVENTORY_FILE), INVENTORY_FILE);
		byte[] inventoryBytes = Files.readAllBytes(inventoryPath);
		if (!expectedFingerprint.equals(WorldBuilderHashes.sha256(inventoryBytes))) {
			throw problem(WorldBuilderErrorCodes.SOURCE_CORRUPT, INVENTORY_FILE,
				"Project runtime inventory does not match its bound fingerprint.",
				"Restore the complete project-local runtime from its trusted project backup.");
		}
		Map<String,Entry> entries = parseInventory(inventoryBytes);
		for (Map.Entry<String,Entry> item : entries.entrySet()) {
			Path file = requireFile(runtime.resolve(item.getKey()),
				"working/runtime/" + item.getKey());
			Entry expected = item.getValue();
			if (Files.size(file) != expected.size
				|| !expected.sha256.equals(WorldBuilderHashes.sha256(file))) {
				throw problem(WorldBuilderErrorCodes.SOURCE_CORRUPT,
					"working/runtime/" + item.getKey(),
					"Immutable project runtime asset changed.",
					"Restore the complete project-local runtime before launching.");
			}
		}
		for (String required : Arrays.asList(
			"server/core.jar", "server/plugins.jar",
			"server/conf/world-builder/adaptive-runtime-capability-v1.json",
			"server/inc/sqlite/world_builder_seed.db",
			"client/Open_RSC_Client.jar")) {
			if (!entries.containsKey(required)) {
				throw problem(WorldBuilderErrorCodes.LOADER_INCOMPATIBLE,
					"working/runtime/" + required,
					"Project runtime inventory omits a required adaptive asset.",
					"Restore the complete project-local runtime.");
			}
		}
		validateCapability(runtime.resolve(
			"server/conf/world-builder/adaptive-runtime-capability-v1.json"));
		validateConfig(runtime.resolve("server/world-builder.conf"), port);
		Path connections = requireFile(runtime.resolve("server/connections.conf"),
			"working/runtime/server/connections.conf");
		if (!"db_type: sqlite\n".equals(new String(
			Files.readAllBytes(connections), StandardCharsets.UTF_8))) {
			throw problem(WorldBuilderErrorCodes.LOADER_INCOMPATIBLE,
				"working/runtime/server/connections.conf",
				"Adaptive runtime database selection is not isolated SQLite.",
				"Restore the generated project runtime configuration.");
		}
		requireFile(runtime.resolve("server/inc/sqlite/world_builder.db"),
			"working/runtime/server/inc/sqlite/world_builder.db");

		String definitionPath = definitionSourcePath(snapshot, origin, true);
		String clientDefinitionPath = definitionSourcePath(snapshot, origin, false);
		Path serverDefinition = requireFile(project.resolve(SERVER_DEFINITION_EVIDENCE),
			SERVER_DEFINITION_EVIDENCE);
		Path clientDefinition = requireFile(project.resolve(CLIENT_DEFINITION_EVIDENCE),
			CLIENT_DEFINITION_EVIDENCE);
		String expectedDefinitionHash = recordHash(snapshot, definitionPath);
		if (!expectedDefinitionHash.equals(WorldBuilderHashes.sha256(serverDefinition))
			|| !expectedDefinitionHash.equals(WorldBuilderHashes.sha256(clientDefinition))
			|| !expectedDefinitionHash.equals(recordHash(snapshot, clientDefinitionPath))) {
			throw problem(WorldBuilderErrorCodes.DEFINITION_MISMATCH,
				SERVER_DEFINITION_EVIDENCE,
				"Project-local definition evidence differs from the immutable source binding.",
				"Restore the complete project-local runtime and immutable source snapshot.");
		}
		byte[] expectedAssets = assetEvidence(snapshot, origin, entries);
		Path serverAssets = requireFile(project.resolve(SERVER_ASSET_EVIDENCE),
			SERVER_ASSET_EVIDENCE);
		Path clientAssets = requireFile(project.resolve(CLIENT_ASSET_EVIDENCE),
			CLIENT_ASSET_EVIDENCE);
		byte[] serverAssetBytes = Files.readAllBytes(serverAssets);
		byte[] clientAssetBytes = Files.readAllBytes(clientAssets);
		if (!Arrays.equals(expectedAssets, serverAssetBytes)
			|| !Arrays.equals(expectedAssets, clientAssetBytes)) {
			throw problem(WorldBuilderErrorCodes.CAPABILITY_MISMATCH,
				SERVER_ASSET_EVIDENCE,
				"Project-local asset evidence differs from its immutable source/runtime binding.",
				"Restore the complete project-local runtime.");
		}
		return new RuntimeEvidence(runtime, serverDefinition, clientDefinition,
			serverAssets, clientAssets, expectedDefinitionHash,
			WorldBuilderHashes.sha256(expectedAssets));
	}

	private static LinkedHashMap<String,String> overrides(int port) {
		LinkedHashMap<String,String> values = new LinkedHashMap<String,String>();
		values.put("world_builder_mode", "true");
		values.put("world_builder_adaptive_mode", "true");
		values.put("world_builder_layered_review_mode", "true");
		values.put("server_name", "World Builder 2 Runtime");
		values.put("server_name_welcome", "World Builder 2 Runtime");
		values.put("welcome_text", "Local isolated World Builder");
		values.put("server_bind_address", "127.0.0.1");
		values.put("server_port", Integer.toString(port));
		values.put("ws_server_port", Integer.toString(port == 65534 ? 65533 : port + 1));
		values.put("want_feature_websockets", "false");
		values.put("db_name", "world_builder");
		values.put("db_table_prefix", "");
		values.put("max_players", "1");
		values.put("max_players_per_ip", "1");
		values.put("want_packet_register", "false");
		values.put("allow_in_game_world_editor", "true");
		values.put("is_localhost_restricted", "true");
		values.put("want_pcap_logging", "false");
		values.put("avatar_generator", "false");
		values.put("monitor_online", "false");
		values.put("monitor_automatic_shutdown", "false");
		values.put("want_auto_server_shutdown", "false");
		values.put("want_layered_player_location_authority", "true");
		values.put("want_layered_spatial_runtime_authority", "true");
		values.put("want_layered_protocol_client_authority", "true");
		values.put("want_layered_synthetic_deep_fixture", "false");
		values.put("want_layered_native_terrain_package", "true");
		values.put("want_layered_native_terrain_residency", "true");
		values.put("want_layered_native_terrain_readiness", "true");
		values.put("want_layered_native_terrain_prediction", "true");
		values.put("want_layered_native_terrain_symmetric_residency", "true");
		values.put("want_layered_native_terrain_atomic_activation", "true");
		values.put("layered_native_world_runtime_profile", "adaptive-world-builder");
		return values;
	}

	private static void collect(final Path root, final String prefix,
		final Map<String,Entry> entries, final Set<String> folded, final long[] total)
		throws IOException, WorldBuilderContractException {
		final IOException[] failure = new IOException[] {null};
		Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
			@Override public FileVisitResult preVisitDirectory(Path directory,
				BasicFileAttributes attributes) throws IOException {
				if (!attributes.isDirectory() || Files.isSymbolicLink(directory)) {
					throw new IOException("Runtime contains an unsafe directory");
				}
				return FileVisitResult.CONTINUE;
			}
			@Override public FileVisitResult visitFile(Path file,
				BasicFileAttributes attributes) throws IOException {
				if (!attributes.isRegularFile() || Files.isSymbolicLink(file)) {
					throw new IOException("Runtime contains an unsupported entry: " + file);
				}
				String relative = prefix + "/" + root.relativize(file)
					.toString().replace('\\', '/');
				if (GENERATED_SOURCE_PATHS.contains(relative)) return FileVisitResult.CONTINUE;
				try {
					WorldBuilderPortablePath.require(relative, OPERATION);
				} catch (WorldBuilderContractException invalid) {
					failure[0] = new IOException(invalid.getMessage(), invalid);
					return FileVisitResult.TERMINATE;
				}
				if (!folded.add(relative.toLowerCase(Locale.ROOT))) {
					throw new IOException("Runtime contains a case-colliding path: " + relative);
				}
				if (entries.size() >= WorldBuilderContractLimits.MAX_INVENTORY_ENTRIES) {
					throw new IOException("Runtime asset inventory is too large");
				}
				long size = attributes.size();
				if (size < 1L || size > WorldBuilderContractLimits.MAX_INVENTORY_FILE_BYTES) {
					throw new IOException("Runtime asset has an unsupported size: " + relative);
				}
				total[0] = Math.addExact(total[0], size);
				if (total[0] > WorldBuilderContractLimits.MAX_INVENTORY_TOTAL_BYTES) {
					throw new IOException("Runtime asset inventory exceeds its byte limit");
				}
				entries.put(relative, new Entry(size, WorldBuilderHashes.sha256(file)));
				return FileVisitResult.CONTINUE;
			}
		});
		if (failure[0] != null) throw failure[0];
	}

	private static byte[] inventoryBytes(Map<String,Entry> entries) {
		StringBuilder value = new StringBuilder(INVENTORY_HEADER).append('\n');
		for (Map.Entry<String,Entry> item : new TreeMap<String,Entry>(entries).entrySet()) {
			value.append(item.getValue().sha256).append('\t')
				.append(item.getValue().size).append('\t')
				.append(item.getKey()).append('\n');
		}
		return value.toString().getBytes(StandardCharsets.UTF_8);
	}

	private static Map<String,Entry> parseInventory(byte[] bytes)
		throws WorldBuilderContractException {
		String value = new String(bytes, StandardCharsets.UTF_8);
		if (value.indexOf('\r') >= 0 || !value.endsWith("\n")) {
			throw malformedInventory();
		}
		String[] lines = value.split("\n", -1);
		if (lines.length < 3 || !INVENTORY_HEADER.equals(lines[0])) {
			throw malformedInventory();
		}
		Map<String,Entry> entries = new TreeMap<String,Entry>();
		String previous = "";
		for (int index = 1; index < lines.length - 1; index++) {
			String[] columns = lines[index].split("\\t", -1);
			if (columns.length != 3 || !columns[0].matches("[0-9a-f]{64}")) {
				throw malformedInventory();
			}
			long size;
			try {
				size = Long.parseLong(columns[1]);
			} catch (NumberFormatException invalid) {
				throw malformedInventory();
			}
			if (size < 1L || size > WorldBuilderContractLimits.MAX_INVENTORY_FILE_BYTES
				|| previous.compareTo(columns[2]) >= 0
				|| entries.size() >= WorldBuilderContractLimits.MAX_INVENTORY_ENTRIES) {
				throw malformedInventory();
			}
			try {
				WorldBuilderPortablePath.require(columns[2], OPERATION);
			} catch (WorldBuilderContractException invalid) {
				throw malformedInventory();
			}
			if (!(columns[2].startsWith("server/") || columns[2].startsWith("client/"))
				|| GENERATED_SOURCE_PATHS.contains(columns[2])) {
				throw malformedInventory();
			}
			entries.put(columns[2], new Entry(size, columns[0]));
			previous = columns[2];
		}
		if (!Arrays.equals(bytes, inventoryBytes(entries))) throw malformedInventory();
		return entries;
	}

	private static WorldBuilderContractException malformedInventory() {
		return problem(WorldBuilderErrorCodes.SOURCE_CORRUPT, INVENTORY_FILE,
			"Project runtime inventory is malformed or noncanonical.",
			"Restore the exact project-local runtime inventory.");
	}

	private static void copyDefinitionEvidence(Path project,
		Map<String,Object> snapshot, String origin, Path destination, boolean server)
		throws IOException, WorldBuilderContractException {
		String sourceRelative = definitionSourcePath(snapshot, origin, server);
		Path source = requireFile(project.resolve(sourceRelative), sourceRelative);
		Files.createDirectories(destination.getParent());
		Files.copy(source, destination, StandardCopyOption.COPY_ATTRIBUTES);
		if (!recordHash(snapshot, sourceRelative).equals(
			WorldBuilderHashes.sha256(destination))) {
			Files.deleteIfExists(destination);
			throw problem(WorldBuilderErrorCodes.DEFINITION_MISMATCH, sourceRelative,
				"Definition evidence changed while preparing the project runtime.",
				"Discard the unpublished project stage and retry from stable evidence.");
		}
	}

	private static String definitionSourcePath(Map<String,Object> snapshot,
		String origin, boolean server) throws WorldBuilderContractException {
		if ("standalone-empty".equals(origin)) {
			return recordPath(snapshot, "default-definition-catalog");
		}
		return recordPath(snapshot,
			server ? "server-definition-catalog" : "client-definition-catalog");
	}

	private static byte[] assetEvidence(Map<String,Object> snapshot, String origin,
		Map<String,Entry> runtimeEntries) throws WorldBuilderContractException {
		StringBuilder value = new StringBuilder(ASSET_HEADER).append('\n');
		if ("standalone-empty".equals(origin)) {
			for (Map.Entry<String,Entry> item : runtimeEntries.entrySet()) {
				if (!item.getKey().startsWith("client/")) continue;
				value.append(item.getKey().substring("client/".length()))
					.append('\t').append(item.getValue().size).append('\t')
					.append(item.getValue().sha256).append('\n');
			}
		} else {
			Map<String,Record> server = new TreeMap<String,Record>();
			Map<String,Record> client = new TreeMap<String,Record>();
			for (Record record : records(snapshot, "definitionRuntimeFiles")) {
				if (record.role.startsWith("server-asset.")) {
					server.put(record.role.substring("server-asset.".length()), record);
				} else if (record.role.startsWith("client-asset.")) {
					client.put(record.role.substring("client-asset.".length()), record);
				}
			}
			if (server.isEmpty() || !server.keySet().equals(client.keySet())) {
				throw problem(WorldBuilderErrorCodes.CAPABILITY_MISMATCH,
					WorldBuilderAdaptiveProjectLifecycle.SNAPSHOT_FILE,
					"Project source has no complete server/client asset evidence binding.",
					"Restore the exact compatible project source snapshot.");
			}
			for (Map.Entry<String,Record> item : server.entrySet()) {
				Record other = client.get(item.getKey());
				if (item.getValue().size != other.size
					|| !item.getValue().sha256.equals(other.sha256)) {
					throw problem(WorldBuilderErrorCodes.CAPABILITY_MISMATCH,
						item.getValue().relativePath,
						"Server/client project asset evidence no longer agrees.",
						"Restore the exact immutable source snapshot.");
				}
				value.append(item.getKey()).append('\t')
					.append(item.getValue().size).append('\t')
					.append(item.getValue().sha256).append('\n');
			}
		}
		if (value.toString().equals(ASSET_HEADER + "\n")) {
			throw problem(WorldBuilderErrorCodes.CAPABILITY_MISMATCH,
				WorldBuilderAdaptiveProjectLifecycle.SNAPSHOT_FILE,
				"Adaptive project has no bounded asset evidence.",
				"Restore a complete content-neutral runtime or compatible target evidence.");
		}
		return value.toString().getBytes(StandardCharsets.UTF_8);
	}

	private static List<Record> records(Map<String,Object> snapshot, String key)
		throws WorldBuilderContractException {
		Object raw = snapshot.get(key);
		if (!(raw instanceof List)) throw malformedSnapshot(key);
		List<Record> result = new ArrayList<Record>();
		for (Object item : (List<?>)raw) {
			if (!(item instanceof Map)) throw malformedSnapshot(key);
			@SuppressWarnings("unchecked") Map<String,Object> value = (Map<String,Object>)item;
			Object role = value.get("role");
			Object path = value.get("relativePath");
			Object present = value.get("present");
			Object size = value.get("size");
			Object hash = value.get("sha256");
			if (!(role instanceof String) || !(path instanceof String)
				|| !(present instanceof Boolean) || !(size instanceof Long)
				|| !(hash instanceof String) || !((Boolean)present).booleanValue()) {
				continue;
			}
			result.add(new Record((String)role, (String)path,
				((Long)size).longValue(), (String)hash));
		}
		return result;
	}

	private static String recordPath(Map<String,Object> snapshot, String wantedRole)
		throws WorldBuilderContractException {
		for (Record record : records(snapshot, "definitionRuntimeFiles")) {
			if (wantedRole.equals(record.role)) return record.relativePath;
		}
		throw malformedSnapshot(wantedRole);
	}

	private static String recordHash(Map<String,Object> snapshot, String path)
		throws WorldBuilderContractException {
		for (Record record : records(snapshot, "definitionRuntimeFiles")) {
			if (path.equals(record.relativePath)) return record.sha256;
		}
		throw malformedSnapshot(path);
	}

	private static WorldBuilderContractException malformedSnapshot(String field) {
		return problem(WorldBuilderErrorCodes.SOURCE_CORRUPT,
			WorldBuilderAdaptiveProjectLifecycle.SNAPSHOT_FILE,
			"Project source snapshot is missing runtime evidence: " + field + ".",
			"Restore the exact immutable project source snapshot.");
	}

	private static void validateCapability(Path path)
		throws IOException, WorldBuilderContractException {
		Map<String,Object> value;
		try {
			value = WorldBuilderJsonDocuments.readObject(requireFile(path,
				"working/runtime/server/conf/world-builder/adaptive-runtime-capability-v1.json"));
		} catch (WorldBuilderDiscoveryException malformed) {
			throw problem(WorldBuilderErrorCodes.LOADER_INCOMPATIBLE,
				"working/runtime/server/conf/world-builder/adaptive-runtime-capability-v1.json",
				"Adaptive runtime capability evidence is malformed.",
				"Restore the exact project-local runtime.");
		}
		Map<String,String> expected = new LinkedHashMap<String,String>();
		expected.put("manifestType", "adaptive-world-builder-runtime-capability");
		expected.put("capabilityId", "adaptive-world-builder-runtime-capability-v1");
		expected.put("profileId", "adaptive-world-builder");
		expected.put("serverBuildId", "core-framework-adaptive-builder-server-v1");
		expected.put("clientBuildId", "core-framework-adaptive-builder-client-v1");
		expected.put("loaderId", "generic-signed-layered-loader-v1");
		expected.put("authoringId", "generic-signed-layered-authoring-v1");
		expected.put("protocolId", "world-builder-native-layered-protocol-v1");
		expected.put("packageSchemaId", "layered-world-package-v1");
		expected.put("coordinateModel", "signed-layered-v1");
		for (Map.Entry<String,String> item : expected.entrySet()) {
			if (!item.getValue().equals(value.get(item.getKey()))) {
				throw problem(WorldBuilderErrorCodes.LOADER_INCOMPATIBLE,
					"working/runtime/server/conf/world-builder/adaptive-runtime-capability-v1.json",
					"Project runtime capability identity is incompatible: " + item.getKey() + ".",
					"Restore the exact pinned adaptive project runtime.");
			}
		}
	}

	private static void validateConfig(Path path, int port)
		throws IOException, WorldBuilderContractException {
		Path config = requireFile(path, "working/runtime/server/world-builder.conf");
		Map<String,String> values = new HashMap<String,String>();
		Set<String> duplicated = new HashSet<String>();
		for (String raw : Files.readAllLines(config, StandardCharsets.UTF_8)) {
			String line = raw.trim();
			if (line.isEmpty() || line.startsWith("#") || line.indexOf(':') < 0) continue;
			int separator = line.indexOf(':');
			String key = line.substring(0, separator).trim();
			String value = line.substring(separator + 1).trim();
			int comment = value.indexOf('#');
			if (comment >= 0) value = value.substring(0, comment).trim();
			if (values.put(key, value) != null) duplicated.add(key);
		}
		Map<String,String> required = overrides(port);
		for (Map.Entry<String,String> item : required.entrySet()) {
			if (duplicated.contains(item.getKey())
				|| !item.getValue().equals(values.get(item.getKey()))) {
				throw problem(WorldBuilderErrorCodes.LOADER_INCOMPATIBLE,
					"working/runtime/server/world-builder.conf",
					"Project runtime isolation setting is missing or changed: "
						+ item.getKey() + ".",
					"Restore the generated project-local adaptive configuration.");
			}
		}
	}

	private static Path realDirectory(Path requested, String label)
		throws IOException, WorldBuilderContractException {
		Path path = requested.toAbsolutePath().normalize();
		if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
			|| Files.isSymbolicLink(path)) {
			throw problem(WorldBuilderErrorCodes.UNSAFE_PATH, path.toString(),
				label + " is missing, linked, or not a directory.",
				"Restore one complete contained runtime directory.");
		}
		return path.toRealPath();
	}

	private static Path requireFile(Path requested, String label)
		throws IOException, WorldBuilderContractException {
		Path path = requested.toAbsolutePath().normalize();
		if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
			|| Files.isSymbolicLink(path)) {
			throw problem(WorldBuilderErrorCodes.UNSAFE_PATH, label,
				"Required adaptive runtime file is missing or unsafe.",
				"Restore the complete project-local adaptive runtime.");
		}
		try {
			Object links = Files.getAttribute(path, "unix:nlink", LinkOption.NOFOLLOW_LINKS);
			if (links instanceof Number && ((Number)links).longValue() != 1L) {
				throw problem(WorldBuilderErrorCodes.UNSAFE_PATH, label,
					"Adaptive runtime file is hard linked.",
					"Restore an independent project-local runtime copy.");
			}
		} catch (UnsupportedOperationException ignored) {
			path.toRealPath();
		} catch (IllegalArgumentException ignored) {
			path.toRealPath();
		}
		return path;
	}

	private static void copyVerified(Path source, Path destination, Entry expected)
		throws IOException, WorldBuilderContractException {
		Path input = requireFile(source, source.toString());
		if (expected == null || Files.size(input) != expected.size
			|| !expected.sha256.equals(WorldBuilderHashes.sha256(input))) {
			throw problem(WorldBuilderErrorCodes.SOURCE_CORRUPT, source.toString(),
				"Application runtime asset changed during project preparation.",
				"Retry from one stable exact candidate runtime.");
		}
		Files.createDirectories(destination.getParent());
		Files.copy(input, destination, StandardCopyOption.COPY_ATTRIBUTES);
		if (Files.size(destination) != expected.size
			|| !expected.sha256.equals(WorldBuilderHashes.sha256(destination))) {
			Files.deleteIfExists(destination);
			throw new IOException("Project runtime copy did not verify: " + destination);
		}
	}

	private static WorldBuilderContractException problem(
		String code, String path, String message, String nextStep) {
		return new WorldBuilderContractException(code, OPERATION, path, false,
			message, nextStep);
	}

	static final class SourceRuntime {
		final Path root;
		final Map<String,Entry> entries;
		final byte[] inventoryBytes;
		final String fingerprintSha256;

		SourceRuntime(Path root, Map<String,Entry> entries, byte[] inventoryBytes,
			String fingerprintSha256) {
			this.root = root;
			this.entries = Collections.unmodifiableMap(
				new TreeMap<String,Entry>(entries));
			this.inventoryBytes = inventoryBytes.clone();
			this.fingerprintSha256 = fingerprintSha256;
		}

		Path sourcePath(String relative) {
			if (relative.startsWith("server/")) return root.resolve(relative);
			return root.resolve("Client_Base")
				.resolve(relative.substring("client/".length()));
		}
	}

	static final class RuntimeEvidence {
		final Path runtimeRoot;
		final Path serverDefinitionEvidence;
		final Path clientDefinitionEvidence;
		final Path serverAssetEvidence;
		final Path clientAssetEvidence;
		final String definitionSha256;
		final String assetSha256;

		RuntimeEvidence(Path runtimeRoot, Path serverDefinitionEvidence,
			Path clientDefinitionEvidence, Path serverAssetEvidence,
			Path clientAssetEvidence, String definitionSha256, String assetSha256) {
			this.runtimeRoot = runtimeRoot;
			this.serverDefinitionEvidence = serverDefinitionEvidence;
			this.clientDefinitionEvidence = clientDefinitionEvidence;
			this.serverAssetEvidence = serverAssetEvidence;
			this.clientAssetEvidence = clientAssetEvidence;
			this.definitionSha256 = definitionSha256;
			this.assetSha256 = assetSha256;
		}
	}

	private static final class Entry {
		final long size;
		final String sha256;
		Entry(long size, String sha256) {
			this.size = size;
			this.sha256 = sha256;
		}
	}

	private static final class Record {
		final String role;
		final String relativePath;
		final long size;
		final String sha256;
		Record(String role, String relativePath, long size, String sha256) {
			this.role = role;
			this.relativePath = relativePath;
			this.size = size;
			this.sha256 = sha256;
		}
	}
}
