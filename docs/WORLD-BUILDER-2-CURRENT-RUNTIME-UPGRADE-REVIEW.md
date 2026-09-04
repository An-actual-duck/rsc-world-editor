# World Builder 2 current-runtime upgrade review

## Document status

| Field | Value |
| --- | --- |
| Status | Active planning decision and evidence review |
| Captured | 2026-09-04 |
| Last reconciled | 2026-09-04, public Base/Advanced adaptability and light-customization intake |
| Product objective | Upgrade supported servers to one current managed runtime generation without losing selected game behavior or durable state |
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

World Builder maintains one current managed runtime generation and release
train. Every supported target upgrades off its historical loader, build, and
runtime onto that generation. The generation publishes a small, explicit set
of provider-owned variants composed from the same platform and compatible
module catalog. Preservation-like targets default to **Current Base**; the
owner's reviewed Core lineage migrates to **Current Advanced**. Variants are
current product compositions, not preserved legacy runtimes, and may not fork
the project format, target ledger, upgrade engine, map engine, or safety
contract.

"One current runtime" therefore means one maintained engine/API/protocol/schema
generation, not one mandatory gameplay composition or one byte-identical JAR
for every server. The public default must not silently acquire the owner's
advanced quests, balance, economy, definitions, assets, database state, client
interfaces, or operational settings. Conversely, the owner's advanced game
must not be downgraded to fit the public baseline.

Initially, a same-generation Advanced build variant is acceptable for behavior
below the present extension boundary. It must be produced from the same
provider revision, share the common platform contracts, declare its exact
module set, and carry a retirement path toward reusable platform hooks/modules.
It is not permission to create a per-target runtime fork.

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

### Public adaptability and expected inputs

Most external server owners are expected to begin from Preservation or a
near-Preservation tree with no layered-map upgrade and only light
customization. That is the primary public intake path, not an edge case. The
usual successful migration should:

1. recognize the legacy layout without requiring Git metadata or an installed
   World Builder receipt;
2. convert its packed map and placements into the canonical current layered
   package;
3. translate supported configuration and durable database state;
4. retain validated declarative content customizations;
5. install Current Base and its matching client; and
6. retire the legacy runtime, build, launcher, updater, and map loader.

The Core copy is the advanced upper-bound fixture and a source of reusable
platform improvements. It informs Current Advanced and the module/SPI design;
it does not define the public default. Adaptability comes from accepting and
classifying more historical inputs and supported customizations, not from
keeping their runtimes active.

Input adapter, output variant, and optional module are independent concepts:

- An **input adapter** recognizes and translates one historical layout/state.
  It is read-only during discovery, never chooses gameplay policy, and is not
  installed into the resulting server.
- A **variant** is a bounded first-party composition on the current platform.
  The initial variants are Current Base and Current Advanced.
- A **module** is maintained optional behavior or data with explicit current
  API requirements, dependencies, conflicts, migrations, client needs, and
  semantic tests.
- A **target customization** is evidence to classify. It becomes canonical
  data, a current module, a reviewed platform/variant change, a deliberate
  retirement, or a pre-mutation blocker. It never becomes an implicitly loaded
  old binary.

### Upgrade-first safety rules

- Upgrade is the normal successful result for every recognized older target
  whose complete input is supported and currently migratable. Recognition that
  produces `PORT_REQUIRED` or an unsafe/opaque blocker remains zero-write until
  the missing current port or evidence exists. Map-only operation is available
  only after the target ledger proves the current runtime is active.
- Legacy readers, fingerprint recognizers, and schema translators live only in
  the migration tool. The installed server/client do not grow permanent
  branches for every historical input.
- A successful migration has one active code runtime and one matching client.
  It does not leave old and new implementations competing on a classpath.
- Routine N-to-N+1 upgrades stay on the target ledger's selected current
  variant and advance automatically when the exact migration is trusted. A
  variant or module-set change is separately previewed and explicitly
  confirmed because it may change gameplay.
