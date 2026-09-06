#!/usr/bin/env python3
"""Actual normal launch of Editor-constructed instances; not cutover authorization.

All map/account inputs are invented before discovery and migration. The only UI
helper reused from the pinned provider is manual_login: neither its hand-written
descriptors nor its terminate/kill cleanup implementation are used here.
"""
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import shutil
import socket
import sqlite3
import stat
import subprocess
import tempfile
import time
import unittest
from unittest import mock
import uuid
import zipfile

ROOT = Path(__file__).resolve().parents[2]
PROVIDER = ROOT / ".runtime-provider"
ROLES = ("server", "client")


def load(name, path):
    specification = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(specification)
    specification.loader.exec_module(module)
    return module


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def write(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    payload = value if isinstance(value, bytes) else json.dumps(value).encode()
    with path.open("xb") as output:
        path.chmod(0o600)
        output.write(payload)


def free_port():
    with socket.socket() as listener:
        listener.bind(("127.0.0.1", 0))
        return listener.getsockname()[1]


class InitialInstanceIntegrationTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.shared = tempfile.TemporaryDirectory(prefix="editor-normal-harness-")
        cls.classes = Path(cls.shared.name) / "classes"
        cls.classes.mkdir()
        harness = Path(cls.shared.name) / "InitialInstanceHarness.java"
        harness.write_text(r'''package com.openrsc.worldbuilder;
import java.nio.file.*;
import java.util.*;
public final class InitialInstanceHarness {
  @SuppressWarnings("unchecked") public static void main(String[] args) throws Exception {
    Path root = Paths.get(args[1]);
    if ("lease".equals(args[0])) {
      try (WorldBuilderCurrentRuntimeInstanceLease lease = WorldBuilderCurrentRuntimeInstanceLease.acquire(root)) {
        lease.verifyHeld(); System.out.print("closed");
      } return;
    }
    if ("verify-initial".equals(args[0])) {
      Map<String,Object> plan = WorldBuilderJsonDocuments.readObject(root.resolve("plan.json"));
      WorldBuilderCurrentRuntimeInstance.verifyInitialOutputs(
        WorldBuilderCurrentRuntimeInstance.validateInitialOutputPlan(plan, (String)plan.get("planFingerprintSha256")),
        root.resolve("instance"));
      return;
    }
    Map<String,Object> request = WorldBuilderJsonDocuments.readObject(root.resolve("request.json"));
    Path release = Paths.get((String)request.get("release"));
    Map<String,Object> migration = WorldBuilderJsonDocuments.readObject(Paths.get((String)request.get("migration")));
    Map<String,Object> execution = (Map<String,Object>)migration.get("stagedExecution");
    Map<String,Path> server = new LinkedHashMap<String,Path>(), client = new LinkedHashMap<String,Path>();
    for (String role : Arrays.asList("server", "client"))
      for (Map.Entry<String,Object> entry : ((Map<String,Object>)request.get(role)).entrySet())
        ("server".equals(role) ? server : client).put(entry.getKey(), Paths.get((String)entry.getValue()));
    WorldBuilderCurrentRuntimeInstance.Plan inspected = WorldBuilderCurrentRuntimeInstance.inspectInitial(
      release, release, root.resolve("instance"), (String)request.get("installationId"), "first-generation",
      WorldBuilderJsonDocuments.readObject(Paths.get((String)request.get("identity"))),
      (Map<String,Object>)execution.get("runtimeLayout"), WorldBuilderCurrentRuntimeGeneratedState.capture(release),
      server, client, "127.0.0.1", ((Number)request.get("port")).intValue());
    WorldBuilderCurrentRuntimeInstance.materializeNew(inspected, root.resolve("instance"));
    WorldBuilderCurrentRuntimeInstance.verifyNew(inspected, root.resolve("instance"));
    Map<String,Object> result = new LinkedHashMap<String,Object>();
    result.put("plan", inspected.document());
    // The test command factory consumes the actual generated descriptors and
    // their inventory-bound provider policy. No replacement descriptor is made.
    Map<String,Object> launches = new LinkedHashMap<String,Object>();
    for (String role : Arrays.asList("server", "client")) {
      Path descriptor = root.resolve("instance/generations/first-generation/" + role + "-launch.json");
      Map<String,Object> document = WorldBuilderJsonDocuments.readObject(descriptor);
      Map<String,Object> binding = (Map<String,Object>)document.get("runtimeProfile");
      Map<String,Object> profile = WorldBuilderJsonDocuments.readObject(Paths.get((String)binding.get("path")));
      Map<String,Object> policy = (Map<String,Object>)profile.get("installedLaunch");
      String expected = "server".equals(role) ? "<codeRoot>/core.jar:<codeRoot>/plugins.jar" : "<codeRoot>/Open_RSC_Client.jar";
      if (!expected.equals(policy.get(role + "Classpath"))
          || !Arrays.asList("--launch", "<absolute-descriptor>").equals(policy.get("arguments")))
        throw new AssertionError("Unexpected provider normal-launch command contract");
      List<String> jars = new ArrayList<String>();
      for (String entry : expected.split(":")) jars.add(entry.replace("<codeRoot>", (String)document.get("codeRoot")));
      Map<String,Object> launch = new LinkedHashMap<String,Object>();
      launch.put("command", Arrays.asList(Paths.get(System.getProperty("java.home"), "bin", "java").toString(),
        "-Xms128m", "-Xmx768m", "-cp", String.join(java.io.File.pathSeparator, jars),
        (String)policy.get(role + "MainClass"), "--launch", descriptor.toString()));
      launch.put("workingDirectory", document.get("workingRoot"));
      launches.put(role, launch);
    }
    result.put("launches", launches);
    System.out.print(WorldBuilderJsonDocuments.pretty(result));
  }
}
''')
        sources = sorted((ROOT / "tools/world-builder/src").rglob("*.java"))
        subprocess.run(["javac", "-source", "8", "-target", "8", "-d", str(cls.classes),
                        *map(str, sources), str(harness)], check=True, capture_output=True, text=True)
        shutil.copytree(ROOT / "tools/world-builder/resources", cls.classes, dirs_exist_ok=True)
        cls.upgrade = load("normal_upgrade_fixture", ROOT / "tests/myworld/test-world-builder-current-runtime-upgrade-transaction.py")
        cls.upgrade.CurrentRuntimeUpgradeTransactionTest.setUpClass()
        cls.provider_ui = load("normal_provider_ui", PROVIDER / "tests/myworld/test-current-base-installed-launch.py")

    @classmethod
    def tearDownClass(cls):
        cls.upgrade.CurrentRuntimeUpgradeTransactionTest.tearDownClass()
        cls.shared.cleanup()

    def invoke(self, operation, path, success=True):
        result = subprocess.run(["java", "-cp", str(self.classes), "com.openrsc.worldbuilder.InitialInstanceHarness",
                                 operation, str(path)], capture_output=True, text=True, timeout=60)
        self.assertEqual(success, result.returncode == 0, result.stderr)
        return result

    def log(self, process):
        record = next(record for record in self.processes if record["process"] is process)
        path = record["log"]
        self.assertLessEqual(path.stat().st_size, 1024 * 1024, "Owned launch diagnostic exceeded test bound")
        return path.read_text(errors="replace")

    def binding(self, role, nonce):
        descriptor = self.descriptors[role]
        return {"schemaVersion": 1, "manifestType": "current-base-installed-session", "action": "ready",
                "installationId": descriptor["installationId"], "role": role, "nonce": nonce,
                "descriptorSha256": self.descriptor_hashes[role],
                "compositionIdentitySha256": descriptor["compositionIdentity"]["sha256"],
                "mapManifestSha256": self.map_hashes[role]}

    def regular_private(self, path):
        self.assertEqual(path, path.resolve(strict=True), "Control path must not have a symlink alias")
        info = path.lstat()
        self.assertTrue(stat.S_ISREG(info.st_mode))
        self.assertEqual(1, info.st_nlink)
        self.assertEqual(0o600, stat.S_IMODE(info.st_mode))
        return info.st_dev, info.st_ino

    def admit_session(self, record, require_ready):
        announcements = re.findall(r"^INSTALLED_SESSION ([0-9a-f]{64})$", self.log(record["process"]), re.MULTILINE)
        if not announcements:
            return False
        self.assertEqual(1, len(announcements), "Exactly one fresh announcement from this owned process is required")
        nonce = announcements[0]
        self.assertNotIn(nonce, self.previous_nonces)
        role = record["role"]
        session = Path(self.descriptors[role]["sessionRoot"]) / nonce
        self.assertEqual(session, session.resolve(strict=True))
        self.assertEqual(0o700, stat.S_IMODE(session.stat().st_mode))
        expected = self.binding(role, nonce)
        record["session"], record["binding"] = session, expected
        ready = session / "ready.json"
        if not ready.exists():
            return not require_ready
        self.regular_private(ready)
        self.assertLess(ready.stat().st_size, 4096)
        self.assertEqual(expected, json.loads(ready.read_text()), "Ready is evidence, never independent process ownership")
        return True

    def spawn(self, role, expected_exit=0):
        launch = self.launches[role]
        log = self.root / f"{len(self.processes)}-{role}.log"
        output = log.open("xb")
        log.chmod(0o600)
        try:
            process = subprocess.Popen(launch["command"], cwd=launch["workingDirectory"], stdout=output,
                                       stderr=subprocess.STDOUT, env=self.provider_ui.launch_environment())
        finally:
            output.close()
        record = {"process": process, "role": role, "log": log, "expected_exit": expected_exit}
        self.processes.append(record)
        write(log.with_suffix(".launch.json"), {"role": role, "command": launch["command"],
              "workingDirectory": launch["workingDirectory"], "descriptorSha256": self.descriptor_hashes[role],
              "diagnosticPid": process.pid, "pidIsCleanupAuthority": False, "expectedExit": expected_exit})
        return record

    def start(self, role):
        record = self.spawn(role)
        process = record["process"]
        deadline = time.monotonic() + 120
        while time.monotonic() < deadline:
            if self.admit_session(record, require_ready=True):
                self.assertIsNone(process.poll(), self.log(process)[-4000:])
                return record
            if process.poll() is not None:
                break
            time.sleep(0.05)
        self.fail("Editor-constructed " + role + " failed real readiness: " + self.log(process)[-7000:])

    def stop(self, record):
        process = record["process"]
        if process.poll() is None:
            self.assertTrue(self.admit_session(record, require_ready=False), "Cannot prove session ownership for normal shutdown")
            request = dict(record["binding"], action="shutdown")
            destination = record["session"] / "shutdown.json"
            if "shutdown" not in record:
                write(destination, request)
                record["shutdown"] = (self.regular_private(destination), destination.read_bytes())
            else:
                self.assertEqual(record["shutdown"], (self.regular_private(destination), destination.read_bytes()))
            process.wait(timeout=90)
        self.assertEqual(record["expected_exit"], process.returncode, self.log(process)[-7000:])

    def cleanup_owned(self):
        errors = []
        for record in reversed(self.processes):
            try:
                self.stop(record)
            except Exception as error:
                errors.append(str(error))
        if self.anchor is not None:
            try:
                self.invoke("lease", self.anchor)
                self.assertEqual(self.anchor_ids, {role: self.regular_private(self.anchor / (role + ".lock")) for role in ROLES})
            except Exception as error:
                errors.append(str(error))
        if errors:
            self.fail("No forced cleanup: retaining disposable instance/process evidence at " + str(self.root)
                      + " and " + str(self.helper.case_root) + ": " + " | ".join(errors))
        # Both actual handles exited cleanly and stable Editor role leases were
        # acquired. These exact newly-created fixture roots are now disposable.
        self.helper.tearDown()
        shutil.rmtree(self.root)

    def test_editor_materialized_instance_manual_login_state_and_restart(self):
        self.assertTrue(os.environ.get("DISPLAY"), "Normal installed instance requires the dedicated GUI lane")
        # The provider UI helper has an optional debug-image destination. Never
        # inherit an external directory from another fixture or user session.
        with mock.patch.dict(os.environ):
            os.environ.pop("CURRENT_BASE_UI_DEBUG_DIR", None)
            self.run_real_pair()

    def run_real_pair(self):
        subprocess.run(["python3", "scripts/build-current-base.py"], cwd=PROVIDER,
                       check=True, capture_output=True, text=True, timeout=240)
        self.root = Path(tempfile.mkdtemp(prefix="editor-normal-instance-"))
        self.processes, self.previous_nonces = [], set()
        self.anchor = None
        self.helper = self.upgrade.CurrentRuntimeUpgradeTransactionTest()
        self.helper.setUp()
        # Never allow TemporaryDirectory finalization to erase an uncertain live
        # fixture. cleanup_owned is the sole cleanup path, including assertion failures.
        self.helper.case._finalizer.detach()
        self.addCleanup(self.cleanup_owned)
        packed = load("normal_packed_fixture", ROOT / "tests/myworld/test-world-builder-packed-conversion.py")
        helper = packed.PackedConversionTest()
        helper.classes = self.helper.classes
        parent = self.helper.case_root / "packed-evidence"
        parent.mkdir()
        target = helper.fixture(parent)
        terrain = bytes(value for x in range(48) for y in range(48)
                        for value in (0, 48 if (x // 4 + y // 4) % 2 else 96, 0, 0, 0, 0, 0, 0, 0, 0))
        for role in ROLES:
            with zipfile.ZipFile(target / role / "maps/active.orsc", "a") as archive:
                archive.writestr("h0x50y50", terrain)
        port = free_port()
        configuration = target / "server/conf/preservation.conf"
        write(configuration, ("server_name: Invented Initial Instance\nserver_port: " + str(port)
                              + "\nws_server_port: " + str(free_port()) + "\ndb_type: sqlite\n").encode())
        database = target / "server/inc/sqlite/preservation.db"
        database.parent.mkdir(parents=True)
        with sqlite3.connect(database) as connection:
            connection.executescript((PROVIDER / "server/database/sqlite/retro.sqlite").read_text())
            connection.execute("INSERT INTO players(id,username,pass,salt,x,y,quest_points,login_date) "
                               "VALUES(901,'launchtest','launchpass','',120,648,0,100)")
            for table in ("curstats", "maxstats", "experience", "capped_experience"):
                connection.execute("INSERT INTO " + table + "(playerID,praygood,prayevil,goodmagic,evilmagic,woodcutting) "
                                   "VALUES(901,11,7,16,9,21)")
            connection.execute("INSERT INTO itemstatuses(itemID,catalogID,amount,noted,wielded,durability) VALUES(901,10,321,0,0,0)")
            connection.execute("INSERT INTO invitems(playerID,itemID,slot) VALUES(901,901,0)")
            connection.execute("INSERT INTO quests(playerID,id,stage) VALUES(901,1,3)")
            connection.execute("INSERT INTO ironman(playerID,iron_man,iron_man_restriction,hc_ironman_death) VALUES(901,0,1,0)")
        source, report, _ = helper.discover_and_copy(target, parent)
        snapshot = self.upgrade.tree_snapshot
        original_target, original_source = snapshot(target), snapshot(source)
        workspace = self.helper.workspace()
        identity = PROVIDER / "output/current-platform/current-base-v1/composition-identity.json"
        staged = self.helper.run_harness("launch-inputs-stage", target, workspace, "normal-pair",
            identity=identity, catalog=PROVIDER / "current-platform", packed_source=source, packed_report=report)
        self.assertEqual(0, staged.returncode, staged.stderr)
        release = workspace / "normal-pair"
        original_release = snapshot(release)
        request = {"release": str(release), "identity": str(identity), "port": port, "installationId": str(uuid.uuid4()),
                   "migration": str(workspace / "normal-pair.migration.json"), "server": {}, "client": {}}
        side_source = self.root / "preserved-side-inputs"
        side_source.mkdir(mode=0o700)
        private, public = side_source / "server.pem", side_source / "client.pem"
        # Exact disposable handshake fixture from the pinned provider; not a
        # production key-size recommendation or replacement of owner keys.
        subprocess.run(["openssl", "genpkey", "-algorithm", "RSA", "-pkeyopt", "rsa_keygen_bits:512", "-out", str(private)],
                       check=True, capture_output=True)
        subprocess.run(["openssl", "pkey", "-in", str(private), "-pubout", "-out", str(public)], check=True, capture_output=True)
        private.chmod(0o600); public.chmod(0o600)
        for role, names in (("server", ("server.pem", "client.pem", "badwords.txt", "goodwords.txt", "alertwords.txt", "ipbans.txt", "ipbans.temp")),
                            ("client", ("client.pem", "clientSettings.conf", "uid.dat", "hideIp.txt", "credentials.txt"))):
            for name in names:
                path = side_source / role / name
                path.parent.mkdir(mode=0o700, exist_ok=True)
                request[role][name] = str(path)
                if name.endswith(".pem"):
                    write(path, (private if name == "server.pem" else public).read_bytes())
                elif name in ("badwords.txt", "goodwords.txt", "alertwords.txt"):
                    write(path, b"inventedword\n")
        before_side = snapshot(side_source)
        write(self.root / "request.json", request)
        constructed = json.loads(self.invoke("construct", self.root).stdout)
        write(self.root / "plan.json", constructed["plan"])
        self.invoke("verify-initial", self.root)
        self.launches = constructed["launches"]
        instance = self.root / "instance"
        self.anchor = instance / "installation"
        self.anchor_ids = {role: self.regular_private(self.anchor / (role + ".lock")) for role in ROLES}
        self.descriptor_paths = {role: Path(self.launches[role]["command"][-1]) for role in ROLES}
        self.descriptors = {role: json.loads(path.read_text()) for role, path in self.descriptor_paths.items()}
        self.descriptor_hashes = {role: digest(path) for role, path in self.descriptor_paths.items()}
        self.map_hashes = {role: digest(Path(document["mapRoot"]) / "manifest.json") for role, document in self.descriptors.items()}
        for role in ROLES:
            self.assertEqual(constructed["plan"]["generation"][role + "Descriptor"], self.descriptors[role])
        self.side = {role: Path(document["sideStateRoot"]) for role, document in self.descriptors.items()}
        stable_generation = snapshot(instance / "generations")
        stable_selection = (self.anchor / "active-launch.json").read_bytes()
        sealed_database = release / "migration/output/state/current-base.db"
        live_database = instance / "state/server/current_base.db"
        self.assertEqual(digest(sealed_database), digest(live_database))
        uid_hash = None
        for iteration in range(2):
            server, client = self.start("server"), self.start("client")
            self.invoke("lease", self.anchor, success=False)
            for role in ROLES:
                sessions_before = set(Path(self.descriptors[role]["sessionRoot"]).iterdir())
                duplicate = self.spawn(role, expected_exit=2)
                self.assertEqual(2, duplicate["process"].wait(timeout=20), self.log(duplicate["process"])[-4000:])
                self.assertNotIn("INSTALLED_SESSION ", self.log(duplicate["process"]))
                self.assertEqual(sessions_before, set(Path(self.descriptors[role]["sessionRoot"]).iterdir()))
            self.provider_ui.CurrentBaseInstalledLaunchTest.manual_login(self, server["process"], client["process"])
            # A stale request is test-owned and removed only after inode+bytes
            # readback. It cannot authorize shutdown of this announced session.
            wrong = dict(server["binding"], action="shutdown", nonce="0" * 64)
            wrong_path = server["session"] / "shutdown.json"
            write(wrong_path, wrong)
            owned_wrong = self.regular_private(wrong_path), wrong_path.read_bytes()
            time.sleep(0.3)
            self.assertIsNone(server["process"].poll())
            self.invoke("lease", self.anchor, success=False)
            self.assertEqual(owned_wrong, (self.regular_private(wrong_path), wrong_path.read_bytes()))
            wrong_path.unlink()
            self.stop(client)
            self.stop(server)
            self.invoke("lease", self.anchor)
            for record in (server, client):
                self.previous_nonces.add(record["binding"]["nonce"])
            with sqlite3.connect(live_database) as connection:
                self.assertEqual((120, 648), connection.execute("SELECT x,y FROM players WHERE id=901").fetchone())
                self.assertEqual((321,), connection.execute("SELECT amount FROM itemstatuses JOIN invitems USING(itemID) WHERE playerID=901 AND catalogID=10").fetchone())
                self.assertEqual((3,), connection.execute("SELECT stage FROM quests WHERE playerID=901 AND id=1").fetchone())
                self.assertEqual((11, 16, 21), connection.execute("SELECT prayer,magic,woodcut FROM maxstats WHERE playerID=901").fetchone())
                login_date, online = connection.execute("SELECT login_date,online FROM players WHERE id=901").fetchone()
                self.assertGreater(login_date, 100)
                self.assertEqual(0, online, "Clean normal shutdown must save the offline state")
            self.assertNotEqual(digest(sealed_database), digest(live_database))
            uid = self.side["client"] / "uid.dat"
            self.regular_private(uid)
            if iteration == 0:
                uid_hash = digest(uid)
            else:
                self.assertEqual(uid_hash, digest(uid))
            self.assertEqual(original_release, snapshot(release))
            self.assertEqual(original_target, snapshot(target))
            self.assertEqual(original_source, snapshot(source))
            self.assertEqual(before_side, snapshot(side_source))
            self.assertEqual(stable_generation, snapshot(instance / "generations"))
            self.assertEqual(stable_selection, (self.anchor / "active-launch.json").read_bytes())
            self.assertEqual(self.anchor_ids, {role: self.regular_private(self.anchor / (role + ".lock")) for role in ROLES})
            for role in ROLES:
                for name, original in request[role].items():
                    if Path(original).is_file():
                        self.assertEqual(digest(Path(original)), digest(self.side[role] / name))
                for name in ("client.pem", "server.pem", "clientSettings.conf", "uid.dat", "credentials.txt"):
                    self.assertFalse((Path(self.descriptors[role]["workingRoot"]) / name).exists())
                self.assertFalse((Path(self.descriptors[role]["workingRoot"]) / "Cache/uid.dat").exists())
            self.assertFalse((self.side["client"] / "credentials.txt").exists())
        self.assertEqual(4, len(self.previous_nonces), "Stable descriptors must start fresh runtime-owned sessions")
        # Initial output verification is deliberately not a perpetual live-state
        # seal: normal gameplay/session history changes the initial inventory.
        self.invoke("verify-initial", self.root, success=False)


class SessionOwnershipTest(unittest.TestCase):
    """Negative checks on the test's admission boundary, not runtime substitutes."""

    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="editor-normal-control-")
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.check = InitialInstanceIntegrationTest()
        self.check.previous_nonces = set()
        self.check.descriptors = {"server": {"installationId": str(uuid.uuid4()),
            "sessionRoot": str(self.root), "compositionIdentity": {"sha256": "a" * 64}}}
        self.check.descriptor_hashes = {"server": "b" * 64}
        self.check.map_hashes = {"server": "c" * 64}
        self.nonce = "d" * 64
        self.session = self.root / self.nonce
        self.session.mkdir(mode=0o700)
        self.expected = self.check.binding("server", self.nonce)
        self.ready = self.session / "ready.json"
        write(self.ready, self.expected)
        self.process = object()
        self.record = {"process": self.process, "role": "server"}
        self.check.log = lambda process: "INSTALLED_SESSION " + self.nonce + "\n" if process is self.process else ""

    def test_ready_cannot_substitute_for_owned_fresh_bound_announcement(self):
        self.assertTrue(self.check.admit_session(self.record, require_ready=True))
        for field in self.expected:
            changed = dict(self.expected, **{field: "foreign"})
            with self.subTest(field=field), mock.patch.object(Path, "read_text", return_value=json.dumps(changed)):
                with self.assertRaises(AssertionError):
                    self.check.admit_session(self.record, require_ready=True)
        with mock.patch.object(Path, "read_text", return_value=json.dumps(dict(self.expected, extra="foreign"))):
            with self.assertRaises(AssertionError):
                self.check.admit_session(self.record, require_ready=True)
        self.check.log = lambda process: ""
        self.assertFalse(self.check.admit_session(self.record, require_ready=True), "An existing ready file cannot establish process ownership")
        self.check.log = lambda process: ("INSTALLED_SESSION " + self.nonce + "\n") * 2
        with self.assertRaises(AssertionError):
            self.check.admit_session(self.record, require_ready=True)
        self.check.log = lambda process: "INSTALLED_SESSION " + self.nonce + "\n"
        self.check.previous_nonces.add(self.nonce)
        with self.assertRaises(AssertionError):
            self.check.admit_session(self.record, require_ready=True)

    def test_aliased_or_nonprivate_ready_is_not_admitted(self):
        self.ready.chmod(0o644)
        with self.assertRaises(AssertionError):
            self.check.admit_session(self.record, require_ready=True)
        self.ready.chmod(0o600)
        alias = self.root / "alias"
        os.link(self.ready, alias)
        with self.assertRaises(AssertionError):
            self.check.admit_session(self.record, require_ready=True)
        alias.unlink()
        self.ready.rename(alias)
        self.ready.symlink_to(alias)
        with self.assertRaises(AssertionError):
            self.check.admit_session(self.record, require_ready=True)

    def test_uncertain_cleanup_retains_every_fixture_and_never_forces(self):
        self.check.root = self.root
        self.check.helper = mock.Mock(case_root=self.root / "historical-input")
        self.check.processes = [{"process": mock.Mock()}]
        self.check.anchor = None
        self.check.stop = mock.Mock(side_effect=AssertionError("unproven owned session"))
        with mock.patch.object(shutil, "rmtree") as remove:
            with self.assertRaisesRegex(AssertionError, "No forced cleanup"):
                self.check.cleanup_owned()
            remove.assert_not_called()
        self.check.helper.tearDown.assert_not_called()
        self.check.processes[0]["process"].terminate.assert_not_called()
        self.check.processes[0]["process"].kill.assert_not_called()


if __name__ == "__main__":
    unittest.main()
