package com.openrsc.worldbuilder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Validated Phase 0 discovery-report-v2 plus its plain-language summary. */
final class WorldBuilderAdaptiveDiscoveryReport {
	static final String TOOL_VERSION = "2.0.0-alpha.2";

	private final Map<String,Object> document;
	private final String summary;
	final String status;

	private WorldBuilderAdaptiveDiscoveryReport(
		Map<String,Object> document, String summary)
		throws WorldBuilderContractException {
		this.document = document;
		String targetDisplay = document.get("targetRootDisplay") instanceof String
			? (String)document.get("targetRootDisplay") : "";
		sanitizePortableContent(document, targetDisplay);
		this.summary = sanitizeDiagnostic(summary, targetDisplay);
		this.status = (String)document.get("status");
		bindFingerprint(document);
		WorldBuilderAdaptiveContracts.validateParsed(
			WorldBuilderAdaptiveContracts.Kind.DISCOVERY_REPORT, document);
	}

	static WorldBuilderAdaptiveDiscoveryReport compatible(
		String targetDisplay,
		List<String> adapterIds,
		Map<String,Object> descriptor,
		WorldBuilderAdapterInspection inspection,
		List<WorldBuilderAdapterInspection.Check> profileChecks)
		throws WorldBuilderContractException {
		Map<String,Object> root = base(targetDisplay, adapterIds, descriptor);
		root.put("status", "compatible");
		root.put("configurationCandidates", candidates(inspection.candidates));
		root.put("selectedConfiguration", stateReference(inspection.selected));
		root.put("representation", inspection.representation);
		root.put("capability", capabilityReference(inspection));
		root.put("files", files(inspection.files));
		List<WorldBuilderAdapterInspection.Check> allChecks =
			new ArrayList<WorldBuilderAdapterInspection.Check>(profileChecks);
		allChecks.addAll(inspection.checks);
		root.put("checks", checks(allChecks));
		root.put("operations", operations(true));
		root.put("issues", new ArrayList<Object>());
		root.put("discoveryFingerprintSha256", zeroHash());
		String summary = "Compatible " + inspection.representation + " target found with "
			+ inspection.adapterId + " using configuration "
			+ inspection.selected.role + ". Discovery was strictly read-only; "
			+ "project creation remains a separate explicit step.";
		return new WorldBuilderAdaptiveDiscoveryReport(root, summary);
	}

	static WorldBuilderAdaptiveDiscoveryReport standalone(
		String targetDisplay, List<String> adapterIds,
		List<WorldBuilderAdapterInspection.Check> profileChecks)
		throws WorldBuilderContractException {
		Map<String,Object> root = base(targetDisplay, adapterIds, absentStateReference());
		root.put("status", "standalone");
		root.put("configurationCandidates", new ArrayList<Object>());
		root.put("selectedConfiguration", absentRoleStateReference());
		root.put("representation", "none");
		root.put("capability", unresolvedCapability());
		root.put("files", new ArrayList<Object>());
		List<WorldBuilderAdapterInspection.Check> allChecks =
			new ArrayList<WorldBuilderAdapterInspection.Check>(profileChecks);
		allChecks.add(new WorldBuilderAdapterInspection.Check(
			"server-evidence", "not-applicable",
			"A recognized descriptor or exact built-in adapter probe root.",
			"No recognizable server/map evidence exists at the target root."));
		root.put("checks", checks(allChecks));
		root.put("operations", operations(true));
		List<Object> issues = new ArrayList<Object>();
		issues.add(issue(WorldBuilderErrorCodes.NO_SERVER, "warning", "", "target-root",
			"No recognizable server evidence was found.",
			"Place World Builder inside a compatible server root, or continue later with standalone empty mode."));
		root.put("issues", issues);
		root.put("discoveryFingerprintSha256", zeroHash());
		return new WorldBuilderAdaptiveDiscoveryReport(root,
			"No recognizable server or map was found. Standalone empty mode is available; "
				+ "no target server was loaded or changed.");
	}

