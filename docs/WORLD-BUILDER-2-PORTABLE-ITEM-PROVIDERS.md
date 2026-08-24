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

The normal end-user path does not involve an AI handoff, build-output folder,
or internal JSON filename:

1. A server maintainer ships `world-builder-provider/` in the server root.
2. The player puts `World Builder 2/` in that same root and chooses **Use
   Detected Server Map**. The provider is selected automatically.
3. If the maintainer distributes the provider separately, the player chooses
   **Select Another Supported Source**, then **Choose complete provider
   package…**, and selects the `world-builder-provider` folder itself.

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
`providers/catalog.json` binds the local source identity to the package and is
published by an atomic replacement. Importing the same bytes again produces
the same provider ID and catalog bytes, so later launcher sessions can select
the local provider automatically.

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
```

Either `--definitions` or `--item-visuals` is required for import. Every input
path is read-only; output is always installation-local.
