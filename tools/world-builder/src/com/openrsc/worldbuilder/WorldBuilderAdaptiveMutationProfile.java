package com.openrsc.worldbuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Compiled, bounded server/client mutation profiles for adaptive installation. */
final class WorldBuilderAdaptiveMutationProfile {
	static final String GENERIC_PROFILE = "generic-layered-install-v1";
	static final String PACKED_PROFILE = "spoiled-milk-layered-install-v1";
	static final String SERVER_PACKAGE_ROOT = "server/world-builder/packages";
	static final String CLIENT_PACKAGE_ROOT = "client/world-builder/packages";
	static final String LEGACY_CLIENT_PACKAGE_ROOT = "Client_Base/world-builder/packages";
	static final String TRANSACTION_CONTENT_CONFIG =
		"package/activation/selected-configuration.json";
	private static final String OPERATION = "plan-adaptive-import";
	private static final String ZERO_HASH =
		"0000000000000000000000000000000000000000000000000000000000000000";

	private WorldBuilderAdaptiveMutationProfile() {
	}

	static Plan prepare(
		WorldBuilderAdaptiveProjectLifecycle.VerifiedProject project,
		WorldBuilderAdaptiveExporter.VerifiedExport export,
		Path targetRoot,
		String transactionId) throws IOException, WorldBuilderContractException {
		if ("standalone-empty".equals(project.origin)) throw problem(
			WorldBuilderErrorCodes.NO_TARGET, "target-root",
			"Standalone projects have no compatible target server.",
			"Continue editing/exporting this standalone project; Import is unavailable.");
		Map<String,Object> projectTarget = WorldBuilderAdaptiveExporter.object(
			project.manifest.get("target"), "target");
		Map<String,Object> selectedReference = WorldBuilderAdaptiveExporter.object(
			project.snapshot.get("selectedConfiguration"), "selectedConfiguration");
		String selectedRole = WorldBuilderAdaptiveExporter.string(
			selectedReference, "role");
		Path target = requireTarget(targetRoot);
		preflightCompiledInstallRoots(target);
		safeExistingFile(target,
			WorldBuilderAdaptiveConfiguration.pathForRole(selectedRole),
			"Selected target configuration");

		WorldBuilderAdaptiveDiscoveryReport fresh =
			new WorldBuilderAdaptiveDiscovery().discover(target,
				WorldBuilderAdaptiveProjectLifecycle.rediscoveryRole(
					project.discoveryReport));
		String expectedLineage = WorldBuilderAdaptiveExporter.string(
			projectTarget, "targetFingerprintSha256");
		if (!"compatible".equals(fresh.status)
			|| !expectedLineage.equals(fresh.fingerprintSha256())) {
			throw problem(WorldBuilderErrorCodes.TARGET_DRIFT, "target-root",
				"Target map, configuration, definitions, runtime, client, or capability "
					+ "no longer matches this project's immutable source lineage.",
				"Do not force the import; restore the exact compatible target or create a new project.");
		}

		WorldBuilderReadOnlyTarget readOnly = WorldBuilderReadOnlyTarget.open(target);
		WorldBuilderTargetCapability capability = WorldBuilderTargetCapability.read(readOnly);
		String adapter = WorldBuilderAdaptiveExporter.string(projectTarget, "adapterId");
		String capabilityId = WorldBuilderAdaptiveExporter.string(
			projectTarget, "capabilityId");
		String profile = WorldBuilderAdaptiveExporter.string(
			projectTarget, "importProfileId");
		if (!capability.installEnabled || !adapter.equals(capability.adapterId)
			|| !capabilityId.equals(capability.capabilityId)
			|| !profile.equals(capability.mutationProfileId)
			|| !(GENERIC_PROFILE.equals(profile) || PACKED_PROFILE.equals(profile))) {
			throw problem(WorldBuilderErrorCodes.LOADER_INCOMPATIBLE,
				WorldBuilderTargetCapability.RELATIVE_PATH,
				"Target does not advertise this project's exact compiled layered install profile.",
				"Install the matching compatible server/client runtime before importing.");
		}
		if (GENERIC_PROFILE.equals(profile)
			&& !WorldBuilderGenericLayeredAdapter.ID.equals(adapter)
			|| PACKED_PROFILE.equals(profile)
				&& !WorldBuilderPackedLayoutAdapter.ID.equals(adapter)) {
			throw problem(WorldBuilderErrorCodes.CAPABILITY_MISMATCH,
				WorldBuilderTargetCapability.RELATIVE_PATH,
				"Adapter and compiled mutation-profile identities disagree.",
				"Use the exact capability descriptor recorded when the project was created.");
		}
		requireInstallEncodingSupport(capability, export.packageValue);

		WorldBuilderAdaptiveConfiguration.Selection selection =
			WorldBuilderAdaptiveConfiguration.select(readOnly, capability, selectedRole);
		WorldBuilderAdaptiveConfiguration configuration = selection.selected;
		String configurationPath = WorldBuilderAdaptiveConfiguration.pathForRole(selectedRole);
		if (!configurationPath.equals(configuration.relativePath)
			|| !configuration.sha256.equals(
				WorldBuilderAdaptiveExporter.string(selectedReference, "sha256"))) {
			throw problem(WorldBuilderErrorCodes.TARGET_DRIFT, configurationPath,
				"Selected configuration no longer matches the project source snapshot.",
				"Restore the exact target or create a new project from current target state.");
		}

		String clientRoot = compiledClientRoot(configuration);
		String packageContentAddress = export.packageValue.nativeInventorySha256;
		String serverPackage = SERVER_PACKAGE_ROOT + "/" + packageContentAddress
			+ "/package";
		String clientPackage = clientRoot + "/world-builder/packages/"
			+ packageContentAddress + "/package";
		requireInstallRootsAbsent(target, serverPackage, clientPackage);
		Map<String,Object> originalConfiguration = readOnly.readObject(configurationPath);
		Map<String,Object> installedConfiguration = deepCopy(originalConfiguration);
		installedConfiguration.put("representation", "layered");
		installedConfiguration.put("serverMapRelativePath", serverPackage);
		installedConfiguration.put("clientMapRelativePath", clientPackage);
		installedConfiguration.put("placements", Collections.emptyList());
		byte[] configurationBytes = WorldBuilderJsonDocuments.pretty(
			installedConfiguration).getBytes(StandardCharsets.UTF_8);
		String configurationAfterHash = WorldBuilderHashes.sha256(configurationBytes);

		List<Action> actions = new ArrayList<Action>();
		List<WorldBuilderReadOnlyTarget.FileState> packageFiles =
			new ArrayList<WorldBuilderReadOnlyTarget.FileState>(export.packageValue.files);
		Collections.sort(packageFiles);
		String packagePrefix = WorldBuilderAdaptiveExporter.PACKAGE_DIRECTORY + "/";
		for (WorldBuilderReadOnlyTarget.FileState file : packageFiles) {
			if (!file.relativePath.startsWith(packagePrefix)) throw problem(
				WorldBuilderErrorCodes.UNSAFE_PATH, file.relativePath,
				"Validated export package file escaped its package root.",
				"Use one exact complete adaptive export.");
			String inside = file.relativePath.substring(packagePrefix.length());
			String serverDestination = serverPackage + "/" + inside;
			String clientDestination = clientPackage + "/" + inside;
			requireAbsentDestination(target, serverDestination);
			requireAbsentDestination(target, clientDestination);
			actions.add(Action.install("server-package-" + pad(actions.size()),
				serverDestination, file.relativePath, file.size, file.sha256));
			actions.add(Action.install("client-package-" + pad(actions.size()),
				clientDestination, file.relativePath, file.size, file.sha256));
		}
		Path configFile = safeExistingFile(target, configurationPath,
			"selected target configuration");
		FileState configBefore = FileState.present(
			Files.size(configFile), WorldBuilderHashes.sha256(configFile));
		if (!configuration.sha256.equals(configBefore.sha256)) throw problem(
			WorldBuilderErrorCodes.TARGET_DRIFT, configurationPath,
			"Selected configuration changed while the mutation plan was built.",
			"Stop target updates and request a fresh import preview.");
		actions.add(new Action("activation-configuration", configurationPath,
			configBefore, FileState.present(configurationBytes.length, configurationAfterHash),
			TRANSACTION_CONTENT_CONFIG,
			"backups/" + transactionId + "/before/" + configurationPath, true,
			configurationBytes));
		appendLegacyLandscapeRetirement(project, target, capability, configuration,
			selectedReference, expectedLineage, transactionId, actions, true);

		List<ConfigurationChange> changes = new ArrayList<ConfigurationChange>();
		addConfigurationChange(changes, configurationPath, "clientMapRelativePath",
			WorldBuilderAdaptiveExporter.string(originalConfiguration,
				"clientMapRelativePath"), clientPackage);
		addConfigurationChange(changes, configurationPath, "representation",
			WorldBuilderAdaptiveExporter.string(originalConfiguration, "representation"),
			"layered");
		addConfigurationChange(changes, configurationPath, "placements",
			WorldBuilderJsonDocuments.canonical(originalConfiguration.get("placements")),
			"[]");
		addConfigurationChange(changes, configurationPath, "serverMapRelativePath",
			WorldBuilderAdaptiveExporter.string(originalConfiguration,
				"serverMapRelativePath"), serverPackage);

		long requiredSpace = 0L;
		for (Action action : actions) {
			requiredSpace = safeAdd(requiredSpace, action.before.size);
			requiredSpace = safeAdd(requiredSpace, action.after.size);
		}
		List<String> directoriesToCreate = plannedDirectories(target, actions);
		Map<String,Object> document = document(transactionId, project, export,
			capability, configuration, expectedLineage, actions, changes,
			directoriesToCreate, requiredSpace);
		return new Plan(target, project, export, capability, configuration,
			profile, serverPackage, clientPackage, configurationBytes,
			actions, changes, directoriesToCreate, document);
	}

