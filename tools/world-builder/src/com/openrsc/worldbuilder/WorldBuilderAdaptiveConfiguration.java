package com.openrsc.worldbuilder;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Strict adapter-owned configuration evidence selected by capability roles. */
final class WorldBuilderAdaptiveConfiguration {
	static final String MANIFEST_TYPE = "world-builder-map-configuration";
	static final long SCHEMA_VERSION = 1L;

	final String configurationId;
	final boolean active;
	final String representation;
	final String serverMapRelativePath;
	final String clientMapRelativePath;
	final String serverRuntimeRelativePath;
	final String clientRuntimeRelativePath;
	final String serverDefinitionCatalogRelativePath;
	final String clientDefinitionCatalogRelativePath;
	final List<AssetPair> assets;
	final List<PlacementSource> placements;
	final String relativePath;
	final String sha256;

	private WorldBuilderAdaptiveConfiguration(
		String configurationId,
		boolean active,
		String representation,
		String serverMapRelativePath,
		String clientMapRelativePath,
		String serverRuntimeRelativePath,
		String clientRuntimeRelativePath,
		String serverDefinitionCatalogRelativePath,
		String clientDefinitionCatalogRelativePath,
		List<AssetPair> assets,
		List<PlacementSource> placements,
		String relativePath,
		String sha256) {
		this.configurationId = configurationId;
		this.active = active;
		this.representation = representation;
		this.serverMapRelativePath = serverMapRelativePath;
		this.clientMapRelativePath = clientMapRelativePath;
		this.serverRuntimeRelativePath = serverRuntimeRelativePath;
		this.clientRuntimeRelativePath = clientRuntimeRelativePath;
		this.serverDefinitionCatalogRelativePath = serverDefinitionCatalogRelativePath;
		this.clientDefinitionCatalogRelativePath = clientDefinitionCatalogRelativePath;
		this.assets = Collections.unmodifiableList(new ArrayList<AssetPair>(assets));
		this.placements = Collections.unmodifiableList(
			new ArrayList<PlacementSource>(placements));
		this.relativePath = relativePath;
		this.sha256 = sha256;
	}

	static Selection select(
		WorldBuilderReadOnlyTarget target,
		WorldBuilderTargetCapability capability,
		String requestedRole) throws WorldBuilderContractException {
		List<WorldBuilderAdaptiveConfiguration> configurations =
			new ArrayList<WorldBuilderAdaptiveConfiguration>();
		for (String role : capability.configurationRoles) {
			String relative = pathForRole(role);
			WorldBuilderReadOnlyTarget.FileState state =
				target.requiredState("configuration." + role, relative);
			WorldBuilderAdaptiveConfiguration configuration =
				read(target, relative, state.sha256);
			if (!role.equals(configuration.configurationId)) {
				throw problem(WorldBuilderErrorCodes.CAPABILITY_MISMATCH, relative,
					"Capability role " + role + " resolves to configuration identity "
						+ configuration.configurationId + ".",
					"Make each descriptor role match its exact configurationId.");
			}
			configurations.add(configuration);
		}

		WorldBuilderAdaptiveConfiguration selected = null;
		if (requestedRole != null && !requestedRole.isEmpty()) {
			for (WorldBuilderAdaptiveConfiguration configuration : configurations) {
				if (requestedRole.equals(configuration.configurationId)) {
					selected = configuration;
					break;
				}
			}
			if (selected == null || !selected.active) {
				throw selectionProblem(WorldBuilderErrorCodes.AMBIGUOUS_CONFIGURATION,
					WorldBuilderTargetCapability.RELATIVE_PATH,
					"The requested configuration role is absent or not active: " + requestedRole,
					"Choose one active role listed in the compatibility report.", configurations);
			}
		} else {
			List<WorldBuilderAdaptiveConfiguration> activeConfigurations =
				new ArrayList<WorldBuilderAdaptiveConfiguration>();
			for (WorldBuilderAdaptiveConfiguration configuration : configurations) {
				if (configuration.active) activeConfigurations.add(configuration);
			}
			if (activeConfigurations.size() > 1) {
				throw selectionProblem(WorldBuilderErrorCodes.AMBIGUOUS_CONFIGURATION,
					WorldBuilderTargetCapability.RELATIVE_PATH,
					"More than one declared configuration is active: "
						+ configurationIds(activeConfigurations),
					"Select one active configuration explicitly; discovery will not guess.",
					activeConfigurations);
			}
			selected = activeConfigurations.isEmpty() ? null : activeConfigurations.get(0);
			if (selected == null) {
				throw selectionProblem(WorldBuilderErrorCodes.MALFORMED_SERVER,
					WorldBuilderTargetCapability.RELATIVE_PATH,
					"The descriptor has no active configuration.",
					"Mark exactly one valid configuration active or choose one explicitly.",
					configurations);
			}
		}
		return new Selection(configurations, selected);
	}

