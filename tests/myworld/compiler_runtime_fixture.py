"""Synthetic packaging fixtures; these do not claim a real Java compiler.

Actual ToolProvider/module-image acceptance is covered separately by the
compiler runtime tests and by candidate checks against reviewed vendor inputs.
"""

PROBE_SHELL = '''if [[ "${1:-}" == --source ]]; then
  printf '\\312\\376\\272\\276' > "$4/CompiledProbe.class"
  printf 'WORLD_BUILDER_COMPILER_OK\\n'
  exit 0
fi
'''

MODULE_INVENTORY = b'''Module: java.compiler
    module-info.class
    javax/tools/ToolProvider.class
Module: jdk.compiler
    module-info.class
    com/sun/tools/javac/api/JavacTool.class
    com/sun/tools/javac/main/JavaCompiler.class
'''

JIMAGE_SHELL = b'#!/usr/bin/env bash\ncat "$2"\n'
