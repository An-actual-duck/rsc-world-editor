package com.openrsc.worldbuilder;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Atomic UUID project creation, selection, validation, and save lifecycle for
 * adaptive World Builder projects.  It never mutates a discovered target.
 */
final class WorldBuilderAdaptiveProjectLifecycle {
	static final String PROJECTS_DIRECTORY = "projects";
	static final String REGISTRY_FILE = "project-registry.json";
	static final String ACTIVE_FILE = "active-project.json";
	static final String PROJECT_FILE = "project.json";
	static final String DISCOVERY_FILE = "discovery/report.json";
	static final String SNAPSHOT_FILE = "source/snapshot-manifest.json";
	static final String BASELINE_DIRECTORY = "source/layered-baseline/package";
	static final String WORKING_PACKAGE_DIRECTORY = "working/layered-world/package";
	static final String WORKING_RUNTIME_FILE = "working/runtime/runtime.json";
	static final String RUNTIME_JAR = "launcher/world-builder-tools.jar";
	static final String RUNTIME_VERSION = "world-builder-project-runtime-v1";
	private static final String OPERATION = "adaptive-project-lifecycle";
	private static final String ZERO_HASH =
		"0000000000000000000000000000000000000000000000000000000000000000";

	interface Observer {
		void observe(String milestone, Path projectStage) throws Exception;
	}

	private static final Observer NO_OP_OBSERVER = new Observer() {
		@Override
		public void observe(String milestone, Path projectStage) {
			// Production lifecycle has no injected failure observer.
		}
	};

	private final Observer observer;

	WorldBuilderAdaptiveProjectLifecycle() {
		this(NO_OP_OBSERVER);
	}

	WorldBuilderAdaptiveProjectLifecycle(Observer observer) {
		this.observer = observer == null ? NO_OP_OBSERVER : observer;
	}

	ProjectResult create(Path requestedInstallRoot, Path requestedRuntimeRoot,
		Path requestedTargetRoot, Path discoveryReportPath, String requestedDisplayName,
		int port, String confirmation) throws IOException, WorldBuilderContractException {
		return create(requestedInstallRoot, requestedRuntimeRoot, requestedTargetRoot,
			discoveryReportPath, requestedDisplayName, port, confirmation, null);
	}

	ProjectResult create(Path requestedInstallRoot, Path requestedRuntimeRoot,
		Path requestedTargetRoot, Path discoveryReportPath, String requestedDisplayName,
		int port, String confirmation, Path itemVisualMappings)
		throws IOException, WorldBuilderContractException {
		if (!"CREATE".equals(confirmation)) {
			throw problem(WorldBuilderErrorCodes.CONTRACT_VALUE_INVALID, "confirmation",
				"Adaptive project creation requires exact CREATE confirmation.",
				"Review discovery, then type CREATE exactly.");
		}
		if (port < 1 || port >= 65535) {
			throw problem(WorldBuilderErrorCodes.CONTRACT_VALUE_INVALID, "run/port",
				"Project runtime port must be between 1 and 65534.",
				"Choose one unused loopback port in the supported range.");
		}
		Path install = realDirectory(requestedInstallRoot, "World Builder install root");
		Path runtime = realDirectory(requestedRuntimeRoot, "World Builder runtime root");
		Path runtimeJar = safeRegularFile(runtime, RUNTIME_JAR, "application runtime");
		// Keep the launcher check explicit, then bind the project to the complete
		// immutable server/client runtime that will actually execute inside it.
		WorldBuilderHashes.sha256(runtimeJar);
		WorldBuilderAdaptiveRuntimePreparer.SourceRuntime sourceRuntime =
			WorldBuilderAdaptiveRuntimePreparer.inspect(runtime);
		String runtimeSha256 = sourceRuntime.fingerprintSha256;
		Map<String,Object> report = readContractMap(
			discoveryReportPath, WorldBuilderAdaptiveContracts.Kind.DISCOVERY_REPORT);
		requireDiscoveryFingerprint(report);
		String status = string(report, "status");
		if (!("compatible".equals(status) || "standalone".equals(status))) {
			throw problem(WorldBuilderErrorCodes.CAPABILITY_MISMATCH, DISCOVERY_FILE,
				"Blocked discovery evidence cannot create a project.",
				"Resolve every discovery blocker and create from a fresh compatible report.");
		}
		String representation = string(report, "representation");
		String origin = "standalone".equals(status) ? "standalone-empty"
			: "packed".equals(representation) ? "target-packed"
				: "layered".equals(representation) ? "target-layered" : "";
		if (origin.isEmpty()) {
			throw problem(WorldBuilderErrorCodes.UNSUPPORTED_FORMAT, DISCOVERY_FILE,
				"Discovery did not select packed, layered, or standalone input.",
				"Run adaptive discovery against one supported stable origin.");
		}
		String displayName = requireDisplayName(requestedDisplayName);
		Path target = requestedTargetRoot == null ? null
			: realDirectory(requestedTargetRoot, "target root");
		if (!"standalone-empty".equals(origin) && target == null) {
			throw problem(WorldBuilderErrorCodes.NO_TARGET, "target-root",
				"A compatible discovery report requires its target root.",
				"Supply the exact root used by discover-adaptive.");
		}
		requireFreshDiscovery(report, target);

		Path projects = install.resolve(PROJECTS_DIRECTORY).normalize();
		requireContained(install, projects, PROJECTS_DIRECTORY);
		ensureRealDirectory(projects);
		Path lockPath = projects.resolve(".registry.lock");
		try (FileChannel channel = openLock(lockPath)) {
			FileLock lock = tryLock(channel);
			if (lock == null) {
				throw problem(WorldBuilderErrorCodes.RECOVERY_REQUIRED, PROJECTS_DIRECTORY,
					"Another project lifecycle operation is already active.",
					"Wait for the other operation to finish and retry.");
			}
			try {
				return createLocked(install, sourceRuntime, runtimeSha256, target, report,
					discoveryReportPath, displayName, origin, port, itemVisualMappings);
			} finally {
				lock.release();
			}
		}
	}

	private ProjectResult createLocked(Path install,
		WorldBuilderAdaptiveRuntimePreparer.SourceRuntime sourceRuntime,
		String runtimeSha256, Path target,
		Map<String,Object> report, Path reportPath, String displayName, String origin,
		int port, Path itemVisualMappings) throws IOException, WorldBuilderContractException {
		RegistryState existing = loadRegistry(install, true);
		if (existing.records.size() >= WorldBuilderContractLimits.MAX_PROJECTS) {
			throw problem(WorldBuilderErrorCodes.CONTRACT_LIMIT_EXCEEDED, REGISTRY_FILE,
				"Project registry reached its supported 4,096-project limit.",
				"Archive complete closed projects outside the install before creating another.");
		}
		String projectId;
		Path project;
		do {
			projectId = UUID.randomUUID().toString().toLowerCase(java.util.Locale.ROOT);
			project = install.resolve(PROJECTS_DIRECTORY).resolve(projectId).normalize();
		} while (Files.exists(project, LinkOption.NOFOLLOW_LINKS));
		Path stage = project.getParent().resolve(
			".staging-" + projectId + "-" + UUID.randomUUID()).normalize();
		requireContained(project.getParent(), stage, stage.getFileName().toString());
		byte[] oldRegistry = readOptionalRegular(install.resolve(REGISTRY_FILE));
		byte[] oldActive = readOptionalRegular(install.resolve(ACTIVE_FILE));
		boolean projectPublished = false;
		try {
			Files.createDirectory(stage);
			observe("stage-created", stage);
			Path stagedReport = stage.resolve(DISCOVERY_FILE);
			writeNew(stagedReport, WorldBuilderJsonDocuments.pretty(report)
				.getBytes(StandardCharsets.UTF_8));
			WorldBuilderAdaptiveContracts.read(
				WorldBuilderAdaptiveContracts.Kind.DISCOVERY_REPORT, stagedReport);

			PreparedOrigin prepared;
			if ("standalone-empty".equals(origin)) {
				WorldBuilderEmptyWorldGenerator.Result empty =
					WorldBuilderEmptyWorldGenerator.generate(
						stage, runtimeSha256, sourceRuntime);
				prepared = PreparedOrigin.empty(empty);
			} else {
				prepared = prepareTargetOrigin(stage, target, report, stagedReport,
					origin, sourceRuntime, itemVisualMappings);
			}
			writePortableDiscoveryReport(stagedReport, report);
			observe("source-prepared", stage);
			requireFreshDiscovery(report, target);

			copyTreeExact(stage.resolve(BASELINE_DIRECTORY),
				stage.resolve(WORKING_PACKAGE_DIRECTORY));
			WorldBuilderWideElevationPromotion.promoteInPlace(
				stage.resolve(WORKING_PACKAGE_DIRECTORY));
			for (String relative : Arrays.asList(
				"exports", "backups", "receipts", "diagnostics", "logs", "run")) {
				ensureRealDirectory(stage.resolve(relative));
			}

			Map<String,Object> snapshot = sourceSnapshot(
				stage, projectId, origin, report, prepared);
			WorldBuilderReadOnlyTarget stagedTarget = WorldBuilderReadOnlyTarget.open(stage);
			WorldBuilderCompatibilityEvidence.DefinitionCatalog stagedDefinitions =
				WorldBuilderCompatibilityEvidence.DefinitionCatalog.read(
					stagedTarget, definitionCatalogPath(snapshot));
			WorldBuilderGenericLayeredPackage stagedWorking =
				WorldBuilderGenericLayeredPackage.inspect(stagedTarget,
					WORKING_PACKAGE_DIRECTORY, "working", stagedDefinitions);
			if ("standalone-empty".equals(origin)) {
				stagedWorking = WorldBuilderEmptyWorldGenerator.bindInitialLocation(
					stagedTarget, stagedWorking);
			}
			if (Files.exists(stage.resolve(
				WorldBuilderProjectContentBundle.SOURCE_DIRECTORY),
				LinkOption.NOFOLLOW_LINKS)) {
				WorldBuilderProjectContentBundle.Bundle content =
					WorldBuilderProjectContentBundle.copyToWorking(stage);
				WorldBuilderContentReconciliation.write(
					stage, sourceRuntime, stagedWorking, content);
			}
			WorldBuilderAdaptiveRuntimePreparer.prepare(stage, sourceRuntime,
				snapshot, origin, port);
			writeRuntimeMetadata(stage, projectId, origin, runtimeSha256, port,
				stagedWorking);
			observe("working-prepared", stage);
			Path snapshotPath = stage.resolve(SNAPSHOT_FILE);
			writeContractNew(snapshotPath, snapshot,
				WorldBuilderAdaptiveContracts.Kind.SOURCE_SNAPSHOT);
			String snapshotFingerprint = string(
				snapshot, "sourceFingerprintSha256");

			Map<String,Object> manifest = projectManifest(stage, projectId, displayName,
				origin, report, prepared, runtimeSha256, snapshotFingerprint,
				stagedWorking.fingerprintSha256);
			Path manifestPath = stage.resolve(PROJECT_FILE);
			writeContractNew(manifestPath, manifest,
				WorldBuilderAdaptiveContracts.Kind.PROJECT_MANIFEST);
			VerifiedProject verified = verifyProjectDirectory(stage, true, true);
			if (!projectId.equals(verified.projectId)) {
				throw problem(WorldBuilderErrorCodes.CONTRACT_VALUE_INVALID, PROJECT_FILE,
					"Staged project identity changed during validation.",
					"Discard the unpublished stage and retry.");
			}
			requireFreshDiscovery(report, target);
			observe("before-project-publish", stage);
			moveAtomicNew(stage, project);
			projectPublished = true;
			observe("project-published", project);

			String manifestHash = WorldBuilderHashes.sha256(project.resolve(PROJECT_FILE));
			Map<String,Object> registry = registryWith(existing, projectId,
				displayName, origin, string(manifest, "state"), manifestHash);
			writeContractAtomic(install.resolve(REGISTRY_FILE), registry,
				WorldBuilderAdaptiveContracts.Kind.PROJECT_REGISTRY);
			observe("registry-published", project);
			Map<String,Object> active = activeProject(projectId, manifestHash);
			writeContractAtomic(install.resolve(ACTIVE_FILE), active,
				WorldBuilderAdaptiveContracts.Kind.ACTIVE_PROJECT);
			observe("active-published", project);
			loadRegistry(install, true);
			return new ProjectResult(project, projectId, origin,
				string(manifest, "state"), prepared.packageFingerprintSha256, port);
		} catch (WorldBuilderContractException failure) {
			rollbackCreation(install, project, stage, projectPublished,
				oldRegistry, oldActive, failure);
			throw failure;
		} catch (IOException failure) {
			rollbackCreation(install, project, stage, projectPublished,
				oldRegistry, oldActive, failure);
			throw failure;
		} catch (RuntimeException failure) {
			rollbackCreation(install, project, stage, projectPublished,
				oldRegistry, oldActive, failure);
			throw failure;
		} catch (Exception callbackFailure) {
			WorldBuilderContractException failure = problem(
				WorldBuilderErrorCodes.MUTATION_FAILED, "project-stage",
				"Project creation was interrupted before a complete metadata transaction.",
				"Retry after resolving the injected or environmental failure.", callbackFailure);
			rollbackCreation(install, project, stage, projectPublished,
				oldRegistry, oldActive, failure);
			throw failure;
		}
	}

