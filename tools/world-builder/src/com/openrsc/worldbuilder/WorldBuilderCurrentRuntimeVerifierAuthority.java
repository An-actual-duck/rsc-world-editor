package com.openrsc.worldbuilder;

import java.io.IOException;
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
import java.util.UUID;

/** Fixed, receipt-bound live authority. Portable execution reports are not cleanup authority. */
final class WorldBuilderCurrentRuntimeVerifierAuthority {
    private static final String OP = "current-runtime-verifier-authority";
    private static final String RECOVERY = "com.openrsc.server.database.CurrentBaseVerifierRecovery";
    static final List<String> INPUTS = Collections.unmodifiableList(Arrays.asList("client-profile",
        "composition-identity", "contract", "installed-client-root", "installed-server-root", "map-package",
        "runtime-profile", "server-config", "server-profile", "state-db"));
    private static final List<String> OPTIONS;
    static {
        List<String> names = new ArrayList<String>(INPUTS);
        names.addAll(Arrays.asList("workspace", "server-port", "websocket-port", "evidence"));
        Collections.sort(names); OPTIONS = Collections.unmodifiableList(names);
    }
    private WorldBuilderCurrentRuntimeVerifierAuthority() { }

    static void retainTools(Path attempt, Path core, Path contract) throws IOException, WorldBuilderContractException {
        privateDirectory(attempt);
        Path tools = attempt.resolve("provider-tools"); createDirectory(tools);
        copy(core, tools.resolve("core.jar")); copy(contract, tools.resolve("contract.json"));
        WorldBuilderAdaptiveDurability.forceDirectory(tools);
        WorldBuilderAdaptiveDurability.forceDirectory(attempt);
    }

    /** No processes are started. Caller must durably journal the returned record before execute. */
    static Map<String,Object> prepare(Path attempt, Map<String,String> options)
        throws IOException, WorldBuilderContractException {
        privateDirectory(attempt); validateOptions(options);
        for (String name : INPUTS) {
            Path input = path(options.get(name));
            if (!input.equals(input.toRealPath())) throw unsafe("Prepared verifier input is aliased.");
            disjoint(attempt.resolve("control"), input); disjoint(attempt.resolve("execution"), input);
        }
        if (!attempt.resolve("provider-tools/contract.json").toString().equals(options.get("contract"))
            || !attempt.resolve("execution").toString().equals(options.get("workspace"))
            || !attempt.resolve("evidence.json").toString().equals(options.get("evidence")))
            throw unsafe("Prepared invocation differs from the fixed attempt layout.");
        Path control = attempt.resolve("control"); createDirectory(control);
        Map<String,Object> files = new LinkedHashMap<String,Object>();
        for (String role : Arrays.asList("supervisor", "server", "client", "intent")) {
            Path file = control.resolve("intent".equals(role) ? "intent.json" : role + ".lock");
            writeNew(file, new byte[0]); files.put(role, inode(file));
        }
        Map<String,Object> authority = new LinkedHashMap<String,Object>();
        authority.put("schemaVersion", Long.valueOf(1));
        authority.put("manifestType", "current-base-verifier-supervision");
        authority.put("invocationId", UUID.randomUUID().toString());
        authority.put("controlRoot", control.toString()); authority.put("workspace", options.get("workspace"));
        authority.put("invocationSha256", invocationHash(options));
        authority.put("compositionIdentitySha256", WorldBuilderHashes.sha256(Paths.get(options.get("composition-identity"))));
        authority.put("verifierContractSha256", WorldBuilderInstalledRuntimeVerifier.CONTRACT_HASH);
        Map<String,Object> inputs = new LinkedHashMap<String,Object>();
        for (String name : INPUTS) inputs.put(name, options.get(name));
        authority.put("inputPaths", inputs); authority.put("files", files);
        Path authorityPath = control.resolve("authority.json");
        writeNew(authorityPath, WorldBuilderJsonDocuments.pretty(authority).getBytes(StandardCharsets.UTF_8));
        WorldBuilderAdaptiveDurability.forceDirectory(control);
        WorldBuilderAdaptiveDurability.forceDirectory(attempt);
        Map<String,Object> result = new LinkedHashMap<String,Object>();
        result.put("schemaVersion", Long.valueOf(1)); result.put("attemptRoot", attempt.toString());
        result.put("authoritySha256", WorldBuilderHashes.sha256(authorityPath));
        result.put("invocationId", authority.get("invocationId"));
        result.put("invocationSha256", authority.get("invocationSha256"));
        result.put("options", new LinkedHashMap<String,String>(options));
        List<Object> retained = new ArrayList<Object>();
        for (String name : Arrays.asList("core.jar", "contract.json")) {
            Path file = attempt.resolve("provider-tools/" + name); Map<String,Object> row = new LinkedHashMap<String,Object>();
            row.put("relativePath", "provider-tools/" + name); row.put("size", Long.valueOf(Files.size(file)));
            row.put("sha256", WorldBuilderHashes.sha256(file)); row.put("mode", "0600"); retained.add(row);
        }
        result.put("retainedTools", retained); validate(result, false);
        writeNew(attempt.resolve("prepared.json"), WorldBuilderJsonDocuments.pretty(result).getBytes(StandardCharsets.UTF_8));
        return snapshot(result);
    }

