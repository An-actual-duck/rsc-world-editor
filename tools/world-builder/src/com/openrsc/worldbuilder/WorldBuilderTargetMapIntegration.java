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
        try { return preparePayload(current == null ? project : current, target, clientRoot); }
        finally { if (current != null) deleteOwnedStage(current); }
    }

    static Result preparePayload(Path project, Path target, String clientRoot) throws IOException, WorldBuilderContractException {
        Path descriptorPath = file(project, "working/runtime/" + DESCRIPTOR);
        Map<String,Object> descriptor = read(descriptorPath);
        requireDescriptor(descriptor);
        List<String> refusals = new ArrayList<String>();
        Result selected = null;
        for (Object raw : array(descriptor.get("adapters"))) {
            Map<String,Object> adapter = object(raw);
            try {
                Result candidate = prepareAdapter(project, target, clientRoot, descriptor, adapter, descriptorPath);
                if (selected != null) throw failure(DESCRIPTOR, "More than one targeted map adapter matches this target.");
                selected = candidate;
            } catch (AdapterMismatch mismatch) { refusals.add(string(adapter, "adapterId") + ": " + mismatch.getMessage()); }
        }
        if (selected == null) throw failure(DESCRIPTOR,
            "No reviewed targeted map integration matches this server/client. " + String.join("; ", refusals));
        return selected;
    }

    private static Result prepareAdapter(Path project, Path target, String clientRoot, Map<String,Object> descriptor,
        Map<String,Object> adapter, Path descriptorPath) throws IOException, WorldBuilderContractException, AdapterMismatch {
        String adapterId = string(adapter, "adapterId");
        TreeMap<String,byte[]> sources = new TreeMap<String,byte[]>();
        TreeMap<String,String> scopes = new TreeMap<String,String>();
        TreeMap<String,String> inputHashes = new TreeMap<String,String>();
        List<Object> inputInventories = new ArrayList<Object>();
        for (Object raw : array(adapter.get("requirements"))) {
            Map<String,Object> requirement = object(raw);
            String path = targetPath(requirement, clientRoot);
            String content = source(target, path);
            if (requirement.containsKey("acceptedSourceSha256") && !array(requirement.get("acceptedSourceSha256")).contains(hash(content.getBytes(StandardCharsets.UTF_8))))
                throw new AdapterMismatch("Map source requirement differs from reviewed implementations at " + path);
            for (Object fragment : array(requirement.get("requiredFragments")))
                if (!(fragment instanceof String) || !executableContains(content, (String)fragment))
                    throw new AdapterMismatch("Required map hook is absent or ambiguous at " + path);
            inputHashes.put(path, hash(content.getBytes(StandardCharsets.UTF_8)));
        }
        for (Object raw : array(adapter.get("requiredEntryProbes"))) {
            Map<String,Object> probe = object(raw);
            String scope = scope(probe);
            String archivePath = root(scope, clientRoot) + "/" + ("server".equals(scope) ? "core.jar" : "Open_RSC_Client.jar");
            Map<String,byte[]> archive = entries(file(target, archivePath));
            String entry = string(probe, "entry");
            byte[] bytes = archive.get(entry);
            if (bytes == null) throw new AdapterMismatch("Required prior map integration is missing from " + archivePath + ": " + entry);
            String binary = new String(bytes, StandardCharsets.ISO_8859_1);
            for (Object marker : array(probe.get("markers"))) if (!(marker instanceof String) || !binary.contains((String)marker))
                throw new AdapterMismatch("Required prior map integration differs at " + entry);
        }
        for (Object raw : array(adapter.get("sources"))) {
            Map<String,Object> spec = object(raw);
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
            String destination = targetPath(spec, clientRoot);
            if (sources.containsKey(destination)) throw failure(DESCRIPTOR, "A source cannot be both replaced and transformed.");
            String before = source(target, destination);
            String after = transform(before, spec, destination);
            inputHashes.put(destination, hash(before.getBytes(StandardCharsets.UTF_8)));
            putSource(sources, scopes, destination, scope(spec), after.getBytes(StandardCharsets.UTF_8));
        }
        if (sources.isEmpty() || sources.size() > 128) throw failure(DESCRIPTOR, "Map source set is empty or exceeds its bound.");
        TreeMap<String,byte[]> outputs = new TreeMap<String,byte[]>(sources);
        Set<String> compiledScopes = new HashSet<String>();
        Set<String> compiledArchives = new HashSet<String>();
        List<Object> archiveEvidence = new ArrayList<Object>();
        Path stage = Files.createTempDirectory("world-builder-map-compile-");
        try {
            for (Object raw : array(adapter.get("compilation"))) {
                Map<String,Object> compilation = object(raw);
                String scope = scope(compilation);
                compiledScopes.add(scope);
                String name = string(compilation, "archiveRelativePath");
                if (!("server".equals(scope) ? Arrays.asList("core.jar", "plugins.jar") : Arrays.asList("Open_RSC_Client.jar")).contains(name))
                    throw failure(DESCRIPTOR, "Unsupported target archive destination.");
                String destination = root(scope, clientRoot) + "/" + name;
                if (!compiledArchives.add(destination)) throw failure(DESCRIPTOR, "Duplicate map compilation archive.");
                Path archive = file(target, destination);
                byte[] beforeBytes = bounded(archive, MAX_ARCHIVE);
                inputHashes.put(destination, hash(beforeBytes));
                Map<String,byte[]> beforeEntries = entries(archive);
                TreeMap<String,byte[]> roleSources = new TreeMap<String,byte[]>();
                List<?> roots = array(compilation.get("sourceRoots"));
                for (Object rawRoot : roots) {
                    if (!(rawRoot instanceof String) || !Arrays.asList("src", "plugins").contains(rawRoot)) throw failure(DESCRIPTOR, "Unsupported compiler source root.");
                    String relativeRoot = root(scope, clientRoot) + "/" + rawRoot;
                    for (String path : sources.keySet()) if (scope.equals(scopes.get(path)) && path.startsWith(relativeRoot + "/")) roleSources.put(path, sources.get(path));
                    if (!Boolean.TRUE.equals(compilation.get("compileAllSources"))) continue;
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
                for (Object rawRoot : roots) if (Boolean.TRUE.equals(compilation.get("compileAllSources")))
                    inputInventories.add(inventory(target, root(scope, clientRoot) + "/" + rawRoot, ".java", true));
                for (Object rawDirectory : array(compilation.get("dependencyDirectories")))
                    inputInventories.add(inventory(target, (String)rawDirectory, ".jar", false));
                Path compilationStage = stage.resolve(scope + "-" + name.replace('.', '-'));
                Map<String,byte[]> classes = compile(target, compilationStage, archive, compilation, roleSources, inputHashes, outputs, stage);
                Set<String> allOwners = owners(roleSources);
                Set<String> owners = new TreeSet<String>();
                for (String path : roleSources.keySet()) if (sources.containsKey(path))
                    owners.addAll(owners(Collections.singletonMap(path, roleSources.get(path))));
                if (compilation.containsKey("abiChangedClasses")) for (Object changed : array(compilation.get("abiChangedClasses")))
                    for (Map.Entry<String,byte[]> entry : beforeEntries.entrySet())
                        if (entry.getKey().endsWith(".class") && new String(entry.getValue(), StandardCharsets.ISO_8859_1).contains((String)changed)) {
                            String owner = ownerOf(entry.getKey(), allOwners);
                            if (owner != null) owners.add(owner);
                        }
                TreeMap<String,byte[]> baselineSources = new TreeMap<String,byte[]>();
                for (String path : roleSources.keySet()) if (Files.exists(safe(target, path), LinkOption.NOFOLLOW_LINKS))
                    baselineSources.put(path, bounded(file(target, path), MAX_SOURCE));
                Map<String,byte[]> baseline = baselineSources.isEmpty() ? Collections.<String,byte[]>emptyMap()
                    : compile(target, stage.resolve("baseline-" + scope + "-" + name.replace('.', '-')), archive, compilation,
                        baselineSources, inputHashes, Collections.<String,byte[]>emptyMap(), stage.resolve("baseline-dependencies"));
                for (Map.Entry<String,byte[]> old : beforeEntries.entrySet()) if (owned(old.getKey(), owners)) {
                    byte[] rebuilt = baseline.get(old.getKey());
                    if (rebuilt == null || !WorldBuilderClassSemantics.equivalent(old.getValue(), rebuilt))
                        throw failure(old.getKey(), "Active target bytecode differs from its source; rebuilding it could discard custom behavior.");
                }
                TreeMap<String,byte[]> selectedClasses = new TreeMap<String,byte[]>();
                for (Map.Entry<String,byte[]> entry : classes.entrySet()) if (owned(entry.getKey(), owners)) selectedClasses.put(entry.getKey(), entry.getValue());
                classes = selectedClasses;
                if (compilation.containsKey("abiChangedClasses")) for (Object changed : array(compilation.get("abiChangedClasses"))) {
                    if (!(changed instanceof String) || !((String)changed).matches("[A-Za-z0-9_$/]+")) throw failure(DESCRIPTOR, "Invalid ABI class name.");
                    for (Map.Entry<String,byte[]> old : beforeEntries.entrySet())
                        if (old.getKey().endsWith(".class") && !owned(old.getKey(), owners)
                            && new String(old.getValue(), StandardCharsets.ISO_8859_1).contains((String)changed))
                            throw failure(old.getKey(), "Prebuilt target code depends on changed map fields but has no source for recompilation.");
                }
                byte[] merged = merge(beforeEntries, classes, owners, object(compilation.get("manifestAttributes")));
                outputs.put(destination, merged);
                Map<String,Object> evidence = new LinkedHashMap<String,Object>();
                evidence.put("relativePath", destination); evidence.put("beforeSha256", hash(beforeBytes));
                evidence.put("sha256", hash(merged)); evidence.put("changedClassOwners", new ArrayList<String>(owners));
                evidence.put("retainedEntriesSha256", retainedFingerprint(beforeEntries, owners));
                archiveEvidence.add(evidence);
            }
        } finally { deleteOwnedStage(stage); }
        if (!compiledScopes.equals(new HashSet<String>(Arrays.asList("server", "client"))))
            throw failure(DESCRIPTOR, "Map integration must compile and verify both server and client.");
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
        for (Map.Entry<String,byte[]> entry : sources.entrySet()) {
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
        boolean allowed = destination.equals(INSTALLED) || destination.equals("server/core.jar") || destination.equals("server/plugins.jar")
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
        return executableIndex(source, fragment) >= 0;
    }

    private static boolean[] executablePositions(String source) throws WorldBuilderContractException {
        if (source.length() > MAX_SOURCE) throw failure("source", "Map source exceeds its size bound.");
        boolean[] values = new boolean[source.length()];
        for (int at = 0; at < source.length();) {
            if (source.startsWith("//", at)) { int end = source.indexOf('\n', at + 2); at = end < 0 ? source.length() : end + 1; continue; }
            if (source.startsWith("/*", at)) { int end = source.indexOf("*/", at + 2); if (end < 0) throw failure("source", "Unterminated Java comment."); at = end + 2; continue; }
            char ch = source.charAt(at);
            if (ch == '"' || ch == '\'') {
                if (source.startsWith("\"\"\"", at)) throw failure("source", "Text blocks are outside this Java source adapter.");
                at++; boolean closed = false;
                while (at < source.length()) { char next = source.charAt(at++); if (next == '\\') at++; else if (next == ch) { closed = true; break; } }
                if (!closed) throw failure("source", "Unterminated Java literal.");
                continue;
            }
            values[at++] = true;
        }
        return values;
    }

    private static Map<String,byte[]> compile(Path target, Path stage, Path archive, Map<String,Object> spec,
        Map<String,byte[]> sources, Map<String,String> inputs, Map<String,byte[]> outputs, Path rootStage) throws IOException, WorldBuilderContractException {
        if (sources.isEmpty()) throw failure(DESCRIPTOR, "Each map compilation requires reviewed sources.");
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
                entries(jar, false); inputs.put(relative, WorldBuilderHashes.sha256(jar)); classpath.add(jar.toString());
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
        for (String name : original.keySet()) if (owned(name, owners)) merged.remove(name);
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
    private static void verifyInventories(Path target, List<?> inventories) throws IOException, WorldBuilderContractException {
        for (Object raw : inventories) {
            Map<String,Object> expected = object(raw);
            if (!expected.equals(inventory(target, string(expected, "relativePath"), string(expected, "suffix"), Boolean.TRUE.equals(expected.get("recursive")))))
                throw failure(string(expected, "relativePath"), "Compilation input inventory changed after preview.");
        }
    }

    private static boolean owned(String name, Set<String> owners) {
        if (!name.endsWith(".class")) return false;
        for (String owner : owners) if (name.equals(owner + ".class") || name.startsWith(owner + "$")) return true;
        return false;
    }

    static Map<String,byte[]> entries(Path archive) throws IOException, WorldBuilderContractException { return entries(archive, true); }
    private static Map<String,byte[]> entries(Path archive, boolean changing) throws IOException, WorldBuilderContractException {
        if (Files.size(archive) > MAX_ARCHIVE) throw failure(archive.toString(), "Target archive exceeds its size bound.");
        TreeMap<String,byte[]> result = new TreeMap<String,byte[]>(); long total = 0;
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            Enumeration<? extends ZipEntry> items = zip.entries();
            while (items.hasMoreElements()) {
                ZipEntry entry = items.nextElement(); String name = entry.getName();
                if (entry.isDirectory()) continue;
                WorldBuilderPortablePath.require(name, "target-map-integration");
                if (changing && (name.startsWith("META-INF/versions/") || name.toUpperCase(Locale.ROOT).matches("META-INF/[^/]+\\.(SF|RSA|DSA|EC)")))
                    throw failure(name, "Signed or multi-release target archives require a separate reviewed adapter.");
                if (result.size() >= 100000 || entry.getSize() > MAX_ENTRY) throw failure(name, "Target archive inventory exceeds its bound.");
                byte[] bytes;
                try (InputStream input = zip.getInputStream(entry)) { bytes = bounded(input, MAX_ENTRY); }
                total += bytes.length;
                if (total > MAX_ARCHIVE || result.put(name, bytes) != null) throw failure(name, "Target archive is oversized or repeats an entry.");
            }
        }
        return result;
    }

    static List<Integer> verifyInstalled(Path project, Path target, String clientRoot) throws IOException, WorldBuilderContractException {
        Path current = embeddedPayload();
        try { return verifyInstalledPayload(current == null ? project : current, target, clientRoot); }
        finally { if (current != null) deleteOwnedStage(current); }
    }
    private static List<Integer> verifyInstalledPayload(Path project, Path target, String clientRoot) throws IOException, WorldBuilderContractException {
        Map<String,Object> descriptor = read(file(project, "working/runtime/" + DESCRIPTOR)); requireDescriptor(descriptor);
        Map<String,Object> installed = read(file(target, INSTALLED));
        if (!"world-builder-installed-target-map-integration".equals(installed.get("manifestType"))
            || !Long.valueOf(1).equals(installed.get("schemaVersion"))
            || !descriptor.get("integrationId").equals(installed.get("integrationId"))
            || !WorldBuilderHashes.sha256(file(project, "working/runtime/" + DESCRIPTOR)).equals(installed.get("descriptorSha256")))
            throw failure(INSTALLED, "Installed targeted map integration differs from this project's reviewed contract.");
        boolean matched = false;
        for (Object raw : array(descriptor.get("adapters"))) if (object(raw).get("adapterId").equals(installed.get("adapterId"))) matched = true;
        if (!matched) throw failure(INSTALLED, "Installed target adapter is not reviewed by this runtime.");
        for (String group : Arrays.asList("sources", "archives")) for (Object raw : array(installed.get(group))) {
            Map<String,Object> record = object(raw); String path = string(record, "relativePath");
            if (!string(record, "sha256").equals(WorldBuilderHashes.sha256(file(target, path))))
                throw failure(path, "Installed map integration changed; recapture and review a targeted upgrade before importing.");
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

    private static List<Integer> encodings(Map<String,Object> descriptor) throws WorldBuilderContractException {
        List<Integer> result = new ArrayList<Integer>();
        for (Object value : array(descriptor.get("encodingVersions"))) result.add(((Long)value).intValue());
        return Collections.unmodifiableList(result);
    }
    private static void requireDescriptor(Map<String,Object> descriptor) throws WorldBuilderContractException {
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
    static WorldBuilderContractException failure(String path, String message) { return new WorldBuilderContractException(WorldBuilderErrorCodes.RUNTIME_UPGRADE_REQUIRED, "target-map-integration", path, false, message, "Use a compiler-enabled World Builder build with a reviewed adapter for this target; no target files were replaced."); }
    static final class AdapterMismatch extends Exception { AdapterMismatch(String message) { super(message); } }
    static final class Result {
        final String adapterId; final Map<String,byte[]> outputs; final Map<String,String> inputs; final List<Integer> encodingVersions;
        Result(String adapterId, Map<String,byte[]> outputs, Map<String,String> inputs, List<Integer> encodingVersions) { this.adapterId = adapterId; this.outputs = outputs; this.inputs = inputs; this.encodingVersions = encodingVersions; }
    }
}
