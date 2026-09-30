package com.openrsc.worldbuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import static com.openrsc.worldbuilder.WorldBuilderNpcVisualJava.*;

/** Reads a deliberately bounded source format; never compiles or executes target Java. */
final class WorldBuilderNpcVisualSourceAdapter {
	private WorldBuilderNpcVisualSourceAdapter() { }
	static List<Map<String,Object>> discover(WorldBuilderReadOnlyTarget target,
		WorldBuilderPackedSourceLayout layout, List<WorldBuilderReadOnlyTarget.FileState> evidence)
		throws WorldBuilderContractException {
		return discover(target, layout, evidence, Collections.<String>emptySet());
	}
	static List<Map<String,Object>> discover(WorldBuilderReadOnlyTarget target,
		WorldBuilderPackedSourceLayout layout, List<WorldBuilderReadOnlyTarget.FileState> evidence,
		Set<String> explicitlyBound) throws WorldBuilderContractException {
		try {
			List<Definition> definitions = definitions(target, layout);
			boolean allExplicit=true;for(Definition definition:definitions)if(!explicitlyBound.contains(definition.path+"#"+definition.index))allExplicit=false;
			if(allExplicit)return new ArrayList<Map<String,Object>>();
			List<Source> sources = sources(target);
			List<Map<String,Object>> result = new ArrayList<>();
			for (Source table : sources) {
				if(!table.isEnum)continue;
				List<Entry> entries;
				try { entries = tableEntries(table); } catch (IllegalArgumentException malformed) {
					if(hasCaller(sources,table.name))throw problem(table.path,"Unsupported source table metadata: "+malformed.getMessage());
					continue;
				}
				if (entries.isEmpty()) {
					if(table.tokens.contains("enum")&&hasCaller(sources,table.name))throw problem(table.path,"Unsupported declarative source table shape.");
					continue;
				}
				List<Definition> selected = new ArrayList<>();
				for (Definition definition : definitions) for (Entry entry : entries)
					if (definition.id == entry.id && definition.name.equals(entry.name)
						&& !explicitlyBound.contains(definition.path + "#" + definition.index)) selected.add(definition);
				if (selected.isEmpty()) continue;
				Caller caller = caller(sources, table);
				// Enum tables alone do not establish a visual association.
				if (caller == null) continue;
				try {
					Map<String,String> binding = caller.binding;
					String root = string(binding.get("root"));
					String provenance = root + "/provenance.json";
					if (!target.exists(provenance)) throw new IllegalArgumentException("Missing provenance.json beside source-selected images");
					Map<String,Object> metadata = WorldBuilderJsonDocuments.readObject(target.requiredFile(provenance));
					Object raw = metadata.get("entries");
					if (!(raw instanceof List) || ((List<?>)raw).size() > 65536) throw new IllegalArgumentException("Invalid provenance entries");
					Source handler = find(sources, binding.get("EntityHandler"));
					Source loader = loader(sources);
					if (!WorldBuilderNpcVisualJava.text(caller.source.tokens).contains(loader.name + " " + binding.get("externalAssetLoader") + " = new " + loader.name + " (")) throw new IllegalArgumentException("Source loader receiver is not bound to the verified class");
					int alpha = verifyLoader(loader);
					Map<String,String> activation = match(method(handler.methods, binding.get("activate")).body,
						"NPCDef $npc = getNpcDef($row.$id); if ($npc == null || !$row.$name.equals($npc.getName())) return; $npc.sprites[$slot] = $getter($row);");
					if (activation == null || !activation.get("getter").equals(binding.get("animationGetter"))) throw new IllegalArgumentException("Unsupported NPC activation semantics");
					Map<String,String> animation = animation(handler, table.name, binding);
					Method getter=method(handler.methods,binding.get("animationGetter"));
					Map<String,String> getterBody=match(getter.body,"Integer $id = $map.get($row); if ($id == null) throw new IllegalStateException($message + $row); return $id;");
					if(getterBody==null||!animation.get("map").equals(getterBody.get("map"))||!sameBindings(getterBody,match(getter.params,table.name+" $row")))throw new IllegalArgumentException("Unproven animation registry lookup");

					int beats = Integer.parseInt(binding.get("beats"));
					if (beats != 3) throw new IllegalArgumentException("Only three-beat directional source layouts are supported");
					Map<String,String> widths = match(method(table.methods,binding.get("columnWidths")).body,"return $columns.clone();");
					if (widths == null) throw new IllegalArgumentException("Unsupported column layout method");
					Map<String,String> combat = match(method(table.methods,binding.get("withCombatFrames")).body,WorldBuilderNpcVisualSourceTemplates.COMBAT);
					if (combat == null) throw new IllegalArgumentException("Unsupported combat frame remapping");
					for (Definition definition : selected) {
						Entry entry = entry(entries,definition.id,definition.name);
						if (!entry.fields.get(activation.get("id")).equals(Long.valueOf(entry.id))
							|| !entry.fields.get(activation.get("name")).equals(entry.name)) throw new IllegalArgumentException("Activation identity fields differ from table");
						String asset = (String)entry.fields.get(binding.get("assetName"));
						List<Long> columns = integers(entry.fields.get(widths.get("columns")));
						Map<?,?> proof = provenance((List<?>)raw,entry.id,asset);
						if (!columns.equals(integers(proof.get("columns")))) throw new IllegalArgumentException("Provenance columns differ from source table");
						long height = number(proof.get("height"));
						if (height < 3 || height > 6144 || height % beats != 0) throw new IllegalArgumentException("Unsupported sheet height");
						String image = root + "/" + asset + ".png";
						String imageHash = WorldBuilderHashes.sha256(target.requiredFile(image));
						if (!imageHash.equals(proof.get("sha256"))) throw new IllegalArgumentException("Image hash differs from provenance");
						boolean hasCombat = bool(evaluateMethod(table,entry,animation.get("combat")));
						int required = 15 + (hasCombat ? 3 : 0);
						List<Map<String,Object>> frames = new ArrayList<>(); int x = 0;
						for (Long width : columns) {
							if (width < 1 || width > 2048) throw new IllegalArgumentException("Column width exceeds neutral frame bound");
							for (int beat=0;beat<beats;beat++) frames.add(frame(image,imageHash,x,beat*(height/beats),width,height/beats));
							x = Math.addExact(x,Math.toIntExact(width));
						}
						if (entry.symbol.equals(combat.get("reuseConstant")) && frames.size()==Integer.parseInt(combat.get("sourceCount"))) {
							int count=Integer.parseInt(combat.get("targetCount")),offset=Integer.parseInt(combat.get("reuseOffset"));
							if(count>27||count<frames.size()||offset<1)throw new IllegalArgumentException("Unsupported reuse bounds");
							for(int i=frames.size();i<count;i++){int from=i-offset;if(from<0||from>=frames.size())throw new IllegalArgumentException("Invalid combat reuse");frames.add(new LinkedHashMap<>(frames.get(from)));}
						}
						if(frames.size()<required)throw new IllegalArgumentException("Source lacks required movement/combat frames");
						Map<String,Object> row=new LinkedHashMap<>();row.put("npcId",Long.valueOf(entry.id));row.put("definitionPath",definition.path);row.put("definitionIndex",Long.valueOf(definition.index));row.put("definitionSha256",WorldBuilderHashes.sha256(target.requiredFile(definition.path)));row.put("spriteSlot",Long.valueOf(Integer.parseInt(activation.get("slot"))+1));
						row.put("frames",new ArrayList<>(frames.subList(0,required)));row.put("alphaThreshold",Long.valueOf(alpha));
						camera(handler,table,entry,row);
						row.put("charColour",Long.valueOf(animation.get("colour")));row.put("blueMask",Long.valueOf(0));row.put("genderModel",Long.valueOf(animation.get("gender")));row.put("hasCombatFrames",hasCombat);row.put("hasSpecialCombatFrames",false);result.add(row);
						evidence.add(target.requiredState("npc-visual-image",image));evidence.add(target.requiredState("npc-visual-source",definition.path));
					}
					for(Source source:Arrays.asList(table,caller.source,handler,loader))evidence.add(target.requiredState("npc-visual-source",source.path));
					evidence.add(target.requiredState("npc-visual-source",provenance));
				} catch (Exception failure) {
					throw problem(table.path,"Source visual association is unresolved: " + failure.getMessage());
				}
			}
			return result;
		} catch (WorldBuilderContractException refusal) { throw refusal; }
		catch (Exception failure) { throw problem("npc-visual-source", "Cannot inspect bounded source metadata: " + failure.getMessage()); }
	}
	private static void camera(Source handler,Source table,Entry entry,Map<String,Object> row) {
		String width=null,height=null;
		for(Method method:handler.methods)for(int i=0;i<method.body.size();i++)if(method.body.get(i).equals("for")&&i+1<method.body.size()&&method.body.get(i+1).equals("(")){
			int close=end(method.body,i+1);if(close+1>=method.body.size()||!method.body.get(close+1).equals("{"))continue;
			Map<String,String> loop=match(method.body.subList(i,close+1),"for ($table $row : $table.values())");if(loop==null||!table.name.equals(loop.get("table")))continue;
			int finish=end(method.body,close+1);
			for(int n=close+2;n+2<finish;n++)if(method.body.get(n).equals("new")&&method.body.get(n+1).equals("NPCDef")&&method.body.get(n+2).equals("(")){
				int end=end(method.body,n+2);List<List<String>> args=arguments(method.body.subList(n+3,end));if(args.size()!=19)throw new IllegalArgumentException("Unsupported NPC constructor shape");
				Map<String,String> w=match(args.get(13),"$row.$method()"),h=match(args.get(14),"$row.$method()"),id=match(args.get(18),"$row.$field");
				if(w==null||h==null||id==null||!loop.get("row").equals(w.get("row"))||!loop.get("row").equals(h.get("row"))||!loop.get("row").equals(id.get("row"))||!Long.valueOf(entry.id).equals(entry.fields.get(id.get("field"))))throw new IllegalArgumentException("Unproven source camera association");
				if(width!=null)throw new IllegalArgumentException("Ambiguous source camera constructors");width=w.get("method");height=h.get("method");
			}
		}
		if(width==null)throw new IllegalArgumentException("Camera constructor cannot be proven");
		row.put("cameraWidth",evaluateMethod(table,entry,width));row.put("cameraHeight",evaluateMethod(table,entry,height));
	}
	private static List<List<String>> arguments(List<String> tokens){
		List<List<String>> result=new ArrayList<>();int start=0;
		for(int i=0;i<tokens.size();i++){String token=tokens.get(i);if(token.equals("(")||token.equals("[")||token.equals("{"))i=end(tokens,i);else if(token.equals(",")){result.add(tokens.subList(start,i));start=i+1;}}
		result.add(tokens.subList(start,tokens.size()));return result;
	}

