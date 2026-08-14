#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
RUNTIME_MANAGER_ROOT="${RSC_WORLD_EDITOR_RUNTIME_ROOT:-$(dirname "$ROOT_DIR")/rsc-world-editor-runtime}"
EDITOR_REMOTE="https://github.com/An-actual-duck/rsc-world-editor.git"
RUNTIME_REMOTE="https://github.com/An-actual-duck/rsc-world-editor-runtime.git"

fail() {
	printf 'FAIL: %s\n' "$*" >&2
	exit 1
}

usage() {
	cat <<'EOF'
Usage:
  ./scripts/product-manager.sh status
  ./scripts/product-manager.sh adopt-runtime [EXPECTED_40_CHARACTER_SHA]

status reports the independent Editor and runtime manager/workers without
inspecting Core-Framework. adopt-runtime selects the clean published runtime
manager main commit (optionally requiring an exact expected SHA), advances the
Editor lock/protocol, materializes and verifies the dependency, runs the full
Editor suite, commits the bounded integration, and publishes Editor main.
EOF
}

require_manager_checkout() {
	local root="$1" label="$2" expected_remote="$3"
	local branch head published remote

	[[ -d "$root/.git" ]] || fail "$label manager checkout is missing: $root"
	[[ -z "$(git -C "$root" status --porcelain --untracked-files=all)" ]] \
		|| fail "$label manager checkout is dirty: $root"
	branch="$(git -C "$root" branch --show-current)"
	[[ "$branch" == main ]] || fail "$label manager must be on main; found ${branch:-detached}"
	remote="$(git -C "$root" remote get-url origin)"
	[[ "$remote" == "$expected_remote" ]] \
		|| fail "$label origin is not the independent expected repository: $remote"
	git -C "$root" fetch --quiet origin main
	head="$(git -C "$root" rev-parse 'HEAD^{commit}')"
	published="$(git -C "$root" rev-parse 'refs/remotes/origin/main^{commit}')"
	[[ "$head" == "$published" ]] \
		|| fail "$label manager main is not the exact published origin/main ($published); found $head"
}

product_status() {
	printf 'World Editor product manager\n'
	(
		cd "$ROOT_DIR"
		./scripts/ai-manager.sh status
	)
	printf '\nIndependent runtime provider manager\n'
	[[ -d "$RUNTIME_MANAGER_ROOT/.git" ]] \
		|| fail "Runtime manager checkout is missing: $RUNTIME_MANAGER_ROOT"
	(
		cd "$RUNTIME_MANAGER_ROOT"
		./scripts/ai-manager.sh status
	)
}

adopt_runtime() {
	local expected="${1:-}" runtime_commit changed untracked unexpected

	[[ $# -le 1 ]] || fail "adopt-runtime accepts at most one exact commit"
	if [[ -n "$expected" ]]; then
		expected="${expected,,}"
		[[ "$expected" =~ ^[0-9a-f]{40}$ ]] \
			|| fail "Expected runtime commit must be a full lowercase 40-character SHA"
	fi
	[[ "$(pwd -P)" == "$ROOT_DIR" ]] \
		|| fail "Run adopt-runtime from the World Editor manager checkout: $ROOT_DIR"
	[[ ! -e "$ROOT_DIR/release/world-builder-v2/RELEASE-READY" \
		&& ! -L "$ROOT_DIR/release/world-builder-v2/RELEASE-READY" ]] \
		|| fail "Close or consume the current release gate before advancing the runtime"

	require_manager_checkout "$ROOT_DIR" "World Editor" "$EDITOR_REMOTE"
	require_manager_checkout "$RUNTIME_MANAGER_ROOT" "Runtime" "$RUNTIME_REMOTE"
	runtime_commit="$(git -C "$RUNTIME_MANAGER_ROOT" rev-parse 'HEAD^{commit}')"
	if [[ -n "$expected" && "$runtime_commit" != "$expected" ]]; then
		fail "Published runtime main is $runtime_commit, not expected $expected"
	fi

	./scripts/sync-from-runtime-provider.sh \
		"$RUNTIME_MANAGER_ROOT" refs/heads/main
	./scripts/checkout-runtime-provider.sh
	./scripts/check-runtime-provider-parity.sh .runtime-provider
	git diff --check

	changed="$(git diff --name-only | LC_ALL=C sort)"
	untracked="$(git ls-files --others --exclude-standard)"
	unexpected="$(printf '%s\n' "$changed" \
		| sed '/^$/d;\|^release/world-builder-v2/world-builder-runtime.conf$|d;\|^runtime-provider.lock$|d')"
	[[ -z "$untracked" ]] || fail "Runtime adoption created unexpected untracked files:\n$untracked"
	[[ -z "$unexpected" ]] || fail "Runtime adoption changed files outside the bounded lock/protocol pair:\n$unexpected"

	if [[ -z "$changed" ]]; then
		printf 'World Editor already locks published runtime %s; no integration commit is needed.\n' \
			"$runtime_commit"
		return 0
	fi

	./scripts/test.sh
	git diff --check
	[[ "$(git -C "$RUNTIME_MANAGER_ROOT" rev-parse 'HEAD^{commit}')" == "$runtime_commit" ]] \
		|| fail "Runtime manager HEAD changed during Editor verification"
	[[ "$(git ls-remote "$RUNTIME_REMOTE" refs/heads/main | awk 'NR == 1 {print $1}')" == "$runtime_commit" ]] \
		|| fail "Published runtime main changed during Editor verification"

	git add runtime-provider.lock release/world-builder-v2/world-builder-runtime.conf
	git commit -m "Advance runtime provider to ${runtime_commit:0:12}"
	git push origin main
	printf 'Published World Editor runtime integration %s -> %s\n' \
		"$runtime_commit" "$(git rev-parse HEAD)"
}

command="${1:-}"
case "$command" in
	status)
		[[ $# -eq 1 ]] || fail "status takes no arguments"
		product_status
		;;
	adopt-runtime)
		shift
		adopt_runtime "$@"
		;;
	-h|--help|"")
		usage
		;;
	*)
		usage >&2
		fail "Unknown command: $command"
		;;
esac
