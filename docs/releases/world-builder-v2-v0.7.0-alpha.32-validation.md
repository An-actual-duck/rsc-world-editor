# World Builder 2 v0.7.0-alpha.32 validation — ACCEPTED

This record accepts one exact restricted pre-gate candidate as the validation
basis for World Builder 2 v0.7.0-alpha.32. It opens production packaging
through `release/world-builder-v2/RELEASE-READY`; it does not promote or reuse
the candidate archives. Production archives must be rebuilt from the later
clean, published gate commit.

## Acceptance

- Status: **ACCEPTED — RELEASE READY**
- Accepted on: **2026-08-29**
- Accepted by: **project owner**, with manager verification of the recorded
  automated evidence
- Restricted candidate World Editor commit:
  `ae6c56f74afe07ed71c5ad4bd30df0f9962dd4af`
- Locked runtime-provider commit:
  `e5291460920ec07422f36d2e95cd03d0a5b4b7c3`
- Version: `v0.7.0-alpha.32`
- Product identity: `rsc-world-editor-v2`
- World-source identity: `target-adaptive-v1`

This hotfix makes Custom_Landscape and packed-to-layered migration compatible
with established placement-v3 layered bases after NPC respawn metadata was
introduced in placement v4. When either composition input uses v4, all
isolated placement payloads are upgraded together before relocation; historical
NPCs receive the neutral `respawnSeconds: -1` default. It also reports exact
missing and unexpected schema fields and the responsible source payload.
Original server and selected layered-base inputs remain byte-identical.

## Exact candidate artifacts

| Artifact | SHA-256 |
| --- | --- |
| `rsc-world-editor-v2-0.7.0-alpha.32-linux-x64.zip` | `54a6719a8a99d23c2cd0d048ca758276a61c826fcc966d45b6616bc73877e18c` |
| `rsc-world-editor-v2-0.7.0-alpha.32-windows-x64.zip` | `84ef311e4542e4596ad92f0d6b66cace6b62abf9a9b4eb2d01d3c838c21f08c9` |
| `SHA256SUMS.txt` | `a5fc344e4e5265739314927973ed0697aecec80fc643228f7c5d1c0a0a9d508f` |
| `candidate-archive-inspection.json` | `445279faca33a90928e38b772098e984882cbb6dc5d3445facab24cced418ef1` |

Independent inspection reported `automated-archive-inspection-passed`.

| Platform | Inner manifest SHA-256 | Reviewed JRE inventory SHA-256 | Files |
| --- | --- | --- | ---: |
| Linux x64 | `7a0e9e2d0f7771d5a5ac869f5b6bf14dd5f71156ac28646c63925d0132a27d2d` | `56e02eae89660c0d7baef03b276f2c8f6ef1749d79403c074dc41ea8f3403c9e` | 420 |
| Windows x64 | `891d46aca4d54cbb77fa6598f9fe648de3a0d669af3f52fca60d492f10a466f5` | `9aaf15bca3b380b3b9099d3097182d85cbc83003d57d0844c8fc36f7c25b2967` | 478 |

The inspector SHA-256 was
`0e067b21b4729070f9fbc55c3693cc7e06c534616953b8ddb25e5f09678ffb55`.
It verified clean published sources, the exact runtime lock, exhaustive
manifests, reviewed JRE bytes and modes, application allowlists, an empty
Builder seed, the production runtime marker, and absence of bundled world,
creator, credential, project, log, receipt, and backup data.

## Automated evidence

| Check | Result |
| --- | --- |
| Editor runtime parity check | PASS |
| Exact mixed placement-v3/v4 composition regression | PASS |
| All 65 adaptive project lifecycle tests | PASS |
| Editor full exact-lock `./scripts/test.sh` | PASS |
| `git diff --check` | PASS |
| Restricted candidate build from clean published `main` | PASS |
| Independent inspection of both external candidate archives | PASS |

The full matrix covered contracts, discovery, packed conversion, layered
composition, project lifecycle, Region Copier, import/undo/recovery,
packaging, release gates, archive safety, and updater rollback. Native
PowerShell execution was unavailable on this Linux host; equivalent Windows
archive and updater contracts passed static and fixture validation.

## Owner-native report

The owner requested textual validation without screenshots.

- The exact Editor correction was installed into a clean alpha.31 package and
  run through the previously failing server-map conversion workflow.
- The diagnostic identified `respawnSeconds` entering a placement-v3 payload
  at `placements/global/lp10.json`; the corrected build then completed that
  same conversion successfully.
- The owner confirmed the regression fixed and requested the release update.
- No public server or real production world was changed during validation.

## Accepted limitations

- Native Windows application launch and native PowerShell updater execution
  were not performed on this Linux host. Windows archive, JRE, launcher,
  manifest, updater, rollback, and transaction validation passed.
- Migration and import remain deliberately fail-closed for ambiguous map
  choices, unsupported layouts, changed-after-preview targets, and targets
  that cannot be proved offline.
- Terrain colour authoring retains the established palette. Detached-camera
  Builder mode and object action scripting remain future work.

## Production rule

The candidate archives listed above are validation evidence only. They must
not be copied, renamed, uploaded, or promoted as production release files.
Production archives must be rebuilt after this record and `RELEASE-READY` are
committed and published on clean `main`, using the exact locked runtime and
reviewed JRE inputs.

## Post-publication gate state

The production release was published from gate commit
`3907d3acd2825fba808b5c3069837b5ad5353c2c` as tag
`rsc-world-editor-v2-0.7.0-alpha.32`. That immutable tag retains this accepted
record and the release gate. The production assets were downloaded back from
GitHub, verified against the uploaded `SHA256SUMS.txt`, and compared
byte-for-byte with the independently inspected pre-upload files.

| Production artifact | SHA-256 |
| --- | --- |
| `rsc-world-editor-v2-0.7.0-alpha.32-linux-x64.zip` | `06b814d671c1ccb317914d46575a1c7ecdd59b037490936709d87196d7a503a6` |
| `rsc-world-editor-v2-0.7.0-alpha.32-windows-x64.zip` | `10fdfffc4bef6a9b66c60394f23bec84cfe2711356fdde60cb096eb7e57c70f7` |
| `SHA256SUMS.txt` | `7d1b365e5277a8cc74ec39a09a4aa342274cd03316b8e22023694a32615ad85b` |
| Pre-upload production inspection | `46e8ea23bfae45ae43dd3908f4793e03562ae1686ca88de076af301e9949204b` |

The public release is
<https://github.com/An-actual-duck/rsc-world-editor/releases/tag/rsc-world-editor-v2-0.7.0-alpha.32>.
Development `main` consumes/removes the gate after publication. Any later
release therefore requires a new exact candidate, owner decision, validation
record, and gate commit.
