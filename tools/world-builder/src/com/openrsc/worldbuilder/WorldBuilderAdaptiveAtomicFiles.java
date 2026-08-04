package com.openrsc.worldbuilder;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Objects;

/** Same-directory, no-overwrite publication for complete files and directories. */
final class WorldBuilderAdaptiveAtomicFiles {
	private WorldBuilderAdaptiveAtomicFiles() {
	}

	static void moveNew(Path source, Path destination, String operation,
		String relative) throws IOException, WorldBuilderContractException {
		Path sourceParent = source.toAbsolutePath().normalize().getParent();
		Path destinationParent = destination.toAbsolutePath().normalize().getParent();
		if (sourceParent == null || destinationParent == null
			|| !sourceParent.toRealPath().equals(destinationParent.toRealPath())) {
			throw problem(operation, relative,
				"Atomic no-overwrite publication requires one real same-directory operation.",
				"Use a local filesystem and keep staging beside its final destination.");
		}
		BasicFileAttributes sourceAttributes = Files.readAttributes(source,
			BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
		String provider = source.getFileSystem().provider().getClass().getName();
		if (sourceAttributes.isRegularFile() && !sourceAttributes.isSymbolicLink()) {
			if (isWindowsProvider(provider)) {
				publishWindowsNativeMove(source, destination, operation, relative);
			} else if (isUnixProvider(provider)) {
				publishRegularFile(source, destination, sourceAttributes.fileKey(),
					operation, relative);
			} else {
				throw unsupportedProvider(operation, relative);
			}
			return;
		}
		if (sourceAttributes.isDirectory() && !sourceAttributes.isSymbolicLink()) {
			if (isWindowsProvider(provider)) {
				publishWindowsNativeMove(source, destination, operation, relative);
			} else if (isUnixProvider(provider)) {
				publishDirectory(source, destination, operation, relative);
			} else {
				throw unsupportedProvider(operation, relative);
			}
			return;
		}
		throw problem(operation, relative,
			"Publication source is linked or is not a regular file/directory.",
			"Use only the exact invocation-owned staging path.");
	}

	private static void publishWindowsNativeMove(Path source, Path destination,
		String operation, String relative)
		throws IOException, WorldBuilderContractException {
		try {
			/* The reviewed JDK Windows provider maps this same-volume, no-option
			 * operation to MoveFileEx without REPLACE_EXISTING. */
			Files.move(source, destination);
		} catch (FileAlreadyExistsException collision) {
			throw collision(operation, relative, collision);
		} catch (IOException failure) {
			if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
				FileAlreadyExistsException appeared = new FileAlreadyExistsException(
					destination.toString(), null, "destination appeared during native move");
				appeared.addSuppressed(failure);
				throw collision(operation, relative, appeared);
			}
			throw failure;
		}
	}

