package com.openrsc.worldbuilder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Packaging-only selected catalog projection. It never executes provider scripts or changes an existing installation. */
final class WorldBuilderCurrentBaseCatalogExport {
	private static final String OP = "export-current-base-catalog";
	private static final String CATALOG = "current-platform";
	private static final String IDENTITY = CATALOG + "/composition-identity.json";
	private WorldBuilderCurrentBaseCatalogExport() { }

	static Map<String,Object> export(Path requestedCatalog, Path requestedIdentity, Path requestedDestination)
		throws IOException, WorldBuilderContractException {
		WorldBuilderReadOnlyTarget catalog = WorldBuilderReadOnlyTarget.open(requestedCatalog);
		WorldBuilderReadOnlyTarget source = WorldBuilderReadOnlyTarget.open(catalog.root.getParent());
		if (!catalog.root.equals(source.root.resolve(CATALOG))) throw refused("Selected catalog must retain its current-platform source namespace.");
		WorldBuilderProviderCatalog.Composition composition = WorldBuilderProviderCatalog.resolve(catalog.root, requestedIdentity);
		if (!composition.installable || !"current-base-v1".equals(composition.identity.get("variantId")) || !composition.moduleIds.isEmpty())
			throw refused("This packaging projection selects only installable Current Base without optional modules.");
		Path identity = requestedIdentity.toAbsolutePath().normalize();
		WorldBuilderReadOnlyTarget identityRoot = WorldBuilderReadOnlyTarget.open(identity.getParent());
		identity = identityRoot.requiredFile(identity.getFileName().toString());
		Path destination = requestedDestination.toAbsolutePath().normalize();
		if (!destination.equals(requestedDestination) || destination.getParent() == null
			|| !destination.getParent().equals(destination.getParent().toRealPath())
			|| destination.startsWith(source.root) || source.root.startsWith(destination)
			|| identity.startsWith(destination) || Files.exists(destination, LinkOption.NOFOLLOW_LINKS))
			throw refused("Catalog export requires a new canonical directory disjoint from the source provider and identity.");

		Map<String,File> files = new TreeMap<String,File>();
		Map<String,WorldBuilderProviderCatalog.Artifact> manifests = new LinkedHashMap<String,WorldBuilderProviderCatalog.Artifact>();
		for (WorldBuilderProviderCatalog.Artifact artifact : composition.artifacts) {
			File file = new File(source, artifact.sourcePath);
			if (!file.hash.equals(artifact.inventory.get("sha256")) || !Long.valueOf(file.size).equals(artifact.inventory.get("size"))
				|| !file.mode.equals(artifact.inventory.get("mode"))) throw refused("Selected artifact changed before packaging.");
			add(files, artifact.sourcePath, file);
			String role = (String)artifact.inventory.get("role");
			if (Arrays.asList("platform-manifest", "variant-manifest", "bundle-spec").contains(role)
				&& manifests.put(role, artifact) != null) throw refused("Selected catalog repeats a required manifest role.");
		}
		for (String role : Arrays.asList("platform-manifest", "variant-manifest", "bundle-spec")) {
			WorldBuilderProviderCatalog.Artifact artifact = manifests.get(role);
			if (artifact == null) throw refused("Selected Base lacks a required catalog manifest.");
			String directory = "platform-manifest".equals(role) ? "platform" : "variant-manifest".equals(role) ? "variants" : "bundle-specs";
			String field = "platform-manifest".equals(role) ? "platformManifestHash" : "variant-manifest".equals(role) ? "variantManifestHash" : "bundleSpecHash";
			String prefix = CATALOG + "/" + directory + "/";
			if (!artifact.sourcePath.startsWith(prefix) || !artifact.sourcePath.endsWith(".json")
				|| artifact.sourcePath.substring(prefix.length()).contains("/")) throw refused("Selected manifest is outside its resolver namespace.");
			Map<String,Object> document = source.readObject(artifact.sourcePath);
			if (!WorldBuilderHashes.sha256(WorldBuilderJsonDocuments.canonical(document).getBytes(java.nio.charset.StandardCharsets.UTF_8))
				.equals(composition.identity.get(field))) throw refused("Selected artifact manifest differs from the resolved catalog.");
			if ("platform-manifest".equals(role)) {
				for (Object raw : (List<?>)document.get("schemaContracts")) {
					Map<?,?> schema = (Map<?,?>)raw;
					String relative = CATALOG + "/" + (String)schema.get("relativePath");
					File file = new File(source, relative);
					if (!file.hash.equals(schema.get("sha256"))) throw refused("Selected platform schema changed before packaging.");
					add(files, relative, file);
				}
			}
		}
		add(files, IDENTITY, new File(identityRoot, identity.getFileName().toString()));
		long total = 0;
		for (File file : files.values()) {
			total = Math.addExact(total, file.size);
			if (total > WorldBuilderContractLimits.MAX_INVENTORY_TOTAL_BYTES) throw refused("Selected packaging projection is too large.");
			file.verify();
		}
		// All input authority and complete destination paths are settled before the first write.
		Files.createDirectory(destination);
		for (Map.Entry<String,File> entry : files.entrySet()) {
			Path output = destination.resolve(entry.getKey());
			Files.createDirectories(output.getParent());
			entry.getValue().verify();
			Files.copy(entry.getValue().path, output, StandardCopyOption.COPY_ATTRIBUTES);
			entry.getValue().verify(output);
		}
		WorldBuilderProviderCatalog.Composition relocated = WorldBuilderProviderCatalog.resolve(destination.resolve(CATALOG), destination.resolve(IDENTITY));
		WorldBuilderProviderCatalog.Composition fresh = WorldBuilderProviderCatalog.resolve(catalog.root, identity);
		if (!WorldBuilderJsonDocuments.canonical(composition.identity).equals(WorldBuilderJsonDocuments.canonical(relocated.identity))
			|| !WorldBuilderJsonDocuments.canonical(composition.identity).equals(WorldBuilderJsonDocuments.canonical(fresh.identity)))
			throw refused("Selected composition changed during packaging.");
		List<Object> inventory = new ArrayList<Object>();
		for (Map.Entry<String,File> entry : files.entrySet()) {
			entry.getValue().verify(); entry.getValue().verify(destination.resolve(entry.getKey()));
			Map<String,Object> record = new LinkedHashMap<String,Object>();
			record.put("relativePath", entry.getKey()); record.put("size", Long.valueOf(entry.getValue().size));
			record.put("sha256", entry.getValue().hash); record.put("mode", entry.getValue().mode);
			inventory.add(record);
		}
		verifyClosure(destination, files);
		WorldBuilderAdaptiveDurability.forceTree(destination);
		WorldBuilderAdaptiveDurability.forceDirectory(destination.getParent());
		Map<String,Object> result = new LinkedHashMap<String,Object>();
		result.put("schemaVersion", Long.valueOf(1)); result.put("manifestType", "world-builder-current-base-catalog-export");
		result.put("catalogRelativePath", CATALOG); result.put("compositionIdentityRelativePath", IDENTITY);
		result.put("composition", composition.identity); result.put("files", inventory);
		WorldBuilderAdaptiveExporter.bindFingerprint(result, "fingerprintSha256");
		return result;
	}

