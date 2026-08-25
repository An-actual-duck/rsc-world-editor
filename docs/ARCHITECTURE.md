# Architecture

## Product boundary

RSC World Editor is a local, isolated editing appliance. Adaptive World Builder
2 is designed as a standalone, server-agnostic drop-in folder placed directly
inside a compatible game/server root. It discovers that target's active map,
definitions, and capabilities, then adopts or converts copies into a project
owned by World Builder. It uses a compatible game client, server, terrain
format, definitions, collision rules, and authoritative world-editor protocol,
but it never connects to or edits a public server.

The repository is divided into four layers:

- `tools/world-builder/` contains Java tooling for target discovery,
  conversion, UUID project lifecycle, process supervision, export, import,
  rollback, recovery, and undo.
- `release/world-builder/` preserves the frozen packed-map v1 package assets.
- `release/world-builder-v2/` contains the distinct signed-layered v2
  launchers, runtime profile, instructions, and asset provenance.
- The independent runtime-provider revision in `runtime-provider.lock` supplies a frozen
  compiled client/server runtime, integrated editor implementation, and one
  supported adapter source. It is an external generic build/runtime dependency,
  not this product's identity or target content and not part of this
  repository's manager/worker system. Runtime source is developed in the
  separate `rsc-world-editor-runtime` repository rather than Core-Framework.

## Durable and replaceable state

An installed package has two fundamentally different classes of files.

Durable World Builder 2 state includes `projects/<uuid>/`,
`project-registry.json`, `active-project.json`, and each project's source,
working package, generated runtime state, exports, backups, receipts,
diagnostics, logs, and settings. The historical v2-alpha `workspace/` is also durable and is
preserved without implicit migration. Durable state must survive application
updates and is never committed here.

Replaceable application state includes the packaged Java runtime, launcher
tooling, client/server binaries, schemas, scripts, documentation, and only the
runtime/default-catalog files named in `RUNTIME-ASSET-ALLOWLIST.txt`. Terrain,
placements, layered packages, projects, and generated operational state can
never enter this layer. Release and updater work may replace it only after the
Builder is closed and the replacement has been verified.

Each v2 package inventories that replaceable layer in
`PACKAGE-MANIFEST.sha256`. Updates back up and remove only paths owned by the
installed manifest, refuse collisions with unknown files, and restore the old
managed layer if installation, verification, or selected-project compatibility
fails. Archive and manifest validation reject links, traversal, durable-state
paths, files outside the application allowlist, and untracked package files
before replacement begins.

## World-data transaction

An adaptive project stores a complete immutable source snapshot and layered
baseline beside a mutable working layered package. Creation adopts compatible
layered input, invokes deterministic packed conversion on the isolated copy,
or generates the standalone structural void. Saving validates only the
project-local working package and updates its fingerprint; it does not read or
write a target. Adaptive export locks and revalidates that project, copies its
complete working package, independently validates the stage, and atomically
publishes a unique deterministic result without target access.

Target mutation begins only after a fresh capability/source-lineage check and
all compiled offline evidence. Adapter code—not target JSON or a receipt—owns
the bounded content-addressed server/client destinations and selected-
configuration path. Preview binds a real transaction UUID to exact before and
after bytes, backups, receipt, free space, activation order, and verification.
Direct CLI apply must repeat that preview's exact transaction UUID and plan
fingerprint as well as literal `IMPORT`; a confirmation supplied before an
unseen plan is never accepted. The packaged active launcher instead keeps one
preview in memory, displays it, and reads a literal untrimmed confirmation.
Exact `IMPORT` file-forces the immutable plan, exact created-directory
authority, generated activation bytes, and every copied backup; directory
entries are then forced from the deepest backup directory through its parent.
Only after that ordering succeeds does it publish and directory-force the
pending receipt. A provider that cannot force directories is refused before
transaction artifacts or target mutation. Import then publishes verified
package content, activates configuration last, and verifies
both selected packages. Failures restore the exact before state or leave a
blocking recovery receipt.

Undo independently rebuilds the successful import plan from immutable project
evidence, its exact historical export, compiled profile, durable plan, and
receipt. Mutable working state may be saved and exported after the import; it
is preserved byte-for-byte and is not substituted for that historical export.
The exact canonical list of directories absent at preview is inside the plan
fingerprint and receipt authority; its separate evidence file must match that
list exactly and every entry must be an action ancestor. Undo
refuses any changed or extra installed path before producing new artifacts;
exact `UNDO` restores/deactivates the original configuration first and then
removes only inactive recorded content-addressed files and directories. Undo
rollback restores package content first and reactivates configuration last.
Explicit `RECOVER` accepts only exact before/after transaction states and can
remove only derivable transaction staging files whose bytes match the durable
plan. Whole fingerprint containers must be absent before import and are
completely inventoried before Undo, including entries beside `package/`; empty
roots, extras, links, hard links, case aliases, and appeared destinations are
preserved and refused. Standalone origin is checked before target resolution
for import, undo, and recovery. The historical workspace transaction continues
to use its existing fixed-layout contracts.

The compiled `process-scan` offline check is fail-closed. It currently requires
a readable Linux `/proc` process view; a missing, unreadable, or unavailable
view refuses mutation rather than recording clean evidence. A still-live
userspace entry requires both readable command-line and working-directory
observations; process exits and empty kernel-thread command lines are handled
separately.

Phase 6 deliberately permits only one outstanding successful import per
project. To install a later saved/exported working state, run exact Undo for
the outstanding import first, then preview and import the new export. Import
refuses this condition before resolving or mutating the target.

