# World Builder 2 v0.7.0-alpha.30 validation — ACCEPTED

This record accepts one exact restricted pre-gate candidate as the validation
basis for World Builder 2 v0.7.0-alpha.30. It opens production packaging
through `release/world-builder-v2/RELEASE-READY`; it does not promote or reuse
the candidate archives. Production archives must be rebuilt from the later
clean, published gate commit.

## Acceptance

- Status: **ACCEPTED — RELEASE READY**
- Accepted on: **2026-08-29**
- Accepted by: **project owner**, with manager verification of the recorded
  automated evidence
- Restricted candidate World Editor commit:
  `d1017c44c50705b9883250a046b0e5db31185726`
- Locked runtime-provider commit:
  `7d4690045b7ff0902e888130fd45bbcf8114890e`
- Version: `v0.7.0-alpha.30`
- Product identity: `rsc-world-editor-v2`
- World-source identity: `target-adaptive-v1`

This release hardens the live editing path introduced in v0.7.0-alpha.24.
Large adaptive saves are bounded and responsive, queued saves report and
complete only after authoritative edit responses, and reopening a project
retains the saved changes. Scenery placement no longer rebuilds unchanged
presentation data, no longer receives a competing legacy static-scene stream,
and commits the authoritative inner scene and outer presentation ring only as
one complete protocol-v8 product. The last complete scene remains visible
while replacement pages arrive, preventing whole-scene fallback-object flashes.

## Exact candidate artifacts

| Artifact | SHA-256 |
| --- | --- |
| `rsc-world-editor-v2-0.7.0-alpha.30-linux-x64.zip` | `d3a32882b711e6b0acc58215f2f60d81919622689035db08c19f164682c47e30` |
| `rsc-world-editor-v2-0.7.0-alpha.30-windows-x64.zip` | `60376433faa8507a52f9aef565b406344531238183c51fb156db0c200f0bc05c` |
| `SHA256SUMS.txt` | `68c0d13a1028075a0a671aef83c9708211c4aca9cbea6d9c21c991b1536d1a74` |
| `candidate-archive-inspection.json` | `3d7f660b0cc6426058a636febaefa92e7e5d128484365e4abf3446cb31d05b93` |

Independent inspection reported `automated-archive-inspection-passed`.

| Platform | Inner manifest SHA-256 | Reviewed JRE inventory SHA-256 | Files |
| --- | --- | --- | ---: |
| Linux x64 | `d3bca6df092dacf1c548512cd345fd7d74c76f7395c3c6c7c9bf3f0d7ac2287e` | `56e02eae89660c0d7baef03b276f2c8f6ef1749d79403c074dc41ea8f3403c9e` | 419 |
| Windows x64 | `21cac55973f29bdf1084bd4e0e1570dde9a3a87e3c52628bdd630b38205db1a9` | `9aaf15bca3b380b3b9099d3097182d85cbc83003d57d0844c8fc36f7c25b2967` | 477 |

The inspector SHA-256 was
`0e067b21b4729070f9fbc55c3693cc7e06c534616953b8ddb25e5f09678ffb55`.
It verified clean published sources, the exact runtime lock, exhaustive
manifests, reviewed JRE bytes and modes, application allowlists, an empty
Builder seed, the production runtime marker, and absence of bundled world,
creator, credential, project, log, receipt, and backup data.

## Automated evidence

| Check | Result |
| --- | --- |
| Runtime full `make test` at the exact provider commit | PASS |
| Runtime adaptive real built client/server login lifecycle | PASS |
| Runtime partial/complete protocol-v8 scene product regressions | PASS |
| Editor runtime parity check | PASS |
| Editor focused candidate suite | PASS |
| Editor full exact-lock `./scripts/test.sh` | PASS |
| `git diff --check` | PASS |
| Restricted candidate build from clean published `main` | PASS |
| Independent inspection of both external candidate archives | PASS |

The complete matrices cover discovery, conversion, project isolation,
authoritative edit/save completion, reopening, layered placement and terrain,
scene baseline paging, static presentation, package and archive safety,
updater rollback, import recovery, and existing editing-tool behavior.

## Owner-native report

The owner requested textual validation without screenshots.

- The exact independently inspected Linux alpha.30 candidate was extracted
  into the designated test-build installation and used for the final test.
- The owner exercised scenery placement after the prior candidate revealed a
  whole-scene flash populated with object ID 0.
- With alpha.30, the owner reported the issue resolved and everything working,
  and approved moving to release.
- Save and reopen behavior had already been exercised on the immediately
  preceding exact workflow, while the automated exact-commit matrix repeats
  the lifecycle and save completion boundaries.

No screenshot was captured or judged by an AI session. No public server or
real production world was changed during release validation.

## Accepted limitations

- Native Windows application launch and native PowerShell updater execution
  were not performed on this Linux host. Windows archive, JRE, launcher,
  manifest, updater, rollback, and cross-platform transaction validation
  passed.
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
`ff0e6f0bd5ca318f31d72e3de1db54a9953e5b97` as tag
`rsc-world-editor-v2-0.7.0-alpha.30`. That immutable tag retains this accepted
record and the release gate. The production assets were downloaded back from
GitHub, verified against the uploaded `SHA256SUMS.txt`, and compared
byte-for-byte with the independently inspected pre-upload files.

| Production artifact | SHA-256 |
| --- | --- |
| `rsc-world-editor-v2-0.7.0-alpha.30-linux-x64.zip` | `657ab5040ba68f450a37f6f341d150a437da6ca20b17b4e77937ee5a9b605ad3` |
| `rsc-world-editor-v2-0.7.0-alpha.30-windows-x64.zip` | `3cc7d4b43f6607ec9fd7065ceb2c9368fb1b8986051a6994adc5658ba424c4ff` |
| `SHA256SUMS.txt` | `decea241a0d3e0c2946ba33c29cd7735f2eeca5ea4aa4da6c081b5c864cbb32f` |
| Pre-upload production inspection | `e51e7f30826331d8f2adc156978e0a68ee273686a46585235d8fc85e23c31dc3` |

The public release is
<https://github.com/An-actual-duck/rsc-world-editor/releases/tag/rsc-world-editor-v2-0.7.0-alpha.30>.
Development `main` consumes/removes the gate after publication. Any later
release therefore requires a new exact candidate, owner decision, validation
record, and gate commit.
