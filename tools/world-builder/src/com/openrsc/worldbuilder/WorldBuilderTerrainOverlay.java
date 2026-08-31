package com.openrsc.worldbuilder;

/** Product-defined raw terrain overlays outside the target TileDef inventory. */
final class WorldBuilderTerrainOverlay {
	static final int BLOCKING_BASE_COLOR = 255;

	private WorldBuilderTerrainOverlay() {
	}

	static boolean isBlockingBaseColor(int rawOverlay) {
		return rawOverlay == BLOCKING_BASE_COLOR;
	}
}
