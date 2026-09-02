package com.openrsc.worldbuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/** Safely folds target-owned append-only NPC registries into the portable custom registry. */
final class WorldBuilderSupplementalNpcDefinitions {
	private static final int MAX_CATALOGS = 64;
	private static final int MAX_DEFINITIONS = 65536;

	private WorldBuilderSupplementalNpcDefinitions() {
	}

	static List<String> inspect(WorldBuilderReadOnlyTarget target,
		WorldBuilderPackedSourceLayout layout) throws WorldBuilderContractException {
		String definitionRoot = layout.definitionPath("");
		Path directory = target.requiredDirectory(trimTrailingSlash(definitionRoot));
		TreeMap<String,String> discovered = new TreeMap<String,String>();
		try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory)) {
			for (Path candidate : entries) {
				String name = candidate.getFileName().toString();
				if (!isSupplementalName(name)) continue;
				String relative = target.relative(candidate);
				target.requiredFile(relative);
				String portable = name.toLowerCase(Locale.ROOT);
				if (discovered.put(portable, relative) != null) {
					throw problem(relative,
						"Supplemental NPC definition catalogs have a portable-name collision.",
						"Rename the supplemental catalogs so their names differ on every platform.");
				}
				if (discovered.size() > MAX_CATALOGS) {
					throw problem(relative,
						"Target contains more than 64 supplemental NPC definition catalogs.",
						"Consolidate the append-only NPC catalogs and retry discovery.");
				}
			}
		} catch (WorldBuilderContractException refusal) {
			throw refusal;
		} catch (IOException failure) {
			throw problem(definitionRoot,
				"Supplemental NPC definition catalogs could not be inspected.",
				"Stop target changes, verify read access, and retry discovery.", failure);
		}
		List<String> result = new ArrayList<String>(discovered.values());
		for (String relative : result) rows(target.requiredFile(relative), relative);
		return Collections.unmodifiableList(result);
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
			"Base NPC definitions contain no record for safe gap placeholders.",
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
		Map<Integer,Occupied> occupied = new TreeMap<Integer,Occupied>();
		for (int index = 0; index < baseRows.size(); index++) {
			occupied.put(Integer.valueOf(index), new Occupied(
				name(baseRows.get(index)), base, index));
		}
		List<Object> merged = new ArrayList<Object>(ordinaryCustom.size());
		for (int index = 0; index < ordinaryCustom.size(); index++) {
			int id = baseRows.size() + index;
			Map<String,Object> row = object(ordinaryCustom.get(index), custom, index);
			Map<String,Object> canonical = withId(row, id);
			merged.add(canonical);
			occupied.put(Integer.valueOf(id), new Occupied(name(canonical), custom, index));
		}

		TreeMap<Integer,Definition> assigned = new TreeMap<Integer,Definition>();
		List<Conflict> conflicts = new ArrayList<Conflict>();
		Map<Definition,Conflict> needsAssignment =
			new LinkedHashMap<Definition,Conflict>();
		int maximumRequested = firstSupplemental - 1;
		for (Definition definition : definitions) {
			if (definition.requestedId == null) {
				needsAssignment.put(definition, null);
				continue;
			}
			int requested = definition.requestedId.intValue();
			maximumRequested = Math.max(maximumRequested, requested);
			Occupied prior = occupied.get(Integer.valueOf(requested));
			Definition priorSupplemental = assigned.get(Integer.valueOf(requested));
			if (prior != null || priorSupplemental != null) {
				Conflict conflict = new Conflict(definition, requested,
					prior != null ? prior : new Occupied(priorSupplemental.name(),
						priorSupplemental.relative, priorSupplemental.index));
				needsAssignment.put(definition, conflict);
				conflicts.add(conflict);
			} else {
				assigned.put(Integer.valueOf(requested), definition);
			}
		}

		int next = Math.max(firstSupplemental, maximumRequested + 1);
		for (Map.Entry<Definition,Conflict> pending : needsAssignment.entrySet()) {
			Definition definition = pending.getKey();
			while (next <= 65535 && (occupied.containsKey(Integer.valueOf(next))
				|| assigned.containsKey(Integer.valueOf(next)))) next++;
			if (next > 65535) throw problem(definition.relative,
				"No NPC ID remains available for a discovered definition.",
				"Retire an unused NPC definition before rediscovering this server.");
			assigned.put(Integer.valueOf(next), definition);
			if (pending.getValue() != null) pending.getValue().assignedId = next;
			next++;
		}

		int gapCount = 0;
		if (!assigned.isEmpty()) {
			int maximum = assigned.lastKey().intValue();
			Map<String,Object> template = object(baseRows.get(0), base, 0);
			for (int id = firstSupplemental; id <= maximum; id++) {
				Definition definition = assigned.get(Integer.valueOf(id));
				if (definition == null) {
					merged.add(placeholder(template, id));
					gapCount++;
				} else {
					merged.add(withId(definition.row, id));
				}
			}
		}
		if (baseRows.size() + merged.size() > MAX_DEFINITIONS) throw tooMany(custom);
		return new Result(merged, catalogs, definitions.size(), gapCount, conflicts);
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
			"Use an integer ID in 0..65535 or omit it for automatic assignment.");
		return Integer.valueOf(((Long)raw).intValue());
	}

	private static Map<String,Object> withId(Map<String,Object> row, int id) {
		Map<String,Object> result = new LinkedHashMap<String,Object>(row);
		result.put("id", Long.valueOf(id));
		return result;
	}

	private static Map<String,Object> placeholder(Map<String,Object> template, int id) {
		Map<String,Object> result = withId(template, id);
		result.put("name", "Unused NPC definition slot " + id);
		if (result.containsKey("description")) {
			result.put("description", "Reserved by World Builder for sparse NPC IDs");
		}
		if (result.containsKey("command")) result.put("command", "");
		if (result.containsKey("command2")) result.put("command2", "");
		if (result.containsKey("attackable")) result.put("attackable", Long.valueOf(0L));
		if (result.containsKey("aggressive")) result.put("aggressive", Long.valueOf(0L));
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

	private static boolean isSupplementalName(String name) {
		return name.endsWith("NpcDefs.json")
			&& !"NpcDefs.json".equals(name)
			&& !"NpcDefsCustom.json".equals(name);
	}

	private static String trimTrailingSlash(String value) {
		return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
	}

	private static WorldBuilderContractException problem(String path,
		String message, String nextStep) {
		return problem(path, message, nextStep, null);
	}

	private static WorldBuilderContractException problem(String path,
		String message, String nextStep, Throwable cause) {
		return new WorldBuilderContractException(WorldBuilderErrorCodes.DEFINITION_MISMATCH,
			"project-content-bundle", "", "", path, "supplemental NPC definitions",
			"Bounded regular append-only *NpcDefs.json catalogs in portable order.",
			message, false, message, nextStep, cause);
	}

	static final class Result {
		final List<Object> customRows;
		final List<String> catalogs;
		final int discoveredDefinitionCount;
		final int gapCount;
		final List<Conflict> conflicts;
		Result(List<Object> customRows, List<String> catalogs,
			int discoveredDefinitionCount, int gapCount, List<Conflict> conflicts) {
			this.customRows = Collections.unmodifiableList(new ArrayList<Object>(customRows));
			this.catalogs = Collections.unmodifiableList(new ArrayList<String>(catalogs));
			this.discoveredDefinitionCount = discoveredDefinitionCount;
			this.gapCount = gapCount;
			this.conflicts = Collections.unmodifiableList(new ArrayList<Conflict>(conflicts));
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