	private static List<String> configurationIds(
		List<WorldBuilderAdaptiveConfiguration> configurations) {
		List<String> result = new ArrayList<String>();
		for (WorldBuilderAdaptiveConfiguration configuration : configurations) {
			result.add(configuration.configurationId);
		}
		return result;
	}

	static WorldBuilderAdaptiveConfiguration read(
		WorldBuilderReadOnlyTarget target, String relative, String sha256)
		throws WorldBuilderContractException {
		Map<String,Object> root = target.readObject(relative);
		exact(root, relative, "schemaVersion", "manifestType", "configurationId",
			"active", "representation", "serverMapRelativePath", "clientMapRelativePath",
			"serverRuntimeRelativePath", "clientRuntimeRelativePath",
			"serverDefinitionCatalogRelativePath", "clientDefinitionCatalogRelativePath",
			"assets", "placements");
		if (integer(root, "schemaVersion", relative) != SCHEMA_VERSION
			|| !MANIFEST_TYPE.equals(string(root, "manifestType", relative))) {
			throw problem(WorldBuilderErrorCodes.UNSUPPORTED_CONTRACT_VERSION, relative,
				"Map configuration identity is not world-builder-map-configuration version 1.",
				"Use the exact version 1 adapter configuration contract.");
		}
		String id = identifier(root, "configurationId", relative);
		if (!id.matches("[a-z0-9][a-z0-9-]{0,63}")) {
			throw problem(WorldBuilderErrorCodes.CONTRACT_VALUE_INVALID, relative,
				"Configuration identity cannot map to a bounded adapter path: " + id,
				"Use a lowercase letter/digit/hyphen configuration identity.");
		}
		boolean active = bool(root, "active", relative);
		String representation = enumeration(root, "representation", relative,
			"layered", "packed");
		String serverMap = serverPath(root, "serverMapRelativePath", relative);
		String clientMap = clientPath(root, "clientMapRelativePath", relative);
		String serverRuntime = serverPath(root, "serverRuntimeRelativePath", relative);
		String clientRuntime = clientPath(root, "clientRuntimeRelativePath", relative);
		String serverDefinitions = serverPath(
			root, "serverDefinitionCatalogRelativePath", relative);
		String clientDefinitions = clientPath(
			root, "clientDefinitionCatalogRelativePath", relative);

		List<?> rawAssets = array(root.get("assets"), relative, "assets", 1, 64);
		List<AssetPair> assets = new ArrayList<AssetPair>(rawAssets.size());
		String previousAsset = null;
		for (Object raw : rawAssets) {
			Map<String,Object> value = object(raw, relative, "asset");
			exact(value, relative, "role", "serverRelativePath", "clientRelativePath");
			String role = identifier(value, "role", relative);
			if (previousAsset != null && previousAsset.compareTo(role) >= 0) {
				throw problem(WorldBuilderErrorCodes.CONTRACT_VALUE_INVALID, relative,
					"Asset roles are duplicated or not canonically ordered.",
					"Sort unique asset records by role.");
			}
			previousAsset = role;
			assets.add(new AssetPair(role,
				serverPath(value, "serverRelativePath", relative),
				clientPath(value, "clientRelativePath", relative)));
		}

		List<?> rawPlacements = array(
			root.get("placements"), relative, "placements", 0, 256);
		List<PlacementSource> placements =
			new ArrayList<PlacementSource>(rawPlacements.size());
		Set<String> placementRoles = new HashSet<String>();
		for (int index = 0; index < rawPlacements.size(); index++) {
			Map<String,Object> value = object(rawPlacements.get(index), relative, "placement");
			exact(value, relative, "role", "family", "kind", "compositionOrder",
				"encoding", "relativePath");
			String role = identifier(value, "role", relative);
			String family = enumeration(value, "family", relative,
				"boundary", "ground-item", "npc", "scenery");
			String kind = enumeration(value, "kind", relative,
				"base", "overlay", "removal");
			long order = integer(value, "compositionOrder", relative);
			if (order != index || !placementRoles.add(role)) {
				throw problem(WorldBuilderErrorCodes.CONTRACT_VALUE_INVALID, relative,
					"Placement composition order is duplicated, noncontiguous, or out of order.",
					"List unique placement roles in exact compositionOrder 0..n-1.");
			}
			String encoding = identifier(value, "encoding", relative);
			placements.add(new PlacementSource(role, family, kind, (int)order,
				encoding, serverPath(value, "relativePath", relative)));
		}
		if ("layered".equals(representation) && !placements.isEmpty()) {
			throw problem(WorldBuilderErrorCodes.CONTRACT_VALUE_INVALID, relative,
				"A layered configuration must keep placements inside its declared package.",
				"Remove external placement sources or select the packed adapter.");
		}

		Set<String> paths = new HashSet<String>();
		for (String path : Arrays.asList(serverMap, clientMap, serverRuntime, clientRuntime,
			serverDefinitions, clientDefinitions)) addPath(paths, path, relative);
		for (AssetPair asset : assets) {
			addPath(paths, asset.serverRelativePath, relative);
			addPath(paths, asset.clientRelativePath, relative);
		}
		for (PlacementSource placement : placements) {
			addPath(paths, placement.relativePath, relative);
		}
		return new WorldBuilderAdaptiveConfiguration(id, active, representation,
			serverMap, clientMap, serverRuntime, clientRuntime,
			serverDefinitions, clientDefinitions, assets, placements, relative, sha256);
	}

