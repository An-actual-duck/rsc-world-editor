package com.openrsc.worldbuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Project-local descriptor evidence for the exact compiled legacy packed fallback. */
final class WorldBuilderPackedFallbackEvidence {
	static final String CAPABILITY_ID = "spoiled-milk-packed-fallback-v1";
	static final String CONFIGURATION_PATH =
		"server/world-builder-configs/primary.json";
	static final String SERVER_DEFINITIONS =
		"server/world-builder-fallback/definitions.json";
	static final String CLIENT_DEFINITIONS =
		"Client_Base/world-builder-fallback/definitions.json";
	static final String SERVER_RUNTIME =
		"server/world-builder-fallback/runtime.json";
	static final String CLIENT_RUNTIME =
		"Client_Base/world-builder-fallback/runtime.json";
	static final String SERVER_ASSET =
		"server/world-builder-fallback/library.orsc";
	static final String BOUNDARY_PLACEMENTS =
		"server/world-builder-fallback/boundaries.json";
	static final String GROUND_ITEM_PLACEMENTS =
		"server/world-builder-fallback/ground-items.json";
	static final String NPC_PLACEMENTS =
		"server/world-builder-fallback/npcs.json";
	static final String SCENERY_PLACEMENTS =
		"server/world-builder-fallback/scenery.json";
	private static final String TARGET_GROUND_ITEM_PLACEMENTS =
		"server/conf/server/defs/locs/MyWorldGroundItemLocs.json";
	static final String CLIENT_ASSET =
		"Client_Base/Cache/video/library.orsc";
	private static final String SERVER_TERRAIN =
		"server/conf/server/data/Custom_Landscape.orsc";
	private static final String CLIENT_TERRAIN =
		"Client_Base/Cache/video/Custom_Landscape.orsc";
	private static final String ZERO_HASH =
		"0000000000000000000000000000000000000000000000000000000000000000";
	private static final List<String> FAMILIES =
		Arrays.asList("boundary", "ground-item", "npc", "scenery");

	private WorldBuilderPackedFallbackEvidence() {
	}

	static List<String> reservedTargetPaths() {
		return Arrays.asList(CONFIGURATION_PATH, SERVER_DEFINITIONS,
			CLIENT_DEFINITIONS, SERVER_RUNTIME, CLIENT_RUNTIME, SERVER_ASSET,
			BOUNDARY_PLACEMENTS, GROUND_ITEM_PLACEMENTS, NPC_PLACEMENTS,
			SCENERY_PLACEMENTS);
	}

	static Result materialize(Path projectStage, Path original, Map<String,Object> discovery,
		WorldBuilderAdaptiveRuntimePreparer.SourceRuntime runtime)
		throws IOException, WorldBuilderContractException {
		return materialize(projectStage, original, discovery, runtime, null);
	}

