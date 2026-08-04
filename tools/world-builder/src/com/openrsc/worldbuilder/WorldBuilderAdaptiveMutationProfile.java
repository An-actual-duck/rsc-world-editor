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
			new WorldBuilderAdaptiveDiscovery().discover(target, selectedRole);
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
		String packageFingerprint = export.packageValue.fingerprintSha256;
		String serverPackage = SERVER_PACKAGE_ROOT + "/" + packageFingerprint
			+ "/package";
		String clientPackage = clientRoot + "/world-builder/packages/"
			+ packageFingerprint + "/package";
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
		Map<String,Object> document = document(transactionId, project, export,
			capability, configuration, expectedLineage, actions, changes, requiredSpace);
		List<String> directoriesToCreate = plannedDirectories(target, actions);
		return new Plan(target, project, export, capability, configuration,
			profile, serverPackage, clientPackage, configurationBytes,
			actions, changes, directoriesToCreate, document);
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

		String clientRoot = compiledClientRoot(configuration);
		String packageFingerprint = export.packageValue.fingerprintSha256;
		String serverPackage = SERVER_PACKAGE_ROOT + "/" + packageFingerprint
			+ "/package";
		String clientPackage = clientRoot + "/world-builder/packages/"
			+ packageFingerprint + "/package";
		Map<String,Object> originalConfiguration = original.readObject(configurationPath);
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
			actions.add(Action.install("server-package-" + pad(actions.size()),
				serverPackage + "/" + inside, file.relativePath, file.size, file.sha256));
			actions.add(Action.install("client-package-" + pad(actions.size()),
				clientPackage + "/" + inside, file.relativePath, file.size, file.sha256));
		}
		FileState configurationBefore = FileState.present(
			originalConfigurationState.size, originalConfigurationState.sha256);
		actions.add(new Action("activation-configuration", configurationPath,
			configurationBefore,
			FileState.present(configurationBytes.length, configurationAfterHash),
			TRANSACTION_CONTENT_CONFIG,
			"backups/" + transactionId + "/before/" + configurationPath, true,
			configurationBytes));

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
		String lineage = WorldBuilderAdaptiveExporter.string(
			projectTarget, "targetFingerprintSha256");
		Map<String,Object> generated = document(transactionId, project, export,
			capability, configuration, lineage, actions, changes, requiredSpace);
		List<String> directories = readCreatedDirectories(
			project.projectRoot, transactionId, generated, actions);
		Plan plan = new Plan(target, project, export, capability, configuration,
			profile, serverPackage, clientPackage, configurationBytes,
			actions, changes, directories, generated);

		Path durablePlan = WorldBuilderPortablePath.resolveContained(
			project.projectRoot, "backups/" + transactionId + "/mutation-plan.json",
			OPERATION);
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
		if (!plan.canonicalSha256.equals(stored.canonicalSha256)) throw problem(
			WorldBuilderErrorCodes.RECOVERY_REQUIRED,
			"backups/" + transactionId + "/mutation-plan.json",
			"Durable mutation plan does not match independently compiled project/export paths.",
			"Keep the target offline and restore exact transaction evidence; do not force undo.");
		requireBeforeBackups(plan);
		return plan;
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
		for (String key : new String[] {"originalFiles", "definitionRuntimeFiles"}) {
			for (Object raw : WorldBuilderAdaptiveExporter.array(
				project.snapshot.get(key), key)) {
				Map<String,Object> record = WorldBuilderAdaptiveExporter.object(raw, key);
				String sourcePath = WorldBuilderAdaptiveExporter.string(
					record, "relativePath");
				String prefix = "source/original/";
				if (!sourcePath.startsWith(prefix)) throw problem(
					WorldBuilderErrorCodes.SOURCE_CORRUPT, sourcePath,
					"Target-derived immutable evidence escaped source/original.",
					"Restore the complete project from a trusted backup.");
				String relative = sourcePath.substring(prefix.length());
				if (relative.equals(changedConfiguration)) continue;
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

	private static List<String> readCreatedDirectories(Path project,
		String transactionId, Map<String,Object> plan, List<Action> actions)
		throws IOException, WorldBuilderContractException {
		Path path = WorldBuilderPortablePath.resolveContained(project,
			"backups/" + transactionId + "/created-directories.json", OPERATION);
		if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
			|| Files.isSymbolicLink(path)) throw problem(
			WorldBuilderErrorCodes.RECOVERY_REQUIRED,
			"backups/" + transactionId + "/created-directories.json",
			"Durable created-directory evidence is missing or unsafe.",
			"Retain the complete transaction backup before undo or recovery.");
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
		return result;
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
		List<ConfigurationChange> changes, long requiredSpace)
		throws WorldBuilderContractException {
		return document(transactionId, project, export, capability, configuration,
			targetLineage, actions, changes, requiredSpace, configuration.sha256);
	}

	private static Map<String,Object> document(String transactionId,
		WorldBuilderAdaptiveProjectLifecycle.VerifiedProject project,
		WorldBuilderAdaptiveExporter.VerifiedExport export,
		WorldBuilderTargetCapability capability,
		WorldBuilderAdaptiveConfiguration configuration,
		String targetLineage, List<Action> actions,
		List<ConfigurationChange> changes, long requiredSpace,
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
			Path originalConfiguration = safeExistingFile(
				WorldBuilderAdaptiveExporter.requireDirectory(
					installed.project.projectRoot, "source/original",
					"immutable original evidence"),
				original.destinationRelativePath, "immutable original configuration");
			restoredConfiguration = Files.readAllBytes(originalConfiguration);
			if (restoredConfiguration.length != original.before.size
				|| !original.before.sha256.equals(
					WorldBuilderHashes.sha256(restoredConfiguration))) throw problem(
				WorldBuilderErrorCodes.SOURCE_CORRUPT,
				original.destinationRelativePath,
				"Immutable original configuration does not match the import before state.",
				"Restore the complete project from a trusted backup.");
			selectedInstalledHash = original.after.sha256;
			actions.add(new Action("undo-" + original.role,
				original.destinationRelativePath, original.after, original.before,
				TRANSACTION_CONTENT_CONFIG,
				prefix + original.destinationRelativePath, true,
				restoredConfiguration));
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
			requiredSpace, selectedInstalledHash);
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
			if (original.activation) {
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
				contentPath = original.activation ? TRANSACTION_CONTENT_CONFIG
					: "package/recovery/" + pad(actions.size()) + ".bin";
			}
			Action recovery = new Action("recovery-" + pad(actions.size()),
				original.destinationRelativePath, original.after, original.before,
				contentPath, original.after.present
					? prefix + original.destinationRelativePath : "",
				original.activation, content);
			actions.add(recovery);
			if (original.activation) {
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
			failed.targetLineage(), actions, changes, requiredSpace, selectedHash);
		return new Plan(failed.targetRoot, failed.project, failed.export,
			failed.capability, failed.configuration, failed.profileId,
			failed.serverPackageRelativePath, failed.clientPackageRelativePath,
			activationBytes == null ? failed.configurationBytes : activationBytes,
			actions, changes, Collections.<String>emptyList(), generated);
	}

	static void requireDurablePlanMatches(Plan plan)
		throws IOException, WorldBuilderContractException {
		Path durable = WorldBuilderPortablePath.resolveContained(
			plan.project.projectRoot,
			"backups/" + plan.transactionId() + "/mutation-plan.json", OPERATION);
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
		return Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
			&& !Files.isSymbolicLink(path) && Files.size(path) == state.size
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
				.append("Affected files: ").append(actions.size()).append('\n')
				.append("Backup: projects/").append(project.projectId).append("/backups/")
				.append(document.get("transactionId")).append('\n')
				.append("Receipt: projects/").append(project.projectId).append("/receipts/")
				.append(document.get("transactionId")).append(".json\n")
				.append("Confirmation required: IMPORT\n");
			return value.toString();
		}
	}
}
