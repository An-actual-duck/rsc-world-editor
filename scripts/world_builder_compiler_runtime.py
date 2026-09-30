"""Verify reviewed compiler-capable Java inputs before shipping them.

The Linux input is executed; Windows is inspected offline using the reviewed
Linux jimage tool. Release MODULES metadata is deliberately not evidence.
These inputs are trusted release toolchains, never discovered target programs.
"""

from __future__ import annotations

import argparse
import json
import os
import subprocess
import tempfile
from pathlib import Path


PROBE = r'''
import java.nio.file.Files;
import java.nio.file.Path;
import javax.tools.ToolProvider;
class WorldBuilderCompilerProbe {
    public static void main(String[] args) throws Exception {
        var compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) throw new IllegalStateException("No system Java compiler");
        Path output = Path.of(args[0]);
        Path source = output.resolve("CompiledProbe.java");
        Files.writeString(source, "class CompiledProbe { int value() { return 17; } }");
        int result = compiler.run(null, null, null, "--release", "17", "-proc:none",
                "-d", output.toString(), source.toString());
        if (result != 0 || !Files.isRegularFile(output.resolve("CompiledProbe.class")))
            throw new IllegalStateException("System Java compiler could not compile Java 17");
        System.out.println("WORLD_BUILDER_COMPILER_OK");
    }
}
'''


def run(arguments: list[str], directory: Path) -> str:
    environment = dict(os.environ)
    for name in ("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS", "CLASSPATH"):
        environment.pop(name, None)
    try:
        result = subprocess.run(arguments, cwd=directory, env=environment,
                                stdin=subprocess.DEVNULL, capture_output=True,
                                text=True, timeout=60)
    except (OSError, subprocess.TimeoutExpired) as error:
        raise ValueError(f"Compiler runtime verification could not run: {error}") from error
    if result.returncode != 0:
        raise ValueError("Compiler runtime verification failed: " + result.stderr[-2000:])
    if len(result.stdout) > 16 * 1024 * 1024:
        raise ValueError("Compiler module inventory exceeds the inspection limit")
    return result.stdout


def verify_compiler_runtimes(linux: Path, windows: Path) -> dict[str, str]:
    linux, windows = linux.resolve(strict=True), windows.resolve(strict=True)
    java = linux / "bin/java"
    jimage = linux / "bin/jimage"
    for executable in (java, jimage):
        if not executable.is_file() or not os.access(executable, os.X_OK):
            raise ValueError(f"Compiler-capable Linux Java input requires executable {executable.name}")
    for platform, runtime in (("Linux", linux), ("Windows", windows)):
        if not (runtime / "lib/modules").is_file():
            raise ValueError(f"Compiler-capable {platform} Java input requires lib/modules")
    with tempfile.TemporaryDirectory(prefix="world-builder-compiler-check-") as temporary:
        directory = Path(temporary)
        source = directory / "WorldBuilderCompilerProbe.java"
        source.write_text(PROBE, encoding="utf-8")
        output = run([str(java), "--source", "17", str(source), str(directory)], directory)
        compiled = directory / "CompiledProbe.class"
        if output.strip() != "WORLD_BUILDER_COMPILER_OK" or not compiled.is_file():
            raise ValueError("Linux Java input did not prove ToolProvider compilation")
        if compiled.read_bytes()[:4] != b"\xca\xfe\xba\xbe":
            raise ValueError("Linux ToolProvider probe did not produce a Java class")
        inventory = run([str(jimage), "list", str(windows / "lib/modules")], directory)
        modules: dict[str, set[str]] = {}
        current = None
        for line in inventory.splitlines():
            if line.startswith("Module: "):
                current = line.removeprefix("Module: ").strip()
                modules[current] = set()
            elif current and line.startswith("    "):
                modules[current].add(line.strip())
        expected = {
            "java.compiler": {"module-info.class", "javax/tools/ToolProvider.class"},
            "jdk.compiler": {"module-info.class", "com/sun/tools/javac/api/JavacTool.class",
                             "com/sun/tools/javac/main/JavaCompiler.class"},
        }
        for module, entries in expected.items():
            if not entries.issubset(modules.get(module, set())):
                raise ValueError(f"Windows runtime image lacks required compiler module contents: {module}")
    return {"linux": "ToolProvider compiled Java 17 with annotation processing disabled",
            "windows": "Offline lib/modules inventory contains compiler implementation; native execution pending"}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--linux-jre", required=True, type=Path)
    parser.add_argument("--windows-jre", required=True, type=Path)
    options = parser.parse_args()
    try:
        print(json.dumps(verify_compiler_runtimes(options.linux_jre, options.windows_jre)))
    except (ValueError, OSError) as error:
        parser.exit(1, f"FAIL: {error}\n")
