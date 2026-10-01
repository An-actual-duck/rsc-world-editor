package com.openrsc.worldbuilder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** Evidence-only successor to an independently rebuilt runtime. */
final class WorldBuilderRuntimeReverification {
    static final String FIELD = "runtimeReverification";

    static WorldBuilderAdaptiveMutationProfile.Plan prepare(
        WorldBuilderAdaptiveProjectLifecycle.VerifiedProject project, Path target, String id,
        WorldBuilderAdaptiveReceipt.State previous) throws IOException, WorldBuilderContractException {
        if (previous == null) throw refusal("Re-verification requires a retained successful integration transaction.");
        if (Files.exists(project.projectRoot.resolve(WorldBuilderFloorUpgradeLineage.PATH)))
            throw refusal("Re-verification of a sibling floor-upgrade project is not yet supported; retain its complete parent history.");
        Map<String,Object> previousPlan = readPlan(project, previous.transactionId());
        // This restores every retained runtime action and checks receipt, export,
        // before-backup and exact plan bindings without trusting the live target.
        Map<String,Object> history = WorldBuilderRuntimeUpgradeHistory.describePredecessors(project, target, previousPlan);
        List<Object> references = new ArrayList<Object>(array(history.get("references")));
        references.add(reference(project, previous));
        Map<String,Path> baseline = new TreeMap<String,Path>();
        byte[] proof = null;
        Map<String,Object> baselineReference = null;
        for (Object raw : references) {
            Map<String,Object> ref = object(raw);
            String tx = string(ref, "transactionId");
            Map<String,Object> plan = readPlan(project, tx);
            if (plan.containsKey(FIELD)) continue;
            Map<String,Path> candidate = new TreeMap<String,Path>();
            for (Object actionRaw : array(plan.get("actions"))) {
                Map<String,Object> action = object(actionRaw);
                if (!string(action,"role").startsWith(WorldBuilderTargetMapIntegration.ROLE)) continue;
                WorldBuilderAdaptiveMutationProfile.Action restored = WorldBuilderTargetMapIntegration.restoreAction(action, project.projectRoot, tx);
                Path content = project.projectRoot.resolve("backups/" + tx + "/content/targeted/" + restored.role + ".bin");
                candidate.put(restored.destinationRelativePath, content);
            }
            if (candidate.containsKey(WorldBuilderTargetMapIntegration.INSTALLED) && candidate.containsKey("server/core.jar")) {
                baseline = candidate;
                proof = Files.readAllBytes(candidate.get(WorldBuilderTargetMapIntegration.INSTALLED));
                baselineReference = ref;
            }
        }
        if (proof == null) throw refusal("The complete original targeted integration archive evidence is unavailable.");
        WorldBuilderAdaptiveExporter.VerifiedExport export = WorldBuilderAdaptiveUndo.findExport(project, previous.exportFingerprint());
        Map<String,Object> configuration = object(project.snapshot.get("selectedConfiguration"));
        String configPath = string(configuration,"relativePath").substring("source/original/".length());
        WorldBuilderAdaptiveConfiguration selected = WorldBuilderAdaptiveConfiguration.read(
            WorldBuilderReadOnlyTarget.open(target), configPath, WorldBuilderHashes.sha256(target.resolve(configPath)));
        String clientRoot = WorldBuilderInstalledFloorContent.clientRoot(selected);
        WorldBuilderTargetMapIntegration.Result result = WorldBuilderTargetMapIntegration.reverify(
            project.projectRoot, target, clientRoot, proof, baseline);
        Set<String> archives = new TreeSet<String>();
        Map<String,Object> originalProof;
        try { originalProof = WorldBuilderJsonDocuments.readObject(proof, "retained-integration-proof"); }
        catch (WorldBuilderDiscoveryException invalid) { throw refusal("Retained integration proof is malformed."); }
        for (Object row : array(originalProof.get("archives"))) archives.add(string(object(row),"relativePath"));
        WorldBuilderAdaptiveMutationProfile.Plan predecessor = WorldBuilderAdaptiveMutationProfile.reconstructInstalled(
            project, export, target, previous.transactionId(), archives);
        WorldBuilderAdaptiveReceipt.requireSuccessfulImportMatches(predecessor, previous);
        Map<String,Object> inputs = new TreeMap<String,Object>();
        for (WorldBuilderAdaptiveMutationProfile.Action action : predecessor.actions) {
            if (!archives.contains(action.destinationRelativePath))
                WorldBuilderAdaptiveImporter.verifyState(target, action.destinationRelativePath, action.after);
            if (!WorldBuilderTargetMapIntegration.INSTALLED.equals(action.destinationRelativePath))
                inputs.put(action.destinationRelativePath, action.after.toJson());
        }
        for (String path : result.inputs.keySet()) {
            WorldBuilderAdaptiveMutationProfile.FileState state = state(target,path);
            if (!state.sha256.equals(result.inputs.get(path))) throw refusal("Runtime changed during verification: " + path);
            if (!WorldBuilderTargetMapIntegration.INSTALLED.equals(path)) inputs.put(path,state.toJson());
        }
        inputs.put(configPath,state(target,configPath).toJson());
        Map<String,Object> evidence = new LinkedHashMap<String,Object>();
        evidence.put("predecessor", reference(project, previous));
        evidence.put("baseline", baselineReference);
        evidence.put("inputs", inputs);
        try { evidence.put("inventories", WorldBuilderJsonDocuments.readObject(result.outputs.get(WorldBuilderTargetMapIntegration.INSTALLED), "verified-proof").get("inputInventories")); }
        catch (WorldBuilderDiscoveryException invalid) { throw refusal("Verified proof is malformed."); }
        return WorldBuilderAdaptiveMutationProfile.prepareReverification(project, export, target, id, predecessor, result, evidence);
    }

