# World Builder 2 Project-Local Custom Content Bundles

## Contract status

`project-local-custom-content-v1` is the strict Editor/runtime boundary for
declarative target-owned content. Its checked-in schema is
`tools/world-builder/schema/project-content-bundle-v1.schema.json`.

The bundle is general. No ID, file, hash, or behavior belonging to one target
is part of the application release or this contract. NPC ID 846 is an
acceptance fixture only.

## Project paths

Target-backed preparation creates only these UUID-project paths:

```text
source/content-bundle/
  manifest.json
  files/
    server/conf/server/defs/...
    client/Cache/video/...
working/content-bundle/
  manifest.json
  files/...
```

`source/content-bundle/` is immutable evidence. `working/content-bundle/` is
the versioned runtime input and future creator-content authoring boundary.
Neither tree is stored in a World Builder release. Discovery and preparation
read the selected target, but never write it.

The isolated client and server receive the same exact bundle identity through
these Builder-only launch properties:

| Property | Value |
| --- | --- |
| `openrsc.worldBuilderContentBundle` | absolute `working/content-bundle/` root |
| `openrsc.worldBuilderContentCapabilityId` | `project-local-custom-content-v1` |
| `openrsc.worldBuilderContentBundleSha256` | manifest-bound bundle fingerprint |
| `openrsc.worldBuilderContentDefinitionSha256` | definition/catalog fingerprint |
| `openrsc.worldBuilderContentAssetSha256` | client-asset fingerprint |

Both processes also receive the existing
`openrsc.worldBuilderDefinitionId`,
`openrsc.worldBuilderDefinitionSha256`,
`openrsc.worldBuilderAssetId`, and
`openrsc.worldBuilderAssetSha256` bindings. Server evidence paths end in
`EvidencePath`; client evidence paths end in `EvidenceFile`. The bundle path
is operational metadata, never part of a fingerprint. A runtime that does not
advertise and enforce `project-local-custom-content-v1` must refuse a nonempty
target-adopted bundle before world entry.

## Closed content surface

Version 1 accepts only these declarative definition roles:

- `definition.tile` (`TileDef.xml`)
- `definition.boundary` (`DoorDef.xml`)
- `definition.scenery` (`GameObjectDef.xml`)
- base, custom, world, and patch roles for NPC JSON definitions
- base, custom, world, and patch roles for item JSON definitions

It accepts only these client asset roles:

- `asset.library`
- `asset.model`
- `asset.sprite.authentic`
- `asset.sprite.custom`
- bounded, directly contained `asset.spritepack` archives

No JAR, class, script, plugin, configuration, database, credential, symlink,
device, socket, executable permission, or target-supplied command is accepted.
Unknown definition or asset files are blockers; they are never approximated or
silently omitted.

Every role has a compiled runtime destination. A manifest cannot select an
arbitrary destination. Files live below `files/<runtimeRelativePath>`, where
the client runtime root is named `client/`. Bundle and runtime paths use
portable forward slashes and are checked for traversal, aliases, Unicode/case
collisions, Windows-invalid names, and exact role/path agreement.

## Catalog and closure

The embedded `definitionCatalog` is derived from the adopted definition bytes,
not the packaged neutral catalog. It carries sorted unique IDs for tiles,
boundaries, scenery, NPCs, and ground items. `catalogSha256` is SHA-256 over
the canonical catalog after temporarily replacing that field with 64 zeroes.

Exactly five sorted family bindings cover `floor`, `ground-item`, `npc`,
`scenery`, and `wall`. Each names all definition and client-asset roles needed
to represent that family. Executable validation requires every referenced role
to exist, every definition role to be used by exactly its applicable family,
and every asset record to participate in at least one family. It also parses
each definition source and checks that the derived ID inventory is exact.

Client archives are bounded and inspected structurally. Links, special entries,
unsafe names or modes, duplicate and case-folded names, expansion outside the
archive limits, malformed containers, and unsupported compression are fatal.
Opaque asset payloads are preserved byte-for-byte; they are never executed.
Version 1 consumes the existing target archive files listed above. It is not a
loose-PNG or loose-OB3 interchange format, and a runtime must not reinterpret
the bundle as one. A future creator-facing loose-file importer requires its
own versioned ingestion contract and must compile to this exact closed runtime
surface.

## Canonical compatibility fixture

The compact bundle at
`tests/fixtures/project-content-bundle-v1/bundle/` is the cross-repository
compatibility oracle. It contains all 16 required roles in their compiled
runtime paths, raw representative definition files, and existing-format raw,
ZIP, and gzip client archives. Its authoring catalog includes the acceptance
IDs floor 31, wall 219, scenery 59, NPC 846, and ground item 9000. None of
those IDs is part of the general contract.

Generate an independent copy or verify the checked-in bytes with:

```bash
python3 scripts/generate-project-content-bundle-v1-fixture.py /empty/output/bundle
python3 scripts/generate-project-content-bundle-v1-fixture.py \
  --check tests/fixtures/project-content-bundle-v1/bundle
```

The generator is deterministic and contains the fingerprint algorithm in a
small language-neutral form. Runtime consumers should copy the fixture from a
published Editor commit or independently mirror its exact bytes and expected
fingerprints; they do not need access to an Editor worktree.

## Fingerprints

All JSON is canonical UTF-8 with no host path or timestamp.

- `definitionFingerprintSha256` is SHA-256 of the ASCII domain
  `world-builder-project-content-definitions-v1\n`, followed by each
  definition record in canonical runtime-path order as
  `role\0runtimeRelativePath\0size\0sha256\n`, followed by the catalog hash.
- `assetFingerprintSha256` uses domain
  `world-builder-project-content-assets-v1\n` and the same record encoding for
  asset records.
- `bundleFingerprintSha256` is SHA-256 of domain
  `world-builder-project-content-bundle-v1\n` followed by the canonical
  manifest bytes after temporarily replacing this field with 64 zeroes. File
  bytes are bound by the exact inventory hashes already in that manifest.

Readers reject noncanonical ordering, duplicate roles (except
`asset.spritepack`), duplicate or case-folded paths, inconsistent hashes,
unreferenced files, unknown keys, and unknown schema/capability versions.

## Safety and lifecycle

Capture is one read-only, two-pass target transaction. It inventories bounded
known paths, copies into a unique project stage, re-reads every target file,
validates copied bytes and semantic closure, then publishes only as part of the
existing atomic UUID-project transaction. Failure publishes no project and
leaves target and existing projects byte-identical.

The immutable source snapshot inventories the complete source bundle.
Preparation copies it into `working/content-bundle/`, reopens and verifies the
copy, and launches only after both runtime sides bind the same catalog and
bundle fingerprints. Descriptor-backed material-free projects retain their
released strict behavior; standalone projects use the content-neutral default
catalog until a separately versioned creator-ingest feature is applied.
