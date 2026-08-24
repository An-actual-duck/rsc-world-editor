package com.openrsc.worldbuilder;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import javax.imageio.ImageIO;

/**
 * Resilient, read-only consumer for neutral item-visual provider manifests.
 *
 * <p>The provider is never executable input. Strictly valid records are
 * normalized into the runtime's existing item-visual evidence. A safely
 * rejected manifest, record, or visual becomes a deterministic project-local
 * placeholder and warning instead of preventing a project from launching.</p>
 */
final class WorldBuilderItemVisualProvider {
	static final String TYPE = "world-builder-item-visual-mapping";
	static final String REPORT_PATH = "diagnostics/item-visual-provider-warnings.json";
	static final String CUSTOM_ROLE = "asset.sprite.custom";
	private static final String GENERATED_SUBSPACE = "world_builder_provider";
	private static final String ZERO_HASH =
		"0000000000000000000000000000000000000000000000000000000000000000";
	private static final long MAX_MANIFEST_BYTES = 16L * 1024L * 1024L;
	private static final long MAX_ASSET_BYTES = 256L * 1024L * 1024L;
	private static final long MAX_EXPANDED_BYTES = 512L * 1024L * 1024L;
	private static final int MAX_ITEMS = 65536;
	private static final int MAX_ENTRIES = 8192;
	private static final Set<String> KEYS = Collections.unmodifiableSet(
		new HashSet<String>(Arrays.asList("itemId", "name", "logicalSpriteLocation",
			"sourceRole", "sourceAsset", "sourceAssetSha256", "authenticSpriteId",
			"customSpriteSubspace", "customSpriteEntry", "externalPng",
			"pictureMask", "blueMask")));

	private WorldBuilderItemVisualProvider() {
	}

