package com.openrsc.worldbuilder;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Read-only discovery for the first supported Spoiled Milk repository layout. */
public final class WorldBuilderDiscovery {
	public static final String LAYOUT_ADAPTER = "spoiled-milk-repository-v1";
	public static final String DEFAULT_CONFIG = WorldBuilderPackedSourceLayout.CANONICAL_CONFIGURATION;

	private static final int TERRAIN_ENTRY_BYTES = 48 * 48 * 10;
	private static final Pattern TERRAIN_ENTRY = Pattern.compile("h[0-3]x[0-9]+y[0-9]+");
	private static final Pattern CONFIG_VALUE = Pattern.compile(
		"^\\s*([A-Za-z0-9_]+)\\s*:\\s*([^#]*?)\\s*(?:#.*)?$");
	private static final Pattern SHA256 = Pattern.compile("[0-9a-fA-F]{64}");

	private static final String TERRAIN_FILE = "Custom_Landscape.orsc";
	private static final String[] SERVER_CONTENT_FILES = {
		"TileDef.xml", "GameObjectDef.xml", "NpcDefs.json",
		"NpcDefsCustom.json", "NpcDefsMyWorld.json", "NpcDefsPatch18.json"
	};

	public WorldBuilderDiscoveryResult discover(Path requestedRoot)
		throws WorldBuilderDiscoveryException {
		try {
			WorldBuilderReadOnlyTarget target = WorldBuilderReadOnlyTarget.open(requestedRoot);
			WorldBuilderPackedSourceLayout layout = WorldBuilderPackedSourceLayout.select(target);
			return discover(requestedRoot, layout.configurationPath, null, layout);
		} catch (WorldBuilderContractException invalid) {
			throw new WorldBuilderDiscoveryException(invalid.getMessage(), invalid);
		}
	}

	public WorldBuilderDiscoveryResult discover(Path requestedRoot, String requestedConfig,
		String expectedContentFingerprint) throws WorldBuilderDiscoveryException {
		try {
			WorldBuilderReadOnlyTarget target = WorldBuilderReadOnlyTarget.open(requestedRoot);
			WorldBuilderPackedSourceLayout layout = WorldBuilderPackedSourceLayout.select(
				target, requestedConfig);
			return discover(requestedRoot, requestedConfig == null
				? layout.configurationPath : requestedConfig,
				expectedContentFingerprint, layout);
		} catch (WorldBuilderContractException invalid) {
			throw new WorldBuilderDiscoveryException(invalid.getMessage(), invalid);
		}
	}

