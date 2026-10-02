package com.openrsc.worldbuilder;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * A read-only projection of a validated, durable content bundle. Population is
 * deliberately absent. Definition signatures do not include source-file hashes:
 * adding a row must not change the identity of every previous row in that file.
 * Unknown archive lookup semantics conservatively bind the complete relevant
 * archive; such changes need review rather than claiming unchanged presentation.
 */
final class WorldBuilderEffectiveContent {
    private WorldBuilderEffectiveContent() { }

    static Index index(WorldBuilderProjectContentBundle.Bundle bundle)
        throws IOException, WorldBuilderContractException {
        // A Bundle is a verified observation, not permission to trust later disk state.
        WorldBuilderProjectContentBundle.Bundle current = WorldBuilderProjectContentBundle.read(bundle.root);
        if (!current.bundleFingerprintSha256.equals(bundle.bundleFingerprintSha256)) {
            throw problem("Content changed while its effective index was being prepared.");
        }
        Map<String,Map<Integer,MutableEntry>> rows = new TreeMap<>();
        rows.put("floor", xml(bundle, "definition.tile", "TileDef"));
        rows.put("boundary", xml(bundle, "definition.boundary", "DoorDef"));
        rows.put("scenery", xml(bundle, "definition.scenery", "GameObjectDef"));
        Map<Integer,MutableEntry> npcs = new TreeMap<>();
        appendJson(bundle, npcs, "definition.npc.base", true, false);
        appendJson(bundle, npcs, "definition.npc.custom", true, false);
        appendJson(bundle, npcs, "definition.npc.patch", false, true);
        appendJson(bundle, npcs, "definition.npc.world", false, true);
        rows.put("npc", npcs);
        Map<Integer,MutableEntry> items = new TreeMap<>();
        appendJson(bundle, items, "definition.item.base", false, false);
        appendJson(bundle, items, "definition.item.custom", false, true);
        appendJson(bundle, items, "definition.item.patch", false, true);
        appendJson(bundle, items, "definition.item.world", false, true);
        rows.put("ground-item", items);
        Map<String,String> dependencies = new TreeMap<>();
        for (WorldBuilderProjectContentBundle.FileRecord file : bundle.files) {
            String role = (String)file.toJson().get("role");
            if (!role.startsWith("definition.")) dependencies.put(role, file.sha256);
        }
        VisualClosure closure = new VisualClosure(bundle, dependencies);
        Map<String,Map<Integer,Entry>> families = new TreeMap<>();
        for (Map.Entry<String,Map<Integer,MutableEntry>> family : rows.entrySet()) {
            String key = family.getKey();
            List<?> expected = (List<?>)bundle.definitionCatalog.get(catalogField(key));
            if (expected == null || expected.size() != family.getValue().size()) {
                throw problem("Effective " + key + " definitions disagree with the validated catalog.");
            }
            Map<Integer,Entry> entries = new TreeMap<>();
            for (Map.Entry<Integer,MutableEntry> row : family.getValue().entrySet()) {
                if (!expected.contains(Long.valueOf(row.getKey()))) throw problem(
                    "Effective " + key + " ID " + row.getKey() + " is outside the validated catalog.");
                MutableEntry value = row.getValue();
                Map<String,Object> visual = closure.forDefinition(key, row.getKey(), value.definition);
                String name = value.definition.get("name") instanceof String
                    ? (String)value.definition.get("name") : key + " " + row.getKey();
                entries.put(row.getKey(), new Entry(row.getKey(), name,
                    hash(value.definition), hash(visual), value.provenance));
            }
            families.put(key, Collections.unmodifiableMap(entries));
        }
        WorldBuilderProjectContentBundle.Bundle after = WorldBuilderProjectContentBundle.read(bundle.root);
        if (!after.bundleFingerprintSha256.equals(bundle.bundleFingerprintSha256)) {
            throw problem("Content changed while its effective index was being prepared.");
        }
        return new Index(families, dependencies, bundle.bundleFingerprintSha256);
    }

