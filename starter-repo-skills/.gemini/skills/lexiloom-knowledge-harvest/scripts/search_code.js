#!/usr/bin/env node
'use strict';

/**
 * LexiLoom Local Code Search (Node.js 原生跨平台版 - 安全加固型)
 * 使用本地 ripgrep (rg) 或 grep 精确检索源码，过滤排除无关与敏感文件。
 */

const { spawnSync } = require('node:child_process');
const path = require('node:path');
const fs = require('node:fs');
const { findProjectRoot } = require('./_common.js');

function printUsage() {
  console.error('用法: node search_code.js <关键词> [扩展名，支持单个或多个如 java 或 java|xml|sql]');
}

function hasCommand(cmd) {
  const isWin = process.platform === 'win32';
  const checkCmd = isWin ? 'where' : 'which';
  const res = spawnSync(checkCmd, [cmd], { stdio: 'ignore' });
  return res.status === 0;
}

function cleanPattern(pat) {
  if (typeof pat !== 'string') return '';
  // 过滤控制字符与换行
  return pat.replace(/[\x00-\x1f\x7f]/g, '').trim();
}

function main() {
  const args = process.argv.slice(2);
  if (args.length === 0 || args.includes('-h') || args.includes('--help')) {
    printUsage();
    process.exit(args.length === 0 ? 2 : 0);
  }

  const keyword = args[0];
  const extFilter = args[1] || '';

  if (extFilter && !/^[A-Za-z0-9_-]+([|,][A-Za-z0-9_-]+)*$/.test(extFilter)) {
    console.error('错误：扩展名只允许字母、数字、下划线和连字符，多个扩展名可用 | 或 , 分隔（如 java|xml|sql）。');
    process.exit(2);
  }

  const projectRoot = findProjectRoot();
  process.chdir(projectRoot);

  const MAX_LINES = 160;
  const exts = extFilter ? extFilter.split(/[|,]/).filter(Boolean) : [];

  // 从 .lexiloom/config.json 读取自定义排除规则并严格清洗
  let customExcludes = [];
  const configFile = path.join(projectRoot, '.lexiloom', 'config.json');
  if (fs.existsSync(configFile)) {
    try {
      const config = JSON.parse(fs.readFileSync(configFile, 'utf8'));
      const pats = config.search && config.search.excludePatterns;
      if (Array.isArray(pats)) {
        customExcludes = pats.map(cleanPattern).filter(Boolean);
      }
    } catch {}
  }

  const hasRg = hasCommand('rg');
  const hasGrep = hasCommand('grep');

  if (!hasRg && !hasGrep) {
    console.error('错误：当前系统未检测到 rg (ripgrep) 或 grep 命令，无法执行代码检索。');
    process.exit(2);
  }

  let outputText = '';
  let exitCode = 0;

  if (hasRg) {
    const rgArgs = [
      '--line-number',
      '--context', '2',
      '--ignore-case',
      '--fixed-strings',
      '--hidden',
      '--max-count', '50',
      '-g', '!**/.git/**',
      '-g', '!**/target/**',
      '-g', '!**/build/**',
      '-g', '!**/dist/**',
      '-g', '!**/node_modules/**',
      '-g', '!**/.idea/**',
      '-g', '!**/.venv/**',
      '-g', '!**/vendor/**',
      '-g', '!**/.lexiloom.env',
      '-g', '!**/.lexiloom/**',
      '-g', '!**/*.min.js',
      '-g', '!**/*.map',
    ];

    for (const pat of customExcludes) {
      rgArgs.push('-g', `!${pat.replace(/^!+/, '')}`);
    }
    for (const ext of exts) {
      rgArgs.push('-g', `*.${ext}`);
    }

    // 使用 '--' 显式结束选项解析，防止关键词以 '-' 开头时被当成参数
    rgArgs.push('--', keyword, '.');

    const res = spawnSync('rg', rgArgs, {
      cwd: projectRoot,
      encoding: 'utf8',
      maxBuffer: 10 * 1024 * 1024,
    });

    exitCode = res.status !== null ? res.status : 1;
    outputText = res.stdout || '';
    if (res.error) {
      console.error(`代码检索执行失败: ${res.error.message}`);
      process.exit(1);
    }
  } else {
    // grep fallback
    const commonDirs = ['src', 'app', 'lib', 'packages', 'cmd', 'pkg', 'internal'];
    const searchPaths = commonDirs.filter((d) => fs.existsSync(path.join(projectRoot, d)));
    if (searchPaths.length === 0) searchPaths.push('.');

    const grepArgs = [
      '-rInF',
      '-I',
      '-m', '50',
      '--exclude-dir=.git',
      '--exclude-dir=target',
      '--exclude-dir=build',
      '--exclude-dir=dist',
      '--exclude-dir=node_modules',
      '--exclude-dir=.idea',
      '--exclude-dir=.venv',
      '--exclude-dir=.lexiloom',
      '--exclude=.lexiloom.env',
      '--exclude=*.min.js',
      '--exclude=*.map',
    ];

    for (const ext of exts) {
      grepArgs.push(`--include=*.${ext}`);
    }
    grepArgs.push('--', keyword, ...searchPaths);

    const res = spawnSync('grep', grepArgs, {
      cwd: projectRoot,
      encoding: 'utf8',
      maxBuffer: 10 * 1024 * 1024,
    });

    exitCode = res.status !== null ? res.status : 1;
    outputText = res.stdout || '';
  }

  if (exitCode === 1 || !outputText.trim()) {
    console.log(`未找到匹配内容（关键词：${keyword}）。`);
    process.exit(0);
  } else if (exitCode !== 0) {
    console.error(`代码检索执行失败 (exit code: ${exitCode})。请检查目录权限或检索参数。`);
    process.exit(exitCode);
  }

  const lines = outputText.split(/\r?\n/);
  const totalLines = lines.length;
  const displayLines = lines.slice(0, MAX_LINES);
  console.log(displayLines.join('\n'));

  if (totalLines > MAX_LINES) {
    console.log('');
    console.log(`[提示] 代码检索共输出 ${totalLines} 行，以上仅展示前 ${MAX_LINES} 行。请使用更精确的符号名或指定文件扩展名缩小范围。`);
  }
}

main();
