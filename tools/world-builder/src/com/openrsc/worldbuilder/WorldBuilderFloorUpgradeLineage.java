package com.openrsc.worldbuilder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/** Verified predecessor authority for a sibling floor upgrade, never a drift override. */
final class WorldBuilderFloorUpgradeLineage {
	static final String PATH = "source/floor-upgrade/current.json";
	static final String FIELD = "inheritedTargetState";
	private static final ThreadLocal<Integer> DEPTH = new ThreadLocal<Integer>();

	static WorldBuilderAdaptiveMutationProfile.Plan installedParent(
		WorldBuilderAdaptiveProjectLifecycle.VerifiedProject project, Path target)
		throws IOException, WorldBuilderContractException {
		int depth = DEPTH.get() == null ? 0 : DEPTH.get().intValue();
		if (depth >= 16) throw refusal("Floor upgrade predecessor chain is too deep or cyclic.");
		DEPTH.set(Integer.valueOf(depth + 1));
		try {
			WorldBuilderAdaptiveProjectLifecycle.VerifiedProject parent = parent(project);
			if (parent == null) return null;
			WorldBuilderAdaptiveReceipt.State receipt =
				WorldBuilderAdaptiveImporter.latestOutstandingSuccessfulImport(parent.projectRoot);
			if (receipt == null) return installedParent(parent, target);
			WorldBuilderAdaptiveExporter.VerifiedExport export =
				WorldBuilderAdaptiveUndo.findExport(parent, receipt.exportFingerprint());
			WorldBuilderAdaptiveMutationProfile.Plan installed =
				WorldBuilderAdaptiveMutationProfile.reconstructInstalled(parent, export, target, receipt.transactionId());
			WorldBuilderAdaptiveReceipt.requireSuccessfulImportMatches(installed, receipt);
			installed = WorldBuilderAdaptiveUndo.resolveEffectiveInstalledPlan(installed);
			if (!WorldBuilderAdaptiveUndo.changedAfterPaths(installed).isEmpty())
				throw refusal("The parent project's installed files changed after its successful transaction.");
			return installed;
		} finally {
			if (depth == 0) DEPTH.remove(); else DEPTH.set(Integer.valueOf(depth));
		}
	}

	/** Mint only from a reconstructed successful parent whose after-state was checked. */
	static Map<String,Object> describe(WorldBuilderAdaptiveMutationProfile.Plan installed)
		throws IOException, WorldBuilderContractException {
		Map<String,Object> result = new LinkedHashMap<String,Object>();
		result.put("projectId", installed.project.projectId);
		result.put("projectFingerprintSha256", installed.project.manifest.get("projectFingerprintSha256"));
		result.put("transactionId", installed.transactionId());
		Path receipt = installed.project.projectRoot.resolve("receipts/" + installed.transactionId() + ".json");
		result.put("receiptSha256", WorldBuilderHashes.sha256(receipt));
		result.put("mutationPlanSha256", installed.canonicalSha256);
		Map<String,Object> states = inheritedFiles(installed.document);
		for (WorldBuilderAdaptiveMutationProfile.Action action : installed.actions)
			states.put(action.destinationRelativePath, action.after.toJson());
		states.put(installed.configuration.relativePath,
			WorldBuilderAdaptiveMutationProfile.FileState.present(installed.configurationBytes.length,
				WorldBuilderHashes.sha256(installed.configurationBytes)).toJson());
		result.put("files", fileList(states));
		return result;
	}

	/** Reverify immutable historical proof without requiring overwritten parent files live. */
	static Map<String,Object> verifyProof(WorldBuilderAdaptiveProjectLifecycle.VerifiedProject project,
		Object raw) throws IOException, WorldBuilderContractException {
		int depth = DEPTH.get() == null ? 0 : DEPTH.get().intValue();
		if (depth >= 16) throw refusal("Floor upgrade predecessor chain is too deep or cyclic.");
		DEPTH.set(Integer.valueOf(depth + 1));
		try { return verifyProofInternal(project, raw); }
		finally { if (depth == 0) DEPTH.remove(); else DEPTH.set(Integer.valueOf(depth)); }
	}

