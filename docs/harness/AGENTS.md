# EchoMind Harness

这份 harness 是给开发者和 AI Agent 读项目、改项目、复盘项目用的入口文档。它不替代根目录的 `AGENTS.md`、`README.md` 和 `CLAUDE.md`，而是把“读代码路径、硬约束、标准命令、当前状态”集中放在 `docs/harness` 下，方便后续按固定方式接手。

## 项目概览

EchoMind 是一个 Java 17 / Spring Boot 3.5 + Vue 3 的 AI Agent 平台。核心能力包括：

- 多模型接入：`echomind-llm` 通过 Spring AI adapter 接入 DeepSeek、OpenAI 兼容接口、阿里云百炼等 Chat Completions 模型。
- Agent 管理：Agent 配置落 MySQL，运行时恢复到 `AgentFactory`。
- 对话执行：公开聊天只保留异步入口 `POST /api/chat` 入队，`GET /api/chat/stream/{requestId}` 订阅消费端直推的 SSE meta/token/tool/result/failure 事件。
- RabbitMQ 用途：承接 `echomind.chat.requests` 异步聊天请求、`echomind.chat-memory.persist.exchange` 普通聊天记忆分片写入、`echomind.user-memory.requests` 用户长期记忆事件，以及 Team Run Event / Step Execute 队列；核心队列失败重试耗尽后进入 DLQ，由主后端归档到 MySQL 并支持受控重放；Agent Team 通过 `TeamBlackboardService -> TeamStepCommandProducer -> TeamDagCoordinator -> TeamStepExecutionConsumer` 推进，Redis 保存 DAG 热投影，MySQL 黑板保存事实。
- 链路追踪：后端内置 OpenTelemetry Spring Boot Starter 和 EchoMind 业务 Span；本地 Compose 默认启动 OpenTelemetry Collector 和 Jaeger，管理端 Trace 页面查询新产生的链路。
- 记忆系统：MySQL 按 `userId + sessionId` 保存普通聊天完整会话；Redis 按字符预算保存单会话短期上下文和用户画像快照；Milvus 保存用户长期事实向量以及 Agent 知识库切片正文和向量。
- Skill 系统：本地 JAR 插件通过 `SkillRegistry` 加载，启用后同步到 `CapabilityRegistry`。
- 工具路由：`ToolRouter` 只做运行时注册表；普通聊天直接把所有已启用 Skill 和已挂载外部 MCP 工具交给模型，参数由模型正式 tool call 生成并按 schema 校验；事实、知识和时效类联网搜索要求通过 `ResultAggregationStage` 的统一工具提示增强，不恢复旧的工具预筛选或独立 GroundingPolicy。
- 外部 MCP：主项目只作为 MCP 客户端接入外部 MCP Server，不再把主项目暴露成 MCP Server；底层连接使用 Spring AI MCP / Java MCP SDK，支持 stdio、SSE 和 Streamable HTTP。
- Agent Team：已演进为 MySQL 黑板 + Redis DAG 投影 + RabbitMQ 队列驱动的异步 DAG 协作，Planner / PlanReview / TeamDagCoordinator / AgentSelector / Executor / StepReviewer / MergeAgent / ConflictDetector / PlannerArbitration / GlobalReviewer 通过 Run / Step / Event 交换上下文；MySQL 是事实来源，Redis 只保存可重建的 ready/running/completed 热状态。

## 整体架构图

