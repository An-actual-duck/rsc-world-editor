package com.openrsc.worldbuilder;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Observation-only producer metadata transition after independent archive equivalence. */
final class WorldBuilderProducerArchiveReverification {
  static final String FIELD = "producerArchiveBinding";
  private static final String CONTENT = "content/producer/archive-binding.json";
  private static final String CLIENT = "Client_Base/Open_RSC_Client.jar";

  static Map<String, Object> prepare(
      WorldBuilderAdaptiveProjectLifecycle.VerifiedProject project,
      Path target,
      String id,
      List<Object> references,
      Set<String> verifiedArchives,
      Map<String, String> verifiedInputs)
      throws IOException, WorldBuilderContractException {
    Retained retained = retained(project, references);
    if (retained == null) return null;
    Path live = WorldBuilderReadOnlyTarget.open(target).requiredFile(retained.path);
    byte[] current =
        WorldBuilderNpcProducerV2.readBounded(live, WorldBuilderNpcProducerV2.MAX_MANIFEST);
    if (state(current).equals(retained.state))
      return null; // The supported reverify-before-export order.
    WorldBuilderNpcProducerV2.Capture capture =
        WorldBuilderNpcProducerV2.discover(
            WorldBuilderReadOnlyTarget.open(target),
            WorldBuilderPackedSourceLayout.select(
                WorldBuilderReadOnlyTarget.open(target), configuration(project)));
    if (capture == null || !retained.path.equals(capture.manifestPath))
      throw refusal("Producer identity changed during runtime re-verification.");
    Map<String, Object> proofInputs = new TreeMap<String, Object>();
    for (String archive : verifiedArchives)
      if (verifiedInputs.containsKey(archive))
        proofInputs.put(archive, verifiedInputs.get(archive));
    compare(retained.bytes, current, proofInputs);
    // Every generation is also checked against the immutable first capture;
    // a forged intermediate receipt cannot introduce new producer semantics.
    compare(retained.original, current, proofInputs);
    read(target, retained.path, state(current));
    boolean captured = false;
    for (WorldBuilderReadOnlyTarget.FileState file : capture.evidence)
      if (retained.path.equals(file.relativePath))
        captured =
            file.present
                && file.size == current.length
                && file.sha256.equals(WorldBuilderHashes.sha256(current));
    if (!captured) throw refusal("Producer changed during independent closure verification.");
    Map<String, Object> result = new LinkedHashMap<String, Object>();
    result.put("relativePath", retained.path);
    result.put("before", retained.state);
    result.put("after", state(current));
    result.put("beforeContentRelativePath", retained.contentPath);
    result.put("contentRelativePath", content(id));
    if (retained.reference != null) result.put("predecessor", retained.reference);
    return result;
  }

  static void validate(Object raw) throws WorldBuilderContractException {
    Map<String, Object> value = object(raw);
    List<String> keys =
        new ArrayList<String>(
            Arrays.asList(
                "relativePath",
                "before",
                "after",
                "beforeContentRelativePath",
                "contentRelativePath"));
    if (value.containsKey("predecessor")) {
      keys.add("predecessor");
      WorldBuilderRuntimeUpgradeHistory.validateShape(
          Collections.singletonList(value.get("predecessor")));
    }
    WorldBuilderBoundedInventory.exactKeys(value, FIELD, keys.toArray(new String[0]));
    String path = string(value, "relativePath");
    if (!paths().contains(path)) throw refusal("Unsupported retained producer path.");
    for (String key : Arrays.asList("beforeContentRelativePath", "contentRelativePath"))
      WorldBuilderPortablePath.require(string(value, key), FIELD);
    for (String key : Arrays.asList("before", "after")) {
      Map<String, Object> state = object(value.get(key));
      WorldBuilderBoundedInventory.exactKeys(state, FIELD, "present", "size", "sha256");
      long size = WorldBuilderAdaptiveExporter.integer(state, "size");
      if (!Boolean.TRUE.equals(state.get("present"))
          || size < 2
          || size > WorldBuilderNpcProducerV2.MAX_MANIFEST
          || !WorldBuilderBoundedInventory.isHash(string(state, "sha256")))
        throw refusal("Invalid retained producer byte state.");
    }
    if (value.get("before").equals(value.get("after")))
      throw refusal("Producer binding transition does not change its observed bytes.");
  }