    static void validate(Map<String,Object> record, boolean allowEmpty) throws WorldBuilderContractException {
        if (allowEmpty && record.isEmpty()) return;
        exact(record, "schemaVersion", "attemptRoot", "authoritySha256", "invocationId", "invocationSha256", "options", "retainedTools");
        if (number(record.get("schemaVersion")) != 1) throw unsafe("Unsupported verifier attempt record.");
        path(string(record, "attemptRoot")); hash(record, "authoritySha256"); hash(record, "invocationSha256");
        try {
            String id = string(record, "invocationId");
            if (!UUID.fromString(id).toString().equals(id)) throw new IllegalArgumentException();
        } catch (IllegalArgumentException invalid) { throw unsafe("Malformed verifier invocation identity."); }
        Map<String,String> options = options(record); validateOptions(options);
        if (!invocationHash(options).equals(record.get("invocationSha256"))) throw unsafe("Invocation journal hash differs.");
        Path attempt = path(string(record, "attemptRoot"));
        for (String name : INPUTS) {
            disjoint(attempt.resolve("control"), path(options.get(name)));
            disjoint(attempt.resolve("execution"), path(options.get(name)));
        }
        if (!attempt.resolve("execution").toString().equals(options.get("workspace"))
            || !attempt.resolve("evidence.json").toString().equals(options.get("evidence"))
            || !attempt.resolve("composition-identity.json").toString().equals(options.get("composition-identity"))
            || !attempt.resolve("provider-tools/contract.json").toString().equals(options.get("contract")))
            throw unsafe("Invocation journal does not bind its fixed layout.");
        List<Object> retained = list(record.get("retainedTools"));
        if (retained.size() != 2) throw unsafe("Recovery tool inventory is incomplete.");
        int index = 0;
        for (String name : Arrays.asList("core.jar", "contract.json")) {
            Map<String,Object> row = object(retained.get(index++)); exact(row, "relativePath", "size", "sha256", "mode");
            long size = number(row.get("size")); hash(row, "sha256");
            if (!row.get("relativePath").equals("provider-tools/" + name) || !"0600".equals(row.get("mode"))
                || size < 1 || size > ("core.jar".equals(name) ? 268435456L : 65536L)) throw unsafe("Invalid retained recovery tool.");
            if ("contract.json".equals(name) && !WorldBuilderInstalledRuntimeVerifier.CONTRACT_HASH.equals(row.get("sha256")))
                throw unsafe("Retained verifier contract is not supported.");
        }
    }

