package com.openrsc.worldbuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Applies the bounded installed-client bootstrap while preserving target content. */
final class WorldBuilderInstalledClientSourceUpgrade {
	static final String SOURCE =
		"working/runtime/server/conf/world-builder/installed-client-source-upgrade-v5.json";
	static final String ID = "world-builder-installed-client-source-upgrade-v5";
	private static final String JSON_SOURCE =
		"server/lib/json-20190722.jar";
	static final String JSON_DESTINATION =
		"PC_Client/lib/json-20190722.jar";
	static final String JSON_ROLE =
		"runtime-compatibility-client-json-dependency";
	private static final String SOURCE_ROLE_PREFIX =
		"runtime-compatibility-client-source-upgrade";
	private static final String[] SOURCE_PATHS = {
		"client/world-builder-source/orsc/AdaptiveWorldBuilderClientSession.java",
		"client/world-builder-source/orsc/ProjectContentBundle.java",
		"client/world-builder-source/orsc/ProjectNpcAnimationRegistry.java",
		"client/world-builder-source/orsc/NativeLayeredTerrainChunk.java",
		"client/world-builder-source/orsc/NativeLayeredTerrainPacketDecoder.java",
		"client/world-builder-source/com/openrsc/client/model/Tile.java",
		"client/world-builder-source/orsc/WorldBuilderClientProfile.java",
		"client/world-builder-source/orsc/WorldBuilderInstalledClientProfile.java",
		"client/world-builder-source/orsc/WorldBuilderTerrainBootstrap.java",
		"client/world-builder-source/orsc/WorldBuilderTerrainOverlay.java",
		"client/world-builder-source/orsc/graphics/three/World.java"
	};
	private static final String[] DESTINATIONS = {
		"src/orsc/AdaptiveWorldBuilderClientSession.java",
		"src/orsc/ProjectContentBundle.java",
		"src/orsc/ProjectNpcAnimationRegistry.java",
		"src/orsc/NativeLayeredTerrainChunk.java",
		"src/orsc/NativeLayeredTerrainPacketDecoder.java",
		"src/com/openrsc/client/model/Tile.java",
		"src/orsc/WorldBuilderClientProfile.java",
		"src/orsc/WorldBuilderInstalledClientProfile.java",
		"src/orsc/WorldBuilderTerrainBootstrap.java",
		"src/orsc/WorldBuilderTerrainOverlay.java",
		"src/orsc/graphics/three/World.java"
	};
	private static final String[] POLICIES = {
		"add-or-exact", "add-or-exact", "add-or-exact",
		"replace-supported-historical", "replace-supported-historical",
		"replace-supported-historical", "replace-supported-historical",
		"add-or-exact", "add-or-exact", "add-or-exact",
		"replace-supported-historical"
	};
	private static final String[] TRANSFORM_IDS = {
		"world-builder-installed-login-world-bootstrap-v2",
		"world-builder-unsigned-uniform-elevation-v1"
	};
	private static final String[] TRANSFORM_PATHS = {
		"src/orsc/mudclient.java",
		"src/orsc/NativeLayeredTerrainSnapshot.java"
	};
	private static final String[] TRANSFORM_ROLES = {
		"runtime-compatibility-client-source-login-transform",
		"runtime-compatibility-client-source-native-uniform-elevation-transform"
	};
	private static final Pattern PROFILE_LOGIN_WORLD = Pattern.compile(
		"return\\s*!\\s*WorldBuilderClientProfile\\s*\\.\\s*current\\s*"
			+ "\\(\\s*\\)\\s*\\.\\s*isStrictAdaptiveTerrain\\s*"
			+ "\\(\\s*\\)\\s*;");
	private static final Pattern BOOTSTRAP_LOGIN_WORLD = Pattern.compile(
		"return\\s*!\\s*WorldBuilderTerrainBootstrap\\s*\\.\\s*"
			+ "isNativeOnly\\s*\\(\\s*\\)\\s*;");
	private static final Pattern INLINE_BOOTSTRAP_LOGIN_GUARD = Pattern.compile(
		"if\\s*\\(\\s*WorldBuilderTerrainBootstrap\\s*\\.\\s*"
			+ "isNativeOnly\\s*\\(\\s*\\)\\s*\\)\\s*\\{");
	private static final Pattern PRE_BOOTSTRAP_LOGIN_METHOD = Pattern.compile(
		"(?m)^([\\t ]*private\\s+void\\s+renderLoginScreenViewports\\s*"
			+ "\\(\\s*int\\s+[A-Za-z_$][A-Za-z0-9_$]*\\s*\\)\\s*\\{"
			+ "[\\t ]*\\r?\\n[\\t ]*try\\s*\\{)");

