"""Exact selected Base packaging inputs; no target or archive code is executed."""
import hashlib
import json
from pathlib import Path, PurePosixPath
import stat


def read_file(root, relative):
    parts = PurePosixPath(relative).parts
    if not parts or PurePosixPath(relative).is_absolute() or any(p in (".", "..") for p in parts):
        raise ValueError("Unsafe selected Base source path: " + relative)
    path = root / relative
    before = path.stat(follow_symlinks=False)
    if (path.resolve(strict=True) != path.absolute() or not stat.S_ISREG(before.st_mode)
            or before.st_nlink != 1 or before.st_size > 512 * 1024 * 1024
            or before.st_mode & 0o7000):
        raise ValueError("Linked or unsafe selected Base input: " + relative)
    data = path.read_bytes()
    after = path.stat(follow_symlinks=False)
    # Reading may legitimately advance atime. Bind identity/content metadata,
    # matching the independent inspector's exact-source read contract.
    stable_fields = ("st_dev", "st_ino", "st_mode", "st_nlink", "st_size", "st_mtime_ns", "st_ctime_ns")
    if (any(getattr(before, field) != getattr(after, field) for field in stable_fields)
            or len(data) != before.st_size):
        raise ValueError("Selected Base input changed while reading: " + relative)
    return data, stat.S_IMODE(before.st_mode)


def selected_base_files(provider):
    """Resolve with the pinned provider's existing read-only contract resolver.

    This is trusted build-source code, never code extracted from a candidate or
    a historical target. Compile directly to avoid writing a provider pycache.
    Java's exporter independently re-resolves the relocated staging tree.
    """
    provider = Path(provider).absolute()
    script = "scripts/current-platform-composition.py"
    resolver_bytes, _ = read_file(provider, script)
    namespace = {"__file__": str(provider / script), "__name__": "selected_base_packaging"}
    exec(compile(resolver_bytes, str(provider / script), "exec"), namespace)
    catalog = namespace["Catalog"](provider / "current-platform")
    namespace["verify_schema_bindings"](catalog)
    identity = namespace["resolve_composition"](catalog, "current-base-v1", [], provider)
    identity_source = "output/current-platform/current-base-v1/composition-identity.json"
    identity_file = read_file(provider, identity_source)
    if (json.loads(identity_file[0]) != identity or identity["installable"] is not True
            or identity["moduleSet"]):
        raise ValueError("Selected Base identity differs from its exact built composition")
    spec = catalog.bundle_specs["current-base-v1"][1]
    inventory = {row["bundlePath"]: row for row in identity["bundleInventory"]}
    files = {}

    def add(relative, value):
        for existing in files:
            a, b = existing.casefold(), relative.casefold()
            if a == b and (existing != relative or files[existing] != value):
                raise ValueError("Conflicting selected Base file: " + relative)
            if a.startswith(b + "/") or b.startswith(a + "/"):
                raise ValueError("Conflicting selected Base path: " + relative)
        files[relative] = value

    for artifact in spec["artifacts"]:
        relative = artifact["sourcePath"]
        value = read_file(provider, relative)
        record = inventory[artifact["bundlePath"]]
        if (hashlib.sha256(value[0]).hexdigest() != record["sha256"]
                or len(value[0]) != record["size"] or format(value[1], "04o") != record["mode"]):
            raise ValueError("Selected Base artifact changed: " + relative)
        add(relative, value)
    for schema in catalog.platform["schemaContracts"]:
        relative = "current-platform/" + schema["relativePath"]
        value = read_file(provider, relative)
        if hashlib.sha256(value[0]).hexdigest() != schema["sha256"]:
            raise ValueError("Selected Base schema changed: " + relative)
        add(relative, value)
    add("current-platform/composition-identity.json", identity_file)
    if read_file(provider, script)[0] != resolver_bytes:
        raise ValueError("Selected Base resolver changed during inspection")
    return files
