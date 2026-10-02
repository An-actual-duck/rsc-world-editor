package com.openrsc.worldbuilder;

import static com.openrsc.worldbuilder.WorldBuilderNpcProducerV2.*;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.jar.JarFile;
import java.util.zip.*;

/** Lossless private frame projection; never writes target assets or executes producer code. */
final class WorldBuilderNpcProducerFrames {
    static final long MAX_EXPANDED = 512L * 1024 * 1024, MAX_RGB = 256L * 1024 * 1024;

    static final class Result {
        final List<Object> animations, overlays, limitations;
        final byte[] authenticArchive;

        Result(
                List<Object> animations,
                List<Object> overlays,
                List<Object> limitations,
                byte[] authenticArchive) {
            this.animations = animations;
            this.overlays = overlays;
            this.limitations = limitations;
            this.authenticArchive = authenticArchive;
        }
    }

    static Result normalize(
            Capture capture,
            WorldBuilderReadOnlyTarget target,
            List<Object> existing,
            byte[] authenticOverride,
            Path clientJar,
            Path serverJar)
            throws IOException, WorldBuilderContractException {
        int next = capability(clientJar, true);
        capability(serverJar, false);
        for (Map<String, Object> row : capture.document.animations.values())
            next = Math.max(next, number(row, "animationId", 0, 65535) + 1);
        List<Object> animations = new ArrayList<>(existing);
        for (Object raw : existing)
            next = Math.max(next, number(object(raw), "animationId", 0, 65535) + 1);
        byte[] bytes =
                authenticOverride == null
                        ? readBounded(
                                target.requiredFile(
                                        "Client_Base/Cache/video/Authentic_Sprites.orsc"),
                                128L * 1024 * 1024)
                        : authenticOverride;
        Map<String, byte[]> archive = zip(bytes);
        int nextSprite = 0;
        for (String name : archive.keySet()) {
            Integer id = spriteId(name);
            if (id != null) nextSprite = Math.max(nextSprite, id + 1);
        }
        long sourceBudget = 0;
        for (byte[] frame : archive.values()) sourceBudget += frame.length;
        Map<String, Map<String, byte[]>> assets = new HashMap<>();
        Map<String, Map<String, OsarEntry>> osars = new HashMap<>();
        Map<String, Set<String>> usedRgbEntries = new HashMap<>();
        Map<Integer, Integer> remap = new TreeMap<>();
        List<Object> limitations = new ArrayList<>();
        long rgbBudget = 0;
        for (Object raw : existing) {
            Map<String, Object> row = object(raw);
            if (!"authentic-rgb".equals(row.get("frameSource"))) continue;
            int base = number(row, "authenticBaseSpriteId", 0, 65535),
                    count = number(row, "requiredFrameCount", 15, 27);
            for (int i = 0; i < count; i++) {
                byte[] frame = findSprite(archive, base + i);
                validateRgb(frame);
                rgbBudget += frame.length;
            }
        }
        for (Map.Entry<Integer, Map<String, Object>> entry :
                capture.document.animations.entrySet()) {
            Map<String, Object> source = entry.getValue(),
                    frameSource = object(source.get("frames")),
                    asset = capture.document.assets.get(identity(frameSource, "assetId"));
            String assetId = identity(asset, "assetId"), format = text(asset, "format", 64);
            Path file = target.requiredFile(path(asset, "targetRelativePath"));
            List<byte[]> resolved = new ArrayList<>();
            int sourceCount = number(source, "resolvedFrameCount", 1, 256);
            if ("openrsc-osar-v1".equals(format)) {
                Map<String, OsarEntry> indexed = osars.get(assetId);
                if (indexed == null) {
                    indexed = osar(file);
                    for (OsarEntry value : indexed.values())
                        for (byte[] frame : value.frames) sourceBudget += frame.length;
                    if (sourceBudget > MAX_EXPANDED)
                        throw failure(FILE, "Combined NPC source frame closure exceeds512MiB.");
                    osars.put(assetId, indexed);
                }
                OsarEntry selected =
                        indexed.get(
                                text(frameSource, "subspace", 128)
                                        + "/"
                                        + text(frameSource, "entry", 128));
                if (selected == null || !selected.sha256.equals(hash(frameSource, "entrySha256")))
                    throw failure(FILE, "NPC OSAR source entry differs from captured authority.");
                resolved.addAll(selected.frames);
            } else {
                Map<String, byte[]> indexed = assets.get(assetId);
                if (indexed == null) {
                    indexed = zip(readBounded(file, 128L * 1024 * 1024));
                    for (byte[] frame : indexed.values()) sourceBudget += frame.length;
                    if (sourceBudget > MAX_EXPANDED)
                        throw failure(FILE, "Combined NPC source frame closure exceeds512MiB.");
                    assets.put(assetId, indexed);
                }
                List<?> hashes =
                        array(
                                frameSource.get(
                                        "world-builder-rgb-frame-zip-v1".equals(format)
                                                ? "frameSha256s"
                                                : "entrySha256s"),
                                sourceCount,
                                sourceCount);
                List<?> keys =
                        "world-builder-rgb-frame-zip-v1".equals(format)
                                ? array(frameSource.get("frameKeys"), sourceCount, sourceCount)
                                : null;
                int base =
                        keys == null
                                ? number(frameSource, "baseSpriteId", 0, 65536 - sourceCount)
                                : 0;
                for (int i = 0; i < sourceCount; i++) {
                    byte[] frame =
                            keys == null
                                    ? findSprite(indexed, base + i)
                                    : indexed.get((String) keys.get(i));
                    if (frame == null
                            || !requireHash(hashes.get(i)).equals(WorldBuilderHashes.sha256(frame)))
                        throw failure(
                                FILE, "NPC resolved frame bytes differ from captured authority.");
                    if (keys != null)
                        usedRgbEntries
                                .computeIfAbsent(assetId, k -> new HashSet<>())
                                .add((String) keys.get(i));
                    resolved.add(frame);
                }
            }
            if (resolved.size() != sourceCount)
                throw failure(
                        FILE, "NPC resolved source frame count differs from complete evidence.");
            for (byte[] frame : resolved) validateRgb(frame);
            Map<String, Object> preview = object(source.get("authoringPreview"));
            List<?> offsets = array(preview.get("frameIndices"), 15, 27);
            if (next > 65535 || nextSprite > 65536 - offsets.size())
                throw failure(FILE, "Private NPC presentation capacity is exhausted.");
            Map<String, Object> animation = new LinkedHashMap<>();
            int privateId = next++;
            remap.put(entry.getKey(), privateId);
            animation.put("animationId", Long.valueOf(privateId));
            animation.put("name", "producer_v2_" + entry.getKey());
            animation.put("category", "npc");
            for (String key :
                    Arrays.asList("charColour", "blueMask", "genderModel", "npcMaskPolicy"))
                animation.put(key, source.get(key));
            animation.put("sourceAnimationId", Long.valueOf(entry.getKey()));
            animation.put("sourceCustomSprites", capture.document.customSprites);
            animation.put("hasCombatFrames", preview.get("hasCombatFrames"));
            animation.put("hasSpecialCombatFrames", preview.get("hasSpecialCombatFrames"));
            animation.put("requiredFrameCount", Long.valueOf(offsets.size()));
            animation.put("frameSource", "authentic-rgb");
            animation.put("authenticBaseSpriteId", Long.valueOf(nextSprite));
            List<String> hashes = new ArrayList<>();
            for (Object rawOffset : offsets) {
                byte[] frame = resolved.get(integer(rawOffset, 0, sourceCount - 1));
                rgbBudget += frame.length;
                if (rgbBudget > MAX_RGB)
                    throw failure(FILE, "Private NPC RGB frames exceed256MiB.");
                hashes.add(WorldBuilderHashes.sha256(frame));
                archive.put("sprites/" + (nextSprite++) + ".dat", frame);
            }
            animation.put("authenticFrameSha256s", hashes);
            animations.add(animation);
            for (Object limitation : array(preview.get("limitations"), 0, 2)) {
                Map<String, Object> report = new TreeMap<>();
                report.put("sourceAnimationId", entry.getKey());
                report.put("limitation", limitation);
                report.put("resolvedFrameCount", sourceCount);
                report.put("authoringFrameIndices", offsets);
                limitations.add(report);
            }
        }
        for (Map.Entry<String, Map<String, byte[]>> asset : assets.entrySet())
            if ("world-builder-rgb-frame-zip-v1"
                            .equals(capture.document.assets.get(asset.getKey()).get("format"))
                    && !asset.getValue().keySet().equals(usedRgbEntries.get(asset.getKey())))
                throw failure(
                        FILE, "Resolved NPC RGB archive contains undeclared or unused entries.");
        List<Object> overlays = new ArrayList<>();
        for (Map.Entry<Integer, Map<String, Object>> entry : capture.document.npcs.entrySet()) {
            Map<String, Object> source = entry.getValue(), row = new LinkedHashMap<>();
            row.put("id", Long.valueOf(entry.getKey()));
            List<?> slots = array(source.get("spriteAnimationIds"), 12, 12);
            for (int i = 0; i < 12; i++) {
                int id = integer(slots.get(i), -1, 65535);
                row.put("sprites" + (i + 1), Long.valueOf(id < 0 ? -1 : remap.get(id)));
            }
            for (String key :
                    Arrays.asList(
                            "hairColour",
                            "topColour",
                            "bottomColour",
                            "skinColour",
                            "walkModel",
                            "combatModel",
                            "combatSprite")) row.put(key, source.get(key));
            row.put("camera1", source.get("cameraWidth"));
            row.put("camera2", source.get("cameraHeight"));
            overlays.add(row);
        }
        animations.sort(
                (a, b) ->
                        Long.compare(
                                (Long) ((Map<?, ?>) a).get("animationId"),
                                (Long) ((Map<?, ?>) b).get("animationId")));
        return new Result(animations, overlays, limitations, writeZip(archive));
    }

