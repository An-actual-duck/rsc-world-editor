# Changelog

All notable changes to RSC World Editor releases will be recorded here.

The project uses semantic versioning for new releases. Historical
`rsc-world-editor-v1`, `v1.01`, `v1.02`, and `v1.03` packages were published
from the Spoiled Milk repository before this dedicated repository was created.

## World Builder 2 - in development

- Added a gated, manager-main-only v2 packager with separate product, tag,
  archive, install, workspace, and update identities.
- Added Linux and Windows v2-only update paths with exact identity, archive,
  complete-inventory, managed-layer, workspace-preservation, and rollback
  contracts.
- Made production packaging require a release-marked client, reproducible
  Linux/Windows LWJGL native inputs, and exclusion of generated IP-ban state.
- Made the v2-only updater discover prereleases from the published release
  list while ignoring drafts, v1, malformed tags, and downgrades.
- Kept public release publication disabled pending final real-archive
  cross-platform validation and owner acceptance; the frozen v1.1.0 assets and
  release channel remain unchanged.

## v1.1.0 - 2026-07-17

- Established the dedicated source, documentation, CI, and release repository.
- Pinned the compatible Spoiled Milk/Core-Framework runtime source.
- Imported the standalone project, export, import, rollback, and supervision
  tooling with its regression tests.
- Added automatic Linux and Windows update checks against the dedicated GitHub
  release channel.
- Added archive and internal package SHA-256 verification, active-process
  refusal, temporary rollback copies, and durable-workspace preservation.
