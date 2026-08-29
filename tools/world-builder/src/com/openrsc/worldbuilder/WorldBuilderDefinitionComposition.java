package com.openrsc.worldbuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Resolves the effective item/NPC definition layers selected by one server
 * configuration. Historical patch files remain immutable source evidence, but
 * only the patch named by based_config_data participates in the project bundle.
 */
final class WorldBuilderDefinitionComposition {
	static final String PROJECT_RELATIVE_PATH =
		"diagnostics/definition-composition-v1.json";
	private static final String TYPE = "world-builder-definition-composition";
	private static final String ZERO_HASH =
		"0000000000000000000000000000000000000000000000000000000000000000";

	private WorldBuilderDefinitionComposition() {
	}

	static Profile inspect(WorldBuilderReadOnlyTarget target,
		WorldBuilderPackedSourceLayout layout) throws WorldBuilderContractException {
		if (!target.exists(layout.configurationPath)) {
			// Descriptor-backed targets may declare the complete definition closure
			// without exposing their ordinary gameplay configuration. Preserve the
			// v1 contract's supplied Patch18/world closure for those old descriptors;
			// new packed discovery with a real config always uses the explicit profile.
			String npc = layout.definitionPath("NpcDefsPatch18.json");
			String item = layout.definitionPath("ItemDefsPatch18.json");
			return new Profile(layout, 18, true, "", "",
				target.exists(npc) ? npc : "", target.exists(item) ? item : "");
		}
		Path path = target.requiredFile(layout.configurationPath);
		try {
			WorldBuilderDiscovery.Config config = WorldBuilderDiscovery.Config.read(path);
			int basedConfigData = config.optionalInt("based_config_data", 85);
			if (basedConfigData < 0 || basedConfigData > 65535) {
				throw problem(layout.configurationPath,
					"based_config_data is outside 0..65535.",
					"Choose a bounded server definition profile and retry discovery.");
			}
			boolean wantMyWorld = config.optionalBoolean("want_myworld", false);
			String npcPatch = selectedPatch(target, layout, "NpcDefsPatch",
				basedConfigData);
			String itemPatch = selectedPatch(target, layout, "ItemDefsPatch",
				basedConfigData);
			return new Profile(layout, basedConfigData, wantMyWorld,
				layout.configurationPath, WorldBuilderHashes.sha256(path),
				npcPatch, itemPatch);
		} catch (WorldBuilderContractException failure) {
			throw failure;
		} catch (WorldBuilderDiscoveryException failure) {
			throw problem(layout.configurationPath,
				"Definition profile selection is malformed: " + failure.getMessage(),
				"Correct the selected server configuration and retry discovery.", failure);
		} catch (IOException failure) {
			throw problem(layout.configurationPath,
				"Definition profile selection changed while it was read.",
				"Stop target updates and retry discovery.", failure);
		}
	}

	private static String selectedPatch(WorldBuilderReadOnlyTarget target,
		WorldBuilderPackedSourceLayout layout, String stem, int basedConfigData)
		throws WorldBuilderContractException {
		if (basedConfigData >= 85) return "";
		String relative = layout.definitionPath(stem + basedConfigData + ".json");
		return target.exists(relative) ? relative : "";
	}

	static byte[] effectiveJson(Profile profile, Path root, String role,
		String fallbackRelativePath) throws IOException, WorldBuilderContractException {
		String source = profile.sourceFor(role, fallbackRelativePath);
		if (source.isEmpty()) {
			String array = role.startsWith("definition.npc.") ? "npcs" : "items";
			Map<String,Object> empty = new LinkedHashMap<String,Object>();
			empty.put(array, new ArrayList<Object>());
			return WorldBuilderJsonDocuments.pretty(empty).getBytes(StandardCharsets.UTF_8);
		}
		Path path = root.resolve(source).normalize();
		if (!path.startsWith(root.toAbsolutePath().normalize())
			|| !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
			|| Files.isSymbolicLink(path)) {
			throw problem(source, "Selected definition layer is unavailable or unsafe.",
				"Rediscover from one stable complete server content set.");
		}
		return Files.readAllBytes(path);
	}

