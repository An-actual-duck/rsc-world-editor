# Targeted integration verification

The targeted runtime consumer requires a compiler-capable Java 17 runtime.
Run its tests with both `java` and `javac` from the reviewed JDK on `PATH`:

```sh
PATH=/path/to/reviewed-jdk-17/bin:$PATH \
  python3 tests/myworld/test-world-builder-target-map-integration.py \
  TargetMapIntegrationTest TargetMapTransactionTest
```

These fixtures exercise source/binary coherence, retained custom callbacks,
transitive field ABI consumers in plugin archives, dependency collisions,
multi-release overlaps, exact generated payload recovery, floor prefix checks,
and ordinary map import after a targeted upgrade. They create disposable
synthetic targets and never use a live server.

To additionally exercise every recipe in the production descriptor against
provider-owned reconstructed sources, use an exact reviewed provider checkout
with its matching provider binaries available:

```sh
PATH=/path/to/reviewed-jdk-17/bin:$PATH \
WORLD_BUILDER_TARGET_MAP_PROVIDER=/path/to/reviewed-runtime-provider \
  python3 tests/myworld/test-world-builder-target-map-integration.py \
  TargetMapProviderConsumerTest
```

This test reverses map features in provider source to reconstruct an older host,
binds its fixture-only preimage hashes, and invokes the real Editor consumer.
It does not widen the production adapter's accepted source hashes and does not
compile external reference source. It checks v5 maps, elevation 65535, plugin
field linkage, content placement identity, and retained custom callbacks. It
complements prerequisite source review; it cannot prove compatibility with
arbitrary modified target sources.

The first production adapter covers the reviewed older native layered source
lineage. Original Preservation and other unrecognized source integrations
remain unsupported for targeted upgrade until their own reviewed adapters
exist. Whole-game composition replacement is not a fallback. Historical
receipt recovery remains available.

Import and upgrade have separate transactions. Upgrade installs generated
map integration source and binaries; later map import must not install the
editor's captured presentation bundle or replace target NPC/item/object data.
Floor additions must preserve the original server XML bytes and match the
client's existing literal floor prefix. Dynamic floor initialization and
unresolved prior floor overrides require an explicit compatibility refusal.

## Integration evidence, 2026-09-30

Provider: `deb55301702dc80f49497ac722341895145363e1`.
Editor implementation handoff: `c836fdab5654138b25d1ad8a32d3b6bb158fce51`.
Preservation regression handoff: `61ab39917a547409971e8027c57c30ab2eaae693`.

On the reviewed Temurin 17.0.20+8 Linux compiler, manager integration passed
all 22 targeted tests (including exact embedding and the provider adapter)
and all eight standard-floor tests in 64 seconds. The migration-choice file
passed all 19 tests on the implementation handoff.

All 68 adaptive transaction cases have passing evidence: 50 unchanged cases
from the initial staged run, then 18 affected cases against the final production
consumer. Four strengthened callback, archive-preservation, metadata, and
overlay-255 cases were rerun successfully. All three public composition/refusal
and historical-recovery dispatch checks passed. There were no skips in these
selected files. This is focused affected-area verification, not a full release
gate; the historical full-tested baseline is recorded in TESTING-POLICY.md.
