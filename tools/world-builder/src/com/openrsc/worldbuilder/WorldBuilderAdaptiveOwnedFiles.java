package com.openrsc.worldbuilder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.LinkedHashMap;
import java.util.Map;

/** Tracks exact temporary file identities created by one invocation. */
final class WorldBuilderAdaptiveOwnedFiles {
	private final Map<Path,OwnedFile> identities =
		new LinkedHashMap<Path,OwnedFile>();

	void reserve(Path path) throws IOException {
		Files.createFile(path);
		Path key = key(path);
		OwnedFile owned = new OwnedFile();
		identities.put(key, owned);
		BasicFileAttributes attributes = attributes(path);
		if (!attributes.isRegularFile() || attributes.isSymbolicLink()) {
			throw new IOException("Created staging path is not a regular file: "
				+ path.getFileName());
		}
		owned.fileKey = attributes.fileKey();
	}

	void seal(Path path) throws IOException {
		Path key = key(path);
		OwnedFile owned = identities.get(key);
		if (owned == null) throw new IOException(
			"Cannot seal an unowned staging path: " + path.getFileName());
		BasicFileAttributes attributes = attributes(path);
		if (!owned.matchesIdentity(attributes)) throw new IOException(
			"Staging identity changed before verification: " + path.getFileName());
		owned.size = attributes.size();
		owned.sha256 = WorldBuilderHashes.sha256(path);
	}

	void forget(Path path) {
		identities.remove(key(path));
	}

	IOException cleanup() {
		try {
			for (Map.Entry<Path,OwnedFile> entry : identities.entrySet()) {
				Path path = entry.getKey();
				if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) continue;
				BasicFileAttributes attributes = attributes(path);
				if (!entry.getValue().matches(path, attributes)) {
					throw new IOException("Invocation-owned staging identity changed: "
						+ path.getFileName());
				}
			}
			for (Map.Entry<Path,OwnedFile> entry : identities.entrySet()) {
				Path path = entry.getKey();
				if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) continue;
				BasicFileAttributes attributes = attributes(path);
				if (!entry.getValue().matches(path, attributes)) throw new IOException(
					"Invocation-owned staging identity changed before deletion: "
						+ path.getFileName());
				Files.delete(path);
			}
			identities.clear();
			return null;
		} catch (IOException failure) {
			return failure;
		}
	}

	void cleanupOrThrow() throws IOException {
		IOException failure = cleanup();
		if (failure != null) throw failure;
	}

	private static BasicFileAttributes attributes(Path path) throws IOException {
		return Files.readAttributes(path, BasicFileAttributes.class,
			LinkOption.NOFOLLOW_LINKS);
	}

	private static Path key(Path path) {
		return path.toAbsolutePath().normalize();
	}

	private static final class OwnedFile {
		Object fileKey;
		long size = -1L;
		String sha256;

		boolean matchesIdentity(BasicFileAttributes attributes) {
			return attributes.isRegularFile() && !attributes.isSymbolicLink()
				&& fileKey != null && fileKey.equals(attributes.fileKey());
		}

		boolean matches(Path path, BasicFileAttributes attributes) throws IOException {
			return matchesIdentity(attributes)
				&& (sha256 == null || (size == attributes.size()
					&& sha256.equals(WorldBuilderHashes.sha256(path))));
		}
	}
}
