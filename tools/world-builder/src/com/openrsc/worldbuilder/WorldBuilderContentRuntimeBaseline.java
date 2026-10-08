package com.openrsc.worldbuilder;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Immutable content-origin links retain original runtime compilation authority. */
final class WorldBuilderContentRuntimeBaseline {
    static final String FIELD = "baselineProject";
    private static final String ORIGIN = "source/content-refresh/origin.json";
    static final class Baseline {
        final WorldBuilderAdaptiveProjectLifecycle.VerifiedProject owner;
        final Map<String,Object> reference, binding, snapshot;
        final Map<String,Path> outputs;
        Baseline(WorldBuilderAdaptiveProjectLifecycle.VerifiedProject owner, Map<String,Object> reference,
            Map<String,Object> binding, Map<String,Path> outputs) {
            this(owner,reference,binding,outputs,null);
        }
        Baseline(WorldBuilderAdaptiveProjectLifecycle.VerifiedProject owner, Map<String,Object> reference,
            Map<String,Object> binding, Map<String,Path> outputs, Map<String,Object> snapshot) {
            this.owner=owner;this.reference=reference;this.binding=binding;this.outputs=outputs;this.snapshot=snapshot;
        }
    }

    static Baseline resolve(WorldBuilderAdaptiveProjectLifecycle.VerifiedProject project)
        throws IOException, WorldBuilderContractException {
        if (!hasOrigin(project)) return WorldBuilderSnapshotRuntimeBaseline.resolve(project);
        Path projects=project.projectRoot.getParent();
        if(projects==null || !"projects".equals(projects.getFileName().toString()) || projects.getParent()==null)
            throw fail("Inherited runtime authority requires the original registered projects directory.");
        Path install=projects.getParent();
        Map<String,Object> registry=read(WorldBuilderAdaptiveExporter.requireFile(install,
            WorldBuilderAdaptiveProjectLifecycle.REGISTRY_FILE,"project registry"));
        WorldBuilderAdaptiveContracts.validateParsed(WorldBuilderAdaptiveContracts.Kind.PROJECT_REGISTRY,registry);
        WorldBuilderAdaptiveExporter.requireFingerprint(registry,"registryFingerprintSha256");
        Map<String,Map<String,Object>> registered=new HashMap<>();
        for(Object raw:list(registry.get("projects"))){Map<String,Object> row=object(raw);if(registered.put(string(row,"projectId"),row)!=null)throw fail("Project registry repeats an ancestor identity.");}
        List<Object> lineage=new ArrayList<>();Set<String> seen=new HashSet<>();
        WorldBuilderAdaptiveProjectLifecycle.VerifiedProject child=project;
        for(int depth=0;depth<16;depth++){
            if(!seen.add(child.projectId))throw fail("Content-origin runtime lineage contains a cycle.");
            Map<String,Object> declared=null;
            for(Object raw:list(child.snapshot.get("originalFiles"))){Map<String,Object> row=object(raw);
                if(ORIGIN.equals(row.get("relativePath"))){if(declared!=null)throw fail("Content origin is repeated.");declared=row;}}
            if(declared==null || !"content-refresh-origin".equals(declared.get("role")))
                throw fail("The complete original targeted integration archive evidence is unavailable in the retained content-origin chain.");
            Path originFile=WorldBuilderAdaptiveExporter.requireFile(child.projectRoot,ORIGIN,"content origin");
            if(!Boolean.TRUE.equals(declared.get("present")) || Files.size(originFile)!=WorldBuilderAdaptiveExporter.integer(declared,"size")
                || !WorldBuilderHashes.sha256(originFile).equals(declared.get("sha256")))throw fail("Content-origin snapshot evidence changed.");
            Map<String,Object> origin=read(originFile);
            if(!Long.valueOf(1).equals(origin.get("schemaVersion")) || !"world-builder-content-refresh-origin".equals(origin.get("manifestType")))
                throw fail("Unsupported content-origin authority.");
            String id=string(origin,"projectId");uuid(id);
            if(seen.contains(id))throw fail("Content-origin runtime lineage contains a cycle.");
            Map<String,Object> record=registered.get(id);
            if(record==null || !("projects/"+id+"/project.json").equals(record.get("manifestRelativePath")))
                throw fail("The runtime ancestor is not registered in this installation.");
            Path root=WorldBuilderPortablePath.resolveContained(install,"projects/"+id,FIELD);
            Path manifest=WorldBuilderAdaptiveExporter.requireFile(root,"project.json","ancestor manifest");
            if(!WorldBuilderHashes.sha256(manifest).equals(record.get("manifestSha256")))throw fail("Registered ancestor manifest changed.");
            WorldBuilderAdaptiveProjectLifecycle.VerifiedProject parent=WorldBuilderAdaptiveProjectLifecycle.verifyProjectDirectory(root,false);
            if(!id.equals(parent.projectId) || !parent.snapshot.get("sourceFingerprintSha256").equals(origin.get("sourceFingerprintSha256")))
                throw fail("The retained ancestor source snapshot differs from the content origin.");
            Map<String,Object> authority=object(origin.get("targetAuthority"));
            if(!"world-builder-content-refresh-authority".equals(authority.get("manifestType")) || !Long.valueOf(1).equals(authority.get("schemaVersion"))
                || !id.equals(authority.get("projectId")) || !Objects.equals(origin.get("projectFingerprintSha256"),authority.get("projectFingerprintSha256"))
                || !Objects.equals(origin.get("workingFingerprintSha256"),authority.get("savedMapFingerprintSha256")))
                throw fail("Content-origin parent authority is inconsistent.");
            List<Object> refs=activeReferences(parent);
            if(!refs.equals(authority.get("history")))throw fail("Retained ancestor history differs from its captured content-origin authority.");
            Map<String,Object> link=new LinkedHashMap<>();link.put("projectId",child.projectId);
            link.put("sourceFingerprintSha256",child.snapshot.get("sourceFingerprintSha256"));link.put("originSha256",declared.get("sha256"));lineage.add(link);
            Baseline found=null;
            for(Object raw:refs){Map<String,Object> ref=object(raw);Map<String,Object> plan=WorldBuilderRuntimeReverification.readPlan(parent,string(ref,"transactionId"));
                if(plan.containsKey(WorldBuilderRuntimeReverification.FIELD))continue;
                boolean proof=false,server=false;
                for(Object item:list(plan.get("actions"))){Map<String,Object> action=object(item);
                    if(!string(action,"role").startsWith(WorldBuilderTargetMapIntegration.ROLE))continue;
                    proof|=WorldBuilderTargetMapIntegration.INSTALLED.equals(action.get("destinationRelativePath"));server|="server/core.jar".equals(action.get("destinationRelativePath"));}
                if(!proof || !server)continue;
                Map<String,Path> outputs=restore(parent,ref,plan);
                Map<String,Object> binding=new LinkedHashMap<>();binding.put("projectId",parent.projectId);
                binding.put("sourceFingerprintSha256",parent.snapshot.get("sourceFingerprintSha256"));binding.put("lineage",new ArrayList<>(lineage));
                found=new Baseline(parent,ref,binding,outputs);
            }
            if(found!=null)return found;
            if(!hasOrigin(parent)) {
                Baseline captured=WorldBuilderSnapshotRuntimeBaseline.resolve(parent);
                Map<String,Object> binding=new LinkedHashMap<>();binding.put("projectId",parent.projectId);
                binding.put("sourceFingerprintSha256",parent.snapshot.get("sourceFingerprintSha256"));binding.put("lineage",new ArrayList<>(lineage));
                return new Baseline(parent,null,binding,captured.outputs,captured.snapshot);
            }
            child=parent;
        }
        throw fail("Content-origin runtime lineage exceeds sixteen retained projects.");
    }

