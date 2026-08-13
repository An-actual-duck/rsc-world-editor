# World Builder 2 v0.2.0-alpha.1 adaptive validation — PENDING

This is the Phase 7 candidate worksheet, not an accepted validation record.
No candidate has been accepted, and this file does not authorize production
packaging, tagging, publication, deployment, or creation of
`release/world-builder-v2/RELEASE-READY`. The historical v0.1.0-alpha.1 record
is unchanged and does not approve this adaptive design.

## Gate state

- Status: **PENDING — NOT RELEASE READY**
- Restricted pre-gate candidate World Editor commit:
  `b05b16fd744f410a7e95e601f5f8f8d42ea2ce6b`
- Locked runtime commit:
  `0dd7aabb1eb599b2082ae44503ce42cf589b00fd`
- Restricted pre-gate Linux candidate SHA-256:
  `4fd6949addebd87dbd9920d80d2c3e7fdb64a602cc820e32acf54635371e5c80`
- Restricted pre-gate Windows candidate SHA-256:
  `0f7a4ff742cbbb83213be204bdaed51f3f627753d8bd270f94aa3359a3cf6b17`
- Restricted pre-gate `SHA256SUMS.txt` SHA-256:
  `d6658e36f401dc286d5362e4ab056dc05d5e28152fe74d82531619b06d8d9c24`
- Reviewed Linux JRE inventory SHA-256:
  `56e02eae89660c0d7baef03b276f2c8f6ef1749d79403c074dc41ea8f3403c9e`
- Reviewed Windows JRE inventory SHA-256:
  `9aaf15bca3b380b3b9099d3097182d85cbc83003d57d0844c8fc36f7c25b2967`
- Owner-native report: **PENDING**
- Accepted limitations: **NONE ACCEPTED**
- Release decision and accepting manager/owner: **PENDING**

Every `PENDING` field must be replaced with exact evidence for one candidate.
An unavailable check stays explicitly unavailable; it is never inferred from
another platform, an older release, a fixture archive, or a different runtime
checkout.

The candidate source now contains the production project-local adaptive launch
path rather than the earlier intentional `LOADER_INCOMPATIBLE` stub. That makes
the owner checklist executable; it does not satisfy it. Native visual/edit/save/
reopen evidence, `releaseReady`, and the release decision remain PENDING.

## Automated evidence for the restricted candidate

Automated archive inspection passed on 2026-08-09 for the exact candidate
commit and locked runtime above. This evidence does not accept the candidate:
the official record reports `releaseReady: false`,
`releaseGateChanged: false`, and status
`automated-archive-inspection-passed`.

- Official `candidate-archive-inspection.json` SHA-256:
  `b27635328f62362628849c7f6ace95ca1ab60547389d108f38af601a470dfd41`.
  It passed clean-published-source, exact locked runtime, external artifact,
  outer checksum, safe-root, exact application allowlist, exhaustive manifest,
  content-neutral world/creator scan, empty Builder seed, dual-platform Java
  17 metadata, exact reviewed JRE bytes/modes, Linux launcher modes, and
  production runtime identity/capability assertions.

The remaining hashes in this subsection are retained as prior-candidate
baseline evidence only. They are not acceptance evidence for the rejected
`b05b16f`/`0dd7aab` candidate and must be replaced by exact reruns after the
provider login correction is separately authorized and locked.

- Independent `ai1-independent-archive-audit.7U11Fn.json` SHA-256:
  `69ca6dc86dae8c4115923ffa00409256252d78946ece453abf9c5effacd6ea86`.
  It confirmed 98 exact allowlist records, the exact 42-file neutral definition
  closure, byte equality with the locked provider, and absence of
  `defs/locs`, terrain, placements, user/project/workspace state, and shared
  PEM keys. It also confirmed the packaged Windows launcher byte-for-byte
  against source and reviewed its adaptive target/runtime/identity control
  flow without executing Windows.
- Extracted Linux no-UI real-runtime integration log SHA-256:
  `86ae0a3edf69e5e1dd0af1b111fce6c607740cc7477c340db74012259796628c`.
  The test passed standalone-empty discovery and creation, real server
  readiness, database migrations, orderly shutdown, post-run save/reopen,
  project-local key/log handling, and immutable package/provider/target checks
  using the candidate's bundled Temurin 17 runtime.
