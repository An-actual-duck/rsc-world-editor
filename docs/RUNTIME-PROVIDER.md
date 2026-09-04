# Independent runtime provider

World Builder's embedded client/server runtime is developed independently from
Spoiled Milk in
[`An-actual-duck/rsc-world-editor-runtime`](https://github.com/An-actual-duck/rsc-world-editor-runtime).
This repository consumes one exact published provider commit through
`runtime-provider.lock`; it never watches or imports Core-Framework branches.

## Planned current platform, variants, modules, and adapters

The replacement design assigns the provider one current
engine/API/protocol/schema generation and release train. A future exact locked
revision may publish a small set of current compositions while the public Editor
package remains free of target worlds and private user state, rather than
forcing one gameplay configuration on every server:

- **Current Base** is the conservative public composition for
  Preservation-like/lightly customized targets.
- **Current Advanced** is the reviewed Spoiled Milk composition built on the
  same platform generation.
- **Modules** are optional maintained code/data/server-client features with
  exact manifests, platform API requirements, dependencies/conflicts,
  migrations, provenance, and semantic tests.
- **Editor-owned input adapters** recognize and translate historical target
  layouts into a current composition; provider manifests and fixtures supply
  runtime-side identities, payload roles, and migration capabilities. Adapters
  are migration inputs, not installed runtimes.

No target map, private asset, credential, player/database state, or other user
content is embedded in a public World Builder archive. Base and Advanced need
not expose identical gameplay or client interfaces. Non-redistributable
Advanced inputs remain local target-derived modules/state and are never silently
promoted into the provider.

Every composition has identity `(platformReleaseId, platformManifestHash,
variantId, variantManifestHash, moduleSetHash, bundleInventoryHash)`, a closed
artifact inventory, and a matching server/client handshake. `moduleSetHash`
binds the canonical ordered module manifests and payload roots; the bundle hash
binds the resolved composition. Variants share the canonical map engine,
project and target-ledger contracts, upgrade engine, safety guarantees, and
release gates.
A same-generation Advanced build variant may temporarily carry behavior below
the extension boundary, but it must have an explicit path toward reusable
platform hooks/modules and cannot become a per-target fork.

Core-derived behavior is reviewed and deliberately ported into the provider;
the provider does not merge Core history, inspect live Core state, copy private
content, or load old target classes. Public Base releases must pass the positive
canonical public gameplay/state contract selected from Preservation-derived
fixtures and prove that Advanced-only gameplay, assets, UI, configuration, and
schema effects are absent. See [World Builder 2 Current Runtime Upgrade
Review](WORLD-BUILDER-2-CURRENT-RUNTIME-UPGRADE-REVIEW.md).

The exact provider currently locked by this repository does not yet implement
this composition/module contract; its pinned-core target-upgrade strategy is
rejected.

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
