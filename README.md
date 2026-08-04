# RSC World Editor

RSC World Editor is the home of two deliberately separated product
generations. The adaptive World Builder 2 contract is a standalone,
server-agnostic drop-in editor: put its complete folder directly inside a
compatible RSC game/server root, launch it, and it discovers that installation's
active map and definitions. It copies, adopts, or converts those inputs into an
isolated project; editing and saving stay inside World Builder until the user
explicitly runs the transactional import command.

The downloadable application is published from this repository. Its in-game
editing runtime is compiled from a pinned revision of
[Spoiled Milk](https://github.com/An-actual-duck/open-rsc-spoiled-milk), so
client and server bug fixes are incorporated deliberately instead of being
copied into a second game fork. That pinned Core-Framework checkout is an
external generic build/runtime dependency and one supported adapter source; it
is not World Builder 2's product identity, target world, or bundled map.

## Product generations

The published packed-map product is the frozen legacy v1 line, whose final
standalone release is `v1.1.0`. Its source package assets remain under
`release/world-builder/` for provenance and reproducibility.

Current development is **World Builder 2**, a distinct signed-layered product
with product/update identity `rsc-world-editor-v2` and package assets under
`release/world-builder-v2/`. The checked-in pre-adaptive assets still use the
inherited install-folder name `Spoiled Milk World Builder 2`; removing that
target-specific packaging identity is pending Phase 5 and is not the adaptive
product contract. V1 never automatically upgrades to v2, and v2 never opens or
silently migrates a v1 workspace. The first public v2 alpha was accepted after
real-archive validation recorded in
`docs/releases/world-builder-v2-v0.1.0-alpha.1-validation.md`. The dedicated v2
packager and workspace-preserving updater operate without reopening the frozen
v1 channel. That historical alpha does not make the adaptive v2 design release
ready; its new release gate remains closed.

## Repository status

This repository contains:

- the standalone project discovery, launch, export, import, and rollback tools;
- separate checksum-verified, workspace-preserving v1 and v2 update channels;
- Linux and Windows launch/import/undo packaging assets;
- versioned project, export, and receipt schemas;
- deterministic unit and filesystem-transaction tests;
- release tooling tied to an explicit Core-Framework source revision; and
- architecture, development, provenance, and release documentation.

Built clients, servers, Java runtimes, user workspaces, credentials, maps,
exports, backups, and logs are intentionally excluded from Git.

## End users

End users should download a supported platform archive from this repository's
[Releases](https://github.com/An-actual-duck/rsc-world-editor/releases) page.
Source checkouts are intended for development and release production. The
currently supported packaged workflow is the frozen legacy v1 line; adaptive
World Builder 2 remains in development.

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
Updates replace application files only; saved projects, exports, backups,
receipts, credentials, databases, and logs under `workspace/` are preserved.
See [Automatic updates](docs/AUTO-UPDATES.md) for the exact safety boundary.

The complete legacy instructions are maintained in
[`release/world-builder/README.txt`](release/world-builder/README.txt).
World Builder 2's in-progress instructions are kept separately in
[`release/world-builder-v2/README.txt`](release/world-builder-v2/README.txt).

The adaptive v2 product contract, once its remaining gates pass, is:

1. Put the complete `World Builder 2` folder directly inside a compatible
   game/server root and launch it.
2. Let World Builder discover the target's active map, definitions, and
   compatibility evidence; unsupported or ambiguous layouts fail with a
   report instead of being guessed.
3. Adopt compatible layered data or convert a supported packed map into an
   isolated project. A production adaptive archive supplies no map, world, or
   static placements of its own.
4. Edit, save, close, and reopen only the project copy under World Builder.
5. Change the target only by running the explicit previewed, backed-up,
   verified import transaction. Standalone empty projects have no import path.

## Development

Requirements are Git, Python 3, and JDK 17 or newer. The tools are compiled to
Java 8 bytecode for compatibility with the bundled runtime contract.

```bash
./scripts/build-tools.sh
./scripts/test.sh
```

The frozen runtime dependency is declared in
[`core-framework.lock`](core-framework.lock). To materialize that exact locked
revision for a build or parity check:

```bash
./scripts/checkout-core-framework.sh
./scripts/check-core-parity.sh .core-framework
```

The World Editor manager does not monitor Spoiled Milk branches, workers, or
releases. Only when the user explicitly assigns an exact-commit dependency
update should the bounded source snapshot and lock be refreshed with:

```bash
./scripts/sync-from-core-framework.sh /path/to/open-rsc-spoiled-milk
```

Maintainer development uses one manager checkout and reusable neutral worker
worktrees. Initialize or inspect that workflow with:

```bash
./scripts/ai-workspace.sh init 3
./scripts/ai-manager.sh status
```

See [AI Workspaces](docs/AI-WORKSPACES.md) for task activation, checkpoint,
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
converted-packed, and standalone-empty projects without target writes. Native
client/server authoring still fails closed pending the external Phase 4 generic
runtime capability. Phase 5 content-neutral packaging and identity cleanup and
Phase 6 generic export/import are also required before adaptive release
readiness.

The dependent design for nontechnical creator-supplied floor and wall images
is documented in [World Builder 2 Custom Wall and Floor
Materials](docs/WORLD-BUILDER-2-CUSTOM-MATERIALS.md).

## License

The source is provided under the GNU Affero General Public License, version 3.
Third-party and game-asset provenance included in release packages is recorded
separately by the packaging inputs and `ASSET-SOURCES.txt` files.