	private static void publishRegularFile(Path source, Path destination,
		Object sourceKey, String operation, String relative)
		throws IOException, WorldBuilderContractException {
		if (sourceKey == null) throw problem(operation, relative,
			"Filesystem does not expose a stable staging-file identity.",
			"Use a local filesystem that exposes stable file keys.");
		try {
			/* A same-filesystem hard-link insertion is one atomic CREATE_NEW
			 * publication. Removing the private staging name leaves one link. */
			Files.createLink(destination, source);
		} catch (FileAlreadyExistsException collision) {
			throw collision(operation, relative, collision);
		} catch (UnsupportedOperationException unsupported) {
			throw problem(operation, relative,
				"Filesystem cannot atomically publish a no-overwrite regular file.",
				"Use a local filesystem supporting same-filesystem hard links.",
				unsupported);
		}
		BasicFileAttributes published;
		try {
			published = Files.readAttributes(destination,
				BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
		} catch (IOException verificationFailure) {
			throw recovery(operation, relative,
				"No-overwrite file publication could not verify its exact new link.",
				verificationFailure);
		}
		if (!published.isRegularFile() || published.isSymbolicLink()
			|| !Objects.equals(sourceKey, published.fileKey())) {
			throw recovery(operation, relative,
				"No-overwrite file publication changed identity before verification.",
				new IOException("published link identity mismatch"));
		}
		try {
			Files.delete(source);
		} catch (IOException deleteFailure) {
			try {
				BasicFileAttributes destinationAttributes = Files.readAttributes(destination,
					BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
				if (sourceKey == null || destinationAttributes.fileKey() == null
					|| !destinationAttributes.isRegularFile()
					|| destinationAttributes.isSymbolicLink()
					|| !Objects.equals(sourceKey, destinationAttributes.fileKey())) {
					throw new IOException("Published file identity changed before rollback");
				}
				Files.delete(destination);
			} catch (IOException rollbackFailure) {
				rollbackFailure.addSuppressed(deleteFailure);
				throw recovery(operation, relative,
					"No-overwrite file publication could not remove its exact new link.",
					rollbackFailure);
			}
			throw deleteFailure;
		}
	}

	private static void publishDirectory(Path source, Path destination,
		String operation, String relative)
		throws IOException, WorldBuilderContractException {
		Object markerKey = null;
		boolean markerCreated = false;
		try {
			/* Reserve the destination name with CREATE_NEW. The atomic move may
			 * replace only this exact empty invocation-owned marker directory. */
			Files.createDirectory(destination);
			markerCreated = true;
			BasicFileAttributes marker = Files.readAttributes(destination,
				BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
			markerKey = marker.fileKey();
			if (!marker.isDirectory() || marker.isSymbolicLink() || markerKey == null) {
				throw new IOException("Destination marker has no stable directory identity");
			}
		} catch (FileAlreadyExistsException collision) {
			throw collision(operation, relative, collision);
		} catch (IOException creationFailure) {
			if (markerCreated) cleanupMarker(destination, markerKey, operation,
				relative, creationFailure);
			throw creationFailure;
		}
		try {
			/* Keeping the marker directory open pins its native identity until
			 * the atomic replacement completes or fails. */
			try (DirectoryStream<Path> markerHandle =
				Files.newDirectoryStream(destination)) {
				requireMarkerIdentity(destination, markerKey);
				if (markerHandle.iterator().hasNext()) throw new IOException(
					"Reserved destination directory is no longer empty");
				Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE,
					StandardCopyOption.REPLACE_EXISTING);
			}
		} catch (AtomicMoveNotSupportedException unsupported) {
			cleanupMarker(destination, markerKey, operation, relative, unsupported);
			throw problem(operation, relative,
				"Filesystem cannot atomically publish the reserved export directory.",
				"Use a local filesystem supporting atomic same-directory directory moves.",
				unsupported);
		} catch (IOException failure) {
			cleanupMarker(destination, markerKey, operation, relative, failure);
			throw failure;
		}
	}

	private static void requireEmptyMarker(Path marker, Object markerKey)
		throws IOException {
		requireMarkerIdentity(marker, markerKey);
		try (DirectoryStream<Path> entries = Files.newDirectoryStream(marker)) {
			if (entries.iterator().hasNext()) {
				throw new IOException("Reserved destination directory is no longer empty");
			}
		}
	}

	private static void requireMarkerIdentity(Path marker, Object markerKey)
		throws IOException {
		BasicFileAttributes attributes = Files.readAttributes(marker,
			BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
		if (!attributes.isDirectory() || attributes.isSymbolicLink()
			|| markerKey == null || !Objects.equals(markerKey, attributes.fileKey())) {
			throw new IOException("Reserved destination directory identity changed");
		}
	}

	private static void cleanupMarker(Path marker, Object markerKey,
		String operation, String relative, Throwable original)
		throws WorldBuilderContractException {
		try {
			if (!Files.exists(marker, LinkOption.NOFOLLOW_LINKS)) return;
			requireEmptyMarker(marker, markerKey);
			Files.delete(marker);
		} catch (IOException cleanupFailure) {
			cleanupFailure.addSuppressed(original);
			throw recovery(operation, relative,
				"Atomic publication could not prove and remove its reserved marker.",
				cleanupFailure);
		}
	}

	private static WorldBuilderContractException collision(String operation,
		String relative, FileAlreadyExistsException cause) {
		return new WorldBuilderContractException(
			WorldBuilderErrorCodes.TARGET_DRIFT, operation, relative, false,
			"Publication destination appeared after validation and was preserved.",
			"Preserve the appeared path and request a fresh operation.", cause);
	}

	private static WorldBuilderContractException recovery(String operation,
		String relative, String message, Throwable cause) {
		return new WorldBuilderContractException(
			WorldBuilderErrorCodes.RECOVERY_REQUIRED, operation, relative, true,
			message, "Preserve every transaction artifact and request exact recovery.", cause);
	}

	private static boolean isWindowsProvider(String provider) {
		return "sun.nio.fs.WindowsFileSystemProvider".equals(provider);
	}

	private static boolean isUnixProvider(String provider) {
		return "sun.nio.fs.LinuxFileSystemProvider".equals(provider)
			|| "sun.nio.fs.UnixFileSystemProvider".equals(provider)
			|| "sun.nio.fs.MacOSXFileSystemProvider".equals(provider);
	}

	private static WorldBuilderContractException unsupportedProvider(
		String operation, String relative) {
		return problem(operation, relative,
			"Filesystem provider has no reviewed atomic no-overwrite publication path.",
			"Use a native local Linux, macOS, or Windows filesystem provider.");
	}

	private static WorldBuilderContractException problem(String operation,
		String relative, String message, String nextStep) {
		return new WorldBuilderContractException(WorldBuilderErrorCodes.MUTATION_FAILED,
			operation, relative, false, message, nextStep);
	}

	private static WorldBuilderContractException problem(String operation,
		String relative, String message, String nextStep, Throwable cause) {
		return new WorldBuilderContractException(WorldBuilderErrorCodes.MUTATION_FAILED,
			operation, relative, false, message, nextStep, cause);
	}
}
