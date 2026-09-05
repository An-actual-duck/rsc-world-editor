#!/usr/bin/env bash
set -euo pipefail

# Repository checks must remain headless even when a graphical desktop is
# available. Individual launcher tests can still exercise their direct fallback
# paths without opening a terminal window on the developer's desktop.
export WORLD_BUILDER_NO_TERMINAL=1

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
verbose=false
list_only=false
selection_requested=false
declare -a requested_groups=()
declare -a requested_files=()
declare -a requested_tests=()
declare -a test_entries=()
declare -A selected_entries=()

usage() {
	cat <<'USAGE'
Usage:
  ./scripts/test.sh
  ./scripts/test.sh --group <name> [--group <name> ...] [--verbose]
  ./scripts/test.sh --file <test-world-builder-*.py> [--file <file> ...] [--verbose]
  ./scripts/test.sh --test <file.py::TestClass.test_method> [--test <selector> ...] [--verbose]
  ./scripts/test.sh --list

No selectors runs the complete repository gate. Successful tests are concise by
default; --verbose prints their complete unittest output. Available groups:
  workflow, discovery, projects, transactions, packaging, updater, candidate, all
USAGE
}

while (($#)); do
	case "$1" in
		--group)
			[[ $# -ge 2 ]] || { printf 'FAIL: --group requires a name.\n' >&2; exit 2; }
			requested_groups+=("$2")
			selection_requested=true
			shift 2
			;;
		--file)
			[[ $# -ge 2 ]] || { printf 'FAIL: --file requires a test file.\n' >&2; exit 2; }
			requested_files+=("$2")
			selection_requested=true
			shift 2
			;;
		--test)
			[[ $# -ge 2 ]] || { printf 'FAIL: --test requires file.py::Class.test_method.\n' >&2; exit 2; }
			requested_tests+=("$2")
			selection_requested=true
			shift 2
			;;
		--verbose|-v)
			verbose=true
			shift
			;;
		--list)
			list_only=true
			shift
			;;
		-h|--help)
			usage
			exit 0
			;;
		*)
			printf 'FAIL: Unknown test option: %s\n' "$1" >&2
			usage >&2
			exit 2
			;;
	esac
done

normalize_test_file() {
	local requested="$1" relative directory base
	if [[ "$requested" == */* ]]; then
		relative="${requested#./}"
	else
		relative="tests/myworld/$requested"
	fi
	directory="$(dirname "$relative")"
	base="$(basename "$relative")"
	case "$directory:$base" in
		tests/myworld:test-world-builder-*.py) ;;
		*)
			printf 'FAIL: Test selection must name tests/myworld/test-world-builder-*.py: %s\n' "$requested" >&2
			return 2
			;;
	esac
	[[ -f "$ROOT_DIR/$relative" ]] || {
		printf 'FAIL: Test file does not exist: %s\n' "$relative" >&2
		return 2
	}
	printf '%s\n' "$relative"
}

add_entry() {
	local relative selector="${2:-}" key
	relative="$(normalize_test_file "$1")" || exit 2
	key="$relative::$selector"
	[[ -z "${selected_entries[$key]:-}" ]] || return 0
	selected_entries["$key"]=1
	test_entries+=("$key")
}

add_group() {
	local group="$1" relative
	local -a members=()
	case "$group" in
		workflow)
			members=(test-world-builder-ai-workspaces.py test-world-builder-maintainability-tooling.py)
			;;
			discovery)
			members=(
				test-world-builder-adaptive-contracts.py
				test-world-builder-adaptive-discovery.py
				test-world-builder-current-runtime-foundation.py
				test-world-builder-preservation-source-closure.py
				test-world-builder-discovery.py
				test-world-builder-map-migration-choice.py
				test-world-builder-packed-conversion.py
				test-world-builder-portable-provider.py
				test-world-builder-npc-definition-provider.py
				test-world-builder-project-content-bundle.py
			)
			;;
		projects)
			members=(
				test-world-builder-adaptive-project-lifecycle.py
				test-world-builder-runtime-preparation.py
				test-world-builder-supervision.py
				test-world-builder-project-revisions.py
				test-world-builder-wide-elevation-v2.py
			)
			;;
		transactions)
			members=(
				test-world-builder-adaptive-transactions.py
				test-world-builder-current-runtime-upgrade-transaction.py
				test-world-builder-export.py
				test-world-builder-import.py
				test-world-builder-map-migration-choice.py
			)
			;;
		packaging)
			members=(
				test-world-builder-product-generations.py
				test-world-builder-project-independence.py
				test-world-builder-v2-candidate-validation.py
				test-world-builder-v2-release-gate.py
				test-world-builder-v2-release.py
			)
			;;
		updater)
			members=(test-world-builder-updater.py test-world-builder-v2-updater.py)
			;;
		candidate)
			members=(
				test-world-builder-v2-candidate-validation.py
				test-world-builder-native-runtime-integration.py
				test-world-builder-adaptive-contracts.py
				test-world-builder-adaptive-discovery.py
				test-world-builder-current-runtime-foundation.py
				test-world-builder-current-runtime-upgrade-transaction.py
				test-world-builder-packed-conversion.py
				test-world-builder-runtime-preparation.py
				test-world-builder-adaptive-project-lifecycle.py
				test-world-builder-supervision.py
				test-world-builder-adaptive-transactions.py
				test-world-builder-v2-release.py
				test-world-builder-ai-workspaces.py
				test-world-builder-maintainability-tooling.py
				test-world-builder-v2-updater.py
				test-world-builder-product-generations.py
				test-world-builder-project-independence.py
			)
			;;
		all)
			while IFS= read -r relative; do
				members+=("${relative#"$ROOT_DIR/"}")
			done < <(find "$ROOT_DIR/tests/myworld" -maxdepth 1 -type f \
				-name 'test-world-builder-*.py' | sort)
			;;
		*)
			printf 'FAIL: Unknown test group: %s\n' "$group" >&2
			return 2
			;;
	esac
	for relative in "${members[@]}"; do
		add_entry "$relative"
	done
}

if [[ "$list_only" == true ]]; then
	printf 'Groups: workflow discovery projects transactions packaging updater candidate all\n'
	printf 'Test files:\n'
	find "$ROOT_DIR/tests/myworld" -maxdepth 1 -type f \
		-name 'test-world-builder-*.py' -printf '  %f\n' | sort
	exit 0
fi

if [[ "$selection_requested" == false ]]; then
	requested_groups=(all)
fi
for group in "${requested_groups[@]}"; do
	add_group "$group"
done
for relative in "${requested_files[@]}"; do
	add_entry "$relative"
done
for specification in "${requested_tests[@]}"; do
	[[ "$specification" == *::* ]] || {
		printf 'FAIL: Exact tests use file.py::TestClass.test_method: %s\n' "$specification" >&2
		exit 2
	}
	relative="${specification%%::*}"
	selector="${specification#*::}"
	[[ -n "$selector" && "$selector" != "$specification" ]] || {
		printf 'FAIL: Exact test selector is empty: %s\n' "$specification" >&2
		exit 2
	}
	add_entry "$relative" "$selector"
done

((${#test_entries[@]} > 0)) || {
	printf 'FAIL: No World Builder tests were selected.\n' >&2
	exit 1
}

"$ROOT_DIR/scripts/build-tools.sh"

for script in \
	"$ROOT_DIR"/release/world-builder/*.sh \
	"$ROOT_DIR"/release/world-builder-v2/*.sh \
	"$ROOT_DIR"/release/updater/*.sh \
	"$ROOT_DIR"/release/updater-v2/*.sh \
	"$ROOT_DIR"/scripts/*.sh; do
	bash -n "$script"
done

log_root="$(mktemp -d "${TMPDIR:-/tmp}/world-builder-tests.XXXXXX")"
trap 'rm -rf -- "$log_root"' EXIT
suite_started=$SECONDS
passed=0

for entry in "${test_entries[@]}"; do
	relative="${entry%%::*}"
	selector="${entry#*::}"
	log="$log_root/$(printf '%04d' "$passed").log"
	started=$SECONDS
	command=(python3 "$ROOT_DIR/$relative")
	[[ -z "$selector" ]] || command+=("$selector")
	[[ "$verbose" == false ]] || command+=(-v)
	if "${command[@]}" </dev/null >"$log" 2>&1; then
		duration=$((SECONDS - started))
		ran="$(sed -n 's/^Ran \([0-9][0-9]*\) tests\{0,1\}.*$/\1/p' "$log" | tail -n 1)"
		result="$(sed -n '/^OK/ {p;q;}' "$log")"
		# A small number of repository checks are direct assertion scripts rather
		# than unittest modules. Their zero exit status remains authoritative.
		[[ -n "$ran" ]] || ran=check
		if [[ "$verbose" == true ]]; then
			cat "$log"
		fi
		printf 'PASS %-66s tests=%-3s time=%ss%s\n' \
			"${relative#tests/myworld/}${selector:+::$selector}" "$ran" "$duration" \
			"${result#OK}"
		passed=$((passed + 1))
	else
		duration=$((SECONDS - started))
		printf 'FAIL: %s%s (%ss)\n' "$relative" "${selector:+::$selector}" "$duration" >&2
		cat "$log" >&2
		exit 1
	fi
done

printf 'PASS: RSC World Editor checks (%s selection(s), %ss)\n' \
	"$passed" "$((SECONDS - suite_started))"
