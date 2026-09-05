package com.openrsc.worldbuilder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Exact post-migration bytes, bound by the activation and durable phase receipt. */
final class WorldBuilderCurrentRuntimeGeneratedState {
    private static final String OPERATION = "current-runtime-generated-state";
    private static final List<String> PATHS = Collections.unmodifiableList(Arrays.asList(
        "migration/output/state/current-base-migration-evidence.json",
        "migration/output/state/current-base.db"));
    private static final long MAX_DATABASE_BYTES = 1073741824L;
    private static final long MAX_EVIDENCE_BYTES = 1048576L;

    private WorldBuilderCurrentRuntimeGeneratedState() { }

    static List<Object> capture(Path release) throws IOException, WorldBuilderContractException {
        List<Object> result = new ArrayList<Object>();
        for (String relative : PATHS) {
            Path file = checkedFile(release, relative);
            long size = Files.size(file);
            if (size <= 0L || size > (relative.endsWith(".json")
                ? MAX_EVIDENCE_BYTES : MAX_DATABASE_BYTES))
                throw invalid("Generated state exceeds its bounded output size.");
            Map<String,Object> record = new LinkedHashMap<String,Object>();
            record.put("relativePath", relative);
            record.put("size", Long.valueOf(size));
            record.put("sha256", WorldBuilderHashes.sha256(file));
            record.put("mode", "0600");
            result.add(record);
            WorldBuilderAdaptiveDurability.forceFile(file);
            WorldBuilderAdaptiveDurability.forceDirectory(file.getParent());
        }
        validate(result, false);
        verify(release, result);
        return result;
    }

    static void validate(List<Object> records, boolean allowEmpty)
        throws WorldBuilderContractException {
        if (allowEmpty && records.isEmpty()) return;
        if (records.size() != PATHS.size()) throw invalid("Generated state inventory is incomplete.");
        for (int i = 0; i < PATHS.size(); i++) {
            Map<String,Object> record = object(records.get(i));
            WorldBuilderBoundedInventory.exactKeys(record, OPERATION,
                "relativePath", "size", "sha256", "mode");
            long size = WorldBuilderBoundedInventory.integer(record.get("size"), OPERATION, "size");
            if (!PATHS.get(i).equals(record.get("relativePath"))
                || !"0600".equals(record.get("mode")) || size <= 0L
                || size > (i == 0 ? MAX_EVIDENCE_BYTES : MAX_DATABASE_BYTES)
                || !WorldBuilderBoundedInventory.isHash(
                    WorldBuilderBoundedInventory.string(record.get("sha256"), OPERATION, "sha256")))
                throw invalid("Generated state inventory differs from the closed SQLite output contract.");
        }
    }

    static void verify(Path release, List<Object> records)
        throws IOException, WorldBuilderContractException {
        validate(records, true);
        for (Object raw : records) {
            Map<String,Object> record = object(raw);
            String relative = (String)record.get("relativePath");
            Path file = checkedFile(release, relative);
            if (Files.size(file) != ((Number)record.get("size")).longValue()
                || !WorldBuilderHashes.sha256(file).equals(record.get("sha256")))
                throw invalid("Generated state bytes differ from the sealed execution inventory.");
        }
    }

    private static Path checkedFile(Path release, String relative)
        throws IOException, WorldBuilderContractException {
        Path file = WorldBuilderReadOnlyTarget.open(release).requiredFile(relative);
        WorldBuilderAdaptiveExporter.rejectHardLink(file, relative);
        try {
            int mode = ((Number)Files.getAttribute(file, "unix:mode",
                LinkOption.NOFOLLOW_LINKS)).intValue();
            if ((mode & 07777) != 0600) throw invalid("Generated state permissions changed.");
        } catch (UnsupportedOperationException unsupported) {
            throw invalid("Generated state requires supported private-file checks.");
        }
        return file;
    }

    @SuppressWarnings("unchecked")
    private static Map<String,Object> object(Object value) throws WorldBuilderContractException {
        if (!(value instanceof Map)) throw invalid("Generated state record must be an object.");
        return (Map<String,Object>)value;
    }

    private static WorldBuilderContractException invalid(String message) {
        return new WorldBuilderContractException(WorldBuilderErrorCodes.TARGET_DRIFT,
            OPERATION, "generatedStateOutputs", false, message,
            "Preserve staging and transaction evidence; do not activate or force cleanup.");
    }
}
