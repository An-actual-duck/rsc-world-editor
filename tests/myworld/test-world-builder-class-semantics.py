#!/usr/bin/env python3
"""Real recompilation and malformed-input coverage for the bounded comparator."""
import os
from pathlib import Path
import shutil
import struct
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / 'tools/world-builder/src/com/openrsc/worldbuilder/WorldBuilderClassSemantics.java'
JDK = Path(os.environ.get('WORLD_BUILDER_TEST_JDK', Path(shutil.which('javac')).resolve().parents[1]))
JAVA = str(JDK / 'bin/java')
JAVAC = str(JDK / 'bin/javac')
U2 = lambda value: struct.pack('>H', value)
U4 = lambda value: struct.pack('>I', value)
I4 = lambda value: struct.pack('>i', value)

HARNESS = '''
import com.openrsc.worldbuilder.WorldBuilderClassSemantics;
import java.nio.file.*;
import java.io.IOException;
import java.util.Arrays;
public class SemanticsHarness extends ClassLoader {
 public static void main(String[] args) throws Exception {
  byte[] first=Files.readAllBytes(Paths.get(args[1]));
  if (args[0].equals("truncations")) {
   for(int n=0;n<first.length;n++) {
    byte[] part=Arrays.copyOf(first,n);
    try { WorldBuilderClassSemantics.equivalent(part,part); throw new AssertionError("Accepted truncation "+n); }
    catch(IOException expected) { }
   }
   System.out.println("REFUSED_ALL"); return;
  }
  if(args[0].equals("load")) {
   Class<?> type=new SemanticsHarness().defineClass(null,first,0,first.length);
   for(int n:new int[]{0,1,42}) System.out.println(type.getMethod("value",int.class).invoke(null,n));
   return;
  }
  try { System.out.println(WorldBuilderClassSemantics.equivalent(first,Files.readAllBytes(Paths.get(args[2])))); }
  catch(IOException expected) { System.out.println("REFUSED:"+expected.getMessage()); }
 }
}
'''

REAL_SOURCE = '''
import java.lang.annotation.*;
import java.io.*;
import java.util.*;
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE,ElementType.TYPE_USE,ElementType.PARAMETER,ElementType.FIELD,ElementType.METHOD})
@interface Mark { String value() default "stable"; int[] numbers() default {1,2}; Class<?> type() default String.class; }
@Mark("stable")
public class Example<T extends Number> implements Serializable {
 @Mark("field") protected volatile long field = 42L;
 public static final String CONSTANT = "constant";
 class Inner { long read() { return field; } }
 public synchronized @Mark("return") String run(@Mark("param") int choice) throws IOException {
  List<String> words = new ArrayList<>();
  String captured = "lambda-stable";
  Runnable action = () -> words.add(captured);
  action.run();
  int[][] grid = new int[2][3];
  try {
   switch(choice) { case 0: grid[0][0]=10; break; case 1: grid[0][0]=20; break; case 2: grid[0][0]=30; break; default: break; }
   switch(choice) { case -300: throw new IOException("io"); case 1000: return "far"; default: break; }
   @Mark("local") String local = (@Mark("cast") String) words.get(0);
   return local + grid[0][0] + field + 1.25d + 2.5f;
  } catch(IllegalArgumentException exception) { throw new IOException(exception); }
 }
}
'''


