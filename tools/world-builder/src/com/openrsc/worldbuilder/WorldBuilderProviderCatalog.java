package com.openrsc.worldbuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Read-only adoption boundary for the exact locked runtime-provider catalog. */
final class WorldBuilderProviderCatalog {
	private static final String OPERATION = "resolve-provider-composition";
	private static final String INPUT_ADAPTER_CONTRACT = "world-builder-input-adapter-v1";
	private static final int MAX_CATALOG_DOCUMENTS = 256;

	private WorldBuilderProviderCatalog() {
	}

	static Composition resolve(Path catalogRoot, Path compositionIdentity)
		throws IOException, WorldBuilderContractException {
		WorldBuilderReadOnlyTarget catalog = WorldBuilderReadOnlyTarget.open(catalogRoot);
		Path payloadRootPath = catalog.root.getParent();
		if (payloadRootPath == null) invalid(
			"Provider catalog has no contained payload root.");
		WorldBuilderReadOnlyTarget payload = WorldBuilderReadOnlyTarget.open(payloadRootPath);
		Map<String,Object> composition = readExternal(compositionIdentity,
			"provider-composition-identity");
		validateComposition(composition);

		String platformId = string(composition, "platformReleaseId");
		String variantId = string(composition, "variantId");
		String bundleSpecId = string(composition, "bundleSpecId");
		Map<String,Object> platform = findManifest(catalog, "platform", "platformReleaseId",
			platformId, "current-platform-release-v1", "current-platform-release");
		Map<String,Object> variant = readManifest(catalog, "variants/" + variantId + ".json",
			"current-variant-v1", "current-platform-variant");
		Map<String,Object> bundle = findManifest(catalog, "bundle-specs", "bundleSpecId",
			bundleSpecId, "current-bundle-spec-v1", "current-platform-bundle-spec");

		requireCanonicalHash(platform, string(composition, "platformManifestHash"),
			"platformManifestHash");
		requireCanonicalHash(variant, string(composition, "variantManifestHash"),
			"variantManifestHash");
		requireCanonicalHash(bundle, string(composition, "bundleSpecHash"), "bundleSpecHash");
		if (!platformId.equals(string(variant, "platformReleaseId"))
			|| !platformId.equals(string(bundle, "platformReleaseId"))
			|| !variantId.equals(string(variant, "variantId"))
			|| !variantId.equals(string(bundle, "variantId"))) invalid(
			"Provider platform, variant, and bundle spec do not form one composition.");
		if (!INPUT_ADAPTER_CONTRACT.equals(string(composition, "inputAdapterContractId"))
			|| !INPUT_ADAPTER_CONTRACT.equals(string(bundle, "inputAdapterContractId"))) invalid(
			"Provider composition does not bind the Editor input-adapter contract.");
		Map<String,Object> adapterBoundary = object(platform.get("inputAdapterBoundary"),
			"platform inputAdapterBoundary");
		exact(adapterBoundary, "contractId", "installedInRuntime", "selectionAuthority",
			"unknownCodePolicy");
		if (!INPUT_ADAPTER_CONTRACT.equals(string(adapterBoundary, "contractId"))
			|| bool(adapterBoundary, "installedInRuntime")
			|| !"editor-migration-boundary-only".equals(
				string(adapterBoundary, "selectionAuthority"))
			|| !"refuse-before-mutation".equals(
				string(adapterBoundary, "unknownCodePolicy"))) invalid(
			"Provider platform does not expose the required fail-closed Editor adapter boundary.");
		boolean installable = bool(composition, "installable");
		boolean bundleInstallable = bool(bundle, "installable");
		boolean variantInstallable = bool(variant, "installable");
		String releaseStatus = identifier(variant, "releaseStatus");
		if (!("foundation-contract-only".equals(releaseStatus)
			|| "release-candidate".equals(releaseStatus)
			|| "released".equals(releaseStatus))) invalid(
			"Provider variant has an unsupported release status.");
		if (installable != bundleInstallable || installable != variantInstallable) invalid(
			"Composition, bundle, and variant installability must agree exactly.");
		if (installable && "foundation-contract-only".equals(releaseStatus)) invalid(
			"A foundation-contract-only provider variant cannot be installable.");
		List<String> availableCapabilities = union(
			uniqueIdentifiers(platform.get("mapRuntimeCapabilities"),
				"platform mapRuntimeCapabilities", 0, 256),
			uniqueIdentifiers(variant.get("requiredCapabilities"),
				"variant requiredCapabilities", 0, 256));
		List<String> admittedAdapters = uniqueIdentifiers(
			variant.get("inputAdapterRecommendations"),
			"variant inputAdapterRecommendations", 0, 128);

		List<Map<String,Object>> schemaContracts = objectList(platform.get("schemaContracts"),
			"schemaContracts", 5, 256);
		validateSchemaContracts(catalog, schemaContracts);
		requireHash(canonicalHash(schemaContracts), string(composition, "schemaSetHash"),
			"schemaSetHash");

		List<Map<String,Object>> moduleSet = objectList(composition.get("moduleSet"),
			"moduleSet", 0, 128);
		List<Map<String,Object>> moduleArtifacts = new ArrayList<Map<String,Object>>();
		List<Artifact> artifacts = new ArrayList<Artifact>();
		Set<String> resolvedModuleIds = new HashSet<String>();
		Map<String,Map<String,Object>> resolvedModulesById =
			new LinkedHashMap<String,Map<String,Object>>();
		for (Map<String,Object> record : moduleSet) {
			exact(record, "manifestHash", "moduleId", "moduleVersion", "payloadRootHash");
			String moduleId = identifier(record, "moduleId");
			text(record, "moduleVersion", 1, 256); hash(record, "manifestHash");
			hash(record, "payloadRootHash");
			if (!resolvedModuleIds.add(moduleId)) invalid(
				"Provider module set repeats a module ID.");
			Map<String,Object> module = readManifest(catalog, "modules/" + moduleId + ".json",
				"current-module-v1", "current-platform-module");
			if (!moduleId.equals(string(module, "moduleId"))
				|| !string(record, "moduleVersion").equals(string(module, "moduleVersion"))
				|| !platformId.equals(string(module, "platformReleaseId"))) invalid(
				"Resolved module identity does not match its provider manifest.");
			requireCanonicalHash(module, string(record, "manifestHash"), "module manifestHash");
			resolvedModulesById.put(moduleId, module);
			List<Map<String,Object>> records = inventory(payload, module.get("artifacts"),
				"module artifacts", artifacts);
			requireHash(canonicalHash(records), string(record, "payloadRootHash"),
				"module payloadRootHash");
			moduleArtifacts.addAll(records);
		}
		List<String> resolvedModules = validateModuleClosure(platform, variant, bundle,
			moduleSet, resolvedModulesById);
		requireHash(canonicalHash(moduleSet), string(composition, "moduleSetHash"),
			"moduleSetHash");

		List<Map<String,Object>> expectedInventory = inventory(payload, bundle.get("artifacts"),
			"bundle artifacts", artifacts);
		expectedInventory.addAll(moduleArtifacts);
		Collections.sort(expectedInventory, inventoryOrder());
		validateInventoryOrderAndCollisions(expectedInventory, "resolved provider inventory");
		List<Map<String,Object>> suppliedInventory = resolvedInventory(
			composition.get("bundleInventory"), "bundleInventory");
		if (!WorldBuilderJsonDocuments.canonical(expectedInventory).equals(
			WorldBuilderJsonDocuments.canonical(suppliedInventory))) invalid(
			"Resolved bundle inventory does not equal the provider bundle plus module closure.");
		requireHash(canonicalHash(suppliedInventory),
			string(composition, "bundleInventoryHash"), "bundleInventoryHash");

		Collections.sort(artifacts, new Comparator<Artifact>() {
			@Override public int compare(Artifact left, Artifact right) {
				return left.bundlePath.compareTo(right.bundlePath);
			}
		});
		return new Composition(composition, installable, resolvedModules,
			availableCapabilities, admittedAdapters, artifacts);
	}

