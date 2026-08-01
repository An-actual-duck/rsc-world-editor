# Manager and Worker AI Workflow

RSC World Editor separates stable folders from temporary task branches. A
folder is an AI seat, not a category of work.

```text
/home/justin/rsc-world-editor       manager AI; main only
/home/justin/rsc-world-editor-ai-1  neutral worker slot
/home/justin/rsc-world-editor-ai-2  neutral worker slot
/home/justin/rsc-world-editor-ai-3  neutral worker slot
```

The standalone repository is managed independently from Spoiled Milk. It still
pins Spoiled Milk as a source dependency: runtime changes land there first,
then this manager advances `core-framework.lock` and synchronizes the bounded
World Builder sources.

## First-time setup

From the manager checkout:

```bash
./scripts/ai-workspace.sh init 3
./scripts/ai-manager.sh status
```

The command creates three persistent detached worktrees beside the manager
checkout. Idle slots may lag behind `main`; starting a task always branches
from the current fetched `origin/main`.

## Normal task cycle

The manager activates one slot with a descriptive topic branch:

```bash
./scripts/ai-workspace.sh start ai-1 feat/example-task
```

Open `/home/justin/rsc-world-editor-ai-1` in the worker AI session. The worker
can plan, discuss, implement, and test normally. The scripts manage Git state;
they do not operate the AI.

At useful milestones the worker commits and pushes a durable checkpoint:

```bash
./scripts/ai-workspace.sh checkpoint -m "Checkpoint example task"
```

When the exact commit is complete and tested:

```bash
./scripts/ai-workspace.sh handoff -m "Finish example task"
```

The manager reviews the complete diff and integrates only the recorded READY
tip:

```bash
./scripts/ai-manager.sh status
git log --oneline main..feat/example-task
git diff main...feat/example-task
./scripts/ai-manager.sh merge feat/example-task
./scripts/test.sh
git push origin main
./scripts/ai-workspace.sh recycle ai-1
```

Recycling is allowed only after the exact handoff commit is contained in both
local and published `main`. It removes the completed temporary branch and
returns the folder to detached IDLE state.

## Long-running and conversational work

A worker does not need a new authorization for every related question,
iteration, or private test. Keep the slot ACTIVE while the work remains under
one coherent umbrella. Checkpoint repeatedly and create a READY handoff only
when manager review is useful.

A new branch is needed when the assignment materially changes, when independent
review/release boundaries matter, or when the existing work has been merged.

## Recovery

If a worker session closes or leaves confusing state, preserve it before
cleanup:

```bash
./scripts/ai-manager.sh rescue ai-2 -m "Rescue abandoned work"
```

Rescue creates a named branch when necessary, commits tracked and untracked
files, and pushes the exact result. Do not use stash, hard reset, clean, or
worktree deletion to make the problem disappear.

## External pull requests

External contributors work in their own clones and username-namespaced topic
branches. After confirming the pull request's exact full commit and remote
branch, the manager can import it into an idle slot without merging:

```bash
./scripts/ai-manager.sh collect-contributor ai-3 username/fix/example FULL_40_CHARACTER_COMMIT
```

Review and test the collected branch like an internal handoff. Collection
never grants the contributor authority over `main`, releases, or the pinned
Spoiled Milk dependency.

## Source synchronization

Runtime-integrated editor work follows a two-repository order:

1. Develop, review, test, merge, and publish the complete change in Spoiled
   Milk.
2. In this manager checkout, synchronize from that exact clean published
   Spoiled Milk commit.
3. Review the bounded diff, update `core-framework.lock`, run
   `./scripts/check-core-parity.sh`, and run `./scripts/test.sh`.
4. Publish this repository's tested `main`.

Do not transplant a Spoiled Milk topic branch into this repository as though
the repositories shared task history. The published commit is a versioned
source input; this repository retains its own history and release identity.

## Release boundary

```bash
./scripts/ai-manager.sh release-check
```

The manager gate requires clean published `main`, no ambiguous worktrees or
stashes, and exact remote backups for active work. The legacy v1.1.0 line is
frozen, and `ai-manager.sh release` intentionally refuses publication until
the separate v2 packager/updater and final release validation are complete.
Once enabled, packaging must enforce the pinned clean Core-Framework revision
and record both repository commits in release provenance.
