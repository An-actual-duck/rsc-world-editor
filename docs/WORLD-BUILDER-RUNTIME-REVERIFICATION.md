# Re-verifying an independently rebuilt runtime

After rebuilding an already-integrated target, keep it offline and select the
existing attached project. Choose **File → Re-verify Rebuilt Target Runtime…**.
Review the preview, target, checked runtime inputs and evidence-only change,
then confirm. Resume **Import Map Changes** using your saved project.
Re-verification does not require exporting or discarding current editor edits.

## What is verified

The editor follows retained integration and map transactions to their original
plans, receipts, backups and generated content. A fresh attached project created
from an already-integrated target may instead use its immutable captured installed
proof and the exact matching server/plugin/client archives. This is explicit
snapshot authority, not a fabricated upgrade receipt. Later map-only transactions
retain that baseline; content revisions authenticate the original ancestor through
their retained origin and registry links.
It compares every active archive entry with those retained integrated archives,
then compiles current maintained server/plugin/client sources in isolation using
the bundled Java 17 compiler, without target scripts or annotation processors.
The standard desktop companion client sources are included when present.
It checks source/binary coherence, paired map support, the application's exact
map contract, dependency hashes and inventories, affected class providers, floor
metadata, current map activation and unrelated discovery evidence.

The supported equivalence check accepts ZIP timestamps, ordering/compression,
constant-pool layout and structurally supported debug metadata differences when
class semantics and class-file major version remain equivalent. Tests cover
Java 17 compiling source/target 8 with `-g`, `-g:none`, and `-g:lines,source`.
`Created-By` and `Ant-Version` manifest provenance may change; missing editor-owned
map markers can be verified through the paired contract. Runtime manifest
attributes (including main class, class path and multi-release behavior), archive
entry inventories and non-class resources must retain their verified meaning.
This is a bounded bytecode comparison, not proof of arbitrary compiler equivalence.

Previously captured integrated sources and dependencies must remain exact.
Client source files not captured by the original integration are checked against
both their current active classes and the retained integrated archive semantics;
the new proof records their complete inputs. Newly changed gameplay, source
ports, dependencies, class inventories, unsupported bytecode and stale sources
require a separately reviewed integration. They are not accepted by refreshing
hashes. The original authority must remain complete: either retained integration
history or a verified captured installed proof with all matching source/archive
evidence. Missing or changed evidence is refused. New discoveries also capture
the installed server build file when the proof declares it, because integration
may have removed its old owned build guard. Older snapshots without this file
support only the unchanged build hash authenticated by their original proof;
re-verification cannot adopt an uncaptured transformed build file from the target.
Sibling floor-upgrade projects currently refuse re-verification; a project with a
successful re-verification boundary cannot authorize target imports from a
floor-upgrade descendant.
Retain the complete project and request supported lineage migration in that case.

## Maintained NPC visual exports after a rebuild

For a project with a captured complete NPC producer v2 export, either order is
supported while the target remains offline:

1. Normally rebuild, regenerate the maintained visual export, then preview and
   apply **Re-verify Rebuilt Target Runtime**.
2. Normally rebuild, preview and apply re-verification, then regenerate the
   maintained visual export.

After either sequence, use **Detect New Content** to capture the current export
as a project revision, then continue saving, exporting and importing map edits.
Saved terrain and placements remain intact; earlier revisions and their history
remain available. The editor never runs the target's exporter or rewrites its
producer manifest.

When the export was regenerated before re-verification, the only accepted
producer differences are whole-archive SHA-256 bindings for the independently
verified equivalent active client JAR. Matching embedded-resource probes may
update that same archive hash; resource paths, presence, entry hashes, probe
order, definitions, presentation values, frame bytes, configuration, source
roles and every other producer field must remain canonically identical to the
captured authority. Formatting differences are preserved as exact observed
bytes. A changed appearance or other content change must be reviewed separately;
this operation does not authorize it as a rebuild difference.

The transaction retains the exact prior and current producer evidence in project
history and rechecks its live bytes before applying. Repeated rebuilds follow
that authenticated chain. Missing or changed retained evidence blocks history
replay and recovery. Recovery keeps the independently rebuilt JAR and maintained
producer export; it restores only the old compatibility proof.

## Transaction and preservation

Preview and confirmation bind the exact checked inputs and inventories. The
editor rechecks under the offline lease before writing and verifies afterward.
The sole target write is the installed compatibility proof. Existing rebuilt
JARs, gameplay sources, custom definitions/assets, map files and editor working
files are not replaced by re-verification. Existing history remains intact;
the new transaction records successor runtime authority for later map imports.

Interrupted recovery restores only the previous evidence file and preserves the
independently rebuilt archives. Runtime drift blocks recovery until the exact
checked rebuilt inputs are restored. After successful re-verification, historical
reversal stops at that boundary; later map-only transactions can be reversed by
the internal recovery/undo machinery. This does not add a public completed-import
Undo action. Older runtime archives cannot be restored across this boundary.

A refusal leaves saved work available. Keep the target offline, retain the full
project and diagnostic source path, and resolve the reported source, dependency,
archive, map/configuration or history discrepancy. There is no force option.

## CLI

Use the candidate's bundled Java and tools JAR. Preview:

```sh
runtime/bin/java -jar builder-runtime/launcher/world-builder-tools.jar \
  reverify-target-runtime --project /path/to/complete-project \
  --target-root /path/to/offline-target
```

Apply with the returned `transactionId` and `planFingerprintSha256`:

```sh
runtime/bin/java -jar builder-runtime/launcher/world-builder-tools.jar \
  reverify-target-runtime --project /path/to/complete-project \
  --target-root /path/to/offline-target --confirm REVERIFY \
  --transaction-id <returned-id> --plan-sha256 <returned-fingerprint>
```

Any change to checked inputs between preview and apply requires a fresh preview.
Map export/import then follows the existing workflow and exact `IMPORT`
confirmation. Do not edit receipts, plans, snapshots or target proof JSON.

## Owner acceptance

Use a disposable copy of the complete attached project and its offline target.
Record hashes/inventories of existing snapshots, exports, receipts, backups,
saved working files, custom definitions/assets and runtime archives. Perform a
normal rebuild with maintained integrated sources, then record the rebuilt JARs.
Preview/apply re-verification and verify that only the compatibility proof changed
on the target. Make three distinct edits, save/export and import each in turn.
Confirm map changes, retained custom content and unchanged rebuilt archive bytes;
verify all pre-existing historical files remain present with identical hashes.
Repeat a rebuild/re-verification after imports to exercise successor history.
Keep any failure diagnostics and do not replace the project with rediscovery.