- Validation-record test log SHA-256:
  `108df76b4fd4891c0c44bc3d33909eb8f859d9d8095a254636fa454dbf9c2a27`.
  It records a clean diff check, all 21 candidate-validation tests, all four
  product-generation tests, project-independence validation, and all 17
  adaptive-contract tests passing.
- Focused candidate-suite log SHA-256:
  `0627a421457759874dbd009a577ceb48dc47554af73ba353ee852ec863137289`.
  It records 166 passing tests plus project-independence validation across
  discovery, conversion, project lifecycle, supervision, Phase 6
  transactions, packaging, updater, product-generation, and workspace
  contracts. The exact-runtime test and five native PowerShell tests were
  explicitly skipped because their reviewed local inputs were unavailable;
  they are not claimed by this log.
- Independent immutable-input inventory digests before and after validation
  matched exactly:
  `3e75f4af8b81851ca79f256415b2bb6832e5440f22dd9b6d5d9468781490baf6`
  for the locked provider working tree,
  `fc6632d6d7e32e3aad9cc8ff876e5d0c05ae954d7077c3cfc6b10abf706ff650`
  for the physical Linux JRE tree, and
  `e800e14cfaaa4370065277a9dc7c0f7007fd58ad8dadb8ed22540fb47eeb8a1d`
  for the physical Windows JRE tree. File counts, byte counts, directory and
  link counts, modes, and aggregate digests were unchanged. The candidate
  archive and checksum hashes also matched their pre-validation values.

Still pending are owner-native layered and standalone edit/save/reopen,
owner software/OpenGL visual review, disposable-target import/undo/recovery,
and manager candidate acceptance. Native Windows application execution is not
required by the current owner decision and was not performed or claimed;
native PowerShell updater execution remains unclaimed. No limitation or
release decision has been accepted.

### Confirmed owner-native launch blocker

The locked provider now correctly avoids the missing legacy landscape archive:
the extracted Linux candidate starts its server and OpenGL client and remains
alive without attempting a legacy terrain read. It still cannot reach owner
visual/edit validation because the automatic Builder login times out before
authentication.

The client emits a complete custom login frame whose two-byte length is 278
bytes. On each attempt, the provider's undecided protocol decoder logs
`Buffer readable bytes: 278 len: 1` followed by
`Buffer readable bytes: 276 len: 0`. It has consumed the high length byte
`0x01` as a one-byte legacy frame length and then consumed the actual login
opcode `0x00` as a zero-length frame. The login handler is never reached.
The isolated Builder credential is a valid project-owned 20-byte regular file
at mode `0600`, the account provisioner reports success, and no credential is
included in this record.

World Editor must not weaken the login or adaptive binding contracts and must
not patch or copy the provider. The locked runtime provider must classify complete
two-byte custom login frames before destructive legacy-frame parsing while an
incoming connection is still undecided. Exact requirements and regression
coverage are in
[`RUNTIME-PROVIDER-ADAPTIVE-LOGIN-CORRECTION.md`](../RUNTIME-PROVIDER-ADAPTIVE-LOGIN-CORRECTION.md).

This blocker invalidates no recorded automated archive evidence, but it blocks
owner acceptance and release readiness until an authorized provider SHA is
reviewed, locked through separate work, rebuilt, independently inspected, and
launched natively. The owner report, every final status, and the release
decision remain **PENDING**. No limitation, gate, or release is accepted.

### Rejected fresh candidate package closure

A later fresh restricted candidate built from published World Editor commit
`d7332f671f836287e609abb962652a3cf57fa810` and locked runtime
`ff9da0aa3d712993f4f06648dc397bdd9062eabc` reached `LoginPacketHandler` with
the packaged OpenGL client, but then timed out. The packaged server repeatedly
threw a null-pointer exception in `MySqlGameDatabase.queryLoadPlayerData`
because the inherited private MySQL query set had no
`player.getPlayerByUsername` registration. The package contained the complete
SQLite query XML set, but `SqliteGameDatabase` inherits player loading from a
superclass that initializes its own private `DatabaseType.MYSQL` query set.

That fresh candidate is rejected. Its archives, checksum values, and any prior
candidate hashes must not be reused or promoted. The package contract now must
carry only the content-neutral inherited MySQL query closure
`bank_presets.xml`, `item.xml`, and `player.xml`, and the extracted native test
must run the actual packaged client through authenticated binding and native
readiness. This correction does not accept a candidate, alter the runtime lock,
or open `RELEASE-READY`; all final evidence remains **PENDING**.

