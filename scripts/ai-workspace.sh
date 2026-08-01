#!/usr/bin/env bash
set -euo pipefail

SCRIPT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ROOT_DIR="${ROOT_DIR:-$SCRIPT_ROOT}"
# shellcheck source=scripts/lib/ai-workspace-common.sh
source "$SCRIPT_ROOT/scripts/lib/ai-workspace-common.sh"

usage() {
  cat <<'USAGE'
Usage:
  ./scripts/ai-workspace.sh init [count]
  ./scripts/ai-workspace.sh create <ai-N>
  ./scripts/ai-workspace.sh start <ai-N> <topic-branch>
  ./scripts/ai-workspace.sh checkpoint [-m message] [--local-only] [--allow-sensitive] [--allow-large]
  ./scripts/ai-workspace.sh handoff [-m message] [--allow-sensitive] [--allow-large]
  ./scripts/ai-workspace.sh status
  ./scripts/ai-workspace.sh recycle <ai-N> [--keep-remote]

Neutral slots are persistent folders. Each task receives a short-lived,
topic-named branch. Checkpoint and handoff preserve tracked and untracked files
in commits; they never use Git stashes.
USAGE
}

workspace_init() {
  local count="${1:-3}"
  local index slot path

  [[ "$count" =~ ^[1-9][0-9]*$ ]] || ai_fail "init count must be a positive integer."
  ai_require_manager
  for ((index = 1; index <= count; index++)); do
    slot="ai-$index"
    path="$(ai_slot_path "$slot")"
    if [[ -e "$path" ]]; then
      ai_require_slot "$slot" >/dev/null
      printf 'Keeping existing %s at %s\n' "$slot" "$path"
      continue
    fi
    ai_create_slot "$slot"
  done
}

