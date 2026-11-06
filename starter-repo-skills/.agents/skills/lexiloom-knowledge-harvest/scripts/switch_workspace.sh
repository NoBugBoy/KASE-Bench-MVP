#!/usr/bin/env bash
# Switch LexiLoom workspace for the current repository.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
NODE_BIN="$(command -v node || true)"

if [[ -n "$NODE_BIN" && -f "$SCRIPT_DIR/manage_workspace.js" ]]; then
  if (( $# == 0 )); then
    exec "$NODE_BIN" "$SCRIPT_DIR/manage_workspace.js" list-workspaces
  elif [[ "$1" =~ ^[0-9]+$ ]]; then
    exec "$NODE_BIN" "$SCRIPT_DIR/manage_workspace.js" set-workspace --workspace-id "$1" "${@:2}"
  elif [[ "$1" == "--list" ]]; then
    exec "$NODE_BIN" "$SCRIPT_DIR/manage_workspace.js" list-workspaces "${@:2}"
  else
    exec "$NODE_BIN" "$SCRIPT_DIR/manage_workspace.js" set-workspace "$@"
  fi
fi

PYTHON_BIN="$(command -v python3 || command -v python || true)"
if [[ -n "$PYTHON_BIN" && -f "$SCRIPT_DIR/manage_workspace.py" ]]; then
  if (( $# == 0 )); then
    exec "$PYTHON_BIN" "$SCRIPT_DIR/manage_workspace.py" list-workspaces
  elif [[ "$1" =~ ^[0-9]+$ ]]; then
    exec "$PYTHON_BIN" "$SCRIPT_DIR/manage_workspace.py" set-workspace --workspace-id "$1" "${@:2}"
  elif [[ "$1" == "--list" ]]; then
    exec "$PYTHON_BIN" "$SCRIPT_DIR/manage_workspace.py" list-workspaces "${@:2}"
  else
    exec "$PYTHON_BIN" "$SCRIPT_DIR/manage_workspace.py" set-workspace "$@"
  fi
fi

echo "错误：未找到 node 或 python3 环境，请确认已安装 Node.js (>=18)。" >&2
exit 2
