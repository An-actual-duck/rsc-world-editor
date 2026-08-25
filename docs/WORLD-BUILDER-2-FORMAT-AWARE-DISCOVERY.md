# World Builder 2 Format-Aware Discovery and Streamlined Launch

Status: **approved product direction; implementation in progress — Phase 2**

Product: `rsc-world-editor-v2` / **World Builder 2**

Repository ownership: World Editor tooling first; runtime-provider changes only
when the normalized content contract requires new client/server consumption

This document is the implementation guide for making ordinary server adoption
automatic. It records the intended user experience, discovery architecture,
content-completeness rules, safety boundaries, phased work, and acceptance
criteria. It is deliberately server-neutral and must not acquire behavior tied
to one named game or private-server repository.

## Product outcome

An ordinary user places World Builder in the root of their game/server,
launches it, and sees three primary actions:

1. **Create new project** — create an isolated empty world.
2. **Detect server map** — automatically find the adjacent server map and all
   supported custom content, then create an isolated project from copies.
3. **Continue working on selected project** — open the selected project,
   defaulting to the most recently used project.

The normal path must not ask the user to browse for source folders, definition
JSON, sprite archives, models, caches, or provider manifests. Provider packages
and individual asset locations are implementation details. Manual selection
remains available only under an **Advanced/Recovery** surface for installations
that are not placed in a server root or whose structure is genuinely
ambiguous.

The target server is always read-only during discovery, conversion, project
creation, editing, and saving. Changes reach it only through the existing
explicit previewed import transaction.

## Why this work is needed

The current release can consume explicit neutral provider packages and some
recognized layouts, but unusual server definitions and assets may still need a
maintainer-generated package or advanced file selection. That is too much
knowledge and responsibility for an ordinary user.

Custom content generally preserves familiar concepts—definition IDs,
placements, models, textures, animations, and sprites—but private servers vary
in file layout, JSON shape, overlay conventions, archive composition, and how
client and server evidence is divided. A broad recursive search alone cannot
distinguish authoritative content from obsolete copies, build output, examples,
or inactive configurations. The solution is format-aware discovery followed by
normalization, not server-name checks and not execution of target code.

One currently observed symptom is incomplete scenery after importing a real
server: terrain and most content load, but some scenery objects are absent.
This objective treats that as a mandatory completeness case. The implementation
must prove where every source placement and every dependency went instead of
assuming that successful launch means complete adoption.

## Design principles

- **One-click when evidence is unambiguous.** Detection, normalization,
  provider generation, and project creation are one user action with a concise
  confirmation summary.
- **Format-aware, project-neutral adapters.** Match structural signatures,
  schemas, and configuration semantics rather than repository names.
- **One normalized internal model.** All supported source layouts become the
  same canonical content graph before conversion or runtime preparation.
- **Complete effective composition.** Compose base records, additions,
  replacements, and removals according to the selected configuration before
  declaring a family complete.
- **Traceable outcomes.** Every accepted, replaced, removed, unresolved, or
  rejected source record has provenance and a deterministic diagnostic.
- **Fail soft for presentation, fail closed for meaning.** Missing optional
  visual bytes may use conspicuous placeholders and warnings. Unknown map
  geometry, placement semantics, IDs, collision meaning, or ambiguous authority
  must not be silently guessed or dropped.
- **Never execute target code.** Do not run or load target JARs, plugins,
  scripts, serialized objects, or arbitrary classes to discover definitions.
- **Read-only target and isolated projects.** Discovery hashes and copies
  bounded regular files; it never changes the selected server.
- **Deterministic and reusable.** The same source bytes and chosen profile yield
  the same normalized inventory, diagnostics, provider identity, and project
  content.

## Primary launcher experience

### Continue working on selected project

When a valid selected project exists, this is the emphasized/default action.
The launcher shows its friendly name and last-used time. A compact project
selector permits switching projects without exposing project directories.

Opening performs the existing full project validation and recovery checks. It
must not rescan or mutate a target unless the project explicitly requests a
compatibility refresh. A detected source change is presented as a separate
refresh decision, never silently merged into ongoing edits.

### Detect server map

This action begins at the installation's expected adjacent server root. It:

