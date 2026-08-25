# Portable item-visual providers

World Builder can consume a neutral, data-only item-visual provider without
running a selected server or client JAR. The preferred portable layout is:

```text
world-builder-provider/
  item-visuals.json
  assets/
    Authentic_Sprites.orsc
    Custom_Sprites.osar
    spritepacks/
    external-items/
```

Place that folder immediately below the server/source folder selected in the
desktop launcher, or select the provider folder itself. An explicit provider
always wins over compatibility discovery. Missing or invalid optional visual
assets do not stop source discovery; the item-visual consumer reports a local
warning and uses the standard placeholder for affected records.

Providerless compatibility discovery also recognizes normal client/server
archive mirrors. If their shared archives are byte-identical, the layout with
the more complete role set (such as client spritepacks) is selected
automatically. A recognized client cache is renderer-authoritative and
server-data archive roots are fallbacks only when no client cache is present.
Conflicting client roots remain visible and require an explicit choice.

The normal end-user path does not involve an AI handoff, build-output folder,
or internal JSON filename:

1. A server maintainer ships `world-builder-provider/` in the server root.
2. The player puts `World Builder 2/` in that same root and chooses **Detect
   Server Map**. The provider is selected automatically without folder
   navigation.
3. If the maintainer distributes the provider separately, the player opens
   **Advanced / Recovery**, chooses **Detected Server Content Options…** or
   **Select Another Supported Source…**, then chooses the complete provider
   package. These file controls stay outside the normal end-user path.

The complete package is copied into the installation-local provider catalog
and remembered for that source. The player never needs to locate
`item-visuals-full-v1.json` inside a versioned package. **Advanced provider
import…** remains available for maintainers assembling a provider from loose
definitions and archives.

The launcher also accepts a read-only versioned provider package whose root
contains `package-manifest-v1.json`, one inventoried
`item-visuals-full-v1.json`, and role-labelled assets. This adapter verifies the
complete sorted package inventory, file sizes and SHA-256 values, catalog
binding, mapping selection, archive bindings, and external PNG bindings before
opening content. Producer paths such as `assets/archives/` and
`assets/external-png/` are normalized into the same internal evidence as the
simple layout; producer identity and source-path metadata remain inert and no
referenced source checkout is inspected.

For versioned mappings, `custom-sprite-archive`, `external-png`, and
`authentic-archive-fallback` are data roles rather than product-specific
behavior. A complete mapping may contain packaged and unrelated item records;
only IDs required by the selected target are materialized. A malformed package
or unusable individual record yields the established placeholder and warning
path instead of authorizing execution or target mutation.

The same package may include an inventory-bound `npc-definitions-v1.json` with
role `full-npc-definition-manifest` (and optional
`npc-definition-mapping-v1.schema.json` with role `npc-definition-schema`).
World Builder automatically consumes it from the selected package; the user
does not choose a second file. Missing or unusable NPC records become explicit
project-local placeholders with warnings while their IDs and placements remain
stable. Exact records use only captured declarative definitions and existing
animation assets; target code is never run for discovery.

After project creation, the desktop launcher presents a visible warning with
the exact placeholder NPC IDs when the selected provider lacks authoritative
records. A placeholder intentionally uses NPC 0's visual data and a
`[Missing NPC <id>]` name; it proves that placement identity was preserved, not
that the custom NPC was imported faithfully. The provider diagnostics path is
shown in the same dialog so a maintainer can supply a successor package and the
user can recreate the project without guessing what went wrong.

`npc-definitions-v1.json` may use either the normalized
`world-builder-npc-definition-mapping` contract or the richer
`world-builder-npc-definitions` producer contract. The latter includes exact
archive bindings and the complete animation closure for placed extension NPCs;
the Editor verifies and normalizes it automatically. Verification includes the
actual raw custom-OSAR entry hash and frame count, the runtime category/name
lookup, the renderer-required 15/18/27-frame shape, and every consecutive
authentic sprite payload hash. Missing, extra, aliased, colliding, malformed,
or hash-drifted animation evidence produces an explicit project-local NPC
placeholder and `NPC_ANIMATION_PLACEHOLDER` diagnostic rather than a later
client crash or invisible NPC. This keeps provider
production portable without requiring end users to translate server and client
definition shapes by hand.

Before a rich provider is consumed, World Builder binds it to the immutable
project-stage copy of the selected server. `NpcDefs.json`,
`NpcDefsCustom.json`, `MyWorldNpcLocs.json`, `Authentic_Sprites.orsc`, and
`Custom_Sprites.osar` must match the producer's exact SHA-256 evidence. The
declared sequential NPC boundary and the complete set of placed extension NPC
IDs must also agree. A stale or cross-server provider fails before project
publication with `CAPABILITY_MISMATCH` and directs the maintainer to regenerate
the server-root package. Unverifiable producer metadata is never silently
treated as compatible.

The ordinary distribution layout is a complete `world-builder-provider/`
directory in the server root. Explicit provider discovery already takes
priority, so an end user who selects that server receives the matching package
automatically and does not use the advanced package chooser. The chooser and
installation-local provider catalog remain recovery/developer paths for server
distributions which have not adopted the portable layout.

## Compatibility discovery