	static Result materialize(Path projectStage, Path original, Map<String,Object> discovery,
		WorldBuilderAdaptiveRuntimePreparer.SourceRuntime runtime, Path itemVisualMappings)
		throws IOException, WorldBuilderContractException {
		WorldBuilderPackedSourceLayout sourceLayout = WorldBuilderPackedSourceLayout.select(
			WorldBuilderReadOnlyTarget.open(original));
		WorldBuilderDiscoveryResult legacy;
		try {
			legacy = new WorldBuilderDiscovery().discover(
				original, sourceLayout.configurationPath, null,
				sourceLayout);
		} catch (WorldBuilderDiscoveryException invalid) {
			throw problem(sourceLayout.configurationPath,
				"Copied packed source no longer matches its discovered base-placement profile.",
				invalid);
		}
		List<WorldBuilderReadOnlyTarget.FileState> normalizedSourceFiles =
			sourceLayout.materializeCanonicalAliases(original, legacy.basedMapData);
		WorldBuilderProjectContentBundle.Bundle content =
			WorldBuilderProjectContentBundle.capture(
				projectStage, original, runtime, itemVisualMappings);
		Map<String,Object> catalog = content.compatibilityCatalog();
		String catalogId = (String)catalog.get("catalogId");
		writeJson(original, SERVER_DEFINITIONS, catalog);
		copyNew(original.resolve(SERVER_DEFINITIONS),
			original.resolve(CLIENT_DEFINITIONS));
		String catalogHash = WorldBuilderHashes.sha256(
			original.resolve(SERVER_DEFINITIONS));

		Map<String,Object> authoring = new LinkedHashMap<String,Object>();
		authoring.put("editExistingLevels", Boolean.TRUE);
		authoring.put("createLevels", Boolean.TRUE);
		authoring.put("placementFamilies", new ArrayList<String>(FAMILIES));
		writeJson(original, SERVER_RUNTIME,
			runtimeEvidence("server", "world-builder-fallback-server-v1",
				catalogId, catalogHash, authoring));
		writeJson(original, CLIENT_RUNTIME,
			runtimeEvidence("client", "world-builder-fallback-client-v1",
				catalogId, catalogHash, authoring));
		copyNew(original.resolve(CLIENT_ASSET), original.resolve(SERVER_ASSET));
		List<WorldBuilderPackedCompatibilityCorrections.Correction> corrections =
			writeBasePlacements(original, legacy.basedMapData);
		WorldBuilderPackedCompatibilityCorrections.writeReport(
			projectStage, corrections);

		List<Object> placements = placements(original);
		Map<String,Object> configuration = configuration(placements);
		writeJson(original, CONFIGURATION_PATH, configuration);
		String configurationHash = WorldBuilderHashes.sha256(
			original.resolve(CONFIGURATION_PATH));

		List<String> sourceRoles = new ArrayList<String>(Arrays.asList(
			"client-asset.library", "client-definition-catalog", "client-runtime",
			"client-terrain", "server-asset.library", "server-definition-catalog",
			"server-runtime", "server-terrain"));
		for (Object raw : placements) {
			@SuppressWarnings("unchecked") Map<String,Object> placement =
				(Map<String,Object>)raw;
			sourceRoles.add("placement." + (String)placement.get("role"));
		}
		Collections.sort(sourceRoles);
		Map<String,Object> capability = capability(
			catalogId, catalogHash, authoring, sourceRoles);
		writeJson(original, WorldBuilderTargetCapability.RELATIVE_PATH, capability);

		WorldBuilderReadOnlyTarget target = WorldBuilderReadOnlyTarget.open(original);
		WorldBuilderTargetCapability parsedCapability =
			WorldBuilderTargetCapability.read(target);
		WorldBuilderAdaptiveConfiguration parsedConfiguration =
			WorldBuilderAdaptiveConfiguration.select(
				target, parsedCapability, "primary").selected;
		List<WorldBuilderReadOnlyTarget.FileState> generated = generatedStates(target);
		generated.addAll(normalizedSourceFiles);
		Collections.sort(generated);
		Map<String,Object> conversionReport = derivedReport(
			discovery, parsedCapability, parsedConfiguration, generated);
		return new Result(parsedCapability, parsedConfiguration,
			generated, conversionReport);
	}

	private static Map<String,Object> runtimeEvidence(String side, String build,
		String catalogId, String catalogHash, Map<String,Object> authoring) {
		Map<String,Object> value = new LinkedHashMap<String,Object>();
		value.put("schemaVersion", Long.valueOf(1L));
		value.put("manifestType", "world-builder-runtime-evidence");
		value.put("side", side);
		value.put("buildId", build);
		value.put("loaderId", "layered-loader-v2");
		value.put("protocolId", "rsc-world-builder-loopback-v1");
		value.put("definitionCatalogId", catalogId);
		value.put("definitionCatalogSha256", catalogHash);
		value.put("mapFormatId", "legacy-packed-orsc-v1");
		value.put("packageSchemaId", "layered-world-package-v1");
		value.put("encodingVersions", Arrays.<Object>asList(Long.valueOf(1L)));
		value.put("authoring", new LinkedHashMap<String,Object>(authoring));
		return value;
	}

	private static Map<String,Object> configuration(List<Object> placements) {
		Map<String,Object> value = new LinkedHashMap<String,Object>();
		value.put("schemaVersion", Long.valueOf(1L));
		value.put("manifestType", WorldBuilderAdaptiveConfiguration.MANIFEST_TYPE);
		value.put("configurationId", "primary");
		value.put("active", Boolean.TRUE);
		value.put("representation", "packed");
		value.put("serverMapRelativePath", SERVER_TERRAIN);
		value.put("clientMapRelativePath", CLIENT_TERRAIN);
		value.put("serverRuntimeRelativePath", SERVER_RUNTIME);
		value.put("clientRuntimeRelativePath", CLIENT_RUNTIME);
		value.put("serverDefinitionCatalogRelativePath", SERVER_DEFINITIONS);
		value.put("clientDefinitionCatalogRelativePath", CLIENT_DEFINITIONS);
		Map<String,Object> asset = new LinkedHashMap<String,Object>();
		asset.put("role", "library");
		asset.put("serverRelativePath", SERVER_ASSET);
		asset.put("clientRelativePath", CLIENT_ASSET);
		value.put("assets", Arrays.<Object>asList(asset));
		value.put("placements", placements);
		return value;
	}

