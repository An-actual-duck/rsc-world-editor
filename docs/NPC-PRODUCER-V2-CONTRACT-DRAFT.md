# Effective NPC visual producer v2 — review contract

Status: proposed, implementation pending manager/Core contract review. Scope is the
maintained OpenRSC NPC registry and existing World Builder RGB presentation
capability. This is not a general target-code interpreter.

## Authority and completeness

The maintainer's build-time exporter reads the final effective client NPC and
animation registries under the selected server configuration and explicit client
flags. It exports all effective server NPC IDs, including unplaced NPCs and IDs
below the preservation boundary. It must capture final client overrides, not
substitute server sprite vectors or infer vectors by NPC name.

World Builder consumes inert data and verifies bindings; it never runs target
Java, the exporter, or a target build. Server effective definitions remain the
only authority for NPC IDs, names, commands, statistics, and gameplay. Producer
rows contain presentation only. No placement counts or placement-file hashes
participate in the new contract. Changing the map cannot invalidate its content
library. A complete producer requires exact set equality with effective server
NPC IDs; a missing client NPC or unresolved frame is an export/refusal error,
not a fabricated placeholder or a remapped server identity.

Source hashes establish freshness against captured target evidence, not proof
that arbitrary code was executed correctly. A maintained exporter must have
producer-side tests against the actual final registry and active frame resolver.
Editor accepts the bounded declared renderer profile, verifies the full source,
configuration, ID and asset closure, and retains this provenance. Unrecognized
renderer semantics require an actionable refusal; extra hashes cannot authorize
an arbitrary renderer or gameplay replacement.

## Proposed exact document shape

Keep `manifestType: "world-builder-npc-definitions"`; distinguish with
`schemaVersion: 2`. Packaging selects exactly one
`full-npc-definition-manifest` role. Proposed new filename is
`npc-definitions-v2.json`; legacy v1 discovery remains supported. Ambiguous two
active NPC manifests are refused rather than chosen by filename order.

```json
{
  "schemaVersion": 2,
  "manifestType": "world-builder-npc-definitions",
  "provider": {
    "identity": "maintainer-defined descriptive identifier",
    "rendererProfile": "openrsc-npc-layered-rgb-v1",
    "configuration": {"relativePath": "server/myworld.conf", "sha256": "..."},
    "clientFlags": {
      "Config.S_WANT_CUSTOM_SPRITES": true,
      "Config.S_ALLOW_BEARDED_LADIES": false
    },
    "sources": [
      {"role": "effective-npc-definition", "relativePath": "server/conf/server/defs/NpcDefs.json", "sha256": "..."},
      {"role": "server-npc-loader", "relativePath": "server/src/com/openrsc/server/external/EntityHandler.java", "sha256": "..."},
      {"role": "client-npc-loader", "relativePath": "Client_Base/src/com/openrsc/client/entityhandling/EntityHandler.java", "sha256": "..."},
      {"role": "client-frame-resolver", "relativePath": "Client_Base/src/orsc/graphics/two/GraphicsController.java", "sha256": "..."}
    ]
  },
  "selection": {"kind": "complete-effective-server-npc-catalog", "npcIds": [0, 1]},
  "assetProviders": [
    {"assetId": "custom", "format": "openrsc-osar-v1", "targetRelativePath": "Client_Base/Cache/video/Custom_Sprites.osar", "packageRelativePath": "Custom_Sprites.osar", "sha256": "..."}
  ],
  "npcDefinitions": [
    {"npcId": 0, "spriteAnimationIds": [0, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1],
     "hairColour": 0, "topColour": 0, "bottomColour": 0, "skinColour": 0,
     "cameraWidth": 145, "cameraHeight": 200, "walkModel": 4, "combatModel": 4, "combatSprite": 1}
  ],
  "animationDefinitions": [
    {"animationId": 0, "name": "head1", "category": "player",
     "charColour": 1, "blueMask": 0, "genderModel": 13,
     "hasCombatFrames": true, "hasSpecialCombatFrames": false, "requiredFrameCount": 18,
     "frames": {"kind": "osar-entry", "assetId": "custom", "subspace": "player", "entry": "head1", "entrySha256": "..."}}
  ]
}
```

