# Development

## Prerequisites

- Git
- Bash
- Python 3
- JDK 17 or newer (`java`, `javac`, and `jar` on `PATH`)

## Build and test

Build the standalone Java tooling:

```bash
./scripts/build-tools.sh
```

Run the complete repository test suite:

```bash
./scripts/test.sh
```

Tests create temporary server layouts, UUID project registries, workspaces,
conversion outputs, and fake isolated runtimes. They must not use an installed
Builder, a user project, or a real private-server directory. Adaptive runtime
tests must keep generated credentials, settings, logs, PIDs, and
`server/ipbans.txt` inside their temporary project fixture.

## Core-Framework dependency

`core-framework.lock` is the sole runtime dependency pin. Treat it as a frozen
external build input during ordinary development. It is not a signal to check
Spoiled Milk status, branches, workers, releases, or newer commits.

A local checkout can be created at the ignored `.core-framework/` path when a
build or explicitly assigned dependency audit requires it:

```bash
./scripts/checkout-core-framework.sh
```

For an explicitly assigned dependency-update task, verify that it is the
expected revision and that the bounded synchronization inputs match:

```bash
./scripts/check-core-parity.sh .core-framework
```

Do not run collaboration scripts inside that checkout or follow its nested
`AGENTS.md`; it belongs to another project. Fetching a locked object for a
build does not authorize updating the lock. Ordinary repository-owned tooling,
packaging, test, and documentation fixes do not require source parity with the
frozen dependency.

Only when the user explicitly assigns a dependency-update task, incorporate
the exact external commit they selected with:

```bash
./scripts/sync-from-core-framework.sh /path/to/open-rsc-spoiled-milk
./scripts/test.sh
git diff --check
```

Review the synchronized diff before committing. The sync command refuses dirty
source paths and updates the lock only after copying `tools/world-builder/`
and `release/world-builder-v2/`. The frozen legacy
`release/world-builder/` tree is never overwritten by synchronization.

## Change routing

- Standalone World Editor tooling, tests, documentation, packaging, updater,
  CI, and release-channel work belong here and use this repository's workers.
- If a feature needs client/server behavior not owned here, record it as an
  external compatibility dependency rather than assuming control of Spoiled
  Milk development.
- Shared-source synchronization occurs only through an explicitly assigned,
  exact-commit dependency update. It is never triggered by another project's
  activity.
