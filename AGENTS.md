# RSC World Editor Collaboration Rules

This repository owns the standalone World Builder tooling, package assets,
tests, documentation, and release channel. The integrated editor and playable
client/server runtime remain coupled to Spoiled Milk through
`core-framework.lock`.

Before changing anything, run:

```bash
git status --short --branch
./scripts/test.sh
```

## Source boundaries

- `tools/world-builder/` and `release/world-builder/` are synchronized with
  the matching paths in the pinned Spoiled Milk revision.
- Do not copy the entire client or server into this repository. Runtime parity
  is maintained by advancing `core-framework.lock` and building against that
  exact revision.
- Changes that require client or server implementation must land in Spoiled
  Milk first, then be synchronized here after publication.
- Standalone-only tooling, repository documentation, CI, and release
  orchestration may be changed directly here.

## Preservation rules

- Never commit a user `workspace/`, map export, backup, receipt, credential,
  database, log, PID, downloaded runtime, or built release archive.
- Never replace or delete an existing user workspace as part of an update or
  test. Tests use temporary fixtures.
- Import and rollback must retain their offline-target, preview, exact
  confirmation, backup, verification, and no-force safety contracts.
- A release updater must treat user workspace state as durable data and the
  packaged runtime as replaceable application data.

## Verification and releases

- Run `./scripts/test.sh` for every change.
- Run `./scripts/check-core-parity.sh <core-checkout>` whenever synchronized
  source or package assets change.
- Release builds must use the exact clean commit in `core-framework.lock` and
  record both repository revisions in their provenance files.
- Tag and publish only from a clean `main` that is already pushed to `origin`.
