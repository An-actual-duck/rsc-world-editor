package com.openrsc.worldbuilder;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/** Executes only the reviewed provider verifier; its game processes use disposable copies. */
final class WorldBuilderInstalledRuntimeVerifier {
    static final String CONTRACT_HASH = "227abec8c5180b80a504591083c2d4d000152d2047dba5fa27202cdce56659da";
    static final String CONTRACT = "contracts/runtime/current-base-v1/installed-execution-verifier.json";
    static final String MAIN = "com.openrsc.server.database.CurrentBaseInstalledExecutionVerifier";
    private static final String OP = "installed-runtime-verification";
    private static final String MAP = "migration/output/map/conversion/package";
    private static final String LAUNCH = "migration/output/launch/";
    private static final String[] IDENTITY = {"platformReleaseId", "platformManifestHash", "variantId",
        "variantManifestHash", "moduleSetHash", "bundleInventoryHash"};
    private static final long RUN_SECONDS = 420L;
    private static final long CLEANUP_SECONDS = 105L;
    private static final long MAX_EVIDENCE = 65536L;
    private static final BooleanSupplier NEVER_CANCEL = new BooleanSupplier() {
        @Override public boolean getAsBoolean() { return false; }
    };

    private WorldBuilderInstalledRuntimeVerifier() { }

    static final class Prepared {
        final Path release, attempt, workspace;
        private final Path[] inputs;
        final Map<String,Object> identity;
        final Map<String,Object> migration, authority;
        final List<Object> generated;
        final List<Object> trustedArtifacts;
        final String serverHash, clientHash, inputHash;
        final int serverPort, websocketPort;
        Prepared(Path release, Path attempt, Path[] inputs, WorldBuilderProviderCatalog.Composition composition,
            Map<String,Object> migration, List<Object> generated, Map<String,Object> authority, List<Object> trustedArtifacts,
            String serverHash, String clientHash, String inputHash, int serverPort, int websocketPort) {
            this.release = release; this.attempt = attempt; this.workspace = attempt.resolve("execution");
            this.inputs = inputs.clone(); this.identity = WorldBuilderCurrentRuntimeVerifierAuthority.snapshot(composition.identity);
            this.migration = WorldBuilderCurrentRuntimeVerifierAuthority.snapshot(migration);
            this.generated = WorldBuilderCurrentRuntimeVerifierAuthority.snapshotList(generated);
            this.authority = WorldBuilderCurrentRuntimeVerifierAuthority.snapshot(authority);
            this.trustedArtifacts = WorldBuilderCurrentRuntimeVerifierAuthority.snapshotList(trustedArtifacts); this.serverHash = serverHash;
            this.clientHash = clientHash; this.inputHash = inputHash; this.serverPort = serverPort; this.websocketPort = websocketPort;
        }
    }

    static Map<String,Object> verify(Path requestedRelease, WorldBuilderProviderCatalog.Composition composition,
        Map<String,Object> migration, List<Object> generatedState, Path requestedAttempt,
        BooleanSupplier cancellation) throws IOException, WorldBuilderContractException {
        return execute(prepare(requestedRelease, composition, migration, generatedState, requestedAttempt, cancellation), cancellation);
    }