    static final String REPORT = "diagnostics/npc-producer-v2-resolution.json";

    static void writeReport(
            Path project, Capture capture, Result result, WorldBuilderEffectiveContent.Index index)
            throws IOException, WorldBuilderContractException {
        List<Object> rows = new ArrayList<>();
        for (Object raw : result.limitations) {
            Map<String, Object> limit = object(raw);
            long source = ((Number) limit.get("sourceAnimationId")).longValue();
            for (Map.Entry<Integer, Map<String, Object>> npc : capture.document.npcs.entrySet())
                if (((List<?>) npc.getValue().get("spriteAnimationIds"))
                        .contains(Long.valueOf(source))) {
                    Map<String, Object> row = new TreeMap<>(limit);
                    row.put("npcId", Long.valueOf(npc.getKey()));
                    row.put("name", index.families.get("npc").get(npc.getKey()).name);
                    rows.add(row);
                }
        }
        Map<String, Object> report = new TreeMap<>();
        report.put("schemaVersion", Long.valueOf(1));
        report.put("manifestType", "world-builder-complete-npc-presentation");
        report.put("producerManifest", capture.manifestPath);
        report.put("npcCount", Long.valueOf(capture.document.npcs.size()));
        report.put("animationCount", Long.valueOf(capture.document.animations.size()));
        report.put("previewLimitations", rows);
        Path path = project.resolve(REPORT);
        Files.createDirectories(path.getParent());
        Files.write(
                path, WorldBuilderJsonDocuments.pretty(report).getBytes(StandardCharsets.UTF_8));
    }

