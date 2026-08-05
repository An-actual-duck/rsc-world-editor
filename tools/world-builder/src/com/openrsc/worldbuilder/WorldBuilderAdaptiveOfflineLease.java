package com.openrsc.worldbuilder;

import java.io.Closeable;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Holds all compiled target-offline evidence for one adaptive transaction. */
final class WorldBuilderAdaptiveOfflineLease implements Closeable {
	private static final String OPERATION = "adaptive-target-offline";
	private static final int COMPILED_TARGET_PORT = 43594;
	private static final String[] PID_PATHS = {
		"server/run/server.pid",
		"server/run/world-builder.pid",
		"server/server.pid",
		"server/run/.server.lock"
	};

	private final FileChannel targetLockChannel;
	private final FileLock targetLock;
	private final ServerSocket portLease;
	final List<Evidence> evidence;

	interface IdentityObserver {
		void observe(String milestone, Path path) throws IOException;
	}

	private static final IdentityObserver NO_OP_IDENTITY_OBSERVER =
		new IdentityObserver() {
			@Override public void observe(String milestone, Path path) {
				// Production lease acquisition has no injected identity replacement.
			}
		};

	private WorldBuilderAdaptiveOfflineLease(FileChannel targetLockChannel,
		FileLock targetLock, ServerSocket portLease, List<Evidence> evidence) {
		this.targetLockChannel = targetLockChannel;
		this.targetLock = targetLock;
		this.portLease = portLease;
		this.evidence = Collections.unmodifiableList(new ArrayList<Evidence>(evidence));
	}

	static WorldBuilderAdaptiveOfflineLease acquire(Path target,
		WorldBuilderTargetCapability capability)
		throws IOException, WorldBuilderContractException {
		return acquire(target, capability, NO_OP_IDENTITY_OBSERVER);
	}

