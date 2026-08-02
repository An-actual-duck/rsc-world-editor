# World Builder 2 Custom Wall and Floor Materials

## Document status

| Field | Value |
| --- | --- |
| Status | Approved design and implementation guide; implementation has not started |
| Approved | 2026-08-01 |
| Product | World Builder 2 only |
| Legacy v1 | Frozen and out of scope |
| Distribution decision | Server owners distribute the matching client and material pack to players |
| Authoring decision | Versioned safe presets; no raw definition flags |
| Image decision | PNG input, explicit crop/resize review, 64-by-64 default, 128-by-128 opt-in |

This document is the source of truth for the first custom-materials increment.
It is intentionally prescriptive so a future maintainer or AI session can
implement one bounded phase without rediscovering product decisions.

The words **MUST**, **MUST NOT**, **SHOULD**, and **MAY** are normative.

## AI execution guardrails

Before working on any phase:

1. Read `AGENTS.md` and run the preflight for the current checkout role.
2. Work on one focused topic branch and inspect the complete current code path.
3. Treat `.core-framework/` only as the clean detached checkout named by
   `core-framework.lock`. Do not develop in it, modify it, fetch in it, or use
   it as a source worktree.
4. Do not advance `core-framework.lock` unless the manager explicitly assigns
   an exact published Spoiled Milk commit in a separate dependency-update task.
5. Do not change `release/world-builder/`, the v1 updater, v1 identities, or v1
   schemas. World Builder 2 materials MUST NOT cross-update or migrate v1.
6. Preserve the source snapshot, offline-target, exact preview and
   confirmation, backup, receipt, verification, partial-failure rollback,
   changed-after-import, and no-force contracts.
7. Use temporary fixtures for every test. Never use or replace a real user
   workspace, server, client, export, backup, receipt, or material pack.
8. Add a new schema version instead of changing the meaning of a released
   schema. Parsers MUST reject unknown keys and unsupported versions.
9. Checkpoint meaningful progress. Do not open a release gate, tag, publish,
   merge, or call a feature READY until that phase's acceptance criteria pass.

Runtime support cannot be completed solely in this repository. Client,
server, protocol, and in-game editor changes MUST land in Spoiled Milk first.
Only a later, explicitly authorized manager task may select that exact commit
and advance the lock. Editing `.core-framework/` is never a substitute.

## Approved product scope

The first increment lets a nontechnical creator add an image to a World
Builder 2 project and use it as either or both of:

- a normal walkable floor material; and
- a normal opaque, solid, full-height wall material.

The creator chooses a name, reviews the exact transformed image, chooses floor,
wall, or both, and confirms before the project changes. World Builder allocates
all numeric IDs and stores them permanently. The creator never edits an XML
definition, archive, manifest, numeric ID, collision flag, or rendering flag.

The following are out of scope for the first increment:

- automatic material downloads from a server;
- hot reload while the client or isolated server is running;
- JPEG, GIF, WebP, SVG, or animated input;
- transparent or translucent pixels;
- animated textures, water, lava, emissive effects, or roof materials;
- passable, invisible, half-height, interactive, or two-sided wall variants;
- direct editing of definition fields or numeric IDs;
- automatic merging of unrelated material packs during target import; and
- conversion of a v1 workspace or release.

Reject an out-of-scope request with an actionable message. Do not silently
approximate it with a different material type.

## Current pipeline and constraints

These facts describe the reviewed pinned runtime. Reconfirm them against the
exact selected upstream revision before implementation; do not encode the
current counts as permanent limits.

### Images and rendering

- The remastered client currently reads the gzip OSAR archive
  `Client_Base/Cache/video/Custom_Sprites.osar`.
- Its `textures` subspace currently contains numeric entries `0` through `66`:
  32 images are 64 by 64 pixels and 35 are 128 by 128 pixels.
- The client loads textures at startup, converts them to the renderer's indexed
  representation, and shares that representation with the software and OpenGL
  paths.
