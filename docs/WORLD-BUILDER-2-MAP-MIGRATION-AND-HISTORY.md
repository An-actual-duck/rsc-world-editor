# World Builder 2 Map Migration, GUI Transactions, and Project History

## Document status

| Field | Value |
| --- | --- |
| Status | Split-map signed-layer preservation correction implemented; verification and owner GUI validation pending |
| Captured | 2026-08-27 |
| Product | World Builder 2 only |
| Immediate objective | Convert a detected legacy custom landscape into one complete layered project and expose safe export/import in the desktop GUI |
| Follow-up objective | Add understandable project-save history and verified restore |
| Current priority | Owner-validate the implemented split-map workflow before resuming detached-camera work |

This document extends the implemented adaptive map workflow. It does not
reinterpret frozen schemas, weaken exact conversion, or authorize destructive
target cleanup. The existing transaction engine remains the authority for
target preview, backup, import, recovery, and undo.

## Product outcome

A typical creator should be able to:

1. click **Detect Server Map**;
2. choose a map only when genuinely different valid configurations exist;
3. answer a simple legacy-landscape migration question when applicable;
4. create and edit one isolated layered project;
5. export its complete layered map without using a terminal;
6. install it through **Import Map Changes to Server** in the same desktop
   application;
7. recover or undo an interrupted/completed target import through that GUI;
   and
8. browse and restore earlier project revisions without understanding package
   paths or map encodings.

The ordinary launcher retains exactly three primary actions: **Create New
Project**, **Detect Server Map**, and **Continue Working on Selected Project**.
Migration, export, import, backup, and recovery belong to the selected project
and do not add competing project-creation buttons.

## Existing foundation

The following are already implemented:

- bounded server-root and configuration discovery;
- explicit selection when more than one valid configuration is found;
- read-only discovery of matching server/client `Custom_Landscape.orsc`;
- exact packed-sector validation and deterministic packed-to-layered
  conversion;
- effective boundary, scenery, NPC, and ground-item composition;
- immutable source evidence and conversion reports;
- a complete mutable layered working package;
- target-independent complete layered export;
- target mutation preview, exact confirmation, offline checks, verified
  backups, durable receipts, rollback, recovery, and exact last-import undo;
- separate Linux and Windows scripts for Import, Undo, and Recovery; and
- copy-on-write project save publication and startup recovery.

The migration choice, immutable lineage, complete-project export action, GUI
Import/Undo/Recovery projection, recoverable legacy retirement, and
creator-facing project revision history are implemented. Destination
selection/reveal for complete exports remains future launcher polish; the
current GUI reports the immutable generated export path.

## Legacy custom-landscape migration

### Meaning of “incorporate”

`Custom_Landscape.orsc` is a bounded legacy sector archive, not a complete
signed-layered world authority. Its four packed planes map only to signed
levels `0`, `1`, `2`, and `-1`; it cannot represent wider levels such as `-2`
or `+10`. Discovery may find it alongside a compatible layered target or as
the selected primary packed map. Choosing to incorporate it means:

1. select the exact matching server/client archive as legacy sector evidence;
2. bind it to the chosen compatible server configuration;
3. collect that configuration's active definitions and placement families;
4. convert every legacy sector exactly into signed layered form;
5. when a complete layered base exists, preserve all of its signed levels and
   placement sets while applying only exact legacy terrain evidence; if one
   non-void legacy tile has exactly one byte-identical destination on a wider
   signed level, suppress the stale old-layer tile and relocate boundary,
   ground-item, NPC, and scenery records anchored to that tile;
6. strictly validate the layered base, legacy conversion, and composed output;
   and
7. record the decision and every input hash in immutable project lineage.

The relocation rule is deliberately fail-closed. A placement already present
with the same semantics is de-duplicated in favor of the layered base. An
occupied target slot with different semantics is reported as a conflict and is
not guessed. A legacy tile matching more than one possible signed destination
does not establish relocation evidence, so neither terrain nor placements are
moved from that ambiguous tile.

