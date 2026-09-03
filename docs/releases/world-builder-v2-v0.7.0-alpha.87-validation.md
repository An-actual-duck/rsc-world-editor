# World Builder 2 v0.7.0-alpha.87 validation — ACCEPTED

This record accepts the exact restricted pre-gate candidate for World Builder
2 v0.7.0-alpha.87. It opens production packaging through
`release/world-builder-v2/RELEASE-READY`; candidate archives are validation
evidence and are not promoted as production artifacts.

## Acceptance

- Status: **ACCEPTED — RELEASE READY**
- Accepted on: **2026-09-02**
- Accepted by: **project owner**, after confirming live region paste works and
  directing Alpha.87 publication
- Restricted candidate World Editor commit:
  `73566c5429efeceac7ed005e8e6b10b883ec295b`
- Locked runtime-provider commit:
  `500780656a2c77d8e2f17c61e024d35e5f34ca2a`
- Version: `v0.7.0-alpha.87`
- Product identity: `rsc-world-editor-v2`
- World-source identity: `target-adaptive-v1`

Alpha.87 corrects live region paste activation after saved Builder edits have
legitimately expanded the working package with a new terrain sector or level.
The activation guard previously compared the published paste only with the
layout loaded at server startup and rejected the editor's own recorded saved
growth as an unexpected bounded-layout change.

The guard now accepts exactly the saved terrain-sector and level growth owned
by the active Builder session. Package identity, version, world spaces,
presentation layout, levels, sectors, and placement sets remain fail-closed;
unrecorded or unrelated layout expansion is still rejected. Failure messages
now identify the specific invariant that changed.

## Exact candidate artifacts

| Artifact | SHA-256 |
| --- | --- |
| `rsc-world-editor-v2-0.7.0-alpha.87-linux-x64.zip` | `8a087949339fc4c178d2e6deb5c53a403cf581116276b140fb7cbdcebd1b4f71` |
| `rsc-world-editor-v2-0.7.0-alpha.87-windows-x64.zip` | `2ada3468c0fca0d26a547ca2c21c759504df817d32fed8f23b3705c8ebbe552e` |
| `SHA256SUMS.txt` | `16c2788d78441a7605a6ac433df720b9ecba0a98d5bc8ec423debd961fc9a90c` |
| `candidate-archive-inspection.json` | `3e8113c91339379f147bfad6b3a3e308c0cec12f86330a431fb25481676cdac0` |

Independent inspection reported `automated-archive-inspection-passed`.

| Platform | Inner manifest SHA-256 | Reviewed JRE inventory SHA-256 | Files |
| --- | --- | --- | ---: |
| Linux x64 | `9ee1a0deecdd3af5d7bce5b1e354948e32ca2b100aa3b27f28994b0bc598850b` | `56e02eae89660c0d7baef03b276f2c8f6ef1749d79403c074dc41ea8f3403c9e` | 434 |
| Windows x64 | `9e818e69ef9329795c9aa44656c145874d15572f07b4f0c2f6ccaec81fd504fa` | `9aaf15bca3b380b3b9099d3097182d85cbc83003d57d0844c8fc36f7c25b2967` | 492 |

## Automated evidence

| Check | Result |
| --- | --- |
| Editor full `./scripts/test.sh` (26 selections, 426 seconds) | PASS |
| Focused candidate suite (15 selections, 391 seconds) | PASS |
| Locked runtime-provider full `./scripts/test.sh` | PASS |
| Runtime server compilation | PASS |
| Adaptive runtime tests (17 cases) | PASS |
| All 23 adaptive contract tests | PASS |
| All 41 adaptive discovery tests | PASS |
| All 74 adaptive project-lifecycle tests | PASS |
| All 51 adaptive transaction tests | PASS |
| Region copy/paste runtime regression | PASS |
| `git diff --check` | PASS |
| Restricted candidate build from clean published `main` | PASS |
| Independent inspection of both external candidate archives | PASS |

## Owner acceptance and limitations

- The owner reproduced the prior live activation failure, tested Alpha.87,
  confirmed that pasting works, and explicitly directed publication.
- The previously accepted detect, edit, save, repeated import, private-server,
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
`1c829fc9db535e2af3f7ae9e3d45742579eefac1` under tag
`rsc-world-editor-v2-0.7.0-alpha.87`. The full Editor release suite passed all
26 selections in 421 seconds from that exact commit, and the complete locked
runtime-provider suite passed. Independent inspection of the fresh production
archives reported `automated-archive-inspection-passed`; its external evidence
document has SHA-256
`c78a2f836d5f4fe3b5808b5c2e3b3093b502e978a38373f11046e4accd4b2d68`.

| Production artifact | SHA-256 |
| --- | --- |
| `rsc-world-editor-v2-0.7.0-alpha.87-linux-x64.zip` | `9dc049ce73ed7154560212cdd2957f0cffe47e0270ab14422a2c209365cf8243` |
| `rsc-world-editor-v2-0.7.0-alpha.87-windows-x64.zip` | `d89d1ec4a6d512704dad3ad8e83f530d1276947e539913a29b2b372335d257d7` |
| `SHA256SUMS.txt` | `3f1ba681251e7f3ca4fdb759c450791a6f77a81f211074038a360a3e3661db7c` |

All three assets were downloaded back from GitHub, verified against the
uploaded `SHA256SUMS.txt`, and compared byte-for-byte with the independently
inspected pre-upload files. The public release is
<https://github.com/An-actual-duck/rsc-world-editor/releases/tag/rsc-world-editor-v2-0.7.0-alpha.87>.
Development `main` consumes/removes the gate after publication; a later
release requires a new exact candidate, owner acceptance, validation record,
and gate commit.