### Pending standalone start-coordinate provider correction

The repository-owned standalone generator now binds its empty project to
`global`, layer `0`, coordinate `120,648`, with one generated sector at
`2,13` and the exact centered 3-by-3 visibility seed. Temporary lifecycle
fixtures prove deterministic generation, save/reopen, source and target
preservation, and the no-target import/undo boundary.

The exact locked runtime provider at
`ff9da0aa3d712993f4f06648dc397bdd9062eabc` is not compatible with that start.
Its `AdaptiveWorldBuilderRuntimeIdentity.validateConfiguredIdentities()`
hard-refuses every `standalone-empty` identity whose initial coordinate is not
`0,0`. A real packaged-client/server run therefore exits before readiness with
`Standalone empty mode must begin at global layer 0, coordinate 0,0`.

This branch must not weaken its generated package or substitute `0,0`. Before
another candidate can be built, separately authorized runtime-provider work
must:

1. retain strict adaptive activation, `global` world space, layer `0`, and the
   existing bounded `0..32767` coordinate checks;
2. accept the exactly bound standalone initial coordinate instead of requiring
   literal `0,0`;
3. prove the configured coordinate is covered by the validated native layered
   terrain before an editable session starts;
4. preserve target-backed starts, normal production profiles, native terrain
   readiness, binding identities, and all legacy-client behavior; and
5. add real client/server coverage for `120,648` using only sector `2,13`,
   including authentication, player load, adaptive binding, native readiness,
   clean shutdown, and no legacy terrain fallback.

Only an authorized, published provider SHA may then be reviewed and advanced
through `runtime-provider.lock`. Until that happens, standalone native launch
and AC-07 are **BLOCKED**, while every owner and release gate remains
**PENDING**.

## Required immutable inputs

- a clean World Editor manager checkout on the exact published `origin/main`
  commit represented inside both candidate archives;
- a separate clean runtime checkout at the exact commit in
  `runtime-provider.lock`, without fetching, advancing, or substituting a newer
  provider revision during release preparation;
- reviewed Linux x64 and Windows x64 JRE 17+ directories, including legal
  notices, plus the exact LWJGL 3.3.4 Linux/Windows native inputs required by
  the packager;
- Linux and Windows candidate archives and `SHA256SUMS.txt` copied to a review
  directory outside both source trees; and
- a reviewed `pwsh` executable for native PowerShell transaction execution if
  that execution is to be claimed. Static PowerShell/launcher coverage does
  not count as native PowerShell execution.

The owner has accepted Java behavior plus automated and code-review coverage
for the Windows launcher; a native Windows-host application launch is not a
release prerequisite. Any unavailable PowerShell execution must still be
recorded honestly.

## Automated candidate commands

Run from clean, published manager `main`. Store console logs and the generated
JSON outside the repository and record their hashes below.

```bash
git diff --check
./scripts/test-world-builder-v2-candidate.sh
./scripts/test.sh

./scripts/ai-manager.sh candidate \
  --version v0.2.0-alpha.1 \
  --runtime-provider /path/to/clean-exact-locked-runtime \
  --linux-jre /path/to/reviewed-temurin-17-linux-x64-jre \
  --windows-jre /path/to/reviewed-temurin-17-windows-x64-jre \
  --assets-cleared

# Copy the three files from output/candidates/world-builder-v2/v0.2.0-alpha.1/
# to /outside-sources/ before inspection.

./scripts/inspect-world-builder-v2-candidate.py \
  --source-root /path/to/clean-published-rsc-world-editor \
  --runtime-provider /path/to/clean-exact-locked-runtime \
  --linux-jre /path/to/reviewed-temurin-17-linux-x64-jre \
  --windows-jre /path/to/reviewed-temurin-17-windows-x64-jre \
  --version v0.2.0-alpha.1 \
  --linux-archive /outside-sources/rsc-world-editor-v2-0.2.0-alpha.1-linux-x64.zip \
  --windows-archive /outside-sources/rsc-world-editor-v2-0.2.0-alpha.1-windows-x64.zip \
  --checksums /outside-sources/SHA256SUMS.txt \
  > /outside-sources/candidate-archive-inspection.json
```

