package com.openrsc.worldbuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Bounded file bridge from the isolated runtime to the Editor-owned snapshot engine. */
final class WorldBuilderRegionControlBridge {
	static final String REQUEST_FILE = "region-copy.request.json";
	static final String RESPONSE_FILE = "region-copy.response.json";
	private static final String REQUEST_PENDING = ".region-copy.request.pending.json";
	private static final String RESPONSE_STAGE = ".region-copy.response.tmp";
	private static final String RUNTIME_RESPONSE_STAGE =
		".region-copy.response.runtime.tmp";
	private static final long MAX_REQUEST_BYTES = 256L * 1024L;
	private static final Pattern REQUEST_ID = Pattern.compile("[0-9a-f]{32}");
	private static final Set<String> REQUEST_KEYS = new HashSet<String>(Arrays.asList(
		"schemaVersion", "manifestType", "requestId", "name", "worldSpace",
		"markers", "levels"));
	private static final Set<String> MARKER_KEYS = new HashSet<String>(Arrays.asList(
		"marker", "x", "y"));

	private final Path project;
	private final Path control;
	private final Path request;
	private final Path response;
	private final Path responseStage;

	WorldBuilderRegionControlBridge(Path project, Path control) throws IOException {
		this.project = project.toAbsolutePath().normalize();
		this.control = control.toAbsolutePath().normalize();
		if (!this.control.equals(this.project.resolve("run/world-builder").normalize())) {
			throw new IOException("Region control bridge is outside the exact project run layout");
		}
		this.request = this.control.resolve(REQUEST_FILE);
		this.response = this.control.resolve(RESPONSE_FILE);
		this.responseStage = this.control.resolve(RESPONSE_STAGE);
	}

	void reset() throws IOException {
		deleteRegularIfPresent(control.resolve(REQUEST_PENDING),
			"pending region Copy request");
		deleteRegularIfPresent(request, "region Copy request");
		deleteRegularIfPresent(response, "region Copy response");
		deleteRegularIfPresent(responseStage, "staged region Copy response");
		deleteRegularIfPresent(control.resolve(RUNTIME_RESPONSE_STAGE),
			"staged runtime region Copy response");
	}

	void poll() throws IOException {
		if (!Files.exists(request, LinkOption.NOFOLLOW_LINKS)) return;
		if (Files.exists(response, LinkOption.NOFOLLOW_LINKS)) return;
		String requestId = "00000000000000000000000000000000";
		Map<String,Object> responseRoot = new LinkedHashMap<String,Object>();
		try {
			Map<String,Object> root = readRequest();
			requestId = requireText(root, "requestId", 32, 32);
			if (!REQUEST_ID.matcher(requestId).matches()) {
				throw new IllegalArgumentException("Region Copy request ID is invalid.");
			}
			String name = requireText(root, "name", 1, 128);
			WorldBuilderRegionContracts.Selection selection = selection(root);
			WorldBuilderProcessSupervisor.relocateLegacyDatabaseLogs(project);
			new WorldBuilderAdaptiveProjectLifecycle()
				.saveAfterSupervisedRun(project);
			String resultText = new WorldBuilderRegionSnapshotService()
				.copyUnderProjectLock(project, selection, name);
			Map<String,Object> result = WorldBuilderJsonDocuments.readObject(
				resultText.getBytes(StandardCharsets.UTF_8), "region Copy result");
			responseRoot.put("status", "accepted");
			responseRoot.put("result", result);
		} catch (WorldBuilderContractException refusal) {
			responseRoot.put("status", "refused");
			responseRoot.put("errorCode", refusal.code());
			responseRoot.put("message", refusal.getMessage());
			responseRoot.put("nextStep", refusal.nextStep());
		} catch (Exception failure) {
			responseRoot.put("status", "refused");
			responseRoot.put("errorCode", WorldBuilderErrorCodes.MUTATION_FAILED);
			responseRoot.put("message", boundedMessage(failure));
			responseRoot.put("nextStep",
				"Review the selection and project state, then retry Region Copy.");
		}
		responseRoot.put("schemaVersion", Long.valueOf(1L));
		responseRoot.put("manifestType", "world-builder-region-copy-response");
		responseRoot.put("requestId", requestId);
		publishResponse(responseRoot);
		Files.deleteIfExists(request);
		WorldBuilderAdaptiveDurability.forceDirectory(control);
	}

	private Map<String,Object> readRequest()
		throws IOException, WorldBuilderDiscoveryException {
		if (!Files.isRegularFile(request, LinkOption.NOFOLLOW_LINKS)
			|| Files.isSymbolicLink(request)) {
			throw new IOException("Region Copy request is not a safe regular file.");
		}
		long size = Files.size(request);
		if (size < 2L || size > MAX_REQUEST_BYTES) {
			throw new IOException("Region Copy request size is invalid.");
		}
		Map<String,Object> root = WorldBuilderJsonDocuments.readObject(request);
		if (!root.keySet().equals(REQUEST_KEYS)
			|| !(root.get("schemaVersion") instanceof Long)
			|| ((Long)root.get("schemaVersion")).longValue() != 1L
			|| !"world-builder-region-copy-request".equals(root.get("manifestType"))) {
			throw new IllegalArgumentException("Region Copy request contract is invalid.");
		}
		return root;
	}

