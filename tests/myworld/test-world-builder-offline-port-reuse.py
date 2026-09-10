#!/usr/bin/env python3
"""Linux offline leases admit a cleanly closed server, never a live listener."""
import os
from pathlib import Path
import socket
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
JAR = ROOT / "output/world-builder-tools/world-builder-tools.jar"


@unittest.skipUnless(sys.platform == "linux", "Linux-specific TIME_WAIT semantics")
class OfflinePortReuseTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temporary = tempfile.TemporaryDirectory(prefix="offline-port-reuse-")
        cls.addClassCleanup(cls.temporary.cleanup)
        cls.root = Path(cls.temporary.name)
        (cls.root / "server.conf").write_text("invented configuration")
        source = cls.root / "OfflinePortProbe.java"
        source.write_text('''package com.openrsc.worldbuilder;
import java.nio.file.*;
import java.util.*;
public final class OfflinePortProbe {
  public static void main(String[] args) throws Exception {
    Map<String,Object> typed = new LinkedHashMap<String,Object>();
    typed.put("sourceRelativePath", "server.conf");
    typed.put("gamePort", Long.valueOf(args[1]));
    typed.put("websocketPort", Long.valueOf(args[2]));
    try (WorldBuilderCurrentRuntimeOfflineLease lease = WorldBuilderCurrentRuntimeOfflineLease.acquire(
      Paths.get(args[0]), typed, false)) { System.out.println("exclusive-offline-lease"); }
  }
}
''')
        subprocess.run(["javac", "-cp", str(JAR), "-d", str(cls.root), str(source)], check=True, capture_output=True)

    def probe(self, port):
        with socket.socket() as secondary:
            secondary.bind(("127.0.0.1", 0))
            websocket_port = secondary.getsockname()[1]
        return subprocess.run(["java", "-cp", str(self.root) + os.pathsep + str(JAR),
            "com.openrsc.worldbuilder.OfflinePortProbe", str(self.root), str(port), str(websocket_port)],
            capture_output=True, text=True, timeout=15)

    def test_closed_connection_in_time_wait_does_not_look_like_a_live_server(self):
        with socket.socket() as listener, socket.socket() as client:
            listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            listener.bind(("127.0.0.1", 0))
            listener.listen()
            port = listener.getsockname()[1]
            client.connect(("127.0.0.1", port))
            accepted, _ = listener.accept()
            accepted.close()  # Server initiates close, placing its port in TIME_WAIT.
            self.assertEqual(b"", client.recv(1))
        with socket.socket() as old_exclusive_bind:
            with self.assertRaises(OSError):
                old_exclusive_bind.bind(("0.0.0.0", port))
        result = self.probe(port)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("exclusive-offline-lease", result.stdout)

    def test_live_listener_is_refused_even_when_it_allows_address_reuse(self):
        for address in ("127.0.0.1", "0.0.0.0"):
            with self.subTest(address=address), socket.socket() as listener:
                listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
                listener.bind((address, 0))
                listener.listen()
                result = self.probe(listener.getsockname()[1])
                self.assertNotEqual(0, result.returncode)
                self.assertIn("is in use or cannot be reserved", result.stderr)


if __name__ == "__main__":
    unittest.main()