	private PreparedOrigin prepareTargetOrigin(Path stage, Path target,
		Map<String,Object> report, Path stagedReport, String origin,
		WorldBuilderAdaptiveRuntimePreparer.SourceRuntime sourceRuntime,
		Path itemVisualMappings)
		throws IOException, WorldBuilderContractException {
		List<Evidence> evidence = evidence(report);
		Path original = stage.resolve("source/original");
		ensureRealDirectory(original);
		WorldBuilderReadOnlyTarget live = WorldBuilderReadOnlyTarget.open(target);
		for (Evidence item : evidence) {
			if (!item.present) {
				if (live.exists(item.targetRelativePath)) {
					throw problem(WorldBuilderErrorCodes.TARGET_DRIFT,
						item.targetRelativePath,
						"Required absence became present during project creation.",
						"Stop target changes, rediscover, and create a new project.");
				}
				continue;
			}
			WorldBuilderReadOnlyTarget.FileState state = live.requiredState(
				item.role, item.targetRelativePath);
			if (item.size >= 0L && state.size != item.size
				|| !state.sha256.equals(item.sha256)) {
				throw problem(WorldBuilderErrorCodes.TARGET_DRIFT,
					item.targetRelativePath,
					"Target evidence changed before its immutable copy was verified.",
					"Stop target changes, rediscover, and create a new project.");
			}
			Path destination = original.resolve(item.targetRelativePath).normalize();
			requireContained(original, destination, item.targetRelativePath);
			copyNewVerified(live.requiredFile(item.targetRelativePath), destination,
				item.size, item.sha256);
		}
		requireExactOriginalTree(original, evidence);
		requireFreshDiscovery(report, target);

		WorldBuilderReadOnlyTarget copied = WorldBuilderReadOnlyTarget.open(original);
		WorldBuilderTargetCapability capability;
		WorldBuilderAdaptiveConfiguration configuration;
		Path conversionReport = stagedReport;
		if (isPackedFallbackReport(report)) {
			WorldBuilderPackedFallbackEvidence.Result generated =
				WorldBuilderPackedFallbackEvidence.materialize(
					stage, original, report, sourceRuntime, itemVisualMappings);
			evidence = withGeneratedFallbackEvidence(evidence, generated.generated);
			requireExactOriginalTree(original, evidence);
			copied = WorldBuilderReadOnlyTarget.open(original);
			capability = generated.capability;
			configuration = generated.configuration;
			conversionReport = stage.resolve(".fallback-conversion-report.json");
			writeNew(conversionReport,
				WorldBuilderJsonDocuments.pretty(generated.conversionReport)
					.getBytes(StandardCharsets.UTF_8));
		} else {
			capability = WorldBuilderTargetCapability.read(copied);
			String selectedRole = selectedRole(report);
			WorldBuilderAdaptiveConfiguration.Selection selection =
				WorldBuilderAdaptiveConfiguration.select(copied, capability, selectedRole);
			configuration = selection.selected;
		}
		WorldBuilderCompatibilityEvidence common =
			WorldBuilderCompatibilityEvidence.inspect(copied, capability, configuration);
		String baselineFingerprint;
		String conversionFingerprint = "";
		if ("target-layered".equals(origin)) {
			WorldBuilderGenericLayeredPackage layered =
				WorldBuilderGenericLayeredPackage.inspect(copied,
					configuration.serverMapRelativePath, "adopted", common.definitions);
			copyLayeredPackage(original, configuration.serverMapRelativePath,
				stage.resolve(BASELINE_DIRECTORY), layered.files);
			WorldBuilderGenericLayeredPackage adopted =
				WorldBuilderGenericLayeredPackage.inspect(
					WorldBuilderReadOnlyTarget.open(stage), BASELINE_DIRECTORY,
					"baseline", common.definitions);
			if (!layered.fingerprintSha256.equals(adopted.fingerprintSha256)) {
				throw problem(WorldBuilderErrorCodes.MAP_MISMATCH, BASELINE_DIRECTORY,
					"Adopted baseline differs from the exact active layered package.",
					"Discard the unpublished project and retry from stable target evidence.");
			}
			baselineFingerprint = adopted.fingerprintSha256;
		} else {
			Path conversionOutput = stage.resolve(".conversion-output");
			ensureRealDirectory(stage.resolve("diagnostics"));
			WorldBuilderPackedConverter.Result converted;
			try {
				converted = new WorldBuilderPackedConverter().convertForProject(
					original, conversionReport, conversionOutput, stage);
			} finally {
				if (!conversionReport.equals(stagedReport)) {
					Files.deleteIfExists(conversionReport);
				}
			}
			Path conversionDirectory = stage.resolve("source/conversion");
			ensureRealDirectory(conversionDirectory);
			moveAtomicNew(conversionOutput.resolve("conversion-plan.json"),
				conversionDirectory.resolve("plan.json"));
			moveAtomicNew(conversionOutput.resolve("conversion-report.json"),
				conversionDirectory.resolve("report.json"));
			moveAtomicNew(conversionOutput.resolve(
				WorldBuilderDiscoveryReconciliation.FILE_NAME),
				stage.resolve(WorldBuilderDiscoveryReconciliation.PROJECT_RELATIVE_PATH));
			ensureRealDirectory(stage.resolve("source/layered-baseline"));
			moveAtomicNew(conversionOutput.resolve("package"),
				stage.resolve(BASELINE_DIRECTORY));
			Files.delete(conversionOutput);
			WorldBuilderGenericLayeredPackage layered =
				WorldBuilderGenericLayeredPackage.inspect(
					WorldBuilderReadOnlyTarget.open(stage), BASELINE_DIRECTORY,
					"baseline", common.definitions);
			if (!converted.outputFingerprintSha256.equals(layered.fingerprintSha256)) {
				throw problem(WorldBuilderErrorCodes.CONVERSION_BLOCKED,
					BASELINE_DIRECTORY,
					"Contained conversion baseline changed after Phase 2 validation.",
					"Discard the unpublished stage and repeat exact conversion.");
			}
			baselineFingerprint = layered.fingerprintSha256;
			conversionFingerprint = converted.outputFingerprintSha256;
		}
		return PreparedOrigin.target(stage, evidence, capability, configuration,
			baselineFingerprint, conversionFingerprint);
	}

	private static Map<String,Object> sourceSnapshot(Path stage, String projectId,
		String origin, Map<String,Object> report, PreparedOrigin prepared)
		throws IOException, WorldBuilderContractException {
		Map<String,Object> snapshot = new LinkedHashMap<String,Object>();
		snapshot.put("schemaVersion", Long.valueOf(2L));
		snapshot.put("manifestType", "world-builder-source-snapshot");
		snapshot.put("projectId", projectId);
		snapshot.put("origin", origin);
		snapshot.put("adapterId", prepared.adapterId);
		snapshot.put("capabilityId", prepared.capabilityId);
		if (prepared.selectedConfigurationSourcePath.isEmpty()) {
			snapshot.put("selectedConfiguration", absentRoleReference());
		} else {
			snapshot.put("selectedConfiguration", stateReference(true,
				prepared.selectedConfigurationRole,
				prepared.selectedConfigurationSourcePath,
				prepared.selectedConfigurationSha256));
		}
		Path reportPath = stage.resolve(DISCOVERY_FILE);
		snapshot.put("discoveryReport", stateReference(true, "",
			DISCOVERY_FILE, WorldBuilderHashes.sha256(reportPath)));
		snapshot.put("originDescriptor", stateReference(true, "",
			prepared.originDescriptorSourcePath,
			WorldBuilderHashes.sha256(stage.resolve(prepared.originDescriptorSourcePath))));
		snapshot.put("originalFiles", records(prepared.originalEvidence));
		snapshot.put("definitionRuntimeFiles", records(prepared.definitionEvidence));
		List<InventoryRecord> conversion = new ArrayList<InventoryRecord>();
		if ("target-packed".equals(origin)) {
			conversion.add(recordFor(stage, "conversion-plan", "source/conversion/plan.json"));
			conversion.add(recordFor(stage, "conversion-report", "source/conversion/report.json"));
		}
		snapshot.put("conversionEvidenceFiles", records(conversion));
		List<InventoryRecord> baseline = inventoryPackage(
			stage, BASELINE_DIRECTORY, "source/layered-baseline/package/");
		snapshot.put("layeredBaselineFiles", records(baseline));
		snapshot.put("sourceFingerprintSha256", ZERO_HASH);
		bindSelfFingerprint(snapshot, "sourceFingerprintSha256", false);
		WorldBuilderAdaptiveContracts.validateParsed(
			WorldBuilderAdaptiveContracts.Kind.SOURCE_SNAPSHOT, snapshot);
		return snapshot;
	}

	private static Map<String,Object> projectManifest(Path stage, String projectId,
		String displayName, String origin, Map<String,Object> report,
		PreparedOrigin prepared, String runtimeSha256, String snapshotFingerprint,
		String workingFingerprint)
		throws IOException, WorldBuilderContractException {
		Map<String,Object> manifest = new LinkedHashMap<String,Object>();
		manifest.put("schemaVersion", Long.valueOf(2L));
		manifest.put("manifestType", "world-builder-project");
		manifest.put("projectId", projectId);
		manifest.put("displayName", displayName);
		manifest.put("origin", origin);
		boolean standalone = "standalone-empty".equals(origin);
		boolean attached = !standalone && prepared.installEnabled;
		manifest.put("state", standalone ? "ready-standalone"
			: attached ? "ready-attached" : "ready-detached");
		Map<String,Object> creation = new LinkedHashMap<String,Object>();
		creation.put("toolVersion", WorldBuilderAdaptiveDiscoveryReport.TOOL_VERSION);
		creation.put("runtimeVersion", RUNTIME_VERSION);
		manifest.put("creation", creation);
		Map<String,Object> target = new LinkedHashMap<String,Object>();
		target.put("targetBacked", Boolean.valueOf(!standalone));
		target.put("locatorDisplay", standalone ? "" : string(report, "targetRootDisplay"));
		target.put("adapterId", prepared.adapterId);
		target.put("capabilityId", prepared.capabilityId);
		target.put("selectedConfigurationRelativePath",
			prepared.selectedConfigurationTargetPath);
		target.put("selectedConfigurationSha256",
			prepared.selectedConfigurationSha256);
		target.put("targetFingerprintSha256", standalone ? ""
			: string(report, "discoveryFingerprintSha256"));
		target.put("importProfileId", standalone ? ""
			: prepared.importProfileId);
		manifest.put("target", target);
		Map<String,Object> standaloneValue = new LinkedHashMap<String,Object>();
		standaloneValue.put("generatorId", standalone
			? WorldBuilderEmptyWorldGenerator.GENERATOR_ID : "");
		standaloneValue.put("catalogId", standalone
			? WorldBuilderEmptyWorldGenerator.CATALOG_ID : "");
		standaloneValue.put("runtimeId", standalone
			? WorldBuilderEmptyWorldGenerator.RUNTIME_ID : "");
		manifest.put("standalone", standaloneValue);
		Map<String,Object> paths = new LinkedHashMap<String,Object>();
		paths.put("sourceSnapshotRelativePath", SNAPSHOT_FILE);
		paths.put("layeredBaselineRelativePath", BASELINE_DIRECTORY);
		paths.put("workingRuntimeRelativePath", "working/runtime");
		paths.put("workingPackageRelativePath", WORKING_PACKAGE_DIRECTORY);
		paths.put("conversionPlanRelativePath",
			"target-packed".equals(origin) ? "source/conversion/plan.json" : "");
		paths.put("conversionReportRelativePath",
			"target-packed".equals(origin) ? "source/conversion/report.json" : "");
		paths.put("exportsRelativePath", "exports");
		paths.put("backupsRelativePath", "backups");
		paths.put("receiptsRelativePath", "receipts");
		paths.put("diagnosticsRelativePath", "diagnostics");
		paths.put("logsRelativePath", "logs");
		paths.put("runRelativePath", "run");
		manifest.put("paths", paths);
		Map<String,Object> fingerprints = new LinkedHashMap<String,Object>();
		fingerprints.put("sourceSha256", snapshotFingerprint);
		fingerprints.put("layeredBaselineSha256", prepared.packageFingerprintSha256);
		fingerprints.put("definitionsSha256", prepared.definitionSha256);
		fingerprints.put("runtimeSha256", runtimeSha256);
		fingerprints.put("conversionSha256", prepared.conversionFingerprintSha256);
		fingerprints.put("workingSha256", workingFingerprint);
		manifest.put("fingerprints", fingerprints);
		manifest.put("operations", operations(
			true, true, attached, false));
		manifest.put("projectFingerprintSha256", ZERO_HASH);
		bindSelfFingerprint(manifest, "projectFingerprintSha256", true);
		WorldBuilderAdaptiveContracts.validateParsed(
			WorldBuilderAdaptiveContracts.Kind.PROJECT_MANIFEST, manifest);
		return manifest;
	}

	static VerifiedProject verifyProjectDirectory(Path requestedProject,
		boolean requireWorkingFingerprint)
		throws IOException, WorldBuilderContractException {
		return verifyProjectDirectory(
			requestedProject, requireWorkingFingerprint, false);
	}

