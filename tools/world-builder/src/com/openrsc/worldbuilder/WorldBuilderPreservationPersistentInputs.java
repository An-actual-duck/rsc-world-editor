package com.openrsc.worldbuilder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Closed historical private-input admission; metadata only, never a project copy policy. */
final class WorldBuilderPreservationPersistentInputs {

	static final String ID = "preservation-c0102e-private-inputs-v1";
	private static final String DATABASE = WorldBuilderPreservationStagedMigrator.SQLITE_SOURCE;
	private static final Map<String,String> PATHS;
	static {
		Map<String,String> paths = new TreeMap<String,String>();
		paths.put(DATABASE, "database");
		for (String name : Arrays.asList("server.pem", "client.pem", "badwords.txt", "goodwords.txt", "alertwords.txt", "ipbans.txt", "ipbans.temp"))
			paths.put("server/" + name, "server-side-state");
		// c0102e's normal desktop client reads this cwd-relative preference file.
		// Later UID/remembered-credential formats are not historical intake authority.
		paths.put("Client_Base/clientSettings.conf", "client-side-state");
		PATHS = Collections.unmodifiableMap(paths);
	}
	private WorldBuilderPreservationPersistentInputs() { }

	static boolean admits(String relative) { return PATHS.containsKey(relative); }

	static List<Object> evidenceRules() {
		List<Object> result = new ArrayList<Object>();
		for (Map.Entry<String,String> item : PATHS.entrySet()) {
			Map<String,Object> rule = new LinkedHashMap<String,Object>();
			rule.put("role", "preserved-" + item.getValue()); rule.put("relativePath", item.getKey());
			rule.put("required", Boolean.FALSE); rule.put("baselineSize", Long.valueOf(0));
			rule.put("baselineSha256", ""); rule.put("evidenceKind", "database".equals(item.getValue()) ? "database" : "portable-data");
			rule.put("recognizedDeltas", new ArrayList<Object>()); result.add(rule);
		}
		return result;
	}

	static Map<String,Object> inspect(WorldBuilderReadOnlyTarget target, boolean requireComplete)
		throws WorldBuilderContractException {
		List<Object> records = new ArrayList<Object>();
		boolean complete = true;
		for (Map.Entry<String,String> item : PATHS.entrySet()) {
			String relative = item.getKey();
			boolean required = DATABASE.equals(relative) || relative.endsWith(".pem");
			WorldBuilderReadOnlyTarget.FileState state = target.optionalState("preserved-" + item.getValue(), relative);
			if (required && !state.present) complete = false;
			if (state.present) validate(target, relative, state.size);
			Map<String,Object> row = new LinkedHashMap<String,Object>();
			row.put("role", state.role); row.put("relativePath", relative); row.put("present", Boolean.valueOf(state.present));
			row.put("size", Long.valueOf(state.size)); row.put("sha256", state.sha256);
			row.put("requiredForNormalInstance", Boolean.valueOf(required));
			row.put("copyIntoProject", Boolean.FALSE); records.add(row);
		}
		boolean privateKey = target.exists("server/server.pem"), publicKey = target.exists("server/client.pem");
		if (privateKey != publicKey) throw blocked("Existing server keys are incomplete; replacement keys are never generated at intake.");
		if (privateKey) {
			try { WorldBuilderCurrentRuntimeInstance.validateKeyPair(target.requiredFile("server/server.pem"), target.requiredFile("server/client.pem")); }
			catch (IOException | WorldBuilderContractException unsafe) { throw blocked("Existing server keypair is invalid or does not agree."); }
		}
		if (requireComplete && !complete) throw blocked("Normal-instance preview requires the existing closed SQLite database and complete owner keypair.");
		Map<String,Object> result = new LinkedHashMap<String,Object>();
		result.put("schemaVersion", Long.valueOf(1)); result.put("manifestType", "world-builder-preservation-private-inputs");
		result.put("policyId", ID); result.put("inputs", records);
		result.put("requiredInputsPresent", Boolean.valueOf(complete));
		result.put("schemaValidation", "pending-provider-sealed-migration");
		result.put("activationApproved", Boolean.FALSE);
		WorldBuilderAdaptiveExporter.bindFingerprint(result, "fingerprintSha256");
		return result;
	}

	static void validate(WorldBuilderReadOnlyTarget target, String relative, long size)
		throws WorldBuilderContractException {
		if (!admits(relative)) throw blocked("Unreviewed persistent input path.");
		Path path = target.requiredFile(relative); // canonical parents, no symlink/hardlink
		long maximum = DATABASE.equals(relative) ? 4294967296L : relative.endsWith(".pem") ? 65536L : 1048576L;
		if (size > maximum) throw blocked("Persistent input exceeds its bounded size.");
		try {
			int mode = ((Number)Files.getAttribute(path, "unix:mode", LinkOption.NOFOLLOW_LINKS)).intValue() & 07777;
			boolean privateBytes = DATABASE.equals(relative) || relative.endsWith(".pem") || relative.endsWith("clientSettings.conf");
			if (mode != 0600 && (privateBytes || mode != 0644)) throw blocked("Persistent input permissions differ from the private intake policy.");
		} catch (IOException | UnsupportedOperationException failure) { throw blocked("Persistent input permissions cannot be proven."); }
		if (DATABASE.equals(relative)) WorldBuilderPreservationStagedMigrator.requireClosedSqliteSnapshot(target.root, path);
	}

	private static WorldBuilderContractException blocked(String message) {
		return new WorldBuilderContractException(WorldBuilderErrorCodes.CONVERSION_BLOCKED,
			"preservation-private-inputs", "persistent-state", false, message,
			"Retain owner state outside project artifacts; resolve the exact input policy before retrying.");
	}
}
