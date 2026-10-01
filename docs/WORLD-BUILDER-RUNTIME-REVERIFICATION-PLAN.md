# Runtime verification after server rebuilds

Status: implemented for the next restricted test candidate. See
[the supported workflow](WORLD-BUILDER-RUNTIME-REVERIFICATION.md). This follows the
archive diagnostics and duplicate legal metadata fixes. It applies to supported
targets generally and does not introduce a Spoiled Milk exception.

## Required outcome

A user who normally rebuilds a previously integrated server should have an
explicit way to verify its current map compatibility and resume map-only imports
without discarding saved editor work. Updating recorded hashes alone is not
verification. Current source, active bytecode, paired client support, dependencies,
and map state must be checked before new compatibility evidence is committed.

## Original constraints

The targeted proof binds its exact descriptor, source inventory, and archive
hashes. A rebuilt archive can fail that check because of changed ZIP metadata,
compiler output, or actual behavior changes. These cases need different treatment.

The importer also binds successful transaction history to exact installed state.
It presently rejects Upgrade Target Runtime after an outstanding successful
transaction. The initial upgrade path requires the original discovery lineage;
its floor preflight checks the original literal initializer. Consequently,
recompiling and replacing the installed proof is not sufficient to safely resume
imports from an existing project.

Relevant implementation points are `WorldBuilderTargetMapIntegration`,
`WorldBuilderAdaptiveImporter.operate`,
`WorldBuilderAdaptiveMutationProfile.prepareRuntimeUpgrade`, and
`WorldBuilderInstalledFloorContent.verifyTargetClientPrefix`.

## Bounded workflow

1. Resolve the selected project's latest successful transaction and exact
   installed integration evidence. A target-supplied receipt alone must not
   establish the previous trusted state. Refuse unresolved recovery first.
2. Classify differences. Terrain, placements, definitions, configuration, client
   selection, and map activation must retain the existing lineage safeguards.
   Only reviewed runtime/source changes qualify for this operation. Report
   unrelated drift separately; do not absorb it into a refreshed fingerprint.
3. Verify the current target with the application's exact trusted contract.
   Require an already integrated source form, current loader/protocol behavior,
   coherent active binaries, complete affected consumer coverage, valid floor
   definitions, and no ambiguous class providers. Use isolated compilation
   without running target build scripts or annotation processors. A source port
   still needed to satisfy the contract belongs to a separate reviewed upgrade.
4. Present an explicit preview describing runtime differences and the evidence
   to be updated. Re-verification should retain the rebuilt archives and target
   custom behavior, rather than regenerate gameplay binaries. Bind every input
   hash and inventory to the reviewed preview and acquire the offline lease.
5. Commit evidence through a recoverable transaction. Record the actual current
   runtime as the before-state, preserve the previous proof and history, and
   verify the complete resulting state. Never make an older rollback silently
   reverse the user's independently performed rebuild.
6. Make subsequent map-only imports consume the verified successor evidence
   while preserving the project's saved map edits. Check repeated imports,
   sibling project relationships, recovery, and historical reversal rules.

The initial implementation should explicitly bound which rebuild differences it
can prove. Unsupported changed map source or dependencies must produce a specific
report. Broader source adoption can follow under the same common contract; no
server names, IDs, or manually manufactured compatibility receipts are needed.

## Acceptance fixtures

Use synthetic disposable targets to test unchanged rebuilds with different JAR
timestamps, supported compiler/debug variations, retained custom behavior, and
repeated map imports. Test changed map semantics, stale source, binary-only
consumers, ambiguous dependencies, floor drift, simultaneous map changes, and
preview-to-apply drift as refusals. Exercise failure rollback and interrupted
recovery of the evidence transaction, then confirm saved project edits and the
rebuilt target archives remain intact.

This is an import/history feature and requires the affected transaction suites,
not merely archive unit tests. Review and implement it separately from the
metadata normalization change so passing archive checks cannot be mistaken for
rebuild acceptance.
