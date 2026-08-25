package com.openrsc.worldbuilder;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Canonical diagnostic proving effective placement IDs resolve to captured
 * definitions and, where the native archive is inspectable, scenery models.
 */
final class WorldBuilderContentReconciliation {
	static final String PROJECT_RELATIVE_PATH =
		"diagnostics/content-reconciliation-v1.json";
	private static final String TYPE = "world-builder-content-reconciliation";
	private static final String ZERO_HASH =
		"0000000000000000000000000000000000000000000000000000000000000000";
	private static final int MAX_ARCHIVE_ENTRIES = 8192;

	private static final List<String> FAMILIES = Collections.unmodifiableList(
		Arrays.asList("floor", "boundary", "ground-item", "npc", "scenery"));

	private WorldBuilderContentReconciliation() {
	}

	static void write(Path projectStage,
		WorldBuilderAdaptiveRuntimePreparer.SourceRuntime runtime,
		WorldBuilderGenericLayeredPackage layered,
		WorldBuilderProjectContentBundle.Bundle bundle)
		throws IOException, WorldBuilderContractException {
		Map<String,List<Integer>> required = layered.requiredDefinitionIds();
		List<Object> issues = new ArrayList<Object>();
		List<Object> families = new ArrayList<Object>();
		for (String family : FAMILIES) {
			List<Integer> catalog = catalogIds(bundle.definitionCatalog,
				catalogField(family));
			List<Integer> used = required.get(family);
			if (!catalog.containsAll(used)) {
				throw problem("Definition reconciliation found a placed ID outside the "
					+ "captured catalog for " + family + ".");
			}
			families.add(family(family, catalog, used, bundle));
		}

		ArchiveIndex models = inspectModelArchive(bundle.pathForRole("asset.model"));
		Map<String,Object> modelEvidence = bundle.evidenceForRole("asset.model");
		String packagedModelArchiveSha256 = WorldBuilderHashes.sha256(
			runtime.verifiedSourcePath("client/Cache/video/models.orsc"));
		boolean exactPackagedModelArchive = packagedModelArchiveSha256.equals(
			modelEvidence.get("sha256"));
		if (!"indexed".equals(models.status)) {
			issues.add(issue("MODEL_ARCHIVE_UNVERIFIED", "scenery", -1,
				"asset.model", models.detail,
				"Keep the exact model archive; a later format adapter can add entry-level verification."));
		}
		WorldBuilderSceneryDefinitionCatalog targetDefinitions;
		WorldBuilderSceneryDefinitionCatalog packagedDefinitions = null;
		try {
			targetDefinitions = WorldBuilderSceneryDefinitionCatalog.read(
				bundle.pathForRole("definition.scenery"));
		} catch (IOException malformed) {
			throw problem("Placed scenery definitions do not have complete parseable "
				+ "collision and model semantics: " + malformed.getMessage() + ".");
		}
		try {
			packagedDefinitions = WorldBuilderSceneryDefinitionCatalog.read(
				runtime.verifiedSourcePath("server/conf/server/defs/GameObjectDef.xml"));
		} catch (Exception unavailable) {
			issues.add(issue("PACKAGED_SCENERY_BASELINE_UNVERIFIED", "scenery", -1,
				"definition.scenery",
				"The exact runtime scenery baseline could not be compared.",
				"Restore the locked runtime inventory before relying on replacement detection."));
		}

		List<Object> scenery = new ArrayList<Object>();
		for (Integer id : required.get("scenery")) {
			WorldBuilderSceneryDefinitionCatalog.Definition target;
			try {
				target = targetDefinitions.require(id.intValue());
			} catch (IOException missing) {
				throw problem("Placed scenery ID " + id
					+ " is absent from the parsed scenery definition catalog.");
			}
			WorldBuilderSceneryDefinitionCatalog.Definition packaged = null;
			if (packagedDefinitions != null) {
				try {
					packaged = packagedDefinitions.require(id.intValue());
				} catch (IOException absentFromPackaged) {
					packaged = null;
				}
			}
			String resolution;
			String modelHash = "";
			if (target.modelName.isEmpty() || "na".equalsIgnoreCase(target.modelName)) {
				resolution = "generated-or-unspecified";
				issues.add(issue("SCENERY_MODEL_UNSPECIFIED", "scenery", id.intValue(),
					"definition.scenery",
					"Placed scenery has no concrete objectModel dependency: " + target.name + ".",
					"Confirm the definition intentionally uses generated/default geometry or name an OB3 model."));
			} else if (exactPackagedModelArchive && packaged != null
				&& target.modelName.equalsIgnoreCase(packaged.modelName)) {
				resolution = "packaged-runtime";
			} else {
				modelHash = modelHash(target.modelName + ".ob3");
				if ("indexed".equals(models.status)
					&& models.hashes.contains(Integer.valueOf(filenameHash(
						target.modelName + ".ob3")))) {
					resolution = "project-archive";
				} else if ("indexed".equals(models.status)) {
					resolution = "missing";
					issues.add(issue("SCENERY_MODEL_MISSING", "scenery", id.intValue(),
						"asset.model",
						"Placed scenery model is absent from models.orsc: "
							+ target.modelName + ".ob3.",
						"Add the exact OB3 model or correct the scenery definition before editing."));
				} else {
					resolution = "archive-unverified";
				}
			}
			scenery.add(scenery(id.intValue(), target, modelHash, resolution));
		}
		Collections.sort(issues, new java.util.Comparator<Object>() {
			@Override public int compare(Object left, Object right) {
				return issueKey(left).compareTo(issueKey(right));
			}
		});

		Map<String,Object> archive = new LinkedHashMap<String,Object>();
		archive.put("role", "asset.model");
		archive.put("size", modelEvidence.get("size"));
		archive.put("sha256", modelEvidence.get("sha256"));
		archive.put("indexStatus", models.status);
		archive.put("entryCount", Long.valueOf(models.entryCount));

		Map<String,Object> report = new LinkedHashMap<String,Object>();
		report.put("schemaVersion", Long.valueOf(1L));
		report.put("manifestType", TYPE);
		report.put("contentBundleFingerprintSha256",
			bundle.bundleFingerprintSha256);
		report.put("outputPackageFingerprintSha256", layered.fingerprintSha256);
		report.put("families", families);
		report.put("modelArchive", archive);
		report.put("sceneryModels", scenery);
		report.put("status", issues.isEmpty() ? "matched" : "matched-with-warnings");
		report.put("issues", issues);
		report.put("reconciliationFingerprintSha256", ZERO_HASH);
		bindFingerprint(report);
		WorldBuilderAdaptiveContracts.validateParsed(
			WorldBuilderAdaptiveContracts.Kind.CONTENT_RECONCILIATION, report);
		Path output = projectStage.resolve(PROJECT_RELATIVE_PATH);
		Files.write(output, WorldBuilderJsonDocuments.pretty(report)
			.getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE_NEW);
		WorldBuilderAdaptiveContracts.read(
			WorldBuilderAdaptiveContracts.Kind.CONTENT_RECONCILIATION, output);
	}