The candidate route fails unless the gate marker is absent and every real
production build, provenance, runtime, no-world, and archive check passes. It
cannot use the fixture-only `--skip-build` path and writes only restricted
pre-gate artifacts. The inspector fails unless the source is clean published
`main`, the runtime is clean at the exact lock, the reviewed JRE trees remain
stable, and all three artifact inputs are outside both source trees. Its JSON
binds the two outer hashes and manifests to both source commits and the complete
dereferenced JRE file/directory inventories, bytes, and relevant modes. It also
repeats safe-root, no-link/special-mode, case/path, exhaustive-manifest,
application-allowlist, exact Linux launcher mode, copied-source, renamed
world/creator content, empty database seed, runtime identity, JRE metadata, and
production-marker checks.
Even on success it reports `releaseReady: false`, `releaseGateChanged: false`,
and the still-pending owner/manager evidence; it cannot authorize a release.

### Command evidence

| Check | Exact command/input | Result | Log/evidence SHA-256 |
| --- | --- | --- | --- |
| Whitespace | `git diff --check` | PASS | `108df76b4fd4891c0c44bc3d33909eb8f859d9d8095a254636fa454dbf9c2a27` |
| Validation-record regression set | candidate-validation, product-generation, independence, and adaptive-contract tests | PRIOR BASELINE — exact corrected-candidate rerun required | `108df76b4fd4891c0c44bc3d33909eb8f859d9d8095a254636fa454dbf9c2a27` |
| Focused candidate suites | `./scripts/test-world-builder-v2-candidate.sh` | PRIOR BASELINE — exact corrected-candidate rerun required | `0627a421457759874dbd009a577ceb48dc47554af73ba353ee852ec863137289` |
| Full repository suite | `./scripts/test.sh` | PENDING | PENDING |
| Restricted real pre-gate build | `./scripts/ai-manager.sh candidate ...` | PASS — restricted artifacts only; acceptance pending | `b27635328f62362628849c7f6ace95ca1ab60547389d108f38af601a470dfd41` |
| External archive inspection | `inspect-world-builder-v2-candidate.py` command above | PASS — automated only; acceptance pending | `b27635328f62362628849c7f6ace95ca1ab60547389d108f38af601a470dfd41` |
| Linux updater success/refusal/install-failure/rollback | focused suite and exact candidate fixture | PRIOR BASELINE — exact corrected-candidate rerun required | `0627a421457759874dbd009a577ceb48dc47554af73ba353ee852ec863137289` |
| PowerShell updater transaction execution | `WORLD_BUILDER_PWSH=...` focused/full suite | PRIOR BASELINE UNAVAILABLE — native execution not claimed | `0627a421457759874dbd009a577ceb48dc47554af73ba353ee852ec863137289` records all five explicit skips |
| Windows updater/launcher Java and static control flow | exact archive/source comparison and static review; no Windows execution | PRIOR BASELINE — exact corrected-candidate static rerun required | `69ca6dc86dae8c4115923ffa00409256252d78946ece453abf9c5effacd6ea86` |
| Phase 6 layered/packed import, rollback, recovery, undo | focused transaction suite | PRIOR BASELINE — exact corrected-candidate rerun required | `0627a421457759874dbd009a577ceb48dc47554af73ba353ee852ec863137289` |
| Adaptive project-local launch, lock/readiness/failure cleanup, clean save/reopen | extracted candidate no-UI real-runtime integration | PRIOR BASELINE — native login now fails on the rejected candidate | `86ae0a3edf69e5e1dd0af1b111fce6c607740cc7477c340db74012259796628c` |

Fixture archives prove rejection and transaction behavior; only the external
inspection row may be used as evidence for the final candidate archive hashes.
The accepted values remain pre-gate validation hashes. Production archives
must be rebuilt after a later accepted-record/gate commit on newly published
`main`; record those production hashes separately and never promote these
pre-gate files in place.

## Archive and package results

Copy these values exactly from the passing inspection JSON and independently
retain the outer `SHA256SUMS.txt`.

