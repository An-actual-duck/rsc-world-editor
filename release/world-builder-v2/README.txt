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
preservation are implemented. Phase 6 complete export, compiled target import,
verified rollback/recovery, changed-after refusal, and exact undo are also
implemented. The generic Phase 4 client/server capability is published and
pinned, but owner-run native visual/edit/save/reopen validation has not yet
accepted the release-launch path. A new public adaptive archive must not be
published until that validation and a new candidate acceptance record are
complete.

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

SAVE, IMPORT, RECOVERY, AND UNDO
--------------------------------

Project save validates the complete working layered package and atomically
updates its fingerprint. It never reads or writes the target. Source corruption,
unsaved manifest drift, linked runtime state, or concurrent project operations
fail closed.

Close World Builder and stop the private target server completely before
installing. Run "Import Map Changes.sh" on Linux or "Import Map Changes.cmd"
on Windows. It exports the active saved project, revalidates the immutable
source and exact target capability, acquires every advertised offline signal,
and displays a JSON/plain-language preview with an actual transaction ID,
server/client content-addressed destinations, configuration changes, backups,
receipt, free-space requirement, and verification steps. Nothing is changed
until you type IMPORT exactly.

Confirmed Import writes verified backups and a durable pending receipt beneath
the project before touching the target. It publishes verified server and
client package content first, activates the selected configuration last, and
then verifies every byte and both package selections. Before restarting,
distribute the exact reported client package/map identity to every player.

Run "Undo Last Map Import" only while the target is offline. It previews the
latest successful unreverted import and requires UNDO exactly. Any installed
file that changed after import, including an extra package path, blocks Undo
before a new backup, receipt, or target mutation. Successful Undo restores the
original configuration and target inventory exactly. Configuration is
deactivated/restored before package removal; rollback restores packages before
reactivation.

A partial failure automatically rolls back and verifies the safe state. If the
tool reports RECOVERY_REQUIRED, do not start the server or run another
transaction. Keep the complete project/backups/receipts and run "Recover Map
Transaction"; review its exact plan and type RECOVER. Recovery accepts only
paths that still match the compiled transaction's exact before or after state.

There is no force option and no binary patcher. Standalone projects can save
and export, but Import, Undo, and Recovery return NO_TARGET before resolving,
accessing, or locking any target path.

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
- A compiled process-scan offline requirement currently requires a readable
  Linux /proc process view and fails closed if that view is unavailable.
- The default local port is 43615. WORLD_BUILDER_PORT may select 1 through
  65534; WORLD_BUILDER_CONFIGURATION_ROLE chooses one declared ambiguous role.
- Server administrators remain responsible for distributing the exact
  compatible client/map identity reported by each successful Import.
- World Editor v1 remains frozen with separate identity, update channel,
  install folder, workspace, and artifacts.
- Release readiness still requires Phase 4 owner validation, complete Phase 7
  automated/native candidate validation, archive inspection, and an exact
  accepted candidate record.

Release source commit: @SOURCE_COMMIT@
