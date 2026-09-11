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
import sys
import tempfile
import unittest
import zipfile

ROOT = Path(__file__).resolve().parents[2]
PROVIDER = ROOT / ".runtime-provider"
JAR = ROOT / "output/world-builder-tools/world-builder-tools.jar"
sys.path.insert(0, str(ROOT / "scripts"))
from world_builder_base_catalog import selected_base_files
SOURCE_GIT = os.environ.get("WORLD_BUILDER_PRESERVATION_SOURCE_GIT")
LAUNCH = os.environ.get("WORLD_BUILDER_BASE_PROJECT_LAUNCH") == "1"
KEEP_PROBE = os.environ.get("WORLD_BUILDER_BASE_PROJECT_KEEP_PROBE") == "1"
MAIN = "com.openrsc.worldbuilder.BaseLifecycleHarness"
HARNESS = """
package com.openrsc.worldbuilder;
import java.nio.file.*;
import java.util.*;
public final class BaseLifecycleHarness {
  public static void main(String[] args) throws Exception {
    Path project = Paths.get(args[1]);
    if ("presenter".equals(args[0])) {
      Class<?> launch = Class.forName("com.openrsc.worldbuilder.WorldBuilderProcessSupervisor$AdaptiveLaunch");
      java.lang.reflect.Method method = launch.getDeclaredMethod("hasBasePresenter", Path.class);
      method.setAccessible(true);
      System.out.print(method.invoke(null, project));
    } else if ("commands".equals(args[0])) {
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
      WorldBuilderLauncherModel model = new WorldBuilderLauncherModel(project, Paths.get(args[2]), Paths.get(args[3]),
        Integer.parseInt(args[4]), "preservation");
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
    def test_sealed_old_clients_keep_software_and_complete_presenter_is_required(self):
        entries = ("org/lwjgl/Version.class", "org/lwjgl/glfw/GLFW.class", "org/lwjgl/opengl/GL.class",
                   "linux/x64/org/lwjgl/liblwjgl.so", "linux/x64/org/lwjgl/glfw/libglfw.so",
                   "linux/x64/org/lwjgl/opengl/liblwjgl_opengl.so", "windows/x64/org/lwjgl/lwjgl.dll",
                   "windows/x64/org/lwjgl/glfw/glfw.dll", "windows/x64/org/lwjgl/opengl/lwjgl_opengl.dll")
        with tempfile.TemporaryDirectory(prefix="base-presenter-api-") as temporary:
            root = Path(temporary)
            classes = compile_harness(root)
            for missing in (None, *entries):
                archive = root / "client.jar"
                with zipfile.ZipFile(archive, "w") as jar:
                    for entry in entries:
                        if entry != missing:
                            jar.writestr(entry, b"capability fixture, not trusted runtime")
                before = archive.read_bytes()
                result = subprocess.run(["java", "-cp", str(classes) + os.pathsep + str(JAR),
                    MAIN, "presenter", str(archive)], capture_output=True, text=True, check=True)
                self.assertEqual("true" if missing is None else "false", result.stdout)
                self.assertEqual(before, archive.read_bytes())

    def test_desktop_native_base_route_precedes_custom_content_and_migration(self):
        source = (ROOT / "tools/world-builder/src/com/openrsc/worldbuilder/WorldBuilderDesktopLauncher.java").read_text()
        routing = source[source.index("final boolean preferMostRecentlyModified) {", source.index("private void inspectLegacyMigrationThenShow")):]
        self.assertLess(routing.index("if (!preview.canCreateServerProject())"), routing.index("showNativeBasePreview(preview);"))
        self.assertLess(routing.index("showNativeBasePreview(preview);"), routing.index('runTask("Checking for legacy map changes'))
        self.assertIn("showNativeBasePreview(preview);\n\t\t\t\treturn;", routing)
        dialog = source.split("private void showNativeBasePreview(", 1)[1].split("private void exportDetectedProviderDiagnostic", 1)[0]
        self.assertIn("createPreviewedProject(preview, displayName);", dialog)
        for forbidden in ("inspectPortableProvider", "importPortableProvider", "guidedProvider", "createMigrated"):
            self.assertNotIn(forbidden, dialog)

    def test_native_creation_and_supervisor_entrypoints_compile_without_runtime_authority(self):
        with tempfile.TemporaryDirectory(prefix="base-lifecycle-api-") as temporary:
            compile_harness(Path(temporary))


@unittest.skipUnless(SOURCE_GIT, "exact reviewed public source input required for native Base lifecycle")
class BaseProjectLifecycleTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        expected = next(line.split("=", 1)[1] for line in (ROOT / "runtime-provider.lock").read_text().splitlines()
                        if line.startswith("RUNTIME_PROVIDER_COMMIT="))
        # Only the genuine two-build cycle supplies an independently checked
        # published ancestor. Ordinary lifecycle coverage stays exactly pinned.
        expected = getattr(cls, "expected_provider_commit", expected)
        actual = subprocess.check_output(["git", "-C", str(PROVIDER), "rev-parse", "HEAD"], text=True).strip()
        if expected != actual or subprocess.run(["git", "-C", str(PROVIDER), "diff", "--quiet", "HEAD", "--"]).returncode:
            raise AssertionError("native Base lifecycle requires the exact unchanged selected provider")
        profile = json.loads((PROVIDER / "current-platform/runtime/current-base-v1/profile.json").read_text())
        if "authoringPolicy" not in profile:
            raise unittest.SkipTest("selected provider does not yet authorize isolated native Base authoring")
        subprocess.run(["python3", "scripts/build-current-base.py"], cwd=PROVIDER,
                       check=True, capture_output=True, timeout=240)
        cls.identity = PROVIDER / "output/current-platform/current-base-v1/composition-identity.json"
        cls.root = Path(tempfile.mkdtemp(prefix="base-project-lifecycle-"))
        if KEEP_PROBE:
            cls.addClassCleanup(lambda: print("Retained invented-state Base lifecycle probe: " + str(cls.root), flush=True))
        else:
            cls.addClassCleanup(shutil.rmtree, cls.root)
        cls.classes = compile_harness(cls.root)
        # Reuse the sealed public-blob fixture builder, not another test execution.
        spec = importlib.util.spec_from_file_location("base_intake_fixture", ROOT / "tests/myworld/test-world-builder-preservation-source-intake.py")
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        cls.source_fixture = module.PreservationSourceIntakeTest
        cls.source_fixture.setUpClass()
        cls.addClassCleanup(cls.source_fixture.doClassCleanups)
        # Lifecycle/cycle proof must tolerate the full stock checkout shape,
        # not only the selected source/map inputs used by unit fixtures.
        module.populate_public_stock(cls.source_fixture.baseline)

    def invoke(self, *args, harness=False, timeout=180):
        prefix = ["java", "-Xmx1024m", "-cp", os.pathsep.join((str(self.classes), str(JAR))), MAIN] if harness else ["java", "-Xmx1024m", "-jar", str(JAR)]
        return subprocess.run(prefix + list(map(str, args)), capture_output=True, text=True, timeout=timeout)

    def test_real_create_reopen_export_and_native_launch_commands_keep_target_private(self):
        target = self.root / "historical-input"
        shutil.copytree(self.source_fixture.baseline, target)
        # Independent disposable server endpoints; never compete with another
        # regression fixture's historical default ports.
        with socket.socket() as game, socket.socket() as websocket:
            game.bind(("127.0.0.1", 0)); websocket.bind(("127.0.0.1", 0))
            (target / "server/connections.conf").write_text(
                "bind_address: 127.0.0.1\nserver_port: " + str(game.getsockname()[1])
                + "\nws_server_port: " + str(websocket.getsockname()[1]) + "\ndb_type: sqlite\n")
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
        # Match the shipped folder, including bundled provider and projects
        # under one installation. External provider fixtures missed this layout.
        for relative, (data, mode) in selected_base_files(PROVIDER).items():
            path = installation / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(data)
            path.chmod(mode)
        discovered = self.invoke("discover-adaptive", "--target-root", target)
        self.assertEqual(0, discovered.returncode, discovered.stdout + discovered.stderr)
        report = self.root / "discovery.json"
        report.write_text(discovered.stdout)
        with socket.socket() as listener:
            listener.bind(("127.0.0.1", 0)); port = listener.getsockname()[1]
        # Exercise the same no-overlay launcher model call as the native Swing
        # dialog, including discovery and Base selection, not only the CLI.
        created = self.invoke("desktop-create", installation, application, target,
            port, harness=True)
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
        # Retained predecessor fixtures intentionally lack the new presenter;
        # each project's sealed client, not the surrounding installation, wins.
        with zipfile.ZipFile(project / "working/runtime/client/Open_RSC_Client.jar") as archive:
            presenter = "true" if "org/lwjgl/glfw/GLFW.class" in archive.namelist() else "false"
        for name in ("openglPresenter", "openglInput", "openglPrimaryWindow"):
            self.assertIn("-Dspoiledmilk." + name + "=" + presenter, commands["client"])
        for name in ("directFramebuffer", "skipLegacyWorldRaster", "openglWorldMesh",
                     "openglWorldMeshTexturedVisible", "openglWorldChunksReplacementComposite",
                     "openglWorldSpritesVisible", "openglWorldUiReplay"):
            self.assertIn("-Dspoiledmilk." + name + "=false", commands["client"])
        for name in ("opengl_sprite_overlay", "opengl_ui_base_frame", "opengl_native_ui_replace"):
            self.assertIn("-Dspoiled_milk." + name + "=false", commands["client"])
        self.assertIn("-Dspoiledmilk.openglWindowMode=borderless-fullscreen", commands["client"])
        if LAUNCH:
            self.assertTrue(os.environ.get("DISPLAY"), "coordinate the GUI acceptance lane and provide DISPLAY")
            source_before = snapshot(project / "source")
            for session in range(2):
                launched = self.invoke("launch", project, harness=True, timeout=240)
                logs = "\n".join(path.read_text(errors="replace") for path in (project / "logs").glob("*.log"))
                self.assertEqual(0, launched.returncode, launched.stdout + launched.stderr + logs[-12000:])
                self.assertIn("base-native-authoring-authenticated-ready-clean-exit", launched.stdout)
                self.assertIn("ADAPTIVE_WORLD_BUILDER_READY nativeTerrain=true initialRegion=true binding=true", logs)
                if presenter == "true":
                    self.assertIn("OpenGL presenter active.", logs)
                    self.assertIn("OpenGL primary window active; Swing client window is hidden.", logs)
                    self.assertIn("OpenGL window mode: borderless fullscreen ", logs)
                    self.assertIn("OpenGL scale mode: aspect-fit automatic", logs)
                receipt = json.loads((project / "run/last-run.json").read_text())
                self.assertEqual(0, receipt["serverExit"])
                self.assertEqual(0, receipt["clientExit"])
                self.assertFalse((project / "run/server.pid").exists())
                self.assertFalse((project / "run/client.pid").exists())
                self.assertEqual(source_before, snapshot(project / "source"))
                self.assertEqual(before, snapshot(target))
            with sqlite3.connect(state) as connection:
                self.assertEqual(1, connection.execute("SELECT COUNT(*) FROM players").fetchone()[0])
                self.assertEqual("builder", connection.execute("SELECT username FROM players").fetchone()[0].lower())
        reopened = self.invoke("open-project", "--installation-root", installation, "--validate-only")
        self.assertEqual(0, reopened.returncode, reopened.stderr)
        exported = self.invoke("export-adaptive", "--project", project)
        self.assertEqual(0, exported.returncode, exported.stderr)
        self.assertEqual(before, snapshot(target))
        changed = project / "source/provider/installed/client/Cache/video/models.orsc"
        original = changed.read_bytes()
        changed.write_bytes(original + b" ")
        refused = self.invoke("verify", project, harness=True)
        self.assertNotEqual(0, refused.returncode)
        changed.write_bytes(original)
        self.assertEqual(before, snapshot(target))


if __name__ == "__main__":
    unittest.main()
