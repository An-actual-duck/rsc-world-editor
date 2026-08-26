# World Builder 2 Product Goals and Readiness

## Document status

| Field | Value |
| --- | --- |
| Status | Living product direction and readiness assessment |
| Captured | 2026-08-14 |
| Last reconciled | 2026-08-25, after `v0.5.0-alpha.11` publication |
| Product | World Builder 2 only |
| Implementation authorization | None; this document does not start or assign work |
| Current focus | Fluid tools, predictable interaction, scenery movement, and interactive reusable regions |
| Longer themes | Creator content, legacy conversion, detached authoring, and safe declarative object actions |

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
- absolute and relative elevation editing across the unsigned 16-bit
  `0..65535` range;
- contextual toolbar actions for terrain, scenery, NPCs, and ground items;
- project-local save, close, reopen, export, transactional import, recovery,
  and exact undo;
- a reversible Build presentation mode with a terrain grid and simplified
  renderer settings; and
- a detailed, unimplemented custom wall/floor material design.

The current drag brush is not yet visually immediate. It queues unique tiles,
sends one authoritative batch at a time, waits for the server response, applies
the accepted client patches, and rebuilds the scene. That safe architecture
explains why the tool works but feels like “apply, then update.”

Owner testing also found that some drag sequences can leave terrain painting
unable to continue. The exact trigger is not yet isolated. Focus loss, modifier
release, mouse release outside the scene, mode changes, refusal, disconnect,
and a delayed final response must all be treated as candidate lifecycle edges
until a reproducer and fix prove otherwise.

The current Build presentation mode suppresses client scenery animation while
active and simplifies several renderer settings. It does not establish a fully
detached camera or a comprehensive server simulation pause.

The Editor repository also contains a complete non-interactive region snapshot
foundation. Ordered polygon capture, copy, cut, paste, import, export,
collision plans, recovery, and all four placement families are tested. The
packaged client has no region-selection toolbar mode, numbered markers, ghost
preview, or snapshot-library workflow yet. Its visible **Copy inspected** action
copies inspected field values and must not be mistaken for region Copy.

## Current product focus — interaction before tool count

The next tool release should establish one responsive interaction foundation
and then build additional tools on it. Adding many independent commands first
would preserve the current delayed feel and multiply input-state, preview,
transaction, and undo paths.

The intended order for the active product focus is:

1. reproduce and eliminate the drag-stroke lockup, measure the current
   acknowledgement and rebuild path, and make every gesture terminate cleanly;
2. add immediate reversible brush feedback and incremental authoritative
   reconciliation, then expose centered 1-by-1 through 7-by-7 footprints;
3. reuse that operation/preview path for line and rectangle outline/fill tools;
4. add an atomic scenery Move gesture with a destination ghost and collision
   validation; and
5. expose and visually validate the existing region snapshot foundation through
   an in-game selection, Copy/Cut/Paste, library, import, and export workflow.

Detached-camera work, deeper world quiescence, quick-house presets, creator
materials, and declarative object actions remain important, but they must not
displace this immediate interaction milestone unless the product owner changes
the priority again.

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

Pointer movement between rendered samples must be interpolated over the tile
grid so a fast drag cannot leave accidental holes. Sampling, preview drawing,
request batching, response handling, and package saving are separate concerns:
slow authority or persistence must not prevent the cursor preview from moving.

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
rebuilds, reconciliation, stroke-level undo, and a proven fix for the reported
stuck-stroke lifecycle are not implemented.

### Centered brush footprints through 7-by-7

The normal square brush choices should be 1-by-1, 3-by-3, 5-by-5, and 7-by-7.
Odd sizes keep one unambiguous tile directly under the pointer. For size `n`,
the footprint is the complete square radius `(n - 1) / 2` around that center;
it must not drift toward one corner as camera angle, level, or screen position
changes.

The complete footprint should be visible before painting. Drag interpolation
operates on successive center tiles, while duplicate footprint tiles within one
logical stroke are coalesced. A 7-by-7 sample contains 49 tiles and fits the
current 64-tile authoritative batch ceiling, but a continuous stroke may span
many batches and must remain one predictable gesture. Edge and unavailable-tile
behavior must be previewed rather than silently clipping the brush.

Readiness: **design-ready after the drag lifecycle audit**. The current 1-by-1
and centered 3-by-3 implementation, 64-tile batches, and 4,096-tile gesture
bound are useful foundations. The client and runtime currently reject sizes 5
and 7 and expose no complete footprint preview.

### Relative raise and lower tools

Creators can raise or lower existing terrain by a configurable delta instead of
first reading and then replacing each tile with an absolute elevation. The
released controls include:

