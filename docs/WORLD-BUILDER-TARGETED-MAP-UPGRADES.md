# Targeted map upgrade acceptance contract

Owner direction, 2026-09-30. This supersedes broader game-composition adoption
requirements where they conflict with preserving the target's custom game.
Status: the first targeted source integration is implemented for the reviewed
older native layered lineage. Original Preservation still needs a separate
adapter. Alpha.5 visual acceptance does not certify the return-to-server path;
the targeted implementation has separate synthetic integration and transaction
coverage described below.

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

## Active implementation (2026-09-30)

The product manager assigned paired work in the independent Editor and runtime
repositories. The chosen mechanism is a provider-owned, versioned map-source
integration descriptor with bounded source edits, accepted preimages for whole
map-only source replacements, and explicit integration requirements. Mixed
gameplay/map classes retain unrelated target code. Changes are compiled in
isolation against target dependencies without executing target build scripts or
annotation processors. Map ABI changes require coherent consumer/plugin rebuilds;
unknown binary-only consumers must not be silently treated as compatible.

The first adapter covers an older native layered loader. Preservation needs its
own reviewed source integration adapter; the old complete-composition migration
is not an acceptable fallback. Runtime and Editor remain separate repositories.
Target copies are evidence inputs only, never development or live test targets.

Known critical details from the audit:

- Old upgrade code disables normal target core rebuilding and substitutes the
  editor binaries. That behavior must leave the targeted upgrade route.
- Custom projectile and enemy-fence collision logic exists inside map-related
  classes. A map-related filename does not justify replacing the whole class.
- Wide elevation affects field consumers and cannot be fixed by quietly
  truncating values to preserve an old byte field.
- Map-only adaptive export already excludes captured NPC appearance overrides;
  regression coverage must retain that separation.
- Appended floor materials must preserve the complete existing floor prefix.
- Bundled authoring JREs lack the actual Java compiler despite listing it in
  release metadata. Upgrades need verified compiler-capable runtimes on both
  shipped platforms, with native Windows acceptance distinguished from offline
  inventory checks.

The implementation must retain the current offline/preview/exact-confirmation/
backup/verification/rollback/recovery contracts. Existing project edits must
survive adoption of a newer upgrade payload. No target mutation is authorized by
this implementation assignment.

## Implemented boundary

The current application embeds the exact locked provider's source descriptor.
It compiles bounded map edits against the target's own dependencies, checks
original source against active bytecode, and rebuilds affected plugin consumers.
Unrelated archive entries retain their exact bytes. Unknown hooks, stale source,
class shadowing, and binary-only map dependencies cause a pre-mutation refusal.
The previous whole-game composition upgrade is disabled at public entry points;
historical recovery and existing compatible map-only imports remain available.

Generated source and archive bytes are included in transaction evidence, so
recovery uses the exact reviewed output without recompiling. Input inventories
and hashes bind preview to apply. Floor additions preserve the existing server
definition bytes and must agree with the client's existing floor prefix.

The provider adapter and the Editor consumer have been tested on disposable
reconstructed and synthetic hosts, including v5 maps, elevation 65535, retained
custom callbacks/resources/content IDs, plugin linkage, map-only import, failure
rollback, and interrupted recovery. This is not an execution test of the owner's
server, a native Windows acceptance, or a production release gate. See
[targeted integration verification](WORLD-BUILDER-TARGETED-INTEGRATION-TESTS.md)
for commands and fixture limits. No owner target was changed or launched.
