# Knowledge Agent 接入说明（仅实验主持人使用）

本文件不作为 Agent 的额外提示词，不要复制给 Agent。

1. 将 `knowledge-base/` 全部文档导入 LexiLoom，保留目录层级或文档元数据。
2. 确保 Agent 只能通过已安装的 LexiLoom Skills 搜索/读取知识，不能直接访问本目录。
3. 在 Agent B 的环境中安装并配置 LexiLoom Skills。
4. Agent B 仍然只接收 `COMMON_TASK_PROMPT.md` 的内容。
5. Agent A 不安装 Skills，也不获得知识库目录访问权限。
6. 不要把知识库文档摘要预先写进 Agent B 的 system prompt。
7. 记录 Agent B 实际发起的检索查询、命中文档、引用片段、工具耗时和 Token。
