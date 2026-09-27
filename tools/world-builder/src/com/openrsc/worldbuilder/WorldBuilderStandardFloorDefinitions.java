package com.openrsc.worldbuilder;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Append-only project palette upgrade. Original bytes and assigned IDs are retained. */
final class WorldBuilderStandardFloorDefinitions {
	static byte[] extend(Path input) throws IOException {
		List<WorldBuilderTerrainDefinitionCatalog.TileDefinition> rows =
			WorldBuilderTerrainDefinitionCatalog.readTiles(input).tiles;
		byte[] original = Files.readAllBytes(input);
		String xml = StandardCharsets.UTF_8.newDecoder()
			.onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
			.decode(ByteBuffer.wrap(original)).toString();
		String closing = "</TileDef-array>";
		int end = xml.indexOf(closing);
		if (end < 0 || end != xml.lastIndexOf(closing))
			throw new IOException("Floor palette upgrade requires one TileDef-array closing tag.");
		StringBuilder added = new StringBuilder();
		int count = rows.size();
		for (int index = 0; index < rows.size(); index++) {
			WorldBuilderTerrainDefinitionCatalog.TileDefinition source = rows.get(index);
			if (source.usesBaseColor() || source.worldBuilderSourceOverlay != 0) continue;
			int raw = index + 1;
			if (raw >= 250 || source.objectType != 0 && source.objectType != 1)
				throw new IOException("Floor palette upgrade cannot extend reserved IDs or noncanonical walkability.");
			for (int blocking = 0; blocking <= 1; blocking++) {
				boolean present = false;
				for (WorldBuilderTerrainDefinitionCatalog.TileDefinition candidate : rows) {
					if (candidate.worldBuilderSourceOverlay == raw && candidate.objectType == blocking) {
						present = true; break;
					}
				}
				// Legacy 2/11 have extra projectile effects. Invisible floors need an explicit
				// semantic definition to remain pickable on every level.
				boolean reusable = raw != 2 && raw != 11
					&& !(source.colour == 12345678 && source.unknown != 4);
				if (present || reusable && source.objectType == blocking) continue;
				append(added, source.colour, source.unknown, blocking,
					"<worldBuilderSourceOverlay>" + raw + "</worldBuilderSourceOverlay>");
				count++;
			}
		}
		for (int blocking = 0; blocking <= 1; blocking++) {
			boolean present = false;
			for (WorldBuilderTerrainDefinitionCatalog.TileDefinition candidate : rows) {
				if (candidate.usesBaseColor() && candidate.objectType == blocking) { present = true; break; }
			}
			if (!present) {
				append(added, 0, 0, blocking, "<worldBuilderMaterial>base-color-v1</worldBuilderMaterial>");
				count++;
			}
		}
		if (count > 249) throw new IOException("Floor palette upgrade needs " + count
			+ " tile slots; this catalog has only 249 usable slots. No definitions were changed.");
		if (added.length() == 0) return original;
		return (xml.substring(0, end) + added + xml.substring(end)).getBytes(StandardCharsets.UTF_8);
	}

	private static void append(StringBuilder output, int colour, int kind, int blocking, String marker) {
		output.append("<TileDef><colour>").append(colour).append("</colour><unknown>")
			.append(kind).append("</unknown><objectType>").append(blocking)
			.append("</objectType>").append(marker).append("</TileDef>\n");
	}
	private WorldBuilderStandardFloorDefinitions() { }
}