	static WorldBuilderAdaptiveOfflineLease acquire(Path target,
		WorldBuilderTargetCapability capability, IdentityObserver observer)
		throws IOException, WorldBuilderContractException {
		if (observer == null) observer = NO_OP_IDENTITY_OBSERVER;
		Path descriptor = WorldBuilderAdaptiveMutationProfile.safeExistingFile(
			target, WorldBuilderTargetCapability.RELATIVE_PATH,
			"target capability descriptor");
		Object beforeIdentity = stableDescriptorIdentity(descriptor);
		if (!capability.evidenceSha256.equals(WorldBuilderHashes.sha256(descriptor))) {
			throw problem(WorldBuilderErrorCodes.CAPABILITY_MISMATCH,
				WorldBuilderTargetCapability.RELATIVE_PATH,
				"Target capability bytes changed before its offline lock was opened.",
				"Stop target updates and request a fresh transaction preview.");
		}
		FileChannel channel = FileChannel.open(descriptor,
			StandardOpenOption.READ, StandardOpenOption.WRITE,
			LinkOption.NOFOLLOW_LINKS);
		FileLock lock = null;
		ServerSocket socket = null;
		try {
			observer.observe("after-open", descriptor);
			Object openedIdentity = stableDescriptorIdentity(descriptor);
			if (!beforeIdentity.equals(openedIdentity)) throw problem(
				WorldBuilderErrorCodes.UNSAFE_PATH,
				WorldBuilderTargetCapability.RELATIVE_PATH,
				"Target capability lock identity changed while it was opened.",
				"Restore one stable, real, single-link capability descriptor.");
			try {
				lock = channel.tryLock();
			} catch (OverlappingFileLockException busy) {
				lock = null;
			}
			if (lock == null) throw problem(WorldBuilderErrorCodes.OFFLINE_REQUIRED,
				WorldBuilderTargetCapability.RELATIVE_PATH,
				"Another import, undo, update, or configuration operation holds the target.",
				"Wait for it to finish and retry the preview.");
			if (!openedIdentity.equals(stableDescriptorIdentity(descriptor))
				|| !capability.evidenceSha256.equals(channelSha256(channel))) {
				throw problem(WorldBuilderErrorCodes.UNSAFE_PATH,
					WorldBuilderTargetCapability.RELATIVE_PATH,
					"The locked capability channel is not the stable descriptor that was reviewed.",
					"Stop concurrent target changes and request a fresh preview.");
			}

			List<Evidence> values = new ArrayList<Evidence>();
			for (String kind : capability.offlineEvidence) {
				if ("pid-file".equals(kind)) {
					requirePidFilesAbsent(target);
					values.add(new Evidence(kind,
						"all compiled target PID/lock paths are absent", true));
				} else if ("port-bind".equals(kind)) {
					if (socket != null) throw problem(
						WorldBuilderErrorCodes.CAPABILITY_MISMATCH,
						WorldBuilderTargetCapability.RELATIVE_PATH,
						"Offline capability repeats the compiled port lease.",
						"Use one canonical unique offline-evidence list.");
					socket = bindPort();
					values.add(new Evidence(kind,
						"exclusive compiled target port lease 0.0.0.0:"
							+ COMPILED_TARGET_PORT, true));
				} else if ("process-scan".equals(kind)) {
					requireNoTargetProcess(target);
					values.add(new Evidence(kind,
						"no matching target process found in the bounded local process view", true));
				} else if ("configuration-lock".equals(kind)) {
					values.add(new Evidence(kind,
						"exclusive existing capability/configuration transaction lock held", true));
				} else {
					throw problem(WorldBuilderErrorCodes.CAPABILITY_MISMATCH,
						WorldBuilderTargetCapability.RELATIVE_PATH,
						"Target requests unknown offline evidence: " + kind + ".",
						"Use only offline evidence implemented by the selected compiled profile.");
				}
			}
			if (values.isEmpty()) throw problem(WorldBuilderErrorCodes.CAPABILITY_MISMATCH,
				WorldBuilderTargetCapability.RELATIVE_PATH,
				"Target install capability declares no offline evidence.",
				"Publish a truthful capability with complete offline requirements.");
			Collections.sort(values);
			return new WorldBuilderAdaptiveOfflineLease(channel, lock, socket, values);
		} catch (IOException failure) {
			closePartial(lock, channel, socket);
			throw failure;
		} catch (WorldBuilderContractException failure) {
			closePartial(lock, channel, socket);
			throw failure;
		} catch (RuntimeException failure) {
			closePartial(lock, channel, socket);
			throw failure;
		}
	}

	private static Object stableDescriptorIdentity(Path descriptor)
		throws IOException, WorldBuilderContractException {
		BasicFileAttributes attributes = Files.readAttributes(descriptor,
			BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
		if (!attributes.isRegularFile() || attributes.isSymbolicLink()
			|| attributes.fileKey() == null) throw problem(
			WorldBuilderErrorCodes.UNSAFE_PATH,
			WorldBuilderTargetCapability.RELATIVE_PATH,
			"Capability descriptor has no stable regular-file identity.",
			"Use a local filesystem exposing stable file identities.");
		WorldBuilderAdaptiveExporter.rejectHardLink(
			descriptor, WorldBuilderTargetCapability.RELATIVE_PATH);
		return attributes.fileKey();
	}

	private static String channelSha256(FileChannel channel) throws IOException {
		long size = channel.size();
		if (size < 0L || size > WorldBuilderContractLimits.MAX_JSON_BYTES) {
			throw new IOException("Capability descriptor exceeds its bounded size");
		}
		MessageDigest digest = WorldBuilderHashes.newDigest();
		ByteBuffer buffer = ByteBuffer.allocate(8192);
		channel.position(0L);
		long readTotal = 0L;
		while (true) {
			int count = channel.read(buffer);
			if (count < 0) break;
			if (count == 0) continue;
			readTotal += count;
			buffer.flip();
			digest.update(buffer);
			buffer.clear();
		}
		if (readTotal != size || channel.size() != size) {
			throw new IOException("Capability descriptor changed during channel reread");
		}
		return WorldBuilderHashes.hex(digest.digest());
	}

	private static void requirePidFilesAbsent(Path target)
		throws IOException, WorldBuilderContractException {
		for (String relative : PID_PATHS) {
			Path path = WorldBuilderAdaptiveMutationProfile.safeDestination(target, relative);
			if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) throw problem(
				WorldBuilderErrorCodes.OFFLINE_REQUIRED, relative,
				"A compiled target PID/lock path is present, so offline state is uncertain.",
				"Stop the target server and remove only its normal stale PID through its own shutdown procedure.");
		}
	}