def synthetic(wide=False, table=False, extra_attribute=None, cycle=False):
    """Generate verifier-valid classes with reordered CP and changed ldc widths."""
    entries = {
        'name': (1, 'SyntheticExample'), 'this': (7, 'name'),
        'supername': (1, 'java/lang/Object'), 'super': (7, 'supername'),
        'method': (1, 'value'), 'descriptor': (1, '(I)Ljava/lang/String;'),
        'code': (1, 'Code'), 'stackmap': (1, 'StackMapTable'),
        'left_text': (1, 'left'), 'left': (8, 'left_text'),
        'right_text': (1, 'right'), 'right': (8, 'right_text'),
        'default_text': (1, 'default'), 'default': (8, 'default_text'),
    }
    if extra_attribute:
        entries['extra'] = (1, extra_attribute)
    if cycle:
        entries.update({
            'bootstrap_attr': (1, 'BootstrapMethods'),
            'bname': (1, 'bootstrap'), 'bdesc': (1, '()Ljava/lang/Object;'),
            'bnat': (12, 'bname', 'bdesc'), 'bmethod': (10, 'this', 'bnat'),
            'handle': (15, 6, 'bmethod'),
            'cname': (1, 'constant'), 'cdesc': (1, 'Ljava/lang/Object;'),
            'cnat': (12, 'cname', 'cdesc'), 'dynamic': (17, 0, 'cnat'),
        })
    names = list(entries)
    if wide:
        names.reverse()
        for n in range(300):
            name = f'unused{n}'
            entries[name] = (1, name)
            names.insert(0, name)
    index = {name: n + 1 for n, name in enumerate(names)}
    pool = bytearray()
    for name in names:
        tag, *args = entries[name]
        pool.append(tag)
        if tag == 1:
            data = args[0].encode()
            pool += U2(len(data)) + data
        elif tag in (7, 8): pool += U2(index[args[0]])
        elif tag in (10, 12): pool += U2(index[args[0]]) + U2(index[args[1]])
        elif tag == 15: pool += bytes([args[0]]) + U2(index[args[1]])
        elif tag == 17: pool += U2(args[0]) + U2(index[args[1]])
    def attr(name, data): return U2(index[name]) + U4(len(data)) + data
    def ldc(name): return bytes([19]) + U2(index[name]) if wide else bytes([18, index[name]])
    code = bytearray([26, 153, 0, 0])  # iload_0, ifeq
    code += ldc('default') + bytes([87, 26])  # pop, iload_0
    switch = len(code)
    code.append(170 if table else 171)
    while len(code) % 4: code.append(0)
    defaults = len(code)
    code += bytes(4)
    if table:
        code += I4(1) + I4(2)
        targets = [(len(code), 'right'), (len(code) + 4, 'left')]
        code += bytes(8)
    else:
        code += I4(2) + I4(1)
        targets = [(len(code), 'right')]
        code += bytes(4) + I4(7)
        targets.append((len(code), 'left'))
        code += bytes(4)
    positions = {}
    for label in ('left', 'right', 'default'):
        positions[label] = len(code)
        code += ldc(label) + bytes([176])
    code[2:4] = U2(positions['left'] - 1)
    code[defaults:defaults + 4] = I4(positions['default'] - switch)
    for at, label in targets: code[at:at + 4] = I4(positions[label] - switch)
    frames = bytearray(U2(3))
    previous = -1
    for at in positions.values():
        delta = at - previous - 1
        frames += bytes([delta]) if delta <= 63 else bytes([251]) + U2(delta)
        previous = at
    code_attr = U2(1) + U2(1) + U4(len(code)) + code + U2(0) + U2(1) + attr('stackmap', frames)
    method = U2(0x0009) + U2(index['method']) + U2(index['descriptor']) + U2(1) + attr('code', code_attr)
    attrs = []
    if extra_attribute: attrs.append(attr('extra', b''))
    if cycle: attrs.append(attr('bootstrap_attr', U2(1) + U2(index['handle']) + U2(1) + U2(index['dynamic'])))
    return (U4(0xcafebabe) + U2(0) + U2(55 if cycle else 52) + U2(len(names) + 1) + pool
            + U2(0x0021) + U2(index['this']) + U2(index['super']) + U2(0) + U2(0) + U2(1) + method
            + U2(len(attrs)) + b''.join(attrs))


class ClassSemanticsTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temporary = tempfile.TemporaryDirectory(prefix='world-builder-class-coherence-')
        cls.addClassCleanup(cls.temporary.cleanup)
        cls.root = Path(cls.temporary.name)
        cls.classes = cls.root / 'harness'
        cls.classes.mkdir()
        harness = cls.root / 'SemanticsHarness.java'
        harness.write_text(HARNESS)
        subprocess.run([JAVAC, '-source', '8', '-target', '8', '-Xlint:-options', '-d', str(cls.classes), str(SOURCE), str(harness)], check=True, capture_output=True)
        version = subprocess.run([JAVAC, '-version'], text=True, capture_output=True, check=True)
        cls.modern = '1.8.' not in (version.stdout + version.stderr)
        cls.fixtures = {}
        for key, text, debug in (
            ('base', REAL_SOURCE, '-g'), ('nodebug', REAL_SOURCE, '-g:none'),
            ('body', REAL_SOURCE.replace('grid[0][0]=10', 'grid[0][0]=11'), '-g'),
            ('field', REAL_SOURCE.replace('volatile long field', 'transient long field'), '-g'),
            ('annotation', REAL_SOURCE.replace('@Mark("stable")', '@Mark("changed")'), '-g'),
            ('lambda', REAL_SOURCE.replace('lambda-stable', 'lambda-changed'), '-g'),
            ('type_annotation', REAL_SOURCE.replace('@Mark("local")', '@Mark("changed-local")'), '-g'),
            ('annotation_default', REAL_SOURCE.replace('default "stable"', 'default "changed"'), '-g'),
            ('parameter', REAL_SOURCE.replace('choice', 'selection'), '-g'),
            ('surrogate_first', REAL_SOURCE.replace('"constant"', r'"\ud800"'), '-g'),
            ('surrogate_second', REAL_SOURCE.replace('"constant"', r'"\ud801"'), '-g'),
        ):
            cls.fixtures[key] = cls.compile(key, text, debug)

    @classmethod
    def compile(cls, key, text, debug='-g', release=8):
        folder = cls.root / key
        folder.mkdir()
        source = folder / 'Example.java'
        source.write_text(text)
        options = ['--release', str(release)] if cls.modern else ['-source', '8', '-target', '8']
        subprocess.run([JAVAC, *options, debug, '-parameters', '-Xlint:-options', '-d', str(folder), str(source)], check=True, capture_output=True)
        return folder

    def invoke(self, mode, first, second=None):
        command = [JAVA, '-cp', str(self.classes), 'SemanticsHarness', mode, str(first)]
        if second is not None: command.append(str(second))
        return subprocess.run(command, check=True, capture_output=True, text=True, timeout=20).stdout.strip()

    def compare_bytes(self, first, second):
        a, b = self.root / 'first.class', self.root / 'second.class'
        a.write_bytes(first); b.write_bytes(second)
        return self.invoke('compare', a, b)

    def test_debug_only_changes_and_inner_classes_are_equivalent(self):
        for name in ('Example.class', 'Example$Inner.class', 'Mark.class'):
            self.assertEqual('true', self.invoke('compare', self.fixtures['base'] / name, self.fixtures['nodebug'] / name), name)

    def test_body_field_annotation_and_lambda_changes_are_not_equivalent(self):
        for key in ('body', 'field', 'annotation', 'lambda', 'type_annotation', 'parameter'):
            self.assertEqual('false', self.invoke('compare', self.fixtures['base'] / 'Example.class', self.fixtures[key] / 'Example.class'), key)
        self.assertEqual('false', self.invoke('compare', self.fixtures['base'] / 'Mark.class', self.fixtures['annotation_default'] / 'Mark.class'))

    def test_unpaired_surrogate_constants_remain_distinct(self):
        first = self.fixtures['surrogate_first'] / 'Example.class'
        second = self.fixtures['surrogate_second'] / 'Example.class'
        self.assertEqual('true', self.invoke('compare', first, first))
        self.assertEqual('false', self.invoke('compare', first, second))

    def test_constant_pool_reordering_ldc_width_and_branch_switch_offsets(self):
        for table in (False, True):
            self.assertEqual('true', self.compare_bytes(synthetic(table=table), synthetic(wide=True, table=table)))
            # These are valid class files, not just parser-shaped byte fixtures.
            for wide in (False, True):
                path = self.root / 'valid.class'
                path.write_bytes(synthetic(wide=wide, table=table))
                self.assertEqual('left\nright\ndefault', self.invoke('load', path))

    def test_java17_records_nests_and_invokedynamic(self):
        if not self.modern: self.skipTest('Java17 compiler not selected; set WORLD_BUILDER_TEST_JDK')
        text = REAL_SOURCE + '\nrecord Pair(@Mark("record") String name, int value) {}\n'
        first = self.compile('modern-debug', text, '-g', 17)
        second = self.compile('modern-nodebug', text, '-g:none', 17)
        for name in ('Example.class', 'Example$Inner.class', 'Pair.class'):
            self.assertEqual('true', self.invoke('compare', first / name, second / name))
        changed = self.compile('modern-lambda', text.replace('lambda-stable', 'lambda-changed'), '-g', 17)
        self.assertEqual('false', self.invoke('compare', first / 'Example.class', changed / 'Example.class'))

    def test_every_truncation_and_trailing_data_are_refused(self):
        path = self.fixtures['base'] / 'Example.class'
        self.assertEqual('REFUSED_ALL', self.invoke('truncations', path))
        data = path.read_bytes()
        self.assertTrue(self.compare_bytes(data + b'\0', data + b'\0').startswith('REFUSED:'))

    def test_unknown_attribute_and_dynamic_constant_cycle_refused(self):
        for data, expected in ((synthetic(extra_attribute='UnknownSemanticAttribute'), 'Unsupported class attribute'),
                               (synthetic(cycle=True), 'Cyclic')):
            self.assertIn(expected, self.compare_bytes(data, data))

    def test_invalid_magic_version_pool_and_oversize_refused(self):
        original = synthetic()
        invalid_utf = bytearray(original)
        invalid_utf[13] = 0  # UTF null must use modified UTF encoding, not a raw zero.
        cases = [b'BAD!' + original[4:], original[:6] + U2(99) + original[8:],
                 original[:10] + bytes([255]) + original[11:], bytes(invalid_utf), b'x' * (16 * 1024 * 1024 + 1)]
        for data in cases:
            self.assertTrue(self.compare_bytes(data, data).startswith('REFUSED:'))

    def test_reserved_opcode_branch_into_operand_and_bad_switch_refused(self):
        original = synthetic(table=True)
        start = original.index(bytes([26, 153]))
        opcode = bytearray(original); opcode[start] = 202
        branch = bytearray(original); branch[start + 2:start + 4] = U2(1)
        switch = bytearray(original)
        # table default/low/high begin at code offset 12 in this short-ldc fixture.
        switch[start + 20:start + 24] = I4(2147483647)
        for data in (opcode, branch, switch):
            self.assertTrue(self.compare_bytes(bytes(data), bytes(data)).startswith('REFUSED:'))


if __name__ == '__main__': unittest.main()
