#!/usr/bin/env bash
set -euo pipefail

SCRIPT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ROOT_DIR="${ROOT_DIR:-$SCRIPT_ROOT}"
# shellcheck disable=SC1091
source "$ROOT_DIR/runtime-provider.lock"
ROOT_DIR="$(cd "$ROOT_DIR" && pwd -P)"

VERSION=""
RUNTIME_PROVIDER_ROOT=""
LINUX_JRE=""
WINDOWS_JRE=""
ASSETS_CLEARED=false
SKIP_BUILD=false
CANDIDATE_BUILD=false
SOURCE_COMMIT=""

PRODUCT_ID="rsc-world-editor-v2"
PRODUCT_GENERATION=2
UPDATE_CHANNEL="rsc-world-editor-v2"
LEGACY_PRODUCT_ID="rsc-world-editor-v1"
LEGACY_FINAL_TAG="v1.1.0"
PACKAGE_NAME="World Builder 2"
ARTIFACT_PREFIX="rsc-world-editor-v2"
WORLD_SOURCE_IDENTITY="target-adaptive-v1"
RELEASE_MARKER_ENTRY="spoiled-milk-release-build.marker"
LWJGL_VERSION="3.3.4"
LWJGL_MODULES="lwjgl lwjgl-glfw lwjgl-opengl"
LWJGL_NATIVE_CLASSIFIERS="natives-linux natives-windows"
VERSION_PATTERN='^v(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(-alpha\.(0|[1-9][0-9]*))?$'

fail() {
	printf 'FAIL: %s\n' "$*" >&2
	exit 1
}

usage() {
	cat <<'EOF'
Usage:
  ./scripts/package-world-builder-v2-release.sh \
    --version v0.1.0-alpha.1 \
    --runtime-provider /path/to/rsc-world-editor-runtime \
    --linux-jre /path/to/temurin-17-linux-x64-jre \
    --windows-jre /path/to/temurin-17-windows-x64-jre \
    --assets-cleared

Options:
  --assets-cleared   Attest that every packaged asset has confirmed
                     redistribution terms.
  --candidate-build  Build restricted pre-gate validation candidates without
                     opening the release gate or producing release artifacts.
  --skip-build       Use existing fixture jars. This is restricted to World
                     Builder 2 packaging tests.
EOF
}