	static Plan prepareChained(
		WorldBuilderAdaptiveProjectLifecycle.VerifiedProject project,
		WorldBuilderAdaptiveExporter.VerifiedExport export,
		Path targetRoot, String transactionId, Plan installed)
		throws IOException, WorldBuilderContractException {
		Path target = requireTarget(targetRoot);
		if (!target.equals(installed.targetRoot)
			|| !project.projectId.equals(installed.project.projectId)) throw problem(
			WorldBuilderErrorCodes.CAPABILITY_MISMATCH, "target-root",
			"Installed transaction authority does not belong to this project and target.",
			"Use the exact project and server root from the latest successful import.");
		List<String> changed = WorldBuilderAdaptiveUndo.changedAfterPaths(installed);
		if (!changed.isEmpty()) throw problem(
			WorldBuilderErrorCodes.TARGET_DRIFT, changed.get(0),
			"The latest installed World Builder package or activation changed: "
				+ joinPaths(changed) + ".",
			"Restore the exact current installed package before importing again; no force mode exists.");
		verifyUnchangedTargetEvidence(project, target,
			installed.configuration.relativePath);
		WorldBuilderReadOnlyTarget readOnly = WorldBuilderReadOnlyTarget.open(target);
		WorldBuilderTargetCapability capability = WorldBuilderTargetCapability.read(readOnly);
		if (!installed.capability.evidenceSha256.equals(capability.evidenceSha256)
			|| !installed.capability.capabilityId.equals(capability.capabilityId)
			|| !installed.profileId.equals(capability.mutationProfileId)) throw problem(
			WorldBuilderErrorCodes.CAPABILITY_MISMATCH,
			WorldBuilderTargetCapability.RELATIVE_PATH,
			"Target capability changed after the latest successful import.",
			"Restore the exact compatible target capability before importing again.");
		requireInstallEncodingSupport(capability, export.packageValue);

		WorldBuilderAdaptiveDiscoveryReport fresh =
			new WorldBuilderAdaptiveDiscovery().discover(target,
				WorldBuilderAdaptiveProjectLifecycle.rediscoveryRole(
					project.discoveryReport));
		if (!"compatible".equals(fresh.status)) throw problem(
			WorldBuilderErrorCodes.TARGET_DRIFT, "target-root",
			"The currently installed World Builder package is not a compatible import baseline.",
			"Restore the exact latest successful import before importing again.");

		String selectedRole = installed.configuration.configurationId;
		WorldBuilderAdaptiveConfiguration configuration =
			WorldBuilderAdaptiveConfiguration.select(readOnly, capability,
				selectedRole).selected;
		String configurationPath = installed.configuration.relativePath;
		if (!configurationPath.equals(configuration.relativePath)) throw problem(
			WorldBuilderErrorCodes.TARGET_DRIFT, configurationPath,
			"The active configuration path changed after the latest successful import.",
			"Restore the exact installed target before importing again.");

		String clientRoot = compiledClientRoot(configuration);
		String packageContentAddress = export.packageValue.nativeInventorySha256;
		String serverPackage = SERVER_PACKAGE_ROOT + "/" + packageContentAddress
			+ "/package";
		String clientPackage = clientRoot + "/world-builder/packages/"
			+ packageContentAddress + "/package";
		if (serverPackage.equals(installed.serverPackageRelativePath)
			|| clientPackage.equals(installed.clientPackageRelativePath)) throw problem(
			WorldBuilderErrorCodes.CONTRACT_VALUE_INVALID, "exports",
			"This exact exported map package is already installed on the target.",
			"Make and save another map edit before importing again.");
		requireInstallRootsAbsent(target, serverPackage, clientPackage);

		Map<String,Object> beforeConfiguration = readOnly.readObject(configurationPath);
		Map<String,Object> afterConfiguration = deepCopy(beforeConfiguration);
		afterConfiguration.put("representation", "layered");
		afterConfiguration.put("serverMapRelativePath", serverPackage);
		afterConfiguration.put("clientMapRelativePath", clientPackage);
		afterConfiguration.put("placements", Collections.emptyList());
		byte[] configurationBytes = WorldBuilderJsonDocuments.pretty(afterConfiguration)
			.getBytes(StandardCharsets.UTF_8);

		List<Action> actions = packageInstallActions(export, serverPackage, clientPackage);
		Path configFile = safeExistingFile(target, configurationPath,
			"selected target configuration");
		FileState configurationBefore = FileState.present(
			Files.size(configFile), WorldBuilderHashes.sha256(configFile));
		if (!configuration.sha256.equals(configurationBefore.sha256)) throw problem(
			WorldBuilderErrorCodes.TARGET_DRIFT, configurationPath,
			"Active configuration changed while the replacement plan was built.",
			"Stop target updates and request a fresh import preview.");
		actions.add(new Action("activation-configuration", configurationPath,
			configurationBefore, FileState.present(configurationBytes.length,
				WorldBuilderHashes.sha256(configurationBytes)),
			TRANSACTION_CONTENT_CONFIG,
			"backups/" + transactionId + "/before/" + configurationPath,
			true, configurationBytes));

		List<ConfigurationChange> changes = configurationChanges(configurationPath,
			beforeConfiguration, serverPackage, clientPackage);
		long requiredSpace = requiredSpace(actions);
		List<String> directories = plannedDirectories(target, actions);
		Map<String,Object> document = document(transactionId, project, export,
			capability, configuration, fresh.fingerprintSha256(), actions, changes,
			directories, requiredSpace);
		return new Plan(target, project, export, capability, configuration,
			installed.profileId, serverPackage, clientPackage, configurationBytes,
			actions, changes, directories, document);
	}

	private static List<Action> packageInstallActions(
		WorldBuilderAdaptiveExporter.VerifiedExport export,
		String serverPackage, String clientPackage)
		throws WorldBuilderContractException {
		List<Action> actions = new ArrayList<Action>();
		List<WorldBuilderReadOnlyTarget.FileState> packageFiles =
			new ArrayList<WorldBuilderReadOnlyTarget.FileState>(export.packageValue.files);
		Collections.sort(packageFiles);
		String prefix = WorldBuilderAdaptiveExporter.PACKAGE_DIRECTORY + "/";
		for (WorldBuilderReadOnlyTarget.FileState file : packageFiles) {
			if (!file.relativePath.startsWith(prefix)) throw problem(
				WorldBuilderErrorCodes.UNSAFE_PATH, file.relativePath,
				"Validated export package file escaped its package root.",
				"Use one exact complete adaptive export.");
			String inside = file.relativePath.substring(prefix.length());
			actions.add(Action.install("server-package-" + pad(actions.size()),
				serverPackage + "/" + inside, file.relativePath, file.size, file.sha256));
			actions.add(Action.install("client-package-" + pad(actions.size()),
				clientPackage + "/" + inside, file.relativePath, file.size, file.sha256));
		}
		return actions;
	}

	private static List<ConfigurationChange> configurationChanges(String path,
		Map<String,Object> before, String serverPackage, String clientPackage)
		throws WorldBuilderContractException {
		List<ConfigurationChange> changes = new ArrayList<ConfigurationChange>();
		addConfigurationChange(changes, path, "clientMapRelativePath",
			WorldBuilderAdaptiveExporter.string(before, "clientMapRelativePath"),
			clientPackage);
		addConfigurationChange(changes, path, "representation",
			WorldBuilderAdaptiveExporter.string(before, "representation"), "layered");
		addConfigurationChange(changes, path, "placements",
			WorldBuilderJsonDocuments.canonical(before.get("placements")), "[]");
		addConfigurationChange(changes, path, "serverMapRelativePath",
			WorldBuilderAdaptiveExporter.string(before, "serverMapRelativePath"),
			serverPackage);
		return changes;
	}

	private static long requiredSpace(List<Action> actions)
		throws WorldBuilderContractException {
		long result = 0L;
		for (Action action : actions) {
			result = safeAdd(result, action.before.size);
			result = safeAdd(result, action.after.size);
		}
		return result;
	}

	private static void requireInstallEncodingSupport(
		WorldBuilderTargetCapability capability,
		WorldBuilderGenericLayeredPackage packageValue)
		throws WorldBuilderContractException {
		List<Integer> missing = new ArrayList<Integer>();
		for (Integer required : packageValue.requiredEncodingVersions) {
			if (!capability.encodingVersions.contains(required)) missing.add(required);
		}
		if (!missing.isEmpty()) throw problem(
			WorldBuilderErrorCodes.LOADER_INCOMPATIBLE,
			WorldBuilderTargetCapability.RELATIVE_PATH,
			"Exported layered package requires encoding version(s) " + missing
				+ ", but the target runtime advertises "
				+ capability.encodingVersions + ".",
			"Adopt runtime support for the required terrain/placement encodings, regenerate truthful target capability evidence, and retry import.");
	}

