package com.openrsc.worldbuilder;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Descriptor-backed packed adapter plus the narrow reviewed legacy fallback probe. */
final class WorldBuilderPackedLayoutAdapter implements WorldBuilderLayoutAdapter {
	static final String ID = "spoiled-milk-packed-v1";
	private static final String FORMAT_ID = "legacy-packed-orsc-v1";
	private static final String PACKAGE_SCHEMA_ID = "layered-world-package-v1";
	private static final String MUTATION_PROFILE_ID = "spoiled-milk-layered-install-v1";
	private static final String LEGACY_CAPABILITY_ID = "spoiled-milk-packed-fallback-v1";
	private static final String SERVER_TERRAIN =
		"server/conf/server/data/Custom_Landscape.orsc";
	private static final String CLIENT_TERRAIN =
		"Client_Base/Cache/video/Custom_Landscape.orsc";
	private static final String[][] LEGACY_CONTENT = {
		{"server-definition.tile", "server/conf/server/defs/TileDef.xml"},
		{"server-definition.scenery", "server/conf/server/defs/GameObjectDef.xml"},
		{"server-definition.npc.base", "server/conf/server/defs/NpcDefs.json"},
		{"server-definition.npc.custom", "server/conf/server/defs/NpcDefsCustom.json"},
		{"server-definition.npc.world", "server/conf/server/defs/NpcDefsMyWorld.json"},
		{"server-definition.npc.patch", "server/conf/server/defs/NpcDefsPatch18.json"},
		{"client-asset.library", "Client_Base/Cache/video/library.orsc"}
	};

	@Override
	public String id() {
		return ID;
	}

	@Override
	public Probe probe(WorldBuilderReadOnlyTarget target)
		throws WorldBuilderContractException {
		boolean config = target.exists(WorldBuilderDiscovery.DEFAULT_CONFIG);
		boolean evidence = config || target.exists(SERVER_TERRAIN)
			|| target.exists(CLIENT_TERRAIN)
			|| target.exists("server/conf/server/defs/locs")
			|| target.exists("server/conf/server/defs/TileDef.xml");
		if (!evidence) return Probe.NO_EVIDENCE;
		return config ? Probe.SUPPORTED : Probe.RECOGNIZABLE;
	}

