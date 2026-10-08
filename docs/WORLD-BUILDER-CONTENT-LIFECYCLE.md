# Content lifecycle delivery

Status: implementation in progress; no new owner candidate accepted.
Objective adopted October 2, 2026 from the owner's content lifecycle handoff.
Baseline Editor: `087e64b5c2624c2e284e43d3e69db64f6ab0ad91`.
Initial runtime: `deb55301702dc80f49497ac722341895145363e1` (the lock file remains authoritative for current builds).

## Outcome and bounded review

Finish an area using existing custom content, save/import it, rediscover and
reopen it, then incorporate newly added supported content without losing edits
or manually repairing evidence. The Slayer Tower is the acceptance scenario,
not a source of hardcoded NPC IDs or a server-specific exception.

The initial review is bounded to the demonstrated content/provider, project,
and import authority failures. Retain the editor, converters, immutable source
capture, content bundles, and transactional import/rollback machinery. Do not
replace the entire game platform or launch an unrelated editor rewrite.

Confirmed reproduction: `WorldBuilderNpcDefinitionProvider.validateProducerTarget`
compares provider selection with the current placed extension type set. Removing
the final placement of one type rejects unchanged definition/asset/provider
bytes. A synthetic regression records this failure. Broader lifecycle tests
must reproduce and then verify the product flow; the helper alone is not exit
evidence. Supplemental filename scanning and implicit ID allocation also need
bounded source authority rather than assuming every matching file is active.

## Authority inventory

| Stage | Existing authority | Required boundary |
| --- | --- | --- |
| Detect | Adapter-selected configuration, files, content provider, discovery fingerprint | Effective available content independent of map population; inert verified inputs |
| Create | Immutable snapshot and normalized `source/content-bundle` | Copy the complete required evidence/dependency closure, including unplaced content |
| Render | `working/content-bundle`, runtime preparation and authoring catalogs | Verified projections of the same normalized content; no target gameplay writes |
| Reopen/save | Project manifest, immutable snapshot, working map and bundle | Target unavailable or changed must not prevent access to saved work |
| Export | Verified saved working package and project evidence | Placements reference stable content identities; historical exports remain immutable |
| Preview/apply | Initial or reconstructed transaction authority, paired target catalogs, offline lease | Verify actual post-transaction supported content, exact preview binding, no force path |
| Rediscover | Current selected map and content | Changed placement population does not invalidate unchanged content |
| Refresh | Previously deferred | Explicit reviewed content revision with preserved map and new verified import baseline |

## Shared implementation contract

Use the existing validated content bundle as the durable effective content
authority. Derive a normalized index for floor, wall, scenery, NPC and ground
item definitions, their dependencies and provenance. Distinguish a definition's
meaning from changes to its containing file: an additive record must not mark
every existing record as changed. Numeric identity is retained, including
overrides of vanilla IDs. Ambiguous identities or active sources are blockers.

Content refresh uses an automatic successor project revision. Preview builds
and verifies fresh content in a private unpublished stage and reports additions,
changes, removals, affected map references, and blockers. Apply rechecks the exact
parent state, target evidence, and reviewed delta, then publishes a successor
with the same display name and selects it automatically. Its working terrain
and placements are copied from the saved edited project, never from the fresh
target map. The predecessor directory, snapshots, receipts, exports, revisions,
and backups remain intact and accessible as prior content history.

A new captured predecessor descriptor binds the transition. It is not a
fabricated import receipt and does not authorize reversing a target across a
content/rebuild boundary. Target transition verification checks the old initial
or latest installed map/configuration, verified runtime authority, and typed
content-only differences. Unrelated map/configuration/source/JAR/dependency
drift is not accepted as content refresh. Existing floor successor authority
must not be reused as an unchecked content drift exemption.

Changed referenced meanings and removed referenced definitions block acceptance
with an actionable resolution. An additive update requires one confirmation.
Unsupported formats require a bounded adapter or trusted build-time inert
export. Never execute target plugins/JARs during discovery. Project refresh
does not install target definitions or simplified gameplay.

## Delivery and dependencies

1. Reproduce the failing lifecycle and reconcile architecture: provider helper
   reproduction exists; product-level reproduction and authority design are
   being implemented before broad refactoring.
2. Correct content/provider selection and verify the existing-content round trip,
   including never-placed definitions, overrides, additions/removals of placement
   types, all supported families, and repeated imports.
