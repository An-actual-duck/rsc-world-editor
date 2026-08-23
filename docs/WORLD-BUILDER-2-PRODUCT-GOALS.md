# World Builder 2 Product Goals and Readiness

## Document status

| Field | Value |
| --- | --- |
| Status | Living product direction and readiness assessment |
| Captured | 2026-08-14 |
| Product | World Builder 2 only |
| Implementation authorization | None; this document does not start or assign work |
| Primary themes | Editor experience, creator content, legacy conversion and reusable regions |

This document records intended product outcomes while the design is still free
to grow, contract, or change. It is not a frozen specification. Later design
documents may make individual increments normative, but they should preserve
the overall direction here or explicitly record why it changed.

World Builder should feel like a creative application that happens to render
through an RSC client, not like ordinary gameplay with administrator commands
attached. It should remain content-neutral, project-local, reversible, and safe
for creators who do not understand the underlying map encodings.

## Readiness language

- **Available foundation** means the relevant behavior is implemented and can
  support later work, although its current user experience may still be rough.
- **Partially ready** means a working path exists but the requested experience
  needs additional protocol, rendering, transaction, or UI work.
- **Design-ready** means the intended behavior is sufficiently understood to
  split into implementation increments after a focused review.
- **Foundational design required** means an important storage, identity,
  compatibility, or runtime decision must be settled before implementation.

## Current foundation

World Builder 2 already provides a substantial base for these goals:

- content-neutral releases with no bundled creator map;
- automatic target discovery for supported layouts;
- deterministic packed-to-layered conversion for an exact supported profile;
- UUID projects with immutable source evidence and isolated mutable working
  packages;
- layered terrain authoring and all four static placement families;
- 1-by-1 and 3-by-3 terrain brushes;
- a continuous Ctrl-drag terrain gesture, bounded to 4,096 unique tiles and
  sent through authoritative batches of at most 64 tiles;
- project-local save, close, reopen, export, transactional import, recovery,
  and exact undo;
- a reversible Build presentation mode with a terrain grid and simplified
  renderer settings; and
- a detailed, unimplemented custom wall/floor material design.

The current drag brush is not yet visually immediate. It queues unique tiles,
sends one authoritative batch at a time, waits for the server response, applies
the accepted client patches, and rebuilds the scene. That safe architecture
explains why the tool works but feels like “apply, then update.”

The current Build presentation mode suppresses client scenery animation while
active and simplifies several renderer settings. It does not establish a fully
detached camera or a comprehensive server simulation pause.

## Goal 1 — A fluid, purpose-built editor

### Detachable editor camera

Builder mode should provide a camera that is independent of the visible player
avatar. A creator should be able to pan, rotate, pitch, and zoom naturally over
the active level without repeatedly walking or teleporting the player.

The intended experience includes:

- keyboard and mouse panning with configurable speed;
- smooth wheel zoom over a wider useful editor range;
- rotation and pitch controls that remain available while another tool is
  selected;
- a visible cursor tile and coordinates derived from the camera ray, not the
  player position;
- quick focus actions such as center on player, center on selection, center on
  coordinates, and return to the last edit;
- level switching without pretending the camera is an ordinary player move;
- optional avatar hiding while authoring; and
- camera bookmarks as later quality-of-life work.

This is not just a camera-variable change. Scene residency, tile picking, the
server-authoritative editing range, and region streaming currently assume a
player-centered view. A safe design needs a Builder camera anchor or observer
contract so the client and isolated server agree on what terrain and
placements must be resident. The anchor must remain bounded to the selected
project and supported world coordinates.

Readiness: **partially ready**. Existing camera controls, click-to-teleport,
layered scene streaming, terrain picking, and Builder-only binding are useful
foundations. The detached anchor and streaming contract still need focused
runtime design and real-client testing.

### Builder-only quiescent world

The isolated Builder runtime should stop gameplay simulation that does not
help editing. Candidate work to pause includes:

- NPC wandering and ordinary entity movement;
- scenery, character, projectile, and environmental animations;
- combat, aggression, fatigue, poison, prayer drain, and skill actions;
- spawn, despawn, respawn, shop, economy, holiday, and world-cycle timers;
- plugin and scheduler activity unrelated to editor control; and
- cosmetic effects that create visual noise or consume rebuild time.

The editor must keep the narrow services it actually needs: authentication and
binding, control/readiness, camera-anchor scene streaming, editor requests,
authoritative validation, save publication, diagnostics, and clean shutdown.
This should be an explicit allowlisted Builder execution profile, not a broad
collection of scattered `if` statements and not a change to normal servers.

