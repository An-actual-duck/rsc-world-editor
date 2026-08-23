#!/usr/bin/env python3
"""Generate the canonical compact project-content-bundle-v1 compatibility fixture."""

import argparse
import gzip
import hashlib
import io
import json
import tempfile
import zipfile
from pathlib import Path


ZERO_HASH = "0" * 64
CAPABILITY_ID = "project-local-custom-content-v1"
FIXTURE_IDS = {
    "floor": 31,
    "wall": 219,
    "scenery": 59,
    "npc": 846,
    "groundItem": 9000,
}

SPECS = (
    ("asset.sprite.authentic", "client/Cache/video/Authentic_Sprites.orsc",
     "application/vnd.openrsc.archive", False),
    ("asset.sprite.custom", "client/Cache/video/Custom_Sprites.osar",
     "application/gzip", False),
    ("asset.library", "client/Cache/video/library.orsc",
     "application/vnd.openrsc.archive", False),
    ("asset.model", "client/Cache/video/models.orsc",
     "application/vnd.openrsc.archive", False),
    ("asset.spritepack", "client/Cache/video/spritepacks/Menus.osar",
     "application/gzip", False),
    ("definition.boundary", "server/conf/server/defs/DoorDef.xml",
     "application/xml", True),
    ("definition.scenery", "server/conf/server/defs/GameObjectDef.xml",
     "application/xml", True),
    ("definition.item.base", "server/conf/server/defs/ItemDefs.json",
     "application/json", True),
    ("definition.item.custom", "server/conf/server/defs/ItemDefsCustom.json",
     "application/json", True),
    ("definition.item.patch", "server/conf/server/defs/ItemDefsPatch18.json",
     "application/json", True),
    ("definition.item.world", "server/conf/server/defs/ItemDefsMyWorld.json",
     "application/json", True),
    ("definition.npc.base", "server/conf/server/defs/NpcDefs.json",
     "application/json", True),
    ("definition.npc.custom", "server/conf/server/defs/NpcDefsCustom.json",
     "application/json", True),
    ("definition.npc.patch", "server/conf/server/defs/NpcDefsPatch18.json",
     "application/json", True),
    ("definition.npc.world", "server/conf/server/defs/NpcDefsMyWorld.json",
     "application/json", True),
    ("definition.tile", "server/conf/server/defs/TileDef.xml",
     "application/xml", True),
)


def canonical(value: object) -> bytes:
    return json.dumps(
        value, sort_keys=True, separators=(",", ":"), ensure_ascii=False
    ).encode("utf-8")


