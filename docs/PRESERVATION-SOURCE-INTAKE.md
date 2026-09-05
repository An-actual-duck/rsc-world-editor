# Reviewed Preservation source intake

The production `preservation-family-v1` adapter now recognizes a bounded genuine
historical source layout, not the invented four-file transaction fixture.
Production activation remains disabled. This is not candidate acceptance.

## Authority and current scope

The historical identity is commit `c0102e60774ab9c9076aabae49f6f97fb6fc4b00`, tree
`6db5536d795abf34f303bb03b20c43b8cfb9e3fe`. The packaged source closure binds
1,246 source/build/resource records and 22 historical vendor dependencies. The
new `preservation-c0102e-source-intake.json` resource additionally seals 12 public
configuration, map, definition and launcher paths. It contains metadata only.

The source files are required. Vendor dependencies may be absent because the
target is not rebuilt or executed; present dependencies must match exact reviewed
bytes and modes. Arbitrary game binaries are not authenticated by source presence.
Unknown JARs refuse before mutation. Changed or additional plugin source receives
T3 `PORT_REQUIRED`; platform/client/build source changes require a T4 port.

The bounded map input is the actual historical pair of
`server/conf/server/data/Authentic_Landscape.orsc` and
`Client_Base/Cache/video/Authentic_Landscape.orsc`, not a fabricated
`client/cache/landscape.pack`. Both reviewed files contain 945,225 bytes with
SHA-256 `48ed0e1634b870888f96c0bc3e31cbaf152570b913140fdfd3596897a3eb29fa`.
Their identity does not by itself establish complete conversion or gameplay parity.

Effective configuration follows connections-first and local-replaces-named
precedence. Sealed hashes of 291 nonempty historical configuration values identify
unchanged defaults without embedding their plaintext values. Existing supported
name, bind, port and experience overrides retain typed translation. Missing,
unknown or changed nonportable settings require a port; they are not silently
dropped. Null/ambiguous parser semantics remain blockers. Source configuration
may be private `0600`; source code retains reviewed `0644`, and historical
launcher files retain their recorded mode but are never executed.

The isolated staging fixture retains real converter/database-migrator execution,
but has distinct `preservation-staging-fixture-v1` execution identity and
`synthetic-fixture` adapter authority. It remains activation-disabled. The public
CLI never selects this fixture based on target paths or bytes.

## Reproducible positive evidence

`test-world-builder-preservation-source-intake.py` always verifies sealed-resource
integrity, distinct fixture/production identities and rejection of invented
topology. Its genuine positive and customization tests require an explicitly
provided Git object store containing the exact historical commit:

```bash
WORLD_BUILDER_PRESERVATION_SOURCE_GIT=/path/to/reviewed-public-git-store \
  ./scripts/test.sh --file test-world-builder-preservation-source-intake.py
```

The test checks commit/tree and every selected blob hash before materializing a
new temporary external source fixture. It reads only the packaged allowlist at
that exact commit, never working-tree files, branches, credentials, databases,
vendor/game binaries or ignored/untracked state. It does not build or launch
historical code. Connection settings are deliberately invented literal-loopback
SQLite configuration; no historical connection secrets are read.

The owner-designated read-only reference supplied the historical source/map bytes
for development verification. Its filesystem path is not a test default. Historical
contributor documentation names `https://orsc.dev/open-rsc/Game` as the public
source origin; an available immutable public mirror/provider fixture acquisition
route must still be established before this becomes a required portable candidate
row. Missing external source is reported as unavailable, never a synthetic pass.

## Remaining intake work

- Account for the rest of a complete checkout's reviewed inactive content and
  generated files without globally ignoring unknown executable inputs.
- Bind initialized and populated state through provider schema evidence, not a
  fixture database file hash.
- Implement descriptor-free Authentic landscape discovery and complete map
  conversion with the actual historical definition/placement selection rules.
  The old fallback requires `custom_landscape: true` and MyWorld-specific inputs;
  it is not a Preservation adapter.
- Feed current project capabilities, installation, subsequent managed upgrades,
  map-only import and desktop selection through those proven inputs.

Do not describe source classification, successful staging, or an unavailable
external fixture as a usable server-upgrade candidate.
