# World Builder 2 v0.2.0-alpha.1 adaptive validation — PENDING

This is the Phase 7 candidate worksheet, not an accepted validation record.
No candidate has been accepted, and this file does not authorize production
packaging, tagging, publication, deployment, or creation of
`release/world-builder-v2/RELEASE-READY`. The historical v0.1.0-alpha.1 record
is unchanged and does not approve this adaptive design.

## Gate state

- Status: **PENDING — NOT RELEASE READY**
- Restricted pre-gate candidate World Editor commit: **PENDING clean published `main`**
- Locked runtime commit:
  `3cd36570ca7df6c436714b5358904aa5953fd1ba`
- Restricted pre-gate Linux candidate SHA-256: **PENDING**
- Restricted pre-gate Windows candidate SHA-256: **PENDING**
- Restricted pre-gate `SHA256SUMS.txt` SHA-256: **PENDING**
- Reviewed Linux JRE inventory SHA-256: **PENDING**
- Reviewed Windows JRE inventory SHA-256: **PENDING**
- Owner-native report: **PENDING**
- Accepted limitations: **NONE ACCEPTED**
- Release decision and accepting manager/owner: **PENDING**

Every `PENDING` field must be replaced with exact evidence for one candidate.
An unavailable check stays explicitly unavailable; it is never inferred from
another platform, an older release, a fixture archive, or a different runtime
checkout.

## Required immutable inputs

- a clean World Editor manager checkout on the exact published `origin/main`
  commit represented inside both candidate archives;
- a separate clean runtime checkout at the exact commit in
  `core-framework.lock`, without fetching, advancing, or substituting a newer
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
  --core-framework /path/to/clean-exact-locked-runtime \
  --linux-jre /path/to/reviewed-temurin-17-linux-x64-jre \
  --windows-jre /path/to/reviewed-temurin-17-windows-x64-jre \
  --assets-cleared

# Copy the three files from output/candidates/world-builder-v2/v0.2.0-alpha.1/
# to /outside-sources/ before inspection.

./scripts/inspect-world-builder-v2-candidate.py \
  --source-root /path/to/clean-published-rsc-world-editor \
  --core-framework /path/to/clean-exact-locked-runtime \
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
| Whitespace | `git diff --check` | PENDING | PENDING |
| Focused candidate suites | `./scripts/test-world-builder-v2-candidate.sh` | PENDING | PENDING |
| Full repository suite | `./scripts/test.sh` | PENDING | PENDING |
| Restricted real pre-gate build | `./scripts/ai-manager.sh candidate ...` | PENDING | PENDING |
| External archive inspection | `inspect-world-builder-v2-candidate.py` command above | PENDING | PENDING |
| Linux updater success/refusal/install-failure/rollback | focused suite and exact candidate fixture | PENDING | PENDING |
| PowerShell updater transaction execution | `WORLD_BUILDER_PWSH=...` focused/full suite | PENDING or UNAVAILABLE | PENDING or N/A |
| Windows updater/launcher Java and static control flow | focused suite and review | PENDING | PENDING |
| Phase 6 layered/packed import, rollback, recovery, undo | focused transaction suite | PENDING | PENDING |

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
| Linux x64 | PENDING | PENDING | PENDING | PENDING | PENDING | PENDING |
| Windows x64 | PENDING | PENDING | PENDING | PENDING | PENDING | PENDING |

Required review statements, all currently **PENDING**:

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
| Compatible layered target: discover/adopt/save/reopen, target unchanged | PENDING | PENDING | PENDING |
| Compatible packed target: discover/convert/parity/save/reopen, target unchanged | PENDING | PENDING | PENDING |
| Standalone empty: layer 0/origin 0,0, first authoring/save/reopen/export | PENDING | PENDING | PENDING |
| No server versus recognizable broken/unsupported/ambiguous server | PENDING | Owner report if encountered | PENDING |
| Multiple projects, moved folder, detached target, no implicit rebase | PENDING | PENDING | PENDING |
| Software/OpenGL terrain, levels, collision, and all four placement families | Contract tests PENDING | PENDING | PENDING |
| Exact import preview/apply/verify/client distribution/undo | PENDING | PENDING | PENDING |
| Interrupted import/undo rollback and explicit recovery | PENDING | PENDING | PENDING |
| Linux update success, incompatibility, installation failure, rollback | PENDING | PENDING | PENDING |
| Windows launcher | Java/static coverage PENDING | Native host not required | PENDING |
| PowerShell updater | Static coverage PENDING; execution PENDING or UNAVAILABLE | Native host not required | PENDING |

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
2. Confirm it opens at layer 0, origin 0,0 with structural void. Author the
   first terrain/floor and one wall or placement, check collision, save, close,
   reopen, and confirm the authored state remains.
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
| AC-01 no release-owned world or creator data | External inspection JSON and hashes | PENDING |
| AC-02 adaptive parent-root discovery on both launcher paths | Focused suites; owner native target | PENDING |
| AC-03 layered adoption | Automated fixture; owner report | PENDING |
| AC-04 lossless packed conversion and placement parity | Automated fixture; owner report | PENDING |
| AC-05 unsupported/unrepresentable refusal | Focused suites | PENDING |
| AC-06 selected working project is the only edited world | Automated fixture; owner report | PENDING |
| AC-07 canonical standalone structural void | Automated fixture; owner report | PENDING |
| AC-08 standalone save/export and target-operation refusal | Automated fixture; owner report | PENDING |
| AC-09 immutable source, isolated save/reopen, unchanged server-owned target outside complete `World Builder 2/` | Automated fixture; scoped byte inventory; owner report | PENDING |
| AC-10 multiple/portable/detached projects | Focused suite; owner report | PENDING |
| AC-11 deterministic complete export and lineage | Phase 6 suite | PENDING |
| AC-12 exact server/client import capability and distribution identity | Phase 6 suite; owner report | PENDING |
| AC-13 preview/offline/drift/backup/receipt/rollback/recovery/undo/no-force | 30-case Phase 6 suite; owner report | PENDING |
| AC-14 no implicit server rebase/install | Focused suite; updater suite | PENDING |
| AC-15 updater durable preservation and v1 isolation | Linux/PowerShell results | PENDING |
| AC-16 complete automated and owner-native candidate validation | All command logs and owner report | PENDING |
| AC-17 accurate simple workflow and compatibility documentation | Final documentation review | PENDING |

This worksheet becomes an accepted validation record only after the exact
candidate fields, evidence hashes, owner report, limitations, and decision are
filled deliberately. Merely committing the worksheet never opens the gate.
