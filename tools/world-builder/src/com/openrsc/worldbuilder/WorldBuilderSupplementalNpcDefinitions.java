package com.openrsc.worldbuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Folds verified active NPC append order into the portable custom registry without changing IDs. */
final class WorldBuilderSupplementalNpcDefinitions {
	private static final int MAX_DEFINITIONS = 65536;

	private WorldBuilderSupplementalNpcDefinitions() {
	}

	static List<String> inspect(WorldBuilderReadOnlyTarget target,
		WorldBuilderPackedSourceLayout layout) throws WorldBuilderContractException {
        List<String> result = WorldBuilderNpcContentSources.inspect(target, layout).supplemental;
        for (String relative : result) rows(target.requiredFile(relative), relative);
        return result;
    }

	static List<Object> mergedCustomRows(Path targetRoot,
		WorldBuilderPackedSourceLayout layout)
		throws IOException, WorldBuilderContractException {
		return normalize(targetRoot, layout).customRows;
	}

	static Result normalize(Path targetRoot, WorldBuilderPackedSourceLayout layout)
		throws IOException, WorldBuilderContractException {
		WorldBuilderReadOnlyTarget target = WorldBuilderReadOnlyTarget.open(targetRoot);
		String base = layout.definitionPath("NpcDefs.json");
		String custom = layout.definitionPath("NpcDefsCustom.json");
		List<Object> baseRows = rows(target.requiredFile(base), base);
		List<Object> ordinaryCustom = rows(target.requiredFile(custom), custom);
		if (baseRows.isEmpty()) throw problem(base,
			"Base NPC definitions contain no initial record.",
			"Restore one complete base NPC registry and retry discovery.");
		if (baseRows.size() + ordinaryCustom.size() > MAX_DEFINITIONS) {
			throw tooMany(custom);
		}

		List<String> catalogs = inspect(target, layout);
		List<Definition> definitions = new ArrayList<Definition>();
		for (String relative : catalogs) {
			List<Object> sourceRows = rows(target.requiredFile(relative), relative);
			for (int index = 0; index < sourceRows.size(); index++) {
				Map<String,Object> row = object(sourceRows.get(index), relative, index);
				definitions.add(new Definition(relative, index, row,
					declaredId(row, relative, index)));
			}
		}
		if (baseRows.size() + ordinaryCustom.size() + definitions.size()
			> MAX_DEFINITIONS) throw tooMany(custom);

		int firstSupplemental = baseRows.size() + ordinaryCustom.size();
		List<Object> merged = new ArrayList<Object>(ordinaryCustom.size());
		for (int index = 0; index < ordinaryCustom.size(); index++) {
			int id = baseRows.size() + index;
			Map<String,Object> row = object(ordinaryCustom.get(index), custom, index);
			Map<String,Object> canonical = withId(row, id);
			merged.add(canonical);
		}

        TreeMap<Integer,Definition> assigned = new TreeMap<Integer,Definition>();
        List<Conflict> conflicts = new ArrayList<Conflict>();
        int next = firstSupplemental;
        for (Definition definition : definitions) {
            if (definition.requestedId != null && definition.requestedId.intValue() != next) {
                throw problem(definition.relative,
                    "NPC declared ID " + definition.requestedId + " disagrees with its active append slot " + next + ".",
                    "Correct the maintained effective definition export; World Builder will not remap content identities.");
            }
            assigned.put(Integer.valueOf(next++), definition);
        }

        for (Map.Entry<Integer,Definition> entry : assigned.entrySet()) {
            merged.add(withId(entry.getValue().row, entry.getKey()));
        }
		if (baseRows.size() + merged.size() > MAX_DEFINITIONS) throw tooMany(custom);
		Map<String,Integer> sourceIds = new TreeMap<String,Integer>();
		for (int index = 0; index < baseRows.size(); index++) sourceIds.put(base + "#" + index, index);
		for (int index = 0; index < ordinaryCustom.size(); index++) sourceIds.put(custom + "#" + index, baseRows.size() + index);
		for (Map.Entry<Integer,Definition> entry : assigned.entrySet()) {
			Definition source = entry.getValue();
			sourceIds.put(source.relative + "#" + source.index, entry.getKey());
		}
		return new Result(merged, catalogs, definitions.size(), 0, conflicts, sourceIds);
	}

	static byte[] customJson(List<Object> rows) {
		Map<String,Object> document = new LinkedHashMap<String,Object>();
		document.put("npcs", rows);
		return WorldBuilderJsonDocuments.pretty(document).getBytes(StandardCharsets.UTF_8);
	}

	private static Map<String,Object> object(Object raw, String path, int index)
		throws WorldBuilderContractException {
		if (!(raw instanceof Map)) throw problem(path,
			"Supplemental NPC definition record " + index + " is not an object.",
			"Use declarative NPC definition objects only.");
		@SuppressWarnings("unchecked") Map<String,Object> value = (Map<String,Object>)raw;
		return value;
	}

