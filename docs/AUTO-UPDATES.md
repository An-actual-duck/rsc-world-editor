# Automatic updates

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
