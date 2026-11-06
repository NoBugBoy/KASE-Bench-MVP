#!/usr/bin/env node
'use strict';

/**
 * LexiLoom Get Document Detail (Node.js 原生零依赖版)
 * 获取并打印指定文档的完整详情与 Markdown 正文。
 */

const {
  findProjectRoot,
  loadConfig,
  loadApiKey,
  extractProblemDetail,
  lexiloomFetch,
} = require('./_common.js');

function printUsage() {
  console.error('用法: node get_document.js <正整数 documentId> [最大字符数，默认 30000，传 0 表示不截断] [--json]');
}

async function main() {
  const args = process.argv.slice(2);
  let documentId = '';
  let maxChars = 30000;
  let jsonOutput = false;

  const positional = [];
  for (let i = 0; i < args.length; i++) {
    const arg = args[i];
    if (arg === '--json') {
      jsonOutput = true;
    } else if (arg === '-h' || arg === '--help') {
      printUsage();
      process.exit(0);
    } else if (!arg.startsWith('-')) {
      positional.push(arg);
    }
  }

  if (positional.length === 0) {
    printUsage();
    process.exit(2);
  }

  documentId = positional[0];
  if (!/^[1-9]\d*$/.test(documentId)) {
    console.error('错误：documentId 必须是正整数。');
    process.exit(2);
  }

  if (positional.length > 1) {
    const parsed = parseInt(positional[1], 10);
    if (!Number.isInteger(parsed) || parsed < 0) {
      console.error('错误：最大字符数必须是非负整数（传 0 表示不截断）。');
      process.exit(2);
    }
    maxChars = parsed;
  }

  const projectRoot = findProjectRoot();
  let config;
  try {
    config = loadConfig(projectRoot);
  } catch (e) {
    console.error(`错误：${e.message}`);
    process.exit(2);
  }

  const apiKey = loadApiKey(projectRoot);
  if (!apiKey) {
    console.error('错误：未读取到 API Key。请设置 LEXILOOM_API_KEY 环境变量或运行 scripts/setup.js 配置 .lexiloom.env。');
    process.exit(2);
  }

  const { serverUrl } = config;
  const docUrl = `${serverUrl}/api/v1/openapi/documents/${documentId}`;
  let resp;
  try {
    resp = await lexiloomFetch(docUrl, {
      method: 'GET',
      apiKey,
      timeoutMs: 30000,
    });
  } catch (err) {
    console.error(`文档详情请求失败：网络连接错误或请求超时 (${err.message})。请检查服务地址与网络连接。`);
    process.exit(1);
  }

  if (!resp.ok) {
    let errBody = '';
    try {
      errBody = await resp.json();
    } catch {
      try {
        errBody = await resp.text();
      } catch {}
    }
    const detail = extractProblemDetail(errBody);

    if (resp.status === 400) {
      console.error(`文档详情请求参数错误 (HTTP 400)。请确认 documentId 是正整数。`);
    } else if (resp.status === 401 || resp.status === 403) {
      if (detail) {
        console.error(`文档详情请求失败 (HTTP ${resp.status})：${detail}`);
      } else if (resp.status === 401) {
        console.error(`文档详情鉴权失败 (HTTP 401)。请检查 API Key。`);
      } else {
        console.error(`无权读取文档 (HTTP 403)。请确认当前凭证具有 doc:read 权限及该文档访问权限。`);
      }
    } else if (resp.status === 404) {
      console.error(`文档详情请求返回 HTTP 404：文档可能不存在，也可能是详情端点未部署或路径不匹配。`);
    } else if (resp.status === 429) {
      console.error(`请求过于频繁 (HTTP 429)，请稍后重试。`);
    } else if (resp.status >= 500) {
      console.error(`知识库服务暂时异常 (HTTP ${resp.status})，请检查后端日志。`);
    } else {
      console.error(`文档详情请求失败 (HTTP ${resp.status})。响应正文已隐藏，避免意外泄露敏感信息。`);
    }
    process.exit(1);
  }

  let doc;
  try {
    doc = await resp.json();
  } catch {
    console.error('错误：服务端返回的不是有效 JSON。');
    process.exit(1);
  }

  if (doc && typeof doc === 'object' && !('content' in doc) && doc.data && typeof doc.data === 'object') {
    doc = doc.data;
  }

  if (!doc || typeof doc !== 'object' || Array.isArray(doc)) {
    console.error('错误：文档详情响应应为 JSON 对象。');
    process.exit(1);
  }
  if (!('content' in doc)) {
    console.error('错误：详情响应缺少 content 字段，无法作为完整正文使用。');
    process.exit(1);
  }
  if (String(doc.id || '') !== documentId) {
    console.error('错误：详情响应的 id 与请求的 documentId 不一致。');
    process.exit(1);
  }

  if (jsonOutput) {
    console.log(JSON.stringify({ success: true, data: doc }, null, 2));
    return;
  }

  console.log('文档详情（字段值按服务端响应展示）');
  const metaKeys = ['id', 'title', 'workspaceId', 'categoryName', 'status', 'version', 'createTime', 'updateTime'];
  for (const key of metaKeys) {
    console.log(`${key}: ${JSON.stringify(doc[key] !== undefined ? doc[key] : null)}`);
  }

  console.log('\n--- Markdown 正文 content 开始 ---\n');
  const content = doc.content;
  if (content === null || content === undefined) {
    console.log('[content 为 null]');
  } else if (typeof content === 'string') {
    if (maxChars > 0 && content.length > maxChars) {
      const snippet = content.slice(0, maxChars);
      process.stdout.write(snippet.endsWith('\n') ? snippet : snippet + '\n');
      console.log(`\n[提示：文档全文共 ${content.length} 字符，已截断展示前 ${maxChars} 字符。如需不截断全文，请执行 node get_document.js ${documentId} 0]`);
    } else {
      process.stdout.write(content.endsWith('\n') ? content : content + '\n');
    }
  } else {
    console.log(JSON.stringify(content, null, 2));
  }
  console.log('\n--- Markdown 正文 content 结束 ---');
}

main().catch((err) => {
  console.error(`未处理错误: ${err.message}`);
  process.exit(1);
});