    static void bind(Map<String,Object> plan, Object evidence) throws WorldBuilderContractException {
        validateShape(evidence); plan.put(FIELD,evidence);
        WorldBuilderAdaptiveExporter.bindFingerprint(plan,"planFingerprintSha256");
    }
    static void copy(Map<String,Object> next, Map<String,Object> source) throws WorldBuilderContractException {
        if (source.containsKey(FIELD)) bind(next,source.get(FIELD));
    }
    static void validateShape(Object raw) throws WorldBuilderContractException {
        Map<String,Object> value = object(raw);
        WorldBuilderBoundedInventory.exactKeys(value,FIELD,"predecessor","baseline","inputs","inventories");
        for (String key : Arrays.asList("predecessor","baseline"))
            WorldBuilderRuntimeUpgradeHistory.validateShape(Collections.singletonList(value.get(key)));
        List<?> inventories = array(value.get("inventories"));
        if (inventories.size() > 128) throw refusal("Runtime inventory is unbounded.");
        Map<String,Object> roots = new HashMap<String,Object>();
        for (Object rawInventory : inventories) {
            Map<String,Object> inventory = object(rawInventory);
            WorldBuilderBoundedInventory.exactKeys(inventory,FIELD,"relativePath","suffix","recursive","paths");
            String root=string(inventory,"relativePath"); WorldBuilderPortablePath.require(root,FIELD);
            Object existing = roots.put(root, inventory);
            if ((existing != null && !existing.equals(inventory)) || !Arrays.asList(".java",".jar").contains(string(inventory,"suffix"))) throw refusal("Invalid or conflicting runtime inventory.");
            WorldBuilderAdaptiveExporter.bool(inventory,"recursive");
            List<?> paths=array(inventory.get("paths"));
            if (paths.size()>16000) throw refusal("Runtime inventory exceeds its bound.");
            String prior="";
            for (Object rawPath:paths) {
                if (!(rawPath instanceof String)) throw refusal("Invalid runtime inventory path.");
                String path=(String)rawPath; WorldBuilderPortablePath.require(path,FIELD);
                if (!path.startsWith(root+"/") || !path.endsWith(string(inventory,"suffix")) || path.compareTo(prior)<=0)
                    throw refusal("Runtime inventory paths are not unique, ordered children.");
                prior=path;
            }
        }
        Map<String,Object> inputs = object(value.get("inputs"));
        if (inputs.isEmpty() || inputs.size() > 100000) throw refusal("Re-verification input inventory is empty or exceeds its bound.");
        for (Map.Entry<String,Object> entry : inputs.entrySet()) {
            WorldBuilderPortablePath.require(entry.getKey(),FIELD);
            Map<String,Object> state = object(entry.getValue());
            WorldBuilderBoundedInventory.exactKeys(state,FIELD,"present","size","sha256");
            fileState(state);
        }
    }
    static void replay(WorldBuilderAdaptiveProjectLifecycle.VerifiedProject project, Map<String,Object> plan,
        Map<String,WorldBuilderAdaptiveMutationProfile.FileState> states) throws IOException, WorldBuilderContractException {
        if (!plan.containsKey(FIELD)) return;
        Map<String,Object> value = object(plan.get(FIELD)); validateShape(value);
        for (String key : Arrays.asList("predecessor","baseline")) {
            Map<String,Object> ref = object(value.get(key));
            WorldBuilderAdaptiveReceipt.State receipt = WorldBuilderAdaptiveReceipt.read(project.projectRoot.resolve("receipts/" + string(ref,"transactionId") + ".json"));
            if (!"successful".equals(receipt.status()) || !"import".equals(receipt.transactionType())
                || !reference(project,receipt).equals(ref)) throw refusal("Re-verification predecessor evidence changed.");
        }
        List<WorldBuilderAdaptiveReceipt.State> receipts = WorldBuilderAdaptiveReceipt.readAll(project.projectRoot);
        WorldBuilderAdaptiveReceipt.State boundary = null, latest = null;
        for (WorldBuilderAdaptiveReceipt.State receipt : receipts)
            if (receipt.transactionId().equals(plan.get("transactionId"))) boundary = receipt;
        if (boundary == null) throw refusal("Re-verification has no durable transaction receipt.");
        Set<String> reverted = new HashSet<String>();
        for (WorldBuilderAdaptiveReceipt.State receipt : receipts) {
            if (receipt.compareTo(boundary)>=0) continue;
            if (receipt.createdAtUtc().equals(boundary.createdAtUtc())) throw refusal("Re-verification history order is ambiguous.");
            if ("undo".equals(receipt.transactionType()) && "reverted".equals(receipt.status())) reverted.add(receipt.revertsTransactionId());
        }
        for (WorldBuilderAdaptiveReceipt.State receipt : receipts)
            if (receipt.compareTo(boundary)<0 && "import".equals(receipt.transactionType()) && "successful".equals(receipt.status())
                && !reverted.contains(receipt.transactionId())) latest=receipt;
        if (latest == null || !latest.transactionId().equals(object(value.get("predecessor")).get("transactionId")))
            throw refusal("Re-verification predecessor is not the latest retained installed transaction.");
        Map<String,Object> baseline = readPlan(project,string(object(value.get("baseline")),"transactionId"));
        if (baseline.containsKey(FIELD) || reverted.contains(baseline.get("transactionId"))) throw refusal("Re-verification baseline is not an original active integration.");
        WorldBuilderAdaptiveReceipt.State baselineReceipt = WorldBuilderAdaptiveReceipt.read(project.projectRoot.resolve("receipts/"+baseline.get("transactionId")+".json"));
        if (baselineReceipt.compareTo(latest)>0) throw refusal("Re-verification baseline follows its predecessor.");
        Map<String,Object> predecessor = readPlan(project,string(object(value.get("predecessor")),"transactionId"));
        Map<String,Object> expected = new TreeMap<String,Object>();
        for (Object raw : array(predecessor.get("actions"))) {
            Map<String,Object> action=object(raw);
            if (!WorldBuilderTargetMapIntegration.INSTALLED.equals(action.get("destinationRelativePath")))
                expected.put(string(action,"destinationRelativePath"),action.get("after"));
        }
        List<?> actions=array(plan.get("actions"));
        if (actions.size()!=1 || !WorldBuilderTargetMapIntegration.INSTALLED.equals(object(actions.get(0)).get("destinationRelativePath"))
            || !array(plan.get("configurationChanges")).isEmpty()) throw refusal("Re-verification may only update its installed proof.");
        WorldBuilderAdaptiveMutationProfile.Action proofAction=WorldBuilderTargetMapIntegration.restoreAction(
            object(actions.get(0)),project.projectRoot,string(plan,"transactionId"));
        Map<String,Object> proof;
        try { proof=WorldBuilderJsonDocuments.readObject(proofAction.generatedContent,"reverification-proof"); }
        catch (WorldBuilderDiscoveryException invalid) { throw refusal("Retained re-verification proof is malformed."); }
        Map<String,Object> inputs=object(value.get("inputs"));
        Map<String,Object> proofInputs=object(proof.get("beforeInputs"));
        for (Map.Entry<String,Object> input:proofInputs.entrySet()) {
            if (WorldBuilderTargetMapIntegration.INSTALLED.equals(input.getKey())) continue;
            Map<String,Object> recorded=object(inputs.get(input.getKey()));
            if (!Boolean.TRUE.equals(recorded.get("present")) || !input.getValue().equals(recorded.get("sha256")))
                throw refusal("Re-verification input disagrees with the compiled proof: "+input.getKey());
            expected.put(input.getKey(),recorded);
        }
        Map<String,Object> selected=object(plan.get("selectedConfiguration"));
        String configuration=string(selected,"relativePath");
        Map<String,Object> currentConfiguration=object(inputs.get(configuration));
        if (!selected.get("sha256").equals(currentConfiguration.get("sha256"))) throw refusal("Re-verification configuration authority differs.");
        expected.put(configuration,currentConfiguration);
        if (!expected.equals(inputs) || !value.get("inventories").equals(proof.get("inputInventories")))
            throw refusal("Re-verification inputs differ from the retained proof and predecessor actions.");
        for (Map.Entry<String,Object> entry : inputs.entrySet())
            states.put(entry.getKey(),fileState(object(entry.getValue())));
    }
    static void verifyRetainedInputs(WorldBuilderAdaptiveMutationProfile.Plan plan) throws IOException, WorldBuilderContractException {
        if (!plan.document.containsKey(WorldBuilderRuntimeUpgradeHistory.FIELD)) return;
        Set<String> superseded = new HashSet<String>();
        for (WorldBuilderAdaptiveMutationProfile.Action action : plan.actions) superseded.add(action.destinationRelativePath);
        WorldBuilderRuntimeUpgradeHistory.verify(plan.project, plan.targetRoot, plan.document, superseded);
    }

