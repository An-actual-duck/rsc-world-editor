# Effective NPC visual producer v2 — supported contract

Status: closed producer/consumer agreement, implemented and tested against the
maintained final-initialization exporter. The historical filename is retained for
existing handoff links. Scope is the maintained OpenRSC NPC registry and World
Builder RGB presentation capability, not a general target-code interpreter.

The independent real producer capture contained 877 available NPCs, 249 animation
records and 4,521 resolved source frames. Editor capture verified all of those
records, preserved exact ordered NPC vectors and presentation values, and emitted
4,509 byte-identical authoring frames with zero unresolved NPC dependencies. Eight
NPCs declare cadence limitations; four also retain unpreviewed secondary-attack
frames. Those counts characterize the acceptance input, not product constants.

## Authority and completeness

The maintainer's build-time exporter captures after the selected branch's entire
client sprite initialization, under the selected server configuration and explicit
client flags. EntityHandler.load is not that boundary: active spritepacks and
external sprite loaders can subsequently mutate NPC vectors, conditionally on PNG
load success. Capture the actual final NPC vectors and selected spriteSelect frames
after all supported loaders complete; a registry-table snapshot is insufficient. It exports all effective server NPC IDs, including unplaced NPCs and IDs
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

## Exact document shape

Keep `manifestType: "world-builder-npc-definitions"`; distinguish with
`schemaVersion: 2`. The active producer filename is `npc-definitions-v2.json`,
discovered under either `world-builder-provider/` or `server/conf/world-builder/`.
Exactly one active v2 manifest is allowed. Legacy v1 discovery remains supported;
a historical generated v1 cache is superseded by the verified complete v2 source,
without deleting that cache. Two active complete producers are refused rather
than chosen by filename order.

```json
{
  "schemaVersion": 2,
  "manifestType": "world-builder-npc-definitions",
  "provider": {
    "identity": "maintainer-defined descriptive identifier",
    "rendererProfile": "openrsc-effective-npc-preview-v1",
    "capturePhase": "after-selected-client-sprite-initialization-v1",
    "spriteBranch": "custom",
    "configuration": {"relativePath": "server/myworld.conf", "sha256": "..."},
    "clientFlags": {
      "Config.S_WANT_CUSTOM_SPRITES": true,
      "Config.S_ALLOW_BEARDED_LADIES": false
    },
    "sources": [
      {"sourceId": "npc-base", "role": "effective-npc-definition", "relativePath": "server/conf/server/defs/NpcDefs.json", "sha256": "..."},
      {"sourceId": "server-loader", "role": "server-npc-loader", "relativePath": "server/src/com/openrsc/server/external/EntityHandler.java", "sha256": "..."},
      {"sourceId": "client-loader", "role": "client-npc-loader", "relativePath": "Client_Base/src/com/openrsc/client/entityhandling/EntityHandler.java", "sha256": "..."},
      {"sourceId": "frame-resolver", "role": "client-frame-resolver", "relativePath": "Client_Base/src/orsc/graphics/two/GraphicsController.java", "sha256": "..."}
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
     "hasCombatFrames": true, "hasSpecialCombatFrames": false, "resolvedFrameCount": 18,
     "npcMaskPolicy": "hair-and-skin",
     "authoringPreview": {"behavior": "generic-layered-preview-v1", "hasCombatFrames": true, "hasSpecialCombatFrames": false,
       "frameIndices": [0,1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17], "limitations": []},
     "resolution": {"resolverSourceId": "frame-resolver", "resolverSourceSha256": "...", "inputSourceIds": ["frame-resolver"], "precedenceSourceIds": ["frame-resolver"]},
     "frames": {"kind": "osar-entry", "assetId": "custom", "subspace": "player", "entry": "head1", "entrySha256": "..."}}
  ]
}
```