There is deliberately no force-import path.

## Runtime parity

The editor spans client and server code, so duplicating the full game source in
this repository would create the same drift this repository is intended to
prevent. Instead, releases use the exact dependency revision already selected
in `runtime-provider.lock`. The release build refuses a different revision, and
dependency-update/release checks verify its published capability, required
runtime surfaces, and protocol. CI and ordinary builds never search for newer
provider work. When an assigned product objective includes runtime work, the
product manager may publish the tested independent runtime commit and adopt it
through the bounded lock/parity/full-suite workflow.

## Product-generation boundary

Legacy v1 ends at standalone release `v1.1.0`. World Builder 2 uses product and
update channel `rsc-world-editor-v2`, generic install folder `World Builder 2`,
world-source identity `target-adaptive-v1`, and UUID signed-layered projects.
Neither generation may identify the other as an
automatic update, and v2 must refuse legacy or unidentified workspaces rather
than attempting an implicit conversion.

## Adaptive map workflow

The approved World Builder 2 architecture makes each editable project derive
from the compatible target server selected at first launch, or from an explicit
standalone empty origin. Production adaptive releases contain no terrain,
world package, or static placements. Supported packed maps are converted
deterministically into isolated signed-layered projects; editing and saving
remain inside World Builder until an administrator runs the explicit
transactional import script. Compatibility means a documented capability or
repository-owned adapter, not arbitrary-server binary patching. See [World
Builder 2 Adaptive Map Workflow](ADAPTIVE-MAP-WORKFLOW.md) for the normative
contracts, phases, tests, and acceptance criteria.

The built-in packed OpenRSC adapter derives the authentic boundary,
ground-item, NPC, and scenery location set from the selected server
configuration's `based_map_data` value. Those ordinary base placements are
composed before supported project-local overlays and removals. It also reads
the selected configuration's `location_data` and exact scenery feature flags
to include active auxiliary scenery sources such as runecrafting, while
excluding present but disabled discontinued/legacy sources. Repeated anchors
inside those legacy auxiliary files retain their runtime order and deterministic
last-record precedence; strict authored project overlays still reject duplicate
anchors. These placements are copied into the isolated layered package; they
are never supplied by the release or read from only the player's initial spawn
region.

Phases 0-3 are implemented and merged on published `main` at
`dac388a32aa41754a49341e3ddcc8cc196389ab4`. Phase 3 adds a UUID registry,
atomic project creation and selection, immutable source snapshot v2, layered
adoption, contained packed conversion, deterministic standalone empty
generation, save/reopen, portability/detachment, project-only supervision, and
immediate standalone import/undo refusal. The Linux and Windows v2 launchers
now use adaptive parent-target discovery instead of a fixed config or packaged
world.

The exact locked runtime supplies the separately reviewed Phase 4 generic
layered-loader, authoring, placement, isolation, and copy-on-write capability.
Owner-run adopted-project and standalone-empty visual/edit/save/reopen
validation passed for the accepted adaptive release. Phase 5 supplies the generic identity, exact
runtime/default-catalog allowlist, no-world archive validation, and durable
Linux/Windows updater boundary. Phase 6 supplies complete adaptive export,
compiled mutation profiles, exact preview/import, durable receipt/backup
authority, reverse rollback, explicit recovery, changed-after refusal, and
exact undo for layered and packed-origin projects. Owner-native Phase 4 and
Phase 7 release validation passed for v0.2.0-alpha.1, opening its production
packaging gate while preserving the rebuild-after-acceptance rule. Publication
consumed that version-bound gate; development `main` is closed for the next
release while the immutable release tag retains the accepted marker.

## Planned custom materials

The approved World Builder 2 design for creator-supplied wall and floor images
uses project-local companion material packs, safe definition presets, explicit
PNG normalization previews, stable automatic IDs, and server-owner client
distribution. It builds on the adaptive project, capability, export, and
transaction foundation above. It does not modify the frozen v1 workflow or
patch the base texture archive. See [World Builder 2 Custom Wall and Floor
Materials](WORLD-BUILDER-2-CUSTOM-MATERIALS.md) for the normative implementation
plan, phase gates, tests, and acceptance criteria.

## Living product direction

The next intended product outcomes—detached camera control, a quiescent Builder
runtime, immediate paint previews, relative elevation, line and house tools,
share-safe creator content, broader conversion assistance, region
copy/cut/paste, and exportable snapshots—are assessed in [World Builder 2
Product Goals and Readiness](WORLD-BUILDER-2-PRODUCT-GOALS.md). That living
document records direction and dependencies without authorizing implementation
or changing the released adaptive contracts described here.

The implemented Editor boundary for ordered polygon selection, portable
non-executable bundles, dependency reports, collision plans, and project-local
copy/cut/paste is normative in [World Builder 2 Region Snapshots
v1](WORLD-BUILDER-2-REGION-SNAPSHOTS.md). Runtime marker packets, ghost previews,
authoritative interactive transactions, and persistent undo/redo are still an
independent runtime capability.

The approved next discovery objective makes explicit provider packages an
advanced interoperability path rather than an ordinary user prerequisite. It
defines automatic server-root recognition, versioned format adapters, one
canonical content graph, family-by-family reconciliation, automatic provider
generation, and a three-action launcher in [World Builder 2 Format-Aware
Discovery and Streamlined Launch](WORLD-BUILDER-2-FORMAT-AWARE-DISCOVERY.md).
The observed incomplete-scenery import is a mandatory reconciliation fixture
for that work.