	private static final Pattern LEGACY_UNIFORM_ELEVATION = Pattern.compile(
		"tile\\s*\\.\\s*groundElevation\\s*=\\s*"
			+ "\\(\\s*byte\\s*\\)\\s*elevation\\s*;");
	private static final Pattern UNSIGNED_UNIFORM_ELEVATION = Pattern.compile(
		"tile\\s*\\.\\s*groundElevation\\s*=\\s*elevation\\s*;");

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
		if (WorldBuilderAdaptiveExporter.integer(manifest, "schemaVersion") != 5L
			|| !"world-builder-installed-client-source-upgrade".equals(
				WorldBuilderAdaptiveExporter.string(manifest, "manifestType"))
			|| !ID.equals(WorldBuilderAdaptiveExporter.string(manifest, "upgradeId"))
			|| !"world-builder-installed-client-profile-v1".equals(
				WorldBuilderAdaptiveExporter.string(manifest, "clientBootstrapId"))
			|| !"atomic-compile-target-client-before-run".equals(
				WorldBuilderAdaptiveExporter.string(manifest, "buildPolicy"))) {
			throw problem(SOURCE,
				"Installed client source upgrade identity is unsupported.");
		}
		appendSourceFiles(project, target, clientRoot, manifest, actions);
		appendDependencies(project, target, manifest, actions);
		appendTransforms(target, clientRoot, manifest, actions);
	}

	private static void appendDependencies(
		WorldBuilderAdaptiveProjectLifecycle.VerifiedProject project,
		Path target, Map<String,Object> manifest,
		List<WorldBuilderAdaptiveMutationProfile.Action> actions)
		throws IOException, WorldBuilderContractException {
		List<?> dependencies = WorldBuilderAdaptiveExporter.array(
			manifest.get("dependencies"), "dependencies");
		if (dependencies.size() != 1) throw problem(SOURCE,
			"Installed client dependency set is unsupported.");
		Map<String,Object> dependency = WorldBuilderAdaptiveExporter.object(
			dependencies.get(0), "dependency");
		String source = WorldBuilderAdaptiveExporter.string(
			dependency, "sourceRelativePath");
		String destination = WorldBuilderAdaptiveExporter.string(
			dependency, "destinationRelativePath");
		String expectedHash = WorldBuilderAdaptiveExporter.string(
			dependency, "sha256");
		if (!JSON_SOURCE.equals(source) || !JSON_DESTINATION.equals(destination)
			|| !"add-or-exact".equals(WorldBuilderAdaptiveExporter.string(
				dependency, "replacementPolicy"))
			|| !expectedHash.matches("[0-9a-f]{64}")) {
			throw problem(SOURCE,
				"Installed client dependency set is unsupported.");
		}
		Path verifiedSource = WorldBuilderAdaptiveExporter.requireFile(
			project.projectRoot, "working/runtime/" + source,
			"installed client dependency");
		if (!expectedHash.equals(WorldBuilderHashes.sha256(verifiedSource))) {
			throw problem("working/runtime/" + source,
				"Installed client dependency hash does not match its manifest.");
		}
		Path targetDependency = WorldBuilderAdaptiveMutationProfile.safeDestination(
			target, destination);
		if (Files.exists(targetDependency, LinkOption.NOFOLLOW_LINKS)) {
			if (!Files.isRegularFile(targetDependency, LinkOption.NOFOLLOW_LINKS)
				|| Files.isSymbolicLink(targetDependency)) {
				throw sourceProblem(destination,
					"Target client dependency destination is unsafe.",
					"Restore a regular target dependency file and retry Import.");
			}
			if (expectedHash.equals(WorldBuilderHashes.sha256(targetDependency))) return;
			throw sourceProblem(destination,
				"Target contains a different JSON dependency at the managed client boundary.",
				"Preserve that dependency separately or restore the supported pinned dependency before Import.");
		}
		WorldBuilderRuntimeCompatibility.appendReplacement(
			project, target, JSON_ROLE, destination,
			"working/runtime/" + source,
			WorldBuilderRuntimeCompatibility.transactionContent(
				"client-json-dependency", ".jar"), actions);
	}

	private static void appendSourceFiles(
		WorldBuilderAdaptiveProjectLifecycle.VerifiedProject project,
		Path target, String clientRoot, Map<String,Object> manifest,
		List<WorldBuilderAdaptiveMutationProfile.Action> actions)
		throws IOException, WorldBuilderContractException {
		List<?> sourceFiles = WorldBuilderAdaptiveExporter.array(
			manifest.get("sourceFiles"), "sourceFiles");
		if (sourceFiles.size() != SOURCE_PATHS.length) throw problem(SOURCE,
			"Installed client source upgrade file set is unsupported.");
		for (int index = 0; index < SOURCE_PATHS.length; index++) {
			Map<String,Object> entry = WorldBuilderAdaptiveExporter.object(
				sourceFiles.get(index), "sourceFile");
			String source = WorldBuilderAdaptiveExporter.string(
				entry, "sourceRelativePath");
			String destination = WorldBuilderAdaptiveExporter.string(
				entry, "destinationRelativePath");
			String expectedHash = WorldBuilderAdaptiveExporter.string(entry, "sha256");
			String policy = WorldBuilderAdaptiveExporter.string(
				entry, "replacementPolicy");
			if (!SOURCE_PATHS[index].equals(source)
				|| !DESTINATIONS[index].equals(destination)
				|| !POLICIES[index].equals(policy)
				|| !expectedHash.matches("[0-9a-f]{64}")) {
				throw problem(SOURCE,
					"Installed client source upgrade file set is unsupported.");
			}
			List<String> supportedBefore = new ArrayList<String>();
			if ("replace-supported-historical".equals(policy)) {
				List<?> supported = WorldBuilderAdaptiveExporter.array(
					entry.get("supportedBeforeSha256"), "supportedBeforeSha256");
				if (supported.isEmpty() || supported.size() > 8) {
					throw problem(SOURCE,
						"Installed client historical source boundary is unsupported.");
				}
				for (Object rawHash : supported) {
					if (!(rawHash instanceof String)
						|| !((String)rawHash).matches("[0-9a-f]{64}")
						|| supportedBefore.contains(rawHash)) {
						throw problem(SOURCE,
							"Installed client historical source boundary is unsupported.");
					}
					supportedBefore.add((String)rawHash);
				}
			} else if (entry.containsKey("supportedBeforeSha256")) {
				throw problem(SOURCE,
					"Installed client additive source boundary is unsupported.");
			}
			Path verifiedSource = WorldBuilderAdaptiveExporter.requireFile(
				project.projectRoot, "working/runtime/" + source,
				"installed client source upgrade file");
			if (!expectedHash.equals(WorldBuilderHashes.sha256(verifiedSource))) {
				throw problem("working/runtime/" + source,
					"Installed client source upgrade hash does not match its manifest.");
			}
			Path targetSource = WorldBuilderAdaptiveMutationProfile.safeDestination(
				target, clientRoot + "/" + destination);
			boolean present = Files.exists(targetSource, LinkOption.NOFOLLOW_LINKS);
			if (present && (!Files.isRegularFile(targetSource, LinkOption.NOFOLLOW_LINKS)
				|| Files.isSymbolicLink(targetSource))) {
				throw sourceProblem(clientRoot + "/" + destination,
					"Target client source upgrade destination is unsafe.",
					"Restore a regular target client source file and retry Import.");
			}
			String targetHash = present ? WorldBuilderHashes.sha256(targetSource) : "";
			if (expectedHash.equals(targetHash)) continue;
			if ("add-or-exact".equals(policy) && present) throw sourceProblem(
				clientRoot + "/" + destination,
				"Target contains a customized file at an additive World Builder runtime boundary.",
				"Preserve that source separately or restore the supported runtime source before Import.");
			if ("replace-supported-historical".equals(policy)
				&& (!present || !supportedBefore.contains(targetHash))) {
				throw sourceProblem(clientRoot + "/" + destination,
					"Target client runtime source is neither current nor a supported historical revision.",
					"Upgrade from a recognized source revision or integrate the current runtime source once.");
			}
			WorldBuilderRuntimeCompatibility.appendReplacement(
				project, target, SOURCE_ROLE_PREFIX + "-" + index,
				clientRoot + "/" + destination,
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
		if (transforms.size() != TRANSFORM_IDS.length) throw problem(SOURCE,
			"Installed client semantic transform set is unsupported.");
		for (int index = 0; index < TRANSFORM_IDS.length; index++) {
			Map<String,Object> entry = WorldBuilderAdaptiveExporter.object(
				transforms.get(index), "semanticTransform");
			if (!TRANSFORM_IDS[index].equals(
					WorldBuilderAdaptiveExporter.string(entry, "transformId"))
				|| !TRANSFORM_PATHS[index].equals(WorldBuilderAdaptiveExporter.string(
					entry, "destinationRelativePath"))) {
				throw problem(SOURCE,
					"Installed client semantic transform set is unsupported.");
			}
			appendTransform(target, clientRoot + "/" + TRANSFORM_PATHS[index],
				index, actions);
		}
	}

	private static void appendTransform(
		Path target, String destination, int transformIndex,
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
		String rendered = transformIndex == 0
			? renderLoginWorldBootstrap(original, destination)
			: renderUnsignedUniformElevation(original, destination);
		byte[] afterBytes = rendered.getBytes(StandardCharsets.UTF_8);
		WorldBuilderAdaptiveMutationProfile.FileState before =
			WorldBuilderAdaptiveMutationProfile.FileState.present(
				beforeBytes.length, WorldBuilderHashes.sha256(beforeBytes));
		WorldBuilderAdaptiveMutationProfile.FileState after =
			WorldBuilderAdaptiveMutationProfile.FileState.present(
				afterBytes.length, WorldBuilderHashes.sha256(afterBytes));
		if (before.size == after.size && before.sha256.equals(after.sha256)) return;
		actions.add(new WorldBuilderAdaptiveMutationProfile.Action(
			TRANSFORM_ROLES[transformIndex], destination,
			before, after, WorldBuilderRuntimeCompatibility.transactionContent(
				"client-source-" + TRANSFORM_IDS[transformIndex], ".java"),
			"backups/{transaction}/before/" + destination, true, afterBytes));
	}

	static String renderUnsignedUniformElevation(String original, String destination)
		throws WorldBuilderContractException {
		int legacy = occurrences(LEGACY_UNIFORM_ELEVATION, original);
		int unsigned = occurrences(UNSIGNED_UNIFORM_ELEVATION, original);
		if (legacy == 0 && unsigned == 1) return original;
		if (legacy != 1 || unsigned != 0) throw sourceProblem(destination,
			"Target NativeLayeredTerrainSnapshot.java does not match the supported uniform elevation materialization boundary.",
			"Restore the recognized target client source and retry Import.");
		String rendered = LEGACY_UNIFORM_ELEVATION.matcher(original).replaceFirst(
			"tile.groundElevation = elevation;");
		if (occurrences(LEGACY_UNIFORM_ELEVATION, rendered) != 0
			|| occurrences(UNSIGNED_UNIFORM_ELEVATION, rendered) != 1) {
			throw sourceProblem(destination,
				"Target uniform elevation upgrade was not exact.",
				"Restore the recognized target client source and retry Import.");
		}
		return rendered;
	}

	static String renderLoginWorldBootstrap(String original, String destination)
		throws WorldBuilderContractException {
		int oldCount = occurrences(PROFILE_LOGIN_WORLD, original);
		int newCount = occurrences(BOOTSTRAP_LOGIN_WORLD, original);
		if (oldCount == 0 && newCount == 1) return original;
		if (oldCount == 0 && newCount == 0
			&& occurrences(INLINE_BOOTSTRAP_LOGIN_GUARD, original) == 1) {
			return original;
		}
		if (oldCount == 0 && newCount == 0) {
			Matcher method = PRE_BOOTSTRAP_LOGIN_METHOD.matcher(original);
			if (!method.find()) throw sourceProblem(destination,
				"Target mudclient.java has no unambiguous legacy login-world renderer.",
				"Restore the recognized target client source and retry Import.");
			String methodStart = method.group(1);
			if (method.find()) throw sourceProblem(destination,
				"Target mudclient.java has multiple legacy login-world renderers.",
				"Restore the recognized target client source and retry Import.");
			String newline = original.contains("\r\n") ? "\r\n" : "\n";
			String indent = methodStart.startsWith("\t") ? "\t\t" : "        ";
			String nested = indent + "\t";
			String block = methodStart + newline
				+ indent + "if (WorldBuilderTerrainBootstrap.isNativeOnly()) {" + newline
				+ nested + "this.getSurface().blackScreen(true);" + newline
				+ nested + "for (int index = 0; index < 3; index++) {" + newline
				+ nested + "\tthis.getSurface().storeSpriteVert(" + newline
				+ nested + "\t\tindex, 0, 0, getGameWidth(), halfGameHeight() + 33);" + newline
				+ nested + "}" + newline
				+ nested + "return;" + newline
				+ indent + "}";
			String rendered = method.replaceFirst(Matcher.quoteReplacement(block));
			if (occurrences(INLINE_BOOTSTRAP_LOGIN_GUARD, rendered) != 1) {
				throw sourceProblem(destination,
					"Target mudclient.java pre-bootstrap login upgrade was not exact.",
					"Restore the target client source and retry Import.");
			}
			return rendered;
		}
		if (oldCount != 1 || newCount != 0) throw sourceProblem(destination,
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

	private static int occurrences(Pattern pattern, String value) {
		int count = 0;
		Matcher matcher = pattern.matcher(value);
		while (matcher.find()) count++;
		return count;
	}

	static int sourceIndex(String destination) {
		for (int index = 0; index < DESTINATIONS.length; index++) {
			if (destination.endsWith("/" + DESTINATIONS[index])) return index;
		}
		return -1;
	}

	static int sourceIndexForRole(String role) {
		if (!role.startsWith(SOURCE_ROLE_PREFIX + "-")) return -1;
		try {
			int index = Integer.parseInt(role.substring(
				SOURCE_ROLE_PREFIX.length() + 1));
			return index >= 0 && index < DESTINATIONS.length ? index : -1;
		} catch (NumberFormatException invalid) {
			return -1;
		}
	}

	static int transformIndexForRole(String role) {
		for (int index = 0; index < TRANSFORM_ROLES.length; index++) {
			if (TRANSFORM_ROLES[index].equals(role)) return index;
		}
		return -1;
	}

	static int transformIndex(String destination) {
		for (int index = 0; index < TRANSFORM_PATHS.length; index++) {
			if (destination.endsWith("/" + TRANSFORM_PATHS[index])) return index;
		}
		return -1;
	}

	static String transformId(int index) {
		return index >= 0 && index < TRANSFORM_IDS.length
			? TRANSFORM_IDS[index] : "";
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
