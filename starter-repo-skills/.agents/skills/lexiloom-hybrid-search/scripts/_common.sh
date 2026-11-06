#!/usr/bin/env bash
# Shared utilities for LexiLoom Hybrid Search Skill scripts.
# Sourced by setup.sh, search_code.sh, search_knowledge.sh, and get_document.sh.

# Locate the target repository root using a prioritized multi-pass upward search.
# Priority:
#   1. Ancestor containing .lexiloom/config.json or .lexiloom/
#   2. Ancestor containing .git (directory or worktree file)
#   3. Ancestor containing project manifest (pom.xml, package.json, pyproject.toml, go.mod, Cargo.toml)
#   4. Fallback relative to skill install path (<repo>/.<agent>/skills/<skill>/scripts)
find_project_root() {
  local script_dir="${1:-$(cd "$(dirname "${BASH_SOURCE[0]:-$0}")" && pwd)}"
  local dir="$PWD"

  # Pass 1: .lexiloom directory or config
  while :; do
    if [[ -f "$dir/.lexiloom/config.json" || -d "$dir/.lexiloom" ]]; then
      printf '%s\n' "$dir"
      return 0
    fi
    [[ "$dir" == "/" ]] && break
    dir="$(dirname "$dir")"
  done

  # Pass 2: .git root (prevents stopping early at sub-packages in monorepos/multi-module builds)
  dir="$PWD"
  while :; do
    if [[ -d "$dir/.git" || -f "$dir/.git" ]]; then
      printf '%s\n' "$dir"
      return 0
    fi
    [[ "$dir" == "/" ]] && break
    dir="$(dirname "$dir")"
  done

  # Pass 3: Language/build manifest files
  dir="$PWD"
  while :; do
    if [[ -f "$dir/pom.xml" || -f "$dir/package.json" || -f "$dir/pyproject.toml" || -f "$dir/go.mod" || -f "$dir/Cargo.toml" || -f "$dir/build.gradle" || -f "$dir/build.gradle.kts" ]]; then
      printf '%s\n' "$dir"
      return 0
    fi
    [[ "$dir" == "/" ]] && break
    dir="$(dirname "$dir")"
  done

  # Pass 4: Skill layout fallback (<repo>/.<agent>/skills/<name>/scripts)
  local candidate
  candidate="$(cd "$script_dir/../../../.." 2>/dev/null && pwd || true)"
  if [[ -n "$candidate" && "$candidate" != "/" && ( -d "$candidate/.lexiloom" || -d "$candidate/.git" || -f "$candidate/.git" || -f "$candidate/pom.xml" || -f "$candidate/package.json" || -f "$candidate/pyproject.toml" ) ]]; then
    printf '%s\n' "$candidate"
    return 0
  fi

  return 1
}

# Load LEXILOOM_API_KEY from environment (preferred) or <project_root>/.lexiloom.env.
# Supports `export KEY=VAL`, single/double quotes, and inline comments.
load_api_key() {
  local env_file="$1"
  if [[ -n "${LEXILOOM_API_KEY:-}" ]]; then
    printf '%s\n' "$LEXILOOM_API_KEY"
    return 0
  fi
  if [[ -f "$env_file" ]]; then
    python3 - "$env_file" <<'PY'
import re, sys

with open(sys.argv[1], encoding='utf-8') as f:
    for raw_line in f:
        line = raw_line.strip()
        if not line or line.startswith('#'):
            continue
        if line.startswith('export '):
            line = line[7:].strip()
        if line.startswith('LEXILOOM_API_KEY='):
            val = line.split('=', 1)[1].strip()
            m = re.match(r'^"([^"]*)"(?:\s+#.*)?$', val) or re.match(r"^'([^']*)'(?:\s+#.*)?$", val)
            if m:
                val = m.group(1)
            else:
                val = re.sub(r'\s+#.*$', '', val).strip()
            if val:
                print(val)
            break
PY
  fi
}

