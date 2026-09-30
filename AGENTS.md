# RSC World Editor AI Collaboration Rules

RSC World Editor is one product managed across two independent repositories:
this World Editor repository and the separate `rsc-world-editor-runtime`
provider. The World Editor manager is the product-level manager. It owns task
routing, scope changes, delegation, integration, dependency adoption,
verification, publication, and releases for the product while preserving the
repository boundary.

## Product direction: targeted map compatibility

Owner clarification (2026-09-30): **Upgrade Target Runtime makes the target
compatible with World Builder's map loader and world-building features.** It
must preserve unrelated custom content and gameplay. This supersedes earlier
instructions to adopt a complete Current Base/Advanced game composition merely
to obtain map compatibility.

- World Builder needs faithful visual representations and placement identities
  for buildable content. Its private authoring runtime need not reproduce the
  target's combat, dialogue, quests, item effects, or other custom gameplay.
- The target retains authority over existing NPC/item/object definitions,
  assets, scripts, interactions, plugins, and unrelated configuration. Captured
  or normalized editor presentation data is not authority to replace them.
- Upgrade only the loader, necessary server/client integration, map protocols,
  and dependencies demonstrably required for supported map features. Rebuilding
  a binary is allowed when its unrelated target customizations are retained;
  substituting a generic whole-game binary is not proof of preservation.
- Automatically migrate supported map-related data, including placements,
  coordinates, floors, spawn bounds, and known map references. Preserve content
  identity. Unknown coordinate consumers require an explicit unresolved report;
  do not guess at arbitrary custom scripts or claim they were migrated.
- Import Map Changes is a separate map-only transaction after compatibility is
  verified. New floor/material definitions or other necessary map metadata must
  be explicit, collision-safe additions; unrelated content is not overwritten.
- One maintained map capability contract and bounded input adapters remain
  desirable. They do not require users to adopt World Builder's gameplay or
  port unrelated custom content into provider-owned modules.
- If the architecture cannot perform a targeted upgrade while preserving
  custom behavior, improve the integration architecture. A preserved backup or
  unchanged asset file does not prove the active game still uses custom code.
- Preview, offline checks, exact backups, verification, interrupted recovery,
  and rollback remain mandatory. Unknown compatibility must be resolved or
  reported before mutation, without claiming a successful upgrade.

This direction preserves the independent-repository and live-target boundaries
below. Older composition plans are historical wherever they conflict with this
scope. See [the targeted upgrade acceptance contract](docs/WORLD-BUILDER-TARGETED-MAP-UPGRADES.md).

Before changing anything, identify the checkout role and run its matching
preflight.

The manager at `/home/justin/rsc-world-editor` runs:

```bash
git status --short --branch
./scripts/ai-manager.sh status
```

An optional Editor worker at `/home/justin/rsc-world-editor-ai-1` through
`-ai-3` runs:

```bash
git status --short --branch
./scripts/ai-workspace.sh status
```

## Roles and default staffing

- `/home/justin/rsc-world-editor` is the product and World Editor manager. It
  owns Editor `main`, integration, dependency selection, final verification,
  publication, and releases. It may directly perform repository management,
  documentation, dependency integration, and small localized Editor changes.
  Meaningful feature work uses a short-lived topic branch or an optional
  Editor worker so `main` remains a stable integration branch.
- `/home/justin/rsc-world-editor-ai-1` is the normal optional Editor worker for
  substantial tooling, UI, project, import/export, updater, or packaging work.
- Editor `ai-2` and `ai-3` are dormant overflow/review slots. Their existence
  does not require their use, monitoring, or activation.
- `/home/justin/rsc-world-editor-runtime` and its workers are the independent
  runtime-provider implementation team. Runtime `ai-1` is the normal runtime
  worker; runtime `ai-2` and `ai-3` are optional overflow slots.
- `.runtime-provider/` is a disposable detached checkout at the exact revision
  in `runtime-provider.lock`. It is never a development worktree, manager
  checkout, worker slot, or source of collaboration instructions.

## Product-manager authority

A user assignment to implement, fix, document, validate, or release a World
Builder objective authorizes the product manager to perform the normal steps
needed to complete that objective across both independent repositories:

1. classify Editor-owned and runtime-owned work;
2. create or revise worker prompts and coherent umbrella scopes;
3. activate, follow up with, review, merge, test, publish, and recycle the
   appropriate Editor or runtime workers;
4. publish a tested runtime `main` commit and advance `runtime-provider.lock`
   to that exact commit when runtime integration is part of the assigned
   objective;
5. run parity and risk-appropriate Editor verification, integrate Editor work,
   and publish tested Editor `main`; and
6. adjust sequencing or subtract superseded details when the user changes the
   active objective.

The user does not need to relay correction prompts, handoff SHAs, or a separate
lock-advance authorization for those in-scope steps. A new authorization is
required only for a materially different product objective, destructive
history/data operations, mutation of a real user target or live server,
deployment, or a release/tag/upload that the user has not already requested.

An active worker assignment is a coherent umbrella, not a frozen specification.
Related details may be added, removed, or corrected on the same branch. Split
the work only when it becomes genuinely unrelated, independently releasable,
or unsafe to review as one diff. Checkpoint during iteration; mark READY only
when manager review is useful.

## Project independence

- This product workflow manages only `rsc-world-editor`,
  `rsc-world-editor-runtime`, their `origin` remotes, and their registered
  worktrees.
