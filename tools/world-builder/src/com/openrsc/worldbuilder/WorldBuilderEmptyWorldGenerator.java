package com.openrsc.worldbuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

/** Generates the canonical structural void used by standalone empty projects. */
final class WorldBuilderEmptyWorldGenerator {
	static final String GENERATOR_ID = "empty-world-v1";
	static final String LEGACY_CATALOG_ID = "world-builder-empty-default-v1";
	static final String CATALOG_ID = "world-builder-runtime-default-v1";
	static final String RUNTIME_ID = "world-builder-empty-runtime-v1";
	static final String DESCRIPTOR_PATH = "source/original/empty-world-v1.json";
	static final String CATALOG_PATH =
		"source/runtime/default-definition-catalog.json";
	static final String RUNTIME_PATH = "source/runtime/default-runtime-evidence.json";
	static final int INITIAL_LEVEL = 0;
	static final int INITIAL_X = 120;
	static final int INITIAL_Y = 648;
	static final int INITIAL_SECTOR_X = Math.floorDiv(INITIAL_X, 48);
	static final int INITIAL_SECTOR_Y = Math.floorDiv(INITIAL_Y, 48);
	static final int INITIAL_LOCAL_X = Math.floorMod(INITIAL_X, 48);
	static final int INITIAL_LOCAL_Y = Math.floorMod(INITIAL_Y, 48);
	private static final String PACKAGE_PATH = "source/layered-baseline/package";
	private static final String PACKAGE_ID = "world-builder.empty-world-v1";
	private static final String PACKAGE_VERSION = "1.0.0";
	private static final String WORLD_SPACE =
		WorldBuilderCanonicalVoidTerrain.WORLD_SPACE;

	private WorldBuilderEmptyWorldGenerator() {
	}

	static Result generate(Path projectRoot, String applicationRuntimeSha256,
		WorldBuilderAdaptiveRuntimePreparer.SourceRuntime sourceRuntime)
		throws IOException, WorldBuilderContractException {
		Path catalogPath = projectRoot.resolve(CATALOG_PATH);
		Files.createDirectories(catalogPath.getParent());
		Map<String,Object> catalog =
			WorldBuilderStandaloneDefinitionCatalog.generate(sourceRuntime, CATALOG_ID);
		writeJson(catalogPath, catalog);
		String catalogSha256 = WorldBuilderHashes.sha256(catalogPath);
		WorldBuilderCompatibilityEvidence.DefinitionCatalog definitions =
			WorldBuilderCompatibilityEvidence.DefinitionCatalog.read(
				WorldBuilderReadOnlyTarget.open(projectRoot), CATALOG_PATH);

		Path runtimePath = projectRoot.resolve(RUNTIME_PATH);
		Map<String,Object> runtime = new LinkedHashMap<String,Object>();
		runtime.put("schemaVersion", Long.valueOf(1L));
		runtime.put("manifestType", "world-builder-standalone-runtime-evidence");
		runtime.put("runtimeId", RUNTIME_ID);
		runtime.put("applicationRuntimeSha256", applicationRuntimeSha256);
		runtime.put("definitionCatalogId", CATALOG_ID);
		runtime.put("definitionCatalogSha256", catalogSha256);
		Map<String,Object> authoring = new LinkedHashMap<String,Object>();
		authoring.put("initialLayer", Long.valueOf(INITIAL_LEVEL));
		authoring.put("initialX", Long.valueOf(INITIAL_X));
		authoring.put("initialY", Long.valueOf(INITIAL_Y));
		authoring.put("createFromVoid", Boolean.TRUE);
		runtime.put("authoring", authoring);
		writeJson(runtimePath, runtime);

		WorldBuilderGenericLayeredPackage layered = generatePackage(
			projectRoot, projectRoot.resolve(PACKAGE_PATH), definitions);
		Path descriptorPath = projectRoot.resolve(DESCRIPTOR_PATH);
		Files.createDirectories(descriptorPath.getParent());
		Map<String,Object> descriptor = new LinkedHashMap<String,Object>();
		descriptor.put("schemaVersion", Long.valueOf(1L));
		descriptor.put("manifestType", "world-builder-empty-world");
		descriptor.put("generatorId", GENERATOR_ID);
		descriptor.put("coordinateModel", "signed-layered-v1");
		Map<String,Object> initial = new LinkedHashMap<String,Object>();
		initial.put("level", Long.valueOf(INITIAL_LEVEL));
		initial.put("x", Long.valueOf(INITIAL_X));
		initial.put("y", Long.valueOf(INITIAL_Y));
		descriptor.put("initialLocation", initial);
		Map<String,Object> catalogIdentity = new LinkedHashMap<String,Object>();
		catalogIdentity.put("catalogId", CATALOG_ID);
		catalogIdentity.put("sha256", catalogSha256);
		descriptor.put("catalog", catalogIdentity);
		Map<String,Object> runtimeIdentity = new LinkedHashMap<String,Object>();
		runtimeIdentity.put("runtimeId", RUNTIME_ID);
		runtimeIdentity.put("sha256", WorldBuilderHashes.sha256(runtimePath));
		descriptor.put("runtime", runtimeIdentity);
		descriptor.put("packageFingerprintSha256", layered.fingerprintSha256);
		writeJson(descriptorPath, descriptor);
		return new Result(layered.fingerprintSha256,
			WorldBuilderHashes.sha256(descriptorPath), Files.size(descriptorPath),
			catalogSha256, Files.size(catalogPath),
			WorldBuilderHashes.sha256(runtimePath), Files.size(runtimePath));
	}

