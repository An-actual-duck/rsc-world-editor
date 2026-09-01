package com.openrsc.worldbuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Applies the bounded installed-client bootstrap while preserving target content. */
final class WorldBuilderInstalledClientSourceUpgrade {
	static final String SOURCE =
		"working/runtime/server/conf/world-builder/installed-client-source-upgrade-v1.json";
	static final String ID = "world-builder-installed-client-source-upgrade-v1";
	private static final Pattern PROFILE_IMPORT = Pattern.compile(
		"(?m)^[\\t ]*import\\s+orsc\\.WorldBuilderClientProfile\\s*;[\\t ]*$");
	private static final Pattern TERRAIN_BOOTSTRAP_IMPORT = Pattern.compile(
		"(?m)^[\\t ]*import\\s+orsc\\.WorldBuilderTerrainBootstrap\\s*;[\\t ]*$");
	private static final Pattern PROFILE_NATIVE_ONLY = profileCall(
		"isStrictAdaptiveTerrain");
	private static final Pattern BOOTSTRAP_NATIVE_ONLY = bootstrapCall(
		"isNativeOnly");
	private static final Pattern PROFILE_MAP_IDENTITY = profileCall(
		"strictAdaptiveMapIdentity");
	private static final Pattern BOOTSTRAP_MAP_IDENTITY = bootstrapCall(
		"mapIdentity");
	private static final Pattern PROFILE_LOGIN_WORLD = Pattern.compile(
		"return\\s*!\\s*WorldBuilderClientProfile\\s*\\.\\s*current\\s*"
			+ "\\(\\s*\\)\\s*\\.\\s*isStrictAdaptiveTerrain\\s*"
			+ "\\(\\s*\\)\\s*;");
	private static final Pattern BOOTSTRAP_LOGIN_WORLD = Pattern.compile(
		"return\\s*!\\s*WorldBuilderTerrainBootstrap\\s*\\.\\s*"
			+ "isNativeOnly\\s*\\(\\s*\\)\\s*;");

	private WorldBuilderInstalledClientSourceUpgrade() {
	}

	static void append(
		WorldBuilderAdaptiveProjectLifecycle.VerifiedProject project,
		Path target, String clientRoot,
		List<WorldBuilderAdaptiveMutationProfile.Action> actions)
		throws IOException, WorldBuilderContractException {
		Path manifestPath = WorldBuilderAdaptiveExporter.requireFile(
			project.projectRoot, SOURCE, "installed client source upgrade");
		Map<String,Object> manifest;
		try {
			manifest = WorldBuilderJsonDocuments.readObject(manifestPath);
		} catch (WorldBuilderDiscoveryException invalid) {
			throw problem(SOURCE, "Installed client source upgrade is malformed.");
		}
		if (WorldBuilderAdaptiveExporter.integer(manifest, "schemaVersion") != 1L
			|| !"world-builder-installed-client-source-upgrade".equals(
				WorldBuilderAdaptiveExporter.string(manifest, "manifestType"))
			|| !ID.equals(WorldBuilderAdaptiveExporter.string(manifest, "upgradeId"))
			|| !"world-builder-installed-client-profile-v1".equals(
				WorldBuilderAdaptiveExporter.string(manifest, "clientBootstrapId"))
			|| !"compile-target-client-before-run".equals(
				WorldBuilderAdaptiveExporter.string(manifest, "buildPolicy"))) {
			throw problem(SOURCE,
				"Installed client source upgrade identity is unsupported.");
		}
		appendSourceFiles(project, target, clientRoot, manifest, actions);
		appendTransforms(target, clientRoot, manifest, actions);
	}

