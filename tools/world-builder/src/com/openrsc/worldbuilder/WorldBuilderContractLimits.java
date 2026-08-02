package com.openrsc.worldbuilder;

/** Shared resource ceilings for adaptive World Builder contracts. */
final class WorldBuilderContractLimits {
	static final long MAX_JSON_BYTES = 16L * 1024L * 1024L;
	static final int MAX_JSON_DEPTH = 32;
	static final int MAX_JSON_VALUES = 1_000_000;
	static final int MAX_PATH_UTF8_BYTES = 1024;
	static final int MAX_PATH_SEGMENT_UTF8_BYTES = 255;
	static final int MAX_IDENTIFIER_CHARS = 128;
	static final int MAX_DISPLAY_CHARS = 512;
	static final int MAX_DETAIL_CHARS = 4096;
	static final int MAX_INVENTORY_ENTRIES = 8192;
	static final long MAX_INVENTORY_FILE_BYTES = 4L * 1024L * 1024L * 1024L;
	static final long MAX_INVENTORY_TOTAL_BYTES = 64L * 1024L * 1024L * 1024L;
	static final int MAX_PROJECTS = 4096;
	static final int MAX_CONFIGURATION_CANDIDATES = 64;
	static final int MAX_ADAPTERS = 64;
	static final int MAX_ISSUES = 4096;
	static final int MAX_MUTATIONS = 4096;
	static final int MAX_PLACEMENT_SUMMARIES = 65536;

	private WorldBuilderContractLimits() {
	}
}
