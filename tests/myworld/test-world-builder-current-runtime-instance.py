#!/usr/bin/env python3
"""New-instance topology/closure only; synthetic jars do not claim real launch proof."""
import copy
import hashlib
import json
import os
from pathlib import Path
import sqlite3
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
UUID = "29a14d29-a9b0-4414-bdfd-1c6e2bcb3dc5"
MAP = "migration/output/map/conversion/package"


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(",", ":")).encode()


def write(path, value, mode=0o600):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(value if isinstance(value, bytes) else json.dumps(value).encode())
    path.chmod(mode)


def snapshot(root):
    return {str(path.relative_to(root)): (path.stat().st_mode & 0o7777, sha(path))
            for path in root.rglob("*") if path.is_file()}


class CurrentRuntimeInstanceTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.shared = tempfile.TemporaryDirectory(prefix="editor-instance-harness-")
        cls.classes = Path(cls.shared.name) / "classes"
        cls.classes.mkdir()
        harness = Path(cls.shared.name) / "InstanceHarness.java"
        harness.write_text(r'''package com.openrsc.worldbuilder;
import java.nio.file.*;
import java.util.*;
public final class InstanceHarness {
  @SuppressWarnings("unchecked") public static void main(String[] args) throws Exception {
    Path root = Paths.get(args[1]);
    if ("verify-installed".equals(args[0])) {
      System.out.print(WorldBuilderJsonDocuments.pretty(WorldBuilderCurrentRuntimeInstalledGeneration.readSpecification(root.resolve("target")))); return;
    }
    if ("verify-serialized".equals(args[0]) || "validate-serialized".equals(args[0])) {
      Map<String,Object> serialized = WorldBuilderJsonDocuments.readObject(root.resolve("plan.json"));
      WorldBuilderCurrentRuntimeInstance.InitialOutputPlan plan =
        WorldBuilderCurrentRuntimeInstance.validateInitialOutputPlan(serialized, args[2]);
      // The validation token has detached evidence, not a writable caller-owned map.
      ((List<Object>)serialized.get("outputInventory")).clear();
      if ("verify-serialized".equals(args[0])) WorldBuilderCurrentRuntimeInstance.verifyInitialOutputs(plan, Paths.get(args[3]));
      System.out.print("{\"verified\":true}"); return;
    }
    Map<String,Object> input = WorldBuilderJsonDocuments.readObject(root.resolve("request.json"));
    if ("render".equals(args[0])) {
      System.out.print(WorldBuilderJsonDocuments.pretty(WorldBuilderCurrentRuntimeInstance.renderGeneration(input)));
      return;
    }
    Path stage = Paths.get((String)input.get("stage"));
    Map<String,Path> server = new LinkedHashMap<String,Path>(), client = new LinkedHashMap<String,Path>();
    for (String role : Arrays.asList("server", "client")) {
      Map<String,Object> sources = (Map<String,Object>)input.get(role);
      for (Map.Entry<String,Object> item : sources.entrySet())
        ("server".equals(role) ? server : client).put(item.getKey(), Paths.get((String)item.getValue()));
    }
    WorldBuilderCurrentRuntimeInstance.Plan plan = WorldBuilderCurrentRuntimeInstance.inspectInitial(stage,
      Paths.get((String)input.get("release")), Paths.get((String)input.get("instance")),
      (String)input.get("installationId"), (String)input.get("generationId"),
      WorldBuilderJsonDocuments.readObject(root.resolve("identity.json")),
      WorldBuilderJsonDocuments.readObject(root.resolve("layout.json")),
      (List<Object>)WorldBuilderJsonDocuments.readObject(root.resolve("generated.json")).get("outputs"),
      server, client, "127.0.0.1", 43594);
    Map<String,Object> document = plan.document();
    // Returned documents are detached, not a writable handle to Plan's trusted input.
    ((Map<String,Object>)document.get("specification")).put("host", "corrupted.invalid");
    document = plan.document();
    if ("inspect".equals(args[0])) {
      System.out.print(WorldBuilderJsonDocuments.pretty(document)); return;
    }
    if (input.containsKey("drift")) Files.write(Paths.get((String)input.get("drift")), new byte[]{99}, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    if (input.containsKey("symlink")) Files.createSymbolicLink(Paths.get((String)input.get("symlink")), stage);
    Path output = Paths.get((String)input.get("output"));
    if ("construct-guarded".equals(args[0])) {
      Path target=Paths.get((String)input.get("target"));
      Map<String,Object> ledger=WorldBuilderJsonDocuments.readObject(root.resolve("ledger-template.json"));
      ledger.put("targetInstallationId",input.get("installationId"));
      ledger=WorldBuilderCurrentRuntimeInstalledGeneration.bind(ledger,target,(Map<String,Object>)document.get("specification"),
        WorldBuilderJsonDocuments.readObject(root.resolve("identity.json")),WorldBuilderJsonDocuments.readObject(stage.resolve("migration/output/map/conversion/package/manifest.json")));
      Map<String,Object> generation=(Map<String,Object>)document.get("generation");
      WorldBuilderCurrentRuntimeCutover.Plan cutover=WorldBuilderCurrentRuntimeCutover.inspect(target,"initial","","",
        WorldBuilderJsonDocuments.pretty(generation.get("activeSelection")).getBytes(java.nio.charset.StandardCharsets.UTF_8),
        WorldBuilderJsonDocuments.pretty(ledger).getBytes(java.nio.charset.StandardCharsets.UTF_8));
      WorldBuilderCurrentRuntimeCutover.journal(cutover,root.resolve("cutover-journal"));
      WorldBuilderCurrentRuntimeInstance.materializeGuarded(plan,output,cutover);
      WorldBuilderCurrentRuntimeInstance.verifyGuardedNew(plan,output,cutover);
      if(!Files.exists(output.resolve("installation/pending-cutover.json"))) throw new AssertionError("startup guard absent");
      try(WorldBuilderCurrentRuntimeInstanceLease lease=WorldBuilderCurrentRuntimeInstanceLease.acquire(output.resolve("installation"))) {
        new WorldBuilderCurrentRuntimeCutover().apply(cutover,root.resolve("cutover-journal"),lease);
      }
      WorldBuilderCurrentRuntimeInstance.verifyNew(plan,output);
      if(!document.get("specification").equals(WorldBuilderCurrentRuntimeInstalledGeneration.readSpecification(target))) throw new AssertionError("installed generation differs");
      System.out.print(WorldBuilderJsonDocuments.pretty(document)); return;
    }
    WorldBuilderCurrentRuntimeInstance.materializeNew(plan, output);
    WorldBuilderCurrentRuntimeInstance.verifyNew(plan, output);
    if ("tamper".equals(args[0])) {
      Files.write(output.resolve("state/server/current_base.db"), new byte[]{1}, StandardOpenOption.APPEND);
      WorldBuilderCurrentRuntimeInstance.verifyNew(plan, output);
    }
    System.out.print(WorldBuilderJsonDocuments.pretty(document));
  }
}
''')
        sources = sorted((ROOT / "tools/world-builder/src").rglob("*.java"))
        subprocess.run(["javac", "-source", "8", "-target", "8", "-d", str(cls.classes),
                        *map(str, sources), str(harness)], check=True, capture_output=True, text=True)
        cls.private = Path(cls.shared.name) / "server.pem"
        cls.public = Path(cls.shared.name) / "client.pem"
        subprocess.run(["openssl", "genpkey", "-algorithm", "RSA", "-pkeyopt", "rsa_keygen_bits:2048", "-out", str(cls.private)],
                       check=True, capture_output=True)
        subprocess.run(["openssl", "pkey", "-in", str(cls.private), "-pubout", "-out", str(cls.public)], check=True, capture_output=True)

    @classmethod
    def tearDownClass(cls):
        cls.shared.cleanup()

    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="editor-instance-fixture-")
        self.root = Path(self.temporary.name)
        write(self.root / "ledger-template.json", json.loads((ROOT / "tests/fixtures/current-runtime-upgrade-v1/targets/managed-n/.world-builder/runtime-ledger-v1.json").read_text()))
        self.stage = self.root / "release"
        self.instance = self.root / "instance"
        for path, data in {"installed/server/core.jar": b"fixture-server", "installed/server/plugins.jar": b"fixture-plugins",
                           "installed/client/Open_RSC_Client.jar": b"fixture-client", "installed/client/Cache/visual.dat": b"fixture-visual"}.items():
            write(self.stage / path, data)
        state_policy = {"contractId": "canonical-public-state-v1", "durableLocation": "outside-code-runtime",
                        "migration": "transactional", "rollback": "exact-predecessor", "sqliteRootProperty": "openrsc.currentBaseStateRoot",
                        "sqliteFile": "current_base.db", "sqliteRootPolicy": "required-canonical-private-directory-disjoint-from-runtime",
                        "sqliteOpenPolicy": "existing-private-file-read-write-no-create"}
        map_policy = {"rootProperty": "openrsc.worldBuilderInstalledMapRoot", "externalRootPolicy": "canonical-absolute-directory-disjoint-from-runtime",
                      "profileBinding": "manifest-sha256-and-package-identity", "defaultLocation": "profile-relative-package"}
        profile = {"variantId": "current-base-v1", "installedLaunch": {}, "advancedExclusions": {"configuration": {"want_myworld": False}}}
        write(self.stage / "runtime/profile.json", profile)
        write(self.stage / "migration/output/launch/current-base.conf", b"db_type: sqlite\ndb_name: current_base\nallow_in_game_world_editor: false\nwant_feature_websockets: false\nserver_port: 43594\nwant_myworld: false\n")
        write(self.stage / MAP / "manifest.json", {"packageId": "fixture-map", "packageVersion": "1.0.0"})
        write(self.stage / MAP / "terrain/tile.raw", b"fixture-map-bytes")
        records = []
        for path in sorted((self.stage / MAP).rglob("*")):
            if path.is_file():
                records.append(f"{path.relative_to(self.stage / MAP)}\0{path.stat().st_size}\0{sha(path)}\n")
        self.map_hash = hashlib.sha256("".join(records).encode()).hexdigest()
        for role in ("server", "client"):
            write(self.stage / f"migration/output/launch/installed-{role}.json", {
                "schemaVersion": 1, "manifestType": f"world-builder-installed-{role}-profile", "active": True,
                "packageId": "fixture-map", "packageVersion": "1.0.0", "packageFingerprintSha256": self.map_hash,
                "manifestSha256": sha(self.stage / MAP / "manifest.json"), "packageRelativePath": "../map/conversion/package"})
        db = self.stage / "migration/output/state/current-base.db"
        db.parent.mkdir(parents=True)
        with sqlite3.connect(db) as connection:
            connection.execute("create table preserved(value text)")
            connection.execute("insert into preserved values ('existing-player-state')")
        db.chmod(0o600)
        write(db.with_name("current-base-migration-evidence.json"), {"fixture": "sealed"})
        generated = []
        for name in ("current-base-migration-evidence.json", "current-base.db"):
            path = db.with_name(name)
            generated.append({"relativePath": str(path.relative_to(self.stage)), "size": path.stat().st_size, "sha256": sha(path), "mode": "0600"})
        write(self.root / "generated.json", {"outputs": generated})
        roles = {"server-runtime": "installed/server/core.jar", "server-plugins": "installed/server/plugins.jar",
                 "client-runtime": "installed/client/Open_RSC_Client.jar", "runtime-profile": "runtime/profile.json"}
        write(self.root / "identity.json", {"installable": True, "variantId": "current-base-v1",
              "bundleInventory": [{"role": role, "sha256": sha(self.stage / path)} for role, path in roles.items()]})
        ledger = json.loads((self.root / "ledger-template.json").read_text())
        identity = json.loads((self.root / "identity.json").read_text())
        for field in ("platformReleaseId", "platformManifestHash", "schemaSetHash", "variantManifestHash", "moduleSetHash",
                      "bundleInventoryHash", "bundleSpecId", "bundleSpecHash", "inputAdapterContractId"):
            identity[field] = ledger[field]
        ledger["variantId"] = "current-base-v1"
        ledger["activeMapPackageId"] = "fixture-map"
        write(self.root / "ledger-template.json", ledger)
        write(self.root / "identity.json", identity)
        outputs = [{"relativePath": str(path.relative_to(self.stage)), "kind": "fixture", "source": "fixture", "size": path.stat().st_size,
                    "sha256": sha(path), "mode": "0600"} for path in sorted((self.stage / "installed").rglob("*")) if path.is_file()]
        write(self.root / "layout.json", {"layoutId": "current-base-runnable-layout-v1", "serverRootRelativePath": "installed/server",
              "clientRootRelativePath": "installed/client", "outputs": outputs, "outputInventoryHash": hashlib.sha256(canonical(outputs)).hexdigest(),
              "ready": True, "statePolicy": state_policy, "mapPolicy": map_policy})
        self.request = {"stage": str(self.stage), "release": str(self.stage), "instance": str(self.instance), "output": str(self.instance),
                        "installationId": UUID, "generationId": "first-generation", "server": {}, "client": {}}
        server_names = ("server.pem", "client.pem", "badwords.txt", "goodwords.txt", "alertwords.txt", "ipbans.txt", "ipbans.temp")
        client_names = ("client.pem", "clientSettings.conf", "uid.dat", "hideIp.txt", "credentials.txt")
        for role, names in (("server", server_names), ("client", client_names)):
            for name in names:
                path = self.root / "side" / role / name
                self.request[role][name] = str(path)
                if name in ("ipbans.temp", "hideIp.txt"):
                    continue  # Exact known absence, not an omitted field.
                data = self.private.read_bytes() if name == "server.pem" else self.public.read_bytes() if name == "client.pem" else b"preserved-" + name.encode()
                write(path, data)

    def tearDown(self):
        self.temporary.cleanup()

    def invoke(self, action="inspect", request=None, success=True):
        write(self.root / "request.json", self.request if request is None else request)
        result = subprocess.run(["java", "-cp", str(self.classes), "com.openrsc.worldbuilder.InstanceHarness", action, str(self.root)],
                                capture_output=True, text=True, timeout=60)
        if success:
            self.assertEqual(0, result.returncode, result.stderr)
            return json.loads(result.stdout)
        self.assertNotEqual(0, result.returncode)
        return result

    def test_private_new_instance_readback_and_immutable_sources(self):
        before_stage = snapshot(self.stage)
        before_side = snapshot(self.root / "side")
        plan = self.invoke("construct")
        self.assertEqual(before_stage, snapshot(self.stage))
        self.assertEqual(before_side, snapshot(self.root / "side"))
        self.assertEqual("127.0.0.1", plan["specification"]["host"])
        pointer = json.loads((self.instance / "installation/active-launch.json").read_text())
        self.assertEqual(UUID, pointer["installationId"])
        self.assertEqual({"schemaVersion", "manifestType", "installationId", "serverDescriptorSha256", "clientDescriptorSha256"}, set(pointer))
        for role in ("server", "client"):
            descriptor = self.instance / f"generations/first-generation/{role}-launch.json"
            self.assertEqual(sha(descriptor), pointer[f"{role}DescriptorSha256"])
            document = json.loads(descriptor.read_text())
            self.assertNotIn("databaseSha256", document)
            self.assertEqual(str(self.instance / "state" / role), document["stateRoot"])
            self.assertEqual([], list((self.instance / "working" / role).iterdir()))
            self.assertEqual(0, (self.instance / "installation" / f"{role}.lock").stat().st_size)
        for path in self.instance.rglob("*"):
            self.assertEqual(0o700 if path.is_dir() else 0o600, path.stat().st_mode & 0o7777)
        self.assertEqual(sha(self.stage / "migration/output/state/current-base.db"), sha(self.instance / "state/server/current_base.db"))
        self.assertFalse((self.instance / "state/server/side/ipbans.temp").exists())
        self.assertEqual(2, len(plan["absentSideStateSources"]))
        with sqlite3.connect(self.instance / "state/server/current_base.db") as connection:
            connection.execute("insert into preserved values ('normal-gameplay')")
        self.assertEqual(before_stage, snapshot(self.stage))
        self.assertEqual(pointer, json.loads((self.instance / "installation/active-launch.json").read_text()))

    def test_initial_guarded_construction_and_metadata_commit(self):
        target = self.root / "target"
        parent = target / ".world-builder/current-runtime"
        parent.mkdir(parents=True)
        self.instance = parent / "instance"
        self.request.update(target=str(target), instance=str(self.instance), output=str(self.instance))
        before_stage = snapshot(self.stage)
        before_side = snapshot(self.root / "side")
        self.invoke("construct-guarded")
        self.assertFalse((self.instance / "installation/pending-cutover.json").exists())
        self.assertTrue((self.root / "cutover-journal/commit.json").exists())
        self.assertEqual(UUID, json.loads((target / ".world-builder/runtime-ledger-v1.json").read_text())["targetInstallationId"])
        self.assertEqual(before_stage, snapshot(self.stage))
        self.assertEqual(before_side, snapshot(self.root / "side"))
        with sqlite3.connect(self.instance / "state/server/current_base.db") as connection:
            connection.execute("insert into preserved values ('postcommit-gameplay')")
        (self.stage / "migration/output/state/current-base.db").write_bytes(b"retired historical snapshot")
        specification = self.invoke("verify-installed")
        self.assertEqual(str(self.instance / "state/server"), specification["serverStateRoot"])
        ledger_path = target / ".world-builder/runtime-ledger-v1.json"
        original = json.loads(ledger_path.read_text())
        for field in ("platformReleaseId", "bundleInventoryHash", "activeMapPackageId"):
            changed = dict(original)
            changed[field] = "a" * 64 if field.endswith("Hash") else "wrong-generation"
            changed["ledgerFingerprintSha256"] = "0" * 64
            changed["ledgerFingerprintSha256"] = hashlib.sha256(canonical(changed)).hexdigest()
            write(ledger_path, changed)
            self.invoke("verify-installed", success=False)
        write(ledger_path, original)
        descriptor = self.instance / "generations/first-generation/client-launch.json"
        descriptor.write_bytes(descriptor.read_bytes() + b" ")
        self.invoke("verify-installed", success=False)

    def test_final_projection_can_precede_release_and_instance_publication(self):
        self.request["release"] = str(self.root / "future" / "release")
        self.request["instance"] = str(self.root / "future" / "instance")
        self.request["output"] = str(self.root / "temporary-instance")
        plan = self.invoke("construct")
        descriptor = plan["generation"]["serverDescriptor"]
        self.assertEqual(str(self.root / "future/release/installed/server"), descriptor["codeRoot"])
        self.assertEqual(str(self.root / "future/instance/state/server"), descriptor["stateRoot"])
        self.assertFalse((self.root / "future").exists())

    def test_staging_cannot_overlap_projected_immutable_release(self):
        self.request["release"] = str(self.root / "future-release")
        for path in (self.root / "future-release", self.root / "future-release/installed/server",
                     self.root / "future-release/migration/output/map/conversion/package",
                     self.root / "future-release/runtime/profile.json"):
            with self.subTest(path=path):
                self.request["output"] = str(path)
                self.invoke("construct", success=False)
                self.assertFalse((self.root / "future-release").exists())
        # Existing canonical parent exercises the overlap check rather than missing-parent refusal.
        (self.root / "future-release").mkdir()
        self.request["output"] = str(self.root / "future-release/unpublished-output")
        self.invoke("construct", success=False)
        self.assertEqual([], list((self.root / "future-release").iterdir()))

    def test_pure_map_and_runtime_generations_preserve_selected_mutable_paths(self):
        spec = self.invoke()["specification"]
        prior = self.invoke("render", spec)
        changed = copy.deepcopy(spec)
        changed["generationId"] = "map-only"
        changed["mapRoot"] = str(self.root / "future-map")
        changed["mapPackageFingerprintSha256"] = "a" * 64
        after = self.invoke("render", changed)
        for role in ("server", "client"):
            for key in ("stateRoot", "sideStateRoot", "codeRoot", "workingRoot"):
                self.assertEqual(prior[f"{role}Descriptor"][key], after[f"{role}Descriptor"][key])
        self.assertNotEqual(prior["activeSelection"], after["activeSelection"])
        changed["generationId"] = "next-runtime"
        changed["serverCodeRoot"] = str(self.root / "next-code")
        changed["serverStateRoot"] = str(self.root / "next-state")
        upgraded = self.invoke("render", changed)
        self.assertEqual(prior["serverDescriptor"]["sideStateRoot"], upgraded["serverDescriptor"]["sideStateRoot"])
        self.assertFalse(self.instance.exists())
        for key, value in (("unexpected", True), ("serverStateRoot", changed["serverCodeRoot"]), ("installationId", "not-uuid"), ("mapRoot", "relative")):
            invalid = copy.deepcopy(changed); invalid[key] = value
            self.invoke("render", invalid, success=False)

    def test_existing_output_and_aliases_refused_without_source_changes(self):
        baseline = snapshot(self.stage)
        self.instance.mkdir()
        self.invoke("construct", success=False)
        self.instance.rmdir()
        self.request["output"] = str(self.stage / "intruder")
        self.invoke("construct", success=False)
        self.assertFalse((self.stage / "intruder").exists())
        self.request["output"] = str(self.instance)
        alias = self.root / "alias"
        alias.symlink_to(self.root / "side", target_is_directory=True)
        self.request["server"]["server.pem"] = str(alias / "server/server.pem")
        self.invoke(success=False)
        self.request["server"]["server.pem"] = str(self.root / "side/server/server.pem")
        hardlink = self.root / "hardlink.pem"
        os.link(self.request["server"]["server.pem"], hardlink)
        self.invoke(success=False)
        hardlink.unlink()
        self.assertEqual(baseline, snapshot(self.stage))
        self.assertFalse(self.instance.exists())

    def test_source_and_known_absence_drift_prevent_creation(self):
        self.request["drift"] = self.request["server"]["ipbans.temp"]
        self.invoke("construct", success=False)
        self.assertFalse(self.instance.exists())
        Path(self.request["drift"]).unlink()
        self.request["drift"] = self.request["server"]["badwords.txt"]
        self.invoke("construct", success=False)
        self.assertFalse(self.instance.exists())

    def test_key_mismatch_omitted_policy_and_unsafe_configuration_refused(self):
        missing = copy.deepcopy(self.request)
        del missing["server"]["ipbans.temp"]
        self.invoke(request=missing, success=False)
        key = Path(self.request["server"]["server.pem"])
        original = key.read_bytes()
        key.write_bytes(b"not a preserved private key")
        self.invoke(success=False)
        key.write_bytes(original)
        config = self.stage / "migration/output/launch/current-base.conf"
        config.write_bytes(config.read_bytes().replace(b"db_type: sqlite", b"db_type: mariadb"))
        self.invoke(success=False)
        self.assertFalse(self.instance.exists())

    def test_initial_readback_rejects_mutated_live_copy(self):
        self.invoke("tamper", success=False)
        self.assertTrue(self.instance.is_dir())  # No destructive cleanup after failure.

    def serialized(self, plan, output=None, confirmed=None, success=True):
        write(self.root / "plan.json", plan)
        args = ["java", "-cp", str(self.classes), "com.openrsc.worldbuilder.InstanceHarness",
                "verify-serialized" if output else "validate-serialized", str(self.root),
                confirmed if confirmed is not None else plan["planFingerprintSha256"]]
        if output:
            args.append(str(output))
        result = subprocess.run(args, capture_output=True, text=True, timeout=60)
        if success:
            self.assertEqual(0, result.returncode, result.stderr)
        else:
            self.assertNotEqual(0, result.returncode)
        return result

    @staticmethod
    def reseal(plan):
        unsigned = {key: value for key, value in plan.items() if key != "planFingerprintSha256"}
        plan["planFingerprintSha256"] = hashlib.sha256(canonical(unsigned)).hexdigest()
        return plan

    def test_recovery_readback_after_source_relocation_and_process_restart(self):
        self.request["release"] = str(self.root / "published-release")
        self.request["instance"] = str(self.root / "published-instance")
        self.request["output"] = str(self.root / "construction-staging")
        plan = self.invoke("construct")
        self.stage.rename(self.root / "published-release")
        (self.root / "construction-staging").rename(self.root / "published-instance")
        # Source paths no longer exist. The new JVM must only inspect exact owned output.
        (self.root / "side").rename(self.root / "relocated-side-sources")
        output = self.root / "published-instance"
        self.serialized(plan, output)
        before = snapshot(output)
        self.serialized(plan, output, confirmed="f" * 64, success=False)
        self.assertEqual(before, snapshot(output))
        with sqlite3.connect(output / "state/server/current_base.db") as connection:
            connection.execute("insert into preserved values ('gameplay-after-construction')")
        changed = snapshot(output)
        self.serialized(plan, output, success=False)
        self.assertEqual(changed, snapshot(output))  # Never rolls back or deletes live state.

    def test_serialized_plan_refuses_forged_closed_metadata_and_inventories(self):
        original = self.invoke("construct")
        self.serialized(original, self.instance)
        mutations = [
            lambda p: p.update(manifestType="untrusted-output-claim"),
            lambda p: p.update(unexpected=True),
            lambda p: p["generation"]["serverDescriptor"].update(role="client"),
            lambda p: p["outputInventory"][1].update(relativePath="../victim"),
            lambda p: p["outputInventory"].append(copy.deepcopy(p["outputInventory"][-1])),
            lambda p: p["outputInventory"][0].update(mode="0755"),
            lambda p: p["copies"][0]["source"].update(size=-1),
            lambda p: p["copies"][0]["source"].update(type="symlink"),
            lambda p: p["absentSideStateSources"][0].update(relativePath="state/server/side/server.pem"),
            lambda p: p["specification"].update(serverCodeTreeSha256="f" * 64),
            lambda p: p.update(projectedInstanceRoot=str(self.root / "wrong-final-root")),
        ]
        before = snapshot(self.instance)
        for index, mutate in enumerate(mutations):
            with self.subTest(index=index):
                plan = copy.deepcopy(original); mutate(plan); self.reseal(plan)
                self.serialized(plan, self.instance, success=False)
        stale = copy.deepcopy(original); stale["failurePolicy"] = "force-cleanup"
        self.serialized(stale, self.instance, success=False)
        self.assertEqual(before, snapshot(self.instance))

    def test_serialized_readback_rejects_extra_linked_or_nonprivate_output(self):
        plan = self.invoke("construct")
        extra = self.instance / "working/server/unreviewed.log"
        write(extra, b"new-live-session")
        self.serialized(plan, self.instance, success=False)
        extra.unlink()
        side = self.instance / "state/client/side/uid.dat"
        side.chmod(0o644)
        self.serialized(plan, self.instance, success=False)
        side.chmod(0o600)
        alias = self.root / "linked-instance"
        alias.symlink_to(self.instance, target_is_directory=True)
        self.serialized(plan, alias, success=False)


if __name__ == "__main__":
    unittest.main()
