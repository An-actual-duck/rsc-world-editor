package com.openrsc.worldbuilder;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Read-only comparison of one exact packed landscape with the selected
 * layered terrain authority.  This deliberately ignores placement and
 * definition conversion: it answers only whether applying the packed terrain
 * would add, preserve, or replace selected layered sectors.
 */
final class WorldBuilderLegacyLandscapeAssessment {
	private static final int MAX_SECTORS = 65536;
	private static final String OPERATION = "assess-legacy-landscape";

	enum Status {
		EQUIVALENT("equivalent"),
		CONFLICTING("conflicting"),
		INCOMPLETE("incomplete");

		final String id;

		Status(String id) {
			this.id = id;
		}
	}

	final Status status;
	final int legacySectorCount;
	final int equivalentSectorCount;
	final int conflictingSectorCount;
	final int missingSectorCount;
	final int layeredOnlySectorCount;
	final List<String> conflictingSectors;
	final List<String> missingSectors;
	final String selectedPackageFingerprintSha256;
	final String selectedPackageManifestSha256;

	private WorldBuilderLegacyLandscapeAssessment(
		Status status, int legacySectorCount, int equivalentSectorCount,
		int conflictingSectorCount, int missingSectorCount,
		int layeredOnlySectorCount, List<String> conflictingSectors,
		List<String> missingSectors, String selectedPackageFingerprintSha256,
		String selectedPackageManifestSha256) {
		this.status = status;
		this.legacySectorCount = legacySectorCount;
		this.equivalentSectorCount = equivalentSectorCount;
		this.conflictingSectorCount = conflictingSectorCount;
		this.missingSectorCount = missingSectorCount;
		this.layeredOnlySectorCount = layeredOnlySectorCount;
		this.conflictingSectors = Collections.unmodifiableList(
			new ArrayList<String>(conflictingSectors));
		this.missingSectors = Collections.unmodifiableList(
			new ArrayList<String>(missingSectors));
		this.selectedPackageFingerprintSha256 =
			selectedPackageFingerprintSha256;
		this.selectedPackageManifestSha256 = selectedPackageManifestSha256;
	}

	static WorldBuilderLegacyLandscapeAssessment inspect(
		Path targetRoot, WorldBuilderAdaptiveDiscoveryReport selectedReport,
		WorldBuilderAdaptiveDiscoveryReport legacyReport)
		throws IOException, WorldBuilderContractException {
		Map<String,Object> selected = parse(selectedReport, "selected target");
		Map<String,Object> legacy = parse(legacyReport, "legacy landscape");
		WorldBuilderReadOnlyTarget target =
			WorldBuilderReadOnlyTarget.open(targetRoot);
		WorldBuilderTargetCapability capability =
			WorldBuilderTargetCapability.read(target);
		String selectedRole = WorldBuilderAdaptiveExporter.string(
			WorldBuilderAdaptiveExporter.object(
				selected.get("selectedConfiguration"), "selectedConfiguration"),
			"role");
		WorldBuilderAdaptiveConfiguration configuration =
			WorldBuilderAdaptiveConfiguration.select(
				target, capability, selectedRole).selected;
		if (!"layered".equals(configuration.representation)) {
			throw problem("The selected target is not a layered terrain authority.",
				"Select one compatible layered server map and retry detection.");
		}
		WorldBuilderCompatibilityEvidence compatibility =
			WorldBuilderCompatibilityEvidence.inspect(
				target, capability, configuration);
		WorldBuilderGenericLayeredPackage packageValue =
			WorldBuilderGenericLayeredPackage.inspect(
				target, configuration.serverMapRelativePath,
				"legacy-assessment", compatibility.definitions);

		Map<String,LayeredSector> layered = layeredTerrain(
			target, configuration.serverMapRelativePath);
		String legacyRelative = legacyServerTerrain(legacy);
		Path legacyArchive = target.requiredFile(legacyRelative);
		Map<String,byte[]> packed = packedTerrain(legacyArchive, legacyRelative);

		List<String> conflicts = new ArrayList<String>();
		List<String> missing = new ArrayList<String>();
		int equivalent = 0;
		for (Map.Entry<String,byte[]> entry : packed.entrySet()) {
			LayeredSector selectedSector = layered.get(entry.getKey());
			if (selectedSector == null) {
				missing.add(entry.getKey());
				continue;
			}
			byte[] selectedBytes = Files.readAllBytes(
				target.requiredFile(selectedSector.relativePath));
			if (WorldBuilderRawLayeredTerrainCodec.V1_ENCODING.equals(
					selectedSector.encoding)) {
				selectedBytes = WorldBuilderRawLayeredTerrainCodec.promoteV1(
					selectedBytes);
			}
			if (Arrays.equals(entry.getValue(), selectedBytes)) equivalent++;
			else conflicts.add(entry.getKey());
		}
		Collections.sort(conflicts);
		Collections.sort(missing);
		Status status = !missing.isEmpty() ? Status.INCOMPLETE
			: conflicts.isEmpty() ? Status.EQUIVALENT : Status.CONFLICTING;
		return new WorldBuilderLegacyLandscapeAssessment(
			status, packed.size(), equivalent, conflicts.size(), missing.size(),
			Math.max(0, layered.size() - packed.size() + missing.size()),
			conflicts, missing, packageValue.fingerprintSha256,
			packageValue.manifestSha256);
	}