    static Baseline require(WorldBuilderAdaptiveProjectLifecycle.VerifiedProject project,Map<String,Object> evidence)
        throws IOException,WorldBuilderContractException {
        Baseline value=resolve(project);
        if(!Objects.equals(value.binding,evidence.get(FIELD)) || !Objects.equals(value.reference,evidence.get("baseline")) || !Objects.equals(value.snapshot,evidence.get(WorldBuilderSnapshotRuntimeBaseline.FIELD)))
            throw fail("The inherited runtime baseline differs from its immutable preview binding.");
        return value;
    }
    static void validate(Object raw)throws WorldBuilderContractException{
        Map<String,Object> value=object(raw);WorldBuilderBoundedInventory.exactKeys(value,FIELD,"projectId","sourceFingerprintSha256","lineage");
        uuid(string(value,"projectId"));hash(string(value,"sourceFingerprintSha256"));List<?> links=list(value.get("lineage"));
        if(links.isEmpty()||links.size()>16)throw fail("Invalid inherited runtime lineage bound.");
        Set<String> ids=new HashSet<>();
        for(Object item:links){Map<String,Object> link=object(item);WorldBuilderBoundedInventory.exactKeys(link,FIELD,"projectId","sourceFingerprintSha256","originSha256");
            String id=string(link,"projectId");uuid(id);if(!ids.add(id))throw fail("Repeated inherited runtime project.");hash(string(link,"sourceFingerprintSha256"));hash(string(link,"originSha256"));}
        if(ids.contains(string(value,"projectId")))throw fail("Inherited runtime lineage is cyclic.");
    }
    private static boolean hasOrigin(WorldBuilderAdaptiveProjectLifecycle.VerifiedProject project) throws WorldBuilderContractException {
        for(Object raw:list(project.snapshot.get("originalFiles"))) if(ORIGIN.equals(object(raw).get("relativePath")))return true;
        return false;
    }
    private static List<Object> activeReferences(WorldBuilderAdaptiveProjectLifecycle.VerifiedProject owner)throws IOException,WorldBuilderContractException{
        List<WorldBuilderAdaptiveReceipt.State> receipts=WorldBuilderAdaptiveReceipt.readAll(owner.projectRoot);Set<String> reverted=new HashSet<>();List<Object> result=new ArrayList<>();
        for(WorldBuilderAdaptiveReceipt.State receipt:receipts){
            if("pending".equals(receipt.status())||"recovery-required".equals(receipt.status()))throw fail("Resolve the ancestor's interrupted transaction before re-verification.");
            if("undo".equals(receipt.transactionType())&&"reverted".equals(receipt.status()))reverted.add(receipt.revertsTransactionId());}
        for(WorldBuilderAdaptiveReceipt.State receipt:receipts)if("import".equals(receipt.transactionType())&&"successful".equals(receipt.status())&&!reverted.contains(receipt.transactionId()))
            result.add(WorldBuilderRuntimeReverification.reference(owner,receipt));
        if(result.size()>4096)throw fail("Ancestor transaction history exceeds its bound.");return result;
    }
    private static Map<String,Path> restore(WorldBuilderAdaptiveProjectLifecycle.VerifiedProject owner,Map<String,Object> ref,Map<String,Object> plan)
        throws IOException,WorldBuilderContractException{
        String tx=string(ref,"transactionId");WorldBuilderAdaptiveReceipt.State receipt=WorldBuilderAdaptiveReceipt.read(owner.projectRoot.resolve("receipts/"+tx+".json"));
        WorldBuilderAdaptiveUndo.findExport(owner,receipt.exportFingerprint());
        Map<String,Map<String,Object>> files=new HashMap<>();for(Object item:list(receipt.document.get("files"))){Map<String,Object> row=object(item);if(files.put(string(row,"relativePath"),row)!=null)throw fail("Ancestor receipt repeats a file.");}
        Map<String,Path> result=new TreeMap<>();
        for(Object item:list(plan.get("actions"))){Map<String,Object> action=object(item);if(!string(action,"role").startsWith(WorldBuilderTargetMapIntegration.ROLE))continue;
            WorldBuilderAdaptiveMutationProfile.Action restored=WorldBuilderTargetMapIntegration.restoreAction(action,owner.projectRoot,tx);
            Map<String,Object> row=files.get(restored.destinationRelativePath);
            if(row==null||!restored.role.equals(row.get("role"))||!restored.before.toJson().equals(row.get("before"))||!restored.after.toJson().equals(row.get("after"))
                ||!restored.backupRelativePath.equals(row.get("backupRelativePath"))||!Boolean.TRUE.equals(row.get("afterVerified")))throw fail("Ancestor integration receipt differs from retained action content.");
            if(restored.before.present){Path backup=WorldBuilderAdaptiveExporter.requireFile(owner.projectRoot,restored.backupRelativePath,"ancestor integration backup");
                if(Files.size(backup)!=restored.before.size||!WorldBuilderHashes.sha256(backup).equals(restored.before.sha256))throw fail("Ancestor integration before-backup changed.");}
            Path content=WorldBuilderAdaptiveExporter.requireFile(owner.projectRoot,"backups/"+tx+"/content/targeted/"+restored.role+".bin","ancestor integration output");
            result.put(restored.destinationRelativePath,content);
        }
        return result;
    }
    private static void uuid(String id)throws WorldBuilderContractException{try{if(!UUID.fromString(id).toString().equals(id))throw new IllegalArgumentException();}catch(IllegalArgumentException bad){throw fail("Invalid ancestor project identity.");}}
    private static void hash(String hash)throws WorldBuilderContractException{if(!WorldBuilderBoundedInventory.isHash(hash))throw fail("Invalid inherited runtime evidence hash.");}
    private static Map<String,Object> read(Path path)throws IOException,WorldBuilderContractException{try{return WorldBuilderJsonDocuments.readObject(path);}catch(WorldBuilderDiscoveryException bad){throw fail("Malformed content-origin evidence.");}}
    private static Map<String,Object> object(Object raw)throws WorldBuilderContractException{return WorldBuilderAdaptiveExporter.object(raw,FIELD);}
    private static List<?> list(Object raw)throws WorldBuilderContractException{return WorldBuilderAdaptiveExporter.array(raw,FIELD);}
    private static String string(Map<String,Object> value,String key)throws WorldBuilderContractException{return WorldBuilderAdaptiveExporter.string(value,key);}
    private static WorldBuilderContractException fail(String message){return WorldBuilderRuntimeReverification.refusal(message);}
}
