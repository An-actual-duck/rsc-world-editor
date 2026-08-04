# Source provenance

RSC World Editor is a separate product and release channel built from two
versioned source inputs:

1. This repository owns the standalone launcher, project-management tools,
   package assets, schemas, tests, and release orchestration.
2. The Spoiled Milk/Core-Framework revision named in `core-framework.lock`
   supplies the compatible generic client/server editing runtime, embedded
   editor integration, runtime capability contract, and explicitly allowlisted
   default definition/rendering assets.

No map cache or server world from that dependency is an authoritative World
Builder 2 input. Target terrain, placements, configuration, definitions, and
lineage come from the user's detected server and are copied into a durable
project; standalone mode generates canonical empty project data locally.

The initial v1 repository snapshot was extracted from Spoiled Milk commit
`b27cc5cad506ac79f9f50566dfec2d3af2337d64`. The legacy
`release/world-builder/` tree is preserved here and is no longer synchronized.
World Builder-owned tools, package assets, schemas, tests, documentation, and
updaters live only in this repository. An explicitly assigned dependency update
advances the lock and verifies the small approved runtime surface; it does not
copy a complete client/server tree into this repository. Release packages
record the exact RSC World Editor and Core-Framework commits from which they
were built, plus the checked-in runtime asset allowlist and source credits.

The dependency is not part of this repository's manager/worker system. Its
branches, workers, releases, and newer commits are not monitored here, and the
lock changes only during a user-assigned exact-commit dependency update.

This arrangement prevents a full client/server fork from silently falling
behind while still giving the World Editor its own source repository, issue
tracker, documentation, tags, and downloadable releases.
