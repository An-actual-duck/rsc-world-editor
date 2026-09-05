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
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
	private final int cumulativeRecordLimit;

	WorldBuilderPackedConverter() {
		this(NO_OP_OBSERVER, null,
			WorldBuilderPackedConversionModel.DEFAULT_CUMULATIVE_RECORD_LIMIT);
	}

	WorldBuilderPackedConverter(Observer observer,
		WorldBuilderPackedConversionModel.PlacementIdFactory idFactory) {
		this(observer, idFactory,
			WorldBuilderPackedConversionModel.DEFAULT_CUMULATIVE_RECORD_LIMIT);
	}

	WorldBuilderPackedConverter(Observer observer,
		WorldBuilderPackedConversionModel.PlacementIdFactory idFactory,
		int cumulativeRecordLimit) {
		this.observer = observer == null ? NO_OP_OBSERVER : observer;
		this.idFactory = idFactory;
		this.cumulativeRecordLimit = cumulativeRecordLimit;
	}

	/** Read-only semantic preview used by the current-runtime upgrade transaction. */
	Inspection inspect(Path sourceRoot, Path discoveryReport)
		throws IOException, WorldBuilderContractException {
		Path temporary = Files.createTempDirectory("world-builder-packed-preview-");
		try {
			Path output = temporary.resolve("conversion");
			Result converted = convert(sourceRoot, discoveryReport, output);
			normalizePrivateModes(output);
			Map<String,Object> conversionPlan;
			Map<String,Object> manifest;
			try {
				conversionPlan = WorldBuilderJsonDocuments.readObject(
					output.resolve("conversion-plan.json"));
				manifest = WorldBuilderJsonDocuments.readObject(output.resolve("package/manifest.json"));
			} catch (WorldBuilderDiscoveryException malformed) {
				throw blocked("Generated conversion plan is malformed.",
					"Inspect the deterministic converter before retrying preview.");
			}
			requireSelfFingerprint(conversionPlan, "planFingerprintSha256");
			return new Inspection(converted.sourceFingerprintSha256,
				(String)conversionPlan.get("planFingerprintSha256"), converted.planSha256,
				converted.reportSha256, converted.reconciliationSha256,
				converted.outputFingerprintSha256,
				converted.terrainCount, converted.placementCount,
				outputInventory(output, "migration/output/map/conversion"), manifest,
				WorldBuilderHashes.sha256(output.resolve("package/manifest.json")));
		} finally {
			deleteTree(temporary);
		}
	}

	static void normalizePrivateModes(final Path root) throws IOException {
		Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
			@Override public FileVisitResult visitFile(Path file,
				BasicFileAttributes attributes) throws IOException {
				if (!attributes.isRegularFile() || Files.isSymbolicLink(file))
					throw new IOException("unsafe packed conversion output file");
				Files.setPosixFilePermissions(file, EnumSet.of(
					PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
				return FileVisitResult.CONTINUE;
			}
		});
	}

	static List<Object> outputInventory(final Path root, final String prefix)
		throws IOException {
		final List<Map<String,Object>> records = new ArrayList<Map<String,Object>>();
		Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
			@Override public FileVisitResult preVisitDirectory(Path directory,
				BasicFileAttributes attributes) throws IOException {
				if (!attributes.isDirectory() || Files.isSymbolicLink(directory))
					throw new IOException("unsafe packed conversion output directory");
				return FileVisitResult.CONTINUE;
			}
			@Override public FileVisitResult visitFile(Path file,
				BasicFileAttributes attributes) throws IOException {
				if (!attributes.isRegularFile() || Files.isSymbolicLink(file))
					throw new IOException("unsafe packed conversion output file");
				String relative = root.relativize(file).toString().replace('\\', '/');
				Map<String,Object> record = new LinkedHashMap<String,Object>();
				record.put("relativePath", prefix + "/" + relative);
				record.put("size", Long.valueOf(attributes.size()));
				record.put("sha256", WorldBuilderHashes.sha256(file));
				record.put("mode", "0600");
				records.add(record);
				return FileVisitResult.CONTINUE;
			}
		});
		Collections.sort(records, new java.util.Comparator<Map<String,Object>>() {
			@Override public int compare(Map<String,Object> left, Map<String,Object> right) {
				return ((String)left.get("relativePath")).compareTo(
					(String)right.get("relativePath"));
			}
		});
		List<Object> result = new ArrayList<Object>(records.size());
		result.addAll(records);
		return result;
	}

	Result convert(Path sourceRoot, Path discoveryReport, Path requestedOutput)
		throws IOException, WorldBuilderContractException {
		return convertInternal(sourceRoot, discoveryReport, requestedOutput, null, false);
	}

	/** Internal Phase 3 conversion confined to one unpublished project stage. */
	Result convertForProject(Path sourceRoot, Path discoveryReport,
		Path requestedOutput, Path projectStage)
		throws IOException, WorldBuilderContractException {
		return convertInternal(sourceRoot, discoveryReport, requestedOutput, projectStage,
			false);
	}

	/** Internal conversion from the distinct legacy input namespace. */
	Result convertForMigrationProject(Path sourceRoot, Path discoveryReport,
		Path requestedOutput, Path projectStage)
		throws IOException, WorldBuilderContractException {
		return convertInternal(sourceRoot, discoveryReport, requestedOutput, projectStage,
			true);
	}

	private Result convertInternal(Path sourceRoot, Path discoveryReport,
		Path requestedOutput, Path projectStage, boolean migrationSource)
		throws IOException, WorldBuilderContractException {
		WorldBuilderPackedConversionSource source;
		try {
			source = projectStage == null
				? WorldBuilderPackedConversionSource.open(sourceRoot, discoveryReport)
				: migrationSource
					? WorldBuilderPackedConversionSource.openForMigrationProject(
						sourceRoot, discoveryReport, projectStage)
					: WorldBuilderPackedConversionSource.openForProject(
						sourceRoot, discoveryReport, projectStage);
		} catch (WorldBuilderContractException refusal) {
			throw asConversionRefusal(refusal);
		}
		Path output = validateOutput(source, requestedOutput, projectStage);
		Prepared prepared = prepare(source);
		WorldBuilderTargetCapability capability = prepared.capability;
		WorldBuilderAdaptiveConfiguration configuration = prepared.configuration;
		WorldBuilderCompatibilityEvidence common = prepared.common;
		WorldBuilderPackedConversionModel model = prepared.model;
		Map<String,Object> plan = plan(source, capability, configuration);
		WorldBuilderAdaptiveContracts.validateParsed(
			WorldBuilderAdaptiveContracts.Kind.CONVERSION_PLAN, plan);
		requireSelfFingerprint(plan, "planFingerprintSha256");

		Path parent = output.getParent();
		Path stage = parent.resolve("." + output.getFileName()
			+ ".staging-" + UUID.randomUUID()).normalize();
		try {
			Files.createDirectory(stage);
			observe("stage-created", stage);
			Path planPath = stage.resolve("conversion-plan.json");
			writeJson(planPath, plan);
			WorldBuilderPackedConversionModel.PackageExpectation expectedPackage =
				model.writePackage(stage.resolve("package"), source.sourceFingerprintSha256);
			observe("package-written", stage);

			WorldBuilderReadOnlyTarget stageTarget = WorldBuilderReadOnlyTarget.open(stage);
			WorldBuilderGenericLayeredPackage validated =
				WorldBuilderGenericLayeredPackage.inspect(
					stageTarget, "package", "converted", common.definitions);
			model.requireExactPackage(
				stageTarget, "package", validated, expectedPackage);
			if (validated.levelCount != model.levels.size()
				|| validated.terrainCount != model.terrain.size()
				|| validated.placementSemantics.size() != model.placementSemantics.size()
				|| !validated.placementSemantics.equals(model.placementSemantics)) {
				throw blocked("Generic layered validation found a conversion parity delta.",
					"Inspect the package writer and packed normalization before retrying.");
			}
			observe("package-validated", stage);

			Map<String,Object> reconciliation =
				WorldBuilderDiscoveryReconciliation.packed(model, validated,
					WorldBuilderPackedLayoutAdapter.ID,
					source.sourceFingerprintSha256,
					expectedPackage.fingerprintSha256);
			Path reconciliationPath = stage.resolve(
				WorldBuilderDiscoveryReconciliation.FILE_NAME);
			writeJson(reconciliationPath, reconciliation);
			WorldBuilderAdaptiveContracts.read(
				WorldBuilderAdaptiveContracts.Kind.DISCOVERY_RECONCILIATION,
				reconciliationPath);
			String reconciliationSha256 = WorldBuilderHashes.sha256(reconciliationPath);

			String planSha256 = WorldBuilderHashes.sha256(planPath);
			Map<String,Object> report = report(
				model, planSha256, expectedPackage.fingerprintSha256);
			WorldBuilderAdaptiveContracts.validateParsed(
				WorldBuilderAdaptiveContracts.Kind.CONVERSION_REPORT, report);
			Path reportPath = stage.resolve("conversion-report.json");
			writeJson(reportPath, report);
			WorldBuilderAdaptiveContracts.read(
				WorldBuilderAdaptiveContracts.Kind.CONVERSION_PLAN, planPath);
			WorldBuilderAdaptiveContracts.read(
				WorldBuilderAdaptiveContracts.Kind.CONVERSION_REPORT, reportPath);
			requireSelfFingerprint(report, "reportFingerprintSha256");
			String reportSha256 = WorldBuilderHashes.sha256(reportPath);
			requireExactOutput(stage, expectedPackage.files.size() + 3);
			source.reverify();
			observe("before-publish", stage);
			source.reverify();
			requireFinalStage(stage, common.definitions, model, expectedPackage,
				planSha256, reportSha256, reconciliationSha256,
				expectedPackage.files.size() + 3);
			try {
				Files.move(stage, output, StandardCopyOption.ATOMIC_MOVE);
			} catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
				throw blocked("The output filesystem does not support atomic directory publication.",
					"Choose an output parent that supports same-filesystem atomic moves.");
			}
			return new Result(output, source.sourceFingerprintSha256, planSha256,
				reportSha256, reconciliationSha256,
				expectedPackage.fingerprintSha256,
				model.terrain.size(), model.placements.size());
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

	private Prepared prepare(WorldBuilderPackedConversionSource source)
		throws WorldBuilderContractException {
		try {
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
			if (!"packed".equals(configuration.representation)) {
				throw blocked("Selected configuration is not a packed representation.",
					"Use the generic layered adoption path for an existing layered package.");
			}
			WorldBuilderCompatibilityEvidence common =
				WorldBuilderCompatibilityEvidence.inspect(
					source.target, capability, configuration);
			List<WorldBuilderReadOnlyTarget.FileState> files =
				new ArrayList<WorldBuilderReadOnlyTarget.FileState>(common.files);
			WorldBuilderReadOnlyTarget.FileState serverTerrain =
				source.target.requiredState(
					"server-terrain", configuration.serverMapRelativePath);
			WorldBuilderReadOnlyTarget.FileState clientTerrain =
				source.target.requiredState(
					"client-terrain", configuration.clientMapRelativePath);
			if (serverTerrain.size != clientTerrain.size
				|| !serverTerrain.sha256.equals(clientTerrain.sha256)) {
				throw new WorldBuilderContractException(WorldBuilderErrorCodes.MAP_MISMATCH,
					OPERATION, configuration.serverMapRelativePath, false,
					"Server and client packed terrain archives are not byte-identical.",
					"Copy one exact active archive for both declared map roles.");
			}
			files.add(serverTerrain);
			files.add(clientTerrain);
			for (WorldBuilderAdaptiveConfiguration.PlacementSource placement
				: configuration.placements) {
				files.add(source.target.requiredState(
					"placement." + placement.role, placement.relativePath));
			}
			WorldBuilderGenericLayeredAdapter.validateInventoryAndRoles(
				files, capability, WorldBuilderTargetCapability.RELATIVE_PATH);
			WorldBuilderPackedConversionModel model =
				WorldBuilderPackedConversionModel.read(source, configuration,
					common.definitions, idFactory, cumulativeRecordLimit,
					WorldBuilderPackedFallbackEvidence.CAPABILITY_ID.equals(
						capability.capabilityId));
			source.reverify();
			return new Prepared(capability, configuration, common, model);
		} catch (WorldBuilderContractException refusal) {
			throw asConversionRefusal(refusal);
		}
	}

	private static Path validateOutput(
		WorldBuilderPackedConversionSource source, Path requested, Path projectStage)
		throws WorldBuilderContractException {
		if (requested == null) {
			throw blocked("No conversion output directory was supplied.",
				"Supply a new output path outside the immutable source root.");
		}
		Path output = requested.toAbsolutePath().normalize();
		Path parent = output.getParent();
		if (parent == null || Files.isSymbolicLink(parent)
			|| !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)
			|| Files.exists(output, LinkOption.NOFOLLOW_LINKS)) {
			throw blocked("Conversion output is unsafe, already exists, or is inside source evidence.",
				"Choose a new path under an existing real directory outside source and target roots.");
		}
		Path canonicalParent;
		try {
			canonicalParent = parent.toRealPath();
		} catch (IOException failure) {
			throw new WorldBuilderContractException(WorldBuilderErrorCodes.UNSAFE_PATH,
				OPERATION, "output", false,
				"Conversion output parent identity cannot be resolved safely.",
				"Choose a stable existing real directory outside source and target roots.", failure);
		}
		Path canonicalOutput = canonicalParent.resolve(output.getFileName()).normalize();
		if (projectStage != null) {
			Path canonicalStage;
			try {
				canonicalStage = projectStage.toRealPath();
			} catch (IOException failure) {
				throw new WorldBuilderContractException(WorldBuilderErrorCodes.UNSAFE_PATH,
					OPERATION, "project-stage", false,
					"Project conversion stage identity cannot be resolved safely.",
					"Use the unique real unpublished project staging directory.", failure);
			}
			if (!canonicalOutput.startsWith(canonicalStage)
				|| canonicalOutput.equals(canonicalStage)
				|| source.overlapsSource(output, canonicalOutput, canonicalParent)) {
				throw blocked("Project conversion output is outside its unpublished stage or aliases source evidence.",
					"Use one new conversion-output child of the project staging directory.");
			}
		} else if (source.overlapsSourceOrReportedTarget(
			output, canonicalOutput, canonicalParent)) {
			throw blocked("Conversion output is inside or aliases source evidence or the reported target.",
				"Choose a new path in an independent directory outside source and target roots.");
		}
		return canonicalOutput;
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
		final List<String> directories = new ArrayList<String>();
		Files.walkFileTree(stage, new SimpleFileVisitor<Path>() {
			@Override
			public FileVisitResult preVisitDirectory(Path directory,
				BasicFileAttributes attributes) throws IOException {
				if (!attributes.isDirectory() || Files.isSymbolicLink(directory)) {
					throw new IOException("unsafe output directory");
				}
				directories.add(stage.equals(directory) ? ""
					: stage.relativize(directory).toString().replace('\\', '/'));
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
		Collections.sort(directories);
		List<String> requiredDirectories = new ArrayList<String>();
		requiredDirectories.add("");
		for (String file : files) {
			String parent = file;
			while (parent.contains("/")) {
				parent = parent.substring(0, parent.lastIndexOf('/'));
				if (!requiredDirectories.contains(parent)) requiredDirectories.add(parent);
			}
		}
		Collections.sort(requiredDirectories);
		if (files.size() != expectedFiles
			|| !files.contains("conversion-plan.json")
			|| !files.contains("conversion-report.json")
			|| !files.contains(WorldBuilderDiscoveryReconciliation.FILE_NAME)
			|| !directories.equals(requiredDirectories)) {
			throw blocked("Staged conversion output contains missing or untracked files.",
				"Inspect the atomic package writer and retry from immutable evidence.");
		}
	}

	private static void requireFinalStage(
		Path stage,
		WorldBuilderCompatibilityEvidence.DefinitionCatalog definitions,
		WorldBuilderPackedConversionModel model,
		WorldBuilderPackedConversionModel.PackageExpectation expectedPackage,
		String planSha256,
		String reportSha256,
		String reconciliationSha256,
		int expectedFiles) throws IOException, WorldBuilderContractException {
		Path planPath = stage.resolve("conversion-plan.json");
		Path reportPath = stage.resolve("conversion-report.json");
		Path reconciliationPath = stage.resolve(
			WorldBuilderDiscoveryReconciliation.FILE_NAME);
		if (!planSha256.equals(WorldBuilderHashes.sha256(planPath))
			|| !reportSha256.equals(WorldBuilderHashes.sha256(reportPath))
			|| !reconciliationSha256.equals(
				WorldBuilderHashes.sha256(reconciliationPath))) {
			throw blocked("Staged conversion contracts changed after validation.",
				"Retry conversion in an output parent not modified by another process.");
		}
		WorldBuilderAdaptiveContracts.read(
			WorldBuilderAdaptiveContracts.Kind.CONVERSION_PLAN, planPath);
		WorldBuilderAdaptiveContracts.read(
			WorldBuilderAdaptiveContracts.Kind.CONVERSION_REPORT, reportPath);
		WorldBuilderAdaptiveContracts.read(
			WorldBuilderAdaptiveContracts.Kind.DISCOVERY_RECONCILIATION,
			reconciliationPath);
		WorldBuilderReadOnlyTarget stageTarget = WorldBuilderReadOnlyTarget.open(stage);
		WorldBuilderGenericLayeredPackage validated =
			WorldBuilderGenericLayeredPackage.inspect(
				stageTarget, "package", "converted", definitions);
		model.requireExactPackage(stageTarget, "package", validated, expectedPackage);
		if (!expectedPackage.fingerprintSha256.equals(validated.fingerprintSha256)
			|| validated.levelCount != model.levels.size()
			|| validated.terrainCount != model.terrain.size()
			|| !validated.placementSemantics.equals(model.placementSemantics)
			|| !validated.placementIdentities.equals(model.placementIdentities)) {
			throw blocked("Staged package changed after semantic parity validation.",
				"Retry conversion in an output parent not modified by another process.");
		}
		requireExactOutput(stage, expectedFiles);
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

	private static WorldBuilderContractException asConversionRefusal(
		WorldBuilderContractException refusal) {
		if (OPERATION.equals(refusal.operation())) return refusal;
		return new WorldBuilderContractException(refusal.code(), OPERATION,
			refusal.projectId(), refusal.adapterId().isEmpty()
				? WorldBuilderPackedLayoutAdapter.ID : refusal.adapterId(),
			refusal.relativePath(), refusal.provenance(), refusal.expected(),
			refusal.observed(), false, refusal.getMessage(), refusal.nextStep(), refusal);
	}

	private static final class Prepared {
		final WorldBuilderTargetCapability capability;
		final WorldBuilderAdaptiveConfiguration configuration;
		final WorldBuilderCompatibilityEvidence common;
		final WorldBuilderPackedConversionModel model;

		Prepared(WorldBuilderTargetCapability capability,
			WorldBuilderAdaptiveConfiguration configuration,
			WorldBuilderCompatibilityEvidence common,
			WorldBuilderPackedConversionModel model) {
			this.capability = capability;
			this.configuration = configuration;
			this.common = common;
			this.model = model;
		}
	}

	static final class Result {
		final Path output;
		final String sourceFingerprintSha256;
		final String planSha256;
		final String reportSha256;
		final String reconciliationSha256;
		final String outputFingerprintSha256;
		final int terrainCount;
		final int placementCount;

		Result(Path output, String sourceFingerprintSha256, String planSha256,
			String reportSha256, String reconciliationSha256,
			String outputFingerprintSha256,
			int terrainCount, int placementCount) {
			this.output = output;
			this.sourceFingerprintSha256 = sourceFingerprintSha256;
			this.planSha256 = planSha256;
			this.reportSha256 = reportSha256;
			this.reconciliationSha256 = reconciliationSha256;
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
			value.put("reconciliationSha256", reconciliationSha256);
			value.put("outputFingerprintSha256", outputFingerprintSha256);
			value.put("terrainCount", Long.valueOf(terrainCount));
			value.put("placementCount", Long.valueOf(placementCount));
			return WorldBuilderJsonDocuments.pretty(value);
		}
	}

	static final class Inspection {
		final Map<String,Object> manifest;
		final String manifestSha256;
		final String sourceFingerprintSha256;
		final String planFingerprintSha256;
		final String planSha256;
		final String reportSha256;
		final String reconciliationSha256;
		final String outputFingerprintSha256;
		final int terrainCount;
		final int placementCount;
		final List<Object> outputInventory;

		Inspection(String sourceFingerprintSha256, String planFingerprintSha256,
			String planSha256, String reportSha256, String reconciliationSha256,
			String outputFingerprintSha256, int terrainCount, int placementCount,
			List<Object> outputInventory, Map<String,Object> manifest, String manifestSha256) {
			this.manifest = manifest;
			this.manifestSha256 = manifestSha256;
			this.sourceFingerprintSha256 = sourceFingerprintSha256;
			this.planFingerprintSha256 = planFingerprintSha256;
			this.planSha256 = planSha256;
			this.reportSha256 = reportSha256;
			this.reconciliationSha256 = reconciliationSha256;
			this.outputFingerprintSha256 = outputFingerprintSha256;
			this.terrainCount = terrainCount;
			this.placementCount = placementCount;
			this.outputInventory = Collections.unmodifiableList(
				new ArrayList<Object>(outputInventory));
		}
	}
}
