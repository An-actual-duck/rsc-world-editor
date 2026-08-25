package com.openrsc.worldbuilder;

import java.io.ByteArrayOutputStream;
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
import java.util.TreeMap;
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
	static final String CAPABILITY_ID = "project-local-custom-content-v2";
	static final String LEGACY_CAPABILITY_ID = "project-local-custom-content-v1";
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
	private static final int MAX_RUNTIME_ID = 65535;
	private static final int MAX_RAW_BYTE_ID = 254;
	private static final String OPERATION = "project-content-bundle";
	private static final String ITEM_VISUAL_EVIDENCE_PATH =
		"server/conf/world-builder/item-visuals-v1.json";

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
	private static final Spec ITEM_VISUAL_SPEC = new Spec("metadata.item-visuals",
		ITEM_VISUAL_EVIDENCE_PATH, ITEM_VISUAL_EVIDENCE_PATH, "application/json", true);

	private WorldBuilderProjectContentBundle() {
	}

	static List<WorldBuilderReadOnlyTarget.FileState> inspectTarget(
		WorldBuilderReadOnlyTarget target) throws WorldBuilderContractException {
		WorldBuilderPackedSourceLayout layout =
			WorldBuilderPackedSourceLayout.select(target);
		List<WorldBuilderReadOnlyTarget.FileState> result =
			new ArrayList<WorldBuilderReadOnlyTarget.FileState>();
		for (Spec spec : SPECS) {
			Spec sourceSpec = sourceSpec(spec, layout);
			WorldBuilderReadOnlyTarget.FileState state =
				target.requiredState(discoveryRole(spec), sourceSpec.targetPath);
			validateFile(target.requiredFile(sourceSpec.targetPath), sourceSpec);
			result.add(state);
		}
		WorldBuilderReadOnlyTarget.FileState visuals = target.optionalState(
			discoveryRole(ITEM_VISUAL_SPEC), ITEM_VISUAL_EVIDENCE_PATH);
		if (visuals.present) {
			validateItemVisualEvidence(target.requiredFile(ITEM_VISUAL_EVIDENCE_PATH));
		}
		result.add(visuals);
		try {
			deriveCatalog(target.root, "target-adopted-content-v1", layout);
		} catch (IOException changed) {
			throw problem(WorldBuilderErrorCodes.DISCOVERY_DRIFT, "target-content",
				"Target definitions changed while their complete catalog was derived.",
				"Stop target updates and retry discovery.", changed);
		}
		Collections.sort(result);
		return result;
	}

	private static Spec sourceSpec(Spec spec, WorldBuilderPackedSourceLayout layout) {
		String video = WorldBuilderPackedSourceLayout.CANONICAL_VIDEO_ROOT + "/";
		if (spec.targetPath.startsWith(video)) return new Spec(spec.role,
			layout.path(spec.targetPath.substring(video.length())),
			spec.runtimePath, spec.mediaType, spec.definition);
		String definitions = WorldBuilderPackedSourceLayout.CANONICAL_DEFINITION_ROOT + "/";
		if (spec.targetPath.startsWith(definitions)) return new Spec(spec.role,
			layout.definitionPath(spec.targetPath.substring(definitions.length())),
			spec.runtimePath, spec.mediaType, spec.definition);
		return spec;
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

	static Bundle capture(Path projectStage, Path copiedTarget,
		WorldBuilderAdaptiveRuntimePreparer.SourceRuntime runtime)
		throws IOException, WorldBuilderContractException {
		return capture(projectStage, copiedTarget, runtime, null);
	}

	static Bundle capture(Path projectStage, Path copiedTarget,
		WorldBuilderAdaptiveRuntimePreparer.SourceRuntime runtime, Path explicitMappings)
		throws IOException, WorldBuilderContractException {
		Path sourceRoot = projectStage.resolve(SOURCE_DIRECTORY).normalize();
		if (!sourceRoot.startsWith(projectStage.toAbsolutePath().normalize())
			|| Files.exists(sourceRoot, LinkOption.NOFOLLOW_LINKS)) {
			throw problem(WorldBuilderErrorCodes.UNSAFE_PATH, SOURCE_DIRECTORY,
				"Project content-bundle destination is unsafe or already exists.",
				"Discard the unpublished project stage and retry.");
		}
		Files.createDirectories(sourceRoot);
		Map<String,Object> targetCatalog = deriveCatalog(copiedTarget,
			"target-adopted-content-v2");
		WorldBuilderNpcDefinitionProvider.Result npcMigration =
			WorldBuilderNpcDefinitionProvider.consume(
				explicitMappings, copiedTarget, targetCatalog);
		Map<String,Object> packagedCatalog =
			WorldBuilderStandaloneDefinitionCatalog.generate(runtime,
				"packaged-content-comparison-v1");
		Set<Integer> beyondPackaged = differenceIds(
			targetCatalog.get("groundItems"), packagedCatalog.get("groundItems"));
		List<Object> itemVisuals;
		ItemVisualMigration migration = null;
		boolean successor = !beyondPackaged.isEmpty();
		if (successor) {
			migration = migrateItemVisuals(copiedTarget,
				beyondPackaged, explicitMappings);
			itemVisuals = migration.itemVisuals;
		} else {
			itemVisuals = Collections.emptyList();
		}
		List<Spec> captureSpecs = new ArrayList<Spec>(SPECS);
		if (successor) captureSpecs.add(ITEM_VISUAL_SPEC);
		List<FileRecord> records = new ArrayList<FileRecord>();
		for (Spec spec : captureSpecs) {
			String bundlePath = "files/" + spec.runtimePath;
			Path destination = sourceRoot.resolve(bundlePath).normalize();
			if (!destination.startsWith(sourceRoot)) throw unsafe(bundlePath);
			Files.createDirectories(destination.getParent());
			Path source = copiedTarget.resolve(spec.targetPath);
			if (spec == ITEM_VISUAL_SPEC
				&& !Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) {
				Map<String,Object> generated = new LinkedHashMap<String,Object>();
				generated.put("schemaVersion", Long.valueOf(1L));
				generated.put("manifestType", "world-builder-item-visual-evidence");
				generated.put("itemVisuals", new ArrayList<Object>(itemVisuals));
				Files.write(destination, WorldBuilderJsonDocuments.pretty(generated)
					.getBytes(StandardCharsets.UTF_8));
			} else {
				source = safeRegular(source, spec.targetPath);
				validateFile(source, spec);
				Files.copy(source, destination, StandardCopyOption.COPY_ATTRIBUTES);
			}
			boolean overridden = false;
			if (npcMigration.changed() && "definition.npc.custom".equals(spec.role)) {
				Files.write(destination, npcMigration.customDefinitions);
				overridden = true;
			} else if (migration != null && "asset.sprite.custom".equals(spec.role)
				&& migration.customArchiveOverride != null) {
				Files.write(destination, migration.customArchiveOverride);
				overridden = true;
			} else if (migration != null && "asset.sprite.authentic".equals(spec.role)
				&& migration.authenticArchiveOverride != null) {
				Files.write(destination, migration.authenticArchiveOverride);
				overridden = true;
			}
			long size = Files.size(destination);
			String hash = WorldBuilderHashes.sha256(destination);
			if (!overridden && Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)
				&& (size != Files.size(source)
					|| !hash.equals(WorldBuilderHashes.sha256(source)))) {
				throw problem(WorldBuilderErrorCodes.SOURCE_CORRUPT, spec.targetPath,
					"Target content changed while its project-local copy was verified.",
					"Stop target changes, rediscover, and create a new project.");
			}
			records.add(new FileRecord(spec, bundlePath, size, hash,
				mediaType(spec, source, successor)));
		}
		if (migration != null && migration.provider != null) {
			WorldBuilderItemVisualProvider.writeReport(projectStage, migration.provider);
		}
		WorldBuilderNpcDefinitionProvider.writeReport(projectStage, npcMigration);
		Collections.sort(records);
		Map<String,Object> catalog = deriveCatalog(sourceRoot,
			successor ? "target-adopted-content-v2" : "target-adopted-content-v1");
		if (successor) validateItemVisualArchiveClosure(sourceRoot, itemVisuals);
		int version = successor ? 2 : 1;
		String definitions = fingerprint(
			"world-builder-project-content-definitions-v" + version + "\n", records, true,
			(String)catalog.get("catalogSha256"));
		String assets = fingerprint(
			"world-builder-project-content-assets-v" + version + "\n", records, false, "");
		String visualHash = itemVisualFingerprint(version, itemVisuals);
		Map<String,Object> manifest = manifest(version, catalog, itemVisuals, records,
			definitions, assets, visualHash);
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
		long version = integer(manifest, "schemaVersion");
		if (version == 1L) {
			exact(manifest, "schemaVersion", "manifestType", "capabilityId", "sourceKind",
				"definitionCatalog", "familyBindings", "files",
				"definitionFingerprintSha256", "assetFingerprintSha256",
				"bundleFingerprintSha256");
		} else if (version == 2L) {
			exact(manifest, "schemaVersion", "manifestType", "capabilityId", "sourceKind",
				"definitionCatalog", "familyBindings", "itemVisuals", "files",
				"definitionFingerprintSha256", "assetFingerprintSha256",
				"itemVisualFingerprintSha256", "bundleFingerprintSha256");
		}
		String capabilityId = version == 1L ? LEGACY_CAPABILITY_ID : CAPABILITY_ID;
		if (!(version == 1L || version == 2L)
			|| !TYPE.equals(manifest.get("manifestType"))
			|| !capabilityId.equals(manifest.get("capabilityId"))
			|| !("target-adopted".equals(manifest.get("sourceKind"))
				|| "content-neutral-default".equals(manifest.get("sourceKind")))) {
			throw problem(WorldBuilderErrorCodes.UNSUPPORTED_CONTRACT_VERSION, MANIFEST,
				"Project custom-content identity or version is unsupported.",
				"Use world-builder-project-content-bundle schema version 1 or 2.");
		}
		Map<String,Object> catalog = object(manifest.get("definitionCatalog"),
			"definitionCatalog");
		validateCatalogShape(catalog);
		List<FileRecord> records = parseFiles(root, manifest.get("files"), (int)version);
		List<Object> itemVisuals = version == 2L
			? parseItemVisuals(manifest.get("itemVisuals"), "itemVisuals")
			: Collections.<Object>emptyList();
		validateFamilies(manifest.get("familyBindings"), records, (int)version, itemVisuals);
		Map<String,Object> derived = deriveCatalog(root, (String)catalog.get("catalogId"));
		if (!catalog.equals(derived)) throw problem(WorldBuilderErrorCodes.DEFINITION_MISMATCH,
			MANIFEST, "Bundle authoring IDs do not exactly match adopted definitions.",
			"Recreate the project from one stable complete target content set.");
		String definitionHash = fingerprint(
			"world-builder-project-content-definitions-v" + version + "\n", records, true,
			(String)catalog.get("catalogSha256"));
		String assetHash = fingerprint(
			"world-builder-project-content-assets-v" + version + "\n", records, false, "");
		String visualHash = itemVisualFingerprint((int)version, itemVisuals);
		if (!definitionHash.equals(manifest.get("definitionFingerprintSha256"))
			|| !assetHash.equals(manifest.get("assetFingerprintSha256"))
			|| version == 2L
				&& !visualHash.equals(manifest.get("itemVisualFingerprintSha256"))) {
			throw problem(WorldBuilderErrorCodes.CAPABILITY_MISMATCH, MANIFEST,
				"Bundle definition or client-asset fingerprint is inconsistent.",
				"Restore the exact complete project-local content bundle.");
		}
		if (version == 2L) {
			Map<String,Object> evidence = readItemVisualEvidence(
				contentPath(root, "metadata.item-visuals"));
			if (!itemVisuals.equals(evidence.get("itemVisuals"))) throw problem(
				WorldBuilderErrorCodes.DEFINITION_MISMATCH, ITEM_VISUAL_EVIDENCE_PATH,
				"Manifest item visuals differ from their preserved declarative evidence.",
				"Restore one exact successor bundle from its immutable source.");
			validateItemVisualCatalogClosure(itemVisuals, catalog);
			validateItemVisualArchiveClosure(root, itemVisuals);
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
		return new Bundle(root, capabilityId, catalog, itemVisuals, records,
			definitionHash, assetHash, visualHash, expectedBundle);
	}

	private static Map<String,Object> manifest(int version, Map<String,Object> catalog,
		List<Object> itemVisuals, List<FileRecord> records, String definitions,
		String assets, String visualHash)
		throws WorldBuilderContractException {
		Map<String,Object> value = new LinkedHashMap<String,Object>();
		value.put("schemaVersion", Long.valueOf(version));
		value.put("manifestType", TYPE);
		value.put("capabilityId", version == 1 ? LEGACY_CAPABILITY_ID : CAPABILITY_ID);
		value.put("sourceKind", "target-adopted");
		value.put("definitionCatalog", catalog);
		value.put("familyBindings", familyBindings());
		if (version == 2) value.put("itemVisuals", new ArrayList<Object>(itemVisuals));
		List<Object> files = new ArrayList<Object>();
		for (FileRecord record : records) files.add(record.toJson());
		value.put("files", files);
		value.put("definitionFingerprintSha256", definitions);
		value.put("assetFingerprintSha256", assets);
		if (version == 2) value.put("itemVisualFingerprintSha256", visualHash);
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

	private static void validateFamilies(Object raw, List<FileRecord> records,
		int version, List<Object> itemVisuals)
		throws WorldBuilderContractException {
		List<?> values = array(raw, "familyBindings", 5, 5);
		List<Object> expected = familyBindings();
		if (!values.equals(expected)) throw problem(WorldBuilderErrorCodes.CONTRACT_VALUE_INVALID,
			MANIFEST, "Content family bindings are incomplete or noncanonical.",
			"Use the exact five canonical family bindings.");
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
		if (version == 2) {
			Set<String> dependencies = new HashSet<String>();
			for (Object rawVisual : itemVisuals) {
				Map<String,Object> visual = object(rawVisual, "itemVisual");
				if (visual.get("authenticSpriteId") != null) {
					dependencies.add("asset.sprite.authentic");
				} else dependencies.add(string(visual, "customSpriteAssetRole"));
			}
			for (String dependency : dependencies) if (!present.contains(dependency)) {
				throw problem(WorldBuilderErrorCodes.CAPABILITY_MISMATCH, MANIFEST,
					"Item visual metadata references a missing client archive role.",
					"Capture the exact referenced client asset dependency closure.");
			}
		}
	}

	private static List<FileRecord> parseFiles(Path root, Object raw, int version)
		throws IOException, WorldBuilderContractException {
		int expectedCount = SPECS.size() + (version == 2 ? 1 : 0);
		List<?> values = array(raw, "files", expectedCount, 256);
		if (values.size() != expectedCount) throw problem(
			WorldBuilderErrorCodes.CAPABILITY_MISMATCH, MANIFEST,
			"Target bundle is missing a required declarative content role.",
			"Capture all required target definitions and client assets.");
		Map<String,Spec> specs = new HashMap<String,Spec>();
		for (Spec spec : SPECS) specs.put(spec.role, spec);
		if (version == 2) specs.put(ITEM_VISUAL_SPEC.role, ITEM_VISUAL_SPEC);
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
			String mediaType = string(record, "mediaType");
			if (spec == null || !roles.add(role) || !spec.runtimePath.equals(runtimePath)
				|| !validMediaType(spec, mediaType, version)
				|| !("files/" + runtimePath).equals(bundlePath)
				|| previous.compareTo(runtimePath) >= 0) {
				throw problem(WorldBuilderErrorCodes.CONTRACT_VALUE_INVALID, MANIFEST,
					"Content file roles or compiled runtime destinations are invalid.",
					"Use each exact versioned role/path mapping once in canonical order.");
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
			result.add(new FileRecord(spec, bundlePath, size, hash, mediaType));
			previous = runtimePath;
		}
		if (!roles.equals(specs.keySet())) throw problem(WorldBuilderErrorCodes.CAPABILITY_MISMATCH,
			MANIFEST, "Content role closure is incomplete.",
			"Capture every required versioned definition, metadata, and client asset role.");
		return result;
	}

	private static Map<String,Object> deriveCatalog(Path root, String catalogId)
		throws IOException, WorldBuilderContractException {
		return deriveCatalog(root, catalogId, null);
	}

	private static Map<String,Object> deriveCatalog(Path root, String catalogId,
		WorldBuilderPackedSourceLayout layout)
		throws IOException, WorldBuilderContractException {
		List<Object> tiles = range(rawByteXmlCount(root, "definition.tile",
			"TileDef-array", "TileDef", layout));
		List<Object> boundaries = range(rawByteXmlCount(root, "definition.boundary",
			"DoorDef-array", "DoorDef", layout));
		List<Object> scenery = range(xmlCount(root, "definition.scenery",
			"GameObjectDef-array", "GameObjectDef", layout));
		Set<Integer> npcIds = new TreeSet<Integer>();
		int appendedNpcCount = jsonCount(root, "definition.npc.base", layout, "npcs")
			+ jsonCount(root, "definition.npc.custom", layout, "npcs");
		for (int id = 0; id < appendedNpcCount; id++) npcIds.add(Integer.valueOf(id));
		npcIds.addAll(jsonIds(root, "definition.npc.world", layout, "npcs"));
		npcIds.addAll(jsonIds(root, "definition.npc.patch", layout, "npcs"));
		Set<Integer> itemIds = new TreeSet<Integer>();
		itemIds.addAll(jsonIds(root, "definition.item.base", layout, "item"));
		itemIds.addAll(jsonIds(root, "definition.item.custom", layout, "items"));
		itemIds.addAll(jsonIds(root, "definition.item.world", layout, "items"));
		itemIds.addAll(jsonIds(root, "definition.item.patch", layout, "items", "item"));
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

	private static int rawByteXmlCount(Path root, String role, String rootName,
		String element) throws IOException, WorldBuilderContractException {
		return rawByteXmlCount(root, role, rootName, element, null);
	}

	private static int rawByteXmlCount(Path root, String role, String rootName,
		String element, WorldBuilderPackedSourceLayout layout)
		throws IOException, WorldBuilderContractException {
		int count = xmlCount(root, role, rootName, element, layout);
		if (count > MAX_RAW_BYTE_ID + 1) throw problem(
			WorldBuilderErrorCodes.CONTRACT_LIMIT_EXCEEDED, role,
			"Floor and boundary definitions exceed their one-byte raw ID domain 0..254.",
			"Reduce the declarative family to at most 255 indexed definitions; raw value 255 is reserved.");
		return count;
	}

	private static int xmlCount(Path root, String role, String rootName, String element)
		throws IOException, WorldBuilderContractException {
		return xmlCount(root, role, rootName, element, null);
	}

	private static int xmlCount(Path root, String role, String rootName, String element,
		WorldBuilderPackedSourceLayout layout)
		throws IOException, WorldBuilderContractException {
		Path path = contentPath(root, role, layout);
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
		return jsonCount(root, role, null, arrayName);
	}

	private static int jsonCount(Path root, String role,
		WorldBuilderPackedSourceLayout layout, String arrayName)
		throws IOException, WorldBuilderContractException {
		return jsonArray(root, role, layout, arrayName).size();
	}

	private static Set<Integer> jsonIds(Path root, String role, String... arrayNames)
		throws IOException, WorldBuilderContractException {
		return jsonIds(root, role, null, arrayNames);
	}

	private static Set<Integer> jsonIds(Path root, String role,
		WorldBuilderPackedSourceLayout layout, String... arrayNames)
		throws IOException, WorldBuilderContractException {
		Set<Integer> result = new TreeSet<Integer>();
		for (Object raw : jsonArray(root, role, layout, arrayNames)) {
			if (!(raw instanceof Map)) throw malformedDefinition(role);
			@SuppressWarnings("unchecked") Map<String,Object> value = (Map<String,Object>)raw;
			Object id = value.get("id");
				if (!(id instanceof Long) || ((Long)id).longValue() < 0L
					|| ((Long)id).longValue() > MAX_RUNTIME_ID
				|| !result.add(Integer.valueOf((int)((Long)id).longValue()))) {
				throw malformedDefinition(role);
			}
		}
		return result;
	}

	private static List<?> jsonArray(Path root, String role, String... arrayNames)
		throws IOException, WorldBuilderContractException {
		return jsonArray(root, role, null, arrayNames);
	}

	private static List<?> jsonArray(Path root, String role,
		WorldBuilderPackedSourceLayout layout, String... arrayNames)
		throws IOException, WorldBuilderContractException {
		Map<String,Object> value;
		try {
			value = WorldBuilderJsonDocuments.readTargetDefinitionObject(
				contentPath(root, role, layout));
		} catch (WorldBuilderDiscoveryException malformed) {
			throw malformedDefinition(role, malformed);
		}
		Object entries = null;
		if (value.size() == 1) {
			for (String arrayName : arrayNames) {
				if (value.containsKey(arrayName)) entries = value.get(arrayName);
			}
		}
		if (!(entries instanceof List)
			|| ((List<?>)entries).size() > MAX_DEFINITIONS) {
			throw malformedDefinition(role);
		}
		return (List<?>)entries;
	}

	private static Path contentPath(Path root, String role)
		throws WorldBuilderContractException {
		return contentPath(root, role, null);
	}

	private static Path contentPath(Path root, String role,
		WorldBuilderPackedSourceLayout layout) throws WorldBuilderContractException {
		List<Spec> specs = new ArrayList<Spec>(SPECS);
		specs.add(ITEM_VISUAL_SPEC);
		for (Spec spec : specs) if (role.equals(spec.role)) {
			Path bundled = root.resolve("files/" + spec.runtimePath);
			return Files.isRegularFile(bundled, LinkOption.NOFOLLOW_LINKS)
				? bundled : root.resolve(layout == null
					? spec.targetPath : sourceSpec(spec, layout).targetPath);
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

	private static String mediaType(Spec spec, Path path, boolean successor)
		throws IOException {
		return spec.mediaType;
	}

	private static boolean validMediaType(Spec spec, String mediaType, int version) {
		return spec.mediaType.equals(mediaType);
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

	/**
	 * Resolves successor item visuals without ever writing to the captured target
	 * tree.  Existing v1 evidence is authoritative.  Otherwise only complete
	 * declarative records and an optional explicit mapping contract participate.
	 */
	private static ItemVisualMigration migrateItemVisuals(Path copiedTarget,
		Set<Integer> required, Path explicitMappings)
		throws IOException, WorldBuilderContractException {
		Map<Integer,String> itemNames = targetItemNames(copiedTarget, required);
		if (explicitMappings != null) {
			if (isLegacyItemVisualMapping(explicitMappings)) {
				Map<String,Object> mapping = readExplicitItemVisualMappings(explicitMappings);
				List<Object> visuals = parseItemVisuals(mapping.get("itemVisuals"),
					"legacy item visual mapping input");
				requireExactVisualClosure(visuals, required);
				validateItemVisualArchiveClosure(copiedTarget, visuals);
				return new ItemVisualMigration(visuals, null);
			}
			WorldBuilderItemVisualProvider.Result provider =
				WorldBuilderItemVisualProvider.consume(explicitMappings, copiedTarget,
					required, itemNames);
			return new ItemVisualMigration(provider.itemVisuals, provider);
		}
		Path existing = copiedTarget.resolve(ITEM_VISUAL_EVIDENCE_PATH);
		if (Files.isRegularFile(existing, LinkOption.NOFOLLOW_LINKS)) {
			List<Object> visuals = validateItemVisualEvidence(existing);
			requireExactVisualClosure(visuals, required);
			validateItemVisualArchiveClosure(copiedTarget, visuals);
			return new ItemVisualMigration(visuals, null);
		}

		Map<Integer,Map<String,Object>> resolved =
			new TreeMap<Integer,Map<String,Object>>();
		Map<String,SpriteArchive> archives = new HashMap<String,SpriteArchive>();
		for (String role : Arrays.asList("asset.sprite.custom", "asset.spritepack")) {
			archives.put(role, readSpriteArchive(contentPath(copiedTarget, role), role));
		}
		Set<Integer> authenticIds = authenticSpriteIds(
			contentPath(copiedTarget, "asset.sprite.authentic"));
		for (String role : Arrays.asList("definition.item.base", "definition.item.custom",
			"definition.item.world", "definition.item.patch")) {
			for (Object raw : jsonArray(copiedTarget, role,
				"definition.item.base".equals(role) ? new String[] {"item"}
					: "definition.item.patch".equals(role)
						? new String[] {"items", "item"} : new String[] {"items"})) {
				@SuppressWarnings("unchecked") Map<String,Object> definition =
					(Map<String,Object>)raw;
				int itemId = (int)((Long)definition.get("id")).longValue();
				if (!required.contains(Integer.valueOf(itemId))) continue;
				Map<String,Object> visual = declarativeVisual(
					definition, itemId, archives, authenticIds, role);
				if (visual != null) mergeVisual(resolved, visual, role);
			}
		}

		if (explicitMappings != null) {
			Map<String,Object> mapping = readExplicitItemVisualMappings(explicitMappings);
			for (Object raw : parseItemVisuals(mapping.get("itemVisuals"),
				"item visual mapping input")) {
				Map<String,Object> visual = object(raw, "item visual mapping input");
				int itemId = (int)integer(visual, "itemId");
				if (!required.contains(Integer.valueOf(itemId))) throw visualProblem(
					"Explicit mappings may contain only unresolved beyond-packaged item IDs.");
				mergeVisual(resolved, visual, "explicit item visual mapping");
			}
		}

		Set<Integer> unresolved = new TreeSet<Integer>(required);
		unresolved.removeAll(resolved.keySet());
		WorldBuilderItemVisualProvider.Result provider = null;
		if (!unresolved.isEmpty()) {
			provider = WorldBuilderItemVisualProvider.consume(null, copiedTarget,
				unresolved, itemNames);
			for (Object raw : provider.itemVisuals) {
				Map<String,Object> visual = object(raw, "generated item visual placeholder");
				mergeVisual(resolved, visual, "generated item visual placeholder");
			}
		}
		List<Object> visuals = new ArrayList<Object>();
		for (Map<String,Object> visual : resolved.values()) {
			visuals.add(new LinkedHashMap<String,Object>(visual));
		}
		requireExactVisualClosure(visuals, required);
		return new ItemVisualMigration(visuals, provider);
	}

	private static boolean isLegacyItemVisualMapping(Path requested) {
		try {
			Map<String,Object> value = WorldBuilderJsonDocuments.readObject(
				requested.toAbsolutePath().normalize());
			Object raw = value.get("itemVisuals");
			if (!(raw instanceof List) || ((List<?>)raw).isEmpty()
				|| !(((List<?>)raw).get(0) instanceof Map)) return false;
			@SuppressWarnings("unchecked") Map<String,Object> first =
				(Map<String,Object>)((List<?>)raw).get(0);
			return first.containsKey("authenticSpriteId") && !first.containsKey("name")
				&& first.keySet().equals(new HashSet<String>(Arrays.asList("itemId",
					"authenticSpriteId", "customSpriteAssetRole", "customSpriteSubspace",
					"customSpriteEntry", "pictureMask", "blueMask")));
		} catch (Exception invalidOrUnreadable) {
			return false;
		}
	}

	private static Map<Integer,String> targetItemNames(Path root, Set<Integer> required)
		throws IOException, WorldBuilderContractException {
		Map<Integer,String> result = new TreeMap<Integer,String>();
		for (String role : Arrays.asList("definition.item.base", "definition.item.custom",
			"definition.item.world", "definition.item.patch")) {
			for (Object raw : jsonArray(root, role,
				"definition.item.base".equals(role) ? new String[] {"item"}
					: "definition.item.patch".equals(role)
						? new String[] {"items", "item"} : new String[] {"items"})) {
				@SuppressWarnings("unchecked") Map<String,Object> definition =
					(Map<String,Object>)raw;
				int itemId = (int)((Long)definition.get("id")).longValue();
				if (!required.contains(Integer.valueOf(itemId))) continue;
				Object name = definition.get("name");
				String resolved = name instanceof String && !((String)name).trim().isEmpty()
					? (String)name : "Item " + itemId;
				// Later declarative patch/world records are the effective name, matching
				// the catalog's established override order for a repeated item ID.
				result.put(Integer.valueOf(itemId), resolved);
			}
		}
		return result;
	}

	private static Map<String,Object> declarativeVisual(Map<String,Object> definition,
		int itemId, Map<String,SpriteArchive> archives, Set<Integer> authenticIds,
		String role)
		throws WorldBuilderContractException {
		Object nested = definition.get("worldBuilderItemVisual");
		if (nested != null) {
			Map<String,Object> value = object(nested, role);
			Map<String,Object> visual = new LinkedHashMap<String,Object>(value);
			if (!visual.containsKey("itemId")) visual.put("itemId", Long.valueOf(itemId));
			List<Object> parsed = parseItemVisuals(
				Arrays.<Object>asList(visual), role);
			Map<String,Object> result = object(parsed.get(0), role);
			if (integer(result, "itemId") != itemId) throw visualProblem(
				"Nested declarative visual itemId differs from its definition ID.");
			if (result.get("authenticSpriteId") instanceof Long
				&& !authenticIds.contains(Integer.valueOf(
					(int)((Long)result.get("authenticSpriteId")).longValue()))) return null;
			return result;
		}

		boolean mentionsVisual = definition.containsKey("sprite")
			|| definition.containsKey("authenticSpriteId")
			|| definition.containsKey("customSpriteAssetRole")
			|| definition.containsKey("customSpriteSubspace")
			|| definition.containsKey("customSpriteEntry")
			|| definition.containsKey("pictureMask")
			|| definition.containsKey("blueMask");
		if (!mentionsVisual) return null;
		Object pictureMask = definition.get("pictureMask");
		Object blueMask = definition.get("blueMask");
		if (!(pictureMask instanceof Long) || !(blueMask instanceof Long)) return null;
		Map<String,Object> visual = new LinkedHashMap<String,Object>();
		visual.put("itemId", Long.valueOf(itemId));
		Object authentic = definition.get("authenticSpriteId");
		Object customRole = definition.get("customSpriteAssetRole");
		Object subspace = definition.get("customSpriteSubspace");
		Object entry = definition.get("customSpriteEntry");
		Object sprite = definition.get("sprite");
		if (authentic == null && customRole == null && subspace == null && entry == null) {
			if (sprite instanceof Long) authentic = sprite;
			else if (sprite instanceof String) {
				String location = (String)sprite;
				WorldBuilderPortablePath.require(location, OPERATION);
				int separator = location.lastIndexOf('/');
				if (separator <= 0 || separator == location.length() - 1) return null;
				subspace = location.substring(0, separator);
				entry = location.substring(separator + 1);
				List<String> owners = new ArrayList<String>();
				for (String candidate : Arrays.asList(
					"asset.sprite.custom", "asset.spritepack")) {
					if (archives.get(candidate).exactNames.contains(location)) owners.add(candidate);
				}
				if (owners.size() != 1) throw problem(
					WorldBuilderErrorCodes.DEFINITION_MISMATCH, role,
					"Declarative sprite location is missing or archive-role ambiguous: "
						+ location + ".",
					"Use one exact case-sensitive entry in one archive, or provide an explicit role mapping.");
				customRole = owners.get(0);
			} else return null;
		}
		visual.put("authenticSpriteId", authentic);
		visual.put("customSpriteAssetRole", customRole);
		visual.put("customSpriteSubspace", subspace);
		visual.put("customSpriteEntry", entry);
		visual.put("pictureMask", pictureMask);
		visual.put("blueMask", blueMask);
		Map<String,Object> result = object(
			parseItemVisuals(Arrays.<Object>asList(visual), role).get(0), role);
		if (result.get("authenticSpriteId") instanceof Long
			&& !authenticIds.contains(Integer.valueOf(
				(int)((Long)result.get("authenticSpriteId")).longValue()))) return null;
		return result;
	}

	private static Set<Integer> authenticSpriteIds(Path path)
		throws IOException, WorldBuilderContractException {
		Set<Integer> result = new HashSet<Integer>();
		try (ZipFile archive = new ZipFile(path.toFile())) {
			java.util.Enumeration<? extends ZipEntry> entries = archive.entries();
			int count = 0;
			while (entries.hasMoreElements()) {
				ZipEntry entry = entries.nextElement();
				if (++count > MAX_ARCHIVE_ENTRIES) throw tooManyDefinitions(
					"asset.sprite.authentic");
				if (entry.isDirectory()) continue;
				String name = entry.getName();
				WorldBuilderPortablePath.require(name, OPERATION);
				String[] components = name.split("/");
				String leaf = components[components.length - 1];
				if (leaf.endsWith(".dat")) leaf = leaf.substring(0, leaf.length() - 4);
				if (!leaf.matches("[0-9]{1,5}")) continue;
				int id = Integer.parseInt(leaf);
				if (id <= MAX_RUNTIME_ID) result.add(Integer.valueOf(id));
			}
		} catch (WorldBuilderContractException invalid) {
			throw invalid;
		} catch (IOException unsupported) {
			// A captured authentic archive without inspectable named entries cannot
			// prove an automatic numeric mapping. Explicit/static evidence remains valid.
			return Collections.emptySet();
		}
		return result;
	}

	private static void mergeVisual(Map<Integer,Map<String,Object>> resolved,
		Map<String,Object> visual, String source) throws WorldBuilderContractException {
		int itemId = (int)integer(visual, "itemId");
		Map<String,Object> previous = resolved.get(Integer.valueOf(itemId));
		if (previous != null && !previous.equals(visual)) throw problem(
			WorldBuilderErrorCodes.DEFINITION_MISMATCH, source,
			"More than one contradictory item visual mapping exists for item "
				+ itemId + ".",
			"Keep one provable mapping and exact mask pair for each item ID.");
		if (previous == null) resolved.put(Integer.valueOf(itemId),
			new LinkedHashMap<String,Object>(visual));
	}

	private static Map<String,Object> readExplicitItemVisualMappings(Path requested)
		throws WorldBuilderContractException {
		Path path = requested.toAbsolutePath().normalize();
		try {
			if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
				|| Files.isSymbolicLink(path) || Files.size(path) < 1L
				|| Files.size(path) > MAX_FILE_BYTES) throw visualProblem(
					"Explicit item visual mapping input must be one bounded regular JSON file.");
			Map<String,Object> value = WorldBuilderJsonDocuments.readObject(path);
			exact(value, "schemaVersion", "manifestType", "itemVisuals");
			if (!Long.valueOf(1L).equals(value.get("schemaVersion"))
				|| !"world-builder-item-visual-mapping".equals(value.get("manifestType"))) {
				throw visualProblem("Explicit item visual mapping identity is unsupported.");
			}
			parseItemVisuals(value.get("itemVisuals"), "item visual mapping input");
			return value;
		} catch (WorldBuilderContractException invalid) {
			throw invalid;
		} catch (IOException changed) {
			throw problem(WorldBuilderErrorCodes.DISCOVERY_DRIFT,
				"item visual mapping input",
				"Explicit item visual mapping input changed or could not be read.",
				"Use one stable bounded mapping file and retry.", changed);
		} catch (WorldBuilderDiscoveryException malformed) {
			throw problem(WorldBuilderErrorCodes.MALFORMED_JSON,
				"item visual mapping input",
				"Explicit item visual mapping input is malformed JSON.",
				"Use the strict world-builder-item-visual-mapping schema version 1.", malformed);
		}
	}

	private static List<Object> validateItemVisualEvidence(Path path)
		throws WorldBuilderContractException {
		return parseItemVisuals(readItemVisualEvidence(path).get("itemVisuals"),
			ITEM_VISUAL_EVIDENCE_PATH);
	}

	private static Map<String,Object> readItemVisualEvidence(Path path)
		throws WorldBuilderContractException {
		Map<String,Object> value;
		try {
			value = WorldBuilderJsonDocuments.readObject(path);
		} catch (IOException failure) {
			throw problem(WorldBuilderErrorCodes.DISCOVERY_DRIFT,
				ITEM_VISUAL_EVIDENCE_PATH,
				"Static item visual evidence changed while it was read.",
				"Stop target changes and retry discovery.", failure);
		} catch (WorldBuilderDiscoveryException malformed) {
			throw problem(WorldBuilderErrorCodes.MALFORMED_JSON,
				ITEM_VISUAL_EVIDENCE_PATH,
				"Static item visual evidence is malformed JSON.",
				"Provide the exact world-builder-item-visual-evidence schema version 1.",
				malformed);
		}
		exact(value, "schemaVersion", "manifestType", "itemVisuals");
		if (!Long.valueOf(1L).equals(value.get("schemaVersion"))
			|| !"world-builder-item-visual-evidence".equals(value.get("manifestType"))) {
			throw problem(WorldBuilderErrorCodes.UNSUPPORTED_CONTRACT_VERSION,
				ITEM_VISUAL_EVIDENCE_PATH,
				"Static item visual evidence identity is unsupported.",
				"Provide world-builder-item-visual-evidence schema version 1.");
		}
		parseItemVisuals(value.get("itemVisuals"), ITEM_VISUAL_EVIDENCE_PATH);
		return value;
	}

	private static List<Object> parseItemVisuals(Object raw, String label)
		throws WorldBuilderContractException {
		List<?> values = array(raw, label, 1, MAX_DEFINITIONS);
		List<Object> result = new ArrayList<Object>(values.size());
		long previous = -1L;
		for (Object rawVisual : values) {
			Map<String,Object> visual = object(rawVisual, label);
			exact(visual, "itemId", "authenticSpriteId", "customSpriteAssetRole",
				"customSpriteSubspace", "customSpriteEntry", "pictureMask", "blueMask");
			long itemId = integer(visual, "itemId");
			if (itemId <= previous || itemId > MAX_RUNTIME_ID) {
				throw visualProblem("Item visual IDs must be unique, ascending, and within 0..65535.");
			}
			previous = itemId;
			Object authentic = visual.get("authenticSpriteId");
			Object role = visual.get("customSpriteAssetRole");
			Object subspace = visual.get("customSpriteSubspace");
			Object entry = visual.get("customSpriteEntry");
			boolean authenticMapping = authentic instanceof Long
				&& role == null && subspace == null && entry == null;
			boolean customMapping = authentic == null && role instanceof String
				&& subspace instanceof String && entry instanceof String;
			if (authenticMapping) {
				long spriteId = ((Long)authentic).longValue();
				if (spriteId < 0L || spriteId > MAX_RUNTIME_ID) {
					throw visualProblem("Authentic sprite IDs must be within 0..65535.");
				}
			} else if (customMapping) {
				String archiveRole = (String)role;
				if (!("asset.sprite.custom".equals(archiveRole)
					|| "asset.spritepack".equals(archiveRole))) {
					throw visualProblem("Custom item visuals must select asset.sprite.custom or asset.spritepack.");
				}
				String combined = (String)subspace + "/" + (String)entry;
				WorldBuilderPortablePath.require((String)subspace, OPERATION);
				WorldBuilderPortablePath.require((String)entry, OPERATION);
				WorldBuilderPortablePath.require(combined, OPERATION);
			} else {
				throw visualProblem("Each item visual must select exactly one authentic or custom archive mapping.");
			}
			for (String mask : Arrays.asList("pictureMask", "blueMask")) {
				long number = integer(visual, mask);
				if (number < Integer.MIN_VALUE || number > Integer.MAX_VALUE) {
					throw visualProblem("Item recolor masks must be signed 32-bit integers.");
				}
			}
			result.add(new LinkedHashMap<String,Object>(visual));
		}
		return result;
	}

	private static void requireExactVisualClosure(List<Object> visuals,
		Set<Integer> beyondPackaged) throws WorldBuilderContractException {
		Set<Integer> actual = new TreeSet<Integer>();
		for (Object raw : visuals) {
			actual.add(Integer.valueOf((int)integer(object(raw, "itemVisual"), "itemId")));
		}
		if (!actual.equals(beyondPackaged)) throw problem(
			WorldBuilderErrorCodes.CONVERSION_BLOCKED, ITEM_VISUAL_EVIDENCE_PATH,
			"Static item visual evidence does not exactly cover the beyond-packaged ground-item definitions.",
			"Add each missing beyond-packaged item once and remove packaged, unknown, or duplicate item records.");
	}

	private static void validateItemVisualCatalogClosure(List<Object> visuals,
		Map<String,Object> catalog) throws WorldBuilderContractException {
		Set<Integer> items = idSet(catalog.get("groundItems"));
		for (Object raw : visuals) {
			int itemId = (int)integer(object(raw, "itemVisual"), "itemId");
			if (!items.contains(Integer.valueOf(itemId))) throw visualProblem(
				"Item visual metadata references a ground-item ID absent from the definition catalog.");
		}
	}

	private static void validateItemVisualArchiveClosure(Path root, List<Object> visuals)
		throws IOException, WorldBuilderContractException {
		Map<String,SpriteArchive> archives = new HashMap<String,SpriteArchive>();
		for (String role : Arrays.asList("asset.sprite.custom", "asset.spritepack")) {
			archives.put(role, readSpriteArchive(contentPath(root, role), role));
		}
		for (Object raw : visuals) {
			Map<String,Object> visual = object(raw, "itemVisual");
			if (visual.get("authenticSpriteId") != null) continue;
			String role = string(visual, "customSpriteAssetRole");
			String required = string(visual, "customSpriteSubspace") + "/"
				+ string(visual, "customSpriteEntry");
			SpriteArchive selected = archives.get(role);
			if (!selected.exactNames.contains(required)) throw problem(
				WorldBuilderErrorCodes.DEFINITION_MISMATCH, role,
				"Custom item visual archive entry is missing: " + required + ".",
				"Add the exact declared entry to the selected target archive or correct the static mapping.");
			String otherRole = "asset.sprite.custom".equals(role)
				? "asset.spritepack" : "asset.sprite.custom";
			if (archives.get(otherRole).caseFoldedNames.contains(
				required.toLowerCase(Locale.ROOT))) throw problem(
				WorldBuilderErrorCodes.DEFINITION_MISMATCH, role,
				"Custom item visual archive mapping is role-ambiguous: " + required + ".",
				"Keep the declared subspace and entry in exactly one selected sprite archive role.");
		}
	}

	private static SpriteArchive readSpriteArchive(Path path, String role)
		throws IOException, WorldBuilderContractException {
		byte[] expanded;
		try (InputStream input = new GZIPInputStream(Files.newInputStream(path));
			ByteArrayOutputStream output = new ByteArrayOutputStream()) {
			byte[] buffer = new byte[8192];
			int read;
			long total = 0L;
			while ((read = input.read(buffer)) >= 0) {
				total += read;
				if (total > MAX_EXPANDED_BYTES) throw problem(
					WorldBuilderErrorCodes.CONTRACT_LIMIT_EXCEEDED, role,
					"OSAR sprite archive expands beyond 512 MiB.",
					"Reduce the exact declarative sprite archive.");
				output.write(buffer, 0, read);
			}
			expanded = output.toByteArray();
		} catch (WorldBuilderContractException invalid) {
			throw invalid;
		} catch (IOException unsupported) {
			throw problem(WorldBuilderErrorCodes.UNSUPPORTED_FORMAT, role,
				"Named custom item visuals require a structurally readable GZIP OSAR archive.",
				"Provide the exact target OSAR produced for the runtime Unpacker.", unsupported);
		}
		try {
			OsarInput input = new OsarInput(expanded);
			Set<String> exact = new HashSet<String>();
			Set<String> folded = new HashSet<String>();
			Set<String> subspaces = new HashSet<String>();
			int subspaceCount = input.unsignedByte();
			if (subspaceCount == 0) throw new IllegalArgumentException("no subspaces");
			int entryCount = 0;
			for (int subspaceIndex = 0; subspaceIndex < subspaceCount; subspaceIndex++) {
				String subspace = input.name();
				requireOsarName(subspace);
				if (!subspaces.add(subspace)) {
					throw new IllegalArgumentException("duplicate subspace name");
				}
				int entries = input.unsignedShort();
				for (int entryIndex = 0; entryIndex < entries; entryIndex++) {
					if (++entryCount > MAX_ARCHIVE_ENTRIES) {
						throw new IllegalArgumentException("too many entries");
					}
					String entry = input.name();
					requireOsarName(entry);
					String combined = subspace + "/" + entry;
					if (!exact.add(combined)) {
						throw new IllegalArgumentException("duplicate entry name");
					}
					folded.add(combined.toLowerCase(Locale.ROOT));
					readSpriteEntry(input);
				}
			}
			if (input.remaining() != 0) throw new IllegalArgumentException("trailing data");
			return new SpriteArchive(exact, folded);
		} catch (WorldBuilderContractException unsafe) {
			throw unsafe;
		} catch (RuntimeException malformed) {
			String reason = malformed.getMessage() == null
				? "unclassified structural failure" : malformed.getMessage();
			throw problem(WorldBuilderErrorCodes.UNSUPPORTED_FORMAT, role,
				"OSAR sprite archive structure is invalid: " + reason + ".",
				"Rebuild a bounded GZIP OSAR with unique runtime-safe names and complete sprite frames.",
				malformed);
		}
	}

	private static void requireOsarName(String name)
		throws WorldBuilderContractException {
		if (!name.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")) {
			throw new IllegalArgumentException("unsafe OSAR name");
		}
	}

	private static void readSpriteEntry(OsarInput input) {
		int type = input.unsignedByte();
		if (type < 0 || type > 4) throw new IllegalArgumentException("entry type");
		if (type >= 1 && type <= 3 && input.unsignedByte() > 11) {
			throw new IllegalArgumentException("entry layer");
		}
		int frameCount = input.unsignedByte();
		if (frameCount == 0) throw new IllegalArgumentException("empty entry");
		int paletteSize = input.unsignedByte() + 1;
		input.skip(paletteSize * 3);
		for (int frame = 0; frame < frameCount; frame++) {
			int width = input.unsignedShort();
			int height = input.unsignedShort();
			int shifted = input.unsignedByte();
			input.signedShort();
			input.signedShort();
			input.unsignedShort();
			input.unsignedShort();
			if (width == 0 || height == 0 || shifted > 1) {
				throw new IllegalArgumentException("frame dimensions");
			}
			long pixels = (long)width * (long)height;
			if (pixels > 16777216L) throw new IllegalArgumentException("frame pixels");
			for (long pixel = 0; pixel < pixels; pixel++) {
				if (input.unsignedByte() >= paletteSize) {
					throw new IllegalArgumentException("pixel palette index");
				}
			}
		}
	}

	private static Set<Integer> differenceIds(Object target, Object packaged)
		throws WorldBuilderContractException {
		Set<Integer> result = idSet(target);
		result.removeAll(idSet(packaged));
		return result;
	}

	private static Set<Integer> idSet(Object raw) throws WorldBuilderContractException {
		Set<Integer> result = new TreeSet<Integer>();
		for (Object value : array(raw, "definition IDs", 0, MAX_DEFINITIONS)) {
			if (!(value instanceof Long)) throw malformedDefinition("definition IDs");
			result.add(Integer.valueOf((int)((Long)value).longValue()));
		}
		return result;
	}

	private static String itemVisualFingerprint(int version, List<Object> visuals) {
		if (version == 1) return ZERO_HASH;
		byte[] domain = "world-builder-project-content-item-visuals-v1\n"
			.getBytes(StandardCharsets.US_ASCII);
		MessageDigest digest = sha256();
		digest.update(domain);
		digest.update(WorldBuilderJsonDocuments.canonical(visuals)
			.getBytes(StandardCharsets.UTF_8));
		return hex(digest.digest());
	}

	private static WorldBuilderContractException visualProblem(String message) {
		return problem(WorldBuilderErrorCodes.DEFINITION_MISMATCH,
			ITEM_VISUAL_EVIDENCE_PATH, message,
			"Correct the truthful static item visual evidence and retry without executing target code.");
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
		digest.update(("world-builder-project-content-bundle-v"
			+ integer(manifest, "schemaVersion") + "\n")
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
		for (String field : Arrays.asList("tiles", "boundaries")) {
			validateIds(catalog.get(field), field, MAX_RAW_BYTE_ID);
		}
		for (String field : Arrays.asList("scenery", "npcs", "groundItems")) {
			validateIds(catalog.get(field), field, MAX_RUNTIME_ID);
		}
		if (!selfHash(catalog, "catalogSha256").equals(catalog.get("catalogSha256"))) {
			throw malformedDefinition("definitionCatalog");
		}
	}

	private static void validateIds(Object raw, String field, int maximum)
		throws WorldBuilderContractException {
		List<?> values = array(raw, field, 0, MAX_DEFINITIONS);
		long previous = -1L;
		for (Object value : values) {
			if (!(value instanceof Long) || ((Long)value).longValue() <= previous
				|| ((Long)value).longValue() > maximum) {
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

	private static final class SpriteArchive {
		final Set<String> exactNames;
		final Set<String> caseFoldedNames;

		SpriteArchive(Set<String> exactNames, Set<String> caseFoldedNames) {
			this.exactNames = exactNames;
			this.caseFoldedNames = caseFoldedNames;
		}
	}

	private static final class ItemVisualMigration {
		final List<Object> itemVisuals;
		final byte[] customArchiveOverride;
		final byte[] authenticArchiveOverride;
		final WorldBuilderItemVisualProvider.Result provider;

		ItemVisualMigration(List<Object> itemVisuals,
			WorldBuilderItemVisualProvider.Result provider) {
			this.itemVisuals = Collections.unmodifiableList(
				new ArrayList<Object>(itemVisuals));
			this.provider = provider;
			this.customArchiveOverride = provider == null
				? null : provider.customArchiveOverride;
			this.authenticArchiveOverride = provider == null
				? null : provider.authenticArchiveOverride;
		}
	}

	private static final class OsarInput {
		private final byte[] bytes;
		private int offset;

		OsarInput(byte[] bytes) {
			this.bytes = bytes;
		}

		int unsignedByte() {
			if (offset >= bytes.length) throw new IllegalArgumentException("truncated OSAR");
			return bytes[offset++] & 0xff;
		}

		int unsignedShort() {
			return unsignedByte() << 8 | unsignedByte();
		}

		int signedShort() {
			int value = unsignedShort();
			return value >= 0x8000 ? value - 0x10000 : value;
		}

		String name() {
			StringBuilder result = new StringBuilder();
			while (true) {
				int value = unsignedByte();
				if (value == 0) break;
				if (result.length() >= 255) throw new IllegalArgumentException("long OSAR name");
				result.append((char)value);
			}
			if (result.length() == 0) throw new IllegalArgumentException("empty OSAR name");
			return result.toString();
		}

		void skip(int count) {
			if (count < 0 || count > remaining()) {
				throw new IllegalArgumentException("truncated OSAR payload");
			}
			offset += count;
		}

		int remaining() {
			return bytes.length - offset;
		}
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
		final String capabilityId;
		final Map<String,Object> definitionCatalog;
		final List<Object> itemVisuals;
		final List<FileRecord> files;
		final String definitionFingerprintSha256;
		final String assetFingerprintSha256;
		final String itemVisualFingerprintSha256;
		final String bundleFingerprintSha256;

		Bundle(Path root, String capabilityId, Map<String,Object> definitionCatalog,
			List<Object> itemVisuals, List<FileRecord> files,
			String definitionFingerprintSha256, String assetFingerprintSha256,
			String itemVisualFingerprintSha256, String bundleFingerprintSha256) {
			this.root = root;
			this.capabilityId = capabilityId;
			this.definitionCatalog = Collections.unmodifiableMap(
				new LinkedHashMap<String,Object>(definitionCatalog));
			this.itemVisuals = Collections.unmodifiableList(
				new ArrayList<Object>(itemVisuals));
			this.files = Collections.unmodifiableList(new ArrayList<FileRecord>(files));
			this.definitionFingerprintSha256 = definitionFingerprintSha256;
			this.assetFingerprintSha256 = assetFingerprintSha256;
			this.itemVisualFingerprintSha256 = itemVisualFingerprintSha256;
			this.bundleFingerprintSha256 = bundleFingerprintSha256;
		}

		Map<String,Object> compatibilityCatalog() {
			Map<String,Object> result =
				new LinkedHashMap<String,Object>(definitionCatalog);
			result.remove("catalogSha256");
			return result;
		}

		Path pathForRole(String role) {
			for (FileRecord file : files) {
				if (file.spec.role.equals(role)) return root.resolve(file.bundlePath);
			}
			throw new IllegalArgumentException("Unknown content-bundle role: " + role);
		}

		Map<String,Object> evidenceForRole(String role) {
			for (FileRecord file : files) {
				if (file.spec.role.equals(role)) return file.toJson();
			}
			throw new IllegalArgumentException("Unknown content-bundle role: " + role);
		}
	}

	static final class FileRecord implements Comparable<FileRecord> {
		final Spec spec;
		final String bundlePath;
		final long size;
		final String sha256;
		final String mediaType;

		FileRecord(Spec spec, String bundlePath, long size, String sha256,
			String mediaType) {
			this.spec = spec;
			this.bundlePath = bundlePath;
			this.size = size;
			this.sha256 = sha256;
			this.mediaType = mediaType;
		}

		Map<String,Object> toJson() {
			Map<String,Object> value = new LinkedHashMap<String,Object>();
			value.put("role", spec.role);
			value.put("bundleRelativePath", bundlePath);
			value.put("runtimeRelativePath", spec.runtimePath);
			value.put("mediaType", mediaType);
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