- Adding a historical source layout creates an input adapter, not a runtime
  variant. Adding portable behavior creates a module, not an old-runtime
  branch. Named variants remain few, provider-owned, and release-gated.
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

The checked-in rejected target upgrader copies `server/core.jar` and the
matching client from the project's frozen `working/runtime`, installs capability
evidence, and patches the target Ant file so `compile_core` is skipped. The
target's customized source remains visible but cannot produce the active binary.

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
plugins, clients, definitions, maps, assets, configuration, SQL migrations, and
tracked SQLite profile/seed databases. It has no prebuilt server, plugin,
client, or launcher artifacts, so a test cannot pass by substituting fake
archive markers.

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

The tracked SQLite databases are nonempty profile/seed artifacts, not a
representative populated live-player fixture. A generated populated SQLite
fixture and a separate MariaDB fixture are required before the Preservation
adapter can prove player-state migration.

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
Current Advanced and to reusable platform/module improvements, not another
permanently supported old host and not the mandatory public baseline. Every
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
gets current implementations and semantic tests in Advanced modules, a bounded
Advanced variant, or the common platform where it is broadly useful.
Unselected historical machinery does not become a compatibility obligation,
and Base users do not receive Advanced-only semantics.

#### Active Core boundaries that Current Advanced must replace coherently

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
upgrade should first establish the sealed current Advanced composition and
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
definition dependencies, edit history, declared capability/module requirements,
and project-schema migrations. It does not own an exact runtime payload,
variant selection, or Core identity. Opening an old project in a new application
must losslessly migrate this state without requiring project recreation.

The project's authoring runtime is a rebuildable cache selected by the current
application. It is not the authority for which production runtime a target
must install.

### 2. Current runtime generation and composition

The replacement application will select the current platform generation from
`rsc-world-editor-runtime`. A complete runtime identity is:

```text
(platformReleaseId, platformManifestHash, variantId, variantManifestHash,
 moduleSetHash, bundleInventoryHash)
```

`platformManifestHash` commits to the platform payload/API/protocol/schema
manifest. `moduleSetHash` commits to the canonical ordered list of module
manifest and payload-root hashes. `bundleInventoryHash` commits to the complete
resolved server/client/dependency/configuration/migration composition, including
the variant and module closure.

The platform owns the current engine/API/protocol/schema generation, canonical
map engine, extension interfaces, configuration/state contracts, launch model,
upgrade engine integration, and shared server/client identity rules.

Current Base is the conservative public composition for Preservation-like
servers. Current Advanced incorporates the owner's reviewed Core behavior.
Common correctness, security, map, protocol-negotiation, configuration, and
extension improvements belong in the shared platform. Owner-specific gameplay,
UI, assets, commands, and schema additions belong in declared Advanced modules
or, temporarily, the bounded Advanced build variant.

Every installable composition is generated as one immutable,
content-addressed, closed bundle. It contains the exact server, client,
dependencies, modules, launcher, profiles, migrations, schemas, and optional
source needed for that composition. It never overlays classes from the old
target.

The Advanced composition should be created by reviewing and adopting the
wanted behavior from the pinned Core copy into the independent provider with
explicit provenance; the Git histories remain independent. Desired behavior is
ported or transformed into current data/modules. It is not left hidden in an
old target tree.

### 3. Current module contract

Modules provide adaptability inside the current generation. Each module has a
self-contained manifest declaring:

- module ID, version, kind, platform API range, provided capabilities,
  requirements, conflicts, and deterministic order;
- exact code/data/client paths, sizes, hashes, provenance, and ownership;
- explicit entry points or data roles;
- namespaced configuration, defaults, state/database migrations, and rollback;
- any matching client module and handshake requirement; and
- semantic tests plus portability or target-derived status.

