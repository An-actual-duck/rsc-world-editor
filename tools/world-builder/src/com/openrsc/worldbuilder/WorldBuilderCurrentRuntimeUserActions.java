package com.openrsc.worldbuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;

/** Shared desktop/terminal routing for the native Base project workflow. */
final class WorldBuilderCurrentRuntimeUserActions {
    private WorldBuilderCurrentRuntimeUserActions() { }

    static boolean isNativeBase(WorldBuilderAdaptiveProjectLifecycle.VerifiedProject project) {
        Object target = project.manifest.get("target");
        return ("target-layered".equals(project.origin) && WorldBuilderManagedTargetAdapter.report(project.discoveryReport))
            || "target-packed".equals(project.origin) && target instanceof Map
            && "preservation-source-jag-v1".equals(((Map<?,?>)target).get("adapterId"))
            && "preservation-c0102e-data-conversion-v1".equals(((Map<?,?>)target).get("capabilityId"));
    }

    static Path target(WorldBuilderAdaptiveProjectLifecycle.VerifiedProject project) throws IOException {
        Object target = project.manifest.get("target");
        Object locator = target instanceof Map ? ((Map<?,?>)target).get("locatorDisplay") : null;
        if (!(locator instanceof String)) throw new IOException("Project has no recorded server target.");
        try {
            return directory(Paths.get((String)locator));
        } catch (InvalidPathException invalid) {
            throw new IOException("Project's recorded server target is invalid.", invalid);
        }
    }

    static Path workspace(Path target) throws IOException {
        return workspace(target, true);
    }

    static Path workspace(Path target, boolean create) throws IOException {
        target = directory(target);
        Path parent = target.getParent();
        if (parent == null) throw new IOException("A filesystem root cannot be a managed server target.");
        Path workspace = parent.resolve(".world-builder-transactions-"
            + WorldBuilderHashes.sha256(target.toString().getBytes(StandardCharsets.UTF_8)).substring(0, 16));
        if (create && !Files.exists(workspace, LinkOption.NOFOLLOW_LINKS)) {
            Files.createDirectory(workspace, PosixFilePermissions.asFileAttribute(
                PosixFilePermissions.fromString("rwx------")));
            WorldBuilderAdaptiveDurability.forceDirectory(parent);
        }
        directory(workspace);
        if (!Files.getPosixFilePermissions(workspace, LinkOption.NOFOLLOW_LINKS)
                .equals(PosixFilePermissions.fromString("rwx------"))
            || !Files.getOwner(workspace, LinkOption.NOFOLLOW_LINKS).equals(Files.getOwner(target, LinkOption.NOFOLLOW_LINKS))
            || !Files.getFileStore(workspace).equals(Files.getFileStore(target)))
            throw new IOException("The external transaction workspace must be private, target-owned, and on the target filesystem. Use the explicit transaction CLI to select another workspace.");
        return workspace;
    }

    static WorldBuilderCurrentRuntimeUpgradeTransaction.Preview previewUpgrade(
        Path installation, WorldBuilderAdaptiveProjectLifecycle.VerifiedProject project)
        throws IOException, WorldBuilderContractException {
        Path target = target(project);
        return new WorldBuilderCurrentRuntimeUpgradeTransaction().previewPreservation(
            target, workspace(target), installation.resolve("current-platform"),
            installation.resolve("current-platform/composition-identity.json"), null,
            java.util.UUID.randomUUID().toString(), null, null, project.projectRoot);
    }

    static String upgradeSummary(WorldBuilderCurrentRuntimeUpgradeTransaction.Preview preview) {
        Map<?,?> destination = (Map<?,?>)preview.plan.get("destination");
        Map<?,?> profile = (Map<?,?>)preview.plan.get("executionProfile");
        return "Upgrade Target Runtime — Current Base\n\nServer target: " + preview.targetRoot
            + "\nDestination: " + destination.get("platformReleaseId") + " / " + destination.get("variantId")
            + "\nActivation authorized: " + preview.plan.get("activationAuthorized")
            + "\n\nInstalls the reviewed server/client composition and canonical map together."
            + "\nMigrates a backed-up copy of player state; retains existing owner keys and filters."
            + "\nAfter activation, replaces Start-Linux.sh with managed startup and retains its exact original backup."
            + "\nThe server and client must remain offline through verification and activation."
            + "\n\n" + profile.get("executionReadinessReason")
            + "\n\nTransaction evidence: " + preview.transactionRoot.resolve((String)preview.plan.get("transactionId"))
            + "\nPlan SHA-256: " + preview.plan.get("planFingerprintSha256")
            + "\nExact confirmation: " + preview.plan.get("confirmationIdentity") + "\n";
    }

    static String installStartupAfterUpgrade(Path target, Path installation) {
        try { return "\nManaged startup installed: " + WorldBuilderManagedLaunch.install(target, installation)
            + "\nRun without arguments for a local server/client pair, or with server/client for separate roles."; }
        catch (Exception failure) { return "\nRuntime activation succeeded, but managed startup installation needs attention: "
            + failure.getMessage() + "\nDo not use the historical startup script. Retry install-current-launcher with --confirm INSTALL while both roles are offline."; }
    }

    private static Path directory(Path path) throws IOException {
        if (!path.isAbsolute() || !path.normalize().equals(path)
            || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) || !path.equals(path.toRealPath()))
            throw new IOException("Server target or transaction workspace is missing or aliased: " + path);
        return path;
    }
}
