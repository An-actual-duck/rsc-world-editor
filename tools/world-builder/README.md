# World Builder Tools

This module owns the standalone, content-neutral World Builder 2 project
lifecycle. It discovers a compatible server map or a true no-server location,
creates an isolated UUID project, preserves immutable source lineage, manages
the mutable layered working package, and supervises the isolated runtime.

World Builder 2 is not tied to one game world. A release contains definitions
and non-world runtime assets, but no terrain, placements, active layered
package, project, export, backup, or receipt.

## Product boundary and current status

The packed-map World Editor is frozen at `v1.1.0`. Its identity, workspaces,
archives, update channel, and legacy commands remain separate from World
Builder 2 and `rsc-world-editor-v2`.

Adaptive discovery, lossless packed conversion, UUID projects, isolated
working copies, save/reopen, a generic pinned runtime capability, content-
neutral packaging, deterministic export, bounded target import, verified
rollback/recovery, retained historical reversal internals, and durable
application updates are implemented. Native
adaptive launch now prepares independent server/client runtime copies beneath
each UUID project and supervises the pinned generic runtime against only that
project's layered working package. Owner-run target-backed and standalone
visual/edit/save/reopen validation passed for accepted releases; every later
candidate still requires fresh version-bound native and final release
validation.

The project-local generic runtime in that paragraph is the current authoring
runtime, not the accepted destination for every target server. The later
development-only pinned-core target-upgrade candidate is rejected. The planned
replacement resolves Preservation-like servers to Current Base, the owner's
reviewed lineage to Current Advanced, and portable custom behavior to explicit
current modules on one platform generation. Provider-owned platform, Base,
Advanced, bundle, module, and resolved-composition contracts now exist, along
with Editor-owned input-adapter, project-capability, target-ledger, and
read-only classification contracts. The provider bundles remain
`foundation-contract-only` and `installable: false`; migrations, the
transactional installer, and the executable release gate still must be built.
Until then, `upgrade-target-runtime` must not be presented as a supported public
migration. See
[`docs/WORLD-BUILDER-2-CURRENT-RUNTIME-UPGRADE-REVIEW.md`](../../docs/WORLD-BUILDER-2-CURRENT-RUNTIME-UPGRADE-REVIEW.md).

Build the standalone tools with:

```bash
./scripts/build-tools.sh
```

The examples below use:

```text
output/world-builder-tools/world-builder-tools.jar
```

Packaged launchers use the identical JAR under
`builder-runtime/launcher/world-builder-tools.jar`.

For tool development, `create-project --development-terrain-seed` is a strict
standalone-only option used by
`scripts/world-builder-tool-test-environment.sh`. It generates one complete
visible-floor sector centered around `120,648`; it cannot be combined with a
target-backed discovery report. Ordinary standalone creation retains its
canonical one-sector structural void with a centered 3-by-3 visibility patch.
The development project retains that canonical immutable baseline and applies
the complete visible sector only to its initial mutable working draft. The
generator is invoked locally and no generated sector or sandbox is stored in
source control or a release archive.

## Desktop project launcher

The packaged Linux and Windows launchers use `desktop-launch`. Development
`main` opens a project screen with five primary actions: **Detect Server Map**,
**Continue Working on Selected Project**, **Upgrade Target Runtime**, **Import
Map Changes**, and **Restore Project Backup**. The upgrade button still invokes
the rejected development candidate and must not be treated as a supported
public migration. **New Empty World** is offered as a labelled outcome when no
server is recognized; **Select Another Supported Source** and project-folder
browsing live under **Advanced / Recovery**. Cancelling any chooser or
confirmation returns without creating a project or starting child processes.

While an editor session is running, the project launcher remains open and
refuses to exit. Close the editor normally so the supervisor can stop the
private server, save the project, remove process metadata, and complete final
validation before the launcher itself is closed.

The source chooser accepts directories that satisfy a compiled packed or
layered adapter; it does not infer a format from an arbitrary map file. The
existing-project chooser is deliberately limited to registered UUID projects
inside the current installation. The screen displays the exact installation,
project UUID/path, origin, attachment state, and discovery compatibility before
the corresponding create/open action.

For headless automation and detailed reports, the terminal command remains
available unchanged:

```bash
java -jar output/world-builder-tools/world-builder-tools.jar launch-adaptive \
  --installation-root '/path/to/World Builder 2' \
  --runtime-root '/path/to/World Builder 2/builder-runtime' \
  --target-root /path/to/server-root-or-ordinary-parent \
  --port 43615
```