World Builder does not infer which package is newer, scan product-specific
state directories, or merge an unrelated package. It first resolves the
complete layered base from bounded server-owned launch metadata. The marker's
recorded server root and selected configuration must match the detected source,
and its layered-package manifest hash must still be exact. When a source
checkout has no such marker, the editor performs a bounded, product-neutral
search of standard platform application-data roots for the exact portable
layout `*/live/layered-worlds/<manifest-sha256>/package`. The directory name
must equal the current manifest hash and the manifest must declare the supported
layered-world identity. No arbitrary files, server JARs, scripts, or
product-named locations are searched or executed. Legacy-present sectors
replace the same coordinates; every other base sector, every wider signed
level, and existing base placement set remains exact. Inputs that cannot
satisfy this deterministic rule fail without changing either source.

One conservative exception prevents obsolete terrain-only duplicates after a
packed level has been split onto wider signed levels. When the layered base
explicitly contains void at a legacy tile or omits that old sector, the old
level has no placement owner there, and that legacy tile is byte-exactly
represented at the same coordinates on a base level outside the packed range
(`-2` or below, or `+3` or above), composition retains the base void tile
instead of recreating the old copy. Unique legacy tiles, occupied old-level
sectors, non-void base tiles, and non-identical relocated terrain continue to
use the normal exact overlay rule. The composition report records the exact
suppressed-tile and affected-sector counts.

Some deployed layered packages preserve otherwise valid, unique signed level
records in historical insertion order. For composition only, World Builder
keeps one byte-exact copy under `layered-base/original-package`, makes a second
isolated package copy, sorts that copy's level, terrain-sector, and placement-set
declarations by their canonical signed coordinates, and records both manifest
hashes, the ordered levels, declaration counts, and each reordered flag in
`layered-base/normalization-report.json`. Placement records inside each copied
level payload are likewise sorted by owner tile, boundary direction where
applicable, and stable placement identity; changed payload hashes are updated
only in the copied manifest. Duplicate, malformed, hash-mismatched, or
out-of-range declarations still fail closed. The installed package is never
normalized in place.

### Streamlined prompt

After **Detect Server Map** and any required configuration selection, discovery
checks the selected source for a matching legacy landscape. This includes a
`Custom_Landscape.orsc` archive that is itself the selected packed map; that
case must not silently skip the retirement choice. Beside an already selected
layered authority, the ordinary prompt is:

> Custom_Landscape file detected. Would you like to incorporate it?

with **Yes** and **No** actions. This remains the ordinary prompt when the
detected primary map is itself the legacy archive: the editor automatically
resolves its associated active layered package from verified launch metadata.
**Yes** preserves that complete signed-layered world and applies the converted
legacy sectors over it. **No** explicitly uses the legacy four-plane map alone.
There is no normal folder-selection step.

When more than one genuinely different, valid active layered package is
recorded or installed, the editor presents those detected maps by package or
configuration identity, evidence time, and manifest fingerprint. It never asks
an ordinary user to navigate to a package directory. If no verified candidate
exists, the editor explains that the layered map must be installed or launched
normally; the user may explicitly use the legacy map alone or cancel without
creating anything.

Choosing **Use Most Recently Modified** at the server-map prompt carries that
choice through subsequent legacy-configuration and installed-layered-package
ambiguity. The newest verified candidate is used consistently; the user is not
asked to make the same time-based choice again. Choosing **Choose from
Detected…** retains explicit selection at each genuinely distinct authority.

- **Yes** creates the project by composing verified legacy sectors over the
  already selected layered target, opens the resulting layered world, and
  records capability-gated retirement intent for a later explicit import.
- **No** still permits ordinary project creation from the selected map
  authority, but records no retirement intent.
- Closing or cancelling the prompt creates nothing and changes nothing.

