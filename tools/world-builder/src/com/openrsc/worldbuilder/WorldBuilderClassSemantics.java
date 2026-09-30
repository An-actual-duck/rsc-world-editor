package com.openrsc.worldbuilder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Conservative class-file comparison for a target-source recompilation check.
 * Never loads target classes. This is not arbitrary Java program equivalence or
 * a replacement for JVM verification. Unsupported structures fail closed.
 */
public final class WorldBuilderClassSemantics {
    private static final int MAX_BYTES = 16 * 1024 * 1024;
    private static final int MAX_NODES = 1000000;
    private static final int MAX_DEPTH = 64;

    private WorldBuilderClassSemantics() { }

    public static boolean equivalent(byte[] original, byte[] recompiled) throws IOException {
        // Parse both even when identical: malformed data must not pass by equality.
        return Arrays.equals(new Parser(original).canonical(), new Parser(recompiled).canonical());
    }

    private static final class Input {
        final byte[] bytes;
        int at;
        Input(byte[] bytes) { this.bytes = bytes; }
        int left() { return bytes.length - at; }
        int u1() throws IOException {
            require(left() >= 1, "Truncated class structure");
            return bytes[at++] & 255;
        }
        int u2() throws IOException { return u1() << 8 | u1(); }
        int i4() throws IOException { return u2() << 16 | u2(); }
        long i8() throws IOException { return ((long) i4() << 32) | (i4() & 0xffffffffL); }
        byte[] take(int length) throws IOException {
            require(length >= 0 && length <= left(), "Invalid class structure length");
            byte[] value = Arrays.copyOfRange(bytes, at, at + length);
            at += length;
            return value;
        }
        void end() throws IOException { require(left() == 0, "Trailing class structure bytes"); }
    }

    private static final class Ref {
        final int index;
        Ref(int index) { this.index = index; }
    }

    private static final class BootstrapRef {
        final int index;
        BootstrapRef(int index) { this.index = index; }
    }

    private static final class Position {
        final Code code;
        final int offset;
        final boolean allowEnd;
        Position(Code code, int offset, boolean allowEnd) {
            this.code = code;
            this.offset = offset;
            this.allowEnd = allowEnd;
        }
        int instruction() throws IOException {
            Integer value = code.positions.get(offset);
            require(value != null && (allowEnd || offset != code.length), "Invalid bytecode target");
            return value;
        }
    }

    private static final class Code {
        final Map<Integer, Integer> positions = new HashMap<Integer, Integer>();
        final int length;
        Code(int length) { this.length = length; }
    }

    private static final class Parser {
        final Input input;
        Object[] pool;
        int[] tags;
        byte[][] poolHashes;
        byte[] poolState;
        List<Object> bootstraps;
        byte[][] bootstrapHashes;
        byte[] bootstrapState;
        int nodes;
        int major;

        Parser(byte[] bytes) throws IOException {
            require(bytes != null && bytes.length <= MAX_BYTES, "Class exceeds byte limit");
            input = new Input(bytes);
        }

        List<Object> node(Object... values) throws IOException {
            require(++nodes <= MAX_NODES, "Class exceeds structure limit");
            return new ArrayList<Object>(Arrays.asList(values));
        }

        byte[] canonical() throws IOException {
            require(input.i4() == 0xcafebabe, "Invalid class magic");
            int minor = input.u2();
            major = input.u2();
            require(major >= 52 && major <= 61 && minor == 0, "Unsupported class version");
            readPool();
            List<Object> result = node("Class", minor, major, input.u2(), ref(input.u2(), 7), optionalRef(input.u2(), 7));
            result.add(refList(input, 7));
            result.add(members("field"));
            result.add(members("method"));
            result.add(attributes(input, "class", null, 0));
            input.end();
            if (bootstraps == null) bootstraps = node();
            bootstrapHashes = new byte[bootstraps.size()][];
            bootstrapState = new byte[bootstraps.size()];
            // Validate unreferenced entries too, without making their ordering semantic.
            for (int i = 1; i < pool.length; i++) if (tags[i] != 0) poolHash(i, 0);
            for (int i = 0; i < bootstraps.size(); i++) bootstrapHash(i, 0);
            return digest(result, 0);
        }

