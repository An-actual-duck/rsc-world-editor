#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
WORKSPACE="$ROOT_DIR/workspace"
UPDATES_DIR="$ROOT_DIR/updates"
LOCK_DIR="$ROOT_DIR/.world-builder-v2-update.lock"
REPOSITORY="An-actual-duck/rsc-world-editor"
API_URL="${WORLD_BUILDER_V2_RELEASE_API_URL:-https://api.github.com/repos/$REPOSITORY/releases/latest}"
DOWNLOAD_ROOT="${WORLD_BUILDER_V2_RELEASE_DOWNLOAD_URL:-https://github.com/$REPOSITORY/releases/download}"
PRODUCT_ID="rsc-world-editor-v2"
PACKAGE_NAME="Spoiled Milk World Builder 2"
ARTIFACT_PREFIX="rsc-world-editor-v2"
TAG_PREFIX="$ARTIFACT_PREFIX-"
AUTOMATIC=false
STAGE=""
BACKUP=""
NEW_MANIFEST=""
ROLLBACK_ARMED=false
IDENTITY_SOURCE_COMMIT=""
IDENTITY_CORE_COMMIT=""

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
	[[ "$1" =~ ^v[0-9]+\.[0-9]+\.[0-9]+(-alpha\.[0-9]+)?$ ]]
}

