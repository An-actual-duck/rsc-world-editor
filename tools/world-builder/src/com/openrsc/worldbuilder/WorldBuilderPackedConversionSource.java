package com.openrsc.worldbuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Exact immutable evidence boundary between Phase 1 discovery and packed conversion. */
final class WorldBuilderPackedConversionSource {
	private static final String OPERATION = "convert-packed";
	private static final String ZERO_HASH =
		"0000000000000000000000000000000000000000000000000000000000000000";

	final WorldBuilderReadOnlyTarget target;
	final Path canonicalSourceRoot;
	final Path reportedTargetRoot;
	final Path canonicalReportedTargetRoot;
	final Map<String,Object> discoveryReport;
	final String sourceFingerprintSha256;
	final String selectedConfigurationRole;
	final String selectedConfigurationRelativePath;
	final String selectedConfigurationSha256;
	final List<WorldBuilderBoundedInventory.Record> inputs;
	private WorldBuilderPreservationMapEvidence.Prepared preservationSource;

	private WorldBuilderPackedConversionSource(
		WorldBuilderReadOnlyTarget target,
		Path canonicalSourceRoot,
		Path reportedTargetRoot,
		Path canonicalReportedTargetRoot,
		Map<String,Object> discoveryReport,
		String sourceFingerprintSha256,
		String selectedConfigurationRole,
		String selectedConfigurationRelativePath,
		String selectedConfigurationSha256,
		List<WorldBuilderBoundedInventory.Record> inputs) {
		this.target = target;
		this.canonicalSourceRoot = canonicalSourceRoot;
		this.reportedTargetRoot = reportedTargetRoot;
		this.canonicalReportedTargetRoot = canonicalReportedTargetRoot;
		this.discoveryReport = discoveryReport;
		this.sourceFingerprintSha256 = sourceFingerprintSha256;
		this.selectedConfigurationRole = selectedConfigurationRole;
		this.selectedConfigurationRelativePath = selectedConfigurationRelativePath;
		this.selectedConfigurationSha256 = selectedConfigurationSha256;
		this.inputs = Collections.unmodifiableList(
			new ArrayList<WorldBuilderBoundedInventory.Record>(inputs));
	}

	static WorldBuilderPackedConversionSource open(Path requestedSourceRoot, Path reportPath)
		throws IOException, WorldBuilderContractException {
		return openInternal(requestedSourceRoot, reportPath, null, null);
	}

	/** Separate immutable data-only authority; no caller-authored discovery flag is accepted. */
	static WorldBuilderPackedConversionSource openPreservation(WorldBuilderPreservationMapEvidence.Prepared genuine)
		throws IOException, WorldBuilderContractException {
		genuine.reverify();
		WorldBuilderReadOnlyTarget target = WorldBuilderReadOnlyTarget.open(genuine.inputRoot);
		List<WorldBuilderBoundedInventory.Record> verified = verifyExactTree(target, genuine.inputs);
		WorldBuilderPackedConversionSource source = new WorldBuilderPackedConversionSource(target, genuine.inputRoot,
			genuine.originalRoot, genuine.originalRoot, Collections.<String,Object>emptyMap(),
			genuine.fingerprintSha256, "preservation-data", "derivation.json", genuine.derivationSha256, verified);
		source.preservationSource = genuine;
		return source;
	}

	/**
	 * Phase 3-only entry point for an unpublished project stage.  The normal
	 * conversion CLI must continue to reject every source below the reported
	 * target; a project installed inside that target can use only the exact
	 * source/original child of its unique staging directory.
	 */
	static WorldBuilderPackedConversionSource openForProject(
		Path requestedSourceRoot, Path reportPath, Path requestedProjectStage)
		throws IOException, WorldBuilderContractException {
		return openInternal(requestedSourceRoot, reportPath, requestedProjectStage,
			"source/original");
	}

	/** Exact secondary source namespace for a layered-target migration project. */
	static WorldBuilderPackedConversionSource openForMigrationProject(
		Path requestedSourceRoot, Path reportPath, Path requestedProjectStage)
		throws IOException, WorldBuilderContractException {
		return openInternal(requestedSourceRoot, reportPath, requestedProjectStage,
			"source/migration/input");
	}

