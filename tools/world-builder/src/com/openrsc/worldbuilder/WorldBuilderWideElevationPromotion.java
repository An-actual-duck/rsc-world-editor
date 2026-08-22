package com.openrsc.worldbuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Losslessly promotes editable raw v1 sectors to the runtime's v2 encoding. */
final class WorldBuilderWideElevationPromotion {
	private WorldBuilderWideElevationPromotion() {
	}

	static boolean requiresPromotion(Path packageRoot)
		throws IOException, WorldBuilderDiscoveryException {
		Map<String,Object> manifest = WorldBuilderJsonDocuments.readObject(
			packageRoot.resolve("manifest.json"));
		for (Object raw : list(manifest, "terrainSectors")) {
			if (WorldBuilderRawLayeredTerrainCodec.V1_ENCODING.equals(
				text(map(raw), "encoding"))) return true;
		}
		return false;
	}

	static int promoteInPlace(Path packageRoot)
		throws IOException, WorldBuilderDiscoveryException, WorldBuilderContractException {
		Path root = packageRoot.toAbsolutePath().normalize();
		Map<String,Object> manifest = WorldBuilderJsonDocuments.readObject(
			root.resolve("manifest.json"));
		int promoted = 0;
		Set<String> paths = new HashSet<String>();
		for (Object raw : list(manifest, "terrainSectors")) {
			Map<String,Object> declaration = map(raw);
			String encoding = text(declaration, "encoding");
			if (!WorldBuilderRawLayeredTerrainCodec.supports(encoding)) {
				throw new WorldBuilderDiscoveryException(
					"Editable package has unsupported terrain encoding: " + encoding);
			}
			String relative = text(declaration, "path");
			Path payload = root.resolve(relative).normalize();
			if (!payload.startsWith(root) || !paths.add(relative)
				|| !Files.isRegularFile(payload, LinkOption.NOFOLLOW_LINKS)
				|| Files.isSymbolicLink(payload)) {
				throw new WorldBuilderDiscoveryException(
					"Editable terrain promotion path is unsafe or duplicated: " + relative);
			}
			byte[] bytes = Files.readAllBytes(payload);
			WorldBuilderRawLayeredTerrainCodec.requireDecodable(bytes, encoding);
			if (!WorldBuilderRawLayeredTerrainCodec.V1_ENCODING.equals(encoding)) continue;
			byte[] wide = WorldBuilderRawLayeredTerrainCodec.promoteV1(bytes);
			Files.write(payload, wide, StandardOpenOption.TRUNCATE_EXISTING,
				StandardOpenOption.WRITE);
			declaration.put("encoding", WorldBuilderRawLayeredTerrainCodec.V2_ENCODING);
			declaration.put("sha256", WorldBuilderHashes.sha256(payload));
			promoted++;
		}
		if (promoted > 0) {
			Files.write(root.resolve("manifest.json"),
				WorldBuilderJsonDocuments.pretty(manifest).getBytes(StandardCharsets.UTF_8),
				StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
		}
		return promoted;
	}

	@SuppressWarnings("unchecked")
	private static Map<String,Object> map(Object value)
		throws WorldBuilderDiscoveryException {
		if (!(value instanceof Map)) throw new WorldBuilderDiscoveryException(
			"Layered terrain declaration is not an object.");
		return (Map<String,Object>)value;
	}

	@SuppressWarnings("unchecked")
	private static List<Object> list(Map<String,Object> value, String key)
		throws WorldBuilderDiscoveryException {
		Object raw = value.get(key);
		if (!(raw instanceof List)) throw new WorldBuilderDiscoveryException(
			"Layered package field is not an array: " + key);
		return (List<Object>)raw;
	}

	private static String text(Map<String,Object> value, String key)
		throws WorldBuilderDiscoveryException {
		Object raw = value.get(key);
		if (!(raw instanceof String)) throw new WorldBuilderDiscoveryException(
			"Layered package field is not text: " + key);
		return (String)raw;
	}
}
