# World Builder 2 v0.3.0-alpha.5 validation — ACCEPTED

This record accepts one exact restricted pre-gate candidate as the validation
basis for the World Builder 2 v0.3.0-alpha.5 Linux first-run hotfix. It opens
production packaging through `release/world-builder-v2/RELEASE-READY`; it does
not promote or reuse the candidate archives. Production archives must be
rebuilt from the later clean, published gate commit.

## Acceptance

- Status: **ACCEPTED — RELEASE READY**
- Accepted on: **2026-08-22**
- Accepted by: **project owner**, with manager verification of the recorded
  automated evidence
- Restricted candidate World Editor commit:
  `d44d95f2316bcc1dd251c0eb058800f5106359b4`
- Locked runtime-provider commit:
  `ae1c8bd8c0dee161f8fc09ed0f2887848e9da38b`
- Version: `v0.3.0-alpha.5`
- Product identity: `rsc-world-editor-v2`
- World-source identity: `target-adaptive-v1`

The accepted hotfix corrects Linux desktop/file-manager launches that have no
usable standard input. The launcher now reopens itself in a visible supported
terminal for first-run discovery and exact confirmation. Interactive terminal
launches retain their previous behavior, and an explicit noninteractive mode
keeps automated updater and packaging checks deterministic.

## Exact candidate artifacts

| Artifact | SHA-256 |
| --- | --- |
| `rsc-world-editor-v2-0.3.0-alpha.5-linux-x64.zip` | `0c51944f9812701983c398d75df721b37accb5e8efef2d7b6d70875b267b01ff` |
| `rsc-world-editor-v2-0.3.0-alpha.5-windows-x64.zip` | `60ba9614af8fcbb6e4884ba7868790c0e4f5587413e07ee13f9375981a94964f` |
| `SHA256SUMS.txt` | `faf3173c4063c5324cc832e569197d8218e21265a84d1370d6d788223e21e3d4` |
| `candidate-archive-inspection.json` | `984b5363dbe9964f4ed8e286ebe9c54a32792dd10ec2a80f1e5d2ba2b1817fdc` |

Independent inspection reported `automated-archive-inspection-passed`.

| Platform | Inner manifest SHA-256 | Reviewed JRE inventory SHA-256 | Files |
| --- | --- | --- | ---: |
| Linux x64 | `bdb328f0f02bd8f52c777a598229abf53ad9301c70ab2489bc1b22c227ef57c1` | `56e02eae89660c0d7baef03b276f2c8f6ef1749d79403c074dc41ea8f3403c9e` | 406 |
| Windows x64 | `7b63fa7323d67b154b03b920ee0e749987e8559eca78ec557ab675795018242d` | `9aaf15bca3b380b3b9099d3097182d85cbc83003d57d0844c8fc36f7c25b2967` | 464 |

The inspector SHA-256 was
`6d79a1ca4cc62b3ac29563dcde8e6dd7c150764a20afd0b4266cb51855266a30`.
It verified clean published sources, the exact runtime lock, exhaustive
manifests, reviewed JRE bytes and modes, application allowlists, an empty
Builder seed, the production runtime marker, and absence of bundled world,
creator, credential, project, log, receipt, and backup data.

## Automated evidence

| Check | Result | Evidence SHA-256 |
| --- | --- | --- |
| `git diff --check` | PASS | `e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855` |
| `./scripts/test-world-builder-v2-candidate.sh` | PASS | `e9bfda32f8a2ac846067a96fe69a0af8603ad7ad1c718342d1f712f737276f78` |
| `RUNTIME_PROVIDER_DIR=.runtime-provider ./scripts/test.sh` | PASS | `70cb6df7382d6c9f024ab945e0f59cb53ea9d8f349e366629f66895575261f28` |

Five native PowerShell updater cases were skipped because `pwsh` was
unavailable. Their static and cross-platform transaction coverage passed. The
runtime provider is unchanged from v0.3.0-alpha.4.

## Owner-native report

The owner requested textual validation without screenshots.

- The exact inspected Linux candidate was extracted into a fresh temporary
  installation and launched with no terminal input, reproducing the desktop
  launch conditions that silently exited in v0.3.0-alpha.4.
- A visible terminal opened and retained the first-run discovery and `CREATE`
  confirmation workflow.
- After confirmation, the client opened successfully and the owner reported
  that it looked good. The launcher and client then exited cleanly.

The accepted v0.3.0-alpha.4 terrain, placement, elevation, foreground picking,
contextual-toolbar, and adaptive-workflow evidence remains the unchanged
functional baseline. This hotfix does not change the runtime provider, map
formats, project data, import/export behavior, or Windows launcher.

## Accepted limitations

- Native Windows application launch and native PowerShell updater execution
  were not performed for this Linux-only launcher hotfix. Windows archive,
  Java, launcher, and static transaction validation passed.
- Linux desktop launch requires one supported terminal application. If none is
  available, the launcher emits a precise error and users may start it from an
  existing terminal.

## Production rule

The candidate archives listed above are validation evidence only. They must
not be copied, renamed, uploaded, or promoted as production release files.
Production archives must be rebuilt after this record and `RELEASE-READY` are
committed and published on clean `main`, using the exact locked runtime and
reviewed JRE inputs.

## Post-publication gate state

The production release was published from gate commit
`463307638bbab03aa5ce735bb1ce3c1756cf2cdf` as tag
`rsc-world-editor-v2-0.3.0-alpha.5`. That immutable tag retains this accepted
record and the release gate. The production assets were downloaded back from
GitHub and verified against the uploaded `SHA256SUMS.txt`:

| Production artifact | SHA-256 |
| --- | --- |
| `rsc-world-editor-v2-0.3.0-alpha.5-linux-x64.zip` | `c3f6647f123c9e8b6b517f9d77dbdd944c20ef1b50dabc9f72b301079bf30fdd` |
| `rsc-world-editor-v2-0.3.0-alpha.5-windows-x64.zip` | `a27cf336bb94e3066966a8de11a67fb6509b5a3b94ebe101ae39fa0274876434` |
| `SHA256SUMS.txt` | `7e7bdb5dbbdd6ee2c5ec44140601d558dd123143481944c758a8da40f338f01a` |
| External production inspection | `48fbbadab2a62987cf925869e72e6067bd1703c582873f70700b28e6cdcb9e3b` |

Development `main` consumes/removes the gate after publication. Any later
release therefore requires a new exact candidate, owner decision, validation
record, and gate commit.
