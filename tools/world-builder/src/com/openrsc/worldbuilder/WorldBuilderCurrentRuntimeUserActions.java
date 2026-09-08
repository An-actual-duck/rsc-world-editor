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
        return "target-packed".equals(project.origin) && target instanceof Map
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
        target = directory(target);
        Path parent = target.getParent();
        if (parent == null) throw new IOException("A filesystem root cannot be a managed server target.");
        Path workspace = parent.resolve(".world-builder-transactions-"
            + WorldBuilderHashes.sha256(target.toString().getBytes(StandardCharsets.UTF_8)).substring(0, 16));
        if (!Files.exists(workspace, LinkOption.NOFOLLOW_LINKS)) {
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

    private static Path directory(Path path) throws IOException {
        if (!path.isAbsolute() || !path.normalize().equals(path)
            || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) || !path.equals(path.toRealPath()))
            throw new IOException("Server target or transaction workspace is missing or aliased: " + path);
        return path;
    }
}
