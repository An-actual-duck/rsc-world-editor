package com.openrsc.worldbuilder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/** Project-local copy/cut/paste foundation for portable region snapshots. */
final class WorldBuilderRegionSnapshotService {
	private static final String OPERATION = "region-snapshot";
	private static final String PACKAGE = "working/layered-world/package";
	private static final String LIBRARY = "snapshot-library/v1";
	private static final String BUNDLE_EXTENSION = ".wbr";
	private static final long MAX_BUNDLE_BYTES = 32L * 1024L * 1024L;
	private static final long MAX_ENTRY_BYTES = 16L * 1024L * 1024L;
	private static final long ZIP_TIME = 315532800000L;
	private final Observer observer;

	interface Observer {
		void observe(String milestone, Path project) throws Exception;
	}

	private static final Observer NO_OP_OBSERVER = new Observer() {
		@Override public void observe(String milestone, Path project) {
			// Production region operations do not inject failures.
		}
	};

	WorldBuilderRegionSnapshotService() {
		this(NO_OP_OBSERVER);
	}

	WorldBuilderRegionSnapshotService(Observer observer) {
		this.observer = observer == null ? NO_OP_OBSERVER : observer;
	}

	String copy(Path project, Path selectionPath, String name)
		throws IOException, WorldBuilderContractException {
		Path root = project.toAbsolutePath().normalize();
		try (WorldBuilderAdaptiveProjectLock ignored =
			WorldBuilderAdaptiveProjectLock.acquire(root, "region-copy")) {
			WorldBuilderAdaptiveProjectLifecycle.VerifiedProject verified =
				WorldBuilderAdaptiveProjectLifecycle.verifyProjectDirectory(root, false);
			WorldBuilderRegionContracts.Selection selection = readSelection(selectionPath);
			Capture capture = capture(verified, selection, name);
			LibraryRecord library = publishToLibrary(root, capture.snapshot);
			Map<String,Object> result = baseResult("copy", capture.snapshot.id, library);
			result.put("workingSha256", verified.working.fingerprintSha256);
			result.put("tileCount", Long.valueOf(capture.snapshot.tileCount));
			result.put("placementCount", Long.valueOf(capture.snapshot.placementCount));
			result.put("footprintBoundaryReports",
				capture.snapshot.root.get("footprintBoundaryReports"));
			return WorldBuilderJsonDocuments.pretty(result);
		}
	}

	String cutPreview(Path project, Path selectionPath, String name)
		throws IOException, WorldBuilderContractException {
		Path root = project.toAbsolutePath().normalize();
		try (WorldBuilderAdaptiveProjectLock ignored =
			WorldBuilderAdaptiveProjectLock.acquire(root, "region-cut-preview")) {
			WorldBuilderAdaptiveProjectLifecycle.VerifiedProject verified =
				WorldBuilderAdaptiveProjectLifecycle.verifyProjectDirectory(root, true);
			Capture capture = capture(verified, readSelection(selectionPath), name);
			LibraryRecord library = publishToLibrary(root, capture.snapshot);
			PreparedMutation prepared = prepareCut(verified, capture.snapshot);
			try {
				Map<String,Object> result = baseResult("cut-preview",
					capture.snapshot.id, library);
				result.put("operationPlan", prepared.plan);
				return WorldBuilderJsonDocuments.pretty(result);
			} finally {
				prepared.discard();
			}
		}
	}

	String applyCut(Path project, String snapshotId, String expectedPlan,
		String confirmation) throws IOException, WorldBuilderContractException {
		return apply(project, snapshotId, null, "cut", expectedPlan, confirmation);
	}

	String importBundle(Path project, Path requestedBundle)
		throws IOException, WorldBuilderContractException {
		Path root = project.toAbsolutePath().normalize();
		try (WorldBuilderAdaptiveProjectLock ignored =
			WorldBuilderAdaptiveProjectLock.acquire(root, "region-import")) {
			WorldBuilderAdaptiveProjectLifecycle.VerifiedProject verified =
				WorldBuilderAdaptiveProjectLifecycle.verifyProjectDirectory(root, true);
			Bundle bundle = readBundle(requestedBundle);
			LibraryRecord library = publishBundle(root, bundle);
			Map<String,Object> compatibility = compatibility(verified, bundle.snapshot);
			Map<String,Object> result = baseResult("import", bundle.snapshot.id, library);
			result.put("compatibilityReport", compatibility);
			result.put("worldModified", Boolean.FALSE);
			return WorldBuilderJsonDocuments.pretty(result);
		}
	}

	String exportBundle(Path project, String snapshotId, Path requestedOutput)
		throws IOException, WorldBuilderContractException {
		Path root = project.toAbsolutePath().normalize();
		try (WorldBuilderAdaptiveProjectLock ignored =
			WorldBuilderAdaptiveProjectLock.acquire(root, "region-export")) {
			WorldBuilderAdaptiveProjectLifecycle.verifyProjectDirectory(root, true);
			Bundle bundle = loadLibrary(root, snapshotId);
			Path output = safeNewOutput(requestedOutput, root);
			Path stage = output.resolveSibling("." + output.getFileName().toString()
				+ ".staging-" + UUID.randomUUID().toString());
			try {
				Files.write(stage, bundle.bytes, StandardOpenOption.CREATE_NEW,
					StandardOpenOption.WRITE);
				WorldBuilderAdaptiveAtomicFiles.moveNew(stage, output,
					"region-export", output.getFileName().toString());
			} finally {
				Files.deleteIfExists(stage);
			}
			Map<String,Object> result = new LinkedHashMap<String,Object>();
			result.put("operation", "export");
			result.put("snapshotId", snapshotId);
			result.put("outputPath", output.toString());
			result.put("bundleSha256", WorldBuilderHashes.sha256(bundle.bytes));
			return WorldBuilderJsonDocuments.pretty(result);
		}
	}

	String pastePreview(Path project, String snapshotId, int level, int x, int y)
		throws IOException, WorldBuilderContractException {
		Path root = project.toAbsolutePath().normalize();
		try (WorldBuilderAdaptiveProjectLock ignored =
			WorldBuilderAdaptiveProjectLock.acquire(root, "region-paste-preview")) {
			WorldBuilderAdaptiveProjectLifecycle.VerifiedProject verified =
				WorldBuilderAdaptiveProjectLifecycle.verifyProjectDirectory(root, true);
			Bundle bundle = loadLibrary(root, snapshotId);
			Map<String,Object> report = compatibility(verified, bundle.snapshot);
			PreparedMutation prepared = preparePaste(verified, bundle.snapshot,
				level, x, y, report);
			try {
				Map<String,Object> result = new LinkedHashMap<String,Object>();
				result.put("operation", "paste-preview");
				result.put("snapshotId", snapshotId);
				result.put("compatibilityReport", report);
				result.put("operationPlan", prepared.plan);
				result.put("worldModified", Boolean.FALSE);
				return WorldBuilderJsonDocuments.pretty(result);
			} finally {
				prepared.discard();
			}
		}
	}

	String applyPaste(Path project, String snapshotId, int level, int x, int y,
		String expectedPlan, String confirmation)
		throws IOException, WorldBuilderContractException {
		return apply(project, snapshotId, new Destination(level, x, y), "paste",
			expectedPlan, confirmation);
	}

	private String apply(Path requestedProject, String snapshotId,
		Destination destination, String operation, String expectedPlan,
		String confirmation) throws IOException, WorldBuilderContractException {
		Path project = requestedProject.toAbsolutePath().normalize();
		try (WorldBuilderAdaptiveProjectLock ignored =
			WorldBuilderAdaptiveProjectLock.acquire(project, "region-" + operation)) {
			WorldBuilderAdaptiveProjectLifecycle.VerifiedProject verified =
				WorldBuilderAdaptiveProjectLifecycle.verifyProjectDirectory(project, true);
			Bundle bundle = loadLibrary(project, snapshotId);
			PreparedMutation prepared;
			if ("cut".equals(operation)) {
				prepared = prepareCut(verified, bundle.snapshot);
			} else {
				Map<String,Object> report = compatibility(verified, bundle.snapshot);
				prepared = preparePaste(verified, bundle.snapshot, destination.level,
					destination.x, destination.y, report);
			}
			String planHash = WorldBuilderRegionContracts.string(prepared.plan,
				"planFingerprintSha256", "region-" + operation);
			boolean blocked = WorldBuilderRegionContracts.bool(prepared.plan,
				"blocked", "region-" + operation);
			boolean overwrite = WorldBuilderRegionContracts.bool(prepared.plan,
				"overwriteRequired", "region-" + operation);
			String required = (overwrite ? "OVERWRITE " : operation.toUpperCase(
				java.util.Locale.ROOT) + " ") + planHash;
			if (blocked) {
				prepared.discard();
				throw problem(WorldBuilderErrorCodes.CAPABILITY_MISMATCH, operation,
					"Region operation plan is blocked by compatibility or coverage issues.",
					"Review the complete compatibility and collision preview; do not force it.");
			}
			if (!planHash.equals(expectedPlan) || !required.equals(confirmation)) {
				prepared.discard();
				throw problem(WorldBuilderErrorCodes.TARGET_DRIFT, operation,
					"Region operation confirmation does not bind the current exact plan.",
					"Generate a fresh preview and enter its exact confirmation text.");
			}
			boolean published = false;
			boolean saved = false;
			try {
				observer.observe("before-package-publication", project);
				publishWorkingPackage(project, prepared.stage);
				published = true;
				observer.observe("package-published", project);
				WorldBuilderAdaptiveProjectLifecycle.ProjectResult result =
					new WorldBuilderAdaptiveProjectLifecycle().saveAfterSupervisedRun(project);
				saved = true;
				completeWorkingPublication(project);
				Map<String,Object> output = new LinkedHashMap<String,Object>();
				output.put("operation", operation);
				output.put("snapshotId", snapshotId);
				output.put("planFingerprintSha256", planHash);
				output.put("workingSha256", result.workingFingerprintSha256);
				output.put("worldModified", Boolean.TRUE);
				return WorldBuilderJsonDocuments.pretty(output);
			} catch (Exception failure) {
				if (published && !saved) rollbackWorkingPublication(project, failure);
				if (failure instanceof IOException) throw (IOException)failure;
				if (failure instanceof WorldBuilderContractException) {
					throw (WorldBuilderContractException)failure;
				}
				throw new IOException("Region publication failed: "
					+ failure.getMessage(), failure);
			} finally {
				if (!published) prepared.discard();
			}
		}
	}