The example arrays are abbreviated; a real complete document must contain all
selected IDs and the complete animation dependency closure. IDs are integers
0..65535; empty slots use -1. walkModel must be positive (renderer division); dimensions and other animation
selectors must satisfy the supported renderer bounds. NPC and animation rows and selection IDs are sorted
and unique. Every vector contains exactly twelve slots. Referenced animation IDs
must equal animation rows, with no missing or unused animation rows. Animation
preview flags require exactly 15, 18, or 27 selected authoring frame indices;
special preview frames require combat preview frames and reachable offsets18..26. resolvedFrameCount records
the complete actual source frame inventory and can differ (for example21). Each
frame index addresses that complete ordered inventory. Source hasCombatFrames and
hasSpecialCombatFrames remain captured evidence; they are not rewritten to invent
nine F frames from three additional attack frames.
No names, stats, descriptions, commands, or alternative NPC IDs are accepted in
v2 presentation rows. Unknown keys are rejected.

`frames` is a tagged union. Initially:

- `osar-entry`: exact object above. Palette-indexed OSAR entry, including all
  frames and headers, is hashed using the existing Editor logical entry digest.
- `resolved-rgb`: `{kind,assetId,frameKeys,frameSha256s}`. Asset format is
  `world-builder-rgb-frame-zip-v1`, a deterministic ZIP of raw existing private
  RGB frame payloads. Keys are contained portable relative paths, sorted archive
  inventory; frameKeys is ordered by effective source frame index and may repeat
  an entry. Every entry is used by at least one animation; no undeclared payloads.
  Both arrays have exactly resolvedFrameCount entries. The archive is a
  maintainer-generated target evidence artifact, bound by targetRelativePath and
  packageRelativePath plus SHA. It is not an invented authentic fallback.
  Payloads must be final spriteSelect Sprite state, including its actual shift and
  logical frame bounds, not raw PNG bytes or an image crop approximation.
  Raw payload is big-endian width:i32, height:i32, shift:u8, offsetX:i32,
  offsetY:i32, boundWidth:i32, boundHeight:i32, then width*height RGB:i32 values.
  Values are 24-bit; zero is transparent. Preserve explicit shift/offset/bounds.
  Existing runtime bounds apply (dimensions/bounds1..4096, offsets-4096..4096,
  <=16MiB per frame and256MiB total decoded private frame budget).
- `authentic-frames`: `{kind,assetId,baseSpriteId,entrySha256s}`. The asset format
  is `openrsc-authentic-zip-v1`; IDs are consecutive from the given base, hashes
  cover exact raw frame payloads, and the array has resolvedFrameCount elements.

Only the actually selected frame mode is required. In particular an OSAR-only
animation under custom sprites does not claim an authentic fallback that does
not exist. Initial profile excludes remastered/procedural sprite substitution
and undeclared spritepack precedence. If a selected spritepack overrides an
entry, the exporter must declare the resolved asset and the supported ordered
resolution evidence; until that profile is implemented, the consumer refuses it.
Resolved RGB is the preferred route when successful PNG loading or layered
resolvers supply final frames. resolution.inputSourceIds includes all used PNG,
OSAR/authentic inputs and client loader modules. precedenceSourceIds lists their
actual application order; all references must exist in provider.sources (assets
also carry corresponding sourceId). The resolver source SHA must match the named
source record and captured target. Bind initializers as client-sprite-initializer,
external images as external-sprite-input, archives as sprite-input. Capture the
original successful/failed resolution outcome, not merely an existing PNG path.
Failed optional loads must reflect the actual fallback final vectors/frames.

A21-frame source with source hasCombatFrames=true and hasSpecialCombatFrames=false
may declare authoringPreview.frameIndices0..17, combat=true,
special=false while retaining all21 raw frames. The remaining three are preserved
second-attack evidence, not invented generic F frames. A source-specific walking
cadence (for example three-phase walking versus idle-left-idle-right) can use an
explicit bounded generic authoring preview, with limitations containing
`source-animation-cadence-not-reproduced` and/or
`source-secondary-attack-not-previewed`. These limitations are displayed during
capture/refresh and retained in the diagnostic report. An export cannot claim
complete runtime animation fidelity from a supported building preview subset.

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