Pausing gameplay work may reduce noise and resource use, but it will not by
itself make painting immediate. The largest perceived delay comes from waiting
for authoritative responses and rebuilding scene data. Quiescence and the
preview/reconciliation work below are complementary.

Readiness: **foundational design required**. Some client animation suppression
already exists, and the server is already an isolated Builder process. A full
scheduler, plugin, movement, spawn, packet, and rendering audit is still
required before claiming that the world is frozen.

### Immediate paint trails with authoritative reconciliation

As the pointer crosses tiles, the creator should see the selected terrain,
floor, or wall operation immediately. The safe target behavior is:

1. capture one stroke identity and its original tile states;
2. draw a reversible local preview as each tile enters the brush;
3. stream bounded, ordered deltas to the isolated server without waiting to
   preview the next tile;
4. reconcile accepted authoritative results without rebuilding unrelated
   sectors; and
5. roll back or clearly mark only refused tiles if the authoritative state
   differs.

The final project state remains server-authoritative. A local preview is not a
save and cannot silently survive a refusal, disconnect, level change, or
runtime restart. Duplicate tiles in one stroke should be coalesced while the
original before-state remains available for one-step undo.

Wall painting should show each segment as it is added, including direction and
corner behavior. Preview colors or outlines should distinguish unacknowledged,
accepted, and refused parts of a stroke without obscuring the actual material.

Readiness: **partially ready**. The current drag gesture, bounded tile batches,
server validation, local terrain patching, and timing measurements are strong
foundations. Pipelined requests, speculative preview state, incremental scene
rebuilds, reconciliation, and stroke-level undo are not implemented.

### Relative raise and lower tools

Creators should be able to raise or lower existing terrain by a small delta
instead of first reading and then replacing each tile with an absolute
elevation. Initial controls should include:

- raise one step and lower one step;
- configurable integer delta;
- 1-by-1 and 3-by-3 brushes using the same continuous stroke behavior;
- exact preview of clamped or refused tiles before commit; and
- an optional smoothing or falloff tool as a later increment.

Relative edits must be computed from one authoritative before-state for the
whole stroke. Repeated packets, retries, or overlapping brush samples must not
apply the delta twice.

The current layered tile encoding stores elevation as an unsigned byte and the
editor accepts `0..255`. Relative raise/lower inside that range is a contained
extension. Raising the underlying elevation cap is separate schema work.

Readiness: **design-ready** for byte-range relative edits. The existing stroke
transaction and terrain snapshots provide most of the required foundation.

### Line tools

A line tool should let the creator choose a start tile and end tile, preview a
deterministic grid line, and apply the current selected operation along it.
The same interaction should support floor, color, elevation, roof, and wall
operations where meaningful.

The line algorithm, endpoint inclusion, diagonal behavior, wall orientation,
corner joins, brush width, level, maximum length, and out-of-coverage handling
must be explicit and platform-independent. No line should partially commit
because its later tiles are invalid; preview should identify the blocker first.

Readiness: **design-ready** after the stroke transaction is generalized. A
simple tile line is close to the existing batch model. Wall lines need extra
orientation and join rules.

### Quick house and enclosed-area tools

The quick-house workflow should allow a creator to:

1. choose a house or enclosure preset;
2. place ordered perimeter waypoints;
3. close the perimeter and inspect a complete preview;
4. choose or confirm floor, wall, roof, height, and optional doorway settings;
5. see overlap, coverage, capacity, and collision warnings; and
6. apply the complete structure as one undoable operation.

The first useful version may support rectangles and orthogonal polygons before
arbitrary shapes. “House type” should be a versioned preset describing safe
authoring behavior, not executable content and not an opaque bundle of raw
server flags. Door placement can remain explicit until predictable automatic
placement rules are agreed.

Readiness: **foundational design required**. Lines, ordered selections,
multi-field previews, region transactions, and undo should land first. House
tools can then become a friendly composition layer over those primitives.

### Continuing quality-of-life direction

The longer-term editor should consider:

- undo and redo for terrain and placements;
- rectangle, ellipse, fill, replace, flatten, smooth, and gradient tools;
- eyedropper sampling for every terrain field and placement family;
- favorites, search, recent choices, thumbnails, and named palettes;
- larger brush shapes with clear maximum cost;
- rotate, mirror, move, duplicate, align, and distribute operations;
- layer isolation, hide/show families, collision overlays, and void overlays;
- selection counts, bounds, material dependencies, and estimated operation
  cost before applying;
