# Discovered NPC direction sheets

The bounded Slayer movement-preview input adapter imports the eight recognized
supplemental NPC definitions and their external direction-sheet PNGs, including
Naga (NPC 866). This repairs the historical layout where the declarative NPC
record contains a basic head and the original client replaces its appearance
from an external image at startup.

Discovery inventories the exact selected sheets as immutable source evidence.
Project creation converts their standard walking and combat poses to lossless
RGB frames in the project authentic sprite archive and binds them through the
portable NPC animation registry. Both rendering modes use those verified frames.
No target Java code is executed. NPC IDs and gameplay fields are retained; the
adapter replaces the recognized placeholder presentation and its camera bounds.
Unplaced definitions are included. Reconciled supplemental ID conflicts follow
the existing definition reconciliation, never a name-only match.

The adapter supports only the recognized catalog identities and known image
layouts. Missing, malformed, oversized, or linked sheets produce an explicit
refusal; customized sprite layers are not silently overwritten. Existing valid
RGB bindings are reused after comparing the captured pixels and all frame
hashes. A matching runtime capability is required even when a previously
captured RGB registry no longer has historical source sheets alongside it.

Source PNGs retain every original direction column. The current standard
renderer consumes 15 walking poses and three combat poses. The additional
projectile-specific poses in some historical sheets remain in source evidence;
this import does not introduce their source game's specialized combat behavior.
Banshee reuses its side poses for combat, matching its historical presentation.

This is an input adapter for one source family, not a general extractor for
arbitrary client-side NPC patches. Existing projects need a fresh capture to
acquire artwork that was not inventoried by an older version. Original projects
and target files are not modified by discovery or project creation.

Focused coverage: `tests/myworld/test-world-builder-npc-direction-sheets.py`.
Optional reference acceptance takes only an explicitly exported PNG through
`WORLD_BUILDER_DIRECTION_NPC_PNG`; `WORLD_BUILDER_DIRECTION_FIXTURE_OUTPUT` must
name a new external disposable directory. No reference checkout is executed.
