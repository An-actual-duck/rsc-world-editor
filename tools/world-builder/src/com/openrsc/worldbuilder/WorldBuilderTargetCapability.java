package com.openrsc.worldbuilder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Typed, strict view of the Phase 0 target-capability-v1 contract. */
final class WorldBuilderTargetCapability {
	static final String RELATIVE_PATH = "server/world-builder-capabilities.json";

	final String adapterId;
	final String capabilityId;
	final String serverBuildId;
	final String serverLoaderId;
	final String clientBuildId;
	final String clientProtocolId;
	final String clientLoaderId;
	final String definitionCatalogId;
	final String definitionCatalogSha256;
	final String mapFormatId;
	final String packageSchemaId;
	final List<Integer> encodingVersions;
	final List<String> configurationRoles;
	final List<String> sourceRepresentations;
	final List<String> sourceRoles;
	final boolean editExistingLevels;
	final boolean createLevels;
	final List<String> placementFamilies;
	final boolean installEnabled;
	final List<String> installServerRoles;
	final List<String> installClientRoles;
	final List<String> installConfigurationRoles;
	final String mutationProfileId;
	final List<String> offlineEvidence;
	final String evidenceSha256;

	private WorldBuilderTargetCapability(Map<String,Object> root, String evidenceSha256) {
		adapterId = string(root, "adapterId");
		capabilityId = string(root, "capabilityId");
		Map<String,Object> server = object(root, "server");
		serverBuildId = string(server, "buildId");
		serverLoaderId = string(server, "loaderId");
		Map<String,Object> client = object(root, "client");
		clientBuildId = string(client, "buildId");
		clientProtocolId = string(client, "protocolId");
		clientLoaderId = string(client, "loaderId");
		Map<String,Object> definitions = object(root, "definitions");
		definitionCatalogId = string(definitions, "catalogId");
		definitionCatalogSha256 = string(definitions, "catalogSha256");
		Map<String,Object> map = object(root, "map");
		mapFormatId = string(map, "formatId");
		packageSchemaId = string(map, "packageSchemaId");
		encodingVersions = integers(map.get("encodingVersions"));
		Map<String,Object> discovery = object(root, "discovery");
		configurationRoles = strings(discovery.get("configurationRoles"));
		sourceRepresentations = strings(discovery.get("sourceRepresentations"));
		sourceRoles = strings(discovery.get("sourceRoles"));
		Map<String,Object> authoring = object(root, "authoring");
		editExistingLevels = bool(authoring, "editExistingLevels");
		createLevels = bool(authoring, "createLevels");
		placementFamilies = strings(authoring.get("placementFamilies"));
		Map<String,Object> install = object(root, "install");
		installEnabled = bool(install, "enabled");
		installServerRoles = strings(install.get("serverRoles"));
		installClientRoles = strings(install.get("clientRoles"));
		installConfigurationRoles = strings(install.get("configurationRoles"));
		mutationProfileId = string(install, "mutationProfileId");
		offlineEvidence = strings(install.get("offlineEvidence"));
		this.evidenceSha256 = evidenceSha256;
	}

	static WorldBuilderTargetCapability read(WorldBuilderReadOnlyTarget target)
		throws WorldBuilderContractException {
		WorldBuilderReadOnlyTarget.FileState state =
			target.requiredState("target-capability", RELATIVE_PATH);
		Map<String,Object> root = target.readObject(RELATIVE_PATH);
		try {
			WorldBuilderAdaptiveContracts.validateParsed(
				WorldBuilderAdaptiveContracts.Kind.TARGET_CAPABILITY, root);
		} catch (WorldBuilderContractException invalid) {
			throw new WorldBuilderContractException(invalid.code(), "discover-target", "", "",
				RELATIVE_PATH, "target capability descriptor",
				"An exact world-builder-target-capability schema version 1 document.",
				invalid.getMessage(), false,
				"Target capability descriptor is invalid: " + invalid.getMessage(),
				"Correct the descriptor without weakening the selected compiled adapter.", invalid);
		}
		return new WorldBuilderTargetCapability(root, state.sha256);
	}

	Map<String,Object> reference() {
		Map<String,Object> value = new LinkedHashMap<String,Object>();
		value.put("resolved", Boolean.TRUE);
		value.put("adapterId", adapterId);
		value.put("capabilityId", capabilityId);
		value.put("evidenceRelativePath", RELATIVE_PATH);
		value.put("evidenceSha256", evidenceSha256);
		return value;
	}

	private static Map<String,Object> object(Map<String,Object> value, String key) {
		@SuppressWarnings("unchecked") Map<String,Object> result =
			(Map<String,Object>)value.get(key);
		return result;
	}

	private static String string(Map<String,Object> value, String key) {
		return (String)value.get(key);
	}

	private static boolean bool(Map<String,Object> value, String key) {
		return ((Boolean)value.get(key)).booleanValue();
	}

	private static List<String> strings(Object raw) {
		List<?> values = (List<?>)raw;
		List<String> result = new ArrayList<String>(values.size());
		for (Object value : values) result.add((String)value);
		return Collections.unmodifiableList(result);
	}

	private static List<Integer> integers(Object raw) {
		List<?> values = (List<?>)raw;
		List<Integer> result = new ArrayList<Integer>(values.size());
		for (Object value : values) result.add(
			Integer.valueOf(((Long)value).intValue()));
		return Collections.unmodifiableList(result);
	}
}
