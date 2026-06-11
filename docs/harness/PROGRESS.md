# 当前进度

最后更新：2026-06-10

## 已完成

- 项目已形成多模块结构：LLM、Memory、MCP、Skill、Agent、Agent Team、Console、Boot、Web 分开维护。
- 主项目已从“暴露 MCP Server”调整为“接入外部 MCP Server”。
- 外部 MCP 底层协议已接入 Spring AI MCP / Java MCP SDK，保留 `CapabilityRegistry` 工具治理，支持 stdio、SSE 和 Streamable HTTP。
- Skill 和外部 MCP 工具统一进入 `CapabilityRegistry`，供 Agent 对话时使用。
- 对话记忆已改为按 `userId + sessionId` 隔离，不再按 Agent 共享记忆。
- 已新增第一阶段用户登录和认证上下文：无 token 兼容请求归属 `default` 用户。
- MySQL 保存完整会话历史，Redis 保存近期上下文和用户画像快照，Milvus 承担用户长期事实向量以及 Agent 知识库切片正文和向量。
- 主聊天向量检索前已增加轻量模型 query rewrite，用户长期事实和 Agent 知识库共用同一个检索 query；改写只影响 embedding，失败回退原句。
- 用户长期记忆已改为主 LLM 决策式方案：主模型同一次调用输出隐藏 `rememberFacts` / `refreshProfile` 布尔决策，解析失败时降级为两个开关都开启；`echomind-user-memory` 通过 RabbitMQ 接收事件后立即用轻量模型更新事实层和画像快照。
- Agent 知识库已支持文档上传、段落/标点优先切片、向量化、Milvus-only 窗口检索和前端展示。
- 前端聊天状态已通过 Pinia 持有，避免普通页面跳转导致智能对话重置。
- 前端已支持 Markdown 渲染和代码块一键复制。
- 已新增 `markdown-code` Skill，用于约束代码输出使用 Markdown fenced code block。
- 联网搜索已迁移为外部 MCP 服务 `open-websearch`，默认 Compose 部署
  `ghcr.io/aas-ee/open-web-search:latest` 并通过 Streamable HTTP 挂载；旧 Java 搜索 Skill 不再随默认部署启用。
