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
  and rollback.
- `release/world-builder/` preserves the frozen packed-map v1 package assets.
- `release/world-builder-v2/` contains the distinct signed-layered v2
  launchers, runtime profile, instructions, and asset provenance.
- The Core-Framework revision in `core-framework.lock` supplies a frozen
  compiled client/server runtime, integrated editor implementation, and one
  supported adapter source. It is an external generic build/runtime dependency,
  not this product's identity or target content and not part of this
  repository's manager/worker system.

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
write a target. Generic adaptive export/import remains a later transaction
phase. The historical workspace transaction continues to use its existing
validated export, offline import, backup, receipt, and undo contracts.

There is deliberately no force-import path.

## Runtime parity

The editor spans client and server code, so duplicating the full game source in
this repository would create the same drift this repository is intended to
prevent. Instead, releases use the exact dependency revision already selected
in `core-framework.lock`. The release build refuses a different revision, and
dependency-update/release checks verify its published capability, required
runtime surfaces, and protocol. Neither CI nor the World Editor manager searches for newer upstream work;
changing the pin is a separate, explicitly assigned task.

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

Phases 0-3 are implemented and merged on published `main` at
`dac388a32aa41754a49341e3ddcc8cc196389ab4`. Phase 3 adds a UUID registry,
atomic project creation and selection, immutable source snapshot v2, layered
adoption, contained packed conversion, deterministic standalone empty
generation, save/reopen, portability/detachment, project-only supervision, and
immediate standalone import/undo refusal. The Linux and Windows v2 launchers
now use adaptive parent-target discovery instead of a fixed config or packaged
world.

The exact locked runtime now supplies the separately reviewed Phase 4 generic
layered-loader, authoring, placement, isolation, and copy-on-write capability.
Owner-run adopted-project and standalone-empty visual/edit/save/reopen
validation remains pending. Phase 5 supplies the generic identity, exact
runtime/default-catalog allowlist, no-world archive validation, and durable
Linux/Windows updater boundary. Phase 6 generic export/import and the owner
validation still keep the adaptive release gate closed.

## Planned custom materials

The approved World Builder 2 design for creator-supplied wall and floor images
uses project-local companion material packs, safe definition presets, explicit
PNG normalization previews, stable automatic IDs, and server-owner client
distribution. It builds on the adaptive project, capability, export, and
transaction foundation above. It does not modify the frozen v1 workflow or
patch the base texture archive. See [World Builder 2 Custom Wall and Floor
Materials](WORLD-BUILDER-2-CUSTOM-MATERIALS.md) for the normative implementation
plan, phase gates, tests, and acceptance criteria.
