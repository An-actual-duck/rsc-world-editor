# Core Import Candidate Pinned-Core Diagnostic — 2026-09-04

## Purpose

This is an intake report for the RSC World Editor product manager. It documents
a release-blocking private-server startup failure after applying the current
World Builder upgrade/import candidate to the offline test target at:

`/home/justin/Core-Framework (copy)`

This report is diagnostic evidence only. No Editor or runtime worker was
activated, no Editor/runtime source was changed, and no public/live server
operation was performed. The private client was deliberately not launched
after the server failed, because it could only wait indefinitely for an absent
listener.

## Disposition

**Reject this importer candidate for release in its current form.**

The imported target cannot compile its plugins or start its private server.
The candidate removes the old monolithic managed-runtime JAR, but reintroduces
the same stale-server-content failure through a different mechanism:

1. it installs an ignored prebuilt `server/core.jar`;
2. it adds a v3 receipt whose mere presence changes `server/build.xml` so
   `compile_core` is skipped; and
3. the pinned JAR lacks current custom Core classes required by the target's
   existing plugins.

The customized source remains on disk, but the build is forbidden from
compiling it. The resulting runtime is therefore a functional rollback of
server-side content even though the source files themselves were not
overwritten.

## Candidate identity and observed target state

- Target branch: `main`
- Target commit before importer mutations:
  `fec94c8731b5521410963575ef0f2fa5c05ef0b3`
- Imported package fingerprint:
  `dbd4c0fc0d86f9b87273fd859fad78e9bbbfc6e2a6448f46fbecea4325dbc332`
- Imported package manifest SHA-256:
  `68c9b84954c206b98150b38af0693f396c9349bc4883ec2edafd1ab6d05589d5`
- Installed pinned `server/core.jar` SHA-256:
  `dce5e4c5f09fd5f9622173dc49827b66dbd37d92e43de93d3394d71bb054610f`
- Installed pinned `server/core.jar` size: `37,747,317` bytes
- Private endpoint requested by the test: `127.0.0.1:43615`
- Final private endpoint state: no listener

Relevant importer mutations observed in the target:

- Modified `server/build.xml`
- Deleted
  `server/conf/world-builder/installed-runtime-capability-v2.json`
- Added
  `server/conf/world-builder/installed-runtime-capability-v3.json`
- Deleted
  `server/world-builder-runtime/world-builder-managed-runtime.jar`
- Added
  `server/world-builder-configs/installed-server.json`
- Modified `server/world-builder-configs/primary.json`
- Modified `Client_Base/world-builder-configs/installed-client.json`
- Installed the content-addressed server/client package trees
- Installed or replaced ignored `server/core.jar`

The v2-to-v3 receipt migration and creation of the previously missing installed
server profile are positive corrections from the prior candidate. They do not
compensate for the unusable pinned host runtime.

## Exact reproduction

From the imported offline target:

```bash
cd '/home/justin/Core-Framework (copy)'
./scripts/run-server.sh
```

Observed sequence:

1. The private-launch guard correctly selected `myworld.conf`,
   `myworld_dev`, and `127.0.0.1:43615`.
2. Generated definition checks passed.
3. Ant entered `compile-and-run`.
4. The nested `compile_core` target did no work because the imported v3
   receipt exists and sets `world.builder.pinned.host.runtime`.
5. `compile_plugins` deleted the old plugin build products and attempted to
   compile 494 plugin source files against the pinned `server/core.jar`.
6. Compilation failed with 32 missing-package/missing-symbol errors.
7. `runserver` was never reached and port 43615 remained unused.

The failure occurs deterministically before database connection, world load,
login, or map rendering. It is not caused by map data, a client mismatch, or a
network problem.

## Finding 1 — critical: the imported target cannot build or start

Severity: **Critical / release-blocking**

Representative compiler failures include:

```text
package com.openrsc.server.model.combat does not exist
cannot find symbol: com.openrsc.server.content.MageGuildStoneCredits
cannot find symbol: com.openrsc.server.content.BlackUnicornOfferingHealing
cannot find symbol: com.openrsc.server.content.OfferingExperience
cannot find symbol: com.openrsc.server.content.FishingBestCatchSelector
cannot find symbol: com.openrsc.server.content.FoundryDragonSmeltingCost
cannot find symbol: com.openrsc.server.net.rsc.InventoryCapacityPackets
cannot find symbol: MonsterSlayerShopService
cannot find symbol: MonsterSlayerGuildAccess
cannot find symbol: MonsterSlayerDialoguePlan
cannot find symbol: MonsterSlayerContactService
cannot find symbol: MonsterSlayerHazard
cannot find symbol: ClericSigilProductionCatalog
```

