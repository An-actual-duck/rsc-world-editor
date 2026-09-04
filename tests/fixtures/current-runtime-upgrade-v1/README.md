# Current-runtime upgrade foundation fixtures

These are synthetic, target-content-neutral recognition fixtures. The fixture
set manifest seals every Editor contract, provider extension, and target file
by SHA-256. No target script, source, archive, map, client, or database from a
user server is included or executed.

The targets isolate every classifier boundary: exact Preservation-like T0,
discardable generated T1 state, typed configuration T2A, portable declarative
T2B data, maintained and unported T3 extensions, plugin/core ABI coupling at
T4, an unknown binary-only T5 refusal, and a trusted synthetic managed-N
ledger.

Platform, variant, bundle, module, and composition contracts remain owned by
the exact revision in `runtime-provider.lock`. The provider extension here is a
sealed test input: it adds one synthetic module and an explicit test-only
installable overlay to the locked provider catalog. It is non-production and
non-installable as shipped; tests apply it only inside a temporary directory.
The provider's real foundation-only Base and Advanced compositions remain
`installable: false` and must never authorize upgrade or activation.