	private static WorldBuilderGenericLayeredPackage generatePackage(
		Path projectRoot, Path packageRoot,
		WorldBuilderCompatibilityEvidence.DefinitionCatalog definitions)
		throws IOException, WorldBuilderContractException {
		String terrainRelative = terrainPath(
			INITIAL_LEVEL, INITIAL_SECTOR_X, INITIAL_SECTOR_Y);
		Path terrain = packageRoot.resolve(terrainRelative);
		Path placements = packageRoot.resolve("placements/global/lp0.json");
		Files.createDirectories(terrain.getParent());
		Files.createDirectories(placements.getParent());
		Files.write(terrain, WorldBuilderCanonicalVoidTerrain.sectorWithVisibleFloorPatch(
			INITIAL_LOCAL_X, INITIAL_LOCAL_Y));

		Map<String,Object> placement = new LinkedHashMap<String,Object>();
		placement.put("schemaVersion", Long.valueOf(3L));
		placement.put("encoding", "layered-world-placements-v3");
		placement.put("worldSpace", WORLD_SPACE);
		placement.put("level", Long.valueOf(0L));
		placement.put("boundaries", new ArrayList<Object>());
		placement.put("groundItems", new ArrayList<Object>());
		placement.put("npcs", new ArrayList<Object>());
		placement.put("scenery", new ArrayList<Object>());
		writeJson(placements, placement);

		Map<String,Object> manifest = new LinkedHashMap<String,Object>();
		manifest.put("schemaVersion", Long.valueOf(1L));
		manifest.put("packageType", "layered-world");
		manifest.put("packageId", PACKAGE_ID);
		manifest.put("packageVersion", PACKAGE_VERSION);
		manifest.put("coordinateModel", "signed-layered-v1");
		Map<String,Object> storage = new LinkedHashMap<String,Object>();
		storage.put("presentationChunkSize", Long.valueOf(24L));
		storage.put("sectorSize", Long.valueOf(48L));
		manifest.put("storage", storage);
		ArrayList<Object> spaces = new ArrayList<Object>();
		Map<String,Object> space = new LinkedHashMap<String,Object>();
		space.put("id", WORLD_SPACE);
		space.put("kind", "static");
		spaces.add(space);
		manifest.put("worldSpaces", spaces);
		ArrayList<Object> levels = new ArrayList<Object>();
		Map<String,Object> level = new LinkedHashMap<String,Object>();
		level.put("level", Long.valueOf(INITIAL_LEVEL));
		level.put("name", "Empty Layer 0");
		level.put("role", "empty-origin");
		level.put("worldSpace", WORLD_SPACE);
		levels.add(level);
		manifest.put("levels", levels);
		ArrayList<Object> sectors = new ArrayList<Object>();
		Map<String,Object> sector = new LinkedHashMap<String,Object>();
		sector.put("encoding", "raw-layered-sector-v1");
		sector.put("level", Long.valueOf(INITIAL_LEVEL));
		sector.put("path", terrainRelative);
		sector.put("sectorX", Long.valueOf(INITIAL_SECTOR_X));
		sector.put("sectorY", Long.valueOf(INITIAL_SECTOR_Y));
		sector.put("sha256", WorldBuilderHashes.sha256(terrain));
		sector.put("worldSpace", WORLD_SPACE);
		sectors.add(sector);
		manifest.put("terrainSectors", sectors);
		ArrayList<Object> sets = new ArrayList<Object>();
		Map<String,Object> set = new LinkedHashMap<String,Object>();
		set.put("encoding", "layered-world-placements-v3");
		set.put("id", "empty-level-0");
		set.put("level", Long.valueOf(0L));
		set.put("path", "placements/global/lp0.json");
		set.put("sha256", WorldBuilderHashes.sha256(placements));
		set.put("worldSpace", WORLD_SPACE);
		sets.add(set);
		manifest.put("placementSets", sets);
		writeJson(packageRoot.resolve("manifest.json"), manifest);
		return WorldBuilderGenericLayeredPackage.inspect(
			WorldBuilderReadOnlyTarget.open(projectRoot), PACKAGE_PATH,
			"standalone-empty", definitions);
	}