For existing OpenRSC installations, the launcher recognizes these neutral
asset roots when their expected archive or asset entries are present:

- `Cache/video/`;
- `client/Cache/video/`;
- `Client_Base/Cache/video/`;
- `builder-runtime/client/Cache/video/`;
- `server/conf/server/data/`; and
- `server/data/`.

Common definition roots include `server/conf/server/defs/`,
`server/data/definitions/`, `server/data/defs/`, `conf/server/defs/`, and
`data/definitions/`. Discovery also recognizes authentic and custom archives,
`spritepacks/`, and `external-items/` (including common underscore/item-folder
aliases). These are layout profiles, not product identities; none assumes a
particular game, server name, or repository.

One complete candidate can be preselected. If multiple cache or definition
roots are present, the launcher lists each candidate and requires guided import
instead of silently choosing one. Discovery reads regular files and folders
only. It never loads target classes, starts a target process, or changes the
selected source.

## Complete-package and advanced import

In **Create Isolated Project from Server Map**, prefer **Choose complete
provider package…** when a server maintainer supplied a
`world-builder-provider` folder. Select the folder, not an individual manifest.
World Builder validates its simple or versioned identity, locates the exact
mapping itself, copies it into the local provider catalog, and reuses it on
later launches.

Choose **Advanced provider import…** only to select any combination of:

- an existing neutral `item-visuals.json`;
- item-definition JSON or a definition folder;
- an authentic sprite archive;
- a custom sprite archive;
- a spritepacks folder; and
- an external item-PNG folder.

For automation, selecting a versioned package's inventoried
`item-visuals-full-v1.json` remains supported. The desktop flow deliberately
hides that implementation detail and accepts the complete folder instead.
Import copies the entire package byte-for-byte into the installation-local
provider catalog, so its manifest and relative asset bindings remain valid.

If no manifest exists, World Builder reads only `item` or `items` arrays with
integer `id` and string `name` fields. It creates deterministic sorted
`unresolved` records using the canonical mapping-v1 keys. This preserves each
item identity and deliberately selects the placeholder; it does not infer a
sprite ID from an item ID. A later provider can replace those records with
exact hashed visual evidence.

All selected inputs are bounded and copied into a new content-addressed folder
under the installation's `providers/` directory. The source is unchanged.
`providers/catalog.json` uses the strict
`local-provider-catalog-v2.schema.json` contract. Each association binds the
source path identity, the exact discovery/content-evidence fingerprint, and the
content-addressed provider fingerprint, and is published by an atomic
replacement. Importing the same source evidence and provider bytes again
produces the same provider ID and catalog bytes, so later launcher sessions can
select the local provider automatically.

Path identity alone never authorizes reuse. The evidence fingerprint combines
the canonical server discovery report with bounded hashes of recognized
definition and client-asset roots. If those bytes change, World Builder retains
the old immutable provider for recovery but labels the association **stale** and
regenerates from the currently recognized layout for a new project. A missing,
drifted, unsafe, or malformed provider/catalog is reported as **corrupt** and is
not opened or silently repaired. Catalog-v1 path-only records remain readable
only as non-authoritative stale history.

The desktop **Advanced / Recovery** menu exposes two bounded operations for the
automatically detected server. **Export Detected Server Diagnostics** writes a
content-addressed JSON report under `diagnostics/provider-cache/`. The report
contains the discovery fingerprint, cache state, summary, recognized profile
IDs, and present component roles, but deliberately contains no absolute source
or provider paths. Repeating an unchanged export returns the same file.

**Reset Detected Server Provider Cache** removes only that server's catalog
association. It requires an explicit confirmation, saves the exact prior
catalog under `diagnostics/provider-cache-recovery/`, and atomically publishes
the replacement catalog. If a regular catalog is malformed, the same recovery
action backs it up and replaces it with an empty valid catalog. Unsafe links,
special paths, and oversized catalogs remain untouched for manual inspection.
Projects and content-addressed provider folders are never removed; the next new
project regenerates from current read-only server evidence.

For recovery and automation, the same operations are available as:

```bash
java -jar builder-runtime/launcher/world-builder-tools.jar \
  discover-item-provider \
  --installation-root "/path/to/World Builder 2" \
  --source-root "/path/to/server"

java -jar builder-runtime/launcher/world-builder-tools.jar \
  import-item-provider \
  --installation-root "/path/to/World Builder 2" \
  --source-root "/path/to/server" \
  --definitions "/path/to/item-definitions" \
  --authentic-archive "/path/to/Authentic_Sprites.orsc" \
  --custom-archive "/path/to/Custom_Sprites.osar" \
  --spritepacks "/path/to/spritepacks" \
  --external-items "/path/to/external-items"

java -jar builder-runtime/launcher/world-builder-tools.jar \
  export-item-provider-diagnostic \
  --installation-root "/path/to/World Builder 2" \
  --source-root "/path/to/server"

java -jar builder-runtime/launcher/world-builder-tools.jar \
  reset-item-provider-cache \
  --installation-root "/path/to/World Builder 2" \
  --source-root "/path/to/server" \
  --confirm "RESET PROVIDER CACHE"
```

Either `--definitions` or `--item-visuals` is required for import. Every input
path is read-only; output is always installation-local.