	static String pathForRole(String role) throws WorldBuilderContractException {
		if (role == null || !role.matches("[a-z0-9][a-z0-9-]{0,63}")) {
			throw problem(WorldBuilderErrorCodes.CAPABILITY_MISMATCH,
				WorldBuilderTargetCapability.RELATIVE_PATH,
				"Capability configuration role cannot map to a bounded adapter path: " + role,
				"Use lowercase letter/digit/hyphen role names up to 64 characters.");
		}
		return "server/world-builder-configs/" + role + ".json";
	}

	private static void addPath(Set<String> paths, String path, String config)
		throws WorldBuilderContractException {
		String key;
		try {
			key = WorldBuilderPortablePath.collisionKey(path, "discover-target");
		} catch (WorldBuilderContractException unsafe) {
			throw problem(WorldBuilderErrorCodes.UNSAFE_PATH, config,
				"Configuration contains an unsafe target path: " + path,
				"Use unique normalized portable paths under the compiled side roots.");
		}
		if (!paths.add(key)) {
			throw problem(WorldBuilderErrorCodes.INVENTORY_DUPLICATE, config,
				"Configuration repeats or case-collides a target path: " + path,
				"Give every target evidence path one portable spelling and role.");
		}
	}

	private static String serverPath(Map<String,Object> value, String key, String config)
		throws WorldBuilderContractException {
		String result = relative(value, key, config);
		if (!result.startsWith("server/")) {
			throw problem(WorldBuilderErrorCodes.UNSAFE_PATH, config,
				"Server evidence path is outside the compiled server root: " + result,
				"Keep server evidence below server/.");
		}
		return result;
	}