- Existing legacy conversion treats exact black specially as transparency.
  New opaque custom materials MUST preserve black as black and MUST use the new
  alpha-aware material loader.
- The renderer supports the required 64-by-64 and 128-by-128 sizes. The preview
  MUST display the canonical post-crop, post-resize, post-palette image that the
  renderer will consume, not the unprocessed source PNG.

Custom materials MUST be loaded from a separate, versioned material-pack
directory. Do not rebuild or patch `Custom_Sprites.osar`.

### Map values and definitions

- A floor overlay stored in terrain is a one-based `TileDef` value. The current
  base catalog has 26 ordinary entries, and raw value `250` has special runtime
  behavior.
- A horizontal, vertical, or diagonal wall stored in terrain resolves through
  a one-based `DoorDef` value. The current base catalog has 214 entries.
- The relevant map fields are byte-sized except for diagonal-wall orientation
  encoding. The implementation MUST obtain the usable and reserved ID sets
  from the exact runtime capability contract rather than assuming the current
  free ranges.
- A floor definition controls both its visual texture and collision semantics.
  A wall definition controls height, front/back visuals, collision, visibility,
  and interaction semantics. An image alone is therefore not a portable map
  material.

The base client definition table, server definition table, texture catalog,
and capability descriptor MUST agree before authoring, launch, export, or
import. The current discovery fingerprint is not sufficient by itself: it
tracks `TileDef.xml` but does not fully bind `DoorDef.xml`, the base client
texture catalog, and the new material capability.

### Editor, workspace, and import

- The current editor exposes numeric floor controls and named wall-definition
  controls without a unified material thumbnail browser.
- A workspace has an immutable-by-contract `source/` snapshot and a mutable
  `working/` runtime. Custom material state belongs in the project and MUST NOT
  be added to the packaged application's replaceable files.
- The native signed-layered package schema has a strict inventory for terrain
  and placements. Arbitrary PNG files MUST NOT be inserted into that package.
- Existing layered export/import moves the complete authored package and uses
  fail-closed transactional import and undo. Materials must extend those
  transactions through a new versioned companion bundle.
- A World Builder release archive MUST NOT contain any user's inbox, normalized
  images, material manifest, export, backup, or receipt.

## Target creator workflow

### Folder convention

Each World Builder 2 workspace contains these durable creator-owned paths:

```text
workspace/
  material-inbox/
    floors/
    walls/
  material-ingest-receipts/
  working/
    world-builder-materials/
      manifest.json
      images/
        <lowercase-sha256>.png
```

`floors/` and `walls/` are input hints, not separate image formats. In the
review screen the creator MAY enable both safe presets for one image, so the
same canonical image and texture ID can serve a floor and a wall. Unknown
subdirectories are ignored with a visible warning; they are never scanned
recursively.

Input files remain creator-owned. Successful ingest MUST NOT delete, rename,
or overwrite them. A receipt records the source path and hash so an unchanged
file is not presented on every launch. Changing the bytes at the same path is
a new pending input. Removing an input file later does not remove an already
registered material.

Canonical exported paths use hashes or generated identifiers, never the input
file name. This avoids traversal, Unicode normalization, case-folding, reserved
Windows device-name, trailing-dot, and trailing-space problems.

### Review and confirmation

The packaged launcher scans the two inbox directories before starting either
the client or isolated server. If pending files exist, it opens a material
review window. There is no hot reload in the first increment.

For each valid PNG the review MUST show:

- source file name, detected format, byte size, dimensions, and SHA-256;
- an editable square crop, initially centered on the largest possible square;
- the selected canonical size, 64 by 64 by default or 128 by 128 by explicit
  opt-in;
- the exact normalized and palette-converted preview;
- the proposed display name and stable slug;
- Floor and Wall checkboxes seeded from the inbox directory;
- the numeric texture, floor-definition, and/or wall-definition IDs that will
  be committed; and
