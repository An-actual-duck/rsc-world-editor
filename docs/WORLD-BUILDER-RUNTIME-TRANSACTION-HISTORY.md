# Runtime upgrade authority across map transactions

This fixes a no-rebuild transaction-history failure. After a targeted runtime
upgrade and a successful map import, a later import could compare an upgraded
source file against the project's original, pre-upgrade snapshot. The latest
map transaction did not itself replace that source, so the old reconstruction
mistook the legitimate upgrade for target drift.

The fix is Editor-owned and contains no server-name or custom-content exception.
It does not perform post-rebuild runtime re-verification; that separate feature
remains described in [the re-verification plan](WORLD-BUILDER-RUNTIME-REVERIFICATION-PLAN.md).

## Authority and verification

`runtimeUpgradeHistory` records predecessor transaction IDs and exact receipt and
canonical mutation-plan hashes. Only successful, unreverted imports containing
runtime-compatibility actions contribute authority. The verifier checks project,
export, capability, selected-configuration and plan/receipt bindings; restores
runtime actions through the existing bounded action reconstruction; verifies
receipt after-state claims and original before backups; and checks continuity
when a later runtime action replaces an earlier one.

Unchanged upgraded paths are checked against those retained after states. The
current transaction's own action paths retain their normal action-state checks.
Unrelated original evidence retains snapshot drift checks. Target-provided
capability hashes alone cannot establish transaction-history authority.

History is a bounded flat list rather than recursive floor-project ancestry.
Imports which also update runtime activation profiles can add a reference; purely
map-only transactions without runtime actions do not. Sibling floor-project
inheritance remains a separate verified contract. New floor proofs also bind the
parent runtime-history references and flatten their after states; proof verification
re-derives those states from the parent evidence, including snapshot-captured
upgraded Java sources. Historical floor proofs without that optional field keep
their existing interpretation. Runtime action reconstruction
uses the original verified configuration's immutable layout fields, not live
map activation paths or a guessed configuration backup in a sibling project.
Older runtime action formats which cannot reconstruct their exact required
content from retained evidence still refuse; this change does not repair missing
legacy runtime evidence.

Older successful plans without the optional field are supported by reconstructing
authority from their retained successful receipts, plans, exports and backups.
Those historical bytes and the original snapshot are never rewritten. New plans
bind the reconstructed references, including for preview-to-apply comparison.
Missing, altered, inconsistent, reversed or ambiguous required authority fails
closed. Inherited target source/JAR drift remains a refusal, including changes
at import, internal undo and interrupted-recovery confirmation boundaries.

## Retest an existing project

Use the corrected Editor candidate (alpha.8 or later containing this fix) with a
disposable offline copy of the target and the **complete existing project**:
retain its source snapshot, saved working map, exports, receipts and backups.
Do not rediscover into a new project, repeat the successful runtime upgrade,
reset the snapshot, delete receipts, or edit transaction JSON to clear the error.

1. Open the retained project and save/export its current map if necessary.
2. Preview the next import. Upgraded sources should now be verified against the
   recorded runtime upgrade; review the proposed map changes and exact target.
3. Apply the reviewed import, make another distinct map edit, save/export, and
   preview/apply again. Repeat with another edit.
4. Confirm the maps and placements changed as intended and unrelated custom
   content remains intact. Keep the project history with the project.

Actual source changes or a rebuilt JAR still require separate investigation;
this fix must not turn them into accepted history. Internal undo/recovery tests
below do not introduce an end-user completed-import Undo feature.

## Verification

Use a compiler-capable Java 17 on `PATH`. The focused affected suites are:

```bash
python3 tests/myworld/test-world-builder-adaptive-transactions.py -v
WORLD_BUILDER_TARGET_MAP_PROVIDER=/path/to/reviewed/runtime-provider \
  python3 tests/myworld/test-world-builder-target-map-integration.py -v
python3 tests/myworld/test-world-builder-adaptive-contracts.py -v
```

The adaptive suite contains dedicated `test_runtime_history_*` cases for four
successive imports and complete internal undo, legacy missing-reference history,
missing/changed authority, source/JAR and reviewed-preview drift, interrupted
import and undo recovery, and source drift at all three confirmation boundaries.
They preserve saved edits and compare exact target inventories. The supplied
incident artifacts are read-only evidence; manager acceptance uses another
isolated copy, without building or launching the target game.

Integration verification on 2026-10-01 covered 76 distinct adaptive transaction
cases across the original suite and focused corrections, with the final 11
history/ancestry cases passing on implementation tip
`20414f0430d9519fab62f95ce98032db21174dbc`. All 29 targeted integration cases
passed. The offline-port case passed in an isolated network namespace, avoiding
the port held by the simultaneous copied-audit import. The merged contract
suite covered all 23 cases; its schema inventory assertion was updated to list
the new optional history field and that case then passed. These are affected-area
checks, not a production release gate; the historical full baseline remains in
`TESTING-POLICY.md`.
