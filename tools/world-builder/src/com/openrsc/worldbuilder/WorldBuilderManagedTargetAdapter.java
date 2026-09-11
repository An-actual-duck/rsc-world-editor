package com.openrsc.worldbuilder;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** The committed installed generation outranks retained historical intake files. */
final class WorldBuilderManagedTargetAdapter implements WorldBuilderLayoutAdapter {
    static final String ID = "managed-current-v1";
    static final String CAPABILITY = "managed-current-base-capture-v1";
    static final String LEDGER = WorldBuilderCurrentRuntimeInstalledGeneration.LEDGER;

    public String id() { return ID; }
    static boolean present(WorldBuilderReadOnlyTarget target) throws WorldBuilderContractException {
        return target.exists(LEDGER) || target.exists(".world-builder/current-runtime");
    }
    static boolean report(Map<String,Object> report) {
        Object value = report.get("capability");
        return value instanceof Map && ID.equals(((Map<?,?>)value).get("adapterId"))
            && CAPABILITY.equals(((Map<?,?>)value).get("capabilityId")) && "layered".equals(report.get("representation"));
    }
    public ProbeResult probe(WorldBuilderReadOnlyTarget target) throws WorldBuilderContractException {
        return new ProbeResult(ID, present(target) ? Probe.SUPPORTED : Probe.NO_EVIDENCE,
            Collections.singletonList(new ProbeResult.Anchor("installed-ledger", LEDGER, target.exists(LEDGER), true)));
    }
    public WorldBuilderAdapterInspection inspect(WorldBuilderReadOnlyTarget target,
        WorldBuilderTargetCapability ignored, String role) throws WorldBuilderContractException {
        try {
            Map<String,Object> spec = WorldBuilderCurrentRuntimeInstalledGeneration.readSpecification(target.root);
            Map<String,Object> ledger = WorldBuilderCurrentRuntimeContracts.read(
                WorldBuilderCurrentRuntimeContracts.Kind.TARGET_LEDGER, target.requiredFile(LEDGER)).root;
            if (!"current-base-v1".equals(ledger.get("variantId"))) throw new IOException("Only Current Base capture is supported.");
            if (role != null && !role.isEmpty() && !"current".equals(role))
                throw new IOException("Select current for a managed installation; historical configurations are not active.");
            Path map = Paths.get((String)spec.get("mapRoot"));
            String mapRelative = relative(target.root, map);
            List<WorldBuilderReadOnlyTarget.FileState> files = new ArrayList<>();
            files.add(target.requiredState("configuration.current", LEDGER));
            // Capture public immutable metadata/map only, never player databases, keys or side state.
            files.add(target.requiredState("installed-composition", relative(target.root, Paths.get((String)spec.get("compositionIdentityPath")))));
            try (java.util.stream.Stream<Path> paths = Files.walk(map)) {
                for (Path path : (Iterable<Path>)paths.filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS))::iterator)
                    files.add(target.requiredState(path.equals(map.resolve("manifest.json"))
                        ? "installed-map-manifest" : "installed-map-file", relative(target.root, path)));
            }
            // Validation re-reads every descriptor, map/code tree and active selection; never guess on drift.
            if (!spec.equals(WorldBuilderCurrentRuntimeInstalledGeneration.readSpecification(target.root)))
                throw new IOException("Installed generation changed during discovery.");
            WorldBuilderReadOnlyTarget.FileState selected = target.requiredState("configuration.current", LEDGER);
            WorldBuilderAdapterInspection.ConfigurationCandidate candidate =
                new WorldBuilderAdapterInspection.ConfigurationCandidate("current", LEDGER, selected.sha256);
            return new WorldBuilderAdapterInspection(ID, CAPABILITY, LEDGER, selected.sha256, "layered",
                Collections.singletonList(candidate), candidate, files, Collections.singletonList(
                new WorldBuilderAdapterInspection.Check("installed-generation", "passed",
                    "One verified committed server/client/map generation; no historical fallback.",
                    "Active package: " + mapRelative)));
        } catch (IOException failure) {
            throw WorldBuilderReadOnlyTarget.problem(WorldBuilderErrorCodes.RUNTIME_UPGRADE_REQUIRED, LEDGER,
                "Managed target cannot be captured: " + failure.getMessage(),
                "Restore or recover the managed installation. Historical map files will not be used as a fallback.");
        }
    }
    static String relative(Path root, Path value) throws IOException {
        if (!value.startsWith(root) || !value.equals(value.toRealPath())) throw new IOException("Managed source escapes its target.");
        return root.relativize(value).toString().replace('\\', '/');
    }
}
