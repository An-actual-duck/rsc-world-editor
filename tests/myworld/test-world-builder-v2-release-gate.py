#!/usr/bin/env python3
"""Verify version-, runtime-, record-, and source-bound release gates."""

from __future__ import annotations

import json
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path


SOURCE_ROOT = Path(__file__).resolve().parents[2]
VALIDATOR = SOURCE_ROOT / "scripts/validate-world-builder-v2-release-gate.sh"
VERSION = "v9.8.7-alpha.1"
VALIDATED_EDITOR = "1" * 40
RUNTIME = "2" * 40
RECORD = "docs/releases/world-builder-v2-v9.8.7-alpha.1-validation.md"


class ReleaseGateFixture:
    def __init__(self) -> None:
        self.temp = tempfile.TemporaryDirectory(prefix="world-builder-v2-gate-")
        self.root = Path(self.temp.name) / "editor"
        self.root.mkdir()
        (self.root / "scripts").mkdir()
        shutil.copy2(VALIDATOR, self.root / "scripts" / VALIDATOR.name)
        self.write(
            "runtime-provider.lock",
            "RUNTIME_PROVIDER_REPOSITORY=https://example.invalid/runtime.git\n"
            "RUNTIME_PROVIDER_REF=refs/heads/main\n"
            f"RUNTIME_PROVIDER_COMMIT={RUNTIME}\n",
        )
        self.write(
            RECORD,
            "# Accepted fixture — ACCEPTED — RELEASE READY\n\n"
            f"Version: {VERSION}\n"
            f"Validated Editor: {VALIDATED_EDITOR}\n"
            f"Runtime: {RUNTIME}\n",
        )
        self.gate = {
            "schemaVersion": 1,
            "manifestType": "world-builder-v2-release-gate",
            "releaseVersion": VERSION,
            "validatedEditorCommit": VALIDATED_EDITOR,
            "runtimeProviderCommit": RUNTIME,
            "validationRecord": RECORD,
        }
        self.write_gate()
        self.git("init", "--initial-branch=main")
        self.git("config", "user.name", "Release Gate Test")
        self.git("config", "user.email", "release-gate@example.invalid")
        self.git("add", ".")
        self.git("commit", "-m", "Accept exact fixture gate")

    def close(self) -> None:
        self.temp.cleanup()

    def write(self, relative: str, text: str) -> None:
        path = self.root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding="utf-8")

    def write_gate(self) -> None:
        self.write(
            "release/world-builder-v2/RELEASE-READY",
            json.dumps(self.gate, sort_keys=True) + "\n",
        )

    def git(self, *args: str) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            ["git", *args],
            cwd=self.root,
            check=True,
            capture_output=True,
            text=True,
        )

    def run(self, version: str = VERSION) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            ["bash", str(self.root / "scripts" / VALIDATOR.name), version],
            cwd=self.root,
            capture_output=True,
            text=True,
        )


class WorldBuilderV2ReleaseGateTest(unittest.TestCase):
    def setUp(self) -> None:
        self.fixture = ReleaseGateFixture()

    def tearDown(self) -> None:
        self.fixture.close()

    def test_exact_gate_passes(self) -> None:
        result = self.fixture.run()
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("production source", result.stdout)

    def test_wrong_version_and_runtime_are_rejected(self) -> None:
        wrong_version = self.fixture.run("v9.8.8-alpha.1")
        self.assertNotEqual(0, wrong_version.returncode)
        self.assertIn("not requested", wrong_version.stderr)

        self.fixture.gate["runtimeProviderCommit"] = "3" * 40
        self.fixture.write_gate()
        wrong_runtime = self.fixture.run()
        self.assertNotEqual(0, wrong_runtime.returncode)
        self.assertIn("does not match runtime-provider.lock", wrong_runtime.stderr)

    def test_unknown_schema_key_and_unsafe_record_are_rejected(self) -> None:
        self.fixture.gate["extra"] = True
        self.fixture.write_gate()
        unknown = self.fixture.run()
        self.assertNotEqual(0, unknown.returncode)
        self.assertIn("exact version-1 schema", unknown.stderr)

        self.fixture.gate.pop("extra")
        self.fixture.gate["validationRecord"] = "../outside.md"
        self.fixture.write_gate()
        unsafe = self.fixture.run()
        self.assertNotEqual(0, unsafe.returncode)
        self.assertIn("outside docs/releases", unsafe.stderr)

    def test_later_commit_makes_gate_stale(self) -> None:
        self.fixture.write("README.md", "later development change\n")
        self.fixture.git("add", "README.md")
        self.fixture.git("commit", "-m", "Continue development")
        stale = self.fixture.run()
        self.assertNotEqual(0, stale.returncode)
        self.assertIn("Release gate is stale", stale.stderr)


if __name__ == "__main__":
    unittest.main()
