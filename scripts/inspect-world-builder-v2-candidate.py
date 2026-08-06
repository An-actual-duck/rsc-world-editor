#!/usr/bin/env python3
"""Independently validate final World Builder 2 candidate archives.

The production packager validates its staging trees before it creates the
archives.  This command is the separate, post-build boundary used by the final
candidate review: it reads artifacts stored outside both source trees, binds
them to clean published World Editor and exact locked runtime commits, verifies
the outer checksums and every manifested archive byte, and repeats the
content-neutral package checks without extracting untrusted paths.
"""

from __future__ import annotations

import argparse
import hashlib
import io
import json
import os
import re
import sqlite3
import stat
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path, PurePosixPath
from typing import Any, Iterable


PACKAGE_ROOT = "World Builder 2"
PRODUCT_ID = "rsc-world-editor-v2"
WORLD_SOURCE_IDENTITY = "target-adaptive-v1"
RELEASE_MARKER_ENTRY = "spoiled-milk-release-build.marker"
MANIFEST_NAME = "PACKAGE-MANIFEST.sha256"
HASH_PATTERN = re.compile(r"[0-9a-f]{64}")
VERSION_PATTERN = re.compile(
    r"v(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)"
    r"(?:-alpha\.(?:0|[1-9][0-9]*))?"
)
PACKED_TERRAIN_ENTRY = re.compile(r"(?:^|/)h-?[0-9]+x-?[0-9]+y-?[0-9]+$")
MAX_CHECKSUM_BYTES = 1 * 1024 * 1024
MAX_ARCHIVE_BYTES = 1024 * 1024 * 1024
MAX_ARCHIVE_ENTRIES = 100_000
MAX_ARCHIVE_EXPANDED_BYTES = 1024 * 1024 * 1024
MAX_ARCHIVE_ENTRY_BYTES = 256 * 1024 * 1024
MAX_NESTED_ARCHIVE_ENTRIES = 100_000
MAX_NESTED_EXPANDED_BYTES = 512 * 1024 * 1024
MAX_NESTED_ENTRY_BYTES = 256 * 1024 * 1024
MAX_STRUCTURED_DOCUMENT_BYTES = 32 * 1024 * 1024
MAX_RUNTIME_ENTRIES = 100_000
MAX_RUNTIME_EXPANDED_BYTES = 1024 * 1024 * 1024
MAX_RUNTIME_ENTRY_BYTES = 256 * 1024 * 1024
MAX_RUNTIME_DEPTH = 128
MAX_FORBIDDEN_CORE_FILES = 100_000
MAX_FORBIDDEN_CORE_BYTES = 1024 * 1024 * 1024
RELEVANT_RUNTIME_MODE_MASK = 0o777

EXPECTED_RUNTIME_CAPABILITY = {
    "schemaVersion": 1,
    "manifestType": "adaptive-world-builder-runtime-capability",
    "capabilityId": "adaptive-world-builder-runtime-capability-v1",
    "profileId": "adaptive-world-builder",
    "serverBuildId": "core-framework-adaptive-builder-server-v1",
    "clientBuildId": "core-framework-adaptive-builder-client-v1",
    "loaderId": "generic-signed-layered-loader-v1",
    "authoringId": "generic-signed-layered-authoring-v1",
    "protocolId": "world-builder-native-layered-protocol-v1",
    "packageSchemaId": "layered-world-package-v1",
    "coordinateModel": "signed-layered-v1",
}

TOP_LEVEL_FILES = {
    "ASSET-SOURCES.txt",
    "CORE-SOURCE-COMMIT.txt",
    "EDITOR-ICON-CREDITS.txt",
    "Import Map Changes.cmd",
    "Import Map Changes.sh",
    "LICENSE",
    MANIFEST_NAME,
    "PLAYER-ASSET-SOURCES.txt",
    "README.txt",
    "Recover Map Transaction.cmd",
    "Recover Map Transaction.sh",
    "RELEASE-IDENTITY.json",
    "RUNTIME-ASSET-ALLOWLIST.txt",
    "SOURCE-COMMIT.txt",
    "Start World Builder.cmd",
    "Start World Builder.sh",
    "Undo Last Map Import.cmd",
    "Undo Last Map Import.sh",
    "Update World Builder.cmd",
    "Update World Builder.ps1",
    "Update World Builder.sh",
    "VERSION.txt",
}
LINUX_EXECUTABLE_LAUNCHERS = {
    relative for relative in TOP_LEVEL_FILES if relative.endswith(".sh")
}
FIXED_BUILDER_RUNTIME_FILES = {
    "builder-runtime/Client_Base/Open_RSC_Client.jar",
    "builder-runtime/server/core.jar",
    "builder-runtime/server/plugins.jar",
    "builder-runtime/server/world-builder.conf",
    "builder-runtime/launcher/world-builder-tools.jar",
}
REQUIRED_BUILDER_RUNTIME_FILES = FIXED_BUILDER_RUNTIME_FILES | {
    "builder-runtime/server/inc/sqlite/world_builder_seed.db",
    "builder-runtime/server/conf/world-builder/adaptive-runtime-capability-v1.json",
}
REQUIRED_CLIENT_ENTRIES = {
    RELEASE_MARKER_ENTRY,
    "orsc/AdaptiveWorldBuilderClientSession.class",
    "orsc/WorldBuilderClientProfile.class",
    "linux/x64/org/lwjgl/liblwjgl.so",
    "linux/x64/org/lwjgl/glfw/libglfw.so",
    "linux/x64/org/lwjgl/opengl/liblwjgl_opengl.so",
    "windows/x64/org/lwjgl/lwjgl.dll",
    "windows/x64/org/lwjgl/glfw/glfw.dll",
    "windows/x64/org/lwjgl/opengl/lwjgl_opengl.dll",
}
REQUIRED_SERVER_ENTRIES = {
    "com/openrsc/server/content/worldedit/AdaptiveWorldBuilderPackagePublisher.class",
    "com/openrsc/server/content/worldedit/AdaptiveWorldBuilderRuntimeIdentity.class",
    "com/openrsc/server/content/worldedit/AdaptiveWorldBuilderRuntimeSession.class",
    "com/openrsc/server/content/worldedit/WorldEditStorageContext.class",
    "com/openrsc/server/content/worldedit/WorldBuilderRuntimeControl.class",
}
TOOL_RUNTIME_ALLOWLIST_ENTRY = (
    "com/openrsc/worldbuilder/runtime-asset-allowlist-v1.txt"
)
REQUIRED_TOOL_ENTRIES = {
    "com/openrsc/worldbuilder/WorldBuilderAdaptiveExporter.class",
    "com/openrsc/worldbuilder/WorldBuilderAdaptiveImporter.class",
    "com/openrsc/worldbuilder/WorldBuilderAdaptiveProjectLifecycle.class",
    "com/openrsc/worldbuilder/WorldBuilderAdaptiveRuntimePreparer.class",
    "com/openrsc/worldbuilder/WorldBuilderAdaptiveRecovery.class",
    "com/openrsc/worldbuilder/WorldBuilderAdaptiveUndo.class",
    "com/openrsc/worldbuilder/WorldBuilderCli.class",
    "com/openrsc/worldbuilder/WorldBuilderLayeredPackage.class",
    "com/openrsc/worldbuilder/WorldBuilderProcessSupervisor.class",
    TOOL_RUNTIME_ALLOWLIST_ENTRY,
}
WINDOWS_RESERVED = {
    "CON",
    "PRN",
    "AUX",
    "NUL",
    *(f"COM{number}" for number in range(1, 10)),
    *(f"LPT{number}" for number in range(1, 10)),
}
FORBIDDEN_ROOT_COMPONENTS = {
    "active-project.json",
    "backups",
    "diagnostics",
    "exports",
    "logs",
    "project-registry.json",
    "projects",
    "receipts",
    "recovery",
    "settings",
    "updates",
    "workspace",
}
FORBIDDEN_PATH_FRAGMENTS = {
    "builder-runtime/layered-world/",
    "builder-runtime/server/client.pem",
    "builder-runtime/server/ipbans.txt",
    "builder-runtime/server/server.pem",
    "clientsettings.conf",
    "credentials.txt",
    "landscape.orsc",
    "world-builder.credential",
    "world_builder.db",
}
STRUCTURED_MANIFEST_TYPES = {
    "world-builder-active-project",
    "world-builder-import-receipt",
    "world-builder-project",
    "world-builder-project-registry",
}
STRUCTURED_WORLD_ENCODINGS = {
    "layered-world-placements-v3",
    "legacy-packed-orsc-v1",
}
ALLOWED_RUNTIME_ROLES = {
    "builder-database-seed",
    "client-template",
    "default-definition-catalog",
    "default-render-catalog",
    "runtime-audio",
    "runtime-capability",
    "runtime-configuration",
    "runtime-database-contract",
    "runtime-library",
}
REQUIRED_NATIVE_RUNTIME_RECORDS = {
    (
        f"server/conf/server/languages/{name}",
        f"server/conf/server/languages/{name}",
        "runtime-configuration",
    )
    for name in (
        "AuthenticMessages_en_UK.properties",
        "AuthenticMessages_en_UK_female.properties",
        "AuthenticMessages_en_UK_female_no_misgender.properties",
        "AuthenticMessages_en_UK_gender_neutral.properties",
        "AuthenticMessages_en_UK_male.properties",
        "CustomMessages_en_UK.properties",
        "CustomMessages_en_UK_female.properties",
        "CustomMessages_en_UK_gender_neutral.properties",
        "CustomMessages_en_UK_male.properties",
    )
} | {
    (
        f"server/database/sqlite/patches/{name}",
        f"server/database/sqlite/patches/{name}",
        "runtime-database-contract",
    )
    for name in (
        "2021_05_11_add_db_patches.sql",
        "2023_02_01_former_names.sql",
        "2026_05_14_add_summoning_skill.sql",
        "2026_08_03_add_blessing_skill.sql",
    )
}


