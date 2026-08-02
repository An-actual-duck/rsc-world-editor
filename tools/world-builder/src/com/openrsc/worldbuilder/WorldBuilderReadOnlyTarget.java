package com.openrsc.worldbuilder;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;

/** Contained, no-follow filesystem reads shared by adaptive discovery adapters. */
final class WorldBuilderReadOnlyTarget {
	private static final String OPERATION = "discover-target";

	final Path root;

	private WorldBuilderReadOnlyTarget(Path root) {
		this.root = root;
	}

	static WorldBuilderReadOnlyTarget open(Path requested)
		throws WorldBuilderContractException {
		if (requested == null) {
			throw problem(WorldBuilderErrorCodes.NO_TARGET, "target-root",
				"A target directory was not supplied.",
				"Supply the server root, or an existing empty directory for standalone mode.");
		}
		Path normalized = requested.toAbsolutePath().normalize();
		try {
			if (Files.isSymbolicLink(normalized)
				|| !Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS)) {
				throw problem(WorldBuilderErrorCodes.UNSAFE_PATH, "target-root",
					"The target root is missing, is not a directory, or is a symbolic link.",
					"Use a real, existing directory as the target root.");
			}
		} catch (SecurityException denied) {
			throw problem(WorldBuilderErrorCodes.UNSAFE_PATH, "target-root",
				"The target root cannot be inspected safely.",
				"Grant read access to the target directory and try again.", denied);
		}
		return new WorldBuilderReadOnlyTarget(normalized);
	}

	boolean exists(String relative) throws WorldBuilderContractException {
		Path path = resolve(relative);
		try {
			Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
			return true;
		} catch (java.nio.file.NoSuchFileException missing) {
			return false;
		} catch (IOException failure) {
			throw problem(WorldBuilderErrorCodes.MALFORMED_SERVER, relative,
				"Bounded target probe evidence cannot be inspected: " + safe(failure.getMessage()),
				"Verify read permissions and retry discovery.", failure);
		} catch (SecurityException denied) {
			throw problem(WorldBuilderErrorCodes.UNSAFE_PATH, relative,
				"Bounded target probe evidence cannot be inspected safely.",
				"Grant read access to the bounded probe path and retry.", denied);
		}
	}

	Path requiredFile(String relative) throws WorldBuilderContractException {
		Path path = resolve(relative);
		requireSafeParents(path, relative);
		try {
			BasicFileAttributes attributes = Files.readAttributes(
				path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
			if (!attributes.isRegularFile() || Files.isSymbolicLink(path)) {
				throw problem(WorldBuilderErrorCodes.UNSAFE_PATH, relative,
					"Expected a regular no-follow target file.",
					"Replace the path with a contained regular file and retry.");
			}
			rejectHardLink(path, relative);
			return path;
		} catch (java.nio.file.NoSuchFileException missing) {
			throw problem(WorldBuilderErrorCodes.MALFORMED_SERVER, relative,
				"Required target evidence is missing.",
				"Restore the configured file or select the correct server configuration.", missing);
		} catch (WorldBuilderContractException refusal) {
			throw refusal;
		} catch (IOException failure) {
			throw problem(WorldBuilderErrorCodes.MALFORMED_SERVER, relative,
				"Required target evidence could not be inspected: " + safe(failure.getMessage()),
				"Verify read permissions and retry discovery.", failure);
		}
	}

	Path requiredDirectory(String relative) throws WorldBuilderContractException {
		Path path = resolve(relative);
		requireSafeParents(path, relative);
		try {
			BasicFileAttributes attributes = Files.readAttributes(
				path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
			if (!attributes.isDirectory() || Files.isSymbolicLink(path)) {
				throw problem(WorldBuilderErrorCodes.UNSAFE_PATH, relative,
					"Expected a real contained target directory.",
					"Replace the path with a contained directory and retry.");
			}
			return path;
		} catch (java.nio.file.NoSuchFileException missing) {
			throw problem(WorldBuilderErrorCodes.MALFORMED_SERVER, relative,
				"Required target directory is missing.",
				"Restore the configured directory or select the correct configuration.", missing);
		} catch (WorldBuilderContractException refusal) {
			throw refusal;
		} catch (IOException failure) {
			throw problem(WorldBuilderErrorCodes.MALFORMED_SERVER, relative,
				"Required target directory could not be inspected: " + safe(failure.getMessage()),
				"Verify read permissions and retry discovery.", failure);
		}
	}

	FileState requiredState(String role, String relative)
		throws WorldBuilderContractException {
		Path path = requiredFile(relative);
		try {
			long size = Files.size(path);
			if (size > WorldBuilderContractLimits.MAX_INVENTORY_FILE_BYTES) {
				throw problem(WorldBuilderErrorCodes.INVENTORY_LIMIT_EXCEEDED, relative,
					"Target evidence exceeds the per-file discovery limit.",
					"Use a supported bounded map layout or split the evidence through a reviewed adapter.");
			}
			return new FileState(role, relative, true, size, sha256NoFollow(path));
		} catch (WorldBuilderContractException refusal) {
			throw refusal;
		} catch (IOException failure) {
			throw problem(WorldBuilderErrorCodes.DISCOVERY_DRIFT, relative,
				"Target evidence changed or became unreadable while it was hashed.",
				"Stop server updates and retry discovery.", failure);
		}
	}

	FileState optionalState(String role, String relative)
		throws WorldBuilderContractException {
		if (!exists(relative)) return new FileState(role, relative, false, 0L, "");
		return requiredState(role, relative);
	}

	Map<String,Object> readObject(String relative)
		throws WorldBuilderContractException {
		Path path = requiredFile(relative);
		try {
			return WorldBuilderJsonDocuments.readObject(path);
		} catch (WorldBuilderDiscoveryException malformed) {
			String message = safe(malformed.getMessage()).replace(root.toString(), relative);
			String code = message.contains("invalid size")
				|| message.contains("complexity limit")
				? WorldBuilderErrorCodes.CONTRACT_LIMIT_EXCEEDED
				: WorldBuilderErrorCodes.MALFORMED_JSON;
			throw problem(code, relative,
				"Target JSON evidence is malformed: " + message,
				"Correct the bounded UTF-8 JSON document and retry.", malformed);
		} catch (IOException failure) {
			throw problem(WorldBuilderErrorCodes.DISCOVERY_DRIFT, relative,
				"Target JSON evidence changed or became unreadable during discovery.",
				"Stop server updates and retry discovery.", failure);
		}
	}

	String relative(Path path) throws WorldBuilderContractException {
		Path normalized = path.toAbsolutePath().normalize();
		if (!normalized.startsWith(root)) {
			throw problem(WorldBuilderErrorCodes.UNSAFE_PATH, "target-root",
				"A discovered path escaped the target root.",
				"Use only adapter-declared paths inside the target root.");
		}
		String relative = root.relativize(normalized).toString().replace('\\', '/');
		try {
			return WorldBuilderPortablePath.require(relative, OPERATION);
		} catch (WorldBuilderContractException unsafe) {
			throw problem(WorldBuilderErrorCodes.UNSAFE_PATH, "target-root",
				"A discovered path is not portable: " + relative,
				"Rename the target path to a portable forward-slash relative path.", unsafe);
		}
	}

	private Path resolve(String relative) throws WorldBuilderContractException {
		try {
			return WorldBuilderPortablePath.resolveContained(root, relative, OPERATION);
		} catch (WorldBuilderContractException unsafe) {
			throw problem(WorldBuilderErrorCodes.UNSAFE_PATH, safeIssuePath(relative),
				"Configured target path is unsafe: " + safe(relative),
				"Use a normalized portable path contained by the adapter's target root.", unsafe);
		}
	}

	private void requireSafeParents(Path path, String relative)
		throws WorldBuilderContractException {
		Path current = root;
		Path contained = root.relativize(path);
		for (Path segment : contained) {
			current = current.resolve(segment);
			if (Files.isSymbolicLink(current)) {
				throw problem(WorldBuilderErrorCodes.UNSAFE_PATH, relative,
					"Target evidence contains a symbolic-link path component.",
					"Replace symbolic links with contained regular files or directories.");
			}
		}
	}

	private static void rejectHardLink(Path path, String relative)
		throws IOException, WorldBuilderContractException {
		try {
			Object value = Files.getAttribute(path, "unix:nlink", LinkOption.NOFOLLOW_LINKS);
			if (value instanceof Number && ((Number)value).longValue() > 1L) {
				throw problem(WorldBuilderErrorCodes.UNSAFE_PATH, relative,
					"Target evidence is hard-linked and its containment cannot be proven.",
					"Copy the evidence into a distinct regular file and retry.");
			}
		} catch (UnsupportedOperationException unsupported) {
			// The platform has no portable link-count view; no-follow checks still apply.
		} catch (IllegalArgumentException unsupportedView) {
			// The platform has no portable link-count view; no-follow checks still apply.
		}
	}

	private static String sha256NoFollow(Path path) throws IOException {
		MessageDigest digest = WorldBuilderHashes.newDigest();
		OpenOption[] options = new OpenOption[] {
			StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS
		};
		try (InputStream input = Files.newInputStream(path, options)) {
			byte[] buffer = new byte[8192];
			int read;
			while ((read = input.read(buffer)) >= 0) {
				if (read > 0) digest.update(buffer, 0, read);
			}
		}
		return WorldBuilderHashes.hex(digest.digest());
	}

	static WorldBuilderContractException problem(
		String code, String relative, String message, String nextStep) {
		return problem(code, relative, message, nextStep, null);
	}

	static WorldBuilderContractException problem(
		String code, String relative, String message, String nextStep, Throwable cause) {
		return new WorldBuilderContractException(code, OPERATION, "", "",
			safeIssuePath(relative), "read-only target discovery",
			"Stable, contained evidence accepted by a compiled layout adapter.",
			message, false, message, nextStep, cause);
	}

	private static String safeIssuePath(String value) {
		if (value != null && !value.isEmpty()) {
			try {
				return WorldBuilderPortablePath.require(value, OPERATION);
			} catch (WorldBuilderContractException ignored) {
				// The unsafe spelling is retained in observed text, not a path field.
			}
		}
		return "target-root";
	}

	private static String safe(String value) {
		if (value == null) return "unknown error";
		StringBuilder result = new StringBuilder(Math.min(value.length(), 512));
		for (int index = 0; index < value.length() && result.length() < 512; index++) {
			char character = value.charAt(index);
			result.append(character < 0x20 || character == 0x7f ? ' ' : character);
		}
		return result.toString();
	}

	static final class FileState implements Comparable<FileState> {
		final String role;
		final String relativePath;
		final boolean present;
		final long size;
		final String sha256;

		FileState(String role, String relativePath, boolean present, long size, String sha256) {
			this.role = role;
			this.relativePath = relativePath;
			this.present = present;
			this.size = size;
			this.sha256 = sha256;
		}

		Map<String,Object> toJson() {
			Map<String,Object> value = new LinkedHashMap<String,Object>();
			value.put("role", role);
			value.put("relativePath", relativePath);
			value.put("present", Boolean.valueOf(present));
			value.put("size", Long.valueOf(size));
			value.put("sha256", sha256);
			return value;
		}

		String stableKey() {
			return relativePath + "\u0000" + role + "\u0000" + present
				+ "\u0000" + size + "\u0000" + sha256;
		}

		@Override
		public int compareTo(FileState other) {
			int result = relativePath.compareTo(other.relativePath);
			return result != 0 ? result : role.compareTo(other.role);
		}
	}
}
