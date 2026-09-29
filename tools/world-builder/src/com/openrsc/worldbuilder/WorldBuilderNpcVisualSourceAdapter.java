package com.openrsc.worldbuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Static source bridge into neutral, definition-bound NPC visual records. */
final class WorldBuilderNpcVisualSourceAdapter {
	private WorldBuilderNpcVisualSourceAdapter() { }

	/**
	 * Returns neutral records with npcId, definitionPath/Index/Sha256, spriteSlot,
	 * frames, alphaThreshold, nullable cameraWidth/Height, charColour, blueMask,
	 * genderModel, hasCombatFrames and hasSpecialCombatFrames. Each frame binds
	 * imagePath/Sha256, x/y/width/height, offsetX/Y and boundWidth/Height.
	 * All interpreted source and image files are added to immutable evidence.
	 */
	static List<Map<String,Object>> discover(WorldBuilderReadOnlyTarget target,
		WorldBuilderPackedSourceLayout layout,
		List<WorldBuilderReadOnlyTarget.FileState> evidence)
		throws WorldBuilderContractException {
		return new ArrayList<Map<String,Object>>();
	}
}
