# World Builder 2 content and terrain correction plan

Date: 2026-09-24. Status: active; initial browser and GPU material corrections
integrated. Floor migration, wall reproduction, and full interactive acceptance
remain pending.

## Objective and baseline

Resolve the browser crashes, inconsistent upper-floor rendering, material
ambiguity, capability declarations, and incomplete custom-content discovery
reported in the owner's local compatibility handoff. This is permanent product
work across the independent Editor and runtime repositories.

Initial Editor baseline: `eed0f455ea2f10c591e395bf84e58dbea12b0355`.
Initial runtime baseline: `0355b2eccf1717a3235251d7b917f13b2fa454d6`.
The recorded v0.8.0 acceptance covers Current Base, not the local presentation
and Slayer adapters. The owner-supplied `CORE-LOCAL-COMPATIBILITY-HANDOFF.md`
in the manager checkout is local evidence, not a shipped source dependency.

## Required floor behavior

The same authored settings must have the same appearance and collision on all
supported signed levels. Overlay 0 uses the selected ground color and is
walkable: a setting that produces white on level 0 must also produce white
upstairs. It must not implicitly turn transparent on levels 1 and 2.

Intentional empty space, an invisible walkable floor, and a solid black floor
are distinct behaviors. The current runtime TileDef.xml contains a candidate
invisible, nonblocking definition at raw overlay 26. Verify its behavior and
availability in each composition/catalog before reusing and labeling it; do
not assume this ID has the same meaning in arbitrary imported catalogs.
Preserve existing IDs, collision, and untouched upper-level void. Audit legacy
void representation before changing rendering and introduce an explicit,
verified conversion if necessary rather than filling every empty tile.

## Ordered implementation

1. **Browser contracts (runtime) — initial correction integrated.** Correct the floor browser's
   `floor` versus `tile` family mismatch. Reproduce the wall crash separately;
   it already requests `boundary`. Exercise open, filter, select, and close on
   multiple levels with stock and custom catalogs. Malformed entries must
   yield actionable diagnostics rather than escape through client input.
2. **Floor consistency (runtime; Editor conversion if required) — pending.**
   Implement the floor contract above. Verify painting, collision, save/reload,
   surrounding void, and negative and higher signed levels as well as 0/1/2.
3. **Material identity (runtime) — correction integrated; full-client acceptance pending.** Preserve explicit solid-color
   versus texture identity through GPU meshes and rendering. Black and low RGB
   colors must not alias texture IDs. Cover floor/wall/roof materials, genuine
   textures, overlay 10, and classic/remastered presentation. Do not reindex
   texture 0 or alter collision to repair appearance.
4. **Truthful capabilities (both repositories) — pending.** Trace declarations
   to supported producer/loader contracts; test every advertised terrain and
   placement encoding and retain refusal of unsupported ones. Do not copy a
   fixed version list into unrelated targets or modify Core metadata here.
5. **Complete content discovery and refresh (both repositories) — pending.**
   Capture authoritative effective definitions plus item sprites, external
   PNGs, NPC animations, and models. Include available unplaced NPCs. Report
   actual visual coverage separately from catalog membership. Add previewed,
   recoverable project content refresh that preserves authored edits and IDs.
   Keep target assets portable and reusable runtime behavior provider-owned;
   do not substitute an entire arbitrary client/server snapshot.
6. **End-to-end acceptance and delivery (Editor manager) — pending.** Discover
   content, preview directions/animation, place custom items and unplaced NPCs
   on levels 0/1/2, save, close, reopen, export, and validate exact IDs against a
   disposable compatible destination. Exercise refresh failure/recovery and
   preservation of existing edits. Publish tested runtime work, adopt its exact
   commit, and prepare a fresh owner-test build. Record unavailable real-content
   or interactive acceptance explicitly.

## Ownership and verification

The Editor manager coordinates the sequence, review, publication, dependency
adoption, and evidence. Runtime AI-1 owns substantial runtime implementation;
Editor implementation uses a topic branch or optional Editor AI-1. Keep one
coherent assignment per worker and integrate useful tested milestones.

Before each test phase declare scope and expected duration under
[Testing Policy](TESTING-POLICY.md). Browser/presentation corrections need
affected compilation, focused behavioral regressions, and actual visual/input
acceptance. Persistence, schemas, refresh, and transactions require affected
integration and refusal/recovery suites. Broad integration and production
release require full applicable verification; a dependency update alone does
not trigger it. Do not claim automated catalog tests prove interactive use.

Only isolated fixtures may be mutated. Do not inspect the live Core installation
or Core workers named in the handoff. Owner-designated reference inputs remain
subject to repository boundaries. Preserve the untracked handoff and all user
projects/adapters. Installing into a real target, retiring local adapters, live
server actions, and public release/tag/upload require their separate applicable
authorization. No hash-guard bypass or weakening of transaction safety is part
of this plan.

## Floor migration findings and required implementation boundary

The owner subsequently supplied an unupgraded Preservation reference. The
[original-map comparison](WORLD-BUILDER-2-ORIGINAL-PRESERVATION-FLOOR-COMPARISON.md)
now establishes the baseline independently of World Builder's current loader.
The original Y-offset model already had internal plane-dependent transparency;
World Builder carried that assumption into general signed-level authoring.
Preserve demonstrated original map behavior, not an application regression.

The first implementation audit found no durable authored-floor distinction in
the current raw terrain fields. `WorldBuilderPackedTerrainCodec.toLayered`
preserves overlay bytes, and `WorldBuilderPackedConversionModel.readTerrain`
verifies exact reverse conversion. Preservation reconciliation likewise seals
the derived terrain. On the runtime side, both the terrain face-input builder
and the older raster loop suppress base color on planes 1 and 2, including the
native signed-level presentation path.

