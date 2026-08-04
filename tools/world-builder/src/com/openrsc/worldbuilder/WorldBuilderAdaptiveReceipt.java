package com.openrsc.worldbuilder;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Strict durable import-receipt-v3 writer/reader for adaptive transactions. */
final class WorldBuilderAdaptiveReceipt {
	private static final String OPERATION = "adaptive-transaction-receipt";
	private static final String ZERO_HASH =
		"0000000000000000000000000000000000000000000000000000000000000000";

	private WorldBuilderAdaptiveReceipt() {
	}

	static State create(WorldBuilderAdaptiveMutationProfile.Plan plan,
		String transactionType, String status, String createdAtUtc,
		boolean mutationOccurred, List<WorldBuilderAdaptiveOfflineLease.Evidence> evidence,
		boolean afterVerified, boolean rollbackVerified,
		List<Verification> verifications, String revertsTransactionId,
		String recoveryTransactionId) throws WorldBuilderContractException {
		Map<String,Object> value = new LinkedHashMap<String,Object>();
		value.put("schemaVersion", Long.valueOf(3L));
		value.put("manifestType", "world-builder-adaptive-import-receipt");
		value.put("transactionId", plan.transactionId());
		value.put("transactionType", transactionType);
		value.put("status", status);
		value.put("createdAtUtc", createdAtUtc);
		value.put("projectId", plan.project.projectId);
		value.put("exportFingerprintSha256", plan.exportFingerprint());
		value.put("mutationPlanSha256", plan.canonicalSha256);
		value.put("adapterId", plan.capability.adapterId);
		value.put("capabilityId", plan.capability.capabilityId);
		value.put("targetLineageSha256", plan.targetLineage());
		@SuppressWarnings("unchecked") Map<String,Object> selected =
			(Map<String,Object>)plan.document.get("selectedConfiguration");
		value.put("selectedConfiguration", copyMap(selected));
		value.put("mutationOccurred", Boolean.valueOf(mutationOccurred));

		List<Object> evidenceValues = new ArrayList<Object>();
		for (WorldBuilderAdaptiveOfflineLease.Evidence item : evidence) {
			Map<String,Object> record = new LinkedHashMap<String,Object>();
			record.put("kind", item.kind);
			record.put("observed", item.observed);
			record.put("verified", Boolean.valueOf(item.verified));
			evidenceValues.add(record);
		}
		value.put("offlineEvidence", evidenceValues);

		List<WorldBuilderAdaptiveMutationProfile.Action> actions =
			new ArrayList<WorldBuilderAdaptiveMutationProfile.Action>(plan.actions);
		Collections.sort(actions, new Comparator<WorldBuilderAdaptiveMutationProfile.Action>() {
			@Override public int compare(WorldBuilderAdaptiveMutationProfile.Action left,
				WorldBuilderAdaptiveMutationProfile.Action right) {
				int result = left.destinationRelativePath.compareTo(
					right.destinationRelativePath);
				return result == 0 ? left.role.compareTo(right.role) : result;
			}
		});
		List<Object> files = new ArrayList<Object>();
		for (WorldBuilderAdaptiveMutationProfile.Action action : actions) {
			Map<String,Object> file = new LinkedHashMap<String,Object>();
			file.put("role", action.role);
			file.put("relativePath", action.destinationRelativePath);
			file.put("before", action.before.toJson());
			file.put("after", action.after.toJson());
			file.put("backupRelativePath", action.backupRelativePath);
			file.put("backupSha256", action.before.present ? action.before.sha256 : "");
			file.put("afterVerified", Boolean.valueOf(afterVerified));
			file.put("rollbackVerified", Boolean.valueOf(rollbackVerified));
			files.add(file);
		}
		value.put("files", files);

		List<Object> configurationChanges = new ArrayList<Object>();
		for (int index = 0; index < plan.configurationChanges.size(); index++) {
			Map<String,Object> change = plan.configurationChanges.get(index)
				.toJson(index, true);
			change.put("afterVerified", Boolean.valueOf(afterVerified));
			change.put("rollbackVerified", Boolean.valueOf(rollbackVerified));
			configurationChanges.add(change);
		}
		value.put("configurationChanges", configurationChanges);

		List<Verification> sorted = new ArrayList<Verification>(verifications);
		Collections.sort(sorted);
		List<Object> verificationValues = new ArrayList<Object>();
		for (Verification verification : sorted) {
			verificationValues.add(verification.toJson());
		}
		value.put("verificationResults", verificationValues);
		value.put("revertsTransactionId", revertsTransactionId == null
			? "" : revertsTransactionId);
		value.put("recoveryTransactionId", recoveryTransactionId == null
			? "" : recoveryTransactionId);
		value.put("receiptFingerprintSha256", ZERO_HASH);
		WorldBuilderAdaptiveExporter.bindFingerprint(value, "receiptFingerprintSha256");
		WorldBuilderAdaptiveContracts.validateParsed(
			WorldBuilderAdaptiveContracts.Kind.ADAPTIVE_RECEIPT, value);
		return new State(value);
	}

