# World Builder 2 Region Snapshots v1 and v2

Status: **Editor foundation implemented; runtime interaction pending**
Scope: ordered selection, portable snapshots, project-local library, copy,
cut, paste, compatibility, collision preview, and atomic Editor publication
Runtime provider: unchanged by this feature

Current packaged UI status as of 2026-08-25: the contracts and advanced Editor
commands below are implemented and tested, but there is no region-selection
toolbar mode, numbered-marker interaction, ghost preview, or snapshot-library
window. The client's **Copy inspected** action copies inspected terrain/entity
values into current editing controls; it is not region Copy. Region sharing is
therefore an implementation foundation, not yet a discoverable creator tool.

This document is normative for region snapshot versions 1 and 2. “MUST”, “MUST NOT”,
“SHALL”, and “SHALL NOT” are requirements. The Java validators are the
executable authority where prose and malformed external input disagree.

## Product boundary

The Editor now owns a complete non-interactive foundation for sharing and
applying regions in an isolated adaptive project. It does not provide the final
in-game marker or ghost-preview experience. Every operation is project-local;
no command in this feature resolves or writes the target server.

The frozen World Builder v1 workflow is unchanged. Region bundles belong only
to World Builder 2 adaptive projects.

## Durable layout

Each project may contain this creator-owned library:

```text
projects/<project-uuid>/
  snapshot-library/
    v1/
      <snapshot-sha256>.wbr
```

The filename is the snapshot content identity. Library entries are independent
regular files. Links, path aliases, identity collisions, and changed bytes are
refused. Files and containing directories are forced before and after atomic
publication. Application updates preserve the complete project directory.

Copy may add a verified entry to this library, but it MUST NOT change
`working/layered-world/package`, `project.json`, source evidence, or a target.
Import has the same rule: it adds a bundle to the library and reports
compatibility; it never pastes automatically.

## Versioned contracts

The strict schemas are:

| Contract | Schema |
| --- | --- |
| Ordered selection | `region-selection-v1.schema.json` |
| Region payload | `region-snapshot-v2.schema.json` (current), `region-snapshot-v1.schema.json` (readable legacy) |
| Bundle manifest | `region-bundle-manifest-v1.schema.json` |
| Compatibility report | `region-compatibility-report-v1.schema.json` |
| Atomic cut/paste plan | `region-operation-plan-v1.schema.json` |

All objects reject unknown fields. Arrays and strings are bounded. Integers
are signed 32-bit unless a narrower range is stated. Every hash is lowercase
SHA-256 over canonical UTF-8 JSON with sorted object keys, preserved array
order, no insignificant whitespace, and its fingerprint field temporarily set
to 64 zeroes. A snapshot sets both `snapshotId` and
`snapshotFingerprintSha256` to zero for hashing, then stores the same resulting
hash in both fields.

## Selection and geometry

A selection is one ordered, simple polygon and one ascending list of existing
signed levels.

- Markers are numbered consecutively from `1` with no repeated coordinate.
- Marker 1 is the source anchor and the paste anchor.
- Polygon order and orientation are preserved. Version 1 does not rotate,
  mirror, clip, or repair the polygon.
- A tile belongs to the selection when its integer tile center is inside the
  polygon. A center exactly on an edge belongs to the selection.
- Integer-only ray crossing and edge tests make ownership identical across
  platforms.
- Self-intersection, overlapping non-adjacent edges, zero area, more than 256
  markers, an axis span over 4,096 tiles, an excessive bounding search, no
  owned tile, unavailable terrain, or more than 65,536 selected tiles fails
  closed.
- Every selected level contains exactly the same polygon-owned horizontal tile
  offsets. Marker 1 anchors horizontal offsets; the lowest selected level
  anchors signed level offsets.

## Captured content

For every owned tile, the snapshot stores every terrain field by name:
elevation, ground texture, ground overlay, roof texture, vertical wall,
horizontal wall, and 32-bit diagonal wall. `canonicalVoid` is verified against
the shared canonical void record:

```text
elevation=0, groundTexture=1, groundOverlay=8,
roofTexture=0, verticalWall=0, horizontalWall=0, diagonalWall=0
```

Snapshot v1 restricts elevation to `0..255` and remains readable without
reinterpretation. New captures use snapshot v2 and retain elevations through
`65535`; copy, cut, paste, bundle import/export, and recovery preserve those
values exactly. Every non-elevation terrain field and placement family has the
same representation in both versions.

The snapshot retains every supported placement whose ownership point belongs
to the polygon:

