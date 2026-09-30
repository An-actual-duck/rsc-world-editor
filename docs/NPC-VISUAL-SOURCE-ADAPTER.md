# Static NPC visual source adapter

The Editor can recover visual metadata from a bounded, recognizable source
layout without compiling or executing target code. This complements explicit
neutral NPC visual descriptors; it is not arbitrary Java program analysis.

The supported layout combines:

- A declarative enum table with literal NPC IDs, names, asset keys, and column
  widths, assigned to fields by its constructor.
- A source loader that iterates that table, loads a direction sheet from an
  explicit path, activates its NPC layer, and installs the resulting frames.
- Matching animation registration and lookup, NPC identity/name checks, and
  camera dimensions supplied by pure table methods.
- A `provenance.json` beside the selected images, binding IDs, asset keys,
  column widths, sheet height, and PNG SHA-256 hashes.

NPC identities, class names, asset paths, dimensions, camera arithmetic, and
conditional combat-frame reuse come from the inspected files. There is no NPC
identity list. The adapter verifies the supported `loadExternalNpcDirectionSheet`
API's crop loop, three-beat ordering, alpha normalization, opaque-black handling,
and its wrapper's disabled guide-color argument. Complete method-body templates
reject unsupported changes instead of treating coincidental filenames as proof.

Only constant-return methods with the bounded expression subset are interpreted:
integer arithmetic, enum identity comparisons, boolean operators, conditionals,
column indexing, and calls to other pure constant-return methods. Unknown names,
ambiguous associations, unsupported transformations, changed assets, and unsafe
paths produce a diagnostic directing the owner to explicit metadata. Explicit
neutral descriptor bindings take precedence over inferred source bindings.

Source scanning is limited to conventional client source roots, 12,000 entries,
4 MiB per source file, 32 MiB in aggregate, and two million tokens. An iterative
lexer handles strings, character literals, comments, and opaque text blocks;
none of those contents are interpreted as source structure. Referenced source
files, provenance, definitions, and PNGs become immutable discovery evidence.

The output represents standard movement and combat frames (15, 18, or 27 slots).
Additional projectile/action poses remain in the retained original sheet; this
adapter does not claim to reproduce custom gameplay effects or arbitrary motion
controllers. Existing game-definition fields remain under the definition
composition rules, while the neutral visual compiler applies the derived
presentation metadata.

Run `python3 tests/myworld/test-world-builder-npc-visual-source.py` for focused
format, renaming, semantic refusal, source-literal, and evidence checks. Set
`WORLD_BUILDER_NPC_SOURCE_FIXTURE` to an owner-authorized exported tracked fixture
for the optional full-source acceptance case. No reference checkout is built or
executed by this test.
