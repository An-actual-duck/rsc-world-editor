package com.openrsc.worldbuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Receipt-bound execution evidence, created only after the real supervised verifier succeeds. */
final class WorldBuilderCurrentRuntimeExecutionEvidence {
	static final String PATH = "migration/output/verification/installed-execution-evidence.json";
	private static final String OPERATION = "current-runtime-execution-evidence";
	private static final long MAX_BYTES = 1048576;
	private WorldBuilderCurrentRuntimeExecutionEvidence() { }

	static List<Object> run(Path release, WorldBuilderProviderCatalog.Composition composition,
		Map<String,Object> migration, List<Object> generated, Path attempt)
		throws IOException, WorldBuilderContractException {
		Path output = WorldBuilderPortablePath.resolveContained(release, PATH, OPERATION);
		if (Files.exists(output.getParent(), LinkOption.NOFOLLOW_LINKS))
			throw invalid("Execution evidence destination already exists.");
		Map<String,Object> evidence = WorldBuilderInstalledRuntimeVerifier.verify(
			release, composition, migration, generated, attempt, null);
		byte[] bytes = WorldBuilderJsonDocuments.pretty(evidence).getBytes(StandardCharsets.UTF_8);
		if (bytes.length == 0 || bytes.length > MAX_BYTES) throw invalid("Verified execution evidence exceeds its bound.");
		Files.createDirectory(output.getParent(), PosixFilePermissions.asFileAttribute(
			PosixFilePermissions.fromString("rwx------")));
		Files.createFile(output, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
		Files.write(output, bytes, StandardOpenOption.WRITE);
		WorldBuilderAdaptiveDurability.forceFile(output);
		WorldBuilderAdaptiveDurability.forceDirectory(output.getParent());
		WorldBuilderAdaptiveDurability.forceDirectory(output.getParent().getParent());
		Map<String,Object> record = new LinkedHashMap<String,Object>();
		record.put("relativePath", PATH);
		record.put("size", Long.valueOf(bytes.length));
		record.put("sha256", WorldBuilderHashes.sha256(bytes));
		record.put("mode", "0600");
		record.put("verifierContractSha256", WorldBuilderInstalledRuntimeVerifier.CONTRACT_HASH);
		List<Object> result = Collections.<Object>singletonList(Collections.unmodifiableMap(record));
		verify(release, result);
		return result;
	}

	static void validate(List<Object> records, boolean allowEmpty) throws WorldBuilderContractException {
		if (allowEmpty && records.isEmpty()) return;
		if (records.size() != 1 || !(records.get(0) instanceof Map))
			throw invalid("Execution evidence inventory is incomplete.");
		Map<String,Object> row = object(records.get(0));
		WorldBuilderBoundedInventory.exactKeys(row, OPERATION,
			"relativePath", "size", "sha256", "mode", "verifierContractSha256");
		long size = WorldBuilderBoundedInventory.integer(row.get("size"), OPERATION, "size");
		if (!PATH.equals(row.get("relativePath")) || !"0600".equals(row.get("mode"))
			|| size < 1 || size > MAX_BYTES
			|| !WorldBuilderInstalledRuntimeVerifier.CONTRACT_HASH.equals(row.get("verifierContractSha256"))
			|| !WorldBuilderBoundedInventory.isHash(WorldBuilderBoundedInventory.string(row.get("sha256"), OPERATION, "sha256")))
			throw invalid("Execution evidence differs from the compiled verifier output contract.");
	}

	static void verify(Path release, List<Object> records) throws IOException, WorldBuilderContractException {
		validate(records, true);
		if (records.isEmpty()) return;
		Map<String,Object> row = object(records.get(0));
		Path file = WorldBuilderReadOnlyTarget.open(release).requiredFile(PATH);
		if (Files.size(file) != ((Number)row.get("size")).longValue()
			|| !WorldBuilderHashes.sha256(file).equals(row.get("sha256"))
			|| !Files.getPosixFilePermissions(file).equals(PosixFilePermissions.fromString("rw-------")))
			throw invalid("Execution evidence changed after supervised verification.");
	}

	@SuppressWarnings("unchecked") private static Map<String,Object> object(Object raw) { return (Map<String,Object>)raw; }
	private static WorldBuilderContractException invalid(String message) {
		return new WorldBuilderContractException(WorldBuilderErrorCodes.TARGET_DRIFT,
			OPERATION, PATH, false, message,
			"Preserve the transaction and verifier attempt; do not activate or force cleanup.");
	}
}
