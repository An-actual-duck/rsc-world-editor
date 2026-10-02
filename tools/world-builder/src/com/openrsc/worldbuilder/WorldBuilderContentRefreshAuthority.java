package com.openrsc.worldbuilder;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Verifies a content-only successor without relaxing installed transaction checks. */
final class WorldBuilderContentRefreshAuthority {
    private static final String OP = "detect-new-content";
    private static final int MAX_FILES = 100000;
    private WorldBuilderContentRefreshAuthority() { }

    static Map<String,Object> verify(WorldBuilderAdaptiveProjectLifecycle.VerifiedProject parent,
        Path requestedTarget, Map<String,Object> freshReport) throws IOException, WorldBuilderContractException {
        WorldBuilderAdaptiveContracts.validateParsed(WorldBuilderAdaptiveContracts.Kind.DISCOVERY_REPORT, freshReport);
        WorldBuilderAdaptiveExporter.requireFingerprint(freshReport, "discoveryFingerprintSha256");
        if (!"compatible".equals(freshReport.get("status"))) throw refusal("discovery", "Fresh discovery must be compatible before content refresh.");
        WorldBuilderReadOnlyTarget target = WorldBuilderReadOnlyTarget.open(requestedTarget);
        if ("standalone-empty".equals(parent.origin)) throw refusal("project", "Content refresh requires an attached target project.");
        Map<String,WorldBuilderAdaptiveMutationProfile.FileState> expected = new TreeMap<>();
        Map<String,Path> originals = new TreeMap<>();
        Map<String,byte[]> generated = new TreeMap<>();
        Set<String> paths = new TreeSet<>();
        for (String group : Arrays.asList("originalFiles", "definitionRuntimeFiles")) for (Object raw : list(parent.snapshot.get(group))) {
            Map<String,Object> row = object(raw); String path = string(row,"relativePath");
            if (!path.startsWith("source/original/")) continue;
            String relative = path.substring("source/original/".length());
            WorldBuilderAdaptiveMutationProfile.FileState state = state(row);
            WorldBuilderAdaptiveMutationProfile.FileState prior = expected.put(relative,state);
            if (prior != null && !prior.toJson().equals(state.toJson())) throw refusal(relative,"Original evidence contains conflicting file states.");
            paths.add(relative);
            if (state.present) originals.put(relative, WorldBuilderAdaptiveExporter.requireFile(parent.projectRoot,path,"immutable original evidence"));
        }
        Map<String,Object> reference = object(parent.snapshot.get("selectedConfiguration"));
        String configurationPath = string(reference,"relativePath").substring("source/original/".length());
        WorldBuilderAdaptiveConfiguration originalConfiguration = WorldBuilderAdaptiveConfiguration.read(
            WorldBuilderReadOnlyTarget.open(parent.projectRoot.resolve("source/original")),configurationPath,string(reference,"sha256"));
        List<WorldBuilderAdaptiveReceipt.State> receipts = WorldBuilderAdaptiveReceipt.readAll(parent.projectRoot);
        Set<String> reverted = new HashSet<>();
        for (WorldBuilderAdaptiveReceipt.State receipt : receipts) {
            if ("pending".equals(receipt.status()) || "recovery-required".equals(receipt.status()))
                throw refusal("receipts/"+receipt.transactionId()+".json","Finish interrupted transaction recovery before detecting new content.");
            if ("undo".equals(receipt.transactionType()) && "reverted".equals(receipt.status())) reverted.add(receipt.revertsTransactionId());
        }
        List<Object> history = new ArrayList<>();
        WorldBuilderAdaptiveReceipt.State latest = null;
        for (WorldBuilderAdaptiveReceipt.State receipt : receipts) {
            if (!"import".equals(receipt.transactionType()) || !"successful".equals(receipt.status()) || reverted.contains(receipt.transactionId())) continue;
            String id = receipt.transactionId();
            Map<String,Object> plan = read(WorldBuilderAdaptiveExporter.requireFile(parent.projectRoot,"backups/"+id+"/mutation-plan.json","retained transaction"));
            WorldBuilderAdaptiveExporter.requireFingerprint(plan,"planFingerprintSha256");
            String planHash = WorldBuilderAdaptiveContracts.validateParsed(WorldBuilderAdaptiveContracts.Kind.MUTATION_PLAN,plan).canonicalSha256;
            if (!planHash.equals(receipt.document.get("mutationPlanSha256")) || !parent.projectId.equals(plan.get("projectId"))
                || !parent.projectId.equals(receipt.document.get("projectId")) || !id.equals(plan.get("transactionId")))
                throw refusal("receipts/"+id+".json","Retained plan and receipt authority disagree.");
            for (String key : Arrays.asList("exportFingerprintSha256","adapterId","capabilityId","targetLineageSha256","selectedConfiguration"))
                if (!Objects.equals(plan.get(key),receipt.document.get(key))) throw refusal("receipts/"+id+".json","Retained plan and receipt bindings disagree: "+key);
            WorldBuilderAdaptiveExporter.VerifiedExport export = WorldBuilderAdaptiveUndo.findExport(parent,receipt.exportFingerprint());
            List<WorldBuilderAdaptiveMutationProfile.Action> runtime = new ArrayList<>();
            WorldBuilderAdaptiveMutationProfile.appendStoredRuntimeCompatibilityActions(plan,parent,export,target.root,id,originalConfiguration,runtime);
            Map<String,WorldBuilderAdaptiveMutationProfile.Action> restored = new HashMap<>();
            for (WorldBuilderAdaptiveMutationProfile.Action action : runtime) restored.put(action.destinationRelativePath,action);
            Map<String,Map<String,Object>> receiptFiles = new HashMap<>();
            for (Object raw : list(receipt.document.get("files"))) {
                Map<String,Object> row=object(raw); if(receiptFiles.put(string(row,"relativePath"),row)!=null) throw refusal(id,"Receipt repeats a file.");
            }
            for (Object raw : list(plan.get("actions"))) {
                Map<String,Object> action=object(raw);String path=string(action,"destinationRelativePath");
                Map<String,Object> recorded=receiptFiles.remove(path);
                if(recorded==null || !Objects.equals(action.get("role"),recorded.get("role"))
                    || !Objects.equals(action.get("before"),recorded.get("before")) || !Objects.equals(action.get("after"),recorded.get("after")))
                    throw refusal(path,"Retained action and receipt file evidence disagree.");
                paths.add(path); expected.put(path,state(object(action.get("after"))));
                WorldBuilderAdaptiveMutationProfile.Action verified=restored.get(path);
                if(verified!=null && verified.generatedContent!=null) generated.put(path,verified.generatedContent);
            }
            if(!receiptFiles.isEmpty())throw refusal(id,"Retained receipt has unexpected file evidence.");
            Map<String,Object> item=new LinkedHashMap<>();item.put("transactionId",id);item.put("mutationPlanSha256",planHash);
            item.put("receiptSha256",WorldBuilderHashes.sha256(WorldBuilderAdaptiveExporter.requireFile(parent.projectRoot,"receipts/"+id+".json","retained receipt")));
            history.add(item);latest=receipt;
        }
        // Exact current configuration is never replaced by a historical copy.
        WorldBuilderAdaptiveMutationProfile.FileState expectedConfiguration=expected.get(configurationPath);
        if(expectedConfiguration==null)throw refusal(configurationPath,"Selected configuration has no retained authority.");
        WorldBuilderAdaptiveImporter.verifyState(target.root,configurationPath,expectedConfiguration);
        WorldBuilderAdaptiveConfiguration configuration=WorldBuilderAdaptiveConfiguration.read(target,configurationPath,expectedConfiguration.sha256);

        Set<String> content=new TreeSet<>();
        WorldBuilderPackedSourceLayout layout=WorldBuilderPackedSourceLayout.select(target);
        List<WorldBuilderReadOnlyTarget.FileState> inspected=WorldBuilderProjectContentBundle.inspectTarget(target,layout);
        for(WorldBuilderReadOnlyTarget.FileState file:inspected){paths.add(file.relativePath);if(contentRole(file.role,file.relativePath))content.add(file.relativePath);}
        for(Object raw:list(freshReport.get("files"))){Map<String,Object> row=object(raw);String path=string(row,"relativePath");paths.add(path);
            WorldBuilderAdaptiveImporter.verifyState(target.root,path,state(row));
            if(!expected.containsKey(path)&&!content.contains(path))throw refusal(path,"Fresh discovery introduced non-content evidence outside the retained target authority.");
        }
        String catalogPath=configuration.serverDefinitionCatalogRelativePath;
        Map<String,Object> freshCatalog=target.readObject(catalogPath);
        Map<String,Object> derived=WorldBuilderProjectContentBundle.deriveTargetCatalog(target,layout,string(freshCatalog,"catalogId"));
        for(String family:Arrays.asList("tiles","boundaries","scenery","npcs","groundItems"))
            if(!Objects.equals(freshCatalog.get(family),derived.get(family)))throw refusal(catalogPath,"Target catalog is not the effective content projection for "+family+"; regenerate paired catalogs from maintained definitions.");
        content.add(catalogPath);content.add(configuration.clientDefinitionCatalogRelativePath);
        Set<String> projections=new TreeSet<>(Arrays.asList(WorldBuilderTargetCapability.RELATIVE_PATH,configuration.serverRuntimeRelativePath,configuration.clientRuntimeRelativePath));
        paths.addAll(content);paths.addAll(projections);
        String catalogHash=WorldBuilderHashes.sha256(target.requiredFile(catalogPath));
        for(String path:projections){
            byte[] old=historical(path,expected,originals,generated);if(old==null)throw refusal(path,"Catalog projection has no retained historical authority.");
            Map<String,Object> prior=read(old,path),current=target.readObject(path);
            if(path.equals(WorldBuilderTargetCapability.RELATIVE_PATH)){
                Map<String,Object> beforeDefinitions=object(prior.get("definitions")),afterDefinitions=object(current.get("definitions"));
                if(!catalogHash.equals(afterDefinitions.get("catalogSha256")))throw refusal(path,"Capability catalog projection is stale.");
                afterDefinitions.put("catalogSha256",beforeDefinitions.get("catalogSha256"));
            }else{
                if(!catalogHash.equals(current.get("definitionCatalogSha256")))throw refusal(path,"Runtime catalog projection is stale.");
                current.put("definitionCatalogSha256",prior.get("definitionCatalogSha256"));
            }
            if(!prior.equals(current))throw refusal(path,"Content refresh cannot accept unrelated capability, runtime or protocol changes.");
        }
        // Validate the live maintained inventories before constructing any shadow.
        verifyLiveRuntime(target,expected,paths);
        Set<String> substituted=new TreeSet<>(content);substituted.addAll(projections);
        Map<String,Object> liveStates=new TreeMap<>();
        if(paths.size()>MAX_FILES)throw refusal("target","Refresh evidence exceeds its file bound.");
        Path shadow=Files.createTempDirectory("world-builder-content-authority-");
        try{
            for(String path:paths){WorldBuilderReadOnlyTarget.FileState live=target.optionalState("content-refresh",path);liveStates.put(path,fileState(live).toJson());
                Path out=WorldBuilderPortablePath.resolveContained(shadow,path,OP);
                if(live.present){if(live.size>512L*1024*1024)throw refusal(path,"Refresh evidence file exceeds 512 MiB.");Files.createDirectories(out.getParent());
                    // Read-only hard links avoid duplicating retained map archives.
                    try{Files.createLink(out,target.requiredFile(path));}catch(IOException unavailable){Files.copy(target.requiredFile(path),out);}
                }
                if(substituted.contains(path)){
                    byte[] old=historical(path,expected,originals,generated);
                    Files.deleteIfExists(out); // Never write through a live hard link.
                    if(old!=null){Files.createDirectories(out.getParent());Files.write(out,old,StandardOpenOption.CREATE_NEW);}
                }
            }
            if(latest!=null){
                WorldBuilderAdaptiveExporter.VerifiedExport export=WorldBuilderAdaptiveUndo.findExport(parent,latest.exportFingerprint());
                WorldBuilderAdaptiveMutationProfile.Plan installed=WorldBuilderAdaptiveMutationProfile.reconstructInstalled(parent,export,shadow,latest.transactionId());
                WorldBuilderAdaptiveReceipt.requireSuccessfulImportMatches(installed,latest);
                installed=WorldBuilderAdaptiveUndo.resolveEffectiveInstalledPlan(installed);
                List<String> changed=WorldBuilderAdaptiveUndo.changedAfterPaths(installed);
                if(!changed.isEmpty())throw refusal(changed.get(0),"The latest installed transaction changed outside the reviewed content transition.");
            }else{
                for(Map.Entry<String,WorldBuilderAdaptiveMutationProfile.FileState> entry:expected.entrySet())WorldBuilderAdaptiveImporter.verifyState(shadow,entry.getKey(),entry.getValue());
            }
            for(Map.Entry<String,Object> entry:liveStates.entrySet())WorldBuilderAdaptiveImporter.verifyState(target.root,entry.getKey(),state(object(entry.getValue())));
            verifyLiveRuntime(target,expected,new TreeSet<String>());
            WorldBuilderAdaptiveDiscoveryReport currentReport = new WorldBuilderAdaptiveDiscovery().discover(target.root,string(reference,"role"));
            if(!freshReport.get("discoveryFingerprintSha256").equals(currentReport.fingerprintSha256()))
                throw refusal("discovery","Target inventory changed during content verification; repeat discovery and review.");
        }finally{try(java.util.stream.Stream<Path> walk=Files.walk(shadow)){List<Path> cleanup=new ArrayList<>();walk.forEach(cleanup::add);cleanup.sort(Comparator.reverseOrder());for(Path path:cleanup)Files.deleteIfExists(path);}}
        Map<String,Object> proof=new LinkedHashMap<>();proof.put("schemaVersion",1L);proof.put("manifestType","world-builder-content-refresh-authority");
        proof.put("projectId",parent.projectId);proof.put("projectFingerprintSha256",parent.manifest.get("projectFingerprintSha256"));
        proof.put("savedMapFingerprintSha256",parent.working.fingerprintSha256);proof.put("discoveryFingerprintSha256",freshReport.get("discoveryFingerprintSha256"));
        proof.put("history",history);proof.put("contentPaths",new ArrayList<>(content));proof.put("catalogProjectionPaths",new ArrayList<>(projections));proof.put("targetStates",liveStates);
        return proof;
    }

