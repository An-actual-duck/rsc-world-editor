# World Builder 2 current-runtime upgrade review

## Document status

| Field | Value |
| --- | --- |
| Status | Active planning decision and evidence review |
| Captured | 2026-09-04 |
| Product objective | Upgrade supported servers to one current managed runtime without losing selected game behavior or durable state |
| Current candidate | Rejected; the pinned generic-core design is not a safe customized-host upgrade |
| Implementation authorization | None in this review; inspection and documentation only |
| Editor evidence | `a3a1f8664179cfdd7e7e54d9089a472d956c17b9` |
| Runtime-provider evidence | `112eea42420d835ac9d208be687127eb7ae7f455` |
| Preservation fixture | `c0102e60774ab9c9076aabae49f6f97fb6fc4b00`, tree `6db5536d795abf34f303bb03b20c43b8cfb9e3fe` |
| Core-copy evidence | `fec94c8731b5521410963575ef0f2fa5c05ef0b3`, tree `22051f8cff480975f3f1d2d1c7e2af836d9d7ff0`, tag `v0.2.87` |

This record supersedes the current pinned-prebuilt-core strategy as product
direction. Earlier live validation remains useful historical evidence about
individual failures, but it does not validate a later strategy whose runtime,
build, activation, or upgrade authority changed.

The active reliability plan remains the historical incident and work log. This
document is the smaller current source for the replacement architecture,
fixture matrix, staged implementation order, and release gates.

## Decision

World Builder will migrate recognized older targets into one complete current
Core-derived Spoiled Milk server/client runtime maintained by the independent
runtime provider. The current product runtime must deliberately incorporate
the selected behavior of the owner's game; it cannot remain a generic
Preservation-like host with compatibility overlays. World Builder will not keep
a matrix of old host runtimes alive, install a generic binary over divergent
source, or accumulate target-specific compatibility layers as the long-term
design.

Compatibility exists only at the migration boundary:

1. identify an exact supported input;
2. inventory and classify its behavior and state;
3. transform durable state and deliberately adopted behavior into the current
   model;
4. stage and execute the current runtime;
5. atomically activate it with exact recovery evidence; and
6. retire the old runtime, build, loader, updater, and client path.

"Preserve" therefore means preserving selected game semantics, authored
content, configuration intent, accounts, and other durable data. It does not
mean retaining obsolete binaries or silently keeping old source as the real
runtime authority.

### Upgrade-first safety rules

- Upgrade is the normal successful result for every recognized older target;
  map-only operation is available only after the target ledger proves the
  current runtime is active.
- Legacy readers, fingerprint recognizers, and schema translators live only in
  the migration tool. The installed server/client do not grow permanent
  branches for every historical input.
- A successful migration has one active code runtime and one matching client.
  It does not leave old and new implementations competing on a classpath.
- Behavior is retained through an explicit product decision and semantic
  acceptance test, not by retaining every historical file.
- Any unclassified code, plugin API, definition, asset, configuration, or data
  delta stops before target mutation. Once classified, it becomes a bounded
  migration or a maintained current feature rather than another compatibility
  mode.
- Rollback may restore the exact old system after a failed cutover, but that
  recovery state is not a second runtime that World Builder must support for
  ongoing authoring.
- Safety checks must prove and enable the upgrade. They must not turn a
  recognized old version into a permanent refusal or require project
  recreation.

## Evidence and findings

### The pinned generic core is a binary/source split, not an upgrade

The current target upgrader copies `server/core.jar` and the matching client
from the project's frozen `working/runtime`, installs capability evidence, and
patches the target Ant file so `compile_core` is skipped. The target's
customized source remains visible but cannot produce the active binary.

The supplied Core failure dossier shows why that is not coherent. The target's
plugins reference customized Core types absent from the provider JAR. With the
pinned JAR installed, compilation of 494 plugin source files reports 32
missing-symbol or missing-package errors and the server never reaches its
listener. Adding those particular classes to the generic JAR would address one
observed set of symbols without establishing that the complete host behavior,
plugin ABI, client, definitions, dependencies, and database are current or
coherent.