	private static VerifiedProject verifyProjectDirectory(Path requestedProject,
		boolean requireWorkingFingerprint, boolean allowUnpublishedStage)
		throws IOException, WorldBuilderContractException {
		Path project = realDirectory(requestedProject, "adaptive project");
		if (!allowUnpublishedStage) {
			WorldBuilderWideElevationPromotionTransaction.requireSettled(project);
		}
		Path manifestPath = safeRegularFile(project, PROJECT_FILE, "project manifest");
		Map<String,Object> manifest = readContractMap(manifestPath,
			WorldBuilderAdaptiveContracts.Kind.PROJECT_MANIFEST);
		requireSelfFingerprint(manifest, "projectFingerprintSha256", true);
		String projectId = string(manifest, "projectId");
		String directoryName = project.getFileName() == null
			? "" : project.getFileName().toString();
		if (!projectId.equals(directoryName)
			&& !(allowUnpublishedStage
				&& directoryName.startsWith(".staging-" + projectId + "-"))) {
			throw problem(WorldBuilderErrorCodes.CONTRACT_VALUE_INVALID, PROJECT_FILE,
				"Project UUID does not match its directory.",
				"Restore the complete project under projects/<its-uuid>.");
		}
		Map<String,Object> fingerprints = object(manifest.get("fingerprints"), "fingerprints");
		Path snapshotPath = safeRegularFile(project, SNAPSHOT_FILE, "source snapshot");
		Map<String,Object> snapshot = readContractMap(snapshotPath,
			WorldBuilderAdaptiveContracts.Kind.SOURCE_SNAPSHOT);
		requireSelfFingerprint(snapshot, "sourceFingerprintSha256", false);
		if (!projectId.equals(string(snapshot, "projectId"))
			|| !string(manifest, "origin").equals(string(snapshot, "origin"))
			|| !string(fingerprints, "sourceSha256").equals(
				string(snapshot, "sourceFingerprintSha256"))) {
			throw problem(WorldBuilderErrorCodes.SOURCE_CORRUPT, SNAPSHOT_FILE,
				"Immutable source snapshot identity does not match the project manifest.",
				"Restore the complete project from a trusted backup; do not rebuild source.");
		}
		verifySourceTree(project, snapshot);
		Path immutableContent = project.resolve(
			WorldBuilderProjectContentBundle.SOURCE_DIRECTORY);
		Path workingContent = project.resolve(
			WorldBuilderProjectContentBundle.WORKING_DIRECTORY);
		if (Files.exists(immutableContent, LinkOption.NOFOLLOW_LINKS)
			|| Files.exists(workingContent, LinkOption.NOFOLLOW_LINKS)) {
			WorldBuilderProjectContentBundle.Bundle immutableBundle =
				WorldBuilderProjectContentBundle.read(immutableContent);
			WorldBuilderProjectContentBundle.Bundle workingBundle =
				WorldBuilderProjectContentBundle.read(workingContent);
			if (!immutableBundle.bundleFingerprintSha256.equals(
				workingBundle.bundleFingerprintSha256)) {
				throw problem(WorldBuilderErrorCodes.DEFINITION_MISMATCH,
					WorldBuilderProjectContentBundle.WORKING_DIRECTORY,
					"Working custom content differs from immutable target content evidence.",
					"Restore the exact complete project-local content bundle.");
			}
		}
		Map<String,Object> reportReference = object(snapshot.get("discoveryReport"),
			"discoveryReport");
		Path reportPath = safeRegularFile(project,
			string(reportReference, "relativePath"), "discovery report");
		if (!string(reportReference, "sha256").equals(WorldBuilderHashes.sha256(reportPath))) {
			throw problem(WorldBuilderErrorCodes.SOURCE_CORRUPT, DISCOVERY_FILE,
				"Immutable discovery report hash changed.",
				"Restore the complete project from a trusted backup.");
		}
		Map<String,Object> report = readContractMap(reportPath,
			WorldBuilderAdaptiveContracts.Kind.DISCOVERY_REPORT);
		requireDiscoveryFingerprint(report);

		String definitionPath = definitionCatalogPath(snapshot);
		WorldBuilderReadOnlyTarget projectTarget = WorldBuilderReadOnlyTarget.open(project);
		WorldBuilderCompatibilityEvidence.DefinitionCatalog definitions =
			WorldBuilderCompatibilityEvidence.DefinitionCatalog.read(
				projectTarget, definitionPath);
		String definitionHash = WorldBuilderHashes.sha256(
			projectTarget.requiredFile(definitionPath));
		if (!definitionHash.equals(string(fingerprints, "definitionsSha256"))) {
			throw problem(WorldBuilderErrorCodes.DEFINITION_MISMATCH, definitionPath,
				"Project definition fingerprint does not match immutable source evidence.",
				"Restore the complete project from a trusted backup.");
		}
		if ("standalone-empty".equals(string(manifest, "origin"))) {
			Map<String,Object> standalone = object(
				manifest.get("standalone"), "standalone");
			if (!WorldBuilderEmptyWorldGenerator.GENERATOR_ID.equals(
					string(standalone, "generatorId"))
				|| !definitions.catalogId.equals(string(standalone, "catalogId"))
				|| !WorldBuilderEmptyWorldGenerator.RUNTIME_ID.equals(
					string(standalone, "runtimeId"))) {
				throw problem(WorldBuilderErrorCodes.DEFINITION_MISMATCH, PROJECT_FILE,
					"Standalone project identity does not match its immutable catalog/runtime evidence.",
					"Restore the complete project; do not replace its catalog silently.");
			}
		}
		WorldBuilderGenericLayeredPackage baseline =
			WorldBuilderGenericLayeredPackage.inspect(projectTarget,
				BASELINE_DIRECTORY, "baseline", definitions);
		if ("standalone-empty".equals(string(manifest, "origin"))) {
			baseline = WorldBuilderEmptyWorldGenerator.bindInitialLocation(
				projectTarget, baseline);
		}
		if (!baseline.fingerprintSha256.equals(
			string(fingerprints, "layeredBaselineSha256"))) {
			throw problem(WorldBuilderErrorCodes.SOURCE_CORRUPT, BASELINE_DIRECTORY,
				"Immutable layered baseline fingerprint changed.",
				"Restore the complete project from a trusted backup.");
		}
		WorldBuilderGenericLayeredPackage working =
			WorldBuilderGenericLayeredPackage.inspect(projectTarget,
				WORKING_PACKAGE_DIRECTORY, "working", definitions);
		if ("standalone-empty".equals(string(manifest, "origin"))) {
			working = WorldBuilderEmptyWorldGenerator.bindInitialLocation(
				projectTarget, working);
		}
		if (requireWorkingFingerprint && !working.fingerprintSha256.equals(
			string(fingerprints, "workingSha256"))) {
			throw problem(WorldBuilderErrorCodes.CONTRACT_VALUE_INVALID,
				WORKING_PACKAGE_DIRECTORY,
				"Working package changed without a validated project save.",
				"Run save-project after the editor writes a complete valid package.");
		}
		Path runtimePath = safeRegularFile(project, WORKING_RUNTIME_FILE,
			"project runtime metadata");
		Map<String,Object> runtime = readJsonObject(runtimePath, WORKING_RUNTIME_FILE);
		requireRuntimeMetadata(runtime, projectId, string(manifest, "origin"),
			string(fingerprints, "runtimeSha256"), working);
		WorldBuilderAdaptiveRuntimePreparer.verify(project,
			string(fingerprints, "runtimeSha256"), snapshot,
			string(manifest, "origin"), (int)integer(runtime, "port"));
		return new VerifiedProject(project, projectId, string(manifest, "origin"),
			string(manifest, "state"), manifest, snapshot, report, definitions,
			baseline, working);
	}

	ProjectResult openActive(Path requestedInstallRoot, Path requestedTargetRoot)
		throws IOException, WorldBuilderContractException {
		return openActive(requestedInstallRoot, requestedTargetRoot, true);
	}

	ProjectResult validateActive(Path requestedInstallRoot, Path requestedTargetRoot)
		throws IOException, WorldBuilderContractException {
		return openActive(requestedInstallRoot, requestedTargetRoot, false);
	}

	private ProjectResult openActive(Path requestedInstallRoot,
		Path requestedTargetRoot, boolean updateAttachmentState)
		throws IOException, WorldBuilderContractException {
		Path install = realDirectory(requestedInstallRoot, "World Builder install root");
		Path projects = install.resolve(PROJECTS_DIRECTORY);
		if (!Files.isDirectory(projects, LinkOption.NOFOLLOW_LINKS)
			|| Files.isSymbolicLink(projects)) {
			throw problem(WorldBuilderErrorCodes.CONTRACT_VALUE_INVALID,
				PROJECTS_DIRECTORY, "Adaptive projects directory is missing or unsafe.",
				"Create the first project or restore the complete projects directory.");
		}
		Path registryLock = projects.resolve(".registry.lock");
		WorldBuilderRegionSnapshotService.recoverProjects(projects);
		try (FileChannel channel = updateAttachmentState
			? openLock(registryLock) : openExistingLock(registryLock)) {
			FileLock lock = tryLock(channel);
			if (lock == null) throw problem(WorldBuilderErrorCodes.RECOVERY_REQUIRED,
				PROJECTS_DIRECTORY, "Project registry is busy.",
				"Wait for the active lifecycle operation and retry.");
			try {
				recoverPromotionTransactions(projects);
				return openActiveLocked(
					install, requestedTargetRoot, updateAttachmentState);
			} finally {
				lock.release();
			}
		}
	}

	private ProjectResult openActiveLocked(Path install, Path requestedTargetRoot,
		boolean updateAttachmentState)
		throws IOException, WorldBuilderContractException {
		RegistryState registry = loadRegistry(install, true);
		ActiveState active = loadActive(install, registry, true);
		if (active.projectId.isEmpty()) {
			throw problem(WorldBuilderErrorCodes.CONTRACT_VALUE_INVALID, ACTIVE_FILE,
				"No active adaptive project is selected.",
				"Create a project or select one by UUID.");
		}
		Path project = install.resolve(PROJECTS_DIRECTORY).resolve(active.projectId);
		VerifiedProject verified = verifyProjectDirectory(project, true);
		if (!"standalone-empty".equals(verified.origin)) {
			boolean attached = false;
			String attachedLocator = "";
			Map<String,Object> targetInfo = object(
				verified.manifest.get("target"), "target");
			boolean importCapable = !"no-import-v1".equals(
				string(targetInfo, "importProfileId"));
			if (importCapable && requestedTargetRoot != null) {
				try {
					Path target = realDirectory(requestedTargetRoot, "target root");
					Map<String,Object> selected = object(
						verified.snapshot.get("selectedConfiguration"),
						"selectedConfiguration");
					WorldBuilderAdaptiveDiscoveryReport fresh =
						new WorldBuilderAdaptiveDiscovery().discover(
							target, string(selected, "role"));
					attached = "compatible".equals(fresh.status)
						&& fresh.fingerprintSha256().equals(
							string(targetInfo, "targetFingerprintSha256"));
					if (attached) attachedLocator = target.toString();
				} catch (WorldBuilderContractException ignored) {
					attached = false;
				} catch (IOException ignored) {
					attached = false;
				}
			}
			String wanted = attached ? "ready-attached" : "ready-detached";
			if (updateAttachmentState && (!wanted.equals(verified.state)
				|| attached && !attachedLocator.equals(
					string(targetInfo, "locatorDisplay")))) {
				verified = updateState(
					install, verified, wanted, attachedLocator);
			}
		}
		int port = readRuntimePort(verified.projectRoot);
		return new ProjectResult(verified.projectRoot, verified.projectId,
			verified.origin, verified.state, verified.working.fingerprintSha256, port);
	}

	ProjectResult select(Path requestedInstallRoot, String projectId)
		throws IOException, WorldBuilderContractException {
		Path install = realDirectory(requestedInstallRoot, "World Builder install root");
		String id = requireUuid(projectId);
		Path projects = install.resolve(PROJECTS_DIRECTORY);
		Path selectedProject = projects.resolve(id);
		if (Files.isDirectory(selectedProject, LinkOption.NOFOLLOW_LINKS)
			&& !Files.isSymbolicLink(selectedProject)) {
			WorldBuilderRegionSnapshotService.recoverProject(selectedProject);
		}
		Path lockPath = projects.resolve(".registry.lock");
		try (FileChannel channel = openLock(lockPath)) {
			FileLock lock = tryLock(channel);
			if (lock == null) throw problem(WorldBuilderErrorCodes.RECOVERY_REQUIRED,
				PROJECTS_DIRECTORY, "Project registry is busy.",
				"Wait for the active lifecycle operation and retry.");
			try {
				recoverPromotionTransactions(projects);
				RegistryState registry = loadRegistry(install, true);
				RegistryRecord record = registry.byId.get(id);
				if (record == null) throw problem(WorldBuilderErrorCodes.CONTRACT_VALUE_INVALID,
					REGISTRY_FILE, "Selected project UUID is not registered.",
					"List projects and select one exact registered UUID.");
				VerifiedProject project = verifyProjectDirectory(
					install.resolve(record.manifestRelativePath).getParent(), true);
				writeContractAtomic(install.resolve(ACTIVE_FILE),
					activeProject(id, record.manifestSha256),
					WorldBuilderAdaptiveContracts.Kind.ACTIVE_PROJECT);
				return new ProjectResult(project.projectRoot, id, project.origin,
					project.state, project.working.fingerprintSha256,
					readRuntimePort(project.projectRoot));
			} finally {
				lock.release();
			}
		}
	}

	String list(Path requestedInstallRoot)
		throws IOException, WorldBuilderContractException {
		Path install = realDirectory(requestedInstallRoot, "World Builder install root");
		RegistryState registry = loadRegistry(install, true);
		ActiveState active = loadActive(install, registry, false);
		Map<String,Object> result = new LinkedHashMap<String,Object>();
		result.put("status", "projects");
		result.put("activeProjectId", active.projectId);
		List<Object> records = new ArrayList<Object>();
		for (RegistryRecord record : registry.records) records.add(record.toJson());
		result.put("projects", records);
		return WorldBuilderJsonDocuments.pretty(result);
	}

	/**
	 * Resolves and verifies the selected project without inspecting a target or
	 * changing attachment metadata.  Adaptive import/undo use this boundary so
	 * standalone projects can refuse before a target path is resolved.
	 */
	static VerifiedProject verifyActiveProject(Path requestedInstallRoot)
		throws IOException, WorldBuilderContractException {
		Path install = realDirectory(requestedInstallRoot,
			"World Builder install root");
		Path projects = install.resolve(PROJECTS_DIRECTORY);
		if (!Files.isDirectory(projects, LinkOption.NOFOLLOW_LINKS)
			|| Files.isSymbolicLink(projects)) {
			throw problem(WorldBuilderErrorCodes.CONTRACT_VALUE_INVALID,
				PROJECTS_DIRECTORY,
				"Adaptive projects directory is missing or unsafe.",
				"Create a project or restore the complete projects directory.");
		}
		WorldBuilderRegionSnapshotService.recoverProjects(projects);
		try (FileChannel channel = openExistingLock(
			projects.resolve(".registry.lock"))) {
			FileLock lock = tryLock(channel);
			if (lock == null) throw problem(
				WorldBuilderErrorCodes.RECOVERY_REQUIRED, PROJECTS_DIRECTORY,
				"Project registry is busy.",
				"Wait for the active lifecycle operation and retry.");
			try {
				recoverPromotionTransactions(projects);
				RegistryState registry = loadRegistry(install, true);
				ActiveState active = loadActive(install, registry, true);
				if (active.projectId.isEmpty()) throw problem(
					WorldBuilderErrorCodes.CONTRACT_VALUE_INVALID, ACTIVE_FILE,
					"No active adaptive project is selected.",
					"Create a project or select one by UUID.");
				return verifyProjectDirectory(
					install.resolve(active.manifestRelativePath).getParent(), true);
			} finally {
				lock.release();
			}
		}
	}

	ProjectResult save(Path requestedProject)
		throws IOException, WorldBuilderContractException {
		Path project = realDirectory(requestedProject, "adaptive project");
		Path run = project.resolve("run");
		if (!Files.isDirectory(run, LinkOption.NOFOLLOW_LINKS)
			|| Files.isSymbolicLink(run) || !run.toRealPath().startsWith(project)) {
			throw problem(WorldBuilderErrorCodes.UNSAFE_PATH, "run",
				"Adaptive project run directory is missing, linked, or escaped.",
				"Restore the complete contained project run directory.");
		}
		try (WorldBuilderAdaptiveProjectLock ignored =
			WorldBuilderAdaptiveProjectLock.acquire(project, OPERATION)) {
			WorldBuilderRegionSnapshotService.recoverRegionTransaction(project);
			return saveWithRunLockHeld(project);
		}
	}

	ProjectResult saveAfterSupervisedRun(Path requestedProject)
		throws IOException, WorldBuilderContractException {
		Path project = realDirectory(requestedProject, "adaptive project");
		WorldBuilderRegionSnapshotService.recoverRegionTransaction(project);
		return saveWithRunLockHeld(project);
	}

