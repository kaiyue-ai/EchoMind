# API 层架构决策

本文档描述 EchoMind API 层的职责边界、调用链路和新增接口时必须遵守的规则。真实代码主要位于：

- `echomind-console/src/main/java/com/echomind/console/controller/rest`
- `echomind-console/src/main/java/com/echomind/console/service`
- `echomind-console/src/main/java/com/echomind/console/dto`

## 分层边界

```text
Controller
  -> Application Service
  -> Runtime / Domain Service
  -> Repository / Store / Provider
```

Controller 只负责 HTTP 适配：

- 接收路径参数、查询参数、请求体和文件上传。
- 做轻量格式校验。
- 调用一个明确的 Application Service 方法。
- 返回 DTO、SSE 或文件上传结果。

Controller 不应该：

- 直接拼 Agent 执行链路。
- 直接操作 Repository。
- 直接改运行时注册表。
- 直接调用 LLM Provider、Skill 或 MCP Client。

Application Service 负责用例编排：

- 参数语义校验。
- 事务顺序。
- 持久化和运行时同步。
- 把底层异常转换成前端能理解的业务错误。

Runtime / Domain Service 负责真正的执行能力：

- `AgentOrchestrator`
- `ExecutionPipeline`
- `CapabilityRegistry`
- `SkillCapabilityService`
- `ExternalMcpRuntimeService`
- `MemoryManager`
- `AgentKnowledgeService`

## 当前 Controller 入口

| Controller | 责任 |
|---|---|
| `AuthController` | 登录、注册、登出占位、当前用户查询和用户头像上传 |
| `AdminAuthController` | 管理端登录、当前管理端用户查询；使用独立管理端账号体系 |
| `AdminDashboardController` | 管理端真实仪表盘数据，聚合请求量、Token、响应耗时、模型分布和趋势 |
| `AdminClientUserController` | 管理端查询客户端用户，封禁、解封和硬删除客户端用户数据 |
| `AdminSensitiveController` | 管理端配置敏感数据脱敏/阻断规则，并查询脱敏事件 |
| `AdminAlertController` | 管理端配置告警规则、静默期和升级策略，展示后端 `Webhook` 生效状态，并查询告警事件 |
| `AdminUsageController` | 管理端 Token 用量总览、客户端用户列表和单用户调用明细 |
| `AgentController` | Agent 创建、更新、删除、执行和知识库管理入口 |
| `ChatController` | 同步聊天、异步聊天、流式聊天、图片上传聊天 |
| `SkillController` | Skill 列表、上传、启停、删除 |
| `MCPController` | 外部 MCP Server 挂载、卸载、刷新和工具直调 |
| `MemoryController` | 会话历史、记忆查询、记忆删除 |
| `ModelController` | 模型列表、默认模型切换 |
| `ObservabilityController` | Trace 配置、Jaeger Trace 搜索和按 TraceID 查询 Span |
| `StorageController` | 对象上传，当前支持本地和阿里云 OSS |
| `TeamController` | Agent Team 创建、执行和协作状态查询 |

## 当前 Application Service

| Service | 责任 |
|---|---|
| `AuthApplicationService` | 用户登录、注册、默认用户初始化、token 签发和头像对象存储写入 |
| `AdminAuthApplicationService` | 管理端默认账号初始化、登录校验和 admin token 签发 |
| `AdminDashboardService` | 从真实用量表和客户端用户表聚合管理端仪表盘，不生成成本、余额等项目没有的数据 |
| `ClientUserAdminService` | 管理端客户端用户列表、账号状态更新和用户数据硬删除编排 |
| `AgentApplicationService` | Agent 配置校验、MySQL 持久化、运行时 AgentFactory 同步 |
| `AgentKnowledgeApplicationService` | Agent 知识库 HTTP 上传校验、文件参数整理和 Memory 模块调用 |
| `ChatApplicationService` | 保留同步、异步提交、队列流式消费和会话删除的用例入口；请求归一化、Agent 执行适配、队列流式消费和会话附件清理由同包 helper 承接 |
| `ChatGovernanceService` | 收口聊天请求/响应治理：Token 配额、敏感数据、调用用量、配额预警和调用错误告警 |
| `AiCallUsageService` | 记录每次客户端 AI 调用的 TraceID、用户、模型、延迟、状态和 token |
| `SensitiveDataService` | 在聊天请求进入 Agent 前、响应返回前做敏感数据脱敏/阻断，并记录脱敏后事件 |
| `AlertService` | 根据调用错误、错误率、Token 阈值和敏感数据事件生成告警，按静默期推送飞书自定义机器人 Webhook，并在静默累计达阈值时发送升级告警 |
| `UsageQueryService` | 管理端查询所有用户总 token、单用户总 token 和单次调用明细 |
| `SkillApplicationService` | Skill 上传、启停、删除，并同步能力注册表 |
| `McpApplicationService` | 外部 MCP 服务挂载、卸载、刷新和工具调用 |
| `MemoryApplicationService` | 会话摘要、聊天历史、附件展示 URL 刷新和会话维度记忆删除 |
| `ModelApplicationService` | 模型列表和默认模型切换 |
| `JaegerTraceClient` | 后端代理 Jaeger Query，给前端返回归一化 Trace / Span DTO |
| `StorageApplicationService` | 文件上传、OSS 或本地存储策略选择 |
| `TeamApplicationService` | Team 创建、执行和消息状态查询 |

