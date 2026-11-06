#!/usr/bin/env node
'use strict';

/**
 * LexiLoom Workspace Setup Wizard (Node.js 原生零依赖版)
 * 引导配置代码仓库与 LexiLoom 知识库工作空间的绑定与鉴权凭证。
 */

const fs = require('node:fs');
const path = require('node:path');
const readline = require('node:readline/promises');
const { stdin: input, stdout: output } = require('node:process');
const {
  findProjectRoot,
  loadConfig,
  saveConfig,
  loadApiKey,
  saveApiKey,
  extractProblemDetail,
  lexiloomFetch,
} = require('./_common.js');

function printUsage() {
  console.log(`用法:
  node setup.js [选项]

选项:
  --url <serverUrl>       LexiLoom 服务端地址 (默认: http://localhost:8080 或已有配置)
  --workspace-id <id>     要绑定的目标工作空间 ID
  --api-key <key>         LexiLoom API Key (若未指定且无环境变量，将安全提示输入)
  -h, --help              显示帮助信息`);
}

async function main() {
  const args = process.argv.slice(2);
  let optUrl = '';
  let optWorkspaceId = '';
  let optApiKey = '';

  for (let i = 0; i < args.length; i++) {
    const arg = args[i];
    if (arg === '-h' || arg === '--help') {
      printUsage();
      process.exit(0);
    } else if (arg === '--url') {
      optUrl = args[++i] || '';
    } else if (arg === '--workspace-id') {
      optWorkspaceId = args[++i] || '';
    } else if (arg === '--api-key') {
      optApiKey = args[++i] || '';
    } else {
      console.error(`错误：未知参数 ${arg}`);
      printUsage();
      process.exit(2);
    }
  }

  const projectRoot = findProjectRoot();
  const configDir = path.join(projectRoot, '.lexiloom');
  const configFile = path.join(configDir, 'config.json');

  let existingUrl = '';
  let existingConfig = {};
  if (fs.existsSync(configFile)) {
    try {
      existingConfig = JSON.parse(fs.readFileSync(configFile, 'utf8'));
      existingUrl = existingConfig.serverUrl || '';
    } catch {}
  }

  const defaultUrl = existingUrl || 'http://localhost:8080';
  let serverUrl = optUrl;

  const rl = readline.createInterface({ input, output });

  try {
    if (!serverUrl) {
      if (!input.isTTY) {
        serverUrl = defaultUrl;
      } else {
        const answer = await rl.question(`LexiLoom 服务端地址 [${defaultUrl}]: `);
        serverUrl = answer.trim() || defaultUrl;
      }
    }
    serverUrl = serverUrl.trim().replace(/\/+$/, '');

    try {
      const u = new URL(serverUrl);
      if (!['http:', 'https:'].includes(u.protocol) || !u.host || u.username || u.password) {
        throw new Error('地址必须为有效 http(s) URL，且不能包含用户名或密码');
      }
    } catch (e) {
      console.error(`错误：服务地址格式无效: ${serverUrl} (${e.message})`);
      process.exit(2);
    }

    let apiKey = optApiKey || loadApiKey(projectRoot);
    if (!apiKey) {
      if (!input.isTTY) {
        console.error('错误：非交互终端下未检测到 API Key。请通过 --api-key 或 LEXILOOM_API_KEY 环境变量提供。');
        process.exit(2);
      }
      apiKey = (await rl.question('请输入 LexiLoom API Key: ')).trim();
    }
    if (!apiKey) {
      console.error('错误：API Key 不能为空。');
      process.exit(2);
    }

    // 请求服务端工作空间列表
    const wsUrl = `${serverUrl}/api/v1/openapi/workspaces`;
    let resp;
    try {
      resp = await lexiloomFetch(wsUrl, {
        method: 'GET',
        apiKey,
        timeoutMs: 20000,
      });
    } catch (err) {
      console.error(`连接失败或超时 (${err.message})。请检查服务地址和网络；未写入配置。`);
      process.exit(1);
    }

    if (!resp.ok) {
      let errBody = '';
      try {
        errBody = await resp.json();
      } catch {
        try { errBody = await resp.text(); } catch {}
      }
      const detail = extractProblemDetail(errBody);

      if (resp.status === 401 || resp.status === 403) {
        console.error(`鉴权失败（HTTP ${resp.status}）${detail ? `：${detail}` : '。请检查 API Key 及其 kb:read 权限'}；未写入配置。`);
      } else if (resp.status === 404) {
        console.error(`工作空间发现接口不存在（HTTP 404）。请确认服务端已部署 /api/v1/openapi/workspaces；未写入配置。`);
      } else {
        console.error(`读取工作空间失败（HTTP ${resp.status}）。未写入配置。`);
      }
      process.exit(1);
    }

    let data;
    try {
      data = await resp.json();
    } catch {
      console.error('错误：服务端返回的工作空间数据不是有效 JSON。未写入配置。');
      process.exit(1);
    }

    const workspaces = data && typeof data === 'object' && Array.isArray(data.data) ? data.data : data;
    if (!Array.isArray(workspaces) || workspaces.length === 0) {
      console.error('错误：当前凭证没有可用工作空间或响应格式异常。未写入配置。');
      process.exit(2);
    }

    console.log('\n可用工作空间列表：');
    for (const w of workspaces) {
      const desc = w.description ? ` — ${w.description}` : '';
      const stats = [];
      if (w.totalDocuments !== undefined && w.totalDocuments !== null) stats.push(`${w.totalDocuments} 篇文档`);
      if (w.totalChunks !== undefined && w.totalChunks !== null) stats.push(`${w.totalChunks} 个切片`);
      const statsStr = stats.length > 0 ? ` (${stats.join(', ')})` : '';
      console.log(`  [${w.id}] ${w.name}${desc}${statsStr}`);
    }

    let selectedWorkspaceId = optWorkspaceId;
    if (!selectedWorkspaceId) {
      if (!input.isTTY) {
        console.error('错误：非交互终端下未指定 --workspace-id。');
        process.exit(2);
      }
      selectedWorkspaceId = (await rl.question('\n请输入要绑定的工作空间 ID（必须来自上方列表）: ')).trim();
    }

    const matched = workspaces.find((w) => String(w.id) === String(selectedWorkspaceId));
    if (!matched) {
      console.error(`错误：选择的 ID (${selectedWorkspaceId}) 不在服务端返回的工作空间列表中。未写入配置。`);
      process.exit(2);
    }

    const wid = Number(matched.id);
    const wname = matched.name || '';

    // 保存配置
    const searchCfg = existingConfig.search && typeof existingConfig.search === 'object' ? existingConfig.search : {};
    if (!searchCfg.excludePatterns) {
      searchCfg.excludePatterns = [
        '**/.git/**',
        '**/target/**',
        '**/build/**',
        '**/dist/**',
        '**/node_modules/**',
        '**/.idea/**',
        '**/.venv/**',
        '**/vendor/**',
      ];
    }
    if (searchCfg.useWorkspaceDefaults === undefined) {
      searchCfg.useWorkspaceDefaults = true;
    }

    const newConfig = {
      version: '1.0.0',
      serverUrl,
      workspace: { id: wid, name: wname },
      search: searchCfg,
    };

    saveConfig(projectRoot, newConfig);
    saveApiKey(projectRoot, apiKey);

    console.log('\n初始化完成。');
    console.log(`项目根目录：${projectRoot}`);
    console.log('项目配置：.lexiloom/config.json（可提交；请确认其中不包含秘密）');
    console.log('本地凭证：.lexiloom.env（权限已设为 600，并加入 .gitignore）');
    console.log(`绑定工作空间：${wname}（ID: ${wid}）`);
  } finally {
    rl.close();
  }
}

main().catch((err) => {
  console.error(`初始化异常: ${err.message}`);
  process.exit(1);
});