	private WorldBuilderRegionContracts.Selection selection(Map<String,Object> requestRoot)
		throws WorldBuilderContractException {
		String worldSpace = requireText(requestRoot, "worldSpace", 1, 128);
		List<?> sourceMarkers = requireList(requestRoot, "markers", 3,
			WorldBuilderRegionContracts.MAX_MARKERS);
		List<Object> markers = new ArrayList<Object>(sourceMarkers.size());
		for (int index = 0; index < sourceMarkers.size(); index++) {
			Map<String,Object> source = requireObject(sourceMarkers.get(index), "marker");
			if (!source.keySet().equals(MARKER_KEYS)) {
				throw new IllegalArgumentException("Region Copy marker keys are invalid.");
			}
			long number = requireInteger(source, "marker");
			long x = requireInteger(source, "x");
			long y = requireInteger(source, "y");
			if (number != index + 1L || !signed(x) || !signed(y)) {
				throw new IllegalArgumentException("Region Copy marker value is invalid.");
			}
			Map<String,Object> marker = new LinkedHashMap<String,Object>();
			marker.put("marker", Long.valueOf(number));
			marker.put("x", Long.valueOf(x));
			marker.put("y", Long.valueOf(y));
			markers.add(marker);
		}
		List<?> sourceLevels = requireList(requestRoot, "levels", 1,
			WorldBuilderRegionContracts.MAX_LEVELS);
		List<Object> levels = new ArrayList<Object>(sourceLevels.size());
		for (Object raw : sourceLevels) {
			if (!(raw instanceof Long) || !signed(((Long)raw).longValue())) {
				throw new IllegalArgumentException("Region Copy level is invalid.");
			}
			levels.add(raw);
		}
		Map<String,Object> selection = new LinkedHashMap<String,Object>();
		selection.put("schemaVersion", Long.valueOf(1L));
		selection.put("manifestType", "world-builder-region-selection");
		selection.put("worldSpace", worldSpace);
		selection.put("markers", markers);
		selection.put("levels", levels);
		selection.put("selectionFingerprintSha256", WorldBuilderRegionContracts.ZERO_HASH);
		WorldBuilderRegionContracts.bindFingerprint(
			selection, "selectionFingerprintSha256");
		return WorldBuilderRegionContracts.selection(selection);
	}

	private void publishResponse(Map<String,Object> root) throws IOException {
		if (Files.exists(response, LinkOption.NOFOLLOW_LINKS)
			|| Files.exists(responseStage, LinkOption.NOFOLLOW_LINKS)) {
			throw new IOException("Region Copy response destination is already occupied.");
		}
		byte[] bytes = WorldBuilderJsonDocuments.pretty(root)
			.getBytes(StandardCharsets.UTF_8);
		Files.write(responseStage, bytes, StandardOpenOption.CREATE_NEW,
			StandardOpenOption.WRITE);
		WorldBuilderAdaptiveDurability.forceFile(responseStage);
		try {
			Files.move(responseStage, response, StandardCopyOption.ATOMIC_MOVE);
		} catch (AtomicMoveNotSupportedException unsupported) {
			Files.move(responseStage, response);
		}
		WorldBuilderAdaptiveDurability.forceDirectory(control);
	}

	private static void deleteRegularIfPresent(Path path, String label)
		throws IOException {
		if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return;
		if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
			|| Files.isSymbolicLink(path)) {
			throw new IOException(label + " is not a safe regular file.");
		}
		Files.delete(path);
	}

	private static String requireText(Map<String,Object> root, String key,
		int minimum, int maximum) {
		Object raw = root.get(key);
		if (!(raw instanceof String)) {
			throw new IllegalArgumentException("Region Copy " + key + " is not text.");
		}
		String value = (String)raw;
		if (value.length() < minimum || value.length() > maximum) {
			throw new IllegalArgumentException("Region Copy " + key + " length is invalid.");
		}
		return value;
	}

	private static long requireInteger(Map<String,Object> root, String key) {
		Object raw = root.get(key);
		if (!(raw instanceof Long)) {
			throw new IllegalArgumentException("Region Copy " + key + " is not an integer.");
		}
		return ((Long)raw).longValue();
	}

	@SuppressWarnings("unchecked")
	private static Map<String,Object> requireObject(Object raw, String label) {
		if (!(raw instanceof Map)) {
			throw new IllegalArgumentException("Region Copy " + label + " is not an object.");
		}
		return (Map<String,Object>)raw;
	}

	private static List<?> requireList(Map<String,Object> root, String key,
		int minimum, int maximum) {
		Object raw = root.get(key);
		if (!(raw instanceof List)) {
			throw new IllegalArgumentException("Region Copy " + key + " is not an array.");
		}
		List<?> values = (List<?>)raw;
		if (values.size() < minimum || values.size() > maximum) {
			throw new IllegalArgumentException("Region Copy " + key + " size is invalid.");
		}
		return values;
	}

	private static boolean signed(long value) {
		return value >= Integer.MIN_VALUE && value <= Integer.MAX_VALUE;
	}

	private static String boundedMessage(Exception failure) {
		String message = failure.getMessage();
		if (message == null || message.trim().isEmpty()) {
			message = failure.getClass().getSimpleName();
		}
		return message.length() <= 512 ? message : message.substring(0, 512);
	}
}
