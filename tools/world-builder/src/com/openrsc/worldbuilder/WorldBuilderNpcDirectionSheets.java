package com.openrsc.worldbuilder;

import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.jar.JarFile;
import java.util.zip.*;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

/** Bounded migration adapter for the historical Slayer movement-preview artwork family.
 * No target Java is executed; the selected PNGs remain immutable source evidence. */
final class WorldBuilderNpcDirectionSheets {
    private static final String CATALOG = "SlayerMovementPreviewNpcDefs.json";
    private static final String ROOT = "dev/myworld/assets/sprites/npcs/slayer-movement-preview/";
    private static final String VIDEO = "Client_Base/Cache/video/Authentic_Sprites.orsc";
    private static final Spec[] SPECS = {
        new Spec(863, "Giant frog", "giant-frog", 100, 100,100,100,100,100,100),
        new Spec(864, "Cockatrice", "cockatrice", 100, 100,100,100,100,100,100),
        new Spec(865, "Banshee", "banshee", 100, 100,100,100,100,100),
        new Spec(866, "Naga", "naga", 100, 100,100,100,100,100,100,128),
        new Spec(867, "Terror dog", "terror-dog", 100, 100,100,100,100,100,100),
        new Spec(868, "Bloodveld", "bloodveld", 110, 120,120,120,120,120,120,220),
        new Spec(869, "Dark beast", "dark-beast", 110, 120,120,120,120,120,120,120),
        new Spec(870, "Abyssal demon", "abyssal-demon", 112, 100,100,100,100,100,112,144)
    };

    static boolean evidenceRole(String role) {
        for (Spec spec : SPECS) if (("npc-direction-sheet." + spec.asset).equals(role)) return true;
        return false;
    }

    static List<WorldBuilderReadOnlyTarget.FileState> inspect(WorldBuilderReadOnlyTarget target,
        WorldBuilderPackedSourceLayout layout) throws WorldBuilderContractException {
        List<WorldBuilderReadOnlyTarget.FileState> result = new ArrayList<>();
        for (Spec spec : selected(target, layout)) {
            WorldBuilderReadOnlyTarget.FileState state = target.requiredState(
                "npc-direction-sheet." + spec.asset, spec.path());
            readImage(target.requiredFile(spec.path()), spec);
            result.add(state);
        }
        return result;
    }

    private static List<Spec> selected(WorldBuilderReadOnlyTarget target,
        WorldBuilderPackedSourceLayout layout) throws WorldBuilderContractException {
        String catalog = layout.definitionPath(CATALOG);
        if (!target.exists(catalog)) return Collections.emptyList();
        try {
            Map<String,Object> doc = WorldBuilderJsonDocuments.readTargetDefinitionObject(target.requiredFile(catalog));
            Object raw = doc.get("npcs");
            if (!(raw instanceof List) || ((List<?>)raw).size() > 65536) throw problem(catalog, "Invalid directional NPC catalog.");
            List<Spec> result = new ArrayList<>();
            for (Spec spec : SPECS) for (Object row : (List<?>)raw) {
                if (!(row instanceof Map)) throw problem(catalog, "Invalid directional NPC record.");
                Map<?,?> npc = (Map<?,?>)row;
                if (Long.valueOf(spec.id).equals(npc.get("id")) && spec.name.equals(npc.get("name"))) {
                    if (result.contains(spec)) throw problem(catalog, "Duplicate directional NPC identity.");
                    result.add(spec);
                }
            }
            return result;
        } catch (WorldBuilderContractException failure) { throw failure; }
        catch (Exception failure) { throw problem(catalog, "Cannot read directional NPC catalog: " + failure.getMessage()); }
    }

