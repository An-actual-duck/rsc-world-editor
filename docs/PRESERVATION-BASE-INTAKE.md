# Preservation source intake boundary

## Actual checkout checkpoint — 2026-09-10

The owner designated `/home/justin/RSC-Preservation` as the read-only source for
a separate test environment. Its public revision is the exact c0102e revision
used below. The original selected-file fixtures missed ordinary stock files
encountered in a full checkout. That public-stock detection gap is now fixed
with 266 exact optional public-file records. Their compiled hash seal is not
target-supplied authority. Missing optional files are allowed; changed bytes,
links, unsafe modes and unknown files remain blocked. Active historical stock
definitions are baseline behavior replaced by Current Base, not claimed to be
unused. All historical files remain untouched and historical code is never run.

Ordinary mode 0664 (a Git checkout under umask 0002) is now admitted wherever
the public source policy expects 0644; tracked executable launchers similarly
allow 0755/0775. Adding executable bits to non-executable sources, special bits,
or world-write remains refused. Database/key permissions still use the separate
private-input policy.

The tools build and all 12 focused source-intake tests passed using the
designated repository's exact public Git blobs (43.098 seconds, no skips).
The discovery fixture now includes every public stock file in the probe roots,
with an exhaustive comparison to the Git tree, fresh database/keys, and explicit
exclusion of original private state. Native lifecycle/cycle fixtures also now
include those stock files; their complete integration gate must be rerun.

`/home/justin/RSC-Preservation-Test-2026-09-10` is prepared from **2,225 public
tracked files**, with fresh empty SQLite, fresh test-only keys, loopback-only
test ports, and the public empty UID placeholder. No original working-tree
credentials, `.env`, accounts, backups, databases, logs or real UID are copied.
Its development-tools discovery passes as `compatible`, `historical-jag`,
`preservation-source-jag-v1`. No project has been created or upgrade applied.
The original source itself still refuses at its effective connections config;
this fresh-state public-baseline result is not acceptance of private original
configuration/state. No changes were made to that original.

The next candidate work is, in order:

1. Integrate the pending runtime fix and Editor work, run the final integration
   gate, build and independently inspect a fresh restricted candidate, and put
   its folder inside the disposable server root. Smoke-check launch/default
   detection without consuming the user's fresh project-creation walkthrough.
2. Hand off the exact launch path and bounded fresh-install test steps. The
   prepared source directory is not ready for the owner until the candidate is
   installed and verified there.

Existing-project adoption of a later authoring runtime remains product work,
but is **not a prerequisite for this fresh first-install test candidate**.
There is no packaged candidate at this checkpoint; the source test copy exists.

## Implemented selected-layout contract

The compiled `preservation-source-jag-v1` discovery adapter admits the reviewed
public c0102e source layout, its JAG/MEM map inputs, and supported effective
configuration. A target-supplied descriptor does not grant this authority.
Changed source/build behavior, unknown files, unsafe paths, and untranslated
configuration remain blockers. Historical code is never built or executed.

The `preservation-c0102e-private-inputs-v1` policy binds metadata for these exact
external inputs, including explicit optional absence:

- Required: `server/inc/sqlite/preservation.db`, `server/server.pem`, and
  `server/client.pem`. Existing owner keys must form a valid matching pair;
  intake never generates replacement owner keys.
- Optional: `server/badwords.txt`, `server/goodwords.txt`,
  `server/alertwords.txt`, `server/ipbans.txt`, `server/ipbans.temp`, and
  `Client_Base/clientSettings.conf`.

These bytes are excluded from discovery's project-copy inventory. The private
binding records paths, roles, presence, size and SHA-256 only. Database/key/client
preferences require mode 0600; text filters and bans permit 0600 or 0644.
Symlinks, hard-link aliases, non-regular entries and unclosed SQLite sidecars
are refused. Bounds are 4 GiB for SQLite, 64 KiB per key and 1 MiB per other file.
The historical client already implements UID state. Only its exact empty public
`Client_Base/Cache/uid.dat` placeholder is admitted as stock metadata; nonempty
UID/remembered-credential state is not silently adopted or copied into projects.

A closed SQLite header is not schema validation. Production migration preview
records `schemaValidation: pending-provider-sealed-migration`, sets
`sqliteSchemaMigrationReady: false`, and keeps `activationApproved: false`.
Any future sealed staging must run the selected provider's actual state
migration and verify its output before activation can be considered. Text and
preference files are bound for preservation, not declared behavior-equivalent
merely because their paths and hashes are known.

The production project path now selects the complete native Base composition,
retains its artifacts and derived full authoring catalog, invokes only the
selected provider's decoder, and seals the original public inputs and canonical
baseline. It does not create an Advanced content overlay or copy the target's
player database or keys. The isolated authoring database belongs at
`working/authoring-state/world_builder.db`, separately from runtime artifacts.
The server and client commands bind the retained composition identity.

