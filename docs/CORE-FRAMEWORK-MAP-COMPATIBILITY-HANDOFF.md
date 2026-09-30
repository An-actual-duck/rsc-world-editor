# Core-Framework map compatibility handoff

Prepared by the World Builder manager for the Core-Framework manager, September
30, 2026. This is a proposed implementation scope for owner review, not a report
that Core has been inspected, repaired, or approved for deployment.

## Objective

Bring Spoiled Milk onto a coherent, maintained Core server and matching player
client that support World Builder's current map features. Resolve its accumulated
runtime overlays and historical upgrades once, in Core's own source and build
process. Preserve custom gameplay, content, and state. World Builder must remain
a general tool without server-name checks, special NPC IDs, or permanent
exceptions for Spoiled Milk's update history.

The owner will coordinate this work with the Core manager. This handoff does not
authorize World Builder agents to inspect or modify the live Core checkout or
server. Core work and deployment follow Core's own contributor and owner rules.

## What is known

The owner tested World Builder `v0.8.1-alpha.6` and reported two refusals:

- **Upgrade Target Runtime:** `RUNTIME_UPGRADE_REQUIRED`, source
  `META-INF/LICENSE.txt`, message “Target archive is oversized or repeats an entry.”
- **Import Map Changes:** `RUNTIME_UPGRADE_REQUIRED`, reporting
  `server/world-builder-runtime/world-builder-managed-runtime.jar` as retired
  class-shadowing runtime content.

The Editor code confirms the first message combines two conditions: cumulative
expanded archive content exceeding 256 MiB, or a repeated entry name. A repeated
license file is plausible but is **not established by the report**. The message
does not identify the containing archive. Core should determine the exact archive,
entry occurrences, byte contents, and expanded size before selecting a remedy.

The second refusal checks for the old overlay file's presence. It does not prove
the live launch currently loads it. Such overlays can replace target-owned
`Player`, `Skills`, `Inventory`, `World`, `Mob`, `Npc`, `ActionSender`, and
`OpcodeOut` classes. The targeted upgrader also refuses unresolved shadow
archives, so fixing the first error alone will not establish compatibility.

Custom-content discovery and visuals were accepted by the owner before this
test. That acceptance covers the editor's representation, not the target's
upgrade or gameplay behavior. No live Core files were examined for this brief.

## Core implementation sequence

### Establish the actual baseline

Work first in a disposable environment under Core management. Record the source
revision, selected configuration, server and player-client artifacts, dependency
versions, launch classpath order, and normal rebuild process. Determine which
archives actually supply the affected classes and whether their active bytecode
matches the maintained source. Include plugins and client dependencies.

Inventory historical overlays, receipts, installed profiles, map locations, and
build-skip guards. Preserve exact backups and a restorable baseline, including
maps, placements, configuration, and player state. Keep credentials and private
state out of any handoff to the World Builder team.

### Consolidate behavior into maintained source

For each active overlay or replacement, identify the behavior it contributes.
Integrate required map features and intended custom behavior into Core's source;
explicitly account for any obsolete behavior being retired. Do not simply delete
the shadow JAR or replace the game with World Builder's private runtime.

Remove obsolete classpath injection and build guards only after the replacement
implementation is verified. Check these known historical locations where present:

- `server/world-builder-runtime/world-builder-managed-runtime.jar`
- `server/lib/world-builder-managed-runtime.jar`
- `server/core-gameplay-overlay.jar`

Archive retired material outside active runtime paths. Normal builds, launchers,
and deployment scripts must not recreate or reactivate it. Retain historical
transaction evidence needed for recovery; do not erase receipts to silence checks.

### Produce unambiguous archives

Investigate the reported archive failure in the build that creates it. Preserve
dependency license notices while giving packaged entries unique paths or an
appropriate combined notice. Distinguish identical metadata from conflicting
resources, classes, manifests, or service registrations; do not apply blanket
duplicate removal. If the actual cause is expanded size, document it separately.
Do not strip necessary content merely to get below a limit.

Verify that a normal clean build reproduces corrected artifacts. An ad hoc edit
to an installed JAR would be undone by the next build and is not the desired fix.

### Integrate the current map requirements

Use the exact provider contract below as a source-level integration reference.
Review mixed gameplay/map classes individually; a map-related filename does not
make replacing the whole class safe. Keep Core's definitions, appearance loaders,
dialogue, quests, item effects, object interactions, plugins, and unrelated
configuration authoritative.