	ProjectResult saveAfterRegionPublication(Path requestedProject)
		throws IOException, WorldBuilderContractException {
		return saveWithRunLockHeld(realDirectory(requestedProject, "adaptive project"));
	}

	private ProjectResult saveWithRunLockHeld(Path project)
		throws IOException, WorldBuilderContractException {
		Path projects = project.getParent();
		if (projects == null || projects.getParent() == null
			|| !PROJECTS_DIRECTORY.equals(projects.getFileName().toString())) {
			throw problem(WorldBuilderErrorCodes.UNSAFE_PATH, PROJECT_FILE,
				"Adaptive project is outside an install projects directory.",
				"Use the complete projects/<uuid> directory selected by the registry.");
		}
		Path install = projects.getParent();
		try (FileChannel channel = openLock(projects.resolve(".registry.lock"))) {
			FileLock lock = tryLock(channel);
			if (lock == null) throw problem(WorldBuilderErrorCodes.RECOVERY_REQUIRED,
				PROJECTS_DIRECTORY, "Project registry is busy.",
				"Wait for the active lifecycle operation and retry.");
			try {
				recoverPromotionTransaction(project);
				VerifiedProject verified = verifyProjectDirectory(project, false);
				boolean promote;
				try {
					promote = WorldBuilderWideElevationPromotion.requiresPromotion(
						project.resolve(WORKING_PACKAGE_DIRECTORY));
				} catch (WorldBuilderDiscoveryException malformed) {
					throw problem(WorldBuilderErrorCodes.UNSUPPORTED_FORMAT,
						WORKING_PACKAGE_DIRECTORY,
						"Editable terrain manifest is malformed during v2 promotion.",
						"Restore the complete valid project and retry save.", malformed);
				}
				if (promote) {
					promoteWorkingPackage(project, verified);
					verified = verifyProjectDirectory(project, false);
				}
				Map<String,Object> manifest = verified.manifest;
				Map<String,Object> fingerprints = object(
					manifest.get("fingerprints"), "fingerprints");
				fingerprints.put("workingSha256", verified.working.fingerprintSha256);
				bindSelfFingerprint(manifest, "projectFingerprintSha256", true);
				publishUpdatedManifest(install, verified, manifest);
				VerifiedProject saved = verifyProjectDirectory(project, true);
				return new ProjectResult(project, saved.projectId, saved.origin,
					saved.state, saved.working.fingerprintSha256,
					readRuntimePort(project));
			} finally {
				lock.release();
			}
		}
	}

	private void promoteWorkingPackage(Path project, VerifiedProject verified)
		throws IOException, WorldBuilderContractException {
		Path stage = null;
		WorldBuilderWideElevationPromotionTransaction transaction = null;
		try {
			stage = WorldBuilderWideElevationPromotionTransaction.createStage(project);
			WorldBuilderWideElevationPromotion.promoteInPlace(stage);
			WorldBuilderGenericLayeredPackage wide = WorldBuilderGenericLayeredPackage.inspect(
				WorldBuilderReadOnlyTarget.open(project),
				project.relativize(stage).toString().replace('\\', '/'),
				"wide-elevation-stage", verified.definitions);
			Map<String,Object> afterManifest = readContractMap(
				project.resolve(PROJECT_FILE),
				WorldBuilderAdaptiveContracts.Kind.PROJECT_MANIFEST);
			Map<String,Object> afterFingerprints = object(
				afterManifest.get("fingerprints"), "fingerprints");
			afterFingerprints.put("workingSha256", wide.fingerprintSha256);
			bindSelfFingerprint(afterManifest, "projectFingerprintSha256", true);
			byte[] afterManifestBytes = WorldBuilderJsonDocuments.pretty(afterManifest)
				.getBytes(StandardCharsets.UTF_8);
			transaction = WorldBuilderWideElevationPromotionTransaction.prepare(
				project, stage, verified.working.fingerprintSha256,
				wide.fingerprintSha256, WorldBuilderHashes.sha256(afterManifestBytes));
			observe("wide-promotion-staged", project);
			transaction.moveOriginalAside();
			observe("wide-promotion-v1-aside", project);
			transaction.installWide();
			observe("wide-promotion-v2-installed", project);
			WorldBuilderGenericLayeredPackage.inspect(
				WorldBuilderReadOnlyTarget.open(project), WORKING_PACKAGE_DIRECTORY,
				"wide-elevation-working", verified.definitions);
			reconcilePromotionMetadata(project, (String)transaction.value.get("token"),
				(String)transaction.value.get("beforeProjectSha256"),
				(String)transaction.value.get("afterProjectSha256"),
				verified.working.fingerprintSha256, wide.fingerprintSha256);
			observe("wide-promotion-before-cleanup", project);
			transaction.finishCommitted();
		} catch (Exception failure) {
			if (transaction == null) {
				deleteTree(stage);
			} else {
				try {
					recoverPromotionTransaction(project);
				} catch (Exception recoveryFailure) {
					recoveryFailure.addSuppressed(failure);
					if (recoveryFailure instanceof IOException) {
						throw (IOException)recoveryFailure;
					}
					throw (WorldBuilderContractException)recoveryFailure;
				}
			}
			if (failure instanceof WorldBuilderDiscoveryException) {
				throw problem(WorldBuilderErrorCodes.UNSUPPORTED_FORMAT,
					WORKING_PACKAGE_DIRECTORY,
					"Editable v1 terrain could not be promoted losslessly to v2.",
					"Restore the complete valid project and retry save.", failure);
			}
			if (failure instanceof IOException) throw (IOException)failure;
			if (failure instanceof WorldBuilderContractException) {
				throw (WorldBuilderContractException)failure;
			}
			throw problem(WorldBuilderErrorCodes.MUTATION_FAILED,
				WORKING_PACKAGE_DIRECTORY,
				"Editable v1 terrain promotion was interrupted and recovered.",
				"Retry save after checking filesystem health.", failure);
		}
	}

