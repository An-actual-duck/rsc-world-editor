#!/usr/bin/env python3
"""Generate the frozen project-content-bundle-v2 compatibility fixture."""

import argparse
import hashlib
import importlib.util
import json
import tempfile
from pathlib import Path


ROOT = Path(__file__).resolve().parent
LEGACY_PATH = ROOT / "generate-project-content-bundle-v1-fixture.py"
SPEC = importlib.util.spec_from_file_location("content_bundle_v1_generator", LEGACY_PATH)
assert SPEC is not None and SPEC.loader is not None
legacy = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(legacy)

ZERO_HASH = "0" * 64
CAPABILITY_ID = "project-local-custom-content-v2"
VISUAL_PATH = "server/conf/world-builder/item-visuals-v1.json"
SPECS = tuple(sorted(tuple(
    (role, path, "application/zip" if role in {
        "asset.sprite.custom", "asset.spritepack"
    } else media, definition)
    for role, path, media, definition in legacy.SPECS
) + (
    ("metadata.item-visuals", VISUAL_PATH, "application/json", True),
), key=lambda value: value[1]))


def item_visuals() -> list[dict]:
    return [
        {
            "itemId": 9000,
            "authenticSpriteId": None,
            "customSpriteAssetRole": "asset.sprite.custom",
            "customSpriteSubspace": "items",
            "customSpriteEntry": "9000.dat",
            "pictureMask": 0x336699,
            "blueMask": 0x112233,
        },
        {
            "itemId": 9001,
            "authenticSpriteId": 417,
            "customSpriteAssetRole": None,
            "customSpriteSubspace": None,
            "customSpriteEntry": None,
            "pictureMask": -1,
            "blueMask": 0,
        },
        {
            "itemId": 9002,
            "authenticSpriteId": None,
            "customSpriteAssetRole": "asset.spritepack",
            "customSpriteSubspace": "inventory",
            "customSpriteEntry": "9002.dat",
            "pictureMask": 0x445566,
            "blueMask": -16776961,
        },
    ]


def payloads() -> dict[str, bytes]:
    values = legacy.payloads()
    values["client/Cache/video/Authentic_Sprites.orsc"] = legacy.deterministic_zip(
        "sprites/authentic-417.dat", b"authentic sprite 417 fixture\n"
    )
    values["client/Cache/video/Custom_Sprites.osar"] = legacy.deterministic_zip(
        "items/9000.dat", b"custom sprite for arbitrary item 9000\n"
    )
    values["client/Cache/video/spritepacks/Menus.osar"] = legacy.deterministic_zip(
        "inventory/9002.dat", b"custom spritepack item 9002\n"
    )
    values[VISUAL_PATH] = legacy.pretty({
        "schemaVersion": 1,
        "manifestType": "world-builder-item-visual-evidence",
        "itemVisuals": item_visuals(),
    })
    return values


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


def catalog() -> dict:
    value = legacy.catalog()
    value["catalogId"] = "target-adopted-content-v2"
    value["catalogSha256"] = ZERO_HASH
    value["catalogSha256"] = legacy.self_hash(value, "catalogSha256")
    return value


def build_manifest(content: dict[str, bytes]) -> dict:
    records = []
    for role, runtime_path, media_type, _ in SPECS:
        payload = content[runtime_path]
        records.append({
            "role": role,
            "bundleRelativePath": "files/" + runtime_path,
            "runtimeRelativePath": runtime_path,
            "mediaType": media_type,
            "size": len(payload),
            "sha256": legacy.sha256(payload),
        })
    adopted_catalog = catalog()
    visuals = item_visuals()
    visual_hash = legacy.sha256(
        b"world-builder-project-content-item-visuals-v1\n" + legacy.canonical(visuals)
    )
    manifest = {
        "schemaVersion": 2,
        "manifestType": "world-builder-project-content-bundle",
        "capabilityId": CAPABILITY_ID,
        "sourceKind": "target-adopted",
        "definitionCatalog": adopted_catalog,
        "familyBindings": legacy.family_bindings(),
        "itemVisuals": visuals,
        "files": records,
        "definitionFingerprintSha256": record_fingerprint(
            b"world-builder-project-content-definitions-v2\n", records, True,
            adopted_catalog["catalogSha256"]
        ),
        "assetFingerprintSha256": record_fingerprint(
            b"world-builder-project-content-assets-v2\n", records, False
        ),
        "itemVisualFingerprintSha256": visual_hash,
        "bundleFingerprintSha256": ZERO_HASH,
    }
    manifest["bundleFingerprintSha256"] = legacy.sha256(
        b"world-builder-project-content-bundle-v2\n" + legacy.canonical(manifest)
    )
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
    (output / "manifest.json").write_bytes(legacy.pretty(build_manifest(content)))


def check(expected: Path) -> None:
    with tempfile.TemporaryDirectory(prefix="project-content-bundle-v2-") as temp:
        generated = Path(temp) / "fixture"
        generate(generated)
        if legacy.file_inventory(generated) != legacy.file_inventory(expected):
            raise SystemExit("Canonical v2 fixture differs; regenerate and review.")
    print("PASS: canonical project-content-bundle-v2 fixture")


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