	private static void appendSourceFiles(
		WorldBuilderAdaptiveProjectLifecycle.VerifiedProject project,
		Path target, String clientRoot, Map<String,Object> manifest,
		List<WorldBuilderAdaptiveMutationProfile.Action> actions)
		throws IOException, WorldBuilderContractException {
		List<?> sourceFiles = WorldBuilderAdaptiveExporter.array(
			manifest.get("sourceFiles"), "sourceFiles");
		String[] sourcePaths = {
			"client/world-builder-source/orsc/WorldBuilderInstalledClientProfile.java",
			"client/world-builder-source/orsc/WorldBuilderTerrainBootstrap.java"
		};
		String[] destinations = {
			"src/orsc/WorldBuilderInstalledClientProfile.java",
			"src/orsc/WorldBuilderTerrainBootstrap.java"
		};
		String[] roles = {
			"runtime-compatibility-client-source-profile",
			"runtime-compatibility-client-source-bootstrap"
		};
		if (sourceFiles.size() != sourcePaths.length) throw problem(SOURCE,
			"Installed client source upgrade file set is unsupported.");
		for (int index = 0; index < sourcePaths.length; index++) {
			Map<String,Object> entry = WorldBuilderAdaptiveExporter.object(
				sourceFiles.get(index), "sourceFile");
			String source = WorldBuilderAdaptiveExporter.string(
				entry, "sourceRelativePath");
			String destination = WorldBuilderAdaptiveExporter.string(
				entry, "destinationRelativePath");
			String expectedHash = WorldBuilderAdaptiveExporter.string(entry, "sha256");
			if (!sourcePaths[index].equals(source)
				|| !destinations[index].equals(destination)
				|| !expectedHash.matches("[0-9a-f]{64}")) {
				throw problem(SOURCE,
					"Installed client source upgrade file set is unsupported.");
			}
			Path verifiedSource = WorldBuilderAdaptiveExporter.requireFile(
				project.projectRoot, "working/runtime/" + source,
				"installed client source upgrade file");
			if (!expectedHash.equals(WorldBuilderHashes.sha256(verifiedSource))) {
				throw problem("working/runtime/" + source,
					"Installed client source upgrade hash does not match its manifest.");
			}
			WorldBuilderRuntimeCompatibility.appendReplacement(
				project, target, roles[index], clientRoot + "/" + destination,
				"working/runtime/" + source,
				WorldBuilderRuntimeCompatibility.transactionContent(
					"client-source-" + index, ".java"), actions);
		}
	}

	private static void appendTransforms(
		Path target, String clientRoot, Map<String,Object> manifest,
		List<WorldBuilderAdaptiveMutationProfile.Action> actions)
		throws IOException, WorldBuilderContractException {
		List<?> transforms = WorldBuilderAdaptiveExporter.array(
			manifest.get("semanticTransforms"), "semanticTransforms");
		String[] transformIds = {
			"world-builder-installed-terrain-bootstrap-v1",
			"world-builder-installed-login-world-bootstrap-v1"
		};
		String[] transformPaths = {
			"src/orsc/graphics/three/World.java", "src/orsc/mudclient.java"
		};
		if (transforms.size() != transformIds.length) throw problem(SOURCE,
			"Installed client semantic transform set is unsupported.");
		for (int index = 0; index < transformIds.length; index++) {
			Map<String,Object> entry = WorldBuilderAdaptiveExporter.object(
				transforms.get(index), "semanticTransform");
			if (!transformIds[index].equals(
					WorldBuilderAdaptiveExporter.string(entry, "transformId"))
				|| !transformPaths[index].equals(
					WorldBuilderAdaptiveExporter.string(
						entry, "destinationRelativePath"))) {
				throw problem(SOURCE,
					"Installed client semantic transform set is unsupported.");
			}
			appendTransform(target, clientRoot + "/" + transformPaths[index],
				transformIds[index], index == 0, actions);
		}
	}

