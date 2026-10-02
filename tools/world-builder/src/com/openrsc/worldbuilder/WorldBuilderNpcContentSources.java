package com.openrsc.worldbuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import static com.openrsc.worldbuilder.WorldBuilderNpcVisualJava.*;

/** Active NPC append sources. Neither filename discovery nor map population selects content. */
final class WorldBuilderNpcContentSources {
    static final String DESCRIPTOR = "server/conf/world-builder/effective-content-sources-v1.json";
    static final String LOADER = "server/src/com/openrsc/server/external/EntityHandler.java";
    private WorldBuilderNpcContentSources() { }

    static Selection inspect(WorldBuilderReadOnlyTarget target, WorldBuilderPackedSourceLayout layout)
        throws WorldBuilderContractException {
        try {
            if (target.exists(DESCRIPTOR)) return descriptor(target, layout);
            if (target.exists(LOADER)) return source(target, layout);
            // Original two-file layouts need no new descriptor. Additional similarly
            // named files are not implicitly active; refuse ambiguity rather than
            // publishing their definitions under invented identities.
            java.nio.file.Path root = target.requiredDirectory(layout.definitionRoot);
            try (java.nio.file.DirectoryStream<java.nio.file.Path> files = Files.newDirectoryStream(root)) {
                for (java.nio.file.Path file : files) {
                    String name = file.getFileName().toString();
                    if (name.endsWith("NpcDefs.json") && !name.equals("NpcDefs.json") && !name.equals("NpcDefsCustom.json")) {
                        throw problem(target.relative(file), "Additional NPC catalogs have no verified active load order.");
                    }
                }
            }
            return new Selection(Collections.<String>emptyList(), Collections.<WorldBuilderReadOnlyTarget.FileState>emptyList());
        } catch (WorldBuilderContractException refusal) { throw refusal; }
        catch (Exception invalid) { throw problem(DESCRIPTOR, "Effective NPC source selection is unsupported: " + invalid.getMessage()); }
    }

    static Selection inspectItems(WorldBuilderReadOnlyTarget target, WorldBuilderPackedSourceLayout layout)
        throws WorldBuilderContractException {
        try {
            List<String> paths = new ArrayList<>();
            List<WorldBuilderReadOnlyTarget.FileState> evidence = new ArrayList<>();
            if (target.exists(DESCRIPTOR)) {
                // Reuse the full descriptor/configuration validation before reading
                // its optional keyed item projection.
                descriptor(target, layout);
                Map<String,Object> root = WorldBuilderJsonDocuments.readTargetDefinitionObject(target.requiredFile(DESCRIPTOR));
                if (root.containsKey("itemRegistry")) {
                    Map<String,Object> registry = object(root.get("itemRegistry")); exact(registry,"semantics","sources");
                    if (!"openrsc-id-overwrite-v1".equals(registry.get("semantics"))) throw problem(DESCRIPTOR,"Unsupported effective item registry semantics.");
                    List<?> sources=list(registry.get("sources"));
                    if(sources.size()<2||sources.size()>66)throw problem(DESCRIPTOR,"Effective item source list must contain 2..66 ordered registries.");
                    for(Object raw:sources){Map<String,Object> row=object(raw);exact(row,"relativePath","sha256");String path=string(row.get("relativePath"));
                        paths.add(boundDeclaredSource(target,layout,path,row.get("sha256"),evidence));}
                    evidence.add(target.requiredState("effective-content-sources",DESCRIPTOR));
                    return selectedItems(layout,paths,evidence);
                }
            }
            if(target.exists(LOADER)) {
                List<Method> methods=methods(tokens(new String(Files.readAllBytes(target.requiredFile(LOADER)),StandardCharsets.UTF_8)));
                Method load=method(methods,"load");
                int start=load.body.indexOf("loadItems");
                if(start>=0){
                    requireTopLevel(load.body, start, "item");
                    requireSingleCaller(methods, "loadItems");
                    Method loader=method(methods,"loadItems"),add=method(methods,"addItemDefinition");
                    requireItemLoader(loader, add);
                    int at=start;
                    while(at<load.body.size()&&"loadItems".equals(load.body.get(at))){
                        int finish=at;while(finish<load.body.size()&&!";".equals(load.body.get(finish)))finish++;
                        Map<String,String> bind=match(load.body.subList(at,finish+1),"loadItems(getServer().getConfig().CONFIG_DIR + $path);");
                        if(bind==null)throw problem(LOADER,"Item source call is not a supported literal registry load.");
                        String path=WorldBuilderNpcVisualJava.string(bind.get("path"));
                        if(!path.matches("/defs/[A-Za-z0-9_-]+\\.json"))throw problem(LOADER,"Item source path is outside selected definitions.");
                        paths.add(layout.definitionPath(path.substring(6)));at=finish+1;
                    }
                    if(load.body.subList(at,load.body.size()).contains("loadItems"))throw problem(LOADER,"Item loads occur outside recognized declarative block.");
                    evidence.add(target.requiredState("effective-content-loader-source",LOADER));
                    return selectedItems(layout,paths,evidence);
                }
            }
            try(java.nio.file.DirectoryStream<java.nio.file.Path> files=Files.newDirectoryStream(target.requiredDirectory(layout.definitionRoot))){
                for(java.nio.file.Path file:files){String name=file.getFileName().toString();if(name.endsWith("ItemDefs.json")&&!name.equals("ItemDefs.json"))throw problem(target.relative(file),"Additional item catalogs have no verified active load order.");}
            }
            return new Selection(Collections.<String>emptyList(),evidence);
        } catch(WorldBuilderContractException failure){throw failure;}
        catch(Exception invalid){throw problem(DESCRIPTOR,"Effective item source selection is unsupported: "+invalid.getMessage());}
    }

