package com.openrsc.worldbuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Compiles the trusted project runtime into bounded target-install actions. */
final class WorldBuilderRuntimeCompatibility {
	static final String CAPABILITY_DESTINATION =
		"server/conf/world-builder/installed-runtime-capability-v2.json";
	static final String LEGACY_CAPABILITY_DESTINATION =
		"server/conf/world-builder/installed-runtime-capability-v1.json";
	static final String CAPABILITY_SOURCE =
		"working/runtime/server/conf/world-builder/installed-runtime-capability-v2.json";
	static final String CONFIGURATION_DESTINATION = "server/myworld.conf";
	static final String CLIENT_PROFILE_NAME =
		"world-builder-configs/installed-client.json";
	private static final String SERVER_DESTINATION = "server/core.jar";
	private static final String SERVER_SOURCE =
		"working/runtime/server/world-builder-install/core.jar";
	private static final String CLIENT_SOURCE =
		"working/runtime/client/world-builder-install/Open_RSC_Client.jar";

	private WorldBuilderRuntimeCompatibility() {
	}

	static Upgrade inspect(
		WorldBuilderAdaptiveProjectLifecycle.VerifiedProject project,
		Path target, WorldBuilderAdaptiveConfiguration configuration,
		WorldBuilderTargetCapability targetCapability,
		WorldBuilderGenericLayeredPackage packageValue)
		throws IOException, WorldBuilderContractException {
		String clientDestination = compiledClientRoot(configuration)
			+ "/Open_RSC_Client.jar";
		Path serverTarget = WorldBuilderAdaptiveMutationProfile.safeDestination(
			target, SERVER_DESTINATION);
		Path clientTarget = WorldBuilderAdaptiveMutationProfile.safeDestination(
			target, clientDestination);
		boolean serverPresent = Files.isRegularFile(
			serverTarget, LinkOption.NOFOLLOW_LINKS);
		boolean clientPresent = Files.isRegularFile(
			clientTarget, LinkOption.NOFOLLOW_LINKS);
		if (!serverPresent && !clientPresent) {
			return new Upgrade(targetCapability.encodingVersions,
				Collections.<WorldBuilderAdaptiveMutationProfile.Action>emptyList(), false);
		}
		if (!serverPresent || !clientPresent) throw problem(
			serverPresent ? clientDestination : SERVER_DESTINATION,
			"Automatic runtime compatibility installation found only one target runtime archive.",
			"Restore the complete server and client runtime pair, then retry Import.");

		List<Integer> preservedEncodingVersions =
			preservedInstalledV1(target);
		if (preservedEncodingVersions != null) {
			List<WorldBuilderAdaptiveMutationProfile.Action> preservedActions =
				new ArrayList<WorldBuilderAdaptiveMutationProfile.Action>();
			appendServerConfiguration(target, packageValue, preservedActions);
			return new Upgrade(preservedEncodingVersions, preservedActions, false);
		}

		Path capabilitySource = WorldBuilderAdaptiveExporter.requireFile(
			project.projectRoot, CAPABILITY_SOURCE,
			"project runtime compatibility capability");
		Map<String,Object> capability;
		try {
			capability = WorldBuilderJsonDocuments.readObject(capabilitySource);
		} catch (WorldBuilderDiscoveryException invalid) {
			throw problem(CAPABILITY_SOURCE,
				"Project runtime compatibility capability is malformed.",
				"Restore the exact verified project runtime.");
		}
		List<Integer> encodingVersions = integerList(
			capability.get("encodingVersions"), CAPABILITY_SOURCE);
		if (!"world-builder-installed-runtime-capability-v2".equals(
				WorldBuilderAdaptiveExporter.string(capability, "capabilityId"))
			|| !"world-builder-installed".equals(
				WorldBuilderAdaptiveExporter.string(capability, "profileId"))
			|| !"world-builder-installed-client-profile-v1".equals(
				WorldBuilderAdaptiveExporter.string(
					capability, "clientBootstrapId"))) {
			throw problem(CAPABILITY_SOURCE,
				"Project runtime compatibility identity is unsupported.",
				"Restore the exact verified project runtime.");
		}

		List<WorldBuilderAdaptiveMutationProfile.Action> actions =
			new ArrayList<WorldBuilderAdaptiveMutationProfile.Action>();
		appendReplacement(project, target, "runtime-compatibility-server",
			SERVER_DESTINATION, SERVER_SOURCE, transactionContent("server", ".jar"),
			actions);
		appendReplacement(project, target, "runtime-compatibility-client",
			clientDestination, CLIENT_SOURCE, transactionContent("client", ".jar"),
			actions);
		appendReplacement(project, target, "runtime-compatibility-capability",
			CAPABILITY_DESTINATION, CAPABILITY_SOURCE,
			transactionContent("capability", ".json"), actions);
		appendServerConfiguration(target, packageValue, actions);
		appendClientProfile(target, clientDestination, targetCapability,
			packageValue, actions);
		return new Upgrade(encodingVersions, actions, true);
	}

