package com.openrsc.worldbuilder;

import java.nio.file.Path;

/** Exact footprint metadata needed to decode scenery embedded in packed terrain. */
final class WorldBuilderPackedSceneryDefinitions {
	static final String RELATIVE_PATH = "server/conf/server/defs/GameObjectDef.xml";

	private final WorldBuilderSceneryDefinitionCatalog definitions;

	private WorldBuilderPackedSceneryDefinitions(
		WorldBuilderSceneryDefinitionCatalog definitions) {
		this.definitions = definitions;
	}

	static WorldBuilderPackedSceneryDefinitions read(
		WorldBuilderPackedConversionSource source) throws WorldBuilderContractException {
		source.requireInput("server-definition.scenery", RELATIVE_PATH);
		Path path = source.target.requiredFile(RELATIVE_PATH);
		try {
			return new WorldBuilderPackedSceneryDefinitions(
				WorldBuilderSceneryDefinitionCatalog.read(path));
		} catch (Exception malformed) {
			throw blocked("Packed scenery definition XML is malformed or unsafe: "
				+ malformed.getMessage() + ".", malformed);
		}
	}

	Footprint require(int id) throws WorldBuilderContractException {
		try {
			WorldBuilderSceneryDefinitionCatalog.Definition definition =
				definitions.require(id);
			return new Footprint(definition.width, definition.height);
		} catch (Exception missing) {
			throw blocked("Embedded packed scenery references definition ID " + id
				+ " outside GameObjectDef.xml.", missing);
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