    static Prepared prepare(Path requestedRelease, WorldBuilderProviderCatalog.Composition composition,
        Map<String,Object> migration, List<Object> generatedState, Path requestedAttempt,
        BooleanSupplier cancellation) throws IOException, WorldBuilderContractException {
        Path release = canonicalDirectory(requestedRelease);
        Path attempt = requestedAttempt.toAbsolutePath();
        if (!attempt.equals(attempt.normalize()) || attempt.getParent() == null
            || !canonicalDirectory(attempt.getParent()).equals(attempt.getParent()))
            throw failure("Verification attempt must have a canonical existing parent.");
        disjoint(release, attempt);
        if (Files.exists(attempt, LinkOption.NOFOLLOW_LINKS)) throw failure("Verification attempt already exists.");
        if (!composition.installable || !"current-base-v1".equals(composition.string("variantId")))
            throw failure("Executable verification requires an installable Current Base composition.");
        Map<String,String> roles = new LinkedHashMap<String,String>();
        roles.put("server-runtime", "runtime/server/core.jar");
        roles.put("runtime-profile", "runtime/profile.json");
        roles.put("installed-execution-verifier", CONTRACT);
        Set<String> found = new HashSet<String>();
        List<Object> trustedArtifacts = new ArrayList<Object>();
        for (WorldBuilderProviderCatalog.Artifact artifact : composition.artifacts) {
            Path providerRoot = artifact.source;
            for (int i = 0; i < Paths.get(artifact.sourcePath).getNameCount(); i++) providerRoot = providerRoot.getParent();
            disjoint(providerRoot, attempt);
            String role = string(artifact.inventory, "role");
            if (!roles.containsKey(role)) continue;
            if (!found.add(role) || !roles.get(role).equals(artifact.bundlePath))
                throw failure("Verifier artifact role or bundle location is ambiguous.");
            Path file = regular(release, artifact.bundlePath);
            if (!WorldBuilderHashes.sha256(file).equals(string(artifact.inventory, "sha256")))
                throw failure("Verifier artifact differs from the selected composition.");
            Map<String,Object> trusted = new LinkedHashMap<String,Object>(artifact.inventory);
            trusted.put("bundlePath", artifact.bundlePath); trustedArtifacts.add(trusted);
        }
        if (!found.equals(roles.keySet()) || !CONTRACT_HASH.equals(WorldBuilderHashes.sha256(regular(release, CONTRACT))))
            throw failure("Provider verifier is not the compiled supervised execution contract.");
        validateSources(release, migration, generatedState);
        if (cancelled(cancellation)) throw failure("Verification cancelled before execution.");

        Files.createDirectory(attempt, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        WorldBuilderAdaptiveDurability.forceDirectory(attempt.getParent());
        WorldBuilderCurrentRuntimeVerifierAuthority.retainTools(attempt,
            regular(release, "runtime/server/core.jar"), regular(release, CONTRACT));
        Path identityPath = attempt.resolve("composition-identity.json");
        Files.createFile(identityPath, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        Files.write(identityPath, WorldBuilderJsonDocuments.pretty(composition.identity).getBytes(StandardCharsets.UTF_8),
            StandardOpenOption.WRITE);
        WorldBuilderAdaptiveDurability.forceFile(identityPath);
        Path workspace = attempt.resolve("execution");
        Path evidencePath = attempt.resolve("evidence.json");
        Path server = release.resolve("installed/server"), client = release.resolve("installed/client");
        Path[] inputs = {identityPath, regular(release, "runtime/profile.json"),
            regular(release, LAUNCH + "current-base.conf"), regular(release, LAUNCH + "installed-server.json"),
            regular(release, LAUNCH + "installed-client.json"), release.resolve(MAP),
            regular(release, WorldBuilderPreservationStagedMigrator.SQLITE_OUTPUT)};
        String serverHash = treeHash(server), clientHash = treeHash(client), inputHash = inputSetHash(inputs);
        int serverPort, websocketPort;
        try (ServerSocket game = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
             ServerSocket websocket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            serverPort = game.getLocalPort(); websocketPort = websocket.getLocalPort();
        }
        String[] names = {"contract", "composition-identity", "runtime-profile", "installed-server-root",
            "installed-client-root", "server-config", "server-profile", "client-profile", "map-package",
            "state-db", "workspace", "server-port", "websocket-port", "evidence"};
        Object[] values = {attempt.resolve("provider-tools/contract.json"), identityPath, inputs[1], server, client, inputs[2], inputs[3],
            inputs[4], inputs[5], inputs[6], workspace, serverPort, websocketPort, evidencePath};
        Map<String,String> options = new LinkedHashMap<String,String>();
        for (int i = 0; i < names.length; i++) options.put(names[i], values[i].toString());
        Map<String,Object> authority = WorldBuilderCurrentRuntimeVerifierAuthority.prepare(attempt, options);
        WorldBuilderCurrentRuntimeVerifierAuthority.authenticate(attempt, authority, trustedArtifacts);
        return new Prepared(release, attempt, inputs, composition, migration, generatedState, authority, trustedArtifacts,
            serverHash, clientHash, inputHash, serverPort, websocketPort);
    }

    /** Caller has durably journaled Prepared.authority before entering this method. */
    static Map<String,Object> execute(Prepared prepared, BooleanSupplier cancellation)
        throws IOException, WorldBuilderContractException {
        Path release = prepared.release, attempt = prepared.attempt, workspace = prepared.workspace;
        Path server = release.resolve("installed/server"), client = release.resolve("installed/client");
        Path[] inputs = prepared.inputs;
        String serverHash = prepared.serverHash, clientHash = prepared.clientHash, inputHash = prepared.inputHash;
        Map<String,Object> migration = prepared.migration;
        List<Object> generatedState = prepared.generated;
        int serverPort = prepared.serverPort, websocketPort = prepared.websocketPort;
        WorldBuilderCurrentRuntimeVerifierAuthority.authenticate(attempt, prepared.authority, prepared.trustedArtifacts);
        Map<String,Object> closure;
        try {
            runCommand(WorldBuilderCurrentRuntimeVerifierAuthority.launchCommand(prepared.authority), attempt,
                cancellation, RUN_SECONDS, CLEANUP_SECONDS);
        } catch (IOException | WorldBuilderContractException | RuntimeException failure) {
            try { WorldBuilderCurrentRuntimeVerifierAuthority.close(prepared.authority, prepared.trustedArtifacts); }
            catch (IOException | WorldBuilderContractException unproven) { unproven.addSuppressed(failure); throw unproven; }
            if (failure instanceof WorldBuilderContractException
                && WorldBuilderErrorCodes.RECOVERY_REQUIRED.equals(((WorldBuilderContractException)failure).code())) {
                WorldBuilderContractException closedFailure = failure("Verification failed; subsequent provider recovery proved closure.");
                closedFailure.addSuppressed(failure); throw closedFailure;
            }
            throw failure;
        }
        closure = WorldBuilderCurrentRuntimeVerifierAuthority.close(prepared.authority, prepared.trustedArtifacts);
        if (cancelled(cancellation)) throw failure("Verification cancelled; evidence is not accepted.");
        validateSources(release, migration, generatedState);
        if (!serverHash.equals(treeHash(server)) || !clientHash.equals(treeHash(client))
            || !inputHash.equals(inputSetHash(inputs))) throw failure("Verification changed an input.");
        if (!treeHash(inputs[5]).equals(treeHash(workspace.resolve("maps/package"))))
            throw failure("Disposable runtime map differs from the selected input package.");
        Path evidence = regular(attempt, "evidence.json");
        if (Files.size(evidence) > MAX_EVIDENCE) throw failure("Verifier evidence exceeds its bound.");
        Map<String,Object> result = read(evidence);
        Map<String,Object> supervision = object(result.get("supervision"));
        WorldBuilderCurrentRuntimeVerifierAuthority.bind(prepared.authority, supervision);
        for (String key : Arrays.asList("intentSha256", "revocationSha256"))
            if (!closure.get(key).equals(supervision.get(key))) throw failure("Execution and recovery evidence disagree.");
        validateEvidence(result, prepared.identity, WorldBuilderHashes.sha256(inputs[0]),
            serverHash, clientHash, inputHash, WorldBuilderCurrentRuntimeLaunchInputs.runtimePackageFingerprint(
                array(object(migration.get("mapMigration")).get("outputInventory"))),
            serverPort, websocketPort, workspace);
        WorldBuilderAdaptiveDurability.forceFile(evidence);
        WorldBuilderAdaptiveDurability.forceDirectory(attempt);
        return result;
    }

    private static void validateSources(Path release, Map<String,Object> migration, List<Object> generated)
        throws IOException, WorldBuilderContractException {
        WorldBuilderCurrentRuntimeExecutionProfile.preservation().validateMigrationPlan(migration);
        Map<String,Object> execution = object(migration.get("stagedExecution"));
        WorldBuilderCurrentRuntimeLayout.verify(release, object(execution.get("runtimeLayout")));
        WorldBuilderCurrentRuntimeGeneratedState.validate(generated, false);
        WorldBuilderCurrentRuntimeGeneratedState.verify(release, generated);
        new WorldBuilderCurrentRuntimeUpgradeTransaction().verifyReviewedPreservationMap(release,
            object(migration.get("mapMigration")));
        Set<String> launchKinds = new HashSet<String>();
        for (Object raw : array(execution.get("stagedOutputs"))) {
            Map<String,Object> row = object(raw);
            Path path = regular(release, string(row, "relativePath"));
            if (Files.size(path) != integer(row, "size") || !WorldBuilderHashes.sha256(path).equals(string(row, "sha256"))
                || !Files.getPosixFilePermissions(path).equals(PosixFilePermissions.fromString("rw-------")))
                throw failure("Launch inputs differ from their reviewed inventory.");
            if (WorldBuilderCurrentRuntimeLaunchInputs.paths().containsKey(string(row, "kind"))) launchKinds.add(string(row, "kind"));
        }
        if (!launchKinds.equals(WorldBuilderCurrentRuntimeLaunchInputs.paths().keySet()))
            throw failure("A complete reviewed launch-input set is required.");
        Path database = regular(release, WorldBuilderPreservationStagedMigrator.SQLITE_OUTPUT);
        for (String suffix : Arrays.asList("-wal", "-shm", "-journal"))
            if (Files.exists(database.resolveSibling(database.getFileName() + suffix), LinkOption.NOFOLLOW_LINKS))
                throw failure("Verification requires a closed SQLite snapshot without sidecars.");
    }

    /** Package-private process seam. No supplied command is exposed through the product UI or CLI. */
    static void runCommand(List<String> command, Path directory, BooleanSupplier cancellation,
        long timeoutSeconds, long cleanupSeconds) throws IOException, WorldBuilderContractException {
        ProcessBuilder builder = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true);
        for (String key : new ArrayList<String>(builder.environment().keySet()))
            if (key.startsWith("OPENRSC_") || key.startsWith("SPOILED_MILK_")
                || Arrays.asList("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS", "CLASSPATH").contains(key))
                builder.environment().remove(key);
        Path diagnostic = directory.resolve("verifier-output.log");
        Files.createFile(diagnostic, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        OutputStream diagnosticOutput = Files.newOutputStream(diagnostic, StandardOpenOption.WRITE);
        final Process process;
        try { process = builder.start(); }
        catch (IOException failure) { diagnosticOutput.close(); throw failure; }
        final OutputDrain drain = new OutputDrain(process, diagnosticOutput);
        Thread output = new Thread(drain, "current-runtime-verifier-output"); output.setDaemon(true); output.start();
        Thread hook = new Thread(new Runnable() { @Override public void run() { closeLifetime(process); } },
            "current-runtime-verifier-parent-exit");
        Runtime.getRuntime().addShutdownHook(hook);
        boolean completed = false, interrupted = false;
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
            while (process.isAlive() && !cancelled(cancellation) && !drain.failed
                && System.nanoTime() < deadline) process.waitFor(100L, TimeUnit.MILLISECONDS);
            completed = !process.isAlive();
        } catch (InterruptedException stopped) { interrupted = true; }
        finally {
            closeLifetime(process);
            try {
                if (process.isAlive() && !process.waitFor(cleanupSeconds, TimeUnit.SECONDS))
                    throw new WorldBuilderContractException(WorldBuilderErrorCodes.RECOVERY_REQUIRED, OP,
                        "verification-attempt", false, "Verifier cleanup did not finish; its disposable workspace must be retained.",
                        "Keep activation disabled and inspect the owned verification processes; do not force-delete the workspace.");
                output.join(5000L);
            } catch (InterruptedException stopped) { interrupted = true; }
            finally {
                try { Runtime.getRuntime().removeShutdownHook(hook); } catch (IllegalStateException exiting) { }
                if (interrupted) Thread.currentThread().interrupt();
            }
        }
        if (process.isAlive()) throw new WorldBuilderContractException(WorldBuilderErrorCodes.RECOVERY_REQUIRED, OP,
            "verification-attempt", false, "Verifier cleanup was interrupted; retain its workspace and processes for recovery.",
            "Do not activate or force-delete the verification workspace.");
        if (interrupted || !completed || cancelled(cancellation) || drain.failed || output.isAlive()
            || process.exitValue() != 0) throw failure("Runtime verification failed, timed out, or was cancelled; no execution evidence is accepted.");
    }

    private static final class OutputDrain implements Runnable {
        private final Process process; private final OutputStream diagnostic; volatile boolean failed;
        OutputDrain(Process process, OutputStream diagnostic) { this.process = process; this.diagnostic = diagnostic; }
        @Override public void run() {
            long size = 0; byte[] buffer = new byte[4096];
            try (InputStream input = process.getInputStream(); OutputStream output = diagnostic) {
                int count; while ((count = input.read(buffer)) != -1) {
                    int retained = (int)Math.min(count, Math.max(0L, MAX_EVIDENCE - size));
                    if (retained > 0) output.write(buffer, 0, retained);
                    size += count;
                    if (size > MAX_EVIDENCE) { failed = true; closeLifetime(process); }
                }
            } catch (IOException problem) { failed = true; closeLifetime(process); }
        }
    }

    static void validateEvidence(Map<String,Object> evidence, Map<String,Object> identity, String identityHash,
        String serverHash, String clientHash, String inputsHash, String mapFingerprint,
        int serverPort, int websocketPort, Path workspace) throws IOException, WorldBuilderContractException {
        validatePortableEvidence(evidence, identity, identityHash, serverHash, clientHash, inputsHash,
            mapFingerprint, serverPort, websocketPort);
        if (Files.exists(workspace.resolve("execution/credential.json"), LinkOption.NOFOLLOW_LINKS)) throw failure("Verifier credential was not removed.");
        equal(object(evidence.get("execution")), "workingStateFinalSha256",
            WorldBuilderHashes.sha256(regular(workspace, "state/current_base.db")));
        for (Object raw : array(evidence.get("logs"))) {
            Map<String,Object> log = object(raw);
            Path file = regular(workspace, "logs/" + string(log, "role") + "-" + integer(log, "run") + ".log");
            if (Files.size(file) != integer(log, "size") || !WorldBuilderHashes.sha256(file).equals(string(log, "sha256")))
                throw failure("Process log bytes differ from the execution evidence.");
        }
    }

    /** Closed historical evidence readback; does not grant fresh execution or cleanup authority. */
    static void validatePortableEvidence(Map<String,Object> evidence, Map<String,Object> identity, String identityHash,
        String serverHash, String clientHash, String inputsHash, String mapFingerprint,
        int serverPort, int websocketPort) throws WorldBuilderContractException {
        exact(evidence, "schemaId", "manifestType", "verifierId", "verifierContractSha256", "status", "composition", "source", "execution", "runs", "logs", "supervision");
        equal(evidence, "schemaId", "current-base-installed-execution-evidence-v1");
        equal(evidence, "manifestType", "current-base-installed-execution-evidence");
        equal(evidence, "verifierId", "current-base-installed-execution-v1");
        equal(evidence, "verifierContractSha256", CONTRACT_HASH); equal(evidence, "status", "verified");
        Map<String,Object> supervision = object(evidence.get("supervision"));
        exact(supervision, "invocationId", "supervisionSha256", "invocationSha256", "intentSha256", "revocationSha256", "closed");
        String invocationId = string(supervision, "invocationId");
        try {
            if (!java.util.UUID.fromString(invocationId).toString().equals(invocationId)) throw new IllegalArgumentException();
        } catch (IllegalArgumentException invalid) { throw failure("Verifier invocation identity is malformed."); }
        for (String field : Arrays.asList("supervisionSha256", "invocationSha256", "intentSha256", "revocationSha256"))
            hash(supervision, field);
        truth(supervision, "closed");
        Map<String,Object> composition = object(evidence.get("composition"));
        exact(composition, "platformReleaseId", "platformManifestHash", "variantId", "variantManifestHash", "moduleSetHash", "bundleInventoryHash", "identitySha256");
        for (String field : IDENTITY) equal(composition, field, string(identity, field));
        equal(composition, "identitySha256", identityHash);
        Map<String,Object> source = object(evidence.get("source"));
        exact(source, "serverTreeBeforeSha256", "serverTreeAfterSha256", "clientTreeBeforeSha256", "clientTreeAfterSha256", "inputSetBeforeSha256", "inputSetAfterSha256", "unchanged");
        for (String side : Arrays.asList("Before", "After")) {
            equal(source, "serverTree" + side + "Sha256", serverHash);
            equal(source, "clientTree" + side + "Sha256", clientHash);
            equal(source, "inputSet" + side + "Sha256", inputsHash);
        }
        truth(source, "unchanged");
        Map<String,Object> execution = object(evidence.get("execution"));
        exact(execution, "endpoint", "serverPort", "websocketPort", "launchCount", "mapPackageFingerprint", "disposableAccountId", "disposableUsernameSha256",
            "workingStateSeededSha256", "workingStateFinalSha256", "disposableStateChanged", "stateOutsideRuntimeRoots", "mapOutsideRuntimeRoots", "mapUnchanged", "persistenceVerified", "credentialDeleted");
        equal(execution, "endpoint", "127.0.0.1"); equal(execution, "mapPackageFingerprint", mapFingerprint);
        if (integer(execution, "serverPort") != serverPort || integer(execution, "websocketPort") != websocketPort
            || serverPort < 1 || serverPort > 65535 || websocketPort < 1 || websocketPort > 65535 || serverPort == websocketPort
            || integer(execution, "launchCount") != 2L || integer(execution, "disposableAccountId") < 1L)
            throw failure("Execution endpoints, launch count or disposable account evidence changed.");
        for (String key : Arrays.asList("disposableUsernameSha256", "workingStateSeededSha256", "workingStateFinalSha256")) hash(execution, key);
        if (string(execution, "workingStateSeededSha256").equals(string(execution, "workingStateFinalSha256")))
            throw failure("Execution did not change disposable state.");
        for (String key : Arrays.asList("disposableStateChanged", "stateOutsideRuntimeRoots", "mapOutsideRuntimeRoots", "mapUnchanged", "persistenceVerified", "credentialDeleted")) truth(execution, key);
        List<Object> runs = array(evidence.get("runs"));
        if (runs.size() != 2) throw failure("Two execution observations are required.");
        Map<String,Object> first = null;
        for (int i = 0; i < runs.size(); i++) {
            Map<String,Object> run = object(runs.get(i));
            exact(run, "run", "handshakeAccepted", "loginAccepted", "canonicalMap", "initialRegion", "worldX", "worldY", "coins", "prayer", "magic", "woodcut", "questStage", "advancedExcluded", "logoutPersisted");
            if (integer(run, "run") != i + 1) throw failure("Execution observations are out of order.");
            for (String key : Arrays.asList("handshakeAccepted", "loginAccepted", "canonicalMap", "initialRegion", "advancedExcluded", "logoutPersisted")) truth(run, key);
            for (String key : Arrays.asList("worldX", "worldY", "coins", "prayer", "magic", "woodcut", "questStage")) {
                long value = integer(run, key);
                if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE || !key.startsWith("world") && value < 1)
                    throw failure("Execution observations are outside their bounds.");
                if (first != null && integer(first, key) != value) throw failure("Runtime state differs across restart observations.");
            }
            first = run;
        }
        List<Object> logs = array(evidence.get("logs"));
        if (logs.size() != 4) throw failure("Four bounded process logs are required.");
        Set<String> seen = new HashSet<String>();
        for (Object raw : logs) {
            Map<String,Object> log = object(raw); exact(log, "run", "role", "sha256", "size", "truncated");
            long run = integer(log, "run"), size = integer(log, "size"); String role = string(log, "role");
            if (run < 1 || run > 2 || !Arrays.asList("server", "client").contains(role)
                || !seen.add(role + run) || size < 0 || size > 1048576 || !(log.get("truncated") instanceof Boolean))
                throw failure("Process log evidence is duplicated, malformed or unbounded.");
            hash(log, "sha256");
        }
    }

