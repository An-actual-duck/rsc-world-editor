# Current Base disposable verifier recovery

This is transaction infrastructure, not permission to activate a real target.
Production apply remains disabled. The Editor adoption requires the published,
locked provider contract and real integration tests before candidate acceptance.

The real verifier runs only disposable copies of the selected server, client,
map and migrated state. Preparation and execution are separate. Before starting
the verifier, the transaction durably records generated state, then its exact
`runtimeVerificationAttempt` in a `verification-prepared` pending receipt. These
generated values do not change the user's confirmed upgrade plan.

The attempt retains only the selected provider `core.jar` and exact verifier
contract in private `provider-tools/`. Both are checked against the selected
composition and confirmed artifact plan; recovery never substitutes a newer
provider or executes target-owned code. Private `control/` contains the
pre-existing supervisor/server/client lease anchors, intent inode and authority.
The receipt binds the authority hash, canonical invocation paths, invocation
identity/hash and retained tool size/hash/mode. File bodies and directory entries
are forced before launch. The separate `prepared.json` records preparation but
does not replace transaction receipt/plan authentication.

Recovery first authenticates the confirmed plan, pending/recovery-required
receipt and any interrupted receipt publication. It then validates the retained
tool against that plan and invokes the provider's exact recovery entry point.
The provider must acquire the supervisor and both actual-child leases, validate
the bound intent, durably revoke delayed starts, and clean up only its exactly
owned credential. The Editor waits for the owned recovery JVM to exit and checks
its closed evidence against the invocation and durable revocation. Busy, unsafe,
timed-out or mismatched recovery leaves `RECOVERY_REQUIRED`; the Editor does not
delete credentials, infer ownership from PIDs or ports, or force discovered
processes. A failed verification remains failed even when cleanup succeeds.

The attempt/control/tool paths are fixed, outside the movable staged release.
After a staging rename, original authority input paths remain historical
identities even when those old paths no longer exist. They are never rewritten
or resealed to point at the published release. Retained composition identity,
tools and live control must remain at their original private paths. Repeated
provider recovery after an already-valid revocation is idempotent; each Editor
recovery invocation writes to a new private output directory.

The installed release carries a portable execution report with a closed,
nonempty successful supervision identity. It survives release relocation and
supports historical verification, but does not grant cleanup authority. Legacy
unbound attempts remain explicit recovery refusals. Uncertain evidence is
retained, including a crash during preparation before a receipt was committed.

Focused coverage lives in `test-world-builder-verifier-authority.py` and
`test-world-builder-installed-runtime-verification.py`; real pair, interrupted
transaction and relocated-release recovery gates require the exact locked
provider. Primitive fixture acceptance is not proof that gameplay ran.
