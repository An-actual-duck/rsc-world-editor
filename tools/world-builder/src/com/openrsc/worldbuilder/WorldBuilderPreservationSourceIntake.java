package com.openrsc.worldbuilder;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Compiled, metadata-only historical source-layout authority; never executes target code. */
final class WorldBuilderPreservationSourceIntake {
	private static final String RESOURCE = "/com/openrsc/worldbuilder/preservation-c0102e-source-intake.json";
	private static final String RESOURCE_HASH = "635974a038dd20e9c54cac932ccf14400235db151e110d52a40be9562304cd24";
	static final String HISTORICAL_ID = "preservation-c0102e-source-layout-v1";
	private static volatile Map<String,Object> metadata;

	private WorldBuilderPreservationSourceIntake() { }

	static List<Object> evidenceRules() throws WorldBuilderContractException {
		List<Object> rules = new ArrayList<Object>();
		for (Object raw : WorldBuilderPreservationSourceClosure.evidenceRules()) {
			Map<String,Object> rule = object(raw);
			// Historical vendor payloads are optional inputs, never current dependencies.
			if (!"dependency".equals(rule.get("evidenceKind"))) rules.add(rule);
		}
		for (Object raw : array(document().get("records"))) {
			Map<String,Object> record = object(raw);
			Map<String,Object> rule = new LinkedHashMap<String,Object>();
			rule.put("role", "historical-" + record.get("kind"));
			rule.put("relativePath", record.get("path"));
			rule.put("required", Boolean.TRUE);
			rule.put("baselineSize", record.get("size"));
			rule.put("baselineSha256", record.get("sha256"));
			rule.put("evidenceKind", record.get("kind"));
			rule.put("recognizedDeltas", new ArrayList<Object>());
			rules.add(rule);
		}
		for (String path : Arrays.asList("server/local.conf", "server/connections.conf")) {
			Map<String,Object> rule = new LinkedHashMap<String,Object>();
			rule.put("role", "historical-effective-configuration");
			rule.put("relativePath", path); rule.put("required", Boolean.FALSE);
			rule.put("baselineSize", Long.valueOf(0)); rule.put("baselineSha256", "");
			rule.put("evidenceKind", "configuration");
			rule.put("recognizedDeltas", new ArrayList<Object>()); rules.add(rule);
		}
		Collections.sort(rules, new Comparator<Object>() {
			@Override public int compare(Object first, Object second) {
				return ((String)object(first).get("relativePath"))
					.compareTo((String)object(second).get("relativePath"));
			}
		});
		return rules;
	}

	static boolean matchesAdapter(Map<String,Object> adapter) throws WorldBuilderContractException {
		if (!HISTORICAL_ID.equals(adapter.get("historicalRuntimeId"))) return false;
		return WorldBuilderJsonDocuments.canonical(adapter).equals(WorldBuilderJsonDocuments.canonical(
			WorldBuilderCurrentRuntimeExecutionProfile.preservation().adapter.root));
	}

	static boolean baselineValue(String key, String value) throws WorldBuilderContractException {
		return WorldBuilderHashes.sha256(value.getBytes(StandardCharsets.UTF_8)).equals(
			object(document().get("configurationValueHashes")).get(key));
	}

	static java.util.Set<String> baselineKeys() throws WorldBuilderContractException {
		return object(document().get("configurationValueHashes")).keySet();
	}

	static boolean knownVendor(WorldBuilderReadOnlyTarget.FileState state)
		throws WorldBuilderContractException {
		for (Object raw : WorldBuilderPreservationSourceClosure.evidenceRules()) {
			Map<String,Object> rule = object(raw);
			if ("dependency".equals(rule.get("evidenceKind"))
				&& state.relativePath.equals(rule.get("relativePath"))
				&& state.sha256.equals(rule.get("baselineSha256"))
				&& Long.valueOf(state.size).equals(rule.get("baselineSize"))) return true;
		}
		return false;
	}

	static boolean modeMatches(java.nio.file.Path file, String relative)
		throws WorldBuilderContractException {
		int expected = 0644;
		for (Object raw : array(document().get("records"))) {
			Map<String,Object> record = object(raw);
			if (relative.equals(record.get("path")))
				expected = Integer.parseInt((String)record.get("mode"), 8) & 0777;
		}
		try {
			int actual = ((Number)java.nio.file.Files.getAttribute(file, "unix:mode",
				java.nio.file.LinkOption.NOFOLLOW_LINKS)).intValue() & 07777;
			return actual == expected || actual == 0600 && relative.endsWith(".conf");
		} catch (IOException | UnsupportedOperationException | IllegalArgumentException failure) {
			return false;
		}
	}

	private static Map<String,Object> document() throws WorldBuilderContractException {
		Map<String,Object> cached = metadata;
		if (cached != null) return cached;
		try (InputStream input = WorldBuilderPreservationSourceIntake.class.getResourceAsStream(RESOURCE)) {
			if (input == null) throw new IOException("missing resource");
			ByteArrayOutputStream output = new ByteArrayOutputStream();
			byte[] buffer = new byte[4096]; int count;
			while ((count = input.read(buffer)) >= 0) {
				output.write(buffer, 0, count);
				if (output.size() > 131072) throw new IOException("oversized resource");
			}
			byte[] bytes = output.toByteArray();
			if (!RESOURCE_HASH.equals(WorldBuilderHashes.sha256(bytes)))
				throw new IOException("modified resource");
			Map<String,Object> loaded = WorldBuilderJsonDocuments.readObject(bytes, RESOURCE);
			metadata = loaded;
			return loaded;
		} catch (Exception failure) {
			throw new WorldBuilderContractException(WorldBuilderErrorCodes.CONTRACT_IDENTITY_INVALID,
				"preservation-source-intake", "metadata", false,
				"The sealed historical source intake metadata is missing or changed.",
				"Restore the exact packaged Editor build; target metadata cannot replace its authority.", failure);
		}
	}

	@SuppressWarnings("unchecked") private static Map<String,Object> object(Object value) {
		return (Map<String,Object>)value;
	}
	@SuppressWarnings("unchecked") private static List<Object> array(Object value) {
		return (List<Object>)value;
	}
}
