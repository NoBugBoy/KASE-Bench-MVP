---
name: lexiloom-workspace-manager
description: >-
  用于查看、管理和切换 LexiLoom 知识库的工作空间（Workspace）与访问密钥（AccessKey / API Key）。当用户提到“切换空间”、“切换工作空间”、“查看空间列表”、“更换空间”、“换个知识库”、“切换accesskey”、“更换key”、“更新api key”、“修改密钥”或需要重新绑定空间与凭证时，务必激活此技能。
---

# LexiLoom Workspace & AccessKey Manager

## 目标与定位

本技能专用于管理当前代码仓库与 LexiLoom 知识库服务端的**空间绑定与凭证管理**：
1. **指令一：切换空间（Switch Workspace）**：查询当前 AccessKey 在服务端所能查看到的全部工作空间列表，结构化呈现给用户进行选择，并在用户确认后安全更新本地配置，即刻在当前 Agent 对话及检索/入库中生效。
2. **指令二：切换 AccessKey（Switch AccessKey）**：更换本项目的 API Key，并在更换完成后**立刻且固定触发选择工作空间流程**，确保新凭证与目标工作空间保持对齐。

> **脚本路径约定**：下文中 `<SKILL_DIR>` 指代当前 `SKILL.md` 所在的目录路径。执行脚本时，工作目录应位于**目标代码仓库或项目根目录**。请优先直接调用 `node "<SKILL_DIR>/scripts/manage_workspace.js"`（零三方依赖，完全兼容 Node.js 18+ 原生环境；亦兼容 `python3` 调用）。
> **安全准则**：脚本会自动处理 `.lexiloom.env`（权限 600 并加入 `.gitignore`），严禁在对话或终端输出中打印明文 AccessKey，仅展示脱敏后的 Key（如 `sk-***1234`）。

---

## 核心指令流程规范

### 指令一：切换空间 (Switch Workspace)

当用户表达切换空间、查看空间、换个知识库等意图时，按以下步骤执行：

#### 步骤 1：查询当前 AccessKey 所能看到的工作空间列表
运行命令获取工作空间 JSON 数据（原生 Node.js 执行）：
```bash
node "<SKILL_DIR>/scripts/manage_workspace.js" list-workspaces --json
```

#### 步骤 2：向用户展示可选工作空间并引导选择
读取 JSON 中的 `workspaces` 数组及 `current_workspace`，以整齐友好的 Markdown 列表向用户呈现：
- 展示当前绑定的空间名称与 ID。
- 列出新 key / 当前 key 下所有可选工作空间：
  - 空间 ID 与名称（高亮标记当前已绑定的空间）
  - 空间描述（若有）
  - 文档篇数与切片总数（指示数据规模）
- 提示用户输入目标工作空间编号或 ID 进行切换。

#### 步骤 3：执行空间切换与配置更新
根据用户选择的目标空间 ID，执行：
```bash
node "<SKILL_DIR>/scripts/manage_workspace.js" set-workspace --workspace-id <目标空间ID> --json
```
脚本会自动向服务端再次校验该 ID 是否在合法列表内，并更新 `.lexiloom/config.json` 中的 `workspace` 节点。

#### 步骤 4：向用户确认切换完成
向用户汇报已成功切换到目标工作空间，并明确说明：
- 绑定的新空间 ID 与空间名称。
- 本地配置文件 `.lexiloom/config.json` 已更新。
- **当前 Agent 对话已即刻生效**，随后的 `search_knowledge`（混合检索）与 `import_document`（知识入库）均会自动在该新空间下执行。

---

### 指令二：切换 AccessKey (Switch AccessKey)

当用户提供新 key 或要求更换凭证时，执行此指令。
> [!IMPORTANT]
> **固定联动规则**：切换完 AccessKey 之后，**立刻且必须触发选择工作空间流程**！绝对不能在更新 key 后直接结束，因为凭证切换后原工作空间可能不可见或不再适用。

#### 步骤 1：获取新 AccessKey 并执行探测验证与更新
引导用户提供新 key，或从用户输入中提取新 key，运行：
```bash
node "<SKILL_DIR>/scripts/manage_workspace.js" set-api-key --api-key "<新KEY>" --json
```

**脚本内部自动化保障机制**：
1. **探测校验先行**：脚本首先使用新 Key 请求服务端 `/api/v1/openapi/workspaces`；
   - 若鉴权失败（401 / 403）或服务端无法访问，脚本立即报错终止，**绝对不会破坏原有配置文件**，旧凭证完好保留。
2. **安全落盘**：校验通过后，脚本将新 Key 写入 `.lexiloom.env`，并确保文件权限为 `600` 及 `.gitignore` 包含该文件。
3. **空间联动**：脚本在返回成功结果的同时，已经自动拉取并包含了该新 Key 下可用的全部工作空间列表。

#### 步骤 2：立刻触发空间选择流程（固定执行）
解析脚本返回的 JSON 数据（`requires_workspace_selection: true`）：
1. 告知用户新 AccessKey 已验证通过并安全更新（输出脱敏 Key）；
2. **立刻展示该新 Key 下所能看到的工作空间列表**；
3. 检查原有绑定的工作空间在新 Key 下是否依然存在：
   - 若依然存在，告知用户：“检测到此前绑定的空间在新凭证下依然可见，您可以继续保留或选择新空间”；
   - 若不存在或此前未绑定，提醒用户：“原工作空间在新凭证下不可用，请立即选择一个工作空间进行绑定”；
4. 引导用户选择要绑定的空间 ID。

#### 步骤 3：绑定选定的工作空间
用户选定后，调用指令一中的命令完成空间绑定：
```bash
node "<SKILL_DIR>/scripts/manage_workspace.js" set-workspace --workspace-id <目标空间ID> --json
```
至此完成 AccessKey 切换与空间重绑定的完整闭环。

---

## 常用脚本调用命令速查

| 操作意图 | 执行命令 |
|---|---|
| 查看当前绑定状态与脱敏凭证 | `node "<SKILL_DIR>/scripts/manage_workspace.js" status --json` |
| 查询当前 Key 下所有工作空间 | `node "<SKILL_DIR>/scripts/manage_workspace.js" list-workspaces --json` |
| 切换到指定工作空间 | `node "<SKILL_DIR>/scripts/manage_workspace.js" set-workspace --workspace-id <ID> --json` |
| 切换 AccessKey（并自动返回新空间列表） | `node "<SKILL_DIR>/scripts/manage_workspace.js" set-api-key --api-key "<KEY>" --json` |
| 交互式快捷向导（终端手动运行） | `node "<SKILL_DIR>/scripts/manage_workspace.js" switch-workspace`<br>`node "<SKILL_DIR>/scripts/manage_workspace.js" switch-key` |
