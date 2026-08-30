# World Builder test map

The no-argument `./scripts/test.sh` command remains the complete repository
gate. During implementation, select the smallest group, file, or exact method
that exercises the change, then run the broader risk-appropriate gate before
integration.

## Commands

```bash
./scripts/test.sh --list
./scripts/test.sh --group transactions
./scripts/test.sh --file test-world-builder-adaptive-transactions.py
./scripts/test.sh --test \
  test-world-builder-adaptive-transactions.py::AdaptiveTransactionTest.test_export_preview_import_and_exact_undo
./scripts/test.sh --group workflow --verbose
```

Successful output is concise. Complete output is captured and printed on
failure; `--verbose` also prints it on success. Every invocation builds the
tooling once and syntax-checks packaged and repository shell scripts.

## Named groups

| Group | Primary ownership | Important files |
| --- | --- | --- |
| `workflow` | Manager/worktree safety and maintainability tooling | `test-world-builder-ai-workspaces.py`, `test-world-builder-maintainability-tooling.py` |
| `discovery` | Contracts, target layouts, packed conversion, providers, content bundles | Adaptive contracts/discovery, legacy discovery, migration choice, packed conversion, portable/NPC providers, bundle fixtures |
| `projects` | UUID lifecycle, runtime preparation, supervision, revisions, wide elevation | Adaptive lifecycle plus focused runtime/project modules |
| `transactions` | Export, import, failure rollback, recovery, offline checks, historical transaction compatibility | Adaptive transactions, legacy export/import, migration choice |
| `packaging` | Product isolation, candidate inspection, release gate, archives | Product generations/independence and v2 candidate/release modules |
| `updater` | Frozen v1 and adaptive v2 update behavior | v1 and v2 updater modules |
| `candidate` | Exact focused candidate boundary | The release-candidate selection formerly duplicated in its wrapper script |
| `all` | Complete repository test inventory | Every `tests/myworld/test-world-builder-*.py` file |

Groups intentionally overlap where a contract crosses ownership. The runner
deduplicates repeated file selections within one invocation.

## Current expensive boundaries

| Module | Current scope | Observed baseline |
| --- | --- | ---: |
| `test-world-builder-adaptive-project-lifecycle.py` | 69 tests spanning creation, reopen, runtime, content, regions, migration, and durability | 114.278 seconds |
| `test-world-builder-adaptive-transactions.py` | 41 tests spanning export/import/Undo/recovery and filesystem failure boundaries | 196.682 seconds |

These modules still contain shared fixture builders and embedded Java harnesses.
They are scheduled for extraction and subsystem splitting. Until then, prefer
an exact method while iterating and the complete module at subsystem handoff.

## Native and optional checks

- `test-world-builder-native-runtime-integration.py` needs explicit reviewed
  runtime/provider paths and a usable Linux `DISPLAY`; otherwise it skips.
- Native PowerShell updater tests require `WORLD_BUILDER_PWSH` or `pwsh`.
- Exact runtime-layout discovery integration requires `RUNTIME_PROVIDER_DIR`.
- Candidate and release validation have additional clean-source, reviewed-JRE,
  archive, and owner-native requirements documented in `docs/RELEASING.md`.

Tests must use temporary fixtures. Never point them at an installed Builder, a
real private-server directory, or a user project.
