package com.openrsc.worldbuilder;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** Bounded read-only index for native hash-addressed ORSC archives. */
final class WorldBuilderNativeArchiveIndex {
	private static final int MAX_ARCHIVE_ENTRIES = 8192;

	final String status;
	final int entryCount;
	final Set<Integer> hashes;
	final String detail;
	private final Path path;
	private final Map<Integer,Entry> entries;

	private WorldBuilderNativeArchiveIndex(String status, int entryCount,
		Path path, Map<Integer,Entry> entries, String detail) {
		this.status = status;
		this.entryCount = entryCount;
		this.path = path;
		this.entries = Collections.unmodifiableMap(
			new TreeMap<Integer,Entry>(entries));
		this.hashes = Collections.unmodifiableSet(
			new TreeSet<Integer>(entries.keySet()));
		this.detail = detail;
	}

	static WorldBuilderNativeArchiveIndex inspect(Path path) {
		try (SeekableByteChannel input = Files.newByteChannel(
			path, StandardOpenOption.READ)) {
			long fileSize = input.size();
			if (fileSize < 8L) return unverified("malformed",
				"Native archive is too short to contain an index.");
			ByteBuffer header = ByteBuffer.allocate(8);
			readFully(input, header);
			header.flip();
			long expanded = uint24(header);
			long stored = uint24(header);
			if (stored + 6L != fileSize) return unverified("malformed",
				"Native archive outer sizes do not match its exact file length.");
			if (expanded != stored) return unverified("compressed-unverified",
				"Native archive uses an outer compressed payload not inspected by this adapter.");
			int count = header.getShort() & 0xffff;
			if (count < 1 || count > MAX_ARCHIVE_ENTRIES) return unverified(
				"malformed", "Native archive entry count is outside 1..8192.");
			long directoryBytes = (long)count * 10L;
			if (2L + directoryBytes > stored) return unverified(
				"malformed", "Native archive directory exceeds its payload.");
			ByteBuffer directory = ByteBuffer.allocate((int)directoryBytes);
			readFully(input, directory);
			directory.flip();
			Map<Integer,Entry> entries = new TreeMap<Integer,Entry>();
			long payloadBytes = 0L;
			long payloadStart = 8L + directoryBytes;
			for (int index = 0; index < count; index++) {
				int hash = directory.getInt();
				long entryExpanded = uint24(directory);
				long entryStored = uint24(directory);
				if (entries.containsKey(Integer.valueOf(hash)) || entryExpanded < 1L
					|| entryStored < 1L || payloadBytes + entryStored > stored) {
					return unverified("malformed",
						"Native archive has a duplicate hash or invalid entry size.");
				}
				entries.put(Integer.valueOf(hash), new Entry(
					entryExpanded, entryStored, payloadStart + payloadBytes));
				payloadBytes += entryStored;
			}
			if (2L + directoryBytes + payloadBytes != stored) {
				return unverified("malformed",
					"Native archive indexed payload sizes do not close exactly.");
			}
			return new WorldBuilderNativeArchiveIndex("indexed", count, path, entries,
				"Native archive index is structurally complete.");
		} catch (Exception failure) {
			return unverified("malformed",
				"Native archive could not be read as a bounded native archive index.");
		}
	}

	boolean indexed() {
		return "indexed".equals(status);
	}

	boolean contains(String filename) {
		return indexed() && entries.containsKey(Integer.valueOf(filenameHash(filename)));
	}

	boolean containsValidModel(String filename) {
		if (!indexed()) return false;
		Entry entry = entries.get(Integer.valueOf(filenameHash(filename)));
		if (entry == null || entry.expanded != entry.stored
			|| entry.stored > Integer.MAX_VALUE) return false;
		try (SeekableByteChannel input = Files.newByteChannel(
			path, StandardOpenOption.READ)) {
			input.position(entry.offset);
			ByteBuffer payload = ByteBuffer.allocate((int)entry.stored);
			readFully(input, payload);
			return validModelPayload(payload.array());
		} catch (Exception invalid) {
			return false;
		}
	}

	static int filenameHash(String name) {
		int result = 0;
		String upper = name.toUpperCase(java.util.Locale.ROOT);
		for (int index = 0; index < upper.length(); index++) {
			result = result * 61 + upper.charAt(index) - 32;
		}
		return result;
	}

	private static void readFully(SeekableByteChannel input, ByteBuffer buffer)
		throws IOException {
		while (buffer.hasRemaining()) {
			if (input.read(buffer) < 0) throw new IOException("unexpected archive EOF");
		}
	}

	private static long uint24(ByteBuffer input) {
		return ((long)input.get() & 0xffL) << 16
			| ((long)input.get() & 0xffL) << 8
			| ((long)input.get() & 0xffL);
	}

	private static boolean validModelPayload(byte[] payload) {
		if (payload.length < 4) return false;
		ByteBuffer input = ByteBuffer.wrap(payload);
		int vertices = input.getShort() & 0xffff;
		int faces = input.getShort() & 0xffff;
		if (vertices < 3 || faces < 1) return false;
		long fixed = 4L + 6L * vertices + 6L * faces;
		if (fixed > payload.length) return false;
		int faceCountsOffset = 4 + 6 * vertices;
		long indices = 0L;
		boolean visibleFace = false;
		for (int face = 0; face < faces; face++) {
			int count = payload[faceCountsOffset + face] & 0xff;
			if (count >= 3) visibleFace = true;
			indices += (long)count * (vertices < 256 ? 1L : 2L);
			if (fixed + indices > payload.length) return false;
		}
		if (!visibleFace || fixed + indices != payload.length) return false;
		int indexOffset = (int)fixed;
		for (int face = 0; face < faces; face++) {
			int count = payload[faceCountsOffset + face] & 0xff;
			for (int point = 0; point < count; point++) {
				int vertex;
				if (vertices < 256) vertex = payload[indexOffset++] & 0xff;
				else {
					vertex = (payload[indexOffset] & 0xff) << 8
						| (payload[indexOffset + 1] & 0xff);
					indexOffset += 2;
				}
				if (vertex >= vertices) return false;
			}
		}
		return true;
	}

	private static WorldBuilderNativeArchiveIndex unverified(
		String status, String detail) {
		return new WorldBuilderNativeArchiveIndex(status, 0, null,
			Collections.<Integer,Entry>emptyMap(), detail);
	}

	private static final class Entry {
		final long expanded;
		final long stored;
		final long offset;
		Entry(long expanded, long stored, long offset) {
			this.expanded = expanded;
			this.stored = stored;
			this.offset = offset;
		}
	}
}