	private static Map<String,String> animation(Source handler,String table,Map<String,String> caller) {
		for(Method m:handler.methods)for(int i=0;i<m.body.size();i++)if("for".equals(m.body.get(i))) {
			int open=i+1;if(open>=m.body.size()||!"(".equals(m.body.get(open)))continue;int close=end(m.body,open);if(close+1>=m.body.size()||!"{".equals(m.body.get(close+1)))continue;int finish=end(m.body,close+1);
			Map<String,String> map=match(m.body.subList(i,finish+1),"for ($table $row : $table.values()) { $map.put($row, animations.size()); animations.add(new AnimationDef($row.$name(), \"npc\", $colour, $gender, $row.$combat(), false, 0)); }");
			if(map!=null&&table.equals(map.get("table"))&&caller.get("animationName").equals(map.get("name")))return map;
		}
		throw new IllegalArgumentException("Missing source animation registration");
	}
	private static Object evaluateMethod(Source source,Entry entry,String name) {
		Method m=method(source.methods,name);
		if(!m.params.isEmpty()||m.body.size()<3||!m.body.get(0).equals("return")||!m.body.get(m.body.size()-1).equals(";"))throw new IllegalArgumentException("Unsupported pure method "+name);
		return evaluate(m.body.subList(1,m.body.size()-1),entry.symbol,entry.fields,source.methods,0);
	}
	private static Map<String,Object> frame(String path,String hash,long x,long y,long width,long height) {
		Map<String,Object> f=new LinkedHashMap<>();f.put("imagePath",path);f.put("imageSha256",hash);f.put("x",x);f.put("y",y);f.put("width",width);f.put("height",height);f.put("offsetX",0L);f.put("offsetY",0L);f.put("boundWidth",width);f.put("boundHeight",height);return f;
	}
	private static Map<?,?> provenance(List<?> rows,int id,String key) {
		Map<?,?> found=null;for(Object raw:rows)if(raw instanceof Map){Map<?,?> row=(Map<?,?>)raw;if(Long.valueOf(id).equals(row.get("id"))&&key.equals(row.get("key"))){if(found!=null)throw new IllegalArgumentException("Ambiguous provenance identity");found=row;}}
		if(found==null)throw new IllegalArgumentException("No matching provenance identity");return found;
	}
	private static List<Long> integers(Object raw){if(!(raw instanceof List))throw new IllegalArgumentException("Expected integer columns");List<Long> result=new ArrayList<>();for(Object n:(List<?>)raw)result.add(number(n));return result;}
	private static Entry entry(List<Entry> entries,int id,String name){for(Entry e:entries)if(e.id==id&&e.name.equals(name))return e;throw new IllegalArgumentException("Ambiguous table");}
	private static Source find(List<Source> sources,String name){Source result=null;for(Source s:sources)if(s.name.equals(name)){if(result!=null)throw new IllegalArgumentException("Ambiguous source class "+name);result=s;}if(result==null)throw new IllegalArgumentException("Missing source class "+name);return result;}
	private static Source loader(List<Source> sources){Source found=null;for(Source s:sources)for(Method m:s.methods)if(m.name.equals("loadExternalNpcDirectionSheet")&&m.params.contains("[")){if(found!=null&&found!=s)throw new IllegalArgumentException("Ambiguous direction-sheet loader");found=s;}if(found==null)throw new IllegalArgumentException("Missing direction-sheet loader");return found;}
	private static int verifyLoader(Source s) {
		Map<String,String> selected=null;boolean wrapper=false;
		for(Method m:s.methods)if(m.name.equals("loadExternalNpcDirectionSheet")){
			Map<String,String> b=match(m.body,WorldBuilderNpcVisualSourceTemplates.LOADER);
			Map<String,String> p=match(m.params,"File $sourceFile, String $spriteName, int[] $directionColumnWidths, int $framesPerDirection, int $transparentGuideRgb");
			if(b!=null&&sameBindings(b,p))selected=b;
			Map<String,String> w=match(m.body,"return loadExternalNpcDirectionSheet($sourceFile, $spriteName, $directionColumnWidths, $framesPerDirection, -1);");
			if(w!=null&&sameBindings(w,match(m.params,"File $sourceFile, String $spriteName, int[] $directionColumnWidths, int $framesPerDirection")))wrapper=true;
		}
		Method norm=method(s.methods,"normalizePixels"), pixel=method(s.methods,"getExternalSpritePixel");
		Map<String,String> n=match(norm.body,WorldBuilderNpcVisualSourceTemplates.NORMALIZE),p=match(pixel.body,WorldBuilderNpcVisualSourceTemplates.PIXEL);
		Method image=method(s.methods,"readAssetImage");
		Map<String,String> reader=match(image.body,"if ($sourceFile.isFile()) { return ImageIO.read($sourceFile); } String $resource = getEmbeddedAssetResource($sourceFile); if ($resource == null) { return null; } try (InputStream $input = getResourceClassLoader().getResourceAsStream($resource)) { return $input == null ? null : ImageIO.read($input); }");
		if(selected==null||!wrapper||!sameBindings(n,match(norm.params,"int[] $pixels, int $alphaThreshold"))||!sameBindings(p,match(pixel.params,"int $argb, int $alphaThreshold"))||!sameBindings(reader,match(image.params,"File $sourceFile")))throw new IllegalArgumentException("Unsupported crop/alpha/black-pixel or loader-wrapper semantics");
		int alpha=Integer.parseInt(selected.get("alpha"));if(alpha<0||alpha>255)throw new IllegalArgumentException("Unsafe alpha threshold");return alpha;
	}
	private static boolean sameBindings(Map<String,String> body,Map<String,String> params){if(body==null||params==null)return false;for(Map.Entry<String,String> e:params.entrySet())if(!e.getValue().equals(body.get(e.getKey())))return false;return true;}

