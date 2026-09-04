# Source provenance

RSC World Editor is a separate product and release channel built from two
versioned source inputs:

1. This repository owns the standalone launcher, project-management tools,
   package assets, schemas, tests, and release orchestration.
2. The independent RSC World Editor Runtime revision named in
   `runtime-provider.lock`
   currently supplies the compiled client/server editing runtime, embedded
   editor integration, capability contracts, and explicitly allowlisted default
   definition/rendering assets. Under the active replacement direction it is
   also the sole future source of the current platform/runtime artifacts,
   provider-owned Base/Advanced compositions/modules, and runtime-side adapter
   manifests, payload roles, migration capabilities, and fixtures. This Editor
   repository continues to own safe historical discovery/classification,
   destination planning, and transaction execution.

No map cache or server world from that dependency is an authoritative World
Builder 2 input. Target terrain, placements, configuration, definitions, and
lineage come from the user's detected server and are copied into a durable
project; standalone mode generates canonical empty project data locally.

The initial v1 repository snapshot was extracted from Spoiled Milk commit
`b27cc5cad506ac79f9f50566dfec2d3af2337d64`. The legacy
`release/world-builder/` tree is preserved here and is no longer synchronized.
World Builder-owned tools, package assets, schemas, tests, documentation, and
updaters live only in this repository. An assigned product objective requiring
runtime integration advances the lock only to a clean tested provider commit
published on provider `main` and verifies the currently implemented approved
runtime surface; the planned current-generation path additionally verifies
platform, variant, module, and adapter manifests. Neither path copies a complete
client/server tree into this repository. Release packages record the exact RSC
World Editor and runtime-provider commits from which they were built, plus all
applicable composition manifests, the checked-in runtime asset allowlist, and
source credits.

The dependency has its own manager and worker system in the separate
`rsc-world-editor-runtime` repository. World Editor consumes only published,
exact commits; its lock changes only within an assigned cross-repository product
objective or a specifically requested dependency update. Neither project
monitors or operates Spoiled Milk/Core-Framework work. Selected advanced
behavior may be deliberately reviewed and ported into the runtime provider with
explicit provenance. An owner-authorized disposable Core copy may serve as
read-only migration evidence, but its Git history, ignored state, private
content, and built artifacts never become an implicit product input.

This arrangement prevents a full client/server fork from silently falling
behind while still giving the World Editor its own source repository, issue
tracker, documentation, tags, and downloadable releases.