	private static void appendLegacyLandscapeRetirement(
		WorldBuilderAdaptiveProjectLifecycle.VerifiedProject project,
		Path target, WorldBuilderTargetCapability capability,
		WorldBuilderAdaptiveConfiguration configuration,
		Map<String,Object> selectedReference, String expectedLineage,
		String transactionId, List<Action> actions, boolean requirePresent)
		throws IOException, WorldBuilderContractException {
		Path choicePath = WorldBuilderPortablePath.resolveContained(
			project.projectRoot, "source/migration/choice.json", OPERATION);
		if (!Files.exists(choicePath, LinkOption.NOFOLLOW_LINKS)) return;
		WorldBuilderAdaptiveExporter.requireFile(project.projectRoot,
			"source/migration/choice.json", "immutable map-migration choice");
		Map<String,Object> choice;
		try {
			choice = WorldBuilderJsonDocuments.readObject(choicePath);
		} catch (WorldBuilderDiscoveryException malformed) {
			throw problem(WorldBuilderErrorCodes.SOURCE_CORRUPT,
				"source/migration/choice.json",
				"Immutable map-migration choice JSON is malformed.",
				"Restore the complete project from a trusted backup.");
		}
		WorldBuilderAdaptiveContracts.Kind choiceKind = migrationChoiceKind(choice);
		WorldBuilderAdaptiveContracts.validateParsed(choiceKind, choice);
		boolean primaryPacked = choiceKind
			== WorldBuilderAdaptiveContracts.Kind.PACKED_MAP_MIGRATION_CHOICE;
		WorldBuilderAdaptiveExporter.requireFingerprint(
			choice, "migrationChoiceFingerprintSha256");
		if (!WorldBuilderAdaptiveExporter.bool(choice, "retirementRequested")) return;
		if (!expectedLineage.equals(WorldBuilderAdaptiveExporter.string(
			choice, "selectedTargetDiscoveryFingerprintSha256"))) {
			throw problem(WorldBuilderErrorCodes.SOURCE_CORRUPT,
				"source/migration/choice.json",
				"Legacy retirement choice is not bound to this project's selected target lineage.",
				"Restore the complete migrated project from a trusted backup.");
		}

		Map<String,Object> choiceConfiguration = WorldBuilderAdaptiveExporter.object(
			choice.get("selectedConfiguration"), "selectedConfiguration");
		String role = WorldBuilderAdaptiveExporter.string(selectedReference, "role");
		String selectedSourcePath = WorldBuilderAdaptiveExporter.string(
			selectedReference, "relativePath");
		String expectedSourcePath = "source/original/" + configuration.relativePath;
		if (!role.equals(configuration.configurationId)
			|| !role.equals(WorldBuilderAdaptiveExporter.string(
				choiceConfiguration, "role"))
			|| !expectedSourcePath.equals(selectedSourcePath)
			|| !configuration.relativePath.equals(WorldBuilderAdaptiveExporter.string(
				choiceConfiguration, "relativePath"))
			|| !configuration.sha256.equals(WorldBuilderAdaptiveExporter.string(
				selectedReference, "sha256"))
			|| !configuration.sha256.equals(WorldBuilderAdaptiveExporter.string(
				choiceConfiguration, "sha256"))) {
			throw problem(WorldBuilderErrorCodes.SOURCE_CORRUPT,
				"source/migration/choice.json",
				"Legacy retirement choice and selected configuration disagree.",
				"Restore the exact migrated project; do not retire target files manually.");
		}
		boolean validAuthority = primaryPacked
			? "packed".equals(configuration.representation)
				&& PACKED_PROFILE.equals(capability.mutationProfileId)
				&& WorldBuilderPackedLayoutAdapter.ID.equals(capability.adapterId)
			: "layered".equals(configuration.representation);
		if (!validAuthority
			|| capability.configurationRoles.size() != 1
			|| !role.equals(capability.configurationRoles.get(0))
			|| capability.installConfigurationRoles.size() != 1
			|| !role.equals(capability.installConfigurationRoles.get(0))) {
			throw problem(WorldBuilderErrorCodes.CAPABILITY_MISMATCH,
				WorldBuilderTargetCapability.RELATIVE_PATH,
				"Target capability does not prove one exact activation authority for legacy retirement.",
				"Keep Custom_Landscape in place until the target advertises one matching install role.");
		}

		Map<String,Object> terrain = WorldBuilderAdaptiveExporter.object(
			choice.get("legacyTerrain"), "legacyTerrain");
		List<Map<String,Object>> records = new ArrayList<Map<String,Object>>();
		records.add(WorldBuilderAdaptiveExporter.object(terrain.get("client"),
			"legacyTerrain.client"));
		records.add(WorldBuilderAdaptiveExporter.object(terrain.get("server"),
			"legacyTerrain.server"));
		Collections.sort(records, new Comparator<Map<String,Object>>() {
			@Override public int compare(Map<String,Object> left,
				Map<String,Object> right) {
				try {
					return WorldBuilderAdaptiveExporter.string(left, "relativePath")
						.compareTo(WorldBuilderAdaptiveExporter.string(right, "relativePath"));
				} catch (WorldBuilderContractException impossible) {
					throw new IllegalStateException(impossible);
				}
			}
		});
		for (Map<String,Object> record : records) {
			String relative = WorldBuilderAdaptiveExporter.string(record, "relativePath");
			if (!primaryPacked && configurationReferences(configuration, relative)) {
				throw problem(WorldBuilderErrorCodes.CAPABILITY_MISMATCH, relative,
					"Selected layered configuration still references the legacy landscape file.",
					"Correct the target configuration before requesting retirement.");
			}
			long size = WorldBuilderAdaptiveExporter.integer(record, "size");
			String sha256 = WorldBuilderAdaptiveExporter.string(record, "sha256");
			if (requirePresent) {
				Path live = safeExistingFile(target, relative, "legacy landscape retirement input");
				if (Files.size(live) != size
					|| !sha256.equals(WorldBuilderHashes.sha256(live))) {
					throw problem(WorldBuilderErrorCodes.TARGET_DRIFT, relative,
						"Legacy landscape bytes changed after the migration choice was recorded.",
						"Create a new migrated project from the current target; no force retirement exists.");
				}
			}
			actions.add(new Action("retire-legacy-landscape-" + pad(actions.size()),
				relative, FileState.present(size, sha256), FileState.absent(), "",
				"backups/" + transactionId + "/before/" + relative, true, null));
		}
	}

	private static boolean configurationReferences(
		WorldBuilderAdaptiveConfiguration configuration, String relative) {
		if (relative.equals(configuration.serverMapRelativePath)
			|| relative.equals(configuration.clientMapRelativePath)
			|| relative.equals(configuration.serverRuntimeRelativePath)
			|| relative.equals(configuration.clientRuntimeRelativePath)
			|| relative.equals(configuration.serverDefinitionCatalogRelativePath)
			|| relative.equals(configuration.clientDefinitionCatalogRelativePath)) return true;
		for (WorldBuilderAdaptiveConfiguration.AssetPair asset : configuration.assets) {
			if (relative.equals(asset.serverRelativePath)
				|| relative.equals(asset.clientRelativePath)) return true;
		}
		for (WorldBuilderAdaptiveConfiguration.PlacementSource placement :
			configuration.placements) {
			if (relative.equals(placement.relativePath)) return true;
		}
		return false;
	}

	private static void preflightCompiledInstallRoots(Path target)
		throws IOException, WorldBuilderContractException {
		for (String probe : new String[] {
			"server/world-builder/packages/probe",
			"client/world-builder/packages/probe",
			"Client_Base/world-builder/packages/probe"
		}) {
			safeDestination(target, probe);
		}
	}

	/**
	 * Rebuilds the exact import plan from project-owned immutable evidence after
	 * that plan has been installed.  Unlike {@link #prepare}, this path never
	 * treats the deliberately changed active configuration as fresh discovery
	 * lineage.  Every unaffected target input is still checked against the
	 * immutable source snapshot, while writable destinations remain compiled.
	 */
	static Plan reconstructInstalled(
		WorldBuilderAdaptiveProjectLifecycle.VerifiedProject project,
		WorldBuilderAdaptiveExporter.VerifiedExport export,
		Path targetRoot, String transactionId)
		throws IOException, WorldBuilderContractException {
		if ("standalone-empty".equals(project.origin)) throw problem(
			WorldBuilderErrorCodes.NO_TARGET, "target-root",
			"Standalone projects have no compatible target server.",
			"Continue editing/exporting this standalone project; Undo is unavailable.");
		Path target = requireTarget(targetRoot);
		Map<String,Object> projectTarget = WorldBuilderAdaptiveExporter.object(
			project.manifest.get("target"), "target");
		Map<String,Object> selectedReference = WorldBuilderAdaptiveExporter.object(
			project.snapshot.get("selectedConfiguration"), "selectedConfiguration");
		String selectedRole = WorldBuilderAdaptiveExporter.string(
			selectedReference, "role");
		String configurationPath = WorldBuilderAdaptiveConfiguration.pathForRole(
			selectedRole);
		if (!("source/original/" + configurationPath).equals(
			WorldBuilderAdaptiveExporter.string(
				selectedReference, "relativePath"))) throw problem(
			WorldBuilderErrorCodes.SOURCE_CORRUPT, "source/snapshot.json",
			"Immutable selected-configuration role and path disagree.",
			"Restore the complete project from a trusted backup.");

		verifyUnchangedTargetEvidence(project, target, configurationPath);
		WorldBuilderReadOnlyTarget live = WorldBuilderReadOnlyTarget.open(target);
		WorldBuilderTargetCapability capability = WorldBuilderTargetCapability.read(live);
		String adapter = WorldBuilderAdaptiveExporter.string(projectTarget, "adapterId");
		String capabilityId = WorldBuilderAdaptiveExporter.string(
			projectTarget, "capabilityId");
		String profile = WorldBuilderAdaptiveExporter.string(
			projectTarget, "importProfileId");
		if (!capability.installEnabled || !adapter.equals(capability.adapterId)
			|| !capabilityId.equals(capability.capabilityId)
			|| !profile.equals(capability.mutationProfileId)
			|| !(GENERIC_PROFILE.equals(profile) || PACKED_PROFILE.equals(profile))) {
			throw problem(WorldBuilderErrorCodes.CAPABILITY_MISMATCH,
				WorldBuilderTargetCapability.RELATIVE_PATH,
				"Target capability no longer matches the installed transaction profile.",
				"Keep the target offline and restore its exact compatible capability.");
		}
		if (GENERIC_PROFILE.equals(profile)
			&& !WorldBuilderGenericLayeredAdapter.ID.equals(adapter)
			|| PACKED_PROFILE.equals(profile)
				&& !WorldBuilderPackedLayoutAdapter.ID.equals(adapter)) {
			throw problem(WorldBuilderErrorCodes.CAPABILITY_MISMATCH,
				WorldBuilderTargetCapability.RELATIVE_PATH,
				"Adapter and compiled mutation-profile identities disagree.",
				"Restore the exact target capability recorded by this project.");
		}

		Path originalRoot = WorldBuilderAdaptiveExporter.requireDirectory(
			project.projectRoot, "source/original", "immutable original evidence");
		WorldBuilderReadOnlyTarget original = WorldBuilderReadOnlyTarget.open(originalRoot);
		WorldBuilderReadOnlyTarget.FileState originalConfigurationState =
			original.requiredState("configuration." + selectedRole, configurationPath);
		String selectedHash = WorldBuilderAdaptiveExporter.string(
			selectedReference, "sha256");
		if (!selectedHash.equals(originalConfigurationState.sha256)) throw problem(
			WorldBuilderErrorCodes.SOURCE_CORRUPT, configurationPath,
			"Immutable selected configuration hash changed.",
			"Restore the complete project from a trusted backup.");
		WorldBuilderAdaptiveConfiguration configuration =
			WorldBuilderAdaptiveConfiguration.read(
				original, configurationPath, originalConfigurationState.sha256);
		if (!selectedRole.equals(configuration.configurationId)) throw problem(
			WorldBuilderErrorCodes.SOURCE_CORRUPT, configurationPath,
			"Immutable configuration identity no longer matches its role.",
			"Restore the complete project from a trusted backup.");

		Path durablePlan = WorldBuilderPortablePath.resolveContained(
			project.projectRoot, "backups/" + transactionId + "/mutation-plan.json",
			OPERATION);
		WorldBuilderAdaptiveExporter.requireFile(project.projectRoot,
			"backups/" + transactionId + "/mutation-plan.json",
			"durable adaptive mutation plan");
		WorldBuilderAdaptiveContracts.Document stored =
			WorldBuilderAdaptiveContracts.read(
				WorldBuilderAdaptiveContracts.Kind.MUTATION_PLAN, durablePlan);
		Map<String,Object> storedObject;
		try {
			storedObject = WorldBuilderJsonDocuments.readObject(durablePlan);
		} catch (WorldBuilderDiscoveryException malformed) {
			throw problem(WorldBuilderErrorCodes.RECOVERY_REQUIRED,
				"backups/" + transactionId + "/mutation-plan.json",
				"Durable mutation plan JSON is malformed.",
				"Retain the project and restore its exact transaction evidence.");
		}
		WorldBuilderAdaptiveExporter.requireFingerprint(
			storedObject, "planFingerprintSha256");
		Map<String,Object> storedSelected = WorldBuilderAdaptiveExporter.object(
			storedObject.get("selectedConfiguration"), "selectedConfiguration");
		String planSelectedHash = WorldBuilderAdaptiveExporter.string(
			storedSelected, "sha256");
		Map<String,Object> beforeConfiguration = original.readObject(configurationPath);
		FileState configurationBefore = FileState.present(
			originalConfigurationState.size, originalConfigurationState.sha256);
		String lineage = WorldBuilderAdaptiveExporter.string(
			projectTarget, "targetFingerprintSha256");
		boolean chained = !planSelectedHash.equals(originalConfigurationState.sha256);
		if (chained) {
			String backupRelative = "backups/" + transactionId + "/before/"
				+ configurationPath;
			Path backup = WorldBuilderAdaptiveExporter.requireFile(
				project.projectRoot, backupRelative,
				"chained import configuration before-state backup");
			byte[] beforeBytes = Files.readAllBytes(backup);
			if (!planSelectedHash.equals(WorldBuilderHashes.sha256(beforeBytes))) throw problem(
				WorldBuilderErrorCodes.RECOVERY_REQUIRED, backupRelative,
				"Chained import configuration backup does not match its selected before state.",
				"Restore the exact complete transaction backup; do not force undo.");
			WorldBuilderAdaptiveConfiguration chainedConfiguration =
				WorldBuilderAdaptiveConfiguration.readBytes(beforeBytes,
					configurationPath, planSelectedHash);
			Map<String,Object> savedConfiguration = configurationDocument(
				beforeBytes, backupRelative);
			if (!selectedRole.equals(chainedConfiguration.configurationId)
				|| !immutableConfigurationFields(beforeConfiguration,
					savedConfiguration)) {
				throw problem(WorldBuilderErrorCodes.RECOVERY_REQUIRED, backupRelative,
					"Chained import configuration changes evidence outside map activation fields.",
					"Restore the exact compatible transaction backup.");
			}
			configuration = chainedConfiguration;
			beforeConfiguration = savedConfiguration;
			configurationBefore = FileState.present(beforeBytes.length, planSelectedHash);
			lineage = WorldBuilderAdaptiveExporter.string(
				storedObject, "targetLineageSha256");
		}

		String clientRoot = compiledClientRoot(configuration);
		String packageContentAddress = reconstructedPackageContentAddress(
			storedObject, clientRoot, export.packageValue.nativeInventorySha256,
			export.packageValue.fingerprintSha256);
		String serverPackage = SERVER_PACKAGE_ROOT + "/" + packageContentAddress
			+ "/package";
		String clientPackage = clientRoot + "/world-builder/packages/"
			+ packageContentAddress + "/package";
		Map<String,Object> installedConfiguration = deepCopy(beforeConfiguration);
		installedConfiguration.put("representation", "layered");
		installedConfiguration.put("serverMapRelativePath", serverPackage);
		installedConfiguration.put("clientMapRelativePath", clientPackage);
		installedConfiguration.put("placements", Collections.emptyList());
		byte[] configurationBytes = WorldBuilderJsonDocuments.pretty(
			installedConfiguration).getBytes(StandardCharsets.UTF_8);
		String configurationAfterHash = WorldBuilderHashes.sha256(configurationBytes);

		List<Action> actions = packageInstallActions(
			export, serverPackage, clientPackage);
		actions.add(new Action("activation-configuration", configurationPath,
			configurationBefore,
			FileState.present(configurationBytes.length, configurationAfterHash),
			TRANSACTION_CONTENT_CONFIG,
			"backups/" + transactionId + "/before/" + configurationPath, true,
			configurationBytes));
		if (!chained) appendLegacyLandscapeRetirement(project, target, capability,
			configuration, selectedReference, lineage, transactionId, actions, false);

		List<ConfigurationChange> changes = configurationChanges(configurationPath,
			beforeConfiguration, serverPackage, clientPackage);

		long requiredSpace = requiredSpace(actions);
		List<String> directories = planCreatedDirectories(storedObject, actions);
		Map<String,Object> generated = document(transactionId, project, export,
			capability, configuration, lineage, actions, changes, directories,
			requiredSpace, planSelectedHash);
		Plan plan = new Plan(target, project, export, capability, configuration,
			profile, serverPackage, clientPackage, configurationBytes,
			actions, changes, directories, generated);
		if (!plan.canonicalSha256.equals(stored.canonicalSha256)) throw problem(
			WorldBuilderErrorCodes.RECOVERY_REQUIRED,
			"backups/" + transactionId + "/mutation-plan.json",
			"Durable mutation plan does not match independently compiled project/export paths.",
			"Keep the target offline and restore exact transaction evidence; do not force undo.");
		List<String> evidenceDirectories = readCreatedDirectories(
			project.projectRoot, transactionId, generated, actions);
		if (!directories.equals(evidenceDirectories)) throw problem(
			WorldBuilderErrorCodes.RECOVERY_REQUIRED,
			"backups/" + transactionId + "/created-directories.json",
			"Created-directory evidence differs from the immutable mutation plan.",
			"Restore the exact complete transaction evidence; do not force undo.");
		requireBeforeBackups(plan);
		return plan;
	}

