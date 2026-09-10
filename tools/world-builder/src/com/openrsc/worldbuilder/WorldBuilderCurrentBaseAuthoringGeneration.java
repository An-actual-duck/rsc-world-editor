package com.openrsc.worldbuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;

/** Append-only preparation for current authoring adoption. Never activates or rewrites a project. */
final class WorldBuilderCurrentBaseAuthoringGeneration {
    private static final String OP = "prepare-current-base-authoring";
    private static final String MANIFEST = "authoring-generation.json";
    private static final List<String> DEFINITIONS = Arrays.asList(
        "source/authoring-evidence/server-definitions.json", "source/authoring-evidence/client-definitions.json");
    private WorldBuilderCurrentBaseAuthoringGeneration() { }

    static Plan preview(Path projectPath, Path catalog, Path identity)
        throws IOException, WorldBuilderContractException {
        WorldBuilderAdaptiveProjectLifecycle.VerifiedProject project =
            WorldBuilderAdaptiveProjectLifecycle.verifyProjectDirectory(projectPath, true);
        if (!WorldBuilderCurrentRuntimeUserActions.isNativeBase(project)) throw refused("A verified native Base project is required.");
        WorldBuilderCurrentBaseProjectContent.Plan base = WorldBuilderCurrentBaseProjectContent.inspect(
            WorldBuilderProviderCatalog.resolve(catalog, identity));
        WorldBuilderCurrentBaseProjectContent.requireAuthoring(base);
        // The isolated seed/control inputs are already bound by the immutable
        // project. Do not introduce player data or a second application runtime.
        WorldBuilderAdaptiveRuntimePreparer.SourceRuntime runtime = WorldBuilderAdaptiveRuntimePreparer.inspectBase(
            project.projectRoot.resolve(WorldBuilderAdaptiveRuntimePreparer.NATIVE_SUPPORT_ROOT), base);
        Map<String,Object> value = new LinkedHashMap<String,Object>();
        value.put("schemaVersion", Long.valueOf(1));
        value.put("manifestType", "world-builder-current-base-authoring-generation");
        value.put("activationAuthorized", Boolean.FALSE);
        value.put("projectId", project.projectId);
        value.put("sourceFingerprintSha256", project.snapshot.get("sourceFingerprintSha256"));
        value.put("originalRuntimeSha256", object(project.manifest.get("fingerprints")).get("runtimeSha256"));
        value.put("runtimeSha256", runtime.fingerprintSha256);
        value.put("compositionIdentitySha256", hash(json(base.composition.identity)));
        value.put("port", Long.valueOf(WorldBuilderAdaptiveProjectLifecycle.readRuntimePort(project.projectRoot)));
        value.put("statePolicy", "preparation-only-no-user-state-copy-or-activation");
        value.put("definitionCatalogSha256", WorldBuilderHashes.sha256(project.projectRoot.resolve(WorldBuilderCurrentBaseProjectContent.CATALOG)));
        WorldBuilderAdaptiveExporter.bindFingerprint(value, "generationFingerprintSha256");
        return new Plan(project.projectRoot, catalog, identity, value, runtime);
    }