1. detects candidate configurations and active map sources;
2. identifies supported structural profiles;
3. inventories terrain, definitions, placements, and visual dependencies;
4. composes the effective content selected by the active configuration;
5. validates completeness and reports any ambiguity;
6. creates a canonical project-local content provider automatically; and
7. presents a short summary before creating the isolated project.

When exactly one complete source is found, no filesystem chooser appears. A
typical summary should say, for example:

> Server map found. 1 map, 5 definition families, 18,420 placements, and 4
> custom asset groups are ready. The server will remain unchanged.

If more than one complete active source is plausible, show friendly candidates
with useful configuration labels and evidence—not raw directory selection—and
require an explicit choice. If no adjacent server root exists, offer **Advanced:
choose another server location**.

### Create new project

This creates the existing standalone empty world. It requires a project name
and optional advanced location only. Server detection and provider controls do
not appear in this flow.

### Progressive disclosure

The primary window should not show provider paths, definition fingerprints,
cache paths, archive selectors, or schema terminology. Relevant surfaces are:

- **Compatibility details** — human summary, family counts, and warnings;
- **Technical details** — exact files, hashes, adapters, record provenance, and
  error codes;
- **Advanced/Recovery** — alternate server root, explicit provider package,
  guided component selection, cache reset, and diagnostic export.

Existing explicit `world-builder-provider/` packages remain a high-confidence
fast path and interoperability format. They cease to be a normal end-user
prerequisite.

## Discovery architecture

Discovery is a bounded pipeline with explicit intermediate evidence.

```text
installation root
        |
        v
server-root recognition
        |
        v
configuration and authority selection
        |
        v
format-profile probes and parsers
        |
        v
effective composition
        |
        v
canonical content graph + reconciliation report
        |
        v
project-local provider/content bundle
        |
        v
existing conversion, project creation, and runtime preparation
```

Probes are read-only and inexpensive. Parsers run only after structural
evidence selects an adapter. A failed probe does not reinterpret malformed
content as another format unless that other format has independent positive
evidence.

### Server-root recognition

Recognition begins with the World Builder installation parent and uses bounded
structural anchors such as configuration files, terrain archives/directories,
definition roots, location roots, and client asset roots. It must:

- ignore World Builder's own projects, cache, providers, release files, and
  runtime payload;
- reject symlink escapes, unsafe path aliases, duplicate/casefold candidates,
  unreadable special files, and paths outside the selected root;
- distinguish source trees, packaged distributions, and mixed layouts;
- avoid selecting inactive examples, backups, generated build directories, or
  duplicate caches without configuration evidence; and
- report every credible candidate when authority is ambiguous.

### Format profiles and adapters

A profile describes structural evidence and parser behavior, not a named
server. Profiles may cover:

- packed terrain archives and compatible layered packages;
- common OpenRSC definition and location layouts;
- base/custom/world definition composition;
- base location sets, project/world additions, and removal overlays;
- authentic and custom sprite archives;
- spritepacks and external PNG asset directories;
- model and texture archives/directories; and
- explicit neutral provider packages.

Each adapter declares:

- its positive structural signature;
- files and configuration keys that establish authority;
- supported schema and encoding variants;
- composition order and duplicate semantics;
- bounded resource limits;
- the canonical records it can produce;
- conditions that are visual-only warnings;
- conditions that block truthful conversion; and
- a versioned adapter identity included in evidence and cache keys.

Adapters may be added without changing the canonical project or runtime
contracts. A future data-only profile descriptor may express safe path and
schema variants, but it must never provide executable code.

## Canonical content graph

All adapters produce one immutable normalized graph before project creation:

```text
world
├── map identity, coordinate bounds, levels, sectors, and terrain
├── placements
│   ├── boundaries
│   ├── scenery
│   ├── NPCs
│   └── ground items
├── definitions
│   ├── floors/tiles
│   ├── walls/boundaries
│   ├── scenery
│   ├── NPCs
│   └── items
└── visual dependencies
    ├── textures
    ├── models
    ├── animations
    ├── authentic sprites
    ├── custom sprites and spritepacks
    └── external images
```

Every canonical record carries source provenance: adapter, configuration,
relative file, logical section, original record index/key, and source hash.
Stable canonical IDs preserve the target's real IDs; the discovery layer must
not randomly renumber server content. Renderer-indexed additions that require
local sequential allocation retain an explicit mapping bound to their portable
logical identity.

