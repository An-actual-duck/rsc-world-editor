# World Builder 2 reliability and iteration plan

## Document status

| Field | Value |
| --- | --- |
| Status | Active audit and ordered product worklist |
| Created | 2026-08-30 |
| Product | World Builder 2 |
| Immediate objective | Restore trustworthy server import and shorten development feedback loops |
| Current manager baseline | Editor `7c954ed73b6ec135a77c876c2f770fad3b0a62ca`; runtime provider `69f908a2be1ff52085f4730f47714423c58c1cba` |
| Release state | `v0.7.0-alpha.35` published; development release gate closed |

This document keeps the reliability work visible while an independent stop-gap
repair is being prepared. The stop-gap must be collected and reviewed as an
exact commit before any overlapping implementation begins. It is evidence and
a possible emergency repair, not automatically the long-term design.

## Product decisions

- Remove **Undo Last Server Import** from World Builder 2. This does not remove
  editor-session Undo/Redo, Region Paste Undo, automatic rollback of a failed
  import attempt, or explicit recovery of an interrupted transaction.
- Before server import, prominently instruct the user to make and verify a
  complete external server backup. World Builder must not imply that its own
  transaction artifacts replace a server backup.
- An import may report success only after it has installed and verified every
  compatibility component required to load the imported map. Copying map files
  and changing the selected-map configuration is not sufficient.
- Compatibility work must start from a reproduced failing target and an exact
  bill of materials derived from the pinned runtime. Repeatedly extending the
  current assumptions without reproducing the real failure is not acceptable.
- During implementation, use focused verification for feedback and the full
  risk-appropriate suite at integration boundaries. Do not run the entire suite
  merely to inspect or become familiar with the repository.

## Iteration-time audit

### Observed costs

The repository preflight takes less than a second. The long onboarding delay on
2026-08-30 came primarily from running `./scripts/test.sh`, which was not needed
for a read-only orientation pass. That was a process error, not evidence that
Git status or the manager workflow is intrinsically slow.

The full suite contains 357 Python test methods and runs every test module
serially. In the observed run, the two largest suites took:

| Suite | Tests | Observed time |
| --- | ---: | ---: |
| Adaptive project lifecycle | 69 | 114.278 seconds |
| Adaptive transactions | 41 | 196.682 seconds |

Those two modules alone consumed about 5 minutes 11 seconds. They repeatedly
create full temporary installations, compile Java harnesses, copy package
trees, hash inventories, inject filesystem failures, and launch Java
subprocesses. This work is valuable before integration or release, but is too
expensive as the default response to every small question or edit.

The maintenance surface is also large:

| Surface | Current size |
| --- | ---: |
| Java tooling | 93 files / 54,628 lines |
| Python tests | 27,662 lines |
| Product documentation | 8,590 lines |
| Largest production class | `WorldBuilderAdaptiveProjectLifecycle.java`, 3,335 lines |
| Import plan compiler | `WorldBuilderAdaptiveMutationProfile.java`, 2,169 lines |
| CLI dispatcher | `WorldBuilderCli.java`, 2,021 lines |
| Server-import Undo implementation | `WorldBuilderAdaptiveUndo.java`, 991 lines |
| Largest test module | `test-world-builder-adaptive-project-lifecycle.py`, 8,461 lines |

Large files make even narrow changes slower to understand and review. Import,
Undo, recovery, receipts, historical package addressing, chained imports,
packaging, update inventories, launch scripts, and documentation are tightly
coupled. Since alpha.35, most changes have accumulated around this transaction
cluster, including several successive fixes to historical Undo and chained
import behavior. That coupling increases the chance that a local patch passes
its new fixture while missing the actual end-user configuration.

Documentation is detailed but duplicated. Release status and feature claims
are repeated across the README, architecture, adaptive workflow, product goals,
release instructions, packaged README, and validation records. Some top-level
statements still call `v0.5.0-alpha.11` current or say adaptive publication is
disabled even though `v0.7.0-alpha.35` is published. Reconciling competing
narratives adds avoidable review time and can send implementation toward stale
requirements.

### Import-specific warning found during the audit

At the current baseline,
`WorldBuilderAdaptiveMutationProfile.appendRuntimeCompatibilityActions` handles
only two compatibility files: `server/core.jar` and the selected client
`Open_RSC_Client.jar`. It replaces them only when both target files already
exist. When neither exists, the method returns without scheduling any runtime
installation. When exactly one exists, it refuses the import.