- warnings, capacity remaining, duplicate status, and any collision.

The review applies only after an explicit **Add material** confirmation. Cancel
leaves the working material pack byte-for-byte unchanged. A multi-file review
is one transaction: if any selected entry is invalid or the final write cannot
be verified, no material from that confirmation is committed.

After a successful commit, the launcher starts the isolated runtime with the
new immutable material manifest. If either process is already active, material
ingest refuses with instructions to close World Builder and start it again.

### PNG normalization contract

The first increment accepts a file only when all of these are true:

- its extension is `.png` case-insensitively and its detected content is PNG;
- it is at most 16 MiB, at most 4096 pixels on either axis, and at most
  16,777,216 decoded pixels;
- it decodes to one still image without malformed or trailing image data;
- every pixel is fully opaque; and
- the requested crop is nonempty and contained in the decoded image.

The normalizer MUST:

1. decode into a defined sRGB color space without applying platform-specific
   display settings;
2. apply the exact reviewed square crop;
3. resize to 64 by 64 or 128 by 128 using deterministic nearest-neighbor
   sampling;
4. reduce the result deterministically to at most 256 opaque colors;
5. encode a canonical PNG with no creator metadata or absolute path; and
6. reopen that PNG, verify its dimensions, opacity, palette, and hash, then use
   the reopened pixels for the final preview.

There is no silent crop, stretch, size choice, alpha removal, or format
conversion. Exact black remains opaque black. The manifest records the source
hash, crop rectangle, target size, normalizer version, canonical image hash,
and canonical pixel hash. Golden fixtures MUST prove byte and pixel behavior on
the supported Linux and Windows JDK.

The 128-by-128 option includes a clear memory/performance note. It is not
selected automatically from source dimensions.

### Names, duplicates, and replacement

The file stem seeds a display name. The creator may edit it before confirming.
The tool derives a bounded lowercase ASCII slug matching
`[a-z0-9][a-z0-9._-]{0,62}[a-z0-9]`, with a one-character slug also allowed.
Comparison uses Unicode normalization and case-folding before slug generation.

Collision handling is fail closed:

- Identical canonical pixels with the same selected preset are a verified
  duplicate. Reuse the existing image/definition and create a no-op receipt.
- Identical canonical pixels with a newly selected second preset reuse the
  existing texture ID and allocate only the missing definition ID.
- A slug already attached to different pixels blocks confirmation until the
  creator changes the name or explicitly chooses **Add as new material**.
- An input path whose bytes changed is never treated as an in-place update.
- Replacing an existing material is a separate explicit transaction. Its
  preview MUST list the number of affected floor and wall map values. It keeps
  the existing IDs, makes a new pack revision, and provides the same rollback
  guarantees. Silent overwrite is forbidden.

Removing a palette entry tombstones its IDs. Existing terrain remains
renderable, the manifest retains the old definition, and the IDs are never
recycled. A later cleanup/migration feature is out of scope.

## Safe preset contract

The material manifest stores named, versioned preset tokens instead of raw
definition fields:

| Token | Required behavior |
| --- | --- |
| `floor-walkable-opaque-v1` | Ordinary visible floor overlay, walkable, no damage or special movement behavior |
| `wall-solid-opaque-v1` | Ordinary visible full-height wall, same image on both faces, solid collision, no command or door interaction |

The exact upstream runtime translates each token into client and server
definition objects. Both processes advertise the same supported preset tokens
in their capability descriptors. Unknown tokens are fatal; they are never
mapped to a default.

The standalone tool does not expose or synthesize raw collision, visibility,
height, door-type, tile-value, command, animation, transparency, or face
fields. Adding a preset later requires a new token and tests on both client and
server.

## Companion material-pack contract

### Logical model

The signed-layered terrain package remains unchanged. A project with custom
materials carries a separate strict manifest with this logical content:

```text
schema identity and version
pack UUID and monotonically increasing revision
base catalog fingerprint
runtime capability identity
pack fingerprint
asset records
  generated asset ID
  display name and slug
  canonical image path, byte hash, pixel hash, dimensions
  source hash and reviewed transformation
  allocated texture ID
material records
  generated material ID
  asset ID
  preset token
  allocated floor-definition or wall-definition ID
tombstoned IDs
exact file inventory
```

The actual JSON schema MUST use exact keys, bounded strings and counts,
lowercase SHA-256 values, safe forward-slash relative paths, and an exact file
inventory. It MUST reject links, traversal, absolute paths, backslashes,
duplicate/case-folded paths, unknown files, unknown presets, inconsistent
hashes, and inconsistent ID references.

The pack fingerprint is a domain-separated SHA-256 over the canonical manifest
content and every inventoried canonical image. No workspace path, timestamp,
host name, or platform separator participates.

### Stable automatic ID allocation

The exact runtime exposes a versioned capability document containing:

- supported material-pack schema and preset versions;
- supported canonical image sizes;
- all reserved and usable texture IDs;
- all reserved and usable floor-definition IDs;
- all reserved and usable wall-definition IDs; and
- the base catalog fingerprint derived from the client texture catalog and the
  matching client/server definition catalogs.

The allocator MUST NOT hardcode `67`, `26`, `214`, `250`, or a presumed free
range. Those values describe the reviewed base only.

For a new asset, allocate the lowest usable texture ID that is neither reserved
by the capability nor present or tombstoned in the source/working pack. For a
new preset use, do the same in the matching definition-ID space. Persist the
allocation before any map can reference it. IDs never change because of file
renames, sorting, restart, export, import, or undo, and retired IDs are never
reused.

An image enabled for both presets has one texture ID, one floor-definition ID,
and one wall-definition ID. Capacity is checked for the complete confirmation
before any write. A capacity error reports the requested and remaining count
for each ID space and changes nothing.

### Base and source compatibility

Discovery must distinguish:

- the immutable base catalog and runtime capability fingerprint; and
- the optional active target material pack that forms part of the project's
  source state.

If the target already has an active valid pack, workspace preparation copies
it into both the immutable source snapshot and the mutable working material
directory. If no pack exists, the snapshot records its exact absence and the
working directory starts with a new empty pack. No file is taken from an
unverified player installation.

Every launch, export, import, and undo revalidates the immutable source
inventory and the full working pack. Import also rediscovers the target base
catalog and active pack. A different base fingerprint, changed source pack, ID
collision, unsupported capability, or missing expected absence refuses before
target mutation. There is no import-time ID remapping or force merge.

## Runtime and player distribution

The new runtime loads material packs at startup from these logical target
locations (final platform paths must use the existing repository path helpers):

```text
server/conf/server/data/world-builder-materials/
  active.json
  packs/<pack-fingerprint>/manifest.json
  packs/<pack-fingerprint>/images/<sha256>.png

Client_Base/Cache/video/world-builder-materials/
  active.json
  packs/<pack-fingerprint>/manifest.json
  packs/<pack-fingerprint>/images/<sha256>.png
```

`active.json` selects one exact immutable pack fingerprint. The pack directory
is complete and verified before the active descriptor changes. Only one pack
is active in the first increment.

The server owner is responsible for distributing a material-capable client and
the exact `Client_Base/Cache/video/world-builder-materials/` directory to every
player. World Builder does not upload, host, or download player assets.
Release and import documentation MUST say this explicitly.

At login, a material-capable client advertises its material protocol version,
base catalog fingerprint, and active pack fingerprint. A server whose world
uses an active custom pack refuses a missing, old, unsupported, or mismatched
client before world entry and reports all expected fingerprints in an
actionable message. A server without a custom pack remains compatible with the
material-capable client. The protocol never treats a missing value as the
empty matching pack.