	static Result consume(Path requestedManifest, Path copiedTarget,
		Set<Integer> required, Map<Integer,String> targetNames)
		throws IOException, WorldBuilderContractException {
		TreeMap<Integer,Resolved> resolved = new TreeMap<Integer,Resolved>();
		List<Warning> warnings = new ArrayList<Warning>();
		Path manifest = requestedManifest == null ? null
			: requestedManifest.toAbsolutePath().normalize();
		String manifestHash = ZERO_HASH;
		Map<String,Object> root = null;
		Path providerRoot = null;
		if (manifest == null) {
			warnings.add(new Warning(-1, "PROVIDER_NOT_SELECTED",
				"No neutral item-visual provider was selected; unresolved items use placeholders."));
		} else {
			try {
				if (!Files.isRegularFile(manifest, LinkOption.NOFOLLOW_LINKS)
					|| Files.isSymbolicLink(manifest) || Files.size(manifest) < 1L
					|| Files.size(manifest) > MAX_MANIFEST_BYTES) {
					throw new Rejected("PROVIDER_MANIFEST_UNSAFE",
						"Provider manifest is not one bounded, unlinked regular JSON file.");
				}
				manifestHash = WorldBuilderHashes.sha256(manifest);
				root = WorldBuilderJsonDocuments.readObject(manifest);
				providerRoot = manifest.getParent();
				if (providerRoot == null || !Files.isDirectory(providerRoot,
					LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(providerRoot)) {
					throw new Rejected("PROVIDER_ROOT_UNSAFE",
						"Provider root is not a real directory.");
				}
				validateRoot(root);
			} catch (Rejected invalid) {
				warnings.add(new Warning(-1, invalid.code, invalid.getMessage()));
				root = null;
			} catch (WorldBuilderDiscoveryException malformed) {
				warnings.add(new Warning(-1, "PROVIDER_MANIFEST_MALFORMED",
					"Provider manifest is malformed JSON and was ignored."));
				root = null;
			} catch (IOException unreadable) {
				warnings.add(new Warning(-1, "PROVIDER_MANIFEST_UNREADABLE",
					"Provider manifest could not be read stably and was ignored."));
				root = null;
			}
		}

		Map<Path,Osar> osars = new HashMap<Path,Osar>();
		Map<Path,Map<Integer,String>> authentic =
			new HashMap<Path,Map<Integer,String>>();
		Map<Path,Set<String>> authenticSelections =
			new TreeMap<Path,Set<String>>();
		if (root != null) {
			@SuppressWarnings("unchecked") List<Object> records = (List<Object>)root.get("itemVisuals");
			long previousId = -1L;
			for (Object raw : records) {
				int itemId = safeItemId(raw);
				if (itemId < 0 || itemId <= previousId) {
					warnings.add(new Warning(itemId,
						itemId == previousId ? "PROVIDER_DUPLICATE_ITEM" : "PROVIDER_ORDER_INVALID",
						"Provider item IDs must be unique and strictly ascending; the manifest was ignored."));
					root = null;
					break;
				}
				previousId = itemId;
			}
		}
		if (root != null) {
			@SuppressWarnings("unchecked") List<Object> records = (List<Object>)root.get("itemVisuals");
			Set<Integer> seen = new HashSet<Integer>();
			for (Object raw : records) {
				int hintedId = safeItemId(raw);
				try {
					ProviderRecord record = parseRecord(raw, providerRoot);
					if (!seen.add(Integer.valueOf(record.itemId))) {
						throw new Rejected("PROVIDER_DUPLICATE_ITEM",
							"Provider repeats item ID " + record.itemId + ".");
					}
					if (!required.contains(Integer.valueOf(record.itemId))) continue;
					Resolved value = resolve(record, osars, authentic);
					if (value.authenticAsset != null) {
						Set<String> selected = authenticSelections.get(value.authenticAsset);
						if (selected == null) {
							selected = new TreeSet<String>();
							authenticSelections.put(value.authenticAsset, selected);
						}
						selected.add(value.authenticEntry);
					}
					resolved.put(Integer.valueOf(record.itemId), value);
				} catch (Rejected invalid) {
					if (hintedId >= 0 && required.contains(Integer.valueOf(hintedId))) {
						warnings.add(new Warning(hintedId, invalid.code, invalid.getMessage()));
					} else if (hintedId < 0) {
						warnings.add(new Warning(-1, invalid.code, invalid.getMessage()));
					}
				}
			}
		}

		byte[] authenticOverride = null;
		if (!authenticSelections.isEmpty()) {
			try {
				authenticOverride = mergeAuthenticArchive(copiedTarget.resolve(
					"Client_Base/Cache/video/Authentic_Sprites.orsc"), authenticSelections);
			} catch (IOException invalid) {
				authenticOverride = null;
				fallbackAuthentic(resolved, warnings, "PROVIDER_AUTHENTIC_MERGE_FAILED",
					"Selected authentic sprite could not be merged safely; a placeholder was generated.");
			} catch (WorldBuilderContractException invalid) {
				authenticOverride = null;
				fallbackAuthentic(resolved, warnings, "PROVIDER_AUTHENTIC_MERGE_UNSAFE",
					"Selected authentic sprite collides with target archive content; a placeholder was generated.");
			}
		}
		String generatedSubspace = generatedSubspace(copiedTarget.resolve(
			"Client_Base/Cache/video/Custom_Sprites.osar"));
		List<GeneratedEntry> generated = new ArrayList<GeneratedEntry>();
		List<Object> canonical = new ArrayList<Object>();
		List<ItemReport> items = new ArrayList<ItemReport>();
		for (Integer boxed : new TreeSet<Integer>(required)) {
			int itemId = boxed.intValue();
			Resolved value = resolved.get(boxed);
			String targetName = targetNames.containsKey(boxed)
				? targetNames.get(boxed) : "Item " + itemId;
			if (value == null) {
				String entry = "item_" + itemId;
				generated.add(new GeneratedEntry(entry, placeholderEntry(itemId)));
				Map<String,Object> visual = canonicalCustom(itemId, generatedSubspace,
					entry, 0, 0);
				canonical.add(visual);
				warnings.add(new Warning(itemId, "PROVIDER_VISUAL_PLACEHOLDER",
					"No usable provider visual exists for item " + itemId
						+ " (" + targetName + "); a deterministic placeholder was generated."));
				items.add(new ItemReport(itemId, targetName, "placeholder", "unresolved",
					"unresolved/" + itemId));
			} else if (value.spriteEntry != null) {
				String entry = "item_" + itemId;
				generated.add(new GeneratedEntry(entry, value.spriteEntry));
				canonical.add(canonicalCustom(itemId, generatedSubspace, entry,
					value.pictureMask, value.blueMask));
				items.add(new ItemReport(itemId, value.name, "resolved",
					value.sourceRole, value.logicalLocation));
			} else {
				canonical.add(canonicalAuthentic(itemId, value.authenticSpriteId,
					value.pictureMask, value.blueMask));
				items.add(new ItemReport(itemId, value.name, "resolved",
					value.sourceRole, value.logicalLocation));
			}
		}
		Collections.sort(warnings);
		byte[] customOverride = generated.isEmpty() ? null
			: appendGeneratedSubspace(copiedTarget.resolve(
				"Client_Base/Cache/video/Custom_Sprites.osar"), generatedSubspace, generated);
		return new Result(canonical, customOverride, authenticOverride,
			manifestHash, items, warnings);
	}

	private static void fallbackAuthentic(Map<Integer,Resolved> resolved,
		List<Warning> warnings, String code, String message) {
		for (Map.Entry<Integer,Resolved> entry :
			new ArrayList<Map.Entry<Integer,Resolved>>(resolved.entrySet())) {
			if (entry.getValue().authenticAsset == null) continue;
			resolved.remove(entry.getKey());
			warnings.add(new Warning(entry.getKey().intValue(), code, message));
		}
	}

	private static void validateRoot(Map<String,Object> root) throws Rejected {
		if (!root.keySet().equals(new HashSet<String>(Arrays.asList(
			"schemaVersion", "manifestType", "itemVisuals")))) {
			throw new Rejected("PROVIDER_SCHEMA_INVALID",
				"Provider manifest must contain only schemaVersion, manifestType, and itemVisuals.");
		}
		if (!Long.valueOf(1L).equals(root.get("schemaVersion"))
			|| !TYPE.equals(root.get("manifestType"))
			|| !(root.get("itemVisuals") instanceof List)
			|| ((List<?>)root.get("itemVisuals")).size() > MAX_ITEMS) {
			throw new Rejected("PROVIDER_SCHEMA_INVALID",
				"Provider identity, itemVisuals type, or record count is unsupported.");
		}
	}

	private static ProviderRecord parseRecord(Object raw, Path providerRoot)
		throws Rejected {
		if (!(raw instanceof Map)) throw new Rejected("PROVIDER_RECORD_INVALID",
			"Provider itemVisuals entries must be objects.");
		@SuppressWarnings("unchecked") Map<String,Object> value = (Map<String,Object>)raw;
		if (!value.keySet().equals(KEYS)) throw new Rejected("PROVIDER_RECORD_INVALID",
			"Provider item visual has missing or unfamiliar fields.");
		int itemId = integer(value.get("itemId"), 0, 65535, "itemId");
		String name = text(value.get("name"), 1, 256, "name");
		String role = text(value.get("sourceRole"), 1, 64, "sourceRole");
		int pictureMask = integer(value.get("pictureMask"), Integer.MIN_VALUE,
			Integer.MAX_VALUE, "pictureMask");
		int blueMask = integer(value.get("blueMask"), Integer.MIN_VALUE,
			Integer.MAX_VALUE, "blueMask");
		if ("unresolved".equals(role)) {
			for (String field : Arrays.asList("logicalSpriteLocation", "sourceAsset",
				"sourceAssetSha256", "authenticSpriteId", "customSpriteSubspace",
				"customSpriteEntry", "externalPng")) requireNull(value.get(field), field);
			if (pictureMask != 0 || blueMask != 0) throw new Rejected(
				"PROVIDER_UNRESOLVED_INVALID", "Unresolved provider masks must default to zero.");
			throw new Rejected("PROVIDER_UNRESOLVED",
				"Provider explicitly marks item " + itemId + " unresolved.");
		}
		String logical = text(value.get("logicalSpriteLocation"), 1, 512,
			"logicalSpriteLocation");
		if (!("asset.sprite.authentic".equals(role) || CUSTOM_ROLE.equals(role)
			|| "asset.spritepack".equals(role) || "asset.sprite.external".equals(role))) {
			throw new Rejected("PROVIDER_ROLE_UNKNOWN",
				"Provider sourceRole is unfamiliar for item " + itemId + ".");
		}
		String sourceAsset = text(value.get("sourceAsset"), 1, 512, "sourceAsset");
		String sourceHash = hash(value.get("sourceAssetSha256"), "sourceAssetSha256");
		Path asset = safeAsset(providerRoot, sourceAsset);
		boolean rolePathMatches = "asset.sprite.authentic".equals(role)
				&& "assets/Authentic_Sprites.orsc".equals(sourceAsset)
			|| CUSTOM_ROLE.equals(role)
				&& "assets/Custom_Sprites.osar".equals(sourceAsset)
			|| "asset.spritepack".equals(role)
				&& sourceAsset.startsWith("assets/spritepacks/")
				&& sourceAsset.endsWith(".osar")
			|| "asset.sprite.external".equals(role)
				&& sourceAsset.startsWith("assets/external-items/")
				&& sourceAsset.toLowerCase(Locale.ROOT).endsWith(".png");
		if (!rolePathMatches) throw new Rejected("PROVIDER_ASSET_ROLE_MISMATCH",
			"Provider sourceAsset is outside the canonical directory for its sourceRole.");
		Object authentic = value.get("authenticSpriteId");
		Object subspace = value.get("customSpriteSubspace");
		Object entry = value.get("customSpriteEntry");
		Object png = value.get("externalPng");
		int spriteId = -1;
		ExternalPng external = null;
		if ("asset.sprite.authentic".equals(role)) {
			spriteId = integer(authentic, 0, 65535, "authenticSpriteId");
			requireNull(subspace, "customSpriteSubspace");
			requireNull(entry, "customSpriteEntry");
			requireNull(png, "externalPng");
			if (!logical.equals("authentic/" + spriteId)) throw new Rejected(
				"PROVIDER_LOCATION_INVALID", "Authentic logicalSpriteLocation is inconsistent.");
		} else if (CUSTOM_ROLE.equals(role) || "asset.spritepack".equals(role)) {
			requireNull(authentic, "authenticSpriteId");
			String sub = portable(text(subspace, 1, 128, "customSpriteSubspace"));
			String ent = portable(text(entry, 1, 128, "customSpriteEntry"));
			String prefix = CUSTOM_ROLE.equals(role) ? "custom/" : "spritepack/";
			if (!logical.equals(prefix + sub + "/" + ent)) throw new Rejected(
				"PROVIDER_LOCATION_INVALID", "Custom logicalSpriteLocation is inconsistent.");
			requireNull(png, "externalPng");
			subspace = sub;
			entry = ent;
		} else {
			requireNull(authentic, "authenticSpriteId");
			requireNull(subspace, "customSpriteSubspace");
			requireNull(entry, "customSpriteEntry");
			external = parseExternal(png, sourceAsset, sourceHash);
			if (!logical.equals("external/" + sourceAsset)) throw new Rejected(
				"PROVIDER_LOCATION_INVALID", "External logicalSpriteLocation is inconsistent.");
		}
		return new ProviderRecord(itemId, name, logical, role, asset, sourceHash,
			spriteId, subspace == null ? null : (String)subspace,
			entry == null ? null : (String)entry, external, pictureMask, blueMask);
	}

	private static Resolved resolve(ProviderRecord record, Map<Path,Osar> osars,
		Map<Path,Map<Integer,String>> authentic) throws Rejected {
		try {
			if (Files.size(record.sourceAsset) < 1L
				|| Files.size(record.sourceAsset) > MAX_ASSET_BYTES
				|| !record.sourceAssetSha256.equals(WorldBuilderHashes.sha256(record.sourceAsset))) {
				throw new Rejected("PROVIDER_ASSET_HASH_MISMATCH",
					"Provider asset is missing, oversized, or differs from its bound SHA-256.");
			}
			if ("asset.sprite.authentic".equals(record.sourceRole)) {
				Map<Integer,String> ids = authentic.get(record.sourceAsset);
				if (ids == null) {
					ids = authenticEntries(record.sourceAsset);
					authentic.put(record.sourceAsset, ids);
				}
				String entry = ids.get(Integer.valueOf(record.authenticSpriteId));
				if (entry == null) throw new Rejected(
					"PROVIDER_ASSET_ENTRY_MISSING", "Authentic archive lacks the declared sprite ID.");
				return Resolved.authentic(record, record.sourceAsset, entry);
			}
			if (CUSTOM_ROLE.equals(record.sourceRole) || "asset.spritepack".equals(record.sourceRole)) {
				Osar archive = osars.get(record.sourceAsset);
				if (archive == null) {
					archive = readOsar(record.sourceAsset);
					osars.put(record.sourceAsset, archive);
				}
				byte[] entry = archive.entries.get(record.customSpriteSubspace + "/"
					+ record.customSpriteEntry);
				if (entry == null) throw new Rejected("PROVIDER_ASSET_ENTRY_MISSING",
					"OSAR lacks the declared subspace and entry.");
				return Resolved.sprite(record, entry);
			}
			return Resolved.sprite(record, pngEntry(record.sourceAsset, record.external));
		} catch (Rejected invalid) {
			throw invalid;
		} catch (IOException unreadable) {
			throw new Rejected("PROVIDER_ASSET_UNREADABLE",
				"Provider asset could not be read stably.");
		}
	}

	private static Path safeAsset(Path root, String relative) throws Rejected {
		String safe = portable(relative);
		if (!safe.startsWith("assets/")) throw new Rejected("PROVIDER_ASSET_PATH_UNSAFE",
			"Provider assets must be beneath the assets directory.");
		Path value = root.resolve(safe).normalize();
		if (!value.startsWith(root) || !Files.isRegularFile(value, LinkOption.NOFOLLOW_LINKS)
			|| Files.isSymbolicLink(value)) throw new Rejected("PROVIDER_ASSET_MISSING",
				"Provider asset is absent or not a safe regular file: " + safe + ".");
		return value;
	}

	private static String portable(String value) throws Rejected {
		try {
			WorldBuilderPortablePath.require(value, "item-visual-provider");
			return value;
		} catch (WorldBuilderContractException unsafe) {
			throw new Rejected("PROVIDER_ASSET_PATH_UNSAFE",
				"Provider path is not a portable contained relative path.");
		}
	}

	private static ExternalPng parseExternal(Object raw, String sourceAsset,
		String sourceHash) throws Rejected {
		if (!(raw instanceof Map)) throw new Rejected("PROVIDER_EXTERNAL_INVALID",
			"External PNG specification is missing.");
		@SuppressWarnings("unchecked") Map<String,Object> value = (Map<String,Object>)raw;
		if (!value.keySet().equals(new HashSet<String>(Arrays.asList(
			"relativePath", "sha256", "width", "height")))) throw new Rejected(
			"PROVIDER_EXTERNAL_INVALID", "External PNG specification fields are invalid.");
		String path = portable(text(value.get("relativePath"), 1, 512, "relativePath"));
		String hash = hash(value.get("sha256"), "sha256");
		int width = integer(value.get("width"), 1, 4096, "width");
		int height = integer(value.get("height"), 1, 4096, "height");
		if (!path.equals(sourceAsset) || !hash.equals(sourceHash)
			|| (long)width * height > 16777216L) throw new Rejected(
			"PROVIDER_EXTERNAL_INVALID", "External PNG path, hash, or bounds are inconsistent.");
		return new ExternalPng(width, height);
	}

	private static byte[] pngEntry(Path path, ExternalPng spec)
		throws IOException, Rejected {
		BufferedImage image = ImageIO.read(path.toFile());
		if (image == null || image.getWidth() != spec.width || image.getHeight() != spec.height) {
			throw new Rejected("PROVIDER_EXTERNAL_INVALID",
				"External PNG is unreadable or its dimensions differ from the manifest.");
		}
		LinkedHashMap<Integer,Integer> palette = new LinkedHashMap<Integer,Integer>();
		byte[] pixels = new byte[image.getWidth() * image.getHeight()];
		int index = 0;
		for (int y = 0; y < image.getHeight(); y++) for (int x = 0; x < image.getWidth(); x++) {
			int color = image.getRGB(x, y) & 0xffffff;
			Integer paletteIndex = palette.get(Integer.valueOf(color));
			if (paletteIndex == null) {
				if (palette.size() == 256) throw new Rejected("PROVIDER_EXTERNAL_PALETTE",
					"External PNG uses more than 256 RGB colors.");
				paletteIndex = Integer.valueOf(palette.size());
				palette.put(Integer.valueOf(color), paletteIndex);
			}
			pixels[index++] = (byte)paletteIndex.intValue();
		}
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		DataOutputStream output = new DataOutputStream(bytes);
		output.writeByte(0);
		output.writeByte(1);
		output.writeByte(palette.size() - 1);
		for (Integer color : palette.keySet()) {
			output.writeByte(color.intValue() >>> 16);
			output.writeByte(color.intValue() >>> 8);
			output.writeByte(color.intValue());
		}
		output.writeShort(image.getWidth());
		output.writeShort(image.getHeight());
		output.writeByte(0);
		output.writeShort(0);
		output.writeShort(0);
		output.writeShort(image.getWidth());
		output.writeShort(image.getHeight());
		output.write(pixels);
		output.flush();
		return bytes.toByteArray();
	}

	private static Map<Integer,String> authenticEntries(Path path) throws IOException, Rejected {
		Map<Integer,String> ids = new HashMap<Integer,String>();
		Set<String> folded = new HashSet<String>();
		try (ZipFile archive = new ZipFile(path.toFile())) {
			java.util.Enumeration<? extends ZipEntry> entries = archive.entries();
			int count = 0;
			while (entries.hasMoreElements()) {
				ZipEntry entry = entries.nextElement();
				if (++count > MAX_ENTRIES || entry.isDirectory()) throw new Rejected(
					"PROVIDER_AUTHENTIC_INVALID", "Authentic archive inventory is unsafe or excessive.");
				String name = entry.getName();
				portable(name);
				if (!folded.add(name.toLowerCase(Locale.ROOT))) throw new Rejected(
					"PROVIDER_AUTHENTIC_INVALID", "Authentic archive names collide.");
				String leaf = name.substring(name.lastIndexOf('/') + 1);
				if (leaf.endsWith(".dat")) leaf = leaf.substring(0, leaf.length() - 4);
				if (leaf.matches("[0-9]{1,5}")) {
					Integer id = Integer.valueOf(Integer.parseInt(leaf));
					if (ids.put(id, name) != null) throw new Rejected(
						"PROVIDER_AUTHENTIC_INVALID", "Authentic archive repeats a sprite ID.");
				}
			}
		}
		return ids;
	}

	private static byte[] mergeAuthenticArchive(Path target,
		Map<Path,Set<String>> selections)
		throws IOException, WorldBuilderContractException {
		TreeMap<String,byte[]> entries = new TreeMap<String,byte[]>();
		Set<String> folded = new HashSet<String>();
		readZipEntries(target, null, entries, folded);
		for (Map.Entry<Path,Set<String>> selection : selections.entrySet()) {
			readZipEntries(selection.getKey(), selection.getValue(), entries, folded);
		}
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (ZipOutputStream output = new ZipOutputStream(bytes)) {
			for (Map.Entry<String,byte[]> entry : entries.entrySet()) {
				ZipEntry zip = new ZipEntry(entry.getKey());
				zip.setTime(0L);
				output.putNextEntry(zip);
				output.write(entry.getValue());
				output.closeEntry();
			}
		}
		return bytes.toByteArray();
	}

	private static void readZipEntries(Path path, Set<String> selected,
		Map<String,byte[]> destination, Set<String> folded)
		throws IOException, WorldBuilderContractException {
		try (ZipFile archive = new ZipFile(path.toFile())) {
			java.util.Enumeration<? extends ZipEntry> entries = archive.entries();
			while (entries.hasMoreElements()) {
				ZipEntry entry = entries.nextElement();
				if (entry.isDirectory() || selected != null && !selected.contains(entry.getName())) continue;
				String name = entry.getName();
				WorldBuilderPortablePath.require(name, "item-visual-provider");
				String lower = name.toLowerCase(Locale.ROOT);
				String exactExisting = null;
				for (String candidate : destination.keySet()) {
					if (candidate.toLowerCase(Locale.ROOT).equals(lower)) exactExisting = candidate;
				}
				if (exactExisting != null && !exactExisting.equals(name)) throw new WorldBuilderContractException(
					WorldBuilderErrorCodes.UNSAFE_PATH, "item-visual-provider", name, false,
					"Selected authentic sprite name collides with existing archive content.",
					"Use an authentic provider archive with exact portable entry names.");
				try (InputStream input = archive.getInputStream(entry);
					ByteArrayOutputStream payload = new ByteArrayOutputStream()) {
					byte[] buffer = new byte[8192];
					for (int read; (read = input.read(buffer)) >= 0;) {
						if ((long)payload.size() + read > MAX_ASSET_BYTES) throw new IOException(
							"authentic entry exceeds bound");
						payload.write(buffer, 0, read);
					}
					destination.put(name, payload.toByteArray());
					folded.add(lower);
				}
			}
		}
	}

	private static Osar readOsar(Path path) throws IOException, Rejected {
		byte[] expanded;
		try (InputStream input = new GZIPInputStream(Files.newInputStream(path));
			ByteArrayOutputStream output = new ByteArrayOutputStream()) {
			byte[] buffer = new byte[8192];
			long total = 0L;
			for (int read; (read = input.read(buffer)) >= 0;) {
				total += read;
				if (total > MAX_EXPANDED_BYTES) throw new Rejected("PROVIDER_OSAR_INVALID",
					"OSAR expands beyond 512 MiB.");
				output.write(buffer, 0, read);
			}
			expanded = output.toByteArray();
		}
		try {
			Cursor input = new Cursor(expanded);
			int subspaces = input.u8();
			if (subspaces < 1) throw new IllegalArgumentException("no subspaces");
			Map<String,byte[]> entries = new LinkedHashMap<String,byte[]>();
			Set<String> folded = new HashSet<String>();
			Set<String> subspaceNames = new HashSet<String>();
			for (int s = 0; s < subspaces; s++) {
				String subspace = input.name();
				if (!subspaceNames.add(subspace)) throw new IllegalArgumentException(
					"duplicate subspace");
				int count = input.u16();
				for (int e = 0; e < count; e++) {
					String name = input.name();
					int start = input.offset;
					input.sprite();
					String combined = subspace + "/" + name;
					portable(combined);
					if (!folded.add(combined.toLowerCase(Locale.ROOT))) {
						throw new IllegalArgumentException("colliding entry");
					}
					entries.put(combined, Arrays.copyOfRange(expanded, start, input.offset));
				}
			}
			if (input.offset != expanded.length || entries.size() > MAX_ENTRIES) {
				throw new IllegalArgumentException("trailing or excessive entries");
			}
			return new Osar(entries, subspaceNames);
		} catch (RuntimeException invalid) {
			throw new Rejected("PROVIDER_OSAR_INVALID",
				"OSAR structure, names, frames, palette, pixels, or bounds are invalid.");
		}
	}

	private static String generatedSubspace(Path target)
		throws IOException, WorldBuilderContractException {
		try {
			Osar archive = readOsar(target);
			String candidate = GENERATED_SUBSPACE;
			for (int suffix = 2; archive.subspaces.contains(candidate); suffix++) {
				candidate = GENERATED_SUBSPACE + "_" + suffix;
			}
			return candidate;
		} catch (Rejected invalid) {
			throw new WorldBuilderContractException(WorldBuilderErrorCodes.UNSUPPORTED_FORMAT,
				"item-visual-provider", target.toString(), false,
				"Target custom sprite archive cannot safely receive project-local visuals.",
				"Repair the target's bounded OSAR structure and retry.");
		}
	}

	private static byte[] appendGeneratedSubspace(Path target, String subspace,
		List<GeneratedEntry> additions) throws IOException, WorldBuilderContractException {
		byte[] expanded;
		try (InputStream input = new GZIPInputStream(Files.newInputStream(target));
			ByteArrayOutputStream output = new ByteArrayOutputStream()) {
			byte[] buffer = new byte[8192];
			for (int read; (read = input.read(buffer)) >= 0;) output.write(buffer, 0, read);
			expanded = output.toByteArray();
		}
		int count = expanded[0] & 0xff;
		if (count >= 255) throw new WorldBuilderContractException(
			WorldBuilderErrorCodes.CONTRACT_LIMIT_EXCEEDED, "item-visual-provider",
			target.toString(), false, "Target custom archive has no room for a provider subspace.",
			"Reduce the target custom archive before importing provider visuals.");
		ByteArrayOutputStream raw = new ByteArrayOutputStream();
		raw.write(count + 1);
		raw.write(expanded, 1, expanded.length - 1);
		raw.write(subspace.getBytes(StandardCharsets.ISO_8859_1));
		raw.write(0);
		raw.write(additions.size() >>> 8);
		raw.write(additions.size());
		for (GeneratedEntry entry : additions) {
			raw.write(entry.name.getBytes(StandardCharsets.ISO_8859_1));
			raw.write(0);
			raw.write(entry.bytes);
		}
		ByteArrayOutputStream compressed = new ByteArrayOutputStream();
		try (GZIPOutputStream gzip = new GZIPOutputStream(compressed)) {
			gzip.write(raw.toByteArray());
		}
		return compressed.toByteArray();
	}

	private static byte[] placeholderEntry(int itemId) {
		int color = 0xff00ff ^ itemId * 0x45d9f3b;
		return new byte[] {0, 1, 0, (byte)(color >>> 16), (byte)(color >>> 8),
			(byte)color, 0, 1, 0, 1, 0, 0, 0, 0, 0, 0, 1, 0, 1, 0};
	}

	private static Map<String,Object> canonicalCustom(int itemId, String subspace,
		String entry, int pictureMask, int blueMask) {
		Map<String,Object> value = new LinkedHashMap<String,Object>();
		value.put("itemId", Long.valueOf(itemId));
		value.put("authenticSpriteId", null);
		value.put("customSpriteAssetRole", CUSTOM_ROLE);
		value.put("customSpriteSubspace", subspace);
		value.put("customSpriteEntry", entry);
		value.put("pictureMask", Long.valueOf(pictureMask));
		value.put("blueMask", Long.valueOf(blueMask));
		return value;
	}

	private static Map<String,Object> canonicalAuthentic(int itemId, int spriteId,
		int pictureMask, int blueMask) {
		Map<String,Object> value = new LinkedHashMap<String,Object>();
		value.put("itemId", Long.valueOf(itemId));
		value.put("authenticSpriteId", Long.valueOf(spriteId));
		value.put("customSpriteAssetRole", null);
		value.put("customSpriteSubspace", null);
		value.put("customSpriteEntry", null);
		value.put("pictureMask", Long.valueOf(pictureMask));
		value.put("blueMask", Long.valueOf(blueMask));
		return value;
	}

	static void writeReport(Path projectStage, Result result)
		throws IOException, WorldBuilderContractException {
		Path path = projectStage.resolve(REPORT_PATH).normalize();
		if (!path.startsWith(projectStage.toAbsolutePath().normalize())) throw new IOException(
			"provider report escaped project stage");
		Files.createDirectories(path.getParent());
		Map<String,Object> report = new LinkedHashMap<String,Object>();
		report.put("schemaVersion", Long.valueOf(1L));
		report.put("manifestType", "world-builder-item-visual-provider-report");
		report.put("providerManifestSha256", result.manifestSha256);
		List<Object> items = new ArrayList<Object>();
		for (ItemReport item : result.items) items.add(item.json());
		report.put("items", items);
		List<Object> warnings = new ArrayList<Object>();
		for (Warning warning : result.warnings) warnings.add(warning.json());
		report.put("warnings", warnings);
		Files.write(path, WorldBuilderJsonDocuments.pretty(report)
			.getBytes(StandardCharsets.UTF_8));
	}

	private static int safeItemId(Object raw) {
		if (!(raw instanceof Map)) return -1;
		Object value = ((Map<?,?>)raw).get("itemId");
		return value instanceof Long && ((Long)value).longValue() >= 0
			&& ((Long)value).longValue() <= 65535 ? ((Long)value).intValue() : -1;
	}

	private static int integer(Object raw, int minimum, int maximum, String name)
		throws Rejected {
		if (!(raw instanceof Long) || ((Long)raw).longValue() < minimum
			|| ((Long)raw).longValue() > maximum) throw new Rejected(
			"PROVIDER_FIELD_INVALID", "Provider " + name + " is outside its integer bounds.");
		return ((Long)raw).intValue();
	}

	private static String text(Object raw, int minimum, int maximum, String name)
		throws Rejected {
		if (!(raw instanceof String) || ((String)raw).length() < minimum
			|| ((String)raw).length() > maximum || ((String)raw).indexOf('\u0000') >= 0) {
			throw new Rejected("PROVIDER_FIELD_INVALID", "Provider " + name + " is invalid.");
		}
		return (String)raw;
	}

	private static String hash(Object raw, String name) throws Rejected {
		String value = text(raw, 64, 64, name);
		if (!value.matches("[0-9a-f]{64}")) throw new Rejected("PROVIDER_FIELD_INVALID",
			"Provider " + name + " must be lowercase SHA-256.");
		return value;
	}

	private static void requireNull(Object value, String name) throws Rejected {
		if (value != null) throw new Rejected("PROVIDER_SELECTOR_INVALID",
			"Provider " + name + " must be null for the selected source role.");
	}

	private static final class ProviderRecord {
		final int itemId, authenticSpriteId, pictureMask, blueMask;
		final String name, logicalSpriteLocation, sourceRole, sourceAssetSha256;
		final String customSpriteSubspace, customSpriteEntry;
		final Path sourceAsset;
		final ExternalPng external;
		ProviderRecord(int itemId, String name, String logical, String role, Path asset,
			String hash, int authenticSpriteId, String subspace, String entry,
			ExternalPng external, int pictureMask, int blueMask) {
			this.itemId = itemId; this.name = name; this.logicalSpriteLocation = logical;
			this.sourceRole = role; this.sourceAsset = asset; this.sourceAssetSha256 = hash;
			this.authenticSpriteId = authenticSpriteId; this.customSpriteSubspace = subspace;
			this.customSpriteEntry = entry; this.external = external;
			this.pictureMask = pictureMask; this.blueMask = blueMask;
		}
	}

	static final class Result {
		final List<Object> itemVisuals;
		final byte[] customArchiveOverride;
		final byte[] authenticArchiveOverride;
		final String manifestSha256;
		final List<ItemReport> items;
		final List<Warning> warnings;
		Result(List<Object> visuals, byte[] custom, byte[] authentic, String manifestHash,
			List<ItemReport> items, List<Warning> warnings) {
			this.itemVisuals = Collections.unmodifiableList(new ArrayList<Object>(visuals));
			this.customArchiveOverride = custom;
			this.authenticArchiveOverride = authentic;
			this.manifestSha256 = manifestHash;
			this.items = Collections.unmodifiableList(new ArrayList<ItemReport>(items));
			this.warnings = Collections.unmodifiableList(new ArrayList<Warning>(warnings));
		}
	}

	private static final class Resolved {
		final String name, sourceRole, logicalLocation;
		final int authenticSpriteId, pictureMask, blueMask;
		final byte[] spriteEntry;
		final Path authenticAsset;
		final String authenticEntry;
		private Resolved(ProviderRecord record, byte[] entry, Path authenticAsset,
			String authenticEntry) {
			this.name = record.name; this.sourceRole = record.sourceRole;
			this.logicalLocation = record.logicalSpriteLocation;
			this.authenticSpriteId = record.authenticSpriteId;
			this.pictureMask = record.pictureMask; this.blueMask = record.blueMask;
			this.spriteEntry = entry; this.authenticAsset = authenticAsset;
			this.authenticEntry = authenticEntry;
		}
		static Resolved sprite(ProviderRecord record, byte[] entry) {
			return new Resolved(record, entry, null, null);
		}
		static Resolved authentic(ProviderRecord record, Path path, String entry) {
			return new Resolved(record, null, path, entry);
		}
	}

	private static final class ExternalPng {
		final int width, height;
		ExternalPng(int width, int height) { this.width = width; this.height = height; }
	}

	private static final class Osar {
		final Map<String,byte[]> entries;
		final Set<String> subspaces;
		Osar(Map<String,byte[]> entries, Set<String> subspaces) {
			this.entries = entries; this.subspaces = subspaces;
		}
	}

	private static final class GeneratedEntry {
		final String name; final byte[] bytes;
		GeneratedEntry(String name, byte[] bytes) { this.name = name; this.bytes = bytes; }
	}

	private static final class Warning implements Comparable<Warning> {
		final int itemId; final String code, message;
		Warning(int itemId, String code, String message) {
			this.itemId = itemId; this.code = code; this.message = message;
		}
		Map<String,Object> json() {
			Map<String,Object> value = new LinkedHashMap<String,Object>();
			value.put("itemId", itemId < 0 ? null : Long.valueOf(itemId));
			value.put("code", code); value.put("message", message); return value;
		}
		@Override public int compareTo(Warning other) {
			int byId = Integer.compare(itemId, other.itemId);
			return byId != 0 ? byId : code.compareTo(other.code);
		}
	}

	private static final class ItemReport {
		final int itemId; final String name, status, sourceRole, logical;
		ItemReport(int itemId, String name, String status, String sourceRole, String logical) {
			this.itemId = itemId; this.name = name; this.status = status;
			this.sourceRole = sourceRole; this.logical = logical;
		}
		Map<String,Object> json() {
			Map<String,Object> value = new LinkedHashMap<String,Object>();
			value.put("itemId", Long.valueOf(itemId)); value.put("name", name);
			value.put("status", status); value.put("sourceRole", sourceRole);
			value.put("logicalSpriteLocation", logical); return value;
		}
	}

	private static final class Rejected extends Exception {
		final String code;
		Rejected(String code, String message) { super(message); this.code = code; }
	}

	private static final class Cursor {
		final byte[] bytes; int offset;
		Cursor(byte[] bytes) { this.bytes = bytes; }
		int u8() { if (offset >= bytes.length) throw new IllegalArgumentException("truncated"); return bytes[offset++] & 255; }
		int u16() { return u8() << 8 | u8(); }
		String name() {
			StringBuilder value = new StringBuilder();
			for (int c; (c = u8()) != 0;) {
				if (value.length() == 128 || c < 32 || c > 126) throw new IllegalArgumentException("name");
				value.append((char)c);
			}
			if (value.length() == 0) throw new IllegalArgumentException("name");
			return value.toString();
		}
		void skip(long count) {
			if (count < 0 || count > bytes.length - offset) throw new IllegalArgumentException("truncated");
			offset += (int)count;
		}
		void sprite() {
			int type = u8(); if (type > 4) throw new IllegalArgumentException("type");
			if (type >= 1 && type <= 3 && u8() > 11) throw new IllegalArgumentException("layer");
			int frames = u8(); if (frames < 1) throw new IllegalArgumentException("frames");
			int palette = u8() + 1; skip((long)palette * 3);
			for (int frame = 0; frame < frames; frame++) {
				int width = u16(), height = u16(), shifted = u8();
				u16(); u16(); u16(); u16();
				if (width < 1 || height < 1 || shifted > 1
					|| (long)width * height > 16777216L) throw new IllegalArgumentException("frame");
				for (long pixel = (long)width * height; pixel > 0; pixel--) {
					if (u8() >= palette) throw new IllegalArgumentException("palette");
				}
			}
		}
	}
}
