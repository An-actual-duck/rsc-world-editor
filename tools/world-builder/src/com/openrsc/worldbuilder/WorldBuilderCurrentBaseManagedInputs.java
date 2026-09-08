package com.openrsc.worldbuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;

/** Current-to-current input adapter. Never revisits historical maps, configuration or databases. */
final class WorldBuilderCurrentBaseManagedInputs {
    static final String BOUNDARY = "current-base-retained-map-v1";
    private static final String PACKAGE = "migration/output/map/conversion/package";
    private WorldBuilderCurrentBaseManagedInputs() { }

    static Map<String,Object> inspect(Path target, WorldBuilderProviderCatalog.Composition selected,
        WorldBuilderCurrentRuntimeExecutionProfile profile) throws IOException, WorldBuilderContractException {
        Map<String,Object> spec = WorldBuilderCurrentRuntimeInstalledGeneration.readSpecification(target);
        Map<String,Object> typed = typed(target, spec);
        Path mapRoot = Paths.get(text(spec, "mapRoot"));
        Map<String,Object> manifest = read(mapRoot.resolve("manifest.json"));
        List<Object> inventory = WorldBuilderPackedConverter.outputInventory(mapRoot, PACKAGE);
        String fingerprint = WorldBuilderCurrentRuntimeLaunchInputs.runtimePackageFingerprint(inventory);
        if (!fingerprint.equals(text(spec, "mapPackageFingerprintSha256"))) throw refused("Installed map changed during successor inspection.");
        WorldBuilderPackedConverter.Inspection launch = new WorldBuilderPackedConverter.Inspection(
            "", "", "", "", "", fingerprint, 0, 0, inventory, manifest, WorldBuilderHashes.sha256(mapRoot.resolve("manifest.json")));
        Map<String,Object> map = new LinkedHashMap<String,Object>();
        map.put("migrationId", profile.mapMigrationId);
        map.put("sourceRelativePath", relative(target, mapRoot));
        map.put("destinationRole", "canonical-signed-layered-map"); map.put("executionBoundary", BOUNDARY);
        for (String key : Arrays.asList("preparedSourceFingerprintSha256", "discoveryReportSha256", "conversionPlanFingerprintSha256",
            "conversionPlanSha256", "conversionReportSha256", "discoveryReconciliationSha256")) map.put(key, "");
        map.put("outputPackageFingerprintSha256", fingerprint);
        map.put("terrainCount", Long.valueOf(0)); map.put("placementCount", Long.valueOf(0));
        map.put("outputInventory", inventory); map.put("packageReady", Boolean.TRUE);
        Map<String,Object> migration = new LinkedHashMap<String,Object>();
        migration.put("schemaVersion", Long.valueOf(1)); migration.put("manifestType", "world-builder-current-runtime-migration-plan");
        migration.put("migratorId", profile.migratorId); migration.put("configurationMigrationId", profile.configurationMigrationId);
        migration.put("typedConfiguration", typed); migration.put("durableStateMigrationId", profile.stateMigrationId);
        migration.put("durableState", durable(target, spec)); migration.put("mapMigration", map);
        Map<String,Object> staged = WorldBuilderPreservationStagedMigrator.planManaged(target, typed, selected, true, launch, spec);
        staged.put("sqliteSchemaMigrationReady", Boolean.FALSE);
        list(staged.get("readinessBlockers")).add("sqlite-schema-validation-pending-provider-sealed-migration");
        migration.put("stagedExecution", staged);
        WorldBuilderAdaptiveExporter.bindFingerprint(migration, "migrationPlanFingerprintSha256");
        return migration;
    }

