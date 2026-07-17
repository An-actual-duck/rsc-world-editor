
AUTOMATIC UPDATES
-----------------

World Builder checks the dedicated RSC World Editor GitHub release channel
before each launch. If a newer normal release is available, it downloads the
correct platform archive, verifies its published SHA-256 checksum and internal
package manifest, creates a temporary rollback copy, and replaces only the
application package.

The workspace folder is durable user data and is never included in, deleted by,
or replaced by an update. Saved maps, exports, backups, receipts, credentials,
the Builder database, and logs remain in place. An existing project remains
tied to the compatible runtime snapshot with which it was created; the updater
does not silently rebase authored map work onto changed definitions.

Run "Update World Builder.sh" on Linux or "Update World Builder.cmd" on
Windows to check manually. Set WORLD_BUILDER_SKIP_UPDATE=1 before launching to
skip an automatic check. A network or update-service failure produces a warning
and the installed version continues to launch. Updating is refused while a
Builder server or client process is active.