	static String now() {
		return Instant.now().toString();
	}

	static State read(Path path) throws IOException, WorldBuilderContractException {
		if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
			|| Files.isSymbolicLink(path)) throw problem(
			WorldBuilderErrorCodes.RECOVERY_REQUIRED, "receipts",
			"Adaptive receipt path is missing, linked, or unsafe.",
			"Restore the exact durable receipt before another transaction.");
		Map<String,Object> value;
		try {
			value = WorldBuilderJsonDocuments.readObject(path);
		} catch (WorldBuilderDiscoveryException malformed) {
			throw problem(WorldBuilderErrorCodes.RECOVERY_REQUIRED, "receipts",
				"Adaptive receipt JSON is malformed: " + malformed.getMessage(),
				"Restore the exact durable receipt before another transaction.", malformed);
		}
		WorldBuilderAdaptiveContracts.validateParsed(
			WorldBuilderAdaptiveContracts.Kind.ADAPTIVE_RECEIPT, value);
		WorldBuilderAdaptiveExporter.requireFingerprint(value, "receiptFingerprintSha256");
		String id = WorldBuilderAdaptiveExporter.string(value, "transactionId");
		if (!path.getFileName().toString().equals(id + ".json")) throw problem(
			WorldBuilderErrorCodes.RECOVERY_REQUIRED, "receipts",
			"Adaptive receipt filename does not match its transaction UUID.",
			"Restore the exact receipts/<transaction-id>.json file.");
		return new State(value);
	}

	static List<State> readAll(Path project)
		throws IOException, WorldBuilderContractException {
		Path receipts = WorldBuilderAdaptiveExporter.requireDirectory(
			project, "receipts", "project receipts directory");
		List<State> values = new ArrayList<State>();
		Set<String> collision = new HashSet<String>();
		try (DirectoryStream<Path> stream = Files.newDirectoryStream(receipts)) {
			for (Path path : stream) {
				String name = path.getFileName().toString();
				if (!collision.add(name.toLowerCase(java.util.Locale.ROOT))) {
					throw problem(WorldBuilderErrorCodes.RECOVERY_REQUIRED, "receipts",
						"Receipt directory contains a case-colliding entry.",
						"Restore a collision-free receipt directory.");
				}
				if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
					|| Files.isSymbolicLink(path) || !name.endsWith(".json")) {
					throw problem(WorldBuilderErrorCodes.RECOVERY_REQUIRED, "receipts",
						"Receipt directory contains an untracked or unsafe entry: " + name + ".",
						"Move diagnostics outside receipts and restore only strict transaction JSON.");
				}
				values.add(read(path));
			}
		}
		Collections.sort(values);
		return values;
	}

	static void write(Path project, State state)
		throws IOException, WorldBuilderContractException {
		Path receipts = WorldBuilderAdaptiveExporter.requireDirectory(
			project, "receipts", "project receipts directory");
		Path destination = WorldBuilderPortablePath.resolveContained(
			receipts, state.transactionId() + ".json", OPERATION);
		Path temporary = receipts.resolve("." + state.transactionId()
			+ ".tmp-" + java.util.UUID.randomUUID().toString()).normalize();
		byte[] bytes = WorldBuilderJsonDocuments.pretty(state.document)
			.getBytes(StandardCharsets.UTF_8);
		Files.write(temporary, bytes, StandardOpenOption.CREATE_NEW,
			StandardOpenOption.WRITE);
		try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
			channel.force(true);
		}
		try {
			try {
				Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE,
					StandardCopyOption.REPLACE_EXISTING);
			} catch (AtomicMoveNotSupportedException unsupported) {
				throw problem(WorldBuilderErrorCodes.RECOVERY_REQUIRED, "receipts",
					"Filesystem cannot atomically publish durable adaptive receipts.",
					"Move the complete closed project to an atomic local filesystem.",
					unsupported);
			}
			State written = read(destination);
			if (!state.canonicalSha256.equals(written.canonicalSha256)) throw problem(
				WorldBuilderErrorCodes.RECOVERY_REQUIRED, "receipts",
				"Durable adaptive receipt did not verify after publication.",
				"Stop transactions and inspect project storage health.");
		} finally {
			Files.deleteIfExists(temporary);
		}
	}

	static void requireSuccessfulImportMatches(
		WorldBuilderAdaptiveMutationProfile.Plan plan, State actual)
		throws WorldBuilderContractException {
		if (!"import".equals(actual.transactionType())
			|| !"successful".equals(actual.status())
			|| !plan.transactionId().equals(actual.transactionId())) throw problem(
			WorldBuilderErrorCodes.RECOVERY_REQUIRED, "receipts",
			"Undo authority is not one successful import receipt for this transaction.",
			"Select the latest successful unreverted adaptive import.");
		requireTransactionMatches(plan, actual);
	}

	static void requireTransactionMatches(
		WorldBuilderAdaptiveMutationProfile.Plan plan, State actual)
		throws WorldBuilderContractException {
		List<WorldBuilderAdaptiveOfflineLease.Evidence> evidence =
			new ArrayList<WorldBuilderAdaptiveOfflineLease.Evidence>();
		for (Object raw : WorldBuilderAdaptiveExporter.array(
			actual.document.get("offlineEvidence"), "offlineEvidence")) {
			Map<String,Object> value = WorldBuilderAdaptiveExporter.object(
				raw, "offlineEvidence");
			evidence.add(new WorldBuilderAdaptiveOfflineLease.Evidence(
				WorldBuilderAdaptiveExporter.string(value, "kind"),
				WorldBuilderAdaptiveExporter.string(value, "observed"),
				WorldBuilderAdaptiveExporter.bool(value, "verified")));
		}
		List<Verification> verifications = new ArrayList<Verification>();
		for (Object raw : WorldBuilderAdaptiveExporter.array(
			actual.document.get("verificationResults"), "verificationResults")) {
			Map<String,Object> value = WorldBuilderAdaptiveExporter.object(
				raw, "verificationResults");
			verifications.add(new Verification(
				WorldBuilderAdaptiveExporter.string(value, "verificationId"),
				WorldBuilderAdaptiveExporter.bool(value, "success"),
				WorldBuilderAdaptiveExporter.string(value, "observed")));
		}
		boolean afterVerified = uniformVerificationFlag(
			actual.document, "afterVerified");
		boolean rollbackVerified = uniformVerificationFlag(
			actual.document, "rollbackVerified");
		State expected = create(plan, actual.transactionType(), actual.status(),
			actual.createdAtUtc(), WorldBuilderAdaptiveExporter.bool(
				actual.document, "mutationOccurred"), evidence,
			afterVerified, rollbackVerified, verifications,
			actual.revertsTransactionId(), WorldBuilderAdaptiveExporter.string(
				actual.document, "recoveryTransactionId"));
		if (!expected.canonicalSha256.equals(actual.canonicalSha256)) throw problem(
			WorldBuilderErrorCodes.RECOVERY_REQUIRED,
			"receipts/" + actual.transactionId() + ".json",
			"Successful receipt does not exactly match the independently compiled plan.",
			"Retain the complete project and exact receipt; do not force undo.");
	}

	private static boolean uniformVerificationFlag(Map<String,Object> receipt,
		String field) throws WorldBuilderContractException {
		Boolean observed = null;
		for (String collection : new String[] {"files", "configurationChanges"}) {
			for (Object raw : WorldBuilderAdaptiveExporter.array(
				receipt.get(collection), collection)) {
				Map<String,Object> value = WorldBuilderAdaptiveExporter.object(raw, collection);
				boolean current = WorldBuilderAdaptiveExporter.bool(value, field);
				if (observed != null && observed.booleanValue() != current) throw problem(
					WorldBuilderErrorCodes.RECOVERY_REQUIRED, "receipts",
					"Receipt uses inconsistent per-file verification flags.",
					"Restore the exact durable transaction receipt.");
				observed = Boolean.valueOf(current);
			}
		}
		return observed != null && observed.booleanValue();
	}

	static State markRolledBack(State source)
		throws WorldBuilderContractException {
		Map<String,Object> value = copyMap(source.document);
		value.put("status", "rolled-back");
		value.put("mutationOccurred", Boolean.TRUE);
		for (Object raw : WorldBuilderAdaptiveExporter.array(value.get("files"), "files")) {
			WorldBuilderAdaptiveExporter.object(raw, "file")
				.put("rollbackVerified", Boolean.TRUE);
		}
		for (Object raw : WorldBuilderAdaptiveExporter.array(
			value.get("configurationChanges"), "configurationChanges")) {
			WorldBuilderAdaptiveExporter.object(raw, "configurationChange")
				.put("rollbackVerified", Boolean.TRUE);
		}
		value.put("receiptFingerprintSha256", ZERO_HASH);
		WorldBuilderAdaptiveExporter.bindFingerprint(
			value, "receiptFingerprintSha256");
		WorldBuilderAdaptiveContracts.validateParsed(
			WorldBuilderAdaptiveContracts.Kind.ADAPTIVE_RECEIPT, value);
		return new State(value);
	}

	@SuppressWarnings("unchecked")
	private static Map<String,Object> copyMap(Map<String,Object> source) {
		return (Map<String,Object>)copy(source);
	}

	@SuppressWarnings("unchecked")
	private static Object copy(Object value) {
		if (value instanceof Map) {
			Map<String,Object> result = new LinkedHashMap<String,Object>();
			for (Map.Entry<String,Object> entry : ((Map<String,Object>)value).entrySet()) {
				result.put(entry.getKey(), copy(entry.getValue()));
			}
			return result;
		}
		if (value instanceof List) {
			List<Object> result = new ArrayList<Object>();
			for (Object child : (List<Object>)value) result.add(copy(child));
			return result;
		}
		return value;
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

	static final class Verification implements Comparable<Verification> {
		final String verificationId;
		final boolean success;
		final String observed;

		Verification(String verificationId, boolean success, String observed) {
			this.verificationId = verificationId;
			this.success = success;
			this.observed = observed;
		}

		Map<String,Object> toJson() {
			Map<String,Object> value = new LinkedHashMap<String,Object>();
			value.put("verificationId", verificationId);
			value.put("success", Boolean.valueOf(success));
			value.put("observed", observed);
			return value;
		}

		@Override public int compareTo(Verification other) {
			return verificationId.compareTo(other.verificationId);
		}
	}

	static final class State implements Comparable<State> {
		final Map<String,Object> document;
		final String canonicalSha256;

		State(Map<String,Object> document) throws WorldBuilderContractException {
			this.document = document;
			this.canonicalSha256 = WorldBuilderAdaptiveContracts.validateParsed(
				WorldBuilderAdaptiveContracts.Kind.ADAPTIVE_RECEIPT,
				document).canonicalSha256;
		}

		String transactionId() throws WorldBuilderContractException {
			return WorldBuilderAdaptiveExporter.string(document, "transactionId");
		}

		String transactionType() throws WorldBuilderContractException {
			return WorldBuilderAdaptiveExporter.string(document, "transactionType");
		}

		String status() throws WorldBuilderContractException {
			return WorldBuilderAdaptiveExporter.string(document, "status");
		}

		String createdAtUtc() throws WorldBuilderContractException {
			return WorldBuilderAdaptiveExporter.string(document, "createdAtUtc");
		}

		String exportFingerprint() throws WorldBuilderContractException {
			return WorldBuilderAdaptiveExporter.string(
				document, "exportFingerprintSha256");
		}

		String revertsTransactionId() throws WorldBuilderContractException {
			return WorldBuilderAdaptiveExporter.string(
				document, "revertsTransactionId");
		}

		@Override public int compareTo(State other) {
			try {
				int result = createdAtUtc().compareTo(other.createdAtUtc());
				return result == 0 ? transactionId().compareTo(other.transactionId()) : result;
			} catch (WorldBuilderContractException impossible) {
				throw new IllegalStateException(impossible);
			}
		}
	}
}