	private static void verifyClosure(Path destination, Map<String,File> files) throws IOException, WorldBuilderContractException {
		java.util.Set<String> expectedDirectories = new java.util.HashSet<String>();
		expectedDirectories.add("");
		for (String relative : files.keySet()) {
			for (Path parent = java.nio.file.Paths.get(relative).getParent(); parent != null; parent = parent.getParent())
				expectedDirectories.add(parent.toString().replace('\\', '/'));
		}
		java.util.Set<String> actual = new java.util.HashSet<String>();
		WorldBuilderReadOnlyTarget output = WorldBuilderReadOnlyTarget.open(destination);
		try (java.util.stream.Stream<Path> paths = Files.walk(destination)) {
			int count = 0;
			for (Path path : (Iterable<Path>)paths::iterator) {
				if (++count > WorldBuilderContractLimits.MAX_INVENTORY_ENTRIES * 2 || Files.isSymbolicLink(path))
					throw refused("Packaged catalog tree is linked or unbounded.");
				String relative = destination.relativize(path).toString().replace('\\', '/');
				if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
					if (!expectedDirectories.contains(relative)) throw refused("Packaged catalog has an unbound directory.");
				} else {
					output.requiredFile(relative); actual.add(relative);
				}
			}
		}
		if (!actual.equals(files.keySet())) throw refused("Packaged catalog file closure changed.");
	}

	private static void add(Map<String,File> files, String relative, File file) throws WorldBuilderContractException {
		WorldBuilderPortablePath.require(relative, OP);
		if (files.size() >= WorldBuilderContractLimits.MAX_INVENTORY_ENTRIES) throw refused("Selected catalog projection is unbounded.");
		for (String existing : files.keySet()) {
			if (existing.equals(relative)) {
				File previous = files.get(existing);
				if (!previous.hash.equals(file.hash) || previous.size != file.size || !previous.mode.equals(file.mode)) throw refused("Selected catalog has conflicting file authority.");
				return;
			}
			String a = existing.toLowerCase(java.util.Locale.ROOT), b = relative.toLowerCase(java.util.Locale.ROOT);
			if (a.equals(b) || a.startsWith(b + "/") || b.startsWith(a + "/")) throw refused("Selected catalog paths collide.");
		}
		files.put(relative, file);
	}

	private static final class File {
		final Path path;
		final long size;
		final String hash, mode;
		File(WorldBuilderReadOnlyTarget source, String relative) throws IOException, WorldBuilderContractException {
			path = source.requiredFile(relative);
			WorldBuilderReadOnlyTarget.FileState state = source.requiredState("selected-catalog-file", relative);
			size = state.size; hash = state.sha256; mode = mode(path);
			if (size < 0 || size > WorldBuilderContractLimits.MAX_INVENTORY_FILE_BYTES) throw refused("Selected catalog file is too large.");
		}
		void verify() throws IOException, WorldBuilderContractException { verify(path); }
		void verify(Path candidate) throws IOException, WorldBuilderContractException {
			WorldBuilderReadOnlyTarget.open(candidate.getParent()).requiredFile(candidate.getFileName().toString());
			if (Files.size(candidate) != size || !WorldBuilderHashes.sha256(candidate).equals(hash) || !mode(candidate).equals(mode))
				throw refused("Selected catalog bytes or mode changed while copying.");
		}
		private static String mode(Path path) throws IOException, WorldBuilderContractException {
			int raw = ((Number)Files.getAttribute(path, "unix:mode", LinkOption.NOFOLLOW_LINKS)).intValue() & 07777;
			if ((raw & 07000) != 0) throw refused("Selected catalog file has unsupported special mode bits.");
			return String.format("%04o", Integer.valueOf(raw));
		}
	}
	private static WorldBuilderContractException refused(String message) {
		return new WorldBuilderContractException(WorldBuilderErrorCodes.CAPABILITY_MISMATCH, OP, message);
	}
}