    static Map<String,Object> typed(Path target, Map<String,Object> spec) throws IOException, WorldBuilderContractException {
        Path path = Paths.get(text(spec, "serverConfigurationPath"));
        Map<String,String> values = new LinkedHashMap<String,String>();
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            String content = line.split("#", 2)[0].trim(); if (content.isEmpty()) continue;
            int colon = content.indexOf(':'); if (colon <= 0) throw refused("Installed configuration contains malformed syntax.");
            String key = content.substring(0, colon).trim(), value = content.substring(colon + 1).trim();
            if (values.put(key, value) != null) throw refused("Installed configuration repeats a key.");
        }
        if (!"sqlite".equals(values.get("db_type")) || !"current_base".equals(values.get("db_name")))
            throw refused("Managed Base requires its current SQLite configuration.");
        Map<String,Object> typed = new LinkedHashMap<String,Object>();
        typed.put("schemaVersion", Long.valueOf(1)); typed.put("manifestType", "world-builder-current-base-configuration");
        typed.put("sourceRelativePath", relative(target, path)); typed.put("precedence", "managed-installed-configuration");
        typed.put("duplicatePolicy", "first-value-wins"); typed.put("serverName", required(values, "server_name"));
        typed.put("experienceRate", Long.valueOf(1)); typed.put("combatExperienceRate", number(values, "combat_exp_rate", 100));
        typed.put("skillingExperienceRate", number(values, "skilling_exp_rate", 100));
        typed.put("bindAddress", required(values, "server_bind_address")); typed.put("gamePort", number(values, "server_port", 65535));
        typed.put("websocketPort", number(values, "ws_server_port", 65535));
        if (!typed.get("gamePort").equals(spec.get("gamePort")) || typed.get("gamePort").equals(typed.get("websocketPort")))
            throw refused("Installed configuration ports disagree with the launch generation.");
        Map<String,Object> database = new LinkedHashMap<String,Object>(); database.put("engine", "sqlite");
        for (String key : Arrays.asList("host", "sourceSchema", "stageSchema", "userEnvironmentName", "passwordEnvironmentName")) database.put(key, "");
        database.put("port", Long.valueOf(0)); typed.put("databaseMigration", database);
        Map<String,Object> source = new LinkedHashMap<String,Object>(); source.put("relativePath", relative(target, path));
        source.put("size", Long.valueOf(Files.size(path))); source.put("sha256", WorldBuilderHashes.sha256(path));
        typed.put("sourceInventory", new ArrayList<Object>(Collections.singletonList(source)));
        for (String key : Arrays.asList("externalSecretReferences", "untranslatedKeys", "configurationBlockers", "translations"))
            typed.put(key, new ArrayList<Object>());
        return typed;
    }

    static List<Object> durable(Path target, Map<String,Object> spec) throws IOException, WorldBuilderContractException {
        TreeMap<String,String> paths = new TreeMap<String,String>();
        paths.put(relative(target, Paths.get(text(spec, "serverStateRoot")).resolve("current_base.db")), "preserved-database");
        for (String role : Arrays.asList("server", "client")) {
            Path root = Paths.get(text(spec, role + "SideStateRoot"));
            try (DirectoryStream<Path> entries = Files.newDirectoryStream(root)) {
                for (Path entry : entries) paths.put(relative(target, entry), "preserved-" + role + "-side-state");
            }
        }
        List<Object> result = new ArrayList<Object>();
        WorldBuilderReadOnlyTarget source = WorldBuilderReadOnlyTarget.open(target);
        for (Map.Entry<String,String> entry : paths.entrySet()) {
            Path path = source.requiredFile(entry.getKey());
            if (!Files.getPosixFilePermissions(path).equals(PosixFilePermissions.fromString("rw-------"))) throw refused("Installed state is not private.");
            if (Files.size(path) > (entry.getValue().equals("preserved-database") ? 4294967296L : 1048576L)) throw refused("Installed state exceeds its input bound.");
            Map<String,Object> row = new LinkedHashMap<String,Object>(); row.put("role", entry.getValue()); row.put("relativePath", entry.getKey());
            row.put("sourceSha256", WorldBuilderHashes.sha256(path)); row.put("policy", "copy-to-staged-durable-state-and-verify-before-cutover"); result.add(row);
        }
        return result;
    }

    static void stageMap(Path target, Path stage, Map<String,Object> migration) throws IOException, WorldBuilderContractException {
        Map<String,Object> spec = WorldBuilderCurrentRuntimeInstalledGeneration.readSpecification(target);
        Map<String,Object> map = object(migration.get("mapMigration"));
        Path root = Paths.get(text(spec, "mapRoot"));
        if (!BOUNDARY.equals(map.get("executionBoundary")) || !relative(target, root).equals(map.get("sourceRelativePath"))
            || !WorldBuilderJsonDocuments.canonical(WorldBuilderPackedConverter.outputInventory(root, PACKAGE)).equals(WorldBuilderJsonDocuments.canonical(map.get("outputInventory"))))
            throw refused("Current active map changed after successor preview.");
        for (Object raw : list(map.get("outputInventory"))) {
            Map<String,Object> row = object(raw); String destination = text(row, "relativePath");
            if (!destination.startsWith(PACKAGE + "/")) throw refused("Successor map copy escaped its package namespace.");
            Path output = stage.resolve(destination);
            Files.createDirectories(output.getParent(), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            Files.copy(WorldBuilderReadOnlyTarget.open(root).requiredFile(destination.substring(PACKAGE.length() + 1)), output);
            Files.setPosixFilePermissions(output, PosixFilePermissions.fromString("rw-------"));
        }
        new WorldBuilderCurrentRuntimeUpgradeTransaction().verifyReviewedPreservationMap(stage, map);
    }

    private static String relative(Path target, Path path) throws WorldBuilderContractException {
        if (!path.isAbsolute() || !path.normalize().equals(path) || !path.startsWith(target) || path.equals(target)) throw refused("Managed input escaped the target.");
        return target.relativize(path).toString().replace('\\', '/');
    }
    private static String required(Map<String,String> values, String key) throws WorldBuilderContractException {
        String value = values.get(key); if (value == null || value.isEmpty() || value.length() > 253) throw refused("Installed configuration omits a bounded required value."); return value;
    }
    private static Long number(Map<String,String> values, String key, long max) throws WorldBuilderContractException {
        try { long value = Long.parseLong(required(values, key)); if (value < 1 || value > max) throw new NumberFormatException(); return Long.valueOf(value); }
        catch (NumberFormatException failure) { throw refused("Installed configuration number is outside its bound."); }
    }
    private static Map<String,Object> read(Path path) throws IOException, WorldBuilderContractException {
        try { return WorldBuilderJsonDocuments.readObject(path); } catch (WorldBuilderDiscoveryException failure) { throw refused("Managed input JSON is malformed."); }
    }
    @SuppressWarnings("unchecked") private static Map<String,Object> object(Object value) { return (Map<String,Object>)value; }
    @SuppressWarnings("unchecked") private static List<Object> list(Object value) { return (List<Object>)value; }
    private static String text(Map<String,Object> value, String key) throws WorldBuilderContractException { return WorldBuilderBoundedInventory.string(value.get(key), "managed-base-inputs", key); }
    private static WorldBuilderContractException refused(String message) { return new WorldBuilderContractException(WorldBuilderErrorCodes.CONVERSION_BLOCKED, "managed-base-inputs", message); }
}