The concise prompt is end-user language. Expandable details list the detected
base package, legacy paths and hashes, selected configuration, exact
composition rule, and the fact that the server remains unchanged during
project creation.

The prompt must not appear merely because a stale backup, build output,
download, or unrelated archive shares the file name. Both archive identity and
the selected adapter/configuration relationship must be proven.

### Migration lineage

A migrated project needs versioned immutable evidence containing at least:

- a migration schema and profile ID;
- the selected configuration role, safe relative path, and SHA-256;
- server and client legacy archive paths, sizes, and SHA-256 values;
- confirmation that both archives were byte-identical at discovery;
- definition, placement, asset, and content-bundle fingerprints;
- conversion plan/report and output package fingerprints;
- the exact selected layered-base inventory and fingerprint when composition
  was requested;
- the exact converted-legacy inventory and fingerprint;
- preserved signed levels and replaced/added terrain-sector counts;
- exact terrain reverse-parity and effective-composition results;
- whether retirement was requested by the user; and
- the compiled target mutation profile permitted to interpret that request.

The project manifest may expose a simple derived state such as
`legacyLandscapeMigrated`, but target mutation relies on the complete versioned
evidence rather than one boolean.

The two-authority contract is `world-builder-map-migration-choice` schema
version 1. It binds two distinct immutable discovery fingerprints: the normally
selected target authority and a separately validated packed-conversion
candidate. It also binds both selected configurations, the exact byte-identical
server/client legacy terrain records, the affirmative incorporation decision,
retirement intent, and its own fingerprint.

The primary-packed contract is `world-builder-packed-map-migration-choice`
schema version 1. It binds the selected packed discovery fingerprint and
configuration directly, both exact byte-identical server/client legacy terrain
records, the affirmative incorporation decision, retirement intent, and its
own fingerprint. An optional creator-selected layered base is separately
validated, copied into immutable migration evidence, and bound through the
composition report; it is not fabricated as a second target discovery report.

In the two-authority flow, the packed candidate does not replace or masquerade
as the selected target report. Project creation must re-run and match both
discoveries at its drift boundaries, then copy their union without allowing
generated packed-fallback descriptor/configuration evidence to collide with the
selected target's real descriptor/configuration evidence. Generated conversion
authority therefore needs its own contained namespace or staging root. The
immutable selected target evidence remains available for later attachment and
import; the packed evidence supplies conversion input only.

## Complete map export in the GUI

The desktop application exposes **Export Complete Map Package…** for a selected
closed project. It uses the existing adaptive exporter and therefore:

- locks and revalidates the project;
- refuses pending or unsafe runtime state;
- copies the complete working layered package;
- includes conversion and validation lineage;
- publishes a new immutable export without overwriting an older export;
- never resolves or changes the target server; and
- reports the destination and package/export fingerprints.

The user may choose a destination or reveal the generated export. The artifact
contains the validated `package/` plus its manifest and validation report. A
future single-file transport wrapper may be added, but it must not replace or
weaken the canonical complete package.

This action is distinct from region `.wbr` export. Region export shares a
selection; complete map export publishes the entire selected project's world.

## Import, undo, and recovery in the GUI

### Import Map Changes to Server

The existing adaptive transaction engine is exposed inside the desktop
application rather than reimplemented in Swing. The selected project must be
closed before import. The GUI:

1. exports or selects the exact current complete project export;
2. rediscovers the attached target and rejects drift;
3. acquires every required offline signal;
4. displays a readable summary with expandable exact plan details;
5. identifies package installation, configuration activation, backups, and any
   proposed legacy retirement separately;
6. requests final confirmation only after displaying that plan;
7. applies the exact in-memory plan through the existing transaction engine;
8. shows the verified receipt and installed client/map identity; and
9. offers the appropriate Undo or Recovery next action.

