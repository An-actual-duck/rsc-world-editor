package com.openrsc.worldbuilder;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Captured installed integration authority, independent of local upgrade receipts. */
final class WorldBuilderSnapshotRuntimeBaseline {
    static final String FIELD = "snapshotBaseline";
    static WorldBuilderContentRuntimeBaseline.Baseline resolve(
        WorldBuilderAdaptiveProjectLifecycle.VerifiedProject project) throws IOException, WorldBuilderContractException {
        Map<String,Map<String,Object>> rows = new TreeMap<>();
        for (String family : Arrays.asList("originalFiles", "definitionRuntimeFiles"))
            for (Object raw : list(project.snapshot.get(family))) {
                Map<String,Object> row=object(raw); String path=string(row,"relativePath");
                if (!path.startsWith("source/original/")) continue;
                WorldBuilderPortablePath.require(path,FIELD);
                if (rows.put(path,row)!=null) throw fail("Captured runtime authority repeats a snapshot path: "+path);
            }
        String proofPath="source/original/"+WorldBuilderTargetMapIntegration.INSTALLED;
        Path proofFile=verified(project,rows,proofPath);
        if (!"installed-map-integration-proof".equals(rows.get(proofPath).get("role")))
            throw fail("The installed integration proof lacks its captured evidence role.");
        Map<String,Object> proof;
        try { proof=WorldBuilderJsonDocuments.readObject(proofFile); }
        catch (WorldBuilderDiscoveryException invalid) { throw fail("Captured integration proof is malformed."); }
        if (!"world-builder-installed-target-map-integration".equals(proof.get("manifestType")) || !Long.valueOf(1).equals(proof.get("schemaVersion")))
            throw fail("Captured integration proof is unsupported.");
        Map<String,Path> outputs=new TreeMap<>(); outputs.put(WorldBuilderTargetMapIntegration.INSTALLED,proofFile);
        List<Object> archives=new ArrayList<>(); Set<String> paths=new HashSet<>();
        for (String family : Arrays.asList("sources","archives")) {
            List<?> entries=list(proof.get(family));
            if (entries.isEmpty() || entries.size()>16000) throw fail("Captured integration inventory is empty or unbounded.");
            for (Object raw:entries) {
                Map<String,Object> entry=object(raw); String path=string(entry,"relativePath"); WorldBuilderPortablePath.require(path,FIELD);
                if (!paths.add(path)) throw fail("Captured integration proof repeats a source or archive.");
                String captured="source/original/"+path; Path file=verified(project,rows,captured); Map<String,Object> row=rows.get(captured);
                if (!entry.get("sha256").equals(row.get("sha256"))) throw fail("Captured integration proof differs from its snapshot: "+path);
                String role=string(row,"role");
                // Discovery keeps the first role when floor/definition/producer
                // evidence already captured this exact source path. The proof's
                // path/hash and verified snapshot row authenticate it together.
                if (role.isEmpty()) throw fail("Captured integration input lacks its evidence role: "+path);
                if ("archives".equals(family)) {
                    if (!Arrays.asList("installed-map-integration-archive","installed-floor-server-runtime","installed-floor-client-runtime").contains(role))
                        throw fail("Captured integration archive lacks its evidence role: "+path);
                    Map<String,Object> binding=new LinkedHashMap<>(); binding.put("relativePath",captured);
                    binding.put("size",row.get("size")); binding.put("sha256",row.get("sha256")); archives.add(binding);
                }
                outputs.put(path,file);
            }
        }
        // Historical compilation hashes remain authenticated by the captured
        // proof and are checked against live sources/dependencies by reverify.
        // Only a captured installed build file can authorize its guard removal.
        String build="source/original/server/build.xml";
        if (rows.containsKey(build)) {
            if (!"installed-map-integration-build".equals(rows.get(build).get("role"))) throw fail("Captured installed build input lacks its evidence role.");
            outputs.put("server/build.xml",verified(project,rows,build));
        }
        Map<String,Object> binding=new LinkedHashMap<>(); binding.put("projectId",project.projectId);
        binding.put("sourceFingerprintSha256",project.snapshot.get("sourceFingerprintSha256"));
        binding.put("proofRelativePath",proofPath); binding.put("proofSha256",rows.get(proofPath).get("sha256")); binding.put("archives",archives);
        validate(binding);
        return new WorldBuilderContentRuntimeBaseline.Baseline(project,null,null,outputs,binding);
    }
    static void require(WorldBuilderAdaptiveProjectLifecycle.VerifiedProject owner,Object binding)
        throws IOException,WorldBuilderContractException {
        if (!resolve(owner).snapshot.equals(binding)) throw fail("Captured runtime baseline differs from its immutable preview binding.");
    }
    static Map<String,Object> identity(WorldBuilderAdaptiveProjectLifecycle.VerifiedProject project) {
        Map<String,Object> value=new LinkedHashMap<>(); value.put("projectId",project.projectId);
        value.put("sourceFingerprintSha256",project.snapshot.get("sourceFingerprintSha256")); return value;
    }
    static void validate(Object raw)throws WorldBuilderContractException {
        Map<String,Object> value=object(raw); WorldBuilderBoundedInventory.exactKeys(value,FIELD,"projectId","sourceFingerprintSha256","proofRelativePath","proofSha256","archives");
        String id=string(value,"projectId"); try { if (!UUID.fromString(id).toString().equals(id)) throw new IllegalArgumentException(); }
        catch (IllegalArgumentException invalid) { throw fail("Invalid captured baseline project identity."); }
        hash(string(value,"sourceFingerprintSha256")); hash(string(value,"proofSha256"));
        if (!("source/original/"+WorldBuilderTargetMapIntegration.INSTALLED).equals(value.get("proofRelativePath"))) throw fail("Invalid captured baseline proof path.");
        List<?> archives=list(value.get("archives")); if (archives.isEmpty()||archives.size()>512) throw fail("Invalid captured archive bound.");
        Set<String> paths=new HashSet<>();
        for(Object rawArchive:archives) { Map<String,Object> archive=object(rawArchive); WorldBuilderBoundedInventory.exactKeys(archive,FIELD,"relativePath","size","sha256");
            String path=string(archive,"relativePath"); WorldBuilderPortablePath.require(path,FIELD);
            if (!path.startsWith("source/original/")||!path.endsWith(".jar")||!paths.add(path)) throw fail("Invalid captured archive path.");
            long size=WorldBuilderAdaptiveExporter.integer(archive,"size"); if(size<0||size>256L*1024*1024)throw fail("Invalid captured archive size.");hash(string(archive,"sha256")); }
    }
    private static Path verified(WorldBuilderAdaptiveProjectLifecycle.VerifiedProject project,Map<String,Map<String,Object>> rows,String path)
        throws IOException,WorldBuilderContractException {
        Map<String,Object> row=rows.get(path);
        if(row==null||!Boolean.TRUE.equals(row.get("present")))throw fail("Required captured integration evidence is missing: "+path);
        Path file=WorldBuilderAdaptiveExporter.requireFile(project.projectRoot,path,"captured integration evidence");
        if(Files.size(file)!=WorldBuilderAdaptiveExporter.integer(row,"size")||!WorldBuilderHashes.sha256(file).equals(row.get("sha256")))throw fail("Captured integration evidence changed: "+path);
        return file;
    }
    private static void hash(String value)throws WorldBuilderContractException{if(!WorldBuilderBoundedInventory.isHash(value))throw fail("Invalid captured evidence hash.");}
    private static Map<String,Object> object(Object raw)throws WorldBuilderContractException{return WorldBuilderAdaptiveExporter.object(raw,FIELD);}
    private static List<?> list(Object raw)throws WorldBuilderContractException{return WorldBuilderAdaptiveExporter.array(raw,FIELD);}
    private static String string(Map<String,Object> value,String key)throws WorldBuilderContractException{return WorldBuilderAdaptiveExporter.string(value,key);}
    private static WorldBuilderContractException fail(String message){return WorldBuilderRuntimeReverification.refusal(message);}
}