The normal import planner also reads and requires an already compatible target
capability descriptor before it schedules those replacements. Therefore the
current implementation can update the archives of a target that already fits
its compatibility model, but it does not demonstrate that it can bootstrap an
older or otherwise incompatible server into that model. This is a concrete
reason a map-only or partially compatible result can survive tests.

This is an audit finding, not yet a complete root-cause determination. The
actual failing server copy, logs, launch configuration, and stop-gap diff must
be compared with the pinned runtime to identify every missing component.

## Faster working model

Use four verification tiers:

1. **Inspection** — manager preflight, targeted source/document reading, and no
   build or test unless the question needs execution evidence.
2. **Fast feedback** — compile once, run the directly affected test method or
   module, and run `git diff --check`.
3. **Subsystem handoff** — run the relevant discovery, lifecycle, transaction,
   packaging, updater, or runtime-parity group before review.
4. **Integration/release** — run the complete suite, exact runtime parity, real
   archive inspection, and required native checks only at merge, dependency
   adoption, candidate, or release boundaries appropriate to the risk.

The safety contracts should not be weakened to gain speed. The improvement is
to choose the smallest meaningful check during iteration, then retain the full
gate before integration.

## Ordered task list

### 1. Capture the real failure and the stop-gap

- [ ] Collect the stop-gap iteration's exact pushed commit, changed files,
  tests, and known limitations without allowing it to overwrite later work.
- [ ] Reproduce the current failure against a disposable copy of the same
  server layout and version that failed for the owner.
- [ ] Record the exact symptom: server log, client log, selected configuration,
  installed map paths, runtime archives, capability evidence, startup command,
  and first failing load operation.
- [ ] Preserve a minimal sanitized fixture or deterministic fixture generator
  for that starting layout. Never commit a real server, map, credential,
  database, log, or user workspace.
- [ ] Add a failing regression proving that the current import can claim
  success without producing a server that can load the new map.
- [ ] Review which stop-gap changes are safe to retain, supersede, or discard.

Exit condition: one repeatable failure with evidence, not another inferred fix.

### 2. Shorten routine feedback

- [ ] Add a documented focused-test entry point that can select one test file,
  test class, or test method without weakening `./scripts/test.sh`.
- [ ] Make the focused runner build the tools once and allow compatible test
  modules to reuse that build instead of rebuilding independently.
- [ ] Add per-module timing output and maintain a small list of subsystem test
  groups: discovery, projects, transactions, packaging/updater, and workflow.
- [ ] Identify redundant full-install fixture construction in the lifecycle
  and transaction suites and introduce safe shared immutable fixture builders.
- [ ] Split the 8,461-line lifecycle test module and other oversized modules by
  subsystem so focused runs do not execute unrelated behavior.
- [ ] Split production classes only along proven ownership boundaries as they
  are touched; do not delay the import repair for a broad refactor.
- [ ] Make release status canonical in one location and update stale README and
  changelog claims. Historical validation records remain immutable evidence.
- [ ] Add a concise manager status mode that hides already-merged remote topic
  branches unless detailed history is requested.
- [ ] Document the verification-tier rule in the collaboration workflow so
  onboarding, diagnosis, and documentation work do not trigger full release
  verification by habit.

Exit condition: a developer can compile and run the directly relevant import
regression in well under the full-suite time, while the unchanged full gate
remains available.

### 3. Remove Undo Last Server Import

- [ ] Freeze the exact intended boundary: remove only user-initiated reversal
  of a completed server import.
- [ ] Remove the desktop button/menu, launcher-model action, adaptive CLI
  commands, Linux/Windows Undo launchers, package inputs, candidate inventory,
  updater expectations, and end-user instructions.
- [ ] Remove `WorldBuilderAdaptiveUndo` and simplify import code that exists
  solely to reconstruct and reverse a completed historical import.
- [ ] Reassess chained-import receipt/address logic. Retain only evidence needed
  to verify the current installed state, recover an interrupted operation, or
  safely apply a later import.
- [ ] Preserve automatic rollback when an import fails before completion.
- [ ] Preserve explicit Recovery for a transaction interrupted in an uncertain
  state, unless a replacement recovery design proves it unnecessary.
- [ ] Preserve editor-session Undo/Redo and project-local Region Paste Undo.
- [ ] Ensure the updater removes formerly managed Undo launcher files during
  upgrade without touching unknown or durable user files.
- [ ] Replace Undo guidance with a prominent pre-import server-backup warning
  in the desktop flow, CLI preview, packaged README, and launch scripts.
- [ ] Require a fresh confirmation that states World Builder cannot reverse a
  completed import and that the user is responsible for a verified backup.
