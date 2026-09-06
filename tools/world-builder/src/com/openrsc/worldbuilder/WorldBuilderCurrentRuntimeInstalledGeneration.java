package com.openrsc.worldbuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Ledger-to-launch binding. Mutable gameplay state is deliberately never sealed here. */
final class WorldBuilderCurrentRuntimeInstalledGeneration {
    static final String INSTANCE = ".world-builder/current-runtime/instance";
    static final String LEDGER = ".world-builder/runtime-ledger-v1.json";
    private WorldBuilderCurrentRuntimeInstalledGeneration() { }

    static Map<String,Object> bind(Map<String,Object> ledger, Path target, Map<String,Object> spec,
        Map<String,Object> composition, Map<String,Object> mapManifest)
        throws WorldBuilderContractException {
        requireContentIdentity(ledger, composition, mapManifest);
        Map<String,Object> generation = WorldBuilderCurrentRuntimeInstance.renderGeneration(spec);
        if (!target.resolve(INSTANCE + "/installation").toString().equals(spec.get("installationRoot"))
            || !Objects.equals(ledger.get("targetInstallationId"), spec.get("installationId")))
            throw unsafe("Ledger and projected instance identity disagree.");
        Map<String,Object> binding = new LinkedHashMap<String,Object>();
        binding.put("instanceRelativePath", INSTANCE); binding.put("generationId", spec.get("generationId"));
        for (String role : Arrays.asList("server", "client")) {
            binding.put(role + "DescriptorRelativePath", INSTANCE + "/generations/" + spec.get("generationId") + "/" + role + "-launch.json");
            binding.put(role + "DescriptorSha256", WorldBuilderHashes.sha256(json(generation.get(role + "Descriptor"))));
        }
        binding.put("activeSelectionSha256", WorldBuilderHashes.sha256(json(generation.get("activeSelection"))));
        Map<String,Object> result = new LinkedHashMap<String,Object>(ledger);
        result.put("installedInstance", binding);
        return WorldBuilderCurrentRuntimeContracts.builtIn(WorldBuilderCurrentRuntimeContracts.Kind.TARGET_LEDGER, result).root;
    }

    /** Verify the active immutable launch closure, not a historical initial DB snapshot. */
    static Map<String,Object> readSpecification(Path target) throws IOException, WorldBuilderContractException {
        regular(target.resolve(LEDGER));
        Map<String,Object> ledger = WorldBuilderCurrentRuntimeContracts.read(
            WorldBuilderCurrentRuntimeContracts.Kind.TARGET_LEDGER, target.resolve(LEDGER)).root;
        if (!(ledger.get("installedInstance") instanceof Map)) throw unsafe("Historical ledger does not bind an installed launch pair.");
        Map<String,Object> binding = object(ledger.get("installedInstance"));
        Path instance = target.resolve(INSTANCE), installation = instance.resolve("installation");
        if (Files.exists(installation.resolve("pending-cutover.json"), LinkOption.NOFOLLOW_LINKS))
            throw unsafe("An interrupted installed cutover requires recovery.");
        Map<String,Object> server = readBound(target.resolve(string(binding, "serverDescriptorRelativePath")), string(binding, "serverDescriptorSha256"));
        Map<String,Object> client = readBound(target.resolve(string(binding, "clientDescriptorRelativePath")), string(binding, "clientDescriptorSha256"));
        Map<String,Object> selection = readBound(installation.resolve("active-launch.json"), string(binding, "activeSelectionSha256"));
        Map<String,Object> spec = new LinkedHashMap<String,Object>();
        spec.put("installationId", ledger.get("targetInstallationId")); spec.put("generationId", binding.get("generationId"));
        spec.put("installationRoot", installation.toString());
        for (String[] pair : new String[][] {{"compositionIdentity", "compositionIdentity"}, {"runtimeProfile", "runtimeProfile"}, {"configuration", "serverConfiguration"}}) {
            Map<String,Object> input = object(server.get(pair[0]));
            spec.put(pair[1] + "Path", input.get("path")); spec.put(pair[1] + "Sha256", input.get("sha256"));
            requireHash(path(string(input, "path")), string(input, "sha256"));
        }
        spec.put("mapRoot", server.get("mapRoot")); spec.put("mapPackageFingerprintSha256", server.get("mapPackageFingerprintSha256"));
        requireContentIdentity(ledger,
            readBound(path(string(spec, "compositionIdentityPath")), string(spec, "compositionIdentitySha256")),
            read(path(string(spec, "mapRoot")).resolve("manifest.json")));
        Map<String,Object> endpoint = object(server.get("endpoint")); spec.put("host", endpoint.get("host")); spec.put("gamePort", endpoint.get("gamePort"));
        for (String role : Arrays.asList("server", "client")) {
            Map<String,Object> descriptor = role.equals("server") ? server : client;
            Map<String,Object> profile = object(descriptor.get("installedMapProfile"));
            spec.put(role + "MapProfilePath", profile.get("path")); spec.put(role + "MapProfileSha256", profile.get("sha256"));
            readBound(path(string(profile, "path")), string(profile, "sha256"));
            for (String field : Arrays.asList("codeRoot", "codeTreeSha256", "workingRoot", "stateRoot", "sideStateRoot")) {
                String capital = Character.toUpperCase(field.charAt(0)) + field.substring(1);
                spec.put(role + capital, descriptor.get(field));
            }
            Map<String,Object> key = object(descriptor.get("publicKey"));
            spec.put(role + "PublicKeySha256", key.get("sha256"));
            requireHash(path(string(key, "path")), string(key, "sha256"));
            for (String field : Arrays.asList("workingRoot", "stateRoot", "sideStateRoot")) {
                Path mutable = path(string(descriptor, field));
                if (!mutable.startsWith(instance) || !Files.isDirectory(mutable, LinkOption.NOFOLLOW_LINKS)
                    || !mutable.equals(mutable.toRealPath())) throw unsafe("Mutable installed paths must remain within this canonical instance.");
            }
            if (!WorldBuilderCurrentRuntimeInstance.treeFingerprint(path(string(descriptor, "codeRoot")), false).equals(descriptor.get("codeTreeSha256")))
                throw unsafe("Installed code changed from the activated generation.");
        }
        if (!WorldBuilderCurrentRuntimeInstance.treeFingerprint(path(string(spec, "mapRoot")), true).equals(spec.get("mapPackageFingerprintSha256")))
            throw unsafe("Active map changed outside an installed transaction.");
        Map<String,Object> rendered = WorldBuilderCurrentRuntimeInstance.renderGeneration(spec);
        if (!server.equals(rendered.get("serverDescriptor")) || !client.equals(rendered.get("clientDescriptor"))
            || !selection.equals(rendered.get("activeSelection"))) throw unsafe("Installed launch pair has mixed or unbound generation fields.");
        return spec;
    }