	private static Map<String,Object> capability(String catalogId, String catalogHash,
		Map<String,Object> authoring, List<String> sourceRoles) {
		Map<String,Object> value = new LinkedHashMap<String,Object>();
		value.put("schemaVersion", Long.valueOf(1L));
		value.put("manifestType", "world-builder-target-capability");
		value.put("adapterId", WorldBuilderPackedLayoutAdapter.ID);
		value.put("capabilityId", CAPABILITY_ID);
		value.put("server", side("world-builder-fallback-server-v1", false));
		value.put("client", side("world-builder-fallback-client-v1", true));
		Map<String,Object> definitions = new LinkedHashMap<String,Object>();
		definitions.put("catalogId", catalogId);
		definitions.put("catalogSha256", catalogHash);
		value.put("definitions", definitions);
		Map<String,Object> map = new LinkedHashMap<String,Object>();
		map.put("formatId", "legacy-packed-orsc-v1");
		map.put("packageSchemaId", "layered-world-package-v1");
		map.put("encodingVersions", Arrays.<Object>asList(Long.valueOf(1L)));
		value.put("map", map);
		Map<String,Object> discovery = new LinkedHashMap<String,Object>();
		discovery.put("configurationRoles", Arrays.<Object>asList("primary"));
		discovery.put("sourceRepresentations", Arrays.<Object>asList("packed"));
		discovery.put("sourceRoles", new ArrayList<String>(sourceRoles));
		value.put("discovery", discovery);
		value.put("authoring", new LinkedHashMap<String,Object>(authoring));
		Map<String,Object> install = new LinkedHashMap<String,Object>();
		install.put("enabled", Boolean.FALSE);
		install.put("serverRoles", new ArrayList<Object>());
		install.put("clientRoles", new ArrayList<Object>());
		install.put("configurationRoles", new ArrayList<Object>());
		install.put("mutationProfileId", "");
		install.put("offlineEvidence", new ArrayList<Object>());
		value.put("install", install);
		return value;
	}

	private static Map<String,Object> side(String build, boolean client) {
		Map<String,Object> value = new LinkedHashMap<String,Object>();
		value.put("buildId", build);
		if (client) value.put("protocolId", "rsc-world-builder-loopback-v1");
		value.put("loaderId", "layered-loader-v2");
		return value;
	}

	private static List<Object> placements(Path original) {
		List<Object> result = new ArrayList<Object>();
		addPlacement(result, original, "boundary-base", "boundary", "base",
			BOUNDARY_PLACEMENTS);
		addPlacement(result, original, "ground-item-base", "ground-item", "base",
			GROUND_ITEM_PLACEMENTS);
		addPlacement(result, original, "npc-base", "npc", "base",
			NPC_PLACEMENTS);
		addPlacement(result, original, "scenery-base", "scenery", "base",
			SCENERY_PLACEMENTS);
		addPlacement(result, original, "ground-item-overlay", "ground-item", "overlay",
			TARGET_GROUND_ITEM_PLACEMENTS);
		addPlacement(result, original, "scenery-overlay", "scenery", "overlay",
			"server/conf/server/defs/locs/MyWorldSceneryLocs.json");
		addPlacement(result, original, "scenery-removal", "scenery", "removal",
			"server/conf/server/defs/locs/MyWorldSceneryRemovals.json");
		addPlacement(result, original, "npc-overlay", "npc", "overlay",
			"server/conf/server/defs/locs/MyWorldNpcLocs.json");
		addPlacement(result, original, "npc-removal", "npc", "removal",
			"server/conf/server/defs/locs/MyWorldNpcRemovals.json");
		return result;
	}

	private static List<WorldBuilderPackedCompatibilityCorrections.Correction>
		writeBasePlacements(Path original, int basedMapData)
		throws IOException, WorldBuilderContractException {
		String[][] sources = WorldBuilderDiscovery.basePlacementFiles(basedMapData);
		writeBasePlacement(original, sources[0][1], BOUNDARY_PLACEMENTS,
			"boundaries", "boundaries", false);
		writeBasePlacement(original, sources[1][1], GROUND_ITEM_PLACEMENTS,
			"grounditems", "ground_items", false);
		List<WorldBuilderPackedCompatibilityCorrections.Correction> corrections =
			writeBasePlacement(original, sources[2][1], NPC_PLACEMENTS,
				"npclocs", "npclocs", true);
		writeBasePlacement(original, sources[3][1], SCENERY_PLACEMENTS,
			"sceneries", "sceneries", false);
		return corrections;
	}