- [ ] Add tests proving no completed-import Undo surface ships or remains
  callable, while failed-import rollback and interrupted recovery still work.

Exit condition: no end-user server-import Undo exists or is advertised, and
the remaining transaction safety mechanisms have unambiguous purposes.

### 4. Define complete target compatibility

- [ ] Derive a versioned compatibility bill of materials from the exact pinned
  runtime provider rather than from filenames observed in one target.
- [ ] Identify every required server component: runtime archive, loader code,
  capability descriptor, selected-map configuration fields, classpath/startup
  integration, definition/configuration expectations, database/schema needs,
  and any required support files.
- [ ] Identify the corresponding client components: runtime archive, layered
  loader, cache/assets, configuration, launch expectations, and protocol match.
- [ ] Classify each component as replace, merge, generate, migrate, verify-only,
  or unsupported. Define ownership and backup behavior for every mutation.
- [ ] Define supported source layouts explicitly. Unknown or ambiguous layouts
  must fail with a precise report instead of receiving a partial installation.
- [ ] Version the compatibility contract so future runtime changes cannot be
  mistaken for compatibility with older import plans.

Exit condition: one reviewed manifest explains exactly what makes a target
capable of loading an imported World Builder 2 map.

### 5. Implement atomic compatibility installation

- [ ] Make Import preview list the complete compatibility plan separately from
  map-package and activation changes.
- [ ] Support the proven real starting layout, including targets that do not
  already contain World Builder's capability evidence or exact runtime pair.
- [ ] Stage and verify every required compatibility byte before target mutation.
- [ ] Back up every replaced target file for failure rollback, while continuing
  to tell the user that this is not a substitute for a server backup.
- [ ] Apply runtime/support files first, map packages next, and selected-map
  activation last, with a defined durable order.
- [ ] Refuse map activation if any required compatibility component is absent,
  mismatched, unverifiable, or unsupported.
- [ ] Verify the installed server package, client package, active configuration,
  capability/protocol, runtime hashes, and all other manifest components after
  apply.
- [ ] On any pre-completion failure, restore the exact before-state or leave a
  precise recovery record; never report a map-only partial result as success.
- [ ] Keep all testing and validation against disposable target copies. Do not
  mutate a real user server without separate explicit authorization.

Exit condition: import is one atomic map-plus-compatibility operation, not a map
copy followed by advice to repair the server manually.

### 6. Prove end-to-end usability

- [ ] Run the focused regression against the sanitized old/incompatible target
  fixture and confirm it now passes for the right reason.
- [ ] Start the installed server from a cold state and prove it loads the new
  layered map without loader, configuration, definition, schema, or protocol
  errors.
- [ ] Start the matching client, authenticate to the isolated server, enter the
  imported area, and verify terrain and all supported placement families.
- [ ] Test changed-target refusal, missing components, mixed versions, partial
  failure rollback, interrupted recovery, and a second later import.
- [ ] Compare every path outside the reviewed mutation plan before and after.
- [ ] Run runtime-provider parity and the relevant subsystem suites.
- [ ] Run `./scripts/test.sh` and `git diff --check` at final integration.
- [ ] Obtain owner-native validation from a disposable server copy before any
  release gate is opened.

Exit condition: automated and native evidence demonstrate that a representative
previously incompatible server becomes runnable with the imported map.

### 7. Integrate and release deliberately

- [ ] Review the complete Editor diff and any independent runtime-provider diff
  at exact pushed tips.
- [ ] Publish and pin runtime work first if the compatibility contract requires
  runtime-provider changes.
- [ ] Merge the tested Editor work only after the stop-gap has been reconciled
  and no unique work is stranded.
- [ ] Update user documentation to explain supported targets, backup
  responsibility, previewed compatibility changes, and recovery limitations.
- [ ] Build a fresh restricted candidate and complete the normal owner
  acceptance, release gate, archive inspection, production rebuild, upload,
  and post-publication verification process.

Exit condition: the tested behavior, documentation, pinned runtime, release
artifacts, and published claims all describe the same compatibility contract.

## Definition of done for server import

Server import is not fixed until all of the following are true:

- a clean supported target from the reproduced failing family can be made
  compatible without a manual stop-gap;
- preview names every target path and compatibility role that will change;
- success is impossible when only the map package was installed;
- the target can cold-start and load the imported map with the matching client;
- unsupported targets fail before mutation with actionable evidence;
- failed or interrupted operations preserve exact rollback/recovery safety;
- completed-import Undo is absent and the backup warning is explicit; and
- focused, full-suite, parity, disposable-target, and owner-native validation
  all pass at their appropriate gates.
