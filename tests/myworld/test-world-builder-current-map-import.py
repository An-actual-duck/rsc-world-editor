#!/usr/bin/env python3
"""Map-only recovery boundary; synthetic metadata is not a genuine intake/launch claim."""
import hashlib
import importlib.util
import json
from pathlib import Path
import socket
import subprocess
import unittest

spec = importlib.util.spec_from_file_location("cutover_fixture", Path(__file__).with_name("test-world-builder-installed-cutover.py"))
fixture = importlib.util.module_from_spec(spec)
spec.loader.exec_module(fixture)


class CurrentMapImportRecoveryTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        fixture.InstalledCutoverTest.setUpClass()
        cls.classes = fixture.InstalledCutoverTest.classes

    @classmethod
    def tearDownClass(cls):
        fixture.InstalledCutoverTest.tearDownClass()

    def setUp(self):
        self.f = fixture.InstalledCutoverTest()
        self.f.setUp()
        self.workspace = self.f.root / "workspace"
        self.transaction = self.workspace / "map-one"
        self.transaction.mkdir(parents=True, mode=0o700)
        self.f.journal = self.transaction / "activation"
        self.f.prepare()
        self.outputs = {}
        self.ports = []
        for _ in range(2):
            with socket.socket() as listener:
                listener.bind(("127.0.0.1", 0))
                self.ports.append(listener.getsockname()[1])
        value = dict(schemaVersion=1, manifestType="world-builder-current-map-import-plan", transactionId="one",
                     targetRoot=str(self.f.target), projectRoot=str(self.f.root / "project"), exportRoot=str(self.f.root / "export"),
                     exportFingerprint="a" * 64, projectId="base-project", cutoverPlanSha256=self.f.fingerprint,
                     ports=dict(gamePort=self.ports[0], websocketPort=self.ports[1]),
                     retainedStatePolicy="same-code-configuration-player-and-side-state-paths-no-state-copy")
        for kind, name in (("map", "map-assets-one"), ("generation", "map-one")):
            relative = ".world-builder/current-runtime/instance/generations/" + name
            output = self.f.target / relative
            output.mkdir(parents=True, mode=0o700)
            data = (kind + " immutable fixture").encode()
            self.f.put(output / "fixture.json", data)
            value[kind + "RelativePath"] = relative
            value[kind + "Outputs"] = [dict(relativePath="fixture.json", size=len(data), sha256=hashlib.sha256(data).hexdigest())]
            self.outputs[kind] = output
        self.plan = self.transaction / "map-plan.json"
        self.f.put(self.plan, json.dumps(value).encode())
        self.fingerprint = hashlib.sha256(self.plan.read_bytes()).hexdigest()

    def tearDown(self):
        self.f.tearDown()

    def recover(self, fingerprint=None):
        return subprocess.run(["java", "-cp", str(self.classes), "com.openrsc.worldbuilder.WorldBuilderCli",
                               "recover-current-map-import", "--target-root", str(self.f.target),
                               "--transaction-root", str(self.workspace), "--transaction-id", "one",
                               "--confirmed-plan-sha256", fingerprint or self.fingerprint], capture_output=True, text=True, timeout=20)

    def test_precommit_recovers_exact_metadata_and_removes_only_owned_maps(self):
        self.assertEqual(73, self.f.run_cutover("apply", "selection-published").returncode)
        result = self.recover()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual("rolled-back", json.loads(result.stdout)["status"])
        self.f.assert_pair(False)
        self.assertTrue(all(not output.exists() for output in self.outputs.values()))
        self.assertEqual(b"private gameplay sentinel", self.f.state.read_bytes())
        self.assertEqual(0, self.recover().returncode)

    def test_postcommit_finalizes_without_rewinding_state(self):
        self.assertEqual(73, self.f.run_cutover("apply", "commit-durable").returncode)
        self.f.state.write_bytes(b"later gameplay")
        result = self.recover()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual("successful", json.loads(result.stdout)["status"])
        self.f.assert_pair(True)
        self.assertTrue(all(output.exists() for output in self.outputs.values()))
        self.assertEqual(b"later gameplay", self.f.state.read_bytes())

    def test_changed_new_output_retains_guard_and_every_output(self):
        self.assertEqual(73, self.f.run_cutover("apply", "selection-published").returncode)
        (self.outputs["map"] / "fixture.json").write_bytes(b"unowned change")
        self.assertNotEqual(0, self.recover().returncode)
        self.assertTrue(self.f.guard.exists())
        self.assertTrue(all(output.exists() for output in self.outputs.values()))
        self.f.assert_pair(True)

    def test_unconfirmed_plan_and_live_role_refuse_before_mutation(self):
        import fcntl
        self.assertNotEqual(0, self.recover("0" * 64).returncode)
        for role in ("server", "client"):
            with (self.f.installation / (role + ".lock")).open("r+b") as lock:
                fcntl.lockf(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
                self.assertNotEqual(0, self.recover().returncode)
        self.f.assert_pair(False)
        self.assertTrue(all(output.exists() for output in self.outputs.values()))

    def test_ambiguous_commit_never_authorizes_cleanup(self):
        self.assertEqual(73, self.f.run_cutover("apply", "selection-published").returncode)
        self.f.put(self.f.journal / "commit.json", b"partial commit")
        self.assertNotEqual(0, self.recover().returncode)
        self.assertTrue(self.f.guard.exists())
        self.assertTrue(all(output.exists() for output in self.outputs.values()))
        self.f.assert_pair(True)


if __name__ == "__main__":
    unittest.main()