	WorldBuilderDiscoveryResult discover(Path requestedRoot, String requestedConfig,
		String expectedContentFingerprint, WorldBuilderPackedSourceLayout layout)
		throws WorldBuilderDiscoveryException {
		try {
			Path root = canonicalRoot(requestedRoot);
			if (layout == null) throw new WorldBuilderDiscoveryException(
				"A packed source-layout profile is required.");
			String configRelative = normalizeRelative(requestedConfig == null ? DEFAULT_CONFIG : requestedConfig);
			if (!isSupportedConfigurationPath(configRelative)) {
				throw new WorldBuilderDiscoveryException(
					"Selected configuration is not a supported packed-layout path: "
						+ configRelative);
			}

			Path configPath = requiredFile(root, configRelative);
			String configSha256 = WorldBuilderHashes.sha256(configPath);
			Config config = Config.read(configPath);
			int clientVersion = config.requiredInt("client_version");
			int basedMapData = config.requiredInt("based_map_data");
			boolean memberWorld = config.requiredBoolean("member_world");
			boolean customLandscape = config.requiredBoolean("custom_landscape");
			boolean wantMyWorld = config.requiredBoolean("want_myworld");

			if (!memberWorld) {
				throw new WorldBuilderDiscoveryException(
					"The first World Builder layout adapter requires member_world: true.");
			}
			if (!customLandscape) {
				throw new WorldBuilderDiscoveryException(
					"The first World Builder layout adapter requires custom_landscape: true.");
			}
			if (!wantMyWorld) {
				throw new WorldBuilderDiscoveryException(
					"The first World Builder layout adapter requires want_myworld: true.");
			}

			List<WorldBuilderDiscoveryResult.SourceFile> firstFiles =
				inventoryAuthoredFiles(root, layout, config);
			WorldBuilderDiscoveryResult.SourceFile serverTerrain = firstFiles.get(0);
			WorldBuilderDiscoveryResult.SourceFile clientTerrain = firstFiles.get(1);
			if (!serverTerrain.sha256.equals(clientTerrain.sha256)
				|| serverTerrain.size != clientTerrain.size) {
				throw new WorldBuilderDiscoveryException(
					"Server and client Custom_Landscape.orsc files are not byte-identical.");
			}

			int serverSectors = validateTerrainArchive(requiredFile(root,
				layout.serverDataPath(TERRAIN_FILE)));
			int clientSectors = validateTerrainArchive(requiredFile(root,
				layout.path("Custom_Landscape.orsc")));
			if (serverSectors != clientSectors) {
				throw new WorldBuilderDiscoveryException(
					"Server and client terrain archives contain different sector counts.");
			}

			String contentFingerprint = contentFingerprint(root, layout);
			if (expectedContentFingerprint != null) {
				if (!SHA256.matcher(expectedContentFingerprint).matches()) {
					throw new WorldBuilderDiscoveryException(
						"Expected content fingerprint must be exactly 64 hexadecimal characters.");
				}
				if (!contentFingerprint.equals(expectedContentFingerprint.toLowerCase(Locale.ROOT))) {
					throw new WorldBuilderDiscoveryException(
						"Target definitions do not match this World Builder release.");
				}
			}

			List<WorldBuilderDiscoveryResult.SourceFile> secondFiles =
				inventoryAuthoredFiles(root, layout, config);
			if (!sameInventory(firstFiles, secondFiles)) {
				throw new WorldBuilderDiscoveryException(
					"World files changed while they were being inspected; try discovery again.");
			}
			if (!configSha256.equals(WorldBuilderHashes.sha256(configPath))) {
				throw new WorldBuilderDiscoveryException(
					"Selected configuration changed while it was being inspected; try discovery again.");
			}
			if (!contentFingerprint.equals(contentFingerprint(root, layout))) {
				throw new WorldBuilderDiscoveryException(
					"Target definitions changed while they were being inspected; try discovery again.");
			}

			String sourceFingerprint = sourceFingerprint(layout.profileId,
				configRelative, configSha256, clientVersion,
				basedMapData, memberWorld, customLandscape, wantMyWorld,
				contentFingerprint, secondFiles);

			return new WorldBuilderDiscoveryResult(LAYOUT_ADAPTER, configRelative, configSha256,
				clientVersion, basedMapData, memberWorld, customLandscape, wantMyWorld,
				serverSectors, contentFingerprint, sourceFingerprint, secondFiles);
		} catch (WorldBuilderDiscoveryException failure) {
			throw failure;
		} catch (IOException failure) {
			throw new WorldBuilderDiscoveryException(
				"Could not inspect the private-server layout: " + failure.getMessage(), failure);
		}
	}

	static boolean isSupportedConfigurationPath(String relative) {
		return WorldBuilderPackedSourceLayout.CONFIGURATION_PATHS.contains(relative)
			|| relative != null
				&& relative.matches("server/[A-Za-z0-9._/-]+\\.conf")
				&& !relative.contains("..");
	}

