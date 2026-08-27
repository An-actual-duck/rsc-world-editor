package com.openrsc.worldbuilder;

import java.io.IOException;
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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Exact source-path profile for equivalent OpenRSC packed layouts. */
final class WorldBuilderPackedSourceLayout {
	static final String CANONICAL_VIDEO_ROOT = "Client_Base/Cache/video";
	static final String CANONICAL_DEFINITION_ROOT = "server/conf/server/defs";
	static final String CANONICAL_DATA_ROOT = "server/conf/server/data";
	static final String CANONICAL_CONFIGURATION = "server/myworld.conf";
	static final List<String> CONFIGURATION_PATHS = Collections.unmodifiableList(Arrays.asList(
		CANONICAL_CONFIGURATION, "myworld.conf", "conf/server/myworld.conf",
		"server/conf/server/myworld.conf"));
	static final List<String> VIDEO_ROOTS = Collections.unmodifiableList(Arrays.asList(
		CANONICAL_VIDEO_ROOT, "client/Cache/video", "Cache/video"));
	static final List<String> DEFINITION_ROOTS = Collections.unmodifiableList(Arrays.asList(
		CANONICAL_DEFINITION_ROOT, "server/data/definitions", "server/data/defs",
		"conf/server/defs", "data/definitions"));
	static final List<String> DATA_ROOTS = Collections.unmodifiableList(Arrays.asList(
		CANONICAL_DATA_ROOT, "server/data", "conf/server/data", "data"));

	private static final List<String> CLIENT_FILES = Collections.unmodifiableList(
		Arrays.asList("Custom_Landscape.orsc", "library.orsc", "models.orsc",
			"Authentic_Sprites.orsc", "Custom_Sprites.osar", "spritepacks/Menus.osar"));
	private static final List<String> DEFINITION_FILES = Collections.unmodifiableList(
		Arrays.asList("TileDef.xml", "DoorDef.xml", "GameObjectDef.xml",
			"ItemDefs.json", "ItemDefsCustom.json", "ItemDefsPatch18.json",
			"ItemDefsMyWorld.json", "NpcDefs.json", "NpcDefsCustom.json",
			"NpcDefsPatch18.json", "NpcDefsMyWorld.json"));
	private static final List<String> OVERLAY_FILES = Collections.unmodifiableList(
		Arrays.asList("MyWorldGroundItemLocs.json", "MyWorldSceneryLocs.json",
			"MyWorldSceneryRemovals.json", "MyWorldNpcLocs.json",
			"MyWorldNpcRemovals.json"));
	private static final int CONFIGURATION_SCAN_DEPTH = 8;
	private static final int MAX_CONFIGURATION_SCAN_ENTRIES = 100000;
	private static final int MAX_CONFIGURATION_CANDIDATES = 256;

	final String profileId;
	final String videoRoot;
	final String definitionRoot;
	final String dataRoot;
	final String configurationPath;

	private WorldBuilderPackedSourceLayout(String profileId, String videoRoot,
		String definitionRoot, String dataRoot, String configurationPath) {
		this.profileId = profileId;
		this.videoRoot = videoRoot;
		this.definitionRoot = definitionRoot;
		this.dataRoot = dataRoot;
		this.configurationPath = configurationPath;
	}

	static WorldBuilderPackedSourceLayout canonical() {
		return create(CANONICAL_VIDEO_ROOT, CANONICAL_DEFINITION_ROOT,
			CANONICAL_DATA_ROOT, CANONICAL_CONFIGURATION);
	}

	static WorldBuilderPackedSourceLayout select(WorldBuilderReadOnlyTarget target)
		throws WorldBuilderContractException {
		return select(target, null);
	}

	static WorldBuilderPackedSourceLayout select(WorldBuilderReadOnlyTarget target,
		String requestedConfiguration) throws WorldBuilderContractException {
		String video = selectRoot(target, VIDEO_ROOTS, CLIENT_FILES,
			"client cache", CANONICAL_VIDEO_ROOT);
		String definitions = selectDefinitionRoot(target);
		String data = selectRoot(target, DATA_ROOTS,
			Collections.singletonList("Custom_Landscape.orsc"),
			"server terrain", CANONICAL_DATA_ROOT);
		String configuration = selectConfiguration(target, requestedConfiguration);
		return create(video, definitions, data, configuration);
	}

