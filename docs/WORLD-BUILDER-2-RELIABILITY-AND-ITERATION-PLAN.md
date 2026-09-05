# World Builder 2 reliability and iteration plan

## Document status

| Field | Value |
| --- | --- |
| Status | Active audit and ordered product worklist |
| Created | 2026-08-30 |
| Last reconciled | 2026-09-04, after rejecting pinned-core and defining adaptable Base/Advanced upgrades |
| Product | World Builder 2 |
| Immediate objective | Replace the failed target-runtime path with trustworthy adaptable upgrades and map-only Import while shortening development feedback loops |
| Historical feature integration base | Editor `147fdc5b34e2f23f441ce4ccdf60cf908ce85aad`; adopted runtime provider `d2903f21530959a3bd9072846c8611fdf035f792` |
| Release state | `v0.7.0-alpha.88` published; later pinned-core candidate rejected; development release gate closed |

This document keeps the reliability work visible after the independent
stop-gap was applied manually to the separate Core checkout. It restored that
server but is evidence, not the long-term import design. The disposable
pre-fix Core copy is the reproducible target for this work.

### Current architecture decision — 2026-09-04

The package-driven pinned-prebuilt-core candidate is rejected. It preserves
divergent target source while preventing that source from rebuilding, cannot
compile the customized target's plugins against the installed generic core,
and does not provide an existing project's N-to-N+1 runtime migration. Checked
items and owner checkpoints below remain historical records of the strategies
they actually exercised; they do not validate the later pinned-core strategy.

The active replacement direction, evidence review, Preservation fixture role,
upgrade-first product model, staged plan, and strategy-bound release gate are
maintained in [World Builder 2 Current Runtime Upgrade Review](WORLD-BUILDER-2-CURRENT-RUNTIME-UPGRADE-REVIEW.md).

That current direction defines one managed platform generation rather than one
mandatory gameplay composition. Preservation-like/lightly customized public
targets upgrade to Current Base; the owner's advanced lineage upgrades to
Current Advanced. Historical layouts are handled by migration adapters and
portable current behavior by explicit modules. Neither creates another active
legacy runtime.

## Product decisions

- Remove **Undo Last Server Import** from World Builder 2. This does not remove
  editor-session Undo/Redo, Region Paste Undo, automatic rollback of a failed
  import attempt, or explicit recovery of an interrupted transaction.
- Before server import, prominently instruct the user to make and verify a
  complete external server backup. World Builder must not imply that its own
  transaction artifacts replace a server backup.
- Map Import may report success only after the target ledger already proves the
  required current composition and the map transaction has installed and
  verified every selected server/client map and activation component. Copying
  some map files or changing one configuration value is not sufficient.
- Runtime-upgrade work must start from a reproduced failing target and an exact
  bill of materials derived from the selected provider composition. Repeatedly
  extending current assumptions without reproducing the real failure is not
  acceptable.
- During implementation, use focused verification for feedback and the full
  risk-appropriate suite at integration boundaries. Do not run the entire suite
  merely to inspect or become familiar with the repository.

## Iteration-time audit

The detailed measurements, retention classification, test-architecture review,
and prioritized optimization recommendations are maintained in [World Builder
2 Maintainability and AI Iteration Audit](WORLD-BUILDER-2-MAINTAINABILITY-AUDIT.md).

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

Documentation is detailed but duplicated. At the 2026-08-30 audit snapshot,
release status and feature claims were repeated across the README, architecture,
adaptive workflow, product goals, release instructions, packaged README, and
validation records. Some top-level statements then called
`v0.5.0-alpha.11` current or said adaptive publication was disabled even though
`v0.7.0-alpha.35` was published. Later reconciliation now identifies Alpha.88
as the latest published release, but duplicated status narratives remain a
maintenance risk.

### Historical import-specific warning found during the audit

At the audited pre-replacement baseline,
`WorldBuilderAdaptiveMutationProfile.appendRuntimeCompatibilityActions` handles
only two compatibility files: `server/core.jar` and the selected client
`Open_RSC_Client.jar`. It replaces them only when both target files already
exist. When neither exists, the method returns without scheduling any runtime
installation. When exactly one exists, it refuses the import.

