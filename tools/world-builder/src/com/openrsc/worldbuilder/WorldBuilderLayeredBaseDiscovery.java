package com.openrsc.worldbuilder;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Resolves an active layered map from bounded, target-owned server launch
 * metadata. The marker is parsed as data and is never evaluated by a shell.
 */
final class WorldBuilderLayeredBaseDiscovery {
	private static final long MAX_MARKER_BYTES = 64L * 1024L;
	private static final long MAX_MANIFEST_BYTES = 16L * 1024L * 1024L;
	private static final int MAX_MARKERS = 256;

	Discovery discover(Path requestedTarget, String selectedConfiguration)
		throws IOException {
		Path target = requireDirectory(requestedTarget, "server source");
		Path markerDirectory = target.resolve("server/run").normalize();
		if (!markerDirectory.startsWith(target)
			|| !Files.isDirectory(markerDirectory, LinkOption.NOFOLLOW_LINKS)
			|| Files.isSymbolicLink(markerDirectory)) {
			return new Discovery(Collections.<Candidate>emptyList());
		}
		String selectedName = selectedConfiguration == null ? ""
			: Paths.get(selectedConfiguration).getFileName().toString();
		List<Candidate> all = new ArrayList<Candidate>();
		int inspected = 0;
		try (DirectoryStream<Path> markers = Files.newDirectoryStream(markerDirectory,
			"server-*.env")) {
			for (Path marker : markers) {
				if (++inspected > MAX_MARKERS) throw new IOException(
					"Too many server launch records were found for safe automatic map discovery.");
				Candidate candidate = inspect(target, marker);
				if (candidate != null) all.add(candidate);
			}
		}
		List<Candidate> preferred = new ArrayList<Candidate>();
		if (!selectedName.isEmpty()) {
			for (Candidate candidate : all) {
				if (selectedName.equals(candidate.configuration)) preferred.add(candidate);
			}
		}
		return new Discovery(deduplicate(preferred.isEmpty() ? all : preferred));
	}