- clear dirty/saving/saved state and queued-operation status;
- keyboard-first workflows with discoverable shortcuts; and
- automatic local recovery checkpoints that never mutate the target server.

These are a direction, not a promise that every tool belongs in one release.
They should reuse a small number of well-tested selection, preview, operation,
undo, and snapshot primitives instead of growing separate unsafe command paths.

## Goal 2 — Creator-supplied materials and broader content

### Drop-in wall and floor textures

The primary custom-content goal is a simple project-owned inbox where a creator
drops correctly sized images and World Builder discovers them on launch. The
tool should validate and preview the exact rendered result, let the creator
select floor, wall, or both, and add the material without hand-editing numeric
definitions or archives.

The existing [custom materials design](WORLD-BUILDER-2-CUSTOM-MATERIALS.md)
already specifies project-local inboxes, PNG normalization, safe floor/wall
presets, thumbnail browsing, append-only allocation, export/import safety, and
matching client distribution. Implementation has not started.

### Share-safe material identity

A file name cannot safely become the raw terrain value: current terrain stores
small numeric definition IDs, file systems disagree about case and Unicode,
and two creators can use the same name for different pixels. The desired
creator experience can still be name-based while the stored identity is
stronger.

The revised design target is:

- the file stem proposes a visible name and normalized slug;
- every creator or pack has a stable namespace;
- the portable logical identity is `namespace + slug + preset version`;
- canonical pixel bytes and their SHA-256 bind what that identity means;
- the same identity and same canonical content is a verified match;
- the same identity with different content is a visible conflict that requires
  rename, replacement, or cancellation; and
- numeric texture/floor/wall IDs are deterministic local runtime mappings,
  never the portable identity presented to creators.

The existing material plan already allocates the lowest available numeric ID,
persists it, and never randomly reassigns it. That is stable inside one project
or inherited pack. It does not yet solve merging two independently created
packs that used the same numeric slot for different materials; the current
plan intentionally refuses such a merge. Before implementation, the material
contracts must decide how shared imports and region snapshots translate logical
material identities into destination-local numeric IDs without changing the
meaning of existing terrain.

Readiness: **design-ready with one required revision**. Image ingest and safe
presets are thoroughly planned. Portable namespace identity, cross-pack merge,
and numeric remapping must be added to the design before code begins if
creator-to-creator sharing is part of the first material release.

### Beyond wall and floor textures

The same creator-content system may later grow into:

- scenery definitions and models;
- item definitions, inventory/equipment sprites, and safe data presets;
- NPC definitions, sprites, roam/combat presets, and placement metadata;
- roof materials, animated materials, water, emissive, and transparency
  presets; and
- reusable content packs with explicit dependencies and compatibility reports.

These are materially harder than floor and wall textures. Items, NPCs, and
scenery combine visual assets with collision, dimensions, commands, animation,
equipment, combat, economy, and server behavior. They should use bounded,
versioned safe presets and declarative assets; World Builder must never execute
creator-supplied code.

Readiness: **exploratory** beyond basic materials. Finish the material identity,
distribution, and dependency model first so larger content does not invent a
second incompatible package system.

The first general foundation is now versioned through
[`project-local-custom-content-v2`](WORLD-BUILDER-2-CUSTOM-CONTENT-BUNDLES.md),
while retaining v1 compatibility for packaged-item-only targets.
It adopts bounded declarative target definitions and matching client archives
into only the UUID project, derives every authoring family from those bytes,
and exposes one fingerprinted runtime boundary. It does not yet provide the
creator-facing inbox, portable cross-project remapping, or executable behavior,
and it never loads target plugins or scripts.

### Elevation range and color freedom

The native layered terrain v2 representation now uses unsigned 16-bit
big-endian elevation (`0..65535`) while ground color, overlay, roof, and wall
fields retain their frozen byte representations. V1 packages remain readable
and are losslessly promoted for editing. True RGB values remain unavailable;
they cannot be added honestly as a UI-only change.

Two useful increments should be distinguished:

1. Improve the UI for the existing representation: expose all valid current
   primitive values, provide palettes and previews, and offer a color picker
   that resolves to the nearest supported current value.
2. Define a later layered terrain capability and schema for true color. This
   still requires compatible package encoding, wire protocol,
   client rendering, server validation/collision, converter behavior,
   snapshot/export/import contracts, and explicit refusal by older runtimes.