	private static List<WorldBuilderPackedCompatibilityCorrections.Correction>
		writeBasePlacement(Path original, String sourceRelative,
		String destinationRelative, String sourceKey, String destinationKey,
		boolean normalizeKnownNpcDefects)
		throws IOException, WorldBuilderContractException {
		try {
			Map<String,Object> source = WorldBuilderJsonDocuments.readTargetDefinitionObject(
				original.resolve(sourceRelative));
			if (source.size() != 1 || !(source.get(sourceKey) instanceof List)) {
				throw new WorldBuilderDiscoveryException(
					"base placement document has the wrong root");
			}
			@SuppressWarnings("unchecked") List<Object> records =
				(List<Object>)source.get(sourceKey);
			List<WorldBuilderPackedCompatibilityCorrections.Correction> corrections =
				Collections.emptyList();
			if (normalizeKnownNpcDefects) {
				WorldBuilderPackedCompatibilityCorrections.Result normalized =
					WorldBuilderPackedCompatibilityCorrections.normalizeBaseNpcs(
						sourceRelative, records);
				records = normalized.records;
				corrections = normalized.corrections;
			}
			Map<String,Object> normalized = new LinkedHashMap<String,Object>();
			normalized.put(destinationKey, records);
			writeJson(original, destinationRelative, normalized);
			return corrections;
		} catch (WorldBuilderDiscoveryException malformed) {
			throw problem(sourceRelative,
				"Selected base placement evidence is malformed or unsupported.", malformed);
		}
	}

	private static void addPlacement(List<Object> values, Path original,
		String role, String family, String kind, String relative) {
		if (!Files.isRegularFile(original.resolve(relative))) return;
		Map<String,Object> value = new LinkedHashMap<String,Object>();
		value.put("role", role);
		value.put("family", family);
		value.put("kind", kind);
		value.put("compositionOrder", Long.valueOf(values.size()));
		value.put("encoding", "packed-" + family + "-"
			+ ("removal".equals(kind) ? "removals" : "locations") + "-v1");
		value.put("relativePath", relative);
		values.add(value);
	}

	private static List<WorldBuilderReadOnlyTarget.FileState> generatedStates(
		WorldBuilderReadOnlyTarget target) throws WorldBuilderContractException {
		List<WorldBuilderReadOnlyTarget.FileState> result =
			new ArrayList<WorldBuilderReadOnlyTarget.FileState>();
		result.add(target.requiredState("target-capability",
			WorldBuilderTargetCapability.RELATIVE_PATH));
		result.add(target.requiredState("configuration.primary", CONFIGURATION_PATH));
		result.add(target.requiredState("server-definition-catalog", SERVER_DEFINITIONS));
		result.add(target.requiredState("client-definition-catalog", CLIENT_DEFINITIONS));
		result.add(target.requiredState("server-runtime", SERVER_RUNTIME));
		result.add(target.requiredState("client-runtime", CLIENT_RUNTIME));
		result.add(target.requiredState("server-asset.library", SERVER_ASSET));
		result.add(target.requiredState("placement.boundary-base", BOUNDARY_PLACEMENTS));
		result.add(target.requiredState(
			"placement.ground-item-base", GROUND_ITEM_PLACEMENTS));
		result.add(target.requiredState("placement.npc-base", NPC_PLACEMENTS));
		result.add(target.requiredState("placement.scenery-base", SCENERY_PLACEMENTS));
		Collections.sort(result);
		return result;
	}