	private static Candidate inspect(Path target, Path marker) throws IOException {
		String name = marker.getFileName().toString();
		if (!name.matches("server-[0-9]{1,5}\\.env")
			|| !Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)
			|| Files.isSymbolicLink(marker)) return null;
		long size = Files.size(marker);
		if (size <= 0L || size > MAX_MARKER_BYTES) return null;
		Map<String,String> values = parseMarker(marker);
		Path recordedRoot = absoluteDirectory(values.get("marker_root"));
		if (recordedRoot == null || !recordedRoot.equals(target)) return null;
		Path packageRoot = absoluteDirectory(values.get("marker_layered_package_path"));
		if (packageRoot == null) return null;
		Path manifest = packageRoot.resolve("manifest.json").normalize();
		if (!manifest.startsWith(packageRoot)
			|| !Files.isRegularFile(manifest, LinkOption.NOFOLLOW_LINKS)
			|| Files.isSymbolicLink(manifest)
			|| Files.size(manifest) <= 0L
			|| Files.size(manifest) > MAX_MANIFEST_BYTES) return null;
		String expectedHash = values.get("marker_layered_manifest_sha256");
		if (expectedHash == null || !expectedHash.matches("[0-9a-f]{64}")
			|| !expectedHash.equals(sha256(manifest))) return null;
		String configuration = values.get("marker_config");
		if (configuration == null) configuration = "";
		return new Candidate(packageRoot, marker.toRealPath(), configuration,
			expectedHash, Files.getLastModifiedTime(marker, LinkOption.NOFOLLOW_LINKS)
				.toMillis());
	}

	private static Map<String,String> parseMarker(Path marker) throws IOException {
		String contents = new String(Files.readAllBytes(marker), StandardCharsets.UTF_8);
		if (contents.indexOf('\0') >= 0) return Collections.emptyMap();
		Map<String,String> result = new LinkedHashMap<String,String>();
		for (String line : contents.split("\\r?\\n", -1)) {
			int separator = line.indexOf('=');
			if (separator <= 0) continue;
			String key = line.substring(0, separator);
			if (!key.matches("marker_[a-z0-9_]+")) continue;
			String value = decodePrintfQ(line.substring(separator + 1));
			if (value != null) result.put(key, value);
		}
		return result;
	}

	/** Decode the ordinary backslash form emitted by bash printf %q. */
	private static String decodePrintfQ(String encoded) {
		if (encoded == null || encoded.startsWith("$'")) return null;
		if (encoded.length() >= 2 && encoded.charAt(0) == '\''
			&& encoded.charAt(encoded.length() - 1) == '\'') {
			encoded = encoded.substring(1, encoded.length() - 1);
		}
		StringBuilder decoded = new StringBuilder(encoded.length());
		boolean escaped = false;
		for (int index = 0; index < encoded.length(); index++) {
			char character = encoded.charAt(index);
			if (escaped) {
				decoded.append(character);
				escaped = false;
			} else if (character == '\\') {
				escaped = true;
			} else {
				decoded.append(character);
			}
		}
		return escaped ? null : decoded.toString();
	}

	private static Path absoluteDirectory(String value) throws IOException {
		if (value == null || value.isEmpty()) return null;
		final Path path;
		try {
			path = Paths.get(value);
		} catch (RuntimeException invalid) {
			return null;
		}
		if (!path.isAbsolute()) return null;
		Path normalized = path.normalize();
		if (!Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS)
			|| Files.isSymbolicLink(normalized)) return null;
		return normalized.toRealPath();
	}

	private static Path requireDirectory(Path path, String label) throws IOException {
		if (path == null) throw new IOException(label + " was not supplied.");
		Path normalized = path.toAbsolutePath().normalize();
		if (!Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS)
			|| Files.isSymbolicLink(normalized)) {
			throw new IOException(label + " is missing or unsafe: " + normalized);
		}
		return normalized.toRealPath();
	}

	private static String sha256(Path path) throws IOException {
		final MessageDigest digest;
		try {
			digest = MessageDigest.getInstance("SHA-256");
		} catch (NoSuchAlgorithmException impossible) {
			throw new IOException("SHA-256 is unavailable.", impossible);
		}
		try (InputStream input = Files.newInputStream(path)) {
			byte[] buffer = new byte[8192];
			for (int read; (read = input.read(buffer)) >= 0;) {
				if (read > 0) digest.update(buffer, 0, read);
			}
		}
		byte[] hash = digest.digest();
		StringBuilder text = new StringBuilder(64);
		for (byte value : hash) text.append(String.format("%02x", value & 0xff));
		return text.toString();
	}

	private static List<Candidate> deduplicate(List<Candidate> candidates) {
		Map<Path,Candidate> byPackage = new LinkedHashMap<Path,Candidate>();
		for (Candidate candidate : candidates) {
			Candidate existing = byPackage.get(candidate.packageRoot);
			if (existing == null || candidate.modifiedMillis > existing.modifiedMillis) {
				byPackage.put(candidate.packageRoot, candidate);
			}
		}
		List<Candidate> result = new ArrayList<Candidate>(byPackage.values());
		Collections.sort(result, new Comparator<Candidate>() {
			@Override public int compare(Candidate left, Candidate right) {
				int byTime = Long.compare(right.modifiedMillis, left.modifiedMillis);
				return byTime != 0 ? byTime
					: left.packageRoot.toString().compareTo(right.packageRoot.toString());
			}
		});
		return result;
	}

	static final class Discovery {
		final List<Candidate> candidates;

		Discovery(List<Candidate> candidates) {
			this.candidates = Collections.unmodifiableList(
				new ArrayList<Candidate>(candidates));
		}

		Candidate automatic() {
			return candidates.size() == 1 ? candidates.get(0) : null;
		}
	}

	static final class Candidate {
		final Path packageRoot;
		final Path marker;
		final String configuration;
		final String manifestSha256;
		final long modifiedMillis;

		Candidate(Path packageRoot, Path marker, String configuration,
			String manifestSha256, long modifiedMillis) {
			this.packageRoot = packageRoot;
			this.marker = marker;
			this.configuration = configuration;
			this.manifestSha256 = manifestSha256;
			this.modifiedMillis = modifiedMillis;
		}

		@Override public String toString() {
			String label = configuration.isEmpty() ? "Detected active map" : configuration;
			return label + " — launched "
				+ WorldBuilderDesktopLauncher.displayTime(modifiedMillis)
				+ " — " + manifestSha256.substring(0, 12);
		}
	}
}
