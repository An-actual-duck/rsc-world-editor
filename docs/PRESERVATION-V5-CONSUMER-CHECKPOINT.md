# Preservation map placement v5 consumer checkpoint

This is a verified Editor map-consumer checkpoint, not candidate acceptance.
The manager adopted published provider
`d21021756e59b82844dc16152af60721be1418b5`. Its inventory-bound decoder and the
genuine end-to-end data conversion now pass together with this Editor consumer.

Historical public NPCs include 146 bounded roaming rectangles crossing absent
terrain. The intended contract retains their exact bounds and all multiplicities,
requires every anchor on present terrain, and leaves absent cells blocked. It
neither clamps the bounds nor activates the 1,412 client-only sectors. Placement
v5 explicitly declares `npcRoamCoverage: blocked-void`; older v4 packages retain
their full-terrain coverage requirement. New empty worlds remain v4.

The common Editor placement helper keeps headers closed and packages uniform,
including empty sets. Parsers, composition, new levels, journal normalization,
region save/paste coverage, and export normalization retain explicit v5. Accepted
v5 composition promotes every placement set; normalization and export compaction
cannot silently remove its policy. Effective NPC reconciliation identities include
the policy, separately from immutable original packed-source identities. Genuine
compiled source derivation binds its selected v5 policy and exact input inventory;
ordinary caller-authored discovery cannot grant genuine source authority.

The adopted current provider requires the exact versions 1–5 capability
and five-entry host probe matrix. Removing v5 is a refusal, not a fallback to v4.
The retired adaptive-v2 contract remains unchanged. Earlier old-lock checks are
not substituted for verification against the adopted provider.

Ordinary descriptor-backed discovery requires every actual package encoding to
be advertised by matching target/server/client evidence. A historical descriptor
is not silently rewritten after import. Chained map import instead creates a
private-constructor, transient installed-host rediscovery proof only after the
successful prior receipt, exact action state, unchanged source evidence, and
current host capability/probes have verified. The proof binds the fixed target,
descriptor, configuration, both installed packages, and exact observed target
and project runtime artifact hashes, and rechecks them at use. It is deliberately
not Current Base composition authority or a production runtime-upgrade ledger;
the existing generic custom-host compatibility policy remains separate.
The proof also pins project/snapshot/discovery/runtime metadata, the runtime
inventory, the successful receipt and durable mutation plan, and the optional
migration choice (including absence). Metadata drift between discovery passes
is rejected without repeatedly running the full project verifier at each use.

Editor-only synthetic tests exercise closed headers, duplicate NPCs, present
anchors, bounded void, v4 refusal, mixed empty-set refusal, actual composition,
new levels, journal/region normalization, region coverage routing, export
compaction/fingerprint policy, and current capability removal. They carry no
historical or executable runtime authority. The genuine conversion test now also
requires all 32,410 placements, all 146 unchanged void-crossing NPC bounds and
their complete multiset, 352 present sectors, immutable inputs, reverse parity,
and matching reconciliation. The actual published-pin run passes all of these
assertions, including all 3,609 NPC records with their original multiplicities
and present anchors. Terrain reverse parity reports 352 matches and zero
mismatches; reconciliation matches all four placement families. The initial
run exposed an uppercase `X`/`Y` source-field typo in the test's final multiset
assertion; correcting the test and rerunning the complete invocation passed.

The test-only retention switch keeps a fresh external sanitized probe for the
separate runtime semantic task. It contains only the sealed public input set,
invented connection settings, full decoder/derivation evidence and converted
package. Neither retained fixture paths nor these data proofs grant production
authority. Source-backed descriptor-free project creation and upgrade-preview
integration remain separate work; no synthetic target descriptor is introduced.

Headless acceptance at the adopted pin also passes the complete source-closure,
source-intake, reconciliation, packed-conversion, placement-v5 and map-choice
modules (47 tests), all 42 adaptive-discovery tests, and 18 selected transaction
and project lifecycle regressions. The latter cover actual CLI save/reopen,
portable export, region copy/cut/paste and safety checks, and repeated v4/v5 import
through verified installed-host authority, including stale, forged and drifting
evidence refusal. These fixtures exercise Editor behavior, not live gameplay.
Together with the two genuine-conversion/API tests, all 109 checks passed without
skips. The product manager retains combined integration/full-suite ownership;
this worker did not run GUI or live server/client acceptance.

Current Base effective-definition/gameplay/state equivalence and actual fused-map
ladder collision/removal and client-void behavior remain separate acceptance
requirements. The reviewed overlay correction at (312, 516, level -1) and absent
client background are not pixel-identical preservation. Production activation
and the public candidate gate remain closed.
