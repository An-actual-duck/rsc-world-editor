# Automatic updates

The frozen v1 line and World Builder 2 have independent update identities,
tags, archives, install folders, scripts, and durable workspaces. They never
cross-update.

## Frozen v1 channel

The existing `release/updater/` implementation describes the frozen v1 update
channel through final release `v1.1.0`. It recognizes only ordinary semantic
v1 tags such as `v1.1.0`; the `rsc-world-editor-v2-*` tag form is invalid on
that channel. Its source remains unchanged for provenance and reproduction.

Every packaged launch checks the latest normal release in
`An-actual-duck/rsc-world-editor`. Network failure does not block the installed
application: the launcher prints a warning and continues with its current
version.

An available update follows this sequence:

1. Refuse if the workspace records a live Builder server or client process.
2. Acquire a package-level update lock.
3. Download the platform archive and `SHA256SUMS.txt` from the same GitHub
   release.
4. Verify the archive SHA-256 digest.
5. Extract into a private staging directory under `updates/`.
6. Validate the release version and every file in
   `PACKAGE-MANIFEST.sha256`.
7. Reject any package manifest that attempts to manage `workspace/` or
   `updates/`.
8. Create a temporary rollback copy of the installed application layer.
9. Remove only the managed application layer, install and verify the new
   package, and restore the prior files if installation or verification fails.
10. Remove temporary download, extraction, and rollback state.

`workspace/` is durable user data. It contains authored maps, source snapshots,
working files, exports, backups, receipts, credentials, the local Builder
database, logs, and run history. The updater neither includes nor replaces that
directory.

Existing projects remain tied to the definitions and runtime snapshot with
which they were created. Updating the application does not silently rebase a
map project onto changed game definitions. Finish and import existing work, or
preserve the old project and create a fresh one, when moving between
incompatible private-server revisions.

Manual update checks use `Update World Builder.sh` on Linux or
`Update World Builder.cmd` on Windows. Set `WORLD_BUILDER_SKIP_UPDATE=1` before
launching to suppress the automatic check for an offline session.

## World Builder 2 channel

World Builder 2 uses product and update identity `rsc-world-editor-v2`, tags
such as `rsc-world-editor-v2-0.1.0-alpha.1`, archive prefix
`rsc-world-editor-v2`, and install folder `Spoiled Milk World Builder 2`.
Its separate scripts live under `release/updater-v2/`. Public v2 alpha
packaging is enabled by its reviewed acceptance marker, and the updater
transaction and package contracts remain covered by release-readiness fixtures.

Before making any network request, the v2 updater requires the installed
`VERSION.txt`, both source-commit files, package manifest, and canonical
`RELEASE-IDENTITY.json` to agree. That identity permits automatic updates only
from `rsc-world-editor-v2`, records frozen legacy product
`rsc-world-editor-v1` at `v1.1.0`, and explicitly disables legacy workspace
migration. A v1 release record is therefore ignored, never an update offer;
if no published valid v2 record exists, the v2 channel check fails closed.
The v2 scripts do not use GitHub's repository-global `releases/latest`
endpoint. They inspect the published releases collection, accept stable and
supported alpha prerelease records, discard drafts, v1 and malformed tags,
and select the greatest valid v2 semantic version before comparing it with the
installed version. Thus frozen v1 can remain the latest normal release while a
newer World Builder 2 alpha remains discoverable only by the v2 channel.

An eligible newer v2 release follows this sequence:

1. Refuse if the workspace records a live Builder server or client process.
2. Acquire the v2-specific package update lock.
3. Query up to 100 published release records, including prereleases, and
   select the newest valid non-draft `rsc-world-editor-v2` semantic tag.
4. Refuse an equal version or downgrade without downloading an archive.
5. Download the platform archive and `SHA256SUMS.txt` from the exact v2 tag.
6. Verify the archive digest, reject unsafe or duplicate archive paths and
   links, and extract only into private `updates/` staging.
7. Require the downloaded version, tag, product identity, update channel, and
   both provenance commits to agree exactly.
8. Validate every package-manifest path and hash, require complete inventory
   coverage, and reject durable-state paths or untracked files.
9. Refuse any collision with an installed path not owned by
   the current application manifest.
10. Back up the exact currently managed application files, arm rollback, and
   remove only those managed files. Unknown installed files are not silently
   deleted or overwritten.
11. Install and reverify the new managed layer. Any copy or verification
   failure removes the partial new layer and restores the previous managed
   files before releasing the lock.
12. Remove temporary archive, extraction, and rollback state. If rollback
    itself cannot complete, retain its staging and update lock for recovery;
    the launcher refuses to start while that lock remains.

The entire v2 `workspace/` remains durable. Updating never rebases a signed-
layered project, imports a map, changes the parent private server, or adopts a
v1 or unidentified workspace. Manual and automatic controls use the same
filenames and `WORLD_BUILDER_SKIP_UPDATE=1` switch as v1, but only inside the
separate World Builder 2 install folder.
