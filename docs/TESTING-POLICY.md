# Scope-first verification

The owner approved this policy after a presentation correction incurred a
28-minute Editor gate plus runtime testing without proving the desired visual
appearance. Test selection must follow the changed behavior, not merely the
fact that a runtime lock or candidate version changed.

## Decide before running

State the affected components, selected tests, expected duration, and why that
scope covers the change. Small UI iteration targets 1–3 minutes of verification;
a refreshed owner candidate targets 5–10 minutes including builds and inspection.
These are budgets, not measured guarantees. If broader verification is needed,
explain the newly identified risk and get owner agreement before expanding a
small UI task. Do not silently skip required safety tests to meet a time target.

| Change | Required scope |
| --- | --- |
| Documentation or testing/workflow tooling | Relevant focused workflow tests, syntax checks and diff check |
| UI scale, layout, window/input presentation | Affected compilation, presentation regressions, actual visual and pointer-alignment acceptance |
| Save/load, schema, import/upgrade/recovery, authentication or shared runtime behavior | Affected integration, persistence and refusal/recovery suites; broaden for shared effects |
| Packaging/updater implementation | Affected packaging/updater and integrity/refusal tests |
| Broad cross-component integration or production release | Full applicable suites and existing release gates |

Review the complete provider diff when advancing the runtime lock. Preserve
exact published revisions, composition pairing, parity and immutable project
checks. A profile name or a supplied reason is not automated impact analysis.
The manager must add tests for changed dependencies, packaging or other effects
that the presentation profile does not cover.

## Presentation iteration

Editor:

```bash
./scripts/test.sh --group presentation
```

This runs the Base launch/capability API tests and supervision test without
building a historical intake or performing server upgrades/imports. The class
selector intentionally excludes the expensive real Base lifecycle fixture,
even if its optional environment variables are set.

Runtime manager/worker (not collaboration commands inside the dependency):

```bash
./scripts/test.sh --group presentation
```

This runs the existing viewport, widescreen-input and software-scale tests.
Run both profiles when launch configuration and runtime presentation interact.
Neither certifies the actual visual appearance. Check the real editor against
the intended reference at the relevant resolution/DPI, including text/control
size, world appearance and cursor alignment, before expensive packaging.
Fullscreen/readiness log messages alone do not satisfy that check.

Use an isolated disposable fixture, never overwrite an owner's sealed project.
Builder-character confirmation is expected. Keep visual iteration distinct from
final fresh-project/package launch acceptance so every tweak does not repeat
intake, migration, login, import and recovery scenarios.

## Owner candidates versus production

A restricted owner-test candidate needs affected-area tests, reviewed exact
source/runtime inputs, fresh packaging, independent archive integrity inspection
and a packaged smoke check. Record the last full-tested baseline and why omitted
suites cover unchanged behavior. Call out visual acceptance still assigned to
the owner. Never represent focused checks as a full gate or production release.

The alpha.3 baseline is Editor `a93f2d9b6f70f20b1607bb685b9f878e700f4142`
with runtime `888e06d397059757fe4089c42cd09b4970ccaeff`; the Editor full gate
passed 542 unittest tests with 12 explicit skips, and runtime passed 236 with
five external-input skips. This is historical evidence, not blanket approval
for subsequent behavior changes.

Production releases retain full applicable tests, accepted version-bound gates,
fresh archives and required platform acceptance. No import/upgrade offline,
preview, exact-confirmation, backup, verification or recovery checks are disabled.

## Avoid duplicate work

- Build once per unchanged input set within a verification phase. Do not run
  concurrent builds/tests against the same output directory.
- Reuse only verified immutable outputs with matching source, dependencies,
  build options and toolchain; isolate mutable databases/projects per test.
  No unchecked `--skip-build` shortcut is introduced by this policy. Persistent
  build caching is separate work, not a prerequisite for the focused path.
- Check Java, DISPLAY and GUI-lane prerequisites before lengthy suites. Retry
  only the failed test after resolving an environment issue, then resume the
  unexecuted suffix if inputs are unchanged. Preserve logs and report resumptions.
- Keep result summaries concise. Do not add workers or repeated broad suites
  merely to fill waiting time. Report uncertainty and stop at the agreed scope.

For compatibility, bare test-runner commands still mean the full suite. Use
explicit selections during agent work: Editor `--group all`, runtime `--full`
only when broad verification is intended. Runtime adoption requires explicit
`--verification presentation|transactions|full --reason TEXT` before mutation.
