# Targeted map upgrade acceptance contract

Owner direction, 2026-09-30. This supersedes broader game-composition adoption
requirements where they conflict with preserving the target's custom game.
Status: scope and initial code audit recorded; implementation compliance is not
yet established. Alpha.4 verifies visual discovery, not this upgrade contract.

## Boundary

The authoring runtime may simulate only what building requires. It must display
buildable content and retain target identities without needing to implement
custom dialogue, combat, quests, drops, or item effects.

Target upgrades install map-loader compatibility and necessary integration.
Existing target definitions, appearance loaders, assets, interaction handlers,
plugins, and unrelated configuration remain authoritative. An editor-only visual
override must not replace a target NPC definition or its client activation code.
A replaced binary must retain target custom behavior; retaining its old source
or backing up the binary is insufficient.

Map return carries terrain and placements (including IDs, directions, layers,
and bounds) and explicitly required map metadata. New materials must not collide
with existing IDs. Supported coordinate and spawn migrations should happen
automatically and consistently. Unknown custom coordinate consumers need a
specific unresolved report before dependent changes, not a guessed translation.

## Initial audit

- `WorldBuilderRuntimeCompatibility.prepareTargetUpgrade` replaces complete
  server/client JARs from the project's runtime, installs capability evidence,
  and applies host/build integration and floor changes. Its small action list
  does not establish a small semantic impact on customized games.
- `WorldBuilderCurrentRuntimeUpgradeTransaction` preserves preimages and
  activates an independent current bundle. Preservation of original files does
  not demonstrate preservation of active custom behavior.
- `WorldBuilderCurrentRuntimeMapImport` retains code/state paths while changing
  map packages and activation metadata, but requires an exact selected Base
  composition. A separate map transaction is useful; that composition prerequisite
  must be reconsidered under the narrower map-capability requirement.

No target was mutated during this audit. These are identified architecture gaps,
not evidence that every current upgrade necessarily breaks custom content.

## Implementation sequence and acceptance

1. Inventory every reachable upgrade/import action and its effective runtime
   dependencies. Classify each as map data, map integration, or unrelated game
   behavior. Trace editor visual overlays through export to target activation.
2. Define a shared map capability boundary with target-owned content resolution.
   Select the integration mechanism from the audit; do not assume swapping a
   generic JAR, copying a few classes, or arbitrary automatic source patching
   preserves custom behavior. Keep necessary client and server changes paired.
3. Implement reviewed adapters that preserve the target's custom implementation
   while upgrading loader behavior. Keep map-only import independently usable
   once that capability is installed. Report unsupported integration precisely.
4. In disposable fixtures, upgrade, import, restart, and rebuild where source is
   supported. Verify custom NPC appearance and dialogue, custom item behavior,
   object interactions, plugin registration, and unchanged unrelated configuration.
   Include overrides of baseline IDs and different custom content at reused IDs.
5. Verify coordinate/placement migration, new map features, stable identities,
   explicit material additions, drift refusal, backups, failure rollback, and
   interrupted recovery. Compare active behavior as well as untouched file hashes.

The acceptance example is straightforward: moving a custom NPC changes its map
location; its appearance and dialogue still come from the target's original
custom implementation. Visual-discovery tests alone cannot establish that result.
