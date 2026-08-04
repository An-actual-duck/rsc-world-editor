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
neutral packaging, and durable application updates are implemented. Native
adaptive launch remains intentionally fail-closed with `LOADER_INCOMPATIBLE`
until the owner records target-backed and standalone visual/edit/save/reopen
validation. Generic adaptive export, target install, recovery, and undo are
Phase 6 work and are not exposed as a working mutation path yet.

Build the standalone tools with:

```bash
./scripts/build-world-builder-tools.sh
```

The examples below use:

```text
output/world-builder-tools/world-builder-tools.jar
```

Packaged launchers use the identical JAR under
`builder-runtime/launcher/world-builder-tools.jar`.

## Primary guided launch

The packaged Linux and Windows launchers use `launch-adaptive`:

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
offers a canonical standalone empty project at layer `0`, coordinate `0,0`.
Recognizable but incomplete, malformed, unsupported, changing, or ambiguous
server evidence is blocked; it is never treated as an empty world.

The discovery report is printed before the exact `CREATE` confirmation. The
command then creates one isolated UUID project without changing the target.
Later launches validate and reopen the selected project. Optional
`--configuration-role <role>` resolves an explicitly declared multi-role
server, and `--display-name <name>` names the first project.

The current native-process step stops at the owner-validation gate described
above. Project creation and validation remain useful and fully transactional;
the refusal does not partially start a client or server.

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
        layered-baseline/package/
        conversion/                 # packed origin only
      working/
        layered-world/package/
        runtime/
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

Commit a verified working-package fingerprint to project metadata with:

```bash
java -jar output/world-builder-tools/world-builder-tools.jar save-project \
  --project '/path/to/World Builder 2/projects/<uuid>'
```

Project creation, selection, open, and save use project/registry locks,
copy-on-write publication, exact reopen verification, and rollback. They do
not mutate a target.

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

## Runtime and application updates

`run-adaptive-project --project <projects/uuid>` supervises only one verified
adaptive project. Runtime PIDs, logs, locks, credentials, generated ban lists,
and server/client mutable state remain inside that UUID project. At present it
reaches the deliberate owner-validation gate and exits without a partial
native launch.

Application updates replace only manifest-owned content-neutral files. They
preserve all UUID projects, registry/selection, historical `workspace/`,
exports, backups, receipts, diagnostics, logs, settings, credentials,
recovery, locks, and unknown files. After replacement, the new runtime invokes
`open-project` for read-only selected-project compatibility; failure rolls
back only the application layer.

## Adaptive export, install, and recovery boundary

The repository contains strict adaptive schema contracts for complete layered
exports (`export-manifest-v2`), compiled target mutation plans
(`target-mutation-plan-v1`), and transactional receipts
(`import-receipt-v3`). Phase 6 must connect those contracts to project-relative
export, exact target preview/install, backup, verification, rollback,
changed-after-import refusal, recovery, and undo.

Until that phase is implemented:

- there is no supported adaptive export/install command;
- `import-active-adaptive` and `undo-active-adaptive` fail closed;
- standalone import/undo returns `NO_TARGET` before resolving a target; and
- no updater or launch command writes to a target.

There is no force path. Future apply and undo operations must retain exact
confirmation, offline proof, immutable source validation, compiled adapter
destinations, backups, receipts, verification, rollback, and target-drift
protection.

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
