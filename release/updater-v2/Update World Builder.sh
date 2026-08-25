#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
WORKSPACE="$ROOT_DIR/workspace"
PROJECTS="$ROOT_DIR/projects"
PROJECT_REGISTRY="$ROOT_DIR/project-registry.json"
ACTIVE_PROJECT="$ROOT_DIR/active-project.json"
UPDATES_DIR="$ROOT_DIR/updates"
LOCK_DIR="$ROOT_DIR/.world-builder-v2-update.lock"
REPOSITORY="An-actual-duck/rsc-world-editor"
API_URL="${WORLD_BUILDER_V2_RELEASE_API_URL:-https://api.github.com/repos/$REPOSITORY/releases?per_page=100}"
DOWNLOAD_ROOT="${WORLD_BUILDER_V2_RELEASE_DOWNLOAD_URL:-https://github.com/$REPOSITORY/releases/download}"
PRODUCT_ID="rsc-world-editor-v2"
PACKAGE_NAME="World Builder 2"
ARTIFACT_PREFIX="rsc-world-editor-v2"
WORLD_SOURCE_IDENTITY="target-adaptive-v1"
TAG_PREFIX="$ARTIFACT_PREFIX-"
AUTOMATIC=false
STAGE=""
BACKUP=""
NEW_MANIFEST=""
ROLLBACK_ARMED=false
PRESERVE_STAGE=false
IDENTITY_SOURCE_COMMIT=""
IDENTITY_RUNTIME_PROVIDER_COMMIT=""
VERSION_PATTERN='^v(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(-alpha\.(0|[1-9][0-9]*))?$'
NUMERIC_COMPARISON=0

fail() {
	printf 'World Builder 2 update failed: %s\n' "$*" >&2
	exit 1
}

for argument in "$@"; do
	case "$argument" in
		--automatic) AUTOMATIC=true ;;
		-h|--help)
			printf 'Usage: %s [--automatic]\n' "$0"
			exit 0
			;;
		*) fail "Unknown option: $argument" ;;
	esac
done

for command_name in awk cmp cp curl find grep mkdir mktemp rm rmdir sed sha256sum sort tr unzip; do
	command -v "$command_name" >/dev/null 2>&1 \
		|| fail "Missing required command: $command_name"
done

validate_version() {
	[[ "$1" =~ $VERSION_PATTERN ]]
}