workspace_create() {
  [[ $# -eq 1 ]] || ai_fail "create requires exactly one slot, for example ai-1."
  ai_require_manager
  ai_create_slot "$1"
}

workspace_start() {
  local slot branch path current_branch published_ref head

  [[ $# -eq 2 ]] || ai_fail "start requires a slot and topic branch."
  slot="$(ai_normalize_slot "$1")"
  branch="$2"
  git -C "$ROOT_DIR" check-ref-format --branch "$branch" >/dev/null 2>&1 \
    || ai_fail "Invalid task branch name: $branch"
  [[ "$branch" != "$AI_MAIN_BRANCH" ]] || ai_fail "A worker task cannot use $AI_MAIN_BRANCH."
  [[ "$branch" != workspace/* ]] \
    || ai_fail "Use a topic branch such as fix/name or feat/name; permanent workspace/* branches are retired."
  [[ "$branch" =~ ^(fix|feat|feature|content|balance|art|idea|docs|refactor|chore|test)/[^/].* ]] \
    || ai_fail "Use a descriptive TYPE/name branch (fix/, feat/, content/, balance/, art/, idea/, docs/, refactor/, chore/, or test/)."

  ai_require_manager
  published_ref="$(ai_published_ref)"
  path="$(ai_require_slot "$slot")"
  ai_require_no_git_operation "$path"
  ai_require_clean "$path" "Workspace $slot"
  current_branch="$(ai_current_branch "$path")"
  [[ "$current_branch" == DETACHED ]] \
    || ai_fail "Workspace $slot is still on '$current_branch'. Handoff/merge/recycle it before starting another task."
  git -C "$path" merge-base --is-ancestor HEAD "$published_ref" \
    || ai_fail "Workspace $slot has clean detached commits not contained in published main. Run ai-manager.sh rescue $slot before starting another task."
  ai_require_idle_slot_state "$slot" "$path" "$published_ref"

  if git -C "$ROOT_DIR" show-ref --verify --quiet "refs/heads/$branch"; then
    ai_fail "Local branch '$branch' already exists. Use its current worktree or choose a new focused branch name."
  fi
  if git -C "$ROOT_DIR" show-ref --verify --quiet "refs/remotes/$AI_REMOTE/$branch"; then
    ai_fail "Remote branch '$AI_REMOTE/$branch' already exists. Choose a new branch name or deliberately recover it first."
  fi
  if ai_branch_worktree "$branch" >/dev/null 2>&1; then
    ai_fail "Branch '$branch' is already checked out in another worktree."
  fi

  printf 'Starting %s in %s\n' "$branch" "$slot"
  printf '  Path: %s\n' "$path"
  printf '  Base: %s\n' "$published_ref"
  git -C "$path" switch -c "$branch" "$published_ref"
  head="$(ai_head "$path")"
  ai_write_state "$slot" ACTIVE "$path" "$branch" "$head"
  ai_generate_workspace_guide "$slot" "$path" ACTIVE "$branch" "$head"
  printf 'Open %s in the worker AI session.\n' "$path"
}

workspace_checkpoint() {
  local requested_phase="$1"
  shift
  local message=""
  local local_only=false
  local allow_sensitive=false
  local allow_large=false
  local slot default_action

  while (($#)); do
    case "$1" in
      -m|--message)
        [[ $# -ge 2 ]] || ai_fail "$1 requires a commit message."
        message="$2"
        shift 2
        ;;
      --local-only)
        local_only=true
        shift
        ;;
      --allow-sensitive)
        allow_sensitive=true
        shift
        ;;
      --allow-large)
        allow_large=true
        shift
        ;;
      -h|--help)
        usage
        exit 0
        ;;
      *)
        ai_fail "Unknown checkpoint option: $1"
        ;;
    esac
  done

  if [[ "$requested_phase" == READY && "$local_only" == true ]]; then
    ai_fail "A handoff must be pushed. Use checkpoint --local-only for a local emergency snapshot."
  fi

  slot="$(ai_current_slot || true)"
  [[ -n "$slot" ]] \
    || ai_fail "Run checkpoint/handoff from a registered neutral AI workspace, not the manager checkout."
  default_action="checkpoint"
  [[ "$requested_phase" == READY ]] && default_action="handoff"
  message="${message:-WIP($slot): $default_action $(date -u '+%Y-%m-%d %H:%M UTC')}"

  if [[ "$local_only" == true ]]; then
    ai_checkpoint_path "$ROOT_DIR" "$slot" ACTIVE "$message" false "$allow_sensitive" "$allow_large"
    ai_warn "Local-only checkpoint created. Run handoff before the session ends so the commit has a remote backup."
  else
    ai_checkpoint_path "$ROOT_DIR" "$slot" "$requested_phase" "$message" true "$allow_sensitive" "$allow_large"
  fi
}

workspace_finish_detached_recycle() {
  local slot="$1"
  local path="$2"
  local published_ref="$3"
  local requested_delete="$4"
  local phase recorded_slot recorded_path recorded_path_real recorded_branch recorded_head
  local policy remote_head="" actual_head local_head

  phase="$(ai_state_get "$slot" phase)"
  recorded_slot="$(ai_state_get "$slot" slot)"
  recorded_path="$(ai_state_get "$slot" path)"
  recorded_branch="$(ai_state_get "$slot" branch)"
  recorded_head="$(ai_state_get "$slot" head)"

  [[ "$recorded_slot" == "$slot" ]] || return 1
  recorded_path_real="$(ai_realpath "$recorded_path" 2>/dev/null || true)"
  [[ "$recorded_path_real" == "$(ai_realpath "$path")" ]] || return 1
  actual_head="$(ai_head "$path")"

  if [[ "$phase" == IDLE && "$recorded_branch" == DETACHED ]] \
      && git -C "$path" merge-base --is-ancestor "$recorded_head" "$published_ref"; then
    ai_warn "Repairing stale IDLE metadata for $slot at a safe published commit."
    ai_write_state "$slot" IDLE "$path" DETACHED "$actual_head"
    ai_generate_workspace_guide "$slot" "$path" IDLE DETACHED "$actual_head"
    printf '%s is already idle.\n' "$slot"
    return 0
  fi

  [[ "$phase" == READY || "$phase" == RECYCLING ]] || return 1
  [[ -n "$recorded_branch" && "$recorded_branch" != DETACHED && "$recorded_branch" != "$AI_MAIN_BRANCH" ]] || return 1
  git -C "$ROOT_DIR" check-ref-format --branch "$recorded_branch" >/dev/null 2>&1 || return 1
  git -C "$ROOT_DIR" rev-parse --verify --quiet "$recorded_head^{commit}" >/dev/null || return 1
  git -C "$ROOT_DIR" merge-base --is-ancestor "$recorded_head" "$published_ref" \
    || ai_fail "Recorded $phase branch '$recorded_branch' is not contained in published main. Reattach and rescue it."

  if [[ "$phase" == RECYCLING ]]; then
    policy="$(ai_state_get "$slot" remote_policy)"
  elif [[ "$requested_delete" == true ]]; then
    policy=DELETE
  else
    policy=KEEP
  fi
  [[ "$policy" == DELETE || "$policy" == KEEP ]] \
    || ai_fail "Interrupted recycle for $slot has an invalid remote policy."

  if git -C "$ROOT_DIR" show-ref --verify --quiet "refs/heads/$recorded_branch"; then
    local_head="$(git -C "$ROOT_DIR" rev-parse "refs/heads/$recorded_branch^{commit}")"
    [[ "$local_head" == "$recorded_head" ]] \
      || ai_fail "Local branch '$recorded_branch' changed after recycle began. Inspect it before recovery."
  fi

  if git -C "$ROOT_DIR" show-ref --verify --quiet "refs/remotes/$AI_REMOTE/$recorded_branch"; then
    remote_head="$(git -C "$ROOT_DIR" rev-parse "refs/remotes/$AI_REMOTE/$recorded_branch^{commit}")"
    [[ "$remote_head" == "$recorded_head" ]] \
      || ai_fail "Remote branch $AI_REMOTE/$recorded_branch changed after recycle began. Inspect it before recovery."
  fi

  if [[ "$policy" == DELETE && -n "$remote_head" ]]; then
    git -C "$ROOT_DIR" push \
      --force-with-lease="refs/heads/$recorded_branch:$remote_head" \
      "$AI_REMOTE" ":refs/heads/$recorded_branch"
  elif [[ "$policy" == KEEP && -z "$remote_head" ]]; then
    if ! git -C "$ROOT_DIR" show-ref --verify --quiet "refs/heads/$recorded_branch"; then
      git -C "$ROOT_DIR" branch "$recorded_branch" "$recorded_head"
    fi
    git -C "$ROOT_DIR" push "$AI_REMOTE" "refs/heads/$recorded_branch:refs/heads/$recorded_branch"
  fi

  if git -C "$ROOT_DIR" show-ref --verify --quiet "refs/heads/$recorded_branch"; then
    git -C "$ROOT_DIR" branch -d "$recorded_branch"
  fi
  ai_write_state "$slot" IDLE "$path" DETACHED "$actual_head"
  ai_generate_workspace_guide "$slot" "$path" IDLE DETACHED "$actual_head"
  printf 'Recovered interrupted recycle and returned %s to IDLE.\n' "$slot"
  return 0
}

workspace_recycle() {
  local slot path delete_remote=true branch head published_ref remote_head="" policy phase

  [[ $# -ge 1 ]] || ai_fail "recycle requires a slot."
  slot="$(ai_normalize_slot "$1")"
  shift
  while (($#)); do
    case "$1" in
      --keep-remote)
        delete_remote=false
        ;;
      --delete-remote)
        delete_remote=true
        ;;
      *)
        ai_fail "Unknown recycle option: $1"
        ;;
    esac
    shift
  done

  ai_require_manager
  path="$(ai_require_slot "$slot")"
  ai_require_no_git_operation "$path"
  ai_require_clean "$path" "Workspace $slot"
  branch="$(ai_current_branch "$path")"
  published_ref="$(ai_published_ref)"
  if [[ "$branch" == DETACHED ]]; then
    head="$(ai_head "$path")"
    git -C "$path" merge-base --is-ancestor "$head" "$published_ref" \
      || ai_fail "Workspace $slot has detached commits not contained in published main. Rescue them before recycling."
    if workspace_finish_detached_recycle "$slot" "$path" "$published_ref" "$delete_remote"; then
      return 0
    fi
    ai_require_idle_slot_state "$slot" "$path" "$published_ref"
    printf '%s is already idle.\n' "$slot"
    return 0
  fi
  [[ "$branch" != "$AI_MAIN_BRANCH" ]] || ai_fail "Refusing to recycle a slot on $AI_MAIN_BRANCH."
  ai_require_slot_state "$slot" "$path"
  phase="$AI_SLOT_STATE_PHASE"
  [[ "$phase" == READY || "$phase" == RECYCLING ]] \
    || ai_fail "Workspace $slot is $phase, not READY. Handoff the exact task tip before recycling."
  if [[ "$phase" == RECYCLING ]]; then
    policy="$AI_SLOT_STATE_REMOTE_POLICY"
    delete_remote=true
    [[ "$policy" == KEEP ]] && delete_remote=false
    ai_warn "Resuming interrupted recycle for $slot with recorded remote policy $policy."
  elif [[ "$delete_remote" == true ]]; then
    policy=DELETE
  else
    policy=KEEP
  fi

  head="$(ai_head "$path")"
  git -C "$ROOT_DIR" merge-base --is-ancestor "$head" "$AI_MAIN_BRANCH" \
    || ai_fail "Branch '$branch' is not merged into local $AI_MAIN_BRANCH."
  git -C "$ROOT_DIR" merge-base --is-ancestor "$head" "$published_ref" \
    || ai_fail "Branch '$branch' is not contained in published $published_ref. Push merged main before recycling."

  published_ref="$(ai_published_ref)"
  [[ "$(ai_head "$ROOT_DIR")" == "$(git -C "$ROOT_DIR" rev-parse "$published_ref^{commit}")" ]] \
    || ai_fail "Published main changed during recycle. Reconcile manager main and retry."
  if git -C "$ROOT_DIR" show-ref --verify --quiet "refs/remotes/$AI_REMOTE/$branch"; then
    remote_head="$(git -C "$ROOT_DIR" rev-parse "refs/remotes/$AI_REMOTE/$branch^{commit}")"
    [[ "$remote_head" == "$head" ]] \
      || ai_fail "Remote branch $AI_REMOTE/$branch does not match the READY task tip $head. Handoff again before recycling."
    git -C "$ROOT_DIR" merge-base --is-ancestor "$remote_head" "$published_ref" \
      || ai_fail "Remote branch $AI_REMOTE/$branch advanced to unmerged commit $remote_head. Review and rescue it instead of deleting it."
  else
    if [[ "$phase" == READY ]]; then
      ai_fail "Remote backup $AI_REMOTE/$branch is missing. Handoff the exact task tip before recycling."
    elif [[ "$policy" == KEEP ]]; then
      ai_push_branch "$path" "$branch"
      remote_head="$head"
    fi
  fi

  if [[ "$phase" == READY ]]; then
    ai_write_state "$slot" RECYCLING "$path" "$branch" "$head" "$policy"
    ai_generate_workspace_guide "$slot" "$path" RECYCLING "$branch" "$head"
  fi

  if [[ "$delete_remote" == true && -n "$remote_head" ]]; then
    git -C "$ROOT_DIR" push \
      --force-with-lease="refs/heads/$branch:$remote_head" \
      "$AI_REMOTE" ":refs/heads/$branch"
  elif [[ "$delete_remote" == false ]]; then
    printf 'Keeping remote task branch %s/%s by request.\n' "$AI_REMOTE" "$branch"
  fi
  git -C "$path" switch --detach "$published_ref"
  git -C "$ROOT_DIR" branch -d "$branch"

  head="$(ai_head "$path")"
  ai_write_state "$slot" IDLE "$path" DETACHED "$head"
  ai_generate_workspace_guide "$slot" "$path" IDLE DETACHED "$head"
  printf 'Recycled %s. The folder remains available for the next task.\n' "$slot"
}

command="${1:-}"
if [[ -z "$command" || "$command" == -h || "$command" == --help ]]; then
  usage
  exit 0
fi
shift

case "$command" in
  init)
    workspace_init "$@"
    ;;
  create)
    workspace_create "$@"
    ;;
  start)
    workspace_start "$@"
    ;;
  checkpoint)
    workspace_checkpoint ACTIVE "$@"
    ;;
  handoff)
    workspace_checkpoint READY "$@"
    ;;
  status)
    [[ $# -eq 0 ]] || ai_fail "status takes no arguments."
    ai_status
    ;;
  recycle)
    workspace_recycle "$@"
    ;;
  *)
    ai_fail "Unknown command '$command'. Run --help for usage."
    ;;
esac
