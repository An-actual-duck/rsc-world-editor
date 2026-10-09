# Core-owned staged content re-export

Prepared October 8, 2026. This is the remaining maintained-export part of the
owner's adopted content lifecycle objective. Editor implementation and packaged
acceptance have passed; this document does not authorize deployment or mutation
of the normal Core checkout.

## Demonstrated gap

The frozen Core producer checkpoint `66854862fb3a3638eceb181807cfac47f520d518`
creates its output directory, frame ZIP and manifest exclusively. A second
capture cannot safely replace the first at stable paths. The readiness checker
correctly verifies current content but does not publish a replacement.

Editor now supports an equivalent rebuild followed by regenerated complete NPC
export and runtime re-verification. It independently verifies the runtime and
allows only the corresponding archive hashes in the otherwise unchanged
producer. Frame paths, IDs, bytes, definitions, probes and other content remain
exact. A maintainer should not have to delete a capture, change its asset paths,
or repair recorded hashes to use this sequence.

The exporter already gives frame entries deterministic timestamps. Preserve that
property and verify that equivalent captures produce identical frame ZIP bytes.
Do not broaden Editor exemptions to accept new content as a rebuild difference.

## Required Core implementation

Implement and test a Core-owned explicit staged export/publish action in the
isolated producer implementation checkout under Core's workflow. Retain the
current export's public manifest and frame paths and all previous captures.

1. Capture into a fresh private stage with the maintained trusted exporter.
   Verify the exact selected source, binary, configuration and effective catalog
   closure before and after capture. Unknown or changed inputs refuse publication.
2. Validate the staged producer, frames and paired catalogs independently. Use
   stable final relative paths in published evidence; temporary paths must not
   become a new content identity.
3. Require the destination offline and unchanged from the reviewed before state.
   Preserve exact previous generated files in a new immutable generation backup.
4. Publish the coordinated generated output with durable intent and recovery.
   A reader must not accept a mixed manifest/frame/catalog generation. Run the
   maintained read-only readiness check on the installed generation.
5. On publication or readiness failure, restore and verify the exact prior
   generated state, or report an explicit recoverable interruption. Never
   overwrite unrelated files or discard an earlier generation.

The ordinary invocation should let a maintainer re-export after its normal build
without selecting a new provider path or manually editing evidence. Keep the
first-capture path supported. This is content export work: target map packages,
activation profiles, saved Editor projects, gameplay definitions, accounts and
unrelated maintained code are not rewritten by publication.

Prefer a publication wrapper around the unchanged capture implementation where
appropriate, so adding safe publication does not gratuitously invalidate the
producer's captured helper-source bindings. Any necessary helper change must be
explicitly reviewed and handed off; do not conceal it from source provenance.

## Acceptance

- First export and repeated export at identical stable paths.
- Equivalent archive rebuild, including changed timestamps/order, followed by
  export: only verified client archive source/probe bindings change; RGB ZIP and
  other producer content remain identical.
- Real added/changed content generates truthful reviewed producer/catalog data;
  it is not presented to Editor as an equivalent runtime rebuild.
- Invalid source/binary/configuration/catalog/frame closure refuses before
  publication. Preview-to-publication changes refuse.
- Injected failure and interruption at publication boundaries preserve or
  recover the exact previous generation. Unexpected existing entries and unsafe
  paths refuse. Historical generations remain byte-identical.
- Current readiness check passes after successful publication and detects stale
  evidence; it remains read-only.
- On a separate disposable copy, normal build → maintained export → readiness →
  Editor re-verification → Detect New Content → two changed imports → reopen.
  Preserve saved map edits, project history and unrelated target content.

Use the accepted restricted Editor candidate `v0.8.1-alpha.15`, source
`2ca414daa1b3864a3eda3c48e861ab1e9445746d`, runtime
`67361019f28ba52f6acf08a19f6499302a79d8c4`. Its persistent directory is
`/home/justin/world-builder-test-builds/targeted-runtime-v0.8.1-alpha.15`;
`CORE-RETEST.md` gives the complete rebuild/re-verification/import sequence and
`ACCEPTANCE-RESULTS.json` binds the evidence. See the
[alpha 15 acceptance record](WORLD-BUILDER-ALPHA15-ACCEPTANCE.md).
Earlier alpha 13 `/tmp` fixtures were lost during the forced shutdown and are
not current retest inputs. Core owns its new disposable mutation fixtures and
actual game verification; frozen Core evidence remains read-only.

## Authorization boundary reported by Core

The Core manager reported that its current owner-supplied `AGENTS.md` says:

> Never activate a Core worker for a World Builder/Editor task, runtime-provider
> correction, provider-lock request, or instruction originating from the World
> Editor repository.

It also reported that ordinary implementation in its manager checkout is
prohibited. This is a report from that manager; Editor has not inspected or
modified normal Core or bypassed those rules.

Core required a direct owner instruction in its own thread allowing this
Core-owned staged re-export implementation and testing in the isolated producer
checkout as a narrowly scoped exception. Normal Core/live mutation and deployment
remain outside that exception. Editor's complete candidate and reviewed scope
are available before that authorization is requested.

The Core manager subsequently reported direct owner authorization in its own
thread and began implementation in a fresh isolated producer checkout at the
frozen checkpoint. The implementation authorization is resolved. The later
implementation handoff is recorded below; complete Editor rebuild/export/import
acceptance remains pending.

Core subsequently handed back reviewed isolated commit
`bf0b31c0ac328191fc743bef87b3cd95e653dc37`, reporting 42 independent checks
including crash recovery and complete build-input closure. Repeated normal
client source build, staged re-export and read-only readiness passed on its new
disposable fixture, retaining deterministic frames and 30,039 project/map files.
This completes the Core implementation handoff; Editor end-to-end runtime
acceptance remains separate. In particular, compiler output differences from
the earlier curated archive are not claimed equivalent. No normal Core or live
mutation or owner retest is authorized by these results.

October 9 update: Core subsequently released quiescent persistent normal
Java 17/staged export/readiness results with saved work, source, history,
deterministic sprites and map assets preserved. Editor's actual alpha 15
re-verification/content successor/two-import/reopen chain and recorded-location
desktop transaction now pass. The candidate above is accepted for restricted
owner inspection; [its acceptance record](WORLD-BUILDER-ALPHA15-ACCEPTANCE.md)
distinguishes that evidence from Core-owned private-gameplay checks and native
Windows/production acceptance. No normal Core/live mutation is authorized here.
