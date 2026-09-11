# Preservation source intake boundary

## Post-upgrade discovery and normal startup correction

The owner accepted alpha.7 login and re-detection. The subsequent file audit
verified the installed composition, active imported package, transaction outputs,
retained historical files and player database. It found two workflow gaps:
discovery still chose the retained historical JAG map, and root `Start-Linux.sh`
still routed through the historical build/start scripts.

Managed discovery now gives the committed runtime ledger priority over all
historical probes. It verifies the installed generation and captures the active
layered package with matching Current Base authoring content. No player database,
private key or side-state file is copied into the project. An invalid ledger or
incomplete cutover blocks capture; there is no historical fallback. Capture needs
the matching installed Base provider, not a silently mixed newer composition.
Re-detected projects retain Base UI/launch behavior. Import from a new project
requires its immutable captured ledger and map baseline to match the active
target before transferring the project binding; a stale foreign capture refuses.
Existing projects are not rewritten: re-detect and create a new project to use
the installed map, or continue using the original editing project.

Public Upgrade Target Runtime actions install managed Linux root startup after
successful activation. `Start-Linux.sh` defaults to a local server/client pair;
`Start-Linux.sh server` and `Start-Linux.sh client` launch roles separately.
Startup reads and verifies the current ledger each time, including after a map
import, and never invokes the historical Makefile or `killall java` helper.
The paired launcher waits for fresh generation-bound server readiness before
starting its client, and stops only its own server when that client exits.

Startup installation is a separate, offline, single-file atomic replacement.
The exact previous script and its permissions are retained under
`.world-builder/startup-backups/<sha256>.sh`. If startup installation fails after
runtime activation, the user receives a distinct warning: the runtime remains
successfully upgraded, but the historical startup must not be used. Retry the
installed tools CLI while both roles are offline:

```text
install-current-launcher --target-root <server-root> --installation-root <World Builder folder> --confirm INSTALL
```

The explicit low-level transaction CLI does not infer an application installation;
call the installer separately after successful activation. This correction covers
Linux startup only; it does not claim new Windows player-launch acceptance.

Focused evidence: current instance/startup integrity and refusal tests, existing
map-import recovery tests, discovery regressions, and an opt-in desktop-model
capture/export/import-preview test against the stopped alpha.7 test installation.
The latter verified all 357 baseline files against the active imported package
and preserved the target ledger without applying an import. Synthetic startup
tests cover quoted installation names, backups, idempotence, malformed/missing
ledger refusal and mismatched readiness. Full suites and the old intake/upgrade
cycle are not repeated for this Editor-only correction; runtime lock unchanged.
Run the opt-in test with `WORLD_BUILDER_MANAGED_CAPTURE_INSTALLATION` pointing at
the matching installed candidate. It creates projects only in temporary fixtures.

The alpha.7 disposable installation received a launcher-JAR-only hotfix with
its prior JAR and package manifest backed up outside the installation. Original
release/source identity files remain the archive identity; this is a local
candidate overlay, not a new release. Real root `Start-Linux.sh server` loaded
the active imported map (26,813 scenery placements), became ready on the existing
loopback endpoint, and invoked normal save-on-exit shutdown. No account login or
new map import was performed in this smoke. The default paired GUI launch remains
an owner acceptance check; its role routing and readiness matching are covered by
focused tests. Existing projects and original historical launch helpers remain.

## Registration correction and baseline retest

After the alpha.6 receipt-order hotfix, the owner completed Import Map Changes.
The installed server loaded the imported map and listened successfully, but New
User registration failed: the upgraded client omitted the composition handshake
on its new registration socket. Configuration fetch alone was not proof of login.

Runtime `382d7d54031cb7c2eefb5020d923f9530d2aea9b` adds the existing handshake to
registration and both password-recovery socket paths. Server compatibility
validation remains intact. Focused real installed-client testing created a fresh
account, restarted the client and logged into that account, then shut down both
roles cleanly. The existing six-field handshake acceptance/refusal test and all
pre-login socket ordering checks passed. Full password-reset interaction was not
tested; no rendering, map, schema or storage implementation changed.

The owner requested restoring the disposable target to pre-upgrade state for
another end-to-end test. Original files matched every present preimage hash;
none required replacement. With both roles stopped and their locks held, the
managed `.world-builder` tree was moved intact to a separate sibling backup.
The originally absent ledger is absent again. All candidate folders, sealed
projects, map saves/exports and external transaction receipts were preserved.
This is a requested test-environment reset, not a new completed-upgrade Undo UI.

The next owner candidate must contain both this runtime correction and the
receipt-order fix. Create a fresh project against the restored baseline to pick
up the corrected runtime; do not silently rewrite the old alpha.6 project.
Then repeat editing/save, Upgrade Target Runtime, Import Map Changes, and normal
player registration/login. Treat character appearance confirmation as expected.

