#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SOURCE_DIR="$ROOT_DIR/tools/world-builder/src"
OUTPUT_DIR="$ROOT_DIR/output/world-builder-tools"
CLASSES_DIR="$OUTPUT_DIR/classes"
JAR_PATH="$OUTPUT_DIR/world-builder-tools.jar"
RUNTIME_ALLOWLIST="$ROOT_DIR/release/world-builder-v2/RUNTIME-ASSET-ALLOWLIST.txt"
RUNTIME_ALLOWLIST_RESOURCE="$CLASSES_DIR/com/openrsc/worldbuilder/runtime-asset-allowlist-v1.txt"
RESOURCE_DIR="$ROOT_DIR/tools/world-builder/resources"

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

[[ -f "$RUNTIME_ALLOWLIST" && ! -L "$RUNTIME_ALLOWLIST" ]] || {
	printf 'FAIL: Missing or unsafe runtime allowlist: %s\n' "$RUNTIME_ALLOWLIST" >&2
	exit 1
}

if javac --help 2>&1 | grep -q -- '--release'; then
	javac --release 8 -encoding UTF-8 -d "$CLASSES_DIR" "${sources[@]}"
else
	javac -source 8 -target 8 -encoding UTF-8 -d "$CLASSES_DIR" "${sources[@]}"
fi
mkdir -p "$(dirname "$RUNTIME_ALLOWLIST_RESOURCE")"
cp "$RUNTIME_ALLOWLIST" "$RUNTIME_ALLOWLIST_RESOURCE"
[[ -d "$RESOURCE_DIR" && ! -L "$RESOURCE_DIR" ]] || {
	printf 'FAIL: Missing or unsafe World Builder resource directory: %s\n' "$RESOURCE_DIR" >&2
	exit 1
}
cp -R "$RESOURCE_DIR"/. "$CLASSES_DIR"/
jar cfe "$JAR_PATH" com.openrsc.worldbuilder.WorldBuilderCli -C "$CLASSES_DIR" .

printf 'Built %s\n' "$JAR_PATH"
