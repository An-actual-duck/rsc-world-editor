package com.openrsc.worldbuilder;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** Read-only historical source admission. No target descriptor or runtime authority is inferred. */
final class WorldBuilderPreservationLayoutAdapter implements WorldBuilderLayoutAdapter {
	static final String ID = "preservation-source-jag-v1";
	static final String CAPABILITY = "preservation-c0102e-data-conversion-v1";
	static final String REPRESENTATION = "historical-jag";
	@Override public String id() { return ID; }

	static boolean recognizes(WorldBuilderReadOnlyTarget target) throws WorldBuilderContractException {
		if (!target.exists("server/preservation.conf") || !target.exists("server/conf/server/data/maps/maps64.jag")) return false;
		// A single compiled public build anchor only routes the probe. Full source,
		// configuration, map and private-input checks below still decide admission.
		for (Object raw : WorldBuilderPreservationSourceClosure.evidenceRules()) {
			Map<?,?> rule = (Map<?,?>)raw;
			if (!"server/build.xml".equals(rule.get("relativePath"))) continue;
			WorldBuilderReadOnlyTarget.FileState state = target.optionalState("historical-build-probe", "server/build.xml");
			return state.present && state.sha256.equals(rule.get("baselineSha256"));
		}
		return false;
	}

	@Override public ProbeResult probe(WorldBuilderReadOnlyTarget target) throws WorldBuilderContractException {
		boolean selected = recognizes(target);
		List<ProbeResult.Anchor> anchors = new ArrayList<ProbeResult.Anchor>();
		for (String path : Arrays.asList("server/preservation.conf", "server/build.xml", "server/conf/server/data/maps/maps64.jag"))
			anchors.add(new ProbeResult.Anchor("historical-source-route", path, target.exists(path), true));
		return new ProbeResult(ID, selected ? Probe.SUPPORTED : Probe.NO_EVIDENCE, anchors);
	}

	@Override public WorldBuilderAdapterInspection inspect(WorldBuilderReadOnlyTarget target,
		WorldBuilderTargetCapability capability, String requestedRole) throws WorldBuilderContractException {
		if (capability != null || requestedRole != null && !requestedRole.isEmpty() && !"preservation".equals(requestedRole))
			throw blocked("Historical Preservation is selected by compiled source evidence, not a supplied capability or another configuration role.");
		List<Map<String,Object>> classified = WorldBuilderCurrentRuntimeContracts.inspectPreservationSource(target.root);
		for (Map<String,Object> row : classified)
			if (!Arrays.asList("T0", "T2A", "T2B").contains(row.get("tier")))
				throw blocked("Unported, missing, changed or unknown historical input: " + row.get("relativePath"));
		Map<String,Object> typed = WorldBuilderCurrentRuntimeExecutionProfile.preservation().typedConfiguration(target.root);
		if (!"sqlite".equals(((Map<?,?>)typed.get("databaseMigration")).get("engine")))
			throw blocked("This production intake milestone supports Preservation SQLite only.");
		Map<String,Object> state = WorldBuilderPreservationPersistentInputs.inspect(target, true);
		String selectedPath = (String)typed.get("sourceRelativePath");
		WorldBuilderReadOnlyTarget.FileState selected = target.requiredState("configuration.preservation", selectedPath);
		List<WorldBuilderReadOnlyTarget.FileState> files = new ArrayList<WorldBuilderReadOnlyTarget.FileState>();
		for (Object raw : WorldBuilderPreservationSourceIntake.evidenceRules()) {
			Map<?,?> rule = (Map<?,?>)raw;
			String relative = (String)rule.get("relativePath");
			if (WorldBuilderPreservationPersistentInputs.admits(relative)) continue;
			files.add(target.optionalState((String)rule.get("role"), relative));
		}
		List<WorldBuilderAdapterInspection.Check> checks = new ArrayList<WorldBuilderAdapterInspection.Check>();
		checks.add(new WorldBuilderAdapterInspection.Check("historical-source-admission", "passed",
			"Exact compiled c0102e public source/data plus supported effective configuration; no historical executable invocation.",
			"JAG/MEM and public definitions admitted for data-only conversion; runtime activation remains disabled."));
		checks.add(new WorldBuilderAdapterInspection.Check("historical-private-input-binding", "passed",
			"Closed existing SQLite and owner keypair; exact supported side-state/absence. Private bytes never enter projects.",
			"policy=" + WorldBuilderPreservationPersistentInputs.ID + "; sha256=" + state.get("fingerprintSha256")
				+ "; schema=pending-provider-sealed-migration; activation=false"));
		WorldBuilderAdapterInspection.ConfigurationCandidate candidate =
			new WorldBuilderAdapterInspection.ConfigurationCandidate("preservation", selectedPath, selected.sha256);
		return new WorldBuilderAdapterInspection(ID, CAPABILITY, selectedPath, selected.sha256, REPRESENTATION,
			Collections.singletonList(candidate), candidate, files, checks);
	}

	static boolean report(Map<String,Object> report) {
		Object raw = report.get("capability");
		return raw instanceof Map && ID.equals(((Map<?,?>)raw).get("adapterId"))
			&& CAPABILITY.equals(((Map<?,?>)raw).get("capabilityId"))
			&& REPRESENTATION.equals(report.get("representation"));
	}

	private static WorldBuilderContractException blocked(String message) {
		return WorldBuilderReadOnlyTarget.problem(WorldBuilderErrorCodes.CONVERSION_BLOCKED, "historical-source", message,
			"Keep originals unchanged; resolve the exact supported Preservation intake before project creation or upgrade preview.");
	}
}