	static void writeReport(Path projectStage, Path targetRoot, Profile profile,
		Path effectiveBundleRoot) throws IOException, WorldBuilderContractException {
		List<Object> families = new ArrayList<Object>();
		families.add(composeNpc(targetRoot, effectiveBundleRoot, profile));
		families.add(composeItems(targetRoot, effectiveBundleRoot, profile));

		Map<String,Object> configuration = new LinkedHashMap<String,Object>();
		configuration.put("relativePath", profile.configurationPath);
		configuration.put("sha256", profile.configurationSha256);
		configuration.put("basedConfigData", Long.valueOf(profile.basedConfigData));
		configuration.put("wantMyWorld", Boolean.valueOf(profile.wantMyWorld));
		configuration.put("activeNpcPatch", profile.npcPatchPath);
		configuration.put("activeItemPatch", profile.itemPatchPath);

		Map<String,Object> report = new LinkedHashMap<String,Object>();
		report.put("schemaVersion", Long.valueOf(1L));
		report.put("manifestType", TYPE);
		report.put("configuration", configuration);
		report.put("families", families);
		report.put("status", "matched");
		report.put("compositionFingerprintSha256", ZERO_HASH);
		byte[] zero = WorldBuilderJsonDocuments.pretty(report)
			.getBytes(StandardCharsets.UTF_8);
		report.put("compositionFingerprintSha256", WorldBuilderHashes.sha256(zero));

		Path destination = projectStage.resolve(PROJECT_RELATIVE_PATH).normalize();
		if (!destination.startsWith(projectStage.toAbsolutePath().normalize())) {
			throw problem(PROJECT_RELATIVE_PATH, "Composition report path is unsafe.",
				"Use the compiled project diagnostic path.");
		}
		Files.createDirectories(destination.getParent());
		if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
			throw problem(PROJECT_RELATIVE_PATH, "Composition report already exists.",
				"Discard the unpublished project stage and retry.");
		}
		Files.write(destination, WorldBuilderJsonDocuments.pretty(report)
			.getBytes(StandardCharsets.UTF_8));
	}

	private static Map<String,Object> composeNpc(Path targetRoot, Path effectiveRoot,
		Profile profile) throws IOException, WorldBuilderContractException {
		Map<Integer,String> effective = new LinkedHashMap<Integer,String>();
		int next = appendSequential(effective, effectiveRoot.resolve(
			"files/server/conf/server/defs/NpcDefs.json"), "npcs", 0,
			"definition.npc.base");
		appendSequential(effective, effectiveRoot.resolve(
			"files/server/conf/server/defs/NpcDefsCustom.json"), "npcs", next,
			"definition.npc.custom");
		List<Object> replacements = new ArrayList<Object>();
		composeOverlay(effective, replacements, targetRoot,
			profile.layout.definitionPath("NpcDefsPatch18.json"), "npcs",
			"definition.npc.patch", profile.npcPatchPath,
			profile.npcPatchPath.isEmpty() ? "inactive-profile" : "superseded-source");
		if (!profile.npcPatchPath.isEmpty()
			&& !profile.npcPatchPath.equals(
				profile.layout.definitionPath("NpcDefsPatch18.json"))) {
			composeOverlay(effective, replacements, targetRoot, profile.npcPatchPath,
				"npcs", "definition.npc.patch", profile.npcPatchPath, "inactive-profile");
		}
		composeOverlay(effective, replacements, targetRoot,
			profile.layout.definitionPath("NpcDefsMyWorld.json"), "npcs",
			"definition.npc.world", profile.wantMyWorld
				? profile.layout.definitionPath("NpcDefsMyWorld.json") : "",
			"disabled-by-configuration");
		return family("npc", effective, replacements, effectiveRoot,
			"definition.npc.base", "definition.npc.custom",
			"definition.npc.patch", "definition.npc.world");
	}

	private static Map<String,Object> composeItems(Path targetRoot, Path effectiveRoot,
		Profile profile) throws IOException, WorldBuilderContractException {
		Map<Integer,String> effective = new LinkedHashMap<Integer,String>();
		List<Object> replacements = new ArrayList<Object>();
		composeRegistry(effective, replacements, effectiveRoot,
			"files/server/conf/server/defs/ItemDefs.json", "item",
			"definition.item.base");
		composeRegistry(effective, replacements, effectiveRoot,
			"files/server/conf/server/defs/ItemDefsCustom.json", "items",
			"definition.item.custom");
		composeOverlay(effective, replacements, targetRoot,
			profile.layout.definitionPath("ItemDefsPatch18.json"), "item",
			"definition.item.patch", profile.itemPatchPath,
			profile.itemPatchPath.isEmpty() ? "inactive-profile" : "superseded-source");
		if (!profile.itemPatchPath.isEmpty()
			&& !profile.itemPatchPath.equals(
				profile.layout.definitionPath("ItemDefsPatch18.json"))) {
			composeOverlay(effective, replacements, targetRoot, profile.itemPatchPath,
				"item", "definition.item.patch", profile.itemPatchPath, "inactive-profile");
		}
		composeOverlay(effective, replacements, targetRoot,
			profile.layout.definitionPath("ItemDefsMyWorld.json"), "items",
			"definition.item.world", profile.wantMyWorld
				? profile.layout.definitionPath("ItemDefsMyWorld.json") : "",
			"disabled-by-configuration");
		return family("ground-item", effective, replacements, effectiveRoot,
			"definition.item.base", "definition.item.custom",
			"definition.item.patch", "definition.item.world");
	}

	private static int appendSequential(Map<Integer,String> effective, Path path,
		String array, int start, String role)
		throws IOException, WorldBuilderContractException {
		List<?> rows = rows(path, array, role);
		for (int index = 0; index < rows.size(); index++) {
			Map<String,Object> row = object(rows.get(index), role);
			int id = start + index;
			if (row.containsKey("id") && id(row, role) != id) {
				throw problem(path.toString(), role + " is not a canonical sequential registry.",
					"Use IDs matching the effective base/custom registry order.");
			}
			if (effective.put(Integer.valueOf(id), name(row)) != null) {
				throw duplicate(role, id);
			}
		}
		return start + rows.size();
	}

	private static void composeRegistry(Map<Integer,String> effective,
		List<Object> replacements, Path root, String relative, String array, String role)
		throws IOException, WorldBuilderContractException {
		Path path = root.resolve(relative);
		Set<Integer> local = new TreeSet<Integer>();
		for (Object raw : rows(path, array, role)) {
			Map<String,Object> row = object(raw, role); int id = id(row, role);
			if (!local.add(Integer.valueOf(id))) throw duplicate(role, id);
			String prior = effective.put(Integer.valueOf(id), name(row));
			if (prior != null) replacements.add(replacement(id, prior, name(row),
				role, relative, "applied", "declared-layer-precedence"));
		}
	}

	private static void composeOverlay(Map<Integer,String> effective,
		List<Object> replacements, Path root, String relative, String array,
		String role, String activePath, String inactiveReason)
		throws IOException, WorldBuilderContractException {
		Path path = root.resolve(relative);
		if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
			if (relative.equals(activePath)) {
				throw problem(relative, "Selected definition overlay is missing.",
					"Restore the exact active definition overlay and rediscover.");
			}
			return;
		}
		Set<Integer> local = new TreeSet<Integer>();
		boolean active = relative.equals(activePath);
		for (Object raw : rows(path, array, role)) {
			Map<String,Object> row = object(raw, role); int id = id(row, role);
			if (!local.add(Integer.valueOf(id)) && active) throw duplicate(role, id);
			String prior = effective.get(Integer.valueOf(id));
			if (prior == null && active) {
				throw problem(relative, role + " references undefined ID " + id + ".",
					"Correct the overlay or provide its complete base definition registry.");
			}
			String replacement = name(row);
			replacements.add(replacement(id, prior == null ? "" : prior,
				replacement, role, relative,
				active ? "applied" : "ignored",
				active ? "declared-layer-precedence" : inactiveReason));
			if (active) effective.put(Integer.valueOf(id), replacement);
		}
	}

	private static Map<String,Object> family(String family,
		Map<Integer,String> effective, List<Object> replacements, Path bundleRoot,
		String... roles) throws IOException {
		Map<String,Object> value = new LinkedHashMap<String,Object>();
		value.put("family", family);
		value.put("effectiveDefinitionCount", Long.valueOf(effective.size()));
		List<Object> definitions = new ArrayList<Object>();
		for (Map.Entry<Integer,String> entry
			: new TreeMap<Integer,String>(effective).entrySet()) {
			Map<String,Object> definition = new LinkedHashMap<String,Object>();
			definition.put("id", Long.valueOf(entry.getKey().intValue()));
			definition.put("name", entry.getValue());
			definitions.add(definition);
		}
		value.put("effectiveDefinitions", definitions);
		value.put("replacements", replacements);
		value.put("effectiveRoleHashes", roleHashes(bundleRoot, roles));
		return value;
	}

	private static List<Object> roleHashes(Path root, String... roles) throws IOException {
		List<Object> result = new ArrayList<Object>();
		for (String role : roles) {
			String file = role.replace("definition.npc.base", "NpcDefs.json")
				.replace("definition.npc.custom", "NpcDefsCustom.json")
				.replace("definition.npc.patch", "NpcDefsPatch18.json")
				.replace("definition.npc.world", "NpcDefsMyWorld.json")
				.replace("definition.item.base", "ItemDefs.json")
				.replace("definition.item.custom", "ItemDefsCustom.json")
				.replace("definition.item.patch", "ItemDefsPatch18.json")
				.replace("definition.item.world", "ItemDefsMyWorld.json");
			Path path = root.resolve("files/server/conf/server/defs/" + file);
			Map<String,Object> record = new LinkedHashMap<String,Object>();
			record.put("role", role);
			record.put("sha256", WorldBuilderHashes.sha256(path));
			result.add(record);
		}
		return result;
	}

	private static Map<String,Object> replacement(int id, String prior,
		String replacement, String role, String path, String disposition, String reason) {
		Map<String,Object> value = new LinkedHashMap<String,Object>();
		value.put("id", Long.valueOf(id));
		value.put("previousName", prior);
		value.put("replacementName", replacement);
		value.put("sourceRole", role);
		value.put("sourceRelativePath", path);
		value.put("disposition", disposition);
		value.put("reason", reason);
		return value;
	}

	private static List<?> rows(Path path, String array, String role)
		throws IOException, WorldBuilderContractException {
		try {
			Map<String,Object> root = WorldBuilderJsonDocuments
				.readTargetDefinitionObject(path);
			Object records = root.get(array);
			if (root.size() == 1 && records == null) {
				records = root.values().iterator().next();
			}
			if (root.size() != 1 || !(records instanceof List)) {
				throw problem(path.toString(), role + " has an invalid definition array.",
					"Use one exact declarative " + array + " array.");
			}
			List<?> rows = (List<?>)records;
			if (rows.size() > 65536) throw problem(path.toString(),
				role + " exceeds 65,536 definitions.",
				"Reduce the bounded definition registry.");
			return rows;
		} catch (WorldBuilderDiscoveryException failure) {
			throw problem(path.toString(), role + " is malformed.",
				"Correct the exact declarative definition JSON.", failure);
		}
	}

	private static Map<String,Object> object(Object raw, String role)
		throws WorldBuilderContractException {
		if (!(raw instanceof Map)) throw problem(role,
			role + " contains a non-object definition.",
			"Use only object records in the definition array.");
		@SuppressWarnings("unchecked") Map<String,Object> value = (Map<String,Object>)raw;
		return value;
	}

	private static int id(Map<String,Object> row, String role)
		throws WorldBuilderContractException {
		Object raw = row.get("id");
		if (!(raw instanceof Long) || ((Long)raw).longValue() < 0L
			|| ((Long)raw).longValue() > 65535L) {
			throw problem(role, role + " contains an invalid ID.",
				"Use integer IDs in 0..65535.");
		}
		return (int)((Long)raw).longValue();
	}

	private static String name(Map<String,Object> row) {
		Object raw = row.get("name");
		return raw instanceof String ? (String)raw : "";
	}

	private static WorldBuilderContractException duplicate(String role, int id) {
		return problem(role, role + " repeats ID " + id + " within one layer.",
			"Keep one record per ID in each definition layer.");
	}

	private static WorldBuilderContractException problem(String path,
		String message, String nextStep) {
		return WorldBuilderReadOnlyTarget.problem(
			WorldBuilderErrorCodes.DEFINITION_MISMATCH, path, message, nextStep);
	}

	private static WorldBuilderContractException problem(String path,
		String message, String nextStep, Throwable cause) {
		return WorldBuilderReadOnlyTarget.problem(
			WorldBuilderErrorCodes.DEFINITION_MISMATCH, path, message, nextStep, cause);
	}

	static final class Profile {
		final WorldBuilderPackedSourceLayout layout;
		final int basedConfigData;
		final boolean wantMyWorld;
		final String configurationPath;
		final String configurationSha256;
		final String npcPatchPath;
		final String itemPatchPath;

		Profile(WorldBuilderPackedSourceLayout layout, int basedConfigData,
			boolean wantMyWorld, String configurationPath,
			String configurationSha256, String npcPatchPath, String itemPatchPath) {
			this.layout = layout;
			this.basedConfigData = basedConfigData;
			this.wantMyWorld = wantMyWorld;
			this.configurationPath = configurationPath;
			this.configurationSha256 = configurationSha256;
			this.npcPatchPath = npcPatchPath;
			this.itemPatchPath = itemPatchPath;
		}

		String sourceFor(String role, String fallback) {
			if ("definition.npc.patch".equals(role)) return npcPatchPath;
			if ("definition.item.patch".equals(role)) return itemPatchPath;
			if ("definition.npc.world".equals(role)
				|| "definition.item.world".equals(role)) {
				return wantMyWorld ? fallback : "";
			}
			return fallback;
		}

		List<String> selectedPatchPaths() {
			List<String> result = new ArrayList<String>();
			if (!npcPatchPath.isEmpty()) result.add(npcPatchPath);
			if (!itemPatchPath.isEmpty()) result.add(itemPatchPath);
			return result;
		}
	}
}
