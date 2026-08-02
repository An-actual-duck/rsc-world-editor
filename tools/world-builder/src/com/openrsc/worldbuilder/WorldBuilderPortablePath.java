package com.openrsc.worldbuilder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** Platform-neutral relative-path validation; never accesses the filesystem. */
final class WorldBuilderPortablePath {
	private static final Set<String> WINDOWS_DEVICES = new HashSet<String>(Arrays.asList(
		"CON", "PRN", "AUX", "NUL",
		"COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
		"LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9"));

	private WorldBuilderPortablePath() {
	}

	static String require(String value, String operation)
		throws WorldBuilderContractException {
		if (value == null || value.isEmpty()) {
			throw unsafe(operation, value, "Portable path is empty.");
		}
		if (!Normalizer.normalize(value, Normalizer.Form.NFC).equals(value)) {
			throw unsafe(operation, value, "Portable path is not in canonical NFC form.");
		}
		if (utf8Length(value) > WorldBuilderContractLimits.MAX_PATH_UTF8_BYTES
			|| value.startsWith("/") || value.endsWith("/") || value.indexOf('\\') >= 0
			|| value.indexOf(':') >= 0) {
			throw unsafe(operation, value,
				"Portable path is absolute, host-specific, or exceeds its size limit.");
		}
		String[] segments = value.split("/", -1);
		for (String segment : segments) {
			requireSegment(segment, value, operation);
		}
		return value;
	}

	static Path resolveContained(Path root, String relative, String operation)
		throws WorldBuilderContractException {
		String safe = require(relative, operation);
		Path normalizedRoot = root.toAbsolutePath().normalize();
		Path resolved = normalizedRoot.resolve(safe).normalize();
		if (!resolved.startsWith(normalizedRoot)) {
			throw unsafe(operation, relative, "Portable path escapes its declared root.");
		}
		return resolved;
	}

	static String collisionKey(String value, String operation)
		throws WorldBuilderContractException {
		return require(value, operation).toLowerCase(Locale.ROOT);
	}

	private static void requireSegment(String segment, String whole, String operation)
		throws WorldBuilderContractException {
		if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)
			|| segment.endsWith(".") || segment.endsWith(" ")
			|| segment.length() > WorldBuilderContractLimits.MAX_PATH_SEGMENT_UTF8_BYTES
			|| utf8Length(segment) > WorldBuilderContractLimits.MAX_PATH_SEGMENT_UTF8_BYTES) {
			throw unsafe(operation, whole, "Portable path contains an unsafe segment.");
		}
		for (int index = 0; index < segment.length(); index++) {
			char character = segment.charAt(index);
			if (character < 0x20 || character == 0x7f || character == '<'
				|| character == '>' || character == '"' || character == '|'
				|| character == '?' || character == '*'
				|| (Character.isSurrogate(character)
					&& !validSurrogatePair(segment, index))) {
				throw unsafe(operation, whole,
					"Portable path contains a platform-invalid character.");
			}
			if (Character.isHighSurrogate(character)) index++;
		}
		String basename = segment;
		int dot = basename.indexOf('.');
		if (dot >= 0) basename = basename.substring(0, dot);
		String upper = basename.toUpperCase(Locale.ROOT);
		if (WINDOWS_DEVICES.contains(upper)
			|| upper.matches("(?:COM|LPT)[\u00b9\u00b2\u00b3]")) {
			throw unsafe(operation, whole,
				"Portable path contains a reserved Windows device name.");
		}
	}

	private static boolean validSurrogatePair(String value, int index) {
		char character = value.charAt(index);
		if (Character.isHighSurrogate(character)) {
			return index + 1 < value.length()
				&& Character.isLowSurrogate(value.charAt(index + 1));
		}
		return Character.isLowSurrogate(character) && index > 0
			&& Character.isHighSurrogate(value.charAt(index - 1));
	}

	private static int utf8Length(String value) {
		return value.getBytes(StandardCharsets.UTF_8).length;
	}

	private static WorldBuilderContractException unsafe(
		String operation, String value, String message) {
		return new WorldBuilderContractException(WorldBuilderErrorCodes.UNSAFE_PATH,
			operation, value == null ? "" : value, false, message,
			"Use a normalized forward-slash relative path without traversal or device names.");
	}
}