	static WorldBuilderAdaptiveDiscoveryReport blocked(
		String targetDisplay,
		List<String> adapterIds,
		Map<String,Object> descriptor,
		WorldBuilderTargetCapability capability,
		WorldBuilderAdapterInspection evidence,
		String adapterId,
		String representation,
		WorldBuilderContractException refusal,
		List<WorldBuilderAdapterInspection.Check> profileChecks)
		throws WorldBuilderContractException {
		Map<String,Object> root = base(targetDisplay, adapterIds, descriptor);
		root.put("status", "blocked");
		List<WorldBuilderAdapterInspection.ConfigurationCandidate> candidateValues =
			new ArrayList<WorldBuilderAdapterInspection.ConfigurationCandidate>();
		if (evidence != null) candidateValues.addAll(evidence.candidates);
		if (refusal instanceof WorldBuilderAdaptiveConfiguration.SelectionException) {
			candidateValues.clear();
			candidateValues.addAll(
				((WorldBuilderAdaptiveConfiguration.SelectionException)refusal).candidates);
		}
		root.put("configurationCandidates", candidates(candidateValues));
		root.put("selectedConfiguration", evidence == null
			? absentRoleStateReference() : stateReference(evidence.selected));
		root.put("representation", evidence == null ? representation : evidence.representation);
		root.put("capability", evidence != null
			? capabilityReference(evidence)
			: capability == null || !adapterIds.contains(capability.adapterId)
				? unresolvedCapability() : capability.reference());
		root.put("files", evidence == null
			? new ArrayList<Object>() : files(evidence.files));
		List<WorldBuilderAdapterInspection.Check> allChecks =
			new ArrayList<WorldBuilderAdapterInspection.Check>(profileChecks);
		if (evidence != null) allChecks.addAll(evidence.checks);
		List<Object> checkValues = checks(allChecks);
		root.put("checks", checkValues);
		root.put("operations", operations(false));
		String resolvedAdapter = refusal.adapterId().isEmpty() ? adapterId : refusal.adapterId();
		List<Object> issues = new ArrayList<Object>();
		issues.add(issue(refusal.code(), "blocker", resolvedAdapter,
			refusal.relativePath().isEmpty() ? "target-root" : refusal.relativePath(),
			refusal.getMessage(), refusal.nextStep(), refusal));
		root.put("issues", issues);
		root.put("discoveryFingerprintSha256", zeroHash());
		return new WorldBuilderAdaptiveDiscoveryReport(root,
			"Target discovery is blocked by " + refusal.code() + ": "
				+ clean(refusal.getMessage()) + " Next step: " + clean(refusal.nextStep()));
	}

	String toJson() {
		return WorldBuilderJsonDocuments.pretty(document);
	}

	String summary() {
		return summary;
	}

	String fingerprintSha256() {
		return (String)document.get("discoveryFingerprintSha256");
	}

	/**
	 * Returns the exact fingerprint emitted before descriptor-backed packed
	 * discovery began inventorying its selected ordinary server configuration.
	 * An empty result means this report does not have precisely that one-record
	 * successor shape.
	 */
	String fingerprintWithoutSoleRuntimeConfiguration() {
		if (!"compatible".equals(status)
			|| !"packed".equals(document.get("representation"))) return "";
		Object rawDescriptor = document.get("descriptor");
		if (!(rawDescriptor instanceof Map)
			|| !Boolean.TRUE.equals(((Map<?,?>)rawDescriptor).get("present"))) return "";

		Object rawFiles = document.get("files");
		if (!(rawFiles instanceof List)) return "";
		List<Object> legacyFiles = new ArrayList<Object>();
		int removed = 0;
		for (Object raw : (List<?>)rawFiles) {
			if (!(raw instanceof Map)) return "";
			Map<?,?> file = (Map<?,?>)raw;
			if ("server-runtime-config".equals(file.get("role"))) {
				if (!Boolean.TRUE.equals(file.get("present"))) return "";
				removed++;
				continue;
			}
			legacyFiles.add(raw);
		}
		if (removed != 1) return "";

		Object rawChecks = document.get("checks");
		if (!(rawChecks instanceof List)) return "";
		List<Object> legacyChecks = new ArrayList<Object>();
		int adjusted = 0;
		for (Object raw : (List<?>)rawChecks) {
			if (!(raw instanceof Map)) return "";
			@SuppressWarnings("unchecked") Map<String,Object> original =
				(Map<String,Object>)raw;
			if (!"inventory-completeness".equals(original.get("checkId"))) {
				legacyChecks.add(raw);
				continue;
			}
			Object rawObserved = original.get("observed");
			if (!(rawObserved instanceof String)) return "";
			String observed = (String)rawObserved;
			String suffix = " complete source evidence file(s).";
			if (!observed.endsWith(suffix)) return "";
			String countText = observed.substring(0,
				observed.length() - suffix.length());
			long count;
			try {
				count = Long.parseLong(countText);
			} catch (NumberFormatException invalid) {
				return "";
			}
			if (count != ((List<?>)rawFiles).size() || count < 2L) return "";
			Map<String,Object> check = new LinkedHashMap<String,Object>(original);
			check.put("observed", Long.toString(count - 1L) + suffix);
			legacyChecks.add(check);
			adjusted++;
		}
		if (adjusted != 1) return "";

		Map<String,Object> legacy = new LinkedHashMap<String,Object>(document);
		legacy.put("files", legacyFiles);
		legacy.put("checks", legacyChecks);
		legacy.put("targetRootDisplay", "");
		legacy.put("discoveryFingerprintSha256", zeroHash());
		return WorldBuilderHashes.sha256(WorldBuilderJsonDocuments.canonical(legacy)
			.getBytes(java.nio.charset.StandardCharsets.UTF_8));
	}