    /** Authenticate the trusted executable against the confirmed plan before executing recovery. */
    static void authenticate(Path expectedAttempt, Map<String,Object> record, List<?> artifactPlan)
        throws IOException, WorldBuilderContractException {
        validate(record, false);
        if (!expectedAttempt.toString().equals(record.get("attemptRoot"))) throw unsafe("Verifier authority cannot relocate.");
        verifyFiles(record);
        for (Object raw : list(record.get("retainedTools"))) {
            Map<String,Object> retained = object(raw);
            String bundle = "provider-tools/core.jar".equals(retained.get("relativePath"))
                ? "runtime/server/core.jar" : WorldBuilderInstalledRuntimeVerifier.CONTRACT;
            Map<String,Object> selected = null;
            for (Object action : artifactPlan) {
                Map<String,Object> row = object(action);
                if (bundle.equals(row.get("bundlePath"))) {
                    if (selected != null) throw unsafe("Ambiguous recovery tool in confirmed artifact plan."); selected = row;
                }
            }
            if (selected == null || !retained.get("sha256").equals(selected.get("sha256"))
                || number(retained.get("size")) != number(selected.get("size")))
                throw unsafe("Retained recovery executable is not the confirmed provider artifact.");
        }
    }

    static List<String> launchCommand(Map<String,Object> record) throws IOException, WorldBuilderContractException {
        verifyFiles(record); List<String> result = command(record, WorldBuilderInstalledRuntimeVerifier.MAIN);
        Map<String,String> options = options(record);
        for (String name : OPTIONS) { result.add("--" + name); result.add(options.get(name)); }
        supervisionArguments(result, record); return result;
    }

    /** A fresh provider closure is required even after a failed parent exit; no PID inference. */
    static Map<String,Object> close(Map<String,Object> record) throws IOException, WorldBuilderContractException {
        try {
            verifyFiles(record);
            Path attempt = path(string(record, "attemptRoot"));
            Path output = attempt.resolve("recovery-" + UUID.randomUUID().toString()); createDirectory(output);
            WorldBuilderAdaptiveDurability.forceDirectory(attempt);
            List<String> command = command(record, RECOVERY);
            command.add("--contract"); command.add(options(record).get("contract"));
            supervisionArguments(command, record); command.add("--evidence"); command.add(output.resolve("evidence.json").toString());
            WorldBuilderInstalledRuntimeVerifier.runCommand(command, output, null, 30L, 10L);
            Path evidencePath = output.resolve("evidence.json"); privateFile(evidencePath, 65536L);
            Map<String,Object> evidence = read(evidencePath); validateClosure(record, evidence);
            // Cross-check actual durable revocation after the recovery JVM has exited.
            Path revocation = attempt.resolve("control/revocation.json");
            privateFile(revocation, 65536L);
            if (!WorldBuilderHashes.sha256(revocation).equals(evidence.get("revocationSha256")))
                throw unsafe("Closure evidence does not bind the durable revocation.");
            return evidence;
        } catch (IOException | WorldBuilderContractException failure) {
            WorldBuilderContractException retained = unsafe("Provider closure was not proven; retain this transaction and verifier authority.");
            retained.addSuppressed(failure); throw retained;
        }
    }

    static void validateClosure(Map<String,Object> record, Map<String,Object> evidence) throws WorldBuilderContractException {
        exact(evidence, "schemaVersion", "manifestType", "status", "verifierContractSha256", "invocationId",
            "supervisionSha256", "invocationSha256", "intentSha256", "revocationSha256", "credentialDeleted");
        if (number(evidence.get("schemaVersion")) != 1 || !"current-base-verifier-recovery-evidence".equals(evidence.get("manifestType"))
            || !"closed".equals(evidence.get("status")) || !Boolean.TRUE.equals(evidence.get("credentialDeleted"))
            || !WorldBuilderInstalledRuntimeVerifier.CONTRACT_HASH.equals(evidence.get("verifierContractSha256")))
            throw unsafe("Provider recovery did not report its exact closed contract.");
        bind(record, evidence);
        String intent = string(evidence, "intentSha256"); if (!intent.isEmpty()) hash(evidence, "intentSha256");
        hash(evidence, "revocationSha256");
    }

