# Preservation source intake boundary

The compiled `preservation-source-jag-v1` discovery adapter admits the reviewed
public c0102e source layout, its JAG/MEM map inputs, and supported effective
configuration. A target-supplied descriptor does not grant this authority.
Changed source/build behavior, unknown files, unsafe paths, and untranslated
configuration remain blockers. Historical code is never built or executed.

The `preservation-c0102e-private-inputs-v1` policy binds metadata for these exact
external inputs, including explicit optional absence:

- Required: `server/inc/sqlite/preservation.db`, `server/server.pem`, and
  `server/client.pem`. Existing owner keys must form a valid matching pair;
  intake never generates replacement owner keys.
- Optional: `server/badwords.txt`, `server/goodwords.txt`,
  `server/alertwords.txt`, `server/ipbans.txt`, `server/ipbans.temp`, and
  `Client_Base/clientSettings.conf`.

These bytes are excluded from discovery's project-copy inventory. The private
binding records paths, roles, presence, size and SHA-256 only. Database/key/client
preferences require mode 0600; text filters and bans permit 0600 or 0644.
Symlinks, hard-link aliases, non-regular entries and unclosed SQLite sidecars
are refused. Bounds are 4 GiB for SQLite, 64 KiB per key and 1 MiB per other file.
Later client UID/remembered-credential layouts are not silently classified as
historical c0102e state.

A closed SQLite header is not schema validation. Production migration preview
records `schemaValidation: pending-provider-sealed-migration`, sets
`sqliteSchemaMigrationReady: false`, and keeps `activationApproved: false`.
Any future sealed staging must run the selected provider's actual state
migration and verify its output before activation can be considered. Text and
preference files are bound for preservation, not declared behavior-equivalent
merely because their paths and hashes are known.

The production project path now selects the complete native Base composition,
retains its artifacts and derived full authoring catalog, invokes only the
selected provider's decoder, and seals the original public inputs and canonical
baseline. It does not create an Advanced content overlay or copy the target's
player database or keys. The isolated authoring database belongs at
`working/authoring-state/world_builder.db`, separately from runtime artifacts.
The server and client commands bind the retained composition identity.

CLI creation selects `--provider-catalog-root` and `--composition-identity`
together. Desktop creation supports an explicitly injected selection; packaged
discovery defaults to `current-platform/` and its `composition-identity.json`
under the installation. Both paths use the existing verified provider catalog
resolver, with no fallback to historical or Advanced executables. Packaging
still needs to ship that selected catalog.

This wiring checkpoint is not native launch acceptance and does not authorize
server upgrade/map-import mutation. The selected provider must explicitly
advertise `current-base-isolated-authoring-v1`; the older pinned normal-only
Base is refused before project publication. After the runtime manager publishes
and the Editor adopts that policy, run the genuine Base lifecycle check and the
actual supervised editor launch. These are the remaining positive checks, not
grounds to repeat already-passing source admission/decoder or inherited
lifecycle checks. Target activation, separate map import, and candidate
acceptance remain manager integration work.
