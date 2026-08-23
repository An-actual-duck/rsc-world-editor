package com.openrsc.worldbuilder;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/** Strict target-owned declarative definitions/assets captured inside one UUID project. */
final class WorldBuilderProjectContentBundle {
	static final String CAPABILITY_ID = "project-local-custom-content-v1";
	static final String SOURCE_DIRECTORY = "source/content-bundle";
	static final String WORKING_DIRECTORY = "working/content-bundle";
	static final String MANIFEST = "manifest.json";
	private static final String TYPE = "world-builder-project-content-bundle";
	private static final String CATALOG_TYPE = "world-builder-definition-catalog";
	private static final String ZERO_HASH =
		"0000000000000000000000000000000000000000000000000000000000000000";
	private static final long MAX_FILE_BYTES = 256L * 1024L * 1024L;
	private static final long MAX_EXPANDED_BYTES = 512L * 1024L * 1024L;
	private static final int MAX_ARCHIVE_ENTRIES = 8192;
	private static final int MAX_DEFINITIONS = 65536;
	private static final String OPERATION = "project-content-bundle";

	private static final List<Spec> SPECS = Collections.unmodifiableList(Arrays.asList(
		new Spec("definition.boundary", "server/conf/server/defs/DoorDef.xml",
			"server/conf/server/defs/DoorDef.xml", "application/xml", true),
		new Spec("definition.item.base", "server/conf/server/defs/ItemDefs.json",
			"server/conf/server/defs/ItemDefs.json", "application/json", true),
		new Spec("definition.item.custom", "server/conf/server/defs/ItemDefsCustom.json",
			"server/conf/server/defs/ItemDefsCustom.json", "application/json", true),
		new Spec("definition.item.patch", "server/conf/server/defs/ItemDefsPatch18.json",
			"server/conf/server/defs/ItemDefsPatch18.json", "application/json", true),
		new Spec("definition.item.world", "server/conf/server/defs/ItemDefsMyWorld.json",
			"server/conf/server/defs/ItemDefsMyWorld.json", "application/json", true),
		new Spec("definition.npc.base", "server/conf/server/defs/NpcDefs.json",
			"server/conf/server/defs/NpcDefs.json", "application/json", true),
		new Spec("definition.npc.custom", "server/conf/server/defs/NpcDefsCustom.json",
			"server/conf/server/defs/NpcDefsCustom.json", "application/json", true),
		new Spec("definition.npc.patch", "server/conf/server/defs/NpcDefsPatch18.json",
			"server/conf/server/defs/NpcDefsPatch18.json", "application/json", true),
		new Spec("definition.npc.world", "server/conf/server/defs/NpcDefsMyWorld.json",
			"server/conf/server/defs/NpcDefsMyWorld.json", "application/json", true),
		new Spec("definition.scenery", "server/conf/server/defs/GameObjectDef.xml",
			"server/conf/server/defs/GameObjectDef.xml", "application/xml", true),
		new Spec("definition.tile", "server/conf/server/defs/TileDef.xml",
			"server/conf/server/defs/TileDef.xml", "application/xml", true),
		new Spec("asset.library", "Client_Base/Cache/video/library.orsc",
			"client/Cache/video/library.orsc", "application/vnd.openrsc.archive", false),
		new Spec("asset.model", "Client_Base/Cache/video/models.orsc",
			"client/Cache/video/models.orsc", "application/vnd.openrsc.archive", false),
		new Spec("asset.sprite.authentic", "Client_Base/Cache/video/Authentic_Sprites.orsc",
			"client/Cache/video/Authentic_Sprites.orsc", "application/vnd.openrsc.archive", false),
		new Spec("asset.sprite.custom", "Client_Base/Cache/video/Custom_Sprites.osar",
			"client/Cache/video/Custom_Sprites.osar", "application/gzip", false),
		new Spec("asset.spritepack", "Client_Base/Cache/video/spritepacks/Menus.osar",
			"client/Cache/video/spritepacks/Menus.osar", "application/gzip", false)
	));

	private WorldBuilderProjectContentBundle() {
	}

	static List<WorldBuilderReadOnlyTarget.FileState> inspectTarget(
		WorldBuilderReadOnlyTarget target) throws WorldBuilderContractException {
		List<WorldBuilderReadOnlyTarget.FileState> result =
			new ArrayList<WorldBuilderReadOnlyTarget.FileState>();
		for (Spec spec : SPECS) {
			WorldBuilderReadOnlyTarget.FileState state =
				target.requiredState(discoveryRole(spec), spec.targetPath);
			validateFile(target.requiredFile(spec.targetPath), spec);
			result.add(state);
		}
		try {
			deriveCatalog(target.root, "target-adopted-content-v1");
		} catch (IOException changed) {
			throw problem(WorldBuilderErrorCodes.DISCOVERY_DRIFT, "target-content",
				"Target definitions changed while their complete catalog was derived.",
				"Stop target updates and retry discovery.", changed);
		}
		Collections.sort(result);
		return result;
	}