所有普通聊天和记忆接口都以后端认证上下文中的用户为准。客户端登录后拿到 HS256 JWT access token，
请求用 `Authorization: Bearer ...` 传递；`AuthFilter` 校验 JWT、确认账号仍启用后写入
`AuthContext` ThreadLocal，并在请求结束时清理。无 token 时归属兼容 `default` 用户；前端不提交可信
`userId`。第一阶段只隔离普通聊天会话和记忆，Agent、Skill、MCP、Team 仍是全局资源。

客户端用户和管理端用户必须隔离：客户端使用 `/api/auth/*`、`echomind_users` 和普通用户 JWT；
管理端使用 `/api/admin/auth/*`、`echomind_admin_users` 和独立 admin JWT。`AuthFilter` 不处理
`/api/admin/*` 与 `/api/observability/*`，这些后台接口由管理端认证链路保护，并写入独立
`AdminContext` ThreadLocal。管理端用户不能混入客户端用户列表、Token 统计或聊天归属。

管理端用户管理接口只操作客户端账号：

```text
GET /api/admin/dashboard?range=7d
GET /api/admin/client-users
PUT /api/admin/client-users/{userId}/status
DELETE /api/admin/client-users/{userId}
```

`/api/admin/dashboard` 只从 `echomind_ai_call_usage` 中 `usage_source=PROVIDER` 的真实客户端调用
和 `echomind_users` 聚合数据，展示今日请求、今日 Token、范围 Token、累计 Token、客户端用户数、
平均响应、模型 Token 分布、Token 日趋势和最近调用。项目没有成本、余额、API 密钥消费等事实数据，
管理端不能伪造或预估这些指标。

封禁/解封只修改 `echomind_users.status`，禁用账号不能继续登录或使用已有 token 访问聊天接口。
删除客户端用户是硬删除：清理该用户账号、普通聊天会话、消息、AI 调用用量、Token 配额、
旧 MySQL 记忆嵌入，以及 Redis 近期上下文、用户画像、用户长期事实向量缓存。删除不影响
全局 Agent、Skill、MCP、Team 和管理端账号。

`POST /api/auth/avatar` 只允许已登录用户上传头像，图片大小上限 2MB。文件写入统一
`ObjectStorageService`，生产环境配置完整时进入 OSS；MySQL 只保存稳定 `avatar_uri`，
接口响应再生成展示 URL。

项目三 AI Infra 只作为现有 Agent 项目的管理端，不新增独立网关、不新增 OpenAI `/v1` 兼容入口、
不引入应用 API Key。治理能力挂在现有 `/api/chat/*` 链路：调用前校验用户级 Token 配额并对请求做脱敏/阻断，
模型响应后再做响应脱敏/阻断、用量落库和告警触发。敏感数据事件只保存脱敏后的样本，不落原始命中文本。
这些治理流程集中在 `ChatGovernanceService`。`ChatApplicationService` 只负责用例入口和调用顺序；
`ChatRequestNormalizer` 负责 REST 请求归一化，`AgentChatExecutor` 负责调用 `AgentOrchestrator`
并保留原始用户消息，`QueuedChatStreamExecutor` 负责 RabbitMQ 队列流式消费和 SSE token 事件，
`ChatSessionCleanupService` 负责删除会话时的附件对象清理。

## 聊天接口链路

同步聊天：