	private WorldBuilderRegionContracts.Selection readSelection(Path requested)
		throws IOException, WorldBuilderContractException {
		Path path = safeExternalFile(requested, "selection JSON", 0L,
			WorldBuilderContractLimits.MAX_JSON_BYTES);
		Map<String,Object> root;
		try {
			root = WorldBuilderJsonDocuments.readObject(path);
		} catch (WorldBuilderDiscoveryException malformed) {
			throw problem(WorldBuilderErrorCodes.MALFORMED_JSON, "selection",
				"Region selection JSON is malformed or unsafe.",
				"Use one strict region-selection-v1 document.", malformed);
		}
		return WorldBuilderRegionContracts.selection(root);
	}

	private Capture capture(
		WorldBuilderAdaptiveProjectLifecycle.VerifiedProject verified,
		WorldBuilderRegionContracts.Selection selection, String name)
		throws IOException, WorldBuilderContractException {
		if (name == null || name.isEmpty() || name.length() > 128) {
			throw problem(WorldBuilderErrorCodes.CONTRACT_VALUE_INVALID, "name",
				"Snapshot name must contain 1..128 characters.",
				"Choose one short creator-facing snapshot name.");
		}
		PackageState state = PackageState.read(verified.projectRoot, PACKAGE);
		if (!selection.worldSpace.equals(state.worldSpace)) {
			throw problem(WorldBuilderErrorCodes.MAP_MISMATCH, "selection",
				"Selection world space does not match the working package.",
				"Select content in the project's exact static world space.");
		}
		int anchorX = selection.markers.get(0).x;
		int anchorY = selection.markers.get(0).y;
		int anchorLevel = selection.levels.get(0).intValue();
		Map<String,Object> root = new LinkedHashMap<String,Object>();
		root.put("schemaVersion", Long.valueOf(1L));
		root.put("manifestType", "world-builder-region-snapshot");
		root.put("snapshotId", WorldBuilderRegionContracts.ZERO_HASH);
		root.put("name", name);
		root.put("worldSpace", selection.worldSpace);
		root.put("anchor", anchor(anchorLevel, anchorX, anchorY));
		List<Object> polygon = new ArrayList<Object>();
		for (int index = 0; index < selection.markers.size(); index++) {
			WorldBuilderRegionContracts.Point point = selection.markers.get(index);
			Map<String,Object> marker = new LinkedHashMap<String,Object>();
			marker.put("marker", Long.valueOf(index + 1L));
			marker.put("xOffset", Long.valueOf((long)point.x - anchorX));
			marker.put("yOffset", Long.valueOf((long)point.y - anchorY));
			polygon.add(marker);
		}
		root.put("polygon", polygon);
		List<Object> levelRecords = new ArrayList<Object>();
		int selectedTiles = 0;
		for (Integer level : selection.levels) {
			if (!state.levels.contains(level)) throw problem(
				WorldBuilderErrorCodes.MAP_MISMATCH, "selection",
				"Selection references a level absent from the working package: " + level + ".",
				"Choose only existing supported levels.");
			Map<String,Object> levelRecord = new LinkedHashMap<String,Object>();
			levelRecord.put("levelOffset", Long.valueOf((long)level - anchorLevel));
			List<Object> tiles = new ArrayList<Object>();
			for (int x = selection.geometry.minimumX; x <= selection.geometry.maximumX; x++) {
				for (int y = selection.geometry.minimumY; y <= selection.geometry.maximumY; y++) {
					if (!selection.geometry.owns(x, y)) continue;
					byte[] tile = state.tile(level.intValue(), x, y);
					if (tile == null) throw problem(WorldBuilderErrorCodes.MAP_MISMATCH,
						"selection", "Selected tile has no declared terrain coverage at "
							+ level + ":" + x + "," + y + ".",
						"Reduce the polygon to complete working terrain coverage.");
					tiles.add(tileRecord(tile, x - anchorX, y - anchorY));
					if (++selectedTiles > WorldBuilderRegionContracts.MAX_TILES) {
						throw problem(WorldBuilderErrorCodes.INVENTORY_LIMIT_EXCEEDED,
							"selection", "Region snapshot exceeds 65,536 selected tiles.",
							"Use a smaller polygon or fewer levels.");
					}
				}
			}
			if (tiles.isEmpty()) throw problem(WorldBuilderErrorCodes.CONTRACT_VALUE_INVALID,
				"selection", "Selection polygon owns no tile centers.",
				"Move the markers so the polygon encloses at least one tile.");
			levelRecord.put("tiles", tiles);
			levelRecords.add(levelRecord);
		}
		root.put("levels", levelRecords);

		Map<String,Object> placements = emptyPlacements();
		List<Object> reports = new ArrayList<Object>();
		Set<Dependency> dependencies = new TreeSet<Dependency>();
		dependencies.add(Dependency.catalog(verified.definitions.catalogId,
			fingerprint(verified.manifest, "definitionsSha256")));
		for (Integer level : selection.levels) {
			Map<String,Object> payload = state.placements.get(level);
			capturePlacements(payload, level.intValue(), anchorLevel, anchorX, anchorY,
				selection.geometry, placements, reports, dependencies,
				verified.definitions.catalogId);
		}
		for (String family : placementFamilies()) sortCanonical(list(placements, family));
		Collections.sort(reports, new Comparator<Object>() {
			@Override public int compare(Object left, Object right) {
				Map<String,Object> a = map(left), b = map(right);
				return (text(a, "family") + "\u0000" + text(a, "placementId"))
					.compareTo(text(b, "family") + "\u0000" + text(b, "placementId"));
			}
		});
		root.put("placements", placements);
		root.put("footprintBoundaryReports", reports);
		Map<String,Object> catalog = new LinkedHashMap<String,Object>();
		catalog.put("catalogId", verified.definitions.catalogId);
		catalog.put("sha256", fingerprint(verified.manifest, "definitionsSha256"));
		root.put("catalog", catalog);
		Map<String,Object> source = new LinkedHashMap<String,Object>();
		source.put("projectId", verified.projectId);
		source.put("packageSchemaId", "layered-world-package-v1");
		source.put("coordinateModel", state.coordinateModel);
		source.put("workingSha256", verified.working.fingerprintSha256);
		source.put("runtimeSha256", fingerprint(verified.manifest, "runtimeSha256"));
		root.put("sourceEvidence", source);
		List<Object> dependencyRecords = new ArrayList<Object>();
		for (Dependency dependency : dependencies) dependencyRecords.add(dependency.toJson());
		root.put("dependencies", dependencyRecords);
		root.put("snapshotFingerprintSha256", WorldBuilderRegionContracts.ZERO_HASH);
		WorldBuilderRegionContracts.bindDualFingerprint(root, "snapshotId",
			"snapshotFingerprintSha256");
		return new Capture(WorldBuilderRegionContracts.snapshot(root));
	}

	private static Map<String,Object> tileRecord(byte[] tile, int x, int y) {
		ByteBuffer input = ByteBuffer.wrap(tile);
		Map<String,Object> result = new LinkedHashMap<String,Object>();
		result.put("xOffset", Long.valueOf(x));
		result.put("yOffset", Long.valueOf(y));
		result.put("elevation", Long.valueOf(input.get() & 0xff));
		result.put("groundTexture", Long.valueOf(input.get() & 0xff));
		result.put("groundOverlay", Long.valueOf(input.get() & 0xff));
		result.put("roofTexture", Long.valueOf(input.get() & 0xff));
		result.put("verticalWall", Long.valueOf(input.get() & 0xff));
		result.put("horizontalWall", Long.valueOf(input.get() & 0xff));
		result.put("diagonalWall", Long.valueOf(input.getInt()));
		result.put("canonicalVoid", Boolean.valueOf(
			Arrays.equals(tile, WorldBuilderCanonicalVoidTerrain.tile())));
		return result;
	}

	private static void capturePlacements(Map<String,Object> payload, int level,
		int anchorLevel, int anchorX, int anchorY,
		WorldBuilderRegionContracts.Geometry geometry, Map<String,Object> output,
		List<Object> reports, Set<Dependency> dependencies, String catalogId)
		throws WorldBuilderContractException {
		for (String family : placementFamilies()) {
			for (Object raw : list(payload, family)) {
				Map<String,Object> record = map(raw);
				Point owner = owner(family, record);
				if (!geometry.owns(owner.x, owner.y)) continue;
				Map<String,Object> relative = relativePlacement(family, record,
					level - anchorLevel, anchorX, anchorY);
				list(output, family).add(relative);
				String placementId = text(record, "placementId");
				boolean crossing = crossesBoundary(family, record, geometry);
				Map<String,Object> report = new LinkedHashMap<String,Object>();
				report.put("family", singularFamily(family));
				report.put("placementId", placementId);
				report.put("ownership", "boundaries".equals(family)
					? "boundary-origin" : "anchor-point");
				report.put("crossesBoundary", Boolean.valueOf(crossing));
				report.put("detail", footprintDetail(family, crossing));
				reports.add(report);
				int id = definitionId(family, record);
				dependencies.add(Dependency.definition(catalogId,
					singularFamily(family), id));
			}
		}
	}