	private static WorldBuilderPackedConversionSource openInternal(
		Path requestedSourceRoot, Path reportPath, Path requestedProjectStage,
		String requiredProjectSource)
		throws IOException, WorldBuilderContractException {
		Map<String,Object> report = readReport(reportPath);
		WorldBuilderAdaptiveContracts.validateParsed(
			WorldBuilderAdaptiveContracts.Kind.DISCOVERY_REPORT, report);
		String sourceFingerprint = requireReportFingerprint(report);
		if (!"compatible".equals(string(report, "status"))
			|| !"packed".equals(string(report, "representation"))) {
			throw blocked("The discovery report does not describe a compatible packed target.",
				"Run Phase 1 discovery against a supported packed target first.");
		}
		Map<String,Object> capability = object(report.get("capability"), "capability");
		if (!Boolean.TRUE.equals(capability.get("resolved"))
			|| !WorldBuilderPackedLayoutAdapter.ID.equals(string(capability, "adapterId"))) {
			throw blocked("The discovery report did not resolve the compiled packed adapter.",
				"Use descriptor-backed " + WorldBuilderPackedLayoutAdapter.ID + " evidence.");
		}
		Map<String,Object> descriptor = object(report.get("descriptor"), "descriptor");
		if (!Boolean.TRUE.equals(descriptor.get("present"))) {
			throw blocked("Legacy fallback evidence cannot prove complete four-family conversion inputs.",
				"Add a truthful target-capability-v1 descriptor and rediscover the target.");
		}
		Map<String,Object> selected = object(
			report.get("selectedConfiguration"), "selectedConfiguration");
		if (!Boolean.TRUE.equals(selected.get("present"))) {
			throw blocked("The discovery report has no selected packed configuration.",
				"Rediscover with exactly one active descriptor-backed configuration.");
		}

		WorldBuilderReadOnlyTarget target = WorldBuilderReadOnlyTarget.open(requestedSourceRoot);
		Path canonicalSource = realDirectory(target.root, "source-root");
		Path canonicalProjectStage = requestedProjectStage == null ? null
			: realDirectory(requestedProjectStage, "project-stage");
		if (canonicalProjectStage != null) {
			Path requiredSource = canonicalProjectStage.resolve(requiredProjectSource).normalize();
			if (!canonicalSource.equals(requiredSource)
				|| Files.isSymbolicLink(requestedProjectStage)
				|| !Files.isDirectory(canonicalProjectStage, LinkOption.NOFOLLOW_LINKS)) {
				throw blocked("Project conversion source is not the exact unpublished "
					+ requiredProjectSource + " staging directory.",
					"Create one contained project stage and copy only the discovery inventory.");
			}
		}
		String targetDisplay = string(report, "targetRootDisplay");
		Path reportedTarget = null;
		Path canonicalReportedTarget = null;
		if (!targetDisplay.isEmpty()) {
			try {
				reportedTarget = java.nio.file.Paths.get(targetDisplay).toAbsolutePath().normalize();
			} catch (RuntimeException invalidPath) {
				throw blocked("The discovery report target display is not a valid local path.",
					"Use the unmodified report emitted by discover-adaptive.");
			}
			canonicalReportedTarget = existingRealDirectory(reportedTarget);
			if (canonicalProjectStage == null && (overlaps(target.root, reportedTarget)
				|| canonicalReportedTarget != null
					&& (overlaps(canonicalSource, canonicalReportedTarget)
						|| containsByIdentity(canonicalSource, canonicalReportedTarget)
						|| containsByIdentity(canonicalReportedTarget, canonicalSource)))) {
				throw blocked("Conversion source is the live discovered target, not an isolated copy.",
					"Copy the complete inventoried evidence to an isolated source directory first.");
			}
			if (canonicalProjectStage != null && canonicalReportedTarget != null
				&& (canonicalProjectStage.equals(canonicalReportedTarget)
					|| canonicalReportedTarget.startsWith(canonicalProjectStage))) {
				throw blocked("Project staging aliases or contains the reported target root.",
					"Keep the unpublished project stage strictly below the World Builder projects directory.");
			}
		}

		List<WorldBuilderBoundedInventory.Record> expected =
			new ArrayList<WorldBuilderBoundedInventory.Record>();
		Set<String> paths = new HashSet<String>();
		addExpected(expected, paths, "target-capability",
			string(descriptor, "relativePath"), string(descriptor, "sha256"), -1L);
		addExpected(expected, paths, "configuration." + string(selected, "role"),
			string(selected, "relativePath"), string(selected, "sha256"), -1L);
		Object rawFiles = report.get("files");
		if (!(rawFiles instanceof List)) throw malformed("Discovery file inventory is absent.");
		for (Object raw : (List<?>)rawFiles) {
			Map<String,Object> file = object(raw, "files");
			if (!Boolean.TRUE.equals(file.get("present"))) {
				throw blocked("Conversion source inventory contains required absence evidence.",
					"Use descriptor-backed packed discovery with complete present inputs.");
			}
			addExpected(expected, paths, string(file, "role"),
				string(file, "relativePath"), string(file, "sha256"),
				integer(file, "size"));
		}
		Collections.sort(expected, new java.util.Comparator<WorldBuilderBoundedInventory.Record>() {
			@Override
			public int compare(WorldBuilderBoundedInventory.Record first,
				WorldBuilderBoundedInventory.Record second) {
				int result = first.relativePath.compareTo(second.relativePath);
				return result == 0 ? first.role.compareTo(second.role) : result;
			}
		});

		List<WorldBuilderBoundedInventory.Record> verified = verifyExactTree(target, expected);
		return new WorldBuilderPackedConversionSource(target, canonicalSource,
			reportedTarget, canonicalReportedTarget, report, sourceFingerprint,
			string(selected, "role"), string(selected, "relativePath"),
			string(selected, "sha256"), verified);
	}

