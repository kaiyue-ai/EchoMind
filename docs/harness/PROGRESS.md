# 当前进度

最后更新：2026-06-03

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
- 用户日/月 Token quota 已改为“请求前快速检查 + 模型返回后原子结算”：`echomind_token_quotas` 保存限额配置，`echomind_token_quota_usage` 按用户日/月 bucket 保存已结算额度；真实 provider usage 审计先落 `echomind_ai_call_usage`，再结算 quota，结算超限返回配额错误且不发普通调用失败告警。
- 已新增管理端客户端用户管理页面：支持查询所有客户端用户，并对客户端账号执行封禁、解封和硬删除；删除会清理该用户聊天、消息、用量、配额和记忆缓存，不影响全局 Agent、Skill、MCP、Team。
- 已新增管理端真实数据仪表盘：按时间范围展示今日请求、今日 Token、范围 Token、累计 Token、客户端用户数、平均响应、模型 Token 分布、Token 日趋势和最近调用，不展示项目没有落库的成本、余额或 API 密钥消费。
- 管理端 Trace 查询已支持按客户端 `userId` 过滤，后端会转换为 Jaeger tag `echomind.user_id` 查询；真实聊天链路在 Jaeger 中包含 HTTP、业务入口、Agent、Pipeline、LLM、JDBC 等多 Span。
- AI Infra 已收敛为现有 Agent 项目的项目三管理端，不新增独立网关、OpenAI `/v1` 入口或应用 API Key。
- 已新增敏感数据治理：聊天请求进入 Agent 前和响应返回前支持手机号、身份证、邮箱、银行卡、IP 等规则脱敏/阻断，事件只保存脱敏后样本。
- 已新增告警治理：调用错误、错误率、Token 超限/预警和敏感数据事件进入告警事件表，支持飞书自定义机器人 Webhook、静默期、静默累计升级和管理端规则配置。
- 管理端已新增脱敏治理和告警中心页面，仪表盘增加错误率、脱敏事件数和告警事件数。
- 聊天入口已进一步解耦：`ChatGovernanceService` 统一收口配额、脱敏、用量和调用错误告警；流式聊天改为 `POST /api/chat` 入队、`GET /api/chat/stream/{requestId}` 订阅 token 事件；`MemoryApplicationService` 统一会话摘要、历史读取和附件展示 URL 刷新。
- RabbitMQ 使用面已梳理：当前只用于 `echomind.chat.requests` 异步聊天请求、`echomind.chat.stream-events` SSE 事件、`echomind.chat-memory.persist.exchange` 普通聊天记忆分片写入和 `echomind.user-memory.requests` 用户长期记忆事件；Agent Team 仍由 `TaskExecutor` 推进 MySQL 黑板状态机。
- RabbitMQ stream-events 消费者配置已统一为 `echomind.rabbitmq.chat-stream-event.*` / `ECHOMIND_RABBITMQ_CHAT_STREAM_EVENT_*`，并保留旧 `chat-response` 配置和环境变量作为兼容兜底。
- 已新增 Docker MySQL 迁移入口：`scripts/apply-mysql-migrations.ps1` 和 harness `make migrate` 会按顺序执行 `docker/mysql/migrations/*.sql`，`make deploy` 在重启后端前自动应用迁移。
- 已补齐 Agent 知识库 HTTP 编排层：`AgentController` 只处理 HTTP 适配，上传校验和 `MultipartFile` 参数整理进入 `AgentKnowledgeApplicationService`。
- 启动恢复逻辑已从 `EchoMindAutoConfiguration` 下沉到 `AgentRuntimeBootstrapper`；默认 Skill 补齐、旧模型迁移、fallback Agent 和退役 Skill 清理已由 `echomind.runtime` 配置驱动，避免启动流程继续硬编码具体 Skill 或旧模型。
- LLM Provider 已移除按具体工具名判断最终答案策略的硬编码；工具输出统一回到 LLM 生成最终答复，不支持工具直出最终答案。
- 工具路由已收敛为显式 metadata 预匹配：`ToolRouter` 保持入口，`ToolMatchScorer` 只基于 `keywords`、`aliases`、`tags` 和工具名打分；短句追问由 `ToolExposurePlanner` 回看最近用户消息补回上一轮业务工具；URL/domain 专用过滤、确定性消歧和直调参数兜底已移除，参数交给模型正式 tool call 与 schema 校验。
- `echomind-llm` 已局部接入 Spring AI：OpenAI-compatible 与 DeepSeek Provider 收缩为 ChatModel adapter，DeepSeek 默认协议迁移到 Chat Completions，默认模型为 `deepseek-v4-flash`。
- README、CLAUDE、AGENTS、docs/API 和 harness 文档已同步项目详解、运行步骤、整体架构图、工具路由边界和验证命令。
- Agent Team v2 已合并 DAG 管控中心与 Reflexion 重试：Planner 输出 SIMPLE/COMPLEX 和 DAG，TeamControlCenter 按依赖调度可并发 Step，AgentSelector 将候选 Executor、能力/负载/健康度评分交给模型自主选择并保留规则兜底，RiskPolicy 触发 SubReviewer，MergeAgent 聚合，ConflictDetector 检测冲突，必要时 PlannerArbitration 仲裁，GlobalReviewer 负责终审、重试、局部重规划、整体重规划、澄清和最终报告。
- Team Run 已按当前用户隔离落 MySQL 黑板，并新增 `/api/team-runs` 与普通聊天历史分开查询；Team 内部调用继续走 `executeInternal`，不写普通聊天会话。
- 前端 Team 看板已改为 0.25 秒轮询，用户可看到中文状态、完整管控流程图、管控中心、风险/质量/Reflexion、冲突/仲裁信息，并下载最终 Markdown。

