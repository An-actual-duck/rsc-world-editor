package com.openrsc.worldbuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;

/** Normal player launch always resolves the current ledger, never a saved generation path. */
final class WorldBuilderManagedLaunch {
    static List<String> command(Path target, String role) throws IOException, WorldBuilderContractException {
        requireDirectory(target);
        if (!Arrays.asList("server", "client").contains(role)) throw new IOException("Role must be server or client.");
        Map<String,Object> spec = WorldBuilderCurrentRuntimeInstalledGeneration.readSpecification(target);
        String key = role + "CodeRoot";
        String code = (String)spec.get(key);
        String cp = role.equals("server") ? code + "/core.jar" + java.io.File.pathSeparator + code + "/plugins.jar" : code + "/Open_RSC_Client.jar";
        Path descriptor = target.resolve(WorldBuilderCurrentRuntimeInstalledGeneration.INSTANCE + "/generations/"
            + spec.get("generationId") + "/" + role + "-launch.json");
        return Arrays.asList(Paths.get(System.getProperty("java.home"), "bin", "java").toString(),
            "-Xmx768m", "-cp", cp, role.equals("server") ? "com.openrsc.server.CurrentBaseInstalledServer" : "orsc.CurrentBaseInstalledClient",
            "--launch", descriptor.toString());
    }
    static Process start(Path target, String role) throws IOException, WorldBuilderContractException {
        List<String> command = command(target, role);
        Map<String,Object> descriptor;
        try { descriptor = WorldBuilderJsonDocuments.readObject(Paths.get(command.get(command.size()-1))); }
        catch (WorldBuilderDiscoveryException invalid) { throw new IOException(invalid); }
        return new ProcessBuilder(command).directory(Paths.get((String)descriptor.get("workingRoot")).toFile()).inheritIO().start();
    }
    static int run(Path target, String role) throws Exception {
        if (!"both".equals(role)) {
            Process child = start(target, role);
            Thread hook = new Thread(() -> { if (child.isAlive()) child.destroy(); }, "managed-role-shutdown");
            Runtime.getRuntime().addShutdownHook(hook);
            try { return child.waitFor(); }
            finally { if (child.isAlive()) child.destroy(); Runtime.getRuntime().removeShutdownHook(hook); }
        }
        List<String> serverCommand = command(target, "server");
        Path descriptor = Paths.get(serverCommand.get(serverCommand.size()-1));
        Path sessions = target.resolve(WorldBuilderCurrentRuntimeInstalledGeneration.INSTANCE + "/installation/sessions/server");
        Set<Path> before = new HashSet<>();
        try (DirectoryStream<Path> paths = Files.newDirectoryStream(sessions)) { for (Path path : paths) before.add(path); }
        Process server = start(target, "server");
        java.util.concurrent.atomic.AtomicReference<Process> client = new java.util.concurrent.atomic.AtomicReference<>();
        Thread shutdown = new Thread(() -> {
            Process ownClient = client.get();
            if (ownClient != null && ownClient.isAlive()) ownClient.destroy();
            if (server.isAlive()) server.destroy();
        }, "managed-local-pair-shutdown");
        Runtime.getRuntime().addShutdownHook(shutdown);
        try {
            long deadline = System.nanoTime() + 120000000000L;
            boolean ready = false;
            while (server.isAlive() && System.nanoTime() < deadline && !ready) {
                try (DirectoryStream<Path> paths = Files.newDirectoryStream(sessions)) {
                    for (Path path : paths) if (!before.contains(path) && ready(path, descriptor)) ready = true;
                }
                if (!ready) Thread.sleep(100);
            }
            if (!ready || !server.isAlive()) throw new IOException("Managed server did not become ready; client was not launched.");
            client.set(start(target, "client"));
            return client.get().waitFor();
        } finally {
            if (server.isAlive()) server.destroy();
            server.waitFor(); // Runtime shutdown hooks save player state; never force-kill.
            Runtime.getRuntime().removeShutdownHook(shutdown);
        }
    }

    @SuppressWarnings("unchecked")
    static boolean ready(Path session, Path descriptor) throws Exception {
        Path receipt = session.resolve("ready.json");
        if (!Files.isRegularFile(receipt, LinkOption.NOFOLLOW_LINKS) || !session.equals(session.toRealPath())) return false;
        Map<String,Object> launch = WorldBuilderJsonDocuments.readObject(descriptor);
        Map<String,Object> actual = WorldBuilderJsonDocuments.readObject(receipt);
        Map<String,Object> expected = new LinkedHashMap<>();
        expected.put("schemaVersion", Long.valueOf(1)); expected.put("manifestType", "current-base-installed-session");
        expected.put("action", "ready"); expected.put("role", "server");
        expected.put("nonce", session.getFileName().toString()); expected.put("installationId", launch.get("installationId"));
        expected.put("descriptorSha256", WorldBuilderHashes.sha256(descriptor));
        expected.put("compositionIdentitySha256", ((Map<String,Object>)launch.get("compositionIdentity")).get("sha256"));
        Map<String,Object> profile = (Map<String,Object>)launch.get("installedMapProfile");
        expected.put("mapManifestSha256", WorldBuilderJsonDocuments.readObject(Paths.get((String)profile.get("path"))).get("manifestSha256"));
        return expected.equals(actual);
    }