The paired server/client implementation needs signed layers, supported map
encodings through v5, terrain and placement activation, unsigned 16-bit elevation
transport, compatible collision/void behavior, and standard floor materials.
Rebuild every affected field/protocol consumer, including plugins; avoid elevation
truncation or source changes that leave old binary consumers active. Preserve
custom collision rules when integrating common map behavior.

Floor additions must preserve existing IDs and definitions and agree with the
client's floor interpretation. Inventory existing coordinate conventions and
map references before conversion. Migrate known placements, spawn bounds,
teleports, and other affected references consistently. Report unknown custom
coordinate consumers instead of assuming a vertical offset conversion covers
them. Preserve saved World Builder projects and edits as separate user data.

### Establish verified Editor recognition

Runtime behavior and Editor recognition must both be proven. The current Editor
has one reviewed source adapter, `native-layered-v4-source-v1`; manually installing
equivalent current code does **not** automatically make a target admissible.

Prefer having Core's consolidated build pass the existing reviewed upgrade path
on the disposable target. If it cannot, return the specific source, ABI, or
verification mismatch to the World Builder manager. A reusable current-runtime
verification or adoption mechanism may need coordinated provider/Editor work.
It must prove the common contract, not identify Spoiled Milk by name or waive
custom-content preservation.

Do not hand-author compatibility receipts or copy a project's capability JSON
into the target to force acceptance. The installed targeted proof binds the
reviewed descriptor and source/archive hashes. A subsequent Core rebuild can
invalidate those hashes even when behavior is intended to be unchanged. Agree
and test the supported re-verification workflow before calling this maintenance
compatible going forward. That workflow is a completion condition, not an
assumption that alpha.6 already handles arbitrary rebuilt Core binaries.

## Acceptance before deployment

Record results for the disposable target and its matching player client:

1. A normal build and restart use the intended classes with no retired shadow
   implementation or hidden dependence on editor binaries.
2. Custom NPC visuals and interactions, custom item effects, object interactions,
   plugin registration, and representative overridden baseline IDs still work.
   Naga may be one example; it must not become a special implementation case.
3. World Builder discovers content, verifies or upgrades map compatibility,
   imports an edited map, and successfully performs a second map-only import.
   Those imports leave gameplay binaries, unrelated definitions, and assets
   unchanged. Captured editor visual data does not leak into target definitions.
4. Layer changes, wide elevations, floor appearance/walkability, placements,
   spawn bounds, and migrated map references behave correctly after restart.
5. Rebuilding Core again has a demonstrated path back to verified map import.
   Source or archive drift is detected rather than silently trusted.
6. Failed/interrupted upgrade or import restores the exact prior state or enters
   recoverable interruption handling. A deployment backup and matching client
   distribution plan are ready before owner-authorized rollout.

Return a concise report with the baseline, root causes, exact reviewed commits,
retained/retired behavior, changed map integration points, test evidence, remaining
gaps, and any generic provider/Editor requirement. Do not send player databases,
credentials, or unrelated private content.

## Reference versions and ownership

- Test candidate: `v0.8.1-alpha.6`, located at
  `/home/justin/world-builder-test-builds/targeted-runtime-v0.8.1-alpha.6/`.
- Candidate Editor source: `9df39e131fe4f67f1c52074cc0658e2eca5ca1b1`.
- Locked provider source: `deb55301702dc80f49497ac722341895145363e1` in the independent
  `/home/justin/rsc-world-editor-runtime` repository. Read the descriptor at
  `server/conf/world-builder/target-map-integration-v1.json` at that revision.
- Contract: integration `target-owned-layered-map-v1`, loader
  `generic-signed-layered-loader-v7-blocking-base-color`, protocol
  `world-builder-native-layered-protocol-v2-u16-elevation`, encodings 1–5.
- Editor implementation:
  [target integration consumer](../tools/world-builder/src/com/openrsc/worldbuilder/WorldBuilderTargetMapIntegration.java)
  and [runtime compatibility checks](../tools/world-builder/src/com/openrsc/worldbuilder/WorldBuilderRuntimeCompatibility.java).
- Product boundary and test limits:
  [targeted upgrade contract](WORLD-BUILDER-TARGETED-MAP-UPGRADES.md) and
  [integration verification](WORLD-BUILDER-TARGETED-INTEGRATION-TESTS.md).

Core owns its one-time consolidation and game validation. World Builder owns
general archive diagnostics/handling, clearer unsupported-cleanup guidance, and
any reusable compatibility verification improvements. Those Editor follow-ups
are proposed work, not fixes already included in alpha.6. Original Preservation
still needs its own adapter; this Core handoff must not become that public path.