	private static List<String> validateModuleClosure(Map<String,Object> platform,
		Map<String,Object> variant, Map<String,Object> bundle,
		List<Map<String,Object>> moduleSet,
		Map<String,Map<String,Object>> modules) throws WorldBuilderContractException {
		List<String> defaults = uniqueIdentifiers(variant.get("defaultModuleIds"),
			"variant defaultModuleIds", 0, 128);
		List<String> bundleModules = uniqueIdentifiers(bundle.get("moduleIds"),
			"bundle moduleIds", 0, 128);
		if (!defaults.equals(bundleModules)) invalid(
			"Bundle module IDs must exactly equal the variant default module set.");
		for (String required : defaults) if (!modules.containsKey(required)) invalid(
			"Resolved composition omits a variant/bundle-required module: " + required);

		Map<String,Set<String>> edges = new LinkedHashMap<String,Set<String>>();
		Map<String,Integer> incoming = new LinkedHashMap<String,Integer>();
		for (String moduleId : modules.keySet()) {
			edges.put(moduleId, new HashSet<String>());
			incoming.put(moduleId, Integer.valueOf(0));
		}
		for (Map.Entry<String,Map<String,Object>> entry : modules.entrySet()) {
			String moduleId = entry.getKey();
			Map<String,Object> module = entry.getValue();
			if (!string(platform, "platformReleaseId").equals(
					string(module, "platformReleaseId"))
				|| !string(platform, "platformApiVersion").equals(
					string(module, "platformApiVersion"))) invalid(
				"Resolved module targets another provider platform/API: " + moduleId);
			Set<String> requirementIds = new HashSet<String>();
			for (Map<String,Object> requirement : objectList(module.get("requires"),
				"module requirements", 0, 128)) {
				exact(requirement, "moduleId", "moduleVersion");
				String requiredId = identifier(requirement, "moduleId");
				String requiredVersion = text(requirement, "moduleVersion", 1, 256);
				if (!requirementIds.add(requiredId)) invalid(
					"Module repeats a dependency: " + moduleId);
				Map<String,Object> required = modules.get(requiredId);
				if (required == null) invalid("Resolved composition omits dependency "
					+ requiredId + " required by " + moduleId);
				if (!requiredVersion.equals(string(required, "moduleVersion"))) invalid(
					"Resolved module dependency version does not match: " + requiredId);
				addEdge(edges, incoming, requiredId, moduleId, "dependency");
			}
			for (String conflict : uniqueIdentifiers(module.get("conflicts"),
				"module conflicts", 0, 128)) if (modules.containsKey(conflict)) invalid(
				"Resolved modules conflict: " + moduleId + " and " + conflict);
			for (String before : uniqueIdentifiers(module.get("loadAfter"),
				"module loadAfter", 0, 128)) {
				if (!modules.containsKey(before)) invalid("Module " + moduleId
					+ " loadAfter names an unselected module: " + before);
				addEdge(edges, incoming, before, moduleId, "loadAfter");
			}
			for (String after : uniqueIdentifiers(module.get("loadBefore"),
				"module loadBefore", 0, 128)) {
				if (!modules.containsKey(after)) invalid("Module " + moduleId
					+ " loadBefore names an unselected module: " + after);
				addEdge(edges, incoming, moduleId, after, "loadBefore");
			}
		}

		List<String> ready = new ArrayList<String>();
		for (Map.Entry<String,Integer> entry : incoming.entrySet()) {
			if (entry.getValue().intValue() == 0) ready.add(entry.getKey());
		}
		Collections.sort(ready);
		List<String> ordered = new ArrayList<String>();
		while (!ready.isEmpty()) {
			String moduleId = ready.remove(0);
			ordered.add(moduleId);
			List<String> afterIds = new ArrayList<String>(edges.get(moduleId));
			Collections.sort(afterIds);
			for (String after : afterIds) {
				int remaining = incoming.get(after).intValue() - 1;
				incoming.put(after, Integer.valueOf(remaining));
				if (remaining == 0) {
					ready.add(after);
					Collections.sort(ready);
				}
			}
		}
		if (ordered.size() != modules.size()) invalid(
			"Resolved module dependency/load ordering contains a cycle.");
		List<String> supplied = new ArrayList<String>();
		for (Map<String,Object> record : moduleSet) supplied.add(string(record, "moduleId"));
		if (!ordered.equals(supplied)) invalid(
			"Resolved module records are not in deterministic provider load order.");
		return ordered;
	}