The in-game editor reads the loaded manifest and displays separate Floor and
Wall thumbnail palettes. Each entry shows the creator name, preview, numeric
definition ID for diagnostics, and preset. Selecting an entry writes the
already allocated definition ID through the existing authoritative editor
protocol. The server remains authoritative and rejects undefined IDs or preset
catalog disagreement.

## Workspace, export, import, and undo

### Workspace rules

- `source/` remains immutable and fully inventoried, including the initial
  material pack or its absence.
- `working/world-builder-materials/` is the only authored canonical material
  state consumed by the isolated runtime.
- Inbox files and ingest receipts are durable convenience state but are not
  part of the runtime pack or an export.
- All updates use same-filesystem staging, exact reopen verification, and an
  atomic move where supported, with safe fallback and rollback.
- Launch and material review share the workspace lock. A pending terrain
  journal or active process blocks material mutation.

### Export format

Do not add PNGs to the native signed-layered package. Add
`export-manifest-v2.schema.json` for material-bearing layered exports. Keep
schema v1 parsing and output unchanged for material-free projects.

A v2 export contains:

```text
manifest.json
CHANGE-SUMMARY.txt
authored/layered-world/package/...
authored/world-builder-materials/manifest.json
authored/world-builder-materials/images/<sha256>.png
```

The v2 manifest retains all v1 layered provenance and inventories, then adds
the source and working material-pack states, base catalog/capability
fingerprints, and an exact material file inventory. A material change counts
as an export change even when terrain and placement bytes are unchanged. The
export carries the complete working material pack, including currently unused
or tombstoned definitions, so the project remains portable and old terrain
always resolves.

Existing v2 software that supports only export schema v1 must reject schema v2
as unsupported without changing a target. It must not ignore the companion
bundle.

### Import preview and apply

Import remains a single transaction covering the complete layered package,
the selected target configuration, the server material directory, and the
client material directory. The preview MUST include:

- exact target root and offline proof;
- source revision and base catalog/capability fingerprints;
- source and exported layered-package fingerprints;
- prior and new active material-pack fingerprints;
- every added, replaced, reused, and removed destination;
- every texture/floor/wall allocation and preset;
- canonical image sizes and hashes;
- backup and receipt display paths valid on Linux and Windows; and
- the player-distribution responsibility and exact client directory.

Apply still requires the exact confirmation `IMPORT`. Before mutation it
repeats discovery, offline leasing, source-snapshot verification, pack parsing,
capacity/collision validation, and preview equivalence. It backs up bytes and
exact prior absence, writes a pending receipt, stages and verifies every file,
installs immutable pack directories, switches active descriptors last,
reopens all destinations, and only then marks the receipt successful.

An injected failure after any write restores every old byte and prior absence,
including both active descriptors and any newly introduced pack directory.
The target and workspace must match their pre-apply hashes after rollback.
There is no partial material-only success and no force option.

### Undo

Add `import-receipt-v2.schema.json` for the expanded destination inventory;
never reinterpret receipt v1. Undo preview still requires the exact
confirmation `UNDO` to apply.

Undo first verifies that every installed layered, configuration, server-pack,
and client-pack path still matches the successful import receipt. If any path
changed, disappeared, appeared unexpectedly, or selects a different active
pack, undo refuses without mutation. Successful undo restores all prior bytes
and absence, verifies them, and records a rollback receipt. An injected undo
failure restores the complete post-import state.

## Backward compatibility

- Frozen v1 files, identity, updater, archives, and workflows do not change.
- A material-capable World Builder 2 release opens an existing material-free
  v2 workspace and emits the existing schema-v1 export when no materials are
  present.
- A material-free target continues to run without `active.json` or a material
  directory.
- Old v2 importers reject material-bearing schema v2 before mutation.
- New v2 importers continue to validate and import schema-v1 exports exactly as
  before.
- Material-bearing exports require the exact material runtime capability and
  matching base catalog. They are never downgraded, stripped, or remapped.
- The updater preserves all workspace material directories as durable state.
- Packaging never seeds a user material pack into a release archive.

