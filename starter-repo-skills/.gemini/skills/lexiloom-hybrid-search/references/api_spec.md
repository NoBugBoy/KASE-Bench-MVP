# LexiLoom OpenAPI 规范与接口契约参考

本文档描述 LexiLoom OpenAPI REST 接口契约，供技能脚本（`scripts/*.sh`）调用、排查接口异常及核对数据字段语义时参考。日常检索请直接调用 `scripts/` 下的封装脚本，不要在终端中手写包含明文 API Key 的 curl 请求。

## 1. 基础信息与鉴权约定

- **基础路径**：`/api/v1/openapi`（兼容别名路径：`/v1/openapi`）
- **数据格式**：标准 JSON；流式问答使用 `text/event-stream` (SSE)
- **双通道认证**：
  - `Authorization: Bearer <API_KEY>`（脚本推荐，通过 `curl --config -` 从标准输入传入，避免在进程列表中暴露）
  - `X-API-Key: <API_KEY>`
- **调用来源标识**：
  - 检索请求推荐携带 `X-Call-Source: CLI_SKILL`（GET 请求亦支持 `callSource=CLI_SKILL` 参数），便于平台对 CLI/Agent 与 Web 端消耗进行独立审计与治理。
- **权限范围 (OpenAPI Scopes)**：
  - `GET /workspaces`：需要 `kb:read`
  - `POST/GET /knowledge/search`：需要 `kb:search`
  - `GET /documents/{id}`：需要 `doc:read`
  - `POST /chat/completions`、`POST /chat`、`POST /chat/stream`：需要 `chat:completion`
- **错误结构**：统一遵循 RFC 7807 ProblemDetail 规范，包含 `title`、`status`、`detail`、`code`、`timestamp`。当权限不足时返回 HTTP 403 并在 `detail` 中指明具体原因（如缺少特定 scope 或未加入空间白名单）。

---

## 2. 接口列表与数据契约

### 2.1 查询可用工作空间列表

- **端点**：`GET /api/v1/openapi/workspaces`
- **Scope**：`kb:read`
- **用途**：`setup.sh` 初始化向导调用，发现当前凭证及用户有权限访问的工作空间。
- **响应示例**（兼容顶层数组或 `{ "data": [...] }`）：

```json
[
  {
    "id": 1,
    "name": "核心研发空间",
    "description": "后端架构设计与领域模型规范",
    "totalDocuments": 42,
    "totalChunks": 1560
  }
]
```

---

### 2.2 知识库语义切片检索 (纯检索 / 无大模型损耗)

- **端点**：
  - `POST /api/v1/openapi/knowledge/search`（**推荐方式**：支持长 Query、避免 URL 泄露）
  - `GET /api/v1/openapi/knowledge/search`（联调支持）
- **Scope**：`kb:search`
- **请求头**：`X-Call-Source: CLI_SKILL`
- **POST 请求体**：

```json
{
  "q": "文档审批流程与状态机流转规则",
  "workspaceId": 1,
  "topK": 5,
  "minScore": 0.5
}
```

| 参数 | 类型 | 必填 | 默认值 | 说明 |
|---|---|:---:|:---:|---|
| `q` | string | 是 | — | 自然语言检索 Query，不可为空白 |
| `workspaceId` | integer | 否 | 项目绑定空间 | 目标工作空间 ID（未传则自动解析凭证默认空间） |
| `topK` | integer | 否 | 5 | 召回切片数量上限，脚本限制范围 1–20 |
| `minScore` | number | 否 | 0.50 | 相似度截断阈值 |

- **响应数据格式**：

```json
{
  "query": "文档审批流程与状态机流转规则",
  "workspaceId": 1,
  "totalMatches": 8,
  "results": [
    {
      "chunkId": "chunk_0192a8e1",
      "documentId": 88,
      "documentTitle": "文档生命周期与审批规范",
      "categoryName": "技术规范",
      "content": "文档进入提交状态后，根据空间是否开启细粒度审批路由至对应审核人...",
      "score": 0.935,
      "chunkIndex": 2,
      "pageNumber": 3,
      "deepDocCoordinates": "[{\"x\":100,\"y\":200,\"w\":400,\"h\":150}]"
    }
  ],
  "traceId": "b182cb2018a34b22"
}
```

