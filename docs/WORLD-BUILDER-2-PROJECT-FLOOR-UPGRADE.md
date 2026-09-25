# Upgrade Project Floors

Select an imported project and choose **Upgrade Project Floors** in Selected Project Actions or the File menu. The preview explains the new copy and asks before creating it. Close the editor first. Cancel leaves the selected project and registry unchanged.

The action creates and selects a new sibling project with its own UUID. It keeps the saved layered map and placements byte-for-byte, preserves captured original target evidence and conversion evidence, and installs current floor definitions and the current private Builder runtime. Imported custom content receives the deterministic append-only standard palette. Native Base projects receive the selected current Base composition; all previously assigned floor IDs must retain their complete material/collision tuple. Standalone projects without imported server content are outside this action.

The original project stays available with its complete source, saved world, backups, exports, runtime state, and import receipts. The copy starts a fresh Builder character and separate backup/receipt history. Neither creating the copy nor canceling the preview changes the external server. Upgrade Target Runtime and Import Map Changes remain separate previewed server operations.

## Transaction and evidence

The operation holds the project and registry locks, verifies the existing registered project and the previewed project fingerprint, copies into a fresh `.staging-<new UUID>-…` directory, and rechecks copied immutable evidence. Only derived provider/content and authoring catalog files change. The new snapshot inventory binds the refreshed definitions. Both runtime JARs must advertise `World-Builder-Floor-Semantics: standard-floors-v1` when those definitions use standard floor metadata.

`source/floor-upgrade/current.json` identifies the immediate parent project. Its fields are `schemaVersion: 1`, `manifestType: world-builder-project-floor-upgrade-origin`, `projectId`, `projectFingerprintSha256`, and `workingFingerprintSha256`. An identical `origin-<parent UUID>.json` retains each historical link across repeated upgrades. The snapshot's `originalFiles` inventory binds these files by SHA-256 under role `project-floor-upgrade-evidence`. The new project does not claim that its parent’s successful import receipts belong to its own UUID.

The staged project must pass normal source, working-package, content, runtime and project verification before publication. Publication uses the existing atomic project creation/registry/active-selection transaction and rollback. Failure leaves the complete original project available and restores the previous registry and active selection. Source corruption, stale preview, missing runtime support, busy project, unsafe paths, exhausted palette IDs, or changes to existing native floor IDs refuse the upgrade.

## Focused validation

`test-world-builder-project-floor-upgrade.py` exercises an unextended captured catalog, the real launcher model, saved-map preservation, repeated lineage, all seven transaction failure milestones, stale/incorrect confirmation, a held project lock, corrupted source and missing runtime semantics. It is registered in the projects test group. An optional actual Swing acceptance run exercises the selected-project button, preview, and Cancel:

```bash
WORLD_BUILDER_FLOOR_UI_ACCEPTANCE=1 \
WORLD_BUILDER_FLOOR_UI_SCREENSHOT=/tmp/project-floor-upgrade \
python3 tests/myworld/test-world-builder-project-floor-upgrade.py
```

The native Base lifecycle fixture also creates and verifies a sibling through the launcher model from disposable public-source evidence. It requires the existing reviewed `WORLD_BUILDER_PRESERVATION_SOURCE_GIT` fixture input and never builds or launches that historical input. The ordinary lifecycle suite continues covering unrelated project creation and recovery behavior.