	boolean overlapsSourceOrReportedTarget(
		Path lexicalPath, Path canonicalPath, Path canonicalParent)
		throws WorldBuilderContractException {
		return overlaps(lexicalPath, target.root)
			|| overlaps(canonicalPath, canonicalSourceRoot)
			|| containsByIdentity(canonicalSourceRoot, canonicalParent)
			|| reportedTargetRoot != null && overlaps(lexicalPath, reportedTargetRoot)
			|| canonicalReportedTargetRoot != null
				&& (overlaps(canonicalPath, canonicalReportedTargetRoot)
					|| containsByIdentity(canonicalReportedTargetRoot, canonicalParent));
	}

	boolean overlapsSource(Path lexicalPath, Path canonicalPath, Path canonicalParent)
		throws WorldBuilderContractException {
		return overlaps(lexicalPath, target.root)
			|| overlaps(canonicalPath, canonicalSourceRoot)
			|| containsByIdentity(canonicalSourceRoot, canonicalParent);
	}

	void reverify() throws WorldBuilderContractException {
		if (preservationSource != null) {
			try { preservationSource.reverify(); }
			catch (IOException changed) { throw blocked("Historical derivation changed during conversion.",
				"Keep original and derived evidence stable until atomic conversion completes."); }
		}
		List<WorldBuilderBoundedInventory.Record> verified = verifyExactTree(target, inputs);
		if (verified.size() != inputs.size()) {
			throw blocked("Immutable conversion evidence changed during conversion.",
				"Discard the output and create a fresh isolated evidence copy.");
		}
	}

	WorldBuilderBoundedInventory.Record requireInput(String role, String relativePath)
		throws WorldBuilderContractException {
		for (WorldBuilderBoundedInventory.Record input : inputs) {
			if (role.equals(input.role) && relativePath.equals(input.relativePath)) return input;
		}
		throw blocked("Adapter requested evidence outside the immutable inventory: "
			+ role + " at " + relativePath + ".",
			"Rediscover and copy the complete declared packed input set.");
	}

	List<Object> inputDocuments() {
		List<Object> result = new ArrayList<Object>(inputs.size());
		for (WorldBuilderBoundedInventory.Record input : inputs) {
			Map<String,Object> value = new LinkedHashMap<String,Object>();
			value.put("role", input.role);
			value.put("relativePath", input.relativePath);
			value.put("present", Boolean.TRUE);
			value.put("size", Long.valueOf(input.size));
			value.put("sha256", input.sha256);
			result.add(value);
		}
		return result;
	}

	private static Map<String,Object> readReport(Path path)
		throws IOException, WorldBuilderContractException {
		if (path == null || Files.isSymbolicLink(path)
			|| !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
			throw blocked("The Phase 1 discovery report is missing or unsafe.",
				"Supply a regular no-follow discover-adaptive JSON report.");
		}
		try {
			return WorldBuilderJsonDocuments.readObject(path);
		} catch (WorldBuilderDiscoveryException malformed) {
			throw new WorldBuilderContractException(WorldBuilderErrorCodes.MALFORMED_JSON,
				OPERATION, "discovery-report", false,
				"The Phase 1 discovery report is malformed.",
				"Supply the unmodified bounded UTF-8 report from discover-adaptive.", malformed);
		}
	}

