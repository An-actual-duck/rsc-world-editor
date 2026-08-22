package com.openrsc.worldbuilder;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.FileVisitResult;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Ordered file and directory persistence primitives for adaptive transactions. */
final class WorldBuilderAdaptiveDurability {
	private WorldBuilderAdaptiveDurability() {
	}

	static void requireDirectoryForce(Path directory, String operation,
		String relativePath, boolean mutationOccurred)
		throws WorldBuilderContractException {
		try {
			forceDirectory(directory);
		} catch (IOException unsupported) {
			throw new WorldBuilderContractException(
				WorldBuilderErrorCodes.MUTATION_FAILED, operation, relativePath,
				mutationOccurred,
				"Filesystem cannot durably order adaptive transaction directory entries.",
				"Use a local filesystem and Java provider that supports directory forcing; no target mutation was authorized without it.",
				unsupported);
		}
	}

	static void requireTransactionProviders(Path project, Path target,
		String operation) throws WorldBuilderContractException {
		requireDirectoryForce(project, operation, "project-root", false);
		requireDirectoryForce(project.resolve("backups"), operation, "backups", false);
		requireDirectoryForce(project.resolve("receipts"), operation, "receipts", false);
		requireDirectoryForce(target, operation, "target-root", false);
	}

	static void forceDirectory(Path directory) throws IOException {
		if (Boolean.parseBoolean(System.getProperty(
			"worldbuilder.adaptive.testDirectoryForceUnsupported", "false"))) {
			throw new IOException("injected unsupported directory force");
		}
		if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)
			|| Files.isSymbolicLink(directory)) {
			throw new IOException("directory persistence target is missing or unsafe");
		}
		try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
			channel.force(true);
		}
	}

	static void forceFile(Path file) throws IOException {
		try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE)) {
			channel.force(true);
		}
	}

	static void forceTreeDirectories(final Path root) throws IOException {
		final List<Path> directories = new ArrayList<Path>();
		Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
			@Override public FileVisitResult preVisitDirectory(Path directory,
				BasicFileAttributes attributes) throws IOException {
				if (!attributes.isDirectory() || Files.isSymbolicLink(directory)) {
					throw new IOException("unsafe directory in persistence tree");
				}
				directories.add(directory);
				return FileVisitResult.CONTINUE;
			}
		});
		Collections.sort(directories, new Comparator<Path>() {
			@Override public int compare(Path left, Path right) {
				int depth = right.getNameCount() - left.getNameCount();
				return depth == 0 ? right.toString().compareTo(left.toString()) : depth;
			}
		});
		for (Path directory : directories) forceDirectory(directory);
		Path parent = root.getParent();
		if (parent != null) forceDirectory(parent);
	}

	static void forceTree(final Path root) throws IOException {
		final List<Path> directories = new ArrayList<Path>();
		Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
			@Override public FileVisitResult preVisitDirectory(Path directory,
				BasicFileAttributes attributes) throws IOException {
				if (!attributes.isDirectory() || Files.isSymbolicLink(directory)) {
					throw new IOException("unsafe directory in persistence tree");
				}
				directories.add(directory);
				return FileVisitResult.CONTINUE;
			}
			@Override public FileVisitResult visitFile(Path file,
				BasicFileAttributes attributes) throws IOException {
				if (!attributes.isRegularFile() || Files.isSymbolicLink(file)) {
					throw new IOException("unsafe file in persistence tree");
				}
				forceFile(file);
				return FileVisitResult.CONTINUE;
			}
		});
		Collections.sort(directories, new Comparator<Path>() {
			@Override public int compare(Path left, Path right) {
				int depth = right.getNameCount() - left.getNameCount();
				return depth == 0 ? right.toString().compareTo(left.toString()) : depth;
			}
		});
		for (Path directory : directories) forceDirectory(directory);
		Path parent = root.getParent();
		if (parent != null) forceDirectory(parent);
	}
}
