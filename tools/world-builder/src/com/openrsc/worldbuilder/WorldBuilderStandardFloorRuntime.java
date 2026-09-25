package com.openrsc.worldbuilder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Content semantics are independent of the unchanged terrain byte encoding. */
final class WorldBuilderStandardFloorRuntime {
	private static final String ATTRIBUTE = "World-Builder-Floor-Semantics";
	private static final String CONTRACT = "standard-floors-v1";

	static boolean required(Path definitions) throws IOException {
		for (WorldBuilderTerrainDefinitionCatalog.TileDefinition tile :
			WorldBuilderTerrainDefinitionCatalog.readTiles(definitions).tiles) {
			if (tile.usesBaseColor() || tile.worldBuilderSourceOverlay != 0) return true;
		}
		return false;
	}

	static void require(Path serverJar, Path clientJar)
		throws IOException, WorldBuilderContractException {
		for (Path jar : new Path[] {serverJar, clientJar}) {
			if (!supports(jar)) throw new WorldBuilderContractException(
				WorldBuilderErrorCodes.LOADER_INCOMPATIBLE, "standard-floor-runtime",
				jar.toString(), false,
				"Selected floor definitions require standard-floors-v1 in both client and server.",
				"Select the current compatible managed runtime before preparing or launching this content.");
		}
	}

	private static boolean supports(Path jar) throws IOException {
		try (ZipFile archive = new ZipFile(jar.toFile())) {
			ZipEntry entry = archive.getEntry("META-INF/MANIFEST.MF");
			if (entry == null || entry.isDirectory() || entry.getSize() > 65536) return false;
			try (InputStream input = archive.getInputStream(entry);
				ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
				byte[] buffer = new byte[4096];
				for (int count; (count = input.read(buffer)) != -1;) {
					if (bytes.size() + count > 65536) return false;
					bytes.write(buffer, 0, count);
				}
				byte[] payload = bytes.toByteArray();
				int declarations = 0;
				for (String line : new String(payload, StandardCharsets.UTF_8).split("\\r?\\n")) {
					if (line.isEmpty()) break;
					if (line.regionMatches(true, 0, ATTRIBUTE + ":", 0, ATTRIBUTE.length() + 1)) declarations++;
				}
				if (declarations != 1) return false;
				Manifest manifest = new Manifest(new ByteArrayInputStream(payload));
				return CONTRACT.equals(manifest.getMainAttributes().getValue(ATTRIBUTE));
			}
		}
	}

	private WorldBuilderStandardFloorRuntime() { }
}