Normal source authority creates a forced choice. Either upgraded source and a
deterministic build must reproduce the active current artifacts, or legacy
source must be explicitly archived outside the active managed runtime. Keeping
divergent source in place while preventing it from rebuilding is not an
acceptable third state.

### The project lifecycle cannot perform continuing upgrades

The current implementation binds target and runtime identity to the project's
immutable original discovery snapshot:

- a target runtime upgrade is rejected after a successful target transaction;
- a changed target fingerprint requires a fresh project;
- upgrade payloads come from project-local `working/runtime`; and
- opening the project verifies that frozen runtime fingerprint.

Consequently, updating World Builder or its provider does not give an existing
project an N-to-N+1 target migration. Project recreation is the migration
mechanism, which risks authored continuity and makes every future provider
change another special case.

### The runtime v3 contract is incomplete and internally contradictory

The v3 capability descriptor declares a host-integration source payload that
is absent from the provider checkout. Its unit test hashes the ordinary target
source path instead of the declared payload path, so the test passes despite
the missing payload.

The descriptor declares a build-guard policy, but the provider does not
implement that guard. Its normal server and client build/launch flows delete
and rebuild the installed archives. A legacy bundle descriptor simultaneously
calls the retired shadow JAR and v2 receipt current, even though the build audit
rejects that JAR and v3 requires those receipts to be retired.

The authoritative verification is also too weak. It searches class files for
marker strings but does not establish executable class identity, plugin ABI,
normal launch topology, a matching server/client handshake, or a successful
installed-host boot.

### Transaction mechanics are stronger than runtime semantics

The importer already has valuable safety machinery: exact before/after file
states, content hashes, backups, pending receipts, target revalidation, atomic
writes, post-write checks, recovery, and rollback. The problem is the scope of
the plan and its semantic success condition, not the absence of transactional
care.

Most runtime-upgrade fixtures begin with v3 capability files and fake marker
archives already installed. The primary upgrade fixture has an empty
`compile_core` target and never compiles the real server/plugins, boots the
installed host, or connects the matching player client. Release-gate validation
checks prose containing expected SHAs and claims; it does not bind executable
evidence to the exact upgrade strategy and artifacts being released.

### Preservation is a clean legacy input, not the desired output

The sealed `RSC-Preservation-Importer-Test` checkout is a useful first adapter
because it is clean, source-complete, and contains matching legacy server,
plugins, clients, definitions, maps, assets, configuration, and an empty SQLite
database. It has no prebuilt server, plugin, client, or launcher artifacts, so a
test cannot pass by substituting fake archive markers.

It is an OpenRSC Preservation distribution with operational and quality-of-life
choices, not a byte-for-byte historical RSC oracle. Its legacy runtime also has
properties that must be translated or retired rather than carried forward:

- effective configuration can be silently overridden by ignored `local.conf`;
- two profile keys do not match the names read by the implementation;
- database patches run during server startup and can leave partial schema
  effects before the ledger records the batch;
- client definitions duplicate server-side identity and must migrate with the
  server/client pair;
- the legacy cache MD5 inventory is stale; and
- legacy launch/update scripts have broad process-killing or network-mutating
  behavior and must never be executed by the importer.

The included SQLite database is empty. A populated SQLite fixture and a
separate MariaDB fixture are required before the Preservation adapter can prove
player-state migration.

### Core is a broad product fork, not a small compatibility delta

The explicitly authorized `/home/justin/Core-Framework (copy)` is a clean
tracked reference at the identity recorded above, apart from one unrelated
pre-existing untracked documentation file. It contains a same-tree
`origin/preservation` reference at the exact sealed Preservation commit, which
permits a direct source inventory even though the imported histories have no
merge base.

Relative to that Preservation reference, Core contains:

| Surface | Added | Modified |
| --- | ---: | ---: |
| Server core Java | 439 | 180 |
| Server plugin Java | 44 | 237 |
| Shared client Java | 112 | 41 |
| Desktop client Java | 63 | 4 |

