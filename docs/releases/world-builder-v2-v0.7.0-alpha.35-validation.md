# World Builder 2 v0.7.0-alpha.35 validation — ACCEPTED

This record accepts one exact restricted pre-gate candidate as the validation
basis for World Builder 2 v0.7.0-alpha.35. It opens production packaging
through `release/world-builder-v2/RELEASE-READY`; it does not promote or reuse
the candidate archives. Production archives must be rebuilt from the later
clean, published gate commit.

## Acceptance

- Status: **ACCEPTED — RELEASE READY**
- Accepted on: **2026-08-29**
- Accepted by: **project owner**, with manager verification of the recorded
  automated evidence
- Restricted candidate World Editor commit:
  `57441fb1de926b952f3da4be292a8cfcadef9709`
- Locked runtime-provider commit:
  `266c6619d0d85eb7aa1f69c8f96e64c29ff50182`
- Version: `v0.7.0-alpha.35`
- Product identity: `rsc-world-editor-v2`
- World-source identity: `target-adaptive-v1`

This update restores session undo and adds definition search for wall and floor
terrain choices. It also composes effective target NPC and item definitions
from the server's active base, patch, and MyWorld configuration instead of
letting filename order choose colliding IDs. Composition inputs, activation,
and the selected winner are inventoried and reported deterministically.

The final correction keeps the launcher's ordinary `packed-map-N` choice
separate from the capability descriptor's configuration identity. When a
descriptor declares one active role, the selected packed map now binds that
role while retaining the exact ordinary server configuration for content
discovery, immutable project copying, and drift-safe rediscovery. Multiple
genuinely distinct map choices remain explicit and fail closed when ambiguous.

## Exact candidate artifacts

| Artifact | SHA-256 |
| --- | --- |
| `rsc-world-editor-v2-0.7.0-alpha.35-linux-x64.zip` | `ef26230099f0d0cea7c1c1391053b3239a6bd50f232f85bdef41aa43986eceab` |
| `rsc-world-editor-v2-0.7.0-alpha.35-windows-x64.zip` | `f7748821df1465b5c26fe96a685664f90d548670367ae715c0ba6b86361a75e0` |
| `SHA256SUMS.txt` | `f22791bab71bed4a94b75811377fbc39ed4eba93d77e7126206bf9aacbaa042f` |
| `candidate-archive-inspection.json` | `3e6c82a1e8c00ca7517b3e9498636db652fa593540790cb3245d9a020168f7a5` |

Independent inspection reported `automated-archive-inspection-passed`.

| Platform | Inner manifest SHA-256 | Reviewed JRE inventory SHA-256 | Files |
| --- | --- | --- | ---: |
| Linux x64 | `7d24f85a4f78eea11bdb584be443d7b98e50d4427c8cd1befbe97873ab19b992` | `56e02eae89660c0d7baef03b276f2c8f6ef1749d79403c074dc41ea8f3403c9e` | 421 |
| Windows x64 | `e2cdea77e49b121838e35cb72d88264cea49e5f35b6bd1853c5ea578872003d9` | `9aaf15bca3b380b3b9099d3097182d85cbc83003d57d0844c8fc36f7c25b2967` | 479 |

The inspector SHA-256 was
`0e067b21b4729070f9fbc55c3693cc7e06c534616953b8ddb25e5f09678ffb55`.
It verified clean published sources, the exact runtime lock, exhaustive
manifests, reviewed JRE bytes and modes, application allowlists, an empty
Builder seed, the production runtime marker, and absence of bundled world,
creator, credential, project, log, receipt, and backup data.

## Automated evidence

| Check | Result |
| --- | --- |
| Editor/runtime exact-lock parity | PASS |
| Descriptor packed-map choice and rediscovery regressions | PASS |
| Effective definition-composition regressions | PASS |
| All 40 adaptive discovery tests | PASS |
| All 68 adaptive project-lifecycle tests | PASS |
| Editor full exact-lock `./scripts/test.sh` | PASS |
| `git diff --check` | PASS |
| Restricted candidate build from clean published `main` | PASS |
| Independent inspection of both external candidate archives | PASS |

The full matrix covered contracts, discovery, packed conversion, layered
composition, project lifecycle, Region Copier, import/undo/recovery,
packaging, release gates, archive safety, and updater rollback. Native
PowerShell execution was unavailable on this Linux host; equivalent Windows
archive and updater contracts passed static and fixture validation.

## Owner-native report

The owner requested textual validation without screenshots.

- The Linux alpha.35 candidate was extracted as a fresh test build and launched
  against the disposable server-copy workflow.
- The previously blocking `packed-map-1` versus descriptor-role error was no
  longer produced, and the selected server map workflow operated correctly.
- The owner confirmed the corrected candidate working and requested this
  release update.
- No public server or real production world was changed during validation.

## Accepted limitations

- Native Windows application launch and native PowerShell updater execution
  were not performed on this Linux host. Windows archive, JRE, launcher,
  manifest, updater, rollback, and transaction validation passed.
- The opt-in automated real OpenGL client/server proof was unavailable during
  the headless full suite; the owner performed the relevant native Linux GUI
  validation directly.
- Migration and import remain deliberately fail closed for ambiguous map
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

## Post-publication gate state

The production release was published from gate commit
`76588c298984dfa359ffed50cb8a6382e0c3cfda` as tag
`rsc-world-editor-v2-0.7.0-alpha.35`. That immutable tag retains this accepted
record and the release gate. The production assets were downloaded back from
GitHub, verified against the uploaded `SHA256SUMS.txt`, and compared
byte-for-byte with the independently inspected pre-upload files.

| Production artifact | SHA-256 |
| --- | --- |
| `rsc-world-editor-v2-0.7.0-alpha.35-linux-x64.zip` | `9ec153f952e6e3a3827c6899f2793d9501ed074174cb5bfa6e551759b9215db5` |
| `rsc-world-editor-v2-0.7.0-alpha.35-windows-x64.zip` | `f1517e5c7fbcef64f7e543240b98b25dac987eafa4985989c4615c3fc3236286` |
| `SHA256SUMS.txt` | `682487e341c2701a3eefa7bcb2cc8c4c9d22bf9cbfb4c5ba05a877b660f96f4b` |
| Pre-upload production inspection | `0d0eedcece50a8b3f3a921a6eee875a26742209a22f760d7c6634db971e8dd8b` |

The public release is
<https://github.com/An-actual-duck/rsc-world-editor/releases/tag/rsc-world-editor-v2-0.7.0-alpha.35>.
Development `main` consumes/removes the gate after publication. Any later
release therefore requires a new exact candidate, owner decision, validation
record, and gate commit.
