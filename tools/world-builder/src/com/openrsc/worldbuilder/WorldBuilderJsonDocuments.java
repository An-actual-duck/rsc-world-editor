package com.openrsc.worldbuilder;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Small dependency-free JSON reader and strict authored-overlay validator. */
final class WorldBuilderJsonDocuments {
	private WorldBuilderJsonDocuments() {
	}

	static Map<String,Object> readObject(Path path) throws IOException, WorldBuilderDiscoveryException {
		return readObject(path, false);
	}

	/** Reads copied target definitions without weakening integer-only contract JSON. */
	static Map<String,Object> readTargetDefinitionObject(Path path)
		throws IOException, WorldBuilderDiscoveryException {
		return readObject(path, true);
	}

	private static Map<String,Object> readObject(Path path, boolean allowDecimals)
		throws IOException, WorldBuilderDiscoveryException {
		if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)) {
			throw new WorldBuilderDiscoveryException("Required JSON file is missing or unsafe: " + path);
		}
		long size = Files.size(path);
		if (size < 2L || size > WorldBuilderContractLimits.MAX_JSON_BYTES) {
			throw new WorldBuilderDiscoveryException("JSON file has an invalid size: " + path);
		}
		byte[] bytes = readBounded(path);
		return readObject(bytes, path.toString(), allowDecimals);
	}

	static Map<String,Object> readObject(byte[] bytes, String label)
		throws WorldBuilderDiscoveryException {
		return readObject(bytes, label, false);
	}

	private static Map<String,Object> readObject(byte[] bytes, String label,
		boolean allowDecimals) throws WorldBuilderDiscoveryException {
		if (bytes.length < 2) {
			throw new WorldBuilderDiscoveryException("JSON file has an invalid size: " + label);
		}
		if (bytes.length > WorldBuilderContractLimits.MAX_JSON_BYTES) {
			throw new WorldBuilderDiscoveryException("JSON file has an invalid size: " + label);
		}
		String text;
		try {
			text = StandardCharsets.UTF_8.newDecoder()
				.onMalformedInput(CodingErrorAction.REPORT)
				.onUnmappableCharacter(CodingErrorAction.REPORT)
				.decode(ByteBuffer.wrap(bytes)).toString();
		} catch (CharacterCodingException invalidUtf8) {
			throw new WorldBuilderDiscoveryException("JSON file is not valid UTF-8: " + label);
		}
		Object parsed = new Parser(text, label, allowDecimals).parse();
		if (!(parsed instanceof Map)) {
			throw new WorldBuilderDiscoveryException("JSON document root must be an object: " + label);
		}
		@SuppressWarnings("unchecked") Map<String,Object> object = (Map<String,Object>)parsed;
		return object;
	}

	private static byte[] readBounded(Path path)
		throws IOException, WorldBuilderDiscoveryException {
		ByteArrayOutputStream output = new ByteArrayOutputStream(8192);
		try (java.io.InputStream input = Files.newInputStream(
			path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
			byte[] buffer = new byte[8192];
			int read;
			while ((read = input.read(buffer)) >= 0) {
				if (read == 0) continue;
				if ((long)output.size() + read > WorldBuilderContractLimits.MAX_JSON_BYTES) {
					throw new WorldBuilderDiscoveryException(
						"JSON file has an invalid size: " + path);
				}
				output.write(buffer, 0, read);
			}
		}
		return output.toByteArray();
	}

	static String pretty(Object value) {
		StringBuilder output = new StringBuilder(64 * 1024);
		write(value, output, 0);
		return output.append('\n').toString();
	}

	/** Canonical UTF-8 JSON text: sorted keys, preserved array order, no whitespace. */
	static String canonical(Object value) {
		StringBuilder output = new StringBuilder(64 * 1024);
		writeCanonical(value, output);
		return output.toString();
	}

	private static void writeCanonical(Object value, StringBuilder output) {
		if (value == null) {
			output.append("null");
		} else if (value instanceof String) {
			writeString((String)value, output);
		} else if (value instanceof Boolean || value instanceof Byte
			|| value instanceof Short || value instanceof Integer
			|| value instanceof Long) {
			output.append(value);
		} else if (value instanceof BigDecimal) {
			output.append(((BigDecimal)value).toPlainString());
		} else if (value instanceof Map) {
			@SuppressWarnings("unchecked") Map<String,Object> object =
				(Map<String,Object>)value;
			output.append('{');
			int index = 0;
			for (Map.Entry<String,Object> entry
				: new TreeMap<String,Object>(object).entrySet()) {
				if (index++ > 0) output.append(',');
				writeString(entry.getKey(), output);
				output.append(':');
				writeCanonical(entry.getValue(), output);
			}
			output.append('}');
		} else if (value instanceof List) {
			@SuppressWarnings("unchecked") List<Object> array = (List<Object>)value;
			output.append('[');
			for (int index = 0; index < array.size(); index++) {
				if (index > 0) output.append(',');
				writeCanonical(array.get(index), output);
			}
			output.append(']');
		} else {
			throw new IllegalArgumentException(
				"Unsupported JSON value: " + value.getClass().getName());
		}
	}

	private static void write(Object value, StringBuilder output, int depth) {
		if (value == null) {
			output.append("null");
		} else if (value instanceof String) {
			writeString((String)value, output);
		} else if (value instanceof Boolean || value instanceof Byte
			|| value instanceof Short || value instanceof Integer
			|| value instanceof Long) {
			output.append(value);
		} else if (value instanceof BigDecimal) {
			output.append(((BigDecimal)value).toPlainString());
		} else if (value instanceof Map) {
			@SuppressWarnings("unchecked") Map<String,Object> object =
				(Map<String,Object>)value;
			output.append('{');
			int index = 0;
			for (Map.Entry<String,Object> entry
				: new TreeMap<String,Object>(object).entrySet()) {
				if (index++ > 0) output.append(',');
				line(output, depth + 1);
				writeString(entry.getKey(), output);
				output.append(": ");
				write(entry.getValue(), output, depth + 1);
			}
			if (!object.isEmpty()) line(output, depth);
			output.append('}');
		} else if (value instanceof List) {
			@SuppressWarnings("unchecked") List<Object> array = (List<Object>)value;
			output.append('[');
			for (int index = 0; index < array.size(); index++) {
				if (index > 0) output.append(',');
				line(output, depth + 1);
				write(array.get(index), output, depth + 1);
			}
			if (!array.isEmpty()) line(output, depth);
			output.append(']');
		} else {
			throw new IllegalArgumentException(
				"Unsupported JSON value: " + value.getClass().getName());
		}
	}

	private static void line(StringBuilder output, int depth) {
		output.append('\n');
		for (int index = 0; index < depth; index++) output.append("  ");
	}

	private static void writeString(String value, StringBuilder output) {
		validateUnicode(value);
		output.append('"');
		for (int index = 0; index < value.length(); index++) {
			char character = value.charAt(index);
			switch (character) {
				case '"': output.append("\\\""); break;
				case '\\': output.append("\\\\"); break;
				case '\b': output.append("\\b"); break;
				case '\f': output.append("\\f"); break;
				case '\n': output.append("\\n"); break;
				case '\r': output.append("\\r"); break;
				case '\t': output.append("\\t"); break;
				default:
					if (character < 0x20) {
						output.append(String.format("\\u%04x", (int)character));
					} else {
						output.append(character);
					}
			}
		}
		output.append('"');
	}

	private static void validateUnicode(String value) {
		for (int index = 0; index < value.length(); index++) {
			char character = value.charAt(index);
			if (Character.isHighSurrogate(character)) {
				if (index + 1 >= value.length()
					|| !Character.isLowSurrogate(value.charAt(index + 1))) {
					throw new IllegalArgumentException("JSON string has an unpaired surrogate.");
				}
				index++;
			} else if (Character.isLowSurrogate(character)) {
				throw new IllegalArgumentException("JSON string has an unpaired surrogate.");
			}
		}
	}

	static int validateSceneryLocs(Path path) throws IOException, WorldBuilderDiscoveryException {
		return validateSceneryLocs(path, false, false);
	}

	static int validateOrderedSceneryLocs(Path path)
		throws IOException, WorldBuilderDiscoveryException {
		return validateSceneryLocs(path, true, true);
	}

	private static int validateSceneryLocs(Path path, boolean allowRepeatedLocations,
		boolean allowInertFields)
		throws IOException, WorldBuilderDiscoveryException {
		List<Object> entries = requiredRootArray(readObject(path), "sceneries", path);
		java.util.HashSet<String> keys = new java.util.HashSet<String>();
		for (Object entry : entries) {
			Map<String,Object> object = allowInertFields
				? requiredObject(entry, path, "id", "pos", "direction")
				: exactObject(entry, path, "id", "pos", "direction");
			int id = integer(object.get("id"), path); int direction = integer(object.get("direction"), path);
			int[] position = position(object.get("pos"), path, allowInertFields);
			boolean repeated = !keys.add(position[0] + "," + position[1]);
			if (id < 0 || direction < 0 || repeated && !allowRepeatedLocations) {
				throw new WorldBuilderDiscoveryException("Invalid or duplicate scenery location in " + path);
			}
		}
		return entries.size();
	}

	static int validateBoundaryLocs(Path path)
		throws IOException, WorldBuilderDiscoveryException {
		List<Object> entries = requiredRootArray(readObject(path), "boundaries", path);
		java.util.HashSet<String> keys = new java.util.HashSet<String>();
		for (Object entry : entries) {
			Map<String,Object> object = exactObject(
				entry, path, "id", "pos", "direction");
			int id = integer(object.get("id"), path);
			int direction = integer(object.get("direction"), path);
			int[] position = position(object.get("pos"), path);
			if (id < 0 || direction < 0
				|| !keys.add(position[0] + "," + position[1] + "," + direction)) {
				throw new WorldBuilderDiscoveryException(
					"Invalid or duplicate boundary location in " + path);
			}
		}
		return entries.size();
	}

	static int validateGroundItemLocs(Path path)
		throws IOException, WorldBuilderDiscoveryException {
		List<Object> entries = requiredRootArray(readObject(path), "grounditems", path);
		java.util.HashSet<String> keys = new java.util.HashSet<String>();
		for (Object entry : entries) {
			Map<String,Object> object = exactObject(
				entry, path, "id", "pos", "amount", "respawn");
			int id = integer(object.get("id"), path);
			int amount = integer(object.get("amount"), path);
			int respawn = integer(object.get("respawn"), path);
			int[] position = position(object.get("pos"), path);
			if (id < 0 || amount < 1 || respawn < 0
				|| !keys.add(id + "," + position[0] + "," + position[1])) {
				throw new WorldBuilderDiscoveryException(
					"Invalid or duplicate ground-item location in " + path);
			}
		}
		return entries.size();
	}

	static int validateSceneryRemovals(Path path) throws IOException, WorldBuilderDiscoveryException {
		List<Object> entries = requiredRootArray(readObject(path), "scenery_removals", path);
		java.util.HashSet<String> keys = new java.util.HashSet<String>();
		for (Object entry : entries) {
			Map<String,Object> object = exactObject(entry, path, "pos");
			int[] position = position(object.get("pos"), path);
			if (!keys.add(position[0] + "," + position[1])) {
				throw new WorldBuilderDiscoveryException("Duplicate scenery removal in " + path);
			}
		}
		return entries.size();
	}

	static int validateNpcLocs(Path path) throws IOException, WorldBuilderDiscoveryException {
		return validateNpcArray(path, "npclocs", true);
	}

	static int validateBaseNpcLocs(Path path)
		throws IOException, WorldBuilderDiscoveryException {
		return validateNpcArray(path, "npclocs", false);
	}

	static int validateNpcRemovals(Path path) throws IOException, WorldBuilderDiscoveryException {
		return validateNpcArray(path, "npc_removals", true);
	}

	private static int validateNpcArray(Path path, String root, boolean requireUnique)
		throws IOException, WorldBuilderDiscoveryException {
		List<Object> entries = requiredRootArray(readObject(path), root, path);
		java.util.HashSet<String> keys = new java.util.HashSet<String>();
		for (Object entry : entries) {
			Map<String,Object> object = exactObject(entry, path, "id", "start", "min", "max");
			int id = integer(object.get("id"), path); int[] start = position(object.get("start"), path);
			int[] minimum = position(object.get("min"), path); int[] maximum = position(object.get("max"), path);
			if (id < 0 || minimum[0] > start[0] || start[0] > maximum[0]
				|| minimum[1] > start[1] || start[1] > maximum[1]
				|| (requireUnique
					&& !keys.add(id + "," + start[0] + "," + start[1]))) {
				throw new WorldBuilderDiscoveryException("Invalid or duplicate NPC location in " + path);
			}
		}
		return entries.size();
	}

	private static List<Object> requiredRootArray(Map<String,Object> root, String name, Path path)
		throws WorldBuilderDiscoveryException {
		if (root.size() != 1 || !root.containsKey(name) || !(root.get(name) instanceof List)) {
			throw new WorldBuilderDiscoveryException("JSON file must contain only the '" + name + "' array: " + path);
		}
		@SuppressWarnings("unchecked") List<Object> entries = (List<Object>)root.get(name);
		return entries;
	}

	private static Map<String,Object> exactObject(Object value, Path path, String... names)
		throws WorldBuilderDiscoveryException {
		Map<String,Object> object = requiredObject(value, path, names);
		if (object.size() != names.length) throw new WorldBuilderDiscoveryException("Unexpected fields in " + path);
		return object;
	}

	private static Map<String,Object> requiredObject(Object value, Path path,
		String... names) throws WorldBuilderDiscoveryException {
		if (!(value instanceof Map)) throw new WorldBuilderDiscoveryException("Expected an object in " + path);
		@SuppressWarnings("unchecked") Map<String,Object> object = (Map<String,Object>)value;
		for (String name : names) if (!object.containsKey(name)) throw new WorldBuilderDiscoveryException("Missing field '" + name + "' in " + path);
		return object;
	}

	private static int[] position(Object value, Path path) throws WorldBuilderDiscoveryException {
		return position(value, path, false);
	}

	private static int[] position(Object value, Path path, boolean allowInertFields)
		throws WorldBuilderDiscoveryException {
		Map<String,Object> object = allowInertFields
			? requiredObject(value, path, "X", "Y")
			: exactObject(value, path, "X", "Y");
		return new int[] {integer(object.get("X"), path), integer(object.get("Y"), path)};
	}

	private static int integer(Object value, Path path) throws WorldBuilderDiscoveryException {
		if (!(value instanceof Long) || ((Long)value).longValue() < Integer.MIN_VALUE
			|| ((Long)value).longValue() > Integer.MAX_VALUE) {
			throw new WorldBuilderDiscoveryException("Expected a 32-bit integer in " + path);
		}
		return ((Long)value).intValue();
	}

	private static final class Parser {
		private final String text, label;
		private final boolean allowDecimals;
		private int at, values;
		Parser(String text, String label, boolean allowDecimals) {
			this.text=text; this.label=label; this.allowDecimals=allowDecimals;
		}
		Object parse() throws WorldBuilderDiscoveryException {
			Object value=value(0); whitespace(); if(at!=text.length())fail("Trailing data"); return value;
		}
		private Object value(int depth) throws WorldBuilderDiscoveryException {
			if(depth>WorldBuilderContractLimits.MAX_JSON_DEPTH
				||++values>WorldBuilderContractLimits.MAX_JSON_VALUES)fail("JSON complexity limit exceeded"); whitespace(); if(at>=text.length())fail("Unexpected end");
			char c=text.charAt(at); if(c=='{')return object(depth+1);if(c=='[')return array(depth+1);if(c=='\"')return string();
			if(c=='-'||(c>='0'&&c<='9'))return number();if(literal("true"))return Boolean.TRUE;if(literal("false"))return Boolean.FALSE;if(literal("null"))return null;
			fail("Unexpected token");return null;
		}
		private Map<String,Object> object(int depth) throws WorldBuilderDiscoveryException {
			at++;LinkedHashMap<String,Object> result=new LinkedHashMap<String,Object>();whitespace();if(take('}'))return result;
			while(true){whitespace();if(at>=text.length()||text.charAt(at)!='\"')fail("Object key must be a string");String key=string();whitespace();if(!take(':'))fail("Missing ':'");
				if(result.containsKey(key))fail("Duplicate object key");Object value=value(depth);result.put(key,value);whitespace();if(take('}'))return result;if(!take(','))fail("Missing ','");}
		}
		private List<Object> array(int depth) throws WorldBuilderDiscoveryException {
			at++;ArrayList<Object> result=new ArrayList<Object>();whitespace();if(take(']'))return result;
			while(true){result.add(value(depth));whitespace();if(take(']'))return result;if(!take(','))fail("Missing ','");}
		}
		private String string() throws WorldBuilderDiscoveryException {
			at++;StringBuilder result=new StringBuilder();while(at<text.length()){char c=text.charAt(at++);if(c=='\"'){try{validateUnicode(result.toString());}catch(IllegalArgumentException invalid){fail("Unpaired Unicode surrogate");}return result.toString();}if(c<' ')fail("Control character in string");
				if(c!='\\'){result.append(c);continue;}if(at>=text.length())fail("Incomplete escape");char escaped=text.charAt(at++);switch(escaped){case '\"':case '\\':case '/':result.append(escaped);break;
					case 'b':result.append('\b');break;case 'f':result.append('\f');break;case 'n':result.append('\n');break;case 'r':result.append('\r');break;case 't':result.append('\t');break;
					case 'u':if(at+4>text.length())fail("Incomplete Unicode escape");try{result.append((char)Integer.parseInt(text.substring(at,at+4),16));}catch(NumberFormatException bad){fail("Invalid Unicode escape");}at+=4;break;default:fail("Invalid escape");}}
			fail("Unterminated string");return null;
		}
		private Object number() throws WorldBuilderDiscoveryException {
			int start=at;
			if(text.charAt(at)=='-')at++;
			if(at>=text.length())fail("Incomplete number");
			if(text.charAt(at)=='0')at++;
			else{
				if(text.charAt(at)<'1'||text.charAt(at)>'9')fail("Invalid number");
				while(at<text.length()&&Character.isDigit(text.charAt(at)))at++;
			}
			boolean decimal=false;
			if(at<text.length()&&text.charAt(at)=='.'){
				decimal=true;at++;
				int fractionStart=at;
				while(at<text.length()&&Character.isDigit(text.charAt(at)))at++;
				if(at==fractionStart)fail("Invalid fractional number");
			}
			if(at<text.length()&&(text.charAt(at)=='e'||text.charAt(at)=='E')){
				decimal=true;at++;
				if(at<text.length()&&(text.charAt(at)=='+'||text.charAt(at)=='-'))at++;
				int exponentStart=at;
				while(at<text.length()&&Character.isDigit(text.charAt(at)))at++;
				if(at==exponentStart)fail("Invalid exponent");
			}
			String token=text.substring(start,at);
			if(decimal){
				if(!allowDecimals)fail("Contract numbers must be integers");
				try{return new BigDecimal(token);}catch(NumberFormatException bad){
					fail("Decimal number is invalid");return null;
				}
			}
			try{return Long.valueOf(token);}catch(NumberFormatException bad){
				fail("Integer out of range");return null;
			}
		}
		private boolean literal(String value){if(text.regionMatches(at,value,0,value.length())){at+=value.length();return true;}return false;}
		private void whitespace(){while(at<text.length()){char c=text.charAt(at);if(c==' '||c=='\n'||c=='\r'||c=='\t')at++;else break;}}
		private boolean take(char expected){if(at<text.length()&&text.charAt(at)==expected){at++;return true;}return false;}
		private void fail(String message) throws WorldBuilderDiscoveryException {throw new WorldBuilderDiscoveryException(message+" at byte/character "+at+" in "+label);}
	}
}
