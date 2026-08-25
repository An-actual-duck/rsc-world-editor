package com.openrsc.worldbuilder;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
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
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Project-local fallback for scenery definitions whose concrete OB3 model is
 * absent from an inspectable target model archive.
 */
final class WorldBuilderSceneryModelProvider {
	static final String REPORT_PATH =
		"diagnostics/scenery-model-provider-warnings.json";
	private static final String DEFINITIONS =
		"server/conf/server/defs/GameObjectDef.xml";
	private static final String MODELS = "Client_Base/Cache/video/models.orsc";
	private static final List<String> PREFERRED_PLACEHOLDERS =
		Collections.unmodifiableList(Arrays.asList(
			"signpost", "signpost2", "dead_tree", "tree", "tree2", "rock", "crate"));

	private WorldBuilderSceneryModelProvider() {
	}

	static Result normalize(Path copiedTarget,
		WorldBuilderAdaptiveRuntimePreparer.SourceRuntime runtime)
		throws IOException, WorldBuilderContractException {
		Path definitionsPath = copiedTarget.resolve(DEFINITIONS);
		Path modelsPath = copiedTarget.resolve(MODELS);
		WorldBuilderSceneryDefinitionCatalog target =
			WorldBuilderSceneryDefinitionCatalog.read(definitionsPath);
		WorldBuilderSceneryDefinitionCatalog packaged =
			WorldBuilderSceneryDefinitionCatalog.read(runtime.verifiedSourcePath(
				"server/conf/server/defs/GameObjectDef.xml"));
		WorldBuilderNativeArchiveIndex targetModels =
			WorldBuilderNativeArchiveIndex.inspect(modelsPath);
		if (!targetModels.indexed()) return Result.unchanged();
		WorldBuilderNativeArchiveIndex packagedModels =
			WorldBuilderNativeArchiveIndex.inspect(runtime.verifiedSourcePath(
				"client/Cache/video/models.orsc"));

		TreeMap<Integer,Replacement> replacements = new TreeMap<Integer,Replacement>();
		for (int id = 0; id < target.definitions.size(); id++) {
			WorldBuilderSceneryDefinitionCatalog.Definition definition =
				target.definitions.get(id);
			String model = definition.modelName;
			if (model.isEmpty() || "na".equalsIgnoreCase(model)
				|| targetModels.containsValidModel(model + ".ob3")
				|| packagedGenerated(id, model, packaged, packagedModels)) continue;
			replacements.put(Integer.valueOf(id), new Replacement(
				id, definition.name, model, ""));
		}
		if (replacements.isEmpty()) return Result.unchanged();
		String placeholder = selectPlaceholder(target, targetModels);
		if (placeholder == null) throw problem(
			"No concrete model entry is available for a deterministic scenery placeholder.",
			"Restore one valid referenced OB3 model in models.orsc and recreate the project.");
		for (Map.Entry<Integer,Replacement> entry : replacements.entrySet()) {
			Replacement value = entry.getValue();
			entry.setValue(new Replacement(value.sceneryId, value.name,
				value.missingModelName, placeholder));
		}
		byte[] normalized = rewrite(definitionsPath, replacements);
		return new Result(normalized,
			new ArrayList<Replacement>(replacements.values()), placeholder);
	}

	private static boolean packagedGenerated(int id, String model,
		WorldBuilderSceneryDefinitionCatalog packaged,
		WorldBuilderNativeArchiveIndex packagedModels) {
		if (id >= packaged.definitions.size()) return false;
		String packagedName = packaged.definitions.get(id).modelName;
		return model.equalsIgnoreCase(packagedName)
			&& packagedModels.indexed()
			&& !packagedModels.contains(packagedName + ".ob3");
	}

	private static String selectPlaceholder(
		WorldBuilderSceneryDefinitionCatalog definitions,
		WorldBuilderNativeArchiveIndex models) {
		for (String preferred : PREFERRED_PLACEHOLDERS) {
			for (WorldBuilderSceneryDefinitionCatalog.Definition definition
				: definitions.definitions) {
				if (preferred.equalsIgnoreCase(definition.modelName)
					&& models.containsValidModel(definition.modelName + ".ob3")) {
					return definition.modelName;
				}
			}
		}
		for (WorldBuilderSceneryDefinitionCatalog.Definition definition
			: definitions.definitions) {
			if (!definition.modelName.isEmpty()
				&& !"na".equalsIgnoreCase(definition.modelName)
				&& models.containsValidModel(definition.modelName + ".ob3")) {
				return definition.modelName;
			}
		}
		return null;
	}

