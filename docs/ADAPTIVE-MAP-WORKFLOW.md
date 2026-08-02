# World Builder 2 Adaptive Map Workflow

## Status

| Field | Value |
| --- | --- |
| Status | Approved architecture and implementation plan; Phases 0-2 implemented, later phases not started |
| Approved | 2026-08-01 |
| Product | World Builder 2 only |
| Legacy v1 | Frozen and out of scope |
| Repository reviewed | `db4d83efeb17c74415997f729b3c8faa3e686407` |
| Pinned runtime reviewed read-only | `026aab5c028aa9ecf6e78d382a4871e6ed56c3f7` from `core-framework.lock` |

Approval establishes this document as the implementation plan. It does not by
itself authorize a dependency update, release-gate change, migration of user
state, publication, deployment, or live-server work. Implementation remains
subject to the phase boundaries and AI guardrails below.

The words **MUST**, **MUST NOT**, **SHOULD**, and **MAY** are normative. A
future implementation MUST add versioned contracts rather than changing the
meaning of a contract that has already shipped.

## Product promise

World Builder must make RSC world creation approachable:

1. Put the complete `World Builder 2` folder inside a server root and launch
   it.
2. World Builder finds that server's active map and definitions. It never
   substitutes a world bundled with World Builder.
3. If the map already uses the signed-layered format, World Builder copies it
   into an isolated project.
4. If the map uses an older packed format, World Builder guides the user
   through a safe conversion into an isolated signed-layered project.
5. The editor always opens layered project data. Editing and saving never
   change the target server.
6. When ready, an administrator runs the explicit import/install script. That
   script checks that the server and client support the layered loader, installs
   the map data and configuration transactionally, and can undo the change.
7. The administrator is responsible for distributing the matching updated
   client and map content to players.

World Builder also works without a server. If it is launched from a directory
that contains no recognizable server or map, it creates a standalone empty
project at coordinate `0,0` on layer `0`. Saving creates a normal layered map
package. Import/install is unavailable because that project has no target.

“Any server” means any compatible RSC server that implements the documented
capability contract or matches a repository-owned layout adapter. World
Builder MUST make support for another layout easy to add, but it MUST NOT guess
unknown formats, run target-supplied code, or claim it can patch an arbitrary
server binary safely.

## Confirmed product decisions

- The editor has one working representation: signed-layered map data.
- Packed maps are converted as part of first-project preparation; users do not
  need to understand or choose an internal “packed project mode.”
- An already compatible layered package is copied and adopted without
  reconversion.
- The release contains application/runtime assets but no terrain, world
  package, or static NPC, scenery, boundary, or ground-item placements.
- A missing server/map outside a recognizable server root starts a standalone
  empty project at layer `0`, coordinate `0,0`.
- Standalone saving and export work normally; import/install fails clearly and
  before any filesystem mutation.
- Target-backed import requires compatible layered-loader server and client
  code. It installs verified map/configuration data but does not patch unknown
  binaries.
- Server administrators distribute the matching client/map update to players.
- Ease of use is the primary UX goal; strict validation remains fail-closed
  under the simple workflow.
- Spoiled Milk is one supported adapter/runtime source, not the product's
  identity, default world, or permanent content assumption.

## AI implementation guardrails

Every implementation phase MUST begin by reading `AGENTS.md` and running the
preflight for the checkout role. It MUST also:

1. use one focused topic branch and temporary fixtures;
2. preserve frozen v1 code, identity, workspace, release, and updater behavior;
3. treat `.core-framework/` only as the clean detached checkout named by
   `core-framework.lock`;
4. put required client, server, loader, protocol, or in-game editor work in
   Spoiled Milk first, then advance the lock only in a separately authorized
   dependency-update task;
5. preserve source-snapshot verification, offline-target checks, exact preview
   and confirmation, drift detection, backups, receipts, post-write
   verification, partial-failure rollback, changed-after-import protection,
   undo, and no-force behavior;
6. never test against user/server data or an external live checkout; and
7. never merge, release, tag, publish, deploy, or open a release gate as an
   incidental implementation step.

## Current-state discrepancy audit

The findings below were verified in this repository and, only where needed to
understand interfaces, the exact pinned dependency.

| Area | Current evidence | Required direction |
| --- | --- | --- |
| Installation root | `release/world-builder-v2/Start World Builder.sh` uses the launcher's parent as `TARGET_ROOT`; the Windows launcher uses `%~dp0..`. Import and undo do the same. | Keep this behavior. The complete World Builder folder belongs immediately inside the server root. |
| Configuration | `WorldBuilderDiscovery.DEFAULT_CONFIG` and both v2 launchers select `server/myworld.conf`. | Discover the active configuration through a versioned adapter/capability; show a simple chooser only when more than one candidate is valid. |
| Map paths | `WorldBuilderDiscovery` hardcodes server/client `Custom_Landscape.orsc` and four `MyWorld` scenery/NPC files. | Resolve logical roles from the selected adapter and actual configuration. Include every active terrain and static placement input. |
| Layout identity | `WorldBuilderDiscovery.LAYOUT_ADAPTER` is `spoiled-milk-repository-v1` and requires three Spoiled Milk configuration flags. | Put that behavior behind one built-in adapter. The product core must not assume it. |
| Existing validation | Discovery correctly checks server/client terrain equality, strict archive entry shape/size, selected definitions, and two inventories for drift. | Retain and generalize these safety properties for each adapter. |
| Release world | `scripts/package-world-builder-v2-release.sh` generates `spoiled-milk-package`, requires its exact review identity, copies it under `builder-runtime/layered-world/package`, and recursively copies broad cache/config/database trees. | Use content-neutral allowlists. Reject all shipped terrain, placements, layered packages, creator data, and generated operational state. |
| First workspace | `WorldBuilderRuntimePreparer` snapshots target files, but then copies the release-owned layered package into source and working trees and configures the isolated server to use it. | Prepare the layered baseline only from the discovered target, its conversion, or the standalone empty generator. |
| Source safety | `WorldBuilderSourceSnapshot` rejects links, additions, removals, and hash changes. Workspace preparation stages and atomically publishes without replacing an existing workspace. | Preserve these mechanisms and extend them to complete project source and conversion inventories. |
| Layered package | `WorldBuilderLayeredPackage` hardcodes Spoiled Milk package ID/version/hash, six levels, 1,782 sectors, and six placement sets. `WorldBuilderLayeredReview` repeats that identity. | Validate generic compatible package content and capability, not one world's identity or counts. |
| Editing scope | Existing layered review treats accepted source levels as a frozen reviewed baseline and mainly permits new-level authoring. | A target-derived or converted package must be authorable on its existing levels when the runtime advertises that capability. |
| Export | `WorldBuilderExporter` emits a fixed five-file packed bundle; `WorldBuilderLayeredExporter` emits the fixed-profile complete package. | Adaptive World Builder exports the complete validated working layered package plus target/conversion lineage. |
| Import | `WorldBuilderImporter` provides strong locking, offline checks, rediscovery, exact plans, backups, receipts, verification, rollback, undo, and no force. Its packed destinations are fixed. `WorldBuilderLayeredImportConfiguration` has a fixed marker, path, profile, and configuration override set. | Retain the transaction engine; drive bounded server/client package paths, configuration, offline evidence, and verification from a reviewed adapter mutation profile. |
| Projects | Launchers use one `workspace/`; project/receipt identities assume one selected configuration and fixed paths. | Add multiple durable projects with stable IDs, explicit target or standalone origin, conversion lineage, and portable relative manifests. |
| Updates | The v2 updater preserves one `workspace/` and requires a managed bundled layered manifest and `signed-layered-v1` release identity. | Preserve all projects and historical state while managing only a content-neutral application layer. |
| Pinned conversion tooling | `.core-framework/tools/layered-maps/` has reusable signed-layered codecs and validation. Its active `spoiled-milk-package` generator also applies exact Spoiled Milk removals, precedence, cleanup, reclassification, exclusions, and relocations. | Reuse generic contracts only. Conversion policy belongs to a versioned adapter and must be lossless unless a separately approved transform says otherwise. |

