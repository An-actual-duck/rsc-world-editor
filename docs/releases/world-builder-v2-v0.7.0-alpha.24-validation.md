# World Builder 2 v0.7.0-alpha.24 validation — ACCEPTED

This record accepts one exact restricted pre-gate candidate as the validation
basis for World Builder 2 v0.7.0-alpha.24. It opens production packaging
through `release/world-builder-v2/RELEASE-READY`; it does not promote or reuse
the candidate archives. Production archives must be rebuilt from the later
clean, published gate commit.

## Acceptance

- Status: **ACCEPTED — RELEASE READY**
- Accepted on: **2026-08-28**
- Accepted by: **project owner**, with manager verification of the recorded
  automated evidence
- Restricted candidate World Editor commit:
  `ca7740da87c8cdda431e3a8549c3f54cecd6b73a`
- Locked runtime-provider commit:
  `47af68b8d9be971bf0d65f53c4971a9ff03fe8c6`
- Version: `v0.7.0-alpha.24`
- Product identity: `rsc-world-editor-v2`
- World-source identity: `target-adaptive-v1`

This release completes the first safe legacy-to-layered map lifecycle exposed
through the desktop launcher. It adds automatic map-source selection, explicit
legacy `Custom_Landscape.orsc` composition, immutable project revisions with a
backup browser, GUI import and restore actions, content-addressed server/client
installation, exact undo/recovery authority, and rediscovery of the installed
layered map on later runs. Successful migration retires the selected legacy
companion from active use while preserving recoverable backup evidence, so it
is not offered for incorporation again.

The release also includes canonical layered level/sector/placement handling,
layer-aware relocation of legacy terrain and placement companions, effective
custom NPC definition/placement reconciliation, and narrowly scoped offline
process checks that refuse a real target server without mistaking unrelated
processes for that server.

## Exact candidate artifacts

| Artifact | SHA-256 |
| --- | --- |
| `rsc-world-editor-v2-0.7.0-alpha.24-linux-x64.zip` | `9eb0757db4a41109133679ce0f45a93429380b41540dc532003740369f8b6f25` |
| `rsc-world-editor-v2-0.7.0-alpha.24-windows-x64.zip` | `2de588ce062a1738e9faac96cc916e0fe5873af4b9f8424e4b63f4d8b6bbfb6c` |
| `SHA256SUMS.txt` | `0cad2a769d88487ce8472bcb0d35a82e88e30ac2a56e52a5113104ad63aa1173` |
| `candidate-archive-inspection.json` | `3f1932256fc445340e60442195a60683b81618c62b37b0f5347212cccabeec35` |

Independent inspection reported `automated-archive-inspection-passed`.

| Platform | Inner manifest SHA-256 | Reviewed JRE inventory SHA-256 | Files |
| --- | --- | --- | ---: |
| Linux x64 | `53c592b5c0bb83f25dd1435083e51dd1f14bff45df06fadc6b18018a23d8add4` | `56e02eae89660c0d7baef03b276f2c8f6ef1749d79403c074dc41ea8f3403c9e` | 419 |
| Windows x64 | `e9651a0ec52f52b0aae3774fcab55eee05698fbd600f827ed3db64c1d9f94cd1` | `9aaf15bca3b380b3b9099d3097182d85cbc83003d57d0844c8fc36f7c25b2967` | 477 |

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

The complete matrices cover strict discovery and conversion, layered-base
selection and normalization, legacy composition, revisions and restoration,
install preview/confirmation, backup, recovery, undo, placement and terrain
canonicalization, content reconciliation, post-install rediscovery, package
and archive safety, update rollback, and the existing editing-tool behavior.

## Owner-native report

The owner requested textual validation without screenshots.

- The exact independently inspected Linux candidate was extracted into the
  designated test-build installation and launched visibly.
- The owner confirmed that Detect Server Map recognized the already imported
  content-addressed layered installation as compatible and did not incorrectly
  route it back through the packed adapter.
- Across the immediately preceding builds of the same accepted workflow, the
  owner exercised legacy companion detection and composition, project launch,
  backup restoration, map import into a disposable server copy, and subsequent
  rediscovery. The exact candidate contains the final rediscovery correction;
  the complete automated transaction fixture repeats the full cycle at the
  accepted source commit.
- The owner reported the entire cycle successful and specifically confirmed
  that a second detection did not offer `Custom_Landscape.orsc` again.
- The owner accepted this state for release.

No screenshot was captured or judged by an AI session. No public server or
real production world was changed during release validation.

## Accepted limitations

- Native Windows application launch and native PowerShell updater execution
  were not performed on this Linux host. Windows archive, JRE, launcher,
  manifest, updater, rollback, and cross-platform transaction validation
  passed.
- The owner did not repeat every migration mutation on the final archive after
  it was produced. The final archive was used for the corrected rediscovery
  step; the earlier steps were owner-tested on the immediately preceding
  builds, and the exact final source passed the complete automated cycle.
- Migration remains deliberately fail-closed for ambiguous map choices,
  unsupported layouts, changed-after-preview targets, and targets that cannot
  be proved offline.
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
`15023bfb6d66537d54735261aaf9663d1009f98d` as tag
`rsc-world-editor-v2-0.7.0-alpha.24`. That immutable tag retains this accepted
record and the release gate. The production assets were downloaded back from
GitHub, verified against the uploaded `SHA256SUMS.txt`, and compared
byte-for-byte with the independently inspected pre-upload files.

| Production artifact | SHA-256 |
| --- | --- |
| `rsc-world-editor-v2-0.7.0-alpha.24-linux-x64.zip` | `22da48d8d6b069810bc531ec885481726448e462a9d2cafa656d5558b3e56af6` |
| `rsc-world-editor-v2-0.7.0-alpha.24-windows-x64.zip` | `bccda34d66ddf1cd465441ad598255aa35931406c998ba141e95eb280770310c` |
| `SHA256SUMS.txt` | `536908746f93789249d05b1b9c2389eb608584567dc6bc3dc7590ad1f7098294` |
| Pre-upload production inspection | `d1854d91e18281279e07db2bb99c735048871b16c592f40241899e1884cc47bb` |

The public release is
<https://github.com/An-actual-duck/rsc-world-editor/releases/tag/rsc-world-editor-v2-0.7.0-alpha.24>.
Development `main` consumes/removes the gate after publication. Any later
release therefore requires a new exact candidate, owner decision, validation
record, and gate commit.
