#!/usr/bin/env python3
"""Genuine public intake -> installed upgrade -> normal login -> two saved map imports.

Uses only fresh invented-state fixtures. Retains the fixture on failure, never
force-stops a process, and reuses the established normal-launch/UI test helpers.
"""
import importlib.util
import json
import os
from pathlib import Path
import shutil
import sqlite3
import subprocess
import struct
import time
import unittest
from PIL import Image, ImageChops

from adaptive_project_test_support import change_working_terrain

ROOT = Path(__file__).resolve().parents[2]
PROVIDER = ROOT / ".runtime-provider"
JAR = ROOT / "output/world-builder-tools/world-builder-tools.jar"


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


@unittest.skipUnless(os.environ.get("WORLD_BUILDER_PRESERVATION_SOURCE_GIT") and os.environ.get("DISPLAY"),
                     "Genuine Base cycle requires the explicit public source and coordinated GUI lane")
class BaseServerCycleTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.native = load("cycle_native", ROOT / "tests/myworld/test-world-builder-base-project-lifecycle.py")
        cls.native.KEEP_PROBE = True  # Never erase an uncertain installed runtime/session.
        cls.native.BaseProjectLifecycleTest.setUpClass()
        cls.addClassCleanup(cls.native.BaseProjectLifecycleTest.doClassCleanups)
        cls.fixture = cls.native.BaseProjectLifecycleTest()
        cls.root = cls.fixture.root
        cls.classes = cls.root / "classes"
        source = cls.root / "BaseCycleProbe.java"
        source.write_text('''package com.openrsc.worldbuilder;
import java.nio.file.*;
public final class BaseCycleProbe {
  public static void main(String[] args) throws Exception {
    if ("lease".equals(args[0])) {
      try (WorldBuilderCurrentRuntimeInstanceLease lease = WorldBuilderCurrentRuntimeInstanceLease.acquire(Paths.get(args[1]))) {
        lease.verifyHeld();
      }
    } else System.out.print(WorldBuilderJsonDocuments.pretty(WorldBuilderCurrentRuntimeInstalledGeneration.readSpecification(Paths.get(args[1]))));
  }
}
''')
        subprocess.run(["javac", "-cp", str(JAR), "-d", str(cls.classes), str(source)], check=True, capture_output=True)

    def cli(self, *args, success=True, timeout=600):
        result = subprocess.run(["java", "-Xmx1536m", "-jar", str(JAR), *map(str, args)],
                                capture_output=True, text=True, timeout=timeout)
        self.assertEqual(success, result.returncode == 0, result.stdout[-3000:] + result.stderr[-8000:])
        return json.loads(result.stdout) if success else result

    def probe(self, operation, path, success=True):
        result = subprocess.run(["java", "-cp", str(self.classes) + os.pathsep + str(JAR),
            "com.openrsc.worldbuilder.BaseCycleProbe", operation, str(path)], capture_output=True, text=True, timeout=60)
        self.assertEqual(success, result.returncode == 0, result.stderr)
        return result

    def test_real_initial_upgrade_normal_restart_and_two_map_imports(self):
        self.fixture.test_real_create_reopen_export_and_native_launch_commands_keep_target_private()
        print("Base cycle: genuine native project created", flush=True)
        self.target = self.root / "historical-input"
        self.project = next((self.root / "installation/projects").glob("*/project.json")).parent
        before = self.native.snapshot(self.target)
        source_before = self.native.snapshot(self.project / "source")
        workspace = self.root / "transactions"
        workspace.mkdir(mode=0o700)
        common = ["--target-root", self.target, "--transaction-root", workspace,
            "--transaction-id", "base-initial", "--adapter", "preservation-family-v1",
            "--provider-catalog-root", PROVIDER / "current-platform", "--composition-identity", self.fixture.identity,
            "--preservation-project", self.project]
        preview = self.cli("preview-current-runtime-upgrade", *common)
        self.assertTrue(preview["activationAuthorized"])
        applied = self.cli("apply-current-runtime-upgrade", *common, "--confirmation-identity", preview["confirmationIdentity"])
        self.assertEqual("successful", applied["status"])
        print("Base cycle: initial runtime upgrade committed", flush=True)
        self.assert_original(before, source_before)
        self.exercise_installed_cycle(workspace, before, source_before)

    def exercise_installed_cycle(self, workspace, before, source_before):
        self.pair = load("cycle_normal", ROOT / "tests/myworld/test-world-builder-initial-instance-integration.py").InitialInstanceIntegrationTest()
        import tempfile
        self.pair.root = Path(tempfile.mkdtemp(prefix="normal-session-logs-", dir=self.root))
        self.pair.processes, self.pair.previous_nonces = [], set()
        self.pair.provider_ui = load("cycle_ui", PROVIDER / "tests/myworld/test-current-base-installed-launch.py")
        self.pair.invoke = self.probe
        self.addCleanup(self.close_pair)
        spec = self.normal_login()
        print("Base cycle: normal player login and clean shutdown passed", flush=True)
        for name in ("badwords.txt", "goodwords.txt", "alertwords.txt"):
            self.assertEqual(b"", (Path(spec["serverSideStateRoot"]) / name).read_bytes())
        live_database = Path(spec["serverStateRoot"]) / "current_base.db"
        saved_gameplay = live_database.read_bytes()
        recovered = self.cli("recover-current-runtime-upgrade", "--target-root", self.target,
            "--transaction-root", workspace, "--transaction-id", "base-initial", success=False)
        # Recovery is for interrupted upgrades, not a completed-upgrade Undo.
        self.assertIn("Recovery receipt does not authorize this exact interrupted plan", recovered.stderr)
        self.assertEqual(saved_gameplay, live_database.read_bytes())
        previous_map = spec["mapPackageFingerprintSha256"]
        for number in (1, 2):
            change_working_terrain(self.project)
            self.cli("save-project", "--project", self.project)
            exported = self.cli("export-adaptive", "--project", self.project)
            args = ["--project", self.project, "--export", exported["exportDirectory"], "--target-root", self.target,
                "--transaction-root", workspace, "--transaction-id", "map-" + str(number)]
            preview = self.cli("preview-current-map-import", *args)
            before_state = self.native.snapshot(Path(spec["serverStateRoot"]))
            result = self.cli("apply-current-map-import", *args, "--confirmation-identity", preview["confirmationIdentity"])
            self.assertEqual("successful", result["status"])
            self.assertEqual(before_state, self.native.snapshot(Path(spec["serverStateRoot"])))
            updated = json.loads(self.probe("inspect", self.target).stdout)
            for key in ("serverCodeRoot", "clientCodeRoot", "serverStateRoot", "clientStateRoot", "serverSideStateRoot", "clientSideStateRoot"):
                self.assertEqual(spec[key], updated[key], key)
            self.assertNotEqual(previous_map, updated["mapPackageFingerprintSha256"])
            previous_map = updated["mapPackageFingerprintSha256"]
            spec = self.normal_login()
            database = Path(spec["serverStateRoot"]) / "current_base.db"
            gameplay = database.read_bytes()
            recovered = self.cli("recover-current-map-import", "--target-root", self.target,
                "--transaction-root", workspace, "--transaction-id", "map-" + str(number),
                "--confirmed-plan-sha256", preview["planFingerprintSha256"])
            self.assertEqual("successful", recovered["status"])
            self.assertEqual(gameplay, database.read_bytes())
            self.assert_original(before, source_before)
            print("Base cycle: saved map import, normal restart, and state-safe recovery passed: " + str(number), flush=True)

    def assert_original(self, before, source_before):
        current = self.native.snapshot(self.target)
        self.assertEqual(before, {key: current[key] for key in before})
        self.assertEqual(source_before, self.native.snapshot(self.project / "source"))

    def close_pair(self):
        for record in reversed(self.pair.processes):
            self.pair.stop(record)
        self.probe("lease", self.pair.anchor)

    def normal_login(self):
        spec = json.loads(self.probe("inspect", self.target).stdout)
        pair = self.pair
        pair.anchor = Path(spec["installationRoot"])
        selection = json.loads((pair.anchor / "active-launch.json").read_text())
        pair.launches, pair.descriptors, pair.descriptor_hashes, pair.map_hashes = {}, {}, {}, {}
        pair.side = {}
        import hashlib
        for role in ("server", "client"):
            path = pair.anchor.parent / "generations" / spec["generationId"] / (role + "-launch.json")
            self.assertEqual(selection[role + "DescriptorSha256"], hashlib.sha256(path.read_bytes()).hexdigest())
            document = json.loads(path.read_text())
            profile = json.loads(Path(document["runtimeProfile"]["path"]).read_text())["installedLaunch"]
            expected = "<codeRoot>/core.jar:<codeRoot>/plugins.jar" if role == "server" else "<codeRoot>/Open_RSC_Client.jar"
            self.assertEqual(expected, profile[role + "Classpath"])
            jars = expected.replace("<codeRoot>", document["codeRoot"]).split(":")
            pair.launches[role] = dict(command=[shutil.which("java"), "-Xmx768m", "-cp", os.pathsep.join(jars),
                profile[role + "MainClass"], "--launch", str(path)], workingDirectory=document["workingRoot"])
            pair.descriptors[role] = document
            pair.side[role] = Path(document["sideStateRoot"])
            pair.descriptor_hashes[role] = hashlib.sha256(path.read_bytes()).hexdigest()
            pair.map_hashes[role] = hashlib.sha256((Path(document["mapRoot"]) / "manifest.json").read_bytes()).hexdigest()
        server, client = pair.start("server"), pair.start("client")
        self.manual_public_login(server["process"], client["process"])
        pair.stop(client)
        pair.stop(server)
        for record in (server, client):
            pair.previous_nonces.add(record["binding"]["nonce"])
        self.probe("lease", pair.anchor)
        with sqlite3.connect(Path(spec["serverStateRoot"]) / "current_base.db") as connection:
            self.assertEqual((321,), connection.execute("SELECT amount FROM itemstatuses JOIN invitems USING(itemID) WHERE playerID=901 AND catalogID=10").fetchone())
            self.assertEqual((3,), connection.execute("SELECT stage FROM quests WHERE playerID=901 AND id=1").fetchone())
            self.assertEqual((11, 16, 21), connection.execute("SELECT prayer,magic,woodcut FROM maxstats WHERE playerID=901").fetchone())
            self.assertEqual((0,), connection.execute("SELECT online FROM players WHERE id=901").fetchone())
        return spec

    def manual_public_login(self, server, client):
        """Same owned-window login as the normal fixture, without its synthetic green-map assumption."""
        pair = self.pair
        def xdo(*args):
            return subprocess.run(["xdotool", *map(str, args)], capture_output=True, text=True, check=True, timeout=10).stdout
        deadline = time.monotonic() + 30
        while "Got server configs!" not in pair.log(client) and time.monotonic() < deadline:
            time.sleep(0.05)
        self.assertIn("Got server configs!", pair.log(client))
        time.sleep(2)
        windows = xdo("search", "--onlyvisible", "--pid", client.pid).split()
        self.assertEqual(1, len(windows))
        window = windows[0]
        xdo("windowactivate", "--sync", window)
        xdo("windowfocus", "--sync", window)
        def capture():
            raw = subprocess.run(["xwd", "-silent", "-nobdrs", "-id", window], check=True, capture_output=True).stdout
            header = struct.unpack(">25I", raw[:100])
            self.assertEqual(32, header[11])
            offset = header[0] + header[19] * 12
            return Image.frombytes("RGB", (header[4], header[5]), raw[offset:], "raw",
                "BGRX" if header[7] == 0 else "XRGB", header[12])
        def click(x, y):
            xdo("mousemove", "--window", window, x, y)
            self.assertEqual(str(client.pid), xdo("getwindowfocus", "getwindowpid").strip())
            xdo("click", "1")
        before = capture()
        before.save(pair.root / (str(client.pid) + "-login.png"))
        width, height = before.size
        pixels = before.load()
        blue = [(x, y) for y in range(height // 3, height * 9 // 10) for x in range(width // 2, width * 4 // 5)
                if 40 < pixels[x, y][0] < 150 and pixels[x, y][0] <= pixels[x, y][1] and pixels[x, y][2] > pixels[x, y][1] + 10]
        self.assertGreater(len(blue), 1000, "Normal Existing User button must be visible")
        click(sum(x for x, _ in blue) // len(blue), sum(y for _, y in blue) // len(blue))
        time.sleep(0.3)
        for value in ("launchtest", "launchpass"):
            self.assertEqual(str(client.pid), xdo("getwindowfocus", "getwindowpid").strip())
            xdo("type", "--delay", "70", value)
            time.sleep(0.3)
            xdo("key", "Return")
            time.sleep(0.3)
        deadline = time.monotonic() + 30
        while "Player Loaded: launchtest" not in pair.log(server) and time.monotonic() < deadline:
            time.sleep(0.1)
        capture().save(pair.root / (str(client.pid) + "-submitted.png"))
        self.assertIn("Player Loaded: launchtest", pair.log(server)[-5000:])
        time.sleep(3)
        click(width // 8, height // 2)
        time.sleep(3)
        after = capture()
        changed = width * height - ImageChops.difference(before, after).convert("L").histogram()[0]
        self.assertGreater(changed, width * height // 4, "Normal login must render the imported world, not a loading screen")
        # The public spawn has buildings/paths as well as grass. Do not require
        # the unrelated fixture's one-third all-green terrain palette.
        world = after.crop((width // 8, height // 5, width * 7 // 8, height * 4 // 5))
        colors = world.getcolors(world.width * world.height)
        self.assertGreater(len(colors), 100, "Imported terrain and scenery must have visible detail")
        self.assertLess(sum(count for count, rgb in colors if rgb == (0, 0, 0)), world.width * world.height // 3,
                        "Imported world must not be mostly blocked void")
        avatar = after.crop((width // 2 - 40, height // 2 - 50, width // 2 + 40, height // 2 + 65))
        colors = avatar.getcolors(80 * 115)
        self.assertLess(sum(count for count, rgb in colors if rgb == (0, 0, 0)), 80 * 115 // 5)
        self.assertGreater(sum(count for count, (r, g, b) in colors if b > 20 or r > g + 12), 100,
                           "The normally authenticated player must be visibly rasterized")
        self.assertNotIn("CURRENT_BASE_RUNTIME_EXECUTION", pair.log(client))
        self.assertFalse((pair.side["client"] / "credentials.txt").exists())


if __name__ == "__main__":
    unittest.main()