	private static List<Integer> preservedInstalledV1(Path target)
		throws IOException, WorldBuilderContractException {
		Path path = WorldBuilderAdaptiveMutationProfile.safeDestination(
			target, LEGACY_CAPABILITY_DESTINATION);
		if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return null;
		if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
			|| Files.isSymbolicLink(path)) {
			throw problem(LEGACY_CAPABILITY_DESTINATION,
				"Installed v1 runtime capability is not a safe regular file.",
				"Restore the exact prior World Builder runtime installation.");
		}
		Map<String,Object> capability;
		try {
			capability = WorldBuilderJsonDocuments.readObject(path);
		} catch (WorldBuilderDiscoveryException invalid) {
			throw problem(LEGACY_CAPABILITY_DESTINATION,
				"Installed v1 runtime capability is malformed.",
				"Restore the exact prior World Builder runtime installation.");
		}
		Map<String,Object> activation = WorldBuilderAdaptiveExporter.object(
			capability.get("activation"), "activation");
		List<Integer> encodings = integerList(
			capability.get("encodingVersions"), LEGACY_CAPABILITY_DESTINATION);
		if (WorldBuilderAdaptiveExporter.integer(capability, "schemaVersion") != 1L
			|| !"world-builder-installed-runtime-capability".equals(
				WorldBuilderAdaptiveExporter.string(capability, "manifestType"))
			|| !"world-builder-installed-runtime-capability-v1".equals(
				WorldBuilderAdaptiveExporter.string(capability, "capabilityId"))
			|| !"world-builder-installed".equals(
				WorldBuilderAdaptiveExporter.string(capability, "profileId"))
			|| !"generic-signed-layered-loader-v6-project-content-bundle-v3".equals(
				WorldBuilderAdaptiveExporter.string(capability, "loaderId"))
			|| !"world-builder-native-layered-protocol-v2-u16-elevation".equals(
				WorldBuilderAdaptiveExporter.string(capability, "protocolId"))
			|| !"layered-world-package-v1".equals(
				WorldBuilderAdaptiveExporter.string(capability, "packageSchemaId"))
			|| !"signed-layered-v1".equals(
				WorldBuilderAdaptiveExporter.string(capability, "coordinateModel"))
			|| !Arrays.asList(1, 2, 3, 4).equals(encodings)
			|| WorldBuilderAdaptiveExporter.bool(activation, "builderOnly")
			|| !WorldBuilderAdaptiveExporter.bool(
				activation, "replacesLegacyTerrain")
			|| !WorldBuilderAdaptiveExporter.bool(
				activation, "replacesLegacyPlacements")) {
			throw problem(LEGACY_CAPABILITY_DESTINATION,
				"Installed v1 runtime capability does not match the preservable contract.",
				"Restore the exact prior World Builder runtime installation.");
		}
		return encodings;
	}

	static void requireArchiveFreeClientBootstrap(
		WorldBuilderAdaptiveProjectLifecycle.VerifiedProject project)
		throws IOException, WorldBuilderContractException {
		Path capabilitySource = WorldBuilderAdaptiveExporter.requireFile(
			project.projectRoot, CAPABILITY_SOURCE,
			"project archive-free client capability");
		Map<String,Object> capability;
		try {
			capability = WorldBuilderJsonDocuments.readObject(capabilitySource);
		} catch (WorldBuilderDiscoveryException invalid) {
			throw problem(CAPABILITY_SOURCE,
				"Project runtime compatibility capability is malformed.",
				"Restore the exact verified project runtime.");
		}
		if (!"world-builder-installed-runtime-capability-v2".equals(
				WorldBuilderAdaptiveExporter.string(capability, "capabilityId"))
			|| !"world-builder-installed-client-profile-v1".equals(
				WorldBuilderAdaptiveExporter.string(capability, "clientBootstrapId"))) {
			throw problem(CAPABILITY_SOURCE,
				"Project runtime does not prove archive-free installed client startup.",
				"Keep both Custom_Landscape files until the matching installed client bootstrap is available.");
		}
	}

	private static void appendClientProfile(
		Path target, String clientRuntimeDestination,
		WorldBuilderTargetCapability targetCapability,
		WorldBuilderGenericLayeredPackage packageValue,
		List<WorldBuilderAdaptiveMutationProfile.Action> actions)
		throws IOException, WorldBuilderContractException {
		String clientRoot = clientRuntimeDestination.substring(
			0, clientRuntimeDestination.indexOf('/'));
		String address = WorldBuilderAdaptiveMutationProfile.PACKED_PROFILE.equals(
			targetCapability.mutationProfileId)
			? packageValue.fingerprintSha256 : packageValue.nativeInventorySha256;
		Map<String,Object> profile = new LinkedHashMap<String,Object>();
		profile.put("schemaVersion", Long.valueOf(1L));
		profile.put("manifestType", "world-builder-installed-client-profile");
		profile.put("active", Boolean.TRUE);
		profile.put("packageId", packageValue.packageId);
		profile.put("packageVersion", packageValue.packageVersion);
		profile.put("packageFingerprintSha256", packageValue.fingerprintSha256);
		profile.put("manifestSha256", packageValue.manifestSha256);
		profile.put("packageRelativePath",
			"world-builder/packages/" + address + "/package");
		byte[] generated = WorldBuilderJsonDocuments.pretty(profile)
			.getBytes(StandardCharsets.UTF_8);
		String destination = clientRoot + "/" + CLIENT_PROFILE_NAME;
		Path destinationPath = WorldBuilderAdaptiveMutationProfile.safeDestination(
			target, destination);
		WorldBuilderAdaptiveMutationProfile.FileState before;
		if (Files.isRegularFile(destinationPath, LinkOption.NOFOLLOW_LINKS)) {
			before = WorldBuilderAdaptiveMutationProfile.FileState.present(
				Files.size(destinationPath), WorldBuilderHashes.sha256(destinationPath));
		} else if (Files.exists(destinationPath, LinkOption.NOFOLLOW_LINKS)) {
			throw problem(destination,
				"Installed client profile destination is not a regular file.",
				"Restore the target client layout and retry Import.");
		} else {
			before = WorldBuilderAdaptiveMutationProfile.FileState.absent();
		}
		WorldBuilderAdaptiveMutationProfile.FileState after =
			WorldBuilderAdaptiveMutationProfile.FileState.present(
				generated.length, WorldBuilderHashes.sha256(generated));
		if (before.present && before.size == after.size
			&& before.sha256.equals(after.sha256)) return;
		actions.add(new WorldBuilderAdaptiveMutationProfile.Action(
			"runtime-compatibility-client-profile", destination, before, after,
			transactionContent("client-profile", ".json"),
			before.present ? "backups/{transaction}/before/" + destination : "",
			true, generated));
	}

	private static void appendServerConfiguration(
		Path target, WorldBuilderGenericLayeredPackage packageValue,
		List<WorldBuilderAdaptiveMutationProfile.Action> actions)
		throws IOException, WorldBuilderContractException {
		Path destination = WorldBuilderAdaptiveMutationProfile.safeExistingFile(
			target, CONFIGURATION_DESTINATION, "target server launch configuration");
		LinkedHashMap<String,String> overrides = new LinkedHashMap<String,String>();
		overrides.put("want_sync_scene_baseline", "true");
		overrides.put("want_layered_player_location_authority", "true");
		overrides.put("want_layered_spatial_runtime_authority", "true");
		overrides.put("want_layered_protocol_client_authority", "true");
		overrides.put("want_layered_synthetic_deep_fixture", "false");
		overrides.put("want_layered_native_terrain_package", "true");
		overrides.put("want_layered_native_terrain_residency", "true");
		overrides.put("want_layered_native_terrain_readiness", "true");
		overrides.put("want_layered_native_terrain_prediction", "true");
		overrides.put("want_layered_native_terrain_symmetric_residency", "true");
		overrides.put("want_layered_native_terrain_atomic_activation", "true");
		overrides.put("layered_native_terrain_package_path",
			"world-builder/packages/" + packageValue.fingerprintSha256 + "/package");
		overrides.put("layered_native_terrain_manifest_sha256",
			packageValue.manifestSha256);
		overrides.put("layered_native_world_runtime_profile",
			"world-builder-installed");
		byte[] beforeBytes = Files.readAllBytes(destination);
		List<String> rendered;
		try {
			rendered = WorldBuilderConfigWriter.render(
				Files.readAllLines(destination, StandardCharsets.UTF_8), overrides,
				"# Activated by World Builder installed-map import");
		} catch (WorldBuilderDiscoveryException malformed) {
			throw problem(CONFIGURATION_DESTINATION,
				"Target server launch configuration cannot be patched safely: "
					+ malformed.getMessage(),
				"Remove duplicate layered runtime keys and retry Import.");
		}
		byte[] afterBytes = (String.join("\n", rendered) + "\n")
			.getBytes(StandardCharsets.UTF_8);
		WorldBuilderAdaptiveMutationProfile.FileState before =
			WorldBuilderAdaptiveMutationProfile.FileState.present(
				beforeBytes.length, WorldBuilderHashes.sha256(destination));
		WorldBuilderAdaptiveMutationProfile.FileState after =
			WorldBuilderAdaptiveMutationProfile.FileState.present(
				afterBytes.length, WorldBuilderHashes.sha256(afterBytes));
		if (before.size == after.size && before.sha256.equals(after.sha256)) return;
		actions.add(new WorldBuilderAdaptiveMutationProfile.Action(
			"runtime-compatibility-server-configuration", CONFIGURATION_DESTINATION,
			before, after, transactionContent("server-configuration", ".conf"),
			"backups/{transaction}/before/" + CONFIGURATION_DESTINATION,
			true, afterBytes));
	}

	private static void appendReplacement(
		WorldBuilderAdaptiveProjectLifecycle.VerifiedProject project,
		Path target, String role, String destination, String source,
		String content, List<WorldBuilderAdaptiveMutationProfile.Action> actions)
		throws IOException, WorldBuilderContractException {
		Path sourcePath = WorldBuilderAdaptiveExporter.requireFile(
			project.projectRoot, source, "project runtime compatibility component");
		Path destinationPath = WorldBuilderAdaptiveMutationProfile.safeDestination(
			target, destination);
		WorldBuilderAdaptiveMutationProfile.FileState before;
		if (Files.isRegularFile(destinationPath, LinkOption.NOFOLLOW_LINKS)) {
			before = WorldBuilderAdaptiveMutationProfile.FileState.present(
				Files.size(destinationPath), WorldBuilderHashes.sha256(destinationPath));
		} else if (Files.exists(destinationPath, LinkOption.NOFOLLOW_LINKS)) {
			throw problem(destination,
				"Runtime compatibility destination is not a regular file.",
				"Restore the target runtime layout, then retry Import.");
		} else {
			before = WorldBuilderAdaptiveMutationProfile.FileState.absent();
		}
		WorldBuilderAdaptiveMutationProfile.FileState after =
			WorldBuilderAdaptiveMutationProfile.FileState.present(
				Files.size(sourcePath), WorldBuilderHashes.sha256(sourcePath));
		if (before.present && before.size == after.size
			&& before.sha256.equals(after.sha256)) return;
		byte[] generated = Files.readAllBytes(sourcePath);
		actions.add(new WorldBuilderAdaptiveMutationProfile.Action(
			role, destination, before, after, content,
			before.present ? "backups/{transaction}/before/" + destination : "",
			true, generated));
	}

	static List<WorldBuilderAdaptiveMutationProfile.Action> bindTransaction(
		Upgrade upgrade, String transactionId) {
		List<WorldBuilderAdaptiveMutationProfile.Action> result =
			new ArrayList<WorldBuilderAdaptiveMutationProfile.Action>();
		for (WorldBuilderAdaptiveMutationProfile.Action action : upgrade.actions) {
			String backup = action.backupRelativePath.replace(
				"{transaction}", transactionId);
			result.add(new WorldBuilderAdaptiveMutationProfile.Action(
				action.role, action.destinationRelativePath, action.before, action.after,
				action.contentRelativePath, backup, action.activation,
				action.generatedContent));
		}
		return result;
	}

	private static String transactionContent(String component, String suffix) {
		return "package/activation/runtime-compatibility-" + component + suffix;
	}

	private static List<Integer> integerList(Object raw, String source)
		throws WorldBuilderContractException {
		if (!(raw instanceof List<?>)) throw problem(source,
			"Runtime compatibility encoding versions are malformed.",
			"Restore the exact verified project runtime.");
		List<Integer> result = new ArrayList<Integer>();
		for (Object value : (List<?>)raw) {
			if (!(value instanceof Long)) throw problem(source,
				"Runtime compatibility encoding versions are malformed.",
				"Restore the exact verified project runtime.");
			result.add(Integer.valueOf(((Long)value).intValue()));
		}
		return Collections.unmodifiableList(result);
	}

	private static String compiledClientRoot(
		WorldBuilderAdaptiveConfiguration configuration)
		throws WorldBuilderContractException {
		String relative = configuration.clientRuntimeRelativePath;
		if (relative.startsWith("Client_Base/")) return "Client_Base";
		if (relative.startsWith("client/")) return "client";
		throw problem(relative,
			"Selected client map path has no supported runtime root.",
			"Restore the exact selected target configuration.");
	}

	private static WorldBuilderContractException problem(
		String source, String message, String nextStep) {
		return new WorldBuilderContractException(
			WorldBuilderErrorCodes.LOADER_INCOMPATIBLE,
			"plan-adaptive-import", "", "", source,
			"runtime compatibility", "A complete trusted server/client runtime pair.",
			message, false, message, nextStep, null);
	}

	static final class Upgrade {
		final List<Integer> encodingVersions;
		final List<WorldBuilderAdaptiveMutationProfile.Action> actions;
		final boolean archiveFreeClientBootstrapProven;

		Upgrade(List<Integer> encodingVersions,
			List<WorldBuilderAdaptiveMutationProfile.Action> actions,
			boolean archiveFreeClientBootstrapProven) {
			this.encodingVersions = encodingVersions;
			this.actions = Collections.unmodifiableList(
				new ArrayList<WorldBuilderAdaptiveMutationProfile.Action>(actions));
			this.archiveFreeClientBootstrapProven =
				archiveFreeClientBootstrapProven;
		}
	}
}
