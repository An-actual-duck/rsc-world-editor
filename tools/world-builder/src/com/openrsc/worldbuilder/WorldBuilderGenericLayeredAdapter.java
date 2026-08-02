package com.openrsc.worldbuilder;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/** Descriptor-backed adapter for arbitrary compatible signed-layered packages. */
final class WorldBuilderGenericLayeredAdapter implements WorldBuilderLayoutAdapter {
	static final String ID = "generic-layered-v1";
	private static final String FORMAT_ID = "signed-layered-v1";
	private static final String PACKAGE_SCHEMA_ID = "layered-world-package-v1";
	private static final String MUTATION_PROFILE_ID = "generic-layered-install-v1";
	private static final String CONFIG_ROOT = "server/world-builder-configs";

	@Override
	public String id() {
		return ID;
	}

	@Override
	public Probe probe(WorldBuilderReadOnlyTarget target)
		throws WorldBuilderContractException {
		return target.exists(CONFIG_ROOT) ? Probe.RECOGNIZABLE : Probe.NO_EVIDENCE;
	}

	@Override
	public WorldBuilderAdapterInspection inspect(
		WorldBuilderReadOnlyTarget target,
		WorldBuilderTargetCapability capability,
		String requestedConfigurationRole) throws WorldBuilderContractException {
		if (capability == null || !ID.equals(capability.adapterId)) {
			throw problem(WorldBuilderErrorCodes.UNSUPPORTED_ADAPTER,
				WorldBuilderTargetCapability.RELATIVE_PATH,
				"Generic layered discovery requires a descriptor selecting " + ID + ".",
				"Install a truthful target capability descriptor for this compiled adapter.");
		}
		requireCapability(capability);
		WorldBuilderAdaptiveConfiguration.Selection selection =
			WorldBuilderAdaptiveConfiguration.select(target, capability,
				requestedConfigurationRole);
		WorldBuilderAdaptiveConfiguration configuration = selection.selected;
		if (!"layered".equals(configuration.representation)) {
			throw problem(WorldBuilderErrorCodes.CAPABILITY_MISMATCH,
				configuration.relativePath,
				"Generic layered adapter was given a " + configuration.representation
					+ " configuration.",
				"Select a layered configuration or the matching packed adapter.");
		}

		WorldBuilderCompatibilityEvidence common =
			WorldBuilderCompatibilityEvidence.inspect(target, capability, configuration);
		WorldBuilderGenericLayeredPackage server = WorldBuilderGenericLayeredPackage.inspect(
			target, configuration.serverMapRelativePath, "server", common.definitions);
		WorldBuilderGenericLayeredPackage client = WorldBuilderGenericLayeredPackage.inspect(
			target, configuration.clientMapRelativePath, "client", common.definitions);
		if (!server.packageId.equals(client.packageId)
			|| !server.packageVersion.equals(client.packageVersion)
			|| !server.worldSpace.equals(client.worldSpace)
			|| !server.fingerprintSha256.equals(client.fingerprintSha256)) {
			throw problem(WorldBuilderErrorCodes.MAP_MISMATCH,
				configuration.serverMapRelativePath,
				"Server and client active layered packages are not byte-for-byte equivalent.",
				"Install one exact active layered package on both server and client.");
		}

		List<WorldBuilderReadOnlyTarget.FileState> files =
			new ArrayList<WorldBuilderReadOnlyTarget.FileState>();
		files.addAll(common.files);
		files.addAll(server.files);
		files.addAll(client.files);
		validateInventoryAndRoles(files, capability,
			WorldBuilderTargetCapability.RELATIVE_PATH);

		List<WorldBuilderAdapterInspection.Check> checks =
			new ArrayList<WorldBuilderAdapterInspection.Check>();
		checks.add(new WorldBuilderAdapterInspection.Check(
			"adapter-capability", "passed",
			"Descriptor facts are independently accepted by generic-layered-v1.",
			capability.capabilityId + " selects " + capability.adapterId + "."));
		checks.add(new WorldBuilderAdapterInspection.Check(
			"authoring-capability", "passed",
			"Existing levels, new levels, and all four placement families are authorable.",
			"Server and client runtime evidence confirms the complete authoring set."));
		checks.add(new WorldBuilderAdapterInspection.Check(
			"client-server-map-agreement", "passed",
			"Server and client select the same complete layered package.",
			server.packageId + " " + server.packageVersion + " at "
				+ server.fingerprintSha256 + "."));
		checks.add(new WorldBuilderAdapterInspection.Check(
			"configuration-selection", "passed",
			"Exactly one active configuration is selected, or the requested active role is explicit.",
			configuration.configurationId + " at " + configuration.relativePath + "."));
		checks.add(new WorldBuilderAdapterInspection.Check(
			"format-validation", "passed",
			"Every manifest, raw sector, placement set, path, size, and hash is strict.",
			server.levelCount + " level(s), " + server.terrainCount + " sector(s), "
				+ server.placementSetCount + " placement set(s)."));
		checks.add(new WorldBuilderAdapterInspection.Check(
			"inventory-completeness", "passed",
			"Only bounded declared package/evidence files are inventoried.",
			files.size() + " complete source evidence file(s)."));
		checks.add(new WorldBuilderAdapterInspection.Check(
			"placement-validation", "passed",
			"All placement families have unique IDs/slots, valid definitions/ranges, and terrain coverage.",
			server.boundaryCount + " boundary, " + server.groundItemCount
				+ " ground-item, " + server.npcCount + " NPC, and "
				+ server.sceneryCount + " scenery record(s)."));
		checks.addAll(common.checks);
		return new WorldBuilderAdapterInspection(ID, capability.capabilityId,
			WorldBuilderTargetCapability.RELATIVE_PATH, capability.evidenceSha256,
			"layered", selection.candidates(), selection.selectedCandidate(), files, checks);
	}

