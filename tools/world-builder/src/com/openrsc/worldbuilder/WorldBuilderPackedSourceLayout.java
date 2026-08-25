package com.openrsc.worldbuilder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Exact source-path profile for equivalent OpenRSC packed client cache layouts.
 * Source paths vary; project/runtime destinations remain canonical.
 */
final class WorldBuilderPackedSourceLayout {
	static final String CANONICAL_VIDEO_ROOT = "Client_Base/Cache/video";
	static final List<String> VIDEO_ROOTS = Collections.unmodifiableList(Arrays.asList(
		CANONICAL_VIDEO_ROOT, "client/Cache/video", "Cache/video"));
	private static final List<String> REQUIRED_FILES = Collections.unmodifiableList(
		Arrays.asList("Custom_Landscape.orsc", "library.orsc", "models.orsc",
			"Authentic_Sprites.orsc", "Custom_Sprites.osar", "spritepacks/Menus.osar"));

	final String profileId;
	final String videoRoot;

	private WorldBuilderPackedSourceLayout(String profileId, String videoRoot) {
		this.profileId = profileId;
		this.videoRoot = videoRoot;
	}

	static WorldBuilderPackedSourceLayout canonical() {
		return forRoot(CANONICAL_VIDEO_ROOT);
	}

	static WorldBuilderPackedSourceLayout select(WorldBuilderReadOnlyTarget target)
		throws WorldBuilderContractException {
		List<WorldBuilderPackedSourceLayout> recognizable =
			new ArrayList<WorldBuilderPackedSourceLayout>();
		for (String root : VIDEO_ROOTS) {
			WorldBuilderPackedSourceLayout layout = forRoot(root);
			if (layout.hasAnyEvidence(target)) recognizable.add(layout);
		}
		if (recognizable.size() > 1) {
			List<String> roots = new ArrayList<String>();
			for (WorldBuilderPackedSourceLayout layout : recognizable) {
				roots.add(layout.videoRoot);
			}
			throw WorldBuilderReadOnlyTarget.problem(
				WorldBuilderErrorCodes.AMBIGUOUS_CONFIGURATION, "target-root",
				"More than one client cache root contains packed-layout evidence: "
					+ roots + ".",
				"Remove inactive/duplicate cache evidence or use one explicit truthful descriptor.");
		}
		return recognizable.isEmpty() ? canonical() : recognizable.get(0);
	}

	String path(String insideVideoRoot) {
		return videoRoot + "/" + insideVideoRoot;
	}

	String canonicalPath(String insideVideoRoot) {
		return CANONICAL_VIDEO_ROOT + "/" + insideVideoRoot;
	}

	List<WorldBuilderReadOnlyTarget.FileState> materializeCanonicalAliases(Path copiedTarget)
		throws IOException, WorldBuilderContractException {
		if (CANONICAL_VIDEO_ROOT.equals(videoRoot)) return Collections.emptyList();
		WorldBuilderReadOnlyTarget source = WorldBuilderReadOnlyTarget.open(copiedTarget);
		List<WorldBuilderReadOnlyTarget.FileState> generated =
			new ArrayList<WorldBuilderReadOnlyTarget.FileState>();
		for (String inside : REQUIRED_FILES) {
			String from = path(inside);
			String to = canonicalPath(inside);
			WorldBuilderReadOnlyTarget.FileState expected = source.requiredState(
				"source-layout." + role(inside), from);
			Path destination = copiedTarget.resolve(to).normalize();
			if (!destination.startsWith(copiedTarget.toAbsolutePath().normalize())
				|| Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
				throw WorldBuilderReadOnlyTarget.problem(
					WorldBuilderErrorCodes.UNSAFE_PATH, to,
					"Canonical project-local source path is unsafe or already exists.",
					"Discard the unpublished project stage and retry from one source layout.");
			}
			Files.createDirectories(destination.getParent());
			Files.copy(source.requiredFile(from), destination,
				StandardCopyOption.COPY_ATTRIBUTES);
			if (Files.size(destination) != expected.size
				|| !WorldBuilderHashes.sha256(destination).equals(expected.sha256)) {
				throw WorldBuilderReadOnlyTarget.problem(
					WorldBuilderErrorCodes.SOURCE_CORRUPT, from,
					"Canonical project-local source copy differs from selected cache evidence.",
					"Discard the unpublished project stage and retry from stable source bytes.");
			}
		}
		WorldBuilderReadOnlyTarget normalized = WorldBuilderReadOnlyTarget.open(copiedTarget);
		for (String inside : REQUIRED_FILES) {
			generated.add(normalized.requiredState(
				role(inside), canonicalPath(inside)));
		}
		Collections.sort(generated);
		return generated;
	}

	private boolean hasAnyEvidence(WorldBuilderReadOnlyTarget target)
		throws WorldBuilderContractException {
		for (String inside : REQUIRED_FILES) if (target.exists(path(inside))) return true;
		return false;
	}

	private static WorldBuilderPackedSourceLayout forRoot(String root) {
		String id = CANONICAL_VIDEO_ROOT.equals(root)
			? "openrsc-source-client-base-cache-v1"
			: "client/Cache/video".equals(root)
				? "openrsc-packaged-client-cache-v1"
				: "openrsc-flat-client-cache-v1";
		return new WorldBuilderPackedSourceLayout(id, root);
	}

	private static String role(String inside) {
		if ("Custom_Landscape.orsc".equals(inside)) return "client-terrain";
		if ("library.orsc".equals(inside)) return "client-asset.library";
		if ("models.orsc".equals(inside)) return "content.asset.model";
		if ("Authentic_Sprites.orsc".equals(inside)) {
			return "content.asset.sprite.authentic";
		}
		if ("Custom_Sprites.osar".equals(inside)) {
			return "content.asset.sprite.custom";
		}
		if ("spritepacks/Menus.osar".equals(inside)) {
			return "content.asset.spritepack";
		}
		throw new AssertionError(inside);
	}
}
