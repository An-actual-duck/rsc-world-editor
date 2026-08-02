package com.openrsc.worldbuilder;

/** A structured, read-only contract refusal with a stable error code. */
final class WorldBuilderContractException extends Exception {
	private final String code;
	private final String operation;
	private final String projectId;
	private final String adapterId;
	private final String relativePath;
	private final String provenance;
	private final String expected;
	private final String observed;
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
		this(code, operation, "", "", relativePath, "", "", "", mutationOccurred,
			message, nextStep, cause);
	}

	WorldBuilderContractException(String code, String operation, String projectId,
		String adapterId, String relativePath, String provenance, String expected,
		String observed, boolean mutationOccurred, String message, String nextStep,
		Throwable cause) {
		super(message, cause);
		if (!WorldBuilderErrorCodes.isStable(code)) {
			throw new IllegalArgumentException("Unknown World Builder error code: " + code);
		}
		this.code = code;
		this.operation = required(operation, "operation");
		this.projectId = optional(projectId);
		this.adapterId = optional(adapterId);
		this.relativePath = optional(relativePath);
		this.provenance = optional(provenance);
		this.expected = optional(expected);
		this.observed = optional(observed);
		this.mutationOccurred = mutationOccurred;
		this.nextStep = required(nextStep, "next step");
	}

	String code() {
		return code;
	}

	String operation() {
		return operation;
	}

	String projectId() {
		return projectId;
	}

	String adapterId() {
		return adapterId;
	}

	String relativePath() {
		return relativePath;
	}

	String provenance() {
		return provenance;
	}

	String expected() {
		return expected;
	}

	String observed() {
		return observed;
	}

	boolean mutationOccurred() {
		return mutationOccurred;
	}

	String nextStep() {
		return nextStep;
	}

	private static String optional(String value) {
		return value == null ? "" : value;
	}

	private static String required(String value, String field) {
		if (value == null || value.isEmpty()) {
			throw new IllegalArgumentException("World Builder refusal has no " + field + ".");
		}
		return value;
	}
}
