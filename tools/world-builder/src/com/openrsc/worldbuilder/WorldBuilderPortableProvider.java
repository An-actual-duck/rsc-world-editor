package com.openrsc.worldbuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Read-only discovery and local publication for neutral item-visual providers.
 * Target content is data only: this class never loads or executes target code.
 */
final class WorldBuilderPortableProvider {
	static final String PACKAGE_DIRECTORY = "world-builder-provider";
	static final String MAPPING_FILE = "item-visuals.json";
	static final String ASSETS_DIRECTORY = "assets";
	static final String AUTHENTIC_FILE = "Authentic_Sprites.orsc";
	static final String CUSTOM_FILE = "Custom_Sprites.osar";
	static final String SPRITEPACKS_DIRECTORY = "spritepacks";
	static final String EXTERNAL_ITEMS_DIRECTORY = "external-items";
	static final String DEFINITIONS_DIRECTORY = "definitions";
	static final String PROVIDERS_DIRECTORY = "providers";
	static final String CATALOG_FILE = "catalog.json";

	private static final long MAX_PROVIDER_FILE_BYTES = 512L * 1024L * 1024L;
	private static final long MAX_PROVIDER_TOTAL_BYTES = 4L * 1024L * 1024L * 1024L;
	private static final int MAX_PROVIDER_FILES = 32768;
	private static final List<String> VIDEO_ROOTS = Collections.unmodifiableList(Arrays.asList(
		"Cache/video", "client/Cache/video", "Client_Base/Cache/video",
		"builder-runtime/client/Cache/video", "server/conf/server/data",
		"server/data"));
	private static final List<String> DEFINITION_ROOTS = Collections.unmodifiableList(Arrays.asList(
		"server/conf/server/defs", "server/data/definitions", "server/data/defs",
		"conf/server/defs", "data/definitions"));

	Discovery discover(Path requestedSource, Path installation) throws IOException {
		Path source = requireDirectory(requestedSource, "provider discovery source");
		Path explicit = safeFile(source.resolve(MAPPING_FILE))
			? source : source.resolve(PACKAGE_DIRECTORY);
		if (safeDirectory(explicit)) {
			Candidate candidate = explicitCandidate(explicit);
			return new Discovery(Status.EXPLICIT, source,
				Collections.singletonList(candidate), candidate,
				"Explicit world-builder-provider package found and selected.");
		}

		List<Candidate> local = localCandidates(source, installation);
		List<Candidate> legacy = legacyCandidates(source);
		if (local.size() == 1) {
			Candidate selected = local.get(0);
			return new Discovery(Status.LOCAL, source, local, selected,
				"A previously imported local provider was selected for this source.");
		}
		if (local.size() > 1) {
			return new Discovery(Status.AMBIGUOUS, source, local, null,
				"More than one local provider matches this source. Choose one explicitly.");
		}
		if (legacy.size() == 1) {
			Candidate candidate = legacy.get(0);
			return new Discovery(Status.RECOGNIZED, source, legacy, candidate,
				"One recognized neutral OpenRSC content layout was found.");
		}
		if (legacy.size() > 1) {
			return new Discovery(Status.AMBIGUOUS, source, legacy, null,
				"More than one recognized content layout was found. Choose the exact files in guided import.");
		}
		return new Discovery(Status.NONE, source, Collections.<Candidate>emptyList(),
			null, "No complete provider layout was found. Use guided import to select the content files.");
	}