	static boolean looksLikePackedMapConfiguration(Path path) throws IOException {
		if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
			|| Files.isSymbolicLink(path)
			|| Files.size(path) > WorldBuilderContractLimits.MAX_JSON_BYTES) {
			return false;
		}
		Config config = Config.read(path);
		return config.contains("client_version")
			&& config.contains("based_map_data")
			&& config.contains("member_world")
			&& config.contains("custom_landscape")
			&& config.contains("want_myworld");
	}

	private static Path canonicalRoot(Path requestedRoot)
		throws IOException, WorldBuilderDiscoveryException {
		if (requestedRoot == null) {
			throw new WorldBuilderDiscoveryException("A private-server root is required.");
		}
		Path root = requestedRoot.toAbsolutePath().normalize();
		if (!Files.isDirectory(root)) {
			throw new WorldBuilderDiscoveryException("Private-server root is not a directory: " + root);
		}
		return root.toRealPath();
	}

	private static String normalizeRelative(String value) throws WorldBuilderDiscoveryException {
		if (value == null || value.trim().isEmpty()) {
			throw new WorldBuilderDiscoveryException("A configuration path is required.");
		}
		Path relative = java.nio.file.Paths.get(value.replace('\\', '/')).normalize();
		if (relative.isAbsolute() || relative.startsWith("..") || relative.toString().isEmpty()) {
			throw new WorldBuilderDiscoveryException("Configuration path must remain inside the server root.");
		}
		return relative.toString().replace('\\', '/');
	}

	private static Path requiredFile(Path root, String relative)
		throws IOException, WorldBuilderDiscoveryException {
		Path candidate = containedPath(root, relative);
		if (!Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
			throw new WorldBuilderDiscoveryException("Required private-server file is missing: " + relative);
		}
		Path real = candidate.toRealPath();
		if (!real.startsWith(root)) {
			throw new WorldBuilderDiscoveryException("Private-server path escapes its root: " + relative);
		}
		if (!Files.isRegularFile(real)) {
			throw new WorldBuilderDiscoveryException("Expected a regular private-server file: " + relative);
		}
		return real;
	}

	private static Path optionalFile(Path root, String relative)
		throws IOException, WorldBuilderDiscoveryException {
		Path candidate = containedPath(root, relative);
		if (!Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
			Path parent = candidate.getParent();
			if (parent == null || !Files.isDirectory(parent)) {
				throw new WorldBuilderDiscoveryException(
					"Optional world-file directory is missing: " + relative);
			}
			Path realParent = parent.toRealPath();
			if (!realParent.startsWith(root)) {
				throw new WorldBuilderDiscoveryException(
					"Optional world-file directory escapes its root: " + relative);
			}
			return null;
		}
		return requiredFile(root, relative);
	}

	private static Path containedPath(Path root, String relative)
		throws WorldBuilderDiscoveryException {
		Path candidate = root.resolve(relative).normalize();
		if (!candidate.startsWith(root)) {
			throw new WorldBuilderDiscoveryException("Private-server path escapes its root: " + relative);
		}
		return candidate;
	}

	private static List<WorldBuilderDiscoveryResult.SourceFile> inventoryAuthoredFiles(
		Path root, WorldBuilderPackedSourceLayout layout, Config config)
		throws IOException, WorldBuilderDiscoveryException {
		List<String[]> authoredFiles = new ArrayList<String[]>();
		authoredFiles.add(new String[] {"serverTerrain",
			layout.serverDataPath(TERRAIN_FILE)});
		authoredFiles.add(new String[] {"clientTerrain",
			layout.path("Custom_Landscape.orsc")});
		authoredFiles.add(new String[] {"sceneryLocs",
			layout.locationPath("MyWorldSceneryLocs.json")});
		authoredFiles.add(new String[] {"sceneryRemovals",
			layout.locationPath("MyWorldSceneryRemovals.json")});
		authoredFiles.add(new String[] {"npcLocs",
			layout.locationPath("MyWorldNpcLocs.json")});
		authoredFiles.add(new String[] {"npcRemovals",
			layout.locationPath("MyWorldNpcRemovals.json")});
		for (String[] active : activeAuxiliarySceneryFiles(config, layout)) {
			authoredFiles.add(active);
		}
		List<WorldBuilderDiscoveryResult.SourceFile> files =
			new ArrayList<WorldBuilderDiscoveryResult.SourceFile>();
		for (int index = 0; index < authoredFiles.size(); index++) {
			String logicalName = authoredFiles.get(index)[0];
			String relative = authoredFiles.get(index)[1];
			Path file = index < 2 ? requiredFile(root, relative) : optionalFile(root, relative);
			if (file == null) {
				files.add(new WorldBuilderDiscoveryResult.SourceFile(
					logicalName, relative, false, 0L, ""));
			} else {
				files.add(new WorldBuilderDiscoveryResult.SourceFile(
					logicalName, relative, true, Files.size(file), WorldBuilderHashes.sha256(file)));
			}
		}
		return files;
	}

	private static List<String[]> activeAuxiliarySceneryFiles(Config config,
		WorldBuilderPackedSourceLayout layout) throws WorldBuilderDiscoveryException {
		List<String[]> files = new ArrayList<String[]>();
		int locationData = config.optionalInt("location_data", 0);
		if (locationData == 4 && config.optionalBoolean("want_openpk_points", false)) {
			addAuxiliaryScenery(files, layout, "OpenPk");
		}
		if ((locationData == 1 || locationData == 2)
			&& config.optionalBoolean("want_fixed_broken_mechanics", false)) {
			addAuxiliaryScenery(files, layout, "Discontinued");
		}
		if (locationData == 2) {
			if (config.optionalBoolean("want_decorated_mod_room", false)) {
				addAuxiliaryScenery(files, layout, "ModRoom");
			}
			if (config.optionalBoolean("want_runecraft", false)) {
				addAuxiliaryScenery(files, layout, "Runecraft");
			}
			if (config.optionalBoolean("want_harvesting", false)) {
				addAuxiliaryScenery(files, layout, "Harvesting");
			}
			if (config.optionalBoolean("want_custom_quests", false)) {
				addAuxiliaryScenery(files, layout, "CustomQuest");
				addAuxiliaryScenery(files, layout, "Expansion");
			}
			if (config.optionalBoolean("mice_to_meet_you", false)) {
				addAuxiliaryScenery(files, layout, "MiceToMeetYou");
			}
			if (config.optionalBoolean("want_woodcutting_guild", false)) {
				addAuxiliaryScenery(files, layout, "WoodcuttingGuild");
			}
			addAuxiliaryScenery(files, layout, "Other");
		}
		return files;
	}

	private static void addAuxiliaryScenery(List<String[]> files,
		WorldBuilderPackedSourceLayout layout, String suffix) {
		files.add(new String[] {"sceneryAux" + suffix,
			layout.locationPath("SceneryLocs" + suffix + ".json")});
	}

	static boolean isAuxiliaryScenery(String logicalName) {
		return logicalName != null && logicalName.startsWith("sceneryAux")
			&& logicalName.length() > "sceneryAux".length();
	}

	static String auxiliarySceneryRole(String logicalName) {
		if (!isAuxiliaryScenery(logicalName)) throw new IllegalArgumentException(logicalName);
		String suffix = logicalName.substring("sceneryAux".length());
		return "scenery-auxiliary-" + suffix.replaceAll(
			"([a-z0-9])([A-Z])", "$1-$2").toLowerCase(Locale.ROOT);
	}

	static String[][] basePlacementFiles(int basedMapData) {
		return basePlacementFiles(basedMapData, WorldBuilderPackedSourceLayout.canonical());
	}

	static String[][] basePlacementFiles(int basedMapData,
		WorldBuilderPackedSourceLayout layout) {
		String suffix = basedMapData == 14 ? "14"
			: basedMapData == 27 ? "27" : "";
		return new String[][] {
			{"boundaryBase", layout.locationPath("BoundaryLocs" + suffix + ".json")},
			{"groundItemBase", layout.locationPath("GroundItems" + suffix + ".json")},
			{"npcBase", layout.locationPath("NpcLocs" + suffix + ".json")},
			{"sceneryBase", layout.locationPath("SceneryLocs" + suffix + ".json")}
		};
	}

	private static boolean sameInventory(List<WorldBuilderDiscoveryResult.SourceFile> first,
		List<WorldBuilderDiscoveryResult.SourceFile> second) {
		if (first.size() != second.size()) {
			return false;
		}
		for (int index = 0; index < first.size(); index++) {
			WorldBuilderDiscoveryResult.SourceFile left = first.get(index);
			WorldBuilderDiscoveryResult.SourceFile right = second.get(index);
			if (!left.logicalName.equals(right.logicalName)
				|| !left.relativePath.equals(right.relativePath)
				|| left.present != right.present
				|| left.size != right.size
				|| !left.sha256.equals(right.sha256)) {
				return false;
			}
		}
		return true;
	}

	static int validateTerrainArchive(Path archive)
		throws IOException, WorldBuilderDiscoveryException {
		int sectors = 0;
		Set<String> names = new HashSet<String>();
		try (ZipFile zip = new ZipFile(archive.toFile())) {
			Enumeration<? extends ZipEntry> entries = zip.entries();
			while (entries.hasMoreElements()) {
				ZipEntry entry = entries.nextElement();
				if (entry.isDirectory()) {
					continue;
				}
				if (!TERRAIN_ENTRY.matcher(entry.getName()).matches()) {
					throw new WorldBuilderDiscoveryException(
						"Terrain archive contains an unsupported entry: " + entry.getName());
				}
				if (!names.add(entry.getName())) {
					throw new WorldBuilderDiscoveryException(
						"Terrain archive contains a duplicate entry: " + entry.getName());
				}
				int bytes = 0;
				try (InputStream input = zip.getInputStream(entry)) {
					byte[] buffer = new byte[8192];
					int read;
					while ((read = input.read(buffer)) >= 0) {
						bytes += Math.max(0, read);
						if (bytes > TERRAIN_ENTRY_BYTES) {
							break;
						}
					}
				}
				if (bytes != TERRAIN_ENTRY_BYTES) {
					throw new WorldBuilderDiscoveryException(
						"Terrain entry has an invalid raw size: " + entry.getName());
				}
				sectors++;
			}
		}
		if (sectors == 0) {
			throw new WorldBuilderDiscoveryException("Terrain archive contains no sectors.");
		}
		return sectors;
	}

	private static String contentFingerprint(Path root,
		WorldBuilderPackedSourceLayout layout)
		throws IOException, WorldBuilderDiscoveryException {
		MessageDigest digest = WorldBuilderHashes.newDigest();
		List<String> contentFiles = new ArrayList<String>();
		for (String inside : SERVER_CONTENT_FILES) {
			contentFiles.add(layout.definitionPath(inside));
		}
		contentFiles.add(layout.path("library.orsc"));
		for (String relative : contentFiles) {
			Path file = requiredFile(root, relative);
			WorldBuilderHashes.updateText(digest, relative);
			WorldBuilderHashes.updateText(digest, Long.toString(Files.size(file)));
			WorldBuilderHashes.updateText(digest, WorldBuilderHashes.sha256(file));
		}
		return WorldBuilderHashes.hex(digest.digest());
	}

	private static String sourceFingerprint(String sourceProfileId,
		String configRelative, String configSha256, int clientVersion,
		int basedMapData, boolean memberWorld, boolean customLandscape, boolean wantMyWorld,
		String contentFingerprint, List<WorldBuilderDiscoveryResult.SourceFile> files) {
		MessageDigest digest = WorldBuilderHashes.newDigest();
		WorldBuilderHashes.updateText(digest, LAYOUT_ADAPTER);
		WorldBuilderHashes.updateText(digest, sourceProfileId);
		WorldBuilderHashes.updateText(digest, configRelative);
		WorldBuilderHashes.updateText(digest, configSha256);
		WorldBuilderHashes.updateText(digest, Integer.toString(clientVersion));
		WorldBuilderHashes.updateText(digest, Integer.toString(basedMapData));
		WorldBuilderHashes.updateText(digest, Boolean.toString(memberWorld));
		WorldBuilderHashes.updateText(digest, Boolean.toString(customLandscape));
		WorldBuilderHashes.updateText(digest, Boolean.toString(wantMyWorld));
		WorldBuilderHashes.updateText(digest, contentFingerprint);
		for (WorldBuilderDiscoveryResult.SourceFile file : files) {
			WorldBuilderHashes.updateText(digest, file.logicalName);
			WorldBuilderHashes.updateText(digest, file.relativePath);
			WorldBuilderHashes.updateText(digest, Boolean.toString(file.present));
			WorldBuilderHashes.updateText(digest, Long.toString(file.size));
			WorldBuilderHashes.updateText(digest, file.sha256);
		}
		return WorldBuilderHashes.hex(digest.digest());
	}

	private static final class Config {
		private final Map<String, List<String>> values;

		private Config(Map<String, List<String>> values) {
			this.values = values;
		}

		static Config read(Path path) throws IOException {
			Map<String, List<String>> values = new LinkedHashMap<String, List<String>>();
			for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
				Matcher matcher = CONFIG_VALUE.matcher(line);
				if (!matcher.matches()) {
					continue;
				}
				String key = matcher.group(1).toLowerCase(Locale.ROOT);
				String value = matcher.group(2).trim();
				if (value.isEmpty()) {
					continue;
				}
				List<String> matches = values.get(key);
				if (matches == null) {
					matches = new ArrayList<String>();
					values.put(key, matches);
				}
				matches.add(value);
			}
			return new Config(values);
		}

		boolean contains(String key) {
			List<String> matches = values.get(key.toLowerCase(Locale.ROOT));
			return matches != null && !matches.isEmpty();
		}

		boolean requiredBoolean(String key) throws WorldBuilderDiscoveryException {
			String value = requiredUnique(key).toLowerCase(Locale.ROOT);
			if ("true".equals(value)) {
				return true;
			}
			if ("false".equals(value)) {
				return false;
			}
			throw new WorldBuilderDiscoveryException(
				"Configuration value " + key + " must be true or false.");
		}

		int requiredInt(String key) throws WorldBuilderDiscoveryException {
			String value = requiredUnique(key);
			try {
				return Integer.parseInt(value);
			} catch (NumberFormatException failure) {
				throw new WorldBuilderDiscoveryException(
					"Configuration value " + key + " must be an integer.");
			}
		}

		boolean optionalBoolean(String key, boolean fallback)
			throws WorldBuilderDiscoveryException {
			List<String> matches = values.get(key.toLowerCase(Locale.ROOT));
			if (matches == null || matches.isEmpty()) return fallback;
			String value = requiredUnique(key).toLowerCase(Locale.ROOT);
			if ("true".equals(value)) return true;
			if ("false".equals(value)) return false;
			throw new WorldBuilderDiscoveryException(
				"Configuration value " + key + " must be true or false.");
		}

		int optionalInt(String key, int fallback) throws WorldBuilderDiscoveryException {
			List<String> matches = values.get(key.toLowerCase(Locale.ROOT));
			if (matches == null || matches.isEmpty()) return fallback;
			return requiredInt(key);
		}

		private String requiredUnique(String key) throws WorldBuilderDiscoveryException {
			List<String> matches = values.get(key.toLowerCase(Locale.ROOT));
			if (matches == null || matches.isEmpty()) {
				throw new WorldBuilderDiscoveryException(
					"Required configuration value is missing: " + key);
			}
			if (matches.size() != 1) {
				throw new WorldBuilderDiscoveryException(
					"Configuration value is ambiguous because it appears more than once: " + key);
			}
			return matches.get(0);
		}
	}
}