## 进行中

- 持续压缩架构边界：Controller 做 HTTP 适配，Application Service 做用例编排，Runtime 做执行。
- 清理无用代码和过时注释，保持中文注释说明关键业务边界。
- 继续压缩启动装配类体积，把 LLM、Memory、Agent Runtime、Messaging 和 Storage 的 Bean 图按 seam 拆开。
- Trace 后续可继续补 Zipkin / Tempo 查询适配、错误 Span 聚合和本地查询缓存；观测组件仍应独立部署，避免影响业务服务启动。
- 告警当前使用飞书自定义机器人 Webhook；地址只读取后端运行环境变量 `Webhook`，管理端不再提供规则级 Webhook 配置。

## 已知问题

- Agent Team 当前异步执行基于 `TaskExecutor`，不是 RabbitMQ Consumer；后续如需跨实例队列和失败恢复，可替换为 RabbitMQ。
- Team Run 取消和手动 Step 重试仍未实现。
- 部分历史功能经过多轮修改，仍需要继续清理过时文档、死代码和重复 DTO。
- Docker 后端部署依赖宿主机环境变量；Windows 手动部署优先使用 `scripts/deploy-runtime.ps1`，
  它会先加载用户/系统环境变量，确保 `Webhook`、LLM、OSS、百炼等变量进入 Docker Compose 进程。
- 默认部署保留独立 OpenTelemetry Collector / Jaeger；后端不依赖观测组件健康状态，可用 `OTEL_TRACES_EXPORTER=none` 关闭导出。

## 下一步建议

优先级从高到低：

1. Team Run 取消和手动 Step 重试。
2. Team 执行由 TaskExecutor 演进为 RabbitMQ Consumer。
3. 多 Executor 能力匹配继续纳入 MCP 工具标签、知识库范围和模型能力。
4. 为更多非标准模型协议补原生 usage 解析；不允许回退到本地预估 Token。
5. 继续拆分 `EchoMindAutoConfiguration`：优先把 LLM、Memory、Agent Runtime、Messaging 和 Storage 装配分成独立 auto-configuration module。
6. 继续清理历史冗余代码，尤其是已废弃的 MCP Server 暴露逻辑和重复能力注册路径。

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
