package com.openrsc.worldbuilder;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Definition, asset, and runtime evidence common to descriptor-backed adapters. */
final class WorldBuilderCompatibilityEvidence {
	final DefinitionCatalog definitions;
	final List<WorldBuilderReadOnlyTarget.FileState> files;
	final List<WorldBuilderAdapterInspection.Check> checks;

	private WorldBuilderCompatibilityEvidence(
		DefinitionCatalog definitions,
		List<WorldBuilderReadOnlyTarget.FileState> files,
		List<WorldBuilderAdapterInspection.Check> checks) {
		this.definitions = definitions;
		this.files = Collections.unmodifiableList(
			new ArrayList<WorldBuilderReadOnlyTarget.FileState>(files));
		this.checks = Collections.unmodifiableList(
			new ArrayList<WorldBuilderAdapterInspection.Check>(checks));
	}

	static WorldBuilderCompatibilityEvidence inspect(
		WorldBuilderReadOnlyTarget target,
		WorldBuilderTargetCapability capability,
		WorldBuilderAdaptiveConfiguration configuration)
		throws WorldBuilderContractException {
		List<WorldBuilderReadOnlyTarget.FileState> files =
			new ArrayList<WorldBuilderReadOnlyTarget.FileState>();
		WorldBuilderReadOnlyTarget.FileState serverDefinitions = target.requiredState(
			"server-definition-catalog", configuration.serverDefinitionCatalogRelativePath);
		WorldBuilderReadOnlyTarget.FileState clientDefinitions = target.requiredState(
			"client-definition-catalog", configuration.clientDefinitionCatalogRelativePath);
		if (!serverDefinitions.sha256.equals(clientDefinitions.sha256)
			|| serverDefinitions.size != clientDefinitions.size) {
			throw problem(WorldBuilderErrorCodes.DEFINITION_MISMATCH,
				configuration.serverDefinitionCatalogRelativePath,
				"Server and client definition catalogs are not byte-identical.",
				"Install one matching catalog on the server and client before discovery.");
		}
		if (!capability.definitionCatalogSha256.equals(serverDefinitions.sha256)) {
			throw problem(WorldBuilderErrorCodes.DEFINITION_MISMATCH,
				configuration.serverDefinitionCatalogRelativePath,
				"Definition catalog bytes do not match the capability hash.",
				"Regenerate the truthful capability descriptor for the active catalog.");
		}
		DefinitionCatalog catalog = DefinitionCatalog.read(
			target, configuration.serverDefinitionCatalogRelativePath);
		if (!capability.definitionCatalogId.equals(catalog.catalogId)) {
			throw problem(WorldBuilderErrorCodes.DEFINITION_MISMATCH,
				configuration.serverDefinitionCatalogRelativePath,
				"Definition catalog identity does not match the capability descriptor.",
				"Use one catalog identity across capability, server, and client evidence.");
		}
		files.add(serverDefinitions);
		files.add(clientDefinitions);

		for (WorldBuilderAdaptiveConfiguration.AssetPair asset : configuration.assets) {
			WorldBuilderReadOnlyTarget.FileState server = target.requiredState(
				"server-asset." + asset.role, asset.serverRelativePath);
			WorldBuilderReadOnlyTarget.FileState client = target.requiredState(
				"client-asset." + asset.role, asset.clientRelativePath);
			if (!server.sha256.equals(client.sha256) || server.size != client.size) {
				throw problem(WorldBuilderErrorCodes.CAPABILITY_MISMATCH,
					asset.serverRelativePath,
					"Server and client asset evidence differs for role " + asset.role + ".",
					"Install byte-identical active assets for this role and retry.");
			}
			files.add(server);
			files.add(client);
		}

		WorldBuilderReadOnlyTarget.FileState serverRuntime = target.requiredState(
			"server-runtime", configuration.serverRuntimeRelativePath);
		WorldBuilderReadOnlyTarget.FileState clientRuntime = target.requiredState(
			"client-runtime", configuration.clientRuntimeRelativePath);
		RuntimeEvidence server = RuntimeEvidence.read(
			target, configuration.serverRuntimeRelativePath, "server");
		RuntimeEvidence client = RuntimeEvidence.read(
			target, configuration.clientRuntimeRelativePath, "client");
		server.requireCapability(capability, true,
			configuration.serverRuntimeRelativePath);
		client.requireCapability(capability, false,
			configuration.clientRuntimeRelativePath);
		if (!server.protocolId.equals(client.protocolId)
			|| !server.mapFormatId.equals(client.mapFormatId)
			|| !server.packageSchemaId.equals(client.packageSchemaId)
			|| !server.encodingVersions.equals(client.encodingVersions)
			|| !server.definitionCatalogId.equals(client.definitionCatalogId)
			|| !server.definitionCatalogSha256.equals(client.definitionCatalogSha256)
			|| server.editExistingLevels != client.editExistingLevels
			|| server.createLevels != client.createLevels
			|| !server.placementFamilies.equals(client.placementFamilies)) {
			throw problem(WorldBuilderErrorCodes.CAPABILITY_MISMATCH,
				configuration.serverRuntimeRelativePath,
				"Server and client runtime evidence disagrees on map, protocol, definitions, or authoring.",
				"Install matching compatible server/client runtime builds and retry.");
		}
		files.add(serverRuntime);
		files.add(clientRuntime);

		List<WorldBuilderAdapterInspection.Check> checks =
			new ArrayList<WorldBuilderAdapterInspection.Check>();
		checks.add(new WorldBuilderAdapterInspection.Check(
			"asset-agreement", "passed",
			"Every configured server/client asset pair is byte-identical.",
			configuration.assets.size() + " bounded asset pair(s) agree."));
		checks.add(new WorldBuilderAdapterInspection.Check(
			"definition-agreement", "passed",
			"Server, client, runtime, and capability use one exact definition catalog.",
			catalog.catalogId + " at " + serverDefinitions.sha256 + "."));
		checks.add(new WorldBuilderAdapterInspection.Check(
			"runtime-agreement", "passed",
			"Server/client builds declare matching protocol, loader format, definitions, and authoring.",
			server.buildId + " / " + client.buildId + " using " + server.protocolId + "."));
		return new WorldBuilderCompatibilityEvidence(catalog, files, checks);
	}

