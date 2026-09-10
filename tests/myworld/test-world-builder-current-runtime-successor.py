#!/usr/bin/env python3
"""Focused successor publication topology; fixture jars are not runtime acceptance."""
import importlib.util
import json
import os
from pathlib import Path
import shutil
import sqlite3
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("instance_fixture", Path(__file__).with_name("test-world-builder-current-runtime-instance.py"))
fixture = importlib.util.module_from_spec(spec)
spec.loader.exec_module(fixture)

HARNESS = r'''package com.openrsc.worldbuilder;
import java.nio.file.*; import java.util.*;
public final class SuccessorHarness {
 @SuppressWarnings("unchecked") public static void main(String[] a) throws Exception {
  Path root=Paths.get(a[1]),target=root.resolve("target"),stage=root.resolve("successor-stage");
  if ("sidecar".equals(a[0])) { WorldBuilderPreservationStagedMigrator.requireClosedSqliteSnapshot(target,Paths.get(a[2])); return; }
  Map<String,Object> old=WorldBuilderCurrentRuntimeInstalledGeneration.readSpecification(target);
  if ("durable".equals(a[0])) {
    System.out.print(WorldBuilderJsonDocuments.pretty(WorldBuilderCurrentBaseManagedInputs.durable(target,old))); return;
  }
  Map<String,Object> identity=WorldBuilderJsonDocuments.readObject(root.resolve("identity.json"));
  Map<String,Object> plan=WorldBuilderCurrentRuntimeSuccessor.inspect(target,stage,target.resolve(".world-builder/current-runtime/releases/successor-bundle/next"),"next",identity,
    WorldBuilderJsonDocuments.readObject(root.resolve("layout.json")),(List<Object>)WorldBuilderJsonDocuments.readObject(root.resolve("generated.json")).get("outputs"));
  WorldBuilderCurrentRuntimeSuccessor.validate(plan,target);
  Map<String,Object> next=(Map<String,Object>)plan.get("specification");
  if(!old.get("serverSideStateRoot").equals(next.get("serverSideStateRoot"))||old.get("serverStateRoot").equals(next.get("serverStateRoot"))) throw new AssertionError("state branching");
  Map<String,Object> ledger=WorldBuilderJsonDocuments.readObject(target.resolve(".world-builder/runtime-ledger-v1.json"));
  ledger=WorldBuilderCurrentRuntimeInstalledGeneration.bind(ledger,target,next,identity,WorldBuilderJsonDocuments.readObject(stage.resolve("migration/output/map/conversion/package/manifest.json")),"base-project");
  WorldBuilderCurrentRuntimeCutover.Plan cutover=WorldBuilderCurrentRuntimeCutover.inspect(target,"next",
    WorldBuilderHashes.sha256(target.resolve(".world-builder/current-runtime/instance/installation/active-launch.json")),WorldBuilderHashes.sha256(target.resolve(".world-builder/runtime-ledger-v1.json")),
    WorldBuilderJsonDocuments.pretty(WorldBuilderCurrentRuntimeInstance.renderGeneration(next).get("activeSelection")).getBytes("UTF-8"),WorldBuilderJsonDocuments.pretty(ledger).getBytes("UTF-8"));
  Path journal=root.resolve("successor-cutover"); WorldBuilderCurrentRuntimeCutover.journal(cutover,journal);
  try(WorldBuilderCurrentRuntimeInstanceLease lease=WorldBuilderCurrentRuntimeInstanceLease.acquire(Paths.get((String)old.get("installationRoot")))) {
    WorldBuilderCurrentRuntimeCutover.guard(cutover,journal,lease);
    WorldBuilderCurrentRuntimeSuccessor.materialize(plan,target,stage,lease);
    if("drift".equals(a[0])) Files.write(Paths.get((String)next.get("serverStateRoot")).resolve("current_base.db"),new byte[]{42},StandardOpenOption.APPEND);
    if(!"success".equals(a[0])) {
      WorldBuilderCurrentRuntimeSuccessor.verify(plan,target,false);
      new WorldBuilderCurrentRuntimeCutover().recover(cutover,journal,lease);
      WorldBuilderCurrentRuntimeSuccessor.removeNeverStarted(plan,target,lease);
    } else {
      Path release=Paths.get((String)plan.get("releaseRoot")); Files.createDirectories(release.getParent()); Files.move(stage,release,StandardCopyOption.ATOMIC_MOVE);
      WorldBuilderCurrentRuntimeSuccessor.verify(plan,target,true);
      new WorldBuilderCurrentRuntimeCutover().apply(cutover,journal,lease);
      if(!next.equals(WorldBuilderCurrentRuntimeInstalledGeneration.readSpecification(target))) throw new AssertionError("installed successor differs");
    }
  }
  System.out.print(WorldBuilderJsonDocuments.pretty(plan));
 }
}'''


class CurrentRuntimeSuccessorTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        fixture.CurrentRuntimeInstanceTest.setUpClass()
        cls.addClassCleanup(fixture.CurrentRuntimeInstanceTest.tearDownClass)
        cls.classes = fixture.CurrentRuntimeInstanceTest.classes
        source = cls.classes.parent / "SuccessorHarness.java"
        source.write_text(HARNESS)
        subprocess.run(["javac", "-source", "8", "-target", "8", "-cp", str(cls.classes), "-d", str(cls.classes), str(source)], check=True, capture_output=True, text=True)

    def setUp(self):
        self.case = fixture.CurrentRuntimeInstanceTest("test_initial_guarded_construction_and_metadata_commit")
        self.case.setUp(); self.addCleanup(self.case.tearDown)
        self.root = self.case.root
        self.target = self.root / "target"
        release = self.target / ".world-builder/current-runtime/releases/initial-bundle/first"
        shutil.copytree(self.case.stage, release)
        self.case.stage = release
        self.case.instance = self.target / ".world-builder/current-runtime/instance"
        self.case.request.update(target=str(self.target), stage=str(release), release=str(release), instance=str(self.case.instance), output=str(self.case.instance))
        self.case.invoke("construct-guarded")
        self.original = fixture.snapshot(self.case.instance)
        self.selection = (self.case.instance / "installation/active-launch.json").read_bytes()
        self.ledger = (self.target / ".world-builder/runtime-ledger-v1.json").read_bytes()
        shutil.copytree(release, self.root / "successor-stage")

    def invoke(self, action, *args, success=True):
        result = subprocess.run(["java", "-cp", str(self.classes), "com.openrsc.worldbuilder.SuccessorHarness", action, str(self.root), *map(str,args)], capture_output=True, text=True, timeout=30)
        if success:
            self.assertEqual(0, result.returncode, result.stderr)
            return json.loads(result.stdout) if result.stdout else None
        self.assertNotEqual(0,result.returncode)
        return result

    def test_successor_branches_database_and_preserves_predecessor_and_side_state(self):
        result=self.invoke("success")
        for relative, record in self.original.items():
            if relative == "installation/active-launch.json": continue
            path=self.case.instance/relative
            self.assertEqual(record,(path.stat().st_mode & 0o7777, fixture.sha(path)))
        state=Path(result["specification"]["serverStateRoot"])/"current_base.db"
        with sqlite3.connect(state) as db: db.execute("insert into preserved values ('new-gameplay')")
        self.case.invoke("verify-installed")
        self.assertEqual("base-project",json.loads((self.target/".world-builder/runtime-ledger-v1.json").read_text())["installedInstance"]["projectId"])

    def test_precommit_rollback_removes_only_exact_never_started_successor(self):
        self.invoke("rollback")
        self.assertEqual(self.original,fixture.snapshot(self.case.instance))
        self.assertEqual(self.selection,(self.case.instance/"installation/active-launch.json").read_bytes())
        self.assertEqual(self.ledger,(self.target/".world-builder/runtime-ledger-v1.json").read_bytes())

    def test_changed_successor_state_is_retained_behind_guard(self):
        self.invoke("drift",success=False)
        self.assertTrue((self.case.instance/"installation/pending-cutover.json").is_file())
        self.assertTrue((self.case.instance/"state/next/server/current_base.db").is_file())
        self.assertEqual(self.ledger,(self.target/".world-builder/runtime-ledger-v1.json").read_bytes())

    def test_current_database_sidecars_are_not_checked_at_historical_path(self):
        state=self.case.instance/"state/server/current_base.db"
        self.invoke("sidecar",state)
        for suffix in ("-wal","-shm","-journal"):
            side=state.with_name(state.name+suffix)
            side.write_bytes(b"active")
            self.invoke("sidecar",state,success=False)
            side.unlink()

    def test_predecessor_umask_ban_files_are_preserved_inside_private_state(self):
        side = self.case.instance / "state/server/side"
        for name in ("ipbans.txt", "ipbans.temp"):
            path = side / name
            path.write_bytes(b"192.0.2.7\n")
            for mode in (0o600, 0o640, 0o644, 0o660, 0o664):
                with self.subTest(name=name, mode=oct(mode)):
                    path.chmod(mode)
                    before = fixture.snapshot(self.case.instance)
                    result = self.invoke("durable")
                    row = next(row for row in result if row["relativePath"] == str(path.relative_to(self.target)))
                    self.assertEqual(fixture.sha(path), row["sourceSha256"])
                    self.assertEqual(before, fixture.snapshot(self.case.instance))

    def test_ban_exception_does_not_admit_unsafe_modes_or_public_roots(self):
        side = self.case.instance / "state/server/side"
        ban = side / "ipbans.txt"
        ban.write_bytes(b"192.0.2.7\n")
        for mode in (0o666, 0o700, 0o4600):
            with self.subTest(mode=oct(mode)):
                ban.chmod(mode)
                self.invoke("durable", success=False)
        ban.chmod(0o600)
        side.chmod(0o755)
        self.invoke("durable", success=False)

    def test_ban_exception_never_admits_nonprivate_secrets_or_other_state(self):
        side = self.case.instance / "state/server/side"
        for path in (side / "server.pem", side / "badwords.txt",
                     self.case.instance / "state/server/current_base.db"):
            with self.subTest(path=path.name):
                path.chmod(0o644)
                self.invoke("durable", success=False)
                path.chmod(0o600)
        extra = side / "unrecognized.txt"
        extra.write_bytes(b"not a ban file")
        extra.chmod(0o644)
        self.invoke("durable", success=False)

    def test_ban_exception_never_admits_linked_files(self):
        side = self.case.instance / "state/server/side"
        ban = side / "ipbans.txt"
        original = self.root / "link-source.txt"
        original.write_bytes(b"192.0.2.7\n")
        original.chmod(0o600)
        ban.unlink()  # Replace only this test's generated ban-file fixture.
        ban.symlink_to(original)
        self.invoke("durable", success=False)
        ban.unlink()
        os.link(original, ban)
        self.invoke("durable", success=False)

if __name__ == "__main__": unittest.main()