        void readPool() throws IOException {
            int count = input.u2();
            require(count > 0, "Empty constant pool");
            pool = new Object[count];
            tags = new int[count];
            poolHashes = new byte[count][];
            poolState = new byte[count];
            for (int i = 1; i < count; i++) {
                int tag = input.u1();
                tags[i] = tag;
                switch (tag) {
                    case 1:
                        int length = input.u2();
                        byte[] utf = input.take(length);
                        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
                        DataOutputStream stream = new DataOutputStream(encoded);
                        stream.writeShort(length);
                        stream.write(utf);
                        pool[i] = new DataInputStream(new ByteArrayInputStream(encoded.toByteArray())).readUTF();
                        ByteArrayOutputStream canonicalUtf = new ByteArrayOutputStream();
                        new DataOutputStream(canonicalUtf).writeUTF((String) pool[i]);
                        require(Arrays.equals(encoded.toByteArray(), canonicalUtf.toByteArray()), "Noncanonical modified UTF constant");
                        break;
                    case 3: case 4: pool[i] = node(tag, input.i4()); break;
                    case 5: case 6:
                        pool[i] = node(tag, input.i8());
                        require(i + 1 < count, "Invalid wide constant pool entry");
                        i++;
                        break;
                    case 7: case 8: case 16:
                        pool[i] = node(tag, new Ref(input.u2()));
                        break;
                    case 9: case 10: case 11: case 12:
                        pool[i] = node(tag, new Ref(input.u2()), new Ref(input.u2()));
                        break;
                    case 15: pool[i] = node(tag, input.u1(), new Ref(input.u2())); break;
                    case 17: case 18:
                        require(tag != 17 || major >= 55, "Unsupported dynamic constant version");
                        pool[i] = node(tag, new BootstrapRef(input.u2()), new Ref(input.u2()));
                        break;
                    default: throw new IOException("Unsupported constant pool tag: " + tag);
                }
            }
            for (int i = 1; i < count; i++) {
                int tag = tags[i];
                if (tag == 0 || tag == 1 || (tag >= 3 && tag <= 6)) continue;
                List<?> value = (List<?>) pool[i];
                if (tag == 7 || tag == 8 || tag == 16) check(((Ref) value.get(1)).index, 1);
                else if (tag >= 9 && tag <= 11) {
                    check(((Ref) value.get(1)).index, 7);
                    check(((Ref) value.get(2)).index, 12);
                } else if (tag == 12) {
                    check(((Ref) value.get(1)).index, 1);
                    check(((Ref) value.get(2)).index, 1);
                } else if (tag == 15) {
                    int kind = (Integer) value.get(1);
                    require(kind >= 1 && kind <= 9, "Invalid method handle kind");
                    int index = ((Ref) value.get(2)).index;
                    if (kind <= 4) check(index, 9);
                    else if (kind == 9) check(index, 11);
                    else if (kind == 6 || kind == 7) check(index, 10, 11);
                    else check(index, 10);
                } else check(((Ref) value.get(2)).index, 12);
            }
        }

        void check(int index, int... permitted) throws IOException {
            require(index > 0 && index < tags.length, "Invalid constant pool reference");
            for (int tag : permitted) if (tags[index] == tag) return;
            throw new IOException("Invalid constant pool reference type");
        }
        Ref ref(int index, int... permitted) throws IOException { check(index, permitted); return new Ref(index); }
        Object optionalRef(int index, int... permitted) throws IOException { return index == 0 ? Integer.valueOf(0) : ref(index, permitted); }
        String utf(int index) throws IOException { check(index, 1); return (String) pool[index]; }
        List<Object> refList(Input in, int... permitted) throws IOException {
            List<Object> result = node();
            int count = in.u2();
            for (int i = 0; i < count; i++) result.add(ref(in.u2(), permitted));
            return result;
        }
        List<Object> members(String context) throws IOException {
            List<Object> result = node();
            int count = input.u2();
            Set<String> identities = new HashSet<String>();
            for (int i = 0; i < count; i++) {
                int flags = input.u2();
                String name = utf(input.u2()), descriptor = utf(input.u2());
                require(identities.add(name + "\u0000" + descriptor), "Duplicate class member");
                result.add(node(flags, name, descriptor, attributes(input, context, null, 0)));
            }
            return result;
        }

