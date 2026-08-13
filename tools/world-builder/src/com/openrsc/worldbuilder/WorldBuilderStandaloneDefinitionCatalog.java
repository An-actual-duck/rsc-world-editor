package com.openrsc.worldbuilder;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/** Builds content-neutral standalone authoring IDs from one exact packaged runtime. */
final class WorldBuilderStandaloneDefinitionCatalog {
	private static final String TILE_DEFINITIONS =
		"server/conf/server/defs/TileDef.xml";
	private static final String BOUNDARY_DEFINITIONS =
		"server/conf/server/defs/DoorDef.xml";
	private static final String SCENERY_DEFINITIONS =
		"server/conf/server/defs/GameObjectDef.xml";
	private static final List<JsonDefinitions> NPC_DEFINITIONS = Arrays.asList(
		new JsonDefinitions("server/conf/server/defs/NpcDefs.json", "npcs"),
		new JsonDefinitions("server/conf/server/defs/NpcDefsCustom.json", "npcs"));
	private static final List<JsonDefinitions> ITEM_DEFINITIONS = Arrays.asList(
		new JsonDefinitions("server/conf/server/defs/ItemDefs.json", "item"),
		new JsonDefinitions("server/conf/server/defs/ItemDefsCustom.json", "items"));
	private static final int MAX_DEFINITIONS = 65536;
	private static final String OPERATION = "standalone-definition-catalog";

	private WorldBuilderStandaloneDefinitionCatalog() {
	}

	static Map<String,Object> generate(
		WorldBuilderAdaptiveRuntimePreparer.SourceRuntime runtime, String catalogId)
		throws IOException, WorldBuilderContractException {
		if (runtime == null) throw problem(TILE_DEFINITIONS,
			"Standalone project creation has no verified packaged runtime.");
		List<Object> tiles = indexedXml(
			runtime, TILE_DEFINITIONS, "TileDef-array", "TileDef");
		List<Object> boundaries = indexedXml(
			runtime, BOUNDARY_DEFINITIONS, "DoorDef-array", "DoorDef");
		List<Object> scenery = indexedXml(
			runtime, SCENERY_DEFINITIONS, "GameObjectDef-array", "GameObjectDef");
		List<Object> npcs = appendedJsonIds(runtime, NPC_DEFINITIONS, "NPC");
		List<Object> items = explicitJsonIds(runtime, ITEM_DEFINITIONS, "item");
		int structuralOverlay =
			WorldBuilderCanonicalVoidTerrain.GROUND_OVERLAY_DEFINITION_ID;
		if (structuralOverlay < 0 || structuralOverlay >= tiles.size()) {
			throw problem(TILE_DEFINITIONS,
				"Packaged tile definitions omit the canonical structural-void overlay.");
		}

		Map<String,Object> catalog = new LinkedHashMap<String,Object>();
		catalog.put("schemaVersion", Long.valueOf(1L));
		catalog.put("manifestType", "world-builder-definition-catalog");
		catalog.put("catalogId", catalogId);
		catalog.put("tiles", tiles);
		catalog.put("boundaries", boundaries);
		catalog.put("scenery", scenery);
		catalog.put("npcs", npcs);
		catalog.put("groundItems", items);
		return catalog;
	}

	private static List<Object> indexedXml(
		WorldBuilderAdaptiveRuntimePreparer.SourceRuntime runtime,
		String relative, String rootName, String elementName)
		throws IOException, WorldBuilderContractException {
		Path path = runtime.verifiedSourcePath(relative);
		if (Files.size(path) > WorldBuilderContractLimits.MAX_JSON_BYTES) {
			throw problem(relative, "Packaged definition XML exceeds its bounded size.");
		}
		try (InputStream input = Files.newInputStream(path)) {
			DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
			factory.setXIncludeAware(false);
			factory.setExpandEntityReferences(false);
			factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
			factory.setFeature(
				"http://apache.org/xml/features/disallow-doctype-decl", true);
			factory.setFeature(
				"http://xml.org/sax/features/external-general-entities", false);
			factory.setFeature(
				"http://xml.org/sax/features/external-parameter-entities", false);
			Document document = factory.newDocumentBuilder().parse(input);
			Element root = document.getDocumentElement();
			if (root == null || !rootName.equals(root.getNodeName())) {
				throw problem(relative,
					"Packaged definition XML has the wrong document element.");
			}
			int count = 0;
			NodeList children = root.getChildNodes();
			for (int index = 0; index < children.getLength(); index++) {
				Node child = children.item(index);
				if (child.getNodeType() == Node.ELEMENT_NODE
					&& elementName.equals(child.getNodeName())) {
					if (++count > MAX_DEFINITIONS) throw tooMany(relative, elementName);
				}
			}
			if (count == 0) throw problem(relative,
				"Packaged definition XML contains no " + elementName + " entries.");
			return range(0, count);
		} catch (WorldBuilderContractException invalid) {
			throw invalid;
		} catch (Exception malformed) {
			throw problem(relative,
				"Packaged definition XML is malformed or unsafe.", malformed);
		}
	}

