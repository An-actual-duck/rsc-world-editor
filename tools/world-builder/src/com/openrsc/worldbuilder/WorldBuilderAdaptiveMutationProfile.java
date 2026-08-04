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
		Path target = requireTarget(targetRoot);
		Map<String,Object> projectTarget = WorldBuilderAdaptiveExporter.object(
			project.manifest.get("target"), "target");
		Map<String,Object> selectedReference = WorldBuilderAdaptiveExporter.object(
			project.snapshot.get("selectedConfiguration"), "selectedConfiguration");
		String selectedRole = WorldBuilderAdaptiveExporter.string(
			selectedReference, "role");

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
		selected.put("sha256", configuration.sha256);
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