Module kinds remain explicit: declarative data/content, code plugin, or
coordinated server/client feature. A temporary below-SPI build variant remains a
variant with its own manifest and retirement path; it never enters module
resolution. Modules may not shadow or replace platform classes. A behavior
requiring core modification first adds a reviewed platform hook/capability.
Unknown target plugins are not dynamically imported.

Provider-published modules may contain only reviewed redistributable code/data.
Target-derived declarative modules are built locally from the target/server
owner's validated inputs, remain bound to that target/project lineage, and never
enter the public archive or provider repository. Private maps, assets,
definitions, credentials, and player state remain outside every public
composition.

Provider modules are curated and signature/hash verified. A locally prepared
module requires explicit local trust and exact provenance; it is never fetched
or enabled merely because a historical target names it. Module resolution is
complete before mutation and refuses undeclared dependencies, cycles,
conflicts, duplicate IDs/paths/classes, or a missing matching client part.

The current plugin model scans one monolithic `plugins.jar` and exposes concrete
server internals. The intended SPI replaces that with manifests, verified entry
points, dependency resolution, preflight linkage, and versioned services such
as registries, lifecycle hooks, events, and a bounded runtime-extension context.
Data modules similarly declare merge order, ID namespaces, overrides,
references, collision behavior, and client limits.

### 4. Target runtime ledger

A stable target installation identity owns a separate runtime ledger. The
ledger records the platform release, selected variant, exact module set,
component/composition hashes, input adapter and predecessor, configuration and
state-migration IDs, server/client build identities, active launcher pointer,
active map package, verification evidence, and transaction receipts.

Map-import history remains project-scoped. Runtime-upgrade history is
target-scoped. A verified current ledger becomes the trusted before-state for
the next current-to-next upgrade.

## Current bundle and migration contract

The provider must generate a versioned manifest from staged release artifacts.
At minimum it records:

- strategy ID and strategy-manifest SHA-256;
- provider commit, platform release/API/protocol/schema versions and manifest
  hash, bundle ID/inventory hash, variant ID/manifest hash, and module-set hash;
- a closed inventory of every server, client, plugin, dependency, launcher,
  profile, schema, migration, and optional source artifact;
- path, type, mode, size, SHA-256, destination, ownership, replacement policy,
  and rollback policy for every component;
- one complete payload-inventory hash;
- compatible Editor input-adapter IDs and required runtime-side migration
  capability IDs;
- the positive Base semantic contract and sentinels, plus a negative inventory
  proving Advanced-only modules/content are absent from the public default;
- module dependencies, conflicts, ordering, configuration namespaces, state
  ownership, server/client pairing, and extension-API requirements;
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

| Tier | Subtype | Recognized input | Current destination |
| --- | --- | --- | --- |
| T0 | Sealed baseline | Exact sealed Preservation footprint | Current Base; automatic map/state upgrade |
| T1 | Expected local state | Baseline plus reproducibly recognized generated state, baseline-equivalent local override, known database schema/state, or known client settings | Current Base; translate effective settings/state and discard only proven-reconstructible runtime files |
| T2A | Typed configuration | Supported configuration-only customization | Current Base with typed current configuration |
| T2B | Portable declarative data | Provider-declared portable data/content or a structurally supported legacy map with exact server/client authority and lossless conversion | Current Base plus canonical migrated data/module/map |
| T3 | Historical extension | Recognized extension/plugin source with a stable semantic fingerprint and supported historical extension boundary | Current Base plus its exact declared module when a registered port exists; otherwise zero-write `PORT_REQUIRED` until that port is prepared |
| T4 | Coupled port | Recognized but unsupported core/client/build/protocol/definition/asset/map coupling, or plugin behavior coupled to concrete host internals | Provider-side port to a current platform hook/module/variant before target mutation |
| T4 | Registered Advanced | Exact reviewed Core lineage | Current Advanced plus its reviewed module set |
| T5 | Unsafe/opaque | Unknown, contradictory, malformed, opaque, binary-only, or unsafe customization | Zero-write refusal with an actionable evidence report |
| Current | Exact current | Exact current ledger and artifacts | Runtime no-op; permit map-only import |
| Managed N | Trusted predecessor | Trusted earlier ledger | Advance within its selected variant to current N+1 |