    private static void requireItemLoader(Method loader, Method add) throws WorldBuilderContractException {
        if (match(add.body,"while (items.size() <= item.getId()) { items.add(null); } items.set(item.getId(), item);") == null) throw problem(LOADER,"Item ID overwrite setter is not recognized.");
        List<String> body = loader.body;
        int loop = body.indexOf("for");
        if (loop < 0 || match(body.subList(0,loop),"try { JSONObject object = new JSONObject(new String(Files.readAllBytes(Paths.get(filename)))); JSONArray itemDefs = object.getJSONArray(JSONObject.getNames(object)[0]);") == null) throw problem(LOADER,"Item source array initialization is not recognized.");
        int conditionEnd = end(body, loop + 1), start = conditionEnd + 1;
        if (match(body.subList(loop,start),"for (int i = 0; i < itemDefs.length(); i++)") == null || !"{".equals(body.get(start))) throw problem(LOADER,"Item row iteration is not recognized.");
        int stop = end(body,start);
        if (match(body.subList(stop+1,body.size()),"} catch (Exception e) { LOGGER.error(e); }") == null) throw problem(LOADER,"Item loader contains unsupported population logic after row iteration.");
        List<String> row = body.subList(start+1,stop);
        String constructor = "JSONObject item = itemDefs.getJSONObject(i); ItemDefinition toAdd = new ItemDefinition(item.getInt(\"id\"), item.getString(\"name\"), item.getString(\"description\"), item.getString(\"command\").split(\",\"), item.getInt(\"isFemaleOnly\") == 1, item.getInt(\"isMembersOnly\") == 1, item.getInt(\"isStackable\") == 1, item.getInt(\"isUntradable\") == 1, item.getInt(\"isWearable\") == 1, item.getInt(\"appearanceID\"), item.getInt(\"wearableID\"), item.getInt(\"wearSlot\"), item.getInt(\"requiredLevel\"), item.getInt(\"requiredSkillID\"), item.getLong(\"armourBonus\"), item.getInt(\"weaponAimBonus\"), item.getInt(\"weaponPowerBonus\"), item.getInt(\"magicBonus\"), item.getInt(\"prayerBonus\"), item.getInt(\"basePrice\"), item.getInt(\"isNoteable\") == 1);";
        List<String> prefix = tokens(constructor), append = tokens("addItemDefinition(toAdd);");
        if (row.size() < prefix.size()+append.size() || !row.subList(0,prefix.size()).equals(prefix) || !row.subList(row.size()-append.size(),row.size()).equals(append)) throw problem(LOADER,"Item identity construction or unconditional registry assignment is unsupported.");
        int at = prefix.size(), finish = row.size()-append.size();
        Set<String> allowed = new HashSet<>();
        allowed.add(text(tokens("if (toAdd.getCommand().length == 1 && \"\".equals(toAdd.getCommand()[0])) { toAdd.nullCommand(); }")));
        for (String field : Arrays.asList("meleeOffense","rangedOffense","magicOffense","weaponSpeed","meleeDefense","rangedDefense","magicDefense")) {
            String setter = "set" + Character.toUpperCase(field.charAt(0)) + field.substring(1);
            allowed.add(text(tokens("if (item.has(\""+field+"\")) toAdd."+setter+"(item.getInt(\""+field+"\"));")));
        }
        while (at < finish) {
            if (!"if".equals(row.get(at))) throw problem(LOADER,"Item loader changes identity or population outside supported field assignments.");
            int condition = end(row,at+1), next = condition+1;
            if ("{".equals(row.get(next))) next=end(row,next)+1;
            else { while(next<finish&&!";".equals(row.get(next)))next++;next++; }
            if (next>finish || !allowed.remove(text(row.subList(at,next)))) throw problem(LOADER,"Item loader contains an unsupported conditional row or field transformation.");
            at=next;
        }
    }