    static Result normalize(Path copiedTarget, WorldBuilderPackedSourceLayout layout,
        WorldBuilderSupplementalNpcDefinitions.Result reconciliation,
        List<Object> customRows, List<Object> existingAnimations, byte[] authenticOverride,
        Path clientJar, Path serverJar) throws IOException, WorldBuilderContractException {
        WorldBuilderReadOnlyTarget target = WorldBuilderReadOnlyTarget.open(copiedTarget);
        List<Spec> specs = selected(target, layout);
        boolean rgb = false;
        for (Object row : existingAnimations) if ("authentic-rgb".equals(((Map<?,?>)row).get("frameSource"))) rgb = true;
        if (rgb) { capability(clientJar, true); capability(serverJar, false); }
        if (specs.isEmpty()) return new Result(null, null, existingAnimations);
        int nextAnimation = capability(clientJar, true);
        capability(serverJar, false);
        List<Object> animations = new ArrayList<>(existingAnimations);
        for (Object raw : animations) nextAnimation = Math.max(nextAnimation,
            ((Long)((Map<?,?>)raw).get("animationId")).intValue() + 1);
        Map<String,Object> base;
        try { base = WorldBuilderJsonDocuments.readTargetDefinitionObject(
            target.requiredFile(layout.definitionPath("NpcDefs.json"))); }
        catch (WorldBuilderDiscoveryException failure) { throw problem("NpcDefs.json", "Cannot read base NPC definitions."); }
        for (Object raw : (List<?>)base.get("npcs")) nextAnimation = afterSprites(raw, nextAnimation);
        for (Object raw : customRows) nextAnimation = afterSprites(raw, nextAnimation);
        TreeMap<String,byte[]> archive = readArchive(authenticOverride == null
            ? Files.readAllBytes(target.requiredFile(VIDEO)) : authenticOverride);
        int nextSprite = 0;
        for (String name : archive.keySet()) {
            Integer id = spriteId(name);
            if (id != null) nextSprite = Math.max(nextSprite, id + 1);
        }
        List<Object> rewritten = new ArrayList<>(customRows);
        for (Spec spec : specs) {
            int selectedId = spec.id;
            for (WorldBuilderSupplementalNpcDefinitions.Conflict conflict : reconciliation.conflicts) {
                if (conflict.definition.relative.equals(layout.definitionPath(CATALOG))
                    && conflict.requestedId == spec.id) selectedId = conflict.assignedId;
            }
            int selectedIndex = selectedId - ((List<?>)base.get("npcs")).size();
            if (selectedIndex < 0 || selectedIndex >= rewritten.size()
                || !spec.name.equals(((Map<?,?>)rewritten.get(selectedIndex)).get("name")))
                throw problem(spec.path(), "Directional NPC identity was not preserved during reconciliation.");
            @SuppressWarnings("unchecked") Map<String,Object> old = (Map<String,Object>)rewritten.get(selectedIndex);
            Map<?,?> already = null;
            for (Object animation : animations) {
                Map<?,?> candidate = (Map<?,?>)animation;
                if ("authentic-rgb".equals(candidate.get("frameSource"))
                    && "npc".equals(candidate.get("category"))
                    && ("slayer-preview-" + spec.asset).equals(candidate.get("name"))) {
                    if (already != null) throw problem(spec.path(), "Directional animation identity is ambiguous.");
                    already = candidate;
                }
            }
            for (int slot = 1; slot <= 12; slot++) {
                if (slot == 1 && already != null && Objects.equals(old.get("sprites1"), already.get("animationId"))) continue;
                if (!Long.valueOf(slot == 1 ? 0 : -1).equals(old.get("sprites" + slot)))
                    throw problem(spec.path(), "Directional NPC presentation differs from the recognized source layout.");
            }
            BufferedImage image = readImage(target.requiredFile(spec.path()), spec);
            List<String> hashes = new ArrayList<>();
            List<byte[]> frames = new ArrayList<>();
            for (int frame = 0; frame < 18; frame++) {
                // Banshee historically reuses side poses for the three combat frames.
                int sourceFrame = spec.columns.length == 5 && frame >= 15 ? frame - 9 : frame;
                int column = sourceFrame / 3, row = sourceFrame % 3, x = 0;
                for (int c = 0; c < column; c++) x += spec.columns[c];
                byte[] bytes = frame(image, x, row * spec.height, spec.columns[column], spec.height);
                frames.add(bytes);
                hashes.add(WorldBuilderHashes.sha256(bytes));
            }
            if (already != null) {
                int previousBase = ((Long)already.get("authenticBaseSpriteId")).intValue();
                if (!hashes.equals(already.get("authenticFrameSha256s")))
                    throw problem(spec.path(), "Captured directional artwork differs from its existing animation evidence.");
                for (int frame = 0; frame < 18; frame++) {
                    byte[] existing = null;
                    for (Map.Entry<String,byte[]> entry : archive.entrySet())
                        if (Integer.valueOf(previousBase + frame).equals(spriteId(entry.getKey()))) existing = entry.getValue();
                    if (!Arrays.equals(frames.get(frame), existing))
                        throw problem(spec.path(), "Captured directional frame payload differs from its registry.");
                }
                rewritten.set(selectedIndex, presentation(old, spec, ((Long)already.get("animationId")).intValue()));
                continue;
            }
            if (nextAnimation > 65535 || nextSprite > 65535 - 17) throw problem(spec.path(), "No room remains for directional NPC animation frames.");
            for (int frame = 0; frame < 18; frame++) archive.put("sprites/" + (nextSprite + frame) + ".dat", frames.get(frame));
            Map<String,Object> animation = new LinkedHashMap<>();
            animation.put("animationId", Long.valueOf(nextAnimation));
            animation.put("name", "slayer-preview-" + spec.asset);
            animation.put("category", "npc");
            animation.put("charColour", Long.valueOf(0));
            animation.put("blueMask", Long.valueOf(0));
            animation.put("genderModel", Long.valueOf(0));
            animation.put("hasCombatFrames", Boolean.TRUE);
            animation.put("hasSpecialCombatFrames", Boolean.FALSE);
            animation.put("requiredFrameCount", Long.valueOf(18));
            animation.put("frameSource", "authentic-rgb");
            animation.put("authenticBaseSpriteId", Long.valueOf(nextSprite));
            animation.put("authenticFrameSha256s", hashes);
            animations.add(animation);
            rewritten.set(selectedIndex, presentation(old, spec, nextAnimation));
            nextAnimation++; nextSprite += 18;
        }
        animations.sort((a,b) -> Long.compare((Long)((Map<?,?>)a).get("animationId"), (Long)((Map<?,?>)b).get("animationId")));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (Map.Entry<String,byte[]> entry : archive.entrySet()) {
                ZipEntry item = new ZipEntry(entry.getKey()); item.setTime(0L);
                zip.putNextEntry(item); zip.write(entry.getValue()); zip.closeEntry();
            }
        }
        return new Result(WorldBuilderSupplementalNpcDefinitions.customJson(rewritten), bytes.toByteArray(), animations);
    }

    private static Map<String,Object> presentation(Map<String,Object> old, Spec spec, int animationId) {
        Map<String,Object> npc = new LinkedHashMap<>(old);
        npc.put("sprites1", Long.valueOf(animationId));
        npc.put("camera1", Long.valueOf(spec.columns[0] * 12 / 5));
        npc.put("camera2", Long.valueOf(spec.height * 12 / 5));
        return npc;
    }

    private static int afterSprites(Object raw, int next) throws WorldBuilderContractException {
        if (!(raw instanceof Map)) throw problem("NPC definitions", "Invalid NPC record.");
        for (int slot = 1; slot <= 12; slot++) {
            Object value = ((Map<?,?>)raw).get("sprites" + slot);
            if (value instanceof Long && (Long)value >= 0 && (Long)value <= 65535) next = Math.max(next, ((Long)value).intValue() + 1);
        }
        return next;
    }

    private static int capability(Path jar, boolean client) throws WorldBuilderContractException {
        try (JarFile file = new JarFile(jar.toFile())) {
            java.util.jar.Attributes attrs = file.getManifest().getMainAttributes();
            if (!"npc-rgb-frames-v1".equals(attrs.getValue("World-Builder-Npc-Rgb"))) throw new IOException("missing RGB frame capability");
            if (!client) return 0;
            int count = Integer.parseInt(attrs.getValue("World-Builder-Npc-Animation-Count"));
            if (count < 1080 || count > 65535) throw new IOException("invalid animation capacity");
            return count;
        } catch (Exception failure) { throw problem(jar.getFileName().toString(), "Selected runtime cannot load lossless directional NPC frames; upgrade the World Builder runtime."); }
    }

    private static BufferedImage readImage(Path file, Spec spec) throws WorldBuilderContractException {
        try {
            if (Files.size(file) > 4 * 1024 * 1024) throw new IOException("PNG exceeds 4 MiB");
            int width = 0; for (int column : spec.columns) width += column;
            try (ImageInputStream input = ImageIO.createImageInputStream(file.toFile())) {
                if (input == null) throw new IOException("unreadable PNG");
                Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
                if (!readers.hasNext()) throw new IOException("unreadable PNG");
                ImageReader reader = readers.next();
                try {
                    if (!"png".equalsIgnoreCase(reader.getFormatName())) throw new IOException("not PNG");
                    reader.setInput(input, true, true);
                    if (reader.getWidth(0) != width || reader.getHeight(0) != spec.height * 3) throw new IOException("wrong sheet dimensions");
                    BufferedImage image = reader.read(0);
                    if (image == null) throw new IOException("empty PNG");
                    return image;
                } finally { reader.dispose(); }
            }
        } catch (IOException | RuntimeException failure) { throw problem(spec.path(), "Directional NPC artwork is invalid: " + failure.getMessage()); }
    }

    private static byte[] frame(BufferedImage image, int x, int y, int width, int height) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeInt(width); out.writeInt(height); out.writeByte(0);
        out.writeInt(0); out.writeInt(0); out.writeInt(width); out.writeInt(height);
        for (int row = 0; row < height; row++) for (int col = 0; col < width; col++) {
            int argb = image.getRGB(x + col, y + row), rgb = argb & 0xffffff;
            out.writeInt((argb >>> 24) < 64 ? 0 : rgb == 0 ? 0x010101 : rgb);
        }
        return bytes.toByteArray();
    }

    private static TreeMap<String,byte[]> readArchive(byte[] bytes) throws IOException, WorldBuilderContractException {
        TreeMap<String,byte[]> result = new TreeMap<>();
        Set<String> names = new HashSet<>(); Set<Integer> ids = new HashSet<>();
        long total = 0;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            for (ZipEntry entry; (entry = zip.getNextEntry()) != null;) {
                String name = entry.getName();
                WorldBuilderPortablePath.require(name, "npc-direction-sheets");
                Integer id = spriteId(name);
                if (entry.isDirectory() || result.size() >= 65536 || !names.add(name.toLowerCase(Locale.ROOT))
                    || id != null && !ids.add(id)) throw problem(VIDEO, "Authentic sprite archive has unsafe or duplicate entries.");
                ByteArrayOutputStream payload = new ByteArrayOutputStream(); byte[] buffer = new byte[8192];
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
        return leaf.matches("[0-9]{1,5}") ? Integer.valueOf(leaf) : null;
    }
    private static WorldBuilderContractException problem(String path, String message) {
        return new WorldBuilderContractException(WorldBuilderErrorCodes.DEFINITION_MISMATCH,
            "npc-direction-sheets", path, false, message,
            "Restore the selected directional NPC artwork and matching current runtime, then rediscover the target.");
    }
    static final class Result {
        final byte[] customDefinitions, authenticArchive;
        final List<Object> animations;
        Result(byte[] definitions, byte[] archive, List<Object> animations) {
            this.customDefinitions = definitions; this.authenticArchive = archive; this.animations = animations;
        }
        boolean changed() { return customDefinitions != null; }
    }
    private static final class Spec {
        final int id, height; final String name, asset; final int[] columns;
        Spec(int id, String name, String asset, int height, int... columns) {
            this.id = id; this.name = name; this.asset = asset; this.height = height; this.columns = columns;
        }
        String path() { return ROOT + asset + ".png"; }
    }
}
