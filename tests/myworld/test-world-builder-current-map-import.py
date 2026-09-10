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
        harness = cls.classes / "CurrentActionsProbe.java"
        harness.write_text('''package com.openrsc.worldbuilder;
import java.nio.file.Paths;
public final class CurrentActionsProbe {
    public static void main(String[] args) throws Exception {
        if (args.length > 1) {
            WorldBuilderCurrentRuntimeRecoveryActions.Preview preview = WorldBuilderCurrentRuntimeRecoveryActions.preview(
                Paths.get(args[1]), args[3], Paths.get(args[2]));
            if ("preview".equals(args[0])) System.out.print(preview.summary());
            else System.out.print(WorldBuilderCurrentRuntimeRecoveryActions.apply(preview,
                args.length > 4 ? args[4] : preview.confirmation()));
            return;
        }
        System.out.println(WorldBuilderCurrentRuntimeUserActions.workspace(Paths.get(args[0])));
    }
}
''')
        subprocess.run(["javac", "-cp", str(cls.classes), "-d", str(cls.classes), str(harness)], check=True)

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

    def actions(self, operation, confirmation=None, project="base-project"):
        arguments = ["java", "-cp", str(self.classes), "com.openrsc.worldbuilder.CurrentActionsProbe",
                     operation, str(self.f.target), str(self.workspace), project]
        if confirmation is not None:
            arguments.append(confirmation)
        return subprocess.run(arguments, capture_output=True, text=True, timeout=20)

    def test_desktop_preview_and_confirmed_precommit_recovery(self):
        self.assertEqual(73, self.f.run_cutover("apply", "selection-published").returncode)
        def snapshot():
            return {str(path.relative_to(self.f.root)): (path.stat().st_mode,
                    path.read_bytes() if path.is_file() else None) for path in self.f.root.rglob("*")}
        before = snapshot()
        preview = self.actions("preview")
        self.assertEqual(0, preview.returncode, preview.stderr)
        self.assertIn("RECOVER:" + self.fingerprint, preview.stdout)
        self.assertNotEqual(0, self.actions("apply", "RECOVER:wrong").returncode)
        self.f.assert_pair(True)
        self.assertEqual(before, snapshot())
        self.assertNotEqual(0, self.actions("preview", project="another-project").returncode)
        result = self.actions("apply", "RECOVER:" + self.fingerprint)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual("rolled-back", result.stdout)
        self.f.assert_pair(False)
        self.assertNotEqual(0, self.actions("preview").returncode)

    def test_desktop_finalizes_interrupted_commit_without_completed_undo(self):
        self.assertEqual(73, self.f.run_cutover("apply", "commit-durable").returncode)
        self.f.state.write_bytes(b"later gameplay")
        preview = self.actions("preview")
        self.assertEqual(0, preview.returncode, preview.stderr)
        result = self.actions("apply")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual("successful", result.stdout)
        self.assertEqual(b"later gameplay", self.f.state.read_bytes())
        self.assertNotEqual(0, self.actions("preview").returncode)

    def test_desktop_rejects_stale_confirmation_and_busy_roles(self):
        import fcntl
        self.assertEqual(73, self.f.run_cutover("apply", "selection-published").returncode)
        self.assertEqual(0, self.actions("preview").returncode)
        self.plan.write_bytes(self.plan.read_bytes() + b"\n")
        self.assertNotEqual(0, self.actions("apply", "RECOVER:" + self.fingerprint).returncode)
        for role in ("server", "client"):
            with (self.f.installation / (role + ".lock")).open("r+b") as lock:
                fcntl.lockf(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
                self.assertNotEqual(0, self.actions("preview").returncode)
        self.f.assert_pair(True)

    def test_user_workspace_is_private_external_and_reusable(self):
        nested_installation = self.f.target / "World Builder 2"
        nested_installation.mkdir()
        def run(target):
            return subprocess.run(["java", "-cp", str(self.classes),
                                   "com.openrsc.worldbuilder.CurrentActionsProbe", str(target)],
                                  capture_output=True, text=True, timeout=20)
        result = run(self.f.target)
        self.assertEqual(0, result.returncode, result.stderr)
        workspace = Path(result.stdout.strip())
        self.assertEqual(self.f.target.parent, workspace.parent)
        self.assertEqual(0o700, workspace.stat().st_mode & 0o777)
        self.assertEqual(result.stdout, run(self.f.target).stdout)
        self.assertEqual([], list(nested_installation.iterdir()))
        workspace.chmod(0o755)
        self.assertNotEqual(0, run(self.f.target).returncode)
        self.assertEqual(0o755, workspace.stat().st_mode & 0o777)
        workspace.rmdir()  # Empty test-owned fixture only.
        workspace.symlink_to(self.workspace, target_is_directory=True)
        self.assertNotEqual(0, run(self.f.target).returncode)
        self.assertTrue(workspace.is_symlink())

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