Classification does not require Git metadata. It uses an Editor-owned
role-aware fingerprint manifest backed by provider runtime identities, and the
highest-risk active delta determines the tier. A generated path name is not
proof that its contents are disposable: an executable/archive remains T1 only
when it matches a known reproducible derivation or declared discardable role.
Unexpected executable bytes elevate the target; a binary-only unknown plugin is
T5.

An ignored `local.conf` remains T1 only when historical precedence resolves to
baseline-equivalent typed settings or known external secret references. A
supported semantic value change is T2A; an unknown key, type, precedence, or
secret-handling rule blocks. A known populated database can remain T1 only when
its schema/patch state is recognized and its invariants validate; schema drift
or a partial patch elevates the classification.

Comparison is semantic where bytes are not authoritative:

- legacy configuration is resolved with its historical precedence and aliases;
- databases use integrity, schema, patch-ledger, and table-state evidence;
- JSON/XML use schema-aware, order-preserving comparison;
- archives use entry inventories and content hashes; and
- source, build, client, protocol, definitions, assets, and dependencies use
  exact identities plus declared ownership.

Definitions, new IDs, sprites, models, caches, protocol, and client limits are
client-coupled even if stored in declarative files. They are not automatically
treated as harmless T2 data. They may become a coordinated current data/feature
module only when IDs, references, assets, cache/client closure, limits, and
merge/collision behavior all validate. A changed legacy landscape may remain
T2B when its format is recognized, the selected server/client authority agrees,
required definitions are closed, and deterministic conversion proves semantic/
reverse parity. A coherently identified but unsupported coupled encoding or
definition/client contract is T4 and needs a port. Opaque or malformed encoding,
contradictory authority, or meaning that cannot be established is T5.

For T3 plugin-only targets, a separate explicit extension-preparation workflow
may port or rebuild reviewed source against the current SPI in an isolated,
non-target environment and produce a sealed local module manifest. The observed
input remains T3 whether or not that current port already exists; port readiness
changes the disposition from `PORT_REQUIRED` to installable module rather than
rewriting discovery history. Upgrade Target Runtime installs only the verified
current module; it never executes target build scripts, annotation processors,
or unknown binaries during discovery/cutover. Legacy plugins coupled to concrete
host internals are T4 and require an actual semantic port, not successful
compilation alone.

Refusal is a discovery result, not permanent compatibility policy. A desired
unknown target is researched once, its behavior is classified, and a new
bounded input migration or provider feature is added before cutover. Import
Map Changes never ports or installs executable code dynamically. Successful
migration leaves that target on the same current platform generation as every
other supported input, with its explicit current variant/module composition. A
variant change requires a separately previewed semantic diff and consent.

### Adapter and discovery extensibility

An input adapter is an Editor-owned, versioned recognition and translation
contract. It declares bounded structural probes, configuration semantics,
source/data roles, baseline fingerprints, map conversion, supported state
migrations, customization classifiers, and zero-write diagnostics. Target
descriptors and target files are evidence only; they cannot supply executable
adapter code or authorize writes.

Adding another historical server family should require an adapter plus sealed
fixtures, not changes scattered through the importer and not a new active
runtime. Community-submitted adapter work can be reviewed and adopted in the
Editor; any new runtime-side migration hook, identity, or fixture is coordinated
through the provider. A sanitized diagnostic export gives maintainers the exact
unknown paths/hashes/roles and semantic blockers without credentials, player
data, maps, or proprietary assets.

The normal discovery summary separates source recognition from destination
resolution and reports:

- detected historical input adapter/runtime;
- recommended current variant and the reason;
- required and optional current modules;
- every meaningful delta as `preserved-data`, `mapped-to-platform`,
  `mapped-to-module`, `retired`, or `blocker`;
