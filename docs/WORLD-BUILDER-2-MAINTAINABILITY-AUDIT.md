# World Builder 2 maintainability and AI iteration audit

## Status and scope

| Field | Value |
| --- | --- |
| Status | Initial audit complete; first optimization batch and reviewed output cleanup complete |
| Audited | 2026-08-30 |
| Editor baseline | `b48e74270a88bff47c442e9a628f6d02db304a6d` |
| Purpose | Reduce routine AI/developer latency without weakening integration or release safety |

This audit covers the Editor repository, its registered manager workflow, and
its ignored Editor-owned output. It does not inspect or manage another product.
No generated archive, workspace, release record, schema, or compatibility
fixture was deleted during the audit.

Measurements and problem descriptions below refer to the audited baseline.
Completed checklist items record later improvements; they do not rewrite the
baseline evidence that motivated them.

## Executive findings

The repository has three different kinds of bloat, and they should not be
treated as one cleanup problem:

1. **Generated storage bloat is severe.** The ignored `output/` tree is about
   31 GB even though the tracked working tree is only about 4.9 MB. Most of the
   output consists of 73 candidate versions, 27 extracted test-build roots, 15
   local release versions, and 21 retired development sandboxes.
2. **Test and source concentration slows navigation and feedback.** Two test
   modules contain 110 tests and 11,786 lines between them, take over five
   minutes together, and also serve as fixture/harness libraries. Several Java
   production classes exceed 2,000 lines, with many responsibilities in one
   package.
3. **Workflow output and documentation consume unnecessary AI context.** Full
   tests always use verbose output, manager preflight lists 68 already-merged
   remote branches, and current product claims are duplicated across long
   documents with stale release references.

Historical release records, tags, versioned schemas, frozen v1 assets, and
compatibility fixtures are not meaningful storage problems. Deleting them
would save little and would remove provenance or backward-compatibility
evidence. They should be indexed and routed more clearly rather than removed.

## Measured repository shape

### Tracked content

| Area | Files | Approximate bytes |
| --- | ---: | ---: |
| `tools/` | 133 | 2.54 MB |
| `tests/` | 62 | 1.46 MB |
| `docs/` | 30 | 0.48 MB |
| `scripts/` | 18 | 0.23 MB |
| `release/` | 33 | 0.16 MB |
| Entire loose Git object database | — | 31.87 MB |

Git history size is not currently a material performance problem. Garbage
collection or history rewriting would provide negligible benefit compared with
the risks and should not be part of this cleanup.

### Ignored/generated content

| Area | Size | Inventory |
| --- | ---: | --- |
| `output/candidates/` | 17 GB | 73 World Builder 2 candidate versions |
| `output/test-builds/` | 5.6 GB | 27 extracted test-build roots |
| `output/development/` | 5.3 GB | Active validation state, JRE inputs, and retired sandboxes |
| `output/releases/` | 3.5 GB | 15 local v1/v2 release versions |
| `.runtime-provider/` | 387 MB | Disposable exact-lock runtime checkout |
| `output/world-builder-tools/` | About 1.2 MB | Rebuildable Java classes and JAR |

The `output/` tree has no repository retention policy. Candidate packaging
replaces the output for the same version but does not expire older version
directories. The reusable tool environment moves every reset into `retired/`
and never prunes it. This explains the accumulation without implying that any
specific directory is safe to delete blindly.

Potentially reclaimable generated candidate, test-build, and local-release
copies account for roughly 26 GB. Retired development sandboxes account for a
further 3.3 GB, but they may contain deliberate edits and must be treated as
durable user-like state until explicitly reviewed.

## Test-system audit

### Current execution model

`scripts/test.sh` performs these operations serially:

1. rebuild the tool JAR;
2. syntax-check every shell script; and
3. launch every `test-world-builder-*.py` file in a separate Python process
   with verbose output.

It has no supported file, class, method, or subsystem selection; no concise
success mode; no per-module timing summary; and no safe parallel scheduling.
Developers can invoke a Python file manually, but the project does not expose
that as a documented, consistent feedback path.