Friendly labels must retain the same transaction UUID, plan fingerprint, write
ordering, verification, and no-force guarantees as the CLI. The GUI never
manufactures a second, weaker import path.

### Recoverable legacy retirement

For a project with verified migration lineage, the import plan may retire the
legacy server/client `Custom_Landscape.orsc` copies only when the compiled
adapter and target capability prove all of the following:

- the exact files are the migrated source identities;
- the new layered package is valid at both bounded destinations;
- the activated configuration selects the layered loader as sole authority;
- no selected server/client configuration still consumes the legacy files;
- all affected files fit the existing backup, receipt, rollback, and undo
  model; and
- the target is offline under the normal lease.

Retirement is never an unrecorded deletion. Exact original bytes are copied to
verified project-owned transaction backups before mutation. Package content is
installed and verified before activation; activation changes occur in the
adapter's safe order; retirement is verified and receipt-bound. Failure
restores the complete before-state. Undo restores both archives and their prior
configuration exactly.

When capability evidence is incomplete, import keeps the legacy archives and
explains why. File presence alone does not duplicate terrain once the layered
loader is sole authority, so safety takes precedence over cosmetic cleanup.

### Undo and recovery

The GUI exposes **Undo Last Server Import…** for one successful unreverted
transaction and **Recover Interrupted Server Import…** when a pending receipt
blocks normal work. Both project the existing exact transaction engine.
Changed-after, missing-backup, target-lineage, online-target, and receipt
failures remain blockers. There is no force button.

The standalone scripts remain available for headless recovery. GUI integration
is an additional safe interface, not removal of the recovery escape hatch.

## Project backups and world-save history

### Three meanings of backup

| Concept | Purpose | Existing state |
| --- | --- | --- |
| Save publication recovery | Restore the working package after an interrupted or failed save | Implemented internally |
| Server transaction backup | Restore target files/configuration during rollback or exact Undo | Implemented and script-accessible |
| Project revision history | Let a creator return to an earlier authored world | Implemented in the selected-project GUI |

Calling all three “backup” in casual UI is acceptable only when each screen
states which world is affected. Loading a project revision never mutates a
server. Undoing an import never silently changes the current project.

### Revision creation

The first project-history increment creates a revision after a successful
closed editing session when the working package fingerprint changed. It also
creates or verifies a pre-restore revision before loading older state.

Each immutable revision records:

- project UUID and display name;
- creation time and reason (`editing-session`, `before-restore`, or explicit
  user backup);
- source, conversion, definition/content, and runtime identities;
- complete working package fingerprint and closed inventory;
- parent revision when applicable;
- optional user description; and
- tool/schema version.

Revisions live under the UUID project, survive application updates, and omit
target credentials, logs, PIDs, databases, unrelated receipts, and absolute
user paths.

Implementation should prefer content-addressed file reuse so unchanged package
files are not copied repeatedly. Correctness cannot depend on hard links,
reflinks, or platform-specific deduplication. Initially, World Builder does not
silently prune revisions. The UI reports count and disk usage; a separately
reviewed retention policy may follow.

### Backup browser

The selected-project GUI provides **Project Backups…** with:

- a newest-first revision list;
- date/time, reason, optional description, and abbreviated package identity;
- the current revision clearly marked;
- package file count and disk usage;
- **Load Backup…**;
- **Export Backup…**; and
- **Create Backup Now…**.

Deletion and automatic pruning are deferred until their recovery and retention
behavior is designed.

### Load Backup

Loading a backup requires the private editor to be closed. The operation:

1. locks and verifies the project and selected revision;
2. shows current and selected identities plus a bounded difference summary;
3. requires confirmation;
4. records a verified pre-restore revision of the current working package;
5. stages the selected complete package as a sibling;
6. validates definitions, assets, package schema, inventory, and project
   lineage;
7. atomically publishes the restored working package and updates its
   fingerprint; and
8. reopens/verifies the project before reporting success.