	private static String discoveryRole(Spec spec) {
		if ("definition.tile".equals(spec.role)) return "server-definition.tile";
		if ("definition.scenery".equals(spec.role)) return "server-definition.scenery";
		if (spec.role.startsWith("definition.npc.")) {
			return "server-" + spec.role;
		}
		if ("asset.library".equals(spec.role)) return "client-asset.library";
		return "content." + spec.role;
	}

	static Bundle capture(Path projectStage, Path copiedTarget)
		throws IOException, WorldBuilderContractException {
		Path sourceRoot = projectStage.resolve(SOURCE_DIRECTORY).normalize();
		if (!sourceRoot.startsWith(projectStage.toAbsolutePath().normalize())
			|| Files.exists(sourceRoot, LinkOption.NOFOLLOW_LINKS)) {
			throw problem(WorldBuilderErrorCodes.UNSAFE_PATH, SOURCE_DIRECTORY,
				"Project content-bundle destination is unsafe or already exists.",
				"Discard the unpublished project stage and retry.");
		}
		Files.createDirectories(sourceRoot);
		List<FileRecord> records = new ArrayList<FileRecord>();
		for (Spec spec : SPECS) {
			Path source = safeRegular(copiedTarget.resolve(spec.targetPath), spec.targetPath);
			validateFile(source, spec);
			String bundlePath = "files/" + spec.runtimePath;
			Path destination = sourceRoot.resolve(bundlePath).normalize();
			if (!destination.startsWith(sourceRoot)) throw unsafe(bundlePath);
			Files.createDirectories(destination.getParent());
			Files.copy(source, destination, StandardCopyOption.COPY_ATTRIBUTES);
			long size = Files.size(destination);
			String hash = WorldBuilderHashes.sha256(destination);
			if (size != Files.size(source) || !hash.equals(WorldBuilderHashes.sha256(source))) {
				throw problem(WorldBuilderErrorCodes.SOURCE_CORRUPT, spec.targetPath,
					"Target content changed while its project-local copy was verified.",
					"Stop target changes, rediscover, and create a new project.");
			}
			records.add(new FileRecord(spec, bundlePath, size, hash));
		}
		Collections.sort(records);
		Map<String,Object> catalog = deriveCatalog(sourceRoot,
			"target-adopted-content-v1");
		String definitions = fingerprint(
			"world-builder-project-content-definitions-v1\n", records, true,
			(String)catalog.get("catalogSha256"));
		String assets = fingerprint(
			"world-builder-project-content-assets-v1\n", records, false, "");
		Map<String,Object> manifest = manifest(catalog, records, definitions, assets);
		Path manifestPath = sourceRoot.resolve(MANIFEST);
		Files.write(manifestPath, WorldBuilderJsonDocuments.pretty(manifest)
			.getBytes(StandardCharsets.UTF_8));
		Bundle captured = read(sourceRoot);
		if (!captured.bundleFingerprintSha256.equals(
			(String)manifest.get("bundleFingerprintSha256"))) {
			throw problem(WorldBuilderErrorCodes.SOURCE_CORRUPT, SOURCE_DIRECTORY,
				"Captured custom-content bundle changed during verification.",
				"Discard the unpublished project stage and retry.");
		}
		return captured;
	}

	static Bundle copyToWorking(Path projectStage)
		throws IOException, WorldBuilderContractException {
		Path source = projectStage.resolve(SOURCE_DIRECTORY);
		Bundle expected = read(source);
		Path working = projectStage.resolve(WORKING_DIRECTORY);
		if (Files.exists(working, LinkOption.NOFOLLOW_LINKS)) throw unsafe(WORKING_DIRECTORY);
		copyTree(source, working, source);
		Bundle copied = read(working);
		if (!expected.bundleFingerprintSha256.equals(copied.bundleFingerprintSha256)) {
			throw problem(WorldBuilderErrorCodes.SOURCE_CORRUPT, WORKING_DIRECTORY,
				"Working custom-content bundle differs from immutable source evidence.",
				"Discard the unpublished project stage and retry.");
		}
		return copied;
	}