- 已新增出行 Skill 包：`12306` 查询国内列车站点、余票、票价、经停和中转换乘，`travel-planning` 生成多城市行程和预算清单；`flight-tracker` 与 `qq-mail` 已移除，不再随默认部署启用。
- 阿里云 OSS 已接入上传文件和图片历史存储路径。
- 阿里云百炼多模态模型已纳入模型配置，模型能力中用 `vision` 区分是否支持图片。
- Docker Compose 默认部署业务依赖、Milvus、前后端、OpenTelemetry Collector 和 Jaeger；后端启动不依赖观测组件健康状态。
- 后端已接入 OpenTelemetry Spring Boot Starter 和 EchoMind 业务 Span；本地 Compose 默认使用 `OTEL_TRACES_EXPORTER=otlp` 接入 Collector。
- OpenTelemetry Collector + Jaeger 已进入默认 `docker-compose.yml`，项目三管理端可直接读取新产生的真实 Span 数据；`docker-compose.observability.yml` 仅保留为空兼容旧命令。
- 已新增独立项目三管理端前端容器 `admin-frontend`，端口 `8081`；客户端端口 `80` 保持对话、Agent、Skill、MCP、Team 工作台，不混入管理端页面。
- 已新增 Trace 管理前端和后端查询代理接口；未配置查询后端时页面显示未接入，不影响主业务。
- Trace 管理端默认只展示 `echomind.chat.*` 业务链路，避免管理端刷新和查询接口产生的单 Span 噪声淹没对话链路；页面可切换到全部 Trace。
- 普通聊天会话列表、历史查询、删除、Redis 最近上下文和异步记忆写入已按当前用户隔离；用户长期事实和画像按用户全局共享；Agent、Skill、MCP、Team 仍为全局资源。
- 用户头像已支持上传到统一对象存储，MySQL 用户表仅保存 `avatar_uri`，前端侧栏可直接更换头像。
- 管理端账号已和客户端账号分离：管理端使用 `/api/admin/auth/*` 与 `echomind_admin_users`，客户端继续使用 `/api/auth/*` 与 `echomind_users`，两类 token 不互认。
- 已新增 `echomind_ai_call_usage` 调用用量表和管理端用户用量页面：支持查看所有客户端总 Token、客户端用户列表、单用户总 Token、每次调用 Token 和 TraceID。
- 用户日/月 Token quota 已改为“两阶段 Redis 预留 + 模型返回后真实用量结算”：`echomind_token_quotas` 保存限额配置，`echomind_token_quota_usage` 按用户日/月 bucket 保存真实已用；Redis `echomind:quota:*` 保存当前 bucket used 热镜像和 in-flight reserved 冻结额度；聊天入队或 Team 调用前做 initial 预留，完整 `ProviderRequest` 生成后 final preflight 只补 delta；真实 provider usage 审计先落 `echomind_ai_call_usage`，再结算 quota，后置结算不拒绝已完成调用，下一次请求前预留负责返回配额错误。
- 已新增管理端客户端用户管理页面：支持查询所有客户端用户，并对客户端账号执行封禁、解封和硬删除；删除会清理该用户聊天、消息、用量、配额和记忆缓存，不影响全局 Agent、Skill、MCP、Team。
- 已新增管理端真实数据仪表盘：按时间范围展示今日请求、今日 Token、范围 Token、累计 Token、客户端用户数、平均响应、模型 Token 分布、Token 日趋势和最近调用，不展示项目没有落库的成本、余额或 API 密钥消费。
- 管理端 Trace 查询已支持按客户端 `userId` 过滤，后端会转换为 Jaeger tag `echomind.user_id` 查询；真实聊天链路在 Jaeger 中包含 HTTP、业务入口、Agent、Pipeline、LLM、JDBC 等多 Span。
- AI Infra 已收敛为现有 Agent 项目的项目三管理端，不新增独立网关、OpenAI `/v1` 入口或应用 API Key。
- 已新增敏感数据治理：聊天请求进入 Agent 前和响应返回前支持手机号、身份证、邮箱、银行卡、IP 等规则脱敏/阻断；请求侧命中 `BLOCK` 不进入 Agent/RabbitMQ 管线，直接返回替代词拼接结果，响应侧 `BLOCK` 仍走阻断异常，事件只保存脱敏后样本或替代词结果。
- 已新增告警治理：调用错误、错误率、Provider Token 预算超限/预警和敏感数据事件进入告警事件表，支持飞书自定义机器人 Webhook、静默期、静默累计升级和管理端规则配置；Provider budget 也使用 Redis 预留和 `echomind_provider_token_budget_usage` 日/周/月真实已用账本；用户日/月 Token quota 不再保留单用户百分比预警。
- 管理端已新增脱敏治理和告警中心页面，仪表盘增加错误率、脱敏事件数和告警事件数。
- 聊天入口已进一步解耦：`ChatGovernanceService` 统一收口配额、脱敏、用量和调用错误告警；流式聊天改为 `POST /api/chat` 入队、`GET /api/chat/stream/{requestId}` 订阅 token 事件；`MemoryApplicationService` 统一会话摘要、历史读取和附件展示 URL 刷新。
- RabbitMQ 使用面已梳理：当前只用于 `echomind.chat.requests` 异步聊天请求、`echomind.chat-memory.persist.exchange` 普通聊天记忆分片写入和 `echomind.user-memory.requests` 用户长期记忆事件；聊天 token、tool、result、failure 事件由消费端直接交给 SSE 推送服务；Agent Team 仍不走 RabbitMQ，Run 级调度由 `TeamExecutionEngine` 适配本地 `TaskExecutor` 推进 MySQL 黑板状态机。
- SSE token 级 RabbitMQ 中转及其消费者配置已移除。
- 聊天请求入队前已携带用户 quota 和 Provider budget 两类 initial Redis reservation；消费端不重复请求前配额校验，Agent Pipeline 在 `ResultAggregationStage` 生成完整 `ProviderRequest` 后执行 final preflight，按 system prompt、工具 schema、附件元信息和输出上限重估，只补两类预算不足的 delta；RabbitMQ 聊天请求死信重放会在重新入队前重建两类 reservation。
- Team 内部 LLM 调用已改为每次调用前按本轮 prompt 显式预留用户 quota 和 Provider budget，并复用 Agent Pipeline 的 final `ProviderRequest` preflight；reservation id 会随 `PipelineContext` 进入统一用量结算。
- 配额/预算治理拒绝已结构化为 `ErrorDetail`，聊天 SSE failure 和 HTTP 错误会透传错误 code、phase、scope 和安全的额度字段；Provider budget 拒绝不向客户端暴露 provider 内部使用量。多段 reservation 结算按 owner/scope/bucket 合并，真实 provider usage 每个 bucket 只累加一次。
- RabbitMQ 核心队列已补齐 DLQ 归档和受控重放：聊天请求、普通聊天记忆和用户长期记忆重试耗尽后进入 DLQ，主后端归档到 MySQL `echomind_rabbitmq_dead_letters`；聊天请求死信会释放入队前冻结的用户 reservation 并推送 SSE `failure`，管理端可查询并按 dead-letter id 重放。
- 已新增 Docker MySQL 迁移入口：`scripts/apply-mysql-migrations.ps1` 和 harness `make migrate` 会按顺序执行 `docker/mysql/migrations/*.sql`，`make deploy` 在重启后端前自动应用迁移。
- 已补齐 Agent 知识库 HTTP 编排层：`AgentController` 只处理 HTTP 适配，上传校验和 `MultipartFile` 参数整理进入 `AgentKnowledgeApplicationService`。
- 启动恢复逻辑已从 `EchoMindAutoConfiguration` 下沉到 `AgentRuntimeBootstrapper`；默认 Skill 补齐、旧模型迁移、fallback Agent 和退役 Skill 清理已由 `echomind.runtime` 配置驱动，避免启动流程继续硬编码具体 Skill 或旧模型。
- 默认 Agent 启动配置已切到阿里云百炼 Qwen vision 模型 `aliyun-bailian:qwen3.6-plus`；芙莉莲、洛克希和张雪峰/耿同学 Skill 已退役，现保留 JVM 大手子、计算机操作系统大手子，并新增张雪峰 Agent。张雪峰 Agent 的系统提示词通过 `system-prompt-resource` 读取 GitHub `zhangxuefeng-skill` 的 `SKILL.md` 内容，私有知识库 seed 使用志愿填报公开著作/简介抽象出的结构化笔记，知识库仍按 `agentId` 进入 MySQL 元数据和 Milvus 向量索引。
- LLM Provider 已移除按具体工具名判断最终答案策略的硬编码；工具输出统一回到 LLM 生成最终答复，不支持工具直出最终答案。
- 工具路由已收敛为全量工具暴露：`ToolRouter` 只做运行时注册表，普通聊天每轮把所有已启用 Skill 和已挂载外部 MCP 工具交给模型；关键词预筛选、Agent allow-list 过滤和短句追问补工具逻辑已移除，参数交给模型正式 tool call 与 schema 校验。
- 普通聊天已移除独立 `GroundingPolicy` 和 `echomind-agent/pipeline/grounding` 包，联网搜索改为简单提示词增强：存在 `open_web_search` 或等价联网搜索工具时，除简短寒暄、翻译/润色/总结用户已给内容、纯格式转换、纯数学推理、纯代码局部解释或用户明确不要联网外，事实、知识和时效类问题回答前必须至少调用一次联网搜索取证；不再通过 `ProviderRequest.requiredToolName` 强制 function tool choice。
- `echomind-llm` 已局部接入 Spring AI：OpenAI-compatible 与 DeepSeek Provider 收缩为 ChatModel adapter，DeepSeek 默认协议迁移到 Chat Completions，默认模型为 `deepseek-v4-flash`。
- README、CLAUDE、AGENTS、docs/API 和 harness 文档已同步项目详解、运行步骤、整体架构图、工具路由边界和验证命令。
- Agent Team v2 已合并 DAG 调度与 Reflexion 重试：Planner 输出 SIMPLE/COMPLEX 和 DAG，调度器按依赖推进可并发 Step，AgentSelector 将候选 Executor、能力/负载/健康度评分交给模型自主选择并保留规则兜底，RiskPolicy 只给 StepReviewer 提供重点校验提示，MergeAgent 聚合，ConflictDetector 检测冲突，必要时 PlannerArbitration 仲裁，GlobalReviewer 只负责终审通过、要求重新合并或失败。
- Team Run 已支持按次选择质量/速度策略：默认保持 PlanReview、每步 Review、GlobalReview 全开；用户可跳过指定 Review，或在 SIMPLE 单 Step 任务上启用直返以减少串行 LLM 调用。DAG 默认可并发 Step 从 3 提升到 7，Team 线程池随运行时并发配置扩容。
- Team Review 语义已收窄为三段质量闸门：PlanReview 只允许计划阶段继续、重试、澄清或失败；StepReviewer 只审当前 Step，可通过、重做当前 Step、带风险接受或失败；GlobalReviewer 只审最终合并稿，可通过、要求 MergeAgent 重新合并或失败，不再重试 Step、不重规划 DAG、不向用户澄清。
- Team 执行已拆出 `TeamExecutionEngine` Run 级边界：负责事务提交后再派发 Run、统一捕获执行异常并回调失败处理；具体 DAG/Step 状态机仍在 `TeamBlackboardService` 和 MySQL 黑板中，底层仍使用本地 `TaskExecutor`。
- Team Run 已按当前用户隔离落 MySQL 黑板，并新增 `/api/team-runs` 与普通聊天历史分开查询；Team 内部调用继续走 `executeInternal`，不写普通聊天会话。
- 前端 Team 看板进入 Run 后立即刷新一次，随后 1 秒轮询；数据未变化时按 0.5 秒退避，最多 3 秒。页面可看到中文状态、DAG 流程图、风险/质量/Reflexion、冲突/仲裁信息，并下载最终 Markdown。