- features/data that will be added, changed, or removed;
- matching-client and database impact; and
- the exact target-ledger transition.

For example: "This Preservation-like server will upgrade to Current Base;
three content customizations will be retained and one plugin needs review."
Technical files/hashes remain available under details, not as the primary user
experience.

## Staged transaction

1. Require the target to be offline and establish its stable installation
   identity.
2. Inventory tracked, ignored, and generated files without executing target
   scripts; detect effective configuration and secrets without copying them
   into project or release artifacts.
3. Select the input adapter, customization tier, destination current variant,
   and resolved module set. Classify every relevant component as `replace`,
   `transform`, `mapped-to-module`, `preserve-state`, `retire`, `verify-only`,
   or `unsupported`.
4. Preview the exact semantic, client, database, and ledger transition. Require
   separate consent for any variant/module-set change.
5. Back up the exact reviewed before-state and produce an external-backup
   warning and confirmation.
6. Stage the resolved current bundle and copied durable state outside the live
   target. Convert the legacy packed terrain/placements to the canonical
   current layered package when the input has no map upgrade.
7. Run configuration and database migrations against staged copies with
   independent checkpoints and invariants.
8. Verify and stage the exact provider-built, release-attested composition and
   every declared current module. Arbitrary target source or binary plugins do
   not enter this bundle. Provider CI/release gates clean-build it against the
   exact platform API and prove reproducibility; an explicitly prepared local
   extension is compiled and sealed before transaction planning. If active
   source ships in the composition, those gates also prove that its normal build
   reproduces the installed artifacts.
9. Launch the staged server through the supported current launcher on private
   loopback ports and disposable database/state paths.
10. Connect the exact matching current client, verify the platform/variant/
    module-set handshake,
    authenticate a synthetic test account, load the map, and exercise selected
    content/gameplay sentinels.
11. Revalidate the target, apply the reviewed state and content changes, and
    atomically switch the managed-runtime pointer.
12. Repeat normal launch/login/map verification against the installed target.
13. Write the active ledger and successful receipt last. On any earlier
   failure, restore the exact before-state or leave a precise recovery record.

The Editor now contains a package-private synthetic transaction foundation for
the safe structural subset of this sequence. Against sealed synthetic
installable fixtures only, it implements semantic preview for T0, T1, T2A,
T2B, and managed-N; binds the exact provider composition, project capability,
input adapter, classification, predecessor inventory, staged artifacts, and
activation ledger; requires an exact plan-specific confirmation identity; and
proves external side-by-side staging, exact backups, activation-last,
post-install verification, automatic rollback, interrupted recovery evidence,
and exact recovery. Preview and all refused classifications write nothing to
the target or transaction directory. Map-only import remains unavailable until
the resulting current ledger and installed provider artifact set both
revalidate. Rollback and recovery refuse destructive cleanup unless the ledger
is byte-exact planned activation or preimage and the release is the exact
transaction-owned artifact/activation tree. Map-import eligibility additionally
requires the ledger launcher/build/map identities and strict activation marker
to match the selected composition, project, adapter, semantic plan binding, and
transaction receipt; all drift paths are zero-write refusals.

This is executor architecture, not production upgrade support. It has no CLI or
desktop apply route and refuses non-synthetic adapters. Current Base is an
honest non-installable artifact candidate: its source build produces a bounded
server/client pair, but its runtime profile records the remaining configuration,
state migration, gameplay execution, and startup-handshake blockers. Source-only
catalog inspection does not fabricate those generated artifacts or activation
authority. The synthetic overlay has no production server/client executable.
Steps 6 through 10 still require the blocked production implementations and
their release evidence before any real target can be upgraded.

## Fixture and executable matrix

### Mandatory baselines

