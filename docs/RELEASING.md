# Releasing

The packed-map v1 line is frozen at standalone release `v1.1.0`. Its existing
`release/world-builder/` assets and `scripts/package-release.sh` remain for
provenance, reproduction, and deliberate maintenance analysis; they are not an
active release channel.

World Builder 2 is the active development generation. It has a distinct
product/update identity (`rsc-world-editor-v2`), install folder, signed-layered
workspace, and `release/world-builder-v2/` package assets. Public v2 packaging
remains fail-closed pending final real-archive cross-platform validation and
owner acceptance. The dedicated packager and workspace-preserving v2 updater
are implemented and tested without enabling publication.

## Release readiness

Repository-level readiness can still be audited:

```bash
./scripts/ai-manager.sh release-check
./scripts/test.sh
./scripts/check-core-parity.sh /path/to/clean-pinned-core-framework
```

`./scripts/ai-manager.sh release` intentionally refuses publication while the
v2 packaging gate remains closed. `release/world-builder-v2/RELEASE-READY` is
deliberately absent. Do not add it or enable the manager release command until
the final validation below is recorded and accepted.

The v2 packager is:

```bash
./scripts/package-world-builder-v2-release.sh \
  --version v0.1.0-alpha.1 \
  --core-framework /path/to/clean-pinned-core-framework \
  --linux-jre /path/to/temurin-17-linux-x64-jre \
  --windows-jre /path/to/temurin-17-windows-x64-jre \
  --assets-cleared
```

It remains production-locked without the acceptance marker. Its restricted
`--skip-build` path requires
`SPOILED_MILK_WORLD_BUILDER_V2_RELEASE_TEST_MODE=1` and exists only for
deterministic temporary-fixture tests; it is not release authorization.

The packager requires:

- clean, already-published `main` in this repository;
- a clean checkout at the exact commit in `core-framework.lock`;
- Linux and Windows JRE 17+ inputs;
- confirmed redistribution terms for every packaged asset;
- a reviewed signed-layered package;
- exact v2 product/update identity, v2-prefixed tag/archive names, and
  self-only update eligibility;
- provenance containing both repository commits; and
- archives containing no workspace, credentials, databases, logs, backups,
  receipts, generated endpoints, or other user state.

Each archive also contains an exhaustive `PACKAGE-MANIFEST.sha256` used by the
v2 updater. The updater requires exact canonical identity before network use,
refuses v1 tags and downgrades, validates the archive and complete extracted
inventory, preserves `workspace/` and unknown unmanaged files, replaces only
the installed manifest's managed layer, and restores it after an injected
installation failure. Linux executes this transaction in automated tests;
the equivalent Windows PowerShell implementation still requires the final
Windows host validation below.

Before enabling publication, build from clean published manager `main` and the
exact clean pinned Core revision with redistribution-ready JREs, then:

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
