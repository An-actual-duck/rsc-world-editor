package com.openrsc.worldbuilder;

import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipFile;
import java.util.zip.ZipEntry;

/** Deterministic launch inputs, separate from unchanged provider runtime roots. */
final class WorldBuilderCurrentRuntimeLaunchInputs {
	private static final String DEFAULTS = "installed/server/current-base.conf";
	private static final String PACKAGE = "migration/output/map/conversion/package";
	private static final int MAX_CONFIG_BYTES = 262144;
	private WorldBuilderCurrentRuntimeLaunchInputs() { }

	static Map<String,String> paths() {
		Map<String,String> paths = new LinkedHashMap<String,String>();
		paths.put("runtime-configuration", "migration/output/launch/current-base.conf");
		paths.put("installed-server-profile", "migration/output/launch/installed-server.json");
		paths.put("installed-client-profile", "migration/output/launch/installed-client.json");
		return Collections.unmodifiableMap(paths);
	}

	static List<Object> plan(WorldBuilderProviderCatalog.Composition composition,
		Map<String,Object> typed, Map<String,Object> layout,
		WorldBuilderPackedConverter.Inspection map)
		throws IOException, WorldBuilderContractException {
		if (map == null || !Boolean.TRUE.equals(layout.get("ready"))
			|| !eligible(typed)) return Collections.emptyList();
		WorldBuilderProviderCatalog.Artifact content = null;
		for (WorldBuilderProviderCatalog.Artifact artifact : composition.artifacts)
			if ("server-content".equals(artifact.inventory.get("role"))) {
				if (content != null) throw failure("Ambiguous server content defaults.");
				content = artifact;
			}
		if (content == null || !WorldBuilderHashes.sha256(content.source)
			.equals(content.inventory.get("sha256"))) throw failure("Provider configuration defaults changed.");
		String defaults;
		try (ZipFile archive = new ZipFile(content.source.toFile())) {
			ZipEntry entry = archive.getEntry("current-base.conf");
			if (entry == null || entry.isDirectory() || entry.getSize() > MAX_CONFIG_BYTES)
				throw failure("Provider configuration defaults are absent or unbounded.");
			try (InputStream input = archive.getInputStream(entry)) {
				ByteArrayOutputStream bytes = new ByteArrayOutputStream();
				byte[] buffer = new byte[8192]; int count;
				while ((count = input.read(buffer)) != -1) {
					if (bytes.size() + count > MAX_CONFIG_BYTES) throw failure("Provider defaults exceed their bound.");
					bytes.write(buffer, 0, count);
				}
				defaults = new String(bytes.toByteArray(), StandardCharsets.UTF_8);
			}
		}
		return records(documents(defaults, typed, map.manifest,
			runtimePackageFingerprint(map.outputInventory), map.manifestSha256));
	}