The graph is the authority used to generate the existing project-local content
bundle/provider evidence. Runtime code consumes only the normalized bounded
data and copied assets, never the original target layout.

## Family discovery requirements

### Terrain and map authority

- Locate the active map through configuration evidence.
- Distinguish packed, layered, and unsupported representations.
- Inventory every expected sector/level and detect aliases or gaps.
- Preserve the existing exact packed-to-layered parity and fail-closed rules.
- Do not infer an arbitrary map from a lone archive without sufficient
  configuration or explicit advanced selection.

### Placements

For boundaries, scenery, NPCs, and ground items:

- parse every authoritative base source;
- parse supported additions, replacements, and removals;
- apply the source profile's exact composition order;
- preserve coordinates, levels, directions, amounts, respawn/roam data,
  footprints, and stable placement identities;
- distinguish a deliberate removal from an accidentally omitted record; and
- reject unresolved collisions or ambiguous duplicate semantics.

### Definitions

Discover the effective floor, wall, scenery, NPC, and item catalogs. Support
recognized base/custom/world/patch arrangements and retain source precedence.
Every placement ID must resolve to one effective definition or a precise
blocking report. Unplaced definitions may still be retained for authoring when
their complete declarative meaning and required visuals are available.

### Visuals and models

Resolve dependencies from definitions rather than scanning assets and guessing
which IDs they represent. Copy only bounded, referenced assets plus explicitly
authorable catalog content. Validate archive structure, paths, sizes, hashes,
image dimensions, animation frames, model records, and role-specific identity.

Missing or unsafe optional visuals produce deterministic conspicuous
placeholders and warnings while preserving definition ID and name. A placeholder
must never masquerade as the correct appearance. Missing collision, dimensions,
animation structure required by the runtime, or other semantic definition data
may remain a blocker.

## Completeness and reconciliation

Successful parsing is not sufficient. Each family produces a reconciliation
ledger with these stages:

1. raw authoritative records found;
2. valid base records;
3. additions/replacements applied;
4. deliberate removals applied;
5. effective unique records;
6. records emitted to the normalized graph;
7. records written to the project package;
8. definitions resolved;
9. visual/model dependencies resolved or placeholdered; and
10. runtime-readable records after preparation.

Counts alone are supporting evidence; exact stable identities and semantics
must reconcile. For each difference, the report identifies whether it was an
expected replacement/removal, a safe presentation fallback, or a blocker.

No family may disappear because it was outside the initial spawn region, stored
in a second authoritative overlay, beyond the packaged definition count, or
referenced by an unfamiliar but supported asset role.

### Mandatory missing-scenery investigation

The first real-source fixture for this objective must preserve the currently
observed server state before attempting a fix. Its acceptance evidence must
include:

- source scenery base/addition/removal counts and exact identities;
- the effective scenery set after composition;
- emitted layered scenery identities and coordinates;
- every referenced scenery definition and its dimensions/collision fields;
- every referenced model and texture resolution;
- project save/reopen equality; and
- runtime materialization counts or exact placement evidence independent of
  the player's visible region.

The implementation must locate the stage at which the missing objects are lost
and add a regression fixture for that exact cause. Broader discovery is not
accepted merely because it happens to make the observed scene look complete.

## Automatic provider generation and refresh

After normalization, World Builder generates a content-addressed local provider
and project content bundle automatically. Users are not asked to name or locate
it.

The cache key binds at least:

- selected server-root identity;
- selected configuration/map identity;
- adapter/profile versions;
- hashes of every authoritative definition and placement input;
- hashes and inventories of copied visual/model assets; and
- normalized graph schema/version.

On a later **Detect server map** action:

- unchanged evidence reuses the verified cached provider;
- changed evidence creates a new provider identity atomically;
- an existing edited project is not silently rebased;
- the user receives a concise **server content changed** summary and may create
  a new project or enter a later explicit refresh/migration workflow; and
- corrupt, partial, or unknown cache content is preserved for diagnostics and
  replaced only through safe content-addressed publication.

Provider generation remains data-only and reproducible. Explicit producer
packages can supply stronger authority than automatic discovery, but their
target compatibility must still be verified.

## Diagnostics and error policy

Ordinary messages use product language:

- **Ready** — complete map and content found.
- **Ready with visual placeholders** — editing can start; N appearances need
  attention.
