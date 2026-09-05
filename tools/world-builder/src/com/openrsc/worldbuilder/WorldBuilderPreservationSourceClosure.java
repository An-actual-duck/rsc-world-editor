package com.openrsc.worldbuilder;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
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

/**
 * Sealed metadata-only closure for the reviewed historical Preservation build
 * routes. No historical source or vendor bytes are packaged or executed.
 */
final class WorldBuilderPreservationSourceClosure {
	private static final String RESOURCE =
		"/com/openrsc/worldbuilder/preservation-c0102e-source-build-dependencies.json";
	private static final String SOURCE_COMMIT =
		"c0102e60774ab9c9076aabae49f6f97fb6fc4b00";
	private static final String SOURCE_TREE =
		"6db5536d795abf34f303bb03b20c43b8cfb9e3fe";
	private static final String ALL_RECORDS_HASH =
		"bfdb1dc141844074869cc27cf93791728f4c4cf1cc83f210adfa3b62f14972b0";
	private static final String SOURCE_BUILD_HASH =
		"539cf77449ddfcf04b78debb9f45baee5cad5ebc379b69833a14db82f4100d2a";
	private static final String VENDOR_HASH =
		"7914b63d326c6fb06d03848b7198848a0ad33fc6844feed22edbbb10e2dfb376";
	private static volatile Closure closure;

	private WorldBuilderPreservationSourceClosure() { }

	static List<Object> evidenceRules() throws WorldBuilderContractException {
		List<Object> result = new ArrayList<Object>();
		for (Record record : closure().records) {
			Map<String,Object> rule = new LinkedHashMap<String,Object>();
			rule.put("role", role(record.path));
			rule.put("relativePath", record.path);
			rule.put("required", Boolean.TRUE);
			rule.put("baselineSize", Long.valueOf(record.size));
			rule.put("baselineSha256", record.sha256);
			rule.put("evidenceKind", kind(record.path));
			rule.put("recognizedDeltas", new ArrayList<Object>());
			result.add(rule);
		}
		return result;
	}

	static boolean owns(String relativePath) throws WorldBuilderContractException {
		return closure().paths.contains(relativePath);
	}

	static String changedTier(String relativePath) {
		if (relativePath.startsWith("server/plugins/")) return "T3";
		if (relativePath.startsWith("server/src/")
			|| relativePath.startsWith("server/lib/")
			|| relativePath.startsWith("Client_Base/src/")
			|| relativePath.startsWith("PC_Client/src/")
			|| relativePath.startsWith("PC_Client/lib/")
			|| "server/build.xml".equals(relativePath)
			|| "server/build.gradle".equals(relativePath)
			|| "server/manifest.mf".equals(relativePath)
			|| "Client_Base/build.xml".equals(relativePath)
			|| "Client_Base/manifest.mf".equals(relativePath)) return "T4";
		return "";
	}

	static boolean modeMatches(Path path) {
		try {
			Object raw = Files.getAttribute(path, "unix:mode", LinkOption.NOFOLLOW_LINKS);
			return raw instanceof Number && (((Number)raw).intValue() & 0777) == 0644;
		} catch (IOException unsupported) {
			return false;
		} catch (UnsupportedOperationException unsupported) {
			return false;
		} catch (IllegalArgumentException unsupported) {
			return false;
		}
	}

	static Map<String,Object> summary() throws WorldBuilderContractException {
		Closure value = closure();
		Map<String,Object> result = new LinkedHashMap<String,Object>();
		result.put("sourceCommit", SOURCE_COMMIT);
		result.put("sourceTree", SOURCE_TREE);
		result.put("recordCount", Long.valueOf(value.records.size()));
		result.put("sourceBuildRecordCount", Long.valueOf(value.sourceBuildCount));
		result.put("vendorDependencyRecordCount", Long.valueOf(value.vendorCount));
		result.put("canonicalRecordsSha256", ALL_RECORDS_HASH);
		result.put("sourceBuildCanonicalRecordsSha256", SOURCE_BUILD_HASH);
		result.put("vendorDependencyCanonicalRecordsSha256", VENDOR_HASH);
		return result;
	}