### Conversion mechanics found in the pinned runtime

The reviewed packed-map implementation provides useful adapter-specific test
vectors:

- terrain archive entries use names such as
  `h<plane>x<archive-x>y<archive-y>`;
- a raw sector is 48 by 48 tiles with 10 bytes per tile, exactly 23,040 bytes;
- the legacy codec maps packed planes and archive offsets into signed layered
  levels and sector coordinates;
- conversion swaps the two legacy orientation bytes, and the inverse transform
  can reproduce the original sector bytes exactly;
- signed-layered placement payloads represent boundaries, scenery, NPCs, and
  ordinary ground items; and
- the effective active placement set depends on source ordering, removals,
  same-slot replacement, cleanup, and other runtime composition rules.

These facts MUST be encoded and tested by the matching adapter. They are not
universal assumptions. Existing Spoiled Milk relocations, exclusions, or
content repair MUST NOT run for an arbitrary target.

## Product invariants

Each invariant has a stable ID so future plans and tests can cite it.

- **INV-01 — No bundled world.** A production archive MUST NOT contain map
  terrain, a layered world package, or static boundary, scenery, NPC, or
  ground-item placements.
- **INV-02 — Layered editing only.** The mutable working map opened by World
  Builder MUST be signed-layered. A packed target is converted before the
  editor opens.
- **INV-03 — Correct origin.** A working project comes only from the detected
  target map, a validated existing layered package, or the explicit standalone
  empty generator. There is no hidden sample/replacement world.
- **INV-04 — Read-only preparation.** Discovery, conversion, project creation,
  launch, edit, save, close, and reopen MUST NOT mutate the target.
- **INV-05 — Immutable evidence.** Every target-backed project retains an exact
  immutable source snapshot of the detected active map, configuration,
  definitions/capabilities, and conversion evidence.
- **INV-06 — Isolated working state.** All mutable map data, configuration,
  database, credentials, logs, PIDs, caches, and server-generated state remain
  inside the selected project.
- **INV-07 — Visible incompatibility.** Unknown layouts, ambiguous configs,
  map disagreement, definition mismatch, unsupported formats, missing loader
  code, and unrepresentable conversion input fail with a useful report.
- **INV-08 — No silent loss.** Conversion MUST NOT silently drop, approximate,
  repair, relocate, merge, or overwrite any terrain or placement data.
- **INV-09 — Determinism.** Identical declared inputs and tool versions produce
  byte-identical conversion output, manifests, reports, and hashes. Absolute
  paths and timestamps do not affect identities.
- **INV-10 — Explicit target mutation.** Only the target-backed import/install
  or undo command may change server/client data, after exact preview and
  confirmation.
- **INV-11 — Bounded authority.** Target metadata may select a compiled adapter
  but cannot authorize arbitrary paths, commands, configuration edits, or
  binary replacement.
- **INV-12 — Transaction safety.** Import retains offline checks, target drift
  detection, backups, receipts, verification, rollback, undo,
  changed-after-import refusal, and no force option.
- **INV-13 — Standalone cannot import.** A standalone empty project's import
  and undo commands fail before target discovery, locking, backup, or mutation.
- **INV-14 — Additive projects.** Creating, converting, selecting, or replacing
  a project MUST NOT delete or overwrite another project.
- **INV-15 — Durable updates.** Updates preserve projects, exports, backups,
  receipts, diagnostics, historical v2 workspace state, and unknown unmanaged
  paths byte-for-byte.
- **INV-16 — Frozen v1.** v2 never identifies, opens, converts, imports,
  migrates, or cross-updates a v1 workspace or release.

## Non-goals

The first adaptive release does not include:

- support for arbitrary unknown server formats without an adapter;
- execution or download of target-supplied adapter code;
- automatic patching/replacement of unknown server or client binaries;
- dynamic runtime state, player database, quest, scheduler, plugin-generated
  spawn, or executable transition conversion;
- automatic data repair, relocation, exclusion, or approximation;
- automatic project rebase/merge after a server update;
- reverse conversion of edited layered maps back to legacy packed maps;
- automatic attachment/import of a standalone empty project to a server;
- structure selection, prefab packaging, or copy/paste commands—the project
  model MUST leave room for those future features, but they are later work;
- automatic player-client distribution; or
- implementation of the separate custom-materials plan.

## User workflows

### A. Edit an existing legacy map

```text
Place folder in server root
        |
        v
Launch -> detect config/map/definitions -> compatibility summary
        |
        v
Read-only packed conversion -> parity validation -> isolated layered project
        |
        v
Edit / save / close / reopen (project only)
        |
        v
Export -> Import preview -> administrator confirms IMPORT
        |
        v
Install server/client map + config -> verify -> receipt -> optional UNDO
```

The normal path SHOULD require only one confirmation before creating the
project. Technical details remain available under an expandable report.
Conversion blockers MUST explain the exact file/record and safe next action.

### B. Edit an existing layered map

Discovery validates the active package and server/client loader agreement.
Project creation copies it into immutable baseline and mutable working trees.
No conversion runs. The runtime MUST advertise that existing levels and all
required placement families are authorable; “loader can read it” alone is not
enough.

### C. Start an empty standalone world

If the World Builder folder's parent does not contain recognizable server
evidence or an active map, the launcher creates or reopens a standalone
project. Its first launch:

1. clearly labels the project **Standalone — no server attached**;
2. creates a generated empty signed-layered baseline with world space
   `global`, layer `0`, no placements, and only the canonical void coverage
   needed for the editor to address coordinate `0,0`;
3. starts the Builder camera/player at `0,0` on layer `0`;
4. uses the versioned default Builder definition/rendering catalog;
5. materializes and validates terrain sectors as the creator authors them;
6. saves and exports a normal complete layered package; and
7. disables Import and Undo with the message that the project has no compatible
   target server.

The empty baseline is generated inside the project. Its map bytes MUST NOT be
stored in or copied from the release archive. Canonical void means “nothing
authored”; it is not sample terrain or creator content.

A recognizable server with a missing, invalid, or ambiguous configured map is
different from a directory with no server. It MUST show the compatibility
failure prominently rather than pretending its server map was loaded. For ease
of use, that screen MAY also offer **Create a separate standalone empty
project**, but the project remains visibly unattached and cannot import.

### D. Reopen, move, or replace projects

World Builder records projects by UUID, not display name or absolute server
path. Later launches reopen the last valid project. A simple project chooser
allows multiple server-derived or standalone projects.

Moving the complete World Builder folder or a complete closed project preserves
it because internal manifests use relative paths. If the original target moved
or changed, the project MAY still open, edit, save, and export in a prominent
detached state. Import remains blocked until fresh discovery proves exact
target lineage. A server update creates a new project; it never silently
rebases an old one.