	private static WorldBuilderContractException problem(
		String code, String path, String message, String nextStep) {
		return WorldBuilderReadOnlyTarget.problem(code, path, message, nextStep);
	}

	static final class DefinitionCatalog {
		final String catalogId;
		final Set<Integer> tiles;
		final Set<Integer> boundaries;
		final Set<Integer> scenery;
		final Set<Integer> npcs;
		final Set<Integer> groundItems;

		private DefinitionCatalog(String catalogId, Set<Integer> tiles,
			Set<Integer> boundaries, Set<Integer> scenery, Set<Integer> npcs,
			Set<Integer> groundItems) {
			this.catalogId = catalogId;
			this.tiles = tiles;
			this.boundaries = boundaries;
			this.scenery = scenery;
			this.npcs = npcs;
			this.groundItems = groundItems;
		}

		static DefinitionCatalog read(WorldBuilderReadOnlyTarget target, String path)
			throws WorldBuilderContractException {
			Map<String,Object> root = target.readObject(path);
			exact(root, path, "schemaVersion", "manifestType", "catalogId", "tiles",
				"boundaries", "scenery", "npcs", "groundItems");
			if (integer(root, "schemaVersion", path) != 1L
				|| !"world-builder-definition-catalog".equals(
					string(root, "manifestType", path))) {
				throw problem(WorldBuilderErrorCodes.UNSUPPORTED_CONTRACT_VERSION, path,
					"Definition catalog identity is unsupported.",
					"Provide world-builder-definition-catalog schema version 1.");
			}
			String catalogId = identifier(root, "catalogId", path);
			return new DefinitionCatalog(catalogId,
				ids(root.get("tiles"), path, "tiles"),
				ids(root.get("boundaries"), path, "boundaries"),
				ids(root.get("scenery"), path, "scenery"),
				ids(root.get("npcs"), path, "npcs"),
				ids(root.get("groundItems"), path, "groundItems"));
		}

