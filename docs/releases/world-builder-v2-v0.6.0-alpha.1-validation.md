# World Builder 2 v0.6.0-alpha.1 validation — ACCEPTED

This record accepts one exact restricted pre-gate candidate as the validation
basis for World Builder 2 v0.6.0-alpha.1. It opens production packaging through
`release/world-builder-v2/RELEASE-READY`; it does not promote or reuse the
candidate archives. Production archives must be rebuilt from the later clean,
published gate commit.

## Acceptance

- Status: **ACCEPTED — RELEASE READY**
- Accepted on: **2026-08-27**
- Accepted by: **project owner**, with manager verification of the recorded
  automated evidence
- Restricted candidate World Editor commit:
  `0f57e3a52c40e574450cf1c761361664fe35b23b`
- Locked runtime-provider commit:
  `3d339ac2c8041a7b659bfbee186b739ef1221063`
- Version: `v0.6.0-alpha.1`
- Product identity: `rsc-world-editor-v2`
- World-source identity: `target-adaptive-v1`

This release advances the editing-tool workflow: recoverable low-latency
terrain dragging, centered brushes through 7x7, freehand and atomic line tools,
visible line anchors, rectangle terrain and smart-wall authoring, atomic scenery
movement, contextual tool controls, and the consolidated Region Copier. Region
Copier now exposes Copy and Paste as subordinate modes with focused flyouts for
selection, copy/export, preview/confirmation, import, and undo. Region pastes
refresh live without an application restart and portable snapshots preserve
terrain and placement families.

## Exact candidate artifacts

| Artifact | SHA-256 |
| --- | --- |
| `rsc-world-editor-v2-0.6.0-alpha.1-linux-x64.zip` | `b9cdfc8eaeb6612c0c4111e7b6f5c7b854bbc25d3530e0b3fdd1186ba994d269` |
| `rsc-world-editor-v2-0.6.0-alpha.1-windows-x64.zip` | `bdc83496f7516111f39c1c480a7383269e94e86708c656d18426ee370868a40e` |
| `SHA256SUMS.txt` | `2f65ae80b08becfe9d18bff4270b0dc6c430f2f6af318dcc0f5a0f95fa27f18b` |
| `candidate-archive-inspection.json` | `ca221fd8ce69de303d8f4c423a810867d9d6f4e291005eb73c7ee2fac4ef4dd7` |

Independent inspection reported `automated-archive-inspection-passed`.

| Platform | Inner manifest SHA-256 | Reviewed JRE inventory SHA-256 | Files |
| --- | --- | --- | ---: |
| Linux x64 | `9e8c313e040b9dc598f078c6d813a27fecb3f037d6aaa24429a5332cb1dd7166` | `56e02eae89660c0d7baef03b276f2c8f6ef1749d79403c074dc41ea8f3403c9e` | 416 |
| Windows x64 | `ad9f7a9d2af4276917ef3458f1faced79251b2267f5e5b08b9381eb03fe4ea09` | `9aaf15bca3b380b3b9099d3097182d85cbc83003d57d0844c8fc36f7c25b2967` | 474 |

The inspector SHA-256 was
`0e067b21b4729070f9fbc55c3693cc7e06c534616953b8ddb25e5f09678ffb55`.
It verified clean published sources, the exact runtime lock, exhaustive
manifests, reviewed JRE bytes and modes, application allowlists, an empty
Builder seed, the production runtime marker, and absence of bundled world,
creator, credential, project, log, receipt, and backup data.

## Automated evidence

| Check | Result |
| --- | --- |
| Runtime `./scripts/test.sh` at the exact provider commit | PASS |
| Editor runtime parity check | PASS |
| Editor focused candidate suite | PASS |
| Editor full exact-lock `./scripts/test.sh` | PASS |
| `git diff --check` | PASS |
| Restricted candidate build from clean published `main` | PASS |
| Independent inspection of both external candidate archives | PASS |

The complete matrices covered archive safety, release gates and updaters,
adaptive contracts, discovery and conversion, project save/reopen, wide
elevation, low-latency and atomic terrain operations, contextual tool state,
scenery movement, Region Copier geometry and placement closure, portable
snapshot export/import, live paste, undo, transaction rollback, crash recovery,
and strict separation from unrelated game-server repositories.

## Owner-native report

The owner requested textual validation without screenshots.

- The exact independently inspected Linux candidate was extracted into a fresh
  temporary installation and launched visibly.
- The owner reviewed the final contextual Region Copier hierarchy and its Copy
  and Paste modes in the native application.
- The owner reported that the candidate looked correct and accepted it for
  release.
- The editing behaviors included in this candidate had also received owner
  validation during development, including brush drag recovery and latency,
  line and rectangle tools, smart walls, scenery movement persistence, live
  region paste, terrain and scenery copying, portable export/import, and the
  consolidated Region Copier controls.

No screenshot was captured or judged by an AI session. No selected server or
user workspace was modified by release validation.

## Accepted limitations

- Native Windows application launch and native PowerShell updater execution
  were not performed on this Linux host. Windows archive, JRE, launcher,
  manifest, updater, rollback, and cross-platform transaction validation
  passed.
- Terrain colour authoring retains the established palette; true RGB terrain
  materials are outside this release.
- The release retains live-interaction Builder mode. A detached editor camera
  and a process-minimal pure Builder mode remain future work.
- Region Copier supports ordered polygonal snapshots and portable transfer;
  more elaborate reusable structure templates and object action scripting
  remain future work.

## Production rule

The candidate archives listed above are validation evidence only. They must
not be copied, renamed, uploaded, or promoted as production release files.
Production archives must be rebuilt after this record and `RELEASE-READY` are
committed and published on clean `main`, using the exact locked runtime and
reviewed JRE inputs.

## Post-publication gate state

The production release was published from gate commit
`b91fb2c3620c5f71f058c994ffeb3f2bf3493d69` as tag
`rsc-world-editor-v2-0.6.0-alpha.1`. That immutable tag retains this accepted
record and the release gate. The production assets were downloaded back from
GitHub and verified against the uploaded `SHA256SUMS.txt`:

| Production artifact | SHA-256 |
| --- | --- |
| `rsc-world-editor-v2-0.6.0-alpha.1-linux-x64.zip` | `1ea3a91522cc071b045f482278d0da961833df38f22e3777e1ae56e4aa51667a` |
| `rsc-world-editor-v2-0.6.0-alpha.1-windows-x64.zip` | `3822d76982a0ee9ec1e19720fa03cc867781aba6aa06ab095c3ceaaa62f4cae1` |
| `SHA256SUMS.txt` | `2933ab117624cb6668ab5b0a3d9c1735abd6448b9e304d6feab0a36c5895c625` |
| Pre-upload production inspection | `e162fc255718abac97d8047029ad5d8488c88046d69941f70965fb50f05c909f` |

The public downloads were byte-identical to the independently inspected
production artifacts. The published GitHub release is
<https://github.com/An-actual-duck/rsc-world-editor/releases/tag/rsc-world-editor-v2-0.6.0-alpha.1>.
Development `main` consumes/removes the gate after publication. Any later
release therefore requires a new exact candidate, owner decision, validation
record, and gate commit.