### E. Install a finished target-backed map

The Import script is the only supported path that changes the server/client.
It:

1. verifies the project's source, conversion, working package, and export;
2. rediscovers the target and rejects drift;
3. verifies that target server and client binaries already advertise the exact
   layered-loader, package-format, protocol, and definition capabilities;
4. shows exact server/client package destinations and configuration changes;
5. requires the administrator to confirm `IMPORT`;
6. backs up every affected file and records expected absence;
7. installs the package and related data, changes activation/configuration
   last, and verifies both server and client selections;
8. writes a durable receipt and supports exact `UNDO`; and
9. reminds the administrator which client/map identity must be distributed to
   players.

Import MAY install adapter-approved map packages, client map data, and bounded
configuration. It MUST NOT overwrite arbitrary customized binaries. If the
target lacks loader code, it fails with the exact compatible runtime requirement
and directs the administrator to upgrade the server/client first.

## Installation and project directories

### Installed product

```text
server-root-or-ordinary-parent/
  World Builder 2/
    Start World Builder.sh
    Start World Builder.cmd
    Import Map Changes.sh
    Import Map Changes.cmd
    Undo Last Map Import.sh
    Undo Last Map Import.cmd
    builder-runtime/                  # replaceable, content-neutral application
    launcher/
    projects/                         # durable creator state
    project-registry.json             # durable
    active-project.json               # durable, atomically replaced
    workspace/                        # preserved historical v2-alpha state
    updates/                          # bounded update transaction state
```

If the parent is a compatible server root, it is the default target. If not,
World Builder runs standalone. Files are not scattered directly into the
server root.

### Content-neutral application layer

Packaging MUST use explicit allowlists, not broad recursive copies followed by
a blocklist. The managed runtime MAY include:

- release-marked tools, client/server code, libraries, schemas, UI resources,
  JRE, and platform natives;
- a fresh Builder-only database seed and configuration template;
- versioned layout adapters and conversion codecs;
- non-world rendering assets; and
- a versioned default definition/rendering catalog needed by standalone empty
  projects.

Definitions describe available tiles, walls, objects, NPC types, and items;
they are not placements. Their inclusion does not permit a bundled world.
Target-backed projects SHOULD use the detected target's compatible definitions
and client assets so custom server content is represented accurately. The
adapter MUST prove server/client/Builder agreement before editing.

Packaging MUST reject terrain archives, static placement data, active layered
packages, project/export/backup/receipt data, credentials, logs, PIDs,
downloaded runtime state, generated ban lists, and any database except the
explicit Builder seed. Tests MUST include renamed payloads so a world cannot
ship merely by avoiding a familiar filename.

### Project layout

```text
World Builder 2/
  projects/
    <project-uuid>/
      project.json
      discovery/
        report.json
      source/                         # immutable by contract
        snapshot-manifest.json
        original/                     # exact target inputs, or empty descriptor
        layered-baseline/
          package/                    # adopted, converted, or generated empty
        conversion/
          plan.json                   # absent for adopted packages
          report.json
      working/
        runtime/                      # isolated client/server and generated state
        layered-world/
          package/                    # mutable authored map
      exports/
      backups/
      receipts/
      diagnostics/
      logs/
      run/
```

| Origin | `source/original` | Layered baseline | Import availability |
| --- | --- | --- | --- |
| Existing packed target | Complete adapter-declared packed terrain, placement, config, definition, and capability evidence | Deterministic conversion output | Only for the exact compatible target after loader checks |
| Existing layered target | Exact active layered package plus config/definition/capability evidence | Verified copy of active package | Only for the exact compatible target |
| Standalone empty | `empty-world-v1.json` plus default catalog identity | Project-generated empty layer 0 | Never; no target binding |

All manifest paths are normalized forward-slash relative paths. Parsers MUST
reject absolute paths, `..`, empty segments, links, hard-link escapes, case
collisions, Windows device names, alternate-data-stream syntax, trailing dots
or spaces, and platform-invalid characters. Display placeholders such as
`<transaction-id>` MUST never be passed to filesystem path APIs.

No operation deletes or replaces an existing project. Registry and active
selection updates use write-new, flush where supported, verify, and atomic
replace semantics.

## State model

| State | Edit/save | Export | Import | Meaning/recovery |
| --- | --- | --- | --- | --- |
| Discovery blocked | No | No | No | Unsupported, invalid, or ambiguous target; show report and safe next action |
| Project staging | No | No | No | Copy/conversion not yet verified; remove only contained stage on failure |
| Ready/attached | Yes | Yes | Yes after complete preflight | Exact target still matches source lineage |
| Ready/detached | Yes | Yes | No | Target missing/moved/drifted; project remains useful and portable |
| Ready/standalone | Yes | Yes | No | Empty-origin project has no target by design |
| Source corrupt | No | No | No | Restore whole project from backup; never rebuild source silently |
| Import recovery required | No | No | No new transaction | Complete verification or rollback using durable journal |

## Adapter and capability model

World Builder uses a small generic core plus versioned repository-owned
adapters. A well-known target descriptor SHOULD be
`server/world-builder-capabilities.json`. The descriptor selects an adapter and
declares versioned facts; it is not executable and cannot authorize arbitrary
writes.

A built-in probe MAY recognize a narrowly defined common layout when the
descriptor is absent. This provides low-friction onboarding for existing
servers. If zero layouts match, discovery explains how to add/support an
adapter. If multiple configurations or layouts match, the UI presents a simple
choice and does not guess by filename, timestamp, or alphabetical order.

### Capability contract

`world-builder-target-capability-v1` MUST use a strict exact-key schema and
include:

- stable contract, adapter, server build, client build/protocol, map-format,
  and definition-catalog identities;
- configuration candidates expressed as adapter-understood roles;
- discovery capabilities for packed or layered sources;
- authoring capabilities, including editing existing layered levels and all
  supported placement families;
- loader package schema and encoding versions;
- install capabilities for server and client package/configuration roles;
- minimum offline evidence; and
- a named bounded mutation profile.

The adapter independently validates every declared fact. The descriptor cannot
weaken path containment, file limits, offline checks, or mutation boundaries.
A target that can load layered terrain but cannot edit existing levels or
represent required placements is not editing-compatible.

### Initial adapters

1. A successor to `spoiled-milk-repository-v1` handles the reviewed legacy
   packed layout and its exact map-composition rules without leaking those
   rules into the generic core.
2. A generic signed-layered adapter validates an active
   `layered-world-package-v1` without hardcoding package ID, version, hash,
   levels, or counts.
3. Standalone empty mode uses a built-in `empty-world-v1` origin generator, not
   a target adapter.

Adding support for another server SHOULD require one adapter, capability
fixtures, conversion/parity fixtures when packed, and one bounded install
profile. It MUST NOT require copying that server's world into this repository.

## Versioned contracts

### Discovery report

`discovery-report-v2` is safe to create even when discovery fails. It records:

- tool/schema version and adapters considered;
- descriptor presence/hash and configuration candidates;
- selected active configuration and its hash;
- active packed/layered representation and every logical source role;
- server/client map agreement;
- definitions, assets, protocol, loader, and authoring identities;
- file presence, size, SHA-256, safe relative path, and role;
- strict format/count/range/coverage/composition results;
- supported user operations; and
- stable blocker/warning codes with plain-language next actions.

Absolute target path may appear as display metadata but is excluded from the
source fingerprint. Discovery never inventories credentials, databases, logs,
player data, or unrelated server files.

