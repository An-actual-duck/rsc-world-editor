# World Builder 2 v0.4.0-alpha.1 validation — ACCEPTED

This record accepts one exact restricted pre-gate candidate as the validation
basis for World Builder 2 v0.4.0-alpha.1. It opens production packaging through
`release/world-builder-v2/RELEASE-READY`; it does not promote or reuse the
candidate archives. Production archives must be rebuilt from the later clean,
published gate commit.

## Acceptance

- Status: **ACCEPTED — RELEASE READY**
- Accepted on: **2026-08-24**
- Accepted by: **project owner**, with manager verification of the recorded
  automated evidence
- Restricted candidate World Editor commit:
  `f125fe34983a8e956f7c44cf15a69983013d2d10`
- Locked runtime-provider commit:
  `3953807716e846feb8383f9843aac91e57ee8df5`
- Version: `v0.4.0-alpha.1`
- Product identity: `rsc-world-editor-v2`
- World-source identity: `target-adaptive-v1`

This feature release adds the persistent desktop project flow, complete-folder
portable provider onboarding, resilient project-local custom item and NPC
content, full Builder client presentation, remembered position after the first
120,648 spawn, portable region copy/cut/paste primitives, unsigned 16-bit
terrain elevation, foreground-aware elevated-terrain picking, and the
contextual editor toolbar.

## Exact candidate artifacts

Only the following second, independently inspected candidate is accepted. An
earlier build whose inspection used the nested disposable runtime checkout was
rejected and is not reusable.

| Artifact | SHA-256 |
| --- | --- |
| `rsc-world-editor-v2-0.4.0-alpha.1-linux-x64.zip` | `a1ef50ec227bbb48f9f82b25c2bf2576f0e1f38edbe3c81866a5706109a42451` |
| `rsc-world-editor-v2-0.4.0-alpha.1-windows-x64.zip` | `02ebc2d4322e44ff28927e4c3109dced8587a305e99f3b8799ef695112d7a3e8` |
| `SHA256SUMS.txt` | `8ccb6562dcadb5f5ff25d6ec86efad0c770e89ec27df10fb0c8a2e01272902ac` |
| `candidate-archive-inspection.json` | `b4c924e9179d70da1c3329a8792e449fb9067ea4e1b750f0222d83eeeb021411` |

Independent inspection reported `automated-archive-inspection-passed`.

| Platform | Inner manifest SHA-256 | Reviewed JRE inventory SHA-256 | Files |
| --- | --- | --- | ---: |
| Linux x64 | `740abead1fc88b6c6b8a56e5b5a14e8f767aa00fb153bbda4d9a7c95107fd5e0` | `56e02eae89660c0d7baef03b276f2c8f6ef1749d79403c074dc41ea8f3403c9e` | 410 |
| Windows x64 | `0a0357deabd704adcee1a7896be587e52f2a7f4b82a23e7d2f607bde26336945` | `9aaf15bca3b380b3b9099d3097182d85cbc83003d57d0844c8fc36f7c25b2967` | 468 |

The inspector SHA-256 was
`2378454606567710ee8e82c99b3bee2d97209b6d111d1a4bca5aecb89e102870`.
It verified clean published sources, the exact runtime lock, exhaustive
manifests, reviewed JRE bytes and modes, application allowlists, an empty
Builder seed, the production runtime marker, and absence of bundled world,
creator, credential, project, log, receipt, and backup data.

## Automated evidence

| Check | Result |
| --- | --- |
| `git diff --check` | PASS |
| `WORLD_BUILDER_NO_TERMINAL=1 RUNTIME_PROVIDER_DIR=.runtime-provider ./scripts/test.sh` | PASS |
| `./scripts/test-world-builder-v2-candidate.sh` | PASS |
| Independent inspection of both external candidate archives | PASS |

The complete suite covered provider discovery and fallback, packed conversion,
layered adoption, standalone creation, copy/cut/paste, wide elevation,
placement families, project save/reopen, export/import/undo/recovery,
transactions, updater rollback, packaging, release gates, and product
independence. Five native PowerShell updater cases were skipped because a
reviewed `pwsh` executable was unavailable; their static and cross-platform
transaction coverage passed.

## Owner-native report

The owner requested textual validation without screenshots.

- The exact inspected Linux candidate was extracted into a fresh temporary
  installation and launched visibly.
- The owner selected a server source and used **Choose complete provider
  package…** on a complete data-only `world-builder-provider` folder rather
  than browsing for an internal JSON file.
- The target map and custom content loaded into the isolated World Builder
  project.
- The owner confirmed the client presentation, first 120,648 spawn, editing,
  save, close, and reopen behavior were working as requested.
- The candidate launcher and client exited cleanly after validation.

No screenshot was captured or judged by an AI session. Source discovery and
project creation did not export changes back into the selected server.

## Accepted limitations

- Native Windows application launch and native PowerShell updater execution
  were not performed on this Linux host. Windows archive, Java, launcher,
  application allowlist, updater, rollback, and cross-platform transaction
  validation passed.
- Optional custom visuals that are missing, unfamiliar, malformed, or unsafe
  intentionally use deterministic placeholders with project-local actionable
  warnings; they do not prevent project launch or discard item identity.

## Production rule

The candidate archives listed above are validation evidence only. They must not
be copied, renamed, uploaded, or promoted as production release files.
Production archives must be rebuilt after this record and `RELEASE-READY` are
committed and published on clean `main`, using the exact locked runtime and
reviewed JRE inputs.
