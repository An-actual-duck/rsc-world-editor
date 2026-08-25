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

/** Bounded semantic parser for raw-byte floor and boundary definitions. */
final class WorldBuilderTerrainDefinitionCatalog {
	private static final int MAX_DEFINITIONS = 255;
	private static final int MAX_TEXT = 256;

	final List<TileDefinition> tiles;
	final List<BoundaryDefinition> boundaries;

	private WorldBuilderTerrainDefinitionCatalog(
		List<TileDefinition> tiles, List<BoundaryDefinition> boundaries) {
		this.tiles = Collections.unmodifiableList(
			new ArrayList<TileDefinition>(tiles));
		this.boundaries = Collections.unmodifiableList(
			new ArrayList<BoundaryDefinition>(boundaries));
	}

	static WorldBuilderTerrainDefinitionCatalog readTiles(Path path)
		throws IOException {
		Document document = document(path, "TileDef-array", "floor");
		List<Element> rows = rows(document, "TileDef", "floor");
		List<TileDefinition> result = new ArrayList<TileDefinition>();
		for (int id = 0; id < rows.size(); id++) {
			Element row = rows.get(id);
			result.add(new TileDefinition(
				integer(row, "colour", 0, "floor", id),
				integer(row, "unknown", 0, "floor", id),
				integer(row, "objectType", 0, "floor", id)));
		}
		return new WorldBuilderTerrainDefinitionCatalog(result,
			Collections.<BoundaryDefinition>emptyList());
	}

	static WorldBuilderTerrainDefinitionCatalog readBoundaries(Path path)
		throws IOException {
		Document document = document(path, "DoorDef-array", "boundary");
		List<Element> rows = rows(document, "DoorDef", "boundary");
		List<BoundaryDefinition> result = new ArrayList<BoundaryDefinition>();
		for (int id = 0; id < rows.size(); id++) {
			Element row = rows.get(id);
			result.add(new BoundaryDefinition(
				text(row, "name", "", "boundary", id),
				text(row, "description", "", "boundary", id),
				text(row, "command1", "", "boundary", id),
				text(row, "command2", "", "boundary", id),
				integer(row, "modelVar1", 0, "boundary", id),
				integer(row, "modelVar2", 0, "boundary", id),
				integer(row, "modelVar3", 0, "boundary", id),
				integer(row, "doorType", 0, "boundary", id),
				integer(row, "unknown", 0, "boundary", id)));
		}
		return new WorldBuilderTerrainDefinitionCatalog(
			Collections.<TileDefinition>emptyList(), result);
	}

	private static Document document(Path path, String rootName, String family)
		throws IOException {
		if (!Files.isRegularFile(path) || Files.size(path) < 1L
			|| Files.size(path) > WorldBuilderContractLimits.MAX_JSON_BYTES) {
			throw new IOException(family
				+ " definition XML is absent, empty, or oversized");
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
				throw new IOException(family
					+ " definition XML has the wrong document element");
			}
			return document;
		} catch (IOException invalid) {
			throw invalid;
		} catch (Exception malformed) {
			throw new IOException(family
				+ " definition XML is malformed or unsafe", malformed);
		}
	}

	private static List<Element> rows(
		Document document, String elementName, String family) throws IOException {
		List<Element> result = new ArrayList<Element>();
		NodeList children = document.getDocumentElement().getChildNodes();
		for (int index = 0; index < children.getLength(); index++) {
			Node child = children.item(index);
			if (child.getNodeType() != Node.ELEMENT_NODE
				|| !elementName.equals(child.getNodeName())) continue;
			if (result.size() >= MAX_DEFINITIONS) {
				throw new LimitException(family
					+ " definitions exceed raw IDs 0..254");
			}
			result.add((Element)child);
		}
		if (result.isEmpty()) throw new IOException(
			family + " definition XML contains no definitions");
		return result;
	}

	private static String text(Element parent, String name, String fallback,
		String family, int id) throws IOException {
		String value = uniqueText(parent, name, family, id);
		if (value == null) return fallback;
		value = value.trim();
		if (value.length() > MAX_TEXT) throw new IOException(
			family + " definition " + id + " has oversized " + name);
		return value;
	}

	private static int integer(Element parent, String name, int fallback,
		String family, int id) throws IOException {
		String text = uniqueText(parent, name, family, id);
		if (text == null || text.trim().isEmpty()) return fallback;
		String value = text.trim();
		if (!value.matches("-?[0-9]{1,10}")) throw new IOException(
			family + " definition " + id + " has malformed " + name);
		try {
			return Integer.parseInt(value);
		} catch (NumberFormatException overflow) {
			throw new IOException(
				family + " definition " + id + " has overflowing " + name,
				overflow);
		}
	}

	private static String uniqueText(Element parent, String name,
		String family, int id) throws IOException {
		NodeList values = parent.getElementsByTagName(name);
		if (values.getLength() > 1) throw new IOException(
			family + " definition " + id + " repeats " + name);
		return values.getLength() == 0 ? null : values.item(0).getTextContent();
	}

	static final class LimitException extends IOException {
		private static final long serialVersionUID = 1L;

		LimitException(String message) {
			super(message);
		}
	}

	static final class TileDefinition {
		final int colour;
		final int unknown;
		final int objectType;

		TileDefinition(int colour, int unknown, int objectType) {
			this.colour = colour;
			this.unknown = unknown;
			this.objectType = objectType;
		}
	}

	static final class BoundaryDefinition {
		final String name;
		final String description;
		final String command1;
		final String command2;
		final int modelVar1;
		final int modelVar2;
		final int modelVar3;
		final int doorType;
		final int unknown;

		BoundaryDefinition(String name, String description, String command1,
			String command2, int modelVar1, int modelVar2, int modelVar3,
			int doorType, int unknown) {
			this.name = name;
			this.description = description;
			this.command1 = command1;
			this.command2 = command2;
			this.modelVar1 = modelVar1;
			this.modelVar2 = modelVar2;
			this.modelVar3 = modelVar3;
			this.doorType = doorType;
			this.unknown = unknown;
		}
	}
}