### Project and source snapshot

`project-manifest-v2` records:

- stable random UUID and display name;
- origin: `target-packed`, `target-layered`, or `standalone-empty`;
- adapter/capability and selected configuration identities, when target-backed;
- source, layered-baseline, definitions/runtime, conversion, and working
  fingerprints;
- optional target locator, excluded from project identity;
- relative project paths and supported operations; and
- creation tool/runtime version.

`source-snapshot-v2` inventories the discovery report, target capability and
configuration evidence, every active world input, required absence,
definitions/runtime evidence, conversion plan/report, and immutable layered
baseline. It rejects extra/missing/changed files and all unsafe entries before
launch, save commit, export, import preview/apply, or undo.

A standalone manifest has no adapter, target locator, target fingerprint,
selected configuration, import profile, or receipt lineage. It binds the exact
empty generator and default definition/runtime identity instead.

### Conversion plan and report

`conversion-plan-v1` binds source fingerprint, adapter conversion profile,
every input role/hash, coordinate mapping, placement composition rules,
definition identity, output package schema, and tool version.

`conversion-report-v1` records:

- plan hash and deterministic output fingerprint;
- terrain entries read/written and exact reverse results;
- placement counts by family, level, source role, and definition ID;
- precedence, removals, replacements, and collision decisions;
- stable placement-ID derivation;
- coordinate, direction, roam-bound, amount, respawn, ID, and terrain-coverage
  validation;
- every unknown, loss, approximation, repair, or parity delta; and
- blocker provenance down to relative path and record index/key.

Any unknown, loss, approximation, unapproved repair, or parity delta makes
project creation fail. A warning cannot downgrade a blocker.

### Export

An adaptive export is a new schema version containing the complete working
layered package, not a fixed packed-file list. It binds:

- project UUID and origin;
- source, baseline, conversion, definitions/runtime, and working fingerprints;
- adapter/capability/install-profile identity when target-backed;
- every package file's safe relative path, role, presence, size, and hash;
- validation report hashes; and
- a deterministic export fingerprint.

Export holds the project lock, verifies source and working state, stages a new
directory, validates it independently, and publishes atomically. It never
overwrites an earlier export.

### Target mutation plan

Only compiled adapter code can create a `target-mutation-plan-v1`. It lists:

- exact target server/client relative destinations;
- expected before state, including absence;
- proposed after bytes/hashes;
- exact configuration old/new values;
- package activation order;
- loader/protocol/definition/client requirements;
- offline evidence and required free space;
- backup and receipt paths with actual transaction IDs; and
- post-write and rollback verification.

Target data can select a named profile but cannot supply raw writable paths or
commands.

### Receipt and undo

Adaptive imports require a new receipt schema. It MUST NOT reinterpret the
checked-in packed v1 schema or the current layered receipt mode. It records:

- transaction UUID/type/status;
- project/export/mutation-plan hashes;
- adapter/capability and path-independent target lineage;
- selected configuration and all offline evidence;
- every before/after presence and hash;
- verified backup paths/hashes;
- configuration and activation changes;
- verification results; and
- recovery or reverted-transaction identity.

Undo requires exact `UNDO` confirmation, a successful unreverted receipt, the
same target lineage, valid backups, offline evidence, and exact installed-after
hashes. A changed path blocks undo. There is no force option.

## Algorithms

### Read-only target discovery

1. Canonicalize the World Builder folder and its parent without following
   links.
2. Look for bounded server evidence: the well-known descriptor and exact
   built-in adapter probe roots. Do not scan the machine or recurse through an
   arbitrary parent.
3. If there is no recognizable server evidence, select standalone empty mode.
4. If server evidence exists, parse the strict descriptor or run only matching
   built-in probes. An unsupported/malformed server produces a compatibility
   report; it does not silently become target-backed empty mode.
5. Enumerate bounded configuration candidates. Select only a single proven
   active configuration or explicit user choice.
6. Resolve the active map semantically through that configuration: server and
   client packed copies or a layered package.
7. Inventory every active terrain, base/overlay placement, removal,
   composition-order, definition, asset, protocol, and capability role.
8. Strictly parse terrain and all boundary, scenery, NPC, and ground-item
   inputs. Validate coordinates, directions, amounts, respawn, roam bounds,
   definition references, duplicates, removals, precedence, and coverage.
9. Prove server/client map selection and definition/protocol agreement.
10. Re-read configuration, descriptor, identities, sizes, and hashes. Any
    change restarts discovery.
11. Emit deterministic JSON and a plain-language summary. Nothing has been
    written to the target or project registry.

### Target-backed project creation

1. Require a successful discovery report and simple `CREATE` confirmation.
2. Allocate a UUID and unique contained staging directory under `projects/`.
3. Copy every adapter-declared source role read-only; preserve required
   absence; verify each copy and reverify the target.
4. If the source is layered, validate and copy it as the immutable baseline.
5. If the source is packed, run the conversion algorithm below into the
   immutable baseline. Failure publishes no project.
6. Copy the verified layered baseline into mutable working state.
7. Build an isolated runtime using content-neutral application assets and the
   project's compatible definitions/assets. Never substitute release terrain.
8. Validate the complete project, source inventory, runtime configuration,
   package, and safe paths.
9. Atomically publish the UUID directory, then atomically update registry and
   active-project selection.

Target hashes before and after creation MUST match. No existing project may
change.

### Packed-to-layered conversion

1. Bind the conversion to the immutable copied source, adapter/profile,
   definitions, configuration, and tool version. Do not read target files
   during conversion.
2. Parse all active packed terrain and placement inputs in declared composition
   order, retaining provenance for every record and removal.
3. For each terrain entry, decode plane/archive coordinates through the
   adapter, derive signed level/sector coordinates, validate identity/range/
   uniqueness/size, transform into native layered bytes, then immediately
   reverse and require the original bytes exactly.
4. Normalize the effective boundaries, scenery, NPCs, and ground items while
   preserving coordinates, directions, IDs, amounts, respawn, and roam bounds.
5. Derive stable placement IDs from adapter ID, source fingerprint, source
   role, normalized record identity, and occurrence index. A collision blocks
   conversion.
6. Group records by signed level. Require placement terrain coverage and valid
   definitions. Do not invent missing terrain or drop unsupported records.
7. Canonically order levels, sectors, placement sets, records, keys, and paths.
   Exclude host paths, separators, timestamps, and random values from hashes.
8. Reverse terrain conversion and compare exact bytes. Normalize the output
   placements back to the adapter's effective-source model and require zero
   additions, removals, moves, replacements, or collision changes.
9. Run the generic package validator and load every payload through the same
   decoder contract used by the isolated runtime.
10. Write the plan, report, baseline package, and hashes into contained staging.
    Phase 2 atomically publishes only that standalone conversion result; Phase
    3 will invoke it inside project staging. Only complete success may permit a
    later project publication and editor launch.

The initial profile is exact-only. A future exclusion, relocation, repair, or
approximation requires its own named/versioned transform, exact preview,
tests, and product approval. There is never a generic force flag.

### Standalone empty project creation

1. Confirm that no recognizable target is selected and label the origin
   `standalone-empty`.
2. Allocate a project UUID and stage `empty-world-v1.json` binding layer `0`,
   origin `0,0`, the empty generator, and default catalog/runtime hashes.
3. Generate a canonical signed-layered baseline with no authored terrain or
   placements beyond minimal void addressability required by the runtime.
