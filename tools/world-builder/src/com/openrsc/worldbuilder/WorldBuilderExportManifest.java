package com.openrsc.worldbuilder;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Strict reader for the versioned export-manifest-v1 release contract. */
final class WorldBuilderExportManifest {
	private static final Map<String, String> CANONICAL_FILES = canonicalFiles();

	final String builderVersion, sourceCommit, layoutAdapter;
	final String sourceFingerprint, contentFingerprint;
	final List<FileRecord> files;
	final int changedFileCount;
	final boolean terrainChanged, sceneryChanged, npcChanged;

	private WorldBuilderExportManifest(String builderVersion, String sourceCommit,
		String layoutAdapter, String sourceFingerprint, String contentFingerprint,
		List<FileRecord> files, int changedFileCount, boolean terrainChanged,
		boolean sceneryChanged, boolean npcChanged) {
		this.builderVersion=builderVersion;this.sourceCommit=sourceCommit;this.layoutAdapter=layoutAdapter;
		this.sourceFingerprint=sourceFingerprint;this.contentFingerprint=contentFingerprint;
		this.files=files;this.changedFileCount=changedFileCount;this.terrainChanged=terrainChanged;
		this.sceneryChanged=sceneryChanged;this.npcChanged=npcChanged;
	}

	static WorldBuilderExportManifest read(Path path)
		throws IOException, WorldBuilderDiscoveryException {
		Map<String,Object> root=WorldBuilderJsonDocuments.readObject(path);
		exactKeys(root,"schemaVersion","manifestType","builderVersion","sourceCommit",
			"layoutAdapter","sourceFingerprintSha256","contentFingerprintSha256","files","changeSummary");
		if(integer(root,"schemaVersion")!=1||!"world-builder-export".equals(string(root,"manifestType")))
			throw new WorldBuilderDiscoveryException("Export manifest identity is invalid.");
		String version=string(root,"builderVersion"),commit=string(root,"sourceCommit"),layout=string(root,"layoutAdapter");
		if(version.isEmpty()||version.length()>64||!commit.matches("[0-9a-f]{40}")||layout.isEmpty())
			throw new WorldBuilderDiscoveryException("Export manifest provenance is invalid.");
		String source=hash(root,"sourceFingerprintSha256"),content=hash(root,"contentFingerprintSha256");
		Object rawFiles=root.get("files");if(!(rawFiles instanceof List)||((List<?>)rawFiles).size()!=5)
			throw new WorldBuilderDiscoveryException("Export manifest must contain five files.");
		List<FileRecord> files=new ArrayList<FileRecord>();Set<String> logicalNames=new HashSet<String>(),paths=new HashSet<String>();
		for(Object item:(List<?>)rawFiles){if(!(item instanceof Map))throw new WorldBuilderDiscoveryException("Export file record is invalid.");
			@SuppressWarnings("unchecked")Map<String,Object> record=(Map<String,Object>)item;exactKeys(record,"logicalName","bundlePath","size","sha256","sourcePresent","sourceSha256","changed");
			String logical=string(record,"logicalName"),bundle=string(record,"bundlePath"),sha=hash(record,"sha256");long size=integer(record,"size");
			boolean sourcePresent=bool(record,"sourcePresent"),changed=bool(record,"changed");String sourceSha=string(record,"sourceSha256");
			if(sourcePresent?!sourceSha.matches("[0-9a-f]{64}"):!sourceSha.isEmpty())throw new WorldBuilderDiscoveryException("Export source-file state is invalid.");
			Path relative=Paths.get(bundle).normalize();if(logical.isEmpty()||size<0||bundle.indexOf('\\')>=0||relative.isAbsolute()||relative.startsWith("..")
				||!bundle.equals(CANONICAL_FILES.get(logical))||!logicalNames.add(logical)||!paths.add(bundle))throw new WorldBuilderDiscoveryException("Export file record is unsafe, noncanonical, or duplicated.");
			if(sourcePresent&&changed==sourceSha.equals(sha))throw new WorldBuilderDiscoveryException("Export file change state is inconsistent.");
			files.add(new FileRecord(logical,bundle,size,sha,sourcePresent,sourceSha,changed));}
		if(!logicalNames.equals(CANONICAL_FILES.keySet()))throw new WorldBuilderDiscoveryException("Export manifest file inventory is incomplete.");
		@SuppressWarnings("unchecked") Map<String,Object> changes = root.get("changeSummary") instanceof Map
			? (Map<String,Object>)root.get("changeSummary") : null;
		if(changes==null)throw new WorldBuilderDiscoveryException("Export change summary is invalid.");
		exactKeys(changes,"changedFileCount","terrainChanged","sceneryChanged","npcChanged");int changed=(int)integer(changes,"changedFileCount");
		if(changed<0||changed>5)throw new WorldBuilderDiscoveryException("Export changed-file count is invalid.");
		int actualChanged=0;boolean terrain=false,scenery=false,npc=false;for(FileRecord file:files){if(!file.changed)continue;actualChanged++;
			if("terrain".equals(file.logicalName))terrain=true;else if(file.logicalName.startsWith("scenery"))scenery=true;else npc=true;}
		boolean listedTerrain=bool(changes,"terrainChanged"),listedScenery=bool(changes,"sceneryChanged"),listedNpc=bool(changes,"npcChanged");
		if(changed!=actualChanged||terrain!=listedTerrain||scenery!=listedScenery||npc!=listedNpc)
			throw new WorldBuilderDiscoveryException("Export change summary is inconsistent.");
		return new WorldBuilderExportManifest(version,commit,layout,source,content,files,changed,terrain,scenery,npc);
	}

