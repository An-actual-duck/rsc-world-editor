# World Builder 2 v0.7.0-alpha.50 validation — ACCEPTED

This record accepts one exact restricted pre-gate candidate as the validation
basis for World Builder 2 v0.7.0-alpha.50. It opens production packaging
through `release/world-builder-v2/RELEASE-READY`; it does not promote or reuse
the candidate archives. Production archives must be rebuilt from the later
clean, published gate commit.

## Acceptance

- Status: **ACCEPTED — RELEASE READY**
- Accepted on: **2026-08-31**
- Accepted by: **project owner**, with manager verification of the recorded
  automated evidence
- Restricted candidate World Editor commit:
  `56cee9d29b7b208b57a12be336d034ca0aafe5ce`
- Locked runtime-provider commit:
  `eac0e33bd5f09b6288be65a7665b6b282331560b`
- Version: `v0.7.0-alpha.50`
- Product identity: `rsc-world-editor-v2`
- World-source identity: `target-adaptive-v1`

This release repairs the end-to-end server map workflow. Import now installs
the bounded server and client runtime compatibility required by the selected
map while preserving an exact recognized target-specific v1 runtime. It
supports verified repeated imports, recognizes historical package addresses,
exports the lowest lossless target encoding, preserves selected-map identity
during reattachment, and selects a free isolated Builder port during project
creation.

The completed-import Undo action has been removed from the desktop, adaptive
CLI, Linux and Windows launchers, package inventory, updater requirements, and
end-user documentation. Automatic failed-import rollback, interrupted-import
Recovery, project backups, editor-session Undo/Redo, and Region Paste Undo are
unchanged. Upgrades remove only the formerly managed server-import Undo
launchers.

## Exact candidate artifacts

| Artifact | SHA-256 |
| --- | --- |
| `rsc-world-editor-v2-0.7.0-alpha.50-linux-x64.zip` | `23a04751c11603d1dcad8ea88e9b8633d3ee2f66f2370e2599c72abfe4d38969` |
| `rsc-world-editor-v2-0.7.0-alpha.50-windows-x64.zip` | `a467a178c194f92b1ca1f94de1d8de7ea8722734f42d0b7c6ff61048c8e3fdee` |
| `SHA256SUMS.txt` | `9033befac24c4e06fe10cd2b94bcace20258702b02ef8d9d85b26e936fb76c10` |
| `candidate-archive-inspection.json` | `366dccc0e0746fb0135bdf4022811c800f0f7a539f66acbd8d792f072595a7fc` |

Independent inspection reported `automated-archive-inspection-passed`.

| Platform | Inner manifest SHA-256 | Reviewed JRE inventory SHA-256 | Files |
| --- | --- | --- | ---: |
| Linux x64 | `42fc7bbbabd718b3b24e4ec6d18eae5dbf9e60496dca16a6a7c4f76e2ea1e436` | `56e02eae89660c0d7baef03b276f2c8f6ef1749d79403c074dc41ea8f3403c9e` | 420 |
| Windows x64 | `32b1b3e5169e813a70136d229ac7ec402140b16616a6ffe1b3c48d3a41f05ad2` | `9aaf15bca3b380b3b9099d3097182d85cbc83003d57d0844c8fc36f7c25b2967` | 478 |

The inspector SHA-256 was
`0651ba611e482e99a51d1962dd09fd918d2c04b49b254c327d9fdca9ca71fa5b`.
It verified clean published sources, the exact runtime lock, exhaustive
manifests, reviewed JRE bytes and modes, application allowlists, an empty
Builder seed, the production runtime marker, and absence of bundled world,
creator, credential, project, log, receipt, and backup data.

## Automated evidence

| Check | Result |
| --- | --- |
| Editor/runtime exact-lock parity | PASS |
| All 40 adaptive discovery tests | PASS |
| All 69 adaptive project-lifecycle tests | PASS |
| All 44 adaptive transaction tests | PASS |
| All 19 map migration-choice tests | PASS |
| Editor full exact-lock `./scripts/test.sh` (26 selections, 387 seconds) | PASS |
| `git diff --check` | PASS |
| Restricted candidate build from clean published `main` | PASS |
| Independent inspection of both external candidate archives | PASS |
| Linux and Windows package inventory excludes completed-import Undo | PASS |
| Updater removes formerly managed Undo launchers and preserves durable state | PASS |

The full matrix covered contracts, discovery, packed conversion, project
lifecycle, map migration, repeat import, automatic rollback, interrupted
recovery, packaging, release gates, archive safety, and updater rollback.
Native PowerShell execution was unavailable on this Linux host; equivalent
Windows archive and updater contracts passed static and fixture validation.

## Owner-native report

The owner requested textual validation without screenshots.

- The owner exercised server-map detection, project loading, map editing,
  saving, and repeated import against the disposable Core-Framework copy.
- The imported private server and client both launched successfully without
  additional compatibility work.
- The owner confirmed the map layers loaded correctly and accepted the
  completed import workflow after further testing.
- The owner formally marked this stage ready for release on 2026-08-31.
- No public server or real production world was changed during validation.

## Accepted limitations

- Native Windows application launch and native PowerShell updater execution
  were not performed on this Linux host. Windows archive, JRE, launcher,
  manifest, updater, rollback, and transaction validation passed.
- The opt-in automated real OpenGL client/server proof was unavailable during
  the headless full suite; the owner performed the relevant native Linux
  server/client and GUI validation directly.
- Import remains deliberately fail closed for unsupported layouts,
  changed-after-preview targets, ambiguous map choices, and targets that
  cannot be proved offline.
- A completed import has no World Builder Undo action. Administrators are
  responsible for making and verifying a complete server backup before import.

## Production rule

The candidate archives listed above are validation evidence only. They must
not be copied, renamed, uploaded, or promoted as production release files.
Production archives must be rebuilt after this record and `RELEASE-READY` are
committed and published on clean `main`, using the exact locked runtime and
reviewed JRE inputs.

## Post-publication gate state

Pending production rebuild, publication, download-back verification, and gate
consumption. The immutable release tag will retain this accepted record and
release gate.