- **Choose detected configuration** — multiple truthful candidates exist.
- **Unsupported content format** — a specific family could not be interpreted.
- **Incomplete map evidence** — conversion would omit or guess world meaning.

The expanded technical report records adapter IDs, paths relative to the
selected root, hashes, schemas, record provenance, family ledgers, unresolved
dependencies, and actionable error codes. Reports must be portable and avoid
leaking absolute paths when exported for support.

Malformed or unknown optional custom visuals do not prevent launch. Malformed
placement data, unresolved placed definitions, unsupported coordinate meaning,
or ambiguous active authority remain blockers until a truthful adapter or
explicit repair workflow exists.

## Safety and trust boundary

Discovery and provider generation must preserve all existing safety contracts:

- no target writes, locks, database changes, builds, process launches, or JAR
  execution;
- no symlink/hardlink/traversal/absolute/backslash escape;
- no device, socket, special, or unsafe archive entries;
- bounded file counts, sizes, JSON depth, strings, arrays, images, frames,
  models, coordinates, IDs, and placements;
- deterministic handling of Unicode, casefolding, and portable paths;
- exact hashes and inventories for every copied input;
- atomic project/cache publication with cancellation and recovery;
- no bundling of credentials, logs, user data, plugins, scripts, databases, or
  unrelated server content; and
- no export back to the target except through the separately confirmed
  transactional import workflow.

## Work ownership

### World Editor repository

The Editor owns:

- root/configuration detection;
- format profiles and source parsers;
- canonical graph and reconciliation evidence;
- provider/content-bundle generation and cache management;
- launcher simplification and diagnostics;
- packed/layered conversion integration;
- project creation, refresh decisions, packaging, updater, and release tests.

### Runtime-provider repository

The runtime owns only changes required to consume a new normalized contract,
materialize definitions/assets/placements, or expose exact runtime evidence.
Discovery of arbitrary server layouts does not belong in the runtime. The
runtime must not inspect a selected target or become coupled to a private-server
repository.

Implementation note: Phase 0 began with the versioned
`discovery-reconciliation-v1` placement ledger. Packed conversion now measures
all declared placement sources, embedded scenery normalization, effective
composition, emitted/package counts, and exact family identity fingerprints.
The second Phase 0 increment adds `content-reconciliation-v1` to every
target-backed project with a captured content bundle. It records the exact
floor and terrain-wall IDs used by layered terrain, the exact definition IDs
used by all four placement families, catalog closure, definition roles, and
whole-file asset evidence. For placed scenery it also reads bounded
`GameObjectDef-array` model names and the native hash-indexed `models.orsc`
directory without executing target code. Project-specific model entries,
packaged-runtime model reuse, missing entries, unspecified models, and archive
formats that cannot yet be inspected are distinguished explicitly. Missing or
opaque presentation evidence remains a durable warning; an ID absent from the
captured definition catalog remains a blocker.

This narrows the observed missing-scenery problem to a concrete definition or
model dependency instead of treating archive capture as proof that every
object can render. Additional definition/archive formats and additional root
adapters remain pending.

Phase 1 now begins from the existing compiled layout-adapter registry rather
than introducing a second discovery framework. Descriptorless adapters return
a bounded format-profile probe contract (version 1) before parsing. Each probe
has a project-neutral, versioned profile identity and records its exact
structural anchors, whether each anchor is present, and whether it is required
for automatic selection or is only a positive signal. These results are
included in the existing strict discovery-report `checks`, participate in both
verification passes, and therefore detect anchor drift even when the coarse
supported/recognizable state does not change. Incomplete recognizable evidence
is reported rather than becoming an empty project, and multiple positive
profiles show their separate evidence instead of being silently resolved.

The first profiles describe the common packed OpenRSC source-tree anchors and
the signed-layered configuration root. Their neutral profile identities are
separate from legacy adapter IDs retained for compatibility. Further source
tree and packaged-distribution layouts can now add anchors and parsers without
changing the public discovery-report schema or the canonical project model.

The first source-path increment introduced exact normalization for the
equivalent client cache roots `Client_Base/Cache/video`, `client/Cache/video`,
and `Cache/video`. Authority requires exactly one populated root; multiple
complete or partial roots block as inactive/duplicate ambiguity. Discovery
inventories the selected source-relative paths and hashes without mutation.
During project creation, noncanonical inputs are copied again under the
compiled canonical fallback paths inside `source/original`, verified against
the selected bytes, and included in the immutable evidence ledger. The target
is never changed, and the project/runtime content bundle remains the single
canonical `client/Cache/video` model.

