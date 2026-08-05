# Releasing

The packed-map v1 line is frozen at standalone release `v1.1.0`. Its existing
`release/world-builder/` assets and `scripts/package-release.sh` remain for
provenance and reproduction and are not changed by v2 work.

World Builder 2 is the active development generation. It has product/update
identity `rsc-world-editor-v2`, install/display name `World Builder 2`,
world-source identity `target-adaptive-v1`, and package assets under
`release/world-builder-v2/`. The historical pre-adaptive alpha validation in
[`world-builder-v2-v0.1.0-alpha.1-validation.md`](releases/world-builder-v2-v0.1.0-alpha.1-validation.md)
remains unchanged historical evidence; it does not approve the adaptive
package design. The new
[`v0.2.0-alpha.1 adaptive validation worksheet`](releases/world-builder-v2-v0.2.0-alpha.1-validation.md)
is explicitly pending and is not release authorization.

## Release gate

Repository readiness is audited with:

```bash
./scripts/ai-manager.sh release-check
./scripts/test.sh
```

`./scripts/ai-manager.sh release` delegates to the v2 packager only when
`release/world-builder-v2/RELEASE-READY` contains a deliberately accepted
candidate record. That marker is currently absent. Phase 4 owner-native
validation and the Phase 7 candidate-validation record still block adaptive
release readiness. Phase 6 transactions are implemented, but that does not
open or replace the missing release gate.

The dependency checkout used for packaging must already be clean and at the
exact commit in `core-framework.lock`. Packaging never checks for a newer
provider commit and never manages the provider's branches or workers. An
explicit dependency-update task uses `check-core-parity.sh` to verify the
published ref, capability document, runtime surfaces, and protocol.

## Restricted pre-gate candidate command

Before `RELEASE-READY` exists, the manager can build the real archives needed
for owner and archive validation with the guarded candidate route:

```bash
./scripts/ai-manager.sh candidate \
  --version v0.2.0-alpha.1 \
  --core-framework /path/to/clean-pinned-core-framework \
  --linux-jre /path/to/reviewed-temurin-17-linux-x64-jre \
  --windows-jre /path/to/reviewed-temurin-17-windows-x64-jre \
  --assets-cleared
```

This route requires clean published manager `main`, the exact clean lock,
reviewed dual-platform JREs, the real server/client/tools and LWJGL builds, all
provenance and no-world checks, and an absent release marker. It refuses
`--skip-build`, writes only under
`output/candidates/world-builder-v2/<version>/`, and never creates or opens the
gate, tags, uploads, publishes, or deploys. These hashes are restricted
pre-gate validation hashes. After a later accepted validation record and gate
commit, production artifacts are rebuilt from that new published commit and
receive their own hashes; pre-gate archives are never promoted in place.

## Production command

Prepare the exact pinned LWJGL inputs and then run from clean, already-published
manager `main`:

```bash
LWJGL_VERSION=3.3.4 \
LWJGL_MODULES='lwjgl lwjgl-glfw lwjgl-opengl' \
LWJGL_NATIVE_CLASSIFIERS='natives-linux natives-windows' \
  /path/to/clean-pinned-core-framework/scripts/download-lwjgl.sh

./scripts/ai-manager.sh release \
  --version v0.2.0-alpha.1 \
  --core-framework /path/to/clean-pinned-core-framework \
  --linux-jre /path/to/temurin-17-linux-x64-jre \
  --windows-jre /path/to/temurin-17-windows-x64-jre \
  --assets-cleared
```

The restricted `--skip-build` fixture path requires
`WORLD_BUILDER_V2_RELEASE_TEST_MODE=1`. It cannot bypass clean published main,
the exact clean dependency, provenance, runtime, identity, archive, or
no-world validation, and is never release authorization.

## Packager requirements

The production packager requires and verifies:

- clean published World Editor `main` and a clean exact pinned dependency;
- Linux and Windows JRE 17+ inputs with contained links and legal notices;
- the exact LWJGL 3.3.4 base/native set for Linux x64 and Windows x64;
- confirmed redistribution terms and complete asset provenance;
- the release-marked production client and required generic Phase 4
  client/server/tool classes;
- exact `rsc-world-editor-v2`, `World Builder 2`, and `target-adaptive-v1`
  identity plus both source commits;
- only files named by the checked-in runtime/default-catalog allowlist,
  repository schemas, launch/import/recovery/undo scripts, documentation, and
  platform JRE;
- one user/world-empty Builder-only database seed whose only nonempty tables
  are reviewed migration metadata, generic recovery questions, and SQLite
  counters;
