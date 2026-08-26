# RSC World Editor

RSC World Editor is the home of two deliberately separated product
generations. The adaptive World Builder 2 contract is a standalone,
server-agnostic drop-in editor: put its complete folder directly inside a
compatible RSC game/server root, launch it, and it discovers that installation's
active map and definitions. It copies, adopts, or converts those inputs into an
isolated project; editing and saving stay inside World Builder until the user
explicitly runs the transactional import command.

The downloadable application is published from this repository. Its in-game
editing runtime is compiled from the separately managed
[RSC World Editor Runtime](https://github.com/An-actual-duck/rsc-world-editor-runtime).
That exact pinned checkout is a generic build/runtime dependency and supported
adapter source; it is not World Builder 2's product identity, target world, or
bundled map. Runtime development is intentionally independent from Spoiled
Milk/Core-Framework.

## Product generations

The published packed-map product is the frozen legacy v1 line, whose final
standalone release is `v1.1.0`. Its source package assets remain under
`release/world-builder/` for provenance and reproducibility.

Current development is **World Builder 2**, a distinct signed-layered product
with product/update identity `rsc-world-editor-v2` and package assets under
`release/world-builder-v2/`. Adaptive packages use the generic install folder
`World Builder 2` and world-source identity `target-adaptive-v1`; they contain
no terrain, static placements, layered world, or creator project. V1 never
automatically upgrades to v2, and v2 never opens or silently migrates a v1
workspace. The first public v2 alpha was accepted after
real-archive validation recorded in
`docs/releases/world-builder-v2-v0.1.0-alpha.1-validation.md`. The dedicated v2
packager and workspace-preserving updater operate without reopening the frozen
v1 channel. The current adaptive release is `v0.5.0-alpha.11`, accepted and
published on 2026-08-25. Production artifacts were rebuilt from its published
gate commit rather than promoted from restricted validation archives.

## Repository status

This repository contains:

- the standalone project discovery, launch, export, import, rollback, recovery,
  and exact undo tools;
- separate checksum-verified v1 and v2 update channels, with v2 preserving all
  adaptive projects and historical creator state;
- Linux and Windows launch/import/recovery/undo packaging assets;
- versioned project, export, and receipt schemas;
- deterministic unit and filesystem-transaction tests;
- release tooling tied to an explicit independent runtime-provider revision; and
- architecture, development, provenance, and release documentation.

Built clients, servers, Java runtimes, user workspaces, credentials, maps,
exports, backups, and logs are intentionally excluded from Git.

## End users

End users should download a supported platform archive from this repository's
[Releases](https://github.com/An-actual-duck/rsc-world-editor/releases) page.
Source checkouts are intended for development and release production. Published
history includes the frozen legacy v1 line, the historical pre-adaptive alpha,
and the current adaptive `rsc-world-editor-v2-0.5.0-alpha.11`. Development
continues after that alpha with its release gate closed.

The legacy v1 packaged workflow is:

1. Extract `Spoiled Milk World Builder` inside a compatible private-server
   root, beside `server/` and `Client_Base/`.
2. Start `Start World Builder.sh` on Linux or `Start World Builder.cmd` on
   Windows.
3. Edit and save inside the isolated `workspace/`.
4. Close the Builder and run `Import Map Changes` only when the target private
   server is offline.
5. Use `Undo Last Map Import` if the imported result needs to be reverted.

The launcher checks this repository's latest normal release before starting.
Updates replace application files only; adaptive `projects/`, registry and
selection files, the historical `workspace/`, exports, backups, receipts,
diagnostics, settings, logs, recovery state, and unknown files are preserved.
See [Automatic updates](docs/AUTO-UPDATES.md) for the exact safety boundary.

The complete legacy instructions are maintained in
[`release/world-builder/README.txt`](release/world-builder/README.txt).
World Builder 2's instructions are kept separately in
[`release/world-builder-v2/README.txt`](release/world-builder-v2/README.txt).

The adaptive v2 product contract is:

1. Put the complete `World Builder 2` folder directly inside a compatible
   game/server root and launch it. The desktop screen offers New Empty World,
   Use Detected Server Map, Open Existing Project, and Select Another Supported
   Source; the command-line workflow remains available for automation.
2. Let World Builder discover the target's active map, definitions, and
   compatibility evidence; unsupported or ambiguous layouts fail with a
   report instead of being guessed.
3. Adopt compatible layered data or convert a supported packed map into an
   isolated project. A production adaptive archive supplies no map, world, or
   static placements of its own.
4. Edit, save, close, and reopen only the project copy under World Builder.
5. Change the target only by running the explicit previewed, backed-up,
   verified import transaction. Distribute the reported matching client
   package before restart.
6. Use the exact previewed Undo transaction to restore an unchanged imported
   target, or keep the target offline and use Recovery if an interrupted
   rollback is explicitly reported. Standalone empty projects have no target
   transaction path.
7. Phase 6 keeps one outstanding successful import at a time. You may continue
   editing and saving the isolated project, but Undo the outstanding import
   before importing a later export; Undo preserves those later working bytes.

## Development

Requirements are Git, Python 3, and JDK 17 or newer. The tools are compiled to
Java 8 bytecode for compatibility with the bundled runtime contract.

```bash
./scripts/build-tools.sh
./scripts/test.sh
```

The frozen runtime dependency is declared in
[`runtime-provider.lock`](runtime-provider.lock). To materialize that exact locked
revision and verify its adaptive runtime contract:

```bash
./scripts/checkout-runtime-provider.sh
./scripts/check-runtime-provider-parity.sh .runtime-provider
```

The World Editor product manager never monitors or operates Spoiled Milk
branches, workers, or releases. When an assigned World Builder objective
includes runtime work, the manager may select the exact tested commit it
publishes from the independent runtime repository and update the dependency
without asking the owner to relay the SHA:

```bash
./scripts/sync-from-runtime-provider.sh \
  /path/to/clean-runtime-provider \
  refs/heads/main
```

For the complete verified and published integration, use
`./scripts/product-manager.sh adopt-runtime`.

Maintainer development uses one manager checkout and reusable neutral worker
worktrees. Initialize or inspect that workflow with:

```bash
./scripts/ai-workspace.sh init 3
./scripts/ai-manager.sh status
```

See [Independent Runtime Provider](docs/RUNTIME-PROVIDER.md) and
[AI Workspaces](docs/AI-WORKSPACES.md) for task activation, checkpoint,
handoff, review, rescue, recycling, and the explicitly gated dependency-update
procedure.

See [Development](docs/DEVELOPMENT.md),
[Architecture](docs/ARCHITECTURE.md), and [Releasing](docs/RELEASING.md) for the
full contracts.

The approved foundation for target-derived maps, content-neutral releases,
isolated projects, and packed-to-layered conversion is documented in [World
Builder 2 Adaptive Map Workflow](docs/ADAPTIVE-MAP-WORKFLOW.md). Phases 0-3,
including strictly read-only adaptive discovery, deterministic packed
conversion, and the adaptive project lifecycle, are merged on published `main`
at `dac388a32aa41754a49341e3ddcc8cc196389ab4`. The lifecycle can atomically
create, select, move, validate, save, and reopen target-layered,
converted-packed, and standalone-empty projects without target writes. The
generic Phase 4 runtime capability is pinned and owner-run native visual,
edit/save, and reopen validation passed. Phase 5 implements the
content-neutral package identity, exact no-world runtime allowlist, and durable
Linux/Windows update boundary. Phase 6 implements deterministic complete
export, compiled server/client mutation plans, offline preview/import, durable
backups and receipts, verified rollback/recovery, changed-after refusal, and
exact undo for adopted and converted projects. Phase 7 archive,
packaged-runtime, transaction, and owner-native validation passed for the exact
candidate recorded in the accepted
[v0.5.0-alpha.11 adaptive validation record](docs/releases/world-builder-v2-v0.5.0-alpha.11-validation.md).
The release was published from its exact gate commit. Development `main` has
consumed and closed that gate so later changes cannot reuse the acceptance;
the immutable release tag retains it. Restricted candidate files remain
evidence only.

The dependent design for nontechnical creator-supplied floor and wall images
is documented in [World Builder 2 Custom Wall and Floor
Materials](docs/WORLD-BUILDER-2-CUSTOM-MATERIALS.md). Existing target materials
can be captured through recognized layouts; the simplest share-safe drop-in
creator-material workflow remains planned for a later release.

The living direction for editor-quality tools, a detached and quiescent Build
mode, fluid terrain strokes, relative elevation, lines and house tools,
share-safe creator materials, broader legacy conversion, region
copy/cut/paste, and exportable snapshots is recorded in [World Builder 2
Product Goals and Readiness](docs/WORLD-BUILDER-2-PRODUCT-GOALS.md). That
document assesses the current foundation and does not itself start
implementation work.

Development now integrates the runtime v2 unsigned 16-bit elevation contract:
v1 terrain remains readable and is promoted losslessly for editing, while v2
packages and region snapshots preserve elevations through 65535. True RGB
terrain remains deferred behind a future explicit capability.

The Editor-owned copy/cut/paste foundation is specified in [World Builder 2
Region Snapshots v1 and v2](docs/WORLD-BUILDER-2-REGION-SNAPSHOTS.md). It implements
strict ordered-polygon snapshots, safe project-local bundle import/export,
compatibility and collision plans, and atomic isolated-project cut/paste.
In-game markers, ghost rendering, runtime transactions, and durable undo remain
separate runtime-provider work and are not claimed by this foundation.

## License

The source is provided under the GNU Affero General Public License, version 3.
Third-party and game-asset provenance included in release packages is recorded
separately by the packaging inputs and `ASSET-SOURCES.txt` files.