		void require(String family, int id, String path)
			throws WorldBuilderContractException {
			Set<Integer> values;
			if ("boundary".equals(family)) values = boundaries;
			else if ("scenery".equals(family)) values = scenery;
			else if ("npc".equals(family)) values = npcs;
			else if ("ground-item".equals(family)) values = groundItems;
			else throw new AssertionError(family);
			if (!values.contains(Integer.valueOf(id))) {
				throw problem(WorldBuilderErrorCodes.DEFINITION_MISMATCH, path,
					"Placement references undefined " + family + " ID " + id + ".",
					"Add the exact definition to the agreed catalog or correct the placement.");
			}
		}
	}

	private static final class RuntimeEvidence {
		final String side;
		final String buildId;
		final String loaderId;
		final String protocolId;
		final String definitionCatalogId;
		final String definitionCatalogSha256;
		final String mapFormatId;
		final String packageSchemaId;
		final List<Integer> encodingVersions;
		final boolean editExistingLevels;
		final boolean createLevels;
		final List<String> placementFamilies;

		private RuntimeEvidence(Map<String,Object> root, String path)
			throws WorldBuilderContractException {
			side = (String)root.get("side");
			buildId = (String)root.get("buildId");
			loaderId = (String)root.get("loaderId");
			protocolId = (String)root.get("protocolId");
			definitionCatalogId = (String)root.get("definitionCatalogId");
			definitionCatalogSha256 = (String)root.get("definitionCatalogSha256");
			mapFormatId = (String)root.get("mapFormatId");
			packageSchemaId = (String)root.get("packageSchemaId");
			encodingVersions = integerList(root.get("encodingVersions"), path,
				"encodingVersions", 1, 32);
			Map<String,Object> authoring = castObject(root.get("authoring"));
			editExistingLevels = ((Boolean)authoring.get("editExistingLevels")).booleanValue();
			createLevels = ((Boolean)authoring.get("createLevels")).booleanValue();
			placementFamilies = stringList(authoring.get("placementFamilies"), path,
				"placementFamilies", 0, 4);
		}

		static RuntimeEvidence read(
			WorldBuilderReadOnlyTarget target, String path, String expectedSide)
			throws WorldBuilderContractException {
			Map<String,Object> root = target.readObject(path);
			exact(root, path, "schemaVersion", "manifestType", "side", "buildId",
				"loaderId", "protocolId", "definitionCatalogId",
				"definitionCatalogSha256", "mapFormatId", "packageSchemaId",
				"encodingVersions", "authoring");
			if (integer(root, "schemaVersion", path) != 1L
				|| !"world-builder-runtime-evidence".equals(
					string(root, "manifestType", path))) {
				throw problem(WorldBuilderErrorCodes.UNSUPPORTED_CONTRACT_VERSION, path,
					"Runtime evidence identity is unsupported.",
					"Provide world-builder-runtime-evidence schema version 1.");
			}
			String side = enumeration(root, "side", path, "client", "server");
			if (!expectedSide.equals(side)) {
				throw problem(WorldBuilderErrorCodes.CAPABILITY_MISMATCH, path,
					"Runtime evidence is assigned to the wrong side: " + side,
					"Point each server/client role at matching side evidence.");
			}
			for (String key : Arrays.asList("buildId", "loaderId", "protocolId",
				"definitionCatalogId", "mapFormatId", "packageSchemaId")) {
				identifier(root, key, path);
			}
			String hash = string(root, "definitionCatalogSha256", path);
			if (!WorldBuilderBoundedInventory.isHash(hash)) {
				throw problem(WorldBuilderErrorCodes.CONTRACT_VALUE_INVALID, path,
					"Runtime definition catalog hash is invalid.",
					"Use the exact lowercase SHA-256 of the active catalog.");
			}
			integerList(root.get("encodingVersions"), path, "encodingVersions", 1, 32);
			Map<String,Object> authoring = object(root.get("authoring"), path, "authoring");
			exact(authoring, path, "editExistingLevels", "createLevels", "placementFamilies");
			bool(authoring, "editExistingLevels", path);
			bool(authoring, "createLevels", path);
			List<String> families = stringList(
				authoring.get("placementFamilies"), path, "placementFamilies", 0, 4);
			for (String family : families) {
				if (!Arrays.asList("boundary", "ground-item", "npc", "scenery").contains(family)) {
					throw problem(WorldBuilderErrorCodes.CONTRACT_VALUE_INVALID, path,
						"Runtime evidence has an unsupported placement family: " + family,
						"Use only boundary, ground-item, npc, and scenery.");
				}
			}
			return new RuntimeEvidence(root, path);
		}