	private static Map<String,Object> family(String family, List<Integer> catalog,
		List<Integer> required, WorldBuilderProjectContentBundle.Bundle bundle) {
		Map<String,Object> value = new LinkedHashMap<String,Object>();
		value.put("family", family);
		value.put("catalogDefinitionCount", Long.valueOf(catalog.size()));
		value.put("catalogDefinitionIdsSha256", idsHash(family + "-catalog", catalog));
		value.put("requiredPlacementDefinitionIds", numbers(required));
		value.put("requiredPlacementDefinitionIdsSha256",
			idsHash(family + "-required", required));
		value.put("resolvedDefinitionCount", Long.valueOf(required.size()));
		value.put("resolvedDefinitionIdsSha256",
			idsHash(family + "-required", required));
		List<String> definitionRoles = definitionRoles(family);
		List<String> assetRoles = assetRoles(family);
		value.put("definitionRoles", new ArrayList<String>(definitionRoles));
		List<Object> assets = new ArrayList<Object>();
		for (String role : assetRoles) {
			Map<String,Object> source = bundle.evidenceForRole(role);
			Map<String,Object> evidence = new LinkedHashMap<String,Object>();
			evidence.put("role", role);
			evidence.put("size", source.get("size"));
			evidence.put("sha256", source.get("sha256"));
			assets.add(evidence);
		}
		value.put("assets", assets);
		value.put("status", "matched");
		return value;
	}

