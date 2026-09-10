#!/usr/bin/env python3
"""Print metadata for exact public c0102e stock inputs omitted by active intake.

Reads committed public blobs only. Never reads working-tree files or private
databases, credentials, nonempty UID state or logs. Output is reviewed and compiled into
the Editor; a target cannot supply or regenerate admission authority at runtime.
"""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess

COMMIT = "c0102e60774ab9c9076aabae49f6f97fb6fc4b00"
TREE = "6db5536d795abf34f303bb03b20c43b8cfb9e3fe"
ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-git", required=True)
    args = parser.parse_args()

    def git(*arguments):
        return subprocess.check_output(["git", "-C", args.source_git, *arguments])

    if git("rev-parse", COMMIT + "^{tree}").decode().strip() != TREE:
        raise SystemExit("Exact public Preservation tree required")
    resources = ROOT / "tools/world-builder/resources/com/openrsc/worldbuilder"
    known = set()
    for name in ("source-intake", "source-build-dependencies"):
        known.update(r["path"] for r in json.loads(
            (resources / ("preservation-c0102e-" + name + ".json")).read_text())["records"])
    records = []
    for entry in git("ls-tree", "-rz", COMMIT, "--", "Client_Base", "PC_Client", "server").split(b"\0"):
        if not entry:
            continue
        header, raw_path = entry.split(b"\t", 1)
        mode, kind, oid = header.decode().split()
        path = raw_path.decode()
        # Private state has a distinct policy, never public stock authority.
        if (path in known or path == "server/connections.conf"
                or path.startswith(("server/inc/sqlite/", "server/logs/"))
                or path.endswith((".pem", ".db", ".log"))):
            continue
        # The public tree contains an EMPTY placeholder, never an owner UID.
        # Verify Git's empty-blob identity before reading; nonempty is forbidden.
        if path == "Client_Base/Cache/uid.dat" and oid != "e69de29bb2d1d6434b8b29ae775ad8c2e48c5391":
            raise SystemExit("UID metadata generation requires the empty public placeholder")
        if kind != "blob" or mode not in ("100644", "100755"):
            raise SystemExit("Unreviewed public stock entry: " + path)
        data = git("cat-file", "blob", oid)
        records.append(dict(path=path, mode=mode, size=len(data),
                            sha256=hashlib.sha256(data).hexdigest()))
    print(json.dumps(dict(sourceCommit=COMMIT, sourceTree=TREE, records=records),
                     indent=2, sort_keys=True))


if __name__ == "__main__":
    main()