The example arrays are abbreviated; a real complete document must contain all
selected IDs and the complete animation dependency closure. IDs are integers
0..65535; empty slots use -1. NPC and animation rows and selection IDs are sorted
and unique. Every vector contains exactly twelve slots. Referenced animation IDs
must equal animation rows, with no missing or unused animation rows. Animation
flags require exactly 15, 18, or 27 frames; special frames require combat frames.
No names, stats, descriptions, commands, or alternative NPC IDs are accepted in
v2 presentation rows. Unknown keys are rejected.

`frames` is a tagged union. Initially:

- `osar-entry`: exact object above. Palette-indexed OSAR entry, including all
  frames and headers, is hashed using the existing Editor logical entry digest.
- `authentic-frames`: `{kind,assetId,baseSpriteId,entrySha256s}`. The asset format
  is `openrsc-authentic-zip-v1`; IDs are consecutive from the given base, hashes
  cover exact raw frame payloads, and the array has requiredFrameCount elements.

Only the actually selected frame mode is required. In particular an OSAR-only
animation under custom sprites does not claim an authentic fallback that does
not exist. Initial profile excludes remastered/procedural sprite substitution
and undeclared spritepack precedence. If a selected spritepack overrides an
entry, the exporter must declare the resolved asset and the supported ordered
resolution evidence; until that profile is implemented, the consumer refuses it.
Existing source-bound direction-sheet discovery remains a separate supported
route and cannot silently override conflicting v2 slots.

### Bindings and captured dependencies

- `configuration` names the exact selected original path, not a different file
  with the same bytes. Canonical project aliases retain original source evidence.
- Include each effective configuration input in `sources` with role
  `configuration-input`; flags must equal the bounded effective configuration
  adapter. Client-only flag sources must be explicit supported inputs. Omitted
  relevant flags, external state, or unsupported overrides are refused.
- `effective-npc-definition` includes every active base, append, and overlay
  source in established load order. The consumer independently verifies this
  ordered set against effective-source/composition discovery. Inactive files
  cannot supply an ID or override.
- Include the maintained server loader, client final registry loader, frame
  resolver, and additional client visual source modules used by the exporter.
  Roles have bounded recognized roots; all paths are contained regular files,
  no links, traversal, executable invocation, or arbitrary filesystem capture.
  This inventory must be discoverable before project snapshot creation.
- Asset bytes must match both package inventory and the corresponding captured
  target path. Asset IDs are unique; each used source archive is inventoried.
  Source and asset inventories are immutable parts of the sealed bundle.
- Whole source/archive hashes establish capture freshness. Per-definition visual
  identity uses resolved frames, masks, dimensions and ordered slots, so adding
  unrelated assets or definitions does not change all old visual signatures.
- No producer hash refresh is permitted. A stale source/config/asset binding
  requires a new maintained export. Existing v1 packages remain historical
  evidence, without being promoted to a complete v2 claim.

## Consumer implementation route

1. Discover the unique typed v2 manifest and its bounded proof dependencies;
   capture original evidence before verification. Keep v1 compatibility isolated.
2. Validate source/config/asset closure and exact effective target NPC set.
   Read final client visual vectors exclusively from v2 rows, including vanilla
   overrides. Do not use name matching or maximum-declarative-ID heuristics.
3. Decode each selected animation into lossless private RGB frames. Preserve
   palette-expanded 24-bit pixels, zero transparency, dimensions, explicit shift
   bit, signed offsets and full bounds. Do not pre-apply tint masks.
4. Allocate project-only RGB animation IDs and frame slots collision-safely using
   existing private runtime capability bounds. Preserve original target animation
   IDs as provenance; rewrite only private NPC sprite references. NPC placement
   IDs and target archives are never remapped or overwritten.
5. Apply a whitelisted visual overlay at any existing effective NPC ID. Preserve
   server names/stats/commands and source definitions. Retain exact closure in
   project diagnostics and the immutable content bundle. Conflicting v2 and
   direction-sheet claims require equality of resolved frames and masks or a
   refusal, never last-writer-wins.