	Map<String,Object> document() {
		Map<String,Object> value = new LinkedHashMap<String,Object>();
		value.put("status", status.id);
		value.put("legacySectorCount", Long.valueOf(legacySectorCount));
		value.put("equivalentSectorCount", Long.valueOf(equivalentSectorCount));
		value.put("conflictingSectorCount", Long.valueOf(conflictingSectorCount));
		value.put("missingSectorCount", Long.valueOf(missingSectorCount));
		value.put("layeredOnlySectorCount", Long.valueOf(layeredOnlySectorCount));
		value.put("conflictingSectors", new ArrayList<String>(conflictingSectors));
		value.put("missingSectors", new ArrayList<String>(missingSectors));
		value.put("selectedPackageFingerprintSha256",
			selectedPackageFingerprintSha256);
		value.put("selectedPackageManifestSha256",
			selectedPackageManifestSha256);
		return value;
	}

	String summary() {
		return legacySectorCount + " legacy sectors: " + equivalentSectorCount
			+ " equivalent, " + conflictingSectorCount + " conflicting, "
			+ missingSectorCount + " absent from the layered map; "
			+ layeredOnlySectorCount + " layered-only sectors.";
	}

	private static Map<String,Object> parse(
		WorldBuilderAdaptiveDiscoveryReport report, String label)
		throws WorldBuilderContractException {
		if (report == null) throw problem("The " + label + " report is missing.",
			"Run both read-only detection passes again.");
		try {
			return WorldBuilderJsonDocuments.readObject(
				report.toJson().getBytes(java.nio.charset.StandardCharsets.UTF_8),
				label + " discovery report");
		} catch (WorldBuilderDiscoveryException malformed) {
			throw problem("The " + label + " report is malformed.",
				"Run both read-only detection passes again.");
		}
	}

	private static String legacyServerTerrain(Map<String,Object> legacy)
		throws WorldBuilderContractException {
		List<WorldBuilderBoundedInventory.Record> files =
			WorldBuilderBoundedInventory.read(
				legacy.get("files"), OPERATION, 1, false);
		for (WorldBuilderBoundedInventory.Record file : files) {
			if ("server-terrain".equals(file.role) && file.present) {
				return file.relativePath;
			}
		}
		throw problem("Legacy detection did not bind a server terrain archive.",
			"Detect both exact Custom_Landscape copies again.");
	}

