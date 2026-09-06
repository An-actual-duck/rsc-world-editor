#!/usr/bin/env python3
"""Exact two-file cutover, actual process interruption, and post-commit gameplay."""
import fcntl
import hashlib
import json
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]


class InstalledCutoverTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.shared = tempfile.TemporaryDirectory(prefix="installed-cutover-classes-")
        cls.classes = Path(cls.shared.name)
        harness = cls.classes / "CutoverHarness.java"
        harness.write_text(r'''package com.openrsc.worldbuilder;
import java.nio.file.*;
public final class CutoverHarness {
 public static void main(String[] args) throws Exception {
  Path target=Paths.get(args[1]), journal=Paths.get(args[2]);
  WorldBuilderCurrentRuntimeCutover cutover=new WorldBuilderCurrentRuntimeCutover(point -> {
   if(point.equals(args[4])) {
    if("halt".equals(args[5])) Runtime.getRuntime().halt(73);
    throw new java.io.IOException("injected "+point);
   }
  });
  if("inspect".equals(args[0])) {
   Path selection=target.resolve(".world-builder/current-runtime/instance/installation/active-launch.json");
   Path ledger=target.resolve(".world-builder/runtime-ledger-v1.json");
   WorldBuilderCurrentRuntimeCutover.Plan plan=WorldBuilderCurrentRuntimeCutover.inspect(target,"cutover-1",
    WorldBuilderHashes.sha256(selection), Files.exists(ledger)?WorldBuilderHashes.sha256(ledger):"",
    Files.readAllBytes(Paths.get(args[6])),Files.readAllBytes(Paths.get(args[7])));
   WorldBuilderCurrentRuntimeCutover.journal(plan,journal);
   System.out.println(plan.fingerprint); return;
  }
  WorldBuilderCurrentRuntimeCutover.Plan plan=WorldBuilderCurrentRuntimeCutover.read(journal,target,args[3]);
  try(WorldBuilderCurrentRuntimeInstanceLease lease=WorldBuilderCurrentRuntimeInstanceLease.acquire(
   target.resolve(".world-builder/current-runtime/instance/installation"))) {
   if("apply".equals(args[0])) { cutover.apply(plan,journal,lease); System.out.println("successful"); }
   else System.out.println(cutover.recover(plan,journal,lease));
  }
 }
}
''')
        sources = sorted((ROOT / "tools/world-builder/src").rglob("*.java"))
        subprocess.run(["javac", "-source", "8", "-target", "8", "-d", str(cls.classes),
                        *map(str, sources), str(harness)], check=True, capture_output=True)

    @classmethod
    def tearDownClass(cls):
        cls.shared.cleanup()

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="installed-cutover-#é-")
        self.root = Path(self.temp.name)
        self.target = self.root / "target"
        self.installation = self.target / ".world-builder/current-runtime/instance/installation"
        self.installation.mkdir(parents=True, mode=0o700)
        for name in ("server.lock", "client.lock"):
            (self.installation / name).touch(mode=0o600)
        self.selection = self.installation / "active-launch.json"
        self.ledger = self.target / ".world-builder/runtime-ledger-v1.json"
        self.guard = self.installation / "pending-cutover.json"
        self.journal = self.root / "journal"
        self.before_selection = b'{"prior":"selection"}\n'
        self.before_ledger = b'{"prior":"ledger"}\n'
        self.put(self.selection, self.before_selection)
        self.put(self.ledger, self.before_ledger)
        identity = "dfc66bfe-3553-4d90-9256-afb90f387e56"
        self.new_selection = json.dumps({"manifestType": "current-base-installed-selection", "installationId": identity}).encode()
        self.new_ledger = json.dumps({"manifestType": "world-builder-current-target-runtime-ledger", "targetInstallationId": identity}).encode()
        self.put(self.root / "selection.json", self.new_selection)
        self.put(self.root / "ledger.json", self.new_ledger)
        self.state = self.target / "player-state.db"
        self.put(self.state, b"private gameplay sentinel")
        self.lock_inodes = [(self.installation / name).stat().st_ino for name in ("server.lock", "client.lock")]

    def tearDown(self):
        self.temp.cleanup()

    def put(self, path, data):
        path.write_bytes(data)
        path.chmod(0o600)

    def run_cutover(self, action, point="", mode="halt", fingerprint=None):
        return subprocess.run(["java", "-cp", str(self.classes), "com.openrsc.worldbuilder.CutoverHarness",
                               action, str(self.target), str(self.journal), fingerprint or getattr(self, "fingerprint", ""),
                               point, mode, str(self.root / "selection.json"), str(self.root / "ledger.json")],
                              capture_output=True, text=True, timeout=20)

    def prepare(self):
        result = self.run_cutover("inspect")
        self.assertEqual(0, result.returncode, result.stderr)
        self.fingerprint = result.stdout.strip()
        self.assertEqual(hashlib.sha256((self.journal / "plan.json").read_bytes()).hexdigest(), self.fingerprint)
        self.assertFalse(self.guard.exists())

    def assert_pair(self, after):
        self.assertEqual(self.new_selection if after else self.before_selection, self.selection.read_bytes())
        if not after and self.before_ledger is None:
            self.assertFalse(self.ledger.exists())
        else:
            self.assertEqual(self.new_ledger if after else self.before_ledger, self.ledger.read_bytes())
        self.assertEqual(self.lock_inodes, [(self.installation / name).stat().st_ino for name in ("server.lock", "client.lock")])

    def test_success_and_post_commit_recovery_never_rewinds_gameplay(self):
        self.prepare()
        result = self.run_cutover("apply")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assert_pair(True)
        self.assertFalse(self.guard.exists())
        self.state.write_bytes(b"new gameplay after commit")
        result = self.run_cutover("recover")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual("successful", result.stdout.strip())
        self.assertEqual(b"new gameplay after commit", self.state.read_bytes())
        self.assert_pair(True)

    def test_real_precommit_process_interruptions_restore_exact_pair(self):
        for point in ("guard-durable", "ledger-published", "selection-published"):
            with self.subTest(point=point):
                self.prepare()
                self.assertEqual(73, self.run_cutover("apply", point).returncode)
                self.assertTrue(self.guard.exists())
                result = self.run_cutover("recover")
                self.assertEqual(0, result.returncode, result.stderr)
                self.assertEqual("rolled-back", result.stdout.strip())
                self.assert_pair(False)
                self.assertFalse(self.guard.exists())
                self.assertEqual(b"private gameplay sentinel", self.state.read_bytes())
                self.journal = self.root / (self.journal.name + "-next")

    def test_real_postcommit_interruptions_finalize_only(self):
        for point in ("commit-durable", "guard-removed"):
            with self.subTest(point=point):
                self.prepare()
                self.assertEqual(73, self.run_cutover("apply", point).returncode)
                self.state.write_bytes(b"postcommit state")
                result = self.run_cutover("recover")
                self.assertEqual(0, result.returncode, result.stderr)
                self.assertEqual("successful", result.stdout.strip())
                self.assert_pair(True)
                self.assertEqual(b"postcommit state", self.state.read_bytes())
                self.assertFalse(self.guard.exists())
                self.put(self.selection, self.before_selection)
                self.put(self.ledger, self.before_ledger)
                self.journal = self.root / (self.journal.name + "-next")

    def test_ordinary_precommit_failure_automatically_rolls_back(self):
        self.prepare()
        result = self.run_cutover("apply", "selection-published", "throw")
        self.assertNotEqual(0, result.returncode)
        self.assert_pair(False)
        self.assertFalse(self.guard.exists())
        self.assertTrue((self.journal / "rollback.json").exists())

    def test_initial_absent_ledger_is_restored(self):
        self.ledger.unlink()
        self.before_ledger = None
        self.prepare()
        self.assertEqual(73, self.run_cutover("apply", "ledger-published").returncode)
        result = self.run_cutover("recover")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assert_pair(False)

    def test_drift_and_busy_lease_are_zero_write_refusals(self):
        self.prepare()
        for name in ("server.lock", "client.lock"):
            with (self.installation / name).open("r+b") as lock:
                fcntl.lockf(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
                self.assertNotEqual(0, self.run_cutover("apply").returncode)
                self.assert_pair(False)
                self.assertFalse(self.guard.exists())
        self.put(self.selection, b"drift")
        self.assertNotEqual(0, self.run_cutover("apply").returncode)
        self.assertEqual(b"drift", self.selection.read_bytes())
        self.assertEqual(self.before_ledger, self.ledger.read_bytes())
        self.assertFalse(self.guard.exists())

    def test_recovery_refuses_tampered_guard_or_confirmation(self):
        self.prepare()
        self.assertEqual(73, self.run_cutover("apply", "ledger-published").returncode)
        original = self.guard.read_bytes()
        self.assertNotEqual(0, self.run_cutover("recover", fingerprint="0" * 64).returncode)
        self.put(self.guard, b"unowned")
        self.assertNotEqual(0, self.run_cutover("recover").returncode)
        self.assertEqual(self.new_ledger, self.ledger.read_bytes())
        self.put(self.guard, original)
        self.assertEqual(0, self.run_cutover("recover").returncode)
        self.assert_pair(False)

    def test_recovery_validates_both_files_before_any_restore(self):
        self.prepare()
        self.assertEqual(73, self.run_cutover("apply", "selection-published").returncode)
        self.put(self.ledger, b"unknown ledger")
        self.assertNotEqual(0, self.run_cutover("recover").returncode)
        self.assertEqual(self.new_selection, self.selection.read_bytes())
        self.assertTrue(self.guard.exists())

    def test_links_and_unknown_temporary_refuse_without_replacement(self):
        self.prepare()
        self.assertEqual(73, self.run_cutover("apply", "selection-published").returncode)
        temp = self.ledger.with_name("." + self.ledger.name + ".cutover-1.forward")
        temp.symlink_to(self.state)
        self.assertNotEqual(0, self.run_cutover("recover").returncode)
        self.assert_pair(True)
        self.assertTrue(temp.is_symlink())
        self.assertEqual(b"private gameplay sentinel", self.state.read_bytes())


if __name__ == "__main__":
    unittest.main()