  static void replay(
      WorldBuilderAdaptiveProjectLifecycle.VerifiedProject project,
      Map<String, Object> plan,
      Map<String, Object> proof,
      Map<String, Object> expected)
      throws IOException, WorldBuilderContractException {
    Map<String, Object> evidence = object(plan.get(WorldBuilderRuntimeReverification.FIELD));
    if (!evidence.containsKey(FIELD)) return;
    Map<String, Object> value = object(evidence.get(FIELD));
    validate(value);
    List<Object> references = new ArrayList<Object>();
    if (plan.containsKey(WorldBuilderRuntimeUpgradeHistory.FIELD))
      references.addAll(array(plan.get(WorldBuilderRuntimeUpgradeHistory.FIELD)));
    Retained retained = retained(project, references);
    if (retained == null
        || !retained.path.equals(value.get("relativePath"))
        || !retained.state.equals(value.get("before"))
        || !retained.contentPath.equals(value.get("beforeContentRelativePath"))
        || !Objects.equals(retained.reference, value.get("predecessor"))
        || !content(string(plan, "transactionId")).equals(value.get("contentRelativePath")))
      throw refusal("Producer transition differs from its authenticated predecessor.");
    byte[] bytes =
        read(project.projectRoot, string(value, "contentRelativePath"), object(value.get("after")));
    Map<String, Object> archives = new TreeMap<String, Object>();
    Map<String, Object> proofInputs = object(proof.get("beforeInputs"));
    for (Object raw : array(proof.get("archives"))) {
      String path = string(object(raw), "relativePath");
      if (proofInputs.containsKey(path)) archives.put(path, proofInputs.get(path));
    }
    compare(retained.bytes, bytes, archives);
    compare(retained.original, bytes, archives);
    expected.put(retained.path, value.get("after"));
  }

  static void retainedContent(
      WorldBuilderAdaptiveProjectLifecycle.VerifiedProject project,
      Map<String, Object> plan,
      Map<String, byte[]> generated)
      throws IOException, WorldBuilderContractException {
    if (!plan.containsKey(WorldBuilderRuntimeReverification.FIELD)) return;
    Map<String, Object> evidence = object(plan.get(WorldBuilderRuntimeReverification.FIELD));
    if (!evidence.containsKey(FIELD)) return;
    Map<String, Object> value = object(evidence.get(FIELD));
    validate(value);
    if (!content(string(plan, "transactionId")).equals(value.get("contentRelativePath")))
      throw refusal("Producer evidence path differs from its transaction.");
    generated.put(
        string(value, "relativePath"),
        read(
            project.projectRoot, string(value, "contentRelativePath"), object(value.get("after"))));
  }

  static long evidenceBytes(WorldBuilderAdaptiveMutationProfile.Plan plan)
      throws WorldBuilderContractException {
    Map<String, Object> value = binding(plan);
    return value == null
        ? 0
        : WorldBuilderAdaptiveExporter.integer(object(value.get("after")), "size");
  }