	private static Integer declaredId(Map<String,Object> row, String path, int index)
		throws WorldBuilderContractException {
		Object raw = row.get("id");
		if (raw == null) return null;
		if (!(raw instanceof Long) || ((Long)raw).longValue() < 0L
			|| ((Long)raw).longValue() > 65535L) throw problem(path,
			"Supplemental NPC definition record " + index + " has an invalid ID.",
			"Use an integer ID matching the maintained append slot, or omit the redundant ID field.");
		return Integer.valueOf(((Long)raw).intValue());
	}

	private static Map<String,Object> withId(Map<String,Object> row, int id) {
		Map<String,Object> result = new LinkedHashMap<String,Object>(row);
		result.put("id", Long.valueOf(id));
		return result;
	}

	private static String name(Object raw) {
		if (!(raw instanceof Map)) return "";
		Object value = ((Map<?,?>)raw).get("name");
		return value instanceof String ? (String)value : "";
	}

	private static WorldBuilderContractException tooMany(String path) {
		return problem(path, "Combined NPC definitions exceed 65,536 records.",
			"Reduce or consolidate the NPC definition catalogs.");
	}

	private static List<Object> rows(Path path, String label)
		throws WorldBuilderContractException {
		try {
			Map<String,Object> document =
				WorldBuilderJsonDocuments.readTargetDefinitionObject(path);
			Object value = document.get("npcs");
			if (document.size() == 1 && value == null) {
				value = document.values().iterator().next();
			}
			if (document.size() != 1 || !(value instanceof List)) {
				throw problem(label,
					"Supplemental NPC definition catalog has an invalid definition array.",
					"Use one bounded JSON object containing one NPC definition array.");
			}
			List<?> values = (List<?>)value;
			if (values.size() > MAX_DEFINITIONS) {
				throw problem(label,
					"Supplemental NPC definition catalog exceeds 65,536 records.",
					"Reduce or split the definition catalog.");
			}
			return new ArrayList<Object>(values);
		} catch (WorldBuilderContractException refusal) {
			throw refusal;
		} catch (IOException failure) {
			throw problem(label,
				"Supplemental NPC definition catalog changed or is malformed.",
				"Correct the bounded JSON catalog and retry discovery.", failure);
		} catch (WorldBuilderDiscoveryException malformed) {
			throw problem(label,
				"Supplemental NPC definition catalog changed or is malformed.",
				"Correct the bounded JSON catalog and retry discovery.", malformed);
		}
	}

	private static WorldBuilderContractException problem(String path,
		String message, String nextStep) {
		return problem(path, message, nextStep, null);
	}

	private static WorldBuilderContractException problem(String path,
		String message, String nextStep, Throwable cause) {
		return new WorldBuilderContractException(WorldBuilderErrorCodes.DEFINITION_MISMATCH,
			"project-content-bundle", "", "", path, "supplemental NPC definitions",
			"Bounded declarative NPC registries in their verified maintained runtime append order.",
			message, false, message, nextStep, cause);
	}

	static final class Result {
		final List<Object> customRows;
		final List<String> catalogs;
		final int discoveredDefinitionCount;
		final int gapCount;
		final List<Conflict> conflicts;
		final Map<String,Integer> sourceIds;
		Result(List<Object> customRows, List<String> catalogs,
			int discoveredDefinitionCount, int gapCount, List<Conflict> conflicts, Map<String,Integer> sourceIds) {
			this.customRows = Collections.unmodifiableList(new ArrayList<Object>(customRows));
			this.catalogs = Collections.unmodifiableList(new ArrayList<String>(catalogs));
			this.discoveredDefinitionCount = discoveredDefinitionCount;
			this.gapCount = gapCount;
			this.conflicts = Collections.unmodifiableList(new ArrayList<Conflict>(conflicts));
			this.sourceIds = Collections.unmodifiableMap(new TreeMap<String,Integer>(sourceIds));
		}
		boolean changed() { return !catalogs.isEmpty(); }
	}

	static final class Definition {
		final String relative;
		final int index;
		final Map<String,Object> row;
		final Integer requestedId;
		Definition(String relative, int index, Map<String,Object> row, Integer requestedId) {
			this.relative = relative;
			this.index = index;
			this.row = row;
			this.requestedId = requestedId;
		}
		String name() { return WorldBuilderSupplementalNpcDefinitions.name(row); }
	}

	static final class Occupied {
		final String name;
		final String relative;
		final int index;
		Occupied(String name, String relative, int index) {
			this.name = name;
			this.relative = relative;
			this.index = index;
		}
	}

	static final class Conflict {
		final Definition definition;
		final int requestedId;
		final Occupied prior;
		int assignedId = -1;
		Conflict(Definition definition, int requestedId, Occupied prior) {
			this.definition = definition;
			this.requestedId = requestedId;
			this.prior = prior;
		}
	}
}
