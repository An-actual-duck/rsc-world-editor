WORLD BUILDER 2 AUTOMATIC UPDATES
---------------------------------

World Builder 2 checks the published release list for dedicated
rsc-world-editor-v2 tags before launch. It supports v2 prereleases, ignores
drafts and malformed tags, and selects the newest valid v2 semantic version
without downgrading. It never treats the frozen World Editor v1.1.0 release as
an eligible update, and v1 never recognizes a v2 tag as one of its updates.

An update is accepted only when the installed and downloaded packages carry
the exact canonical rsc-world-editor-v2 product, channel, and
target-adaptive-v1 world-source identity. The updater verifies the platform
archive against SHA256SUMS.txt, rejects unsafe archive paths and links,
verifies every managed file against the application manifest and exact runtime
asset allowlist, prepares a private rollback copy, and replaces only the
content-neutral application layer.

Updates never install a map, terrain archive, placement set, project, export,
backup, receipt, credential, database state, log, or PID. A valid application
contains only the generic runtime, tools, launchers, schemas, default
definitions/rendering assets, and a Builder-only database seed with no terrain/
placement, player/account, log, security, or generated-operational rows. Its
only rows are reviewed migration metadata, generic recovery questions, and
SQLite counters allowed by the release inventory.

DURABLE STATE
-------------

The following creator and operational paths are durable and are never
included in, deleted by, or replaced by an update:

  projects/
  project-registry.json
  active-project.json
  workspace/                 (preserved historical v2-alpha state)
  updates/
  exports/
  backups/
  receipts/
  diagnostics/
  logs/
  settings/
  providers/                (local neutral item-visual provider packages)
  credentials/
  recovery/
  locks/

This includes every UUID project and each project's source snapshot, working
map, exports, backups, receipts, diagnostics, logs, runtime state, and material
inbox or pack added by a later feature. Unknown files outside the managed
application manifest are also preserved and are never silently overwritten.

TRANSACTION AND ROLLBACK
------------------------

Before replacement, the updater refuses while any registered project or
historical workspace has an active Builder server/client PID. After replacing
the application, it verifies the installed inventory. If adaptive project
state exists, the new runtime then opens the selected project through the
existing read-only project-validation boundary. That check verifies registry,
selection, project metadata, source lineage, working package, and runtime
compatibility; it does not save, export, import, migrate, rebase, or mutate a
project or target.

If replacement or selected-project validation fails, the previous managed
application files are restored and durable state is left byte-for-byte
untouched. If emergency restoration itself cannot complete, recovery staging
and the update lock are retained and launch stays blocked instead of starting
an unverified mixed application.

HISTORICAL ALPHA INSTALLS
-------------------------

A pre-adaptive v2-alpha installation that has workspace/ state but no adaptive
project registry is preserved and refused before any network request or
replacement. The updater does not relabel or migrate that state. Install the
current adaptive World Builder 2 side by side, then keep the historical
installation unchanged until a separately reviewed migration exists.

USING THE UPDATER
-----------------

Run "Update World Builder.sh" on Linux or "Update World Builder.cmd" on
Windows to check manually. Set WORLD_BUILDER_SKIP_UPDATE=1 before launching to
skip an automatic check. A network or update-service failure produces a
warning and the verified installed application continues to launch.

Updating never changes the target server. Export/install/recovery/undo are a
separate explicit workflow and are not authorized by an application update.