| Fixture | Input delta and required result |
| --- | --- |
| F0 sealed Preservation | No delta; T0 upgrades to Current Base, including canonical map conversion, with no legacy executable active |
| F1 first-run/state | Generated artifacts, effective local configuration/client state, and populated synthetic SQLite; T1 migrates intent/state and discards generated runtime files |
| F2 configuration | Supported rates, text, toggles, renamed keys, and legacy typo aliases; T2A translates to typed Base configuration |
| F3 portable content/map | Existing-ID locations, declared portable data, and one supported packed-map edit with matching server/client authority; T2B becomes canonical current data/map or refuses precisely |
| F4a maintained extension | Known plugin semantic delta with a registered current port; T3 resolves Base plus its module and proves SPI isolation |
| F4b recognized unported extension | Same supported historical extension boundary without a registered current port; T3 returns actionable zero-write `PORT_REQUIRED` |
| F5 client-coupled fork | One recognized core/client/definition/asset/build delta plus a coherently identified unsupported map encoding/contract; T4 blocks until a current platform/module/variant/adapter port exists |
| F6 unknown/partial | Malformed configuration, partial database patch, opaque map, contradictory map authority/identity, or binary-only plugin; T5 performs zero writes |
| F7 Core lineage | Sanitized advanced topology and synthetic state; upgrades to Current Advanced and preserves selected sentinels without leaking private data/assets |
| F8 managed N | Current Base N and Advanced N, with modules; each advances to its own current N+1 without project recreation or legacy runtime residue |

F1 includes accounts, stats, inventory, bank, item metadata, quests,
friends/ignores, recovery, former names, ordering edge cases, and an equivalent
MariaDB fixture with a consistent snapshot/restore path. One-axis subfixtures
cover plugin/API, definitions, placements, maps, sprites/assets,
dependency/classpath, launcher, and schema boundaries.

F1/F2 jointly prove Preservation configuration precedence with three explicit
cases: absent `local.conf`; a launcher-generated, baseline-equivalent
`local.conf` that remains T1; and a conflicting supported effective value that
becomes T2A. Duplicate-key fixtures prove the legacy reader's first-value-wins
rule so reading only `preservation.conf` cannot pass.

### Required scenarios

- old project opens in the newer application without recreation;
- Preservation with no prior map support converts and opens on Current Base;
- runtime N upgrades to current within Base and Advanced, followed by each
  current composition advancing to a synthetic N+1;
- authored project data remains exact across both application upgrades;
- every declared module resolves, compiles/loads against the current SPI, and
  contains no duplicate platform class identity;
- Base passes the positive canonical public gameplay, login, map/collision,
  configuration, and state-migration sentinels selected from the
  Preservation-derived fixture;
- Base contains no Advanced-only gameplay, client UI, assets, feature flags, or
  schema effects;
- module dependency, conflict, ordering, data-ID collision, add/update/remove,
  and server/client pairing behavior is deterministic and reversible;
- actual normal build and launcher paths cannot restore the old runtime;
- matching server/client cold start, platform/variant/module identity handshake,
  login, and first scene load succeed;
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
fingerprint, strategy-manifest hash, platform release/manifest hash, variant ID
and manifest hash, ordered module-set hash, bundle-inventory hash, project and
predecessor/current-ledger schema hashes, adapter manifest and fixture hashes,
required-scenario-set hash, commands, exit codes, timestamps, before/after
inventories, server/client composition identities, listener/login and map
assertions, retained-behavior assertions, rollback/recovery results, and
`skipped: false`.

The semantic evidence key is:

```text
attestation-v1(
  editorCommit,
  providerCommit,
  upgradeEngineHash,
  strategyManifestHash,
  platformReleaseId,
  platformManifestHash,
  variantId,
  variantManifestHash,
  moduleSetHash,
  bundleInventoryHash,
  inputAdapterManifestHash,
  projectSchemaHash,
  predecessorLedgerSchemaHash,
  targetLedgerSchemaHash,
  fixtureHash,
  requiredScenarioSetHash
)
```