    static List<Object> readPreviewLimitations(Path project)
            throws IOException, WorldBuilderContractException {
        Path path = project.resolve(REPORT);
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return Collections.emptyList();
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(path)
                || Files.size(path) > 16 * 1024 * 1024)
            throw failure(REPORT, "NPC authoring preview report is unsafe or oversized.");
        try {
            Map<String, Object> report = WorldBuilderJsonDocuments.readTargetDefinitionObject(path);
            exact(
                    report,
                    "schemaVersion",
                    "manifestType",
                    "producerManifest",
                    "npcCount",
                    "animationCount",
                    "previewLimitations");
            if (!Long.valueOf(1).equals(report.get("schemaVersion"))
                    || !"world-builder-complete-npc-presentation"
                            .equals(report.get("manifestType")))
                throw failure(REPORT, "Unsupported NPC preview report.");
            List<Object> result = new ArrayList<>();
            for (Object raw : array(report.get("previewLimitations"), 0, 65536)) {
                Map<String, Object> row = object(raw);
                exact(
                        row,
                        "sourceAnimationId",
                        "limitation",
                        "resolvedFrameCount",
                        "authoringFrameIndices",
                        "npcId",
                        "name");
                number(row, "sourceAnimationId", 0, 65535);
                number(row, "npcId", 0, 65535);
                text(row, "name", 1024);
                int count = number(row, "resolvedFrameCount", 1, 256);
                for (Object i : array(row.get("authoringFrameIndices"), 15, 27))
                    integer(i, 0, count - 1);
                if (!Arrays.asList(
                                "source-animation-cadence-not-reproduced",
                                "source-secondary-attack-not-previewed")
                        .contains(row.get("limitation")))
                    throw failure(REPORT, "Unknown NPC preview limitation.");
                result.add(Collections.unmodifiableMap(new TreeMap<>(row)));
            }
            return Collections.unmodifiableList(result);
        } catch (WorldBuilderDiscoveryException invalid) {
            throw failure(REPORT, "Malformed NPC preview report.");
        }
    }

    static String projectPreviewSummary(Path project) {
        if (project == null) return null;
        try {
            List<Object> rows = readPreviewLimitations(project);
            if (rows.isEmpty()) return null;
            Set<Long> ids = new TreeSet<>();
            for (Object raw : rows) ids.add((Long) object(raw).get("npcId"));
            StringBuilder text =
                    new StringBuilder(
                                    "\n\n"
                                        + "Verified NPC visuals use a bounded authoring animation"
                                        + " preview for ")
                            .append(ids.size())
                            .append(
                                    " NPCs. Source-specific cadence or secondary attacks are"
                                            + " retained as evidence but are not reproduced by the"
                                            + " building preview.");
            for (int i = 0; i < Math.min(5, rows.size()); i++) {
                Map<String, Object> row = object(rows.get(i));
                text.append("\nNPC ")
                        .append(row.get("npcId"))
                        .append(" (")
                        .append(row.get("name"))
                        .append("): ")
                        .append(row.get("limitation"));
            }
            if (rows.size() > 5)
                text.append("\n... and ").append(rows.size() - 5).append(" more entries.");
            return text.append("\nFull preview report: ")
                    .append(project.resolve(REPORT))
                    .toString();
        } catch (Exception invalid) {
            return null;
        }
    }

    static int capability(Path jar, boolean client)
            throws IOException, WorldBuilderContractException {
        try (JarFile file = new JarFile(jar.toFile())) {
            java.util.jar.Attributes attributes = file.getManifest().getMainAttributes();
            if (!"npc-rgb-frames-v1".equals(attributes.getValue("World-Builder-Npc-Rgb"))
                    || !"npc-mask-policy-v1"
                            .equals(attributes.getValue("World-Builder-Npc-Mask-Policy")))
                throw failure(
                        jar.toString(),
                        "Private authoring runtime lacks verified NPC RGB/mask-policy support;"
                                + " update World Builder.");
            if (!client) return 0;
            int count = Integer.parseInt(attributes.getValue("World-Builder-Npc-Animation-Count"));
            if (count < 1080 || count > 65535)
                throw failure(jar.toString(), "Private NPC animation capacity is invalid.");
            return count;
        } catch (NumberFormatException | NullPointerException invalid) {
            throw failure(jar.toString(), "Private NPC presentation capability is malformed.");
        }
    }

    static void validateRgb(byte[] bytes) throws WorldBuilderContractException {
        if (bytes == null || bytes.length < 25 || bytes.length > 16 * 1024 * 1024)
            throw failure(FILE, "NPC RGB frame is missing, truncated, or oversized.");
        ByteBuffer data = ByteBuffer.wrap(bytes);
        int width = data.getInt(),
                height = data.getInt(),
                shift = data.get() & 255,
                x = data.getInt(),
                y = data.getInt(),
                bw = data.getInt(),
                bh = data.getInt();
        if (width < 1
                || height < 1
                || width > 4096
                || height > 4096
                || shift > 1
                || x < -4096
                || x > 4096
                || y < -4096
                || y > 4096
                || bw < 1
                || bh < 1
                || bw > 4096
                || bh > 4096
                || 25L + 4L * width * height != bytes.length)
            throw failure(FILE, "NPC RGB frame geometry is outside supported bounds.");
        while (data.hasRemaining())
            if ((data.getInt() & 0xff000000) != 0)
                throw failure(FILE, "NPC frame pixel contains unsupported alpha/high bits.");
    }

    static Map<String, byte[]> zip(byte[] bytes) throws IOException, WorldBuilderContractException {
        Map<String, byte[]> result = new TreeMap<>();
        Set<Integer> numericIds = new HashSet<>();
        long expanded = 0;
        try (ZipInputStream input = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                String name = portable(entry.getName());
                Integer numeric = spriteId(name);
                if (numeric != null && !numericIds.add(numeric))
                    throw failure(FILE, "Duplicate numeric NPC frame identity.");
                if (entry.isDirectory() || result.size() >= 32768 || result.containsKey(name))
                    throw failure(
                            FILE,
                            "NPC frame archive contains directories, duplicates, or too many"
                                    + " entries.");
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                byte[] block = new byte[8192];
                int n;
                while ((n = input.read(block)) != -1) {
                    expanded += n;
                    if (expanded > MAX_EXPANDED || output.size() + n > 16 * 1024 * 1024)
                        throw failure(FILE, "NPC frame archive exceeds its expanded bounds.");
                    output.write(block, 0, n);
                }
                result.put(name, output.toByteArray());
            }
        }
        if (result.isEmpty()) throw failure(FILE, "NPC frame archive is empty or not a ZIP.");
        return result;
    }

    private static byte[] writeZip(Map<String, byte[]> entries) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream output = new ZipOutputStream(bytes)) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                ZipEntry item = new ZipEntry(entry.getKey());
                item.setTime(0);
                output.putNextEntry(item);
                output.write(entry.getValue());
                output.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    private static Integer spriteId(String name) {
        String plain = name.startsWith("sprites/") ? name.substring(8) : name;
        if (plain.endsWith(".dat")) plain = plain.substring(0, plain.length() - 4);
        if (!plain.matches("[0-9]{1,5}")) return null;
        int value = Integer.parseInt(plain);
        return value <= 65535 ? value : null;
    }

    private static byte[] findSprite(Map<String, byte[]> entries, int id)
            throws WorldBuilderContractException {
        byte[] found = null;
        for (String key :
                Arrays.asList(
                        Integer.toString(id),
                        id + ".dat",
                        "sprites/" + id,
                        "sprites/" + id + ".dat")) {
            byte[] value = entries.get(key);
            if (value != null) {
                if (found != null) throw failure(FILE, "Duplicate numeric NPC frame identity.");
                found = value;
            }
        }
        if (found == null)
            for (Map.Entry<String, byte[]> entry : entries.entrySet())
                if (Integer.valueOf(id).equals(spriteId(entry.getKey()))) found = entry.getValue();
        if (found == null) throw failure(FILE, "NPC authentic frame is missing: " + id);
        return found;
    }

    static final class OsarEntry {
        final String sha256;
        final List<byte[]> frames;

        OsarEntry(String sha256, List<byte[]> frames) {
            this.sha256 = sha256;
            this.frames = frames;
        }
    }

    static Map<String, OsarEntry> osar(Path path)
            throws IOException, WorldBuilderContractException {
        // Reuse the existing strict identity/frame-count validator before decoding.
        Map<String, WorldBuilderNpcDefinitionProvider.SpriteEntry> verified =
                WorldBuilderNpcDefinitionProvider.readOsar(path);
        ByteArrayOutputStream expanded = new ByteArrayOutputStream();
        try (InputStream input = new GZIPInputStream(Files.newInputStream(path))) {
            byte[] block = new byte[8192];
            int n;
            while ((n = input.read(block)) != -1) {
                if (expanded.size() + n > MAX_EXPANDED)
                    throw failure(FILE, "NPC OSAR exceeds512MiB.");
                expanded.write(block, 0, n);
            }
        }
        ByteBuffer input = ByteBuffer.wrap(expanded.toByteArray());
        Map<String, OsarEntry> result = new TreeMap<>();
        long rgbBytes = 0;
        try {
            int spaces = input.get() & 255;
            for (int space = 0; space < spaces; space++) {
                String subspace = name(input);
                int entries = input.getShort() & 65535;
                for (int e = 0; e < entries; e++) {
                    String entry = name(input), key = subspace + "/" + entry;
                    int type = input.get() & 255;
                    if (type >= 1 && type <= 3) input.get();
                    int count = input.get() & 255, paletteSize = (input.get() & 255) + 1;
                    int[] palette = new int[paletteSize];
                    for (int i = 0; i < palette.length; i++)
                        palette[i] =
                                ((input.get() & 255) << 16)
                                        | ((input.get() & 255) << 8)
                                        | (input.get() & 255);
                    List<byte[]> frames = new ArrayList<>();
                    for (int f = 0; f < count; f++) {
                        int width = input.getShort() & 65535,
                                height = input.getShort() & 65535,
                                shift = input.get() & 255,
                                x = input.getShort(),
                                y = input.getShort(),
                                bw = input.getShort() & 65535,
                                bh = input.getShort() & 65535;
                        long size = 25L + 4L * width * height;
                        rgbBytes += size;
                        if (size > 16 * 1024 * 1024 || rgbBytes > MAX_RGB)
                            throw failure(FILE, "NPC OSAR frame closure exceeds RGB bounds.");
                        ByteBuffer frame = ByteBuffer.allocate((int) size);
                        frame.putInt(width)
                                .putInt(height)
                                .put((byte) shift)
                                .putInt(x)
                                .putInt(y)
                                .putInt(bw)
                                .putInt(bh);
                        for (long i = 0; i < (long) width * height; i++)
                            frame.putInt(palette[input.get() & 255]);
                        validateRgb(frame.array());
                        frames.add(frame.array());
                    }
                    result.put(key, new OsarEntry(verified.get(key).sha256, frames));
                }
            }
            if (input.hasRemaining()) throw failure(FILE, "NPC OSAR has trailing bytes.");
        } catch (RuntimeException invalid) {
            throw failure(FILE, "NPC OSAR frame payload is malformed.");
        }
        return result;
    }

    private static String name(ByteBuffer input) {
        StringBuilder result = new StringBuilder();
        int next;
        while ((next = input.get() & 255) != 0) result.append((char) next);
        return result.toString();
    }
}
