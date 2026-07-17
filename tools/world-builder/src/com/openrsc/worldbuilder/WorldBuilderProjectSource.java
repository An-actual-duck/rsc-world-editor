package com.openrsc.worldbuilder;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Strict reader for the immutable project-source-v1 contract. */
final class WorldBuilderProjectSource {
	final String layoutAdapter;
	final String selectedConfig;
	final String selectedConfigSha256;
	final String sourceFingerprint;
	final String contentFingerprint;
	final Map<String, FileState> files;

	private WorldBuilderProjectSource(String layoutAdapter, String selectedConfig,
		String selectedConfigSha256, String sourceFingerprint, String contentFingerprint,
		Map<String, FileState> files) {
		this.layoutAdapter = layoutAdapter;
		this.selectedConfig = selectedConfig;
		this.selectedConfigSha256 = selectedConfigSha256;
		this.sourceFingerprint = sourceFingerprint;
		this.contentFingerprint = contentFingerprint;
		this.files = files;
	}

	static WorldBuilderProjectSource read(Path path)
		throws IOException, WorldBuilderDiscoveryException {
		Map<String, Object> root = WorldBuilderJsonDocuments.readObject(path);
		exactKeys(root, "schemaVersion", "manifestType", "layoutAdapter", "selectedConfig",
			"selectedConfigSha256", "configuration", "terrainSectorCount",
			"contentFingerprintSha256", "sourceFingerprintSha256", "files");
		if (integer(root, "schemaVersion") != 1
			|| !"world-builder-project-source".equals(string(root, "manifestType"))) {
			throw new WorldBuilderDiscoveryException("Project source manifest identity is invalid.");
		}
		String layout = string(root, "layoutAdapter");
		String selectedConfig = string(root, "selectedConfig");
		if (!WorldBuilderDiscovery.LAYOUT_ADAPTER.equals(layout)
			|| !selectedConfig.matches("server/[A-Za-z0-9._/-]+\\.conf")
			|| selectedConfig.contains("..")) {
			throw new WorldBuilderDiscoveryException("Project source layout or configuration is invalid.");
		}
		String selectedConfigSha = hash(root, "selectedConfigSha256");
		String source = hash(root, "sourceFingerprintSha256");
		String content = hash(root, "contentFingerprintSha256");
		if (integer(root, "terrainSectorCount") < 1) {
			throw new WorldBuilderDiscoveryException("Project source terrain count is invalid.");
		}
		validateConfiguration(root.get("configuration"));

		Object listed = root.get("files");
		if (!(listed instanceof List) || ((List<?>)listed).size() != 6) {
			throw new WorldBuilderDiscoveryException(
				"Project source file inventory must contain six records.");
		}
		Map<String, FileState> files = new LinkedHashMap<String, FileState>();
		for (Object item : (List<?>)listed) {
			if (!(item instanceof Map)) {
				throw new WorldBuilderDiscoveryException("Project source file record is invalid.");
			}
			@SuppressWarnings("unchecked") Map<String, Object> record = (Map<String, Object>)item;
			exactKeys(record, "logicalName", "relativePath", "present", "size", "sha256");
			String logicalName = string(record, "logicalName");
			String relativePath = string(record, "relativePath");
			boolean present = bool(record, "present");
			long size = integer(record, "size");
			String sha256 = string(record, "sha256");
			if (logicalName.isEmpty() || relativePath.isEmpty() || relativePath.startsWith("/")
				|| relativePath.contains("\\") || relativePath.contains("..") || size < 0
				|| (present ? !sha256.matches("[0-9a-f]{64}") : size != 0 || !sha256.isEmpty())
				|| files.put(logicalName,
					new FileState(logicalName, relativePath, present, size, sha256)) != null) {
				throw new WorldBuilderDiscoveryException("Project source file record is invalid.");
			}
		}
		for (String required : Arrays.asList("serverTerrain", "clientTerrain", "sceneryLocs",
			"sceneryRemovals", "npcLocs", "npcRemovals")) {
			if (!files.containsKey(required)) {
				throw new WorldBuilderDiscoveryException(
					"Project source file inventory is incomplete.");
			}
		}
		return new WorldBuilderProjectSource(layout, selectedConfig, selectedConfigSha,
			source, content, files);
	}

	FileState required(String logicalName) throws WorldBuilderDiscoveryException {
		FileState state = files.get(logicalName);
		if (state == null) {
			throw new WorldBuilderDiscoveryException(
				"Project source manifest is missing " + logicalName + ".");
		}
		return state;
	}

	private static void validateConfiguration(Object value) throws WorldBuilderDiscoveryException {
		if (!(value instanceof Map)) {
			throw new WorldBuilderDiscoveryException("Project source configuration is invalid.");
		}
		@SuppressWarnings("unchecked") Map<String, Object> configuration = (Map<String, Object>)value;
		exactKeys(configuration, "clientVersion", "basedMapData", "memberWorld",
			"customLandscape", "wantMyWorld");
		if (integer(configuration, "clientVersion") < 1
			|| integer(configuration, "basedMapData") < 0) {
			throw new WorldBuilderDiscoveryException("Project source configuration is invalid.");
		}
		bool(configuration, "memberWorld");
		bool(configuration, "customLandscape");
		bool(configuration, "wantMyWorld");
	}

	private static void exactKeys(Map<String, Object> object, String... keys)
		throws WorldBuilderDiscoveryException {
		java.util.Set<String> expected =
			new java.util.HashSet<String>(Arrays.asList(keys));
		if (object.size() != expected.size() || !object.keySet().equals(expected)) {
			throw new WorldBuilderDiscoveryException(
				"Project source manifest contains missing or unexpected fields.");
		}
	}

	private static String string(Map<String, Object> object, String key)
		throws WorldBuilderDiscoveryException {
		Object value = object.get(key);
		if (!(value instanceof String)) {
			throw new WorldBuilderDiscoveryException(
				"Project source field is not a string: " + key);
		}
		return (String)value;
	}

	private static String hash(Map<String, Object> object, String key)
		throws WorldBuilderDiscoveryException {
		String value = string(object, key);
		if (!value.matches("[0-9a-f]{64}")) {
			throw new WorldBuilderDiscoveryException("Project source hash is invalid: " + key);
		}
		return value;
	}

	private static long integer(Map<String, Object> object, String key)
		throws WorldBuilderDiscoveryException {
		Object value = object.get(key);
		if (!(value instanceof Long)) {
			throw new WorldBuilderDiscoveryException(
				"Project source field is not an integer: " + key);
		}
		return ((Long)value).longValue();
	}

	private static boolean bool(Map<String, Object> object, String key)
		throws WorldBuilderDiscoveryException {
		Object value = object.get(key);
		if (!(value instanceof Boolean)) {
			throw new WorldBuilderDiscoveryException(
				"Project source field is not boolean: " + key);
		}
		return ((Boolean)value).booleanValue();
	}

	static final class FileState {
		final String logicalName;
		final String relativePath;
		final boolean present;
		final long size;
		final String sha256;

		FileState(String logicalName, String relativePath, boolean present, long size,
			String sha256) {
			this.logicalName = logicalName;
			this.relativePath = relativePath;
			this.present = present;
			this.size = size;
			this.sha256 = sha256;
		}
	}
}
