#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WORKSPACE="$ROOT_DIR/workspace"
UPDATES_DIR="$ROOT_DIR/updates"
LOCK_DIR="$ROOT_DIR/.world-builder-update.lock"
REPOSITORY="An-actual-duck/rsc-world-editor"
API_URL="${WORLD_BUILDER_RELEASE_API_URL:-https://api.github.com/repos/$REPOSITORY/releases/latest}"
DOWNLOAD_ROOT="${WORLD_BUILDER_RELEASE_DOWNLOAD_URL:-https://github.com/$REPOSITORY/releases/download}"
AUTOMATIC=false
STAGE=""

fail() {
	printf 'World Builder update failed: %s\n' "$*" >&2
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

for command_name in curl unzip sha256sum awk; do
	command -v "$command_name" >/dev/null 2>&1 \
		|| fail "Missing required command: $command_name"
done

[[ -f "$ROOT_DIR/VERSION.txt" ]] || fail "VERSION.txt is missing"
CURRENT_VERSION="$(tr -d '\r\n' < "$ROOT_DIR/VERSION.txt")"
[[ "$CURRENT_VERSION" =~ ^v[0-9]+\.[0-9]+\.[0-9]+(-alpha\.[0-9]+)?$ ]] \
	|| fail "VERSION.txt does not contain a supported semantic version"

for pid_file in "$WORKSPACE/run/server.pid" "$WORKSPACE/run/client.pid"; do
	if [[ -f "$pid_file" ]]; then
		pid="$(tr -cd '0-9' < "$pid_file")"
		if [[ -n "$pid" ]] && kill -0 "$pid" 2>/dev/null; then
			fail "Close World Builder before updating (active process $pid)"
		fi
	fi
done

mkdir -p "$UPDATES_DIR"
mkdir "$LOCK_DIR" 2>/dev/null || fail "Another World Builder update is already running"
cleanup() {
	[[ -z "$STAGE" ]] || rm -rf "$STAGE"
	rmdir "$LOCK_DIR" 2>/dev/null || true
}
trap cleanup EXIT INT TERM

release_json="$(curl -fsSL --connect-timeout 10 --max-time 30 "$API_URL")" \
	|| fail "Unable to query the GitHub release channel"
LATEST_VERSION="$(printf '%s\n' "$release_json" | tr ',' '\n' \
	| sed -n 's/.*"tag_name"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' | head -n 1)"
[[ "$LATEST_VERSION" =~ ^v[0-9]+\.[0-9]+\.[0-9]+(-alpha\.[0-9]+)?$ ]] \
	|| fail "The latest GitHub release does not use a supported semantic version"

if [[ "$LATEST_VERSION" == "$CURRENT_VERSION" ]]; then
	$AUTOMATIC || printf 'World Builder is up to date (%s).\n' "$CURRENT_VERSION"
	exit 0
fi

ASSET_NAME="rsc-world-editor-$LATEST_VERSION-linux-x64.zip"
STAGE="$(mktemp -d "$UPDATES_DIR/.update-$LATEST_VERSION-XXXXXX")"
ARCHIVE="$STAGE/$ASSET_NAME"
CHECKSUMS="$STAGE/SHA256SUMS.txt"
EXTRACTED="$STAGE/extracted"

printf 'Updating World Builder from %s to %s...\n' "$CURRENT_VERSION" "$LATEST_VERSION"
curl -fL --connect-timeout 10 --max-time 600 \
	"$DOWNLOAD_ROOT/$LATEST_VERSION/$ASSET_NAME" -o "$ARCHIVE" \
	|| fail "Unable to download $ASSET_NAME"
curl -fL --connect-timeout 10 --max-time 60 \
	"$DOWNLOAD_ROOT/$LATEST_VERSION/SHA256SUMS.txt" -o "$CHECKSUMS" \
	|| fail "Unable to download SHA256SUMS.txt"

expected_hash="$(awk -v name="$ASSET_NAME" '$2 == name || $2 == "*" name {print $1; exit}' "$CHECKSUMS")"
[[ "$expected_hash" =~ ^[0-9a-fA-F]{64}$ ]] \
	|| fail "SHA256SUMS.txt does not contain $ASSET_NAME"
actual_hash="$(sha256sum "$ARCHIVE" | awk '{print $1}')"
[[ "${actual_hash,,}" == "${expected_hash,,}" ]] \
	|| fail "Downloaded archive checksum does not match the published checksum"

mkdir -p "$EXTRACTED"
unzip -q "$ARCHIVE" -d "$EXTRACTED" || fail "Unable to extract the downloaded archive"
PACKAGE_ROOT="$EXTRACTED/Spoiled Milk World Builder"
[[ -d "$PACKAGE_ROOT" ]] || fail "Downloaded archive has an unexpected directory layout"
[[ -f "$PACKAGE_ROOT/PACKAGE-MANIFEST.sha256" ]] \
	|| fail "Downloaded package manifest is missing"
[[ "$(tr -d '\r\n' < "$PACKAGE_ROOT/VERSION.txt")" == "$LATEST_VERSION" ]] \
	|| fail "Downloaded package version does not match its release tag"

if grep -Eq '(^|[[:space:]])\*?\.?/?(workspace|updates)(/|$)' \
	"$PACKAGE_ROOT/PACKAGE-MANIFEST.sha256"; then
	fail "Downloaded package manifest attempts to manage durable user state"
fi
(cd "$PACKAGE_ROOT" && sha256sum -c PACKAGE-MANIFEST.sha256 >/dev/null) \
	|| fail "Downloaded package file verification failed"

BACKUP="$STAGE/backup"
mkdir -p "$BACKUP"
for installed_path in "$ROOT_DIR"/* "$ROOT_DIR"/.[!.]*; do
	[[ -e "$installed_path" ]] || continue
	installed_name="$(basename "$installed_path")"
	case "$installed_name" in
		workspace|updates|.world-builder-update.lock|.workspace.world-builder.lock) continue ;;
	esac
	cp -a "$installed_path" "$BACKUP/" || fail "Unable to prepare the update rollback copy"
done

restore_backup() {
	cp -a "$BACKUP/." "$ROOT_DIR/" 2>/dev/null || true
}

for installed_path in "$ROOT_DIR"/* "$ROOT_DIR"/.[!.]*; do
	[[ -e "$installed_path" ]] || continue
	installed_name="$(basename "$installed_path")"
	case "$installed_name" in
		workspace|updates|.world-builder-update.lock|.workspace.world-builder.lock) continue ;;
	esac
	if ! rm -rf "$installed_path"; then
		restore_backup
		fail "Unable to clear the previous application files; they were restored"
	fi
done

if ! cp -a "$PACKAGE_ROOT/." "$ROOT_DIR/"; then
	restore_backup
	fail "Unable to install the update; the previous application files were restored"
fi
if [[ "$(tr -d '\r\n' < "$ROOT_DIR/VERSION.txt")" != "$LATEST_VERSION" ]] \
	|| ! (cd "$ROOT_DIR" && sha256sum -c PACKAGE-MANIFEST.sha256 >/dev/null); then
	restore_backup
	fail "Installed update verification failed; the previous application files were restored"
fi

printf 'World Builder updated successfully to %s.\n' "$LATEST_VERSION"
if [[ -d "$WORKSPACE" ]]; then
	printf 'Your existing workspace, exports, backups, receipts, and credentials were preserved.\n'
	printf 'The existing project remains tied to the runtime snapshot with which it was created.\n'
fi