On first use, this command performs bounded read-only discovery. If the path is
a supported server root, it offers to adopt a complete layered map or copy and
losslessly convert a complete packed map. If no server evidence exists, it
offers a canonical standalone empty project at layer `0`, coordinate `120,648`,
centered on a generated 3-by-3 visibility seed with floor color and overlay `0`.
The standalone authoring catalog is generated during project staging from the
exact verified definition files in that packaged runtime. It contains only
numeric IDs: XML array positions for tiles, boundaries, and scenery; effective
base-plus-custom array positions for NPCs; and sorted explicit base/custom item
IDs. It does not bundle a map, placement, name, or creator content. The catalog
bytes and runtime bytes are independently inventoried and bound into immutable
project evidence.
Recognizable but incomplete, malformed, unsupported, changing, or ambiguous
server evidence is blocked; it is never treated as an empty world.

The CLI discovery report is printed before the exact `CREATE` confirmation.
The GUI presents the equivalent summary and explicit confirmation. Both create
one isolated UUID project without changing the target. Later desktop launches
offer the validated project list and start the client/server supervisor after
the user selects a project. Optional CLI
`--configuration-role <role>` resolves an explicitly declared multi-role
server, and `--display-name <name>` names the first project.

The native-process step verifies the complete project and project-local runtime,
holds the project run lock, starts the isolated server and client with the
bundled Java, waits for bound readiness, requests orderly shutdown, and records
a save only after a clean completion. It never resolves or mutates the target.

## Read-only discovery

Advanced users can inspect a target without creating state:

```bash
java -jar output/world-builder-tools/world-builder-tools.jar discover-adaptive \
  --target-root /path/to/server-root \
  > discovery-report.json
```

Add `--configuration-role <role>` only when a descriptor truthfully declares
more than one active role. The command emits a strict
`world-builder-discovery-report` v2 document. Exit `0` means compatible or a
clearly labelled no-server classification; exit `3` means blocked.

### Current-runtime target classification foundation

Developers can validate an Editor-owned migration contract and classify a
sealed target against a provider-resolved current composition:

```bash
java -jar output/world-builder-tools/world-builder-tools.jar \
  validate-current-runtime-contract --kind input-adapter \
  --document /path/to/input-adapter.json

java -jar output/world-builder-tools/world-builder-tools.jar \
  classify-current-target --target-root /path/to/offline-target \
  --provider-catalog-root .runtime-provider/current-platform \
  --composition-identity /path/to/resolved-composition.json \
  --input-adapter /path/to/input-adapter.json \
  --project-capability /path/to/project-capability.json
```

Classification is non-executing and read-only. It binds the provider platform,
schema set, variant, module set, complete bundle inventory, bundle spec, and
input-adapter boundary before inspecting bounded target evidence. Outcomes are
`CURRENT`, `UPGRADE_READY`, `PORT_REQUIRED`, `BLOCKED_UNSAFE`, or
`NOT_INSTALLABLE`. The last outcome is mandatory for any provider composition
that has not reached installable release authority. Foundation contracts and
artifact candidates may be inspected, but neither can authorize activation or
map import. A resolved composition still requires every declared artifact; a
source-only candidate catalog is metadata evidence, not a substitute payload.
`UPGRADE_READY` is classification evidence, not an installer; this command
never changes a target.

A project's required capability IDs are satisfied by exactly the union of the
platform's `mapRuntimeCapabilities` and the selected variant's
`requiredCapabilities`. Module-provided capabilities do not silently widen
that project contract; module selection is separately bound by
`requiredModuleIds`. The resolver also requires exact agreement among
composition, bundle, and variant installability, and independently reconstructs
the complete dependency/conflict/load-order module closure.

Input adapters are separate, non-installable migration evidence. A new
historical layout should add a reviewed bounded adapter, while portable behavior
should map to a provider-owned current module. Unknown code, unregistered module
ports, mixed ledgers, unsafe paths, and unrecognized bytes fail before mutation.
The adapter ID must be explicitly admitted by the selected variant's
`inputAdapterRecommendations`; a recommended variant alone is insufficient.
The repository currently ships only synthetic recognition fixtures, not a
production Preservation-family fingerprint set, so do not point this command at
a real server and infer upgrade support from fixture results.