```text
POST /api/chat/sync
  -> ChatController
  -> ChatApplicationService.executeSync
  -> AgentOrchestrator.execute(userId, ...)
  -> Agent.chat
  -> ExecutionPipeline
  -> ContextEnrichStage
  -> UserMemoryRetrievalStage
  -> KnowledgeRetrievalStage
  -> ToolResolutionStage
  -> ResultAggregationStage
  -> MemoryPersistStage
```

聊天入口已接入 OpenTelemetry：`POST /api/chat/sync` 返回 `traceId`，`POST /api/chat`
提交响应返回 `traceId`，`GET /api/chat/stream/{requestId}` 的 `meta` SSE 事件返回 `traceId`。业务 Span
覆盖 `echomind.chat.*`、`echomind.agent.*`、`echomind.pipeline.stage`、`echomind.llm.*`
和 `echomind.tool.invoke`；自动 HTTP/JDBC/Redis Span 由 OpenTelemetry Spring Boot Starter
和配置的 exporter 负责。本地 Compose 默认通过 `OTEL_TRACES_EXPORTER=otlp` 和
`OTEL_EXPORTER_OTLP_ENDPOINT=http://otel-collector:4318` 接入 OpenTelemetry Collector，
Collector 再转发到 Jaeger 查询后端。

一次真实聊天在 Jaeger 中应该能看到完整多 Span 链路：HTTP 入口、`echomind.chat.*` 业务入口、
Agent 编排、Pipeline Stage、LLM 调用、工具调用和落库 Span。聊天业务 Span 必须带
`echomind.user_id`、`echomind.account_type=client`、用户名、模型、延迟和 token tag；
`echomind_ai_call_usage.trace_id` 与 Jaeger TraceID 保持一致，用于管理端从 Token 明细跳转链路。
Token 必须来自模型服务原生 usage：OpenAI 兼容和 DeepSeek Chat Completions 都经 Spring AI
adapter 读取 provider 原生 usage，再转成 EchoMind `TokenUsage`。没有原生 usage 时不记录用量，
不能用本地预估数进入管理端仪表盘。

项目三管理端前端独立部署在 `admin-frontend` 容器，默认宿主端口 `8081`；客户端 `frontend`
容器默认宿主端口 `80`，只保留对话、Agent、Skill、MCP、Team 工作台。管理端 Trace 页面走后端代理接口：

```text
GET /api/observability/traces/config
GET /api/observability/traces?limit=20&lookback=1h&scope=business&userId=<clientUserId>
GET /api/observability/traces/{traceId}
GET /api/admin/usage/summary
GET /api/admin/usage/users
GET /api/admin/usage/users/{userId}/calls?limit=100
GET /api/admin/quotas
PUT /api/admin/quotas/users/{userId}
GET /api/admin/sensitive/rules
PUT /api/admin/sensitive/rules
GET /api/admin/sensitive/events?limit=100
GET /api/admin/alerts/rules
PUT /api/admin/alerts/rules
GET /api/admin/alerts/events?limit=100
```

`scope` 默认是 `business`，只查询 `echomind.chat.*` 业务链路，避免管理端自己的刷新、查询和健康检查
Trace 淹没真实对话链路；需要排查全部 HTTP/JDBC/Redis Span 时可传 `scope=all`。`userId`
会转换为 Jaeger tag `echomind.user_id`，用于按客户端用户查询其调用链路。

本地代理默认开启，配置为 `ECHOMIND_OBSERVABILITY_JAEGER_ENABLED=true` 和
`ECHOMIND_OBSERVABILITY_JAEGER_QUERY_URL=http://jaeger:16686`；前端不直接访问 Jaeger，以便后续统一接入权限、
租户过滤或替换 Zipkin / Tempo 查询实现。Collector + Jaeger 已在默认 `docker-compose.yml`
中声明，后端只等待 Collector 容器启动，不依赖观测组件健康状态；导出关闭期间产生的旧 TraceID 不会补写到 Jaeger。

`UserMemoryRetrievalStage` 按当前 `userId` 读取 Redis 用户画像快照，并从 Redis Stack 用户事实层召回
与本轮问题相关的长期事实；普通聊天短期上下文仍由 `ContextEnrichStage` 从 Redis 字符预算缓存读取。

