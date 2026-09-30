# Target class/source coherence

Before a targeted upgrade replaces a class compiled from target source, that
source must account for the corresponding active binary. Recompiling the
unmodified source and comparing it with the original class provides a bounded
check for unrelated custom code that would otherwise disappear during rebuild.
The upgrade consumer must enforce this for every replaced class and retain
unreplaced target entries separately; the comparator alone does not establish a
complete upgrade's preservation contract.

`WorldBuilderClassSemantics.equivalent(byte[], byte[])` reads both inputs without
loading or executing target code. It returns `true` only when their supported
canonical class structures match, `false` for a semantic structural difference,
and throws `IOException` for malformed or unsupported structures. Even two
identical malformed byte arrays are parsed and refused.

Supported inputs are ordinary, non-preview Java 8–17 class files. Constant-pool
references are compared by recursively resolved contents, including bootstrap
methods and their arguments, rather than pool indices. Bytecode retains
operations and operands but expresses branch, switch, exception-table, and type
annotation offsets as instruction positions. `ldc`/`ldc_w`, `goto`/`goto_w`, and
wide local operand encodings normalize to the same operation. Constant-pool and
bootstrap ordering and unused constants are not a reason to reject otherwise
matching code.

The comparison retains class versions, access flags, inheritance, interfaces,
field and method order, descriptors, signatures, constants, exception handling,
method parameters, runtime-visible and invisible annotations and defaults,
bootstrap behavior, inner/enclosing/nest metadata, records, permitted subclasses,
and code stack/local limits. Modified UTF strings preserve individual UTF-16
code units, including unpaired surrogates.

Only source/debug attributes (`SourceFile`, `SourceDebugExtension`, line numbers,
local variable debug tables) and structurally parsed `StackMapTable` verifier
metadata are omitted. Unknown attributes, unsupported versions, Java module
descriptors, and obsolete subroutine bytecodes fail closed. A 16 MiB class limit,
bounded node count, bounded recursion, code lengths, pool-reference validation,
cycle detection, exact attribute lengths, and instruction-boundary checks limit
malformed-input processing.

This is conservative structural equivalence, not arbitrary Java program
equivalence across compilers. Different compiler versions can produce different
synthetic methods, method order, or instruction sequences for equivalent source;
those differences are refused. The parser is also not a full JVM verifier: it
does not prove stack types or resolve dependencies. The consumer still needs
trusted compilation, ordinary runtime verification, and the target upgrade's
transaction and recovery checks. A mismatch or unsupported structure must
produce an unresolved pre-mutation report, never bypass preservation checks.

Focused tests compile actual annotated Java classes, lambdas, inner classes and
Java 17 records; compare debug/no-debug builds; and reject changed bodies,
modifiers, annotations, parameter names and lambda constants. Synthetic classes
with reordered pools and widened loads pass JVM loading and exercise relocated
branch and both switch formats. Truncation, unsupported metadata/instructions,
cycles, malformed targets and bounded sizes are refusal cases.

```bash
WORLD_BUILDER_TEST_JDK=/path/to/reviewed/java17-jdk \
  python3 tests/myworld/test-world-builder-class-semantics.py
```

Without an explicit toolchain, the tests use the installed `javac` and matching
`java`; Java 17 cases explicitly skip when only Java 8 is selected.
