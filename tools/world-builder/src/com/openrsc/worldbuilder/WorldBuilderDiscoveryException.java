package com.openrsc.worldbuilder;

/** A safe, user-actionable refusal to treat a folder as a World Builder project. */
public final class WorldBuilderDiscoveryException extends Exception {
	public WorldBuilderDiscoveryException(String message) {
		super(message);
	}

	public WorldBuilderDiscoveryException(String message, Throwable cause) {
		super(message, cause);
	}
}