## 进行中

- 持续压缩架构边界：Controller 做 HTTP 适配，Application Service 做用例编排，Runtime 做执行。
- 清理无用代码和过时注释，保持中文注释说明关键业务边界。
- 继续压缩启动装配类体积，把 LLM、Memory、Agent Runtime、Messaging 和 Storage 的 Bean 图按 seam 拆开。
- Trace 后续可继续补 Zipkin / Tempo 查询适配、错误 Span 聚合和本地查询缓存；观测组件仍应独立部署，避免影响业务服务启动。
- 告警当前使用飞书自定义机器人 Webhook；地址只读取后端运行环境变量 `Webhook`，管理端不再提供规则级 Webhook 配置。

## 已知问题

- Agent Team 当前异步执行基于 `TeamExecutionEngine + TaskExecutor`，不是 RabbitMQ Consumer；DAG 推进仍由 MySQL 黑板状态机和线程池完成，后续如需跨实例队列、失败恢复和真正事件驱动 DAG，需要按 MySQL Edge/Outbox + Redis DAG 投影 + RabbitMQ Team command 队列分切片演进。
- Team Run 取消和手动 Step 重试仍未实现。
- 部分历史功能经过多轮修改，仍需要继续清理过时文档、死代码和重复 DTO。
- Docker 后端部署依赖宿主机环境变量；Windows 手动部署优先使用 `scripts/deploy-runtime.ps1`，
  它会先加载用户/系统环境变量，确保 `Webhook`、LLM、OSS、百炼等变量进入 Docker Compose 进程。