The next transaction layer is also deliberately synthetic-only and has no CLI
or desktop route. Its package-private regression harness proves zero-write
semantic preview for T0/T1/T2A/T2B and managed-N, an exact composition/project/
adapter/classification-bound plan, external same-filesystem staging, exact
preimage backup inventory, activation-last ledger publication, installed
artifact verification, and automatic rollback/recovery evidence. It rejects
`PORT_REQUIRED`, T5, non-installable provider compositions, non-synthetic
adapters, changed confirmation identities, and offline uncertainty before
target writes. Rollback and recovery first prove the current ledger is the
byte-exact planned activation or preimage and the complete release tree remains
transaction-owned; extra, missing, linked, or changed evidence is preserved as
`RECOVERY_REQUIRED` instead of overwritten or deleted. This executor foundation
is not permission to upgrade a real server: it does not yet contain a
production Preservation adapter, canonical
map/configuration/database migrators, an executable provider bundle, staged
launch/login/gameplay verification, or a public apply surface.

`Import Map Changes` remains a separate transaction. The synthetic harness
keeps its gate closed before activation and opens it only when classification
is exactly `CURRENT` and every installed provider artifact is revalidated
against the selected composition. The ledger launcher/build/map identities and
strict activation marker must also match the selected composition, project,
adapter, semantic plan binding, and transaction receipt. Any ledger, marker,
or release-tree drift closes the gate without writes.

A descriptor-backed server publishes
`server/world-builder-capabilities.json` and maps a lowercase role to
`server/world-builder-configs/<role>.json`. Compiled adapters—not target data—
own parsing, conversion, and mutation destinations. The current registry
supports:

- `generic-layered-v1` for a complete compatible signed-layered package; and
- `spoiled-milk-packed-v1` as a format adapter for the reviewed packed layout.

The second name identifies a packed layout codec, not the World Builder
product, release contents, target world, or install folder. Discovery binds
the exact server/client map pair, definition catalog, rendering assets,
runtime evidence, configuration role, and all four placement families.

The one descriptorless exception is the compiled adapter's exact, complete
reviewed packed fallback layout. Project creation copies that source first,
then writes deterministic capability, configuration, catalog, and runtime
evidence only into the unpublished project staging tree. It never adds those
files to the selected server. Missing, partial, conflicting, ambiguous, or
otherwise unsupported evidence remains blocked. Because this fallback has no
truthful target mutation contract, its project stays detached and does not
advertise import even when the original source is still present.

That fallback accepts one and only one of these equivalent client cache roots:

- `Client_Base/Cache/video` (source-tree layout);
- `client/Cache/video` (packaged-client layout); or
- `Cache/video` (flat packaged-client layout).

The selected root must contain the complete terrain, library, model,
authentic/custom sprite, and menu spritepack evidence. More than one populated
root is treated as ambiguous so a stale build or duplicate cache is never
silently selected. Noncanonical source paths and hashes are preserved in the
immutable source inventory; project creation makes verified canonical aliases
only inside its unpublished staging tree, and the runtime continues receiving
the existing `client/Cache/video` content-bundle layout.

The fallback also captures the target's bounded declarative floor, wall,
scenery, NPC, and item definitions with matching client model/sprite archives
through the versioned project-local custom-content boundary. Bundle v1 remains
for packaged-item-only targets; v2 requires exact static visual metadata and
named archive-entry closure for every beyond-packaged ground item. Authoring
IDs come from exact target bytes rather than the neutral catalog. The immutable
and working copies stay inside the UUID project; no target code runs and no
custom content enters a release archive.

If that static visual file is absent, creation can migrate provable visual
fields from the captured declarative item definitions and captured sprite
archives. Unresolved IDs require an explicit strict mapping JSON selected in
the desktop dialog or passed as `--item-visual-mappings <mapping.json>`.
Mappings select an authentic sprite ID or one exact custom/spritepack
subspace-entry pair and always provide signed picture and blue masks. The
resulting evidence is generated and validated only inside unpublished project
staging; it is never written into the selected server or `source/original`.

## Explicit project creation

The guided command is preferred. To create from a reviewed report explicitly:

```bash
java -jar output/world-builder-tools/world-builder-tools.jar create-project \
  --installation-root '/path/to/World Builder 2' \
  --runtime-root '/path/to/World Builder 2/builder-runtime' \
  --target-root /path/to/server-root \
  --discovery-report /path/to/discovery-report.json \
  --display-name 'My World' \
  --port 43615 \
  --confirm CREATE
```