	@Override
	public WorldBuilderAdapterInspection inspect(
		WorldBuilderReadOnlyTarget target,
		WorldBuilderTargetCapability capability,
		String requestedConfigurationRole) throws WorldBuilderContractException {
		if (capability == null) {
			return inspectLegacyFallback(target, requestedConfigurationRole);
		}
		if (!ID.equals(capability.adapterId)) {
			throw problem(WorldBuilderErrorCodes.UNSUPPORTED_ADAPTER,
				WorldBuilderTargetCapability.RELATIVE_PATH,
				"Packed adapter cannot handle descriptor adapter " + capability.adapterId + ".",
				"Select a registered adapter ID matching the active map layout.");
		}
		requireCapability(capability);
		WorldBuilderAdaptiveConfiguration.Selection selection =
			WorldBuilderAdaptiveConfiguration.select(target, capability,
				requestedConfigurationRole);
		WorldBuilderAdaptiveConfiguration configuration = selection.selected;
		if (!"packed".equals(configuration.representation)) {
			throw problem(WorldBuilderErrorCodes.CAPABILITY_MISMATCH,
				configuration.relativePath,
				"Packed adapter was given a " + configuration.representation + " configuration.",
				"Select a packed configuration or the generic layered adapter.");
		}
		WorldBuilderCompatibilityEvidence common =
			WorldBuilderCompatibilityEvidence.inspect(target, capability, configuration);
		WorldBuilderPackedMap map = WorldBuilderPackedMap.inspect(
			target, configuration, common.definitions);
		List<WorldBuilderReadOnlyTarget.FileState> files =
			new ArrayList<WorldBuilderReadOnlyTarget.FileState>();
		files.addAll(common.files);
		files.addAll(map.files);
		WorldBuilderGenericLayeredAdapter.validateInventoryAndRoles(
			files, capability, WorldBuilderTargetCapability.RELATIVE_PATH);

		List<WorldBuilderAdapterInspection.Check> checks =
			new ArrayList<WorldBuilderAdapterInspection.Check>();
		checks.add(new WorldBuilderAdapterInspection.Check(
			"adapter-capability", "passed",
			"Descriptor facts are independently accepted by spoiled-milk-packed-v1.",
			capability.capabilityId + " selects " + capability.adapterId + "."));
		checks.add(new WorldBuilderAdapterInspection.Check(
			"authoring-capability", "passed",
			"Converted existing/new levels and all placement families are authorable.",
			"Matching runtime evidence confirms the complete authoring set."));
		checks.add(new WorldBuilderAdapterInspection.Check(
			"client-server-map-agreement", "passed",
			"Server and client select one byte-identical packed terrain archive.",
			map.sectorCount + " packed sector(s) agree."));
		checks.add(new WorldBuilderAdapterInspection.Check(
			"configuration-selection", "passed",
			"Exactly one active configuration is selected, or the requested active role is explicit.",
			configuration.configurationId + " at " + configuration.relativePath + "."));
		checks.add(new WorldBuilderAdapterInspection.Check(
			"format-validation", "passed",
			"Packed entries have exact names, unique coordinates, and 23,040-byte raw payloads.",
			map.sectorCount + " bounded sector(s) validated."));
		checks.add(new WorldBuilderAdapterInspection.Check(
			"inventory-completeness", "passed",
			"Every configured terrain, placement, definition, asset, and runtime role is inventoried.",
			files.size() + " complete source evidence file(s)."));
		checks.add(new WorldBuilderAdapterInspection.Check(
			"placement-validation", "passed",
			"All placement families have valid order, definitions, ranges, removals, and terrain coverage.",
			map.boundaryCount + " boundary, " + map.groundItemCount
				+ " ground-item, " + map.npcCount + " NPC, and "
				+ map.sceneryCount + " scenery record(s) remain effective."));
		checks.addAll(common.checks);
		return new WorldBuilderAdapterInspection(ID, capability.capabilityId,
			WorldBuilderTargetCapability.RELATIVE_PATH, capability.evidenceSha256,
			"packed", selection.candidates(), selection.selectedCandidate(), files, checks);
	}

