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

Phase 6 transaction fixtures create disposable descriptor-backed layered and
packed targets. They verify deterministic complete export, preview
non-mutation, exact server/client installation, standalone refusal, free-space
and no-force preflight, ordered file/directory persistence refusal, exact
created-directory authority, complete fingerprint-container changed-after
refusal at both boundaries, historical undo after a valid later save,
explicit non-chainable successive imports, partial import and undo rollback,
appeared-path preservation, rollback-failure recovery, per-process unreadable
scan handling, lock identity replacement, and byte-exact undo. Failure
observers are package-local test hooks; production commands cannot request an
injected failure or bypass a check.

Phase 5 packaging fixtures must prove the exact runtime/default-catalog
allowlist and inject renamed terrain, layered manifests, placement data, and a
nonempty database placement or user/operational table. Updater fixtures use
multiple projects, registry/selection state, unknown paths, historical
`workspace/`, and injected
installation/compatibility/rollback failures. Every durable byte is compared
before and after. Linux and PowerShell implement the same contract; native
PowerShell execution is run when `WORLD_BUILDER_PWSH` is available, with static
contract coverage always required.

The Phase 7 focused release-candidate boundary is:

```bash
./scripts/test-world-builder-v2-candidate.sh
```

It groups the adaptive contracts/discovery/origins, packed conversion,
project lifecycle, Phase 6 transactions, content-neutral release, updater,
product-generation, independence, external-candidate-inspector fixtures, and
the opt-in exact-provider native startup proof. The native proof skips unless
the reviewed runtime and exact-provider inputs are explicit; the Java override
is optional when the reviewed `java` is already on `PATH`:

```bash
WORLD_BUILDER_NATIVE_RUNTIME_ROOT=/path/to/fresh/builder-runtime \
WORLD_BUILDER_EXACT_RUNTIME_PROVIDER=/path/to/clean-exact-locked-runtime-provider \
WORLD_BUILDER_NATIVE_JAVA=/path/to/reviewed-java \
python3 tests/myworld/test-world-builder-native-runtime-integration.py -v
```

The runtime allowlist must be the runtime directory's sibling. The proof uses
no editor UI: it creates a standalone-empty project, starts the real server,
waits for readiness, requests orderly shutdown, verifies the post-run save,
database migrations and project-local PEM generation, and proves the packaged
runtime, exact provider, and target outside the installation stayed unchanged.
It closes test stdin so preview-cancellation fixtures cannot become interactive
confirmation prompts, and reports rather than hides an unavailable native
PowerShell run. Final real archives are inspected separately from outside both
source trees with `scripts/inspect-world-builder-v2-candidate.py`. The manager
creates those real restricted artifacts before the gate with
`./scripts/ai-manager.sh candidate`; that route performs a real build, refuses
the fixture-only skip path and an open gate, and writes only to
`output/candidates/`. Inspection also requires the exact reviewed Linux and
Windows JRE trees and binds their complete dereferenced inventories, bytes, and
relevant modes; see
[Releasing](RELEASING.md) and the pending
[adaptive validation worksheet](releases/world-builder-v2-v0.2.0-alpha.1-validation.md).

## Independent runtime-provider dependency

`runtime-provider.lock` is the sole runtime dependency pin. Treat its exact
commit on its canonical `refs/heads/main` as a frozen external build input
during ordinary development. The provider is developed in
`https://github.com/An-actual-duck/rsc-world-editor-runtime`; it is never
resolved from or synchronized with Spoiled Milk/Core-Framework.

A local checkout can be created at the ignored `.runtime-provider/` path when a
build or explicitly assigned dependency audit requires it:

```bash
./scripts/checkout-runtime-provider.sh
```

For an explicitly assigned dependency-update task, verify that it is the
expected revision and that its adaptive capability and protocol match:

```bash
./scripts/check-runtime-provider-parity.sh .runtime-provider
```

Do not run collaboration scripts inside the disposable checkout. Provider work
uses `/home/justin/rsc-world-editor-runtime` and its own workers; dependency
consumption here uses only the detached exact lock. Fetching a locked object
does not authorize updating the lock.

Only when the user explicitly assigns a dependency-update task, adopt the
exact external commit and durable runtime provider ref they selected with:

```bash
./scripts/sync-from-runtime-provider.sh \
  /path/to/clean-runtime-provider \
  refs/heads/main
./scripts/checkout-runtime-provider.sh
./scripts/check-runtime-provider-parity.sh .runtime-provider
./scripts/test.sh
git diff --check
```

Review the dependency and protocol diff before committing. The adoption
command refuses dirty providers, requires the exact commit at the named remote
ref, and updates only `runtime-provider.lock` plus the v2 runtime protocol. It
never copies World Builder-owned tooling, templates, or either release line.

## Change routing

- Standalone World Editor tooling, tests, documentation, packaging, updater,
  CI, and release-channel work belong here and use this repository's workers.
- If a feature needs client/server behavior not owned here, assign it through
  the independent runtime manager rather than assuming control of Spoiled Milk.
- Runtime adoption occurs only through an explicitly assigned exact commit and
  durable provider ref. It never copies shared source and is never triggered by
  another project's activity.