CLI creation selects `--provider-catalog-root` and `--composition-identity`
together. Desktop creation supports an explicitly injected selection; packaged
discovery defaults to `current-platform/` and its `composition-identity.json`
under the installation. Both paths use the existing verified provider catalog
resolver, with no fallback to historical or Advanced executables. Packaging
still needs to ship that selected catalog.

The packaging helper `export-current-base-catalog` accepts
`--provider-catalog-root`, `--composition-identity`, and
`--destination <new-absolute-installation-root>`. It copies only selected
artifact `sourcePath` entries, the platform's schema-contract closure, and the
exact identity at `current-platform/composition-identity.json`. It preserves
source bytes/modes and proves that the compiled Java resolver accepts the
relocated catalog. Provider build scripts may be inventory-bound payloads;
the helper never executes them or includes the full source checkout. Existing,
linked, or overlapping destinations are refused. A failed partial new tree
is not a candidate and must not be reused as a completed export.

Native lifecycle acceptance now passes against published runtime
`4d589cb4bb43c8db3954a3d412eceaa5d743d7b2`: genuine project creation from the
sealed public source, two authenticated native editor sessions with clean
server/client exit and restart, reopen/export, content tamper refusal, and
unchanged historical target/source evidence. The test uses invented private
state and confirms that only the isolated authoring database gains the Builder
account. Two tests passed in 118.923 seconds with the real-launch option enabled.
The connected run found and fixed a missing diagnostics-directory creation
before moving the conversion reconciliation report.

The selected provider must explicitly advertise
`current-base-isolated-authoring-v1`; the older normal-only Base is refused
before project publication. The current pin satisfies that requirement.

## Connected upgrade checkpoint — 2026-09-09

A real CLI upgrade on a retained invented-state Preservation target completed
sealed SQLite migration, executable server/client verification, initial instance
construction, and guarded activation successfully. Its first attempt had rolled
back because the constructor required optional absent chat-filter files. Initial
activation now supplies private empty lists for exactly those absent files,
preserving the historical no-list behavior instead of importing provider default
filters. Existing filter bytes and the owner keypair remain unchanged; original
persistent inputs are rechecked before cutover.

Focused transaction (33), instance (13), successor topology (4), and empty-filter
(2) tests pass. The connected server-cycle test uses a fresh populated historical
database, isolated disposable ports, normal owned-window login, saved map edits,
and repeated map-only imports/restarts. The normal login helper's synthetic
all-green-map assumption is replaced with imported-world detail, non-void, and
visible-player checks. After the two-phase diagnostic proof, the complete fresh
all-in-one regression passed in 319.801 seconds: genuine creation, real initial
upgrade, normal login, and two saved-map imports/restarts with state-safe recovery.
This closes the connected-cycle gap, but is not a full-suite or packaged-candidate
pass.

