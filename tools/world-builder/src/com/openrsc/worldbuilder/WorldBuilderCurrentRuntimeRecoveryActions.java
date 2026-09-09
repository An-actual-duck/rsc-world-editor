package com.openrsc.worldbuilder;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Desktop recovery selection. Journals supply evidence, never arbitrary execution paths. */
final class WorldBuilderCurrentRuntimeRecoveryActions {
    private static final String GUARD = ".world-builder/current-runtime/instance/installation/pending-cutover.json";

    static Preview preview(Path target, String projectId, Path workspace)
        throws IOException, WorldBuilderContractException {
        directory(target); directory(workspace);
        if (target.startsWith(workspace) || workspace.startsWith(target)
            || !Files.getFileStore(target).equals(Files.getFileStore(workspace)))
            throw new IOException("Recovery requires the original external same-filesystem workspace.");
        Map<String,Object> guard = Files.exists(target.resolve(GUARD), LinkOption.NOFOLLOW_LINKS)
            ? document(target.resolve(GUARD)) : Collections.<String,Object>emptyMap();
        List<Preview> candidates = new ArrayList<Preview>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(workspace)) {
            int count = 0;
            for (Path entry : entries) {
                if (++count > 1024) throw new IOException("Recovery workspace exceeds its bounded history. Use explicit transaction recovery.");
                directory(entry);
                boolean map = Files.exists(entry.resolve("map-plan.json"), LinkOption.NOFOLLOW_LINKS);
                boolean upgrade = Files.exists(entry.resolve("upgrade-plan.json"), LinkOption.NOFOLLOW_LINKS);
                if (map && upgrade) throw new IOException("Recovery journal contains ambiguous transaction types.");
                if (!map && !upgrade) continue;
                Map<String,Object> plan = document(entry.resolve(map ? "map-plan.json" : "upgrade-plan.json"));
                Object boundProject = map ? plan.get("projectId") : object(plan.get("projectCapability")).get("projectId");
                if (!projectId.equals(boundProject)) continue;
                String id = text(plan.get("transactionId"));
                if (!id.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,110}")
                    || !entry.getFileName().toString().equals((map ? "map-" : "") + id))
                    throw new IOException("Recovery transaction directory does not match its plan.");
                if (map) {
                    boolean decided = Files.exists(entry.resolve("activation/commit.json"), LinkOption.NOFOLLOW_LINKS)
                        || Files.exists(entry.resolve("activation/rollback.json"), LinkOption.NOFOLLOW_LINKS);
                    if (decided && !Objects.equals(plan.get("cutoverPlanSha256"), guard.get("planSha256"))) continue;
                    String fingerprint = WorldBuilderHashes.sha256(read(entry.resolve("map-plan.json")));
                    Map<String,Object> reviewed = new WorldBuilderCurrentRuntimeMapImport().previewRecovery(target, workspace, id, fingerprint);
                    if (!projectId.equals(reviewed.get("projectId"))) throw new IOException("Recovery project changed during preview.");
                    candidates.add(new Preview(target, workspace, projectId, id, fingerprint, true));
                } else {
                    String status = text(document(entry.resolve("receipt.json")).get("status"));
                    if ("successful".equals(status) || "rolled-back".equals(status)) continue;
                    WorldBuilderCurrentRuntimeUpgradeTransaction.RecoveryPreview reviewed =
                        new WorldBuilderCurrentRuntimeUpgradeTransaction().previewRecovery(target, workspace, id);
                    if (!projectId.equals(object(reviewed.plan.get("projectCapability")).get("projectId")))
                        throw new IOException("Recovery project changed during preview.");
                    candidates.add(new Preview(target, workspace, projectId, id, reviewed.fingerprint, false));
                }
            }
        }
        if (candidates.size() != 1) throw new IOException(candidates.isEmpty()
            ? "No interrupted current transaction was found for this project. Completed operations are not Undo actions. For an explicitly selected CLI workspace, use its exact recovery command."
            : "More than one interrupted transaction needs review. Preserve all evidence and use explicit transaction recovery; do not guess the latest journal.");
        return candidates.get(0);
    }

    static String apply(Preview reviewed, String confirmation) throws IOException, WorldBuilderContractException {
        if (reviewed == null || !reviewed.confirmation().equals(confirmation))
            throw new IOException("Recovery requires the exact reviewed confirmation.");
        Preview fresh = preview(reviewed.target, reviewed.projectId, reviewed.workspace);
        if (fresh.map != reviewed.map || !fresh.transactionId.equals(reviewed.transactionId)
            || !fresh.fingerprint.equals(reviewed.fingerprint)) throw new IOException("Recovery evidence changed after preview. Review it again.");
        if (reviewed.map) return new WorldBuilderCurrentRuntimeMapImport().recover(
            reviewed.target, reviewed.workspace, reviewed.transactionId, reviewed.fingerprint);
        return new WorldBuilderCurrentRuntimeUpgradeTransaction().recover(
            reviewed.target, reviewed.workspace, reviewed.transactionId, reviewed.fingerprint).toJson();
    }

    private static void directory(Path path) throws IOException {
        if (!path.isAbsolute() || !path.normalize().equals(path) || !path.equals(path.toRealPath())
            || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Recovery directory is absent or aliased.");
    }
    private static byte[] read(Path path) throws IOException, WorldBuilderContractException {
        directory(path.getParent());
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || !path.equals(path.toRealPath())
            || Files.size(path) > WorldBuilderContractLimits.MAX_JSON_BYTES)
            throw new IOException("Recovery metadata is absent, aliased, or oversized.");
        WorldBuilderAdaptiveExporter.rejectHardLink(path, "recovery-metadata");
        return Files.readAllBytes(path);
    }
    private static Map<String,Object> document(Path path) throws IOException, WorldBuilderContractException {
        try { return WorldBuilderJsonDocuments.readObject(read(path), "current-recovery"); }
        catch (WorldBuilderDiscoveryException malformed) { throw new IOException("Malformed recovery metadata.", malformed); }
    }
    private static Map<?,?> object(Object value) throws IOException {
        if (!(value instanceof Map)) throw new IOException("Malformed recovery project binding.");
        return (Map<?,?>)value;
    }
    private static String text(Object value) throws IOException {
        if (!(value instanceof String)) throw new IOException("Malformed recovery identity.");
        return (String)value;
    }

    static final class Preview {
        final Path target, workspace;
        final String projectId, transactionId, fingerprint;
        final boolean map;
        Preview(Path target, Path workspace, String projectId, String transactionId, String fingerprint, boolean map) {
            this.target = target; this.workspace = workspace; this.projectId = projectId;
            this.transactionId = transactionId; this.fingerprint = fingerprint; this.map = map;
        }
        String confirmation() { return "RECOVER:" + fingerprint; }
        String summary() {
            return "Recover Interrupted " + (map ? "Map Import" : "Runtime Upgrade")
                + "\n\nServer target: " + target + "\nTransaction: " + transactionId
                + "\nEvidence: " + workspace.resolve((map ? "map-" : "") + transactionId)
                + "\n\nBoth server and client must be offline. Recovery finishes a committed activation"
                + " or restores the verified pre-commit state. It never rewinds gameplay after commit."
                + "\nExact confirmation: " + confirmation();
        }
    }
}
