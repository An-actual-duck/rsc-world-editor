package com.openrsc.worldbuilder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.*;

/** Strict inert final-client presentation projection. Target code is never executed. */
final class WorldBuilderNpcProducerV2 {
    static final String FILE = "npc-definitions-v2.json";
    static final String TYPE = "world-builder-npc-definitions";
    static final String PROFILE = "openrsc-effective-npc-preview-v1";
    static final long MAX_MANIFEST = 16L * 1024 * 1024;
    static final String ROLE_PREFIX = "npc-producer-v2-";

    private WorldBuilderNpcProducerV2() {}

    static boolean evidenceRole(String role) {
        return Arrays.asList(
                        "manifest",
                        "definition",
                        "config",
                        "source",
                        "helper-source",
                        "client-archive",
                        "asset",
                        "probe",
                        "visual-selector")
                .stream()
                .anyMatch(suffix -> (ROLE_PREFIX + suffix).equals(role));
    }

    static final class Document {
        final Map<String, Object> value, provider;
        final Map<String, Map<String, Object>> sources = new LinkedHashMap<>(),
                assets = new LinkedHashMap<>(),
                probes = new LinkedHashMap<>();
        final Map<Integer, Map<String, Object>> npcs = new TreeMap<>(),
                animations = new TreeMap<>();
        final boolean customSprites;

        Document(Map<String, Object> value, Map<String, Object> provider, boolean customSprites) {
            this.value = value;
            this.provider = provider;
            this.customSprites = customSprites;
        }
    }

