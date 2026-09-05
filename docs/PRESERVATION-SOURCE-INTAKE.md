# Reviewed Preservation source intake

The production `preservation-family-v1` adapter now recognizes a bounded genuine
historical source layout, not the invented four-file transaction fixture.
Production activation remains disabled. This is not candidate acceptance.

## Authority and current scope

The historical identity is commit `c0102e60774ab9c9076aabae49f6f97fb6fc4b00`, tree
`6db5536d795abf34f303bb03b20c43b8cfb9e3fe`. The packaged source closure binds
1,246 source/build/resource records and 22 historical vendor dependencies. The
new `preservation-c0102e-source-intake.json` resource additionally seals 23 public
configuration, map, definition and launcher paths. It contains metadata only.

The source files are required. Vendor dependencies may be absent because the
target is not rebuilt or executed; present dependencies must match exact reviewed
bytes and modes. Arbitrary game binaries are not authenticated by source presence.
Unknown JARs refuse before mutation. Changed or additional plugin source receives
T3 `PORT_REQUIRED`; platform/client/build source changes require a T4 port.
Unmigrated crypto keys, word-filter files, client settings/UID and databases also
remain explicit blockers, not disposable generated state. Tests use invented
side-state sentinels only; the reference's side state is never read.

The bounded input set includes all four active historical server archives:
`server/conf/server/data/maps/maps64.jag`, `maps64.mem`, `land64.jag` and
`land64.mem`. Historical `WorldLoader.loadWorld` first opens those archives for
`based_map_data: 64` and `custom_landscape: false`. It uses the ZIP only when
both map JAG/MEM archives are unavailable. Omitting these files would test a
fallback and cannot prove default Preservation map fidelity.

The set also includes the historical pair of
`server/conf/server/data/Authentic_Landscape.orsc` and
`Client_Base/Cache/video/Authentic_Landscape.orsc`, not a fabricated
`client/cache/landscape.pack`. Both reviewed files contain 945,225 bytes with
SHA-256 `48ed0e1634b870888f96c0bc3e31cbaf152570b913140fdfd3596897a3eb29fa`.
The matching client uses that ZIP, while the server normally selects JAG/MEM.
Their identical ZIP hashes do not establish server/client map parity. Lossless
historical JAG/MEM decoding and selected-world comparison are still required.
Production preview identifies `historical-jag-conversion-pending` and its explicit
readiness blocker. Descriptor-backed ZIP evidence cannot substitute for this
unfinished migration path.

## Field-wise map reconciliation (data proof, not activation)

The compiled reconciliation component verifies the reviewed provider decoder's
complete 1,680-outcome inventory, its 352 raw sectors and all 1,764 client ZIP
entries. It never modifies either input. Separate reverse proofs retain the
historical server bytes and the derived presentation bytes; the two are not
misrepresented as byte-identical source maps.

Elevation, ground texture and roof values come from the historical client because
the reviewed server does not consume them for gameplay. Overlay, walls, diagonal
values and effective placements retain server authority. The 291 client scenery
markers in two Lumbridge sectors serve the historical login background only;
they are retained in source provenance, not added to the live world.

One reviewed discrepancy at `(312,516,level -1)` retains server overlay `0` instead
of client overlay `8`. The latter independently blocks terrain. Ladder 199 at the
same tile blocks while present, but that does not prove equivalent behavior after
its removal. The report displays the correction and keeps interaction/removal
verification explicitly pending. Pixel-identical client preservation is not
claimed. Any unfamiliar discrepancy remains a pre-conversion blocker.

The 1,412 client-only sectors comprise 1,328 server-probed absences and 84 sectors
outside the server probe domain. Their exact bytes remain source provenance;
they are not promoted into playable terrain. Adjacent background rendering may
differ and is explicitly reported, not silently described as visual parity.

Effective stock content includes seven definition files (base and Custom item/NPC
registries plus tile, door and scenery XML), all four base placement families and
the active discontinued scenery/NPC files. Historical settings select these last
two files even on stock Preservation. NPC multiplicity must survive composition.

The pure reconciliation tests currently accept an explicit separately verified
decoder fixture through `WORLD_BUILDER_PRESERVATION_DECODED_MAP`, containing
`sectors/` and `evidence.json`, in addition to the exact public Git source input.
That fixture validates the data consumer only, not provider invocation authority.
Production remains blocked until invocation is bound to the selected provider's
contract/core inventory and the complete conversion/project output proof is wired.

Effective configuration follows connections-first and local-replaces-named
precedence. Sealed hashes of 291 nonempty historical configuration values identify
unchanged defaults without embedding their plaintext values. Existing supported
name, bind, port and experience overrides retain typed translation. Missing,
unknown or changed nonportable settings require a port; they are not silently
dropped. Null/ambiguous parser semantics remain blockers. Source configuration
may be private `0600`; source code retains reviewed `0644`, and historical
launcher files retain their recorded mode but are never executed.

The isolated staging fixture retains real converter/database-migrator execution,
but has distinct `preservation-staging-fixture-v1` execution identity and
`synthetic-fixture` adapter authority. It remains activation-disabled. The public
CLI never selects this fixture based on target paths or bytes.

## Reproducible positive evidence

`test-world-builder-preservation-source-intake.py` always verifies sealed-resource
integrity, distinct fixture/production identities and rejection of invented
topology. Its genuine positive and customization tests require an explicitly
provided Git object store containing the exact historical commit:

```bash
WORLD_BUILDER_PRESERVATION_SOURCE_GIT=/path/to/reviewed-public-git-store \
  ./scripts/test.sh --file test-world-builder-preservation-source-intake.py
```

The test checks commit/tree and every selected blob hash before materializing a
new temporary external source fixture. It reads only the packaged allowlist at
that exact commit, never working-tree files, branches, credentials, databases,
vendor/game binaries or ignored/untracked state. It does not build or launch
historical code. Connection settings are deliberately invented literal-loopback
SQLite configuration; no historical connection secrets are read.

The owner-designated read-only reference supplied the historical source/map bytes
for development verification. Its filesystem path is not a test default. Historical
contributor documentation names `https://orsc.dev/open-rsc/Game` as the public
source origin; an available immutable public mirror/provider fixture acquisition
route must still be established before this becomes a required portable candidate
row. Missing external source is reported as unavailable, never a synthetic pass.

## Remaining intake work

- Account for the rest of a complete checkout's reviewed inactive content and
  generated files without globally ignoring unknown executable inputs.
- Bind initialized and populated state through provider schema evidence, not a
  fixture database file hash.
- Implement descriptor-free JAG/MEM landscape discovery and complete map
  conversion with the actual historical definition/placement selection rules.
  The old fallback requires `custom_landscape: true` and MyWorld-specific inputs;
  it is not a Preservation adapter.
- Feed current project capabilities, installation, subsequent managed upgrades,
  map-only import and desktop selection through those proven inputs.

Do not describe source classification, successful staging, or an unavailable
external fixture as a usable server-upgrade candidate.