    static void bind(Map<String,Object> record, Map<String,Object> proof) throws WorldBuilderContractException {
        if (!record.get("authoritySha256").equals(proof.get("supervisionSha256"))
            || !record.get("invocationId").equals(proof.get("invocationId"))
            || !record.get("invocationSha256").equals(proof.get("invocationSha256")))
            throw unsafe("Provider proof belongs to a different verifier invocation.");
    }

    private static void verifyFiles(Map<String,Object> record) throws IOException, WorldBuilderContractException {
        validate(record, false); Path attempt = path(string(record, "attemptRoot")); privateDirectory(attempt);
        privateDirectory(attempt.resolve("provider-tools")); privateDirectory(attempt.resolve("control"));
        for (Object raw : list(record.get("retainedTools"))) {
            Map<String,Object> row = object(raw); Path file = attempt.resolve(string(row, "relativePath"));
            privateFile(file, 268435456L);
            if (Files.size(file) != number(row.get("size")) || !WorldBuilderHashes.sha256(file).equals(row.get("sha256")))
                throw unsafe("Retained verifier tool changed.");
        }
        Path authorityPath = attempt.resolve("control/authority.json"); privateFile(authorityPath, 65536L);
        byte[] bytes = Files.readAllBytes(authorityPath);
        if (!WorldBuilderHashes.sha256(bytes).equals(record.get("authoritySha256"))) throw unsafe("Verifier authority changed.");
        Map<String,Object> authority;
        try { authority = WorldBuilderJsonDocuments.readObject(bytes, "verifier authority"); }
        catch (WorldBuilderDiscoveryException malformed) { throw unsafe("Verifier authority is malformed."); }
        exact(authority, "schemaVersion", "manifestType", "invocationId", "controlRoot", "workspace",
            "invocationSha256", "compositionIdentitySha256", "verifierContractSha256", "inputPaths", "files");
        if (number(authority.get("schemaVersion")) != 1
            || !"current-base-verifier-supervision".equals(authority.get("manifestType"))
            || !WorldBuilderInstalledRuntimeVerifier.CONTRACT_HASH.equals(authority.get("verifierContractSha256"))
            || !attempt.resolve("execution").toString().equals(authority.get("workspace"))
            || !record.get("invocationId").equals(authority.get("invocationId"))
            || !record.get("invocationSha256").equals(authority.get("invocationSha256"))
            || !attempt.resolve("control").toString().equals(authority.get("controlRoot"))) throw unsafe("Verifier authority journal differs.");
        Map<String,Object> inputs = object(authority.get("inputPaths")); exact(inputs, INPUTS.toArray(new String[0]));
        Map<String,String> options = options(record);
        for (String name : INPUTS) if (!options.get(name).equals(inputs.get(name))) throw unsafe("Verifier historical input path differs.");
        Path identity = attempt.resolve("composition-identity.json"); privateFile(identity, 65536L);
        hash(authority, "compositionIdentitySha256");
        if (!WorldBuilderHashes.sha256(identity).equals(authority.get("compositionIdentitySha256")))
            throw unsafe("Retained composition identity changed.");
        Map<String,Object> anchors = object(authority.get("files")); exact(anchors, "supervisor", "server", "client", "intent");
        for (String role : Arrays.asList("supervisor", "server", "client", "intent")) {
            Map<String,Object> anchor = object(anchors.get(role)); exact(anchor, "device", "inode");
            Path file = attempt.resolve("control/" + ("intent".equals(role) ? "intent.json" : role + ".lock"));
            privateFile(file, "intent".equals(role) ? 65536L : 0L);
            for (String key : Arrays.asList("device", "inode")) {
                String expected = string(anchor, key);
                if (!expected.matches("0|[1-9][0-9]*") || !expected.equals(Files.getAttribute(file,
                    "unix:" + ("device".equals(key) ? "dev" : "ino"), LinkOption.NOFOLLOW_LINKS).toString()))
                    throw unsafe("Verifier anchor inode changed.");
            }
        }
    }