	private static boolean immutableConfigurationFields(Map<String,Object> original,
		Map<String,Object> candidate) {
		Map<String,Object> left = deepCopy(original);
		Map<String,Object> right = deepCopy(candidate);
		for (String mutable : new String[] {
			"representation", "serverMapRelativePath", "clientMapRelativePath",
			"placements"
		}) {
			left.remove(mutable);
			right.remove(mutable);
		}
		return left.equals(right);
	}

	static String reconstructedPackageContentAddress(Map<String,Object> storedPlan,
		String clientRoot, String nativeAddress, String legacyAddress)
		throws WorldBuilderContractException {
		Set<String> candidates = new java.util.LinkedHashSet<String>();
		candidates.add(nativeAddress);
		candidates.add(legacyAddress);
		String serverAfter = "";
		String clientAfter = "";
		for (Object raw : WorldBuilderAdaptiveExporter.array(
			storedPlan.get("configurationChanges"), "configurationChanges")) {
			Map<String,Object> change = WorldBuilderAdaptiveExporter.object(
				raw, "configurationChange");
			String key = WorldBuilderAdaptiveExporter.string(change, "key");
			if ("serverMapRelativePath".equals(key)) {
				serverAfter = WorldBuilderAdaptiveExporter.string(change, "afterValue");
			} else if ("clientMapRelativePath".equals(key)) {
				clientAfter = WorldBuilderAdaptiveExporter.string(change, "afterValue");
			}
		}
		String selected = "";
		for (String candidate : candidates) {
			String server = SERVER_PACKAGE_ROOT + "/" + candidate + "/package";
			String client = clientRoot + "/world-builder/packages/" + candidate
				+ "/package";
			if (!server.equals(serverAfter) || !client.equals(clientAfter)) continue;
			if (!selected.isEmpty() && !selected.equals(candidate)) throw problem(
				WorldBuilderErrorCodes.RECOVERY_REQUIRED, "configurationChanges",
				"Durable mutation plan has ambiguous package content-address lineage.",
				"Retain the project and restore its exact transaction evidence.");
			selected = candidate;
		}
		if (selected.isEmpty()) throw problem(
			WorldBuilderErrorCodes.RECOVERY_REQUIRED, "configurationChanges",
			"Durable mutation plan uses an unrecognized package content-address.",
			"Retain the project and restore its exact transaction evidence; arbitrary package paths are never adopted.");
		return selected;
	}

	static Plan relocateHistoricalInstalledPlan(Plan historical)
		throws IOException, WorldBuilderContractException {
		String legacyAddress = historical.export.packageValue.fingerprintSha256;
		String nativeAddress = historical.export.packageValue.nativeInventorySha256;
		if (legacyAddress.equals(nativeAddress)) return historical;
		String clientRoot = compiledClientRoot(historical.configuration);
		String legacyServer = SERVER_PACKAGE_ROOT + "/" + legacyAddress + "/package";
		String legacyClient = clientRoot + "/world-builder/packages/"
			+ legacyAddress + "/package";
		if (!legacyServer.equals(historical.serverPackageRelativePath)
			|| !legacyClient.equals(historical.clientPackageRelativePath)) return historical;
		String nativeServer = SERVER_PACKAGE_ROOT + "/" + nativeAddress + "/package";
		String nativeClient = clientRoot + "/world-builder/packages/"
			+ nativeAddress + "/package";

		Map<String,Object> configuration = configurationDocument(
			historical.configurationBytes, "historical installed configuration");
		configuration.put("serverMapRelativePath", nativeServer);
		configuration.put("clientMapRelativePath", nativeClient);
		byte[] configurationBytes = WorldBuilderJsonDocuments.pretty(configuration)
			.getBytes(StandardCharsets.UTF_8);
		FileState configurationAfter = FileState.present(
			configurationBytes.length, WorldBuilderHashes.sha256(configurationBytes));

		List<Action> actions = new ArrayList<Action>();
		for (Action action : historical.actions) {
			String destination = relocatePackagePath(action.destinationRelativePath,
				legacyServer, nativeServer, legacyClient, nativeClient);
			FileState after = action.after;
			byte[] generated = action.generatedContent;
			if (action.activation && action.destinationRelativePath.equals(
				historical.configuration.relativePath) && action.after.present) {
				after = configurationAfter;
				generated = configurationBytes;
			}
			actions.add(new Action(action.role, destination, action.before, after,
				action.contentRelativePath, action.backupRelativePath,
				action.activation, generated));
		}
		List<ConfigurationChange> changes = new ArrayList<ConfigurationChange>();
		for (ConfigurationChange change : historical.configurationChanges) {
			String after = relocatePackagePath(change.afterValue,
				legacyServer, nativeServer, legacyClient, nativeClient);
			changes.add(new ConfigurationChange(change.path, change.key,
				change.beforePresent, change.beforeValue, change.afterPresent, after));
		}
		String legacyServerRoot = fingerprintRoot(legacyServer);
		String legacyClientRoot = fingerprintRoot(legacyClient);
		String nativeServerRoot = fingerprintRoot(nativeServer);
		String nativeClientRoot = fingerprintRoot(nativeClient);
		List<String> directories = new ArrayList<String>();
		for (String directory : historical.directoriesToCreate) {
			directories.add(relocatePackagePath(directory,
				legacyServerRoot, nativeServerRoot, legacyClientRoot, nativeClientRoot));
		}
		long requiredSpace = 0L;
		for (Action action : actions) {
			requiredSpace = safeAdd(requiredSpace, action.before.size);
			requiredSpace = safeAdd(requiredSpace, action.after.size);
		}
		Map<String,Object> document = document(historical.transactionId(),
			historical.project, historical.export, historical.capability,
			historical.configuration, historical.targetLineage(), actions, changes,
			directories, requiredSpace);
		return new Plan(historical.targetRoot, historical.project, historical.export,
			historical.capability, historical.configuration, historical.profileId,
			nativeServer, nativeClient, configurationBytes, actions, changes,
			directories, document);
	}