	private static void addEdge(Map<String,Set<String>> edges,
		Map<String,Integer> incoming, String before, String after, String reason)
		throws WorldBuilderContractException {
		if (before.equals(after)) invalid("Module has a self-order constraint: " + reason);
		if (edges.get(before).add(after)) incoming.put(after,
			Integer.valueOf(incoming.get(after).intValue() + 1));
	}

	private static void validateComposition(Map<String,Object> root)
		throws WorldBuilderContractException {
		exact(root, "schemaId", "manifestType", "platformReleaseId",
			"platformManifestHash", "schemaSetHash", "variantId", "variantManifestHash",
			"moduleSetHash", "bundleInventoryHash", "moduleSet", "bundleInventory",
			"bundleSpecId", "bundleSpecHash", "inputAdapterContractId", "installable");
		if (!"current-composition-identity-v1".equals(identifier(root, "schemaId"))
			|| !"current-platform-composition-identity".equals(
				identifier(root, "manifestType"))) invalid(
			"Resolved provider composition has the wrong schema or manifest type.");
		identifier(root, "platformReleaseId"); hash(root, "platformManifestHash");
		hash(root, "schemaSetHash"); identifier(root, "variantId");
		hash(root, "variantManifestHash"); hash(root, "moduleSetHash");
		hash(root, "bundleInventoryHash"); identifier(root, "bundleSpecId");
		hash(root, "bundleSpecHash"); identifier(root, "inputAdapterContractId");
		bool(root, "installable");
	}

