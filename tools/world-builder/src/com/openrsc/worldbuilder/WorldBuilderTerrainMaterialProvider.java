package com.openrsc.worldbuilder;

import java.io.ByteArrayInputStream;
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
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/** Project-local closure for numeric floor and wall texture materials. */
final class WorldBuilderTerrainMaterialProvider {
	static final String REPORT_PATH =
		"diagnostics/terrain-material-provider-warnings.json";
	private static final String TILE_DEFINITIONS =
		"server/conf/server/defs/TileDef.xml";
	private static final String BOUNDARY_DEFINITIONS =
		"server/conf/server/defs/DoorDef.xml";
	private static final String CUSTOM_SPRITES =
		"Client_Base/Cache/video/Custom_Sprites.osar";
	private static final int TRANSPARENT = 12345678;
	private static final int MAX_TEXTURE_ID = 4095;
	private static final int MAX_ARCHIVE_ENTRIES = 8192;
	private static final long MAX_EXPANDED_BYTES = 512L * 1024L * 1024L;

	private WorldBuilderTerrainMaterialProvider() {
	}

	static Result normalize(Path copiedTarget, byte[] priorArchiveOverride)
		throws IOException, WorldBuilderContractException {
		WorldBuilderTerrainDefinitionCatalog tiles =
			WorldBuilderTerrainDefinitionCatalog.readTiles(
				copiedTarget.resolve(TILE_DEFINITIONS));
		WorldBuilderTerrainDefinitionCatalog boundaries =
			WorldBuilderTerrainDefinitionCatalog.readBoundaries(
				copiedTarget.resolve(BOUNDARY_DEFINITIONS));
		TreeMap<Integer,List<Reference>> required = new TreeMap<Integer,List<Reference>>();
		for (int id = 0; id < tiles.tiles.size(); id++) {
			require(required, tiles.tiles.get(id).colour, "floor", id, "colour");
		}
		for (int id = 0; id < boundaries.boundaries.size(); id++) {
			WorldBuilderTerrainDefinitionCatalog.BoundaryDefinition definition =
				boundaries.boundaries.get(id);
			require(required, definition.modelVar2, "boundary", id, "modelVar2");
			require(required, definition.modelVar3, "boundary", id, "modelVar3");
		}
		for (Integer id : required.keySet()) if (id.intValue() > MAX_TEXTURE_ID) {
			throw problem(WorldBuilderErrorCodes.CONTRACT_LIMIT_EXCEEDED,
				"Texture material ID " + id + " exceeds the bounded project domain 0.."
					+ MAX_TEXTURE_ID + ".",
				"Use a negative packed colour or a texture ID within 0.."
					+ MAX_TEXTURE_ID + ".");
		}

		Path path = copiedTarget.resolve(CUSTOM_SPRITES);
		byte[] source = priorArchiveOverride == null
			? Files.readAllBytes(path) : priorArchiveOverride;
		Archive archive = Archive.read(source);
		Subspace textures = archive.subspace("textures");
		if (textures == null) {
			textures = new Subspace("textures", new ArrayList<Entry>());
			archive.subspaces.add(textures);
		}
		TreeMap<Integer,Entry> indexed = new TreeMap<Integer,Entry>();
		for (Entry entry : textures.entries) {
			if (!entry.name.matches("0|[1-9][0-9]{0,3}")) throw problem(
				WorldBuilderErrorCodes.UNSUPPORTED_FORMAT,
				"Texture subspace entry is not a canonical numeric ID: "
					+ entry.name + ".",
				"Rename texture entries to the contiguous decimal IDs used by the client.");
			int id = Integer.parseInt(entry.name);
			if (!entry.sprite.isTexture()) throw problem(
				WorldBuilderErrorCodes.UNSUPPORTED_FORMAT,
				"Texture subspace entry " + id
					+ " is not a complete 64x64 or 128x128 texture.",
				"Rebuild the exact texture entry with one complete square renderer texture.");
			if (id > MAX_TEXTURE_ID || indexed.put(Integer.valueOf(id), entry) != null) {
				throw problem(WorldBuilderErrorCodes.CONTRACT_LIMIT_EXCEEDED,
					"Texture subspace IDs are duplicated or exceed 0.."
						+ MAX_TEXTURE_ID + ".",
					"Keep one bounded texture entry for each canonical decimal ID.");
			}
		}
		int maximum = Math.max(0, indexed.isEmpty() ? 0 : indexed.lastKey().intValue());
		if (!required.isEmpty()) maximum = Math.max(maximum, required.lastKey().intValue());
		Set<Integer> added = new TreeSet<Integer>();
		for (int id = 0; id <= maximum; id++) {
			if (!indexed.containsKey(Integer.valueOf(id))) {
				Entry replacement = new Entry(Integer.toString(id), placeholder(),
					new SpriteShape(64, 64, 0, 64, 64));
				indexed.put(Integer.valueOf(id), replacement);
				added.add(Integer.valueOf(id));
			}
		}
		if (archive.entryCountWithout(textures) + indexed.size()
			> MAX_ARCHIVE_ENTRIES) throw problem(
			WorldBuilderErrorCodes.CONTRACT_LIMIT_EXCEEDED,
			"Texture placeholder closure would exceed 8,192 OSAR entries.",
			"Reduce the source archive or its maximum referenced texture ID.");
		if (added.isEmpty()) return Result.unchanged();
		textures.entries.clear();
		textures.entries.addAll(indexed.values());
		return new Result(archive.write(), added, required);
	}

