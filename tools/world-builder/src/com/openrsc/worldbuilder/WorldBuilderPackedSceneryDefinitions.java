package com.openrsc.worldbuilder;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/** Exact footprint metadata needed to decode scenery embedded in packed terrain. */
final class WorldBuilderPackedSceneryDefinitions {
	static final String RELATIVE_PATH = "server/conf/server/defs/GameObjectDef.xml";
	private static final int MAX_DEFINITIONS = 65536;
	private static final int MAX_DIMENSION = 128;

	private final List<Footprint> definitions;

	private WorldBuilderPackedSceneryDefinitions(List<Footprint> definitions) {
		this.definitions = Collections.unmodifiableList(
			new ArrayList<Footprint>(definitions));
	}

	static WorldBuilderPackedSceneryDefinitions read(
		WorldBuilderPackedConversionSource source) throws WorldBuilderContractException {
		source.requireInput("server-definition.scenery", RELATIVE_PATH);
		Path path = source.target.requiredFile(RELATIVE_PATH);
		try {
			if (Files.size(path) > WorldBuilderContractLimits.MAX_JSON_BYTES) {
				throw blocked("Packed scenery definition XML exceeds its bounded size.");
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
				if (root == null || !"GameObjectDef-array".equals(root.getNodeName())) {
					throw blocked("Packed scenery definition XML has the wrong document element.");
				}
				List<Footprint> result = new ArrayList<Footprint>();
				NodeList children = root.getChildNodes();
				for (int index = 0; index < children.getLength(); index++) {
					Node child = children.item(index);
					if (child.getNodeType() != Node.ELEMENT_NODE
						|| !"GameObjectDef".equals(child.getNodeName())) continue;
					if (result.size() >= MAX_DEFINITIONS) {
						throw blocked("Packed scenery definitions exceed 65,536 entries.");
					}
					Element definition = (Element)child;
					result.add(new Footprint(
						dimension(definition, "width", result.size()),
						dimension(definition, "height", result.size())));
				}
				if (result.isEmpty()) {
					throw blocked("Packed scenery definition XML contains no definitions.");
				}
				return new WorldBuilderPackedSceneryDefinitions(result);
			}
		} catch (WorldBuilderContractException invalid) {
			throw invalid;
		} catch (Exception malformed) {
			throw blocked("Packed scenery definition XML is malformed or unsafe.", malformed);
		}
	}

	Footprint require(int id) throws WorldBuilderContractException {
		if (id < 0 || id >= definitions.size()) {
			throw blocked("Embedded packed scenery references definition ID " + id
				+ " outside GameObjectDef.xml.");
		}
		return definitions.get(id);
	}

	private static int dimension(Element definition, String name, int id)
		throws WorldBuilderContractException {
		NodeList children = definition.getChildNodes();
		String text = null;
		for (int index = 0; index < children.getLength(); index++) {
			Node child = children.item(index);
			if (child.getNodeType() != Node.ELEMENT_NODE
				|| !name.equals(child.getNodeName())) continue;
			if (text != null) throw blocked("Packed scenery definition " + id
				+ " repeats " + name + ".");
			text = child.getTextContent();
		}
		if (text == null || !text.trim().matches("[0-9]{1,10}")) {
			throw blocked("Packed scenery definition " + id
				+ " has no exact nonnegative " + name + ".");
		}
		try {
			int value = Integer.parseInt(text.trim());
			if (value > MAX_DIMENSION) throw blocked("Packed scenery definition " + id
				+ " has " + name + " beyond the 128-tile conversion bound.");
			return value;
		} catch (NumberFormatException overflow) {
			throw blocked("Packed scenery definition " + id
				+ " has an overflowing " + name + ".", overflow);
		}
	}

	private static WorldBuilderContractException blocked(String message) {
		return blocked(message, null);
	}

	private static WorldBuilderContractException blocked(
		String message, Throwable cause) {
		return new WorldBuilderContractException(
			WorldBuilderErrorCodes.CONVERSION_BLOCKED, "convert-packed",
			RELATIVE_PATH, false, message,
			"Correct the exact packed scenery definitions and rediscover the target.",
			cause);
	}

	static final class Footprint {
		final int width;
		final int height;

		Footprint(int width, int height) {
			this.width = width;
			this.height = height;
		}
	}
}
