package com.openrsc.worldbuilder;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Owns the isolated local server/client lifecycle for one prepared workspace or project. */
public final class WorldBuilderProcessSupervisor {
	private static final long DEFAULT_READY_TIMEOUT_MILLIS = 60_000L;
	private static final long SHUTDOWN_TIMEOUT_MILLIS = 20_000L;
	private static final int RESTART_AFTER_REGION_PASTE = -1000;
	private static final int MAX_REGION_PASTE_RESTARTS = 1024;
	private static final Pattern SOURCE_FINGERPRINT = Pattern.compile(
		"\\\"sourceFingerprintSha256\\\"\\s*:\\s*\\\"([0-9a-f]{64})\\\"");
	private static final Pattern RUNTIME_PORT = Pattern.compile(
		"\\\"port\\\"\\s*:\\s*([0-9]+)");

	public int runPrepared(Path requestedWorkspace, int port)
		throws IOException, WorldBuilderDiscoveryException, InterruptedException {
		Path workspace = validateWorkspace(requestedWorkspace, port);
		return superviseWithCommands(
			workspace, port, null, null, DEFAULT_READY_TIMEOUT_MILLIS);
	}

	public int runAdaptiveProject(Path requestedProject)
		throws IOException, WorldBuilderContractException,
			WorldBuilderDiscoveryException, InterruptedException {
		return superviseAdaptive(requestedProject, null, null,
			DEFAULT_READY_TIMEOUT_MILLIS, true);
	}

	int superviseAdaptiveWithCommands(Path requestedProject,
		List<String> serverCommand, List<String> clientCommand, long readyTimeoutMillis)
		throws IOException, WorldBuilderContractException,
			WorldBuilderDiscoveryException, InterruptedException {
		if (serverCommand == null || clientCommand == null) {
			throw new IllegalArgumentException(
				"Adaptive server and client test commands must both be supplied.");
		}
		return superviseAdaptive(requestedProject, serverCommand, clientCommand,
			readyTimeoutMillis, false);
	}

	private int superviseAdaptive(Path requestedProject,
		List<String> suppliedServerCommand, List<String> suppliedClientCommand,
		long readyTimeoutMillis, boolean productionCommands)
		throws IOException, WorldBuilderContractException,
			WorldBuilderDiscoveryException, InterruptedException {
		WorldBuilderRegionSnapshotService.recoverProject(requestedProject);
		WorldBuilderAdaptiveProjectLifecycle.VerifiedProject verified =
			WorldBuilderAdaptiveProjectLifecycle.verifyProjectDirectory(
				requestedProject, true);
		Path project = verified.projectRoot;
		int port = WorldBuilderAdaptiveProjectLifecycle.readRuntimePort(project);
		requireAdaptiveMutableLayout(project);
		try (WorldBuilderAdaptiveProjectLock ignored =
			WorldBuilderAdaptiveProjectLock.acquire(
				project, "adaptive-project-supervision")) {
			verified = WorldBuilderAdaptiveProjectLifecycle.verifyProjectDirectory(
				project, true);
			requireAdaptiveMutableLayout(project);
			List<String> serverCommand = suppliedServerCommand;
			List<String> clientCommand = suppliedClientCommand;
			if (productionCommands) {
				AdaptiveLaunch launch = AdaptiveLaunch.create(verified, port);
				serverCommand = launch.serverCommand();
				clientCommand = launch.clientCommand();
			}
			ProcessLayout layout = ProcessLayout.adaptive(project);
			int exit;
			int restarts = 0;
			do {
				WorldBuilderRegionControlBridge regionBridge =
					new WorldBuilderRegionControlBridge(project, layout.control);
				exit = superviseLocked(layout, port,
					serverCommand, clientCommand, readyTimeoutMillis, regionBridge);
				if (exit != RESTART_AFTER_REGION_PASTE) break;
				if (++restarts > MAX_REGION_PASTE_RESTARTS) {
					throw new IOException(
						"World Builder exceeded its bounded automatic Paste restart count.");
				}
				requireAdaptiveMutableLayout(project);
				WorldBuilderAdaptiveProjectLifecycle.verifyProjectDirectory(project, true);
				System.out.println("Restarting World Builder after atomic Region Paste.");
			} while (true);
			requireAdaptiveMutableLayout(project);
			relocateLegacyDatabaseLogs(project);
			if (exit == 0) {
				requireAdaptiveMutableLayout(project);
				new WorldBuilderAdaptiveProjectLifecycle()
					.saveAfterSupervisedRun(project);
				WorldBuilderAdaptiveProjectLifecycle.verifyProjectDirectory(project, true);
			}
			return exit;
		}
	}