        List<Object> attributes(Input in, String context, Code code, int depth) throws IOException {
            require(depth <= MAX_DEPTH, "Class attribute nesting limit");
            int count = in.u2();
            List<Object> result = node();
            Set<String> seen = new HashSet<String>();
            for (int i = 0; i < count; i++) {
                String name = utf(in.u2());
                Input value = new Input(in.take(in.i4()));
                require(seen.add(name), "Duplicate attribute: " + name);
                Object content;
                if (name.equals("SourceFile")) {
                    context(context, "class"); utf(value.u2()); content = null;
                } else if (name.equals("SourceDebugExtension")) {
                    context(context, "class"); value.take(value.left()); content = null;
                } else if (name.equals("LineNumberTable")) {
                    context(context, "code");
                    int size = value.u2();
                    for (int j = 0; j < size; j++) { position(code, value.u2(), false).instruction(); value.u2(); }
                    content = null;
                } else if (name.equals("LocalVariableTable") || name.equals("LocalVariableTypeTable")) {
                    context(context, "code");
                    int size = value.u2();
                    for (int j = 0; j < size; j++) {
                        int start = value.u2(), length = value.u2();
                        position(code, start, false).instruction(); position(code, start + length, true).instruction();
                        utf(value.u2()); utf(value.u2()); value.u2();
                    }
                    content = null;
                } else if (name.equals("StackMapTable")) {
                    context(context, "code"); stackMap(value, code); content = null;
                } else if (name.equals("Code")) {
                    context(context, "method"); content = code(value, depth + 1);
                } else if (name.equals("ConstantValue")) {
                    context(context, "field"); content = ref(value.u2(), 3, 4, 5, 6, 8);
                } else if (name.equals("Signature")) {
                    context(context, "class", "field", "method", "record"); content = ref(value.u2(), 1);
                } else if (name.equals("Synthetic") || name.equals("Deprecated")) {
                    context(context, "class", "field", "method"); content = node();
                } else if (name.equals("Exceptions")) {
                    context(context, "method"); content = refList(value, 7);
                } else if (name.equals("NestHost")) {
                    context(context, "class"); content = ref(value.u2(), 7);
                } else if (name.equals("NestMembers") || name.equals("PermittedSubclasses")) {
                    context(context, "class"); content = refList(value, 7);
                } else if (name.equals("EnclosingMethod")) {
                    context(context, "class"); content = node(ref(value.u2(), 7), optionalRef(value.u2(), 12));
                } else if (name.equals("InnerClasses")) {
                    context(context, "class");
                    List<Object> entries = node(); int size = value.u2();
                    for (int j = 0; j < size; j++) entries.add(node(ref(value.u2(), 7), optionalRef(value.u2(), 7), optionalRef(value.u2(), 1), value.u2()));
                    content = entries;
                } else if (name.equals("MethodParameters")) {
                    context(context, "method");
                    List<Object> entries = node(); int size = value.u1();
                    for (int j = 0; j < size; j++) entries.add(node(optionalRef(value.u2(), 1), value.u2()));
                    content = entries;
                } else if (name.equals("RuntimeVisibleAnnotations") || name.equals("RuntimeInvisibleAnnotations")) {
                    context(context, "class", "field", "method", "record");
                    content = annotations(value, depth + 1);
                } else if (name.equals("RuntimeVisibleParameterAnnotations") || name.equals("RuntimeInvisibleParameterAnnotations")) {
                    context(context, "method");
                    List<Object> entries = node(); int size = value.u1();
                    for (int j = 0; j < size; j++) entries.add(annotations(value, depth + 1));
                    content = entries;
                } else if (name.equals("RuntimeVisibleTypeAnnotations") || name.equals("RuntimeInvisibleTypeAnnotations")) {
                    content = typeAnnotations(value, context, code, depth + 1);
                } else if (name.equals("AnnotationDefault")) {
                    context(context, "method"); content = element(value, depth + 1);
                } else if (name.equals("BootstrapMethods")) {
                    context(context, "class");
                    require(bootstraps == null, "Duplicate bootstrap methods");
                    bootstraps = node(); int size = value.u2();
                    for (int j = 0; j < size; j++) {
                        bootstraps.add(node(ref(value.u2(), 15), refList(value, 3, 4, 5, 6, 7, 8, 15, 16, 17)));
                    }
                    content = null; // Referenced entries are embedded in constant canonicalization.
                } else if (name.equals("Record")) {
                    context(context, "class");
                    List<Object> entries = node(); int size = value.u2();
                    for (int j = 0; j < size; j++) entries.add(node(ref(value.u2(), 1), ref(value.u2(), 1), attributes(value, "record", null, depth + 1)));
                    content = entries;
                } else throw new IOException("Unsupported class attribute: " + name);
                value.end();
                if (content != null) result.add(node(name, content));
            }
            Collections.sort(result, new Comparator<Object>() {
                public int compare(Object first, Object second) {
                    return ((String) ((List<?>) first).get(0)).compareTo((String) ((List<?>) second).get(0));
                }
            });
            return result;
        }

