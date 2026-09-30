package com.openrsc.worldbuilder;

import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

/** Data-only, source-bound visual closure shared by neutral metadata and structural source adapters. */
final class WorldBuilderNpcVisualInventory {
	static final String FILE = "npc-visuals-v1.json";
	static final String TYPE = "world-builder-npc-visual-sources";
	static final String REPORT = "diagnostics/npc-visual-resolution-v1.json";
	static final int MAX_RECORDS = 4096;
	static boolean evidenceRole(String role) {
		return "npc-visual-source".equals(role) || "npc-visual-image".equals(role)
			|| "npc-visual-metadata".equals(role);
	}
	static final class Inventory {
		final List<Map<String,Object>> records;
		final List<WorldBuilderReadOnlyTarget.FileState> evidence;
		Inventory(List<Map<String,Object>> records, List<WorldBuilderReadOnlyTarget.FileState> evidence) {
			this.records = records; this.evidence = evidence;
		}
	}
	static Inventory discover(WorldBuilderReadOnlyTarget target, WorldBuilderPackedSourceLayout layout)
		throws WorldBuilderContractException {
		List<WorldBuilderReadOnlyTarget.FileState> evidence = new ArrayList<>();
		List<Map<String,Object>> records = new ArrayList<>();
		String selectedHash = null;
		for (String path : new LinkedHashSet<String>(Arrays.asList(FILE, layout.definitionPath(FILE),
				layout.definitionPath("world-builder/" + FILE)))) {
			if (!target.exists(path)) continue;
			WorldBuilderReadOnlyTarget.FileState state = target.requiredState("npc-visual-metadata", path);
			evidence.add(state);
			if (selectedHash != null) {
				if (!selectedHash.equals(state.sha256)) throw problem(path, "Multiple NPC visual descriptors disagree.");
				continue;
			}
			selectedHash = state.sha256;
			Map<String,Object> document = json(target.requiredFile(path));
			exact(document, "schemaVersion", "manifestType", "visuals");
			if (!Long.valueOf(1).equals(document.get("schemaVersion")) || !TYPE.equals(document.get("manifestType")))
				throw problem(path, "Unknown NPC visual descriptor contract.");
			for (Object raw : list(document.get("visuals"), MAX_RECORDS)) records.add(object(raw));
		}
		Set<String> explicit = new HashSet<>(), explicitlyBoundDefinitions = new HashSet<>();
		for (Map<String,Object> record : records) {
			explicit.add(identity(record));
			explicitlyBoundDefinitions.add(record.get("definitionPath") + "#" + record.get("definitionIndex"));
		}
		List<Map<String,Object>> inferred = WorldBuilderNpcVisualSourceAdapter.discover(target, layout, evidence, explicitlyBoundDefinitions);
		// Explicit neutral declarations take precedence for the same definition layer and slot.
		for (Map<String,Object> record : inferred) if (!explicit.contains(identity(record))) records.add(record);
		if (records.size() > MAX_RECORDS) throw problem(FILE, "NPC visual inventory exceeds 4096 records.");
		Set<String> identities = new HashSet<>();
		Map<String,BufferedImage> images = new HashMap<>();
		long[] decodedPixels = {0};
		for (Map<String,Object> record : records) {
			validate(target, record, evidence, images, decodedPixels);
			if (!identities.add(identity(record))) throw problem(FILE, "Duplicate NPC visual binding for one definition and sprite slot.");
		}
		long frameBytes = 0;
		for (Map<String,Object> record : records) for (Object raw : list(record.get("frames"),27)) {
			Map<String,Object> frame = object(raw);
			frameBytes += 25L + 4L * number(frame,"width",1,2048) * number(frame,"height",1,2048);
			if(frameBytes > 256L*1024*1024) throw problem(FILE,"NPC visual frames exceed the 256 MiB decoded payload budget.");
		}
		TreeMap<String,WorldBuilderReadOnlyTarget.FileState> unique = new TreeMap<>();
		for (WorldBuilderReadOnlyTarget.FileState state : evidence) {
			WorldBuilderReadOnlyTarget.FileState old = unique.put(state.relativePath, state);
			if (old != null && (!old.sha256.equals(state.sha256) || old.size != state.size))
				throw problem(state.relativePath, "Visual source changed during discovery.");
		}
		records.sort((a,b) -> identity(a).compareTo(identity(b)));
		return new Inventory(records, new ArrayList<>(unique.values()));
	}
	private static void validate(WorldBuilderReadOnlyTarget target, Map<String,Object> record,
		List<WorldBuilderReadOnlyTarget.FileState> evidence, Map<String,BufferedImage> images,
		long[] decodedPixels) throws WorldBuilderContractException {
		exact(record, "npcId", "definitionPath", "definitionIndex", "definitionSha256", "spriteSlot", "frames",
			"alphaThreshold", "cameraWidth", "cameraHeight", "charColour", "blueMask", "genderModel",
			"hasCombatFrames", "hasSpecialCombatFrames");
		number(record, "npcId", 0, 65535); number(record, "definitionIndex", 0, 65535);
		number(record, "spriteSlot", 1, 12); number(record, "alphaThreshold", 0, 255);
		for (String field : Arrays.asList("charColour", "blueMask", "genderModel")) number(record, field, Integer.MIN_VALUE, Integer.MAX_VALUE);
		for (String field : Arrays.asList("cameraWidth", "cameraHeight")) if (record.get(field) != null) number(record, field, 1, 65535);
		boolean combat = flag(record,"hasCombatFrames"), special = flag(record,"hasSpecialCombatFrames");
		if (special && !combat) throw problem(FILE, "Special combat frames require combat frames.");
		List<Object> frames = list(record.get("frames"), 27);
		if (frames.size() != 15 + (combat ? 3 : 0) + (special ? 9 : 0)) throw problem(FILE, "Frame inventory disagrees with renderer pose flags.");
		bound(target, text(record,"definitionPath"), text(record,"definitionSha256"), "npc-visual-source", evidence);
		for (Object raw : frames) {
			Map<String,Object> frame = object(raw);
			exact(frame, "imagePath", "imageSha256", "x", "y", "width", "height", "offsetX", "offsetY", "boundWidth", "boundHeight");
			int x = number(frame,"x",0,4095), y = number(frame,"y",0,4095);
			int width = number(frame,"width",1,2048), height = number(frame,"height",1,2048);
			number(frame,"offsetX",-4096,4096); number(frame,"offsetY",-4096,4096);
			number(frame,"boundWidth",1,4096); number(frame,"boundHeight",1,4096);
			String path = text(frame,"imagePath");
			if (!images.containsKey(path)) {
				bound(target, path, text(frame,"imageSha256"), "npc-visual-image", evidence);
				BufferedImage image = image(target.requiredFile(path), path, 16L * 1024 * 1024 - decodedPixels[0]);
				decodedPixels[0] += (long)image.getWidth() * image.getHeight();
				if (decodedPixels[0] > 16L * 1024 * 1024) throw problem(path, "NPC image inventory exceeds 16 million decoded pixels.");
				images.put(path, image);
			} else if (!WorldBuilderHashesSafe.hash(target.requiredFile(path)).equals(text(frame,"imageSha256")))
				throw problem(path, "Image hash bindings disagree.");
			BufferedImage image = images.get(path);
			if ((long)x + width > image.getWidth() || (long)y + height > image.getHeight()) throw problem(path, "NPC frame rectangle exceeds its source image.");
		}
	}
	static BufferedImage image(Path path, String label) throws WorldBuilderContractException {
		return image(path,label,16L*1024*1024);
	}
	private static BufferedImage image(Path path,String label,long pixelBudget) throws WorldBuilderContractException {
		try {
			if (Files.size(path) > 16L * 1024 * 1024) throw new IOException("PNG exceeds 16 MiB");
			try (ImageInputStream input = ImageIO.createImageInputStream(path.toFile())) {
				if (input == null) throw new IOException("unreadable image");
				Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
				if (!readers.hasNext()) throw new IOException("unreadable image");
				ImageReader reader = readers.next();
				try {
					reader.setInput(input, true, true);
					int width = reader.getWidth(0), height = reader.getHeight(0);
					if (!"png".equalsIgnoreCase(reader.getFormatName()) || width < 1 || height < 1
						|| width > 4096 || height > 4096 || (long)width * height > pixelBudget) throw new IOException("unsupported PNG bounds");
					return reader.read(0);
				} finally { reader.dispose(); }
			}
		} catch (IOException | RuntimeException invalid) { throw problem(label, "NPC visual image is invalid: " + invalid.getMessage()); }
	}
	static void bound(WorldBuilderReadOnlyTarget target, String path, String expected, String role,
		List<WorldBuilderReadOnlyTarget.FileState> evidence) throws WorldBuilderContractException {
		if (!WorldBuilderBoundedInventory.isHash(expected)) throw problem(path,"Invalid source SHA-256.");
		WorldBuilderReadOnlyTarget.FileState state = target.requiredState(role,path);
		if (!state.sha256.equals(expected)) throw problem(path,"NPC visual source differs from its declared SHA-256.");
		evidence.add(state);
	}
	static String identity(Map<String,Object> record) {
		return record.get("definitionPath") + "#" + record.get("definitionIndex") + ":" + record.get("spriteSlot");
	}
	static Map<String,Object> json(Path path) throws WorldBuilderContractException {
		try { return WorldBuilderJsonDocuments.readTargetDefinitionObject(path); }
		catch (Exception invalid) { throw problem(path.toString(),"Invalid bounded NPC visual JSON: " + invalid.getMessage()); }
	}
	@SuppressWarnings("unchecked") static Map<String,Object> object(Object value) throws WorldBuilderContractException {
		if (!(value instanceof Map)) throw problem(FILE,"Expected an object.");
		return (Map<String,Object>)value;
	}
	static List<Object> list(Object value, int max) throws WorldBuilderContractException {
		if (!(value instanceof List) || ((List<?>)value).size() > max) throw problem(FILE,"Invalid or excessive visual record array.");
		return new ArrayList<Object>((List<?>)value);
	}
	static int number(Map<String,Object> value, String field, int min, int max) throws WorldBuilderContractException {
		Object raw = value.get(field);
		if (!(raw instanceof Long) || (Long)raw < min || (Long)raw > max) throw problem(FILE,"Invalid integer " + field + ".");
		return ((Long)raw).intValue();
	}
	static boolean flag(Map<String,Object> value, String field) throws WorldBuilderContractException {
		if (!(value.get(field) instanceof Boolean)) throw problem(FILE,"Invalid flag " + field + ".");
		return (Boolean)value.get(field);
	}
	static String text(Map<String,Object> value, String field) throws WorldBuilderContractException {
		if (!(value.get(field) instanceof String) || ((String)value.get(field)).length() > 512) throw problem(FILE,"Invalid text " + field + ".");
		return (String)value.get(field);
	}
	static void exact(Map<String,Object> value, String... keys) throws WorldBuilderContractException {
		if (!value.keySet().equals(new HashSet<String>(Arrays.asList(keys)))) throw problem(FILE,"Unexpected NPC visual record fields.");
	}
	static WorldBuilderContractException problem(String path, String message) {
		return new WorldBuilderContractException(WorldBuilderErrorCodes.DEFINITION_MISMATCH,"npc-visual-discovery",path,false,
			message,"Correct the selected visual metadata or supply complete neutral visual evidence, then rediscover.");
	}
	// Keeps checked I/O failures in the same data-only diagnostic boundary.
	private static final class WorldBuilderHashesSafe {
		static String hash(Path path) throws WorldBuilderContractException {
			try { return WorldBuilderHashes.sha256(path); } catch (IOException e) { throw problem(path.toString(),"Visual source changed while hashing."); }
		}
	}
}