## File-level implementation plan

Each phase is separately reviewable. File names for new classes are the
approved ownership split; adjust only if the existing package structure makes
one name misleading, and record the reason in the checkpoint.

### Phase 0: Freeze cross-repository contracts

Repository-owned changes:

- add `tools/world-builder/schema/material-capabilities-v1.schema.json`;
- add `tools/world-builder/schema/material-pack-v1.schema.json`;
- add canonical valid and invalid fixtures under a new
  `tests/fixtures/world-builder-materials/` directory;
- add a machine-readable normalization fixture with source, crop, output
  pixels, and hashes; and
- keep this guide synchronized with the exact schemas.

Tests MUST validate strict keys, safe paths, exact inventories, ID uniqueness,
preset tokens, fingerprints, malformed input, and cross-platform golden image
output. Stop here if client/server owners cannot accept the contract.

### Phase 1: Implement the runtime contract in Spoiled Milk

This is a separate upstream task, never work inside `.core-framework/`.
Likely integration points must be confirmed in the exact upstream checkout:

- client definition loading near
  `Client_Base/src/com/openrsc/client/entityhandling/EntityHandler.java`;
- client texture startup near `Client_Base/src/orsc/mudclient.java` and the
  renderer texture data;
- editor controls near
  `Client_Base/src/com/openrsc/interfaces/misc/WorldEditorInterface.java` and
  its definition catalog/browser helpers;
- server definitions near
  `server/src/com/openrsc/server/external/EntityHandler.java`;
- authoritative edits near the server `WorldEditorHandler`; and
- login capability validation near the client limitations/login packet path.

Prefer isolated runtime components such as `WorldBuilderMaterialCapabilities`,
`WorldBuilderMaterialCatalog`, and `WorldBuilderMaterialImageLoader` over
special cases spread through renderers. The loader reads already normalized
indexed PNGs and MUST NOT independently resize or requantize them.

Upstream tests cover both renderers, black pixels, 64/128 sizes, client/server
definition equality, safe preset semantics, bad manifests/images, ID
collisions, editor thumbnails/selections, protocol mismatch refusal, and a
material-free server. Publish and identify the exact tested upstream commit
before any lock update is considered.

### Phase 2: Adopt the exact upstream runtime

Manager-only, separately authorized work:

- select the exact published Spoiled Milk commit;
- advance `core-framework.lock` to only that commit;
- materialize a clean detached dependency checkout;
- verify required client/server capability artifacts and matching fingerprints;
- run the explicitly authorized synchronization/parity checks; and
- run the complete pinned build and test suite.

Do not mix standalone feature work into this phase. A dirty, wrong, missing, or
capability-mismatched dependency fails closed.

### Phase 3: Add strict standalone material models and normalization

Add under `tools/world-builder/src/com/openrsc/worldbuilder/`:

- `WorldBuilderMaterialCapabilities.java` for strict capability parsing;
- `WorldBuilderMaterialPack.java` for manifest, inventory, and fingerprint
  validation;
- `WorldBuilderMaterialNormalizer.java` for bounded deterministic PNG handling;
- `WorldBuilderMaterialAllocator.java` for stable append-only allocations;
- `WorldBuilderMaterialIngestor.java` for staged multi-file transactions; and
- `WorldBuilderMaterialReviewDialog.java` for the nontechnical preview flow.

Extend `WorldBuilderCli.java` with a testable non-launching material review
entry point, while packaged startup invokes the same model before supervision.
Keep all filesystem/model logic outside Swing so temporary-fixture tests are
headless and deterministic.

Add `tests/myworld/test-world-builder-materials.py` for normalization,
preview data, confirmation, cancel, deduplication, slug collision, replacement,
tombstones, capacity, unsafe files, injected write failure, and exact no-change
recovery.

### Phase 4: Integrate discovery, workspace preparation, and launch

Update:

