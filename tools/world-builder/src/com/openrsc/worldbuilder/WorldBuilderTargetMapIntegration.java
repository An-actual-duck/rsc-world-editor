package com.openrsc.worldbuilder;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import java.util.regex.*;
import java.util.zip.*;
import javax.tools.*;

/** Builds map integration against target-owned code; never executes target build scripts. */
final class WorldBuilderTargetMapIntegration {
    static final String DESCRIPTOR = "server/conf/world-builder/target-map-integration-v1.json";
    static final String INSTALLED = "server/conf/world-builder/installed-target-map-integration-v1.json";
    static final String ROLE = "runtime-compatibility-targeted-";
    private static final int MAX_SOURCE = 4 * 1024 * 1024;
    private static final int MAX_ARCHIVE = 256 * 1024 * 1024;
    private static final int MAX_ENTRY = 16 * 1024 * 1024;
    private WorldBuilderTargetMapIntegration() { }

    static Result prepare(Path project, Path target, String clientRoot) throws IOException, WorldBuilderContractException {
        Path current = embeddedPayload();
        if (current == null) throw failure(DESCRIPTOR, "This application has no trusted targeted map integration payload.");
        try { return preparePayload(current, target, clientRoot); }
        finally { if (current != null) deleteOwnedStage(current); }
    }

    /**
     * Re-check a rebuild against project-owned, transaction-verified original
     * integration evidence. The caller verifies receipt/plan/content lineage;
     * target-provided proof or hashes alone must never supply these arguments.
     * Only the compatibility proof is returned for transactional installation.
     */
    static Result reverify(Path project, Path target, String clientRoot, byte[] trustedInstalledProof,
        Map<String,Path> trustedBaselineOutputs) throws IOException, WorldBuilderContractException {
        Path current = embeddedPayload();
        if (current == null) throw failure(DESCRIPTOR, "This application has no trusted targeted map integration payload.");
        try { return reverifyPayload(current, target, clientRoot, trustedInstalledProof, trustedBaselineOutputs); }
        finally { deleteOwnedStage(current); }
    }

    static Result reverifyPayload(Path payload, Path target, String clientRoot, byte[] trustedInstalledProof,
        Map<String,Path> trustedBaselineOutputs) throws IOException, WorldBuilderContractException {
        Map<String,Object> previous;
        try { previous = WorldBuilderJsonDocuments.readObject(trustedInstalledProof, INSTALLED); }
        catch (WorldBuilderDiscoveryException invalid) { throw failure(INSTALLED, "Retained integration proof is malformed."); }
        Path descriptorPath = file(payload, "working/runtime/" + DESCRIPTOR);
        Map<String,Object> descriptor = read(descriptorPath); requireDescriptor(descriptor);
        if (!"world-builder-installed-target-map-integration".equals(previous.get("manifestType"))
            || !Long.valueOf(1).equals(previous.get("schemaVersion"))
            || !descriptor.get("integrationId").equals(previous.get("integrationId"))
            || !WorldBuilderHashes.sha256(descriptorPath).equals(previous.get("descriptorSha256")))
            throw failure(INSTALLED, "Retained integration proof is not covered by this application's exact map contract.");
        Map<String,Object> adapter = null;
        for (Object raw : array(descriptor.get("adapters")))
            if (object(raw).get("adapterId").equals(previous.get("adapterId"))) adapter = object(raw);
        if (adapter == null) throw failure(INSTALLED, "Retained integration adapter is unsupported by this application.");
        TreeMap<String,String> expected = new TreeMap<String,String>();
        for (Map.Entry<String,Object> entry : object(previous.get("beforeInputs")).entrySet()) {
            if (!(entry.getValue() instanceof String) || !((String)entry.getValue()).matches("[0-9a-f]{64}"))
                throw failure(INSTALLED, "Retained compilation input hash is malformed.");
            expected.put(entry.getKey(), (String)entry.getValue());
        }
        Map<String,Map<String,Object>> manifestContracts = new TreeMap<String,Map<String,Object>>();
        for (Object raw : array(adapter.get("compilation"))) {
            Map<String,Object> compilation = object(raw);
            manifestContracts.put(root(scope(compilation), clientRoot) + "/" + string(compilation, "archiveRelativePath"), object(compilation.get("manifestAttributes")));
        }
        Set<String> archivePaths = new TreeSet<String>();
        for (Object raw : array(previous.get("archives"))) {
            Map<String,Object> record = object(raw); String path = string(record, "relativePath");
            if (!archivePaths.add(path)) throw failure(INSTALLED, "Retained archive proof repeats an archive.");
            Path baseline = trustedBaselineOutputs.get(path);
            if (baseline == null || Files.isSymbolicLink(baseline) || !Files.isRegularFile(baseline, LinkOption.NOFOLLOW_LINKS))
                throw failure(path, "Re-verification requires the exact project-retained integrated archive, verified against its transaction.");
            byte[] baselineBytes = bounded(baseline, MAX_ARCHIVE);
            if (!hash(baselineBytes).equals(string(record, "sha256")))
                throw failure(path, "Re-verification requires the exact project-retained integrated archive, verified against its transaction.");
            if (!manifestContracts.containsKey(path)) throw failure(path, "Retained archive is outside the reviewed compilation contract.");
            byte[] currentBytes = bounded(file(target, path), MAX_ARCHIVE);
            requireEquivalentArchive(path, rebuildEntries(baselineBytes, path), rebuildEntries(currentBytes, path), manifestContracts.get(path));
            expected.put(path, hash(currentBytes));
        }
        for (Object raw : array(previous.get("sources"))) {
            Map<String,Object> record = object(raw);
            expected.put(string(record, "relativePath"), string(record, "sha256"));
        }
        // A removed legacy build guard was an explicit original integration
        // action, but its after-state is not a source row in the old proof.
        if (expected.containsKey("server/build.xml") && trustedBaselineOutputs.containsKey("server/build.xml"))
            expected.put("server/build.xml", WorldBuilderHashes.sha256(trustedBaselineOutputs.get("server/build.xml")));
        for (Map.Entry<String,String> input : expected.entrySet())
            if (!input.getValue().equals(WorldBuilderHashes.sha256(file(target, input.getKey()))))
                throw failure(input.getKey(), "Re-verification supports unchanged integrated sources and dependencies only; restore the reviewed input or obtain a reviewed source integration.");

        Result checked = preparePayload(payload, target, clientRoot, true);
        if (!checked.adapterId.equals(previous.get("adapterId")))
            throw failure(INSTALLED, "The current target no longer matches the retained integration adapter.");
        for (Map.Entry<String,String> input : expected.entrySet())
            if (!input.getValue().equals(checked.inputs.get(input.getKey())))
                throw failure(input.getKey(), "Source or dependency inventory differs from the retained integrated state.");
        Set<String> newlyCoveredRoots = new TreeSet<String>();
        for (Object raw : array(adapter.get("compilation"))) {
            Map<String,Object> compilation = object(raw);
            if (!Boolean.TRUE.equals(compilation.get("compileAllSources")))
                for (Object root : array(compilation.get("sourceRoots"))) newlyCoveredRoots.add(root(scope(compilation), clientRoot) + "/" + root + "/");
            if ("client".equals(scope(compilation)) && "Client_Base".equals(clientRoot)) newlyCoveredRoots.add("PC_Client/src/");
        }
        for (String path : checked.inputs.keySet()) if (!expected.containsKey(path)) {
            boolean newlyCovered = false;
            for (String root : newlyCoveredRoots) if (path.startsWith(root) && path.endsWith(".java")) newlyCovered = true;
            if (!newlyCovered) throw failure(path, "Source or dependency inventory differs from the retained integrated state; added compiler inputs require a reviewed integration.");
        }
        Set<String> checkedArchives = new TreeSet<String>();
        for (Map.Entry<String,byte[]> output : checked.outputs.entrySet()) {
            String path = output.getKey(); if (INSTALLED.equals(path)) continue;
            if (archivePaths.contains(path)) { checkedArchives.add(path); continue; }
            if (!Arrays.equals(output.getValue(), bounded(file(target, path), MAX_SOURCE)))
                throw failure(path, "The maintained sources still require integration changes; re-verification cannot port source or build files.");
        }
        if (!checkedArchives.equals(archivePaths)) throw failure(INSTALLED, "Retained integration archive inventory is incomplete.");
        Map<String,Object> installed;
        try { installed = WorldBuilderJsonDocuments.readObject(checked.outputs.get(INSTALLED), INSTALLED); }
        catch (WorldBuilderDiscoveryException invalid) { throw failure(INSTALLED, "Rechecked integration proof is malformed."); }
        for (Object raw : array(installed.get("archives"))) {
            Map<String,Object> record = object(raw); String path = string(record, "relativePath");
            // The isolated compile proved current map behavior. It must require
            // no class/resource changes even though its ZIP encoding may differ.
            Path stage = Files.createTempFile("world-builder-rechecked-", ".jar");
            try {
                Files.write(stage, checked.outputs.get(path));
                requireEquivalentArchive(path, entries(file(target, path), path, true), entries(stage, path, true), manifestContracts.get(path));
            } finally { Files.deleteIfExists(stage); }
            record.put("sha256", checked.inputs.get(path));
            record.put("beforeSha256", checked.inputs.get(path));
            record.remove("normalizedIdenticalLegalNotices");
        }
        TreeMap<String,byte[]> outputs = new TreeMap<String,byte[]>();
        outputs.put(INSTALLED, WorldBuilderJsonDocuments.pretty(installed).getBytes(StandardCharsets.UTF_8));
        return new Result(checked.adapterId, outputs, checked.inputs, checked.encodingVersions);
    }

    private static Map<String,byte[]> rebuildEntries(byte[] bytes, String path) throws IOException, WorldBuilderContractException {
        Path stage = Files.createTempFile("world-builder-rebuild-input-", ".jar");
        try { Files.write(stage, bytes); return entries(stage, path, true); }
        finally { Files.deleteIfExists(stage); }
    }