| Platform | Archive | Outer SHA-256 | Inner manifest SHA-256 | Reviewed JRE inventory SHA-256 | Manifested files | Result |
| --- | --- | --- | --- | --- | --- | --- |
| Linux x64 | `rsc-world-editor-v2-0.2.0-alpha.1-linux-x64.zip` | `4fd6949addebd87dbd9920d80d2c3e7fdb64a602cc820e32acf54635371e5c80` | `f06bd1f5bca564c9f3914a865622798fb7291fb5ee87b01e9941eecf4d2d1468` | `56e02eae89660c0d7baef03b276f2c8f6ef1749d79403c074dc41ea8f3403c9e` | 397 | AUTOMATED PASS — acceptance pending |
| Windows x64 | `rsc-world-editor-v2-0.2.0-alpha.1-windows-x64.zip` | `0f7a4ff742cbbb83213be204bdaed51f3f627753d8bd270f94aa3359a3cf6b17` | `2fd8151e3fb5841e90752781ccaaca313cc91ca40888c302c89b6559dc5e3440` | `9aaf15bca3b380b3b9099d3097182d85cbc83003d57d0844c8fc36f7c25b2967` | 455 | AUTOMATED PASS — acceptance pending |

Required automated archive review statements below are **PASS** for these exact
restricted artifacts; owner validation and candidate acceptance remain
**PENDING**:

- both archives have the sole root `World Builder 2/`;
- archive names, identity, version, source commits, platform runtime, inner
  manifest, and outer checksums agree exactly;
- no terrain, packed map, static placements, active layered package, project,
  registry/selection, export, backup, receipt, diagnostics, settings,
  credentials, logs, PID, downloaded state, or renamed equivalent is present;
- the Builder database seed contains no terrain/placement, player/account,
  log, security, generated-operational, or unknown non-static rows; and
- the packaged launch/import/recovery/undo/update scripts and copied runtime
  assets equal their exact clean source inputs; and
- the complete packaged JRE inventories/bytes/relevant modes equal the reviewed
  JRE trees, with every Linux production shell launcher at mode `0755` and no
  special/setuid bits.

## Compatibility matrix

All target work uses disposable copies. Nothing here authorizes touching a
live or public server.

| Scenario | Automated evidence | Owner/native evidence | Final status |
| --- | --- | --- | --- |
| Compatible layered target: discover/adopt/save/reopen, target unchanged | PASS — temporary fixtures | PENDING | PENDING |
| Compatible packed target: discover/convert/parity/save/reopen, target unchanged | PASS — temporary fixtures | PENDING | PENDING |
| Standalone empty: layer 0/start 120,648, exact 3x3 visibility seed, first authoring/save/reopen/export | PASS — temporary fixtures; exact pinned runtime refuses the start | BLOCKED on provider correction | BLOCKED |
| No server versus recognizable broken/unsupported/ambiguous server | PASS — adversarial discovery fixtures | Owner report if encountered | PENDING |
| Multiple projects, moved folder, detached target, no implicit rebase | PASS — temporary fixtures | PENDING | PENDING |
| Software/OpenGL terrain, levels, collision, and all four placement families | PASS — data, definition, and placement contracts only | PENDING visual review | PENDING |
| Exact import preview/apply/verify/client distribution/undo | PASS — temporary fixtures | PENDING | PENDING |
| Interrupted import/undo rollback and explicit recovery | PASS — injected-failure fixtures | PENDING | PENDING |
| Linux update success, incompatibility, installation failure, rollback | PASS — automated fixtures | PENDING | PENDING |
| Windows launcher | PASS — Java/static and exact archive/source coverage | Native host not required | PENDING |
| PowerShell updater | PASS — static coverage; native execution UNAVAILABLE and unclaimed | Native host not required | PENDING |

## Owner checklist — report text, not screenshots

Use disposable copies and report each numbered item as PASS or FAIL with a
short observation. Do not use a live/public server, and do not send
screenshots for AI judgment.

For every target byte comparison below, inventory all server-owned content
while excluding the complete top-level `World Builder 2/` directory and
everything beneath it. Do not exclude individual files inside that directory
or any other target content. The installation/project directory is expected to
change as projects, receipts, backups, and recovery state are created; the
server-owned comparison scope must not change between before and after.

### A. Target-backed projects

1. Put the complete candidate folder directly inside one disposable compatible
   layered target. Record the server-owned target inventory using the exclusion
   above, launch natively, and
   confirm discovery names that target's active map rather than release-owned
   content. Create the adopted project.
2. Repeat first creation with one disposable supported packed target. Confirm
   the conversion report is understandable and that creation completes without
   changing the target.
3. Open at least one target-backed project in software mode and OpenGL mode.
   Check terrain/floors, levels, collision, boundaries, scenery, NPCs, and
   ground items. Make a small unmistakable terrain and placement edit, save,
   close, reopen, and confirm the edit remains in the isolated project.
