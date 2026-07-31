#!/usr/bin/env bash
set -euo pipefail

SCRIPT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

if [[ $# -eq 0 || "${1:-}" == -h || "${1:-}" == --help ]]; then
  cat <<'USAGE'
Usage: ./scripts/create-ai-workspace.sh <ai-N>

Compatibility wrapper for:
  ./scripts/ai-workspace.sh create <ai-N>
USAGE
  exit 0
fi

exec "$SCRIPT_ROOT/scripts/ai-workspace.sh" create "$@"
