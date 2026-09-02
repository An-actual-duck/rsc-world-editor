# World Builder 2 Project-Local Custom Content Bundles

## Contract status

`project-local-custom-content-v3` is the latest strict Editor/runtime boundary
when a target supplies authoritative NPC animation definitions not already
guaranteed by the packaged runtime. Its checked-in schemas are
`tools/world-builder/schema/project-content-bundle-v3.schema.json` and
`tools/world-builder/schema/npc-animation-registry-v1.schema.json`. The Editor
continues to read and produce v2 for targets needing only target-owned item
visuals, and v1 when every target ground item is an exact verified vanilla
definition already supplied by the packaged runtime. Material-free projects
continue to launch without a content bundle.

The bundle is general. No custom NPC, item, or scenery identity belonging to
one target is part of the application release or this contract. Only the
immutable vanilla registries may be recognized as a reuse baseline; custom
content is discovered from the selected target even when its numeric ID happens
to collide with content in the packaged runtime. Floor 31, wall 219,
scenery 59, NPC 846, and items 9000 through 9002 are fixtures only.

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
| `openrsc.worldBuilderContentCapabilityId` | exact v1, v2, or v3 manifest capability |
| `openrsc.worldBuilderContentBundleSha256` | manifest-bound bundle fingerprint |
| `openrsc.worldBuilderContentDefinitionSha256` | definition/catalog fingerprint |
| `openrsc.worldBuilderContentAssetSha256` | client-asset fingerprint |
| `openrsc.worldBuilderContentItemVisualSha256` | v2/v3 item-visual fingerprint; 64 zeroes for v1 |

Both processes also receive the existing
`openrsc.worldBuilderDefinitionId`,
`openrsc.worldBuilderDefinitionSha256`,
`openrsc.worldBuilderAssetId`, and
`openrsc.worldBuilderAssetSha256` bindings. Server evidence paths end in
`EvidencePath`; client evidence paths end in `EvidenceFile`. The bundle path
is operational metadata, never part of a fingerprint. A runtime that does not
advertise and enforce the exact manifest capability must refuse a nonempty
target-adopted bundle before world entry.

## Closed content surface

Both versions accept only these declarative definition roles:

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

Version 2 also preserves static producer evidence at
`server/conf/world-builder/item-visuals-v1.json` as role
`metadata.item-visuals`. It is strict JSON with manifest type
`world-builder-item-visual-evidence`, schema version 1, and one `itemVisuals`
array. It is data, never executable target code.

Version 3 additionally preserves `metadata.npc-animations` at
`server/conf/world-builder/npc-animations-v1.json`. The registry has manifest
type `world-builder-npc-animation-registry`, schema version 1, and sorted unique
records that bind each project animation ID to its renderer semantics and exact
custom/authentic sprite evidence. It too is inert data.

Discovery inventories and validates an existing registry from an already
upgraded target, so later detect/edit/import cycles retain its target-owned
custom animation identities without consulting same-numbered packaged custom
animations.

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
boundaries, scenery, NPCs, and ground items. Floors and boundaries use their
one-byte raw domain `0..254`; raw value 255 is reserved. Scenery, NPC,
ground-item, and sprite IDs use the bounded runtime domain `0..65535`.
`catalogSha256` is SHA-256 over
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
Version 2 named sprite mappings use the runtime's GZIP OSAR format and media
type `application/gzip`: a bounded sequence of named subspaces and entries,
each with valid type/layer metadata, a nonempty frame set, palette, dimensions,
shift and bounds metadata, and complete indexed pixels. Parsing consumes the
archive exactly and never loads or executes target code.
Opaque asset payloads are preserved byte-for-byte; they are never executed.
Version 1 consumes the existing target archive files listed above. It is not a
loose-PNG or loose-OB3 interchange format, and a runtime must not reinterpret
the bundle as one. A future creator-facing loose-file importer requires its
own versioned ingestion contract and must compile to this exact closed runtime
surface.

## Authoritative item visuals

The Editor derives the target's effective item registry from its selected base,
custom, patch, and world layers and derives the packaged comparison registry
from exact verified definition bytes. An item may reuse a packaged visual only
when its ID is within the immutable vanilla range and its base/custom identity
equals the packaged definition at that ID. Gameplay-only patch and world
overlays retain that visual, matching the client runtime's overlay behavior;
an overlay that explicitly declares visual fields remains target-owned. Every
non-vanilla item is target-owned, regardless of whether the packaged runtime
happens to use the same number for unrelated custom content. A changed or
absent same-ID vanilla base/custom definition is target-owned as well. An empty
target-owned set produces bundle v1. A nonempty set requires static item-visual
evidence covering that set exactly—no missing, duplicate, or unknown records.