    private static Selection selectedItems(WorldBuilderPackedSourceLayout layout,List<String> paths,List<WorldBuilderReadOnlyTarget.FileState> evidence)throws WorldBuilderContractException{
        if(paths.size()<2||paths.size()>66||!paths.get(0).equals(layout.definitionPath("ItemDefs.json"))||!paths.get(1).equals(layout.definitionPath("ItemDefsCustom.json"))||new HashSet<>(paths).size()!=paths.size())throw problem(DESCRIPTOR,"Item sources do not have one unambiguous base/custom overwrite order.");
        return new Selection(new ArrayList<>(paths.subList(2,paths.size())),evidence);
    }

    private static Selection descriptor(WorldBuilderReadOnlyTarget target, WorldBuilderPackedSourceLayout layout)
        throws Exception {
        Map<String,Object> root = WorldBuilderJsonDocuments.readTargetDefinitionObject(target.requiredFile(DESCRIPTOR));
        if (root.containsKey("itemRegistry")) exact(root,"schemaVersion","manifestType","configuration","npcRegistry","itemRegistry");
        else exact(root,"schemaVersion","manifestType","configuration","npcRegistry");
        if (!Long.valueOf(1).equals(root.get("schemaVersion")) || !"world-builder-effective-content-sources".equals(root.get("manifestType"))) throw problem(DESCRIPTOR,"Unsupported effective content source descriptor.");
        Map<String,Object> configuration = object(root.get("configuration")); exact(configuration,"relativePath","sha256");
        String configPath = string(configuration.get("relativePath"));
        WorldBuilderReadOnlyTarget.FileState configured = target.requiredState("effective-content-configuration",configPath);
        if (!configPath.equals(layout.configurationPath) || !configured.sha256.equals(configuration.get("sha256")) || !configured.sha256.equals(WorldBuilderHashes.sha256(target.requiredFile(layout.configurationPath)))) throw problem(DESCRIPTOR,"Effective content export selects a different or changed configuration.");
        Map<String,Object> registry = object(root.get("npcRegistry")); exact(registry,"semantics","sources");
        if (!"openrsc-sequential-append-v1".equals(registry.get("semantics"))) throw problem(DESCRIPTOR,"Unsupported effective NPC registry semantics.");
        List<?> sources = list(registry.get("sources"));
        if (sources.size()<2 || sources.size()>66) throw problem(DESCRIPTOR,"Effective NPC source list must contain 2..66 ordered registries.");
        List<String> paths = new ArrayList<>(); List<WorldBuilderReadOnlyTarget.FileState> evidence = new ArrayList<>();
        evidence.add(target.requiredState("effective-content-sources",DESCRIPTOR));
        for (Object raw:sources) {
            Map<String,Object> row=object(raw);exact(row,"relativePath","sha256");
            String path=string(row.get("relativePath"));
            paths.add(boundDeclaredSource(target, layout, path, row.get("sha256"), evidence));
        }
        return selected(layout,paths,evidence);
    }

