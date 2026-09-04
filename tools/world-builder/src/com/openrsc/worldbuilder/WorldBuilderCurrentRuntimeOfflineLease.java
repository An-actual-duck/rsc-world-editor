package com.openrsc.worldbuilder;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Target-scoped operation lock plus exclusive game/websocket port leases. */
final class WorldBuilderCurrentRuntimeOfflineLease implements Closeable {
	private final FileChannel channel;
	private final FileLock lock;
	private final List<ServerSocket> sockets;

	private WorldBuilderCurrentRuntimeOfflineLease(FileChannel channel, FileLock lock,
		List<ServerSocket> sockets) {
		this.channel = channel; this.lock = lock; this.sockets = sockets;
	}

	static void inspect(Path target, Map<String,Object> typed)
		throws IOException, WorldBuilderContractException {
		try (WorldBuilderCurrentRuntimeOfflineLease ignored = acquire(target, typed)) {
			// Read-only preview proves the same locks can be held, then releases them.
		}
	}

	static WorldBuilderCurrentRuntimeOfflineLease acquire(Path target,
		Map<String,Object> typed) throws IOException, WorldBuilderContractException {
		Path root = target.toRealPath();
		Path anchor = selectAnchor(root, typed);
		FileChannel channel = FileChannel.open(anchor, StandardOpenOption.READ,
			StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
		FileLock lock = null;
		List<ServerSocket> sockets = new ArrayList<ServerSocket>();
		try {
			try { lock = channel.tryLock(); }
			catch (OverlappingFileLockException busy) { lock = null; }
			if (lock == null) throw refusal("Another target transaction holds the reviewed configuration or ledger.");
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

	private static Path selectAnchor(Path target, Map<String,Object> typed)
		throws IOException, WorldBuilderContractException {
		String configured = string(typed, "sourceRelativePath");
		Path anchor = configured.isEmpty()
			? target.resolve(".world-builder/runtime-ledger-v1.json")
			: WorldBuilderPortablePath.resolveContained(target, configured,
				"current-runtime-offline");
		if (!Files.isRegularFile(anchor, LinkOption.NOFOLLOW_LINKS)
			|| Files.isSymbolicLink(anchor) || !anchor.toRealPath().startsWith(target))
			throw refusal("Target operation-lock anchor is missing or unsafe.");
		WorldBuilderAdaptiveExporter.rejectHardLink(anchor, configured);
		return anchor;
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
		try { lock.release(); } catch (IOException item) { if (failure == null) failure = item; }
		try { channel.close(); } catch (IOException item) { if (failure == null) failure = item; }
		if (failure != null) throw failure;
	}
}
