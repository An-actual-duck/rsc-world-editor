package com.openrsc.worldbuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Normalizes sparse target/provider NPC definitions into the sequential runtime
 * registry without executing target code. Missing definitions become explicit
 * project-local placeholders and actionable warnings.
 */
final class WorldBuilderNpcDefinitionProvider {
	static final String TYPE = "world-builder-npc-definition-mapping";
	static final String PRODUCER_TYPE = "world-builder-npc-definitions";
	static final String FILE_NAME = "npc-definitions-v1.json";
	static final String REPORT_PATH = "diagnostics/npc-definition-provider-warnings.json";
	private static final String PACKAGE_MANIFEST = "package-manifest-v1.json";
	private static final long MAX_BYTES = 16L * 1024L * 1024L;
	private static final int MAX_ID = 65535;
	private static final int MAX_FILES = 8192;
	private static final Set<String> PACKAGE_ROLES = set(
		"authentic-sprite-archive", "custom-sprite-archive", "external-png",
		"item-visual-schema", "compatibility-item-visual-manifest",
		"full-item-visual-manifest", "npc-definition-schema",
		"full-npc-definition-manifest");
	private static final Set<String> RECORD_KEYS = set("npcId", "name", "definition");
	private static final Set<String> REQUIRED_DEFINITION_KEYS = set(
		"id", "name", "description", "command", "command2", "attack", "strength",
		"hits", "defense", "ranged", "combatlvl", "isMembers", "attackable",
		"aggressive", "respawnTime", "sprites1", "sprites2", "sprites3", "sprites4",
		"sprites5", "sprites6", "sprites7", "sprites8", "sprites9", "sprites10",
		"sprites11", "sprites12", "hairColour", "topColour", "bottomColour",
		"skinColour", "camera1", "camera2", "walkModel", "combatModel",
		"combatSprite", "roundMode");
	private static final Set<String> OPTIONAL_DEFINITION_KEYS = set(
		"projectileRange", "meleeOffense", "rangedOffense", "magicOffense",
		"meleeDefense", "rangedDefense", "magicDefense", "meleeDefenseMultiplier",
		"rangedDefenseMultiplier", "magicDefenseMultiplier", "meleeDefenseDivisor",
		"rangedDefenseDivisor", "magicDefenseDivisor");
	private static final Set<String> PRODUCER_ROOT_KEYS = set(
		"schemaVersion", "manifestType", "provider", "assetProviders", "selection",
		"npcDefinitions", "animationDefinitions");
	private static final Set<String> PRODUCER_NPC_KEYS = set(
		"npcId", "definitionId", "name", "description", "command1", "command2",
		"attack", "strength", "hits", "defense", "attackable",
		"spriteAnimationIds", "hairColour", "topColour", "bottomColour",
		"skinColour", "cameraWidth", "cameraHeight", "walkModel", "combatModel",
		"combatSprite");
	private static final Set<String> PRODUCER_PROVIDER_KEYS = set(
		"identity", "definitionMode", "finalClientNpcCount",
		"finalClientNpcCatalogSha256", "sources");
	private static final Set<String> PRODUCER_SOURCE_KEYS = set(
		"role", "identity", "sha256");
	private static final Set<String> PRODUCER_SELECTION_KEYS = set(
		"kind", "declarativeMaximumNpcId", "placementCount", "npcCount",
		"placedNpcIds", "placementCountByNpcId", "npcIdsSha256",
		"definitionsSha256", "animationsSha256");
	private static final Set<String> PRODUCER_ANIMATION_KEYS = set(
		"animationId", "name", "category", "charColour", "blueMask", "genderModel",
		"hasCombatFrames", "hasSpecialCombatFrames", "requiredFrameCount",
		"customArchive", "authenticArchive");

	private WorldBuilderNpcDefinitionProvider() {
	}