	private static boolean hasCaller(List<Source> sources,String table){for(Source source:sources)for(Method method:source.methods)if(method.body.contains(table)&&method.body.contains("loadExternalNpcDirectionSheet"))return true;return false;}
	private static Caller caller(List<Source> sources,Source table) {
		Caller result=null;boolean candidate=false;
		for(Source s:sources)for(Method m:s.methods)if(m.body.contains(table.name)&&m.body.contains("loadExternalNpcDirectionSheet")) {
			candidate=true;Map<String,String> b=match(m.body,WorldBuilderNpcVisualSourceTemplates.CALLER);
			if(b!=null&&table.name.equals(b.get("table"))){if(result!=null)throw new IllegalArgumentException("Ambiguous visual loader callers");result=new Caller(s,b);}
		}
		if(candidate&&result==null)throw new IllegalArgumentException("Unsupported source loader/activation body for "+table.path);
		return result;
	}
	private static List<Entry> tableEntries(Source s) {
		List<Entry> result=new ArrayList<>();int start=s.tokens.indexOf("enum");if(start<0||start+2>=s.tokens.size())return result;
		int open=s.tokens.indexOf("{");if(open<0)return result;
		Method ctor=null;for(Method m:s.methods)if(m.name.equals(s.name)&&m.params.contains("..."))ctor=m;if(ctor==null)return result;
		List<String> params=new ArrayList<>();for(int i=0;i<ctor.params.size();i++)if(i+1==ctor.params.size()||ctor.params.get(i+1).equals(","))params.add(ctor.params.get(i));
		if(params.size()!=4)return result;Map<String,String> fields=new LinkedHashMap<>();
		for(int i=0;i<ctor.body.size();i+=6){if(i+6>ctor.body.size())return result;Map<String,String> b=match(ctor.body.subList(i,i+6),"this.$field = $parameter;");if(b==null)return result;fields.put(b.get("parameter"),b.get("field"));}
		if(fields.size()!=4)return result;
		for(int i=open+1;i<s.tokens.size()&&!s.tokens.get(i).equals(";");){String symbol=s.tokens.get(i++);if(!identifier(symbol)||!s.tokens.get(i).equals("("))return Collections.emptyList();int close=end(s.tokens,i);List<String> args=new ArrayList<>();for(String t:s.tokens.subList(i+1,close))if(!t.equals(","))args.add(t);if(args.size()<8||!args.get(0).matches("[0-9]+"))return Collections.emptyList();
			Map<String,Object> values=new LinkedHashMap<>();values.put(fields.get(params.get(0)),Long.valueOf(args.get(0)));values.put(fields.get(params.get(1)),string(args.get(1)));values.put(fields.get(params.get(2)),string(args.get(2)));List<Long> widths=new ArrayList<>();for(String t:args.subList(3,args.size()))widths.add(Long.valueOf(t));values.put(fields.get(params.get(3)),widths);result.add(new Entry(symbol,Math.toIntExact(Long.parseLong(args.get(0))),string(args.get(1)),values));i=close+1;if(s.tokens.get(i).equals(","))i++;
		}
		Set<String> symbols=new HashSet<>();for(Entry e:result)symbols.add(e.symbol);for(Entry e:result)for(String symbol:symbols)e.fields.put(symbol,symbol);
		return result;
	}
	private static List<Source> sources(WorldBuilderReadOnlyTarget target)throws Exception {
		List<Source> sources=new ArrayList<>();long bytes=0;int visits=0,totalTokens=0;
		for(String root:Arrays.asList("Client_Base/src","client/src","src"))if(target.exists(root)) {
			target.requiredDirectory(root);
			try(java.util.stream.Stream<Path> walk=Files.walk(target.root.resolve(root),16)) {
				Iterator<Path> it=walk.iterator();while(it.hasNext()){Path p=it.next();if(++visits>12000)throw new IllegalArgumentException("Source inventory exceeds 12000 entries");if(Files.isSymbolicLink(p))throw new IllegalArgumentException("Source inventory contains a link");if(!p.toString().endsWith(".java")||!Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS))continue;String relative=target.relative(p);Path file=target.requiredFile(relative);long size=Files.size(file);bytes+=size;if(size>4*1024*1024||bytes>32*1024*1024)throw new IllegalArgumentException("Source inventory exceeds bounded byte budget");String text=new String(Files.readAllBytes(file),StandardCharsets.UTF_8);Source source=new Source(relative,text);totalTokens+=source.tokens.size();if(totalTokens>2000000)throw new IllegalArgumentException("Aggregate source token budget exceeded");sources.add(source);}
			}
		}
		return sources;
	}
	private static List<Definition> definitions(WorldBuilderReadOnlyTarget target,WorldBuilderPackedSourceLayout layout)throws Exception {
		List<Definition> result=new ArrayList<>();int offset=0;
		for(String file:Arrays.asList("NpcDefs.json","NpcDefsCustom.json")){
			String path=layout.definitionPath(file);if(!target.exists(path))continue;
			List<?> rows=definitionRows(target,path);int index=0;
			for(Object raw:rows){Map<?,?> row=(Map<?,?>)raw;if(row.get("name") instanceof String)result.add(new Definition(path,index,offset+index,(String)row.get("name")));index++;}offset+=rows.size();
		}
		for(String path:WorldBuilderSupplementalNpcDefinitions.inspect(target,layout)){
			int index=0;for(Object raw:definitionRows(target,path)){Map<?,?> row=(Map<?,?>)raw;if(row.get("id") instanceof Long&&row.get("name") instanceof String)result.add(new Definition(path,index,Math.toIntExact((Long)row.get("id")),(String)row.get("name")));index++;}
		}
		WorldBuilderDefinitionComposition.Profile profile=WorldBuilderDefinitionComposition.inspect(target,layout);
		for(String path:Arrays.asList(profile.npcPatchPath,profile.wantMyWorld?layout.definitionPath("NpcDefsMyWorld.json"):"")){
			if(path.isEmpty()||!target.exists(path))continue;int index=0;
			for(Object raw:definitionRows(target,path)){Map<?,?> row=(Map<?,?>)raw;if(row.get("id") instanceof Long&&row.get("name") instanceof String){int id=Math.toIntExact((Long)row.get("id"));for(Iterator<Definition> it=result.iterator();it.hasNext();)if(it.next().id==id)it.remove();result.add(new Definition(path,index,id,(String)row.get("name")));}index++;}
		}
		return result;
	}
	private static List<?> definitionRows(WorldBuilderReadOnlyTarget target,String path)throws Exception {
		Object rows=WorldBuilderJsonDocuments.readTargetDefinitionObject(target.requiredFile(path)).get("npcs");
		if(!(rows instanceof List)||((List<?>)rows).size()>65536)throw new IllegalArgumentException("Invalid bounded NPC catalog");
		for(Object row:(List<?>)rows)if(!(row instanceof Map))throw new IllegalArgumentException("Invalid NPC definition row");return (List<?>)rows;
	}

	private static WorldBuilderContractException problem(String path,String message){return new WorldBuilderContractException(WorldBuilderErrorCodes.DEFINITION_MISMATCH,"npc-visual-source",path,false,message,"Provide an explicit neutral NPC visual descriptor for this definition, or restore matching source metadata and assets.");}
	private static final class Source {
		final String path,name;final boolean isEnum;final List<String> tokens;final List<Method> methods;
		Source(String path,String source){
			this.path=path;
			List<String> raw=WorldBuilderNpcVisualJava.tokens(source);String declared="";boolean enumDeclaration=false;
			for(int i=0;i+1<raw.size();i++)if(raw.get(i).equals("class")||raw.get(i).equals("enum")){declared=raw.get(i+1);enumDeclaration=raw.get(i).equals("enum");break;}
			name=declared;isEnum=enumDeclaration;tokens=new ArrayList<>();
			// Strip qualified package names only in token sequences, never literals.
			for(int i=0;i<raw.size();i++){
				int end=i;
				if(raw.get(i).matches("[a-z][A-Za-z0-9_$]*"))while(end+2<raw.size()&&raw.get(end+1).equals(".")&&identifier(raw.get(end+2))){
					end+=2;if(Character.isUpperCase(raw.get(end).charAt(0)))break;
				}
				if(end>i&&Character.isUpperCase(raw.get(end).charAt(0)))i=end;
				if(raw.get(i).equals("SpriteArchive")&&i+2<raw.size()&&raw.get(i+1).equals(".")&&raw.get(i+2).equals("Entry"))i+=2;
				tokens.add(raw.get(i));
			}
			methods=WorldBuilderNpcVisualJava.methods(tokens);
		}
	}
	private static final class Entry {final String symbol,name;final int id;final Map<String,Object> fields;Entry(String s,int i,String n,Map<String,Object> f){symbol=s;id=i;name=n;fields=f;}}
	private static final class Definition {final String path,name;final int index,id;Definition(String p,int i,int id,String n){path=p;index=i;this.id=id;name=n;}}
	private static final class Caller {final Source source;final Map<String,String> binding;Caller(Source s,Map<String,String>b){source=s;binding=b;}}
}
