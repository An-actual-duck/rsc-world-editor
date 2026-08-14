#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
VERSION="${1:-}"
MARKER="$ROOT_DIR/release/world-builder-v2/RELEASE-READY"

[[ "$VERSION" =~ ^v(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(-alpha\.(0|[1-9][0-9]*))?$ ]] || {
	printf 'FAIL: validate-world-builder-v2-release-gate.sh requires one semantic release version.\n' >&2
	exit 2
}
[[ -f "$MARKER" && ! -L "$MARKER" ]] || {
	printf 'FAIL: World Builder 2 release gate is closed or unsafe.\n' >&2
	exit 1
}

python3 - "$ROOT_DIR" "$VERSION" "$MARKER" <<'PY'
import json
import pathlib
import re
import subprocess
import sys

root = pathlib.Path(sys.argv[1]).resolve()
version = sys.argv[2]
marker = pathlib.Path(sys.argv[3])
expected_keys = {
    "schemaVersion",
    "manifestType",
    "releaseVersion",
    "validatedEditorCommit",
    "runtimeProviderCommit",
    "validationRecord",
}

try:
    gate = json.loads(marker.read_text(encoding="utf-8"))
except (OSError, UnicodeError, json.JSONDecodeError) as error:
    raise SystemExit(f"FAIL: Unable to read strict release gate: {error}")
if not isinstance(gate, dict) or set(gate) != expected_keys:
    raise SystemExit("FAIL: Release gate does not have the exact version-1 schema")
if gate["schemaVersion"] != 1 or gate["manifestType"] != "world-builder-v2-release-gate":
    raise SystemExit("FAIL: Unsupported World Builder 2 release gate identity")
if gate["releaseVersion"] != version:
    raise SystemExit(
        f"FAIL: Release gate accepts {gate['releaseVersion']!r}, not requested {version!r}"
    )

commit_pattern = re.compile(r"[0-9a-f]{40}")
for key in ("validatedEditorCommit", "runtimeProviderCommit"):
    if not isinstance(gate[key], str) or not commit_pattern.fullmatch(gate[key]):
        raise SystemExit(f"FAIL: Release gate {key} is not an exact commit")

lock = root / "runtime-provider.lock"
locked_commit = None
for line in lock.read_text(encoding="utf-8").splitlines():
    if line.startswith("RUNTIME_PROVIDER_COMMIT="):
        locked_commit = line.split("=", 1)[1]
if gate["runtimeProviderCommit"] != locked_commit:
    raise SystemExit(
        "FAIL: Release gate runtime commit does not match runtime-provider.lock"
    )

record_text = gate["validationRecord"]
if not isinstance(record_text, str):
    raise SystemExit("FAIL: Release gate validationRecord must be a path")
record_relative = pathlib.PurePosixPath(record_text)
if (
    record_relative.is_absolute()
    or not record_relative.parts
    or record_relative.parts[:2] != ("docs", "releases")
    or any(part in ("", ".", "..") for part in record_relative.parts)
):
    raise SystemExit("FAIL: Release gate validationRecord is outside docs/releases")
record = root.joinpath(*record_relative.parts)
if not record.is_file() or record.is_symlink() or root not in record.resolve().parents:
    raise SystemExit("FAIL: Release gate validation record is missing or unsafe")
validation = record.read_text(encoding="utf-8")
for required in (
    "ACCEPTED — RELEASE READY",
    version,
    gate["validatedEditorCommit"],
    gate["runtimeProviderCommit"],
):
    if required not in validation:
        raise SystemExit(
            f"FAIL: Release gate validation record does not bind {required!r}"
        )

relative_marker = marker.relative_to(root).as_posix()
head = subprocess.run(
    ["git", "-C", str(root), "rev-parse", "HEAD^{commit}"],
    check=True,
    capture_output=True,
    text=True,
).stdout.strip()
gate_commit = subprocess.run(
    ["git", "-C", str(root), "log", "-1", "--format=%H", "--", relative_marker],
    check=True,
    capture_output=True,
    text=True,
).stdout.strip()
if gate_commit != head:
    raise SystemExit(
        "FAIL: Release gate is stale; it must be added or refreshed in the exact production source commit"
    )

print(
    f"PASS: World Builder 2 release gate binds {version}, runtime "
    f"{locked_commit}, and production source {head}"
)
PY
