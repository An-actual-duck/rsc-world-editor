# Contributing

Bug reports and focused pull requests are welcome.

Before opening a change:

1. Read `AGENTS.md` and `docs/ARCHITECTURE.md`.
2. Keep generated workspaces, maps, exports, credentials, databases, logs, and
   release archives out of Git.
3. Keep the change scoped to this repository. If it requires external
   client/server runtime work, describe that compatibility dependency
   separately; do not transplant or monitor another project's topic branches.
4. Run `./scripts/test.sh` and include the result in the pull request.
5. Change synchronized paths or `core-framework.lock` only as part of an
   explicitly assigned exact-commit dependency update, then run
   `./scripts/check-core-parity.sh` against that selected revision.
6. Preserve the frozen v1 and active v2 product identities; do not create an
   automatic updater or workspace migration between them.

Reports should include the World Builder version, operating system, whether
the legacy or OpenGL renderer was used, exact reproduction steps, and the
relevant `workspace/logs` excerpts. Remove credentials, private server
addresses, and unrelated player data before attaching files.
