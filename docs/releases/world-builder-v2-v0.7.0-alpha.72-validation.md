# World Builder 2 v0.7.0-alpha.72 validation — ACCEPTED

This record accepts one exact restricted pre-gate candidate as the validation
basis for World Builder 2 v0.7.0-alpha.72. It opens production packaging
through `release/world-builder-v2/RELEASE-READY`; it does not promote or reuse
the candidate archives. Production archives must be rebuilt from the later
clean, published gate commit.

## Acceptance

- Status: **ACCEPTED — RELEASE READY**
- Accepted on: **2026-09-01**
- Accepted by: **project owner**, with manager verification of the recorded
  automated evidence
- Restricted candidate World Editor commit:
  `147fdc5b34e2f23f441ce4ccdf60cf908ce85aad`
- Locked runtime-provider commit:
  `d2903f21530959a3bd9072846c8611fdf035f792`
- Version: `v0.7.0-alpha.72`
- Product identity: `rsc-world-editor-v2`
- World-source identity: `target-adaptive-v1`

This release completes the upgrade-first native layered-map path. Detection
adopts supported server maps into isolated projects without requiring the
target server to stop. Import upgrades the offline target server and client to
the pinned World Builder runtime contract, retires the legacy
`Custom_Landscape.orsc` dependency from the installed native path, supports
blocking blended base-color terrain and unsigned 16-bit elevation, and remains
repeatable across later edits and exports.

The final importer correction recognizes an exact active content-addressed map
package when a freshly detected project still needs a newer runtime. It updates
only the verified stale runtime/client sources. A changed or mismatched active
package remains fail-closed with `TARGET_DRIFT`; existing package directories
are never overwritten.

## Exact candidate artifacts

| Artifact | SHA-256 |
| --- | --- |
| `rsc-world-editor-v2-0.7.0-alpha.72-linux-x64.zip` | `e04193cee477f3eceb4e5f8b8cfea57f7f13ea10591c1750e8da85ae51eca871` |
| `rsc-world-editor-v2-0.7.0-alpha.72-windows-x64.zip` | `9e99bc87ce6f38798907bb16f382122485d33e835a11436ad2ae5589dd536d8c` |
| `SHA256SUMS.txt` | `469d9745616fba8c8f4778f840a77a33430636a47a67d636e0ad5f29a953a9aa` |
| `candidate-archive-inspection.json` | `41e4258cc0e67574f5858e9148273f903df62290732eeb9f7e529a957aba7513` |

Independent inspection reported `automated-archive-inspection-passed`.

| Platform | Inner manifest SHA-256 | Reviewed JRE inventory SHA-256 | Files |
| --- | --- | --- | ---: |
| Linux x64 | `a82251279abe9ae536ee4a6e39aada0647960838c9c12aa5b3bebfef2b84f659` | `56e02eae89660c0d7baef03b276f2c8f6ef1749d79403c074dc41ea8f3403c9e` | 434 |
| Windows x64 | `8daf0716de1402f94439074025d7f7de5003ed5d26eca04833ff800961da40c7` | `9aaf15bca3b380b3b9099d3097182d85cbc83003d57d0844c8fc36f7c25b2967` | 492 |

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
| All 70 adaptive project-lifecycle tests | PASS |
| All 50 adaptive transaction tests | PASS |
| All 19 map migration-choice tests | PASS |
| Editor full exact-lock `./scripts/test.sh` (26 selections, 424 seconds) | PASS |
| `git diff --check` | PASS |
| Restricted candidate build from clean published `main` | PASS |
| Independent inspection of both external candidate archives | PASS |
| Exact existing-package runtime completion regression | PASS |
| Changed active-package fail-closed regression | PASS |

The full matrix covered contracts, discovery, project lifecycle, packed
conversion, import, runtime completion, repeat import, automatic rollback,
interrupted recovery, packaging, release gates, archive safety, launch
supervision, wide elevation, and updater rollback. Native PowerShell execution
was unavailable on this Linux host; equivalent Windows archive and updater
contracts passed static and fixture validation.

## Owner-native report

The owner requested textual validation without screenshots.

- The owner detected the disposable server copy, loaded and edited its map,
  saved, exported, and imported it through the normal desktop workflow.
- The upgraded private server and client launched successfully, including the
  layered content and later unsigned-elevation corrections.
- The owner tested the smaller editor functions and reported that they worked.
- The owner then deleted the entire disposable copy, created a fresh copy, and
  repeated the complete process successfully without additional work.
- The owner formally gave this exact candidate the green light on 2026-09-01.
- No public server or production world was changed during validation.

## Accepted limitations

- Native Windows application launch and native PowerShell updater execution
  were not performed on this Linux host. Windows archive, JRE, launcher,
  manifest, updater, rollback, and transaction validation passed.
- The opt-in automated real OpenGL client/server proof was unavailable during
  the headless full suite; the owner performed the relevant native Linux
  server/client and GUI validation directly.
- Import remains deliberately fail closed for unsupported layouts,
  changed-after-preview targets, ambiguous map choices, mismatched installed
  packages, and targets that cannot be proved offline.
- A completed import has no World Builder Undo action. Administrators are
  responsible for making and verifying a complete server backup before import.

## Production rule

The candidate archives listed above are validation evidence only. They must
not be copied, renamed, uploaded, or promoted as production release files.
Production archives must be rebuilt after this record and `RELEASE-READY` are
committed and published on clean `main`, using the exact locked runtime and
reviewed JRE inputs.

## Post-publication gate state

The production release was published from gate commit
`ee8ff26be9714536c606a8dd7fba4e05fe54b91a` as tag
`rsc-world-editor-v2-0.7.0-alpha.72`. That immutable tag retains this accepted
record and the release gate. The production assets were downloaded back from
GitHub, verified against the uploaded `SHA256SUMS.txt`, and compared
byte-for-byte with the independently inspected pre-upload files.

| Production artifact | SHA-256 |
| --- | --- |
| `rsc-world-editor-v2-0.7.0-alpha.72-linux-x64.zip` | `242bf51956332e3e56edff0de6a6888b72310c6246f99a3a8e3116764b45d45c` |
| `rsc-world-editor-v2-0.7.0-alpha.72-windows-x64.zip` | `786d964bae1b32aab0050d21ebb8bc6308ba0074801f5e606d0aa2cbe5706f87` |
| `SHA256SUMS.txt` | `37594204a6bfe3c0ea4f95fd29e5254213b3ab88666d795175a2b2918ff52946` |
| Pre-upload production inspection | `9b4259b32a517eaa2a8c92cac1444370787b44030b3ccc98595b6671e6c7684c` |

The public release is
<https://github.com/An-actual-duck/rsc-world-editor/releases/tag/rsc-world-editor-v2-0.7.0-alpha.72>.
Development `main` consumes/removes the gate after publication. Any later
release therefore requires a new exact candidate, owner decision, validation
record, and gate commit.
