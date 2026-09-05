package com.openrsc.worldbuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

/** Invokes only the selected provider's inventory-bound pure historical decoder. */
final class WorldBuilderPreservationJagDecoder {
	static final String CONTRACT_SHA256 = "1dd693a742c5a0a92669feac187015acdd15586073b8d62d544dde1b5dd24f1a";
	static final String CONTRACT_PATH = "contracts/input-adapters/preservation-r64-sqlite-v1.json";
	private static final String MAIN = "com.openrsc.server.io.PreservationJagDecode";
	private static final String OP = "decode-reviewed-preservation-map";
	private static final String MAP_DIRECTORY = "server/conf/server/data/maps/";
	private static final String[] ARCHIVES = {"maps64.jag", "maps64.mem", "land64.jag", "land64.mem"};
	private static final String[] OPTIONS = {"maps-free", "maps-members", "land-free", "land-members"};

	private WorldBuilderPreservationJagDecoder() { }

	static Result decode(Path originalRoot, WorldBuilderProviderCatalog.Composition composition,
		Path requestedAttempt, BooleanSupplier cancellation) throws IOException, WorldBuilderContractException {
		Path original = directory(originalRoot);
		if (requestedAttempt == null || !requestedAttempt.isAbsolute()
			|| !requestedAttempt.equals(requestedAttempt.normalize()) || requestedAttempt.getParent() == null)
			throw blocked("Decoder attempt requires one literal absolute new path.");
		directory(requestedAttempt.getParent());
		disjoint(original, requestedAttempt);
		if (Files.exists(requestedAttempt, LinkOption.NOFOLLOW_LINKS)) throw blocked("Decoder attempt already exists.");
		if (composition == null || !composition.installable || !"current-base-v1".equals(composition.string("variantId")))
			throw blocked("Historical decoding requires the selected installable Current Base composition.");
		WorldBuilderProviderCatalog.Artifact core = artifact(composition, "server-runtime", "runtime/server/core.jar");
		WorldBuilderProviderCatalog.Artifact contract = artifact(composition, "input-adapter-manifest", CONTRACT_PATH);
		verifyArtifact(core); verifyArtifact(contract);
		if (!CONTRACT_SHA256.equals(contract.inventory.get("sha256"))) throw blocked("Provider has no compiled reviewed historical decoder contract.");
		for (WorldBuilderProviderCatalog.Artifact selected : Arrays.asList(core, contract)) {
			Path providerRoot = selected.source;
			for (int i = 0; i < Paths.get(selected.sourcePath).getNameCount(); i++) providerRoot = providerRoot.getParent();
			disjoint(directory(providerRoot), requestedAttempt);
		}
		WorldBuilderReadOnlyTarget target = WorldBuilderReadOnlyTarget.open(original);
		List<WorldBuilderReadOnlyTarget.FileState> archives = new ArrayList<WorldBuilderReadOnlyTarget.FileState>();
		for (String name : ARCHIVES)
			archives.add(WorldBuilderPreservationSourceIntake.requireBaseline(target, MAP_DIRECTORY + name));
		if (cancelled(cancellation)) throw blocked("Historical decode cancelled before execution.");
		Files.createDirectory(requestedAttempt, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
		Path sectors = requestedAttempt.resolve("sectors"), evidence = requestedAttempt.resolve("evidence.json");
		List<String> command = new ArrayList<String>(Arrays.asList(
			Paths.get(System.getProperty("java.home"), "bin", "java").toString(),
			"-Xmx256m", "-cp", core.source.toString(), MAIN, "--contract", contract.source.toString()));
		for (int i = 0; i < ARCHIVES.length; i++) {
			command.add("--" + OPTIONS[i]); command.add(target.requiredFile(MAP_DIRECTORY + ARCHIVES[i]).toString());
		}
		command.add("--output"); command.add(sectors.toString());
		command.add("--evidence"); command.add(evidence.toString());
		// No historical classpath or command is accepted. A timed-out still-live
		// provider process raises RECOVERY_REQUIRED and its attempt is retained.
		WorldBuilderInstalledRuntimeVerifier.runCommand(command, requestedAttempt, cancellation, 60L, 10L);
		if (cancelled(cancellation)) throw blocked("Historical decode cancelled; no evidence is accepted.");
		verifyArtifact(core); verifyArtifact(contract);
		for (WorldBuilderReadOnlyTarget.FileState before : archives) {
			WorldBuilderReadOnlyTarget.FileState after = target.requiredState(before.role, before.relativePath);
			if (before.size != after.size || !before.sha256.equals(after.sha256)) throw blocked("Historical archive changed during decoder execution.");
		}
		WorldBuilderPreservationMapReconciliation.Plan plan = WorldBuilderPreservationMapReconciliation.inspect(original, sectors, evidence);
		Map<String,Object> proof = new LinkedHashMap<String,Object>();
		proof.put("schemaVersion", Long.valueOf(1)); proof.put("manifestType", "world-builder-preservation-decoder-invocation");
		proof.put("compositionIdentity", composition.identity);
		proof.put("coreArtifact", core.inventory); proof.put("decoderContractArtifact", contract.inventory);
		proof.put("decoderId", "preservation-r64-jag-decode-v1"); proof.put("mainClass", MAIN);
		proof.put("decoderContractSha256", CONTRACT_SHA256); proof.put("exitCode", Long.valueOf(0));
		proof.put("decoderEvidenceSha256", WorldBuilderHashes.sha256(evidence));
		proof.put("reconciliationSha256", WorldBuilderHashes.sha256(plan.reportJson().getBytes(StandardCharsets.UTF_8)));
		proof.put("runtimePromotionApproved", Boolean.FALSE);
		WorldBuilderAdaptiveExporter.bindFingerprint(proof, "invocationFingerprintSha256");
		Path reconciliation = requestedAttempt.resolve("reconciliation.json");
		writeNew(reconciliation, plan.reportJson());
		String proofJson = WorldBuilderJsonDocuments.pretty(proof);
		writeNew(requestedAttempt.resolve("invocation.json"), proofJson);
		WorldBuilderAdaptiveDurability.forceDirectory(requestedAttempt);
		return new Result(requestedAttempt, plan, proofJson);
	}

	private static WorldBuilderProviderCatalog.Artifact artifact(WorldBuilderProviderCatalog.Composition composition,
		String role, String bundlePath) throws WorldBuilderContractException {
		WorldBuilderProviderCatalog.Artifact found = null;
		for (WorldBuilderProviderCatalog.Artifact artifact : composition.artifacts) {
			if (!role.equals(artifact.inventory.get("role"))) continue;
			if (found != null || !bundlePath.equals(artifact.bundlePath)) throw blocked("Decoder provider artifact role/path is ambiguous.");
			found = artifact;
		}
		if (found == null) throw blocked("Selected provider composition lacks decoder artifact role " + role + ".");
		return found;
	}
	private static void verifyArtifact(WorldBuilderProviderCatalog.Artifact artifact) throws IOException, WorldBuilderContractException {
		Path parent = directory(artifact.source.getParent());
		Path file = WorldBuilderReadOnlyTarget.open(parent).requiredFile(artifact.source.getFileName().toString());
		if (!file.equals(artifact.source) || Files.size(file) != WorldBuilderBoundedInventory.integer(artifact.inventory.get("size"), OP, "size")
			|| !WorldBuilderHashes.sha256(file).equals(artifact.inventory.get("sha256")))
			throw blocked("Selected provider decoder artifact changed after composition resolution.");
	}
	private static Path directory(Path path) throws IOException, WorldBuilderContractException {
		if (path == null || !path.isAbsolute() || !path.equals(path.normalize()) || !path.equals(path.toRealPath())
			|| !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) throw blocked("Decoder directory is noncanonical, aliased, or missing.");
		return path;
	}
	private static void disjoint(Path a, Path b) throws WorldBuilderContractException {
		if (a.startsWith(b) || b.startsWith(a)) throw blocked("Decoder output overlaps an original input or provider root.");
	}
	private static boolean cancelled(BooleanSupplier cancellation) {
		return Thread.currentThread().isInterrupted() || cancellation != null && cancellation.getAsBoolean();
	}
	private static void writeNew(Path path, String value) throws IOException {
		Files.createFile(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
		Files.write(path, value.getBytes(StandardCharsets.UTF_8), StandardOpenOption.WRITE);
		WorldBuilderAdaptiveDurability.forceFile(path);
	}
	private static WorldBuilderContractException blocked(String message) {
		return new WorldBuilderContractException(WorldBuilderErrorCodes.CONVERSION_BLOCKED, OP, "decoder-attempt", false,
			message, "Retain failed attempts and original inputs; retry only with the exact reviewed provider composition.");
	}
	static final class Result {
		final Path attempt;
		final WorldBuilderPreservationMapReconciliation.Plan plan;
		final String invocationJson;
		private Result(Path attempt, WorldBuilderPreservationMapReconciliation.Plan plan, String invocationJson) {
			this.attempt = attempt; this.plan = plan; this.invocationJson = invocationJson;
		}
	}
}
