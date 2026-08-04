WORLD BUILDER 2 @VERSION@
=========================

World Builder 2 is a server-agnostic drop-in world editor. Its archive contains
the application runtime and default definition/rendering catalogs, but no map,
terrain, static placements, layered world package, or sample project.

It is separate from the frozen packed-map World Editor v1 line, whose final tag
is v1.1.0. This product and update channel is rsc-world-editor-v2, its install
name is "World Builder 2", and its world-source identity is target-adaptive-v1.
It never upgrades from, opens, or migrates a v1 workspace.

CURRENT STATUS
--------------

Adaptive discovery, packed conversion, layered adoption, standalone empty
creation, UUID project lifecycle, content-neutral packaging, and durable update
preservation are implemented. The generic Phase 4 client/server capability is
published and pinned, but owner-run native visual/edit/save/reopen validation
has not yet accepted the release-launch path. Generic export/import/undo is
Phase 6. A new public adaptive archive must not be published until those gates
and a new candidate acceptance record are complete.

INSTALLATION
------------

For a target-backed project, place the complete folder directly inside the root
of the server you want to edit:

  Your Server/
    client-or-Client_Base/
    server/
    World Builder 2/

For a standalone empty project, put the complete folder in an ordinary parent
that contains no recognizable server. Do not put it inside server/ or a client
folder, extract it over v1, or move individual files from another installation.

FIRST LAUNCH
------------

Linux: run "Start World Builder.sh".
Windows: double-click "Start World Builder.cmd".

The launcher treats its parent as the possible target and performs read-only
adaptive discovery. It does not assume one configuration filename and never
substitutes a world bundled with this application. Discovery produces one of
these results:

- a compatible layered map is copied into an isolated project unchanged;
- a supported packed map is copied and converted deterministically;
- no recognizable server creates a labelled standalone empty project at layer
  0, coordinate 0,0; or
- an ambiguous, malformed, partially recognized, or unsupported server stops
  with a compatibility report and no project or target change.

Review the report and type CREATE exactly before the first project is
published. Project creation writes beneath:

  World Builder 2/projects/<project-uuid>/

The parent target remains byte-for-byte unchanged. Projects have immutable
source evidence, a verified layered baseline, mutable working package, and
their own runtime/log/backup/export/receipt/diagnostic state. Multiple projects
coexist. Moving the complete folder preserves project identity; a target-backed
project becomes detached until its exact compatible target is available again.
There is no automatic rebase.

NATIVE VALIDATION GATE
----------------------

The pinned provider now advertises generic layered loading, existing-level and
canonical-void authoring, all placement families, isolated runtime binding, and
verified copy-on-write save. This development release keeps native adaptive
launch fail-closed with LOADER_INCOMPATIBLE until the owner records adopted and
standalone visual/edit/save/reopen acceptance. The project stays valid and the
target is not touched by that refusal.

AI validation uses automated launcher/runtime contracts only. The owner must
perform and report the visual inspection; screenshots are not captured or
judged by the release process.

SAVE, IMPORT, AND UNDO
----------------------

Project save validates the complete working layered package and atomically
updates its fingerprint. It never reads or writes the target. Source corruption,
unsaved manifest drift, linked runtime state, or concurrent project operations
fail closed.

Generic adaptive export/import/undo is Phase 6 and is not implemented here.
Standalone Import and Undo return NO_TARGET before target access. Target-backed
adaptive mutation remains unavailable until the bounded adapter-driven Phase 6
transaction is complete. There is no force option and no binary patcher.

HISTORICAL V2 ALPHA
-------------------

An existing workspace/ belongs to the historical pre-adaptive alpha. It is not
called target-derived and is never migrated into projects/. A historical-only
installation refuses automatic adaptive relabelling or update; preserve its
complete folder for matching-version recovery and install adaptive World
Builder 2 beside it in a separate folder.

UPDATES
-------

The Linux and PowerShell v2 updaters manage only the replaceable application
layer. They never own projects/, project-registry.json, active-project.json,
historical workspace/, exports, backups, receipts, diagnostics, settings, logs,
recovery state, or unknown files. All remain byte-for-byte unchanged on success
and rollback. After replacement, the updater runs the compatibility check
available through the selected-project lifecycle; incompatibility restores only
the old application layer.

Run "Update World Builder.sh" or "Update World Builder.cmd" for a manual check.
Set WORLD_BUILDER_SKIP_UPDATE=1 before launch for an offline session. A network
failure warns and continues with the installed verified application. A retained
update lock blocks launch pending recovery.

REQUIREMENTS AND LIMITS
-----------------------

- A supported target supplies truthful capability evidence or matches one
  exact repository-owned adapter. Similar-looking unknown forks are not guessed.
- The default local port is 43615. WORLD_BUILDER_PORT may select 1 through
  65534; WORLD_BUILDER_CONFIGURATION_ROLE chooses one declared ambiguous role.
- Server administrators remain responsible for distributing the matching
  compatible client/map update after Phase 6 import is available.
- World Editor v1 remains frozen with separate identity, update channel,
  install folder, workspace, and artifacts.
- Release readiness still requires Phase 4 owner validation, Phase 6, complete
  automated tests, archive inspection, and an exact accepted candidate record.

Release source commit: @SOURCE_COMMIT@