## Effective NPC mask policy (required runtime addition)

Pixel parity alone is insufficient. The maintained drawNPC branch uses the
ORIGINAL animation ID and custom-sprite flag before considering charColour2/3.
A private ID>=1080 can change the chosen tint even when frame pixels are exact.
The producer exports npcMaskPolicy per source animation; Editor independently derives
and checks the following ordered policy using source animationId, charColour,
and captured Config.S_WANT_CUSTOM_SPRITES:

1. charColour==1: `hair-and-skin`.
2. customSprites && source animationId>=230: `literal-and-skin`.
3. charColour==2: `top-and-skin`.
4. charColour==3: `bottom-and-skin`.
5. Otherwise: `literal-only`.

Private RGB registry rows carry the optional all-or-none triple
`npcMaskPolicy`, `sourceAnimationId` (0..65535), and `sourceCustomSprites` (boolean).
Runtime independently validates that policy against the two source provenance
fields and charColour; all three are allowed only on RGB rows.
Private registry rows need the verified policy; the runtime's NPC renderer must
apply it independently of private allocation IDs and authoring global flags.
Existing rows without the field retain their historical behavior. The literal
value remains charColour; NPC palette values remain per-definition. NPC blue
mask is literal0 in this profile; captured animation blueMask is source metadata,
not a claim that the NPC renderer applies it. Both renderer branches and the final
ordinary draw path need the same bounded helper. Runtime review and framebuffer
parity are required before consumer acceptance.

## OSAR normalization finding

Frame storage requires no new format, but the explicit NPC mask-policy runtime
addition above IS required for faithful tinting. Existing locked runtime
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
The runtime change is limited to the explicit private NPC mask policy; it does
not transplant target gameplay or source-specific combat/animation controllers.
Unsupported frame or renderer behavior still refuses rather than forcing acceptance.

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
- Final loader-success and loader-failure fixtures: actual vectors after PNG
  success differ from the earlier table snapshot; failed PNG retains actual
  fallback. Source21-frame inventory retains all21 hashes; authoring18 subset
  does not pretend F9 support. Named cadence/secondary-attack limits are visible.
- All five mask policies, original IDs229/230, charColour1/2/3/literal, flags
  false/true, different private allocations, NPC palettes and pixel-level tint
  framebuffer parity; animation blueMask metadata does not alter NPC output.
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

## Closed v2 validation addendum

This addendum resolves conditional shapes and supersedes abbreviated example rows.

- Root keys are exactly schemaVersion, manifestType, provider, selection,
  assetProviders, npcDefinitions, animationDefinitions.
- provider keys are exactly identity, rendererProfile, capturePhase, spriteBranch,
  configuration, clientFlags, sources, resolutionProbes. clientFlags has exactly
  Config.S_WANT_CUSTOM_SPRITES and Config.S_ALLOW_BEARDED_LADIES, both booleans.
  spriteBranch is `custom` iff the first flag is true, otherwise `authentic`.
  The initial profile's frame/tint logic has no other configurable branch.
  All registry/loading decisions must derive from bound selected configuration
  and bound source defaults; any additional renderer flag or unbound preference
  requires a new supported profile, not an ignored flag. Remastered resolution
  is unsupported. Both explicit flag values must be checked against the selected
  configuration adapter plus captured supported client/server defaults. A value
  that cannot be derived from those inputs refuses; a producer assertion alone
  is insufficient. This does not authorize parsing arbitrary Java control flow.