	private static String clientPath(Map<String,Object> value, String key, String config)
		throws WorldBuilderContractException {
		String result = relative(value, key, config);
		if (!(result.startsWith("Client_Base/") || result.startsWith("client/"))) {
			throw problem(WorldBuilderErrorCodes.UNSAFE_PATH, config,
				"Client evidence path is outside a compiled client root: " + result,
				"Keep client evidence below Client_Base/ or client/.");
		}
		return result;
	}

	private static String relative(Map<String,Object> value, String key, String config)
		throws WorldBuilderContractException {
		String result = string(value, key, config);
		try {
			return WorldBuilderPortablePath.require(result, "discover-target");
		} catch (WorldBuilderContractException unsafe) {
			throw problem(WorldBuilderErrorCodes.UNSAFE_PATH, config,
				"Configuration field " + key + " contains an unsafe path: " + result,
				"Use a normalized forward-slash path contained by the target root.");
		}
	}

	private static Map<String,Object> object(Object raw, String path, String label)
		throws WorldBuilderContractException {
		if (!(raw instanceof Map)) {
			throw problem(WorldBuilderErrorCodes.CONTRACT_VALUE_INVALID, path,
				"Map configuration field is not an object: " + label,
				"Correct the exact version 1 configuration structure.");
		}
		@SuppressWarnings("unchecked") Map<String,Object> value = (Map<String,Object>)raw;
		return value;
	}

	private static List<?> array(Object raw, String path, String label, int minimum, int maximum)
		throws WorldBuilderContractException {
		if (!(raw instanceof List) || ((List<?>)raw).size() < minimum
			|| ((List<?>)raw).size() > maximum) {
			throw problem(WorldBuilderErrorCodes.CONTRACT_LIMIT_EXCEEDED, path,
				"Map configuration array is missing or outside its limit: " + label,
				"Use a bounded array accepted by the selected adapter.");
		}
		return (List<?>)raw;
	}

	private static void exact(Map<String,Object> value, String path, String... keys)
		throws WorldBuilderContractException {
		Set<String> expected = new HashSet<String>(Arrays.asList(keys));
		if (value.size() != expected.size() || !value.keySet().equals(expected)) {
			throw problem(WorldBuilderErrorCodes.CONTRACT_KEYS_INVALID, path,
				"Map configuration contains missing or unexpected fields.",
				"Use only the exact version 1 configuration keys.");
		}
	}

	private static String string(Map<String,Object> value, String key, String path)
		throws WorldBuilderContractException {
		Object raw = value.get(key);
		if (!(raw instanceof String)) {
			throw problem(WorldBuilderErrorCodes.CONTRACT_VALUE_INVALID, path,
				"Map configuration field is not a string: " + key,
				"Correct the field type and retry.");
		}
		return (String)raw;
	}

	private static String identifier(Map<String,Object> value, String key, String path)
		throws WorldBuilderContractException {
		String result = string(value, key, path);
		if (!result.matches("[A-Za-z0-9][A-Za-z0-9._:+-]{0,127}")) {
			throw problem(WorldBuilderErrorCodes.CONTRACT_VALUE_INVALID, path,
				"Map configuration identifier is invalid: " + key,
				"Use a portable 1..128 character identifier.");
		}
		return result;
	}

	private static long integer(Map<String,Object> value, String key, String path)
		throws WorldBuilderContractException {
		Object raw = value.get(key);
		if (!(raw instanceof Long)) {
			throw problem(WorldBuilderErrorCodes.CONTRACT_VALUE_INVALID, path,
				"Map configuration field is not an integer: " + key,
				"Correct the field type and retry.");
		}
		return ((Long)raw).longValue();
	}

	private static boolean bool(Map<String,Object> value, String key, String path)
		throws WorldBuilderContractException {
		Object raw = value.get(key);
		if (!(raw instanceof Boolean)) {
			throw problem(WorldBuilderErrorCodes.CONTRACT_VALUE_INVALID, path,
				"Map configuration field is not boolean: " + key,
				"Correct the field type and retry.");
		}
		return ((Boolean)raw).booleanValue();
	}