Every v2 `itemVisuals` record has exactly these fields:

| Field | Contract |
| --- | --- |
| `itemId` | unique ascending integer `0..65535`; one target-owned definition |
| `authenticSpriteId` | authentic archive sprite ID `0..65535`, otherwise `null` |
| `customSpriteAssetRole` | `asset.sprite.custom` or `asset.spritepack`, otherwise `null` |
| `customSpriteSubspace` | portable archive subspace for a custom mapping, otherwise `null` |
| `customSpriteEntry` | portable entry below that subspace, otherwise `null` |
| `pictureMask` | authoritative signed 32-bit recolor mask |
| `blueMask` | authoritative signed 32-bit recolor mask |

Exactly one mapping form is allowed. An authentic mapping supplies only
`authenticSpriteId`; a custom mapping supplies the complete
role/subspace/entry triple. The Editor never derives `spriteId` from `itemId`,
never assumes `items:<itemId>`, and never executes a target client. Named
mappings require a structurally readable GZIP OSAR container and the exact
case-sensitive `<subspace>/<entry>` pair used by the runtime Unpacker. The same
case-folded pair cannot exist in both custom and spritepack archives when it is
declared, because its runtime role would be ambiguous after both archives are
loaded. Malformed or duplicate evidence, missing evidence or archive entries,
and incomplete closure are precise read-only discovery/conversion blockers.

### Project-local migration

Portable server discovery, explicit `world-builder-provider/` packages, and
the desktop guided-import flow are documented in
[`WORLD-BUILDER-2-PORTABLE-ITEM-PROVIDERS.md`](WORLD-BUILDER-2-PORTABLE-ITEM-PROVIDERS.md).
Those neutral provider inputs are copied into the World Builder installation;
they never authorize a write to the selected server.

When a target-backed packed project has target-owned ground-item IDs but no
`item-visuals-v1.json`, project creation does not ask the operator to add one to
the server. After the complete target evidence has been copied into the unique
unpublished UUID stage, the Editor first looks for complete visual fields on the
captured item definitions. A numeric `sprite` or `authenticSpriteId` is an
authentic mapping. A portable `sprite` location such as `items/0` is accepted
only when that exact case-sensitive entry exists in exactly one of the captured
custom or spritepack OSAR archives. Both signed `pictureMask` and `blueMask`
must be present. A complete nested `worldBuilderItemVisual` record uses the same
fields as the evidence schema. Partial, contradictory, missing, malformed, or
archive-ambiguous data is never guessed.

Unresolved IDs can be supplied during creation with
`--item-visual-mappings <world-builder-provider/item-visuals.json>`, or by
choosing that file in the desktop source dialog. The neutral provider layout is:

```text
world-builder-provider/
  item-visuals.json
  assets/
    Authentic_Sprites.orsc
    Custom_Sprites.osar
    spritepacks/
    external-items/
```

The producer contract has `schemaVersion: 1`, `manifestType:
"world-builder-item-visual-mapping"`, and unique ascending `itemVisuals` records.
Each record binds `itemId` and `name` to `logicalSpriteLocation`, `sourceRole`, a
portable `sourceAsset`, its lowercase `sourceAssetSha256`, exact selectors,
signed `pictureMask`/`blueMask`, and (for external PNGs) the repeated path/hash
plus bounded width and height. The exact schema is
`tools/world-builder/schema/item-visual-mapping-v1.schema.json`.

A provider may contain a complete catalog, including packaged or unrelated
items. Creation deterministically selects only the target's required
target-owned IDs. Authentic archives, custom OSARs, any selected spritepack,
and external RGB PNGs are hash-checked without loading target JARs. Custom,
spritepack, and external frames are normalized into a generated project-local
custom OSAR subspace; authentic records retain their exact archive ID and bind
the selected authentic archive. Only selected authentic entries are merged into
the captured target archive copy; unrelated authentic entries are retained.

