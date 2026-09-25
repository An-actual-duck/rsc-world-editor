package com.openrsc.worldbuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarFile;

/** Exact derived floor content shared by the installed server and normal player client. */
final class WorldBuilderInstalledFloorContent {
	static final String SOURCE = WorldBuilderProjectContentBundle.SOURCE_DIRECTORY
		+ "/files/server/conf/server/defs/TileDef.xml";
	static final String CLIENT_TILES = "world-builder-configs/TileDef.xml";
	static final String CLIENT_DESCRIPTOR = "world-builder-configs/installed-floors.json";
	static final String SERVER_ROLE = "runtime-compatibility-floor-server";
	static final String CLIENT_ROLE = "runtime-compatibility-floor-client";
	static final String DESCRIPTOR_ROLE = "runtime-compatibility-floor-descriptor";
	private WorldBuilderInstalledFloorContent() { }

	static boolean required(Path project) throws IOException, WorldBuilderContractException {
		Path path = project.resolve(SOURCE);
		if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return false;
		return WorldBuilderStandardFloorRuntime.required(
			WorldBuilderReadOnlyTarget.open(project).requiredFile(SOURCE));
	}

	static byte[] descriptor(byte[] tiles) {
		Map<String,Object> value = new LinkedHashMap<String,Object>();
		value.put("schemaVersion", Long.valueOf(1));
		value.put("manifestType", "world-builder-installed-floor-definitions");
		value.put("tileDefinitionsRelativePath", CLIENT_TILES);
		value.put("tileDefinitionsSha256", WorldBuilderHashes.sha256(tiles));
		return WorldBuilderJsonDocuments.pretty(value).getBytes(StandardCharsets.UTF_8);
	}

	static void requireClient(Path client) throws IOException, WorldBuilderContractException {
		try (JarFile archive = new JarFile(client.toFile())) {
			java.util.jar.JarEntry entry = archive.getJarEntry("META-INF/MANIFEST.MF");
			if (entry == null || entry.isDirectory() || entry.getSize() > 65536
				|| archive.getJarEntry("orsc/WorldBuilderInstalledFloorDefinitions.class") == null)
				throw refusal("Player client cannot load the selected standard floor definitions.");
			java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
			try (java.io.InputStream input = archive.getInputStream(entry)) {
				byte[] buffer = new byte[4096];
				for (int count; (count = input.read(buffer)) != -1;) {
					if (bytes.size() + count > 65536) throw refusal("Player client capability manifest is unbounded.");
					bytes.write(buffer, 0, count);
				}
			}
			String attribute = "World-Builder-Installed-Floors";
			int declarations = 0;
			for (String line : new String(bytes.toByteArray(), StandardCharsets.UTF_8).split("\\r?\\n")) {
				if (line.isEmpty()) break;
				if (line.regionMatches(true, 0, attribute + ":", 0, attribute.length() + 1)) declarations++;
			}
			java.util.jar.Manifest manifest = new java.util.jar.Manifest(new java.io.ByteArrayInputStream(bytes.toByteArray()));
			if (declarations != 1 || !"installed-floors-v1".equals(manifest.getMainAttributes().getValue(attribute)))
				throw refusal("Player client cannot load the selected standard floor definitions.");
		}
	}

	static String serverDestination(WorldBuilderAdaptiveProjectLifecycle.VerifiedProject project)
		throws WorldBuilderContractException {
		String result = null;
		for (String group : Arrays.asList("originalFiles", "definitionRuntimeFiles")) {
			for (Object raw : WorldBuilderAdaptiveExporter.array(project.snapshot.get(group), group)) {
				Map<String,Object> record = WorldBuilderAdaptiveExporter.object(raw, "source floor evidence");
				if (!"server-definition.tile".equals(record.get("role"))) continue;
				String source = WorldBuilderAdaptiveExporter.string(record, "relativePath");
				if (!source.startsWith("source/original/")) throw refusal("Source floor definition path is not target evidence.");
				String path = source.substring("source/original/".length());
				boolean allowed = false;
				for (String root : WorldBuilderPackedSourceLayout.DEFINITION_ROOTS)
					allowed |= (root + "/TileDef.xml").equals(path);
				if (!allowed || result != null) throw refusal("Source floor definition path is ambiguous or unsupported.");
				result = path;
			}
		}
		if (result == null) throw refusal("Project has no exact target floor definition evidence.");
		return result;
	}

	static String clientRoot(WorldBuilderAdaptiveConfiguration configuration)
		throws WorldBuilderContractException {
		String path = configuration.clientRuntimeRelativePath;
		if (path.startsWith("Client_Base/")) return "Client_Base";
		if (path.startsWith("client/")) return "client";
		throw refusal("Selected player client root is unsupported.");
	}