    private static void requireEquivalentArchive(String path, Map<String,byte[]> trusted, Map<String,byte[]> rebuilt, Map<String,Object> manifestContract)
        throws IOException, WorldBuilderContractException {
        if (!trusted.keySet().equals(rebuilt.keySet()))
            throw failure(path, "Rebuilt archive entry inventory changed; added or removed content needs a reviewed integration.");
        for (String name : trusted.keySet()) {
            byte[] before = trusted.get(name), after = rebuilt.get(name);
            boolean same = Arrays.equals(before, after) || (name.endsWith(".class") ? equivalentClass(path + "!/" + name, before, after)
                : "META-INF/MANIFEST.MF".equals(name) ? equivalentManifest(path, before, after, manifestContract) : false);
            if (!same) throw failure(path + "!/" + name,
                "Rebuilt archive differs from verified target behavior or retained resources. Only equivalent class/debug output and ZIP metadata differences are supported.");
        }
    }

    private static boolean equivalentManifest(String path, byte[] before, byte[] after, Map<String,Object> contract)
        throws IOException, WorldBuilderContractException {
        Manifest original = new Manifest(new ByteArrayInputStream(before));
        Manifest rebuilt = new Manifest(new ByteArrayInputStream(after));
        for (Manifest manifest : Arrays.asList(original, rebuilt)) {
            Attributes attributes = manifest.getMainAttributes();
            // Packaging provenance is not executed. All other attributes and
            // per-entry sections (sealing, release behavior, etc.) stay exact.
            attributes.remove(new Attributes.Name("Created-By"));
            attributes.remove(new Attributes.Name("Ant-Version"));
            for (Map.Entry<String,Object> entry : contract.entrySet()) {
                if (!Arrays.asList("World-Builder-Floor-Semantics", "World-Builder-Installed-Floors", "World-Builder-Map-Integration").contains(entry.getKey()))
                    throw failure(DESCRIPTOR, "Unknown map integration manifest marker.");
                String actual = attributes.getValue(entry.getKey());
                if (actual != null && !actual.equals(entry.getValue()))
                    throw failure(path, "A rebuilt archive advertises an incompatible map integration marker.");
                attributes.remove(new Attributes.Name(entry.getKey()));
            }
        }
        return original.equals(rebuilt);
    }

    static Result preparePayload(Path project, Path target, String clientRoot) throws IOException, WorldBuilderContractException {
        return preparePayload(project, target, clientRoot, false);
    }

    private static Result preparePayload(Path project, Path target, String clientRoot, boolean reverify) throws IOException, WorldBuilderContractException {
        Path descriptorPath = file(project, "working/runtime/" + DESCRIPTOR);
        Map<String,Object> descriptor = read(descriptorPath);
        requireDescriptor(descriptor);
        List<String> refusals = new ArrayList<String>();
        Result selected = null;
        for (Object raw : array(descriptor.get("adapters"))) {
            Map<String,Object> adapter = object(raw);
            try {
                Result candidate = prepareAdapter(project, target, clientRoot, descriptor, adapter, descriptorPath, reverify);
                if (selected != null) throw failure(DESCRIPTOR, "More than one targeted map adapter matches this target.");
                selected = candidate;
            } catch (AdapterMismatch mismatch) { refusals.add(string(adapter, "adapterId") + ": " + mismatch.getMessage()); }
        }
        if (selected == null) throw failure(DESCRIPTOR,
            "No reviewed targeted map integration matches this server/client. " + String.join("; ", refusals));
        return selected;
    }

