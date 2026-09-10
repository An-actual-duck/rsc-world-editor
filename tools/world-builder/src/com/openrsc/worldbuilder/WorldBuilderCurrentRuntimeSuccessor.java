package com.openrsc.worldbuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;

/** New generation and new database branch only; predecessor state and side-state are never rewritten. */
final class WorldBuilderCurrentRuntimeSuccessor {
    private static final String INSTANCE = WorldBuilderCurrentRuntimeInstalledGeneration.INSTANCE;
    private static final String LAUNCH = "migration/output/launch/";
    private WorldBuilderCurrentRuntimeSuccessor() { }

    static Map<String,Object> inspect(Path target, Path stage, Path release, String id,
        Map<String,Object> identity, Map<String,Object> layout, List<Object> generated)
        throws IOException, WorldBuilderContractException {
        if (!id.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")) throw refused("Invalid successor identity.");
        WorldBuilderCurrentRuntimeLayout.verify(stage, layout);
        WorldBuilderCurrentRuntimeGeneratedState.verify(stage, generated);
        Map<String,Object> old = WorldBuilderCurrentRuntimeInstalledGeneration.readSpecification(target);
        Map<String,Object> spec = new LinkedHashMap<String,Object>(old);
        Path instance = target.resolve(INSTANCE), generation = instance.resolve("generations/" + id);
        Path state = instance.resolve("state/" + id);
        absent(generation); absent(state);
        spec.put("generationId", id);
        spec.put("compositionIdentityPath", generation.resolve("composition-identity.json").toString());
        spec.put("compositionIdentitySha256", WorldBuilderHashes.sha256(bytes(identity)));
        spec.put("runtimeProfilePath", release.resolve("runtime/profile.json").toString());
        spec.put("runtimeProfileSha256", WorldBuilderHashes.sha256(stage.resolve("runtime/profile.json")));
        spec.put("serverConfigurationPath", release.resolve(LAUNCH + "current-base.conf").toString());
        spec.put("serverConfigurationSha256", WorldBuilderHashes.sha256(stage.resolve(LAUNCH + "current-base.conf")));
        spec.put("mapRoot", release.resolve("migration/output/map/conversion/package").toString());
        spec.put("mapPackageFingerprintSha256", WorldBuilderCurrentRuntimeInstance.treeFingerprint(stage.resolve("migration/output/map/conversion/package"), true));
        spec.put("serverStateRoot", state.resolve("server").toString());
        for (String role : Arrays.asList("server", "client")) {
            spec.put(role + "CodeRoot", release.resolve("installed/" + role).toString());
            spec.put(role + "CodeTreeSha256", WorldBuilderCurrentRuntimeInstance.treeFingerprint(stage.resolve("installed/" + role), false));
            spec.put(role + "MapProfilePath", release.resolve(LAUNCH + "installed-" + role + ".json").toString());
            spec.put(role + "MapProfileSha256", WorldBuilderHashes.sha256(stage.resolve(LAUNCH + "installed-" + role + ".json")));
        }
        Map<String,Object> plan = new LinkedHashMap<String,Object>();
        plan.put("schemaVersion", Long.valueOf(1)); plan.put("manifestType", "world-builder-current-successor-construction");
        plan.put("targetRoot", target.toString()); plan.put("transactionId", id); plan.put("releaseRoot", release.toString());
        plan.put("previousSpecification", old); plan.put("specification", spec); plan.put("compositionIdentity", identity);
        Path database = stage.resolve("migration/output/state/current-base.db");
        plan.put("databaseSize", Long.valueOf(Files.size(database))); plan.put("databaseSha256", WorldBuilderHashes.sha256(database));
        return plan;
    }

    static void validate(Map<String,Object> plan, Path target) throws WorldBuilderContractException {
        WorldBuilderBoundedInventory.exactKeys(plan, "current-successor", "schemaVersion", "manifestType", "targetRoot", "transactionId",
            "releaseRoot", "previousSpecification", "specification", "compositionIdentity", "databaseSize", "databaseSha256");
        String id = text(plan, "transactionId");
        if (!Long.valueOf(1).equals(plan.get("schemaVersion")) || !"world-builder-current-successor-construction".equals(plan.get("manifestType"))
            || !target.toString().equals(plan.get("targetRoot")) || !id.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")) throw refused("Successor construction identity differs.");
        Path release = Paths.get(text(plan, "releaseRoot"));
        if (!release.isAbsolute() || !release.normalize().equals(release) || !release.startsWith(target.resolve(".world-builder/current-runtime/releases"))
            || !release.getFileName().toString().equals(id)) throw refused("Successor release path differs.");
        Map<String,Object> old = object(plan.get("previousSpecification")), spec = object(plan.get("specification"));
        WorldBuilderCurrentRuntimeInstance.renderGeneration(old); WorldBuilderCurrentRuntimeInstance.renderGeneration(spec);
        Path instance = target.resolve(INSTANCE), generation = instance.resolve("generations/" + id);
        if (!id.equals(spec.get("generationId")) || !instance.resolve("installation").toString().equals(spec.get("installationRoot"))
            || !generation.resolve("composition-identity.json").toString().equals(spec.get("compositionIdentityPath"))
            || !instance.resolve("state/" + id + "/server").toString().equals(spec.get("serverStateRoot"))) throw refused("Successor output paths differ from the fixed topology.");
        Set<String> changed = new HashSet<String>(Arrays.asList("generationId", "compositionIdentityPath", "compositionIdentitySha256",
            "runtimeProfilePath", "runtimeProfileSha256", "serverConfigurationPath", "serverConfigurationSha256", "mapRoot", "mapPackageFingerprintSha256", "serverStateRoot"));
        for (String role : Arrays.asList("server", "client")) for (String suffix : Arrays.asList("CodeRoot", "CodeTreeSha256", "MapProfilePath", "MapProfileSha256")) changed.add(role + suffix);
        for (String key : old.keySet()) if (!changed.contains(key) && !Objects.equals(old.get(key), spec.get(key))) throw refused("Successor changed retained installation/state identity.");
        Map<String,String> paths = new LinkedHashMap<String,String>(); paths.put("runtimeProfilePath", "runtime/profile.json");
        paths.put("serverConfigurationPath", LAUNCH + "current-base.conf"); paths.put("mapRoot", "migration/output/map/conversion/package");
        for (String role : Arrays.asList("server", "client")) { paths.put(role + "CodeRoot", "installed/" + role); paths.put(role + "MapProfilePath", LAUNCH + "installed-" + role + ".json"); }
        for (Map.Entry<String,String> entry : paths.entrySet()) if (!release.resolve(entry.getValue()).toString().equals(spec.get(entry.getKey()))) throw refused("Successor runtime input path differs.");
        if (!WorldBuilderHashes.sha256(bytes(plan.get("compositionIdentity"))).equals(spec.get("compositionIdentitySha256"))
            || !WorldBuilderBoundedInventory.isHash(text(plan, "databaseSha256")) || number(plan, "databaseSize") < 1 || number(plan, "databaseSize") > 4294967296L)
            throw refused("Successor immutable payload binding differs.");
    }

    static void materialize(Map<String,Object> plan, Path target, Path stage, WorldBuilderCurrentRuntimeInstanceLease lease)
        throws IOException, WorldBuilderContractException {
        validate(plan, target); lease.verifyHeld(target.resolve(INSTANCE + "/installation"));
        Path generation = generation(plan, target), state = state(plan, target);
        absent(generation); absent(state);
        directory(generation.getParent()); directory(state.getParent());
        Files.createDirectory(state, permissions("rwx------")); Files.createDirectory(state.resolve("server"), permissions("rwx------"));
        Path source = stage.resolve("migration/output/state/current-base.db"); requireDatabase(plan, source);
        Path destination = state.resolve("server/current_base.db"); Files.copy(source, destination);
        Files.setPosixFilePermissions(destination, PosixFilePermissions.fromString("rw-------")); requireDatabase(plan, destination);
        Files.createDirectory(generation, permissions("rwx------"));
        for (Map.Entry<String,byte[]> document : documents(plan).entrySet()) {
            Path path = generation.resolve(document.getKey()); Files.createFile(path, permissions("rw-------")); Files.write(path, document.getValue(), StandardOpenOption.WRITE);
        }
        WorldBuilderAdaptiveDurability.forceTree(state); WorldBuilderAdaptiveDurability.forceTree(generation);
        WorldBuilderAdaptiveDurability.forceDirectory(state.getParent()); WorldBuilderAdaptiveDurability.forceDirectory(generation.getParent());
        verify(plan, target, true);
    }

    static void verify(Map<String,Object> plan, Path target, boolean requirePresent) throws IOException, WorldBuilderContractException {
        validate(plan, target); Path generation = generation(plan, target), state = state(plan, target);
        if (Files.exists(generation, LinkOption.NOFOLLOW_LINKS)) {
            requireNames(generation, documents(plan).keySet());
            for (Map.Entry<String,byte[]> item : documents(plan).entrySet()) {
                Path path = generation.resolve(item.getKey()); regular(path);
                if (!Arrays.equals(Files.readAllBytes(path), item.getValue())) throw refused("Successor launch output has drifted.");
            }
        } else if (requirePresent) throw refused("Successor generation is missing.");
        if (Files.exists(state, LinkOption.NOFOLLOW_LINKS)) {
            requireNames(state, Collections.singleton("server")); requireNames(state.resolve("server"), Collections.singleton("current_base.db"));
            requireDatabase(plan, state.resolve("server/current_base.db"));
        } else if (requirePresent) throw refused("Successor state branch is missing.");
    }

    static void removeNeverStarted(Map<String,Object> plan, Path target, WorldBuilderCurrentRuntimeInstanceLease lease)
        throws IOException, WorldBuilderContractException {
        verify(plan, target, false); lease.verifyHeld(target.resolve(INSTANCE + "/installation"));
        // Called only after authenticated precommit rollback; unknown partial output is deliberately retained.
        for (Path root : Arrays.asList(generation(plan, target), state(plan, target))) if (Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            try (java.util.stream.Stream<Path> paths = Files.walk(root)) {
                List<Path> ordered = new ArrayList<Path>(); paths.forEach(ordered::add); ordered.sort(Comparator.reverseOrder());
                for (Path path : ordered) Files.delete(path);
            }
            WorldBuilderAdaptiveDurability.forceDirectory(root.getParent());
        }
    }

    private static Map<String,byte[]> documents(Map<String,Object> plan) throws WorldBuilderContractException {
        Map<String,Object> generation = WorldBuilderCurrentRuntimeInstance.renderGeneration(object(plan.get("specification")));
        Map<String,byte[]> result = new LinkedHashMap<String,byte[]>(); result.put("composition-identity.json", bytes(plan.get("compositionIdentity")));
        for (String role : Arrays.asList("server", "client")) result.put(role + "-launch.json", bytes(generation.get(role + "Descriptor"))); return result;
    }
    private static Path generation(Map<String,Object> plan, Path target) throws WorldBuilderContractException { return target.resolve(INSTANCE + "/generations/" + text(plan, "transactionId")); }
    private static Path state(Map<String,Object> plan, Path target) throws WorldBuilderContractException { return target.resolve(INSTANCE + "/state/" + text(plan, "transactionId")); }
    private static void requireDatabase(Map<String,Object> plan, Path path) throws IOException, WorldBuilderContractException {
        regular(path); if (Files.size(path) != number(plan, "databaseSize") || !WorldBuilderHashes.sha256(path).equals(plan.get("databaseSha256"))) throw refused("Successor database copy differs from sealed migration output.");
    }
    private static void requireNames(Path root, Set<String> names) throws IOException, WorldBuilderContractException {
        directory(root); Set<String> actual = new HashSet<String>(); try (DirectoryStream<Path> paths = Files.newDirectoryStream(root)) { for (Path path : paths) actual.add(path.getFileName().toString()); }
        if (!actual.equals(names)) throw refused("Successor output tree is incomplete or contains unowned entries.");
    }
    private static void directory(Path path) throws IOException, WorldBuilderContractException {
        if (!path.equals(path.toRealPath()) || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
            || !Files.getPosixFilePermissions(path).equals(PosixFilePermissions.fromString("rwx------"))) throw refused("Successor directory is not canonical and private.");
    }
    private static void regular(Path path) throws IOException, WorldBuilderContractException {
        if (!path.equals(path.toRealPath()) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
            || !Files.getPosixFilePermissions(path).equals(PosixFilePermissions.fromString("rw-------"))) throw refused("Successor file is not canonical and private.");
        WorldBuilderAdaptiveExporter.rejectHardLink(path, path.getFileName().toString());
    }
    private static void absent(Path path) throws WorldBuilderContractException { if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) throw refused("Successor output already exists."); }
    private static java.nio.file.attribute.FileAttribute<Set<java.nio.file.attribute.PosixFilePermission>> permissions(String mode) { return PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString(mode)); }
    private static byte[] bytes(Object value) { return WorldBuilderJsonDocuments.pretty(value).getBytes(StandardCharsets.UTF_8); }
    @SuppressWarnings("unchecked") private static Map<String,Object> object(Object value) { return (Map<String,Object>)value; }
    private static String text(Map<String,Object> value, String key) throws WorldBuilderContractException { return WorldBuilderBoundedInventory.string(value.get(key), "current-successor", key); }
    private static long number(Map<String,Object> value, String key) throws WorldBuilderContractException { return WorldBuilderBoundedInventory.integer(value.get(key), "current-successor", key); }
    private static WorldBuilderContractException refused(String message) { return new WorldBuilderContractException(WorldBuilderErrorCodes.RECOVERY_REQUIRED, "current-successor", message); }
}