At least 18 test modules contain embedded Java/compiler patterns. Sixteen test
classes compile Java in `setUpClass`, and five modules invoke
`scripts/build-tools.sh` again. `build-tools.sh` deletes and rebuilds its output,
so independent modules cannot safely share or parallelize that global output
without first changing the build contract.

### Concentrated files

| File | Lines | Tests | Temp-directory sites | Subprocess call sites |
| --- | ---: | ---: | ---: | ---: |
| `test-world-builder-adaptive-project-lifecycle.py` | 8,461 | 69 | 73 | 26 |
| `test-world-builder-adaptive-transactions.py` | 3,325 | 41 | 67 | 9 |
| `test-world-builder-adaptive-contracts.py` | 2,241 | 22 | 5 | 2 |
| `test-world-builder-adaptive-discovery.py` | 2,036 | 40 | 43 | 4 |
| `test-world-builder-v2-release.py` | 1,484 | 16 | 30 | 12 |

The observed lifecycle suite took 114.278 seconds and the transaction suite
took 196.682 seconds. The transaction module dynamically imports the lifecycle
test module to reuse its fixture generators, then separately compiles all
production Java sources and additional historical/harness variants. Test cases,
fixtures, compiler setup, subprocess adapters, and large embedded Java programs
are mixed in the same files.

### Why this affects AI work

- A narrow import change appears to require reading both giant modules because
  their support code is not separated from test cases.
- Embedded Java harnesses are difficult to navigate, diff, compile selectively,
  or map back to the production behavior they inject.
- Verbose successful output can consume thousands of tokens without adding
  decision-relevant evidence.
- The absence of named subsystem groups encourages either an ad hoc command or
  the full six-minute suite.
- Repeated full fixture construction is correct for isolation but masks which
  setup steps actually dominate runtime.

### Recommended test architecture

1. Add `scripts/test-focused.sh` or equivalent selection support to
   `scripts/test.sh` for named groups, files, classes, and methods.
2. Add a concise default suitable for AI: one line per module with status,
   tests, skips, and duration; preserve complete output in a temporary log and
   print the relevant tail on failure. Keep `--verbose` for diagnosis.
3. Extract shared Python fixture builders into a clearly named support module.
   Transaction tests must not import another test module as a library.
4. Move embedded Java harnesses into normal files under
   `tests/java/com/openrsc/worldbuilder/`, named by the failure boundary they
   exercise.
5. Compile production test classes once per source/allowlist hash. Compile only
   small harness deltas for a test group. Never reuse stale classes without
   verifying that hash.
6. Split lifecycle tests into project creation/reopen, runtime preparation,
   region operations, content providers, migration, and durability/recovery.
7. Split transaction tests into export/import, failed-import rollback,
   recovery, offline/process safety, and historical compatibility. Removal of
   completed-import Undo should delete its dedicated test surface rather than
   preserve it under another name.
8. Add timing data before optimizing fixture copies. Use copy-on-write/reflink
   clones only as a verified optimization; do not introduce hard links between
   mutable test installations.
9. After shared global outputs are removed, run independently isolated test
   groups in bounded parallel workers. Keep release packaging and any group
   with shared state serialized.

Recommended targets are a focused import regression under 30 seconds, a named
transaction group under 90 seconds, and a full local suite under 3 minutes.
These are performance targets, not reasons to remove meaningful safety cases.

## Production-code navigation audit

All 93 Java sources currently use the same
`com.openrsc.worldbuilder` package. The largest files are:

| File | Lines |
| --- | ---: |
| `WorldBuilderAdaptiveProjectLifecycle.java` | 3,335 |
| `WorldBuilderRegionSnapshotService.java` | 3,272 |
| `WorldBuilderAdaptiveMutationProfile.java` | 2,169 |
| `WorldBuilderAdaptiveContracts.java` | 2,095 |
| `WorldBuilderCli.java` | 2,021 |
| `WorldBuilderProjectContentBundle.java` | 1,978 |
| `WorldBuilderDesktopLauncher.java` | 1,738 |
| `WorldBuilderLayeredTerrainDraftJournal.java` | 1,702 |