	private static ServerSocket bindPort()
		throws WorldBuilderContractException {
		IOException unavailable = null;
		for (int attempt = 0; attempt < 4; attempt++) {
			ServerSocket socket = null;
			try {
				socket = new ServerSocket();
				socket.setReuseAddress(false);
				socket.bind(new InetSocketAddress("0.0.0.0", COMPILED_TARGET_PORT));
				return socket;
			} catch (IOException failure) {
				unavailable = failure;
				if (socket != null) try { socket.close(); } catch (IOException ignored) { }
				if (attempt < 3) try {
					Thread.sleep(25L);
				} catch (InterruptedException interrupted) {
					Thread.currentThread().interrupt();
					break;
				}
			}
		}
		throw problem(WorldBuilderErrorCodes.OFFLINE_REQUIRED, "target-root",
			"The compiled target server port is in use or cannot be reserved.",
			"Stop the target server and any process using port "
				+ COMPILED_TARGET_PORT + ", then retry.", unavailable);
	}

	private static void requireNoTargetProcess(Path target)
		throws WorldBuilderContractException {
		if (Boolean.parseBoolean(System.getProperty(
			"worldbuilder.adaptive.testProcessViewUnavailable", "false"))) {
			throw processViewUnavailable(null);
		}
		Path proc = Paths.get("/proc");
		if (!Files.isDirectory(proc, LinkOption.NOFOLLOW_LINKS)
			|| Files.isSymbolicLink(proc) || !Files.isReadable(proc)) {
			throw processViewUnavailable(null);
		}
		String ownPid = currentPid();
		try (DirectoryStream<Path> processes = Files.newDirectoryStream(proc)) {
			for (Path process : processes) {
				if (!process.getFileName().toString().matches("[0-9]+")) continue;
				if (process.getFileName().toString().equals(ownPid)) continue;
				String processId = process.getFileName().toString();
				byte[] command = null;
				boolean commandReadable = false;
				try {
					Path commandPath = process.resolve("cmdline");
					if (!Files.isRegularFile(commandPath, LinkOption.NOFOLLOW_LINKS)
						|| Files.size(commandPath) > 65_536L) {
						throw new IOException("cmdline unavailable or oversized");
					}
					command = Files.readAllBytes(commandPath);
					commandReadable = true;
				} catch (IOException ignored) {
					// The cwd observation may still be sufficient for this live entry.
				} catch (SecurityException ignored) {
					// The cwd observation may still be sufficient for this live entry.
				}
				Path cwd = null;
				boolean cwdReadable = false;
				try {
					Path cwdLink = process.resolve("cwd");
					cwd = Files.readSymbolicLink(cwdLink);
					if (!cwd.isAbsolute()) cwd = cwdLink.getParent().resolve(cwd).normalize();
					cwd = cwd.toAbsolutePath().normalize();
					cwdReadable = true;
				} catch (IOException ignored) {
					// The command observation may still be sufficient for this live entry.
				} catch (SecurityException ignored) {
					// The command observation may still be sufficient for this live entry.
				}
				requireProcessObservationSafe(target, processId,
					processStillLive(process), commandReadable, command,
					cwdReadable, cwd);
			}
		} catch (WorldBuilderContractException active) {
			throw active;
		} catch (IOException unavailable) {
			throw processViewUnavailable(unavailable);
		} catch (SecurityException unavailable) {
			throw processViewUnavailable(unavailable);
		}
	}

