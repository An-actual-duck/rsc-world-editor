# World Builder 2 v0.3.0-alpha.4 validation — ACCEPTED

This record accepts one exact restricted pre-gate candidate as the validation
basis for World Builder 2 v0.3.0-alpha.4. It opens production packaging through
`release/world-builder-v2/RELEASE-READY`; it does not promote or reuse the
candidate archives. Production archives must be rebuilt from the later clean,
published gate commit and receive their own hashes.

## Acceptance

- Status: **ACCEPTED — RELEASE READY**
- Accepted on: **2026-08-22**
- Accepted by: **project owner**, with manager verification of the recorded
  automated evidence
- Restricted candidate World Editor commit:
  `cda8b3f79cca72848055c93e0a525468e3727263`
- Locked runtime-provider commit:
  `ae1c8bd8c0dee161f8fc09ed0f2887848e9da38b`
- Version: `v0.3.0-alpha.4`
- Product identity: `rsc-world-editor-v2`
- World-source identity: `target-adaptive-v1`
- Owner decision: the candidate behavior is accepted and an official release
  update is requested.

The accepted update adds the Editor-owned portable region snapshot foundation,
unsigned 16-bit terrain elevation, foreground-aware elevated-terrain picking,
and the contextual two-column in-game toolbar. It retains the independent,
content-neutral adaptive baseline accepted for v0.2.0-alpha.1.

## Exact candidate artifacts

| Artifact | SHA-256 |
| --- | --- |
| `rsc-world-editor-v2-0.3.0-alpha.4-linux-x64.zip` | `6bf04cb74a6e6662b4dca77c00c5a51da14be36a2c0e3a4663eeb2c2d6035d47` |
| `rsc-world-editor-v2-0.3.0-alpha.4-windows-x64.zip` | `b11df15a9c2aea1c6fd654fc76c0dcbd626971f6a4e24139933d0b2f68f10d7f` |
| `SHA256SUMS.txt` | `b7cf7c7a225fb30e49b05b3fef135127047c0ee7558636022a317feaa4db859d` |
| `candidate-archive-inspection.json` | `4a5eb82203196d22a45831cd5f2403af4410f4303e0d7c2d8fa8d6c8bee7f5e0` |

Independent inspection reported `automated-archive-inspection-passed`.

| Platform | Inner manifest SHA-256 | Reviewed JRE inventory SHA-256 | Files |
| --- | --- | --- | ---: |
| Linux x64 | `d56801e55b1531f2a782596d76e4764c3c48eb1704f37c1cbd0138226bdea906` | `56e02eae89660c0d7baef03b276f2c8f6ef1749d79403c074dc41ea8f3403c9e` | 406 |
| Windows x64 | `58014da27f20c9d7bea424abefeac35fa8c81741d7711dad60c23903931a4dae` | `9aaf15bca3b380b3b9099d3097182d85cbc83003d57d0844c8fc36f7c25b2967` | 464 |

The inspector SHA-256 was
`6d79a1ca4cc62b3ac29563dcde8e6dd7c150764a20afd0b4266cb51855266a30`.
It verified exact clean published sources, the exact runtime lock, one safe
archive root, exhaustive manifests, reviewed JRE bytes and modes, application
allowlists, an empty Builder seed, the production runtime marker, and absence
of bundled world, creator, credential, project, log, receipt, and backup data.

## Automated evidence

| Check | Result | Evidence SHA-256 |
| --- | --- | --- |
| `git diff --check` | PASS | `e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855` |
| `./scripts/test-world-builder-v2-candidate.sh` | PASS | `ce1653d38ddf32c0e7c368fefe1212bb97cea429482bc5044026a1a5407bcb52` |
| `RUNTIME_PROVIDER_DIR=.runtime-provider ./scripts/test.sh` | PASS | `5548891717c77d9c9def3789ceb9f2507b94a0709d7aa3daf86d1b49e5420bbe` |

The exact runtime provider also passed its complete suite, client/server builds,
archive audit, compact-toolbar contract, terrain inspection/save/stroke tests,
foreground picking test, and real authenticated packaged Builder lifecycles.
Five native PowerShell updater cases were skipped because `pwsh` was
unavailable; their static and cross-platform transaction coverage passed.

