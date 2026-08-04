# Automatic updates

The frozen v1 line and World Builder 2 have independent product identities,
tags, archives, install folders, scripts, and durable state. They never
cross-update.

## Frozen v1 channel

The existing `release/updater/` implementation describes the frozen v1 update
channel through final release `v1.1.0`. It recognizes only ordinary semantic v1
tags such as `v1.1.0`; `rsc-world-editor-v2-*` is invalid on that channel. Its
source and single-`workspace/` durability contract remain unchanged for
provenance and reproduction.

The v1 launcher warns and continues with its installed version when a network
check fails. A verified update downloads the platform archive and checksums,
validates the v1 identity and complete manifest, backs up only managed
application files, preserves `workspace/`, and restores the managed layer on
failure. See the frozen scripts for the exact historical transaction.

## World Builder 2 channel

Adaptive World Builder 2 uses:

- product/update identity `rsc-world-editor-v2`;
- tags such as `rsc-world-editor-v2-0.2.0-alpha.1`;
- archive prefix `rsc-world-editor-v2`;
- install/display name `World Builder 2`; and
- world-source identity `target-adaptive-v1`.

The canonical identity permits automatic updates only from the same v2 product
and explicitly disables v1 workspace migration. The updater inspects published
releases, including supported alpha prereleases, while rejecting drafts,
duplicate or malformed tags, v1 records, equal versions, and downgrades.

The historical `rsc-world-editor-v2-0.1.0-alpha.1` package predates the adaptive
identity and install name. It is not silently relabelled. A historical
`workspace/` without an adaptive registry causes the update to refuse before
network or update staging; keep that complete installation for matching-version
recovery and install adaptive World Builder 2 in a separate folder. If an
adaptive installation also retains historical `workspace/` state, the updater
preserves it byte-for-byte but never calls it an adaptive project.

### Durable state

The v2 managed manifest must never own or replace:

- `projects/`, including every project's source, working runtime/map, exports,
  backups, receipts, diagnostics, logs, run state, settings, and recovery data;
- `project-registry.json` or `active-project.json`;
- historical `workspace/`;
- root-level exports, backups, receipts, diagnostics, logs, settings, recovery,
  or update-recovery data; or
- any unknown unmanaged file or directory.

These paths remain byte-identical through a successful update and through an
injected rollback. A failed emergency restoration keeps its private recovery
stage and update lock without changing creator state.

### Transaction

An eligible newer release follows the same bounded transaction on Linux and
PowerShell:

1. Validate installed version, canonical adaptive identity, provenance,
   manifest hashes, required files, and the content-neutral application
   allowlist before network access.
2. Refuse a historical-only installation or any live server/client PID found
   in the historical workspace or an adaptive project.
3. Acquire `.world-builder-v2-update.lock` and stage only under `updates/`.
4. Select the newest valid published v2 release; never select v1, a draft,
   malformed record, equal version, or downgrade.
5. Download the exact platform archive and `SHA256SUMS.txt`, then verify its
   digest, root name, safe paths, link-free inventory, and complete package
   manifest.
6. Require exact `rsc-world-editor-v2` / `target-adaptive-v1` identity and both
   source commits. Reject every manifest path outside the replaceable
   application/runtime allowlist, every durable path, and every untracked file.
7. Refuse collisions where a downloaded managed file would overwrite an
   installed path not owned by the old manifest.
8. Back up only the old managed files, arm rollback, remove that managed layer,
   install the new layer, and reverify identity, inventory, and hashes.
9. When adaptive registry state exists, run the compatibility check currently
   available through the new runtime against the selected project. A failure
   restores only the old managed application layer; project state is untouched.
10. Remove temporary transaction state and the lock after success or successful
    rollback. Preserve both after an incomplete emergency restore.

Manual checks use `Update World Builder.sh` on Linux or
`Update World Builder.cmd` on Windows. `WORLD_BUILDER_SKIP_UPDATE=1` suppresses
the automatic check for an offline session. A launch-time network failure warns
and continues with the already verified installed application; a retained
update lock always blocks launch.