    static String summary(WorldBuilderAdaptiveMutationProfile.Plan plan) {
        try {
            Map<String,Object> evidence = object(plan.document.get(FIELD));
            Map<String,Object> baseline = readPlan(plan.project, string(object(evidence.get("baseline")), "transactionId"));
            Map<String,Object> inputs = object(evidence.get("inputs"));
            StringBuilder out = new StringBuilder("Checked runtime/map inputs: " + inputs.size() + " files; "
                + array(evidence.get("inventories")).size() + " source/dependency inventories.\n");
            for (Object raw : array(baseline.get("actions"))) {
                Map<String,Object> action = object(raw); String path = string(action, "destinationRelativePath");
                if (!string(action, "role").startsWith(WorldBuilderTargetMapIntegration.ROLE) || !path.endsWith(".jar")) continue;
                String before = string(object(action.get("after")), "sha256");
                String after = string(object(inputs.get(path)), "sha256");
                out.append("Retain ").append(path).append(": ").append(before.substring(0, 12))
                    .append(" → ").append(after.substring(0, 12)).append(before.equals(after) ? " (unchanged)\n" : " (verified equivalent rebuild)\n");
            }
            return out.toString() + "\n";
        } catch (IOException | WorldBuilderContractException invalid) {
            // Presentation is not authority; apply revalidates all durable inputs.
            return "Retained preview evidence is unavailable; apply will require fresh verification.\n\n";
        }
    }