	private static Closure closure() throws WorldBuilderContractException {
		Closure cached = closure;
		if (cached != null) return cached;
		synchronized (WorldBuilderPreservationSourceClosure.class) {
			if (closure == null) closure = load();
			return closure;
		}
	}

	private static Closure load() throws WorldBuilderContractException {
		byte[] bytes;
		try (InputStream input = WorldBuilderPreservationSourceClosure.class
			.getResourceAsStream(RESOURCE)) {
			if (input == null) throw refusal("The sealed historical source closure resource is missing.");
			ByteArrayOutputStream output = new ByteArrayOutputStream(320 * 1024);
			byte[] buffer = new byte[8192];
			int count;
			while ((count = input.read(buffer)) >= 0) {
				if (count == 0) continue;
				if ((long)output.size() + count > WorldBuilderContractLimits.MAX_JSON_BYTES)
					throw refusal("The sealed historical source closure exceeds its bound.");
				output.write(buffer, 0, count);
			}
			bytes = output.toByteArray();
		} catch (IOException failure) {
			throw refusal("The sealed historical source closure could not be read.", failure);
		}
		Map<String,Object> root;
		try {
			root = WorldBuilderJsonDocuments.readObject(bytes, RESOURCE);
		} catch (WorldBuilderDiscoveryException malformed) {
			throw refusal("The sealed historical source closure is malformed.", malformed);
		}
		exact(root, "schemaVersion", "manifestType", "sourceCommit", "sourceTree",
			"recordCount", "sourceBuildRecordCount", "vendorDependencyRecordCount",
			"sourceBuildCanonicalRecordsSha256", "canonicalHashRecipe", "scope", "records");
		if (!Long.valueOf(1L).equals(root.get("schemaVersion"))
			|| !"reviewed-preservation-source-build-dependency-closure".equals(
				root.get("manifestType"))
			|| !SOURCE_COMMIT.equals(root.get("sourceCommit"))
			|| !SOURCE_TREE.equals(root.get("sourceTree"))
			|| !SOURCE_BUILD_HASH.equals(root.get("sourceBuildCanonicalRecordsSha256")))
			throw refusal("The sealed historical source closure identity changed.");
		List<Object> rawRecords = array(root.get("records"));
		if (!Long.valueOf(1268L).equals(root.get("recordCount"))
			|| !Long.valueOf(1246L).equals(root.get("sourceBuildRecordCount"))
			|| !Long.valueOf(22L).equals(root.get("vendorDependencyRecordCount"))
			|| rawRecords.size() != 1268
			|| !ALL_RECORDS_HASH.equals(canonicalHash(rawRecords)))
			throw refusal("The sealed historical source closure record inventory changed.");
		List<Object> source = new ArrayList<Object>();
		List<Object> vendor = new ArrayList<Object>();
		List<Record> records = new ArrayList<Record>();
		Set<String> paths = new HashSet<String>();
		String previous = null;
		for (Object raw : rawRecords) {
			Map<String,Object> record = object(raw);
			exact(record, "path", "mode", "size", "sha256");
			String path = value(record, "path");
			try { path = WorldBuilderPortablePath.require(path, "preservation-source-closure"); }
			catch (WorldBuilderContractException unsafe) {
				throw refusal("The sealed historical source closure contains an unsafe path.", unsafe);
			}
			if (previous != null && previous.compareTo(path) >= 0 || !paths.add(path)
				|| changedTier(path).isEmpty() || !"100644".equals(value(record, "mode")))
				throw refusal("The sealed historical source closure path or mode policy changed.");
			Object rawSize = record.get("size");
			if (!(rawSize instanceof Long) || ((Long)rawSize).longValue() < 0L
				|| ((Long)rawSize).longValue() > WorldBuilderContractLimits.MAX_INVENTORY_FILE_BYTES)
				throw refusal("The sealed historical source closure contains an invalid size.");
			String sha256 = value(record, "sha256");
			if (!sha256.matches("[0-9a-f]{64}"))
				throw refusal("The sealed historical source closure contains an invalid hash.");
			Record item = new Record(path, ((Long)rawSize).longValue(), sha256);
			records.add(item);
			if (isVendor(path)) vendor.add(record); else source.add(record);
			previous = path;
		}
		if (source.size() != 1246 || vendor.size() != 22
			|| !SOURCE_BUILD_HASH.equals(canonicalHash(source))
			|| !VENDOR_HASH.equals(canonicalHash(vendor)))
			throw refusal("The sealed source/build or vendor dependency closure hash changed.");
		return new Closure(records, paths, source.size(), vendor.size());
	}