# Read and validate .lexiloom/config.json.
# Prints tab-separated: serverUrl \t workspaceId \t workspaceName
load_lexiloom_config() {
  local config_file="$1"
  [[ -f "$config_file" ]] || {
    echo "错误：缺少 .lexiloom/config.json。请先在目标仓库运行 scripts/setup.sh。" >&2
    return 2
  }
  python3 - "$config_file" <<'PY'
import json, sys, urllib.parse

try:
    with open(sys.argv[1], encoding='utf-8') as f:
        c = json.load(f)
    url = c.get('serverUrl')
    ws = c.get('workspace') or {}
    wid = ws.get('id')
    name = ws.get('name') or ''
    if not isinstance(url, str) or not url.strip():
        raise ValueError('serverUrl 缺失')
    url = url.strip().rstrip('/')
    u = urllib.parse.urlparse(url)
    if u.scheme not in ('http', 'https') or not u.netloc or u.username or u.password:
        raise ValueError('serverUrl 必须是有效的 http(s) URL，且不能包含用户名或密码')
    if isinstance(wid, bool) or not isinstance(wid, int) or wid <= 0:
        raise ValueError('workspace.id 必须是正整数')
    print(f"{url}\t{wid}\t{name.replace(chr(9), ' ').replace(chr(10), ' ')}")
except Exception as e:
    print('配置错误：' + str(e), file=sys.stderr)
    sys.exit(2)
PY
}

# Extract RFC 7807 ProblemDetail `detail` (or `message`) safely from an error response body.
extract_problem_detail() {
  local body_file="$1"
  [[ -f "$body_file" ]] || return 0
  python3 - "$body_file" <<'PY'
import json, re, sys

try:
    with open(sys.argv[1], encoding='utf-8') as f:
        data = json.load(f)
    if isinstance(data, dict):
        detail = data.get('detail') or data.get('message') or ''
        if isinstance(detail, str) and detail.strip():
            clean = re.sub(r'[\x00-\x1f\x7f]+', ' ', detail).strip()[:300]
            print(clean)
except Exception:
    pass
PY
}

# Perform an authenticated GET request without exposing the API key in process arguments (ps aux).
# Usage: lexiloom_curl_get <output_file> <api_key> <connect_timeout> <max_time> <url> [extra_curl_args...]
lexiloom_curl_get() {
  local output_file="$1"
  local api_key="$2"
  local connect_timeout="$3"
  local max_time="$4"
  local url="$5"
  shift 5

  curl --silent --show-error --get \
    --connect-timeout "$connect_timeout" \
    --max-time "$max_time" \
    --output "$output_file" \
    --write-out '%{http_code}' \
    --header 'Accept: application/json' \
    --config - \
    "$@" \
    "$url" <<EOF
header = "Authorization: Bearer ${api_key//\"/\\\"}"
header = "X-API-Key: ${api_key//\"/\\\"}"
EOF
}

# Perform an authenticated POST request with JSON payload without exposing the API key in process arguments.
# Usage: lexiloom_curl_post <output_file> <api_key> <connect_timeout> <max_time> <url> <json_payload> [extra_curl_args...]
lexiloom_curl_post() {
  local output_file="$1"
  local api_key="$2"
  local connect_timeout="$3"
  local max_time="$4"
  local url="$5"
  local json_payload="$6"
  shift 6

  curl --silent --show-error \
    --connect-timeout "$connect_timeout" \
    --max-time "$max_time" \
    --output "$output_file" \
    --write-out '%{http_code}' \
    --header 'Content-Type: application/json' \
    --header 'Accept: application/json' \
    --data "$json_payload" \
    --config - \
    "$@" \
    "$url" <<EOF
header = "Authorization: Bearer ${api_key//\"/\\\"}"
header = "X-API-Key: ${api_key//\"/\\\"}"
EOF
}
