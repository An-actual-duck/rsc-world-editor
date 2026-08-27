package com.openrsc.worldbuilder;

import java.nio.file.Path;
import java.util.Collections;

/**
 * Explicit secondary discovery for packed Custom_Landscape evidence that
 * coexists with an independently selected normal target.
 */
final class WorldBuilderLegacyLandscapeDiscovery {
	WorldBuilderAdaptiveDiscoveryReport discover(
		Path requestedRoot, String requestedConfigurationRole)
		throws WorldBuilderContractException {
		WorldBuilderReadOnlyTarget target = WorldBuilderReadOnlyTarget.open(requestedRoot);
		WorldBuilderAdapterInspection first =
			WorldBuilderPackedLayoutAdapter.inspectLegacyLandscapeCandidate(
				target, requestedConfigurationRole);
		WorldBuilderAdapterInspection second =
			WorldBuilderPackedLayoutAdapter.inspectLegacyLandscapeCandidate(
				target, requestedConfigurationRole);
		if (!first.stableKey().equals(second.stableKey())) {
			throw WorldBuilderReadOnlyTarget.problem(
				WorldBuilderErrorCodes.DISCOVERY_DRIFT, "target-root",
				"Legacy landscape evidence changed during bounded verification.",
				"Stop target updates and detect the server map again.");
		}
		return WorldBuilderAdaptiveDiscoveryReport.compatible(
			target.root.toString(),
			Collections.singletonList(WorldBuilderPackedLayoutAdapter.ID),
			WorldBuilderAdaptiveDiscoveryReport.absentStateReference(), second,
			Collections.<WorldBuilderAdapterInspection.Check>emptyList());
	}
}
