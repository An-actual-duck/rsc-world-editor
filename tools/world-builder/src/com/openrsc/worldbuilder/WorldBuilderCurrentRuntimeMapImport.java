package com.openrsc.worldbuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;

/** Map-only publication for an already installed, exact Current Base composition. */
final class WorldBuilderCurrentRuntimeMapImport {
    private static final String INSTANCE = WorldBuilderCurrentRuntimeInstalledGeneration.INSTANCE;
    private static final String LEDGER = WorldBuilderCurrentRuntimeInstalledGeneration.LEDGER;
    private static final String[] COMPOSITION = {"platformReleaseId", "platformManifestHash", "schemaSetHash", "variantId",
        "variantManifestHash", "moduleSetHash", "bundleInventoryHash", "bundleSpecId", "bundleSpecHash", "inputAdapterContractId"};
    interface Observer { void at(String milestone) throws IOException; }
    private final Observer observer;
    WorldBuilderCurrentRuntimeMapImport() { this(point -> { }); }
    WorldBuilderCurrentRuntimeMapImport(Observer observer) { this.observer = observer; }

    Plan preview(Path projectPath, Path exportPath, Path target, Path workspace, String transactionId)
        throws IOException, WorldBuilderContractException {
        directory(target); directory(workspace);
        if (!transactionId.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,110}") || target.startsWith(workspace) || workspace.startsWith(target)
            || !Files.getFileStore(target).equals(Files.getFileStore(workspace))) throw unsafe("Invalid external same-filesystem map transaction location.");
        WorldBuilderAdaptiveProjectLifecycle.VerifiedProject project =
            WorldBuilderAdaptiveProjectLifecycle.verifyProjectDirectory(projectPath, true);
        if (workspace.startsWith(project.projectRoot) || project.projectRoot.startsWith(workspace))
            throw unsafe("Map transaction workspace must be separate from the project.");
        Map<String,Object> projectTarget = object(project.manifest.get("target"));
        if (!"target-packed".equals(project.origin) || !"preservation-source-jag-v1".equals(projectTarget.get("adapterId"))
            || !"preservation-c0102e-data-conversion-v1".equals(projectTarget.get("capabilityId")))
            throw unsafe("Map import requires the verified native Preservation Base project.");
        Map<String,Object> identity = WorldBuilderCurrentBaseProjectContent.verifiedIdentity(project.projectRoot);
        if (!Boolean.TRUE.equals(identity.get("installable")) || !"current-base-v1".equals(identity.get("variantId")))
            throw unsafe("Project does not select installable Current Base.");
        Map<String,Object> spec = WorldBuilderCurrentRuntimeInstalledGeneration.readSpecification(target);
        Map<String,Object> ledger = WorldBuilderCurrentRuntimeContracts.read(WorldBuilderCurrentRuntimeContracts.Kind.TARGET_LEDGER, target.resolve(LEDGER)).root;
        if (!project.projectId.equals(object(ledger.get("installedInstance")).get("projectId")))
            throw unsafe("The installed target belongs to a different project.");
        for (String field : COMPOSITION) if (!Objects.equals(identity.get(field), ledger.get(field)))
            throw unsafe("Upgrade Target Runtime to the project's current composition before importing maps.");
        for (String role : Arrays.asList("server", "client"))
            if (!WorldBuilderCurrentRuntimeInstance.treeFingerprint(project.projectRoot.resolve("source/provider/installed/" + role), false)
                .equals(spec.get(role + "CodeTreeSha256")))
                throw unsafe("Installed code differs from the project's exact selected Base payload.");
        WorldBuilderAdaptiveExporter.VerifiedExport export = WorldBuilderAdaptiveExporter.validate(exportPath, project);
        for (Integer encoding : export.packageValue.requiredEncodingVersions)
            if (encoding.intValue() < 1 || encoding.intValue() > 5) throw unsafe("Map encoding is outside the accepted Base runtime contract.");
        Map<String,Object> ports = ports(spec);
        try (WorldBuilderCurrentRuntimeOfflineLease lease = WorldBuilderCurrentRuntimeOfflineLease.acquireInstalled(target.resolve(INSTANCE + "/installation"), ports)) {
            lease.verifyInstalledHeld();
            if (!spec.equals(WorldBuilderCurrentRuntimeInstalledGeneration.readSpecification(target))) throw unsafe("Installed generation changed during preview.");
            if (!ledger.equals(WorldBuilderCurrentRuntimeContracts.read(WorldBuilderCurrentRuntimeContracts.Kind.TARGET_LEDGER, target.resolve(LEDGER)).root))
                throw unsafe("Installed ledger changed during preview.");
            return plan(project, export, target, workspace, transactionId, identity, ledger, spec, ports);
        }
    }

    private static Plan plan(WorldBuilderAdaptiveProjectLifecycle.VerifiedProject project, WorldBuilderAdaptiveExporter.VerifiedExport export,
        Path target, Path workspace, String id, Map<String,Object> identity, Map<String,Object> beforeLedger,
        Map<String,Object> beforeSpec, Map<String,Object> ports) throws IOException, WorldBuilderContractException {
        Path transaction = workspace.resolve("map-" + id); absent(transaction);
        String mapRelative = INSTANCE + "/generations/map-assets-" + id;
        String generationRelative = INSTANCE + "/generations/map-" + id;
        absent(target.resolve(mapRelative)); absent(target.resolve(generationRelative));
        Map<String,Object> spec = new LinkedHashMap<String,Object>(beforeSpec);
        spec.put("generationId", "map-" + id); spec.put("mapRoot", target.resolve(mapRelative + "/package").toString());
        spec.put("mapPackageFingerprintSha256", export.packageValue.nativeInventorySha256);
        Map<String,byte[]> mapDocuments = new TreeMap<String,byte[]>();
        for (String role : Arrays.asList("server", "client")) {
            Map<String,Object> profile = read(Paths.get(string(beforeSpec, role + "MapProfilePath")));
            profile.put("packageId", export.packageValue.packageId); profile.put("packageVersion", export.packageValue.packageVersion);
            profile.put("manifestSha256", export.packageValue.manifestSha256);
            profile.put("packageFingerprintSha256", export.packageValue.nativeInventorySha256);
            // The external map root changes its physical location, not the
            // provider-owned profile's canonical logical package identity.
            profile.put("packageRelativePath", "world-builder/packages/"
                + export.packageValue.nativeInventorySha256 + "/package");
            byte[] bytes = json(profile); String name = "installed-" + role + ".json";
            mapDocuments.put(name, bytes);
            spec.put(role + "MapProfilePath", target.resolve(mapRelative + "/" + name).toString());
            spec.put(role + "MapProfileSha256", hash(bytes));
        }
        Map<String,Object> generation = WorldBuilderCurrentRuntimeInstance.renderGeneration(spec);
        Map<String,byte[]> generationDocuments = new TreeMap<String,byte[]>();
        for (String role : Arrays.asList("server", "client")) generationDocuments.put(role + "-launch.json", json(generation.get(role + "Descriptor")));
        Map<String,Object> ledger = new LinkedHashMap<String,Object>(beforeLedger);
        ledger.put("predecessorIdentityHash", beforeLedger.get("ledgerFingerprintSha256"));
        ledger.put("activeLauncherRelativePath", generationRelative + "/server-launch.json");
        ledger.put("activeMapPackageId", export.packageValue.packageId);
        List<Object> receipts = new ArrayList<Object>(array(beforeLedger.get("transactionReceiptIds")));
        if (receipts.contains(id) || receipts.size() >= 256) throw unsafe("Map transaction identity is reused or receipt history is full.");
        receipts.add(id); ledger.put("transactionReceiptIds", receipts);
        Map<String,Object> verification = new LinkedHashMap<String,Object>();
        verification.put("predecessor", beforeLedger.get("verificationEvidenceHash"));
        verification.put("export", export.manifestCanonicalSha256); verification.put("map", export.packageValue.nativeInventorySha256);
        ledger.put("verificationEvidenceHash", hash(json(verification)));
        ledger = WorldBuilderCurrentRuntimeInstalledGeneration.bind(ledger, target, spec, identity, read(export.root.resolve("package/manifest.json")), project.projectId);
        WorldBuilderCurrentRuntimeCutover.Plan cutover = WorldBuilderCurrentRuntimeCutover.inspect(target, "map-" + id,
            WorldBuilderHashes.sha256(target.resolve(INSTANCE + "/installation/active-launch.json")),
            WorldBuilderHashes.sha256(target.resolve(LEDGER)), json(generation.get("activeSelection")), json(ledger));
        Map<String,Object> value = new LinkedHashMap<String,Object>();
        value.put("schemaVersion", Long.valueOf(1)); value.put("manifestType", "world-builder-current-map-import-plan");
        value.put("transactionId", id); value.put("targetRoot", target.toString());
        value.put("projectRoot", project.projectRoot.toString()); value.put("exportRoot", export.root.toString());
        value.put("exportFingerprint", export.manifestCanonicalSha256); value.put("projectId", project.projectId);
        value.put("mapRelativePath", mapRelative); value.put("generationRelativePath", generationRelative);
        value.put("ports", ports); value.put("cutoverPlanSha256", cutover.fingerprint);
        value.put("mapOutputs", outputInventory(export, mapDocuments));
        value.put("generationOutputs", documentInventory(generationDocuments));
        value.put("retainedStatePolicy", "same-code-configuration-player-and-side-state-paths-no-state-copy");
        return new Plan(value, target, workspace, transaction, cutover, export, mapDocuments, generationDocuments);
    }

    String apply(Plan reviewed, String confirmation) throws IOException, WorldBuilderContractException {
        if (!reviewed.confirmation().equals(confirmation)) throw unsafe("Map import requires the exact reviewed confirmation identity.");
        try (WorldBuilderAdaptiveProjectLock projectLock = WorldBuilderAdaptiveProjectLock.acquire(Paths.get(string(reviewed.value, "projectRoot")), "current-map-import")) {
            Plan fresh = preview(Paths.get(string(reviewed.value, "projectRoot")), Paths.get(string(reviewed.value, "exportRoot")),
                reviewed.target, reviewed.workspace, string(reviewed.value, "transactionId"));
            if (!fresh.fingerprint.equals(reviewed.fingerprint)) throw unsafe("Map preview inputs or target have drifted.");
            reviewed = fresh; // Execute only the independently reconstructed immutable input closure.
            try (WorldBuilderCurrentRuntimeOfflineLease offline = WorldBuilderCurrentRuntimeOfflineLease.acquireInstalled(
                reviewed.target.resolve(INSTANCE + "/installation"), object(reviewed.value.get("ports")))) {
                WorldBuilderCurrentRuntimeInstanceLease lease = offline.installedLease();
                // Recheck the exact authority after taking the actual runtime leases.
                if (!WorldBuilderHashes.sha256(reviewed.target.resolve(LEDGER)).equals(hash(reviewed.cutover.beforeLedger))
                    || !WorldBuilderHashes.sha256(reviewed.target.resolve(INSTANCE + "/installation/active-launch.json")).equals(hash(reviewed.cutover.beforeSelection)))
                    throw unsafe("Installed metadata changed before map staging.");
                absent(reviewed.transaction); createDirectory(reviewed.transaction);
                write(reviewed.transaction.resolve("map-plan.json"), reviewed.bytes);
                WorldBuilderCurrentRuntimeCutover.journal(reviewed.cutover, reviewed.transaction.resolve("activation"));
                WorldBuilderAdaptiveDurability.forceDirectory(reviewed.transaction);
                WorldBuilderAdaptiveDurability.forceDirectory(reviewed.workspace);
                Path mapStage = reviewed.transaction.resolve("map-stage"), generationStage = reviewed.transaction.resolve("generation-stage");
                stage(reviewed, mapStage, generationStage);
                observer.at("staging-verified");
                WorldBuilderCurrentRuntimeCutover.guard(reviewed.cutover, reviewed.transaction.resolve("activation"), lease);
                try {
                    observer.at("guard-durable");
                    Path map = reviewed.target.resolve(string(reviewed.value, "mapRelativePath"));
                    directory(map.getParent()); lease.verifyHeld(reviewed.target.resolve(INSTANCE + "/installation"));
                    moveNew(mapStage, map); observer.at("map-published");
                    Path generation = reviewed.target.resolve(string(reviewed.value, "generationRelativePath"));
                    directory(generation.getParent()); moveNew(generationStage, generation); observer.at("generation-published");
                    verifyOutputs(map, array(reviewed.value.get("mapOutputs"))); verifyOutputs(generation, array(reviewed.value.get("generationOutputs")));
                    new WorldBuilderCurrentRuntimeCutover(observer::at).apply(reviewed.cutover, reviewed.transaction.resolve("activation"), lease);
                    observer.at("committed");
                    return "successful";
                } catch (IOException | WorldBuilderContractException | RuntimeException failure) {
                    // An ambiguous or damaged commit entry must NEVER authorize cleanup.
                    if (Files.exists(reviewed.transaction.resolve("activation/commit.json"), LinkOption.NOFOLLOW_LINKS)) throw failure;
                    try { recoverLocked(reviewed.value, reviewed.target, reviewed.transaction, reviewed.cutover, lease); }
                    catch (IOException | WorldBuilderContractException | RuntimeException retained) { retained.addSuppressed(failure); throw retained; }
                    throw failure;
                }
            }
        }
    }

    String recover(Path target, Path workspace, String id, String confirmedPlanHash) throws IOException, WorldBuilderContractException {
        directory(target); directory(workspace);
        if (target.startsWith(workspace) || workspace.startsWith(target)
            || !Files.getFileStore(target).equals(Files.getFileStore(workspace))) throw unsafe("Invalid external recovery workspace.");
        if (!id.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,110}")) throw unsafe("Invalid map recovery identity.");
        Path transaction = workspace.resolve("map-" + id);
        byte[] bytes = bytes(transaction.resolve("map-plan.json"));
        if (!hash(bytes).equals(confirmedPlanHash)) throw unsafe("Recovery requires the exact confirmed map plan fingerprint.");
        Map<String,Object> plan = read(transaction.resolve("map-plan.json"));
        validateRecovery(plan, target, id);
        WorldBuilderCurrentRuntimeCutover.Plan cutover = WorldBuilderCurrentRuntimeCutover.read(transaction.resolve("activation"), target, string(plan, "cutoverPlanSha256"));
        try (WorldBuilderCurrentRuntimeOfflineLease offline = WorldBuilderCurrentRuntimeOfflineLease.acquireInstalled(
            target.resolve(INSTANCE + "/installation"), object(plan.get("ports")))) {
            return recoverLocked(plan, target, transaction, cutover, offline.installedLease());
        }
    }

    private static String recoverLocked(Map<String,Object> plan, Path target, Path transaction,
        WorldBuilderCurrentRuntimeCutover.Plan cutover, WorldBuilderCurrentRuntimeInstanceLease lease)
        throws IOException, WorldBuilderContractException {
        if (Files.exists(transaction.resolve("activation/commit.json"), LinkOption.NOFOLLOW_LINKS)) {
            // Validate the new immutable inputs but never inspect/restore player state.
            verifyOutputs(target.resolve(string(plan, "mapRelativePath")), array(plan.get("mapOutputs")));
            verifyOutputs(target.resolve(string(plan, "generationRelativePath")), array(plan.get("generationOutputs")));
            return new WorldBuilderCurrentRuntimeCutover().recover(cutover, transaction.resolve("activation"), lease);
        }
        for (String kind : Arrays.asList("map", "generation")) {
            Path output = target.resolve(string(plan, kind + "RelativePath"));
            if (Files.exists(output, LinkOption.NOFOLLOW_LINKS)) verifyOutputs(output, array(plan.get(kind + "Outputs")));
        }
        String status = new WorldBuilderCurrentRuntimeCutover().recover(cutover, transaction.resolve("activation"), lease);
        for (String kind : Arrays.asList("generation", "map")) {
            lease.verifyHeld(target.resolve(INSTANCE + "/installation"));
            Path output = target.resolve(string(plan, kind + "RelativePath"));
            if (Files.exists(output, LinkOption.NOFOLLOW_LINKS)) deleteOwned(output, array(plan.get(kind + "Outputs")));
        }
        return status;
    }

    private static void stage(Plan plan, Path map, Path generation) throws IOException, WorldBuilderContractException {
        createDirectory(map); createDirectory(generation);
        for (WorldBuilderReadOnlyTarget.FileState file : plan.export.packageValue.files) {
            if (!file.relativePath.startsWith("package/")) throw unsafe("Exported map path escaped its package.");
            Path source = plan.export.root.resolve(file.relativePath); regular(source);
            if (Files.size(source) != file.size || !WorldBuilderHashes.sha256(source).equals(file.sha256)) throw unsafe("Export changed during staging.");
            Path destination = map.resolve(file.relativePath); createParents(map, destination.getParent());
            Files.copy(source, destination); Files.setPosixFilePermissions(destination, PosixFilePermissions.fromString("rw-------"));
        }
        for (Map.Entry<String,byte[]> entry : plan.mapDocuments.entrySet()) write(map.resolve(entry.getKey()), entry.getValue());
        for (Map.Entry<String,byte[]> entry : plan.generationDocuments.entrySet()) write(generation.resolve(entry.getKey()), entry.getValue());
        verifyOutputs(map, array(plan.value.get("mapOutputs"))); verifyOutputs(generation, array(plan.value.get("generationOutputs")));
        WorldBuilderAdaptiveDurability.forceTree(map); WorldBuilderAdaptiveDurability.forceTree(generation);
        WorldBuilderAdaptiveDurability.forceDirectory(plan.transaction);
    }

    private static List<Object> outputInventory(WorldBuilderAdaptiveExporter.VerifiedExport export, Map<String,byte[]> documents) throws WorldBuilderContractException {
        List<Object> rows = documentInventory(documents);
        for (WorldBuilderReadOnlyTarget.FileState file : export.packageValue.files) rows.add(row(file.relativePath, file.size, file.sha256));
        sort(rows); return rows;
    }
    private static List<Object> documentInventory(Map<String,byte[]> documents) throws WorldBuilderContractException {
        List<Object> result = new ArrayList<Object>();
        for (Map.Entry<String,byte[]> entry : documents.entrySet()) result.add(row(entry.getKey(), entry.getValue().length, hash(entry.getValue())));
        sort(result); return result;
    }
    private static Map<String,Object> row(String path, long size, String hash) throws WorldBuilderContractException {
        WorldBuilderPortablePath.require(path, "current-map-import");
        Map<String,Object> value = new LinkedHashMap<String,Object>(); value.put("relativePath", path); value.put("size", Long.valueOf(size)); value.put("sha256", hash); return value;
    }
    private static void sort(List<Object> rows) { Collections.sort(rows, (a,b) -> ((String)((Map<?,?>)a).get("relativePath")).compareTo((String)((Map<?,?>)b).get("relativePath"))); }
    private static void verifyOutputs(Path root, List<Object> expected) throws IOException, WorldBuilderContractException {
        directory(root); List<Object> actual = new ArrayList<Object>(); Set<String> directories = new HashSet<String>(); directories.add("");
        for (Object raw : expected) {
            String file = string(object(raw), "relativePath"); WorldBuilderPortablePath.require(file, "current-map-import");
            int slash = file.lastIndexOf('/'); while (slash >= 0) { directories.add(file.substring(0,slash)); slash = file.lastIndexOf('/',slash-1); }
        }
        try (java.util.stream.Stream<Path> stream = Files.walk(root)) {
            Iterator<Path> iterator = stream.iterator(); int count = 0;
            while (iterator.hasNext()) {
                Path path = iterator.next(); if (++count > 100000) throw unsafe("Map output inventory exceeds its bound.");
                String relative = root.relativize(path).toString().replace('\\','/');
                if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                    directory(path);
                    if (!directories.remove(relative) || !Files.getPosixFilePermissions(path).equals(PosixFilePermissions.fromString("rwx------"))) throw unsafe("Unexpected or nonprivate output directory.");
                } else {
                    regular(path);
                    if (!Files.getPosixFilePermissions(path).equals(PosixFilePermissions.fromString("rw-------"))) throw unsafe("Nonprivate map output.");
                    actual.add(row(relative, Files.size(path), WorldBuilderHashes.sha256(path)));
                }
            }
        }
        sort(actual);
        if (!directories.isEmpty() || !actual.equals(expected)) throw unsafe("Map publication inventory changed; preserve it for recovery.");
    }
    private static void deleteOwned(Path root, List<Object> expected) throws IOException, WorldBuilderContractException {
        verifyOutputs(root, expected);
        Map<String,Object> records = new HashMap<String,Object>();
        for (Object raw : expected) records.put(string(object(raw), "relativePath"), raw);
        List<Path> paths = new ArrayList<Path>();
        try (java.util.stream.Stream<Path> stream = Files.walk(root)) { stream.forEach(paths::add); }
        Collections.sort(paths, Comparator.reverseOrder());
        for (Path path : paths) {
            if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) directory(path);
            else {
                regular(path);
                String relative = root.relativize(path).toString().replace('\\','/');
                if (!row(relative, Files.size(path), WorldBuilderHashes.sha256(path)).equals(records.get(relative)))
                    throw unsafe("Owned map output changed during cleanup; retain it for recovery.");
            }
            Files.delete(path); // Directories must be empty; no recursive or force deletion.
        }
        WorldBuilderAdaptiveDurability.forceDirectory(root.getParent());
    }
    private static Map<String,Object> ports(Map<String,Object> spec) throws IOException, WorldBuilderContractException {
        Map<String,Object> result = new LinkedHashMap<String,Object>(); result.put("gamePort", spec.get("gamePort"));
        String value = null;
        for (String line : new String(bytes(Paths.get(string(spec,"serverConfigurationPath"))), StandardCharsets.UTF_8).split("\\r?\\n")) {
            String clean = line.split("#",2)[0].trim();
            if (clean.startsWith("ws_server_port:")) { if (value != null) throw unsafe("Ambiguous installed websocket port."); value = clean.substring(clean.indexOf(':')+1).trim(); }
        }
        try { result.put("websocketPort", Long.valueOf(value)); } catch (RuntimeException bad) { throw unsafe("Installed websocket port is absent or invalid."); }
        return result;
    }
    private static void validateRecovery(Map<String,Object> plan, Path target, String id) throws WorldBuilderContractException {
        Set<String> keys = new HashSet<String>(Arrays.asList("schemaVersion", "manifestType", "transactionId", "targetRoot", "projectRoot", "exportRoot", "exportFingerprint", "projectId",
            "mapRelativePath", "generationRelativePath", "ports", "cutoverPlanSha256", "mapOutputs", "generationOutputs", "retainedStatePolicy"));
        if (!plan.keySet().equals(keys) || !Long.valueOf(1).equals(plan.get("schemaVersion"))
            || !"world-builder-current-map-import-plan".equals(plan.get("manifestType")) || !target.toString().equals(plan.get("targetRoot")) || !id.equals(plan.get("transactionId"))
            || !(INSTANCE+"/generations/map-assets-"+id).equals(plan.get("mapRelativePath"))
            || !(INSTANCE+"/generations/map-"+id).equals(plan.get("generationRelativePath"))) throw unsafe("Map recovery plan identity/path differs.");
        for (String kind : Arrays.asList("map", "generation")) {
            List<Object> rows = array(plan.get(kind+"Outputs")); if (rows.size() > 30000) throw unsafe("Recovery inventory exceeds its bound.");
            for (Object raw : rows) {
                Map<String,Object> row = object(raw);
                if (!row.keySet().equals(new HashSet<String>(Arrays.asList("relativePath","size","sha256")))) throw unsafe("Malformed map inventory.");
                WorldBuilderPortablePath.require(string(row,"relativePath"),"current-map-import");
            }
        }
    }
    private static void createParents(Path root, Path parent) throws IOException, WorldBuilderContractException {
        if (!parent.startsWith(root)) throw unsafe("Staged map output escapes its root.");
        if (parent.equals(root)) return;
        createParents(root,parent.getParent());
        if (!Files.exists(parent,LinkOption.NOFOLLOW_LINKS)) createDirectory(parent); else directory(parent);
    }
    private static void createDirectory(Path path) throws IOException, WorldBuilderContractException { directory(path.getParent()); absent(path); Files.createDirectory(path,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"))); }
    private static void moveNew(Path from, Path to) throws IOException, WorldBuilderContractException { absent(to); Files.move(from,to,StandardCopyOption.ATOMIC_MOVE); WorldBuilderAdaptiveDurability.forceDirectory(to.getParent()); WorldBuilderAdaptiveDurability.forceDirectory(from.getParent()); }
    private static void directory(Path path) throws IOException, WorldBuilderContractException { if (!path.isAbsolute() || !path.normalize().equals(path) || !path.equals(path.toRealPath()) || !Files.isDirectory(path,LinkOption.NOFOLLOW_LINKS)) throw unsafe("Map transaction directory is absent or aliased."); }
    private static void regular(Path path) throws IOException, WorldBuilderContractException { if (!path.isAbsolute() || !path.normalize().equals(path) || !path.equals(path.toRealPath()) || !Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS) || ((Number)Files.getAttribute(path,"unix:nlink",LinkOption.NOFOLLOW_LINKS)).longValue()!=1) throw unsafe("Map input/output is absent or aliased."); }
    private static byte[] bytes(Path path) throws IOException, WorldBuilderContractException { regular(path); if(Files.size(path)>1048576) throw unsafe("Metadata exceeds its bound."); return Files.readAllBytes(path); }
    private static Map<String,Object> read(Path path) throws IOException, WorldBuilderContractException { try { return WorldBuilderJsonDocuments.readObject(bytes(path),"current-map-import"); } catch(WorldBuilderDiscoveryException bad) { throw unsafe("Malformed map metadata."); } }
    private static void absent(Path path) throws WorldBuilderContractException { if(Files.exists(path,LinkOption.NOFOLLOW_LINKS)) throw unsafe("Map transaction destination already exists."); }
    private static void write(Path path,byte[] bytes) throws IOException,WorldBuilderContractException { directory(path.getParent()); absent(path); Files.createFile(path,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))); Files.write(path,bytes,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS); WorldBuilderAdaptiveDurability.forceFile(path); }
    private static byte[] json(Object value) { return WorldBuilderJsonDocuments.pretty(value).getBytes(StandardCharsets.UTF_8); }
    private static String hash(byte[] value) { return WorldBuilderHashes.sha256(value); }
    @SuppressWarnings("unchecked") private static Map<String,Object> object(Object value) throws WorldBuilderContractException { if(!(value instanceof Map)) throw unsafe("Expected map transaction object."); return (Map<String,Object>)value; }
    @SuppressWarnings("unchecked") private static List<Object> array(Object value) throws WorldBuilderContractException { if(!(value instanceof List)) throw unsafe("Expected map transaction array."); return (List<Object>)value; }
    private static String string(Map<String,Object> value,String key) throws WorldBuilderContractException { if(!(value.get(key) instanceof String)) throw unsafe("Expected map transaction string."); return (String)value.get(key); }
    private static WorldBuilderContractException unsafe(String message) { return new WorldBuilderContractException(WorldBuilderErrorCodes.RECOVERY_REQUIRED,"current-map-import","transaction",false,message,"Keep the installed pair offline; review a fresh preview or recover the exact confirmed transaction. There is no force mode."); }
    static final class Plan {
        private final Map<String,Object> value; private final Path target,workspace,transaction;
        private final WorldBuilderCurrentRuntimeCutover.Plan cutover; private final WorldBuilderAdaptiveExporter.VerifiedExport export;
        private final Map<String,byte[]> mapDocuments,generationDocuments; private final byte[] bytes; final String fingerprint;
        Plan(Map<String,Object> value,Path target,Path workspace,Path transaction,WorldBuilderCurrentRuntimeCutover.Plan cutover,
            WorldBuilderAdaptiveExporter.VerifiedExport export,Map<String,byte[]> mapDocuments,Map<String,byte[]> generationDocuments) throws WorldBuilderContractException {
            this.value=value;this.target=target;this.workspace=workspace;this.transaction=transaction;this.cutover=cutover;this.export=export;
            this.mapDocuments=mapDocuments;this.generationDocuments=generationDocuments;bytes=json(value);fingerprint=hash(bytes);
            if (bytes.length > 1048576) throw unsafe("Map transaction plan exceeds its recovery bound.");
        }
        String confirmation() { return "IMPORT-MAP "+fingerprint; }
        String humanSummary() {
            return "Import Map Changes — Current Base\nServer target: " + target
                + "\nProject: " + value.get("projectRoot") + "\nExport: " + value.get("exportRoot")
                + "\nRetains the installed code, configuration, player database, and side state."
                + "\nBoth server and client must remain offline."
                + "\nRecovery workspace: " + workspace + "\nTransaction: " + value.get("transactionId")
                + "\nKeep this confirmation for interrupted recovery: " + confirmation() + "\n";
        }
        String toJson() { Map<String,Object> result=new LinkedHashMap<String,Object>(value);result.put("planFingerprintSha256",fingerprint);result.put("confirmationIdentity",confirmation());return WorldBuilderJsonDocuments.pretty(result); }
    }
}
