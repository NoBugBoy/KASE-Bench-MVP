#!/usr/bin/env node
'use strict';

/**
 * LexiLoom Workspace & AccessKey Manager (Node.js 原生零依赖版 - 安全加固型)
 * 用于管理和切换 LexiLoom 知识库绑定的工作空间与访问凭证。
 */

const readline = require('node:readline/promises');
const { stdin: input, stdout: output } = require('node:process');
const {
  findProjectRoot,
  sanitizeServerUrl,
  loadConfig,
  saveConfig,
  loadApiKey,
  saveApiKey,
  maskKey,
  extractProblemDetail,
  lexiloomFetch,
} = require('./_common.js');

async function requestWorkspaces(serverUrl, apiKey, timeoutMs = 15000) {
  if (!apiKey) {
    throw new Error('缺少 API Key，无法访问工作空间列表。请通过 --api-key 提供或先配置 API Key。');
  }

  const cleanUrl = sanitizeServerUrl(serverUrl);
  const targetUrl = `${cleanUrl}/api/v1/openapi/workspaces`;

  const resp = await lexiloomFetch(targetUrl, {
    method: 'GET',
    apiKey,
    timeoutMs,
  });

  if (!resp.ok) {
    let errBody = '';
    try {
      errBody = await resp.json();
    } catch {
      try { errBody = await resp.text(); } catch {}
    }
    const detail = extractProblemDetail(errBody);

    if (resp.status === 401 || resp.status === 403) {
      const msg = `鉴权失败 (HTTP ${resp.status})${detail ? `: ${detail}` : '，请确认 API Key 是否有效且拥有 kb:read 权限。'}`;
      const err = new Error(msg);
      err.status = resp.status;
      throw err;
    }
    if (resp.status === 404) {
      throw new Error(`接口不存在 (HTTP 404): ${targetUrl}，请确认服务端已部署 OpenAPI 工作空间端点。`);
    }
    throw new Error(`请求失败 (HTTP ${resp.status}): ${detail || resp.statusText}`);
  }

  const data = await resp.json();
  const workspaces = data && typeof data === 'object' && Array.isArray(data.data) ? data.data : data;
  if (!Array.isArray(workspaces)) {
    throw new Error('服务端返回的数据格式异常，未包含工作空间列表');
  }
  return workspaces;
}

function buildWorkspacesResult(projectRoot, serverUrl, apiKey, workspaces) {
  let config = {};
  try {
    config = loadConfig(projectRoot);
  } catch {}

  const currentWs = config.workspaceId ? { id: config.workspaceId, name: config.workspaceName } : null;
  const currentId = currentWs ? currentWs.id : null;

  const items = workspaces.map((w) => {
    const wid = w.id;
    const isCurrent = currentId !== null && String(wid) === String(currentId);
    return {
      id: wid,
      name: (w.name || '').trim(),
      description: (w.description || '').trim(),
      totalDocuments: w.totalDocuments !== undefined && w.totalDocuments !== null ? Number(w.totalDocuments) : null,
      totalChunks: w.totalChunks !== undefined && w.totalChunks !== null ? Number(w.totalChunks) : null,
      is_current: isCurrent,
    };
  });

  return {
    serverUrl,
    current_workspace: currentWs,
    api_key_masked: maskKey(apiKey),
    total_workspaces: items.length,
    workspaces: items,
  };
}

async function cmdListWorkspaces(opts, projectRoot) {
  let existingConfig = {};
  try {
    existingConfig = loadConfig(projectRoot);
  } catch {}

  const serverUrl = opts.url || existingConfig.serverUrl || 'http://localhost:8080';
  const apiKey = loadApiKey(projectRoot, opts.apiKey);

  try {
    const workspaces = await requestWorkspaces(serverUrl, apiKey);
    const result = buildWorkspacesResult(projectRoot, serverUrl, apiKey, workspaces);

    if (opts.json) {
      console.log(JSON.stringify({ success: true, data: result }, null, 2));
      return 0;
    }

    console.log(`LexiLoom 服务端: ${result.serverUrl}`);
    console.log(`当前凭证 (脱敏): ${result.api_key_masked}`);
    const cur = result.current_workspace;
    if (cur && cur.id) {
      console.log(`当前绑定空间: [${cur.id}] ${cur.name || '未命名'}`);
    } else {
      console.log('当前绑定空间: <未绑定>');
    }

    console.log(`\n当前 AccessKey 可见工作空间 (共 ${result.total_workspaces} 个):`);
    if (result.workspaces.length === 0) {
      console.log('  (当前 Key 无可用工作空间，请联系管理员分配权限)');
    } else {
      for (const w of result.workspaces) {
        const tag = w.is_current ? ' [当前绑定] *' : '';
        const stats = [];
        if (w.totalDocuments !== null) stats.push(`${w.totalDocuments} 篇文档`);
        if (w.totalChunks !== null) stats.push(`${w.totalChunks} 个切片`);
        const statsStr = stats.length > 0 ? ` (${stats.join(', ')})` : '';
        const desc = w.description ? ` — ${w.description}` : '';
        console.log(`  • [${w.id}] ${w.name}${tag}${desc}${statsStr}`);
      }
    }
    return 0;
  } catch (err) {
    if (opts.json) {
      console.log(JSON.stringify({ success: false, error: err.message }, null, 2));
    } else {
      console.error(`错误: ${err.message}`);
    }
    return 1;
  }
}