	private static Map<String,Object> base(
		String targetDisplay, List<String> adapterIds, Map<String,Object> descriptor) {
		Map<String,Object> root = new LinkedHashMap<String,Object>();
		root.put("schemaVersion", Long.valueOf(2L));
		root.put("manifestType", "world-builder-discovery-report");
		root.put("toolVersion", TOOL_VERSION);
		root.put("targetRootDisplay", boundedDisplay(targetDisplay));
		root.put("adaptersConsidered", new ArrayList<String>(adapterIds));
		root.put("descriptor", descriptor);
		return root;
	}

	private static List<Object> candidates(
		List<WorldBuilderAdapterInspection.ConfigurationCandidate> candidates) {
		List<WorldBuilderAdapterInspection.ConfigurationCandidate> sorted =
			new ArrayList<WorldBuilderAdapterInspection.ConfigurationCandidate>(candidates);
		Collections.sort(sorted);
		List<Object> values = new ArrayList<Object>(sorted.size());
		for (WorldBuilderAdapterInspection.ConfigurationCandidate candidate : sorted) {
			values.add(candidate.toJson());
		}
		return values;
	}

	private static List<Object> files(
		List<WorldBuilderReadOnlyTarget.FileState> files) {
		List<WorldBuilderReadOnlyTarget.FileState> sorted =
			new ArrayList<WorldBuilderReadOnlyTarget.FileState>(files);
		Collections.sort(sorted);
		List<Object> values = new ArrayList<Object>(sorted.size());
		for (WorldBuilderReadOnlyTarget.FileState file : sorted) values.add(file.toJson());
		return values;
	}

	private static List<Object> checks(List<WorldBuilderAdapterInspection.Check> checks) {
		List<WorldBuilderAdapterInspection.Check> sorted =
			new ArrayList<WorldBuilderAdapterInspection.Check>(checks);
		Collections.sort(sorted);
		List<Object> values = new ArrayList<Object>(sorted.size());
		for (WorldBuilderAdapterInspection.Check check : sorted) values.add(check.toJson());
		return values;
	}

	private static Map<String,Object> stateReference(
		WorldBuilderAdapterInspection.ConfigurationCandidate candidate) {
		Map<String,Object> value = new LinkedHashMap<String,Object>();
		value.put("present", Boolean.TRUE);
		value.put("role", candidate.role);
		value.put("relativePath", candidate.relativePath);
		value.put("sha256", candidate.sha256);
		return value;
	}

	static Map<String,Object> descriptorReference(
		WorldBuilderReadOnlyTarget.FileState descriptor) {
		Map<String,Object> value = new LinkedHashMap<String,Object>();
		value.put("present", Boolean.TRUE);
		value.put("relativePath", descriptor.relativePath);
		value.put("sha256", descriptor.sha256);
		return value;
	}

	static Map<String,Object> absentStateReference() {
		Map<String,Object> value = new LinkedHashMap<String,Object>();
		value.put("present", Boolean.FALSE);
		value.put("relativePath", "");
		value.put("sha256", "");
		return value;
	}

	private static Map<String,Object> absentRoleStateReference() {
		Map<String,Object> value = new LinkedHashMap<String,Object>();
		value.put("present", Boolean.FALSE);
		value.put("role", "");
		value.put("relativePath", "");
		value.put("sha256", "");
		return value;
	}

	private static Map<String,Object> capabilityReference(
		WorldBuilderAdapterInspection inspection) {
		Map<String,Object> value = new LinkedHashMap<String,Object>();
		value.put("resolved", Boolean.TRUE);
		value.put("adapterId", inspection.adapterId);
		value.put("capabilityId", inspection.capabilityId);
		value.put("evidenceRelativePath", inspection.capabilityEvidenceRelativePath);
		value.put("evidenceSha256", inspection.capabilityEvidenceSha256);
		return value;
	}

	private static Map<String,Object> unresolvedCapability() {
		Map<String,Object> value = new LinkedHashMap<String,Object>();
		value.put("resolved", Boolean.FALSE);
		value.put("adapterId", "");
		value.put("capabilityId", "");
		value.put("evidenceRelativePath", "");
		value.put("evidenceSha256", "");
		return value;
	}

	private static Map<String,Object> operations(boolean createProject) {
		Map<String,Object> value = new LinkedHashMap<String,Object>();
		value.put("createProject", Boolean.valueOf(createProject));
		value.put("edit", Boolean.FALSE);
		value.put("save", Boolean.FALSE);
		value.put("export", Boolean.FALSE);
		value.put("import", Boolean.FALSE);
		value.put("undo", Boolean.FALSE);
		return value;
	}