The normal import planner also reads and requires an already compatible target
capability descriptor before it schedules those replacements. Therefore the
then-current implementation could update the archives of a target that already
fit its compatibility model, but it did not demonstrate that it could bootstrap
an older or otherwise incompatible server into that model. This is a concrete
reason a map-only or partially compatible result could survive tests.

This is an audit finding, not yet a complete root-cause determination. The
actual failing server copy, logs, launch configuration, and stop-gap diff must
be compared with the pinned runtime to identify every missing component.

### Reproduced import failure and current compatibility gap

This section is a chronological incident record of earlier implementation
strategies. Its present-tense observations describe the code or candidate under
test at that point; the current Base/Advanced replacement decision above
supersedes any prescriptive Import/runtime language in the record.

The failure is now reproduced against the explicitly authorized disposable
`Core-Framework (copy)` target. The historical successful import receipt
contained 3,578 map/configuration actions and no runtime compatibility action.
Before the current branch changed planning order, a fresh import instead
refused because the target advertised encoding version 1 while the export
required version 3.

The active branch can now plan and atomically install the server JAR, client
JAR, adaptive runtime capability, map packages, and selected map configuration.
A fresh project built from runtime provider
`3ebe9ce753b5f42765c05ec1d2406202913d0cc4` applied 3,556 verified actions to
the disposable target. The three compatibility artifacts matched the pinned
runtime byte-for-byte, and the target was subsequently restored byte-for-byte
from verified transaction and external backup evidence.

That launch test found the next, more fundamental boundary. The target's normal
`myworld.conf` remained active, so the new runtime logged that all layered
authority/package settings were absent, loaded the legacy
`Custom_Landscape.orsc` and legacy placements, and failed during legacy world
population. The installed `server/world-builder-configs/primary.json` is an
Editor-side selection descriptor; the server runtime does not read it as its
launch configuration. Moreover, the current adaptive runtime profile is
builder-only and requires isolated project evidence, while the other
replacement profiles accept only hard-coded product packages. A generic,
strict installed-package runtime profile plus a bounded target launch-config
activation contract is therefore required. Replacing JARs alone cannot make a
normal target use an arbitrary imported World Builder package.

The next real-target test installed that profile and patched `server/myworld.conf`.
The server then correctly logged that it skipped both legacy terrain and legacy
base placements, proving that launch activation selected the imported package.
Startup nevertheless failed closed on NPC definition 854. The replacement
runtime JAR came from the independent generic runtime provider and therefore
discarded target-specific Core behavior which conditionally loads that custom
definition. The working and pre-fix Core source trees differ in only nine
server runtime files and one client runtime file for the stop-gap, while the
complete Core and generic-runtime trees differ much more broadly. Consequently,
blindly replacing a customized target's complete JARs is not a valid general
compatibility strategy. The evidence established that runtime migration must
preserve deliberately selected target behavior through a current port or refuse
before mutation.

A target-preserving prototype then crossed the cold-start boundary. The ten
stop-gap source changes were applied to a temporary copy of the pre-fix Core
source, augmented with the generic `world-builder-installed` profile, and built
against the target's own libraries without modifying its source tree. Both the
server and client staged builds succeeded. With the staged target-preserving
JAR, the server retained all 862 target NPC definitions, activated the imported
package, populated 3,749 NPC, 1,013 ground-item, 27,753 scenery, and 965 boundary
placements, and reached its online TCP/WS state. The disposable target was then
stopped and restored through reviewed transaction Undo.

That prototype also proved that the packed Core activation contract uses the
canonical package fingerprint as its content-address, while the current Editor
planner installs under a distinct native-inventory digest. The two hashes are
both deterministic but are not interchangeable. A supported-Core compatibility
contract must select and verify the canonical fingerprint address expected by
`WorldBuilderInstalledMapActivation`; generic package profiles may retain a
different address only when their runtime explicitly defines it.

A disposable live-test import has since completed through the reviewed Editor
transaction path using the target-preserving staged artifacts and canonical
package address. Its imported server cold-started successfully with the full
placement counts above. The owner then connected the matching private client,
authenticated, and confirmed the imported world was usable. Detection, project
load, one saved map edit, import, server launch, and client launch have therefore
all crossed the native live-test boundary. This also exposed an important
packaging boundary:
the target-preserving installed runtime and the generic adaptive authoring
runtime are different products of the compatibility process. The former must
not replace the project-local Editor runtime, because this target family does
not implement the Editor-only `adaptive-world-builder` profile.

