# World Builder 2 v0.7.0-alpha.31 validation — ACCEPTED

This record accepts one exact restricted pre-gate candidate as the validation
basis for World Builder 2 v0.7.0-alpha.31. It opens production packaging
through `release/world-builder-v2/RELEASE-READY`; it does not promote or reuse
the candidate archives. Production archives must be rebuilt from the later
clean, published gate commit.

## Acceptance

- Status: **ACCEPTED — RELEASE READY**
- Accepted on: **2026-08-29**
- Accepted by: **project owner**, with manager verification of the recorded
  automated evidence
- Restricted candidate World Editor commit:
  `fc869ab22896f7e94daf14105be68475a6797c3e`
- Locked runtime-provider commit:
  `e5291460920ec07422f36d2e95cd03d0a5b4b7c3`
- Version: `v0.7.0-alpha.31`
- Product identity: `rsc-world-editor-v2`
- World-source identity: `target-adaptive-v1`

This release adds authorable NPC respawn delays throughout layered projects and
portable Region Snapshot v3 bundles, makes placement subtools open their own
flyouts, and hardens signed-level navigation. A missing level created through
the Builder command is now activated immediately in the authenticated client
session, so manual level creation and automatic stair/ladder pairing no longer
require a save and reopen cycle.

## Exact candidate artifacts

| Artifact | SHA-256 |
| --- | --- |
| `rsc-world-editor-v2-0.7.0-alpha.31-linux-x64.zip` | `9a88e0869acecadf1424ab6e92ae9b0b79ec9386ff69ae79c6533a2e678ae2e3` |
| `rsc-world-editor-v2-0.7.0-alpha.31-windows-x64.zip` | `4a173f49d5bd043ba628b8d597ef1ec00f07e5796e8e4c50b36fcf2efa1db3ca` |
| `SHA256SUMS.txt` | `f303c0251083b88213ce18afdbe84a64e7b7d7a64113174654633d4262dac521` |
| `candidate-archive-inspection.json` | `8fe0dffd0e664589e06a0edb6253d799274593f636310bac4dde817072e3c35f` |

Independent inspection reported `automated-archive-inspection-passed`.

| Platform | Inner manifest SHA-256 | Reviewed JRE inventory SHA-256 | Files |
| --- | --- | --- | ---: |
| Linux x64 | `36f31cabc78dcbd130a55410ab40ff4f684aef81564dec2fa7bfe49266b120b8` | `56e02eae89660c0d7baef03b276f2c8f6ef1749d79403c074dc41ea8f3403c9e` | 420 |
| Windows x64 | `a6ee8b5f608c2924bb7c88ccace561d1660749dd5adbbd6b8940c1765aa5ccc2` | `9aaf15bca3b380b3b9099d3097182d85cbc83003d57d0844c8fc36f7c25b2967` | 478 |

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
| Runtime adaptive real built client/server lifecycle | PASS |
| Runtime NPC respawn, signed-level, and live generated-level regressions | PASS |
| Editor runtime parity check | PASS |
| Editor focused candidate suite | PASS |
| Editor full exact-lock `./scripts/test.sh` | PASS |
| `git diff --check` | PASS |
| Restricted candidate build from clean published `main` | PASS |
| Independent inspection of both external candidate archives | PASS |

The focused candidate matrix repeated archive safety, discovery, conversion,
project lifecycle, transactions, runtime supervision, updater rollback, and
product-generation tests. Native PowerShell execution was unavailable on this
Linux host; equivalent Windows archive and updater contracts passed static and
fixture validation.

## Owner-native report

The owner requested textual validation without screenshots.

- The exact published Editor/runtime pair was launched in the persistent Linux
  development test environment.
- The owner created and entered a previously missing signed level without a
  save/reopen cycle and confirmed it was working as intended.
- The same validation sequence covered the newly exposed NPC respawn setting
  and placement-tool flyout behavior before the owner approved this release.
- The independently inspected archives contain that exact source pair and
  reviewed runtime inputs; no candidate archive is promoted to production.

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
