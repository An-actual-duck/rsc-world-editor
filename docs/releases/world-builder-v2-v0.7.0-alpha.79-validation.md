# World Builder 2 v0.7.0-alpha.79 validation — ACCEPTED

This record accepts the exact restricted pre-gate candidate for World Builder
2 v0.7.0-alpha.79. It opens production packaging through
`release/world-builder-v2/RELEASE-READY`; candidate archives are validation
evidence and are not promoted as production artifacts.

## Acceptance

- Status: **ACCEPTED — RELEASE READY**
- Accepted on: **2026-09-02**
- Accepted by: **project owner**, through the direct instruction to release the
  Alpha.79 work to GitHub
- Restricted candidate World Editor commit:
  `a7be4d71eb78290dc1fe592a45c2bad043914282`
- Locked runtime-provider commit:
  `30a54ee0c39ff96227ca6814dd82348b8d774136`
- Version: `v0.7.0-alpha.79`
- Product identity: `rsc-world-editor-v2`
- World-source identity: `target-adaptive-v1`

Alpha.79 completes the Region Copy/Paste correction sequence. Copy retains its
durable clipboard across the Copy-to-Paste handoff and reports its final result
visibly. Copy and Paste footprint work is scoped to the selected or incoming
region instead of the aggregate destination map. Paste Preview reports a
separate visible completion result. Confirmed overwrite treats terrain,
boundaries, ground items, NPC spawns and roam footprints, and scenery as one
atomic replacement payload. Destination placement collisions require the
existing overwrite confirmation but no longer block the operation. Missing
terrain coverage, incompatible definitions, malformed content, and bounded
inventory exhaustion remain fail-closed blockers.

## Exact candidate artifacts

| Artifact | SHA-256 |
| --- | --- |
| `rsc-world-editor-v2-0.7.0-alpha.79-linux-x64.zip` | `12dc4197175709a83bfea61d598075ad648bc242717fd8a9daca6bcc87a04879` |
| `rsc-world-editor-v2-0.7.0-alpha.79-windows-x64.zip` | `e9dc41399ebbc37ec53986dcbaccd5d0fce98e5b1331de5a5d8acb70fa977099` |
| `SHA256SUMS.txt` | `6f1e6cf9232871ce9487d33996bd00801e5be410b2e8b3d92292b283697487fd` |

Independent inspection reported `automated-archive-inspection-passed`.

| Platform | Inner manifest SHA-256 | Reviewed JRE inventory SHA-256 | Files |
| --- | --- | --- | ---: |
| Linux x64 | `115c4806d6b5b9104655d36439749aa03b356377f6df1463f81a4691c86168cc` | `56e02eae89660c0d7baef03b276f2c8f6ef1749d79403c074dc41ea8f3403c9e` | 434 |
| Windows x64 | `e9640c8cce43ee5ed7c3dbc1b22957aa1301e0d5207d2aa7570e27e5eac04b1c` | `9aaf15bca3b380b3b9099d3097182d85cbc83003d57d0844c8fc36f7c25b2967` | 492 |

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
| All 71 adaptive project-lifecycle tests | PASS |
| All 50 adaptive transaction tests | PASS |
| All 19 map migration-choice tests | PASS |
| Editor full exact-lock `./scripts/test.sh` (26 selections, 419 seconds) | PASS |
| Runtime full `./scripts/test.sh` at the locked revision | PASS |
| `git diff --check` | PASS |
| Restricted candidate build from clean published `main` | PASS |
| Independent inspection of both external candidate archives | PASS |
| Four-family placement replacement and repeatability regressions | PASS |
| External and incoming NPC-footprint replacement regressions | PASS |
| Missing destination coverage remains blocked | PASS |

The complete suite covers contracts, discovery, project lifecycle, packed
conversion, imports, repeat imports, atomic publication, undo, interrupted
recovery, packaging, release gates, archive safety, launch supervision, wide
elevation, and updater rollback.

## Owner acceptance and limitations

- The owner previously completed native Linux server/client, layered map,
  editing, save, import, repeat-import, and fresh disposable-target validation
  for this release line.
- The owner identified the Region Copy/Paste failures through direct native use
  and accepted this exact Alpha.79 candidate for GitHub publication on
  2026-09-02 after the bounded corrections and their verification were
  reported.
- Native Windows application launch and PowerShell updater execution were not
  performed on this Linux host. Windows archive, JRE, launcher, manifest,
  updater, rollback, and transaction contracts passed automated validation.
- Placement collisions are destructive only inside the exact previewed
  replacement set and still require the distinct overwrite confirmation.
  Region Paste Undo and project backup/recovery contracts remain in force.
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
`157055dbbc92138cfeabb7b680dd21558fc67381` under tag
`rsc-world-editor-v2-0.7.0-alpha.79`. The immutable tag retains this accepted
record and release gate. All three assets were downloaded back from GitHub,
verified against the uploaded `SHA256SUMS.txt`, and compared byte-for-byte with
the independently inspected pre-upload files.

| Production artifact | SHA-256 |
| --- | --- |
| `rsc-world-editor-v2-0.7.0-alpha.79-linux-x64.zip` | `d6edf5df2375c29968c402f56daa0037354d5be997dee0d1302168f0c8dd2459` |
| `rsc-world-editor-v2-0.7.0-alpha.79-windows-x64.zip` | `7067b37f64dc800726124b808e6840646fc8e937dae264f1d482e37ad4f02a08` |
| `SHA256SUMS.txt` | `0c5125a0790ca8f43a64808dd76fe773f455006d79bca3d6d1d3054ce4d2d663` |

The public release is
<https://github.com/An-actual-duck/rsc-world-editor/releases/tag/rsc-world-editor-v2-0.7.0-alpha.79>.
Development `main` consumes/removes the gate after publication; a later
release requires a new exact candidate, owner acceptance, validation record,
and gate commit.