async function cmdSetWorkspace(opts, projectRoot) {
  if (opts.workspaceId === undefined || opts.workspaceId === null) {
    console.error('错误: 必须指定 --workspace-id 参数。');
    return 2;
  }

  const targetId = Number(opts.workspaceId);
  if (!Number.isInteger(targetId) || targetId <= 0) {
    console.error('错误: --workspace-id 必须是有效的正整数。');
    return 2;
  }

  let existingConfig = {};
  try {
    existingConfig = loadConfig(projectRoot);
  } catch {}

  const serverUrl = opts.url || existingConfig.serverUrl || 'http://localhost:8080';
  const apiKey = loadApiKey(projectRoot, opts.apiKey);

  try {
    const workspaces = await requestWorkspaces(serverUrl, apiKey);
    const matched = workspaces.find((w) => String(w.id) === String(targetId));

    if (!matched) {
      const availIds = workspaces.map((w) => String(w.id)).join(', ');
      const errMsg = `工作空间 ID [${targetId}] 无效或当前 Key 无权访问。可选 ID 列表: ${availIds}`;
      if (opts.json) {
        console.log(JSON.stringify({ success: false, error: errMsg }, null, 2));
      } else {
        console.error(`错误: ${errMsg}`);
      }
      return 2;
    }

    const wid = Number(matched.id);
    const wname = (matched.name || '').trim();

    const configToSave = existingConfig.raw || {
      version: '1.0.0',
      serverUrl: sanitizeServerUrl(serverUrl),
      workspace: {},
    };
    configToSave.serverUrl = sanitizeServerUrl(serverUrl);
    configToSave.workspace = { id: wid, name: wname };

    saveConfig(projectRoot, configToSave);

    const resultData = {
      workspace_id: wid,
      workspace_name: wname,
      description: (matched.description || '').trim(),
      serverUrl: configToSave.serverUrl,
      config_file: `${projectRoot}/.lexiloom/config.json`,
    };

    if (opts.json) {
      console.log(
        JSON.stringify(
          {
            success: true,
            message: `成功切换到工作空间 [${wid}] ${wname}`,
            data: resultData,
          },
          null,
          2
        )
      );
    } else {
      console.log(`✔ 成功切换到工作空间: [${wid}] ${wname}`);
      console.log(`配置文件已更新: ${resultData.config_file}`);
      console.log('当前 Agent 对话及后续检索/入库已即刻生效！');
    }
    return 0;
  } catch (err) {
    if (opts.json) {
      console.log(JSON.stringify({ success: false, error: err.message }, null, 2));
    } else {
      console.error(`错误: ${err.message}`);
    }
    return 1;
  }
}