  static void writeEvidence(WorldBuilderAdaptiveMutationProfile.Plan plan, Path backupRoot)
      throws IOException, WorldBuilderContractException {
    Map<String, Object> value = binding(plan);
    if (value == null) return;
    validate(value);
    if (!content(plan.transactionId()).equals(value.get("contentRelativePath")))
      throw refusal("Producer evidence path differs from transaction identity.");
    byte[] bytes = read(plan.targetRoot, string(value, "relativePath"), object(value.get("after")));
    Path output = WorldBuilderPortablePath.resolveContained(backupRoot, CONTENT, FIELD);
    Files.createDirectories(output.getParent());
    Files.write(output, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    WorldBuilderAdaptiveDurability.forceFile(output);
    read(
        plan.project.projectRoot, string(value, "contentRelativePath"), object(value.get("after")));
  }

  private static Map<String, Object> binding(WorldBuilderAdaptiveMutationProfile.Plan plan)
      throws WorldBuilderContractException {
    if (!plan.document.containsKey(WorldBuilderRuntimeReverification.FIELD)) return null;
    Map<String, Object> evidence =
        object(plan.document.get(WorldBuilderRuntimeReverification.FIELD));
    return evidence.containsKey(FIELD) ? object(evidence.get(FIELD)) : null;
  }

  private static String configuration(WorldBuilderAdaptiveProjectLifecycle.VerifiedProject project)
      throws WorldBuilderContractException {
    Set<String> paths = new TreeSet<String>();
    for (String group : Arrays.asList("originalFiles", "definitionRuntimeFiles"))
      for (Object raw : array(project.snapshot.get(group))) {
        Map<String, Object> row = object(raw);
        String path = string(row, "relativePath");
        if ("server-runtime-config".equals(row.get("role"))
            && Boolean.TRUE.equals(row.get("present"))
            && path.startsWith("source/original/"))
          paths.add(path.substring("source/original/".length()));
      }
    if (paths.size() != 1)
      throw refusal(
          "Producer re-verification requires one authenticated maintained content configuration.");
    return paths.iterator().next();
  }

  private static Retained retained(
      WorldBuilderAdaptiveProjectLifecycle.VerifiedProject project, List<Object> references)
      throws IOException, WorldBuilderContractException {
    Retained result = null;
    for (String family : Arrays.asList("originalFiles", "definitionRuntimeFiles"))
      for (Object raw : array(project.snapshot.get(family))) {
        Map<String, Object> row = object(raw);
        String relative = string(row, "relativePath");
        if (!relative.startsWith("source/original/")) continue;
        String path = relative.substring("source/original/".length());
        if (!paths().contains(path)) continue;
        Map<String, Object> state = new LinkedHashMap<String, Object>();
        for (String key : Arrays.asList("present", "size", "sha256")) state.put(key, row.get(key));
        if (result != null) {
          if (!result.path.equals(path) || !result.state.equals(state))
            throw refusal("Snapshot repeats producer authority.");
          continue;
        }
        byte[] bytes = read(project.projectRoot, relative, state);
        result = new Retained(path, relative, state, bytes, bytes, null);
      }
    if (result == null) return null;
    Set<String> seen = new HashSet<String>();
    for (Object raw : references) {
      Map<String, Object> ref = object(raw);
      String id = string(ref, "transactionId");
      if (!seen.add(id)) throw refusal("Producer predecessor is repeated.");
      Map<String, Object> plan = WorldBuilderRuntimeReverification.readPlan(project, id);
      WorldBuilderAdaptiveReceipt.State receipt =
          WorldBuilderAdaptiveReceipt.read(project.projectRoot.resolve("receipts/" + id + ".json"));
      if (!"successful".equals(receipt.status())
          || !ref.equals(WorldBuilderRuntimeReverification.reference(project, receipt)))
        throw refusal("Producer predecessor receipt differs.");
      if (!plan.containsKey(WorldBuilderRuntimeReverification.FIELD)) continue;
      Map<String, Object> evidence = object(plan.get(WorldBuilderRuntimeReverification.FIELD));
      if (!evidence.containsKey(FIELD)) continue;
      Map<String, Object> value = object(evidence.get(FIELD));
      validate(value);
      if (!result.path.equals(value.get("relativePath"))
          || !result.state.equals(value.get("before"))
          || !result.contentPath.equals(value.get("beforeContentRelativePath"))
          || !Objects.equals(result.reference, value.get("predecessor"))
          || !content(id).equals(value.get("contentRelativePath")))
        throw refusal("Producer history is discontinuous.");
      byte[] bytes = read(project.projectRoot, content(id), object(value.get("after")));
      result =
          new Retained(
              result.path, content(id), object(value.get("after")), bytes, result.original, ref);
    }
    return result;
  }

  private static void compare(byte[] before, byte[] after, Map<String, Object> verifiedArchives)
      throws WorldBuilderContractException {
    WorldBuilderNpcProducerV2.Document old = decode(before), current = decode(after);
    boolean bound = false;
    for (Map<String, Object> source : old.sources.values()) {
      String path = string(source, "relativePath");
      if ("client-visual-archive".equals(source.get("role"))
          && CLIENT.equals(path)
          && verifiedArchives.containsKey(path)) {
        String hash = (String) verifiedArchives.get(path);
        if (!WorldBuilderBoundedInventory.isHash(hash))
          throw refusal("Archive equivalence has no valid hash binding.");
        source.put("sha256", hash);
        bound = true;
        for (Map<String, Object> probe : old.probes.values())
          if ("archive-entry".equals(probe.get("kind"))
              && path.equals(probe.get("archiveRelativePath"))) probe.put("archiveSha256", hash);
      }
    }
    if (!bound
        || !WorldBuilderAdaptiveExporter.canonicalHash(old.value)
            .equals(WorldBuilderAdaptiveExporter.canonicalHash(current.value)))
      throw refusal(
          "The refreshed NPC producer changes more than independently verified archive hash"
              + " bindings. Preserve definitions, frames, probes and configuration; review content"
              + " changes separately after runtime re-verification.");
  }

  private static WorldBuilderNpcProducerV2.Document decode(byte[] bytes)
      throws WorldBuilderContractException {
    try {
      return WorldBuilderNpcProducerV2.parse(
          WorldBuilderJsonDocuments.readTargetDefinitionObject(bytes, FIELD));
    } catch (WorldBuilderDiscoveryException invalid) {
      throw refusal("Retained producer JSON is malformed.");
    }
  }

  private static byte[] read(Path root, String relative, Map<String, Object> expected)
      throws IOException, WorldBuilderContractException {
    byte[] bytes =
        WorldBuilderNpcProducerV2.readBounded(
            WorldBuilderReadOnlyTarget.open(root).requiredFile(relative),
            WorldBuilderNpcProducerV2.MAX_MANIFEST);
    if (!state(bytes).equals(expected))
      throw refusal("Retained producer byte evidence changed: " + relative);
    return bytes;
  }

  private static Map<String, Object> state(byte[] bytes) {
    Map<String, Object> state = new LinkedHashMap<String, Object>();
    state.put("present", true);
    state.put("size", Long.valueOf(bytes.length));
    state.put("sha256", WorldBuilderHashes.sha256(bytes));
    return state;
  }

  private static List<String> paths() {
    return Arrays.asList(
        "world-builder-provider/" + WorldBuilderNpcProducerV2.FILE,
        "server/conf/world-builder/" + WorldBuilderNpcProducerV2.FILE);
  }

  private static String content(String id) {
    return "backups/" + id + "/" + CONTENT;
  }

  private static Map<String, Object> object(Object value) throws WorldBuilderContractException {
    return WorldBuilderAdaptiveExporter.object(value, FIELD);
  }

  private static List<?> array(Object value) throws WorldBuilderContractException {
    return WorldBuilderAdaptiveExporter.array(value, FIELD);
  }

  private static String string(Map<String, Object> value, String key)
      throws WorldBuilderContractException {
    return WorldBuilderAdaptiveExporter.string(value, key);
  }

  private static WorldBuilderContractException refusal(String message) {
    return WorldBuilderRuntimeReverification.refusal(message);
  }

  private static final class Retained {
    final String path, contentPath;
    final Map<String, Object> state, reference;
    final byte[] bytes, original;

    Retained(
        String path,
        String contentPath,
        Map<String, Object> state,
        byte[] bytes,
        byte[] original,
        Map<String, Object> reference) {
      this.path = path;
      this.contentPath = contentPath;
      this.state = state;
      this.bytes = bytes;
      this.original = original;
      this.reference = reference;
    }
  }
}
