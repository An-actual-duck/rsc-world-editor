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

Tests create temporary server layouts and workspaces. They must not use an
installed Builder or a real private-server directory.

## Core-Framework dependency

`core-framework.lock` is the sole runtime dependency pin. A local checkout can
be created at the ignored `.core-framework/` path:

```bash
./scripts/checkout-core-framework.sh
```

Verify that it is the expected revision and that synchronized files match:

```bash
./scripts/check-core-parity.sh .core-framework
```

To incorporate a published editor or packaging change from Spoiled Milk:

```bash
./scripts/sync-from-core-framework.sh /path/to/open-rsc-spoiled-milk
./scripts/test.sh
git diff --check
```

Review the synchronized diff before committing. The sync command refuses dirty
source paths and updates the lock only after copying the two bounded directory
trees.

## Change routing

- Client/server/editor behavior changes begin in Spoiled Milk because they need
  the full runtime and regression suite.
- Standalone project tooling shared with Spoiled Milk should be synchronized in
  both repositories at the same published revision.
- Repository CI, release-channel documentation, and build orchestration belong
  here.