The second source-path increment applies the same contract to exact server
content layouts. Definition and placement evidence may now reside beneath
`server/conf/server/defs`, `server/data/definitions`, or `server/data/defs`;
packed server terrain may reside beneath `server/conf/server/data` or
`server/data`. Definition-root and terrain-root authority are selected
independently, and more than one populated candidate for either authority is a
hard ambiguity. The selected base placement suffix still comes only from the
validated `based_map_data` setting. On project creation, only the selected
required definitions, selected base placements, present overlays/removals, and
terrain are copied to canonical `server/conf/server/...` aliases inside the
isolated source snapshot. Every alias is size/hash verified and recorded in the
derived evidence; the original target paths and bytes remain intact.

The third source-path increment adds exact active-configuration profiles for
`server/myworld.conf`, root-level `myworld.conf`,
`conf/server/myworld.conf`, and `server/conf/server/myworld.conf`. Automatic
detection requires exactly one populated candidate. Multiple candidates block
with all paths reported; the lower-level advanced CLI may name one exact
compiled candidate explicitly. The selected file must still parse the same
bounded configuration keys and semantics, is inventoried under its original
path, and is copied read-only into the isolated project. Runtime preparation
uses the generated project-local configuration, so no canonical configuration
alias is written and the target configuration is never modified during
discovery or project creation.

The fourth source-path increment completes the reviewed flattened server
profiles. Definitions and placements may additionally reside under
`conf/server/defs` or `data/definitions`, while server terrain may reside under
`conf/server/data` or `data`. These roots compose with the existing client-cache
and configuration profiles, so a fully flattened distribution such as
root-level `myworld.conf`, `Cache/video`, and `conf/server/{defs,data}` can be
detected without browsing. Selection still requires exactly one populated
root for each authority. Merely finding a provider-compatible definition
folder is not enough: the complete definitions, selected placements, terrain,
client assets, and configuration must all validate before the layout is
accepted. Canonical aliases continue to exist only in the isolated project.

Phase 2 begins by replacing the former scenery count-only check and duplicate
XML readers with one bounded declarative scenery catalog parser. Discovery,
packed conversion, and content reconciliation now agree on the exact indexed
`GameObjectDef-array` contract. Every definition—not only the objects near the
initial player region—is parsed before project creation. The parser preserves
the runtime defaults for omitted fields and validates `name`, `objectModel`,
`type`, `width`, `height`, and `groundItemVar`; repeated known fields,
non-integer or overflowing numeric values, footprints outside the compiled
128-tile bound, unsafe XML features, empty catalogs, and oversized content are
actionable definition blockers. This prevents a semantically malformed
scenery catalog from passing discovery merely because its element count and
placement IDs look valid. Model-entry resolution and warnings remain in the
existing content reconciliation ledger; additional declarative scenery formats
must enter through later versioned adapters rather than being guessed.

The next Phase 2 increment closes the inspectable native scenery-model gap.
The model reconciler and project-content producer now share one bounded native
ORSC index reader. For an uncompressed, structurally complete model archive,
every concrete `objectModel` in the effective authoring catalog is checked by
its native filename hash and bounded OB3 structure. A definition whose model
is provably absent or malformed is
rewritten only in the isolated project content bundle to use one deterministic
existing model entry; its scenery ID and name are preserved, and the original
model name, replacement model, and affected ID are recorded in
`diagnostics/scenery-model-provider-warnings.json`. The desktop creation result
surfaces that warning before launch. If the archive contains no definition-
backed model that can truthfully serve as a visible placeholder, project
creation blocks before publication. Byte-identical repeated inputs produce the
same normalized content and report, while the selected server remains
unchanged. Runtime-generated models already proven by the pinned runtime are
preserved. Outer-compressed or otherwise opaque model archives remain explicit
unverified evidence for a later archive-format adapter; they are not guessed or
rewritten by this increment.

