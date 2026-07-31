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
  integrates completed work, advances the pinned Spoiled Milk source,
  performs final verification, publishes `main`, and builds releases. Do not
  use it for ordinary feature implementation.
- `/home/justin/rsc-world-editor-ai-1` through `-ai-3` are reusable neutral
  worker slots. A worker may edit only after the manager starts a focused topic
  branch in that slot.
- `.core-framework/` is a disposable, detached dependency checkout at the
  exact revision in `core-framework.lock`. It is not a development worktree.

## Source boundaries

- This repository owns standalone World Builder tooling, package assets,
  tests, documentation, automatic updates, and its release channel.
- Integrated client, server, map-loader, and in-game editor changes must land
  in Spoiled Milk first. After that commit is published, advance
  `core-framework.lock` and synchronize only the explicitly owned paths.
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
6. Workers do not merge other tasks, advance `core-framework.lock`, publish
   `main`, tag releases, or upload release assets.

## Manager rules

1. Keep the manager checkout on clean `main` except for deliberate
   integration, source synchronization, or repository-management work.
2. Begin collection with `./scripts/ai-manager.sh status`.
3. If a session disappeared with unique or dirty work, run
   `./scripts/ai-manager.sh rescue <slot> -m "message"` before doing anything
   else to that slot.
4. Inspect the complete branch diff, then merge only an exact READY handoff
   with `./scripts/ai-manager.sh merge <branch>`.
5. Run `./scripts/test.sh` and, for synchronized sources,
   `./scripts/check-core-parity.sh <clean-pinned-core-checkout>` before
   publishing.
6. Push tested `main` to `origin`, then recycle a merged slot with
   `./scripts/ai-workspace.sh recycle <slot>`. Recycling must refuse any
   branch not contained in published `main`.
7. Run `./scripts/ai-manager.sh release-check` before packaging. Releases
   must come from clean, already-published `main` and the exact clean commit
   named by `core-framework.lock`.

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