	private static WorldBuilderAdapterInspection inspectLegacyFallback(
		WorldBuilderReadOnlyTarget target, String requestedConfigurationRole)
		throws WorldBuilderContractException {
		if (requestedConfigurationRole != null && !requestedConfigurationRole.isEmpty()
			&& !"primary".equals(requestedConfigurationRole)) {
			throw problem(WorldBuilderErrorCodes.AMBIGUOUS_CONFIGURATION,
				WorldBuilderDiscovery.DEFAULT_CONFIG,
				"The built-in packed probe exposes only configuration role primary.",
				"Remove the role override or select primary.");
		}
		WorldBuilderReadOnlyTarget.FileState legacyConfig = target.requiredState(
			"server-runtime-config", WorldBuilderDiscovery.DEFAULT_CONFIG);
		if (legacyConfig.size > WorldBuilderContractLimits.MAX_JSON_BYTES) {
			throw problem(WorldBuilderErrorCodes.CONTRACT_LIMIT_EXCEEDED,
				WorldBuilderDiscovery.DEFAULT_CONFIG,
				"Built-in packed configuration exceeds the 16 MiB read limit.",
				"Reduce the configuration to a bounded reviewed layout.");
		}
		WorldBuilderPackedMap.validateArchive(
			target.requiredFile(SERVER_TERRAIN), SERVER_TERRAIN);
		WorldBuilderDiscoveryResult legacy;
		try {
			legacy = new WorldBuilderDiscovery().discover(
				target.root, WorldBuilderDiscovery.DEFAULT_CONFIG, null);
		} catch (WorldBuilderDiscoveryException failure) {
			String message = failure.getMessage() == null ? "legacy layout refusal"
				: failure.getMessage();
			String code = message.contains("byte-identical")
				|| message.contains("different sector")
				? WorldBuilderErrorCodes.MAP_MISMATCH
				: message.contains("escapes") || message.contains("relative server")
					? WorldBuilderErrorCodes.UNSAFE_PATH
					: WorldBuilderErrorCodes.MALFORMED_SERVER;
			throw problem(code, WorldBuilderDiscovery.DEFAULT_CONFIG,
				"Built-in packed probe refused the recognizable layout: " + message,
				"Correct the reviewed legacy layout or add a truthful capability descriptor.");
		}

		List<WorldBuilderReadOnlyTarget.FileState> files =
			new ArrayList<WorldBuilderReadOnlyTarget.FileState>();
		Map<String,String> roles = new LinkedHashMap<String,String>();
		roles.put("serverTerrain", "server-terrain");
		roles.put("clientTerrain", "client-terrain");
		roles.put("sceneryLocs", "placement.scenery-overlay");
		roles.put("sceneryRemovals", "placement.scenery-removal");
		roles.put("npcLocs", "placement.npc-overlay");
		roles.put("npcRemovals", "placement.npc-removal");
		for (WorldBuilderDiscoveryResult.SourceFile file : legacy.files) {
			String role = roles.get(file.logicalName);
			files.add(new WorldBuilderReadOnlyTarget.FileState(role, file.relativePath,
				file.present, file.size, file.sha256));
			if (file.present) validateLegacyPlacement(target, file.logicalName, file.relativePath);
		}
		for (String[] content : LEGACY_CONTENT) {
			files.add(target.requiredState(content[0], content[1]));
		}
		files.add(legacyConfig);
		Collections.sort(files);
		List<Object> records = new ArrayList<Object>(files.size());
		for (WorldBuilderReadOnlyTarget.FileState file : files) records.add(file.toJson());
		WorldBuilderBoundedInventory.read(records, "discover-target", 1, false);

		WorldBuilderAdapterInspection.ConfigurationCandidate candidate =
			new WorldBuilderAdapterInspection.ConfigurationCandidate(
				"primary", WorldBuilderDiscovery.DEFAULT_CONFIG,
				legacy.selectedConfigSha256);
		List<WorldBuilderAdapterInspection.Check> checks =
			new ArrayList<WorldBuilderAdapterInspection.Check>();
		checks.add(new WorldBuilderAdapterInspection.Check(
			"adapter-capability", "passed",
			"The exact bounded reviewed packed fallback layout is present.",
			LEGACY_CAPABILITY_ID + " inferred by compiled adapter " + ID + "."));
		checks.add(new WorldBuilderAdapterInspection.Check(
			"authoring-capability", "passed",
			"Fallback discovery records packed conversion inputs without activating project/runtime work.",
			"Phase 1 discovery only; later creation must revalidate runtime authoring capability."));
		checks.add(new WorldBuilderAdapterInspection.Check(
			"asset-agreement", "passed",
			"The compiled fallback's exact client asset evidence is present.",
			"Client library is bound into content fingerprint "
				+ legacy.contentFingerprintSha256 + "."));
		checks.add(new WorldBuilderAdapterInspection.Check(
			"client-server-map-agreement", "passed",
			"Server and client terrain archives are byte-identical.",
			legacy.terrainSectorCount + " packed sector(s) agree."));
		checks.add(new WorldBuilderAdapterInspection.Check(
			"configuration-selection", "passed",
			"The narrow fallback has exactly one compiled configuration path.",
			WorldBuilderDiscovery.DEFAULT_CONFIG + " is active."));
		checks.add(new WorldBuilderAdapterInspection.Check(
			"definition-agreement", "passed",
			"All compiled fallback definition/asset inputs are present and fingerprinted.",
			legacy.contentFingerprintSha256 + "."));
		checks.add(new WorldBuilderAdapterInspection.Check(
			"format-validation", "passed",
			"Every packed terrain entry has the reviewed name and raw byte shape.",
			legacy.terrainSectorCount + " bounded sector(s) validated."));
		checks.add(new WorldBuilderAdapterInspection.Check(
			"inventory-completeness", "passed",
			"Terrain, active MyWorld overlays/removals, definitions, client asset, and config are inventoried.",
			files.size() + " exact/required-absence evidence record(s)."));
		checks.add(new WorldBuilderAdapterInspection.Check(
			"placement-validation", "passed",
			"Known scenery/NPC overlays and removals parse strictly; boundary terrain is in packed sectors.",
			"No separate static ground-item source is enabled by this compiled fallback profile."));
		checks.add(new WorldBuilderAdapterInspection.Check(
			"runtime-agreement", "passed",
			"The selected config declares the reviewed client/map mode used by this fallback.",
			"clientVersion=" + legacy.clientVersion + ", basedMapData="
				+ legacy.basedMapData + "."));
		return new WorldBuilderAdapterInspection(ID, LEGACY_CAPABILITY_ID,
			WorldBuilderDiscovery.DEFAULT_CONFIG, legacy.selectedConfigSha256, "packed",
			Collections.singletonList(candidate), candidate, files, checks);
	}