Readiness: **implemented** for unsigned 16-bit elevation across package,
protocol, persistence, conversion, and region sharing; **design-ready** for
better current-value color UI; **foundational design required** for true RGB.
The v2 elevation representation does not reinterpret released v1 bytes.

## Goal 3 — Legacy conversion, region editing, and sharing

### Where conversion stands now

For a supported packed target, automatic packed-to-layered conversion is
already implemented and invoked during adaptive project creation. It:

- copies and binds exact source evidence before conversion;
- converts terrain into signed levels and sectors;
- composes boundaries, scenery, NPCs, and ground items;
- preserves coordinates, directions, IDs, amounts, respawn, and roam bounds;
- derives stable placement IDs;
- reverses terrain to require exact source-byte parity;
- compares normalized placement semantics for zero additions, removals, moves,
  replacements, or collision changes; and
- emits a deterministic conversion plan and record-level report.

Unknown, lossy, approximate, unapproved repair, or parity-changing cases fail
closed and identify their source provenance. The target remains untouched.

This means the straightforward supported conversion path is **available**. It
does not yet mean that every legacy map is one-click compatible.

### Conversion work still needed

The following remain:

- adapters for additional real legacy layouts and configuration conventions;
- a polished compatibility summary and progress UI;
- an outlier workbench that groups blockers by file, record, coordinates,
  family, and recommended action;
- a safe way to create a quarantined repair project when exact source
  conversion is impossible, while clearly withholding normal import authority;
- jump-to-location and selection actions for spatial outliers;
- explicit, versioned repair decisions instead of a generic force option;
- a final zero-blocker validation pass that upgrades a repair project to a
  normal layered project; and
- a separately designed migration path if historical World Builder v1
  workspaces, rather than active server packed maps, must also be accepted.

Automatic conversion should handle every exact case. Weird outliers should be
reported precisely and offered deliberate fixes where a safe meaning exists.
Unknown records must never be silently dropped, moved, or approximated.

Readiness: **partially ready**. The deterministic engine and detailed evidence
model are complete. Broader adapter coverage and the manual-remediation UX are
not implemented.

### Ordered region selection

Copy, cut, paste, repair, and prefab tools should share one selection model.
The intended polygon workflow is:

1. choose a selection or cut tool;
2. place ordered markers around the region (`1`, `2`, `3`, and so on);
3. move, remove, or insert a marker before closing the shape;
4. preview the enclosed tiles, affected levels, placements, materials, and
   bounds; and
5. explicitly choose Copy or Cut.

Marker 1 is the snapshot anchor. The paste location is the destination of that
anchor, making the result predictable without exposing internal sector math.
The selection must define whether boundary segments lying on its edge belong
inside, and it must report placements whose multi-tile footprint crosses the
selection boundary.

Readiness: **Editor foundation implemented; interactive runtime pending**. The
strict ordered-polygon contract, integer tile-center/edge ownership,
content-addressed library, and placement-footprint reports are implemented in
[World Builder 2 Region Snapshots v1](WORLD-BUILDER-2-REGION-SNAPSHOTS.md).
There is not yet an in-game marker protocol or selection UI.

### Copy, cut, and paste behavior

A region snapshot should capture, relative to marker 1:

- every selected terrain field and whether a tile is canonical void;
- all boundaries, scenery, NPCs, and ground items owned by the selection;
- signed level offsets;
- placement directions, footprints, amounts, respawn, and roam bounds;
- definition and custom-material logical identities;
- the source project's relevant capability and catalog fingerprints; and
- a canonical inventory and content hash.

Copy leaves the working project unchanged. Cut first creates and verifies the
snapshot, then removes included placements and restores selected terrain to
canonical void as one project-local undoable transaction. It never changes the
target server.

Paste should show a complete ghost preview. If any destination is non-void,
contains placements, crosses unavailable terrain, exceeds supported bounds, or
has incompatible definitions/materials, the tool must show exactly what would
collide. Overwriting non-void or occupied content requires a separate explicit
confirmation after the collision preview. There is no silent partial paste.

The first version should preserve orientation and level offsets. Rotation,
mirroring, selective terrain/placement paste, clipping, and merge strategies
can follow after exact untranslated paste is reliable.

Readiness: **Editor foundation implemented; interactive runtime pending**.
Versioned snapshot/operation schemas, copy-on-write cut/paste, collision plans,
exact overwrite confirmation, and placement-footprint rules are implemented.
Server-authoritative interactive transactions, preview packets, undo/redo, and
client ghost rendering remain runtime work.

### Exportable and shareable snapshots