    static Document readManifest(Path path) throws IOException, WorldBuilderContractException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(path)
                || Files.size(path) < 1
                || Files.size(path) > MAX_MANIFEST)
            throw failure(FILE, "NPC producer manifest is missing or exceeds16MiB.");
        try {
            return parse(
                    WorldBuilderJsonDocuments.readTargetDefinitionObject(
                            readBounded(path, MAX_MANIFEST), path.toString()));
        } catch (WorldBuilderDiscoveryException invalid) {
            throw failure(FILE, "NPC producer manifest is malformed JSON.");
        }
    }

    static byte[] readBounded(Path path, long limit)
            throws IOException, WorldBuilderContractException {
        if (Files.size(path) > limit)
            throw failure(
                    path.toString(), "NPC producer input exceeds its bounded byte limit: " + limit);
        try (java.io.InputStream input = Files.newInputStream(path);
                java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream()) {
            byte[] bytes = new byte[8192];
            int count;
            while ((count = input.read(bytes)) != -1) {
                if ((long) output.size() + count > limit)
                    throw failure(
                            path.toString(),
                            "NPC producer input grew beyond its bounded byte limit: " + limit);
                output.write(bytes, 0, count);
            }
            return output.toByteArray();
        }
    }

    private static List<String> lines(String text) throws IOException {
        List<String> result = new ArrayList<>();
        try (java.io.BufferedReader input =
                new java.io.BufferedReader(new java.io.StringReader(text))) {
            String line;
            while ((line = input.readLine()) != null) result.add(line);
        }
        return result;
    }

    private static Boolean configuredFlag(String text, String key)
            throws IOException, WorldBuilderContractException {
        java.util.regex.Pattern line =
                java.util.regex.Pattern.compile(
                        "^\\s*([A-Za-z0-9_]+)\\s*:\\s*([^#]*?)\\s*(?:#.*)?$");
        Boolean found = null;
        for (String value : lines(text)) {
            java.util.regex.Matcher match = line.matcher(value);
            if (!match.matches()
                    || !key.equalsIgnoreCase(match.group(1))
                    || match.group(2).trim().isEmpty()) continue;
            String flag = match.group(2).trim();
            if (found != null || !("true".equalsIgnoreCase(flag) || "false".equalsIgnoreCase(flag)))
                throw failure(
                        FILE,
                        "NPC producer configuration flag is ambiguous or not boolean: " + key);
            found = Boolean.valueOf(flag);
        }
        return found;
    }

    static final class Capture {
        final Document document;
        final String manifestPath;
        final List<WorldBuilderReadOnlyTarget.FileState> evidence;

        Capture(
                Document document,
                String manifestPath,
                List<WorldBuilderReadOnlyTarget.FileState> evidence) {
            this.document = document;
            this.manifestPath = manifestPath;
            this.evidence = Collections.unmodifiableList(new ArrayList<>(evidence));
        }
    }

    static Capture discover(
            WorldBuilderReadOnlyTarget target, WorldBuilderPackedSourceLayout layout)
            throws IOException, WorldBuilderContractException {
        String selected = null;
        for (String candidate :
                Arrays.asList(
                        "world-builder-provider/" + FILE, "server/conf/world-builder/" + FILE)) {
            if (!target.exists(candidate)) continue;
            if (selected != null)
                throw failure(
                        FILE,
                        "Two active complete NPC producers are present; select one maintained"
                                + " export.");
            selected = candidate;
        }
        if (selected == null) return null;
        Document document = readManifest(target.requiredFile(selected));
        List<WorldBuilderReadOnlyTarget.FileState> evidence = new ArrayList<>();
        evidence.add(target.requiredState(ROLE_PREFIX + "manifest", selected));
        Map<String, Object> configured = object(document.provider.get("configuration"));
        if (!layout.configurationPath.equals(configured.get("relativePath")))
            throw failure(selected, "NPC producer selects another server configuration path.");
        bind(
                target,
                layout.configurationPath,
                hash(configured, "sha256"),
                ROLE_PREFIX + "config",
                evidence);
        List<String> declaredDefinitions = new ArrayList<>();
        Map<String, String> boundPaths = new HashMap<>();
        for (Map<String, Object> source : document.sources.values()) {
            String path = path(source, "relativePath"), role = text(source, "role", 96);
            if (!supportedSourcePath(role, path, layout))
                throw failure(
                        path,
                        "NPC producer source path is outside its supported typed input roots.");
            if (boundPaths.put(path, hash(source, "sha256")) != null)
                throw failure(
                        path, "NPC producer repeats one source path under multiple identities.");
            String evidenceRole = "source";
            if ("effective-npc-definition".equals(role)) {
                evidenceRole = "definition";
                declaredDefinitions.add(path);
            } else if ("configuration-input".equals(role))
                evidenceRole =
                        "Client_Base/Cache/config.txt".equals(path) ? "visual-selector" : "config";
            else if ("producer-helper-source".equals(role)) evidenceRole = "helper-source";
            else if ("client-visual-archive".equals(role)) evidenceRole = "client-archive";
            else if (Arrays.asList(
                            "external-sprite-input", "sprite-input", "resolved-frame-artifact")
                    .contains(role)) evidenceRole = "asset";
            bind(target, path, hash(source, "sha256"), ROLE_PREFIX + evidenceRole, evidence);
        }
        List<String> expected =
                new ArrayList<>(
                        Arrays.asList(
                                layout.definitionPath("NpcDefs.json"),
                                layout.definitionPath("NpcDefsCustom.json")));
        expected.addAll(WorldBuilderNpcContentSources.inspect(target, layout).supplemental);
        WorldBuilderDefinitionComposition.Profile composition =
                WorldBuilderDefinitionComposition.inspect(target, layout);
        if (!composition.npcPatchPath.isEmpty()) expected.add(composition.npcPatchPath);
        if (composition.wantMyWorld) expected.add(layout.definitionPath("NpcDefsMyWorld.json"));
        if (!expected.equals(declaredDefinitions))
            throw failure(
                    selected,
                    "NPC producer definition sources differ from the verified effective load"
                            + " order.");
        requireSourcePath(boundPaths, "server/src/com/openrsc/server/external/EntityHandler.java");
        requireSourcePath(
                boundPaths, "Client_Base/src/com/openrsc/client/entityhandling/EntityHandler.java");
        requireSourcePath(boundPaths, "Client_Base/src/orsc/mudclient.java");
        requireSourcePath(boundPaths, "Client_Base/src/orsc/graphics/two/GraphicsController.java");
        verifyFlags(target, layout, document, boundPaths);
        verifyDisjointSpritepacks(target, document, boundPaths);
        Map<String, Object> catalog =
                WorldBuilderProjectContentBundle.deriveTargetCatalog(
                        target, layout, "npc-producer-v2-effective");
        List<Integer> ids = new ArrayList<>();
        for (Object id : array(catalog.get("npcs"), 1, 65536)) ids.add(integer(id, 0, 65535));
        if (!ids.equals(new ArrayList<>(document.npcs.keySet())))
            throw failure(
                    selected,
                    "Complete NPC producer IDs differ from the effective server catalog; publish a"
                            + " fresh complete export.");
        for (Map<String, Object> asset : document.assets.values()) {
            String packagePath = path(asset, "packageRelativePath");
            Path root = target.requiredFile(selected).getParent();
            Path file = WorldBuilderPortablePath.resolveContained(root, packagePath, FILE);
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                    || Files.isSymbolicLink(file)
                    || !hash(asset, "sha256").equals(WorldBuilderHashes.sha256(file)))
                throw failure(
                        packagePath, "NPC packaged frame asset differs from its target binding.");
            String relative = target.root.relativize(file).toString().replace('\\', '/');
            bind(target, relative, hash(asset, "sha256"), ROLE_PREFIX + "asset", evidence);
        }
        for (Map<String, Object> probe : document.probes.values()) {
            if ("file".equals(probe.get("kind"))) {
                String path = path(probe, "relativePath");
                if (!externalPath(path))
                    throw failure(path, "NPC file probe is outside supported input roots.");
                WorldBuilderReadOnlyTarget.FileState state =
                        target.optionalState(ROLE_PREFIX + "probe", path);
                if (state.present != flag(probe, "present")
                        || state.present && !state.sha256.equals(hash(probe, "sha256")))
                    throw failure(
                            path,
                            "NPC loader candidate appeared, disappeared, or changed after capture;"
                                    + " regenerate the complete visual export.");
                evidence.add(state);
            } else {
                String path = path(probe, "archiveRelativePath"), entry = path(probe, "entryPath");
                if (!boundPaths.containsKey(path)
                        || !boundPaths.get(path).equals(hash(probe, "archiveSha256")))
                    throw failure(
                            path, "NPC archive probe lacks whole-container source authority.");
                if (path.endsWith(".jar") && !entry.startsWith("myworld-assets/"))
                    throw failure(
                            entry,
                            "NPC embedded probe is outside supported client visual resources.");
                verifyArchiveProbe(
                        target.requiredFile(path),
                        entry,
                        flag(probe, "present"),
                        probe.get("entrySha256"));
            }
        }
        for (WorldBuilderReadOnlyTarget.FileState state : evidence) {
            WorldBuilderReadOnlyTarget.FileState after =
                    target.optionalState(state.role, state.relativePath);
            if (state.present != after.present
                    || state.size != after.size
                    || !state.sha256.equals(after.sha256))
                throw failure(state.relativePath, "NPC producer evidence changed during capture.");
        }
        return new Capture(document, selected, evidence);
    }

    static void verifyTarget(
            WorldBuilderReadOnlyTarget target, WorldBuilderPackedSourceLayout layout)
            throws IOException, WorldBuilderContractException {
        discover(target, layout);
    }

    private static void bind(
            WorldBuilderReadOnlyTarget target,
            String path,
            String expected,
            String role,
            List<WorldBuilderReadOnlyTarget.FileState> evidence)
            throws WorldBuilderContractException {
        WorldBuilderReadOnlyTarget.FileState state = target.requiredState(role, path);
        if (!expected.equals(state.sha256))
            throw failure(
                    path,
                    "NPC producer source or asset is stale; regenerate the maintained export.");
        evidence.add(state);
    }

    private static void requireSourcePath(Map<String, String> paths, String path)
            throws WorldBuilderContractException {
        if (!paths.containsKey(path))
            throw failure(path, "Complete NPC producer omits a required maintained loader source.");
    }

    private static boolean externalPath(String path) {
        return path.startsWith("Client_Base/Cache/")
                || path.startsWith("server/conf/world-builder/")
                || path.startsWith("dev/myworld/assets/sprites/npcs/")
                || path.startsWith("Client_Base/dev/myworld/assets/")
                || path.startsWith("Core-Framework/dev/myworld/assets/");
    }

    private static boolean supportedSourcePath(
            String role, String path, WorldBuilderPackedSourceLayout layout) {
        if ("producer-helper-source".equals(role))
            return path.matches("tools/item-visual-provider/[A-Za-z0-9_-]+\\.(java|py)")
                    || "scripts/generate-world-builder-target-contract.py".equals(path);
        if ("client-visual-archive".equals(role))
            return "Client_Base/Open_RSC_Client.jar".equals(path);
        if ("effective-npc-definition".equals(role))
            return path.startsWith(layout.definitionRoot + "/") && path.endsWith(".json");
        if ("configuration-input".equals(role))
            return "Client_Base/Cache/config.txt".equals(path)
                    || path.equals(layout.configurationPath)
                    || path.startsWith("server/conf/") && path.endsWith(".conf");
        if ("server-npc-loader".equals(role))
            return path.startsWith("server/src/") && path.endsWith(".java");
        if (Arrays.asList("client-npc-loader", "client-frame-resolver", "client-sprite-initializer")
                .contains(role))
            return path.startsWith("Client_Base/src/") && path.endsWith(".java");
        return externalPath(path);
    }

    private static String withoutJavaComments(String source) throws WorldBuilderContractException {
        StringBuilder out = new StringBuilder(source.length());
        int state = 0;
        boolean escape = false;
        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i), next = i + 1 < source.length() ? source.charAt(i + 1) : 0;
            if (state == 1) {
                if (c == '\n' || c == '\r') {
                    state = 0;
                    out.append(c);
                } else out.append(' ');
                continue;
            }
            if (state == 2) {
                if (c == '*' && next == '/') {
                    out.append("  ");
                    i++;
                    state = 0;
                } else out.append(c == '\n' || c == '\r' ? c : ' ');
                continue;
            }
            if (state == 3 || state == 4) {
                out.append(c);
                if (escape) escape = false;
                else if (c == '\\') escape = true;
                else if (c == (state == 3 ? '"' : '\'')) state = 0;
                continue;
            }
            if (c == '/' && next == '/') {
                out.append("  ");
                i++;
                state = 1;
            } else if (c == '/' && next == '*') {
                out.append("  ");
                i++;
                state = 2;
            } else {
                out.append(c);
                if (c == '"') state = 3;
                else if (c == '\'') state = 4;
            }
        }
        if (state == 2 || state == 3 || state == 4)
            throw failure(FILE, "Unterminated maintained configuration source syntax.");
        return out.toString();
    }

    private static void verifyFlags(
            WorldBuilderReadOnlyTarget target,
            WorldBuilderPackedSourceLayout layout,
            Document document,
            Map<String, String> sourcePaths)
            throws IOException, WorldBuilderContractException {
        {
            String config =
                    new String(
                            readBounded(
                                    target.requiredFile(layout.configurationPath),
                                    4L * 1024 * 1024),
                            java.nio.charset.StandardCharsets.UTF_8);
            Map<String, Object> flags = object(document.provider.get("clientFlags"));
            for (String[] spec :
                    new String[][] {
                        {"custom_sprites", "Config.S_WANT_CUSTOM_SPRITES", "WANT_CUSTOM_SPRITES"},
                        {
                            "allow_bearded_ladies",
                            "Config.S_ALLOW_BEARDED_LADIES",
                            "ALLOW_BEARDED_LADIES"
                        }
                    }) {
                boolean value;
                Boolean configured = configuredFlag(config, spec[0]);
                if (configured != null) value = configured;
                else {
                    String source = "server/src/com/openrsc/server/ServerConfiguration.java";
                    requireSourcePath(sourcePaths, source);
                    String sourceText =
                            withoutJavaComments(
                                    new String(
                                            readBounded(
                                                    target.requiredFile(source), 16L * 1024 * 1024),
                                            java.nio.charset.StandardCharsets.UTF_8));
                    java.util.regex.Pattern pattern =
                            java.util.regex.Pattern.compile(
                                    "\\b"
                                            + spec[2]
                                            + "\\s*=\\s*tryReadBool\\(\\s*\""
                                            + spec[0]
                                            + "\"\\s*\\)\\.orElse\\(\\s*(true|false)\\s*\\)\\s*;");
                    java.util.regex.Matcher matcher = pattern.matcher(sourceText);
                    if (!matcher.find())
                        throw failure(
                                source,
                                "NPC client flag has no bounded supported configuration/default"
                                        + " proof.");
                    value = Boolean.parseBoolean(matcher.group(1));
                    if (matcher.find())
                        throw failure(source, "NPC client flag default is ambiguous.");
                    java.util.regex.Matcher assignments =
                            java.util.regex.Pattern.compile("\\b" + spec[2] + "\\s*=(?!=)")
                                    .matcher(sourceText);
                    int assignmentCount = 0;
                    while (assignments.find()) assignmentCount++;
                    if (assignmentCount != 1)
                        throw failure(
                                source,
                                "NPC client flag default has unsupported competing assignments.");
                }
                if (value != flag(flags, spec[1]))
                    throw failure(
                            layout.configurationPath,
                            "NPC producer client flag disagrees with selected configuration: "
                                    + spec[1]);
            }
        }
    }

    private static void verifyDisjointSpritepacks(
            WorldBuilderReadOnlyTarget target, Document document, Map<String, String> boundPaths)
            throws IOException, WorldBuilderContractException {
        String config = "Client_Base/Cache/config.txt";
        if (!target.exists(config)) {
            boolean proven = false;
            for (Map<String, Object> probe : document.probes.values())
                if ("file".equals(probe.get("kind"))
                        && config.equals(probe.get("relativePath"))
                        && Boolean.FALSE.equals(probe.get("present"))) proven = true;
            if (!proven)
                throw failure(
                        config, "Missing spritepack selection requires a bound absent-file probe.");
            return;
        }
        requireSourcePath(boundPaths, config);
        Set<String> animationKeys = new HashSet<>();
        for (Map<String, Object> animation : document.animations.values())
            animationKeys.add(animation.get("category") + "/" + animation.get("name"));
        Set<String> seen = new HashSet<>();
        List<String> activeSources = new ArrayList<>();
        for (String line :
                lines(
                        new String(
                                readBounded(target.requiredFile(config), 4L * 1024 * 1024),
                                java.nio.charset.StandardCharsets.UTF_8))) {
            if (!line.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}:[01]"))
                throw failure(
                        config,
                        "Unsupported spritepack configuration; expected bounded unique name:0/1"
                                + " lines.");
            String[] parts = line.split(":");
            if (!seen.add(parts[0])) throw failure(config, "Duplicate spritepack selection.");
            if (!"1".equals(parts[1])) continue;
            String path = "Client_Base/Cache/video/spritepacks/" + parts[0] + ".osar";
            requireSourcePath(boundPaths, path);
            Map<String, WorldBuilderNpcDefinitionProvider.SpriteEntry> entries =
                    WorldBuilderNpcDefinitionProvider.readOsar(target.requiredFile(path));
            for (String key : entries.keySet())
                if (animationKeys.contains(key))
                    throw failure(
                            path,
                            "An active spritepack overrides NPC animation "
                                    + key
                                    + "; this bounded profile supports only NPC-disjoint"
                                    + " spritepacks.");
            for (Map.Entry<String, Map<String, Object>> source : document.sources.entrySet())
                if (path.equals(source.getValue().get("relativePath")))
                    activeSources.add(source.getKey());
        }
        for (Map<String, Object> animation : document.animations.values()) {
            Map<String, Object> resolution = object(animation.get("resolution"));
            List<?> inputs = array(resolution.get("inputSourceIds"), 1, 8192),
                    order = array(resolution.get("precedenceSourceIds"), 0, 8192);
            List<Object> selected = new ArrayList<>();
            for (Object key : order) if (activeSources.contains(key)) selected.add(key);
            if (!inputs.containsAll(activeSources) || !selected.equals(activeSources))
                throw failure(
                        config,
                        "NPC resolver closure omits or reorders active spritepack evidence.");
        }
    }

    private static void verifyArchiveProbe(
            Path path, String entry, boolean present, Object expected)
            throws IOException, WorldBuilderContractException {
        try (java.util.zip.ZipFile archive = new java.util.zip.ZipFile(path.toFile())) {
            Set<String> names = new HashSet<>();
            java.util.Enumeration<? extends java.util.zip.ZipEntry> entries = archive.entries();
            java.util.zip.ZipEntry found = null;
            int count = 0;
            while (entries.hasMoreElements()) {
                java.util.zip.ZipEntry row = entries.nextElement();
                if (++count > 32768 || !names.add(row.getName()))
                    throw failure(FILE, "NPC archive probe container is duplicated or oversized.");
                if (entry.equals(row.getName())) found = row;
            }
            if ((found != null) != present)
                throw failure(entry, "NPC embedded resource outcome changed after capture.");
            if (found != null) {
                if (found.isDirectory()
                        || found.getSize() < 0
                        || found.getSize() > 16L * 1024 * 1024)
                    throw failure(entry, "NPC embedded resource is invalid or oversized.");
                try (java.io.InputStream input = archive.getInputStream(found)) {
                    java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
                    byte[] block = new byte[8192];
                    int n;
                    while ((n = input.read(block)) != -1) {
                        if (bytes.size() + n > 16 * 1024 * 1024)
                            throw failure(entry, "NPC embedded resource exceeds its bound.");
                        bytes.write(block, 0, n);
                    }
                    if (!requireHash(expected)
                            .equals(WorldBuilderHashes.sha256(bytes.toByteArray())))
                        throw failure(entry, "NPC embedded resource bytes changed after capture.");
                }
            }
        }
    }

    static Document parse(Map<String, Object> root) throws WorldBuilderContractException {
        exact(
                root,
                "schemaVersion",
                "manifestType",
                "provider",
                "selection",
                "assetProviders",
                "npcDefinitions",
                "animationDefinitions");
        if (!Long.valueOf(2).equals(root.get("schemaVersion"))
                || !TYPE.equals(root.get("manifestType")))
            throw failure(FILE, "Expected complete effective NPC producer schema2.");
        Map<String, Object> provider = object(root.get("provider"));
        exact(
                provider,
                "identity",
                "rendererProfile",
                "capturePhase",
                "spriteBranch",
                "configuration",
                "clientFlags",
                "sources",
                "resolutionProbes");
        text(provider, "identity", 256);
        if (!PROFILE.equals(provider.get("rendererProfile"))
                || !"after-selected-client-sprite-initialization-v1"
                        .equals(provider.get("capturePhase")))
            throw failure(FILE, "Unsupported NPC renderer profile or incomplete capture phase.");
        Map<String, Object> configuration = object(provider.get("configuration"));
        exact(configuration, "relativePath", "sha256");
        path(configuration, "relativePath");
        hash(configuration, "sha256");
        Map<String, Object> flags = object(provider.get("clientFlags"));
        exact(flags, "Config.S_WANT_CUSTOM_SPRITES", "Config.S_ALLOW_BEARDED_LADIES");
        boolean custom = flag(flags, "Config.S_WANT_CUSTOM_SPRITES");
        flag(flags, "Config.S_ALLOW_BEARDED_LADIES");
        if (!(custom ? "custom" : "authentic").equals(provider.get("spriteBranch")))
            throw failure(FILE, "NPC sprite branch disagrees with captured client flags.");
        Document document = new Document(root, provider, custom);
        for (Object raw : array(provider.get("sources"), 1, 8192)) {
            Map<String, Object> row = object(raw);
            exact(row, "sourceId", "role", "relativePath", "sha256");
            String id = identity(row, "sourceId"), role = text(row, "role", 96);
            path(row, "relativePath");
            hash(row, "sha256");
            if (!Arrays.asList(
                            "effective-npc-definition",
                            "configuration-input",
                            "server-npc-loader",
                            "client-npc-loader",
                            "client-frame-resolver",
                            "client-sprite-initializer",
                            "external-sprite-input",
                            "sprite-input",
                            "resolved-frame-artifact",
                            "client-visual-archive",
                            "producer-helper-source")
                    .contains(role))
                throw failure(FILE, "Unknown NPC producer source role: " + role);
            if (document.sources.put(id, row) != null)
                throw failure(FILE, "Duplicate NPC producer source identity.");
        }
        for (Object raw : array(provider.get("resolutionProbes"), 0, 8192)) {
            Map<String, Object> row = object(raw);
            String id = identity(row, "probeId");
            boolean present = flag(row, "present");
            String kind = text(row, "kind", 32);
            if ("file".equals(kind)) {
                if (present) exact(row, "probeId", "kind", "relativePath", "present", "sha256");
                else exact(row, "probeId", "kind", "relativePath", "present");
                path(row, "relativePath");
                if (present) hash(row, "sha256");
            } else if ("archive-entry".equals(kind)) {
                if (present)
                    exact(
                            row,
                            "probeId",
                            "kind",
                            "archiveRelativePath",
                            "archiveSha256",
                            "entryPath",
                            "present",
                            "entrySha256");
                else
                    exact(
                            row,
                            "probeId",
                            "kind",
                            "archiveRelativePath",
                            "archiveSha256",
                            "entryPath",
                            "present");
                path(row, "archiveRelativePath");
                path(row, "entryPath");
                hash(row, "archiveSha256");
                if (present) hash(row, "entrySha256");
            } else throw failure(FILE, "Unknown NPC resolution probe type.");
            if (document.probes.put(id, row) != null)
                throw failure(FILE, "Duplicate NPC resolution probe identity.");
        }
        for (Object raw : array(root.get("assetProviders"), 1, 8192)) {
            Map<String, Object> row = object(raw);
            exact(
                    row,
                    "assetId",
                    "sourceId",
                    "format",
                    "targetRelativePath",
                    "packageRelativePath",
                    "sha256");
            String id = identity(row, "assetId"), source = identity(row, "sourceId");
            path(row, "targetRelativePath");
            path(row, "packageRelativePath");
            hash(row, "sha256");
            Map<String, Object> bound = document.sources.get(source);
            if (bound == null
                    || !bound.get("relativePath").equals(row.get("targetRelativePath"))
                    || !bound.get("sha256").equals(row.get("sha256"))
                    || !Arrays.asList(
                                    "sprite-input",
                                    "resolved-frame-artifact",
                                    "client-visual-archive")
                            .contains(bound.get("role")))
                throw failure(FILE, "NPC asset lacks matching source authority.");
            if (!Arrays.asList(
                            "openrsc-osar-v1",
                            "openrsc-authentic-zip-v1",
                            "world-builder-rgb-frame-zip-v1")
                    .contains(row.get("format")))
                throw failure(FILE, "Unsupported NPC frame asset format.");
            if (document.assets.put(id, row) != null)
                throw failure(FILE, "Duplicate NPC asset identity.");
        }
        Set<String> usedProbes = new HashSet<>();
        int previous = -1;
        for (Object raw : array(root.get("animationDefinitions"), 0, 65536)) {
            Map<String, Object> row = object(raw);
            exact(
                    row,
                    "animationId",
                    "name",
                    "category",
                    "charColour",
                    "blueMask",
                    "genderModel",
                    "hasCombatFrames",
                    "hasSpecialCombatFrames",
                    "resolvedFrameCount",
                    "npcMaskPolicy",
                    "authoringPreview",
                    "resolution",
                    "frames");
            int id = number(row, "animationId", 0, 65535);
            if (id <= previous) throw failure(FILE, "NPC animations are not sorted and unique.");
            previous = id;
            identity(row, "name");
            identity(row, "category");
            int colour = number(row, "charColour", Integer.MIN_VALUE, Integer.MAX_VALUE);
            number(row, "blueMask", Integer.MIN_VALUE, Integer.MAX_VALUE);
            number(row, "genderModel", Integer.MIN_VALUE, Integer.MAX_VALUE);
            boolean sourceA = flag(row, "hasCombatFrames"),
                    sourceF = flag(row, "hasSpecialCombatFrames");
            int count = number(row, "resolvedFrameCount", 1, 256);
            if (sourceF && (!sourceA || count < 27))
                throw failure(
                        FILE,
                        "Source special frames require source combat and complete offsets"
                                + " through26.");
            if (!WorldBuilderProjectContentBundle.effectiveNpcMaskPolicy(id, custom, colour)
                    .equals(row.get("npcMaskPolicy")))
                throw failure(
                        FILE,
                        "NPC mask policy disagrees with original animation identity, flags and"
                                + " colour.");
            Map<String, Object> preview = object(row.get("authoringPreview"));
            exact(
                    preview,
                    "behavior",
                    "hasCombatFrames",
                    "hasSpecialCombatFrames",
                    "frameIndices",
                    "limitations");
            if (!"generic-layered-preview-v1".equals(preview.get("behavior")))
                throw failure(FILE, "Unsupported NPC authoring preview behavior.");
            boolean a = flag(preview, "hasCombatFrames"),
                    f = flag(preview, "hasSpecialCombatFrames");
            if (f && !a) throw failure(FILE, "Authoring special frames require combat frames.");
            int selected = 15 + (a ? 3 : 0) + (f ? 9 : 0);
            for (Object offset : array(preview.get("frameIndices"), selected, selected))
                integer(offset, 0, count - 1);
            Set<String> limits = new HashSet<>();
            for (Object limit : array(preview.get("limitations"), 0, 2))
                if (!Arrays.asList(
                                        "source-animation-cadence-not-reproduced",
                                        "source-secondary-attack-not-previewed")
                                .contains(limit)
                        || !limits.add((String) limit))
                    throw failure(FILE, "Unsupported or duplicate NPC preview limitation.");
            if (count == 21
                    && (!sourceA
                            || sourceF
                            || !a
                            || f
                            || !limits.contains("source-secondary-attack-not-previewed")))
                throw failure(
                        FILE,
                        "Twenty-one source frames require explicit combat-only authoring projection"
                                + " and retained secondary-attack limitation.");
            Map<String, Object> resolution = object(row.get("resolution"));
            exact(
                    resolution,
                    "resolverSourceId",
                    "resolverSourceSha256",
                    "inputSourceIds",
                    "precedenceSourceIds",
                    "probeIds");
            String resolver = identity(resolution, "resolverSourceId");
            hash(resolution, "resolverSourceSha256");
            Map<String, Object> source = document.sources.get(resolver);
            if (source == null
                    || !"client-frame-resolver".equals(source.get("role"))
                    || !source.get("sha256").equals(resolution.get("resolverSourceSha256")))
                throw failure(FILE, "NPC resolver lacks matching source authority.");
            Set<String> inputs =
                    references(resolution, "inputSourceIds", document.sources.keySet(), 1);
            references(resolution, "precedenceSourceIds", inputs, 0);
            usedProbes.addAll(references(resolution, "probeIds", document.probes.keySet(), 0));
            Map<String, Object> frames = object(row.get("frames"));
            String kind = text(frames, "kind", 32), assetId = identity(frames, "assetId");
            Map<String, Object> asset = document.assets.get(assetId);
            if (asset == null || !inputs.contains(asset.get("sourceId")))
                throw failure(FILE, "NPC frames lack referenced asset source closure.");
            if ("resolved-rgb".equals(kind)) {
                exact(frames, "kind", "assetId", "frameKeys", "frameSha256s");
                if (!"world-builder-rgb-frame-zip-v1".equals(asset.get("format")))
                    throw failure(FILE, "Resolved NPC RGB frames select another asset format.");
                for (Object key : array(frames.get("frameKeys"), count, count)) portable(key);
                for (Object sha : array(frames.get("frameSha256s"), count, count)) requireHash(sha);
            } else if ("osar-entry".equals(kind)) {
                exact(frames, "kind", "assetId", "subspace", "entry", "entrySha256");
                identity(frames, "subspace");
                identity(frames, "entry");
                hash(frames, "entrySha256");
                if (!"openrsc-osar-v1".equals(asset.get("format")))
                    throw failure(FILE, "NPC OSAR frames select another asset format.");
            } else if ("authentic-frames".equals(kind)) {
                exact(frames, "kind", "assetId", "baseSpriteId", "entrySha256s");
                number(frames, "baseSpriteId", 0, 65536 - count);
                for (Object sha : array(frames.get("entrySha256s"), count, count)) requireHash(sha);
                if (!"openrsc-authentic-zip-v1".equals(asset.get("format")))
                    throw failure(FILE, "NPC authentic frames select another asset format.");
            } else throw failure(FILE, "Unsupported NPC resolved frame source.");
            document.animations.put(id, row);
        }
        if (!usedProbes.equals(document.probes.keySet()))
            throw failure(FILE, "NPC producer contains unreferenced resolution probes.");
        Set<Integer> usedAnimations = new TreeSet<>();
        previous = -1;
        for (Object raw : array(root.get("npcDefinitions"), 1, 65536)) {
            Map<String, Object> row = object(raw);
            exact(
                    row,
                    "npcId",
                    "spriteAnimationIds",
                    "hairColour",
                    "topColour",
                    "bottomColour",
                    "skinColour",
                    "cameraWidth",
                    "cameraHeight",
                    "walkModel",
                    "combatModel",
                    "combatSprite");
            int id = number(row, "npcId", 0, 65535);
            if (id <= previous)
                throw failure(FILE, "NPC visual identities are not sorted and unique.");
            previous = id;
            for (Object rawId : array(row.get("spriteAnimationIds"), 12, 12)) {
                int animation = integer(rawId, -1, 65535);
                if (animation >= 0 && !document.animations.containsKey(animation))
                    throw failure(FILE, "NPC visual vector references an unresolved animation.");
                if (animation >= 0) usedAnimations.add(animation);
            }
            for (String key :
                    Arrays.asList(
                            "hairColour",
                            "topColour",
                            "bottomColour",
                            "skinColour",
                            "combatModel",
                            "combatSprite")) number(row, key, Integer.MIN_VALUE, Integer.MAX_VALUE);
            number(row, "walkModel", 1, Integer.MAX_VALUE);
            number(row, "cameraWidth", 1, 65535);
            number(row, "cameraHeight", 1, 65535);
            document.npcs.put(id, row);
        }
        if (!usedAnimations.equals(document.animations.keySet()))
            throw failure(FILE, "NPC animation closure contains missing or unused records.");
        Map<String, Object> selection = object(root.get("selection"));
        exact(selection, "kind", "npcIds");
        if (!"complete-effective-server-npc-catalog".equals(selection.get("kind")))
            throw failure(FILE, "NPC selection is not the complete available catalog.");
        List<Integer> selected = new ArrayList<>();
        previous = -1;
        for (Object raw : array(selection.get("npcIds"), 1, 65536)) {
            int id = integer(raw, 0, 65535);
            if (id <= previous) throw failure(FILE, "NPC selection is not sorted and unique.");
            selected.add(id);
            previous = id;
        }
        if (!selected.equals(new ArrayList<>(document.npcs.keySet())))
            throw failure(FILE, "NPC selection differs from visual rows.");
        return document;
    }

    private static Set<String> references(
            Map<String, Object> row, String key, Set<String> allowed, int min)
            throws WorldBuilderContractException {
        Set<String> result = new LinkedHashSet<>();
        for (Object raw : array(row.get(key), min, 8192)) {
            if (!(raw instanceof String) || !allowed.contains(raw) || !result.add((String) raw))
                throw failure(FILE, "Unknown or duplicate NPC source/probe reference.");
        }
        return result;
    }

    static void exact(Map<String, Object> row, String... keys)
            throws WorldBuilderContractException {
        if (!row.keySet().equals(new HashSet<>(Arrays.asList(keys))))
            throw failure(FILE, "NPC producer object has missing or unsupported keys.");
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> object(Object raw) throws WorldBuilderContractException {
        if (!(raw instanceof Map)) throw failure(FILE, "NPC producer expects an object.");
        return (Map<String, Object>) raw;
    }

    static List<?> array(Object raw, int min, int max) throws WorldBuilderContractException {
        if (!(raw instanceof List) || ((List<?>) raw).size() < min || ((List<?>) raw).size() > max)
            throw failure(FILE, "NPC producer array is missing or outside its bound.");
        return (List<?>) raw;
    }

    static int integer(Object raw, int min, int max) throws WorldBuilderContractException {
        if (!(raw instanceof Long) || (Long) raw < min || (Long) raw > max)
            throw failure(FILE, "NPC producer integer is outside its bound.");
        return ((Long) raw).intValue();
    }

    static int number(Map<String, Object> row, String key, int min, int max)
            throws WorldBuilderContractException {
        return integer(row.get(key), min, max);
    }

    static boolean flag(Map<String, Object> row, String key) throws WorldBuilderContractException {
        if (!(row.get(key) instanceof Boolean))
            throw failure(FILE, "NPC producer flag must be boolean: " + key);
        return (Boolean) row.get(key);
    }

    static String text(Map<String, Object> row, String key, int max)
            throws WorldBuilderContractException {
        Object raw = row.get(key);
        if (!(raw instanceof String) || ((String) raw).isEmpty() || ((String) raw).length() > max)
            throw failure(FILE, "NPC producer text is missing or outside its bound: " + key);
        return (String) raw;
    }

    static String identity(Map<String, Object> row, String key)
            throws WorldBuilderContractException {
        String value = text(row, key, 128);
        if (!value.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}"))
            throw failure(FILE, "NPC producer identity is unsafe.");
        return value;
    }

    static String portable(Object raw) throws WorldBuilderContractException {
        if (!(raw instanceof String) || ((String) raw).length() > 512)
            throw failure(FILE, "NPC producer path is malformed.");
        WorldBuilderPortablePath.require((String) raw, FILE);
        return (String) raw;
    }

    static String path(Map<String, Object> row, String key) throws WorldBuilderContractException {
        return portable(row.get(key));
    }

    static String requireHash(Object raw) throws WorldBuilderContractException {
        if (!(raw instanceof String) || !WorldBuilderBoundedInventory.isHash((String) raw))
            throw failure(FILE, "NPC producer SHA-256 is invalid.");
        return (String) raw;
    }

    static String hash(Map<String, Object> row, String key) throws WorldBuilderContractException {
        return requireHash(row.get(key));
    }

    static WorldBuilderContractException failure(String path, String message) {
        return WorldBuilderReadOnlyTarget.problem(
                WorldBuilderErrorCodes.DEFINITION_MISMATCH,
                path,
                message,
                "Publish a fresh complete maintained NPC visual export for the selected"
                        + " configuration, then detect new content.");
    }
}