	private static void requireCapability(WorldBuilderTargetCapability capability)
		throws WorldBuilderContractException {
		List<String> families = Arrays.asList("boundary", "ground-item", "npc", "scenery");
		if (!FORMAT_ID.equals(capability.mapFormatId)
			|| !PACKAGE_SCHEMA_ID.equals(capability.packageSchemaId)
			|| !capability.sourceRepresentations.equals(Collections.singletonList("layered"))
			|| !capability.encodingVersions.equals(Arrays.asList(
				Integer.valueOf(1), Integer.valueOf(3)))
			|| !capability.editExistingLevels || !capability.createLevels
			|| !capability.placementFamilies.equals(families)
			|| !capability.serverLoaderId.equals(capability.clientLoaderId)) {
			throw problem(WorldBuilderErrorCodes.CAPABILITY_MISMATCH,
				WorldBuilderTargetCapability.RELATIVE_PATH,
				"Descriptor does not declare the complete generic layered map/authoring contract.",
				"Declare signed-layered-v1, package v1, encodings 1/3, matching loaders, and all authoring families.");
		}
		if (capability.installEnabled) {
			if (!MUTATION_PROFILE_ID.equals(capability.mutationProfileId)
				|| !capability.installServerRoles.equals(
					Collections.singletonList("layered-package"))
				|| !capability.installClientRoles.equals(
					Collections.singletonList("layered-package"))
				|| !capability.installConfigurationRoles.equals(
					capability.configurationRoles)) {
				throw problem(WorldBuilderErrorCodes.CAPABILITY_MISMATCH,
					WorldBuilderTargetCapability.RELATIVE_PATH,
					"Descriptor requests install authority outside the compiled generic profile.",
					"Use generic-layered-install-v1 with only layered-package and declared configuration roles.");
			}
		}
	}

	static void validateInventoryAndRoles(
		List<WorldBuilderReadOnlyTarget.FileState> files,
		WorldBuilderTargetCapability capability,
		String provenance) throws WorldBuilderContractException {
		List<WorldBuilderReadOnlyTarget.FileState> sorted =
			new ArrayList<WorldBuilderReadOnlyTarget.FileState>(files);
		Collections.sort(sorted);
		List<Object> records = new ArrayList<Object>(sorted.size());
		Set<String> roles = new HashSet<String>();
		for (WorldBuilderReadOnlyTarget.FileState file : sorted) {
			records.add(file.toJson());
			roles.add(file.role);
		}
		WorldBuilderBoundedInventory.read(records, "discover-target", 1, true);
		if (!roles.equals(new HashSet<String>(capability.sourceRoles))) {
			Set<String> missing = new TreeSet<String>(capability.sourceRoles);
			missing.removeAll(roles);
			Set<String> undeclared = new TreeSet<String>(roles);
			undeclared.removeAll(capability.sourceRoles);
			throw problem(WorldBuilderErrorCodes.CAPABILITY_MISMATCH, provenance,
				"Capability source roles do not match the complete inventory; missing="
					+ missing + ", undeclared=" + undeclared + ".",
				"Declare every adapter-produced logical source role exactly once.");
		}
	}

	private static WorldBuilderContractException problem(
		String code, String path, String message, String nextStep) {
		return WorldBuilderReadOnlyTarget.problem(code, path, message, nextStep);
	}
}