	private static List<Object> explicitJsonIds(
		WorldBuilderAdaptiveRuntimePreparer.SourceRuntime runtime,
		List<JsonDefinitions> sources, String label)
		throws IOException, WorldBuilderContractException {
		Set<Integer> ids = new TreeSet<Integer>();
		int count = 0;
		for (JsonDefinitions source : sources) {
			for (Object definition : singleArray(runtime, source)) {
				if (++count > MAX_DEFINITIONS) throw tooMany(source.relative, label);
				if (!(definition instanceof Map)) throw problem(source.relative,
					"Packaged " + label + " definition entry is not an object.");
				@SuppressWarnings("unchecked") Map<String,Object> value =
					(Map<String,Object>)definition;
				Object rawId = value.get("id");
				if (!(rawId instanceof Long)) throw problem(source.relative,
					"Packaged " + label + " definition has no integer ID.");
				long id = ((Long)rawId).longValue();
				if (id < 0L || id > Integer.MAX_VALUE) throw problem(source.relative,
					"Packaged " + label + " definition ID is outside its supported range.");
				ids.add(Integer.valueOf((int)id));
			}
		}
		if (ids.isEmpty()) throw problem(sources.get(0).relative,
			"Packaged runtime contains no " + label + " definitions.");
		List<Object> result = new ArrayList<Object>(ids.size());
		for (Integer id : ids) result.add(Long.valueOf(id.longValue()));
		return result;
	}

	private static List<Object> appendedJsonIds(
		WorldBuilderAdaptiveRuntimePreparer.SourceRuntime runtime,
		List<JsonDefinitions> sources, String label)
		throws IOException, WorldBuilderContractException {
		int count = 0;
		for (JsonDefinitions source : sources) {
			for (Object definition : singleArray(runtime, source)) {
				if (!(definition instanceof Map)) throw problem(source.relative,
					"Packaged " + label + " definition entry is not an object.");
				if (++count > MAX_DEFINITIONS) throw tooMany(source.relative, label);
			}
		}
		if (count == 0) throw problem(sources.get(0).relative,
			"Packaged runtime contains no " + label + " definitions.");
		return range(0, count);
	}

	private static List<?> singleArray(
		WorldBuilderAdaptiveRuntimePreparer.SourceRuntime runtime,
		JsonDefinitions source)
		throws IOException, WorldBuilderContractException {
		Path path = runtime.verifiedSourcePath(source.relative);
		Map<String,Object> root;
		try {
			root = WorldBuilderJsonDocuments.readObject(path);
		} catch (WorldBuilderDiscoveryException malformed) {
			throw problem(source.relative,
				"Packaged definition JSON is malformed or unsafe.", malformed);
		}
		if (root.size() != 1 || !root.containsKey(source.arrayName)) {
			throw problem(source.relative,
				"Packaged definition JSON has the wrong root definition array.");
		}
		Object raw = root.get(source.arrayName);
		if (!(raw instanceof List)) throw problem(source.relative,
			"Packaged definition JSON root value is not an array.");
		return (List<?>)raw;
	}

	private static List<Object> range(int first, int exclusiveEnd) {
		List<Object> result = new ArrayList<Object>(exclusiveEnd - first);
		for (int id = first; id < exclusiveEnd; id++) {
			result.add(Long.valueOf(id));
		}
		return result;
	}

	private static WorldBuilderContractException tooMany(
		String relative, String label) {
		return problem(relative,
			"Packaged " + label + " definition count exceeds 65,536 entries.");
	}

	private static WorldBuilderContractException problem(
		String relative, String message) {
		return problem(relative, message, null);
	}

	private static WorldBuilderContractException problem(
		String relative, String message, Throwable cause) {
		return new WorldBuilderContractException(
			WorldBuilderErrorCodes.DEFINITION_MISMATCH, OPERATION, relative, false,
			message,
			"Restore the exact content-neutral packaged runtime definitions and retry.",
			cause);
	}

	private static final class JsonDefinitions {
		final String relative;
		final String arrayName;

		JsonDefinitions(String relative, String arrayName) {
			this.relative = relative;
			this.arrayName = arrayName;
		}
	}
}
