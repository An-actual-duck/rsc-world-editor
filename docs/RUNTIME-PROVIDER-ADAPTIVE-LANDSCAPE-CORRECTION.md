# Runtime provider correction: strict adaptive terrain startup

| Field | Required value |
| --- | --- |
| Status | PROVIDER CORRECTION VERIFIED AT `0dd7aabb1eb599b2082ae44503ce42cf589b00fd` |
| Affected product | World Builder 2 adaptive client/runtime |
| Current locked provider | `0dd7aabb1eb599b2082ae44503ce42cf589b00fd` |
| Observed blocker | RESOLVED — the Linux client stays alive without requesting or rendering the legacy landscape archive. |
| Release state | PENDING — NOT RELEASE READY |

This correction is complete. The later native candidate is blocked by a
separate undecided-protocol login framing defect documented in
[`RUNTIME-PROVIDER-ADAPTIVE-LOGIN-CORRECTION.md`](RUNTIME-PROVIDER-ADAPTIVE-LOGIN-CORRECTION.md).
This file remains as the accepted terrain-startup contract and regression
baseline; it is not the current provider work request.

## Ownership decision

World Editor must not resolve this failure by packaging, copying, or generating
an `Authentic_Landscape.orsc`, `Custom_Landscape.orsc`, empty archive, packed
terrain placeholder, or renamed equivalent.

The v2 archive is content-neutral, and the selected verified signed-layered
package must be the sole terrain authority. A downstream placeholder would
retain an unverified legacy initialization path and could conceal an attempted
fallback, incomplete native residency, or rendering before layered terrain is
ready. Client terrain initialization, native residency, and rendering gates
belong to the independent runtime provider.

This specification does not authorize modifying Spoiled Milk/Core-Framework
or advancing `runtime-provider.lock`. Provider work belongs in
`rsc-world-editor-runtime` and must arrive as an exact READY handoff before a
separately assigned dependency update.

## Required provider behavior

### 1. Strict activation boundary

The client may bypass legacy terrain initialization only in strict adaptive
World Builder mode.

- Require both explicit client properties
  `openrsc.worldBuilderMode=true` and
  `openrsc.worldBuilderAdaptiveMode=true`.
- Require the authenticated adaptive runtime/protocol binding and the exact
  `adaptive-world-builder` runtime profile before world state is accepted.
- Do not activate from package shape, filenames, missing files, localhost,
  editor availability, or either property by itself.
- Fail closed on incomplete, contradictory, or mismatched activation evidence.

### 2. No legacy archive initialization or reads

In strict adaptive mode, the client must skip the complete legacy packed
landscape initialization path. It must not require, probe, open, hash, parse,
list, cache, or derive terrain state from `Authentic_Landscape.orsc`,
`Custom_Landscape.orsc`, or another legacy terrain archive.

Add an adaptive-mode tripwire at every legacy terrain archive read entry point.
If strict adaptive mode reaches one of those entry points, startup must fail
immediately with an actionable adaptive-runtime error identifying the forbidden
legacy read. It must never fall back to a default, authentic, custom, empty, or
partially loaded legacy map.

### 3. Native layered readiness before rendering

Strict adaptive mode must not expose or render world terrain until the client
has verified and made resident the native layered terrain selected by the
adaptive session.

Before the first world render or editable input, require:

- authenticated client/server adaptive protocol agreement;
- the expected generic layered loader, authoring, coordinate, and protocol
  identities;
- package and manifest identity agreement;
- matching definition and asset evidence;
- native layered terrain readiness/residency for the initial world space,
  level, and coordinates; and
- fail-closed coverage, decoding, collision, and placement validation.

Missing, delayed, malformed, mismatched, unsupported, or out-of-coverage native
terrain must produce an actionable failure. It must not render legacy terrain,
unverified zero-filled arrays, an approximation, or a stale previous world.

### 4. Preserve normal clients

All non-adaptive preservation and Spoiled Milk production profiles must retain
their existing legacy landscape selection, initialization, validation, and
failure behavior. The correction must not weaken or generalize their archive
checks and must not select the adaptive path from content shape.

## Required provider tests

Use content-neutral temporary fixtures and the real client startup path.

1. Strict adaptive standalone-empty startup succeeds with both legacy
   landscape files absent and reaches native layered readiness before render.
2. Strict adaptive adopted-package startup succeeds with arbitrary package
   identity and existing levels while both legacy files remain absent.
3. Instrument every legacy terrain read entry point and prove zero calls during
   successful strict adaptive startup.
4. Force each legacy terrain entry point during strict adaptive mode and prove
   the tripwire fails before rendering or editable input.
5. Delay or omit layered residency/readiness and prove the client does not
   render world state or fall back to legacy terrain.
6. Mismatch protocol, package, manifest, definitions, assets, coordinates,
   loader, and authoring identities independently; each must fail closed.
7. Cover malformed, unsupported, and out-of-coverage layered terrain plus all
   four placement families without silent loss or approximation.
8. Prove either adaptive property alone, package shape, missing legacy files,
   and localhost do not activate the bypass.
9. Run existing preservation and production client tests and prove their legacy
   behavior remains exact.
10. Build and test both provider client and server, including layered loader,
    protocol, editor, persistence, placement, and runtime-profile suites.

## Provider handoff requirements

The runtime provider handoff must report:

- exact pushed commit and branch;
- changed files and the exact legacy initialization/read entry points guarded;
- tests and client/server builds run;
- evidence that native layered readiness gates the first world render;
- evidence that normal legacy-client behavior is unchanged;
- untested native or visual behavior and remaining risks; and
- explicit READY or NOT READY status.

It must not modify this repository, deploy, release, restart a public server,
or alter a live checkout.

## Downstream steps after an authorized provider SHA

The World Editor manager must use a separately authorized dependency-update
task to review the provider handoff and update `runtime-provider.lock`. Then:

1. run the full World Editor suite against the exact clean locked provider;
2. publish tested World Editor `main`;
3. rebuild both restricted Phase 7 candidates with reviewed JRE inputs;
4. independently inspect external copies and record entirely new hashes;
5. launch the Linux candidate for owner visual/edit/save/reopen validation;
6. complete disposable-target import, undo, interruption, and recovery tests;
7. record owner acceptance only if every required check passes; and
8. create `RELEASE-READY` through reviewed work before building a new
   production release.

The current restricted candidate remains rejected and must not be promoted or
reused. No gate, tag, publication, or release is authorized by this document.