Adoption verification was narrowed after the coarse `transactions` preset began
running the unrelated legacy adaptive-transaction suite. That preset run was
interrupted and is not claimed as passing. The scoped checks are current map
import/recovery, current runtime instance, and initial-instance integration,
alongside provider parity and the real registration/handshake evidence above.
No full suite or production release acceptance is implied.

## Map import receipt ordering correction

The owner confirmed alpha.6 tools, save and Upgrade Target Runtime succeeded.
Import Map Changes then failed with `CONTRACT_VALUE_INVALID` for
`transactionReceiptIds`. Map preview appended a random transaction UUID without
sorting; the ledger requires unique lexically ordered identifiers. Upgrade
already sorts its receipts. Target inspection showed only the successful upgrade
generation, with no map-import generation published.

Map preview now constructs a sorted copy before binding the successor ledger.
Duplicate-ID and 256-receipt limits are unchanged; predecessor metadata is not
modified. The focused import/recovery module passed all 10 tests, including
out-of-order insertion, duplicates, capacity limits and existing recovery safety.
The full Base server-cycle fixture now uses descending map IDs before the upgrade
ID; that expensive live cycle was updated but not rerun for this isolated fix.
No runtime-provider change or target mutation is needed for the correction.
The corrected Editor tools JAR is built for a launcher-only installation;
the owner's existing project/export and successful target upgrade must be kept.

## Tool acceptance and software-overlay follow-up

After alpha.5 the owner reported all tools working. The follow-up is visual:
restore copy/paste markers, terrain line/rectangle anchors and other existing
editor overlays; move the coordinate overlay down roughly one text line; use
Preservation Magic/Prayer presentation and remove Summon from authoring.
The preview artwork was not lost: existing Surface drawing was gated on a
captured 3D frame that the software authoring profile intentionally lacks.
Restore camera projection for those overlays without re-enabling OpenGL or
altering map operations. Base spell/prayer definitions and action IDs must
remain canonical; do not substitute Base IDs into an Advanced composition.
Focused projection/menu regressions and actual software preview screenshots
are the acceptance scope, not another full gameplay/import gate.

Delivered alpha.6 with runtime `22b3eb4aa328` and Editor `13ce8786ff3b`.
Actual software screenshots verified classic Magic/Prayer lists, copy/paste
markers, line/rectangle previews and the build grid. Coordinates moved y34→48
and yield to open menus after visual review caught a tab overlap. Focused
projection/clipping/menu checks, the prior navigation latch regression, archive
inspection and packaged Linux launch/exit passed. Preview-state fixtures do
not substitute for full edit transactions; the owner's functional-tool report
stands, while normal interactive visual acceptance remains with the owner.

## Owner acceptance and active navigation correction

The owner officially accepted alpha.4's UI and visuals. Preserve that UI;
the active objective is working editor tools, beginning with navigation.
After level changes and click teleport from 120,648 to 146,658 on L0, the
destination activated but the displayed frame froze. Thread traces showed
ongoing software rendering, not a blocked server request. Investigation found
the retained-frame release was called only when a captured 3D frame existed;
the software authoring profile disables that capture. The correction must
release a freshly rendered software frame after authoritative activation and
terrain presentation readiness, not remove the readiness/retention safeguard.
Verification is limited to that boundary regression and actual navigation in
a disposable Base project, followed by candidate integrity/smoke checks.

Alpha.5 resolves that display-retention bug with runtime `43bdcdfa6ad5`
and Editor `aaaa19605ed4`. After the interrupted session, the existing archives
were reused and independently re-inspected; no rebuild/full-suite rerun was
needed. The owner clarified that same-level new-chunk loading is the acceptance
case. The packaged test now covers L-1 → L0 setup, then on **L0 only**:
120,648 → 146,658 → 300,550 → 120,648 (48-tile chunks 2,13 → 3,13 →
6,11 → 2,13). The first crossing and return use the click-teleport send path;
the distant jump uses the navigation coordinate action. Every destination
reached the expected coordinates, kept rendering, and released the old frame;
the distant destination screenshot was inspected. This is navigation acceptance,
not a claim that all other editor tools have been tested. Runtime unit coverage
also retains incomplete-activation and incomplete-terrain refusal cases.

## Current owner direction — Preservation authoring UI

After testing alpha.3, the owner superseded the fullscreen/side-bar request:
World Builder should use Preservation's UI and selectable x1/x2 software scaling,
not forced fullscreen/aspect-fit scaling. Keep the established map-editor tools,
middle-mouse camera rotation/pitch, extended zoom-out and on-screen X/Y/L
coordinates. Remove Spoiled Milk-only UI extras and nonfunctional renderer
controls. Retained Graphics options belong in General; no separate Graphics tab.
The minimum authoring window is 640×480 so the existing 396-pixel-tall editor
dock remains usable; stock Preservation's 512×346 minimum would clip tools.
This editor-specific size does not force fullscreen or replace x1/x2 scaling.

