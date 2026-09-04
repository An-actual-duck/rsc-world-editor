package com.openrsc.worldbuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
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

		List<Map<String,Object>> schemaContracts = objectList(platform.get("schemaContracts"),
			"schemaContracts", 5, 256);
		validateSchemaContracts(catalog, schemaContracts);
		requireHash(canonicalHash(schemaContracts), string(composition, "schemaSetHash"),
			"schemaSetHash");

		List<Map<String,Object>> moduleSet = objectList(composition.get("moduleSet"),
			"moduleSet", 0, 128);
		List<Map<String,Object>> moduleArtifacts = new ArrayList<Map<String,Object>>();
		Set<String> resolvedModuleIds = new HashSet<String>();
		for (Map<String,Object> record : moduleSet) {
			exact(record, "manifestHash", "moduleId", "moduleVersion", "payloadRootHash");
			String moduleId = identifier(record, "moduleId");
			identifier(record, "moduleVersion"); hash(record, "manifestHash");
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
			List<Map<String,Object>> artifacts = inventory(module.get("artifacts"),
				"module artifacts");
			requireHash(canonicalHash(artifacts), string(record, "payloadRootHash"),
				"module payloadRootHash");
			moduleArtifacts.addAll(artifacts);
		}
		requireHash(canonicalHash(moduleSet), string(composition, "moduleSetHash"),
			"moduleSetHash");

		List<Map<String,Object>> expectedInventory = inventory(bundle.get("artifacts"),
			"bundle artifacts");
		expectedInventory.addAll(moduleArtifacts);
		Collections.sort(expectedInventory, inventoryOrder());
		validateInventoryOrderAndCollisions(expectedInventory, "resolved provider inventory");
		List<Map<String,Object>> suppliedInventory = inventory(
			composition.get("bundleInventory"), "bundleInventory");
		if (!WorldBuilderJsonDocuments.canonical(expectedInventory).equals(
			WorldBuilderJsonDocuments.canonical(suppliedInventory))) invalid(
			"Resolved bundle inventory does not equal the provider bundle plus module closure.");
		requireHash(canonicalHash(suppliedInventory),
			string(composition, "bundleInventoryHash"), "bundleInventoryHash");

		List<String> bundleModules = identifiers(bundle.get("moduleIds"), "bundle moduleIds", 0, 128);
		List<String> resolvedModules = new ArrayList<String>();
		for (Map<String,Object> record : moduleSet) resolvedModules.add(string(record, "moduleId"));
		if (!resolvedModules.containsAll(bundleModules)) invalid(
			"Resolved composition omits a bundle-required module.");
		boolean installable = bool(composition, "installable");
		if (installable != bool(bundle, "installable")) invalid(
			"Composition and bundle installability disagree.");
		return new Composition(composition, variant, installable, resolvedModules);
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

	private static List<Map<String,Object>> inventory(Object raw, String name)
		throws WorldBuilderContractException {
		List<Map<String,Object>> result = objectList(raw, name, 0,
			WorldBuilderContractLimits.MAX_INVENTORY_ENTRIES);
		for (Map<String,Object> record : result) {
			exact(record, "bundlePath", "destination", "mode", "ownership", "provenance",
				"replacementPolicy", "role", "rollbackPolicy", "sha256", "size", "type");
			relative(record, "bundlePath"); relative(record, "destination");
			identifier(record, "mode"); identifier(record, "ownership");
			text(record, "provenance", 1, 1024); identifier(record, "replacementPolicy");
			identifier(record, "role"); identifier(record, "rollbackPolicy");
			hash(record, "sha256");
			long size = integer(record, "size");
			if (size < 0 || size > WorldBuilderContractLimits.MAX_INVENTORY_FILE_BYTES) invalid(
				"Provider artifact size is outside the Editor's bounded inventory limit.");
			identifier(record, "type");
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

	private static List<String> identifiers(Object raw, String name, int minimum, int maximum)
		throws WorldBuilderContractException {
		if (!(raw instanceof List)) invalid("Provider field is not an array: " + name);
		List<?> values = (List<?>)raw;
		if (values.size() < minimum || values.size() > maximum) invalid(
			"Provider identifier array is outside bounds: " + name);
		List<String> result = new ArrayList<String>();
		String previous = null;
		for (Object value : values) {
			String item = WorldBuilderBoundedInventory.identifier(value, OPERATION, name);
			if (previous != null && previous.compareTo(item) >= 0) invalid(
				"Provider identifier array repeats or is unordered: " + name);
			result.add(item); previous = item;
		}
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

	private static WorldBuilderContractException invalid(String message) {
		return new WorldBuilderContractException(
			WorldBuilderErrorCodes.CAPABILITY_MISMATCH, OPERATION, message);
	}

	static final class Composition {
		final Map<String,Object> identity;
		final Map<String,Object> variant;
		final boolean installable;
		final List<String> moduleIds;

		Composition(Map<String,Object> identity, Map<String,Object> variant,
			boolean installable, List<String> moduleIds) {
			this.identity = identity;
			this.variant = variant;
			this.installable = installable;
			this.moduleIds = Collections.unmodifiableList(new ArrayList<String>(moduleIds));
		}

		String string(String key) throws WorldBuilderContractException {
			return WorldBuilderProviderCatalog.string(identity, key);
		}
	}
}
