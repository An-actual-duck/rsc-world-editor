#!/usr/bin/env python3
"""Compiler input proof and refusal, separate from synthetic archive fixtures."""
import os
from pathlib import Path
import shutil
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "scripts"))
from world_builder_compiler_runtime import verify_compiler_runtimes
from compiler_runtime_fixture import PROBE_SHELL, MODULE_INVENTORY, JIMAGE_SHELL


class CompilerRuntimeTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="compiler-refusal-")
        self.addCleanup(self.temporary.cleanup)
        self.linux = Path(self.temporary.name) / "linux"
        self.windows = Path(self.temporary.name) / "windows"
        for runtime in (self.linux, self.windows):
            (runtime / "bin").mkdir(parents=True)
            (runtime / "lib").mkdir()
            (runtime / "lib/modules").write_bytes(MODULE_INVENTORY)
            # Deliberately misleading metadata must never certify the image.
            (runtime / "release").write_text('MODULES="java.compiler jdk.compiler"\n')
        (self.linux / "bin/java").write_text("#!/usr/bin/env bash\n" + PROBE_SHELL)
        (self.linux / "bin/jimage").write_bytes(JIMAGE_SHELL)
        (self.linux / "bin/java").chmod(0o755)
        (self.linux / "bin/jimage").chmod(0o755)

    def test_windows_java_compiler_api_without_implementation_is_refused(self):
        (self.windows / "lib/modules").write_bytes(MODULE_INVENTORY.split(b"Module: jdk.compiler")[0])
        with self.assertRaisesRegex(ValueError, "jdk.compiler"):
            verify_compiler_runtimes(self.linux, self.windows)

    def test_compiler_names_in_unrelated_module_do_not_prove_compiler(self):
        (self.windows / "lib/modules").write_bytes(MODULE_INVENTORY.replace(b"Module: jdk.compiler", b"Module: arbitrary"))
        with self.assertRaisesRegex(ValueError, "jdk.compiler"):
            verify_compiler_runtimes(self.linux, self.windows)

    def test_linux_success_exit_without_compilation_is_refused(self):
        (self.linux / "bin/java").write_text("#!/bin/sh\necho WORLD_BUILDER_COMPILER_OK\n")
        with self.assertRaisesRegex(ValueError, "ToolProvider compilation"):
            verify_compiler_runtimes(self.linux, self.windows)

    def test_missing_image_or_inspection_tool_is_refused(self):
        (self.windows / "lib/modules").unlink()
        with self.assertRaisesRegex(ValueError, "Windows.*lib/modules"):
            verify_compiler_runtimes(self.linux, self.windows)
        (self.linux / "bin/jimage").unlink()
        with self.assertRaisesRegex(ValueError, "executable jimage"):
            verify_compiler_runtimes(self.linux, self.windows)

    def test_real_host_compiler_and_module_image(self):
        java = shutil.which("java")
        if not java:
            self.skipTest("Java compiler toolchain unavailable")
        runtime = Path(java).resolve().parents[1]
        if not (runtime / "bin/jimage").is_file():
            self.skipTest("Host jimage unavailable")
        # The image format is platform-independent; this proves the real parser.
        # Native Windows acceptance still requires the vendor Windows input.
        evidence = verify_compiler_runtimes(runtime, runtime)
        self.assertIn("ToolProvider compiled Java 17", evidence["linux"])
        self.assertIn("native execution pending", evidence["windows"])

    def test_optional_reviewed_vendor_inputs(self):
        root = os.environ.get("WORLD_BUILDER_COMPILER_INPUTS")
        if not root:
            self.skipTest("Reviewed dual-platform vendor inputs not configured")
        verify_compiler_runtimes(Path(root) / "linux", Path(root) / "windows")


if __name__ == "__main__":
    unittest.main()