	static Result consume(Path selectedProviderManifest, Path copiedTarget,
		Map<String,Object> targetCatalog)
		throws IOException, WorldBuilderContractException {
		Map<String,Object> base = definitionDocument(copiedTarget,
			"server/conf/server/defs/NpcDefs.json", "npcs");
		Map<String,Object> custom = definitionDocument(copiedTarget,
			"server/conf/server/defs/NpcDefsCustom.json", "npcs");
		List<Object> baseRows = array(base.get("npcs"), "NpcDefs.json");
		List<Object> customRows = array(custom.get("npcs"), "NpcDefsCustom.json");
		int appendedCount = baseRows.size() + customRows.size();
		if (appendedCount < 1) throw problem("server/conf/server/defs/NpcDefs.json",
			"Target NPC definitions contain no sequential base record.");

		Set<Integer> required = catalogIds(targetCatalog);
		required.addAll(placementIds(copiedTarget));
		int maximum = required.isEmpty() ? -1 : Collections.max(required).intValue();
		if (maximum < appendedCount) return Result.unchanged();
		if (maximum > MAX_ID) throw problem("npc placements",
			"Required NPC ID exceeds the runtime domain 0..65535.");

		Provider provider = readProvider(selectedProviderManifest);
		Map<String,Object> template = object(baseRows.get(0), "NpcDefs.json#record=0");
		List<Object> rewritten = new ArrayList<Object>(customRows);
		List<Item> items = new ArrayList<Item>();
		List<Warning> warnings = new ArrayList<Warning>();
		for (int id = appendedCount; id <= maximum; id++) {
			Map<String,Object> definition = provider.definitions.get(Integer.valueOf(id));
			boolean requiredId = required.contains(Integer.valueOf(id));
			if (definition == null) {
				definition = placeholder(template, id);
				if (requiredId) warnings.add(new Warning(id,
					"NPC_DEFINITION_PLACEHOLDER",
					"No authoritative provider definition exists for NPC " + id
						+ "; a deterministic project-local placeholder was generated."));
				items.add(new Item(id, requiredId ? "placeholder" : "gap-placeholder"));
			} else {
				items.add(new Item(id, "resolved"));
			}
			rewritten.add(definition);
		}
		Map<String,Object> document = new LinkedHashMap<String,Object>();
		document.put("npcs", rewritten);
		return new Result(WorldBuilderJsonDocuments.pretty(document)
			.getBytes(StandardCharsets.UTF_8), provider.sha256, items, warnings);
	}

	static void writeReport(Path projectStage, Result result)
		throws IOException {
		if (!result.changed()) return;
		Path path = projectStage.resolve(REPORT_PATH).normalize();
		if (!path.startsWith(projectStage.toAbsolutePath().normalize())) {
			throw new IOException("NPC provider report escaped project stage");
		}
		Files.createDirectories(path.getParent());
		Map<String,Object> report = new LinkedHashMap<String,Object>();
		report.put("schemaVersion", Long.valueOf(1L));
		report.put("manifestType", "world-builder-npc-definition-provider-report");
		report.put("providerManifestSha256", result.providerSha256);
		List<Object> items = new ArrayList<Object>();
		for (Item item : result.items) items.add(item.json());
		report.put("npcs", items);
		List<Object> warnings = new ArrayList<Object>();
		for (Warning warning : result.warnings) warnings.add(warning.json());
		report.put("warnings", warnings);
		Files.write(path, WorldBuilderJsonDocuments.pretty(report)
			.getBytes(StandardCharsets.UTF_8));
	}

	static String projectWarningSummary(Path projectRoot) {
		if (projectRoot == null) return null;
		Path root = projectRoot.toAbsolutePath().normalize();
		Path report = root.resolve(REPORT_PATH).normalize();
		try {
			if (!report.startsWith(root)
				|| !Files.isRegularFile(report, LinkOption.NOFOLLOW_LINKS)
				|| Files.isSymbolicLink(report)
				|| Files.size(report) > MAX_BYTES) return null;
			Map<String,Object> value =
				WorldBuilderJsonDocuments.readTargetDefinitionObject(report);
			Object rawWarnings = value.get("warnings");
			if (!(rawWarnings instanceof List) || ((List<?>)rawWarnings).isEmpty()) return null;
			TreeSet<Integer> ids = new TreeSet<Integer>();
			for (Object raw : (List<?>)rawWarnings) {
				if (!(raw instanceof Map)) continue;
				Map<?,?> warning = (Map<?,?>)raw;
				if (!"NPC_DEFINITION_PLACEHOLDER".equals(warning.get("code"))) continue;
				Object id = warning.get("npcId");
				if (id instanceof Number) ids.add(Integer.valueOf(((Number)id).intValue()));
			}
			if (ids.isEmpty()) return null;
			return "\n\nNPC provider warning: no authoritative definitions were supplied for "
				+ "NPC IDs " + ids + ". They will appear as clearly named placeholders "
				+ "using NPC 0's visuals. Install a complete provider containing "
				+ FILE_NAME + " and recreate this project for faithful NPC visuals.\n"
				+ "Details: " + report;
		} catch (Exception ignored) {
			return null;
		}
	}

