package com.openrsc.worldbuilder;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

/** Canonical family ledger proving packed source placements reach the package. */
final class WorldBuilderDiscoveryReconciliation {
	static final String FILE_NAME = "discovery-reconciliation.json";
	static final String PROJECT_RELATIVE_PATH =
		"diagnostics/discovery-reconciliation-v1.json";
	private static final String ZERO_HASH =
		"0000000000000000000000000000000000000000000000000000000000000000";

	private WorldBuilderDiscoveryReconciliation() {
	}

	static Map<String,Object> packed(
		WorldBuilderPackedConversionModel model,
		WorldBuilderGenericLayeredPackage validated,
		String adapterId,
		String sourceFingerprintSha256,
		String outputPackageFingerprintSha256)
		throws WorldBuilderContractException {
		Map<String,Object> value = new LinkedHashMap<String,Object>();
		value.put("schemaVersion", Long.valueOf(1L));
		value.put("manifestType", "world-builder-discovery-reconciliation");
		value.put("adapterId", adapterId);
		value.put("representation", "packed");
		value.put("sourceFingerprintSha256", sourceFingerprintSha256);
		value.put("outputPackageFingerprintSha256", outputPackageFingerprintSha256);
		value.put("families", model.reconciliationFamilies(validated));
		value.put("status", "matched");
		value.put("issues", new ArrayList<Object>());
		value.put("reconciliationFingerprintSha256", ZERO_HASH);
		bindFingerprint(value);
		WorldBuilderAdaptiveContracts.validateParsed(
			WorldBuilderAdaptiveContracts.Kind.DISCOVERY_RECONCILIATION, value);
		return value;
	}

	private static void bindFingerprint(Map<String,Object> document) {
		document.put("reconciliationFingerprintSha256", ZERO_HASH);
		document.put("reconciliationFingerprintSha256", WorldBuilderHashes.sha256(
			WorldBuilderJsonDocuments.canonical(document)
				.getBytes(StandardCharsets.UTF_8)));
	}
}
