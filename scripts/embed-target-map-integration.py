#!/usr/bin/env python3
"""Embed reviewed map integration and inert authoring lookups from the exact lock."""
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
    verified = subprocess.run(["git", "-C", str(provider), "cat-file", "-t", commit], capture_output=True)
    if verified.returncode or verified.stdout.strip() != b"commit":
        raise ValueError("Locked runtime provider commit is unavailable")
    descriptor = "server/conf/world-builder/target-map-integration-v1.json"
    def read(path, optional=False):
        parts = PurePosixPath(path).parts
        if not parts or path.startswith("/") or ".." in parts or "\\" in path:
            raise ValueError("Unsafe targeted runtime source path")
        result = subprocess.run(["git", "-C", str(provider), "show", f"{commit}:{path}"], capture_output=True)
        if result.returncode:
            if optional:
                exists = subprocess.run(["git", "-C", str(provider), "ls-tree", "-z", commit, "--", path], capture_output=True)
                if exists.returncode == 0 and not exists.stdout:
                    return None
            raise ValueError(f"Missing locked runtime source: {path}")
        if len(result.stdout) > 4 * 1024 * 1024:
            raise ValueError("Targeted source exceeds size bound")
        return result.stdout
    # Presentation lookup only: this does not select or install a game composition.
    baseline = read("current-platform/runtime/current-base-v1/public-definitions/item-visuals.json", optional=True)
    if baseline is not None:
        value = json.loads(baseline)
        if value.get("manifestType") != "current-base-public-item-visuals" or value.get("schemaVersion") != 1:
            raise ValueError("Unsupported immutable authoring item lookup")
        destination = classes / "com/openrsc/worldbuilder/authoring-lookups/item-visuals.json"
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_bytes(baseline)
    animations = read("current-platform/runtime/current-base-v1/public-definitions/animation-visuals.json", optional=True)
    if animations is not None:
        value = json.loads(animations)
        if value.get("manifestType") != "current-base-public-animation-visuals" or value.get("schemaVersion") != 1:
            raise ValueError("Unsupported immutable authoring animation lookup")
        destination = classes / "com/openrsc/worldbuilder/authoring-lookups/animation-visuals.json"
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_bytes(animations)
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
            scope = source["scope"]
            relative = source["targetRelativePath"]
            if scope not in ("server", "client") or path != f"server/conf/world-builder/target-map-source/{scope}/{relative}":
                raise ValueError("Source payload path does not match its reviewed source scope")
            content = read(("server/" if scope == "server" else "Client_Base/") + relative)
            digest = hashlib.sha256(content).hexdigest()
            if digest != source["sha256"] or path in copied and copied[path] != digest:
                raise ValueError("Targeted map payload hash differs")
            copied[path] = digest
            destination = root / path
            destination.parent.mkdir(parents=True, exist_ok=True)
            destination.write_bytes(content)


if __name__ == "__main__":
    main()