This is an explicit authoring-only presentation profile, not a server-gameplay
or normal installed-player UI migration. Client launch selects
`openrsc.worldBuilderPreservationUi=true`, software presentation and no forced
window mode or scalar. Runtime and Editor verification use focused presentation
and settings/input checks plus actual visual acceptance, per TESTING-POLICY.md;
do not repeat the unchanged full server upgrade/import suites for this change.
Advanced authoring software appearance must be identified separately from Base
acceptance rather than claimed from a Base-only screenshot.

Older project runtime snapshots remain immutable; the standardized UI requires
a freshly captured current client. Preserve installed alpha.3 and existing
projects when delivering the next candidate. First-run character confirmation
remains expected. The following alpha.3 fullscreen evidence is historical, not
the acceptance criterion for this new UI direction.

## Historical owner display finding — alpha.3

The owner confirmed alpha.2 launches successfully, but its native Base editor
opens windowed with a small UI. Base authoring explicitly disabled the shared
OpenGL presenter, primary window and input mapping; the fullscreen option could
not affect the resulting legacy software window. The authorized read-only Core
reference-client review confirmed the intended existing presentation: a 960×540
logical surface scaled to a borderless fullscreen window, with matching pointer
coordinates. Those components already exist in the runtime provider; no new
renderer or reference-code copy is needed.

The scoped correction packages the provider's pinned LWJGL presenter dependencies
and enables presentation/input/window ownership for newly captured Base clients.
Base continues to supply its software-rendered world frame; Advanced world mesh,
replacement compositing and UI overlays remain explicitly disabled. Normal
installed-player launch behavior is outside this map-editor correction.

Older projects retain their immutable embedded runtimes. A verified older client
without the complete presenter payload retains software launch, rather than
being overwritten or failing because libraries are absent. Consequently a new
installation alone does **not** fix an existing alpha.2 project's display. Keep
the old folder and any saved edits; use a fresh project for launch-only testing,
or resolve safe project-runtime adoption before promising the fix for saved work.

The owner confirmed alpha.2 was used only to test launching, with no saved map
edits to carry forward. Deliver the corrected candidate in a fresh sibling
folder and create a fresh project there; leave alpha.2 intact. Project-runtime
adoption is therefore not a prerequisite for this display-fix candidate.

Published runtime `888e06d397059757fe4089c42cd09b4970ccaeff` contains the
reviewed dependency correction. Its complete gate passed across preserved
segments: 246 checks/modules, 236 unittest passes and five explicit external
semantic-probe skips. A missing DISPLAY precondition and a shared-desktop focus
interruption were resolved by targeted resumptions, not source changes or a
repeat of the passed prefix. No Windows native execution is claimed.

The exact selected runtime also passed the native Editor lifecycle check: four
tests, including real Base creation and two authenticated authoring sessions,
with unchanged source/target, clean exits, and explicit log assertions for the
GLFW primary window, borderless fullscreen and automatic aspect-fit scaling.
The provider regression checks 960×540 input mapping at 1440p and 4K/HiDPI.
This is not a manual visual review of the owner's monitor or Windows acceptance.

The integrated Editor gate passed all 49 entrypoints in 1,686 seconds: 542
unittest passes, 12 explicit skips and the direct repository-independence check.
This includes the real upgrade/login/two-import/recovery cycle. The skips are
external discovery (1), opt-in native integration (1, separately covered by the
focused Base authoring run above), external reconciliation fixtures (5), and
unavailable native PowerShell (5).

Remaining delivery is fresh alpha.3 candidate packaging, independent archive
inspection and the bundled-Java launch check. Launcher readiness alone does not
prove display scale. First-run builder-character confirmation
remains expected, not a failure. No updated installed candidate is claimed here.

## Owner candidate finding — 2026-09-11

The installed v0.8.0-alpha.1 candidate detected the prepared Preservation copy,
but the owner's Swing project-creation attempt failed with `CAPABILITY_MISMATCH`:
historical Preservation requires native Base without a custom overlay/migration
choice. The generic desktop preview still inspected portable content and could
automatically import stock definitions as a custom provider. Native Base's
creation guard correctly rejects the resulting overlay. This is a desktop
routing defect, not an instruction for the owner to select provider files.

The correction routes compatible historical Preservation directly to a native
Base confirmation dialog before legacy-migration or portable-provider setup,
for both normal detection and advanced source selection. It retains project
naming and cancellation, uses the bundled Base selection, and leaves all
lifecycle guards intact. Other map formats keep their existing content choices.
Regression coverage checks this early routing and exercises real project
creation/reopen/export through the launcher model against full public stock.

