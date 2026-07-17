# Source provenance

RSC World Editor is a separate product and release channel built from two
versioned source inputs:

1. This repository owns the standalone launcher, project-management tools,
   package assets, schemas, tests, and release orchestration.
2. The Spoiled Milk/Core-Framework revision named in `core-framework.lock`
   supplies the compatible client, server, definitions, map cache, embedded
   editor integration, and runtime assets.

The initial repository snapshot was extracted from Spoiled Milk commit
`b27cc5cad506ac79f9f50566dfec2d3af2337d64`. The synchronized paths are:

- `tools/world-builder/`
- `release/world-builder/`

The synchronization check compares these directories byte-for-byte with the
locked Core-Framework checkout. Release packages additionally record the exact
RSC World Editor commit and Core-Framework commit from which they were built.

This arrangement prevents a full client/server fork from silently falling
behind while still giving the World Editor its own source repository, issue
tracker, documentation, tags, and downloadable releases.