	Provider publishGuided(Path requestedInstallation, Path requestedSource,
		GuidedSelection selection) throws IOException, WorldBuilderDiscoveryException {
		Path installation = requireDirectory(requestedInstallation,
			"World Builder installation");
		Path source = requireDirectory(requestedSource, "guided-import source");
		selection = selection.normalized();
		selection.requireUsable();

		Path providers = installation.resolve(PROVIDERS_DIRECTORY);
		requireOrCreateDirectory(providers, "local provider directory");
		Path stage = providers.resolve(".provider-" + UUID.randomUUID().toString());
		Files.createDirectory(stage);
		boolean published = false;
		try {
			Path packageStage = stage;
			Path assets = packageStage.resolve(ASSETS_DIRECTORY);
			Files.createDirectories(assets);
			if (selection.itemVisuals != null) {
				copyRegular(selection.itemVisuals, packageStage.resolve(MAPPING_FILE));
			} else {
				writeUnresolvedMapping(selection.definitions,
					packageStage.resolve(MAPPING_FILE));
			}
			if (selection.authenticArchive != null) copyRegular(selection.authenticArchive,
				assets.resolve(AUTHENTIC_FILE));
			if (selection.customArchive != null) copyRegular(selection.customArchive,
				assets.resolve(CUSTOM_FILE));
			if (selection.spritepacks != null) copyTree(selection.spritepacks,
				assets.resolve(SPRITEPACKS_DIRECTORY));
			if (selection.externalItems != null) copyTree(selection.externalItems,
				assets.resolve(EXTERNAL_ITEMS_DIRECTORY));
			if (selection.definitions != null) {
				if (safeFile(selection.definitions)) copyRegular(selection.definitions,
					assets.resolve(DEFINITIONS_DIRECTORY).resolve(
						selection.definitions.getFileName().toString()));
				else copyTree(selection.definitions, assets.resolve(DEFINITIONS_DIRECTORY));
			}

			List<FileRecord> files = inventory(packageStage);
			String fingerprint = providerFingerprint(files);
			String providerId = "provider-" + fingerprint.substring(0, 16);
			Path destination = providers.resolve(providerId);
			if (!Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
				WorldBuilderAdaptiveDurability.forceTree(packageStage);
				try {
					WorldBuilderAdaptiveAtomicFiles.moveNew(packageStage, destination,
						"portable-provider-import", providerId);
				} catch (WorldBuilderContractException publication) {
					throw new IOException(publication.getMessage(), publication);
				}
				WorldBuilderAdaptiveDurability.forceDirectory(providers);
			} else {
				List<FileRecord> existing = inventory(destination);
				if (!fingerprint.equals(providerFingerprint(existing))) {
					throw new IOException("Local provider ID collision was preserved: " + providerId);
				}
			}
			Provider provider = new Provider(providerId, destination,
				destination.resolve(MAPPING_FILE), fingerprint, files);
			updateCatalog(providers, source, provider);
			published = true;
			return provider;
		} finally {
			deleteOwnedTree(stage);
			if (!published) {
				// Any already-published content-addressed provider is safe and reusable;
				// the catalog remains the only source association authority.
			}
		}
	}

	private Candidate explicitCandidate(Path root) throws IOException {
		Path mapping = requireFile(root.resolve(MAPPING_FILE), "explicit provider mapping");
		Path assets = root.resolve(ASSETS_DIRECTORY);
		if (!safeDirectory(assets)) assets = null;
		return new Candidate("explicit-provider", "Explicit portable provider", root,
			mapping, assets == null ? null : childFile(assets, AUTHENTIC_FILE),
			assets == null ? null : childFile(assets, CUSTOM_FILE),
			assets == null ? null : childDirectory(assets, SPRITEPACKS_DIRECTORY),
			assets == null ? null : childDirectory(assets, EXTERNAL_ITEMS_DIRECTORY),
			assets == null ? null : childDirectory(assets, DEFINITIONS_DIRECTORY));
	}