    private static String boundDeclaredSource(WorldBuilderReadOnlyTarget target,
        WorldBuilderPackedSourceLayout layout, String path, Object expected,
        List<WorldBuilderReadOnlyTarget.FileState> evidence) throws Exception {
        int slash = path.lastIndexOf('/');
        if (slash < 0 || !path.substring(slash + 1).matches("[A-Za-z0-9_-]+\\.json")) throw problem(DESCRIPTOR,"Effective source is not a direct bounded definition JSON file.");
        String root = path.substring(0,slash);
        if (!WorldBuilderPackedSourceLayout.DEFINITION_ROOTS.contains(root)) throw problem(DESCRIPTOR,"Effective source root is outside supported definition layouts.");
        String selected = layout.definitionPath(path.substring(slash + 1));
        WorldBuilderReadOnlyTarget.FileState original = target.requiredState("effective-content-definition",path);
        if (!original.sha256.equals(expected)) throw problem(path,"Effective content source export is stale.");
        if (!path.equals(selected)) {
            // During project capture the original immutable source and its compiled
            // canonical alias coexist. Both must be exact; never rewrite the export.
            if (!layout.definitionRoot.equals(WorldBuilderPackedSourceLayout.CANONICAL_DEFINITION_ROOT)
                || !target.requiredState("effective-content-definition",selected).sha256.equals(expected)) {
                throw problem(path,"Effective source does not match its selected canonical definition alias.");
            }
            evidence.add(original);
        }
        return selected;
    }

    private static Selection source(WorldBuilderReadOnlyTarget target, WorldBuilderPackedSourceLayout layout)
        throws Exception {
        String raw=new String(Files.readAllBytes(target.requiredFile(LOADER)),StandardCharsets.UTF_8);
        List<Method> methods=methods(tokens(raw));
        Method loader=method(methods,"loadNpcs"), load=method(methods,"load");
        // Recognize only the standard sequential array loader. Its identity is
        // append position, never a guessed spare ID. More elaborate loaders need
        // the inert effective-source descriptor from their maintained build.
        String body=text(loader.body);
        if (!body.contains("npcDefs . getJSONObject ( i )") || !body.contains("npcs . add ( def )")
            || body.contains("npcs . set") || body.contains("npcs . clear") || body.contains("npcs . remove")
            || !body.contains("for ( int i = 0 ; i < npcDefs . length ( ) ; i ++ )")) {
            throw problem(LOADER,"NPC append loader semantics are not recognized.");
        }
        int appendReferences = 0;
        for (String token : loader.body) if ("npcs".equals(token)) appendReferences++;
        if (appendReferences != 1) throw problem(LOADER,"NPC loader mutates the registry outside its single append operation.");
        int loop = loader.body.indexOf("for");
        int loopConditionEnd = end(loader.body, loop + 1), loopStart = loopConditionEnd + 1;
        if (!"{".equals(loader.body.get(loopStart))) throw problem(LOADER,"NPC append loop has no bounded body.");
        int loopEnd = end(loader.body, loopStart);
        List<String> append = tokens("npcs.add(def);");
        if (loopEnd - append.size() <= loopStart || !loader.body.subList(loopEnd-append.size(),loopEnd).equals(append)) throw problem(LOADER,"NPC append does not occur unconditionally at the end of each row.");
        for (String token : loader.body.subList(loopStart+1,loopEnd)) if (Arrays.asList("if","while","switch","return","continue","break","throw","try").contains(token)) throw problem(LOADER,"NPC loader contains conditional population changes requiring an inert content export.");
        List<String> paths=new ArrayList<>();
        WorldBuilderDefinitionComposition.Profile profile=WorldBuilderDefinitionComposition.inspect(target,layout);
        List<String> ts=load.body;
        int start=ts.indexOf("loadNpcs");
        if(start<0)throw problem(LOADER,"NPC registry loading is not explicitly declared.");
        requireTopLevel(ts, start, "NPC");
        requireSingleCaller(methods, "loadNpcs");
        int stop=start;
        while(stop<ts.size()) {
            String token=ts.get(stop);
            if("loadNpcs".equals(token)) { stop=call(ts,stop,layout,paths,true); continue; }
            if("if".equals(token)) {
                if(stop+1>=ts.size()||!"(".equals(ts.get(stop+1)))break;
                int conditionEnd=end(ts,stop+1);
                if(match(ts.subList(stop+2,conditionEnd),"getServer().getConfig().WANT_MYWORLD")==null)throw problem(LOADER,"NPC source activation has an unsupported condition.");
                int block=conditionEnd+1;
                if(block>=ts.size()||!"{".equals(ts.get(block)))throw problem(LOADER,"NPC source condition requires a bounded block.");
                int blockEnd=end(ts,block), next=block+1;
                while(next<blockEnd)next=call(ts,next,layout,paths,profile.wantMyWorld);
                stop=blockEnd+1;continue;
            }
            break;
        }
        // A later conditional/dynamic call is not ignored simply because the
        // first block looked familiar. No selected source may be added twice.
        if(ts.subList(stop,ts.size()).contains("loadNpcs"))throw problem(LOADER,"NPC loads occur outside the recognized declarative block.");
        return selected(layout,paths,Collections.singletonList(target.requiredState("effective-content-loader-source",LOADER)));
    }

