package com.openrsc.worldbuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.FileVisitResult;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Explicit content revision transition. Target reads never install gameplay content. */
final class WorldBuilderProjectContentRefresh {
    static final String ORIGIN = "source/content-refresh/origin.json";
    private static final String OPERATION = "detect-new-content";
    interface Observer { void progress(String phase, long elapsedMillis); }
    private final Observer observer;
    private long started;
    private final List<Object> timings = new ArrayList<Object>();
    WorldBuilderProjectContentRefresh() { this(null); }
    WorldBuilderProjectContentRefresh(Observer observer) { this.observer = observer; }
    private void begin() { started = System.nanoTime(); timings.clear(); }
    private void phase(String phase) {
        long elapsed = (System.nanoTime() - started) / 1000000L;
        Map<String,Object> timing = new LinkedHashMap<String,Object>();
        timing.put("phase", phase); timing.put("elapsedMillis", Long.valueOf(elapsed)); timings.add(timing);
        if (observer != null) observer.progress(phase, elapsed);
    }


    Preview preview(Path project, Path runtime, Path target, int port)
        throws IOException, WorldBuilderContractException {
        try (WorldBuilderAdaptiveProjectLock ignored = WorldBuilderAdaptiveProjectLock.acquire(project, OPERATION)) {
            begin();
            return inspect(project, runtime, target, port);
        }
    }

    WorldBuilderAdaptiveProjectLifecycle.ProjectResult apply(Path project, Path runtime, Path target,
        int port, String expectedPreview, String confirmation) throws IOException, WorldBuilderContractException {
        if (!"REFRESH".equals(confirmation)) throw refusal("confirmation",
            "Content refresh requires exact REFRESH confirmation.", "Review Detect New Content and confirm the reviewed changes.");
        try (WorldBuilderAdaptiveProjectLock ignored = WorldBuilderAdaptiveProjectLock.acquire(project, OPERATION)) {
            begin();
            Preview current = inspect(project, runtime, target, port);
            if (!current.fingerprint.equals(expectedPreview)) throw drift("preview",
                "Project or target content changed after the refresh preview.", "Run Detect New Content again and review the updated changes.");
            if (!current.blockers.isEmpty()) throw refusal("content",
                "Content refresh has unresolved identity or removal conflicts: " + current.blockers,
                "Keep editing the preserved project; resolve the listed target definitions before refreshing.");
            if (current.unchanged) return new WorldBuilderAdaptiveProjectLifecycle.ProjectResult(
                current.parent.projectRoot, current.parent.projectId, current.parent.origin,
                WorldBuilderAdaptiveExporter.string(current.parent.manifest, "state"), current.parent.working.fingerprintSha256, port);
            Path report = Files.createTempFile(project.getParent().getParent(), ".content-refresh-report-", ".json");
            try {
                Files.write(report, WorldBuilderJsonDocuments.pretty(current.report).getBytes(StandardCharsets.UTF_8));
                phase("Publishing preserved content revision");
                return new WorldBuilderAdaptiveProjectLifecycle(new WorldBuilderAdaptiveProjectLifecycle.Observer() {
                    @Override public void observe(String milestone, Path stage) {
                        if ("source-prepared".equals(milestone)) phase("Captured verified target content");
                        if ("working-prepared".equals(milestone)) phase("Prepared preserved map and editor caches");
                        if ("active-published".equals(milestone)) phase("Selected new content revision");
                    }
                }).createContentRevision(
                    project.getParent().getParent(), runtime, target, report, current.parent,
                    current.authority, current.newContentSha256, port);
            } finally { Files.deleteIfExists(report); }
        }
    }