- raise one step and lower one step;
- configurable integer delta;
- Set, Raise, and Lower modes;
- 1-by-1 and 3-by-3 brushes using the same continuous stroke behavior; and
- atomic refusal when a relative operation would exceed `0..65535`.

The next polish should add the centered 5-by-5 and 7-by-7 footprints, immediate
preview, and a clear indication of any operation that would be refused before
commit. Smoothing and falloff remain later increments.

Relative edits must be computed from one authoritative before-state for the
whole stroke. Repeated packets, retries, or overlapping brush samples must not
apply the delta twice.

The current layered tile encoding stores elevation as an unsigned 16-bit value.
Packages, protocol, runtime validation, save/reopen, conversion, and region
snapshots preserve `0..65535` without reinterpreting released v1 bytes.

Readiness: **runtime and persistence implemented; interaction partially
ready**. The next terrain-tool increment should concentrate on shared fluid
preview, centered brush sizes, and gesture polish rather than another elevation
encoding.

### Drag-and-drop scenery movement

Scenery mode should gain **Move** alongside Place, Rotate, and Remove. A creator
selects an existing scenery placement, drags or chooses its destination tile,
sees a ghost using the preserved definition and direction, and commits one
atomic move. Escape, right-click cancellation, focus loss, a mode change, or an
invalid destination leaves the original placement untouched.

The first increment should preserve level, placement identity, definition,
direction, and any project-local metadata. It should refuse overlap, missing
terrain, unsupported bounds, or an unavailable destination before removing the
source. Cross-level moves, duplication, rotation during drag, and multi-object
movement can follow after same-level single-scenery movement is reliable.

Readiness: **partially ready**. Authoritative scenery inspection, placement,
rotation, removal, definition browsing, collision checks, and stable placement
records exist. There is no Move tool, ghost preview, or single authoritative
move transaction.

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

### Rectangle outline and fill tools

A two-corner rectangle tool should provide both **Outline** and **Fill** modes.
For terrain, Fill applies the currently enabled terrain fields to every tile in
the rectangle; Outline applies only its perimeter. For walls, Outline creates
the four correctly oriented edges and deterministic corner joins. A filled
wall rectangle must not mean a dense wall on every interior tile unless the
creator explicitly selects a terrain-like wall-fill operation.

The cursor selects the first corner, movement shows the complete prospective
bounds and tile count, and the second click commits only after all coverage,
range, field, and operation limits validate. Cancellation changes nothing.
Line and rectangle operations should share geometry, preview, batching,
reconciliation, and undo primitives with freehand painting.

Readiness: **design-ready after the shared operation model**. Rectangle terrain
enumeration is straightforward; wall edge ownership, corner joins, atomic
multi-batch application, and visible preview require explicit implementation.

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

### Long-term declarative Action mode

Placed scenery should eventually support an **Action** mode. A creator could
right-click a placement, inspect its existing interaction definition, clone it
into a new project-local definition, choose one or more safe behavior presets,
edit their bounded parameters, preview validation, and save the new definition
without hand-editing server code.

The motivating presets include:

- an agility shortcut with requirements, success destination, failure
  destination, animations, messages, and optional damage;
- a fast-travel or teleport interaction with explicit source and destination;
- simple doors, ladders, entrances, and one-way transitions; and
- reusable named actions whose dependencies travel with a region snapshot.

This must be declarative and versioned. World Builder must not execute imported
scripts, plugins, expressions, JARs, or arbitrary creator code. Locations,
definition identity, action labels, requirements, failure states, and runtime
effects need strict schemas, bounds, compatibility reporting, and a deployment
adapter for each supported server behavior format. Saving “as a new object”
also requires collision-free project-local definition identity and portable
remapping when shared.

Readiness: **foundational design required and intentionally long-term**. The
current project can preserve bounded scenery definitions and placements, but
server interaction behavior is not standardized across private servers. Safe
action schemas, supported presets, definition allocation, runtime preview,
snapshot dependencies, export adapters, and target compatibility all need a
separate design before implementation. It is not part of the immediate fluid
tools milestone.

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
[`project-local-custom-content-v3`](WORLD-BUILDER-2-CUSTOM-CONTENT-BUNDLES.md),
while retaining v1/v2 compatibility for targets that need no private NPC
animation-registry binding.
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

The approved implementation sequence for broader server compatibility is now
specified in [World Builder 2 Format-Aware Discovery and Streamlined
Launch](WORLD-BUILDER-2-FORMAT-AWARE-DISCOVERY.md). It makes automatic
server-root discovery and canonical content reconciliation the ordinary path,
generates provider evidence internally, moves manual provider/file selection
behind Advanced/Recovery, and uses the currently observed incomplete-scenery
import as a mandatory end-to-end regression case.

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
There is not yet an in-game marker protocol or selection UI, and there is no
region tool on the packaged toolbar.

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
client ghost rendering remain runtime work. Before calling the feature
creator-ready, the exposed workflow must be tested end to end for irregular
ordered polygons, canonical-void Cut, collision-confirmed Paste, all four
placement families, wide elevations, close/reopen recovery, and `.wbr`
export/import between two independent projects.

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