	private static Map<String,Object> findManifest(WorldBuilderReadOnlyTarget catalog,
		String directory, String idField, String wantedId, String schemaId,
		String manifestType) throws IOException, WorldBuilderContractException {
		Path root = catalog.requiredDirectory(directory);
		List<String> matches = new ArrayList<String>();
		try (DirectoryStream<Path> entries = Files.newDirectoryStream(root, "*.json")) {
			for (Path path : entries) {
				if (matches.size() >= MAX_CATALOG_DOCUMENTS) invalid(
					"Provider catalog directory exceeds its bounded manifest limit.");
				String relative = catalog.relative(path);
				Map<String,Object> candidate = readManifest(catalog, relative, schemaId, manifestType);
				if (wantedId.equals(string(candidate, idField))) matches.add(relative);
			}
		}
		if (matches.size() != 1) invalid(
			"Provider catalog does not contain exactly one requested " + idField + ".");
		return readManifest(catalog, matches.get(0), schemaId, manifestType);
	}

	private static Map<String,Object> readManifest(WorldBuilderReadOnlyTarget catalog,
		String relative, String schemaId, String manifestType)
		throws WorldBuilderContractException {
		Map<String,Object> root = catalog.readObject(relative);
		if (!schemaId.equals(identifier(root, "schemaId"))
			|| !manifestType.equals(identifier(root, "manifestType"))) invalid(
			"Provider catalog manifest has an unexpected schema or type: " + relative);
		return root;
	}

	private static Map<String,Object> readExternal(Path path, String label)
		throws IOException, WorldBuilderContractException {
		try {
			return WorldBuilderJsonDocuments.readObject(path);
		} catch (WorldBuilderDiscoveryException malformed) {
			throw new WorldBuilderContractException(WorldBuilderErrorCodes.MALFORMED_JSON,
				OPERATION, "", false, "Provider composition is malformed: " + label,
				"Regenerate it from the exact locked provider catalog.", malformed);
		}
	}

