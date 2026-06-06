# 数据库与存储硬约束

本文档用于约束 EchoMind 的持久化设计。核心原则是：可审计业务配置和普通聊天历史进 MySQL，LLM 短期上下文和用户画像快照进 Redis，用户长期事实和知识库向量进 Milvus，运行时 Map 只做索引。用户画像快照是 Redis 中的派生摘要，不替代事实层。

## 存储分工

| 数据 | 事实来源 | 运行时或缓存 |
|---|---|---|
| Agent 配置 | MySQL `echomind_agents` | `AgentFactory` |
| Skill 市场状态 | MySQL `echomind_skills` + Skill JAR | `SkillRegistry`、`CapabilityRegistry` |
| 会话列表 | MySQL `echomind_chat_sessions`，按 `user_id + session_id` 隔离 | 前端 Pinia store |
| 会话消息 | MySQL `echomind_chat_messages`，按 `user_id + session_id` 保存，仅用于前端展示/审计 | Redis 字符预算短期缓存是 LLM 上下文来源 |
| 用户账号 | MySQL `echomind_users`，含用户名、密码哈希、状态和 `avatar_uri` | 头像二进制在 OSS / 本地对象存储 |
| 管理端账号 | MySQL `echomind_admin_users`，独立于客户端账号 | 独立 admin JWT，不进入客户端用户统计 |
| AI 调用用量 | MySQL `echomind_ai_call_usage`，按客户端 `user_id` 记录 TraceID、模型、token、延迟和状态 | 管理端仪表盘和 Trace 跳转 |
| 用户 Token 配额 | MySQL `echomind_token_quotas` 保存用户日/月限额配置；`echomind_token_quota_usage` 按 `user_id + scope + bucket_start` 保存真实已用 token | Redis `echomind:quota:*` 保存当前 bucket 的 used 热镜像和 in-flight reserved 冻结额度 |
| Provider Token 预算 | MySQL `echomind_provider_token_budgets` 保存 Provider 日/周/月预算配置；`echomind_provider_token_budget_usage` 按 `provider_id + scope + bucket_start` 保存真实已用 token | Redis `echomind:provider-budget:*` 保存当前 bucket 的 used 热镜像和 in-flight reserved 冻结额度 |
| 敏感数据治理 | MySQL `echomind_sensitive_rules` 保存规则，`echomind_sensitive_events` 保存脱敏后事件样本；请求侧 `BLOCK` 样本保存替代词结果 | Redis `echomind:sensitive:rules:*` 只做规则热缓存；`ChatApplicationService` 请求/响应治理钩子 |
| 告警治理 | MySQL `echomind_alert_rules` 保存阈值、静默期和升级策略，`echomind_alert_events` 保存推送结果、升级标记和飞书响应摘要 | 飞书 Webhook 客户端，不作为事实来源；地址来自后端运行环境变量 `Webhook` |
| 用户长期记忆 | Milvus `echomind_user_memory_spring_ai_v1` 按 `userId` 保存细粒度事实；Redis `echomind:user-profile:snapshot:*` 保存画像快照 | Spring AI VectorStore 负责 embedding 和中心向量召回；旧 collection 保留但不再读取 |
| Agent 知识库文档 | MySQL 文档表 + 对象存储文件 | Milvus `echomind_agent_knowledge_spring_ai_v1` 保存切片正文、切片元数据和知识库向量索引 |
| 上传文件 | 阿里云 OSS 或本地对象目录 | 数据库只保存引用信息 |
| 外部 MCP 运行状态 | 配置文件或后续 MySQL 配置表 | `ExternalMcpRuntimeService` 持有的 Spring AI MCP client 和工具 provider |
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
- 客户端用户账号和管理端用户账号，但两者必须分表、分 JWT、分接口。
- AI 调用用量审计，包含 `trace_id`、客户端 `user_id`、`operation`、模型、prompt/completion/total token、耗时、状态和错误信息。
- 用户 Token 配额配置和日/月真实已用账本；账本行按 `user_id + scope + bucket_start` 唯一，不能只从审计表聚合判断 quota。
- Provider Token 预算配置和日/周/月真实已用账本；迁移会从 `echomind_ai_call_usage` 回填已有 provider bucket 用量。
- 敏感数据规则、脱敏事件、告警规则和告警事件。敏感事件只能保存脱敏后的样本或请求侧 `BLOCK` 替代词结果，不允许落原始手机号、身份证、邮箱、银行卡等命中文本。
- Agent 知识库文档元数据和原文件引用。
- 后续 Agent Team 的 Team、Member、Run、Step。

管理端用户管理只能操作客户端用户表 `echomind_users`。封禁账号只改 `status`；删除账号是硬删除该客户端用户的
账号、普通聊天会话、消息、AI 调用用量、Token 配额配置、Token 配额账本和旧 MySQL 记忆嵌入，并尽力清理 Redis 近期上下文、
用户画像快照、用户长期事实向量。删除客户端用户不能级联删除全局 Agent、Skill、MCP、Team 或管理端账号。