async function cmdSetApiKey(opts, projectRoot) {
  const newKey = (opts.apiKey || '').trim();
  if (!newKey) {
    console.error('错误: 必须指定 --api-key 参数以提供新的凭证。');
    return 2;
  }

  let existingConfig = {};
  try {
    existingConfig = loadConfig(projectRoot);
  } catch {}

  const serverUrl = opts.url || existingConfig.serverUrl || 'http://localhost:8080';

  try {
    // 1. 优先使用新 Key 请求服务端校验凭证有效性
    const workspaces = await requestWorkspaces(serverUrl, newKey);

    // 2. 校验通过，写入 .lexiloom.env (带 umask 077 保护)
    saveApiKey(projectRoot, newKey);

    // 3. 组织空间列表数据供立刻选择
    const wsResult = buildWorkspacesResult(projectRoot, serverUrl, newKey, workspaces);
    const currentWs = wsResult.current_workspace;
    const isCurrentValidUnderNewKey = wsResult.workspaces.some((w) => w.is_current);

    const resultData = {
      api_key_masked: maskKey(newKey),
      env_file: `${projectRoot}/.lexiloom.env`,
      current_workspace: currentWs,
      is_current_workspace_valid: isCurrentValidUnderNewKey,
      requires_workspace_selection: true,
      total_workspaces: wsResult.total_workspaces,
      workspaces: wsResult.workspaces,
    };

    if (opts.json) {
      console.log(
        JSON.stringify(
          {
            success: true,
            message: 'AccessKey 切换成功并已通过验证，必须立刻为新凭证选择对应的工作空间',
            data: resultData,
          },
          null,
          2
        )
      );
    } else {
      console.log('✔ AccessKey 验证通过并已成功切换！');
      console.log(`凭证文件: ${resultData.env_file} (权限 600)`);
      console.log(`当前凭证 (脱敏): ${resultData.api_key_masked}`);
      console.log('\n' + '='.repeat(50));
      console.log('👉 【固定流程】请立刻为新凭证选择要绑定的工作空间：');
      console.log('='.repeat(50));
      if (wsResult.workspaces.length === 0) {
        console.log('⚠️ 警告: 该新 AccessKey 下暂无任何可见工作空间，请联系管理员分配空间权限。');
      } else {
        for (const w of wsResult.workspaces) {
          const tag = w.is_current ? ' [此前绑定]' : '';
          const stats = [];
          if (w.totalDocuments !== null) stats.push(`${w.totalDocuments} 篇文档`);
          if (w.totalChunks !== null) stats.push(`${w.totalChunks} 个切片`);
          const statsStr = stats.length > 0 ? ` (${stats.join(', ')})` : '';
          const desc = w.description ? ` — ${w.description}` : '';
          console.log(`  • [${w.id}] ${w.name}${tag}${desc}${statsStr}`);
        }
        console.log('\n请回复需要绑定的工作空间 ID，或执行：');
        console.log(`node ${process.argv[1]} set-workspace --workspace-id <空间ID>`);
      }
    }
    return 0;
  } catch (err) {
    if (opts.json) {
      console.log(JSON.stringify({ success: false, error: err.message }, null, 2));
    } else {
      console.error(`凭证切换失败: ${err.message}\n旧凭证保持不变，未修改任何文件。`);
    }
    return 1;
  }
}

async function cmdStatus(opts, projectRoot) {
  let config = {};
  try {
    config = loadConfig(projectRoot);
  } catch {}

  const serverUrl = config.serverUrl || 'http://localhost:8080';
  const apiKey = loadApiKey(projectRoot);
  const currentWs = config.workspaceId ? { id: config.workspaceId, name: config.workspaceName } : null;

  const statusData = {
    project_root: projectRoot,
    config_file: `${projectRoot}/.lexiloom/config.json`,
    env_file: `${projectRoot}/.lexiloom.env`,
    serverUrl,
    workspace: currentWs || {},
    api_key_configured: Boolean(apiKey),
    api_key_masked: maskKey(apiKey),
  };

  if (opts.json) {
    console.log(JSON.stringify({ success: true, data: statusData }, null, 2));
    return 0;
  }

  console.log('LexiLoom 当前项目绑定状态:');
  console.log(`  • 项目根目录: ${statusData.project_root}`);
  console.log(`  • 服务地址:   ${statusData.serverUrl}`);
  console.log(`  • API Key:    ${statusData.api_key_masked}`);
  if (currentWs && currentWs.id) {
    console.log(`  • 绑定空间:   [${currentWs.id}] ${currentWs.name || '未命名'}`);
  } else {
    console.log('  • 绑定空间:   <未绑定>');
  }
  return 0;
}

async function cmdInteractiveSwitchWorkspace(projectRoot) {
  const rl = readline.createInterface({ input, output });
  try {
    let config = {};
    try { config = loadConfig(projectRoot); } catch {}
    const serverUrl = config.serverUrl || 'http://localhost:8080';
    let apiKey = loadApiKey(projectRoot);
    if (!apiKey) {
      apiKey = (await rl.question('未检测到 API Key，请输入 API Key: ')).trim();
      if (!apiKey) {
        console.log('API Key 不能为空，取消操作。');
        return 1;
      }
      saveApiKey(projectRoot, apiKey);
    }

    const workspaces = await requestWorkspaces(serverUrl, apiKey);
    const result = buildWorkspacesResult(projectRoot, serverUrl, apiKey, workspaces);
    console.log(`\n可用工作空间列表 (共 ${result.workspaces.length} 个):`);
    result.workspaces.forEach((w, idx) => {
      const currStr = w.is_current ? ' (当前绑定)' : '';
      console.log(`  ${idx + 1}. [${w.id}] ${w.name}${currStr}`);
    });

    const choice = (await rl.question('\n请输入要绑定的工作空间编号或 ID (直接回车取消): ')).trim();
    if (!choice) {
      console.log('已取消操作。');
      return 0;
    }

    let targetId = null;
    if (/^\d+$/.test(choice)) {
      const val = parseInt(choice, 10);
      if (val >= 1 && val <= result.workspaces.length) {
        targetId = result.workspaces[val - 1].id;
      } else {
        const found = result.workspaces.find((w) => String(w.id) === String(val));
        if (found) targetId = found.id;
      }
    }

    if (targetId === null) {
      console.log(`输入无效: ${choice}`);
      return 1;
    }

    return await cmdSetWorkspace({ workspaceId: targetId, url: serverUrl, apiKey, json: false }, projectRoot);
  } catch (err) {
    console.error(`操作失败: ${err.message}`);
    return 1;
  } finally {
    rl.close();
  }
}