- boundary: origin tile, definition ID, direction, and placement ID;
- scenery: anchor tile, definition ID, direction, and placement ID;
- NPC: start tile, definition ID, placement ID, and complete roam bounds; and
- ground item: tile, definition ID, placement ID, amount, and respawn seconds.

All coordinates and levels are stored relative to marker 1. Placement IDs are
preserved. Duplicate IDs or invalid records fail validation.

Scenery snapshots preserve the legacy compatibility direction `8` when it is
present in imported content. Interactive authoring continues to offer
directions `0..7`; values above `8` are invalid.

### Footprint ownership and boundary reports

Version 1 has deterministic ownership rules matching the generic layered
placement representation:

- a boundary is owned by its origin tile; directions `0,1,2,3` report the
  adjacent edge tile at north, east, south, and west respectively;
- an NPC is owned by its start tile and its complete roam rectangle is checked
  for crossing;
- scenery and ground items have one represented anchor tile because generic
  package v1 carries no larger definition footprint.

Every captured placement has exactly one report. It states the ownership rule
and whether the represented footprint crosses the polygon. Crossings are
visible; they are never silently clipped or approximated.

## Portable dependency model

The snapshot carries the exact catalog ID/hash, source package/coordinate
identity, source working hash, runtime evidence hash, and a canonical dependency
array. Dependencies use stable logical IDs and one of these kinds:

- `definition-catalog`;
- `definition` for boundary, scenery, NPC, or ground-item IDs;
- `material`; or
- `sprite`.

Version 1 bundles no dependency payload. `bundled` MUST be false. Catalog-backed
definitions resolve only against the exact destination catalog and required
numeric ID. `material` and `sprite` records are representable for forward
compatibility but have resolution `unsupported`; they produce precise blocking
issues until the custom-material capability exists. The tool MUST NOT infer,
renumber, approximate, download, or execute missing content.

Runtime/source hashes are portable diagnostic evidence, not host paths. No
bundle may contain credentials, logs, players, databases, backups, receipts,
absolute paths, commands, scripts, native code, or target mutation authority.

## Bundle format and library import/export

`.wbr` is a deterministic ZIP using stored entries and a fixed timestamp. It
contains exactly these regular JSON entries in this order:

```text
manifest.json
snapshot.json
```

The manifest inventories `snapshot.json` by exact relative path, byte length,
and hash. Archive size, expanded entry size, entry count, path normalization,
case/Unicode portable collisions, JSON complexity, schema identity, canonical
fingerprints, and inventory agreement are checked before publication. Any
extra entry—including an executable or traversal path—blocks import.
Semantically valid archives with alternate ZIP metadata, compression, entry
ordering, comments, extra fields, or prefix/trailing bytes are re-encoded to
the one stored, timestamp-fixed, two-entry representation before they may enter
the library; none of those source encodings are retained.

Export uses a new `.wbr` destination outside the project and never overwrites.
Repeated export of one library entry is byte-identical. Import first validates
the complete bundle, then publishes it by content identity. An already present
identical entry is a no-op. Different bytes at one identity are a hard
collision. Structurally valid bundles with currently unsupported dependencies
are retained for future use and receive an incompatible report; paste remains
blocked.

## Cut contract

Cut is deliberately two-step:

1. `region-cut-preview` captures, validates, publishes, and reopens the
   snapshot before planning any map mutation.
2. It emits a canonical plan bound to the snapshot ID, project UUID, exact
   working fingerprint, anchor, and every changed package file’s before/after
   hash.
3. `region-cut-apply` recalculates the plan under the shared project lock.
4. Apply requires the exact plan hash and literal `CUT <plan-sha256>`.
5. The tool stages a complete package, removes every owned placement, replaces
   every selected terrain tile with the shared canonical void tile, validates
   the staged package with the generic layered validator, then publishes and
   saves it through verified copy-on-write exchange.

Drift, a stale plan, an unavailable tile, validation failure, or publication
failure leaves the last complete package in authority. The already verified
snapshot remains in the library. Source and target data never change.
The staged package, rollback package, project-manifest save, and cleanup are
ordered by a forced transaction journal. Re-entry recovers only when exact tree
and working fingerprints prove the complete before or after state; any other
artifact combination is retained and refused as ambiguous.
Cleanup intent names both its exact transaction artifact and deterministic
quarantine before any staged, failed/displaced, or rollback tree is deleted.
The source-to-quarantine rename is atomic; once the live tree and project
manifest prove the complete before or after state, recovery resumes deletion
even if an earlier attempt stopped mid-tree. No random, unjournaled displaced
package exists. Orphan atomic-journal writes are bounded and adopted or discarded
only when their immutable transaction identity agrees. Ordinary project open,
selection, launch, and save run this recovery before validating or using the
working package. Project, recovery, cleanup-tree, and library directory scans
have explicit inventory ceilings, and every file is size-checked before a
recovery or library identity hash.