	static WorldBuilderGenericLayeredPackage bindInitialLocation(
		WorldBuilderReadOnlyTarget target,
		WorldBuilderGenericLayeredPackage layered)
		throws WorldBuilderContractException {
		Map<String,Object> descriptor = target.readObject(DESCRIPTOR_PATH);
		if (!GENERATOR_ID.equals(descriptor.get("generatorId"))) {
			throw problem(DESCRIPTOR_PATH,
				"Standalone generator identity is unsupported.",
				"Restore a project created by the supported empty-world generator.");
		}
		Map<String,Object> initial = object(
			descriptor.get("initialLocation"), DESCRIPTOR_PATH, "initialLocation");
		int level = signedInteger(initial, "level", DESCRIPTOR_PATH);
		int x = signedInteger(initial, "x", DESCRIPTOR_PATH);
		int y = signedInteger(initial, "y", DESCRIPTOR_PATH);

		Map<String,Object> runtime = target.readObject(RUNTIME_PATH);
		if (!RUNTIME_ID.equals(runtime.get("runtimeId"))) {
			throw problem(RUNTIME_PATH,
				"Standalone runtime evidence identity is unsupported.",
				"Restore the complete immutable standalone source evidence.");
		}
		WorldBuilderCompatibilityEvidence.DefinitionCatalog definitions =
			WorldBuilderCompatibilityEvidence.DefinitionCatalog.read(target, CATALOG_PATH);
		if (!(CATALOG_ID.equals(definitions.catalogId)
			|| LEGACY_CATALOG_ID.equals(definitions.catalogId))) {
			throw problem(CATALOG_PATH,
				"Standalone definition catalog identity is unsupported.",
				"Keep the project's original supported catalog; do not replace it silently.");
		}
		WorldBuilderReadOnlyTarget.FileState catalogState =
			target.requiredState("default-definition-catalog", CATALOG_PATH);
		Map<String,Object> descriptorCatalog = object(
			descriptor.get("catalog"), DESCRIPTOR_PATH, "catalog");
		Map<String,Object> descriptorRuntime = object(
			descriptor.get("runtime"), DESCRIPTOR_PATH, "runtime");
		if (!definitions.catalogId.equals(descriptorCatalog.get("catalogId"))
			|| !catalogState.sha256.equals(descriptorCatalog.get("sha256"))
			|| !RUNTIME_ID.equals(descriptorRuntime.get("runtimeId"))
			|| !target.requiredState("default-runtime-evidence", RUNTIME_PATH)
				.sha256.equals(descriptorRuntime.get("sha256"))
			|| !definitions.catalogId.equals(runtime.get("definitionCatalogId"))
			|| !catalogState.sha256.equals(runtime.get("definitionCatalogSha256"))) {
			throw problem(DESCRIPTOR_PATH,
				"Standalone catalog/runtime evidence identities disagree.",
				"Restore the complete immutable standalone source evidence.");
		}
		Map<String,Object> authoring = object(
			runtime.get("authoring"), RUNTIME_PATH, "authoring");
		if (signedInteger(authoring, "initialLayer", RUNTIME_PATH) != level
			|| signedInteger(authoring, "initialX", RUNTIME_PATH) != x
			|| signedInteger(authoring, "initialY", RUNTIME_PATH) != y
			|| !Boolean.TRUE.equals(authoring.get("createFromVoid"))) {
			throw problem(RUNTIME_PATH,
				"Standalone descriptor and authoring start metadata disagree.",
				"Restore the complete immutable standalone source evidence.");
		}
		return layered.withInitialLocation(level, x, y, DESCRIPTOR_PATH);
	}