async function cmdInteractiveSwitchKey(projectRoot) {
  const rl = readline.createInterface({ input, output });
  try {
    const newKey = (await rl.question('请输入新的 LexiLoom API Key (将安全保存至 .lexiloom.env): ')).trim();
    if (!newKey) {
      console.log('API Key 不能为空，取消操作。');
      return 1;
    }
    const ret = await cmdSetApiKey({ apiKey: newKey, json: false }, projectRoot);
    if (ret !== 0) return ret;

    rl.close();
    return await cmdInteractiveSwitchWorkspace(projectRoot);
  } catch (err) {
    console.error(`操作失败: ${err.message}`);
    return 1;
  } finally {
    rl.close();
  }
}

function parseArgs(args) {
  const opts = {
    command: '',
    projectRoot: '',
    url: '',
    apiKey: '',
    workspaceId: undefined,
    json: false,
    help: false,
  };

  const positional = [];
  for (let i = 0; i < args.length; i++) {
    const arg = args[i];
    if (arg === '--help' || arg === '-h') {
      opts.help = true;
    } else if (arg === '--json') {
      opts.json = true;
    } else if (arg === '--project-root') {
      opts.projectRoot = args[++i];
    } else if (arg === '--url') {
      opts.url = args[++i];
    } else if (arg === '--api-key') {
      opts.apiKey = args[++i];
    } else if (arg === '--workspace-id') {
      const val = args[++i];
      opts.workspaceId = val !== undefined && /^\d+$/.test(val) ? Number(val) : val;
    } else if (!arg.startsWith('-')) {
      positional.push(arg);
    }
  }

  opts.command = positional[0] || '';
  if (positional.length > 1 && opts.workspaceId === undefined && /^\d+$/.test(positional[1])) {
    opts.workspaceId = Number(positional[1]);
  }

  return opts;
}

function printUsage() {
  console.log(`LexiLoom Workspace & AccessKey Manager (Node.js 原生零依赖版)

用法:
  node manage_workspace.js <command> [options]

子命令:
  list-workspaces     列出当前 AccessKey 可见的工作空间
  set-workspace       切换/绑定指定的工作空间 (--workspace-id <ID>)
  set-api-key         切换 AccessKey，并自动探测验证与返回空间列表 (--api-key <KEY>)
  status              查看当前绑定的服务端、工作空间与凭证状态
  switch-workspace    终端交互式切换工作空间向导
  switch-key          终端交互式切换 AccessKey 并联动选择空间向导

通用选项:
  --project-root <dir> 指定项目根目录（默认自动向上查找）
  --url <serverUrl>    LexiLoom 服务端地址
  --api-key <key>      显式指定 API Key 进行探测
  --json               以标准 JSON 格式输出结果
  -h, --help           显示帮助信息`);
}

async function main() {
  const opts = parseArgs(process.argv.slice(2));
  if (opts.help || !opts.command) {
    printUsage();
    return 0;
  }

  const projectRoot = opts.projectRoot ? require('node:path').resolve(opts.projectRoot) : findProjectRoot();

  switch (opts.command) {
    case 'list-workspaces':
      return await cmdListWorkspaces(opts, projectRoot);
    case 'set-workspace':
      return await cmdSetWorkspace(opts, projectRoot);
    case 'set-api-key':
      return await cmdSetApiKey(opts, projectRoot);
    case 'status':
      return await cmdStatus(opts, projectRoot);
    case 'switch-workspace':
      return await cmdInteractiveSwitchWorkspace(projectRoot);
    case 'switch-key':
      return await cmdInteractiveSwitchKey(projectRoot);
    default:
      console.error(`未知子命令: ${opts.command}`);
      printUsage();
      return 2;
  }
}

if (require.main === module) {
  main()
    .then((code) => {
      process.exitCode = code;
    })
    .catch((err) => {
      console.error(`未捕获异常: ${err.message}`);
      process.exitCode = 1;
    });
}