3. Implement Detect New Content, the automatic revision transition, target
   binding, and preservation/refusal/recovery tests. Coordinate a maintained
   effective-content export/catalog workflow with Core through a written contract.
4. Validate a real copied project and isolated maintained game. Core owns its
   game integration and private gameplay verification; no shared target or
   public deployment is modified by Editor testing.
5. Package exact tested commits, test desktop detect/create/import and a populated
   installation update, and supply one consolidated inspection checklist.

The critical path is effective-content authority → safe refresh/import lineage →
complete lifecycle and packaged update acceptance. Adapter and refresh workers
can proceed independently after the interface contract above; actual maintained
game acceptance depends on the Core-owned content/export integration. No owner
retest is requested at the provider-only or preview-only milestone.

## Acceptance and preservation

Automate discover/create/edit/save/export/import/rediscover/reopen/repeat using
synthetic supported targets, then a sanitized copy of the owner-designated
project. Include every supported family, unplaced custom content, a vanilla-ID
override, additive refresh and used-content conflict, unavailable target,
external map drift, input tampering after preview, injected failure/recovery,
ordinary compatible rebuild, and exact already-active exports. An edited export
must differ from the installed package. Capture phase duration and progress;
measure repeated immutable reads before optimizing them.

Affected discovery, project, content, transaction, update and desktop tests are
required. This cross-component lifecycle change also requires the full Editor
integration suites before its final candidate; any provider change receives its
own affected validation and exact published lock adoption. Historical alpha 10–12
evidence capture, definition preflight, transaction history, and package-collision
regressions remain mandatory. Never represent a helper or CLI preview as complete
desktop/game acceptance.

Compare inventories and expected file changes, not just file counts. Refresh
may write only new project revision/cache/registry state. Ordinary map import
may write verified paired map packages and explicit reviewed activation metadata.
Loader upgrades remain separate transactions with narrow reviewed target writes.
User projects and all historical evidence stay intact. Existing runtime/gameplay,
custom content, account data and unrelated target files are not replaced.

Final delivery records exact commits, package checksums, supported schema and
migration limits, Core prerequisites, manager-run versus owner-run evidence,
untested platforms, and detect/continue/refresh/save/import/recovery instructions.

## Integrated verification checkpoints

These are manager-run results on disposable fixtures. The alpha 13 candidate,
packaged desktop and populated update checks passed. Core private-game
acceptance and maintained staged re-export remain pending; this document does
not request an owner retest.

The complete NPC producer is now captured rather than inferred from the placed
population. The maintained fixture contains 877 effective NPCs, 249 referenced
animations and 4,521 source frames, with no unresolved NPC visuals. All source
and selected asset hashes, lookup precedence, configuration flags and absent
resolver candidates are verified. These counts describe the fixture, not
hardcoded supported content. Independent comparison verified 4,509 normalized
preview frames against their captured source bytes. Secondary attack frames are
retained as evidence but are outside the authoring preview's animation model;
the review reports these limits separately from missing visuals. Fourteen
procedural scenery warnings remain explicit.

A private desktop rehearsal displayed preservation and custom NPCs, including
Imp, Bunny, Duck, Ranger and Naga. The exact final package must repeat visual,
terrain, collision-bearing scenery, save/export/import and reopen acceptance.

### Existing project and target preservation

The earlier placement lifecycle passed three changed imports: initial custom
NPC placement, movement, and removal of its last placement, alongside terrain,
item, scenery and boundary edits. Rediscovery and reopening passed each time.
All 9,129 unrelated pre-existing target files were retained. Three reviewed
activation/profile files changed and new paired map package files were added.

With the complete producer, refresh retained all 11,181 files in the previous
project revision, including saved terrain, placements, snapshots, exports,
receipts and backups. The successor then passed two changed imports and
reopening after each. All 16,658 unrelated existing target files were unchanged.
Only the selected primary activation and paired installed profiles changed;
7,156 new files were independently matched by path, size and hash to the two
reviewed paired-package transaction plans. The frozen reference fixture was
unchanged. No target gameplay code or definitions were replaced.

The representative complete refresh took about 81 seconds for preview and
162 seconds for apply. Its two imports took about 31 and 152 seconds; saved-map
export took about six seconds and reopening about five to six seconds. These
measurements retain exact mutable-target verification and are not promises
about every server's performance.

### Verification and packaging