compare_numeric_identifiers() {
	local candidate="$1" current="$2" index candidate_digit current_digit
	NUMERIC_COMPARISON=0
	if ((${#candidate} > ${#current})); then
		NUMERIC_COMPARISON=1
		return
	fi
	if ((${#candidate} < ${#current})); then
		NUMERIC_COMPARISON=-1
		return
	fi
	for ((index = 0; index < ${#candidate}; index++)); do
		candidate_digit="${candidate:index:1}"
		current_digit="${current:index:1}"
		if ((candidate_digit > current_digit)); then
			NUMERIC_COMPARISON=1
			return
		fi
		if ((candidate_digit < current_digit)); then
			NUMERIC_COMPARISON=-1
			return
		fi
	done
}

version_is_newer() {
	local candidate="$1" current="$2"
	local candidate_major candidate_minor candidate_patch candidate_alpha
	local current_major current_minor current_patch current_alpha index
	local -a candidate_parts current_parts

	[[ "$candidate" =~ $VERSION_PATTERN ]] \
		|| return 1
	candidate_major="${BASH_REMATCH[1]}"
	candidate_minor="${BASH_REMATCH[2]}"
	candidate_patch="${BASH_REMATCH[3]}"
	candidate_alpha="${BASH_REMATCH[5]:--1}"
	[[ "$current" =~ $VERSION_PATTERN ]] \
		|| return 1
	current_major="${BASH_REMATCH[1]}"
	current_minor="${BASH_REMATCH[2]}"
	current_patch="${BASH_REMATCH[3]}"
	current_alpha="${BASH_REMATCH[5]:--1}"

	candidate_parts=("$candidate_major" "$candidate_minor" "$candidate_patch")
	current_parts=("$current_major" "$current_minor" "$current_patch")
	for index in 0 1 2; do
		compare_numeric_identifiers \
			"${candidate_parts[index]}" "${current_parts[index]}"
		((NUMERIC_COMPARISON > 0)) && return 0
		((NUMERIC_COMPARISON < 0)) && return 1
	done
	if [[ "$candidate_alpha" == -1 ]]; then
		[[ "$current_alpha" != -1 ]]
		return
	fi
	[[ "$current_alpha" != -1 ]] || return 1
	compare_numeric_identifiers "$candidate_alpha" "$current_alpha"
	((NUMERIC_COMPARISON > 0))
}

extract_published_release_tags() {
	awk '
function mark_error() {
	parse_error = 1
}

function begin_release() {
	release_active = 1
	tag_count = draft_count = prerelease_count = 0
	tag_value = draft_value = prerelease_value = ""
	last_string = pending_key = ""
	last_string_available = expect_value = 0
}

function capture_value(type, value) {
	if (pending_key == "tag_name") {
		tag_count++
		tag_value = (type == "string" ? value : "")
	} else if (pending_key == "draft") {
		draft_count++
		draft_value = (type == "literal" ? value : "")
	} else if (pending_key == "prerelease") {
		prerelease_count++
		prerelease_value = (type == "literal" ? value : "")
	}
	pending_key = ""
	expect_value = 0
}

function finish_release() {
	if (expect_value) {
		mark_error()
	}
	if (tag_count == 1 && draft_count == 1 && prerelease_count == 1 \
			&& draft_value == "false" \
			&& (prerelease_value == "true" || prerelease_value == "false")) {
		print tag_value
	}
	release_active = 0
}

function process_token(type, value) {
	if (parse_error) {
		return
	}
	if (!root_started) {
		if (type == "punct" && value == "[") {
			root_started = 1
			array_depth = 1
			return
		}
		mark_error()
		return
	}
	if (root_closed) {
		mark_error()
		return
	}

	if (type == "punct") {
		if (value == "[") {
			if (array_depth == 1 && object_depth == 0) {
				mark_error()
				return
			}
			if (array_depth == 1 && object_depth == 1 && expect_value) {
				capture_value("container", "")
			}
			array_depth++
		} else if (value == "]") {
			if (array_depth <= 0) {
				mark_error()
				return
			}
			array_depth--
			if (array_depth == 0) {
				if (object_depth != 0) {
					mark_error()
					return
				}
				root_closed = 1
			}
		} else if (value == "{") {
			if (array_depth == 1 && object_depth == 0) {
				begin_release()
			} else if (array_depth == 1 && object_depth == 1 && expect_value) {
				capture_value("container", "")
			}
			object_depth++
		} else if (value == "}") {
			if (object_depth <= 0) {
				mark_error()
				return
			}
			if (array_depth == 1 && object_depth == 1 && release_active) {
				finish_release()
			}
			object_depth--
		} else if (value == ":") {
			if (array_depth == 1 && object_depth == 1 \
					&& last_string_available && !expect_value) {
				pending_key = last_string
				expect_value = 1
				last_string_available = 0
			} else if (array_depth == 1 && object_depth == 1) {
				mark_error()
			}
		} else if (value == "," && array_depth == 1 && object_depth == 1) {
			pending_key = last_string = ""
			expect_value = last_string_available = 0
		}
		return
	}

	if (array_depth == 1 && object_depth == 0) {
		mark_error()
		return
	}
	if (array_depth == 1 && object_depth == 1) {
		if (expect_value) {
			capture_value(type, value)
		} else if (type == "string") {
			last_string = value
			last_string_available = 1
		}
	}
}

function flush_literal() {
	if (literal != "") {
		process_token("literal", literal)
		literal = ""
	}
}

{
	for (character_index = 1; character_index <= length($0); character_index++) {
		character = substr($0, character_index, 1)
		if (in_string) {
			if (escaped) {
				string_value = string_value "\\" character
				escaped = 0
			} else if (character == "\\") {
				escaped = 1
			} else if (character == "\"") {
				in_string = 0
				process_token("string", string_value)
				string_value = ""
			} else {
				string_value = string_value character
			}
		} else if (character == "\"") {
			flush_literal()
			in_string = 1
		} else if (character ~ /[[:space:]]/) {
			flush_literal()
		} else if (index("{}[]:,", character)) {
			flush_literal()
			process_token("punct", character)
		} else {
			literal = literal character
		}
	}
	flush_literal()
	if (in_string) {
		mark_error()
	}
}

END {
	flush_literal()
	if (parse_error || in_string || !root_started || !root_closed \
			|| array_depth != 0 || object_depth != 0) {
		exit 2
	}
}
'
}

write_expected_identity() {
	local destination="$1" version="$2" release_tag="$3"
	local source_commit="$4" runtime_provider_commit="$5"
	cat > "$destination" <<EOF
{
  "schemaVersion": 1,
  "productId": "$PRODUCT_ID",
  "productGeneration": 2,
  "displayName": "$PACKAGE_NAME",
  "updateChannel": "$PRODUCT_ID",
  "releaseTag": "$release_tag",
  "artifactPrefix": "$ARTIFACT_PREFIX",
  "worldSourceIdentity": "$WORLD_SOURCE_IDENTITY",
  "automaticUpgradeFromProductIds": [
    "$PRODUCT_ID"
  ],
  "legacyProductId": "rsc-world-editor-v1",
  "legacyFinalTag": "v1.1.0",
  "legacyWorkspaceMigration": false,
  "version": "$version",
  "sourceCommit": "$source_commit",
  "runtimeProviderCommit": "$runtime_provider_commit"
}
EOF
}

validate_identity() {
	local identity="$1" expected_version="$2" expected_tag="$3"
	local source_commit runtime_provider_commit expected

	[[ -f "$identity" && ! -L "$identity" ]] || return 1
	source_commit="$(sed -n 's/^  "sourceCommit": "\([0-9a-f]\{40\}\)",$/\1/p' "$identity")"
	runtime_provider_commit="$(sed -n 's/^  "runtimeProviderCommit": "\([0-9a-f]\{40\}\)"$/\1/p' "$identity")"
	[[ "$source_commit" =~ ^[0-9a-f]{40}$ \
		&& "$runtime_provider_commit" =~ ^[0-9a-f]{40}$ ]] || return 1
	expected="$(mktemp "${TMPDIR:-/tmp}/world-builder-v2-identity-XXXXXX")" \
		|| return 1
	write_expected_identity "$expected" "$expected_version" "$expected_tag" \
		"$source_commit" "$runtime_provider_commit"
	if ! cmp -s "$expected" "$identity"; then
		rm -f -- "$expected"
		return 1
	fi
	rm -f -- "$expected"
	IDENTITY_SOURCE_COMMIT="$source_commit"
	IDENTITY_RUNTIME_PROVIDER_COMMIT="$runtime_provider_commit"
}

validate_relative_path() {
	local relative="$1" segment
	local -a segments
	[[ -n "$relative" && "$relative" != /* && "$relative" != *\\* \
		&& "$relative" != *$'\r'* && "$relative" != *$'\t'* ]] || return 1
	IFS='/' read -r -a segments <<< "$relative"
	((${#segments[@]} > 0)) || return 1
	for segment in "${segments[@]}"; do
		[[ -n "$segment" && "$segment" != . && "$segment" != .. ]] || return 1
	done
}

path_has_symlink_component() {
	local root="$1" relative="$2" segment candidate="$1"
	local -a segments
	IFS='/' read -r -a segments <<< "$relative"
	for segment in "${segments[@]}"; do
		candidate="$candidate/$segment"
		[[ ! -L "$candidate" ]] || return 0
	done
	return 1
}

is_durable_path() {
	local top="${1%%/*}"
	case "$top" in
		projects|project-registry.json|active-project.json|workspace|updates|\
		exports|backups|receipts|diagnostics|logs|settings|providers|credentials|recovery|\
		.world-builder-v2-update.lock|.workspace.world-builder.lock|\
		.project-registry.lock)
			return 0
			;;
	esac
	return 1
}

validate_manifest_paths() {
	local manifest="$1" line hash relative
	local -A seen=()
	[[ -s "$manifest" && ! -L "$manifest" ]] || return 1
	while IFS= read -r line || [[ -n "$line" ]]; do
		if [[ ! "$line" =~ ^([0-9a-f]{64})[[:space:]][[:space:]]\./(.+)$ ]]; then
			return 1
		fi
		hash="${BASH_REMATCH[1]}"
		relative="${BASH_REMATCH[2]}"
		[[ -n "$hash" ]] || return 1
		validate_relative_path "$relative" || return 1
		is_durable_path "$relative" && return 1
		[[ "$relative" != PACKAGE-MANIFEST.sha256 ]] || return 1
		[[ -z "${seen[$relative]+present}" ]] || return 1
		seen["$relative"]=1
	done < "$manifest"
	((${#seen[@]} > 0))
}

manifest_contains() {
	local manifest="$1" wanted="$2" line
	while IFS= read -r line || [[ -n "$line" ]]; do
		[[ "$line" =~ ^[0-9a-f]{64}[[:space:]][[:space:]]\./(.+)$ ]] || return 1
		[[ "${BASH_REMATCH[1]}" == "$wanted" ]] && return 0
	done < "$manifest"
	return 1
}

require_manifest_paths() {
	local manifest="$1" label="$2" required
	for required in \
		"VERSION.txt" \
		"SOURCE-COMMIT.txt" \
		"RUNTIME-PROVIDER-COMMIT.txt" \
		"RELEASE-IDENTITY.json" \
		"Start World Builder.sh" \
		"Start World Builder.cmd" \
		"Update World Builder.sh" \
		"Update World Builder.cmd" \
		"Update World Builder.ps1" \
		"Import Map Changes.sh" \
		"Import Map Changes.cmd" \
		"Recover Map Transaction.sh" \
		"Recover Map Transaction.cmd" \
		"Undo Last Map Import.sh" \
		"Undo Last Map Import.cmd" \
		"RUNTIME-ASSET-ALLOWLIST.txt" \
		"builder-runtime/Client_Base/Open_RSC_Client.jar" \
		"builder-runtime/server/core.jar" \
		"builder-runtime/server/plugins.jar" \
		"builder-runtime/server/inc/sqlite/world_builder_seed.db" \
		"builder-runtime/server/world-builder.conf" \
		"builder-runtime/server/conf/world-builder/adaptive-runtime-capability-v2.json" \
		"builder-runtime/launcher/world-builder-tools.jar" \
		"runtime/bin/java"; do
		manifest_contains "$manifest" "$required" \
			|| fail "$label package manifest omits required application file: $required"
	done
}

validate_application_paths() {
	local root="$1" manifest="$2" line relative source destination role
	local -A allowed=()
	for relative in \
		"ASSET-SOURCES.txt" "RUNTIME-PROVIDER-COMMIT.txt" \
		"EDITOR-ICON-CREDITS.txt" "Import Map Changes.cmd" \
		"Import Map Changes.sh" "LICENSE" "PLAYER-ASSET-SOURCES.txt" \
		"README.txt" "Recover Map Transaction.cmd" \
		"Recover Map Transaction.sh" "RELEASE-IDENTITY.json" \
		"RUNTIME-ASSET-ALLOWLIST.txt" \
		"SOURCE-COMMIT.txt" "Start World Builder.cmd" "Start World Builder.sh" \
		"Undo Last Map Import.cmd" "Undo Last Map Import.sh" \
		"Update World Builder.cmd" "Update World Builder.ps1" \
		"Update World Builder.sh" "VERSION.txt" \
		"builder-runtime/Client_Base/Open_RSC_Client.jar" \
		"builder-runtime/server/core.jar" "builder-runtime/server/plugins.jar" \
		"builder-runtime/server/world-builder.conf" \
		"builder-runtime/launcher/world-builder-tools.jar"; do
		allowed["$relative"]=1
	done
	for relative in \
		active-project-v1.schema.json \
		adaptive-contract-definitions-v1.schema.json \
		conversion-plan-v1.schema.json conversion-report-v1.schema.json \
		content-reconciliation-v1.schema.json \
		discovery-reconciliation-v1.schema.json \
		discovery-report-v2.schema.json export-manifest-v1.schema.json \
		export-manifest-v2.schema.json import-receipt-v1.schema.json \
		import-receipt-v3.schema.json project-manifest-v1.schema.json \
		project-manifest-v2.schema.json project-registry-v1.schema.json \
		project-content-bundle-v1.schema.json project-content-bundle-v2.schema.json \
		item-visual-mapping-v1.schema.json \
		npc-definition-mapping-v1.schema.json \
		region-bundle-manifest-v1.schema.json \
		region-compatibility-report-v1.schema.json \
		region-operation-plan-v1.schema.json region-selection-v1.schema.json \
		region-snapshot-v1.schema.json region-snapshot-v2.schema.json \
		source-snapshot-v2.schema.json target-capability-v1.schema.json \
		target-mutation-plan-v1.schema.json; do
		allowed["builder-runtime/launcher/schema/$relative"]=1
	done
	[[ -f "$root/RUNTIME-ASSET-ALLOWLIST.txt" \
		&& ! -L "$root/RUNTIME-ASSET-ALLOWLIST.txt" ]] || return 1
	while IFS=$'\t' read -r source destination role \
		|| [[ -n "$source$destination$role" ]]; do
		[[ -n "$source" && "$source" != \#* ]] || continue
		[[ -n "$destination" && -n "$role" && "$role" != *$'\t'* ]] || return 1
		validate_relative_path "$source" || return 1
		validate_relative_path "$destination" || return 1
		relative="builder-runtime/$destination"
		[[ -z "${allowed[$relative]+present}" ]] || return 1
		allowed["$relative"]=1
	done < "$root/RUNTIME-ASSET-ALLOWLIST.txt"
	while IFS= read -r line || [[ -n "$line" ]]; do
		[[ "$line" =~ ^[0-9a-f]{64}[[:space:]][[:space:]]\./(.+)$ ]] || return 1
		relative="${BASH_REMATCH[1]}"
		[[ -n "${allowed[$relative]+present}" || "$relative" == runtime/* ]] \
			|| return 1
	done < "$manifest"
}

require_linux_executables() {
	local root="$1" label="$2" required
	for required in \
		"Start World Builder.sh" \
		"Update World Builder.sh" \
		"Import Map Changes.sh" \
		"Recover Map Transaction.sh" \
		"Undo Last Map Import.sh" \
		"runtime/bin/java"; do
		[[ -x "$root/$required" ]] \
			|| fail "$label package file is not executable: $required"
	done
}

verify_manifest_files() {
	local root="$1" manifest="$2" exact_inventory="${3:-false}"
	local line relative actual_inventory manifest_inventory
	validate_manifest_paths "$manifest" || return 1
	while IFS= read -r line || [[ -n "$line" ]]; do
		[[ "$line" =~ ^[0-9a-f]{64}[[:space:]][[:space:]]\./(.+)$ ]] || return 1
		relative="${BASH_REMATCH[1]}"
		[[ -f "$root/$relative" ]] || return 1
		path_has_symlink_component "$root" "$relative" && return 1
	done < "$manifest"
	(cd "$root" && sha256sum -c "$manifest" >/dev/null) || return 1
	if [[ "$exact_inventory" == true ]]; then
		actual_inventory="$STAGE/actual-package-inventory.txt"
		manifest_inventory="$STAGE/manifest-package-inventory.txt"
		(
			cd "$root"
			find . -type f ! -name 'PACKAGE-MANIFEST.sha256' -print \
				| LC_ALL=C sort
		) > "$actual_inventory"
		sed -n 's/^[0-9a-f]\{64\}  \(\.\/.*\)$/\1/p' "$manifest" \
			| LC_ALL=C sort > "$manifest_inventory"
		cmp -s "$actual_inventory" "$manifest_inventory" || return 1
	fi
}

validate_archive_layout() {
	local archive="$1" entry relative
	local found_file=false
	local -A seen=()
	if unzip -Z -l "$archive" | awk '$1 ~ /^l/ { found = 1 } END { exit !found }'; then
		return 1
	fi
	while IFS= read -r entry || [[ -n "$entry" ]]; do
		[[ -n "$entry" && "$entry" != *\\* && "$entry" != /* \
			&& "$entry" != *$'\r'* && "$entry" != *$'\t'* ]] || return 1
		[[ -z "${seen[$entry]+present}" ]] || return 1
		seen["$entry"]=1
		[[ "$entry" == "$PACKAGE_NAME/"* ]] || return 1
		relative="${entry#"$PACKAGE_NAME/"}"
		[[ -n "$relative" ]] || continue
		relative="${relative%/}"
		validate_relative_path "$relative" || return 1
		[[ "$entry" == */ ]] || found_file=true
	done < <(unzip -Z1 "$archive")
	[[ "$found_file" == true ]]
}

prune_manifest_directories() {
	local root="$1" manifest="$2" line relative directory
	local -A directories=()
	while IFS= read -r line || [[ -n "$line" ]]; do
		[[ "$line" =~ ^[0-9a-f]{64}[[:space:]][[:space:]]\./(.+)$ ]] || continue
		relative="${BASH_REMATCH[1]}"
		directory="${relative%/*}"
		while [[ "$directory" != "$relative" && "$directory" != . ]]; do
			directories["$directory"]=1
			relative="$directory"
			directory="${relative%/*}"
		done
	done < "$manifest"
	if ((${#directories[@]})); then
		while IFS= read -r directory; do
			rmdir -- "$root/$directory" 2>/dev/null || true
		done < <(printf '%s\n' "${!directories[@]}" | LC_ALL=C sort -r)
	fi
}

remove_manifest_files() {
	local root="$1" manifest="$2" line relative status=0
	[[ -f "$manifest" ]] || return 1
	while IFS= read -r line || [[ -n "$line" ]]; do
		[[ "$line" =~ ^[0-9a-f]{64}[[:space:]][[:space:]]\./(.+)$ ]] || {
			status=1
			continue
		}
		relative="${BASH_REMATCH[1]}"
		if path_has_symlink_component "$root" "$relative"; then
			status=1
			continue
		fi
		rm -f -- "$root/$relative" || status=1
	done < "$manifest"
	rm -f -- "$root/PACKAGE-MANIFEST.sha256" || status=1
	prune_manifest_directories "$root" "$manifest"
	return "$status"
}

restore_previous_installation() {
	local status=0
	[[ -n "$BACKUP" && -d "$BACKUP" ]] || return 1
	if [[ -n "$NEW_MANIFEST" && -f "$NEW_MANIFEST" ]]; then
		remove_manifest_files "$ROOT_DIR" "$NEW_MANIFEST" || return 1
	fi
	while IFS= read -r line || [[ -n "$line" ]]; do
		[[ "$line" =~ ^[0-9a-f]{64}[[:space:]][[:space:]]\./(.+)$ ]] || return 1
		path_has_symlink_component "$ROOT_DIR" "${BASH_REMATCH[1]}" && return 1
	done < "$BACKUP/PACKAGE-MANIFEST.sha256"
	cp -a "$BACKUP"/. "$ROOT_DIR/" || status=1
	if [[ $status -eq 0 ]]; then
		verify_manifest_files "$ROOT_DIR" \
			"$ROOT_DIR/PACKAGE-MANIFEST.sha256" false || status=1
	fi
	return "$status"
}

cleanup() {
	local status=$?
	trap - EXIT INT TERM
	if [[ "$ROLLBACK_ARMED" == true ]]; then
		if restore_previous_installation; then
			printf 'The previous World Builder 2 application files were restored.\n' >&2
		else
			PRESERVE_STAGE=true
			printf 'CRITICAL: automatic rollback could not fully restore the previous application. Preserve workspace/ and recovery staging at %s.\n' "$STAGE" >&2
			status=1
		fi
	fi
	if [[ "$PRESERVE_STAGE" != true && -n "$STAGE" \
		&& "$STAGE" == "$UPDATES_DIR/.update-"* ]]; then
		rm -rf -- "$STAGE"
	fi
	if [[ "$PRESERVE_STAGE" != true ]]; then
		rmdir -- "$LOCK_DIR" 2>/dev/null || true
	fi
	exit "$status"
}

[[ -f "$ROOT_DIR/VERSION.txt" && ! -L "$ROOT_DIR/VERSION.txt" ]] \
	|| fail "VERSION.txt is missing or unsafe"
CURRENT_VERSION="$(tr -d '\r\n' < "$ROOT_DIR/VERSION.txt")"
validate_version "$CURRENT_VERSION" \
	|| fail "VERSION.txt does not contain a supported World Builder 2 version"
CURRENT_RELEASE_TAG="$TAG_PREFIX${CURRENT_VERSION#v}"
if [[ -f "$ROOT_DIR/RELEASE-IDENTITY.json" ]] \
	&& grep -F '"worldCoordinateModel": "signed-layered-v1"' \
		"$ROOT_DIR/RELEASE-IDENTITY.json" >/dev/null; then
	fail "This is a historical pre-adaptive World Builder 2 installation. Automatic relabelling or workspace migration is unsupported; preserve the complete folder and install adaptive World Builder 2 separately."
fi
validate_identity "$ROOT_DIR/RELEASE-IDENTITY.json" \
	"$CURRENT_VERSION" "$CURRENT_RELEASE_TAG" \
	|| fail "Installed release identity is missing, malformed, or not $PRODUCT_ID"
[[ "$(tr -d '\r\n' < "$ROOT_DIR/SOURCE-COMMIT.txt")" == "$IDENTITY_SOURCE_COMMIT" \
	&& "$(tr -d '\r\n' < "$ROOT_DIR/RUNTIME-PROVIDER-COMMIT.txt")" == "$IDENTITY_RUNTIME_PROVIDER_COMMIT" ]] \
	|| fail "Installed release provenance does not match its v2 identity"
verify_manifest_files "$ROOT_DIR" "$ROOT_DIR/PACKAGE-MANIFEST.sha256" false \
	|| fail "Installed World Builder 2 application manifest is missing or does not verify"
require_manifest_paths "$ROOT_DIR/PACKAGE-MANIFEST.sha256" "Installed"
validate_application_paths "$ROOT_DIR" "$ROOT_DIR/PACKAGE-MANIFEST.sha256" \
	|| fail "Installed package manifest owns a path outside the content-neutral application allowlist"
require_linux_executables "$ROOT_DIR" "Installed"

if [[ -e "$WORKSPACE" && ! -e "$PROJECT_REGISTRY" ]]; then
	fail "This is a historical pre-adaptive World Builder 2 installation. Its workspace was preserved, but it cannot be relabelled or migrated automatically. Keep the complete installation for matching-version recovery and install adaptive World Builder 2 in a separate folder."
fi

pid_files=("$WORKSPACE/run/server.pid" "$WORKSPACE/run/client.pid")
if [[ -d "$PROJECTS" && ! -L "$PROJECTS" ]]; then
	while IFS= read -r -d '' pid_file; do
		pid_files+=("$pid_file")
	done < <(find "$PROJECTS" -type f \( -path '*/run/server.pid' -o -path '*/run/client.pid' \) -print0)
fi
for pid_file in "${pid_files[@]}"; do
	if [[ -f "$pid_file" ]]; then
		pid="$(tr -d '\r\n' < "$pid_file")"
		if [[ "$pid" =~ ^[0-9]+$ ]] && kill -0 "$pid" 2>/dev/null; then
			fail "Close World Builder 2 before updating (active process $pid)"
		fi
	fi
done

[[ ! -L "$UPDATES_DIR" && ( ! -e "$UPDATES_DIR" || -d "$UPDATES_DIR" ) ]] \
	|| fail "The updates path is unsafe; preserve it for review before retrying"
mkdir -p "$UPDATES_DIR"
mkdir "$LOCK_DIR" 2>/dev/null \
	|| fail "Another World Builder 2 update is already running"
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

release_json="$(curl -fsSL --connect-timeout 10 --max-time 30 "$API_URL")" \
	|| fail "Unable to query the World Builder 2 release channel"
release_tags_text="$(printf '%s\n' "$release_json" | extract_published_release_tags)" \
	|| fail "The World Builder 2 release channel returned malformed JSON"
release_tags=()
if [[ -n "$release_tags_text" ]]; then
	mapfile -t release_tags <<< "$release_tags_text"
fi
LATEST_RELEASE_TAG=""
LATEST_VERSION=""
declare -A seen_release_tags=()
for candidate_tag in "${release_tags[@]}"; do
	[[ "$candidate_tag" == "$TAG_PREFIX"* ]] || continue
	candidate_version="v${candidate_tag#"$TAG_PREFIX"}"
	validate_version "$candidate_version" || continue
	[[ -z "${seen_release_tags[$candidate_tag]+present}" ]] \
		|| fail "The World Builder 2 release channel returned duplicate tag $candidate_tag"
	seen_release_tags["$candidate_tag"]=1
	if [[ -z "$LATEST_VERSION" ]] \
		|| version_is_newer "$candidate_version" "$LATEST_VERSION"; then
		LATEST_RELEASE_TAG="$candidate_tag"
		LATEST_VERSION="$candidate_version"
	fi
done
[[ -n "$LATEST_RELEASE_TAG" ]] \
	|| fail "The World Builder 2 release channel contains no published valid $PRODUCT_ID release"

if [[ "$LATEST_VERSION" == "$CURRENT_VERSION" ]]; then
	$AUTOMATIC || printf 'World Builder 2 is up to date (%s).\n' "$CURRENT_VERSION"
	exit 0
fi
if ! version_is_newer "$LATEST_VERSION" "$CURRENT_VERSION"; then
	$AUTOMATIC \
		|| printf 'Installed World Builder 2 %s is newer than channel release %s; no downgrade was performed.\n' \
			"$CURRENT_VERSION" "$LATEST_VERSION"
	exit 0
fi

ASSET_NAME="$ARTIFACT_PREFIX-${LATEST_VERSION#v}-linux-x64.zip"
STAGE="$(mktemp -d "$UPDATES_DIR/.update-${LATEST_VERSION#v}-XXXXXX")"
ARCHIVE="$STAGE/$ASSET_NAME"
CHECKSUMS="$STAGE/SHA256SUMS.txt"
EXTRACTED="$STAGE/extracted"

printf 'Updating World Builder 2 from %s to %s...\n' \
	"$CURRENT_VERSION" "$LATEST_VERSION"
curl -fL --connect-timeout 10 --max-time 600 \
	"$DOWNLOAD_ROOT/$LATEST_RELEASE_TAG/$ASSET_NAME" -o "$ARCHIVE" \
	|| fail "Unable to download $ASSET_NAME"
curl -fL --connect-timeout 10 --max-time 60 \
	"$DOWNLOAD_ROOT/$LATEST_RELEASE_TAG/SHA256SUMS.txt" -o "$CHECKSUMS" \
	|| fail "Unable to download SHA256SUMS.txt"

mapfile -t expected_hashes < <(
	awk -v name="$ASSET_NAME" '$2 == name || $2 == "*" name {print $1}' "$CHECKSUMS"
)
[[ ${#expected_hashes[@]} -eq 1 && "${expected_hashes[0]}" =~ ^[0-9a-fA-F]{64}$ ]] \
	|| fail "SHA256SUMS.txt does not contain one unambiguous checksum for $ASSET_NAME"
actual_hash="$(sha256sum "$ARCHIVE" | awk '{print $1}')"
[[ "${actual_hash,,}" == "${expected_hashes[0],,}" ]] \
	|| fail "Downloaded archive checksum does not match the published checksum"

validate_archive_layout "$ARCHIVE" \
	|| fail "Downloaded archive has an unsafe or unexpected directory layout"
mkdir -p "$EXTRACTED"
unzip -q "$ARCHIVE" -d "$EXTRACTED" \
	|| fail "Unable to extract the downloaded archive"
PACKAGE_ROOT="$EXTRACTED/$PACKAGE_NAME"
[[ -d "$PACKAGE_ROOT" && ! -L "$PACKAGE_ROOT" ]] \
	|| fail "Downloaded archive has an unexpected package root"
mapfile -t extracted_roots < <(find "$EXTRACTED" -mindepth 1 -maxdepth 1 -print)
[[ ${#extracted_roots[@]} -eq 1 && "${extracted_roots[0]}" == "$PACKAGE_ROOT" ]] \
	|| fail "Downloaded archive contains entries outside the World Builder 2 package"
if find "$PACKAGE_ROOT" ! -type f ! -type d -print -quit | grep -q .; then
	fail "Downloaded package contains a link or unsupported filesystem entry"
fi

NEW_MANIFEST="$PACKAGE_ROOT/PACKAGE-MANIFEST.sha256"
verify_manifest_files "$PACKAGE_ROOT" "$NEW_MANIFEST" true \
	|| fail "Downloaded package manifest, inventory, or file verification failed"
require_manifest_paths "$NEW_MANIFEST" "Downloaded"
validate_application_paths "$PACKAGE_ROOT" "$NEW_MANIFEST" \
	|| fail "Downloaded package manifest owns a path outside the content-neutral application allowlist"
require_linux_executables "$PACKAGE_ROOT" "Downloaded"
[[ "$(tr -d '\r\n' < "$PACKAGE_ROOT/VERSION.txt")" == "$LATEST_VERSION" ]] \
	|| fail "Downloaded package version does not match its release tag"
validate_identity "$PACKAGE_ROOT/RELEASE-IDENTITY.json" \
	"$LATEST_VERSION" "$LATEST_RELEASE_TAG" \
	|| fail "Downloaded package is not an exact $PRODUCT_ID release"
[[ "$(tr -d '\r\n' < "$PACKAGE_ROOT/SOURCE-COMMIT.txt")" == "$IDENTITY_SOURCE_COMMIT" \
	&& "$(tr -d '\r\n' < "$PACKAGE_ROOT/RUNTIME-PROVIDER-COMMIT.txt")" == "$IDENTITY_RUNTIME_PROVIDER_COMMIT" ]] \
	|| fail "Downloaded package provenance does not match its v2 identity"

declare -A old_managed=()
while IFS= read -r line || [[ -n "$line" ]]; do
	[[ "$line" =~ ^[0-9a-f]{64}[[:space:]][[:space:]]\./(.+)$ ]] \
		|| fail "Installed package manifest became malformed"
	old_managed["${BASH_REMATCH[1]}"]=1
done < "$ROOT_DIR/PACKAGE-MANIFEST.sha256"
old_managed["PACKAGE-MANIFEST.sha256"]=1

while IFS= read -r line || [[ -n "$line" ]]; do
	[[ "$line" =~ ^[0-9a-f]{64}[[:space:]][[:space:]]\./(.+)$ ]] \
		|| fail "Downloaded package manifest became malformed"
	relative="${BASH_REMATCH[1]}"
	destination="$ROOT_DIR/$relative"
	if [[ -e "$destination" || -L "$destination" ]]; then
		[[ -n "${old_managed[$relative]+present}" ]] \
			|| fail "Update would overwrite an unmanaged installed path: $relative"
	fi
	ancestor="${relative%/*}"
	while [[ "$ancestor" != "$relative" && "$ancestor" != . ]]; do
		[[ ! -L "$ROOT_DIR/$ancestor" ]] \
			|| fail "Update path crosses an installed symbolic link: $ancestor"
		if [[ -e "$ROOT_DIR/$ancestor" && ! -d "$ROOT_DIR/$ancestor" ]]; then
			[[ -n "${old_managed[$ancestor]+present}" ]] \
				|| fail "Update path is blocked by unmanaged installed data: $ancestor"
		fi
		relative="$ancestor"
		ancestor="${relative%/*}"
	done
done < "$NEW_MANIFEST"

BACKUP="$STAGE/backup"
mkdir -p "$BACKUP"
while IFS= read -r line || [[ -n "$line" ]]; do
	[[ "$line" =~ ^[0-9a-f]{64}[[:space:]][[:space:]]\./(.+)$ ]] \
		|| fail "Installed package manifest became malformed"
	relative="${BASH_REMATCH[1]}"
	backup_parent="${relative%/*}"
	if [[ "$backup_parent" != "$relative" ]]; then
		mkdir -p "$BACKUP/$backup_parent"
	fi
	cp -a "$ROOT_DIR/$relative" "$BACKUP/$relative" \
		|| fail "Unable to prepare the update rollback copy"
done < "$ROOT_DIR/PACKAGE-MANIFEST.sha256"
cp -a "$ROOT_DIR/PACKAGE-MANIFEST.sha256" "$BACKUP/PACKAGE-MANIFEST.sha256" \
	|| fail "Unable to preserve the installed package manifest"

ROLLBACK_ARMED=true
remove_manifest_files "$ROOT_DIR" "$BACKUP/PACKAGE-MANIFEST.sha256" \
	|| fail "Unable to clear the previous managed application files"
while IFS= read -r line || [[ -n "$line" ]]; do
	[[ "$line" =~ ^[0-9a-f]{64}[[:space:]][[:space:]]\./(.+)$ ]] \
		|| fail "Downloaded package manifest became malformed during installation"
	relative="${BASH_REMATCH[1]}"
	install_parent="${relative%/*}"
	if [[ "$install_parent" != "$relative" ]]; then
		mkdir -p "$ROOT_DIR/$install_parent" \
			|| fail "Unable to create an application directory: $install_parent"
	fi
	cp -a "$PACKAGE_ROOT/$relative" "$ROOT_DIR/$relative" \
		|| fail "Unable to install downloaded application file: $relative"
done < "$NEW_MANIFEST"
cp -a "$NEW_MANIFEST" "$ROOT_DIR/PACKAGE-MANIFEST.sha256" \
	|| fail "Unable to install the downloaded package manifest"
verify_manifest_files "$ROOT_DIR" "$ROOT_DIR/PACKAGE-MANIFEST.sha256" false \
	|| fail "Installed update verification failed"
require_manifest_paths "$ROOT_DIR/PACKAGE-MANIFEST.sha256" "Installed update"
validate_application_paths "$ROOT_DIR" "$ROOT_DIR/PACKAGE-MANIFEST.sha256" \
	|| fail "Installed update escaped the content-neutral application allowlist"
require_linux_executables "$ROOT_DIR" "Installed update"
validate_identity "$ROOT_DIR/RELEASE-IDENTITY.json" \
	"$LATEST_VERSION" "$LATEST_RELEASE_TAG" \
	|| fail "Installed update identity verification failed"
[[ "$(tr -d '\r\n' < "$ROOT_DIR/VERSION.txt")" == "$LATEST_VERSION" ]] \
	|| fail "Installed update version verification failed"
if [[ -e "$PROJECT_REGISTRY" || -e "$ACTIVE_PROJECT" || -e "$PROJECTS" ]]; then
	[[ -f "$PROJECT_REGISTRY" && ! -L "$PROJECT_REGISTRY" \
		&& -f "$ACTIVE_PROJECT" && ! -L "$ACTIVE_PROJECT" \
		&& -d "$PROJECTS" && ! -L "$PROJECTS" ]] \
		|| fail "Adaptive project state is incomplete or unsafe after the application update"
	"$ROOT_DIR/runtime/bin/java" -jar \
		"$ROOT_DIR/builder-runtime/launcher/world-builder-tools.jar" \
		open-project --installation-root "$ROOT_DIR" --target-root "$ROOT_DIR/.." \
		--validate-only \
		>/dev/null \
		|| fail "The selected adaptive project is incompatible with the updated runtime"
fi
ROLLBACK_ARMED=false

printf 'World Builder 2 updated successfully to %s.\n' "$LATEST_VERSION"
if [[ -d "$PROJECTS" || -d "$WORKSPACE" ]]; then
	printf 'All adaptive projects, registries, providers, exports, backups, receipts, diagnostics, settings, logs, and historical workspace state were preserved.\n'
	printf 'The selected project passed the compatibility checks available in this runtime.\n'
fi
