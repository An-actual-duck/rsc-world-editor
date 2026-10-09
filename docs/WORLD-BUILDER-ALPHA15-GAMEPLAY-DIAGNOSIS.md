# Alpha 15 gameplay failure: read-only diagnosis

October 9, 2026. Owner acceptance is failed; the previous lifecycle and
preservation tests remain valid. The owner authorized the bounded two-fix scope
after this diagnosis. The original investigation below is retained; current
implementation progress follows here and does not establish gameplay acceptance.

## Authorized implementation progress

The shared provider correction is published at
`c79e9ab3bf178d1f693127659399e5940721049c`, exact tested implementation
`9b1ba85d45a80bed093f3d710f2e087066860369`. A paired replay reproduces the old
9-versus-25 resident ledger disagreement before stage acknowledgement. A separate
diagnostic continuing past that early equality stop reproduces the original
decoder's missing-resident-reference exception after prediction cancellation.
The strict regression is unchanged and passes with the correction.

All three stage senders now commit wire residency after successful queueing.
Stage acknowledgement proves prebuild readiness only. Late/duplicate/stale ACKs
cannot recommit a previous cache snapshot. No client, wire format, identity,
capacity or feature gate changed. Twenty-three tests across seven affected
suites pass, including legacy/wide mixed terrain replay, capacity pressure,
different-center cancellation, reversals, teleport, reconnect, failed queues
and strict readiness. The replay also verifies that the actual changed
`GameStateUpdater` compiles through the production serializer source path.

Persistent evidence:
`/home/justin/world-builder-test-builds/alpha15-terrain-sequencing-repair/PROVIDER-VERIFICATION.json`.
Historical narrow fixture tests were run against a verified lossless nine-sector
guild window from the accepted Editor-owned fixture; full-map compression tests
now understand both supported strides without narrowing wide elevations. The
initial missing/incompatible fixture attempts are retained as failures, not
passed acceptance results. No original fixture or owner project was changed.

Core's maintained context path also defers residency until ACK, unlike the old
provider context sender. Its adaptation therefore needs the reviewed wire-order
semantics for both contexts and stages, under Core authority. The distinct
recovery fence remains in progress: queued full contexts cannot authenticate
the reset epoch with the old type-2 request alone. Core is reviewing a narrowly
correlated request/response recovery extension, retaining strict normal traffic,
nonce/session validation and replacement context/baseline barriers. Its final
wire definition and fail-to-pass tests still require review.

Editor exact dependency adoption passed parity and the affected target
integration, rebuild verifier, installed runtime verification and upgrade
transaction suites: 74 selected tests, two explicit external-input skips.
Editor integration is `7de29aa8c5d2eb68ea3dd056de97608715da5317`.

Fresh alpha 16 Linux/Windows archives were built and independently inspected
from those exact inputs. They remain held for internal automation at
`/home/justin/world-builder-test-builds/alpha15-terrain-sequencing-repair/held-alpha16`.
A fresh disposable attached project exercised the actual packaged authoring
client through nine contexts and 18 stages at the 64-sector cache limit,
including two natural predicted walking activations and ordinary teleports
through centers (2,13), (3,13), (3,12) and (2,12). No missing-reference or fatal
baseline errors occurred. All 1,789 saved map files, 5,495 source files and
70,665 target files remained exact. Owned client/server/GUI processes were
closed cleanly. The owner project and original fixtures were not modified.

Evidence is in `authoring-automation/movement-results.json` and
`authoring-automation/movement-preservation.json` below the persistent repair
directory. This supports cache correctness, not complete gameplay acceptance.

Performance remains held: the 1200-by-850 CPU/Xvfb run showed roughly 3 FPS.
The Editor's `configurePreservationUi` explicitly disables the OpenGL presenter,
input and world replacement paths; history traces that policy to `a6f2263`
(Preservation UI and integer scaling). No rendering policy was changed. A
controlled comparison must distinguish presentation, terrain work and fixture
environment before claiming improved FPS or fixed clipping/flicker. Correlated
Core recovery, paired private gameplay and preservation acceptance remain
outstanding. Do not ask for another owner retest yet.

## Retained evidence

Frozen input: `/home/justin/world-builder-test-builds/core-alpha15-failure-evidence-rZ1hBo`.
It contains the failed client/server logs, launch scripts, reviewed mutation
plan, import verification and receipt. The private launch used Java 21,
`-Xmx2g` and `-Dspoiledmilk.openglPresenter=false`.

