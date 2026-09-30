#!/usr/bin/env python3
"""Embed only reviewed map integration objects from the exact provider lock."""
import hashlib
import json
from pathlib import Path, PurePosixPath
import re
import subprocess
import sys


def main():
    editor, provider, classes = map(Path, sys.argv[1:])
    lock = (editor / "runtime-provider.lock").read_text()
    match = re.search(r"^RUNTIME_PROVIDER_COMMIT=([0-9a-f]{40})$", lock, re.M)
    if not match:
        raise ValueError("Invalid exact runtime provider lock")
    commit = match.group(1)
    descriptor = "server/conf/world-builder/target-map-integration-v1.json"
    def read(path, optional=False):
        parts = PurePosixPath(path).parts
        if not parts or path.startswith("/") or ".." in parts or "\\" in path:
            raise ValueError("Unsafe targeted runtime source path")
        result = subprocess.run(["git", "-C", str(provider), "show", f"{commit}:{path}"], capture_output=True)
        if result.returncode:
            if optional:
                return None
            raise ValueError(f"Missing locked runtime source: {path}")
        if len(result.stdout) > 4 * 1024 * 1024:
            raise ValueError("Targeted source exceeds size bound")
        return result.stdout
    raw = read(descriptor, optional=True)
    if raw is None:
        return  # Older locked providers do not support targeted upgrades.
    contract = json.loads(raw)
    if contract.get("manifestType") != "world-builder-target-map-integration" or contract.get("schemaVersion") != 1:
        raise ValueError("Unsupported targeted runtime contract")
    root = classes / "com/openrsc/worldbuilder/target-map-integration"
    root.mkdir(parents=True, exist_ok=True)
    (root / "target-map-integration-v1.json").write_bytes(raw)
    copied = {}
    for adapter in contract["adapters"]:
        for source in adapter["sources"]:
            path = source["payloadRelativePath"]
            if not path.startswith("server/conf/world-builder/target-map-source/"):
                raise ValueError("Source outside targeted integration payload")
            content = read(path)
            digest = hashlib.sha256(content).hexdigest()
            if digest != source["sha256"] or path in copied and copied[path] != digest:
                raise ValueError("Targeted map payload hash differs")
            copied[path] = digest
            destination = root / path
            destination.parent.mkdir(parents=True, exist_ok=True)
            destination.write_bytes(content)


if __name__ == "__main__":
    main()
