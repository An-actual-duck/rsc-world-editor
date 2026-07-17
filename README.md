# RSC World Editor

RSC World Editor is the standalone distribution of Spoiled Milk's in-game
world-building tools. It launches an isolated local server and client, logs in
as the protected `Builder` account, and provides terrain, wall, scenery, and
NPC editing without changing the attached private server until the user
explicitly imports an export.

The downloadable application is published from this repository. The gameplay
runtime is compiled from a pinned revision of
[Spoiled Milk](https://github.com/An-actual-duck/open-rsc-spoiled-milk), so
client and server bug fixes are incorporated deliberately instead of being
copied into a second game fork.

## Repository status

This repository contains:

- the standalone project discovery, launch, export, import, and rollback tools;
- a checksum-verified, workspace-preserving automatic update channel;
- Linux and Windows launch/import/undo packaging assets;
- versioned project, export, and receipt schemas;
- deterministic unit and filesystem-transaction tests;
- release tooling tied to an explicit Core-Framework source revision; and
- architecture, development, provenance, and release documentation.

Built clients, servers, Java runtimes, user workspaces, credentials, maps,
exports, backups, and logs are intentionally excluded from Git.

## End users

End users should download a platform archive from this repository's
[Releases](https://github.com/An-actual-duck/rsc-world-editor/releases) page.
Source checkouts are intended for development and release production.

The packaged workflow is:

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

The complete end-user instructions are maintained in
[`release/world-builder/README.txt`](release/world-builder/README.txt).

## Development

Requirements are Git, Python 3, and JDK 17 or newer. The tools are compiled to
Java 8 bytecode for compatibility with the bundled runtime contract.

```bash
./scripts/build-tools.sh
./scripts/test.sh
```

The pinned runtime source is declared in [`core-framework.lock`](core-framework.lock).
To create or refresh a local dependency checkout:

```bash
./scripts/checkout-core-framework.sh
./scripts/check-core-parity.sh .core-framework
```

World Builder integration changes are developed alongside their client/server
changes in Spoiled Milk first. Once those changes are published, this source
snapshot and lock are refreshed with:

```bash
./scripts/sync-from-core-framework.sh /path/to/open-rsc-spoiled-milk
```

See [Development](docs/DEVELOPMENT.md),
[Architecture](docs/ARCHITECTURE.md), and [Releasing](docs/RELEASING.md) for the
full contracts.

## License

The source is provided under the GNU Affero General Public License, version 3.
Third-party and game-asset provenance included in release packages is recorded
separately by the packaging inputs and `ASSET-SOURCES.txt` files.