class CandidateError(Exception):
    """A candidate violated one final-validation contract."""


def fail(message: str) -> None:
    raise CandidateError(message)


def digest(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def file_identity(metadata: os.stat_result) -> tuple[int, ...]:
    return (
        metadata.st_dev,
        metadata.st_ino,
        metadata.st_size,
        metadata.st_mtime_ns,
        metadata.st_ctime_ns,
    )


def run_git(root: Path, *arguments: str) -> str:
    result = subprocess.run(
        ["git", "-C", str(root), *arguments],
        text=True,
        capture_output=True,
    )
    if result.returncode:
        detail = result.stderr.strip() or result.stdout.strip()
        fail(f"Git inspection failed for {root}: {detail}")
    return result.stdout.strip()


def validate_source_checkout(root: Path) -> str:
    if run_git(root, "rev-parse", "--is-inside-work-tree") != "true":
        fail("World Editor source is not a Git worktree")
    if run_git(root, "status", "--porcelain=v1", "--untracked-files=all"):
        fail("World Editor source must be clean for candidate inspection")
    branch = run_git(root, "symbolic-ref", "--quiet", "--short", "HEAD")
    if branch != "main":
        fail(f"World Editor candidate source must be on main; found {branch}")
    head = run_git(root, "rev-parse", "HEAD")
    published = run_git(root, "rev-parse", "refs/remotes/origin/main^{commit}")
    if published != head:
        fail("World Editor candidate source is not the published origin/main commit")
    return head


def read_locked_core_commit(source_root: Path) -> str:
    lock = source_root / "core-framework.lock"
    try:
        lines = lock.read_text(encoding="utf-8").splitlines()
    except OSError as error:
        fail(f"Unable to read core-framework.lock: {error}")
    values: dict[str, str] = {}
    for line in lines:
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        values[key] = value
    commit = values.get("CORE_COMMIT", "")
    if not re.fullmatch(r"[0-9a-f]{40}", commit):
        fail("core-framework.lock does not name one lowercase 40-character commit")
    return commit


def validate_core_checkout(root: Path, expected_commit: str) -> None:
    if run_git(root, "rev-parse", "--is-inside-work-tree") != "true":
        fail("Locked runtime source is not a Git worktree")
    if run_git(root, "status", "--porcelain=v1", "--untracked-files=all"):
        fail("Locked runtime source must be clean for candidate inspection")
    actual = run_git(root, "rev-parse", "HEAD")
    if actual != expected_commit:
        fail(
            "Locked runtime source mismatch: core-framework.lock requires "
            f"{expected_commit}, found {actual}"
        )


def is_within(path: Path, root: Path) -> bool:
    try:
        path.relative_to(root)
        return True
    except ValueError:
        return False


def external_regular_file(path: Path, source_root: Path, core_root: Path) -> Path:
    if path.is_symlink():
        fail(f"Candidate input must not be a symbolic link: {path}")
    try:
        resolved = path.resolve(strict=True)
    except OSError as error:
        fail(f"Candidate input is unavailable: {path}: {error}")
    if not resolved.is_file():
        fail(f"Candidate input is not a regular file: {resolved}")
    if is_within(resolved, source_root) or is_within(resolved, core_root):
        fail(
            "Candidate archives and checksums must be inspected from outside both "
            f"source trees: {resolved}"
        )
    return resolved


def read_stable_external(path: Path, maximum_bytes: int) -> tuple[bytes, tuple[int, ...]]:
    try:
        with path.open("rb") as source:
            before = os.fstat(source.fileno())
            if not stat.S_ISREG(before.st_mode):
                fail(f"Candidate input is not a regular file: {path}")
            if before.st_size > maximum_bytes:
                fail(f"Candidate input exceeds its inspection limit: {path.name}")
            if getattr(before, "st_nlink", 1) != 1:
                fail(f"Candidate input must not have filesystem aliases: {path}")
            data = source.read(maximum_bytes + 1)
            after = os.fstat(source.fileno())
        visible = path.stat(follow_symlinks=False)
    except OSError as error:
        fail(f"Unable to read stable candidate input {path}: {error}")
    if len(data) > maximum_bytes:
        fail(f"Candidate input exceeds its inspection limit: {path.name}")
    identity_before = file_identity(before)
    identity_after = file_identity(after)
    identity_visible = file_identity(visible)
    if identity_before != identity_after or identity_after != identity_visible:
        fail(f"Candidate input changed or was replaced during inspection: {path.name}")
    return data, identity_visible


def require_external_unchanged(
    path: Path, expected_data: bytes, expected_identity: tuple[int, ...], maximum_bytes: int
) -> None:
    actual_data, actual_identity = read_stable_external(path, maximum_bytes)
    if actual_identity != expected_identity or actual_data != expected_data:
        fail(f"Candidate input changed after inspection: {path.name}")


def external_runtime_directory(
    path: Path, source_root: Path, core_root: Path, platform: str
) -> Path:
    try:
        metadata = path.stat(follow_symlinks=False)
        resolved = path.resolve(strict=True)
    except OSError as error:
        fail(f"Reviewed {platform} JRE input is unavailable: {path}: {error}")
    if path.is_symlink() or not stat.S_ISDIR(metadata.st_mode):
        fail(f"Reviewed {platform} JRE input must be a real directory: {path}")
    if (
        is_within(resolved, source_root)
        or is_within(source_root, resolved)
        or is_within(resolved, core_root)
        or is_within(core_root, resolved)
    ):
        fail(
            f"Reviewed {platform} JRE input must be separate from both source trees: "
            f"{resolved}"
        )
    return resolved


def relevant_runtime_mode(mode: int) -> int:
    return stat.S_IMODE(mode) & RELEVANT_RUNTIME_MODE_MASK


def runtime_inventory_digest(inventory: dict[str, Any]) -> str:
    encoded = json.dumps(
        inventory, ensure_ascii=True, separators=(",", ":"), sort_keys=True
    ).encode("utf-8")
    return digest(encoded)


def inventory_runtime_tree(root: Path, platform: str) -> dict[str, Any]:
    """Inventory exactly what the packager's dereferencing copy must contain."""

    try:
        root_before = root.stat(follow_symlinks=False)
    except OSError as error:
        fail(f"Unable to inspect reviewed {platform} JRE input: {error}")
    if root.is_symlink() or not stat.S_ISDIR(root_before.st_mode):
        fail(f"Reviewed {platform} JRE input must remain a real directory: {root}")
    if stat.S_IMODE(root_before.st_mode) & 0o7000:
        fail(f"Reviewed {platform} JRE root has forbidden special permission bits")

    files: dict[str, dict[str, int | str]] = {}
    # `runtime/` is the package-owned wrapper normalized by the packager; every
    # descendant mode comes from the reviewed dereferenced JRE tree.
    directories: dict[str, int] = {"runtime": 0o755}
    folded_paths: dict[str, str] = {"runtime": "runtime"}
    exact_paths = {"runtime"}
    entry_count = 1
    expanded_bytes = 0

    def record_path(relative: str) -> None:
        nonlocal entry_count
        display = f"runtime/{relative}" if relative else "runtime"
        validate_zip_name(f"{PACKAGE_ROOT}/{display}")
        folded = display.casefold()
        previous = folded_paths.get(folded)
        if previous is not None and previous != display:
            fail(
                "Reviewed JRE contains case-colliding dereferenced paths: "
                f"{previous!r} and {display!r}"
            )
        if display not in exact_paths:
            entry_count += 1
            if entry_count > MAX_RUNTIME_ENTRIES:
                fail(f"Reviewed {platform} JRE exceeds the entry-count limit")
            exact_paths.add(display)
        folded_paths[folded] = display

    def stable_link_state(path: Path) -> tuple[tuple[int, ...], str] | None:
        metadata = path.stat(follow_symlinks=False)
        if not stat.S_ISLNK(metadata.st_mode):
            return None
        try:
            target_text = os.readlink(path)
        except OSError as error:
            fail(f"Unable to read reviewed {platform} JRE link {path}: {error}")
        return file_identity(metadata), target_text

    def require_link_unchanged(
        path: Path, expected: tuple[tuple[int, ...], str] | None
    ) -> None:
        actual = stable_link_state(path)
        if actual != expected:
            fail(f"Reviewed {platform} JRE link changed during inspection: {path}")

    def visit(source_path: Path, relative: str, ancestors: frozenset[tuple[int, int]]) -> None:
        nonlocal expanded_bytes
        if len(ancestors) > MAX_RUNTIME_DEPTH:
            fail(f"Reviewed {platform} JRE exceeds the directory-depth limit")
        record_path(relative)
        link_state = stable_link_state(source_path)
        try:
            resolved = source_path.resolve(strict=True)
            resolved.relative_to(root)
            target_before = resolved.stat(follow_symlinks=False)
        except (FileNotFoundError, OSError, RuntimeError, ValueError) as error:
            fail(
                f"Reviewed {platform} JRE contains a broken or external link at "
                f"runtime/{relative}: {error}"
            )
        permissions = stat.S_IMODE(target_before.st_mode)
        if permissions & 0o7000:
            fail(
                f"Reviewed {platform} JRE has forbidden special permission bits at "
                f"runtime/{relative}"
            )
        archive_relative = f"runtime/{relative}"
        if stat.S_ISDIR(target_before.st_mode):
            identity = (target_before.st_dev, target_before.st_ino)
            if identity in ancestors:
                fail(
                    f"Reviewed {platform} JRE contains a dereferenced directory cycle at "
                    f"{archive_relative}"
                )
            directories[archive_relative] = relevant_runtime_mode(target_before.st_mode)
            try:
                with os.scandir(resolved) as scan:
                    names = sorted(entry.name for entry in scan)
            except OSError as error:
                fail(f"Unable to enumerate reviewed {platform} JRE: {error}")
            for name in names:
                child_relative = f"{relative}/{name}" if relative else name
                visit(resolved / name, child_relative, ancestors | {identity})
            try:
                target_after = resolved.stat(follow_symlinks=False)
            except OSError as error:
                fail(f"Reviewed {platform} JRE directory changed during inspection: {error}")
            if file_identity(target_after) != file_identity(target_before):
                fail(
                    f"Reviewed {platform} JRE directory changed during inspection: "
                    f"{archive_relative}"
                )
            require_link_unchanged(source_path, link_state)
            return
        if not stat.S_ISREG(target_before.st_mode):
            fail(
                f"Reviewed {platform} JRE contains a special filesystem entry: "
                f"{archive_relative}"
            )
        if getattr(target_before, "st_nlink", 1) != 1:
            fail(
                f"Reviewed {platform} JRE file has filesystem aliases: "
                f"{archive_relative}"
            )
        if target_before.st_size > MAX_RUNTIME_ENTRY_BYTES:
            fail(f"Reviewed {platform} JRE file exceeds the per-file limit")
        try:
            with resolved.open("rb") as source:
                opened_before = os.fstat(source.fileno())
                data = source.read(MAX_RUNTIME_ENTRY_BYTES + 1)
                opened_after = os.fstat(source.fileno())
            visible_after = resolved.stat(follow_symlinks=False)
        except OSError as error:
            fail(f"Unable to read reviewed {platform} JRE file: {error}")
        if len(data) > MAX_RUNTIME_ENTRY_BYTES:
            fail(f"Reviewed {platform} JRE file exceeds the per-file limit")
        expected_identity = file_identity(target_before)
        if not (
            file_identity(opened_before)
            == file_identity(opened_after)
            == file_identity(visible_after)
            == expected_identity
        ):
            fail(
                f"Reviewed {platform} JRE file changed during inspection: "
                f"{archive_relative}"
            )
        require_link_unchanged(source_path, link_state)
        expanded_bytes += len(data)
        if expanded_bytes > MAX_RUNTIME_EXPANDED_BYTES:
            fail(f"Reviewed {platform} JRE exceeds the expanded-byte limit")
        files[archive_relative] = {
            "byteSize": len(data),
            "relevantMode": relevant_runtime_mode(target_before.st_mode),
            "sha256": digest(data),
        }

    try:
        with os.scandir(root) as scan:
            root_names = sorted(entry.name for entry in scan)
    except OSError as error:
        fail(f"Unable to enumerate reviewed {platform} JRE root: {error}")
    root_identity = (root_before.st_dev, root_before.st_ino)
    for name in root_names:
        visit(root / name, name, frozenset({root_identity}))
    try:
        root_after = root.stat(follow_symlinks=False)
    except OSError as error:
        fail(f"Reviewed {platform} JRE root changed during inspection: {error}")
    if file_identity(root_after) != file_identity(root_before):
        fail(f"Reviewed {platform} JRE root changed during inspection")
    return {
        "directories": dict(sorted(directories.items())),
        "files": dict(sorted(files.items())),
    }


def parse_checksums(data: bytes) -> dict[str, str]:
    try:
        lines = data.decode("utf-8").splitlines()
    except UnicodeDecodeError as error:
        fail(f"Unable to read SHA256SUMS.txt: {error}")
    entries: dict[str, str] = {}
    for line in lines:
        if "  " not in line:
            fail(f"Malformed SHA256SUMS.txt line: {line!r}")
        checksum, name = line.split("  ", 1)
        if not HASH_PATTERN.fullmatch(checksum):
            fail(f"Malformed SHA256SUMS.txt digest: {checksum!r}")
        if not name or name != Path(name).name or name in entries:
            fail(f"Unsafe or duplicate SHA256SUMS.txt name: {name!r}")
        entries[name] = checksum
    return entries


def validate_component(component: str, display: str) -> None:
    if (
        component in {"", ".", ".."}
        or component.endswith((" ", "."))
        or any(ord(character) < 32 or character in '<>:"\\|?*' for character in component)
        or component.split(".", 1)[0].upper() in WINDOWS_RESERVED
    ):
        fail(f"Unsafe archive path component in {display!r}")


def validate_zip_name(name: str) -> tuple[str, ...]:
    if not name or name.startswith(("/", "\\")) or "\\" in name or "\0" in name:
        fail(f"Unsafe archive path: {name!r}")
    path = PurePosixPath(name)
    parts = path.parts
    canonical = PurePosixPath(*parts).as_posix()
    if name != canonical and name != canonical + "/":
        fail(f"Non-canonical archive path: {name!r}")
    if not parts or parts[0] != PACKAGE_ROOT:
        fail(f"Archive entry is outside the single {PACKAGE_ROOT!r} root: {name!r}")
    for component in parts:
        validate_component(component, name)
    return parts


def reject_structured_world(data: bytes, display: str) -> None:
    if len(data) > MAX_STRUCTURED_DOCUMENT_BYTES:
        if display.casefold().endswith(".json"):
            fail(f"Structured candidate document exceeds the inspection limit: {display}")
        return
    try:
        value = json.loads(data.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError):
        return
    pending: list[Any] = [value]
    while pending:
        item = pending.pop()
        if isinstance(item, dict):
            package_type = item.get("packageType")
            encoding = item.get("encoding")
            manifest_type = item.get("manifestType")
            if package_type == "layered-world":
                fail(f"Layered world package content is forbidden: {display}")
            if isinstance(encoding, str) and encoding in STRUCTURED_WORLD_ENCODINGS:
                fail(f"Terrain or placement payload content is forbidden: {display}")
            if (
                isinstance(manifest_type, str)
                and manifest_type in STRUCTURED_MANIFEST_TYPES
            ):
                fail(f"Creator or transaction state is forbidden: {display}")
            pending.extend(item.values())
        elif isinstance(item, list):
            pending.extend(item)


def forbidden_core_hashes(core_root: Path) -> dict[str, tuple[str, str]]:
    result: dict[str, tuple[str, str]] = {}
    file_count = 0
    total_bytes = 0
    patterns = (
        ("Client_Base/Cache/video/*Landscape*", "map terrain"),
        ("server/conf/server/data/**/*", "map terrain"),
        ("server/conf/server/defs/locs/**/*", "static placement data"),
        ("tools/layered-maps/workspace/**/*", "layered world package"),
    )
    for pattern, role in patterns:
        for path in core_root.glob(pattern):
            if path.is_file() and not path.is_symlink():
                file_count += 1
                if file_count > MAX_FORBIDDEN_CORE_FILES:
                    fail("Locked runtime forbidden-world inventory exceeds its file limit")
                data = read_expected_file(
                    path,
                    f"forbidden {role} source {path.relative_to(core_root).as_posix()}",
                    core_root,
                )
                total_bytes += len(data)
                if total_bytes > MAX_FORBIDDEN_CORE_BYTES:
                    fail("Locked runtime forbidden-world inventory exceeds its byte limit")
                result[digest(data)] = (
                    role,
                    path.relative_to(core_root).as_posix(),
                )
    return result


def parse_runtime_allowlist(
    source_root: Path,
    core_root: Path,
) -> tuple[bytes, set[str], dict[str, str], dict[str, Path]]:
    path = source_root / "release/world-builder-v2/RUNTIME-ASSET-ALLOWLIST.txt"
    try:
        contents = path.read_bytes()
        lines = contents.decode("utf-8").splitlines()
    except (OSError, UnicodeDecodeError) as error:
        fail(f"Unable to read the runtime allowlist: {error}")
    allowed = set(FIXED_BUILDER_RUNTIME_FILES)
    sources: set[str] = set()
    destinations: set[str] = set()
    runtime_sources: dict[str, str] = {}
    records: set[tuple[str, str, str]] = set()
    project_only_generated = {"server/client.pem", "server/server.pem"}
    for line in lines:
        if not line or line.startswith("#"):
            continue
        fields = line.split("\t")
        if len(fields) != 3:
            fail(f"Malformed runtime allowlist line: {line!r}")
        source, destination, role = fields
        source_path = PurePosixPath(source)
        if source_path.is_absolute() or source_path.as_posix() != source:
            fail(f"Unsafe runtime allowlist source: {source!r}")
        for component in source_path.parts:
            validate_component(component, source)
        source_key = source.casefold()
        destination_key = destination.casefold()
        if source_key in sources or destination_key in destinations:
            fail(f"Duplicate runtime allowlist source or destination: {line!r}")
        if role not in ALLOWED_RUNTIME_ROLES:
            fail(f"Unknown runtime allowlist role: {role!r}")
        if source in project_only_generated or destination in project_only_generated:
            fail(f"Runtime allowlist includes project-only generated state: {line!r}")
        validate_zip_name(f"{PACKAGE_ROOT}/builder-runtime/{destination}")
        sources.add(source_key)
        destinations.add(destination_key)
        records.add((source, destination, role))
        allowed.add("builder-runtime/" + destination)
        runtime_sources["builder-runtime/" + destination] = source
    missing_native = REQUIRED_NATIVE_RUNTIME_RECORDS - records
    if missing_native:
        missing = sorted(destination for _, destination, _ in missing_native)
        fail(
            "Runtime allowlist is missing required native server assets: "
            + ", ".join(missing)
        )
    definition_prefix = "server/conf/server/defs/"
    definition_root = core_root / "server/conf/server/defs"
    if not definition_root.is_dir() or definition_root.is_symlink():
        fail("Exact provider definition root is missing or unsafe")
    provider_definitions: set[str] = set()
    for definition in definition_root.rglob("*"):
        relative = definition.relative_to(definition_root)
        if relative.parts and relative.parts[0].casefold() == "locs":
            continue
        if definition.is_symlink():
            fail(f"Exact provider definition closure contains a link: {relative}")
        if definition.is_dir():
            continue
        if (
            not definition.is_file()
            or definition.stat(follow_symlinks=False).st_nlink != 1
        ):
            fail(
                "Exact provider definition closure contains an unsupported entry: "
                + relative.as_posix()
            )
        provider_definitions.add(definition_prefix + relative.as_posix())
    if not provider_definitions:
        fail("Exact provider definition closure is empty")
    required_definition_records = {
        (relative, relative, "default-definition-catalog")
        for relative in provider_definitions
    }
    allowlisted_definition_records = {
        record
        for record in records
        if record[0].casefold().startswith(definition_prefix)
        or record[1].casefold().startswith(definition_prefix)
    }
    if any(
        source.casefold().startswith(definition_prefix + "locs/")
        or destination.casefold().startswith(definition_prefix + "locs/")
        for source, destination, _ in records
    ):
        fail("Runtime allowlist must exclude the complete defs/locs subtree")
    missing_definitions = required_definition_records - allowlisted_definition_records
    extra_definitions = allowlisted_definition_records - required_definition_records
    if missing_definitions or extra_definitions:
        detail = []
        if missing_definitions:
            detail.append(
                "missing "
                + ", ".join(sorted(source for source, _, _ in missing_definitions))
            )
        if extra_definitions:
            detail.append(
                "unexpected "
                + ", ".join(sorted(source for source, _, _ in extra_definitions))
            )
        fail(
            "Runtime allowlist does not match the exact content-neutral definition "
            "closure: " + "; ".join(detail)
        )
    schema_root = source_root / "tools/world-builder/schema"
    schemas: dict[str, Path] = {}
    for schema in schema_root.rglob("*"):
        if schema.is_file():
            relative = schema.relative_to(schema_root).as_posix()
            archive_relative = "builder-runtime/launcher/schema/" + relative
            allowed.add(archive_relative)
            schemas[archive_relative] = schema
    return contents, allowed, runtime_sources, schemas


def read_expected_file(path: Path, label: str, root: Path) -> bytes:
    try:
        resolved = path.resolve(strict=True)
        metadata = path.stat(follow_symlinks=False)
    except OSError as error:
        fail(f"Unable to inspect exact candidate source input ({label}): {error}")
    if (
        path.is_symlink()
        or not stat.S_ISREG(metadata.st_mode)
        or resolved != path.absolute()
        or not is_within(resolved, root)
        or getattr(metadata, "st_nlink", 1) != 1
        or metadata.st_size > MAX_ARCHIVE_ENTRY_BYTES
    ):
        fail(f"Exact candidate source input is missing, linked, or unsafe ({label})")
    try:
        data = path.read_bytes()
    except OSError as error:
        fail(f"Unable to read exact candidate source input ({label}): {error}")
    if file_identity(path.stat(follow_symlinks=False)) != file_identity(metadata):
        fail(f"Exact candidate source input changed while read ({label})")
    return data


def copied_file_expectations(
    source_root: Path,
    core_root: Path,
    version: str,
    source_commit: str,
    core_commit: str,
    runtime_sources: dict[str, str],
    schemas: dict[str, Path],
) -> dict[str, bytes]:
    source_files = {
        "ASSET-SOURCES.txt": "release/world-builder-v2/ASSET-SOURCES.txt",
        "Import Map Changes.cmd": "release/world-builder-v2/Import Map Changes.cmd",
        "Import Map Changes.sh": "release/world-builder-v2/Import Map Changes.sh",
        "LICENSE": "LICENSE",
        "Recover Map Transaction.cmd": "release/world-builder-v2/Recover Map Transaction.cmd",
        "Recover Map Transaction.sh": "release/world-builder-v2/Recover Map Transaction.sh",
        "Start World Builder.cmd": "release/updater-v2/Start World Builder.cmd",
        "Start World Builder.sh": "release/updater-v2/Start World Builder.sh",
        "Undo Last Map Import.cmd": "release/world-builder-v2/Undo Last Map Import.cmd",
        "Undo Last Map Import.sh": "release/world-builder-v2/Undo Last Map Import.sh",
        "Update World Builder.cmd": "release/updater-v2/Update World Builder.cmd",
        "Update World Builder.ps1": "release/updater-v2/Update World Builder.ps1",
        "Update World Builder.sh": "release/updater-v2/Update World Builder.sh",
        "builder-runtime/server/world-builder.conf": (
            "release/world-builder-v2/world-builder-runtime.conf"
        ),
    }
    expected = {
        archive_relative: read_expected_file(
            source_root / source_relative, archive_relative, source_root
        )
        for archive_relative, source_relative in source_files.items()
    }
    expected["RUNTIME-ASSET-ALLOWLIST.txt"] = read_expected_file(
        source_root / "release/world-builder-v2/RUNTIME-ASSET-ALLOWLIST.txt",
        "RUNTIME-ASSET-ALLOWLIST.txt",
        source_root,
    )
    expected["builder-runtime/Client_Base/Open_RSC_Client.jar"] = read_expected_file(
        core_root / "Client_Base/Open_RSC_Client.jar", "production client jar", core_root
    )
    expected["builder-runtime/server/core.jar"] = read_expected_file(
        core_root / "server/core.jar", "production server jar", core_root
    )
    expected["builder-runtime/server/plugins.jar"] = read_expected_file(
        core_root / "server/plugins.jar", "production plugins jar", core_root
    )
    expected["builder-runtime/launcher/world-builder-tools.jar"] = read_expected_file(
        source_root / "output/world-builder-tools/world-builder-tools.jar",
        "production standalone tools jar",
        source_root,
    )
    for archive_relative, source_relative in runtime_sources.items():
        expected[archive_relative] = read_expected_file(
            core_root / source_relative, archive_relative, core_root
        )
    for archive_relative, source_path in schemas.items():
        expected[archive_relative] = read_expected_file(
            source_path, archive_relative, source_root
        )
    expected["PLAYER-ASSET-SOURCES.txt"] = read_expected_file(
        core_root / "release/player/ASSET-SOURCES.txt",
        "PLAYER-ASSET-SOURCES.txt",
        core_root,
    )
    expected["EDITOR-ICON-CREDITS.txt"] = read_expected_file(
        core_root / "dev/myworld/assets/ui/world-editor/CREDITS.md",
        "EDITOR-ICON-CREDITS.txt",
        core_root,
    )
    package_readme = read_expected_file(
        source_root / "release/world-builder-v2/README.txt",
        "README.txt template",
        source_root,
    ).decode("utf-8")
    updater_readme = read_expected_file(
        source_root / "release/updater-v2/README-AUTO-UPDATE.txt",
        "README updater appendix",
        source_root,
    ).decode("utf-8")
    expected["README.txt"] = (
        package_readme.replace("@VERSION@", version).replace(
            "@SOURCE_COMMIT@", source_commit
        )
        + updater_readme
        + f"\nCore-Framework runtime commit: {core_commit}\n"
    ).encode("utf-8")
    return expected


def validate_runtime_capability(data: bytes, display: str) -> None:
    try:
        capability = json.loads(data.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        fail(f"Adaptive runtime capability is invalid ({display}): {error}")
    if not isinstance(capability, dict):
        fail(f"Adaptive runtime capability is not an object: {display}")
    for key, expected in EXPECTED_RUNTIME_CAPABILITY.items():
        if capability.get(key) != expected:
            fail(
                "Adaptive runtime capability identity mismatch "
                f"({display}): {key}"
            )
    authoring = capability.get("authoring")
    if not isinstance(authoring, dict) or authoring.get("placementFamilies") != [
        "boundary",
        "ground-item",
        "npc",
        "scenery",
    ]:
        fail(f"Adaptive runtime placement-family capability mismatch: {display}")


def validate_runtime_configuration(data: bytes, display: str) -> None:
    try:
        lines = data.decode("utf-8").splitlines()
    except UnicodeDecodeError as error:
        fail(f"Builder runtime configuration is not UTF-8 ({display}): {error}")
    values: dict[str, str] = {}
    for raw in lines:
        stripped = raw.strip()
        if not stripped or stripped.startswith("#") or ":" not in stripped:
            continue
        key, value = stripped.split(":", 1)
        values[key.strip()] = value.strip()
    required = {
        "server_bind_address": "127.0.0.1",
        "world_builder_mode": "true",
        "world_builder_adaptive_mode": "true",
        "layered_native_world_runtime_profile": "adaptive-world-builder",
    }
    for key, expected in required.items():
        if values.get(key) != expected:
            fail(f"Builder runtime activation mismatch ({display}): {key}")
    protocol = values.get("client_version", "")
    if not protocol.isdigit() or int(protocol) < 1:
        fail(f"Builder runtime protocol is invalid: {display}")


def validate_application_path(
    relative: str, allowed_runtime: set[str], packaged_jre_files: set[str]
) -> None:
    parts = PurePosixPath(relative).parts
    if parts and parts[0].casefold() in FORBIDDEN_ROOT_COMPONENTS:
        fail(f"Candidate contains durable creator state: {relative}")
    folded = relative.casefold()
    if any(fragment in folded for fragment in FORBIDDEN_PATH_FRAGMENTS):
        fail(f"Candidate contains a forbidden world or operational path: {relative}")
    if (
        relative in TOP_LEVEL_FILES
        or relative in allowed_runtime
        or relative in packaged_jre_files
    ):
        return
    fail(f"Candidate file is outside the exact application allowlist: {relative}")


def validate_application_directory(
    relative: str, allowed_runtime: set[str], packaged_jre_directories: set[str]
) -> None:
    parts = PurePosixPath(relative).parts
    if parts and parts[0].casefold() in FORBIDDEN_ROOT_COMPONENTS:
        fail(f"Candidate contains a durable creator-state directory: {relative}")
    folded = relative.casefold().rstrip("/") + "/"
    if any(fragment in folded for fragment in FORBIDDEN_PATH_FRAGMENTS):
        fail(f"Candidate contains a forbidden world or operational directory: {relative}")
    if relative in packaged_jre_directories:
        return
    possible_files = TOP_LEVEL_FILES | allowed_runtime | packaged_jre_directories
    prefix = relative.rstrip("/") + "/"
    if any(candidate.startswith(prefix) for candidate in possible_files):
        return
    fail(f"Candidate directory is outside the exact application allowlist: {relative}")


def validate_zip_limits(
    infos: list[zipfile.ZipInfo],
    display: str,
    maximum_entries: int,
    maximum_expanded_bytes: int,
    maximum_entry_bytes: int,
) -> None:
    if len(infos) > maximum_entries:
        fail(f"Archive entry count exceeds the inspection limit: {display}")
    expanded = 0
    for info in infos:
        if info.file_size < 0 or info.compress_size < 0:
            fail(f"Archive contains an invalid entry size: {display}!{info.filename}")
        if info.file_size > maximum_entry_bytes:
            fail(f"Archive entry expands beyond the inspection limit: {display}")
        expanded += info.file_size
        if expanded > maximum_expanded_bytes:
            fail(f"Archive expands beyond the inspection limit: {display}")
        if (
            info.file_size > 1024 * 1024
            and info.compress_size > 0
            and info.file_size > info.compress_size * 1000
        ):
            fail(f"Archive entry has an unsafe compression ratio: {display}")


def validate_nested_archive(
    data: bytes,
    display: str,
    forbidden_hashes: dict[str, tuple[str, str]],
    require_archive: bool = False,
) -> set[str]:
    try:
        nested = zipfile.ZipFile(io.BytesIO(data))
    except zipfile.BadZipFile:
        if require_archive:
            fail(f"Nested JAR/ZIP input is not a valid archive: {display}")
        return set()
    names: set[str] = set()
    seen: dict[str, str] = {}
    with nested:
        infos = nested.infolist()
        validate_zip_limits(
            infos,
            display,
            MAX_NESTED_ARCHIVE_ENTRIES,
            MAX_NESTED_EXPANDED_BYTES,
            MAX_NESTED_ENTRY_BYTES,
        )
        corrupt = nested.testzip()
        if corrupt is not None:
            fail(f"Nested archive ZIP integrity failed at {display}!{corrupt}")
        for info in infos:
            name = info.filename
            if not name or name.startswith(("/", "\\")) or "\\" in name:
                fail(f"Unsafe nested archive path: {display}!{name!r}")
            path = PurePosixPath(name)
            canonical = PurePosixPath(*path.parts).as_posix()
            if name != canonical and name != canonical + "/":
                fail(f"Non-canonical nested archive path: {display}!{name!r}")
            for component in path.parts:
                validate_component(component, f"{display}!{name}")
            lowered = canonical.casefold()
            if lowered in seen:
                fail(
                    "Duplicate or case-colliding nested archive entry: "
                    f"{display}!{seen[lowered]} and {name}"
                )
            seen[lowered] = name
            names.add(name)
            mode = info.external_attr >> 16
            kind = stat.S_IFMT(mode)
            expected_kinds = (0, stat.S_IFDIR) if info.is_dir() else (0, stat.S_IFREG)
            if kind not in expected_kinds:
                fail(f"Link or special nested archive entry is forbidden: {display}!{name}")
            if info.flag_bits & 0x1:
                fail(f"Encrypted nested archive entry is forbidden: {display}!{name}")
            if info.is_dir():
                if info.file_size:
                    fail(f"Nested archive directory entry carries data: {display}!{name}")
                continue
            nested_data = nested.read(info)
            nested_digest = digest(nested_data)
            if nested_digest in forbidden_hashes:
                role, source = forbidden_hashes[nested_digest]
                fail(
                    f"Nested archive contains forbidden {role} copied from {source}: "
                    f"{display}!{name}"
                )
            if PACKED_TERRAIN_ENTRY.search(name):
                fail(f"Nested archive contains packed terrain: {display}!{name}")
            if not name.endswith(".class"):
                reject_structured_world(nested_data, f"{display}!{name}")
    return names


def parse_manifest(data: bytes, display: str) -> dict[str, str]:
    try:
        lines = data.decode("utf-8").splitlines()
    except UnicodeDecodeError as error:
        fail(f"Package manifest is not UTF-8 in {display}: {error}")
    entries: dict[str, str] = {}
    paths: list[str] = []
    for line in lines:
        marker = "  ./"
        if marker not in line:
            fail(f"Malformed package manifest line in {display}: {line!r}")
        checksum, relative = line.split(marker, 1)
        if not HASH_PATTERN.fullmatch(checksum):
            fail(f"Malformed package manifest digest in {display}: {checksum!r}")
        validate_zip_name(f"{PACKAGE_ROOT}/{relative}")
        if relative in entries:
            fail(f"Duplicate package manifest path in {display}: {relative}")
        entries[relative] = checksum
        paths.append(relative)
    if paths != sorted(paths):
        fail(f"Package manifest is not in deterministic path order: {display}")
    return entries


def validate_identity(
    data: bytes,
    version: str,
    source_commit: str,
    core_commit: str,
    display: str,
) -> None:
    try:
        identity = json.loads(data.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        fail(f"Invalid release identity in {display}: {error}")
    expected = {
        "schemaVersion": 1,
        "productId": PRODUCT_ID,
        "productGeneration": 2,
        "displayName": PACKAGE_ROOT,
        "updateChannel": PRODUCT_ID,
        "releaseTag": f"{PRODUCT_ID}-{version.removeprefix('v')}",
        "artifactPrefix": PRODUCT_ID,
        "worldSourceIdentity": WORLD_SOURCE_IDENTITY,
        "automaticUpgradeFromProductIds": [PRODUCT_ID],
        "legacyProductId": "rsc-world-editor-v1",
        "legacyFinalTag": "v1.1.0",
        "legacyWorkspaceMigration": False,
        "version": version,
        "sourceCommit": source_commit,
        "coreSourceCommit": core_commit,
    }
    if identity != expected:
        fail(f"Release identity does not exactly match the candidate inputs: {display}")


def validate_seed(data: bytes, display: str) -> None:
    descriptor, temporary = tempfile.mkstemp(prefix="world-builder-seed-", suffix=".db")
    try:
        with os.fdopen(descriptor, "wb") as output:
            output.write(data)
        connection = sqlite3.connect(f"file:{temporary}?mode=ro", uri=True)
        try:
            integrity = [row[0] for row in connection.execute("PRAGMA integrity_check")]
            if integrity != ["ok"]:
                fail(f"Builder seed failed SQLite integrity_check: {display}")
            tables = [
                row[0]
                for row in connection.execute(
                    "SELECT name FROM sqlite_schema WHERE type = 'table' ORDER BY name"
                )
            ]
            counts: dict[str, int] = {}
            for table in tables:
                quoted = '"' + table.replace('"', '""') + '"'
                counts[table] = int(
                    connection.execute(f"SELECT COUNT(*) FROM {quoted}").fetchone()[0]
                )
            for required in ("grounditems", "npclocs", "objects"):
                if required not in counts or counts[required]:
                    fail(f"Builder seed is missing an empty {required} table: {display}")
            allowed_nonempty = {"db_patches", "recovery_questions", "sqlite_sequence"}
            for table, count in counts.items():
                if count and table not in allowed_nonempty:
                    fail(f"Builder seed contains forbidden {table} rows: {display}")
        finally:
            connection.close()
    except sqlite3.Error as error:
        fail(f"Builder seed is not a valid readable SQLite database: {display}: {error}")
    finally:
        Path(temporary).unlink(missing_ok=True)


def text_entry(files: dict[str, bytes], relative: str, display: str) -> str:
    try:
        return files[relative].decode("utf-8")
    except KeyError:
        fail(f"Candidate is missing required file {relative}: {display}")
    except UnicodeDecodeError as error:
        fail(f"Candidate text file is not UTF-8 ({relative}): {display}: {error}")
    raise AssertionError("unreachable")


def validate_runtime_metadata(files: dict[str, bytes], platform: str, display: str) -> None:
    metadata = text_entry(files, "runtime/release", display)
    values: dict[str, str] = {}
    for line in metadata.splitlines():
        match = re.fullmatch(r"([A-Z0-9_]+)=\"([^\"]*)\"", line)
        if match:
            values[match.group(1)] = match.group(2)
    version = values.get("JAVA_VERSION", "")
    major_text = version[2:].split(".", 1)[0] if version.startswith("1.") else version.split(".", 1)[0]
    if not major_text.isdigit() or int(major_text) < 17:
        fail(f"{platform} candidate runtime is not Java 17+: {display}")
    expected_os = "Windows" if platform == "windows" else "Linux"
    if values.get("OS_NAME") != expected_os:
        fail(f"{platform} candidate runtime OS metadata is incorrect: {display}")
    if values.get("OS_ARCH") not in {"amd64", "x86_64"}:
        fail(f"{platform} candidate runtime is not x64: {display}")
    legal = {
        "runtime/LICENSE",
        "runtime/NOTICE",
        "runtime/legal/java.base/LICENSE",
    }
    if not legal.intersection(files):
        fail(f"{platform} candidate runtime has no redistribution notice: {display}")


def validate_archive(
    path: Path,
    archive_data: bytes,
    platform: str,
    version: str,
    source_commit: str,
    core_commit: str,
    allowlist_bytes: bytes,
    allowed_runtime: set[str],
    forbidden_hashes: dict[str, tuple[str, str]],
    copied_files: dict[str, bytes],
    jre_inventory: dict[str, Any],
) -> dict[str, Any]:
    try:
        archive = zipfile.ZipFile(io.BytesIO(archive_data))
    except zipfile.BadZipFile as error:
        fail(f"Candidate is not a valid ZIP archive ({path.name}): {error}")
    files: dict[str, bytes] = {}
    modes: dict[str, int] = {}
    directory_modes: dict[str, int] = {}
    seen: dict[str, str] = {}
    packaged_jre_files = set(jre_inventory["files"])
    packaged_jre_directories = set(jre_inventory["directories"])
    with archive:
        infos = archive.infolist()
        validate_zip_limits(
            infos,
            path.name,
            MAX_ARCHIVE_ENTRIES,
            MAX_ARCHIVE_EXPANDED_BYTES,
            MAX_ARCHIVE_ENTRY_BYTES,
        )
        corrupt = archive.testzip()
        if corrupt is not None:
            fail(f"Candidate ZIP integrity failed at {corrupt}: {path.name}")
        for info in infos:
            parts = validate_zip_name(info.filename)
            folded = PurePosixPath(*parts).as_posix().casefold()
            if folded in seen:
                fail(
                    "Duplicate or case-colliding candidate paths: "
                    f"{seen[folded]!r} and {info.filename!r}"
                )
            seen[folded] = info.filename
            mode = info.external_attr >> 16
            kind = stat.S_IFMT(mode)
            expected_kinds = (0, stat.S_IFDIR) if info.is_dir() else (0, stat.S_IFREG)
            if kind not in expected_kinds:
                fail(f"Candidate contains a link or special entry: {info.filename}")
            if stat.S_IMODE(mode) & 0o7000:
                fail(f"Candidate contains forbidden special permission bits: {info.filename}")
            if info.flag_bits & 0x1:
                fail(f"Candidate contains an encrypted entry: {info.filename}")
            relative = PurePosixPath(*parts[1:]).as_posix()
            if info.is_dir():
                if info.file_size:
                    fail(f"Candidate directory entry carries data: {info.filename}")
                if relative != ".":
                    validate_application_directory(
                        relative, allowed_runtime, packaged_jre_directories
                    )
                    directory_modes[relative] = mode
                continue
            if not relative:
                fail(f"Candidate contains a file at the package root entry: {path.name}")
            validate_application_path(relative, allowed_runtime, packaged_jre_files)
            data = archive.read(info)
            files[relative] = data
            modes[relative] = mode
            copied = digest(data)
            if copied in forbidden_hashes:
                role, source = forbidden_hashes[copied]
                fail(
                    f"Candidate contains forbidden {role} copied from {source}: "
                    f"{path.name}!{relative}"
                )
            reject_structured_world(data, f"{path.name}!{relative}")

    required = (
        TOP_LEVEL_FILES
        | allowed_runtime
        | REQUIRED_BUILDER_RUNTIME_FILES
        | packaged_jre_files
    )
    missing = sorted(required - files.keys())
    if missing:
        fail(f"Candidate is missing required files ({path.name}): {', '.join(missing)}")
    if files["RUNTIME-ASSET-ALLOWLIST.txt"] != allowlist_bytes:
        fail(f"Candidate runtime allowlist differs from source: {path.name}")
    actual_jre_files = {relative for relative in files if relative.startswith("runtime/")}
    actual_jre_directories = {
        relative
        for relative in directory_modes
        if relative == "runtime" or relative.startswith("runtime/")
    }
    if actual_jre_files != packaged_jre_files:
        fail(
            f"Candidate JRE file inventory differs from the reviewed {platform} input: "
            f"{path.name}"
        )
    if actual_jre_directories != packaged_jre_directories:
        fail(
            f"Candidate JRE directory inventory differs from the reviewed {platform} input: "
            f"{path.name}"
        )
    for relative, expected in jre_inventory["files"].items():
        if (
            len(files[relative]) != expected["byteSize"]
            or digest(files[relative]) != expected["sha256"]
            or relevant_runtime_mode(modes[relative]) != expected["relevantMode"]
        ):
            fail(
                f"Candidate JRE bytes or relevant mode differ from the reviewed "
                f"{platform} input ({path.name}): {relative}"
            )
    for relative, expected_mode in jre_inventory["directories"].items():
        actual_mode = relevant_runtime_mode(directory_modes[relative])
        if actual_mode != expected_mode:
            fail(
                f"Candidate JRE directory mode differs from the reviewed {platform} "
                f"input ({path.name}): {relative}; expected {expected_mode:04o}, "
                f"found {actual_mode:04o}"
            )
    manifest = parse_manifest(files[MANIFEST_NAME], path.name)
    actual = set(files) - {MANIFEST_NAME}
    if set(manifest) != actual:
        missing_manifest = sorted(actual - set(manifest))
        missing_files = sorted(set(manifest) - actual)
        fail(
            f"Package manifest is not exhaustive ({path.name}); "
            f"unmanifested={missing_manifest}, absent={missing_files}"
        )
    for relative, checksum in manifest.items():
        if digest(files[relative]) != checksum:
            fail(f"Package manifest digest mismatch ({path.name}): {relative}")

    if text_entry(files, "VERSION.txt", path.name) != version + "\n":
        fail(f"VERSION.txt mismatch: {path.name}")
    if text_entry(files, "SOURCE-COMMIT.txt", path.name) != source_commit + "\n":
        fail(f"SOURCE-COMMIT.txt mismatch: {path.name}")
    if text_entry(files, "CORE-SOURCE-COMMIT.txt", path.name) != core_commit + "\n":
        fail(f"CORE-SOURCE-COMMIT.txt mismatch: {path.name}")
    validate_identity(
        files["RELEASE-IDENTITY.json"], version, source_commit, core_commit, path.name
    )
    validate_runtime_capability(
        files[
            "builder-runtime/server/conf/world-builder/"
            "adaptive-runtime-capability-v1.json"
        ],
        path.name,
    )
    validate_runtime_configuration(
        files["builder-runtime/server/world-builder.conf"], path.name
    )
    validate_runtime_metadata(files, platform, path.name)

    runtime_java = "runtime/bin/java.exe" if platform == "windows" else "runtime/bin/java"
    if runtime_java not in files:
        fail(f"{platform} candidate is missing its bundled Java executable: {path.name}")
    if platform == "linux":
        for launcher in sorted(LINUX_EXECUTABLE_LAUNCHERS):
            if stat.S_IMODE(modes[launcher]) != 0o755:
                fail(
                    f"Linux production launcher must have exact mode 0755 "
                    f"({path.name}): {launcher}"
                )
        if stat.S_IMODE(modes[runtime_java]) & 0o111 != 0o111:
            fail(f"Linux bundled Java is not executable for all users: {path.name}")

    client_entries = validate_nested_archive(
        files["builder-runtime/Client_Base/Open_RSC_Client.jar"],
        f"{path.name}!builder-runtime/Client_Base/Open_RSC_Client.jar",
        forbidden_hashes,
        True,
    )
    if not REQUIRED_CLIENT_ENTRIES.issubset(client_entries):
        fail(f"Production client is missing its marker/classes/natives: {path.name}")
    with zipfile.ZipFile(
        io.BytesIO(files["builder-runtime/Client_Base/Open_RSC_Client.jar"])
    ) as client:
        if client.read(RELEASE_MARKER_ENTRY) != b"release-build=true\n":
            fail(f"Production client release marker is invalid: {path.name}")
    server_entries = validate_nested_archive(
        files["builder-runtime/server/core.jar"],
        f"{path.name}!builder-runtime/server/core.jar",
        forbidden_hashes,
        True,
    )
    if not REQUIRED_SERVER_ENTRIES.issubset(server_entries):
        fail(f"Server runtime is missing required World Builder classes: {path.name}")
    tool_entries = validate_nested_archive(
        files["builder-runtime/launcher/world-builder-tools.jar"],
        f"{path.name}!builder-runtime/launcher/world-builder-tools.jar",
        forbidden_hashes,
        True,
    )
    if not REQUIRED_TOOL_ENTRIES.issubset(tool_entries):
        fail(f"Tool runtime is missing required adaptive classes: {path.name}")
    with zipfile.ZipFile(
        io.BytesIO(files["builder-runtime/launcher/world-builder-tools.jar"])
    ) as tools:
        if tools.read(TOOL_RUNTIME_ALLOWLIST_ENTRY) != allowlist_bytes:
            fail(
                "Tool runtime embedded allowlist differs from the exact release "
                f"allowlist: {path.name}"
            )

    for relative, data in files.items():
        if relative.endswith((".jar", ".zip")) and relative not in {
            "builder-runtime/Client_Base/Open_RSC_Client.jar",
            "builder-runtime/server/core.jar",
            "builder-runtime/launcher/world-builder-tools.jar",
        }:
            validate_nested_archive(
                data, f"{path.name}!{relative}", forbidden_hashes, True
            )

    validate_seed(
        files["builder-runtime/server/inc/sqlite/world_builder_seed.db"], path.name
    )
    for relative, expected_data in copied_files.items():
        if files.get(relative) != expected_data:
            fail(
                "Candidate copied file differs from its exact locked source "
                f"({path.name}): {relative}"
            )
    return {
        "platform": platform,
        "fileName": path.name,
        "sha256": digest(archive_data),
        "archiveByteSize": len(archive_data),
        "expandedByteSize": sum(info.file_size for info in infos),
        "manifestSha256": digest(files[MANIFEST_NAME]),
        "manifestedFileCount": len(manifest),
        "reviewedJreInventorySha256": runtime_inventory_digest(jre_inventory),
        "reviewedJreFileCount": len(jre_inventory["files"]),
        "reviewedJreDirectoryCount": len(jre_inventory["directories"]),
    }


def parse_arguments(arguments: Iterable[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Inspect real World Builder 2 archives outside both source trees."
    )
    parser.add_argument("--source-root", required=True, type=Path)
    parser.add_argument("--core-framework", required=True, type=Path)
    parser.add_argument("--linux-jre", required=True, type=Path)
    parser.add_argument("--windows-jre", required=True, type=Path)
    parser.add_argument("--version", required=True)
    parser.add_argument("--linux-archive", required=True, type=Path)
    parser.add_argument("--windows-archive", required=True, type=Path)
    parser.add_argument("--checksums", required=True, type=Path)
    return parser.parse_args(list(arguments))


def main(arguments: Iterable[str]) -> int:
    options = parse_arguments(arguments)
    if not VERSION_PATTERN.fullmatch(options.version):
        fail("--version must be canonical semantic v2 release syntax")
    source_root = options.source_root.resolve(strict=True)
    core_root = options.core_framework.resolve(strict=True)
    if source_root == core_root or is_within(core_root, source_root) or is_within(source_root, core_root):
        fail("World Editor and locked runtime source trees must be separate")
    source_commit = validate_source_checkout(source_root)
    core_commit = read_locked_core_commit(source_root)
    validate_core_checkout(core_root, core_commit)

    linux_jre = external_runtime_directory(
        options.linux_jre, source_root, core_root, "Linux"
    )
    windows_jre = external_runtime_directory(
        options.windows_jre, source_root, core_root, "Windows"
    )
    if (
        linux_jre == windows_jre
        or os.path.samefile(linux_jre, windows_jre)
        or is_within(linux_jre, windows_jre)
        or is_within(windows_jre, linux_jre)
    ):
        fail("Reviewed Linux and Windows JRE inputs must be separate trees")

    linux = external_regular_file(options.linux_archive, source_root, core_root)
    windows = external_regular_file(options.windows_archive, source_root, core_root)
    checksums = external_regular_file(options.checksums, source_root, core_root)
    for artifact in (linux, windows, checksums):
        if is_within(artifact, linux_jre) or is_within(artifact, windows_jre):
            fail("Candidate artifacts and checksums must be outside reviewed JRE trees")
    linux_jre_inventory = inventory_runtime_tree(linux_jre, "Linux")
    windows_jre_inventory = inventory_runtime_tree(windows_jre, "Windows")
    linux_data, linux_identity = read_stable_external(linux, MAX_ARCHIVE_BYTES)
    windows_data, windows_identity = read_stable_external(windows, MAX_ARCHIVE_BYTES)
    checksum_data, checksum_identity = read_stable_external(
        checksums, MAX_CHECKSUM_BYTES
    )
    expected_names = {
        f"{PRODUCT_ID}-{options.version.removeprefix('v')}-linux-x64.zip",
        f"{PRODUCT_ID}-{options.version.removeprefix('v')}-windows-x64.zip",
    }
    if {linux.name, windows.name} != expected_names:
        fail("Candidate archive names do not exactly match the version and platforms")
    checksum_entries = parse_checksums(checksum_data)
    if set(checksum_entries) != expected_names:
        fail("SHA256SUMS.txt must contain exactly the Linux and Windows candidates")
    for archive_path, archive_data in (
        (linux, linux_data),
        (windows, windows_data),
    ):
        if digest(archive_data) != checksum_entries[archive_path.name]:
            fail(f"Outer candidate checksum mismatch: {archive_path.name}")

    (
        allowlist_bytes,
        allowed_runtime,
        runtime_sources,
        schemas,
    ) = parse_runtime_allowlist(source_root, core_root)
    forbidden_hashes = forbidden_core_hashes(core_root)
    copied_files = copied_file_expectations(
        source_root,
        core_root,
        options.version,
        source_commit,
        core_commit,
        runtime_sources,
        schemas,
    )
    artifacts = [
        validate_archive(
            linux,
            linux_data,
            "linux",
            options.version,
            source_commit,
            core_commit,
            allowlist_bytes,
            allowed_runtime,
            forbidden_hashes,
            copied_files,
            linux_jre_inventory,
        ),
        validate_archive(
            windows,
            windows_data,
            "windows",
            options.version,
            source_commit,
            core_commit,
            allowlist_bytes,
            allowed_runtime,
            forbidden_hashes,
            copied_files,
            windows_jre_inventory,
        ),
    ]
    if inventory_runtime_tree(linux_jre, "Linux") != linux_jre_inventory:
        fail("Reviewed Linux JRE input changed during candidate inspection")
    if inventory_runtime_tree(windows_jre, "Windows") != windows_jre_inventory:
        fail("Reviewed Windows JRE input changed during candidate inspection")
    reloaded_allowlist = parse_runtime_allowlist(source_root, core_root)
    if reloaded_allowlist[:3] != (
        allowlist_bytes,
        allowed_runtime,
        runtime_sources,
    ) or reloaded_allowlist[3] != schemas:
        fail("World Editor release inputs changed during candidate inspection")
    reloaded_copies = copied_file_expectations(
        source_root,
        core_root,
        options.version,
        source_commit,
        core_commit,
        runtime_sources,
        schemas,
    )
    if reloaded_copies != copied_files or forbidden_core_hashes(core_root) != forbidden_hashes:
        fail("Exact source or forbidden-world evidence changed during inspection")
    if validate_source_checkout(source_root) != source_commit:
        fail("World Editor source commit changed during candidate inspection")
    validate_core_checkout(core_root, core_commit)

    evidence = {
        "schemaVersion": 1,
        "recordType": "world-builder-v2-candidate-archive-inspection",
        "status": "automated-archive-inspection-passed",
        "releaseReady": False,
        "releaseGateChanged": False,
        "version": options.version,
        "sourceCommit": source_commit,
        "coreSourceCommit": core_commit,
        "checksumsFile": checksums.name,
        "checksumsSha256": digest(checksum_data),
        "inspectorSha256": digest(Path(__file__).resolve().read_bytes()),
        "artifacts": artifacts,
        "assertions": [
            "clean-published-source",
            "clean-exact-locked-runtime",
            "external-artifact-location",
            "outer-checksums",
            "single-safe-root",
            "exact-application-allowlist",
            "exhaustive-inner-manifest",
            "content-neutral-world-and-creator-scan",
            "empty-builder-database-seed",
            "dual-platform-jre17-metadata",
            "exact-reviewed-dual-platform-jre-inventory-bytes-and-modes",
            "linux-production-launcher-modes",
            "production-runtime-marker-and-capabilities",
        ],
        "pendingEvidence": [
            "owner-native-layered-edit-save-reopen",
            "owner-native-standalone-edit-save-reopen",
            "owner-software-and-opengl-visual-review",
            "disposable-target-import-undo-recovery",
            "manager-candidate-acceptance",
        ],
    }
    require_external_unchanged(
        linux, linux_data, linux_identity, MAX_ARCHIVE_BYTES
    )
    require_external_unchanged(
        windows, windows_data, windows_identity, MAX_ARCHIVE_BYTES
    )
    require_external_unchanged(
        checksums, checksum_data, checksum_identity, MAX_CHECKSUM_BYTES
    )
    final_copies = copied_file_expectations(
        source_root,
        core_root,
        options.version,
        source_commit,
        core_commit,
        runtime_sources,
        schemas,
    )
    if final_copies != copied_files:
        fail("Exact source inputs changed after candidate inspection")
    if inventory_runtime_tree(linux_jre, "Linux") != linux_jre_inventory:
        fail("Reviewed Linux JRE input changed after candidate inspection")
    if inventory_runtime_tree(windows_jre, "Windows") != windows_jre_inventory:
        fail("Reviewed Windows JRE input changed after candidate inspection")
    if validate_source_checkout(source_root) != source_commit:
        fail("World Editor source commit changed after candidate inspection")
    validate_core_checkout(core_root, core_commit)
    json.dump(evidence, sys.stdout, indent=2, sort_keys=True)
    sys.stdout.write("\n")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main(sys.argv[1:]))
    except CandidateError as error:
        print(f"FAIL: {error}", file=sys.stderr)
        raise SystemExit(1)
    except (OSError, UnicodeError, zipfile.BadZipFile) as error:
        print(f"FAIL: Candidate inspection could not complete safely: {error}", file=sys.stderr)
        raise SystemExit(1)