Consequently, removing the renderer branch alone would make historical
invisible upper-floor space visible. Replacing historical overlay 0 with
structural void 8 would change walkability. Neither is the intended fix.

The follow-on floor change must establish explicit current floor semantics and
a bounded migration from historical semantics:

- Preserve historical invisible walkable space as an explicit invisible
  walkable material; preserve blocking invisible space and visible materials
  independently. Do not infer authored intent from a zero color byte.
- Make newly authored overlay 0 mean the same selected-color walkable floor
  on every signed level. Explicit invisible walkable flooring remains a
  separate discoverable selection. Blocking base color must also retain its
  selected color consistently.
- Carry any new semantic distinction through a contract accepted by both
  client and server, rather than a launch-only renderer flag. Determine the
  smallest adequate representation; the comparison alone does not require a
  new binary terrain encoding. Preserve target definition IDs; do not assume
  overlay 26 exists or is available to reserve.
- Convert historical inputs and existing projects through reviewed, recoverable
  staging, retaining immutable before-state and exact derivation evidence.
  Reopen/save/export/region operations and target compatibility checks must
  carry the distinction. An older runtime must not silently load new semantics.
- Validate imported surrounding void and authored floors together, including
  mixed historical/current data, negative/higher levels, collision, interrupted
  migration and rollback. Existing bytes can encode both historical invisible
  space and an unsuccessful attempt to paint overlay 0; preserve recorded
  behavior during migration and make repainting 0 explicitly produce a floor.

This is an implementation boundary, not a completed migration or a reason to
retain the old behavior indefinitely. The independent material/RGB fix is
being integrated first because it needs no data or semantic migration.

## Progress and evidence

- 2026-09-24: reviewed both repository baselines, release evidence, and handoff.
  Source confirms floor family mismatch, plane-1/2 transparency override, and
  fallback-color/texture ambiguity. Wall crash remains unreproduced. Identified
  existing invisible-floor candidate and placement-bound NPC provider evidence.
- Update this section with exact commits, checks, remaining acceptance, and
  changes to scope as each milestone completes.
- Browser correction reviewed at runtime handoff
  `e8388ca8c2c50ee640659c6e32b2d308c4e8eef6`, published on runtime main as
  `175b60216f4970e89f601658b79b545fe3ed7d7e`, and selected in the Editor lock.
  Fixes the floor family call and a missing bridge-definition dereference;
  malformed tile/wall records now log their IDs and provider guidance.
  Existing tests bypassed adaptive profile wiring. New coverage exercises
  actual Editor browser opening, filtering, keyboard/mouse selection, and
  closing against stock/custom/malformed in-memory inventories.
- Runtime validation: client build, presentation group, one new browser test
  with three scenarios, two existing browser tests, six catalog tests, shell
  syntax and diff checks passed. The full runner now includes both browser
  suites. Wall selection passes north/east/diagonal/smart-wall fixture paths,
  but the owner's crash is not reproduced. Rendered GUI, signed-level
  interaction, and owner-specific reproduction remain open; these headless
  fixtures do not prove those acceptance criteria. No production package or
  user installation was updated.
- Editor adoption validation: exact runtime parity and
  `scripts/test.sh --group presentation` passed (four tests, nine seconds
  reported for the two selections). Reviewed runtime changes affect client
  browser/catalog handling only; no server, data, protocol, or persistence
  changes require transaction-suite expansion for this milestone.
- Further floor audit: overlay 26 is already labeled `Invisible Path` in the
  runtime catalog, but Current Base's public TileDef.xml ends at overlay 25.
  Availability therefore needs composition-aware handling. New Editor space
  explicitly uses overlay 8 for structural void; legacy upper-plane implicit
  void still needs separate audit. The runtime evidence generator already
  advertises encodings 1 through 5, so the reported stale declaration is not
  reproduced by that current generator alone.
- GPU material correction reviewed at runtime handoff
  `f8d233955b86d1ba8e01af25a7d16c1db91fdc85` and published on runtime main as
  `b09b1d5eebb04015791220624c5f6e45b853955d`. The World GPU producer now
  resolves legacy resources into separate texture-ID and RGB channels before
  rendering; consumers and texture-dependency collection no longer interpret
  RGB as an index. No definition IDs, saved bytes, collision, or protocols
  changed. The Editor lock selects this published revision.
- Material validation passed: client build, runtime presentation group,
  world-model product split, renderer geometry, object primitive builder,
  material-family and texture-cache regressions. Behavioral coverage includes
  terrain/wall/roof front and back materials, black/low RGB/white, transparency,
  genuine textures 0/1, UVs, and classic/remaster material-color inputs.
  `test-opengl-world-texture-reference-cache.py --render` additionally passed
  hidden OpenGL framebuffer readback using production vertex upload, texture
  atlas, and texture-enabled batch classification/binding. This covers the
  fixed-function path, not full remaster shaders, software rasterization, or
  interactive owner-project acceptance. Reproduction commands and limitations
  are in the runtime's `docs/TERRAIN-MATERIAL-CORRECTION.md`.
- Material adoption validation: exact runtime parity and all four Editor
  presentation/supervision tests passed (ten seconds for the two selections).
  This is a tested development integration, not a release or an update to the
  owner's installation. Next implementation milestone is the explicit floor
  contract and recoverable migration described above.
- Original-reference comparison: selected tracked source/maps in the
  owner-authorized `/home/justin/RSC-Preservation` match original commit
  `c0102e60774ab9c9076aabae49f6f97fb6fc4b00`. Actual Editor codecs preserve
  colors, overlays, sector coordinates, and exact reverse bytes across all
  1,764 client archive sectors. See the comparison record for counts, original
  renderer evidence, and limitations. No reference files were changed.