	private static String relocatePackagePath(String value,
		String oldServer, String newServer, String oldClient, String newClient) {
		if (value.equals(oldServer) || value.startsWith(oldServer + "/")) {
			return newServer + value.substring(oldServer.length());
		}
		if (value.equals(oldClient) || value.startsWith(oldClient + "/")) {
			return newClient + value.substring(oldClient.length());
		}
		return value;
	}

	private static Map<String,Object> configurationDocument(byte[] bytes, String label)
		throws WorldBuilderContractException {
		try {
			return WorldBuilderJsonDocuments.readObject(bytes, label);
		} catch (WorldBuilderDiscoveryException malformed) {
			throw problem(WorldBuilderErrorCodes.RECOVERY_REQUIRED, label,
				"Saved transaction configuration JSON is malformed.",
				"Restore the exact complete transaction evidence.");
		}
	}

	private static void requireBeforeBackups(Plan plan)
		throws IOException, WorldBuilderContractException {
		for (Action action : plan.actions) {
			if (!action.before.present) continue;
			Path backup = WorldBuilderAdaptiveExporter.requireFile(
				plan.project.projectRoot, action.backupRelativePath,
				"adaptive transaction before-state backup");
			if (Files.size(backup) != action.before.size
				|| !action.before.sha256.equals(WorldBuilderHashes.sha256(backup))) {
				throw problem(WorldBuilderErrorCodes.RECOVERY_REQUIRED,
					action.backupRelativePath,
					"Adaptive transaction backup differs from its exact before state.",
					"Retain the project and restore the exact verified backup; do not force undo or recovery.");
			}
		}
	}

	private static void verifyUnchangedTargetEvidence(
		WorldBuilderAdaptiveProjectLifecycle.VerifiedProject project,
		Path target, String changedConfiguration)
		throws IOException, WorldBuilderContractException {
		Set<String> retirementPaths = legacyRetirementPaths(project);
		for (String key : new String[] {"originalFiles", "definitionRuntimeFiles"}) {
			for (Object raw : WorldBuilderAdaptiveExporter.array(
				project.snapshot.get(key), key)) {
				Map<String,Object> record = WorldBuilderAdaptiveExporter.object(raw, key);
				String sourcePath = WorldBuilderAdaptiveExporter.string(
					record, "relativePath");
				String prefix = "source/original/";
				if (!sourcePath.startsWith(prefix)) continue;
				String relative = sourcePath.substring(prefix.length());
				if (relative.equals(changedConfiguration)) continue;
				if (retirementPaths.contains(relative)) continue;
				boolean present = WorldBuilderAdaptiveExporter.bool(record, "present");
				Path live = safeDestination(target, relative);
				if (!present) {
					if (Files.exists(live, LinkOption.NOFOLLOW_LINKS)) throw problem(
						WorldBuilderErrorCodes.TARGET_DRIFT, relative,
						"Target evidence expected absent is now present.",
						"Keep the target offline and restore the exact imported target lineage.");
					continue;
				}
				Path file = safeExistingFile(target, relative, "unchanged target evidence");
				long size = WorldBuilderAdaptiveExporter.integer(record, "size");
				String hash = WorldBuilderAdaptiveExporter.string(record, "sha256");
				if (Files.size(file) != size || !hash.equals(WorldBuilderHashes.sha256(file))) {
					throw problem(WorldBuilderErrorCodes.TARGET_DRIFT, relative,
						"Target evidence changed after the recorded import.",
						"Do not force undo; restore the exact installed target first.");
				}
			}
		}
	}

	private static Set<String> legacyRetirementPaths(
		WorldBuilderAdaptiveProjectLifecycle.VerifiedProject project)
		throws IOException, WorldBuilderContractException {
		Set<String> result = new HashSet<String>();
		Path choicePath = WorldBuilderPortablePath.resolveContained(
			project.projectRoot, "source/migration/choice.json", OPERATION);
		if (!Files.exists(choicePath, LinkOption.NOFOLLOW_LINKS)) return result;
		Map<String,Object> choice;
		try {
			choice = WorldBuilderJsonDocuments.readObject(choicePath);
		} catch (WorldBuilderDiscoveryException malformed) {
			throw problem(WorldBuilderErrorCodes.SOURCE_CORRUPT,
				"source/migration/choice.json",
				"Immutable map-migration choice JSON is malformed.",
				"Restore the complete project from a trusted backup.");
		}
		WorldBuilderAdaptiveContracts.validateParsed(migrationChoiceKind(choice), choice);
		WorldBuilderAdaptiveExporter.requireFingerprint(
			choice, "migrationChoiceFingerprintSha256");
		if (!WorldBuilderAdaptiveExporter.bool(choice, "retirementRequested")) return result;
		Map<String,Object> terrain = WorldBuilderAdaptiveExporter.object(
			choice.get("legacyTerrain"), "legacyTerrain");
		for (String side : new String[] {"client", "server"}) {
			Map<String,Object> record = WorldBuilderAdaptiveExporter.object(
				terrain.get(side), "legacyTerrain." + side);
			result.add(WorldBuilderAdaptiveExporter.string(record, "relativePath"));
		}
		return result;
	}

	private static WorldBuilderAdaptiveContracts.Kind migrationChoiceKind(
		Map<String,Object> choice) throws WorldBuilderContractException {
		Object raw = choice.get("manifestType");
		if ("world-builder-map-migration-choice".equals(raw)) {
			return WorldBuilderAdaptiveContracts.Kind.MAP_MIGRATION_CHOICE;
		}
		if ("world-builder-packed-map-migration-choice".equals(raw)) {
			return WorldBuilderAdaptiveContracts.Kind.PACKED_MAP_MIGRATION_CHOICE;
		}
		throw problem(WorldBuilderErrorCodes.SOURCE_CORRUPT,
			"source/migration/choice.json",
			"Immutable map-migration choice has an unknown contract identity.",
			"Restore the complete project from a trusted backup.");
	}

	private static List<String> readCreatedDirectories(Path project,
		String transactionId, Map<String,Object> plan, List<Action> actions)
		throws IOException, WorldBuilderContractException {
		Path path = WorldBuilderPortablePath.resolveContained(project,
			"backups/" + transactionId + "/created-directories.json", OPERATION);
		WorldBuilderAdaptiveExporter.requireFile(project,
			"backups/" + transactionId + "/created-directories.json",
			"durable created-directory evidence");
		Map<String,Object> value;
		try {
			value = WorldBuilderJsonDocuments.readObject(path);
		} catch (WorldBuilderDiscoveryException malformed) {
			throw problem(WorldBuilderErrorCodes.RECOVERY_REQUIRED,
				"backups/" + transactionId + "/created-directories.json",
				"Durable created-directory evidence is malformed.",
				"Retain the complete transaction backup before undo or recovery.");
		}
		if (value.size() != 5 || WorldBuilderAdaptiveExporter.integer(
			value, "schemaVersion") != 1L
			|| !"world-builder-created-directories".equals(
				WorldBuilderAdaptiveExporter.string(value, "manifestType"))
			|| !transactionId.equals(WorldBuilderAdaptiveExporter.string(
				value, "transactionId"))
			|| !WorldBuilderAdaptiveExporter.string(plan,
				"planFingerprintSha256").equals(
					WorldBuilderAdaptiveExporter.string(value,
						"planFingerprintSha256"))) throw problem(
			WorldBuilderErrorCodes.RECOVERY_REQUIRED,
			"backups/" + transactionId + "/created-directories.json",
			"Durable created-directory evidence does not bind the exact plan.",
			"Retain the complete exact transaction backup; do not force undo.");
		List<String> expected = planCreatedDirectories(plan, actions);
		List<String> result = new ArrayList<String>();
		Set<String> collisions = new HashSet<String>();
		for (Object raw : WorldBuilderAdaptiveExporter.array(
			value.get("relativePaths"), "relativePaths")) {
			if (!(raw instanceof String)) throw problem(
				WorldBuilderErrorCodes.RECOVERY_REQUIRED, "created-directories.json",
				"Created-directory evidence contains a non-string path.",
				"Restore the exact transaction backup.");
			String relative = WorldBuilderPortablePath.require((String)raw, OPERATION);
			if (!collisions.add(WorldBuilderPortablePath.collisionKey(relative, OPERATION))) {
				throw problem(WorldBuilderErrorCodes.RECOVERY_REQUIRED, relative,
					"Created-directory evidence repeats a portable path.",
					"Restore the exact transaction backup.");
			}
			boolean ancestor = false;
			for (Action action : actions) {
				if (action.destinationRelativePath.startsWith(relative + "/")) {
					ancestor = true;
					break;
				}
			}
			if (!ancestor) throw problem(WorldBuilderErrorCodes.UNSAFE_PATH, relative,
				"Created-directory evidence is outside every compiled destination.",
				"Restore exact transaction evidence; no arbitrary directory is removable.");
			result.add(relative);
		}
		List<String> sorted = new ArrayList<String>(result);
		Collections.sort(sorted, new Comparator<String>() {
			@Override public int compare(String left, String right) {
				int depth = left.split("/").length - right.split("/").length;
				return depth == 0 ? left.compareTo(right) : depth;
			}
		});
		if (!result.equals(sorted)) throw problem(
			WorldBuilderErrorCodes.RECOVERY_REQUIRED, "created-directories.json",
			"Created-directory evidence is not canonical.",
			"Restore the exact transaction backup.");
		if (!result.equals(expected)) throw problem(
			WorldBuilderErrorCodes.RECOVERY_REQUIRED, "created-directories.json",
			"Created-directory evidence was added, removed, or reordered after planning.",
			"Restore the exact transaction evidence bound by the mutation plan and receipt.");
		return result;
	}