    private static Map<String,Object> readBound(Path path, String hash) throws IOException, WorldBuilderContractException {
        requireHash(path, hash);
        return read(path);
    }
    private static Map<String,Object> read(Path path) throws IOException, WorldBuilderContractException {
        regular(path);
        try { return WorldBuilderJsonDocuments.readObject(path); }
        catch (WorldBuilderDiscoveryException invalid) { throw unsafe("Malformed immutable installed metadata."); }
    }
    private static void requireContentIdentity(Map<String,Object> ledger, Map<String,Object> composition,
        Map<String,Object> manifest) throws WorldBuilderContractException {
        for (String field : Arrays.asList("platformReleaseId", "platformManifestHash", "schemaSetHash", "variantId",
            "variantManifestHash", "moduleSetHash", "bundleInventoryHash", "bundleSpecId", "bundleSpecHash", "inputAdapterContractId"))
            if (!string(ledger, field).equals(string(composition, field))) throw unsafe("Ledger composition differs from its installed launch identity.");
        if (!string(ledger, "activeMapPackageId").equals(string(manifest, "packageId")))
            throw unsafe("Ledger map identity differs from its activated package.");
    }
    private static void requireHash(Path path, String hash) throws IOException, WorldBuilderContractException {
        regular(path);
        if (!WorldBuilderHashes.sha256(path).equals(hash)) throw unsafe("Installed metadata differs from its ledger binding.");
    }
    private static void regular(Path path) throws IOException, WorldBuilderContractException {
        if (!path.isAbsolute() || !path.normalize().equals(path) || !path.equals(path.toRealPath())
            || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > 1048576
            || ((Number)Files.getAttribute(path, "unix:nlink", LinkOption.NOFOLLOW_LINKS)).longValue() != 1)
            throw unsafe("Installed metadata path is unbounded or aliased.");
    }
    private static Path path(String text) throws WorldBuilderContractException {
        try { Path result = Paths.get(text); if (!result.isAbsolute() || !result.normalize().equals(result)) throw unsafe("Noncanonical installed path."); return result; }
        catch (InvalidPathException bad) { throw unsafe("Invalid installed path."); }
    }
    private static byte[] json(Object value) { return WorldBuilderJsonDocuments.pretty(value).getBytes(StandardCharsets.UTF_8); }
    @SuppressWarnings("unchecked") private static Map<String,Object> object(Object value) throws WorldBuilderContractException {
        if (!(value instanceof Map)) throw unsafe("Missing installed generation binding."); return (Map<String,Object>)value;
    }
    private static String string(Map<String,Object> value, String field) throws WorldBuilderContractException {
        if (!(value.get(field) instanceof String)) throw unsafe("Invalid installed generation field."); return (String)value.get(field);
    }
    private static WorldBuilderContractException unsafe(String message) {
        return new WorldBuilderContractException(WorldBuilderErrorCodes.RUNTIME_UPGRADE_REQUIRED,
            "installed-generation", "ledger", false, message,
            "Keep both roles offline and restore or recover the exact managed installation; do not bypass its ledger.");
    }
}