	private static String selectConfiguration(WorldBuilderReadOnlyTarget target,
		String requested) throws WorldBuilderContractException {
		List<String> paths = configurationPaths(target);
		if (requested != null && !requested.isEmpty()) {
			if ("primary".equals(requested)) {
				if (paths.size() == 1) return paths.get(0);
				if (paths.isEmpty()) return CANONICAL_CONFIGURATION;
				throw configurationAmbiguity(target, paths);
			}
			if (requested.matches("packed-map-[1-9][0-9]*")) {
				int index;
				try {
					index = Integer.parseInt(requested.substring("packed-map-".length())) - 1;
				} catch (NumberFormatException invalid) {
					index = -1;
				}
				if (index >= 0 && index < paths.size()) return paths.get(index);
				throw WorldBuilderReadOnlyTarget.problem(
					WorldBuilderErrorCodes.AMBIGUOUS_CONFIGURATION, "target-configuration",
					"The selected packed map is no longer present: " + requested + ".",
					"Run detection again and choose one currently listed map.");
			}
			return requireConfigurationPath(requested);
		}
		if (paths.size() > 1) throw configurationAmbiguity(target, paths);
		return paths.isEmpty() ? CANONICAL_CONFIGURATION : paths.get(0);
	}

	static List<WorldBuilderAdapterInspection.ConfigurationCandidate>
		configurationCandidates(WorldBuilderReadOnlyTarget target)
		throws WorldBuilderContractException {
		List<String> paths = configurationPaths(target);
		List<WorldBuilderAdapterInspection.ConfigurationCandidate> result =
			new ArrayList<WorldBuilderAdapterInspection.ConfigurationCandidate>();
		for (int index = 0; index < paths.size(); index++) {
			String path = paths.get(index);
			WorldBuilderReadOnlyTarget.FileState state = target.requiredState(
				"server-runtime-config", path);
			result.add(new WorldBuilderAdapterInspection.ConfigurationCandidate(
				paths.size() == 1 ? "primary" : "packed-map-" + (index + 1),
				path, state.sha256));
		}
		return result;
	}

	static String configurationRole(WorldBuilderReadOnlyTarget target, String selectedPath)
		throws WorldBuilderContractException {
		List<String> paths = configurationPaths(target);
		for (int index = 0; index < paths.size(); index++) {
			if (paths.get(index).equals(selectedPath)) {
				return paths.size() == 1 ? "primary" : "packed-map-" + (index + 1);
			}
		}
		return "primary";
	}

	static List<String> configurationPaths(WorldBuilderReadOnlyTarget target)
		throws WorldBuilderContractException {
		final Set<String> discovered = new HashSet<String>();
		for (String path : CONFIGURATION_PATHS) {
			if (target.exists(path)) discovered.add(path);
		}

		final Path serverRoot = target.root.resolve("server").normalize();
		if (Files.isDirectory(serverRoot, LinkOption.NOFOLLOW_LINKS)
			&& !Files.isSymbolicLink(serverRoot)) {
			final int[] entries = {0};
			try {
				Files.walkFileTree(serverRoot, Collections.emptySet(),
					CONFIGURATION_SCAN_DEPTH, new SimpleFileVisitor<Path>() {
						@Override public FileVisitResult preVisitDirectory(
							Path directory, BasicFileAttributes attributes) throws IOException {
							if (++entries[0] > MAX_CONFIGURATION_SCAN_ENTRIES) {
								throw new IOException("packed configuration scan exceeded "
									+ MAX_CONFIGURATION_SCAN_ENTRIES + " entries");
							}
							return Files.isSymbolicLink(directory)
								? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
						}

						@Override public FileVisitResult visitFile(
							Path file, BasicFileAttributes attributes) throws IOException {
							if (++entries[0] > MAX_CONFIGURATION_SCAN_ENTRIES) {
								throw new IOException("packed configuration scan exceeded "
									+ MAX_CONFIGURATION_SCAN_ENTRIES + " entries");
							}
							if (!attributes.isRegularFile() || Files.isSymbolicLink(file)
								|| !file.getFileName().toString().endsWith(".conf")) {
								return FileVisitResult.CONTINUE;
							}
							String relative = target.root.relativize(file.toAbsolutePath().normalize())
								.toString().replace('\\', '/');
							boolean mapConfiguration = false;
							if (WorldBuilderDiscovery.isSupportedConfigurationPath(relative)) {
								try {
									mapConfiguration = WorldBuilderDiscovery
										.looksLikePackedMapConfiguration(file);
								} catch (IOException malformedOrUnreadable) {
									// An unrelated or malformed named .conf is not a map candidate.
								}
							}
							if (mapConfiguration) {
								discovered.add(relative);
								if (discovered.size() > MAX_CONFIGURATION_CANDIDATES) {
									throw new IOException("more than "
										+ MAX_CONFIGURATION_CANDIDATES
										+ " packed map configurations were found");
								}
							}
							return FileVisitResult.CONTINUE;
						}
					});
			} catch (IOException failure) {
				throw WorldBuilderReadOnlyTarget.problem(
					WorldBuilderErrorCodes.INVENTORY_LIMIT_EXCEEDED,
					"server", "Bounded packed configuration discovery failed: "
						+ failure.getMessage(),
					"Reduce duplicate server configuration evidence and retry detection.",
					failure);
			}
		}
		List<String> result = new ArrayList<String>(discovered);
		Collections.sort(result);
		return result;
	}