    private Preview inspect(Path project, Path runtime, Path target, int port)
        throws IOException, WorldBuilderContractException {
        WorldBuilderAdaptiveProjectLifecycle.VerifiedProject parent =
            WorldBuilderAdaptiveProjectLifecycle.verifyProjectDirectory(project, true);
        if ("standalone-empty".equals(parent.origin)
            || !Files.isDirectory(project.resolve(WorldBuilderProjectContentBundle.SOURCE_DIRECTORY), LinkOption.NOFOLLOW_LINKS))
            throw refusal("project", "This project does not have a supported captured target content library.",
                "Detect New Content currently supports attached declarative-content projects; preserve this project unchanged.");
        phase("Scanning effective target content");
        Map<String,Object> selected = WorldBuilderAdaptiveExporter.object(parent.snapshot.get("selectedConfiguration"), "selectedConfiguration");
        String role = WorldBuilderAdaptiveProjectLifecycle.rediscoveryRole(parent.discoveryReport);
        Map<String,Object> report = parse(new WorldBuilderAdaptiveDiscovery().discover(target, role.isEmpty() ? null : role).toJson());
        if ("compatible".equals(report.get("status"))) {
            Map<String,Object> currentSelection = WorldBuilderAdaptiveExporter.object(report.get("selectedConfiguration"), "selectedConfiguration");
            String expectedPath = WorldBuilderAdaptiveExporter.string(selected, "relativePath");
            if (!expectedPath.startsWith("source/original/")
                || !expectedPath.substring("source/original/".length()).equals(currentSelection.get("relativePath")))
                throw drift("selectedConfiguration", "Detected content belongs to a different active map configuration.",
                    "Select the original project target and resolve the map configuration change separately.");
        }
        phase("Verifying current map, runtime and transaction history");
        Map<String,Object> authority = WorldBuilderContentRefreshAuthority.verify(parent, target, report);
        WorldBuilderEffectiveContent.Index before = WorldBuilderEffectiveContent.index(
            WorldBuilderProjectContentBundle.read(project.resolve(WorldBuilderProjectContentBundle.SOURCE_DIRECTORY)));
        phase("Preparing complete content and visual dependencies");
        Path temporary = Files.createTempDirectory(project.getParent().getParent(), ".content-refresh-preview-");
        try {
            Path reportPath = temporary.resolve("discovery.json");
            Files.write(reportPath, WorldBuilderJsonDocuments.pretty(report).getBytes(StandardCharsets.UTF_8));
            WorldBuilderAdaptiveProjectLifecycle.ProjectResult captured = new WorldBuilderAdaptiveProjectLifecycle().create(
                temporary, runtime, target, reportPath, "Content refresh validation", port, "CREATE");
            WorldBuilderEffectiveContent.Index after = WorldBuilderEffectiveContent.index(
                WorldBuilderProjectContentBundle.read(captured.projectRoot.resolve(WorldBuilderProjectContentBundle.SOURCE_DIRECTORY)));
            phase("Comparing content identities and saved map references");
            Preview result = new Preview(parent, report, authority, before, after);
            if (!authority.equals(WorldBuilderContentRefreshAuthority.verify(parent, target, report)))
                throw drift("target", "Target authority changed while content was inspected.", "Stop target updates and repeat Detect New Content.");
            phase("Content review ready");
            result.document.put("phaseTimings", new ArrayList<Object>(timings));
            return result;
        } finally { deleteTemporary(temporary); }
    }

    static final class Origin {
        final WorldBuilderAdaptiveProjectLifecycle.VerifiedProject parent;
        final Map<String,Object> authority;
        final String expectedContentSha256;
        Origin(WorldBuilderAdaptiveProjectLifecycle.VerifiedProject parent, Map<String,Object> authority, String expected) {
            this.parent = parent; this.authority = authority; this.expectedContentSha256 = expected;
        }
        void capture(Path stage, Path target, Map<String,Object> report) throws IOException, WorldBuilderContractException {
            verify(target, report);
            WorldBuilderEffectiveContent.Index content = WorldBuilderEffectiveContent.index(
                WorldBuilderProjectContentBundle.read(stage.resolve(WorldBuilderProjectContentBundle.SOURCE_DIRECTORY)));
            if (!expectedContentSha256.equals(content.contentSha256)) throw drift("content",
                "Normalized content differs from the reviewed revision.", "Repeat Detect New Content and review the new revision.");
            Map<String,Object> document = new LinkedHashMap<String,Object>();
            document.put("schemaVersion", Long.valueOf(1));
            document.put("manifestType", "world-builder-content-refresh-origin");
            document.put("projectId", parent.projectId);
            document.put("projectFingerprintSha256", parent.manifest.get("projectFingerprintSha256"));
            document.put("sourceFingerprintSha256", parent.snapshot.get("sourceFingerprintSha256"));
            document.put("workingFingerprintSha256", parent.working.fingerprintSha256);
            document.put("contentFingerprintSha256", expectedContentSha256);
            document.put("targetAuthority", authority);
            Files.createDirectories(stage.resolve(ORIGIN).getParent());
            Files.write(stage.resolve(ORIGIN), WorldBuilderJsonDocuments.pretty(document).getBytes(StandardCharsets.UTF_8));
        }
        void verify(Path target, Map<String,Object> report) throws IOException, WorldBuilderContractException {
            WorldBuilderAdaptiveProjectLifecycle.VerifiedProject current =
                WorldBuilderAdaptiveProjectLifecycle.verifyProjectDirectory(parent.projectRoot, true);
            if (!parent.manifest.get("projectFingerprintSha256").equals(current.manifest.get("projectFingerprintSha256"))
                || !parent.working.fingerprintSha256.equals(current.working.fingerprintSha256)
                || !authority.equals(WorldBuilderContentRefreshAuthority.verify(current, target, report)))
                throw drift("project", "The predecessor or checked target changed before content publication.",
                    "Keep the preserved project and repeat Detect New Content.");
        }
    }