### `Custom_Landscape.orsc` authority audit

The imported target still contains byte-identical server and client
`Custom_Landscape.orsc` archives with SHA-256
`c48f9734f8faf027b9128c28dfcece468d3e84a5c1ed4b9a4452c2481392b6ee`
and 1,771 packed sectors. Their presence does not make them the active server
map. The installed server selects the content-addressed layered package and
logs both `Skipping legacy terrain archives for installed World Builder package
profile` and the corresponding suppression of legacy base placements.

The ordinary player client has a different startup boundary. Before login and
before the server can send native layered terrain, it still opens either
`Custom_Landscape.orsc` or `Authentic_Landscape.orsc` and uses that file's MD5
as its reported map identity. Only the isolated adaptive World Builder client
profile can currently start with no packed landscape archive. Consequently,
deleting both legacy files during import would break ordinary client startup
even though the installed server no longer reads them. The existing migration
retirement action is unsafe for this installed-runtime path until the player
client receives an installed-package bootstrap profile.

The current desktop question is also too weak. It is displayed whenever a
layered target happens to contain a legacy file; it does not determine whether
the archive is absent from, identical to, already incorporated into, or
superseded by the layered authority. Selecting **Yes** on the disposable Core
copy fails with `DEFINITION_MISMATCH` before terrain composition because the
secondary packed conversion derives a different definition catalog. Forcing
composition would be incorrect: exact conversion comparison shows that all
1,771 legacy sectors already exist in the selected layered package, 1,758 are
byte-equivalent, and 13 differ. The layered package additionally carries the
reviewed level `-2` and level `+10` relocation sectors. Applying the legacy
archive as an overlay would restore stale source-level terrain over those
reviewed relocations. Selecting **No** works because it preserves the already
complete layered authority.

The replacement must be a one-time classification and supersession workflow,
not another unconditional overlay prompt:

1. Compare the exact legacy archive hash and converted sector inventory with
   the selected layered package during read-only detection.
2. If exact provenance or subset parity proves prior incorporation, silently
   keep the layered package and record the legacy source as already handled.
3. If sectors conflict, show their counts and require one explicit decision:
   keep the current layered authority, apply the legacy sectors, or cancel.
   Keeping the validated layered authority is the correct decision for the
   disposable Core copy.
4. Bind that decision, both legacy file hashes, and the selected layered
   fingerprint into immutable project evidence and the later installed
   receipt. Repeated detection must recognize that evidence and never ask the
   same question again unless either side changes.

The implemented import compatibility path now treats the exact installed v1
capability as a preservation contract. It keeps that target-specific
server/client runtime pair, activates each new content-addressed map package,
and patches the bounded server launch configuration without installing the
generic v2 runtime over it. Because the v1 ordinary client still consumes the
packed archive during startup, both exact `Custom_Landscape.orsc` copies remain
in place and the requested retirement is deferred. A later edit/save/import
uses the same project decision and repeats the map update without replacing
the preserved runtime. Generic targets that receive the proven v2
archive-free client bootstrap retain the guarded retirement path.

The blocking blended base-color contract advances the installed loader from v6
to v7. Import now installs the content-neutral managed server runtime and a
bounded client source upgrade instead of replacing the complete customized
client archive with the generic provider build. The upgrade adds the verified
archive-free bootstrap and transforms the existing native terrain selection
points while preserving the target's protocol version, definitions, advertised
limits, custom behavior, and assets. The target's normal client build compiles
that combined result before launch; repeated edited-map imports reuse it.

The client upgrade is now a versioned one-way source migration rather than a
fragile search for one recent hook shape. Import installs six missing runtime
components, replaces `Tile.java`, `WorldBuilderClientProfile.java`, and
`World.java` only when their hashes identify the supported historical revision,
and inserts one bounded native-login guard into the customized
`mudclient.java`. Unknown collisions fail before mutation. Every addition,
replacement, and semantic edit is included in the reviewed transaction,
backed up, verified, recoverable, and stable on repeat import. The obsolete v1
source-upgrade manifest is no longer shipped.