	static void write(Path stage, Map<String,Object> typed, Map<String,Object> execution,
		Map<String,Object> mapMigration)
		throws IOException, WorldBuilderContractException {
		List<Object> planned = new ArrayList<Object>();
		for (Object raw : array(execution.get("stagedOutputs")))
			if (paths().containsKey(object(raw).get("kind"))) planned.add(raw);
		if (planned.isEmpty()) return;
		if (!eligible(typed)) throw failure("Typed configuration is not ready for runtime rendering.");
		WorldBuilderCurrentRuntimeLayout.verify(stage, object(execution.get("runtimeLayout")));
		Path defaultsPath = WorldBuilderPortablePath.resolveContained(stage, DEFAULTS, "runtime-launch-inputs");
		if (Files.size(defaultsPath) > MAX_CONFIG_BYTES) throw failure("Provider defaults exceed their bound.");
		Path map = WorldBuilderPortablePath.resolveContained(stage, PACKAGE, "runtime-launch-inputs");
		new WorldBuilderCurrentRuntimeUpgradeTransaction().verifyReviewedPreservationMap(stage, mapMigration);
		Map<String,Object> manifest;
		try { manifest = WorldBuilderJsonDocuments.readObject(map.resolve("manifest.json")); }
		catch (WorldBuilderDiscoveryException invalid) { throw failure("Converted map manifest is malformed."); }
		Map<String,byte[]> documents = documents(new String(Files.readAllBytes(defaultsPath),
			StandardCharsets.UTF_8), typed, manifest, runtimePackageFingerprint(array(mapMigration.get("outputInventory"))),
			WorldBuilderHashes.sha256(map.resolve("manifest.json")));
		if (!records(documents).equals(planned)) throw failure("Runtime launch inputs changed after preview.");
		// Validate the entire set before creating any launch output; never overwrite a prior set.
		Path destination = WorldBuilderPortablePath.resolveContained(stage,
			"migration/output/launch", "runtime-launch-inputs");
		Files.createDirectory(destination, PosixFilePermissions.asFileAttribute(
			PosixFilePermissions.fromString("rwx------")));
		for (Map.Entry<String,byte[]> entry : documents.entrySet()) {
			Path path = stage.resolve(paths().get(entry.getKey()));
			Files.createFile(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
			Files.write(path, entry.getValue(), StandardOpenOption.WRITE);
		}
		WorldBuilderAdaptiveDurability.forceTree(destination);
	}

	/** Native runtime addressing uses newline-terminated records, unlike conversion proof identities. */
	static String runtimePackageFingerprint(List<Object> conversionInventory) throws WorldBuilderContractException {
		Map<String,Map<String,Object>> files = new java.util.TreeMap<String,Map<String,Object>>();
		for (Object raw : conversionInventory) {
			Map<String,Object> row = object(raw);
			String path = WorldBuilderBoundedInventory.string(row.get("relativePath"), "runtime-launch-inputs", "relativePath");
			if (!path.startsWith(PACKAGE + "/")) continue;
			String relative = path.substring(PACKAGE.length() + 1);
			WorldBuilderPortablePath.require(relative, "runtime-launch-inputs");
			if (files.put(relative, row) != null) throw failure("Duplicate runtime package inventory path.");
		}
		if (!files.containsKey("manifest.json")) throw failure("Runtime package inventory lacks its manifest.");
		StringBuilder canonical = new StringBuilder();
		for (Map.Entry<String,Map<String,Object>> entry : files.entrySet()) {
			Map<String,Object> row = entry.getValue();
			long size = WorldBuilderBoundedInventory.integer(row.get("size"), "runtime-launch-inputs", "size");
			String hash = WorldBuilderBoundedInventory.string(row.get("sha256"), "runtime-launch-inputs", "sha256");
			if (size < 1 || !WorldBuilderBoundedInventory.isHash(hash)) throw failure("Malformed runtime package inventory record.");
			canonical.append(entry.getKey()).append('\0').append(size).append('\0').append(hash).append('\n');
		}
		return WorldBuilderHashes.sha256(canonical.toString().getBytes(StandardCharsets.UTF_8));
	}

	private static boolean eligible(Map<String,Object> typed) {
		return array(typed.get("configurationBlockers")).isEmpty()
			&& "sqlite".equals(object(typed.get("databaseMigration")).get("engine"))
			&& representable((String)typed.get("serverName"))
			&& representable((String)typed.get("bindAddress"));
	}

	private static boolean representable(String value) {
		return value != null && !value.isEmpty() && value.equals(value.trim())
			&& !"null".equalsIgnoreCase(value) && !value.matches("(?s).*[:#\\p{Cntrl}].*");
	}

	private static Map<String,byte[]> documents(String defaults, Map<String,Object> typed,
		Map<String,Object> manifest, String fingerprint, String manifestHash) throws WorldBuilderContractException {
		Map<String,byte[]> result = new LinkedHashMap<String,byte[]>();
		result.put("runtime-configuration", render(defaults, typed).getBytes(StandardCharsets.UTF_8));
		for (String role : Arrays.asList("server", "client")) {
			Map<String,Object> profile = new LinkedHashMap<String,Object>();
			profile.put("schemaVersion", Long.valueOf(1));
			profile.put("manifestType", "world-builder-installed-" + role + "-profile");
			profile.put("active", Boolean.TRUE);
			profile.put("packageId", manifest.get("packageId"));
			profile.put("packageVersion", manifest.get("packageVersion"));
			profile.put("packageFingerprintSha256", fingerprint);
			profile.put("manifestSha256", manifestHash);
			profile.put("packageRelativePath", "world-builder/packages/" + fingerprint + "/package");
			result.put("installed-" + role + "-profile",
				WorldBuilderJsonDocuments.pretty(profile).getBytes(StandardCharsets.UTF_8));
		}
		return result;
	}

	static String render(String defaults, Map<String,Object> typed) throws WorldBuilderContractException {
		if (!eligible(typed)) throw failure("Configuration cannot be represented by the reviewed runtime parser.");
		Map<String,String> replacements = new LinkedHashMap<String,String>();
		replacements.put("db_name", "current_base");
		replacements.put("server_name", (String)typed.get("serverName"));
		replacements.put("server_name_welcome", (String)typed.get("serverName"));
		replacements.put("server_bind_address", (String)typed.get("bindAddress"));
		replacements.put("server_port", number(typed, "gamePort", 65535));
		replacements.put("ws_server_port", number(typed, "websocketPort", 65535));
		if (replacements.get("server_port").equals(replacements.get("ws_server_port")))
			throw failure("Runtime ports must differ.");
		Set<String> required = new HashSet<String>(replacements.keySet());
		replacements.put("combat_exp_rate", number(typed, "combatExperienceRate", 100));
		replacements.put("skilling_exp_rate", number(typed, "skillingExperienceRate", 100));
		Set<String> found = new HashSet<String>();
		StringBuilder rendered = new StringBuilder();
		for (String line : defaults.split("\\r?\\n")) {
			String content = line.split("#", 2)[0];
			int colon = content.indexOf(':');
			String key = colon < 0 ? "" : content.substring(0, colon).trim();
			if (replacements.containsKey(key)) {
				if (!found.add(key)) throw failure("Provider defaults repeat a translated runtime key.");
				line = key + ": " + replacements.get(key);
			}
			rendered.append(line).append('\n');
		}
		if (!found.containsAll(required)) throw failure("Provider defaults omit a required runtime key.");
		for (String key : replacements.keySet()) if (!found.contains(key))
			rendered.append(key).append(": ").append(replacements.get(key)).append('\n');
		return rendered.toString();
	}

	private static String number(Map<String,Object> typed, String key, long maximum)
		throws WorldBuilderContractException {
		long value = WorldBuilderBoundedInventory.integer(typed.get(key), "runtime-launch-inputs", key);
		if (value < 1 || value > maximum) throw failure("Translated runtime number is out of range.");
		return Long.toString(value);
	}

	private static List<Object> records(Map<String,byte[]> documents) {
		List<Object> result = new ArrayList<Object>();
		for (Map.Entry<String,byte[]> entry : documents.entrySet()) {
			Map<String,Object> record = new LinkedHashMap<String,Object>();
			record.put("relativePath", paths().get(entry.getKey())); record.put("kind", entry.getKey());
			record.put("sourceRelativePath", ""); record.put("sourceSha256", "");
			record.put("size", Long.valueOf(entry.getValue().length));
			record.put("sha256", WorldBuilderHashes.sha256(entry.getValue())); record.put("mode", "0600");
			result.add(record);
		}
		return result;
	}

	private static WorldBuilderContractException failure(String message) {
		return new WorldBuilderContractException(WorldBuilderErrorCodes.CONVERSION_BLOCKED,
			"runtime-launch-inputs", "migration/output/launch", false, message,
			"Keep the target offline and obtain a fresh reviewed upgrade preview.");
	}
	@SuppressWarnings("unchecked") private static Map<String,Object> object(Object value) { return (Map<String,Object>)value; }
	@SuppressWarnings("unchecked") private static List<Object> array(Object value) { return (List<Object>)value; }
}
