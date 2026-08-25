package com.openrsc.worldbuilder;

import java.io.IOException;
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

/**
 * Bounded parser for the declarative OpenRSC scenery definition contract used
 * by both packed conversion and project content reconciliation.
 */
final class WorldBuilderSceneryDefinitionCatalog {
	private static final int MAX_DEFINITIONS = 65536;
	private static final int MAX_DIMENSION = 128;
	private static final int MAX_TEXT = 256;

	final List<Definition> definitions;

	private WorldBuilderSceneryDefinitionCatalog(List<Definition> definitions) {
		this.definitions = Collections.unmodifiableList(
			new ArrayList<Definition>(definitions));
	}

	static WorldBuilderSceneryDefinitionCatalog read(Path path) throws IOException {
		if (!Files.isRegularFile(path) || Files.size(path) < 1L
			|| Files.size(path) > WorldBuilderContractLimits.MAX_JSON_BYTES) {
			throw new IOException("scenery definition XML is absent, empty, or oversized");
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
				throw new IOException("scenery definition XML has the wrong document element");
			}
			List<Definition> result = new ArrayList<Definition>();
			NodeList children = root.getChildNodes();
			for (int index = 0; index < children.getLength(); index++) {
				Node child = children.item(index);
				if (child.getNodeType() != Node.ELEMENT_NODE
					|| !"GameObjectDef".equals(child.getNodeName())) continue;
				if (result.size() >= MAX_DEFINITIONS) {
					throw new IOException("scenery definitions exceed 65,536 entries");
				}
				result.add(definition((Element)child, result.size()));
			}
			if (result.isEmpty()) throw new IOException(
				"scenery definition XML contains no definitions");
			return new WorldBuilderSceneryDefinitionCatalog(result);
		} catch (IOException invalid) {
			throw invalid;
		} catch (Exception malformed) {
			throw new IOException("scenery definition XML is malformed or unsafe", malformed);
		}
	}

	Definition require(int id) throws IOException {
		if (id < 0 || id >= definitions.size()) {
			throw new IOException("scenery definition ID " + id + " is outside the catalog");
		}
		return definitions.get(id);
	}

	private static Definition definition(Element element, int id) throws IOException {
		return new Definition(
			text(element, "name", "", id),
			text(element, "objectModel", "", id),
			integer(element, "type", 0, id, Integer.MIN_VALUE, Integer.MAX_VALUE),
			integer(element, "width", 1, id, 0, MAX_DIMENSION),
			integer(element, "height", 1, id, 0, MAX_DIMENSION),
			integer(element, "groundItemVar", 0, id,
				Integer.MIN_VALUE, Integer.MAX_VALUE));
	}

	private static String text(Element parent, String name, String fallback, int id)
		throws IOException {
		String value = uniqueText(parent, name, id);
		if (value == null) return fallback;
		value = value.trim();
		if (value.length() > MAX_TEXT) throw new IOException(
			"scenery definition " + id + " has oversized " + name);
		return value;
	}

	private static int integer(Element parent, String name, int fallback, int id,
		int minimum, int maximum) throws IOException {
		String text = uniqueText(parent, name, id);
		if (text == null || text.trim().isEmpty()) return fallback;
		String value = text.trim();
		if (!value.matches("-?[0-9]{1,10}")) throw new IOException(
			"scenery definition " + id + " has malformed " + name);
		try {
			int parsed = Integer.parseInt(value);
			if (parsed < minimum || parsed > maximum) throw new IOException(
				"scenery definition " + id + " has out-of-range " + name);
			return parsed;
		} catch (NumberFormatException overflow) {
			throw new IOException(
				"scenery definition " + id + " has overflowing " + name, overflow);
		}
	}

	private static String uniqueText(Element parent, String name, int id)
		throws IOException {
		NodeList values = parent.getElementsByTagName(name);
		if (values.getLength() > 1) throw new IOException(
			"scenery definition " + id + " repeats " + name);
		return values.getLength() == 0 ? null : values.item(0).getTextContent();
	}

	static final class Definition {
		final String name;
		final String modelName;
		final int type;
		final int width;
		final int height;
		final int groundItemVar;

		Definition(String name, String modelName, int type, int width, int height,
			int groundItemVar) {
			this.name = name;
			this.modelName = modelName;
			this.type = type;
			this.width = width;
			this.height = height;
			this.groundItemVar = groundItemVar;
		}
	}
}