	private static List<String> definitionRoles(String family) {
		if ("floor".equals(family)) return Arrays.asList("definition.tile");
		if ("boundary".equals(family)) return Arrays.asList("definition.boundary");
		if ("ground-item".equals(family)) return Arrays.asList("definition.item.base",
			"definition.item.custom", "definition.item.patch", "definition.item.world");
		if ("npc".equals(family)) return Arrays.asList("definition.npc.base",
			"definition.npc.custom", "definition.npc.patch", "definition.npc.world");
		return Arrays.asList("definition.scenery");
	}

	private static List<String> assetRoles(String family) {
		List<String> result;
		if ("floor".equals(family) || "boundary".equals(family)) {
			result = Arrays.asList("asset.sprite.custom");
		} else if ("scenery".equals(family)) result = Arrays.asList(
			"asset.library", "asset.model", "asset.sprite.custom");
		else result = Arrays.asList("asset.library", "asset.sprite.authentic",
			"asset.sprite.custom", "asset.spritepack");
		result = new ArrayList<String>(result);
		Collections.sort(result);
		return result;
	}

	private static String catalogField(String family) {
		if ("floor".equals(family)) return "tiles";
		if ("boundary".equals(family)) return "boundaries";
		if ("ground-item".equals(family)) return "groundItems";
		if ("npc".equals(family)) return "npcs";
		return "scenery";
	}

	private static List<Integer> catalogIds(Map<String,Object> catalog, String field) {
		List<Integer> result = new ArrayList<Integer>();
		for (Object raw : (List<?>)catalog.get(field)) {
			result.add(Integer.valueOf((int)((Long)raw).longValue()));
		}
		return Collections.unmodifiableList(result);
	}

	private static List<Object> numbers(List<Integer> values) {
		List<Object> result = new ArrayList<Object>();
		for (Integer value : values) result.add(Long.valueOf(value.longValue()));
		return result;
	}

	private static String idsHash(String domain, List<Integer> values) {
		java.security.MessageDigest digest = WorldBuilderHashes.newDigest();
		WorldBuilderHashes.updateText(digest, "world-builder-content-reconciliation-v1");
		WorldBuilderHashes.updateText(digest, domain);
		for (Integer value : values) WorldBuilderHashes.updateText(
			digest, Integer.toString(value.intValue()));
		return WorldBuilderHashes.hex(digest.digest());
	}

	private static Map<String,Object> scenery(int id,
		WorldBuilderSceneryDefinitionCatalog.Definition definition,
		String hash, String resolution) {
		Map<String,Object> value = new LinkedHashMap<String,Object>();
		value.put("sceneryId", Long.valueOf(id));
		value.put("name", definition.name);
		value.put("modelName", definition.modelName);
		value.put("modelFileHash", hash);
		value.put("resolution", resolution);
		return value;
	}

	private static Map<String,Object> issue(String code, String family, int id,
		String role, String message, String nextStep) {
		Map<String,Object> value = new LinkedHashMap<String,Object>();
		value.put("code", code);
		value.put("family", family);
		value.put("definitionId", Long.valueOf(id));
		value.put("assetRole", role);
		value.put("message", message);
		value.put("nextStep", nextStep);
		return value;
	}

	@SuppressWarnings("unchecked")
	private static String issueKey(Object raw) {
		Map<String,Object> issue = (Map<String,Object>)raw;
		return issue.get("code") + "\u0000" + issue.get("family") + "\u0000"
			+ String.format("%06d", ((Long)issue.get("definitionId")).longValue())
			+ "\u0000" + issue.get("assetRole");
	}