	private PreparedMutation prepareCut(
		WorldBuilderAdaptiveProjectLifecycle.VerifiedProject verified,
		WorldBuilderRegionContracts.Snapshot snapshot)
		throws IOException, WorldBuilderContractException {
		Map<String,Object> source = map(snapshot.root.get("sourceEvidence"));
		if (!verified.projectId.equals(text(source, "projectId"))
			|| !verified.working.fingerprintSha256.equals(text(source, "workingSha256"))) {
			return blockedPlan(verified, snapshot, "cut", snapshot.anchorLevel,
				anchorX(snapshot), anchorY(snapshot), "source-drift",
				"Cut snapshot no longer binds the exact source working package.");
		}
		Path stage = stagePackage(verified.projectRoot);
		try {
			PackageState state = PackageState.read(verified.projectRoot,
				relativeStage(verified.projectRoot, stage));
			applySnapshotTiles(state, snapshot, snapshot.anchorLevel,
				anchorX(snapshot), anchorY(snapshot), true);
			removeOwnedPlacements(state, snapshot, snapshot.anchorLevel,
				anchorX(snapshot), anchorY(snapshot));
			state.writeAndValidate(verified);
			Map<String,Object> plan = plan(verified, snapshot, "cut",
				snapshot.anchorLevel, anchorX(snapshot), anchorY(snapshot), stage,
				new ArrayList<Object>(), false, false);
			return new PreparedMutation(stage, plan);
		} catch (Exception failure) {
			deleteTree(stage);
			if (failure instanceof IOException) throw (IOException)failure;
			throw (WorldBuilderContractException)failure;
		}
	}

	private PreparedMutation preparePaste(
		WorldBuilderAdaptiveProjectLifecycle.VerifiedProject verified,
		WorldBuilderRegionContracts.Snapshot snapshot, int level, int x, int y,
		Map<String,Object> compatibility)
		throws IOException, WorldBuilderContractException {
		List<Object> collisions = new ArrayList<Object>();
		boolean compatible = WorldBuilderRegionContracts.bool(compatibility,
			"compatible", "region-paste");
		if (!compatible) {
			return blockedPlan(verified, snapshot, "paste", level, x, y,
				"incompatible-dependency", "Snapshot dependencies are incompatible.");
		}
		PackageState live = PackageState.read(verified.projectRoot, PACKAGE);
		WorldBuilderRegionContracts.Geometry destination = translatedGeometry(snapshot, x, y);
		Set<Integer> destinationLevels = new HashSet<Integer>();
		boolean blocked = false;
		boolean overwrite = false;
		for (Object rawLevel : list(snapshot.root, "levels")) {
			Map<String,Object> levelRecord = map(rawLevel);
			int targetLevel = checkedAdd(level,
				integer(levelRecord, "levelOffset"), "paste level");
			destinationLevels.add(Integer.valueOf(targetLevel));
			if (!live.levels.contains(Integer.valueOf(targetLevel))) {
				collisions.add(collision("unavailable-level", targetLevel, x, y,
					"Destination level is absent from the working package."));
				blocked = true;
				continue;
			}
			for (Object rawTile : list(levelRecord, "tiles")) {
				Map<String,Object> tile = map(rawTile);
				int targetX = checkedAdd(x, integer(tile, "xOffset"), "paste x");
				int targetY = checkedAdd(y, integer(tile, "yOffset"), "paste y");
				byte[] existing = live.tile(targetLevel, targetX, targetY);
				if (existing == null) {
					collisions.add(collision("unavailable-terrain", targetLevel,
						targetX, targetY, "Destination tile has no declared terrain sector."));
					blocked = true;
				} else if (!Arrays.equals(existing, WorldBuilderCanonicalVoidTerrain.tile())) {
					collisions.add(collision("non-void-terrain", targetLevel,
						targetX, targetY, "Destination tile is not canonical structural void."));
					overwrite = true;
				}
			}
		}
		for (Integer targetLevel : destinationLevels) {
			Map<String,Object> payload = live.placements.get(targetLevel);
			if (payload == null) continue;
			for (String family : placementFamilies()) {
				for (Object raw : list(payload, family)) {
					Map<String,Object> record = map(raw);
					Point owner = owner(family, record);
					if (destination.owns(owner.x, owner.y)) {
						collisions.add(collision("occupied-" + singularFamily(family),
							targetLevel.intValue(), owner.x, owner.y,
							"Destination selection owns an existing placement."));
						overwrite = true;
					}
				}
			}
		}
		Set<String> replacingIds = snapshotPlacementIds(snapshot);
		for (Map.Entry<Integer,Map<String,Object>> entry : live.placements.entrySet()) {
			for (String family : placementFamilies()) {
				for (Object raw : list(entry.getValue(), family)) {
					Map<String,Object> record = map(raw);
					String id = text(record, "placementId");
					Point owner = owner(family, record);
					boolean removed = destinationLevels.contains(entry.getKey())
						&& destination.owns(owner.x, owner.y);
					if (!removed && replacingIds.contains(id)) {
						collisions.add(collision("placement-id", entry.getKey().intValue(),
							owner.x, owner.y, "Preserved destination placement already uses ID "
								+ id + "."));
						blocked = true;
					}
				}
			}
		}
		Collections.sort(collisions, canonicalComparator());
		if (blocked) return planOnly(verified, snapshot, "paste", level, x, y,
			collisions, overwrite, true);

		Path stage = stagePackage(verified.projectRoot);
		try {
			PackageState state = PackageState.read(verified.projectRoot,
				relativeStage(verified.projectRoot, stage));
			removeDestinationPlacements(state, destinationLevels, destination);
			applySnapshotTiles(state, snapshot, level, x, y, false);
			addSnapshotPlacements(state, snapshot, level, x, y);
			state.writeAndValidate(verified);
			Map<String,Object> plan = plan(verified, snapshot, "paste", level, x, y,
				stage, collisions, overwrite, false);
			return new PreparedMutation(stage, plan);
		} catch (Exception failure) {
			deleteTree(stage);
			if (failure instanceof IOException) throw (IOException)failure;
			throw (WorldBuilderContractException)failure;
		}
	}

	private static void applySnapshotTiles(PackageState state,
		WorldBuilderRegionContracts.Snapshot snapshot, int destinationLevel,
		int destinationX, int destinationY, boolean voidTiles)
		throws WorldBuilderContractException {
		for (Object rawLevel : list(snapshot.root, "levels")) {
			Map<String,Object> level = map(rawLevel);
			int targetLevel = checkedAdd(destinationLevel,
				integer(level, "levelOffset"), "target level");
			for (Object rawTile : list(level, "tiles")) {
				Map<String,Object> tile = map(rawTile);
				int x = checkedAdd(destinationX, integer(tile, "xOffset"), "target x");
				int y = checkedAdd(destinationY, integer(tile, "yOffset"), "target y");
				byte[] encoded = voidTiles ? WorldBuilderCanonicalVoidTerrain.tile()
					: encodeTile(tile);
				if (!state.setTile(targetLevel, x, y, encoded)) throw problem(
					WorldBuilderErrorCodes.MAP_MISMATCH, "terrain",
					"Region operation reached unavailable terrain after planning.",
					"Discard the stale plan and preview again.");
			}
		}
	}

	private static void removeOwnedPlacements(PackageState state,
		WorldBuilderRegionContracts.Snapshot snapshot, int level, int x, int y)
		throws WorldBuilderContractException {
		WorldBuilderRegionContracts.Geometry geometry = translatedGeometry(snapshot, x, y);
		Set<Integer> levels = snapshotLevels(snapshot, level);
		removeDestinationPlacements(state, levels, geometry);
	}

	private static void removeDestinationPlacements(PackageState state,
		Set<Integer> levels, WorldBuilderRegionContracts.Geometry geometry)
		throws WorldBuilderContractException {
		for (Integer level : levels) {
			Map<String,Object> payload = state.placements.get(level);
			if (payload == null) continue;
			for (String family : placementFamilies()) {
				List<Object> kept = new ArrayList<Object>();
				for (Object raw : list(payload, family)) {
					Map<String,Object> record = map(raw);
					Point point = owner(family, record);
					if (!geometry.owns(point.x, point.y)) kept.add(record);
				}
				payload.put(family, kept);
			}
		}
	}

	private static void addSnapshotPlacements(PackageState state,
		WorldBuilderRegionContracts.Snapshot snapshot, int level, int x, int y)
		throws WorldBuilderContractException {
		Map<String,Object> placements = map(snapshot.root.get("placements"));
		for (String family : placementFamilies()) {
			for (Object raw : list(placements, family)) {
				Map<String,Object> relative = map(raw);
				int targetLevel = checkedAdd(level,
					integer(relative, "levelOffset"), "placement level");
				Map<String,Object> payload = state.placements.get(Integer.valueOf(targetLevel));
				if (payload == null) throw problem(WorldBuilderErrorCodes.MAP_MISMATCH,
					"placements", "Pasted placement level is unavailable.",
					"Preview against the current complete destination package.");
				list(payload, family).add(absolutePlacement(family, relative, x, y));
			}
		}
		for (Map<String,Object> payload : state.placements.values()) {
			for (String family : placementFamilies()) sortPlacements(family,
				list(payload, family));
		}
	}