    private static List<String> command(Map<String,Object> record, String main) throws WorldBuilderContractException {
        return new ArrayList<String>(Arrays.asList(Paths.get(System.getProperty("java.home"), "bin", "java").toString(),
            "-cp", path(string(record, "attemptRoot")).resolve("provider-tools/core.jar").toString(), main));
    }
    private static void supervisionArguments(List<String> command, Map<String,Object> record) throws WorldBuilderContractException {
        command.add("--supervision"); command.add(path(string(record, "attemptRoot")).resolve("control/authority.json").toString());
        command.add("--supervision-sha256"); command.add(string(record, "authoritySha256"));
    }
    static String invocationHash(Map<String,String> options) {
        StringBuilder framed = new StringBuilder();
        for (String name : OPTIONS) framed.append(name).append('\0').append(options.get(name)).append('\0');
        return WorldBuilderHashes.sha256(framed.toString().getBytes(StandardCharsets.UTF_8));
    }
    private static Map<String,String> options(Map<String,Object> record) throws WorldBuilderContractException {
        Map<String,Object> raw = object(record.get("options")); exact(raw, OPTIONS.toArray(new String[0]));
        Map<String,String> result = new LinkedHashMap<String,String>();
        for (String name : OPTIONS) result.put(name, string(raw, name)); return result;
    }
    private static void validateOptions(Map<String,String> options) throws WorldBuilderContractException {
        if (!options.keySet().equals(new java.util.HashSet<String>(OPTIONS))) throw unsafe("Verifier invocation options are not closed.");
        for (String name : OPTIONS) {
            String value = options.get(name);
            if (value == null) throw unsafe("Verifier argument is missing.");
            if ("server-port".equals(name) || "websocket-port".equals(name)) {
                try { int port = Integer.parseInt(value); if (port < 1 || port > 65535 || !Integer.toString(port).equals(value)) throw new NumberFormatException(); }
                catch (NumberFormatException invalid) { throw unsafe("Verifier port is not a bounded decimal."); }
            } else path(value);
        }
        if (options.get("server-port").equals(options.get("websocket-port"))) throw unsafe("Verifier ports must differ.");
    }
    private static Path path(String value) throws WorldBuilderContractException {
        try { Path path = Paths.get(value); if (value.length() > 4096 || !path.isAbsolute() || !path.normalize().equals(path)) throw new IllegalArgumentException(); return path; }
        catch (IllegalArgumentException invalid) { throw unsafe("Verifier path is not an absolute bounded canonical spelling."); }
    }
    private static void disjoint(Path left, Path right) throws WorldBuilderContractException {
        if (left.startsWith(right) || right.startsWith(left)) throw unsafe("Verifier authority overlaps an invocation input.");
    }
    private static void privateDirectory(Path path) throws IOException, WorldBuilderContractException {
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) || !path.equals(path.toRealPath())
            || !Files.getPosixFilePermissions(path).equals(PosixFilePermissions.fromString("rwx------")))
            throw unsafe("Verifier authority directory is aliased or not private.");
    }
    private static void privateFile(Path path, long bound) throws IOException, WorldBuilderContractException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || !path.equals(path.toRealPath())
            || !Files.getPosixFilePermissions(path).equals(PosixFilePermissions.fromString("rw-------"))
            || Files.size(path) > bound || ((Number)Files.getAttribute(path, "unix:nlink", LinkOption.NOFOLLOW_LINKS)).longValue() != 1)
            throw unsafe("Verifier authority file is aliased, linked, unbounded or not private.");
    }
    private static Map<String,Object> inode(Path path) throws IOException, WorldBuilderContractException {
        privateFile(path, 0L); Map<String,Object> result = new LinkedHashMap<String,Object>();
        for (String key : Arrays.asList("device", "inode")) result.put(key, Files.getAttribute(path,
            "unix:" + ("device".equals(key) ? "dev" : "ino"), LinkOption.NOFOLLOW_LINKS).toString()); return result;
    }
    private static void createDirectory(Path path) throws IOException {
        Files.createDirectory(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        WorldBuilderAdaptiveDurability.forceDirectory(path); WorldBuilderAdaptiveDurability.forceDirectory(path.getParent());
    }
    private static void writeNew(Path path, byte[] bytes) throws IOException {
        Files.createFile(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        Files.write(path, bytes, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
        WorldBuilderAdaptiveDurability.forceFile(path); WorldBuilderAdaptiveDurability.forceDirectory(path.getParent());
    }
    private static void copy(Path source, Path destination) throws IOException { writeNew(destination, new byte[0]);
        try (java.io.InputStream input = Files.newInputStream(source, LinkOption.NOFOLLOW_LINKS);
             java.io.OutputStream output = Files.newOutputStream(destination, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = new byte[8192]; long total = 0; int count;
            while ((count = input.read(bytes)) != -1) { total += count; if (total > 268435456L) throw new IOException("Recovery tool exceeds bound"); output.write(bytes, 0, count); }
        } WorldBuilderAdaptiveDurability.forceFile(destination); }
    private static Map<String,Object> read(Path path) throws IOException, WorldBuilderContractException {
        try { return WorldBuilderJsonDocuments.readObject(path); }
        catch (WorldBuilderDiscoveryException invalid) { throw unsafe("Verifier authority evidence is malformed."); }
    }
    private static void exact(Map<String,Object> map, String... keys) throws WorldBuilderContractException { WorldBuilderBoundedInventory.exactKeys(map, OP, keys); }
    static Map<String,Object> snapshot(Map<String,Object> value) {
        Map<String,Object> copy = new LinkedHashMap<String,Object>();
        for (Map.Entry<String,Object> entry : value.entrySet()) copy.put(entry.getKey(), freeze(entry.getValue()));
        return Collections.unmodifiableMap(copy);
    }
    static List<Object> snapshotList(List<Object> value) {
        List<Object> copy = new ArrayList<Object>(); for (Object item : value) copy.add(freeze(item));
        return Collections.unmodifiableList(copy);
    }
    @SuppressWarnings("unchecked") private static Object freeze(Object value) {
        if (value instanceof Map) return snapshot((Map<String,Object>)value);
        if (value instanceof List) return snapshotList((List<Object>)value);
        return value;
    }
    private static String string(Map<String,Object> map, String key) throws WorldBuilderContractException { return WorldBuilderBoundedInventory.string(map.get(key), OP, key); }
    private static long number(Object value) throws WorldBuilderContractException { return WorldBuilderBoundedInventory.integer(value, OP, "number"); }
    private static void hash(Map<String,Object> map, String key) throws WorldBuilderContractException {
        if (!WorldBuilderBoundedInventory.isHash(string(map, key))) throw unsafe("Verifier hash is malformed."); }
    @SuppressWarnings("unchecked") private static Map<String,Object> object(Object value) throws WorldBuilderContractException {
        if (!(value instanceof Map)) throw unsafe("Verifier journal object is malformed."); return (Map<String,Object>)value; }
    @SuppressWarnings("unchecked") private static List<Object> list(Object value) throws WorldBuilderContractException {
        if (!(value instanceof List)) throw unsafe("Verifier journal array is malformed."); return (List<Object>)value; }
    static WorldBuilderContractException unsafe(String message) { return new WorldBuilderContractException(
        WorldBuilderErrorCodes.RECOVERY_REQUIRED, OP, "runtime-verification", false, message,
        "Keep the target offline and retain the exact transaction, authority and provider tools; never infer cleanup from a PID or portable report."); }
}
