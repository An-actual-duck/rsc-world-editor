# Original Preservation floor comparison

Date: 2026-09-24. Read-only investigation for the active content/terrain fixes.

## Reference and method

The owner explicitly designated `/home/justin/RSC-Preservation` as the original,
unupgraded reference. Its checked-out commit is
`c0102e60774ab9c9076aabae49f6f97fb6fc4b00`. No local AGENTS.md was found in
that checkout or its ancestors. No reference checkout code was built or
launched, and no reference files were modified.

Compared these selected tracked inputs with their committed Git blobs: client
World, Sector and Tile source; server WorldLoader source; TileDef.xml; client
and server Authentic_Landscape.orsc; and land64/maps64 JAG/MEM archives. Every
selected file matched. This verifies those inputs, not arbitrary files or user
state elsewhere in the checkout.

Both authentic landscape copies have SHA-256
`48ed0e1634b870888f96c0bc3e31cbaf152570b913140fdfd3596897a3eb29fa`.
Client World.java has SHA-256
`29d025f1446e2b51b98b7cffd6dfb5cdf454e4114623cd184d0cfc88905c303f`.

## What predates World Builder

The original game uses Y offsets of 944. Its server WorldLoader still selects
an internal plane while projecting it into those coordinates (lines 521–531).
The client archive names likewise include the plane: `h0...` through `h3...`.

The original client World.java, lines 556–563, explicitly replaces the base
color resource with transparency on planes 1 and 2 before overlay processing.
The second terrain/minimap path repeats that rule at lines 1739–1746. A
nonzero overlay can supply a visible material afterward. The server's
overlay-collision check at lines 384–388 does not add blocking for overlay 0;
other walls/objects can still block a tile.

Thus the Y-offset representation and an internal plane-dependent rendering rule
coexisted in the original client. World Builder did not invent that rule, but
carried it into its general signed-level authoring model, where it conflicts
with the intended consistent Floor Color / overlay 0 behavior.

## Original map evidence

The client authentic archive contains 441 sectors per internal plane, each
with 2,304 tiles. Counts below cover the complete client archive, not a claim
that every sector is active in the original server's selected JAG map.

| Internal plane | Tiles with overlay 0 | Tiles with overlay 8 |
| --- | ---: | ---: |
| 0 | 300,360 | 184 |
| 1 | 1,009,025 | 98 |
| 2 | 1,014,518 | 21 |
| 3 | 33,637 | 962,054 |

A concrete mixed sector is `h1x49y46`: 2,198 tiles use overlay 0, 77 use overlay
3, 28 use overlay 6, and one uses overlay 8. Local tile `(0,0)` has ground color
0 and overlay 0; `(10,7)` has ground color 0 and overlay 3. The corresponding
plane-2 sector has 2,297 overlay-0 tiles, six overlay-3 tiles, and one overlay-8
tile. These distinguish original blank upper terrain from explicit floor
overlays without relying on a World Builder-upgraded input.

## Conversion comparison

A temporary harness outside the reference called the current Editor's compiled
`WorldBuilderPackedCoordinateCodec` and `WorldBuilderPackedTerrainCodec` over
all 1,764 original client archive sectors (4,064,256 tiles). It verified:

- Exact sector-name/coordinate round trips.
- Identical ground-color and overlay bytes after raw-layered conversion.
- Byte-exact reverse conversion for every sector.
- Packed Y values 648, 1592, 2536, and 3480 map to local Y 648 with signed
  levels 0, 1, 2, and -1 respectively, and round-trip exactly.

All checks passed. This exercises the actual codec, not complete project
creation, server JAG reconciliation, gameplay, or original rendered output.

## Consequence for the fix

The authoring requirement remains: overlay 0 uses the selected ground color
and stays walkable on every signed level. Black/invisible flooring needs an
explicit separate selection. The original reference establishes which imported
space depended on the historical implicit rendering rule; current broken
authoring output must not itself become a preservation requirement.

Use this evidence to implement the smallest explicit distinction needed to
preserve original map appearance/collision while making newly authored floors
consistent. Do not silently remap all upper overlay-0 tiles to blocking void 8,
assume imported overlay 26 is free, or fill the original empty upper sectors.
Any necessary conversion must retain exact source evidence and recoverable
project state. The comparison establishes the semantic problem; it does not
by itself prove that a new binary terrain encoding is the only solution.