	private static void validateSchemaContracts(WorldBuilderReadOnlyTarget catalog,
		List<Map<String,Object>> records) throws WorldBuilderContractException {
		String previous = null;
		for (Map<String,Object> record : records) {
			exact(record, "schemaId", "relativePath", "sha256");
			String schemaId = identifier(record, "schemaId");
			String path = relative(record, "relativePath");
			String expected = hash(record, "sha256");
			if (previous != null && previous.compareTo(schemaId) >= 0) invalid(
				"Provider schema contracts are not ordered by schema ID.");
			previous = schemaId;
			WorldBuilderReadOnlyTarget.FileState state = catalog.requiredState("provider-schema", path);
			if (!expected.equals(state.sha256)) invalid(
				"Provider schema bytes do not match the platform manifest: " + path);
		}
	}

	private static List<Map<String,Object>> inventory(WorldBuilderReadOnlyTarget payload,
		Object raw, String name, List<Artifact> resolvedArtifacts)
		throws WorldBuilderContractException {
		List<Map<String,Object>> specs = objectList(raw, name, 1,
			WorldBuilderContractLimits.MAX_INVENTORY_ENTRIES);
		List<Map<String,Object>> result = new ArrayList<Map<String,Object>>();
		for (Map<String,Object> spec : specs) {
			exact(spec, "sourcePath", "bundlePath", "role", "destination", "ownership",
				"replacementPolicy", "rollbackPolicy", "provenance");
			String sourcePath = relative(spec, "sourcePath");
			String bundlePath = relative(spec, "bundlePath");
			String destination = relative(spec, "destination");
			String role = identifier(spec, "role");
			String ownership = identifier(spec, "ownership");
			String replacement = identifier(spec, "replacementPolicy");
			String rollback = identifier(spec, "rollbackPolicy");
			String provenance = identifier(spec, "provenance");
			WorldBuilderReadOnlyTarget.FileState state =
				payload.requiredState("provider-artifact", sourcePath);
			Map<String,Object> record = new LinkedHashMap<String,Object>();
			record.put("bundlePath", bundlePath);
			record.put("destination", destination);
			record.put("mode", fileMode(payload.requiredFile(sourcePath), sourcePath));
			record.put("ownership", ownership);
			record.put("provenance", provenance);
			record.put("replacementPolicy", replacement);
			record.put("role", role);
			record.put("rollbackPolicy", rollback);
			record.put("sha256", state.sha256);
			record.put("size", Long.valueOf(state.size));
			record.put("type", "file");
			result.add(record);
			resolvedArtifacts.add(new Artifact(sourcePath, bundlePath,
				payload.requiredFile(sourcePath), record));
		}
		Collections.sort(result, inventoryOrder());
		validateInventoryOrderAndCollisions(result, name);
		return result;
	}

	private static String fileMode(Path path, String relative)
		throws WorldBuilderContractException {
		try {
			Object raw = Files.getAttribute(path, "unix:mode", LinkOption.NOFOLLOW_LINKS);
			if (!(raw instanceof Number)) invalid(
				"Provider artifact has no numeric file mode: " + relative);
			return String.format("%04o", Integer.valueOf(((Number)raw).intValue() & 0777));
		} catch (UnsupportedOperationException unsupported) {
			try {
				Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(
					path, LinkOption.NOFOLLOW_LINKS);
				int mode = 0;
				if (permissions.contains(PosixFilePermission.OWNER_READ)) mode |= 0400;
				if (permissions.contains(PosixFilePermission.OWNER_WRITE)) mode |= 0200;
				if (permissions.contains(PosixFilePermission.OWNER_EXECUTE)) mode |= 0100;
				if (permissions.contains(PosixFilePermission.GROUP_READ)) mode |= 0040;
				if (permissions.contains(PosixFilePermission.GROUP_WRITE)) mode |= 0020;
				if (permissions.contains(PosixFilePermission.GROUP_EXECUTE)) mode |= 0010;
				if (permissions.contains(PosixFilePermission.OTHERS_READ)) mode |= 0004;
				if (permissions.contains(PosixFilePermission.OTHERS_WRITE)) mode |= 0002;
				if (permissions.contains(PosixFilePermission.OTHERS_EXECUTE)) mode |= 0001;
				return String.format("%04o", Integer.valueOf(mode));
			} catch (IOException failure) {
				invalid("Provider artifact mode cannot be verified: " + relative);
				return "";
			}
		} catch (IOException failure) {
			invalid("Provider artifact mode cannot be verified: " + relative);
			return "";
		}
	}