	private static WorldBuilderContractException configurationAmbiguity(
		WorldBuilderReadOnlyTarget target, List<String> paths)
		throws WorldBuilderContractException {
		return new WorldBuilderAdaptiveConfiguration.SelectionException(
			WorldBuilderErrorCodes.AMBIGUOUS_CONFIGURATION, "target-configuration",
			"More than one supported server map configuration was found: " + paths + ".",
			"Choose the map configuration to import; discovery will not guess.",
			configurationCandidates(target));
	}

	private static String requireConfigurationPath(String value)
		throws WorldBuilderContractException {
		String normalized = value == null ? "" : value.replace('\\', '/');
		if (normalized.startsWith("/") || normalized.startsWith("../")
			|| normalized.contains("/../") || "..".equals(normalized)) {
			throw WorldBuilderReadOnlyTarget.problem(
				WorldBuilderErrorCodes.UNSAFE_PATH, normalized,
				"Configuration path must remain inside the server root.",
				"Select one contained compiled configuration path.");
		}
		if (!WorldBuilderDiscovery.isSupportedConfigurationPath(normalized)) {
			throw WorldBuilderReadOnlyTarget.problem(
				WorldBuilderErrorCodes.AMBIGUOUS_CONFIGURATION, normalized,
				"Selected packed configuration path is not supported.",
				"Use one detected packed map configuration below server/ or a supported "
					+ "legacy myworld.conf path.");
		}
		return normalized;
	}

	private static String selectDefinitionRoot(WorldBuilderReadOnlyTarget target)
		throws WorldBuilderContractException {
		List<String> recognizable = new ArrayList<String>();
		for (String root : DEFINITION_ROOTS) {
			boolean evidence = target.exists(root + "/locs");
			for (String inside : DEFINITION_FILES) evidence |= target.exists(root + "/" + inside);
			if (evidence) recognizable.add(root);
		}
		if (recognizable.size() > 1) {
			throw WorldBuilderReadOnlyTarget.problem(
				WorldBuilderErrorCodes.AMBIGUOUS_CONFIGURATION, "target-root",
				"More than one server definition root contains packed-layout evidence: "
					+ recognizable + ".",
				"Remove inactive/duplicate definitions or use one explicit truthful descriptor.");
		}
		return recognizable.isEmpty() ? CANONICAL_DEFINITION_ROOT : recognizable.get(0);
	}

	private static String selectRoot(WorldBuilderReadOnlyTarget target,
		List<String> roots, List<String> evidenceFiles, String label, String fallback)
		throws WorldBuilderContractException {
		List<String> recognizable = new ArrayList<String>();
		for (String root : roots) {
			for (String inside : evidenceFiles) {
				String path = inside.isEmpty() ? root : root + "/" + inside;
				if (target.exists(path)) {
					recognizable.add(root);
					break;
				}
			}
		}
		if (recognizable.size() > 1) {
			throw WorldBuilderReadOnlyTarget.problem(
				WorldBuilderErrorCodes.AMBIGUOUS_CONFIGURATION, "target-root",
				"More than one " + label + " root contains packed-layout evidence: "
					+ recognizable + ".",
				"Remove inactive/duplicate evidence or use one explicit truthful descriptor.");
		}
		return recognizable.isEmpty() ? fallback : recognizable.get(0);
	}