All representative classes are present in the customized target's
`server/src` tree. They are absent from the imported prebuilt `server/core.jar`.
The existing plugins therefore remain internally consistent with the target
source but are incompatible with the substituted binary host core.

This is exactly the class of failure the importer must detect before reporting
a successful upgrade.

## Finding 2 — critical: stale server content is now pinned at `core.jar`

Severity: **Critical / architectural regression**

The prior candidate correctly removed
`server/world-builder-runtime/world-builder-managed-runtime.jar`, whose broad
classpath precedence could shadow customized host classes. The new candidate
does not preserve the intended narrow host-integrated model. Instead, it moves
broad provider ownership to `server/core.jar` and suppresses rebuilding that
JAR from the target's current source.

The imported `server/build.xml` adds:

```xml
<available file="conf/world-builder/installed-runtime-capability-v3.json"
           property="world.builder.pinned.host.runtime"/>
```

and changes:

```xml
<target name="compile_core">
```

to:

```xml
<target name="compile_core" unless="world.builder.pinned.host.runtime">
```

The v3 receipt explicitly describes the policy as:

`skip-obsolete-source-recompile-while-capability-installed`

On this target, the source is not obsolete: it contains active custom combat,
guild, inventory-capacity, production, cleric, fishing, and Monster Slayer
systems consumed by the plugin tree. Treating that source as obsolete silently
removes those systems from the executable runtime.

Required correction:

- Do not install a generic provider `core.jar` over a customized target.
- Do not disable compilation of the target's current server source merely
  because a capability receipt exists.
- Integrate the required World Builder runtime capability narrowly into the
  current host source, or construct the runtime from the exact customized
  target source in a staged build.
- A runtime upgrade must preserve the complete target API/content surface, not
  only the World Builder classes and markers.

## Finding 3 — critical: artifact probes prove features, not host compatibility

Severity: **Critical / false-positive acceptance gate**

The pinned JAR passes the receipt's World Builder-oriented probes. It contains:

- `WorldBuilderInstalledServerProfile.class`
- `RSCProtocolDecoder.class`
- `NativeLayeredTerrainChunk.class`
- `GameStateUpdater.class`
- `NativeLayeredWorldPackage.class`

Those probes establish that selected World Builder features exist. They do not
establish that the binary is a compatible replacement for the target's host
core. The target's plugins immediately prove that it is not compatible.

The receipt also does not identify the installed `server/core.jar` through an
explicit target-relative artifact path and exact SHA-256. Its abstract
`archive: server-core` probes are not a complete provenance or transaction
record.

Required correction:

- Inventory the target plugin-to-core linkage before choosing a prebuilt
  strategy.
- Compile every target plugin against the exact staged core that will be
  installed.
- Run a clean full server build from the fully staged post-import tree.
- Record the exact runtime artifact path, SHA-256, provider commit/build
  provenance, and target compatibility evidence in the receipt.
- Feature-marker probes may supplement behavioral/build verification; they
  cannot replace it.

## Finding 4 — high: receipt existence is an unsafe build-control signal

Severity: **High**

`<available>` checks only whether the v3 JSON file exists. It does not verify:

- that the receipt parses;
- that the receipt is authoritative and active;
- that its declared profile matches the selected package;
- that `server/core.jar` exists;
- that the JAR matches a recorded hash;
- that the JAR satisfies target plugin linkage;
- that a required gameplay overlay exists; or
- that the installed transaction completed successfully.

Any partial install, damaged receipt, manual copy, rollback defect, or later
artifact deletion can therefore suppress `compile_core` and strand the target.

The build also still expects `core-gameplay-overlay.jar` on the runtime
classpath, but that file is absent after import. Its creation remains inside
the now-skipped `compile_core` target, making the two policies contradictory.

Required correction:

- Do not use passive file existence as authority to disable the normal build.
- If a pinned mode remains, require an explicit validated launch/build mode
  whose guard verifies every artifact and hash before changing target
  behavior.
- Ensure every output needed by `compile_plugins` and `runserver` is present
  and verified before the transaction commits.
- A failed or incomplete pin validation should fail with one precise importer
  error or safely fall back to compiling preserved host source; it must not
  proceed into a partially pinned build.

## Finding 5 — high: an ignored executable artifact hides the mutation