The integration tree includes producer, discovery, content-index, project,
refresh, transaction, schema, converter and updater regressions. The full
Editor gate passed at `0453e296e9e79db46fd6829d9d38e80ecb347a68`
with runtime `67361019f28ba52f6acf08a19f6499302a79d8c4`: 64 selections
in 1,409 seconds, 750 tests including 35 explicit optional-input/platform skips.
The locked provider includes independently tested NPC mask semantics and a GUI
test-helper focus correction. Mandatory real desktop login, restart and
preservation now pass without injected input workarounds.

The final rebuild/export interaction is corrected. Equivalent client archive
rebuild followed by regenerated complete visual export can now be re-verified;
re-verifying before export also works. The transition accepts only independently
verified archive bindings in an otherwise unchanged producer, retains exact
bytes in project history, and writes only the compatibility proof on the target.
The exact worker tip `26d05d3a5fb01b6a75072a9f8fbc4a299edeee88` passed
160 focused safety tests in 887 seconds: 23 contracts, 11 producer lifecycle,
eight content-authority, 18 refresh, seven rebuild and 93 transaction cases.
Coverage includes three successive rebuilds, embedded resource probes, selected
configuration, refresh and later imports, preview drift, damaged retained
evidence, interrupted recovery and historical undo boundaries.

The alpha 12 updater omitted a shipped schema from its own exact allowed file
list. Commit `4f831702e8556fc42b8fa5f0b8fb73f00486c611` corrects the list
and permits the verified new package's updater to select an existing
installation. It does not patch the installed manifest before verification.
Twenty-two updater tests passed; five PowerShell execution tests were skipped
because PowerShell is unavailable. The actual Linux update fixture now retains
33,504 durable files across the selected complete-content revision and its two
predecessors. Its packaged update and offline reopen passed with all 33,504 durable files
byte-identical and the same selected project. A local candidate installation
entry point also passed against a second populated copy.

Core's maintained complete-content readiness check is available at checkpoint
`66854862fb3a3638eceb181807cfac47f520d518`. It is read-only and verifies
the effective producer, paired catalogs, source/archive/configuration bindings,
lookup probes and frame dependencies. Core owns the later actual private-game
acceptance; its shared target and normal deployment remain untouched by Editor
mutation tests.

### Exact packaged lifecycle acceptance

Candidate `v0.8.1-alpha.13` was built from Editor
`cfd1ffef7f73356c6cb6dc765482b8dfc3d58697` and runtime
`67361019f28ba52f6acf08a19f6499302a79d8c4`. Both platform archives passed
independent manifest, inventory, identity, toolchain and checksum inspection.
Windows native execution remains untested.

The exact Linux archive passed desktop Detect/Create, five complete NPC visuals,
terrain and scenery placement, two distinct changed exports and successful UI
imports, reopening after both, and unchanged Detect New Content review. Each
import completed 3,581 verification checks. All 9,135 unrelated original target
files were unchanged; only the three reviewed activation profiles changed.
All 7,156 added files matched the reviewed package plans. All 9,138 frozen input
files and 5,492 immutable project evidence files were verified intact.

Evidence: `/tmp/world-builder-final-desktop-p9r81lbx/acceptance-results.json`.
Package and local updater:
`/home/justin/world-builder-test-builds/targeted-runtime-v0.8.1-alpha.13`.
This is a restricted candidate, not a production release or deployment.

The actual player-game acceptance belongs to Core and is proceeding separately.
Core reports that the private game displays the five imported NPCs and terrain
and scenery edits, with floor traversal and Pine Tree blocking checks passed.
Naga combat, Slime Solvent display/use, and saving equipped items on logout also
worked. These are partial manager-reported observations: reconnect persistence,
both Naga attack modes, and an unprotected enemy status effect remain unconfirmed.
They do not constitute complete gameplay acceptance.
Its producer also needs a maintained staged re-export workflow: the frozen
exporter currently only creates new paths. The concrete bounded work and the
Core manager's reported direct-authorization requirement are documented in
[Core Staged Content Export Handoff](CORE-STAGED-CONTENT-EXPORT-HANDOFF.md).
Core has now reported direct owner authorization and started the scoped isolated
implementation. Its delivery and complete maintained lifecycle acceptance remain
pending.
Editor drift checks remain strict. Fourteen code-generated scenery appearances
still have explicit unresolved warnings/placeholders, and intentional NPC
animation preview limits remain reported. Existing saved work is preserved.