Omit `--target-root` only for a report whose origin is exactly
`standalone-empty`. A target-backed report must match the supplied target
again during creation. Creation copies and verifies target evidence into
unpublished staging, adopts or converts the map, and publishes the project,
registry, and active pointer only after complete validation.

Each project has this durable shape:

```text
World Builder 2/
  project-registry.json
  active-project.json
  projects/
    <uuid>/
      project.json
      discovery/report.json
      source/
        snapshot-manifest.json
        original/
        content-bundle/              # target-owned declarative content, if adopted
        layered-baseline/package/
        conversion/                 # packed origin only
      working/
        content-bundle/              # explicit versioned runtime input, if adopted
        layered-world/package/
        runtime/
      snapshot-library/v1/          # content-addressed .wbr region bundles
      exports/
      backups/
      receipts/
      diagnostics/
      logs/
      run/
```

`source/` is immutable by contract. Editing and saves affect only the
project's working package. The original server remains read-only until a later
explicit install transaction is requested. Project manifests bind the origin,
adapter, capability, selected configuration, definition/runtime identity, and
source/baseline/working fingerprints. Only a display locator may be absolute,
and it is excluded from portable project identity.

Terrain input accepts the frozen `raw-layered-sector-v1` encoding (10 bytes per
tile) and `raw-layered-sector-v2-u16` (11 bytes per tile). Editable working
packages are promoted losslessly to v2: unsigned 16-bit big-endian elevation
followed by the unchanged nine legacy bytes. New empty worlds, packed
conversions, saves, and region snapshots use v2. A legacy terrain downgrade is
refused unless every elevation is at most 255, with every blocking tile's
level, world coordinates, and value reported.

Existing-project promotion uses a forced, bounded transaction journal and
same-directory atomic package exchange. Open, verification, and save recover
an interrupted exchange to either the exact journaled v1 tree or the fully
validated v2 tree, reconcile project/registry fingerprints, and refuse
unjournaled, linked, malformed, or otherwise ambiguous artifacts.

## Project selection, validation, and save

List and select by exact UUID:

```bash
java -jar output/world-builder-tools/world-builder-tools.jar list-projects \
  --installation-root '/path/to/World Builder 2'

java -jar output/world-builder-tools/world-builder-tools.jar select-project \
  --installation-root '/path/to/World Builder 2' \
  --project-id 12345678-1234-1234-1234-123456789abc
```

Validate and reopen the selected project:

```bash
java -jar output/world-builder-tools/world-builder-tools.jar open-project \
  --installation-root '/path/to/World Builder 2' \
  --target-root /path/to/server-root
```

For a standalone project, omit `--target-root`. A target-backed project may
still be edited in isolation after the original target moves or drifts, but it
is reported detached and cannot later be installed until the exact compatible
target is supplied and verified.

Existing standalone projects retain their immutable catalog and remain on their
original compatibility contract under the current implementation and are never
silently rewritten. The replacement architecture instead adds a lossless
project-schema migration and treats the project-local authoring runtime as a
rebuildable cache, so routine current-to-next upgrades will not require project
recreation. Until that migration is implemented and verified, preserve the
existing project/runtime bytes exactly.

Application updates use the same command with `--validate-only`. That mode
verifies the selected project and optional target evidence without refreshing
attachment state or changing any project, registry, or active-pointer bytes.

Commit a verified working-package fingerprint to project metadata with:

```bash
java -jar output/world-builder-tools/world-builder-tools.jar save-project \
  --project '/path/to/World Builder 2/projects/<uuid>'
```

Project creation, selection, open, and save use project/registry locks,
copy-on-write publication, exact reopen verification, and rollback. They do
not mutate a target.

## Shareable region snapshots

The advanced region commands implement the content-neutral Editor foundation
in `docs/WORLD-BUILDER-2-REGION-SNAPSHOTS.md`. A strict ordered polygon uses
marker 1 as its anchor. Copy captures exact terrain and all four placement
families into a deterministic, non-executable `.wbr` bundle in the
project-local content-addressed library without changing the working package.