- `WorldBuilderDiscovery.java` and `WorldBuilderDiscoveryResult.java` to bind
  both definition catalogs, the client base texture catalog, runtime
  capabilities, and the optional active target pack;
- `WorldBuilderRuntimePreparer.java` to create the inbox/receipt paths and copy
  a verified source material pack into source and working state;
- `WorldBuilderSourceSnapshot.java` and `WorldBuilderProjectSource.java` to
  inventory the pack or exact absence without weakening source protection;
- `WorldBuilderProcessSupervisor.java` to lock, review, verify, and then launch;
  and
- `WorldBuilderConfigWriter.java` only if the upstream optional-pack loader
  requires an explicit v2 setting.

Extend `test-world-builder-discovery.py`,
`test-world-builder-runtime-preparation.py`, and
`test-world-builder-supervision.py`. Cover first preparation with no pack,
existing valid pack, mismatched capability/catalog, source drift, unsafe paths,
cancel, active processes, and a failed ingest that leaves source, working, and
target bytes unchanged.

### Phase 5: Extend export, import, and undo transactionally

Add:

- `tools/world-builder/schema/export-manifest-v2.schema.json`;
- `tools/world-builder/schema/import-receipt-v2.schema.json`; and
- strict v2 models without modifying the meaning of v1 models.

Update:

- `WorldBuilderLayeredExporter.java` and `WorldBuilderExportManifest.java` for
  companion-pack export and change summaries;
- `WorldBuilderExportBundle.java` for exact mixed inventories;
- `WorldBuilderLayeredImportConfiguration.java` for canonical target material
  locations;
- `WorldBuilderImporter.java` for one preview/apply/rollback transaction across
  layered, server, and client paths;
- `WorldBuilderImportReceipt.java` for v2 backup and undo state; and
- `WorldBuilderCli.java` without changing `IMPORT`/`UNDO` confirmations.

Extend `test-world-builder-export.py` and
`test-world-builder-import.py`. Cover material-only export, mixed export,
schema-v1 compatibility, exact previews, apply, undo, source/workspace/target
preservation, client/server parity, changed-after-import refusal, and injected
failure after each meaningful write boundary. Include Windows-invalid
character and case-folded path fixtures.

### Phase 6: Package validation and operator documentation

Update only v2-owned paths:

- `scripts/package-world-builder-v2-release.sh` to require matching material
  capabilities and reject any durable material state in the archive;
- `release/world-builder-v2/README.txt` with creator ingest and server-owner
  player-distribution instructions;
- `release/world-builder-v2/ASSET-SOURCES.txt` only if the release introduces a
  repository-owned example asset; and
- `docs/ARCHITECTURE.md`, `docs/DEVELOPMENT.md`, and `docs/RELEASING.md` where
  the implemented contracts affect their existing guidance.

Extend `test-world-builder-v2-release.py` and
`test-world-builder-product-generations.py`. Prove the archive contains the
capability but no workspace/inbox/custom image, the dependency is the exact
clean lock, v1 is byte-for-byte outside the change, and player-distribution
wording is present.

This phase does not authorize opening `RELEASE-READY`, tagging, publishing, or
uploading an archive.

### Phase 7: Complete verification

Run all focused tests from the preceding phases, then:

```bash
git diff --check
./scripts/test.sh
```

Manual release-candidate verification MUST cover:

- owner-run packaged startup and visual review on the owner's native platform,
  plus code review and automated package/launcher validation for the other
  platform;
- a 64-by-64 floor, 128-by-128 wall, and one shared floor/wall image;
- exact preview/render appearance in software and OpenGL modes;
- save, close, reopen, export, dry-run import, `IMPORT`, dry-run undo, and
  `UNDO`;
- a material-free existing v2 workspace and export;
- matching player login plus missing, old, and mismatched client refusal; and
- distribution of the documented client directory into a clean compatible
  player installation.

Record exact commits, package hashes, test output, untested behavior, and the
owner's visual observations. AI sessions MUST ask the owner to perform visual
inspection rather than capture or judge screenshots themselves.

