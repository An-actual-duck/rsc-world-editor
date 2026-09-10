package com.openrsc.worldbuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Complete native Base content authority. Never a custom overlay or target executable. */
final class WorldBuilderCurrentBaseProjectContent {
	static final String ROOT = "source/provider";
	static final String IDENTITY = ROOT + "/composition-identity.json";
	static final String BINDING = ROOT + "/native-content.json";
	static final String CATALOG = ROOT + "/authoring-catalog.json";
	static final String NATIVE = ROOT + "/installed";
	static final String STATE_ROOT = "working/authoring-state";
	static final String STATE_PROPERTY = "openrsc.currentBaseAuthoringStateRoot";
	private static final String OP = "current-base-project-content";
	private static final Map<String,String> ROLES;
	static {
		Map<String,String> roles = new TreeMap<String,String>();
		roles.put("client-content", "runtime/client/content.zip");
		roles.put("client-runtime", "runtime/client/Open_RSC_Client.jar");
		roles.put("runtime-profile", "runtime/profile.json");
		roles.put("server-content", "runtime/server/content.zip");
		roles.put("server-plugins", "runtime/server/plugins.jar");
		roles.put("server-runtime", "runtime/server/core.jar");
		ROLES = Collections.unmodifiableMap(roles);
	}
	private WorldBuilderCurrentBaseProjectContent() { }

	/** Data/content inspection alone does not authorize an authoring launch. */
	static Plan inspect(WorldBuilderProviderCatalog.Composition composition)
		throws IOException, WorldBuilderContractException {
		if (!composition.installable || !"current-base-v1".equals(composition.identity.get("variantId"))
			|| !composition.moduleIds.isEmpty()) throw refusal("Select the exact native Current Base composition without overlays.");
		Map<String,Bound> selected = new TreeMap<String,Bound>();
		for (WorldBuilderProviderCatalog.Artifact artifact : composition.artifacts) {
			String role = string(artifact.inventory, "role");
			if (!ROLES.containsKey(role)) continue;
			if (!ROLES.get(role).equals(artifact.bundlePath) || selected.containsKey(role))
				throw refusal("Selected Base native artifact role/path is not exact.");
			Bound bound = new Bound(artifact); bound.verify(); selected.put(role, bound);
		}
		if (!selected.keySet().equals(ROLES.keySet())) throw refusal("Selected Base content is incomplete.");
		Map<String,Object> layout = WorldBuilderCurrentRuntimeLayout.inspect(composition);
		Map<String,Object> profile = read(selected.get("runtime-profile").source);
		List<Object> inventory = new ArrayList<Object>();
		for (Bound item : selected.values()) inventory.add(item.record());
		byte[] identity = bytes(composition.identity);
		Map<String,Object> binding = new LinkedHashMap<String,Object>();
		binding.put("schemaVersion", Long.valueOf(1));
		binding.put("manifestType", "world-builder-current-base-native-content");
		binding.put("compositionIdentitySha256", WorldBuilderHashes.sha256(identity));
		binding.put("artifacts", inventory); binding.put("layout", layout);
		WorldBuilderAdaptiveExporter.bindFingerprint(binding, "fingerprintSha256");
		return new Plan(composition, selected, identity, bytes(binding), profile, layout);
	}

	static Map<String,Object> authoringPolicy() {
		Map<String,Object> expected = new LinkedHashMap<String,Object>();
		expected.put("policyId", "current-base-isolated-authoring-v1");
		expected.put("stateRootProperty", STATE_PROPERTY);
		expected.put("sqliteFile", "world_builder.db");
		expected.put("runtimeProfile", "adaptive-world-builder");
		expected.put("contentPolicy", "native-public-no-overlay");
		return expected;
	}

	/** Check before creating a runnable project; old normal-only Base is not enough. */
	static void requireAuthoring(Plan plan) throws WorldBuilderContractException {
		if (!authoringPolicy().equals(plan.profile.get("authoringPolicy")))
			throw refusal("Selected provider lacks the exact isolated native Base authoring policy.");
	}

