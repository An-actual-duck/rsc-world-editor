package com.openrsc.worldbuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Atomic Phase 2 conversion from an immutable packed evidence copy. */
final class WorldBuilderPackedConverter {
	private static final String OPERATION = "convert-packed";
	private static final String ZERO_HASH =
		"0000000000000000000000000000000000000000000000000000000000000000";

	interface Observer {
		void observe(String milestone, Path stage) throws Exception;
	}

	private static final Observer NO_OP_OBSERVER = new Observer() {
		@Override
		public void observe(String milestone, Path stage) {
			// Production conversion does not interfere with atomic staging.
		}
	};

	private final Observer observer;
	private final WorldBuilderPackedConversionModel.PlacementIdFactory idFactory;

	WorldBuilderPackedConverter() {
		this(NO_OP_OBSERVER, null);
	}

	WorldBuilderPackedConverter(Observer observer,
		WorldBuilderPackedConversionModel.PlacementIdFactory idFactory) {
		this.observer = observer == null ? NO_OP_OBSERVER : observer;
		this.idFactory = idFactory;
	}

	Result convert(Path sourceRoot, Path discoveryReport, Path requestedOutput)
		throws IOException, WorldBuilderContractException {
		WorldBuilderPackedConversionSource source =
			WorldBuilderPackedConversionSource.open(sourceRoot, discoveryReport);
		Path output = validateOutput(source, requestedOutput);

		WorldBuilderTargetCapability capability =
			WorldBuilderTargetCapability.read(source.target);
		WorldBuilderPackedLayoutAdapter.requireCapability(capability);
		WorldBuilderAdaptiveConfiguration.Selection selection =
			WorldBuilderAdaptiveConfiguration.select(
				source.target, capability, source.selectedConfigurationRole);
		WorldBuilderAdaptiveConfiguration configuration = selection.selected;
		if (!configuration.relativePath.equals(source.selectedConfigurationRelativePath)
			|| !configuration.sha256.equals(source.selectedConfigurationSha256)) {
			throw blocked("Selected configuration does not match the immutable discovery report.",
				"Recreate the source copy and report from one stable discovery pass.");
		}
		WorldBuilderCompatibilityEvidence common =
			WorldBuilderCompatibilityEvidence.inspect(source.target, capability, configuration);
		new WorldBuilderPackedLayoutAdapter().inspect(
			source.target, capability, source.selectedConfigurationRole);
		WorldBuilderPackedConversionModel model = idFactory == null
			? WorldBuilderPackedConversionModel.read(source, configuration, common.definitions)
			: WorldBuilderPackedConversionModel.read(
				source, configuration, common.definitions, idFactory);
		source.reverify();
		Map<String,Object> plan = plan(source, capability, configuration);
		WorldBuilderAdaptiveContracts.validateParsed(
			WorldBuilderAdaptiveContracts.Kind.CONVERSION_PLAN, plan);

		Path parent = output.getParent();
		Path stage = parent.resolve("." + output.getFileName()
			+ ".staging-" + UUID.randomUUID()).normalize();
		try {
			Files.createDirectory(stage);
			observe("stage-created", stage);
			Path planPath = stage.resolve("conversion-plan.json");
			writeJson(planPath, plan);
			model.writePackage(stage.resolve("package"), source.sourceFingerprintSha256);
			observe("package-written", stage);

			WorldBuilderReadOnlyTarget stageTarget = WorldBuilderReadOnlyTarget.open(stage);
			WorldBuilderGenericLayeredPackage validated =
				WorldBuilderGenericLayeredPackage.inspect(
					stageTarget, "package", "converted", common.definitions);
			if (validated.levelCount != model.levels.size()
				|| validated.terrainCount != model.terrain.size()
				|| validated.placementSemantics.size() != model.placementSemantics.size()
				|| !validated.placementSemantics.equals(model.placementSemantics)) {
				throw blocked("Generic layered validation found a conversion parity delta.",
					"Inspect the package writer and packed normalization before retrying.");
			}
			observe("package-validated", stage);

			String planSha256 = WorldBuilderHashes.sha256(planPath);
			Map<String,Object> report = report(
				model, planSha256, validated.fingerprintSha256);
			WorldBuilderAdaptiveContracts.validateParsed(
				WorldBuilderAdaptiveContracts.Kind.CONVERSION_REPORT, report);
			Path reportPath = stage.resolve("conversion-report.json");
			writeJson(reportPath, report);
			WorldBuilderAdaptiveContracts.read(
				WorldBuilderAdaptiveContracts.Kind.CONVERSION_PLAN, planPath);
			WorldBuilderAdaptiveContracts.read(
				WorldBuilderAdaptiveContracts.Kind.CONVERSION_REPORT, reportPath);
			requireSelfFingerprint(report, "reportFingerprintSha256");
			requireExactOutput(stage, validated.files.size() + 2);
			source.reverify();
			observe("before-publish", stage);
			source.reverify();
			try {
				Files.move(stage, output, StandardCopyOption.ATOMIC_MOVE);
			} catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
				throw blocked("The output filesystem does not support atomic directory publication.",
					"Choose an output parent that supports same-filesystem atomic moves.");
			}
			return new Result(output, source.sourceFingerprintSha256, planSha256,
				WorldBuilderHashes.sha256(reportPathFor(output)),
				validated.fingerprintSha256, model.terrain.size(), model.placements.size());
		} catch (IOException failure) {
			deleteTree(stage);
			throw failure;
		} catch (WorldBuilderContractException failure) {
			deleteTree(stage);
			throw failure;
		} catch (RuntimeException failure) {
			deleteTree(stage);
			throw failure;
		} catch (Exception callbackFailure) {
			deleteTree(stage);
			throw new WorldBuilderContractException(WorldBuilderErrorCodes.CONVERSION_BLOCKED,
				OPERATION, "output-stage", false,
				"Conversion staging was interrupted before atomic publication.",
				"Retry after resolving the injected or environmental staging failure.",
				callbackFailure);
		}
	}

	private static Path validateOutput(
		WorldBuilderPackedConversionSource source, Path requested)
		throws WorldBuilderContractException {
		if (requested == null) {
			throw blocked("No conversion output directory was supplied.",
				"Supply a new output path outside the immutable source root.");
		}
		Path output = requested.toAbsolutePath().normalize();
		Path parent = output.getParent();
		if (parent == null || Files.isSymbolicLink(parent)
			|| !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)
			|| output.startsWith(source.target.root)
			|| Files.exists(output, LinkOption.NOFOLLOW_LINKS)) {
			throw blocked("Conversion output is unsafe, already exists, or is inside source evidence.",
				"Choose a new path under an existing real directory outside the source root.");
		}
		return output;
	}

	private static Map<String,Object> plan(
		WorldBuilderPackedConversionSource source,
		WorldBuilderTargetCapability capability,
		WorldBuilderAdaptiveConfiguration configuration)
		throws WorldBuilderContractException {
		Map<String,Object> plan = new LinkedHashMap<String,Object>();
		plan.put("schemaVersion", Long.valueOf(1L));
		plan.put("manifestType", "world-builder-conversion-plan");
		plan.put("toolVersion", WorldBuilderAdaptiveDiscoveryReport.TOOL_VERSION);
		plan.put("adapterId", WorldBuilderPackedLayoutAdapter.ID);
		plan.put("conversionProfileId",
			WorldBuilderPackedTerrainCodec.CONVERSION_PROFILE_ID);
		plan.put("sourceFingerprintSha256", source.sourceFingerprintSha256);
		plan.put("definitionFingerprintSha256", capability.definitionCatalogSha256);
		plan.put("coordinateMappingId",
			WorldBuilderPackedCoordinateCodec.COORDINATE_MAPPING_ID);
		plan.put("placementCompositionProfileId",
			WorldBuilderPackedConversionModel.PLACEMENT_COMPOSITION_PROFILE_ID);
		plan.put("outputPackageSchemaId",
			WorldBuilderPackedConversionModel.OUTPUT_PACKAGE_SCHEMA_ID);
		plan.put("outputEncodingVersion", Long.valueOf(
			WorldBuilderPackedConversionModel.OUTPUT_ENCODING_VERSION));
		plan.put("inputs", source.inputDocuments());
		List<Object> order = new ArrayList<Object>(configuration.placements.size());
		for (WorldBuilderAdaptiveConfiguration.PlacementSource placement
			: configuration.placements) {
			order.add("placement." + placement.role);
		}
		plan.put("placementSourceOrder", order);
		plan.put("planFingerprintSha256", ZERO_HASH);
		bindSelfFingerprint(plan, "planFingerprintSha256");
		return plan;
	}

	private static Map<String,Object> report(
		WorldBuilderPackedConversionModel model,
		String planSha256,
		String outputFingerprintSha256) throws WorldBuilderContractException {
		Map<String,Object> report = new LinkedHashMap<String,Object>();
		report.put("schemaVersion", Long.valueOf(1L));
		report.put("manifestType", "world-builder-conversion-report");
		report.put("planSha256", planSha256);
		report.put("outputFingerprintSha256", outputFingerprintSha256);
		Map<String,Object> terrain = new LinkedHashMap<String,Object>();
		terrain.put("entriesRead", Long.valueOf(model.terrain.size()));
		terrain.put("entriesWritten", Long.valueOf(model.terrain.size()));
		terrain.put("reverseMatched", Long.valueOf(model.reverseMatched));
		terrain.put("reverseMismatches", Long.valueOf(0L));
		report.put("terrain", terrain);
		report.put("placements", new ArrayList<Object>(model.placementSummaries));
		report.put("decisions", new ArrayList<Object>(model.decisions));
		Map<String,Object> validation = new LinkedHashMap<String,Object>();
		validation.put("unknownCount", Long.valueOf(0L));
		validation.put("lossCount", Long.valueOf(0L));
		validation.put("approximationCount", Long.valueOf(0L));
		validation.put("repairCount", Long.valueOf(0L));
		validation.put("parityDeltaCount", Long.valueOf(0L));
		report.put("validation", validation);
		report.put("issues", new ArrayList<Object>());
		report.put("blocked", Boolean.FALSE);
		report.put("reportFingerprintSha256", ZERO_HASH);
		bindSelfFingerprint(report, "reportFingerprintSha256");
		return report;
	}

	private static void bindSelfFingerprint(Map<String,Object> document, String field)
		throws WorldBuilderContractException {
		document.put(field, ZERO_HASH);
		String canonical;
		try {
			canonical = WorldBuilderJsonDocuments.canonical(document);
		} catch (IllegalArgumentException malformed) {
			throw new WorldBuilderContractException(WorldBuilderErrorCodes.MALFORMED_JSON,
				OPERATION, "generated-contract", false,
				"Generated conversion contract contains invalid JSON text.",
				"Inspect adapter provenance and identifier generation.", malformed);
		}
		document.put(field, WorldBuilderHashes.sha256(
			canonical.getBytes(StandardCharsets.UTF_8)));
	}

	private static void requireSelfFingerprint(Map<String,Object> document, String field)
		throws WorldBuilderContractException {
		Object raw = document.get(field);
		if (!(raw instanceof String)) throw blocked("Generated report has no fingerprint.",
			"Inspect the conversion report generator.");
		String supplied = (String)raw;
		document.put(field, ZERO_HASH);
		String calculated = WorldBuilderHashes.sha256(
			WorldBuilderJsonDocuments.canonical(document).getBytes(StandardCharsets.UTF_8));
		document.put(field, supplied);
		if (!supplied.equals(calculated)) throw blocked(
			"Generated conversion report fingerprint failed verification.",
			"Inspect the report generator before publishing any package.");
	}

	private void observe(String milestone, Path stage) throws Exception {
		observer.observe(milestone, stage);
	}

	private static void writeJson(Path path, Map<String,Object> document)
		throws IOException {
		Files.write(path, WorldBuilderJsonDocuments.pretty(document)
			.getBytes(StandardCharsets.UTF_8));
	}

	private static void requireExactOutput(final Path stage, int expectedFiles)
		throws IOException, WorldBuilderContractException {
		final List<String> files = new ArrayList<String>();
		Files.walkFileTree(stage, new SimpleFileVisitor<Path>() {
			@Override
			public FileVisitResult preVisitDirectory(Path directory,
				BasicFileAttributes attributes) throws IOException {
				if (!attributes.isDirectory() || Files.isSymbolicLink(directory)) {
					throw new IOException("unsafe output directory");
				}
				return FileVisitResult.CONTINUE;
			}

			@Override
			public FileVisitResult visitFile(Path file, BasicFileAttributes attributes)
				throws IOException {
				if (!attributes.isRegularFile() || Files.isSymbolicLink(file)) {
					throw new IOException("unsafe output file");
				}
				files.add(stage.relativize(file).toString().replace('\\', '/'));
				return FileVisitResult.CONTINUE;
			}
		});
		Collections.sort(files);
		if (files.size() != expectedFiles
			|| !files.contains("conversion-plan.json")
			|| !files.contains("conversion-report.json")) {
			throw blocked("Staged conversion output contains missing or untracked files.",
				"Inspect the atomic package writer and retry from immutable evidence.");
		}
	}

	private static Path reportPathFor(Path output) {
		return output.resolve("conversion-report.json");
	}

	private static void deleteTree(Path root) {
		if (root == null || !Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return;
		try {
			Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
				@Override
				public FileVisitResult visitFile(Path file, BasicFileAttributes attributes)
					throws IOException {
					Files.deleteIfExists(file);
					return FileVisitResult.CONTINUE;
				}

				@Override
				public FileVisitResult postVisitDirectory(Path directory, IOException failure)
					throws IOException {
					if (failure != null) throw failure;
					Files.deleteIfExists(directory);
					return FileVisitResult.CONTINUE;
				}
			});
		} catch (IOException ignored) {
			// Best-effort cleanup never permits publication or masks the original failure.
		}
	}

	private static WorldBuilderContractException blocked(String message, String nextStep) {
		return new WorldBuilderContractException(WorldBuilderErrorCodes.CONVERSION_BLOCKED,
			OPERATION, "output", false, message, nextStep);
	}

	static final class Result {
		final Path output;
		final String sourceFingerprintSha256;
		final String planSha256;
		final String reportSha256;
		final String outputFingerprintSha256;
		final int terrainCount;
		final int placementCount;

		Result(Path output, String sourceFingerprintSha256, String planSha256,
			String reportSha256, String outputFingerprintSha256,
			int terrainCount, int placementCount) {
			this.output = output;
			this.sourceFingerprintSha256 = sourceFingerprintSha256;
			this.planSha256 = planSha256;
			this.reportSha256 = reportSha256;
			this.outputFingerprintSha256 = outputFingerprintSha256;
			this.terrainCount = terrainCount;
			this.placementCount = placementCount;
		}

		String toJson() {
			Map<String,Object> value = new LinkedHashMap<String,Object>();
			value.put("status", "converted");
			value.put("sourceFingerprintSha256", sourceFingerprintSha256);
			value.put("planSha256", planSha256);
			value.put("reportSha256", reportSha256);
			value.put("outputFingerprintSha256", outputFingerprintSha256);
			value.put("terrainCount", Long.valueOf(terrainCount));
			value.put("placementCount", Long.valueOf(placementCount));
			return WorldBuilderJsonDocuments.pretty(value);
		}
	}
}
