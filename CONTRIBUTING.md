# Contributing

Bug reports and focused pull requests are welcome.

Before opening a change:

1. Read `AGENTS.md` and `docs/ARCHITECTURE.md`.
2. Keep generated workspaces, maps, exports, credentials, databases, logs, and
   release archives out of Git.
3. Route client/server/editor-runtime changes through Spoiled Milk first.
4. Run `./scripts/test.sh` and include the result in the pull request.
5. If synchronized paths changed, identify the matching Core-Framework commit
   and run `./scripts/check-core-parity.sh` against it.

Reports should include the World Builder version, operating system, whether
the legacy or OpenGL renderer was used, exact reproduction steps, and the
relevant `workspace/logs` excerpts. Remove credentials, private server
addresses, and unrelated player data before attaching files.