- safe, case-collision-free, link-free platform archives with an exhaustive
  `PACKAGE-MANIFEST.sha256`; and
- archive checksums generated after final integrity verification.

There is no layered-package input or world generator. Broad recursive copies
from client cache, server configuration, or server database trees are
forbidden. Stage validation rejects terrain archives, static boundary/scenery/
NPC/ground-item data, active layered packages, project/registry/selection
state, exports, backups, receipts, diagnostics, settings, credentials, logs,
PIDs, endpoint identity, downloaded/generated state, and every database except
the reviewed user/world-empty Builder seed. It compares staged file and
nested-archive hashes with the pinned dependency's forbidden world sources,
parses renamed structured
payloads, runs SQLite integrity validation, rejects rows in every terrain/
placement, player/account, log, security, generated-operational, or unknown
non-static seed table, and enforces an exact path allowlist.

The external runtime still uses its provider-specific production build marker
name. That marker is a build-integrity input, not the World Builder product,
install, world, or update identity.

## Update validation

Each platform updater must demonstrate the same behavior:

- exact adaptive identity/channel selection with no v1, draft, malformed,
  duplicate, equal-version, or downgrade selection;
- safe archive, exhaustive manifest, required-file, checksum, provenance, and
  exact application-allowlist validation;
- refusal to own or replace `projects/`, registry/selection, historical
  `workspace/`, exports, backups, receipts, diagnostics, logs, settings,
  recovery state, or unknown files;
- byte-for-byte preservation of multiple projects and every durable path on
  success and injected rollback;
- rollback of only the managed application when the Phase 3 `open-project`
  compatibility check rejects the selected project; and
- explicit refusal of a historical-only pre-adaptive install, with no implicit
  relabelling or workspace migration.

The compatibility check validates what the current project lifecycle exposes;
it does not export, import, alter, rebase, or migrate a project. Implemented
Phase 6 transactions remain separate explicit user commands and are never run
by an application update.

## Final candidate validation

Before adding a new adaptive `RELEASE-READY` record:

1. Run `git diff --check`, focused release/updater/product suites, and the full
   repository suite using the exact clean pinned dependency.
2. Build restricted real pre-gate archives with `ai-manager.sh candidate`, then
   inspect both archives and manifests from outside either source tree. Confirm
   the only root is `World Builder 2/` and search content as well as names for
   world or creator payloads.
3. Exercise target-layered adoption, packed conversion, and standalone empty
   creation without Git, Ant, source code, or a system JDK. Confirm the target
   remains byte-identical.
4. Ask the owner to perform the native software/OpenGL visual, edit, save,
   close, and reopen checks for adopted and standalone-empty projects. AI
   sessions do not capture or judge screenshots.
5. Run Linux and PowerShell update success, incompatibility, installation
   failure, and rollback fixtures. Perform the available native platform smoke
   check and record any reviewed launcher-only platform limitation explicitly.
6. Validate Phase 6 export/import/rollback/recovery/undo against disposable
   offline layered and packed-origin targets, including exact preview,
   server/client distribution identity, changed-after refusal, standalone
   refusal, and injected failures at mutation boundaries.
7. Record the exact source commits, commands, artifact hashes, compatibility
   matrix, owner report, remaining limitations, and accepted candidate commit
   in a new adaptive validation record.

The repeatable focused boundary is:

```bash
./scripts/test-world-builder-v2-candidate.sh
./scripts/test.sh
```

After the guarded pre-gate build, copy both archives and `SHA256SUMS.txt` from
`output/candidates/world-builder-v2/<version>/` to a review directory
outside the World Editor and pinned runtime trees. From clean published manager
`main`, bind those real artifacts to both exact clean source commits and emit
the independent evidence document with:

```bash
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

The inspector never packages, extracts, publishes, or changes either source
tree. It binds the complete dereferenced file/directory inventory, every file
digest, and relevant executable/special mode state to the exact reviewed JRE
trees, and it requires exact mode `0755` on every Linux production shell
launcher. Its JSON status is `automated-archive-inspection-passed` while
`releaseReady` and `releaseGateChanged` remain `false`; archive success cannot
stand in for pending owner and manager evidence. Fixture archives from the
focused suite are regression evidence, not substitutes for this real-artifact
inspection. Fill the pending worksheet only with results from one exact
candidate; unavailable native PowerShell execution or owner checks stay
visibly pending or unavailable.

Publishing a World Editor release does not authorize changing, deploying, or
restarting any public game server.
