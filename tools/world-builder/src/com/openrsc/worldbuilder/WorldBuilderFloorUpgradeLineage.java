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
			WorldBuilderAdaptiveProjectLifecycle.VerifiedProject parent = parent(project, true);
			if (parent == null) return null;
            if (WorldBuilderRuntimeReverification.hasSuccessful(parent))
                throw refusal("A sibling floor upgrade cannot cross a runtime re-verification boundary in this version; continue map editing in the original project.");
			WorldBuilderAdaptiveReceipt.State receipt =
				WorldBuilderAdaptiveImporter.latestOutstandingSuccessfulImport(parent.projectRoot);
			if (receipt == null) return installedParent(parent, target);
			WorldBuilderAdaptiveExporter.VerifiedExport export =
				WorldBuilderAdaptiveUndo.findExport(parent, receipt.exportFingerprint());
			WorldBuilderAdaptiveMutationProfile.Plan installed =
				WorldBuilderAdaptiveMutationProfile.reconstructInstalled(parent, export, target, receipt.transactionId());
			WorldBuilderAdaptiveReceipt.requireSuccessfulImportMatches(installed, receipt);
			WorldBuilderAdaptiveMutationProfile.Plan effective = WorldBuilderAdaptiveUndo.resolveEffectiveInstalledPlan(installed);
			if (effective != installed) throw refusal("Historical relocated predecessor requires a fresh verified target capture before floor upgrade.");
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
        Map<String,Object> runtime = WorldBuilderRuntimeUpgradeHistory.describePredecessors(
            installed.project, installed.targetRoot, installed.document);
        if (!WorldBuilderAdaptiveExporter.array(runtime.get("references"), "references").isEmpty()) {
            result.put(WorldBuilderRuntimeUpgradeHistory.FIELD, runtime.get("references"));
            states.putAll(object(runtime.get("states")));
        }
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
		WorldBuilderAdaptiveProjectLifecycle.VerifiedProject previous = parent(project, false);
		int depth = 0;
		while (previous != null && !previous.projectId.equals(id) && ++depth < 16) previous = parent(previous, false);
		if (previous == null || !previous.projectId.equals(id)
			|| !boundParentFingerprint(project, id).equals(proof.get("projectFingerprintSha256")))
			throw refusal("Inherited target state is not bound to this project's verified predecessor.");
		WorldBuilderAdaptiveReceipt.State latest = successfulReceipt(previous.projectRoot, transaction);
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
        if (proof.containsKey(WorldBuilderRuntimeUpgradeHistory.FIELD)) {
            Map<String,Object> runtime = WorldBuilderRuntimeUpgradeHistory.describePredecessors(
                previous, previous.projectRoot.resolve("source/original"), plan);
            if (!runtime.get("references").equals(proof.get(WorldBuilderRuntimeUpgradeHistory.FIELD)))
                throw refusal("Inherited runtime history differs from the bound predecessor authority.");
            expected.putAll(object(runtime.get("states")));
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
		Map<String,Object> prefix = new LinkedHashMap<String,Object>(proof);
		prefix.remove("successors");
		prefix.put("files", fileList(expected));
		List<Object> accepted = new ArrayList<Object>();
		for (Object successor : successors(proof)) {
			Map<String,Object> reference = object(successor);
			if (!project.projectId.equals(reference.get("projectId")))
				throw refusal("Floor successor belongs to a different project.");
			Map<String,Object> successorPlan = historicalPlan(project.projectRoot, reference);
			if (!prefix.equals(successorPlan.get(FIELD)))
				throw refusal("Floor successor does not extend the exact preceding inherited proof.");
			applySuccessor(expected, successorPlan);
			accepted.add(reference);
			prefix.put("successors", new ArrayList<Object>(accepted));
			prefix.put("files", fileList(expected));
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
		WorldBuilderAdaptiveProjectLifecycle.VerifiedProject project, boolean strict) throws IOException, WorldBuilderContractException {
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
		if (strict && (!reference.get("projectFingerprintSha256").equals(parent.manifest.get("projectFingerprintSha256"))
			|| !reference.get("workingFingerprintSha256").equals(parent.working.fingerprintSha256))
			|| !project.manifest.get("target").equals(parent.manifest.get("target")))
			throw refusal("The retained predecessor no longer matches its bound project identity.");
		return parent;
	}

	static Object advance(WorldBuilderAdaptiveMutationProfile.Plan installed)
		throws IOException, WorldBuilderContractException {
		Object raw = installed.document.get(FIELD);
		if (raw == null) return null;
		Map<String,Object> proof = verifyProof(installed.project, raw);
		Map<String,Object> states = files(proof);
		boolean changes = false;
		for (WorldBuilderAdaptiveMutationProfile.Action action : installed.actions)
			changes |= action.role.startsWith("runtime-compatibility-") && states.containsKey(action.destinationRelativePath)
				&& !states.get(action.destinationRelativePath).equals(action.after.toJson());
		if (!changes) return proof;
		WorldBuilderAdaptiveReceipt.State receipt = successfulReceipt(installed.project.projectRoot, installed.transactionId());
		WorldBuilderAdaptiveReceipt.requireSuccessfulImportMatches(installed, receipt);
		Map<String,Object> reference = new LinkedHashMap<String,Object>();
		reference.put("projectId", installed.project.projectId);
		reference.put("transactionId", installed.transactionId());
		reference.put("receiptSha256", WorldBuilderHashes.sha256(installed.project.projectRoot.resolve("receipts/" + installed.transactionId() + ".json")));
		reference.put("mutationPlanSha256", installed.canonicalSha256);
		Map<String,Object> plan = historicalPlan(installed.project.projectRoot, reference);
		if (!proof.equals(plan.get(FIELD))) throw refusal("Successor source proof changed.");
		applySuccessor(states, plan);
		Map<String,Object> result = new LinkedHashMap<String,Object>(proof);
		List<Object> references = new ArrayList<Object>(successors(proof)); references.add(reference);
		result.put("successors", references); result.put("files", fileList(states)); validateShape(result);
		return result;
	}

	private static void applySuccessor(Map<String,Object> states, Map<String,Object> plan) throws WorldBuilderContractException {
		boolean changed = false;
		for (Object raw : WorldBuilderAdaptiveExporter.array(plan.get("actions"), "actions")) {
			Map<String,Object> action = object(raw);
			String path = string(action, "destinationRelativePath");
			if (!string(action, "role").startsWith("runtime-compatibility-") || !states.containsKey(path)) continue;
			if (!states.get(path).equals(action.get("before"))) throw refusal("Successor before-state differs from inherited authority.");
			states.put(path, action.get("after")); changed = true;
		}
		if (!changed) throw refusal("Successor proof does not replace inherited runtime state.");
	}

	private static List<?> successors(Map<String,Object> proof) throws WorldBuilderContractException {
		if (!proof.containsKey("successors")) return java.util.Collections.emptyList();
		List<?> result = WorldBuilderAdaptiveExporter.array(proof.get("successors"), "successors");
		if (result.isEmpty() || result.size() > 16) throw refusal("Floor successor proof is unbounded.");
		return result;
	}

	private static WorldBuilderAdaptiveReceipt.State successfulReceipt(Path root, String transaction)
		throws IOException, WorldBuilderContractException {
		WorldBuilderAdaptiveReceipt.State found = null;
		for (WorldBuilderAdaptiveReceipt.State receipt : WorldBuilderAdaptiveReceipt.readAll(root)) {
			if (transaction.equals(receipt.transactionId()) && "import".equals(receipt.transactionType()) && "successful".equals(receipt.status())) found = receipt;
			if (transaction.equals(receipt.revertsTransactionId()) && "undo".equals(receipt.transactionType()) && "reverted".equals(receipt.status()))
				throw refusal("Historical floor authority was explicitly reversed.");
		}
		if (found == null) throw refusal("Historical successful transaction is unavailable.");
		return found;
	}

	private static Map<String,Object> historicalPlan(Path root, Map<String,Object> reference)
		throws IOException, WorldBuilderContractException {
		String transaction = string(reference, "transactionId"); uuid(transaction);
		WorldBuilderReadOnlyTarget evidence = WorldBuilderReadOnlyTarget.open(root);
		WorldBuilderAdaptiveReceipt.State receipt = successfulReceipt(root, transaction);
		if (!WorldBuilderHashes.sha256(evidence.requiredFile("receipts/" + transaction + ".json")).equals(reference.get("receiptSha256")))
			throw refusal("Historical successor receipt bytes changed.");
		Map<String,Object> plan = read(evidence.requiredFile("backups/" + transaction + "/mutation-plan.json"));
		WorldBuilderAdaptiveExporter.requireFingerprint(plan, "planFingerprintSha256");
		String hash = WorldBuilderAdaptiveContracts.validateParsed(WorldBuilderAdaptiveContracts.Kind.MUTATION_PLAN, plan).canonicalSha256;
		if (!hash.equals(reference.get("mutationPlanSha256")) || !hash.equals(receipt.document.get("mutationPlanSha256"))
			|| !reference.get("projectId").equals(plan.get("projectId")) || !reference.get("projectId").equals(receipt.document.get("projectId"))
			|| !transaction.equals(plan.get("transactionId")) || !plan.get("exportFingerprintSha256").equals(receipt.document.get("exportFingerprintSha256")))
			throw refusal("Historical successor plan and receipt disagree.");
		return plan;
	}

	private static String boundParentFingerprint(WorldBuilderAdaptiveProjectLifecycle.VerifiedProject project, String id)
		throws IOException, WorldBuilderContractException {
		for (int depth = 0; depth < 16; depth++) {
			Map<String,Object> reference = read(WorldBuilderReadOnlyTarget.open(project.projectRoot).requiredFile(PATH));
			if (id.equals(reference.get("projectId"))) return string(reference, "projectFingerprintSha256");
			project = parent(project, false);
			if (project == null) break;
		}
		throw refusal("Bound predecessor identity was not found.");
	}

	private static Map<String,Object> inheritedFiles(Map<String,Object> plan) throws WorldBuilderContractException {
		return plan.containsKey(FIELD) ? files(object(plan.get(FIELD))) : new TreeMap<String,Object>();
	}
	static void validateShape(Object raw) throws WorldBuilderContractException {
		Map<String,Object> proof = object(raw);
		Map<String,Object> shape = new LinkedHashMap<String,Object>(proof);
		shape.remove("successors");
        if (shape.containsKey(WorldBuilderRuntimeUpgradeHistory.FIELD))
            WorldBuilderRuntimeUpgradeHistory.validateShape(shape.remove(WorldBuilderRuntimeUpgradeHistory.FIELD));
		WorldBuilderBoundedInventory.exactKeys(shape, "floor-upgrade-lineage", "projectId",
			"projectFingerprintSha256", "transactionId", "receiptSha256", "mutationPlanSha256", "files");
		uuid(string(proof, "projectId")); uuid(string(proof, "transactionId"));
		for (String key : new String[] {"projectFingerprintSha256", "receiptSha256", "mutationPlanSha256"})
			if (!WorldBuilderBoundedInventory.isHash(string(proof, key))) throw refusal("Invalid predecessor evidence hash.");
		files(proof);
		for (Object rawSuccessor : successors(proof)) {
			Map<String,Object> successor = object(rawSuccessor);
			WorldBuilderBoundedInventory.exactKeys(successor, "floor-successor", "projectId", "transactionId", "receiptSha256", "mutationPlanSha256");
			uuid(string(successor, "projectId")); uuid(string(successor, "transactionId"));
			for (String key : new String[]{"receiptSha256", "mutationPlanSha256"})
				if (!WorldBuilderBoundedInventory.isHash(string(successor,key))) throw refusal("Invalid successor evidence hash.");
		}
	}
	static Map<String,Object> files(Map<String,Object> proof) throws WorldBuilderContractException {
		Map<String,Object> result = new TreeMap<String,Object>();
		List<?> values = WorldBuilderAdaptiveExporter.array(proof.get("files"), "files");
		if (values.isEmpty() || values.size() > WorldBuilderContractLimits.MAX_INVENTORY_ENTRIES) throw refusal("Inherited file inventory is unbounded.");
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
