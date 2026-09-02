# World Builder 2 v0.7.0-alpha.84 validation — ACCEPTED

This record accepts the exact restricted pre-gate candidate for World Builder
2 v0.7.0-alpha.84. It opens production packaging through
`release/world-builder-v2/RELEASE-READY`; candidate archives are validation
evidence and are not promoted as production artifacts.

## Acceptance

- Status: **ACCEPTED — RELEASE READY**
- Accepted on: **2026-09-02**
- Accepted by: **project owner**, after direct Alpha.84 visual validation
- Restricted candidate World Editor commit:
  `556dd5345f1a55b9d0c5a356e21eb14a7f67024a`
- Locked runtime-provider commit:
  `ce7f6d0f63f8877c34b440f36c372c3f78765c7c`
- Version: `v0.7.0-alpha.84`
- Product identity: `rsc-world-editor-v2`
- World-source identity: `target-adaptive-v1`

Alpha.84 fixes item-visual discovery for vanilla items whose active server
patch or world definition changes gameplay fields without replacing the item
art. Those overlays now retain the packaged client's authoritative visual,
matching the installed client runtime contract, instead of receiving a
generated placeholder. The audit found 542 distinct vanilla item IDs that
could encounter the faulty classification; Bronze Arrows at ID 11 reproduced
the visible tan-square symptom.

Explicit visual declarations, changed same-ID base/custom definitions, and all
non-vanilla items remain target-owned and continue through exact target or
provider discovery. The change does not hard-code custom server content or
weaken conflicting-ID safeguards.

## Exact candidate artifacts

| Artifact | SHA-256 |
| --- | --- |
| `rsc-world-editor-v2-0.7.0-alpha.84-linux-x64.zip` | `04b0a614a8b9dbce74d5e854f3054ee6813225b072cdb943ea516ce9f6f4ef4c` |
| `rsc-world-editor-v2-0.7.0-alpha.84-windows-x64.zip` | `bf3902eb1a82f6dca21b11845fbdaa9bf5dfba80ae576fcfcc9ffa36e7a7ebb3` |
| `SHA256SUMS.txt` | `67f19227f4ae2e0bc9cc6502dae63659bd00f6c95a07ac70d0b2225e4fc8ab4e` |
| `candidate-archive-inspection.json` | `6d107536d72bb5c2df3d50758a6e29afa2e29b68c521c0da6d1507727b46feda` |

Independent inspection reported `automated-archive-inspection-passed`.

| Platform | Inner manifest SHA-256 | Reviewed JRE inventory SHA-256 | Files |
| --- | --- | --- | ---: |
| Linux x64 | `d2f03dd91494d13325127e5257ad1f2679e1e5335ea8887cea58fd5fc40ec310` | `56e02eae89660c0d7baef03b276f2c8f6ef1749d79403c074dc41ea8f3403c9e` | 434 |
| Windows x64 | `c8ebce292d7c7efe7765b1a7278d889a526ed8438bc3773bb569ceabcc987970` | `9aaf15bca3b380b3b9099d3097182d85cbc83003d57d0844c8fc36f7c25b2967` | 492 |

The inspector SHA-256 was
`4280d6aa31b5e7170bde755a07350d1efed2dc7e3ccfafab2e29407fd17929a8`.
It verified clean published sources, the exact runtime lock, exhaustive
manifests, reviewed JRE bytes and modes, application allowlists, an empty
Builder seed, the production runtime marker, and absence of bundled world,
creator, credential, project, log, receipt, and backup data.

## Automated evidence

| Check | Result |
| --- | --- |
| Editor full `./scripts/test.sh` (26 selections, 426 seconds) | PASS |
| Focused candidate suite (15 selections, 386 seconds) | PASS |
| All 23 adaptive contract tests | PASS |
| All 41 adaptive discovery tests | PASS |
| All 74 adaptive project-lifecycle tests | PASS |
| All 51 adaptive transaction tests | PASS |
| `git diff --check` | PASS |
| Restricted candidate build from clean published `main` | PASS |
| Independent inspection of both external candidate archives | PASS |
| Gameplay-only vanilla item overlay visual regression | PASS |
| Redefined same-ID and custom item discovery regressions | PASS |

The complete suite covers contracts, discovery, project lifecycle, custom
content reconciliation, packed conversion, imports, repeat imports, atomic
publication, interrupted recovery, packaging, release gates, archive safety,
launch supervision, wide elevation, and updater rollback.

## Owner acceptance and limitations

- The owner directly tested the exact Alpha.84 Linux candidate and confirmed
  the corrected item sprites look good.
- The previously accepted detect, edit, save, repeated import, private-server,
  and client workflow is unchanged by this narrowly scoped classification fix.
- Native Windows application launch and PowerShell updater execution were not
  performed on this Linux host. Windows archive, JRE, launcher, manifest,
  updater, rollback, and transaction contracts passed automated validation.
- Standalone empty-project creation remains intentionally unavailable through
  the desktop launcher until its separate exit-code-1 failure is repaired.
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