	private static void validateLegacyPlacement(
		WorldBuilderReadOnlyTarget target, String logicalName, String relative)
		throws WorldBuilderContractException {
		try {
			if ("sceneryLocs".equals(logicalName)) {
				WorldBuilderJsonDocuments.validateSceneryLocs(target.requiredFile(relative));
			} else if ("sceneryRemovals".equals(logicalName)) {
				WorldBuilderJsonDocuments.validateSceneryRemovals(target.requiredFile(relative));
			} else if ("npcLocs".equals(logicalName)) {
				WorldBuilderJsonDocuments.validateNpcLocs(target.requiredFile(relative));
			} else if ("npcRemovals".equals(logicalName)) {
				WorldBuilderJsonDocuments.validateNpcRemovals(target.requiredFile(relative));
			}
		} catch (WorldBuilderDiscoveryException malformed) {
			throw problem(WorldBuilderErrorCodes.MALFORMED_SERVER, relative,
				"Legacy packed placement evidence is malformed: " + malformed.getMessage(),
				"Correct the exact overlay/removal JSON and retry discovery.");
		} catch (IOException failure) {
			throw problem(WorldBuilderErrorCodes.DISCOVERY_DRIFT, relative,
				"Legacy placement evidence changed while it was parsed.",
				"Stop target updates and retry discovery.");
		}
	}

	static void requireCapability(WorldBuilderTargetCapability capability)
		throws WorldBuilderContractException {
		if (!FORMAT_ID.equals(capability.mapFormatId)
			|| !PACKAGE_SCHEMA_ID.equals(capability.packageSchemaId)
			|| !capability.sourceRepresentations.equals(Collections.singletonList("packed"))
			|| !capability.encodingVersions.equals(Collections.singletonList(Integer.valueOf(1)))
			|| !capability.editExistingLevels || !capability.createLevels
			|| !capability.placementFamilies.equals(
				Arrays.asList("boundary", "ground-item", "npc", "scenery"))
			|| !capability.serverLoaderId.equals(capability.clientLoaderId)) {
			throw problem(WorldBuilderErrorCodes.CAPABILITY_MISMATCH,
				WorldBuilderTargetCapability.RELATIVE_PATH,
				"Descriptor does not declare the complete packed conversion/authoring contract.",
				"Declare legacy-packed-orsc-v1, package v1, encoding 1, matching loaders, and all authoring families.");
		}
		if (capability.installEnabled
			&& (!MUTATION_PROFILE_ID.equals(capability.mutationProfileId)
				|| !capability.installServerRoles.equals(
					Collections.singletonList("layered-package"))
				|| !capability.installClientRoles.equals(
					Collections.singletonList("layered-package"))
				|| !capability.installConfigurationRoles.equals(
					capability.configurationRoles))) {
			throw problem(WorldBuilderErrorCodes.CAPABILITY_MISMATCH,
				WorldBuilderTargetCapability.RELATIVE_PATH,
				"Descriptor requests install authority outside the compiled packed profile.",
				"Use spoiled-milk-layered-install-v1 with only layered-package and declared configuration roles.");
		}
	}

	private static WorldBuilderContractException problem(
		String code, String path, String message, String nextStep) {
		return WorldBuilderReadOnlyTarget.problem(code, path, message, nextStep);
	}
}