`MemoryPersistStage` 只发布普通聊天记忆事件，不在主线程写 MySQL、Redis 或向量库。
事件进入 `echomind.chat-memory.persist.exchange`，事件体带 `userId`，按 `sessionId` hash 到
`echomind.chat-memory.persist.requests.shard.N`。每个分片队列只能单消费者，整体并发靠分片数扩展；
不要把单个聊天记忆分片改成多消费者，否则同一会话的历史可能乱序。
后台 `ChatMemoryPersistConsumer` 写 MySQL 完整历史和 Redis 短期上下文字数预算缓存后，发布用户记忆事件；
`echomind-user-memory` 按用户全局缓冲，满足 5 轮、字数上限、空闲超时或主模型重要性信号后，
批量更新 Redis Stack 用户事实和 Redis 用户画像快照。

异步聊天：

```text
POST /api/chat
  -> ChatController
  -> ChatApplicationService.submitAsync
  -> RabbitMQ chat.requests
  -> ChatRabbitConsumer
  -> ChatApplicationService.executeQueuedStream
  -> AgentOrchestrator.executeStreamContext
  -> Agent.chatStream
  -> RabbitMQ chat.stream-events
  -> SsePushService
  -> GET /api/chat/stream/{requestId} 推送 meta/token/result/failure
```

`POST /api/chat/stream` 直连模型流式执行已删除。前端若需要流式体验，必须先 `POST /api/chat`
入队，再订阅 `GET /api/chat/stream/{requestId}`；SSE 层只转发事件，不承担模型执行。

Provider 层通过 Spring AI ChatModel adapter 使用厂商真流式能力。OpenAI 兼容协议（包括阿里云百炼/Qwen）
和 DeepSeek 都走 Chat Completions；Provider 只负责在 Spring AI response 与 EchoMind 的
`ProviderResponse` / `ProviderStreamChunk` 之间转换。Mock Provider 仅用于开发和测试，仍是单段模拟输出。

Agent 聚合阶段不再重复解析模型：`ToolResolutionStage` 负责写入 `modelId` 和 typed
`PipelineContext.resolvedModel`；`ResultAggregationStage` 只复用该模型，委托 `PromptComposer`、
`ToolExposurePlanner` 和 `ProviderRequestFactory` 构造模型输入后调用 Provider。

删除单条会话：

```text
DELETE /api/chat/{sessionId}
  -> ChatController
  -> ChatApplicationService.deleteSession
  -> MemoryManager.getFullContext(userId, sessionId)
  -> MemoryManager.clearSession(userId, sessionId)
  -> ObjectStorageService.deleteObject (尽力回收聊天附件)
```

会话列表和聊天历史读取：

```text
GET /api/chat/sessions
GET /api/chat/{sessionId}/history
  -> ChatController
  -> MemoryApplicationService
  -> MemoryManager.getFullContext/listSessions(userId, sessionId)
  -> ObjectStorageService.urlFor (仅刷新托管附件展示 URL)
```

## 工具调用链路

本地 Skill：

```text
Skill JAR
  -> SkillDirectoryWatcher
  -> SkillRegistry
  -> SkillCapabilityService
  -> CapabilityRegistry
  -> ResultAggregationStage
  -> LLM function calling
```

外部 MCP：

```text
外部 MCP Server
  -> ExternalMcpRuntimeService
  -> StdioMCPClient
  -> CapabilityRegistry
  -> ResultAggregationStage
  -> LLM function calling
```

重要边界：

- Skill 和 MCP 最终都进入 `CapabilityRegistry`，让 Agent 看到统一工具视图。
- Skill 不应该伪装成 MCP Server。
- MCP 管理接口只管理外部 MCP Server。
- 禁用 Skill 或卸载 MCP 时，必须同步移除对应工具能力。
- `ToolRouter` 只是工具注册和匹配入口；URL/domain 兼容性放 `ToolCompatibilityPolicy`，
  关键词评分放 `ToolMatchScorer`，铁路/日期等冲突消歧放 `ToolDisambiguationPolicy`，
  用户文本到工具参数的直调兜底构造放 `ToolParameterExtractor`。
- URL 请求先走确定性工具过滤：通用网页读取/搜索交给带 `web/search/lookup` 标签的通用工具；
  域名专用工具通过 `domain:` / `host:` / `url-host:` 元数据提示，或兼容兜底识别，只在链接域名匹配时暴露，
  避免模型把 CSDN、知乎等链接误交给专站工具。