```bash
java -jar output/world-builder-tools/world-builder-tools.jar region-copy \
  --project '/path/to/World Builder 2/projects/<uuid>' \
  --selection region-selection-v1.json --name 'Courtyard'

java -jar output/world-builder-tools/world-builder-tools.jar region-export \
  --project '/path/to/World Builder 2/projects/<uuid>' \
  --snapshot <snapshot-sha256> --output /separate/path/courtyard.wbr

java -jar output/world-builder-tools/world-builder-tools.jar region-import \
  --project '/path/to/World Builder 2/projects/<uuid>' \
  --bundle /separate/path/courtyard.wbr
```

Import only adds a validated bundle and emits a compatibility report. It never
pastes. Alternate safe ZIP encodings are canonicalized before library
publication. Cut and paste are preview/apply pairs whose apply command must repeat
the exact current plan hash and use `CUT <hash>`, `PASTE <hash>`, or the
separate `OVERWRITE <hash>` confirmation reported by preview. Missing terrain,
catalog or logical dependency mismatch, stale state, malformed paths, and
unsupported custom materials fail closed. Operations affect only the isolated
working package; source and target data remain unchanged.
Paste previews bind exact deterministic source-to-destination placement-ID
mappings, including when the unchanged source already owns all four IDs.
Boundary/NPC crossing footprints participate in blocking coverage and occupied-
content collision checks. A forced region transaction journal recovers the last
provably complete package/manifest state after interruption and refuses
ambiguous artifacts.
Every staged, failed/displaced, or rollback cleanup is journaled before an
atomic move to its deterministic quarantine. Normal reopen, launch, and save
retry exact before/after cleanup, including partial quarantine deletion and
orphan journal writes. Recovery and library scans are inventory-bounded, and
files are size-checked before hashing or comparison. Aggregate footprint,
spatial-index, and candidate-scan
limits keep adversarial NPC roam inventories bounded without tile expansion.

This is not yet the packaged in-game selection experience. Ordered marker UI,
ghost previews, runtime-authoritative transactions, and durable interactive
undo/redo require the runtime-provider capability listed in the normative
region document.

## Deterministic packed conversion

Project creation invokes the converter internally for a packed origin. The
advanced standalone boundary accepts only an isolated, immutable copy of the
exact files inventoried by a compatible discovery report:

```bash
java -jar output/world-builder-tools/world-builder-tools.jar convert-packed \
  --source-root /path/to/isolated-inventoried-copy \
  --discovery-report /path/to/discovery-report.json \
  --output /path/to/new-conversion-result
```

`--source-root` must not be the live server. Extra files, links, changed
hashes, missing evidence, unsupported encoding, parity loss, unsafe paths, or
an existing output path are refused. Success publishes only a canonical plan,
report, and complete generic layered package. Every terrain sector is decoded
and round-tripped, and base/overlay/removal placement composition retains
record provenance and zero-delta semantics.

Legacy packed terrain values `48001..59999` are embedded scenery markers, not
diagonal boundaries. Conversion resolves each marker through the exact
inventoried `GameObjectDef.xml`, collapses repeated multi-tile footprint
markers in legacy scan order, emits a direction-0 base scenery placement, and
clears the marker from layered terrain. Exact marker bytes remain in immutable
source evidence and are restored during reverse-parity verification. Some
legacy maps use exactly `12000` as an inert diagonal sentinel; conversion
normalizes that value to zero in layered terrain and restores it only for
byte-exact reverse-parity verification. All other nonzero diagonal values
outside the two boundary ranges remain blocked.

The descriptorless legacy fallback also preserves stale scenery and NPC
removal records as immutable source evidence. The original runtime treats a
removal with no matching effective placement as an inert tombstone, so the
layered package omits it and the conversion report records the no-op. This
compatibility rule is limited to the synthesized fallback capability;
descriptor-backed packed composition continues requiring exact removals.

## Runtime and application updates

`run-adaptive-project --project <projects/uuid>` supervises only one verified
adaptive project. Runtime PIDs, logs, locks, credentials, generated ban lists,
database state, client settings, and server/client mutable state remain inside
that UUID project. Immutable runtime assets, definition/asset evidence, the
working package, and source baseline are revalidated before process creation;
readiness, server, or client failure cleans up supervision state and does not
commit a project save.

Application updates replace only manifest-owned content-neutral files. They
preserve all UUID projects, registry/selection, historical `workspace/`,
exports, backups, receipts, diagnostics, logs, settings, credentials,
recovery, locks, and unknown files. After replacement, the new runtime invokes
`open-project` for read-only selected-project compatibility; failure rolls
back only the application layer.

