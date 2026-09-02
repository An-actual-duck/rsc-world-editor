# World Builder 2 v0.7.0-alpha.86 validation — ACCEPTED

This record accepts the exact restricted pre-gate candidate for World Builder
2 v0.7.0-alpha.86. It opens production packaging through
`release/world-builder-v2/RELEASE-READY`; candidate archives are validation
evidence and are not promoted as production artifacts.

## Acceptance

- Status: **ACCEPTED — RELEASE READY**
- Accepted on: **2026-09-02**
- Accepted by: **project owner**, after confirming paste behavior and directing
  Alpha.86 publication
- Restricted candidate World Editor commit:
  `b1a90a4210965c03b1531446063f2d041e35f648`
- Locked runtime-provider commit:
  `67be8ea7bb309a54df6f36ca969261b13ef0c155`
- Version: `v0.7.0-alpha.86`
- Product identity: `rsc-world-editor-v2`
- World-source identity: `target-adaptive-v1`

Alpha.86 corrects the region-paste collision preview without changing paste
mutation behavior. Placements such as NPCs may be anchored outside a selected
region while their represented roam footprint crosses into it. The preview
previously drew its informational `!` at those distant spawn anchors, making
valid footprint notices look like scattered false flags. It now draws each
crossing notice at the first actual represented tile inside the paste region.

The accepted build also includes the Alpha.85 exact-destination no-op handling:
when a paste destination already matches the copied region exactly, the tool
reports that state instead of failing with `CONTRACT_VALUE_INVALID`. Terrain,
boundaries, NPCs, scenery, ground items, overwrite confirmation, undo, and
backup behavior remain unchanged.

## Exact candidate artifacts

| Artifact | SHA-256 |
| --- | --- |
| `rsc-world-editor-v2-0.7.0-alpha.86-linux-x64.zip` | `9a08f2a6b0697a3d49c52597536b82ed60ffb14cef3ddc7620165a526a24d1cd` |
| `rsc-world-editor-v2-0.7.0-alpha.86-windows-x64.zip` | `2de046ce09957d2feb2f214dac61162392fdf21ceeef14f8cb24bb43cf5c5ed9` |
| `SHA256SUMS.txt` | `023b8a6b7fb9bcb357dcef112e19cee2166f8a9d22e0c8ece26a49d99d96f8f3` |
| `candidate-archive-inspection.json` | `7e494c96e598f4f53c4cff63112fb40f71ab2de81ed8a12aa5f1666ec69c9e47` |

Independent inspection reported `automated-archive-inspection-passed`.

| Platform | Inner manifest SHA-256 | Reviewed JRE inventory SHA-256 | Files |
| --- | --- | --- | ---: |
| Linux x64 | `78bcca49622354c04652094f31afb8e17c0fc500e231b6d6c00f05ea910a3be8` | `56e02eae89660c0d7baef03b276f2c8f6ef1749d79403c074dc41ea8f3403c9e` | 434 |
| Windows x64 | `f64eec14819c0f3b776d4acb0371b1e83c87307baa72df0a1fb02a7de6e5fd76` | `9aaf15bca3b380b3b9099d3097182d85cbc83003d57d0844c8fc36f7c25b2967` | 492 |

The inspector SHA-256 was
`4280d6aa31b5e7170bde755a07350d1efed2dc7e3ccfafab2e29407fd17929a8`.
It verified clean published sources, the exact runtime lock, exhaustive
manifests, reviewed JRE bytes and modes, application allowlists, an empty
Builder seed, the production runtime marker, and absence of bundled world,
creator, credential, project, log, receipt, and backup data.

## Automated evidence

| Check | Result |
| --- | --- |
| Editor full `./scripts/test.sh` (26 selections, 420 seconds) | PASS |
| Focused candidate suite (15 selections, 397 seconds) | PASS |
| Locked runtime-provider full `./scripts/test.sh` | PASS |
| All 23 adaptive contract tests | PASS |
| All 41 adaptive discovery tests | PASS |
| All 74 adaptive project-lifecycle tests | PASS |
| All 51 adaptive transaction tests | PASS |
| Region crossing-marker location regression | PASS |
| Region overwrite/replacement behavior regression | PASS |
| `git diff --check` | PASS |
| Restricted candidate build from clean published `main` | PASS |
| Independent inspection of both external candidate archives | PASS |

The first focused-matrix attempt overlapped the runtime suite and one lifecycle
fixture correctly refused to reserve port 43594. After the runtime suite
completed, the focused matrix was rerun alone and passed all 15 selections.
This orchestration-only refusal did not affect candidate artifacts or product
state.

## Owner acceptance and limitations

- The owner confirmed region pasting works correctly, identified only the
  misleading external collision markers, and explicitly directed publication
  after the narrow Alpha.86 correction was prepared.
- The previously accepted detect, edit, save, repeated import, private-server,
  and client workflows are unchanged.
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

## Post-publication gate state

The production release was rebuilt from and published at gate commit
`c9219fcd181891e1558c627727767615e8b5eb99` under tag
`rsc-world-editor-v2-0.7.0-alpha.86`. The full Editor release suite passed all
26 selections in 423 seconds from that exact commit, and the complete locked
runtime-provider suite passed. Independent inspection of the fresh production
archives reported `automated-archive-inspection-passed`; its external evidence
document has SHA-256
`57a54589f3c3ca9753e501e07e7b80f5fc07a771e1ca091ba697fefad5091958`.

| Production artifact | SHA-256 |
| --- | --- |
| `rsc-world-editor-v2-0.7.0-alpha.86-linux-x64.zip` | `f6ed92d1ff37b5f04a44bbe13b60e781f09ca9f2f3cc86fb5e12a672ac5c1bbf` |
| `rsc-world-editor-v2-0.7.0-alpha.86-windows-x64.zip` | `aa0af27be816792b6447815d2ba26bb0469c26af75d001e9ce0f8a4c9bc46aff` |
| `SHA256SUMS.txt` | `32f1162d1d35ec7ca91364990d79c92e5c44fe4e19b18babff4bb2a56b5a23a8` |

All three assets were downloaded back from GitHub, verified against the
uploaded `SHA256SUMS.txt`, and compared byte-for-byte with the independently
inspected pre-upload files. The public release is
<https://github.com/An-actual-duck/rsc-world-editor/releases/tag/rsc-world-editor-v2-0.7.0-alpha.86>.
Development `main` consumes/removes the gate after publication; a later
release requires a new exact candidate, owner acceptance, validation record,
and gate commit.
