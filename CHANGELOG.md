# Changelog

All notable changes to RSC World Editor releases will be recorded here.

The project uses semantic versioning for new releases. Historical
`rsc-world-editor-v1`, `v1.01`, `v1.02`, and `v1.03` packages were published
from the Spoiled Milk repository before this dedicated repository was created.

## World Builder 2 - in development

- Replaced the broad host-runtime claim with a package-driven v3 capability
  matrix. Import now proves the server and player-client class behavior needed
  by each selected terrain/placement encoding, including mixed legacy and
  unsigned-16 terrain, visual/structural scene packets, and placement v3/v4.
- Runtime upgrade now installs one exact prebuilt host core and guards the
  target Ant `compile_core` target while its authoritative v3 receipt exists,
  preventing obsolete target source from silently replacing wide-terrain and
  login behavior. Known old login decoder source is aligned transactionally;
  missing, newer, or customized source is preserved.
- Runtime upgrade retires superseded v1/v2 capability receipts atomically and
  records explicit activation ownership: legacy terrain/placement files remain
  physically present, while the selected native layered package is the runtime
  authority.
- Split runtime migration from ordinary Import. The explicit, offline
  `UPGRADE` transaction accepts an affected backup as its before-state, backs
  up and replaces only the pinned host-integrated `core.jar`, matching player
  client, and v3 capability, removes retired shadow/overlay JARs, verifies the
  result, and lets the following map-only Import chain from its receipt. Maps,
  configuration, definitions, plugins, databases, sources, build files, and
  assets outside the bounded decoder/build integration remain untouched by the
  runtime upgrade.
- Added adaptive discovery, deterministic packed conversion, UUID project
  creation/selection, compatible layered adoption, and standalone empty mode.
- Pinned the generic adaptive loader/authoring runtime capability while keeping
  owner-native visual/edit/save/reopen validation as a release prerequisite.
- Added deterministic complete adaptive exports and compiled content-addressed
  server/client import plans with transaction-ID/plan-fingerprint-bound review,
  fail-closed offline evidence, collision-safe backups/receipts, configuration-
  last activation, and distribution identity.
- Added reverse verified rollback, explicit interrupted-transaction recovery,
  changed-after refusal, and successful-receipt-authorized exact undo for
  layered and converted packed origins. Undo deactivates configuration before
  package removal and restores packages before rollback reactivation.
  Standalone target operations stop with `NO_TARGET` before target resolution.
- Made installed-map Import fail closed before preview or mutation when a
  managed server provider duplicates any class from the target's `core.jar` or
  retained `server/lib` archives. Target libraries and `core.jar` now remain
  first on the generated runtime classpath, and diagnostics identify
  representative conflicting classes without changing the target.
- Replaced the bundled-world package input and broad runtime copies with an
  explicit content-neutral runtime/default-catalog allowlist. Production
  validation now rejects terrain, placements, layered packages, creator state,
  operational state, nonempty terrain/placement, player/account, log, security,
  or generated-operational seed tables, and renamed world bytes.
- Changed the v2 install/display identity to `World Builder 2` and the
  world-source identity to `target-adaptive-v1`, without changing the
  `rsc-world-editor-v2` product or update channel.
- Extended both v2 updaters to preserve every adaptive project, registry and
  selection record, historical `workspace/`, export, backup, receipt,
  diagnostic, setting, log, recovery path, and unknown file through success or
  rollback. Selected-project incompatibility rolls back only the application.
- Added a gated, manager-main-only v2 packager with separate product, tag,
  archive, install, workspace, and update identities.
- Added Linux and Windows v2-only update paths with exact identity, archive,
  complete-inventory, managed-layer, workspace-preservation, and rollback
  contracts.
- Made production packaging require a release-marked client, reproducible
  Linux/Windows LWJGL native inputs, and exclusion of generated IP-ban state.
- Made the v2-only updater discover prereleases from the published release
  list while ignoring drafts, v1, malformed tags, and downgrades.
- Kept public adaptive release publication disabled pending owner-native and
  final Phase 7 real-archive cross-platform validation and acceptance; the
  frozen v1.1.0 assets and release channel remain unchanged.

## v1.1.0 - 2026-07-17

- Established the dedicated source, documentation, CI, and release repository.
- Pinned the compatible Spoiled Milk/Core-Framework runtime source.
- Imported the standalone project, export, import, rollback, and supervision
  tooling with its regression tests.
- Added automatic Linux and Windows update checks against the dedicated GitHub
  release channel.
- Added archive and internal package SHA-256 verification, active-process
  refusal, temporary rollback copies, and durable-workspace preservation.