    /** Entry-level closures for supported lookups; unsupported lookups retain exact archive authority. */
    private static final class VisualClosure {
        final WorldBuilderProjectContentBundle.Bundle bundle;
        final Map<String,String> dependencies;
        final Map<String,Map<String,String>> sprites = new TreeMap<>();
        final Map<Integer,String> authentic;
        final Map<Integer,Map<String,Object>> animations = new TreeMap<>();
        final Map<Integer,Map<String,Object>> items = new TreeMap<>();
        final WorldBuilderNativeArchiveIndex models;
        VisualClosure(WorldBuilderProjectContentBundle.Bundle bundle, Map<String,String> dependencies)
            throws IOException, WorldBuilderContractException {
            this.bundle = bundle; this.dependencies = dependencies;
            for (String role : Arrays.asList("asset.sprite.custom", "asset.spritepack")) {
                try { sprites.put(role, WorldBuilderNpcDefinitionProvider.spriteEntryHashes(bundle.pathForRole(role))); }
                catch (IOException unsupported) { sprites.put(role, null); }
            }
            Map<Integer,String> indexed;
            try { indexed = WorldBuilderNpcDefinitionProvider.readAuthentic(bundle.pathForRole("asset.sprite.authentic")); }
            catch (IOException unsupported) { indexed = null; }
            authentic = indexed;
            models = WorldBuilderNativeArchiveIndex.inspect(bundle.pathForRole("asset.model"));
            if (dependencies.containsKey("metadata.npc-animations")) {
                Map<String,Object> registry;
                try { registry = WorldBuilderJsonDocuments.readTargetDefinitionObject(bundle.pathForRole("metadata.npc-animations")); }
                catch (WorldBuilderDiscoveryException invalid) { throw new IOException(invalid); }
                for (Object raw : (List<?>)registry.get("animations")) {
                    @SuppressWarnings("unchecked") Map<String,Object> row = (Map<String,Object>)raw;
                    animations.put(((Long)row.get("animationId")).intValue(), row);
                }
            }
            for (Object raw : bundle.itemVisuals) {
                @SuppressWarnings("unchecked") Map<String,Object> row = (Map<String,Object>)raw;
                items.put(((Long)row.get("itemId")).intValue(), row);
            }
        }
        Map<String,Object> forDefinition(String family, int id, Map<String,Object> definition)
            throws IOException, WorldBuilderContractException {
            Map<String,Object> result = new TreeMap<>();
            if ("scenery".equals(family)) {
                Object raw = definition.get("objectModel");
                if (raw instanceof String && !((String)raw).isEmpty() && !"na".equalsIgnoreCase((String)raw)) {
                    String name = (String)raw + ".ob3";
                    String entry = models.entrySha256(name);
                    if (entry != null && models.containsValidModel(name)) result.put(name, entry);
                    else result.put("unresolved-model-archive", dependencies.get("asset.model"));
                } else result.put("unresolved-model-archive", dependencies.get("asset.model"));
            } else if ("floor".equals(family) || "boundary".equals(family)) {
                boolean baseColor = "floor".equals(family) && "base-color-v1".equals(definition.get("worldBuilderMaterial"));
                for (String field : baseColor ? Collections.<String>emptyList() : "floor".equals(family) ? Arrays.asList("colour") : Arrays.asList("modelVar2", "modelVar3")) {
                    Integer material = integer(definition.get(field));
                    if (material == null) {
                        result.put("unresolved-material-archive", dependencies.get("asset.sprite.custom")); continue;
                    }
                    // Renderer sentinel used by invisible floors and wall faces.
                    if (material >= 0 && material != 12345678) sprite(result, "asset.sprite.custom", "textures/" + material);
                }
                // type-4 bridge underlays use a separately selected material.
                if (!baseColor && "floor".equals(family) && Integer.valueOf(4).equals(integer(definition.get("unknown")))) {
                    Integer selected = integer(definition.get("worldBuilderSourceOverlay"));
                    int overlay = selected == null || selected == 0 ? id + 1 : selected;
                    sprite(result, "asset.sprite.custom", "textures/" + (overlay == 12 ? 31 : 1));
                }
            } else if ("ground-item".equals(family) && items.containsKey(id)) {
                Map<String,Object> row = items.get(id); result.put("mapping", row);
                String role = (String)row.get("customSpriteAssetRole");
                if (role != null && !role.isEmpty()) sprite(result, role, row.get("customSpriteSubspace") + "/" + row.get("customSpriteEntry"));
                Integer sprite = integer(row.get("authenticSpriteId"));
                if (sprite != null && sprite >= 0) frame(result, sprite);
            } else if ("npc".equals(family)) {
                boolean unresolved = false;
                for (int slot = 1; slot <= 12; slot++) {
                    Integer animation = integer(definition.get("sprites" + slot));
                    if (animation == null) { unresolved = true; continue; }
                    if (animation < 0) continue;
                    Map<String,Object> row = animations.get(animation);
                    if (row == null) { unresolved = true; continue; }
                    result.put("animation/" + animation, row);
                    if (!"authentic-rgb".equals(row.get("frameSource"))) {
                        String key = row.get("customSpriteSubspace") + "/" + row.get("customSpriteEntry");
                        sprite(result, "asset.sprite.custom", key);
                        String actual = sprites.get("asset.sprite.custom") == null ? null : sprites.get("asset.sprite.custom").get(key);
                        if (actual != null && !actual.equals(row.get("customEntrySha256"))) throw problem("NPC custom animation entry differs from its verified registry.");
                    }
                    int first = ((Long)row.get("authenticBaseSpriteId")).intValue();
                    List<?> hashes = (List<?>)row.get("authenticFrameSha256s");
                    for (int offset = 0; offset < hashes.size(); offset++) {
                        frame(result, first + offset);
                        if (authentic != null && !hashes.get(offset).equals(authentic.get(first + offset))) throw problem("NPC authentic frame differs from its verified registry.");
                    }
                }
                if (unresolved) unresolvedSprites(result);
            } else unresolvedSprites(result);
            return result;
        }
        void sprite(Map<String,Object> result, String role, String key) throws WorldBuilderContractException {
            Map<String,String> entries = sprites.get(role);
            if (entries == null) result.put("unresolved/" + role, dependencies.get(role));
            else if (entries.containsKey(key)) result.put(role + "/" + key, entries.get(key));
            else throw problem("Content references missing sprite dependency " + role + "/" + key + ".");
        }
        void frame(Map<String,Object> result, int id) throws WorldBuilderContractException {
            if (authentic == null) result.put("unresolved/asset.sprite.authentic", dependencies.get("asset.sprite.authentic"));
            else if (authentic.containsKey(id)) result.put("authentic/" + id, authentic.get(id));
            else throw problem("Content references missing authentic frame " + id + ".");
        }
        void unresolvedSprites(Map<String,Object> result) {
            // Unrecognized lookup semantics cannot safely use per-entry equivalence.
            // Bind logical entries when readable (archive timestamps are irrelevant).
            result.put("unresolved/authentic", authentic == null ? dependencies.get("asset.sprite.authentic") : authentic);
            for (String role : sprites.keySet()) result.put("unresolved/" + role,
                sprites.get(role) == null ? dependencies.get(role) : sprites.get(role));
        }
        Integer integer(Object raw) {
            if (raw instanceof Long && (Long)raw >= Integer.MIN_VALUE && (Long)raw <= Integer.MAX_VALUE) return ((Long)raw).intValue();
            if (raw instanceof String) try { return Integer.valueOf((String)raw); } catch (NumberFormatException invalid) { return null; }
            return null;
        }
    }