Snapshots should be exportable as content-neutral, non-executable bundles that
another creator can inspect before importing. A bundle should contain:

- one strict manifest with schema and tool version;
- safe relative paths only;
- the canonical region payload and hash;
- definition/material dependencies by portable logical identity;
- source capability information for diagnostics, not absolute host paths;
- optional thumbnail/preview evidence generated by the tool; and
- no credentials, player data, logs, project secrets, backups, receipts, or
  target mutation authority.

Importing a snapshot adds it to a project-local library; it does not paste or
change the target automatically. Compatibility and collision checks run before
the user can preview a paste. Unknown schema versions, dependencies, files, or
presets fail closed.

Readiness: **Editor foundation implemented**. Deterministic two-entry `.wbr`
bundles, strict inventory/path validation, project-local import, independent
export, portable logical dependencies, and incompatible-custom-content reports
are implemented. Material/sprite payload bundling remains blocked until its
separate capability exists.

## Recommended dependency order

This is a technical dependency order, not an assignment or fixed release plan:

1. Generalize the current terrain stroke into one previewable, undoable editor
   operation model.
2. Build on the implemented atomic `0..65535` relative raise/lower capability
   and add deterministic line tools.
3. Add immediate local stroke previews, pipelined authoritative
   reconciliation, and incremental scene rebuilds.
4. Design and implement the detached camera anchor and the quiescent Builder
   execution profile.
5. Use the implemented Editor-owned ordered selection, local snapshot,
   copy/cut/paste, and strict import/export contracts as the runtime boundary.
6. Add runtime marker placement, ghost previews, authoritative transactions,
   and durable undo/redo against those contracts.
7. Use selection and snapshots to build the conversion outlier workbench and
   quick-house/prefab tools.
8. Revise the custom-material identity model for creator-to-creator sharing,
   then implement drop-in floor/wall materials.
9. Consider RGB terrain and broader custom content only through new explicit
   capabilities and schema versions; wide elevation already uses that boundary.

Some increments can be reordered, but region snapshots should not invent a
material-sharing model that custom materials later have to replace.

## Readiness summary

| Goal | Current readiness | Main missing work |
| --- | --- | --- |
| Detached camera | Partially ready | Camera anchor, scene residency, editor picking and protocol |
| Quiescent Builder runtime | Foundational design required | Scheduler/plugin/entity audit and explicit allowlist |
| Fluid paint trails | Partially ready | Immediate preview, pipelining, reconciliation, incremental rebuild |
| Relative raise/lower within `0..65535` | Runtime and persistence implemented | Polished Editor UI |
| Line tools | Design-ready | Deterministic geometry, wall joins, complete preview |
| Quick house tools | Foundational design required | Selection, lines, presets, region transaction and undo |
| Drop-in wall/floor textures | Design-ready with revision | Portable identity/remapping plus runtime implementation |
| Wider elevation | Implemented | Polished Editor UI and additional visual validation |
| True RGB | Foundational design required | New package, protocol, renderer and compatibility capability |
| Packed-to-layered exact conversion | Available for supported profile | More adapters and polished UX |
| Outlier-assisted conversion | Partially ready | Repair-project model, workbench, reviewed transform decisions |
| Region copy/cut/paste | Editor foundation implemented | Runtime marker/ghost transaction and durable undo UX |
| Exportable snapshots | Editor foundation implemented | Custom material/sprite payload capability |

## Decisions to settle before implementation planning

- How far may a detached camera move before the isolated runtime changes its
  resident scene window, and how is that anchor authenticated?
- Which exact scheduler, plugin, entity, animation, and timer families remain
  active in the quiescent Builder profile?
- Does a refused tile roll back only itself or the entire logical stroke?
- What undo/redo durability is required across save, close, and reopen?
- Is byte-range relative elevation sufficient for the first terrain-tool
  increment, or should wider elevation be designed first?
- What stable namespace identifies a creator/material pack, and how is it
  preserved when shared?
- When custom materials become bundleable, will paste translate their logical
  identities into destination-local IDs, or will layered terrain gain logical
  material references? Snapshot v1 deliberately reports them as unsupported.
- Snapshot v1 preserves exact orientation and signed offsets across multiple
  selected levels; it does not rotate, mirror, clip, or selectively paste them.
- Which outliers are safely repairable, which require manual edits, and which
  must remain hard blockers?
- What is the smallest useful house preset: rectangle, orthogonal polygon, or
  arbitrary closed polygon?

These questions are intended to make future discussion concrete. Answers may
be added here without activating implementation work.
