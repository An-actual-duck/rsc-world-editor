package com.openrsc.worldbuilder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Immutable registry of repository-owned compiled discovery adapters. */
final class WorldBuilderLayoutAdapterRegistry {
	private final Map<String,WorldBuilderLayoutAdapter> adapters;

	private WorldBuilderLayoutAdapterRegistry(List<WorldBuilderLayoutAdapter> values) {
		List<WorldBuilderLayoutAdapter> sorted =
			new ArrayList<WorldBuilderLayoutAdapter>(values);
		Collections.sort(sorted, new java.util.Comparator<WorldBuilderLayoutAdapter>() {
			@Override
			public int compare(WorldBuilderLayoutAdapter left, WorldBuilderLayoutAdapter right) {
				return left.id().compareTo(right.id());
			}
		});
		Map<String,WorldBuilderLayoutAdapter> indexed =
			new LinkedHashMap<String,WorldBuilderLayoutAdapter>();
		for (WorldBuilderLayoutAdapter adapter : sorted) {
			if (indexed.put(adapter.id(), adapter) != null) {
				throw new IllegalArgumentException("Duplicate layout adapter: " + adapter.id());
			}
		}
		adapters = Collections.unmodifiableMap(indexed);
	}

	static WorldBuilderLayoutAdapterRegistry standard() {
		return new WorldBuilderLayoutAdapterRegistry(java.util.Arrays.asList(
			new WorldBuilderGenericLayeredAdapter(),
			new WorldBuilderPackedLayoutAdapter()));
	}

	WorldBuilderLayoutAdapter named(String id) {
		return adapters.get(id);
	}

	List<WorldBuilderLayoutAdapter> adapters() {
		return Collections.unmodifiableList(
			new ArrayList<WorldBuilderLayoutAdapter>(adapters.values()));
	}

	List<String> ids() {
		return Collections.unmodifiableList(new ArrayList<String>(adapters.keySet()));
	}
}
