# World Builder 2 v0.2.0-alpha.1 adaptive validation — ACCEPTED

This record accepts one exact restricted pre-gate candidate as the validation
basis for World Builder 2 v0.2.0-alpha.1. It opens production packaging through
`release/world-builder-v2/RELEASE-READY`; it does not promote or reuse the
candidate archives. Production archives must be rebuilt from the later clean,
published gate commit and receive their own hashes.

## Acceptance

- Status: **ACCEPTED — RELEASE READY**
- Accepted on: **2026-08-14**
- Accepted by: **project owner**, with manager verification of the recorded
  automated evidence
- Restricted candidate World Editor commit:
  `aaab273663e96683bb0eeab773c7df7921e8cfd2`
- Locked runtime-provider commit:
  `a2d00ee389761732ce5c8ffca07f430133aca4f5`
- Version: `v0.2.0-alpha.1`
- Product identity: `rsc-world-editor-v2`
- Install root: complete top-level `World Builder 2/` directory
- World-source identity: `target-adaptive-v1`
- Owner decision: **ACCEPT THIS EXACT CANDIDATE**

The accepted scope is the adaptive, content-neutral World Builder foundation:
drop-in target discovery, isolated projects, layered adoption, packed
conversion, standalone-empty authoring, all four placement families, export,
explicit transactional import, recovery, and undo. It bundles no target map,
terrain, static placements, creator project, or user state.

## Exact candidate artifacts

The restricted artifacts were rebuilt fresh after the canonical placement-save
runtime correction. Every earlier candidate and hash is rejected historical
evidence and must never be promoted or reused.

| Artifact | SHA-256 |
| --- | --- |
| `rsc-world-editor-v2-0.2.0-alpha.1-linux-x64.zip` | `70805f788c277de826945f62913ceafab3cc07bad72a5f832461c71d01aa5c01` |
| `rsc-world-editor-v2-0.2.0-alpha.1-windows-x64.zip` | `dbf52cf3169393343b6ccddf1adaa3e3dbb204e5c55dded2eccfb80b3e58c243` |
| `SHA256SUMS.txt` | `45762359d1a86588909b49d8f19ed5b7c85750ca6c62a29eaffebe1837933eb3` |
| `candidate-archive-inspection.json` | `b1078ace7f1d3ea0906e9607dcd471e9e606ec913158afae745ef3eeeda45d1e` |

Independent inspection reported
`automated-archive-inspection-passed`. As designed for a restricted pre-gate
inspection, its evidence fields remained `releaseReady: false` and
`releaseGateChanged: false`; those fields prove the inspector did not accept or
open the release gate on its own. The owner decision recorded here is the
separate acceptance step.

| Platform | Inner manifest SHA-256 | Reviewed JRE inventory SHA-256 | Files |
| --- | --- | --- | ---: |
| Linux x64 | `f12aa7414135ed1b069a5467b66b63ce7a02da6ae76c9233eabfa67fe23c6f2d` | `56e02eae89660c0d7baef03b276f2c8f6ef1749d79403c074dc41ea8f3403c9e` | 400 |
| Windows x64 | `539e9e915c6d8a96315ee20bbaee8073843df064d1b5992f2523430d11592252` | `9aaf15bca3b380b3b9099d3097182d85cbc83003d57d0844c8fc36f7c25b2967` | 458 |

The inspector script SHA-256 was
`d2b9a2a3ca09cdd2228972b500eb394799e76e9c42c7e111ec1db376b097dbd8`.
Inspection verified exact clean published sources, the exact lock, sole safe
archive root, exhaustive manifests, reviewed JRE bytes and modes, launcher
modes, application allowlists, production runtime identity, content-neutral
default catalogs, an empty Builder seed, and absence of world, creator,
credential, log, project, backup, receipt, and downloaded state.

## Automated evidence

| Check | Result | Evidence SHA-256 |
| --- | --- | --- |
| `git diff --check` | PASS | `e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855` |
| `./scripts/test-world-builder-v2-candidate.sh` | PASS | `9598fca182f325f2abfe9c5243c7f4d1aea587c69754970cbb6a0a53559ea3b4` |
| `./scripts/test.sh` | PASS | `f281b4bbf2a5f1b42423dd36816868c9691c7deb10c5154be973b014078c8cee` |
| Exact extracted packaged client/server integration | PASS in 16.084 seconds | `62f2111392c27aa79ac99c1d4b262e363ef58f1e8a19f8927f671567a8594211` |

The focused and full suites passed against the exact locked provider. Five
native PowerShell execution cases were explicitly skipped because `pwsh` was
unavailable; their static transaction and launcher coverage passed. The real
packaged Linux integration used the candidate's bundled Java runtime and
proved authenticated Builder binding, native terrain and placement readiness,
save/reopen behavior, and clean client/server shutdown.

## Owner-native report

The owner checklist requested report text, not screenshots. The owner supplied
the following textual results.

### Compatible layered target

- Disposable target:
  `/tmp/rsc-world-editor-phase7-canonical-owner-layered.x2qItk`
- Project: `5f86a3e2-7934-48b2-8820-4637e40ed8d3`
- All 14 server-owned files stayed byte-identical through discovery, editing,
  save, reopen, software rendering, and OpenGL rendering.
