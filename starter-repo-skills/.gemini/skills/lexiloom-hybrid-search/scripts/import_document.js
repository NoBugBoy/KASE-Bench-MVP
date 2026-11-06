#!/usr/bin/env node
'use strict';

/**
 * LexiLoom Document Import (Node.js 原生零依赖版 - 安全加固型)
 * 依赖 Node.js 18+ 原生 fetch、FormData、Blob 进行文件流上传入库。
 */

const fs = require('node:fs');
const path = require('node:path');
const {
  findProjectRoot,
  loadConfig,
  loadApiKey,
  extractProblemDetail,
  lexiloomFetch,
} = require('./_common.js');

const MAX_FILE_SIZE = 100 * 1024 * 1024; // 100 MB 上限保护，防止超大文件撑爆进程内存

function printUsage() {
  console.log(`用法:
  node import_document.js <文件路径> [选项]

支持的文件类型:
  .pdf, .docx, .doc, .xlsx, .xls, .pptx, .ppt, .txt, .md, .csv, .tsv, .html, .htm, .json

选项:
  --title <标题>              指定知识库文档标题（默认使用文件名）
  --category-id <ID>          指定归属分类 ID
  --category-name <分类名>    指定归属分类名称
  --workspace-id <ID>         覆盖项目绑定的工作空间 ID
  --timeout <秒数>            自定义请求超时时间（默认 180 秒）
  --json                      以标准 JSON 格式输出结果
  -h, --help                  显示帮助信息

示例:
  # 导入提炼好的 Markdown 知识文档
  node import_document.js "docs/customer_issue_sop.md" --title "客户支付超时排查指南与解决方案"

  # 导入测试用例表格并指定分类
  node import_document.js "testcases/payment_regression.xlsx" --category-name "测试用例"`);
}

function getMimeType(ext) {
  switch (ext.toLowerCase()) {
    case 'pdf': return 'application/pdf';
    case 'docx': return 'application/vnd.openxmlformats-officedocument.wordprocessingml.document';
    case 'doc': return 'application/msword';
    case 'xlsx': return 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet';
    case 'xls': return 'application/vnd.ms-excel';
    case 'pptx': return 'application/vnd.openxmlformats-officedocument.presentationml.presentation';
    case 'ppt': return 'application/vnd.ms-powerpoint';
    case 'txt': return 'text/plain';
    case 'md': return 'text/markdown';
    case 'csv': return 'text/csv';
    case 'tsv': return 'text/tab-separated-values';
    case 'html':
    case 'htm': return 'text/html';
    case 'json': return 'application/json';
    default: return 'application/octet-stream';
  }
}

