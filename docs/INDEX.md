# Documentation index

Use this page to choose the smallest authoritative document for a task. A
historical or design record should not override current source, accepted release
evidence, or an active product decision.

## Current operating documents

| Document | Status and authority | Read when |
| --- | --- | --- |
| [Architecture](ARCHITECTURE.md) | Current product boundaries and safety invariants | Changing durable state, projects, target transactions, packaging, or runtime parity |
| [Development](DEVELOPMENT.md) | Current build, focused-test, sandbox, and dependency workflow | Building, testing, or preparing local fixtures |
| [Releasing](RELEASING.md) | Current release-gate procedure; examples may name historical versions | Building candidates or production archives |
| [AI Workspaces](AI-WORKSPACES.md) | Detailed manager/worker workflow | Activating, handing off, integrating, rescuing, or releasing work |
| [Independent Runtime Provider](RUNTIME-PROVIDER.md) | Current repository boundary and adoption route | Work crosses into `rsc-world-editor-runtime` |
| [Automatic Updates](AUTO-UPDATES.md) | Current updater safety boundary | Changing update selection, replacement, rollback, or preservation |
| [Current Runtime Upgrade Review](WORLD-BUILDER-2-CURRENT-RUNTIME-UPGRADE-REVIEW.md) | Active replacement architecture, public adaptability, and acceptance plan | Upgrading Preservation-like or advanced targets to the current platform generation |
| [Reliability and Iteration Plan](WORLD-BUILDER-2-RELIABILITY-AND-ITERATION-PLAN.md) | Active ordered worklist and historical incident record | Planning the adaptable runtime replacement, map-import reliability, or maintainability work |
| [Maintainability Audit](WORLD-BUILDER-2-MAINTAINABILITY-AUDIT.md) | Measured 2026-08-30 audit and recommendations | Reducing storage, test, navigation, documentation, or AI cost |

## Implemented workflow and contract references

| Document | Status and authority | Read when |
| --- | --- | --- |
| [Adaptive Map Workflow](ADAPTIVE-MAP-WORKFLOW.md) | Mixed implemented map/project contract, historical phases, and planned upgrade reconciliation | Discovery, project lifecycle, export/import/recovery, or target safety changes |
| [Format-Aware Discovery](WORLD-BUILDER-2-FORMAT-AWARE-DISCOVERY.md) | Implemented discovery/profile design; some release labels are historical | Adding or diagnosing target layouts and content reconciliation |
| [Map Migration and History](WORLD-BUILDER-2-MAP-MIGRATION-AND-HISTORY.md) | Implemented map/history work with superseded target-runtime policy called out | Legacy landscape migration, project revisions, or GUI transaction actions |
| [Region Snapshots](WORLD-BUILDER-2-REGION-SNAPSHOTS.md) | Implemented region engine and interactive workflow with remaining polish | Region Copy/Cut/Paste, portable bundles, or Region Paste Undo |
| [Custom Content Bundles](WORLD-BUILDER-2-CUSTOM-CONTENT-BUNDLES.md) | Versioned implemented bundle contract | Definition/asset portability and bundle compatibility |
| [Portable Item Providers](WORLD-BUILDER-2-PORTABLE-ITEM-PROVIDERS.md) | Implemented provider contract and compatibility guidance | Item visuals or portable provider discovery |

## Product direction and future design

| Document | Status and authority | Read when |
| --- | --- | --- |
| [Product Goals and Readiness](WORLD-BUILDER-2-PRODUCT-GOALS.md) | Living direction; not implementation authorization by itself | Prioritizing editor experience and future capabilities |
| [Custom Materials](WORLD-BUILDER-2-CUSTOM-MATERIALS.md) | Detailed design, not fully implemented | Planning creator-supplied floor/wall materials |

## Historical correction and release evidence

| Location | Status and authority | Read when |
| --- | --- | --- |
| [Adaptive landscape correction](RUNTIME-PROVIDER-ADAPTIVE-LANDSCAPE-CORRECTION.md) | Historical cross-repository correction record | Auditing that specific runtime change |
| [Adaptive login correction](RUNTIME-PROVIDER-ADAPTIVE-LOGIN-CORRECTION.md) | Historical cross-repository correction record | Auditing that specific runtime change |
| [`docs/releases/`](releases/) | Immutable accepted validation records corresponding to tags | Auditing or reproducing one published release |

Repository entry documents remain [README](../README.md),
[Contributing](../CONTRIBUTING.md), [Source Provenance](../SOURCE-PROVENANCE.md),
and the [Changelog](../CHANGELOG.md). Release records preserve historical facts;
they are not the source of current development status.
