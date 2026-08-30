#!/usr/bin/env python3
"""Focused tests for concise verification and read-only output inventory."""

from __future__ import annotations

import os
import shutil
import subprocess
import tempfile
import time
import unittest
from pathlib import Path


SOURCE_ROOT = Path(__file__).resolve().parents[2]


class MaintainabilityToolingTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory(prefix="maintainability-tooling-")
        self.root = Path(self.temp.name) / "rsc-world-editor"
        (self.root / "scripts").mkdir(parents=True)
        (self.root / "tests/myworld").mkdir(parents=True)
        for relative in (
            "release/world-builder",
            "release/world-builder-v2",
            "release/updater",
            "release/updater-v2",
        ):
            path = self.root / relative
            path.mkdir(parents=True)
            (path / "fixture.sh").write_text(
                "#!/usr/bin/env bash\nset -euo pipefail\n", encoding="utf-8"
            )
        for name in ("test.sh", "preview-generated-output-cleanup.sh"):
            shutil.copy2(SOURCE_ROOT / "scripts" / name, self.root / "scripts" / name)
        build = self.root / "scripts/build-tools.sh"
        build.write_text(
            "#!/usr/bin/env bash\n"
            "set -euo pipefail\n"
            "printf 'build\\n' >> \"$ROOT_DIR/build-count.txt\"\n",
            encoding="utf-8",
        )
        build.chmod(0o755)

    def tearDown(self) -> None:
        self.temp.cleanup()

    def run_script(
        self, name: str, *args: str, check: bool = True
    ) -> subprocess.CompletedProcess[str]:
        result = subprocess.run(
            ["bash", str(self.root / "scripts" / name), *args],
            cwd=self.root,
            env={**os.environ, "ROOT_DIR": str(self.root)},
            capture_output=True,
            text=True,
        )
        if check and result.returncode != 0:
            raise AssertionError(
                f"{name} failed ({result.returncode})\n"
                f"STDOUT:\n{result.stdout}\nSTDERR:\n{result.stderr}"
            )
        return result

    def write_test(self, name: str, methods: int = 1) -> None:
        body = [
            "#!/usr/bin/env python3",
            "import unittest",
            "",
            "class FixtureTest(unittest.TestCase):",
        ]
        for index in range(methods):
            body.extend(
                [
                    f"    def test_{index + 1}(self):",
                    "        self.assertTrue(True)",
                    "",
                ]
            )
        body.extend(["if __name__ == '__main__':", "    unittest.main()", ""])
        (self.root / "tests/myworld" / name).write_text(
            "\n".join(body), encoding="utf-8"
        )

    def write_direct_check(self, name: str) -> None:
        (self.root / "tests/myworld" / name).write_text(
            "#!/usr/bin/env python3\nprint('PASS: direct fixture check')\n",
            encoding="utf-8",
        )

    def test_focused_runner_selects_file_method_and_group_concisely(self) -> None:
        self.write_test("test-world-builder-fixture.py", methods=2)
        self.write_test("test-world-builder-ai-workspaces.py")
        self.write_test("test-world-builder-maintainability-tooling.py")

        exact = self.run_script(
            "test.sh",
            "--test",
            "test-world-builder-fixture.py::FixtureTest.test_1",
        )
        self.assertIn("tests=1", exact.stdout)
        self.assertIn("1 selection(s)", exact.stdout)
        self.assertNotIn("test_1 (", exact.stdout)

        selected_file = self.run_script(
            "test.sh", "--file", "test-world-builder-fixture.py"
        )
        self.assertIn("tests=2", selected_file.stdout)

        self.write_direct_check("test-world-builder-direct-check.py")
        direct = self.run_script(
            "test.sh", "--file", "test-world-builder-direct-check.py"
        )
        self.assertIn("tests=check", direct.stdout)

        group = self.run_script(
            "test.sh",
            "--group",
            "workflow",
            "--file",
            "test-world-builder-ai-workspaces.py",
        )
        self.assertIn("2 selection(s)", group.stdout)
        self.assertEqual(
            ["build", "build", "build", "build"],
            (self.root / "build-count.txt").read_text(encoding="utf-8").splitlines(),
        )

        listing = self.run_script("test.sh", "--list")
        self.assertIn("workflow discovery projects", listing.stdout)
        self.assertIn("test-world-builder-fixture.py", listing.stdout)

    def test_focused_runner_prints_complete_failure_and_rejects_bad_selection(self) -> None:
        self.write_test("test-world-builder-fixture.py")
        missing = self.run_script(
            "test.sh",
            "--test",
            "test-world-builder-fixture.py::FixtureTest.test_missing",
            check=False,
        )
        self.assertNotEqual(0, missing.returncode)
        self.assertIn("AttributeError", missing.stderr)

        unsafe = self.run_script(
            "test.sh", "--file", "../outside.py", check=False
        )
        self.assertEqual(2, unsafe.returncode)
        self.assertIn("must name tests/myworld", unsafe.stderr)

    def test_cleanup_preview_is_read_only_and_blocks_durable_state(self) -> None:
        candidates = self.root / "output/candidates/world-builder-v2"
        test_builds = self.root / "output/test-builds"
        releases = self.root / "output/releases/world-builder-v2"
        development = self.root / "output/development"
        for root in (candidates, test_builds, releases, development):
            root.mkdir(parents=True)

        now = time.time()
        for index in range(4):
            candidate = candidates / f"v0.0.0-alpha.{index}"
            candidate.mkdir()
            (candidate / "archive.zip").write_bytes(bytes([index]) * 32)
            os.utime(candidate, (now - index * 60, now - index * 60))
        protected = candidates / "v0.0.0-alpha.3/projects/example"
        protected.mkdir(parents=True)
        (protected / "project.txt").write_text("keep me\n", encoding="utf-8")
        for root, name in ((test_builds, "build-1"), (releases, "release-1")):
            entry = root / name
            entry.mkdir()
            (entry / "artifact.bin").write_bytes(b"fixture")
        (development / "sandbox").mkdir()

        before = sorted(
            (path.relative_to(self.root).as_posix(), path.stat().st_size)
            for path in self.root.rglob("*")
            if path.is_file()
        )
        preview = self.run_script("preview-generated-output-cleanup.sh", "--verbose")
        after = sorted(
            (path.relative_to(self.root).as_posix(), path.stat().st_size)
            for path in self.root.rglob("*")
            if path.is_file()
        )

        self.assertEqual(before, after)
        self.assertIn("Generated output cleanup preview (read-only)", preview.stdout)
        self.assertIn("BLOCKED-DURABLE:", preview.stdout)
        self.assertIn("REVIEW-DISPOSABLE", preview.stdout)
        self.assertIn("No files changed", preview.stdout)


if __name__ == "__main__":
    unittest.main()