    private static void appendJson(WorldBuilderProjectContentBundle.Bundle bundle,
        Map<Integer,MutableEntry> entries, String role, boolean sequential, boolean overlay)
        throws IOException, WorldBuilderContractException {
        Map<String,Object> document;
        try { document = WorldBuilderJsonDocuments.readTargetDefinitionObject(bundle.pathForRole(role)); }
        catch (WorldBuilderDiscoveryException invalid) { throw new IOException(invalid); }
        if (document.size() != 1 || !(document.values().iterator().next() instanceof List)) {
            throw problem("Effective definition role has no single registry array: " + role);
        }
        List<?> rows = (List<?>)document.values().iterator().next();
        int start = entries.size();
        java.util.Set<Integer> local = new java.util.HashSet<>();
        for (int position = 0; position < rows.size(); position++) {
            if (!(rows.get(position) instanceof Map)) throw problem("Invalid effective definition in " + role);
            @SuppressWarnings("unchecked") Map<String,Object> row = (Map<String,Object>)rows.get(position);
            Object rawId = row.get("id");
            int id;
            if (sequential) id = start + position;
            else if (rawId instanceof Long && (Long)rawId >= 0 && (Long)rawId <= 65535) id = ((Long)rawId).intValue();
            else throw problem("Missing bounded definition identity in " + role);
            if (!local.add(id)) throw problem("Duplicate effective definition " + id + " in " + role);
            MutableEntry entry = entries.get(id);
            if (entry != null && !overlay) throw problem("Repeated effective definition " + id + " in " + role);
            if (entry == null) { entry = new MutableEntry(); entries.put(id, entry); }
            entry.definition.putAll(row);
            entry.definition.put("id", Long.valueOf(id));
            entry.provenance.add(role + "#" + position);
        }
    }

