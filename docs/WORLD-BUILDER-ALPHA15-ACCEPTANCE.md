# Alpha 15 restricted acceptance

Accepted October 9, 2026 for owner inspection, not production release.

- Packaged Editor: `2ca414daa1b3864a3eda3c48e861ab1e9445746d`.
- Exact tested implementation: `dbf24826462932765b5635af69a8712a266242ed`.
- Unchanged runtime provider: `67361019f28ba52f6acf08a19f6499302a79d8c4`.
- Candidate: `/home/justin/world-builder-test-builds/targeted-runtime-v0.8.1-alpha.15`.

## General correction

Maintained archives without optional Editor floor manifest hints exposed a
historical reconstruction error: retained floor verification reapplied the old
integration proof's raw archive hash after independent runtime verification had
already proved an equivalent rebuild. Interrupted recovery exposed the same
mistake after restoring the old proof.

Re-verification now passes the verifier's exact archive hashes into predecessor
reconstruction. Retained floor verification rechecks the complete server/client
pair and requires exact floor definitions and metadata. Recovery first verifies
the complete retained transaction history, proof, inventories and inputs, then
reuses only its replay-authenticated pair. It adds no extra destinations or
source/configuration/floor exemptions. Ordinary current-proof checks, offline
leases, preview binding, failure rollback and historical undo boundaries remain.
No target-specific NPC, server-name exception, schema or provider change was added.

## Verification

All eight affected suites passed on the clean pushed implementation tip: 283
tests in 1,188 seconds with trusted Java 17. Counts: contracts 23, discovery 42,
projects 75, runtime verifier 7, content authority 8, refresh 18, maintained
producer 17, transactions 93. Coverage includes timestamp/debug rebuilds,
source/binary/dependency/map/floor drift, preview changes, repeated imports,
interruption recovery and undo boundaries.

The expanded recovery regression initially refused safely and restored its
starting state. That failed attempt and the successful focused/final reruns are
retained separately. Final log:
`/home/justin/world-builder-test-builds/content-lifecycle-alpha15-worker/safety-dbf2482.log`,
SHA-256 `1ef4f5d4074a7b30138479c9b3b0698e3f9b2882402c048feb82a7a2e016b99e`.

Manager review confirmed the integrated program/test tree equals the tested
worker tree. Main was published and provider parity passed before fresh builds.
This bounded correction received affected safety verification; the preceding
full integration baseline remains `0453e296e9e79db46fd6829d9d38e80ecb347a68`
(750 tests, 35 explicit skips, 64 selections, 1,409 seconds). It is historical
evidence, not a new production gate.

Both fresh Linux/Windows archives passed independent identity, manifest, input
inventory, content-neutrality and compiler-runtime inspection. Populated
alpha 14 installations updated through the candidate's manifest-verified
production updater: all 12,816 project files and registry state remained exact,
and all 63,509 target files outside each installation remained unchanged.

## Actual maintained-target lifecycle

Persistent fixtures rebuilt the chain after `/tmp` was cleared by shutdown:
fresh attached alpha 14 project → two changed imports/reopens → third saved,
exported, unimported edit → Core-owned normal Java 17 build → staged maintained
export/readiness → packaged alpha 15 re-verification → reviewed content successor
→ retained-edit import → another changed import → reopen after each.

Core handed back quiescent disposable results and verified all 5,838 archive
members and RGB bytes unchanged despite the new container hash. Original
integration proof and project/map evidence remained untouched during its build.
Editor independently compared archive entries and saved inventories.

The actual alpha 15 chain passed. Re-verification changed only its installed
compatibility proof. Both map imports succeeded, including existing Naga
placements supported by matching target catalogs. All 7,160 effective target
writes matched the exact reviewed plans. The complete predecessor's 12,822 files
remained exact after successor creation/imports; 5,495 original evidence files,
5,387 earlier historical files, saved work and 91 runtime/producer files were
retained. No map import replaced gameplay code, definitions or assets.

The actual packaged desktop used the authentic recorded target location after
Core's explicitly coordinated in-place build on an Editor-created disposable
fixture. File → Re-verify Rebuilt Target Runtime showed the verified rebuilt
client and one proposed proof write. Preview left all target/map/source/history
files unchanged. Clicking Re-verify succeeded; the receipt matched displayed
transaction `d2a07ef6-619b-4a58-a50b-ec0c6cd1be4e` and the one exact proof change.

The private editor opened the preserved scene, showed full-body content,
offered 877 discovered NPCs, and found Naga 866 by name. Ordinary mouse/keyboard
input and clean window close succeeded. All 1,789 saved-map files, original
evidence and earlier history remained byte-identical afterward. The earlier
alpha 14 actual desktop detect/create check also verified target nonmutation;
current changes do not alter that UI/capture implementation.

Persistent manager evidence:
`/home/justin/world-builder-test-builds/content-lifecycle-alpha15`.
CLI result:
`/home/justin/world-builder-test-builds/content-lifecycle-alpha14/fixture-resumed/editor-alpha15-resume/acceptance-results.json`.
The candidate's `ACCEPTANCE-RESULTS.json` records exact paths/hashes.
Original Core evidence, normal Core, preserved baseline and live deployment
remain untouched. Only explicitly released Editor-created disposable fixtures
were mutated; Core owns actual private-gameplay acceptance.

## Retest and limits

Use the candidate's `Apply Candidate Update.sh` with the existing installation
directory as its argument. Continue the existing project. Follow `CORE-RETEST.md`:
offline supported normal rebuild → maintained staged export/readiness → File
re-verification preview/confirmation → Detect New Content → two changed
save/export/import cycles and reopen → independent preservation checks.

Actual maintained Java 17 equivalence is verified. Archive timestamps/order and
explicitly tested compiler debug/Ant metadata differences are supported;
arbitrary compiler, source, gameplay or dependency differences are not accepted
by refreshing hashes. Java 21 output differing from the retained Java 17
baseline remains unsupported. Missing original authority remains a refusal;
saved work/history is retained rather than reset or repaired.

Linux packaged execution passed. Native Windows execution remains untested;
Windows archive/toolchain inspection passed. Fourteen existing procedural
scenery warnings and intentional secondary-attack preview limits remain
explicit. Very long disposable receipt paths can extend the existing completion
dialog beyond the screen; Enter dismisses it. This presentation limitation did
not affect confirmation binding, transaction completion or preservation.

Linux archive SHA-256:
`99e480fb1d3faecb9d675c03d2af7be036297566de1df7d34e5ec7084d7bbb6b`.
Windows archive SHA-256:
`ec0e3b5cf1161b4c0b0aadc7bf8c5929824dbf7dc1ef784ba733323fed61c763`.
No release tag, upload, production gate or deployment was performed.