This is too broad to model as a handful of loader patches over a generic host.
The binary evidence is stronger still. The rebuilt target `core.jar` contains
16,434 classes while the pinned candidate contains 16,134. The target has 377
classes absent from the candidate, and 759 common classes differ byte for byte.
Analysis of the existing `plugins.jar` against the candidate finds 26 plugin
classes depending on 37 wholly absent candidate classes, for 64 direct
caller/dependency pairs, before considering methods missing from classes that
exist in both archives.

Representative target-only contracts include expanded inventory capacity,
Monster Slayer services, cleric spellbook/inventory packets, and projectile or
damage transaction types. These are game and plugin APIs, not map-loader
details. Earlier Core incident evidence also records a broad shadow runtime
causing live linkage failures and silently reverting expanded inventory
behavior. This corroborates the current compile failure: archive replacement
cannot establish semantic equivalence for this target.

The copy also contains ignored live-like and sensitive state, including local
environment/configuration, credentials and PEM material, logs, run markers,
database/backups, and an ignored World Builder installation/project. None of
that material may enter a fixture, project, diagnostic archive, commit, or
release. Only generated synthetic state and a reviewed code/topology manifest
may represent the customized target in product tests.

Core's selected behavior should therefore become an explicit product input to
the current runtime design, not another permanently supported old host. Every
meaningful Core delta needs one recorded disposition:

- **adopt** as maintained current server/client behavior;
- **transform** into a current data, plugin, configuration, or migration
  contract;
- **preserve-state** as user-authored or operational data moved through a
  schema migration;
- **retire** as an obsolete loader, build, updater, workaround, duplicate
  implementation, unused feature, or bug; or
- **decision-required**, which blocks cutover until the owner chooses the
  intended current behavior.

The goal is behavioral uplift, not source-file parity. Selected Core behavior
gets current implementations and semantic tests in the runtime provider;
unselected historical machinery does not become a compatibility obligation.

#### Active Core boundaries that the new runtime must replace coherently

Core's active product is the server engine, separately compiled plugins,
configuration/definitions/database/maps, shared client, desktop/OpenGL client,
tests, tooling, and custom assets. The old Android client, self-updating PC
launcher, and portable Windows toolchain are already legacy residue rather than
active current components; they should not be revived by migration.

The documented production build authority is bundled Ant, not Gradle. It builds
the complete current server source into a fat `core.jar`, compiles all current
plugins against that core, and combines the shared and desktop roots into the
matching client. The present build still duplicates 14,070 dependency classes
inside the fat core while external libraries also participate in launch, and
the secondary Gradle dependency declarations have drifted from the shipped Ant
libraries. V4 needs one reproducible build and dependency bill of materials,
one runtime origin for each server class, and an explicit versioned plugin API.

This does not require changing the language level, every dependency, the build
system, the runtime layout, and game semantics in one unreviewable jump. The
upgrade should first establish the sealed current Core-derived runtime and
class-origin contract, then modernize toolchain/dependencies in separately
gated current-to-next upgrades. Sequencing independent risks is not legacy
compatibility; both steps still move forward and retire their predecessors.

#### Configuration and database behavior require real migrations

Core loads `connections.conf` before the selected world configuration, but an
ignored `local.conf`, when present, replaces the named profile rather than
overlaying it. Its loose YAML-like reader keeps the first duplicate key. A
tracked or ignored legacy file therefore is not by itself the effective
configuration intent.

The input adapter should resolve legacy precedence read-only, redact secrets,
and render a typed current configuration with explicit precedence, external
secret references, private-by-default network binding, schema validation, and
a reviewed translation report. The legacy parser and `local.conf` replacement
semantics then retire.

Core startup also applies unrecorded database patches against the opened live
connection and records the ledger only after the batch. Ledger-query failures
can be interpreted as no applied patches. The current schema adds product state
such as Summoning and Blessing; this state must survive, but startup-driven
patching is not a safe migration engine. SQLite and MariaDB each require
versioned migrations on staged copies, invariants, failure injection between
DDL and ledger changes, and an atomic data cutover or exact restore.

#### Server, data, and client are one upgrade unit

Core has more definitions, authored placement overlays, content-addressed
layered packages, custom assets, and gameplay state than Preservation. Its
active client uses a newer protocol/version and includes expanded-inventory,
Cleric, Summoning, production, bank, pinned-interface, layered-scene, and
OpenGL behavior. A server-only upgrade can build and listen while still
silently breaking item identity, UI, packets, rendering, or gameplay.