> **字段说明**：
> - `categoryName`：文档所属分类名称（如“技术规范”、“产品需求”），用于识别规则性质；
> - `score`：多路召回与 Cross-Encoder 重排后的相关度排序分，非事实正确率；
> - `deepDocCoordinates`：DeepDoc 视觉原件版面矩形坐标，用于视觉高保真定位；
> - `traceId`：全链路检索观测追踪 ID。

---

### 2.3 获取单篇长文档完整正文

- **端点**：`GET /api/v1/openapi/documents/{id}`
- **Scope**：`doc:read`
- **用途**：当切片包含不完整条款、表格表头截断、状态机上下文缺失或代码与切片冲突时，调用 `scripts/get_document.sh <id>` 通读完整正文。
- **响应数据格式**：

```json
{
  "id": 88,
  "title": "文档生命周期与审批规范",
  "workspaceId": 1,
  "categoryName": "技术规范",
  "status": "APPROVED",
  "version": 3,
  "content": "# 文档生命周期与审批规范\n\n## 1. 概述\n...",
  "createTime": "2026-10-20 10:30:00",
  "updateTime": "2026-11-02 16:45:00"
}
```

> **字段说明**：
> - `status`：文档审批与生效状态（`DRAFT` 草稿 / `PENDING_APPROVAL` 审批中 / `APPROVED` 已发布 / `REJECTED` 驳回 / `ARCHIVED` 已归档）；
> - `version`：文档版本号；
> - `updateTime`：最近更新时间，可与本地代码 Git 提交时间对比识别文档滞后。

---

### 2.4 获取服务端实时 API 文档

- **端点**：`GET /api/v1/openapi/docs`
- **返回类型**：`text/markdown;charset=UTF-8`
- **用途**：获取后端当前部署版本内置的 Markdown 接口文档。

---

### 2.5 大模型统一对话与知识库问答入口 (可选扩展)

- **端点**：
  - `POST /api/v1/openapi/chat/completions`（OpenAI 兼容统一入口，按 `stream` 分派）
  - `POST /api/v1/openapi/chat`（同步问答快捷入口）
  - `POST /api/v1/openapi/chat/stream`（SSE 流式通道）
- **Scope**：`chat:completion`
- **请求体关键字段**：

```json
{
  "message": "总结一下文档审批流程",
  "workspaceId": 1,
  "sessionId": "session-001",
  "stream": false,
  "enableThinking": false,
  "documentId": 88,
  "topK": 5
}
```

---

## 3. 错误状态码与排查建议

| HTTP 状态码 | 业务语义 | 客户端排查与处理建议 |
|:---:|---|---|
| `200` | 请求成功 | 正常解析并结构化呈现 |
| `400` | 参数错误 | 检查 `q`（非空）、`workspaceId`（正整数）、`topK`（1–20）、`id` 是否有效 |
| `401` | 凭证无效/缺失 | 检查 `LEXILOOM_API_KEY` 环境变量或 `.lexiloom.env` 是否配置有效 Key |
| `403` | 权限不足 | 查看响应体 `detail`：检查 API Key 拥有的 scopes、空间白名单与文档阅读权限 |
| `404` | 资源未找到 | 文档不存在，或服务端未挂载该路由（确认服务端部署了 `/api/v1/openapi/*`） |
| `429` | 触发限流 | 短期内请求过多，稍后重试 |
| `5xx` | 服务端内部异常 | 检查服务端运行状态及日志，记录 `traceId` 供后端排查 |
| 网络连接超时 | 连接或响应超时 | 检查 `serverUrl` 网络连通性（默认连接超时 5s，总超时 20–30s） |