	static void requireProcessObservationSafe(Path target, String processId,
		boolean stillLive, boolean commandReadable, byte[] commandBytes,
		boolean cwdReadable, Path cwd)
		throws WorldBuilderContractException {
		if (!stillLive) return;
		if (cwdReadable && cwd != null
			&& (cwd.equals(target) || cwd.startsWith(target))) {
			throw problem(WorldBuilderErrorCodes.OFFLINE_REQUIRED, "target-root",
				"A server process appears to be running from this target root.",
				"Stop the target server completely and retry.");
		}
		if (commandReadable && commandBytes != null) {
			if (commandBytes.length == 0) return; // Live kernel thread or exiting zombie.
			String command = new String(commandBytes, StandardCharsets.UTF_8)
				.replace('\0', ' ');
			if (command.contains(target.toString())) throw problem(
				WorldBuilderErrorCodes.OFFLINE_REQUIRED, "target-root",
				"A server process appears to be running from this target root.",
				"Stop the target server completely and retry.");
		}
		if (!commandReadable || !cwdReadable) throw problem(
			WorldBuilderErrorCodes.OFFLINE_REQUIRED, "process-scan",
			"Live process " + processId
				+ " could not be completely examined through both cmdline and cwd.",
			"Run with a readable local /proc view or stop the unreadable process, then retry.");
	}

	private static boolean processStillLive(Path process) {
		try {
			BasicFileAttributes attributes = Files.readAttributes(process,
				BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
			return attributes.isDirectory();
		} catch (NoSuchFileException exited) {
			return false;
		} catch (IOException unreadable) {
			return true;
		} catch (SecurityException unreadable) {
			return true;
		}
	}

	private static WorldBuilderContractException processViewUnavailable(
		Throwable cause) {
		String message = "The required local /proc process view is unavailable or unreadable; "
			+ "offline state cannot be verified on this platform.";
		String next = "Run the transaction on a Linux host with a readable /proc process "
			+ "view after stopping the target server.";
		return cause == null
			? problem(WorldBuilderErrorCodes.OFFLINE_REQUIRED, "process-scan",
				message, next)
			: problem(WorldBuilderErrorCodes.OFFLINE_REQUIRED, "process-scan",
				message, next, cause);
	}

	private static String currentPid() {
		String runtime = ManagementFactory.getRuntimeMXBean().getName();
		int separator = runtime.indexOf('@');
		String candidate = separator < 0 ? runtime : runtime.substring(0, separator);
		return candidate.matches("[0-9]+") ? candidate : "";
	}

	private static void closePartial(FileLock lock, FileChannel channel,
		ServerSocket socket) {
		if (socket != null) try { socket.close(); } catch (IOException ignored) { }
		if (lock != null) try { lock.release(); } catch (IOException ignored) { }
		try { channel.close(); } catch (IOException ignored) { }
	}

	@Override public void close() throws IOException {
		IOException failure = null;
		if (portLease != null) try { portLease.close(); } catch (IOException problem) {
			failure = problem;
		}
		try { targetLock.release(); } catch (IOException problem) {
			if (failure == null) failure = problem;
		}
		try { targetLockChannel.close(); } catch (IOException problem) {
			if (failure == null) failure = problem;
		}
		if (failure != null) throw failure;
	}

	private static WorldBuilderContractException problem(
		String code, String path, String message, String nextStep) {
		return new WorldBuilderContractException(code, OPERATION, path, false,
			message, nextStep);
	}

	private static WorldBuilderContractException problem(
		String code, String path, String message, String nextStep, Throwable cause) {
		return new WorldBuilderContractException(code, OPERATION, path, false,
			message, nextStep, cause);
	}

	static final class Evidence implements Comparable<Evidence> {
		final String kind;
		final String observed;
		final boolean verified;

		Evidence(String kind, String observed, boolean verified) {
			this.kind = kind;
			this.observed = observed;
			this.verified = verified;
		}

		@Override public int compareTo(Evidence other) {
			return kind.compareTo(other.kind);
		}
	}
}