The first live test of that migration proved the server upgrade and exposed a
missing client dependency contract: three installed sources use `org.json`,
while the historical target client compiles and packages only JARs from
`PC_Client/lib`. The current source-upgrade manifest therefore installs the
exact pinned `json-20190722.jar` into that client-owned directory as part of the
same transaction. Import also rewrites the recognized client compile target to
build `Open_RSC_Client.jar.world-builder-new` and move it over the active JAR
only after compilation and packaging succeed. A failed compile consequently
retains the last verified client executable. A previously upgraded target whose
old build already removed the client JAR is an explicit repairable state after
fresh detection; no manual JAR restoration is required.
5. Do not retire the physical archives until the installed player client can
   bootstrap from a verified installed-package identity without opening a
   packed archive. Once that runtime support exists, import may back up, remove,
   and verify both exact legacy files as the final step.

That replacement is now implemented on the active integration branch. Runtime
provider `eac0e33bd5f09b6288be65a7665b6b282331560b` adds a strict installed-client
profile whose verified package manifest becomes the pre-login map identity and
whose native-only startup path never probes a legacy archive. Detection now
compares converted legacy sectors to the selected layered package and records
either incorporation or `keep-selected-layered-landscape` against both
discovery fingerprints and both exact archive hashes. Import installs the
matching client profile before transactionally retiring the two archived files;
Undo retains exact restoration evidence. A fresh disposable-target candidate
and owner live test remain the final gate.

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

### Current priority: adaptable current-generation upgrades

The owner has explicitly chosen forward server evolution over permanent
backward-runtime support while requiring a useful public product. Most external
targets are expected to be Preservation-like, lightly customized, and entirely
without World Builder map support. They should upgrade to Current Base. The
owner's advanced Core lineage should upgrade to Current Advanced on the same
platform generation and contribute reusable platform/module improvements
without imposing its gameplay or content on Base users.

- [x] Define and schema-bind the foundation identities: the provider owns the
  platform release, Base/Advanced variants, modules, bundle specs, and resolved
  compositions; the Editor owns input adapters, project capabilities, target
  ledgers, and classification. Current provider bundles remain explicitly
  non-installable until runtime artifacts and release evidence exist.
- [x] Add the non-executing, read-only role-aware T0-T5 classifier for sealed
  synthetic Preservation-like baselines, expected local state,
  configuration/data changes, maintained and unported extensions, ABI-coupled
  changes, and unknown inputs. Production Preservation fingerprint adoption and
  transactional upgrade execution remain separate unfinished work.
- [ ] Build Current Base with canonical legacy-map conversion, a matching
  client, typed configuration/database migrations, and no Advanced-only
  effects.
- [ ] Define the current extension/module contract and route recognized light
  customization to canonical data or maintained modules; unknown executable
  changes remain zero-write blockers until ported. A recognized but not-yet-
  ported extension receives a distinct actionable `PORT_REQUIRED` result.
- [ ] Complete the Core behavior-disposition register and build Current
  Advanced from the same provider generation.