	private static List<Map<String,Object>> resolvedInventory(Object raw, String name)
		throws WorldBuilderContractException {
		List<Map<String,Object>> result = objectList(raw, name, 0,
			WorldBuilderContractLimits.MAX_INVENTORY_ENTRIES);
		for (Map<String,Object> record : result) {
			exact(record, "bundlePath", "destination", "mode", "ownership", "provenance",
				"replacementPolicy", "role", "rollbackPolicy", "sha256", "size", "type");
			relative(record, "bundlePath"); relative(record, "destination");
			String mode = string(record, "mode");
			if (!mode.matches("[0-7]{4}")) invalid("Provider artifact mode is invalid.");
			identifier(record, "ownership");
			text(record, "provenance", 1, 1024); identifier(record, "replacementPolicy");
			identifier(record, "role"); identifier(record, "rollbackPolicy");
			hash(record, "sha256");
			long size = integer(record, "size");
			if (size < 0 || size > WorldBuilderContractLimits.MAX_INVENTORY_FILE_BYTES) invalid(
				"Provider artifact size is outside the Editor's bounded inventory limit.");
			if (!"file".equals(string(record, "type"))) invalid(
				"Provider inventory may contain only regular files.");
		}
		validateInventoryOrderAndCollisions(result, name);
		return result;
	}

	private static void validateInventoryOrderAndCollisions(
		List<Map<String,Object>> inventory, String name) throws WorldBuilderContractException {
		Set<String> paths = new HashSet<String>();
		String previous = null;
		for (Map<String,Object> record : inventory) {
			String path = string(record, "bundlePath");
			if (!paths.add(WorldBuilderPortablePath.collisionKey(path, OPERATION))
				|| previous != null && previous.compareTo(path) >= 0) invalid(
				"Provider inventory repeats, case-collides, or is unordered: " + name);
			previous = path;
		}
	}

	private static Comparator<Map<String,Object>> inventoryOrder() {
		return new Comparator<Map<String,Object>>() {
			@Override public int compare(Map<String,Object> first, Map<String,Object> second) {
				try {
					return string(first, "bundlePath").compareTo(string(second, "bundlePath"));
				} catch (WorldBuilderContractException impossible) {
					throw new IllegalArgumentException(impossible);
				}
			}
		};
	}

	private static List<Map<String,Object>> objectList(Object raw, String name,
		int minimum, int maximum) throws WorldBuilderContractException {
		if (!(raw instanceof List)) invalid("Provider field is not an array: " + name);
		List<?> values = (List<?>)raw;
		if (values.size() < minimum || values.size() > maximum) invalid(
			"Provider array is outside bounds: " + name);
		List<Map<String,Object>> result = new ArrayList<Map<String,Object>>();
		for (Object value : values) {
			if (!(value instanceof Map)) invalid("Provider array contains a non-object: " + name);
			@SuppressWarnings("unchecked") Map<String,Object> object = (Map<String,Object>)value;
			result.add(object);
		}
		return result;
	}

	private static Map<String,Object> object(Object raw, String name)
		throws WorldBuilderContractException {
		if (!(raw instanceof Map)) invalid("Provider field is not an object: " + name);
		@SuppressWarnings("unchecked") Map<String,Object> result = (Map<String,Object>)raw;
		return result;
	}

	private static List<String> uniqueIdentifiers(Object raw, String name,
		int minimum, int maximum)
		throws WorldBuilderContractException {
		if (!(raw instanceof List)) invalid("Provider field is not an array: " + name);
		List<?> values = (List<?>)raw;
		if (values.size() < minimum || values.size() > maximum) invalid(
			"Provider identifier array is outside bounds: " + name);
		List<String> result = new ArrayList<String>();
		Set<String> seen = new HashSet<String>();
		for (Object value : values) {
			String item = WorldBuilderBoundedInventory.identifier(value, OPERATION, name);
			if (!seen.add(item)) invalid("Provider identifier array repeats: " + name);
			result.add(item);
		}
		return result;
	}

