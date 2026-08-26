# World Builder 2 v0.5.0-alpha.11 validation — ACCEPTED

This record accepts one exact restricted pre-gate candidate as the validation
basis for World Builder 2 v0.5.0-alpha.11. It opens production packaging
through `release/world-builder-v2/RELEASE-READY`; it does not promote or reuse
the candidate archives. Production archives must be rebuilt from the later
clean, published gate commit.

## Acceptance

- Status: **ACCEPTED — RELEASE READY**
- Accepted on: **2026-08-25**
- Accepted by: **project owner**, with manager verification of the recorded
  automated evidence
- Restricted candidate World Editor commit:
  `eef508cffe453fbe506f3079188f9f473ce597d8`
- Locked runtime-provider commit:
  `e001c11d8da3c67d7fc2fe1df70b2101de783ae4`
- Version: `v0.5.0-alpha.11`
- Product identity: `rsc-world-editor-v2`
- World-source identity: `target-adaptive-v1`

This release advances the format-aware, provider-independent server discovery
workflow; resilient custom item, NPC, and scenery consumption; wide terrain
elevation; contextual Editor controls; project-local region copy/cut/paste and
portable snapshots; and the simplified desktop launcher. The final correction
retains trusted packaged-client scenery identities while loading a project and
resolves an absent historical in-range server model alias only when the real
project model archive proves the requested entry absent and the packaged model
present. Genuine custom models continue to win, and new scenery identities
remain fail-closed without exact model evidence.

## Exact candidate artifacts

| Artifact | SHA-256 |
| --- | --- |
| `rsc-world-editor-v2-0.5.0-alpha.11-linux-x64.zip` | `588c3d89037a36f411666dcc244f3f3c084d756f36418290b12b89f683314912` |
| `rsc-world-editor-v2-0.5.0-alpha.11-windows-x64.zip` | `9e1c84dca1071d52c85111d5010e1c6ee1828eb6e10e8311f6eea5c4e086797f` |
| `SHA256SUMS.txt` | `263e3307edc611b6eff895eaee2654d4ad07c5a2089035c3eac26bee56c65fd8` |
| `candidate-archive-inspection.json` | `f15f2f9569b8a629efc353f0f9e90b6cc6239a0d745edd27e1612056b79dde6b` |

Independent inspection reported `automated-archive-inspection-passed`.

| Platform | Inner manifest SHA-256 | Reviewed JRE inventory SHA-256 | Files |
| --- | --- | --- | ---: |
| Linux x64 | `983eb3c6598b0d148509ae8daab65d925f685b28da8544184116a71fd3ea7ce2` | `56e02eae89660c0d7baef03b276f2c8f6ef1749d79403c074dc41ea8f3403c9e` | 416 |
| Windows x64 | `7d21e383885d57d16d7c10322c736160da0061977a0285c7e526c6c553b61c6d` | `9aaf15bca3b380b3b9099d3097182d85cbc83003d57d0844c8fc36f7c25b2967` | 474 |

The inspector SHA-256 was
`0e067b21b4729070f9fbc55c3693cc7e06c534616953b8ddb25e5f09678ffb55`.
It verified clean published sources, the exact runtime lock, exhaustive
manifests, reviewed JRE bytes and modes, application allowlists, an empty
Builder seed, the production runtime marker, and absence of bundled world,
creator, credential, project, log, receipt, and backup data.

## Automated evidence

| Check | Result |
| --- | --- |
| Runtime `./scripts/test.sh` | PASS |
| Runtime real built client/server login, author, save, and reopen | PASS |
| Editor runtime parity check | PASS |
| Editor `RUNTIME_PROVIDER_DIR=.runtime-provider ./scripts/test.sh` | PASS |
| `git diff --check` | PASS |
| Restricted candidate build from clean published `main` | PASS |
| Independent inspection of both external candidate archives | PASS |

The complete matrices covered discovery and format profiles, declarative
custom-content reconciliation, provider and placeholder boundaries, packed
conversion, layered adoption, standalone creation, all placement families,
wide elevation, project save/reopen, region snapshots, export/import/undo and
crash recovery, updater rollback, packaging, release gates, archive safety,
and strict separation from unrelated game-server repositories.

## Owner-native report

The owner requested textual validation without screenshots.

- The exact independently inspected Linux candidate was extracted into a fresh
  temporary installation and launched visibly.
- The owner exercised the automatic server-map workflow against the intended
  target project and confirmed that it launched successfully.
- The final native check confirmed that the runecrafting altar glyph and its
  physical altar model both render at the converted placement.
- The owner stated that the result works as desired and explicitly authorized
  the official release.

No screenshot was captured or judged by an AI session. Discovery and project
creation did not export changes back into the selected server.

## Accepted limitations

- Native Windows application launch and native PowerShell updater execution
  were not performed on this Linux host. Windows archive, JRE, launcher,
  manifest, updater, rollback, and cross-platform transaction validation
  passed.
- Recognized declarative layouts are automatic. Unfamiliar or ambiguous custom
  content formats can still require guided selection or explicit evidence;
  target code is never executed to guess definitions.
- Missing or unsafe optional custom visuals use deterministic placeholders and
  project-local warnings. Beyond-packaged scenery models remain mandatory and
  are never replaced with an inferred model.
- Terrain colour authoring retains the established palette; true RGB terrain
  materials are outside this release.

## Production rule

The candidate archives listed above are validation evidence only. They must
not be copied, renamed, uploaded, or promoted as production release files.
Production archives must be rebuilt after this record and `RELEASE-READY` are
committed and published on clean `main`, using the exact locked runtime and
reviewed JRE inputs.

## Post-publication gate state

Pending production publication. The immutable release tag will retain this
record and gate; development `main` will consume/remove the gate afterward so
no later commit or version can reuse this acceptance.
