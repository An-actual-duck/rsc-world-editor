package com.openrsc.worldbuilder;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.ByteBuffer;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Arrays;
import java.util.Locale;

/** No-follow, single-link project transaction lock shared by Phase 6 operations. */
final class WorldBuilderAdaptiveProjectLock implements AutoCloseable {
	private static final String NAME = "world-builder.lock";
	private static final String RELATIVE = "run/" + NAME;

	final FileChannel channel;
	final FileLock lock;

	interface IdentityObserver {
		void observe(String milestone, Path path) throws IOException;
	}

	private static final IdentityObserver NO_OP_OBSERVER = new IdentityObserver() {
		@Override public void observe(String milestone, Path path) {
			// Production lock acquisition has no injected identity replacement.
		}
	};

	private WorldBuilderAdaptiveProjectLock(FileChannel channel, FileLock lock) {
		this.channel = channel;
		this.lock = lock;
	}

	static WorldBuilderAdaptiveProjectLock acquire(Path project, String operation)
		throws IOException, WorldBuilderContractException {
		return acquire(project, operation, NO_OP_OBSERVER);
	}

	static WorldBuilderAdaptiveProjectLock acquire(Path project, String operation,
		IdentityObserver observer)
		throws IOException, WorldBuilderContractException {
		if (observer == null) observer = NO_OP_OBSERVER;
		Path normalizedProject = project.toAbsolutePath().normalize();
		Path run = WorldBuilderAdaptiveExporter.requireDirectory(
			normalizedProject, "run", "project run directory");
		rejectCaseAlias(run, operation);
		Path path = WorldBuilderPortablePath.resolveContained(
			normalizedProject, RELATIVE, operation);
		boolean existed = Files.exists(path, LinkOption.NOFOLLOW_LINKS);
		Object beforeIdentity = null;
		if (existed) {
			WorldBuilderAdaptiveExporter.requireFile(
				normalizedProject, RELATIVE, "project transaction lock");
			beforeIdentity = stableIdentity(path, operation);
		}

		FileChannel channel = null;
		try {
			channel = existed
				? FileChannel.open(path, StandardOpenOption.READ,
					StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)
				: FileChannel.open(path, StandardOpenOption.CREATE_NEW,
					StandardOpenOption.READ, StandardOpenOption.WRITE,
					LinkOption.NOFOLLOW_LINKS);
			observer.observe("after-open", path);
			Object openedIdentity = stableIdentity(path, operation);
			if (beforeIdentity != null && !beforeIdentity.equals(openedIdentity)) {
				throw problem(operation, WorldBuilderErrorCodes.UNSAFE_PATH,
					"Project transaction lock identity changed while it was opened.",
					"Restore one stable, real, single-link project lock and retry.");
			}
			rejectCaseAlias(run, operation);
			WorldBuilderAdaptiveExporter.requireFile(
				normalizedProject, RELATIVE, "project transaction lock");
			bindChannelToPath(channel, path, openedIdentity, operation);
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
			if (!openedIdentity.equals(stableIdentity(path, operation))) {
				lock.release();
				throw problem(operation, WorldBuilderErrorCodes.UNSAFE_PATH,
					"Project transaction lock identity changed during acquisition.",
					"Restore one stable, real, single-link project lock and retry.");
			}
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

	private static Object stableIdentity(Path path, String operation)
		throws IOException, WorldBuilderContractException {
		BasicFileAttributes attributes = Files.readAttributes(path,
			BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
		if (!attributes.isRegularFile() || attributes.isSymbolicLink()
			|| attributes.fileKey() == null) throw problem(operation,
			WorldBuilderErrorCodes.UNSAFE_PATH,
			"Project transaction lock has no stable regular-file identity.",
			"Use a local filesystem exposing stable file identities.");
		WorldBuilderAdaptiveExporter.rejectHardLink(path, RELATIVE);
		return attributes.fileKey();
	}

	private static void bindChannelToPath(FileChannel channel, Path path,
		Object identity, String operation)
		throws IOException, WorldBuilderContractException {
		long size = channel.size();
		if (size < 0L || size > 4096L) throw problem(operation,
			WorldBuilderErrorCodes.UNSAFE_PATH,
			"Project transaction lock content exceeds its bounded size.",
			"Restore one bounded project transaction lock file.");
		byte[] channelBytes = new byte[(int)size];
		channel.position(0L);
		ByteBuffer buffer = ByteBuffer.wrap(channelBytes);
		while (buffer.hasRemaining()) {
			int count = channel.read(buffer);
			if (count < 0) break;
			if (count == 0) continue;
		}
		if (!identity.equals(stableIdentity(path, operation))
			|| buffer.hasRemaining()
			|| !Arrays.equals(channelBytes, Files.readAllBytes(path))
			|| !identity.equals(stableIdentity(path, operation))) {
			throw problem(operation, WorldBuilderErrorCodes.UNSAFE_PATH,
				"The opened project lock channel is not the lock path now in the project.",
				"Stop concurrent path replacement and restore one stable project lock.");
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
