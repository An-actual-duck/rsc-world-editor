package com.openrsc.worldbuilder;

import java.io.Closeable;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;

/** Holds both installed JVM role leases; never creates, truncates, or removes locks. */
final class WorldBuilderCurrentRuntimeInstanceLease implements Closeable {
	// POSIX process locks can be dropped by closing any descriptor for that inode.
	// Refuse a same-JVM overlap before opening either role file.
	private static final java.util.Set<Object> ACTIVE = new java.util.HashSet<Object>();
	private final Path root;
	private final Object rootIdentity;
	private final List<Role> roles = new ArrayList<Role>();
	private boolean closed;

	private WorldBuilderCurrentRuntimeInstanceLease(Path root, Object identity) {
		this.root = root; this.rootIdentity = identity;
	}

	static synchronized WorldBuilderCurrentRuntimeInstanceLease acquire(Path installation)
		throws IOException, WorldBuilderContractException {
		Object identity = identity(installation, true);
		if (!ACTIVE.add(identity)) throw refusal("This Editor already holds the installed role leases.");
		WorldBuilderCurrentRuntimeInstanceLease result =
			new WorldBuilderCurrentRuntimeInstanceLease(installation, identity);
		try {
			// One fixed order for upgrades, map import, recovery, and competing Editors.
			for (String name : new String[] {"server.lock", "client.lock"}) {
				Path path = installation.resolve(name);
				Object before = identity(path, false);
				FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE,
					LinkOption.NOFOLLOW_LINKS);
				Role role = new Role(path, before, channel);
				result.roles.add(role);
				try { role.lock = channel.tryLock(); }
				catch (OverlappingFileLockException busy) { role.lock = null; }
				if (role.lock == null) throw refusal("An installed server, client, or Editor holds a role lease.");
				result.verifyHeld();
			}
			return result;
		} catch (IOException | WorldBuilderContractException | RuntimeException failure) {
			try { result.close(); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
			throw failure;
		}
	}

	/** Recheck before each protected mutation; a replaced path is not lease authority. */
	void verifyHeld() throws IOException, WorldBuilderContractException {
		if (closed || !rootIdentity.equals(identity(root, true)))
			throw refusal("The installed role-lease directory changed or its lease was closed.");
		for (Role role : roles) {
			if (role.lock == null || !role.lock.isValid() || role.channel.size() != 0
				|| !role.identity.equals(identity(role.path, false)))
				throw refusal("An installed role-lock identity changed while leased.");
		}
	}

	private static Object identity(Path path, boolean directory)
		throws IOException, WorldBuilderContractException {
		if (!path.isAbsolute() || !path.normalize().equals(path)
			|| !path.toRealPath().equals(path)) throw refusal("Installed lease paths must be canonical and absolute.");
		BasicFileAttributes attributes = Files.readAttributes(path,
			BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
		if (attributes.fileKey() == null || attributes.isSymbolicLink()
			|| (directory ? !attributes.isDirectory() : !attributes.isRegularFile()))
			throw refusal("Installed lease paths require stable regular-file or directory identities.");
		if (!Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS).equals(
			PosixFilePermissions.fromString(directory ? "rwx------" : "rw-------")))
			throw refusal("Installed role leases require private POSIX paths.");
		if (!directory && (attributes.size() != 0
			|| ((Number)Files.getAttribute(path, "unix:nlink", LinkOption.NOFOLLOW_LINKS)).longValue() != 1))
			throw refusal("Installed role locks must be empty singly-linked files.");
		return attributes.fileKey();
	}

	@Override public void close() throws IOException {
		synchronized (WorldBuilderCurrentRuntimeInstanceLease.class) {
			if (closed) return;
			closed = true;
			IOException failure = null;
			for (int index = roles.size() - 1; index >= 0; index--) {
				Role role = roles.get(index);
				try { if (role.lock != null) role.lock.release(); }
				catch (IOException item) { if (failure == null) failure = item; else failure.addSuppressed(item); }
				try { role.channel.close(); }
				catch (IOException item) { if (failure == null) failure = item; else failure.addSuppressed(item); }
			}
			ACTIVE.remove(rootIdentity);
			if (failure != null) throw failure;
		}
	}

	private static WorldBuilderContractException refusal(String message) {
		return new WorldBuilderContractException(WorldBuilderErrorCodes.OFFLINE_REQUIRED,
			"current-runtime-instance-lease", "installation", false, message,
			"Stop both installed roles and request a fresh preview; do not replace their lock files.");
	}

	private static final class Role {
		final Path path;
		final Object identity;
		final FileChannel channel;
		FileLock lock;
		Role(Path path, Object identity, FileChannel channel) {
			this.path = path; this.identity = identity; this.channel = channel;
		}
	}
}