	private Map<String,Object> compatibility(
		WorldBuilderAdaptiveProjectLifecycle.VerifiedProject verified,
		WorldBuilderRegionContracts.Snapshot snapshot)
		throws WorldBuilderContractException {
		List<Object> issues = new ArrayList<Object>();
		Map<String,Object> source = map(snapshot.root.get("sourceEvidence"));
		if (!snapshot.worldSpace.equals(verified.working.worldSpace)) {
			issues.add(issue("world-space-mismatch", snapshot.worldSpace,
				"Snapshot and project static world spaces differ."));
		}
		if (!"layered-world-package-v1".equals(text(source, "packageSchemaId"))
			|| !"signed-layered-v1".equals(text(source, "coordinateModel"))) {
			issues.add(issue("package-contract-mismatch", text(source, "packageSchemaId"),
				"Snapshot package schema or coordinate model is unsupported."));
		}
		Map<String,Object> catalog = map(snapshot.root.get("catalog"));
		String projectCatalogHash = fingerprint(verified.manifest, "definitionsSha256");
		if (!verified.definitions.catalogId.equals(text(catalog, "catalogId"))
			|| !projectCatalogHash.equals(text(catalog, "sha256"))) {
			issues.add(issue("catalog-mismatch", "definition-catalog",
				"Snapshot and project definition catalogs are not byte-identical."));
		}
		for (Object raw : list(snapshot.root, "dependencies")) {
			Map<String,Object> dependency = map(raw);
			String logical = text(dependency, "logicalId");
			if ("unsupported".equals(text(dependency, "resolution"))) {
				issues.add(issue("unsupported-dependency", logical,
					"Custom material/sprite content is not bundleable in region snapshot v1."));
				continue;
			}
			if ("definition".equals(text(dependency, "kind"))) {
				String family = text(dependency, "family");
				int id = integer(dependency, "numericId");
				if (!hasDefinition(verified.definitions, family, id)) {
					issues.add(issue("missing-definition", logical,
						"Project catalog does not contain the required logical definition."));
				}
			}
		}
		Collections.sort(issues, canonicalComparator());
		Map<String,Object> report = new LinkedHashMap<String,Object>();
		report.put("schemaVersion", Long.valueOf(1L));
		report.put("manifestType", "world-builder-region-compatibility-report");
		report.put("snapshotId", snapshot.id);
		report.put("projectId", verified.projectId);
		report.put("compatible", Boolean.valueOf(issues.isEmpty()));
		report.put("issues", issues);
		report.put("reportFingerprintSha256", WorldBuilderRegionContracts.ZERO_HASH);
		WorldBuilderRegionContracts.bindFingerprint(report, "reportFingerprintSha256");
		WorldBuilderRegionContracts.compatibility(report);
		return report;
	}

	private PreparedMutation blockedPlan(
		WorldBuilderAdaptiveProjectLifecycle.VerifiedProject verified,
		WorldBuilderRegionContracts.Snapshot snapshot, String operation,
		int level, int x, int y, String kind, String detail)
		throws WorldBuilderContractException {
		List<Object> collisions = new ArrayList<Object>();
		collisions.add(collision(kind, level, x, y, detail));
		return planOnly(verified, snapshot, operation, level, x, y,
			collisions, false, true);
	}

	private PreparedMutation planOnly(
		WorldBuilderAdaptiveProjectLifecycle.VerifiedProject verified,
		WorldBuilderRegionContracts.Snapshot snapshot, String operation,
		int level, int x, int y, List<Object> collisions, boolean overwrite,
		boolean blocked) throws WorldBuilderContractException {
		Map<String,Object> plan = planBase(verified, snapshot, operation, level, x, y);
		plan.put("files", new ArrayList<Object>());
		plan.put("collisions", collisions);
		plan.put("overwriteRequired", Boolean.valueOf(overwrite));
		plan.put("blocked", Boolean.valueOf(blocked));
		plan.put("planFingerprintSha256", WorldBuilderRegionContracts.ZERO_HASH);
		WorldBuilderRegionContracts.bindFingerprint(plan, "planFingerprintSha256");
		WorldBuilderRegionContracts.operationPlan(plan);
		return new PreparedMutation(null, plan);
	}

	private Map<String,Object> plan(
		WorldBuilderAdaptiveProjectLifecycle.VerifiedProject verified,
		WorldBuilderRegionContracts.Snapshot snapshot, String operation,
		int level, int x, int y, Path stage, List<Object> collisions,
		boolean overwrite, boolean blocked)
		throws IOException, WorldBuilderContractException {
		Map<String,Object> plan = planBase(verified, snapshot, operation, level, x, y);
		Path live = verified.projectRoot.resolve(PACKAGE);
		List<Object> files = changedFiles(live, stage);
		plan.put("files", blocked ? new ArrayList<Object>() : files);
		plan.put("collisions", collisions);
		plan.put("overwriteRequired", Boolean.valueOf(overwrite));
		plan.put("blocked", Boolean.valueOf(blocked));
		plan.put("planFingerprintSha256", WorldBuilderRegionContracts.ZERO_HASH);
		WorldBuilderRegionContracts.bindFingerprint(plan, "planFingerprintSha256");
		WorldBuilderRegionContracts.operationPlan(plan);
		return plan;
	}

	private static Map<String,Object> planBase(
		WorldBuilderAdaptiveProjectLifecycle.VerifiedProject verified,
		WorldBuilderRegionContracts.Snapshot snapshot, String operation,
		int level, int x, int y) {
		Map<String,Object> plan = new LinkedHashMap<String,Object>();
		plan.put("schemaVersion", Long.valueOf(1L));
		plan.put("manifestType", "world-builder-region-operation-plan");
		plan.put("operation", operation);
		plan.put("snapshotId", snapshot.id);
		plan.put("projectId", verified.projectId);
		plan.put("workingBeforeSha256", verified.working.fingerprintSha256);
		plan.put("destinationAnchor", anchor(level, x, y));
		return plan;
	}

	private static List<Object> changedFiles(Path live, Path stage)
		throws IOException, WorldBuilderContractException {
		Map<String,Path> before = files(live);
		Map<String,Path> after = files(stage);
		if (!before.keySet().equals(after.keySet())) throw problem(
			WorldBuilderErrorCodes.MUTATION_FAILED, "package",
			"Region operation unexpectedly changed the package inventory.",
			"Keep v1 region edits within existing declared terrain and placement files.");
		List<Object> records = new ArrayList<Object>();
		for (String relative : before.keySet()) {
			String beforeHash = WorldBuilderHashes.sha256(before.get(relative));
			String afterHash = WorldBuilderHashes.sha256(after.get(relative));
			if (beforeHash.equals(afterHash)) continue;
			Map<String,Object> record = new LinkedHashMap<String,Object>();
			record.put("relativePath", relative);
			record.put("beforeSha256", beforeHash);
			record.put("afterSha256", afterHash);
			records.add(record);
		}
		if (records.isEmpty()) throw problem(WorldBuilderErrorCodes.CONTRACT_VALUE_INVALID,
			"operation", "Region operation would make no working-package change.",
			"Choose content or a destination whose exact result differs.");
		return records;
	}

	private LibraryRecord publishToLibrary(Path project,
		WorldBuilderRegionContracts.Snapshot snapshot)
		throws IOException, WorldBuilderContractException {
		byte[] snapshotBytes = WorldBuilderJsonDocuments.pretty(snapshot.root)
			.getBytes(StandardCharsets.UTF_8);
		Map<String,Object> manifest = bundleManifest(snapshot, snapshotBytes);
		byte[] bundle = writeBundle(manifest, snapshotBytes);
		return publishBundle(project, new Bundle(bundle, manifest, snapshot));
	}