	static Bundle read(Path requestedRoot)
		throws IOException, WorldBuilderContractException {
		Path root = realDirectory(requestedRoot, requestedRoot.toString());
		Path manifestPath = safeRegular(root.resolve(MANIFEST), MANIFEST);
		Map<String,Object> manifest;
		try {
			manifest = WorldBuilderJsonDocuments.readObject(manifestPath);
		} catch (WorldBuilderDiscoveryException malformed) {
			throw problem(WorldBuilderErrorCodes.MALFORMED_JSON, MANIFEST,
				"Project custom-content manifest is malformed.",
				"Restore the exact version 1 content bundle.", malformed);
		}
		exact(manifest, "schemaVersion", "manifestType", "capabilityId", "sourceKind",
			"definitionCatalog", "familyBindings", "files",
			"definitionFingerprintSha256", "assetFingerprintSha256",
			"bundleFingerprintSha256");
		if (!Long.valueOf(1L).equals(manifest.get("schemaVersion"))
			|| !TYPE.equals(manifest.get("manifestType"))
			|| !CAPABILITY_ID.equals(manifest.get("capabilityId"))
			|| !("target-adopted".equals(manifest.get("sourceKind"))
				|| "content-neutral-default".equals(manifest.get("sourceKind")))) {
			throw problem(WorldBuilderErrorCodes.UNSUPPORTED_CONTRACT_VERSION, MANIFEST,
				"Project custom-content identity or version is unsupported.",
				"Use world-builder-project-content-bundle schema version 1.");
		}
		Map<String,Object> catalog = object(manifest.get("definitionCatalog"),
			"definitionCatalog");
		validateCatalogShape(catalog);
		List<FileRecord> records = parseFiles(root, manifest.get("files"));
		validateFamilies(manifest.get("familyBindings"), records);
		Map<String,Object> derived = deriveCatalog(root, (String)catalog.get("catalogId"));
		if (!catalog.equals(derived)) throw problem(WorldBuilderErrorCodes.DEFINITION_MISMATCH,
			MANIFEST, "Bundle authoring IDs do not exactly match adopted definitions.",
			"Recreate the project from one stable complete target content set.");
		String definitionHash = fingerprint(
			"world-builder-project-content-definitions-v1\n", records, true,
			(String)catalog.get("catalogSha256"));
		String assetHash = fingerprint(
			"world-builder-project-content-assets-v1\n", records, false, "");
		if (!definitionHash.equals(manifest.get("definitionFingerprintSha256"))
			|| !assetHash.equals(manifest.get("assetFingerprintSha256"))) {
			throw problem(WorldBuilderErrorCodes.CAPABILITY_MISMATCH, MANIFEST,
				"Bundle definition or client-asset fingerprint is inconsistent.",
				"Restore the exact complete project-local content bundle.");
		}
		String expectedBundle = selfFingerprint(manifest);
		if (!expectedBundle.equals(manifest.get("bundleFingerprintSha256"))) {
			throw problem(WorldBuilderErrorCodes.CAPABILITY_MISMATCH, MANIFEST,
				"Bundle fingerprint is inconsistent with its canonical manifest.",
				"Restore the exact complete project-local content bundle.");
		}
		Set<String> expectedPaths = new HashSet<String>();
		expectedPaths.add(MANIFEST);
		for (FileRecord record : records) expectedPaths.add(record.bundlePath);
		Set<String> actualPaths = scanFiles(root);
		if (!actualPaths.equals(expectedPaths)) throw problem(
			WorldBuilderErrorCodes.INVENTORY_DUPLICATE, requestedRoot.toString(),
			"Project custom-content file inventory is missing or contains extras.",
			"Restore the exact version 1 bundle inventory.");
		return new Bundle(root, catalog, records, definitionHash, assetHash, expectedBundle);
	}

	private static Map<String,Object> manifest(Map<String,Object> catalog,
		List<FileRecord> records, String definitions, String assets)
		throws WorldBuilderContractException {
		Map<String,Object> value = new LinkedHashMap<String,Object>();
		value.put("schemaVersion", Long.valueOf(1L));
		value.put("manifestType", TYPE);
		value.put("capabilityId", CAPABILITY_ID);
		value.put("sourceKind", "target-adopted");
		value.put("definitionCatalog", catalog);
		value.put("familyBindings", familyBindings());
		List<Object> files = new ArrayList<Object>();
		for (FileRecord record : records) files.add(record.toJson());
		value.put("files", files);
		value.put("definitionFingerprintSha256", definitions);
		value.put("assetFingerprintSha256", assets);
		value.put("bundleFingerprintSha256", ZERO_HASH);
		value.put("bundleFingerprintSha256", selfFingerprint(value));
		return value;
	}

	private static List<Object> familyBindings() {
		List<Object> result = new ArrayList<Object>();
		result.add(binding("floor", Arrays.asList("definition.tile"),
			Arrays.asList("asset.sprite.custom")));
		result.add(binding("ground-item", Arrays.asList("definition.item.base",
			"definition.item.custom", "definition.item.patch", "definition.item.world"),
			Arrays.asList("asset.library", "asset.sprite.authentic", "asset.sprite.custom",
				"asset.spritepack")));
		result.add(binding("npc", Arrays.asList("definition.npc.base",
			"definition.npc.custom", "definition.npc.patch", "definition.npc.world"),
			Arrays.asList("asset.library", "asset.sprite.authentic", "asset.sprite.custom",
				"asset.spritepack")));
		result.add(binding("scenery", Arrays.asList("definition.scenery"),
			Arrays.asList("asset.library", "asset.model", "asset.sprite.custom")));
		result.add(binding("wall", Arrays.asList("definition.boundary"),
			Arrays.asList("asset.sprite.custom")));
		return result;
	}

	private static Map<String,Object> binding(String family,
		List<String> definitions, List<String> assets) {
		Map<String,Object> value = new LinkedHashMap<String,Object>();
		value.put("family", family);
		value.put("definitionRoles", new ArrayList<String>(definitions));
		value.put("assetRoles", new ArrayList<String>(assets));
		return value;
	}

