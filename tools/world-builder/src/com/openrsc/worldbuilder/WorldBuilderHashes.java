package com.openrsc.worldbuilder;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

final class WorldBuilderHashes {
	private WorldBuilderHashes() {
	}

	static String sha256(Path path) throws IOException {
		MessageDigest digest = newDigest();
		try (InputStream input = Files.newInputStream(path)) {
			byte[] buffer = new byte[8192];
			int read;
			while ((read = input.read(buffer)) >= 0) {
				if (read > 0) {
					digest.update(buffer, 0, read);
				}
			}
		}
		return hex(digest.digest());
	}

	static String sha256(byte[] bytes) {
		MessageDigest digest = newDigest();
		digest.update(bytes);
		return hex(digest.digest());
	}

	static MessageDigest newDigest() {
		try {
			return MessageDigest.getInstance("SHA-256");
		} catch (NoSuchAlgorithmException impossible) {
			throw new IllegalStateException("Java runtime does not provide SHA-256", impossible);
		}
	}

	static void updateText(MessageDigest digest, String value) {
		digest.update(value.getBytes(StandardCharsets.UTF_8));
		digest.update((byte) 0);
	}

	static String hex(byte[] value) {
		StringBuilder output = new StringBuilder(value.length * 2);
		for (byte part : value) {
			output.append(Character.forDigit((part >>> 4) & 0x0f, 16));
			output.append(Character.forDigit(part & 0x0f, 16));
		}
		return output.toString();
	}
}
