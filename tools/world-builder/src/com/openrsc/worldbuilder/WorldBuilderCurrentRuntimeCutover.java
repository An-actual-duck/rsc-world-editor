package com.openrsc.worldbuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;

/**
 * The two-file installed activation commit. This component never writes player
 * state, code, maps or descriptors. Its caller validates those immutable inputs
 * and binds this plan's fingerprint into the confirmed transaction journal.
 * A durable commit decision is irreversible: recovery thereafter only finishes
 * publication, so even gameplay after a lost final receipt cannot be rolled back.
 */
final class WorldBuilderCurrentRuntimeCutover {
    private static final String LEDGER = ".world-builder/runtime-ledger-v1.json";
    private static final String INSTANCE = ".world-builder/current-runtime/instance/installation";
    private static final String SELECTION = INSTANCE + "/active-launch.json";
    private static final String GUARD = "pending-cutover.json";
    private static final int LIMIT = 1048576;
    interface Observer { void at(String milestone) throws IOException; }
    private final Observer observer;
    WorldBuilderCurrentRuntimeCutover() { this(milestone -> { }); }
    WorldBuilderCurrentRuntimeCutover(Observer observer) { this.observer = observer; }

    /** Read-only preview. Empty expected hashes mean absent, never "ignore drift". */
    static Plan inspect(Path target, String transactionId, String expectedSelectionHash,
        String expectedLedgerHash, byte[] selection, byte[] ledger)
        throws IOException, WorldBuilderContractException {
        directory(target);
        if (!transactionId.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")) throw unsafe("Invalid cutover identity.");
        if (selection.length > LIMIT || ledger.length > LIMIT) throw unsafe("Activation documents exceed their bound.");
        Map<String,Object> selected = parse(selection), installed = parse(ledger);
        if (!"current-base-installed-selection".equals(selected.get("manifestType"))
            || !"world-builder-current-target-runtime-ledger".equals(installed.get("manifestType"))
            || !Objects.equals(selected.get("installationId"), installed.get("targetInstallationId")))
            throw unsafe("Selection and ledger do not identify the same installation.");
        byte[] beforeSelection = optional(target.resolve(SELECTION));
        byte[] beforeLedger = optional(target.resolve(LEDGER));
        if (!digest(beforeSelection).equals(expectedSelectionHash) || !digest(beforeLedger).equals(expectedLedgerHash))
            throw unsafe("Activation metadata changed after preview.");
        Map<String,Object> plan = new LinkedHashMap<String,Object>();
        plan.put("schemaVersion", Long.valueOf(1)); plan.put("manifestType", "world-builder-installed-cutover");
        plan.put("targetRoot", target.toString()); plan.put("transactionId", transactionId);
        plan.put("beforeSelection", encoded(beforeSelection)); plan.put("beforeLedger", encoded(beforeLedger));
        plan.put("afterSelection", encoded(selection)); plan.put("afterLedger", encoded(ledger));
        return new Plan(plan);
    }

    /** The outer confirmed receipt, not a self-declared field in this file, supplies expectedHash. */
    static Plan read(Path journal, Path expectedTarget, String expectedHash)
        throws IOException, WorldBuilderContractException {
        directory(journal); directory(expectedTarget);
        byte[] bytes = required(journal.resolve("plan.json"));
        if (!digest(bytes).equals(expectedHash)) throw unsafe("Cutover plan differs from confirmed recovery authority.");
        Map<String,Object> value = parse(bytes);
        exact(value, "schemaVersion", "manifestType", "targetRoot", "transactionId",
            "beforeSelection", "beforeLedger", "afterSelection", "afterLedger");
        if (!Long.valueOf(1).equals(value.get("schemaVersion"))
            || !"world-builder-installed-cutover".equals(value.get("manifestType"))
            || !expectedTarget.toString().equals(value.get("targetRoot"))) throw unsafe("Cutover plan identity differs.");
        Plan result = new Plan(value);
        if (!result.fingerprint.equals(expectedHash)) throw unsafe("Cutover plan is not canonical.");
        return result;
    }

    /** Persist before constructing/publishing any launchable initial instance. */
    static void journal(Plan plan, Path journal) throws IOException, WorldBuilderContractException {
        externalJournal(plan, journal); absent(journal); directory(journal.getParent());
        Files.createDirectory(journal, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        writeNew(journal.resolve("plan.json"), plan.bytes);
        WorldBuilderAdaptiveDurability.forceDirectory(journal);
        WorldBuilderAdaptiveDurability.forceDirectory(journal.getParent());
    }

    /** Both installed role leases must already be held by the owning transaction. */
    void apply(Plan plan, Path journal, WorldBuilderCurrentRuntimeInstanceLease lease)
        throws IOException, WorldBuilderContractException {
        authenticate(plan, journal); lease.verifyHeld(plan.target.resolve(INSTANCE));
        Path installation = plan.target.resolve(INSTANCE);
        Path guard = installation.resolve(GUARD);
        absent(journal.resolve("commit.json")); absent(journal.resolve("rollback.json"));
        requireStartingPair(plan);
        for (String relative : Arrays.asList(SELECTION, LEDGER))
            for (String phase : Arrays.asList("forward", "rollback"))
                absent(temporary(plan.target.resolve(relative), plan, phase));
        byte[] startingSelection = optional(plan.target.resolve(SELECTION));
        if (Files.exists(guard, LinkOption.NOFOLLOW_LINKS)) requireBytes(guard, plan.guard);
        else writeNew(guard, plan.guard);
        WorldBuilderAdaptiveDurability.forceDirectory(installation);
        try {
            observer.at("guard-durable");
            lease.verifyHeld(installation); requireBytes(guard, plan.guard); requireStartingPair(plan);
            replace(plan.target.resolve(LEDGER), plan.afterLedger, plan, "forward");
            observer.at("ledger-published");
            lease.verifyHeld(installation); requireBytes(guard, plan.guard);
            requireBytes(plan.target.resolve(LEDGER), plan.afterLedger);
            requireBytesOrAbsent(plan.target.resolve(SELECTION), startingSelection);
            replace(plan.target.resolve(SELECTION), plan.afterSelection, plan, "forward");
            observer.at("selection-published");
            requirePair(plan, true); lease.verifyHeld(installation); requireBytes(guard, plan.guard);
            // If the rename happened but its directory fsync failed, this process
            // must still never roll back: recovery durably confirms this decision.
            decision(journal, "commit", plan);
            observer.at("commit-durable");
            finishCommitted(plan, journal, lease);
        } catch (IOException | WorldBuilderContractException | RuntimeException failure) {
            try {
                if (Files.exists(journal.resolve("commit.json"), LinkOption.NOFOLLOW_LINKS)) {
                    requireBytes(journal.resolve("commit.json"), plan.decision("commit"));
                    throw unsafe("Commit decision may be durable; retain the guard and finalize through recovery.");
                }
                rollback(plan, journal, lease);
            } catch (IOException | WorldBuilderContractException | RuntimeException retained) {
                retained.addSuppressed(failure); throw retained;
            }
            throw failure;
        }
    }

    String recover(Plan plan, Path journal, WorldBuilderCurrentRuntimeInstanceLease lease)
        throws IOException, WorldBuilderContractException {
        authenticate(plan, journal); lease.verifyHeld(plan.target.resolve(INSTANCE));
        if (Files.exists(journal.resolve("commit.json"), LinkOption.NOFOLLOW_LINKS)) {
            requireBytes(journal.resolve("commit.json"), plan.decision("commit"));
            absent(journal.resolve("rollback.json"));
            WorldBuilderAdaptiveDurability.forceFile(journal.resolve("commit.json"));
            WorldBuilderAdaptiveDurability.forceDirectory(journal);
            finishCommitted(plan, journal, lease);
            return "successful";
        }
        rollback(plan, journal, lease);
        return "rolled-back";
    }

    private void finishCommitted(Plan plan, Path journal, WorldBuilderCurrentRuntimeInstanceLease lease)
        throws IOException, WorldBuilderContractException {
        requirePair(plan, true); lease.verifyHeld(plan.target.resolve(INSTANCE));
        requireBytes(journal.resolve("commit.json"), plan.decision("commit"));
        removeGuard(plan); observer.at("guard-removed");
        // No state snapshot, historical database or initial-output seal is read.
    }

    private void rollback(Plan plan, Path journal, WorldBuilderCurrentRuntimeInstanceLease lease)
        throws IOException, WorldBuilderContractException {
        absent(journal.resolve("commit.json")); lease.verifyHeld(plan.target.resolve(INSTANCE));
        Path guard = plan.target.resolve(INSTANCE).resolve(GUARD);
        if (!Files.exists(guard, LinkOption.NOFOLLOW_LINKS)) {
            // A crash before creating the guard (or after completed rollback)
            // authorizes no target write. In particular do not recreate a guard.
            requirePair(plan, false); decision(journal, "rollback", plan); return;
        }
        requireBytes(guard, plan.guard);
        // Validate BOTH destinations and all temporaries before changing either.
        requireEither(plan.target.resolve(SELECTION), plan.beforeSelection, plan.afterSelection);
        requireEither(plan.target.resolve(LEDGER), plan.beforeLedger, plan.afterLedger);
        for (String relative : Arrays.asList(SELECTION, LEDGER)) {
            Path file = plan.target.resolve(relative);
            byte[] before = relative.equals(SELECTION) ? plan.beforeSelection : plan.beforeLedger;
            byte[] after = relative.equals(SELECTION) ? plan.afterSelection : plan.afterLedger;
            checkTemporary(file, after, plan, "forward"); checkTemporary(file, before, plan, "rollback");
        }
        for (String relative : Arrays.asList(SELECTION, LEDGER)) {
            lease.verifyHeld(plan.target.resolve(INSTANCE)); requireBytes(guard, plan.guard);
            Path file = plan.target.resolve(relative);
            byte[] before = relative.equals(SELECTION) ? plan.beforeSelection : plan.beforeLedger;
            byte[] after = relative.equals(SELECTION) ? plan.afterSelection : plan.afterLedger;
            requireEither(file, before, after);
            removeTemporary(file, after, plan, "forward");
            if (!Arrays.equals(optional(file), before)) replace(file, before, plan, "rollback");
            removeTemporary(file, before, plan, "rollback");
        }
        requirePair(plan, false); decision(journal, "rollback", plan);
        lease.verifyHeld(plan.target.resolve(INSTANCE)); removeGuard(plan);
    }

    private static void requirePair(Plan plan, boolean after) throws IOException, WorldBuilderContractException {
        requireBytesOrAbsent(plan.target.resolve(SELECTION), after ? plan.afterSelection : plan.beforeSelection);
        requireBytesOrAbsent(plan.target.resolve(LEDGER), after ? plan.afterLedger : plan.beforeLedger);
    }
    private static void requireStartingPair(Plan plan) throws IOException, WorldBuilderContractException {
        if (plan.beforeSelection == null && Files.exists(plan.target.resolve(SELECTION), LinkOption.NOFOLLOW_LINKS)) {
            // Only initial construction may install its projected selection before
            // the metadata commit, and only behind this exact pre-journaled guard.
            requireBytes(plan.target.resolve(INSTANCE).resolve(GUARD), plan.guard);
            requireBytes(plan.target.resolve(SELECTION), plan.afterSelection);
            requireBytesOrAbsent(plan.target.resolve(LEDGER), plan.beforeLedger);
        } else requirePair(plan, false);
    }
    private static void requireEither(Path path, byte[] a, byte[] b) throws IOException, WorldBuilderContractException {
        byte[] actual = optional(path);
        if (!Arrays.equals(actual, a) && !Arrays.equals(actual, b)) throw unsafe("Activation metadata is neither exact preimage nor transaction output.");
    }
    private static void decision(Path journal, String kind, Plan plan) throws IOException, WorldBuilderContractException {
        Path path = journal.resolve(kind + ".json");
        byte[] bytes = plan.decision(kind);
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) requireBytes(path, bytes);
        else {
            Path temp = journal.resolve(kind + ".pending");
            if (Files.exists(temp, LinkOption.NOFOLLOW_LINKS)) requireBytes(temp, bytes);
            else writeNew(temp, bytes);
            WorldBuilderAdaptiveDurability.forceFile(temp);
            Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE);
        }
        WorldBuilderAdaptiveDurability.forceFile(path);
        WorldBuilderAdaptiveDurability.forceDirectory(journal);
    }
    private static Path temporary(Path file, Plan plan, String phase) {
        return file.resolveSibling("." + file.getFileName() + "." + plan.transactionId + "." + phase);
    }
    private static void checkTemporary(Path file, byte[] bytes, Plan plan, String phase) throws IOException, WorldBuilderContractException {
        Path temp = temporary(file, plan, phase);
        if (Files.exists(temp, LinkOption.NOFOLLOW_LINKS)) {
            if (bytes == null) throw unsafe("Unexpected temporary for an absent preimage.");
            requireBytes(temp, bytes);
        }
    }
    private static void removeTemporary(Path file, byte[] bytes, Plan plan, String phase) throws IOException, WorldBuilderContractException {
        Path temp = temporary(file, plan, phase); checkTemporary(file, bytes, plan, phase);
        if (Files.exists(temp, LinkOption.NOFOLLOW_LINKS)) { Files.delete(temp); WorldBuilderAdaptiveDurability.forceDirectory(temp.getParent()); }
    }
    private static void replace(Path file, byte[] bytes, Plan plan, String phase) throws IOException, WorldBuilderContractException {
        directory(file.getParent()); optional(file);
        if (bytes == null) { Files.delete(file); WorldBuilderAdaptiveDurability.forceDirectory(file.getParent()); return; }
        Path temp = temporary(file, plan, phase);
        if (Files.exists(temp, LinkOption.NOFOLLOW_LINKS)) requireBytes(temp, bytes);
        else writeNew(temp, bytes);
        WorldBuilderAdaptiveDurability.forceFile(temp);
        Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        WorldBuilderAdaptiveDurability.forceFile(file); WorldBuilderAdaptiveDurability.forceDirectory(file.getParent());
        requireBytes(file, bytes);
    }
    private static void removeGuard(Plan plan) throws IOException, WorldBuilderContractException {
        Path guard = plan.target.resolve(INSTANCE).resolve(GUARD);
        if (Files.exists(guard, LinkOption.NOFOLLOW_LINKS)) {
            requireBytes(guard, plan.guard); Files.delete(guard); WorldBuilderAdaptiveDurability.forceDirectory(guard.getParent());
        }
    }
    private static void authenticate(Plan plan, Path journal) throws IOException, WorldBuilderContractException {
        externalJournal(plan, journal); directory(journal); requireBytes(journal.resolve("plan.json"), plan.bytes);
    }
    private static void externalJournal(Plan plan, Path journal) throws IOException, WorldBuilderContractException {
        canonicalProjection(journal);
        if (journal.startsWith(plan.target) || plan.target.startsWith(journal)) throw unsafe("Cutover journal must be external to the target.");
        if (!Files.getFileStore(journal.getParent()).equals(Files.getFileStore(plan.target))) throw unsafe("Cutover journal must share the target filesystem.");
    }
    private static void canonicalProjection(Path path) throws IOException, WorldBuilderContractException {
        if (!path.isAbsolute() || !path.normalize().equals(path)) throw unsafe("Cutover paths must be canonical absolute paths.");
        Path ancestor = path;
        while (!Files.exists(ancestor, LinkOption.NOFOLLOW_LINKS)) ancestor = ancestor.getParent();
        if (!ancestor.equals(ancestor.toRealPath())) throw unsafe("Aliased cutover path.");
    }
    private static void directory(Path path) throws IOException, WorldBuilderContractException {
        canonicalProjection(path);
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) throw unsafe("Required cutover directory is absent or unsafe.");
    }
    private static byte[] optional(Path file) throws IOException, WorldBuilderContractException {
        canonicalProjection(file);
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return null;
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > LIMIT
            || ((Number)Files.getAttribute(file, "unix:nlink", LinkOption.NOFOLLOW_LINKS)).longValue() != 1
            || !Files.getPosixFilePermissions(file, LinkOption.NOFOLLOW_LINKS).equals(PosixFilePermissions.fromString("rw-------")))
            throw unsafe("Activation metadata must be bounded private singly-linked files.");
        return Files.readAllBytes(file);
    }
    private static byte[] required(Path path) throws IOException, WorldBuilderContractException {
        byte[] result = optional(path); if (result == null) throw unsafe("Required cutover evidence is absent."); return result;
    }
    private static void requireBytes(Path path, byte[] bytes) throws IOException, WorldBuilderContractException {
        if (!Arrays.equals(required(path), bytes)) throw unsafe("Cutover evidence has drifted.");
    }
    private static void requireBytesOrAbsent(Path path, byte[] bytes) throws IOException, WorldBuilderContractException {
        if (!Arrays.equals(optional(path), bytes)) throw unsafe("Cutover metadata differs from the exact expected bytes.");
    }
    private static void absent(Path path) throws WorldBuilderContractException {
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) throw unsafe("Cutover destination or decision already exists.");
    }
    private static void writeNew(Path path, byte[] bytes) throws IOException {
        Files.createFile(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        Files.write(path, bytes, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
        WorldBuilderAdaptiveDurability.forceFile(path);
    }
    private static Map<String,Object> parse(byte[] bytes) throws WorldBuilderContractException {
        try { return WorldBuilderJsonDocuments.readObject(bytes, "installed-cutover"); }
        catch (WorldBuilderDiscoveryException malformed) { throw unsafe("Malformed cutover document."); }
    }
    private static byte[] json(Object value) { return WorldBuilderJsonDocuments.pretty(value).getBytes(StandardCharsets.UTF_8); }
    private static String digest(byte[] bytes) { return bytes == null ? "" : WorldBuilderHashes.sha256(bytes); }
    private static Object encoded(byte[] bytes) { return bytes == null ? null : Base64.getEncoder().encodeToString(bytes); }
    private static byte[] decoded(Object value) throws WorldBuilderContractException {
        if (value == null) return null;
        if (!(value instanceof String) || ((String)value).length() > LIMIT * 2) throw unsafe("Invalid cutover document encoding.");
        try { byte[] bytes = Base64.getDecoder().decode((String)value); if (bytes.length > LIMIT) throw unsafe("Cutover document exceeds bound."); return bytes; }
        catch (IllegalArgumentException invalid) { throw unsafe("Malformed cutover document encoding."); }
    }
    private static void exact(Map<String,Object> value, String... keys) throws WorldBuilderContractException {
        if (!value.keySet().equals(new HashSet<String>(Arrays.asList(keys)))) throw unsafe("Closed cutover fields differ.");
    }
    private static WorldBuilderContractException unsafe(String message) {
        return new WorldBuilderContractException(WorldBuilderErrorCodes.RECOVERY_REQUIRED,
            "installed-cutover", "activation", true, message,
            "Keep both roles offline and preserve the exact journal and guard; never force restoration.");
    }
    static final class Plan {
        final Path target;
        final String transactionId, fingerprint;
        final byte[] bytes, guard, beforeSelection, beforeLedger, afterSelection, afterLedger;
        Plan(Map<String,Object> value) throws WorldBuilderContractException {
            if (!(value.get("targetRoot") instanceof String) || !(value.get("transactionId") instanceof String)) throw unsafe("Malformed cutover identity.");
            target = Paths.get((String)value.get("targetRoot")); transactionId = (String)value.get("transactionId");
            if (!transactionId.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}") || !target.isAbsolute() || !target.normalize().equals(target)) throw unsafe("Unsafe cutover identity.");
            beforeSelection = decoded(value.get("beforeSelection")); beforeLedger = decoded(value.get("beforeLedger"));
            afterSelection = decoded(value.get("afterSelection")); afterLedger = decoded(value.get("afterLedger"));
            if (afterSelection == null || afterLedger == null) throw unsafe("Cutover cannot remove its active metadata.");
            bytes = json(value); if (bytes.length > LIMIT) throw unsafe("Cutover plan exceeds its bound.");
            fingerprint = digest(bytes); guard = decision("pending");
        }
        byte[] decision(String status) {
            Map<String,Object> value = new LinkedHashMap<String,Object>();
            value.put("schemaVersion", Long.valueOf(1)); value.put("manifestType", "world-builder-installed-cutover-decision");
            value.put("transactionId", transactionId); value.put("planSha256", fingerprint); value.put("decision", status);
            return json(value);
        }
    }
}
