package com.openrsc.worldbuilder;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
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
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Normalizes sparse target/provider NPC definitions into the sequential runtime
 * registry without executing target code. Rich providers also bind every used
 * animation to its exact custom and authentic sprite payloads. Missing or
 * unusable definitions become explicit project-local placeholders and
 * actionable warnings.
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
	private static final long MAX_EXPANDED_BYTES = 512L * 1024L * 1024L;
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
		return consume(selectedProviderManifest, copiedTarget, targetCatalog,
			Collections.<Integer>emptySet());
	}

	static Result consume(Path selectedProviderManifest, Path copiedTarget,
		Map<String,Object> targetCatalog, Set<Integer> effectiveNpcIds)
		throws IOException, WorldBuilderContractException {
		Map<String,Object> base = definitionDocument(copiedTarget,
			"server/conf/server/defs/NpcDefs.json", "npcs");
		List<Object> baseRows = array(base.get("npcs"), "NpcDefs.json");
		WorldBuilderPackedSourceLayout layout = WorldBuilderPackedSourceLayout.canonical(
			WorldBuilderPackedSourceLayout.CANONICAL_CONFIGURATION);
		List<Object> customRows = WorldBuilderSupplementalNpcDefinitions
			.mergedCustomRows(copiedTarget, layout);
		int appendedCount = baseRows.size() + customRows.size();
		if (appendedCount < 1) throw problem("server/conf/server/defs/NpcDefs.json",
			"Target NPC definitions contain no sequential base record.");

		Set<Integer> required = catalogIds(targetCatalog);
		Set<Integer> placements = placementIds(copiedTarget);
		required.addAll(placements);
		for (Integer id : effectiveNpcIds) {
			if (id == null || id.intValue() < 0 || id.intValue() > MAX_ID) {
				throw problem("effective NPC placements",
					"Effective NPC placement ID exceeds the runtime domain 0..65535.");
			}
			required.add(id);
		}
		Set<Integer> providerPlacements = effectiveNpcIds.isEmpty()
			? placements : new TreeSet<Integer>(effectiveNpcIds);
		int maximum = required.isEmpty() ? -1 : Collections.max(required).intValue();
		if (maximum < appendedCount) return Result.unchanged();
		if (maximum > MAX_ID) throw problem("npc placements",
			"Required NPC ID exceeds the runtime domain 0..65535.");

		Provider provider = readProvider(selectedProviderManifest, copiedTarget,
			appendedCount - 1, providerPlacements);
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
					provider.animationFailure == null
						? "NPC_DEFINITION_PLACEHOLDER" : "NPC_ANIMATION_PLACEHOLDER",
					provider.unavailableReason != null
						? provider.unavailableReason
						: provider.animationFailure == null
						? "No authoritative provider definition exists for NPC " + id
							+ "; a deterministic project-local placeholder was generated."
						: "NPC " + id + " animation evidence is unusable: "
							+ provider.animationFailure
							+ " A deterministic project-local placeholder was generated."));
				items.add(new Item(id, requiredId ? "placeholder" : "gap-placeholder"));
			} else {
				items.add(new Item(id, "resolved"));
			}
			rewritten.add(definition);
		}
		Map<String,Object> document = new LinkedHashMap<String,Object>();
		document.put("npcs", rewritten);
		return new Result(WorldBuilderJsonDocuments.pretty(document)
			.getBytes(StandardCharsets.UTF_8), provider.sha256, items, warnings,
			provider.animations);
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
		List<Object> animations = new ArrayList<Object>();
		for (Animation item : result.animations) animations.add(item.json());
		report.put("animations", animations);
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
			String firstReason = null;
			for (Object raw : (List<?>)rawWarnings) {
				if (!(raw instanceof Map)) continue;
				Map<?,?> warning = (Map<?,?>)raw;
				if (!(warning.get("code") instanceof String)
					|| !((String)warning.get("code")).startsWith("NPC_")
					|| !((String)warning.get("code")).endsWith("_PLACEHOLDER")) continue;
				Object id = warning.get("npcId");
				if (id instanceof Number) ids.add(Integer.valueOf(((Number)id).intValue()));
				if (firstReason == null && warning.get("message") instanceof String) {
					firstReason = (String)warning.get("message");
				}
			}
			if (ids.isEmpty()) return null;
			return "\n\nNPC provider warning: complete definitions and animation visuals were not "
				+ "available for NPC IDs " + ids + ". They will appear as clearly named placeholders "
				+ "using NPC 0's visuals. Install a complete provider containing "
				+ FILE_NAME + " and recreate this project for faithful NPC visuals.\n"
				+ (firstReason == null ? "" : "Reason: " + firstReason + "\n")
				+ "Details: " + report;
		} catch (Exception ignored) {
			return null;
		}
	}

	private static Provider readProvider(Path selected, Path copiedTarget,
		int declarativeMaximum, Set<Integer> placements)
		throws WorldBuilderContractException {
		if (selected == null || selected.getParent() == null) return Provider.unavailable(
			"No local custom-content provider was selected for the project.");
		Path root = selected.toAbsolutePath().normalize().getParent();
		Path candidate = root.resolve(FILE_NAME).normalize();
		try {
			if (!candidate.startsWith(root)
				|| !Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)
				|| Files.isSymbolicLink(candidate) || Files.size(candidate) < 1L
				|| Files.size(candidate) > MAX_BYTES) return Provider.unavailable(
					"The selected local provider has no safe bounded " + FILE_NAME + ".");
			validatePackageInventory(root, candidate);
			Map<String,Object> document =
				WorldBuilderJsonDocuments.readTargetDefinitionObject(candidate);
			if (!Long.valueOf(1L).equals(document.get("schemaVersion"))) {
				return Provider.unavailable("The discovered NPC provider uses an unsupported schema version.");
			}
			if (PRODUCER_TYPE.equals(document.get("manifestType"))) {
				return readProducerProvider(root, candidate, copiedTarget,
					declarativeMaximum, placements, document);
			}
			exact(document, set("schemaVersion", "manifestType", "npcs"), FILE_NAME);
			if (!TYPE.equals(document.get("manifestType"))) return Provider.unavailable(
				"The discovered NPC provider uses an unsupported manifest type.");
			List<Object> rows = array(document.get("npcs"), FILE_NAME);
			TreeMap<Integer,Map<String,Object>> definitions =
				new TreeMap<Integer,Map<String,Object>>();
			int previous = -1;
			for (int index = 0; index < rows.size(); index++) {
				Map<String,Object> row = object(rows.get(index), FILE_NAME + "#record=" + index);
				exact(row, RECORD_KEYS, FILE_NAME + "#record=" + index);
				int id = integer(row.get("npcId"), 0, MAX_ID, "npcId");
				if (id <= previous) return Provider.unavailable(
					"The discovered NPC provider records are not sorted and unique.");
				previous = id;
				String name = text(row.get("name"), 1, 256, "name");
				Map<String,Object> definition = normalizeDefinition(
					object(row.get("definition"), "definition"), id, name);
				definitions.put(Integer.valueOf(id), definition);
			}
			return new Provider(definitions, WorldBuilderHashes.sha256(candidate),
				Collections.<Animation>emptyList(), null, null);
		} catch (TargetMismatch mismatch) {
			throw providerMismatch(candidate.toString(), mismatch.getMessage(), mismatch);
		} catch (AnimationFailure invalid) {
			return Provider.animationFailure(invalid.getMessage());
		} catch (Exception invalid) {
			return Provider.unavailable(stableProviderFailure(invalid, root, candidate));
		}
	}

	private static String stableProviderFailure(Exception failure, Path root, Path candidate) {
		String message = failure.getMessage();
		if (message == null || message.trim().isEmpty()) {
			return "The discovered NPC provider is malformed or incompatible."
				+ " Validation failed with " + failure.getClass().getSimpleName() + ".";
		}
		String stable = message.replace(candidate.toString(), "<npc-manifest>")
			.replace(root.toString(), "<provider-root>")
			.replace('\n', ' ').replace('\r', ' ').trim();
		if (stable.length() > 512) stable = stable.substring(0, 512) + "…";
		return "The discovered NPC provider was rejected: " + stable;
	}

	/**
	 * Consumes the richer neutral producer contract without requiring the
	 * producer to duplicate the runtime's legacy server-definition shape.
	 * Fields which do not exist in the producer contract are deliberately inert
	 * in Builder mode; identity, appearance, commands, stats, and dimensions are
	 * retained exactly.
	 */
	private static Provider readProducerProvider(Path root, Path candidate,
		Path copiedTarget, int declarativeMaximum, Set<Integer> placements,
		Map<String,Object> document) throws IOException {
		exact(document, PRODUCER_ROOT_KEYS, FILE_NAME);
		Map<String,Object> metadata = object(document.get("provider"), "provider");
		validateProducerMetadata(metadata);
		Map<String,Object> assets = object(document.get("assetProviders"), "assetProviders");
		AssetIndexes assetIndexes;
		try {
			assetIndexes = validateProducerAssets(root, assets);
		} catch (AnimationFailure invalid) {
			throw invalid;
		} catch (IOException invalid) {
			throw new AnimationFailure("The NPC animation asset provider is malformed: "
				+ invalid.getMessage(), invalid);
		}
		List<Object> animations = array(document.get("animationDefinitions"),
			"animationDefinitions");
		Set<Integer> animationIds = new TreeSet<Integer>();
		Map<Integer,Animation> animationEvidence = new TreeMap<Integer,Animation>();
		int previousAnimation = -1;
		for (int index = 0; index < animations.size(); index++) {
			try {
				Map<String,Object> animation = object(animations.get(index),
					"animationDefinitions#record=" + index);
				Animation evidence = validateProducerAnimation(animation, assetIndexes);
				int id = integer(animation.get("animationId"), 0, MAX_ID,
					"animationId");
				if (id <= previousAnimation) throw new AnimationFailure(
					"Producer animation definitions are not sorted and unique.");
				previousAnimation = id;
				animationIds.add(Integer.valueOf(id));
				animationEvidence.put(Integer.valueOf(id), evidence);
			} catch (AnimationFailure invalid) {
				throw invalid;
			} catch (IOException invalid) {
				throw new AnimationFailure("Animation record " + index
					+ " is malformed: " + invalid.getMessage(), invalid);
			}
		}

		List<Object> rows = array(document.get("npcDefinitions"), "npcDefinitions");
		TreeMap<Integer,Map<String,Object>> definitions =
			new TreeMap<Integer,Map<String,Object>>();
		Set<Integer> referencedAnimationIds = new TreeSet<Integer>();
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
				if (animation >= 0) referencedAnimationIds.add(Integer.valueOf(animation));
			}
			definitions.put(Integer.valueOf(id), producerDefinition(row, id, name, sprites));
		}
		Map<String,Object> selection = object(document.get("selection"), "selection");
		validateProducerSelection(selection,
			definitions.keySet());
		if (!referencedAnimationIds.equals(animationIds)) throw new AnimationFailure(
			"The provider animation inventory contains missing or unreferenced animation IDs.");
		validateProducerTarget(copiedTarget, declarativeMaximum, placements,
			metadata, assets, selection);
		return new Provider(definitions, WorldBuilderHashes.sha256(candidate),
			new ArrayList<Animation>(animationEvidence.values()), null, null);
	}

	private static void validateProducerTarget(Path copiedTarget, int declarativeMaximum,
		Set<Integer> placements,
		Map<String,Object> provider, Map<String,Object> assets,
		Map<String,Object> selection) throws IOException {
		if (copiedTarget == null) throw new TargetMismatch(
			"Provider compatibility cannot be proven without an immutable target copy.");
		Map<String,String> expected = new TreeMap<String,String>();
		for (Object raw : array(provider.get("sources"), "provider.sources")) {
			Map<String,Object> source = object(raw, "provider source");
			String role = text(source.get("role"), 1, 96, "provider source role");
			String identity = text(source.get("identity"), 1, 512,
				"provider source identity");
			if (("declarative-npc-registry".equals(role)
					&& ("NpcDefs.json".equals(identity)
						|| "NpcDefsCustom.json".equals(identity)))
				|| ("authoritative-npc-placements".equals(role)
					&& "MyWorldNpcLocs.json".equals(identity))) {
				if (expected.put(identity, hash(source.get("sha256"),
					"provider source sha256")) != null) {
					throw new TargetMismatch("Provider repeats target binding " + identity + ".");
				}
			}
		}
		for (String identity : Arrays.asList("NpcDefs.json", "NpcDefsCustom.json")) {
			if (!expected.containsKey(identity)) throw new TargetMismatch(
				"Provider is missing required target binding " + identity + ".");
			String relative = "server/conf/server/defs/" + identity;
			Path actual = copiedTarget.resolve(relative);
			if (!Files.isRegularFile(actual, LinkOption.NOFOLLOW_LINKS)
				|| Files.isSymbolicLink(actual)
				|| !expected.get(identity).equals(WorldBuilderHashes.sha256(actual))) {
				throw new TargetMismatch("Provider was generated for a different "
					+ identity + ".");
			}
		}
		if (!expected.containsKey("MyWorldNpcLocs.json")) throw new TargetMismatch(
			"Provider is missing required target binding MyWorldNpcLocs.json.");

		for (String key : Arrays.asList("customSpriteArchive", "authenticSpriteArchive")) {
			Map<String,Object> asset = object(assets.get(key), key);
			String file = "customSpriteArchive".equals(key)
				? "Custom_Sprites.osar" : "Authentic_Sprites.orsc";
			Path actual = copiedTarget.resolve("Client_Base/Cache/video/" + file);
			if (!Files.isRegularFile(actual, LinkOption.NOFOLLOW_LINKS)
				|| Files.isSymbolicLink(actual)
				|| !hash(asset.get("sha256"), key + ".sha256")
					.equals(WorldBuilderHashes.sha256(actual))) {
				throw new TargetMismatch("Provider was generated for a different " + file + ".");
			}
		}

		if (integer(selection.get("declarativeMaximumNpcId"), 0, MAX_ID,
			"declarativeMaximumNpcId") != declarativeMaximum) {
			throw new TargetMismatch("Provider declarative NPC boundary differs from the target.");
		}
		Set<Integer> extensionPlacements = new TreeSet<Integer>(placements);
		extensionPlacements.removeIf(id -> id.intValue() <= declarativeMaximum);
		Set<Integer> selected = new TreeSet<Integer>();
		for (Object raw : array(selection.get("placedNpcIds"), "placedNpcIds")) {
			selected.add(Integer.valueOf(integer(raw, 0, MAX_ID, "placedNpcId")));
		}
		// Placement coordinates and counts may change during ordinary world editing.
		// Reuse the provider while the extension identity set, declarative registry,
		// and sprite archives still match; those are the authorities that determine
		// NPC definitions and visuals.
		if (!extensionPlacements.equals(selected)) throw new TargetMismatch(
			"Provider placed extension NPC set differs from the target.");
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

	private static AssetIndexes validateProducerAssets(
		Path root, Map<String,Object> assets)
		throws IOException {
		exact(assets, set("customSpriteArchive", "authenticSpriteArchive"),
			"assetProviders");
		Path customPath = null;
		Path authenticPath = null;
		int customCount = -1;
		int authenticCount = -1;
		for (String key : Arrays.asList("customSpriteArchive", "authenticSpriteArchive")) {
			Map<String,Object> asset = object(assets.get(key), key);
			exact(asset, "customSpriteArchive".equals(key)
				? set("path", "sha256", "entryCount")
				: set("path", "sha256", "numericEntryCount"), key);
			String relative = text(asset.get("path"), 1, 512, key + ".path");
			String hash = hash(asset.get("sha256"), key + ".sha256");
			int declaredCount = integer(asset.get("customSpriteArchive".equals(key)
				? "entryCount" : "numericEntryCount"), 0, MAX_FILES,
				key + ".entryCount");
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
			if ("customSpriteArchive".equals(key)) {
				customPath = path; customCount = declaredCount;
			} else {
				authenticPath = path; authenticCount = declaredCount;
			}
		}
		try {
			Map<String,SpriteEntry> custom = readOsar(customPath);
			Map<Integer,String> authentic = readAuthentic(authenticPath);
			if (custom.size() != customCount || authentic.size() != authenticCount) {
				throw new AnimationFailure(
					"The selected NPC sprite archive inventory counts differ from the provider.");
			}
			return new AssetIndexes(custom, authentic);
		} catch (AnimationFailure invalid) {
			throw invalid;
		} catch (IOException invalid) {
			throw new AnimationFailure(
				"The selected NPC sprite archives could not be indexed safely.", invalid);
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

	private static Animation validateProducerAnimation(
		Map<String,Object> animation, AssetIndexes assets)
		throws IOException {
		exact(animation, PRODUCER_ANIMATION_KEYS, "animation definition");
		int animationId = integer(animation.get("animationId"), 0, MAX_ID,
			"animationId");
		String name = osarName(text(animation.get("name"), 1, 128,
			"animation name"), "Animation name");
		String category = osarName(text(animation.get("category"), 1, 128,
			"animation category"), "Animation category");
		int charColour = integer(animation.get("charColour"), Integer.MIN_VALUE,
			Integer.MAX_VALUE, "charColour");
		int blueMask = integer(animation.get("blueMask"), Integer.MIN_VALUE,
			Integer.MAX_VALUE, "blueMask");
		int genderModel = integer(animation.get("genderModel"), Integer.MIN_VALUE,
			Integer.MAX_VALUE, "genderModel");
		boolean combat = bool(animation.get("hasCombatFrames"), "hasCombatFrames");
		boolean special = bool(animation.get("hasSpecialCombatFrames"),
			"hasSpecialCombatFrames");
		if (special && !combat) throw new AnimationFailure(
			"Animation " + animationId
				+ " declares special-combat frames without combat frames.");
		int count = integer(animation.get("requiredFrameCount"), 1, 4096,
			"requiredFrameCount");
		int rendererCount = 15 + (combat ? 3 : 0) + (special ? 9 : 0);
		if (count != rendererCount) throw new AnimationFailure(
			"Animation " + animationId + " requires " + rendererCount
				+ " renderer frames but declares " + count + ".");
		Map<String,Object> custom = object(animation.get("customArchive"),
			"customArchive");
		exact(custom, set("subspace", "entry", "frameCount", "entrySha256",
			"spritepackOverrideKey"), "customArchive");
		String subspace = portable(text(custom.get("subspace"), 1, 128,
			"custom subspace"));
		String entry = portable(text(custom.get("entry"), 1, 128, "custom entry"));
		if (!category.equals(subspace) || !name.equals(entry)) {
			throw new AnimationFailure("Animation " + animationId
				+ " custom lookup differs from its runtime category/name identity.");
		}
		if (integer(custom.get("frameCount"), 1, 4096, "custom frameCount") != count) {
			throw new IOException("custom animation frame count is inconsistent");
		}
		String customHash = hash(custom.get("entrySha256"), "custom entrySha256");
		String override = text(custom.get("spritepackOverrideKey"), 1, 512,
			"spritepackOverrideKey");
		if (!(category + ":" + name).equals(override)) throw new AnimationFailure(
			"Animation " + animationId + " spritepack override identity is inconsistent.");
		SpriteEntry customEntry = assets.custom.get(category + "/" + name);
		if (customEntry == null || customEntry.frames != count
			|| !customHash.equals(customEntry.sha256)) throw new AnimationFailure(
			"Animation " + animationId + " custom OSAR entry " + category + "/"
				+ name + " is absent, has the wrong frame count, or differs from its hash.");

		Map<String,Object> authentic = object(animation.get("authenticArchive"),
			"authenticArchive");
		exact(authentic, set("baseSpriteId", "frames"), "authenticArchive");
		int baseSpriteId = integer(authentic.get("baseSpriteId"), 0, MAX_ID,
			"baseSpriteId");
		List<Object> frames = array(authentic.get("frames"), "authentic frames");
		if (frames.size() != count) throw new IOException(
			"authentic animation frame count is inconsistent");
		int previous = baseSpriteId - 1;
		List<String> authenticHashes = new ArrayList<String>();
		for (int index = 0; index < frames.size(); index++) {
			Object raw = frames.get(index);
			Map<String,Object> frame = object(raw, "authentic frame");
			exact(frame, set("spriteId", "entrySha256"), "authentic frame");
			int spriteId = integer(frame.get("spriteId"), 0, MAX_ID, "spriteId");
			if (spriteId != baseSpriteId + index || spriteId <= previous) {
				throw new AnimationFailure("Animation " + animationId
					+ " authentic sprite IDs are not consecutive from the declared base.");
			}
			previous = spriteId;
			String frameHash = hash(frame.get("entrySha256"),
				"authentic frame entrySha256");
			authenticHashes.add(frameHash);
			if (!frameHash.equals(assets.authentic.get(Integer.valueOf(spriteId)))) {
				throw new AnimationFailure("Animation " + animationId
					+ " authentic sprite " + spriteId
					+ " is absent or differs from its declared hash.");
			}
		}
		return new Animation(animationId, category, name, charColour, blueMask,
			genderModel, combat, special, count, customHash, baseSpriteId,
			authenticHashes);
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

	private static boolean bool(Object raw, String label) throws IOException {
		if (!(raw instanceof Boolean)) throw new IOException(label + " is invalid");
		return ((Boolean)raw).booleanValue();
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

	private static String osarName(String value, String label) throws IOException {
		if (!value.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")) {
			throw new AnimationFailure(label + " is not a portable OSAR name.");
		}
		return value;
	}

	private static Map<String,SpriteEntry> readOsar(Path path)
		throws IOException {
		byte[] expanded;
		try (InputStream input = new GZIPInputStream(Files.newInputStream(path));
			ByteArrayOutputStream output = new ByteArrayOutputStream()) {
			byte[] buffer = new byte[8192];
			long total = 0L;
			for (int read; (read = input.read(buffer)) >= 0;) {
				if (read == 0) continue;
				total += read;
				if (total > MAX_EXPANDED_BYTES) throw new AnimationFailure(
					"The custom NPC sprite archive expands beyond 512 MiB.");
				output.write(buffer, 0, read);
			}
			expanded = output.toByteArray();
		} catch (AnimationFailure invalid) {
			throw invalid;
		} catch (IOException invalid) {
			throw new AnimationFailure(
				"The custom NPC sprite archive is not a readable GZIP OSAR.", invalid);
		}
		try {
			SpriteInput input = new SpriteInput(expanded);
			int subspaceCount = input.u8();
			if (subspaceCount < 1) throw new IllegalArgumentException("no subspaces");
			Map<String,SpriteEntry> result = new TreeMap<String,SpriteEntry>();
			Set<String> folded = new TreeSet<String>();
			Set<String> foldedSubspaces = new TreeSet<String>();
			int count = 0;
			for (int subspaceIndex = 0; subspaceIndex < subspaceCount;
				subspaceIndex++) {
				String subspace = osarName(input.name(), "Custom animation subspace");
				if (!foldedSubspaces.add(subspace.toLowerCase(java.util.Locale.ROOT))) {
					throw new IllegalArgumentException("colliding subspace");
				}
				int entries = input.u16();
				for (int entryIndex = 0; entryIndex < entries; entryIndex++) {
					if (++count > MAX_FILES) throw new IllegalArgumentException(
						"too many entries");
					int start = input.offset;
					String entry = osarName(input.name(), "Custom animation entry");
					String key = subspace + "/" + entry;
					if (!folded.add(key.toLowerCase(java.util.Locale.ROOT))) {
						throw new IllegalArgumentException("colliding entry");
					}
					int frames = input.sprite();
					byte[] payload = Arrays.copyOfRange(expanded, start, input.offset);
					result.put(key, new SpriteEntry(frames,
						WorldBuilderHashes.sha256(payload)));
				}
			}
			if (input.remaining() != 0) throw new IllegalArgumentException("trailing data");
			return result;
		} catch (AnimationFailure invalid) {
			throw invalid;
		} catch (RuntimeException invalid) {
			throw new AnimationFailure(
				"The custom NPC sprite archive has unsafe names or malformed frames.", invalid);
		}
	}

	private static Map<Integer,String> readAuthentic(Path path)
		throws IOException {
		Map<Integer,String> result = new TreeMap<Integer,String>();
		Set<String> folded = new TreeSet<String>();
		try (ZipFile archive = new ZipFile(path.toFile())) {
			java.util.Enumeration<? extends ZipEntry> entries = archive.entries();
			int count = 0;
			while (entries.hasMoreElements()) {
				ZipEntry entry = entries.nextElement();
				if (++count > MAX_FILES || entry.isDirectory()) throw new AnimationFailure(
					"The authentic NPC sprite archive inventory is unsafe or excessive.");
				String name = portable(entry.getName());
				if (!folded.add(name.toLowerCase(java.util.Locale.ROOT))) {
					throw new AnimationFailure(
						"The authentic NPC sprite archive contains colliding names.");
				}
				String leaf = name.substring(name.lastIndexOf('/') + 1);
				if (leaf.endsWith(".dat")) leaf = leaf.substring(0, leaf.length() - 4);
				if (!leaf.matches("0|[1-9][0-9]{0,4}")) continue;
				int id = Integer.parseInt(leaf);
				if (id > MAX_ID || result.containsKey(Integer.valueOf(id))) {
					throw new AnimationFailure(
						"The authentic NPC sprite archive repeats or exceeds its ID domain.");
				}
				try (InputStream input = archive.getInputStream(entry);
					ByteArrayOutputStream payload = new ByteArrayOutputStream()) {
					byte[] buffer = new byte[8192];
					for (int read; (read = input.read(buffer)) >= 0;) {
						if (read == 0) continue;
						if ((long)payload.size() + read > MAX_BYTES) throw new AnimationFailure(
							"An authentic NPC sprite entry exceeds 16 MiB.");
						payload.write(buffer, 0, read);
					}
					if (payload.size() < 1) throw new AnimationFailure(
						"An authentic NPC sprite entry is empty.");
					result.put(Integer.valueOf(id),
						WorldBuilderHashes.sha256(payload.toByteArray()));
				}
			}
		} catch (AnimationFailure invalid) {
			throw invalid;
		} catch (IOException invalid) {
			throw new AnimationFailure(
				"The authentic NPC sprite archive is not a readable bounded ZIP.", invalid);
		}
		return result;
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

	private static WorldBuilderContractException providerMismatch(
		String path, String message, Throwable cause) {
		return new WorldBuilderContractException(WorldBuilderErrorCodes.CAPABILITY_MISMATCH,
			"npc-definition-provider-compatibility", path, false,
			"Selected provider does not match this server revision: " + message,
			"Regenerate the server-root world-builder-provider package from this exact "
				+ "server revision and create the project again.", cause);
	}

	private static final class TargetMismatch extends IOException {
		private static final long serialVersionUID = 1L;
		TargetMismatch(String message) { super(message); }
	}

	private static final class AnimationFailure extends IOException {
		private static final long serialVersionUID = 1L;
		AnimationFailure(String message) { super(message); }
		AnimationFailure(String message, Throwable cause) { super(message, cause); }
	}

	static final class Result {
		final byte[] customDefinitions;
		final String providerSha256;
		final List<Item> items;
		final List<Warning> warnings;
		final List<Animation> animations;
		Result(byte[] customDefinitions, String providerSha256,
			List<Item> items, List<Warning> warnings, List<Animation> animations) {
			this.customDefinitions = customDefinitions;
			this.providerSha256 = providerSha256;
			this.items = Collections.unmodifiableList(new ArrayList<Item>(items));
			this.warnings = Collections.unmodifiableList(new ArrayList<Warning>(warnings));
			this.animations = Collections.unmodifiableList(
				new ArrayList<Animation>(animations));
		}
		static Result unchanged() {
			return new Result(null, "", Collections.<Item>emptyList(),
				Collections.<Warning>emptyList(),
				Collections.<Animation>emptyList());
		}
		boolean changed() { return customDefinitions != null; }
	}

	private static final class Provider {
		final Map<Integer,Map<String,Object>> definitions;
		final String sha256;
		final List<Animation> animations;
		final String animationFailure;
		final String unavailableReason;
		Provider(Map<Integer,Map<String,Object>> definitions, String sha256,
			List<Animation> animations, String animationFailure,
			String unavailableReason) {
			this.definitions = definitions; this.sha256 = sha256;
			this.animations = animations; this.animationFailure = animationFailure;
			this.unavailableReason = unavailableReason;
		}
		static Provider unavailable(String reason) {
			return new Provider(Collections.<Integer,Map<String,Object>>emptyMap(), "",
				Collections.<Animation>emptyList(), null, reason);
		}
		static Provider animationFailure(String message) {
			return new Provider(Collections.<Integer,Map<String,Object>>emptyMap(), "",
				Collections.<Animation>emptyList(), message, null);
		}
	}

	static final class Animation {
		final int animationId;
		final String category;
		final String name;
		final int charColour;
		final int blueMask;
		final int genderModel;
		final boolean hasCombatFrames;
		final boolean hasSpecialCombatFrames;
		final int requiredFrameCount;
		final String customEntrySha256;
		final int authenticBaseSpriteId;
		final List<String> authenticFrameSha256s;

		Animation(int animationId, String category, String name, int charColour,
			int blueMask, int genderModel, boolean hasCombatFrames,
			boolean hasSpecialCombatFrames, int requiredFrameCount,
			String customEntrySha256, int authenticBaseSpriteId,
			List<String> authenticFrameSha256s) {
			this.animationId = animationId; this.category = category;
			this.name = name; this.charColour = charColour; this.blueMask = blueMask;
			this.genderModel = genderModel; this.hasCombatFrames = hasCombatFrames;
			this.hasSpecialCombatFrames = hasSpecialCombatFrames;
			this.requiredFrameCount = requiredFrameCount;
			this.customEntrySha256 = customEntrySha256;
			this.authenticBaseSpriteId = authenticBaseSpriteId;
			this.authenticFrameSha256s = Collections.unmodifiableList(
				new ArrayList<String>(authenticFrameSha256s));
		}

		Map<String,Object> json() {
			Map<String,Object> value = new LinkedHashMap<String,Object>();
			value.put("animationId", Long.valueOf(animationId));
			value.put("category", category); value.put("name", name);
			value.put("requiredFrameCount", Long.valueOf(requiredFrameCount));
			value.put("customEntrySha256", customEntrySha256);
			value.put("authenticBaseSpriteId", Long.valueOf(authenticBaseSpriteId));
			value.put("status", "resolved");
			return value;
		}

		Map<String,Object> registryJson() {
			Map<String,Object> value = new LinkedHashMap<String,Object>();
			value.put("animationId", Long.valueOf(animationId));
			value.put("name", name); value.put("category", category);
			value.put("charColour", Long.valueOf(charColour));
			value.put("blueMask", Long.valueOf(blueMask));
			value.put("genderModel", Long.valueOf(genderModel));
			value.put("hasCombatFrames", Boolean.valueOf(hasCombatFrames));
			value.put("hasSpecialCombatFrames",
				Boolean.valueOf(hasSpecialCombatFrames));
			value.put("requiredFrameCount", Long.valueOf(requiredFrameCount));
			value.put("customSpriteSubspace", category);
			value.put("customSpriteEntry", name);
			value.put("customEntrySha256", customEntrySha256);
			value.put("authenticBaseSpriteId",
				Long.valueOf(authenticBaseSpriteId));
			value.put("authenticFrameSha256s",
				new ArrayList<String>(authenticFrameSha256s));
			return value;
		}
	}

	private static final class AssetIndexes {
		final Map<String,SpriteEntry> custom;
		final Map<Integer,String> authentic;
		AssetIndexes(Map<String,SpriteEntry> custom, Map<Integer,String> authentic) {
			this.custom = custom; this.authentic = authentic;
		}
	}

	private static final class SpriteEntry {
		final int frames;
		final String sha256;
		SpriteEntry(int frames, String sha256) {
			this.frames = frames; this.sha256 = sha256;
		}
	}

	private static final class SpriteInput {
		final byte[] bytes;
		int offset;
		SpriteInput(byte[] bytes) { this.bytes = bytes; }
		int u8() {
			if (offset >= bytes.length) throw new IllegalArgumentException("truncated OSAR");
			return bytes[offset++] & 0xff;
		}
		int u16() { return u8() << 8 | u8(); }
		String name() {
			StringBuilder value = new StringBuilder();
			while (true) {
				int next = u8();
				if (next == 0) break;
				if (value.length() >= 128) throw new IllegalArgumentException(
					"long OSAR name");
				value.append((char)next);
			}
			if (value.length() == 0) throw new IllegalArgumentException("empty OSAR name");
			return value.toString();
		}
		int sprite() {
			int type = u8();
			if (type > 4) throw new IllegalArgumentException("entry type");
			if (type >= 1 && type <= 3 && u8() > 11) {
				throw new IllegalArgumentException("entry layer");
			}
			int frames = u8();
			if (frames < 1) throw new IllegalArgumentException("empty entry");
			int palette = u8() + 1;
			skip((long)palette * 3L);
			for (int frame = 0; frame < frames; frame++) {
				int width = u16(), height = u16(), shifted = u8();
				u16(); u16(); u16(); u16();
				long pixels = (long)width * (long)height;
				if (width < 1 || height < 1 || shifted > 1 || pixels > 16777216L) {
					throw new IllegalArgumentException("frame dimensions");
				}
				for (long pixel = 0L; pixel < pixels; pixel++) {
					if (u8() >= palette) throw new IllegalArgumentException(
						"pixel palette index");
				}
			}
			return frames;
		}
		void skip(long count) {
			if (count < 0L || count > remaining()) throw new IllegalArgumentException(
				"truncated OSAR payload");
			offset += (int)count;
		}
		int remaining() { return bytes.length - offset; }
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
