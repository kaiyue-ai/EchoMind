# EchoMind Harness

这份 harness 是给开发者和 AI Agent 读项目、改项目、复盘项目用的入口文档。它不替代根目录的 `AGENTS.md`、`README.md` 和 `CLAUDE.md`，而是把“读代码路径、硬约束、标准命令、当前状态”集中放在 `docs/harness` 下，方便后续按固定方式接手。

## 项目概览

EchoMind 是一个 Java 17 / Spring Boot 3.3 + Vue 3 的 AI Agent 平台。核心能力包括：

- 多模型接入：DeepSeek、OpenAI 兼容接口、阿里云百炼，Anthropic 可选接入。
- Agent 管理：Agent 配置落 MySQL，运行时恢复到 `AgentFactory`。
- 对话执行：同步、异步、流式三种入口最终进入 `AgentOrchestrator -> Agent -> ExecutionPipeline`。
- 记忆系统：MySQL 保存完整会话，Redis Stack 做近期上下文缓存、向量检索和用户长期画像。
- Skill 系统：本地 JAR 插件通过 `SkillRegistry` 加载，启用后同步到 `CapabilityRegistry`。
- 外部 MCP：主项目只作为 MCP 客户端接入外部 MCP Server，不再把主项目暴露成 MCP Server。
- Agent Team：目前已有 Planner / Executor / Reviewer 的协作骨架，后续应向持久化任务运行、能力分配、状态机编排演进。

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
├── echomind-llm/             # 模型 Provider、模型路由
├── echomind-memory/          # 会话记忆、向量检索、知识库
├── echomind-mcp/             # 外部 MCP 客户端与 stdio 运行时
├── echomind-agent/           # 单 Agent 执行、Pipeline、能力注册表
├── echomind-agent-team/      # 多 Agent 协作编排
├── echomind-console/         # REST API、Application Service、CLI
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
6. 对话主链路从 `ChatController`、`ChatApplicationService`、`AgentOrchestrator`、`ExecutionPipeline` 读。
7. 工具调用从 `CapabilityRegistry`、`SkillCapabilityService`、`ExternalMcpRuntimeService` 读。
8. 前端从 `echomind-web/src/stores` 和页面组件读，先看状态如何跨路由保持。

## 硬约束

- MySQL 是 Agent、Skill 状态、前端完整会话历史、知识库元数据的事实来源。
- Redis Stack 是 LLM 最近上下文、向量索引和用户长期画像事实来源；LLM 不从 MySQL 读取聊天历史。
- `AgentFactory`、`SkillRegistry`、`CapabilityRegistry` 都是运行时索引，不允许当持久化存储。
- Controller 不写业务流程，只做 HTTP 适配和 DTO 转换。
- Application Service 负责用例编排、校验、持久化顺序和运行时同步。
- 单 Agent 执行逻辑放 `echomind-agent`，团队协作逻辑放 `echomind-agent-team`。
- 主项目只接入外部 MCP Server，不恢复“主项目暴露 MCP Server”的旧能力。
- 禁用 Skill 或卸载 MCP 后，必须同步移除 `CapabilityRegistry` 中的工具。
- 对话记忆按 `sessionId` 隔离，不要改回“每个 Agent 一份记忆”。
- 前端页面跳转不能导致聊天会话状态丢失，应由 Pinia store 承接跨路由状态。
- 修改架构、接口、调用链路后，同步更新根目录 `README.md`、`CLAUDE.md` 或本 harness。

## 标准操作

优先使用本目录下的 `Makefile` 作为命令入口：

```powershell
cd D:\claudeWorkSpace\ai-agent\docs\harness
make check
make test
make deploy
```

如果本机没有 `make`，可以打开 `docs/harness/Makefile`，直接复制里面对应目标的 PowerShell 命令执行。

## 读改原则

- 先找入口，再找用例编排，再找运行时实现，最后看持久化。
- 发现重复代码时，先判断是不是模块边界不清导致的，不要直接抽公共工具类。
- 发现内存 Map 保存业务数据时，优先判断是否应该落 MySQL。
- 发现 Controller 里业务逻辑变厚时，优先下沉到 Application Service。
- 发现 Agent、Skill、MCP 互相直接调用时，优先改为通过 `CapabilityRegistry` 或编排层解耦。
