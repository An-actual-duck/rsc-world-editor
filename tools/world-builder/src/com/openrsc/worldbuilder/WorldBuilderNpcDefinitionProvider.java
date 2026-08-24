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

	private WorldBuilderNpcDefinitionProvider() {
	}

	static Result consume(Path selectedProviderManifest, Path copiedTarget)
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

		Set<Integer> required = placementIds(copiedTarget);
		required.addAll(overlayIds(copiedTarget,
			"server/conf/server/defs/NpcDefsMyWorld.json"));
		required.addAll(overlayIds(copiedTarget,
			"server/conf/server/defs/NpcDefsPatch18.json"));
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
			Map<String,Object> document = WorldBuilderJsonDocuments.readObject(candidate);
			exact(document, set("schemaVersion", "manifestType", "npcs"), FILE_NAME);
			if (!Long.valueOf(1L).equals(document.get("schemaVersion"))
				|| !TYPE.equals(document.get("manifestType"))) return Provider.empty();
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

	private static Set<Integer> placementIds(Path root)
		throws IOException, WorldBuilderContractException {
		Path path = root.resolve("server/conf/server/defs/locs/MyWorldNpcLocs.json");
		if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) return new TreeSet<Integer>();
		return ids(path, "npclocs");
	}

	private static Set<Integer> overlayIds(Path root, String relative)
		throws IOException, WorldBuilderContractException {
		Path path = root.resolve(relative);
		return Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
			? ids(path, "npcs") : new TreeSet<Integer>();
	}

	private static Set<Integer> ids(Path path, String key)
		throws IOException, WorldBuilderContractException {
		try {
			Map<String,Object> value = WorldBuilderJsonDocuments.readObject(path);
			if (value.size() != 1 || !value.containsKey(key)) throw new IOException("wrong root");
			Set<Integer> result = new TreeSet<Integer>();
			for (Object raw : array(value.get(key), path.toString())) {
				Map<String,Object> row = object(raw, path.toString());
				int id = integer(row.get("id"), 0, MAX_ID, "id");
				result.add(Integer.valueOf(id));
			}
			return result;
		} catch (WorldBuilderDiscoveryException malformed) {
			throw problem(path.toString(), "NPC definition/placement JSON is malformed.", malformed);
		}
	}

	private static Map<String,Object> definitionDocument(Path root, String relative,
		String key) throws IOException, WorldBuilderContractException {
		Path path = root.resolve(relative);
		try {
			Map<String,Object> value = WorldBuilderJsonDocuments.readObject(path);
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
