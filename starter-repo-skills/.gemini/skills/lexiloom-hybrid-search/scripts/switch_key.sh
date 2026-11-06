#!/usr/bin/env bash
# Switch LexiLoom AccessKey (API Key) and immediately trigger workspace selection.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
NODE_BIN="$(command -v node || true)"

if [[ -n "$NODE_BIN" && -f "$SCRIPT_DIR/manage_workspace.js" ]]; then
  if (( $# == 0 )); then
    exec "$NODE_BIN" "$SCRIPT_DIR/manage_workspace.js" switch-key
  elif [[ "$1" == "--api-key" ]]; then
    exec "$NODE_BIN" "$SCRIPT_DIR/manage_workspace.js" set-api-key "$@"
  else
    exec "$NODE_BIN" "$SCRIPT_DIR/manage_workspace.js" set-api-key --api-key "$1" "${@:2}"
  fi
fi

PYTHON_BIN="$(command -v python3 || command -v python || true)"
if [[ -n "$PYTHON_BIN" && -f "$SCRIPT_DIR/manage_workspace.py" ]]; then
  if (( $# == 0 )); then
    exec "$PYTHON_BIN" "$SCRIPT_DIR/manage_workspace.py" switch-key
  elif [[ "$1" == "--api-key" ]]; then
    exec "$PYTHON_BIN" "$SCRIPT_DIR/manage_workspace.py" set-api-key "$@"
  else
    exec "$PYTHON_BIN" "$SCRIPT_DIR/manage_workspace.py" set-api-key --api-key "$1" "${@:2}"
  fi
fi

echo "错误：未找到 node 或 python3 环境，请确认已安装 Node.js (>=18)。" >&2
exit 2