	private static Provider readProvider(Path selected) {
		if (selected == null || selected.getParent() == null) return Provider.empty();
		Path root = selected.toAbsolutePath().normalize().getParent();
		Path candidate = root.resolve(FILE_NAME).normalize();
		try {
			if (!candidate.startsWith(root)
				|| !Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)
				|| Files.isSymbolicLink(candidate) || Files.size(candidate) < 1L
				|| Files.size(candidate) > MAX_BYTES) return Provider.empty();
			validatePackageInventory(root, candidate);
			Map<String,Object> document =
				WorldBuilderJsonDocuments.readTargetDefinitionObject(candidate);
			if (!Long.valueOf(1L).equals(document.get("schemaVersion"))) {
				return Provider.empty();
			}
			if (PRODUCER_TYPE.equals(document.get("manifestType"))) {
				return readProducerProvider(root, candidate, document);
			}
			exact(document, set("schemaVersion", "manifestType", "npcs"), FILE_NAME);
			if (!TYPE.equals(document.get("manifestType"))) return Provider.empty();
			List<Object> rows = array(document.get("npcs"), FILE_NAME);
			TreeMap<Integer,Map<String,Object>> definitions =
				new TreeMap<Integer,Map<String,Object>>();
			int previous = -1;
			for (int index = 0; index < rows.size(); index++) {
				Map<String,Object> row = object(rows.get(index), FILE_NAME + "#record=" + index);
				exact(row, RECORD_KEYS, FILE_NAME + "#record=" + index);
				int id = integer(row.get("npcId"), 0, MAX_ID, "npcId");
				if (id <= previous) return Provider.empty();
				previous = id;
				String name = text(row.get("name"), 1, 256, "name");
				Map<String,Object> definition = normalizeDefinition(
					object(row.get("definition"), "definition"), id, name);
				definitions.put(Integer.valueOf(id), definition);
			}
			return new Provider(definitions, WorldBuilderHashes.sha256(candidate));
		} catch (Exception invalid) {
			return Provider.empty();
		}
	}

	/**
	 * Consumes the richer neutral producer contract without requiring the
	 * producer to duplicate the runtime's legacy server-definition shape.
	 * Fields which do not exist in the producer contract are deliberately inert
	 * in Builder mode; identity, appearance, commands, stats, and dimensions are
	 * retained exactly.
	 */
	private static Provider readProducerProvider(Path root, Path candidate,
		Map<String,Object> document) throws IOException {
		exact(document, PRODUCER_ROOT_KEYS, FILE_NAME);
		validateProducerMetadata(object(document.get("provider"), "provider"));
		validateProducerAssets(root,
			object(document.get("assetProviders"), "assetProviders"));
		List<Object> animations = array(document.get("animationDefinitions"),
			"animationDefinitions");
		Set<Integer> animationIds = new TreeSet<Integer>();
		int previousAnimation = -1;
		for (int index = 0; index < animations.size(); index++) {
			Map<String,Object> animation = object(animations.get(index),
				"animationDefinitions#record=" + index);
			validateProducerAnimation(animation);
			int id = integer(animation.get("animationId"), 0, MAX_ID, "animationId");
			if (id <= previousAnimation) throw new IOException(
				"producer animation definitions are not sorted and unique");
			previousAnimation = id;
			animationIds.add(Integer.valueOf(id));
		}

		List<Object> rows = array(document.get("npcDefinitions"), "npcDefinitions");
		TreeMap<Integer,Map<String,Object>> definitions =
			new TreeMap<Integer,Map<String,Object>>();
		int previous = -1;
		for (int index = 0; index < rows.size(); index++) {
			Map<String,Object> row = object(rows.get(index),
				"npcDefinitions#record=" + index);
			exact(row, PRODUCER_NPC_KEYS, "npcDefinitions#record=" + index);
			int id = integer(row.get("npcId"), 0, MAX_ID, "npcId");
			if (id <= previous || integer(row.get("definitionId"), 0, MAX_ID,
				"definitionId") != id) throw new IOException(
				"producer NPC definitions are not sorted, unique, and identity-bound");
			previous = id;
			String name = text(row.get("name"), 1, 256, "name");
			List<Object> sprites = array(row.get("spriteAnimationIds"),
				"spriteAnimationIds");
			if (sprites.size() != 12) throw new IOException(
				"producer NPC definition must contain exactly 12 sprite animation IDs");
			for (Object raw : sprites) {
				int animation = integer(raw, -1, MAX_ID, "spriteAnimationId");
				if (animation >= 0 && !animationIds.contains(Integer.valueOf(animation))) {
					throw new IOException("producer NPC references an unresolved animation");
				}
			}
			definitions.put(Integer.valueOf(id), producerDefinition(row, id, name, sprites));
		}
		validateProducerSelection(object(document.get("selection"), "selection"),
			definitions.keySet());
		return new Provider(definitions, WorldBuilderHashes.sha256(candidate));
	}

	private static Map<String,Object> producerDefinition(Map<String,Object> row,
		int id, String name, List<Object> sprites) throws IOException {
		Map<String,Object> result = new LinkedHashMap<String,Object>();
		result.put("id", Long.valueOf(id));
		result.put("name", name);
		result.put("description", nullableText(row.get("description"), 1024,
			"description"));
		result.put("command", nullableText(row.get("command1"), 1024, "command1"));
		result.put("command2", nullableText(row.get("command2"), 1024, "command2"));
		for (String key : Arrays.asList("attack", "strength", "hits", "defense")) {
			result.put(key, Long.valueOf(integer(row.get(key), Integer.MIN_VALUE,
				Integer.MAX_VALUE, key)));
		}
		result.put("ranged", Boolean.FALSE);
		result.put("combatlvl", Long.valueOf(0L));
		result.put("isMembers", Long.valueOf(0L));
		if (!(row.get("attackable") instanceof Boolean)) {
			throw new IOException("attackable is invalid");
		}
		result.put("attackable", Long.valueOf(Boolean.TRUE.equals(row.get("attackable"))
			? 1L : 0L));
		result.put("aggressive", Long.valueOf(0L));
		result.put("respawnTime", Long.valueOf(30L));
		for (int index = 0; index < 12; index++) {
			result.put("sprites" + (index + 1), Long.valueOf(integer(sprites.get(index),
				-1, MAX_ID, "spriteAnimationId")));
		}
		for (String key : Arrays.asList("hairColour", "topColour", "bottomColour",
			"skinColour", "walkModel", "combatModel", "combatSprite")) {
			result.put(key, Long.valueOf(integer(row.get(key), Integer.MIN_VALUE,
				Integer.MAX_VALUE, key)));
		}
		result.put("camera1", Long.valueOf(integer(row.get("cameraWidth"), 1,
			Integer.MAX_VALUE, "cameraWidth")));
		result.put("camera2", Long.valueOf(integer(row.get("cameraHeight"), 1,
			Integer.MAX_VALUE, "cameraHeight")));
		result.put("roundMode", Long.valueOf(-1L));
		return result;
	}

	private static void validateProducerAssets(Path root, Map<String,Object> assets)
		throws IOException {
		exact(assets, set("customSpriteArchive", "authenticSpriteArchive"),
			"assetProviders");
		for (String key : Arrays.asList("customSpriteArchive", "authenticSpriteArchive")) {
			Map<String,Object> asset = object(assets.get(key), key);
			exact(asset, "customSpriteArchive".equals(key)
				? set("path", "sha256", "entryCount")
				: set("path", "sha256", "numericEntryCount"), key);
			String relative = text(asset.get("path"), 1, 512, key + ".path");
			String hash = hash(asset.get("sha256"), key + ".sha256");
			integer(asset.get("customSpriteArchive".equals(key) ? "entryCount"
				: "numericEntryCount"), 0, Integer.MAX_VALUE, key + ".entryCount");
			Path path;
			try {
				path = WorldBuilderPortablePath.resolveContained(root, relative,
					"npc-definition-provider");
			} catch (WorldBuilderContractException unsafe) {
				throw new IOException("unsafe producer asset path", unsafe);
			}
			if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
				|| Files.isSymbolicLink(path) || !hash.equals(WorldBuilderHashes.sha256(path))) {
				throw new IOException("producer asset differs from its binding");
			}
		}
	}

	private static void validateProducerMetadata(Map<String,Object> provider)
		throws IOException {
		exact(provider, PRODUCER_PROVIDER_KEYS, "provider");
		text(provider.get("identity"), 1, 256, "provider.identity");
		text(provider.get("definitionMode"), 1, 256, "provider.definitionMode");
		integer(provider.get("finalClientNpcCount"), 1, MAX_ID + 1,
			"provider.finalClientNpcCount");
		hash(provider.get("finalClientNpcCatalogSha256"),
			"provider.finalClientNpcCatalogSha256");
		List<Object> sources = array(provider.get("sources"), "provider.sources");
		if (sources.isEmpty()) throw new IOException("provider sources are empty");
		for (Object raw : sources) {
			Map<String,Object> source = object(raw, "provider source");
			exact(source, PRODUCER_SOURCE_KEYS, "provider source");
			text(source.get("role"), 1, 96, "provider source role");
			text(source.get("identity"), 1, 512, "provider source identity");
			hash(source.get("sha256"), "provider source sha256");
		}
	}

	private static void validateProducerAnimation(Map<String,Object> animation)
		throws IOException {
		exact(animation, PRODUCER_ANIMATION_KEYS, "animation definition");
		text(animation.get("name"), 1, 256, "animation name");
		text(animation.get("category"), 1, 128, "animation category");
		for (String key : Arrays.asList("charColour", "blueMask", "genderModel")) {
			integer(animation.get(key), Integer.MIN_VALUE, Integer.MAX_VALUE, key);
		}
		bool(animation.get("hasCombatFrames"), "hasCombatFrames");
		bool(animation.get("hasSpecialCombatFrames"), "hasSpecialCombatFrames");
		int count = integer(animation.get("requiredFrameCount"), 1, 4096,
			"requiredFrameCount");
		Map<String,Object> custom = object(animation.get("customArchive"),
			"customArchive");
		exact(custom, set("subspace", "entry", "frameCount", "entrySha256",
			"spritepackOverrideKey"), "customArchive");
		portable(text(custom.get("subspace"), 1, 256, "custom subspace"));
		portable(text(custom.get("entry"), 1, 256, "custom entry"));
		if (integer(custom.get("frameCount"), 1, 4096, "custom frameCount") != count) {
			throw new IOException("custom animation frame count is inconsistent");
		}
		hash(custom.get("entrySha256"), "custom entrySha256");
		text(custom.get("spritepackOverrideKey"), 1, 512,
			"spritepackOverrideKey");

		Map<String,Object> authentic = object(animation.get("authenticArchive"),
			"authenticArchive");
		exact(authentic, set("baseSpriteId", "frames"), "authenticArchive");
		integer(authentic.get("baseSpriteId"), 0, MAX_ID, "baseSpriteId");
		List<Object> frames = array(authentic.get("frames"), "authentic frames");
		if (frames.size() != count) throw new IOException(
			"authentic animation frame count is inconsistent");
		int previous = -1;
		for (Object raw : frames) {
			Map<String,Object> frame = object(raw, "authentic frame");
			exact(frame, set("spriteId", "entrySha256"), "authentic frame");
			int spriteId = integer(frame.get("spriteId"), 0, MAX_ID, "spriteId");
			if (spriteId <= previous) throw new IOException(
				"authentic frame IDs are not sorted and unique");
			previous = spriteId;
			hash(frame.get("entrySha256"), "authentic frame entrySha256");
		}
	}

	private static void validateProducerSelection(Map<String,Object> selection,
		Set<Integer> definitionIds) throws IOException {
		exact(selection, PRODUCER_SELECTION_KEYS, "selection");
		text(selection.get("kind"), 1, 256, "selection.kind");
		integer(selection.get("declarativeMaximumNpcId"), 0, MAX_ID,
			"declarativeMaximumNpcId");
		hash(selection.get("npcIdsSha256"), "npcIdsSha256");
		hash(selection.get("definitionsSha256"), "definitionsSha256");
		hash(selection.get("animationsSha256"), "animationsSha256");
		Object raw = selection.get("placedNpcIds");
		List<Object> ids = array(raw, "selection.placedNpcIds");
		TreeSet<Integer> selected = new TreeSet<Integer>();
		int previous = -1;
		for (Object value : ids) {
			int id = integer(value, 0, MAX_ID, "placedNpcId");
			if (id <= previous) throw new IOException("placed NPC IDs are not sorted and unique");
			previous = id;
			selected.add(Integer.valueOf(id));
		}
		List<Object> counts = array(selection.get("placementCountByNpcId"),
			"placementCountByNpcId");
		TreeSet<Integer> counted = new TreeSet<Integer>();
		long placementCount = 0L;
		for (Object value : counts) {
			Map<String,Object> count = object(value, "placement count");
			exact(count, set("npcId", "count"), "placement count");
			int id = integer(count.get("npcId"), 0, MAX_ID, "placement npcId");
			if (!counted.add(Integer.valueOf(id))) throw new IOException(
				"placement count IDs are duplicated");
			placementCount += integer(count.get("count"), 1, Integer.MAX_VALUE,
				"placement count");
			if (placementCount > Integer.MAX_VALUE) throw new IOException(
				"placement count is excessive");
		}
		if (!selected.equals(definitionIds) || !selected.equals(counted)
			|| integer(selection.get("npcCount"), 1, MAX_ID + 1, "npcCount")
				!= definitionIds.size()
			|| integer(selection.get("placementCount"), 1, Integer.MAX_VALUE,
				"placementCount") != (int)placementCount) {
			throw new IOException("producer selection differs from NPC definitions");
		}
	}

	private static String nullableText(Object raw, int maximum, String label)
		throws IOException {
		if (raw == null) return "";
		return text(raw, 0, maximum, label);
	}

	private static void bool(Object raw, String label) throws IOException {
		if (!(raw instanceof Boolean)) throw new IOException(label + " is invalid");
	}

	private static String hash(Object raw, String label) throws IOException {
		String value = text(raw, 64, 64, label);
		if (!value.matches("[0-9a-f]{64}")) throw new IOException(label + " is invalid");
		return value;
	}

	private static String portable(String value) throws IOException {
		try {
			return WorldBuilderPortablePath.require(value, "npc-definition-provider");
		} catch (WorldBuilderContractException invalid) {
			throw new IOException("producer path is unsafe", invalid);
		}
	}

	private static void validatePackageInventory(Path root, Path candidate)
		throws IOException, WorldBuilderDiscoveryException {
		Path manifest = root.resolve(PACKAGE_MANIFEST);
		if (!Files.exists(manifest, LinkOption.NOFOLLOW_LINKS)) return;
		if (!Files.isRegularFile(manifest, LinkOption.NOFOLLOW_LINKS)
			|| Files.isSymbolicLink(manifest) || Files.size(manifest) > MAX_BYTES) {
			throw new IOException("unsafe package manifest");
		}
		Map<String,Object> value = WorldBuilderJsonDocuments.readObject(manifest);
		exact(value, set("schemaVersion", "manifestType", "providerDirectory",
			"catalogSha256", "files"), PACKAGE_MANIFEST);
		if (!Long.valueOf(1L).equals(value.get("schemaVersion"))
			|| !"world-builder-item-visual-provider-package".equals(value.get("manifestType"))
			|| !"world-builder-provider".equals(value.get("providerDirectory"))
			|| !(value.get("catalogSha256") instanceof String)
			|| !((String)value.get("catalogSha256")).matches("[0-9a-f]{64}")) {
			throw new IOException("unsupported package identity");
		}
		List<Object> rawFiles = array(value.get("files"), PACKAGE_MANIFEST);
		if (rawFiles.isEmpty() || rawFiles.size() > MAX_FILES) {
			throw new IOException("missing or excessive package inventory");
		}
		Set<String> declared = new TreeSet<String>();
		Set<String> folded = new HashSet<String>();
		String previous = null;
		boolean matched = false;
		Path realRoot = root.toRealPath();
		for (Object raw : rawFiles) {
			Map<String,Object> record = object(raw, "package inventory row");
			exact(record, set("path", "role", "size", "sha256"), "package inventory row");
			String relative = text(record.get("path"), 1, 512, "package path");
			try {
				WorldBuilderPortablePath.require(relative, "npc-definition-provider");
			} catch (WorldBuilderContractException unsafe) {
				throw new IOException("unsafe package path", unsafe);
			}
			String role = text(record.get("role"), 1, 96, "package role");
			if (!PACKAGE_ROLES.contains(role) || previous != null
				&& previous.compareTo(relative) >= 0
				|| !declared.add(relative)
				|| !folded.add(relative.toLowerCase(java.util.Locale.ROOT))) {
				throw new IOException("unsupported, unsorted, or colliding package inventory");
			}
			previous = relative;
			long size = ((record.get("size") instanceof Long)
				? ((Long)record.get("size")).longValue() : -1L);
			String hash = record.get("sha256") instanceof String
				? (String)record.get("sha256") : "";
			Path actual;
			try {
				actual = WorldBuilderPortablePath.resolveContained(
					root, relative, "npc-definition-provider");
			} catch (WorldBuilderContractException unsafe) {
				throw new IOException("unsafe package path", unsafe);
			}
			if (size < 1L || size > 256L * 1024L * 1024L
				|| !hash.matches("[0-9a-f]{64}")
				|| !Files.isRegularFile(actual, LinkOption.NOFOLLOW_LINKS)
				|| Files.isSymbolicLink(actual) || !actual.toRealPath().startsWith(realRoot)
				|| Files.size(actual) != size || !hash.equals(WorldBuilderHashes.sha256(actual))) {
				throw new IOException("package file differs from its bounded inventory");
			}
			if (FILE_NAME.equals(relative)) {
				matched = "full-npc-definition-manifest".equals(role)
					&& actual.toRealPath().equals(candidate.toRealPath());
			}
		}
		Set<String> actualFiles = new TreeSet<String>();
		try (java.util.stream.Stream<Path> stream = Files.walk(root)) {
			java.util.Iterator<Path> iterator = stream.iterator();
			while (iterator.hasNext()) {
				Path path = iterator.next();
				if (Files.isSymbolicLink(path)) throw new IOException("package contains a link");
				if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) continue;
				if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
					throw new IOException("package contains a special file");
				}
				String relative = root.relativize(path).toString().replace('\\', '/');
				if (!PACKAGE_MANIFEST.equals(relative)) actualFiles.add(relative);
			}
		}
		if (!matched || !actualFiles.equals(declared)) {
			throw new IOException("NPC manifest or package inventory closure is invalid");
		}
	}

	private static Map<String,Object> normalizeDefinition(
		Map<String,Object> input, int id, String name) throws IOException {
		Set<String> allowed = new HashSet<String>(REQUIRED_DEFINITION_KEYS);
		allowed.addAll(OPTIONAL_DEFINITION_KEYS);
		if (!input.keySet().equals(allowed)
			&& (!input.keySet().containsAll(REQUIRED_DEFINITION_KEYS)
				|| !allowed.containsAll(input.keySet()))) {
			throw new IOException("NPC definition keys are incomplete or unsupported");
		}
		if (integer(input.get("id"), 0, MAX_ID, "definition.id") != id
			|| !name.equals(text(input.get("name"), 1, 256, "definition.name"))) {
			throw new IOException("NPC definition identity mismatch");
		}
		for (String key : Arrays.asList("description", "command", "command2")) {
			text(input.get(key), 0, 1024, key);
		}
		if (!(input.get("ranged") instanceof Boolean)) throw new IOException("invalid ranged");
		for (String key : REQUIRED_DEFINITION_KEYS) {
			if (Arrays.asList("id", "name", "description", "command", "command2", "ranged")
				.contains(key)) continue;
			integer(input.get(key), Integer.MIN_VALUE, Integer.MAX_VALUE, key);
		}
		for (String key : OPTIONAL_DEFINITION_KEYS) {
			if (!input.containsKey(key) || !(input.get(key) instanceof Number)) continue;
			double number = ((Number)input.get(key)).doubleValue();
			if (!Double.isFinite(number) || Math.abs(number) > 1000000.0D) {
				throw new IOException("invalid " + key);
			}
		}
		return new LinkedHashMap<String,Object>(input);
	}

	private static Map<String,Object> placeholder(Map<String,Object> template, int id) {
		Map<String,Object> result = new LinkedHashMap<String,Object>(template);
		result.put("id", Long.valueOf(id));
		result.put("name", "[Missing NPC " + id + "]");
		result.put("description", "Project-local placeholder for unresolved NPC " + id + ".");
		result.put("command", "");
		result.put("command2", "");
		result.put("attackable", Long.valueOf(0L));
		result.put("aggressive", Long.valueOf(0L));
		return result;
	}

	private static Set<Integer> catalogIds(Map<String,Object> targetCatalog)
		throws IOException {
		if (targetCatalog == null || !targetCatalog.containsKey("npcs")) {
			throw new IOException("Derived target catalog has no NPC family");
		}
		Set<Integer> result = new TreeSet<Integer>();
		int previous = -1;
		for (Object raw : array(targetCatalog.get("npcs"), "derived target NPC catalog")) {
			int id = integer(raw, 0, MAX_ID, "derived target NPC ID");
			if (id <= previous) throw new IOException(
				"Derived target NPC catalog is not sorted and unique");
			previous = id;
			result.add(Integer.valueOf(id));
		}
		return result;
	}

	private static Set<Integer> placementIds(Path root)
		throws IOException, WorldBuilderContractException {
		Path path = root.resolve("server/conf/server/defs/locs/MyWorldNpcLocs.json");
		if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
			return new TreeSet<Integer>();
		}
		try {
			Map<String,Object> value = WorldBuilderJsonDocuments.readObject(path);
			if (value.size() != 1 || !value.containsKey("npclocs")) {
				throw new WorldBuilderDiscoveryException("wrong NPC placement root");
			}
			Set<Integer> result = new TreeSet<Integer>();
			for (Object raw : array(value.get("npclocs"), path.toString())) {
				Map<String,Object> row = object(raw, path.toString());
				result.add(Integer.valueOf(integer(row.get("id"), 0, MAX_ID, "id")));
			}
			return result;
		} catch (WorldBuilderDiscoveryException malformed) {
			throw problem(path.toString(), "NPC placement JSON is malformed.", malformed);
		}
	}

	private static Map<String,Object> definitionDocument(Path root, String relative,
		String key) throws IOException, WorldBuilderContractException {
		Path path = root.resolve(relative);
		try {
			Map<String,Object> value =
				WorldBuilderJsonDocuments.readTargetDefinitionObject(path);
			if (value.size() != 1 || !value.containsKey(key)) throw new IOException("wrong root");
			array(value.get(key), relative);
			return value;
		} catch (WorldBuilderDiscoveryException malformed) {
			throw problem(relative, "NPC definition JSON is malformed.", malformed);
		}
	}

	private static Map<String,Object> object(Object raw, String label) throws IOException {
		if (!(raw instanceof Map)) throw new IOException(label + " is not an object");
		@SuppressWarnings("unchecked") Map<String,Object> value = (Map<String,Object>)raw;
		return value;
	}

	private static List<Object> array(Object raw, String label) throws IOException {
		if (!(raw instanceof List) || ((List<?>)raw).size() > 65536) {
			throw new IOException(label + " is not a bounded array");
		}
		@SuppressWarnings("unchecked") List<Object> value = (List<Object>)raw;
		return value;
	}

	private static void exact(Map<String,Object> value, Set<String> keys, String label)
		throws IOException {
		if (!value.keySet().equals(keys)) throw new IOException(label + " has unexpected keys");
	}

	private static int integer(Object raw, int minimum, int maximum, String label)
		throws IOException {
		if (!(raw instanceof Long) || ((Long)raw).longValue() < minimum
			|| ((Long)raw).longValue() > maximum) throw new IOException(label + " is invalid");
		return ((Long)raw).intValue();
	}

	private static String text(Object raw, int minimum, int maximum, String label)
		throws IOException {
		if (!(raw instanceof String) || ((String)raw).length() < minimum
			|| ((String)raw).length() > maximum || ((String)raw).indexOf('\u0000') >= 0) {
			throw new IOException(label + " is invalid");
		}
		return (String)raw;
	}

	private static Set<String> set(String... values) {
		return Collections.unmodifiableSet(new HashSet<String>(Arrays.asList(values)));
	}

	private static WorldBuilderContractException problem(String path, String message) {
		return problem(path, message, null);
	}

	private static WorldBuilderContractException problem(
		String path, String message, Throwable cause) {
		return new WorldBuilderContractException(WorldBuilderErrorCodes.DEFINITION_MISMATCH,
			"npc-definition-provider", path, false, message,
			"Correct the declarative NPC evidence or use a neutral provider package.", cause);
	}

	static final class Result {
		final byte[] customDefinitions;
		final String providerSha256;
		final List<Item> items;
		final List<Warning> warnings;
		Result(byte[] customDefinitions, String providerSha256,
			List<Item> items, List<Warning> warnings) {
			this.customDefinitions = customDefinitions;
			this.providerSha256 = providerSha256;
			this.items = Collections.unmodifiableList(new ArrayList<Item>(items));
			this.warnings = Collections.unmodifiableList(new ArrayList<Warning>(warnings));
		}
		static Result unchanged() {
			return new Result(null, "", Collections.<Item>emptyList(),
				Collections.<Warning>emptyList());
		}
		boolean changed() { return customDefinitions != null; }
	}

	private static final class Provider {
		final Map<Integer,Map<String,Object>> definitions;
		final String sha256;
		Provider(Map<Integer,Map<String,Object>> definitions, String sha256) {
			this.definitions = definitions; this.sha256 = sha256;
		}
		static Provider empty() {
			return new Provider(Collections.<Integer,Map<String,Object>>emptyMap(), "");
		}
	}

	static final class Item {
		final int npcId; final String status;
		Item(int npcId, String status) { this.npcId = npcId; this.status = status; }
		Map<String,Object> json() {
			Map<String,Object> value = new LinkedHashMap<String,Object>();
			value.put("npcId", Long.valueOf(npcId)); value.put("status", status);
			return value;
		}
	}

	static final class Warning {
		final int npcId; final String code, message;
		Warning(int npcId, String code, String message) {
			this.npcId = npcId; this.code = code; this.message = message;
		}
		Map<String,Object> json() {
			Map<String,Object> value = new LinkedHashMap<String,Object>();
			value.put("npcId", Long.valueOf(npcId)); value.put("code", code);
			value.put("message", message); return value;
		}
	}
}