	private static List<String> planCreatedDirectories(Map<String,Object> plan,
		List<Action> actions) throws WorldBuilderContractException {
		List<String> result = new ArrayList<String>();
		Set<String> collisions = new HashSet<String>();
		for (Object raw : WorldBuilderAdaptiveExporter.array(
			plan.get("createdDirectories"), "createdDirectories")) {
			if (!(raw instanceof String)) throw problem(
				WorldBuilderErrorCodes.RECOVERY_REQUIRED, "createdDirectories",
				"Mutation-plan created-directory authority contains a non-string path.",
				"Restore the exact immutable mutation plan and receipt.");
			String relative = WorldBuilderPortablePath.require((String)raw, OPERATION);
			if (!collisions.add(WorldBuilderPortablePath.collisionKey(relative, OPERATION))) {
				throw problem(WorldBuilderErrorCodes.RECOVERY_REQUIRED, relative,
					"Mutation-plan created-directory authority repeats a portable path.",
					"Restore the exact immutable mutation plan and receipt.");
			}
			boolean ancestor = false;
			for (Action action : actions) {
				if (action.destinationRelativePath.startsWith(relative + "/")) {
					ancestor = true;
					break;
				}
			}
			if (!ancestor) throw problem(WorldBuilderErrorCodes.UNSAFE_PATH, relative,
				"Mutation-plan created-directory authority is not an action ancestor.",
				"Restore exact transaction evidence; arbitrary target paths are never removable.");
			result.add(relative);
		}
		List<String> sorted = new ArrayList<String>(result);
		Collections.sort(sorted, new Comparator<String>() {
			@Override public int compare(String left, String right) {
				int depth = left.split("/").length - right.split("/").length;
				return depth == 0 ? left.compareTo(right) : depth;
			}
		});
		if (!result.equals(sorted)) throw problem(
			WorldBuilderErrorCodes.RECOVERY_REQUIRED, "createdDirectories",
			"Mutation-plan created-directory authority is not canonical.",
			"Restore the exact immutable mutation plan and receipt.");
		return Collections.unmodifiableList(result);
	}