## Owner-native report

The owner requested textual validation without screenshots.

- Unsigned wide elevation was exercised on a two-by-two-chunk test area with a
  center plateau at elevation 300 and surrounding elevation 0.
- Scenery placement above elevation 255, movement targeting, and foreground
  elevated-tile priority were corrected and then reported as working as
  intended.
- The Linux v0.3.0-alpha.4 candidate was launched from a fresh standalone
  project. The owner accepted the taller two-column toolbar, the left-side
  Scenery/NPC/Items/Brush organization, contextual right-side actions, and the
  reduced entity flyouts.
- The final candidate refresh changed only packaged release documentation.
  Both client JARs contain the same 4,795 entry names with byte-identical entry
  payloads; the accepted `WorldEditorInterface.class` payload is
  `af1958535288cad5b9cb1b094555215c4ca8f5c3dcc7b7868c98df3ee3e47fee`.

The v0.2.0-alpha.1 owner-native layered, packed, standalone, placement,
save/reopen, import, undo, and isolation report remains the accepted unchanged
baseline. Current automated and runtime real-process tests revalidated those
contracts after the v0.3 changes.

## Compatibility and release scope

| Contract | Evidence | Status |
| --- | --- | --- |
| Content-neutral drop-in adaptive application | External archive inspection and full suite | PASS |
| Layered adoption, packed conversion, standalone creation | Prior owner baseline plus current lifecycle suite | PASS |
| Isolated save/export/import/recovery/undo | Current transaction suite | PASS |
| Portable region snapshot/bundle/cut/paste foundation | Exact round-trip, collision, recovery, and family tests | PASS |
| Unsigned 16-bit elevation and recovery | Format boundary, promotion crash matrix, runtime lifecycle, owner report | PASS |
| Elevated scenery and foreground terrain targeting | Runtime tests and owner report | PASS |
| Contextual two-column toolbar | Exact client build, focused tests, owner report | PASS |
| Linux/Windows archives and update isolation | Inspector and updater suites | PASS with accepted platform limitation |

## Accepted limitations

- Region snapshot storage, portable bundles, and atomic operations are
  implemented, but the in-game ordered-marker and ghost-preview workflow is
  not yet implemented. Current access is through the included tooling
  contract.
- Easy drop-in custom wall and floor material packs are not implemented in
  this release.
- A native Windows application launch and native PowerShell updater execution
  were not performed. Windows archive, Java, launcher, and static transaction
  review passed; the launchers remain Java intermediaries.

These limitations do not weaken project isolation, map compatibility,
transaction safety, archive neutrality, or the exact runtime-provider lock.

## Production rule

The candidate archives listed above are validation evidence only. They must not
be copied, renamed, uploaded, or promoted as production release files.
Production archives must be rebuilt after this record and `RELEASE-READY` are
committed and published on clean `main`, using the exact locked runtime and
reviewed JRE inputs.

## Post-publication gate state

The production release was published from gate commit
`80e785170adb17580dd725c21ecda98b730972fb` as tag
`rsc-world-editor-v2-0.3.0-alpha.4`. That immutable tag retains this accepted
record and the release gate. The production assets were downloaded back from
GitHub and verified against the uploaded `SHA256SUMS.txt`:

| Production artifact | SHA-256 |
| --- | --- |
| `rsc-world-editor-v2-0.3.0-alpha.4-linux-x64.zip` | `dc577212e0b70a1bd940dfccc04b463414a6b1a674b5f2f72d284f2afb09cf25` |
| `rsc-world-editor-v2-0.3.0-alpha.4-windows-x64.zip` | `1a4a70c32ee99b2c00c699b34779c6a451e25a0e991acb27d8c25bca3e9744a4` |
| `SHA256SUMS.txt` | `8dd182b3573ce95b682468d43caa0091152a20fa82ff021e92588c3545058d46` |
| External production inspection | `ef45a67093bbd6901e4fcb105705fa2d5512a096412dfd48e660c6ddfd5bba9d` |

Development `main` consumes/removes the gate after publication. Any later
release therefore requires a new exact candidate, owner decision, validation
record, and gate commit.