## Paste contract

Paste translates marker 1 to the supplied destination `(level,x,y)`. All
horizontal offsets, directions, placement metadata, and signed level offsets
are preserved exactly.

Preview reports:

- definition/catalog/custom dependency incompatibility;
- absent destination levels or terrain coverage;
- every non-void destination tile;
- every placement owned by the destination polygon;
- represented boundary edges and NPC roam footprints entering the destination
  from placements anchored outside it;
- missing coverage or preserved occupied content under an incoming crossing
  boundary/NPC footprint;
- the complete deterministic source-placement-ID to destination-local-ID map;
- the exact file hashes that an allowed operation would change.

Placement IDs remain unchanged in snapshot provenance. Paste preserves an ID
when locally available and otherwise derives a deterministic project/snapshot-
bound destination ID, so Copy then Paste works without deleting or changing
the source placements. Missing coverage, crossing footprints, incompatible
dependencies, overflow, or malformed content is blocking and yields no file
authority. Non-void terrain or destination-owned placements set
`overwriteRequired`. A normal paste requires literal
`PASTE <plan-sha256>`; an overwrite requires the separate literal
`OVERWRITE <plan-sha256>`. There is no force, partial, merge, clipping,
rotation, mirroring, or selective-family mode.

Represented footprint work is rectangle- and sector-based. Snapshot and
destination inventories each have an explicit aggregate one-million-tile
footprint budget, the 48-by-48 spatial index has a one-million-entry budget,
and cumulative candidate scans are bounded. NPC roam rectangles are never
expanded into in-memory point lists or repeatedly compared tile-by-placement.

Overwrite clears placements owned by the destination polygon before restoring
the complete snapshot. Content outside that ownership polygon is preserved.
The staged complete package must pass the same generic validator before the
copy-on-write publication and project save.

## Commands

```text
region-copy --project P --selection S --name N
region-cut-preview --project P --selection S --name N
region-cut-apply --project P --snapshot ID --expected-plan H --confirm "CUT H"
region-import --project P --bundle FILE.wbr
region-export --project P --snapshot ID --output NEW-FILE.wbr
region-paste-preview --project P --snapshot ID --level L --x X --y Y
region-paste-apply --project P --snapshot ID --level L --x X --y Y \
  --expected-plan H --confirm "PASTE H"
# use "OVERWRITE H" only when preview says overwriteRequired=true
```

These advanced commands are the Editor-owned contract boundary. The packaged
in-game UI will call equivalent reviewed APIs only after runtime work below.

## Runtime work still required

The runtime provider must implement a separate reviewed capability before the
feature is exposed as an in-game creator workflow:

1. authenticated ordered marker placement, insertion, movement, removal, and
   closure packets;
2. server-authoritative selection computation using this exact geometry rule;
3. a complete ghost preview for terrain, all placement families, footprint
   crossings, missing coverage, and overwrite collisions;
4. hash-bound cut/paste transaction requests that cannot bypass the preview;
5. runtime/editor coordination so the quiescent loaded world observes only the
   newly validated complete package;
6. durable user-facing undo/redo and recovery across save, close, interruption,
   and reopen; and
7. custom material/sprite/definition capability negotiation and safe logical-ID
   remapping before any such dependency may resolve.

Runtime work MUST preserve loopback authentication, package/catalog binding,
project locks, source/target isolation, clean shutdown, and fail-closed native
terrain readiness. Editor schemas do not authorize runtime behavior by
themselves.

## Acceptance evidence

Automated tests cover strict schemas, canonical hashes, polygon edge ownership,
self-intersection and malformed selection refusal, all terrain fields and
levels, all four placement families, footprint reports, deterministic bundle
round trips, archive canonicalization, import without world mutation,
traversal/extra-entry refusal, dependency incompatibility, collision/overwrite
previews, crossing footprints, extreme coordinates, symlinked export ancestors,
aggregate footprint refusal, oversized/tampered library entries and stages,
bounded adversarial directories, ordinary open/save/launch recovery, partial
before/after cleanup and journal-write/delete recovery,
every publication recovery milestone, same-project four-family Copy/Paste,
canonical cut voiding, exact paste restoration, and source/target preservation.
Runtime marker/ghost/undo UX remains explicitly
untested because it is not implemented here.
