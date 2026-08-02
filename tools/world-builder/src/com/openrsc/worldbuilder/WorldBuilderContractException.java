package com.openrsc.worldbuilder;

/** A structured, read-only contract refusal with a stable error code. */
final class WorldBuilderContractException extends Exception {
	private final String code;
	private final String operation;
	private final String relativePath;
	private final boolean mutationOccurred;
	private final String nextStep;

	WorldBuilderContractException(String code, String operation, String message) {
		this(code, operation, "", false, message,
			"Correct the contract and validate it again.", null);
	}

	WorldBuilderContractException(String code, String operation, String relativePath,
		boolean mutationOccurred, String message, String nextStep) {
		this(code, operation, relativePath, mutationOccurred, message, nextStep, null);
	}

	WorldBuilderContractException(String code, String operation, String relativePath,
		boolean mutationOccurred, String message, String nextStep, Throwable cause) {
		super(message, cause);
		if (!WorldBuilderErrorCodes.isStable(code)) {
			throw new IllegalArgumentException("Unknown World Builder error code: " + code);
		}
		this.code = code;
		this.operation = operation;
		this.relativePath = relativePath;
		this.mutationOccurred = mutationOccurred;
		this.nextStep = nextStep;
	}

	String code() {
		return code;
	}

	String operation() {
		return operation;
	}

	String relativePath() {
		return relativePath;
	}

	boolean mutationOccurred() {
		return mutationOccurred;
	}

	String nextStep() {
		return nextStep;
	}
}
