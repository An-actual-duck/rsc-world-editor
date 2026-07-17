# Changelog

All notable changes to RSC World Editor releases will be recorded here.

The project uses semantic versioning for new releases. Historical
`rsc-world-editor-v1`, `v1.01`, `v1.02`, and `v1.03` packages were published
from the Spoiled Milk repository before this dedicated repository was created.

## v1.1.0 - 2026-07-17

- Established the dedicated source, documentation, CI, and release repository.
- Pinned the compatible Spoiled Milk/Core-Framework runtime source.
- Imported the standalone project, export, import, rollback, and supervision
  tooling with its regression tests.
- Added automatic Linux and Windows update checks against the dedicated GitHub
  release channel.
- Added archive and internal package SHA-256 verification, active-process
  refusal, temporary rollback copies, and durable-workspace preservation.
