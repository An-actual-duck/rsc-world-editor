# Releasing

The packed-map v1 line is frozen at standalone release `v1.1.0`. Its existing
`release/world-builder/` assets and `scripts/package-release.sh` remain for
provenance, reproduction, and deliberate maintenance analysis; they are not an
active release channel.

World Builder 2 is the active development generation. It has a distinct
product/update identity (`rsc-world-editor-v2`), install folder, signed-layered
workspace, and `release/world-builder-v2/` package assets. Public v2 alpha
packaging is enabled after the real-archive validation and owner acceptance
recorded in
[`docs/releases/world-builder-v2-v0.1.0-alpha.1-validation.md`](releases/world-builder-v2-v0.1.0-alpha.1-validation.md).
The dedicated packager and workspace-preserving v2 updater remain separate
from the frozen v1 channel.

## Release readiness

Repository-level readiness can still be audited:

```bash
./scripts/ai-manager.sh release-check
./scripts/test.sh
```

Source parity is not a World Builder 2 packaging prerequisite. This repository
owns `tools/world-builder/` and `release/world-builder-v2/`, so tested fixes in
those paths may be newer than the frozen runtime dependency. Keep
`check-core-parity.sh` and `sync-from-core-framework.sh` for separately
authorized exact-commit dependency-update tasks. The packager still requires
the dependency checkout itself to be clean and at the exact commit named by
`core-framework.lock`.

`./scripts/ai-manager.sh release` applies the manager release check and then
delegates to the v2 packager. It remains fail-closed if
`release/world-builder-v2/RELEASE-READY` is absent. Removing or changing that
marker requires a deliberate release-readiness review.

The v2 packager is:

```bash
LWJGL_VERSION=3.3.4 \
LWJGL_MODULES='lwjgl lwjgl-glfw lwjgl-opengl' \
LWJGL_NATIVE_CLASSIFIERS='natives-linux natives-windows' \
  /path/to/clean-pinned-core-framework/scripts/download-lwjgl.sh

./scripts/ai-manager.sh release \
  --version v0.1.0-alpha.1 \
  --core-framework /path/to/clean-pinned-core-framework \
  --linux-jre /path/to/temurin-17-linux-x64-jre \
  --windows-jre /path/to/temurin-17-windows-x64-jre \
  --assets-cleared
```

Production packaging remains locked without the acceptance marker. Its restricted
`--skip-build` path requires
`SPOILED_MILK_WORLD_BUILDER_V2_RELEASE_TEST_MODE=1` and exists only for
deterministic temporary-fixture tests; it is not release authorization.

The packager requires:

- clean, already-published `main` in this repository;
- a clean checkout at the exact commit in `core-framework.lock`;
- Linux and Windows JRE 17+ inputs;
- the pinned LWJGL 3.3.4 base jars and both Linux-x64 and Windows-x64 native
  classifiers prepared by the exact command above;
- confirmed redistribution terms for every packaged asset;
- a reviewed signed-layered package;
- exact v2 product/update identity, v2-prefixed tag/archive names, and
  self-only update eligibility;
- provenance containing both repository commits; and
- archives containing no workspace, credentials, databases, logs, backups,
  receipts, generated endpoints, ignored `server/ipbans.txt`, or other user
  state.

Production packaging always invokes the pinned client build with
`SPOILED_MILK_RELEASE_BUILD=1` and then requires the client jar to contain the
exact `spoiled-milk-release-build.marker` value. Missing, invalid, or extra
LWJGL input jars fail before the build and print the cleanup guidance and
reproducible preparation command; missing native entries or a missing release
marker fail before staging.

Each archive also contains an exhaustive `PACKAGE-MANIFEST.sha256` used by the
v2 updater. The updater requires exact canonical identity before network use,
queries the published releases collection rather than the repository-global
latest release, includes prereleases, ignores drafts, v1 and malformed tags,
selects the newest supported v2 semantic version, and refuses downgrades. It
then validates the archive and complete extracted inventory, preserves
`workspace/` and unknown unmanaged files, replaces only the installed
manifest's managed layer, and restores it after an injected installation
failure. Linux executes this transaction in automated tests; the native
PowerShell success, prerelease selection, downgrade, and injected-rollback
transactions are also exercised when `WORLD_BUILDER_PWSH` names a PowerShell
runtime. Those tests can run cross-platform. Native Windows execution remains
a release-validation expectation; the first alpha's explicitly accepted
limitation is recorded in its validation record.

Before publishing, build from clean published manager `main` and the exact
clean pinned Core revision with redistribution-ready JREs, then:

1. Verify both archives and `SHA256SUMS.txt` from outside either source tree.
2. Exercise first launch, reopen, and isolated layered authoring on Linux x64
   and Windows x64 without Git, Ant, source code, or a system JDK.
3. Verify save/export/import/undo against a disposable compatible private
   server that is offline during import and undo.
4. Exercise a real v2-to-v2 update on both platforms and confirm the complete
   workspace survives byte-for-byte.
5. Inject or simulate an installation failure on both platforms and verify the
   prior application plus workspace are restored and usable.
6. Confirm a frozen v1 install cannot discover the v2 tag and a v2 install
   refuses v1, malformed, wrong-identity, downgrade, and legacy-workspace
   inputs.
7. Record owner visual acceptance, remaining limitations, exact artifact
   hashes, runtime sources, commands, and results before opening the gate.

Publishing a World Editor release does not authorize changing or restarting a
public Spoiled Milk server.