    static final class Preview {
        final WorldBuilderAdaptiveProjectLifecycle.VerifiedProject parent;
        final Map<String,Object> report, authority, document;
        final List<String> blockers;
        final String fingerprint, newContentSha256;
        final boolean unchanged;
        Preview(WorldBuilderAdaptiveProjectLifecycle.VerifiedProject parent, Map<String,Object> report,
            Map<String,Object> authority, WorldBuilderEffectiveContent.Index before, WorldBuilderEffectiveContent.Index after)
            throws IOException, WorldBuilderContractException {
            this.parent = parent; this.report = report; this.authority = authority; this.newContentSha256 = after.contentSha256;
            List<String> conflicts = new ArrayList<String>();
            List<Object> families = new ArrayList<Object>();
            Map<String,Map<Integer,Long>> references = referenceCounts(parent);
            for (String family : before.families.keySet()) {
                Map<Integer,WorldBuilderEffectiveContent.Entry> old = before.families.get(family), next = after.families.get(family);
                List<Object> added = new ArrayList<Object>(), changed = new ArrayList<Object>(), removed = new ArrayList<Object>(), visuals = new ArrayList<Object>(), details = new ArrayList<Object>();
                for (Map.Entry<Integer,WorldBuilderEffectiveContent.Entry> entry : old.entrySet()) {
                    Integer id = entry.getKey(); WorldBuilderEffectiveContent.Entry value = next.get(id);
                    long uses = references.get(family).containsKey(id) ? references.get(family).get(id).longValue() : 0L;
                    String identity = family + " " + id + " (“" + entry.getValue().name + "”, " + uses + " map references)";
                    if (value == null) {
                        removed.add(Long.valueOf(id)); conflicts.add(identity + " removed");
                        details.add(delta(id, entry.getValue(), null, uses, "removed"));
                    } else if (!entry.getValue().semanticSha256.equals(value.semanticSha256)) {
                        changed.add(Long.valueOf(id)); conflicts.add(identity + " changed identity/semantics to “" + value.name + "”");
                        details.add(delta(id, entry.getValue(), value, uses, "changed"));
                    } else if (!entry.getValue().visualSha256.equals(value.visualSha256)) {
                        visuals.add(Long.valueOf(id)); details.add(delta(id, entry.getValue(), value, uses, "visuals-changed"));
                    }
                }
                for (Integer id : next.keySet()) if (!old.containsKey(id)) {
                    added.add(Long.valueOf(id)); details.add(delta(id, null, next.get(id), 0L, "added"));
                }
                Map<String,Object> row = new LinkedHashMap<String,Object>(); row.put("family", family);
                row.put("added", added); row.put("changed", changed); row.put("removed", removed); row.put("visualsChanged", visuals); row.put("details", details); families.add(row);
            }
            this.blockers = Collections.unmodifiableList(conflicts);
            document = new LinkedHashMap<String,Object>();
            document.put("schemaVersion", Long.valueOf(1)); document.put("manifestType", "world-builder-content-refresh-preview");
            document.put("projectId", parent.projectId); document.put("projectFingerprintSha256", parent.manifest.get("projectFingerprintSha256"));
            document.put("workingFingerprintSha256", parent.working.fingerprintSha256);
            document.put("discoveryFingerprintSha256", report.get("discoveryFingerprintSha256"));
            document.put("targetRootDisplay", report.get("targetRootDisplay"));
            document.put("previousContentSha256", before.contentSha256); document.put("contentSha256", after.contentSha256);
            document.put("targetAuthority", authority); document.put("families", families); document.put("blockers", new ArrayList<String>(conflicts));
            unchanged = conflicts.isEmpty() && before.bundleFingerprintSha256.equals(after.bundleFingerprintSha256)
                && capturedContentAuthorityMatches(parent, authority);
            document.put("status", !conflicts.isEmpty() ? "blocked" : unchanged ? "unchanged" : "ready");
            fingerprint = WorldBuilderHashes.sha256(WorldBuilderJsonDocuments.canonical(document).getBytes(StandardCharsets.UTF_8));
            document.put("previewFingerprintSha256", fingerprint);
        }
        private static boolean capturedContentAuthorityMatches(WorldBuilderAdaptiveProjectLifecycle.VerifiedProject parent,
            Map<String,Object> authority) throws WorldBuilderContractException {
            Map<String,Map<String,Object>> captured = new java.util.TreeMap<String,Map<String,Object>>();
            for (String group : java.util.Arrays.asList("originalFiles", "definitionRuntimeFiles")) {
                for (Object raw : WorldBuilderAdaptiveExporter.array(parent.snapshot.get(group), group)) {
                    Map<String,Object> row = WorldBuilderAdaptiveExporter.object(raw, group);
                    String path = WorldBuilderAdaptiveExporter.string(row, "relativePath");
                    if (path.startsWith("source/original/")) captured.put(path.substring("source/original/".length()), row);
                }
            }
            Map<String,Object> states = WorldBuilderAdaptiveExporter.object(authority.get("targetStates"), "targetStates");
            for (String group : java.util.Arrays.asList("contentPaths", "catalogProjectionPaths")) {
                for (Object raw : WorldBuilderAdaptiveExporter.array(authority.get(group), group)) {
                    Map<String,Object> before = captured.get(String.valueOf(raw));
                    Map<String,Object> current = WorldBuilderAdaptiveExporter.object(states.get(String.valueOf(raw)), "targetState");
                    if (before == null) {
                        if (Boolean.FALSE.equals(current.get("present"))) continue;
                        return false;
                    }
                    for (String key : java.util.Arrays.asList("present", "size", "sha256"))
                        if (!java.util.Objects.equals(before.get(key), current.get(key))) return false;
                }
            }
            return true;
        }
        private static Map<String,Object> delta(int id, WorldBuilderEffectiveContent.Entry before,
            WorldBuilderEffectiveContent.Entry after, long references, String status) {
            Map<String,Object> result = new LinkedHashMap<String,Object>();
            result.put("id", Long.valueOf(id)); result.put("status", status);
            result.put("previousName", before == null ? "" : before.name);
            result.put("name", after == null ? "" : after.name); result.put("mapReferenceCount", Long.valueOf(references));
            result.put("previousSemanticSha256", before == null ? "" : before.semanticSha256);
            result.put("semanticSha256", after == null ? "" : after.semanticSha256);
            result.put("previousVisualSha256", before == null ? "" : before.visualSha256);
            result.put("visualSha256", after == null ? "" : after.visualSha256);
            return result;
        }
        private static String compact(Object raw) {
            List<?> values = (List<?>)raw;
            return values.size() <= 12 ? values.toString() : values.subList(0, 12) + " (" + values.size() + " total)";
        }
        String toJson() { return WorldBuilderJsonDocuments.pretty(document); }
        String summary() {
            if (unchanged) return "Detect New Content\n\nThe complete captured library and its compatibility evidence match the target. No content revision is needed; continue working in this project.";
            StringBuilder text = new StringBuilder("Detect New Content\n\nYour saved terrain and placements will be preserved. Previous content revisions, exports and receipts remain available.\nNo target files will be changed.\n\n");
            for (Object raw : (List<?>)document.get("families")) {
                @SuppressWarnings("unchecked") Map<String,Object> family = (Map<String,Object>)raw;
                text.append(family.get("family")).append(": added ").append(compact(family.get("added")))
                    .append("; changed ").append(compact(family.get("changed"))).append("; removed ").append(compact(family.get("removed")))
                    .append("; visuals ").append(compact(family.get("visualsChanged"))).append('\n');
                List<?> changes = (List<?>)family.get("details");
                for (int index = 0; index < Math.min(8, changes.size()); index++) {
                    @SuppressWarnings("unchecked") Map<String,Object> change = (Map<String,Object>)changes.get(index);
                    String name = String.valueOf(change.get("name"));
                    if (name.isEmpty()) name = String.valueOf(change.get("previousName"));
                    text.append("  ").append(change.get("status")).append(" ").append(change.get("id"))
                        .append(": ").append(name).append(" — ").append(change.get("mapReferenceCount")).append(" map references\n");
                }
            }
            if (!blockers.isEmpty()) text.append("\nRefresh is blocked: ").append(compact(blockers))
                .append("\n\nKeep working in the current preserved project. Restore the target IDs to their previous meanings, or resolve changes explicitly with the target maintainer. This version accepts additions and reviewed visual updates; it does not accept identity redefinitions or removals. No references are removed or substituted.");
            else text.append("\nAccept and continue with this content revision?");
            return text.toString();
        }
    }