def sha256(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def pretty(value: object) -> bytes:
    return (json.dumps(
        value, sort_keys=True, indent=2, ensure_ascii=False
    ) + "\n").encode("utf-8")


def deterministic_zip(name: str, payload: bytes) -> bytes:
    output = io.BytesIO()
    with zipfile.ZipFile(output, "w") as archive:
        entry = zipfile.ZipInfo(name, (1980, 1, 1, 0, 0, 0))
        entry.create_system = 3
        entry.external_attr = 0o100644 << 16
        entry.compress_type = zipfile.ZIP_STORED
        archive.writestr(entry, payload)
    return output.getvalue()


def deterministic_gzip(payload: bytes) -> bytes:
    output = io.BytesIO()
    with gzip.GzipFile(filename="", mode="wb", fileobj=output, mtime=0) as archive:
        archive.write(payload)
    return output.getvalue()


def xml_array(root: str, element: str, count: int, body: str) -> bytes:
    values = "".join(f"<{element}>{body.format(index=index)}</{element}>"
                     for index in range(count))
    return f"<{root}>{values}</{root}>\n".encode("utf-8")


def payloads() -> dict[str, bytes]:
    return {
        "client/Cache/video/Authentic_Sprites.orsc": deterministic_zip(
            "sprites/fixture.dat", b"authentic sprite fixture\n"
        ),
        "client/Cache/video/Custom_Sprites.osar": deterministic_gzip(
            b"custom sprites: npc=846 item=9000 floor=31 wall=219 scenery=59\n"
        ),
        "client/Cache/video/library.orsc": b"fixture library archive bytes v1\n",
        "client/Cache/video/models.orsc": b"fixture model archive: scenery=59\n",
        "client/Cache/video/spritepacks/Menus.osar": deterministic_gzip(
            b"fixture inventory sprites: item=9000\n"
        ),
        "server/conf/server/defs/DoorDef.xml": xml_array(
            "DoorDef-array", "DoorDef", 220,
            "<name>fixture-wall-{index}</name>"
        ),
        "server/conf/server/defs/GameObjectDef.xml": xml_array(
            "GameObjectDef-array", "GameObjectDef", 60,
            "<name>fixture-scenery-{index}</name><width>1</width><height>1</height>"
        ),
        "server/conf/server/defs/ItemDefs.json": pretty(
            {"item": [{"id": 0, "name": "fixture-base-item"}]}
        ),
        "server/conf/server/defs/ItemDefsCustom.json": pretty(
            {"items": [{"id": 9000, "name": "fixture-custom-item"}]}
        ),
        "server/conf/server/defs/ItemDefsPatch18.json": pretty(
            {"items": [{"id": 9002, "name": "fixture-patch-item"}]}
        ),
        "server/conf/server/defs/ItemDefsMyWorld.json": pretty(
            {"items": [{"id": 9001, "name": "fixture-world-item"}]}
        ),
        "server/conf/server/defs/NpcDefs.json": pretty(
            {"npcs": [{"id": 0, "name": "fixture-base-npc"}]}
        ),
        "server/conf/server/defs/NpcDefsCustom.json": pretty(
            {"npcs": [{"id": 12, "name": "fixture-appended-npc"}]}
        ),
        "server/conf/server/defs/NpcDefsPatch18.json": pretty(
            {"npcs": [{"id": 100, "name": "fixture-patch-npc"}]}
        ),
        "server/conf/server/defs/NpcDefsMyWorld.json": pretty(
            {"npcs": [{"id": 846, "name": "fixture-world-npc"}]}
        ),
        "server/conf/server/defs/TileDef.xml": xml_array(
            "TileDef-array", "TileDef", 32,
            "<colour>{index}</colour>"
        ),
    }


def self_hash(value: dict, field: str) -> str:
    copy = dict(value)
    copy[field] = ZERO_HASH
    return sha256(canonical(copy))


def catalog() -> dict:
    value = {
        "schemaVersion": 1,
        "manifestType": "world-builder-definition-catalog",
        "catalogId": "target-adopted-content-v1",
        "tiles": list(range(32)),
        "boundaries": list(range(220)),
        "scenery": list(range(60)),
        "npcs": [0, 1, 100, 846],
        "groundItems": [0, 9000, 9001, 9002],
        "catalogSha256": ZERO_HASH,
    }
    value["catalogSha256"] = self_hash(value, "catalogSha256")
    return value


def family_bindings() -> list[dict]:
    return [
        {"family": "floor", "definitionRoles": ["definition.tile"],
         "assetRoles": ["asset.sprite.custom"]},
        {"family": "ground-item", "definitionRoles": [
            "definition.item.base", "definition.item.custom",
            "definition.item.patch", "definition.item.world"
        ], "assetRoles": [
            "asset.library", "asset.sprite.authentic",
            "asset.sprite.custom", "asset.spritepack"
        ]},
        {"family": "npc", "definitionRoles": [
            "definition.npc.base", "definition.npc.custom",
            "definition.npc.patch", "definition.npc.world"
        ], "assetRoles": [
            "asset.library", "asset.sprite.authentic",
            "asset.sprite.custom", "asset.spritepack"
        ]},
        {"family": "scenery", "definitionRoles": ["definition.scenery"],
         "assetRoles": ["asset.library", "asset.model", "asset.sprite.custom"]},
        {"family": "wall", "definitionRoles": ["definition.boundary"],
         "assetRoles": ["asset.sprite.custom"]},
    ]


def record_fingerprint(domain: bytes, records: list[dict], definition: bool,
                       catalog_hash: str = "") -> str:
    digest = hashlib.sha256(domain)
    definitions = {role for role, _, _, is_definition in SPECS if is_definition}
    for record in records:
        if (record["role"] in definitions) != definition:
            continue
        digest.update((
            f'{record["role"]}\0{record["runtimeRelativePath"]}\0'
            f'{record["size"]}\0{record["sha256"]}\n'
        ).encode("utf-8"))
    if catalog_hash:
        digest.update(catalog_hash.encode("ascii"))
    return digest.hexdigest()


def build_manifest(content: dict[str, bytes]) -> dict:
    records = []
    for role, runtime_path, media_type, _ in sorted(SPECS, key=lambda value: value[1]):
        payload = content[runtime_path]
        records.append({
            "role": role,
            "bundleRelativePath": "files/" + runtime_path,
            "runtimeRelativePath": runtime_path,
            "mediaType": media_type,
            "size": len(payload),
            "sha256": sha256(payload),
        })
    adopted_catalog = catalog()
    manifest = {
        "schemaVersion": 1,
        "manifestType": "world-builder-project-content-bundle",
        "capabilityId": CAPABILITY_ID,
        "sourceKind": "target-adopted",
        "definitionCatalog": adopted_catalog,
        "familyBindings": family_bindings(),
        "files": records,
        "definitionFingerprintSha256": record_fingerprint(
            b"world-builder-project-content-definitions-v1\n", records, True,
            adopted_catalog["catalogSha256"]
        ),
        "assetFingerprintSha256": record_fingerprint(
            b"world-builder-project-content-assets-v1\n", records, False
        ),
        "bundleFingerprintSha256": ZERO_HASH,
    }
    domain = b"world-builder-project-content-bundle-v1\n"
    manifest["bundleFingerprintSha256"] = sha256(domain + canonical(manifest))
    return manifest


def generate(output: Path) -> None:
    if output.exists() and any(output.iterdir()):
        raise SystemExit(f"Refusing nonempty output directory: {output}")
    output.mkdir(parents=True, exist_ok=True)
    content = payloads()
    for relative, payload in content.items():
        path = output / "files" / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(payload)
    manifest = build_manifest(content)
    (output / "manifest.json").write_bytes(pretty(manifest))


def file_inventory(root: Path) -> dict[str, bytes]:
    return {
        path.relative_to(root).as_posix(): path.read_bytes()
        for path in sorted(root.rglob("*")) if path.is_file()
    }


def check(expected: Path) -> None:
    with tempfile.TemporaryDirectory(prefix="project-content-bundle-v1-") as temp:
        generated = Path(temp) / "fixture"
        generate(generated)
        if file_inventory(generated) != file_inventory(expected):
            raise SystemExit(
                "Canonical fixture differs; regenerate into an empty directory and review."
            )
    print("PASS: canonical project-content-bundle-v1 fixture")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("output", nargs="?", type=Path)
    parser.add_argument("--check", type=Path)
    args = parser.parse_args()
    if (args.output is None) == (args.check is None):
        parser.error("choose exactly one output directory or --check DIRECTORY")
    if args.check is not None:
        check(args.check.resolve())
    else:
        generate(args.output.resolve())


if __name__ == "__main__":
    main()
