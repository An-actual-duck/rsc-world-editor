# Friendly floor authoring

Implementation contract, 2026-09-24. Follow progress in the
[content and terrain plan](WORLD-BUILDER-2-CONTENT-AND-TERRAIN-FIX-PLAN.md).

## Imported-server completion (in progress)

The first candidate only extended Current Base. Imported/custom catalogs must
also receive a deterministic append-only extension during project preparation.
Reuse existing same-source semantic partners and safe original definitions;
preserve every existing ID and original row. Historical projectile floors 2/11
and invisible floors need explicit semantic partners. Complete or partially
extended catalogs must upgrade idempotently. Capacity exhaustion is a precise
pre-publication refusal, never silent ID reassignment.

Existing projects upgrade through a new sibling project carrying the exact
working map, with the complete original project retained for recovery. Source
evidence, derived content, runtime binding and catalogs must remain verifiable.
The normal player client must load the same verified definitions as the server.
Upgrade Target Runtime installs that pair with exact backups and recovery;
Import Map Changes remains map-only and requires the installed definitions.

Acceptance includes custom water, original-row preservation, repeated upgrades,
existing edited projects, failed publication, client/server definition pairing,
map-only refusal before upgrade, and upgrade rollback/interruption recovery.

## Authoring behavior

The Floor tool combines appearance and floor walkability. Select Color chooses
from the existing terrain color palette. Select Texture includes None; None
uses the selected color. Choosing a texture retains the color for later but
uses the texture's appearance. Walkable controls whether the floor blocks
movement. Wall and object collision remain independent.

The tool resolves those choices against the project's actual definitions and
current signed level. It paints the color and resolved overlay together in
one terrain operation. The interface must show unavailable combinations and
explain why they cannot be selected. Copying/inspecting a tile reports its
effective appearance, including invisible legacy upper-floor space.

The standard supplies both walkability states for each included appearance,
plus two explicit base-color definitions. Existing original IDs and map bytes
are retained. Historical raw overlay 0 is unchanged: on original upper planes
1 and 2 it is invisible. A newly selected color resolves to explicit standard
definitions that display the selected color at every supported level.

## Content contract

The provider owns a deterministic `standard-floors-v1` transformation of its
original TileDef source. It appends definitions without renumbering originals.
Generated definitions fit below reserved overlays 250 and 255; insufficient
space is a refusal rather than a partial standard.

Two optional TileDef XML fields distinguish generated semantics:

- `worldBuilderMaterial` is exactly `base-color-v1`. Its canonical `colour`
  and `unknown` values are 0. `objectType` is 0 or 1. The renderer reads the
  tile's existing color byte, rather than interpreting colour 0 as texture 0.
- `worldBuilderSourceOverlay` refers to an earlier unmarked original overlay.
  The definition copies its `colour` and `unknown`, with `objectType` 0 or 1.
  The reference preserves ID-dependent visual interpretation, such as water
  texture selection, without copying ID-dependent gameplay effects.

These fields are mutually exclusive. Empty/unknown markers, reserved IDs,
noncanonical blocking values, forward references, chains, and mismatched source
materials are rejected. An absent marker retains historical semantics.
Client, server, and Editor content validation must agree on the contract.

The standard uses generated definitions for both traversal states. Original
overlays 2 and 11 carry projectile-blocking behavior, which is not part of the
new appearance-plus-walkability controls. There is no generic terrain-triggered
lava damage or water swimming in the audited runtime; specific obstacle scripts
implement their own effects. Untouched original map tiles retain all behavior.

## Compatibility and verification

Current Base ships the standard as part of its exact selected composition.
Projects remain bound to their own immutable content, so an existing project
does not silently gain or change definitions. Catalogs without the standard
offer only combinations their actual definitions can represent. Updating
project content remains an explicit lifecycle operation.

The standard does not introduce another binary terrain format. Existing
color/overlay fields persist the choice. Managed import and runtime-upgrade
checks must still prove the selected client/server/content pairing before
new definitions reach a target. Marked definitions additionally require both
runtime JAR manifests to declare `World-Builder-Floor-Semantics:
standard-floors-v1`. Project capture and runtime verification check this
declaration; unmarked historical content does not gain that requirement.
No real target is modified by implementation
or automated acceptance.

Acceptance covers original-ID preservation, deterministic generation, malformed
metadata refusal, rendering and minimap appearance across levels, transparent
floor selection, client/server collision, atomic painting, and save/reload.
Actual control rendering and pointer behavior require visual/input acceptance
in addition to headless resolver tests.
