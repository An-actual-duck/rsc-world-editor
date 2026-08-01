# Releasing

The packed-map v1 line is frozen at standalone release `v1.1.0`. Its existing
`release/world-builder/` assets and `scripts/package-release.sh` remain for
provenance, reproduction, and deliberate maintenance analysis; they are not an
active release channel.

World Builder 2 is the active development generation. It has a distinct
product/update identity (`rsc-world-editor-v2`), install folder, signed-layered
workspace, and `release/world-builder-v2/` package assets. Public v2 packaging
remains fail-closed until layered export/import, a workspace-preserving v2
updater, and final cross-platform release validation are complete.

## Release readiness

Repository-level readiness can still be audited:

```bash
./scripts/ai-manager.sh release-check
./scripts/test.sh
./scripts/check-core-parity.sh /path/to/clean-pinned-core-framework
```

`./scripts/ai-manager.sh release` intentionally refuses publication while the
v2 packaging gate remains closed. The future v2 packager must require:

- clean, already-published `main` in this repository;
- a clean checkout at the exact commit in `core-framework.lock`;
- Linux and Windows JRE 17+ inputs;
- confirmed redistribution terms for every packaged asset;
- a reviewed signed-layered package;
- exact product/update identity and self-only update eligibility;
- provenance containing both repository commits; and
- archives containing no workspace, credentials, databases, logs, backups,
  receipts, generated endpoints, or other user state.

Before enabling publication, extract and validate both platform archives,
exercise first launch and isolated authoring, verify export/import/undo against
a disposable offline private server, and add automated package/updater tests.

Publishing a World Editor release does not authorize changing or restarting a
public Spoiled Milk server.