- Existing scenery ID 20 at `(5,5)` and newly placed scenery ID 21 at `(4,3)`
  saved in canonical order and both reappeared after reopen.
- Default OpenGL and explicit software rendering were visually accepted.
- Client and server exited cleanly.

### Import, undo, and transaction safety

- Import transaction: `8f3d796a-a620-46cf-9358-d4fef7666b94`.
- The exact server/client package was installed and verified with a seven-file
  receipt, backup, offline process/port evidence, and matching content identity.
- Recovery correctly refused because the completed import had no
  `RECOVERY_REQUIRED` state.
- Undo transaction: `47bde609-f5d7-4e14-9336-94e0af34d5ac`.
- Undo restored the exact original 14-file inventory and removed all installed
  package roots. Automated coverage separately exercises injected rollback and
  recovery boundaries.

### Compatible packed target

- Disposable target:
  `/tmp/rsc-world-editor-phase7-canonical-owner-packed.53HOIB`
- Project: `67c651a3-dac8-45ac-81ee-082c824c3de8`
- All 22 server-owned files remained byte-identical across discovery,
  conversion, editing, save, and reopen.
- The owner confirmed the edit persisted and both processes exited cleanly.

### Standalone empty project

- Disposable parent:
  `/tmp/rsc-world-editor-phase7-canonical-owner-standalone.WiAaX7`
- Project: `351336bb-7f5e-470d-bb80-5ba7ed0543a8`
- The empty parent gained only `World Builder 2`.
- Start position was exactly `(120,648)`, level 0, sector `2,13`, with the exact
  centered 3-by-3 zero-color/zero-overlay visibility seed and nine non-void
  tiles.
- Terrain editing and scenery placement persisted through save and reopen.
- Standalone export succeeded with export fingerprint
  `54c86a5e99d7c55420168762915bc4e299a8fcc0d81c5629276d448a36ab4d70`
  and package fingerprint
  `94666ee6c9305b260da5d6c25dde2566c529595b0e5f36e0c95db7e3c6632fc5`.
- Import, Undo, and Recovery each returned `NO_TARGET` before target access.

## Compatibility and acceptance matrix

| Contract | Evidence | Status |
| --- | --- | --- |
| AC-01 no release-owned world or creator data | External archive inspection | PASS |
| AC-02 adaptive parent-root discovery | Automated and owner-native target/empty fixtures | PASS |
| AC-03 layered adoption | Byte-identical target plus save/reopen report | PASS |
| AC-04 lossless packed conversion and placement parity | Automated parity plus owner save/reopen report | PASS |
| AC-05 unsupported or unrepresentable input refuses | Adversarial discovery/conversion suites | PASS |
| AC-06 selected working project is the sole edited world | Project isolation and immutable-target evidence | PASS |
| AC-07 canonical standalone structural void | Exact `(120,648)`/sector `2,13`/3-by-3 evidence | PASS |
| AC-08 standalone save/export and target-operation refusal | Owner report and no-target checks | PASS |
| AC-09 immutable source and isolated save/reopen | Layered and packed target inventories | PASS |
| AC-10 multiple, portable, and detached projects | Focused lifecycle suite | PASS |
| AC-11 deterministic complete export and lineage | Phase 6 suite and standalone export | PASS |
| AC-12 exact server/client distribution identity | Verified import receipt and parity | PASS |
| AC-13 preview/offline/drift/backup/rollback/recovery/undo/no-force | 31-case suite and owner import/undo | PASS |
| AC-14 no implicit server rebase or install | Focused lifecycle/updater suites | PASS |
| AC-15 updater preservation and v1 isolation | Linux execution and Windows/PowerShell static review | PASS with accepted platform limitation |
| AC-16 complete automated and owner-native candidate validation | Exact archives, native integration, and owner report | PASS |
| AC-17 accurate workflow and compatibility documentation | Final acceptance documentation review | PASS |

## Accepted limitations and follow-up

- Easy drop-in custom wall and floor material packs are **not implemented in
  this release**. Their approved design remains in
  `docs/WORLD-BUILDER-2-CUSTOM-MATERIALS.md` for the next development release.
- A native Windows application launch was not performed and was not required
  by the owner because the Windows and Linux scripts are Java launch
  intermediaries. Windows archive, Java, launcher, and static control-flow
  review passed.
- Native PowerShell updater execution was unavailable and is not claimed.
  Static PowerShell transaction coverage passed.
- The software renderer opened in windowed mode rather than the usual
  fullscreen presentation; the owner accepted its visuals and behavior.

These limitations do not weaken map isolation, fail-closed compatibility,
transaction safety, archive neutrality, or the exact locked-provider contract.
They are accepted for v0.2.0-alpha.1 and must remain visible in release notes.

## Production rule

The candidate archives listed above are validation evidence only. They must not
be copied, renamed, uploaded, or promoted as production release files.
Production archives must be rebuilt after this accepted record and
`RELEASE-READY` are committed and published on clean `main`, using runtime
provider `a2d00ee389761732ce5c8ffca07f430133aca4f5` and the reviewed JRE inputs.
The rebuilt production files require a fresh integrity check and their own
published SHA-256 values.