	private static void validateFamilies(Object raw, List<FileRecord> records)
		throws WorldBuilderContractException {
		List<?> values = array(raw, "familyBindings", 5, 5);
		List<Object> expected = familyBindings();
		if (!values.equals(expected)) throw problem(WorldBuilderErrorCodes.CONTRACT_VALUE_INVALID,
			MANIFEST, "Content family bindings are incomplete or noncanonical.",
			"Use the exact five version 1 family bindings.");
		Set<String> present = new HashSet<String>();
		for (FileRecord record : records) present.add(record.spec.role);
		for (Object rawBinding : values) {
			Map<String,Object> binding = object(rawBinding, "familyBinding");
			for (String key : Arrays.asList("definitionRoles", "assetRoles")) {
				for (Object role : array(binding.get(key), key, 1, 64)) {
					if (!(role instanceof String) || !present.contains(role)) throw problem(
						WorldBuilderErrorCodes.CAPABILITY_MISMATCH, MANIFEST,
						"Content family references a missing definition or asset role.",
						"Capture the complete matching target content closure.");
				}
			}
		}
	}

	private static List<FileRecord> parseFiles(Path root, Object raw)
		throws IOException, WorldBuilderContractException {
		List<?> values = array(raw, "files", SPECS.size(), 256);
		if (values.size() != SPECS.size()) throw problem(
			WorldBuilderErrorCodes.CAPABILITY_MISMATCH, MANIFEST,
			"Version 1 target bundle is missing a required declarative content role.",
			"Capture all required target definitions and client assets.");
		Map<String,Spec> specs = new HashMap<String,Spec>();
		for (Spec spec : SPECS) specs.put(spec.role, spec);
		Set<String> paths = new HashSet<String>();
		Set<String> roles = new HashSet<String>();
		List<FileRecord> result = new ArrayList<FileRecord>();
		String previous = "";
		for (Object rawRecord : values) {
			Map<String,Object> record = object(rawRecord, "file");
			exact(record, "role", "bundleRelativePath", "runtimeRelativePath",
				"mediaType", "size", "sha256");
			String role = string(record, "role");
			Spec spec = specs.get(role);
			String bundlePath = string(record, "bundleRelativePath");
			String runtimePath = string(record, "runtimeRelativePath");
			if (spec == null || !roles.add(role) || !spec.runtimePath.equals(runtimePath)
				|| !spec.mediaType.equals(string(record, "mediaType"))
				|| !("files/" + runtimePath).equals(bundlePath)
				|| previous.compareTo(runtimePath) >= 0) {
				throw problem(WorldBuilderErrorCodes.CONTRACT_VALUE_INVALID, MANIFEST,
					"Content file roles or compiled runtime destinations are invalid.",
					"Use each exact version 1 role/path mapping once in canonical order.");
			}
			WorldBuilderPortablePath.require(bundlePath, OPERATION);
			WorldBuilderPortablePath.require(runtimePath, OPERATION);
			if (!paths.add(bundlePath.toLowerCase(Locale.ROOT))) throw unsafe(bundlePath);
			long size = integer(record, "size");
			String hash = string(record, "sha256");
			if (size < 1L || size > MAX_FILE_BYTES || !WorldBuilderBoundedInventory.isHash(hash)) {
				throw problem(WorldBuilderErrorCodes.CONTRACT_LIMIT_EXCEEDED, bundlePath,
					"Content file size or hash is outside version 1 limits.",
					"Use a nonempty file no larger than 256 MiB with exact SHA-256.");
			}
			Path path = safeRegular(root.resolve(bundlePath), bundlePath);
			if (Files.size(path) != size || !hash.equals(WorldBuilderHashes.sha256(path))) {
				throw problem(WorldBuilderErrorCodes.SOURCE_CORRUPT, bundlePath,
					"Content file differs from the exact manifest inventory.",
					"Restore the exact complete project-local bundle.");
			}
			validateFile(path, spec);
			result.add(new FileRecord(spec, bundlePath, size, hash));
			previous = runtimePath;
		}
		if (!roles.equals(specs.keySet())) throw problem(WorldBuilderErrorCodes.CAPABILITY_MISMATCH,
			MANIFEST, "Content role closure is incomplete.",
			"Capture every required version 1 definition and client asset role.");
		return result;
	}

	private static Map<String,Object> deriveCatalog(Path root, String catalogId)
		throws IOException, WorldBuilderContractException {
		List<Object> tiles = range(xmlCount(root, "definition.tile",
			"TileDef-array", "TileDef"));
		List<Object> boundaries = range(xmlCount(root, "definition.boundary",
			"DoorDef-array", "DoorDef"));
		List<Object> scenery = range(xmlCount(root, "definition.scenery",
			"GameObjectDef-array", "GameObjectDef"));
		Set<Integer> npcIds = new TreeSet<Integer>();
		int appendedNpcCount = jsonCount(root, "definition.npc.base", "npcs")
			+ jsonCount(root, "definition.npc.custom", "npcs");
		for (int id = 0; id < appendedNpcCount; id++) npcIds.add(Integer.valueOf(id));
		npcIds.addAll(jsonIds(root, "definition.npc.world", "npcs"));
		npcIds.addAll(jsonIds(root, "definition.npc.patch", "npcs"));
		Set<Integer> itemIds = new TreeSet<Integer>();
		itemIds.addAll(jsonIds(root, "definition.item.base", "item"));
		itemIds.addAll(jsonIds(root, "definition.item.custom", "items"));
		itemIds.addAll(jsonIds(root, "definition.item.world", "items"));
		itemIds.addAll(jsonIds(root, "definition.item.patch", "items"));
		Map<String,Object> catalog = new LinkedHashMap<String,Object>();
		catalog.put("schemaVersion", Long.valueOf(1L));
		catalog.put("manifestType", CATALOG_TYPE);
		catalog.put("catalogId", catalogId);
		catalog.put("tiles", tiles);
		catalog.put("boundaries", boundaries);
		catalog.put("scenery", scenery);
		catalog.put("npcs", numbers(npcIds));
		catalog.put("groundItems", numbers(itemIds));
		catalog.put("catalogSha256", ZERO_HASH);
		catalog.put("catalogSha256", selfHash(catalog, "catalogSha256"));
		return catalog;
	}