    private static void requireTopLevel(List<String> tokens, int at, String family) throws WorldBuilderContractException {
        int depth = 0;
        for (String token : tokens.subList(0, at)) {
            if ("{".equals(token)) depth++;
            if ("}".equals(token)) depth--;
        }
        if (depth != 0) throw problem(LOADER, family + " registry starts inside an unsupported conditional or loop.");
    }

    private static void requireSingleCaller(List<Method> methods, String operation) throws WorldBuilderContractException {
        for (Method method : methods) if (!"load".equals(method.name) && method.body.contains(operation)) {
            throw problem(LOADER, "Additional " + operation + " calls require a maintained inert content export.");
        }
    }

    private static int call(List<String> tokens,int start,WorldBuilderPackedSourceLayout layout,List<String> selected,boolean enabled) throws WorldBuilderContractException {
        int finish=start;while(finish<tokens.size()&&!";".equals(tokens.get(finish)))finish++;
        if(finish==tokens.size())throw problem(LOADER,"Unterminated NPC source call.");
        Map<String,String> binding=match(tokens.subList(start,finish+1),"loadNpcs(getServer().getConfig().CONFIG_DIR + $path);");
        if(binding==null)throw problem(LOADER,"NPC source call is not a literal supported registry load.");
        String path=WorldBuilderNpcVisualJava.string(binding.get("path"));
        if(!path.matches("/defs/[A-Za-z0-9_-]+\\.json"))throw problem(LOADER,"NPC source path is outside the selected definition directory.");
        if(enabled)selected.add(layout.definitionPath(path.substring("/defs/".length())));
        return finish+1;
    }

    private static Selection selected(WorldBuilderPackedSourceLayout layout,List<String> paths,List<WorldBuilderReadOnlyTarget.FileState> evidence) throws WorldBuilderContractException {
        if(paths.size()<2||!paths.get(0).equals(layout.definitionPath("NpcDefs.json"))||!paths.get(1).equals(layout.definitionPath("NpcDefsCustom.json"))||paths.size()>66||new HashSet<>(paths).size()!=paths.size()) throw problem(DESCRIPTOR,"NPC sources do not have one unambiguous base/custom append order.");
        return new Selection(new ArrayList<>(paths.subList(2,paths.size())),evidence);
    }
    @SuppressWarnings("unchecked") private static Map<String,Object> object(Object value) throws WorldBuilderContractException {if(!(value instanceof Map))throw problem(DESCRIPTOR,"Expected descriptor object.");return(Map<String,Object>)value;}
    private static List<?> list(Object value) throws WorldBuilderContractException {if(!(value instanceof List))throw problem(DESCRIPTOR,"Expected descriptor array.");return(List<?>)value;}
    private static String string(Object value) throws WorldBuilderContractException {if(!(value instanceof String))throw problem(DESCRIPTOR,"Expected descriptor string.");return(String)value;}
    private static void exact(Map<String,Object> value,String...keys) throws WorldBuilderContractException {if(!value.keySet().equals(new HashSet<>(Arrays.asList(keys))))throw problem(DESCRIPTOR,"Unexpected descriptor fields.");}
    private static WorldBuilderContractException problem(String path,String message){return WorldBuilderReadOnlyTarget.problem(WorldBuilderErrorCodes.DEFINITION_MISMATCH,path,message,"Publish a fresh maintained effective-content-sources-v1.json export for this configuration, or use the supported sequential OpenRSC loader.");}
    static final class Selection {
        final List<String> supplemental;final List<WorldBuilderReadOnlyTarget.FileState> evidence;
        Selection(List<String> supplemental,List<WorldBuilderReadOnlyTarget.FileState> evidence){this.supplemental=Collections.unmodifiableList(supplemental);this.evidence=Collections.unmodifiableList(evidence);}
    }
}