4. Validate it, copy it to working state, and prepare an isolated runtime whose
   initial editor location is exactly layer `0`, coordinate `0,0`.
5. Publish the project and registry atomically.
6. On save, materialize changed sectors/placements and validate a standard
   layered package. Export behaves normally.
7. Import and undo inspect `origin` first and fail with `NO_TARGET` before any
   target path is resolved or lock is acquired.

This requires the runtime/package contract to support authoring from canonical
void. If the current schema requires at least one sector, the generator MAY
create exactly one canonical void sector containing `0,0`; it remains generated
structural state, not a shipped map.

### Import/install

1. Reject standalone origin before target access.
2. Lock the target-backed project; verify immutable source, conversion,
   working package, and export.
3. Canonicalize and rediscover the target through the same adapter/capability.
4. Verify exact source lineage and reject configuration, map, definition,
   binary capability, or client/server drift.
5. Acquire every adapter-required offline signal: relevant ports, process/lock
   evidence, and configuration locks. One port check is not assumed sufficient.
6. Build a bounded mutation plan and show an exact human/JSON preview using
   actual safe transaction IDs and paths.
7. Require exact `IMPORT` confirmation.
8. Write a durable pending receipt and verified backups before first mutation.
9. Stage and verify content-addressed server/client package data on the target
   filesystem.
10. Change package activation/configuration last.
11. Verify every byte, semantic config value, active package hash, and
    server/client selection.
12. Finalize the success receipt and display the exact client/map identity the
    administrator must distribute.
13. On any failure, roll back in reverse safe order and verify the complete
    before inventory. If rollback cannot verify, retain recovery state and
    block new transactions.

The adapter SHOULD install a package under a content-addressed destination such
as `.../packages/<package-fingerprint>/` and switch a separate active reference.
The adapter determines the exact bounded path. Existing packed source maps are
backed up and retained unless a separately approved mutation plan explicitly
needs a different reversible treatment.

### Isolated validation before import

Automated project validation MUST prove:

- immutable inventories and all hashes;
- package schema, encoding, paths, definitions, protocol, and server/client
  agreement;
- terrain reverse parity and effective placement parity;
- coverage, collision, bounds, spawn, and duplicate invariants;
- isolated server readiness and client connection using project-owned state;
- representative access to every level/sector and placement family;
- save, journal commit, orderly close, reopen, and unchanged target inventory;
  and
- deterministic export without reading the target.

Owner-run validation before a release SHOULD cover software and OpenGL
rendering, representative routes and levels, terrain/scenery/boundary
collision, NPC roam behavior, ground-item visibility/respawn, edit/save/reopen,
and client reconnect. It records package/tool hashes in a project validation
receipt. It never starts, stops, or modifies the target server.

## Failure and recovery

| Failure | Required result |
| --- | --- |
| No recognizable server/map | Create or reopen a clearly labelled standalone empty project; never imply a server was loaded. |
| Server detected but configured map missing/invalid | Show compatibility failure; MAY offer a separate unattached empty project. |
| Unknown adapter/capability | Show supported layouts and exact missing/unsupported evidence; write no target/project state. |
| Multiple valid configs/maps | Present a simple chooser; never guess or persist selection before confirmation. |
| Discovery changes mid-read | Discard mixed observations and retry; copy nothing. |
| Project copy/conversion fails | Delete only the contained unique stage; preserve target, registry, active project, and all existing projects. |
| Unknown/unrepresentable conversion record | Name the source role/path/record and stop; no partial project or approximate output. |
| Immutable source changes | Refuse launch/save/export/import; never reconstruct silently from target or working state. |
| Isolated runtime cannot load baseline | Keep diagnostics, publish no ready project, and leave target unchanged. |
| Target moves or drifts | Mark project detached; allow isolated edit/export, but block import. |
| Standalone import/undo | Fail immediately with `NO_TARGET`; no target path, lock, backup, or receipt is created. |
| Loader/server/client capability missing | Refuse import and identify the exact compatible runtime required; do not patch binaries. |
| Import fails before mutation | Record a safe failed/no-change result; target remains exact. |
| Import partially fails | Restore verified backups and activation in safe order; verify before state. Block new transactions if recovery is uncertain. |
| Undo sees changed-after-import data | Refuse before mutation and list changed paths; no force mode. |
| Update cannot support a selected project | Restore/retain the old managed layer and preserve every project; do not rewrite compatibility metadata. |

Errors MUST contain a stable code, operation, project UUID when available,
adapter/capability version, safe relative path or record provenance, expected
and observed facts, whether mutation occurred, and one safe next step. They
MUST redact credentials and must not tell users to delete a project or backup.

## Project identity, drift, and portability

- A project UUID is permanent and random. Its display name may be changed and
  need not be unique.
- Source identity hashes adapter/capability, configuration, world inputs,
  definitions/runtime, and conversion evidence. It never hashes an absolute
  target path.
- A target path is only a last-known locator. Reattachment requires complete
  lineage agreement.
- Replacing the active project changes one atomic selection pointer. It does
  not replace project data.
- A server update creates a fresh target-derived project. Automatic rebase or
  file copying is forbidden.
- A closed complete project can be moved or backed up. Detached projects remain
  editable/exportable but not importable.
- A standalone project remains standalone even if later moved into a server
  root. Future explicit attachment/prefab workflows require a separate design.

## Backward compatibility and releases

### Frozen v1

`release/world-builder/`, the v1 updater, v1 identity, schemas, workspaces, and
tests remain unchanged. Adaptive v2 cannot open, convert, or update v1.

### Existing v2-alpha workspace

The historical `workspace/` uses a release-owned reviewed layered source. A
future adaptive updater MUST preserve it byte-for-byte and MUST NOT call it
target-derived. The safest compatibility policy is bounded legacy
open/export/finish/undo using its matching runtime when available, with no
adaptive attachment or migration. If that runtime cannot be retained safely,
the update refuses and explains how to preserve the old installation.

`release/world-builder-v2/RELEASE-READY` and
`docs/releases/world-builder-v2-v0.1.0-alpha.1-validation.md` remain historical
evidence. Adaptive implementation does not reinterpret them as approval of a
new release design. The current marker is not version-scoped, so the release
workflow MUST replace or constrain it with an exact adaptive-version and
candidate-commit acceptance record before adaptive implementation can be
packaged. Until then, no adaptive release is authorized by that historical
marker.

### Packaging

The future v2 packager stops invoking the content-specific package generator
and accepting a bundled layered-package input. It uses allowlists and a new
world-source identity such as `target-adaptive-v1`.

It retains every existing safeguard: exact clean commit from
`core-framework.lock`, production client marker, reproducible LWJGL natives,
asset provenance, safe archive inventory, repository independence, channel
isolation, and archive verification. The release gate stays closed until a
manager separately validates a completed implementation.

### Updating

Linux and PowerShell v2 updaters remain behaviorally equivalent. Their managed
manifest owns only replaceable application files. It rejects ownership of
`projects/`, project registry/selection, `workspace/`, exports, backups,
receipts, diagnostics, or unknown files.

The updater preserves the current v2 prerelease channel rules: it never selects
v1, drafts, malformed tags, or downgrades. After replacement it verifies the
selected project against the new runtime. Incompatibility restores the prior
managed layer without touching durable state.

## Documentation impact

Every listed file MUST be reviewed during implementation even if review
concludes that only a link is needed.

