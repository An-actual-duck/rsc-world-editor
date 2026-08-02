package com.openrsc.worldbuilder;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/** Stable, machine-readable refusal codes used by adaptive contracts. */
final class WorldBuilderErrorCodes {
	static final String AMBIGUOUS_CONFIGURATION = "AMBIGUOUS_CONFIGURATION";
	static final String CAPABILITY_MISMATCH = "CAPABILITY_MISMATCH";
	static final String CONTRACT_IDENTITY_INVALID = "CONTRACT_IDENTITY_INVALID";
	static final String CONTRACT_KEYS_INVALID = "CONTRACT_KEYS_INVALID";
	static final String CONTRACT_LIMIT_EXCEEDED = "CONTRACT_LIMIT_EXCEEDED";
	static final String CONTRACT_VALUE_INVALID = "CONTRACT_VALUE_INVALID";
	static final String CONVERSION_BLOCKED = "CONVERSION_BLOCKED";
	static final String DEFINITION_MISMATCH = "DEFINITION_MISMATCH";
	static final String DISCOVERY_DRIFT = "DISCOVERY_DRIFT";
	static final String INVENTORY_DUPLICATE = "INVENTORY_DUPLICATE";
	static final String INVENTORY_LIMIT_EXCEEDED = "INVENTORY_LIMIT_EXCEEDED";
	static final String LOADER_INCOMPATIBLE = "LOADER_INCOMPATIBLE";
	static final String MALFORMED_JSON = "MALFORMED_JSON";
	static final String MALFORMED_SERVER = "MALFORMED_SERVER";
	static final String MAP_MISMATCH = "MAP_MISMATCH";
	static final String MUTATION_FAILED = "MUTATION_FAILED";
	static final String NO_SERVER = "NO_SERVER";
	static final String NO_TARGET = "NO_TARGET";
	static final String OFFLINE_REQUIRED = "OFFLINE_REQUIRED";
	static final String PROJECT_DETACHED = "PROJECT_DETACHED";
	static final String RECOVERY_REQUIRED = "RECOVERY_REQUIRED";
	static final String SOURCE_CORRUPT = "SOURCE_CORRUPT";
	static final String TARGET_DRIFT = "TARGET_DRIFT";
	static final String UNSAFE_PATH = "UNSAFE_PATH";
	static final String UNSUPPORTED_ADAPTER = "UNSUPPORTED_ADAPTER";
	static final String UNSUPPORTED_CONTRACT_VERSION = "UNSUPPORTED_CONTRACT_VERSION";
	static final String UNSUPPORTED_FORMAT = "UNSUPPORTED_FORMAT";

	private static final Set<String> STABLE = Collections.unmodifiableSet(
		new HashSet<String>(Arrays.asList(
			AMBIGUOUS_CONFIGURATION,
			CAPABILITY_MISMATCH,
			CONTRACT_IDENTITY_INVALID,
			CONTRACT_KEYS_INVALID,
			CONTRACT_LIMIT_EXCEEDED,
			CONTRACT_VALUE_INVALID,
			CONVERSION_BLOCKED,
			DEFINITION_MISMATCH,
			DISCOVERY_DRIFT,
			INVENTORY_DUPLICATE,
			INVENTORY_LIMIT_EXCEEDED,
			LOADER_INCOMPATIBLE,
			MALFORMED_JSON,
			MALFORMED_SERVER,
			MAP_MISMATCH,
			MUTATION_FAILED,
			NO_SERVER,
			NO_TARGET,
			OFFLINE_REQUIRED,
			PROJECT_DETACHED,
			RECOVERY_REQUIRED,
			SOURCE_CORRUPT,
			TARGET_DRIFT,
			UNSAFE_PATH,
			UNSUPPORTED_ADAPTER,
			UNSUPPORTED_CONTRACT_VERSION,
			UNSUPPORTED_FORMAT)));

	private WorldBuilderErrorCodes() {
	}

	static boolean isStable(String code) {
		return STABLE.contains(code);
	}
}