	private static ArchiveIndex inspectModelArchive(Path path) {
		try (SeekableByteChannel input = Files.newByteChannel(path, StandardOpenOption.READ)) {
			long fileSize = input.size();
			if (fileSize < 8L) return ArchiveIndex.unverified("malformed",
				"models.orsc is too short to contain a native archive index.");
			ByteBuffer header = ByteBuffer.allocate(8);
			readFully(input, header); header.flip();
			long expanded = uint24(header); long stored = uint24(header);
			if (stored + 6L != fileSize) return ArchiveIndex.unverified("malformed",
				"models.orsc outer sizes do not match its exact file length.");
			if (expanded != stored) return ArchiveIndex.unverified("compressed-unverified",
				"models.orsc uses an outer compressed payload not inspected by this adapter.");
			int count = header.getShort() & 0xffff;
			if (count < 1 || count > MAX_ARCHIVE_ENTRIES) return ArchiveIndex.unverified(
				"malformed", "models.orsc entry count is outside 1..8192.");
			long directoryBytes = (long)count * 10L;
			if (2L + directoryBytes > stored) return ArchiveIndex.unverified(
				"malformed", "models.orsc directory exceeds its payload.");
			ByteBuffer directory = ByteBuffer.allocate((int)directoryBytes);
			readFully(input, directory); directory.flip();
			Set<Integer> hashes = new HashSet<Integer>();
			long payloadBytes = 0L;
			for (int index = 0; index < count; index++) {
				int hash = directory.getInt();
				long entryExpanded = uint24(directory);
				long entryStored = uint24(directory);
				if (!hashes.add(Integer.valueOf(hash)) || entryExpanded < 1L
					|| entryStored < 1L || payloadBytes + entryStored > stored) {
					return ArchiveIndex.unverified("malformed",
						"models.orsc has a duplicate hash or invalid entry size.");
				}
				payloadBytes += entryStored;
			}
			if (2L + directoryBytes + payloadBytes != stored) {
				return ArchiveIndex.unverified("malformed",
					"models.orsc indexed payload sizes do not close exactly.");
			}
			return new ArchiveIndex("indexed", count, hashes,
				"Native model archive index is structurally complete.");
		} catch (Exception failure) {
			return ArchiveIndex.unverified("malformed",
				"models.orsc could not be read as a bounded native archive index.");
		}
	}

	private static void readFully(SeekableByteChannel input, ByteBuffer buffer)
		throws IOException {
		while (buffer.hasRemaining()) {
			if (input.read(buffer) < 0) throw new IOException("unexpected archive EOF");
		}
	}

	private static long uint24(ByteBuffer input) {
		return ((long)input.get() & 0xffL) << 16
			| ((long)input.get() & 0xffL) << 8
			| ((long)input.get() & 0xffL);
	}

	private static int filenameHash(String name) {
		int result = 0;
		String upper = name.toUpperCase(java.util.Locale.ROOT);
		for (int index = 0; index < upper.length(); index++) {
			result = result * 61 + upper.charAt(index) - 32;
		}
		return result;
	}

	private static String modelHash(String name) {
		return String.format("%08x", filenameHash(name));
	}

	private static void bindFingerprint(Map<String,Object> report) {
		report.put("reconciliationFingerprintSha256", ZERO_HASH);
		report.put("reconciliationFingerprintSha256", WorldBuilderHashes.sha256(
			WorldBuilderJsonDocuments.canonical(report).getBytes(StandardCharsets.UTF_8)));
	}

	private static WorldBuilderContractException problem(String message) {
		return new WorldBuilderContractException(WorldBuilderErrorCodes.DEFINITION_MISMATCH,
			"content-reconciliation", PROJECT_RELATIVE_PATH, false, message,
			"Recreate the project from one stable complete content set.");
	}

	private static final class ArchiveIndex {
		final String status;
		final int entryCount;
		final Set<Integer> hashes;
		final String detail;
		ArchiveIndex(String status, int entryCount, Set<Integer> hashes, String detail) {
			this.status = status;
			this.entryCount = entryCount;
			this.hashes = Collections.unmodifiableSet(new TreeSet<Integer>(hashes));
			this.detail = detail;
		}
		static ArchiveIndex unverified(String status, String detail) {
			return new ArchiveIndex(status, 0, Collections.<Integer>emptySet(), detail);
		}
	}
}