- [ ] Implement project migration, target-runtime ledger, side-by-side staging,
  semantic destination preview, explicit variant consent, atomic activation,
  and exact recovery. The supported CLI and transaction engine now bind a
  closed built-in Preservation-family adapter/migrator profile, typed legacy
  configuration, staged durable-state/map migration boundaries, exact
  identity/preimage evidence, external staging, activation-last, artifact
  verification, rollback, and interrupted recovery. Target documents cannot
  select executable adapters or migration identities. This item remains open:
  the production provider is an installable unreleased candidate, but real apply
  remains a zero-write refusal. A separate compiled execution-readiness gate is false,
  independent of provider metadata, pending executable
  migrators, staged and installed runtime verification, project migration,
  broader reviewed fingerprints, and UI consent. The Editor now renders typed
  configuration and executes the provider's hash-bound
  `current-base-state-migration-v1` exact retro/core/initialized SQLite rows from the staged
  `server-runtime`, verifies closed evidence/current-schema output and exact
  source immutability, and rejects customized state. MariaDB has a safe
  loopback/schema/environment-name preview contract but remains apply-blocked
  until external stage cleanup/recovery is exact. Authorization is selected-plan
  specific, so future proven SQLite apply does not depend on MariaDB. Complete
  recognized descriptor-backed packed-map evidence now runs through the complete
  existing packed converter. Preview binds its conversion-plan identity and the
  exact deterministic output inventory; staging revalidates full reverse parity,
  package/report/reconciliation hashes and no-extra-file ownership. No raw
  one-sector shortcut is accepted as public migration proof. Materializing that
  package plus migrated configuration/state into runnable server/client roots,
  runnable staged/installed server-client verification, and live-instance
  state activation/recovery remain open. The provider's candidate
  verifier still has build-only authority. A separate bundle-inventoried
  installed-execution verifier now proves two real server/client launch cycles,
  handshake/login/map/state/restart checks on private disposable copies, with
  closed arguments and hash-bound evidence. Editor activation still needs to
  invoke and verify that contract; complete transition behavior and spawn
  walkability remain outside this execution proof. Editor code must not guess
  launch arguments or trust unbound logs.
  Current transaction confirmation now binds the complete plan, and atomic
  pending phase receipts recover a process halt after publication. Preview
  probes, while apply/recovery hold, the translated game/websocket ports and a
  target-scoped configuration/ledger lock. Provider-style colon configuration,
  explicit SQLite selection, and bounded public bind addresses are translated;
  disposable verification alone may force loopback.
  The runtime now requires Current Base SQLite in a private, canonical external
  state directory via `openrsc.currentBaseStateRoot`, outside both its working
  directory and code artifacts; missing/aliased/in-code databases do not fall
  back or get created. The installed verifier proves two launch cycles against
  external state and records that boundary in its closed evidence. Editor
  layout planning binds the exact provider state policy and rejects altered
  destinations. Generated SQLite snapshot/evidence bytes now have a closed
  post-migration inventory bound into activation, ledger verification and phase
  receipts without changing the confirmed preview. Pre-publication, installed,
  rollback ownership and receipt-based recovery checks use that exact inventory;
  conflicting phase receipts and changed/aliased outputs refuse without cleanup.
  Disposable staged-release tests cover reload/relocation and drift; production
  apply remains disabled. Live-instance creation/activation/recovery, remaining
  writable paths and Editor verifier invocation remain open.
- [ ] Prove sealed Preservation, positive and Advanced-negative Base semantics,
  light customization, maintained module, recognized-unported extension,
  unknown-refusal, Advanced Core, Base/Advanced N-to-N+1, and module lifecycle
  rows twice with composition-bound evidence and no required skips.

Ordinary map import becomes a map-only transaction once the target ledger
proves that its selected current composition is installed. The complete current
design is maintained in [World Builder 2 Current Runtime Upgrade
Review](WORLD-BUILDER-2-CURRENT-RUNTIME-UPGRADE-REVIEW.md).

## Superseded pinned-core implementation record

The following sections through the historical integration/release checklist
record the now-rejected pinned-core strategy. Their checked items and present-
tense implementation notes are historical facts, not the current plan or
acceptance evidence.

- [x] Define the single current managed-runtime identity and the target-owned
  bundle contract needed to retain legitimate game customization.
- [x] Make the explicit runtime upgrader install the exact current
  host-integrated server/client pair and retire the earlier class-shadowing
  overlays before a separate map-only Import.
- [x] Bind host integration to package-driven server/client artifact probes,
  align only a known old decoder source, preserve newer/custom source, and
  guard the target Ant build so it cannot overwrite the exact prebuilt core.
- [x] Refuse ordinary Import while a retired provider can shadow target-owned
  classes. Runtime upgrade replaces the authoritative core/client archives and
  authoritative v3 capability while retiring v1/v2 receipts and preserving
  plugins, definitions, databases, unrelated source/build behavior,
  configuration, maps, and assets.
- [x] Replace installed-v1 preservation with a bounded one-way migration and
  remove obsolete runtime branches after migration and rollback coverage are
  proven.
- [ ] Keep old project/map readers only where needed to migrate authored data
  losslessly into the current format; do not maintain old runtimes merely to
  avoid designing the upgrade.
- [ ] Prove the cutover on a disposable target through upgrade, launch, client
  connection, map edit, and at least two repeat imports.

### Historical post-release feature: blocking blended base color

- [x] Reserve raw ground overlay `255` outside the `TileDef` domain as the
  non-walkable counterpart to overlay `0`.