```mermaid
flowchart TB
    subgraph Entry["入口层"]
        Client["客户端 Vue 工作台"]
        Admin["项目三管理端"]
        API["REST API"]
    end

    subgraph Console["echomind-console"]
        Controller["Controller<br/>HTTP / SSE 适配"]
        Service["Application Service<br/>用例编排 / 治理 / 运行时同步"]
    end

    subgraph AgentRuntime["Agent Runtime"]
        Orchestrator["AgentOrchestrator"]
        Pipeline["ExecutionPipeline"]
        Capability["CapabilityRegistry"]
        Team["echomind-agent-team<br/>MySQL Blackboard<br/>Redis DAG"]
    end

    subgraph Extension["能力扩展"]
        Skills["Skill JAR<br/>SkillRegistry"]
        ExternalMcp["External MCP<br/>stdio / SSE / Streamable HTTP"]
    end

    subgraph DataAndInfra["数据与基础设施"]
        LLM["echomind-llm<br/>Spring AI Provider Adapter"]
        MySQL["MySQL<br/>业务事实和完整历史"]
        Redis["Redis<br/>近期上下文和画像快照"]
        Milvus["Milvus<br/>长期事实向量和知识库向量"]
        Rabbit["RabbitMQ<br/>chat.requests<br/>chat-memory shards / user-memory<br/>team run/step queues<br/>DLQ archive / replay"]
        ObjectStore["OSS / Local Storage<br/>头像和附件"]
        Observability["OTel Collector + Jaeger"]
        UserMemory["echomind-user-memory"]
    end

    Client --> Controller
    Admin --> Controller
    API --> Controller
    Controller --> Service
    Service --> Orchestrator
    Service --> Team
    Orchestrator --> Pipeline
    Pipeline --> LLM
    Pipeline --> Capability
    Skills --> Capability
    ExternalMcp --> Capability
    Pipeline --> Rabbit
    Rabbit --> UserMemory
    UserMemory --> Milvus
    UserMemory --> Redis
    Service --> MySQL
    Service --> Redis
    Service --> Milvus
    Service --> ObjectStore
    Console --> Observability
    AgentRuntime --> Observability
```

## 真实目录对应

```text
项目根目录
├── AGENTS.md                 # 全局协作规则
├── CLAUDE.md                 # Claude / AI 工具上下文
├── README.md                 # 面向用户和开发者的主说明
├── docs/harness/             # 本 harness
├── echomind-common/          # 公共模型、异常、Schema 校验
├── echomind-skill-api/       # Skill SPI，插件 JAR 的最小依赖
├── echomind-skill/           # Skill 加载、市场状态、热加载
├── echomind-llm/             # 模型 Provider、Spring AI adapter、模型路由
├── echomind-memory/          # 会话记忆、Milvus 向量检索、知识库、用户画像快照端口
├── echomind-mcp/             # MCP 工具 provider 抽象，外部 MCP 运行时适配在 echomind-agent/tool/mcp
├── echomind-agent/           # 单 Agent 执行、Pipeline、工具路由、能力注册表
├── echomind-agent-team/      # 多 Agent 协作编排
├── echomind-console/         # REST API、Application Service
├── echomind-boot/            # Spring Boot 自动装配
├── echomind-app/             # 应用启动入口和 application.yml
├── echomind-web/             # Vue 前端
└── skills/                   # 内置 Skill 示例
```

## 从哪里开始读

第一次读项目，按这个顺序走：

1. 读根目录 `README.md`，先理解整体调用链路。
2. 读根目录 `AGENTS.md`，了解这个项目最重要的硬约束。
3. 读 `docs/harness/src/api/ARCHITECTURE.md`，看接口层和应用服务层怎么分工。
4. 读 `docs/harness/src/db/CONSTRAINTS.md`，确认哪些数据必须持久化，哪些只是缓存。
5. 后端从 `echomind-console` 的 Controller 进入，再顺着 Application Service 往下读。
6. 对话主链路从 `ChatController`、`ChatApplicationService`、`ChatRabbitConsumer`、`AgentOrchestrator`、`ExecutionPipeline` 读。
7. 工具调用从 `CapabilityRegistry`、`SkillCapabilityService`、`ExternalMcpRuntimeService` 读。
   如果在看模型工具入参，继续读 `ToolRouter`、`ResultAggregationStage` 和 `ProviderRequestFactory`。
8. 前端从 `echomind-web/src/stores` 和页面组件读，先看状态如何跨路由保持。

## 硬约束