    /** Destination must be new and external. Incomplete stages stay non-authoritative. */
    static String prepare(Plan reviewed, Path destination) throws IOException, WorldBuilderContractException {
        Path parent = directory(destination.toAbsolutePath().normalize().getParent());
        Path provider = directory(reviewed.catalog).getParent();
        if (!destination.isAbsolute() || !destination.normalize().equals(destination)
            || destination.startsWith(reviewed.project) || reviewed.project.startsWith(destination)
            || destination.startsWith(provider) || provider.startsWith(destination)
            || Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) throw refused("Generation destination must be a new external directory.");
        if (!Files.getFileStore(parent).equals(Files.getFileStore(reviewed.project)))
            throw refused("Generation preparation must stay on the project filesystem.");
        try (WorldBuilderAdaptiveProjectLock lock = WorldBuilderAdaptiveProjectLock.acquire(reviewed.project, OP)) {
            Plan fresh = preview(reviewed.project, reviewed.catalog, reviewed.identity);
            if (!Arrays.equals(reviewed.bytes, fresh.bytes)) throw refused("Selected authoring inputs changed after preview.");
            WorldBuilderAdaptiveProjectLifecycle.VerifiedProject project =
                WorldBuilderAdaptiveProjectLifecycle.verifyProjectDirectory(reviewed.project, true);
            Path recordedTarget = Paths.get((String)object(project.manifest.get("target")).get("locatorDisplay")).toAbsolutePath().normalize();
            if (destination.startsWith(recordedTarget) || recordedTarget.startsWith(destination))
                throw refused("Authoring preparation must be separate from the recorded server target.");
            Files.createDirectory(destination, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            WorldBuilderCurrentBaseProjectContent.capture(destination, fresh.runtime.basePlan);
            if (!WorldBuilderHashes.sha256(destination.resolve(WorldBuilderCurrentBaseProjectContent.CATALOG))
                .equals(fresh.value.get("definitionCatalogSha256")))
                throw refused("Current definition IDs need a reviewed project-data migration before adoption.");
            WorldBuilderAdaptiveRuntimePreparer.captureBaseSupport(destination, fresh.runtime);
            for (String relative : DEFINITIONS) {
                Path source = WorldBuilderReadOnlyTarget.open(project.projectRoot).requiredFile(relative);
                Path output = destination.resolve(relative);
                Files.createDirectories(output.getParent());
                Files.copy(source, output, StandardCopyOption.COPY_ATTRIBUTES);
            }
            Files.createDirectory(destination.resolve("working"));
            WorldBuilderAdaptiveRuntimePreparer.prepare(destination, fresh.runtime, project.snapshot, project.origin,
                ((Number)fresh.value.get("port")).intValue());
            // Seal the complete prepared tree, including modes and generated
            // evidence. It is not runnable authority and contains no copied user DB.
            Map<String,Object> seal = new LinkedHashMap<String,Object>();
            seal.put("generation", fresh.value); seal.put("outputs", inventory(destination));
            WorldBuilderAdaptiveExporter.bindFingerprint(seal, "sealSha256");
            byte[] sealedBytes = json(seal);
            String sealedHash = hash(sealedBytes);
            Files.write(destination.resolve(MANIFEST), sealedBytes, StandardOpenOption.CREATE_NEW);
            Files.setPosixFilePermissions(destination.resolve(MANIFEST), PosixFilePermissions.fromString("rw-------"));
            verify(fresh, destination, sealedHash);
            // Recheck source/catalog while the project remains locked. A failure
            // leaves a stage, never an active pointer or altered project runtime.
            if (!Arrays.equals(fresh.bytes, preview(reviewed.project, reviewed.catalog, reviewed.identity).bytes))
                throw refused("Authoring inputs changed during generation preparation.");
            WorldBuilderAdaptiveDurability.forceTree(destination);
            WorldBuilderAdaptiveDurability.forceDirectory(parent);
            return sealedHash;
        }
    }

    /** The seal hash comes from preparation / a future trusted activation journal, never from this tree itself. */
    static WorldBuilderAdaptiveRuntimePreparer.RuntimeEvidence verify(Plan expected, Path generation, String expectedSealSha256)
        throws IOException, WorldBuilderContractException {
        directory(generation);
        Path manifest = WorldBuilderReadOnlyTarget.open(generation).requiredFile(MANIFEST);
        if (mode(manifest) != 0600 || !WorldBuilderBoundedInventory.isHash(expectedSealSha256)
            || !expectedSealSha256.equals(WorldBuilderHashes.sha256(manifest)))
            throw refused("Generation seal differs from its independently retained preparation hash.");
        Map<String,Object> seal = read(manifest);
        WorldBuilderBoundedInventory.exactKeys(seal, OP, "generation", "outputs", "sealSha256");
        Map<String,Object> rebound = new LinkedHashMap<String,Object>(seal);
        WorldBuilderAdaptiveExporter.bindFingerprint(rebound, "sealSha256");
        if (!rebound.equals(seal) || !expected.value.equals(seal.get("generation"))
            || !inventory(generation).equals(seal.get("outputs"))) throw refused("Prepared authoring generation changed from its complete seal.");
        WorldBuilderAdaptiveProjectLifecycle.VerifiedProject project =
            WorldBuilderAdaptiveProjectLifecycle.verifyProjectDirectory(expected.project, true);
        if (!Arrays.equals(expected.bytes, preview(expected.project, expected.catalog, expected.identity).bytes))
            throw refused("Prepared generation no longer matches the selected project/composition.");
        if (!expected.value.get("compositionIdentitySha256").equals(hash(json(
            WorldBuilderCurrentBaseProjectContent.verifiedIdentity(generation))))) throw refused("Prepared composition identity changed.");
        for (String relative : DEFINITIONS)
            if (!WorldBuilderHashes.sha256(generation.resolve(relative)).equals(WorldBuilderHashes.sha256(project.projectRoot.resolve(relative))))
                throw refused("Prepared definition evidence differs from the original project.");
        return WorldBuilderAdaptiveRuntimePreparer.verify(generation, (String)expected.value.get("runtimeSha256"),
            project.snapshot, project.origin, ((Number)expected.value.get("port")).intValue());
    }

    private static List<Object> inventory(Path root) throws IOException, WorldBuilderContractException {
        TreeMap<String,Object> rows = new TreeMap<String,Object>();
        long total = 0;
        try (java.util.stream.Stream<Path> stream = Files.walk(root)) {
            Iterator<Path> entries = stream.iterator();
            while (entries.hasNext()) {
                Path path = entries.next();
                if (path.equals(root)) continue;
                String relative = root.relativize(path).toString().replace('\\', '/');
                if (MANIFEST.equals(relative)) continue;
                if (rows.size() >= WorldBuilderContractLimits.MAX_INVENTORY_ENTRIES * 2) throw refused("Prepared generation is unbounded.");
                Map<String,Object> row = new LinkedHashMap<String,Object>(); row.put("relativePath", relative);
                row.put("mode", String.format("%04o", mode(path)));
                if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                    WorldBuilderReadOnlyTarget.open(root).requiredDirectory(relative); row.put("type", "directory");
                } else {
                    WorldBuilderReadOnlyTarget.open(root).requiredFile(relative); row.put("type", "file");
                    long size = Files.size(path); total = Math.addExact(total, size);
                    if (size > WorldBuilderContractLimits.MAX_INVENTORY_FILE_BYTES
                        || total > WorldBuilderContractLimits.MAX_INVENTORY_TOTAL_BYTES) throw refused("Prepared generation exceeds its byte bound.");
                    row.put("size", Long.valueOf(size)); row.put("sha256", WorldBuilderHashes.sha256(path));
                }
                rows.put(relative, row);
            }
        }
        return new ArrayList<Object>(rows.values());
    }
    private static Path directory(Path path) throws IOException, WorldBuilderContractException {
        if (path == null || !path.isAbsolute() || !path.normalize().equals(path)
            || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) || !path.equals(path.toRealPath())) throw refused("Generation directory must be canonical.");
        return path;
    }
    private static int mode(Path path) throws IOException { return ((Number)Files.getAttribute(path, "unix:mode", LinkOption.NOFOLLOW_LINKS)).intValue() & 07777; }
    private static byte[] json(Object value) { return WorldBuilderJsonDocuments.pretty(value).getBytes(StandardCharsets.UTF_8); }
    private static String hash(byte[] value) { return WorldBuilderHashes.sha256(value); }
    @SuppressWarnings("unchecked") private static Map<String,Object> object(Object value) { return (Map<String,Object>)value; }
    private static Map<String,Object> read(Path path) throws IOException, WorldBuilderContractException {
        try { return WorldBuilderJsonDocuments.readObject(path); }
        catch (WorldBuilderDiscoveryException malformed) { throw refused("Generation seal is malformed."); }
    }
    private static WorldBuilderContractException refused(String message) {
        return new WorldBuilderContractException(WorldBuilderErrorCodes.CAPABILITY_MISMATCH, OP, message);
    }
    static final class Plan {
        final Path project, catalog, identity;
        private final Map<String,Object> value;
        private final byte[] bytes;
        private final WorldBuilderAdaptiveRuntimePreparer.SourceRuntime runtime;
        private Plan(Path project, Path catalog, Path identity, Map<String,Object> value,
                     WorldBuilderAdaptiveRuntimePreparer.SourceRuntime runtime) {
            this.project = project; this.catalog = catalog; this.identity = identity;
            this.value = Collections.unmodifiableMap(new LinkedHashMap<String,Object>(value));
            this.bytes = json(value); this.runtime = runtime;
        }
        String toJson() { return new String(bytes, StandardCharsets.UTF_8); }
    }
}
