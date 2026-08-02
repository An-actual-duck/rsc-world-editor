package com.openrsc.worldbuilder;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Strict, sorted, resource-bounded file inventory used by adaptive contracts. */
final class WorldBuilderBoundedInventory {
	private WorldBuilderBoundedInventory() {
	}

	static List<Record> read(Object raw, String operation, int minimum,
		boolean requirePresent) throws WorldBuilderContractException {
		if (!(raw instanceof List)) {
			throw invalid(operation, "File inventory must be an array.");
		}
		List<?> values = (List<?>)raw;
		if (values.size() < minimum
			|| values.size() > WorldBuilderContractLimits.MAX_INVENTORY_ENTRIES) {
			throw new WorldBuilderContractException(
				WorldBuilderErrorCodes.INVENTORY_LIMIT_EXCEEDED, operation,
				"File inventory count is outside the supported range.");
		}
		List<Record> records = new ArrayList<Record>(values.size());
		Set<String> portablePaths = new HashSet<String>();
		Set<String> exactPaths = new HashSet<String>();
		String previousOrder = null;
		long total = 0L;
		for (Object value : values) {
			if (!(value instanceof Map)) {
				throw invalid(operation, "File inventory record must be an object.");
			}
			@SuppressWarnings("unchecked") Map<String,Object> object =
				(Map<String,Object>)value;
			exactKeys(object, operation, "role", "relativePath", "present", "size", "sha256");
			String role = identifier(object.get("role"), operation, "role");
			String relative = string(object.get("relativePath"), operation, "relativePath");
			WorldBuilderPortablePath.require(relative, operation);
			boolean present = bool(object.get("present"), operation, "present");
			long size = integer(object.get("size"), operation, "size");
			String hash = string(object.get("sha256"), operation, "sha256");
			if (size < 0L || size > WorldBuilderContractLimits.MAX_INVENTORY_FILE_BYTES
				|| (present && !isHash(hash))
				|| (!present && (size != 0L || !hash.isEmpty()))
				|| (requirePresent && !present)) {
				throw invalid(operation, "File inventory state is inconsistent or too large.");
			}
			try {
				total = Math.addExact(total, size);
			} catch (ArithmeticException overflow) {
				throw new WorldBuilderContractException(
					WorldBuilderErrorCodes.INVENTORY_LIMIT_EXCEEDED, operation,
					"File inventory byte total overflowed.");
			}
			if (total > WorldBuilderContractLimits.MAX_INVENTORY_TOTAL_BYTES) {
				throw new WorldBuilderContractException(
					WorldBuilderErrorCodes.INVENTORY_LIMIT_EXCEEDED, operation,
					"File inventory byte total exceeds the supported limit.");
			}
			String portable = WorldBuilderPortablePath.collisionKey(relative, operation);
			if (!exactPaths.add(relative) || !portablePaths.add(portable)) {
				throw new WorldBuilderContractException(
					WorldBuilderErrorCodes.INVENTORY_DUPLICATE, operation, relative,
					false, "File inventory contains a duplicate or case-colliding path.",
					"Give every inventoried file one portable path.");
			}
			String order = relative + "\u0000" + role;
			if (previousOrder != null && previousOrder.compareTo(order) >= 0) {
				throw invalid(operation,
					"File inventory is not in canonical relative-path and role order.");
			}
			previousOrder = order;
			records.add(new Record(role, relative, present, size, hash));
		}
		return Collections.unmodifiableList(records);
	}

	static void exactKeys(Map<String,Object> object, String operation, String... keys)
		throws WorldBuilderContractException {
		Set<String> expected = new HashSet<String>(Arrays.asList(keys));
		if (object.size() != expected.size() || !object.keySet().equals(expected)) {
			throw new WorldBuilderContractException(
				WorldBuilderErrorCodes.CONTRACT_KEYS_INVALID, operation,
				"Contract object contains missing or unexpected fields.");
		}
	}

	static String string(Object value, String operation, String field)
		throws WorldBuilderContractException {
		if (!(value instanceof String)) {
			throw invalid(operation, "Contract field is not a string: " + field);
		}
		return (String)value;
	}

	static String identifier(Object value, String operation, String field)
		throws WorldBuilderContractException {
		String identifier = string(value, operation, field);
		if (identifier.length() < 1
			|| identifier.length() > WorldBuilderContractLimits.MAX_IDENTIFIER_CHARS
			|| !identifier.matches("[A-Za-z0-9][A-Za-z0-9._:+-]*")) {
			throw invalid(operation, "Contract identifier is invalid: " + field);
		}
		return identifier;
	}

	static long integer(Object value, String operation, String field)
		throws WorldBuilderContractException {
		if (!(value instanceof Long)) {
			throw invalid(operation, "Contract field is not an integer: " + field);
		}
		return ((Long)value).longValue();
	}

	static boolean bool(Object value, String operation, String field)
		throws WorldBuilderContractException {
		if (!(value instanceof Boolean)) {
			throw invalid(operation, "Contract field is not boolean: " + field);
		}
		return ((Boolean)value).booleanValue();
	}

	static boolean isHash(String value) {
		return value.matches("[0-9a-f]{64}");
	}

	private static WorldBuilderContractException invalid(String operation, String message) {
		return new WorldBuilderContractException(
			WorldBuilderErrorCodes.CONTRACT_VALUE_INVALID, operation, message);
	}

	static final class Record {
		final String role;
		final String relativePath;
		final boolean present;
		final long size;
		final String sha256;

		Record(String role, String relativePath, boolean present, long size, String sha256) {
			this.role = role;
			this.relativePath = relativePath;
			this.present = present;
			this.size = size;
			this.sha256 = sha256;
		}
	}
}
