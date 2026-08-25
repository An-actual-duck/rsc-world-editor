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
	static final String PACKAGE_MANIFEST_FILE = "package-manifest-v1.json";
	static final String ASSETS_DIRECTORY = "assets";
	static final String AUTHENTIC_FILE = "Authentic_Sprites.orsc";
	static final String CUSTOM_FILE = "Custom_Sprites.osar";
	static final String SPRITEPACKS_DIRECTORY = "spritepacks";
	static final String EXTERNAL_ITEMS_DIRECTORY = "external-items";
	static final String DEFINITIONS_DIRECTORY = "definitions";
	static final String PROVIDERS_DIRECTORY = "providers";
	static final String CATALOG_FILE = "catalog.json";
	static final String CACHE_RESET_CONFIRMATION = "RESET PROVIDER CACHE";

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
			|| safeFile(source.resolve(PACKAGE_MANIFEST_FILE))
			? source : source.resolve(PACKAGE_DIRECTORY);
		if (safeDirectory(explicit)) {
			Candidate candidate = explicitCandidate(explicit);
			return new Discovery(Status.EXPLICIT, source,
				Collections.singletonList(candidate), candidate,
				"Explicit world-builder-provider package found and selected.",
				CacheStatus.BYPASSED);
		}
		return discover(source, installation, adaptiveDiscoveryEvidence(source));
	}

	Discovery discover(Path requestedSource, Path installation,
		String discoveryEvidenceSha256) throws IOException {
		Path source = requireDirectory(requestedSource, "provider discovery source");
		if (!WorldBuilderBoundedInventory.isHash(discoveryEvidenceSha256)) {
			throw new IOException("Provider discovery requires one exact source-evidence SHA-256.");
		}
		Path explicit = safeFile(source.resolve(MAPPING_FILE))
			|| safeFile(source.resolve(PACKAGE_MANIFEST_FILE))
			? source : source.resolve(PACKAGE_DIRECTORY);
		if (safeDirectory(explicit)) {
			Candidate candidate = explicitCandidate(explicit);
			return new Discovery(Status.EXPLICIT, source,
				Collections.singletonList(candidate), candidate,
				"Explicit world-builder-provider package found and selected.",
				CacheStatus.BYPASSED);
		}
		String sourceEvidenceSha256 = sourceEvidence(source, discoveryEvidenceSha256);

		CacheLookup cache = localCandidates(source, installation, sourceEvidenceSha256);
		List<Candidate> local = cache.candidates;
		List<Candidate> legacy = normalizedLegacyCandidates(source);
		if (local.size() == 1) {
			Candidate selected = local.get(0);
			return new Discovery(Status.LOCAL, source, local, selected,
				"An exact unchanged local provider was selected for this server evidence.",
				CacheStatus.HIT);
		}
		if (local.size() > 1) {
			return new Discovery(Status.AMBIGUOUS, source, local, null,
				"More than one exact local provider matches this server evidence. Choose one explicitly.",
				CacheStatus.AMBIGUOUS);
		}
		if (legacy.size() == 1) {
			Candidate candidate = legacy.get(0);
			String cacheMessage = cache.corrupt
				? " The previous local cache is corrupt and was not selected; its files were preserved."
				: cache.stale > 0
					? " Server content changed, so the previous local provider was not reused."
					: "";
			return new Discovery(Status.RECOGNIZED, source, legacy, candidate,
				"One recognized neutral OpenRSC content layout was found." + cacheMessage,
				cache.corrupt ? CacheStatus.CORRUPT
					: cache.stale > 0 ? CacheStatus.STALE : CacheStatus.MISS);
		}
		if (legacy.size() > 1) {
			return new Discovery(Status.AMBIGUOUS, source, legacy, null,
				"More than one recognized content layout was found. Choose the exact files in guided import.",
				cache.corrupt ? CacheStatus.CORRUPT
					: cache.stale > 0 ? CacheStatus.STALE : CacheStatus.MISS);
		}
		if (cache.corrupt) return new Discovery(Status.CORRUPT, source,
			Collections.<Candidate>emptyList(), null,
			"The local provider cache is corrupt and was preserved. Use Advanced/Recovery to inspect or replace it.",
			CacheStatus.CORRUPT);
		if (cache.stale > 0) return new Discovery(Status.STALE, source,
			Collections.<Candidate>emptyList(), null,
			"Server content changed, so the previous provider was not reused. Create a new provider/project from current evidence.",
			CacheStatus.STALE);
		return new Discovery(Status.NONE, source, Collections.<Candidate>emptyList(),
			null, "No complete provider layout was found. Use guided import to select the content files.",
			CacheStatus.MISS);
	}

	Provider publishGuided(Path requestedInstallation, Path requestedSource,
		GuidedSelection selection) throws IOException, WorldBuilderDiscoveryException {
		Path source = requireDirectory(requestedSource, "guided-import source");
		return publishGuided(requestedInstallation, source, selection,
			adaptiveDiscoveryEvidence(source));
	}

	Path exportDiagnostic(Path requestedInstallation, Path requestedSource,
		String discoveryEvidenceSha256) throws IOException {
		Path installation = requireDirectory(requestedInstallation,
			"World Builder installation");
		Discovery discovered = discover(requestedSource, installation,
			discoveryEvidenceSha256);
		byte[] bytes = discovered.diagnosticJson(discoveryEvidenceSha256)
			.getBytes(StandardCharsets.UTF_8);
		String id = WorldBuilderHashes.sha256(bytes).substring(0, 16);
		Path diagnostics = installation.resolve("diagnostics");
		requireOrCreateDirectory(diagnostics, "World Builder diagnostics directory");
		Path providerDiagnostics = diagnostics.resolve("provider-cache");
		requireOrCreateDirectory(providerDiagnostics,
			"provider-cache diagnostics directory");
		Path destination = providerDiagnostics.resolve(
			"provider-diagnostic-" + id + ".json");
		if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
			if (!safeFile(destination) || Files.size(destination) != bytes.length
				|| !Arrays.equals(Files.readAllBytes(destination), bytes)) {
				throw new IOException("Existing provider diagnostic collision was preserved: "
					+ destination);
			}
			return destination;
		}
		Path temporary = Files.createTempFile(providerDiagnostics,
			".provider-diagnostic-", ".tmp");
		try {
			Files.write(temporary, bytes, StandardOpenOption.TRUNCATE_EXISTING);
			WorldBuilderAdaptiveDurability.forceFile(temporary);
			try {
				WorldBuilderAdaptiveAtomicFiles.moveNew(temporary, destination,
					"provider-diagnostic-export", id);
			} catch (WorldBuilderContractException publication) {
				throw new IOException(publication.getMessage(), publication);
			}
			WorldBuilderAdaptiveDurability.forceDirectory(providerDiagnostics);
			return destination;
		} finally {
			Files.deleteIfExists(temporary);
		}
	}

	CacheReset resetCache(Path requestedInstallation, Path requestedSource,
		String confirmation) throws IOException, WorldBuilderDiscoveryException {
		if (!CACHE_RESET_CONFIRMATION.equals(confirmation)) {
			throw new IOException("Provider cache reset requires exact confirmation: "
				+ CACHE_RESET_CONFIRMATION);
		}
		Path installation = requireDirectory(requestedInstallation,
			"World Builder installation");
		Path source = requireDirectory(requestedSource, "provider cache source");
		Path providers = installation.resolve(PROVIDERS_DIRECTORY);
		if (!safeDirectory(providers)) {
			return new CacheReset(false, false, 0, null,
				"No local provider cache exists for this installation.");
		}
		Path catalog = providers.resolve(CATALOG_FILE);
		if (!Files.exists(catalog, LinkOption.NOFOLLOW_LINKS)) {
			return new CacheReset(false, false, 0, null,
				"No local provider catalog exists; nothing was reset.");
		}
		if (!safeFile(catalog)
			|| Files.size(catalog) > WorldBuilderContractLimits.MAX_JSON_BYTES) {
			throw new IOException("Unsafe or oversized provider catalog was preserved. "
				+ "Export diagnostics and inspect it manually before recovery.");
		}
		byte[] before = Files.readAllBytes(catalog);
		CatalogDocument parsed = null;
		boolean corrupt = false;
		try {
			parsed = readCatalog(catalog);
		} catch (IOException malformed) {
			corrupt = true;
		} catch (WorldBuilderDiscoveryException malformed) {
			corrupt = true;
		}
		String sourceId = sourceIdentity(source);
		List<Map<String,Object>> retained = new ArrayList<Map<String,Object>>();
		int removed = 0;
		if (parsed != null) {
			for (Map<String,Object> item : parsed.records) {
				if (sourceId.equals(item.get("sourceIdentitySha256"))) removed++;
				else retained.add(toVersion2Record(item, parsed.version));
			}
			if (removed == 0) return new CacheReset(false, false, 0, null,
				"No provider-cache association exists for this server.");
		}
		Path diagnostics = installation.resolve("diagnostics");
		requireOrCreateDirectory(diagnostics, "World Builder diagnostics directory");
		Path recovery = diagnostics.resolve("provider-cache-recovery");
		requireOrCreateDirectory(recovery, "provider-cache recovery directory");
		String backupId = WorldBuilderHashes.sha256(before).substring(0, 16);
		Path backup = recovery.resolve("catalog-before-reset-" + backupId + ".json");
		writeNewOrVerify(backup, before, "provider cache recovery backup");
		writeCatalog(providers, retained);
		return new CacheReset(true, corrupt, removed, backup,
			corrupt
				? "The malformed catalog was backed up and replaced. Provider folders and projects were preserved."
				: "The selected server association was reset. Provider folders and projects were preserved.");
	}

	Provider publishGuided(Path requestedInstallation, Path requestedSource,
		GuidedSelection selection, String expectedSourceEvidenceSha256)
		throws IOException, WorldBuilderDiscoveryException {
		Path installation = requireDirectory(requestedInstallation,
			"World Builder installation");
		Path source = requireDirectory(requestedSource, "guided-import source");
		if (!WorldBuilderBoundedInventory.isHash(expectedSourceEvidenceSha256)) {
			throw new IOException("Provider import requires one exact discovery-evidence SHA-256.");
		}
		String currentSourceEvidence = sourceEvidence(source,
			expectedSourceEvidenceSha256);
		selection = selection.normalized();
		selection.requireUsable();

		Path providers = installation.resolve(PROVIDERS_DIRECTORY);
		requireOrCreateDirectory(providers, "local provider directory");
		Path stage = providers.resolve(".provider-" + UUID.randomUUID().toString());
		Files.createDirectory(stage);
		boolean published = false;
		try {
			Path packageStage = stage;
			Path mappingRelative = java.nio.file.Paths.get(MAPPING_FILE);
			Path versionedPackage = versionedPackageRoot(selection);
			if (versionedPackage != null) {
				copyTree(versionedPackage, packageStage);
				mappingRelative = versionedPackage.relativize(
					requireFile(selection.itemVisuals, "versioned provider mapping"));
			} else {
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
				destination.resolve(mappingRelative), fingerprint, files);
			updateCatalog(providers, source, currentSourceEvidence, provider);
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

	private Path versionedPackageRoot(GuidedSelection selection) throws IOException {
		if (selection.itemVisuals == null) return null;
		Path mapping = requireFile(selection.itemVisuals, "item-visual mapping JSON");
		Path root = mapping.getParent();
		if (root == null || !safeFile(root.resolve(PACKAGE_MANIFEST_FILE))) return null;
		Path selected = packagedMapping(root);
		if (!selected.equals(mapping)) return null;
		if (selection.definitions != null || selection.authenticArchive != null
			|| selection.customArchive != null || selection.spritepacks != null
			|| selection.externalItems != null) {
			throw new IOException("A versioned provider package is already complete. "
				+ "Select its full mapping without additional guided-import assets.");
		}
		return root;
	}

	private Candidate explicitCandidate(Path root) throws IOException {
		Path mapping = safeFile(root.resolve(MAPPING_FILE))
			? requireFile(root.resolve(MAPPING_FILE), "explicit provider mapping")
			: packagedMapping(root);
		Path assets = root.resolve(ASSETS_DIRECTORY);
		if (!safeDirectory(assets)) assets = null;
		return new Candidate("explicit-provider", "Explicit portable provider", root,
			mapping, assets == null ? null : childFile(assets, AUTHENTIC_FILE),
			assets == null ? null : childFile(assets, CUSTOM_FILE),
			assets == null ? null : childDirectory(assets, SPRITEPACKS_DIRECTORY),
			assets == null ? null : childDirectory(assets, EXTERNAL_ITEMS_DIRECTORY),
			assets == null ? null : childDirectory(assets, DEFINITIONS_DIRECTORY));
	}

	private Path packagedMapping(Path root) throws IOException {
		Path manifest = requireFile(root.resolve(PACKAGE_MANIFEST_FILE),
			"versioned provider package manifest");
		if (Files.size(manifest) > WorldBuilderContractLimits.MAX_JSON_BYTES) {
			throw new IOException("Versioned provider package manifest exceeds its JSON bound.");
		}
		try {
			Map<String,Object> document = WorldBuilderJsonDocuments.readObject(manifest);
			if (!Long.valueOf(1L).equals(document.get("schemaVersion"))
				|| !"world-builder-item-visual-provider-package".equals(
					document.get("manifestType"))
				|| !(document.get("files") instanceof List)) {
				throw new IOException("Versioned provider package identity is unsupported.");
			}
			Path selected = null;
			for (Object raw : (List<?>)document.get("files")) {
				if (!(raw instanceof Map)) continue;
				@SuppressWarnings("unchecked") Map<String,Object> file = (Map<String,Object>)raw;
				if (!"full-item-visual-manifest".equals(file.get("role"))) continue;
				if (!(file.get("path") instanceof String) || selected != null) {
					throw new IOException("Versioned provider package has an ambiguous full mapping.");
				}
				try {
					selected = WorldBuilderPortablePath.resolveContained(root,
						(String)file.get("path"), "portable-provider-discovery");
				} catch (WorldBuilderContractException unsafe) {
					throw new IOException(unsafe.getMessage(), unsafe);
				}
			}
			return requireFile(selected, "versioned provider full mapping");
		} catch (WorldBuilderDiscoveryException malformed) {
			throw new IOException("Versioned provider package manifest is malformed.", malformed);
		}
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

	private List<Candidate> normalizedLegacyCandidates(Path source) throws IOException {
		List<Candidate> discovered = legacyCandidates(source);
		boolean hasClientAuthority = false;
		for (Candidate candidate : discovered) {
			if (!serverFallback(candidate)) {
				hasClientAuthority = true;
				break;
			}
		}
		if (hasClientAuthority) {
			List<Candidate> client = new ArrayList<Candidate>();
			for (Candidate candidate : discovered) {
				if (!serverFallback(candidate)) client.add(candidate);
			}
			discovered = client;
		}
		return collapseMirroredCandidates(discovered);
	}

	private static boolean serverFallback(Candidate candidate) {
		return candidate.profileId.startsWith("legacy-server-conf-server-data")
			|| candidate.profileId.startsWith("legacy-server-data");
	}

	/**
	 * A normal OpenRSC distribution may retain the same renderer archives in a
	 * client cache and in server data.  Those mirrors are not competing content
	 * authorities.  Collapse only when every shared role is byte-identical and
	 * one candidate contains every role exposed by the other; conflicting bytes
	 * remain an explicit ambiguity.
	 */
	private List<Candidate> collapseMirroredCandidates(List<Candidate> candidates)
		throws IOException {
		if (candidates.size() < 2) return candidates;
		List<Candidate> result = new ArrayList<Candidate>();
		for (int index = 0; index < candidates.size(); index++) {
			Candidate candidate = candidates.get(index);
			boolean shadowed = false;
			for (int otherIndex = 0; otherIndex < candidates.size(); otherIndex++) {
				if (index == otherIndex) continue;
				Candidate other = candidates.get(otherIndex);
				if (!containsEquivalentContent(other, candidate)) continue;
				if (hasMoreContent(other, candidate)
					|| !hasMoreContent(candidate, other)
						&& other.compareTo(candidate) < 0) {
					shadowed = true;
					break;
				}
			}
			if (!shadowed) result.add(candidate);
		}
		Collections.sort(result);
		return result;
	}

	private static boolean containsEquivalentContent(Candidate complete,
		Candidate subset) throws IOException {
		return containsEquivalent(complete.itemVisuals, subset.itemVisuals)
			&& containsEquivalent(complete.definitions, subset.definitions)
			&& containsEquivalent(complete.authenticArchive, subset.authenticArchive)
			&& containsEquivalent(complete.customArchive, subset.customArchive)
			&& containsEquivalent(complete.spritepacks, subset.spritepacks)
			&& containsEquivalent(complete.externalItems, subset.externalItems);
	}

	private static boolean hasMoreContent(Candidate left, Candidate right) {
		return present(left.itemVisuals) > present(right.itemVisuals)
			|| present(left.definitions) > present(right.definitions)
			|| present(left.authenticArchive) > present(right.authenticArchive)
			|| present(left.customArchive) > present(right.customArchive)
			|| present(left.spritepacks) > present(right.spritepacks)
			|| present(left.externalItems) > present(right.externalItems);
	}

	private static int present(Path value) { return value == null ? 0 : 1; }

	private static boolean containsEquivalent(Path complete, Path subset)
		throws IOException {
		if (subset == null) return true;
		if (complete == null) return false;
		Path left = complete.toRealPath();
		Path right = subset.toRealPath();
		if (left.equals(right)) return true;
		if (safeFile(left) && safeFile(right)) {
			return Files.size(left) == Files.size(right)
				&& WorldBuilderHashes.sha256(left).equals(WorldBuilderHashes.sha256(right));
		}
		if (!safeDirectory(left) || !safeDirectory(right)) return false;
		List<FileRecord> leftFiles = inventory(left);
		List<FileRecord> rightFiles = inventory(right);
		if (leftFiles.size() != rightFiles.size()) return false;
		for (int index = 0; index < leftFiles.size(); index++) {
			FileRecord leftFile = leftFiles.get(index);
			FileRecord rightFile = rightFiles.get(index);
			if (!leftFile.relativePath.equals(rightFile.relativePath)
				|| leftFile.size != rightFile.size
				|| !leftFile.sha256.equals(rightFile.sha256)) return false;
		}
		return true;
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

	private CacheLookup localCandidates(Path source, Path installation,
		String sourceEvidenceSha256)
		throws IOException {
		if (installation == null) return CacheLookup.empty();
		Path root;
		try {
			root = requireDirectory(installation, "World Builder installation");
		} catch (IOException unavailable) {
			return CacheLookup.empty();
		}
		Path catalog = root.resolve(PROVIDERS_DIRECTORY).resolve(CATALOG_FILE);
		if (!Files.exists(catalog, LinkOption.NOFOLLOW_LINKS)) {
			return CacheLookup.empty();
		}
		if (!safeFile(catalog)
			|| Files.size(catalog) > WorldBuilderContractLimits.MAX_JSON_BYTES) {
			return CacheLookup.corrupt();
		}
		try {
			CatalogDocument parsed = readCatalog(catalog);
			boolean version2 = parsed.version == 2;
			String sourceId = sourceIdentity(source);
			List<Candidate> result = new ArrayList<Candidate>();
			int stale = 0;
			boolean corrupt = false;
			for (Map<String,Object> value : parsed.records) {
				if (!sourceId.equals(value.get("sourceIdentitySha256"))) continue;
				Object relative = value.get("providerRelativePath");
				Object expectedFingerprint = value.get("providerFingerprintSha256");
				Object expectedSourceEvidence = value.get("sourceDiscoveryFingerprintSha256");
				if (!(relative instanceof String) || !(expectedFingerprint instanceof String)) {
					corrupt = true; continue;
				}
				if (!version2 || !(expectedSourceEvidence instanceof String)
					|| !WorldBuilderBoundedInventory.isHash((String)expectedSourceEvidence)
					|| !sourceEvidenceSha256.equals(expectedSourceEvidence)) {
					stale++; continue;
				}
				Path provider = root.resolve(PROVIDERS_DIRECTORY).resolve((String)relative).normalize();
				if (!provider.getParent().equals(root.resolve(PROVIDERS_DIRECTORY))) {
					corrupt = true; continue;
				}
				if (safeDirectory(provider)
					&& expectedFingerprint.equals(providerFingerprint(inventory(provider)))) {
					result.add(explicitCandidate(provider));
				} else corrupt = true;
			}
			Collections.sort(result);
			return new CacheLookup(result, stale, corrupt);
		} catch (WorldBuilderDiscoveryException malformed) {
			return CacheLookup.corrupt();
		} catch (IOException malformed) {
			return CacheLookup.corrupt();
		}
	}

	private void updateCatalog(Path providers, Path source,
		String sourceEvidenceSha256, Provider provider)
		throws IOException, WorldBuilderDiscoveryException {
		Path catalog = providers.resolve(CATALOG_FILE);
		Map<String,Map<String,Object>> byProvider = new TreeMap<String,Map<String,Object>>();
		if (Files.exists(catalog, LinkOption.NOFOLLOW_LINKS)) {
			if (!safeFile(catalog)
				|| Files.size(catalog) > WorldBuilderContractLimits.MAX_JSON_BYTES) {
				throw new IOException("Unsafe or oversized local provider catalog was preserved.");
			}
			CatalogDocument existing = readCatalog(catalog);
			for (Map<String,Object> item : existing.records) {
					Map<String,Object> record = toVersion2Record(item, existing.version);
					Object id = record.get("providerId");
					Object sourceId = record.get("sourceIdentitySha256");
					byProvider.put((String)id + "\u0000" + (String)sourceId, record);
				}
		}
		Map<String,Object> record = new LinkedHashMap<String,Object>();
		record.put("providerId", provider.providerId);
		record.put("providerRelativePath", provider.providerId);
		record.put("providerFingerprintSha256", provider.fingerprintSha256);
		record.put("sourceIdentitySha256", sourceIdentity(source));
		record.put("sourceDiscoveryFingerprintSha256", sourceEvidenceSha256);
		byProvider.put(provider.providerId + "\u0000" + sourceIdentity(source), record);
		writeCatalog(providers, new ArrayList<Map<String,Object>>(byProvider.values()));
	}

	private static Map<String,Object> toVersion2Record(Map<String,Object> item,
		int version) {
		Map<String,Object> record = new LinkedHashMap<String,Object>(item);
		if (version == 1) record.put("sourceDiscoveryFingerprintSha256", "");
		return record;
	}

	private static void writeCatalog(Path providers,
		List<Map<String,Object>> records) throws IOException {
		Map<String,Object> document = new LinkedHashMap<String,Object>();
		document.put("schemaVersion", Long.valueOf(2L));
		document.put("manifestType", "world-builder-local-provider-catalog");
		document.put("providers", new ArrayList<Object>(records));
		byte[] bytes = WorldBuilderJsonDocuments.pretty(document)
			.getBytes(StandardCharsets.UTF_8);
		Path catalog = providers.resolve(CATALOG_FILE);
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

	private static void writeNewOrVerify(Path destination, byte[] bytes, String label)
		throws IOException {
		if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
			if (!safeFile(destination) || Files.size(destination) != bytes.length
				|| !Arrays.equals(Files.readAllBytes(destination), bytes)) {
				throw new IOException("Existing " + label + " collision was preserved: "
					+ destination);
			}
			return;
		}
		Path temporary = Files.createTempFile(destination.getParent(), ".recovery-", ".tmp");
		try {
			Files.write(temporary, bytes, StandardOpenOption.TRUNCATE_EXISTING);
			WorldBuilderAdaptiveDurability.forceFile(temporary);
			try {
				WorldBuilderAdaptiveAtomicFiles.moveNew(temporary, destination,
					"provider-cache-recovery", destination.getFileName().toString());
			} catch (WorldBuilderContractException publication) {
				throw new IOException(publication.getMessage(), publication);
			}
			WorldBuilderAdaptiveDurability.forceDirectory(destination.getParent());
		} finally {
			Files.deleteIfExists(temporary);
		}
	}

	private static CatalogDocument readCatalog(Path path)
		throws IOException, WorldBuilderDiscoveryException {
		Map<String,Object> document = WorldBuilderJsonDocuments.readObject(path);
		if (!document.keySet().equals(new java.util.HashSet<String>(Arrays.asList(
			"schemaVersion", "manifestType", "providers")))
			|| !"world-builder-local-provider-catalog".equals(
				document.get("manifestType"))) {
			throw new IOException("Local provider catalog identity or keys are invalid.");
		}
		Object rawVersion = document.get("schemaVersion");
		int version = Long.valueOf(1L).equals(rawVersion) ? 1
			: Long.valueOf(2L).equals(rawVersion) ? 2 : 0;
		Object rawRecords = document.get("providers");
		if (version == 0 || !(rawRecords instanceof List)
			|| ((List<?>)rawRecords).size() > MAX_PROVIDER_FILES) {
			throw new IOException("Local provider catalog version or record bound is invalid.");
		}
		List<Map<String,Object>> records = new ArrayList<Map<String,Object>>();
		String previous = "";
		for (Object raw : (List<?>)rawRecords) {
			if (!(raw instanceof Map)) throw new IOException(
				"Local provider catalog record is not an object.");
			@SuppressWarnings("unchecked") Map<String,Object> record =
				(Map<String,Object>)raw;
			java.util.Set<String> expected = new java.util.HashSet<String>(Arrays.asList(
				"providerId", "providerRelativePath", "providerFingerprintSha256",
				"sourceIdentitySha256"));
			if (version == 2) expected.add("sourceDiscoveryFingerprintSha256");
			Object id = record.get("providerId");
			Object relative = record.get("providerRelativePath");
			Object providerHash = record.get("providerFingerprintSha256");
			Object sourceHash = record.get("sourceIdentitySha256");
			Object evidenceHash = record.get("sourceDiscoveryFingerprintSha256");
			if (!record.keySet().equals(expected) || !(id instanceof String)
				|| !((String)id).matches("provider-[0-9a-f]{16}")
				|| !id.equals(relative)
				|| !(providerHash instanceof String)
				|| !WorldBuilderBoundedInventory.isHash((String)providerHash)
				|| !(sourceHash instanceof String)
				|| !WorldBuilderBoundedInventory.isHash((String)sourceHash)
				|| version == 2 && (!(evidenceHash instanceof String)
					|| !("".equals(evidenceHash)
						|| WorldBuilderBoundedInventory.isHash((String)evidenceHash)))) {
				throw new IOException("Local provider catalog record is invalid.");
			}
			String key = (String)id + "\u0000" + (String)sourceHash;
			if (key.compareTo(previous) <= 0) throw new IOException(
				"Local provider catalog records are not sorted and unique.");
			previous = key;
			records.add(new LinkedHashMap<String,Object>(record));
		}
		return new CatalogDocument(version, records);
	}

	private static String sourceIdentity(Path source) throws IOException {
		return WorldBuilderHashes.sha256(source.toRealPath().toString()
			.getBytes(StandardCharsets.UTF_8));
	}

	private static String adaptiveDiscoveryEvidence(Path source) throws IOException {
		try {
			return new WorldBuilderAdaptiveDiscovery().discover(source, null)
				.fingerprintSha256();
		} catch (WorldBuilderContractException invalid) {
			throw new IOException("Server evidence could not be fingerprinted safely: "
				+ invalid.getMessage(), invalid);
		}
	}

	private String sourceEvidence(Path source, String discovery) throws IOException {
		Path realSource = source.toRealPath();
		Map<String,FileRecord> evidence = new TreeMap<String,FileRecord>();
		for (Candidate candidate : normalizedLegacyCandidates(realSource)) {
			addEvidence(candidate.root, realSource, evidence);
			addEvidence(candidate.itemVisuals, realSource, evidence);
			addEvidence(candidate.definitions, realSource, evidence);
		}
		MessageDigest digest = WorldBuilderHashes.newDigest();
		WorldBuilderHashes.updateText(digest,
			"world-builder-provider-source-evidence-v2");
		WorldBuilderHashes.updateText(digest, discovery);
		for (FileRecord record : evidence.values()) {
			WorldBuilderHashes.updateText(digest, record.relativePath);
			WorldBuilderHashes.updateText(digest, Long.toString(record.size));
			WorldBuilderHashes.updateText(digest, record.sha256);
		}
		return WorldBuilderHashes.hex(digest.digest());
	}

	private static void addEvidence(Path requested, Path source,
		Map<String,FileRecord> evidence) throws IOException {
		if (requested == null) return;
		Path value = requested.toRealPath();
		if (!value.startsWith(source)) return;
		if (safeFile(value)) {
			String relative = source.relativize(value).toString().replace('\\', '/');
			evidence.put(relative, new FileRecord(relative, Files.size(value),
				WorldBuilderHashes.sha256(value)));
			return;
		}
		if (!safeDirectory(value)) return;
		for (FileRecord record : inventory(value)) {
			String relative = source.relativize(value.resolve(record.relativePath))
				.toString().replace('\\', '/');
			evidence.put(relative, new FileRecord(relative, record.size, record.sha256));
		}
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

	enum Status { EXPLICIT, RECOGNIZED, LOCAL, AMBIGUOUS, STALE, CORRUPT, NONE }
	enum CacheStatus { BYPASSED, HIT, MISS, STALE, CORRUPT, AMBIGUOUS }

	static final class Discovery {
		final Status status;
		final Path source;
		final List<Candidate> candidates;
		final Candidate selected;
		final String summary;
		final CacheStatus cacheStatus;

		Discovery(Status status, Path source, List<Candidate> candidates,
			Candidate selected, String summary, CacheStatus cacheStatus) {
			this.status = status;
			this.source = source;
			this.candidates = Collections.unmodifiableList(new ArrayList<Candidate>(candidates));
			this.selected = selected;
			this.summary = summary;
			this.cacheStatus = cacheStatus;
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
			value.put("cacheStatus", cacheStatus.name().toLowerCase(
				java.util.Locale.ROOT));
			value.put("selectedProfileId", selected == null ? null : selected.profileId);
			List<Object> values = new ArrayList<Object>();
			for (Candidate candidate : candidates) values.add(candidate.toMap());
			value.put("candidates", values);
			return WorldBuilderJsonDocuments.pretty(value);
		}

		String diagnosticJson(String discoveryEvidenceSha256) throws IOException {
			if (!WorldBuilderBoundedInventory.isHash(discoveryEvidenceSha256)) {
				throw new IOException("Provider diagnostic requires one exact discovery SHA-256.");
			}
			Map<String,Object> value = new LinkedHashMap<String,Object>();
			value.put("schemaVersion", Long.valueOf(1L));
			value.put("manifestType", "world-builder-provider-cache-diagnostic");
			value.put("sourceDiscoveryFingerprintSha256", discoveryEvidenceSha256);
			value.put("status", status.name().toLowerCase(java.util.Locale.ROOT));
			value.put("cacheStatus", cacheStatus.name().toLowerCase(
				java.util.Locale.ROOT));
			value.put("summary", summary);
			List<Object> profiles = new ArrayList<Object>();
			for (Candidate candidate : candidates) {
				Map<String,Object> profile = new LinkedHashMap<String,Object>();
				profile.put("profileId", candidate.profileId);
				profile.put("label", candidate.label);
				List<Object> roles = new ArrayList<Object>();
				if (candidate.itemVisuals != null) roles.add("item-visuals");
				if (candidate.definitions != null) roles.add("definitions");
				if (candidate.authenticArchive != null) roles.add("authentic-archive");
				if (candidate.customArchive != null) roles.add("custom-archive");
				if (candidate.spritepacks != null) roles.add("spritepacks");
				if (candidate.externalItems != null) roles.add("external-items");
				profile.put("componentRoles", roles);
				profiles.add(profile);
			}
			value.put("candidateProfiles", profiles);
			value.put("sourcePathsIncluded", Boolean.FALSE);
			return WorldBuilderJsonDocuments.pretty(value);
		}
	}

	static final class CacheReset {
		final boolean changed;
		final boolean corruptCatalogRecovered;
		final int removedAssociations;
		final Path backup;
		final String summary;

		CacheReset(boolean changed, boolean corruptCatalogRecovered,
			int removedAssociations, Path backup, String summary) {
			this.changed = changed;
			this.corruptCatalogRecovered = corruptCatalogRecovered;
			this.removedAssociations = removedAssociations;
			this.backup = backup;
			this.summary = summary;
		}

		String toJson() {
			Map<String,Object> value = new LinkedHashMap<String,Object>();
			value.put("schemaVersion", Long.valueOf(1L));
			value.put("manifestType", "world-builder-provider-cache-reset");
			value.put("changed", Boolean.valueOf(changed));
			value.put("corruptCatalogRecovered",
				Boolean.valueOf(corruptCatalogRecovered));
			value.put("removedAssociations", Long.valueOf(removedAssociations));
			value.put("backup", backup == null ? null : backup.toString());
			value.put("summary", summary);
			return WorldBuilderJsonDocuments.pretty(value);
		}
	}

	private static final class CacheLookup {
		final List<Candidate> candidates;
		final int stale;
		final boolean corrupt;

		CacheLookup(List<Candidate> candidates, int stale, boolean corrupt) {
			this.candidates = Collections.unmodifiableList(
				new ArrayList<Candidate>(candidates));
			this.stale = stale;
			this.corrupt = corrupt;
		}

		static CacheLookup empty() {
			return new CacheLookup(Collections.<Candidate>emptyList(), 0, false);
		}

		static CacheLookup corrupt() {
			return new CacheLookup(Collections.<Candidate>emptyList(), 0, true);
		}
	}

	private static final class CatalogDocument {
		final int version;
		final List<Map<String,Object>> records;

		CatalogDocument(int version, List<Map<String,Object>> records) {
			this.version = version;
			this.records = Collections.unmodifiableList(
				new ArrayList<Map<String,Object>>(records));
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