	private static Path realDirectory(Path path, String label)
		throws WorldBuilderContractException {
		try {
			return path.toRealPath();
		} catch (IOException failure) {
			throw new WorldBuilderContractException(WorldBuilderErrorCodes.UNSAFE_PATH,
				OPERATION, label, false,
				"The conversion directory identity cannot be resolved safely.",
				"Use an existing real directory with stable filesystem identity.", failure);
		}
	}

	private static Path existingRealDirectory(Path path)
		throws WorldBuilderContractException {
		try {
			if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return null;
			Path real = path.toRealPath();
			if (!Files.isDirectory(real, LinkOption.NOFOLLOW_LINKS)) {
				throw new IOException("reported target is no longer a directory");
			}
			return real;
		} catch (IOException failure) {
			throw new WorldBuilderContractException(WorldBuilderErrorCodes.UNSAFE_PATH,
				OPERATION, "reported-target", false,
				"The locally existing reported target identity cannot be resolved safely.",
				"Stop target changes and rediscover before conversion.", failure);
		}
	}

	private static boolean sameFile(Path first, Path second)
		throws WorldBuilderContractException {
		try {
			return Files.isSameFile(first, second);
		} catch (IOException failure) {
			throw new WorldBuilderContractException(WorldBuilderErrorCodes.UNSAFE_PATH,
				OPERATION, "source-root", false,
				"Source and reported target filesystem identity cannot be compared safely.",
				"Use stable, locally accessible real directories and retry.", failure);
		}
	}

	private static boolean containsByIdentity(Path protectedRoot, Path candidate)
		throws WorldBuilderContractException {
		for (Path current = candidate; current != null; current = current.getParent()) {
			if (sameFile(protectedRoot, current)) return true;
		}
		return false;
	}

	private static boolean overlaps(Path first, Path second) {
		return first.equals(second) || first.startsWith(second) || second.startsWith(first);
	}