		void requireCapability(
			WorldBuilderTargetCapability capability, boolean server, String path)
			throws WorldBuilderContractException {
			String expectedBuild = server ? capability.serverBuildId : capability.clientBuildId;
			String expectedLoader = server ? capability.serverLoaderId : capability.clientLoaderId;
			if (!expectedBuild.equals(buildId) || !expectedLoader.equals(loaderId)
				|| !capability.clientProtocolId.equals(protocolId)
				|| !capability.definitionCatalogId.equals(definitionCatalogId)
				|| !capability.definitionCatalogSha256.equals(definitionCatalogSha256)
				|| !capability.mapFormatId.equals(mapFormatId)
				|| !capability.packageSchemaId.equals(packageSchemaId)
				|| !capability.encodingVersions.equals(encodingVersions)
				|| capability.editExistingLevels != editExistingLevels
				|| capability.createLevels != createLevels
				|| !capability.placementFamilies.equals(placementFamilies)) {
				throw problem(WorldBuilderErrorCodes.CAPABILITY_MISMATCH, path,
					"Runtime evidence does not independently confirm the capability descriptor.",
					"Regenerate truthful matching runtime and capability evidence.");
			}
		}
	}

	private static Set<Integer> ids(Object raw, String path, String label)
		throws WorldBuilderContractException {
		List<Integer> values = integerList(raw, path, label, 0, 65536);
		return Collections.unmodifiableSet(new HashSet<Integer>(values));
	}

	private static List<Integer> integerList(
		Object raw, String path, String label, int minimum, int maximum)
		throws WorldBuilderContractException {
		List<?> values = array(raw, path, label, minimum, maximum);
		List<Integer> result = new ArrayList<Integer>(values.size());
		long previous = -1L;
		for (Object rawValue : values) {
			if (!(rawValue instanceof Long)) {
				throw problem(WorldBuilderErrorCodes.CONTRACT_VALUE_INVALID, path,
					"Evidence list is not integer-valued: " + label,
					"Use sorted unique nonnegative integers.");
			}
			long value = ((Long)rawValue).longValue();
			if (value < 0L || value > Integer.MAX_VALUE || value <= previous) {
				throw problem(WorldBuilderErrorCodes.CONTRACT_VALUE_INVALID, path,
					"Evidence integer list is duplicated, unsorted, or out of range: " + label,
					"Use sorted unique nonnegative 32-bit integers.");
			}
			previous = value;
			result.add(Integer.valueOf((int)value));
		}
		return Collections.unmodifiableList(result);
	}

