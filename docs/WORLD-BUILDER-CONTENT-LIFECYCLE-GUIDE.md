# Continue building with captured content

This guide describes the content lifecycle implementation. Candidate acceptance
and exact build information are recorded separately in
[Content Lifecycle Delivery](WORLD-BUILDER-CONTENT-LIFECYCLE.md); that document
currently marks the delivery in progress.

## Continue an existing map

Launch World Builder and use **Continue Working** with the selected project.
The project retains its own map and content library. Opening it does not import
new server files, and an unavailable server does not prevent local editing.
Moving, duplicating, placing, or removing a supported NPC, item, scenery object,
or boundary changes the map. Removing the last placement does not remove that
definition from the library.

Save the editor's map and close the editing session before using launcher
actions. Keep the target offline for **Import Map Changes**. Review its preview
and confirm; World Builder rechecks the exact target state before applying it.
Subsequent changed imports retain their own receipts and backups. An exact
already-active map does not need another map installation.

Use **Detect Server Map** to start a new attached project. For supported inputs,
the normal flow captures available definitions and visual dependencies,
including content that has never been placed. Provider-file selection and
manual cache editing are not routine setup steps.

## Bring newly maintained content into saved work

The server maintainer first makes the content available through its normal
definition/assets workflow and regenerates its effective-content evidence and
paired placement catalogs. The
[maintainer coordination contract](CORE-CONTENT-LIFECYCLE-COORDINATION.md)
describes those responsibilities. Editor presentation data does not replace
the server's gameplay definitions.

1. Save and close the editor, then select the current project in the launcher.
2. Choose **Detect New Content**. It checks the target map, runtime authority,
   history, definitions and visual dependencies, then compares the libraries.
3. Review additions, visual changes and conflicts. Conflicts identify the
   affected IDs and their references in the saved map.
4. Accept a supported update. The launcher selects an updated content revision
   with the same project name and the exact saved terrain and placements.
5. Continue Working, place the new content, save, then import normally.

Previous content revisions remain in the project list with their snapshots,
exports, receipts and backups. They remain usable for local editing and export.
After refresh, target operations use the updated revision; historical revisions
cannot silently reverse the target across the content transition. Keep the
complete installation's `projects` directory when moving or backing it up,
because retained runtime verification may need an earlier revision's evidence.

The initial policy accepts additions and reviewed visual updates. Existing-ID
semantic changes and removals require resolution, including currently unused
IDs. It does not discard placements or reinterpret an ID to make an import pass.
Already-installed floor additions must preserve the previous material prefix
and have matching server/client definitions and their verified descriptor.

An external target-map change is a separate conflict. Detect New Content never
replaces the saved working map with the target map. A stale target catalog must
be corrected by the maintainer's supported workflow; retaining an old visual in
the editor is not proof that the target can accept that placement.

## Rebuilds and recovery

Use **Re-verify Target Runtime** after a supported normal rebuild of an already
integrated runtime. The target must remain offline. Verification checks the
maintained source/binary relationship and dependencies; it does not merely
refresh archive hashes or replace gameplay with the editing runtime. Content
refresh retains the original integration evidence through its predecessor
revisions. Source changes and other unsupported differences receive a refusal
requiring separate resolution.

If a transaction was interrupted, use **Recover Interrupted Server Transaction**
before another import or content refresh. Preserve the complete project and its
transaction evidence. Recovery checks what was actually written and restores
only the state it can verify. Historical reversal cannot replace independently
rebuilt binaries with older archives. There is no force-import procedure.

## What each action may write

| Action | Allowed writes |
| --- | --- |
| Detect/create | Isolated project and generated provider/cache data in the Editor installation; no target-map or gameplay writes |
| Detect New Content | A verified successor project, its generated authoring runtime/cache, and project selection/registry; prior project bytes and target files retained |
| Import Map Changes | Reviewed paired map packages and explicit activation/profile metadata, plus project transaction backups/receipts |
| Re-verify Target Runtime | Reviewed compatibility evidence through a recoverable transaction; independently rebuilt archives retained |
| Upgrade Target Runtime | Separately previewed, supported loader/server/client integration and necessary map metadata/dependencies; unrelated gameplay retained |

The exact preview is authoritative for a transaction's paths. In the maintained
layered fixture, ordinary imports install under server/client
`world-builder/packages/<content-hash>` and update the selected map activation
and installed profile descriptors. They do not rewrite NPC/item definitions,
custom assets, dialogue, combat code, accounts, or unrelated configuration.

## Supported inputs and diagnostics

The current adapters support bounded OpenRSC definition formats, declared
effective NPC append order and item ID overwrite order, supported patch/world
overlays, and validated presentation assets. An inert maintained export can
declare active sources. Otherwise only recognized loader structures are read;
World Builder does not execute arbitrary target plugins or JARs to discover
content. Unknown load order, ID conflicts, missing dependencies, stale exports,
and unsupported runtime changes require an actionable refusal.

When reporting a refusal, retain its code, source and next step, the selected
project, and the diagnostics exported by the launcher. Keep saved work and
historical evidence intact while resolving the reported input.