	static boolean isRole(String role) {
		return SERVER_ROLE.equals(role) || CLIENT_ROLE.equals(role) || DESCRIPTOR_ROLE.equals(role);
	}

	static String contentPath(String role) {
		return "package/activation/" + role + (DESCRIPTOR_ROLE.equals(role) ? ".json" : ".xml");
	}

	static String destination(WorldBuilderAdaptiveProjectLifecycle.VerifiedProject project,
		WorldBuilderAdaptiveConfiguration configuration, String role) throws WorldBuilderContractException {
		if (SERVER_ROLE.equals(role)) return serverDestination(project);
		if (CLIENT_ROLE.equals(role)) return clientRoot(configuration) + "/" + CLIENT_TILES;
		if (DESCRIPTOR_ROLE.equals(role)) return clientRoot(configuration) + "/" + CLIENT_DESCRIPTOR;
		throw refusal("Unknown installed floor transaction role.");
	}

	static byte[] content(Path project, String role) throws IOException, WorldBuilderContractException {
		if (!isRole(role) || !required(project)) throw refusal("Project does not bind installed standard floor content.");
		byte[] tiles = Files.readAllBytes(WorldBuilderReadOnlyTarget.open(project).requiredFile(SOURCE));
		return DESCRIPTOR_ROLE.equals(role) ? descriptor(tiles) : tiles;
	}

