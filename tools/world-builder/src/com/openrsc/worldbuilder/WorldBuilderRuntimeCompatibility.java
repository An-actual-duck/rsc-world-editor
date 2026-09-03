package com.openrsc.worldbuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Compiles the trusted project runtime into bounded target-install actions. */
final class WorldBuilderRuntimeCompatibility {
	static final String CAPABILITY_DESTINATION =
		"server/conf/world-builder/installed-runtime-capability-v2.json";
	static final String LEGACY_CAPABILITY_DESTINATION =
		"server/conf/world-builder/installed-runtime-capability-v1.json";
	static final String LEGACY_MANAGED_SERVER_DESTINATION =
		"server/lib/world-builder-managed-runtime.jar";
	static final String CAPABILITY_SOURCE =
		"working/runtime/server/conf/world-builder/installed-runtime-capability-v2.json";
	static final String BUNDLE_SOURCE =
		"working/runtime/server/conf/world-builder/managed-runtime-bundle.json";
	static final String CONFIGURATION_DESTINATION = "server/myworld.conf";
	static final String BUILD_DESTINATION = "server/build.xml";
	static final String CLIENT_PROFILE_NAME =
		"world-builder-configs/installed-client.json";
	private static final String CLIENT_BUILD_NAME = "build.xml";
	private static final String BUILD_GUARD_PROPERTY =
		"world.builder.installed.runtime";
	private static final String BUILD_GUARD_CAPABILITY =
		"conf/world-builder/installed-runtime-capability-v2.json";
	private static final String CLIENT_BUILD_GUARD_PROPERTY =
		"world.builder.installed.client";
	private static final String CLIENT_BUILD_GUARD_PROFILE =
		"world-builder-configs/installed-client.json";
	private static final String MANAGED_SERVER_DESTINATION =
		"server/world-builder-runtime/world-builder-managed-runtime.jar";
	private static final String MANAGED_SERVER_BUILD_PATH =
		"world-builder-runtime/world-builder-managed-runtime.jar";
	private static final Pattern PROJECT_TAG = Pattern.compile("<project\\b[^>]*>");
	private static final Pattern TARGET_TAG = Pattern.compile("<target\\b[^>]*>");
	private static final Pattern AVAILABLE_TAG = Pattern.compile("<available\\b[^>]*>");
	private static final Pattern COMPILE_CORE_NAME = Pattern.compile(
		"\\bname\\s*=\\s*(['\"]?)compile_core\\1(?:\\s|/?>|$)");
	private static final Pattern COMPILE_CLIENT_NAME = Pattern.compile(
		"\\bname\\s*=\\s*(['\"]?)compile\\1(?:\\s|/?>|$)");
	private static final Pattern UNLESS_ATTRIBUTE = Pattern.compile(
		"\\bunless\\s*=");
	private static final String SERVER_DESTINATION = "server/core.jar";
	private static final String SERVER_SOURCE =
		"working/runtime/server/world-builder-runtime/world-builder-managed-runtime.jar";
	private static final String BUNDLE_ID =
		"world-builder-managed-runtime-current";

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
		if (!serverPresent) throw problem(
			SERVER_DESTINATION,
			"Managed runtime upgrade found only one target runtime archive.",
			"Restore the complete server and client runtime pair, then retry Import.");
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
		Map<String,Object> activation = WorldBuilderAdaptiveExporter.object(
			capability.get("activation"), "activation");
		Map<String,Object> runtimeArchives = WorldBuilderAdaptiveExporter.object(
			capability.get("runtimeArchives"), "runtimeArchives");
		Map<String,Object> clientSourceUpgrade = WorldBuilderAdaptiveExporter.object(
			capability.get("clientSourceUpgrade"), "clientSourceUpgrade");
		List<?> clientNames = WorldBuilderAdaptiveExporter.array(
			runtimeArchives.get("clientNames"), "clientNames");
		if (WorldBuilderAdaptiveExporter.integer(capability, "schemaVersion") != 1L
			|| !"world-builder-installed-runtime-capability".equals(
				WorldBuilderAdaptiveExporter.string(capability, "manifestType"))
			|| !"world-builder-installed-runtime-capability-v2".equals(
				WorldBuilderAdaptiveExporter.string(capability, "capabilityId"))
			|| !BUNDLE_ID.equals(WorldBuilderAdaptiveExporter.string(
				capability, "managedRuntimeBundleId"))
			|| !"world-builder-installed".equals(
				WorldBuilderAdaptiveExporter.string(capability, "profileId"))
			|| !"generic-signed-layered-loader-v7-blocking-base-color".equals(
				WorldBuilderAdaptiveExporter.string(capability, "loaderId"))
			|| !"world-builder-installed-client-profile-v1".equals(
				WorldBuilderAdaptiveExporter.string(
					capability, "clientBootstrapId"))
			|| !"world-builder-native-layered-protocol-v2-u16-elevation".equals(
				WorldBuilderAdaptiveExporter.string(capability, "protocolId"))
			|| !"signed-layered-v1".equals(
				WorldBuilderAdaptiveExporter.string(capability, "mapFormatId"))
			|| !"layered-world-package-v1".equals(
				WorldBuilderAdaptiveExporter.string(capability, "packageSchemaId"))
			|| !"signed-layered-v1".equals(
				WorldBuilderAdaptiveExporter.string(capability, "coordinateModel"))
			|| !MANAGED_SERVER_DESTINATION.equals(
				WorldBuilderAdaptiveExporter.string(
					runtimeArchives, "serverRelativePath"))
			|| !SERVER_DESTINATION.equals(WorldBuilderAdaptiveExporter.string(
				runtimeArchives, "targetFallbackRelativePath"))
			|| !WorldBuilderInstalledClientSourceUpgrade.ID.equals(
				WorldBuilderAdaptiveExporter.string(
				clientSourceUpgrade, "upgradeId"))
			|| !"server/conf/world-builder/installed-client-source-upgrade-v5.json"
				.equals(WorldBuilderAdaptiveExporter.string(
					clientSourceUpgrade, "manifestRelativePath"))
			|| !"atomic-compile-target-client-before-run".equals(
				WorldBuilderAdaptiveExporter.string(clientSourceUpgrade, "buildPolicy"))
			|| clientNames.size() != 2
			|| !"Client_Base/Open_RSC_Client.jar".equals(clientNames.get(0))
			|| !"client/Open_RSC_Client.jar".equals(clientNames.get(1))
			|| !currentEncodingSet(encodingVersions)
			|| !"world-builder-installed".equals(
				WorldBuilderAdaptiveExporter.string(activation, "runtimeProfile"))
			|| WorldBuilderAdaptiveExporter.bool(activation, "builderOnly")
			|| !WorldBuilderAdaptiveExporter.bool(
				activation, "requiresExactManifestSha256")
			|| !WorldBuilderAdaptiveExporter.bool(
				activation, "replacesLegacyTerrain")
			|| !WorldBuilderAdaptiveExporter.bool(
				activation, "replacesLegacyPlacements")
			|| !WorldBuilderAdaptiveExporter.bool(
				activation, "replacesLegacyClientBootstrap")) {
			throw problem(CAPABILITY_SOURCE,
				"Project runtime compatibility identity is unsupported.",
				"Restore the exact verified project runtime.");
		}
		verifyBundle(project, capability);
		Path managedServerSource = WorldBuilderAdaptiveExporter.requireFile(
			project.projectRoot, SERVER_SOURCE, "managed server runtime upgrade");
		requireNoTargetClassShadowing(managedServerSource, target, serverTarget);
		if (!clientPresent && !"world-builder-installed-runtime-capability-v2"
			.equals(targetCapability.capabilityId)
			&& !hasManagedRepairCapability(target)) {
			throw problem(clientDestination,
				"Target client archive is missing outside a recognized managed-runtime repair state.",
				"Restore the target client archive or detect the previously upgraded target again.");
		}