	private static Map<String,Object> verifyProofInternal(WorldBuilderAdaptiveProjectLifecycle.VerifiedProject project,
		Object raw) throws IOException, WorldBuilderContractException {
		Map<String,Object> proof = object(raw);
		validateShape(proof);
		String id = string(proof, "projectId"), transaction = string(proof, "transactionId");
		uuid(id); uuid(transaction);
		WorldBuilderAdaptiveProjectLifecycle.VerifiedProject previous = parent(project);
		int depth = 0;
		while (previous != null && !previous.projectId.equals(id) && ++depth < 16) previous = parent(previous);
		if (previous == null || !previous.projectId.equals(id)
			|| !previous.manifest.get("projectFingerprintSha256").equals(proof.get("projectFingerprintSha256")))
			throw refusal("Inherited target state is not bound to this project's verified predecessor.");
		WorldBuilderAdaptiveReceipt.State latest =
			WorldBuilderAdaptiveImporter.latestOutstandingSuccessfulImport(previous.projectRoot);
		if (latest == null || !transaction.equals(latest.transactionId()))
			throw refusal("The predecessor's installed transaction changed after the floor upgrade preview.");
		WorldBuilderReadOnlyTarget evidence = WorldBuilderReadOnlyTarget.open(previous.projectRoot);
		Path receiptPath = evidence.requiredFile("receipts/" + transaction + ".json");
		if (!WorldBuilderHashes.sha256(receiptPath).equals(proof.get("receiptSha256")))
			throw refusal("Inherited receipt bytes changed.");
		Path planPath = evidence.requiredFile("backups/" + transaction + "/mutation-plan.json");
		Map<String,Object> plan = read(planPath);
		WorldBuilderAdaptiveExporter.requireFingerprint(plan, "planFingerprintSha256");
		String planHash = WorldBuilderAdaptiveContracts.validateParsed(
			WorldBuilderAdaptiveContracts.Kind.MUTATION_PLAN, plan).canonicalSha256;
		if (!planHash.equals(proof.get("mutationPlanSha256"))
			|| !planHash.equals(latest.document.get("mutationPlanSha256"))
			|| !id.equals(plan.get("projectId")) || !id.equals(latest.document.get("projectId"))
			|| !transaction.equals(plan.get("transactionId"))
			|| !plan.get("exportFingerprintSha256").equals(latest.document.get("exportFingerprintSha256")))
			throw refusal("Inherited plan and receipt identities disagree.");
		Map<String,Object> expected = new TreeMap<String,Object>();
		if (plan.containsKey(FIELD)) {
			Map<String,Object> ancestor = verifyProof(previous, plan.get(FIELD));
			expected.putAll(files(ancestor));
		}
		for (Object value : WorldBuilderAdaptiveExporter.array(plan.get("actions"), "actions")) {
			Map<String,Object> action = object(value);
			expected.put(string(action, "destinationRelativePath"), action.get("after"));
		}
		Map<String,Object> actual = files(proof);
		Map<String,Object> selected = object(plan.get("selectedConfiguration"));
		String configuration = string(selected, "relativePath");
		if (!expected.containsKey(configuration)) {
			Map<String,Object> state = object(actual.get(configuration));
			if (!Boolean.TRUE.equals(state.get("present")) || !selected.get("sha256").equals(state.get("sha256")))
				throw refusal("Inherited configuration does not match the successful runtime transaction.");
			expected.put(configuration, state);
		}
		if (!expected.equals(actual)) throw refusal("Inherited file inventory differs from the successful predecessor plan.");
		return proof;
	}

	static Set<String> verifyRemaining(WorldBuilderAdaptiveProjectLifecycle.VerifiedProject project,
		Path target, Object proof, Set<String> superseded) throws IOException, WorldBuilderContractException {
		Map<String,Object> states = files(verifyProof(project, proof));
		for (Map.Entry<String,Object> item : states.entrySet()) {
			if (superseded.contains(item.getKey())) continue;
			Map<String,Object> state = object(item.getValue());
			Path file = WorldBuilderAdaptiveMutationProfile.safeDestination(target, item.getKey());
			boolean present = Boolean.TRUE.equals(state.get("present"));
			if (!present && Files.exists(file, LinkOption.NOFOLLOW_LINKS)
				|| present && (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
					|| Files.size(file) != ((Long)state.get("size")).longValue()
					|| !WorldBuilderHashes.sha256(file).equals(state.get("sha256"))))
				throw refusal("Inherited target file changed: " + item.getKey());
		}
		return states.keySet();
	}

	private static WorldBuilderAdaptiveProjectLifecycle.VerifiedProject parent(
		WorldBuilderAdaptiveProjectLifecycle.VerifiedProject project) throws IOException, WorldBuilderContractException {
		Path path = project.projectRoot.resolve(PATH);
		if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return null;
		Map<String,Object> reference = read(WorldBuilderReadOnlyTarget.open(project.projectRoot).requiredFile(PATH));
		WorldBuilderBoundedInventory.exactKeys(reference, "floor-upgrade-lineage", "schemaVersion", "manifestType",
			"projectId", "projectFingerprintSha256", "workingFingerprintSha256");
		if (!Long.valueOf(1).equals(reference.get("schemaVersion"))
			|| !"world-builder-project-floor-upgrade-origin".equals(reference.get("manifestType")))
			throw refusal("Unsupported floor upgrade predecessor record.");
		String id = string(reference, "projectId"); uuid(id);
		if (id.equals(project.projectId)) throw refusal("A floor project cannot be its own predecessor.");
		Path root = project.projectRoot.getParent().resolve(id);
		WorldBuilderAdaptiveProjectLifecycle.VerifiedProject parent =
			WorldBuilderAdaptiveProjectLifecycle.verifyProjectDirectory(root, true);
		if (!reference.get("projectFingerprintSha256").equals(parent.manifest.get("projectFingerprintSha256"))
			|| !reference.get("workingFingerprintSha256").equals(parent.working.fingerprintSha256)
			|| !project.manifest.get("target").equals(parent.manifest.get("target")))
			throw refusal("The retained predecessor no longer matches its bound project identity.");
		return parent;
	}

