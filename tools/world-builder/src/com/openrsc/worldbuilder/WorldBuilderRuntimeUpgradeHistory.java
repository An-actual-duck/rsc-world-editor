package com.openrsc.worldbuilder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/** Project-owned runtime authority retained across map-only transactions. */
final class WorldBuilderRuntimeUpgradeHistory {
    static final String FIELD = "runtimeUpgradeHistory";

    static void copy(Map<String,Object> destination, Map<String,Object> source)
        throws WorldBuilderContractException {
        if (source.containsKey(FIELD)) bind(destination, source.get(FIELD));
    }

    private static void bind(Map<String,Object> plan, Object references) throws WorldBuilderContractException {
        validateShape(references);
        plan.put(FIELD, references);
        WorldBuilderAdaptiveExporter.bindFingerprint(plan, "planFingerprintSha256");
    }

    static void record(WorldBuilderAdaptiveMutationProfile.Plan installed, Map<String,Object> next)
        throws IOException, WorldBuilderContractException {
        History history = read(installed.project, installed.targetRoot, next);
        if (!history.references.isEmpty()) bind(next, history.references);
    }

    static Set<String> verify(WorldBuilderAdaptiveProjectLifecycle.VerifiedProject project,
        Path target, Map<String,Object> plan, Set<String> superseded)
        throws IOException, WorldBuilderContractException {
        History history = read(project, target, plan);
        for (Map.Entry<String,WorldBuilderAdaptiveMutationProfile.FileState> item : history.states.entrySet()) {
            if (!superseded.contains(item.getKey()))
                WorldBuilderAdaptiveImporter.verifyState(target, item.getKey(), item.getValue());
        }
        return history.states.keySet();
    }

    static boolean hasPredecessors(WorldBuilderAdaptiveProjectLifecycle.VerifiedProject project,
        Path target, Map<String,Object> plan) throws IOException, WorldBuilderContractException {
        return !read(project, target, plan).references.isEmpty();
    }

    static Map<String,Object> describePredecessors(WorldBuilderAdaptiveProjectLifecycle.VerifiedProject project,
        Path target, Map<String,Object> plan) throws IOException, WorldBuilderContractException {
        History history = read(project, target, plan);
        Map<String,Object> result = new LinkedHashMap<String,Object>();
        result.put("references", history.references);
        Map<String,Object> states = new TreeMap<String,Object>();
        for (Map.Entry<String,WorldBuilderAdaptiveMutationProfile.FileState> entry : history.states.entrySet())
            states.put(entry.getKey(), entry.getValue().toJson());
        result.put("states", states);
        return result;
    }