The behavior register should begin with these Core feature families, without
assuming that every experimental or administrative feature must ship:

- combat, projectile, damage, Magic, Ranged, area effects, and extension APIs;
- Summoning, Blessing, Cleric, Worship, Enchanting, Herblaw, gathering,
  production, and their persistent state;
- Monster Slayer services, contacts, shops, state migration, entitlements,
  and 30/31/40-slot inventory-capacity semantics;
- Mage, Legends, and Gnome guild behavior plus offering, fishing, and foundry
  rules referenced by current plugins;
- native layered-map loading, placements, collision, residency, package
  activation, and signed coordinate/elevation semantics;
- login/network framing, server/client version and capability exchange;
- matching client catalogs, packets, interfaces, terrain scene, OpenGL
  renderer, assets, and release natives; and
- safe configuration validation and intended performance/correctness fixes.

The owner marks each family and any remaining delta `adopt`, `transform`, or
`retire`. Adopted families receive end-to-end semantic sentinels; that list is
the cutover specification.

#### Safe Core-derived fixtures

Fixtures must be extracted from the exact Git object, never copied from the
working directory. An allowlist records the commit, tree, path inventory, and
SHA-256 manifest. Host/network configuration is transformed to generated
loopback-only values. All ignored and untracked content, local environment,
credentials, PEMs, real or development databases/backups, logs, run markers,
receipts, built artifacts, and the entire ignored `World Builder 2/` directory
are hard exclusions.

Two fixtures serve different purposes:

1. a small ABI/linkage canary containing the critical target-only APIs and
   plugin callers that reproduces generic-candidate failure; and
2. a sealed allowlisted tracked-source topology with synthetic configuration,
   database rows, representative definitions/assets, and bounded map data for
   full current-runtime migration.

The full fixture must contain no real player names or state. Synthetic rows
cover inventory-capacity masks, malformed/quarantined caches, old-to-current
Monster Slayer state, Summoning/Blessing values, account, bank, inventory,
quest, and ordering edge cases. MariaDB runs in a separate disposable test
service; neither fixture is launched from the authorized source copy.

## Product model

### 1. Authored project identity

The durable World Builder project owns its UUID, map packages, placement and
definition dependencies, edit history, and project-schema migrations. Opening
an old project in a new application must losslessly migrate this state without
requiring project recreation.

The project's authoring runtime is a rebuildable cache selected by the current
application. It is not the authority for which production runtime a target
must install.

### 2. Current provider bundle

The installed application selects one immutable, content-addressed current
runtime bundle from `rsc-world-editor-runtime`. Its next major baseline should
be created by reviewing and adopting the wanted behavior from the exact pinned
Core copy into the independent provider with explicit provenance; the Git
histories remain independent. That bundle contains the complete maintained
server/client code runtime and every exact dependency, plugin, launcher,
profile, migration, schema, and optional source artifact needed by its declared
host product.

Desired customized-host behavior is reviewed and ported into this maintained
runtime or transformed into supported data/plugins. It is not left hidden in
an old target tree and it is not loaded through a broad duplicate-class
overlay.

### 3. Target runtime ledger

A stable target installation identity owns a separate runtime ledger. The
ledger records the installed bundle, component hashes, input adapter and
predecessor, state-migration IDs, server/client build identities, active
launcher pointer, active map package, verification evidence, and transaction
receipts.

Map-import history remains project-scoped. Runtime-upgrade history is
target-scoped. A verified current ledger becomes the trusted before-state for
the next current-to-next upgrade.

## Current bundle and migration contract

The provider must generate a versioned manifest from staged release artifacts.
At minimum it records:

- strategy ID and strategy-manifest SHA-256;
- provider commit, bundle ID, runtime version, and product variant;
- a closed inventory of every server, client, plugin, dependency, launcher,
  profile, schema, migration, and optional source artifact;
- path, type, mode, size, SHA-256, destination, ownership, replacement policy,
  and rollback policy for every component;