The names are descriptive, but there is no code map showing entry points,
ownership, durable data, target-mutation boundaries, or the tests that cover a
class. An AI must infer this repeatedly from search results and long documents.

Recommended changes:

- Add a short generated-or-checked `tools/world-builder/CODE-MAP.md` organized
  by discovery, projects, runtime preparation, export, import/recovery,
  regions, content providers, contracts, desktop, and CLI.
- Add `tests/README.md` mapping each subsystem to its focused group, fixture
  support, production owners, expected duration, and native requirements.
- Prefer extracting cohesive collaborators from files as those areas are
  changed. Do not begin with a repository-wide package migration.
- For import work, separate compatibility manifest compilation, map-package
  planning, target preflight, transaction execution, and post-install
  verification. The current mutation-profile class owns too many of these.
- Replace the long CLI command `if` chain with a small command registry only
  after tests can target commands cheaply. This improves indexing but is lower
  priority than the import repair.
- Split desktop actions from Swing layout so removing or testing one action
  does not require navigating the entire launcher.

A line-count ceiling should be a review signal, not a mechanical rule. New
responsibilities should not be added to a file already above roughly 1,500
lines without either extracting a collaborator or recording why locality is
safer.

## Documentation and indexing audit

The main product documents total 8,885 lines. The largest are the adaptive map
workflow (1,506), product goals (962), format-aware discovery (851), and custom
materials design (821). Several mix historical design, implementation status,
future work, and current user contract.

Stale examples include:

- the top-level README calling `v0.5.0-alpha.11` current;
- the changelog saying adaptive publication remains disabled;
- development/release instructions centered on the old alpha.2 worksheet;
- architecture and workflow status still describing alpha.2 as the relevant
  completed release; and
- product-goal focus/status statements that lag implemented later work.

Recommendations:

1. Create `docs/INDEX.md` with exactly one row per document: owner, status,
   authority, current/historical designation, and when an AI should read it.
2. Establish one small current-state file containing current release, runtime
   lock, release-gate state, active objective, and supported product generation.
   Other documents should link to it instead of copying those values.
3. Mark design documents as current, partially superseded, or historical.
   Preserve their content but route normal work away from obsolete sections.
4. Keep immutable validation records under `docs/releases/`. Their total size
   is only about 104 KB and each corresponds to a release tag. Moving or
   deleting them would break evidence and links for no useful performance gain.
5. Keep `AGENTS.md` focused on mandatory safety and routing rules; move
   explanatory duplication into the indexed workflow document only when the
   same rule remains unambiguous at entry.
6. Update status claims as one bounded documentation task rather than editing
   them incidentally during behavioral fixes.

## Old-version and artifact classification

### Retain in Git

- Published tags and `docs/releases/*-validation.md`: release provenance.
- Versioned schemas and their v1/v2 fixtures: existing user projects, exports,
  receipts, and provider bundles may still need validation or migration.
- Frozen `release/world-builder/` and `release/updater/` v1 assets: required by
  the product-generation boundary and reproducibility rules.
- Current release/updater negative fixtures: they prove v1/v2 isolation,
  traversal refusal, rollback, and content-neutral packages.

These items should be indexed or isolated into clearly named compatibility
groups, not removed merely because their version number is old.

### Rebuildable and normally disposable after review

- `output/world-builder-tools/`;
- rejected or superseded `output/candidates/world-builder-v2/*` directories;
- extracted `output/test-builds/*` with no project/workspace state;
- local copies under `output/releases/` after their public artifacts and
  checksums are verified; and
- temporary marker directories that are not named by an active process,
  recovery record, release gate, or validation task.

### Require explicit inspection before removal

- any accepted candidate involved in an open release gate;
- reviewed JRE inputs not stored elsewhere;
- the active reusable tool-test sandbox;
- every `retired/` sandbox, because it may contain deliberate edits;
- extracted native owner-validation builds containing `projects/`,
  `workspace/`, exports, backups, receipts, or logs; and
- any directory referenced by an unfinished stop-gap or validation report.

The cleanup tool must discover these conditions and refuse by default. It must
never implement cleanup as an unrestricted recursive deletion of `output/`.

## Workflow and branch-noise audit