	static void appendUpgrade(WorldBuilderAdaptiveProjectLifecycle.VerifiedProject project, Path target,
		WorldBuilderAdaptiveConfiguration configuration, List<WorldBuilderAdaptiveMutationProfile.Action> actions)
		throws IOException, WorldBuilderContractException {
		if (!required(project.projectRoot)) return;
		WorldBuilderStandardFloorRuntime.require(project.projectRoot.resolve("working/runtime/server/core.jar"),
			project.projectRoot.resolve("working/runtime/client/Open_RSC_Client.jar"));
		requireClient(project.projectRoot.resolve("working/runtime/client/Open_RSC_Client.jar"));
		for (String role : Arrays.asList(SERVER_ROLE, CLIENT_ROLE, DESCRIPTOR_ROLE)) {
			String destination = destination(project, configuration, role);
			Path path = WorldBuilderAdaptiveMutationProfile.safeDestination(target, destination);
			WorldBuilderAdaptiveMutationProfile.FileState before = WorldBuilderAdaptiveMutationProfile.FileState.absent();
			if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
				WorldBuilderReadOnlyTarget.open(target).requiredFile(destination);
				before = WorldBuilderAdaptiveMutationProfile.FileState.present(Files.size(path), WorldBuilderHashes.sha256(path));
			}
			byte[] bytes = content(project.projectRoot, role);
			WorldBuilderAdaptiveMutationProfile.FileState after = WorldBuilderAdaptiveMutationProfile.FileState.present(bytes.length, WorldBuilderHashes.sha256(bytes));
			if (before.present && before.size == after.size && before.sha256.equals(after.sha256)) continue;
			actions.add(new WorldBuilderAdaptiveMutationProfile.Action(role, destination, before, after,
				contentPath(role), before.present ? "backups/{transaction}/before/" + destination : "", true, bytes));
		}
	}

	static void verifyInstalled(WorldBuilderAdaptiveProjectLifecycle.VerifiedProject project, Path target,
		WorldBuilderAdaptiveConfiguration configuration) throws IOException, WorldBuilderContractException {
		if (!required(project.projectRoot)) return;
		WorldBuilderStandardFloorRuntime.require(target.resolve("server/core.jar"),
			target.resolve(clientRoot(configuration) + "/Open_RSC_Client.jar"));
		requireClient(target.resolve(clientRoot(configuration) + "/Open_RSC_Client.jar"));
		for (String role : Arrays.asList(SERVER_ROLE, CLIENT_ROLE, DESCRIPTOR_ROLE)) {
			String destination = destination(project, configuration, role);
			Path path = WorldBuilderAdaptiveMutationProfile.safeDestination(target, destination);
			if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) throw refusal("Installed floor content is missing at " + destination + ".");
			path = WorldBuilderReadOnlyTarget.open(target).requiredFile(destination);
			byte[] expected = content(project.projectRoot, role);
			if (Files.size(path) != expected.length || !WorldBuilderHashes.sha256(expected).equals(WorldBuilderHashes.sha256(path)))
				throw refusal("Installed floor definitions differ from this project at " + destination + ".");
		}
	}

	/** A later map receipt retains the installed floor generation without rewriting it. */
	static boolean verifyRetainedPath(WorldBuilderAdaptiveProjectLifecycle.VerifiedProject project,
		Path target, String relative) throws IOException, WorldBuilderContractException {
		if (!relative.endsWith("/TileDef.xml") && !relative.endsWith("/installed-floors.json")) return false;
		if (!required(project.projectRoot)) return false;
		Map<String,Object> selected = WorldBuilderAdaptiveExporter.object(project.snapshot.get("selectedConfiguration"), "selectedConfiguration");
		String source = WorldBuilderAdaptiveExporter.string(selected, "relativePath");
		byte[] bytes = Files.readAllBytes(WorldBuilderReadOnlyTarget.open(project.projectRoot).requiredFile(source));
		WorldBuilderAdaptiveConfiguration configuration = WorldBuilderAdaptiveConfiguration.readBytes(bytes,
			source.substring("source/original/".length()), WorldBuilderHashes.sha256(bytes));
		for (String role : Arrays.asList(SERVER_ROLE, CLIENT_ROLE, DESCRIPTOR_ROLE)) {
			if (!relative.equals(destination(project, configuration, role))) continue;
			verifyInstalled(project, target, configuration);
			return true;
		}
		return false;
	}

	/** Read-only discovery of the concrete installed override; never grants mutation authority. */
	static WorldBuilderCompatibilityEvidence.DefinitionCatalog inspectTarget(WorldBuilderReadOnlyTarget target,
		WorldBuilderAdaptiveConfiguration configuration, WorldBuilderCompatibilityEvidence.DefinitionCatalog original,
		List<WorldBuilderReadOnlyTarget.FileState> evidence) throws WorldBuilderContractException {
		if (!configuration.clientRuntimeRelativePath.startsWith("client/")
			&& !configuration.clientRuntimeRelativePath.startsWith("Client_Base/")) return original;
		String client = clientRoot(configuration);
		String descriptor = client + "/" + CLIENT_DESCRIPTOR;
		if (!target.exists(descriptor)) return original;
		try {
			Path tiles = target.requiredFile(client + "/" + CLIENT_TILES);
			int count = WorldBuilderTerrainDefinitionCatalog.readTiles(tiles).tiles.size();
			byte[] bytes = Files.readAllBytes(tiles);
			Path descriptorPath = target.requiredFile(descriptor);
			byte[] expectedDescriptor = descriptor(bytes);
			if (Files.size(descriptorPath) != expectedDescriptor.length
				|| !WorldBuilderHashes.sha256(expectedDescriptor).equals(WorldBuilderHashes.sha256(descriptorPath)))
				throw refusal("Installed player floor descriptor does not bind its exact definitions.");
			String server = null;
			for (String root : WorldBuilderPackedSourceLayout.DEFINITION_ROOTS) {
				String candidate = root + "/TileDef.xml";
				if (!target.exists(candidate)) continue;
				if (server != null) throw refusal("Installed server floor definition root is ambiguous.");
				server = candidate;
			}
			if (server == null || Files.size(target.requiredFile(server)) != bytes.length
				|| !WorldBuilderHashes.sha256(bytes).equals(WorldBuilderHashes.sha256(target.requiredFile(server))))
				throw refusal("Installed server and player floor definitions differ.");
			WorldBuilderStandardFloorRuntime.require(target.requiredFile("server/core.jar"), target.requiredFile(client + "/Open_RSC_Client.jar"));
			requireClient(target.requiredFile(client + "/Open_RSC_Client.jar"));
			if (count < 1 || count >= 250) throw refusal("Installed floor definitions exceed the supported slot capacity.");
			java.util.Set<Integer> ids = new java.util.TreeSet<Integer>();
			for (int id = 0; id < count; id++) ids.add(Integer.valueOf(id));
			if (!ids.containsAll(original.tiles)) throw refusal("Installed floors omit IDs required by the target catalog.");
			String[] paths = {server, client + "/" + CLIENT_TILES, descriptor,
				"server/core.jar", client + "/Open_RSC_Client.jar"};
			String[] roles = {"server-definition.tile", "installed-floor-client", "installed-floor-descriptor",
				"installed-floor-server-runtime", "installed-floor-client-runtime"};
			for (int index = 0; index < paths.length; index++)
				evidence.add(target.requiredState(roles[index], paths[index]));
			return original.withTiles(java.util.Collections.unmodifiableSet(ids));
		} catch (IOException invalid) {
			throw refusal("Installed floor content cannot be verified: " + invalid.getMessage());
		}
	}

	static boolean evidenceRole(String role) {
		return Arrays.asList("server-definition.tile", "installed-floor-client", "installed-floor-descriptor",
			"installed-floor-server-runtime", "installed-floor-client-runtime").contains(role);
	}

	private static WorldBuilderContractException refusal(String message) {
		return new WorldBuilderContractException(WorldBuilderErrorCodes.RUNTIME_UPGRADE_REQUIRED,
			"installed-floor-content", SOURCE, false, message,
			"Run Upgrade Target Runtime to install the matching server and player-client floor definitions before Import Map Changes.");
	}
}