	private static byte[] rewrite(Path path,
		Map<Integer,Replacement> replacements) throws IOException {
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
			int definitionId = 0;
			NodeList children = root.getChildNodes();
			for (int index = 0; index < children.getLength(); index++) {
				Node child = children.item(index);
				if (child.getNodeType() != Node.ELEMENT_NODE
					|| !"GameObjectDef".equals(child.getNodeName())) continue;
				Replacement replacement = replacements.get(Integer.valueOf(definitionId++));
				if (replacement == null) continue;
				Element definition = (Element)child;
				NodeList models = definition.getElementsByTagName("objectModel");
				if (models.getLength() == 0) {
					Element model = document.createElement("objectModel");
					model.setTextContent(replacement.placeholderModelName);
					definition.appendChild(model);
				} else {
					models.item(0).setTextContent(replacement.placeholderModelName);
				}
			}
			TransformerFactory transformerFactory = TransformerFactory.newInstance();
			transformerFactory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
			transformerFactory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
			transformerFactory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
			Transformer transformer = transformerFactory.newTransformer();
			transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
			transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
			transformer.setOutputProperty(OutputKeys.INDENT, "no");
			ByteArrayOutputStream output = new ByteArrayOutputStream();
			transformer.transform(new DOMSource(document), new StreamResult(output));
			output.write('\n');
			return output.toByteArray();
		} catch (IOException failure) {
			throw failure;
		} catch (Exception failure) {
			throw new IOException("Could not normalize project-local scenery models", failure);
		}
	}

	static void writeReport(Path projectStage, Result result) throws IOException {
		if (!result.changed()) return;
		Path path = projectStage.resolve(REPORT_PATH).normalize();
		if (!path.startsWith(projectStage.toAbsolutePath().normalize())) {
			throw new IOException("Scenery model report escaped project stage");
		}
		Files.createDirectories(path.getParent());
		Map<String,Object> report = new LinkedHashMap<String,Object>();
		report.put("schemaVersion", Long.valueOf(1L));
		report.put("manifestType", "world-builder-scenery-model-provider-report");
		report.put("placeholderModelName", result.placeholderModelName);
		List<Object> warnings = new ArrayList<Object>();
		for (Replacement replacement : result.replacements) {
			warnings.add(replacement.json());
		}
		report.put("warnings", warnings);
		Files.write(path, WorldBuilderJsonDocuments.pretty(report)
			.getBytes(StandardCharsets.UTF_8));
	}

	static String projectWarningSummary(Path projectRoot) {
		if (projectRoot == null) return null;
		Path root = projectRoot.toAbsolutePath().normalize();
		Path report = root.resolve(REPORT_PATH).normalize();
		try {
			if (!report.startsWith(root)
				|| !Files.isRegularFile(report, LinkOption.NOFOLLOW_LINKS)
				|| Files.isSymbolicLink(report)
				|| Files.size(report) > WorldBuilderContractLimits.MAX_JSON_BYTES) return null;
			Map<String,Object> value =
				WorldBuilderJsonDocuments.readTargetDefinitionObject(report);
			Object raw = value.get("warnings");
			if (!(raw instanceof List) || ((List<?>)raw).isEmpty()) return null;
			List<Integer> ids = new ArrayList<Integer>();
			for (Object warning : (List<?>)raw) {
				if (!(warning instanceof Map)) continue;
				Object id = ((Map<?,?>)warning).get("sceneryId");
				if (id instanceof Number) ids.add(Integer.valueOf(((Number)id).intValue()));
			}
			if (ids.isEmpty()) return null;
			return "\n\nScenery model warning: missing model visuals for scenery IDs "
				+ ids + " were replaced inside this project with a visible placeholder."
				+ " The selected server remains unchanged.\nDetails: " + report;
		} catch (Exception ignored) {
			return null;
		}
	}

	private static WorldBuilderContractException problem(
		String message, String nextStep) {
		return new WorldBuilderContractException(
			WorldBuilderErrorCodes.DEFINITION_MISMATCH, "scenery-model-provider",
			MODELS, false, message, nextStep);
	}

	static final class Result {
		final byte[] definitionsOverride;
		final List<Replacement> replacements;
		final String placeholderModelName;

		Result(byte[] definitionsOverride, List<Replacement> replacements,
			String placeholderModelName) {
			this.definitionsOverride = definitionsOverride;
			this.replacements = Collections.unmodifiableList(
				new ArrayList<Replacement>(replacements));
			this.placeholderModelName = placeholderModelName;
		}

		static Result unchanged() {
			return new Result(null, Collections.<Replacement>emptyList(), "");
		}

		boolean changed() {
			return definitionsOverride != null;
		}
	}

	private static final class Replacement {
		final int sceneryId;
		final String name;
		final String missingModelName;
		final String placeholderModelName;

		Replacement(int sceneryId, String name, String missingModelName,
			String placeholderModelName) {
			this.sceneryId = sceneryId;
			this.name = name;
			this.missingModelName = missingModelName;
			this.placeholderModelName = placeholderModelName;
		}

		Map<String,Object> json() {
			Map<String,Object> value = new LinkedHashMap<String,Object>();
			value.put("code", "SCENERY_MODEL_PLACEHOLDER");
			value.put("sceneryId", Long.valueOf(sceneryId));
			value.put("name", name);
			value.put("missingModelName", missingModelName);
			value.put("placeholderModelName", placeholderModelName);
			return value;
		}
	}
}