	static void relocateLegacyDatabaseLogs(Path project)
		throws IOException, WorldBuilderContractException {
		Path server = project.resolve("working/runtime/server");
		Path logs = server.resolve("logs");
		if (Files.exists(logs, LinkOption.NOFOLLOW_LINKS)) {
			if (!Files.isDirectory(logs, LinkOption.NOFOLLOW_LINKS)
				|| Files.isSymbolicLink(logs)) {
				throw unsafeAdaptive("working/runtime/server/logs",
					"Adaptive server log directory is missing, linked, or unsafe.");
			}
		} else {
			Files.createDirectory(logs);
		}
		for (String name : Arrays.asList("create_db.log", "create_db_error.log")) {
			Path source = server.resolve(name);
			if (!Files.exists(source, LinkOption.NOFOLLOW_LINKS)) continue;
			BasicFileAttributes before = Files.readAttributes(source,
				BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
			if (!before.isRegularFile() || before.isSymbolicLink()
				|| before.size() > WorldBuilderContractLimits.MAX_INVENTORY_FILE_BYTES
				|| hasMultipleLinks(source)) {
				throw unsafeAdaptive("working/runtime/server/" + name,
					"Legacy database setup log is linked, unsupported, or unbounded.");
			}
			Path destination = logs.resolve(name);
			if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
				BasicFileAttributes existing = Files.readAttributes(destination,
					BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
				if (!existing.isRegularFile() || existing.isSymbolicLink()
					|| hasMultipleLinks(destination)) {
					throw unsafeAdaptive("working/runtime/server/logs/" + name,
						"Legacy database setup log destination is linked or unsafe.");
				}
			}
			Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE,
				StandardCopyOption.REPLACE_EXISTING);
			BasicFileAttributes after = Files.readAttributes(destination,
				BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
			if (!after.isRegularFile() || after.isSymbolicLink()
				|| after.size() != before.size() || hasMultipleLinks(destination)
				|| before.fileKey() != null && after.fileKey() != null
					&& !before.fileKey().equals(after.fileKey())) {
				throw unsafeAdaptive("working/runtime/server/logs/" + name,
					"Relocated database setup log changed identity or became unsafe.");
			}
		}
	}

	static List<String> defaultAdaptiveServerCommand(Path requestedProject)
		throws IOException, WorldBuilderContractException {
		WorldBuilderAdaptiveProjectLifecycle.VerifiedProject verified =
			WorldBuilderAdaptiveProjectLifecycle.verifyProjectDirectory(
				requestedProject, true);
		int port = WorldBuilderAdaptiveProjectLifecycle.readRuntimePort(
			verified.projectRoot);
		requireAdaptiveMutableLayout(verified.projectRoot);
		return AdaptiveLaunch.create(verified, port).serverCommand();
	}

	static List<String> defaultAdaptiveClientCommand(Path requestedProject)
		throws IOException, WorldBuilderContractException {
		WorldBuilderAdaptiveProjectLifecycle.VerifiedProject verified =
			WorldBuilderAdaptiveProjectLifecycle.verifyProjectDirectory(
				requestedProject, true);
		int port = WorldBuilderAdaptiveProjectLifecycle.readRuntimePort(
			verified.projectRoot);
		requireAdaptiveMutableLayout(verified.projectRoot);
		return AdaptiveLaunch.create(verified, port).clientCommand();
	}

	private static void requireAdaptiveMutableLayout(final Path project)
		throws IOException, WorldBuilderContractException {
		final int[] count = new int[] {0};
		for (String relative : Arrays.asList("working/runtime", "logs", "run")) {
			final Path root = project.resolve(relative).normalize();
			if (!root.startsWith(project)
				|| !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)
				|| Files.isSymbolicLink(root)
				|| !root.toRealPath().startsWith(project)) {
				throw unsafeAdaptive(relative,
					"Adaptive mutable directory is missing, linked, or escaped its project.");
			}
			final String[] unsafe = new String[] {null};
			Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
				@Override public FileVisitResult preVisitDirectory(Path directory,
					BasicFileAttributes attributes) throws IOException {
					if (!attributes.isDirectory() || Files.isSymbolicLink(directory)) {
						unsafe[0] = project.relativize(directory).toString();
						return FileVisitResult.TERMINATE;
					}
					return boundedEntry(count, directory, unsafe, project);
				}

				@Override public FileVisitResult visitFile(Path file,
					BasicFileAttributes attributes) throws IOException {
					if (!attributes.isRegularFile() || Files.isSymbolicLink(file)
						|| hasMultipleLinks(file)) {
						unsafe[0] = project.relativize(file).toString();
						return FileVisitResult.TERMINATE;
					}
					return boundedEntry(count, file, unsafe, project);
				}
			});
			if (unsafe[0] != null) {
				throw unsafeAdaptive(
					unsafe[0].replace('\\', '/'),
					"Adaptive mutable state contains a link, unsupported entry, "
						+ "or exceeds its bounded inventory.");
			}
		}
	}

	private static FileVisitResult boundedEntry(int[] count, Path path,
		String[] unsafe, Path project) {
		if (++count[0] > WorldBuilderContractLimits.MAX_INVENTORY_ENTRIES) {
			unsafe[0] = project.relativize(path).toString();
			return FileVisitResult.TERMINATE;
		}
		return FileVisitResult.CONTINUE;
	}

	private static boolean hasMultipleLinks(Path path) throws IOException {
		try {
			Object links = Files.getAttribute(path, "unix:nlink", LinkOption.NOFOLLOW_LINKS);
			return links instanceof Number && ((Number)links).longValue() > 1L;
		} catch (UnsupportedOperationException ignored) {
			return false;
		} catch (IllegalArgumentException ignored) {
			return false;
		}
	}

	private static WorldBuilderContractException unsafeAdaptive(
		String relative, String message) {
		return new WorldBuilderContractException(
			WorldBuilderErrorCodes.UNSAFE_PATH, "adaptive-project-supervision",
			relative, false, message,
			"Restore the complete project-local runtime, logs, and run directories "
				+ "without links or external filesystem identities.");
	}

	int superviseWithCommands(Path workspace, int port, List<String> serverCommand,
		List<String> clientCommand, long readyTimeoutMillis)
		throws IOException, WorldBuilderDiscoveryException, InterruptedException {
		Path lockPath = workspace.getParent().resolve("." + workspace.getFileName() + ".world-builder.lock");
		try (FileChannel lockChannel = FileChannel.open(lockPath,
			StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
			FileLock lock;
			try {
				lock = lockChannel.tryLock();
			} catch (OverlappingFileLockException busy) {
				lock = null;
			}
			if (lock == null) {
				throw new WorldBuilderDiscoveryException(
					"This World Builder workspace is already running: " + workspace);
			}
			try {
				boolean preparedCommands = serverCommand == null && clientCommand == null;
				if ((serverCommand == null) != (clientCommand == null)) {
					throw new IllegalArgumentException(
						"World Builder server and client commands must both be supplied.");
				}
				if (preparedCommands) {
					commitPendingLayeredTerrain(workspace);
					validateWorkspace(workspace, port);
					serverCommand = defaultServerCommand(workspace);
					clientCommand = defaultClientCommand(workspace, port);
				}
				int exit = superviseLocked(ProcessLayout.legacy(workspace), port,
					serverCommand, clientCommand, readyTimeoutMillis, null);
				if (preparedCommands && exit == 0) {
					commitPendingLayeredTerrain(workspace);
					validateWorkspace(workspace, port);
				} else if (preparedCommands) {
					System.err.println(
						"World Builder did not close cleanly; any saved layered "
							+ "terrain journal was retained without committing.");
				}
				return exit;
			} finally {
				lock.release();
			}
		}
	}

	private static void commitPendingLayeredTerrain(Path workspace)
		throws IOException, WorldBuilderDiscoveryException {
		WorldBuilderLayeredTerrainDraftJournal.CommitResult committed =
			new WorldBuilderLayeredTerrainDraftJournal()
				.commitIfPresentLocked(workspace);
		if (committed != null) {
			System.out.println(
				"Committed layered Builder draft: "
					+ committed.levelCount + " levels, "
					+ committed.tileCount + " tiles, "
					+ committed.sectorCount + " sectors, "
					+ committed.sceneryCount + " scenery edits, "
					+ committed.npcCount + " NPC edits, "
					+ committed.groundItemCount
					+ " ground-item edits, manifest "
					+ committed.manifestSha256.substring(0, 12));
		}
	}

	private int superviseLocked(ProcessLayout layout, int port, List<String> serverCommand,
		List<String> clientCommand, long readyTimeoutMillis,
		WorldBuilderRegionControlBridge regionBridge)
		throws IOException, WorldBuilderDiscoveryException, InterruptedException {
		Path run = layout.run;
		Path logs = layout.logs;
		Path control = layout.control;
		Path ready = control.resolve("ready");
		Path shutdown = control.resolve("shutdown.request");
		Path credential = layout.credential;
		Files.createDirectories(run);
		Files.createDirectories(logs);
		Files.createDirectories(control);
		Files.deleteIfExists(ready);
		Files.deleteIfExists(shutdown);
		if (regionBridge != null) regionBridge.reset();

		Path serverLog = logs.resolve("server.log");
		Path clientLog = logs.resolve("client.log");
		rotateLog(serverLog);
		rotateLog(clientLog);

		final Process[] active = new Process[2];
		Thread shutdownHook = new Thread(new Runnable() {
			@Override
			public void run() {
				try {
					requestShutdown(shutdown);
				} catch (Exception ignored) {
				}
				for (Process process : active) {
					if (process != null && process.isAlive()) {
						process.destroy();
					}
				}
			}
		}, "World Builder Launcher Shutdown");
		Runtime.getRuntime().addShutdownHook(shutdownHook);

		int clientExit = -1;
		int serverExit = -1;
		boolean serverFailedFirst = false;
		try {
			active[0] = startProcess(serverCommand, layout.server, serverLog);
			writePid(run.resolve("server.pid"), active[0]);
			waitForReady(active[0], ready, credential, port, readyTimeoutMillis);

			active[1] = startProcess(clientCommand, layout.client, clientLog);
			writePid(run.resolve("client.pid"), active[1]);
			while (active[1].isAlive() && active[0].isAlive()) {
				if (regionBridge != null) regionBridge.poll();
				Thread.sleep(200L);
			}
			serverFailedFirst = !active[0].isAlive() && active[1].isAlive();
			if (serverFailedFirst) {
				serverExit = active[0].exitValue();
				active[1].destroy();
				if (!active[1].waitFor(5L, TimeUnit.SECONDS)) {
					destroyForcibly(active[1]);
				}
			}
			clientExit = active[1].waitFor();
			requestShutdown(shutdown);
			if (active[0].isAlive() && !active[0].waitFor(SHUTDOWN_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
				active[0].destroy();
				if (!active[0].waitFor(5L, TimeUnit.SECONDS)) {
					destroyForcibly(active[0]);
				}
			}
			serverExit = active[0].waitFor();
			if (!serverFailedFirst && serverExit == 0 && clientExit == 0
				&& regionBridge != null && regionBridge.restartPending()) {
				return RESTART_AFTER_REGION_PASTE;
			}
			return serverFailedFirst || serverExit != 0 ? 5 : clientExit;
		} finally {
			if (active[1] != null && active[1].isAlive()) {
				active[1].destroy();
			}
			if (active[0] != null && active[0].isAlive()) {
				try {
					requestShutdown(shutdown);
					if (!active[0].waitFor(5L, TimeUnit.SECONDS)) {
						active[0].destroy();
					}
				} catch (Exception ignored) {
					active[0].destroy();
				}
			}
			Files.deleteIfExists(run.resolve("server.pid"));
			Files.deleteIfExists(run.resolve("client.pid"));
			Files.deleteIfExists(ready);
			writeLastRun(run.resolve("last-run.json"), serverExit, clientExit, serverFailedFirst);
			try {
				Runtime.getRuntime().removeShutdownHook(shutdownHook);
			} catch (IllegalStateException ignored) {
			}
		}
	}

	private static Path validateWorkspace(Path requestedWorkspace, int port)
		throws IOException, WorldBuilderDiscoveryException {
		if (port < 1 || port >= 65535) {
			throw new WorldBuilderDiscoveryException("Builder port must be between 1 and 65534.");
		}
		Path workspace = requestedWorkspace.toAbsolutePath().normalize();
		if (!Files.isDirectory(workspace, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(workspace)) {
			throw new WorldBuilderDiscoveryException("Prepared Builder workspace is missing: " + workspace);
		}
		workspace = workspace.toRealPath();
		for (String relative : Arrays.asList(
			"working/server/core.jar",
			"working/server/plugins.jar",
			"working/server/world-builder.conf",
			"working/server/inc/sqlite/world_builder.db",
			"working/Client_Base/Open_RSC_Client.jar",
			"project-source.json",
			"source-snapshot.sha256",
			"runtime.json")) {
			Path file = workspace.resolve(relative).normalize();
			if (!file.startsWith(workspace)
				|| !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
				|| Files.isSymbolicLink(file)) {
				throw new WorldBuilderDiscoveryException("Prepared Builder runtime is incomplete: " + relative);
			}
		}
		WorldBuilderSourceSnapshot.verify(workspace);
		WorldBuilderLayeredReview.readIfPresent(workspace);
		int preparedPort = readPreparedPort(workspace);
		if (port != preparedPort) {
			throw new WorldBuilderDiscoveryException(
				"Requested Builder port " + port + " does not match prepared project port "
					+ preparedPort + ".");
		}
		return workspace;
	}

	static List<String> defaultServerCommand(Path workspace) {
		String java = javaExecutable();
		String credential = workspace.resolve(WorldBuilderRuntimePreparer.BUILDER_CREDENTIAL).toString();
		String control = workspace.resolve("working/server/run/world-builder").toString();
		String classpath = String.join(System.getProperty("path.separator"),
			"lib/*", "core.jar", "plugins.jar");
		return Arrays.asList(
			java,
			"-Xms256m",
			"-Xmx1536m",
			"-Dopenrsc.worldBuilderCredentialFile=" + credential,
			"-Dopenrsc.worldBuilderControlDirectory=" + control,
			"-Dopenrsc.worldBuilderWorkspaceRoot=" + workspace,
			"-cp",
			classpath,
			"com.openrsc.server.Server",
			"world-builder.conf");
	}

	static List<String> defaultClientCommand(Path workspace, int port) {
		String credential = workspace.resolve(WorldBuilderRuntimePreparer.BUILDER_CREDENTIAL).toString();
		String projectName = workspace.getFileName().toString();
		String sourceRevision = readSourceRevision(workspace);
		WorldBuilderLayeredReview layered = readLayeredReview(workspace);
		List<String> command = new ArrayList<String>(Arrays.asList(
			javaExecutable(),
			"-Xms512m",
			"-Xmx2g",
			// The Builder's primary renderer owns its own LWJGL context. Avoid also
			// enabling Java2D's OpenGL pipeline. Start in the same borderless/vsynced
			// presentation used by the normal client while retaining the in-game
			// window-mode toggle for users who prefer a bounded window.
			"-Dsun.java2d.opengl=false",
			"-Dspoiledmilk.openglWindowMode=borderless-fullscreen",
			"-Dspoiledmilk.openglVsync=true",
			"-Dopenrsc.worldBuilderMode=true",
			"-Dopenrsc.worldBuilderHost=127.0.0.1",
			"-Dopenrsc.worldBuilderPort=" + port,
			"-Dopenrsc.worldBuilderCredentialFile=" + credential,
			"-Dopenrsc.worldBuilderProjectName=" + projectName,
			"-Dopenrsc.worldBuilderSourceRevision=" + sourceRevision));
		if (layered != null) {
			command.add("-Dopenrsc.worldBuilderLayeredReview=true");
			command.add("-Dopenrsc.worldBuilderLayeredTerrainDraft="
				+ layered.hasBuilderCreatedLevels());
			command.add("-Dopenrsc.worldBuilderLayeredPackageId=" + layered.packageId);
			command.add("-Dopenrsc.worldBuilderLayeredPackageVersion="
				+ layered.packageVersion);
			command.add("-Dopenrsc.worldBuilderLayeredManifestSha256="
				+ layered.manifestSha256);
			command.add("-Dopenrsc.worldBuilderLayeredWorldSpace="
				+ layered.worldSpace);
			command.add("-Dopenrsc.worldBuilderLayeredLevels="
				+ layered.levelsProperty());
		}
		command.addAll(Arrays.asList(
			"-jar",
			"Open_RSC_Client.jar"));
		return command;
	}

	private static WorldBuilderLayeredReview readLayeredReview(Path workspace) {
		try {
			return WorldBuilderLayeredReview.readIfPresent(workspace);
		} catch (IOException failure) {
			throw new IllegalStateException(
				"Prepared World Builder layered review metadata is invalid", failure);
		} catch (WorldBuilderDiscoveryException failure) {
			throw new IllegalStateException(
				"Prepared World Builder layered review metadata is invalid", failure);
		}
	}

	private static String readSourceRevision(Path workspace) {
		try {
			Path metadata = workspace.resolve("runtime.json");
			if (Files.size(metadata) > 16_384L) {
				throw new IOException("runtime metadata is unexpectedly large");
			}
			Matcher matcher = SOURCE_FINGERPRINT.matcher(
				new String(Files.readAllBytes(metadata), StandardCharsets.UTF_8));
			if (!matcher.find()) {
				throw new IOException("source revision is missing");
			}
			return matcher.group(1);
		} catch (IOException failure) {
			throw new IllegalStateException("Prepared World Builder source revision is invalid", failure);
		}
	}

	static int readPreparedPort(Path requestedWorkspace)
		throws IOException, WorldBuilderDiscoveryException {
		Path workspace = requestedWorkspace.toAbsolutePath().normalize();
		Path metadata = workspace.resolve("runtime.json");
		if (!Files.isRegularFile(metadata, LinkOption.NOFOLLOW_LINKS)
			|| Files.isSymbolicLink(metadata) || Files.size(metadata) > 16_384L) {
			throw new WorldBuilderDiscoveryException(
				"Prepared World Builder runtime metadata is missing or unsafe.");
		}
		Matcher matcher = RUNTIME_PORT.matcher(
			new String(Files.readAllBytes(metadata), StandardCharsets.UTF_8));
		if (!matcher.find()) {
			throw new WorldBuilderDiscoveryException(
				"Prepared World Builder runtime port is missing.");
		}
		int port;
		try {
			port = Integer.parseInt(matcher.group(1));
		} catch (NumberFormatException invalid) {
			port = 0;
		}
		if (port < 1 || port >= 65535) {
			throw new WorldBuilderDiscoveryException(
				"Prepared World Builder runtime port is invalid.");
		}
		return port;
	}

	private static String javaExecutable() {
		String executable = System.getProperty("os.name", "").toLowerCase().contains("win")
			? "java.exe" : "java";
		Path bundled = Paths.get(System.getProperty("java.home"), "bin", executable);
		return Files.isRegularFile(bundled) ? bundled.toString() : executable;
	}

	private static Process startProcess(List<String> command, Path directory, Path log) throws IOException {
		ProcessBuilder builder = new ProcessBuilder(new ArrayList<String>(command));
		builder.directory(directory.toFile());
		builder.redirectErrorStream(true);
		builder.redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()));
		return builder.start();
	}

	private static void waitForReady(Process server, Path ready, Path credential, int port,
		long timeoutMillis) throws IOException, WorldBuilderDiscoveryException, InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
		while (System.nanoTime() < deadline) {
			if (!server.isAlive()) {
				throw new WorldBuilderDiscoveryException(
					"World Builder server exited before it became ready (exit " + server.exitValue() + ").");
			}
			if (Files.isRegularFile(ready, LinkOption.NOFOLLOW_LINKS)
				&& Files.isRegularFile(credential, LinkOption.NOFOLLOW_LINKS)
				&& loopbackPortAcceptsConnections(port)) {
				return;
			}
			Thread.sleep(100L);
		}
		throw new WorldBuilderDiscoveryException(
			"World Builder server did not become ready within " + timeoutMillis + "ms.");
	}

	private static boolean loopbackPortAcceptsConnections(int port) {
		try (Socket socket = new Socket()) {
			socket.connect(new InetSocketAddress("127.0.0.1", port), 250);
			return true;
		} catch (IOException ignored) {
			return false;
		}
	}

	private static void requestShutdown(Path shutdown) throws IOException {
		Files.createDirectories(shutdown.getParent());
		Path staged = shutdown.resolveSibling(shutdown.getFileName() + ".tmp");
		Files.write(staged, "shutdown\n".getBytes(StandardCharsets.US_ASCII));
		try {
			Files.move(staged, shutdown, StandardCopyOption.ATOMIC_MOVE,
				StandardCopyOption.REPLACE_EXISTING);
		} catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
			Files.move(staged, shutdown, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	private static void rotateLog(Path log) throws IOException {
		Files.createDirectories(log.getParent());
		if (!Files.exists(log, LinkOption.NOFOLLOW_LINKS)) {
			return;
		}
		Path previous = log.resolveSibling(log.getFileName() + ".previous");
		Files.deleteIfExists(previous);
		Files.move(log, previous, StandardCopyOption.REPLACE_EXISTING);
	}

	private static void writePid(Path destination, Process process) throws IOException {
		Files.write(destination, (processId(process) + "\n").getBytes(StandardCharsets.US_ASCII));
	}

	private static long processId(Process process) {
		try {
			Method pid = Process.class.getMethod("pid");
			return ((Number)pid.invoke(process)).longValue();
		} catch (Exception ignored) {
			String runtimeName = ManagementFactory.getRuntimeMXBean().getName();
			int at = runtimeName.indexOf('@');
			if (at > 0) {
				try {
					return Long.parseLong(runtimeName.substring(0, at));
				} catch (NumberFormatException ignoredAgain) {
				}
			}
			return -1L;
		}
	}

	private static void destroyForcibly(Process process) {
		try {
			Method method = Process.class.getMethod("destroyForcibly");
			method.invoke(process);
		} catch (Exception ignored) {
			process.destroy();
		}
	}

	private static void writeLastRun(Path destination, int serverExit, int clientExit,
		boolean serverFailedFirst) throws IOException {
		String json = "{\n"
			+ "  \"schemaVersion\": 1,\n"
			+ "  \"serverExit\": " + serverExit + ",\n"
			+ "  \"clientExit\": " + clientExit + ",\n"
			+ "  \"serverFailedFirst\": " + serverFailedFirst + "\n"
			+ "}\n";
		Files.write(destination, json.getBytes(StandardCharsets.UTF_8));
	}

	private static final class ProcessLayout {
		final Path server;
		final Path client;
		final Path control;
		final Path credential;
		final Path logs;
		final Path run;

		ProcessLayout(Path server, Path client, Path control,
			Path credential, Path logs, Path run) {
			this.server = server;
			this.client = client;
			this.control = control;
			this.credential = credential;
			this.logs = logs;
			this.run = run;
		}

		static ProcessLayout legacy(Path workspace) {
			return new ProcessLayout(workspace.resolve("working/server"),
				workspace.resolve("working/Client_Base"),
				workspace.resolve("working/server/run/world-builder"),
				workspace.resolve(WorldBuilderRuntimePreparer.BUILDER_CREDENTIAL),
				workspace.resolve("logs"), workspace.resolve("run"));
		}

		static ProcessLayout adaptive(Path project) {
			Path runtime = project.resolve("working/runtime");
			return new ProcessLayout(runtime.resolve("server"), runtime.resolve("client"),
				project.resolve("run/world-builder"),
				runtime.resolve("server/inc/sqlite/world-builder.credential"),
				project.resolve("logs"), project.resolve("run"));
		}
	}

	private static final class AdaptiveLaunch {
		final Path project;
		final Path server;
		final Path client;
		final Path credential;
		final Path control;
		final Path packageRoot;
		final Path binding;
		final String projectId;
		final String displayName;
		final String sourceFingerprint;
		final String sourceCapability;
		final String origin;
		final String definitionId;
		final String definitionSha256;
		final Path serverDefinitionEvidence;
		final Path clientDefinitionEvidence;
		final String assetSha256;
		final Path serverAssetEvidence;
		final Path clientAssetEvidence;
		final Path contentBundle;
		final String contentCapabilityId;
		final String contentBundleSha256;
		final String contentDefinitionSha256;
		final String contentAssetSha256;
		final String contentItemVisualSha256;
		final String manifestSha256;
		final String workingInventorySha256;
		final String baselineInventorySha256;
		final int initialLevel;
		final int initialX;
		final int initialY;
		final int port;

		private AdaptiveLaunch(
			WorldBuilderAdaptiveProjectLifecycle.VerifiedProject verified,
			WorldBuilderAdaptiveRuntimePreparer.RuntimeEvidence evidence,
			String displayName, String sourceFingerprint, String sourceCapability,
			String manifestSha256, String workingInventorySha256,
			String baselineInventorySha256,
			WorldBuilderProjectContentBundle.Bundle content, int port) {
			this.project = verified.projectRoot;
			this.server = project.resolve("working/runtime/server");
			this.client = project.resolve("working/runtime/client");
			this.credential = server.resolve("inc/sqlite/world-builder.credential");
			this.control = project.resolve("run/world-builder");
			this.packageRoot = project.resolve(
				WorldBuilderAdaptiveProjectLifecycle.WORKING_PACKAGE_DIRECTORY);
			this.binding = control.resolve("runtime-binding.properties");
			this.projectId = verified.projectId;
			this.displayName = displayName.length() <= 64 ? displayName : verified.projectId;
			this.sourceFingerprint = sourceFingerprint;
			this.sourceCapability = sourceCapability;
			this.origin = verified.origin;
			this.definitionId = verified.definitions.catalogId;
			this.definitionSha256 = evidence.definitionSha256;
			this.serverDefinitionEvidence = evidence.serverDefinitionEvidence;
			this.clientDefinitionEvidence = evidence.clientDefinitionEvidence;
			this.assetSha256 = evidence.assetSha256;
			this.serverAssetEvidence = evidence.serverAssetEvidence;
			this.clientAssetEvidence = evidence.clientAssetEvidence;
			this.contentBundle = content == null ? null : content.root;
			this.contentCapabilityId = content == null ? "" : content.capabilityId;
			this.contentBundleSha256 = content == null ? ""
				: content.bundleFingerprintSha256;
			this.contentDefinitionSha256 = content == null ? ""
				: content.definitionFingerprintSha256;
			this.contentAssetSha256 = content == null ? ""
				: content.assetFingerprintSha256;
			this.contentItemVisualSha256 = content == null ? ""
				: content.itemVisualFingerprintSha256;
			this.manifestSha256 = manifestSha256;
			this.workingInventorySha256 = workingInventorySha256;
			this.baselineInventorySha256 = baselineInventorySha256;
			this.initialLevel = verified.working.initialLevel;
			this.initialX = verified.working.initialX;
			this.initialY = verified.working.initialY;
			this.port = port;
		}

		static AdaptiveLaunch create(
			WorldBuilderAdaptiveProjectLifecycle.VerifiedProject verified, int port)
			throws IOException, WorldBuilderContractException {
			if (!"global".equals(verified.working.worldSpace)) {
				throw incompatible(
					WorldBuilderAdaptiveProjectLifecycle.WORKING_PACKAGE_DIRECTORY,
					"Adaptive runtime requires the package world space global.",
					"Convert or adopt a generic signed-layered package using global world space.");
			}
			if (verified.working.initialX < 0 || verified.working.initialX > 32767
				|| verified.working.initialY < 0 || verified.working.initialY > 32767) {
				throw incompatible("working/runtime/runtime.json",
					"Adaptive initial coordinates are outside the client carrier range.",
					"Select package terrain addressable at coordinates 0..32767.");
			}
			Map<String,Object> fingerprints = map(
				verified.manifest.get("fingerprints"), "fingerprints");
			Map<String,Object> target = map(
				verified.manifest.get("target"), "target");
			String runtimeSha256 = text(fingerprints, "runtimeSha256");
			WorldBuilderAdaptiveRuntimePreparer.RuntimeEvidence evidence =
				WorldBuilderAdaptiveRuntimePreparer.verify(verified.projectRoot,
					runtimeSha256, verified.snapshot, verified.origin, port);
			String manifest = packageManifestSha256(verified.working);
			String workingInventory = inventorySha256(verified.working,
				WorldBuilderAdaptiveProjectLifecycle.WORKING_PACKAGE_DIRECTORY);
			String baselineInventory = inventorySha256(verified.baseline,
				WorldBuilderAdaptiveProjectLifecycle.BASELINE_DIRECTORY);
			WorldBuilderProjectContentBundle.Bundle content = null;
			Path sourceContent = verified.projectRoot.resolve(
				WorldBuilderProjectContentBundle.SOURCE_DIRECTORY);
			Path workingContent = verified.projectRoot.resolve(
				WorldBuilderProjectContentBundle.WORKING_DIRECTORY);
			if (Files.exists(sourceContent, LinkOption.NOFOLLOW_LINKS)
				|| Files.exists(workingContent, LinkOption.NOFOLLOW_LINKS)) {
				WorldBuilderProjectContentBundle.Bundle immutable =
					WorldBuilderProjectContentBundle.read(sourceContent);
				content = WorldBuilderProjectContentBundle.read(workingContent);
				if (!immutable.bundleFingerprintSha256.equals(
					content.bundleFingerprintSha256)) {
					throw incompatible(WorldBuilderProjectContentBundle.WORKING_DIRECTORY,
						"Working custom content differs from immutable source evidence.",
						"Restore the exact project-local content bundle.");
				}
			}
			return new AdaptiveLaunch(verified, evidence,
				text(verified.manifest, "displayName"),
				text(fingerprints, "sourceSha256"), text(target, "capabilityId"),
				manifest, workingInventory, baselineInventory, content, port);
		}

		List<String> serverCommand() {
			String classpath = String.join(System.getProperty("path.separator"),
				"lib/*", "core.jar", "plugins.jar");
			return Arrays.asList(
				javaExecutable(),
				"-Xms256m", "-Xmx1536m",
				property("openrsc.worldBuilderCredentialFile", credential),
				property("openrsc.worldBuilderControlDirectory", control),
				property("openrsc.worldBuilderWorkspaceRoot", project),
				property("openrsc.worldBuilderPort", Integer.toString(port)),
				property("openrsc.worldBuilderProjectId", projectId),
				property("openrsc.worldBuilderSourceCapabilityId", sourceCapability),
				property("openrsc.worldBuilderAdaptiveMode", "true"),
				property("openrsc.worldBuilderProjectOrigin", origin),
				property("openrsc.worldBuilderDefinitionId", definitionId),
				property("openrsc.worldBuilderDefinitionSha256", definitionSha256),
				property("openrsc.worldBuilderDefinitionEvidencePath",
					serverDefinitionEvidence),
				property("openrsc.worldBuilderAssetId",
					WorldBuilderAdaptiveRuntimePreparer.ASSET_ID),
				property("openrsc.worldBuilderAssetSha256", assetSha256),
				property("openrsc.worldBuilderAssetEvidencePath", serverAssetEvidence),
				property("openrsc.worldBuilderContentBundle",
					contentBundle == null ? "" : contentBundle.toString()),
				property("openrsc.worldBuilderContentCapabilityId", contentCapabilityId),
				property("openrsc.worldBuilderContentBundleSha256", contentBundleSha256),
				property("openrsc.worldBuilderContentDefinitionSha256",
					contentDefinitionSha256),
				property("openrsc.worldBuilderContentAssetSha256", contentAssetSha256),
				property("openrsc.worldBuilderContentItemVisualSha256",
					contentItemVisualSha256),
				property("openrsc.worldBuilderSourceBaselineInventorySha256",
					baselineInventorySha256),
				property("openrsc.worldBuilderInitialWorldSpace", "global"),
				property("openrsc.worldBuilderInitialLevel", Integer.toString(initialLevel)),
				property("openrsc.worldBuilderInitialX", Integer.toString(initialX)),
				property("openrsc.worldBuilderInitialY", Integer.toString(initialY)),
				property("openrsc.layeredNativeTerrainPackagePath", packageRoot),
				property("openrsc.layeredNativeTerrainManifestSha256", manifestSha256),
				property("openrsc.layeredNativeTerrainInventorySha256",
					workingInventorySha256),
				property("openrsc.layeredNativeWorldRuntimeProfile",
					"adaptive-world-builder"),
				"-cp", classpath, "com.openrsc.server.Server", "world-builder.conf");
		}

		List<String> clientCommand() {
			return Arrays.asList(
				javaExecutable(),
				"-Xms512m", "-Xmx2g",
				"-Dsun.java2d.opengl=false",
				"-Dspoiledmilk.openglWindowMode=borderless-fullscreen",
				"-Dspoiledmilk.openglVsync=true",
				property("spoiledmilk.clientLog",
					project.resolve("logs/client-runtime.log")),
				property("openrsc.worldBuilderMode", "true"),
				property("openrsc.worldBuilderAdaptiveMode", "true"),
				property("openrsc.worldBuilderHost", "127.0.0.1"),
				property("openrsc.worldBuilderPort", Integer.toString(port)),
				property("openrsc.worldBuilderCredentialFile", credential),
				property("openrsc.worldBuilderWorkspaceRoot", project),
				property("openrsc.worldBuilderProjectName", displayName),
				property("openrsc.worldBuilderProjectId", projectId),
				property("openrsc.worldBuilderSourceCapabilityId", sourceCapability),
				property("openrsc.worldBuilderSourceRevision", sourceFingerprint),
				property("openrsc.worldBuilderRuntimeBindingFile", binding),
				property("openrsc.worldBuilderDefinitionId", definitionId),
				property("openrsc.worldBuilderDefinitionSha256", definitionSha256),
				property("openrsc.worldBuilderDefinitionEvidenceFile",
					clientDefinitionEvidence),
				property("openrsc.worldBuilderAssetId",
					WorldBuilderAdaptiveRuntimePreparer.ASSET_ID),
				property("openrsc.worldBuilderAssetSha256", assetSha256),
				property("openrsc.worldBuilderAssetEvidenceFile", clientAssetEvidence),
				property("openrsc.worldBuilderContentBundle",
					contentBundle == null ? "" : contentBundle.toString()),
				property("openrsc.worldBuilderContentCapabilityId", contentCapabilityId),
				property("openrsc.worldBuilderContentBundleSha256", contentBundleSha256),
				property("openrsc.worldBuilderContentDefinitionSha256",
					contentDefinitionSha256),
				property("openrsc.worldBuilderContentAssetSha256", contentAssetSha256),
				property("openrsc.worldBuilderContentItemVisualSha256",
					contentItemVisualSha256),
				"-jar", "Open_RSC_Client.jar");
		}

		private static String property(String name, Path value) {
			return property(name, value.toString());
		}

		private static String property(String name, String value) {
			return "-D" + name + "=" + value;
		}

		private static String packageManifestSha256(
			WorldBuilderGenericLayeredPackage worldPackage)
			throws WorldBuilderContractException {
			for (WorldBuilderReadOnlyTarget.FileState file : worldPackage.files) {
				if (file.relativePath.endsWith("/manifest.json")) return file.sha256;
			}
			throw incompatible("working/layered-world/package/manifest.json",
				"Adaptive working package manifest evidence is missing.",
				"Restore the complete verified working package.");
		}

		private static String inventorySha256(
			WorldBuilderGenericLayeredPackage worldPackage, String relativeRoot) {
			StringBuilder canonical = new StringBuilder();
			for (WorldBuilderReadOnlyTarget.FileState file : worldPackage.files) {
				String relative = file.relativePath.substring(relativeRoot.length() + 1);
				canonical.append(relative).append('\0').append(file.size).append('\0')
					.append(file.sha256).append('\n');
			}
			return WorldBuilderHashes.sha256(
				canonical.toString().getBytes(StandardCharsets.UTF_8));
		}

		@SuppressWarnings("unchecked")
		private static Map<String,Object> map(Object value, String field)
			throws WorldBuilderContractException {
			if (value instanceof Map) return (Map<String,Object>)value;
			throw incompatible("project.json", "Project field is invalid: " + field + ".",
				"Restore the canonical project manifest.");
		}

		private static String text(Map<String,Object> value, String field)
			throws WorldBuilderContractException {
			Object raw = value.get(field);
			if (raw instanceof String) return (String)raw;
			throw incompatible("project.json", "Project field is invalid: " + field + ".",
				"Restore the canonical project manifest.");
		}

		private static WorldBuilderContractException incompatible(
			String path, String message, String nextStep) {
			return new WorldBuilderContractException(
				WorldBuilderErrorCodes.LOADER_INCOMPATIBLE,
				"adaptive-project-supervision", path, false, message, nextStep);
		}
	}
}