	private static void require(Map<Integer,List<Reference>> required,
		int material, String family, int definitionId, String field) {
		if (material < 0 || material == TRANSPARENT) return;
		Integer key = Integer.valueOf(material);
		List<Reference> references = required.get(key);
		if (references == null) {
			references = new ArrayList<Reference>();
			required.put(key, references);
		}
		references.add(new Reference(family, definitionId, field));
	}

	static void writeReport(Path projectStage, Result result) throws IOException {
		if (!result.changed()) return;
		Path path = projectStage.resolve(REPORT_PATH).normalize();
		if (!path.startsWith(projectStage.toAbsolutePath().normalize())) {
			throw new IOException("Terrain material report escaped project stage");
		}
		Files.createDirectories(path.getParent());
		Map<String,Object> report = new LinkedHashMap<String,Object>();
		report.put("schemaVersion", Long.valueOf(1L));
		report.put("manifestType", "world-builder-terrain-material-provider-report");
		report.put("placeholder", "magenta-black-checkerboard-64x64-v1");
		List<Object> warnings = new ArrayList<Object>();
		for (Integer textureId : result.addedTextureIds) {
			Map<String,Object> warning = new LinkedHashMap<String,Object>();
			warning.put("code", "TEXTURE_MATERIAL_PLACEHOLDER");
			warning.put("textureId", Long.valueOf(textureId.longValue()));
			List<Object> references = new ArrayList<Object>();
			List<Reference> values = result.references.get(textureId);
			if (values != null) for (Reference reference : values) {
				references.add(reference.json());
			}
			warning.put("references", references);
			warnings.add(warning);
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
				Object id = ((Map<?,?>)warning).get("textureId");
				if (id instanceof Number) ids.add(
					Integer.valueOf(((Number)id).intValue()));
			}
			if (ids.isEmpty()) return null;
			return "\n\nTerrain material warning: missing texture IDs " + ids
				+ " use a visible project-local checkerboard placeholder."
				+ " The selected server remains unchanged.\nDetails: " + report;
		} catch (Exception ignored) {
			return null;
		}
	}

	private static byte[] placeholder() throws IOException {
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		output.write(0); // ordinary sprite
		output.write(1); // one frame
		output.write(1); // two palette colours minus one
		output.write(new byte[] {(byte)255, 0, (byte)255, 32, 32, 32});
		writeShort(output, 64); writeShort(output, 64);
		output.write(0); // unshifted
		writeShort(output, 0); writeShort(output, 0);
		writeShort(output, 64); writeShort(output, 64);
		for (int y = 0; y < 64; y++) for (int x = 0; x < 64; x++) {
			output.write(((x / 8) + (y / 8)) % 2);
		}
		return output.toByteArray();
	}

	private static void writeShort(ByteArrayOutputStream output, int value) {
		output.write(value >>> 8 & 0xff);
		output.write(value & 0xff);
	}

	private static WorldBuilderContractException problem(
		String code, String message, String nextStep) {
		return new WorldBuilderContractException(code, "terrain-material-provider",
			CUSTOM_SPRITES, false, message, nextStep);
	}

	static final class Result {
		final byte[] customArchiveOverride;
		final Set<Integer> addedTextureIds;
		final Map<Integer,List<Reference>> references;

		Result(byte[] customArchiveOverride, Set<Integer> addedTextureIds,
			Map<Integer,List<Reference>> references) {
			this.customArchiveOverride = customArchiveOverride;
			this.addedTextureIds = Collections.unmodifiableSet(
				new TreeSet<Integer>(addedTextureIds));
			TreeMap<Integer,List<Reference>> copied =
				new TreeMap<Integer,List<Reference>>();
			for (Map.Entry<Integer,List<Reference>> entry : references.entrySet()) {
				copied.put(entry.getKey(), Collections.unmodifiableList(
					new ArrayList<Reference>(entry.getValue())));
			}
			this.references = Collections.unmodifiableMap(copied);
		}

		static Result unchanged() {
			return new Result(null, Collections.<Integer>emptySet(),
				Collections.<Integer,List<Reference>>emptyMap());
		}

		boolean changed() {
			return customArchiveOverride != null;
		}
	}

	private static final class Reference {
		final String family;
		final int definitionId;
		final String field;

		Reference(String family, int definitionId, String field) {
			this.family = family;
			this.definitionId = definitionId;
			this.field = field;
		}

		Map<String,Object> json() {
			Map<String,Object> value = new LinkedHashMap<String,Object>();
			value.put("family", family);
			value.put("definitionId", Long.valueOf(definitionId));
			value.put("field", field);
			return value;
		}
	}

	private static final class Archive {
		final List<Subspace> subspaces;

		Archive(List<Subspace> subspaces) {
			this.subspaces = subspaces;
		}

		static Archive read(byte[] compressed)
			throws IOException, WorldBuilderContractException {
			byte[] expanded;
			try (InputStream input = new GZIPInputStream(
				new ByteArrayInputStream(compressed));
				ByteArrayOutputStream output = new ByteArrayOutputStream()) {
				byte[] buffer = new byte[8192];
				long total = 0L;
				for (int count; (count = input.read(buffer)) >= 0;) {
					if (count == 0) continue;
					total += count;
					if (total > MAX_EXPANDED_BYTES) throw problem(
						WorldBuilderErrorCodes.CONTRACT_LIMIT_EXCEEDED,
						"Custom sprite archive expands beyond 512 MiB.",
						"Reduce the exact project-local custom sprite archive.");
					output.write(buffer, 0, count);
				}
				expanded = output.toByteArray();
			} catch (WorldBuilderContractException invalid) {
				throw invalid;
			} catch (IOException malformed) {
				throw problem(WorldBuilderErrorCodes.UNSUPPORTED_FORMAT,
					"Custom sprite archive is not a readable GZIP OSAR.",
					"Provide the exact target OSAR produced for the runtime Unpacker.");
			}
			try {
				Input cursor = new Input(expanded);
				int subspaceCount = cursor.u8();
				if (subspaceCount < 1) throw new IllegalArgumentException("no subspaces");
				List<Subspace> subspaces = new ArrayList<Subspace>();
				Set<String> foldedSubspaces = new TreeSet<String>();
				int totalEntries = 0;
				for (int index = 0; index < subspaceCount; index++) {
					String name = cursor.name();
					requireName(name);
					if (!foldedSubspaces.add(name.toLowerCase(Locale.ROOT))) {
						throw new IllegalArgumentException("case-colliding subspace");
					}
					int count = cursor.u16();
					List<Entry> entries = new ArrayList<Entry>();
					Set<String> foldedEntries = new TreeSet<String>();
					for (int entryIndex = 0; entryIndex < count; entryIndex++) {
						if (++totalEntries > MAX_ARCHIVE_ENTRIES) {
							throw new IllegalArgumentException("too many entries");
						}
						String entryName = cursor.name();
						requireName(entryName);
						if (!foldedEntries.add(entryName.toLowerCase(Locale.ROOT))) {
							throw new IllegalArgumentException("case-colliding entry");
						}
						int start = cursor.offset;
						SpriteShape sprite = cursor.sprite();
						entries.add(new Entry(entryName,
							Arrays.copyOfRange(expanded, start, cursor.offset), sprite));
					}
					subspaces.add(new Subspace(name, entries));
				}
				if (cursor.remaining() != 0) {
					throw new IllegalArgumentException("trailing data");
				}
				return new Archive(subspaces);
			} catch (RuntimeException malformed) {
				String reason = malformed.getMessage() == null
					? "unclassified structural failure" : malformed.getMessage();
				throw problem(WorldBuilderErrorCodes.UNSUPPORTED_FORMAT,
					"Custom sprite archive structure is invalid: " + reason + ".",
					"Rebuild a bounded GZIP OSAR with unique portable names and complete frames.");
			}
		}

		Subspace subspace(String name) {
			for (Subspace subspace : subspaces) if (name.equals(subspace.name)) {
				return subspace;
			}
			return null;
		}

		int entryCountWithout(Subspace selected) {
			int result = 0;
			for (Subspace subspace : subspaces) if (subspace != selected) {
				result += subspace.entries.size();
			}
			return result;
		}

		byte[] write() throws IOException {
			if (subspaces.size() > 255) throw new IOException("too many OSAR subspaces");
			ByteArrayOutputStream raw = new ByteArrayOutputStream();
			raw.write(subspaces.size());
			for (Subspace subspace : subspaces) {
				writeName(raw, subspace.name);
				writeShort(raw, subspace.entries.size());
				for (Entry entry : subspace.entries) {
					writeName(raw, entry.name);
					raw.write(entry.payload);
				}
			}
			ByteArrayOutputStream result = new ByteArrayOutputStream();
			try (GZIPOutputStream gzip = new GZIPOutputStream(result)) {
				gzip.write(raw.toByteArray());
			}
			return result.toByteArray();
		}
	}

	private static void requireName(String name) {
		if (!name.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")) {
			throw new IllegalArgumentException("unsafe OSAR name");
		}
	}

	private static void writeName(ByteArrayOutputStream output, String name)
		throws IOException {
		output.write(name.getBytes(StandardCharsets.ISO_8859_1));
		output.write(0);
	}

	private static final class Subspace {
		final String name;
		final List<Entry> entries;

		Subspace(String name, List<Entry> entries) {
			this.name = name;
			this.entries = entries;
		}
	}

	private static final class Entry {
		final String name;
		final byte[] payload;
		final SpriteShape sprite;

		Entry(String name, byte[] payload, SpriteShape sprite) {
			this.name = name;
			this.payload = payload;
			this.sprite = sprite;
		}
	}

	private static final class SpriteShape {
		final int width;
		final int height;
		final int shifted;
		final int boundWidth;
		final int boundHeight;

		SpriteShape(int width, int height, int shifted,
			int boundWidth, int boundHeight) {
			this.width = width;
			this.height = height;
			this.shifted = shifted;
			this.boundWidth = boundWidth;
			this.boundHeight = boundHeight;
		}

		boolean isTexture() {
			return width == height && width == boundWidth
				&& height == boundHeight && (width == 64 || width == 128);
		}
	}

	private static final class Input {
		final byte[] bytes;
		int offset;

		Input(byte[] bytes) {
			this.bytes = bytes;
		}

		int u8() {
			if (offset >= bytes.length) throw new IllegalArgumentException("truncated OSAR");
			return bytes[offset++] & 0xff;
		}

		int u16() {
			return u8() << 8 | u8();
		}

		String name() {
			StringBuilder value = new StringBuilder();
			while (true) {
				int next = u8();
				if (next == 0) break;
				if (value.length() >= 128) throw new IllegalArgumentException("long OSAR name");
				value.append((char)next);
			}
			if (value.length() == 0) throw new IllegalArgumentException("empty OSAR name");
			return value.toString();
		}

		SpriteShape sprite() {
			int type = u8();
			if (type < 0 || type > 4) throw new IllegalArgumentException("entry type");
			if (type >= 1 && type <= 3 && u8() > 11) {
				throw new IllegalArgumentException("entry layer");
			}
			int frames = u8();
			if (frames < 1) throw new IllegalArgumentException("empty entry");
			int palette = u8() + 1;
			skip(palette * 3);
			SpriteShape first = null;
			for (int frame = 0; frame < frames; frame++) {
				int width = u16();
				int height = u16();
				int shifted = u8();
				u16(); u16();
				int boundWidth = u16();
				int boundHeight = u16();
				if (width < 1 || height < 1 || shifted > 1) {
					throw new IllegalArgumentException("frame dimensions");
				}
				if (first == null) first = new SpriteShape(
					width, height, shifted, boundWidth, boundHeight);
				long pixels = (long)width * (long)height;
				if (pixels > 16777216L) throw new IllegalArgumentException("frame pixels");
				for (long pixel = 0; pixel < pixels; pixel++) {
					if (u8() >= palette) {
						throw new IllegalArgumentException("pixel palette index");
					}
				}
			}
			return first;
		}

		void skip(int count) {
			if (count < 0 || count > remaining()) {
				throw new IllegalArgumentException("truncated OSAR payload");
			}
			offset += count;
		}

		int remaining() {
			return bytes.length - offset;
		}
	}
}