	private List<Candidate> legacyCandidates(Path source) throws IOException {
		List<Candidate> result = new ArrayList<Candidate>();
		for (String relative : VIDEO_ROOTS) {
			Path video = source.resolve(relative);
			if (!safeDirectory(video)) continue;
			Path authentic = firstFile(video, "Authentic_Sprites.orsc", "authentic-sprites.orsc");
			Path custom = firstFile(video, "Custom_Sprites.osar", "custom-sprites.osar");
			Path spritepacks = childDirectory(video, SPRITEPACKS_DIRECTORY);
			Path external = firstDirectory(video, "external-items", "external_items", "items");
			if (authentic == null && custom == null && spritepacks == null && external == null) continue;
			List<Path> definitions = definitionRoots(source);
			if (definitions.isEmpty()) definitions = Collections.singletonList(null);
			Path mapping = firstFile(source.resolve(PACKAGE_DIRECTORY), MAPPING_FILE);
			for (int index = 0; index < definitions.size(); index++) {
				String profileId = "legacy-" + portableKey(relative)
					+ (definitions.size() == 1 ? "" : "-definitions-" + (index + 1));
				result.add(new Candidate(profileId,
					"Recognized OpenRSC layout: " + relative, video, mapping, authentic,
					custom, spritepacks, external, definitions.get(index)));
			}
		}
		Collections.sort(result);
		return result;
	}

	private List<Path> definitionRoots(Path source) throws IOException {
		List<Path> found = new ArrayList<Path>();
		for (String relative : DEFINITION_ROOTS) {
			Path candidate = source.resolve(relative);
			if (!safeDirectory(candidate)) continue;
			found.add(candidate.toRealPath());
		}
		Collections.sort(found, new Comparator<Path>() {
			@Override public int compare(Path left, Path right) {
				return left.toString().compareTo(right.toString());
			}
		});
		return found;
	}

	private List<Candidate> localCandidates(Path source, Path installation)
		throws IOException {
		if (installation == null) return Collections.emptyList();
		Path root;
		try {
			root = requireDirectory(installation, "World Builder installation");
		} catch (IOException unavailable) {
			return Collections.emptyList();
		}
		Path catalog = root.resolve(PROVIDERS_DIRECTORY).resolve(CATALOG_FILE);
		if (!safeFile(catalog) || Files.size(catalog) > WorldBuilderContractLimits.MAX_JSON_BYTES) {
			return Collections.emptyList();
		}
		try {
			Map<String,Object> document = WorldBuilderJsonDocuments.readObject(catalog);
			if (!Long.valueOf(1L).equals(document.get("schemaVersion"))
				|| !"world-builder-local-provider-catalog".equals(document.get("manifestType"))) {
				return Collections.emptyList();
			}
			String sourceId = sourceIdentity(source);
			Object raw = document.get("providers");
			if (!(raw instanceof List)) return Collections.emptyList();
			List<Candidate> result = new ArrayList<Candidate>();
			for (Object entry : (List<?>)raw) {
				if (!(entry instanceof Map)) continue;
				@SuppressWarnings("unchecked") Map<String,Object> value = (Map<String,Object>)entry;
				if (!sourceId.equals(value.get("sourceIdentitySha256"))) continue;
				Object relative = value.get("providerRelativePath");
				Object expectedFingerprint = value.get("providerFingerprintSha256");
				if (!(relative instanceof String) || !(expectedFingerprint instanceof String)) continue;
				Path provider = root.resolve(PROVIDERS_DIRECTORY).resolve((String)relative).normalize();
				if (!provider.getParent().equals(root.resolve(PROVIDERS_DIRECTORY))) continue;
				if (safeDirectory(provider)
					&& expectedFingerprint.equals(providerFingerprint(inventory(provider)))) {
					result.add(explicitCandidate(provider));
				}
			}
			Collections.sort(result);
			return result;
		} catch (WorldBuilderDiscoveryException malformed) {
			return Collections.emptyList();
		}
	}