        void context(String actual, String... allowed) throws IOException {
            for (String candidate : allowed) if (actual.equals(candidate)) return;
            throw new IOException("Attribute in unsupported context: " + actual);
        }

        Object code(Input in, int depth) throws IOException {
            int stack = in.u2(), locals = in.u2();
            int length = in.i4();
            require(length > 0 && length <= 65535, "Invalid code length");
            Input bytes = new Input(in.take(length));
            Code code = new Code(length);
            List<Object> instructions = node();
            while (bytes.left() > 0) {
                int offset = bytes.at;
                code.positions.put(offset, instructions.size());
                int op = bytes.u1();
                List<Object> instruction = node(op);
                if (op == 16) instruction.add((int) (byte) bytes.u1());
                else if (op == 17) instruction.add((int) (short) bytes.u2());
                else if (op == 18 || op == 19 || op == 20) {
                    instruction.set(0, op == 19 ? 18 : op);
                    instruction.add(op == 20 ? ref(bytes.u2(), 5, 6, 17) : ref(op == 18 ? bytes.u1() : bytes.u2(), 3, 4, 7, 8, 15, 16, 17));
                } else if ((op >= 21 && op <= 25) || (op >= 54 && op <= 58)) instruction.add(bytes.u1());
                else if (op == 132) { instruction.add(bytes.u1()); instruction.add((int) (byte) bytes.u1()); }
                else if ((op >= 153 && op <= 167) || op == 198 || op == 199) instruction.add(position(code, offset + (short) bytes.u2(), false));
                else if (op == 200) { instruction.set(0, 167); instruction.add(position(code, (long) offset + bytes.i4(), false)); }
                else if (op == 170 || op == 171) {
                    while (bytes.at % 4 != 0) require(bytes.u1() == 0, "Nonzero switch padding");
                    instruction.add(position(code, (long) offset + bytes.i4(), false));
                    if (op == 170) {
                        int low = bytes.i4(), high = bytes.i4();
                        long size = (long) high - low + 1;
                        require(size > 0 && size <= bytes.left() / 4, "Invalid tableswitch bounds");
                        instruction.add(low); instruction.add(high);
                        for (int i = 0; i < size; i++) instruction.add(position(code, (long) offset + bytes.i4(), false));
                    } else {
                        int size = bytes.i4(); require(size >= 0 && size <= bytes.left() / 8, "Invalid lookupswitch bounds");
                        int previous = 0;
                        for (int i = 0; i < size; i++) {
                            int key = bytes.i4(); require(i == 0 || key > previous, "Unsorted lookupswitch"); previous = key;
                            instruction.add(node(key, position(code, (long) offset + bytes.i4(), false)));
                        }
                    }
                } else if (op >= 178 && op <= 181) instruction.add(ref(bytes.u2(), 9));
                else if (op >= 182 && op <= 184) instruction.add(op == 182 ? ref(bytes.u2(), 10) : ref(bytes.u2(), 10, 11));
                else if (op == 185) {
                    instruction.add(ref(bytes.u2(), 11)); int count = bytes.u1();
                    require(count > 0 && bytes.u1() == 0, "Invalid invokeinterface operands"); instruction.add(count);
                } else if (op == 186) {
                    instruction.add(ref(bytes.u2(), 18)); require(bytes.u2() == 0, "Invalid invokedynamic operands");
                } else if (op == 187 || op == 189 || op == 192 || op == 193) instruction.add(ref(bytes.u2(), 7));
                else if (op == 188) { int type = bytes.u1(); require(type >= 4 && type <= 11, "Invalid newarray type"); instruction.add(type); }
                else if (op == 196) {
                    int nested = bytes.u1();
                    require((nested >= 21 && nested <= 25) || (nested >= 54 && nested <= 58) || nested == 132, "Unsupported wide instruction");
                    instruction.set(0, nested); instruction.add(bytes.u2());
                    if (nested == 132) instruction.add((int) (short) bytes.u2());
                } else if (op == 197) {
                    instruction.add(ref(bytes.u2(), 7)); int dimensions = bytes.u1();
                    require(dimensions > 0, "Invalid multianewarray dimensions"); instruction.add(dimensions);
                } else require((op >= 0 && op <= 15) || (op >= 26 && op <= 53) || (op >= 59 && op <= 131)
                        || (op >= 133 && op <= 152) || (op >= 172 && op <= 177) || op == 190 || op == 191 || op == 194 || op == 195,
                        "Unsupported bytecode instruction: " + op);
                instructions.add(instruction);
            }
            code.positions.put(length, instructions.size());
            List<Object> exceptions = node(); int count = in.u2();
            for (int i = 0; i < count; i++) {
                int start = in.u2(), end = in.u2(), handler = in.u2();
                require(start < end, "Invalid code exception range");
                exceptions.add(node(position(code, start, false), position(code, end, true), position(code, handler, false), optionalRef(in.u2(), 7)));
            }
            return node(stack, locals, instructions, exceptions, attributes(in, "code", code, depth));
        }