4. Confirm both server-owned target inventories remain byte-identical after
   discovery, creation, editing, saving, closing, and reopening.
5. With the selected disposable target fully offline, run Import preview.
   Confirm the exact server/client destinations, activation, backup, receipt,
   and player-distribution identity are understandable. Type `IMPORT`, verify
   success, and test the imported result only on that disposable target.
6. Stop the disposable target again, run Undo preview, type `UNDO`, and confirm
   the pre-import server-owned target inventory is restored exactly. If a
   deliberately interrupted disposable transaction reports `RECOVERY_REQUIRED`, preserve
   its artifacts, run Recovery, and report the verified result.

### B. Standalone empty project

1. Put a separate complete candidate folder in an ordinary empty parent with
   no recognizable server. Launch natively and confirm it clearly offers a
   standalone empty project, not a guessed or bundled map.
2. Confirm it opens at layer 0, coordinate 120,648 with structural void
   available in every direction and a visible centered 3-by-3 floor seed.
   Extend or replace that seed and author one wall or placement, check
   collision, save, close, reopen, and confirm the authored state remains.
3. Export the saved standalone project. Confirm Import, Undo, and Recovery each
   refuse with `NO_TARGET` without asking for or accessing a server path.

### C. Owner report

```text
Candidate commit:
Linux archive SHA-256:
Native platform/runtime:
Layered adoption A1-A6: PASS/FAIL + notes
Packed conversion A1-A6: PASS/FAIL + notes
Standalone B1-B3: PASS/FAIL + notes
Software/OpenGL and save/reopen: PASS/FAIL + notes
Import/Undo/Recovery: PASS/FAIL + notes
Server-owned target byte comparisons (complete `World Builder 2/` excluded): PASS/FAIL + method
Accepted limitations: none / exact text
Release acceptance: NOT YET / ACCEPT THIS EXACT CANDIDATE
```

## Final acceptance audit

Before a manager can separately decide whether to create `RELEASE-READY`, every
row below needs exact evidence and no unresolved failure.

| Contract | Evidence | Status |
| --- | --- | --- |
| AC-01 no release-owned world or creator data | Automated PASS — external inspection JSON and hashes; acceptance pending | PENDING |
| AC-02 adaptive parent-root discovery on both launcher paths | Automated PASS — focused suites; owner native target pending | PENDING |
| AC-03 layered adoption | Automated PASS — fixture; owner report pending | PENDING |
| AC-04 lossless packed conversion and placement parity | Automated PASS — fixture; owner report pending | PENDING |
| AC-05 unsupported/unrepresentable refusal | Automated PASS — focused suites | PENDING |
| AC-06 selected working project is the only edited world | Automated PASS — fixture; owner report pending | PENDING |
| AC-07 canonical standalone structural void | Fixture PASS; exact pinned runtime refuses the bound 120,648 start before readiness | BLOCKED |
| AC-08 standalone save/export and target-operation refusal | Automated PASS — fixture; owner report pending | PENDING |
| AC-09 immutable source, isolated save/reopen, unchanged server-owned target outside complete `World Builder 2/` | Automated PASS — fixture and scoped inventories; owner report pending | PENDING |
| AC-10 multiple/portable/detached projects | Automated PASS — focused suite; owner report pending | PENDING |
| AC-11 deterministic complete export and lineage | Automated PASS — Phase 6 suite | PENDING |
| AC-12 exact server/client import capability and distribution identity | Automated PASS — Phase 6 suite; owner report pending | PENDING |
| AC-13 preview/offline/drift/backup/receipt/rollback/recovery/undo/no-force | Automated PASS — 31-case Phase 6 suite; owner report pending | PENDING |
| AC-14 no implicit server rebase/install | Automated PASS — focused and updater suites | PENDING |
| AC-15 updater durable preservation and v1 isolation | Linux automated PASS; PowerShell static PASS and native execution unavailable | PENDING |
| AC-16 complete automated and owner-native candidate validation | Focused and archive automation PASS; full suite and owner report pending | PENDING |
| AC-17 accurate simple workflow and compatibility documentation | Final documentation review | PENDING |

This worksheet becomes an accepted validation record only after the exact
candidate fields, evidence hashes, owner report, limitations, and decision are
filled deliberately. Merely committing the worksheet never opens the gate.
