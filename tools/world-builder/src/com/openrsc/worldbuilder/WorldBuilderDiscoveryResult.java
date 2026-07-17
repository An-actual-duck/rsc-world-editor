package com.openrsc.worldbuilder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Immutable, path-relative source manifest produced by read-only discovery. */
public final class WorldBuilderDiscoveryResult {
	public static final int PROJECT_SCHEMA_VERSION = 1;
	public static final String MANIFEST_TYPE = "world-builder-project-source";

	public static final class SourceFile {
		public final String logicalName;
		public final String relativePath;
		public final boolean present;
		public final long size;
		public final String sha256;

		SourceFile(String logicalName, String relativePath, boolean present, long size, String sha256) {
			this.logicalName = logicalName;
			this.relativePath = relativePath;
			this.present = present;
			this.size = size;
			this.sha256 = sha256;
		}
	}

	public final String layoutAdapter;
	public final String selectedConfig;
	public final String selectedConfigSha256;
	public final int clientVersion;
	public final int basedMapData;
	public final boolean memberWorld;
	public final boolean customLandscape;
	public final boolean wantMyWorld;
	public final int terrainSectorCount;
	public final String contentFingerprintSha256;
	public final String sourceFingerprintSha256;
	public final List<SourceFile> files;

	WorldBuilderDiscoveryResult(String layoutAdapter, String selectedConfig, String selectedConfigSha256,
		int clientVersion, int basedMapData, boolean memberWorld,
		boolean customLandscape, boolean wantMyWorld, int terrainSectorCount,
		String contentFingerprintSha256, String sourceFingerprintSha256,
		List<SourceFile> files) {
		this.layoutAdapter = layoutAdapter;
		this.selectedConfig = selectedConfig;
		this.selectedConfigSha256 = selectedConfigSha256;
		this.clientVersion = clientVersion;
		this.basedMapData = basedMapData;
		this.memberWorld = memberWorld;
		this.customLandscape = customLandscape;
		this.wantMyWorld = wantMyWorld;
		this.terrainSectorCount = terrainSectorCount;
		this.contentFingerprintSha256 = contentFingerprintSha256;
		this.sourceFingerprintSha256 = sourceFingerprintSha256;
		this.files = Collections.unmodifiableList(new ArrayList<SourceFile>(files));
	}

	public String toJson() {
		StringBuilder json = new StringBuilder(2048);
		json.append("{\n");
		field(json, 1, "schemaVersion", Integer.toString(PROJECT_SCHEMA_VERSION), false, true);
		field(json, 1, "manifestType", MANIFEST_TYPE, true, true);
		field(json, 1, "layoutAdapter", layoutAdapter, true, true);
		field(json, 1, "selectedConfig", selectedConfig, true, true);
		field(json, 1, "selectedConfigSha256", selectedConfigSha256, true, true);
		json.append("  \"configuration\": {\n");
		field(json, 2, "clientVersion", Integer.toString(clientVersion), false, true);
		field(json, 2, "basedMapData", Integer.toString(basedMapData), false, true);
		field(json, 2, "memberWorld", Boolean.toString(memberWorld), false, true);
		field(json, 2, "customLandscape", Boolean.toString(customLandscape), false, true);
		field(json, 2, "wantMyWorld", Boolean.toString(wantMyWorld), false, false);
		json.append("  },\n");
		field(json, 1, "terrainSectorCount", Integer.toString(terrainSectorCount), false, true);
		field(json, 1, "contentFingerprintSha256", contentFingerprintSha256, true, true);
		field(json, 1, "sourceFingerprintSha256", sourceFingerprintSha256, true, true);
		json.append("  \"files\": [\n");
		for (int index = 0; index < files.size(); index++) {
			SourceFile file = files.get(index);
			json.append("    {\n");
			field(json, 3, "logicalName", file.logicalName, true, true);
			field(json, 3, "relativePath", file.relativePath, true, true);
			field(json, 3, "present", Boolean.toString(file.present), false, true);
			field(json, 3, "size", Long.toString(file.size), false, true);
			field(json, 3, "sha256", file.sha256, true, false);
			json.append("    }");
			if (index + 1 < files.size()) {
				json.append(',');
			}
			json.append('\n');
		}
		json.append("  ]\n");
		json.append("}\n");
		return json.toString();
	}

	private static void field(StringBuilder output, int indent, String name,
		String value, boolean quote, boolean comma) {
		for (int index = 0; index < indent; index++) {
			output.append("  ");
		}
		output.append('"').append(escape(name)).append("\": ");
		if (quote) {
			output.append('"').append(escape(value)).append('"');
		} else {
			output.append(value);
		}
		if (comma) {
			output.append(',');
		}
		output.append('\n');
	}

	private static String escape(String value) {
		StringBuilder escaped = new StringBuilder(value.length() + 16);
		for (int index = 0; index < value.length(); index++) {
			char character = value.charAt(index);
			switch (character) {
				case '"': escaped.append("\\\""); break;
				case '\\': escaped.append("\\\\"); break;
				case '\b': escaped.append("\\b"); break;
				case '\f': escaped.append("\\f"); break;
				case '\n': escaped.append("\\n"); break;
				case '\r': escaped.append("\\r"); break;
				case '\t': escaped.append("\\t"); break;
				default:
					if (character < 0x20) {
						escaped.append(String.format("\\u%04x", (int) character));
					} else {
						escaped.append(character);
					}
			}
		}
		return escaped.toString();
	}
}
