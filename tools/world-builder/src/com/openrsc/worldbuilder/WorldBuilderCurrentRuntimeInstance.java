package com.openrsc.worldbuilder;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.*;

/** New-instance construction only. Activation, live-state mutation, and recovery belong to the transaction. */
final class WorldBuilderCurrentRuntimeInstance {
    private static final String OP = "current-runtime-instance";
    private static final String MAP = "migration/output/map/conversion/package";
    private static final String LAUNCH = "migration/output/launch/";
    private static final long MAX_TREE_BYTES = 4L * 1073741824L;
    private static final Set<String> SERVER_REQUIRED = names("server.pem", "client.pem", "badwords.txt", "goodwords.txt", "alertwords.txt");
    private static final Set<String> SERVER_OPTIONAL = names("ipbans.txt", "ipbans.temp");
    private static final Set<String> CLIENT_OPTIONAL = names("clientSettings.conf", "uid.dat", "hideIp.txt", "credentials.txt");
    private static final String[] SPEC_KEYS = {"installationId", "generationId", "installationRoot", "compositionIdentityPath",
        "runtimeProfilePath", "serverMapProfilePath", "clientMapProfilePath", "serverConfigurationPath", "mapRoot",
        "serverCodeRoot", "clientCodeRoot", "serverWorkingRoot", "clientWorkingRoot", "serverStateRoot", "clientStateRoot",
        "serverSideStateRoot", "clientSideStateRoot",
        "compositionIdentitySha256", "runtimeProfileSha256", "serverMapProfileSha256", "clientMapProfileSha256",
        "mapPackageFingerprintSha256", "serverCodeTreeSha256", "clientCodeTreeSha256", "serverConfigurationSha256",
        "serverPublicKeySha256", "clientPublicKeySha256", "host", "gamePort"};

    private WorldBuilderCurrentRuntimeInstance() { }

