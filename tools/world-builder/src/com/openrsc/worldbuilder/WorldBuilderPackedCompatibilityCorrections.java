package com.openrsc.worldbuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Exact, source-preserving corrections for documented legacy data defects. */
final class WorldBuilderPackedCompatibilityCorrections {
	static final String REPORT_PATH = "diagnostics/packed-compatibility-corrections.json";
	static final String NPC_67_ROAM_PROFILE =
		"openrsc-npc-67-max-y-transposition-v1";

	private WorldBuilderPackedCompatibilityCorrections() {
	}

	static Result normalizeBaseNpcs(String sourceRelativePath, List<?> source)
		throws WorldBuilderContractException {
		List<Object> normalized = new ArrayList<Object>(source.size());
		List<Correction> corrections = new ArrayList<Correction>();
		for (int index = 0; index < source.size(); index++) {
			Object raw = source.get(index);
			if (knownNpc67RoamDefect(raw)) {
				if (!corrections.isEmpty()) {
					throw blocked(sourceRelativePath,
						"Known NPC 67 roam-bound defect occurs more than once; automatic "
							+ "correction is not uniquely attributable.");
				}
				normalized.add(correctNpc67(raw));
				corrections.add(new Correction(sourceRelativePath, index));
			} else {
				normalized.add(raw);
			}
		}
		return new Result(normalized, corrections);
	}

	static void writeReport(Path projectStage, List<Correction> corrections)
		throws IOException {
		if (corrections.isEmpty()) return;
		Path root = projectStage.toAbsolutePath().normalize();
		Path path = root.resolve(REPORT_PATH).normalize();
		if (!path.startsWith(root)) throw new IOException(
			"Packed compatibility report escaped project stage");
		Files.createDirectories(path.getParent());
		Map<String,Object> report = new LinkedHashMap<String,Object>();
		report.put("schemaVersion", Long.valueOf(1L));
		report.put("manifestType", "world-builder-packed-compatibility-corrections");
		List<Object> values = new ArrayList<Object>(corrections.size());
		for (Correction correction : corrections) values.add(correction.toJson());
		report.put("corrections", values);
		Files.write(path, WorldBuilderJsonDocuments.pretty(report)
			.getBytes(StandardCharsets.UTF_8));
	}

	private static boolean knownNpc67RoamDefect(Object raw) {
		if (!(raw instanceof Map)) return false;
		Map<?,?> record = (Map<?,?>)raw;
		return record.size() == 4
			&& integer(record.get("id"), 67)
			&& point(record.get("start"), 647, 3534)
			&& point(record.get("min"), 632, 3519)
			&& point(record.get("max"), 662, 6549);
	}

	@SuppressWarnings("unchecked")
	private static Map<String,Object> correctNpc67(Object raw) {
		Map<String,Object> record = new LinkedHashMap<String,Object>(
			(Map<String,Object>)raw);
		Map<String,Object> maximum = new LinkedHashMap<String,Object>(
			(Map<String,Object>)record.get("max"));
		maximum.put("Y", Long.valueOf(3549L));
		record.put("max", maximum);
		return record;
	}

	private static boolean point(Object raw, int x, int y) {
		if (!(raw instanceof Map)) return false;
		Map<?,?> value = (Map<?,?>)raw;
		return value.size() == 2
			&& integer(value.get("X"), x)
			&& integer(value.get("Y"), y);
	}

	private static boolean integer(Object raw, int expected) {
		if (!(raw instanceof Number)) return false;
		Number value = (Number)raw;
		return value.longValue() == expected
			&& value.doubleValue() == (double)expected;
	}

	private static WorldBuilderContractException blocked(String path, String message) {
		return new WorldBuilderContractException(WorldBuilderErrorCodes.CONVERSION_BLOCKED,
			"prepare-packed-fallback", path, false, message,
			"Correct the exact source ambiguity or remove the duplicate malformed record.");
	}

	static final class Result {
		final List<Object> records;
		final List<Correction> corrections;

		Result(List<Object> records, List<Correction> corrections) {
			this.records = Collections.unmodifiableList(new ArrayList<Object>(records));
			this.corrections = Collections.unmodifiableList(
				new ArrayList<Correction>(corrections));
		}
	}

	static final class Correction {
		final String sourceRelativePath;
		final int recordIndex;

		Correction(String sourceRelativePath, int recordIndex) {
			this.sourceRelativePath = sourceRelativePath;
			this.recordIndex = recordIndex;
		}

		Map<String,Object> toJson() {
			Map<String,Object> value = new LinkedHashMap<String,Object>();
			value.put("profileId", NPC_67_ROAM_PROFILE);
			value.put("sourceRelativePath", sourceRelativePath);
			value.put("recordIndex", Long.valueOf(recordIndex));
			value.put("npcId", Long.valueOf(67L));
			value.put("field", "max.Y");
			value.put("originalValue", Long.valueOf(6549L));
			value.put("correctedValue", Long.valueOf(3549L));
			value.put("reason",
				"Exact known legacy transposition: the original bound is outside every "
					+ "supported packed plane; 3549 restores the symmetric 30x30 roam box.");
			return value;
		}
	}
}
