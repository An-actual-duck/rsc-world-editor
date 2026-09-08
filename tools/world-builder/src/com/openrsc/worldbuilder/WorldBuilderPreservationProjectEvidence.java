package com.openrsc.worldbuilder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** Genuine project baseline handoff. Never promotes edited maps or historical code to runtime authority. */
final class WorldBuilderPreservationProjectEvidence {
	static final String BOUNDARY = "reviewed-preservation-project-baseline-v1";
	static final String STAGED_SOURCE = "migration/source/preservation-project";
	private static final String REPORT = WorldBuilderAdaptiveProjectLifecycle.DISCOVERY_FILE;
	private static final List<String> ROOTS = Arrays.asList("source/original", "source/migration/decoder", "source/migration/input");
	private WorldBuilderPreservationProjectEvidence() { }

	static Verified open(Path project, Path exactTarget, WorldBuilderProviderCatalog.Composition selected)
		throws IOException, WorldBuilderContractException {
		WorldBuilderAdaptiveProjectLifecycle.VerifiedProject verified =
			WorldBuilderAdaptiveProjectLifecycle.verifyProjectDirectory(project, false);
		if (!"target-packed".equals(verified.origin)
			|| !WorldBuilderPreservationLayoutAdapter.report(verified.discoveryReport)
			|| !WorldBuilderPreservationLayoutAdapter.ID.equals(verified.snapshot.get("adapterId"))
			|| !WorldBuilderPreservationLayoutAdapter.CAPABILITY.equals(verified.snapshot.get("capabilityId")))
			throw blocked("Upgrade requires a genuine Preservation conversion project.");
		if (!WorldBuilderJsonDocuments.canonical(WorldBuilderCurrentBaseProjectContent.verifiedIdentity(project))
			.equals(WorldBuilderJsonDocuments.canonical(selected.identity)))
			throw blocked("Project native Base composition differs from the selected upgrade composition.");
		WorldBuilderReadOnlyTarget target = WorldBuilderReadOnlyTarget.open(exactTarget);
		Object locator = ((Map<?,?>)verified.manifest.get("target")).get("locatorDisplay");
		if (!target.root.toString().equals(locator)) throw blocked("Project was not created from this exact target path.");
		WorldBuilderAdaptiveDiscoveryReport fresh = new WorldBuilderAdaptiveDiscovery().discover(target.root, "preservation");
		if (!"compatible".equals(fresh.status) || !fresh.fingerprintSha256().equals(verified.discoveryReport.get("discoveryFingerprintSha256")))
			throw blocked("Historical target changed after project creation; review a fresh discovery.");
		return new Verified(project, selected, WorldBuilderPreservationMapEvidence.reopen(project, selected));
	}

	/** Caller must first authenticate the transaction's retained source inventory; this does not revisit a live historical target. */
	static Verified reopenStaged(Path stagedRoot, WorldBuilderProviderCatalog.Composition selected, Map<String,Object> mapMigration)
		throws IOException, WorldBuilderContractException {
		if (!BOUNDARY.equals(mapMigration.get("executionBoundary")) || !Boolean.TRUE.equals(mapMigration.get("packageReady")))
			throw blocked("Staged map migration is not the genuine baseline execution boundary.");
		Verified result = new Verified(stagedRoot, selected, WorldBuilderPreservationMapEvidence.reopen(stagedRoot, selected));
		if (!result.discoveryReportSha256().equals(mapMigration.get("discoveryReportSha256")))
			throw blocked("Retained discovery evidence changed.");
		requireInspection(result.inspectConversion(), mapMigration);
		return result;
	}

	static void requireInspection(WorldBuilderPackedConverter.Inspection actual, Map<String,Object> expected)
		throws WorldBuilderContractException {
		if (!actual.sourceFingerprintSha256.equals(expected.get("preparedSourceFingerprintSha256"))
			|| !actual.planFingerprintSha256.equals(expected.get("conversionPlanFingerprintSha256"))
			|| !actual.planSha256.equals(expected.get("conversionPlanSha256"))
			|| !actual.reportSha256.equals(expected.get("conversionReportSha256"))
			|| !actual.reconciliationSha256.equals(expected.get("discoveryReconciliationSha256"))
			|| !actual.outputFingerprintSha256.equals(expected.get("outputPackageFingerprintSha256"))
			|| !Long.valueOf(actual.terrainCount).equals(expected.get("terrainCount"))
			|| !Long.valueOf(actual.placementCount).equals(expected.get("placementCount"))
			|| !WorldBuilderJsonDocuments.canonical(actual.outputInventory).equals(WorldBuilderJsonDocuments.canonical(expected.get("outputInventory"))))
			throw blocked("Reopened genuine conversion differs from the confirmed migration plan.");
	}