	private static int xmlCount(Path root, String role, String rootName, String element)
		throws IOException, WorldBuilderContractException {
		Path path = contentPath(root, role);
		try (InputStream input = Files.newInputStream(path)) {
			DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
			factory.setXIncludeAware(false);
			factory.setExpandEntityReferences(false);
			factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
			factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
			factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
			factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
			Document document = factory.newDocumentBuilder().parse(input);
			Element documentRoot = document.getDocumentElement();
			if (documentRoot == null || !rootName.equals(documentRoot.getNodeName())) {
				throw malformedDefinition(role);
			}
			int count = 0;
			NodeList children = documentRoot.getChildNodes();
			for (int index = 0; index < children.getLength(); index++) {
				Node child = children.item(index);
				if (child.getNodeType() == Node.ELEMENT_NODE
					&& element.equals(child.getNodeName()) && ++count > MAX_DEFINITIONS) {
					throw tooManyDefinitions(role);
				}
			}
			if (count == 0) throw malformedDefinition(role);
			return count;
		} catch (WorldBuilderContractException invalid) {
			throw invalid;
		} catch (Exception malformed) {
			throw malformedDefinition(role, malformed);
		}
	}

	private static int jsonCount(Path root, String role, String arrayName)
		throws IOException, WorldBuilderContractException {
		return jsonArray(root, role, arrayName).size();
	}

	private static Set<Integer> jsonIds(Path root, String role, String arrayName)
		throws IOException, WorldBuilderContractException {
		Set<Integer> result = new TreeSet<Integer>();
		for (Object raw : jsonArray(root, role, arrayName)) {
			if (!(raw instanceof Map)) throw malformedDefinition(role);
			@SuppressWarnings("unchecked") Map<String,Object> value = (Map<String,Object>)raw;
			Object id = value.get("id");
			if (!(id instanceof Long) || ((Long)id).longValue() < 0L
				|| ((Long)id).longValue() > Integer.MAX_VALUE
				|| !result.add(Integer.valueOf((int)((Long)id).longValue()))) {
				throw malformedDefinition(role);
			}
		}
		return result;
	}

	private static List<?> jsonArray(Path root, String role, String arrayName)
		throws IOException, WorldBuilderContractException {
		Map<String,Object> value;
		try {
			value = WorldBuilderJsonDocuments.readObject(contentPath(root, role));
		} catch (WorldBuilderDiscoveryException malformed) {
			throw malformedDefinition(role, malformed);
		}
		if (value.size() != 1 || !(value.get(arrayName) instanceof List)
			|| ((List<?>)value.get(arrayName)).size() > MAX_DEFINITIONS) {
			throw malformedDefinition(role);
		}
		return (List<?>)value.get(arrayName);
	}

	private static Path contentPath(Path root, String role)
		throws WorldBuilderContractException {
		for (Spec spec : SPECS) if (role.equals(spec.role)) {
			Path bundled = root.resolve("files/" + spec.runtimePath);
			return Files.isRegularFile(bundled, LinkOption.NOFOLLOW_LINKS)
				? bundled : root.resolve(spec.targetPath);
		}
		throw new AssertionError(role);
	}

	private static void validateFile(Path path, Spec spec)
		throws WorldBuilderContractException {
		try {
			long size = Files.size(path);
			if (size < 1L || size > MAX_FILE_BYTES) throw problem(
				WorldBuilderErrorCodes.CONTRACT_LIMIT_EXCEEDED, spec.targetPath,
				"Target custom-content file is empty or exceeds 256 MiB.",
				"Use one bounded nonempty declarative definition or client asset.");
			if (spec.definition) return;
			byte[] magic = new byte[4];
			try (InputStream input = Files.newInputStream(path)) {
				int offset = 0;
				while (offset < magic.length) {
					int read = input.read(magic, offset, magic.length - offset);
					if (read < 0) break;
					offset += read;
				}
			}
			boolean gzip = magic[0] == 0x1f && (magic[1] & 0xff) == 0x8b;
			boolean zip = magic[0] == 'P' && magic[1] == 'K';
			if (gzip) validateGzip(path, spec.targetPath);
			else if (zip) validateZip(path, spec.targetPath);
			else if (!("asset.library".equals(spec.role) || "asset.model".equals(spec.role))) {
				throw problem(WorldBuilderErrorCodes.UNSUPPORTED_FORMAT, spec.targetPath,
					"Client asset archive has an unsupported container format.",
					"Provide the exact supported target-owned archive.");
			}
		} catch (WorldBuilderContractException invalid) {
			throw invalid;
		} catch (IOException failure) {
			throw problem(WorldBuilderErrorCodes.DISCOVERY_DRIFT, spec.targetPath,
				"Target custom-content file changed while it was inspected.",
				"Stop target updates and retry discovery.", failure);
		}
	}