async function main() {
  const args = process.argv.slice(2);
  let filePath = '';
  let optTitle = '';
  let optCategoryId = '';
  let optCategoryName = '';
  let optWorkspaceId = '';
  let optTimeout = 180;
  let jsonOutput = false;

  for (let i = 0; i < args.length; i++) {
    const arg = args[i];
    if (arg === '-h' || arg === '--help') {
      printUsage();
      process.exit(0);
    } else if (arg === '--json') {
      jsonOutput = true;
    } else if (arg === '--title') {
      optTitle = args[++i] || '';
    } else if (arg === '--category-id') {
      optCategoryId = args[++i] || '';
    } else if (arg === '--category-name') {
      optCategoryName = args[++i] || '';
    } else if (arg === '--workspace-id') {
      optWorkspaceId = args[++i] || '';
    } else if (arg === '--timeout') {
      const t = parseInt(args[++i], 10);
      if (!Number.isInteger(t) || t <= 0) {
        console.error('错误：--timeout 必须为正整数。');
        process.exit(2);
      }
      optTimeout = t;
    } else if (!arg.startsWith('-')) {
      if (!filePath) {
        filePath = arg;
      } else {
        console.error(`错误：多余的位置参数 ${arg}`);
        process.exit(2);
      }
    } else {
      console.error(`错误：未知选项 ${arg}`);
      printUsage();
      process.exit(2);
    }
  }

  if (!filePath) {
    console.error('错误：必须指定待导入的文件路径。');
    printUsage();
    process.exit(2);
  }

  const fileAbsPath = path.resolve(process.cwd(), filePath);
  let stat;
  try {
    stat = fs.statSync(fileAbsPath);
  } catch (err) {
    console.error(`错误：指定的文件不存在或无法访问: ${filePath} (${err.message})`);
    process.exit(2);
  }

  if (!stat.isFile()) {
    console.error(`错误：指定的路径不是普通文件: ${filePath}`);
    process.exit(2);
  }

  const fileSize = stat.size;
  if (fileSize === 0) {
    console.error('错误：待导入文件为空（0 字节），拒绝导入。');
    process.exit(2);
  }

  if (fileSize > MAX_FILE_SIZE) {
    const sizeMb = (fileSize / (1024 * 1024)).toFixed(1);
    console.error(`错误：待导入文件过大 (${sizeMb} MB)，超过单文件安全上限 (100 MB)。请压缩或拆分后再导入。`);
    process.exit(2);
  }

  const rawFilename = path.basename(fileAbsPath);
  // 清洗文件名，防止非法控制字符与路径伪装
  const filename = rawFilename.replace(/[\x00-\x1f\x7f"/\\?*<>|]/g, '_');
  const ext = path.extname(filename).replace(/^\./, '').toLowerCase();
  const supportedExts = new Set(['pdf', 'docx', 'doc', 'xlsx', 'xls', 'pptx', 'ppt', 'txt', 'md', 'csv', 'tsv', 'html', 'htm', 'json']);

  if (!supportedExts.has(ext)) {
    console.error(`错误：不支持的文件类型 (.${ext})。`);
    console.error('支持的格式包含: .pdf, .docx, .doc, .xlsx, .xls, .pptx, .ppt, .txt, .md, .csv, .tsv, .html, .htm, .json');
    console.error('对于纯文本内容建议提炼合并为高质量的 .md 文件；若涉及截图或图片强烈推荐使用 .pdf 或 .docx。');
    process.exit(2);
  }

  const mimeType = getMimeType(ext);

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

  const serverUrl = config.serverUrl;
  const workspaceId = optWorkspaceId ? parseInt(optWorkspaceId, 10) : config.workspaceId;
  const workspaceName = config.workspaceName;

  if (!workspaceId || !Number.isInteger(workspaceId) || workspaceId <= 0) {
    console.error('错误：工作空间 ID 必须是正整数。请在 .lexiloom/config.json 确认或通过 --workspace-id 指定。');
    process.exit(2);
  }

  const defaultTitle = path.basename(filename, path.extname(filename));
  const finalTitle = optTitle || defaultTitle;

  // 使用 Node 18+ 原生 FormData 与 Blob 进行二进制文件封装
  const fileBuffer = fs.readFileSync(fileAbsPath);
  const fileBlob = new Blob([fileBuffer], { type: mimeType });

  const formData = new FormData();
  formData.append('file', fileBlob, filename);
  formData.append('workspaceId', String(workspaceId));
  if (finalTitle) formData.append('title', finalTitle);
  if (optCategoryId) formData.append('categoryId', String(optCategoryId));
  if (optCategoryName) formData.append('categoryName', optCategoryName);

  if (!jsonOutput) {
    console.log('🚀 正在向 LexiLoom 发起文档导入...');
    console.log(`  - 目标空间: ${workspaceName || '工作空间'} (ID: ${workspaceId})`);
    console.log(`  - 文档标题: ${finalTitle}`);
    console.log(`  - 文件路径: ${fileAbsPath} (${fileSize} 字节, ${mimeType})`);
    if (optCategoryName) console.log(`  - 目标分类: ${optCategoryName}`);
  }

  const importUrl = `${serverUrl}/api/v1/openapi/documents/import?workspaceId=${workspaceId}`;
  let resp;
  try {
    resp = await lexiloomFetch(importUrl, {
      method: 'POST',
      apiKey,
      timeoutMs: optTimeout * 1000,
      body: formData,
    });
  } catch (err) {
    console.error(`文档导入请求失败：${err.message}`);
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
      console.error(`文档导入参数错误 (HTTP 400)${detail ? `：${detail}` : ''}。`);
      console.error('请确认工作空间 ID 正确，且文件格式有效。');
    } else if (resp.status === 401 || resp.status === 403) {
      if (detail) {
        console.error(`文档导入鉴权失败 (HTTP ${resp.status})：${detail}`);
      } else {
        console.error(`文档导入鉴权失败 (HTTP ${resp.status})。请检查 API Key 是否具有文档导入权限（doc:import / doc:write），以及对应工作空间的授权。`);
      }
    } else if (resp.status === 404) {
      console.error('文档导入接口未找到 (HTTP 404)。请确认后端已部署 POST /api/v1/openapi/documents/import。');
    } else if (resp.status === 413) {
      console.error('上传文件过大 (HTTP 413 Payload Too Large)。请检查文件大小或调大后端上传限制。');
    } else if (resp.status === 429) {
      console.error('请求过于频繁 (HTTP 429 Too Many Requests)，请稍后重试。');
    } else if (resp.status >= 500) {
      console.error(`知识库服务暂时异常 (HTTP ${resp.status})${detail ? `：${detail}` : ''}。请检查后端服务日志。`);
    } else {
      console.error(`文档导入失败 (HTTP ${resp.status})。响应正文已隐藏，避免意外泄露敏感信息。`);
    }
    process.exit(1);
  }

  let data = {};
  try {
    data = await resp.json();
  } catch {
    // 允许空返回
  }

  let docId = '未知';
  let title = finalTitle;
  let status = 'PROCESSING';
  let chunks = null;
  let category = optCategoryName || null;
  let msg = null;

  if (data && typeof data === 'object') {
    msg = data.message || data.msg;
    const rawData = data.data;
    if (typeof rawData === 'number' || typeof rawData === 'string') {
      docId = String(rawData);
      title = data.title || title;
      status = data.status || status;
    } else if (rawData && typeof rawData === 'object') {
      docId = rawData.id || rawData.documentId || docId;
      title = rawData.title || rawData.name || title;
      status = rawData.status || status;
      chunks = rawData.totalChunks !== undefined ? rawData.totalChunks : rawData.chunkCount;
      category = rawData.categoryName || rawData.category || category;
      msg = rawData.message || rawData.msg || msg;
    } else {
      docId = data.id || data.documentId || docId;
      title = data.title || data.name || title;
      status = data.status || status;
      chunks = data.totalChunks !== undefined ? data.totalChunks : data.chunkCount;
      category = data.categoryName || data.category || category;
    }
  }

  const resultData = {
    documentId: docId,
    title,
    status,
    categoryName: category,
    totalChunks: chunks,
    workspaceId,
    serverMessage: msg,
  };

  if (jsonOutput) {
    console.log(JSON.stringify({ success: true, data: resultData }, null, 2));
    return;
  }

  console.log(`✓ 文档导入成功（HTTP ${resp.status}）！`);
  console.log(`  - 文档 ID: ${docId}`);
  console.log(`  - 文档标题: ${title}`);
  console.log(`  - 当前状态: ${status}`);
  if (category) console.log(`  - 归属分类: ${category}`);
  if (chunks !== null && chunks !== undefined) console.log(`  - 切片数量: ${chunks}`);
  if (msg && !['success', 'ok'].includes(msg.toLowerCase())) console.log(`  - 服务端信息: ${msg}`);
}

main().catch((err) => {
  console.error(`未处理错误: ${err.message}`);
  process.exit(1);
});