- one complete payload-inventory hash;
- exact recognized input-adapter IDs, layouts, and fingerprints;
- durable-state roots plus configuration and database migration IDs;
- embedded server/client runtime identities and a required login handshake;
- an explicit, working-directory-independent launch path;
- separate package-format and network-wire capability declarations;
- supported predecessor ledger/receipt states; and
- required executable scenario IDs.

No dangling payload path, marker-string probe, receipt existence check, or
patched build-file text is authoritative evidence that the current runtime is
usable.

The preferred activation model is a complete runtime staged beside the old
one in a content-addressed managed directory. Verification occurs before a
small explicit launcher/pointer is switched. Durable state lives outside the
code-runtime directory. This makes replacement and rollback explicit and
prevents an old build command from overwriting the active runtime.

## Target classification and upgrade behavior

Preview classifies the complete offline target before proposing changes:

| Classification | Action |
| --- | --- |
| Exact current ledger and artifacts | Runtime no-op; permit map-only import |
| Supported earlier managed ledger | Apply its finite migration chain to current |
| Exact sealed Preservation input | Translate state/configuration and replace its runtime/client with current |
| Recognized customized Core-derived input | Require its selected behavior in the current bundle, migrate state/configuration/content, then replace the old runtime |
| Known baseline plus bounded supported deltas | Apply the corresponding reviewed transformations |
| Unknown, conflicting, or secretly modified input | Refuse before mutation with an actionable component and semantic diff |

Refusal is a discovery result, not permanent compatibility policy. A desired
unknown target is researched once, its behavior is classified, and a new
bounded input migration or provider feature is added before cutover. Import
never ports executable code dynamically. Successful migration leaves that
target on the same current runtime as every other supported input.

## Staged transaction

1. Require the target to be offline and establish its stable installation
   identity.
2. Inventory tracked, ignored, and generated files without executing target
   scripts; detect effective configuration and secrets without copying them
   into project or release artifacts.
3. Classify every relevant component as `replace`, `transform`, `adopt`,
   `preserve-state`, `retire`, `verify-only`, or `unsupported`.
4. Back up the exact reviewed before-state and produce an external-backup
   warning and confirmation.
5. Stage the current bundle and copied durable state outside the live target.
6. Run configuration and database migrations against staged copies with
   independent checkpoints and invariants.
7. Clean-build the provider bundle and compile every adopted plugin against
   the exact current core. If source ships as active source, prove that its
   normal build reproduces the installed artifacts.
8. Launch the staged server through the supported current launcher on private
   loopback ports and disposable database/state paths.
9. Connect the exact matching current client, verify the build handshake,
   authenticate a synthetic test account, load the map, and exercise selected
   content/gameplay sentinels.
10. Revalidate the target, apply the reviewed state and content changes, and
    atomically switch the managed-runtime pointer.
11. Repeat normal launch/login/map verification against the installed target.
12. Write the active ledger and successful receipt last. On any earlier
    failure, restore the exact before-state or leave a precise recovery record.

## Fixture and executable matrix

### Mandatory baselines

1. Exact sealed Preservation source tree with its empty SQLite database.
2. First-run Preservation state including ignored configuration/client files
   and synthetic credentials.
3. Populated Preservation SQLite data spanning accounts, stats, inventory,
   bank, item metadata, quests, friends/ignores, recovery, former names, and
   ordering edge cases.
4. Equivalent populated MariaDB data and consistent snapshot/restore path.
5. Sanitized Core-derived topology and customization fixture with no real map,
   credential, log, account, or database content.
6. One-axis synthetic customization fixtures for core source, plugin/API,
   definitions, placements, landscape, sprites/assets, configuration, client,
   dependency/classpath, launcher, and database schema.

### Required scenarios

- old project opens in the newer application without recreation;
- runtime N upgrades to current, followed by current to a synthetic N+1;
- authored project data remains exact across both application upgrades;
- every adopted plugin compiles and loads against the current core;
- actual normal build and launcher paths cannot restore the old runtime;
- matching server/client cold start, identity handshake, login, and first scene
  load succeed;
- terrain boundaries `0`, `255`, `256`, and `65535`, signed coordinates and
  levels, all placement families, collision, NPC respawn, save/reload, and
  client rendering inputs behave correctly;