The manager preflight itself completed in about 0.4 seconds, so its Git work is
not slow. Its default output listed 68 merged remote topic branches, however,
which is unnecessary context for most tasks.

Recommendations:

- Make `ai-manager.sh status` concise by default: current manager, active or
  dirty workers, unmerged work, stash count, and a count of merged remote
  branches.
- Add `status --verbose` for the complete merged-branch inventory.
- After a separate explicit remote-cleanup review, delete merged remote topic
  refs that have durable merge ancestry on published `main`. Retain tags and
  any branch referenced by an active handoff or release procedure.
- Do not activate workers for orientation, tiny documentation changes, or
  read-only audits. Use them for independently reviewable feature work.

## Safe generated-output retention design

Add a manager-owned command with preview as its default behavior. It should:

1. inventory size, age, type, version, and durable-state markers;
2. refuse to operate outside this repository's `output/` directory;
3. refuse while a release gate, candidate operation, updater, Builder process,
   or relevant worker is active;
4. preserve current tool output, the active development sandbox, reviewed JRE
   inputs, and an explicitly selected number of recent builds;
5. never remove a directory containing projects, workspace, exports, backups,
   receipts, recovery state, credentials, databases, or unknown user files;
6. offer archive/move instructions for intentionally retained owner-validation
   state;
7. require an exact second command or confirmation to remove the previewed
   immutable list; and
8. report bytes reclaimed and paths retained.

Suggested default retention after those guards are implemented:

- keep the newest two rejected/development candidates;
- keep every candidate named by an open gate;
- keep the newest one disposable extracted test build;
- keep the latest published local release only when needed for update tests;
- keep zero old tool JARs; and
- never age-delete development sandboxes.

## Prioritized implementation recommendation

### Priority 0 — immediate feedback and storage controls

- [x] Add concise manager status and detailed `--verbose` output.
- [x] Add focused test selection, named groups, timings, and concise success
  output.
- [x] Add a read-only generated-output inventory/cleanup preview command.
- [x] Review the 31 GB output inventory with the owner, then perform one exact
  recoverable/archive-aware cleanup under explicit approval. The reviewed 109
  disposable directories (about 24 GB) were moved to system Trash; protected
  development, project-bearing, current-tool, and frozen-v1 outputs remained.
- [x] Add `docs/INDEX.md`, `tools/world-builder/CODE-MAP.md`, and
  `tests/README.md`.

### Priority 1 — extract test infrastructure

- [x] Move shared fixture builders into
  `tests/myworld/adaptive_project_test_support.py`; transaction tests no longer
  dynamically import the 8,000-line lifecycle suite.
- [ ] Move embedded Java harnesses into `tests/java/`.
- [ ] Share source-hash-bound compiled production classes.
- [ ] Split lifecycle and transaction modules along subsystem boundaries.
- [ ] Enable bounded parallel execution for proven-isolated groups.

### Priority 2 — simplify the active problem area

- [ ] Remove completed server-import Undo and its packaging, UI, CLI,
  historical reconstruction, and tests as specified by the reliability plan.
- [ ] Split compatibility planning from map planning and transaction execution.
- [ ] Add a versioned compatibility manifest and real failing-target regression.

### Priority 3 — reduce long-term drift

- [ ] Reconcile stale current-state and release documentation.
- [ ] Add lightweight size/time budgets as warnings in review tooling.
- [ ] Review and prune merged remote topic branches.
- [ ] Refactor other oversized production classes only when their subsystem is
  active, backed by focused tests.

## Success measures

The optimization effort is successful when:

- routine preflight output is under roughly 30 lines unless verbose detail is
  requested;
- an AI can identify the production owner, tests, and authoritative document
  for a subsystem from one index;
- a focused import test completes in under 30 seconds;
- successful test output is concise while failure logs remain complete;
- the full suite completes in under 3 minutes without deleting meaningful
  cases;
- generated output remains under a documented retention budget;
- no test module doubles as another module's hidden fixture library;
- no cleanup removes user-like state or release evidence; and
- behavioral fixes are not declared complete without the appropriate real
  disposable-target evidence.
