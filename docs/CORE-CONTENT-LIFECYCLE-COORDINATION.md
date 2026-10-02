# Maintained game content export and lifecycle acceptance

Implementation coordination for the October 2 content lifecycle objective.
This is a technical contract for the Core manager, not authorization to mutate
a normal checkout, shared test target, private database, or public deployment.
Editor implementation and verification status are tracked in
[Content Lifecycle Delivery](WORLD-BUILDER-CONTENT-LIFECYCLE.md).

## Maintained content authority

Generate content evidence from the effective server/client definition setup as
part of the maintained build/readiness workflow. Include unplaced definitions,
vanilla-ID overrides, selected patches, and all required presentation inputs.
Do not infer active content by enumerating similarly named files. In particular,
the supported OpenRSC NPC loader assigns array IDs by append order; JSON `id`
fields alone do not establish the live game identity.

The Editor's new optional inert descriptor contract is:

```json
{
  "schemaVersion": 1,
  "manifestType": "world-builder-effective-content-sources",
  "configuration": {
    "relativePath": "server/myworld.conf",
    "sha256": "<SHA-256 of the selected configuration>"
  },
  "npcRegistry": {
    "semantics": "openrsc-sequential-append-v1",
    "sources": [
      {"relativePath": "server/conf/server/defs/NpcDefs.json", "sha256": "<SHA-256>"},
      {"relativePath": "server/conf/server/defs/NpcDefsCustom.json", "sha256": "<SHA-256>"}
    ]
  }
}
```

Destination: `server/conf/world-builder/effective-content-sources-v1.json`.
Append all active supplemental NPC registries in their exact live append order.
The first two sources are the selected layout's base and custom registries.
All supplemental paths must be bounded direct children of that definition root.
Any declared record ID must agree with its resulting array index. Existing
configuration-selected patch/world overlays continue through the recognized
composition rules; this descriptor cannot redefine their semantics. Unknown
schemas or executable/generated behavior require a supported inert export
adapter, not executing target code during Editor discovery.

An optional sibling `itemRegistry` uses the same ordered `sources` records and
`semantics: "openrsc-id-overwrite-v1"`. Its first two files are the selected
layout's `ItemDefs.json` and `ItemDefsCustom.json`. The supported item loader
indexes complete definitions by their explicit IDs, with later loaded records
replacing earlier records. It does not use the NPC append-index rule. Include
every actively loaded supplemental item file and retain separately selected
patch/world overlay behavior. The current private fixture demonstrated a real
omission here: supplemental items had valid exported visuals but were missing
from the editor's definition library.

The remaining families retain their existing bounded definition formats:
tile and boundary XML, scenery XML/models, and verified item/NPC visual metadata
with sprites/animations/textures. This descriptor selects supported NPC/item
source compositions; it does not claim arbitrary engine support or new gameplay
authority.

Generate both installed placement catalogs from the same effective content.
Validate their agreement with the current loader contract and resolved client
support before marking a build/import target ready. A stale-catalog check must
fail when sources, ordering, selected configuration, or visual dependencies
change without regeneration. Counts and particular NPC IDs are observations,
not constants. Existing source-bound producer exports must be regenerated when
their declared inputs change; an old cached export is not refreshed by editing
its recorded hashes.

Editor refresh writes only a new project content revision and selection. It
does not generate or install target gameplay definitions. Any required Core
catalog/export changes belong to Core's reviewed maintained workflow and must
remain separate from map import.

## Coordinated private acceptance

Use a new isolated copy of the maintained game and a complete copied project.
Preserve the current edited active map; do not revert to an older baseline.
Retain shared/private test databases untouched and create disposable test data
for any new session. Record exact source/build inputs and before inventories.

1. Verify the maintained export and paired catalogs are current.
2. Run packaged desktop detect/create; verify never-placed custom content and
   an overridden vanilla identity with their actual visuals.
3. Place representative custom NPCs, collision-bearing scenery, and a visible
   terrain change. Save, export, preview and import while the target is offline.
4. Rediscover, reopen and repeat with another changed export. Check each export
   differs from the installed package before claiming an edited-map test.
5. Add one supported inert content definition/dependency through the maintained
   build/export workflow. Detect New Content in the existing edited project,
   accept the additive revision, place it, import and reopen. Prior map edits
   and history must remain available. A used-ID conflict must be refused.
6. Perform the supported normal rebuild/re-verification sequence and another
   import. Preserve rebuilt archives and gameplay.
7. In a private maintained server/player-client session, inspect the imported
   coordinates, floors, visuals and collision. Core verifies Naga melee/ranged
   behavior, another status-effect enemy, custom equipment and persistence.
8. Compare exact expected writes and retained hashes for custom content, game
   sources/archives, unrelated configuration, account data and project history.

Core owns actual gameplay acceptance and subsequent integration into its normal
maintained checkout. Do not copy temporary fixture activations or target-specific
transaction proofs blindly into normal Core. Editor will provide exact tested
commits, candidate/checksums, project selection and coordinates with the final
consolidated handoff. No public activation is part of this work.