	private static void recoverPromotionTransactions(Path projects)
		throws IOException, WorldBuilderContractException {
		int count = 0;
		try (DirectoryStream<Path> entries = Files.newDirectoryStream(projects)) {
			for (Path entry : entries) {
				if (++count > WorldBuilderContractLimits.MAX_PROJECTS + 32) {
					throw problem(WorldBuilderErrorCodes.INVENTORY_LIMIT_EXCEEDED,
						PROJECTS_DIRECTORY,
						"Project recovery scan exceeds its bounded inventory.",
						"Remove unrelated entries without touching registered projects.");
				}
				String name = entry.getFileName().toString();
				if (!name.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) {
					continue;
				}
				if (!Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)
					|| Files.isSymbolicLink(entry)) {
					throw problem(WorldBuilderErrorCodes.RECOVERY_REQUIRED, name,
						"Registered-looking project recovery path is unsafe.",
						"Preserve the projects directory and restore a real project directory.");
				}
				recoverPromotionTransaction(entry);
			}
		}
	}

	private static void recoverPromotionTransaction(Path project)
		throws IOException, WorldBuilderContractException {
		WorldBuilderWideElevationPromotionTransaction.recover(project,
			new WorldBuilderWideElevationPromotionTransaction.MetadataReconciler() {
				@Override public void installAfterFingerprint(Path selected,
					String token, String beforeProjectSha256, String afterProjectSha256,
					String beforeWorkingSha256, String afterWorkingSha256)
					throws IOException, WorldBuilderContractException {
					reconcilePromotionMetadata(selected, token, beforeProjectSha256,
						afterProjectSha256, beforeWorkingSha256, afterWorkingSha256);
				}
			});
	}

	private static void reconcilePromotionMetadata(Path project,
		String token, String beforeProjectSha256, String afterProjectSha256,
		String beforeWorkingSha256, String afterWorkingSha256)
		throws IOException, WorldBuilderContractException {
		Path manifestPath = project.resolve(PROJECT_FILE);
		Map<String,Object> manifest = readContractMap(manifestPath,
			WorldBuilderAdaptiveContracts.Kind.PROJECT_MANIFEST);
		requireSelfFingerprint(manifest, "projectFingerprintSha256", true);
		String currentProjectSha256 = WorldBuilderHashes.sha256(manifestPath);
		String currentWorkingSha256 = string(
			object(manifest.get("fingerprints"), "fingerprints"), "workingSha256");
		if (!(beforeProjectSha256.equals(currentProjectSha256)
				&& beforeWorkingSha256.equals(currentWorkingSha256))
			&& !(afterProjectSha256.equals(currentProjectSha256)
				&& afterWorkingSha256.equals(currentWorkingSha256))) {
			throw problem(WorldBuilderErrorCodes.RECOVERY_REQUIRED, PROJECT_FILE,
				"Project fingerprints do not match either promotion metadata state.",
				"Preserve the promotion journal and exact project metadata; do not guess.");
		}
		Map<String,Object> fingerprints = object(manifest.get("fingerprints"), "fingerprints");
		fingerprints.put("workingSha256", afterWorkingSha256);
		bindSelfFingerprint(manifest, "projectFingerprintSha256", true);
		byte[] afterBytes = WorldBuilderJsonDocuments.pretty(manifest)
			.getBytes(StandardCharsets.UTF_8);
		if (!afterProjectSha256.equals(WorldBuilderHashes.sha256(afterBytes))) {
			throw problem(WorldBuilderErrorCodes.RECOVERY_REQUIRED, PROJECT_FILE,
				"Journaled v2 project fingerprint cannot be reconstructed exactly.",
				"Preserve the promotion transaction and restore exact metadata.");
		}
		Path projects = project.getParent();
		Path install = projects == null ? null : projects.getParent();
		if (install == null || !PROJECTS_DIRECTORY.equals(projects.getFileName().toString())) {
			throw problem(WorldBuilderErrorCodes.UNSAFE_PATH, PROJECT_FILE,
				"Promotion project is outside its install registry.",
				"Use the exact registered projects/<uuid> directory.");
		}
		RegistryState registry = loadRegistryWithoutManifestHashes(install);
		String projectId = string(manifest, "projectId");
		if (!registry.byId.containsKey(projectId)) {
			throw problem(WorldBuilderErrorCodes.RECOVERY_REQUIRED, REGISTRY_FILE,
				"Promoted project is absent from its registry.",
				"Preserve the transaction and restore exact registry authority.");
		}
		Map<String,Object> active = null;
		Path activePath = install.resolve(ACTIVE_FILE);
		if (Files.exists(activePath, LinkOption.NOFOLLOW_LINKS)) {
			active = readContractMap(activePath,
				WorldBuilderAdaptiveContracts.Kind.ACTIVE_PROJECT);
		}
		writePromotionContractAtomic(manifestPath, manifest,
			WorldBuilderAdaptiveContracts.Kind.PROJECT_MANIFEST,
			project.resolve(".wide-elevation-project-" + token + ".new"));
		String manifestHash = WorldBuilderHashes.sha256(manifestPath);
		Map<String,Object> updated = registryReplacing(registry, projectId,
			string(manifest, "displayName"), string(manifest, "origin"),
			string(manifest, "state"), manifestHash);
		writePromotionContractAtomic(install.resolve(REGISTRY_FILE), updated,
			WorldBuilderAdaptiveContracts.Kind.PROJECT_REGISTRY,
			install.resolve(".wide-elevation-registry-" + token + ".new"));
		if (active != null && projectId.equals(string(active, "projectId"))) {
			writePromotionContractAtomic(activePath, activeProject(projectId, manifestHash),
				WorldBuilderAdaptiveContracts.Kind.ACTIVE_PROJECT,
				install.resolve(".wide-elevation-active-" + token + ".new"));
		}
	}

	static void refuseAdaptiveMutationBeforeTarget(Path requestedWorkspace,
		String operation) throws IOException, WorldBuilderDiscoveryException {
		if (requestedWorkspace == null) return;
		Path workspace = requestedWorkspace.toAbsolutePath().normalize();
		Path manifest = workspace.resolve(PROJECT_FILE).normalize();
		if (!manifest.startsWith(workspace)
			|| !Files.isRegularFile(manifest, LinkOption.NOFOLLOW_LINKS)
			|| Files.isSymbolicLink(manifest)) return;
		try {
			Map<String,Object> project = readContractMap(manifest,
				WorldBuilderAdaptiveContracts.Kind.PROJECT_MANIFEST);
			requireSelfFingerprint(project, "projectFingerprintSha256", true);
			String origin = string(project, "origin");
			if ("standalone-empty".equals(origin)) {
				throw new WorldBuilderDiscoveryException("[" + WorldBuilderErrorCodes.NO_TARGET
					+ "] Standalone project " + string(project, "projectId")
					+ " has no target; " + operation
					+ " stopped before resolving or locking any target path.");
			}
			throw new WorldBuilderDiscoveryException("["
				+ WorldBuilderErrorCodes.UNSUPPORTED_FORMAT
				+ "] Adaptive target mutation is reserved for Phase 6; "
				+ operation + " stopped before target access.");
		} catch (WorldBuilderContractException refusal) {
			throw new WorldBuilderDiscoveryException(
				"Adaptive project mutation preflight failed [" + refusal.code()
					+ "]: " + refusal.getMessage(), refusal);
		}
	}

	static void refuseActiveMutationBeforeTarget(Path requestedInstallRoot,
		String operation) throws IOException, WorldBuilderDiscoveryException {
		try {
			Path install = realDirectory(
				requestedInstallRoot, "World Builder install root");
			RegistryState registry = loadRegistry(install, true);
			ActiveState active = loadActive(install, registry, true);
			if (active.projectId.isEmpty()) {
				throw new WorldBuilderDiscoveryException(
					"No active adaptive project is selected; " + operation
						+ " stopped before target access.");
			}
			Path project = install.resolve(active.manifestRelativePath).getParent();
			refuseAdaptiveMutationBeforeTarget(project, operation);
		} catch (WorldBuilderContractException refusal) {
			throw new WorldBuilderDiscoveryException(
				"Adaptive project mutation preflight failed [" + refusal.code()
					+ "]: " + refusal.getMessage(), refusal);
		}
	}

	private static VerifiedProject updateState(Path install, VerifiedProject verified,
		String state, String locatorDisplay)
		throws IOException, WorldBuilderContractException {
		Map<String,Object> manifest = verified.manifest;
		manifest.put("state", state);
		Map<String,Object> target = object(manifest.get("target"), "target");
		if (!locatorDisplay.isEmpty()) target.put("locatorDisplay", locatorDisplay);
		manifest.put("operations", operations(true, true,
			"ready-attached".equals(state), false));
		bindSelfFingerprint(manifest, "projectFingerprintSha256", true);
		publishUpdatedManifest(install, verified, manifest);
		return verifyProjectDirectory(verified.projectRoot, true);
	}

	private static void publishUpdatedManifest(Path install, VerifiedProject verified,
		Map<String,Object> manifest) throws IOException, WorldBuilderContractException {
		RegistryState registry = loadRegistry(install, true);
		ActiveState active = loadActive(install, registry, false);
		byte[] oldManifest = Files.readAllBytes(verified.projectRoot.resolve(PROJECT_FILE));
		byte[] oldRegistry = readOptionalRegular(install.resolve(REGISTRY_FILE));
		byte[] oldActive = readOptionalRegular(install.resolve(ACTIVE_FILE));
		try {
			writeContractAtomic(verified.projectRoot.resolve(PROJECT_FILE), manifest,
				WorldBuilderAdaptiveContracts.Kind.PROJECT_MANIFEST);
			String manifestHash = WorldBuilderHashes.sha256(
				verified.projectRoot.resolve(PROJECT_FILE));
			RegistryState refreshed = loadRegistryWithoutManifestHashes(install);
			Map<String,Object> updated = registryReplacing(refreshed, verified.projectId,
				string(manifest, "displayName"), string(manifest, "origin"),
				string(manifest, "state"), manifestHash);
			writeContractAtomic(install.resolve(REGISTRY_FILE), updated,
				WorldBuilderAdaptiveContracts.Kind.PROJECT_REGISTRY);
			if (verified.projectId.equals(active.projectId)) {
				writeContractAtomic(install.resolve(ACTIVE_FILE),
					activeProject(verified.projectId, manifestHash),
					WorldBuilderAdaptiveContracts.Kind.ACTIVE_PROJECT);
			}
			loadRegistry(install, true);
		} catch (Exception failure) {
			restoreAtomic(verified.projectRoot.resolve(PROJECT_FILE), oldManifest);
			restoreAtomic(install.resolve(REGISTRY_FILE), oldRegistry);
			restoreAtomic(install.resolve(ACTIVE_FILE), oldActive);
			if (failure instanceof IOException) throw (IOException)failure;
			if (failure instanceof WorldBuilderContractException) {
				throw (WorldBuilderContractException)failure;
			}
			throw problem(WorldBuilderErrorCodes.MUTATION_FAILED, PROJECT_FILE,
				"Project metadata transaction failed and was restored.",
				"Retry the save or selection after checking filesystem health.", failure);
		}
	}

	private static RegistryState loadRegistry(Path install, boolean verifyManifests)
		throws IOException, WorldBuilderContractException {
		Path path = install.resolve(REGISTRY_FILE);
		if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
			return RegistryState.empty();
		}
		Map<String,Object> root = readContractMap(path,
			WorldBuilderAdaptiveContracts.Kind.PROJECT_REGISTRY);
		requireSelfFingerprint(root, "registryFingerprintSha256", false);
		List<RegistryRecord> records = new ArrayList<RegistryRecord>();
		Map<String,RegistryRecord> byId = new LinkedHashMap<String,RegistryRecord>();
		for (Object raw : array(root.get("projects"), "projects")) {
			Map<String,Object> value = object(raw, "project registry record");
			RegistryRecord record = RegistryRecord.from(value);
			if (byId.put(record.projectId, record) != null) {
				throw problem(WorldBuilderErrorCodes.INVENTORY_DUPLICATE, REGISTRY_FILE,
					"Project registry repeats a UUID.",
					"Restore the last verified registry copy.");
			}
			records.add(record);
			if (verifyManifests) {
				Path manifest = safeRegularFile(install,
					record.manifestRelativePath, "registered project manifest");
				if (!record.manifestSha256.equals(WorldBuilderHashes.sha256(manifest))) {
					throw problem(WorldBuilderErrorCodes.SOURCE_CORRUPT,
						record.manifestRelativePath,
						"Registered project manifest hash changed.",
						"Restore the complete install metadata and project backup.");
				}
				Map<String,Object> manifestValue = readContractMap(manifest,
					WorldBuilderAdaptiveContracts.Kind.PROJECT_MANIFEST);
				requireSelfFingerprint(manifestValue, "projectFingerprintSha256", true);
				if (!record.projectId.equals(string(manifestValue, "projectId"))
					|| !record.displayName.equals(string(manifestValue, "displayName"))
					|| !record.origin.equals(string(manifestValue, "origin"))
					|| !record.state.equals(string(manifestValue, "state"))) {
					throw problem(WorldBuilderErrorCodes.SOURCE_CORRUPT,
						record.manifestRelativePath,
						"Registry record disagrees with its project manifest.",
						"Restore the exact matching registry and project metadata.");
				}
			}
		}
		return new RegistryState(root, records, byId);
	}

	private static RegistryState loadRegistryWithoutManifestHashes(Path install)
		throws IOException, WorldBuilderContractException {
		return loadRegistry(install, false);
	}

	private static ActiveState loadActive(Path install, RegistryState registry,
		boolean required) throws IOException, WorldBuilderContractException {
		Path path = install.resolve(ACTIVE_FILE);
		if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
			if (required) throw problem(WorldBuilderErrorCodes.CONTRACT_VALUE_INVALID,
				ACTIVE_FILE, "Active project pointer is missing.",
				"Select one registered project.");
			return new ActiveState("", "", "");
		}
		Map<String,Object> root = readContractMap(path,
			WorldBuilderAdaptiveContracts.Kind.ACTIVE_PROJECT);
		String id = string(root, "projectId");
		String relative = string(root, "manifestRelativePath");
		String hash = string(root, "manifestSha256");
		if (!id.isEmpty()) {
			RegistryRecord record = registry.byId.get(id);
			if (record == null || !record.manifestRelativePath.equals(relative)
				|| !record.manifestSha256.equals(hash)) {
				throw problem(WorldBuilderErrorCodes.SOURCE_CORRUPT, ACTIVE_FILE,
					"Active project pointer disagrees with the registry.",
					"Restore or reselect an exact registered project.");
			}
		}
		return new ActiveState(id, relative, hash);
	}

	private static Map<String,Object> registryWith(RegistryState existing,
		String projectId, String displayName, String origin, String state,
		String manifestHash) throws WorldBuilderContractException {
		if (existing.byId.containsKey(projectId)) {
			throw problem(WorldBuilderErrorCodes.INVENTORY_DUPLICATE, REGISTRY_FILE,
				"Random project UUID collided with an existing project.",
				"Retry project creation; existing data will not be replaced.");
		}
		List<RegistryRecord> values = new ArrayList<RegistryRecord>(existing.records);
		values.add(new RegistryRecord(projectId,
			"projects/" + projectId + "/project.json", manifestHash,
			displayName, origin, state));
		return registry(values);
	}

	private static Map<String,Object> registryReplacing(RegistryState existing,
		String projectId, String displayName, String origin, String state,
		String manifestHash) throws WorldBuilderContractException {
		List<RegistryRecord> values = new ArrayList<RegistryRecord>();
		boolean replaced = false;
		for (RegistryRecord record : existing.records) {
			if (projectId.equals(record.projectId)) {
				values.add(new RegistryRecord(projectId,
					"projects/" + projectId + "/project.json", manifestHash,
					displayName, origin, state));
				replaced = true;
			} else values.add(record);
		}
		if (!replaced) throw problem(WorldBuilderErrorCodes.CONTRACT_VALUE_INVALID,
			REGISTRY_FILE, "Project being saved is not registered.",
			"Restore the matching registry before saving this project.");
		return registry(values);
	}

	private static Map<String,Object> registry(List<RegistryRecord> values)
		throws WorldBuilderContractException {
		Collections.sort(values, new Comparator<RegistryRecord>() {
			@Override public int compare(RegistryRecord left, RegistryRecord right) {
				return left.projectId.compareTo(right.projectId);
			}
		});
		Map<String,Object> root = new LinkedHashMap<String,Object>();
		root.put("schemaVersion", Long.valueOf(1L));
		root.put("manifestType", "world-builder-project-registry");
		List<Object> records = new ArrayList<Object>();
		for (RegistryRecord value : values) records.add(value.toJson());
		root.put("projects", records);
		root.put("registryFingerprintSha256", ZERO_HASH);
		bindSelfFingerprint(root, "registryFingerprintSha256", false);
		WorldBuilderAdaptiveContracts.validateParsed(
			WorldBuilderAdaptiveContracts.Kind.PROJECT_REGISTRY, root);
		return root;
	}

	private static Map<String,Object> activeProject(String id, String manifestHash)
		throws WorldBuilderContractException {
		Map<String,Object> root = new LinkedHashMap<String,Object>();
		root.put("schemaVersion", Long.valueOf(1L));
		root.put("manifestType", "world-builder-active-project");
		root.put("projectId", id);
		root.put("manifestRelativePath", "projects/" + id + "/project.json");
		root.put("manifestSha256", manifestHash);
		WorldBuilderAdaptiveContracts.validateParsed(
			WorldBuilderAdaptiveContracts.Kind.ACTIVE_PROJECT, root);
		return root;
	}

	private static List<Evidence> evidence(Map<String,Object> report)
		throws WorldBuilderContractException {
		List<Evidence> result = new ArrayList<Evidence>();
		Set<String> paths = new HashSet<String>();
		Map<String,Object> descriptor = object(report.get("descriptor"), "descriptor");
		boolean fallback = isPackedFallbackReport(report);
		if (!Boolean.TRUE.equals(descriptor.get("present")) && !fallback) {
			throw problem(WorldBuilderErrorCodes.CONVERSION_BLOCKED, DISCOVERY_FILE,
				"Target-backed projects require descriptor-backed complete evidence.",
				"Add a truthful capability descriptor and rediscover before project creation.");
		}
		if (!fallback) {
			addEvidence(result, paths, "target-capability",
				string(descriptor, "relativePath"), true, -1L,
				string(descriptor, "sha256"), false);
			Map<String,Object> selected = object(
				report.get("selectedConfiguration"), "selectedConfiguration");
			addEvidence(result, paths, "configuration." + string(selected, "role"),
				string(selected, "relativePath"), true, -1L,
				string(selected, "sha256"), false);
		}
		for (Object raw : array(report.get("files"), "files")) {
			Map<String,Object> file = object(raw, "discovery file");
			String role = string(file, "role");
			addEvidence(result, paths, role, string(file, "relativePath"),
				bool(file, "present"), integer(file, "size"),
				string(file, "sha256"), definitionRuntimeRole(role));
		}
		Collections.sort(result);
		return result;
	}

	private static boolean isPackedFallbackReport(Map<String,Object> report)
		throws WorldBuilderContractException {
		Map<String,Object> descriptor = object(report.get("descriptor"), "descriptor");
		Map<String,Object> capability = object(report.get("capability"), "capability");
		Map<String,Object> selected = object(
			report.get("selectedConfiguration"), "selectedConfiguration");
		return !bool(descriptor, "present")
			&& "compatible".equals(string(report, "status"))
			&& "packed".equals(string(report, "representation"))
			&& bool(capability, "resolved")
			&& WorldBuilderPackedLayoutAdapter.ID.equals(
				string(capability, "adapterId"))
			&& WorldBuilderPackedFallbackEvidence.CAPABILITY_ID.equals(
				string(capability, "capabilityId"))
			&& WorldBuilderDiscovery.DEFAULT_CONFIG.equals(
				string(capability, "evidenceRelativePath"))
			&& bool(selected, "present")
			&& "primary".equals(string(selected, "role"))
			&& WorldBuilderDiscovery.DEFAULT_CONFIG.equals(
				string(selected, "relativePath"))
			&& string(capability, "evidenceSha256").equals(
				string(selected, "sha256"));
	}

	private static List<Evidence> withGeneratedFallbackEvidence(
		List<Evidence> discovered,
		List<WorldBuilderReadOnlyTarget.FileState> generated)
		throws WorldBuilderContractException {
		Set<String> generatedPaths = new HashSet<String>();
		for (WorldBuilderReadOnlyTarget.FileState file : generated) {
			generatedPaths.add(file.relativePath);
		}
		List<Evidence> result = new ArrayList<Evidence>();
		Set<String> paths = new HashSet<String>();
		for (Evidence item : discovered) {
			if (!generatedPaths.contains(item.targetRelativePath)) {
				addEvidence(result, paths, item.role, item.targetRelativePath,
					item.present, item.size, item.sha256, item.definitionRuntime);
			}
		}
		for (WorldBuilderReadOnlyTarget.FileState file : generated) {
			addEvidence(result, paths, file.role, file.relativePath, true,
				file.size, file.sha256, definitionRuntimeRole(file.role));
		}
		Collections.sort(result);
		return result;
	}

	private static void addEvidence(List<Evidence> values, Set<String> paths,
		String role, String path, boolean present, long size, String hash,
		boolean definitionRuntime) throws WorldBuilderContractException {
		WorldBuilderPortablePath.require(path, OPERATION);
		if (!paths.add(WorldBuilderPortablePath.collisionKey(path, OPERATION))
			|| present && !WorldBuilderBoundedInventory.isHash(hash)
			|| !present && (size != 0L || !hash.isEmpty())) {
			throw problem(WorldBuilderErrorCodes.INVENTORY_DUPLICATE, DISCOVERY_FILE,
				"Discovery evidence paths or states are duplicated or invalid.",
				"Use one unmodified compatible Phase 1 discovery report.");
		}
		values.add(new Evidence(role, path, present, size, hash, definitionRuntime));
	}

	private static boolean definitionRuntimeRole(String role) {
		return role.contains("definition") || role.contains("runtime")
			|| role.contains("asset");
	}

	private static void copyLayeredPackage(Path original, String packageRelative,
		Path destination, List<WorldBuilderReadOnlyTarget.FileState> files)
		throws IOException, WorldBuilderContractException {
		ensureRealDirectory(destination);
		String prefix = packageRelative + "/";
		for (WorldBuilderReadOnlyTarget.FileState file : files) {
			if (!file.relativePath.startsWith(prefix)) {
				throw problem(WorldBuilderErrorCodes.UNSAFE_PATH, file.relativePath,
					"Layered package evidence escaped its configured package root.",
					"Use one complete contained active layered package.");
			}
			String inside = file.relativePath.substring(prefix.length());
			Path source = original.resolve(file.relativePath).normalize();
			Path target = destination.resolve(inside).normalize();
			requireContained(destination, target, inside);
			copyNewVerified(source, target, file.size, file.sha256);
		}
	}

	private static void writeRuntimeMetadata(Path stage, String projectId,
		String origin, String runtimeSha256, int port,
		WorldBuilderGenericLayeredPackage working) throws IOException {
		Map<String,Object> runtime = new LinkedHashMap<String,Object>();
		runtime.put("schemaVersion", Long.valueOf(1L));
		runtime.put("runtimeType", "adaptive-isolated-world-builder");
		runtime.put("runtimeId", RUNTIME_VERSION);
		runtime.put("applicationRuntimeSha256", runtimeSha256);
		runtime.put("projectId", projectId);
		runtime.put("origin", origin);
		runtime.put("host", "127.0.0.1");
		runtime.put("port", Long.valueOf(port));
		runtime.put("workingPackageRelativePath", WORKING_PACKAGE_DIRECTORY);
		runtime.put("initialLayer", Long.valueOf(working.initialLevel));
		runtime.put("initialX", Long.valueOf(working.initialX));
		runtime.put("initialY", Long.valueOf(working.initialY));
		runtime.put("upstreamAuthoringCapability",
			"adaptive-world-builder-runtime-capability-v2");
		writeNew(stage.resolve(WORKING_RUNTIME_FILE),
			WorldBuilderJsonDocuments.pretty(runtime).getBytes(StandardCharsets.UTF_8));
	}

	private static void requireRuntimeMetadata(Map<String,Object> value,
		String projectId, String origin, String runtimeSha256,
		WorldBuilderGenericLayeredPackage working)
		throws WorldBuilderContractException {
		exact(value, "runtime metadata", "schemaVersion", "runtimeType", "runtimeId",
			"applicationRuntimeSha256", "projectId", "origin", "host", "port",
			"workingPackageRelativePath", "initialLayer", "initialX", "initialY",
			"upstreamAuthoringCapability");
		if (integer(value, "schemaVersion") != 1L
			|| !"adaptive-isolated-world-builder".equals(string(value, "runtimeType"))
			|| !RUNTIME_VERSION.equals(string(value, "runtimeId"))
			|| !runtimeSha256.equals(string(value, "applicationRuntimeSha256"))
			|| !projectId.equals(string(value, "projectId"))
			|| !origin.equals(string(value, "origin"))
			|| !"127.0.0.1".equals(string(value, "host"))
			|| !WORKING_PACKAGE_DIRECTORY.equals(
				string(value, "workingPackageRelativePath"))
			|| integer(value, "initialLayer") != working.initialLevel
			|| integer(value, "initialX") != working.initialX
			|| integer(value, "initialY") != working.initialY
			|| !("adaptive-world-builder-runtime-capability-v1".equals(
				string(value, "upstreamAuthoringCapability"))
				|| "adaptive-world-builder-runtime-capability-v2".equals(
				string(value, "upstreamAuthoringCapability")))) {
			throw problem(WorldBuilderErrorCodes.SOURCE_CORRUPT, WORKING_RUNTIME_FILE,
				"Project runtime metadata is inconsistent with the selected project.",
				"Restore the complete project from a trusted backup.");
		}
		long port = integer(value, "port");
		if (port < 1L || port >= 65535L) throw problem(
			WorldBuilderErrorCodes.CONTRACT_VALUE_INVALID, WORKING_RUNTIME_FILE,
			"Project runtime port is invalid.", "Select a valid isolated loopback port.");
	}

	static int readRuntimePort(Path project)
		throws IOException, WorldBuilderContractException {
		Map<String,Object> runtime = readJsonObject(
			safeRegularFile(project, WORKING_RUNTIME_FILE, "project runtime metadata"),
			WORKING_RUNTIME_FILE);
		long value = integer(runtime, "port");
		if (value < 1L || value >= 65535L) throw problem(
			WorldBuilderErrorCodes.CONTRACT_VALUE_INVALID, WORKING_RUNTIME_FILE,
			"Project runtime port is invalid.", "Select a valid isolated loopback port.");
		return (int)value;
	}

	private static String definitionCatalogPath(Map<String,Object> snapshot)
		throws WorldBuilderContractException {
		for (Object raw : array(snapshot.get("definitionRuntimeFiles"),
			"definitionRuntimeFiles")) {
			Map<String,Object> record = object(raw, "definition evidence");
			String role = string(record, "role");
			if (("server-definition-catalog".equals(role)
				|| "default-definition-catalog".equals(role))
				&& bool(record, "present")) return string(record, "relativePath");
		}
		throw problem(WorldBuilderErrorCodes.DEFINITION_MISMATCH, SNAPSHOT_FILE,
			"Source snapshot has no verified Builder definition catalog.",
			"Restore the complete immutable source snapshot.");
	}

	private static void verifySourceTree(Path project, Map<String,Object> snapshot)
		throws IOException, WorldBuilderContractException {
		Map<String,InventoryRecord> expected = new TreeMap<String,InventoryRecord>();
		for (String key : Arrays.asList("originalFiles", "definitionRuntimeFiles",
			"conversionEvidenceFiles", "layeredBaselineFiles")) {
			for (Object raw : array(snapshot.get(key), key)) {
				InventoryRecord record = InventoryRecord.from(
					object(raw, "source inventory"));
				if (expected.put(record.relativePath, record) != null) {
					throw problem(WorldBuilderErrorCodes.INVENTORY_DUPLICATE,
						SNAPSHOT_FILE, "Source snapshot repeats a path.",
						"Restore the exact immutable source snapshot.");
				}
			}
		}
		for (InventoryRecord record : expected.values()) {
			Path path = project.resolve(record.relativePath).normalize();
			requireContained(project, path, record.relativePath);
			if (!record.present) {
				if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) throw problem(
					WorldBuilderErrorCodes.SOURCE_CORRUPT, record.relativePath,
					"Required immutable absence became present.",
					"Restore the complete project from a trusted backup.");
				continue;
			}
			Path safe = safeRegularFile(project, record.relativePath,
				"immutable source file");
			if (Files.size(safe) != record.size
				|| !record.sha256.equals(WorldBuilderHashes.sha256(safe))) {
				throw problem(WorldBuilderErrorCodes.SOURCE_CORRUPT,
					record.relativePath, "Immutable source bytes changed.",
					"Restore the complete project from a trusted backup; do not rebuild source.");
			}
		}
		Set<String> actual = scanRegularFiles(project.resolve("source"), project);
		Set<String> allowed = new HashSet<String>();
		allowed.add(SNAPSHOT_FILE);
		for (InventoryRecord record : expected.values()) if (record.present) {
			allowed.add(record.relativePath);
		}
		if (!actual.equals(allowed)) {
			throw problem(WorldBuilderErrorCodes.SOURCE_CORRUPT, "source",
				"Immutable source tree has missing or untracked files.",
				"Restore the complete project from a trusted backup.");
		}
	}

	private static void requireExactOriginalTree(Path original, List<Evidence> evidence)
		throws IOException, WorldBuilderContractException {
		Set<String> actual = scanRegularFiles(original, original);
		Set<String> wanted = new HashSet<String>();
		for (Evidence item : evidence) if (item.present) wanted.add(item.targetRelativePath);
		if (!actual.equals(wanted)) throw problem(WorldBuilderErrorCodes.SOURCE_CORRUPT,
			"source/original", "Copied target evidence is incomplete or contains extras.",
			"Discard the unpublished stage and copy exactly the discovery inventory.");
	}

	private static List<InventoryRecord> inventoryPackage(Path project,
		String relativeRoot, String requiredPrefix)
		throws IOException, WorldBuilderContractException {
		List<InventoryRecord> result = new ArrayList<InventoryRecord>();
		Path root = project.resolve(relativeRoot).normalize();
		for (String relative : scanRegularFiles(root, project)) {
			if (!relative.startsWith(requiredPrefix)) throw problem(
				WorldBuilderErrorCodes.UNSAFE_PATH, relative,
				"Layered baseline inventory escaped its immutable package directory.",
				"Restore one contained complete package.");
			String role = relative.endsWith("/manifest.json") ? "layered-package-manifest"
				: relative.endsWith(".raw") ? "layered-terrain"
					: "layered-placement-set";
			Path path = project.resolve(relative);
			result.add(new InventoryRecord(role, relative, true,
				Files.size(path), WorldBuilderHashes.sha256(path)));
		}
		Collections.sort(result);
		return result;
	}

	private static InventoryRecord recordFor(Path project, String role, String relative)
		throws IOException, WorldBuilderContractException {
		Path path = safeRegularFile(project, relative, "source evidence");
		return new InventoryRecord(role, relative, true,
			Files.size(path), WorldBuilderHashes.sha256(path));
	}

	private static List<Object> records(List<? extends InventoryRecord> values) {
		List<InventoryRecord> sorted = new ArrayList<InventoryRecord>(values);
		Collections.sort(sorted);
		List<Object> result = new ArrayList<Object>();
		for (InventoryRecord value : sorted) result.add(value.toJson());
		return result;
	}

	private static Map<String,Object> operations(boolean edit, boolean export,
		boolean importTarget, boolean undo) {
		Map<String,Object> value = new LinkedHashMap<String,Object>();
		value.put("createProject", Boolean.FALSE);
		value.put("edit", Boolean.valueOf(edit));
		value.put("save", Boolean.valueOf(edit));
		value.put("export", Boolean.valueOf(export));
		value.put("import", Boolean.valueOf(importTarget));
		value.put("undo", Boolean.valueOf(undo));
		return value;
	}

	private static Map<String,Object> stateReference(boolean present, String role,
		String relative, String hash) {
		Map<String,Object> value = new LinkedHashMap<String,Object>();
		value.put("present", Boolean.valueOf(present));
		if (!role.isEmpty()) value.put("role", role);
		value.put("relativePath", relative);
		value.put("sha256", hash);
		return value;
	}

	private static Map<String,Object> absentRoleReference() {
		Map<String,Object> value = new LinkedHashMap<String,Object>();
		value.put("present", Boolean.FALSE);
		value.put("role", "");
		value.put("relativePath", "");
		value.put("sha256", "");
		return value;
	}

	private static void writePortableDiscoveryReport(Path path,
		Map<String,Object> report) throws IOException, WorldBuilderContractException {
		String display = string(report, "targetRootDisplay");
		try {
			report.put("targetRootDisplay", "");
			WorldBuilderAdaptiveContracts.validateParsed(
				WorldBuilderAdaptiveContracts.Kind.DISCOVERY_REPORT, report);
			Files.write(path, WorldBuilderJsonDocuments.pretty(report)
				.getBytes(StandardCharsets.UTF_8), StandardOpenOption.TRUNCATE_EXISTING);
			WorldBuilderAdaptiveContracts.read(
				WorldBuilderAdaptiveContracts.Kind.DISCOVERY_REPORT, path);
		} finally {
			report.put("targetRootDisplay", display);
		}
	}

	private static void requireFreshDiscovery(Map<String,Object> report, Path target)
		throws WorldBuilderContractException {
		String status = string(report, "status");
		if (target == null) {
			if (!"standalone".equals(status)) throw problem(
				WorldBuilderErrorCodes.NO_TARGET, "target-root",
				"Target-backed project creation has no target root.",
				"Supply the exact discovered target root.");
			return;
		}
		WorldBuilderAdaptiveDiscoveryReport fresh =
			new WorldBuilderAdaptiveDiscovery().discover(target,
				"compatible".equals(status) ? selectedRole(report) : null);
		if (!status.equals(fresh.status)
			|| !string(report, "discoveryFingerprintSha256").equals(
				fresh.fingerprintSha256())) {
			throw problem(WorldBuilderErrorCodes.TARGET_DRIFT, "target-root",
				"Target discovery no longer matches the approved creation report.",
				"Stop target changes, rediscover, and create a new project.");
		}
	}

	private static String selectedRole(Map<String,Object> report)
		throws WorldBuilderContractException {
		return string(object(report.get("selectedConfiguration"),
			"selectedConfiguration"), "role");
	}

	private static void requireDiscoveryFingerprint(Map<String,Object> report)
		throws WorldBuilderContractException {
		String supplied = string(report, "discoveryFingerprintSha256");
		String display = string(report, "targetRootDisplay");
		report.put("targetRootDisplay", "");
		report.put("discoveryFingerprintSha256", ZERO_HASH);
		String calculated;
		try {
			calculated = WorldBuilderHashes.sha256(
				WorldBuilderJsonDocuments.canonical(report).getBytes(StandardCharsets.UTF_8));
		} finally {
			report.put("targetRootDisplay", display);
			report.put("discoveryFingerprintSha256", supplied);
		}
		if (!supplied.equals(calculated)) throw problem(
			WorldBuilderErrorCodes.CONTRACT_VALUE_INVALID, DISCOVERY_FILE,
			"Discovery report self-fingerprint does not match its content.",
			"Use the unmodified report emitted by discover-adaptive.");
	}

	private static void bindSelfFingerprint(Map<String,Object> value, String field,
		boolean ignoreTargetLocator) throws WorldBuilderContractException {
		value.put(field, ZERO_HASH);
		String locator = null;
		Map<String,Object> target = null;
		if (ignoreTargetLocator) {
			target = object(value.get("target"), "target");
			locator = string(target, "locatorDisplay");
			target.put("locatorDisplay", "");
		}
		String hash;
		try {
			hash = WorldBuilderHashes.sha256(
				WorldBuilderJsonDocuments.canonical(value).getBytes(StandardCharsets.UTF_8));
		} finally {
			if (target != null) target.put("locatorDisplay", locator);
		}
		value.put(field, hash);
	}

	private static void requireSelfFingerprint(Map<String,Object> value, String field,
		boolean ignoreTargetLocator) throws WorldBuilderContractException {
		String supplied = string(value, field);
		bindSelfFingerprint(value, field, ignoreTargetLocator);
		String calculated = string(value, field);
		value.put(field, supplied);
		if (!supplied.equals(calculated)) throw problem(
			WorldBuilderErrorCodes.SOURCE_CORRUPT, field,
			"Contract self-fingerprint does not match its content.",
			"Restore the exact verified metadata document.");
	}

	private static void rollbackCreation(Path install, Path project, Path stage,
		boolean published, byte[] registry, byte[] active, Throwable original)
		throws WorldBuilderContractException {
		try {
			restoreAtomic(install.resolve(ACTIVE_FILE), active);
			restoreAtomic(install.resolve(REGISTRY_FILE), registry);
			if (published) deleteTree(project); else deleteTree(stage);
		} catch (Exception recoveryFailure) {
			recoveryFailure.addSuppressed(original);
			throw problem(WorldBuilderErrorCodes.RECOVERY_REQUIRED,
				PROJECTS_DIRECTORY,
				"Project creation failed and automatic cleanup could not be verified.",
				"Preserve the install and inspect the project stage, registry, and "
					+ "active pointer before any new lifecycle operation.", recoveryFailure);
		}
	}

	private void observe(String milestone, Path stage) throws Exception {
		observer.observe(milestone, stage);
	}

	private static void writeContractNew(Path path, Map<String,Object> value,
		WorldBuilderAdaptiveContracts.Kind kind)
		throws IOException, WorldBuilderContractException {
		WorldBuilderAdaptiveContracts.validateParsed(kind, value);
		writeNew(path, WorldBuilderJsonDocuments.pretty(value)
			.getBytes(StandardCharsets.UTF_8));
		WorldBuilderAdaptiveContracts.read(kind, path);
	}

	private static void writeContractAtomic(Path path, Map<String,Object> value,
		WorldBuilderAdaptiveContracts.Kind kind)
		throws IOException, WorldBuilderContractException {
		WorldBuilderAdaptiveContracts.validateParsed(kind, value);
		byte[] bytes = WorldBuilderJsonDocuments.pretty(value)
			.getBytes(StandardCharsets.UTF_8);
		writeAtomic(path, bytes);
		WorldBuilderAdaptiveContracts.read(kind, path);
	}

	private static void writePromotionContractAtomic(Path path,
		Map<String,Object> value, WorldBuilderAdaptiveContracts.Kind kind,
		Path temporary) throws IOException, WorldBuilderContractException {
		WorldBuilderAdaptiveContracts.validateParsed(kind, value);
		byte[] bytes = WorldBuilderJsonDocuments.pretty(value)
			.getBytes(StandardCharsets.UTF_8);
		Path parent = path.getParent();
		ensureRealDirectory(parent);
		if (!temporary.getParent().equals(parent)
			|| !temporary.getFileName().toString().matches(
				"\\.wide-elevation-(project|registry|active)-[0-9a-f-]{36}\\.new")) {
			throw new IOException("Promotion metadata stage is outside journal authority");
		}
		if (Files.exists(temporary, LinkOption.NOFOLLOW_LINKS)) {
			if (!Files.isRegularFile(temporary, LinkOption.NOFOLLOW_LINKS)
				|| Files.isSymbolicLink(temporary)) {
				throw new IOException("Promotion metadata stage is unsafe");
			}
			Files.delete(temporary);
			WorldBuilderAdaptiveDurability.forceDirectory(parent);
		}
		writeNew(temporary, bytes);
		try {
			Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE,
				StandardCopyOption.REPLACE_EXISTING);
		} catch (AtomicMoveNotSupportedException unsupported) {
			throw new IOException("Filesystem cannot atomically reconcile promotion metadata",
				unsupported);
		}
		WorldBuilderAdaptiveDurability.forceDirectory(parent);
		WorldBuilderAdaptiveContracts.read(kind, path);
	}

	private static void writeNew(Path path, byte[] bytes) throws IOException {
		Files.createDirectories(path.getParent());
		try (FileChannel channel = FileChannel.open(path,
			StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
			java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(bytes);
			while (buffer.hasRemaining()) channel.write(buffer);
			channel.force(true);
		}
	}

	private static void writeAtomic(Path path, byte[] bytes) throws IOException {
		Path parent = path.getParent();
		ensureRealDirectory(parent);
		if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)
			&& (Files.isSymbolicLink(path)
				|| !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))) {
			throw new IOException("Metadata destination is unsafe: " + path.getFileName());
		}
		Path temporary = parent.resolve("." + path.getFileName()
			+ ".new-" + UUID.randomUUID()).normalize();
		writeNew(temporary, bytes);
		try {
			Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE,
				StandardCopyOption.REPLACE_EXISTING);
		} catch (AtomicMoveNotSupportedException unsupported) {
			Files.deleteIfExists(temporary);
			throw new IOException("Filesystem cannot atomically publish project metadata",
				unsupported);
		}
		WorldBuilderAdaptiveDurability.forceDirectory(parent);
	}

	private static void restoreAtomic(Path path, byte[] bytes) throws IOException {
		if (bytes == null) {
			if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
				if (Files.isSymbolicLink(path)
					|| !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
					throw new IOException("Cannot safely remove failed metadata publication");
				}
				Files.delete(path);
			}
		} else {
			writeAtomic(path, bytes);
		}
	}

	private static void moveAtomicNew(Path source, Path destination)
		throws IOException {
		if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
			throw new IOException("Existing project path will not be replaced: " + destination);
		}
		try {
			Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE);
		} catch (AtomicMoveNotSupportedException unsupported) {
			throw new IOException("Filesystem cannot atomically publish the project", unsupported);
		}
	}

	private static void copyNewVerified(Path source, Path destination,
		long expectedSize, String expectedHash)
		throws IOException, WorldBuilderContractException {
		rejectHardLink(source, source.getFileName().toString());
		Files.createDirectories(destination.getParent());
		MessageDigest digest = WorldBuilderHashes.newDigest();
		long size = 0L;
		OpenOption[] options = new OpenOption[] {
			StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS
		};
		try (InputStream input = Files.newInputStream(source, options);
			java.io.OutputStream output = Files.newOutputStream(destination,
				StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
			byte[] buffer = new byte[8192];
			int read;
			while ((read = input.read(buffer)) >= 0) {
				if (read == 0) continue;
				size = Math.addExact(size, read);
				if (size > WorldBuilderContractLimits.MAX_INVENTORY_FILE_BYTES) {
					throw new IOException("Copied evidence exceeds its bounded file limit");
				}
				digest.update(buffer, 0, read);
				output.write(buffer, 0, read);
			}
		}
		String hash = WorldBuilderHashes.hex(digest.digest());
		if (expectedSize >= 0L && size != expectedSize || !expectedHash.equals(hash)
			|| Files.size(destination) != size
			|| !hash.equals(WorldBuilderHashes.sha256(destination))) {
			Files.deleteIfExists(destination);
			throw problem(WorldBuilderErrorCodes.TARGET_DRIFT,
				destination.getFileName().toString(),
				"Copied evidence did not verify byte-for-byte.",
				"Stop target changes and retry from a fresh discovery report.");
		}
	}

	private static void copyTreeExact(final Path source, final Path destination)
		throws IOException {
		if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
			throw new IOException("Working package destination already exists");
		}
		Files.createDirectories(destination.getParent());
		Files.walkFileTree(source, new SimpleFileVisitor<Path>() {
			@Override public FileVisitResult preVisitDirectory(Path directory,
				BasicFileAttributes attributes) throws IOException {
				if (!attributes.isDirectory() || Files.isSymbolicLink(directory)) {
					throw new IOException("Package tree contains an unsafe directory");
				}
				Files.createDirectory(destination.resolve(source.relativize(directory)));
				return FileVisitResult.CONTINUE;
			}
			@Override public FileVisitResult visitFile(Path file,
				BasicFileAttributes attributes) throws IOException {
				if (!attributes.isRegularFile() || Files.isSymbolicLink(file)) {
					throw new IOException("Package tree contains an unsafe file");
				}
				Files.copy(file, destination.resolve(source.relativize(file)),
					StandardCopyOption.COPY_ATTRIBUTES);
				return FileVisitResult.CONTINUE;
			}
		});
	}

	private static Set<String> scanRegularFiles(Path root, Path relativeRoot)
		throws IOException, WorldBuilderContractException {
		if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(root)) {
			throw problem(WorldBuilderErrorCodes.SOURCE_CORRUPT,
				relativeRoot.relativize(root).toString().replace('\\', '/'),
				"Required immutable directory is missing or unsafe.",
				"Restore the complete project from a trusted backup.");
		}
		final Set<String> files = new HashSet<String>();
		final int[] count = new int[] {0};
		Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
			@Override public FileVisitResult preVisitDirectory(Path directory,
				BasicFileAttributes attributes) throws IOException {
				if (!attributes.isDirectory() || Files.isSymbolicLink(directory)) {
					throw new IOException("unsafe immutable directory");
				}
				return FileVisitResult.CONTINUE;
			}
			@Override public FileVisitResult visitFile(Path file,
				BasicFileAttributes attributes) throws IOException {
				if (!attributes.isRegularFile() || Files.isSymbolicLink(file)
					|| ++count[0] > WorldBuilderContractLimits.MAX_INVENTORY_ENTRIES) {
					throw new IOException("unsafe or unbounded immutable file");
				}
				String relative = relativeRoot.relativize(file).toString().replace('\\', '/');
				files.add(relative);
				return FileVisitResult.CONTINUE;
			}
		});
		return files;
	}

	private static Path safeRegularFile(Path root, String relative, String label)
		throws IOException, WorldBuilderContractException {
		Path path = WorldBuilderPortablePath.resolveContained(root, relative, OPERATION);
		Path current = root;
		for (Path segment : root.relativize(path)) {
			current = current.resolve(segment);
			if (Files.isSymbolicLink(current)) throw problem(
				WorldBuilderErrorCodes.UNSAFE_PATH, relative,
				label + " contains a symbolic-link path component.",
				"Use contained regular files and real directories only.");
		}
		if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
			|| Files.isSymbolicLink(path)) throw problem(
			WorldBuilderErrorCodes.UNSAFE_PATH, relative,
			label + " is missing or is not a regular no-follow file.",
			"Restore the exact contained regular file.");
		rejectHardLink(path, relative);
		return path;
	}

	private static void rejectHardLink(Path path, String relative)
		throws IOException, WorldBuilderContractException {
		try {
			Object links = Files.getAttribute(path, "unix:nlink", LinkOption.NOFOLLOW_LINKS);
			if (links instanceof Number && ((Number)links).longValue() > 1L) throw problem(
				WorldBuilderErrorCodes.UNSAFE_PATH, relative,
				"File is hard-linked and containment cannot be proven.",
				"Copy it into one distinct contained regular file.");
		} catch (UnsupportedOperationException ignored) {
			// No portable link-count view; no-follow parent checks remain enforced.
		} catch (IllegalArgumentException ignored) {
			// No portable link-count view; no-follow parent checks remain enforced.
		}
	}

	private static Path realDirectory(Path requested, String label)
		throws IOException, WorldBuilderContractException {
		if (requested == null) throw problem(WorldBuilderErrorCodes.UNSAFE_PATH,
			"target-root", label + " was not supplied.",
			"Supply one existing real directory.");
		Path normalized = requested.toAbsolutePath().normalize();
		if (!Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS)
			|| Files.isSymbolicLink(normalized)) throw problem(
			WorldBuilderErrorCodes.UNSAFE_PATH, "target-root",
			label + " is missing, unsafe, or not a directory.",
			"Use one existing real no-follow directory.");
		return normalized.toRealPath();
	}

	private static void ensureRealDirectory(Path directory) throws IOException {
		if (Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
			if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)
				|| Files.isSymbolicLink(directory)) {
				throw new IOException("Required directory is unsafe: " + directory);
			}
			return;
		}
		Files.createDirectories(directory);
		if (Files.isSymbolicLink(directory)) throw new IOException(
			"Created directory became a symbolic link: " + directory);
	}

	private static FileChannel openLock(Path path) throws IOException {
		if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)
			&& (Files.isSymbolicLink(path)
				|| !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))) {
			throw new IOException("Project registry lock path is unsafe");
		}
		return FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
	}

	private static FileChannel openExistingLock(Path path)
		throws IOException, WorldBuilderContractException {
		if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
			|| Files.isSymbolicLink(path)) {
			throw problem(WorldBuilderErrorCodes.RECOVERY_REQUIRED,
				PROJECTS_DIRECTORY + "/.registry.lock",
				"Project registry lock is missing or unsafe for read-only validation.",
				"Restore the exact existing registry lock; validation will not create or repair it.");
		}
		return FileChannel.open(path, StandardOpenOption.READ,
			StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
	}

	private static FileLock tryLock(FileChannel channel) throws IOException {
		try {
			return channel.tryLock();
		} catch (OverlappingFileLockException busy) {
			return null;
		}
	}

	private static byte[] readOptionalRegular(Path path)
		throws IOException, WorldBuilderContractException {
		if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return null;
		Path safe = safeRegularFile(path.getParent(), path.getFileName().toString(),
			"project metadata");
		if (Files.size(safe) > WorldBuilderContractLimits.MAX_JSON_BYTES) throw problem(
			WorldBuilderErrorCodes.CONTRACT_LIMIT_EXCEEDED,
			path.getFileName().toString(), "Project metadata is too large.",
			"Restore one bounded canonical metadata file.");
		return Files.readAllBytes(safe);
	}

	private static void requireContained(Path root, Path child, String label)
		throws WorldBuilderContractException {
		if (!child.toAbsolutePath().normalize().startsWith(root.toAbsolutePath().normalize())) {
			throw problem(WorldBuilderErrorCodes.UNSAFE_PATH, label,
				"Project path escaped its declared root.",
				"Use normalized contained project-relative paths only.");
		}
	}

	private static void deleteTree(Path root) throws IOException {
		if (root == null || !Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return;
		if (Files.isSymbolicLink(root)) throw new IOException("Refusing to delete linked stage");
		Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
			@Override public FileVisitResult visitFile(Path file,
				BasicFileAttributes attributes) throws IOException {
				if (!attributes.isRegularFile() || Files.isSymbolicLink(file)) {
					throw new IOException("Refusing to delete unsafe staged entry");
				}
				Files.delete(file);
				return FileVisitResult.CONTINUE;
			}
			@Override public FileVisitResult postVisitDirectory(Path directory,
				IOException failure) throws IOException {
				if (failure != null) throw failure;
				Files.delete(directory);
				return FileVisitResult.CONTINUE;
			}
		});
	}

	private static Map<String,Object> readContractMap(Path path,
		WorldBuilderAdaptiveContracts.Kind kind)
		throws IOException, WorldBuilderContractException {
		WorldBuilderAdaptiveContracts.read(kind, path);
		try {
			return WorldBuilderJsonDocuments.readObject(path);
		} catch (WorldBuilderDiscoveryException malformed) {
			throw problem(WorldBuilderErrorCodes.MALFORMED_JSON,
				path.getFileName().toString(), "Contract JSON is malformed.",
				"Restore one bounded canonical contract file.", malformed);
		}
	}

	private static Map<String,Object> readJsonObject(Path path, String relative)
		throws IOException, WorldBuilderContractException {
		try {
			return WorldBuilderJsonDocuments.readObject(path);
		} catch (WorldBuilderDiscoveryException malformed) {
			throw problem(WorldBuilderErrorCodes.MALFORMED_JSON, relative,
				"Project JSON is malformed: " + malformed.getMessage(),
				"Restore one strict bounded UTF-8 project document.", malformed);
		}
	}

	private static String requireDisplayName(String value)
		throws WorldBuilderContractException {
		if (value == null || value.trim().isEmpty() || value.length() > 512) {
			throw problem(WorldBuilderErrorCodes.CONTRACT_VALUE_INVALID, PROJECT_FILE,
				"Project display name must contain 1 to 512 characters.",
				"Choose a short descriptive project name.");
		}
		for (int index = 0; index < value.length(); index++) {
			if (Character.isISOControl(value.charAt(index))) throw problem(
				WorldBuilderErrorCodes.CONTRACT_VALUE_INVALID, PROJECT_FILE,
				"Project display name contains control characters.",
				"Use ordinary printable text.");
		}
		return value;
	}

	private static String requireUuid(String value)
		throws WorldBuilderContractException {
		try {
			String canonical = UUID.fromString(value).toString();
			if (!canonical.equals(value)) throw new IllegalArgumentException();
			return canonical;
		} catch (RuntimeException invalid) {
			throw problem(WorldBuilderErrorCodes.CONTRACT_VALUE_INVALID, REGISTRY_FILE,
				"Project selection is not one canonical lowercase UUID.",
				"List projects and use one exact registered UUID.");
		}
	}

	private static Map<String,Object> object(Object value, String label)
		throws WorldBuilderContractException {
		if (!(value instanceof Map)) throw problem(
			WorldBuilderErrorCodes.CONTRACT_VALUE_INVALID, label,
			"Expected a contract object.", "Restore the canonical contract.");
		@SuppressWarnings("unchecked") Map<String,Object> result = (Map<String,Object>)value;
		return result;
	}

	private static List<?> array(Object value, String label)
		throws WorldBuilderContractException {
		if (!(value instanceof List)) throw problem(
			WorldBuilderErrorCodes.CONTRACT_VALUE_INVALID, label,
			"Expected a contract array.", "Restore the canonical contract.");
		return (List<?>)value;
	}

	private static String string(Map<String,Object> value, String key)
		throws WorldBuilderContractException {
		Object raw = value.get(key);
		if (!(raw instanceof String)) throw problem(
			WorldBuilderErrorCodes.CONTRACT_VALUE_INVALID, key,
			"Expected a string contract field.", "Restore the canonical contract.");
		return (String)raw;
	}

	private static long integer(Map<String,Object> value, String key)
		throws WorldBuilderContractException {
		Object raw = value.get(key);
		if (!(raw instanceof Long)) throw problem(
			WorldBuilderErrorCodes.CONTRACT_VALUE_INVALID, key,
			"Expected an integer contract field.", "Restore the canonical contract.");
		return ((Long)raw).longValue();
	}

	private static boolean bool(Map<String,Object> value, String key)
		throws WorldBuilderContractException {
		Object raw = value.get(key);
		if (!(raw instanceof Boolean)) throw problem(
			WorldBuilderErrorCodes.CONTRACT_VALUE_INVALID, key,
			"Expected a boolean contract field.", "Restore the canonical contract.");
		return ((Boolean)raw).booleanValue();
	}

	private static void exact(Map<String,Object> value, String label, String... keys)
		throws WorldBuilderContractException {
		Set<String> expected = new HashSet<String>(Arrays.asList(keys));
		if (!value.keySet().equals(expected)) throw problem(
			WorldBuilderErrorCodes.CONTRACT_KEYS_INVALID, label,
			"Metadata contains missing or unexpected fields.",
			"Restore the exact versioned metadata document.");
	}

	private static WorldBuilderContractException problem(
		String code, String path, String message, String nextStep) {
		return new WorldBuilderContractException(code, OPERATION, path, false,
			message, nextStep);
	}

	private static WorldBuilderContractException problem(
		String code, String path, String message, String nextStep, Throwable cause) {
		return new WorldBuilderContractException(code, OPERATION, path, false,
			message, nextStep, cause);
	}

	static final class ProjectResult {
		final Path projectRoot;
		final String projectId;
		final String origin;
		final String state;
		final String workingFingerprintSha256;
		final int port;

		ProjectResult(Path projectRoot, String projectId, String origin, String state,
			String workingFingerprintSha256, int port) {
			this.projectRoot = projectRoot;
			this.projectId = projectId;
			this.origin = origin;
			this.state = state;
			this.workingFingerprintSha256 = workingFingerprintSha256;
			this.port = port;
		}

		String toJson() {
			Map<String,Object> value = new LinkedHashMap<String,Object>();
			value.put("status", "project-ready");
			value.put("projectId", projectId);
			value.put("origin", origin);
			value.put("state", state);
			value.put("projectRoot", projectRoot.toString());
			value.put("workingFingerprintSha256", workingFingerprintSha256);
			value.put("host", "127.0.0.1");
			value.put("port", Long.valueOf(port));
			return WorldBuilderJsonDocuments.pretty(value);
		}
	}

	static final class VerifiedProject {
		final Path projectRoot;
		final String projectId;
		final String origin;
		final String state;
		final Map<String,Object> manifest;
		final Map<String,Object> snapshot;
		final Map<String,Object> discoveryReport;
		final WorldBuilderCompatibilityEvidence.DefinitionCatalog definitions;
		final WorldBuilderGenericLayeredPackage baseline;
		final WorldBuilderGenericLayeredPackage working;

		VerifiedProject(Path projectRoot, String projectId, String origin, String state,
			Map<String,Object> manifest, Map<String,Object> snapshot,
			Map<String,Object> discoveryReport,
			WorldBuilderCompatibilityEvidence.DefinitionCatalog definitions,
			WorldBuilderGenericLayeredPackage baseline,
			WorldBuilderGenericLayeredPackage working) {
			this.projectRoot = projectRoot;
			this.projectId = projectId;
			this.origin = origin;
			this.state = state;
			this.manifest = manifest;
			this.snapshot = snapshot;
			this.discoveryReport = discoveryReport;
			this.definitions = definitions;
			this.baseline = baseline;
			this.working = working;
		}
	}

	private static class InventoryRecord implements Comparable<InventoryRecord> {
		final String role;
		final String relativePath;
		final boolean present;
		final long size;
		final String sha256;

		InventoryRecord(String role, String relativePath, boolean present,
			long size, String sha256) {
			this.role = role;
			this.relativePath = relativePath;
			this.present = present;
			this.size = size;
			this.sha256 = sha256;
		}

		static InventoryRecord from(Map<String,Object> value)
			throws WorldBuilderContractException {
			return new InventoryRecord(string(value, "role"),
				string(value, "relativePath"), bool(value, "present"),
				integer(value, "size"), string(value, "sha256"));
		}

		Map<String,Object> toJson() {
			Map<String,Object> value = new LinkedHashMap<String,Object>();
			value.put("role", role);
			value.put("relativePath", relativePath);
			value.put("present", Boolean.valueOf(present));
			value.put("size", Long.valueOf(size));
			value.put("sha256", sha256);
			return value;
		}

		@Override public int compareTo(InventoryRecord other) {
			int result = relativePath.compareTo(other.relativePath);
			return result == 0 ? role.compareTo(other.role) : result;
		}
	}

	private static final class Evidence extends InventoryRecord {
		final String targetRelativePath;
		final boolean definitionRuntime;

		Evidence(String role, String targetRelativePath, boolean present,
			long size, String sha256, boolean definitionRuntime) {
			super(role, "source/original/" + targetRelativePath,
				present, size, sha256);
			this.targetRelativePath = targetRelativePath;
			this.definitionRuntime = definitionRuntime;
		}
	}

	private static final class PreparedOrigin {
		final List<InventoryRecord> originalEvidence;
		final List<InventoryRecord> definitionEvidence;
		final String adapterId;
		final String capabilityId;
		final String selectedConfigurationRole;
		final String selectedConfigurationTargetPath;
		final String selectedConfigurationSourcePath;
		final String selectedConfigurationSha256;
		final String originDescriptorSourcePath;
		final String definitionSha256;
		final String packageFingerprintSha256;
		final String conversionFingerprintSha256;
		final String importProfileId;
		final boolean installEnabled;

		PreparedOrigin(List<InventoryRecord> originalEvidence,
			List<InventoryRecord> definitionEvidence, String adapterId,
			String capabilityId, String selectedConfigurationRole,
			String selectedConfigurationTargetPath,
			String selectedConfigurationSourcePath,
			String selectedConfigurationSha256,
			String originDescriptorSourcePath, String definitionSha256,
			String packageFingerprintSha256, String conversionFingerprintSha256,
			String importProfileId, boolean installEnabled) {
			this.originalEvidence = originalEvidence;
			this.definitionEvidence = definitionEvidence;
			this.adapterId = adapterId;
			this.capabilityId = capabilityId;
			this.selectedConfigurationRole = selectedConfigurationRole;
			this.selectedConfigurationTargetPath = selectedConfigurationTargetPath;
			this.selectedConfigurationSourcePath = selectedConfigurationSourcePath;
			this.selectedConfigurationSha256 = selectedConfigurationSha256;
			this.originDescriptorSourcePath = originDescriptorSourcePath;
			this.definitionSha256 = definitionSha256;
			this.packageFingerprintSha256 = packageFingerprintSha256;
			this.conversionFingerprintSha256 = conversionFingerprintSha256;
			this.importProfileId = importProfileId;
			this.installEnabled = installEnabled;
		}

		static PreparedOrigin target(Path projectStage, List<Evidence> evidence,
			WorldBuilderTargetCapability capability,
			WorldBuilderAdaptiveConfiguration configuration,
			String packageFingerprint, String conversionFingerprint)
			throws IOException, WorldBuilderContractException {
			List<InventoryRecord> original = new ArrayList<InventoryRecord>();
			List<InventoryRecord> definitions = new ArrayList<InventoryRecord>();
			String descriptor = "";
			for (Evidence item : evidence) {
				InventoryRecord recorded = item;
				if (item.present) {
					Path copied = safeRegularFile(projectStage, item.relativePath,
						"copied target evidence");
					recorded = new InventoryRecord(item.role, item.relativePath, true,
						Files.size(copied), WorldBuilderHashes.sha256(copied));
				}
				(item.definitionRuntime ? definitions : original).add(recorded);
				if ("target-capability".equals(item.role)) descriptor = item.relativePath;
			}
			Path bundleRoot = projectStage.resolve(
				WorldBuilderProjectContentBundle.SOURCE_DIRECTORY);
			if (Files.exists(bundleRoot, LinkOption.NOFOLLOW_LINKS)) {
				WorldBuilderProjectContentBundle.read(bundleRoot);
				for (String relative : scanRegularFiles(bundleRoot, projectStage)) {
					definitions.add(recordFor(projectStage,
						relative.endsWith("/manifest.json")
							? "project-content-bundle-manifest"
							: "project-content-bundle-file",
						relative));
				}
			}
			String profile = capability.mutationProfileId.isEmpty()
				? "no-import-v1" : capability.mutationProfileId;
			return new PreparedOrigin(original, definitions, capability.adapterId,
				capability.capabilityId, configuration.configurationId,
				configuration.relativePath,
				"source/original/" + configuration.relativePath,
				configuration.sha256, descriptor,
				capability.definitionCatalogSha256, packageFingerprint,
				conversionFingerprint, profile, capability.installEnabled);
		}

		static PreparedOrigin empty(WorldBuilderEmptyWorldGenerator.Result empty) {
			List<InventoryRecord> original = Arrays.<InventoryRecord>asList(
				new InventoryRecord("empty-origin",
					WorldBuilderEmptyWorldGenerator.DESCRIPTOR_PATH, true,
					empty.descriptorSize, empty.descriptorSha256));
			List<InventoryRecord> definitions = Arrays.<InventoryRecord>asList(
				new InventoryRecord("default-definition-catalog",
					WorldBuilderEmptyWorldGenerator.CATALOG_PATH, true,
					empty.catalogSize, empty.catalogSha256),
				new InventoryRecord("default-runtime-evidence",
					WorldBuilderEmptyWorldGenerator.RUNTIME_PATH, true,
					empty.runtimeEvidenceSize, empty.runtimeEvidenceSha256));
			return new PreparedOrigin(original, definitions, "", "", "", "", "", "",
				WorldBuilderEmptyWorldGenerator.DESCRIPTOR_PATH,
				empty.catalogSha256, empty.packageFingerprintSha256, "", "", false);
		}
	}

	private static final class RegistryRecord {
		final String projectId;
		final String manifestRelativePath;
		final String manifestSha256;
		final String displayName;
		final String origin;
		final String state;

		RegistryRecord(String projectId, String manifestRelativePath,
			String manifestSha256, String displayName, String origin, String state) {
			this.projectId = projectId;
			this.manifestRelativePath = manifestRelativePath;
			this.manifestSha256 = manifestSha256;
			this.displayName = displayName;
			this.origin = origin;
			this.state = state;
		}

		static RegistryRecord from(Map<String,Object> value)
			throws WorldBuilderContractException {
			return new RegistryRecord(string(value, "projectId"),
				string(value, "manifestRelativePath"),
				string(value, "manifestSha256"), string(value, "displayName"),
				string(value, "origin"), string(value, "state"));
		}

		Map<String,Object> toJson() {
			Map<String,Object> value = new LinkedHashMap<String,Object>();
			value.put("projectId", projectId);
			value.put("manifestRelativePath", manifestRelativePath);
			value.put("manifestSha256", manifestSha256);
			value.put("displayName", displayName);
			value.put("origin", origin);
			value.put("state", state);
			return value;
		}
	}

	private static final class RegistryState {
		final Map<String,Object> document;
		final List<RegistryRecord> records;
		final Map<String,RegistryRecord> byId;

		RegistryState(Map<String,Object> document, List<RegistryRecord> records,
			Map<String,RegistryRecord> byId) {
			this.document = document;
			this.records = Collections.unmodifiableList(records);
			this.byId = Collections.unmodifiableMap(byId);
		}

		static RegistryState empty() throws WorldBuilderContractException {
			return new RegistryState(registry(new ArrayList<RegistryRecord>()),
				new ArrayList<RegistryRecord>(),
				new LinkedHashMap<String,RegistryRecord>());
		}
	}

	private static final class ActiveState {
		final String projectId;
		final String manifestRelativePath;
		final String manifestSha256;
		ActiveState(String projectId, String manifestRelativePath, String manifestSha256) {
			this.projectId = projectId;
			this.manifestRelativePath = manifestRelativePath;
			this.manifestSha256 = manifestSha256;
		}
	}
}