	private static void exactKeys(Map<String,Object> object,String... keys)throws WorldBuilderDiscoveryException{
		Set<String> expected=new HashSet<String>(Arrays.asList(keys));if(object.size()!=expected.size()||!object.keySet().equals(expected))
			throw new WorldBuilderDiscoveryException("Export manifest contains missing or unexpected fields.");}
	private static String string(Map<String,Object> object,String key)throws WorldBuilderDiscoveryException{Object value=object.get(key);
		if(!(value instanceof String))throw new WorldBuilderDiscoveryException("Export manifest field is not a string: "+key);return(String)value;}
	private static String hash(Map<String,Object> object,String key)throws WorldBuilderDiscoveryException{String value=string(object,key);
		if(!value.matches("[0-9a-f]{64}"))throw new WorldBuilderDiscoveryException("Export manifest hash is invalid: "+key);return value;}
	private static long integer(Map<String,Object> object,String key)throws WorldBuilderDiscoveryException{Object value=object.get(key);
		if(!(value instanceof Long))throw new WorldBuilderDiscoveryException("Export manifest field is not an integer: "+key);return((Long)value).longValue();}
	private static boolean bool(Map<String,Object> object,String key)throws WorldBuilderDiscoveryException{Object value=object.get(key);
		if(!(value instanceof Boolean))throw new WorldBuilderDiscoveryException("Export manifest field is not boolean: "+key);return((Boolean)value).booleanValue();}
	private static Map<String,String> canonicalFiles(){Map<String,String> files=new LinkedHashMap<String,String>();
		files.put("terrain","authored/Custom_Landscape.orsc");files.put("sceneryLocs","authored/MyWorldSceneryLocs.json");
		files.put("sceneryRemovals","authored/MyWorldSceneryRemovals.json");files.put("npcLocs","authored/MyWorldNpcLocs.json");
		files.put("npcRemovals","authored/MyWorldNpcRemovals.json");return java.util.Collections.unmodifiableMap(files);}

	static final class FileRecord{final String logicalName,bundlePath,sha256,sourceSha256;final long size;final boolean sourcePresent,changed;
		FileRecord(String logical,String bundle,long size,String sha,boolean sourcePresent,String sourceSha,boolean changed){
			logicalName=logical;bundlePath=bundle;this.size=size;sha256=sha;this.sourcePresent=sourcePresent;sourceSha256=sourceSha;this.changed=changed;}}
}