- Never inspect, summarize, coordinate, merge, recycle, or report the branches,
  workers, releases, pull requests, deployments, live-server state, or user
  data of `/home/justin/Core-Framework` or another Spoiled Milk checkout.
- An owner-designated disposable/reference input copy may be inspected read-only
  only when the owner explicitly places it in scope for target migration
  evidence. It is not managed or modified by this workflow: follow its local
  contributor instructions, never invoke its manager/worker collaboration,
  never build/launch it, and exclude all ignored/untracked state, credentials,
  databases, logs, artifacts, and user content from fixtures and reports. The
  currently authorized reference is `/home/justin/Core-Framework (copy)`.
- Never activate, inspect, or collect `/home/justin/Core-Framework-ai-*` for a
  World Editor task. Never route a runtime correction through Core.
- Never run collaboration scripts inside `.runtime-provider` or follow its
  nested `AGENTS.md`. Runtime collaboration commands run only from the
  registered runtime manager or runtime worker checkout.
- Collaboration scripts must continue rejecting cross-project invocation; do
  not bypass that boundary by overriding workflow paths.
- Activity in Spoiled Milk does not create a World Editor task and is never
  monitored automatically.

## Independent runtime boundary

- Client/server runtime behavior belongs to `rsc-world-editor-runtime`; Editor
  tooling, projects, import/export, packaging, updates, release gates, and
  end-user documentation belong here.
- The product manager may open the runtime manager checkout, follow its local
  instructions, coordinate its workers, review and publish runtime work, and
  then adopt the exact published commit here. That authority does not combine
  the Git histories or permit development inside `.runtime-provider`.
- `runtime-provider.lock` remains an exact immutable build input between
  integrations. It advances only to a clean tested runtime commit published on
  runtime `refs/heads/main`, as part of an assigned cross-repository objective
  or a specifically requested dependency update.
- Never copy the complete client/server into this repository or develop
  runtime changes against Core or another unpinned checkout.
- Preserve the frozen v1 release line; product identities, update channels,
  workspaces, and artifacts must not cross-update.

## Editor worker rules

1. One coherent umbrella and one descriptive topic branch per slot. Never work
   on `main` or detached `HEAD`.
2. The manager may revise the umbrella while it remains coherent; the worker
   incorporates follow-up details without demanding a new authorization.
3. Checkpoint meaningful progress with
   `./scripts/ai-workspace.sh checkpoint -m "message"`. This commits and pushes
   tracked and untracked project files after safety inspection.
4. Hand off only the exact tested review tip with
   `./scripts/ai-workspace.sh handoff -m "message"`.
5. Report changed files, tests, untested behavior, risks, and the exact pushed
   SHA. Workers do not merge, publish `main`, advance the runtime lock, release,
   deploy, or touch a live target.

## Manager rules

1. Keep manager `main` clean and published except during deliberate integration
   or small localized manager-owned work. Use a short-lived manager topic
   branch when a direct change is meaningful enough to benefit from isolation.
2. Run the matching status command before collection or cross-repository
   integration. The manager—not the user—handles prompts, follow-ups, READY
   inspection, exact diffs, merging, publication, and recycling.
3. Rescue unique or dirty work before doing anything that could overwrite or
   discard it.
4. Merge worker work only from an exact clean pushed READY tip after reviewing
   the complete diff. Direct manager work does not require a synthetic worker
   handoff but receives the same review and verification discipline.
5. Verification follows `docs/TESTING-POLICY.md`. Declare scope and expected
   duration before testing. UI-only changes use focused presentation checks
   and actual visual/input acceptance, not full suites. Data/schema/transaction
   changes require the affected safety suites. Broad integration and production
   releases require full suites. Neither a runtime-lock change nor an owner-test
   candidate alone triggers full testing: review the actual implementation diff.
   Explain and obtain owner agreement before expanding a small UI task to broad
   verification. Use focused workflow tests for testing-policy/tooling changes.
6. Push tested `main`, then recycle a merged slot only after its exact tip is
   contained in published `main`.
7. Release production remains separately guarded: clean published `main`, the
   exact locked runtime, a version-bound accepted release gate, full tests, and
   fresh production archives. A current-platform release gate binds every
   shipped variant/module composition and required input-adapter matrix row.
   It proves Base's positive public gameplay/state contract as well as exclusion
   of Advanced-only effects. Candidate archives are never promoted in place.
   After publication, consume/remove the gate on development `main`; the release
   tag retains the historical gate and validation record.

## Preservation rules

- Never use `git stash`, `git clean`, `git reset --hard`, forced checkout,
  forced branch deletion, or forced worktree removal as routine workflow.
- Never delete a dirty slot. Rescue and push it first.
- Never run two AI sessions in the same worktree.
- Never commit a user workspace, map export, backup, receipt, credential,
  database, log, PID, downloaded runtime, or built release archive.
- Never replace or delete an existing user workspace during an update or test;
  tests use temporary fixtures.
- Import and rollback retain offline-target, preview, exact confirmation,
  backup, verification, automatic failure rollback, interrupted Recovery,
  historical reversal evidence, and no-force safety contracts. No current
  end-user completed-import Undo surface is implied.

The complete workflow is documented in
[`docs/AI-WORKSPACES.md`](docs/AI-WORKSPACES.md).