| File | Required eventual update |
| --- | --- |
| `README.md` | Explain the simple detect/adopt-or-convert/edit/install path, standalone empty mode, compatibility adapters, and frozen v1. Remove bundled-world framing. |
| `CONTRIBUTING.md` | Refer to selected-project logs rather than only `workspace/logs`; retain redaction and generated-state rules. |
| `SOURCE-PROVENANCE.md` | Separate pinned runtime code/assets from target-derived world data and standalone-owned tools. Stop describing a pinned map cache as authoritative v2 input. |
| `CHANGELOG.md` | Add an adaptive workflow entry only when implemented; keep release history unchanged. |
| `docs/ARCHITECTURE.md` | Replace single-workspace/five-file wording with adapters, target/empty origins, conversion baseline, project registry, and generic transactions; link this plan after approval. |
| `docs/AUTO-UPDATES.md` | Add all adaptive durable paths and historical workspace policy; update the content-neutral v2 identity. |
| `docs/DEVELOPMENT.md` | Document adapter fixtures, empty projects, deterministic conversion, no-world package tests, and repository/runtime boundaries. |
| `docs/RELEASING.md` | Remove bundled reviewed-package prerequisites; add no-world archive inspection, compatibility matrix, empty-mode checks, and adaptive owner validation. |
| `docs/WORLD-BUILDER-2-CUSTOM-MATERIALS.md` | Build materials on project UUIDs, origin, adapter capability, and definition identity. Use project-relative inbox/pack paths and extend whatever adaptive export/receipt schema is current rather than assuming the old workspace/schema numbers. |
| `tools/world-builder/README.md` | Replace fixed config/workspace/five-file/profile examples with discover, project selection, conversion, empty creation, layered export/install, and recovery commands. |
| `release/world-builder-v2/README.txt` | Give nontechnical target-backed and standalone instructions, supported-layout errors, admin/client responsibility, import/undo, and no bundled-world statement. |
| `release/updater-v2/README-AUTO-UPDATE.txt` | Explain project durability, adaptive identity, historical workspace handling, and post-update compatibility validation. |
| `release/world-builder-v2/ASSET-SOURCES.txt` | Remove eliminated world/package provenance and continue documenting every shipped runtime/default-catalog asset. |
| Historical alpha validation | Keep unchanged; a new adaptive release gets a new record. |

`AGENTS.md`, `AI_WORKSPACE.md`, `docs/AI-WORKSPACES.md`, and frozen-v1 package
instructions were reviewed. Their collaboration or v1 preservation rules do
not need adaptive-map changes.

### Custom materials dependency

The approved custom wall/floor materials plan remains a product plan, but its
storage and transaction foundation becomes this adaptive project model:

- a material belongs to a project UUID and definition/capability fingerprint;
- target-backed projects use compatible target catalogs; standalone projects
  use the versioned default catalog;
- materials extend the complete layered export and bounded install receipt;
- no custom image, definition, or creator content ships in a release; and
- server owners continue to distribute matching client/material content.

Its safe presets, PNG normalization preview, and automatic stable-ID decisions
remain unchanged. Implementation must revise its old `workspace/` paths and
proposed schema numbers after adaptive contracts are final.

## Test impact

| Existing test | Required change |
| --- | --- |
| `test-world-builder-discovery.py` | Replace fixed `myworld.conf`, six files, and one layout with descriptor/fallback, ambiguity, packed/layered, all placements/definitions, unsupported-server, no-server, and drift fixtures. |
| `test-world-builder-runtime-preparation.py` | Test target-layered adoption, packed conversion baseline, standalone empty layer 0/origin 0,0, project registry, content-neutral runtime, generated-state confinement, and target preservation. |
| `test-world-builder-supervision.py` | Add selected-project/origin/capability/detached cases; retain loopback, source verification, locking, journal, and orderly shutdown. |
| `test-world-builder-export.py` | Replace fixed five-file output with deterministic complete layered exports for adopted, converted, and empty origins. |
| `test-world-builder-import.py` | Generalize destination/config/marker behavior through mutation profiles; add standalone immediate refusal while retaining exact preview, confirmation, offline, drift, backups, receipts, injected failure, rollback, undo, changed-after, Windows paths, and no-force tests. |
| `test-world-builder-v2-release.py` | Prove zero terrain, placement, layered package, creator, or operational data—including renamed payloads—while preserving lock, dirty dependency, marker, native, provenance, archive, and independence checks. |
| `test-world-builder-v2-updater.py` | Replace managed bundled-package identity with adaptive identity and preserve multiple projects, registry/selection, historical `workspace/`, unknown paths, and rollback byte-for-byte on Linux and PowerShell. |
| `test-world-builder-product-generations.py` | Update v2 world-source expectations; retain strict v1/v2 identity, channel, archive, install-folder, and workspace isolation. |
| `test-world-builder-ai-workspaces.py` | Preserve workflow coverage; update only future release-gate fixtures as needed. |
| `test-world-builder-project-independence.py` | Preserve standalone source ownership and exact clean dependency use; extend only for changed packaging/CI paths. |
| `test-world-builder-updater.py` | Frozen v1; unchanged. |

Dedicated `test-world-builder-adaptive-contracts.py`,
`test-world-builder-adaptive-discovery.py`, and
`test-world-builder-packed-conversion.py` suites now cover Phases 0-2. Project
registry/lifecycle, standalone empty generation, layered adoption, and
cross-platform adaptive transactions remain later dedicated suites.

## Existing implementation-file impact

| File/group | Planned responsibility |
| --- | --- |
| `WorldBuilderDiscovery*` | Generic read-only orchestration, adapter selection, standalone detection, deterministic compatibility report, and double inventory. |
| `WorldBuilderPackedCoordinateCodec.java`, `WorldBuilderPackedTerrainCodec.java` | Adapter-owned exact plane/archive/placement coordinate mapping and reversible terrain orientation conversion. |
| `WorldBuilderPackedConversionSource.java`, `WorldBuilderPackedConversionModel.java`, `WorldBuilderPackedConverter.java` | Verify the isolated inventory, compose provenance-rich placements, write canonical package/contracts, prove parity, and publish only a complete atomic result. |
| `WorldBuilderGenericLayeredPackage.java`, `WorldBuilderRawLayeredTerrainCodec.java`, `WorldBuilderPlacementSemantics.java` | Content-neutral package/runtime decoding validation and shared semantic placement comparison without fixed world identity. |
| `WorldBuilderProjectSource.java` and `project-manifest-v1.schema.json` | Preserve old meaning; add v2 origin/project/source contracts. |
| `WorldBuilderRuntimePreparer.java` | Stage adopted, converted, or generated-empty layered baselines; create isolated project working runtime; never copy release world data. |
| `WorldBuilderSourceSnapshot.java` | Verify complete immutable original, baseline, compatibility, and conversion evidence. |
| `WorldBuilderProcessSupervisor.java` | Run one selected layered project, start empty mode at layer 0/origin 0,0, retain loopback/process/source locks, confine generated state. |
| `WorldBuilderConfigWriter.java` | Render only adapter-approved isolated and target profiles; retain duplicate-key and exact semantic verification. |
| `WorldBuilderExporter.java`, `WorldBuilderExportBundle.java`, `WorldBuilderExportManifest.java` | Preserve historical readers; export complete generic layered projects with lineage. |
| `WorldBuilderImporter.java`, `WorldBuilderTargetOfflineLease.java` | Keep transaction engine; consume bounded adapter server/client mutation and offline plans; reject standalone before target access. |
| `WorldBuilderImportReceipt.java` and receipt schemas | Preserve historical readers and add a strict adaptive receipt. |
| `WorldBuilderLayeredPackage.java`, `WorldBuilderLayeredReview.java` | Separate generic package validation from the hardcoded Spoiled Milk identity/counts. |
| `WorldBuilderLayeredDraftWriter.java`, `WorldBuilderLayeredTerrainDraftJournal.java` | Author target-derived existing levels and empty-generated sectors under project lock when runtime capability exists. |
| `WorldBuilderLayeredExporter.java`, `WorldBuilderLayeredImportConfiguration.java` | Use generic export/mutation contracts rather than fixed package/profile/path/marker/config. |
| `WorldBuilderJsonDocuments.java`, `WorldBuilderHashes.java` | Supply bounded canonical JSON, safe paths, and deterministic hashing without weakening exact parsing. |
| `WorldBuilderCli.java` | Expose simple auto-launch plus advanced report/project/convert/validate/export/import/undo commands. No force command. |
| v2 release/updater launchers | Keep parent-target detection; add safe no-server empty mode and active-project selection; remove fixed config/package/workspace. |
| `release/world-builder-v2/world-builder-runtime.conf` | Become a content-neutral isolated template; target/empty origin supplies world and compatible catalog selections. |
| `scripts/package-world-builder-v2-release.sh` | Explicit no-world allowlists and adaptive identity while retaining all release safeguards. |
| v2 Linux/PowerShell updater | Preserve adaptive/historical state and validate content-neutral managed identity equivalently. |