- Provider 只负责 Spring AI adapter 和 EchoMind seam 转换；OpenAI 兼容模型与 DeepSeek 的 tool-calling
  协议细节交给 Spring AI。EchoMind 的工具回调仍按 Schema 校验参数。Provider 不允许按具体 Skill
  名称硬编码默认参数或最终答案策略；工具输出可直接作为最终答案时，由 Skill/工具 metadata 声明
  `direct-result` 或 `final-answer` 标签。

## Agent Team 接口演进方向

Team 已演进为 MySQL 黑板驱动的异步协作：

- `POST /api/teams`：创建 Team，Reviewer 必填；成员包含角色、Agent 和能力标签。
- `DELETE /api/teams/{teamId}`：硬删除 Team、Member、Run、Step、Event 黑板记录。
- `POST /api/teams/{teamId}/runs`：创建异步 Run，写 MySQL 后由 `TaskExecutor` 后台执行。
- `GET /api/teams/{teamId}/runs`：查询当前用户在该 Team 下的 Run。
- `GET /api/teams/{teamId}/runs/{runId}`：轮询 Run、Step、Event、Reviewer 决策、Mermaid 和最终报告。
- `POST /api/teams/{teamId}/runs/{runId}/resume`：Reviewer 要求澄清后提交用户补充信息并继续 Run。
- `GET /api/team-runs`：查询当前用户所有 Team Run 历史，与普通聊天会话历史分离。

Team v2 的执行链路是 `Planner -> Reviewer 规划审查 -> TeamControlCenter DAG 调度 -> AgentSelector -> Executor -> SubReviewer(高风险) -> MergeAgent -> ConflictDetector -> PlannerArbitration(必要时) -> GlobalReviewer`。
Planner 只输出 `taskLevel`、`requiredCapabilities`、`dependsOn`、`riskLevel` 等结构化约束，不在计划里硬指定 Agent；执行前 `AgentSelector` 会把候选 Executor、能力标签、当前活跃 Step 负载、健康状态和规则评分交给模型自主选择，模型选择失败时才按规则评分兜底。`RiskPolicy` 统一裁决是否进入 SubReviewer，避免按任务名、Skill 名或 Agent 名硬编码。
`taskLevel=SIMPLE` 时走快路径：创建 `simple-draft` Step 生成初稿，再进入 GlobalReviewer 简易终审；复杂任务继续进入 DAG 并发调度。

Reviewer 是状态机质量闸门：规划后审查 Planner 的 Step，执行后对照初始需求审查 Executor 原始结果。
Reviewer 可返回 `CONTINUE`、`RETRY`、`PARTIAL_REPLAN`、`REPLAN`、`ASK_CLARIFICATION` 或 `FAILED`；
`RETRY` 只重跑指定 Step，`PARTIAL_REPLAN` 会把局部 DAG 分支及其下游标记为 `SUPERSEDED` 并重规划替代子图，`REPLAN` 用于结果阶段发现 Step 结构缺失或拆解方向错误时重新规划，默认最多 1 次。
每次重试必须把错误原因、修改意见和上一轮输出摘要写入 Step 的 `reflectionJson`，再放进下一轮 Executor prompt。
MergeAgent 输出后先走 ConflictDetector；如 `conflictReportJson.hasConflict=true`，Planner 产出 `arbitrationJson`，MergeAgent 带仲裁结果二次聚合。仲裁仍无法消除冲突时交给 GlobalReviewer 判定重试、局部重规划、整体重规划或失败。

不要让 Agent 之间直接互调。Team 协作上下文通过 Run / Step / Event 黑板传递，统一由 Team 运行时服务编排。
Team 内部的 Planner / Executor / Reviewer / MergeAgent / ConflictDetector LLM 调用走 `AgentOrchestrator.executeInternal`，不读取或写入普通聊天会话历史；前端只通过 Team Run 看板展示黑板数据、管控中心事件和最终报告，轮询间隔为 0.25 秒，用户可下载最终 Markdown。

## 新增 API 检查表

新增接口前检查：

- 是否已有对应 Application Service；没有就先补一个，不要把流程写进 Controller。
- 请求体和响应体是否在 DTO 包中定义清楚。
- 是否涉及持久化；涉及就确认 MySQL 表或 Repository。
- 是否涉及运行时索引；涉及就确认写库成功后再同步运行时。
- 是否涉及前端跨页面状态；涉及就同步设计 Pinia store。
- 是否需要更新 `docs/API.md`、根 `README.md` 或本 harness。
