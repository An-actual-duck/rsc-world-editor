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
		WorldBuilderReadOnlyTarget target = WorldBuilderReadOnlyTarget.open(targetRoot);
		String custom = layout.definitionPath("NpcDefsCustom.json");
		List<Object> merged = new ArrayList<Object>(rows(
			target.requiredFile(custom), custom));
		for (String relative : inspect(target, layout)) {
			merged.addAll(rows(target.requiredFile(relative), relative));
			if (merged.size() > MAX_DEFINITIONS) {
				throw problem(relative,
					"Combined custom NPC definitions exceed 65,536 records.",
					"Reduce or consolidate the supplemental definition catalogs.");
			}
		}
		return merged;
	}

	static byte[] mergedCustomJson(Path targetRoot,
		WorldBuilderPackedSourceLayout layout)
		throws IOException, WorldBuilderContractException {
		Map<String,Object> document = new LinkedHashMap<String,Object>();
		document.put("npcs", mergedCustomRows(targetRoot, layout));
		return WorldBuilderJsonDocuments.pretty(document).getBytes(StandardCharsets.UTF_8);
	}

	static boolean hasSupplemental(Path targetRoot,
		WorldBuilderPackedSourceLayout layout) throws WorldBuilderContractException {
		return !inspect(WorldBuilderReadOnlyTarget.open(targetRoot), layout).isEmpty();
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
}