- [x] Keep overlay `255` visually driven by each tile's ground-color value and
  include overlay `0` and `255` in one vertex-blending neighborhood so their
  colors blend across either boundary.
- [x] Apply full terrain collision in the server, native layered collision
  plan, client scene, and live editor patch path without looking up TileDef
  `254`.
- [x] Expose a searchable **Non-Walkable Base Floor Color** entry in the editor
  and retain overlay `255` in project-bound definition filters.
- [x] Exclude overlay `255` from imported-package floor-definition
  dependencies and packed-conversion definition validation.
- [x] Version the installed runtime loader capability and refuse imports that
  use overlay `255` with the preserved v1 loader.
- [x] Supersede the interim target-specific v2 preservation behavior with the
  current managed server/client bundle upgrade.
- [x] Publish the tested runtime revision, advance `runtime-provider.lock`, and
  complete the Editor parity and full-suite gates.
- [x] Install loader-v7 through the current managed runtime bundle instead of
  requiring a manual target-source integration.
- [ ] Obtain native user validation of painting, mixed-edge blending,
  collision, save/reload, and a repeated server import before the next release.

### 1. Capture the real failure and the stop-gap

- [x] Record that the stop-gap was a manual Core-only repair, not a pushed
  World Builder handoff to collect or integrate.
- [x] Reproduce the current failure against a disposable copy of the same
  server layout and version that failed for the owner.
- [x] Record the exact symptom: server log, selected configuration,
  installed map paths, runtime archives, capability evidence, startup command,
  and first failing load operation. Client connection evidence remains part of
  the end-to-end gate.
- [x] Preserve a minimal sanitized fixture or deterministic fixture generator
  for that starting layout. Never commit a real server, map, credential,
  database, log, or user workspace.
- [x] Add a failing regression proving that the current import can claim
  success without producing a server that can load the new map.
- [x] Compare the working and pre-fix Core copies to derive runtime loader,
  terrain encoding, placement encoding, NPC override, activation, and launch
  configuration evidence. Do not copy Core-specific source into the product.
- [x] Prove that generic runtime replacement activates the imported package but
  loses target-specific definition loading; record the first missing definition
  (`NPC 854`) and restore the disposable target through reviewed Undo.

Exit condition: one repeatable failure with evidence, not another inferred fix.

### 2. Shorten routine feedback

- [x] Add a documented focused-test entry point that can select one test file,
  test class, or test method without weakening `./scripts/test.sh`.
- [ ] Make the focused runner build the tools once and allow compatible test
  modules to reuse that build instead of rebuilding independently.
- [x] Add per-module timing output and maintain a small list of subsystem test
  groups: discovery, projects, transactions, packaging/updater, and workflow.
- [x] Identify redundant full-install fixture construction in the lifecycle
  and transaction suites and introduce shared deterministic fixture builders.
- [ ] Split oversized test modules only where a behavioral boundary materially
  improves focused execution or navigation; do not split by line count alone.
- [ ] Split production classes only along proven ownership boundaries as they
  are touched; do not delay the import repair for a broad refactor.
- [ ] Make release status canonical in one location and update stale README and
  changelog claims. Historical validation records remain immutable evidence.
- [x] Add a concise manager status mode that hides already-merged remote topic
  branches unless detailed history is requested.
- [x] Document the verification-tier rule in the development workflow so
  onboarding, diagnosis, and documentation work do not trigger full release
  verification by habit.

Exit condition: a developer can compile and run the directly relevant import
regression in well under the full-suite time, while the unchanged full gate
remains available.

### 3. Remove Undo Last Server Import

- [x] Freeze the exact intended boundary: remove only user-initiated reversal
  of a completed server import.
- [x] Remove the desktop button/menu, launcher-model action, adaptive CLI
  commands, Linux/Windows Undo launchers, package inputs, candidate inventory,
  updater expectations, and end-user instructions.
- [x] Keep `WorldBuilderAdaptiveUndo` internal because import chaining and
  interrupted recovery reuse its verified historical-plan reconstruction.
- [ ] Reassess chained-import receipt/address logic. Retain only evidence needed
  to verify the current installed state, recover an interrupted operation, or
  safely apply a later import.
- [x] Preserve automatic rollback when an import fails before completion.
- [x] Preserve explicit Recovery for a transaction interrupted in an uncertain
  state, unless a replacement recovery design proves it unnecessary.