        Position position(Code code, long offset, boolean allowEnd) throws IOException {
            require(code != null && offset >= 0 && offset <= code.length, "Invalid code position");
            return new Position(code, (int) offset, allowEnd);
        }

        void stackMap(Input in, Code code) throws IOException {
            int count = in.u2(), previous = -1;
            for (int i = 0; i < count; i++) {
                int tag = in.u1(), delta;
                if (tag <= 63) delta = tag;
                else if (tag <= 127) { delta = tag - 64; verification(in, code); }
                else if (tag == 247) { delta = in.u2(); verification(in, code); }
                else if (tag >= 248 && tag <= 251) delta = in.u2();
                else if (tag >= 252 && tag <= 254) { delta = in.u2(); for (int j = 0; j < tag - 251; j++) verification(in, code); }
                else if (tag == 255) {
                    delta = in.u2(); int size = in.u2();
                    for (int j = 0; j < size; j++) verification(in, code);
                    size = in.u2(); for (int j = 0; j < size; j++) verification(in, code);
                } else throw new IOException("Invalid stack map frame");
                previous += delta + 1; position(code, previous, false).instruction();
            }
        }
        void verification(Input in, Code code) throws IOException {
            int tag = in.u1(); require(tag <= 8, "Invalid stack map verification type");
            if (tag == 7) ref(in.u2(), 7);
            else if (tag == 8) position(code, in.u2(), false).instruction();
        }