## Phased implementation plan

No phase begins until this document is explicitly approved. Each phase gets a
focused branch, temporary fixtures, checkpoints, complete relevant tests, and a
separate reviewable handoff.

### Phase 0 — contracts and adversarial fixtures

- Add strict schemas for capability, discovery report, project/registry/source,
  conversion plan/report, adaptive export, mutation plan, and receipt.
- Add canonical JSON, portable relative-path, stable error-code, and bounded
  inventory helpers.
- Add synthetic temporary packed, layered, malformed-server, no-server, and
  Windows/Linux path fixtures.

Gate: exact-key/version parsing, limits, canonical serialization vectors, safe
paths, and contract compatibility rules pass without runtime/target writes.

### Phase 1 — adaptive discovery

- Refactor discovery behind `WorldBuilderLayoutAdapter` and a registry.
- Add target capability parsing, narrow built-in packed probe, generic layered
  adapter, compatibility reports, and standalone/no-server classification.
- Inventory all terrain and static placement families plus definitions/runtime
  agreement.

Gate: descriptor/fallback, zero/multiple configs, packed/layered/no-server,
malformed-server, server/client mismatch, definitions, unsafe paths, and
mid-discovery drift pass. Discovery never mutates target or project state.

### Phase 2 — deterministic packed conversion

Implementation status: complete in this repository. The advanced
`convert-packed` boundary accepts only an exact isolated evidence copy tied to
a compatible descriptor-backed Phase 1 report. It rejects the live reported
target, fallback-only evidence, links, hard links, missing/extra files, and
hash drift. It emits `package/`, `conversion-plan.json`, and
`conversion-report.json` through same-filesystem atomic staging. It does not
create a project or runtime and does not read or mutate the reported target.

- Add adapter-owned terrain/coordinate codecs and effective placement
  composition with provenance.
- Add canonical package writer, plan/report, stable IDs, reverse terrain, and
  semantic placement parity.
- Reuse only generic pinned layered contracts through the repository/runtime
  boundary; do not copy Spoiled Milk content policy.

Gate: identical runs are byte-identical; terrain reverses exactly; boundaries,
scenery, NPCs, and ground items have zero-delta parity; every unknown/loss/
approximation case fails visibly.

### Phase 3 — layered project lifecycle and empty mode

- Add UUID registry/selection/creation and source snapshot v2.
- Refactor runtime preparation/supervision for adopted, converted, and empty
  layered baselines.
- Add generated `empty-world-v1` at layer 0/origin 0,0 and standalone import
  refusal.
- Update Linux/Windows launchers and test atomic project creation, multiple
  projects, portability, drift, save/reopen, and generated-state confinement.

Gate: all three origins launch/edit/save/reopen/export in isolation; target and
pre-existing projects remain byte-identical through success and injected
failure; standalone import fails before target access.

### Phase 4 — required runtime capability upstream

In a separately assigned Spoiled Milk task:

- publish the versioned capability/build/definition/protocol identities;
- support generic validated packages rather than one fixed package;
- support authoring existing copied levels and creating terrain from canonical
  void at layer 0/origin 0,0;
- support all required placement families and package/client agreement;
- expose effective static composition when files alone cannot prove it; and
- provide bounded loader activation/configuration and offline contracts.

Gate: upstream tests and owner validation pass and the exact commit is
published. Only a separately authorized manager task may advance
`core-framework.lock`; this repository never edits `.core-framework/`.

### Phase 5 — content-neutral packaging and update preservation

- Replace broad copies with runtime/default-catalog allowlists.
- Remove bundled package generator/input and change v2 world-source identity.
- Preserve all adaptive and historical durable paths in both updaters.
- Add adversarial renamed-world/archive/managed-path tests.

Gate: no world or creator data ships; production marker, locked clean
dependency, natives, provenance, archive safety, project independence, update
channel, rollback, and Linux/PowerShell equivalence all still pass.

### Phase 6 — generic export, install, recovery, and undo

- Replace fixed five-file/fixed-layered destinations with adaptive export and
  bounded server/client mutation profiles.
- Generalize offline evidence, configuration changes, receipts, backups,
  recovery, and undo.
- Add exact preview and injected-failure tests at every mutation boundary.

Gate: adopted and converted target projects preview/apply/verify/rollback/undo
exactly; missing loader, target drift, changed-after, source corruption, and
force attempts fail closed; standalone import/undo never touches a target.

### Phase 7 — UX, documentation, and release validation

- Present auto-detect/adopt-or-convert/create-empty as the primary UI; keep
  technical reports expandable and actionable.
- Update every affected document/test listed above and align custom materials.
- Run `git diff --check`, focused suites, and `./scripts/test.sh`.
- Perform owner-run target-backed and standalone validation on the owner's
  native platform, automated and code-review coverage for the other platform,
  updater validation, owner-run software/OpenGL visual review, and install/undo
  recovery.
- Add a new adaptive release validation record without changing historical
  evidence.

Gate: every acceptance criterion has evidence. Release-gate opening, tag,
publication, and deployment remain separate manager decisions.

## Repository/runtime ownership boundary

| This repository owns | Compatible runtime owns |
| --- | --- |
| Capability parser, built-in adapters, discovery, reports, project registry | Truthful capability descriptor and stable build/definition/protocol IDs |
| Source snapshots, empty generator, isolated preparation, portability | Client/server ability to load and author the declared representation |
| Deterministic static-data conversion, package writing, parity reports | Effective composition interface when plugins/runtime decide active data |
| Layered export, mutation planning, import/undo transaction, receipts | Layered loader, collision/population, protocol, existing-level and void authoring |
| Content-neutral packaging, updater, docs, fixtures, release validation | Player-client compatibility/handshake and upstream runtime tests |

World Builder can convert only static data whose meaning it can prove. Loader
or protocol code must exist in the server/client runtime before import. Static
files in an unknown server repository are not enough evidence by themselves.