		List<WorldBuilderAdaptiveMutationProfile.Action> actions =
			new ArrayList<WorldBuilderAdaptiveMutationProfile.Action>();
		appendReplacement(project, target, "runtime-compatibility-server-upgrade",
			MANAGED_SERVER_DESTINATION, SERVER_SOURCE,
			transactionContent("server-upgrade", ".jar"),
			actions);
		appendLegacyOverlayRetirement(target, actions);
		WorldBuilderInstalledClientSourceUpgrade.append(project, target,
			compiledClientRoot(configuration), actions);
		appendReplacement(project, target, "runtime-compatibility-capability",
			CAPABILITY_DESTINATION, CAPABILITY_SOURCE,
			transactionContent("capability", ".json"), actions);
		appendLegacyCapabilityRetirement(target, actions);
		appendServerConfiguration(target, packageValue, actions);
		appendServerBuildOverlay(target, actions);
		appendClientBuildOverlay(target, clientDestination, actions);
		appendClientProfile(target, clientDestination, targetCapability,
			packageValue, actions);
		return new Upgrade(encodingVersions, actions, true);
	}

	/**
	 * Refuses an overlay archive whenever it can win a target-owned server class.
	 * Byte-identical duplicates are still unsafe: a later target rebuild could
	 * change one side while leaving the first-on-classpath overlay stale.
	 */
	static void requireNoTargetClassShadowing(
		Path providerArchive, Path target, Path targetArchive)
		throws IOException, WorldBuilderContractException {
		Set<String> providerClasses = archiveClasses(
			providerArchive, SERVER_SOURCE, "Managed server runtime upgrade");
		Set<String> targetClasses = archiveClasses(
			targetArchive, SERVER_DESTINATION, "Target server runtime");
		Path libraries = WorldBuilderAdaptiveMutationProfile.safeDestination(
			target, "server/lib");
		if (Files.exists(libraries, LinkOption.NOFOLLOW_LINKS)) {
			if (!Files.isDirectory(libraries, LinkOption.NOFOLLOW_LINKS)
				|| Files.isSymbolicLink(libraries)) {
				throw problem("server/lib",
					"Target server library path is not a safe directory.",
					"Restore the target runtime layout and retry Import.");
			}
			List<Path> archives = new ArrayList<Path>();
			try (java.nio.file.DirectoryStream<Path> entries =
				Files.newDirectoryStream(libraries, "*.jar")) {
				for (Path archive : entries) {
					if (archives.size() >= 512) throw problem("server/lib",
						"Target server library directory contains too many JAR archives.",
						"Reduce the target runtime to a bounded reviewed library set and retry Import.");
					archives.add(archive);
				}
			} catch (WorldBuilderContractException invalid) {
				throw invalid;
			} catch (IOException invalid) {
				throw problem("server/lib",
					"Target server library directory cannot be inspected safely.",
					"Restore readable target runtime libraries and retry Import.");
			}
			Collections.sort(archives, Comparator.comparing(Path::toString));
			for (Path archive : archives) {
				String relative = "server/lib/" + archive.getFileName().toString();
				// The former managed overlay is removed by this same transaction,
				// so it cannot own a class on the resulting production classpath.
				if (LEGACY_MANAGED_SERVER_DESTINATION.equals(relative)) continue;
				if (!Files.isRegularFile(archive, LinkOption.NOFOLLOW_LINKS)
					|| Files.isSymbolicLink(archive)) {
					throw problem(relative,
						"Target server library is not a safe regular JAR archive.",
						"Restore a safe regular target library and retry Import.");
				}
				targetClasses.addAll(archiveClasses(
					archive, relative, "Target server library"));
			}
		}
		Set<String> overlap = new TreeSet<String>(providerClasses);
		overlap.retainAll(targetClasses);
		if (overlap.isEmpty()) return;

		List<String> examples = new ArrayList<String>();
		String[] important = {
			"com/openrsc/server/model/entity/player/Player.class",
			"com/openrsc/server/model/container/Inventory.class",
			"com/openrsc/server/model/world/World.class",
			"com/openrsc/server/net/rsc/ActionSender.class"
		};
		for (String name : important) if (overlap.contains(name)) examples.add(name);
		for (String name : overlap) {
			if (examples.size() >= 4) break;
			if (!examples.contains(name)) examples.add(name);
		}
		throw problem(SERVER_SOURCE,
			"Managed server runtime would shadow " + overlap.size()
				+ " target-owned runtime class(es): " + examples + ".",
			"Install or build a host-compatible World Builder runtime with no duplicate "
				+ "com.openrsc.server classes; the target was not changed.");
	}

	private static Set<String> archiveClasses(
		Path archive, String source, String description)
		throws WorldBuilderContractException {
		Set<String> result = new TreeSet<String>();
		int entries = 0;
		try (ZipFile zip = new ZipFile(archive.toFile())) {
			Enumeration<? extends ZipEntry> values = zip.entries();
			while (values.hasMoreElements()) {
				ZipEntry entry = values.nextElement();
				entries++;
				if (entries > 100000) throw problem(source,
					description + " contains too many archive entries.",
					"Restore a bounded regular JAR and retry Import.");
				String name = entry.getName();
				if (name.length() > 1024 || name.indexOf('\\') >= 0
					|| name.startsWith("/") || name.contains("../")) {
					throw problem(source, description + " contains an unsafe archive path.",
						"Restore a safe regular JAR and retry Import.");
				}
				if (entry.isDirectory() || !name.endsWith(".class")) continue;
				if (!result.add(name)) throw problem(source,
					description + " repeats server class " + name + ".",
					"Restore an archive with unique class entries and retry Import.");
			}
		} catch (WorldBuilderContractException invalid) {
			throw invalid;
		} catch (IOException invalid) {
			throw problem(source, description + " is not a readable JAR archive.",
				"Restore a valid regular JAR and retry Import.");
		}
		return result;
	}

	private static boolean hasManagedRepairCapability(Path target)
		throws IOException, WorldBuilderContractException {
		Path path = WorldBuilderAdaptiveMutationProfile.safeDestination(
			target, CAPABILITY_DESTINATION);
		if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
			|| Files.isSymbolicLink(path)) return false;
		try {
			Map<String,Object> capability = WorldBuilderJsonDocuments.readObject(path);
			return "world-builder-installed-runtime-capability-v2".equals(
				WorldBuilderAdaptiveExporter.string(capability, "capabilityId"))
				&& "world-builder-installed".equals(
					WorldBuilderAdaptiveExporter.string(capability, "profileId"));
		} catch (WorldBuilderDiscoveryException invalid) {
			return false;
		}
	}

	private static void verifyBundle(
		WorldBuilderAdaptiveProjectLifecycle.VerifiedProject project,
		Map<String,Object> capability)
		throws IOException, WorldBuilderContractException {
		Path source = WorldBuilderAdaptiveExporter.requireFile(
			project.projectRoot, BUNDLE_SOURCE, "managed runtime bundle");
		Map<String,Object> bundle;
		try {
			bundle = WorldBuilderJsonDocuments.readObject(source);
		} catch (WorldBuilderDiscoveryException invalid) {
			throw problem(BUNDLE_SOURCE, "Managed runtime bundle is malformed.",
				"Restore the exact verified project runtime.");
		}
		if (WorldBuilderAdaptiveExporter.integer(bundle, "schemaVersion") != 1L
			|| !"world-builder-managed-runtime-bundle".equals(
				WorldBuilderAdaptiveExporter.string(bundle, "manifestType"))
			|| !BUNDLE_ID.equals(
				WorldBuilderAdaptiveExporter.string(bundle, "bundleId"))
			|| !"world-builder-installed-loader-v12".equals(
				WorldBuilderAdaptiveExporter.string(bundle, "runtimeContractId"))
			|| !BUNDLE_ID.equals(WorldBuilderAdaptiveExporter.string(
				capability, "managedRuntimeBundleId"))
			|| !sameIdentity(bundle, capability, "profileId")
			|| !sameIdentity(bundle, capability, "loaderId")
			|| !sameIdentity(bundle, capability, "protocolId")
			|| !sameIdentity(bundle, capability, "clientBootstrapId")) {
			throw problem(BUNDLE_SOURCE,
				"Managed runtime bundle identity does not match its installed capability.",
				"Restore the exact verified project runtime.");
		}
		List<?> components = WorldBuilderAdaptiveExporter.array(
			bundle.get("components"), "components");
		if (components.size() != 3
			|| !componentMatches(components.get(0), "server-runtime-upgrade",
				"server/world-builder-runtime/world-builder-managed-runtime.jar",
				"target-root", MANAGED_SERVER_DESTINATION)
			|| !componentMatches(components.get(1), "client-source-upgrade",
				"server/conf/world-builder/installed-client-source-upgrade-v5.json",
				"selected-client-root", "src",
				"semantic-upgrade-with-verified-backup")
			|| !componentMatches(components.get(2), "runtime-capability",
				"server/conf/world-builder/installed-runtime-capability-v2.json",
				"target-root", CAPABILITY_DESTINATION,
				"replace-with-verified-backup")) {
			throw problem(BUNDLE_SOURCE,
				"Managed runtime bundle component set is unsupported.",
				"Restore the exact verified project runtime.");
		}
		List<?> legacy = WorldBuilderAdaptiveExporter.array(
			bundle.get("legacyCapabilityPaths"), "legacyCapabilityPaths");
		if (legacy.size() != 1 || !LEGACY_CAPABILITY_DESTINATION.equals(legacy.get(0))) {
			throw problem(BUNDLE_SOURCE,
				"Managed runtime bundle legacy retirement set is unsupported.",
				"Restore the exact verified project runtime.");
		}
	}

	private static boolean currentEncodingSet(List<Integer> values) {
		return values.size() == 4
			&& Integer.valueOf(1).equals(values.get(0))
			&& Integer.valueOf(2).equals(values.get(1))
			&& Integer.valueOf(3).equals(values.get(2))
			&& Integer.valueOf(4).equals(values.get(3));
	}

	private static boolean sameIdentity(
		Map<String,Object> left, Map<String,Object> right, String key)
		throws WorldBuilderContractException {
		return WorldBuilderAdaptiveExporter.string(left, key).equals(
			WorldBuilderAdaptiveExporter.string(right, key));
	}

	private static boolean componentMatches(Object raw, String role, String source,
		String destinationKind, String destination)
		throws WorldBuilderContractException {
		return componentMatches(raw, role, source, destinationKind, destination,
			"replace-with-verified-backup");
	}

	private static boolean componentMatches(Object raw, String role, String source,
		String destinationKind, String destination, String replacementPolicy)
		throws WorldBuilderContractException {
		Map<String,Object> component = WorldBuilderAdaptiveExporter.object(raw, "component");
		return role.equals(WorldBuilderAdaptiveExporter.string(component, "role"))
			&& source.equals(WorldBuilderAdaptiveExporter.string(
				component, "sourceRelativePath"))
			&& destinationKind.equals(WorldBuilderAdaptiveExporter.string(
				component, "destinationKind"))
			&& destination.equals(WorldBuilderAdaptiveExporter.string(
				component, "destinationRelativePath"))
			&& replacementPolicy.equals(
				WorldBuilderAdaptiveExporter.string(component, "replacementPolicy"));
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
			|| !BUNDLE_ID.equals(WorldBuilderAdaptiveExporter.string(
				capability, "managedRuntimeBundleId"))
			|| !"generic-signed-layered-loader-v7-blocking-base-color".equals(
				WorldBuilderAdaptiveExporter.string(capability, "loaderId"))
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
		Path destination = WorldBuilderAdaptiveMutationProfile.safeDestination(
			target, CONFIGURATION_DESTINATION);
		if (!Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) return;
		if (!Files.isRegularFile(destination, LinkOption.NOFOLLOW_LINKS)
			|| Files.isSymbolicLink(destination)) {
			throw problem(CONFIGURATION_DESTINATION,
				"Target server launch configuration is not a safe regular file.",
				"Restore the target server layout and retry Import.");
		}
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

	private static void appendServerBuildOverlay(
		Path target, List<WorldBuilderAdaptiveMutationProfile.Action> actions)
		throws IOException, WorldBuilderContractException {
		Path destination = WorldBuilderAdaptiveMutationProfile.safeDestination(
			target, BUILD_DESTINATION);
		if (!Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) return;
		if (!Files.isRegularFile(destination, LinkOption.NOFOLLOW_LINKS)
			|| Files.isSymbolicLink(destination)) {
			throw problem(BUILD_DESTINATION,
				"Target server build file is not a safe regular file.",
				"Restore the target server layout and retry Import.");
		}
		byte[] beforeBytes = Files.readAllBytes(destination);
		String original = new String(beforeBytes, StandardCharsets.UTF_8);
		if (!java.util.Arrays.equals(beforeBytes,
				original.getBytes(StandardCharsets.UTF_8))) {
			throw problem(BUILD_DESTINATION,
				"Target server build file is not valid UTF-8.",
				"Convert server/build.xml to UTF-8 and retry Import.");
		}
		byte[] afterBytes = renderServerBuildOverlay(original)
			.getBytes(StandardCharsets.UTF_8);
		WorldBuilderAdaptiveMutationProfile.FileState before =
			WorldBuilderAdaptiveMutationProfile.FileState.present(
				beforeBytes.length, WorldBuilderHashes.sha256(beforeBytes));
		WorldBuilderAdaptiveMutationProfile.FileState after =
			WorldBuilderAdaptiveMutationProfile.FileState.present(
				afterBytes.length, WorldBuilderHashes.sha256(afterBytes));
		if (before.size == after.size && before.sha256.equals(after.sha256)) return;
		actions.add(new WorldBuilderAdaptiveMutationProfile.Action(
			"runtime-compatibility-server-build-overlay", BUILD_DESTINATION,
			before, after, transactionContent("server-build-overlay", ".xml"),
			"backups/{transaction}/before/" + BUILD_DESTINATION,
			true, afterBytes));
	}

	private static void appendClientBuildOverlay(
		Path target, String clientRuntimeDestination,
		List<WorldBuilderAdaptiveMutationProfile.Action> actions)
		throws IOException, WorldBuilderContractException {
		String clientRoot = clientRuntimeDestination.substring(
			0, clientRuntimeDestination.indexOf('/'));
		String destination = clientRoot + "/" + CLIENT_BUILD_NAME;
		Path destinationPath = WorldBuilderAdaptiveMutationProfile.safeDestination(
			target, destination);
		if (!Files.exists(destinationPath, LinkOption.NOFOLLOW_LINKS)) return;
		if (!Files.isRegularFile(destinationPath, LinkOption.NOFOLLOW_LINKS)
			|| Files.isSymbolicLink(destinationPath)) {
			throw problem(destination,
				"Target client build file is not a safe regular file.",
				"Restore the target client layout and retry Import.");
		}
		byte[] beforeBytes = Files.readAllBytes(destinationPath);
		String original = new String(beforeBytes, StandardCharsets.UTF_8);
		if (!java.util.Arrays.equals(beforeBytes,
				original.getBytes(StandardCharsets.UTF_8))) {
			throw problem(destination,
				"Target client build file is not valid UTF-8.",
				"Convert " + destination + " to UTF-8 and retry Import.");
		}
		byte[] afterBytes = renderClientBuildOverlay(original, destination)
			.getBytes(StandardCharsets.UTF_8);
		WorldBuilderAdaptiveMutationProfile.FileState before =
			WorldBuilderAdaptiveMutationProfile.FileState.present(
				beforeBytes.length, WorldBuilderHashes.sha256(beforeBytes));
		WorldBuilderAdaptiveMutationProfile.FileState after =
			WorldBuilderAdaptiveMutationProfile.FileState.present(
				afterBytes.length, WorldBuilderHashes.sha256(afterBytes));
		if (before.size == after.size && before.sha256.equals(after.sha256)) return;
		actions.add(new WorldBuilderAdaptiveMutationProfile.Action(
			"runtime-compatibility-client-build-overlay", destination,
			before, after, transactionContent("client-build-overlay", ".xml"),
			"backups/{transaction}/before/" + destination,
			true, afterBytes));
	}

	static String renderClientBuildOverlay(String original, String destination)
		throws WorldBuilderContractException {
		Matcher project = PROJECT_TAG.matcher(original);
		if (!project.find() || project.find()) throw clientBuildProblem(destination,
			"Target client build file does not contain one unambiguous project element.");

		Matcher targets = TARGET_TAG.matcher(original);
		String compileTag = null;
		int compileCount = 0;
		while (targets.find()) {
			String tag = targets.group();
			if (!COMPILE_CLIENT_NAME.matcher(tag).find()) continue;
			compileCount++;
			if (compileCount == 1) compileTag = tag;
		}
		if (compileCount != 1 || compileTag == null) throw clientBuildProblem(
			destination,
			"Target client build file does not contain one unambiguous compile target.");

		boolean targetGuarded = Pattern.compile("\\bunless\\s*=\\s*(['\"]?)"
			+ Pattern.quote(CLIENT_BUILD_GUARD_PROPERTY) + "\\1(?:\\s|>)")
			.matcher(compileTag).find();
		int guardDeclarations = 0;
		Matcher available = AVAILABLE_TAG.matcher(original);
		while (available.find()) {
			String tag = available.group();
			if (attributeEquals(tag, "file", CLIENT_BUILD_GUARD_PROFILE)
				&& attributeEquals(tag, "property", CLIENT_BUILD_GUARD_PROPERTY)) {
				guardDeclarations++;
			}
		}
		boolean profileNamed = original.contains(CLIENT_BUILD_GUARD_PROFILE);
		boolean propertyNamed = original.contains(CLIENT_BUILD_GUARD_PROPERTY);
		String working = original;
		if (targetGuarded || guardDeclarations != 0 || profileNamed || propertyNamed) {
			if (!targetGuarded || guardDeclarations != 1) throw clientBuildProblem(
				destination,
				"Target client build file contains a partial or conflicting installed-client guard.");
			working = working.replaceFirst(
				"\\s+unless\\s*=\\s*(['\"])"
					+ Pattern.quote(CLIENT_BUILD_GUARD_PROPERTY) + "\\1", "");
			working = working.replaceFirst(
				"(?m)^[ \\t]*<!-- Preserve the verified World Builder client runtime during target launches\\. -->\\r?\\n", "");
			working = working.replaceFirst(
				"(?m)^[ \\t]*<available\\s+file=\""
					+ Pattern.quote(CLIENT_BUILD_GUARD_PROFILE)
					+ "\"\\s+property=\"" + Pattern.quote(CLIENT_BUILD_GUARD_PROPERTY)
					+ "\"/>\\r?\\n?", "");
			if (working.contains(CLIENT_BUILD_GUARD_PROFILE)
				|| working.contains(CLIENT_BUILD_GUARD_PROPERTY)) {
				throw clientBuildProblem(destination,
					"Target client build guard could not be retired completely.");
			}
		} else if (UNLESS_ATTRIBUTE.matcher(compileTag).find()) throw clientBuildProblem(
			destination,
			"Target client compile target has an unrelated conditional guard and cannot prove compilation before launch.");
		if (compileTag.endsWith("/>")) throw clientBuildProblem(destination,
			"Target client compile target cannot be self-closing.");

		Pattern compileTargetPattern = Pattern.compile(
			"(?s)<target\\b(?=[^>]*\\bname\\s*=\\s*(['\"])compile\\1)[^>]*>.*?</target>");
		Matcher compileTarget = compileTargetPattern.matcher(working);
		if (!compileTarget.find()) throw clientBuildProblem(destination,
			"Target client compile target body is missing.");
		String block = compileTarget.group();
		int blockStart = compileTarget.start();
		int blockEnd = compileTarget.end();
		if (compileTarget.find()) throw clientBuildProblem(destination,
			"Target client build file contains multiple compile target bodies.");
		Pattern primaryDelete = Pattern.compile(
			"<delete\\b(?=[^>]*\\bfile\\s*=\\s*(['\"])\\$\\{jar\\}\\1)[^>]*/>");
		Pattern stagedDelete = Pattern.compile(
			"<delete\\b(?=[^>]*\\bfile\\s*=\\s*(['\"])\\$\\{jar\\}\\.world-builder-new\\1)[^>]*/>");
		Pattern primaryDestination = Pattern.compile(
			"\\bdestfile\\s*=\\s*(['\"])\\$\\{jar\\}\\1");
		Pattern stagedDestination = Pattern.compile(
			"\\bdestfile\\s*=\\s*(['\"])\\$\\{jar\\}\\.world-builder-new\\1");
		Pattern stagedMove = Pattern.compile(
			"<move\\b(?=[^>]*\\bfile\\s*=\\s*(['\"])\\$\\{jar\\}\\.world-builder-new\\1)"
				+ "(?=[^>]*\\btofile\\s*=\\s*(['\"])\\$\\{jar\\}\\2)[^>]*/>");
		int stagedEvidence = patternCount(stagedDelete, block)
			+ patternCount(stagedDestination, block) + patternCount(stagedMove, block);
		if (stagedEvidence != 0) {
			if (stagedEvidence != 3 || patternCount(primaryDelete, block) != 0
				|| patternCount(stagedDelete, block) != 1
				|| patternCount(stagedDestination, block) != 1
				|| patternCount(stagedMove, block) != 1) {
				throw clientBuildProblem(destination,
					"Target client build contains a partial atomic archive replacement.");
			}
			return working;
		}
		if (patternCount(primaryDelete, block) != 1
			|| patternCount(primaryDestination, block) != 1
			|| literalCount(block, "</jar>") != 1) {
			throw clientBuildProblem(destination,
				"Target client compile target does not match the supported archive boundary.");
		}
		String upgraded = primaryDelete.matcher(block).replaceFirst(
			"<delete file=\"\\${jar}.world-builder-new\"/>");
		upgraded = primaryDestination.matcher(upgraded).replaceFirst(
			"destfile=\"\\${jar}.world-builder-new\"");
		String newline = working.contains("\r\n") ? "\r\n" : "\n";
		int jarEnd = upgraded.indexOf("</jar>") + "</jar>".length();
		upgraded = upgraded.substring(0, jarEnd) + newline
			+ "        <move file=\"${jar}.world-builder-new\" tofile=\"${jar}\"/>"
			+ upgraded.substring(jarEnd);
		return working.substring(0, blockStart) + upgraded
			+ working.substring(blockEnd);
	}

	private static int patternCount(Pattern pattern, String value) {
		int count = 0;
		Matcher matcher = pattern.matcher(value);
		while (matcher.find()) count++;
		return count;
	}

	private static int literalCount(String value, String needle) {
		int count = 0;
		for (int index = 0; (index = value.indexOf(needle, index)) >= 0;
			index += needle.length()) count++;
		return count;
	}

	private static WorldBuilderContractException clientBuildProblem(
		String destination, String message) {
		return problem(destination, message,
			"Restore an unmodified " + destination
				+ " whose compile target runs before launch, then retry Import.");
	}

	static String renderServerBuildOverlay(String original)
		throws WorldBuilderContractException {
		Matcher project = PROJECT_TAG.matcher(original);
		if (!project.find() || project.find()) throw buildGuardProblem(
			"Target server build file does not contain one unambiguous project element.");

		Matcher targets = TARGET_TAG.matcher(original);
		String compileTag = null;
		int compileCoreCount = 0;
		while (targets.find()) {
			String tag = targets.group();
			if (!COMPILE_CORE_NAME.matcher(tag).find()) continue;
			compileCoreCount++;
			if (compileCoreCount == 1) {
				compileTag = tag;
			}
		}
		if (compileCoreCount != 1 || compileTag == null) throw buildGuardProblem(
			"Target server build file does not contain one unambiguous compile_core target.");

		boolean targetGuarded = Pattern.compile("\\bunless\\s*=\\s*(['\"]?)"
			+ Pattern.quote(BUILD_GUARD_PROPERTY) + "\\1(?:\\s|>)")
			.matcher(compileTag).find();
		int guardDeclarations = 0;
		Matcher available = AVAILABLE_TAG.matcher(original);
		while (available.find()) {
			String tag = available.group();
			if (attributeEquals(tag, "file", BUILD_GUARD_CAPABILITY)
				&& attributeEquals(tag, "property", BUILD_GUARD_PROPERTY)) {
				guardDeclarations++;
			}
		}
		boolean capabilityNamed = original.contains(BUILD_GUARD_CAPABILITY);
		boolean propertyNamed = original.contains(BUILD_GUARD_PROPERTY);
		String working = original;
		if (targetGuarded || guardDeclarations != 0 || capabilityNamed || propertyNamed) {
			if (!targetGuarded || guardDeclarations != 1) throw buildGuardProblem(
				"Target server build file contains a partial or conflicting compile_core guard.");
			working = working.replaceFirst(
				"\\s+unless\\s*=\\s*(['\"])"
					+ Pattern.quote(BUILD_GUARD_PROPERTY) + "\\1", "");
			working = working.replaceFirst(
				"(?m)^[ \\t]*<!-- Preserve the verified World Builder core\\.jar during target launches\\. -->\\r?\\n", "");
			working = working.replaceFirst(
				"(?m)^[ \\t]*<available\\s+file=\""
					+ Pattern.quote(BUILD_GUARD_CAPABILITY)
					+ "\"\\s+property=\"" + Pattern.quote(BUILD_GUARD_PROPERTY)
					+ "\"/>\\r?\\n?", "");
		} else if (UNLESS_ATTRIBUTE.matcher(compileTag).find()) {
			throw buildGuardProblem(
				"Target compile_core target already has an unrelated conditional guard.");
		}

		Pattern pluginTargetPattern = Pattern.compile(
			"(?s)<target\\b(?=[^>]*\\bname\\s*=\\s*(['\"])compile_plugins\\1)[^>]*>.*?</target>");
		Matcher pluginTarget = pluginTargetPattern.matcher(working);
		if (!pluginTarget.find()) throw buildGuardProblem(
			"Target server build file does not contain one unambiguous compile_plugins target.");
		String block = pluginTarget.group();
		int pluginTargetStart = pluginTarget.start();
		int pluginTargetEnd = pluginTarget.end();
		if (pluginTarget.find()) throw buildGuardProblem(
			"Target server build file does not contain one unambiguous compile_plugins target.");
		String legacyManagedEntry =
			"<pathelement location=\"lib/world-builder-managed-runtime.jar\"/>";
		String managedEntry = "<pathelement location=\""
			+ MANAGED_SERVER_BUILD_PATH + "\"/>";
		if (repeatedEntry(block, legacyManagedEntry)
			|| repeatedEntry(block, managedEntry)) throw buildGuardProblem(
			"Target compile_plugins classpath repeats a managed runtime overlay.");
		block = removeEntryLine(block, legacyManagedEntry);
		block = removeEntryLine(block, managedEntry);
		Matcher coreEntry = Pattern.compile(
			"<pathelement\\s+location=\"(?:core\\.jar|\\$\\{jar\\})\"\\s*/>")
			.matcher(block);
		if (!coreEntry.find()) throw buildGuardProblem(
			"Target compile_plugins classpath has no unambiguous target core entry.");
		int coreEntryStart = coreEntry.start();
		int coreEntryEnd = coreEntry.end();
		if (coreEntry.find()) throw buildGuardProblem(
			"Target compile_plugins classpath has no unambiguous target core entry.");
		String newline = working.contains("\r\n") ? "\r\n" : "\n";
		String indent = lineIndent(block, coreEntryStart);
		block = block.substring(0, coreEntryEnd) + newline + indent + managedEntry
			+ block.substring(coreEntryEnd);
		working = working.substring(0, pluginTargetStart) + block
			+ working.substring(pluginTargetEnd);
		working = renderRunTargetOverlay(working, "runserver", true);
		return renderRunTargetOverlay(working, "runserverzgc", false);
	}

	private static String renderRunTargetOverlay(
		String original, String targetName, boolean required)
		throws WorldBuilderContractException {
		Pattern targetPattern = Pattern.compile(
			"(?s)<target\\b(?=[^>]*\\bname\\s*=\\s*(['\"])"
				+ Pattern.quote(targetName) + "\\1)[^>]*>.*?</target>");
		Matcher target = targetPattern.matcher(original);
		if (!target.find()) {
			if (required) throw buildGuardProblem(
				"Target server build file does not contain one unambiguous "
					+ targetName + " target.");
			return original;
		}
		String block = target.group();
		int targetStart = target.start();
		int targetEnd = target.end();
		if (target.find()) throw buildGuardProblem(
			"Target server build file does not contain one unambiguous "
				+ targetName + " target.");
		String legacyManagedEntry =
			"<pathelement location=\"lib/world-builder-managed-runtime.jar\"/>";
		String managedEntry = "<pathelement location=\""
			+ MANAGED_SERVER_BUILD_PATH + "\"/>";
		if (repeatedEntry(block, legacyManagedEntry)
			|| repeatedEntry(block, managedEntry)) throw buildGuardProblem(
			"Target " + targetName + " classpath repeats a managed runtime overlay.");
		block = removeEntryLine(block, legacyManagedEntry);
		block = removeEntryLine(block, managedEntry);
		String libraryEntry = "<pathelement location=\"${lib}/*\"/>";
		String targetCoreEntry = "<pathelement path=\"${jar}/\"/>";
		int libraryIndex = block.indexOf(libraryEntry);
		int targetCoreIndex = block.indexOf(targetCoreEntry);
		if (libraryIndex < 0 || targetCoreIndex < 0) {
			if (!required) return original;
			throw buildGuardProblem(
				"Target " + targetName
					+ " classpath cannot isolate the managed runtime before core.jar.");
		}
		if (block.indexOf(libraryEntry, libraryIndex + 1) >= 0
			|| block.indexOf(targetCoreEntry, targetCoreIndex + 1) >= 0
			|| libraryIndex > targetCoreIndex) throw buildGuardProblem(
			"Target " + targetName
				+ " classpath cannot isolate the managed runtime before core.jar.");
		String newline = original.contains("\r\n") ? "\r\n" : "\n";
		String indent = lineIndent(block, libraryIndex);
		block = block.substring(0, libraryIndex) + managedEntry + newline + indent
			+ block.substring(libraryIndex);
		return original.substring(0, targetStart) + block + original.substring(targetEnd);
	}

	private static boolean repeatedEntry(String value, String entry) {
		int first = value.indexOf(entry);
		return first >= 0 && value.indexOf(entry, first + 1) >= 0;
	}

	private static String removeEntryLine(String value, String entry) {
		int entryStart = value.indexOf(entry);
		if (entryStart < 0) return value;
		int entryEnd = entryStart + entry.length();
		int lineStart = value.lastIndexOf('\n', Math.max(0, entryStart - 1));
		lineStart = lineStart < 0 ? 0 : lineStart + 1;
		int lineEnd = value.indexOf('\n', entryEnd);
		int contentEnd = lineEnd < 0 ? value.length() : lineEnd;
		if (value.substring(lineStart, entryStart).trim().isEmpty()
			&& value.substring(entryEnd, contentEnd).trim().isEmpty()) {
			return value.substring(0, lineStart)
				+ value.substring(lineEnd < 0 ? contentEnd : lineEnd + 1);
		}
		return value.substring(0, entryStart) + value.substring(entryEnd);
	}

	private static boolean attributeEquals(
		String tag, String name, String expected) {
		return Pattern.compile("\\b" + Pattern.quote(name)
			+ "\\s*=\\s*(['\"])" + Pattern.quote(expected) + "\\1")
			.matcher(tag).find();
	}

	private static String lineIndent(String value, int offset) {
		int lineStart = value.lastIndexOf('\n', Math.max(0, offset - 1));
		lineStart = lineStart < 0 ? 0 : lineStart + 1;
		int cursor = lineStart;
		while (cursor < offset) {
			char character = value.charAt(cursor);
			if (character != ' ' && character != '\t') break;
			cursor++;
		}
		return value.substring(lineStart, cursor);
	}

	private static WorldBuilderContractException buildGuardProblem(String message) {
		return problem(BUILD_DESTINATION, message,
			"Restore an unmodified server/build.xml with one compile_core target and retry Import.");
	}

	private static void appendLegacyCapabilityRetirement(
		Path target, List<WorldBuilderAdaptiveMutationProfile.Action> actions)
		throws IOException, WorldBuilderContractException {
		Path destination = WorldBuilderAdaptiveMutationProfile.safeDestination(
			target, LEGACY_CAPABILITY_DESTINATION);
		if (!Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) return;
		if (!Files.isRegularFile(destination, LinkOption.NOFOLLOW_LINKS)
			|| Files.isSymbolicLink(destination)) {
			throw problem(LEGACY_CAPABILITY_DESTINATION,
				"Legacy runtime capability is not a safe regular file.",
				"Restore the target runtime layout, then retry Import.");
		}
		WorldBuilderAdaptiveMutationProfile.FileState before =
			WorldBuilderAdaptiveMutationProfile.FileState.present(
				Files.size(destination), WorldBuilderHashes.sha256(destination));
		actions.add(new WorldBuilderAdaptiveMutationProfile.Action(
			"runtime-compatibility-legacy-capability-retirement",
			LEGACY_CAPABILITY_DESTINATION, before,
			WorldBuilderAdaptiveMutationProfile.FileState.absent(), "",
			"backups/{transaction}/before/" + LEGACY_CAPABILITY_DESTINATION,
			true, null));
	}

	private static void appendLegacyOverlayRetirement(
		Path target, List<WorldBuilderAdaptiveMutationProfile.Action> actions)
		throws IOException, WorldBuilderContractException {
		Path destination = WorldBuilderAdaptiveMutationProfile.safeDestination(
			target, LEGACY_MANAGED_SERVER_DESTINATION);
		if (!Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) return;
		if (!Files.isRegularFile(destination, LinkOption.NOFOLLOW_LINKS)
			|| Files.isSymbolicLink(destination)) {
			throw problem(LEGACY_MANAGED_SERVER_DESTINATION,
				"Legacy managed runtime overlay is not a safe regular file.",
				"Restore the target runtime layout, then retry Import.");
		}
		WorldBuilderAdaptiveMutationProfile.FileState before =
			WorldBuilderAdaptiveMutationProfile.FileState.present(
				Files.size(destination), WorldBuilderHashes.sha256(destination));
		actions.add(new WorldBuilderAdaptiveMutationProfile.Action(
			"runtime-compatibility-legacy-overlay-retirement",
			LEGACY_MANAGED_SERVER_DESTINATION, before,
			WorldBuilderAdaptiveMutationProfile.FileState.absent(), "",
			"backups/{transaction}/before/" + LEGACY_MANAGED_SERVER_DESTINATION,
			true, null));
	}

	static void appendReplacement(
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

	static String transactionContent(String component, String suffix) {
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

	static WorldBuilderContractException problem(
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