	private static void validateGzip(Path path, String label)
		throws IOException, WorldBuilderContractException {
		long total = 0L;
		try (InputStream input = new GZIPInputStream(Files.newInputStream(path))) {
			byte[] buffer = new byte[8192];
			int read;
			while ((read = input.read(buffer)) >= 0) {
				total += read;
				if (total > MAX_EXPANDED_BYTES) throw problem(
					WorldBuilderErrorCodes.CONTRACT_LIMIT_EXCEEDED, label,
					"Compressed client asset expands beyond 512 MiB.",
					"Reduce the exact declarative asset archive.");
			}
		}
		if (total == 0L) throw problem(WorldBuilderErrorCodes.UNSUPPORTED_FORMAT, label,
			"Compressed client asset expands to no data.",
			"Provide a complete supported target-owned archive.");
	}

	private static void validateZip(Path path, String label)
		throws IOException, WorldBuilderContractException {
		Set<String> names = new HashSet<String>();
		long total = 0L;
		int count = 0;
		try (ZipFile archive = new ZipFile(path.toFile())) {
			java.util.Enumeration<? extends ZipEntry> entries = archive.entries();
			while (entries.hasMoreElements()) {
				ZipEntry entry = entries.nextElement();
				if (++count > MAX_ARCHIVE_ENTRIES) throw problem(
					WorldBuilderErrorCodes.CONTRACT_LIMIT_EXCEEDED, label,
					"Client asset archive has too many entries.",
					"Reduce the exact declarative asset archive.");
				String name = entry.getName();
				WorldBuilderPortablePath.require(name, OPERATION);
				if (entry.isDirectory() || !names.add(name.toLowerCase(Locale.ROOT))) {
					throw problem(WorldBuilderErrorCodes.UNSAFE_PATH, label,
						"Client asset archive contains a directory or colliding entry.",
						"Rebuild the archive with unique portable regular-file entries only.");
				}
				long size = entry.getSize();
				if (size < 0L || size > MAX_FILE_BYTES || total + size > MAX_EXPANDED_BYTES) {
					throw problem(WorldBuilderErrorCodes.CONTRACT_LIMIT_EXCEEDED, label,
						"Client asset archive expansion is unknown or exceeds its bound.",
						"Rebuild the archive with bounded declared entry sizes.");
				}
				total += size;
			}
		}
		if (count == 0) throw problem(WorldBuilderErrorCodes.UNSUPPORTED_FORMAT, label,
			"Client asset archive contains no entries.",
			"Provide a complete supported target-owned archive.");
	}

	private static String fingerprint(String domain, List<FileRecord> records,
		boolean definitions, String suffix) {
		MessageDigest digest = sha256();
		digest.update(domain.getBytes(StandardCharsets.US_ASCII));
		for (FileRecord record : records) {
			if (record.spec.definition != definitions) continue;
			String value = record.spec.role + "\u0000" + record.spec.runtimePath
				+ "\u0000" + record.size + "\u0000" + record.sha256 + "\n";
			digest.update(value.getBytes(StandardCharsets.UTF_8));
		}
		if (!suffix.isEmpty()) digest.update(suffix.getBytes(StandardCharsets.US_ASCII));
		return hex(digest.digest());
	}

	private static String selfFingerprint(Map<String,Object> manifest)
		throws WorldBuilderContractException {
		MessageDigest digest = sha256();
		digest.update("world-builder-project-content-bundle-v1\n"
			.getBytes(StandardCharsets.US_ASCII));
		Map<String,Object> copy = deepCopy(manifest);
		copy.put("bundleFingerprintSha256", ZERO_HASH);
		digest.update(WorldBuilderJsonDocuments.canonical(copy)
			.getBytes(StandardCharsets.UTF_8));
		return hex(digest.digest());
	}

	private static String selfHash(Map<String,Object> value, String field)
		throws WorldBuilderContractException {
		Map<String,Object> copy = deepCopy(value);
		copy.put(field, ZERO_HASH);
		return WorldBuilderHashes.sha256(WorldBuilderJsonDocuments.canonical(copy)
			.getBytes(StandardCharsets.UTF_8));
	}