    private static Map<String,Map<Integer,Long>> referenceCounts(WorldBuilderAdaptiveProjectLifecycle.VerifiedProject project)
        throws IOException, WorldBuilderContractException {
        Map<String,Map<Integer,Long>> counts = new java.util.TreeMap<String,Map<Integer,Long>>();
        for (String family : project.working.requiredDefinitionIds().keySet()) counts.put(family, new java.util.TreeMap<Integer,Long>());
        for (String semantic : project.working.placementSemantics) {
            String[] parts = semantic.split("\\u0000", 4);
            increment(counts.get(parts[0]), Integer.parseInt(parts[2]));
        }
        WorldBuilderReadOnlyTarget local = WorldBuilderReadOnlyTarget.open(project.projectRoot);
        String prefix = WorldBuilderAdaptiveProjectLifecycle.WORKING_PACKAGE_DIRECTORY + "/";
        Map<String,Object> manifest = local.readObject(prefix + "manifest.json");
        for (Object raw : WorldBuilderAdaptiveExporter.array(manifest.get("terrainSectors"), "terrainSectors")) {
            Map<String,Object> sector = WorldBuilderAdaptiveExporter.object(raw, "terrainSector");
            String encoding = WorldBuilderAdaptiveExporter.string(sector, "encoding");
            int size = WorldBuilderRawLayeredTerrainCodec.byteCount(encoding);
            String relative = prefix + WorldBuilderAdaptiveExporter.string(sector, "path");
            Path path = local.requiredFile(relative);
            byte[] bytes = new byte[size]; int offset = 0;
            try (java.io.InputStream input = Files.newInputStream(path)) {
                while (offset < size) { int read = input.read(bytes, offset, size - offset); if (read < 0) break; offset += read; }
                if (offset != size || input.read() != -1 || !WorldBuilderHashes.sha256(bytes).equals(sector.get("sha256")))
                    throw drift(relative, "Saved terrain changed while content references were reviewed.", "Save the complete map and repeat Detect New Content.");
            }
            int shift = WorldBuilderRawLayeredTerrainCodec.isWide(encoding) ? 1 : 0;
            for (int at = 0; at < size; at += WorldBuilderRawLayeredTerrainCodec.tileBytes(encoding)) {
                int overlay = bytes[at + shift + 2] & 255;
                int effective = overlay == 250 ? 2 : overlay;
                if (effective > 0 && !WorldBuilderTerrainOverlay.isBlockingBaseColor(effective)) increment(counts.get("floor"), effective - 1);
                int vertical = bytes[at + shift + 4] & 255, horizontal = bytes[at + shift + 5] & 255;
                if (vertical > 0) increment(counts.get("boundary"), vertical - 1);
                if (horizontal > 0) increment(counts.get("boundary"), horizontal - 1);
                int diagonal = java.nio.ByteBuffer.wrap(bytes, at + shift + 6, 4).getInt();
                if (diagonal > 0 && diagonal < 12000) increment(counts.get("boundary"), diagonal - 1);
                else if (diagonal > 12000 && diagonal < 24000) increment(counts.get("boundary"), diagonal - 12001);
            }
        }
        return counts;
    }
    private static void increment(Map<Integer,Long> counts, int id) {
        Long before = counts.get(Integer.valueOf(id)); counts.put(Integer.valueOf(id), Long.valueOf(before == null ? 1L : before.longValue() + 1L));
    }

    private static Map<String,Object> parse(String value) throws IOException {
        try { return WorldBuilderJsonDocuments.readObject(value.getBytes(StandardCharsets.UTF_8), OPERATION); }
        catch (WorldBuilderDiscoveryException failure) { throw new IOException("Discovery produced invalid content evidence.", failure); }
    }
    private static void deleteTemporary(Path root) throws IOException {
        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file); return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult postVisitDirectory(Path directory, IOException failure) throws IOException {
                if (failure != null) throw failure; Files.delete(directory); return FileVisitResult.CONTINUE;
            }
        });
    }
    private static WorldBuilderContractException drift(String source, String message, String next) {
        return new WorldBuilderContractException(WorldBuilderErrorCodes.TARGET_DRIFT, OPERATION, source, false, message, next);
    }
    private static WorldBuilderContractException refusal(String source, String message, String next) {
        return new WorldBuilderContractException(WorldBuilderErrorCodes.DEFINITION_MISMATCH, OPERATION, source, false, message, next);
    }
}
