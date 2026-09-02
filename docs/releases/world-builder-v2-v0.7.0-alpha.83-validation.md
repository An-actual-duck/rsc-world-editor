# World Builder 2 v0.7.0-alpha.83 validation — ACCEPTED

This record accepts the exact restricted pre-gate candidate for World Builder
2 v0.7.0-alpha.83. It opens production packaging through
`release/world-builder-v2/RELEASE-READY`; candidate archives are validation
evidence and are not promoted as production artifacts.

## Acceptance

- Status: **ACCEPTED — RELEASE READY**
- Accepted on: **2026-09-02**
- Accepted by: **project owner**, after direct end-to-end server validation and
  confirmation of the final launcher safeguard
- Restricted candidate World Editor commit:
  `2692f819c7297ade1f037d083f321a584024e761`
- Locked runtime-provider commit:
  `ce7f6d0f63f8877c34b440f36c372c3f78765c7c`
- Version: `v0.7.0-alpha.83`
- Product identity: `rsc-world-editor-v2`
- World-source identity: `target-adaptive-v1`

Alpha.83 expands server-owned custom-content discovery so supplemental NPCs,
item visuals, and their effective identities are derived from the selected
server rather than a Spoiled Milk-specific custom registry. Supplemental NPC
catalogs are reconciled into a canonical sequential project registry, while
the installed runtime now honors their declared IDs across separately named
catalogs instead of silently reordering them by filename. This preserves the
new attackable Green Dragon at ID 862 and the nonattackable Gorak at ID 861.

The release retains the approved detect, isolated edit, save, export, repeated
import, upgraded private-server, and client workflow. Standalone empty-project
creation currently exits unsuccessfully, so its launcher button and File-menu
entry are deliberately hidden for this release. The underlying implementation
is retained for later repair; no existing server-project workflow was changed.

## Exact candidate artifacts

| Artifact | SHA-256 |
| --- | --- |
| `rsc-world-editor-v2-0.7.0-alpha.83-linux-x64.zip` | `e39c28f55406f48a68aeec700c5d36c536649e545655dfa015ee837bd180312d` |
| `rsc-world-editor-v2-0.7.0-alpha.83-windows-x64.zip` | `722bea9d61492c2565bffeb63aa3265097e702c89bbd355e9c3bab7a63af9907` |
| `SHA256SUMS.txt` | `80d347b532c32de9f31c2ebbaabc47827850ad9b006045f727b6065b9d713c4f` |
| `candidate-archive-inspection.json` | `42b2140e1631b572c3c039c403a395bed84d3ffd10c36e9abaf404d2436405d2` |

Independent inspection reported `automated-archive-inspection-passed`.

| Platform | Inner manifest SHA-256 | Reviewed JRE inventory SHA-256 | Files |
| --- | --- | --- | ---: |
| Linux x64 | `256a15601a55d343c6e3798225eb6d9d305657d43d9c5e6e7026522b51e2b099` | `56e02eae89660c0d7baef03b276f2c8f6ef1749d79403c074dc41ea8f3403c9e` | 434 |
| Windows x64 | `28ca0c970bcdca8d4fddae90deaa5e949127457e338611635063d3843add6750` | `9aaf15bca3b380b3b9099d3097182d85cbc83003d57d0844c8fc36f7c25b2967` | 492 |

The inspector SHA-256 was
`4280d6aa31b5e7170bde755a07350d1efed2dc7e3ccfafab2e29407fd17929a8`.
It verified clean published sources, the exact runtime lock, exhaustive
manifests, reviewed JRE bytes and modes, application allowlists, an empty
Builder seed, the production runtime marker, and absence of bundled world,
creator, credential, project, log, receipt, and backup data.

## Automated evidence

| Check | Result |
| --- | --- |
| Editor/runtime exact-lock parity | PASS |
| All 23 adaptive contract tests | PASS |
| All 41 adaptive discovery tests | PASS |
| All 73 adaptive project-lifecycle tests | PASS |
| All 51 adaptive transaction tests | PASS |
| All 19 map migration-choice tests | PASS |
| Editor full exact-lock `./scripts/test.sh` (26 selections, 426 seconds) | PASS |
| Runtime full `./scripts/test.sh` at the locked revision | PASS |
| `git diff --check` | PASS |
| Restricted candidate build from clean published `main` | PASS |
| Independent inspection of both external candidate archives | PASS |
| Supplemental NPC split-catalog declared-ID regression | PASS |
| Empty-project launcher button and File-menu absence regression | PASS |

The complete suite covers contracts, discovery, project lifecycle, custom
content reconciliation, packed conversion, imports, repeat imports, atomic
publication, interrupted recovery, packaging, release gates, archive safety,
launch supervision, wide elevation, and updater rollback.

## Owner acceptance and limitations

- The owner completed native Linux detection, editing, save, import, private
  server launch, client launch, and repeatability validation on Alpha.82 and
  reported the complete workflow working correctly.
- Alpha.83 differs from that accepted candidate only by hiding the broken
  standalone empty-project button and matching File-menu entry. The owner
  directly confirmed those entry points are absent in the exact Alpha.83
  candidate on 2026-09-02.
- Native Windows application launch and PowerShell updater execution were not
  performed on this Linux host. Windows archive, JRE, launcher, manifest,
  updater, rollback, and transaction contracts passed automated validation.
- Standalone empty-project creation is intentionally unavailable through the
  desktop launcher until its exit-code-1 failure is separately repaired.
- Import remains fail closed for unsupported layouts, changed-after-preview
  targets, ambiguous map choices, mismatched installed packages, and targets
  that cannot be proved offline.
- No public server or production world was changed during validation.

## Production rule

The candidate archives listed above must not be copied, renamed, uploaded, or
promoted. Production archives must be rebuilt from the later clean, published
gate commit using the exact locked runtime and reviewed JRE inputs. After the
GitHub release is verified, this record will retain the production tag and
artifact hashes while development `main` consumes the release gate.

## Post-publication gate state

The production release was rebuilt from and published at gate commit
`3967eeca9f585d13bc3730b4127b3208d5ed1f35` under tag
`rsc-world-editor-v2-0.7.0-alpha.83`. The full release suite passed all 26
selections in 427 seconds from that exact commit. Independent inspection of the
fresh production archives reported `automated-archive-inspection-passed`; its
external evidence document has SHA-256
`3df6eeba6805db89507727ea793dc158f8966a6b70d20df2ea394a5effee520c`.

| Production artifact | SHA-256 |
| --- | --- |
| `rsc-world-editor-v2-0.7.0-alpha.83-linux-x64.zip` | `d2a471eb49418d30d46c2d1c46dd8f151511167473e374d6b841b1fd2e3b806d` |
| `rsc-world-editor-v2-0.7.0-alpha.83-windows-x64.zip` | `895d6ac96b37c35bad2684d3c4b5ab9559981f0ca7b98ed46d9d21a05d8c0b48` |
| `SHA256SUMS.txt` | `a97ac43d360bdb42a7defa70b404f0ccb48c96e016f6df8c112afbb5daf8f6a9` |

All three assets were downloaded back from GitHub, verified against the
uploaded `SHA256SUMS.txt`, and compared byte-for-byte with the independently
inspected pre-upload files. The public release is
<https://github.com/An-actual-duck/rsc-world-editor/releases/tag/rsc-world-editor-v2-0.7.0-alpha.83>.
Development `main` consumes/removes the gate after publication; a later
release requires a new exact candidate, owner acceptance, validation record,
and gate commit.
