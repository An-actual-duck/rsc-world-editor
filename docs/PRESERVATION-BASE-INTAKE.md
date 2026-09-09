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

The packaging helper `export-current-base-catalog` accepts
`--provider-catalog-root`, `--composition-identity`, and
`--destination <new-absolute-installation-root>`. It copies only selected
artifact `sourcePath` entries, the platform's schema-contract closure, and the
exact identity at `current-platform/composition-identity.json`. It preserves
source bytes/modes and proves that the compiled Java resolver accepts the
relocated catalog. Provider build scripts may be inventory-bound payloads;
the helper never executes them or includes the full source checkout. Existing,
linked, or overlapping destinations are refused. A failed partial new tree
is not a candidate and must not be reused as a completed export.

Native lifecycle acceptance now passes against published runtime
`4d589cb4bb43c8db3954a3d412eceaa5d743d7b2`: genuine project creation from the
sealed public source, two authenticated native editor sessions with clean
server/client exit and restart, reopen/export, content tamper refusal, and
unchanged historical target/source evidence. The test uses invented private
state and confirms that only the isolated authoring database gains the Builder
account. Two tests passed in 118.923 seconds with the real-launch option enabled.
The connected run found and fixed a missing diagnostics-directory creation
before moving the conversion reconciliation report.

The selected provider must explicitly advertise
`current-base-isolated-authoring-v1`; the older normal-only Base is refused
before project publication. The current pin satisfies that requirement.

## Connected upgrade checkpoint — 2026-09-09

A real CLI upgrade on a retained invented-state Preservation target completed
sealed SQLite migration, executable server/client verification, initial instance
construction, and guarded activation successfully. Its first attempt had rolled
back because the constructor required optional absent chat-filter files. Initial
activation now supplies private empty lists for exactly those absent files,
preserving the historical no-list behavior instead of importing provider default
filters. Existing filter bytes and the owner keypair remain unchanged; original
persistent inputs are rechecked before cutover.

Focused transaction (33), instance (13), successor topology (4), and empty-filter
(2) tests pass. The connected server-cycle test uses a fresh populated historical
database, isolated disposable ports, normal owned-window login, saved map edits,
and repeated map-only imports/restarts. The normal login helper's synthetic
all-green-map assumption is replaced with imported-world detail, non-void, and
visible-player checks. Acceptance was exercised in two phases on the same
retained disposable installation: real initial upgrade, then normal login and
two saved-map imports/restarts after fixing the connected failures below. The
fresh all-in-one regression remains part of consolidated acceptance; this is not
an uninterrupted full-suite or packaged-candidate pass.

The established desktop **Upgrade Target Runtime** action and active-project
terminal command now route native Base projects to this guarded transaction,
using the installation's relocated selected catalog and the project's recorded
target (not the installation's parent). Their preview displays the destination,
state-preservation policy, evidence location, plan fingerprint, and exact
confirmation. A genuine fixture test passes desktop preview and terminal
cancellation with the historical target unchanged. Catalog export is tested;
the release packager still needs to include that projection.

Connected testing also exposed a Linux `TIME_WAIT` false busy-port refusal after
clean shutdown. Linux leases now allow address reuse without enabling port
sharing; tests prove a closed connection is accepted and active loopback/wildcard
listeners are still refused. Other operating systems retain exclusive defaults.
The 33 transaction, 6 map-import, 13 instance, 4 successor-topology, and 2
empty-filter tests pass after this fix, as do both new port tests.

The connected test reached a committed map import with unchanged player state,
then caught a real restart refusal: the generated map profile used `package`
instead of the runtime's canonical
`world-builder/packages/<package-fingerprint>/package` identity. Map import now
emits the provider-required logical path while keeping the physical external map
root in the bound launch descriptor. Both server and client profiles are checked
in the connected regression. No runtime rewrite or relaxed loader check is needed.

The retained installation then passed both real map imports, three normal player
logins with clean owned-session shutdown, and post-commit map recovery without
rewinding later state. Inventory (321 coins), quest stage, skill levels, and
offline account status survived. Imports retained the runtime code and all
player/side-state roots, and the original historical files and project source
remained unchanged. A completed-upgrade recovery request was correctly refused
without changing gameplay state; it is not a completed-upgrade Undo operation.
The six focused map recovery/workspace checks also pass after the profile fix.

No live-target mutation or candidate is authorized by these test results. Managed
successor end-to-end acceptance, desktop current-transaction recovery,
packaging integration, and consolidated candidate acceptance remain work to close.