	private static String requireReportFingerprint(Map<String,Object> report)
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
		if (!supplied.equals(calculated)) {
			throw blocked("The Phase 1 discovery report fingerprint does not match its content.",
				"Run discover-adaptive again and do not edit its report.");
		}
		return supplied;
	}

	private static void addExpected(List<WorldBuilderBoundedInventory.Record> expected,
		Set<String> paths, String role, String relative, String hash, long declaredSize)
		throws WorldBuilderContractException {
		WorldBuilderPortablePath.require(relative, OPERATION);
		if (!WorldBuilderBoundedInventory.isHash(hash)
			|| !paths.add(WorldBuilderPortablePath.collisionKey(relative, OPERATION))) {
			throw malformed("Discovery evidence paths or hashes are duplicated or invalid.");
		}
		expected.add(new WorldBuilderBoundedInventory.Record(
			role, relative, true, declaredSize, hash));
	}

	private static List<WorldBuilderBoundedInventory.Record> verifyExactTree(
		final WorldBuilderReadOnlyTarget target,
		List<WorldBuilderBoundedInventory.Record> expected)
		throws WorldBuilderContractException {
		final Set<String> actual = new HashSet<String>();
		final Set<String> allowedDirectories = new HashSet<String>();
		allowedDirectories.add("");
		for (WorldBuilderBoundedInventory.Record record : expected) {
			String parent = record.relativePath;
			while (parent.contains("/")) {
				parent = parent.substring(0, parent.lastIndexOf('/'));
				allowedDirectories.add(parent);
			}
		}
		final int[] count = new int[] {0};
		final int[] directoryCount = new int[] {0};
		try {
			Files.walkFileTree(target.root, new SimpleFileVisitor<Path>() {
				@Override
				public FileVisitResult preVisitDirectory(
					Path directory, BasicFileAttributes attributes) throws IOException {
					if (!attributes.isDirectory() || Files.isSymbolicLink(directory)) {
						throw new IOException("unsafe directory");
					}
					try {
						String relative = directory.equals(target.root)
							? "" : target.relative(directory);
						if (!allowedDirectories.contains(relative)
							|| ++directoryCount[0]
								> WorldBuilderContractLimits.MAX_INVENTORY_ENTRIES) {
							throw new IOException("unexpected or unbounded directory");
						}
					} catch (WorldBuilderContractException refusal) {
						throw new ConversionWalkException(refusal);
					}
					return FileVisitResult.CONTINUE;
				}

				@Override
				public FileVisitResult visitFile(
					Path file, BasicFileAttributes attributes) throws IOException {
					if (!attributes.isRegularFile() || Files.isSymbolicLink(file)) {
						throw new IOException("unsafe file");
					}
					try {
						String relative = target.relative(file);
						if (++count[0] > WorldBuilderContractLimits.MAX_INVENTORY_ENTRIES
							|| !actual.add(relative)) throw new IOException("inventory limit");
					} catch (WorldBuilderContractException refusal) {
						throw new ConversionWalkException(refusal);
					}
					return FileVisitResult.CONTINUE;
				}
			});
		} catch (ConversionWalkException refusal) {
			throw refusal.refusal;
		} catch (IOException failure) {
			throw new WorldBuilderContractException(WorldBuilderErrorCodes.UNSAFE_PATH,
				OPERATION, "source-root", false,
				"The isolated conversion evidence tree contains an unsafe or unbounded entry.",
				"Copy only contained regular inventoried files into the source root.", failure);
		}
		Set<String> declared = new HashSet<String>();
		for (WorldBuilderBoundedInventory.Record record : expected) {
			declared.add(record.relativePath);
		}
		if (!actual.equals(declared)) {
			Set<String> missing = new java.util.TreeSet<String>(declared);
			missing.removeAll(actual);
			Set<String> extra = new java.util.TreeSet<String>(actual);
			extra.removeAll(declared);
			throw blocked("Isolated conversion evidence does not exactly match discovery; missing="
				+ missing + ", extra=" + extra + ".",
				"Recreate the evidence copy from exactly the Phase 1 inventory.");
		}
		List<WorldBuilderBoundedInventory.Record> verified =
			new ArrayList<WorldBuilderBoundedInventory.Record>(expected.size());
		for (WorldBuilderBoundedInventory.Record record : expected) {
			WorldBuilderReadOnlyTarget.FileState state =
				target.requiredState(record.role, record.relativePath);
			if (!record.sha256.equals(state.sha256)
				|| record.size >= 0L && record.size != state.size) {
				throw blocked("Isolated conversion evidence differs from discovery at "
					+ record.relativePath + ".",
					"Discard the copy and snapshot the exact discovered bytes again.");
			}
			verified.add(new WorldBuilderBoundedInventory.Record(record.role,
				record.relativePath, true, state.size, state.sha256));
		}
		return verified;
	}

	private static Map<String,Object> object(Object raw, String label)
		throws WorldBuilderContractException {
		if (!(raw instanceof Map)) throw malformed("Discovery report field is not an object: " + label);
		@SuppressWarnings("unchecked") Map<String,Object> result = (Map<String,Object>)raw;
		return result;
	}

	private static String string(Map<String,Object> value, String key)
		throws WorldBuilderContractException {
		Object raw = value.get(key);
		if (!(raw instanceof String)) throw malformed("Discovery report field is not a string: " + key);
		return (String)raw;
	}

	private static long integer(Map<String,Object> value, String key)
		throws WorldBuilderContractException {
		Object raw = value.get(key);
		if (!(raw instanceof Long)) throw malformed("Discovery report field is not an integer: " + key);
		return ((Long)raw).longValue();
	}

	private static WorldBuilderContractException malformed(String message) {
		return new WorldBuilderContractException(WorldBuilderErrorCodes.CONTRACT_VALUE_INVALID,
			OPERATION, "discovery-report", false, message,
			"Supply the unmodified report emitted by discover-adaptive.");
	}

	private static WorldBuilderContractException blocked(String message, String nextStep) {
		return new WorldBuilderContractException(WorldBuilderErrorCodes.CONVERSION_BLOCKED,
			OPERATION, "source-root", false, message, nextStep);
	}

	private static final class ConversionWalkException extends IOException {
		private static final long serialVersionUID = 1L;
		final WorldBuilderContractException refusal;
		ConversionWalkException(WorldBuilderContractException refusal) {
			super(refusal);
			this.refusal = refusal;
		}
	}
}