	private static void appendTransform(
		Path target, String destination, String transformId, boolean world,
		List<WorldBuilderAdaptiveMutationProfile.Action> actions)
		throws IOException, WorldBuilderContractException {
		Path path = WorldBuilderAdaptiveMutationProfile.safeDestination(
			target, destination);
		if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
			|| Files.isSymbolicLink(path)) {
			throw WorldBuilderRuntimeCompatibility.problem(destination,
				"Target client source required by the installed upgrade is missing or unsafe.",
				"Restore the target client source tree and retry Import.");
		}
		byte[] beforeBytes = Files.readAllBytes(path);
		String original = new String(beforeBytes, StandardCharsets.UTF_8);
		if (!java.util.Arrays.equals(beforeBytes,
				original.getBytes(StandardCharsets.UTF_8))) {
			throw WorldBuilderRuntimeCompatibility.problem(destination,
				"Target client source is not valid UTF-8.",
				"Convert " + destination + " to UTF-8 and retry Import.");
		}
		String rendered = world
			? renderTerrainBootstrap(original, destination)
			: renderLoginWorldBootstrap(original, destination);
		byte[] afterBytes = rendered.getBytes(StandardCharsets.UTF_8);
		WorldBuilderAdaptiveMutationProfile.FileState before =
			WorldBuilderAdaptiveMutationProfile.FileState.present(
				beforeBytes.length, WorldBuilderHashes.sha256(beforeBytes));
		WorldBuilderAdaptiveMutationProfile.FileState after =
			WorldBuilderAdaptiveMutationProfile.FileState.present(
				afterBytes.length, WorldBuilderHashes.sha256(afterBytes));
		if (before.size == after.size && before.sha256.equals(after.sha256)) return;
		actions.add(new WorldBuilderAdaptiveMutationProfile.Action(
			world ? "runtime-compatibility-client-source-world-transform"
				: "runtime-compatibility-client-source-login-transform", destination,
			before, after, WorldBuilderRuntimeCompatibility.transactionContent(
				"client-source-" + transformId, ".java"),
			"backups/{transaction}/before/" + destination, true, afterBytes));
	}

	static String renderTerrainBootstrap(String original, String destination)
		throws WorldBuilderContractException {
		int oldCount = occurrences(PROFILE_NATIVE_ONLY, original);
		int newCount = occurrences(BOOTSTRAP_NATIVE_ONLY, original);
		int profileImportCount = occurrences(PROFILE_IMPORT, original);
		int bootstrapImportCount = occurrences(TERRAIN_BOOTSTRAP_IMPORT, original);
		int oldIdentityCount = occurrences(PROFILE_MAP_IDENTITY, original);
		int newIdentityCount = occurrences(BOOTSTRAP_MAP_IDENTITY, original);
		if (oldCount + newCount < 3
			|| profileImportCount != 1
			|| bootstrapImportCount > 1
			|| oldIdentityCount + newIdentityCount != 1) throw sourceProblem(
			destination,
			"Target World.java does not match the supported native terrain bootstrap boundary.",
			"Restore the target client source or integrate the installed terrain bootstrap once.");
		String newline = original.contains("\r\n") ? "\r\n" : "\n";
		String rendered = original;
		if (bootstrapImportCount == 0) {
			Matcher profileImport = PROFILE_IMPORT.matcher(rendered);
			if (!profileImport.find()) throw sourceProblem(destination,
				"Target World.java has no unambiguous World Builder client profile import.",
				"Restore the target client source and retry Import.");
			rendered = profileImport.replaceFirst(
				Matcher.quoteReplacement(profileImport.group()
					+ newline + "import orsc.WorldBuilderTerrainBootstrap;"));
		}
		rendered = PROFILE_NATIVE_ONLY.matcher(rendered).replaceAll(
			"WorldBuilderTerrainBootstrap.isNativeOnly()");
		rendered = PROFILE_MAP_IDENTITY.matcher(rendered).replaceAll(
			"WorldBuilderTerrainBootstrap.mapIdentity()");
		if (occurrences(PROFILE_NATIVE_ONLY, rendered) != 0
			|| occurrences(BOOTSTRAP_NATIVE_ONLY, rendered) != oldCount + newCount
			|| occurrences(TERRAIN_BOOTSTRAP_IMPORT, rendered) != 1
			|| occurrences(PROFILE_MAP_IDENTITY, rendered) != 0
			|| occurrences(BOOTSTRAP_MAP_IDENTITY, rendered) != 1) {
			throw sourceProblem(destination,
				"Target World.java terrain bootstrap upgrade was not exact.",
				"Restore the target client source and retry Import.");
		}
		return rendered;
	}

	static String renderLoginWorldBootstrap(String original, String destination)
		throws WorldBuilderContractException {
		int oldCount = occurrences(PROFILE_LOGIN_WORLD, original);
		int newCount = occurrences(BOOTSTRAP_LOGIN_WORLD, original);
		if (oldCount + newCount != 1) throw sourceProblem(destination,
			"Target mudclient.java does not match the supported login-world bootstrap boundary.",
			"Restore the target client source or integrate the installed login bootstrap once.");
		String rendered = PROFILE_LOGIN_WORLD.matcher(original).replaceAll(
			"return !WorldBuilderTerrainBootstrap.isNativeOnly();");
		if (occurrences(PROFILE_LOGIN_WORLD, rendered) != 0
			|| occurrences(BOOTSTRAP_LOGIN_WORLD, rendered) != 1) {
			throw sourceProblem(destination,
				"Target mudclient.java login bootstrap upgrade was not exact.",
				"Restore the target client source and retry Import.");
		}
		return rendered;
	}

	private static Pattern profileCall(String method) {
		return Pattern.compile(
			"WorldBuilderClientProfile\\s*\\.\\s*current\\s*\\(\\s*\\)\\s*"
				+ "\\.\\s*" + method + "\\s*\\(\\s*\\)");
	}

	private static Pattern bootstrapCall(String method) {
		return Pattern.compile(
			"WorldBuilderTerrainBootstrap\\s*\\.\\s*" + method
				+ "\\s*\\(\\s*\\)");
	}

	private static int occurrences(Pattern pattern, String value) {
		int count = 0;
		Matcher matcher = pattern.matcher(value);
		while (matcher.find()) count++;
		return count;
	}

	private static WorldBuilderContractException problem(
		String source, String message) {
		return WorldBuilderRuntimeCompatibility.problem(source, message,
			"Restore the exact verified project runtime.");
	}

	private static WorldBuilderContractException sourceProblem(
		String source, String message, String nextStep) {
		return WorldBuilderRuntimeCompatibility.problem(
			source, message, nextStep);
	}
}
