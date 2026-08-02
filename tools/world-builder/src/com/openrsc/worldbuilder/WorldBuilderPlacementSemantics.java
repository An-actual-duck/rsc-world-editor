package com.openrsc.worldbuilder;

/** Shared content-only placement identity used for conversion parity checks. */
final class WorldBuilderPlacementSemantics {
	private WorldBuilderPlacementSemantics() {
	}

	static String boundary(int level, int id, int x, int y, int direction) {
		return key("boundary", level, id, x, y, direction);
	}

	static String groundItem(int level, int id, int x, int y, int amount, int respawn) {
		return key("ground-item", level, id, x, y, amount, respawn);
	}

	static String npc(int level, int id, int startX, int startY,
		int minimumX, int minimumY, int maximumX, int maximumY) {
		return key("npc", level, id, startX, startY,
			minimumX, minimumY, maximumX, maximumY);
	}

	static String scenery(int level, int id, int x, int y, int direction) {
		return key("scenery", level, id, x, y, direction);
	}

	private static String key(String family, int... values) {
		StringBuilder result = new StringBuilder(family);
		for (int value : values) result.append('\u0000').append(value);
		return result.toString();
	}
}