- [x] Preserve editor-session Undo/Redo and project-local Region Paste Undo.
- [x] Ensure the updater removes formerly managed Undo launcher files during
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

- [x] Derive the first compatibility bill of materials from the exact pinned
  runtime provider rather than from filenames observed in one target.
- [x] Identify the currently proven server components: runtime archive, loader
  code, capability descriptor, selected-map configuration, target launch
  configuration, definition compatibility, and startup/classpath mode.
- [ ] Complete the server component contract for generated launch
  configuration, supported startup modes, database/schema needs, and required
  support files.
- [x] Identify the corresponding client archive/protocol requirement and the
  installed client package path.
- [x] Connect the matching private client to the disposable imported server and
  verify that the installed layered package activates after authentication.
- [x] Add an installed-package client bootstrap contract for v2-compatible
  targets so ordinary player startup no longer opens or hashes
  `Custom_Landscape.orsc` before login; preserve archives for installed v1
  targets until they can adopt that bootstrap without losing customization.
- [ ] Separate the overloaded `custom_landscape` feature flag from terrain
  authority and unrelated combat-sprite presentation behavior.
- [ ] Complete remaining client launch/configuration and cache/assets
  requirements after the archive-free bootstrap path is proven.
- [ ] Classify each component as replace, merge, generate, migrate, verify-only,
  or unsupported. Define ownership and backup behavior for every mutation.
- [x] Prove that target-preserving staged server/client builds are viable using
  the target source and libraries, without copying the complete target into the
  product or replacing unrelated customized runtime behavior.
- [x] Identify the packed-Core package-address contract as the canonical package
  fingerprint rather than the Editor's newer native-inventory digest.
- [ ] Define supported source layouts explicitly. Unknown or ambiguous layouts
  must fail with a precise report instead of receiving a partial installation.
- [x] Version the compatibility contract so future runtime changes cannot be
  mistaken for compatibility with older import plans.

Exit condition: one reviewed manifest explains exactly what makes a target
capable of loading an imported World Builder 2 map.

### 5. Implement atomic compatibility installation

- [x] Make Import preview list the complete compatibility plan separately from
  map-package and activation changes.
- [x] Support the proven real starting layout, including targets that do not
  already contain World Builder's capability evidence or exact runtime pair.
- [x] Stage and verify every required compatibility byte before target mutation.
- [x] Back up every replaced target file for failure rollback, while continuing
  to tell the user that this is not a substitute for a server backup.
- [x] Apply runtime/support files first, map packages next, and selected-map
  activation last, with a defined durable order.
- [x] Refuse map activation if any required compatibility component is absent,
  mismatched, unverifiable, or unsupported.
- [x] Verify the installed server package, client package, active configuration,
  capability/protocol, runtime hashes, and all other manifest components after
  apply.
- [x] On any pre-completion failure, restore the exact before-state or leave a
  precise recovery record; never report a map-only partial result as success.
- [x] Keep all testing and validation against disposable target copies. Do not
  mutate a real user server without separate explicit authorization.
- [x] Replace the unconditional legacy-file question with exact
  absent/equivalent/already-incorporated/conflicting classification.
- [x] Record an immutable keep-layered/apply-legacy/cancel decision with both
  legacy hashes and the selected package fingerprint.
- [x] Persist successful supersession provenance so later detection does not
  repeat the question when neither source has changed.
- [x] Retain both packed archives while the installed player client still needs
  them; permit verified retirement only after archive-free client bootstrap is
  installed and proven.

Historical exit condition (superseded): the rejected design treated Import as
one atomic map-plus-compatibility operation. The replacement makes target
runtime upgrade its own complete atomic transaction, followed by a separately
reviewed map-only Import once the target ledger is current.

Implementation checkpoint (2026-08-31): runtime provider
`f48bdfcefd9706d61a2c157d57a22ce7ef93b4e1` publishes the current managed
runtime bundle; Editor `dfd9129c41e5f2eb5cc9a2b10d12b78f62127067`
pins it and implements the one-transaction upgrade/import path. Owner-native
testing of a fresh candidate remains in section 6 before release.

### 6. Prove end-to-end usability

