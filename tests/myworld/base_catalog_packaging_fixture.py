"""Small synthetic packaging payloads; never runtime/gameplay acceptance evidence."""
from functools import lru_cache
import io
import json
from pathlib import Path
import shutil
import subprocess
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[2]
PROVIDER = ROOT / ".runtime-provider"


def prepare_base_catalog(root):
    shutil.copytree(PROVIDER / "current-platform", root / "current-platform")
    spec = json.loads((root / "current-platform/bundle-specs/current-base-v1.json").read_text())
    for artifact in spec["artifacts"]:
        relative = artifact["sourcePath"]
        path = root / relative
        if path.exists():
            continue
        path.parent.mkdir(parents=True, exist_ok=True)
        if relative.startswith("output/"):
            if path.suffix in (".zip", ".jar"):
                with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as archive:
                    archive.writestr("fixture/packaging-only.txt", relative)
            else:
                path.write_text("synthetic packaging payload: " + relative + "\n")
        elif relative == "scripts/build-current-base.py":
            path.write_text("# Packaging fixture is already materialized; no runtime is built.\n")
        else:
            shutil.copy2(PROVIDER / relative, path)
    resolver = root / "scripts/current-platform-composition.py"
    namespace = {"__file__": str(resolver), "__name__": "packaging_fixture"}
    exec(compile(resolver.read_bytes(), str(resolver), "exec"), namespace)
    catalog = namespace["Catalog"](root / "current-platform")
    identity = namespace["resolve_composition"](catalog, "current-base-v1", [], root)
    identity_path = root / "output/current-platform/current-base-v1/composition-identity.json"
    identity_path.write_text(json.dumps(identity, indent=2) + "\n")
    paths = {artifact["sourcePath"] for artifact in spec["artifacts"]}
    paths.update("current-platform/" + row["relativePath"] for row in catalog.platform["schemaContracts"])
    files = {relative: ((root / relative).read_bytes(), (root / relative).stat().st_mode & 0o777)
             for relative in paths}
    files["current-platform/composition-identity.json"] = (identity_path.read_bytes(), identity_path.stat().st_mode & 0o777)
    return files


@lru_cache(maxsize=1)
def compiled_tools():
    # Compile once per test process. Fake class bytes cannot exercise the real
    # selected-catalog exporter; real runtime artifacts are unnecessary here.
    with tempfile.TemporaryDirectory(prefix="packaging-tools-classes-") as temporary:
        classes = Path(temporary)
        subprocess.run(["javac", "-source", "8", "-target", "8", "-encoding", "UTF-8", "-d", str(classes),
                        *map(str, sorted((ROOT / "tools/world-builder/src").rglob("*.java")))],
                       check=True, capture_output=True, timeout=120)
        entries = {str(path.relative_to(classes)): path.read_bytes()
                   for path in classes.rglob("*.class")}
    resources = ROOT / "tools/world-builder/resources"
    entries.update({str(path.relative_to(resources)): path.read_bytes()
                    for path in resources.rglob("*") if path.is_file()})
    entries["META-INF/MANIFEST.MF"] = b"Manifest-Version: 1.0\nMain-Class: com.openrsc.worldbuilder.WorldBuilderCli\n\n"
    return entries


def tools_jar(allowlist):
    entries = dict(compiled_tools())
    entries["com/openrsc/worldbuilder/runtime-asset-allowlist-v1.txt"] = allowlist
    stream = io.BytesIO()
    with zipfile.ZipFile(stream, "w", zipfile.ZIP_DEFLATED) as archive:
        for name, data in sorted(entries.items()):
            archive.writestr(name, data)
    return stream.getvalue()