1. Reproduce the broken drag lifecycle, profile acknowledgement/rebuild costs,
   and guarantee cleanup on release, refusal, focus loss, mode change,
   disconnect, and shutdown.
2. Generalize the current terrain stroke into one immediate, previewable,
   reconcilable, and undoable operation model; expose centered 1-by-1, 3-by-3,
   5-by-5, and 7-by-7 footprints through it.
3. Add deterministic line and rectangle Outline/Fill tools using that same
   geometry, preview, transaction, and undo path.
4. Add same-level single-scenery Move with a ghost destination and one atomic
   authoritative transaction.
5. Use the implemented Editor-owned ordered selection, local snapshot,
   copy/cut/paste, and strict import/export contracts as the runtime boundary;
   add marker placement, ghost previews, transactions, toolbar/library UI, and
   durable undo/redo.
6. Design and implement the detached camera anchor and the quiescent Builder
   execution profile.
7. Use selection, lines, rectangles, and snapshots to build the conversion
   outlier workbench and quick-house/prefab tools.
8. Revise the custom-material identity model for creator-to-creator sharing,
   then implement drop-in floor/wall materials.
9. Design declarative scenery Action presets and server-format adapters only
   after definition identity and portable dependency handling are settled.
10. Consider RGB terrain and broader custom content only through new explicit
    capabilities and schema versions; wide elevation already uses that boundary.

Some increments can be reordered, but region snapshots should not invent a
material-sharing model that custom materials later have to replace.

## Readiness summary

| Goal | Current readiness | Main missing work |
| --- | --- | --- |
| Detached camera | Partially ready | Camera anchor, scene residency, editor picking and protocol |
| Quiescent Builder runtime | Foundational design required | Scheduler/plugin/entity audit and explicit allowlist |
| Fluid paint trails | Partially ready | Immediate preview, pipelining, reconciliation, incremental rebuild |
| Centered 5-by-5 and 7-by-7 brushes | Design-ready after drag audit | General footprint logic, preview, controls and validation |
| Relative raise/lower within `0..65535` | Runtime and persistence implemented | Polished Editor UI |
| Line tools | Design-ready | Deterministic geometry, wall joins, complete preview |
| Rectangle outline/fill | Design-ready after operation model | Preview, wall edges/corners and atomic multi-batch apply |
| Scenery drag-move | Partially ready | Move mode, ghost destination and atomic move transaction |
| Quick house tools | Foundational design required | Selection, lines, presets, region transaction and undo |
| Drop-in wall/floor textures | Design-ready with revision | Portable identity/remapping plus runtime implementation |
| Wider elevation | Implemented | Polished Editor UI and additional visual validation |
| True RGB | Foundational design required | New package, protocol, renderer and compatibility capability |
| Packed-to-layered exact conversion | Available for supported profile | More adapters and polished UX |
| Outlier-assisted conversion | Partially ready | Repair-project model, workbench, reviewed transform decisions |
| Region copy/cut/paste | Editor foundation implemented | Runtime marker/ghost transaction and durable undo UX |
| Exportable snapshots | Editor foundation implemented | Custom material/sprite payload capability |
| Declarative scenery Action mode | Foundational design required; long-term | Safe presets, definition identity, runtime behavior and server adapters |

## Decisions to settle before implementation planning

- How far may a detached camera move before the isolated runtime changes its
  resident scene window, and how is that anchor authenticated?
- Which exact scheduler, plugin, entity, animation, and timer families remain
  active in the quiescent Builder profile?
- Does a refused tile roll back only itself or the entire logical stroke?
- Should ordinary primary-button drag paint while the brush is selected, or
  should Ctrl remain required after accidental-edit prevention is reviewed?
- Which input events terminate a stroke, and what visible state proves there
  is no pending or stuck gesture?
- What undo/redo durability is required across save, close, and reopen?
- Which deterministic grid-line rule and diagonal wall join rule become the
  portable line contract?
- Does rectangle wall Fill mean perimeter enclosure only, or should a separate
  dense-fill operation exist?
- Must scenery Move preserve the exact placement ID, and which metadata becomes
  part of the atomic move identity?
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
- Which first declarative Action presets are portable enough to support across
  server formats, and how are target-specific behaviors reported when no safe
  adapter exists?

These questions are intended to make future discussion concrete. Answers may
be added here without activating implementation work.
