# Architecture

## Product boundary

RSC World Editor is a local, isolated editing appliance. It uses the real game
client, server, terrain format, definitions, collision rules, and authoritative
world-editor protocol, but it never connects to or edits a public server.

The repository is divided into three layers:

- `tools/world-builder/` contains Java tooling for target discovery, workspace
  preparation, process supervision, export, import, and rollback.
- `release/world-builder/` contains the user-facing launchers, runtime profile,
  instructions, and asset provenance copied into platform packages.
- The Core-Framework revision in `core-framework.lock` supplies the compiled
  client/server runtime and the integrated editor implementation.

## Durable and replaceable state

An installed package has two fundamentally different classes of files.

Durable user state includes `workspace/source`, `workspace/working` authored
map files, exports, backups, receipts, credentials, the Builder database, and
settings. It must survive application updates and is never committed here.

Replaceable application state includes the packaged Java runtime, launcher
tooling, client/server binaries, definitions, caches, schemas, scripts, and
documentation. Release and updater work may replace this layer only after the
Builder is closed and the replacement has been verified.

## World-data transaction

The workspace stores an immutable source snapshot and a mutable working copy.
Saving affects only the working copy. Export produces a validated five-file
authored bundle. Import compares that bundle with the exact target revision,
requires an offline target, creates verified backups, replaces all destinations
transactionally, and writes a receipt. Undo uses the receipt and refuses to
overwrite files changed after import.

There is deliberately no force-import path.

## Runtime parity

The editor spans client and server code, so duplicating the full game source in
this repository would create the same drift this repository is intended to
prevent. Instead, every release pins one published Spoiled Milk commit. The
release build refuses a different dependency revision, and CI verifies the
synchronized standalone directories against that revision.
