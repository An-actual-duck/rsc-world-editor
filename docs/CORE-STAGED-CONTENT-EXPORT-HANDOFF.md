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

Use Editor candidate `v0.8.1-alpha.13`, source
`cfd1ffef7f73356c6cb6dc765482b8dfc3d58697`, runtime
`67361019f28ba52f6acf08a19f6499302a79d8c4`. The packaged desktop acceptance
is `/tmp/world-builder-final-desktop-p9r81lbx/acceptance-results.json`; its target
and frozen reference are read-only inputs, not Core implementation checkouts.
Core owns any new disposable mutation fixtures and actual game verification.

## Authorization boundary reported by Core

The Core manager reported that its current owner-supplied `AGENTS.md` says:

> Never activate a Core worker for a World Builder/Editor task, runtime-provider
> correction, provider-lock request, or instruction originating from the World
> Editor repository.

It also reported that ordinary implementation in its manager checkout is
prohibited. This is a report from that manager; Editor has not inspected or
modified normal Core or bypassed those rules.

Core requires a direct owner instruction in its own thread allowing this
Core-owned staged re-export implementation and testing in the isolated producer
checkout as a narrowly scoped exception. Normal Core/live mutation and deployment
remain outside that exception. Editor's complete candidate and reviewed scope
are available before that authorization is requested.