Provider input is resilient by design. A missing manifest, malformed manifest,
unknown role, unsafe path, bad selector, missing/hash-mismatched asset, unreadable
frame, unresolved record, or newly encountered item ID is never executed and
never prevents project creation or launch. Unsafe content is not opened. Each
affected required ID receives a deterministic one-frame placeholder while its
ID/name association is retained. Sorted actionable results are written to
`diagnostics/item-visual-provider-warnings.json`. Producer/schema violations
remain visible there; they are not silently accepted as valid visuals.

The Editor writes the canonical runtime-compatible
`world-builder-item-visual-evidence` document
only to `source/content-bundle/files/server/conf/world-builder/` in project
staging. It is not added to `source/original` and no selected-server byte is
changed. Full bundle validation, archive-entry closure, target revalidation,
and atomic publication still run afterward. Cancellation or any validation
failure removes the unpublished stage and publishes no registry/project state.

### Sparse and unresolved NPC definitions

OpenRSC NPC base/custom JSON is loaded as one sequential registry even when a
record contains an `id` field. A placement or override can therefore name an ID
that is not actually backed by the combined base/custom row count. World
Builder no longer blocks project creation in that case. It extends only the
project-local copied custom definition file through the highest required ID,
uses deterministic nonaggressive placeholders for unresolved IDs, retains the
exact placement, and writes sorted diagnostics to
`diagnostics/npc-definition-provider-warnings.json`. The selected server and
immutable source copy are unchanged.

An exact neutral provider may place `npc-definitions-v1.json` beside the
selected item-visual manifest. Its contract is `schemaVersion: 1`,
`manifestType: "world-builder-npc-definition-mapping"`, and strictly ascending
records containing `npcId`, `name`, and one complete declarative NPC definition.
The exact data-only schema is
`tools/world-builder/schema/npc-definition-mapping-v1.schema.json`. In a
versioned provider package the file must be inventory-bound with role
`full-npc-definition-manifest`; its optional schema uses role
`npc-definition-schema`. No target JAR or class is consulted.

Provider definitions replace the placeholder at the exact sequential ID.
Unrepresented gaps remain inert placeholders so sparse IDs stay stable. Both
runtime consumers independently prove that every catalog NPC and every
patch/world override is backed by the resulting sequential registry before
authentication. The simple v1 NPC-definition mapping may reference only
animation IDs already available through the packaged runtime. Rich neutral
providers supply the additional animation evidence needed for bundle v3.

The consumer also accepts the richer neutral producer form with
`manifestType: "world-builder-npc-definitions"`. That form carries
`npcDefinitions`, the complete referenced `animationDefinitions`, exact sprite
archive bindings, and a sorted placed-extension selection. World Builder
validates the package inventory, archive hashes, NPC/definition identities,
selection closure, and every referenced animation before normalizing it to the
isolated Builder registry. Producer fields which have no Builder runtime
equivalent are never inferred from target code: server-only movement and combat
process fields are set to inert Builder values, while names, commands, visible
stats, animation IDs, recolor values, dimensions, and models are retained.
Malformed rich manifests continue through the existing explicit placeholder
and warning path rather than executing provider or target code.

For a successfully validated rich provider, the Editor emits bundle v3 and an
exact `world-builder-npc-animation-registry`. Each record retains the original
animation ID, name/category lookup, signed colour and gender fields,
combat/special-frame flags, required 15/18/27-frame shape, raw custom OSAR entry
hash, authentic base sprite ID, and every consecutive authentic frame hash.
The independent runtime validates the same registry and archives on both sides,
then installs definitions at their original (including sparse) client IDs
before project NPC definitions load. Existing v1/v2 projects remain readable.

A structurally valid rich manifest is not sufficient by itself. Its target
definition, placement, and sprite-archive bindings are compared with the
immutable copied source before normalization. A mismatch is a hard
`CAPABILITY_MISMATCH`, not a placeholder case: placeholders preserve genuinely
unresolved records, whereas a stale provider could assign valid-looking but
incorrect content to an existing numeric ID.

## Effective definition composition

Packed targets may retain historical `NpcDefsPatch<N>.json` and
`ItemDefsPatch<N>.json` files beside their current definitions. Their presence
does not activate them. Project creation reads the selected server
configuration and applies only the patch named by `based_config_data` when the
value is below 85 and that exact file exists. `NpcDefsMyWorld.json` and
`ItemDefsMyWorld.json` participate only when `want_myworld` is active. The
bundle keeps its established patch/world runtime roles, but inactive roles are
materialized as canonical empty overlays. Historical source bytes remain in
the immutable source snapshot.

