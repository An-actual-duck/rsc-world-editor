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

Routine development should use the smallest relevant named group, file, or
exact unittest selector. Successful output is concise by default; `--verbose`
prints the complete captured unittest output:

```bash
./scripts/test.sh --list
./scripts/test.sh --group workflow
./scripts/test.sh --file test-world-builder-adaptive-transactions.py
./scripts/test.sh --test \
  test-world-builder-adaptive-transactions.py::AdaptiveTransactionTest.test_export_preview_import_and_exact_undo
```

The no-argument command remains the full integration gate. Focused selection
shortens feedback; it does not replace the risk-appropriate full run before
behavioral integration or release. See [Test map](../tests/README.md) for group
ownership and native requirements.

Inspect ignored generated-output retention without changing any file:

```bash
./scripts/preview-generated-output-cleanup.sh
./scripts/preview-generated-output-cleanup.sh --verbose
```

`REVIEW-DISPOSABLE` is an inventory classification, not deletion authority.
Development sandboxes and paths with durable-state markers remain blocked or
manual-review-only.

## Reusable tool test environment

Tool interaction work uses a generated development-only world rather than a
private-server map. The first prepare builds the exact locked independent
runtime and creates one persistent ignored project with a complete flat
48-by-48-tile working sector centered on layer `0` spawn `120,648`. Its
immutable baseline remains the runtime-validated canonical standalone seed:

```bash
./scripts/world-builder-tool-test-environment.sh prepare
```

Later prepares validate and reuse the same UUID project. Launch it directly,
without the desktop Create Project flow, with:

```bash
./scripts/world-builder-tool-test-environment.sh launch
```

The default installation is beneath
`output/development/world-builder-tool-test-environment/` and is ignored by
Git. Print its exact path with the `path` mode. To return to a new deterministic
seed, use the explicit recoverable reset:

```bash
./scripts/world-builder-tool-test-environment.sh reset --confirm RESET
```

Reset moves the complete previous installation under the environment's
`retired/` directory before creating another; it does not delete the old
project. A runtime-lock or seed-identity change also refuses silent reuse and
requires this explicit reset.

The persistent sandbox is for human/AI exploratory work only. Automated tests
create independent temporary projects from the same generator and never reuse
the mutable sandbox. Public packaging continues to reject terrain, placements,
projects, databases, logs, and other generated development state.

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
DISPLAY=:0 \
python3 tests/myworld/test-world-builder-native-runtime-integration.py -v
```

The runtime allowlist must be the runtime directory's sibling. On Linux this
proof requires an explicit usable `DISPLAY`; routine headless CI skips instead
of substituting a no-UI client. It creates a standalone-empty project, starts
the packaged server and OpenGL client, authenticates the isolated Builder,
accepts the adaptive binding and native terrain, and uses the client's
automated-exit-on-ready test property for noninteractive orderly shutdown. It
also verifies the post-run save, database migrations and project-local PEM
generation, rejects login/query-registration exceptions and retries, and
proves the packaged runtime, exact provider, and target outside the
installation stayed unchanged. No screenshot or visual acceptance is claimed.
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

For an in-scope runtime integration, verify that it is the expected revision
and that its adaptive capability and protocol match:

```bash
./scripts/check-runtime-provider-parity.sh .runtime-provider
```

Do not run collaboration scripts inside the disposable checkout. Provider work
uses `/home/justin/rsc-world-editor-runtime` and its own workers; dependency
consumption here uses only the detached exact lock.

When an assigned World Builder objective includes runtime work, the product
manager may adopt the exact tested commit it published on the durable runtime
provider ref with:

```bash
./scripts/sync-from-runtime-provider.sh \
  /path/to/clean-runtime-provider \
  refs/heads/main
./scripts/checkout-runtime-provider.sh
./scripts/check-runtime-provider-parity.sh .runtime-provider
./scripts/test.sh
git diff --check
```

`./scripts/product-manager.sh adopt-runtime` performs that complete bounded
selection, materialization, parity, full-test, commit, and publication cycle.
The owner does not need to relay the SHA or issue a second lock prompt for
runtime work already inside the active objective.

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