	static final class Verified {
		private final Path root;
		private final WorldBuilderProviderCatalog.Composition selected;
		private final String fingerprint;
		private final List<WorldBuilderBoundedInventory.Record> inventory;
		private Verified(Path root, WorldBuilderProviderCatalog.Composition selected, WorldBuilderPreservationMapEvidence.Prepared prepared)
			throws IOException, WorldBuilderContractException {
			this.root = root; this.selected = selected; this.fingerprint = prepared.fingerprintSha256;
			this.inventory = inventory(root);
		}
		String discoveryReportSha256() throws IOException, WorldBuilderContractException {
			reverify(); return WorldBuilderHashes.sha256(WorldBuilderReadOnlyTarget.open(root).requiredFile(REPORT));
		}
		WorldBuilderPackedConverter.Inspection inspectConversion() throws IOException, WorldBuilderContractException {
			return new WorldBuilderPackedConverter().inspectPreservation(reverify());
		}
		WorldBuilderPackedConverter.Result convert(Path output) throws IOException, WorldBuilderContractException {
			WorldBuilderPackedConverter.Result result = new WorldBuilderPackedConverter().convertPreservationDetached(reverify(), output);
			reverify(); return result;
		}
		void stageSource(Path newRoot) throws IOException, WorldBuilderContractException {
			reverify();
			if (newRoot == null || !newRoot.isAbsolute() || !newRoot.equals(newRoot.normalize())
				|| newRoot.getParent() == null || !newRoot.getParent().equals(newRoot.getParent().toRealPath())
				|| newRoot.startsWith(root) || root.startsWith(newRoot) || Files.exists(newRoot, LinkOption.NOFOLLOW_LINKS))
				throw blocked("Retained source requires a new canonical disjoint transaction directory.");
			Files.createDirectory(newRoot, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
			WorldBuilderReadOnlyTarget source = WorldBuilderReadOnlyTarget.open(root);
			for (WorldBuilderBoundedInventory.Record entry : inventory) {
				Path output = newRoot.resolve(entry.relativePath);
				Files.createDirectories(output.getParent(), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
				Files.copy(source.requiredFile(entry.relativePath), output, StandardCopyOption.COPY_ATTRIBUTES);
				Files.setPosixFilePermissions(output, PosixFilePermissions.fromString("rw-------"));
				WorldBuilderAdaptiveDurability.forceFile(output);
				if (Files.size(output) != entry.size || !WorldBuilderHashes.sha256(output).equals(entry.sha256))
					throw blocked("Retained source copy drifted during staging.");
			}
			try (java.util.stream.Stream<Path> paths = Files.walk(newRoot)) {
				for (Path path : (Iterable<Path>)paths.sorted(java.util.Comparator.reverseOrder())::iterator)
					if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) WorldBuilderAdaptiveDurability.forceDirectory(path);
			}
			WorldBuilderAdaptiveDurability.forceDirectory(newRoot.getParent());
			reverify();
		}
		private WorldBuilderPreservationMapEvidence.Prepared reverify() throws IOException, WorldBuilderContractException {
			List<WorldBuilderBoundedInventory.Record> actual = inventory(root);
			if (actual.size() != inventory.size()) throw blocked("Retained historical source closure changed.");
			for (int i = 0; i < actual.size(); i++) {
				WorldBuilderBoundedInventory.Record a = actual.get(i), b = inventory.get(i);
				if (!a.relativePath.equals(b.relativePath) || a.size != b.size || !a.sha256.equals(b.sha256))
					throw blocked("Retained historical source bytes changed.");
			}
			WorldBuilderPreservationMapEvidence.Prepared result = WorldBuilderPreservationMapEvidence.reopen(root, selected);
			if (!fingerprint.equals(result.fingerprintSha256)) throw blocked("Retained historical derivation changed.");
			return result;
		}
	}

	private static List<WorldBuilderBoundedInventory.Record> inventory(Path root) throws IOException, WorldBuilderContractException {
		WorldBuilderReadOnlyTarget source = WorldBuilderReadOnlyTarget.open(root);
		List<String> paths = new ArrayList<String>(); paths.add(REPORT);
		for (String name : ROOTS) {
			Path directory = root.resolve(name);
			if (!directory.equals(directory.toRealPath()) || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) throw blocked("Retained source namespace is unsafe.");
			try (java.util.stream.Stream<Path> walk = Files.walk(directory)) {
				int count = 0;
				for (Path path : (Iterable<Path>)walk::iterator) {
					if (++count > WorldBuilderContractLimits.MAX_INVENTORY_ENTRIES || Files.isSymbolicLink(path)) throw blocked("Retained source tree is unsafe or too large.");
					if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) continue;
					String relative = root.relativize(path).toString().replace('\\', '/');
					if (relative.startsWith("source/original/") && WorldBuilderPreservationPersistentInputs.admits(relative.substring("source/original/".length())))
						throw blocked("Private historical inputs must never be retained in project map evidence.");
					paths.add(relative);
				}
			}
		}
		java.util.Collections.sort(paths);
		List<WorldBuilderBoundedInventory.Record> result = new ArrayList<WorldBuilderBoundedInventory.Record>();
		for (String relative : paths) {
			WorldBuilderReadOnlyTarget.FileState state = source.requiredState("preservation-public-source", relative);
			result.add(new WorldBuilderBoundedInventory.Record(state.role, relative, true, state.size, state.sha256));
		}
		return result;
	}
	private static WorldBuilderContractException blocked(String message) {
		return new WorldBuilderContractException(WorldBuilderErrorCodes.CONVERSION_BLOCKED, "preservation-project-evidence", message);
	}
}
