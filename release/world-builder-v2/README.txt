SPOILED MILK WORLD BUILDER 2 @VERSION@
======================================

This package edits a compatible Spoiled Milk private server without changing
its active map until you deliberately import your work.

This is the signed-layered second generation of the editor. It is a separate
product from the frozen packed-map RSC World Editor v1 line, whose final tag is
v1.1.0. World Builder 2 uses product and update channel
rsc-world-editor-v2. It never installs as an automatic v1 update, and it does
not open or migrate a v1 workspace.

IMPLEMENTATION STATUS
---------------------

This source template contains the repository-owned Phase 3 adaptive project
lifecycle. It is not the final adaptive release guide. Native editing remains
blocked until a separately reviewed compatible client/server runtime is
published and pinned. Content-neutral packaging and adaptive export/import are
also later release gates. Do not publish a new adaptive archive from this state.

INSTALLATION
------------

For a target-backed project, place the entire "Spoiled Milk World Builder 2"
folder directly inside the root of a compatible private server:

  Your Private Server/
    client-or-Client_Base/
    server/
    Spoiled Milk World Builder 2/

For a standalone empty project, place the complete folder in an ordinary
parent directory that contains no recognizable server. Do not place it inside
server/ or a client folder. Do not extract it over World Editor v1 or copy a v1
workspace into it.

FIRST LAUNCH AND PROJECTS
-------------------------

Linux: run "Start World Builder.sh".
Windows: double-click "Start World Builder.cmd".

The launcher treats its parent as the possible target and performs strictly
read-only adaptive discovery. It does not assume server/myworld.conf and does
not select a map bundled with World Builder. Discovery has three safe results:

- a compatible layered map is copied into an isolated project unchanged;
- a compatible packed map is copied and converted deterministically; or
- no recognizable server creates a labelled standalone empty project at layer
  0, coordinate 0,0.

A malformed, ambiguous, unsupported, or partially recognized server is a
blocker, not an empty world. Review the report and type CREATE exactly before
the first project is published. Project creation writes only beneath:

  Spoiled Milk World Builder 2/projects/<project-uuid>/

The parent server remains byte-for-byte unchanged. Later launches validate and
reopen active-project.json. Advanced users can list and select multiple project
UUIDs with the packaged World Builder CLI. Moving the complete folder preserves
project identity; a target-backed project becomes detached until the exact
compatible target is found again. There is no automatic rebase.

CURRENT NATIVE-RUNTIME LIMIT
----------------------------

After creating or reopening a valid project, this phase stops with
LOADER_INCOMPATIBLE before starting a real server or client. The pinned runtime
does not yet advertise the Phase 4 generic layered-loader, existing-level
authoring, and empty-world void-authoring capability. The project is valid and
preserved; this refusal does not modify the target and is not a partial launch.

The repository tests process supervision with isolated temporary runtimes.
Those tests keep logs, PIDs, credentials, client settings, generated
server/ipbans.txt, and all other operational state inside the selected project.
They do not claim that the current packaged client/server can edit an adaptive
project.

SAVING AND SOURCE SAFETY
------------------------

Each project contains an immutable source snapshot and layered baseline plus a
mutable working/layered-world/package. The save-project command validates the
complete working package and atomically updates its fingerprint. It never reads
or writes the target. Source corruption, an unsaved manifest mismatch, linked
runtime state, target drift, or a concurrent project operation fails closed.

The in-game Save workflow becomes available only after the compatible Phase 4
runtime is pinned. Until then, do not represent the native editor as usable.

IMPORT AND UNDO
---------------

Adaptive generic export/import is Phase 6 and is not implemented here. When an
adaptive registry exists, the Linux and Windows Import and Undo scripts inspect
the active project before target access:

- standalone empty projects return NO_TARGET; and
- target-backed projects report that adaptive mutation is reserved for Phase 6.

Neither result creates a preview, backup, receipt, or target change. There is no
force option. The historical workspace transaction remains versioned legacy v2
behavior and is not silently reused for an adaptive project.

HISTORICAL V2 STATE AND UPDATES
-------------------------------

An existing workspace/ belongs to the earlier v2-alpha workflow. It is
preserved, but the adaptive launcher refuses to migrate, replace, or open it.
Keep the complete matching installation for recovery. Do not copy individual
files between workspace/ and projects/.

Project/update durability and removal of every bundled world are separate
Phase 5 release gates. Preserve a complete backup of this development
installation. No release, update, import, undo, or dependency change is
authorized merely because project creation succeeds.

REQUIREMENTS AND LIMITS
-----------------------

- The repository-owned launcher behavior is equivalent on Linux and Windows;
  final native validation still requires the later compatible runtime.
- Supported targets require a truthful versioned capability descriptor or one
  exact built-in adapter layout. Similar-looking forks are not guessed.
- The default local Builder port is 43615. Advanced users may set
  WORLD_BUILDER_PORT to another port from 1 through 65534.
- WORLD_BUILDER_CONFIGURATION_ROLE selects one exact declared role when the
  compatibility report identifies more than one candidate.
- World Editor v1 remains frozen at v1.1.0 and has separate identity, update
  channel, install folder, and workspace behavior.
- There is no native adaptive editing, generic export/import, packaging
  readiness, automatic project rebase, migration, or force path in this phase.

Release source commit: @SOURCE_COMMIT@