    private static void requireDirectory(Path path) throws IOException {
        if (!path.isAbsolute() || !path.normalize().equals(path) || !path.equals(path.toRealPath()))
            throw new IOException("Managed target or installation must be a canonical absolute directory.");
    }

    /** One atomic startup-file replacement, with an exact durable original retained first. */
    static Path install(Path target, Path installation) throws Exception {
        requireDirectory(target); requireDirectory(installation);
        if (!installation.getParent().equals(target)) throw new IOException("World Builder must be directly inside this server root.");
        Path jar = installation.resolve("builder-runtime/launcher/world-builder-tools.jar");
        if (!Files.isRegularFile(jar, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Installed launcher JAR is missing.");
        Path roles = target.resolve(WorldBuilderCurrentRuntimeInstalledGeneration.INSTANCE + "/installation");
        try (WorldBuilderCurrentRuntimeInstanceLease lease = WorldBuilderCurrentRuntimeInstanceLease.acquire(roles)) {
            WorldBuilderCurrentRuntimeInstalledGeneration.readSpecification(target);
            Path launcher = target.resolve("Start-Linux.sh");
            byte[] before = null;
            if (Files.exists(launcher, LinkOption.NOFOLLOW_LINKS)) {
                if (!Files.isRegularFile(launcher, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(launcher)
                    || Files.size(launcher) > 1048576 || ((Number)Files.getAttribute(launcher,"unix:nlink")).intValue()!=1)
                    throw new IOException("Existing startup file is not a safe regular file.");
                before = Files.readAllBytes(launcher);
            }
            byte[] after = script(installation.getFileName().toString()).getBytes(StandardCharsets.UTF_8);
            if (Arrays.equals(before,after)) return launcher;
            Path backups = target.resolve(".world-builder/startup-backups");
            if (!Files.exists(backups, LinkOption.NOFOLLOW_LINKS)) Files.createDirectory(backups, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            if (!backups.equals(backups.toRealPath())) throw new IOException("Startup backup directory is aliased.");
            if (before != null) {
                Path backup = backups.resolve(WorldBuilderHashes.sha256(before) + ".sh");
                if (!Files.exists(backup, LinkOption.NOFOLLOW_LINKS)) Files.copy(launcher, backup, StandardCopyOption.COPY_ATTRIBUTES);
                if (!Files.isRegularFile(backup, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(backup)
                    || ((Number)Files.getAttribute(backup,"unix:nlink")).intValue()!=1
                    || Files.size(backup) != before.length
                    || !Arrays.equals(before, Files.readAllBytes(backup))) throw new IOException("Startup backup differs.");
                WorldBuilderAdaptiveDurability.forceFile(backup);
                WorldBuilderAdaptiveDurability.forceDirectory(backups);
                WorldBuilderAdaptiveDurability.forceDirectory(backups.getParent());
            }
            Path stage = Files.createTempFile(target.resolve(".world-builder"), ".startup-", ".sh");
            try {
                Files.write(stage, after); Files.setPosixFilePermissions(stage, PosixFilePermissions.fromString("rwxr-xr-x"));
                WorldBuilderAdaptiveDurability.forceFile(stage);
                lease.verifyHeld();
                if (before == null ? Files.exists(launcher, LinkOption.NOFOLLOW_LINKS)
                    : !Files.isRegularFile(launcher, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(launcher)
                        || ((Number)Files.getAttribute(launcher,"unix:nlink")).intValue()!=1
                        || !Arrays.equals(before, Files.readAllBytes(launcher))) throw new IOException("Startup file changed during installation.");
                Files.move(stage, launcher, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                WorldBuilderAdaptiveDurability.forceDirectory(target);
            } finally { Files.deleteIfExists(stage); }
            return launcher;
        }
    }
    static String script(String installationName) {
        String quoted = "'" + installationName.replace("'", "'\\''") + "'";
        return "#!/usr/bin/env bash\nset -euo pipefail\n# World Builder managed startup. Original retained in .world-builder/startup-backups.\n"
            + "ROOT=\"$(cd -- \"$(dirname -- \"${BASH_SOURCE[0]}\")\" && pwd -P)\"\nBUILDER=" + quoted + "\n"
            + "ROLE=\"${1:-both}\"\ncase \"$ROLE\" in server|client|both) ;; *) echo 'Usage: Start-Linux.sh [both|server|client]' >&2; exit 2;; esac\n"
            + "exec \"$ROOT/$BUILDER/runtime/bin/java\" -jar \"$ROOT/$BUILDER/builder-runtime/launcher/world-builder-tools.jar\" launch-current-target --target-root \"$ROOT\" --role \"$ROLE\"\n";
    }
}