An actual Swing confirmation probe then found a second packaging-only blocker:
native content capture and historical decoding treated the entire installation
ancestor as a provider input, refusing its sibling `projects/` tree. Both now
reject overlap with exact selected input files rather than their common
installation ancestor. Decoder attempts still cannot overlap original map inputs.
Selected-file hash/mode checks, fresh output, immutable evidence and verification
remain required. A relocated real packaged-catalog regression verifies both
successful sibling project capture and pre-mutation rejection when the project
would contain selected input files, including unchanged provider bytes/modes.

The previous packaged smoke check proved launcher startup and model detection,
not the Swing confirmation-to-creation path. That distinction matters: detection
alone is insufficient acceptance for the next candidate. The installed alpha.1
folder and any owner-created state must not be overwritten as part of testing
this correction. A source fix is not an installed candidate update; fresh
packaging and verification remain separate steps.

The corrected development Swing probe reached real project creation, then
exposed a separate bundled-Java-17 server startup failure in Log4j caller lookup.
The runtime provider's fat JAR contained Java-version-specific logging classes
but omitted the `Multi-Release` manifest flag. Published runtime
`c6c6d0901a549062c2e0c4be3b323a34c591e388` corrects that build flag without
upgrading dependencies. Its full gate passed (245 entrypoints, 232 unittest
passes, five external-input skips), with additional bundled Java 17 logging/JDBC
and real installed server startup/restart checks.

An isolated development installation using that exact provider passed actual
Swing Detect Server Map, project confirmation, creation, native Java 17 editor
readiness and clean exit. The first-run builder-character confirmation is an
expected interaction, not a startup failure. The replacement still requires
the integrated Editor gate, fresh archives and independent archive inspection;
this development probe is not a claim that the installed alpha.1 was updated.

### Replacement integration gate — complete

The full integrated Editor gate at `ce6cb5c` with runtime `c6c6d090` passed in
one uninterrupted run: 49 entrypoints, 541 unittest passes, 12 explicit skips,
and one direct repository-independence check (1,696 seconds). The real Base
server upgrade/login/two-import/recovery cycle passed in 330 seconds. The skips
are external discovery (1), opt-in native integration (1), external decoded-map
reconciliation fixtures (5), and unavailable native PowerShell (5).

The next restricted artifact is **v0.8.0-alpha.2**. Fresh archive inspection and
an exact packaged Swing walkthrough remain delivery steps. Install it beside
alpha.1, preserving the old folder and all owner-created state. First-run
character confirmation remains expected. No production release, Windows
execution acceptance, or completed user test is implied.

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

### Integration checkpoint

Editor work through `510b2fe` is now merged locally on manager `main` with an
identical tree. The 16-selection discovery/project group passed in 334 seconds:
176 tests executed successfully and six external-fixture skips. The full-stock
native project creation/reopen/export tests passed; they did not launch the GUI
in that group.

The manager now adopts published runtime
`30833010dcd16cf2fca6997e1de3e2ebabccba04`, including the reviewed private ban-file
creation fix. Its full runtime gate completed in two segments on the identical
worker tree: 245 top-level checks/modules, 232 unittest passes and five skipped
external Preservation semantic-probe tests. Actual installed launch, login,
map loading, persistence and restart checks passed. Editor runtime parity
passes; the combined Editor full gate is next. No candidate acceptance or
production release is implied by this checkpoint.

### Combined candidate code gate — complete

The gate covers all **49 Editor test entrypoints**: **539 unittest passes,
12 explicit skips, and one direct repository-independence check**. Coverage
was completed in segments, not one uninterrupted green run. The first run
exposed a validator contradiction when T5 unsafe evidence coexisted with a
customization needing a port. `f1fe86a` fixes only refusal precedence: the target
remains blocked, both evidence rows remain visible, and no target mutation is
allowed. All 18 foundation and 34 upgrade-transaction tests then passed,
including a new mixed-evidence regression. All 33 remaining entrypoints passed.
Earlier positive workflow results are retained because the change affects only
the previously invalid unsafe-refusal case.

The integrated full-stock Base server cycle passed in **329 seconds**, including
initial upgrade, normal login, two saved-map imports/restarts and recovery
checks, on a separate invented-state fixture. The owner's prepared test copy
was not consumed by these tests. Runtime parity against published `30833010`
passes. The 12 Editor skips are external discovery (1), opt-in packaged native
runtime integration (1), external decoded-map reconciliation inputs (5), and
unavailable native PowerShell (5). They are not counted as passes. Candidate
archive inspection and the actual packaged launcher/default-detection smoke
check remain separate delivery steps; Windows execution is not claimed.

The next artifact is restricted **v0.8.0-alpha.1**, not a production release.
Its generated archive inspection report and the test copy's local handoff notes
record packaging/launch results. Do not open the production release gate or
promote candidate archives in place. Owner testing still precedes acceptance.

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