	private static boolean isVendor(String path) {
		return path.startsWith("server/lib/") || path.startsWith("PC_Client/lib/");
	}

	private static String role(String path) {
		if (path.startsWith("server/plugins/")) return "historical-plugin-source";
		if (path.startsWith("server/src/")) return "historical-core-source";
		if (path.startsWith("Client_Base/src/") || path.startsWith("PC_Client/src/"))
			return "historical-client-source";
		if (isVendor(path)) return "historical-vendor-dependency";
		return "historical-build-route";
	}

	private static String kind(String path) {
		if (path.startsWith("server/plugins/")) return "plugin-source";
		if (path.startsWith("server/src/")) return "core-source";
		if (path.startsWith("Client_Base/src/") || path.startsWith("PC_Client/src/"))
			return "client-source";
		if (isVendor(path)) return "dependency";
		return "build";
	}

	private static String canonicalHash(Object value) {
		return WorldBuilderHashes.sha256(WorldBuilderJsonDocuments.canonical(value)
			.getBytes(java.nio.charset.StandardCharsets.UTF_8));
	}

	private static void exact(Map<String,Object> value, String... keys)
		throws WorldBuilderContractException {
		Set<String> expected = new HashSet<String>(Arrays.asList(keys));
		if (!value.keySet().equals(expected))
			throw refusal("The sealed historical source closure shape changed.");
	}

	@SuppressWarnings("unchecked") private static Map<String,Object> object(Object value)
		throws WorldBuilderContractException {
		if (!(value instanceof Map)) throw refusal("The sealed historical source closure record is not an object.");
		return (Map<String,Object>)value;
	}

	@SuppressWarnings("unchecked") private static List<Object> array(Object value)
		throws WorldBuilderContractException {
		if (!(value instanceof List)) throw refusal("The sealed historical source closure records are not an array.");
		return (List<Object>)value;
	}

	private static String value(Map<String,Object> root, String key)
		throws WorldBuilderContractException {
		Object value = root.get(key);
		if (!(value instanceof String)) throw refusal("The sealed historical source closure field is not text.");
		return (String)value;
	}

	private static WorldBuilderContractException refusal(String message) {
		return refusal(message, null);
	}

	private static WorldBuilderContractException refusal(String message, Throwable cause) {
		return new WorldBuilderContractException(WorldBuilderErrorCodes.SOURCE_CORRUPT,
			"preservation-source-closure", "built-in-adapter", false, message,
			"Restore the exact World Builder installation; target files cannot replace built-in migration evidence.", cause);
	}

	private static final class Record {
		final String path;
		final long size;
		final String sha256;
		Record(String path, long size, String sha256) {
			this.path = path; this.size = size; this.sha256 = sha256;
		}
	}

	private static final class Closure {
		final List<Record> records;
		final Set<String> paths;
		final int sourceBuildCount;
		final int vendorCount;
		Closure(List<Record> records, Set<String> paths, int sourceBuildCount,
			int vendorCount) {
			this.records = Collections.unmodifiableList(records);
			this.paths = Collections.unmodifiableSet(paths);
			this.sourceBuildCount = sourceBuildCount;
			this.vendorCount = vendorCount;
		}
	}
}
