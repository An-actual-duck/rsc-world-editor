package com.openrsc.worldbuilder;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Holds the selected target port while an import/rollback proves the server is offline. */
final class WorldBuilderTargetOfflineLease implements Closeable {
	private static final Pattern CONFIG_LINE = Pattern.compile(
		"^\\s*([A-Za-z0-9_]+)\\s*:\\s*([^#]*?)\\s*(?:#.*)?$");
	private final ServerSocket portLease;

	private WorldBuilderTargetOfflineLease(ServerSocket portLease) {
		this.portLease = portLease;
	}

	static WorldBuilderTargetOfflineLease acquire(Path targetRoot, String selectedConfig)
		throws IOException, WorldBuilderDiscoveryException {
		Path target = targetRoot.toRealPath();
		Path config = target.resolve(selectedConfig).normalize();
		if (!config.startsWith(target) || !Files.isRegularFile(config, LinkOption.NOFOLLOW_LINKS)
			|| Files.isSymbolicLink(config) || !config.toRealPath().startsWith(target)) {
			throw new WorldBuilderDiscoveryException(
				"Selected target configuration is missing or unsafe.");
		}
		int port = readServerPort(config);
		if (targetServerProcessAppearsActive(target)) {
			throw new WorldBuilderDiscoveryException(
				"The target private server appears to be running; stop it before import or rollback.");
		}

		ServerSocket socket = new ServerSocket();
		try {
			socket.setReuseAddress(false);
			socket.bind(new InetSocketAddress("0.0.0.0", port));
			return new WorldBuilderTargetOfflineLease(socket);
		} catch (IOException unavailable) {
			try {
				socket.close();
			} catch (IOException ignored) {
			}
			throw new WorldBuilderDiscoveryException(
				"The target server port is in use or cannot be reserved; offline state is uncertain.");
		}
	}

	private static int readServerPort(Path config)
		throws IOException, WorldBuilderDiscoveryException {
		if (Files.size(config) > 1_048_576L) {
			throw new WorldBuilderDiscoveryException("Selected target configuration is unexpectedly large.");
		}
		List<String> lines = Files.readAllLines(config, StandardCharsets.UTF_8);
		Integer port = null;
		for (String line : lines) {
			Matcher matcher = CONFIG_LINE.matcher(line);
			if (!matcher.matches() || !"server_port".equals(matcher.group(1))) {
				continue;
			}
			if (port != null) {
				throw new WorldBuilderDiscoveryException(
					"Selected target configuration contains duplicate server_port keys.");
			}
			try {
				port = Integer.valueOf(matcher.group(2).trim());
			} catch (NumberFormatException invalid) {
				throw new WorldBuilderDiscoveryException(
					"Selected target configuration has an invalid server_port.");
			}
		}
		if (port == null || port.intValue() < 1 || port.intValue() > 65535) {
			throw new WorldBuilderDiscoveryException(
				"Selected target configuration must contain one valid server_port.");
		}
		return port.intValue();
	}

	private static boolean targetServerProcessAppearsActive(Path target) {
		Path proc = java.nio.file.Paths.get("/proc");
		if (!Files.isDirectory(proc, LinkOption.NOFOLLOW_LINKS)) {
			return false;
		}
		try (DirectoryStream<Path> processes = Files.newDirectoryStream(proc)) {
			for (Path process : processes) {
				if (!process.getFileName().toString().matches("[0-9]+")) {
					continue;
				}
				try {
					Path commandPath = process.resolve("cmdline");
					if (!Files.isRegularFile(commandPath) || Files.size(commandPath) > 65_536L) {
						continue;
					}
					String command = new String(Files.readAllBytes(commandPath), StandardCharsets.UTF_8)
						.replace('\0', ' ');
					if (!command.contains("core.jar")
						&& !command.contains("com.openrsc.server.Server")) {
						continue;
					}
					if (command.contains(target.resolve("server/core.jar").toString())) {
						return true;
					}
					Path cwd = Files.readSymbolicLink(process.resolve("cwd"));
					if (!cwd.isAbsolute()) {
						cwd = process.resolve("cwd").getParent().resolve(cwd).normalize();
					}
					if (cwd.toAbsolutePath().normalize().equals(target.resolve("server"))) {
						return true;
					}
				} catch (IOException inaccessible) {
					// The selected-port reservation remains the cross-platform authority.
				} catch (SecurityException inaccessible) {
					// The selected-port reservation remains the cross-platform authority.
				}
			}
		} catch (IOException inaccessible) {
			return false;
		}
		return false;
	}

	@Override
	public void close() throws IOException {
		portLease.close();
	}
}