    /**
     * Caller first verifies the reviewed release/complete conversion. This additional closure checks
     * its layout, generated-state seal, provider executable bindings, map profile, and every copied input.
     * Side-state maps must explicitly enumerate the adapter's complete admitted sources (including
     * optional files when present). This component never scans a historical target or generates keys.
     */
    static Plan inspectInitial(Path stagedRelease, Path finalRelease, Path finalInstanceRoot,
        String installationId, String generationId, Map<String,Object> compositionIdentity,
        Map<String,Object> runtimeLayout, List<Object> generatedStateOutputs,
        Map<String,Path> serverSideSources, Map<String,Path> clientSideSources, String host, int port)
        throws IOException, WorldBuilderContractException {
        Path stage = directory(stagedRelease);
        Path release = projected(finalRelease), instance = projected(finalInstanceRoot);
        disjoint(stage, instance); disjoint(release, instance);
        if (!stage.equals(release)) disjoint(stage, release);
        absent(instance);
        List<Object> sourceTree = inventory(stage, false);
        WorldBuilderCurrentRuntimeLayout.verify(stage, runtimeLayout);
        WorldBuilderCurrentRuntimeGeneratedState.validate(generatedStateOutputs, false);
        WorldBuilderCurrentRuntimeGeneratedState.verify(stage, generatedStateOutputs);
        Map<String,Object> identity = copy(compositionIdentity);
        if (!Boolean.TRUE.equals(identity.get("installable")) || !"current-base-v1".equals(identity.get("variantId")))
            throw failure("Only an installable Current Base identity may be projected.");
        verifyRole(identity, "server-runtime", stage.resolve("installed/server/core.jar"));
        verifyRole(identity, "server-plugins", stage.resolve("installed/server/plugins.jar"));
        verifyRole(identity, "client-runtime", stage.resolve("installed/client/Open_RSC_Client.jar"));
        verifyRole(identity, "runtime-profile", stage.resolve("runtime/profile.json"));
        Map<String,Object> profile = read(stage.resolve("runtime/profile.json"));
        if (!"current-base-v1".equals(profile.get("variantId")) || !(profile.get("installedLaunch") instanceof Map))
            throw failure("Provider profile does not advertise normal installed launch.");
        String mapHash = treeFingerprint(stage.resolve(MAP), true);
        Map<String,Object> spec = new LinkedHashMap<String,Object>();
        spec.put("installationId", installationId); spec.put("generationId", generationId);
        spec.put("installationRoot", instance.resolve("installation").toString());
        spec.put("compositionIdentityPath", instance.resolve("generations/" + generationId + "/composition-identity.json").toString());
        spec.put("runtimeProfilePath", release.resolve("runtime/profile.json").toString());
        spec.put("serverConfigurationPath", release.resolve(LAUNCH + "current-base.conf").toString());
        spec.put("mapRoot", release.resolve(MAP).toString());
        spec.put("compositionIdentitySha256", hashBytes(json(identity)));
        spec.put("runtimeProfileSha256", sha(stage.resolve("runtime/profile.json")));
        for (String role : Arrays.asList("server", "client")) {
            Path mapProfile = stage.resolve(LAUNCH + "installed-" + role + ".json");
            validateMapProfile(read(mapProfile), role, stage.resolve(MAP), mapHash);
            spec.put(role + "MapProfileSha256", sha(mapProfile));
            spec.put(role + "CodeTreeSha256", treeFingerprint(stage.resolve("installed/" + role), false));
            spec.put(role + "MapProfilePath", release.resolve(LAUNCH + "installed-" + role + ".json").toString());
            spec.put(role + "CodeRoot", release.resolve("installed/" + role).toString());
            spec.put(role + "WorkingRoot", instance.resolve("working/" + role).toString());
            spec.put(role + "StateRoot", instance.resolve("state/" + role).toString());
            spec.put(role + "SideStateRoot", instance.resolve("state/" + role + "/side").toString());
        }
        spec.put("mapPackageFingerprintSha256", mapHash);
        Path configuration = stage.resolve(LAUNCH + "current-base.conf");
        validateConfiguration(configuration, profile, port);
        spec.put("serverConfigurationSha256", sha(configuration));
        spec.put("host", host); spec.put("gamePort", Long.valueOf(port));
        Map<String,Path> server = new TreeMap<String,Path>(serverSideSources);
        Map<String,Path> client = new TreeMap<String,Path>(clientSideSources);
        if (!server.keySet().equals(union(SERVER_REQUIRED, SERVER_OPTIONAL))
            || !client.keySet().equals(union(names("client.pem"), CLIENT_OPTIONAL)))
            throw failure("Side-state sources must explicitly bind every supported present or absent name.");
        Set<Path> sidePaths = new HashSet<Path>();
        List<Copy> copies = new ArrayList<Copy>();
        List<Path> absentSources = new ArrayList<Path>();
        for (String role : Arrays.asList("server", "client")) {
            Map<String,Path> sources = "server".equals(role) ? server : client;
            for (Map.Entry<String,Path> entry : sources.entrySet()) {
                if (entry.getValue() == null) throw failure("Side-state absence requires an explicit source path.");
                Path source = projected(entry.getValue());
                boolean required = "server".equals(role) ? SERVER_REQUIRED.contains(entry.getKey()) : "client.pem".equals(entry.getKey());
                if (!Files.exists(source, LinkOption.NOFOLLOW_LINKS)) {
                    if (required) throw failure("Required preserved side-state is absent.");
                    disjoint(source, instance); if (!stage.equals(release)) disjoint(source, release);
                    if (!sidePaths.add(source)) throw failure("Absent side-state paths alias.");
                    absentSources.add(source); continue;
                }
                regular(source);
                // The public key may be intentionally copied to both roles; no other input aliases.
                if (!sidePaths.add(source) && !("client".equals(role) && "client.pem".equals(entry.getKey())
                    && source.equals(server.get("client.pem")))) throw failure("Side-state inputs alias one another.");
                disjoint(source, instance);
                if (!stage.equals(release)) disjoint(source, release);
                long maximum = entry.getKey().endsWith(".pem") ? 65536L : 1048576L;
                if (Files.size(source) > maximum) throw failure("Side-state input exceeds its bounded size.");
                copies.add(new Copy(source, "state/" + role + "/side/" + entry.getKey(), fileRecord(source)));
            }
            spec.put(role + "PublicKeySha256", sha(sources.get("client.pem")));
        }
        if (!spec.get("serverPublicKeySha256").equals(spec.get("clientPublicKeySha256")))
            throw failure("Client trust key differs from the preserved server key.");
        validateKeyPair(server.get("server.pem"), server.get("client.pem"));
        Path database = stage.resolve("migration/output/state/current-base.db");
        copies.add(new Copy(database, "state/server/current_base.db", fileRecord(database)));
        Map<String,Object> generation = renderGeneration(spec);
        Map<String,byte[]> documents = new TreeMap<String,byte[]>();
        String generationPath = "generations/" + generationId + "/";
        documents.put(generationPath + "composition-identity.json", json(identity));
        documents.put(generationPath + "server-launch.json", json(generation.get("serverDescriptor")));
        documents.put(generationPath + "client-launch.json", json(generation.get("clientDescriptor")));
        documents.put("installation/active-launch.json", json(generation.get("activeSelection")));
        documents.put("installation/server.lock", new byte[0]); documents.put("installation/client.lock", new byte[0]);
        Plan result = new Plan(stage, instance, sourceTree, spec, copies, absentSources, documents);
        result.verifySources();
        return result;
    }

