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
pinned. This update adds portable region snapshot copy/cut/paste primitives,
lossless unsigned 16-bit terrain elevation, foreground-aware elevated-terrain
picking, a contextual two-column editor toolbar, a persistent desktop project
screen, portable custom-content providers, full project-backed client
presentation options, and remembered Builder position after the initial
120,648 spawn. Exact archive, packaged-runtime, transaction, and owner-run
native validation are required for every accepted release candidate.

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

Linux: run "Start World Builder.sh". Windows: double-click
"Start World Builder.cmd". Both open the World Builder desktop project screen;
no terminal interaction is required.

Choose New Empty World, Use Detected Server Map, Open Existing Project, or
Select Another Supported Source. Closing or cancelling a screen changes
nothing. The advanced launch-adaptive command remains available for headless
CLI automation.

Servers with custom item or NPC content should distribute one data-only folder:

  Your Server/
    world-builder-provider/
    World Builder 2/

Use Detected Server Map selects that provider automatically. If the server
maintainer distributes it separately, choose Select Another Supported Source,
then Choose complete provider package, and select the
world-builder-provider folder itself. Do not browse for an internal JSON file.
World Builder copies the validated package into its own local provider catalog
and remembers it for later launches. Advanced provider import is only for
maintainers assembling a package from separate definition and asset files.

A missing, unfamiliar, or invalid optional custom visual does not prevent the
editor from launching. The affected ID and name are retained, a deterministic
placeholder is shown, and an actionable warning is saved in the project's
diagnostics folder. No selected target JAR is executed for discovery.

The project screen always shows the World Builder installation folder and its
registered projects, so starting a second extracted copy cannot be mistaken for
reopening the first copy. Open Existing Project means a validated project under
this installation's projects/ folder. Select Another Supported Source accepts a
server/source directory; it does not guess an arbitrary individual map file.
The compatibility preview identifies packed, layered, standalone, or blocked
input and says whether confirming will create a new isolated project or open an
existing one.

The launcher treats its parent as the possible target and performs read-only
adaptive discovery. It does not assume one configuration filename and never
substitutes a world bundled with this application. Discovery produces one of
these results:

- a compatible layered map is copied into an isolated project unchanged;
- a supported packed map is copied and converted deterministically;
- no recognizable server creates a labelled standalone empty project at layer
  0, coordinate 120,648 with a centered 3-by-3 visibility seed; or
- an ambiguous, malformed, partially recognized, or unsupported server stops
  with a compatibility report and no project or target change.

Review the GUI summary and confirm before the first project is published.
When matching server/client Custom_Landscape.orsc files are the selected packed
map or coexist with a compatible layered target, the launcher asks whether to
incorporate them. Yes converts exact verified packed terrain and records guarded
retirement intent for a later compatible import; project creation does not
change the server. No creates from the selected authority without retirement
intent.

Project creation writes beneath:

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
verified copy-on-write save. Native adaptive launch now runs only the selected
UUID project's independent server/client copies, layered working package,
credential, control state, logs, and database. It never resolves the target
during launch or save. Adopted, converted, and standalone visual/edit/save/
reopen acceptance passed for the exact candidate named in the
current validation record. The original adaptive baseline remains recorded in
the v0.2.0-alpha.1 validation record.

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

Confirmed Import file-forces its exact plan, created-directory authority,
activation content, and every verified backup, then forces their directory
entries before publishing and forcing the pending receipt. A filesystem/Java
provider that cannot provide that ordering is refused before transaction
artifacts or target mutation. Import publishes verified server and client
package content first, activates the selected configuration last, and then
verifies every byte and both package selections. Before restarting,
distribute the exact reported client package/map identity to every player.

Run "Undo Last Map Import" only while the target is offline. It previews the
latest successful unreverted import and requires UNDO exactly. Any installed
file that changed after import, including an extra package path, blocks Undo
before a new backup, receipt, or target mutation. Successful Undo restores the
original configuration and target inventory exactly. Configuration is
deactivated/restored before package removal; rollback restores packages before
reactivation.

Phase 6 supports one outstanding successful import per project. You may keep
editing and saving the isolated project after Import A. Undo A uses its exact
historical export and preserves the later working bytes. Before Import B, run
exact Undo A, then export/preview/import the desired saved state. A second
outstanding import is refused with this instruction rather than guessed or
chained.

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
  Linux /proc process view and fails closed if that view is unavailable. A
  Java or ambiguous process whose command names the target is refused. A
  potentially relevant Java process requires both readable cmdline and cwd
  evidence; conclusively non-Java processes may name the target, hide cwd, or
  have a cwd below the target without being misidentified as its Java server.
- Import, Undo, and Recovery require a filesystem/Java provider capable of
  forcing transaction directory entries; unsupported providers fail before
  target mutation.
- The default local port is 43615. WORLD_BUILDER_PORT may select 1 through
  65534; WORLD_BUILDER_CONFIGURATION_ROLE chooses one declared ambiguous role.
- Server administrators remain responsible for distributing the exact
  compatible client/map identity reported by each successful Import.
- World Editor v1 remains frozen with separate identity, update channel,
  install folder, workspace, and artifacts.
- Region snapshot storage, portable bundles, and atomic copy/cut/paste are
  implemented, but the in-game ordered-marker and ghost-preview interface is
  still planned; current access is through the included tooling contract.
- Easy drop-in custom wall and floor material packs are not implemented in
  this release; their approved design remains planned for a later release.
- Native Windows application and PowerShell updater execution were not claimed;
  Windows archive, Java, launcher, and static transaction review passed.

Release source commit: @SOURCE_COMMIT@
