# Content lifecycle delivery

Status: implementation in progress; no new owner candidate accepted.
Objective adopted October 2, 2026 from the owner's content lifecycle handoff.
Baseline Editor: `087e64b5c2624c2e284e43d3e69db64f6ab0ad91`.
Pinned runtime: `deb55301702dc80f49497ac722341895145363e1` (the lock file remains authoritative).

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

These are manager-run intermediate results, not a packaged-candidate or private
game acceptance claim. Final packaging and the complete release test record
remain pending.

- Effective source discovery and bundle capture passed on a sanitized frozen
  maintained target. The Core-generated inert source descriptor also passed;
  changing one declared source hash was refused.
- Three changed imports passed with placement, movement, and removal of the last
  placement of the tested custom NPC, plus item, scenery, boundary and terrain
  edits. Rediscovery and reopen passed after each import. Exact inventories
  retained 9,129 pre-existing target files; only the selected activation and
  paired installed profiles changed, with new paired packages added.
- Fourteen content refresh cases and 75 project lifecycle cases passed after
  integration. Two additional immediate rebuild/recovery cases and one
  visual-only model refresh/import case passed after their later integration.
  The complete refresh suite now contains 17 cases.
- On the copied existing edited project, refresh retained all 11,390 predecessor
  files and all 1,789 saved map files, while leaving all 9,132 target files
  unchanged. Repeated imports from that successor are a separate running check.
- Snapshot re-verification covers a refreshed successor with no local import
  history. It binds an ordinary saved-map export and authenticated predecessor
  evidence, rejects changed confirmation inputs, and preserves rebuilt archives
  through interrupted recovery and historical undo boundaries.

The first real-copy refresh preview took about 83 seconds and apply about 160
seconds. Its launcher presents phase progress; repeated history verification
remains intentionally exact. These measurements do not imply final packaged
performance or authorization to reuse stale target checks.