Floor and boundary XML now use one additional bounded semantic catalog parser
instead of count-only acceptance. It mirrors the pinned runtime defaults for
omitted fields while validating every indexed `TileDef` and `DoorDef`, including
signed integer syntax and range, bounded text, duplicate known fields, secure
XML parsing, nonempty catalogs, and the raw-byte definition domain. Discovery,
project catalog derivation, copied content verification, conversion, and reopen
therefore agree on the same floor and boundary identities and semantics. Custom
integer material values remain valid; this increment does not narrow legitimate
private-server colours or wall materials. Malformed records block during the
read-only discovery pass with the exact family and field, before a project is
published or a runtime is launched.

Floor and boundary material closure now interprets those validated values with
the pinned renderer's exact contract: negative integers remain packed colours,
`12345678` remains transparent, and other nonnegative integers are texture
indexes. The producer structurally validates the selected GZIP OSAR, preserves
every existing subspace and sprite payload, and makes its `textures` subspace a
contiguous numeric range through the greatest existing or referenced index.
Only absent indexes receive the deterministic visible 64-by-64 magenta/black
checkerboard. The isolated project records every inserted index and every
floor/boundary field that requires it in
`diagnostics/terrain-material-provider-warnings.json`, and the desktop creation
summary points the user to that report. Noncanonical names, structural archive
damage, and IDs beyond the compiled bounded domain block atomically rather than
being guessed. Repeated equivalent imports produce byte-identical project-local
archives and reports; the selected server archive is never rewritten.

Rich neutral NPC providers now receive record-level animation closure rather
than whole-archive trust. For every animation used by a selected extension NPC,
the Editor proves the exact runtime `category/name` lookup, the 15/18/27-frame
renderer shape implied by its combat flags, the raw custom-OSAR entry hash and
frame count, and a consecutive authentic-sprite range whose individual payload
hashes match. The provider may not omit a referenced animation or include an
unreferenced one. Archive inventories remain bounded, portable, collision-free,
and structurally parsed without executing target code. A stale target binding
still blocks as a capability mismatch; malformed or incomplete visual evidence
instead preserves the placement through the established clearly named NPC-0
placeholder and records `NPC_ANIMATION_PLACEHOLDER` with the exact failure in
`diagnostics/npc-definition-provider-warnings.json`. Successful closure and
repeat normalization are deterministic and leave the selected server bytes
unchanged. The Editor emits this closure as the bundle-v3
`world-builder-npc-animation-registry`; the pinned runtime verifies and
installs exact private-server animation IDs and semantics before project NPC
definitions load. This closes both the asset and runtime-binding sides of the
rich NPC provider contract without relying on target code.

## Implementation phases

### Phase 0 — Baseline inventory and acceptance fixtures

- Freeze synthetic fixtures for currently supported packed, layered, explicit
  provider, and standalone paths.
- Capture a sanitized structural fixture reproducing the observed missing
  scenery without committing real user/server data.
- Define the canonical graph, provenance records, family reconciliation ledger,
  diagnostic schema, and resource bounds.
- Record current discovery outcomes so improvements cannot hide regressions.

Exit gate: the current implementation can be measured family-by-family and the
missing-scenery loss stage is identified or precisely narrowed.

### Phase 1 — Adapter framework and authoritative root selection

- Separate probing, authority selection, parsing, composition, and
  normalization.
- Implement versioned project-neutral adapter interfaces.
- Cover common source-tree and packaged OpenRSC structural layouts.
- Detect configuration ambiguity and inactive/duplicate evidence.
- Preserve the explicit provider fast path.

Exit gate: one unambiguous supported server root produces a deterministic
profile selection with no manual paths; ambiguity lists truthful candidates.

### Phase 2 — Complete definitions, placements, and asset closure

- Normalize all five definition families and four placement families.
- Compose base/custom/world/patch/removal variants.
- Resolve texture, model, animation, sprite, spritepack, and external-image
  dependencies.
- Generate the complete project-local provider/content bundle automatically.
- Add safe placeholder and blocking classifications.

Exit gate: exact reconciliation passes for every family, including the
missing-scenery fixture, and repeat generation is byte-deterministic.

### Phase 3 — Automatic cache and refresh behavior

- Bind cache identity to complete authoritative evidence and adapter versions.
- Reuse unchanged providers and atomically regenerate changed providers.
- Detect stale target/provider relationships without blocking unrelated
  projects.
- Provide diagnostic export and advanced cache reset/recovery.

Exit gate: unchanged relaunch is fast and deterministic; source changes never
silently alter an edited project or reuse stale content.