Receipt `c0d8051b-bfe9-40ca-86e7-6258c1c84a42` verified 3,581 reviewed writes.
The paired active map package is
`654011db40358d8f2e76f12ba149c85e51d01d55673b81900a97e4ae309af17e`.
Core reported loading the eight owner NPC placements and ground item 3350 at
(127,645), level 0. Visibility, pickup and equip have not passed acceptance.
No map corruption is established. Owner projects, account and histories are
retained; normal Core, public deployment and frozen origins are untouched.

## Shared provider sequencing hypothesis

Reviewed provider: `67361019f28ba52f6acf08a19f6499302a79d8c4`.
All following paths are relative to the independent runtime repository.

- `server/src/com/openrsc/server/GameStateUpdater.java:393`: context residency
  is committed after queueing the context packet.
- The same file at line 517: stage residency is committed only after readiness
  acknowledgement.
- `Client_Base/src/orsc/NativeLayeredTerrainPacketDecoder.java:443`: the client
  commits residency upon decoding the stage, before prebuild/acknowledgement.
- `GameStateUpdater.java:537`: `canActivateNativeTerrainStage` permits context
  activation when the outstanding prediction targets a different center.
- The same file at line 953: clearing the pending stage discards its transaction.

If a stage reached the client but its acknowledgement is delayed, switching to
a different center can build the next context from the server's pre-stage cache
mirror while the client has already changed its ordered cache. Discarding that
stage can leave the two ledgers inconsistent. This is a concrete code hazard,
but the observed initial cache miss is not yet causally reproduced.

Both default caches hold 64 sectors; identities include full, visual and
structural encodings. Capacity alone should trigger payload retransmission,
not references to evicted sectors. Do not increase capacity as the correction.
The frozen log shows a stage miss after context 2, recovery into context 3,
resident counts 25, 41, 60, 64, then another missing reference and baseline crash.
It does not identify the discarded transaction; paired packet replay is needed.

## Target-maintained recovery defect

The private target's `Client_Base/src/orsc/PacketHandler.java` catches
`MissingReferenceException` around lines 735–761 and requests resynchronization,
returning before accepting the incoming layered context. Around line 906,
recovery clears residency/prebuild/stage state. The queued baseline still names
the rejected context and fails strict sequence validation around line 3075.

The pinned provider has no such exception/recovery extension; its missing
reference throws `IllegalStateException`. Its baseline check at
`Client_Base/src/orsc/PacketHandler.java:2943` remains strict. Core owns changes
to its maintained recovery extension. A replacement provider gameplay client
is not an acceptable target fix.

## Proposed reproduction and correction boundaries

First create a deterministic paired ledger replay, using disposable inputs:

1. Replay full terrain, visual/structural halo, predictions and context packets
   through server and client residency implementations. Record ordered identities,
   payload/reference flags, queue success and commit/acknowledgement order.
2. Delay stage acknowledgement, change destination center and cross a boundary;
   deliver the late acknowledgement. Require identical ordered ledgers and no
   missing references after each committed packet.
3. Repeat at capacity with direction reversals, teleport, reconnect and queue
   failures. Distinguish staging/readiness acknowledgement from receipt of cache
   entries; prove abandoned predictions cannot discard already received state.
4. Reproduce recovery with queued baseline, movement and stage packets. An
   explicit recovery fence must prevent stale packets from being consumed as
   the replacement scene. Resume only after a validated replacement context and
   baseline; mismatched sequences outside recovery must still be refused.

If replay confirms the shared hazard, correct provider stage/context sequencing
and test it there; then review/publish the exact provider commit and integrate
through the Editor's locked dependency. Coordinate the distinct maintained
target recovery correction with Core. Do not rewrite integration proof,
disable feature gates, refresh arbitrary hashes or touch the owner's active
project. Re-run complete offline import/preservation and private movement
acceptance before requesting another owner retest.

## Separate presentation findings

The owner reports low FPS, sprite flicker and authoring clipping. Logs include
primitive capture limit 4,096 and halo builds of 586/418 milliseconds. The
OpenGL presenter was disabled. These observations do not establish a cache
cause or explain all flicker; measure presentation separately after reproducing
the crash, without treating a launch flag as proof of resolution.

The authoring item provider explicitly marks Whip 3350 unresolved. Diagnostics
contain 2,118 unresolved items with 4,236 warnings, plus 14 scenery warnings.
Proper inventory/ground visual capture integration is a separate follow-up;
catalog/proof patches cannot manufacture usable visuals. Placement preservation
and gameplay compatibility do not establish authoring visual completeness.

No code changes, tests, builds, launches or target mutations were performed
during this diagnosis. Documentation alone was updated.