	private static List<String> union(List<String> first, List<String> second) {
		Set<String> combined = new HashSet<String>();
		combined.addAll(first);
		combined.addAll(second);
		List<String> result = new ArrayList<String>(combined);
		Collections.sort(result);
		return result;
	}

	private static void requireCanonicalHash(Map<String,Object> root, String expected,
		String field) throws WorldBuilderContractException {
		requireHash(canonicalHash(root), expected, field);
	}

	private static String canonicalHash(Object value) {
		return WorldBuilderHashes.sha256(
			WorldBuilderJsonDocuments.canonical(value).getBytes(StandardCharsets.UTF_8));
	}

	private static void requireHash(String actual, String expected, String field)
		throws WorldBuilderContractException {
		if (!actual.equals(expected)) invalid("Provider composition identity mismatch: " + field);
	}

	private static void exact(Map<String,Object> root, String... keys)
		throws WorldBuilderContractException {
		WorldBuilderBoundedInventory.exactKeys(root, OPERATION, keys);
	}

	private static String string(Map<String,Object> root, String key)
		throws WorldBuilderContractException {
		return WorldBuilderBoundedInventory.string(root.get(key), OPERATION, key);
	}

	private static String identifier(Map<String,Object> root, String key)
		throws WorldBuilderContractException {
		return WorldBuilderBoundedInventory.identifier(root.get(key), OPERATION, key);
	}

	private static String relative(Map<String,Object> root, String key)
		throws WorldBuilderContractException {
		return WorldBuilderPortablePath.require(string(root, key), OPERATION);
	}

	private static String hash(Map<String,Object> root, String key)
		throws WorldBuilderContractException {
		String value = string(root, key);
		if (!WorldBuilderBoundedInventory.isHash(value)) invalid("Provider SHA-256 is invalid: " + key);
		return value;
	}

	private static long integer(Map<String,Object> root, String key)
		throws WorldBuilderContractException {
		return WorldBuilderBoundedInventory.integer(root.get(key), OPERATION, key);
	}

	private static boolean bool(Map<String,Object> root, String key)
		throws WorldBuilderContractException {
		return WorldBuilderBoundedInventory.bool(root.get(key), OPERATION, key);
	}

	private static String text(Map<String,Object> root, String key, int minimum, int maximum)
		throws WorldBuilderContractException {
		String value = string(root, key);
		if (value.length() < minimum || value.length() > maximum) invalid(
			"Provider text is outside bounds: " + key);
		return value;
	}

	private static void invalid(String message) throws WorldBuilderContractException {
		throw new WorldBuilderContractException(
			WorldBuilderErrorCodes.CAPABILITY_MISMATCH, OPERATION, message);
	}

	static final class Composition {
		final Map<String,Object> identity;
		final boolean installable;
		final List<String> moduleIds;
		final List<String> availableCapabilities;
		final List<String> admittedAdapterIds;
		final List<Artifact> artifacts;

		Composition(Map<String,Object> identity, boolean installable,
			List<String> moduleIds, List<String> availableCapabilities,
			List<String> admittedAdapterIds, List<Artifact> artifacts) {
			this.identity = identity;
			this.installable = installable;
			this.moduleIds = Collections.unmodifiableList(new ArrayList<String>(moduleIds));
			this.availableCapabilities = Collections.unmodifiableList(
				new ArrayList<String>(availableCapabilities));
			this.admittedAdapterIds = Collections.unmodifiableList(
				new ArrayList<String>(admittedAdapterIds));
			this.artifacts = Collections.unmodifiableList(
				new ArrayList<Artifact>(artifacts));
		}

		String string(String key) throws WorldBuilderContractException {
			return WorldBuilderProviderCatalog.string(identity, key);
		}
	}

	static final class Artifact {
		final String sourcePath;
		final String bundlePath;
		final Path source;
		final Map<String,Object> inventory;

		Artifact(String sourcePath, String bundlePath, Path source,
			Map<String,Object> inventory) {
			this.sourcePath = sourcePath;
			this.bundlePath = bundlePath;
			this.source = source;
			this.inventory = inventory;
		}
	}
}
