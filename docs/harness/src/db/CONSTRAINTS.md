# 数据库与存储硬约束

本文档用于约束 EchoMind 的持久化设计。核心原则是：可审计业务配置和普通聊天历史进 MySQL，LLM 短期上下文和向量检索进 Redis Stack，运行时 Map 只做索引。用户长期画像和知识库向量由 Redis Stack 承担事实和检索来源。

## 存储分工

| 数据 | 事实来源 | 运行时或缓存 |
|---|---|---|
| Agent 配置 | MySQL `echomind_agents` | `AgentFactory` |
| Skill 市场状态 | MySQL `echomind_skills` + Skill JAR | `SkillRegistry`、`CapabilityRegistry` |
| 会话列表 | MySQL `echomind_chat_sessions` | 前端 Pinia store |
| 会话消息 | MySQL `echomind_chat_messages`，仅用于前端展示/审计 | Redis 最近 100 条是 LLM 上下文来源 |
| 会话向量 | Redis Stack 向量索引 | 后台异步写入，主链路不等待 |
| 用户长期画像 | Redis Stack `idx:user:memory:vectors` | 主应用 Pipeline 按 `sessionId` KNN 召回 |
| Agent 知识库文档 | MySQL 文档表 + 对象存储文件，切片正文/元数据进 MySQL | Redis Stack 知识库向量索引 |
| 上传文件 | 阿里云 OSS 或本地对象目录 | 数据库只保存引用信息 |
| 外部 MCP 运行状态 | 配置文件或后续 MySQL 配置表 | `ExternalMcpRuntimeService` 子进程和工具 provider |
| Team 定义和执行记录 | MySQL `echomind_agent_teams` / `members` / `runs` / `steps` / `events` | `TaskExecutor` 运行中任务 |

## 禁止事项

- 禁止把业务事实只放在 `ConcurrentHashMap`、静态变量或单例 Bean 内。
- 禁止 LLM prompt 从 MySQL 读取聊天历史；LLM 只读 Redis 最近窗口。
- 禁止把 Redis List 当完整会话历史展示来源。
- 禁止使用 Redis `KEYS *` 做生产查询；应使用明确索引、集合或 `SCAN`。
- 禁止每次追加消息都全量读出、全量序列化、全量写回。
- 禁止前端状态替代后端事实来源。
- 禁止上传文件只存临时路径而不记录对象存储引用。
- 禁止删除 Agent 时静默丢失相关知识库和历史关联；必须明确软删、硬删或级联策略。

## MySQL 负责什么

MySQL 保存可以审计、可以恢复、不能因为重启丢失的数据：

- Agent 定义。
- Skill 启用状态和市场元数据。
- 完整对话会话和消息，供前端历史展示和审计。
- 会话摘要。
- Agent 知识库文档元数据、切片正文和切片元数据。
- 后续 Agent Team 的 Team、Member、Run、Step。

写入顺序建议：

1. 先校验请求。
2. 先写 MySQL。
3. 写成功后刷新运行时索引。
4. 运行时刷新失败时，要返回明确错误，并尽量保留可重试路径。

## Redis Stack 负责什么

Redis Stack 负责速度和向量检索；用户长期画像和知识库向量由 Redis Stack 承担事实来源：

- 最近 100 条上下文热缓存，是 LLM prompt 的聊天历史来源。
- 会话语义召回向量索引，后台异步写入。
- Agent 知识库向量索引，KNN 检索只走 Redis Stack。
- 用户长期画像条目和画像向量，按 `sessionId` 隔离。
- 可以重建的临时检索结构。

Redis 里的数据必须满足：

- 普通聊天 Redis 窗口丢失后不阻塞 LLM；可由后台任务从 MySQL 预热或随新对话重新累积。
- 用户长期画像丢失后不从 MySQL 恢复，只能由后续对话重新沉淀。
- 查询不能依赖全量 `KEYS`。
- 读取上下文时按窗口、topK、token 或字符预算裁剪。

## 对话记忆策略

当前记忆按一次对话隔离：

```text
memoryKey = sessionId 优先
```

每次聊天进入提示词的内容应该是：

- 当前 Agent 的 system prompt。
- 当前用户消息。
- Redis 最近窗口内的短期历史。
- 按 `sessionId` 召回的用户长期画像。
- Agent 知识库召回片段。

不要从 MySQL 全量或回源拼接历史。`prompt-max-chars`、`prompt-max-history-message-chars` 等配置必须生效。

## 知识库约束

Agent 知识库支持 txt、pdf 等文件上传后切片和向量化。

约束：

- 原文件进入 OSS 或本地对象存储。
- 文档元数据进 MySQL。
- 切片文本和切片元数据进 MySQL。
- 向量只进入 Redis Stack 知识库索引，不再写 MySQL embedding 备份，也不使用 MySQL 线性向量兜底。
- 删除文档时，必须删除或标记删除对应切片和向量。
- 修改文档时，不要在旧向量上追加新版本；应明确版本、重建或清理旧切片。

## Agent Team 持久化方向

Team 协作不能长期只放内存。建议表结构方向：

- `echomind_agent_teams`：团队定义。
- `echomind_agent_team_members`：成员 Agent、角色、能力标签、排序。
- `echomind_agent_team_runs`：一次任务执行，也是 Team 黑板首页；澄清暂停时记录 `clarification_stage`，用于 resume 回到规划审查或结果审查阶段。
- `echomind_agent_team_steps`：Planner 拆出的子任务、分配 Agent、状态、输入、原始输出、重试意见。
- `echomind_agent_team_events`：协作事件流，前端时间线和 Mermaid 从这里恢复。

Team 黑板是角色记忆互通的事实来源。Planner、Executor、Reviewer 不依赖同一个聊天 session 互相“碰上下文”，而是由后端从 Run / Step / Event 组装最小必要上下文。
Team 内部调用不能落入 `echomind_chat_sessions` / `echomind_chat_messages`；普通会话历史只保存用户在 Chat 页面发起的真实对话。
删除 Team 是硬删除：必须同时删除 members、runs、steps、events，不保留逻辑删除标记。

Team 执行记录至少要支持：

- 重启后查询历史。
- 前端展示每一步状态。
- 某一步失败后定位原因。
- 后续支持重试、取消和 SSE 事件恢复。

## 数据变更检查表

改数据库相关代码前检查：

- 新数据是否必须重启后保留；如果是，必须进 MySQL。
- 是否只是可重建索引；如果是，可以进 Redis Stack。
- 是否需要 OSS 保存原文件。
- 是否需要软删。
- 是否需要唯一约束。
- 是否需要分页，避免一次性拉全量。
- 是否需要按 `sessionId`、`agentId`、`teamId`、`runId` 建查询路径。
- 是否会影响已有数据迁移和启动恢复。