    private static Result prepareAdapter(Path project, Path target, String clientRoot, Map<String,Object> descriptor,
        Map<String,Object> adapter, Path descriptorPath, boolean reverify) throws IOException, WorldBuilderContractException, AdapterMismatch {
        exactKeys(adapter, "adapterId", "sources", "transforms", "requirements", "requiredEntryProbes", "compilation");
        String adapterId = string(adapter, "adapterId");
        TreeMap<String,byte[]> sources = new TreeMap<String,byte[]>();
        TreeMap<String,String> scopes = new TreeMap<String,String>();
        TreeMap<String,byte[]> verificationSources = new TreeMap<String,byte[]>();
        TreeMap<String,String> inputHashes = new TreeMap<String,String>();
        List<Object> inputInventories = new ArrayList<Object>();
        for (Object raw : array(adapter.get("requirements"))) {
            Map<String,Object> requirement = object(raw);
            allowedKeys(requirement, Arrays.asList("acceptedSourceSha256"), "scope", "targetRelativePath", "requiredFragments");
            String path = targetPath(requirement, clientRoot);
            String content = source(target, path);
            if (requirement.containsKey("acceptedSourceSha256") && !array(requirement.get("acceptedSourceSha256")).contains(hash(content.getBytes(StandardCharsets.UTF_8))))
                throw new AdapterMismatch("Map source requirement differs from reviewed implementations at " + path);
            for (Object fragment : array(requirement.get("requiredFragments")))
                if (!(fragment instanceof String) || !executableContains(content, (String)fragment))
                    throw new AdapterMismatch("Required map hook is absent at " + path);
            inputHashes.put(path, hash(content.getBytes(StandardCharsets.UTF_8)));
            verificationSources.put(path, content.getBytes(StandardCharsets.UTF_8));
        }
        for (Object raw : array(adapter.get("requiredEntryProbes"))) {
            Map<String,Object> probe = object(raw);
            exactKeys(probe, "scope", "entry", "markers");
            String scope = scope(probe);
            String archivePath = root(scope, clientRoot) + "/" + ("server".equals(scope) ? "core.jar" : "Open_RSC_Client.jar");
            Map<String,byte[]> archive = entries(file(target, archivePath), archivePath, true);
            String entry = string(probe, "entry");
            byte[] bytes = archive.get(entry);
            if (bytes == null) throw new AdapterMismatch("Required prior map integration is missing from " + archivePath + ": " + entry);
            String binary = new String(bytes, StandardCharsets.ISO_8859_1);
            for (Object marker : array(probe.get("markers"))) if (!(marker instanceof String) || !binary.contains((String)marker))
                throw new AdapterMismatch("Required prior map integration differs at " + entry);
        }
        for (Object raw : array(adapter.get("sources"))) {
            Map<String,Object> spec = object(raw);
            exactKeys(spec, "scope", "targetRelativePath", "payloadRelativePath", "sha256", "policy", "acceptedBeforeSha256");
            if (!string(spec, "sha256").matches("[0-9a-f]{64}") || array(spec.get("acceptedBeforeSha256")).size() > 32) throw failure(DESCRIPTOR, "Invalid source hash evidence.");
            for (Object beforeHash : array(spec.get("acceptedBeforeSha256"))) if (!(beforeHash instanceof String) || !((String)beforeHash).matches("[0-9a-f]{64}")) throw failure(DESCRIPTOR, "Invalid source preimage hash.");
            String destination = targetPath(spec, clientRoot);
            String payloadPath = string(spec, "payloadRelativePath");
            if (!payloadPath.startsWith("server/conf/world-builder/target-map-source/"))
                throw failure(DESCRIPTOR, "Source payload is outside the reviewed map integration directory.");
            byte[] bytes = bounded(file(project, "working/runtime/" + payloadPath), MAX_SOURCE);
            if (!hash(bytes).equals(string(spec, "sha256"))) throw failure(payloadPath, "Map source payload does not match its descriptor.");
            String policy = string(spec, "policy");
            if (!"add-or-exact".equals(policy) && !"replace-reviewed-map-source".equals(policy))
                throw failure(DESCRIPTOR, "Unknown map source replacement policy.");
            Path before = safe(target, destination);
            if (Files.exists(before, LinkOption.NOFOLLOW_LINKS)) {
                byte[] current = bounded(file(target, destination), MAX_SOURCE);
                if (!Arrays.equals(current, bytes) && (!"replace-reviewed-map-source".equals(policy)
                    || !array(spec.get("acceptedBeforeSha256")).contains(hash(current))))
                    throw new AdapterMismatch("Customized map source requires reviewed integration at " + destination);
                inputHashes.put(destination, hash(current));
            } else if (!"add-or-exact".equals(policy)) {
                throw new AdapterMismatch("Required existing map source is absent at " + destination);
            }
            putSource(sources, scopes, destination, scope(spec), bytes);
        }
        for (Object raw : array(adapter.get("transforms"))) {
            Map<String,Object> spec = object(raw);
            exactKeys(spec, "scope", "targetRelativePath", "transformId", "edits");
            String destination = targetPath(spec, clientRoot);
            if (sources.containsKey(destination)) throw failure(DESCRIPTOR, "A source cannot be both replaced and transformed.");
            String before = source(target, destination);
            String after = transform(before, spec, destination);
            inputHashes.put(destination, hash(before.getBytes(StandardCharsets.UTF_8)));
            putSource(sources, scopes, destination, scope(spec), after.getBytes(StandardCharsets.UTF_8));
        }
        if (sources.isEmpty() || sources.size() > 128) throw failure(DESCRIPTOR, "Map source set is empty or exceeds its bound.");
        Set<String> abiClosure = abiClosure(target, clientRoot, adapter);
        TreeMap<String,byte[]> outputs = new TreeMap<String,byte[]>(sources);
        for (String shadow : Arrays.asList("server/core-gameplay-overlay.jar", "server/world-builder-runtime/world-builder-managed-runtime.jar", "server/lib/world-builder-managed-runtime.jar"))
            if (Files.exists(safe(target, shadow), LinkOption.NOFOLLOW_LINKS)) throw retiredShadowRuntime(shadow);
        Path build = safe(target, "server/build.xml");
        if (Files.exists(build, LinkOption.NOFOLLOW_LINKS)) {
            byte[] before = bounded(file(target, "server/build.xml"), MAX_SOURCE);
            String corrected = removeOwnedBuildGuard(utf8(before, "server/build.xml"));
            inputHashes.put("server/build.xml", hash(before));
            if (!Arrays.equals(before, corrected.getBytes(StandardCharsets.UTF_8))) outputs.put("server/build.xml", corrected.getBytes(StandardCharsets.UTF_8));
        }
        Set<String> compiledScopes = new HashSet<String>();
        Set<String> compiledArchives = new HashSet<String>();
        List<Object> archiveEvidence = new ArrayList<Object>();
        Path stage = Files.createTempDirectory("world-builder-map-compile-");
        try {
            for (Object raw : array(adapter.get("compilation"))) {
                Map<String,Object> compilation = object(raw);
                allowedKeys(compilation, Arrays.asList("dependencyArchives", "abiChangedClasses", "verificationSources"), "scope", "archiveRelativePath", "sourceRoots", "dependencyDirectories", "sourceLevel", "targetLevel", "manifestAttributes", "compileAllSources", "runtimeLevel");
                if (!(compilation.get("compileAllSources") instanceof Boolean) || !Long.valueOf(17).equals(compilation.get("runtimeLevel"))) throw failure(DESCRIPTOR, "Unsupported Java runtime or compilation scope contract.");
                String scope = scope(compilation);
                compiledScopes.add(scope);
                String name = string(compilation, "archiveRelativePath");
                if (!("server".equals(scope) ? Arrays.asList("core.jar", "plugins.jar") : Arrays.asList("Open_RSC_Client.jar")).contains(name))
                    throw failure(DESCRIPTOR, "Unsupported target archive destination.");
                String destination = root(scope, clientRoot) + "/" + name;
                if (!compiledArchives.add(destination)) throw failure(DESCRIPTOR, "Duplicate map compilation archive.");
                Path archive = file(target, destination);
                Map<String,Integer> normalizedNotices = new TreeMap<String,Integer>();
                Map<String,byte[]> beforeEntries = WorldBuilderTargetArchive.read(archive, destination, true, normalizedNotices);
                byte[] beforeBytes = bounded(archive, MAX_ARCHIVE);
                inputHashes.put(destination, hash(beforeBytes));
                TreeMap<String,byte[]> roleSources = new TreeMap<String,byte[]>();
                List<?> roots = array(compilation.get("sourceRoots"));
                if (compilation.containsKey("verificationSources")) for (Object rawSource : array(compilation.get("verificationSources"))) {
                    if (!(rawSource instanceof String) || !((String)rawSource).startsWith("src/") || !((String)rawSource).endsWith(".java")) throw failure(DESCRIPTOR, "Invalid verification source path.");
                    String path = root(scope, clientRoot) + "/" + rawSource; byte[] bytes = bounded(file(target, path), MAX_SOURCE);
                    inputHashes.put(path, hash(bytes)); verificationSources.put(path, bytes);
                }
                for (Object rawRoot : roots) {
                    if (!(rawRoot instanceof String) || !Arrays.asList("src", "plugins").contains(rawRoot)) throw failure(DESCRIPTOR, "Unsupported compiler source root.");
                    String relativeRoot = root(scope, clientRoot) + "/" + rawRoot;
                    for (String path : verificationSources.keySet()) if (path.startsWith(relativeRoot + "/")) roleSources.put(path, verificationSources.get(path));
                    for (String path : sources.keySet()) if (scope.equals(scopes.get(path)) && path.startsWith(relativeRoot + "/")) roleSources.put(path, sources.get(path));
                    if (!reverify && !Boolean.TRUE.equals(compilation.get("compileAllSources"))) continue;
                    Path sourceRoot = fileRoot(target, relativeRoot);
                    try (java.util.stream.Stream<Path> walk = Files.walk(sourceRoot)) {
                        Iterator<Path> iterator = walk.iterator();
                        while (iterator.hasNext()) {
                            Path path = iterator.next();
                            if (Files.isSymbolicLink(path)) throw failure(path.toString(), "Linked compiler source is unsupported.");
                            if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) continue;
                            if (!path.toString().endsWith(".java")) continue;
                            if (roleSources.size() >= 16000) throw failure("src", "Target source inventory exceeds its bound.");
                            String relative = target.relativize(path).toString().replace('\\', '/');
                            byte[] bytes = bounded(file(target, relative), MAX_SOURCE);
                            inputHashes.put(relative, hash(bytes));
                            if (!roleSources.containsKey(relative)) roleSources.put(relative, bytes);
                        }
                    }
                }
                if (reverify && "client".equals(scope) && "Client_Base".equals(clientRoot)
                    && Files.exists(safe(target, "PC_Client/src"), LinkOption.NOFOLLOW_LINKS)) {
                    Path companion = fileRoot(target, "PC_Client/src");
                    try (java.util.stream.Stream<Path> walk = Files.walk(companion)) {
                        Iterator<Path> iterator = walk.iterator();
                        while (iterator.hasNext()) {
                            Path path = iterator.next();
                            if (Files.isSymbolicLink(path)) throw failure(path.toString(), "Linked compiler source is unsupported.");
                            if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) || !path.toString().endsWith(".java")) continue;
                            if (roleSources.size() >= 16000) throw failure("src", "Target source inventory exceeds its bound.");
                            String relative = target.relativize(path).toString().replace('\\', '/');
                            byte[] bytes = bounded(file(target, relative), MAX_SOURCE);
                            inputHashes.put(relative, hash(bytes)); roleSources.put(relative, bytes);
                        }
                    }
                }
                if (reverify && "client".equals(scope) && "Client_Base".equals(clientRoot))
                    inputInventories.add(inventory(target, "PC_Client/src", ".java", true));
                for (Object rawRoot : roots) if (reverify || Boolean.TRUE.equals(compilation.get("compileAllSources")))
                    inputInventories.add(inventory(target, root(scope, clientRoot) + "/" + rawRoot, ".java", true));
                for (Object rawDirectory : array(compilation.get("dependencyDirectories")))
                    inputInventories.add(inventory(target, (String)rawDirectory, ".jar", false));
                Path compilationStage = stage.resolve(scope + "-" + name.replace('.', '-'));
                Map<String,byte[]> classes = compile(target, compilationStage, archive, compilation, roleSources, inputHashes, outputs, stage);
                Set<String> allOwners = owners(roleSources);
                Set<String> owners = new TreeSet<String>();
                for (String path : roleSources.keySet()) if (sources.containsKey(path))
                    owners.addAll(owners(Collections.singletonMap(path, roleSources.get(path))));
                for (String affected : abiClosure) {
                    String owner = ownerOf(affected + ".class", allOwners);
                    if (owner != null) owners.add(owner);
                }
                TreeMap<String,byte[]> baselineSources = new TreeMap<String,byte[]>();
                for (String path : roleSources.keySet()) if (Files.exists(safe(target, path), LinkOption.NOFOLLOW_LINKS))
                    baselineSources.put(path, bounded(file(target, path), MAX_SOURCE));
                Map<String,byte[]> baseline = baselineSources.isEmpty() ? Collections.<String,byte[]>emptyMap()
                    : compile(target, stage.resolve("baseline-" + scope + "-" + name.replace('.', '-')), archive, compilation,
                        baselineSources, inputHashes, Collections.<String,byte[]>emptyMap(), stage.resolve("baseline-dependencies"));
                Set<String> checkedOwners = new TreeSet<String>(reverify ? allOwners : owners);
                for (String path : roleSources.keySet()) if (verificationSources.containsKey(path)) checkedOwners.addAll(owners(Collections.singletonMap(path, roleSources.get(path))));
                for (String path : beforeEntries.keySet()) if (path.startsWith("META-INF/versions/")
                    && owned(path.replaceFirst("^META-INF/versions/[0-9]+/", ""), checkedOwners))
                    throw failure(path, "A versioned target class overlaps an updated map owner or verification source; it requires a reviewed multi-release integration.");
                Set<String> originalOwnerClasses = new TreeSet<String>(), baselineOwnerClasses = new TreeSet<String>();
                for (String path : beforeEntries.keySet()) if (owned(path, checkedOwners)) originalOwnerClasses.add(path);
                for (String path : baseline.keySet()) if (owned(path, checkedOwners)) baselineOwnerClasses.add(path);
                if (!originalOwnerClasses.equals(baselineOwnerClasses)) throw failure(destination,
                    "Target source class inventory differs from active bytecode; newly discovered source classes cannot replace or shadow custom code.");
                refuseDependencyShadows(target, compilation, destination, originalOwnerClasses);
                for (Map.Entry<String,byte[]> old : beforeEntries.entrySet()) if (owned(old.getKey(), checkedOwners)) {
                    byte[] rebuilt = baseline.get(old.getKey());
                    if (rebuilt == null || !equivalentClass(old.getKey(), old.getValue(), rebuilt))
                        throw failure(old.getKey(), "Active target bytecode differs from its source; rebuilding it could discard custom behavior.");
                }
                TreeMap<String,byte[]> selectedClasses = new TreeMap<String,byte[]>();
                for (Map.Entry<String,byte[]> entry : classes.entrySet()) if (owned(entry.getKey(), owners)) {
                    byte[] old = beforeEntries.get(entry.getKey());
                    selectedClasses.put(entry.getKey(), old != null && equivalentClass(entry.getKey(), old, entry.getValue()) ? old : entry.getValue());
                }
                classes = selectedClasses;
                refuseDependencyShadows(target, compilation, destination, classes.keySet());
                for (String affected : abiClosure) if (beforeEntries.containsKey(affected + ".class") && ownerOf(affected + ".class", allOwners) == null)
                    throw failure(affected, "Prebuilt target code depends on changed map fields but has no source for recompilation.");
                byte[] merged = classes.isEmpty() && object(compilation.get("manifestAttributes")).isEmpty()
                    ? beforeBytes : merge(beforeEntries, classes, owners, object(compilation.get("manifestAttributes")));
                outputs.put(destination, merged);
                Map<String,Object> evidence = new LinkedHashMap<String,Object>();
                evidence.put("relativePath", destination); evidence.put("beforeSha256", hash(beforeBytes));
                evidence.put("sha256", hash(merged)); evidence.put("changedClassOwners", new ArrayList<String>(owners));
                evidence.put("retainedEntriesSha256", retainedFingerprint(beforeEntries, owners));
                if (!Arrays.equals(beforeBytes, merged) && !normalizedNotices.isEmpty()) evidence.put("normalizedIdenticalLegalNotices", normalizedNotices);
                archiveEvidence.add(evidence);
            }
        } finally { deleteOwnedStage(stage); }
        if (!compiledScopes.equals(new HashSet<String>(Arrays.asList("server", "client"))))
            throw failure(DESCRIPTOR, "Map integration must compile and verify both server and client.");
        for (Object raw : inputInventories) for (Object path : array(object(raw).get("paths")))
            if (!inputHashes.containsKey(path)) throw failure((String)path, "Compilation input appeared while the source inventory was collected.");
        for (Map.Entry<String,String> input : inputHashes.entrySet())
            if (!input.getValue().equals(WorldBuilderHashes.sha256(file(target, input.getKey()))))
                throw failure(input.getKey(), "Target source or dependency changed during map compilation.");
        Map<String,Object> installed = new LinkedHashMap<String,Object>();
        installed.put("schemaVersion", Long.valueOf(1));
        installed.put("manifestType", "world-builder-installed-target-map-integration");
        installed.put("integrationId", descriptor.get("integrationId")); installed.put("adapterId", adapterId);
        installed.put("descriptorSha256", WorldBuilderHashes.sha256(descriptorPath));
        installed.put("encodingVersions", descriptor.get("encodingVersions"));
        installed.put("archives", archiveEvidence);
        List<Object> sourceEvidence = new ArrayList<Object>();
        verificationSources.putAll(sources);
        for (Map.Entry<String,byte[]> entry : verificationSources.entrySet()) {
            Map<String,Object> value = new LinkedHashMap<String,Object>();
            value.put("relativePath", entry.getKey()); value.put("sha256", hash(entry.getValue())); sourceEvidence.add(value);
        }
        installed.put("sources", sourceEvidence);
        installed.put("beforeInputs", inputHashes);
        installed.put("inputInventories", inputInventories);
        verifyInventories(target, inputInventories);
        outputs.put(INSTALLED, WorldBuilderJsonDocuments.pretty(installed).getBytes(StandardCharsets.UTF_8));
        return new Result(adapterId, outputs, inputHashes, encodings(descriptor));
    }

    static String removeOwnedBuildGuard(String source) throws WorldBuilderContractException {
        String property = "world.builder.pinned.host.runtime";
        if (!source.contains(property)) {
            if (source.contains("world.builder.installed.runtime") || source.contains("world.builder.installed.client"))
                throw failure("server/build.xml", "An older runtime build guard needs a reviewed migration before target recompilation.");
            return source;
        }
        String attr = " unless=\"" + property + "\"";
        String declaration = "<available file=\"conf/world-builder/installed-runtime-capability-v3.json\" property=\"" + property + "\"/>";
        if (count(source, attr) != 1 || count(source, declaration) != 1 || count(source, property) != 2)
            throw failure("server/build.xml", "The installed runtime build guard differs from the exact World Builder form.");
        Matcher targets = Pattern.compile("<target\\b[^>]*>").matcher(source); boolean found = false;
        while (targets.find()) if (targets.group().contains(attr)) {
            if (!Pattern.compile("\\bname\\s*=\\s*['\"]compile_core['\"]").matcher(targets.group()).find())
                throw failure("server/build.xml", "Runtime build guard is attached to an unrecognized target.");
            found = true;
        }
        if (!found) throw failure("server/build.xml", "Runtime build guard does not identify compile_core.");
        return source.replace(attr, "").replace(declaration, "")
            .replace("<!-- Preserve the verified World Builder core.jar during target launches. -->", "");
    }
    private static int count(String source, String text) { int result = 0; for (int at = source.indexOf(text); at >= 0; at = source.indexOf(text, at + text.length())) result++; return result; }

    static List<WorldBuilderAdaptiveMutationProfile.Action> actions(Path target, Result result) throws IOException, WorldBuilderContractException {
        List<WorldBuilderAdaptiveMutationProfile.Action> actions = new ArrayList<WorldBuilderAdaptiveMutationProfile.Action>();
        List<String> paths = new ArrayList<String>(result.outputs.keySet());
        Collections.sort(paths, Comparator.comparingInt(path -> path.equals(INSTALLED) ? 2 : path.endsWith(".jar") ? 1 : 0));
        for (String path : paths) {
            byte[] content = result.outputs.get(path); Path existing = safe(target, path);
            WorldBuilderAdaptiveMutationProfile.FileState before = WorldBuilderAdaptiveMutationProfile.FileState.absent();
            if (Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
                existing = file(target, path);
                before = WorldBuilderAdaptiveMutationProfile.FileState.present(Files.size(existing), WorldBuilderHashes.sha256(existing));
            }
            if (before.present && before.size == content.length && before.sha256.equals(hash(content))) continue;
            String role = role(path);
            actions.add(new WorldBuilderAdaptiveMutationProfile.Action(role, path, before,
                WorldBuilderAdaptiveMutationProfile.FileState.present(content.length, hash(content)),
                "package/activation/targeted/" + role + ".bin", before.present ? "backups/{transaction}/before/" + path : "", true, content));
        }
        return actions;
    }

    static void writeEvidence(WorldBuilderAdaptiveMutationProfile.Plan plan, Path backup) throws IOException, WorldBuilderContractException {
        for (WorldBuilderAdaptiveMutationProfile.Action action : plan.actions) if (action.role.startsWith(ROLE)) {
            if (action.generatedContent == null) throw failure(action.destinationRelativePath, "Targeted output has no generated payload.");
            Path path = safe(backup, "content/targeted/" + action.role + ".bin"); Files.createDirectories(path.getParent());
            Files.write(path, action.generatedContent, StandardOpenOption.CREATE_NEW);
            try (java.nio.channels.FileChannel channel = java.nio.channels.FileChannel.open(path, StandardOpenOption.WRITE)) { channel.force(true); }
        }
    }

    static void verifyInputs(WorldBuilderAdaptiveMutationProfile.Plan plan) throws IOException, WorldBuilderContractException {
        for (WorldBuilderAdaptiveMutationProfile.Action action : plan.actions) if (INSTALLED.equals(action.destinationRelativePath) && action.role.startsWith(ROLE)) {
            Map<String,Object> receipt;
            try { receipt = WorldBuilderJsonDocuments.readObject(action.generatedContent, INSTALLED); }
            catch (WorldBuilderDiscoveryException invalid) { throw failure(INSTALLED, "Generated targeted integration receipt is malformed."); }
            verifyInventories(plan.targetRoot, array(receipt.get("inputInventories")));
            for (Map.Entry<String,Object> input : object(receipt.get("beforeInputs")).entrySet())
                if (!input.getValue().equals(WorldBuilderHashes.sha256(file(plan.targetRoot, input.getKey()))))
                    throw failure(input.getKey(), "Target compilation input changed after preview.");
        }
    }

    static WorldBuilderAdaptiveMutationProfile.Action restoreAction(Map<String,Object> value, Path project, String transactionId)
        throws IOException, WorldBuilderContractException {
        String destination = string(value, "destinationRelativePath"), suppliedRole = string(value, "role");
        WorldBuilderPortablePath.require(destination, "target-map-integration");
        boolean allowed = destination.equals(INSTALLED) || destination.equals("server/build.xml") || destination.equals("server/core.jar") || destination.equals("server/plugins.jar")
            || destination.equals("Client_Base/Open_RSC_Client.jar") || destination.equals("client/Open_RSC_Client.jar")
            || destination.matches("(?:server|Client_Base|client)/src/[A-Za-z0-9_$/]+\\.java");
        if (!allowed || !role(destination).equals(suppliedRole)
            || !("package/activation/targeted/" + suppliedRole + ".bin").equals(value.get("contentRelativePath"))
            || !Boolean.TRUE.equals(value.get("activation"))) throw failure(destination, "Targeted recovery action is outside its bounded destination contract.");
        Map<String,Object> beforeValue = object(value.get("before")), afterValue = object(value.get("after"));
        boolean present = Boolean.TRUE.equals(beforeValue.get("present"));
        WorldBuilderAdaptiveMutationProfile.FileState before = present
            ? WorldBuilderAdaptiveMutationProfile.FileState.present(WorldBuilderAdaptiveExporter.integer(beforeValue, "size"), string(beforeValue, "sha256"))
            : WorldBuilderAdaptiveMutationProfile.FileState.absent();
        String backup = present ? "backups/" + transactionId + "/before/" + destination : "";
        if (!backup.equals(value.get("backupRelativePath")) || !Boolean.TRUE.equals(afterValue.get("present")))
            throw failure(destination, "Targeted recovery preimage declaration differs.");
        byte[] content = bounded(file(project, "backups/" + transactionId + "/content/targeted/" + suppliedRole + ".bin"), MAX_ARCHIVE);
        if (WorldBuilderAdaptiveExporter.integer(afterValue, "size") != content.length || !hash(content).equals(afterValue.get("sha256")))
            throw failure(destination, "Persisted targeted output differs from the exact reviewed transaction.");
        return new WorldBuilderAdaptiveMutationProfile.Action(suppliedRole, destination, before,
            WorldBuilderAdaptiveMutationProfile.FileState.present(content.length, hash(content)),
            (String)value.get("contentRelativePath"), backup, true, content);
    }
    private static String role(String path) { return ROLE + hash(path.getBytes(StandardCharsets.UTF_8)).substring(0, 24); }

    static String transform(String source, Map<String,Object> spec, String path) throws WorldBuilderContractException, AdapterMismatch {
        String result = source;
        List<?> edits = array(spec.get("edits"));
        if (edits.isEmpty() || edits.size() > 128) throw failure(path, "Unbounded or empty map source transform.");
        for (Object raw : edits) {
            Map<String,Object> edit = object(raw);
            exactKeys(edit, "before", "after", "occurrences");
            String before = string(edit, "before"), after = string(edit, "after");
            if (!(edit.get("occurrences") instanceof Long) || ((Long)edit.get("occurrences")) < 1L || ((Long)edit.get("occurrences")) > 32L || before.isEmpty() || after.isEmpty() || before.equals(after))
                throw failure(path, "Map source edits must have one distinct nonempty executable anchor.");
            int expected = ((Long)edit.get("occurrences")).intValue();
            List<Integer> beforeAt = executableIndexes(result, before), afterAt = executableIndexes(result, after);
            if (afterAt.size() == expected) {
                boolean enclosed = true;
                for (Integer index : beforeAt) { boolean inside = false; for (Integer start : afterAt) inside |= index >= start && index + before.length() <= start + after.length(); enclosed &= inside; }
                if (enclosed) continue;
            }
            if (beforeAt.size() != expected || !afterAt.isEmpty()) throw new AdapterMismatch("Map hook differs or is ambiguous at " + path + " (" + string(spec, "transformId") + ")");
            for (int i = beforeAt.size() - 1; i >= 0; i--) { int at = beforeAt.get(i); result = result.substring(0, at) + after + result.substring(at + before.length()); }

        }
        return result;
    }

    /** Exact literal anchors must start in executable source, never a comment or literal. */
    static int executableIndex(String source, String anchor) throws WorldBuilderContractException {
        List<Integer> positions = executableIndexes(source, anchor);
        return positions.size() == 1 ? positions.get(0) : positions.isEmpty() ? -1 : -2;
    }
    private static List<Integer> executableIndexes(String source, String anchor) throws WorldBuilderContractException {
        if (anchor.isEmpty()) throw failure("source", "Empty executable anchor.");
        boolean[] executable = executablePositions(source); List<Integer> result = new ArrayList<Integer>();
        for (int at = source.indexOf(anchor); at >= 0; at = source.indexOf(anchor, at + anchor.length())) {
            int first = at; while (first < at + anchor.length() && Character.isWhitespace(source.charAt(first))) first++;
            if (first < at + anchor.length() && executable[first]) result.add(at);
        }
        return result;
    }

    private static boolean executableContains(String source, String fragment) throws WorldBuilderContractException {
        return !executableIndexes(source, fragment).isEmpty();
    }

    private static boolean[] executablePositions(String source) throws WorldBuilderContractException {
        if (source.length() > MAX_SOURCE) throw failure("source", "Map source exceeds its size bound.");
        boolean[] values = new boolean[source.length()];
        for (int at = 0; at < source.length();) {
            if (source.startsWith("//", at)) { int end = source.indexOf('\n', at + 2); int next = end < 0 ? source.length() : end + 1; rejectUnicodeOutsideLiteral(source.substring(at, next)); at = next; continue; }
            if (source.startsWith("/*", at)) { int end = source.indexOf("*/", at + 2); if (end < 0) throw failure("source", "Unterminated Java comment."); rejectUnicodeOutsideLiteral(source.substring(at, end + 2)); at = end + 2; continue; }
            char ch = source.charAt(at);
            if (ch == '"' || ch == '\'') {
                if (source.startsWith("\"\"\"", at)) throw failure("source", "Text blocks are outside this Java source adapter.");
                at++; boolean closed = false;
                while (at < source.length()) {
                    char next = source.charAt(at++);
                    if (next == '\\') {
                        if (at < source.length() && source.charAt(at) == 'u') {
                            while (at < source.length() && source.charAt(at) == 'u') at++;
                            if (at + 4 > source.length()) throw failure("source", "Malformed Java Unicode literal.");
                            int decoded;
                            try { decoded = Integer.parseInt(source.substring(at, at + 4), 16); }
                            catch (NumberFormatException invalid) { throw failure("source", "Malformed Java Unicode literal."); }
                            if (decoded == 34 || decoded == 39 || decoded == 92 || decoded == 10 || decoded == 13)
                                throw failure("source", "Structural Java Unicode escapes require a reviewed lexical adapter.");
                            at += 4;
                        } else at++;
                    } else if (next == ch) { closed = true; break; }
                }
                if (!closed) throw failure("source", "Unterminated Java literal.");
                continue;
            }
            if (ch == '\\' && at + 1 < source.length() && source.charAt(at + 1) == 'u')
                throw failure("source", "Structural Java Unicode escapes require a reviewed lexical adapter.");
            values[at++] = true;
        }
        return values;
    }

    private static void rejectUnicodeOutsideLiteral(String source) throws WorldBuilderContractException {
        for (int at = 0; at < source.length();) {
            if (source.charAt(at) != '\\') { at++; continue; }
            int start = at; while (at < source.length() && source.charAt(at) == '\\') at++;
            if ((at - start) % 2 == 1 && at < source.length() && source.charAt(at) == 'u')
                throw failure("source", "Structural Java Unicode escapes require a reviewed lexical adapter.");
        }
    }

    private static Map<String,byte[]> compile(Path target, Path stage, Path archive, Map<String,Object> spec,
        Map<String,byte[]> sources, Map<String,String> inputs, Map<String,byte[]> outputs, Path rootStage) throws IOException, WorldBuilderContractException {
        if (sources.isEmpty()) throw failure(DESCRIPTOR, "Each map compilation requires reviewed sources.");
        long totalSourceBytes = 0;
        for (byte[] bytes : sources.values()) totalSourceBytes += bytes.length;
        if (totalSourceBytes > 128L * 1024 * 1024) throw failure(DESCRIPTOR, "Target compiler source set exceeds 128 MiB.");
        String runtimeLevel = System.getProperty("java.specification.version", "0");
        try { if (Integer.parseInt(runtimeLevel) < 17) throw failure("java", "Targeted integration requires the application Java 17 compiler runtime."); }
        catch (NumberFormatException invalid) { throw failure("java", "Targeted integration requires the application Java 17 compiler runtime."); }
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) throw failure("java", "This World Builder runtime has no Java compiler for a targeted upgrade.");

        String level = string(spec, "sourceLevel");
        if (!level.equals(string(spec, "targetLevel")) || !Arrays.asList("8", "11", "17").contains(level))
            throw failure(DESCRIPTOR, "Unsupported map integration Java level.");
        Files.createDirectories(stage);
        Path out = Files.createDirectory(stage.resolve("classes"));
        Path empty = Files.createDirectory(stage.resolve("empty-sourcepath"));
        List<File> sourceFiles = new ArrayList<File>();
        for (Map.Entry<String,byte[]> entry : sources.entrySet()) {
            Path path = safe(stage, entry.getKey()); Files.createDirectories(path.getParent()); Files.write(path, entry.getValue(), StandardOpenOption.CREATE_NEW);
            sourceFiles.add(path.toFile());
        }
        List<String> classpath = new ArrayList<String>();
        if (spec.containsKey("dependencyArchives")) for (Object raw : array(spec.get("dependencyArchives"))) {
            if (!(raw instanceof String) || !Arrays.asList("server/core.jar", "server/plugins.jar").contains(raw)) throw failure(DESCRIPTOR, "Unsupported dependent target archive.");
            String path = (String)raw;
            inputs.put(path, WorldBuilderHashes.sha256(file(target, path)));
            if (outputs.containsKey(path)) {
                Path staged = safe(rootStage, "rebuilt-dependencies/" + path); Files.createDirectories(staged.getParent());
                if (!Files.exists(staged)) Files.write(staged, outputs.get(path), StandardOpenOption.CREATE_NEW);
                classpath.add(staged.toString());
            } else classpath.add(file(target, path).toString());
        }
        classpath.add(archive.toString());
        for (Object raw : array(spec.get("dependencyDirectories"))) {
            if (!(raw instanceof String) || !Arrays.asList("server/lib", "PC_Client/lib", "Client_Base/lib", "client/lib").contains(raw))
                throw failure(DESCRIPTOR, "Unsupported map compiler dependency directory.");
            Path directory = safe(target, (String)raw);
            if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) continue;
            if (Files.isSymbolicLink(directory) || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS))
                throw failure((String)raw, "Map compiler dependency directory is unsafe.");
            TreeSet<String> names = new TreeSet<String>();
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, "*.jar")) {
                for (Path jar : stream) { names.add(jar.getFileName().toString()); if (names.size() > 256) throw failure((String)raw, "Too many compiler dependencies."); }
            }
            for (String name : names) {
                String relative = raw + "/" + name; Path jar = file(target, relative);
                entries(jar, relative, false); inputs.put(relative, WorldBuilderHashes.sha256(jar)); classpath.add(jar.toString());
            }
        }
        for (String dependency : classpath) {
            try (JarFile jar = new JarFile(dependency, false)) {
                Manifest manifest = jar.getManifest();
                if (manifest != null) {
                    String implicit = manifest.getMainAttributes().getValue(Attributes.Name.CLASS_PATH);
                    if (implicit != null && !implicit.trim().isEmpty()) throw failure(dependency,
                        "Implicit archive Class-Path dependencies need a reviewed explicit compiler adapter.");
                }
            }
        }
        List<String> options = new ArrayList<String>(Arrays.asList("-proc:none", "-implicit:none", "-encoding", "UTF-8",
            "-source", level, "-target", level, "-classpath", String.join(File.pathSeparator, classpath), "-sourcepath", empty.toString(), "-d", out.toString()));
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<JavaFileObject>();
        try (StandardJavaFileManager manager = compiler.getStandardFileManager(diagnostics, Locale.ROOT, StandardCharsets.UTF_8)) {
            if (!Boolean.TRUE.equals(compiler.getTask(null, manager, diagnostics, options, null,
                manager.getJavaFileObjectsFromFiles(sourceFiles)).call())) {
                StringBuilder reason = new StringBuilder(); int count = 0;
                for (Diagnostic<? extends JavaFileObject> diagnostic : diagnostics.getDiagnostics()) {
                    if (diagnostic.getKind() != Diagnostic.Kind.ERROR) continue;
                    if (++count > 8) break;
                    reason.append(diagnostic.getMessage(Locale.ROOT)).append("; ");
                }
                throw failure("target-sources", "Targeted map integration did not compile against this target: " + reason);
            }
        }
        TreeMap<String,byte[]> result = new TreeMap<String,byte[]>();
        try (java.util.stream.Stream<Path> walk = Files.walk(out)) {
            Iterator<Path> iterator = walk.iterator();
            while (iterator.hasNext()) { Path path = iterator.next(); if (Files.isDirectory(path)) continue;
                String name = out.relativize(path).toString().replace('\\', '/');
                if (!name.endsWith(".class") || result.size() >= 4096) throw failure(name, "Unexpected map compiler output.");
                result.put(name, bounded(path, MAX_ENTRY));
            }
        }
        return result;
    }

    static Set<String> owners(Map<String,byte[]> sources) throws WorldBuilderContractException {
        TreeSet<String> result = new TreeSet<String>();
        for (Map.Entry<String,byte[]> entry : sources.entrySet()) {
            String source = utf8(entry.getValue(), entry.getKey());
            List<String> tokens;
            try { tokens = WorldBuilderNpcVisualJava.tokens(source); } catch (IllegalArgumentException invalid) { throw failure(entry.getKey(), invalid.getMessage()); }
            int depth = 0; String pkg = ""; boolean packageSeen = false;
            for (int i = 0; i < tokens.size(); i++) {
                String token = tokens.get(i);
                if (depth == 0 && "package".equals(token)) {
                    if (packageSeen) throw failure(entry.getKey(), "Duplicate source package."); packageSeen = true;
                    StringBuilder value = new StringBuilder();
                    while (++i < tokens.size() && !";".equals(tokens.get(i))) value.append(tokens.get(i));
                    pkg = value.toString().replace('.', '/') + "/";
                } else if (depth == 0 && Arrays.asList("class", "interface", "enum").contains(token) && (i == 0 || !".".equals(tokens.get(i - 1)))) {
                    if (++i >= tokens.size() || !WorldBuilderNpcVisualJava.identifier(tokens.get(i))) throw failure(entry.getKey(), "Invalid top-level class.");
                    String owner = pkg + tokens.get(i);
                    if (!result.add(owner)) throw failure(entry.getKey(), "Duplicate compilation owner.");
                } else if ("{".equals(token)) depth++;
                else if ("}".equals(token)) depth--;
                if (depth < 0) throw failure(entry.getKey(), "Unbalanced compilation source.");
            }
            if (depth != 0) throw failure(entry.getKey(), "Unbalanced compilation source.");
        }
        if (result.isEmpty()) throw failure("target-sources", "No map compilation owners.");
        return result;
    }

    static byte[] merge(Map<String,byte[]> original, Map<String,byte[]> classes, Set<String> owners,
        Map<String,Object> attributes) throws IOException, WorldBuilderContractException {
        TreeMap<String,byte[]> merged = new TreeMap<String,byte[]>(original);
        for (String name : original.keySet()) {
            if (name.startsWith("META-INF/versions/") && owned(name.replaceFirst("^META-INF/versions/[0-9]+/", ""), owners))
                throw failure(name, "A versioned target class overlaps an updated map owner; it requires a reviewed multi-release integration.");
            if (owned(name, owners)) merged.remove(name);
        }
        for (Map.Entry<String,byte[]> entry : classes.entrySet()) {
            if (!owned(entry.getKey(), owners)) throw failure(entry.getKey(), "Compiler emitted a class outside the reviewed map sources.");
            merged.put(entry.getKey(), entry.getValue());
        }
        for (String owner : owners) if (!classes.containsKey(owner + ".class")) throw failure(owner, "Compiler did not emit a reviewed source owner.");
        if (!attributes.isEmpty()) {
            byte[] oldManifest = merged.get("META-INF/MANIFEST.MF");
            Manifest manifest = oldManifest == null ? new Manifest() : new Manifest(new ByteArrayInputStream(oldManifest));
            if (manifest.getMainAttributes().getValue(Attributes.Name.MANIFEST_VERSION) == null) manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
            for (Map.Entry<String,Object> attribute : attributes.entrySet()) {
                if (!Arrays.asList("World-Builder-Floor-Semantics", "World-Builder-Installed-Floors", "World-Builder-Map-Integration").contains(attribute.getKey())
                    || !(attribute.getValue() instanceof String) || ((String)attribute.getValue()).length() > 128)
                    throw failure(DESCRIPTOR, "Manifest edit is outside the map capability contract.");
                manifest.getMainAttributes().putValue(attribute.getKey(), (String)attribute.getValue());
            }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(); manifest.write(bytes); merged.put("META-INF/MANIFEST.MF", bytes.toByteArray());
        }
        if (!retainedFingerprint(original, owners).equals(retainedFingerprint(merged, owners))) throw failure("target-archive", "Non-map archive entries changed.");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (Map.Entry<String,byte[]> entry : merged.entrySet()) { ZipEntry item = new ZipEntry(entry.getKey()); item.setTime(0L); zip.putNextEntry(item); zip.write(entry.getValue()); zip.closeEntry(); }
        }
        if (bytes.size() > MAX_ARCHIVE) throw failure("target-archive", "Targeted archive exceeds its bound.");
        return bytes.toByteArray();
    }

    private static String retainedFingerprint(Map<String,byte[]> entries, Set<String> owners) {
        java.security.MessageDigest digest = WorldBuilderHashes.newDigest();
        for (Map.Entry<String,byte[]> entry : new TreeMap<String,byte[]>(entries).entrySet()) {
            if (owned(entry.getKey(), owners) || "META-INF/MANIFEST.MF".equals(entry.getKey())) continue;
            WorldBuilderHashes.updateText(digest, entry.getKey()); WorldBuilderHashes.updateText(digest, hash(entry.getValue()));
        }
        return WorldBuilderHashes.hex(digest.digest());
    }

    private static void refuseDependencyShadows(Path target, Map<String,Object> compilation, String destination, Set<String> emitted)
        throws IOException, WorldBuilderContractException {
        Set<String> dependencies = new TreeSet<String>();
        if (compilation.containsKey("dependencyArchives")) for (Object raw : array(compilation.get("dependencyArchives"))) dependencies.add((String)raw);
        for (Object raw : array(compilation.get("dependencyDirectories"))) {
            Path directory = safe(target, (String)raw); if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) continue;
            fileRoot(target, (String)raw);
            try (DirectoryStream<Path> files = Files.newDirectoryStream(directory)) {
                for (Path path : files) if (path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar")) dependencies.add(raw + "/" + path.getFileName());
            }
        }
        dependencies.remove(destination);
        for (String dependency : dependencies) for (String name : entries(file(target, dependency), dependency, false).keySet()) {
            String base = name.replaceFirst("^META-INF/versions/[0-9]+/", "");
            if (emitted.contains(base)) throw failure(base, "A rebuilt map class also exists in target dependency " + dependency + "; replacing it could shadow custom behavior.");
        }
    }

    /** Conservative transitive class linkage closure, including inherited field owners across JARs. */
    private static Set<String> abiClosure(Path target, String clientRoot, Map<String,Object> adapter)
        throws IOException, WorldBuilderContractException {
        Set<String> affected = new TreeSet<String>(); Set<String> targetArchives = new TreeSet<String>(); Set<String> archives = new TreeSet<String>();
        for (Object raw : array(adapter.get("compilation"))) {
            Map<String,Object> row = object(raw);
            if (row.containsKey("abiChangedClasses")) for (Object value : array(row.get("abiChangedClasses"))) {
                if (!(value instanceof String) || !((String)value).matches("[A-Za-z0-9_$/]+")) throw failure(DESCRIPTOR, "Invalid ABI class name."); affected.add((String)value);
            }
            String archive = root(scope(row), clientRoot) + "/" + string(row, "archiveRelativePath"); targetArchives.add(archive); archives.add(archive);
            for (Object directory : array(row.get("dependencyDirectories"))) {
                if (!(directory instanceof String)) throw failure(DESCRIPTOR, "Invalid dependency directory.");
                Path path = safe(target, (String)directory); if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) continue;
                fileRoot(target, (String)directory);
                try (DirectoryStream<Path> children = Files.newDirectoryStream(path)) {
                    for (Path child : children) if (child.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar"))
                        archives.add(directory + "/" + child.getFileName());
                }
            }
        }
        if (affected.isEmpty()) return affected;
        if (archives.size() > 512) throw failure(DESCRIPTOR, "ABI dependency inventory exceeds its bound.");
        Map<String,Set<String>> users = new HashMap<String,Set<String>>(); Map<String,Set<String>> locations = new HashMap<String,Set<String>>(); Set<String> versioned = new HashSet<String>(); int count = 0;
        for (String path : archives) for (Map.Entry<String,byte[]> entry : entries(file(target, path), path, targetArchives.contains(path)).entrySet()) {
            if (!entry.getKey().endsWith(".class")) continue;
            String name = entry.getKey().replaceFirst("^META-INF/versions/[0-9]+/", "");
            String owner = name.substring(0, name.length() - 6);
            if (!name.equals(entry.getKey())) versioned.add(owner);
            if (++count > 150000) throw failure(path, "ABI class inventory exceeds its bound.");
            locations.computeIfAbsent(owner, key -> new TreeSet<String>()).add(path);
            for (String reference : classReferences(entry.getValue())) users.computeIfAbsent(reference, key -> new TreeSet<String>()).add(owner);
        }
        ArrayDeque<String> pending = new ArrayDeque<String>(affected);
        while (!pending.isEmpty()) {
            Set<String> dependents = users.get(pending.remove()); if (dependents == null) continue;
            for (String dependent : dependents) if (affected.add(dependent)) pending.add(dependent);
        }
        for (String owner : affected) {
            if (versioned.contains(owner)) throw failure(owner, "Changed map ABI reaches a versioned class requiring a reviewed multi-release integration.");
            Set<String> paths = locations.get(owner); if (paths == null) continue;
            if (paths.size() != 1 || !targetArchives.contains(paths.iterator().next()))
                throw failure(owner, "Changed map ABI reaches ambiguous or prebuilt dependency code without a reviewed source rebuild.");
        }
        return affected;
    }
    private static Set<String> classReferences(byte[] bytes) throws IOException, WorldBuilderContractException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
        if (in.readInt() != 0xcafebabe) throw failure("target-class", "Invalid classfile in target ABI closure.");
        in.readUnsignedShort(); in.readUnsignedShort(); int count = in.readUnsignedShort();
        String[] utf = new String[count]; int[] classNames = new int[count]; List<Integer> descriptors = new ArrayList<Integer>();
        for (int i = 1; i < count; i++) {
            int tag = in.readUnsignedByte();
            switch (tag) {
                case 1: utf[i] = in.readUTF(); break;
                case 3: case 4: in.readInt(); break;
                case 5: case 6: in.readLong(); i++; break;
                case 7: classNames[i] = in.readUnsignedShort(); break;
                case 8: case 19: case 20: in.readUnsignedShort(); break;
                case 9: case 10: case 11: case 17: case 18: in.readUnsignedShort(); in.readUnsignedShort(); break;
                case 12: in.readUnsignedShort(); descriptors.add(in.readUnsignedShort()); break;
                case 15: in.readUnsignedByte(); in.readUnsignedShort(); break;
                case 16: descriptors.add(in.readUnsignedShort()); break;
                default: throw failure("target-class", "Unsupported constant-pool entry in ABI closure.");
            }
        }
        Set<String> result = new TreeSet<String>();
        for (int name : classNames) if (name != 0) {
            if (name >= count || utf[name] == null) throw failure("target-class", "Invalid class reference.");
            if (utf[name].startsWith("[")) descriptorClasses(utf[name], result); else result.add(utf[name]);
        }
        for (Integer index : descriptors) { if (index < 1 || index >= count || utf[index] == null) throw failure("target-class", "Invalid type descriptor."); descriptorClasses(utf[index], result); }
        return result;
    }
    private static void descriptorClasses(String descriptor, Set<String> result) {
        Matcher matcher = Pattern.compile("L([^;]+);").matcher(descriptor); while (matcher.find()) result.add(matcher.group(1));
    }

    private static String ownerOf(String name, Set<String> owners) {
        for (String owner : owners) if (name.equals(owner + ".class") || name.startsWith(owner + "$")) return owner;
        return null;
    }
    private static Map<String,Object> inventory(Path target, String relative, String suffix, boolean recursive)
        throws IOException, WorldBuilderContractException {
        Path root = safe(target, relative); List<String> paths = new ArrayList<String>();
        if (Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            fileRoot(target, relative);
            try (java.util.stream.Stream<Path> walk = recursive ? Files.walk(root) : Files.list(root)) {
                Iterator<Path> iterator = walk.iterator();
                while (iterator.hasNext()) {
                    Path path = iterator.next();
                    if (Files.isSymbolicLink(path)) throw failure(relative, "Linked compilation input is unsupported.");
                    if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) continue;
                    if (!path.toString().toLowerCase(Locale.ROOT).endsWith(suffix)) continue;
                    String value = target.relativize(path).toString().replace('\\', '/'); file(target, value); paths.add(value);
                    if (paths.size() > 16000) throw failure(relative, "Compilation inventory exceeds its bound.");
                }
            }
        }
        Collections.sort(paths); Map<String,Object> value = new LinkedHashMap<String,Object>();
        value.put("relativePath", relative); value.put("suffix", suffix); value.put("recursive", Boolean.valueOf(recursive)); value.put("paths", paths); return value;
    }
    static void verifyInventories(Path target, List<?> inventories) throws IOException, WorldBuilderContractException {
        for (Object raw : inventories) {
            Map<String,Object> expected = object(raw);
            if (!expected.equals(inventory(target, string(expected, "relativePath"), string(expected, "suffix"), Boolean.TRUE.equals(expected.get("recursive")))))
                throw failure(string(expected, "relativePath"), "Compilation input inventory changed after preview.");
        }
    }

    private static boolean equivalentClass(String path, byte[] original, byte[] recompiled) throws WorldBuilderContractException {
        try { return WorldBuilderClassSemantics.equivalent(original, recompiled); }
        catch (IOException unsupported) { throw failure(path, "Target class is malformed or outside the reviewed bytecode comparison: " + unsupported.getMessage()); }
    }

    private static boolean owned(String name, Set<String> owners) {
        if (!name.endsWith(".class")) return false;
        for (String owner : owners) if (name.equals(owner + ".class") || name.startsWith(owner + "$")) return true;
        return false;
    }

    static Map<String,byte[]> entries(Path archive) throws IOException, WorldBuilderContractException {
        return entries(archive, archive.toString(), true);
    }
    private static Map<String,byte[]> entries(Path archive, String relative, boolean changing) throws IOException, WorldBuilderContractException {
        return WorldBuilderTargetArchive.read(archive, relative, changing, new TreeMap<String,Integer>());
    }

    static String normalizationSummary(List<WorldBuilderAdaptiveMutationProfile.Action> actions) {
        StringBuilder summary = new StringBuilder();
        for (WorldBuilderAdaptiveMutationProfile.Action action : actions) if (INSTALLED.equals(action.destinationRelativePath)
            && action.generatedContent != null && action.role.startsWith(ROLE)) {
            try {
                Map<String,Object> evidence = WorldBuilderJsonDocuments.readObject(action.generatedContent, INSTALLED);
                for (Object raw : array(evidence.get("archives"))) {
                    Map<String,Object> archive = object(raw);
                    if (!archive.containsKey("normalizedIdenticalLegalNotices")) continue;
                    for (Map.Entry<String,Object> notice : object(archive.get("normalizedIdenticalLegalNotices")).entrySet())
                        summary.append("Identical legal notice: ").append(archive.get("relativePath")).append("!/").append(notice.getKey())
                            .append(" — ").append(notice.getValue()).append(" occurrences become one; complete text bytes retained\n");
                }
            } catch (WorldBuilderDiscoveryException | WorldBuilderContractException invalid) {
                throw new IllegalStateException("Generated targeted integration evidence is invalid", invalid);
            }
        }
        return summary.toString();
    }

    static boolean evidenceRole(String role) {
        return Arrays.asList("installed-map-integration-proof", "installed-map-integration-source",
            "installed-map-integration-archive").contains(role);
    }

    /** Capture exactly the current files consumed by installed-proof verification. */
    static void inspectInstalledEvidence(WorldBuilderReadOnlyTarget target,
        WorldBuilderAdaptiveConfiguration configuration, List<WorldBuilderReadOnlyTarget.FileState> evidence)
        throws WorldBuilderContractException {
        if (!target.exists(INSTALLED)) return;
        try { verifyInstalled(target.root, WorldBuilderInstalledFloorContent.clientRoot(configuration), evidence); }
        catch (IOException invalid) { throw failure(INSTALLED, "Installed integration evidence cannot be read; restore the stable target and rediscover."); }
    }

    static List<Integer> verifyInstalled(Path project, Path target, String clientRoot) throws IOException, WorldBuilderContractException {
        return verifyInstalled(target, clientRoot, null);
    }
    private static List<Integer> verifyInstalled(Path target, String clientRoot,
        List<WorldBuilderReadOnlyTarget.FileState> evidence) throws IOException, WorldBuilderContractException {
        Path current = embeddedPayload();
        if (current == null) throw failure(DESCRIPTOR, "This application has no trusted targeted map integration payload.");
        try { return verifyInstalledPayload(current, target, clientRoot, evidence); }
        finally { if (current != null) deleteOwnedStage(current); }
    }
    static List<Integer> verifyInstalledPayload(Path project, Path target, String clientRoot) throws IOException, WorldBuilderContractException {
        return verifyInstalledPayload(project, target, clientRoot, null);
    }
    static List<Integer> verifyInstalledPayload(Path project, Path target, String clientRoot,
        List<WorldBuilderReadOnlyTarget.FileState> evidence) throws IOException, WorldBuilderContractException {
        WorldBuilderReadOnlyTarget checkedTarget = WorldBuilderReadOnlyTarget.open(target);
        WorldBuilderReadOnlyTarget.FileState proof = checkedTarget.requiredState("installed-map-integration-proof", INSTALLED);
        Map<String,Object> descriptor = read(file(project, "working/runtime/" + DESCRIPTOR)); requireDescriptor(descriptor);
        Map<String,Object> installed = read(file(target, INSTALLED));
        if (!"world-builder-installed-target-map-integration".equals(installed.get("manifestType"))
            || !Long.valueOf(1).equals(installed.get("schemaVersion"))
            || !descriptor.get("integrationId").equals(installed.get("integrationId"))
            || !WorldBuilderHashes.sha256(file(project, "working/runtime/" + DESCRIPTOR)).equals(installed.get("descriptorSha256")))
            throw failure(INSTALLED, "Installed targeted map integration differs from this project's reviewed contract.");
        Map<String,Object> matched = null;
        for (Object raw : array(descriptor.get("adapters"))) if (object(raw).get("adapterId").equals(installed.get("adapterId"))) matched = object(raw);
        if (matched == null) throw failure(INSTALLED, "Installed target adapter is not reviewed by this runtime.");
        Set<String> expectedSources = new TreeSet<String>(); Set<String> expectedArchives = new TreeSet<String>();
        for (String field : Arrays.asList("sources", "transforms", "requirements")) for (Object raw : array(matched.get(field))) expectedSources.add(targetPath(object(raw), clientRoot));
        for (Object raw : array(matched.get("compilation"))) {
            Map<String,Object> row = object(raw);
            if (row.containsKey("verificationSources")) for (Object path : array(row.get("verificationSources"))) expectedSources.add(root(scope(row), clientRoot) + "/" + path);
        }
        for (Object raw : array(matched.get("compilation"))) { Map<String,Object> row = object(raw); expectedArchives.add(root(scope(row), clientRoot) + "/" + string(row, "archiveRelativePath")); }
        Set<String> actualSources = new TreeSet<String>(); Set<String> actualArchives = new TreeSet<String>();
        for (String group : Arrays.asList("sources", "archives")) for (Object raw : array(installed.get(group))) {
            Map<String,Object> record = object(raw); String path = string(record, "relativePath");
            if (!("sources".equals(group) ? actualSources : actualArchives).add(path)) throw failure(INSTALLED, "Installed proof repeats a source or archive.");
        }
        if (!expectedSources.equals(actualSources) || !expectedArchives.equals(actualArchives))
            throw failure(INSTALLED, "Installed proof does not cover the complete paired source and archive inventory.");
        List<WorldBuilderReadOnlyTarget.FileState> verified = new ArrayList<WorldBuilderReadOnlyTarget.FileState>();
        verified.add(proof);
        // Validate the descriptor-derived path sets before reading target-declared
        // paths. beforeInputs/inputInventories describe historical compilation
        // inputs, not the current transformed source/archive proof dependency set.
        for (String group : Arrays.asList("sources", "archives")) for (Object raw : array(installed.get(group))) {
            Map<String,Object> record = object(raw); String path = string(record, "relativePath");
            WorldBuilderReadOnlyTarget.FileState state = checkedTarget.requiredState(
                "sources".equals(group) ? "installed-map-integration-source" : "installed-map-integration-archive", path);
            if (!string(record, "sha256").equals(state.sha256))
                throw failure(path, "Installed map integration changed; recapture and review a targeted upgrade before importing.");
            verified.add(state);
        }
        if (!proof.stableKey().equals(checkedTarget.requiredState(proof.role, INSTALLED).stableKey()))
            throw failure(INSTALLED, "Installed map integration proof changed during verification; rediscover the stable target.");
        if (evidence != null) for (WorldBuilderReadOnlyTarget.FileState state : verified) {
            WorldBuilderReadOnlyTarget.FileState existing = null;
            for (WorldBuilderReadOnlyTarget.FileState item : evidence)
                if (item.relativePath.equals(state.relativePath)) { existing = item; break; }
            if (existing == null) evidence.add(state);
            else if (existing.present != state.present || existing.size != state.size || !existing.sha256.equals(state.sha256))
                throw failure(state.relativePath, "Installed map integration evidence changed during discovery; rediscover the stable target.");
        }
        return encodings(descriptor);
    }

    static void refuseCompositionReplacement() throws WorldBuilderContractException {
        throw failure("target-map-integration", "This target needs a reviewed source integration adapter. Installing a generic game composition would not preserve its custom content.");
    }

    private static Path embeddedPayload() throws IOException, WorldBuilderContractException {
        String prefix = "/com/openrsc/worldbuilder/target-map-integration/";
        InputStream descriptorStream = WorldBuilderTargetMapIntegration.class.getResourceAsStream(prefix + "target-map-integration-v1.json");
        if (descriptorStream == null) return null;
        Path stage = Files.createTempDirectory("world-builder-map-payload-");
        try {
            Path descriptor = safe(stage, "working/runtime/" + DESCRIPTOR); Files.createDirectories(descriptor.getParent());
            try (InputStream input = descriptorStream) { Files.write(descriptor, bounded(input, MAX_SOURCE), StandardOpenOption.CREATE_NEW); }
            Map<String,Object> manifest = read(descriptor); requireDescriptor(manifest);
            Set<String> copied = new HashSet<String>();
            for (Object raw : array(manifest.get("adapters"))) for (Object item : array(object(raw).get("sources"))) {
                String path = string(object(item), "payloadRelativePath");
                if (!path.startsWith("server/conf/world-builder/target-map-source/")) throw failure(path, "Untrusted embedded payload path.");
                if (!copied.add(path)) continue;
                Path destination = safe(stage, "working/runtime/" + path); Files.createDirectories(destination.getParent());
                try (InputStream input = WorldBuilderTargetMapIntegration.class.getResourceAsStream(prefix + path)) {
                    if (input == null) throw failure(path, "Current tool is missing reviewed map integration source.");
                    Files.write(destination, bounded(input, MAX_SOURCE), StandardOpenOption.CREATE_NEW);
                }
            }
            return stage;
        } catch (IOException | WorldBuilderContractException failure) { deleteOwnedStage(stage); throw failure; }
    }
    private static Path fileRoot(Path root, String relative) throws IOException, WorldBuilderContractException {
        return WorldBuilderAdaptiveExporter.requireDirectory(root, relative, "target source root");
    }

    private static void exactKeys(Map<String,Object> value, String... required) throws WorldBuilderContractException {
        allowedKeys(value, Collections.<String>emptyList(), required);
    }
    private static void allowedKeys(Map<String,Object> value, List<String> optional, String... required) throws WorldBuilderContractException {
        Set<String> keys = new HashSet<String>(Arrays.asList(required));
        if (!value.keySet().containsAll(keys)) throw failure(DESCRIPTOR, "Target map contract omits required fields.");
        keys.addAll(optional);
        if (!keys.containsAll(value.keySet())) throw failure(DESCRIPTOR, "Target map contract contains unsupported fields.");
    }

    private static List<Integer> encodings(Map<String,Object> descriptor) throws WorldBuilderContractException {
        List<Integer> result = new ArrayList<Integer>();
        for (Object value : array(descriptor.get("encodingVersions"))) result.add(((Long)value).intValue());
        return Collections.unmodifiableList(result);
    }
    private static void requireDescriptor(Map<String,Object> descriptor) throws WorldBuilderContractException {
        exactKeys(descriptor, "schemaVersion", "manifestType", "integrationId", "loaderId", "protocolId", "encodingVersions", "adapters");
        if (!Long.valueOf(1).equals(descriptor.get("schemaVersion")) || !"world-builder-target-map-integration".equals(descriptor.get("manifestType"))
            || !"target-owned-layered-map-v1".equals(descriptor.get("integrationId"))
            || !"generic-signed-layered-loader-v7-blocking-base-color".equals(descriptor.get("loaderId"))
            || !"world-builder-native-layered-protocol-v2-u16-elevation".equals(descriptor.get("protocolId"))
            || !Arrays.asList(1L, 2L, 3L, 4L, 5L).equals(array(descriptor.get("encodingVersions")))
            || array(descriptor.get("adapters")).isEmpty() || array(descriptor.get("adapters")).size() > 16)
            throw failure(DESCRIPTOR, "Targeted map integration contract is unsupported.");
    }

    static String targetPath(Map<String,Object> spec, String clientRoot) throws WorldBuilderContractException {
        String relative = string(spec, "targetRelativePath");
        if (!relative.startsWith("src/") || !relative.endsWith(".java")) throw failure(relative, "Only reviewed Java source paths can be integrated.");
        WorldBuilderPortablePath.require(relative, "target-map-integration");
        return root(scope(spec), clientRoot) + "/" + relative;
    }
    private static String scope(Map<String,Object> spec) throws WorldBuilderContractException { String scope = string(spec, "scope"); if (!Arrays.asList("server", "client").contains(scope)) throw failure(DESCRIPTOR, "Unknown integration scope."); return scope; }
    private static String root(String scope, String client) throws WorldBuilderContractException { if (!Arrays.asList("client", "Client_Base").contains(client)) throw failure(client, "Unsupported client root."); return "server".equals(scope) ? "server" : client; }
    private static void putSource(Map<String,byte[]> sources, Map<String,String> scopes, String path, String scope, byte[] bytes) throws WorldBuilderContractException { utf8(bytes, path); if (sources.put(path, bytes) != null) throw failure(path, "Duplicate map integration source."); scopes.put(path, scope); }
    private static String source(Path root, String relative) throws IOException, WorldBuilderContractException, AdapterMismatch { Path path = safe(root, relative); if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) throw new AdapterMismatch("Required source absent at " + relative); return utf8(bounded(file(root, relative), MAX_SOURCE), relative); }
    private static String utf8(byte[] bytes, String path) throws WorldBuilderContractException { String value = new String(bytes, StandardCharsets.UTF_8); if (!Arrays.equals(bytes, value.getBytes(StandardCharsets.UTF_8))) throw failure(path, "Source is not valid UTF-8."); return value; }
    static Path safe(Path root, String relative) throws WorldBuilderContractException { return WorldBuilderPortablePath.resolveContained(root, relative, "target-map-integration"); }
    private static Path file(Path root, String relative) throws IOException, WorldBuilderContractException { return WorldBuilderReadOnlyTarget.open(root).requiredFile(relative); }
    private static byte[] bounded(Path path, int max) throws IOException, WorldBuilderContractException { if (Files.size(path) > max) throw failure(path.toString(), "Integration input exceeds its size bound."); try (InputStream input = Files.newInputStream(path)) { return bounded(input, max); } }
    private static byte[] bounded(InputStream input, int max) throws IOException, WorldBuilderContractException { ByteArrayOutputStream output = new ByteArrayOutputStream(); byte[] buffer = new byte[8192]; for (int count; (count = input.read(buffer)) >= 0;) { if (output.size() + count > max) throw failure("archive", "Integration entry exceeds its size bound."); output.write(buffer, 0, count); } return output.toByteArray(); }
    private static void deleteOwnedStage(Path stage) throws IOException { try (java.util.stream.Stream<Path> paths = Files.walk(stage)) { Iterator<Path> iterator = paths.sorted(Comparator.reverseOrder()).iterator(); while (iterator.hasNext()) Files.delete(iterator.next()); } }
    static Map<String,Object> read(Path path) throws IOException, WorldBuilderContractException { try { return WorldBuilderJsonDocuments.readObject(path); } catch (WorldBuilderDiscoveryException invalid) { throw failure(path.toString(), "Malformed targeted map integration evidence."); } }
    static Map<String,Object> object(Object value) throws WorldBuilderContractException { return WorldBuilderAdaptiveExporter.object(value, DESCRIPTOR); }
    static List<?> array(Object value) throws WorldBuilderContractException { return WorldBuilderAdaptiveExporter.array(value, DESCRIPTOR); }
    static String string(Map<String,Object> value, String key) throws WorldBuilderContractException { return WorldBuilderAdaptiveExporter.string(value, key); }
    private static String hash(byte[] bytes) { return WorldBuilderHashes.sha256(bytes); }
    static WorldBuilderContractException retiredShadowRuntime(String path) {
        return new WorldBuilderContractException(WorldBuilderErrorCodes.RUNTIME_UPGRADE_REQUIRED, "target-map-integration", path, false,
            "Target still contains retired class-shadowing runtime content at " + path
                + ". It can replace target-owned Player, Skills, Inventory, World, Mob, Npc, ActionSender, and OpcodeOut classes. Automatic targeted upgrade cannot consolidate this overlay.",
            "Keep the target offline and have its maintainer integrate intended overlay behavior into the maintained server sources and launch configuration, then verify the rebuilt server/client against the map contract. Do not merely delete the archive or repeatedly retry Upgrade Target Runtime.");
    }
    static WorldBuilderContractException failure(String path, String message) { return new WorldBuilderContractException(WorldBuilderErrorCodes.RUNTIME_UPGRADE_REQUIRED, "target-map-integration", path, false, message, "Keep the target offline and use the exact reviewed integration and transaction evidence; do not force the operation."); }
    static final class AdapterMismatch extends Exception { AdapterMismatch(String message) { super(message); } }
    static final class Result {
        final String adapterId; final Map<String,byte[]> outputs; final Map<String,String> inputs; final List<Integer> encodingVersions;
        Result(String adapterId, Map<String,byte[]> outputs, Map<String,String> inputs, List<Integer> encodingVersions) { this.adapterId = adapterId; this.outputs = outputs; this.inputs = inputs; this.encodingVersions = encodingVersions; }
    }
}