The established desktop **Upgrade Target Runtime** action and active-project
terminal command now route native Base projects to this guarded transaction,
using the installation's relocated selected catalog and the project's recorded
target (not the installation's parent). Their preview displays the destination,
state-preservation policy, evidence location, plan fingerprint, and exact
confirmation. A genuine fixture test passes desktop preview and terminal
cancellation with the historical target unchanged. Catalog export is tested;
the release packager still needs to include that projection.

Connected testing also exposed a Linux `TIME_WAIT` false busy-port refusal after
clean shutdown. Linux leases now allow address reuse without enabling port
sharing; tests prove a closed connection is accepted and active loopback/wildcard
listeners are still refused. Other operating systems retain exclusive defaults.
The 33 transaction, 6 map-import, 13 instance, 4 successor-topology, and 2
empty-filter tests pass after this fix, as do both new port tests.

The connected test reached a committed map import with unchanged player state,
then caught a real restart refusal: the generated map profile used `package`
instead of the runtime's canonical
`world-builder/packages/<package-fingerprint>/package` identity. Map import now
emits the provider-required logical path while keeping the physical external map
root in the bound launch descriptor. Both server and client profiles are checked
in the connected regression. No runtime rewrite or relaxed loader check is needed.

The retained installation then passed both real map imports, three normal player
logins with clean owned-session shutdown, and post-commit map recovery without
rewinding later state. Inventory (321 coins), quest stage, skill levels, and
offline account status survived. Imports retained the runtime code and all
player/side-state roots, and the original historical files and project source
remained unchanged. A completed-upgrade recovery request was correctly refused
without changing gameplay state; it is not a completed-upgrade Undo operation.
The six focused map recovery/workspace checks also pass after the profile fix.

## Interrupted recovery controls

Native Base projects now use **Advanced / Recovery → Recover Interrupted Server
Transaction** for both upgrades and map imports. The existing recovery shortcut
uses the same current-transaction path. It looks only in the private external
workspace shown by the normal upgrade/import preview, binds the selected project,
and requires exactly one interrupted transaction. Preview does not create a
missing recovery directory or change the target. Completed transactions are not
offered as Undo actions; ambiguous histories are left untouched for explicit
transaction review.

Review the operation, server target, journal location, and exact recovery
confirmation. Keep both roles offline. Apply rechecks the evidence before invoking
the existing recovery executor. Upgrade confirmation binds the validated plan,
receipt, and any pending receipt; map confirmation binds the exact map plan.
Pre-commit recovery restores the verified prior state. A committed activation is
finalized without restoring an older player database. If the transaction was
started with an explicitly chosen CLI workspace, use its original exact recovery
command rather than relocating its evidence into the desktop workspace.

The focused upgrade suite passes 34 tests. The map suite passes 9 tests including
desktop preview without writes, wrong/stale confirmations, occupied role leases,
pre-commit rollback, and post-commit finalization with later gameplay retained.
This is shared model/transaction coverage, not a claim of packaged GUI acceptance.
A genuine native-project control test also passes (108.681 seconds), confirming
desktop upgrade preview, terminal cancellation, and native desktop/terminal
recovery routing without offering an operation for an empty transaction history
or changing the target. The fresh connected-cycle pass is recorded above.

No live-target mutation or candidate is authorized by these test results.

## Selected Base delivery checkpoint

The existing v2 packager now builds Current Base and invokes the existing Java
catalog exporter before staging the application. Both archives contain the exact
36-file selected projection, including its composition identity, schema contracts,
runtime artifacts, and public migration baselines. It does not copy the provider
checkout, unselected Advanced catalog, projects, or target state.

Staging and the independent final-archive inspector re-resolve the selected
composition from the pinned provider and require exact file bytes and modes.
Regenerating outer checksums or the archive manifest cannot authorize a changed
runtime, identity, missing payload, or extra catalog file. The Linux and PowerShell
updaters admit these exact paths rather than entire catalog/output directories.
Updater fixtures include the new payload in application replacement and recovery
checks; actual Windows execution still requires its separate validation environment.

Focused evidence: 16 packaging and 23 independent archive-inspection tests pass.
The two real-provider catalog export/relocation tests pass, including independent
Python/Java selection parity and updater path-list parity. The updater suite passes
18 tests with 5 explicit PowerShell skips on this host. These are component/delivery
checks, not fresh packaged GUI or live-server acceptance.

Managed-successor end-to-end proof, Editor integration/publication,
and fresh packaged candidate acceptance remain the required next steps. Do not
reopen completed map-conversion or gameplay work without a concrete failing case.

The successor proof has a genuine predecessor available: published runtime
ancestor `e9cf05c78a6aadde44ecc1a8449dbba2cecdc159` builds and verifies separately
from selected `4d589cb4bb43c8db3954a3d412eceaa5d743d7b2`. Both have the current
SQLite copy contract; the selected revision includes real client/server gameplay
changes. No provider source or Editor lock was modified to prepare this input.
The first genuine two-version execution exposed two concrete integration bugs:
normal predecessor gameplay creates `ipbans.txt` with umask-derived permissions,
and the staged-plan validator still expected historical SQLite migration rows
for a managed successor. Both Editor paths are now corrected on the active topic.
The permission bridge admits only `ipbans.txt` / `ipbans.temp` at the bounded
legacy modes inside a canonical, target-owned `0700` side-state root; it preserves
the predecessor without chmod, still rejects links, and does not broaden database,
key, credential, or other-state permissions. Managed plan validation binds the
exact current-state copy row rather than the historical row set.

The retained invented-state probe subsequently completed the genuine
`e9cf05c7` -> `4d589cb4` upgrade and normal player login/shutdown. Both code trees
changed, the edited map fingerprint stayed exact, the new database matched the
predecessor before launch, and side-state paths stayed unchanged. The initial
failed transaction reported no target mutation and completed rollback.

The subsequent fresh combined regression passed in **410.074 seconds**:
genuine project creation, initial predecessor installation, normal player login,
two saved map imports/restarts/recovery checks, genuine successor activation,
normal successor login/shutdown, and existing-project reopen/save/export. It also
rejects forged historical migration rows and verifies that the still-old authoring
composition cannot import into the successor, without target mutation. This last
refusal is a recorded remaining requirement, **not** successful post-upgrade import
acceptance. Log: `/tmp/base-genuine-successor-complete.log`; retained invented-state
fixture: `/tmp/base-project-lifecycle-hf9de2vz`. All owned processes exited cleanly.

Reproduce this extra two-build lane with the normal Base-cycle test and explicit
`WORLD_BUILDER_BASE_PREDECESSOR_PROVIDER` pointing to a clean built checkout of
`e9cf05c7`, plus the existing authorized public-source and coordinated-display
inputs. The test independently checks the exact ancestor against the selected
provider repository. No historical reference checkout is built or launched.

Runtime worker `fix/current-base-private-ban-state` is clean, pushed and READY at
`bf0fcac5eeab106aebed69294886622a97997969`: new installed ban/temp files use `0600`,
existing ban bytes/modes survive startup, and non-installed behavior stays intact.
Three focused runtime tests passed, including real server restart and compiled
ban/unban/link-refusal checks. This correction is **not** yet merged to runtime
main or adopted by the Editor lock; full integration verification remains due.

Concrete follow-on work found during review: native map import currently compares
the target against the project's original composition and code trees. A genuine
managed upgrade must also prove that the same existing project can import another
saved edit afterward. Bind that operation to the verified selected current
composition without rewriting the immutable project source, recreating the
project, dropping the map-compatibility checks, or trusting an arbitrary target
ledger as runtime authority. This is functional closure work, not optional polish.

The authoring review found no existing project-runtime adoption mechanism to reuse:
`working/runtime` is verified against immutable `source/provider` and the project
manifest. Matching valid-ID catalogs alone cannot establish compatibility because
they omit definition/asset semantics. The two genuine builds also change content
configuration. Do not remove exact import checks or claim the old authoring runtime
has been upgraded. The next functional step is a guarded current-authoring adoption
path that preserves original source evidence and authored data, followed by another
saved edit/import/restart from the same project. Until then the regression explicitly
expects old-project successor import to refuse without target mutation.

The first implementation step now reuses `WorldBuilderAdaptiveRuntimePreparer`
and native provider capture to prepare a separate current-authoring generation.
It consumes selected current Base code/content and the original project's sealed
isolated seed/control inputs, without copying player or existing authoring state.
The original project, source evidence, working map, runtime and server target are
not replaced. Current definition IDs must still fit the original catalog; the
generation includes the complete selected current definitions/assets, not merely
an assertion that matching ID lists imply matching gameplay.

Preparation is append-only in a new external directory. Project locks, canonical
paths, source/provider/target separation, selected-input revalidation, complete
byte/mode inventories and an independently retained seal hash protect readback.
Incomplete stages cannot activate anything. The plan explicitly records
`activationAuthorized: false`; there is no new end-user command or launch routing
yet. This is implementation of preparation, **not** completed authoring adoption.

Focused generation verification passed three real-artifact tests in 148.478
seconds using an `e9cf05c7` project and selected `4d589cb4` artifacts. Checks cover
complete current payload preparation, unchanged project/target, independently
bound seals, changed/extra files and modes, links, occupied projects, forbidden
destinations and selected-artifact drift before stage creation. The test runner's
three focused checks also passed, and the new test is included in the projects
group. These results do not claim a current-authoring launch, switch/recovery,
post-upgrade import, full-suite integration or candidate acceptance.

Next: bind the prepared seal into a durable project-generation switch with exact
predecessor backup/recovery; route the existing launcher and map import through
that verified selection; then prove current authoring and another saved import on
the existing project after server upgrade. Preserve the original source and map
throughout. Runtime `bf0fcac5` integration/adoption and fresh packaged acceptance
remain separate pending gates.

At checkpoint `42377cd`, consolidated Editor verification covered all 48 suite
entrypoints: 527 tests passed, 13 were explicitly skipped, and the direct repository
independence check passed. Coverage was completed in three segments, not one
uninterrupted green command. The initial headless run reached a mandatory GUI
login test; enabling the coordinated display resolved that environment failure.
The next segment exposed an old verifier fixture using the retired thin migration
plan. That test now stages a genuine native Base project through the existing
production staging seam, with invented private state; no production validator was
relaxed. All four verifier tests then passed, including real execution, source
drift, pre-start and interrupted-process recovery, and closed evidence readback.
The unchanged passing prefix was reused, and every remaining module passed.

The 13 skips were the headless Base cycle (1; the separate prior genuine cycle
pass remains recorded above), external discovery input (1), packaged native
runtime input (1), external reconciliation matrix inputs (5), and unavailable
PowerShell execution (5). These are explicit limits, not fresh packaged or
cross-platform acceptance. The exact locked runtime parity check also passed.

The subsequent successor fixes have focused verification: eight successor
topology/permission tests and all 34 upgrade-transaction tests pass. The full
integration gate must be rerun before publishing/adopting the newer changes;
the earlier 48-entrypoint result is not an acceptance claim for a changed tip.
