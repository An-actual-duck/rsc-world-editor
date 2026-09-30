# Discovered NPC visuals

NPC identity and NPC appearance are separate discovery results. A server's
existing definition may refer to ordinary baseline layers, a custom archive
animation, or image frames applied by its client. Discovering an ID and name
does not by itself prove the final custom appearance.

World Builder enriches discovered definitions through a generic source-bound
visual inventory. It includes existing and unplaced NPCs, including baseline
IDs with custom presentations. No custom NPC name or ID is built into this
inventory. Structural source adapters and neutral metadata produce the same
records; the resulting portable RGB animations work in both rendering modes.

## Neutral metadata

A target may supply `npc-visuals-v1.json` at its root, beside its selected
NPC definitions, or in their `world-builder/` subdirectory. The format is
[`npc-visual-sources-v1.schema.json`](../tools/world-builder/schema/npc-visual-sources-v1.schema.json).
Multiple copies must agree byte-for-byte. Explicit records take precedence over
source inference for their bound definition records.

Each visual binds the original definition path, array index and whole-file
SHA-256, its NPC ID, and one sprite slot. Frame records bind PNG paths and
SHA-256 values plus exact rectangles, offsets and canvas bounds. They specify
15 walking poses, optionally three combat poses and nine special combat poses,
in renderer order. Repeated rectangles express pose reuse without requiring
NPC-specific rules. Alpha threshold, color masks and optional camera bounds are
explicit. Asset paths are relative to the selected target root, not inferred
from NPC names.

Discovery captures every interpreted source, descriptor, definition and image
as immutable project evidence. Hash mismatch, links, contradictory bindings,
invalid rectangles, or excessive decoded data stop capture with a path-specific
error. RGB frames preserve their source pixels (transparent pixels become zero;
opaque black becomes `0x010101`, following the renderer's transparency rule).

The compiler resolves supplemental ID reassignment through definition provenance
and appends presentation overrides after the active definition overlays. Server
stats, commands, and other gameplay fields remain authoritative. Other sprite
slots remain unchanged. Conflicting camera bounds or multiple sources claiming
one effective sprite slot are refused. Existing verified derived animations are
reused after checking all frame hashes and bytes.

## Existing providers and diagnostics

The legacy rich neutral NPC provider remains supported. Its placed-extension
selection and original base/custom boundary are still checked exactly. A
verified provider can now enrich an already-discovered supplemental definition
when its extension-catalog identity and hash prove that binding. A name match
alone is insufficient. Missing binding produces `NPC_VISUAL_UNRESOLVED` and
retains the server presentation references. This legacy selection does not
claim coverage of unplaced or baseline NPCs; the generic inventory does.

`diagnostics/npc-visual-resolution-v1.json` distinguishes verified custom sprite
slots from retained baseline/existing references. Verifying one slot does not
claim that every layer is verified. The existing project diagnostics surface
shows imported custom-visual counts and provider warnings without adding a
warning to untouched baseline projects.

Source-only customizations outside the supported structural source forms need
complete neutral metadata; arbitrary target code is never executed. An image
without an authoritative identity and frame-layout binding is not assigned by
name or visual guesswork. Supported alternate packed definition roots retain their original binding paths
and hashes. Verified canonical aliases preserve supplemental catalogs and map
visual bindings through the selected source layout; unrelated matching bytes
are never used as an identity shortcut.

Existing projects retain immutable evidence. Create a fresh project to capture
visual sources missing from an older project. Discovery and creation do not
modify the target or original project.

Focused coverage: `tests/myworld/test-world-builder-npc-direction-sheets.py`
and the structural source-adapter tests. Optional reference tests consume only
explicitly exported files; they never build or launch the reference checkout.
