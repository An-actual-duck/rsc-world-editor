# RSC World Editor AI Collaboration Rules

This repository uses one manager AI and up to three neutral worker AI
sessions. The directory identifies the role; the branch identifies the task.

Before changing anything, identify the session role and run its matching
preflight.

The manager at `/home/justin/rsc-world-editor` runs:

```bash
git status --short --branch
./scripts/ai-manager.sh status
```

A maintainer worker at `/home/justin/rsc-world-editor-ai-1` through
`-ai-3` runs:

```bash
git status --short --branch
./scripts/ai-workspace.sh status
```

## Roles

- `/home/justin/rsc-world-editor` is the manager checkout. It owns `main`,
  integrates completed World Editor work, performs final verification,
  publishes this repository's `main`, and builds this repository's releases.
  Do not use it for ordinary feature implementation.
- `/home/justin/rsc-world-editor-ai-1` through `-ai-3` are reusable neutral
  worker slots. A worker may edit only after the manager starts a focused topic
  branch in that slot.
- `.runtime-provider/` is a disposable, detached dependency checkout at the
  exact revision in `runtime-provider.lock`. It is not a development worktree,
  manager checkout, worker slot, or source of collaboration instructions.

## Project independence

- This manager and its workers manage only the `rsc-world-editor` Git
  repository, its `origin` remote, and `/home/justin/rsc-world-editor-ai-*`
  worktrees.
- Never inspect, summarize, coordinate, merge, recycle, or report the branches,
  worktrees, worker state, releases, pull requests, or live-server state of
  `/home/justin/Core-Framework` or another Spoiled Milk checkout as part of
  routine World Editor work.
- Never run `.runtime-provider/scripts/ai-manager.sh`,
  `.runtime-provider/scripts/ai-workspace.sh`, or follow
  `.runtime-provider/AGENTS.md`. Those belong to a different project and a
  different manager/worker team.
- Collaboration scripts reject callers whose current directory is outside a
  registered `rsc-world-editor` worktree. Do not bypass that boundary by
  changing directories or overriding workflow paths.
- Activity in Spoiled Milk—including a worker handoff, merge, release, or a
  newer upstream commit—does not create a World Editor task and must not be
  monitored automatically.
- `runtime-provider.lock` is an immutable compatibility input during ordinary
  development. Fetch, advance, or synchronize that dependency only when the
  user explicitly assigns a dependency-update task to the World Editor
  manager. Do not infer such permission from a status request, release request,
  related Spoiled Milk work, or the existence of a newer commit.

## Independent runtime provider

- World Builder client/server runtime work belongs to the separate
  `rsc-world-editor-runtime` repository, not Spoiled Milk/Core-Framework.
- Its manager is `/home/justin/rsc-world-editor-runtime`; its independent
  workers are `/home/justin/rsc-world-editor-runtime-ai-1` through `-ai-3`.
  Coordinate runtime work only by opening that manager checkout and following
  its own `AGENTS.md` and collaboration scripts.
- Never activate, inspect, or collect `/home/justin/Core-Framework-ai-*` for a
  World Editor task. Never route a runtime correction to the Core manager.
- This repository consumes only the exact published runtime commit recorded in
  `runtime-provider.lock`. A dependency update advances that lock only after
  the runtime manager publishes and reports an exact tested commit.

## Runtime dependency boundary

- This repository owns standalone World Builder tooling, package assets,
  tests, documentation, automatic updates, and its release channel.
- The locked runtime-provider checkout is an external runtime/build dependency,
  comparable to a pinned SDK. Use only the exact locked revision when a build,
  parity check, or explicitly assigned dependency update requires it.
- If a requested World Editor feature requires client/server functionality not
  owned here, report that external dependency clearly. Do not take over the
  Spoiled Milk project, its manager role, or its workers from this repository.
- Never copy the complete client or server into this repository or develop
  runtime changes against an unpinned Spoiled Milk checkout.
- Preserve the frozen legacy v1 release line when developing World Builder 2;
  product identities, update channels, workspaces, and release artifacts must
  not cross-update.

## Worker rules

1. One task and one topic branch per slot. Never work on `main` or detached
   `HEAD`.
2. Use a descriptive branch such as `fix/import-preview`,
   `feat/definition-browser`, or `docs/release-guide`; never name a branch
   after the slot.
3. Run `./scripts/ai-workspace.sh checkpoint -m "message"` at meaningful
   milestones and before the session may end. It commits tracked and untracked
   project files and pushes the same branch to `origin`.
4. Run `./scripts/ai-workspace.sh handoff -m "message"` only when the exact
   pushed commit is ready for manager review.
5. Report changed files, tests, untested behavior, known risks, and whether the
   handoff is READY.
6. Workers do not merge other tasks, inspect or manage Spoiled Milk work,
   advance `runtime-provider.lock`, publish `main`, tag releases, or upload
   release assets.

## Manager rules

1. Keep the manager checkout on clean `main` except for deliberate
   integration, explicitly assigned dependency updates, or
   repository-management work.
2. Begin collection with `./scripts/ai-manager.sh status`.
3. If a session disappeared with unique or dirty work, run
   `./scripts/ai-manager.sh rescue <slot> -m "message"` before doing anything
   else to that slot.
4. Inspect the complete branch diff, then merge only an exact READY handoff
   with `./scripts/ai-manager.sh merge <branch>`.
5. Run `./scripts/test.sh` before publishing. Run
   `./scripts/check-runtime-provider-parity.sh <clean-pinned-runtime-checkout>` only for an
   explicitly assigned dependency synchronization or a release that needs to
   verify the already-locked runtime.
6. Push tested `main` to `origin`, then recycle a merged slot with
   `./scripts/ai-workspace.sh recycle <slot>`. Recycling must refuse any
   branch not contained in published `main`.
7. Run `./scripts/ai-manager.sh release-check` before packaging. Releases must
   come from clean, already-published World Editor `main` and use the exact
   already-selected dependency commit named by `runtime-provider.lock`; release
   preparation does not authorize checking for or adopting a newer upstream
   revision. The legacy v1 line is frozen. World Builder 2 production packaging
   is enabled only while `release/world-builder-v2/RELEASE-READY` records an
   accepted validation gate; `ai-manager.sh release` delegates only to the v2
   packager. Before that gate exists, `ai-manager.sh candidate` is the sole
   exception: it may build real, restricted validation archives from clean
   published `main`, the exact clean locked dependency, and reviewed JRE inputs
   only under `output/candidates/`. It must refuse fixture builds and an open
   gate, and it must never create the gate, tag, upload, publish, deploy, or
   promote those archives. Production archives are rebuilt after acceptance.

## Preservation rules

- Never use `git stash`, `git clean`, `git reset --hard`, forced checkout,
  forced branch deletion, or forced worktree removal as routine workflow.
- Never delete a dirty slot. Rescue and push it first.
- Never commit a user `workspace/`, map export, backup, receipt, credential,
  database, log, PID, downloaded runtime, or built release archive.
- Never replace or delete an existing user workspace as part of an update or
  test. Tests use temporary fixtures.
- Import and rollback must retain their offline-target, preview, exact
  confirmation, backup, verification, and no-force safety contracts.
- Do not run two AI sessions in the same worktree.

The complete workflow is documented in
[`docs/AI-WORKSPACES.md`](docs/AI-WORKSPACES.md).