	private static Map<String,Object> derivedReport(Map<String,Object> original,
		WorldBuilderTargetCapability capability,
		WorldBuilderAdaptiveConfiguration configuration,
		List<WorldBuilderReadOnlyTarget.FileState> generated)
		throws WorldBuilderContractException {
		Map<String,Object> report;
		try {
			report = WorldBuilderJsonDocuments.readObject(
				WorldBuilderJsonDocuments.pretty(original).getBytes(StandardCharsets.UTF_8),
				"fallback conversion report");
		} catch (WorldBuilderDiscoveryException malformed) {
			throw problem("discovery/report.json",
				"Fallback discovery report could not be copied safely.", malformed);
		}
		WorldBuilderReadOnlyTarget.FileState descriptor = find(
			generated, WorldBuilderTargetCapability.RELATIVE_PATH);
		report.put("descriptor", descriptorReference(descriptor));
		Map<String,Object> candidate = new LinkedHashMap<String,Object>();
		candidate.put("role", "primary");
		candidate.put("relativePath", CONFIGURATION_PATH);
		candidate.put("sha256", configuration.sha256);
		report.put("configurationCandidates", Arrays.<Object>asList(candidate));
		Map<String,Object> selected = new LinkedHashMap<String,Object>(candidate);
		selected.put("present", Boolean.TRUE);
		report.put("selectedConfiguration", selected);
		report.put("capability", capability.reference());

		List<WorldBuilderReadOnlyTarget.FileState> files =
			new ArrayList<WorldBuilderReadOnlyTarget.FileState>();
		for (Object raw : array(original.get("files"), "files")) {
			Map<String,Object> file = object(raw, "files");
			if (Boolean.TRUE.equals(file.get("present"))) {
				files.add(new WorldBuilderReadOnlyTarget.FileState(
					(String)file.get("role"), (String)file.get("relativePath"), true,
					((Long)file.get("size")).longValue(), (String)file.get("sha256")));
			}
		}
		for (WorldBuilderReadOnlyTarget.FileState file : generated) {
			if (!"target-capability".equals(file.role)
				&& !"configuration.primary".equals(file.role)) files.add(file);
		}
		Collections.sort(files);
		List<Object> documents = new ArrayList<Object>(files.size());
		for (WorldBuilderReadOnlyTarget.FileState file : files) {
			documents.add(file.toJson());
		}
		report.put("files", documents);
		String display = (String)report.get("targetRootDisplay");
		report.put("targetRootDisplay", "");
		report.put("discoveryFingerprintSha256", ZERO_HASH);
		String fingerprint = WorldBuilderHashes.sha256(
			WorldBuilderJsonDocuments.canonical(report).getBytes(StandardCharsets.UTF_8));
		report.put("targetRootDisplay", display);
		report.put("discoveryFingerprintSha256", fingerprint);
		WorldBuilderAdaptiveContracts.validateParsed(
			WorldBuilderAdaptiveContracts.Kind.DISCOVERY_REPORT, report);
		return report;
	}

	private static WorldBuilderReadOnlyTarget.FileState find(
		List<WorldBuilderReadOnlyTarget.FileState> files, String relative) {
		for (WorldBuilderReadOnlyTarget.FileState file : files) {
			if (relative.equals(file.relativePath)) return file;
		}
		throw new AssertionError(relative);
	}

	private static Map<String,Object> descriptorReference(
		WorldBuilderReadOnlyTarget.FileState descriptor) {
		Map<String,Object> value = new LinkedHashMap<String,Object>();
		value.put("present", Boolean.TRUE);
		value.put("relativePath", descriptor.relativePath);
		value.put("sha256", descriptor.sha256);
		return value;
	}

	private static void writeJson(Path root, String relative,
		Map<String,Object> value) throws IOException {
		Path path = root.resolve(relative).normalize();
		Files.createDirectories(path.getParent());
		Files.write(path, WorldBuilderJsonDocuments.pretty(value)
			.getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE_NEW);
	}

	private static void copyNew(Path source, Path destination) throws IOException {
		Files.createDirectories(destination.getParent());
		Files.copy(source, destination);
	}

	@SuppressWarnings("unchecked")
	private static Map<String,Object> object(Object value, String label)
		throws WorldBuilderContractException {
		if (!(value instanceof Map)) throw problem(label,
			"Fallback discovery field is not an object.");
		return (Map<String,Object>)value;
	}

	private static List<?> array(Object value, String label)
		throws WorldBuilderContractException {
		if (!(value instanceof List)) throw problem(label,
			"Fallback discovery field is not an array.");
		return (List<?>)value;
	}

	private static WorldBuilderContractException problem(String path, String message) {
		return problem(path, message, null);
	}

	private static WorldBuilderContractException problem(
		String path, String message, Throwable cause) {
		return new WorldBuilderContractException(WorldBuilderErrorCodes.CONVERSION_BLOCKED,
			"create-project", path, false, message,
			"Rediscover the unchanged exact built-in packed layout and retry.", cause);
	}

	static final class Result {
		final WorldBuilderTargetCapability capability;
		final WorldBuilderAdaptiveConfiguration configuration;
		final List<WorldBuilderReadOnlyTarget.FileState> generated;
		final Map<String,Object> conversionReport;

		Result(WorldBuilderTargetCapability capability,
			WorldBuilderAdaptiveConfiguration configuration,
			List<WorldBuilderReadOnlyTarget.FileState> generated,
			Map<String,Object> conversionReport) {
			this.capability = capability;
			this.configuration = configuration;
			this.generated = Collections.unmodifiableList(
				new ArrayList<WorldBuilderReadOnlyTarget.FileState>(generated));
			this.conversionReport = conversionReport;
		}
	}
}