	private static Map<String,Object> inheritedFiles(Map<String,Object> plan) throws WorldBuilderContractException {
		return plan.containsKey(FIELD) ? files(object(plan.get(FIELD))) : new TreeMap<String,Object>();
	}
	static void validateShape(Object raw) throws WorldBuilderContractException {
		Map<String,Object> proof = object(raw);
		WorldBuilderBoundedInventory.exactKeys(proof, "floor-upgrade-lineage", "projectId",
			"projectFingerprintSha256", "transactionId", "receiptSha256", "mutationPlanSha256", "files");
		uuid(string(proof, "projectId")); uuid(string(proof, "transactionId"));
		for (String key : new String[] {"projectFingerprintSha256", "receiptSha256", "mutationPlanSha256"})
			if (!WorldBuilderBoundedInventory.isHash(string(proof, key))) throw refusal("Invalid predecessor evidence hash.");
		files(proof);
	}
	static Map<String,Object> files(Map<String,Object> proof) throws WorldBuilderContractException {
		Map<String,Object> result = new TreeMap<String,Object>();
		List<?> values = WorldBuilderAdaptiveExporter.array(proof.get("files"), "files");
		if (values.isEmpty() || values.size() > 4096) throw refusal("Inherited file inventory is unbounded.");
		for (Object value : values) {
			Map<String,Object> file = object(value);
			WorldBuilderBoundedInventory.exactKeys(file, "floor-upgrade-lineage", "relativePath", "state");
			String relative = string(file, "relativePath");
			WorldBuilderPortablePath.require(relative, "floor-upgrade-lineage");
			Map<String,Object> state = object(file.get("state"));
			WorldBuilderBoundedInventory.exactKeys(state, "floor-upgrade-lineage", "present", "size", "sha256");
			if (!(state.get("present") instanceof Boolean) || !(state.get("size") instanceof Long)
				|| ((Long)state.get("size")).longValue() < 0 || ((Long)state.get("size")).longValue() > WorldBuilderContractLimits.MAX_INVENTORY_FILE_BYTES
				|| Boolean.TRUE.equals(state.get("present")) && !WorldBuilderBoundedInventory.isHash(string(state, "sha256"))
				|| Boolean.FALSE.equals(state.get("present")) && (!Long.valueOf(0).equals(state.get("size")) || !"".equals(state.get("sha256"))))
				throw refusal("Inherited file state is malformed.");
			if (result.put(relative, state) != null) throw refusal("Inherited file inventory repeats a path.");
		}
		return result;
	}
	private static List<Object> fileList(Map<String,Object> states) {
		List<Object> result = new ArrayList<Object>();
		for (Map.Entry<String,Object> item : states.entrySet()) {
			Map<String,Object> row = new LinkedHashMap<String,Object>();
			row.put("relativePath", item.getKey()); row.put("state", item.getValue()); result.add(row);
		}
		return result;
	}
	private static void uuid(String value) throws WorldBuilderContractException {
		try { if (!UUID.fromString(value).toString().equals(value)) throw new IllegalArgumentException(); }
		catch (IllegalArgumentException malformed) { throw refusal("Invalid predecessor UUID."); }
	}
	private static String string(Map<String,Object> value, String key) throws WorldBuilderContractException { return WorldBuilderAdaptiveExporter.string(value, key); }
	private static Map<String,Object> object(Object value) throws WorldBuilderContractException { return WorldBuilderAdaptiveExporter.object(value, "floor-upgrade-lineage"); }
	private static Map<String,Object> read(Path path) throws IOException, WorldBuilderContractException {
		try { return WorldBuilderJsonDocuments.readObject(path); }
		catch (WorldBuilderDiscoveryException malformed) { throw refusal("Malformed predecessor evidence."); }
	}
	private static WorldBuilderContractException refusal(String message) { return new WorldBuilderContractException(
		WorldBuilderErrorCodes.TARGET_DRIFT, "floor-upgrade-lineage", PATH, false, message,
		"Keep the original project and its transaction evidence intact; restore the exact installed state before retrying."); }
	private WorldBuilderFloorUpgradeLineage() { }
}
