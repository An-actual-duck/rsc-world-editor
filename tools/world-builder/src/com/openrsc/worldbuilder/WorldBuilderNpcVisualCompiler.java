package com.openrsc.worldbuilder;

import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.jar.JarFile;
import java.util.zip.*;
import static com.openrsc.worldbuilder.WorldBuilderNpcVisualInventory.*;

/** Compiles any supported source-bound NPC visual record into portable lossless frames. */
final class WorldBuilderNpcVisualCompiler {
	private static final String VIDEO = "Client_Base/Cache/video/Authentic_Sprites.orsc";
	static Result normalize(Path copiedTarget, WorldBuilderPackedSourceLayout layout,
		WorldBuilderPackedSourceLayout originalLayout,
		WorldBuilderSupplementalNpcDefinitions.Result reconciliation, List<Object> customRows,
		List<Object> existingAnimations, byte[] authenticOverride, byte[] effectiveWorld,
		Path clientJar, Path serverJar) throws IOException, WorldBuilderContractException {
		WorldBuilderReadOnlyTarget target = WorldBuilderReadOnlyTarget.open(copiedTarget);
		Inventory inventory = discover(target, originalLayout);
		for (Object row : existingAnimations) if ("authentic-rgb".equals(object(row).get("frameSource"))) {
			capability(clientJar, true);
			capability(serverJar, false);
			break;
		}
		List<Object> diagnostics = new ArrayList<>();
		List<Object> base = list(json(target.requiredFile(layout.definitionPath("NpcDefs.json"))).get("npcs"), 65536);
		List<Object> effective = new ArrayList<>(base);
		effective.addAll(customRows);
		WorldBuilderDefinitionComposition.Profile composition = WorldBuilderDefinitionComposition.inspect(target, layout);
		applyOverlay(effective, readRows(WorldBuilderDefinitionComposition.effectiveJson(composition, copiedTarget,
		"definition.npc.patch", layout.definitionPath("NpcDefsPatch18.json"))));
		List<Object> world = readRows(effectiveWorld);
		applyOverlay(effective, world);
		for (int id=0;id<effective.size();id++) {
			Map<String, Object> diagnostic = new LinkedHashMap<>();
			diagnostic.put("npcId", Long.valueOf(id));
			diagnostic.put("status", "baseline-or-existing-layer-reference");
			diagnostic.put("resolvedSpriteSlots", new ArrayList<Object>());
			diagnostic.put("detail", "Definition sprite references retained; final custom presentation is not independently verified.");
			diagnostics.add(diagnostic);
		}
		if (inventory.records.isEmpty()) return new Result(null, null, existingAnimations, diagnostics);
		int nextAnimation=capability(clientJar, true);
		capability(serverJar, false);
		List<Object> animations=new ArrayList<>(existingAnimations);
		for (Object raw:animations) nextAnimation=Math.max(nextAnimation, number(object(raw), "animationId", 0, 65535)+1);
		for (Object raw:effective) for (int slot=1;slot<=12;slot++) {
			Object id=object(raw).get("sprites"+slot);
			if (id instanceof Long && (Long)id>=0 && (Long)id<=65535) nextAnimation=Math.max(nextAnimation, ((Long)id).intValue()+1);
		}
		TreeMap<String, byte[]> archive=readArchive(authenticOverride==null?Files.readAllBytes(target.requiredFile(VIDEO)):authenticOverride);
		Map<Integer, byte[]> spritePayloads=new HashMap<>();
		for (Map.Entry<String, byte[]> entry:archive.entrySet()) {
			Integer id=spriteId(entry.getKey());
			if (id!=null)spritePayloads.put(id, entry.getValue());
		}
		long rgbBytes=0;
		for (Object raw:animations) {
			Map<String, Object> row=object(raw);
			if (!"authentic-rgb".equals(row.get("frameSource")))continue;
			int baseId=number(row, "authenticBaseSpriteId", 0, 65535), count=number(row, "requiredFrameCount", 15, 27);
			for (int index=0;index<count;index++) {
				byte[] payload=spritePayloads.get(baseId+index);
				if (payload==null)throw problem(FILE, "Existing RGB animation frame is missing.");
				rgbBytes+=payload.length;
			}
		}
		Set<String> existingKeys=new HashSet<>();
		for (Object raw:animations)if ("authentic-rgb".equals(object(raw).get("frameSource")))existingKeys.add(text(object(raw), "name"));
		for (Map<String, Object> record:inventory.records) if (!existingKeys.contains(animationKey(record)))
		for (Object raw:list(record.get("frames"), 27)) {
			Map<String, Object> frame=object(raw);
			rgbBytes+=25L+4L*number(frame, "width", 1, 2048)*number(frame, "height", 1, 2048);
		}
		if (rgbBytes>256L*1024*1024)throw problem(FILE, "Existing and discovered RGB frames exceed the 256 MiB decoded payload budget.");
		int nextSprite=0;
		for (String name:archive.keySet()) {
			Integer id=spriteId(name);
			if (id!=null) nextSprite=Math.max(nextSprite, id+1);
		}
		Set<String> boundSlots=new HashSet<>();
		Map<String, Object> cameraClaims=new HashMap<>();
		Map<String, BufferedImage> images=new HashMap<>();
		for (Map<String, Object> record:inventory.records) {
			String definition=text(record, "definitionPath");
			int index=number(record, "definitionIndex", 0, 65535);
			List<Object> sourceRows=rows(json(target.requiredFile(definition)));
			if (index>=sourceRows.size()) throw problem(definition, "Visual definition index is outside its source array.");
			int npc=number(record, "npcId", 0, 65535), slot=number(record, "spriteSlot", 1, 12);
			Map<String, Object> source=object(sourceRows.get(index));
			String resolvedPath=definition;
			if (!originalLayout.definitionRoot.equals(layout.definitionRoot) && definition.startsWith(originalLayout.definitionRoot+"/")) {
				resolvedPath=layout.definitionRoot+definition.substring(originalLayout.definitionRoot.length());
				if (!WorldBuilderHashes.sha256(target.requiredFile(definition)).equals(WorldBuilderHashes.sha256(target.requiredFile(resolvedPath))))
				throw problem(definition, "Canonical visual definition alias differs from its original source evidence.");
			}
			Integer resolved=reconciliation.sourceIds.get(resolvedPath+"#"+index);
			boolean sequential=resolvedPath.equals(layout.definitionPath("NpcDefs.json"))||resolvedPath.equals(layout.definitionPath("NpcDefsCustom.json"));
			if (sequential) {
				int sourceId=resolvedPath.equals(layout.definitionPath("NpcDefs.json"))?index:base.size()+index;
				if (npc!=sourceId) throw problem(definition, "Visual NPC identity differs from the sequential definition slot.");
			}
			else if (!Long.valueOf(npc).equals(source.get("id"))) throw problem(definition, "Visual NPC identity differs from its bound definition.");
			if (resolved==null) {
				String patch=composition.sourceFor("definition.npc.patch", layout.definitionPath("NpcDefsPatch18.json"));
				String selectedWorld=composition.sourceFor("definition.npc.world", layout.definitionPath("NpcDefsMyWorld.json"));
				if (!resolvedPath.equals(patch)&&!resolvedPath.equals(selectedWorld)) throw problem(definition, "Visual metadata binds an inactive or unknown definition layer.");
				resolved=npc;
			}
			npc=resolved;
			if (npc>=effective.size()) throw problem(definition, "Visual metadata references an undefined effective NPC.");
			if (!boundSlots.add(npc+":"+slot)) throw problem(definition, "Multiple visual sources claim one effective NPC sprite slot.");
			List<byte[]> frames=new ArrayList<>();
			List<String> hashes=new ArrayList<>();
			for (Object raw:list(record.get("frames"), 27)) {
				Map<String, Object> frame=object(raw);
				String imagePath=text(frame, "imagePath");
				BufferedImage image=images.get(imagePath);
				if (image==null) {
					image=image(target.requiredFile(imagePath), imagePath);
					images.put(imagePath, image);
				}
				byte[] bytes=frame(image, frame, number(record, "alphaThreshold", 0, 255));
				frames.add(bytes);
				hashes.add(WorldBuilderHashes.sha256(bytes));
			}
			String key=animationKey(record);
			Map<String, Object> animation=null;
			for (Object raw:animations) {
				Map<String, Object> candidate=object(raw);
				if (key.equals(candidate.get("name"))&&"authentic-rgb".equals(candidate.get("frameSource"))) {
					if (animation!=null) throw problem(definition, "Duplicate derived visual animation identity.");
					animation=candidate;
				}
			}
			int animationId;
			if (animation!=null) {
				animationId=number(animation, "animationId", 1080, 65535);
				if (!hashes.equals(animation.get("authenticFrameSha256s"))) throw problem(definition, "Stored visual frame hashes differ from source evidence.");
				int previousBase=number(animation, "authenticBaseSpriteId", 0, 65535);
				for (int frame=0;frame<frames.size();frame++) {
					byte[] existing=spritePayloads.get(previousBase+frame);
					if (!Arrays.equals(frames.get(frame), existing)) throw problem(definition, "Stored visual payload differs from source evidence.");
				}
			}
			else {
				if (nextAnimation>65535||nextSprite>65536-frames.size()) throw problem(definition, "NPC visual animation capacity exhausted.");
				animationId=nextAnimation++;
				animation=new LinkedHashMap<>();
				animation.put("animationId", Long.valueOf(animationId));
				animation.put("name", key);
				animation.put("category", "npc");
				for (String field:Arrays.asList("charColour", "blueMask", "genderModel", "hasCombatFrames", "hasSpecialCombatFrames")) animation.put(field, record.get(field));
				animation.put("requiredFrameCount", Long.valueOf(frames.size()));
				animation.put("frameSource", "authentic-rgb");
				animation.put("authenticBaseSpriteId", Long.valueOf(nextSprite));
				animation.put("authenticFrameSha256s", hashes);
				for (int frame=0;frame<frames.size();frame++) archive.put("sprites/"+(nextSprite+frame)+".dat", frames.get(frame));
				nextSprite+=frames.size();
				animations.add(animation);
			}
			Map<String, Object> presentation=new LinkedHashMap<>();
			presentation.put("id", Long.valueOf(npc));
			presentation.put("sprites"+slot, Long.valueOf(animationId));
			for (String field:Arrays.asList("cameraWidth", "cameraHeight")) if (record.get(field)!=null) {
				Object prior=cameraClaims.put(npc+":"+field, record.get(field));
				if (prior!=null&&!prior.equals(record.get(field))) throw problem(definition, "Conflicting visual camera bounds for one NPC.");
				presentation.put(field.equals("cameraWidth")?"camera1":"camera2", record.get(field));
			}
			world.add(presentation);
			Map<String, Object> diagnostic=object(diagnostics.get(npc));
			diagnostic.put("status", "verified-custom-visual-slots");
			@SuppressWarnings("unchecked") List<Object> resolvedSlots=(List<Object>)diagnostic.get("resolvedSpriteSlots");
			resolvedSlots.add(Long.valueOf(slot));
			diagnostic.put("detail", "Listed sprite slots are verified from exact source evidence; remaining definition layers are retained references.");
		}
		animations.sort((a, b)->Long.compare((Long)((Map<?, ?>)a).get("animationId"), (Long)((Map<?, ?>)b).get("animationId")));
		ByteArrayOutputStream bytes=new ByteArrayOutputStream();
		try (ZipOutputStream zip=new ZipOutputStream(bytes)) {
			for (Map.Entry<String, byte[]> entry:archive.entrySet()) {
				ZipEntry item=new ZipEntry(entry.getKey());
				item.setTime(0L);
				zip.putNextEntry(item);
				zip.write(entry.getValue());
				zip.closeEntry();
			}
		}
		return new Result(WorldBuilderSupplementalNpcDefinitions.customJson(world), bytes.toByteArray(), animations, diagnostics);
	}
	private static String animationKey(Map<String, Object> record) {
		return "visual-"+WorldBuilderHashes.sha256(WorldBuilderJsonDocuments.pretty(record).getBytes(StandardCharsets.UTF_8)).substring(0, 40);
	}
	static List<Object> readRows(byte[] bytes) throws WorldBuilderContractException {
		try {
			return rows(WorldBuilderJsonDocuments.readTargetDefinitionObject(bytes, "NPC definitions"));
		}
		catch (WorldBuilderDiscoveryException invalid){
			throw problem("NPC definitions", "Malformed effective NPC definitions.");
		}
	}
	static List<Object> rows(Map<String, Object> document)throws WorldBuilderContractException {
		if (document.size()!=1)throw problem("NPC definitions", "Expected one NPC definition array.");
		return list(document.values().iterator().next(), 65536);
	}
	private static void applyOverlay(List<Object> effective, List<Object> overlay)throws WorldBuilderContractException {
		for (Object raw:overlay){
			Map<String, Object> row=object(raw);
			int id=number(row, "id", 0, 65535);
			if (id>=effective.size())throw problem("NPC definitions", "Overlay references unknown NPC.");
			Map<String, Object> merged=new LinkedHashMap<>(object(effective.get(id)));
			merged.putAll(row);
			effective.set(id, merged);
		}
	}
	private static byte[] frame(BufferedImage image, Map<String, Object> frame, int alpha)throws IOException, WorldBuilderContractException {
		int x=number(frame, "x", 0, 4095), y=number(frame, "y", 0, 4095), width=number(frame, "width", 1, 2048), height=number(frame, "height", 1, 2048);
		ByteArrayOutputStream bytes=new ByteArrayOutputStream();
		DataOutputStream out=new DataOutputStream(bytes);
		int ox=number(frame, "offsetX", -4096, 4096), oy=number(frame, "offsetY", -4096, 4096);
		out.writeInt(width);
		out.writeInt(height);
		out.writeByte(ox!=0||oy!=0?1:0);
		out.writeInt(ox);
		out.writeInt(oy);
		out.writeInt(number(frame, "boundWidth", 1, 4096));
		out.writeInt(number(frame, "boundHeight", 1, 4096));
		for (int row=0;row<height;row++)for (int col=0;col<width;col++){
			int argb=image.getRGB(x+col, y+row), rgb=argb&0xffffff;
			out.writeInt((argb>>>24)<alpha?0:rgb==0?0x010101:rgb);
		}
		return bytes.toByteArray();
	}
	static String projectWarningSummary(Path project) {
		if (project==null)return null;
		Path report=project.resolve(REPORT);
		if (!Files.isRegularFile(report, LinkOption.NOFOLLOW_LINKS)||Files.isSymbolicLink(report))return null;
		try {
			int verified=0;
			for (Object raw:list(json(report).get("npcs"), 65536))
			if ("verified-custom-visual-slots".equals(object(raw).get("status")))verified++;
			return verified==0?null:"\n\nImported verified custom visual slots for "+verified+" NPC definitions. Details: "+report;
		}
		catch (WorldBuilderContractException invalid){
			return null;
		}
	}
	static void writeReport(Path project, Result result)throws IOException{
		Map<String, Object> document=new LinkedHashMap<>();
		document.put("schemaVersion", Long.valueOf(1));
		document.put("manifestType", "world-builder-npc-visual-resolution");
		document.put("npcs", result.diagnostics);
		Path path=project.resolve(REPORT);
		Files.createDirectories(path.getParent());
		Files.write(path, WorldBuilderJsonDocuments.pretty(document).getBytes(StandardCharsets.UTF_8));
	}
	static final class Result {
		final byte[] worldDefinitions, authenticArchive;
		final List<Object> animations, diagnostics;
		Result(byte[] world, byte[] archive, List<Object> animations, List<Object> diagnostics){
			this.worldDefinitions=world;
			this.authenticArchive=archive;
			this.animations=animations;
			this.diagnostics=diagnostics;
		}
		boolean changed(){
			return worldDefinitions!=null;
		}
	}
	private static int capability(Path jar, boolean client) throws WorldBuilderContractException {
		try (JarFile file = new JarFile(jar.toFile())) {
			java.util.jar.Attributes attrs = file.getManifest().getMainAttributes();
			if (!"npc-rgb-frames-v1".equals(attrs.getValue("World-Builder-Npc-Rgb"))) throw new IOException("missing RGB frame capability");
			if (!client) return 0;
			int count = Integer.parseInt(attrs.getValue("World-Builder-Npc-Animation-Count"));
			if (count < 1080 || count > 65535) throw new IOException("invalid animation capacity");
			return count;
		}
		catch (Exception failure) {
			throw problem(jar.getFileName().toString(), "Selected runtime cannot load lossless directional NPC frames; upgrade the World Builder runtime.");
		}
	}
	private static TreeMap<String, byte[]> readArchive(byte[] bytes) throws IOException, WorldBuilderContractException {
		TreeMap<String, byte[]> result = new TreeMap<>();
		Set<String> names = new HashSet<>();
		Set<Integer> ids = new HashSet<>();
		long total = 0;
		try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
			for (ZipEntry entry; (entry = zip.getNextEntry()) != null;) {
				String name = entry.getName();
				WorldBuilderPortablePath.require(name, "npc-direction-sheets");
				Integer id = spriteId(name);
				if (entry.isDirectory() || result.size() >= 65536 || !names.add(name.toLowerCase(Locale.ROOT))
				|| id != null && !ids.add(id)) throw problem(VIDEO, "Authentic sprite archive has unsafe or duplicate entries.");
				ByteArrayOutputStream payload = new ByteArrayOutputStream();
				byte[] buffer = new byte[8192];
				for (int count; (count = zip.read(buffer)) >= 0;) {
					total += count;
					if (payload.size() + count > 16 * 1024 * 1024 || total > 512L * 1024 * 1024) throw problem(VIDEO, "Authentic sprite archive exceeds safe bounds.");
					payload.write(buffer, 0, count);
				}
				result.put(name, payload.toByteArray());
			}
		}
		if (result.isEmpty()) throw problem(VIDEO, "Authentic sprite archive is empty or unreadable.");
		return result;
	}
	private static Integer spriteId(String name) {
		String leaf = name.substring(name.lastIndexOf('/') + 1);
		if (leaf.endsWith(".dat")) leaf = leaf.substring(0, leaf.length() - 4);
		return leaf.matches("[0-9]{1, 5}") ? Integer.valueOf(leaf) : null;
	}
}