version_is_newer() {
	local candidate="$1" current="$2"
	local candidate_major candidate_minor candidate_patch candidate_alpha
	local current_major current_minor current_patch current_alpha index

	[[ "$candidate" =~ ^v([0-9]+)\.([0-9]+)\.([0-9]+)(-alpha\.([0-9]+))?$ ]] \
		|| return 1
	candidate_major=$((10#${BASH_REMATCH[1]}))
	candidate_minor=$((10#${BASH_REMATCH[2]}))
	candidate_patch=$((10#${BASH_REMATCH[3]}))
	candidate_alpha="${BASH_REMATCH[5]:--1}"
	[[ "$current" =~ ^v([0-9]+)\.([0-9]+)\.([0-9]+)(-alpha\.([0-9]+))?$ ]] \
		|| return 1
	current_major=$((10#${BASH_REMATCH[1]}))
	current_minor=$((10#${BASH_REMATCH[2]}))
	current_patch=$((10#${BASH_REMATCH[3]}))
	current_alpha="${BASH_REMATCH[5]:--1}"

	for index in 1 2 3; do
		case "$index" in
			1) candidate_value=$candidate_major; current_value=$current_major ;;
			2) candidate_value=$candidate_minor; current_value=$current_minor ;;
			3) candidate_value=$candidate_patch; current_value=$current_patch ;;
		esac
		((candidate_value > current_value)) && return 0
		((candidate_value < current_value)) && return 1
	done
	if [[ "$candidate_alpha" == -1 ]]; then
		[[ "$current_alpha" != -1 ]]
		return
	fi
	[[ "$current_alpha" != -1 ]] || return 1
	((10#$candidate_alpha > 10#$current_alpha))
}

write_expected_identity() {
	local destination="$1" version="$2" release_tag="$3"
	local source_commit="$4" core_commit="$5"
	cat > "$destination" <<EOF
{
  "schemaVersion": 1,
  "productId": "$PRODUCT_ID",
  "productGeneration": 2,
  "displayName": "$PACKAGE_NAME",
  "updateChannel": "$PRODUCT_ID",
  "releaseTag": "$release_tag",
  "artifactPrefix": "$ARTIFACT_PREFIX",
  "worldCoordinateModel": "signed-layered-v1",
  "automaticUpgradeFromProductIds": [
    "$PRODUCT_ID"
  ],
  "legacyProductId": "rsc-world-editor-v1",
  "legacyFinalTag": "v1.1.0",
  "legacyWorkspaceMigration": false,
  "version": "$version",
  "sourceCommit": "$source_commit",
  "coreSourceCommit": "$core_commit"
}
EOF
}

validate_identity() {
	local identity="$1" expected_version="$2" expected_tag="$3"
	local source_commit core_commit expected

	[[ -f "$identity" && ! -L "$identity" ]] || return 1
	source_commit="$(sed -n 's/^  "sourceCommit": "\([0-9a-f]\{40\}\)",$/\1/p' "$identity")"
	core_commit="$(sed -n 's/^  "coreSourceCommit": "\([0-9a-f]\{40\}\)"$/\1/p' "$identity")"
	[[ "$source_commit" =~ ^[0-9a-f]{40}$ \
		&& "$core_commit" =~ ^[0-9a-f]{40}$ ]] || return 1
	expected="$(mktemp "${TMPDIR:-/tmp}/world-builder-v2-identity-XXXXXX")" \
		|| return 1
	write_expected_identity "$expected" "$expected_version" "$expected_tag" \
		"$source_commit" "$core_commit"
	if ! cmp -s "$expected" "$identity"; then
		rm -f -- "$expected"
		return 1
	fi
	rm -f -- "$expected"
	IDENTITY_SOURCE_COMMIT="$source_commit"
	IDENTITY_CORE_COMMIT="$core_commit"
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

is_durable_path() {
	local top="${1%%/*}"
	case "$top" in
		workspace|updates|exports|backups|receipts|logs|credentials|\
		.world-builder-v2-update.lock|.workspace.world-builder.lock)
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

verify_manifest_files() {
	local root="$1" manifest="$2" exact_inventory="${3:-false}"
	local line relative actual_inventory manifest_inventory
	validate_manifest_paths "$manifest" || return 1
	while IFS= read -r line || [[ -n "$line" ]]; do
		[[ "$line" =~ ^[0-9a-f]{64}[[:space:]][[:space:]]\./(.+)$ ]] || return 1
		relative="${BASH_REMATCH[1]}"
		[[ -f "$root/$relative" && ! -L "$root/$relative" ]] || return 1
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
	if unzip -Z -l "$archive" | grep -Eq '^l'; then
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
		remove_manifest_files "$ROOT_DIR" "$NEW_MANIFEST" || status=1
	fi
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
			printf 'CRITICAL: automatic rollback could not fully restore the previous application. Preserve workspace/ and the updates directory for recovery.\n' >&2
			status=1
		fi
	fi
	if [[ -n "$STAGE" && "$STAGE" == "$UPDATES_DIR/.update-"* ]]; then
		rm -rf -- "$STAGE"
	fi
	rmdir -- "$LOCK_DIR" 2>/dev/null || true
	exit "$status"
}

[[ -f "$ROOT_DIR/VERSION.txt" && ! -L "$ROOT_DIR/VERSION.txt" ]] \
	|| fail "VERSION.txt is missing or unsafe"
CURRENT_VERSION="$(tr -d '\r\n' < "$ROOT_DIR/VERSION.txt")"
validate_version "$CURRENT_VERSION" \
	|| fail "VERSION.txt does not contain a supported World Builder 2 version"
CURRENT_RELEASE_TAG="$TAG_PREFIX${CURRENT_VERSION#v}"
validate_identity "$ROOT_DIR/RELEASE-IDENTITY.json" \
	"$CURRENT_VERSION" "$CURRENT_RELEASE_TAG" \
	|| fail "Installed release identity is missing, malformed, or not $PRODUCT_ID"
[[ "$(tr -d '\r\n' < "$ROOT_DIR/SOURCE-COMMIT.txt")" == "$IDENTITY_SOURCE_COMMIT" \
	&& "$(tr -d '\r\n' < "$ROOT_DIR/CORE-SOURCE-COMMIT.txt")" == "$IDENTITY_CORE_COMMIT" ]] \
	|| fail "Installed release provenance does not match its v2 identity"
verify_manifest_files "$ROOT_DIR" "$ROOT_DIR/PACKAGE-MANIFEST.sha256" false \
	|| fail "Installed World Builder 2 application manifest is missing or does not verify"

for pid_file in "$WORKSPACE/run/server.pid" "$WORKSPACE/run/client.pid"; do
	if [[ -f "$pid_file" ]]; then
		pid="$(tr -d '\r\n' < "$pid_file")"
		if [[ "$pid" =~ ^[0-9]+$ ]] && kill -0 "$pid" 2>/dev/null; then
			fail "Close World Builder 2 before updating (active process $pid)"
		fi
	fi
done

mkdir -p "$UPDATES_DIR"
mkdir "$LOCK_DIR" 2>/dev/null \
	|| fail "Another World Builder 2 update is already running"
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

release_json="$(curl -fsSL --connect-timeout 10 --max-time 30 "$API_URL")" \
	|| fail "Unable to query the World Builder 2 release channel"
mapfile -t release_tags < <(
	printf '%s\n' "$release_json" | tr ',' '\n' \
		| sed -n 's/.*"tag_name"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p'
)
[[ ${#release_tags[@]} -eq 1 ]] \
	|| fail "The release service returned an ambiguous release identity"
LATEST_RELEASE_TAG="${release_tags[0]}"
[[ "$LATEST_RELEASE_TAG" =~ ^rsc-world-editor-v2-[0-9]+\.[0-9]+\.[0-9]+(-alpha\.[0-9]+)?$ ]] \
	|| fail "The latest release is not on the $PRODUCT_ID update channel"
LATEST_VERSION="v${LATEST_RELEASE_TAG#"$TAG_PREFIX"}"
validate_version "$LATEST_VERSION" \
	|| fail "The latest World Builder 2 release has an unsupported version"

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
if find "$PACKAGE_ROOT" -type l -print -quit | grep -q .; then
	fail "Downloaded package contains a symbolic link"
fi

NEW_MANIFEST="$PACKAGE_ROOT/PACKAGE-MANIFEST.sha256"
verify_manifest_files "$PACKAGE_ROOT" "$NEW_MANIFEST" true \
	|| fail "Downloaded package manifest, inventory, or file verification failed"
for required_managed_path in \
	"VERSION.txt" \
	"SOURCE-COMMIT.txt" \
	"CORE-SOURCE-COMMIT.txt" \
	"RELEASE-IDENTITY.json" \
	"Start World Builder.sh" \
	"Start World Builder.cmd" \
	"Update World Builder.sh" \
	"Update World Builder.cmd" \
	"Update World Builder.ps1"; do
	manifest_contains "$NEW_MANIFEST" "$required_managed_path" \
		|| fail "Downloaded package manifest omits required application file: $required_managed_path"
done
[[ "$(tr -d '\r\n' < "$PACKAGE_ROOT/VERSION.txt")" == "$LATEST_VERSION" ]] \
	|| fail "Downloaded package version does not match its release tag"
validate_identity "$PACKAGE_ROOT/RELEASE-IDENTITY.json" \
	"$LATEST_VERSION" "$LATEST_RELEASE_TAG" \
	|| fail "Downloaded package is not an exact $PRODUCT_ID release"
[[ "$(tr -d '\r\n' < "$PACKAGE_ROOT/SOURCE-COMMIT.txt")" == "$IDENTITY_SOURCE_COMMIT" \
	&& "$(tr -d '\r\n' < "$PACKAGE_ROOT/CORE-SOURCE-COMMIT.txt")" == "$IDENTITY_CORE_COMMIT" ]] \
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
cp -a "$PACKAGE_ROOT"/. "$ROOT_DIR/" \
	|| fail "Unable to install the downloaded application files"
verify_manifest_files "$ROOT_DIR" "$ROOT_DIR/PACKAGE-MANIFEST.sha256" false \
	|| fail "Installed update verification failed"
validate_identity "$ROOT_DIR/RELEASE-IDENTITY.json" \
	"$LATEST_VERSION" "$LATEST_RELEASE_TAG" \
	|| fail "Installed update identity verification failed"
[[ "$(tr -d '\r\n' < "$ROOT_DIR/VERSION.txt")" == "$LATEST_VERSION" ]] \
	|| fail "Installed update version verification failed"
ROLLBACK_ARMED=false

printf 'World Builder 2 updated successfully to %s.\n' "$LATEST_VERSION"
if [[ -d "$WORKSPACE" ]]; then
	printf 'Your existing v2 workspace, exports, backups, receipts, credentials, database, and logs were preserved.\n'
	printf 'The existing project remains tied to the runtime snapshot with which it was created.\n'
fi