        List<Object> annotations(Input in, int depth) throws IOException {
            List<Object> result = node(); int size = in.u2();
            for (int i = 0; i < size; i++) result.add(annotation(in, depth));
            return result;
        }
        Object annotation(Input in, int depth) throws IOException {
            require(depth <= MAX_DEPTH, "Annotation nesting limit");
            List<Object> result = node(ref(in.u2(), 1)); int count = in.u2();
            for (int i = 0; i < count; i++) result.add(node(ref(in.u2(), 1), element(in, depth + 1)));
            return result;
        }
        Object element(Input in, int depth) throws IOException {
            require(depth <= MAX_DEPTH, "Annotation nesting limit");
            int tag = in.u1(); Object value;
            switch (tag) {
                case 'B': case 'C': case 'I': case 'S': case 'Z': value = ref(in.u2(), 3); break;
                case 'D': value = ref(in.u2(), 6); break;
                case 'F': value = ref(in.u2(), 4); break;
                case 'J': value = ref(in.u2(), 5); break;
                case 's': case 'c': value = ref(in.u2(), 1); break;
                case 'e': value = node(ref(in.u2(), 1), ref(in.u2(), 1)); break;
                case '@': value = annotation(in, depth + 1); break;
                case '[':
                    List<Object> values = node(); int count = in.u2();
                    for (int i = 0; i < count; i++) values.add(element(in, depth + 1));
                    value = values; break;
                default: throw new IOException("Unknown annotation element tag");
            }
            return node(tag, value);
        }
        Object typeAnnotations(Input in, String context, Code code, int depth) throws IOException {
            List<Object> result = node(); int size = in.u2();
            for (int i = 0; i < size; i++) {
                int tag = in.u1(); List<Object> item = node(tag);
                switch (tag) {
                    case 0x00: context(context, "class"); item.add(in.u1()); break;
                    case 0x01: context(context, "method"); item.add(in.u1()); break;
                    case 0x10: context(context, "class"); item.add(in.u2()); break;
                    case 0x11: context(context, "class"); item.add(in.u1()); item.add(in.u1()); break;
                    case 0x12: context(context, "method"); item.add(in.u1()); item.add(in.u1()); break;
                    case 0x13: context(context, "field", "record"); break;
                    case 0x14: case 0x15: context(context, "method"); break;
                    case 0x16: context(context, "method"); item.add(in.u1()); break;
                    case 0x17: context(context, "method"); item.add(in.u2()); break;
                    case 0x40: case 0x41:
                        context(context, "code"); int count = in.u2();
                        for (int j = 0; j < count; j++) {
                            int start = in.u2(), length = in.u2();
                            item.add(node(position(code, start, false), position(code, start + length, true), in.u2()));
                        }
                        break;
                    case 0x42: context(context, "code"); item.add(in.u2()); break;
                    case 0x43: case 0x44: case 0x45: case 0x46:
                        context(context, "code"); item.add(position(code, in.u2(), false)); break;
                    case 0x47: case 0x48: case 0x49: case 0x4a: case 0x4b:
                        context(context, "code"); item.add(position(code, in.u2(), false)); item.add(in.u1()); break;
                    default: throw new IOException("Unsupported annotation target");
                }
                List<Object> path = node(); int count = in.u1();
                for (int j = 0; j < count; j++) {
                    int kind = in.u1(), argument = in.u1();
                    require(kind <= 3 && (kind == 3 || argument == 0), "Invalid type annotation path");
                    path.add(node(kind, argument));
                }
                item.add(path); item.add(annotation(in, depth + 1)); result.add(item);
            }
            return result;
        }

        byte[] poolHash(int index, int depth) throws IOException {
            require(depth <= MAX_DEPTH, "Constant reference nesting limit");
            require(index > 0 && index < pool.length && tags[index] != 0, "Invalid constant reference");
            if (poolHashes[index] != null) return poolHashes[index];
            require(poolState[index] == 0, "Cyclic constant reference");
            poolState[index] = 1;
            poolHashes[index] = digest(pool[index], depth + 1);
            poolState[index] = 2;
            return poolHashes[index];
        }
        byte[] bootstrapHash(int index, int depth) throws IOException {
            require(depth <= MAX_DEPTH && index >= 0 && index < bootstraps.size(), "Invalid bootstrap reference");
            if (bootstrapHashes[index] != null) return bootstrapHashes[index];
            require(bootstrapState[index] == 0, "Cyclic bootstrap reference");
            bootstrapState[index] = 1;
            bootstrapHashes[index] = digest(bootstraps.get(index), depth + 1);
            bootstrapState[index] = 2;
            return bootstrapHashes[index];
        }
        byte[] digest(Object value, int depth) throws IOException {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            write(new DataOutputStream(bytes), value, depth);
            try { return MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()); }
            catch (NoSuchAlgorithmException impossible) { throw new IOException("SHA-256 unavailable", impossible); }
        }
        void write(DataOutputStream out, Object value, int depth) throws IOException {
            require(depth <= MAX_DEPTH, "Canonical structure nesting limit");
            if (value instanceof Ref) { out.writeByte(1); out.write(poolHash(((Ref) value).index, depth + 1)); }
            else if (value instanceof BootstrapRef) { out.writeByte(2); out.write(bootstrapHash(((BootstrapRef) value).index, depth + 1)); }
            else if (value instanceof Position) { out.writeByte(3); out.writeInt(((Position) value).instruction()); }
            else if (value instanceof Integer) { out.writeByte(4); out.writeInt((Integer) value); }
            else if (value instanceof Long) { out.writeByte(5); out.writeLong((Long) value); }
            else if (value instanceof String) { out.writeByte(6); out.writeUTF((String) value); }
            else if (value instanceof List<?>) {
                out.writeByte(7); out.writeInt(((List<?>) value).size());
                for (Object element : (List<?>) value) write(out, element, depth + 1);
            } else throw new IOException("Unsupported canonical value");
        }
    }

    private static void require(boolean valid, String message) throws IOException {
        if (!valid) throw new IOException(message);
    }
}