	private static List<String> plannedDirectories(Path target, List<Action> actions)
		throws IOException, WorldBuilderContractException {
		Set<String> missing = new HashSet<String>();
		for (Action action : actions) {
			String relative = action.destinationRelativePath;
			String[] segments = relative.split("/");
			StringBuilder current = new StringBuilder();
			for (int index = 0; index < segments.length - 1; index++) {
				if (index > 0) current.append('/');
				current.append(segments[index]);
				String directory = current.toString();
				Path path = WorldBuilderPortablePath.resolveContained(target, directory,
					OPERATION);
				if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
					missing.add(directory);
				} else if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
					|| Files.isSymbolicLink(path)) {
					throw problem(WorldBuilderErrorCodes.UNSAFE_PATH, relative,
						"Mutation destination has an unsafe directory ancestor.",
						"Restore one real contained target directory layout.");
				}
			}
		}
		List<String> values = new ArrayList<String>(missing);
		Collections.sort(values, new Comparator<String>() {
			@Override public int compare(String left, String right) {
				int depth = left.split("/").length - right.split("/").length;
				return depth == 0 ? left.compareTo(right) : depth;
			}
		});
		return values;
	}

	private static Map<String,Object> document(String transactionId,
		WorldBuilderAdaptiveProjectLifecycle.VerifiedProject project,
		WorldBuilderAdaptiveExporter.VerifiedExport export,
		WorldBuilderTargetCapability capability,
		WorldBuilderAdaptiveConfiguration configuration,
		String targetLineage, List<Action> actions,
		List<ConfigurationChange> changes, List<String> createdDirectories,
		long requiredSpace)
		throws WorldBuilderContractException {
		return document(transactionId, project, export, capability, configuration,
			targetLineage, actions, changes, createdDirectories, requiredSpace,
			configuration.sha256);
	}

	private static Map<String,Object> document(String transactionId,
		WorldBuilderAdaptiveProjectLifecycle.VerifiedProject project,
		WorldBuilderAdaptiveExporter.VerifiedExport export,
		WorldBuilderTargetCapability capability,
		WorldBuilderAdaptiveConfiguration configuration,
		String targetLineage, List<Action> actions,
		List<ConfigurationChange> changes, List<String> createdDirectories,
		long requiredSpace,
		String selectedConfigurationSha256)
		throws WorldBuilderContractException {
		Map<String,Object> value = new LinkedHashMap<String,Object>();
		value.put("schemaVersion", Long.valueOf(1L));
		value.put("manifestType", "world-builder-target-mutation-plan");
		value.put("transactionId", transactionId);
		value.put("projectId", project.projectId);
		value.put("exportFingerprintSha256", WorldBuilderAdaptiveExporter.string(
			export.manifest, "exportFingerprintSha256"));
		value.put("adapterId", capability.adapterId);
		value.put("capabilityId", capability.capabilityId);
		value.put("mutationProfileId", capability.mutationProfileId);
		value.put("targetLineageSha256", targetLineage);
		Map<String,Object> selected = new LinkedHashMap<String,Object>();
		selected.put("present", Boolean.TRUE);
		selected.put("role", configuration.configurationId);
		selected.put("relativePath", configuration.relativePath);
		selected.put("sha256", selectedConfigurationSha256);
		value.put("selectedConfiguration", selected);
		Map<String,Object> requirements = new LinkedHashMap<String,Object>();
		requirements.put("loaderId", capability.serverLoaderId);
		requirements.put("protocolId", capability.clientProtocolId);
		requirements.put("definitionCatalogId", capability.definitionCatalogId);
		requirements.put("clientBuildId", capability.clientBuildId);
		requirements.put("offlineEvidence",
			new ArrayList<String>(capability.offlineEvidence));
		requirements.put("requiredFreeSpaceBytes", Long.valueOf(requiredSpace));
		value.put("requirements", requirements);
		List<Object> actionValues = new ArrayList<Object>();
		for (int index = 0; index < actions.size(); index++) {
			actionValues.add(actions.get(index).toJson(index));
		}
		value.put("actions", actionValues);
		value.put("createdDirectories", new ArrayList<String>(createdDirectories));
		List<Object> changeValues = new ArrayList<Object>();
		for (int index = 0; index < changes.size(); index++) {
			changeValues.add(changes.get(index).toJson(index, false));
		}
		value.put("configurationChanges", changeValues);
		value.put("backupRootRelativePath", "backups/" + transactionId);
		value.put("receiptRelativePath", "receipts/" + transactionId + ".json");
		value.put("postWriteVerifications", verifications(actions, true));
		value.put("rollbackVerifications", verifications(actions, false));
		value.put("planFingerprintSha256", ZERO_HASH);
		WorldBuilderAdaptiveExporter.bindFingerprint(value, "planFingerprintSha256");
		WorldBuilderAdaptiveContracts.validateParsed(
			WorldBuilderAdaptiveContracts.Kind.MUTATION_PLAN, value);
		return value;
	}

	static Plan reverseForUndo(Plan installed, String transactionId)
		throws IOException, WorldBuilderContractException {
		List<Action> actions = new ArrayList<Action>();
		String prefix = "backups/" + transactionId + "/before/";
		byte[] restoredConfiguration = null;
		String selectedInstalledHash = "";
		for (Action original : installed.actions) {
			if (original.activation) continue;
			actions.add(new Action("undo-" + original.role,
				original.destinationRelativePath, original.after, original.before,
				original.before.present ? original.contentRelativePath : "",
				prefix + original.destinationRelativePath, false, null));
		}
		for (Action original : installed.actions) {
			if (!original.activation) continue;
			byte[] restored = null;
			if (original.before.present) {
				Path beforeBackup = WorldBuilderAdaptiveExporter.requireFile(
					installed.project.projectRoot, original.backupRelativePath,
					"installed transaction before-state backup");
				restored = Files.readAllBytes(beforeBackup);
				if (restored.length != original.before.size
					|| !original.before.sha256.equals(
						WorldBuilderHashes.sha256(restored))) throw problem(
					WorldBuilderErrorCodes.SOURCE_CORRUPT,
					original.backupRelativePath,
					"Installed transaction backup does not match the import before state.",
					"Restore the complete project transaction backup.");
			}
			if (isConfigurationActivation(original)) {
				restoredConfiguration = restored;
				selectedInstalledHash = original.after.sha256;
			}
			actions.add(new Action("undo-" + original.role,
				original.destinationRelativePath, original.after, original.before,
				original.before.present
					? isConfigurationActivation(original) ? TRANSACTION_CONTENT_CONFIG
						: "package/undo/" + pad(actions.size()) + ".bin"
					: "",
				original.after.present
					? prefix + original.destinationRelativePath : "", true,
				restored));
		}
		if (restoredConfiguration == null || selectedInstalledHash.isEmpty()) {
			throw problem(WorldBuilderErrorCodes.RECOVERY_REQUIRED, "mutation-plan",
				"Installed transaction has no compiled activation action.",
				"Retain the complete project and transaction evidence.");
		}
		List<ConfigurationChange> changes = new ArrayList<ConfigurationChange>();
		for (ConfigurationChange change : installed.configurationChanges) {
			changes.add(new ConfigurationChange(change.path, change.key,
				change.afterPresent, change.afterValue,
				change.beforePresent, change.beforeValue));
		}
		long requiredSpace = 0L;
		for (Action action : actions) {
			requiredSpace = safeAdd(requiredSpace, action.before.size);
			requiredSpace = safeAdd(requiredSpace, action.after.size);
		}
		Map<String,Object> generated = document(transactionId,
			installed.project, installed.export, installed.capability,
			installed.configuration, installed.targetLineage(), actions, changes,
			Collections.<String>emptyList(), requiredSpace, selectedInstalledHash);
		return new Plan(installed.targetRoot, installed.project, installed.export,
			installed.capability, installed.configuration, installed.profileId,
			installed.serverPackageRelativePath, installed.clientPackageRelativePath,
			restoredConfiguration, actions, changes,
			Collections.<String>emptyList(), generated);
	}

	static Plan reverseForRecovery(Plan failed, String transactionId)
		throws IOException, WorldBuilderContractException {
		List<Action> actions = new ArrayList<Action>();
		List<String> unknown = new ArrayList<String>();
		String prefix = "backups/" + transactionId + "/before/";
		String selectedHash = "";
		byte[] activationBytes = null;
		boolean activationIncluded = false;
		for (Action original : failed.actions) {
			boolean atBefore = stateMatches(failed.targetRoot,
				original.destinationRelativePath, original.before);
			boolean atAfter = stateMatches(failed.targetRoot,
				original.destinationRelativePath, original.after);
			if (!atBefore && !atAfter) {
				unknown.add(original.destinationRelativePath);
				continue;
			}
			if (isConfigurationActivation(original)) {
				selectedHash = atAfter ? original.after.sha256 : original.before.sha256;
			}
			if (atBefore) continue;
			byte[] content = null;
			String contentPath = "";
			if (original.before.present) {
				Path backup = WorldBuilderPortablePath.resolveContained(
					failed.project.projectRoot, original.backupRelativePath, OPERATION);
				if (!Files.isRegularFile(backup, LinkOption.NOFOLLOW_LINKS)
					|| Files.isSymbolicLink(backup)
					|| Files.size(backup) != original.before.size
					|| !original.before.sha256.equals(WorldBuilderHashes.sha256(backup))) {
					throw problem(WorldBuilderErrorCodes.RECOVERY_REQUIRED,
						original.backupRelativePath,
						"Original transaction backup is missing, unsafe, or corrupt.",
						"Retain the project and restore its exact backup before recovery.");
				}
				content = Files.readAllBytes(backup);
				contentPath = isConfigurationActivation(original)
					? TRANSACTION_CONTENT_CONFIG
					: "package/recovery/" + pad(actions.size()) + ".bin";
			}
			Action recovery = new Action("recovery-" + pad(actions.size()),
				original.destinationRelativePath, original.after, original.before,
				contentPath, original.after.present
					? prefix + original.destinationRelativePath : "",
				original.activation, content);
			actions.add(recovery);
			if (isConfigurationActivation(original)) {
				activationIncluded = true;
				activationBytes = content;
			}
		}
		if (!unknown.isEmpty()) throw problem(WorldBuilderErrorCodes.TARGET_DRIFT,
			unknown.get(0),
			"Recovery found target paths that match neither exact before nor exact after state: "
				+ joinPaths(unknown) + ".",
			"Keep the target offline and restore known transaction bytes; no force mode exists.");
		if (actions.isEmpty()) return null;
		if (selectedHash.isEmpty()) throw problem(
			WorldBuilderErrorCodes.RECOVERY_REQUIRED, "mutation-plan",
			"Failed transaction has no exact selected-configuration state.",
			"Retain the project and exact transaction evidence.");
		List<ConfigurationChange> changes = new ArrayList<ConfigurationChange>();
		if (activationIncluded) {
			for (ConfigurationChange change : failed.configurationChanges) {
				changes.add(new ConfigurationChange(change.path, change.key,
					change.afterPresent, change.afterValue,
					change.beforePresent, change.beforeValue));
			}
		}
		long requiredSpace = 0L;
		for (Action action : actions) {
			requiredSpace = safeAdd(requiredSpace, action.before.size);
			requiredSpace = safeAdd(requiredSpace, action.after.size);
		}
		Map<String,Object> generated = document(transactionId,
			failed.project, failed.export, failed.capability, failed.configuration,
			failed.targetLineage(), actions, changes,
			Collections.<String>emptyList(), requiredSpace, selectedHash);
		return new Plan(failed.targetRoot, failed.project, failed.export,
			failed.capability, failed.configuration, failed.profileId,
			failed.serverPackageRelativePath, failed.clientPackageRelativePath,
			activationBytes == null ? failed.configurationBytes : activationBytes,
			actions, changes, Collections.<String>emptyList(), generated);
	}

	private static boolean isConfigurationActivation(Action action) {
		return action.activation && action.role.endsWith("activation-configuration");
	}

	static void requireDurablePlanMatches(Plan plan)
		throws IOException, WorldBuilderContractException {
		Path durable = WorldBuilderPortablePath.resolveContained(
			plan.project.projectRoot,
			"backups/" + plan.transactionId() + "/mutation-plan.json", OPERATION);
		WorldBuilderAdaptiveExporter.requireFile(plan.project.projectRoot,
			"backups/" + plan.transactionId() + "/mutation-plan.json",
			"durable adaptive mutation plan");
		WorldBuilderAdaptiveContracts.Document stored =
			WorldBuilderAdaptiveContracts.read(
				WorldBuilderAdaptiveContracts.Kind.MUTATION_PLAN, durable);
		Map<String,Object> value;
		try {
			value = WorldBuilderJsonDocuments.readObject(durable);
		} catch (WorldBuilderDiscoveryException malformed) {
			throw problem(WorldBuilderErrorCodes.RECOVERY_REQUIRED,
				"backups/" + plan.transactionId() + "/mutation-plan.json",
				"Durable mutation plan is malformed.",
				"Restore the exact transaction evidence.");
		}
		WorldBuilderAdaptiveExporter.requireFingerprint(value, "planFingerprintSha256");
		if (!plan.canonicalSha256.equals(stored.canonicalSha256)) throw problem(
			WorldBuilderErrorCodes.RECOVERY_REQUIRED,
			"backups/" + plan.transactionId() + "/mutation-plan.json",
			"Durable mutation plan differs from the independently compiled plan.",
			"Restore exact transaction evidence; do not force recovery.");
		List<String> directories = readCreatedDirectories(plan.project.projectRoot,
			plan.transactionId(), plan.document, plan.actions);
		if (!directories.equals(plan.directoriesToCreate)) throw problem(
			WorldBuilderErrorCodes.RECOVERY_REQUIRED,
			"backups/" + plan.transactionId() + "/created-directories.json",
			"Durable directory evidence differs from the independently compiled plan.",
			"Restore exact transaction evidence; do not force recovery.");
		requireBeforeBackups(plan);
	}

	private static boolean stateMatches(Path target, String relative, FileState state)
		throws IOException, WorldBuilderContractException {
		Path path = safeDestination(target, relative);
		if (!state.present) return !Files.exists(path, LinkOption.NOFOLLOW_LINKS);
		if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return false;
		path = safeExistingFile(target, relative, "transaction state authority");
		return Files.size(path) == state.size
			&& state.sha256.equals(WorldBuilderHashes.sha256(path));
	}

	private static String joinPaths(List<String> values) {
		StringBuilder joined = new StringBuilder();
		for (int index = 0; index < values.size() && index < 32; index++) {
			if (index > 0) joined.append(", ");
			joined.append(values.get(index));
		}
		if (values.size() > 32) joined.append(", and ")
			.append(values.size() - 32).append(" more");
		return joined.toString();
	}

	private static List<Object> verifications(List<Action> actions, boolean after) {
		List<Object> values = new ArrayList<Object>();
		for (int index = 0; index < actions.size(); index++) {
			Action action = actions.get(index);
			Map<String,Object> value = new LinkedHashMap<String,Object>();
			value.put("verificationId", (after ? "post-" : "rollback-") + pad(index));
			value.put("relativePath", action.destinationRelativePath);
			FileState state = after ? action.after : action.before;
			value.put("expected", state.present ? state.sha256 : "absent");
			values.add(value);
		}
		return values;
	}

	private static String compiledClientRoot(
		WorldBuilderAdaptiveConfiguration configuration)
		throws WorldBuilderContractException {
		if (configuration.clientRuntimeRelativePath.startsWith("client/")) {
			return CLIENT_PACKAGE_ROOT.substring(0, "client".length());
		}
		if (configuration.clientRuntimeRelativePath.startsWith("Client_Base/")) {
			return LEGACY_CLIENT_PACKAGE_ROOT.substring(0, "Client_Base".length());
		}
		throw problem(WorldBuilderErrorCodes.UNSAFE_PATH,
			configuration.clientRuntimeRelativePath,
			"Compiled install profile cannot select a bounded client root.",
			"Use client/ or Client_Base/ evidence with the matching compiled adapter.");
	}

	private static void addConfigurationChange(List<ConfigurationChange> values,
		String path, String key, String before, String after) {
		if (!before.equals(after)) {
			values.add(new ConfigurationChange(path, key, true, before, true, after));
		}
	}

	private static void requireAbsentDestination(Path target, String relative)
		throws IOException, WorldBuilderContractException {
		Path destination = safeDestination(target, relative);
		if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
			throw problem(WorldBuilderErrorCodes.TARGET_DRIFT, relative,
				"Content-addressed install destination already exists.",
				"Do not overwrite it; undo the matching prior import or use a fresh export/target.");
		}
	}

	static void requireInstallRootsAbsent(Plan plan)
		throws IOException, WorldBuilderContractException {
		requireInstallRootsAbsent(plan.targetRoot, plan.serverPackageRelativePath,
			plan.clientPackageRelativePath);
	}

	private static void requireInstallRootsAbsent(Path target,
		String serverPackage, String clientPackage)
		throws IOException, WorldBuilderContractException {
		requireAbsentDestination(target, fingerprintRoot(serverPackage));
		requireAbsentDestination(target, fingerprintRoot(clientPackage));
	}

	static String fingerprintRoot(String packagePath)
		throws WorldBuilderContractException {
		String suffix = "/package";
		if (!packagePath.endsWith(suffix)) throw problem(
			WorldBuilderErrorCodes.CAPABILITY_MISMATCH, packagePath,
			"Compiled content-addressed package path has an unexpected shape.",
			"Use the exact supported adaptive install profile.");
		return packagePath.substring(0, packagePath.length() - suffix.length());
	}

	static Path safeDestination(Path target, String relative)
		throws IOException, WorldBuilderContractException {
		Path destination = WorldBuilderPortablePath.resolveContained(target, relative, OPERATION);
		Path cursor = target;
		String[] parts = relative.split("/");
		for (int index = 0; index < parts.length - 1; index++) {
			Path next = cursor.resolve(parts[index]);
			if (Files.exists(next, LinkOption.NOFOLLOW_LINKS)) {
				if (!Files.isDirectory(next, LinkOption.NOFOLLOW_LINKS)
					|| Files.isSymbolicLink(next)) throw problem(
					WorldBuilderErrorCodes.UNSAFE_PATH, relative,
					"Mutation destination has a linked or non-directory ancestor.",
					"Replace unsafe ancestors with real contained directories.");
				requireNoCaseAlias(cursor, parts[index], relative);
				cursor = next.toRealPath();
				if (!cursor.startsWith(target)) throw problem(
					WorldBuilderErrorCodes.UNSAFE_PATH, relative,
					"Mutation destination ancestor escaped the target root.",
					"Use a real contained target layout.");
			} else {
				requireNoCaseAlias(cursor, parts[index], relative);
				cursor = next;
			}
		}
		requireNoCaseAlias(cursor, parts[parts.length - 1], relative);
		return destination;
	}

	private static void requireNoCaseAlias(Path parent, String wanted, String relative)
		throws IOException, WorldBuilderContractException {
		if (!Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) return;
		try (java.nio.file.DirectoryStream<Path> entries = Files.newDirectoryStream(parent)) {
			for (Path entry : entries) {
				String existing = entry.getFileName().toString();
				if (existing.equalsIgnoreCase(wanted) && !existing.equals(wanted)) {
					throw problem(WorldBuilderErrorCodes.UNSAFE_PATH, relative,
						"Mutation destination case-collides with existing path " + existing + ".",
						"Use a target without portable path collisions.");
				}
			}
		}
	}

	static Path safeExistingFile(Path target, String relative, String label)
		throws IOException, WorldBuilderContractException {
		Path path = safeDestination(target, relative);
		if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
			|| Files.isSymbolicLink(path)) throw problem(
			WorldBuilderErrorCodes.UNSAFE_PATH, relative,
			label + " is missing, linked, or not a regular file.",
			"Restore the exact compatible target file.");
		Path real = path.toRealPath();
		if (!real.startsWith(target)) throw problem(
			WorldBuilderErrorCodes.UNSAFE_PATH, relative,
			label + " resolves outside the target.",
			"Use one real contained target file.");
		rejectHardLink(real, relative);
		return real;
	}

	private static void rejectHardLink(Path path, String relative)
		throws IOException, WorldBuilderContractException {
		try {
			Object links = Files.getAttribute(path, "unix:nlink", LinkOption.NOFOLLOW_LINKS);
			if (links instanceof Number && ((Number)links).longValue() > 1L) {
				throw problem(WorldBuilderErrorCodes.UNSAFE_PATH, relative,
					"Target file is hard-linked and rollback containment cannot be proven.",
					"Replace it with one distinct regular target file.");
			}
		} catch (UnsupportedOperationException ignored) {
			// No portable link-count view.
		} catch (IllegalArgumentException ignored) {
			// No portable link-count view.
		}
	}

	static Path requireTarget(Path requested)
		throws IOException, WorldBuilderContractException {
		if (requested == null) throw problem(WorldBuilderErrorCodes.NO_TARGET,
			"target-root", "Target root was not supplied.",
			"Place World Builder directly inside the compatible server root.");
		Path target = requested.toAbsolutePath().normalize();
		if (!Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)
			|| Files.isSymbolicLink(target)) throw problem(
			WorldBuilderErrorCodes.UNSAFE_PATH, "target-root",
			"Target root is missing, linked, or not a directory.",
			"Use the exact real compatible server root.");
		return target.toRealPath();
	}

	@SuppressWarnings("unchecked")
	private static Map<String,Object> deepCopy(Map<String,Object> source) {
		Map<String,Object> value = new LinkedHashMap<String,Object>();
		for (Map.Entry<String,Object> entry : source.entrySet()) {
			Object child = entry.getValue();
			if (child instanceof Map) child = deepCopy((Map<String,Object>)child);
			else if (child instanceof List) child = deepCopyList((List<Object>)child);
			value.put(entry.getKey(), child);
		}
		return value;
	}

	@SuppressWarnings("unchecked")
	private static List<Object> deepCopyList(List<Object> source) {
		List<Object> value = new ArrayList<Object>();
		for (Object child : source) {
			if (child instanceof Map) child = deepCopy((Map<String,Object>)child);
			else if (child instanceof List) child = deepCopyList((List<Object>)child);
			value.add(child);
		}
		return value;
	}

	private static String pad(int value) {
		return String.format(java.util.Locale.ROOT, "%04d", Integer.valueOf(value));
	}

	private static long safeAdd(long first, long second)
		throws WorldBuilderContractException {
		try {
			long value = Math.addExact(first, second);
			if (value > WorldBuilderContractLimits.MAX_INVENTORY_TOTAL_BYTES) {
				throw new ArithmeticException("bounded total");
			}
			return value;
		} catch (ArithmeticException overflow) {
			throw problem(WorldBuilderErrorCodes.CONTRACT_LIMIT_EXCEEDED, "actions",
				"Planned backup/content byte total exceeds the supported limit.",
				"Use a smaller complete package accepted by the adaptive contract.");
		}
	}

	private static WorldBuilderContractException problem(
		String code, String path, String message, String nextStep) {
		return new WorldBuilderContractException(code, OPERATION, path, false,
			message, nextStep);
	}

	static final class FileState {
		final boolean present;
		final long size;
		final String sha256;

		FileState(boolean present, long size, String sha256) {
			this.present = present;
			this.size = size;
			this.sha256 = sha256;
		}

		static FileState absent() {
			return new FileState(false, 0L, "");
		}

		static FileState present(long size, String sha256) {
			return new FileState(true, size, sha256);
		}

		Map<String,Object> toJson() {
			Map<String,Object> value = new LinkedHashMap<String,Object>();
			value.put("present", Boolean.valueOf(present));
			value.put("size", Long.valueOf(size));
			value.put("sha256", sha256);
			return value;
		}
	}

	static final class Action {
		final String role;
		final String destinationRelativePath;
		final FileState before;
		final FileState after;
		final String contentRelativePath;
		final String backupRelativePath;
		final boolean activation;
		final byte[] generatedContent;

		Action(String role, String destinationRelativePath, FileState before,
			FileState after, String contentRelativePath, String backupRelativePath,
			boolean activation, byte[] generatedContent) {
			this.role = role;
			this.destinationRelativePath = destinationRelativePath;
			this.before = before;
			this.after = after;
			this.contentRelativePath = contentRelativePath;
			this.backupRelativePath = backupRelativePath;
			this.activation = activation;
			this.generatedContent = generatedContent;
		}

		static Action install(String role, String destination,
			String content, long size, String sha256) {
			return new Action(role, destination, FileState.absent(),
				FileState.present(size, sha256), content, "", false, null);
		}

		Map<String,Object> toJson(int sequence) {
			Map<String,Object> value = new LinkedHashMap<String,Object>();
			value.put("sequence", Long.valueOf(sequence));
			value.put("role", role);
			value.put("destinationRelativePath", destinationRelativePath);
			value.put("before", before.toJson());
			value.put("after", after.toJson());
			value.put("contentRelativePath", contentRelativePath);
			value.put("backupRelativePath", backupRelativePath);
			value.put("activation", Boolean.valueOf(activation));
			return value;
		}
	}

	static final class ConfigurationChange {
		final String path;
		final String key;
		final boolean beforePresent;
		final String beforeValue;
		final boolean afterPresent;
		final String afterValue;

		ConfigurationChange(String path, String key, boolean beforePresent,
			String beforeValue, boolean afterPresent, String afterValue) {
			this.path = path;
			this.key = key;
			this.beforePresent = beforePresent;
			this.beforeValue = beforeValue;
			this.afterPresent = afterPresent;
			this.afterValue = afterValue;
		}

		Map<String,Object> toJson(int sequence, boolean receipt) {
			Map<String,Object> value = new LinkedHashMap<String,Object>();
			value.put("sequence", Long.valueOf(sequence));
			value.put("configurationRelativePath", path);
			value.put("key", key);
			value.put("beforePresent", Boolean.valueOf(beforePresent));
			value.put("beforeValue", beforeValue);
			value.put("afterPresent", Boolean.valueOf(afterPresent));
			value.put("afterValue", afterValue);
			if (receipt) {
				value.put("afterVerified", Boolean.FALSE);
				value.put("rollbackVerified", Boolean.FALSE);
			}
			return value;
		}
	}

	static final class Plan {
		final Path targetRoot;
		final WorldBuilderAdaptiveProjectLifecycle.VerifiedProject project;
		final WorldBuilderAdaptiveExporter.VerifiedExport export;
		final WorldBuilderTargetCapability capability;
		final WorldBuilderAdaptiveConfiguration configuration;
		final String profileId;
		final String serverPackageRelativePath;
		final String clientPackageRelativePath;
		final byte[] configurationBytes;
		final List<Action> actions;
		final List<ConfigurationChange> configurationChanges;
		final List<String> directoriesToCreate;
		final Map<String,Object> document;
		final String canonicalSha256;

		Plan(Path targetRoot,
			WorldBuilderAdaptiveProjectLifecycle.VerifiedProject project,
			WorldBuilderAdaptiveExporter.VerifiedExport export,
			WorldBuilderTargetCapability capability,
			WorldBuilderAdaptiveConfiguration configuration,
			String profileId, String serverPackageRelativePath,
			String clientPackageRelativePath, byte[] configurationBytes,
			List<Action> actions, List<ConfigurationChange> configurationChanges,
			List<String> directoriesToCreate,
			Map<String,Object> document) throws WorldBuilderContractException {
			this.targetRoot = targetRoot;
			this.project = project;
			this.export = export;
			this.capability = capability;
			this.configuration = configuration;
			this.profileId = profileId;
			this.serverPackageRelativePath = serverPackageRelativePath;
			this.clientPackageRelativePath = clientPackageRelativePath;
			this.configurationBytes = configurationBytes.clone();
			this.actions = Collections.unmodifiableList(new ArrayList<Action>(actions));
			this.configurationChanges = Collections.unmodifiableList(
				new ArrayList<ConfigurationChange>(configurationChanges));
			this.directoriesToCreate = Collections.unmodifiableList(
				new ArrayList<String>(directoriesToCreate));
			this.document = document;
			if (!this.directoriesToCreate.equals(
				planCreatedDirectories(document, actions))) throw problem(
				WorldBuilderErrorCodes.RECOVERY_REQUIRED, "createdDirectories",
				"In-memory directory authority differs from the immutable mutation plan.",
				"Rebuild the transaction from exact validated evidence.");
			this.canonicalSha256 = WorldBuilderAdaptiveContracts.validateParsed(
				WorldBuilderAdaptiveContracts.Kind.MUTATION_PLAN, document).canonicalSha256;
		}

		String transactionId() throws WorldBuilderContractException {
			return WorldBuilderAdaptiveExporter.string(document, "transactionId");
		}

		String exportFingerprint() throws WorldBuilderContractException {
			return WorldBuilderAdaptiveExporter.string(
				document, "exportFingerprintSha256");
		}

		String targetLineage() throws WorldBuilderContractException {
			return WorldBuilderAdaptiveExporter.string(document, "targetLineageSha256");
		}

		String toJson() {
			return WorldBuilderJsonDocuments.pretty(document);
		}

		String humanSummary() {
			int retiredLegacyFiles = 0;
			for (Action action : actions) {
				if (action.role.startsWith("retire-legacy-landscape-")
					&& action.before.present && !action.after.present) {
					retiredLegacyFiles++;
				}
			}
			StringBuilder value = new StringBuilder(4096);
			value.append("Import preview (no target files changed)\n")
				.append("Transaction: ").append(document.get("transactionId")).append('\n')
				.append("Project: ").append(project.projectId).append('\n')
				.append("Adapter/profile: ").append(capability.adapterId).append(" / ")
				.append(profileId).append('\n')
				.append("Server package: ").append(serverPackageRelativePath).append('\n')
				.append("Client package: ").append(clientPackageRelativePath).append('\n')
				.append("Activation configuration: ")
				.append(configuration.relativePath).append('\n')
				.append("Affected files: ").append(actions.size()).append('\n');
			if (retiredLegacyFiles > 0) {
				value.append("Legacy Custom_Landscape retirement: ")
					.append(retiredLegacyFiles)
					.append(" exact files (backed up; Undo restores them)\n");
			}
			value
				.append("Backup: projects/").append(project.projectId).append("/backups/")
				.append(document.get("transactionId")).append('\n')
				.append("Receipt: projects/").append(project.projectId).append("/receipts/")
				.append(document.get("transactionId")).append(".json\n")
				.append("Confirmation required: IMPORT\n");
			return value.toString();
		}
	}
}