6. Index semantic and visual identity separately. For NPCs exclude the established
   presentation fields (12 sprite slots, four colors, camera width/height,
   walkModel/combatModel/combatSprite) from the semantic hash on both historical
   and fresh bundles. Preserve target ID/name/gameplay semantics. Leave collision
   geometry and other families' identity rules unchanged.
7. Visual identity includes all excluded presentation inputs: ordered twelve
   sprite slots (including duplicates and empty slots), hair/top/bottom/skin
   palettes, camera dimensions, walk/combat selectors, and combatSprite. For each
   slot hash resolved ordered RGB pixels/headers and renderer-used masks/flags,
   excluding private allocation IDs, frame bases, and generated animation names. Those are addressing/provenance, not visual changes. Missing
   to resolved appearance is a reviewed visual update, not a semantic blocker.
8. Run normal preview-bound Detect New Content successor revision publication,
   preserving saved work and all old evidence. Map import still validates target
   catalog support independently and never installs private presentation data.

## OSAR normalization finding

No new runtime gameplay behavior appears necessary. Existing locked runtime
`ProjectNpcAnimationRegistry` supports `authentic-rgb` frames for private animation
IDs >=1080. `GraphicsController.spriteSelect(AnimationDef,int)` returns those
frames before the custom/authentic branch; NPC layer composition and mask
application remain shared. OSAR `Unpacker.readEntry` expands palette bytes into
24-bit pixels and retains the explicit frame geometry. The existing RGB payload
can represent those values exactly.

Do not reuse the current PNG direction-sheet `frame()` encoder unchanged: it
turns opaque black into 0x010101 and derives a shift bit, whereas OSAR preservation
must retain raw zero and the original bit. Add a strict OSAR decoder/encoder
route and prove parity with the maintained decoder and actual shared renderer.
Bounds beyond the supported RGB decoder budget must refuse before publication.
A runtime provider change is warranted only if these parity tests identify an
actual unrepresentable frame/render behavior; do not guess or force acceptance.

Source references at runtime lock 236d47bf4660605cc4f2482769eb54c21f990cae:
`Client_Base/src/orsc/graphics/two/SpriteArchive/Unpacker.java`,
`Client_Base/src/orsc/ProjectNpcAnimationRegistry.java`,
`Client_Base/src/orsc/graphics/two/GraphicsController.java:1505`, and
`Client_Base/src/orsc/mudclient.java:20943`.

## Required regression and product acceptance

- Complete unplaced library, add new NPC with no placements, place/remove last
  instance, rediscover, reopen, and repeated imports without producer regeneration.
- Existing vanilla ID with a different final client vector: visual changes,
  server ID/name/stats/commands unchanged; no new fake extension or placeholder.
- Final client vectors intentionally differ from server vectors, including two
  similar names, proving no inferred name/index match.
- Legacy saved project to v2 content refresh: unresolved14-style dependencies
  become resolved visual updates; saved map edits and historical files persist.
- OSAR-only custom mode with no authentic counterpart; exact pixel/mask/shift,
  offset and bounds parity for 15/18/27 frames, grayscale and blue masking,
  transparency, mirrored directions and combat frame selection.
- False/custom flag mismatch, stale sources, selected config mismatch, missing
  source dependencies, inactive definition source, duplicate/missing IDs,
  partial closure, corrupt OSAR/palette/frame budgets, unsupported frame resolver
  or spritepack, and preview-to-apply asset changes all refuse before mutation.
- Reorder slots, repeat a slot, and change only each NPC palette/camera/animation
  selector: each changes the visual signature without changing semantic identity.
  Change name, command, or statistic: semantic conflict remains blocked.
- Adding one animation cannot change existing NPC signatures because private
  addresses moved. Adding unrelated archive entries preserves old signatures.
- Target files remain unchanged during discovery/capture/refresh; final imports
  touch only map transaction destinations. No authoring vector, normalized frame
  archive, or provider definition is written over target-owned content.

Producer acceptance must compare v2 vectors and decoded selected frames against
actual final client registries for the selected target build/profile. Editor
acceptance validates inert consumption, appearance parity, saved work, and the
complete lifecycle. Neither side substitutes a successful helper test for both.