Every target-backed project records the complete result in
`diagnostics/definition-composition-v1.json`, governed by
`tools/world-builder/schema/definition-composition-v1.schema.json`. For NPCs
and ground items the report lists every effective ID/name, every applied or
ignored replacement, its source role/path, the reason for its disposition, and
the hashes of all four effective definition roles. Duplicate IDs within an
active layer, noncanonical sequential NPC base/custom registries, and active
overlays that reference an undefined ID block project publication. Inactive
historical records—including orphaned or duplicated IDs—are reported as
ignored and cannot block or alter a modern composition. Cross-layer
replacement is allowed only through the declared deterministic precedence
order and is always reported.

Additional bounded `*NpcDefs.json` catalogs are discovered without a compiled
allowlist of NPC names or IDs. Their declared IDs determine the project-local
sequential registry, independent of filename order; sparse IDs receive inert
reserved records. A declared ID already occupied by the base, custom, or an
earlier supplemental definition is the only automatic-reassignment case. The
later definition receives the next deterministic free ID, existing spawns keep
their original meaning, and
`diagnostics/npc-definition-reconciliation-v1.json` records both definitions,
the reassignment, and every matching source spawn coordinate for manual review.
Targets remain read-only throughout discovery and project creation.

Descriptor-backed v1 targets that expose no ordinary gameplay configuration
retain the legacy supplied Patch18/world closure for compatibility. New packed
discovery with a readable configuration never guesses from filenames: a modern
profile such as `based_config_data: 85` leaves Patch18 inactive. This is why a
base NPC 22 named `Lesser Demon` can no longer be silently replaced by the
obsolete Patch18 `Demon`, while an explicitly historical profile can still
select that patch truthfully.

## Canonical compatibility fixture

The legacy bundle at `tests/fixtures/project-content-bundle-v1/bundle/` remains
the v1 compatibility oracle. The frozen successor oracle is
`tests/fixtures/project-content-bundle-v2/bundle/`. Its 17-role inventory
retains floor 31, wall 219, scenery 59, and NPC 846, and maps ordinary
beyond-packaged items through authentic sprite 417 and named `items/0` and
`GUI/0` custom OSAR entries with real frame pixels and nontrivial recolor masks.
Item 9000 is not special-cased.

Generate an independent copy or verify the checked-in bytes with:

```bash
python3 scripts/generate-project-content-bundle-v1-fixture.py /empty/output/bundle
python3 scripts/generate-project-content-bundle-v1-fixture.py \
  --check tests/fixtures/project-content-bundle-v1/bundle
python3 scripts/generate-project-content-bundle-v2-fixture.py \
  --check tests/fixtures/project-content-bundle-v2/bundle
```

The generator is deterministic and contains the fingerprint algorithm in a
small language-neutral form. Runtime consumers should copy the fixture from a
published Editor commit or independently mirror its exact bytes and expected
fingerprints; they do not need access to an Editor worktree.

## Fingerprints

All JSON is canonical UTF-8 with no host path or timestamp.

- `definitionFingerprintSha256` is SHA-256 of the ASCII domain
  `world-builder-project-content-definitions-v<bundle-version>\n`, followed by each
  definition record in canonical runtime-path order as
  `role\0runtimeRelativePath\0size\0sha256\n`, followed by the catalog hash.
- `assetFingerprintSha256` uses domain
  `world-builder-project-content-assets-v<bundle-version>\n` and the same record encoding for
  asset records.
- `itemVisualFingerprintSha256` in v2/v3 is SHA-256 of domain
  `world-builder-project-content-item-visuals-v1\n` followed by canonical
  `itemVisuals` JSON. V1 launch compatibility uses 64 zeroes.
- `bundleFingerprintSha256` is SHA-256 of domain
  `world-builder-project-content-bundle-v<bundle-version>\n` followed by the canonical
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
complete bytes, preserved evidence equality, archive-entry closure, and all
fingerprints. Both runtime sides receive the same capability and item-visual
fingerprint; v3 additionally requires the canonical NPC animation registry.
Working-copy drift blocks save/launch without changing immutable
source or the target. Descriptor-backed material-free projects retain their
released strict behavior; standalone projects use the content-neutral default
catalog until a separately versioned creator-ingest feature is applied.
