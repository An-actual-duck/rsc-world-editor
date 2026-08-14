# Independent runtime provider

World Builder's embedded client/server runtime is developed independently from
Spoiled Milk in
[`An-actual-duck/rsc-world-editor-runtime`](https://github.com/An-actual-duck/rsc-world-editor-runtime).
This repository consumes one exact published provider commit through
`runtime-provider.lock`; it never watches or imports Core-Framework branches.

## Product-level coordination and local ownership

```text
/home/justin/rsc-world-editor-runtime       runtime manager; main only
/home/justin/rsc-world-editor-runtime-ai-1  runtime worker
/home/justin/rsc-world-editor-runtime-ai-2  runtime worker
/home/justin/rsc-world-editor-runtime-ai-3  runtime worker
```

The World Editor manager is the product-level manager and may coordinate this
runtime manager and its workers as a normal part of an assigned World Builder
objective. Runtime implementation still begins and ends in those worktrees
using that repository's `AGENTS.md` and collaboration scripts. The disposable
`.runtime-provider/` directory inside this repository is only a detached build
input. It must never be used as a manager or worker checkout.

## Handoff and adoption

1. The runtime manager assigns a focused topic branch to a runtime worker.
2. The worker checkpoints, tests, and produces an exact READY handoff.
3. The runtime manager reviews, tests, merges, and publishes runtime `main`.
4. When runtime integration is part of the active World Builder objective, the
   product manager advances `runtime-provider.lock` to that exact published SHA
   without requiring the owner to relay it or issue another prompt.
5. World Editor materializes the lock and runs parity and its full test suite.

```bash
./scripts/sync-from-runtime-provider.sh \
  /home/justin/rsc-world-editor-runtime \
  refs/heads/main
./scripts/checkout-runtime-provider.sh
./scripts/check-runtime-provider-parity.sh .runtime-provider
./scripts/test.sh
```

The manager can run this sequence with `./scripts/product-manager.sh
adopt-runtime`; an optional exact SHA acts as an additional guard. No step in
this flow activates, collects, merges, or inspects
`/home/justin/Core-Framework-ai-*`. Spoiled Milk releases, deployments, live
servers, and player data are outside both World Editor projects.