    private static History read(WorldBuilderAdaptiveProjectLifecycle.VerifiedProject project,
        Path target, Map<String,Object> current) throws IOException, WorldBuilderContractException {
        List<WorldBuilderAdaptiveReceipt.State> receipts = WorldBuilderAdaptiveReceipt.readAll(project.projectRoot);
        Set<String> reverted = new HashSet<String>();
        WorldBuilderAdaptiveReceipt.State boundary = null;
        String currentId = string(current, "transactionId");
        for (WorldBuilderAdaptiveReceipt.State receipt : receipts) {
            if (currentId.equals(receipt.transactionId())) boundary = receipt;
            if ("undo".equals(receipt.transactionType()) && "reverted".equals(receipt.status()))
                reverted.add(receipt.revertsTransactionId());
        }
        if (boundary != null) for (WorldBuilderAdaptiveReceipt.State receipt : receipts)
            if (!receipt.transactionId().equals(currentId) && receipt.createdAtUtc().equals(boundary.createdAtUtc()))
                throw refusal("Transaction history order is ambiguous.");
        Map<String,Object> projectTarget = object(project.manifest.get("target"));
        Map<String,Object> projectConfiguration = object(project.snapshot.get("selectedConfiguration"));
        History result = new History();
        WorldBuilderReadOnlyTarget evidence = WorldBuilderReadOnlyTarget.open(project.projectRoot);
        for (WorldBuilderAdaptiveReceipt.State receipt : receipts) {
            if (boundary != null && receipt.compareTo(boundary) >= 0) break;
            if (!"import".equals(receipt.transactionType()) || !"successful".equals(receipt.status())
                || reverted.contains(receipt.transactionId())) continue;
            String id = receipt.transactionId();
            Path path = evidence.requiredFile("backups/" + id + "/mutation-plan.json");
            Map<String,Object> plan;
            try { plan = WorldBuilderJsonDocuments.readObject(path); }
            catch (WorldBuilderDiscoveryException malformed) { throw refusal("Historical mutation plan is malformed."); }
            WorldBuilderAdaptiveExporter.requireFingerprint(plan, "planFingerprintSha256");
            String hash = WorldBuilderAdaptiveContracts.validateParsed(WorldBuilderAdaptiveContracts.Kind.MUTATION_PLAN, plan).canonicalSha256;
            boolean runtime = false;
            for (Object raw : WorldBuilderAdaptiveExporter.array(plan.get("actions"), "actions"))
                runtime |= string(object(raw), "role").startsWith("runtime-compatibility-");
            if (!runtime) continue;
            if (boundary != null && receipt.createdAtUtc().equals(boundary.createdAtUtc()))
                throw refusal("Runtime transaction order is ambiguous.");
            if (!hash.equals(receipt.document.get("mutationPlanSha256"))
                || !project.projectId.equals(plan.get("projectId")) || !project.projectId.equals(receipt.document.get("projectId"))
                || !id.equals(plan.get("transactionId"))) throw refusal("Runtime plan and receipt identities disagree.");
            for (String key : new String[]{"exportFingerprintSha256", "adapterId", "capabilityId", "targetLineageSha256", "selectedConfiguration"})
                if (!plan.get(key).equals(receipt.document.get(key))) throw refusal("Runtime plan and receipt bindings disagree: " + key);
            for (String key : new String[]{"adapterId", "capabilityId"})
                if (!plan.get(key).equals(projectTarget.get(key))) throw refusal("Runtime history belongs to a different project target: " + key);
            if (!plan.get("mutationProfileId").equals(projectTarget.get("importProfileId")))
                throw refusal("Runtime history belongs to a different mutation profile.");
            if (plan.containsKey(FIELD) && !result.references.equals(plan.get(FIELD)))
                throw refusal("Runtime history no longer matches its recorded predecessors.");
            WorldBuilderAdaptiveExporter.VerifiedExport export = WorldBuilderAdaptiveUndo.findExport(project, receipt.exportFingerprint());
            Map<String,Object> selected = object(plan.get("selectedConfiguration"));
            String configurationPath = string(selected, "relativePath");
            if (!selected.get("role").equals(projectConfiguration.get("role"))
                || !("source/original/" + configurationPath).equals(projectConfiguration.get("relativePath")))
                throw refusal("Runtime history selected configuration differs from the project snapshot authority.");
            Path original = evidence.requiredFile("source/original/" + configurationPath);
            byte[] configurationBytes = Files.readAllBytes(original);
            // Runtime action restoration uses immutable layout/client-root fields,
            // never the historical active map paths. A sibling floor project can
            // inherit a parent map configuration without owning its before backup.
            WorldBuilderAdaptiveConfiguration configuration = WorldBuilderAdaptiveConfiguration.readBytes(
                configurationBytes, configurationPath, WorldBuilderHashes.sha256(configurationBytes));
            if (!configuration.configurationId.equals(selected.get("role")))
                throw refusal("Runtime history selected a different configuration role.");
            List<WorldBuilderAdaptiveMutationProfile.Action> restored = new ArrayList<WorldBuilderAdaptiveMutationProfile.Action>();
            WorldBuilderAdaptiveMutationProfile.appendStoredRuntimeCompatibilityActions(plan, project, export, target, id, configuration, restored);
            int runtimeCount = 0;
            for (Object raw : WorldBuilderAdaptiveExporter.array(plan.get("actions"), "actions"))
                if (string(object(raw), "role").startsWith("runtime-compatibility-")) runtimeCount++;
            if (restored.isEmpty() || restored.size() != runtimeCount) throw refusal("Runtime action inventory could not be restored exactly.");
            Map<String,Map<String,Object>> files = new TreeMap<String,Map<String,Object>>();
            for (Object raw : WorldBuilderAdaptiveExporter.array(receipt.document.get("files"), "files")) {
                Map<String,Object> file = object(raw);
                if (files.put(string(file,"relativePath"), file) != null) throw refusal("Runtime receipt repeats a destination.");
            }
            for (WorldBuilderAdaptiveMutationProfile.Action action : restored) {
                Map<String,Object> file = files.get(action.destinationRelativePath);
                if (file == null || !action.role.equals(file.get("role")) || !action.before.toJson().equals(file.get("before"))
                    || !action.after.toJson().equals(file.get("after")) || !action.backupRelativePath.equals(file.get("backupRelativePath"))
                    || !Boolean.TRUE.equals(file.get("afterVerified"))) throw refusal("Runtime receipt does not verify its restored action.");
                WorldBuilderAdaptiveMutationProfile.FileState previous = result.states.get(action.destinationRelativePath);
                if (previous != null && !previous.toJson().equals(action.before.toJson()))
                    throw refusal("Runtime history has a discontinuous before-state: " + action.destinationRelativePath);
                if (action.before.present) {
                    Path backup = evidence.requiredFile(action.backupRelativePath);
                    if (Files.size(backup) != action.before.size || !WorldBuilderHashes.sha256(backup).equals(action.before.sha256))
                        throw refusal("Runtime history before backup changed: " + action.destinationRelativePath);
                }
                result.states.put(action.destinationRelativePath, action.after);
            }
            Map<String,Object> reference = new LinkedHashMap<String,Object>();
            reference.put("transactionId", id);
            reference.put("receiptSha256", WorldBuilderHashes.sha256(evidence.requiredFile("receipts/" + id + ".json")));
            reference.put("mutationPlanSha256", hash);
            result.references.add(reference);
            if (result.references.size() > 4096) throw refusal("Runtime upgrade history exceeds its bounded inventory.");
        }
        if (current.containsKey(FIELD) && !result.references.equals(current.get(FIELD)))
            throw refusal("Recorded runtime upgrade authority changed or is unavailable.");
        return result;
    }

