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
        List<Absent> absentSources = new ArrayList<Absent>();
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
                    absentSources.add(new Absent(source, "state/" + role + "/side/" + entry.getKey())); continue;
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
        Plan result = new Plan(stage, release, instance, sourceTree, spec, copies, absentSources, documents);
        result.verifySources();
        Map<String,Object> serialized = result.document();
        validateInitialOutputPlan(serialized, string(serialized, "planFingerprintSha256"));
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
        materializeNew(plan, newRoot, null);
    }

    /** Initial production construction publishes the durable startup guard before launch metadata. */
    static void materializeGuarded(Plan plan, Path newRoot, WorldBuilderCurrentRuntimeCutover.Plan cutover)
        throws IOException, WorldBuilderContractException {
        if (!newRoot.equals(cutover.target.resolve(".world-builder/current-runtime/instance"))
            || !newRoot.equals(plan.finalRoot)) throw failure("Initial guard belongs to another projected instance.");
        materializeNew(plan, newRoot, cutover.guard);
    }

    private static void materializeNew(Plan plan, Path newRoot, byte[] guard) throws IOException, WorldBuilderContractException {
        Path root = projected(newRoot); absent(root); directory(root.getParent());
        disjoint(root, plan.stage);
        disjoint(root, plan.finalRelease);
        if (!root.equals(plan.finalRoot)) disjoint(root, plan.finalRoot);
        for (Copy copy : plan.copies) disjoint(root, copy.source);
        for (Absent source : plan.absentSources) disjoint(root, source.source);
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
        if (guard != null) {
            Path guardPath = root.resolve("installation/pending-cutover.json");
            requireWriteParent(root, rootKey, guardPath);
            writeNew(guardPath, guard);
            WorldBuilderAdaptiveDurability.forceFile(guardPath);
            WorldBuilderAdaptiveDurability.forceDirectory(guardPath.getParent());
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
        verifyConstructed(plan, root, guard);
        WorldBuilderAdaptiveDurability.forceDirectory(root.getParent());
    }

    /** Initial construction verification only: intentionally not a perpetual live-state validator. */
    static void verifyNew(Plan plan, Path root) throws IOException, WorldBuilderContractException {
        verifyConstructed(plan, root, null);
    }

    static void verifyGuardedNew(Plan plan, Path root, WorldBuilderCurrentRuntimeCutover.Plan cutover)
        throws IOException, WorldBuilderContractException {
        if (!root.equals(cutover.target.resolve(".world-builder/current-runtime/instance"))) throw failure("Guard verification selects another instance.");
        verifyConstructed(plan, root, cutover.guard);
    }

    private static void verifyConstructed(Plan plan, Path root, byte[] guard) throws IOException, WorldBuilderContractException {
        List<Object> expected = new ArrayList<Object>(plan.outputInventory);
        if (guard != null) { expected.add(treeRow("installation/pending-cutover.json", bytesRecord(guard))); sortInventory(expected); }
        if (!inventory(directory(root), true).equals(expected)) throw failure("New instance readback differs from its complete construction inventory.");
        plan.verifySources();
    }

    /**
     * Reconstructs READ-ONLY initial-output evidence after a process restart. The fingerprint must
     * come from the transaction's independently trusted confirmed journal, not from this document.
     * No source paths are opened. This token grants neither deletion nor restoration/live-state reuse.
     */
    static InitialOutputPlan validateInitialOutputPlan(Map<String,Object> input, String confirmedFingerprint)
        throws WorldBuilderContractException {
        exact(input, "schemaVersion", "manifestType", "specification", "sourceReleaseRoot", "projectedReleaseRoot",
            "projectedInstanceRoot", "sourceReleaseInventory", "outputInventory", "copies", "generation",
            "absentSideStateSources", "failurePolicy", "planFingerprintSha256");
        requireHash(confirmedFingerprint);
        if (array(input.get("sourceReleaseInventory")).size() > 100000 || array(input.get("outputInventory")).size() > 128
            || array(input.get("copies")).size() > 13 || array(input.get("absentSideStateSources")).size() > 6
            || json(input).length > WorldBuilderContractLimits.MAX_JSON_BYTES) throw failure("Serialized construction plan exceeds its bound.");
        Map<String,Object> document = copy(input);
        if (number(document.get("schemaVersion")) != 1
            || !"world-builder-current-instance-construction-plan".equals(document.get("manifestType"))
            || !"retain-new-partial-output-for-journaled-recovery".equals(document.get("failurePolicy")))
            throw failure("Unsupported initial construction evidence identity.");
        String claimed = string(document, "planFingerprintSha256");
        Map<String,Object> unsigned = new LinkedHashMap<String,Object>(document); unsigned.remove("planFingerprintSha256");
        if (!confirmedFingerprint.equals(claimed) || !claimed.equals(hashBytes(WorldBuilderJsonDocuments.canonical(unsigned).getBytes(StandardCharsets.UTF_8))))
            throw failure("Construction document differs from the independently confirmed plan.");
        Map<String,Object> spec = object(document.get("specification"));
        Map<String,Object> generation = renderGeneration(spec);
        if (!generation.equals(object(document.get("generation")))) throw failure("Serialized launch pair differs from its pure projection.");
        Path stage = lexical(string(document, "sourceReleaseRoot"));
        Path release = lexical(string(document, "projectedReleaseRoot")), instance = lexical(string(document, "projectedInstanceRoot"));
        disjoint(stage, instance); disjoint(release, instance); if (!stage.equals(release)) disjoint(stage, release);
        requireInitialProjection(spec, release, instance);
        Map<String,Map<String,Object>> source = validateInventory(array(document.get("sourceReleaseInventory")), false);
        Map<String,Map<String,Object>> output = validateInventory(array(document.get("outputInventory")), true);
        for (String role : Arrays.asList("server", "client")) {
            requireSourceHash(source, LAUNCH + "installed-" + role + ".json", string(spec, role + "MapProfileSha256"));
            if (!string(spec, role + "CodeTreeSha256").equals(inventoryFingerprint(source, "installed/" + role + "/", false)))
                throw failure("Projected code hash differs from the sealed source inventory.");
        }
        requireSourceHash(source, "runtime/profile.json", string(spec, "runtimeProfileSha256"));
        requireSourceHash(source, LAUNCH + "current-base.conf", string(spec, "serverConfigurationSha256"));
        if (!string(spec, "mapPackageFingerprintSha256").equals(inventoryFingerprint(source, MAP + "/", true)))
            throw failure("Projected map hash differs from the sealed source inventory.");
        Set<String> required = names("state/server/current_base.db", "state/client/side/client.pem");
        for (String name : SERVER_REQUIRED) required.add("state/server/side/" + name);
        Set<String> optional = new HashSet<String>();
        for (String name : SERVER_OPTIONAL) optional.add("state/server/side/" + name);
        for (String name : CLIENT_OPTIONAL) optional.add("state/client/side/" + name);
        Map<String,Map<String,Object>> expectedFiles = new TreeMap<String,Map<String,Object>>();
        Map<Path,String> sourcePaths = new HashMap<Path,String>();
        for (Object raw : array(document.get("copies"))) {
            Map<String,Object> row = object(raw); exact(row, "sourcePath", "relativePath", "source");
            String relative = portable(string(row, "relativePath"));
            if (!required.contains(relative) && !optional.contains(relative)) throw failure("Construction copy selects an unsupported output.");
            Path sourcePath = lexical(string(row, "sourcePath")); disjoint(sourcePath, instance);
            if (!stage.equals(release)) disjoint(sourcePath, release);
            String prior = sourcePaths.put(sourcePath, relative);
            if (prior != null && !(names(prior, relative).equals(names("state/server/side/client.pem", "state/client/side/client.pem"))))
                throw failure("Serialized construction sources alias.");
            Map<String,Object> record = object(row.get("source")); validateFileRecord(record);
            if (expectedFiles.put(relative, outputRecord(record)) != null) throw failure("Repeated construction destination.");
            if (relative.equals("state/server/current_base.db")) {
                String database = "migration/output/state/current-base.db";
                if (!sourcePath.equals(stage.resolve(database)) || !record.equals(source.get(database))) throw failure("Initial database copy is not the sealed migration output.");
            } else {
                long maximum = relative.endsWith(".pem") ? 65536L : 1048576L;
                if (number(record.get("size")) > maximum) throw failure("Side-state copy exceeds its bound.");
            }
        }
        if (!expectedFiles.keySet().containsAll(required)) throw failure("Initial construction omits required state.");
        Set<String> absence = new HashSet<String>();
        for (Object raw : array(document.get("absentSideStateSources"))) {
            Map<String,Object> row = object(raw); exact(row, "sourcePath", "relativePath");
            String relative = portable(string(row, "relativePath"));
            Path sourcePath = lexical(string(row, "sourcePath")); disjoint(sourcePath, instance);
            if (!stage.equals(release)) disjoint(sourcePath, release);
            if (!optional.contains(relative) || expectedFiles.containsKey(relative) || !absence.add(relative)
                || sourcePaths.put(sourcePath, relative) != null) throw failure("Known-absent side-state is duplicated, aliased, or unsupported.");
        }
        Set<String> dispositions = new HashSet<String>(expectedFiles.keySet()); dispositions.addAll(absence);
        if (!dispositions.equals(union(required, optional))) throw failure("Side-state presence/absence is incomplete.");
        String prefix = "generations/" + string(spec, "generationId") + "/";
        Map<String,Object> identity = output.get(prefix + "composition-identity.json");
        if (identity == null || !"file".equals(identity.get("type")) || number(identity.get("size")) < 2
            || number(identity.get("size")) > 1048576 || !spec.get("compositionIdentitySha256").equals(identity.get("sha256")))
            throw failure("Generated composition identity is not bound to its initial output.");
        expectedFiles.put(prefix + "composition-identity.json", identity);
        for (String role : Arrays.asList("server", "client")) {
            if (!expectedFiles.get("state/" + role + "/side/client.pem").get("sha256").equals(spec.get(role + "PublicKeySha256")))
                throw failure("Initial trust copy differs from its launch binding.");
            expectedFiles.put(prefix + role + "-launch.json", bytesRecord(json(generation.get(role + "Descriptor"))));
            expectedFiles.put("installation/" + role + ".lock", bytesRecord(new byte[0]));
        }
        expectedFiles.put("installation/active-launch.json", bytesRecord(json(generation.get("activeSelection"))));
        Map<String,Map<String,Object>> expected = new TreeMap<String,Map<String,Object>>(expectedFiles);
        Set<String> directories = names("");
        for (String path : Arrays.asList("installation/sessions/server", "installation/sessions/client", "state/server/side", "state/client/side", "working/server", "working/client")) addDirectories(path, directories);
        for (String path : expectedFiles.keySet()) addParents(path, directories);
        for (String path : directories) if (expected.put(path, directoryRecord("0700")) != null) throw failure("Output file collides with a directory.");
        if (!expected.equals(output)) throw failure("Serialized output inventory differs from the exact initial topology and bytes.");
        return new InitialOutputPlan(array(document.get("outputInventory")), stage, release, instance);
    }

    /** Read-only; any gameplay/extra session output makes the initial seal fail. Never cleans up. */
    static void verifyInitialOutputs(InitialOutputPlan plan, Path root) throws IOException, WorldBuilderContractException {
        Path actual = directory(root); disjoint(actual, plan.stage); disjoint(actual, plan.release);
        if (!inventory(actual, true).equals(plan.outputInventory)) throw failure("Initial instance output has drifted; this evidence cannot authorize live-state restoration or cleanup.");
    }

    /** Detached initial evidence only, behind the exact journaled guard. Never authorizes cleanup itself. */
    static void verifyGuardedInitialOutputs(InitialOutputPlan plan, Path root, WorldBuilderCurrentRuntimeCutover.Plan cutover)
        throws IOException, WorldBuilderContractException {
        verifyInitialCutoverOutputs(plan, root, cutover, true);
    }

    /** Exact initial inventory after metadata rollback; any gameplay or partial construction refuses. */
    static void verifyRolledBackInitialOutputs(InitialOutputPlan plan, Path root, WorldBuilderCurrentRuntimeCutover.Plan cutover)
        throws IOException, WorldBuilderContractException {
        verifyInitialCutoverOutputs(plan, root, cutover, false);
    }

    private static void verifyInitialCutoverOutputs(InitialOutputPlan plan, Path root,
        WorldBuilderCurrentRuntimeCutover.Plan cutover, boolean guarded) throws IOException, WorldBuilderContractException {
        Path actual = directory(root); disjoint(actual, plan.stage); disjoint(actual, plan.release);
        if (!actual.equals(plan.instance) || !actual.equals(cutover.target.resolve(".world-builder/current-runtime/instance"))
            || cutover.beforeSelection != null || cutover.beforeLedger != null)
            throw failure("Initial recovery evidence does not select this absent predecessor installation.");
        List<Object> expected = new ArrayList<Object>(plan.outputInventory);
        Object selection = treeRow("installation/active-launch.json", bytesRecord(cutover.afterSelection));
        if (!expected.contains(selection)) throw failure("Initial recovery selection differs from its construction evidence.");
        if (guarded) expected.add(treeRow("installation/pending-cutover.json", bytesRecord(cutover.guard)));
        else expected.remove(selection);
        sortInventory(expected);
        if (!inventory(actual, true).equals(expected))
            throw failure("Initial recovery output differs from its exact phase inventory; retain it for recovery.");
    }

    static final class InitialOutputPlan {
        private final List<Object> outputInventory;
        private final Path stage, release, instance;
        private InitialOutputPlan(List<Object> outputInventory, Path stage, Path release, Path instance) { this.outputInventory = outputInventory; this.stage = stage; this.release = release; this.instance = instance; }
    }

    private static void requireInitialProjection(Map<String,Object> spec, Path release, Path instance) throws WorldBuilderContractException {
        Map<String,Path> paths = new LinkedHashMap<String,Path>();
        paths.put("installationRoot", instance.resolve("installation"));
        paths.put("compositionIdentityPath", instance.resolve("generations/" + string(spec, "generationId") + "/composition-identity.json"));
        paths.put("runtimeProfilePath", release.resolve("runtime/profile.json")); paths.put("serverConfigurationPath", release.resolve(LAUNCH + "current-base.conf")); paths.put("mapRoot", release.resolve(MAP));
        for (String role : Arrays.asList("server", "client")) {
            paths.put(role + "MapProfilePath", release.resolve(LAUNCH + "installed-" + role + ".json"));
            paths.put(role + "CodeRoot", release.resolve("installed/" + role)); paths.put(role + "WorkingRoot", instance.resolve("working/" + role));
            paths.put(role + "StateRoot", instance.resolve("state/" + role)); paths.put(role + "SideStateRoot", instance.resolve("state/" + role + "/side"));
        }
        for (Map.Entry<String,Path> entry : paths.entrySet()) if (!entry.getValue().toString().equals(spec.get(entry.getKey()))) throw failure("Initial descriptor path differs from the fixed projected topology.");
    }

    private static Map<String,Map<String,Object>> validateInventory(List<Object> rows, boolean outputs) throws WorldBuilderContractException {
        Map<String,Map<String,Object>> result = new TreeMap<String,Map<String,Object>>(); Set<String> folded = new HashSet<String>();
        String previous = null; long total = 0;
        for (Object raw : rows) {
            Map<String,Object> row = object(raw); String path = string(row, "relativePath");
            if (!path.isEmpty()) portable(path);
            if (previous != null && previous.compareTo(path) >= 0 || !folded.add(path.toLowerCase(Locale.ROOT))) throw failure("Inventory paths are repeated, aliased, or unsorted.");
            previous = path; Map<String,Object> record = new LinkedHashMap<String,Object>(row); record.remove("relativePath");
            if ("directory".equals(record.get("type"))) {
                exact(record, "type", "mode"); if (!string(record, "mode").matches("[0-7]{4}")) throw failure("Invalid directory mode.");
                if (outputs && !"0700".equals(record.get("mode"))) throw failure("Initial output directory mode differs.");
            } else {
                validateFileRecord(record); total += number(record.get("size"));
                if (path.isEmpty() || outputs && !"0600".equals(record.get("mode"))) throw failure("Invalid initial output file.");
            }
            if (total > MAX_TREE_BYTES) throw failure("Inventory exceeds total byte bound.");
            result.put(path, record);
        }
        if (!result.containsKey("") || !"directory".equals(result.get("").get("type"))) throw failure("Inventory omits its directory root.");
        for (String path : result.keySet()) if (!path.isEmpty()) {
            int slash = path.lastIndexOf('/'); String parent = slash < 0 ? "" : path.substring(0, slash);
            if (!result.containsKey(parent) || !"directory".equals(result.get(parent).get("type"))) throw failure("Inventory path lacks its exact directory parent.");
        }
        return result;
    }
    private static void validateFileRecord(Map<String,Object> record) throws WorldBuilderContractException {
        exact(record, "type", "mode", "size", "sha256");
        if (!"file".equals(record.get("type")) || !string(record, "mode").matches("[0-7]{4}")
            || number(record.get("size")) < 0 || number(record.get("size")) > 1073741824L) throw failure("Invalid bounded regular-file record.");
        requireHash(string(record, "sha256"));
    }
    private static String portable(String path) throws WorldBuilderContractException { return WorldBuilderPortablePath.require(path, OP); }
    private static void requireSourceHash(Map<String,Map<String,Object>> source, String path, String hash) throws WorldBuilderContractException {
        if (!source.containsKey(path) || !"file".equals(source.get(path).get("type")) || !hash.equals(source.get(path).get("sha256"))) throw failure("Bound source document differs from its sealed inventory.");
    }
    private static String inventoryFingerprint(Map<String,Map<String,Object>> rows, String prefix, boolean map) throws WorldBuilderContractException {
        StringBuilder text = new StringBuilder(); int count = 0; long total = 0;
        for (Map.Entry<String,Map<String,Object>> entry : rows.entrySet()) if (entry.getKey().startsWith(prefix) && "file".equals(entry.getValue().get("type"))) {
            count++; total += number(entry.getValue().get("size")); text.append(entry.getKey().substring(prefix.length())).append('\0');
            if (map) text.append(entry.getValue().get("size")).append('\0'); text.append(entry.getValue().get("sha256")).append(map ? '\n' : '\0');
        }
        if (count == 0 || count > 20000 || total > 1073741824L) throw failure("Sealed runtime input tree exceeds its bound.");
        return hashBytes(text.toString().getBytes(StandardCharsets.UTF_8));
    }
    private static Map<String,Object> bytesRecord(byte[] bytes) { Map<String,Object> result = new LinkedHashMap<String,Object>(); result.put("type", "file"); result.put("mode", "0600"); result.put("size", Long.valueOf(bytes.length)); result.put("sha256", hashBytes(bytes)); return result; }

    static final class Plan {
        private final Path stage, finalRelease, finalRoot;
        private final List<Object> sourceInventory, outputInventory;
        private final Map<String,Object> specification;
        private final List<Copy> copies;
        private final List<Absent> absentSources;
        private final Map<String,byte[]> documents;
        private final SortedSet<String> directories;
        private Plan(Path stage, Path finalRelease, Path finalRoot, List<Object> sourceInventory, Map<String,Object> specification,
            List<Copy> copies, List<Absent> absentSources, Map<String,byte[]> documents) throws WorldBuilderContractException {
            this.stage = stage; this.finalRelease = finalRelease; this.finalRoot = finalRoot; this.sourceInventory = sourceInventory;
            this.specification = copy(specification); this.copies = new ArrayList<Copy>(copies); this.documents = documents;
            this.absentSources = new ArrayList<Absent>(absentSources);
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
            result.put("projectedReleaseRoot", finalRelease.toString()); result.put("projectedInstanceRoot", finalRoot.toString());
            result.put("sourceReleaseInventory", sourceInventory); result.put("outputInventory", outputInventory);
            List<Object> rows = new ArrayList<Object>();
            for (Copy item : copies) {
                Map<String,Object> row = new LinkedHashMap<String,Object>(); row.put("sourcePath", item.source.toString()); row.put("relativePath", item.destination); row.put("source", item.record); rows.add(row);
            }
            result.put("copies", rows); result.put("generation", renderGeneration(specification));
            List<Object> absent = new ArrayList<Object>();
            for (Absent item : absentSources) {
                Map<String,Object> row = new LinkedHashMap<String,Object>(); row.put("sourcePath", item.source.toString()); row.put("relativePath", item.destination); absent.add(row);
            }
            result.put("absentSideStateSources", absent);
            result.put("failurePolicy", "retain-new-partial-output-for-journaled-recovery");
            result.put("planFingerprintSha256", hashBytes(WorldBuilderJsonDocuments.canonical(result).getBytes(StandardCharsets.UTF_8)));
            return copy(result);
        }
        private void verifySources() throws IOException, WorldBuilderContractException {
            if (!sourceInventory.equals(inventory(stage, false))) throw failure("Reviewed release changed during instance construction.");
            for (Copy item : copies) requireRecord(item.source, item.record);
            for (Absent item : absentSources) { projected(item.source); if (Files.exists(item.source, LinkOption.NOFOLLOW_LINKS)) throw failure("Reviewed absent side-state appeared during construction."); }
        }
    }

    private static final class Copy {
        final Path source; final String destination; final Map<String,Object> record;
        Copy(Path source, String destination, Map<String,Object> record) { this.source = source; this.destination = destination; this.record = record; }
    }

    private static final class Absent {
        final Path source; final String destination;
        Absent(Path source, String destination) { this.source = source; this.destination = destination; }
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
