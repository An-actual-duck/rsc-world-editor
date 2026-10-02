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
        Map<String,Map<Integer,Entry>> families = new TreeMap<>();
        for (Map.Entry<String,Map<Integer,MutableEntry>> family : rows.entrySet()) {
            String key = family.getKey();
            List<?> expected = (List<?>)bundle.definitionCatalog.get(catalogField(key));
            if (expected == null || expected.size() != family.getValue().size()) {
                throw problem("Effective " + key + " definitions disagree with the validated catalog.");
            }
            Map<Integer,Entry> entries = new TreeMap<>();
            Map<String,String> visuals = visualDependencies(key, dependencies);
            for (Map.Entry<Integer,MutableEntry> row : family.getValue().entrySet()) {
                if (!expected.contains(Long.valueOf(row.getKey()))) throw problem(
                    "Effective " + key + " ID " + row.getKey() + " is outside the validated catalog.");
                MutableEntry value = row.getValue();
                Map<String,Object> visual = new TreeMap<>();
                visual.put("dependencies", visuals);
                if ("ground-item".equals(key)) {
                    for (Object raw : bundle.itemVisuals) {
                        Map<?,?> item = (Map<?,?>)raw;
                        if (Long.valueOf(row.getKey()).equals(item.get("itemId"))) visual.put("item", raw);
                    }
                }
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

    private static Map<String,String> visualDependencies(String family, Map<String,String> dependencies) {
        Map<String,String> result = new TreeMap<>();
        for (Map.Entry<String,String> item : dependencies.entrySet()) {
            String role = item.getKey();
            boolean relevant = "scenery".equals(family) ? "asset.model".equals(role)
                : "npc".equals(family) ? role.contains("sprite") || role.contains("animation")
                : "ground-item".equals(family) ? role.contains("sprite")
                : "asset.library".equals(role) || "asset.sprite.custom".equals(role);
            if (relevant) result.put(role, item.getValue());
        }
        return result;
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
            for (Map.Entry<?,?> entry : ((Map<?,?>)value).entrySet()) sorted.put((String)entry.getKey(), canonical(entry.getValue()));
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