	private void updateCatalog(Path providers, Path source, Provider provider)
		throws IOException, WorldBuilderDiscoveryException {
		Path catalog = providers.resolve(CATALOG_FILE);
		Map<String,Map<String,Object>> byProvider = new TreeMap<String,Map<String,Object>>();
		if (safeFile(catalog)) {
			Map<String,Object> existing = WorldBuilderJsonDocuments.readObject(catalog);
			Object raw = existing.get("providers");
			if (raw instanceof List) {
				for (Object entry : (List<?>)raw) {
					if (!(entry instanceof Map)) continue;
					@SuppressWarnings("unchecked") Map<String,Object> record = (Map<String,Object>)entry;
					Object id = record.get("providerId");
					Object sourceId = record.get("sourceIdentitySha256");
					if (id instanceof String && sourceId instanceof String) {
						byProvider.put((String)id + "\u0000" + (String)sourceId,
							new LinkedHashMap<String,Object>(record));
					}
				}
			}
		}
		Map<String,Object> record = new LinkedHashMap<String,Object>();
		record.put("providerId", provider.providerId);
		record.put("providerRelativePath", provider.providerId);
		record.put("providerFingerprintSha256", provider.fingerprintSha256);
		record.put("sourceIdentitySha256", sourceIdentity(source));
		byProvider.put(provider.providerId + "\u0000" + sourceIdentity(source), record);
		Map<String,Object> document = new LinkedHashMap<String,Object>();
		document.put("schemaVersion", Long.valueOf(1L));
		document.put("manifestType", "world-builder-local-provider-catalog");
		document.put("providers", new ArrayList<Object>(byProvider.values()));
		byte[] bytes = WorldBuilderJsonDocuments.pretty(document).getBytes(StandardCharsets.UTF_8);
		Path temporary = Files.createTempFile(providers, ".provider-catalog-", ".tmp");
		try {
			Files.write(temporary, bytes, StandardOpenOption.TRUNCATE_EXISTING);
			WorldBuilderAdaptiveDurability.forceFile(temporary);
			try {
				Files.move(temporary, catalog, StandardCopyOption.ATOMIC_MOVE,
					StandardCopyOption.REPLACE_EXISTING);
			} catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
				throw new IOException("Local filesystem cannot atomically publish the provider catalog.", unsupported);
			}
			WorldBuilderAdaptiveDurability.forceDirectory(providers);
		} finally {
			Files.deleteIfExists(temporary);
		}
	}

	private static String sourceIdentity(Path source) throws IOException {
		return WorldBuilderHashes.sha256(source.toRealPath().toString()
			.getBytes(StandardCharsets.UTF_8));
	}

	private static List<FileRecord> inventory(final Path root) throws IOException {
		final List<FileRecord> result = new ArrayList<FileRecord>();
		final long[] total = {0L};
		Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
			@Override public FileVisitResult preVisitDirectory(Path directory,
				BasicFileAttributes attributes) throws IOException {
				if (!attributes.isDirectory() || attributes.isSymbolicLink()) {
					throw new IOException("Provider contains an unsafe directory: " + directory);
				}
				return FileVisitResult.CONTINUE;
			}
			@Override public FileVisitResult visitFile(Path file,
				BasicFileAttributes attributes) throws IOException {
				if (!attributes.isRegularFile() || attributes.isSymbolicLink()) {
					throw new IOException("Provider contains an unsafe file: " + file);
				}
				if (attributes.size() > MAX_PROVIDER_FILE_BYTES
					|| total[0] > MAX_PROVIDER_TOTAL_BYTES - attributes.size()
					|| result.size() >= MAX_PROVIDER_FILES) {
					throw new IOException("Provider exceeds the bounded local import limits.");
				}
				total[0] += attributes.size();
				String relative = root.relativize(file).toString().replace('\\', '/');
				try {
					WorldBuilderPortablePath.require(relative, "portable-provider-import");
				} catch (WorldBuilderContractException invalid) {
					throw new IOException(invalid.getMessage(), invalid);
				}
				result.add(new FileRecord(relative, attributes.size(),
					WorldBuilderHashes.sha256(file)));
				return FileVisitResult.CONTINUE;
			}
		});
		Collections.sort(result);
		return result;
	}

	private static String providerFingerprint(List<FileRecord> files) {
		MessageDigest digest = WorldBuilderHashes.newDigest();
		WorldBuilderHashes.updateText(digest, "world-builder-local-provider-v1");
		for (FileRecord file : files) {
			WorldBuilderHashes.updateText(digest, file.relativePath);
			WorldBuilderHashes.updateText(digest, Long.toString(file.size));
			WorldBuilderHashes.updateText(digest, file.sha256);
		}
		return WorldBuilderHashes.hex(digest.digest());
	}

	private static void copyRegular(Path source, Path destination) throws IOException {
		Path safe = requireFile(source, "guided-import file");
		long size = Files.size(safe);
		if (size < 1L || size > MAX_PROVIDER_FILE_BYTES) {
			throw new IOException("Guided-import file has an invalid bounded size: " + safe);
		}
		Files.createDirectories(destination.getParent());
		Files.copy(safe, destination);
	}

	private static void writeUnresolvedMapping(Path definitions, Path destination)
		throws IOException, WorldBuilderDiscoveryException {
		List<Path> documents = definitionDocuments(definitions);
		Map<Integer,String> items = new TreeMap<Integer,String>();
		for (Path document : documents) {
			Map<String,Object> root = WorldBuilderJsonDocuments.readTargetDefinitionObject(document);
			Object raw = root.get("items");
			if (!(raw instanceof List)) raw = root.get("item");
			if (!(raw instanceof List)) continue;
			for (Object entry : (List<?>)raw) {
				if (!(entry instanceof Map)) continue;
				@SuppressWarnings("unchecked") Map<String,Object> item = (Map<String,Object>)entry;
				Object rawId = item.get("id");
				Object rawName = item.get("name");
				if (!(rawId instanceof Long) || !(rawName instanceof String)) continue;
				long id = ((Long)rawId).longValue();
				String name = ((String)rawName).trim();
				if (id < 0L || id > 65535L || name.isEmpty()
					|| name.length() > WorldBuilderContractLimits.MAX_DISPLAY_CHARS) continue;
				items.put(Integer.valueOf((int)id), name);
			}
		}
		if (items.isEmpty()) {
			throw new IOException("The selected definitions contain no supported item ID/name records. "
				+ "Choose JSON with an item or items array containing integer id and string name fields.");
		}
		List<Object> visuals = new ArrayList<Object>();
		for (Map.Entry<Integer,String> item : items.entrySet()) {
			Map<String,Object> visual = new LinkedHashMap<String,Object>();
			visual.put("itemId", Long.valueOf(item.getKey().longValue()));
			visual.put("name", item.getValue());
			visual.put("logicalSpriteLocation", null);
			visual.put("sourceRole", "unresolved");
			visual.put("sourceAsset", null);
			visual.put("sourceAssetSha256", null);
			visual.put("authenticSpriteId", null);
			visual.put("customSpriteSubspace", null);
			visual.put("customSpriteEntry", null);
			visual.put("externalPng", null);
			visual.put("pictureMask", Long.valueOf(0L));
			visual.put("blueMask", Long.valueOf(0L));
			visuals.add(visual);
		}
		Map<String,Object> mapping = new LinkedHashMap<String,Object>();
		mapping.put("schemaVersion", Long.valueOf(1L));
		mapping.put("manifestType", "world-builder-item-visual-mapping");
		mapping.put("itemVisuals", visuals);
		Files.createDirectories(destination.getParent());
		Files.write(destination,
			WorldBuilderJsonDocuments.pretty(mapping).getBytes(StandardCharsets.UTF_8),
			StandardOpenOption.CREATE_NEW);
	}

	private static List<Path> definitionDocuments(Path requested) throws IOException {
		if (requested == null) throw new IOException(
			"Choose either an item-visual mapping JSON or item definition JSON/folder.");
		Path value = requested.toAbsolutePath().normalize();
		if (safeFile(value)) {
			if (!value.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".json")) {
				throw new IOException("The selected definition file must be JSON: " + value);
			}
			return Collections.singletonList(value.toRealPath());
		}
		Path root = requireDirectory(value, "item definition folder");
		final List<Path> result = new ArrayList<Path>();
		Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
			@Override public FileVisitResult preVisitDirectory(Path directory,
				BasicFileAttributes attributes) throws IOException {
				if (!attributes.isDirectory() || attributes.isSymbolicLink()) {
					throw new IOException("Definition folder contains an unsafe directory: " + directory);
				}
				return FileVisitResult.CONTINUE;
			}
			@Override public FileVisitResult visitFile(Path file,
				BasicFileAttributes attributes) throws IOException {
				if (!attributes.isRegularFile() || attributes.isSymbolicLink()) {
					throw new IOException("Definition folder contains an unsafe file: " + file);
				}
				if (file.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".json")) {
					if (attributes.size() > WorldBuilderContractLimits.MAX_JSON_BYTES) {
						throw new IOException("Definition JSON exceeds the bounded input limit: " + file);
					}
					result.add(file);
				}
				return FileVisitResult.CONTINUE;
			}
		});
		Collections.sort(result, new Comparator<Path>() {
			@Override public int compare(Path left, Path right) {
				return root.relativize(left).toString().replace('\\', '/').compareTo(
					root.relativize(right).toString().replace('\\', '/'));
			}
		});
		if (result.isEmpty()) throw new IOException(
			"The selected definition folder contains no safe JSON files.");
		return result;
	}

	private static void copyTree(final Path requested, final Path destination)
		throws IOException {
		final Path source = requireDirectory(requested, "guided-import folder");
		final long[] total = {0L};
		final int[] count = {0};
		Files.walkFileTree(source, new SimpleFileVisitor<Path>() {
			@Override public FileVisitResult preVisitDirectory(Path directory,
				BasicFileAttributes attributes) throws IOException {
				if (!attributes.isDirectory() || attributes.isSymbolicLink()) {
					throw new IOException("Guided-import folder contains an unsafe directory: " + directory);
				}
				Path relative = source.relativize(directory);
				Files.createDirectories(destination.resolve(relative.toString()));
				return FileVisitResult.CONTINUE;
			}
			@Override public FileVisitResult visitFile(Path file,
				BasicFileAttributes attributes) throws IOException {
				if (!attributes.isRegularFile() || attributes.isSymbolicLink()) {
					throw new IOException("Guided-import folder contains an unsafe file: " + file);
				}
				if (attributes.size() > MAX_PROVIDER_FILE_BYTES
					|| total[0] > MAX_PROVIDER_TOTAL_BYTES - attributes.size()
					|| ++count[0] > MAX_PROVIDER_FILES) {
					throw new IOException("Guided-import folder exceeds the bounded local import limits.");
				}
				total[0] += attributes.size();
				Files.copy(file, destination.resolve(source.relativize(file).toString()));
				return FileVisitResult.CONTINUE;
			}
		});
	}

	private static void requireOrCreateDirectory(Path directory, String label)
		throws IOException {
		if (Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
			requireDirectory(directory, label);
		} else Files.createDirectory(directory);
	}

	private static Path requireDirectory(Path path, String label) throws IOException {
		if (path == null) throw new IOException(label + " was not supplied.");
		Path normalized = path.toAbsolutePath().normalize();
		if (!safeDirectory(normalized)) throw new IOException(label + " is missing or unsafe: " + normalized);
		return normalized.toRealPath();
	}

	private static Path requireFile(Path path, String label) throws IOException {
		if (path == null) throw new IOException(label + " was not supplied.");
		Path normalized = path.toAbsolutePath().normalize();
		if (!safeFile(normalized)) throw new IOException(label + " is missing or unsafe: " + normalized);
		return normalized.toRealPath();
	}

	private static boolean safeDirectory(Path path) {
		return path != null && Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
			&& !Files.isSymbolicLink(path);
	}

	private static boolean safeFile(Path path) {
		return path != null && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
			&& !Files.isSymbolicLink(path);
	}

	private static Path childFile(Path parent, String name) {
		Path value = parent.resolve(name);
		return safeFile(value) ? value.toAbsolutePath().normalize() : null;
	}

	private static Path childDirectory(Path parent, String name) {
		Path value = parent.resolve(name);
		return safeDirectory(value) ? value.toAbsolutePath().normalize() : null;
	}

	private static Path firstFile(Path parent, String... names) {
		if (!safeDirectory(parent)) return null;
		for (String name : names) {
			Path found = childFile(parent, name);
			if (found != null) return found;
		}
		return null;
	}

	private static Path firstDirectory(Path parent, String... names) {
		if (!safeDirectory(parent)) return null;
		for (String name : names) {
			Path found = childDirectory(parent, name);
			if (found != null) return found;
		}
		return null;
	}

	private static String portableKey(String value) {
		return value.toLowerCase(java.util.Locale.ROOT).replace('/', '-').replace('_', '-');
	}

	private static void deleteOwnedTree(Path root) throws IOException {
		if (root == null || !Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return;
		Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
			@Override public FileVisitResult visitFile(Path file,
				BasicFileAttributes attributes) throws IOException {
				Files.delete(file);
				return FileVisitResult.CONTINUE;
			}
			@Override public FileVisitResult postVisitDirectory(Path directory,
				IOException failure) throws IOException {
				if (failure != null) throw failure;
				Files.delete(directory);
				return FileVisitResult.CONTINUE;
			}
		});
	}

	enum Status { EXPLICIT, RECOGNIZED, LOCAL, AMBIGUOUS, NONE }

	static final class Discovery {
		final Status status;
		final Path source;
		final List<Candidate> candidates;
		final Candidate selected;
		final String summary;

		Discovery(Status status, Path source, List<Candidate> candidates,
			Candidate selected, String summary) {
			this.status = status;
			this.source = source;
			this.candidates = Collections.unmodifiableList(new ArrayList<Candidate>(candidates));
			this.selected = selected;
			this.summary = summary;
		}

		boolean automatic() { return selected != null; }

		String describe() {
			StringBuilder text = new StringBuilder(summary);
			for (Candidate candidate : candidates) {
				text.append("\n\n• ").append(candidate.label);
				for (String component : candidate.components()) text.append("\n  ").append(component);
			}
			return text.toString();
		}

		String toJson() {
			Map<String,Object> value = new LinkedHashMap<String,Object>();
			value.put("schemaVersion", Long.valueOf(1L));
			value.put("manifestType", "world-builder-provider-discovery");
			value.put("status", status.name().toLowerCase(java.util.Locale.ROOT));
			value.put("source", source.toString());
			value.put("summary", summary);
			value.put("selectedProfileId", selected == null ? null : selected.profileId);
			List<Object> values = new ArrayList<Object>();
			for (Candidate candidate : candidates) values.add(candidate.toMap());
			value.put("candidates", values);
			return WorldBuilderJsonDocuments.pretty(value);
		}
	}

	static final class Candidate implements Comparable<Candidate> {
		final String profileId;
		final String label;
		final Path root;
		final Path itemVisuals;
		final Path authenticArchive;
		final Path customArchive;
		final Path spritepacks;
		final Path externalItems;
		final Path definitions;

		Candidate(String profileId, String label, Path root, Path itemVisuals,
			Path authenticArchive, Path customArchive, Path spritepacks,
			Path externalItems, Path definitions) {
			this.profileId = profileId;
			this.label = label;
			this.root = root;
			this.itemVisuals = itemVisuals;
			this.authenticArchive = authenticArchive;
			this.customArchive = customArchive;
			this.spritepacks = spritepacks;
			this.externalItems = externalItems;
			this.definitions = definitions;
		}

		List<String> components() {
			List<String> values = new ArrayList<String>();
			add(values, "mapping", itemVisuals); add(values, "definitions", definitions);
			add(values, "authentic archive", authenticArchive); add(values, "custom archive", customArchive);
			add(values, "spritepacks", spritepacks); add(values, "external items", externalItems);
			return values;
		}

		private static void add(List<String> values, String label, Path path) {
			if (path != null) values.add(label + ": " + path);
		}

		@Override public int compareTo(Candidate other) {
			int byId = profileId.compareTo(other.profileId);
			return byId != 0 ? byId : root.toString().compareTo(other.root.toString());
		}

		Map<String,Object> toMap() {
			Map<String,Object> value = new LinkedHashMap<String,Object>();
			value.put("profileId", profileId);
			value.put("label", label);
			value.put("root", root.toString());
			value.put("itemVisuals", path(itemVisuals));
			value.put("definitions", path(definitions));
			value.put("authenticArchive", path(authenticArchive));
			value.put("customArchive", path(customArchive));
			value.put("spritepacks", path(spritepacks));
			value.put("externalItems", path(externalItems));
			return value;
		}

		private static String path(Path value) {
			return value == null ? null : value.toString();
		}
	}

	static final class GuidedSelection {
		final Path itemVisuals;
		final Path definitions;
		final Path authenticArchive;
		final Path customArchive;
		final Path spritepacks;
		final Path externalItems;

		GuidedSelection(Path itemVisuals, Path definitions, Path authenticArchive,
			Path customArchive, Path spritepacks, Path externalItems) {
			this.itemVisuals = itemVisuals;
			this.definitions = definitions;
			this.authenticArchive = authenticArchive;
			this.customArchive = customArchive;
			this.spritepacks = spritepacks;
			this.externalItems = externalItems;
		}

		GuidedSelection normalized() {
			return new GuidedSelection(normalize(itemVisuals), normalize(definitions),
				normalize(authenticArchive), normalize(customArchive), normalize(spritepacks),
				normalize(externalItems));
		}

		void requireUsable() throws IOException {
			if (itemVisuals == null && definitions == null) throw new IOException(
				"Choose either an item-visual mapping JSON or item definition JSON/folder.");
			if (itemVisuals != null) requireFile(itemVisuals, "item-visual mapping JSON");
			if (definitions != null && !safeFile(definitions) && !safeDirectory(definitions)) {
				throw new IOException("Definitions selection is missing or unsafe: " + definitions);
			}
			if (authenticArchive != null) requireFile(authenticArchive, "authentic sprite archive");
			if (customArchive != null) requireFile(customArchive, "custom sprite archive");
			if (spritepacks != null) requireDirectory(spritepacks, "spritepacks folder");
			if (externalItems != null) requireDirectory(externalItems, "external item-assets folder");
		}

		private static Path normalize(Path value) {
			return value == null ? null : value.toAbsolutePath().normalize();
		}
	}

	static final class Provider {
		final String providerId;
		final Path root;
		final Path itemVisuals;
		final String fingerprintSha256;
		final List<FileRecord> files;

		Provider(String providerId, Path root, Path itemVisuals,
			String fingerprintSha256, List<FileRecord> files) {
			this.providerId = providerId;
			this.root = root;
			this.itemVisuals = itemVisuals;
			this.fingerprintSha256 = fingerprintSha256;
			this.files = Collections.unmodifiableList(new ArrayList<FileRecord>(files));
		}

		String toJson() {
			Map<String,Object> value = new LinkedHashMap<String,Object>();
			value.put("schemaVersion", Long.valueOf(1L));
			value.put("manifestType", "world-builder-local-provider");
			value.put("providerId", providerId);
			value.put("root", root.toString());
			value.put("itemVisuals", itemVisuals.toString());
			value.put("providerFingerprintSha256", fingerprintSha256);
			List<Object> inventory = new ArrayList<Object>();
			for (FileRecord file : files) inventory.add(file.toMap());
			value.put("files", inventory);
			return WorldBuilderJsonDocuments.pretty(value);
		}
	}

	static final class FileRecord implements Comparable<FileRecord> {
		final String relativePath;
		final long size;
		final String sha256;

		FileRecord(String relativePath, long size, String sha256) {
			this.relativePath = relativePath;
			this.size = size;
			this.sha256 = sha256;
		}

		@Override public int compareTo(FileRecord other) {
			return relativePath.compareTo(other.relativePath);
		}

		Map<String,Object> toMap() {
			Map<String,Object> value = new LinkedHashMap<String,Object>();
			value.put("relativePath", relativePath);
			value.put("size", Long.valueOf(size));
			value.put("sha256", sha256);
			return value;
		}
	}
}
