'use strict';

/**
 * LexiLoom Skills 共享通用工具库 (Node.js 原生零依赖版 - 安全加固型)
 * 依赖 Node.js >= 18 (内置 fetch, FormData, Blob, fs, path, os)
 */

const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');

/**
 * 多轮向上查找定位目标代码仓库根目录。
 * 优先级:
 * 1. 祖先目录包含 .lexiloom/config.json 或 .lexiloom/
 * 2. 祖先目录包含 .git
 * 3. 祖先目录包含工程构建配置 (pom.xml, package.json, pyproject.toml, go.mod, Cargo.toml 等)
 * 4. 回退到当前工作目录 process.cwd()
 */
function findProjectRoot(startDir) {
  let current = path.resolve(startDir || process.cwd());

  // Pass 1: .lexiloom 目录或配置
  let p = current;
  while (true) {
    if (fs.existsSync(path.join(p, '.lexiloom', 'config.json')) || fs.existsSync(path.join(p, '.lexiloom'))) {
      return p;
    }
    const parent = path.dirname(p);
    if (parent === p) break;
    p = parent;
  }

  // Pass 2: .git 目录或 worktree 文件
  p = current;
  while (true) {
    if (fs.existsSync(path.join(p, '.git'))) {
      return p;
    }
    const parent = path.dirname(p);
    if (parent === p) break;
    p = parent;
  }

  // Pass 3: 构建描述文件
  const manifests = [
    'pom.xml',
    'package.json',
    'pyproject.toml',
    'go.mod',
    'Cargo.toml',
    'build.gradle',
    'build.gradle.kts',
  ];
  p = current;
  while (true) {
    if (manifests.some((m) => fs.existsSync(path.join(p, m)))) {
      return p;
    }
    const parent = path.dirname(p);
    if (parent === p) break;
    p = parent;
  }

  return current;
}

/**
 * 校验并规整 Server URL，防范 SSRF、非 HTTP 协议与凭证泄露
 */
function sanitizeServerUrl(rawUrl) {
  if (!rawUrl || typeof rawUrl !== 'string') {
    throw new Error('服务地址不能为空');
  }
  const trimmed = rawUrl.trim().replace(/\/+$/, '');
  let parsed;
  try {
    parsed = new URL(trimmed);
  } catch (e) {
    throw new Error(`服务地址格式无效: "${rawUrl}" (${e.message})`);
  }

  if (!['http:', 'https:'].includes(parsed.protocol)) {
    throw new Error(`服务地址协议不合法: 必须是 http: 或 https:，不支持 "${parsed.protocol}"`);
  }
  if (!parsed.hostname) {
    throw new Error('服务地址缺少有效的主机名 (hostname)');
  }
  if (parsed.username || parsed.password) {
    throw new Error('服务地址中严禁包含用户名或密码，以防凭据意外泄露');
  }
  return trimmed;
}

/**
 * 读取并校验 .lexiloom/config.json
 */
function loadConfig(projectRoot) {
  const configFile = path.join(projectRoot, '.lexiloom', 'config.json');
  if (!fs.existsSync(configFile)) {
    const err = new Error('缺少 .lexiloom/config.json。请先在目标仓库运行 scripts/setup.js。');
    err.code = 'CONFIG_NOT_FOUND';
    throw err;
  }

  let data;
  try {
    const raw = fs.readFileSync(configFile, 'utf8');
    data = JSON.parse(raw);
  } catch (e) {
    throw new Error(`配置文件格式错误 (非有效 JSON): ${configFile} (${e.message})`);
  }

  if (!data || typeof data !== 'object' || Array.isArray(data)) {
    throw new Error(`配置错误: ${configFile} 必须包含顶级 JSON 对象`);
  }

  const serverUrl = sanitizeServerUrl(data.serverUrl || '');

  const ws = data.workspace || {};
  const workspaceId = ws.id;
  const workspaceName = (ws.name || '').trim();

  if (
    workspaceId === undefined ||
    workspaceId === null ||
    typeof workspaceId === 'boolean' ||
    !Number.isInteger(Number(workspaceId)) ||
    Number(workspaceId) <= 0
  ) {
    throw new Error('配置错误: workspace.id 必须是正整数');
  }

  return {
    serverUrl,
    workspaceId: Number(workspaceId),
    workspaceName,
    search: data.search || {},
    raw: data,
  };
}

