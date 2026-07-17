#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SOURCE_DIR="$ROOT_DIR/tools/world-builder/src"
OUTPUT_DIR="$ROOT_DIR/output/world-builder-tools"
CLASSES_DIR="$OUTPUT_DIR/classes"
JAR_PATH="$OUTPUT_DIR/world-builder-tools.jar"

for command_name in javac jar; do
	command -v "$command_name" >/dev/null 2>&1 || {
		printf 'FAIL: Missing required command: %s\n' "$command_name" >&2
		exit 1
	}
done

mapfile -t sources < <(find "$SOURCE_DIR" -type f -name '*.java' -print | sort)
(( ${#sources[@]} > 0 )) || {
	printf 'FAIL: No World Builder Java sources found under %s\n' "$SOURCE_DIR" >&2
	exit 1
}

rm -rf "$OUTPUT_DIR"
mkdir -p "$CLASSES_DIR"

if javac --help 2>&1 | grep -q -- '--release'; then
	javac --release 8 -encoding UTF-8 -d "$CLASSES_DIR" "${sources[@]}"
else
	javac -source 8 -target 8 -encoding UTF-8 -d "$CLASSES_DIR" "${sources[@]}"
fi
jar cfe "$JAR_PATH" com.openrsc.worldbuilder.WorldBuilderCli -C "$CLASSES_DIR" .

printf 'Built %s\n' "$JAR_PATH"