	private static List<String> stringList(
		Object raw, String path, String label, int minimum, int maximum)
		throws WorldBuilderContractException {
		List<?> values = array(raw, path, label, minimum, maximum);
		List<String> result = new ArrayList<String>(values.size());
		String previous = null;
		for (Object value : values) {
			if (!(value instanceof String) || previous != null
				&& previous.compareTo((String)value) >= 0) {
				throw problem(WorldBuilderErrorCodes.CONTRACT_VALUE_INVALID, path,
					"Evidence string list is invalid or not canonical: " + label,
					"Use a sorted unique string list.");
			}
			previous = (String)value;
			result.add((String)value);
		}
		return Collections.unmodifiableList(result);
	}

	private static Map<String,Object> object(Object raw, String path, String label)
		throws WorldBuilderContractException {
		if (!(raw instanceof Map)) {
			throw problem(WorldBuilderErrorCodes.CONTRACT_VALUE_INVALID, path,
				"Evidence field is not an object: " + label,
				"Correct the exact evidence document.");
		}
		return castObject(raw);
	}

	@SuppressWarnings("unchecked")
	private static Map<String,Object> castObject(Object raw) {
		return (Map<String,Object>)raw;
	}

	private static List<?> array(Object raw, String path, String label, int minimum, int maximum)
		throws WorldBuilderContractException {
		if (!(raw instanceof List) || ((List<?>)raw).size() < minimum
			|| ((List<?>)raw).size() > maximum) {
			throw problem(WorldBuilderErrorCodes.CONTRACT_LIMIT_EXCEEDED, path,
				"Evidence array is missing or outside its limit: " + label,
				"Use the bounded version 1 evidence format.");
		}
		return (List<?>)raw;
	}

	private static void exact(Map<String,Object> value, String path, String... keys)
		throws WorldBuilderContractException {
		Set<String> expected = new HashSet<String>(Arrays.asList(keys));
		if (value.size() != expected.size() || !value.keySet().equals(expected)) {
			throw problem(WorldBuilderErrorCodes.CONTRACT_KEYS_INVALID, path,
				"Evidence contains missing or unexpected fields.",
				"Use only the exact version 1 evidence keys.");
		}
	}

	private static String string(Map<String,Object> value, String key, String path)
		throws WorldBuilderContractException {
		Object raw = value.get(key);
		if (!(raw instanceof String)) {
			throw problem(WorldBuilderErrorCodes.CONTRACT_VALUE_INVALID, path,
				"Evidence field is not a string: " + key,
				"Correct the field type and retry.");
		}
		return (String)raw;
	}

	private static String identifier(Map<String,Object> value, String key, String path)
		throws WorldBuilderContractException {
		String result = string(value, key, path);
		if (!result.matches("[A-Za-z0-9][A-Za-z0-9._:+-]{0,127}")) {
			throw problem(WorldBuilderErrorCodes.CONTRACT_VALUE_INVALID, path,
				"Evidence identifier is invalid: " + key,
				"Use a portable 1..128 character identifier.");
		}
		return result;
	}

	private static long integer(Map<String,Object> value, String key, String path)
		throws WorldBuilderContractException {
		Object raw = value.get(key);
		if (!(raw instanceof Long)) {
			throw problem(WorldBuilderErrorCodes.CONTRACT_VALUE_INVALID, path,
				"Evidence field is not an integer: " + key,
				"Correct the field type and retry.");
		}
		return ((Long)raw).longValue();
	}

	private static boolean bool(Map<String,Object> value, String key, String path)
		throws WorldBuilderContractException {
		Object raw = value.get(key);
		if (!(raw instanceof Boolean)) {
			throw problem(WorldBuilderErrorCodes.CONTRACT_VALUE_INVALID, path,
				"Evidence field is not boolean: " + key,
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
				"Evidence enum is unsupported: " + key + "=" + result,
				"Use a value supported by the version 1 evidence contract.");
		}
		return result;
	}
}
