# World Builder tooling code map

All production classes currently use `com.openrsc.worldbuilder`. This map routes
a change to its primary entry points before a broad search. Supporting classes
may participate across more than one subsystem.

## User entry points

| Responsibility | Primary classes |
| --- | --- |
| CLI parsing and dispatch | `WorldBuilderCli` |
| Desktop Swing surface | `WorldBuilderDesktopLauncher` |
| Desktop action orchestration | `WorldBuilderLauncherModel` |
| Runtime process lifecycle | `WorldBuilderProcessSupervisor`, `WorldBuilderRuntimePreparer`, `WorldBuilderAdaptiveRuntimePreparer` |

## Discovery and target profiles

| Responsibility | Primary classes |
| --- | --- |
| Legacy packed discovery | `WorldBuilderDiscovery`, `WorldBuilderDiscoveryResult`, `WorldBuilderProjectSource` |
| Adaptive discovery | `WorldBuilderAdaptiveDiscovery`, `WorldBuilderAdaptiveDiscoveryReport`, `WorldBuilderReadOnlyTarget` |
| Layout selection | `WorldBuilderLayoutAdapter`, `WorldBuilderLayoutAdapterRegistry`, `WorldBuilderGenericLayeredAdapter`, `WorldBuilderPackedLayoutAdapter` |
| Packed-layout probing | `WorldBuilderPackedSourceLayout`, `WorldBuilderPackedFallbackEvidence`, `WorldBuilderPackedCompatibilityCorrections` |
| Capability and compatibility evidence | `WorldBuilderTargetCapability`, `WorldBuilderCompatibilityEvidence`, `WorldBuilderDiscoveryReconciliation`, `WorldBuilderContentReconciliation` |
| Provider current-composition adoption and read-only T0-T5 classification | `WorldBuilderProviderCatalog`, `WorldBuilderCurrentRuntimeContracts`, and Editor-owned `schema/current-*.schema.json` |
| Legacy layered-base migration | `WorldBuilderLegacyLandscapeDiscovery`, `WorldBuilderLayeredBaseDiscovery`, `WorldBuilderMapMigrationChoice`, `WorldBuilderPackedMigrationChoice` |

## Projects and durable state

| Responsibility | Primary classes |
| --- | --- |
| Adaptive UUID lifecycle | `WorldBuilderAdaptiveProjectLifecycle`, `WorldBuilderAdaptiveProjectLock` |
| Source evidence and revisions | `WorldBuilderSourceSnapshot`, `WorldBuilderProjectRevisionService` |
| Atomic/durable file operations | `WorldBuilderAdaptiveAtomicFiles`, `WorldBuilderAdaptiveDurability`, `WorldBuilderAdaptiveOwnedFiles`, `WorldBuilderHashes`, `WorldBuilderPortablePath` |
| Empty-world creation and promotion | `WorldBuilderEmptyWorldGenerator`, `WorldBuilderCanonicalVoidTerrain`, `WorldBuilderWideElevationPromotion`, `WorldBuilderWideElevationPromotionTransaction` |
| Layered draft persistence | `WorldBuilderLayeredDraftWriter`, `WorldBuilderLayeredTerrainDraftJournal`, `WorldBuilderLayeredReview` |

## Export, import, and target mutation

| Responsibility | Primary classes |
| --- | --- |
| Legacy export/import | `WorldBuilderExporter`, `WorldBuilderExportBundle`, `WorldBuilderExportManifest`, `WorldBuilderImporter`, `WorldBuilderImportReceipt` |
| Adaptive export | `WorldBuilderAdaptiveExporter` |
| Adaptive import execution | `WorldBuilderAdaptiveImporter` |
| Compiled map/runtime mutation plan | `WorldBuilderAdaptiveMutationProfile` |
| Receipts and recovery | `WorldBuilderAdaptiveReceipt`, `WorldBuilderAdaptiveRecovery` |
| Completed server-import Undo (scheduled for removal) | `WorldBuilderAdaptiveUndo` |
| Offline target authority | `WorldBuilderAdaptiveOfflineLease`, `WorldBuilderTargetOfflineLease` |
| Active configuration | `WorldBuilderAdaptiveConfiguration`, `WorldBuilderConfigWriter`, `WorldBuilderLayeredImportConfiguration` |

Target mutation is high risk. Start with the reliability plan and transaction
tests; do not infer safety from a single class.

## Terrain, packages, and conversion

| Responsibility | Primary classes |
| --- | --- |
| Generic layered packages | `WorldBuilderGenericLayeredPackage`, `WorldBuilderLayeredPackage`, `WorldBuilderLayeredExporter` |
| Layered terrain composition | `WorldBuilderLayeredTerrainComposer`, `WorldBuilderRawLayeredTerrainCodec` |
| Packed map decoding | `WorldBuilderPackedMap`, `WorldBuilderPackedTerrainCodec`, `WorldBuilderPackedCoordinateCodec`, `WorldBuilderPackedSceneryDefinitions` |
| Packed conversion | `WorldBuilderPackedConverter`, `WorldBuilderPackedConversionModel`, `WorldBuilderPackedConversionSource` |
| Placement semantics | `WorldBuilderPlacementSemantics` |

## Definitions, visuals, and portable content

| Responsibility | Primary classes |
| --- | --- |
| Definition composition | `WorldBuilderDefinitionComposition`, `WorldBuilderStandaloneDefinitionCatalog` |
| Terrain definitions/materials | `WorldBuilderTerrainDefinitionCatalog`, `WorldBuilderTerrainMaterialProvider` |
| Scenery definitions/models | `WorldBuilderSceneryDefinitionCatalog`, `WorldBuilderSceneryModelProvider` |
| NPC definitions | `WorldBuilderNpcDefinitionProvider` |
| Item visuals | `WorldBuilderItemVisualProvider` |
| Portable providers and bundles | `WorldBuilderPortableProvider`, `WorldBuilderProjectContentBundle`, `WorldBuilderNativeArchiveIndex`, `WorldBuilderBoundedInventory` |

## Regions

| Responsibility | Primary classes |
| --- | --- |
| Region contracts and plans | `WorldBuilderRegionContracts` |
| Copy/Cut/Paste, bundles, and project-local undo | `WorldBuilderRegionSnapshotService` |
| Runtime command bridge | `WorldBuilderRegionControlBridge` |

## Contracts and shared parsing

| Responsibility | Primary classes |
| --- | --- |
| Versioned adaptive validation | `WorldBuilderAdaptiveContracts` and `schema/*.schema.json` |
| Locked provider platform/variant/module/bundle/composition resolution | `WorldBuilderProviderCatalog` consumes `.runtime-provider/current-platform` |
| Editor-owned input-adapter/project/target-ledger/classification validation | `WorldBuilderCurrentRuntimeContracts` and `schema/current-*.schema.json` |
| JSON parsing/canonicalization | `WorldBuilderJsonDocuments` |
| Bounds and error identity | `WorldBuilderContractLimits`, `WorldBuilderContractException`, `WorldBuilderErrorCodes`, `WorldBuilderDiscoveryException` |

See [the test map](../../tests/README.md) for focused commands and coverage
ownership.
