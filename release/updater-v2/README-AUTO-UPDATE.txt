
WORLD BUILDER 2 AUTOMATIC UPDATES
---------------------------------

World Builder 2 checks the published release list for dedicated
rsc-world-editor-v2 tags before launch. It supports v2 alpha prereleases,
ignores drafts and malformed tags, and selects the newest valid v2 semantic
version without downgrading. It never treats the frozen World Editor v1.1.0
normal release as an eligible update, and v1 never recognizes a v2 tag as one
of its updates.

An update is accepted only when the installed and downloaded packages carry
the exact canonical rsc-world-editor-v2 product and channel identity. The
updater verifies the platform archive against the release SHA256SUMS.txt,
rejects unsafe archive paths and links, verifies every file and the complete
package inventory, prepares a private rollback copy, and replaces only files
managed by the installed application manifest.

The workspace/ directory is durable v2 user state and is never included in,
deleted by, or replaced by an update. Saved layered projects, exports,
backups, receipts, credentials, the Builder database, settings, and logs stay
in place. Unknown files outside the managed application manifest are also not
silently overwritten. If installation or verification fails after replacement
begins, the previous managed application files are restored.
If that emergency restoration itself cannot complete, recovery staging and
the update lock are retained and launch stays blocked instead of starting an
unverified mixed application.

Run "Update World Builder.sh" on Linux or "Update World Builder.cmd" on
Windows to check manually. Set WORLD_BUILDER_SKIP_UPDATE=1 before launching to
skip an automatic check. A network or update-service failure produces a
warning and the installed v2 application continues to launch. Updating is
refused while a Builder server or client process is active.

Updating the application never rebases or migrates a project. An existing v2
workspace remains tied to the runtime and signed-layered source snapshot with
which it was created. A legacy or unidentified workspace is always refused.