    private static Map<Integer,MutableEntry> xml(WorldBuilderProjectContentBundle.Bundle bundle,
        String role, String element) throws IOException, WorldBuilderContractException {
        Path path = bundle.pathForRole(role);
        if (Files.size(path) > WorldBuilderContractLimits.MAX_JSON_BYTES) throw problem("Oversized " + role);
        try (InputStream input = Files.newInputStream(path)) {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setXIncludeAware(false); factory.setExpandEntityReferences(false);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            Element root = factory.newDocumentBuilder().parse(input).getDocumentElement();
            if (!(element + "-array").equals(root.getNodeName())) throw problem("Unexpected XML root for " + role);
            Map<Integer,MutableEntry> rows = new TreeMap<>();
            NodeList children = root.getChildNodes();
            for (int i = 0; i < children.getLength(); i++) {
                Node child = children.item(i);
                if (child.getNodeType() != Node.ELEMENT_NODE) continue;
                if (!element.equals(child.getNodeName())) throw problem("Unexpected XML record in " + role);
                if (rows.size() >= 65536) throw problem("Oversized effective definition registry " + role);
                MutableEntry row = new MutableEntry();
                NodeList fields = child.getChildNodes();
                for (int j = 0; j < fields.getLength(); j++) {
                    Node field = fields.item(j);
                    if (field.getNodeType() != Node.ELEMENT_NODE) continue;
                    if (row.definition.put(field.getNodeName(), xmlValue(field)) != null) throw problem("Duplicate XML definition field in " + role);
                }
                int id = rows.size();
                row.definition.put("id", Long.valueOf(id));
                row.provenance.add(role + "#" + id); rows.put(id, row);
            }
            return rows;
        } catch (WorldBuilderContractException failure) { throw failure; }
        catch (Exception invalid) { throw new IOException("Invalid effective content XML: " + role, invalid); }
    }

    private static Object xmlValue(Node node) throws WorldBuilderContractException {
        Map<String,Object> nested = new TreeMap<>();
        NodeList children = node.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.ELEMENT_NODE) {
                if (nested.put(child.getNodeName(), xmlValue(child)) != null) throw problem("Duplicate nested XML content field.");
            }
        }
        if (node.hasAttributes()) for (int i = 0; i < node.getAttributes().getLength(); i++) {
            Node attribute = node.getAttributes().item(i);
            nested.put("@" + attribute.getNodeName(), attribute.getNodeValue());
        }
        if (nested.isEmpty()) return node.getTextContent().trim();
        return nested;
    }

    private static String catalogField(String family) {
        if ("floor".equals(family)) return "tiles";
        if ("boundary".equals(family)) return "boundaries";
        if ("npc".equals(family)) return "npcs";
        if ("ground-item".equals(family)) return "groundItems";
        return "scenery";
    }

    private static Object canonical(Object value) {
        if (value instanceof Map) {
            Map<String,Object> sorted = new TreeMap<>();
            for (Map.Entry<?,?> entry : ((Map<?,?>)value).entrySet()) sorted.put(String.valueOf(entry.getKey()), canonical(entry.getValue()));
            return sorted;
        }
        if (value instanceof List) {
            List<Object> ordered = new ArrayList<>();
            for (Object item : (List<?>)value) ordered.add(canonical(item));
            return ordered;
        }
        return value;
    }

    private static String hash(Object value) {
        return WorldBuilderHashes.sha256(WorldBuilderJsonDocuments.pretty(canonical(value)).getBytes(StandardCharsets.UTF_8));
    }

    private static WorldBuilderContractException problem(String message) {
        return WorldBuilderReadOnlyTarget.problem(WorldBuilderErrorCodes.DEFINITION_MISMATCH,
            "content revision", message, "Restore verified project content or detect new content from one stable target.");
    }

    private static final class MutableEntry {
        final Map<String,Object> definition = new TreeMap<>();
        final List<String> provenance = new ArrayList<>();
    }

    static final class Entry {
        final int id;
        final String name, semanticSha256, visualSha256;
        final List<String> provenance;
        Entry(int id, String name, String semantic, String visual, List<String> provenance) {
            this.id = id; this.name = name; this.semanticSha256 = semantic; this.visualSha256 = visual;
            this.provenance = Collections.unmodifiableList(new ArrayList<>(provenance));
        }
    }

    static final class Index {
        final Map<String,Map<Integer,Entry>> families;
        final Map<String,String> dependencies;
        final String contentSha256, bundleFingerprintSha256;
        Index(Map<String,Map<Integer,Entry>> families, Map<String,String> dependencies, String bundleHash) {
            this.families = Collections.unmodifiableMap(new TreeMap<>(families));
            this.dependencies = Collections.unmodifiableMap(new TreeMap<>(dependencies));
            this.bundleFingerprintSha256 = bundleHash;
            Map<String,Object> values = new TreeMap<>();
            for (Map.Entry<String,Map<Integer,Entry>> family : families.entrySet()) {
                Map<String,Object> entries = new TreeMap<>();
                for (Entry entry : family.getValue().values()) entries.put(Integer.toString(entry.id),
                    Arrays.asList(entry.semanticSha256, entry.visualSha256));
                values.put(family.getKey(), entries);
            }
            contentSha256 = hash(values);
        }
    }
}