	private static void validateCatalogShape(Map<String,Object> catalog)
		throws WorldBuilderContractException {
		exact(catalog, "schemaVersion", "manifestType", "catalogId", "tiles",
			"boundaries", "scenery", "npcs", "groundItems", "catalogSha256");
		if (!Long.valueOf(1L).equals(catalog.get("schemaVersion"))
			|| !CATALOG_TYPE.equals(catalog.get("manifestType"))) {
			throw malformedDefinition("definitionCatalog");
		}
		String id = string(catalog, "catalogId");
		if (!id.matches("[A-Za-z0-9][A-Za-z0-9._:+-]{0,127}")) {
			throw malformedDefinition("definitionCatalog");
		}
		for (String field : Arrays.asList("tiles", "boundaries", "scenery", "npcs",
			"groundItems")) validateIds(catalog.get(field), field);
		if (!selfHash(catalog, "catalogSha256").equals(catalog.get("catalogSha256"))) {
			throw malformedDefinition("definitionCatalog");
		}
	}

	private static void validateIds(Object raw, String field)
		throws WorldBuilderContractException {
		List<?> values = array(raw, field, 0, MAX_DEFINITIONS);
		long previous = -1L;
		for (Object value : values) {
			if (!(value instanceof Long) || ((Long)value).longValue() <= previous
				|| ((Long)value).longValue() > Integer.MAX_VALUE) {
				throw malformedDefinition(field);
			}
			previous = ((Long)value).longValue();
		}
	}

	private static List<Object> range(int count) {
		List<Object> result = new ArrayList<Object>(count);
		for (int id = 0; id < count; id++) result.add(Long.valueOf(id));
		return result;
	}

	private static List<Object> numbers(Set<Integer> values) {
		List<Object> result = new ArrayList<Object>(values.size());
		for (Integer value : values) result.add(Long.valueOf(value.longValue()));
		return result;
	}

	private static void copyTree(Path source, Path destination, Path sourceRoot)
		throws IOException, WorldBuilderContractException {
		Files.createDirectories(destination);
		for (String relative : scanFiles(sourceRoot)) {
			Path input = safeRegular(source.resolve(relative), relative);
			Path output = destination.resolve(relative).normalize();
			if (!output.startsWith(destination)) throw unsafe(relative);
			Files.createDirectories(output.getParent());
			Files.copy(input, output, StandardCopyOption.COPY_ATTRIBUTES);
		}
	}

	private static Set<String> scanFiles(Path root)
		throws IOException, WorldBuilderContractException {
		final Set<String> result = new TreeSet<String>();
		Files.walkFileTree(root, new java.nio.file.SimpleFileVisitor<Path>() {
			@Override public java.nio.file.FileVisitResult preVisitDirectory(
				Path directory, java.nio.file.attribute.BasicFileAttributes attributes)
				throws IOException {
				if (Files.isSymbolicLink(directory)) throw new IOException("linked directory");
				return java.nio.file.FileVisitResult.CONTINUE;
			}
			@Override public java.nio.file.FileVisitResult visitFile(
				Path file, java.nio.file.attribute.BasicFileAttributes attributes)
				throws IOException {
				if (!attributes.isRegularFile() || Files.isSymbolicLink(file)) {
					throw new IOException("unsafe content entry");
				}
				String relative = root.relativize(file).toString().replace('\\', '/');
				try {
					WorldBuilderPortablePath.require(relative, OPERATION);
				} catch (WorldBuilderContractException invalid) {
					throw new IOException("unsafe content path", invalid);
				}
				if (!result.add(relative)) throw new IOException("duplicate content path");
				return java.nio.file.FileVisitResult.CONTINUE;
			}
		});
		return result;
	}