- 默认部署保留独立 OpenTelemetry Collector / Jaeger；后端不依赖观测组件健康状态，可用 `OTEL_TRACES_EXPORTER=none` 关闭导出。

## 下一步建议

优先级从高到低：

1. 新增 Team Step Edge 表和 Redis DAG 热投影，停止调度层全量扫 JSON 依赖。
2. Team 执行由 `TeamExecutionEngine + TaskExecutor` 演进为 RabbitMQ Consumer，并通过 Outbox 发布 Team command。
3. 为 Team Run 增加 Reconciler，支持 Redis 投影丢失后从 MySQL 黑板恢复 ready/running 状态。
4. 多 Executor 能力匹配继续纳入 MCP 工具标签、知识库范围和模型能力。
5. 为更多非标准模型协议补原生 usage 解析；不允许回退到本地预估 Token。
6. 继续拆分 `EchoMindAutoConfiguration`：优先把 LLM、Memory、Agent Runtime、Messaging 和 Storage 装配分成独立 auto-configuration module。
7. 继续清理历史冗余代码，尤其是已废弃的 MCP Server 暴露逻辑和重复能力注册路径。

## 阻塞项

- 如果要完整验证真实 LLM、多模态和 OSS，需要宿主机具备有效环境变量：
  - `DEEPSEEK_API_KEY`
  - `DEEPSEEK_BASE_URL`
  - `ALIYUN_BAILIAN_API_KEY`
  - `ALIYUN_BAILIAN_BASE_URL`
  - `AccessKeyID`
  - `AccessKeySecret`
  - `ALIYUN_OSS_BUCKET`
- 如果要验证 OCR，需要部署环境具备 Tesseract，并确认语言包配置。
