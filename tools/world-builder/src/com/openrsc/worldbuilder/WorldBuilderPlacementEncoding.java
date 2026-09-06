package com.openrsc.worldbuilder;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Closed placement headers and lossless writer policy. Terrain encoding is independent. */
final class WorldBuilderPlacementEncoding {
	static final String BLOCKED_VOID = "blocked-void";
	static final int DEFAULT_VERSION = 4;
	private WorldBuilderPlacementEncoding() { }

	static boolean supports(Object encoding) {
		return "layered-world-placements-v3".equals(encoding)
			|| "layered-world-placements-v4".equals(encoding)
			|| "layered-world-placements-v5".equals(encoding);
	}

	static int version(Object encoding) {
		if (!supports(encoding)) throw new IllegalArgumentException("Unsupported placement encoding: " + encoding);
		return ((String)encoding).charAt(((String)encoding).length() - 1) - '0';
	}

	static String encoding(int version) {
		String result = "layered-world-placements-v" + version;
		version(result);
		return result;
	}

	static int validateHeader(Map<String,Object> payload, Object declaredEncoding) {
		int version = version(payload.get("encoding"));
		Object schema = payload.get("schemaVersion");
		if (!(schema instanceof Long || schema instanceof Integer)
			|| ((Number)schema).longValue() != version
			|| !payload.get("encoding").equals(declaredEncoding)) {
			throw new IllegalArgumentException("Placement schema, payload and declaration disagree.");
		}
		Set<String> keys = new LinkedHashSet<String>(Arrays.asList(
			"boundaries", "encoding", "groundItems", "level", "npcs", "scenery", "schemaVersion", "worldSpace"));
		if (version == 5) keys.add("npcRoamCoverage");
		if (!payload.keySet().equals(keys)
			|| version == 5 && !BLOCKED_VOID.equals(payload.get("npcRoamCoverage"))) {
			throw new IllegalArgumentException("Placement payload has unknown fields or unsupported NPC roaming policy.");
		}
		return version;
	}

	/** Existing packages remain uniform, including empty placement sets. */
	static int packageVersion(List<?> declarations) {
		int selected = 0;
		for (Object raw : declarations) {
			if (!(raw instanceof Map)) throw new IllegalArgumentException("Invalid placement declaration.");
			int current = version(((Map<?,?>)raw).get("encoding"));
			if (selected != 0 && selected != current) throw new IllegalArgumentException("Mixed placement encodings are unsupported.");
			selected = current;
		}
		return selected == 0 ? DEFAULT_VERSION : selected;
	}

	static Map<String,Object> empty(String worldSpace, int level, int version) {
		Map<String,Object> payload = new LinkedHashMap<String,Object>();
		for (String family : Arrays.asList("boundaries", "groundItems", "npcs", "scenery")) {
			payload.put(family, new ArrayList<Object>());
		}
		payload.put("worldSpace", worldSpace);
		payload.put("level", Long.valueOf(level));
		writeHeader(payload, version);
		return payload;
	}

	static void writeHeader(Map<String,Object> payload, int version) {
		if (version != 5 && payload.containsKey("npcRoamCoverage")) {
			throw new IllegalArgumentException("Cannot discard NPC roaming policy.");
		}
		payload.put("encoding", encoding(version));
		payload.put("schemaVersion", Long.valueOf(version));
		if (version == 5) payload.put("npcRoamCoverage", BLOCKED_VOID);
	}

	/** Only explicit accepted composition can promote to v5; normalization never downgrades it. */
	static boolean promote(Map<String,Object> payload, Map<String,Object> declaration, int targetVersion) {
		int current = validateHeader(payload, declaration.get("encoding"));
		version(encoding(targetVersion));
		if (targetVersion < current) throw new IllegalArgumentException("Cannot downgrade placement capability.");
		if (targetVersion == current) return false;
		if (current == 3) {
			Object records = payload.get("npcs");
			if (!(records instanceof List)) throw new IllegalArgumentException("Invalid NPC records.");
			for (Object raw : (List<?>)records) {
				if (!(raw instanceof Map)) throw new IllegalArgumentException("Invalid NPC record.");
				if (((Map<?,?>)raw).containsKey("respawnSeconds")) throw new IllegalArgumentException("Unexpected v3 NPC respawn metadata.");
			}
			for (Object raw : (List<?>)records) {
				@SuppressWarnings("unchecked") Map<String,Object> npc = (Map<String,Object>)raw;
				npc.put("respawnSeconds", Long.valueOf(-1));
			}
		}
		writeHeader(payload, targetVersion);
		declaration.put("encoding", encoding(targetVersion));
		return true;
	}
}