- MySQL 是 Agent、Skill 状态、前端完整会话历史、知识库文档元数据的事实来源。
- Redis 是 LLM 最近上下文和用户画像快照来源；Milvus 是用户长期事实以及知识库切片正文和向量事实来源；Redis 用户画像快照是事实层的压缩结果；LLM 不从 MySQL 读取聊天历史。
- `AgentFactory`、`SkillRegistry`、`CapabilityRegistry` 都是运行时索引，不允许当持久化存储。
- Controller 不写业务流程，只做 HTTP 适配和 DTO 转换。
- Application Service 负责用例编排、校验、持久化顺序和运行时同步。
- 单 Agent 执行逻辑放 `echomind-agent`，团队协作逻辑放 `echomind-agent-team`。
- 主项目只接入外部 MCP Server，不恢复“主项目暴露 MCP Server”的旧能力。
- 禁用 Skill 或卸载 MCP 后，必须同步移除 `CapabilityRegistry` 中的工具。
- Provider 不允许按具体 Skill 名称硬编码默认参数、工具选择或最终答案策略；新增工具应通过
  `description` 和 `parameterSchema` 让模型理解何时调用。
- `keywords`、`aliases` 和 `tags` 仍可用于市场展示、管理和能力分析，但普通聊天不再用它们预筛选工具。
- 普通聊天记忆按 `userId + sessionId` 隔离；旧无 token 请求归 `default` 用户，不要改回“每个 Agent 一份记忆”。
- 第一阶段只隔离普通聊天会话和记忆；Agent、Skill、MCP 仍是全局资源。Team 定义和 Team Run 按当前用户隔离。
- Trace 埋点使用 OpenTelemetry API，不自建替代标准 Span 的追踪模型。
- 前端页面跳转不能导致聊天会话状态丢失，应由 Pinia store 承接跨路由状态。
- 修改架构、接口、调用链路后，同步更新根目录 `README.md`、`CLAUDE.md` 或本 harness。

## 标准操作

优先使用本目录下的 `Makefile` 作为命令入口：

```powershell
cd D:\claudeWorkSpace\ai-agent\docs\harness
make check
make test
make migrate
make deploy
```

如果本机没有 `make`，可以打开 `docs/harness/Makefile`，直接复制里面对应目标的 PowerShell 命令执行。
`make migrate` 会启动 Docker Compose 里的 MySQL，并按顺序执行 `docker/mysql/migrations/*.sql`；
`make deploy` 会在后端重启前自动执行同一迁移入口，避免旧 MySQL volume 缺表导致生产 profile
启动失败。

## RabbitMQ 可靠性边界

- 可靠队列覆盖聊天请求、普通聊天记忆分片、用户长期记忆事件和 Team Run/Step 命令；在线 SSE token/tool/result/failure 事件仍由消费端直接推送，不进 RabbitMQ 强可靠队列。
- `echomind.chat.requests`、聊天记忆分片队列和 `echomind.user-memory.requests` 发布端使用可靠发布；消费端有限重试耗尽后进入 `echomind.dlx` 下的 DLQ。
- 主后端低并发消费 DLQ，把原始 payload、错误 headers、业务 key 和 traceId 归档到 MySQL `echomind_rabbitmq_dead_letters`。
- 聊天请求死信会释放入队前冻结的用户 Token reservation，并向 SSE buffer 推送 `failure` 终态；归档补偿完成后状态为 `COMPENSATED`。
- 聊天记忆和用户记忆死信只归档，不自动重放，避免重复写历史或重复沉淀用户事实。
- 管理端通过 `GET /api/admin/rabbitmq/dead-letters` 查询归档记录，通过 `POST /api/admin/rabbitmq/dead-letters/{id}/replay` 按 id 受控重放；成功状态为 `REPLAYED`，失败状态为 `REPLAY_FAILED`。
- Team RabbitMQ 只做可靠命令队列，MySQL 仍保存 Run / Step / Event 事实，Redis 只保存可重建 DAG 热投影。当前实现用消息 ID、MySQL 状态和 Redis DAG 状态做幂等边界；后续如补 Outbox、epoch、iteration 或 attemptId，仍必须保持 MySQL 事实优先。

## 读改原则

- 先找入口，再找用例编排，再找运行时实现，最后看持久化。
- 发现重复代码时，先判断是不是模块边界不清导致的，不要直接抽公共工具类。
- 发现内存 Map 保存业务数据时，优先判断是否应该落 MySQL。
- 发现 Controller 里业务逻辑变厚时，优先下沉到 Application Service。
- 发现 Agent、Skill、MCP 互相直接调用时，优先改为通过 `CapabilityRegistry` 或编排层解耦。
- 发现工具选择继续长硬编码时，优先增强工具 description/schema 和系统提示，不要把具体业务工具名写进 Provider。