	private static String enumeration(
		Map<String,Object> value, String key, String path, String... allowed)
		throws WorldBuilderContractException {
		String result = string(value, key, path);
		if (!Arrays.asList(allowed).contains(result)) {
			throw problem(WorldBuilderErrorCodes.CONTRACT_VALUE_INVALID, path,
				"Map configuration enum is unsupported: " + key + "=" + result,
				"Use a value supported by the selected compiled adapter.");
		}
		return result;
	}

	private static WorldBuilderContractException problem(
		String code, String path, String message, String nextStep) {
		return WorldBuilderReadOnlyTarget.problem(code, path, message, nextStep);
	}

	private static SelectionException selectionProblem(
		String code, String path, String message, String nextStep,
		List<WorldBuilderAdaptiveConfiguration> configurations) {
		List<WorldBuilderAdapterInspection.ConfigurationCandidate> candidates =
			new ArrayList<WorldBuilderAdapterInspection.ConfigurationCandidate>();
		for (WorldBuilderAdaptiveConfiguration configuration : configurations) {
			candidates.add(new WorldBuilderAdapterInspection.ConfigurationCandidate(
				configuration.configurationId, configuration.relativePath, configuration.sha256));
		}
		return new SelectionException(code, path, message, nextStep, candidates);
	}

	static final class Selection {
		final List<WorldBuilderAdaptiveConfiguration> configurations;
		final WorldBuilderAdaptiveConfiguration selected;

		Selection(List<WorldBuilderAdaptiveConfiguration> configurations,
			WorldBuilderAdaptiveConfiguration selected) {
			this.configurations = Collections.unmodifiableList(
				new ArrayList<WorldBuilderAdaptiveConfiguration>(configurations));
			this.selected = selected;
		}

		List<WorldBuilderAdapterInspection.ConfigurationCandidate> candidates() {
			List<WorldBuilderAdapterInspection.ConfigurationCandidate> result =
				new ArrayList<WorldBuilderAdapterInspection.ConfigurationCandidate>();
			for (WorldBuilderAdaptiveConfiguration configuration : configurations) {
				result.add(new WorldBuilderAdapterInspection.ConfigurationCandidate(
					configuration.configurationId, configuration.relativePath,
					configuration.sha256));
			}
			return result;
		}

		WorldBuilderAdapterInspection.ConfigurationCandidate selectedCandidate() {
			return new WorldBuilderAdapterInspection.ConfigurationCandidate(
				selected.configurationId, selected.relativePath, selected.sha256);
		}
	}

	static final class SelectionException extends WorldBuilderContractException {
		private static final long serialVersionUID = 1L;
		final List<WorldBuilderAdapterInspection.ConfigurationCandidate> candidates;

		SelectionException(String code, String path, String message, String nextStep,
			List<WorldBuilderAdapterInspection.ConfigurationCandidate> candidates) {
			super(code, "discover-target", "", "", path,
				"read-only target discovery",
				"Exactly one active configuration or one explicit active choice.",
				message, false, message, nextStep, null);
			List<WorldBuilderAdapterInspection.ConfigurationCandidate> sorted =
				new ArrayList<WorldBuilderAdapterInspection.ConfigurationCandidate>(candidates);
			Collections.sort(sorted);
			this.candidates = Collections.unmodifiableList(sorted);
		}
	}

	static final class AssetPair {
		final String role;
		final String serverRelativePath;
		final String clientRelativePath;

		AssetPair(String role, String serverRelativePath, String clientRelativePath) {
			this.role = role;
			this.serverRelativePath = serverRelativePath;
			this.clientRelativePath = clientRelativePath;
		}
	}

	static final class PlacementSource {
		final String role;
		final String family;
		final String kind;
		final int compositionOrder;
		final String encoding;
		final String relativePath;

		PlacementSource(String role, String family, String kind, int compositionOrder,
			String encoding, String relativePath) {
			this.role = role;
			this.family = family;
			this.kind = kind;
			this.compositionOrder = compositionOrder;
			this.encoding = encoding;
			this.relativePath = relativePath;
		}
	}
}