	private static Map<String,Object> issue(
		String code,
		String severity,
		String adapterId,
		String path,
		String observed,
		String nextStep) {
		return issue(code, severity, adapterId, path, observed, nextStep, null);
	}

	private static Map<String,Object> issue(
		String code,
		String severity,
		String adapterId,
		String path,
		String observed,
		String nextStep,
		WorldBuilderContractException refusal) {
		Map<String,Object> value = new LinkedHashMap<String,Object>();
		value.put("code", code);
		value.put("severity", severity);
		value.put("operation", "discover-target");
		value.put("projectId", "");
		value.put("adapterId", adapterId == null ? "" : adapterId);
		value.put("relativePath", safeRelative(path));
		value.put("provenance", refusal == null || refusal.provenance().isEmpty()
			? "read-only target discovery" : bounded(refusal.provenance()));
		value.put("recordIndex", Long.valueOf(-1L));
		value.put("recordKey", "");
		value.put("expected", refusal == null || refusal.expected().isEmpty()
			? "Stable, contained evidence accepted by a compiled layout adapter."
			: bounded(refusal.expected()));
		value.put("observed", bounded(observed));
		value.put("mutationOccurred", Boolean.FALSE);
		value.put("nextStep", bounded(nextStep));
		return value;
	}

	private static String safeRelative(String value) {
		try {
			return WorldBuilderPortablePath.require(value, "discover-target");
		} catch (WorldBuilderContractException unsafe) {
			return "target-root";
		}
	}

	private static void sanitizePortableContent(Object value, String targetDisplay) {
		if (value instanceof Map) {
			@SuppressWarnings("unchecked") Map<String,Object> object =
				(Map<String,Object>)value;
			for (Map.Entry<String,Object> entry : object.entrySet()) {
				if ("targetRootDisplay".equals(entry.getKey())) continue;
				Object child = entry.getValue();
				if (child instanceof String) {
					entry.setValue(sanitizeDiagnostic((String)child, targetDisplay));
				} else {
					sanitizePortableContent(child, targetDisplay);
				}
			}
		} else if (value instanceof List) {
			@SuppressWarnings("unchecked") List<Object> array = (List<Object>)value;
			for (int index = 0; index < array.size(); index++) {
				Object child = array.get(index);
				if (child instanceof String) {
					array.set(index, sanitizeDiagnostic((String)child, targetDisplay));
				} else {
					sanitizePortableContent(child, targetDisplay);
				}
			}
		}
	}

	static String sanitizeDiagnostic(String value, String targetDisplay) {
		String portable = bounded(value).replace('\\', '/');
		if (targetDisplay == null || targetDisplay.isEmpty()) return portable;
		String root = targetDisplay.replace('\\', '/');
		while (root.length() > 1 && root.endsWith("/")) {
			root = root.substring(0, root.length() - 1);
		}
		if ("/".equals(root)) {
			return portable.replaceAll(
				"(^|[\\s\\(\\[\\{:'\"])/(?=[A-Za-z0-9._-])", "$1");
		}
		portable = portable.replace(root + "/", "");
		return portable.replace(root, "target-root");
	}

	private static void bindFingerprint(Map<String,Object> root) {
		String display = (String)root.get("targetRootDisplay");
		root.put("targetRootDisplay", "");
		root.put("discoveryFingerprintSha256", zeroHash());
		String canonical = WorldBuilderJsonDocuments.canonical(root);
		root.put("targetRootDisplay", display);
		root.put("discoveryFingerprintSha256", WorldBuilderHashes.sha256(
			canonical.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
	}

	private static String boundedDisplay(String value) {
		return bounded(value == null ? "" : value);
	}

	private static String bounded(String value) {
		if (value == null) return "unknown";
		StringBuilder result = new StringBuilder(Math.min(value.length(), 4096));
		for (int index = 0; index < value.length() && result.length() < 4096; index++) {
			char character = value.charAt(index);
			if (Character.isHighSurrogate(character)) {
				if (index + 1 < value.length()
					&& Character.isLowSurrogate(value.charAt(index + 1))
					&& result.length() + 2 <= 4096) {
					result.append(character).append(value.charAt(++index));
				} else {
					result.append('\ufffd');
				}
			} else if (Character.isLowSurrogate(character)) {
				result.append('\ufffd');
			} else {
				result.append(character < 0x20 || character == 0x7f ? ' ' : character);
			}
		}
		return result.toString();
	}

	private static String clean(String value) {
		String result = bounded(value);
		return result.length() > 512 ? result.substring(0, 512) : result;
	}

	private static String zeroHash() {
		return "0000000000000000000000000000000000000000000000000000000000000000";
	}
}
