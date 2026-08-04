package com.openrsc.worldbuilder;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Locale;

/** No-follow, single-link project transaction lock shared by Phase 6 operations. */
final class WorldBuilderAdaptiveProjectLock implements AutoCloseable {
	private static final String NAME = "world-builder.lock";
	private static final String RELATIVE = "run/" + NAME;

	final FileChannel channel;
	final FileLock lock;

	private WorldBuilderAdaptiveProjectLock(FileChannel channel, FileLock lock) {
		this.channel = channel;
		this.lock = lock;
	}

	static WorldBuilderAdaptiveProjectLock acquire(Path project, String operation)
		throws IOException, WorldBuilderContractException {
		Path normalizedProject = project.toAbsolutePath().normalize();
		Path run = WorldBuilderAdaptiveExporter.requireDirectory(
			normalizedProject, "run", "project run directory");
		rejectCaseAlias(run, operation);
		Path path = WorldBuilderPortablePath.resolveContained(
			normalizedProject, RELATIVE, operation);
		boolean existed = Files.exists(path, LinkOption.NOFOLLOW_LINKS);
		if (existed) {
			WorldBuilderAdaptiveExporter.requireFile(
				normalizedProject, RELATIVE, "project transaction lock");
		}

		FileChannel channel = null;
		try {
			channel = existed
				? FileChannel.open(path, StandardOpenOption.READ,
					StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)
				: FileChannel.open(path, StandardOpenOption.CREATE_NEW,
					StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
			rejectCaseAlias(run, operation);
			WorldBuilderAdaptiveExporter.requireFile(
				normalizedProject, RELATIVE, "project transaction lock");
			FileLock lock;
			try {
				lock = channel.tryLock();
			} catch (OverlappingFileLockException busy) {
				lock = null;
			}
			if (lock == null) throw problem(operation,
				WorldBuilderErrorCodes.RECOVERY_REQUIRED,
				"The project is running or another project operation is active.",
				"Close World Builder and wait for the other project operation.");
			return new WorldBuilderAdaptiveProjectLock(channel, lock);
		} catch (IOException failure) {
			if (channel != null) channel.close();
			throw failure;
		} catch (WorldBuilderContractException failure) {
			if (channel != null) channel.close();
			throw failure;
		} catch (RuntimeException failure) {
			if (channel != null) channel.close();
			throw failure;
		}
	}

	private static void rejectCaseAlias(Path run, String operation)
		throws IOException, WorldBuilderContractException {
		String key = NAME.toLowerCase(Locale.ROOT);
		try (DirectoryStream<Path> entries = Files.newDirectoryStream(run)) {
			for (Path entry : entries) {
				String name = entry.getFileName().toString();
				if (key.equals(name.toLowerCase(Locale.ROOT)) && !NAME.equals(name)) {
					throw problem(operation, WorldBuilderErrorCodes.UNSAFE_PATH,
						"Project transaction lock has a case-colliding alias.",
						"Remove the alias without changing the exact project lock.");
				}
			}
		}
	}

	private static WorldBuilderContractException problem(String operation,
		String code, String message, String nextStep) {
		return new WorldBuilderContractException(code, operation, RELATIVE, false,
			message, nextStep);
	}

	@Override public void close() throws IOException {
		try {
			lock.release();
		} finally {
			channel.close();
		}
	}
}
