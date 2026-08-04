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

Tests create temporary server layouts, packed and layered inputs, standalone
empty origins, UUID project registries, historical workspaces, conversion
outputs, release archives, and fake isolated runtimes. They must not use an
installed Builder, a user project, or a real private-server directory. Adaptive
runtime tests keep generated credentials, settings, logs, PIDs, and
`server/ipbans.txt` inside their temporary project fixture.

Phase 5 packaging fixtures must prove the exact runtime/default-catalog
allowlist and inject renamed terrain, layered manifests, placement data, and a
nonempty database placement or user/operational table. Updater fixtures use
multiple projects, registry/selection state, unknown paths, historical
`workspace/`, and injected
installation/compatibility/rollback failures. Every durable byte is compared
before and after. Linux and PowerShell implement the same contract; native
PowerShell execution is run when `WORLD_BUILDER_PWSH` is available, with static
contract coverage always required.

## Core-Framework dependency

`core-framework.lock` is the sole runtime dependency pin. Treat its exact
commit and durable provider ref as a frozen external build input during
ordinary development. The provider ref exists only to keep that exact object
fetchable; it is not a signal to check Spoiled Milk status, branches, workers,
releases, or newer commits.

A local checkout can be created at the ignored `.core-framework/` path when a
build or explicitly assigned dependency audit requires it:

```bash
./scripts/checkout-core-framework.sh
```

For an explicitly assigned dependency-update task, verify that it is the
expected revision and that its adaptive capability and protocol match:

```bash
./scripts/check-core-parity.sh .core-framework
```

Do not run collaboration scripts inside that checkout or follow its nested
`AGENTS.md`; it belongs to another project. Fetching a locked object for a
build does not authorize updating the lock. Ordinary repository-owned tooling,
packaging, test, and documentation fixes do not require source parity with the
frozen dependency.

Only when the user explicitly assigns a dependency-update task, adopt the
exact external commit and durable runtime provider ref they selected with:

```bash
./scripts/sync-from-core-framework.sh \
  /path/to/clean-runtime-provider \
  refs/heads/world-builder/runtime/name
./scripts/checkout-core-framework.sh
./scripts/check-core-parity.sh .core-framework
./scripts/test.sh
git diff --check
```

Review the dependency and protocol diff before committing. The adoption
command refuses dirty providers, requires the exact commit at the named remote
ref, and updates only `core-framework.lock` plus the v2 runtime protocol. It
never copies World Builder-owned tooling, templates, or either release line.

## Change routing

- Standalone World Editor tooling, tests, documentation, packaging, updater,
  CI, and release-channel work belong here and use this repository's workers.
- If a feature needs client/server behavior not owned here, record it as an
  external compatibility dependency rather than assuming control of Spoiled
  Milk development.
- Runtime adoption occurs only through an explicitly assigned exact commit and
  durable provider ref. It never copies shared source and is never triggered by
  another project's activity.
