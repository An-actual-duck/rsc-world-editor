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

    Preview preview(Path project, Path runtime, Path target, int port)
        throws IOException, WorldBuilderContractException {
        try (WorldBuilderAdaptiveProjectLock ignored = WorldBuilderAdaptiveProjectLock.acquire(project, OPERATION)) {
            return inspect(project, runtime, target, port);
        }
    }

    WorldBuilderAdaptiveProjectLifecycle.ProjectResult apply(Path project, Path runtime, Path target,
        int port, String expectedPreview, String confirmation) throws IOException, WorldBuilderContractException {
        if (!"REFRESH".equals(confirmation)) throw refusal("confirmation",
            "Content refresh requires exact REFRESH confirmation.", "Review Detect New Content and confirm the reviewed changes.");
        try (WorldBuilderAdaptiveProjectLock ignored = WorldBuilderAdaptiveProjectLock.acquire(project, OPERATION)) {
            Preview current = inspect(project, runtime, target, port);
            if (!current.fingerprint.equals(expectedPreview)) throw refusal("preview",
                "Project or target content changed after the refresh preview.", "Run Detect New Content again and review the updated changes.");
            if (!current.blockers.isEmpty()) throw refusal("content",
                "Content refresh has unresolved identity or removal conflicts: " + current.blockers,
                "Keep editing the preserved project; resolve the listed target definitions before refreshing.");
            Path report = Files.createTempFile(project.getParent().getParent(), ".content-refresh-report-", ".json");
            try {
                Files.write(report, WorldBuilderJsonDocuments.pretty(current.report).getBytes(StandardCharsets.UTF_8));
                return new WorldBuilderAdaptiveProjectLifecycle().createContentRevision(
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
        Map<String,Object> selected = WorldBuilderAdaptiveExporter.object(parent.snapshot.get("selectedConfiguration"), "selectedConfiguration");
        String role = WorldBuilderAdaptiveExporter.string(selected, "role");
        Map<String,Object> report = parse(new WorldBuilderAdaptiveDiscovery().discover(target, role.isEmpty() ? null : role).toJson());
        Map<String,Object> authority = WorldBuilderContentRefreshAuthority.verify(parent, target, report);
        WorldBuilderEffectiveContent.Index before = WorldBuilderEffectiveContent.index(
            WorldBuilderProjectContentBundle.read(project.resolve(WorldBuilderProjectContentBundle.SOURCE_DIRECTORY)));
        Path temporary = Files.createTempDirectory(project.getParent().getParent(), ".content-refresh-preview-");
        try {
            Path reportPath = temporary.resolve("discovery.json");
            Files.write(reportPath, WorldBuilderJsonDocuments.pretty(report).getBytes(StandardCharsets.UTF_8));
            WorldBuilderAdaptiveProjectLifecycle.ProjectResult captured = new WorldBuilderAdaptiveProjectLifecycle().create(
                temporary, runtime, target, reportPath, "Content refresh validation", port, "CREATE");
            WorldBuilderEffectiveContent.Index after = WorldBuilderEffectiveContent.index(
                WorldBuilderProjectContentBundle.read(captured.projectRoot.resolve(WorldBuilderProjectContentBundle.SOURCE_DIRECTORY)));
            Preview result = new Preview(parent, report, authority, before, after);
            if (!authority.equals(WorldBuilderContentRefreshAuthority.verify(parent, target, report)))
                throw refusal("target", "Target authority changed while content was inspected.", "Stop target updates and repeat Detect New Content.");
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
            if (!expectedContentSha256.equals(content.contentSha256)) throw refusal("content",
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
                throw refusal("project", "The predecessor or checked target changed before content publication.",
                    "Keep the preserved project and repeat Detect New Content.");
        }
    }

    static final class Preview {
        final WorldBuilderAdaptiveProjectLifecycle.VerifiedProject parent;
        final Map<String,Object> report, authority, document;
        final List<String> blockers;
        final String fingerprint, newContentSha256;
        Preview(WorldBuilderAdaptiveProjectLifecycle.VerifiedProject parent, Map<String,Object> report,
            Map<String,Object> authority, WorldBuilderEffectiveContent.Index before, WorldBuilderEffectiveContent.Index after)
            throws WorldBuilderContractException {
            this.parent = parent; this.report = report; this.authority = authority; this.newContentSha256 = after.contentSha256;
            List<String> conflicts = new ArrayList<String>();
            List<Object> families = new ArrayList<Object>();
            Map<String,List<Integer>> referenced = parent.working.requiredDefinitionIds();
            for (String family : before.families.keySet()) {
                Map<Integer,WorldBuilderEffectiveContent.Entry> old = before.families.get(family), next = after.families.get(family);
                List<Object> added = new ArrayList<Object>(), changed = new ArrayList<Object>(), removed = new ArrayList<Object>(), visuals = new ArrayList<Object>();
                for (Map.Entry<Integer,WorldBuilderEffectiveContent.Entry> entry : old.entrySet()) {
                    Integer id = entry.getKey(); WorldBuilderEffectiveContent.Entry value = next.get(id);
                    String uses = referenced.get(family).contains(id) ? " (used by the saved map)" : " (available, currently unplaced)";
                    if (value == null) {
                        removed.add(Long.valueOf(id)); conflicts.add(family + " " + id + " removed" + uses);
                    } else if (!entry.getValue().semanticSha256.equals(value.semanticSha256)) {
                        changed.add(Long.valueOf(id)); conflicts.add(family + " " + id + " changed identity/semantics" + uses);
                    } else if (!entry.getValue().visualSha256.equals(value.visualSha256)) visuals.add(Long.valueOf(id));
                }
                for (Integer id : next.keySet()) if (!old.containsKey(id)) added.add(Long.valueOf(id));
                Map<String,Object> row = new LinkedHashMap<String,Object>(); row.put("family", family);
                row.put("added", added); row.put("changed", changed); row.put("removed", removed); row.put("visualsChanged", visuals); families.add(row);
            }
            this.blockers = Collections.unmodifiableList(conflicts);
            document = new LinkedHashMap<String,Object>();
            document.put("schemaVersion", Long.valueOf(1)); document.put("manifestType", "world-builder-content-refresh-preview");
            document.put("projectId", parent.projectId); document.put("projectFingerprintSha256", parent.manifest.get("projectFingerprintSha256"));
            document.put("workingFingerprintSha256", parent.working.fingerprintSha256);
            document.put("discoveryFingerprintSha256", report.get("discoveryFingerprintSha256"));
            document.put("previousContentSha256", before.contentSha256); document.put("contentSha256", after.contentSha256);
            document.put("targetAuthority", authority); document.put("families", families); document.put("blockers", new ArrayList<String>(conflicts));
            document.put("status", conflicts.isEmpty() ? "ready" : "blocked");
            fingerprint = WorldBuilderHashes.sha256(WorldBuilderJsonDocuments.pretty(document).getBytes(StandardCharsets.UTF_8));
            document.put("previewFingerprintSha256", fingerprint);
        }
        String toJson() { return WorldBuilderJsonDocuments.pretty(document); }
        String summary() {
            StringBuilder text = new StringBuilder("Detect New Content\n\nYour saved terrain and placements will be preserved. Previous content revisions, exports and receipts remain available.\nNo target files will be changed.\n\n");
            for (Object raw : (List<?>)document.get("families")) {
                @SuppressWarnings("unchecked") Map<String,Object> family = (Map<String,Object>)raw;
                text.append(family.get("family")).append(": added ").append(family.get("added"))
                    .append("; changed ").append(family.get("changed")).append("; removed ").append(family.get("removed"))
                    .append("; visuals ").append(family.get("visualsChanged")).append('\n');
            }
            if (!blockers.isEmpty()) text.append("\nRefresh is blocked: ").append(blockers);
            else text.append("\nAccept and continue with this content revision?");
            return text.toString();
        }
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
    private static WorldBuilderContractException refusal(String source, String message, String next) {
        return new WorldBuilderContractException(WorldBuilderErrorCodes.DEFINITION_MISMATCH, OPERATION, source, false, message, next);
    }
}
