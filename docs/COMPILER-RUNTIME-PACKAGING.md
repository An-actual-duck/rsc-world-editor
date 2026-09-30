# Compiler-capable bundled Java

Targeted map upgrades compile the supported map integration against the target's
existing runtime dependencies. The bundled Java therefore needs the actual
system compiler implementation, not just the `javax.tools` API. Supplying a
generic replacement game binary is not an alternative to this requirement.

The packaging and independent candidate inspection commands keep their existing
`--linux-jre` and `--windows-jre` option names for compatibility. Supply reviewed
Java 17+ x64 **JDK** trees (for example, the full matching Temurin distribution).
The Linux tree must also include executable `bin/jimage` for offline inspection
of the Windows runtime image. Existing license, OS, architecture, symlink,
inventory, exact archive bytes, and mode checks still apply.

Both commands run `scripts/world_builder_compiler_runtime.py` before accepting
the inputs. It executes only the reviewed Linux Java toolchain and a fixed
Editor-owned probe in a new temporary directory. The probe calls
`ToolProvider.getSystemJavaCompiler()` and compiles Java 17 with annotation
processing disabled. It must produce a class file successfully. Ambient Java
option and classpath environment variables are removed for this check.

The reviewed Linux `jimage` reads the Windows `lib/modules` image. The inventory
must contain the `java.compiler` API and the `jdk.compiler` implementation under
their actual module names. Merely listing these names in the `release` file's
`MODULES` field does not suffice: an earlier reduced runtime reported compiler
modules there even though they were absent from its runtime image.

Windows inspection is offline module evidence, **not native Windows execution**.
The candidate inspection report explicitly retains native Windows ToolProvider
compilation as pending acceptance. Official vendor checksums and reviewed input
provenance remain part of obtaining trusted toolchains. These checks do not
execute a discovered server's Java executable or build scripts.

Focused verification:

```bash
python3 tests/myworld/test-world-builder-compiler-runtime.py
python3 tests/myworld/test-world-builder-v2-release.py
python3 tests/myworld/test-world-builder-v2-candidate-validation.py
```

Set `WORLD_BUILDER_COMPILER_INPUTS` to an external directory with reviewed
`linux/` and `windows/` inputs to include the real dual-platform acceptance
check. Archive fixtures use explicitly synthetic compiler responses to exercise
packaging/refusal logic; they do not certify a vendor runtime. The separate
compiler test executes an available host compiler and, when supplied, the real
reviewed inputs.