	private static Path safeRegular(Path path, String label)
		throws IOException, WorldBuilderContractException {
		if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
			|| Files.isSymbolicLink(path)) throw unsafe(label);
		Path parent = path.getParent();
		if (parent == null || !path.toRealPath().startsWith(parent.toRealPath())) {
			throw unsafe(label);
		}
		return path;
	}

	private static Path realDirectory(Path path, String label)
		throws IOException, WorldBuilderContractException {
		if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
			|| Files.isSymbolicLink(path)) throw unsafe(label);
		return path.toRealPath();
	}

	private static void exact(Map<String,Object> value, String... keys)
		throws WorldBuilderContractException {
		Set<String> expected = new HashSet<String>(Arrays.asList(keys));
		if (!value.keySet().equals(expected)) throw problem(
			WorldBuilderErrorCodes.CONTRACT_KEYS_INVALID, MANIFEST,
			"Project custom-content document contains missing or unknown keys.",
			"Use only the exact schema version 1 keys.");
	}

	@SuppressWarnings("unchecked")
	private static Map<String,Object> object(Object raw, String field)
		throws WorldBuilderContractException {
		if (!(raw instanceof Map)) throw malformedDefinition(field);
		return (Map<String,Object>)raw;
	}

	private static List<?> array(Object raw, String field, int minimum, int maximum)
		throws WorldBuilderContractException {
		if (!(raw instanceof List) || ((List<?>)raw).size() < minimum
			|| ((List<?>)raw).size() > maximum) throw problem(
			WorldBuilderErrorCodes.CONTRACT_LIMIT_EXCEEDED, MANIFEST,
			"Project custom-content array is missing or outside limits: " + field,
			"Use the bounded version 1 contract.");
		return (List<?>)raw;
	}

	private static String string(Map<String,Object> value, String field)
		throws WorldBuilderContractException {
		Object raw = value.get(field);
		if (!(raw instanceof String)) throw malformedDefinition(field);
		return (String)raw;
	}

	private static long integer(Map<String,Object> value, String field)
		throws WorldBuilderContractException {
		Object raw = value.get(field);
		if (!(raw instanceof Long)) throw malformedDefinition(field);
		return ((Long)raw).longValue();
	}

	private static WorldBuilderContractException malformedDefinition(String role) {
		return malformedDefinition(role, null);
	}

	private static WorldBuilderContractException malformedDefinition(
		String role, Throwable cause) {
		return problem(WorldBuilderErrorCodes.DEFINITION_MISMATCH, role,
			"Target declarative definition content is malformed or unsupported.",
			"Correct the exact target definition and retry discovery.", cause);
	}

	private static WorldBuilderContractException tooManyDefinitions(String role) {
		return problem(WorldBuilderErrorCodes.CONTRACT_LIMIT_EXCEEDED, role,
			"Target definition family exceeds 65,536 entries.",
			"Reduce the exact declarative definition inventory.");
	}

	private static WorldBuilderContractException unsafe(String path) {
		return problem(WorldBuilderErrorCodes.UNSAFE_PATH, path,
			"Project custom-content path is linked, special, aliased, or outside its root.",
			"Use only contained portable regular files and real directories.");
	}

	private static WorldBuilderContractException problem(
		String code, String path, String message, String nextStep) {
		return problem(code, path, message, nextStep, null);
	}

	private static WorldBuilderContractException problem(
		String code, String path, String message, String nextStep, Throwable cause) {
		return new WorldBuilderContractException(code, OPERATION, path, false,
			message, nextStep, cause);
	}

	private static MessageDigest sha256() {
		try {
			return MessageDigest.getInstance("SHA-256");
		} catch (NoSuchAlgorithmException unavailable) {
			throw new AssertionError(unavailable);
		}
	}

	private static String hex(byte[] value) {
		StringBuilder result = new StringBuilder(value.length * 2);
		for (byte item : value) result.append(String.format(Locale.ROOT, "%02x", item & 0xff));
		return result.toString();
	}

	@SuppressWarnings("unchecked")
	private static Map<String,Object> deepCopy(Map<String,Object> value)
		throws WorldBuilderContractException {
		try {
			return WorldBuilderJsonDocuments.readObject(
				WorldBuilderJsonDocuments.pretty(value).getBytes(StandardCharsets.UTF_8),
				"custom content fingerprint");
		} catch (WorldBuilderDiscoveryException impossible) {
			throw problem(WorldBuilderErrorCodes.CONTRACT_VALUE_INVALID, MANIFEST,
				"Project custom-content manifest cannot be canonicalized.",
				"Restore the exact version 1 manifest.", impossible);
		}
	}

	static final class Bundle {
		final Path root;
		final Map<String,Object> definitionCatalog;
		final List<FileRecord> files;
		final String definitionFingerprintSha256;
		final String assetFingerprintSha256;
		final String bundleFingerprintSha256;

		Bundle(Path root, Map<String,Object> definitionCatalog,
			List<FileRecord> files, String definitionFingerprintSha256,
			String assetFingerprintSha256, String bundleFingerprintSha256) {
			this.root = root;
			this.definitionCatalog = Collections.unmodifiableMap(
				new LinkedHashMap<String,Object>(definitionCatalog));
			this.files = Collections.unmodifiableList(new ArrayList<FileRecord>(files));
			this.definitionFingerprintSha256 = definitionFingerprintSha256;
			this.assetFingerprintSha256 = assetFingerprintSha256;
			this.bundleFingerprintSha256 = bundleFingerprintSha256;
		}

		Map<String,Object> compatibilityCatalog() {
			Map<String,Object> result =
				new LinkedHashMap<String,Object>(definitionCatalog);
			result.remove("catalogSha256");
			return result;
		}
	}

	static final class FileRecord implements Comparable<FileRecord> {
		final Spec spec;
		final String bundlePath;
		final long size;
		final String sha256;

		FileRecord(Spec spec, String bundlePath, long size, String sha256) {
			this.spec = spec;
			this.bundlePath = bundlePath;
			this.size = size;
			this.sha256 = sha256;
		}

		Map<String,Object> toJson() {
			Map<String,Object> value = new LinkedHashMap<String,Object>();
			value.put("role", spec.role);
			value.put("bundleRelativePath", bundlePath);
			value.put("runtimeRelativePath", spec.runtimePath);
			value.put("mediaType", spec.mediaType);
			value.put("size", Long.valueOf(size));
			value.put("sha256", sha256);
			return value;
		}

		@Override public int compareTo(FileRecord other) {
			return spec.runtimePath.compareTo(other.spec.runtimePath);
		}
	}

	private static final class Spec {
		final String role;
		final String targetPath;
		final String runtimePath;
		final String mediaType;
		final boolean definition;

		Spec(String role, String targetPath, String runtimePath,
			String mediaType, boolean definition) {
			this.role = role;
			this.targetPath = targetPath;
			this.runtimePath = runtimePath;
			this.mediaType = mediaType;
			this.definition = definition;
		}
	}
}