    static void verifyInputs(WorldBuilderAdaptiveMutationProfile.Plan plan) throws IOException, WorldBuilderContractException {
        Map<String,WorldBuilderAdaptiveMutationProfile.FileState> states = new TreeMap<String,WorldBuilderAdaptiveMutationProfile.FileState>();
        replay(plan.project,plan.document,states);
        verifyInventories(plan.targetRoot, plan.document);
        for (Map.Entry<String,WorldBuilderAdaptiveMutationProfile.FileState> entry : states.entrySet())
            WorldBuilderAdaptiveImporter.verifyState(plan.targetRoot,entry.getKey(),entry.getValue());
    }
    static void verifyInventories(Path target, Map<String,Object> plan) throws IOException, WorldBuilderContractException {
        if (plan.containsKey(FIELD)) WorldBuilderTargetMapIntegration.verifyInventories(target,
            array(object(plan.get(FIELD)).get("inventories")));
    }
    static boolean hasSuccessful(WorldBuilderAdaptiveProjectLifecycle.VerifiedProject project) throws IOException, WorldBuilderContractException {
        for (WorldBuilderAdaptiveReceipt.State receipt : WorldBuilderAdaptiveReceipt.readAll(project.projectRoot))
            if ("successful".equals(receipt.status()) && "import".equals(receipt.transactionType())
                && readPlan(project,receipt.transactionId()).containsKey(FIELD)) return true;
        return false;
    }
    static void requireUndoAllowed(WorldBuilderAdaptiveProjectLifecycle.VerifiedProject project,
        WorldBuilderAdaptiveReceipt.State authority) throws IOException, WorldBuilderContractException {
        for (WorldBuilderAdaptiveReceipt.State receipt : WorldBuilderAdaptiveReceipt.readAll(project.projectRoot))
            if ("successful".equals(receipt.status()) && "import".equals(receipt.transactionType())
                && readPlan(project,receipt.transactionId()).containsKey(FIELD) && authority.compareTo(receipt) <= 0)
                throw refusal("Historical undo stops at the independently rebuilt runtime's re-verification boundary. Later map imports may be undone; older runtime binaries will not be restored.");
    }
    static Map<String,Object> readPlan(WorldBuilderAdaptiveProjectLifecycle.VerifiedProject project,String id)
        throws IOException, WorldBuilderContractException {
        Path path = WorldBuilderReadOnlyTarget.open(project.projectRoot).requiredFile("backups/"+id+"/mutation-plan.json");
        Map<String,Object> plan;
        try { plan=WorldBuilderJsonDocuments.readObject(path); }
        catch (WorldBuilderDiscoveryException invalid) { throw refusal("Retained mutation plan is malformed."); }
        WorldBuilderAdaptiveExporter.requireFingerprint(plan,"planFingerprintSha256");
        WorldBuilderAdaptiveContracts.validateParsed(WorldBuilderAdaptiveContracts.Kind.MUTATION_PLAN,plan);
        return plan;
    }
    static Map<String,Object> reference(WorldBuilderAdaptiveProjectLifecycle.VerifiedProject project,WorldBuilderAdaptiveReceipt.State receipt)
        throws IOException, WorldBuilderContractException {
        Map<String,Object> plan=readPlan(project,receipt.transactionId());
        String hash=WorldBuilderAdaptiveContracts.validateParsed(WorldBuilderAdaptiveContracts.Kind.MUTATION_PLAN,plan).canonicalSha256;
        if (!hash.equals(receipt.document.get("mutationPlanSha256")) || !project.projectId.equals(plan.get("projectId"))
            || !project.projectId.equals(receipt.document.get("projectId"))) throw refusal("Retained receipt and plan identity disagree.");
        if (!receipt.transactionId().equals(plan.get("transactionId"))) throw refusal("Retained transaction identity differs.");
        for (String key:Arrays.asList("exportFingerprintSha256","adapterId","capabilityId","targetLineageSha256","selectedConfiguration"))
            if (!Objects.equals(plan.get(key),receipt.document.get(key))) throw refusal("Retained receipt binding differs: "+key);
        Map<String,Object> projectTarget=object(project.manifest.get("target"));
        for (String key:Arrays.asList("adapterId","capabilityId"))
            if (!Objects.equals(projectTarget.get(key),plan.get(key))) throw refusal("Retained transaction target differs: "+key);
        if (!Objects.equals(projectTarget.get("importProfileId"),plan.get("mutationProfileId"))) throw refusal("Retained mutation profile differs.");
        Map<String,Object> ref=new LinkedHashMap<String,Object>(); ref.put("transactionId",receipt.transactionId());
        ref.put("receiptSha256",WorldBuilderHashes.sha256(WorldBuilderReadOnlyTarget.open(project.projectRoot).requiredFile("receipts/"+receipt.transactionId()+".json")));
        ref.put("mutationPlanSha256",hash); return ref;
    }
    private static WorldBuilderAdaptiveMutationProfile.FileState state(Path target,String path) throws IOException,WorldBuilderContractException {
        Path file=WorldBuilderReadOnlyTarget.open(target).requiredFile(path);
        return WorldBuilderAdaptiveMutationProfile.FileState.present(Files.size(file),WorldBuilderHashes.sha256(file));
    }
    private static WorldBuilderAdaptiveMutationProfile.FileState fileState(Map<String,Object> value) throws WorldBuilderContractException {
        if (!WorldBuilderAdaptiveExporter.bool(value,"present")) {
            if (WorldBuilderAdaptiveExporter.integer(value,"size") != 0 || !string(value,"sha256").isEmpty()) throw refusal("Invalid absent runtime input state.");
            return WorldBuilderAdaptiveMutationProfile.FileState.absent();
        }
        long size=WorldBuilderAdaptiveExporter.integer(value,"size"); String hash=string(value,"sha256");
        if (size<0 || !WorldBuilderBoundedInventory.isHash(hash)) throw refusal("Invalid runtime input state.");
        return WorldBuilderAdaptiveMutationProfile.FileState.present(size,hash);
    }
    private static Map<String,Object> object(Object raw) throws WorldBuilderContractException { return WorldBuilderAdaptiveExporter.object(raw,FIELD); }
    private static List<?> array(Object raw) throws WorldBuilderContractException { return WorldBuilderAdaptiveExporter.array(raw,FIELD); }
    private static String string(Map<String,Object> map,String key) throws WorldBuilderContractException { return WorldBuilderAdaptiveExporter.string(map,key); }
    static WorldBuilderContractException refusal(String message) {
        return new WorldBuilderContractException(WorldBuilderErrorCodes.RUNTIME_UPGRADE_REQUIRED,FIELD,FIELD,false,message,
            "Keep the target offline. Retain the complete project and exact integration evidence; restore unsupported changes or request a reviewed integration. No force mode exists.");
    }
}
