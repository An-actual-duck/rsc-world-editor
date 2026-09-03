# World Builder 2 v0.7.0-alpha.88 validation — ACCEPTED

This record accepts the exact restricted pre-gate candidate for World Builder
2 v0.7.0-alpha.88. It opens production packaging through
`release/world-builder-v2/RELEASE-READY`; candidate archives are validation
evidence and are not promoted as production artifacts.

## Acceptance

- Status: **ACCEPTED — RELEASE READY**
- Accepted on: **2026-09-02**
- Accepted by: **project owner**, after testing Alpha.88 and directing its
  publication
- Restricted candidate World Editor commit:
  `233568516e72ddaf85f8af5da8f80b8b96866135`
- Locked runtime-provider commit:
  `23db5a02fd51087bf7390182938fbb7f6857c732`
- Version: `v0.7.0-alpha.88`
- Product identity: `rsc-world-editor-v2`
- World-source identity: `target-adaptive-v1`

Alpha.88 corrects scenery disappearing from surrounding chunks when an item is
placed with the ground-item editor. Ground-item-only scene telemetry was
unconditionally triggering static-scene pruning and reconstruction even though
the packet did not carry a changed static-scene product.

The client now distinguishes packets that change or contribute to static
scenery from item-only telemetry. It reconciles legacy scenery lists and static
presentation only for the former, while continuing to record diagnostics and
apply the ground-item update for the latter. A changed static-scene identity
still requests the complete fail-closed reconciliation path.

## Exact candidate artifacts

| Artifact | SHA-256 |
| --- | --- |
| `rsc-world-editor-v2-0.7.0-alpha.88-linux-x64.zip` | `f696ddc884627097c7e395448552243ee935905ee8d9fb278a9ddcabb8cbde03` |
| `rsc-world-editor-v2-0.7.0-alpha.88-windows-x64.zip` | `f163ffa763b36d4c439c6ded1b4c37f503df32a5e4d05e205acdb07d93e5e4cc` |
| `SHA256SUMS.txt` | `df4b6187cb7fc721ea2199369a16c0452f828b1a417b97b3c2166c38c4616c9b` |
| `candidate-archive-inspection.json` | `f4114bfc2c7b7d8c5afdcf3d55bfb270064abd841745db9a455ab56a077fca56` |

Independent inspection reported `automated-archive-inspection-passed`.

| Platform | Inner manifest SHA-256 | Reviewed JRE inventory SHA-256 | Files |
| --- | --- | --- | ---: |
| Linux x64 | `e2c4b92216b7f3115969810c17e5ee6517c72bd2be22c34e09615279af1946e2` | `56e02eae89660c0d7baef03b276f2c8f6ef1749d79403c074dc41ea8f3403c9e` | 434 |
| Windows x64 | `17d8d2d29de5c86e9c32f81a99f95b1bd1be1b82f7c14ca3c17720aeb0655795` | `9aaf15bca3b380b3b9099d3097182d85cbc83003d57d0844c8fc36f7c25b2967` | 492 |

## Automated evidence

| Check | Result |
| --- | --- |
| Editor full `./scripts/test.sh` (26 selections, 422 seconds) | PASS |
| Locked runtime-provider full `./scripts/test.sh` | PASS |
| Runtime server and client compilation | PASS |
| Item-only scene update and static-scenery preservation regression | PASS |
| `git diff --check` | PASS |
| Restricted candidate build from clean published `main` | PASS |
| Independent inspection of both external candidate archives | PASS |

## Owner acceptance and limitations

- The owner tested the Alpha.88 candidate and explicitly directed publication.
- Previously accepted detect, edit, save, repeated import, private-server,
  client, copy/paste overwrite, undo, and backup workflows are unchanged.
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
`ba04b4a43f09b6e00a477f6f27b8c8d5397c7754` under tag
`rsc-world-editor-v2-0.7.0-alpha.88`. The full Editor release suite passed all
26 selections in 422 seconds from that exact commit, and the complete locked
runtime-provider suite passed. Independent inspection of the fresh production
archives reported `automated-archive-inspection-passed`; its external evidence
document has SHA-256
`af2757273fc995ced188d64ee797671fd3ff920e1107a831304faeb46a82bfa4`.

| Production artifact | SHA-256 |
| --- | --- |
| `rsc-world-editor-v2-0.7.0-alpha.88-linux-x64.zip` | `50a460be5d8bf37d1f98b7d342652da5d69e0938387b5ab7795170f3f1940d0f` |
| `rsc-world-editor-v2-0.7.0-alpha.88-windows-x64.zip` | `bac88984ed35571721da61f0ca08bbfbff07511256771b224678217c4206e156` |
| `SHA256SUMS.txt` | `d8adbc44173509aeffa4681e676882728f6ce84920e4ba430e70bb7edb104415` |

All three assets were downloaded back from GitHub, verified against the
uploaded `SHA256SUMS.txt`, and compared byte-for-byte with the independently
inspected pre-upload files. The public release is
<https://github.com/An-actual-duck/rsc-world-editor/releases/tag/rsc-world-editor-v2-0.7.0-alpha.88>.
Development `main` consumes/removes the gate after publication; a later
release requires a new exact candidate, owner acceptance, validation record,
and gate commit.
