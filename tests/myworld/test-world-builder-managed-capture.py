#!/usr/bin/env python3
"""Opt-in read-only installed-map intake; projects/exports use temporary directories.

WORLD_BUILDER_MANAGED_CAPTURE_INSTALLATION names a matching installed candidate
directly inside a stopped, already upgraded target. No import is applied.
"""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
JAR = ROOT / "output/world-builder-tools/world-builder-tools.jar"
INSTALLATION = os.environ.get("WORLD_BUILDER_MANAGED_CAPTURE_INSTALLATION")


@unittest.skipUnless(INSTALLATION, "explicit stopped managed installation required")
class ManagedCaptureTest(unittest.TestCase):
    def test_desktop_capture_export_and_import_preview_use_installed_map(self):
        installation = Path(INSTALLATION).resolve()
        target = installation.parent
        ledger_path = target / ".world-builder/runtime-ledger-v1.json"
        before = ledger_path.read_bytes()
        with tempfile.TemporaryDirectory(prefix="managed-capture-test-") as temporary:
            root = Path(temporary)
            app = root / "app"
            app.mkdir()
            workspace = root / "transactions"
            workspace.mkdir(mode=0o700)
            harness = root / "ManagedCaptureProbe.java"
            harness.write_text('''package com.openrsc.worldbuilder;
import java.nio.file.*;
import java.util.*;
public final class ManagedCaptureProbe {
  public static void main(String[] args) throws Exception {
    Path app = Paths.get(args[0]), installed = Paths.get(args[1]), target = installed.getParent();
    WorldBuilderProviderCatalog.Composition composition = WorldBuilderProviderCatalog.resolve(
      installed.resolve("current-platform"), installed.resolve("current-platform/composition-identity.json"));
    WorldBuilderLauncherModel model = new WorldBuilderLauncherModel(app, installed.resolve("builder-runtime"),
      target, 43831, null, composition);
    WorldBuilderLauncherModel.DiscoveryPreview preview = model.inspectDefaultTarget();
    if (!preview.report.isManaged() || model.inspectLegacyMigration(preview) != null)
      throw new AssertionError("Current target must bypass legacy migration");
    model.create(preview, "Managed capture regression");
    Path project = model.projects().get(0).projectRoot;
    List<String> server = WorldBuilderProcessSupervisor.defaultAdaptiveServerCommand(project);
    List<String> client = WorldBuilderProcessSupervisor.defaultAdaptiveClientCommand(project);
    if (!server.toString().contains("currentBaseAuthoringStateRoot") || !client.contains("-Dopenrsc.worldBuilderPreservationUi=true"))
      throw new AssertionError("Managed project lost native Base authoring launch");
    Map<String,Object> result = new LinkedHashMap<>();
    result.put("project", project.toString());
    result.put("mapRoot", WorldBuilderCurrentRuntimeInstalledGeneration.readSpecification(target).get("mapRoot"));
    System.out.print(WorldBuilderJsonDocuments.pretty(result));
  }
}
''')
            subprocess.run(["javac", "-cp", str(JAR), "-d", str(root), str(harness)], check=True, capture_output=True)
            result = subprocess.run(["java", "-cp", str(root) + os.pathsep + str(JAR),
                "com.openrsc.worldbuilder.ManagedCaptureProbe", str(app), str(installation)],
                capture_output=True, text=True, timeout=120)
            self.assertEqual(0, result.returncode, result.stderr)
            captured = json.loads(result.stdout)
            project = Path(captured["project"])
            def inventory(path):
                return {str(p.relative_to(path)): hashlib.sha256(p.read_bytes()).hexdigest()
                        for p in path.rglob("*") if p.is_file()}
            self.assertEqual(inventory(Path(captured["mapRoot"])), inventory(project / "source/layered-baseline/package"))
            def cli(*args):
                result = subprocess.run(["java", "-jar", str(JAR), *map(str, args)], capture_output=True, text=True, timeout=90)
                self.assertEqual(0, result.returncode, result.stderr)
                return json.loads(result.stdout)
            exported = cli("export-adaptive", "--project", project)
            plan = cli("preview-current-map-import", "--project", project, "--export", exported["exportDirectory"],
                       "--target-root", target, "--transaction-root", workspace, "--transaction-id", "managed-capture-review")
            self.assertEqual(str(project), plan["projectRoot"])
            self.assertFalse(any(workspace.iterdir()), "Preview must not publish transaction outputs")
        self.assertEqual(before, ledger_path.read_bytes())


if __name__ == "__main__":
    unittest.main()