    private static void verifyLiveRuntime(WorldBuilderReadOnlyTarget target,Map<String,WorldBuilderAdaptiveMutationProfile.FileState> expected,Set<String> paths)
        throws IOException,WorldBuilderContractException{
        String installed=WorldBuilderTargetMapIntegration.INSTALLED;
        if(!target.exists(installed))return;
        WorldBuilderAdaptiveMutationProfile.FileState authority=expected.get(installed);
        if(authority==null)throw refusal(installed,"Installed integration proof has no project-owned authority.");
        WorldBuilderAdaptiveImporter.verifyState(target.root,installed,authority);paths.add(installed);
        Map<String,Object> proof=target.readObject(installed);Map<String,String> inputs=new TreeMap<>();
        for(Map.Entry<String,Object> row:object(proof.get("beforeInputs")).entrySet())inputs.put(row.getKey(),(String)row.getValue());
        Set<String> addedSources=new TreeSet<>();
        for(String group:Arrays.asList("sources","archives"))for(Object raw:list(proof.get(group))){Map<String,Object> row=object(raw);String path=string(row,"relativePath");inputs.put(path,string(row,"sha256"));if("sources".equals(group))addedSources.add(path);}
        if(expected.containsKey("server/build.xml"))inputs.put("server/build.xml",expected.get("server/build.xml").sha256);
        for(Map.Entry<String,String> entry:inputs.entrySet()){paths.add(entry.getKey());if(!entry.getValue().equals(WorldBuilderHashes.sha256(target.requiredFile(entry.getKey()))))throw refusal(entry.getKey(),"Maintained source, binary or dependency changed; content refresh cannot accept a runtime rebuild.");}
        for(Object raw:list(proof.get("inputInventories"))){Map<String,Object> inventory=new LinkedHashMap<>(object(raw));Set<String> names=new TreeSet<>();for(Object path:list(inventory.get("paths")))names.add((String)path);
            String root=string(inventory,"relativePath"),suffix=string(inventory,"suffix");
            for(String path:addedSources)if(path.startsWith(root+"/")&&path.endsWith(suffix))names.add(path);
            inventory.put("paths",new ArrayList<>(names));WorldBuilderTargetMapIntegration.verifyInventories(target.root,Collections.singletonList(inventory));paths.addAll(names);
        }
    }
    private static boolean contentRole(String role,String path){
        if(path.endsWith(".java")||path.endsWith(".class")||path.endsWith(".jar"))return false;
        return role.startsWith("server-definition.")||role.startsWith("content.definition.")||role.startsWith("content.asset.")||role.startsWith("content.metadata.")
            ||"client-asset.library".equals(role)||"npc-visual-image".equals(role)||"npc-visual-metadata".equals(role)||"definition-composition.patch".equals(role);
    }
    private static byte[] historical(String path,Map<String,WorldBuilderAdaptiveMutationProfile.FileState> expected,Map<String,Path> originals,Map<String,byte[]> generated)throws IOException,WorldBuilderContractException{
        WorldBuilderAdaptiveMutationProfile.FileState state=expected.get(path);if(state==null||!state.present)return null;
        byte[] bytes=generated.get(path);if(bytes==null&&originals.containsKey(path))bytes=Files.readAllBytes(originals.get(path));
        if(bytes==null||bytes.length!=state.size||!WorldBuilderHashes.sha256(bytes).equals(state.sha256))throw refusal(path,"Exact historical content bytes are missing or do not match retained transaction authority.");return bytes;
    }
    private static WorldBuilderAdaptiveMutationProfile.FileState fileState(WorldBuilderReadOnlyTarget.FileState value){return value.present?WorldBuilderAdaptiveMutationProfile.FileState.present(value.size,value.sha256):WorldBuilderAdaptiveMutationProfile.FileState.absent();}
    private static WorldBuilderAdaptiveMutationProfile.FileState state(Map<String,Object> value)throws WorldBuilderContractException{return WorldBuilderAdaptiveExporter.bool(value,"present")?WorldBuilderAdaptiveMutationProfile.FileState.present(WorldBuilderAdaptiveExporter.integer(value,"size"),string(value,"sha256")):WorldBuilderAdaptiveMutationProfile.FileState.absent();}
    private static Map<String,Object> read(Path path)throws IOException,WorldBuilderContractException{try{return WorldBuilderJsonDocuments.readObject(path);}catch(WorldBuilderDiscoveryException bad){throw refusal(path.toString(),"Malformed retained JSON evidence.");}}
    private static Map<String,Object> read(byte[] bytes,String path)throws WorldBuilderContractException{try{return WorldBuilderJsonDocuments.readObject(bytes,path);}catch(WorldBuilderDiscoveryException bad){throw refusal(path,"Malformed historical JSON evidence.");}}
    private static Map<String,Object> object(Object value)throws WorldBuilderContractException{return WorldBuilderAdaptiveExporter.object(value,OP);}
    private static List<?> list(Object value)throws WorldBuilderContractException{return WorldBuilderAdaptiveExporter.array(value,OP);}
    private static String string(Map<String,Object> value,String key)throws WorldBuilderContractException{return WorldBuilderAdaptiveExporter.string(value,key);}
    private static WorldBuilderContractException refusal(String path,String message){return new WorldBuilderContractException(WorldBuilderErrorCodes.TARGET_DRIFT,OP,path,false,message,"Resolve the reported map/runtime/history difference separately, then retry Detect New Content.");}
}