	private static Map<String,LayeredSector> layeredTerrain(
		WorldBuilderReadOnlyTarget target, String packageRelative)
		throws WorldBuilderContractException {
		Map<String,Object> manifest = target.readObject(
			packageRelative + "/manifest.json");
		List<?> records = WorldBuilderAdaptiveExporter.array(
			manifest.get("terrainSectors"), "terrainSectors");
		Map<String,LayeredSector> result =
			new LinkedHashMap<String,LayeredSector>();
		for (Object raw : records) {
			Map<String,Object> record =
				WorldBuilderAdaptiveExporter.object(raw, "terrain sector");
			String worldSpace = WorldBuilderAdaptiveExporter.string(
				record, "worldSpace");
			int level = Math.toIntExact(
				WorldBuilderAdaptiveExporter.integer(record, "level"));
			int sectorX = Math.toIntExact(
				WorldBuilderAdaptiveExporter.integer(record, "sectorX"));
			int sectorY = Math.toIntExact(
				WorldBuilderAdaptiveExporter.integer(record, "sectorY"));
			String key = key(worldSpace, level, sectorX, sectorY);
			String path = packageRelative + "/"
				+ WorldBuilderAdaptiveExporter.string(record, "path");
			String encoding = WorldBuilderAdaptiveExporter.string(record, "encoding");
			if (result.put(key, new LayeredSector(path, encoding)) != null) {
				throw problem("The selected layered manifest duplicates sector "
					+ key + ".", "Repair the layered package before detection.");
			}
		}
		return result;
	}

	private static Map<String,byte[]> packedTerrain(
		Path archive, String relative)
		throws IOException, WorldBuilderContractException {
		Map<String,byte[]> result = new LinkedHashMap<String,byte[]>();
		Set<String> names = new HashSet<String>();
		try (ZipFile zip = new ZipFile(archive.toFile())) {
			Enumeration<? extends ZipEntry> entries = zip.entries();
			while (entries.hasMoreElements()) {
				ZipEntry entry = entries.nextElement();
				if (entry.isDirectory() || result.size() >= MAX_SECTORS
					|| !names.add(entry.getName())) {
					throw problem("Legacy terrain contains a duplicate, directory, or "
						+ "too many entries.", "Restore the exact legacy archive.");
				}
				WorldBuilderPackedCoordinateCodec.Sector coordinate =
					WorldBuilderPackedCoordinateCodec.decodeTerrainEntry(
						entry.getName());
				byte[] legacy = readExact(zip, entry, relative);
				byte[] converted = WorldBuilderPackedTerrainCodec.toLayered(legacy);
				String key = key("global", coordinate.level,
					coordinate.sectorX, coordinate.sectorY);
				if (result.put(key, converted) != null) {
					throw problem("Legacy terrain duplicates normalized sector " + key
						+ ".", "Restore the exact legacy archive.");
				}
			}
		}
		if (result.isEmpty()) throw problem("Legacy terrain contains no sectors.",
			"Restore the exact legacy archive.");
		return result;
	}

	private static byte[] readExact(
		ZipFile zip, ZipEntry entry, String relative)
		throws IOException, WorldBuilderContractException {
		ByteArrayOutputStream output = new ByteArrayOutputStream(
			WorldBuilderPackedTerrainCodec.BYTE_COUNT);
		try (InputStream input = zip.getInputStream(entry)) {
			byte[] buffer = new byte[8192];
			int read;
			while ((read = input.read(buffer)) >= 0) {
				if (read == 0) continue;
				if (output.size() + read
					> WorldBuilderPackedTerrainCodec.BYTE_COUNT) {
					throw problem("Legacy terrain entry is too large in " + relative
						+ ": " + entry.getName() + ".",
						"Restore the exact legacy archive.");
				}
				output.write(buffer, 0, read);
			}
		}
		if (output.size() != WorldBuilderPackedTerrainCodec.BYTE_COUNT) {
			throw problem("Legacy terrain entry has the wrong size in " + relative
				+ ": " + entry.getName() + ".",
				"Restore the exact legacy archive.");
		}
		return output.toByteArray();
	}

	private static String key(
		String worldSpace, int level, int sectorX, int sectorY) {
		return worldSpace + ":" + level + ":" + sectorX + ":" + sectorY;
	}

	private static WorldBuilderContractException problem(
		String message, String nextStep) {
		return new WorldBuilderContractException(
			WorldBuilderErrorCodes.CONVERSION_BLOCKED, OPERATION, "", false,
			message, nextStep);
	}

	private static final class LayeredSector {
		final String relativePath;
		final String encoding;

		LayeredSector(String relativePath, String encoding) {
			this.relativePath = relativePath;
			this.encoding = encoding;
		}
	}
}