	static void capture(Path projectStage, Plan plan) throws IOException, WorldBuilderContractException {
		Path project = projectStage.toRealPath();
		if (!project.equals(projectStage.toAbsolutePath().normalize())) throw refusal("Project stage must be canonical.");
		Path destination = project.resolve(ROOT);
		if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) throw refusal("Native content capture never overwrites existing evidence.");
		for (Bound item : plan.selected.values()) {
			item.verify();
			if (project.startsWith(item.providerRoot) || item.providerRoot.startsWith(project))
				throw refusal("Project capture and selected provider roots must be disjoint.");
		}
		Files.createDirectories(destination);
		for (Bound item : plan.selected.values()) {
			Path output = destination.resolve(item.bundlePath);
			Files.createDirectories(output.getParent());
			Files.copy(item.source, output, StandardCopyOption.COPY_ATTRIBUTES);
			item.verify(); item.verify(output);
		}
		write(destination.resolve("composition-identity.json"), plan.identity);
		write(destination.resolve("native-content.json"), plan.binding);
		WorldBuilderCurrentRuntimeLayout.materialize(destination, plan.layout);
		Map<String,Object> catalog = deriveCatalog(destination.resolve("installed"));
		write(project.resolve(CATALOG), bytes(catalog));
		verify(project);
		WorldBuilderAdaptiveDurability.forceTree(destination);
	}

	/** Portable immutable evidence, rechecked without consulting a later provider checkout. */
	static Map<String,Object> verifiedIdentity(Path project) throws IOException, WorldBuilderContractException {
		verify(project);
		return read(project.resolve(IDENTITY));
	}

	/** Portable immutable evidence, rechecked without consulting a later provider checkout. */
	static Map<String,Object> verify(Path project) throws IOException, WorldBuilderContractException {
		WorldBuilderReadOnlyTarget target = WorldBuilderReadOnlyTarget.open(project);
		Map<String,Object> binding = read(target.requiredFile(BINDING));
		WorldBuilderBoundedInventory.exactKeys(binding, OP, "schemaVersion", "manifestType",
			"compositionIdentitySha256", "artifacts", "layout", "fingerprintSha256");
		if (!Long.valueOf(1).equals(binding.get("schemaVersion"))
			|| !"world-builder-current-base-native-content".equals(binding.get("manifestType"))) throw refusal("Native content binding identity changed.");
		Map<String,Object> expected = new LinkedHashMap<String,Object>(binding);
		WorldBuilderAdaptiveExporter.bindFingerprint(expected, "fingerprintSha256");
		if (!expected.equals(binding) || !WorldBuilderHashes.sha256(target.requiredFile(IDENTITY)).equals(binding.get("compositionIdentitySha256")))
			throw refusal("Native content identity or binding changed.");
		Map<String,Object> identity = read(target.requiredFile(IDENTITY));
		if (!"current-base-v1".equals(identity.get("variantId")) || !Boolean.TRUE.equals(identity.get("installable")))
			throw refusal("Native authoring requires selected Current Base identity.");
		List<?> selectedInventory = list(identity.get("bundleInventory"));
		if (!WorldBuilderHashes.sha256(WorldBuilderJsonDocuments.canonical(selectedInventory).getBytes(StandardCharsets.UTF_8))
			.equals(identity.get("bundleInventoryHash"))) throw refusal("Selected composition artifact inventory changed.");
		List<?> artifacts = list(binding.get("artifacts"));
		if (artifacts.size() != ROLES.size()) throw refusal("Native content artifact inventory is incomplete.");
		int index = 0;
		for (Map.Entry<String,String> role : ROLES.entrySet()) {
			Map<String,Object> item = object(artifacts.get(index++));
			WorldBuilderBoundedInventory.exactKeys(item, OP, "role", "bundlePath", "size", "sha256", "mode");
			if (!role.getKey().equals(item.get("role")) || !role.getValue().equals(item.get("bundlePath")))
				throw refusal("Native content role inventory changed.");
			int matches = 0;
			for (Object raw : selectedInventory) {
				Map<String,Object> selected = object(raw);
				if (!role.getValue().equals(selected.get("bundlePath"))) continue;
				for (String field : item.keySet()) if (!item.get(field).equals(selected.get(field)))
					throw refusal("Native artifact differs from the selected composition inventory.");
				matches++;
			}
			if (matches != 1) throw refusal("Selected composition does not bind the native artifact exactly once.");
			verifyFile(target.requiredFile(ROOT + "/" + role.getValue()), item);
		}
		WorldBuilderCurrentRuntimeLayout.verify(project.resolve(ROOT), object(binding.get("layout")));
		Map<String,Object> catalog = deriveCatalog(project.resolve(NATIVE));
		if (!Arrays.equals(bytes(catalog), Files.readAllBytes(target.requiredFile(CATALOG))))
			throw refusal("Complete Base authoring catalog differs from selected native content.");
		java.util.Set<String> expectedFiles = new java.util.HashSet<String>(Arrays.asList(IDENTITY, BINDING, CATALOG));
		for (String path : ROLES.values()) expectedFiles.add(ROOT + "/" + path);
		for (Object raw : list(object(binding.get("layout")).get("outputs")))
			expectedFiles.add(ROOT + "/" + string(object(raw), "relativePath"));
		java.util.Set<String> actualFiles = new java.util.HashSet<String>();
		try (java.util.stream.Stream<Path> paths = Files.walk(project.resolve(ROOT))) {
			java.util.Iterator<Path> iterator = paths.iterator(); int count = 0;
			while (iterator.hasNext()) {
				Path entry = iterator.next();
				if (++count > WorldBuilderContractLimits.MAX_INVENTORY_ENTRIES * 2) throw refusal("Native evidence tree is unbounded.");
				if (Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)) continue;
				String relative = project.relativize(entry).toString().replace('\\', '/');
				target.requiredFile(relative); actualFiles.add(relative);
			}
		}
		if (!actualFiles.equals(expectedFiles)) throw refusal("Native evidence contains missing or extra files.");
		return binding;
	}

	private static Map<String,Object> deriveCatalog(Path nativeRoot) throws IOException, WorldBuilderContractException {
		WorldBuilderReadOnlyTarget content = WorldBuilderReadOnlyTarget.open(nativeRoot);
		for (String name : Arrays.asList("DoorDef.xml", "GameObjectDef.xml", "TileDef.xml", "ItemDefs.json", "ItemDefsCustom.json", "NpcDefs.json", "NpcDefsCustom.json", "PrayerDef.xml", "SpellDef.xml")) {
			String server = WorldBuilderHashes.sha256(content.requiredFile("server/conf/server/defs/" + name));
			String client = WorldBuilderHashes.sha256(content.requiredFile("client/Cache/current-base-definitions/" + name));
			if (!server.equals(client)) throw refusal("Selected native Base server/client public definitions disagree.");
		}
		for (String path : Arrays.asList("video/Authentic_Sprites.orsc", "video/library.orsc", "video/models.orsc",
			"video/CurrentBase_Public_Sprites.osar", "video/spritepacks/Menus.osar",
			"current-base-definitions/item-visuals.json", "current-base-definitions/scenery-visuals.json"))
			content.requiredFile("client/Cache/" + path);
		return WorldBuilderProjectContentBundle.currentBaseNativeCatalog(nativeRoot);
	}

	private static void verifyFile(Path path, Map<String,Object> record) throws IOException, WorldBuilderContractException {
		WorldBuilderReadOnlyTarget.open(path.getParent()).requiredFile(path.getFileName().toString());
		long size = WorldBuilderBoundedInventory.integer(record.get("size"), OP, "size");
		String mode = string(record, "mode"), hash = string(record, "sha256");
		if (size < 0 || size > WorldBuilderContractLimits.MAX_INVENTORY_FILE_BYTES
			|| !WorldBuilderBoundedInventory.isHash(hash) || !("0644".equals(mode) || "0664".equals(mode) || "0600".equals(mode))
			|| Files.size(path) != size || !hash.equals(WorldBuilderHashes.sha256(path))
			|| (((Number)Files.getAttribute(path, "unix:mode", LinkOption.NOFOLLOW_LINKS)).intValue() & 07777) != Integer.parseInt(mode, 8))
			throw refusal("Native provider artifact bytes or mode changed.");
	}
	private static void write(Path path, byte[] bytes) throws IOException {
		Files.write(path, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
		Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
	}
	private static byte[] bytes(Map<String,Object> value) { return WorldBuilderJsonDocuments.pretty(value).getBytes(StandardCharsets.UTF_8); }
	private static Map<String,Object> read(Path path) throws IOException, WorldBuilderContractException {
		try { return WorldBuilderJsonDocuments.readObject(path); }
		catch (WorldBuilderDiscoveryException malformed) { throw refusal("Native provider document is malformed."); }
	}
	@SuppressWarnings("unchecked") private static Map<String,Object> object(Object raw) throws WorldBuilderContractException {
		if (!(raw instanceof Map)) throw refusal("Native content object is malformed."); return (Map<String,Object>)raw;
	}
	private static List<?> list(Object raw) throws WorldBuilderContractException {
		if (!(raw instanceof List)) throw refusal("Native content inventory is malformed."); return (List<?>)raw;
	}
	private static String string(Map<String,Object> value, String name) throws WorldBuilderContractException {
		if (!(value.get(name) instanceof String)) throw refusal("Native content field is malformed."); return (String)value.get(name);
	}
	private static WorldBuilderContractException refusal(String message) { return new WorldBuilderContractException(
		WorldBuilderErrorCodes.CAPABILITY_MISMATCH, OP, ROOT, false, message,
		"Select the reviewed Current Base provider; do not substitute Advanced assets or target executables."); }

	static final class Plan {
		final WorldBuilderProviderCatalog.Composition composition;
		private final Map<String,Bound> selected;
		private final byte[] identity, binding;
		private final Map<String,Object> profile, layout;
		private Plan(WorldBuilderProviderCatalog.Composition composition, Map<String,Bound> selected, byte[] identity, byte[] binding, Map<String,Object> profile, Map<String,Object> layout) {
			this.composition = composition;
			this.selected = Collections.unmodifiableMap(new TreeMap<String,Bound>(selected));
			this.identity = identity.clone(); this.binding = binding.clone(); this.profile = profile; this.layout = layout;
		}
		String fingerprint() { return WorldBuilderHashes.sha256(binding); }
		Map<String,Object> layout() throws WorldBuilderContractException {
			try { return WorldBuilderJsonDocuments.readObject(bytes(layout), "native-layout"); }
			catch (WorldBuilderDiscoveryException impossible) { throw refusal("Native layout snapshot cannot be decoded."); }
		}
	}
	private static final class Bound {
		final Path source, providerRoot;
		final String role, bundlePath, mode, hash;
		final long size;
		Bound(WorldBuilderProviderCatalog.Artifact artifact) throws WorldBuilderContractException {
			source = artifact.source; role = string(artifact.inventory, "role"); bundlePath = artifact.bundlePath;
			mode = string(artifact.inventory, "mode"); hash = string(artifact.inventory, "sha256");
			size = WorldBuilderBoundedInventory.integer(artifact.inventory.get("size"), OP, "size");
			Path root = source;
			for (String ignored : artifact.sourcePath.split("/")) root = root.getParent();
			providerRoot = root;
		}
		Map<String,Object> record() {
			Map<String,Object> value = new LinkedHashMap<String,Object>();
			value.put("role", role); value.put("bundlePath", bundlePath); value.put("size", Long.valueOf(size));
			value.put("sha256", hash); value.put("mode", mode); return value;
		}
		void verify() throws IOException, WorldBuilderContractException { verify(source); }
		void verify(Path path) throws IOException, WorldBuilderContractException { verifyFile(path, record()); }
	}
}