	String path(String inside) { return videoRoot + "/" + inside; }
	String canonicalPath(String inside) { return CANONICAL_VIDEO_ROOT + "/" + inside; }
	String definitionPath(String inside) { return definitionRoot + "/" + inside; }
	String canonicalDefinitionPath(String inside) {
		return CANONICAL_DEFINITION_ROOT + "/" + inside;
	}
	String locationPath(String inside) { return definitionPath("locs/" + inside); }
	String canonicalLocationPath(String inside) {
		return canonicalDefinitionPath("locs/" + inside);
	}
	String serverDataPath(String inside) { return dataRoot + "/" + inside; }
	String canonicalServerDataPath(String inside) { return CANONICAL_DATA_ROOT + "/" + inside; }

	List<WorldBuilderReadOnlyTarget.FileState> materializeCanonicalAliases(
		Path copiedTarget, int basedMapData) throws IOException, WorldBuilderContractException {
		WorldBuilderReadOnlyTarget source = WorldBuilderReadOnlyTarget.open(copiedTarget);
		List<Alias> aliases = new ArrayList<Alias>();
		if (!CANONICAL_VIDEO_ROOT.equals(videoRoot)) {
			for (String inside : CLIENT_FILES) aliases.add(new Alias(
				clientRole(inside), path(inside), canonicalPath(inside), true));
		}
		if (!CANONICAL_DATA_ROOT.equals(dataRoot)) aliases.add(new Alias(
			"server-terrain", serverDataPath("Custom_Landscape.orsc"),
			canonicalServerDataPath("Custom_Landscape.orsc"), true));
		if (!CANONICAL_DEFINITION_ROOT.equals(definitionRoot)) {
			for (String inside : DEFINITION_FILES) aliases.add(new Alias(
				definitionRole(inside), definitionPath(inside),
				canonicalDefinitionPath(inside), true));
			String suffix = basedMapData == 14 ? "14" : basedMapData == 27 ? "27" : "";
			aliases.add(new Alias("placement.boundary-base-source",
				locationPath("BoundaryLocs" + suffix + ".json"),
				canonicalLocationPath("BoundaryLocs" + suffix + ".json"), true));
			aliases.add(new Alias("placement.ground-item-base-source",
				locationPath("GroundItems" + suffix + ".json"),
				canonicalLocationPath("GroundItems" + suffix + ".json"), true));
			aliases.add(new Alias("placement.npc-base-source",
				locationPath("NpcLocs" + suffix + ".json"),
				canonicalLocationPath("NpcLocs" + suffix + ".json"), true));
			aliases.add(new Alias("placement.scenery-base-source",
				locationPath("SceneryLocs" + suffix + ".json"),
				canonicalLocationPath("SceneryLocs" + suffix + ".json"), true));
			for (String inside : OVERLAY_FILES) aliases.add(new Alias(
				overlayRole(inside), locationPath(inside),
				canonicalLocationPath(inside), false));
		}

		List<WorldBuilderReadOnlyTarget.FileState> generated =
			new ArrayList<WorldBuilderReadOnlyTarget.FileState>();
		for (Alias alias : aliases) {
			if (!alias.required && !source.exists(alias.from)) continue;
			WorldBuilderReadOnlyTarget.FileState expected = alias.required
				? source.requiredState("source-layout." + alias.role, alias.from)
				: source.optionalState("source-layout." + alias.role, alias.from);
			copyVerified(copiedTarget, source, expected, alias.from, alias.to);
		}
		WorldBuilderReadOnlyTarget normalized = WorldBuilderReadOnlyTarget.open(copiedTarget);
		for (Alias alias : aliases) {
			if (normalized.exists(alias.to)) {
				generated.add(normalized.requiredState(alias.role, alias.to));
			}
		}
		Collections.sort(generated);
		return generated;
	}