/**
 * 跨平台原子写入文件（兼容 POSIX 与 Windows 的 rename 行为）
 */
function atomicWriteFile(targetFile, content, mode = 0o644) {
  const targetDir = path.dirname(targetFile);
  if (!fs.existsSync(targetDir)) {
    fs.mkdirSync(targetDir, { recursive: true });
  }

  const tmpFile = path.join(targetDir, `.${path.basename(targetFile)}_${Date.now()}_${Math.random().toString(36).slice(2)}.tmp`);
  try {
    fs.writeFileSync(tmpFile, content, { encoding: 'utf8', mode });
    if (process.platform !== 'win32') {
      try { fs.chmodSync(tmpFile, mode); } catch {}
    }

    try {
      fs.renameSync(tmpFile, targetFile);
    } catch (renameErr) {
      // Windows 上若目标文件已存在且被某些句柄侦听，尝试先安全移除再重命名
      if (fs.existsSync(targetFile)) {
        try { fs.unlinkSync(targetFile); } catch {}
      }
      fs.renameSync(tmpFile, targetFile);
    }

    if (process.platform !== 'win32') {
      try { fs.chmodSync(targetFile, mode); } catch {}
    }
  } finally {
    if (fs.existsSync(tmpFile)) {
      try { fs.unlinkSync(tmpFile); } catch {}
    }
  }
}

/**
 * 保存配置到 .lexiloom/config.json (原子写入)
 */
function saveConfig(projectRoot, configData) {
  const configFile = path.join(projectRoot, '.lexiloom', 'config.json');
  atomicWriteFile(configFile, JSON.stringify(configData, null, 2) + '\n', 0o644);
}

/**
 * 加载 API Key (优先级: 参数 > LEXILOOM_API_KEY 环境变量 > .lexiloom.env)
 */
function loadApiKey(projectRoot, explicitKey) {
  if (explicitKey && explicitKey.trim()) {
    return explicitKey.trim();
  }

  const envKey = (process.env.LEXILOOM_API_KEY || '').trim();
  if (envKey) {
    return envKey;
  }

  const envFile = path.join(projectRoot, '.lexiloom.env');
  if (fs.existsSync(envFile)) {
    try {
      const lines = fs.readFileSync(envFile, 'utf8').split(/\r?\n/);
      for (let line of lines) {
        line = line.trim();
        if (!line || line.startsWith('#')) continue;
        if (line.startsWith('export ')) line = line.slice(7).trim();
        if (line.startsWith('LEXILOOM_API_KEY=')) {
          let val = line.slice('LEXILOOM_API_KEY='.length).trim();
          const m = val.match(/^"([^"]*)"(?:\s+#.*)?$/) || val.match(/^'([^']*)'(?:\s+#.*)?$/);
          if (m) {
            val = m[1];
          } else {
            val = val.replace(/\s+#.*$/, '').trim();
          }
          if (val) return val;
        }
      }
    } catch {}
  }

  return '';
}

/**
 * 保存 API Key 到 .lexiloom.env，带严格 umask 077 保护，权限 600 并确保写入 .gitignore
 */
function saveApiKey(projectRoot, apiKey) {
  const cleanKey = (apiKey || '').trim();
  if (!cleanKey) {
    throw new Error('API Key 不能为空');
  }

  // 确保写入 .gitignore（严格检查尾部换行符）
  const gitignoreFile = path.join(projectRoot, '.gitignore');
  try {
    if (fs.existsSync(gitignoreFile)) {
      const content = fs.readFileSync(gitignoreFile, 'utf8');
      if (!/(?:^|\n)\.lexiloom\.env(?:\s|$)/m.test(content)) {
        const prefix = content.length > 0 && !content.endsWith('\n') ? '\n' : '';
        fs.appendFileSync(gitignoreFile, `${prefix}# LexiLoom local credentials (do not commit)\n.lexiloom.env\n`, 'utf8');
      }
    } else {
      fs.writeFileSync(gitignoreFile, '# LexiLoom local credentials (do not commit)\n.lexiloom.env\n', 'utf8');
    }
  } catch (e) {
    console.error(`警告：更新 .gitignore 失败: ${e.message}`);
  }

  // 切换 umask 确保新建的临时凭据文件不向组或他人泄露任何读写权
  let oldUmask;
  if (typeof process.umask === 'function') {
    try {
      oldUmask = process.umask(0o077);
    } catch {}
  }

  try {
    const envFile = path.join(projectRoot, '.lexiloom.env');
    const content = `# Local LexiLoom credentials; do not commit.\nLEXILOOM_API_KEY=${cleanKey}\n`;
    atomicWriteFile(envFile, content, 0o600);
  } finally {
    if (oldUmask !== undefined) {
      try { process.umask(oldUmask); } catch {}
    }
  }
}