写入顺序建议：

1. 先校验请求。
2. 先写 MySQL。
3. 写成功后刷新运行时索引。
4. 运行时刷新失败时，要返回明确错误，并尽量保留可重试路径。

## Redis 和 Milvus 负责什么

Redis 和 Milvus 负责速度、短期上下文和向量检索；用户长期事实和知识库向量由 Milvus 承担事实来源：

- 单会话短期上下文热缓存，按总字数预算和最大条数双重裁剪，是 LLM prompt 的聊天历史来源。
- 用户 quota 和 Provider budget 的当前 bucket used 热镜像与 in-flight reserved 冻结额度；预留、释放和结算必须用 Redis Lua 原子执行，Redis 不可用时新 LLM 调用失败关闭，避免预算穿透。
- Agent 知识库切片正文、切片元数据和向量索引，中心 KNN 检索走 Spring AI VectorStore，窗口扩展只用小型 Milvus native adapter 查询同文档前后切片。
- 用户长期事实向量，按 `userId` 全局隔离，事实带 `firstObservedAt`、`lastObservedAt` 和 `updatedAt`；召回和合并旧事实必须同时过滤事实置信度和 Milvus COSINE 向量相似度，不能只取无阈值 TopK。
- 旧手写 Milvus collection 保留但不再读取；新数据写入 Spring AI collection，需要重新索引旧知识库文件和重新沉淀用户长期事实。
- 用户画像快照，存 Redis Hash，是长期事实的固定长度压缩摘要。
- 可以重建的临时检索结构。

Redis 里的数据必须满足：

- 普通聊天 Redis 窗口丢失后不阻塞 LLM；不从 MySQL 回源补历史，只随新对话重新累积。
- 用户长期事实和画像快照丢失后不从 MySQL 恢复，只能由后续对话重新沉淀。
- 查询不能依赖全量 `KEYS`。
- 读取上下文时按窗口、topK、token 或字符预算裁剪。

## 对话记忆策略

当前普通聊天记忆按登录用户和一次对话隔离：

```text
memoryKey = userId + ":" + sessionId
MySQL session key = user_id + session_id
```

无 token 的兼容请求和历史无用户数据统一归属 `default` 用户。第一阶段只隔离普通聊天会话和记忆；
Agent、Skill、MCP、Team 仍是全局资源，不在本阶段引入 RBAC、租户或应用 API Key。

客户端用户和管理端用户均使用 HS256 JWT access token，但必须使用不同 secret、不同 token 类型和不同
ThreadLocal 上下文；普通请求进入 `AuthContext`，管理端请求进入 `AdminContext`，请求结束必须清理。
管理端账号不属于客户端用户，不允许写入 `echomind_users`，也不允许出现在用户用量列表中。
管理端登录、刷新 Trace 页面和查询 usage 都不能写入 `echomind_ai_call_usage`；该表只记录客户端发起的
AI 模型调用。`echomind_ai_call_usage.trace_id` 必须能关联到导出到 Jaeger 的 Trace，
`echomind.user_id` Span tag 必须和该表的 `user_id` 一致，方便按用户查询链路。
`prompt_tokens`、`completion_tokens` 和 `total_tokens` 必须来自模型服务原生 usage，
`usage_source` 固定为 `PROVIDER`；模型未返回原生 usage 时不允许写入本地预估 Token。
管理端仪表盘的请求量、Token、平均响应、模型分布、趋势和最近调用都只能从该表的真实记录聚合；
项目没有落库的成本、余额、API 密钥消费等数据不能在管理端展示为真实指标。
用户日/月 quota 和 Provider 日/周/月 budget 都会在调用前用 Redis 预留固定额度：
普通聊天在 `ChatApplicationService.submitAsync` 入队前预留用户 quota，Team 内部 LLM 调用通过
`TeamUsageRecorder` 预留用户 quota，Provider budget 在 `ProviderTokenBudgetGuardStage` 模型解析后预留。
模型返回真实 provider usage 后，`AiCallUsageService` 先保留 `echomind_ai_call_usage` 审计记录，再用
`echomind_token_quota_usage` 和 `echomind_provider_token_budget_usage` 在事务内结算真实已用；
事务提交后才把 Redis reserved 转入 used，事务回滚或模型未返回原生 usage 时释放 reserved。
后置结算不拒绝已经完成的模型调用；若本次真实 usage 使 bucket 超过限额，仍保留并结算，
下一次请求前预留会返回用户配额或 Provider 预算错误。
项目三 AI Infra 是当前 Agent 项目的管理端，不新增独立网关或应用 API Key；配额仍按客户端用户维度执行。
脱敏/告警属于管理端治理事实，必须落 MySQL，不能只放内存 Map；请求侧命中 `BLOCK` 会阻止进入 Agent/RabbitMQ 管线并返回替代词拼接结果，响应侧命中 `BLOCK` 仍走阻断异常。飞书自定义机器人 Webhook 只负责通知，不是告警事实来源。Webhook 地址只来自后端运行环境变量 `Webhook`，不在管理端前端配置规则级 Webhook。
敏感规则可以进入 Redis 热缓存来减少聊天治理链路反复查库，但 MySQL 仍是事实来源；规则写入成功后必须删除对应 Redis 缓存，让下一次读取回源刷新。