Any failure preserves or restores the exact current package. A loaded backup
creates a new history head; it does not rewrite or delete historical revisions.
The target remains untouched until a later explicit Import.

## Implementation sequence

### Increment 1 — migration choice and lineage

- Detect an applicable legacy archive after exact configuration selection.
- Add the Yes/No migration prompt and expandable evidence.
- Preserve cancellation and target read-only behavior.
- Record versioned migration/retirement intent in project lineage.
- Prove exact packed conversion and complete layered export using fixtures and
  the owner's currently available split-map validation case.

### Increment 2 — complete export and target transactions in the GUI

- Add selected-project **Export Complete Map Package…**.
- Add **Import Map Changes to Server…** through the existing engine.
- Add readable and expandable transaction previews.
- Add GUI Undo and Recovery while retaining the scripts.
- Add capability-gated, recoverable legacy retirement to the mutation plan.
- Exercise Linux GUI behavior and Java-level Windows-equivalent contracts;
  owner visual review uses no screenshots.

### Increment 3 — project revision history (implemented)

- Define the immutable revision schema and content-addressed storage.
- Record changed successful editing sessions and explicit backups.
- Add the project backup browser and disk-usage reporting.
- Add verified Load Backup and Export Backup.
- Add interruption, corruption, update-preservation, and rollback coverage.

Each increment remains a reviewable exact commit and passes its
risk-appropriate test gate before the next begins.

## Acceptance criteria

- **MH-01:** The primary launcher still presents exactly three ordinary project
  actions.
- **MH-02:** A matching legacy archive produces one clear Yes/No prompt after
  required map selection; cancellation creates nothing.
- **MH-03:** Yes converts legacy terrain plus effective placements/content into
  one complete layered project with exact parity; No preserves the selected
  map authority.
- **MH-04:** No source or target byte changes during discovery, conversion,
  editing, save, backup creation, backup load, or complete export.
- **MH-05:** Complete export is available from the GUI and independently
  validates the full package and lineage.
- **MH-06:** GUI Import uses the existing exact transaction plan and cannot
  bypass offline, confirmation, backup, receipt, verification, rollback, or
  no-force rules.
- **MH-07:** Legacy retirement occurs only for exact migration identities under
  a compatible mutation profile and remains fully undoable.
- **MH-08:** GUI Undo and Recovery produce the same plans and results as their
  script/CLI counterparts.
- **MH-09:** Project history distinguishes creative revisions from server
  transaction backups and internal crash recovery.
- **MH-10:** Load Backup creates a pre-restore revision, validates before
  publication, never rewrites history, and never accesses the target.
- **MH-11:** Application updates preserve every project revision, export,
  transaction backup, and receipt.
- **MH-12:** Temporary fixtures cover packed migration after the owner's real
  split-map case is unavailable; no test mutates a real server or user
  workspace.

## Explicit non-goals

- Arbitrary two-map overlay or timestamp-based per-tile merging.
- Guessing that a same-named archive belongs to the selected server map.
- Executing target JARs, scripts, plugins, or configuration-supplied commands.
- Deleting legacy terrain without exact verified backup and undo authority.
- Loading a project backup directly into a server.
- Silently pruning creator history.
- Treating region snapshots as complete map backups.
- Hard-coding a specific private server, external live-state directory, or
  creator map identity into the neutral Editor.

## Readiness

| Area | Readiness | Missing work |
| --- | --- | --- |
| Legacy packed detection/conversion | Implemented | Owner validation on additional real-world targets |
| Complete layered export | GUI action implemented | Optional destination/reveal experience |
| Target import/undo/recovery | GUI projection implemented | Owner visual validation and release testing |
| Legacy retirement | Implemented and transaction-tested | Owner validation of automatic active-layer association on the real split-map workflow |
| Project revision history | Implemented with content-addressed storage and recovery | Owner GUI validation and additional long-running real-project use |
