#!/usr/bin/env python3
"""Real Base project creation from exact public source; never run a historical target."""
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import shutil
import socket
import sqlite3
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
PROVIDER = ROOT / ".runtime-provider"
JAR = ROOT / "output/world-builder-tools/world-builder-tools.jar"
SOURCE_GIT = os.environ.get("WORLD_BUILDER_PRESERVATION_SOURCE_GIT")
LAUNCH = os.environ.get("WORLD_BUILDER_BASE_PROJECT_LAUNCH") == "1"
MAIN = "com.openrsc.worldbuilder.BaseLifecycleHarness"
HARNESS = """
package com.openrsc.worldbuilder;
import java.nio.file.*;
import java.util.*;
public final class BaseLifecycleHarness {
  public static void main(String[] args) throws Exception {
    Path project = Paths.get(args[1]);
    if ("commands".equals(args[0])) {
      Map<String,Object> result = new LinkedHashMap<String,Object>();
      result.put("identity", WorldBuilderCurrentBaseProjectContent.verifiedIdentity(project));
      result.put("server", WorldBuilderProcessSupervisor.defaultAdaptiveServerCommand(project));
      result.put("client", WorldBuilderProcessSupervisor.defaultAdaptiveClientCommand(project));
      WorldBuilderPreservationProjectEvidence.open(project, Paths.get(args[2]),
        WorldBuilderProviderCatalog.resolve(Paths.get(args[3]), Paths.get(args[4])));
      System.out.print(WorldBuilderJsonDocuments.pretty(result));
    } else if ("launch".equals(args[0])) {
      List<String> server = WorldBuilderProcessSupervisor.defaultAdaptiveServerCommand(project);
      List<String> client = new ArrayList<String>(WorldBuilderProcessSupervisor.defaultAdaptiveClientCommand(project));
      client.add(client.indexOf("-jar"), "-Dopenrsc.worldBuilderAutomatedExitOnReady=true");
      int exit = new WorldBuilderProcessSupervisor().superviseAdaptiveWithCommands(project, server, client, 180000L);
      if (exit != 0) throw new AssertionError("native Base authoring session exited " + exit);
      System.out.println("base-native-authoring-authenticated-ready-clean-exit");
    } else if ("desktop-create".equals(args[0])) {
      WorldBuilderProviderCatalog.Composition composition = WorldBuilderProviderCatalog.resolve(Paths.get(args[4]), Paths.get(args[5]));
      WorldBuilderLauncherModel model = new WorldBuilderLauncherModel(project, Paths.get(args[2]), Paths.get(args[3]),
        Integer.parseInt(args[6]), "preservation", composition);
      System.out.print(model.create(model.inspectDefaultTarget(), "Native Base desktop").toJson());
    } else {
      WorldBuilderAdaptiveProjectLifecycle.verifyProjectDirectory(project, true);
    }
  }
}
"""


def snapshot(root):
    return {str(path.relative_to(root)): (path.stat().st_mode & 0o777, hashlib.sha256(path.read_bytes()).hexdigest())
            for path in root.rglob("*") if path.is_file()}


def compile_harness(root):
    classes = root / "classes"
    classes.mkdir()
    source = root / "BaseLifecycleHarness.java"
    source.write_text(HARNESS)
    subprocess.run(["javac", "-source", "8", "-target", "8", "-cp", str(JAR), "-d", str(classes), str(source)],
                   check=True, capture_output=True)
    return classes


class BaseProjectLifecycleApiTest(unittest.TestCase):
    def test_native_creation_and_supervisor_entrypoints_compile_without_runtime_authority(self):
        with tempfile.TemporaryDirectory(prefix="base-lifecycle-api-") as temporary:
            compile_harness(Path(temporary))


