# Product manager and optional worker workflow

RSC World Editor uses one product manager across two independent repositories.
Repository separation protects provenance and release boundaries; it does not
require the owner to act as a courier between two AI management layers.

```text
/home/justin/rsc-world-editor               product/Editor manager
/home/justin/rsc-world-editor-ai-1          optional Editor implementation worker
/home/justin/rsc-world-editor-ai-2..-ai-3   dormant Editor overflow/review slots

/home/justin/rsc-world-editor-runtime       independent runtime manager
/home/justin/rsc-world-editor-runtime-ai-1  normal runtime implementation worker
/home/justin/rsc-world-editor-runtime-ai-2..-ai-3 optional runtime overflow slots
```

Core-Framework and Spoiled Milk are outside this workflow. Do not inspect or
operate their managers, workers, branches, releases, deployments, or live data.
The ignored `.runtime-provider/` directory is only the exact detached build
dependency recorded by `runtime-provider.lock`; never run collaboration tools
inside it.

## Normal interaction

The owner describes the desired outcome and may freely add, remove, or revise
details while work is active. The product manager:

1. classifies each change as Editor-owned, runtime-owned, or cross-repository;
2. handles small Editor changes directly or assigns substantial Editor work to
   Editor AI-1;
3. assigns client/server work to runtime AI-1 through the runtime manager;
4. follows up with workers as the scope evolves;
5. reviews exact READY handoffs, runs appropriate tests, merges, publishes, and
   recycles the slots; and
6. adopts an in-scope published runtime commit into the Editor lock without
   requiring the owner to relay a SHA or issue a second authorization.

READY is a review state, not a prohibition on further changes. If the owner
adds a related requirement after handoff, the manager reactivates or follows up
on the same coherent task. A new branch is needed only for unrelated work, an
independent release boundary, or a diff that would become unsafe to review.

## Editor implementation choices

Small documentation, repository-management, dependency-integration, and
localized Editor changes may be completed by the manager. Meaningful direct
implementation uses a short-lived manager topic branch when isolation is
helpful.

Use Editor AI-1 when work is substantial enough that independent implementation
and manager review add value, especially for UI, project schemas, discovery,
conversion, import/export, updater, or packaging changes. AI-2 and AI-3 are
overflow capacity, not required participants. Leaving them detached and IDLE
is the normal state.

To assign an Editor worker:

```bash
./scripts/ai-workspace.sh start ai-1 feat/descriptive-task
```

The worker checkpoints during iteration and hands off once:

```bash
./scripts/ai-workspace.sh checkpoint -m "Checkpoint coherent task"
./scripts/ai-workspace.sh handoff -m "Finish coherent task"
```

The manager reviews and completes integration:

```bash
./scripts/ai-manager.sh status
git diff main...feat/descriptive-task
./scripts/ai-manager.sh merge feat/descriptive-task
./scripts/test.sh
git push origin main
./scripts/ai-workspace.sh recycle ai-1
```

## Cross-repository runtime work

Runtime implementation remains in the independent runtime repository. The
product manager may operate its manager and workers from their correct
registered worktrees; it never edits `.runtime-provider` as source and never
imports Core.

One cross-repository feature assignment normally authorizes this entire cycle:

1. activate runtime AI-1 on a descriptive branch;
2. revise its prompt as related details change;
3. inspect and test the exact READY handoff;
4. merge and publish runtime `main`;
5. select that exact published commit in `runtime-provider.lock`;
6. materialize the detached dependency, run parity and the Editor full suite;
7. commit and publish the bounded Editor integration; and
8. continue or merge any associated Editor-owned feature work.

The owner need not provide a correction prompt, relay the commit hash, or say
“advance the lock” between those steps. The manager reports the resulting exact
runtime and Editor commits. A separate instruction is needed only when the
runtime update is unrelated to the assigned objective.

The combined status and adoption helper is:

```bash
./scripts/product-manager.sh status
./scripts/product-manager.sh adopt-runtime
```

`adopt-runtime` selects only the clean published runtime manager `main`, updates
only the lock/protocol inputs, materializes the lock, runs parity and the full
Editor suite, then commits and publishes the bounded integration. An optional
full SHA may be supplied as an additional guard. It refuses an open release
gate or unrelated dirty Editor state.

## Recovery and exact handoffs

One AI session per worktree remains absolute. Most historical friction came
from two sessions editing the same AI-1 folder, not from the repository split.
If a session disappears or ownership becomes unclear, stop writers and rescue
before cleanup:

```bash
./scripts/ai-manager.sh rescue ai-1 -m "Rescue abandoned work"
```

Never use stash, hard reset, clean, forced checkout, or worktree deletion to
erase ambiguous work. Exact READY commits and remote checkpoints remain useful
because they make ownership and review deterministic; they no longer require
the owner to manually shuttle information between managers.

## Risk-based verification

- Documentation and narrow low-risk changes: focused tests plus
  `git diff --check` may be sufficient.
- Behavioral features: relevant focused suites plus the full repository suite
  before publishing.
- Runtime-lock, schema, transaction, import/export, updater, packaging, or broad
  integration changes: parity where applicable and the full suite.
- Candidate acceptance and releases: every documented release check, full
  suite, owner validation where required, and fresh production artifacts.

The manager reports what ran, what was unavailable, and any accepted limits.

## Release gates

An accepted gate is version-bound and valid only at the exact published commit
that records it. The production packager validates its schema, release version,
runtime commit, validation record, and Git gate commit. Candidate archives are
evidence only and are never promoted.

After the release is published, remove `release/world-builder-v2/RELEASE-READY`
from development `main`. The immutable release tag retains the gate and
accepted record; later feature work begins with a closed gate and cannot reuse
the prior acceptance.

Release/tag/upload, deployment, real user-target mutation, live-server action,
and destructive data/history operations remain explicit authorization
boundaries unless already requested in the active objective.
