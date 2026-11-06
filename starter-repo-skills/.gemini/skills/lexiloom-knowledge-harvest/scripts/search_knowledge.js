#!/usr/bin/env node
'use strict';

/**
 * LexiLoom Knowledge Search (Node.js 原生零依赖版)
 * 查询 LexiLoom OpenAPI 知识库检索接口并格式化输出证据。
 */

const path = require('node:path');
const {
  findProjectRoot,
  loadConfig,
  loadApiKey,
  extractProblemDetail,
  lexiloomFetch,
} = require('./_common.js');

function printUsage() {
  console.error('用法: node search_knowledge.js <自然语言检索问题> [topK，默认 5，范围 1–20] [--json]');
}

async function main() {
  const args = process.argv.slice(2);
  let query = '';
  let topK = 5;
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

  query = positional[0];
  if (positional.length > 1) {
    const parsed = parseInt(positional[1], 10);
    if (!Number.isInteger(parsed) || parsed < 1 || parsed > 20) {
      console.error('错误：topK 必须是 1–20 的整数。');
      process.exit(2);
    }
    topK = parsed;
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

  const { serverUrl, workspaceId, workspaceName } = config;

  // 1. 优先使用 POST 发送 JSON 请求体 (支持长 Query，避免 URL 泄露与截断)
  let searchUrl = `${serverUrl}/api/v1/openapi/knowledge/search`;
  let resp;
  let usedMethod = 'POST';

  try {
    resp = await lexiloomFetch(searchUrl, {
      method: 'POST',
      apiKey,
      timeoutMs: 30000,
      headers: {
        'Content-Type': 'application/json',
        'X-Call-Source': 'CLI_SKILL',
      },
      body: JSON.stringify({
        q: query,
        workspaceId,
        topK,
      }),
    });

    // 若 POST 返回 405 Method Not Allowed 或 404，平滑降级为 GET 请求
    if (resp.status === 405 || resp.status === 404) {
      usedMethod = 'GET';
      const getParams = new URLSearchParams({
        q: query,
        workspaceId: String(workspaceId),
        topK: String(topK),
      });
      resp = await lexiloomFetch(`${searchUrl}?${getParams.toString()}`, {
        method: 'GET',
        apiKey,
        timeoutMs: 30000,
        headers: {
          'X-Call-Source': 'CLI_SKILL',
        },
      });
    }
  } catch (err) {
    console.error(`知识库检索请求失败：网络连接错误或请求超时 (${err.message})。请检查服务地址与网络连接。`);
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
      console.error(`知识库检索请求参数错误 (HTTP 400)${detail ? `：${detail}` : ''}。`);
    } else if (resp.status === 401 || resp.status === 403) {
      if (detail) {
        console.error(`知识库检索失败 (HTTP ${resp.status})：${detail}`);
      } else {
        console.error(`知识库检索鉴权失败 (HTTP ${resp.status})。请检查 API Key（需 kb:search 权限）与工作空间访问权限。`);
      }
    } else if (resp.status === 404) {
      console.error(`知识库检索接口不存在 (HTTP 404)：请确认后端已部署 /api/v1/openapi/knowledge/search。`);
    } else if (resp.status === 429) {
      console.error(`请求过于频繁 (HTTP 429)，请稍后重试。`);
    } else if (resp.status >= 500) {
      console.error(`知识库服务暂时异常 (HTTP ${resp.status})，请检查后端日志。`);
    } else {
      console.error(`知识库检索请求失败 (HTTP ${resp.status})。响应正文已隐藏，避免意外泄露敏感信息。`);
    }
    process.exit(1);
  }

  let data;
  try {
    data = await resp.json();
  } catch {
    console.error('错误：服务端返回的不是有效 JSON。');
    process.exit(1);
  }

  let results = [];
  let traceId = null;
  let totalMatches = null;

  if (data && typeof data === 'object' && !Array.isArray(data)) {
    results = data.results;
    traceId = data.traceId;
    totalMatches = data.totalMatches;
    if (results === undefined && data.data && typeof data.data === 'object' && !Array.isArray(data.data)) {
      results = data.data.results;
      traceId = traceId || data.data.traceId;
      totalMatches = totalMatches !== undefined ? totalMatches : data.data.totalMatches;
    }
    if (results === undefined && Array.isArray(data.data)) {
      results = data.data;
    }
    if (results === undefined) results = [];
  } else if (Array.isArray(data)) {
    results = data;
    totalMatches = data.length;
  }

  if (!Array.isArray(results)) {
    console.error('错误：响应中的 results 字段格式不正确。');
    process.exit(1);
  }

  if (jsonOutput) {
    console.log(
      JSON.stringify(
        {
          success: true,
          workspaceId,
          workspaceName,
          totalMatches,
          traceId,
          results,
        },
        null,
        2
      )
    );
    return;
  }

  const traceStr = traceId ? ` | 链路 Trace ID: ${traceId}` : '';
  const totalStr = totalMatches !== null && totalMatches !== undefined ? ` (匹配总数: ${totalMatches})` : '';
  console.log(`知识库检索：${workspaceName || '未命名工作空间'}（ID: ${workspaceId}）`);
  console.log(`返回 ${results.length} 条切片${totalStr}${traceStr}。\n`);

  if (results.length === 0) {
    console.log('未检索到相关切片。建议：');
    console.log('  1. 提炼核心业务关键词/领域实体术语（避免直接输入整句口语提问）；');
    console.log('  2. 适当增大 topK（如 10）；');
    console.log('  3. 确认相关文档已入库并完成 AI 索引。');
    return;
  }

  results.forEach((item, index) => {
    if (!item || typeof item !== 'object') return;
    const title = item.documentTitle || '无标题';
    const category = item.categoryName || '未分类';
    const docId = item.documentId !== undefined ? item.documentId : '未知';
    const chunk = item.chunkIndex !== undefined ? item.chunkIndex : '未知';
    const page = item.pageNumber;
    const score = item.score;
    const coords = item.deepDocCoordinates;

    let meta = `${index + 1}. 文档：《${title}》 [${category}] | 文档 ID: ${docId} | 切片: ${chunk}`;
    if (page !== undefined && page !== null) meta += ` | 页码: ${page}`;
    if (score !== undefined && score !== null) meta += ` | 得分: ${score}`;
    if (coords && coords !== '[]' && coords !== 'null') meta += ` | 视觉坐标: ${coords}`;

    const rawContent = String(item.content || '').trim();
    // 规整连续空行，同时保留 Markdown 列表、表格与代码块缩进结构
    const cleanContent = rawContent.replace(/\n{3,}/g, '\n\n');

    console.log(meta);
    if (!cleanContent) {
      console.log('   [空切片正文]');
    } else if (cleanContent.length > 2000) {
      const snippet = cleanContent.slice(0, 2000);
      const indented = snippet.split('\n').map((l) => '   ' + l).join('\n');
      console.log(indented);
      console.log(`   ... [切片内容已截断，可通过 node get_document.js ${docId} 读取完整文档]`);
    } else {
      const indented = cleanContent.split('\n').map((l) => '   ' + l).join('\n');
      console.log(indented);
    }
    console.log();
  });
}

main().catch((err) => {
  console.error(`未处理错误: ${err.message}`);
  process.exit(1);
});
