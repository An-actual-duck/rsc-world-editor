package com.openrsc.worldbuilder;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Optional exact stock inputs, retained in the historical tree, never executed
 * or selected as current composition inputs. Private state is not in this seal. */
final class WorldBuilderPreservationStockInputs {
	private static final String RESOURCE = "/com/openrsc/worldbuilder/preservation-c0102e-stock-inputs.json";
	private static final String HASH = "58f6774193699a511541a51088ad776642d18475defcddc7386d7ec4e0c07695";
	private static volatile Map<String,Map<String,Object>> records;

	private WorldBuilderPreservationStockInputs() { }

	static boolean matches(Path path, WorldBuilderReadOnlyTarget.FileState state)
		throws WorldBuilderContractException {
		Map<String,Object> record = records().get(state.relativePath);
		if (record == null || !Long.valueOf(state.size).equals(record.get("size"))
			|| !state.sha256.equals(record.get("sha256"))) return false;
		try {
			int mode = ((Number)Files.getAttribute(path, "unix:mode", LinkOption.NOFOLLOW_LINKS)).intValue() & 07777;
			int expected = Integer.parseInt((String)record.get("mode"), 8) & 0777;
			return mode == expected || mode == (expected | 0020);
		} catch (IOException | UnsupportedOperationException | IllegalArgumentException unsafe) {
			return false;
		}
	}

	@SuppressWarnings("unchecked")
	private static Map<String,Map<String,Object>> records() throws WorldBuilderContractException {
		Map<String,Map<String,Object>> cached = records;
		if (cached != null) return cached;
		try (InputStream input = WorldBuilderPreservationStockInputs.class.getResourceAsStream(RESOURCE)) {
			if (input == null) throw new IOException("missing stock metadata");
			ByteArrayOutputStream output = new ByteArrayOutputStream();
			byte[] buffer = new byte[4096]; int count;
			while ((count = input.read(buffer)) >= 0) {
				output.write(buffer, 0, count);
				if (output.size() > 131072) throw new IOException("oversized stock metadata");
			}
			byte[] bytes = output.toByteArray();
			if (!HASH.equals(WorldBuilderHashes.sha256(bytes))) throw new IOException("changed stock metadata");
			Map<String,Object> document = WorldBuilderJsonDocuments.readObject(bytes, RESOURCE);
			Map<String,Map<String,Object>> loaded = new LinkedHashMap<String,Map<String,Object>>();
			for (Object raw : (List<?>)document.get("records")) {
				Map<String,Object> record = (Map<String,Object>)raw;
				loaded.put((String)record.get("path"), record);
			}
			records = loaded;
			return loaded;
		} catch (Exception failure) {
			throw new WorldBuilderContractException(WorldBuilderErrorCodes.CONTRACT_IDENTITY_INVALID,
				"preservation-source-intake", "stock-metadata", false,
				"The sealed historical stock input metadata is missing or changed.",
				"Restore the exact packaged Editor build; target metadata cannot replace its authority.", failure);
		}
	}
}