    /** Pure deterministic projection; no path access, live-state hash, or pointer/descriptor hash cycle. */
    static Map<String,Object> renderGeneration(Map<String,Object> input) throws WorldBuilderContractException {
        exact(input, SPEC_KEYS);
        Map<String,Object> spec = copy(input);
        String id = string(spec, "installationId"), generation = string(spec, "generationId");
        try { if (!UUID.fromString(id).toString().equals(id)) throw new IllegalArgumentException(); }
        catch (IllegalArgumentException bad) { throw failure("Installation identity must be a canonical UUID."); }
        if (!generation.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")) throw failure("Invalid immutable generation identity.");
        for (String key : SPEC_KEYS) if (key.endsWith("Root") || key.endsWith("Path")) lexical(string(spec, key));
        Path installation = lexical(string(spec, "installationRoot"));
        Path mapRoot = lexical(string(spec, "mapRoot"));
        List<Path> immutableRoots = Arrays.asList(mapRoot, lexical(string(spec, "serverCodeRoot")), lexical(string(spec, "clientCodeRoot")));
        disjoint(immutableRoots.get(0), immutableRoots.get(1)); disjoint(immutableRoots.get(0), immutableRoots.get(2)); disjoint(immutableRoots.get(1), immutableRoots.get(2));
        List<Path> mutableRoots = new ArrayList<Path>(); mutableRoots.add(installation);
        for (String role : Arrays.asList("server", "client")) {
            Path working = lexical(string(spec, role + "WorkingRoot")), state = lexical(string(spec, role + "StateRoot")), side = lexical(string(spec, role + "SideStateRoot"));
            for (Path root : Arrays.asList(working, state, side)) {
                for (Path immutable : immutableRoots) disjoint(root, immutable);
                for (Path previous : mutableRoots) disjoint(root, previous);
            }
            disjoint(working, state); disjoint(working, side);
            if (!side.startsWith(state)) disjoint(state, side);
            mutableRoots.add(working); mutableRoots.add(state);
            if (!side.startsWith(state)) mutableRoots.add(side);
        }
        for (String key : SPEC_KEYS) if (key.endsWith("Sha256")) requireHash(string(spec, key));
        if (!spec.get("serverPublicKeySha256").equals(spec.get("clientPublicKeySha256")))
            throw failure("Projected client trust key differs from the server public key.");
        for (String key : Arrays.asList("compositionIdentityPath", "runtimeProfilePath", "serverMapProfilePath", "clientMapProfilePath", "serverConfigurationPath")) {
            Path document = lexical(string(spec, key));
            for (Path mutable : mutableRoots) disjoint(document, mutable);
        }
        String host = string(spec, "host");
        long port = number(spec.get("gamePort"));
        if (!host.matches("[A-Za-z0-9][A-Za-z0-9.:-]{0,252}") || port < 1 || port > 65535)
            throw failure("Invalid normal installed endpoint.");
        Map<String,Object> result = new LinkedHashMap<String,Object>();
        for (String role : Arrays.asList("server", "client")) {
            Map<String,Object> descriptor = new LinkedHashMap<String,Object>();
            descriptor.put("schemaVersion", Long.valueOf(1)); descriptor.put("manifestType", "current-base-installed-launch");
            descriptor.put("role", role); descriptor.put("installationId", id);
            descriptor.put("compositionIdentity", binding(lexical(string(spec, "compositionIdentityPath")), string(spec, "compositionIdentitySha256")));
            descriptor.put("runtimeProfile", binding(lexical(string(spec, "runtimeProfilePath")), string(spec, "runtimeProfileSha256")));
            descriptor.put("installedMapProfile", binding(lexical(string(spec, role + "MapProfilePath")), string(spec, role + "MapProfileSha256")));
            descriptor.put("mapRoot", mapRoot.toString());
            descriptor.put("mapPackageFingerprintSha256", spec.get("mapPackageFingerprintSha256"));
            descriptor.put("codeRoot", spec.get(role + "CodeRoot"));
            descriptor.put("codeTreeSha256", spec.get(role + "CodeTreeSha256"));
            descriptor.put("workingRoot", spec.get(role + "WorkingRoot"));
            descriptor.put("stateRoot", spec.get(role + "StateRoot"));
            descriptor.put("sideStateRoot", spec.get(role + "SideStateRoot"));
            descriptor.put("installationRoot", installation.toString());
            descriptor.put("sessionRoot", installation.resolve("sessions/" + role).toString());
            descriptor.put("configuration", "server".equals(role)
                ? binding(lexical(string(spec, "serverConfigurationPath")), string(spec, "serverConfigurationSha256")) : new LinkedHashMap<String,Object>());
            Map<String,Object> endpoint = new LinkedHashMap<String,Object>(); endpoint.put("host", host); endpoint.put("gamePort", Long.valueOf(port));
            descriptor.put("endpoint", endpoint);
            descriptor.put("publicKey", binding(lexical(string(spec, role + "SideStateRoot")).resolve("client.pem"), string(spec, role + "PublicKeySha256")));
            result.put(role + "Descriptor", descriptor);
        }
        Map<String,Object> selection = new LinkedHashMap<String,Object>();
        selection.put("schemaVersion", Long.valueOf(1)); selection.put("manifestType", "current-base-installed-selection"); selection.put("installationId", id);
        selection.put("serverDescriptorSha256", hashBytes(json(result.get("serverDescriptor"))));
        selection.put("clientDescriptorSha256", hashBytes(json(result.get("clientDescriptor"))));
        result.put("activeSelection", selection);
        return result;
    }

    /** Writes only a wholly absent root. Partial failure is retained for journaled recovery, never force-cleaned. */
    static void materializeNew(Plan plan, Path newRoot) throws IOException, WorldBuilderContractException {
        Path root = projected(newRoot); absent(root); directory(root.getParent());
        disjoint(root, plan.stage);
        if (!root.equals(plan.finalRoot)) disjoint(root, plan.finalRoot);
        for (Copy copy : plan.copies) disjoint(root, copy.source);
        for (Path source : plan.absentSources) disjoint(root, source);
        plan.verifySources();
        Files.createDirectory(root, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        Object rootKey = Files.readAttributes(root, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).fileKey();
        if (rootKey == null) throw failure("New instance lacks stable filesystem identity.");
        for (String relative : plan.directories) {
            if (!relative.isEmpty()) {
                requireWriteParent(root, rootKey, root.resolve(relative));
                Files.createDirectory(root.resolve(relative), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            }
        }
        for (Copy copy : plan.copies) {
            requireRecord(copy.source, copy.record);
            Path target = root.resolve(copy.destination);
            requireWriteParent(root, rootKey, target);
            writeCopy(copy.source, target, number(copy.record.get("size")));
            requireRecord(target, outputRecord(copy.record));
        }
        for (Map.Entry<String,byte[]> document : plan.documents.entrySet()) {
            requireWriteParent(root, rootKey, root.resolve(document.getKey()));
            writeNew(root.resolve(document.getKey()), document.getValue());
        }
        WorldBuilderAdaptiveDurability.forceTree(root);
        verifyNew(plan, root);
        WorldBuilderAdaptiveDurability.forceDirectory(root.getParent());
    }

    /** Initial construction verification only: intentionally not a perpetual live-state validator. */
    static void verifyNew(Plan plan, Path root) throws IOException, WorldBuilderContractException {
        if (!inventory(directory(root), true).equals(plan.outputInventory)) throw failure("New instance readback differs from its complete construction inventory.");
        plan.verifySources();
    }

    static final class Plan {
        private final Path stage, finalRoot;
        private final List<Object> sourceInventory, outputInventory;
        private final Map<String,Object> specification;
        private final List<Copy> copies;
        private final List<Path> absentSources;
        private final Map<String,byte[]> documents;
        private final SortedSet<String> directories;
        private Plan(Path stage, Path finalRoot, List<Object> sourceInventory, Map<String,Object> specification,
            List<Copy> copies, List<Path> absentSources, Map<String,byte[]> documents) throws WorldBuilderContractException {
            this.stage = stage; this.finalRoot = finalRoot; this.sourceInventory = sourceInventory;
            this.specification = copy(specification); this.copies = new ArrayList<Copy>(copies); this.documents = documents;
            this.absentSources = new ArrayList<Path>(absentSources);
            directories = new TreeSet<String>(); directories.add("");
            for (String path : Arrays.asList("installation/sessions/server", "installation/sessions/client", "state/server/side", "state/client/side", "working/server", "working/client")) addDirectories(path, directories);
            Map<String,Map<String,Object>> files = new TreeMap<String,Map<String,Object>>();
            for (Copy item : copies) { addParents(item.destination, directories); files.put(item.destination, outputRecord(item.record)); }
            for (Map.Entry<String,byte[]> item : documents.entrySet()) {
                addParents(item.getKey(), directories);
                Map<String,Object> row = new LinkedHashMap<String,Object>(); row.put("type", "file"); row.put("mode", "0600");
                row.put("size", Long.valueOf(item.getValue().length)); row.put("sha256", hashBytes(item.getValue())); files.put(item.getKey(), row);
            }
            outputInventory = new ArrayList<Object>();
            for (String path : directories) outputInventory.add(treeRow(path, directoryRecord("0700")));
            for (Map.Entry<String,Map<String,Object>> entry : files.entrySet()) outputInventory.add(treeRow(entry.getKey(), entry.getValue()));
            sortInventory(outputInventory);
        }
        Map<String,Object> document() throws WorldBuilderContractException {
            Map<String,Object> result = new LinkedHashMap<String,Object>();
            result.put("schemaVersion", Long.valueOf(1)); result.put("manifestType", "world-builder-current-instance-construction-plan");
            result.put("specification", copy(specification)); result.put("sourceReleaseRoot", stage.toString());
            result.put("sourceReleaseInventory", sourceInventory); result.put("outputInventory", outputInventory);
            List<Object> rows = new ArrayList<Object>();
            for (Copy item : copies) {
                Map<String,Object> row = new LinkedHashMap<String,Object>(); row.put("sourcePath", item.source.toString()); row.put("relativePath", item.destination); row.put("source", item.record); rows.add(row);
            }
            result.put("copies", rows); result.put("generation", renderGeneration(specification));
            List<Object> absent = new ArrayList<Object>(); for (Path path : absentSources) absent.add(path.toString());
            result.put("absentSideStateSources", absent);
            result.put("failurePolicy", "retain-new-partial-output-for-journaled-recovery");
            result.put("planFingerprintSha256", hashBytes(WorldBuilderJsonDocuments.canonical(result).getBytes(StandardCharsets.UTF_8)));
            return copy(result);
        }
        private void verifySources() throws IOException, WorldBuilderContractException {
            if (!sourceInventory.equals(inventory(stage, false))) throw failure("Reviewed release changed during instance construction.");
            for (Copy item : copies) requireRecord(item.source, item.record);
            for (Path path : absentSources) { projected(path); if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) throw failure("Reviewed absent side-state appeared during construction."); }
        }
    }

    private static final class Copy {
        final Path source; final String destination; final Map<String,Object> record;
        Copy(Path source, String destination, Map<String,Object> record) { this.source = source; this.destination = destination; this.record = record; }
    }

    private static void validateKeyPair(Path privatePath, Path publicPath) throws IOException, WorldBuilderContractException {
        try {
            java.security.PrivateKey privateKey = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(pem(privatePath, "PRIVATE KEY")));
            java.security.PublicKey publicKey = KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(pem(publicPath, "PUBLIC KEY")));
            Signature signer = Signature.getInstance("SHA256withRSA");
            byte[] challenge = "current-base-preserved-key-pair-validation-v1".getBytes(StandardCharsets.US_ASCII);
            signer.initSign(privateKey); signer.update(challenge); byte[] signature = signer.sign();
            signer.initVerify(publicKey); signer.update(challenge);
            if (!signer.verify(signature)) throw failure("Preserved RSA key pair does not match.");
        } catch (java.security.GeneralSecurityException | IllegalArgumentException invalid) { throw failure("Preserved RSA key pair is malformed or mismatched."); }
    }
    private static byte[] pem(Path path, String label) throws IOException, WorldBuilderContractException {
        if (Files.size(regular(path)) > 65536) throw failure("Key input exceeds size bound.");
        String text = new String(Files.readAllBytes(path), StandardCharsets.US_ASCII).trim();
        String begin = "-----BEGIN " + label + "-----", end = "-----END " + label + "-----";
        if (!text.startsWith(begin) || !text.endsWith(end)) throw failure("Preserved key uses an unsupported PEM format.");
        return Base64.getDecoder().decode(text.substring(begin.length(), text.length() - end.length()).replaceAll("\\s", ""));
    }
    private static void validateConfiguration(Path path, Map<String,Object> profile, int port) throws IOException, WorldBuilderContractException {
        if (Files.size(regular(path)) > 1048576) throw failure("Server configuration exceeds its bound.");
        Map<String,String> values = new HashMap<String,String>();
        for (String raw : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            String line = raw.split("#", 2)[0].trim(); if (line.isEmpty()) continue;
            int colon = line.indexOf(':'); if (colon < 1) throw failure("Malformed server configuration.");
            String key = line.substring(0, colon).trim(), value = line.substring(colon + 1).trim();
            if (!value.isEmpty() && values.put(key, value) != null) throw failure("Repeated server configuration key.");
        }
        Map<String,Object> excluded = object(object(profile.get("advancedExclusions")).get("configuration"));
        for (String key : excluded.keySet()) if (!Boolean.FALSE.equals(excluded.get(key)) || !"false".equals(values.get(key))) throw failure("Server configuration enables an excluded setting.");
        if (!"sqlite".equals(values.get("db_type")) || !"current_base".equals(values.get("db_name"))
            || !"false".equals(values.get("allow_in_game_world_editor")) || !"false".equals(values.get("want_feature_websockets"))
            || !Integer.toString(port).equals(values.get("server_port"))) throw failure("Server configuration does not select normal Current Base state and endpoint.");
    }
    private static void validateMapProfile(Map<String,Object> profile, String role, Path root, String fingerprint) throws IOException, WorldBuilderContractException {
        exact(profile, "schemaVersion", "manifestType", "active", "packageId", "packageVersion", "packageFingerprintSha256", "manifestSha256", "packageRelativePath");
        Map<String,Object> manifest = read(root.resolve("manifest.json"));
        if (number(profile.get("schemaVersion")) != 1 || !Boolean.TRUE.equals(profile.get("active"))
            || !("world-builder-installed-" + role + "-profile").equals(profile.get("manifestType"))
            || !fingerprint.equals(profile.get("packageFingerprintSha256")) || !sha(root.resolve("manifest.json")).equals(profile.get("manifestSha256"))
            || !Objects.equals(manifest.get("packageId"), profile.get("packageId")) || !Objects.equals(manifest.get("packageVersion"), profile.get("packageVersion")))
            throw failure("Installed map profile differs from the reviewed package.");
    }
    private static void verifyRole(Map<String,Object> identity, String role, Path file) throws IOException, WorldBuilderContractException {
        int count = 0;
        for (Object raw : array(identity.get("bundleInventory"))) {
            Map<String,Object> row = object(raw);
            if (role.equals(row.get("role"))) { count++; if (!sha(file).equals(row.get("sha256"))) throw failure("Provider artifact binding differs."); }
        }
        if (count != 1) throw failure("Provider artifact role is missing or ambiguous.");
    }
    static String treeFingerprint(Path root, boolean map) throws IOException, WorldBuilderContractException {
        List<Object> rows = inventory(directory(root), false); StringBuilder text = new StringBuilder();
        long total = 0; int count = 0;
        if (rows.size() > 30000) throw failure("Runtime input tree exceeds its entry bound.");
        for (Object raw : rows) {
            Map<String,Object> row = object(raw); if (!"file".equals(row.get("type"))) continue;
            count++; total += number(row.get("size"));
            text.append(row.get("relativePath")).append('\0');
            if (map) text.append(row.get("size")).append('\0');
            text.append(row.get("sha256")).append(map ? '\n' : '\0');
        }
        if (count > 20000 || total > 1073741824L) throw failure("Runtime input tree exceeds its byte/file bound.");
        return hashBytes(text.toString().getBytes(StandardCharsets.UTF_8));
    }
    private static List<Object> inventory(final Path root, final boolean privateModes) throws IOException, WorldBuilderContractException {
        final List<Object> result = new ArrayList<Object>(); final long[] total = {0};
        Files.walkFileTree(directory(root), new SimpleFileVisitor<Path>() {
            private void add(Path path, Map<String,Object> value) throws IOException {
                if (result.size() >= 100000) throw new IOException("Instance input inventory exceeds entry bound");
                result.add(treeRow(root.relativize(path).toString().replace('\\', '/'), value));
            }
            @Override public FileVisitResult preVisitDirectory(Path path, BasicFileAttributes attrs) throws IOException {
                try {
                    directory(path); String mode = mode(path);
                    if (privateModes && !"0700".equals(mode)) throw failure("Instance directory is not private.");
                    add(path, directoryRecord(mode)); return FileVisitResult.CONTINUE;
                } catch (WorldBuilderContractException unsafe) { throw new IOException(unsafe); }
            }
            @Override public FileVisitResult visitFile(Path path, BasicFileAttributes attrs) throws IOException {
                try {
                    Map<String,Object> row = fileRecord(path); total[0] += number(row.get("size"));
                    if (total[0] > MAX_TREE_BYTES || privateModes && !"0600".equals(row.get("mode"))) throw failure("Instance input is oversized or nonprivate.");
                    add(path, row); return FileVisitResult.CONTINUE;
                } catch (WorldBuilderContractException unsafe) { throw new IOException(unsafe); }
            }
        });
        sortInventory(result); return result;
    }
    private static Map<String,Object> fileRecord(Path path) throws IOException, WorldBuilderContractException {
        regular(path); Map<String,Object> result = new LinkedHashMap<String,Object>();
        if (Files.size(path) > 1073741824L) throw failure("Source file exceeds the one GiB read bound.");
        result.put("type", "file"); result.put("mode", mode(path)); result.put("size", Long.valueOf(Files.size(path))); result.put("sha256", WorldBuilderHashes.sha256(path)); return result;
    }
    private static Map<String,Object> outputRecord(Map<String,Object> source) { Map<String,Object> result = new LinkedHashMap<String,Object>(source); result.put("mode", "0600"); return result; }
    private static Map<String,Object> directoryRecord(String mode) { Map<String,Object> result = new LinkedHashMap<String,Object>(); result.put("type", "directory"); result.put("mode", mode); return result; }
    private static Map<String,Object> treeRow(String path, Map<String,Object> value) { Map<String,Object> result = new LinkedHashMap<String,Object>(); result.put("relativePath", path); result.putAll(value); return result; }
    private static void sortInventory(List<Object> rows) { Collections.sort(rows, (a,b) -> ((String)((Map<?,?>)a).get("relativePath")).compareTo((String)((Map<?,?>)b).get("relativePath"))); }
    private static void requireRecord(Path path, Map<String,Object> expected) throws IOException, WorldBuilderContractException { if (!fileRecord(path).equals(expected)) throw failure("Construction source/output changed from its reviewed inventory."); }
    private static void addParents(String file, Set<String> directories) { int slash = file.lastIndexOf('/'); if (slash > 0) addDirectories(file.substring(0, slash), directories); }
    private static void addDirectories(String path, Set<String> directories) { directories.add(path); int slash = path.lastIndexOf('/'); if (slash > 0) addDirectories(path.substring(0, slash), directories); }
    private static Path lexical(String value) throws WorldBuilderContractException {
        try { Path path = Paths.get(value); if (!path.isAbsolute() || !path.normalize().equals(path)) throw failure("Instance paths must be canonical absolute projections."); return path; }
        catch (InvalidPathException bad) { throw failure("Invalid instance path."); }
    }
    private static Path projected(Path path) throws IOException, WorldBuilderContractException {
        lexical(path.toString()); Path ancestor = path;
        while (!Files.exists(ancestor, LinkOption.NOFOLLOW_LINKS)) { ancestor = ancestor.getParent(); if (ancestor == null) throw failure("Path has no existing canonical ancestor."); }
        if (!ancestor.toRealPath().equals(ancestor) || Files.isSymbolicLink(ancestor)) throw failure("Projected path has an aliased ancestor.");
        return path;
    }
    private static Path directory(Path path) throws IOException, WorldBuilderContractException { projected(path); if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) throw failure("Required directory is absent or unsafe."); return path; }
    private static Path regular(Path path) throws IOException, WorldBuilderContractException {
        projected(path);
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || !path.equals(path.toRealPath())
            || ((Number)Files.getAttribute(path, "unix:nlink", LinkOption.NOFOLLOW_LINKS)).longValue() != 1) throw failure("Input must be a canonical singly-linked regular file.");
        return path;
    }
    private static String mode(Path path) throws IOException { return String.format(Locale.ROOT, "%04o", ((Number)Files.getAttribute(path, "unix:mode", LinkOption.NOFOLLOW_LINKS)).intValue() & 07777); }
    private static void absent(Path path) throws WorldBuilderContractException { if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) throw failure("New instance destination already exists."); }
    private static void requireWriteParent(Path root, Object rootKey, Path target) throws IOException, WorldBuilderContractException {
        directory(root);
        if (!rootKey.equals(Files.readAttributes(root, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).fileKey()))
            throw failure("New instance root identity changed during construction.");
        Path parent = directory(target.getParent());
        if (!parent.startsWith(root) || !"0700".equals(mode(parent))) throw failure("New instance output parent is not private and contained.");
        absent(target);
    }
    private static void disjoint(Path a, Path b) throws WorldBuilderContractException { if (a.startsWith(b) || b.startsWith(a)) throw failure("Instance inputs and outputs overlap."); }
    private static void writeNew(Path path, byte[] bytes) throws IOException {
        Files.createFile(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        Files.write(path, bytes, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
    }
    private static void writeCopy(Path source, Path destination, long expected) throws IOException, WorldBuilderContractException {
        Files.createFile(destination, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        long total = 0;
        try (InputStream input = Files.newInputStream(source, LinkOption.NOFOLLOW_LINKS); OutputStream output = Files.newOutputStream(destination, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            byte[] buffer = new byte[65536]; int count;
            while ((count = input.read(buffer)) != -1) { total += count; if (total > expected) throw failure("Copy source exceeded its reviewed length."); output.write(buffer, 0, count); }
        }
        if (total != expected) throw failure("Copy source ended before its reviewed length.");
    }
    private static String sha(Path path) throws IOException, WorldBuilderContractException { return WorldBuilderHashes.sha256(regular(path)); }
    private static String hashBytes(byte[] bytes) { return WorldBuilderHashes.sha256(bytes); }
    private static byte[] json(Object value) { return WorldBuilderJsonDocuments.pretty(value).getBytes(StandardCharsets.UTF_8); }
    private static Map<String,Object> binding(Path path, String hash) { Map<String,Object> value = new LinkedHashMap<String,Object>(); value.put("path", path.toString()); value.put("sha256", hash); return value; }
    private static Set<String> names(String... values) { return new HashSet<String>(Arrays.asList(values)); }
    private static Set<String> union(Set<String> a, Set<String> b) { Set<String> result = new HashSet<String>(a); result.addAll(b); return result; }
    private static void requireHash(String hash) throws WorldBuilderContractException { if (!hash.matches("[0-9a-f]{64}")) throw failure("Invalid instance digest."); }
    private static long number(Object value) throws WorldBuilderContractException { if (!(value instanceof Long) && !(value instanceof Integer)) throw failure("Expected an integer."); return ((Number)value).longValue(); }
    private static String string(Map<String,Object> value, String key) throws WorldBuilderContractException { if (!(value.get(key) instanceof String)) throw failure("Expected a string field."); return (String)value.get(key); }
    @SuppressWarnings("unchecked") private static Map<String,Object> object(Object value) throws WorldBuilderContractException { if (!(value instanceof Map)) throw failure("Expected an object."); return (Map<String,Object>)value; }
    @SuppressWarnings("unchecked") private static List<Object> array(Object value) throws WorldBuilderContractException { if (!(value instanceof List)) throw failure("Expected an array."); return (List<Object>)value; }
    private static void exact(Map<String,Object> value, String... keys) throws WorldBuilderContractException { if (!value.keySet().equals(names(keys))) throw failure("Closed instance fields differ."); }
    private static Map<String,Object> copy(Map<String,Object> value) throws WorldBuilderContractException {
        try { return WorldBuilderJsonDocuments.readObject(json(value), OP); }
        catch (WorldBuilderDiscoveryException bad) { throw failure("Invalid instance document."); }
    }
    private static Map<String,Object> read(Path path) throws IOException, WorldBuilderContractException {
        regular(path); if (Files.size(path) > 1048576) throw failure("Bound JSON exceeds its size limit.");
        try { return WorldBuilderJsonDocuments.readObject(path); } catch (WorldBuilderDiscoveryException bad) { throw failure("Bound JSON is malformed."); }
    }
    private static WorldBuilderContractException failure(String message) { return new WorldBuilderContractException(WorldBuilderErrorCodes.TARGET_DRIFT, OP, "instance", false, message, "Preserve reviewed inputs and partial outputs; request a fresh plan, never force replacement."); }
}