	private static String terrainPath(int level, int sectorX, int sectorY) {
		return "terrain/" + WORLD_SPACE
			+ "/l" + WorldBuilderLayeredPackage.signedToken(level)
			+ "/x" + WorldBuilderLayeredPackage.signedToken(sectorX)
			+ "-y" + WorldBuilderLayeredPackage.signedToken(sectorY) + ".raw";
	}

	@SuppressWarnings("unchecked")
	private static Map<String,Object> object(
		Object raw, String path, String label)
		throws WorldBuilderContractException {
		if (!(raw instanceof Map)) {
			throw problem(path, "Standalone " + label + " is not an object.",
				"Restore the complete immutable standalone source evidence.");
		}
		return (Map<String,Object>)raw;
	}

	private static int signedInteger(Map<String,Object> value, String key, String path)
		throws WorldBuilderContractException {
		Object raw = value.get(key);
		if (!(raw instanceof Long)
			|| ((Long)raw).longValue() < Integer.MIN_VALUE
			|| ((Long)raw).longValue() > Integer.MAX_VALUE) {
			throw problem(path,
				"Standalone initial-location field is not a signed integer: " + key + ".",
				"Restore the complete immutable standalone source evidence.");
		}
		return (int)((Long)raw).longValue();
	}

	private static WorldBuilderContractException problem(
		String path, String message, String nextStep) {
		return WorldBuilderReadOnlyTarget.problem(
			WorldBuilderErrorCodes.SOURCE_CORRUPT, path, message, nextStep);
	}

	private static void writeJson(Path path, Map<String,Object> value)
		throws IOException {
		Files.createDirectories(path.getParent());
		Files.write(path, WorldBuilderJsonDocuments.pretty(value)
			.getBytes(StandardCharsets.UTF_8));
	}

	static final class Result {
		final String packageFingerprintSha256;
		final String descriptorSha256;
		final long descriptorSize;
		final String catalogSha256;
		final long catalogSize;
		final String runtimeEvidenceSha256;
		final long runtimeEvidenceSize;

		Result(String packageFingerprintSha256, String descriptorSha256,
			long descriptorSize, String catalogSha256, long catalogSize,
			String runtimeEvidenceSha256, long runtimeEvidenceSize) {
			this.packageFingerprintSha256 = packageFingerprintSha256;
			this.descriptorSha256 = descriptorSha256;
			this.descriptorSize = descriptorSize;
			this.catalogSha256 = catalogSha256;
			this.catalogSize = catalogSize;
			this.runtimeEvidenceSha256 = runtimeEvidenceSha256;
			this.runtimeEvidenceSize = runtimeEvidenceSize;
		}
	}
}