    static void validateShape(Object raw) throws WorldBuilderContractException {
        List<?> values = WorldBuilderAdaptiveExporter.array(raw, FIELD);
        if (values.isEmpty() || values.size() > 4096) throw refusal("Runtime upgrade history is empty or unbounded.");
        Set<String> ids = new HashSet<String>();
        for (Object value : values) {
            Map<String,Object> reference = object(value);
            WorldBuilderBoundedInventory.exactKeys(reference, FIELD, "transactionId", "receiptSha256", "mutationPlanSha256");
            String id = string(reference,"transactionId");
            try { if (!UUID.fromString(id).toString().equals(id) || !ids.add(id)) throw new IllegalArgumentException(); }
            catch (IllegalArgumentException invalid) { throw refusal("Runtime history transaction identity is invalid or repeated."); }
            for (String key : new String[]{"receiptSha256", "mutationPlanSha256"})
                if (!WorldBuilderBoundedInventory.isHash(string(reference,key))) throw refusal("Runtime history evidence hash is invalid.");
        }
    }

    private static Map<String,Object> object(Object value) throws WorldBuilderContractException {
        return WorldBuilderAdaptiveExporter.object(value, FIELD);
    }
    private static String string(Map<String,Object> value, String key) throws WorldBuilderContractException {
        return WorldBuilderAdaptiveExporter.string(value, key);
    }
    private static WorldBuilderContractException refusal(String message) {
        return new WorldBuilderContractException(WorldBuilderErrorCodes.RECOVERY_REQUIRED, FIELD, FIELD, false, message,
            "Keep the target offline and restore the exact retained project transaction evidence; do not force the operation.");
    }
    private static final class History {
        final List<Object> references = new ArrayList<Object>();
        final Map<String,WorldBuilderAdaptiveMutationProfile.FileState> states = new TreeMap<String,WorldBuilderAdaptiveMutationProfile.FileState>();
    }
}