每次聊天进入提示词的内容应该是：

- 当前 Agent 的 system prompt。
- 当前用户消息。
- Redis 单会话短期历史，按字符预算和最大条数裁剪。
- 按 `userId` 读取的用户画像快照。
- 按 `userId` 从 Spring AI Milvus VectorStore 召回的相关用户长期事实。
- Agent 知识库召回片段。

不要从 MySQL 全量或回源拼接历史。`prompt-max-chars`、`prompt-max-history-message-chars` 等配置必须生效。

## 知识库约束

Agent 知识库支持 txt、pdf 等文件上传后切片和向量化。

约束：

- 原文件进入 OSS 或本地对象存储。
- 文档元数据进 MySQL，包括可空 `object_uri` 和 `content_type`；旧文档允许没有原文件对象。
- 切片文本、切片元数据和向量进入 Spring AI Milvus 知识库索引，不再进入 MySQL 分片表。
- 检索只走 Milvus：先由 Spring AI VectorStore 向量命中中心片段，再用 native query 扩展同文档前后窗口，不使用 MySQL 关键词或线性向量兜底。
- 删除文档时，必须删除或标记删除对应切片、向量，并尽力删除原文件对象。
- 修改文档时，不要在旧向量上追加新版本；应明确版本、重建或清理旧切片。

## Agent Team 持久化方向

Team 协作不能长期只放内存。当前 Team v2 表结构方向：

- `echomind_agent_teams`：团队定义，全局资源。
- `echomind_agent_team_members`：成员 Agent、角色、能力标签、排序；可配置 `PLANNER`、多个 `EXECUTOR`、必选 `REVIEWER`，可选 `SUB_REVIEWER` / `MERGER`。
- `echomind_agent_team_runs`：一次任务执行，也是 Team 黑板首页；按 `user_id` 隔离；保存 `task_level`、`merge_output`、`global_review_json`、`conflict_report_json`、`arbitration_json`、最终报告、澄清字段、规划重试、局部重规划、整体重规划和仲裁次数。
- `echomind_agent_team_steps`：Planner 拆出的 DAG 子任务、`client_step_id`、依赖 Step、风险等级、质量状态、分配 Agent、输入、原始输出、SubReviewer 审查、Reflexion 重试上下文。
- `echomind_agent_team_events`：协作事件流，前端时间线和 Mermaid 从这里恢复。

Team 黑板是角色记忆互通的事实来源。Planner、Executor、Reviewer 不依赖同一个聊天 session 互相“碰上下文”，而是由后端从 Run / Step / Event 组装最小必要上下文。
Team 内部调用不能落入 `echomind_chat_sessions` / `echomind_chat_messages`；普通会话历史只保存用户在 Chat 页面发起的真实对话。
删除 Team 是硬删除：必须同时删除 members、runs、steps、events，不保留逻辑删除标记。

Team v2 迁移 `20260521_team_v2_dag_reflexion.sql` 会清空旧 Team 数据，避免旧消息总线/旧 Step 格式混入新 DAG 状态机。后续数据迁移如果要保留历史，需要先写显式升级脚本，不要让运行时代码兼容过多旧结构。

Team 执行记录至少要支持：

- 重启后查询历史。
- 按当前用户隔离查询 Team Run，且前端与普通聊天历史分开展示。
- 前端展示每一步状态。
- 前端展示 DAG 依赖、AgentSelector 选择理由、RiskPolicy 裁决、冲突报告、Planner 仲裁、风险、质量、Reviewer 审查、Reflexion 和最终 Markdown 下载。
- 某一步失败后定位原因。
- 后续支持取消、单 Step 手动重试和 SSE 事件恢复。

## 数据变更检查表

改数据库相关代码前检查：

- 新数据是否必须重启后保留；如果是，必须进 MySQL。
- 是否只是可重建索引；如果是，可以进 Milvus。
- 是否需要 OSS 保存原文件。
- 是否需要软删。
- 是否需要唯一约束。
- 是否需要分页，避免一次性拉全量。
- 是否需要按 `userId + sessionId`、`agentId`、`teamId`、`runId` 建查询路径。
- 是否会影响已有数据迁移和启动恢复。
- 如果新增或修改 MySQL 表结构，必须补 `docker/mysql/migrations/*.sql`，并通过
  `scripts/apply-mysql-migrations.ps1 -StartDatabase` 或 harness `make migrate` 验证旧 volume 可升级。