	private static void copyVerified(Path copiedTarget, WorldBuilderReadOnlyTarget source,
		WorldBuilderReadOnlyTarget.FileState expected, String from, String to)
		throws IOException, WorldBuilderContractException {
		Path destination = copiedTarget.resolve(to).normalize();
		if (!destination.startsWith(copiedTarget.toAbsolutePath().normalize())
			|| Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
			throw WorldBuilderReadOnlyTarget.problem(
				WorldBuilderErrorCodes.UNSAFE_PATH, to,
				"Canonical project-local source path is unsafe or already exists.",
				"Discard the unpublished project stage and retry from one source layout.");
		}
		Files.createDirectories(destination.getParent());
		Files.copy(source.requiredFile(from), destination, StandardCopyOption.COPY_ATTRIBUTES);
		if (Files.size(destination) != expected.size
			|| !WorldBuilderHashes.sha256(destination).equals(expected.sha256)) {
			throw WorldBuilderReadOnlyTarget.problem(
				WorldBuilderErrorCodes.SOURCE_CORRUPT, from,
				"Canonical project-local source copy differs from selected source evidence.",
				"Discard the unpublished project stage and retry from stable source bytes.");
		}
	}

	private static WorldBuilderPackedSourceLayout create(String videoRoot,
		String definitionRoot, String dataRoot, String configurationPath) {
		String clientId = CANONICAL_VIDEO_ROOT.equals(videoRoot)
			? "openrsc-source-client-base-cache-v1"
			: "client/Cache/video".equals(videoRoot)
				? "openrsc-packaged-client-cache-v1" : "openrsc-flat-client-cache-v1";
		String serverId = CANONICAL_DEFINITION_ROOT.equals(definitionRoot)
			&& CANONICAL_DATA_ROOT.equals(dataRoot) ? ""
			: "openrsc-packed-server-layout-"
				+ portableId(definitionRoot + "-" + dataRoot) + "-v1";
		String configurationId = CANONICAL_CONFIGURATION.equals(configurationPath)
			? "" : "openrsc-packed-config-" + portableId(configurationPath) + "-v1";
		String id = clientId;
		if (!serverId.isEmpty()) id += "+" + serverId;
		if (!configurationId.isEmpty()) id += "+" + configurationId;
		return new WorldBuilderPackedSourceLayout(id, videoRoot, definitionRoot,
			dataRoot, configurationPath);
	}

	private static String portableId(String value) {
		return value.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]+", "-")
			.replaceAll("^-|-$", "");
	}

	private static String clientRole(String inside) {
		if ("Custom_Landscape.orsc".equals(inside)) return "client-terrain";
		if ("library.orsc".equals(inside)) return "client-asset.library";
		if ("models.orsc".equals(inside)) return "content.asset.model";
		if ("Authentic_Sprites.orsc".equals(inside)) return "content.asset.sprite.authentic";
		if ("Custom_Sprites.osar".equals(inside)) return "content.asset.sprite.custom";
		if ("spritepacks/Menus.osar".equals(inside)) return "content.asset.spritepack";
		throw new AssertionError(inside);
	}

	private static String definitionRole(String inside) {
		if ("TileDef.xml".equals(inside)) return "server-definition.tile";
		if ("GameObjectDef.xml".equals(inside)) return "server-definition.scenery";
		if ("DoorDef.xml".equals(inside)) return "content.definition.boundary";
		if (inside.startsWith("NpcDefs")) return "server-definition.npc." + definitionVariant(inside);
		if (inside.startsWith("ItemDefs")) return "content.definition.item." + definitionVariant(inside);
		throw new AssertionError(inside);
	}

	private static String definitionVariant(String inside) {
		if (inside.contains("Custom")) return "custom";
		if (inside.contains("Patch")) return "patch";
		if (inside.contains("MyWorld")) return "world";
		return "base";
	}

	private static String overlayRole(String inside) {
		if (inside.startsWith("MyWorldGroundItem")) return "placement.ground-item-overlay";
		if (inside.startsWith("MyWorldSceneryRemovals")) return "placement.scenery-removal";
		if (inside.startsWith("MyWorldScenery")) return "placement.scenery-overlay";
		if (inside.startsWith("MyWorldNpcRemovals")) return "placement.npc-removal";
		if (inside.startsWith("MyWorldNpc")) return "placement.npc-overlay";
		throw new AssertionError(inside);
	}

	private static final class Alias {
		final String role;
		final String from;
		final String to;
		final boolean required;
		Alias(String role, String from, String to, boolean required) {
			this.role = role;
			this.from = from;
			this.to = to;
			this.required = required;
		}
	}
}