    static String treeHash(Path root) throws IOException, WorldBuilderContractException {
        canonicalDirectory(root);
        List<String> records = new ArrayList<String>(); long total = 0;
        try (java.util.stream.Stream<Path> entries = Files.walk(root)) {
            java.util.Iterator<Path> iterator = entries.iterator(); int count = 0;
            while (iterator.hasNext()) {
                Path path = iterator.next();
                if (++count > 30000 || Files.isSymbolicLink(path)) throw failure("Verifier source tree is linked or unbounded.");
                if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) continue;
                Path file = regular(root, root.relativize(path).toString().replace('\\', '/'));
                total += Files.size(file);
                if (records.size() >= 20000 || total > 1073741824L) throw failure("Verifier source tree exceeds its reviewed bound.");
                records.add(root.relativize(file).toString().replace('\\', '/') + "\0" + WorldBuilderHashes.sha256(file) + "\0");
            }
        }
        Collections.sort(records); return WorldBuilderHashes.sha256(String.join("", records).getBytes(StandardCharsets.UTF_8));
    }

    static String inputSetHash(Path... paths) throws IOException, WorldBuilderContractException {
        StringBuilder value = new StringBuilder();
        for (int i = 0; i < paths.length; i++) value.append(i).append('\0').append(
            Files.isDirectory(paths[i], LinkOption.NOFOLLOW_LINKS) ? treeHash(paths[i]) : WorldBuilderHashes.sha256(paths[i])).append('\0');
        return WorldBuilderHashes.sha256(value.toString().getBytes(StandardCharsets.UTF_8));
    }
    private static String javaExecutable() { return Paths.get(System.getProperty("java.home"), "bin", "java").toString(); }
    private static void closeLifetime(Process process) { try { process.getOutputStream().close(); } catch (IOException ignored) { } }
    private static boolean cancelled(BooleanSupplier value) { return (value == null ? NEVER_CANCEL : value).getAsBoolean(); }
    private static void disjoint(Path a, Path b) throws WorldBuilderContractException { if (a.startsWith(b) || b.startsWith(a)) throw failure("Verification output overlaps an input root."); }
    private static Path canonicalDirectory(Path path) throws IOException, WorldBuilderContractException {
        Path absolute = path.toAbsolutePath();
        if (!absolute.equals(absolute.normalize()) || !Files.isDirectory(absolute, LinkOption.NOFOLLOW_LINKS)
            || !absolute.equals(absolute.toRealPath())) throw failure("Verification path must be a canonical, unaliased directory.");
        return absolute;
    }
    private static Path regular(Path root, String relative) throws WorldBuilderContractException { return WorldBuilderReadOnlyTarget.open(root).requiredFile(relative); }
    private static Map<String,Object> read(Path file) throws IOException, WorldBuilderContractException {
        try { return WorldBuilderJsonDocuments.readObject(file); } catch (WorldBuilderDiscoveryException invalid) { throw failure("Verifier evidence is not a bounded JSON object."); }
    }
    private static void exact(Map<String,Object> map, String... keys) throws WorldBuilderContractException { WorldBuilderBoundedInventory.exactKeys(map, OP, keys); }
    private static void equal(Map<String,Object> map, String key, String value) throws WorldBuilderContractException { if (!value.equals(string(map, key))) throw failure("Verifier evidence binding differs: " + key); }
    private static void truth(Map<String,Object> map, String key) throws WorldBuilderContractException { if (!Boolean.TRUE.equals(map.get(key))) throw failure("Required verifier observation is false: " + key); }
    private static void hash(Map<String,Object> map, String key) throws WorldBuilderContractException { if (!WorldBuilderBoundedInventory.isHash(string(map, key))) throw failure("Verifier digest is malformed: " + key); }
    private static String string(Map<String,Object> map, String key) throws WorldBuilderContractException { return WorldBuilderBoundedInventory.string(map.get(key), OP, key); }
    private static long integer(Map<String,Object> map, String key) throws WorldBuilderContractException { return WorldBuilderBoundedInventory.integer(map.get(key), OP, key); }
    @SuppressWarnings("unchecked") private static Map<String,Object> object(Object value) throws WorldBuilderContractException { if (!(value instanceof Map)) throw failure("Verifier object is malformed."); return (Map<String,Object>)value; }
    @SuppressWarnings("unchecked") private static List<Object> array(Object value) throws WorldBuilderContractException { if (!(value instanceof List)) throw failure("Verifier array is malformed."); return (List<Object>)value; }
    private static WorldBuilderContractException failure(String message) { return new WorldBuilderContractException(WorldBuilderErrorCodes.CONVERSION_BLOCKED,
        OP, "execution-evidence", false, message, "Retain the disposable verification attempt; do not activate the target."); }
}
