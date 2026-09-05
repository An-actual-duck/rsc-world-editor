#!/usr/bin/env python3
"""Persistent installed JVM leases: real cross-process exclusion, no target writes."""
import fcntl
import os
from pathlib import Path
import subprocess
import socket
import select
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]


class InstalledInstanceLeaseTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.shared = tempfile.TemporaryDirectory(prefix="installed-lease-classes-")
        cls.classes = Path(cls.shared.name)
        harness = cls.classes / "InstanceLeaseHarness.java"
        harness.write_text(r'''package com.openrsc.worldbuilder;
import java.nio.file.*;
public final class InstanceLeaseHarness {
 public static void main(String[] args) throws Exception {
  if (args.length == 3) {
   java.util.Map<String,Object> ports = new java.util.HashMap<String,Object>();
   ports.put("gamePort", Long.valueOf(args[1])); ports.put("websocketPort", Long.valueOf(args[2]));
   try (WorldBuilderCurrentRuntimeOfflineLease lease =
     WorldBuilderCurrentRuntimeOfflineLease.acquireInstalled(Paths.get(args[0]), ports)) {
    System.out.println("HELD"); System.out.flush(); System.in.read(); lease.verifyInstalledHeld();
   } catch (WorldBuilderContractException failure) {
    System.err.println("CODE=" + failure.code()); throw failure;
   }
   return;
  }
  try (WorldBuilderCurrentRuntimeInstanceLease lease =
    WorldBuilderCurrentRuntimeInstanceLease.acquire(Paths.get(args[0]))) {
   if (args.length > 1 && "overlap".equals(args[1])) {
    try (WorldBuilderCurrentRuntimeInstanceLease other =
      WorldBuilderCurrentRuntimeInstanceLease.acquire(Paths.get(args[0]))) {
     throw new AssertionError("same-JVM overlap accepted");
    } catch (WorldBuilderContractException expected) { }
   }
   System.out.println("HELD"); System.out.flush();
   System.in.read(); lease.verifyHeld();
  } catch (WorldBuilderContractException failure) {
   System.err.println("CODE=" + failure.code()); throw failure;
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
        self.temporary = tempfile.TemporaryDirectory(prefix="installed-lease-#é-")
        self.root = Path(self.temporary.name)
        self.instance = self.root / "installation"
        self.instance.mkdir(mode=0o700)
        for name in ("server.lock", "client.lock"):
            (self.instance / name).touch(mode=0o600)

    def tearDown(self):
        self.temporary.cleanup()

    def command(self, root=None, *args):
        return ["java", "-cp", str(self.classes), "com.openrsc.worldbuilder.InstanceLeaseHarness",
                str(root or self.instance), *args]

    def snapshot(self):
        return {p.name: (p.stat().st_ino, p.stat().st_mode, p.read_bytes())
                for p in self.instance.iterdir() if p.is_file()}

    def await_held(self, process):
        self.assertTrue(select.select([process.stdout], [], [], 10)[0], "Lease process did not respond")
        self.assertEqual("HELD", process.stdout.readline().strip())

    def probe(self, role, busy):
        with (self.instance / role).open("r+b") as lock:
            if busy:
                with self.assertRaises(BlockingIOError):
                    fcntl.lockf(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            else:
                fcntl.lockf(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)

    def test_holds_both_roles_restarts_and_same_jvm_refusal_retains_os_locks(self):
        before = self.snapshot()
        for mode in ((), ("overlap",), ()):
            process = subprocess.Popen(self.command(None, *mode), stdin=subprocess.PIPE,
                                       stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
            try:
                self.await_held(process)
                for role in ("server.lock", "client.lock"):
                    self.probe(role, True)
                refused = subprocess.run(self.command(), input="\n", capture_output=True,
                                         text=True, timeout=10)
                self.assertNotEqual(0, refused.returncode)
                self.assertIn("CODE=OFFLINE_REQUIRED", refused.stderr)
            finally:
                _, stderr = process.communicate("\n", timeout=10)
            self.assertEqual(0, process.returncode, stderr)
            for role in ("server.lock", "client.lock"):
                self.probe(role, False)
            self.assertEqual(before, self.snapshot())

    def test_busy_second_role_releases_first_without_modifying_either(self):
        before = self.snapshot()
        with (self.instance / "client.lock").open("r+b") as client:
            fcntl.lockf(client, fcntl.LOCK_EX | fcntl.LOCK_NB)
            refused = subprocess.run(self.command(), input="\n", capture_output=True,
                                     text=True, timeout=10)
            self.assertNotEqual(0, refused.returncode)
            self.assertIn("CODE=OFFLINE_REQUIRED", refused.stderr)
            self.probe("server.lock", False)
        self.assertEqual(before, self.snapshot())

    def test_installed_offline_lease_reserves_ports_and_releases_roles_on_port_refusal(self):
        with socket.socket() as first, socket.socket() as second:
            first.bind(("0.0.0.0", 0)); second.bind(("0.0.0.0", 0))
            game, websocket = first.getsockname()[1], second.getsockname()[1]
        before = self.snapshot()
        process = subprocess.Popen(self.command(None, str(game), str(websocket)),
                                   stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                   stderr=subprocess.PIPE, text=True)
        try:
            self.await_held(process)
            for port in (game, websocket):
                with socket.socket() as probe:
                    with self.assertRaises(OSError):
                        probe.bind(("0.0.0.0", port))
            for role in ("server.lock", "client.lock"):
                self.probe(role, True)
        finally:
            _, stderr = process.communicate("\n", timeout=10)
        self.assertEqual(0, process.returncode, stderr)
        with socket.socket() as busy:
            busy.bind(("0.0.0.0", websocket))
            refused = subprocess.run(self.command(None, str(game), str(websocket)),
                                     input="\n", capture_output=True, text=True, timeout=10)
            self.assertNotEqual(0, refused.returncode)
            self.assertIn("CODE=OFFLINE_REQUIRED", refused.stderr)
            with socket.socket() as available:
                available.bind(("0.0.0.0", game))
            for role in ("server.lock", "client.lock"):
                self.probe(role, False)
        self.assertEqual(before, self.snapshot())

    def test_refuses_missing_unsafe_or_nonprivate_paths_without_repair(self):
        server = self.instance / "server.lock"
        preserved = self.root / "preserved.lock"
        for mode in ("missing", "symlink", "hardlink", "nonempty", "permissions", "directory"):
            with self.subTest(mode=mode):
                server.rename(preserved)
                if mode == "symlink":
                    server.symlink_to(preserved)
                elif mode == "hardlink":
                    os.link(preserved, server)
                elif mode == "directory":
                    server.mkdir(mode=0o700)
                elif mode in ("nonempty", "permissions"):
                    server.touch(mode=0o600)
                    if mode == "nonempty":
                        server.write_bytes(b"user data")
                    else:
                        server.chmod(0o644)
                refused = subprocess.run(self.command(), input="\n", capture_output=True,
                                         text=True, timeout=10)
                self.assertNotEqual(0, refused.returncode)
                self.assertEqual(b"", preserved.read_bytes())
                if mode == "missing":
                    self.assertFalse(server.exists())
                elif mode == "directory":
                    server.rmdir()
                else:
                    if mode == "nonempty":
                        self.assertEqual(b"user data", server.read_bytes())
                    server.unlink()
                preserved.rename(server)
        alias = self.root / "alias"
        alias.symlink_to(self.instance, target_is_directory=True)
        self.assertNotEqual(0, subprocess.run(self.command(alias), input="\n",
                            capture_output=True, text=True, timeout=10).returncode)
        self.instance.chmod(0o755)
        self.assertNotEqual(0, subprocess.run(self.command(), input="\n",
                            capture_output=True, text=True, timeout=10).returncode)

    def test_replacement_after_acquisition_invalidates_authority_and_retains_files(self):
        process = subprocess.Popen(self.command(), stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                   stderr=subprocess.PIPE, text=True)
        try:
            self.await_held(process)
            server = self.instance / "server.lock"
            preserved = self.instance / "preserved.lock"
            server.rename(preserved)
            server.touch(mode=0o600)
        finally:
            _, stderr = process.communicate("\n", timeout=10)
        self.assertNotEqual(0, process.returncode)
        self.assertIn("CODE=OFFLINE_REQUIRED", stderr)
        self.assertEqual(b"", preserved.read_bytes())
        self.assertEqual(b"", server.read_bytes())
        self.probe("server.lock", False)
        self.probe("client.lock", False)


if __name__ == "__main__":
    unittest.main()
