package com.openrsc.worldbuilder;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Target-scoped operation lock plus exclusive game/websocket port leases. */
final class WorldBuilderCurrentRuntimeOfflineLease implements Closeable {
	interface IdentityObserver {
		void observe(String milestone, Path anchor) throws IOException;
	}

	private static final IdentityObserver NO_OP_IDENTITY_OBSERVER =
		new IdentityObserver() {
			@Override public void observe(String milestone, Path anchor) { }
		};

	private final FileChannel channel;
	private final FileLock lock;
	private final List<ServerSocket> sockets;
	private final WorldBuilderCurrentRuntimeInstanceLease instance;

	private WorldBuilderCurrentRuntimeOfflineLease(FileChannel channel, FileLock lock,
		List<ServerSocket> sockets) {
		this.channel = channel; this.lock = lock; this.sockets = sockets;
		this.instance = null;
	}

	private WorldBuilderCurrentRuntimeOfflineLease(WorldBuilderCurrentRuntimeInstanceLease instance,
		List<ServerSocket> sockets) {
		this.channel = null; this.lock = null; this.sockets = sockets; this.instance = instance;
	}

	/** Installed generations use persistent role locks, not replaceable activation files. */
	static WorldBuilderCurrentRuntimeOfflineLease acquireInstalled(Path installation,
		Map<String,Object> typed) throws IOException, WorldBuilderContractException {
		WorldBuilderCurrentRuntimeInstanceLease instance =
			WorldBuilderCurrentRuntimeInstanceLease.acquire(installation);
		List<ServerSocket> sockets = new ArrayList<ServerSocket>();
		try {
			long game = integer(typed, "gamePort"), websocket = integer(typed, "websocketPort");
			if (game < 1 || game > 65535 || websocket < 1 || websocket > 65535 || game == websocket)
				throw refusal("Installed game and websocket ports must be distinct valid ports.");
			sockets.add(bind((int)game, "game"));
			sockets.add(bind((int)websocket, "websocket"));
			instance.verifyHeld();
			return new WorldBuilderCurrentRuntimeOfflineLease(instance, sockets);
		} catch (IOException | WorldBuilderContractException | RuntimeException failure) {
			for (ServerSocket socket : sockets) try { socket.close(); }
			catch (IOException cleanup) { failure.addSuppressed(cleanup); }
			try { instance.close(); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
			throw failure;
		}
	}

	void verifyInstalledHeld() throws IOException, WorldBuilderContractException {
		if (instance == null) throw refusal("This is not a persistent installed-instance lease.");
		instance.verifyHeld();
	}

	static void inspect(Path target, Map<String,Object> typed,
		boolean syntheticFixture)
		throws IOException, WorldBuilderContractException {
		try (WorldBuilderCurrentRuntimeOfflineLease ignored = acquire(target, typed,
			syntheticFixture)) {
			// Read-only preview proves the same locks can be held, then releases them.
		}
	}

	static WorldBuilderCurrentRuntimeOfflineLease acquire(Path target,
		Map<String,Object> typed, boolean syntheticFixture)
		throws IOException, WorldBuilderContractException {
		return acquire(target, typed, syntheticFixture, NO_OP_IDENTITY_OBSERVER);
	}

	static WorldBuilderCurrentRuntimeOfflineLease acquire(Path target,
		Map<String,Object> typed, boolean syntheticFixture,
		IdentityObserver observer)
		throws IOException, WorldBuilderContractException {
		if (observer == null) observer = NO_OP_IDENTITY_OBSERVER;
		Path root = target.toRealPath();
		Path anchor = selectAnchor(root, typed, syntheticFixture);
		Object beforeIdentity = stableIdentity(anchor);
		String beforeHash = WorldBuilderHashes.sha256(anchor);
		FileChannel channel = FileChannel.open(anchor, StandardOpenOption.READ,
			StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
		FileLock lock = null;
		List<ServerSocket> sockets = new ArrayList<ServerSocket>();
		try {
			observer.observe("after-open", anchor);
			if (!Objects.equals(beforeIdentity, stableIdentity(anchor))) throw refusal(
				"Target operation-lock anchor identity changed while it was opened.");
			try { lock = channel.tryLock(); }
			catch (OverlappingFileLockException busy) { lock = null; }
			if (lock == null) throw refusal("Another target transaction holds the reviewed configuration or ledger.");
			if (!Objects.equals(beforeIdentity, stableIdentity(anchor))
				|| !beforeHash.equals(channelSha256(channel))) throw refusal(
					"The locked operation anchor is not the exact stable file that was reviewed.");
			long game = integer(typed, "gamePort");
			long websocket = integer(typed, "websocketPort");
			if (game == websocket) throw refusal("Game and websocket ports must be distinct.");
			sockets.add(bind((int)game, "game"));
			sockets.add(bind((int)websocket, "websocket"));
			return new WorldBuilderCurrentRuntimeOfflineLease(channel, lock, sockets);
		} catch (IOException failure) {
			closePartial(lock, channel, sockets); throw failure;
		} catch (WorldBuilderContractException failure) {
			closePartial(lock, channel, sockets); throw failure;
		} catch (RuntimeException failure) {
			closePartial(lock, channel, sockets); throw failure;
		}
	}

	private static Path selectAnchor(Path target, Map<String,Object> typed,
		boolean syntheticFixture)
		throws IOException, WorldBuilderContractException {
		String configured = string(typed, "sourceRelativePath");
		Path anchor;
		if (configured.isEmpty()) {
			Path ledger = target.resolve(".world-builder/runtime-ledger-v1.json");
			if (syntheticFixture) return requireSafeAnchor(target, ledger,
				".world-builder/runtime-ledger-v1.json");
			WorldBuilderCurrentRuntimeContracts.Document installed =
				WorldBuilderCurrentRuntimeContracts.read(
					WorldBuilderCurrentRuntimeContracts.Kind.TARGET_LEDGER, ledger);
			String launcher = WorldBuilderBoundedInventory.string(
				installed.root.get("activeLauncherRelativePath"),
				"current-runtime-offline", "activeLauncherRelativePath");
			anchor = WorldBuilderPortablePath.resolveContained(target, launcher,
				"current-runtime-offline");
		} else {
			anchor = WorldBuilderPortablePath.resolveContained(target, configured,
				"current-runtime-offline");
		}
		return requireSafeAnchor(target, anchor, configured);
	}

	private static Path requireSafeAnchor(Path target, Path anchor, String relative)
		throws IOException, WorldBuilderContractException {
		if (!Files.isRegularFile(anchor, LinkOption.NOFOLLOW_LINKS)
			|| Files.isSymbolicLink(anchor) || !anchor.toRealPath().startsWith(target))
			throw refusal("Target operation-lock anchor is missing or unsafe.");
		WorldBuilderAdaptiveExporter.rejectHardLink(anchor, relative);
		return anchor;
	}

	private static Object stableIdentity(Path anchor)
		throws IOException, WorldBuilderContractException {
		BasicFileAttributes attributes = Files.readAttributes(anchor,
			BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
		if (!attributes.isRegularFile() || attributes.isSymbolicLink()
			|| attributes.fileKey() == null) throw refusal(
				"Target operation-lock anchor has no stable regular-file identity.");
		WorldBuilderAdaptiveExporter.rejectHardLink(anchor,
			anchor.getFileName().toString());
		return attributes.fileKey();
	}

	private static String channelSha256(FileChannel channel) throws IOException {
		long size = channel.size();
		if (size < 0L || size > WorldBuilderContractLimits.MAX_JSON_BYTES)
			throw new IOException("Operation-lock anchor exceeds its bounded size");
		MessageDigest digest = WorldBuilderHashes.newDigest();
		ByteBuffer buffer = ByteBuffer.allocate(8192);
		channel.position(0L);
		long readTotal = 0L;
		while (true) {
			int count = channel.read(buffer);
			if (count < 0) break;
			if (count == 0) continue;
			readTotal += count;
			buffer.flip(); digest.update(buffer); buffer.clear();
		}
		if (readTotal != size || channel.size() != size)
			throw new IOException("Operation-lock anchor changed during channel reread");
		return WorldBuilderHashes.hex(digest.digest());
	}

	private static ServerSocket bind(int port, String role)
		throws WorldBuilderContractException {
		ServerSocket socket = null;
		try {
			socket = new ServerSocket();
			socket.setReuseAddress(false);
			socket.bind(new InetSocketAddress("0.0.0.0", port));
			return socket;
		} catch (IOException unavailable) {
			if (socket != null) try { socket.close(); } catch (IOException ignored) { }
			throw new WorldBuilderContractException(WorldBuilderErrorCodes.OFFLINE_REQUIRED,
				"current-runtime-offline", role + "-port", false,
				"The selected target " + role + " port " + port
					+ " is in use or cannot be reserved.",
				"Stop the target and retry while both target ports are available.", unavailable);
		}
	}

	private static long integer(Map<String,Object> value, String key)
		throws WorldBuilderContractException {
		return WorldBuilderBoundedInventory.integer(value.get(key),
			"current-runtime-offline", key);
	}

	private static String string(Map<String,Object> value, String key)
		throws WorldBuilderContractException {
		return WorldBuilderBoundedInventory.string(value.get(key),
			"current-runtime-offline", key);
	}

	private static WorldBuilderContractException refusal(String message) {
		return new WorldBuilderContractException(WorldBuilderErrorCodes.OFFLINE_REQUIRED,
			"current-runtime-offline", "target-root", false, message,
			"Stop concurrent target activity and request a fresh preview.");
	}

	private static void closePartial(FileLock lock, FileChannel channel,
		List<ServerSocket> sockets) {
		for (ServerSocket socket : sockets) try { socket.close(); } catch (IOException ignored) { }
		if (lock != null) try { lock.release(); } catch (IOException ignored) { }
		try { channel.close(); } catch (IOException ignored) { }
	}

	@Override public void close() throws IOException {
		IOException failure = null;
		for (ServerSocket socket : sockets) try { socket.close(); }
		catch (IOException item) { if (failure == null) failure = item; }
		try { if (lock != null) lock.release(); } catch (IOException item) { if (failure == null) failure = item; }
		try { if (channel != null) channel.close(); } catch (IOException item) { if (failure == null) failure = item; }
		try { if (instance != null) instance.close(); } catch (IOException item) { if (failure == null) failure = item; }
		if (failure != null) throw failure;
	}
}