- Each source has exactly sourceId, role, relativePath, sha256. sourceId is a
  unique `[A-Za-z0-9][A-Za-z0-9._-]{0,127}` identifier. Permitted roles are
  effective-npc-definition, configuration-input, server-npc-loader,
  client-npc-loader, client-frame-resolver, client-sprite-initializer,
  external-sprite-input, sprite-input, resolved-frame-artifact,
  client-visual-archive, producer-helper-source. Code roles are
  bounded under server/src or Client_Base/src, definition roles under the selected
  definition root, configuration inputs under the supported selected-profile
  configuration roots, and image/archive artifacts under Client_Base/Cache or
  server/conf/world-builder. external-sprite-input additionally permits the
  maintained source asset roots dev/myworld/assets/sprites/npcs/**,
  Client_Base/dev/myworld/assets/**, and Core-Framework/dev/myworld/assets/**,
  always resolved INSIDE this selected target root. These last roots bind actual
  earlier candidate absence; they never authorize another checkout or parent.
  client-visual-archive is exactly the independently selected active paired client
  JAR path (including supported Client_Base/Open_RSC_Client.jar), checked against
  discovery's active binary evidence. Embedded members are bounded to
  myworld-assets/**. producer-helper-source permits direct *.java or *.py files under
  tools/item-visual-provider plus the exact path scripts/generate-world-builder-target-contract.py; these are inert freshness inputs, never run by
  Editor. Client source inputs include Sprite.java and the declared SpriteArchive
  decoder modules. All use exact contained portable paths.
- Each asset provider has exactly assetId, sourceId, format, targetRelativePath,
  packageRelativePath, sha256. sourceId references a matching sprite-input,
  resolved-frame-artifact, or client-visual-archive source; targetRelativePath and SHA must match it.
- resolutionProbes is an ordered array with unique probeId values using the
  sourceId identifier grammar. Exact tagged shapes are:
  * absent file: `{probeId,kind:"file",relativePath,present:false}`;
  * present file: `{probeId,kind:"file",relativePath,present:true,sha256}`;
  * absent member: `{probeId,kind:"archive-entry",archiveRelativePath,
    archiveSha256,entryPath,present:false}`;
  * present member: the same archive-entry shape with present:true and
    entrySha256 (hash of exact expanded member bytes).
  File/archive paths use the same supported external image/archive roots;
  entryPath is a contained portable archive path. The containing archive must
  also be a declared sprite-input or client-visual-archive source and its SHA must match. Duplicate
  archive members refuse; absent member means no entry with that exact name.
  A successful file hashes exact bytes. Missing file means no directory, regular
  file, or symlink exists at that path. Any later appearance/disappearance/byte
  change makes the provider stale. Sources still require present regular files.
  No missing file/member has a fictitious content hash.
- Each animation resolution object has exactly resolverSourceId,
  resolverSourceSha256, inputSourceIds, precedenceSourceIds, probeIds.
  probeIds references declared resolutionProbes by probeId in actual attempt order. Every probe is referenced by
  at least one resolution. inputSourceIds includes the final selected asset's
  source and every prerequisite loader/source/image affecting resolution;
  precedenceSourceIds contains each ordered applied resolver input once, drawn
  from inputSourceIds. Unknown/dangling/duplicate references refuse.
- Each animation row has exactly animationId, name, category, charColour,
  blueMask, genderModel, hasCombatFrames, hasSpecialCombatFrames,
  resolvedFrameCount, npcMaskPolicy, authoringPreview, resolution, frames.
  The two top-level flags describe the source animation. Authoring preview keys
  are exactly behavior, hasCombatFrames, hasSpecialCombatFrames, frameIndices,
  limitations. behavior is generic-layered-preview-v1. Limitations is a unique
  array containing only source-animation-cadence-not-reproduced and
  source-secondary-attack-not-previewed. A projected source must report its
  applicable limitation; claims are checked by its supported source profile.
- The initial complete source frame inventory is bounded1..256 per animation;
  authoring frameIndices has15/18/27 indices consistent with its flags. Each is
  within resolvedFrameCount. Repeated indices are explicit and permitted. A
  source21→authoring18 projection requires sourceA=true/sourceF=false, retains
  all21 frame payloads, and declares source-secondary-attack-not-previewed.
  SourceF=true requires source frames reachable through26; no fabricated F9.
- Up to65536 NPC/animation rows; up to8192 sources/assets/probes, paths<=512
  characters, manifest<=16MiB, expanded source archives<=512MiB. RGB payload
  limits above are additional and enforced before publishing project files.
  ZIPs reject duplicate names, paths outside the provider root, links, nested
  archives, and undeclared entries. Frame allocation must remain within0..65535;
  private RGB animation IDs remain1080..65535.

The shortened main example must include sourceId on its asset and empty
resolutionProbes/probeIds where no optional-path probes apply. Producers must
emit the closed shapes above; missing keys never imply guessed default evidence.

Probe ordering follows the actual external loader's attempted candidate order,
including failed earlier candidates before the selected file or embedded-JAR
fallback. Relative traversal to a parent or another checkout is never authorized
by the producer. The maintainer must capture in an isolated sealed input layout
whose effective candidate paths remain inside the selected owned target root;
otherwise the consumer refuses with the exact external path requirement. For a
missing embedded member, capture the whole containing JAR digest and explicit
member lookup outcome in a supported resolver profile; do not invent a missing
member hash or accept an unbound running classpath.

Absent-path observations are durable evidence despite having no file to copy.
The original producer manifest and its ordered probe states enter the sealed
content/source snapshot. Discovery validates them against the live target;
project capture checks again before and after copying, and refresh/import preview
and apply revalidate the exact absent/present state. Merely omitting missing files
from the copied file inventory is insufficient. Appearance of an earlier candidate
must stale the provider even when its final selected asset still has the old hash.

The contained Core-Framework/dev candidate prefix above is a literal input of the
recognized maintained external-asset resolver, not a server identity exception.
No behavior depends on server folder name or NPC identity. Other resolver profiles
need separately verified semantics; no external checkout is read.

### Active spritepack selection in this bounded profile

`Client_Base/Cache/config.txt` is an explicit visual selector, not gameplay
configuration. Its complete file is bound as `configuration-input`; discovery
emits `npc-producer-v2-visual-selector` only after verifying its bounded syntax.
Each line is a unique portable pack name followed by `:0` or `:1`. Each active
pack is bound as a `sprite-input` source at
`Client_Base/Cache/video/spritepacks/<name>.osar`. Every animation's resolution
input list includes active packs, with their ordered source IDs retained in the
precedence list. The consumer independently decodes each active pack and refuses
any `(category, name)` that intersects the complete referenced NPC animation
closure. Disjoint packs remain active and unchanged. Supporting packs that replace
NPC frames requires a future reviewed resolver profile; the consumer never
silently disables them. A missing selector requires a bound absent-file probe.

Discovery validates manifest/source/asset hashes and selection/probe outcomes.
Capture validates every resolved frame's internal geometry, payload and declared
closure before publishing a project or revision. ZIP closures permit at most
32,768 entries, 128 MiB compressed input and 512 MiB expanded; individual RGB payloads are limited to
16 MiB. Combined retained source frame payloads are bounded to 512 MiB, and the
private emitted NPC RGB projection to 256 MiB. Newly read manifests and maintained
configuration-default sources are limited to 16 MiB; configuration text is limited
to 4 MiB. Reads enforce their bounds while streaming as well as checking initial
file size. OSAR-to-RGB conversion is checked
against the exact locked provider's maintained `Unpacker`, including palette
zero, offsets, shifts, bounds and frame order.

Intentional cadence and secondary-attack limitations are recorded separately in
`diagnostics/npc-producer-v2-resolution.json`. They do not mark verified frames as
missing or unresolved. Names and original NPC IDs identify affected rows; no
private animation IDs appear in placement data. A first legacy-to-complete-RGB
revision may require broad visual review because the verified representation and
source renderer profile change. That review is not a claim that every NPC's
visible appearance changed, and it does not authorize changing semantic content.