/**
 * 健壮的 API Key 脱敏展示，防止短 key 侧信道泄露
 */
function maskKey(key) {
  if (!key) return '<未设置>';
  const str = String(key).trim();
  if (str.length <= 12) {
    return '***';
  }
  return `${str.slice(0, 3)}***${str.slice(-4)}`;
}

/**
 * 清洗字符串，移除控制字符与 ANSI 转义码，防止终端注入
 */
function sanitizeOutputText(text) {
  if (!text) return '';
  return String(text)
    .replace(/\u001b\[[0-9;]*[a-zA-Z]/g, '') // 移除 ANSI 转义序列
    .replace(/[\x00-\x08\x0b-\x0c\x0e-\x1f\x7f]/g, '') // 移除不可见控制字符 (保留 \t, \n, \r)
    .trim();
}

/**
 * 安全提取 RFC 7807 ProblemDetail 错误原因，防 HTML 泄露与控制字符注入
 */
function extractProblemDetail(errBody) {
  if (!errBody) return '';
  if (typeof errBody === 'object') {
    const detail = errBody.detail || errBody.message || '';
    return sanitizeOutputText(detail).slice(0, 300);
  }

  const raw = String(errBody).trim();
  try {
    const data = JSON.parse(raw);
    const detail = data.detail || data.message || '';
    return sanitizeOutputText(detail).slice(0, 300);
  } catch {
    // 若返回为 HTML 页面（如反向代理 502/504 错误页），避免将内部 HTML 打印到终端造成信息泄露
    if (/<html|<!DOCTYPE/i.test(raw)) {
      return '服务端返回了非 JSON 错误响应 (HTML)';
    }
    return sanitizeOutputText(raw).slice(0, 300);
  }
}

/**
 * 发送带认证的 HTTP 请求 (Node.js 18+ 原生 fetch 封装，带完善超时与异常归一化处理)
 */
async function lexiloomFetch(url, options = {}) {
  const { apiKey, timeoutMs = 30000, headers = {}, ...rest } = options;
  const mergedHeaders = {
    Accept: 'application/json',
    ...headers,
  };
  if (apiKey) {
    mergedHeaders['Authorization'] = `Bearer ${apiKey}`;
    mergedHeaders['X-API-Key'] = apiKey;
  }

  try {
    return await fetch(url, {
      ...rest,
      headers: mergedHeaders,
      signal: AbortSignal.timeout(timeoutMs),
    });
  } catch (err) {
    if (err.name === 'TimeoutError' || err.code === 20 || err.name === 'AbortError') {
      const e = new Error(`请求超时 (${timeoutMs / 1000} 秒): ${url}`);
      e.code = 'TIMEOUT';
      throw e;
    }
    if (err.code === 'ECONNREFUSED') {
      const e = new Error(`连接被拒绝: 无法连接至 ${url}，请确认后端服务已启动。`);
      e.code = 'CONN_REFUSED';
      throw e;
    }
    if (err.code === 'ENOTFOUND') {
      const e = new Error(`域名无法解析: ${url}`);
      e.code = 'DNS_FAILED';
      throw e;
    }
    throw err;
  }
}

module.exports = {
  findProjectRoot,
  sanitizeServerUrl,
  loadConfig,
  saveConfig,
  loadApiKey,
  saveApiKey,
  maskKey,
  sanitizeOutputText,
  extractProblemDetail,
  lexiloomFetch,
  atomicWriteFile,
};