- [x] Run the focused regression against the sanitized old/incompatible target
  fixture and confirm it now passes for the right reason.
- [x] Start the installed server from a cold state and prove it loads the new
  layered map without loader, configuration, definition, schema, or protocol
  errors.
- [x] Start the matching client, authenticate to the isolated server, enter the
  imported area, and verify terrain and all supported placement families.
- [x] Test changed-target refusal, missing components, mixed versions, partial
  failure rollback, interrupted recovery, and a second later import.
- [x] Compare every path outside the reviewed mutation plan before and after.
- [x] Run runtime-provider parity and the relevant subsystem suites.
- [x] Run `./scripts/test.sh` and `git diff --check` at final integration.
- [x] Obtain owner-native validation from a disposable server copy before any
  release gate is opened.

Exit condition: automated and native evidence demonstrate that a representative
previously incompatible server becomes runnable with the imported map.

Owner checkpoint (2026-09-01): Alpha.69 successfully installed, built, started,
connected, and authenticated the private server/client pair. Native terrain then
exposed a signed-byte materialization defect on elevations 128..255. The runtime
provider was already correct, but installed-client upgrade v3 had left the
target's older `NativeLayeredTerrainChunk` and `NativeLayeredTerrainSnapshot`
assignments untouched. Upgrade v4 now applies two bounded, repeatable semantic
transforms so Import promotes legacy elevation bytes to unsigned `Tile` ints
without replacing either complete target class. Boundary-value runtime tests,
transactional import/undo/repeat tests, and owner visual retesting gate the next
candidate.

Owner checkpoint (2026-09-01): Alpha.70 proved terrain, layers, and custom NPCs
through elevation 255, then crashed when a v2 sector containing elevation 500
entered the client's radius-two halo. Diagnostics proved the installed server
sent the correct 11-byte v2 sector while the imported target client retained its
historical fixed 10-byte chunk inflater. Installed-client upgrade v5 therefore
owns `NativeLayeredTerrainChunk` and `NativeLayeredTerrainPacketDecoder` as one
encoding-aware protocol unit: recognized Alpha.69 and Alpha.70 revisions are
replaced transactionally with the provider's v1/u8 plus v2/u16 implementation,
while unknown custom revisions fail before mutation. An executable mixed-v1/v2
v9 halo regression now materializes elevation 500 and rejects mismatched declared
encodings without changing residency.

Owner checkpoint (2026-09-01): Alpha.72 completed the upgrade-first import
path twice. The owner validated the normal detection, editing, saving, import,
private server/client launch, elevated terrain, and smaller editor functions,
then deleted the disposable server copy, created a fresh copy, and repeated the
complete workflow successfully. The exact candidate was accepted, rebuilt from
its published gate commit, independently inspected, uploaded, downloaded back,
and verified byte-for-byte before the development gate was closed.

### 7. Integrate and release deliberately

- [x] Review the complete Editor diff and any independent runtime-provider diff
  at exact pushed tips.
- [x] Publish and pin runtime work first if the compatibility contract requires
  runtime-provider changes.
- [x] Merge the tested Editor work only after the stop-gap has been reconciled
  and no unique work is stranded.
- [x] Update user documentation to explain supported targets, backup
  responsibility, previewed compatibility changes, and recovery limitations.
- [x] Build a fresh restricted candidate and complete the normal owner
  acceptance, release gate, archive inspection, production rebuild, upload,
  and post-publication verification process.

Exit condition: the tested behavior, documentation, pinned runtime, release
artifacts, and published claims all describe the same compatibility contract.

## Definition of done for target upgrade and map import

The replacement is not complete until all of the following are true:

- a clean supported target from each required public/Advanced family upgrades
  without a manual stop-gap or retained legacy runtime;
- runtime-upgrade preview names every target path, behavior disposition, state
  migration, variant/module choice, and client effect that will change;
- map Import is refused until the target ledger proves the selected current
  composition, then changes only the reviewed map package and activation;
- the upgraded target can cold-start and load the imported map with its exact
  matching client;
- unsupported targets fail before mutation with actionable evidence;
- failed or interrupted operations preserve exact rollback/recovery safety;
- completed-import Undo is absent and the backup warning is explicit; and
- focused, full-suite, parity, disposable-target, and owner-native validation
  all pass at their appropriate gates.