while (($#)); do
	case "$1" in
		--version)
			[[ $# -ge 2 ]] || fail "--version requires a value"
			VERSION="$2"
			shift 2
			;;
		--runtime-provider)
			[[ $# -ge 2 ]] || fail "--runtime-provider requires a value"
			RUNTIME_PROVIDER_ROOT="$2"
			shift 2
			;;
		--windows-jre)
			[[ $# -ge 2 ]] || fail "--windows-jre requires a value"
			WINDOWS_JRE="$2"
			shift 2
			;;
		--linux-jre)
			[[ $# -ge 2 ]] || fail "--linux-jre requires a value"
			LINUX_JRE="$2"
			shift 2
			;;
		--assets-cleared)
			ASSETS_CLEARED=true
			shift
			;;
		--skip-build)
			SKIP_BUILD=true
			shift
			;;
		--candidate-build)
			CANDIDATE_BUILD=true
			shift
			;;
		-h|--help)
			usage
			exit 0
			;;
		*)
			fail "Unknown option: $1"
			;;
	esac
done

[[ "$VERSION" =~ $VERSION_PATTERN ]] \
	|| fail "Version must use semantic form, for example v0.1.0 or v0.1.0-alpha.1"
[[ -n "$RUNTIME_PROVIDER_ROOT" ]] || fail "--runtime-provider is required"
[[ "$RUNTIME_PROVIDER_COMMIT" =~ ^[0-9a-f]{40}$ ]] \
	|| fail "runtime-provider.lock contains an invalid commit"
RUNTIME_PROVIDER_ROOT="$(cd "$RUNTIME_PROVIDER_ROOT" 2>/dev/null && pwd -P)" \
	|| fail "Runtime provider directory does not exist"
[[ "$ASSETS_CLEARED" == true ]] \
	|| fail "Confirm redistribution terms with --assets-cleared before packaging"
if [[ "$SKIP_BUILD" == true \
	&& "${WORLD_BUILDER_V2_RELEASE_TEST_MODE:-}" != 1 ]]; then
	fail "--skip-build is restricted to World Builder 2 packaging tests"
fi
if [[ "$SKIP_BUILD" == true && "$CANDIDATE_BUILD" == true ]]; then
	fail "--candidate-build requires a real build and cannot be combined with --skip-build"
fi
if [[ "$CANDIDATE_BUILD" == true \
	&& "${WORLD_BUILDER_V2_MANAGER_CANDIDATE:-}" != 1 ]]; then
	fail "--candidate-build is internal; use ./scripts/ai-manager.sh candidate"
fi

for command_name in cp diff find git grep jar python3 sed sha256sum unzip xargs zip; do
	command -v "$command_name" >/dev/null 2>&1 \
		|| fail "Missing dependency: $command_name"
done

require_release_git_state() {
	local expected_commit="${1:-}"
	local git_dir current_branch current_commit worktree_status published_commit
	local operation_entry operation_marker operation_name

	git -C "$ROOT_DIR" rev-parse --is-inside-work-tree >/dev/null 2>&1 \
		|| fail "World Builder 2 packaging must run from the manager Git worktree"
	git_dir="$(git -C "$ROOT_DIR" rev-parse --absolute-git-dir)"
	for operation_entry in \
		"MERGE_HEAD:merge" \
		"CHERRY_PICK_HEAD:cherry-pick" \
		"REVERT_HEAD:revert" \
		"REBASE_HEAD:rebase" \
		"rebase-apply:rebase or am" \
		"rebase-merge:rebase" \
		"sequencer:sequenced operation" \
		"BISECT_LOG:bisect"; do
		operation_marker="${operation_entry%%:*}"
		operation_name="${operation_entry#*:}"
		[[ ! -e "$git_dir/$operation_marker" ]] \
			|| fail "Packaging is blocked by an in-progress Git $operation_name operation"
	done

	current_branch="$(git -C "$ROOT_DIR" symbolic-ref --quiet --short HEAD 2>/dev/null || true)"
	[[ "$current_branch" == main ]] \
		|| fail "World Builder 2 packaging must run from manager branch main; found ${current_branch:-detached HEAD}"
	worktree_status="$(git -C "$ROOT_DIR" status --porcelain --untracked-files=all)"
	[[ -z "$worktree_status" ]] \
		|| fail "World Builder 2 packaging requires a clean manager main worktree"

	current_commit="$(git -C "$ROOT_DIR" rev-parse --verify 'HEAD^{commit}')"
	if [[ -n "$expected_commit" && "$current_commit" != "$expected_commit" ]]; then
		fail "Release source changed during packaging (expected $expected_commit, found $current_commit)"
	fi
	if [[ -z "$SOURCE_COMMIT" ]]; then
		SOURCE_COMMIT="$current_commit"
	fi
	published_commit="$(git -C "$ROOT_DIR" rev-parse --verify 'refs/remotes/origin/main^{commit}' 2>/dev/null || true)"
	[[ -n "$published_commit" ]] || fail "Missing origin/main"
	[[ "$SOURCE_COMMIT" == "$published_commit" ]] \
		|| fail "Packaging requires HEAD to match origin/main"
}

require_runtime_provider_state() {
	local expected_commit="${1:-$RUNTIME_PROVIDER_COMMIT}" actual_commit worktree_status
	git -C "$RUNTIME_PROVIDER_ROOT" rev-parse --is-inside-work-tree >/dev/null 2>&1 \
		|| fail "--runtime-provider must name a Git checkout"
	actual_commit="$(git -C "$RUNTIME_PROVIDER_ROOT" rev-parse --verify 'HEAD^{commit}')"
	[[ "$actual_commit" == "$expected_commit" ]] \
		|| fail "Runtime provider must be at locked commit $expected_commit; found $actual_commit"
	worktree_status="$(git -C "$RUNTIME_PROVIDER_ROOT" status --porcelain --untracked-files=all)"
	[[ -z "$worktree_status" ]] \
		|| fail "Runtime provider release checkout must be clean"
}

print_lwjgl_preparation_guidance() {
	printf 'Remove any listed invalid or unexpected ignored LWJGL jars first; the downloader does not overwrite existing files.\n' >&2
	printf 'Prepare the pinned Core checkout with the exact World Builder 2 LWJGL inputs, then rerun packaging:\n' >&2
	printf "  LWJGL_VERSION=%s LWJGL_MODULES='%s' LWJGL_NATIVE_CLASSIFIERS='%s' %q\n" \
		"$LWJGL_VERSION" "$LWJGL_MODULES" "$LWJGL_NATIVE_CLASSIFIERS" \
		"$RUNTIME_PROVIDER_ROOT/scripts/download-lwjgl.sh" >&2
}

require_lwjgl_release_inputs() {
	local library_root="$RUNTIME_PROVIDER_ROOT/PC_Client/lib/lwjgl"
	local specification jar_name expected_entry jar_path is_expected
	local -a missing=()
	local -a invalid=()
	local -a unexpected=()
	local -a specifications=(
		"lwjgl-$LWJGL_VERSION.jar:org/lwjgl/Version.class"
		"lwjgl-glfw-$LWJGL_VERSION.jar:org/lwjgl/glfw/GLFW.class"
		"lwjgl-opengl-$LWJGL_VERSION.jar:org/lwjgl/opengl/GL.class"
		"lwjgl-$LWJGL_VERSION-natives-linux.jar:linux/x64/org/lwjgl/liblwjgl.so"
		"lwjgl-glfw-$LWJGL_VERSION-natives-linux.jar:linux/x64/org/lwjgl/glfw/libglfw.so"
		"lwjgl-opengl-$LWJGL_VERSION-natives-linux.jar:linux/x64/org/lwjgl/opengl/liblwjgl_opengl.so"
		"lwjgl-$LWJGL_VERSION-natives-windows.jar:windows/x64/org/lwjgl/lwjgl.dll"
		"lwjgl-glfw-$LWJGL_VERSION-natives-windows.jar:windows/x64/org/lwjgl/glfw/glfw.dll"
		"lwjgl-opengl-$LWJGL_VERSION-natives-windows.jar:windows/x64/org/lwjgl/opengl/lwjgl_opengl.dll"
	)

	[[ -x "$RUNTIME_PROVIDER_ROOT/scripts/download-lwjgl.sh" ]] \
		|| fail "Pinned Core checkout is missing executable scripts/download-lwjgl.sh"
	for specification in "${specifications[@]}"; do
		jar_name="${specification%%:*}"
		expected_entry="${specification#*:}"
		if [[ ! -f "$library_root/$jar_name" ]]; then
			missing+=("$jar_name")
		elif ! jar tf "$library_root/$jar_name" | grep -Fx "$expected_entry" >/dev/null; then
			invalid+=("$jar_name")
		fi
	done
	if [[ -d "$library_root" ]]; then
		while IFS= read -r -d '' jar_path; do
			jar_name="${jar_path##*/}"
			is_expected=false
			for specification in "${specifications[@]}"; do
				if [[ "$jar_name" == "${specification%%:*}" ]]; then
					is_expected=true
					break
				fi
			done
			[[ "$is_expected" == true ]] || unexpected+=("$jar_name")
		done < <(find "$library_root" -maxdepth 1 -type f -name '*.jar' -print0)
	fi
	if ((${#missing[@]} || ${#invalid[@]} || ${#unexpected[@]})); then
		if ((${#missing[@]})); then
			printf 'Missing pinned LWJGL release inputs:\n' >&2
			printf '  %s\n' "${missing[@]}" >&2
		fi
		if ((${#invalid[@]})); then
			printf 'Invalid pinned LWJGL release inputs:\n' >&2
			printf '  %s\n' "${invalid[@]}" >&2
		fi
		if ((${#unexpected[@]})); then
			printf 'Unexpected LWJGL jars would make the release build non-reproducible:\n' >&2
			printf '  %s\n' "${unexpected[@]}" >&2
		fi
		print_lwjgl_preparation_guidance
		fail "World Builder 2 requires reproducible Linux and Windows LWJGL natives before the release build"
	fi
}

require_release_git_state
require_runtime_provider_state

validate_runtime() {
	local platform="$1" runtime="$2" java_path="$3" expected_os="$4"
	local runtime_version runtime_major runtime_os runtime_arch

	[[ -d "$runtime" ]] || fail "$platform JRE directory does not exist: $runtime"
	[[ -f "$runtime/$java_path" ]] || fail "$platform JRE must contain $java_path"
	[[ -f "$runtime/release" ]] || fail "$platform JRE must contain release metadata"
	[[ -f "$runtime/LICENSE" || -f "$runtime/NOTICE" \
		|| -f "$runtime/legal/java.base/LICENSE" ]] \
		|| fail "$platform JRE must contain redistribution legal files"

	runtime_version="$(sed -n 's/^JAVA_VERSION="\([^"]*\)".*/\1/p' "$runtime/release" | head -n 1)"
	[[ -n "$runtime_version" ]] || fail "Unable to read JAVA_VERSION from the $platform JRE"
	if [[ "$runtime_version" == 1.* ]]; then
		runtime_major="${runtime_version#1.}"
		runtime_major="${runtime_major%%.*}"
	else
		runtime_major="${runtime_version%%.*}"
	fi
	[[ "$runtime_major" =~ ^[0-9]+$ ]] && ((runtime_major >= 17)) \
		|| fail "$platform World Builder requires Java 17+; found $runtime_version"
	runtime_os="$(sed -n 's/^OS_NAME="\([^"]*\)".*/\1/p' "$runtime/release" | head -n 1)"
	runtime_arch="$(sed -n 's/^OS_ARCH="\([^"]*\)".*/\1/p' "$runtime/release" | head -n 1)"
	[[ "$runtime_os" == "$expected_os" ]] \
		|| fail "$platform JRE must report OS_NAME=\"$expected_os\"; found ${runtime_os:-missing}"
	[[ "$runtime_arch" == x86_64 || "$runtime_arch" == amd64 ]] \
		|| fail "$platform JRE must be x64; found ${runtime_arch:-missing}"
	python3 - "$platform" "$runtime" <<'PY'
import pathlib
import sys

platform, runtime_text = sys.argv[1:]
runtime = pathlib.Path(runtime_text).resolve()
for path in runtime.rglob("*"):
    if not path.is_symlink():
        continue
    try:
        target = path.resolve(strict=True)
        target.relative_to(runtime)
    except (FileNotFoundError, RuntimeError, ValueError):
        raise SystemExit(
            f"{platform} JRE contains a broken or external symbolic link: {path}"
        )
PY
}

validate_runtime "Linux" "$LINUX_JRE" "bin/java" "Linux"
[[ -x "$LINUX_JRE/bin/java" ]] || fail "Linux JRE bin/java must be executable"
validate_runtime "Windows" "$WINDOWS_JRE" "bin/java.exe" "Windows"

PACKAGE_ASSETS="$ROOT_DIR/release/world-builder-v2"
UPDATE_ASSETS="$ROOT_DIR/release/updater-v2"
ICON_CREDITS="$RUNTIME_PROVIDER_ROOT/dev/myworld/assets/ui/world-editor/CREDITS.md"
if [[ "$CANDIDATE_BUILD" == true ]]; then
	[[ ! -e "$PACKAGE_ASSETS/RELEASE-READY" \
		&& ! -L "$PACKAGE_ASSETS/RELEASE-READY" ]] \
		|| fail "Pre-gate candidate packaging is forbidden after the World Builder 2 release gate is opened"
elif [[ "$SKIP_BUILD" != true && ! -f "$PACKAGE_ASSETS/RELEASE-READY" ]]; then
	fail "World Builder 2 public packaging remains locked until final cross-platform release validation is accepted"
elif [[ "$SKIP_BUILD" != true ]]; then
	"$ROOT_DIR/scripts/validate-world-builder-v2-release-gate.sh" "$VERSION" \
		|| fail "World Builder 2 release gate validation failed"
fi
[[ -f "$ICON_CREDITS" ]] || fail "World editor icon credits are missing"
if grep -Eiq 'pending confirmation|pending;|not release-ready' "$ICON_CREDITS"; then
	fail "World editor icon provenance is unresolved; update $ICON_CREDITS before packaging"
fi

if [[ "$SKIP_BUILD" != true ]]; then
	require_lwjgl_release_inputs
	"$RUNTIME_PROVIDER_ROOT/scripts/build-server.sh"
	SPOILED_MILK_RELEASE_BUILD=1 "$RUNTIME_PROVIDER_ROOT/scripts/build-client.sh"
	"$ROOT_DIR/scripts/build-tools.sh"
fi
require_release_git_state "$SOURCE_COMMIT"
require_runtime_provider_state "$RUNTIME_PROVIDER_COMMIT"

CLIENT_JAR="$RUNTIME_PROVIDER_ROOT/Client_Base/Open_RSC_Client.jar"
TOOLS_JAR="$ROOT_DIR/output/world-builder-tools/world-builder-tools.jar"
SERVER_JAR="$RUNTIME_PROVIDER_ROOT/server/core.jar"
PLUGINS_JAR="$RUNTIME_PROVIDER_ROOT/server/plugins.jar"
RUNTIME_ALLOWLIST="$PACKAGE_ASSETS/RUNTIME-ASSET-ALLOWLIST.txt"
TOOLS_RUNTIME_ALLOWLIST_ENTRY="com/openrsc/worldbuilder/runtime-asset-allowlist-v1.txt"

for required_path in \
	"$CLIENT_JAR" \
	"$SERVER_JAR" \
	"$PLUGINS_JAR" \
	"$TOOLS_JAR" \
	"$ROOT_DIR/tools/world-builder/schema" \
	"$ROOT_DIR/LICENSE" \
	"$RUNTIME_PROVIDER_ROOT/release/player/ASSET-SOURCES.txt" \
	"$PACKAGE_ASSETS/README.txt" \
	"$PACKAGE_ASSETS/ASSET-SOURCES.txt" \
	"$RUNTIME_ALLOWLIST" \
	"$PACKAGE_ASSETS/world-builder-runtime.conf" \
	"$PACKAGE_ASSETS/Import Map Changes.sh" \
	"$PACKAGE_ASSETS/Import Map Changes.cmd" \
	"$PACKAGE_ASSETS/Recover Map Transaction.sh" \
	"$PACKAGE_ASSETS/Recover Map Transaction.cmd" \
	"$PACKAGE_ASSETS/Undo Last Map Import.sh" \
	"$PACKAGE_ASSETS/Undo Last Map Import.cmd" \
	"$UPDATE_ASSETS/Start World Builder.sh" \
	"$UPDATE_ASSETS/Start World Builder.cmd" \
	"$UPDATE_ASSETS/Update World Builder.sh" \
	"$UPDATE_ASSETS/Update World Builder.cmd" \
	"$UPDATE_ASSETS/Update World Builder.ps1" \
	"$UPDATE_ASSETS/README-AUTO-UPDATE.txt"; do
	[[ -e "$required_path" ]] || fail "Missing release input: $required_path"
done

validate_allowlist() {
	python3 - "$RUNTIME_PROVIDER_ROOT" "$RUNTIME_ALLOWLIST" <<'PY'
import pathlib
import re
import sys

core = pathlib.Path(sys.argv[1]).resolve()
allowlist = pathlib.Path(sys.argv[2])
allowed_roles = {
    "runtime-audio", "client-template", "default-render-catalog",
    "runtime-library", "runtime-configuration", "default-definition-catalog",
    "runtime-capability", "runtime-database-contract", "builder-database-seed",
}
required_native_records = {
    (f"server/conf/server/languages/{name}",
     f"server/conf/server/languages/{name}", "runtime-configuration")
    for name in (
        "AuthenticMessages_en_UK.properties",
        "AuthenticMessages_en_UK_female.properties",
        "AuthenticMessages_en_UK_female_no_misgender.properties",
        "AuthenticMessages_en_UK_gender_neutral.properties",
        "AuthenticMessages_en_UK_male.properties",
        "CustomMessages_en_UK.properties",
        "CustomMessages_en_UK_female.properties",
        "CustomMessages_en_UK_gender_neutral.properties",
        "CustomMessages_en_UK_male.properties",
    )
} | {
    (f"server/database/{namespace}/queries/{name}",
     f"server/database/{namespace}/queries/{name}", "runtime-database-contract")
    for namespace, names in (
        ("mysql", ("bank_presets.xml", "item.xml", "player.xml")),
        ("sqlite", ("bank_presets.xml", "item.xml", "patches.xml", "player.xml")),
    )
    for name in names
} | {
    (f"server/database/sqlite/patches/{name}",
     f"server/database/sqlite/patches/{name}", "runtime-database-contract")
    for name in (
        "2021_05_11_add_db_patches.sql",
        "2023_02_01_former_names.sql",
        "2026_05_14_add_summoning_skill.sql",
        "2026_08_03_add_blessing_skill.sql",
    )
}
project_only_generated = {"server/client.pem", "server/server.pem"}
portable = re.compile(r"^[A-Za-z0-9._+ -]+(?:/[A-Za-z0-9._+ -]+)*$")
seen_source = set()
seen_destination = set()
records = []
for number, raw in enumerate(allowlist.read_text(encoding="utf-8").splitlines(), 1):
    if not raw or raw.startswith("#"):
        continue
    fields = raw.split("\t")
    if len(fields) != 3:
        raise SystemExit(f"Malformed runtime allowlist line {number}")
    source, destination, role = fields
    if source in project_only_generated or destination in project_only_generated:
        raise SystemExit(
            f"Runtime allowlist includes project-only generated state: {source}"
        )
    if (
        not portable.fullmatch(source)
        or not portable.fullmatch(destination)
        or any(part in ("", ".", "..") for part in pathlib.PurePosixPath(source).parts)
        or any(part in ("", ".", "..") for part in pathlib.PurePosixPath(destination).parts)
        or role not in allowed_roles
        or source.casefold() in seen_source
        or destination.casefold() in seen_destination
    ):
        raise SystemExit(f"Unsafe or duplicate runtime allowlist line {number}")
    seen_source.add(source.casefold())
    seen_destination.add(destination.casefold())
    path = core.joinpath(*source.split("/"))
    if not path.is_file() or path.is_symlink() or core not in path.resolve().parents:
        raise SystemExit(f"Allowed runtime input is missing or unsafe: {source}")
    records.append((source, destination, role))
if not records:
    raise SystemExit("Runtime asset allowlist is empty")
if sum(role == "builder-database-seed" for _, _, role in records) != 1:
    raise SystemExit("Runtime allowlist must contain exactly one Builder database seed")
missing_native = required_native_records.difference(records)
if missing_native:
    missing = sorted(destination for _, destination, _ in missing_native)
    raise SystemExit(
        "Runtime allowlist is missing required native server assets: "
        + ", ".join(missing)
    )

definition_prefix = "server/conf/server/defs/"
definition_root = core / "server/conf/server/defs"
if not definition_root.is_dir() or definition_root.is_symlink():
    raise SystemExit("Exact provider definition root is missing or unsafe")
provider_definitions = set()
for path in definition_root.rglob("*"):
    relative = path.relative_to(definition_root)
    if relative.parts and relative.parts[0].casefold() == "locs":
        continue
    if path.is_symlink():
        raise SystemExit(f"Exact provider definition closure contains a link: {relative}")
    if path.is_dir():
        continue
    if not path.is_file() or path.stat(follow_symlinks=False).st_nlink != 1:
        raise SystemExit(
            f"Exact provider definition closure contains an unsupported entry: {relative}"
        )
    provider_definitions.add(definition_prefix + relative.as_posix())
if not provider_definitions:
    raise SystemExit("Exact provider definition closure is empty")
required_definition_records = {
    (relative, relative, "default-definition-catalog")
    for relative in provider_definitions
}
allowlisted_definition_records = {
    record
    for record in records
    if record[0].casefold().startswith(definition_prefix)
    or record[1].casefold().startswith(definition_prefix)
}
if any(
    source.casefold().startswith(definition_prefix + "locs/")
    or destination.casefold().startswith(definition_prefix + "locs/")
    for source, destination, _ in records
):
    raise SystemExit("Runtime allowlist must exclude the complete defs/locs subtree")
missing_definitions = required_definition_records.difference(
    allowlisted_definition_records
)
extra_definitions = allowlisted_definition_records.difference(
    required_definition_records
)
if missing_definitions or extra_definitions:
    detail = []
    if missing_definitions:
        detail.append(
            "missing "
            + ", ".join(sorted(source for source, _, _ in missing_definitions))
        )
    if extra_definitions:
        detail.append(
            "unexpected "
            + ", ".join(sorted(source for source, _, _ in extra_definitions))
        )
    raise SystemExit(
        "Runtime allowlist does not match the exact content-neutral definition closure: "
        + "; ".join(detail)
    )
PY
}

validate_allowlist || fail "Content-neutral runtime asset allowlist validation failed"

require_jar_entry() {
	local archive="$1" entry="$2" label="$3"
	jar tf "$archive" | grep -Fx "$entry" >/dev/null \
		|| fail "$label is missing required entry: $entry"
}

require_jar_entry "$CLIENT_JAR" "orsc/WorldBuilderClientProfile.class" "client jar"
marker_value="$(unzip -p "$CLIENT_JAR" "$RELEASE_MARKER_ENTRY" 2>/dev/null || true)"
[[ "$marker_value" == "release-build=true" ]] \
	|| fail "Release client jar is missing the exact $RELEASE_MARKER_ENTRY entry; rebuild through this production packager"
require_jar_entry "$SERVER_JAR" \
	"com/openrsc/server/content/worldedit/WorldEditStorageContext.class" "server jar"
require_jar_entry "$SERVER_JAR" \
	"com/openrsc/server/content/worldedit/WorldBuilderRuntimeControl.class" "server jar"
require_jar_entry "$TOOLS_JAR" \
	"com/openrsc/worldbuilder/WorldBuilderCli.class" "tools jar"
require_jar_entry "$TOOLS_JAR" \
	"com/openrsc/worldbuilder/WorldBuilderLayeredPackage.class" "tools jar"
require_jar_entry "$TOOLS_JAR" "$TOOLS_RUNTIME_ALLOWLIST_ENTRY" "tools jar"
python3 - "$TOOLS_JAR" "$RUNTIME_ALLOWLIST" "$TOOLS_RUNTIME_ALLOWLIST_ENTRY" <<'PY' \
	|| fail "Tools jar embedded runtime allowlist differs from its release source"
import pathlib
import sys
import zipfile

archive = pathlib.Path(sys.argv[1])
allowlist = pathlib.Path(sys.argv[2]).read_bytes()
entry = sys.argv[3]
with zipfile.ZipFile(archive) as jar:
    matches = [item for item in jar.infolist() if item.filename == entry]
    if len(matches) != 1 or jar.read(matches[0]) != allowlist:
        raise SystemExit(1)
PY

jar tf "$CLIENT_JAR" | grep '^myworld-assets/ui/world-editor/' >/dev/null \
	|| fail "Client jar is missing embedded World Builder UI assets"
for native_entry in \
	"linux/x64/org/lwjgl/liblwjgl.so" \
	"linux/x64/org/lwjgl/glfw/libglfw.so" \
	"linux/x64/org/lwjgl/opengl/liblwjgl_opengl.so" \
	"windows/x64/org/lwjgl/lwjgl.dll" \
	"windows/x64/org/lwjgl/glfw/glfw.dll" \
	"windows/x64/org/lwjgl/opengl/lwjgl_opengl.dll"; do
	if ! jar tf "$CLIENT_JAR" | grep -Fx "$native_entry" >/dev/null; then
		print_lwjgl_preparation_guidance
		fail "Release client jar is missing required LWJGL native entry: $native_entry"
	fi
done

server_protocol="$(sed -n 's/^[[:space:]]*client_version:[[:space:]]*\([0-9][0-9]*\).*/\1/p' "$RUNTIME_PROVIDER_ROOT/server/myworld.conf" | head -n 1)"
client_protocol="$(sed -n 's/.*CLIENT_VERSION[[:space:]]*=[[:space:]]*\([0-9][0-9]*\).*/\1/p' "$RUNTIME_PROVIDER_ROOT/Client_Base/src/orsc/Config.java" | head -n 1)"
runtime_protocol="$(sed -n 's/^[[:space:]]*client_version:[[:space:]]*\([0-9][0-9]*\).*/\1/p' "$PACKAGE_ASSETS/world-builder-runtime.conf" | head -n 1)"
[[ -n "$server_protocol" && "$server_protocol" == "$client_protocol" \
	&& "$server_protocol" == "$runtime_protocol" ]] \
	|| fail "Client, server, and Builder runtime protocol versions disagree"

if [[ "$CANDIDATE_BUILD" == true ]]; then
	OUTPUT_ROOT="$ROOT_DIR/output/candidates/world-builder-v2"
else
	OUTPUT_ROOT="$ROOT_DIR/output/releases/world-builder-v2"
fi
OUTPUT_DIR="$OUTPUT_ROOT/$VERSION"
STAGING_DIR="$OUTPUT_DIR/staging"
LINUX_STAGE="$STAGING_DIR/linux/$PACKAGE_NAME"
WINDOWS_STAGE="$STAGING_DIR/windows/$PACKAGE_NAME"
VERSION_NUMBER="${VERSION#v}"
RELEASE_TAG="$ARTIFACT_PREFIX-$VERSION_NUMBER"
LINUX_ARCHIVE="$OUTPUT_DIR/$ARTIFACT_PREFIX-$VERSION_NUMBER-linux-x64.zip"
WINDOWS_ARCHIVE="$OUTPUT_DIR/$ARTIFACT_PREFIX-$VERSION_NUMBER-windows-x64.zip"

[[ "$OUTPUT_DIR" == "$OUTPUT_ROOT/"* && "$OUTPUT_DIR" != "$OUTPUT_ROOT" ]] \
	|| fail "Refusing unsafe release output path: $OUTPUT_DIR"

require_unlinked_output_path() {
	local current="$ROOT_DIR" relative component
	relative="${OUTPUT_DIR#"$ROOT_DIR"/}"
	[[ "$relative" != "$OUTPUT_DIR" && -n "$relative" ]] \
		|| fail "Refusing candidate/release output outside the repository"
	while IFS= read -r component; do
		[[ -n "$component" && "$component" != . && "$component" != .. ]] \
			|| fail "Refusing unsafe candidate/release output component"
		current="$current/$component"
		[[ ! -L "$current" ]] \
			|| fail "Candidate/release output path contains a symbolic link: $current"
		[[ ! -e "$current" || -d "$current" ]] \
			|| fail "Candidate/release output path is not a directory: $current"
	done < <(printf '%s\n' "$relative" | tr '/' '\n')
}

require_unlinked_output_path
rm -rf -- "$OUTPUT_DIR"
mkdir -p "$LINUX_STAGE" "$WINDOWS_STAGE" "$OUTPUT_DIR"
require_unlinked_output_path
[[ "$(cd "$OUTPUT_DIR" && pwd -P)" == "$OUTPUT_DIR" ]] \
	|| fail "Candidate/release output resolved outside its repository path"

write_release_identity() {
	local destination="$1"
	cat > "$destination/RELEASE-IDENTITY.json" <<EOF
{
  "schemaVersion": 1,
  "productId": "$PRODUCT_ID",
  "productGeneration": $PRODUCT_GENERATION,
  "displayName": "$PACKAGE_NAME",
  "updateChannel": "$UPDATE_CHANNEL",
  "releaseTag": "$RELEASE_TAG",
  "artifactPrefix": "$ARTIFACT_PREFIX",
  "worldSourceIdentity": "$WORLD_SOURCE_IDENTITY",
  "automaticUpgradeFromProductIds": [
    "$PRODUCT_ID"
  ],
  "legacyProductId": "$LEGACY_PRODUCT_ID",
  "legacyFinalTag": "$LEGACY_FINAL_TAG",
  "legacyWorkspaceMigration": false,
  "version": "$VERSION",
  "sourceCommit": "$SOURCE_COMMIT",
  "runtimeProviderCommit": "$RUNTIME_PROVIDER_COMMIT"
}
EOF
}

stage_builder() {
	local destination="$1"
	local runtime="$destination/builder-runtime"
	local source relative role source_path destination_path

	mkdir -p "$runtime/Client_Base" "$runtime/server" "$runtime/launcher/schema"
	cp "$CLIENT_JAR" "$runtime/Client_Base/Open_RSC_Client.jar"
	cp "$SERVER_JAR" "$runtime/server/core.jar"
	cp "$PLUGINS_JAR" "$runtime/server/plugins.jar"
	while IFS=$'\t' read -r source relative role || [[ -n "$source$relative$role" ]]; do
		[[ -n "$source" && "$source" != \#* ]] || continue
		source_path="$RUNTIME_PROVIDER_ROOT/$source"
		destination_path="$runtime/$relative"
		mkdir -p "${destination_path%/*}"
		cp "$source_path" "$destination_path"
	done < "$RUNTIME_ALLOWLIST"
	cp "$PACKAGE_ASSETS/world-builder-runtime.conf" "$runtime/server/world-builder.conf"
	cp "$TOOLS_JAR" "$runtime/launcher/world-builder-tools.jar"
	while IFS= read -r -d '' source_path; do
		relative="${source_path#"$ROOT_DIR/tools/world-builder/schema/"}"
		if [[ "${relative%/*}" != "$relative" ]]; then
			mkdir -p "$runtime/launcher/schema/${relative%/*}"
		fi
		cp "$source_path" "$runtime/launcher/schema/$relative"
	done < <(find "$ROOT_DIR/tools/world-builder/schema" -type f -print0)

	cp "$UPDATE_ASSETS/Start World Builder.sh" "$destination/Start World Builder.sh"
	cp "$UPDATE_ASSETS/Start World Builder.cmd" "$destination/Start World Builder.cmd"
	cp "$UPDATE_ASSETS/Update World Builder.sh" "$destination/Update World Builder.sh"
	cp "$UPDATE_ASSETS/Update World Builder.cmd" "$destination/Update World Builder.cmd"
	cp "$UPDATE_ASSETS/Update World Builder.ps1" "$destination/Update World Builder.ps1"
	cp "$PACKAGE_ASSETS/Import Map Changes.sh" "$destination/Import Map Changes.sh"
	cp "$PACKAGE_ASSETS/Import Map Changes.cmd" "$destination/Import Map Changes.cmd"
	cp "$PACKAGE_ASSETS/Recover Map Transaction.sh" "$destination/Recover Map Transaction.sh"
	cp "$PACKAGE_ASSETS/Recover Map Transaction.cmd" "$destination/Recover Map Transaction.cmd"
	cp "$PACKAGE_ASSETS/Undo Last Map Import.sh" "$destination/Undo Last Map Import.sh"
	cp "$PACKAGE_ASSETS/Undo Last Map Import.cmd" "$destination/Undo Last Map Import.cmd"
	chmod 0755 "$destination/Start World Builder.sh" \
		"$destination/Update World Builder.sh" \
		"$destination/Import Map Changes.sh" \
		"$destination/Recover Map Transaction.sh" \
		"$destination/Undo Last Map Import.sh"
	sed "s/@VERSION@/$VERSION/g; s/@SOURCE_COMMIT@/$SOURCE_COMMIT/g" \
		"$PACKAGE_ASSETS/README.txt" > "$destination/README.txt"
	cat "$UPDATE_ASSETS/README-AUTO-UPDATE.txt" >> "$destination/README.txt"
	printf '\nRuntime provider commit: %s\n' "$RUNTIME_PROVIDER_COMMIT" \
		>> "$destination/README.txt"
	cp "$ROOT_DIR/LICENSE" "$destination/LICENSE"
	cp "$PACKAGE_ASSETS/ASSET-SOURCES.txt" "$destination/ASSET-SOURCES.txt"
	cp "$RUNTIME_ALLOWLIST" "$destination/RUNTIME-ASSET-ALLOWLIST.txt"
	cp "$RUNTIME_PROVIDER_ROOT/release/player/ASSET-SOURCES.txt" \
		"$destination/PLAYER-ASSET-SOURCES.txt"
	cp "$ICON_CREDITS" "$destination/EDITOR-ICON-CREDITS.txt"
	printf '%s\n' "$VERSION" > "$destination/VERSION.txt"
	printf '%s\n' "$SOURCE_COMMIT" > "$destination/SOURCE-COMMIT.txt"
	printf '%s\n' "$RUNTIME_PROVIDER_COMMIT" > "$destination/RUNTIME-PROVIDER-COMMIT.txt"
	write_release_identity "$destination"
}

stage_builder "$LINUX_STAGE"
stage_builder "$WINDOWS_STAGE"
mkdir -p "$LINUX_STAGE/runtime"
cp -RL --preserve=mode "$LINUX_JRE"/. "$LINUX_STAGE/runtime/"
chmod 0755 "$LINUX_STAGE/runtime"
mkdir -p "$WINDOWS_STAGE/runtime"
cp -RL --preserve=mode "$WINDOWS_JRE"/. "$WINDOWS_STAGE/runtime/"
chmod 0755 "$WINDOWS_STAGE/runtime"

validate_stage() {
	local stage="$1"
	if find "$stage" -type l -print -quit | grep -q .; then
		fail "Staged World Builder package contains a symbolic link"
	fi
	python3 - "$stage" "$RUNTIME_PROVIDER_ROOT" "$RUNTIME_ALLOWLIST" \
		"$ROOT_DIR/tools/world-builder/schema" <<'PY'
import hashlib
import json
import pathlib
import sqlite3
import sys
import zipfile

root = pathlib.Path(sys.argv[1]).resolve()
core = pathlib.Path(sys.argv[2]).resolve()
allowlist_path = pathlib.Path(sys.argv[3])
schema_root = pathlib.Path(sys.argv[4]).resolve()
seen = {}
reserved = {
    "CON", "PRN", "AUX", "NUL",
    *(f"COM{number}" for number in range(1, 10)),
    *(f"LPT{number}" for number in range(1, 10)),
}
top_files = {
    "ASSET-SOURCES.txt", "RUNTIME-PROVIDER-COMMIT.txt", "EDITOR-ICON-CREDITS.txt",
    "Import Map Changes.cmd", "Import Map Changes.sh", "LICENSE",
    "PLAYER-ASSET-SOURCES.txt", "README.txt", "Recover Map Transaction.cmd",
    "Recover Map Transaction.sh", "RELEASE-IDENTITY.json",
    "RUNTIME-ASSET-ALLOWLIST.txt", "SOURCE-COMMIT.txt", "Start World Builder.cmd",
    "Start World Builder.sh", "Undo Last Map Import.cmd", "Undo Last Map Import.sh",
    "Update World Builder.cmd", "Update World Builder.ps1", "Update World Builder.sh",
    "VERSION.txt",
}
runtime_files = {
    "builder-runtime/Client_Base/Open_RSC_Client.jar",
    "builder-runtime/server/core.jar",
    "builder-runtime/server/plugins.jar",
    "builder-runtime/server/world-builder.conf",
    "builder-runtime/launcher/world-builder-tools.jar",
}
for raw in allowlist_path.read_text(encoding="utf-8").splitlines():
    if not raw or raw.startswith("#"):
        continue
    _, destination, _ = raw.split("\t")
    runtime_files.add("builder-runtime/" + destination)
for path in schema_root.rglob("*"):
    if path.is_file():
        runtime_files.add(
            "builder-runtime/launcher/schema/" + path.relative_to(schema_root).as_posix()
        )

forbidden_hashes = {}
for pattern, role in (
    ("Client_Base/Cache/video/*Landscape*", "map terrain"),
    ("server/conf/server/data/**/*", "map terrain"),
    ("server/conf/server/defs/locs/**/*", "static placement data"),
    ("tools/layered-maps/workspace/**/*", "layered world package"),
):
    for path in core.glob(pattern):
        if path.is_file() and not path.is_symlink():
            digest = hashlib.sha256(path.read_bytes()).hexdigest()
            forbidden_hashes[digest] = (role, path.relative_to(core).as_posix())

def reject_structured_world(data, relative):
    if len(data) > 32 * 1024 * 1024:
        return
    try:
        value = json.loads(data.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError):
        return
    pending = [value]
    while pending:
        item = pending.pop()
        if isinstance(item, dict):
            if item.get("packageType") == "layered-world":
                raise SystemExit("Layered world package content is forbidden: " + relative)
            encoding = item.get("encoding")
            if isinstance(encoding, str) and encoding in {
                "layered-world-placements-v3", "legacy-packed-orsc-v1"
            }:
                raise SystemExit("Terrain or placement payload content is forbidden: " + relative)
            manifest_type = item.get("manifestType")
            if isinstance(manifest_type, str) and manifest_type in {
                "world-builder-project", "world-builder-project-registry",
                "world-builder-active-project", "world-builder-import-receipt",
            }:
                raise SystemExit("Creator or transaction state is forbidden: " + relative)
            pending.extend(item.values())
        elif isinstance(item, list):
            pending.extend(item)

for path in root.rglob("*"):
    relative = path.relative_to(root).as_posix()
    for component in path.relative_to(root).parts:
        if (
            any(ord(character) < 32 or character in '<>:"\\|?*' for character in component)
            or component.endswith((" ", "."))
            or component.split(".", 1)[0].upper() in reserved
        ):
            raise SystemExit("Windows-unsafe staged package path: " + repr(relative))
    folded = relative.casefold()
    if folded in seen and seen[folded] != relative:
        raise SystemExit(
            "Case-colliding staged package paths: "
            + repr(seen[folded])
            + " and "
            + repr(relative)
        )
    seen[folded] = relative
    if "\\" in relative:
        raise SystemExit("Unsafe staged package path: " + repr(relative))
    if path.is_symlink() or not (path.is_dir() or path.is_file()):
        raise SystemExit("Unsupported staged package entry: " + relative)
    if not path.is_file():
        continue
    if not (
        relative in top_files
        or relative in runtime_files
        or relative.startswith("runtime/")
    ):
        raise SystemExit("Staged file is outside the application allowlist: " + relative)
    data = path.read_bytes()
    digest = hashlib.sha256(data).hexdigest()
    if digest in forbidden_hashes:
        role, source = forbidden_hashes[digest]
        raise SystemExit(
            f"Staged file contains forbidden {role} copied from {source}: {relative}"
        )
    reject_structured_world(data, relative)
    if zipfile.is_zipfile(path):
        with zipfile.ZipFile(path) as archive:
            for info in archive.infolist():
                if info.is_dir():
                    continue
                nested = archive.read(info)
                nested_digest = hashlib.sha256(nested).hexdigest()
                if nested_digest in forbidden_hashes:
                    role, source = forbidden_hashes[nested_digest]
                    raise SystemExit(
                        f"Archive entry contains forbidden {role} copied from {source}: "
                        f"{relative}!{info.filename}"
                    )
                if not info.filename.endswith(".class"):
                    reject_structured_world(nested, relative + "!" + info.filename)

seed = root / "builder-runtime/server/inc/sqlite/world_builder_seed.db"
try:
    connection = sqlite3.connect(f"file:{seed}?mode=ro", uri=True)
    integrity = [row[0] for row in connection.execute("PRAGMA integrity_check")]
    if integrity != ["ok"]:
        raise SystemExit(
            "Builder database seed failed SQLite integrity_check: "
            + "; ".join(str(item) for item in integrity)
        )
    table_names = [
        row[0]
        for row in connection.execute(
            "SELECT name FROM sqlite_schema WHERE type = 'table' ORDER BY name"
        )
    ]
    table_counts = {}
    for table in table_names:
        quoted = '"' + table.replace('"', '""') + '"'
        table_counts[table] = connection.execute(
            f"SELECT COUNT(*) FROM {quoted}"
        ).fetchone()[0]
    for table in ("grounditems", "npclocs", "objects"):
        if table not in table_counts:
            raise SystemExit(
                f"Builder database seed is missing required placement table {table}"
            )
        count = table_counts[table]
        if count:
            raise SystemExit(
                f"Builder database seed contains forbidden generated/static {table} state"
            )
    # The pinned empty Builder seed intentionally carries only migration history,
    # generic recovery-question definitions, and SQLite AUTOINCREMENT counters.
    # Every other table is terrain/placement, player/account, log, security, or
    # generated operational state and must be empty even when a future table is
    # unfamiliar.
    allowed_static_seed_tables = {
        "db_patches", "recovery_questions", "sqlite_sequence",
    }
    for table, count in table_counts.items():
        if count and table not in allowed_static_seed_tables:
            raise SystemExit(
                f"Builder database seed contains forbidden user/operational {table} state"
            )
except sqlite3.Error as error:
    raise SystemExit(f"Builder database seed is not a valid readable SQLite database: {error}")
finally:
    if "connection" in locals():
        connection.close()
PY
}

write_package_manifest() {
	local stage="$1"
	(
		cd "$stage"
		find . -type f ! -name 'PACKAGE-MANIFEST.sha256' -print0 \
			| LC_ALL=C sort -z \
			| xargs -0 sha256sum > PACKAGE-MANIFEST.sha256
	)
}

for stage in "$LINUX_STAGE" "$WINDOWS_STAGE"; do
	validate_stage "$stage"
	write_package_manifest "$stage"
	(
		cd "$stage"
		sha256sum -c PACKAGE-MANIFEST.sha256 >/dev/null
	)
done

require_release_git_state "$SOURCE_COMMIT"
require_runtime_provider_state "$RUNTIME_PROVIDER_COMMIT"

(
	cd "$STAGING_DIR/linux"
	zip -qr "$LINUX_ARCHIVE" "$PACKAGE_NAME"
)
(
	cd "$STAGING_DIR/windows"
	zip -qr "$WINDOWS_ARCHIVE" "$PACKAGE_NAME"
)
unzip -tq "$LINUX_ARCHIVE" >/dev/null \
	|| fail "Created Linux archive did not pass ZIP integrity verification"
unzip -tq "$WINDOWS_ARCHIVE" >/dev/null \
	|| fail "Created Windows archive did not pass ZIP integrity verification"
(
	cd "$OUTPUT_DIR"
	sha256sum "$(basename "$LINUX_ARCHIVE")" \
		"$(basename "$WINDOWS_ARCHIVE")" > SHA256SUMS.txt
)

rm -rf -- "$STAGING_DIR"

if [[ "$CANDIDATE_BUILD" == true ]]; then
	printf 'Created restricted World Builder 2 pre-gate candidate artifacts (not release artifacts):\n'
else
	printf 'Created World Builder 2 release artifacts:\n'
fi
printf '  %s\n' "$LINUX_ARCHIVE"
printf '  %s\n' "$WINDOWS_ARCHIVE"
printf '  %s\n' "$OUTPUT_DIR/SHA256SUMS.txt"