## Acceptance criteria

The feature is complete only when every statement below is true:

- [ ] An arbitrary-size, bounded opaque PNG cannot change a project until its
  crop, 64/128 size, normalized preview, presets, and allocated IDs are shown
  and explicitly confirmed.
- [ ] The preview pixels equal the loaded renderer pixels for both supported
  sizes and both renderers; opaque black never becomes transparent.
- [ ] A creator can add a safe floor, a safe wall, or both from one image
  without seeing or editing raw definition fields.
- [ ] IDs are allocated from the exact runtime capability, remain stable across
  restart/export/import/undo, and are never silently reused or remapped.
- [ ] Duplicate bytes, conflicting names, exhausted ID spaces, malformed PNGs,
  alpha, traversal, links, unknown files, and catalog mismatch fail exactly as
  documented and leave all protected state unchanged.
- [ ] Existing source materials are snapshotted; new materials are project
  state; no material is read from an unverified player installation.
- [ ] Export is self-contained and portable, while native layered package v1
  and material-free export schema v1 retain their current meaning.
- [ ] Import preview lists every server/client effect and remains offline,
  exact-confirmation, backup, receipt, verification, rollback, source-safe, and
  no-force.
- [ ] Undo refuses changed post-import state, restores exact old bytes/absence,
  and recovers the post-import state after an injected partial undo failure.
- [ ] A matching player client can enter; missing, unsupported, or mismatched
  clients are refused before world entry with actionable fingerprints.
- [ ] Documentation makes the server owner responsible for distributing the
  compatible client and exact material folder; no automatic download exists.
- [ ] A packaged release contains the capability and tools but no user material
  state, and its v1 release line is untouched.
- [ ] Focused, lint, full automated, Linux, Windows, software-renderer, and
  OpenGL checks are recorded for the exact candidate commit.

## Risk register

| Risk | Required mitigation |
| --- | --- |
| Wall or floor byte-ID exhaustion | Capability-driven preflight, full-transaction capacity check, tombstones, actionable remaining counts |
| Client/server visual or definition drift | One pack fingerprint, base catalog fingerprint, strict startup parsing, login handshake, renderer golden tests |
| PNG decompression or memory abuse | Byte, dimension, and decoded-pixel limits before allocation; bounded decode; malformed-data rejection |
| Preview differs from the game | Store the canonical palette image, reopen it for preview, prohibit runtime resizing/requantization, test both renderers |
| Existing target pack is overwritten | Snapshot exact source pack/absence, reject drift and unrelated packs, preview all paths, no-force import |
| Partial multi-root import or undo | Same-filesystem staging per destination, verified backups, pending receipt, active descriptors last, injected-failure tests |
| Windows/Linux path disagreement | Hash-based canonical paths, forward-slash manifests, case-fold collision checks, tests on both platforms |
| Upstream/standalone sequencing drift | Freeze schemas/fixtures first, upstream exact commit second, manager-authorized lock update third |
| Creator distributes the wrong player assets | Exact documented folder, active fingerprint in UI/logs, login refusal with expected/actual values |
| Unlicensed third-party artwork | Rights reminder in review/docs; never bundle creator images in a World Builder release |
| Scope expands through raw definition options | Only the two versioned preset tokens are accepted; new behavior requires a separately reviewed preset/version |

## Handoff requirements for implementation phases

Every phase handoff must report:

- exact branch and pushed commit;
- changed files grouped by repository-owned versus upstream-owned work;
- focused, lint, and full test commands with results;
- manual platforms/renderers exercised;
- untested behavior and why;
- schema/capability versions and fixture hashes affected;
- confirmation that v1, live checkouts, user workspaces, release gates, tags,
  published assets, and unrelated tasks were untouched;
- known risks or follow-up phases; and
- `READY` only for the bounded phase whose criteria passed, never for the
  unreleased feature as a whole.
