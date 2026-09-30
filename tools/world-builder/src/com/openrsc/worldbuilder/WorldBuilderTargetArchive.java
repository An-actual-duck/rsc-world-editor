package com.openrsc.worldbuilder;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/** Bounded archive intake. Read occurrences sequentially: Java 8 ZipFile reads by name. */
final class WorldBuilderTargetArchive {
    static final int MAX_ARCHIVE = 256 * 1024 * 1024;
    static final int MAX_ENTRY = 16 * 1024 * 1024;
    static final int MAX_ENTRIES = 100000;
    private static final String PACKAGING = "Keep the target offline and have its maintainer rebuild the named archive from the target's own sources and dependencies, preserving all legal notices and custom behavior; then retry the preview.";
    private WorldBuilderTargetArchive() { }

    static Map<String,byte[]> read(Path archive, String relative, boolean changing, Map<String,Integer> normalized)
        throws IOException, WorldBuilderContractException {
        return read(archive, relative, changing, normalized, MAX_ARCHIVE, MAX_ENTRY, MAX_ENTRIES);
    }

    // Smaller bounds support inexpensive adversarial fixtures; production bounds cannot be raised.
    static Map<String,byte[]> read(Path archive, String relative, boolean changing, Map<String,Integer> normalized,
        int archiveLimit, int entryLimit, int countLimit) throws IOException, WorldBuilderContractException {
        if (archiveLimit <= 0 || archiveLimit > MAX_ARCHIVE || entryLimit <= 0 || entryLimit > MAX_ENTRY
            || countLimit <= 0 || countLimit > MAX_ENTRIES) throw new IllegalArgumentException("Invalid archive bounds");
        long size = Files.size(archive);
        if (size > archiveLimit) throw limit(relative, "", "compressed file bytes", size, archiveLimit);
        Map<String,Integer> inventory = new HashMap<String,Integer>();
        Map<String,Integer> occurrences = new HashMap<String,Integer>();
        Map<String,byte[]> result = new TreeMap<String,byte[]>();
        int count = 0;
        try {
            // Central directory is what the JVM uses. Require the same occurrence inventory as the
            // local streams, including CRC/size/method, instead of trusting lookup-by-name results.
            try (ZipFile zip = new ZipFile(archive.toFile())) {
                Enumeration<? extends ZipEntry> items = zip.entries();
                while (items.hasMoreElements()) {
                    ZipEntry entry = items.nextElement(); String name = entry.getName();
                    if (++count > countLimit) throw limit(relative, name, "entry count", count, countLimit);
                    if (!entry.isDirectory()) {
                        try { WorldBuilderPortablePath.require(name, "target-map-integration"); }
                        catch (WorldBuilderContractException invalid) { throw problem(relative, name, "Unsafe archive entry name.", PACKAGING); }
                        if (name.equalsIgnoreCase("META-INF/MANIFEST.MF") && !name.equals("META-INF/MANIFEST.MF"))
                            throw problem(relative, name, "Target archive manifest must use the unique canonical META-INF/MANIFEST.MF entry.", PACKAGING);
                        if (changing && name.toUpperCase(Locale.ROOT).matches("META-INF/[^/]+\\.(SF|RSA|DSA|EC)"))
                            throw problem(relative, name, "Signed target archives require a separate reviewed adapter.",
                                "Keep the target offline and request a reviewed signing-aware integration; do not strip signatures to bypass this check.");
                    }
                    if (entry.getSize() > entryLimit) throw limit(relative, name, "declared expanded entry bytes", entry.getSize(), entryLimit);
                    String identity = identity(entry);
                    inventory.put(identity, inventory.getOrDefault(identity, 0) + 1);
                }
            }
            long total = 0; int localCount = 0;
            try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(archive))) {
                for (ZipEntry entry; (entry = zip.getNextEntry()) != null;) {
                    String name = entry.getName();
                    if (++localCount > countLimit) throw limit(relative, name, "entry count", localCount, countLimit);
                    ByteArrayOutputStream bytes = new ByteArrayOutputStream(); byte[] buffer = new byte[8192];
                    for (int read; (read = zip.read(buffer)) >= 0;) {
                        long observed = (long)bytes.size() + read;
                        if (observed > entryLimit) throw limit(relative, name, "expanded entry bytes read", observed, entryLimit);
                        total += read;
                        if (total > archiveLimit) throw limit(relative, name, "total expanded bytes read", total, archiveLimit);
                        bytes.write(buffer, 0, read);
                    }
                    String identity = identity(entry); Integer remaining = inventory.get(identity);
                    if (remaining == null || remaining == 0) throw problem(relative, name,
                        "Archive local entries disagree with its central directory.", PACKAGING);
                    inventory.put(identity, remaining - 1);
                    if (entry.isDirectory()) {
                        if (bytes.size() != 0) throw problem(relative, name, "Archive directory contains file data.", PACKAGING);
                        continue;
                    }
                    int occurrence = occurrences.getOrDefault(name, 0) + 1; occurrences.put(name, occurrence);
                    byte[] content = bytes.toByteArray(), previous = result.get(name);
                    if (previous != null) {
                        if (!legalNotice(name)) throw problem(relative, name,
                            "Duplicate archive entry (occurrence " + occurrence + "); only byte-identical legal notices may repeat.", PACKAGING);
                        if (!Arrays.equals(previous, content)) throw problem(relative, name,
                            "Repeated legal notice has differing bytes (occurrence " + occurrence + "); automatic normalization would discard notice text.", PACKAGING);
                        normalized.put(name, occurrence);
                    } else result.put(name, content);
                }
            }
            if (localCount != count || inventory.values().stream().anyMatch(value -> value != 0))
                throw problem(relative, "", "Archive local entry count " + localCount + " disagrees with central directory count " + count + ".", PACKAGING);
            // Also bind the bytes looked up by the JVM to the sequentially checked payloads.
            // Matching central metadata alone does not prove its local-header offsets are honest.
            try (ZipFile zip = new ZipFile(archive.toFile())) {
                byte[] buffer = new byte[8192];
                for (Map.Entry<String,byte[]> checked : result.entrySet()) {
                    byte[] expected = checked.getValue(); int offset = 0;
                    try (InputStream input = zip.getInputStream(zip.getEntry(checked.getKey()))) {
                        for (int read; (read = input.read(buffer)) >= 0;) {
                            if ((long)offset + read > expected.length) throw problem(relative, checked.getKey(),
                                "Archive JVM lookup bytes disagree with its checked local entry.", PACKAGING);
                            for (int i = 0; i < read; i++) if (buffer[i] != expected[offset + i]) throw problem(relative, checked.getKey(),
                                "Archive JVM lookup bytes disagree with its checked local entry.", PACKAGING);
                            offset += read;
                        }
                    }
                    if (offset != expected.length) throw problem(relative, checked.getKey(),
                        "Archive JVM lookup bytes disagree with its checked local entry.", PACKAGING);
                }
            }
        } catch (ZipException | EOFException malformed) {
            throw problem(relative, "", "Target archive is malformed or unsupported: " + malformed.getMessage(), PACKAGING);
        }
        return result;
    }

    private static boolean legalNotice(String name) {
        return name.matches("(?:META-INF/)?(?:LICENSE|NOTICE|COPYING)(?:\\.txt)?");
    }
    private static String identity(ZipEntry entry) {
        return entry.getName() + "\u0000" + entry.getMethod() + ":" + entry.getSize() + ":" + entry.getCrc();
    }
    private static WorldBuilderContractException limit(String archive, String entry, String kind, long observed, long limit) {
        return problem(archive, entry, "Archive limit exceeded: " + kind + " " + observed + ", limit " + limit + ".",
            "Keep the target offline and provide the named archive and measured limit to the World Builder maintainer for a bounded compatibility review; do not remove game content to bypass the limit.");
    }
    private static WorldBuilderContractException problem(String archive, String entry, String message, String nextStep) {
        return new WorldBuilderContractException(WorldBuilderErrorCodes.RUNTIME_UPGRADE_REQUIRED, "target-map-integration",
            archive + (entry.isEmpty() ? "" : "!/" + entry), false,
            message + " Archive: " + archive + (entry.isEmpty() ? "." : "; entry: " + entry + "."), nextStep);
    }
}