Implementation note: the first Phase 3 increment upgrades the installation-local
provider catalog to schema v2. Cache authority now binds both the source path
identity and an exact evidence fingerprint composed from the canonical discovery
report plus bounded recognized definition/client-asset inventories. Exact
unchanged bytes produce a cache hit; changed evidence becomes a visible stale
association and falls back to regeneration from the current recognized layout.
Malformed catalogs and drifted provider directories are preserved and reported
as corrupt rather than selected or overwritten. Legacy path-only catalog records
remain readable only as stale recovery history. Project refresh/rebase remains a
separate later workflow; an existing edited project is never silently changed.

### Phase 4 — Three-action launcher

- Reduce the primary UI to the three approved actions.
- Default Continue to the selected/recent project.
- Make Detect Server Map one-click for adjacent unambiguous installations.
- Replace provider/file controls with concise progress and compatibility
  summaries.
- Move alternate roots and explicit/guided providers to Advanced/Recovery.
- Preserve safe cancellation, responsive progress, remembered state, clean
  shutdown, and no invisible Java processes.

Exit gate: a nontechnical user can install in a compatible server root and
reach an isolated editable project without navigating the filesystem or
understanding providers.

### Phase 5 — Compatibility matrix and release hardening

- Test multiple structural layouts and schema variants on Linux and Windows
  launch paths.
- Exercise ambiguity, malformed inputs, drift, cancellation, resource limits,
  cache corruption, placeholders, and unsupported families.
- Run real built client/server project launch, save, close, and reopen using
  generated provider content.
- Perform owner-native visual validation without screenshots.
- Rebuild and inspect fresh release candidates under the existing release gate.

Exit gate: the public workflow is one-click for supported layouts, safely
actionable for unsupported layouts, and complete for all source placement and
definition families.

## Required test matrix

At minimum, automated coverage must include:

- adjacent server-root detection with no chooser;
- no-server standalone behavior;
- source-tree and packaged-layout profiles;
- explicit provider priority;
- multiple active configurations and ambiguous definition/asset roots;
- packed and layered maps;
- base/addition/replacement/removal composition for all placement families;
- base/custom/world/patch definition composition for all five families;
- custom floor/wall textures, scenery models, NPC animations/sprites, and item
  sprites;
- missing optional visual placeholder behavior;
- unknown placed definition and semantic-data refusal;
- exact scenery reconciliation and runtime materialization;
- source changes, cache reuse, cache corruption, and deterministic regeneration;
- unsafe links, traversal, casefold collisions, archive modes, oversized
  content, malformed structured data, and target mutation checks;
- cancellation/failure atomicity and project preservation;
- launcher state, selected-project continuation, and clean process exit; and
- complete suite, runtime integration where required, packaging, updater, and
  candidate inspection.

Fixtures must be synthetic or sanitized and contain no real user workspace,
server data, credentials, or unrelated proprietary content.

## Definition of done

This objective is complete when:

- the primary launcher exposes only the three approved ordinary actions;
- placing World Builder in a supported server root requires no folder or
  provider selection;
- Detect Server Map discovers the active map and complete effective custom
  content through a versioned neutral adapter;
- all terrain, boundary, scenery, NPC, and ground-item records reconcile from
  source through saved/reopened runtime project;
- every referenced definition and semantic dependency is resolved or blocks
  with exact provenance;
- missing optional presentation assets use honest placeholders and warnings;
- the observed missing-scenery case has a diagnosed cause and permanent
  regression coverage;
- automatic providers are deterministic, cached, refreshed safely, and hidden
  from ordinary workflow;
- target bytes remain unchanged until an explicit import transaction;
- unsupported or ambiguous layouts receive actionable choices without silent
  guessing; and
- a fresh public candidate passes the full product, runtime, safety, updater,
  packaging, external inspection, and owner-native validation gates.

## Deferred extensions

This objective establishes discovery and normalization infrastructure that can
later support:

- data-only community profile descriptors;
- a compatibility/outlier repair workbench;
- explicit project refresh/rebase after server content changes;
- creator material and region-snapshot dependency remapping;
- additional archive and definition formats; and
- portable diagnostic bundles for adding a new adapter without sharing an
  entire server.

Those extensions must reuse the canonical graph and safety boundary rather
than creating parallel provider systems.
