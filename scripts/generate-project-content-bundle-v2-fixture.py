#!/usr/bin/env python3
"""Generate the frozen project-content-bundle-v2 compatibility fixture."""

import argparse
import gzip
import hashlib
import importlib.util
import io
import json
import struct
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
    (role, path, media, definition)
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
            "customSpriteEntry": "0",
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
            "customSpriteSubspace": "GUI",
            "customSpriteEntry": "0",
            "pictureMask": 0x445566,
            "blueMask": -16776961,
        },
    ]


def osar_entry(*, width: int, height: int, pixels: list[int],
               offset_x: int = 0, offset_y: int = 0,
               bound_width: int | None = None,
               bound_height: int | None = None) -> bytes:
    """Encode one runtime-Unpacker-compatible TYPE.SPRITE entry."""
    if len(pixels) != width * height or not pixels:
        raise ValueError("OSAR fixture pixels must exactly fill one nonempty frame")
    palette = list(dict.fromkeys(pixels))
    if not 1 <= len(palette) <= 256:
        raise ValueError("OSAR fixture palette must contain 1..256 colors")
    bounded_width = width if bound_width is None else bound_width
    bounded_height = height if bound_height is None else bound_height
    output = io.BytesIO()
    output.write(bytes((0, 1, len(palette) - 1)))  # SPRITE, one frame, palette-1
    for color in palette:
        output.write((color & 0xFFFFFF).to_bytes(3, "big"))
    output.write(struct.pack(
        ">HHBhhHH", width, height, int(offset_x != 0 or offset_y != 0),
        offset_x, offset_y, bounded_width, bounded_height,
    ))
    output.write(bytes(palette.index(pixel) for pixel in pixels))
    return output.getvalue()


def deterministic_osar(subspaces: list[tuple[str, list[tuple[str, bytes]]]]) -> bytes:
    """Encode a complete deterministic GZIP OSAR sprite archive."""
    payload = io.BytesIO()
    payload.write(bytes((len(subspaces),)))
    for subspace, entries in subspaces:
        payload.write(subspace.encode("latin-1") + b"\0")
        payload.write(len(entries).to_bytes(2, "big"))
        for name, entry in entries:
            payload.write(name.encode("latin-1") + b"\0")
            payload.write(entry)
    output = io.BytesIO()
    with gzip.GzipFile(filename="", mode="wb", fileobj=output, mtime=0) as archive:
        archive.write(payload.getvalue())
    return output.getvalue()


def payloads() -> dict[str, bytes]:
    values = legacy.payloads()
    values["client/Cache/video/Authentic_Sprites.orsc"] = legacy.deterministic_zip(
        "sprites/417.dat", osar_entry(
            width=2, height=2,
            pixels=[0x102030, 0x405060, 0x708090, 0xA0B0C0],
        )
    )
    values["client/Cache/video/Custom_Sprites.osar"] = deterministic_osar([
        ("items", [("0", osar_entry(
            width=3, height=2,
            pixels=[0x112233, 0xD0A020, 0x112233, 0x3060C0, 0xD0A020, 0x3060C0],
            offset_x=1, offset_y=2, bound_width=5, bound_height=6,
        ))]),
    ])
    values["client/Cache/video/spritepacks/Menus.osar"] = deterministic_osar([
        ("GUI", [("0", osar_entry(
            width=2, height=3,
            pixels=[0x204060, 0x80A0C0, 0xE08020, 0x204060, 0xE08020, 0x80A0C0],
            offset_x=2, offset_y=1, bound_width=6, bound_height=5,
        ))]),
    ])
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
