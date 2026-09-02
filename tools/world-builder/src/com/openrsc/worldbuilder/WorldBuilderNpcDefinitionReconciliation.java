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
import java.util.Map;

/** Writes and presents the audit trail for deterministic supplemental NPC ID repair. */
final class WorldBuilderNpcDefinitionReconciliation {
	static final String REPORT_PATH =
		"diagnostics/npc-definition-reconciliation-v1.json";
	private static final int MAX_SPAWN_RECORDS = 65536;

	private WorldBuilderNpcDefinitionReconciliation() {
	}

	static void writeReport(Path projectStage, Path copiedTarget,
		WorldBuilderPackedSourceLayout layout,
		WorldBuilderSupplementalNpcDefinitions.Result result) throws IOException {
		if (result.catalogs.isEmpty()) return;
		Map<String,Object> report = new LinkedHashMap<String,Object>();
		report.put("schemaVersion", Long.valueOf(1L));
		report.put("manifestType", "world-builder-npc-definition-reconciliation");
		report.put("supplementalCatalogs", new ArrayList<String>(result.catalogs));
		report.put("discoveredDefinitionCount",
			Long.valueOf(result.discoveredDefinitionCount));
		report.put("generatedGapDefinitionCount", Long.valueOf(result.gapCount));
		List<Object> conflictValues = new ArrayList<Object>();
		for (WorldBuilderSupplementalNpcDefinitions.Conflict conflict : result.conflicts) {
			Map<String,Object> value = new LinkedHashMap<String,Object>();
			value.put("sourceRelativePath", conflict.definition.relative);
			value.put("sourceRecordIndex", Long.valueOf(conflict.definition.index));
			value.put("name", conflict.definition.name());
			value.put("requestedId", Long.valueOf(conflict.requestedId));
			value.put("assignedId", Long.valueOf(conflict.assignedId));
			Map<String,Object> prior = new LinkedHashMap<String,Object>();
			prior.put("name", conflict.prior.name);
			prior.put("sourceRelativePath", conflict.prior.relative);
			prior.put("sourceRecordIndex", Long.valueOf(conflict.prior.index));
			value.put("existingDefinition", prior);
			value.put("spawnDisposition", "retained-on-existing-definition");
			value.put("spawnLocationsForRequestedId", spawnLocations(
				copiedTarget, layout, conflict.requestedId));
			conflictValues.add(value);
		}
		report.put("conflicts", conflictValues);
		report.put("status", result.conflicts.isEmpty() ? "matched" : "reconciled");
		Path destination = projectStage.resolve(REPORT_PATH).normalize();
		if (!destination.startsWith(projectStage.toAbsolutePath().normalize())) {
			throw new IOException("NPC reconciliation report escaped project stage");
		}
		Files.createDirectories(destination.getParent());
		Files.write(destination, WorldBuilderJsonDocuments.pretty(report)
			.getBytes(StandardCharsets.UTF_8));
	}

	static String projectWarningSummary(Path projectRoot) {
		if (projectRoot == null) return null;
		Path root = projectRoot.toAbsolutePath().normalize();
		Path report = root.resolve(REPORT_PATH).normalize();
		try {
			if (!report.startsWith(root)
				|| !Files.isRegularFile(report, LinkOption.NOFOLLOW_LINKS)
				|| Files.isSymbolicLink(report)) return null;
			Map<String,Object> document =
				WorldBuilderJsonDocuments.readTargetDefinitionObject(report);
			Object raw = document.get("conflicts");
			if (!(raw instanceof List) || ((List<?>)raw).isEmpty()) return null;
			List<String> assignments = new ArrayList<String>();
			for (Object entry : (List<?>)raw) {
				if (!(entry instanceof Map)) continue;
				Map<?,?> value = (Map<?,?>)entry;
				assignments.add(String.valueOf(value.get("name")) + " "
					+ value.get("requestedId") + " → " + value.get("assignedId"));
			}
			return assignments.isEmpty() ? null
				: "NPC ID conflicts were reconciled: " + assignments
					+ ". Existing spawns retain their original IDs. Matching spawn locations "
					+ "are listed for manual inspection in " + report + ".";
		} catch (Exception ignored) {
			return null;
		}
	}

	private static List<Object> spawnLocations(Path targetRoot,
		WorldBuilderPackedSourceLayout layout, int npcId) throws IOException {
		List<Object> result = new ArrayList<Object>();
		Path directory = targetRoot.resolve(layout.locationPath("")).normalize();
		if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)
			|| Files.isSymbolicLink(directory)) return result;
		List<Path> files = new ArrayList<Path>();
		try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory)) {
			for (Path path : entries) {
				String name = path.getFileName().toString();
				if (name.contains("NpcLocs") && name.endsWith(".json")
					&& Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
					&& !Files.isSymbolicLink(path)) files.add(path);
			}
		}
		Collections.sort(files);
		for (Path path : files) {
			try {
				Map<String,Object> document =
					WorldBuilderJsonDocuments.readTargetDefinitionObject(path);
				Object rawRows = document.get("npclocs");
				if (!(rawRows instanceof List)) continue;
				List<?> values = (List<?>)rawRows;
				for (int index = 0; index < values.size(); index++) {
					if (!(values.get(index) instanceof Map)) continue;
					Map<?,?> row = (Map<?,?>)values.get(index);
					if (!(row.get("id") instanceof Long)
						|| ((Long)row.get("id")).intValue() != npcId) continue;
					Map<String,Object> location = new LinkedHashMap<String,Object>();
					location.put("sourceRelativePath",
						targetRoot.toAbsolutePath().normalize().relativize(
							path.toAbsolutePath().normalize()).toString().replace('\\', '/'));
					location.put("sourceRecordIndex", Long.valueOf(index));
					location.put("start", row.get("start"));
					location.put("minimum", row.get("min"));
					location.put("maximum", row.get("max"));
					result.add(location);
					if (result.size() >= MAX_SPAWN_RECORDS) return result;
				}
			} catch (WorldBuilderDiscoveryException malformed) {
				// Active placement files are validated elsewhere; unrelated historical
				// location JSON cannot block this advisory audit.
			}
		}
		return result;
	}
}