## Adaptive export, install, and recovery boundary

Export is project-local and never reads or writes the target:

```bash
java -jar output/world-builder-tools/world-builder-tools.jar export-adaptive \
  --project '/path/to/World Builder 2/projects/<uuid>'
```

It locks and revalidates the project, copies the complete working layered
package into a unique `exports/export-<fingerprint>-<sequence>/` directory,
binds all source/conversion/definition/runtime identities, independently
validates every byte, and publishes atomically. Re-exporting unchanged state
uses a new directory but retains the deterministic export fingerprint.

An explicit import without confirmation is preview-only:

```bash
java -jar output/world-builder-tools/world-builder-tools.jar import-adaptive \
  --project '/path/to/World Builder 2/projects/<uuid>' \
  --export '/path/to/project/exports/export-…' \
  --target-root /path/to/server-root
```

The preview contains an actual transaction UUID, exact server/client
content-addressed destinations, configuration changes, byte states, backup
and receipt paths, free-space requirements, and post-write/rollback checks.
Preview stdout is exactly one plan JSON document. Apply it with a second call
that repeats the emitted `transactionId` and `planFingerprintSha256`:

```bash
java -jar output/world-builder-tools/world-builder-tools.jar import-adaptive \
  --project '/path/to/World Builder 2/projects/<uuid>' \
  --export '/path/to/project/exports/export-…' \
  --target-root /path/to/server-root \
  --confirm IMPORT \
  --transaction-id '<preview transactionId>' \
  --plan-sha256 '<preview planFingerprintSha256>'
```

The apply call independently recompiles that exact identity before creating
transaction artifacts and emits exactly one result JSON document. The desktop
now separates **Upgrade Target Runtime** from **Import Map Changes** so a
runtime/variant/module transition and a map-only transaction have distinct
preview and consent. The packaged `Import Map Changes` scripts retain literal,
untrimmed `IMPORT` input for command-line and recovery use.

Import reacquires the project and all target offline evidence, rediscovers the
same adapter/capability/source lineage, and rejects drift. Under the replacement
contract it first requires a trusted current target ledger, then writes verified
project-owned backups and a pending map receipt, stages the content-addressed
map package, and activates World-Builder-owned configuration last. The current
development implementation still carries the rejected pinned-core package/
receipt assumptions and is not release evidence for this replacement. Later
detection must recognize the exact installed current composition and layered
map without restoring or reconverting the retired packed source.

Back up and verify the complete target server before importing. There is no
end-user action to reverse a completed import.

Every partial import failure rolls back in safe reverse order and verifies
the complete expected state. If that proof cannot complete, new transactions
are blocked by `recovery-required`. Keep the target offline and use `Recover
Map Transaction`, or preview/apply explicitly with:

```bash
java -jar output/world-builder-tools/world-builder-tools.jar recover-adaptive \
  --project '/path/to/World Builder 2/projects/<uuid>' \
  --target-root /path/to/server-root
```

Apply that reviewed plan in a second call with `--confirm RECOVER`, its emitted
`--transaction-id`, and its emitted `--plan-sha256`. Recovery accepts only paths
and states that match the independently rebuilt compiled transaction, and it
deletes only exact derivable staging content. There is no `--force` path.
Standalone projects may export normally, but current Import and Recovery return
`NO_TARGET` before a target path is resolved, accessed, locked, backed up, or
receipted; the retained historical reversal command has the same origin guard.
A compiled `process-scan` offline requirement currently needs a
readable Linux `/proc` view and fails closed when that process view is absent or
unavailable. A process merely having its working directory below the target is
not treated as the server when readable command and process-name evidence prove
that it is non-Java. The same classification applies when a harmless launcher
or terminal command names the target; Java and ambiguous target-root processes
remain blocked.

## Historical interfaces

The JAR still parses the fixed-layout `discover`, `prepare`, `launch`, `run`,
`create-level`, `export`, `import`, and undo commands for frozen historical
fixtures and earlier v2-alpha compatibility. They operate on the old
single-`workspace/` model and are not the adaptive World Builder 2 workflow.
New launchers, documentation, releases, and projects must use the adaptive
interfaces above. A historical-only installation is preserved rather than
silently relabelled or migrated.

Schemas under `schema/` are versioned release contracts. Add a new schema
version instead of changing an existing version's meaning.