@unittest.skipUnless(SOURCE_GIT, "exact reviewed public source input required for native Base lifecycle")
class BaseProjectLifecycleTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        expected = next(line.split("=", 1)[1] for line in (ROOT / "runtime-provider.lock").read_text().splitlines()
                        if line.startswith("RUNTIME_PROVIDER_COMMIT="))
        actual = subprocess.check_output(["git", "-C", str(PROVIDER), "rev-parse", "HEAD"], text=True).strip()
        if expected != actual or subprocess.run(["git", "-C", str(PROVIDER), "diff", "--quiet", "HEAD", "--"]).returncode:
            raise AssertionError("native Base lifecycle requires the exact unchanged selected provider")
        profile = json.loads((PROVIDER / "current-platform/runtime/current-base-v1/profile.json").read_text())
        if "authoringPolicy" not in profile:
            raise unittest.SkipTest("selected provider does not yet authorize isolated native Base authoring")
        subprocess.run(["python3", "scripts/build-current-base.py"], cwd=PROVIDER,
                       check=True, capture_output=True, timeout=240)
        cls.identity = PROVIDER / "output/current-platform/current-base-v1/composition-identity.json"
        cls.temporary = tempfile.TemporaryDirectory(prefix="base-project-lifecycle-")
        cls.addClassCleanup(cls.temporary.cleanup)
        cls.root = Path(cls.temporary.name)
        cls.classes = compile_harness(cls.root)
        # Reuse the sealed public-blob fixture builder, not another test execution.
        spec = importlib.util.spec_from_file_location("base_intake_fixture", ROOT / "tests/myworld/test-world-builder-preservation-source-intake.py")
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        cls.source_fixture = module.PreservationSourceIntakeTest
        cls.source_fixture.setUpClass()
        cls.addClassCleanup(cls.source_fixture.doClassCleanups)

    def invoke(self, *args, harness=False, timeout=180):
        prefix = ["java", "-Xmx1024m", "-cp", os.pathsep.join((str(self.classes), str(JAR))), MAIN] if harness else ["java", "-Xmx1024m", "-jar", str(JAR)]
        return subprocess.run(prefix + list(map(str, args)), capture_output=True, text=True, timeout=timeout)

    def test_real_create_reopen_export_and_native_launch_commands_keep_target_private(self):
        target = self.root / "historical-input"
        shutil.copytree(self.source_fixture.baseline, target)
        database = target / "server/inc/sqlite/preservation.db"
        database.parent.mkdir(parents=True)
        with sqlite3.connect(database) as connection:
            connection.executescript((PROVIDER / "server/database/sqlite/retro.sqlite").read_text())
            connection.execute("INSERT INTO players(username,pass,salt) VALUES ('inventedplayer','private-test-password','')")
        database.chmod(0o600)
        private, public = target / "server/server.pem", target / "server/client.pem"
        subprocess.run(["openssl", "genpkey", "-algorithm", "RSA", "-pkeyopt", "rsa_keygen_bits:512", "-out", str(private)], check=True, capture_output=True)
        subprocess.run(["openssl", "pkey", "-in", str(private), "-pubout", "-out", str(public)], check=True, capture_output=True)
        private.chmod(0o600); public.chmod(0o600)
        before = snapshot(target)
        application = self.root / "application-runtime"
        for source, relative in ((JAR, "launcher/world-builder-tools.jar"),
                (PROVIDER / "server/inc/sqlite/myworld_seed.db", "server/inc/sqlite/world_builder_seed.db"),
                (PROVIDER / "server/conf/world-builder/adaptive-runtime-capability-v2.json", "server/conf/world-builder/adaptive-runtime-capability-v2.json")):
            output = application / relative
            output.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(source, output)
        installation = self.root / "installation"
        installation.mkdir()
        discovered = self.invoke("discover-adaptive", "--target-root", target)
        self.assertEqual(0, discovered.returncode, discovered.stdout + discovered.stderr)
        report = self.root / "discovery.json"
        report.write_text(discovered.stdout)
        with socket.socket() as listener:
            listener.bind(("127.0.0.1", 0)); port = listener.getsockname()[1]
        created = self.invoke("create-project", "--installation-root", installation, "--runtime-root", application,
            "--target-root", target, "--discovery-report", report, "--display-name", "Native Base", "--port", port,
            "--confirm", "CREATE", "--provider-catalog-root", PROVIDER / "current-platform", "--composition-identity", self.identity)
        self.assertEqual(0, created.returncode, created.stdout + created.stderr)
        project = Path(json.loads(created.stdout)["projectRoot"])
        manifest = json.loads((project / "project.json").read_text())
        self.assertEqual("target-packed", manifest["origin"])
        self.assertEqual("preservation-source-jag-v1", manifest["target"]["adapterId"])
        self.assertFalse((project / "source/content-bundle").exists())
        self.assertFalse((project / "working/content-bundle").exists())
        for relative in ("server/server.pem", "server/client.pem", "server/inc/sqlite/preservation.db"):
            self.assertFalse((project / "source/original" / relative).exists())
        state = project / "working/authoring-state/world_builder.db"
        self.assertTrue(state.is_file())
        self.assertEqual(0o600, state.stat().st_mode & 0o777)
        self.assertEqual(0o700, state.parent.stat().st_mode & 0o777)
        with sqlite3.connect(state) as connection:
            self.assertEqual(0, connection.execute("SELECT COUNT(*) FROM players").fetchone()[0])
        self.assertFalse((project / "working/runtime/server/inc/sqlite/world_builder.db").exists())
        commands = self.invoke("commands", project, target, PROVIDER / "current-platform", self.identity, harness=True)
        self.assertEqual(0, commands.returncode, commands.stderr)
        commands = json.loads(commands.stdout)
        self.assertIn("-Dopenrsc.currentBaseAuthoringStateRoot=" + str(state.parent), commands["server"])
        for role in ("server", "client"):
            self.assertIn("-Dopenrsc.currentCompositionIdentityFile=" + str(project / "source/provider/composition-identity.json"), commands[role])
        self.assertEqual("current-base-v1", commands["identity"]["variantId"])
        if LAUNCH:
            self.assertTrue(os.environ.get("DISPLAY"), "coordinate the GUI acceptance lane and provide DISPLAY")
            source_before = snapshot(project / "source")
            for session in range(2):
                launched = self.invoke("launch", project, harness=True, timeout=240)
                logs = "\n".join(path.read_text(errors="replace") for path in (project / "logs").glob("*.log"))
                self.assertEqual(0, launched.returncode, launched.stdout + launched.stderr + logs[-12000:])
                self.assertIn("base-native-authoring-authenticated-ready-clean-exit", launched.stdout)
                self.assertIn("ADAPTIVE_WORLD_BUILDER_READY nativeTerrain=true initialRegion=true binding=true", logs)
                receipt = json.loads((project / "run/last-run.json").read_text())
                self.assertEqual(0, receipt["serverExit"])
                self.assertEqual(0, receipt["clientExit"])
                self.assertFalse((project / "run/server.pid").exists())
                self.assertFalse((project / "run/client.pid").exists())
                self.assertEqual(source_before, snapshot(project / "source"))
                self.assertEqual(before, snapshot(target))
            with sqlite3.connect(state) as connection:
                self.assertEqual(1, connection.execute("SELECT COUNT(*) FROM players").fetchone()[0])
                self.assertEqual("Builder", connection.execute("SELECT username FROM players").fetchone()[0])
        reopened = self.invoke("open-project", "--installation-root", installation, "--validate-only")
        self.assertEqual(0, reopened.returncode, reopened.stderr)
        exported = self.invoke("export-adaptive", "--project", project)
        self.assertEqual(0, exported.returncode, exported.stderr)
        self.assertEqual(before, snapshot(target))
        changed = project / "source/provider/installed/client/Cache/video/models.orsc"
        changed.write_bytes(changed.read_bytes() + b" ")
        refused = self.invoke("verify", project, harness=True)
        self.assertNotEqual(0, refused.returncode)
        self.assertEqual(before, snapshot(target))


if __name__ == "__main__":
    unittest.main()