	private LibraryRecord publishBundle(Path project, Bundle bundle)
		throws IOException, WorldBuilderContractException {
		Path library = library(project, true);
		String name = bundle.snapshot.id + BUNDLE_EXTENSION;
		Path destination = library.resolve(name);
		if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
			Path verified = requireLibraryFile(project, bundle.snapshot.id);
			byte[] existing = Files.readAllBytes(verified);
			if (!Arrays.equals(existing, bundle.bytes)) throw problem(
				WorldBuilderErrorCodes.INVENTORY_DUPLICATE, LIBRARY + "/" + name,
				"Snapshot ID already exists with different bundle bytes.",
				"Preserve the existing library entry and investigate the collision.");
			return new LibraryRecord(LIBRARY + "/" + name,
				WorldBuilderHashes.sha256(existing), false);
		}
		Path stage = library.resolve("." + name + ".staging-" + UUID.randomUUID());
		try {
			Files.write(stage, bundle.bytes, StandardOpenOption.CREATE_NEW,
				StandardOpenOption.WRITE);
			WorldBuilderAdaptiveAtomicFiles.moveNew(stage, destination,
				"region-library", LIBRARY + "/" + name);
		} finally {
			Files.deleteIfExists(stage);
		}
		Bundle reread = readBundle(destination);
		if (!reread.snapshot.id.equals(bundle.snapshot.id)
			|| !Arrays.equals(reread.bytes, bundle.bytes)) throw problem(
			WorldBuilderErrorCodes.MUTATION_FAILED, LIBRARY + "/" + name,
			"Published snapshot library bundle did not verify byte-for-byte.",
			"Preserve the library and request filesystem recovery.");
		return new LibraryRecord(LIBRARY + "/" + name,
			WorldBuilderHashes.sha256(bundle.bytes), true);
	}

	private Bundle loadLibrary(Path project, String snapshotId)
		throws IOException, WorldBuilderContractException {
		if (!WorldBuilderBoundedInventory.isHash(snapshotId)) throw problem(
			WorldBuilderErrorCodes.CONTRACT_VALUE_INVALID, "snapshotId",
			"Snapshot ID is not one lowercase SHA-256 value.",
			"Use the exact ID returned by copy or import.");
		Bundle bundle = readBundle(requireLibraryFile(project, snapshotId));
		if (!snapshotId.equals(bundle.snapshot.id)) throw problem(
			WorldBuilderErrorCodes.SOURCE_CORRUPT, LIBRARY,
			"Library filename and snapshot identity disagree.",
			"Restore the exact content-addressed library entry.");
		return bundle;
	}

	private static Path requireLibraryFile(Path project, String snapshotId)
		throws IOException, WorldBuilderContractException {
		Path library = library(project, false);
		Path path = library.resolve(snapshotId + BUNDLE_EXTENSION).normalize();
		if (!path.getParent().equals(library)
			|| !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
			|| Files.isSymbolicLink(path)) throw problem(
			WorldBuilderErrorCodes.UNSAFE_PATH, LIBRARY,
			"Snapshot library entry is missing, linked, or escaped.",
			"Restore one real content-addressed .wbr file.");
		rejectHardLink(path, LIBRARY + "/" + path.getFileName());
		return path;
	}

	private static Path library(Path project, boolean create)
		throws IOException, WorldBuilderContractException {
		Path root = project.toAbsolutePath().normalize();
		Path library = root.resolve(LIBRARY).normalize();
		if (!library.startsWith(root)) throw problem(WorldBuilderErrorCodes.UNSAFE_PATH,
			LIBRARY, "Snapshot library path escaped the project.",
			"Restore the canonical project-local library path.");
		Path parent = library.getParent();
		if (create && !Files.exists(parent, LinkOption.NOFOLLOW_LINKS)) {
			Files.createDirectory(parent);
		}
		if (!Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)
			|| Files.isSymbolicLink(parent)) throw problem(WorldBuilderErrorCodes.UNSAFE_PATH,
			LIBRARY, "Snapshot library parent is linked or not a directory.",
			"Restore one real project-local snapshot-library directory.");
		if (create && !Files.exists(library, LinkOption.NOFOLLOW_LINKS)) {
			Files.createDirectory(library);
		}
		if (!Files.isDirectory(library, LinkOption.NOFOLLOW_LINKS)
			|| Files.isSymbolicLink(library) || !library.toRealPath().startsWith(root.toRealPath())) {
			throw problem(WorldBuilderErrorCodes.UNSAFE_PATH, LIBRARY,
				"Snapshot library is linked, missing, or outside the project.",
				"Restore one real contained project-local library.");
		}
		int count = 0;
		try (DirectoryStream<Path> entries = Files.newDirectoryStream(library)) {
			for (Path entry : entries) {
				if (++count > 1024) throw problem(
					WorldBuilderErrorCodes.INVENTORY_LIMIT_EXCEEDED, LIBRARY,
					"Snapshot library exceeds 1,024 entries.",
					"Move reviewed bundles to a separate archive before adding more.");
				String name = entry.getFileName().toString();
				if (!name.matches("[0-9a-f]{64}\\.wbr")
					|| !Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)
					|| Files.isSymbolicLink(entry)) throw problem(
					WorldBuilderErrorCodes.UNSAFE_PATH, LIBRARY,
					"Snapshot library contains an unsafe or untracked entry: " + name + ".",
					"Keep only content-addressed regular .wbr bundles in library v1.");
				rejectHardLink(entry, LIBRARY + "/" + name);
			}
		}
		return library;
	}

	private static Bundle readBundle(Path requested)
		throws IOException, WorldBuilderContractException {
		Path path = safeExternalFile(requested, "region bundle", 1L, MAX_BUNDLE_BYTES);
		byte[] archiveBytes = Files.readAllBytes(path);
		Map<String,byte[]> entries = new TreeMap<String,byte[]>();
		try (ZipFile archive = new ZipFile(path.toFile())) {
			Enumeration<? extends ZipEntry> rawEntries = archive.entries();
			while (rawEntries.hasMoreElements()) {
				ZipEntry entry = rawEntries.nextElement();
				if (entry.isDirectory()) throw unsafeBundle("Bundle contains a directory entry.");
				String name = WorldBuilderPortablePath.require(entry.getName(),
					"region-bundle");
				if (!("manifest.json".equals(name) || "snapshot.json".equals(name))
					|| entries.containsKey(name)) throw unsafeBundle(
					"Bundle inventory contains an unexpected or duplicate entry: " + name + ".");
				long declared = entry.getSize();
				if (declared < 2L || declared > MAX_ENTRY_BYTES) throw unsafeBundle(
					"Bundle entry size is outside its bound: " + name + ".");
				try (InputStream input = archive.getInputStream(entry)) {
					entries.put(name, readBounded(input, MAX_ENTRY_BYTES));
				}
			}
		} catch (WorldBuilderContractException refusal) {
			throw refusal;
		} catch (IOException malformed) {
			throw problem(WorldBuilderErrorCodes.UNSUPPORTED_FORMAT, "bundle",
				"Region bundle ZIP is malformed or unreadable.",
				"Use an exact portable-region-bundle-v1 archive.", malformed);
		}
		if (!entries.keySet().equals(new TreeSet<String>(
			Arrays.asList("manifest.json", "snapshot.json")))) throw unsafeBundle(
			"Bundle must contain exactly manifest.json and snapshot.json.");
		Map<String,Object> manifest = readJson(entries.get("manifest.json"), "manifest.json");
		Map<String,Object> snapshotRoot = readJson(entries.get("snapshot.json"), "snapshot.json");
		WorldBuilderRegionContracts.Snapshot snapshot =
			WorldBuilderRegionContracts.snapshot(snapshotRoot);
		WorldBuilderRegionContracts.bundleManifest(manifest,
			WorldBuilderHashes.sha256(entries.get("snapshot.json")),
			entries.get("snapshot.json").length);
		if (!snapshot.id.equals(text(manifest, "snapshotId"))) throw unsafeBundle(
			"Bundle manifest and snapshot identities disagree.");
		return new Bundle(archiveBytes, manifest, snapshot);
	}

	private static Map<String,Object> bundleManifest(
		WorldBuilderRegionContracts.Snapshot snapshot, byte[] snapshotBytes)
		throws WorldBuilderContractException {
		Map<String,Object> file = new LinkedHashMap<String,Object>();
		file.put("role", "snapshot");
		file.put("relativePath", "snapshot.json");
		file.put("size", Long.valueOf(snapshotBytes.length));
		file.put("sha256", WorldBuilderHashes.sha256(snapshotBytes));
		Map<String,Object> manifest = new LinkedHashMap<String,Object>();
		manifest.put("schemaVersion", Long.valueOf(1L));
		manifest.put("manifestType", "world-builder-region-bundle");
		manifest.put("formatId", "portable-region-bundle-v1");
		manifest.put("snapshotId", snapshot.id);
		manifest.put("files", Arrays.<Object>asList(file));
		manifest.put("bundleFingerprintSha256", WorldBuilderRegionContracts.ZERO_HASH);
		WorldBuilderRegionContracts.bindFingerprint(manifest, "bundleFingerprintSha256");
		WorldBuilderRegionContracts.bundleManifest(manifest,
			WorldBuilderHashes.sha256(snapshotBytes), snapshotBytes.length);
		return manifest;
	}

	private static byte[] writeBundle(Map<String,Object> manifest, byte[] snapshot)
		throws IOException {
		byte[] manifestBytes = WorldBuilderJsonDocuments.pretty(manifest)
			.getBytes(StandardCharsets.UTF_8);
		ByteArrayOutputStream output = new ByteArrayOutputStream(
			manifestBytes.length + snapshot.length + 512);
		try (ZipOutputStream archive = new ZipOutputStream(output)) {
			writeStored(archive, "manifest.json", manifestBytes);
			writeStored(archive, "snapshot.json", snapshot);
		}
		return output.toByteArray();
	}

	private static void writeStored(ZipOutputStream archive, String name, byte[] bytes)
		throws IOException {
		CRC32 crc = new CRC32(); crc.update(bytes);
		ZipEntry entry = new ZipEntry(name);
		entry.setMethod(ZipEntry.STORED); entry.setTime(ZIP_TIME);
		entry.setSize(bytes.length); entry.setCompressedSize(bytes.length);
		entry.setCrc(crc.getValue());
		archive.putNextEntry(entry); archive.write(bytes); archive.closeEntry();
	}

	private static Map<String,Object> readJson(byte[] bytes, String label)
		throws WorldBuilderContractException {
		try {
			return WorldBuilderJsonDocuments.readObject(bytes, label);
		} catch (WorldBuilderDiscoveryException malformed) {
			throw problem(WorldBuilderErrorCodes.MALFORMED_JSON, label,
				"Region bundle JSON is malformed.",
				"Use canonical bounded UTF-8 region contract JSON.", malformed);
		}
	}

	private static Path stagePackage(Path project)
		throws IOException, WorldBuilderContractException {
		Path parent = project.resolve("working/layered-world");
		Path stage = parent.resolve(".region-stage-" + UUID.randomUUID());
		copyTree(project.resolve(PACKAGE), stage);
		return stage;
	}

	private static void publishWorkingPackage(Path project, Path stage)
		throws IOException, WorldBuilderContractException {
		if (stage == null) throw problem(WorldBuilderErrorCodes.MUTATION_FAILED,
			"package", "Blocked plan has no publishable staged package.",
			"Resolve every blocker and preview again.");
		Path live = project.resolve(PACKAGE);
		Path rollback = rollbackPath(project);
		if (Files.exists(rollback, LinkOption.NOFOLLOW_LINKS)) throw problem(
			WorldBuilderErrorCodes.RECOVERY_REQUIRED, "working/layered-world",
			"A prior region operation retains rollback state.",
			"Recover or inspect the exact retained package before retrying.");
		try {
			Files.move(live, rollback, StandardCopyOption.ATOMIC_MOVE);
			try {
				Files.move(stage, live, StandardCopyOption.ATOMIC_MOVE);
			} catch (Exception failure) {
				Files.move(rollback, live, StandardCopyOption.ATOMIC_MOVE);
				throw failure;
			}
		} catch (AtomicMoveNotSupportedException unsupported) {
			throw problem(WorldBuilderErrorCodes.MUTATION_FAILED, PACKAGE,
				"Filesystem cannot atomically exchange the working package.",
				"Use a local filesystem with same-directory atomic moves.", unsupported);
		}
	}

	private static void rollbackWorkingPublication(Path project, Exception original)
		throws IOException, WorldBuilderContractException {
		Path live = project.resolve(PACKAGE);
		Path rollback = rollbackPath(project);
		Path failed = project.resolve("working/layered-world/.region-failed-"
			+ UUID.randomUUID());
		try {
			Files.move(live, failed, StandardCopyOption.ATOMIC_MOVE);
			Files.move(rollback, live, StandardCopyOption.ATOMIC_MOVE);
			deleteTree(failed);
		} catch (Exception rollbackFailure) {
			rollbackFailure.addSuppressed(original);
			throw problem(WorldBuilderErrorCodes.RECOVERY_REQUIRED, PACKAGE,
				"Region operation failed and exact working-package rollback did not complete.",
				"Preserve .region-original-v1 and request exact recovery.", rollbackFailure);
		}
	}

	private static void completeWorkingPublication(Path project)
		throws IOException {
		deleteTree(rollbackPath(project));
	}

	private static Path rollbackPath(Path project) {
		return project.resolve("working/layered-world/.region-original-v1");
	}

	private static String relativeStage(Path project, Path stage) {
		return project.relativize(stage).toString().replace('\\', '/');
	}

	private static void copyTree(Path source, Path destination)
		throws IOException, WorldBuilderContractException {
		Files.createDirectory(destination);
		for (Map.Entry<String,Path> entry : files(source).entrySet()) {
			Path target = destination.resolve(entry.getKey());
			Files.createDirectories(target.getParent());
			Files.copy(entry.getValue(), target);
		}
	}

	private static Map<String,Path> files(Path root)
		throws IOException, WorldBuilderContractException {
		if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)
			|| Files.isSymbolicLink(root)) throw problem(WorldBuilderErrorCodes.UNSAFE_PATH,
			"package", "Working package is missing, linked, or not a directory.",
			"Restore the complete real project-local working package.");
		Map<String,Path> result = new TreeMap<String,Path>();
		java.nio.file.Files.walkFileTree(root,
			java.util.EnumSet.noneOf(java.nio.file.FileVisitOption.class), 16,
			new java.nio.file.SimpleFileVisitor<Path>() {
				@Override public java.nio.file.FileVisitResult preVisitDirectory(
					Path directory, BasicFileAttributes attributes) throws IOException {
					if (attributes.isSymbolicLink()) throw new IOException("linked directory");
					return java.nio.file.FileVisitResult.CONTINUE;
				}
				@Override public java.nio.file.FileVisitResult visitFile(
					Path file, BasicFileAttributes attributes) throws IOException {
					if (!attributes.isRegularFile() || attributes.isSymbolicLink()) {
						throw new IOException("unsafe package file");
					}
					String relative = root.relativize(file).toString().replace('\\', '/');
					try {
						WorldBuilderPortablePath.require(relative, OPERATION);
					} catch (WorldBuilderContractException unsafe) {
						throw new IOException(unsafe);
					}
					result.put(relative, file);
					return java.nio.file.FileVisitResult.CONTINUE;
				}
			});
		return result;
	}

	private static void deleteTree(Path root) throws IOException {
		if (root == null || !Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return;
		Files.walkFileTree(root, new java.nio.file.SimpleFileVisitor<Path>() {
			@Override public java.nio.file.FileVisitResult visitFile(Path file,
				BasicFileAttributes attributes) throws IOException {
				Files.delete(file); return java.nio.file.FileVisitResult.CONTINUE;
			}
			@Override public java.nio.file.FileVisitResult postVisitDirectory(
				Path directory, IOException failure) throws IOException {
				if (failure != null) throw failure;
				Files.delete(directory); return java.nio.file.FileVisitResult.CONTINUE;
			}
		});
	}

	private static Path safeExternalFile(Path requested, String label,
		long minimum, long maximum) throws IOException, WorldBuilderContractException {
		if (requested == null) throw problem(WorldBuilderErrorCodes.UNSAFE_PATH, label,
			"Required external file path is absent.", "Choose one real bounded file.");
		Path path = requested.toAbsolutePath().normalize();
		if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
			|| Files.isSymbolicLink(path)) throw problem(WorldBuilderErrorCodes.UNSAFE_PATH,
			label, "External file is missing, linked, or not regular.",
			"Choose one real independent file.");
		rejectHardLink(path, label);
		long size = Files.size(path);
		if (size < minimum || size > maximum) throw problem(
			WorldBuilderErrorCodes.CONTRACT_LIMIT_EXCEEDED, label,
			"External file size is outside its bound.", "Choose one bounded file.");
		return path;
	}

	private static Path safeNewOutput(Path requested, Path project)
		throws IOException, WorldBuilderContractException {
		if (requested == null) throw problem(WorldBuilderErrorCodes.UNSAFE_PATH, "output",
			"Region export output path is absent.", "Choose a new .wbr output path.");
		Path output = requested.toAbsolutePath().normalize();
		if (output.startsWith(project.toAbsolutePath().normalize())) throw problem(
			WorldBuilderErrorCodes.UNSAFE_PATH, "output",
			"Region export output cannot be inside the adaptive project.",
			"Choose a separate creator-owned export directory.");
		if (!output.getFileName().toString().endsWith(BUNDLE_EXTENSION)
			|| Files.exists(output, LinkOption.NOFOLLOW_LINKS)) throw problem(
			WorldBuilderErrorCodes.TARGET_DRIFT, "output",
			"Region export destination exists or is not a .wbr path.",
			"Choose a new portable bundle filename.");
		Path parent = output.getParent();
		if (parent == null || !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)
			|| Files.isSymbolicLink(parent)) throw problem(WorldBuilderErrorCodes.UNSAFE_PATH,
			"output", "Region export parent is missing or linked.",
			"Choose a real existing output directory.");
		return output;
	}

	private static void rejectHardLink(Path path, String label)
		throws IOException, WorldBuilderContractException {
		try {
			Object links = Files.getAttribute(path, "unix:nlink", LinkOption.NOFOLLOW_LINKS);
			if (links instanceof Number && ((Number)links).longValue() != 1L) {
				throw problem(WorldBuilderErrorCodes.UNSAFE_PATH, label,
					"Region file is hard linked.", "Use one independent regular file.");
			}
		} catch (UnsupportedOperationException ignored) {
			path.toRealPath();
		} catch (IllegalArgumentException ignored) {
			path.toRealPath();
		}
	}

	private static byte[] readBounded(InputStream input, long maximum)
		throws IOException, WorldBuilderContractException {
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		byte[] buffer = new byte[8192];
		int read;
		while ((read = input.read(buffer)) >= 0) {
			if (read == 0) continue;
			if ((long)output.size() + read > maximum) throw unsafeBundle(
				"Bundle entry expands beyond its limit.");
			output.write(buffer, 0, read);
		}
		return output.toByteArray();
	}

	private static WorldBuilderContractException unsafeBundle(String message) {
		return problem(WorldBuilderErrorCodes.UNSUPPORTED_FORMAT, "bundle", message,
			"Use a strict two-entry portable-region-bundle-v1 archive.");
	}

	private static Map<String,Object> baseResult(String operation, String id,
		LibraryRecord library) {
		Map<String,Object> result = new LinkedHashMap<String,Object>();
		result.put("operation", operation);
		result.put("snapshotId", id);
		result.put("libraryRelativePath", library.relativePath);
		result.put("bundleSha256", library.sha256);
		result.put("libraryEntryCreated", Boolean.valueOf(library.created));
		result.put("worldModified", Boolean.FALSE);
		return result;
	}

	private static Map<String,Object> anchor(int level, int x, int y) {
		Map<String,Object> result = new LinkedHashMap<String,Object>();
		result.put("level", Long.valueOf(level));
		result.put("x", Long.valueOf(x));
		result.put("y", Long.valueOf(y));
		return result;
	}

	private static Map<String,Object> emptyPlacements() {
		Map<String,Object> result = new LinkedHashMap<String,Object>();
		for (String family : placementFamilies()) result.put(family,
			new ArrayList<Object>());
		return result;
	}

	private static List<String> placementFamilies() {
		return Arrays.asList("boundaries", "groundItems", "npcs", "scenery");
	}

	private static String singularFamily(String family) {
		if ("boundaries".equals(family)) return "boundary";
		if ("groundItems".equals(family)) return "ground-item";
		if ("npcs".equals(family)) return "npc";
		if ("scenery".equals(family)) return "scenery";
		throw new AssertionError(family);
	}

	private static int definitionId(String family, Map<String,Object> record) {
		if ("boundaries".equals(family)) return integer(record, "boundaryId");
		if ("groundItems".equals(family)) return integer(record, "itemId");
		if ("npcs".equals(family)) return integer(record, "npcId");
		return integer(record, "sceneryId");
	}

	private static Point owner(String family, Map<String,Object> record) {
		if ("npcs".equals(family)) return point(record.get("start"));
		return point(record.get("position"));
	}

	private static boolean crossesBoundary(String family, Map<String,Object> record,
		WorldBuilderRegionContracts.Geometry geometry) {
		Point origin = owner(family, record);
		if ("boundaries".equals(family)) {
			int direction = integer(record, "direction");
			int[] dx = {0, 1, 0, -1};
			int[] dy = {-1, 0, 1, 0};
			return !geometry.owns(origin.x + dx[direction], origin.y + dy[direction]);
		}
		if ("npcs".equals(family)) {
			Map<String,Object> bounds = map(record.get("roamBounds"));
			Point minimum = point(bounds.get("minimum"));
			Point maximum = point(bounds.get("maximum"));
			for (int x = minimum.x; x <= maximum.x; x++) {
				for (int y = minimum.y; y <= maximum.y; y++) {
					if (!geometry.owns(x, y)) return true;
				}
			}
		}
		return false;
	}

	private static String footprintDetail(String family, boolean crossing) {
		String rule;
		if ("boundaries".equals(family)) rule =
			"boundary direction maps to one adjacent edge tile";
		else if ("npcs".equals(family)) rule = "complete roam rectangle";
		else rule = "one represented anchor tile";
		return rule + (crossing ? "; footprint crosses polygon" : "; footprint contained");
	}

	private static Map<String,Object> relativePlacement(String family,
		Map<String,Object> record, int levelOffset, int anchorX, int anchorY) {
		Map<String,Object> output = new LinkedHashMap<String,Object>();
		output.put("levelOffset", Long.valueOf(levelOffset));
		output.put("placementId", text(record, "placementId"));
		if ("boundaries".equals(family)) {
			output.put("boundaryId", Long.valueOf(integer(record, "boundaryId")));
			output.put("direction", Long.valueOf(integer(record, "direction")));
			output.put("position", relativePoint(record.get("position"), anchorX, anchorY));
		} else if ("groundItems".equals(family)) {
			output.put("itemId", Long.valueOf(integer(record, "itemId")));
			output.put("amount", Long.valueOf(integer(record, "amount")));
			output.put("respawnSeconds", Long.valueOf(integer(record, "respawnSeconds")));
			output.put("position", relativePoint(record.get("position"), anchorX, anchorY));
		} else if ("npcs".equals(family)) {
			output.put("npcId", Long.valueOf(integer(record, "npcId")));
			output.put("start", relativePoint(record.get("start"), anchorX, anchorY));
			Map<String,Object> bounds = map(record.get("roamBounds"));
			Map<String,Object> relativeBounds = new LinkedHashMap<String,Object>();
			relativeBounds.put("minimum", relativePoint(bounds.get("minimum"), anchorX, anchorY));
			relativeBounds.put("maximum", relativePoint(bounds.get("maximum"), anchorX, anchorY));
			output.put("roamBounds", relativeBounds);
		} else {
			output.put("sceneryId", Long.valueOf(integer(record, "sceneryId")));
			output.put("direction", Long.valueOf(integer(record, "direction")));
			output.put("position", relativePoint(record.get("position"), anchorX, anchorY));
		}
		return output;
	}

	private static Map<String,Object> absolutePlacement(String family,
		Map<String,Object> relative, int x, int y) throws WorldBuilderContractException {
		Map<String,Object> output = new LinkedHashMap<String,Object>();
		if ("boundaries".equals(family)) {
			output.put("boundaryId", Long.valueOf(integer(relative, "boundaryId")));
			output.put("direction", Long.valueOf(integer(relative, "direction")));
			output.put("placementId", text(relative, "placementId"));
			output.put("position", absolutePoint(relative.get("position"), x, y));
		} else if ("groundItems".equals(family)) {
			output.put("amount", Long.valueOf(integer(relative, "amount")));
			output.put("itemId", Long.valueOf(integer(relative, "itemId")));
			output.put("placementId", text(relative, "placementId"));
			output.put("position", absolutePoint(relative.get("position"), x, y));
			output.put("respawnSeconds", Long.valueOf(integer(relative, "respawnSeconds")));
		} else if ("npcs".equals(family)) {
			output.put("npcId", Long.valueOf(integer(relative, "npcId")));
			output.put("placementId", text(relative, "placementId"));
			Map<String,Object> bounds = map(relative.get("roamBounds"));
			Map<String,Object> absoluteBounds = new LinkedHashMap<String,Object>();
			absoluteBounds.put("maximum", absolutePoint(bounds.get("maximum"), x, y));
			absoluteBounds.put("minimum", absolutePoint(bounds.get("minimum"), x, y));
			output.put("roamBounds", absoluteBounds);
			output.put("start", absolutePoint(relative.get("start"), x, y));
		} else {
			output.put("direction", Long.valueOf(integer(relative, "direction")));
			output.put("placementId", text(relative, "placementId"));
			output.put("position", absolutePoint(relative.get("position"), x, y));
			output.put("sceneryId", Long.valueOf(integer(relative, "sceneryId")));
		}
		return output;
	}

	private static Map<String,Object> relativePoint(Object raw, int anchorX, int anchorY) {
		Point point = point(raw);
		Map<String,Object> result = new LinkedHashMap<String,Object>();
		result.put("xOffset", Long.valueOf((long)point.x - anchorX));
		result.put("yOffset", Long.valueOf((long)point.y - anchorY));
		return result;
	}

	private static Map<String,Object> absolutePoint(Object raw, int x, int y)
		throws WorldBuilderContractException {
		Map<String,Object> point = map(raw);
		Map<String,Object> result = new LinkedHashMap<String,Object>();
		result.put("x", Long.valueOf(checkedAdd(x, integer(point, "xOffset"), "point x")));
		result.put("y", Long.valueOf(checkedAdd(y, integer(point, "yOffset"), "point y")));
		return result;
	}

	private static byte[] encodeTile(Map<String,Object> tile) {
		ByteBuffer output = ByteBuffer.allocate(WorldBuilderRawLayeredTerrainCodec.TILE_BYTES);
		output.put((byte)integer(tile, "elevation"));
		output.put((byte)integer(tile, "groundTexture"));
		output.put((byte)integer(tile, "groundOverlay"));
		output.put((byte)integer(tile, "roofTexture"));
		output.put((byte)integer(tile, "verticalWall"));
		output.put((byte)integer(tile, "horizontalWall"));
		output.putInt(integer(tile, "diagonalWall"));
		return output.array();
	}

	private static Set<Integer> snapshotLevels(
		WorldBuilderRegionContracts.Snapshot snapshot, int destinationLevel)
		throws WorldBuilderContractException {
		Set<Integer> result = new HashSet<Integer>();
		for (Object raw : list(snapshot.root, "levels")) {
			result.add(Integer.valueOf(checkedAdd(destinationLevel,
				integer(map(raw), "levelOffset"), "snapshot level")));
		}
		return result;
	}

	private static WorldBuilderRegionContracts.Geometry translatedGeometry(
		WorldBuilderRegionContracts.Snapshot snapshot, int x, int y)
		throws WorldBuilderContractException {
		List<WorldBuilderRegionContracts.Point> points =
			new ArrayList<WorldBuilderRegionContracts.Point>();
		for (Object raw : list(snapshot.root, "polygon")) {
			Map<String,Object> marker = map(raw);
			points.add(new WorldBuilderRegionContracts.Point(
				checkedAdd(x, integer(marker, "xOffset"), "polygon x"),
				checkedAdd(y, integer(marker, "yOffset"), "polygon y")));
		}
		return WorldBuilderRegionContracts.Geometry.create(points, "region-paste");
	}

	private static Set<String> snapshotPlacementIds(
		WorldBuilderRegionContracts.Snapshot snapshot) {
		Set<String> ids = new HashSet<String>();
		Map<String,Object> placements = map(snapshot.root.get("placements"));
		for (String family : placementFamilies()) {
			for (Object raw : list(placements, family)) {
				ids.add(text(map(raw), "placementId"));
			}
		}
		return ids;
	}

	private static boolean hasDefinition(
		WorldBuilderCompatibilityEvidence.DefinitionCatalog definitions,
		String family, int id) {
		if ("boundary".equals(family)) return definitions.boundaries.contains(id);
		if ("ground-item".equals(family)) return definitions.groundItems.contains(id);
		if ("npc".equals(family)) return definitions.npcs.contains(id);
		if ("scenery".equals(family)) return definitions.scenery.contains(id);
		return false;
	}

	private static Map<String,Object> issue(String code, String dependency,
		String message) {
		Map<String,Object> result = new LinkedHashMap<String,Object>();
		result.put("code", code); result.put("dependency", dependency);
		result.put("message", message); return result;
	}

	private static Map<String,Object> collision(String kind, int level, int x,
		int y, String detail) {
		Map<String,Object> result = new LinkedHashMap<String,Object>();
		result.put("kind", kind); result.put("level", Long.valueOf(level));
		result.put("x", Long.valueOf(x)); result.put("y", Long.valueOf(y));
		result.put("detail", detail); return result;
	}

	private static String fingerprint(Map<String,Object> manifest, String key) {
		return text(map(manifest.get("fingerprints")), key);
	}

	private static int anchorX(WorldBuilderRegionContracts.Snapshot snapshot) {
		return integer(map(snapshot.root.get("anchor")), "x");
	}

	private static int anchorY(WorldBuilderRegionContracts.Snapshot snapshot) {
		return integer(map(snapshot.root.get("anchor")), "y");
	}

	private static int checkedAdd(int base, int offset, String label)
		throws WorldBuilderContractException {
		long value = (long)base + offset;
		if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) throw problem(
			WorldBuilderErrorCodes.CONTRACT_VALUE_INVALID, label,
			"Translated region coordinate exceeds signed 32-bit range.",
			"Choose a destination whose complete snapshot remains representable.");
		return (int)value;
	}

	@SuppressWarnings("unchecked")
	private static Map<String,Object> map(Object raw) {
		return (Map<String,Object>)raw;
	}

	@SuppressWarnings("unchecked")
	private static List<Object> list(Map<String,Object> value, String key) {
		return (List<Object>)value.get(key);
	}

	private static String text(Map<String,Object> value, String key) {
		return (String)value.get(key);
	}

	private static int integer(Map<String,Object> value, String key) {
		return (int)((Long)value.get(key)).longValue();
	}

	private static Point point(Object raw) {
		Map<String,Object> value = map(raw);
		return new Point(integer(value, "x"), integer(value, "y"));
	}

	private static void sortCanonical(List<Object> values) {
		Collections.sort(values, canonicalComparator());
	}

	private static Comparator<Object> canonicalComparator() {
		return new Comparator<Object>() {
			@Override public int compare(Object left, Object right) {
				return WorldBuilderRegionContracts.canonical(left).compareTo(
					WorldBuilderRegionContracts.canonical(right));
			}
		};
	}

	private static void sortPlacements(String family, List<Object> records) {
		Collections.sort(records, new Comparator<Object>() {
			@Override public int compare(Object left, Object right) {
				Map<String,Object> a = map(left), b = map(right);
				Point ap = owner(family, a), bp = owner(family, b);
				int result = Integer.compare(ap.x, bp.x);
				if (result == 0) result = Integer.compare(ap.y, bp.y);
				if (result == 0 && "boundaries".equals(family)) {
					result = Integer.compare(integer(a, "direction"), integer(b, "direction"));
				}
				if (result == 0) result = text(a, "placementId").compareTo(
					text(b, "placementId"));
				return result;
			}
		});
	}

	private static WorldBuilderContractException problem(String code, String path,
		String message, String nextStep) {
		return new WorldBuilderContractException(code, OPERATION, path, false,
			message, nextStep);
	}

	private static WorldBuilderContractException problem(String code, String path,
		String message, String nextStep, Throwable cause) {
		return new WorldBuilderContractException(code, OPERATION, path, false,
			message, nextStep, cause);
	}

	private static final class Capture {
		final WorldBuilderRegionContracts.Snapshot snapshot;
		Capture(WorldBuilderRegionContracts.Snapshot snapshot) { this.snapshot = snapshot; }
	}

	private static final class Bundle {
		final byte[] bytes;
		final Map<String,Object> manifest;
		final WorldBuilderRegionContracts.Snapshot snapshot;
		Bundle(byte[] bytes, Map<String,Object> manifest,
			WorldBuilderRegionContracts.Snapshot snapshot) {
			this.bytes = bytes; this.manifest = manifest; this.snapshot = snapshot;
		}
	}

	private static final class LibraryRecord {
		final String relativePath;
		final String sha256;
		final boolean created;
		LibraryRecord(String relativePath, String sha256, boolean created) {
			this.relativePath = relativePath; this.sha256 = sha256;
			this.created = created;
		}
	}

	private static final class PreparedMutation {
		final Path stage;
		final Map<String,Object> plan;
		PreparedMutation(Path stage, Map<String,Object> plan) {
			this.stage = stage; this.plan = plan;
		}
		void discard() throws IOException { deleteTree(stage); }
	}

	private static final class Destination {
		final int level; final int x; final int y;
		Destination(int level, int x, int y) { this.level = level; this.x = x; this.y = y; }
	}

	private static final class Point {
		final int x; final int y;
		Point(int x, int y) { this.x = x; this.y = y; }
	}

	private static final class Dependency implements Comparable<Dependency> {
		final String kind, family, logicalId, catalogId, contentSha256, resolution;
		final int numericId;
		Dependency(String kind, String family, String logicalId, int numericId,
			String catalogId, String contentSha256, String resolution) {
			this.kind = kind; this.family = family; this.logicalId = logicalId;
			this.numericId = numericId; this.catalogId = catalogId;
			this.contentSha256 = contentSha256; this.resolution = resolution;
		}
		static Dependency catalog(String id, String sha256) {
			return new Dependency("definition-catalog", "catalog", "catalog:" + id,
				-1, id, sha256, "catalog");
		}
		static Dependency definition(String catalog, String family, int id) {
			return new Dependency("definition", family,
				"catalog:" + catalog + ":" + family + ":" + id,
				id, catalog, "", "catalog");
		}
		Map<String,Object> toJson() {
			Map<String,Object> value = new LinkedHashMap<String,Object>();
			value.put("kind", kind); value.put("family", family);
			value.put("logicalId", logicalId); value.put("numericId", Long.valueOf(numericId));
			value.put("catalogId", catalogId); value.put("contentSha256", contentSha256);
			value.put("resolution", resolution); value.put("bundled", Boolean.FALSE);
			return value;
		}
		@Override public int compareTo(Dependency other) {
			return (kind + "\u0000" + family + "\u0000" + logicalId).compareTo(
				other.kind + "\u0000" + other.family + "\u0000" + other.logicalId);
		}
	}

	private static final class PackageState {
		final Path project;
		final String relative;
		final Path root;
		final Map<String,Object> manifest;
		final String worldSpace;
		final String coordinateModel;
		final Set<Integer> levels = new HashSet<Integer>();
		final Map<String,Sector> sectors = new HashMap<String,Sector>();
		final Map<Integer,Map<String,Object>> placements =
			new TreeMap<Integer,Map<String,Object>>();
		final Map<Integer,Map<String,Object>> placementDeclarations =
			new HashMap<Integer,Map<String,Object>>();

		private PackageState(Path project, String relative, Map<String,Object> manifest,
			String worldSpace, String coordinateModel) {
			this.project = project; this.relative = relative;
			this.root = project.resolve(relative); this.manifest = manifest;
			this.worldSpace = worldSpace; this.coordinateModel = coordinateModel;
		}

		static PackageState read(Path project, String relative)
			throws IOException, WorldBuilderContractException {
			Path root = project.resolve(relative);
			Map<String,Object> manifest;
			try {
				manifest = WorldBuilderJsonDocuments.readObject(root.resolve("manifest.json"));
			} catch (WorldBuilderDiscoveryException malformed) {
				throw problem(WorldBuilderErrorCodes.MALFORMED_JSON, relative,
					"Working package manifest changed or is malformed.",
					"Restore the last complete saved working package.", malformed);
			}
			String worldSpace = text(map(list(manifest, "worldSpaces").get(0)), "id");
			PackageState state = new PackageState(project, relative, manifest,
				worldSpace, text(manifest, "coordinateModel"));
			for (Object raw : list(manifest, "levels")) {
				state.levels.add(Integer.valueOf(integer(map(raw), "level")));
			}
			for (Object raw : list(manifest, "terrainSectors")) {
				Map<String,Object> declaration = map(raw);
				int level = integer(declaration, "level");
				int sx = integer(declaration, "sectorX");
				int sy = integer(declaration, "sectorY");
				Path path = root.resolve(text(declaration, "path"));
				state.sectors.put(key(level, sx, sy),
					new Sector(declaration, path, Files.readAllBytes(path)));
			}
			for (Object raw : list(manifest, "placementSets")) {
				Map<String,Object> declaration = map(raw);
				int level = integer(declaration, "level");
				try {
					state.placements.put(Integer.valueOf(level),
						WorldBuilderJsonDocuments.readObject(
							root.resolve(text(declaration, "path"))));
				} catch (WorldBuilderDiscoveryException malformed) {
					throw problem(WorldBuilderErrorCodes.MALFORMED_JSON, relative,
						"Working placement payload changed or is malformed.",
						"Restore the last complete saved working package.", malformed);
				}
				state.placementDeclarations.put(Integer.valueOf(level), declaration);
			}
			return state;
		}

		byte[] tile(int level, int x, int y) {
			Sector sector = sectors.get(key(level, Math.floorDiv(x, 48), Math.floorDiv(y, 48)));
			if (sector == null) return null;
			int offset = (Math.floorMod(x, 48) * 48 + Math.floorMod(y, 48)) * 10;
			return Arrays.copyOfRange(sector.bytes, offset, offset + 10);
		}

		boolean setTile(int level, int x, int y, byte[] value) {
			Sector sector = sectors.get(key(level, Math.floorDiv(x, 48), Math.floorDiv(y, 48)));
			if (sector == null) return false;
			int offset = (Math.floorMod(x, 48) * 48 + Math.floorMod(y, 48)) * 10;
			System.arraycopy(value, 0, sector.bytes, offset, 10);
			return true;
		}

		void writeAndValidate(
			WorldBuilderAdaptiveProjectLifecycle.VerifiedProject verified)
			throws IOException, WorldBuilderContractException {
			for (Sector sector : sectors.values()) {
				Files.write(sector.path, sector.bytes);
				sector.declaration.put("sha256", WorldBuilderHashes.sha256(sector.path));
			}
			for (Map.Entry<Integer,Map<String,Object>> entry : placements.entrySet()) {
				Map<String,Object> declaration = placementDeclarations.get(entry.getKey());
				Path path = root.resolve(text(declaration, "path"));
				Files.write(path, WorldBuilderJsonDocuments.pretty(entry.getValue())
					.getBytes(StandardCharsets.UTF_8));
				declaration.put("sha256", WorldBuilderHashes.sha256(path));
			}
			Files.write(root.resolve("manifest.json"),
				WorldBuilderJsonDocuments.pretty(manifest).getBytes(StandardCharsets.UTF_8));
			WorldBuilderGenericLayeredPackage.inspect(
				WorldBuilderReadOnlyTarget.open(project), relative, "region-stage",
				verified.definitions);
		}

		private static String key(int level, int x, int y) {
			return level + ":" + x + ":" + y;
		}
	}

	private static final class Sector {
		final Map<String,Object> declaration;
		final Path path;
		final byte[] bytes;
		Sector(Map<String,Object> declaration, Path path, byte[] bytes) {
			this.declaration = declaration; this.path = path; this.bytes = bytes;
		}
	}
}