- selected customized-host gameplay, command, definition, asset, and client
  sentinels survive;
- two map edit/import/rebuild/reconnect cycles succeed and later runtime plans
  are no-ops;
- online, malformed, mixed-version, low-space, permission, and unknown-layout
  targets fail before mutation;
- interruption at each backup, staging, migration, activation, verification,
  and receipt boundary produces exact rollback or deterministic recovery; and
- no path outside the reviewed plan changes.

Required executable scenarios may not be silently skipped.

## Strategy-bound release gate

Every evidence record includes the Editor and provider commits, upgrade-engine
fingerprint, strategy ID/hash, bundle inventory hash, project and ledger schema
versions, adapter and fixture IDs, required scenario IDs, commands, exit codes,
timestamps, before/after inventories, server/client identities, listener/login
and map assertions, retained-behavior assertions, rollback/recovery results,
and `skipped: false`.

Changing the upgrade engine, strategy, runtime components, provider lock,
capability or receipt schemas, build/launcher integration, fixture identity, or
required scenario set invalidates prior semantic evidence unless a machine
comparison proves the complete relevant fingerprint unchanged. Historical
Markdown stating that a workflow is unchanged is not sufficient.

A release requires two consecutive clean runs of the complete Preservation and
customized-host cutovers with zero required skips.

## Ordered work

1. Finish the direct read-only Core-copy inventory and record its exact desired
   behavior/state deltas against the current runtime direction.
2. Freeze the Preservation and sanitized Core-derived fixture identities and
   add the three first red tests: real plugin/core ABI failure, existing-project
   N-to-N+1, and exact Preservation zero-write detection.
3. Define the provider-owned current product runtime and decide every observed
   Core behavior as adopt, transform-to-data/plugin, or retire.
4. Publish the generated v4 bundle/schema and remove contradictory active
   v1-v3 runtime descriptions.
5. Migrate Editor project identity and introduce the target runtime ledger.
6. Implement Preservation and Core-derived input migrations through the staged
   transaction, then failure injection and current-to-next testing.
7. Make structured executable evidence the release gate and run both complete
   cutovers twice before retiring the pinned-core path.
8. After successful cutover, consider a genuinely thin, uniquely namespaced
   runtime SPI. It is a future maintainability improvement, not a reason to
   delay the complete current runtime.

## Decisions and evidence still required

- The owner must select which experimental, administrative, diagnostic, and
  custom gameplay features are current product requirements. File presence
  alone is not that decision.
- Custom asset and definition licensing/provenance must be verified before the
  provider packages them.
- The read-only Core audit establishes source, artifact, ABI, configuration,
  and schema evidence; it did not clean-build or launch the copy. The sealed
  fixture must prove source/artifact correspondence and runtime behavior.
- Current SQLite and MariaDB semantics, including DDL failure and retry, need
  disposable-engine tests rather than inference from patch source.
- Java/toolchain, dependency, and plugin-boundary modernization need staged
  current-to-next plans. They should proceed, but must not be combined without
  independent evidence merely to make the first runtime uplift appear more
  comprehensive.
- Map fidelity does not prove gameplay/runtime fidelity, and server startup
  does not prove matching-client behavior. Both require separate assertions.

## Explicit non-solutions

- Copy only the currently missing Core classes into the generic provider JAR.
- Add more class-marker strings and call that host compatibility.
- Keep the v3 receipt-existence Ant guard or add a similar client guard.
- Restore a broad duplicate-class shadow overlay.
- Treat success on pristine Preservation as proof of customized-host migration.
- Keep one runtime branch per historical server indefinitely.
- Require owners to recreate projects after every provider upgrade.
- Reuse Alpha.72, Alpha.88, or other older live-validation prose after changing
  the runtime strategy without fresh bound executable evidence.

## Evidence map

Primary Editor evidence:

- `tools/world-builder/src/com/openrsc/worldbuilder/WorldBuilderRuntimeCompatibility.java:96-160,362-455,581-739`
  installs project-frozen archives, requires v3 marker capabilities, patches
  the pinned build guard, and performs non-executable target verification.
