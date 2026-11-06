# KASE-Bench MVP：部分退款业务开发对照实验

版本：1.0  
目标：比较同一 Coding Agent 在「仅代码 + 任务提示词」与「代码 + 同一提示词 + LexiLoom Knowledge Skills」两种条件下的真实开发表现。

## 目录

- `starter-repo/`：两组 Agent 使用的同一份 Java 工程起点。
- `knowledge-base/`：仅 Knowledge 组导入知识库。包含产品背景、UE、需求、API、详细设计、ADR。
- `agent-prompts/`：两组必须使用同一份提示词。
- `evaluation/evaluator-only/`：验收规则、隐藏测试设计和评分标准。**不得提供给任何 Agent**。
- `evaluation/run-records/`：实验记录模板。

## 实验主题

电商订单部分退款：在现有订单与支付代码基础上，增加支持多次部分退款、退款金额累计校验、幂等、并发保护、原接口兼容和支付渠道信息留痕的退款能力。

## 重要：公平性

1. 两组从相同 Git commit / ZIP 解压状态开始，分别使用独立工作目录。
2. 模型、版本、推理强度、工具权限、最大运行时长、最大 Token、测试命令保持一致。
3. Agent A 不导入 `knowledge-base/`，也不得通过其他途径读取这些文档。
4. Agent B 将 `knowledge-base/` 导入 LexiLoom，并通过 Skills 检索；不要把文档全文直接粘进任务提示词。
5. 两组使用 `agent-prompts/COMMON_TASK_PROMPT.md`，除是否接入 Skills 外不改变提示词。
6. `evaluation/evaluator-only/` 由实验主持人保管，完成后才运行隐藏验收。
7. 两组不能共享工作区、对话历史、Agent memory 或 Git 分支。
8. 建议每组至少重复 3 次；任务顺序、模型随机性可能影响结果。

## 运行

工程使用 Java 17 + Maven，无需数据库或外部服务。持久化层使用内存实现，便于可重复测试。

```bash
cd starter-repo
mvn test
mvn spring-boot:run
```

API 基础路径：`/api/v1`

## 建议实验流程

1. 记录两组 Agent 和模型配置。
2. A、B 分别从同一基线开始执行提示词。
3. 记录 Agent 是否主动澄清、是否检索文档、修改文件、执行测试、最终解释。
4. 冻结提交，记录 Git diff 和 commit hash。
5. 由主持人运行隐藏验收并按评分表评分。
6. 汇总功能正确性、业务规则遵循、兼容性、质量、成本和耗时。

## 本数据集的边界

这是一个可运行的 MVP 基准，不是完整生产级支付系统。支付渠道通过模拟网关表达；不涉及真实资金流、退款异步回调、分布式消息、数据库事务隔离或真实分布式锁。文档中会明确哪些约束必须实现、哪些属于范围外。