The canonical attestation hash is the evidence key. Each nested manifest hash
transitively commits to its schema and closed payload inventory; friendly IDs
alone are never release authority.

Changing the upgrade engine, strategy, runtime components, provider lock,
capability or receipt schemas, build/launcher integration, fixture identity, or
required scenario set invalidates prior semantic evidence unless a machine
comparison proves the complete relevant fingerprint unchanged. Historical
Markdown stating that a workflow is unchanged is not sufficient.

A shared-platform change invalidates all variants. A variant/module change
invalidates every matrix row using it. An adapter change invalidates its input
family. Project portability is retained unless a project explicitly declares a
current module requirement.

A release requires two consecutive clean runs of the complete Base,
light-customization, maintained-extension, Advanced, module-migration, and
pre-mutation-refusal matrix with zero required skips.

## Ordered work

1. Freeze the current-generation, variant, module, adapter, composition
   identity, customization-tier, and target-ledger contracts before another
   archive strategy is implemented.
2. Freeze the Preservation and sanitized Core-derived fixture identities and
   add the first red tests: exact Preservation-to-Base map uplift, light
   configuration/data classification, real plugin/core ABI failure,
   existing-project N-to-N+1, and T5 zero-write refusal.
3. Build Current Base as the conservative public runtime with the canonical map
   engine, typed configuration/state migrations, matching client, explicit
   plugin/module boundary, and no Advanced-only behavior.
4. Complete the Core behavior-disposition register. Move broadly useful hooks
   into the platform and selected owner behavior into Advanced modules or the
   bounded same-generation Advanced variant.
5. Publish generated Base/Advanced bundle and module schemas, then remove
   contradictory active v1-v3 runtime descriptions.
6. Migrate Editor project identity, add destination resolution and the
   target-runtime ledger, and make project runtime caches replaceable.
7. Implement Preservation/light-customization and Core-derived adapters through
   the same staged transaction, then module resolution, failure injection, and
   Base/Advanced current-to-next testing.
8. Make structured composition-bound executable evidence the release gate and
   run the complete matrix twice before retiring the pinned-core path.
9. Continue shrinking the bounded Advanced build variant by extracting
   genuinely thin, uniquely namespaced modules onto the current SPI. Do not
   delay the first safe current-generation cutover for a perfect plugin model.

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
- Make Current Advanced or the owner's content/gameplay the mandatory public
  default.
- Create one current runtime variant for every historical adapter or target.
- Load an unknown target plugin/source/binary directly because its old server
  accepted it.
- Treat every JSON/XML change as portable when definitions, IDs, assets, maps,
  protocol, or client limits may be coupled.
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
  factual findings are incorporated above without adopting that untracked file.
  Its target-source integration/build recommendation belongs to the earlier
  preservation prototype and is superseded by the provider-built Base/Advanced/
  module architecture and release gate in this document.

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
- `server/src/com/openrsc/server/plugins/io/PluginJarLoader.java:35-50` and
  `server/src/com/openrsc/server/plugins/handler/PluginHandler.java:52-125`
  show the current monolithic class-scanning plugin loader and coupling to
  concrete host internals.
- `scripts/audit-server-build.py:235-244` requires authentic and owner-specific
  plugin classes in one production plugin artifact, so that artifact is not a
  neutral public module boundary.
- `server/src/com/openrsc/server/content/worldedit/AdaptiveWorldBuilderProjectContentBundle.java:960-1004`
  already demonstrates explicit data roles and feature-to-content bindings that
  can inform the current data-module contract.

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

The upgrade roadblock is cleared when existing authored projects, common
Preservation-like/lightly customized targets, and the advanced Core lineage all
reach the same current platform generation in their explicit Base or Advanced
compositions; retain selected behavior and durable state; pass real
server/client execution twice; and subsequently advance within those
compositions to a synthetic next generation without project recreation,
legacy-runtime branches, or per-target architecture work.