- `tools/world-builder/src/com/openrsc/worldbuilder/WorldBuilderAdaptiveImporter.java:162-168,587-625`
  rejects an upgrade after a successful target transaction and delegates
  semantic success to the same runtime verifier.
- `tools/world-builder/src/com/openrsc/worldbuilder/WorldBuilderAdaptiveMutationProfile.java:55-94`
  binds upgrade planning to the original target discovery fingerprint and
  selected configuration.
- `tools/world-builder/src/com/openrsc/worldbuilder/WorldBuilderAdaptiveProjectLifecycle.java:1337-1344`
  verifies the frozen project-local runtime identity.
- The local 2026-09-04 pinned-core diagnostic supplied for this review records
  the current candidate's plugin compilation and launch failure; its relevant
  findings are incorporated above without adopting that untracked file.

Primary runtime-provider evidence:

- `server/conf/world-builder/installed-runtime-capability-v3.json:21-53,84-157`
  declares the incomplete payload, pinned-core policy, mixed capabilities, and
  retired receipt requirements.
- `server/conf/world-builder/managed-runtime-bundle.json:10-31` still describes
  the contradictory retired runtime as current.
- `server/build.xml:36-95,145-148` and `Client_Base/build.xml:33-85` rebuild the
  archives that the Editor assumes remain pinned.
- `tests/myworld/test-host-runtime-capability.py:13-59` uses hard-coded archives,
  marker strings, and the wrong declared-payload path as evidence.

Primary authorized Core-copy evidence:

- `README.md:1-15,36-204` describes the scale and main feature families of the
  customized game.
- `docs/myworld/info/world-builder-import-runtime-shadowing-incident-2026-09-03.md:23-230,447-505`
  records classpath shadowing, linkage failures, silent behavior rollback, and
  the size of the missing API surface.
- `docs/myworld/info/server-build-source-of-truth.md:3-36,88-185` records Ant
  authority, obsolete launch paths, dependency duplication, and Gradle drift.
- `server/src/com/openrsc/server/config/ServerConfiguration.java:432-451,969-985`
  and `server/src/com/openrsc/server/util/YMLReader.java:14-83` establish legacy
  configuration precedence.
- `server/src/com/openrsc/server/database/patches/PatchApplier.java:23-38`,
  `JDBCPatchApplier.java:74-109`, and `server/src/com/openrsc/server/Server.java:901-918`
  establish the unsafe startup patch boundary.
- `server/src/com/openrsc/server/model/container/Inventory.java:38-53,102-127`,
  `server/src/com/openrsc/server/model/entity/player/Player.java:6681-6682`, and
  `server/src/com/openrsc/server/net/rsc/ActionSender.java:289-292` are concrete
  expanded-inventory/API sentinels.
- `server/src/com/openrsc/server/content/world/WorldBuilderInstalledMapActivation.java:39-246`
  is current layered-package activation behavior to retain.
- `Client_Base/src/orsc/Config.java:21` and
  `Client_Base/src/orsc/PacketHandler.java:375` establish matching client
  version/capacity-packet behavior.

Primary Preservation evidence:

- `server/preservation.conf`,
  `server/src/com/openrsc/server/ServerConfiguration.java:409,648,762`, and
  `server/ant_launcher.sh:10` establish profile intent and hidden override
  behavior.
- `server/src/com/openrsc/server/Server.java:350` and
  `server/src/com/openrsc/server/database/patches/PatchApplier.java:23-38`
  establish the legacy startup database-migration risk.
- `Client_Base/src/orsc/Config.java:21` and
  `Client_Base/src/com/openrsc/client/entityhandling/EntityHandler.java:6946`
  establish server/client version and duplicated-definition coupling.
- `Client_Base/Cache/MD5.SUM` and `server/run_server.sh:4` establish legacy
  updater/launcher hazards that migration must not execute.

## Exit criteria

The upgrade roadblock is cleared when an existing authored project and both
recognized legacy fixtures reach the same current provider runtime, retain all
selected behavior and durable state, pass real server/client execution twice,
and can subsequently advance to a synthetic next runtime without project
recreation or another architecture branch.