Severity: **High / transaction and audit weakness**

`server/core.jar` is ignored by the target's Git rules (`server/*.jar`). Its
replacement is therefore invisible in ordinary `git status` and `git diff`,
even though it controls the entire server API used by plugins and the runtime
that would be launched.

This is a shadow mutation. A reviewer can see the build guard and capability
receipt but cannot see which executable was installed, whether it was backed
up, or how it differs from the customized target by inspecting the normal
working-tree diff.

Required correction:

- Treat ignored executable artifacts as first-class transaction members.
- Preview must show old/new path, size, SHA-256, provenance, and rollback
  location.
- Backup, verification, recovery, and rollback must include `server/core.jar`,
  `plugins.jar`, and any overlay artifact affected by the changed build flow.
- Post-install verification must run from a clean build state, not inherit
  previously generated JARs that can hide missing outputs.

## Finding 6 — critical: importer verification did not run the actual launch build

Severity: **Critical / acceptance-process gap**

The candidate reached the target as an apparent successful import despite a
failure reproduced by the ordinary supported command `./scripts/run-server.sh`
in less than one build cycle. This means the acceptance process did not execute
the actual post-install build graph, or did not treat its failure as a
transactional rejection.

A package validator, receipt validator, marker scan, or provider-only test
cannot detect target-specific APIs required by customized plugins. Only the
fully assembled target provides that compatibility boundary.

Required correction:

1. Build in a staged copy containing every proposed mutation, including
   ignored artifacts and build-file changes.
2. Run the target's supported clean server build.
3. Compile the target's complete plugin tree against the exact staged runtime.
4. Start the private server with the installed server profile.
5. Confirm the declared private listener becomes ready.
6. Only then commit the import transaction and receipt migration.
7. On any failure, restore the exact pre-import state and report the rejected
   candidate without leaving a pinning receipt behind.

## Positive results to retain

The following candidate improvements are structurally correct and should be
preserved in the next design:

- The stale v2 receipt is removed rather than left alongside v3.
- The old `world-builder-managed-runtime.jar` is removed.
- A concrete `installed-server.json` profile is now created.
- Server and client profiles select the same package fingerprint and manifest.
- The content-addressed server/client package paths are coherent.
- The private launch guard still selects the development database and private
  loopback endpoint.

The correction must retain those improvements without replacing or bypassing
customized host code.

## Ownership split for correction

### Editor/importer-owned

- Detect whether the target is customized before selecting an upgrade plan.
- Include ignored runtime artifacts in preview, backup, verification, and
  rollback.
- Stage the complete transaction and run the target's supported full build.
- Refuse/roll back when core/plugin linkage fails.
- Make receipt activation dependent on verified transaction completion rather
  than file presence.
- Report exact artifact hashes and provenance to the user.

### Runtime-provider-owned

- Supply a narrow integration or target-built runtime that preserves the
  customized host API/content surface.
- Define a versioned capability contract that does not require declaring the
  target's current source obsolete.
- Provide target-consumable build/behavior tests for all installed map
  encodings and profile/bootstrap functionality.
- If a prebuilt mode is retained for genuinely compatible targets, provide a
  complete binary-linkage gate and explicit artifact identity.

The World Editor product manager should coordinate both sides and reject a
provider fix that merely broadens the prebuilt snapshot again.

## Minimum regression fixture

Create a customized Core fixture containing:

- at least one target-only core class referenced by an existing plugin;
- at least one target-modified core class;
- a valid older installed-runtime receipt/package;
- ignored pre-existing build artifacts; and
- the current v3 upgrade candidate.

The import test must prove all of the following:

1. preview inventories every tracked and ignored runtime mutation;
2. import preserves the target-only and target-modified classes;
3. a clean post-import `compile_core`/`compile_plugins` succeeds;
4. the server starts on the private port using the installed profile;
5. current World Builder terrain and placement capabilities remain active;
6. rollback restores exact old hashes and build behavior; and
7. no broad provider archive or pinned generic core shadows the customized
   host.

## Final recommendation

Keep the single v3 receipt, installed server profile, and removal of the old
managed-runtime archive. Reject the `pinned-prebuilt-host-core-v1` strategy for
customized targets in its present form.

The importer must upgrade the current customized server rather than substitute
a generic server snapshot and label the customized source obsolete. The
minimum release gate is the actual target's clean server/plugin build followed
by private listener readiness using the exact artifacts that the transaction
will leave installed.