## Verification plan

### Automated

Every test uses temporary fixtures and inventories all target and pre-existing
project bytes before and after. Required coverage includes:

- Linux/Windows parent-target and standalone detection;
- descriptors, built-in probes, malformed/unknown layouts, ambiguous configs,
  and clear compatibility reports;
- packed/layered active maps, server/client mismatch, all placement families,
  definitions/capabilities, links/path attacks, and two-pass drift;
- exact deterministic terrain/placement conversion and every loss/unknown
  blocker;
- adopted, converted, and empty project creation; layer 0/origin 0,0; multiple
  projects; registry interruption; source corruption; portability; detached
  state; save/reopen; and generated-state confinement;
- no-world release canaries and updater durable-path attacks;
- complete deterministic layered export;
- standalone import/undo immediate refusal;
- target-backed exact preview/confirmation, loader refusal, offline refusal,
  drift, free-space/locks, backups, injected partial failures, recovery,
  verification, undo, changed-after refusal, and no force;
- Linux/PowerShell updater equivalence and historical workspace preservation;
  and
- frozen v1/v2 identity and channel isolation.

Every change runs `git diff --check`; the complete repository suite is
`./scripts/test.sh`. Release work runs focused package/updater suites and
independently inspects final archive paths, content, and hashes.

### Owner-run

Use disposable copies, never a live/public server:

- first launch on the owner's native platform with one packed server, one
  layered server, no server, unsupported server, and ambiguous configs;
- code review and automated package/launcher coverage for the other platform;
- simple conversion report, editor launch, save, close, reopen, and target byte
  comparison;
- empty project opening at layer 0/origin 0,0, authoring first terrain,
  save/reopen/export, and Import refusal;
- multiple project selection, complete-folder movement, server update, and
  detached behavior;
- software/OpenGL terrain, levels, collision, boundaries, scenery, NPCs,
  ground items, save/reopen, and reconnect;
- exact import preview, compatible server/client check, administrator player
  distribution message, apply, verification, owner restart, and undo;
- interruption and rollback recovery; and
- update from historical v2-alpha state with preservation policy verified.

AI sessions MUST ask the owner to perform and report visual inspection rather
than capture or judge screenshots themselves.

## Acceptance criteria

- **AC-01:** The production archive contains zero map terrain, static
  placements, layered packages, or creator state.
- **AC-02:** A folder inside a compatible server root discovers the actual
  active config/map/definitions on Linux and Windows.
- **AC-03:** An active layered map is copied and opened without conversion.
- **AC-04:** A supported packed map is converted automatically during project
  creation, with exact terrain reverse parity and zero-delta effective
  boundary/scenery/NPC/ground-item parity.
- **AC-05:** Unknown or unrepresentable input prevents project publication and
  names exact provenance; nothing is silently dropped or repaired.
- **AC-06:** The editor always opens a layered working package derived from the
  selected origin, never a release-owned world.
- **AC-07:** With no recognizable server/map, first launch opens a labelled
  standalone empty project at layer 0, coordinate 0,0, with no authored world.
- **AC-08:** Saving an empty project creates a valid package/export; Import and
  Undo fail before target access.
- **AC-09:** Source snapshots are complete/immutable, working state is isolated,
  and create/edit/save/close/reopen leave target bytes unchanged.
- **AC-10:** Multiple projects coexist and portable/detached project identity
  does not depend on an absolute path.
- **AC-11:** Export contains the complete deterministic working layered package
  and compatibility/conversion lineage.
- **AC-12:** Import requires exact compatible server/client loader capability,
  installs only adapter-approved package/config data, and identifies the client
  content administrators must distribute.
- **AC-13:** Import/undo preserve offline, preview, confirmation, drift,
  backups, receipts, verification, rollback, changed-after, and no-force
  contracts through every injected failure.
- **AC-14:** Server updates never cause implicit rebase, attachment, conversion,
  or installation.
- **AC-15:** Updaters preserve all projects, historical v2 state, and unknown
  files and never cross-update v1.
- **AC-16:** `git diff --check`, focused tests, `./scripts/test.sh`, automated
  cross-platform package/launcher checks, and owner-native visual validation
  pass before release readiness.
- **AC-17:** Documentation describes actual simple workflows and the exact
  compatibility boundary.

## Risk register

| Risk | Impact | Required mitigation |
| --- | --- | --- |
| “Any server” interpreted literally | Users expect unsafe universal guessing | Say “compatible adapter/capability” consistently; make adding adapters small and documented; actionable unsupported report |
| Missing map mistaken for standalone mode inside a server | User thinks the real server map is open | Distinguish no server evidence from broken server evidence; prominent standalone label; import disabled |
| Capability/path spoofing | Reads/writes escape intended roots | Strict schema/limits, compiled adapters only, canonical contained paths, adapter-owned mutation profiles, adversarial tests |
| Ambiguous config/map | Wrong map gets converted | Semantic enumeration and simple explicit chooser; never filename/time heuristics |
| Incomplete placement composition | Silent lost/duplicate content | All four families, removals/precedence provenance, normalized parity and collision checks, fail unknown |
| Definition mismatch | Wrong visuals, collision, NPC/items | Complete target server/client/Builder catalog identity and referenced-ID checks |
| Coordinate/orientation conversion error | Shifted terrain or walls/collision | Adapter vectors, exact byte reverse, semantic parity, owner visual/collision tests |
| Dynamic/plugin data cannot convert | Output differs at runtime | Static-data boundary and runtime composition capability; visible blocker, no approximation |
| Empty world runtime cannot address void | Standalone mode fails before first tile | Explicit upstream void-authoring contract; canonical generated origin sector if required; layer 0/origin tests |
| Default catalog makes standalone output target-specific | Empty projects are not automatically portable | Version/catalog identity in project/export; no import attachment in first phase; future compatibility mapping requires design |
| Target drift/server update | Old project overwrites new world | Fresh discovery, exact lineage, detached editing only, target import blocked |
| Updater owns project state | Creator data loss | Durable denylist plus unknown-file refusal, archive validation, byte-for-byte update/rollback fixtures |
| Historical v2 workspace mismatch | Existing work becomes inaccessible | Preserve byte-for-byte; bounded matching-runtime access or refuse update; no implicit migration |
| Cross-platform paths/atomics | Windows-only failures or corruption | Portable canonical paths, no display placeholders in path APIs, Linux/Windows fixtures, verified atomic fallback |
| Missing loader/client support | Imported package cannot run | Capability check before preview/apply; no binary patching; exact upgrade requirement and admin distribution notice |
| Partial import/rollback failure | Target left uncertain | Pending receipt before mutation, package first/config last, verified backups, reverse rollback, recovery lock |
| Multiple full projects consume disk | Interrupted creation or storage pressure | Exact size/free-space preflight, unique staging, no implicit deletion, later explicit archival workflow |

## Approval record

The product owner approved this document on 2026-08-01 after confirming that:

- World Builder always edits signed-layered project data;
- first-project preparation converts supported legacy maps automatically;
- target installation requires compatible layered-loader server/client code
  and the administrator distributes matching player content; and
- launching without a recognizable server/map creates a standalone empty
  project at layer `0`, coordinate `0,0`, whose import and undo paths are
  disabled.

This approval does not approve a dependency change, release-gate change, tag,
publication, deployment, live-server work, or implementation outside the
separately reviewed phases in this plan.
