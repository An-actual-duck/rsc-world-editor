# World Builder 2 v0.8.0 release validation

Status: **ACCEPTED — RELEASE READY** (2026-09-12).

Validated Editor commit: `25f37d41369eef3b38c5dce8f7e0caf7d4360b3c`.
Locked, published runtime: `0355b2eccf1717a3235251d7b917f13b2fa454d6`.
The production source adds only this record and its version-bound release gate.
Publication remains conditional on fresh production packaging and archive inspection.

## Accepted scope

Ship the owner's accepted map-editor-compatible Current Base workflow, not a
new client modernization project. The owner tested editor tools, saving, runtime
upgrade, map import, normal player login and managed-map re-detection on Linux.
Preservation-style editor presentation is accepted. Optional renderer upgrades,
Advanced compositions/modules and Spoiled Milk-specific integration are not
shipped or required for this release. V1 remains frozen and cannot cross-update.
Publish this V2 build as GitHub Latest, not the older alpha.88 prerelease.

The shipped composition is `current-base-v1`, with no optional modules. The
supported input matrix is the reviewed Preservation-family SQLite intake and
managed Current Base targets. Unsupported or ambiguous customizations fail
before mutation. This is not a claim of universal historical-server support.

## Verification and bounded reruns

Both complete release runners were executed through their final selections:
Editor `scripts/test.sh --group all` and runtime `scripts/test.sh --full`.
When a selection failed, its cause was inspected, the affected selection rerun,
and the unexecuted suffix resumed; already passing suites were not restarted.

- Editor coverage includes discovery, project lifecycle, upgrade/import safety,
  persistence/recovery, installed launch, packaging, updater and release gates.
- Runtime coverage includes Base public definitions, state migration, gameplay
  execution, real installed login/registration/logout, streaming/navigation,
  rendering guardrails and exclusion of Advanced-only effects.
- Explicit public-source intake: all 12 tests pass across initial run and one
  rerun after an overlapping fixture build was stopped from invalidating inputs.
- Genuine Preservation conversion: passes against public historical commit
  `c0102e60774ab9c9076aabae49f6f97fb6fc4b00`, using Git blobs and invented state.
- Genuine map semantics: all five tests pass, including independent historical
  terrain/collision/population checks, transitions, client windows and tampering
  rejection. The current conversion probe has a reviewed fixed seal; no dynamic
  trust bypass was introduced.
- Fresh Base server cycle: passes (198.898 seconds), covering native project
  capture, transactional initial upgrade, normal player login/clean shutdown,
  two saved-map imports, normal restarts and state-safe recovery.
- Runtime lock parity passes at the exact published runtime above.

The release run corrected stale test expectations for accepted Preservation UI
and test routing, and refreshed the genuine semantic fixture's exact binding.
Runtime changes after `382d7d54031cb7c2eefb5020d923f9530d2aea9b` are exclusively
tests/documentation; production source/assets are unchanged. Editor production
behavior is unchanged after the accepted `b1828c9382447a75218d9c80310cae3bd9f16419`;
subsequent changes are documentation, test expectations and the runtime lock.
Full suites were run against that same implementation. Lock adoption requires
parity and fresh production builds, not another duplicate full-suite run.

Nested Xephyr manual-input checks did not reliably deliver keystrokes. Actual
GUI login cases passed on the normal desktop with the bundled Java 17. A first
fresh-cycle verifier timed out fetching client configuration; the transaction
did not activate its target. Its focused rerun passed all phases without a code
change. The initial timeout is retained as an intermittent test observation,
not represented as a diagnosed or fixed product defect.

Default optional tests requiring external historical/predecessor inputs and
native Windows execution were skipped where unavailable. Explicit genuine
public intake/conversion/semantic/Base-cycle checks above cover public Base
intake. Managed successor safety is covered by the automated current-runtime
upgrade suites; a new native historical-predecessor GUI cycle is not claimed.
Native Windows interactive acceptance is not claimed. Windows archive,
launcher and updater checks are automated; server-root managed player startup
is Linux-only.

Session logs use `/tmp/world-builder-v080-` prefixes: `editor-full`,
`editor-resumed`, `editor-tail`, `editor-native`, `runtime-full`,
`runtime-resumed`, `client-native`, `runtime-tail`, `runtime-final-tail`,
`public-intake`, `public-intake-rerun`, `map-probe`, `genuine-semantics`,
`genuine-client-rerun` and `base-cycle-rerun` (each `.log`). Temporary logs and
invented-state fixtures are not release contents or committed user data.

## Packaging and publication

Build fresh Linux x64 and Windows x64 production archives with their reviewed
Java 17 distributions; do not promote candidate archives in place. Keep asset
allowlist, release-marker, manifest, checksum and independent archive checks.
Upload only the resulting two archives and checksums after inspection succeeds.
Keep the accepted gate in the immutable release tag, then consume it on
development main after publication.
