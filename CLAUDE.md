# CLAUDE.md - EchoMind Claude 入口

本文件是 Claude / Claude Code 进入 EchoMind 项目时的轻量上下文。详细工程规则不要在这里重复维护，统一看 `AGENTS.md` 和 `docs/harness`。

## 必读文件

1. `AGENTS.md`：最高优先级规则和常用命令。
2. `docs/harness/AGENTS.md`：项目阅读 harness。
3. `docs/harness/src/api/ARCHITECTURE.md`：API 和应用服务边界。
4. `docs/harness/src/db/CONSTRAINTS.md`：数据库和存储硬约束。
5. `docs/harness/PROGRESS.md`：当前进度、已知问题和下一步建议。

## 项目速览

EchoMind 是 Java 17 / Spring Boot 3.5 + Vue 3 的 AI Agent 平台。

核心模块：

- `echomind-common`：公共模型、异常、Schema 校验。
- `echomind-skill-api`：Skill SPI。
- `echomind-skill`：Skill 加载、注册、热加载、市场状态。
- `echomind-llm`：模型 Provider 和模型路由；Provider implementation 通过 Spring AI adapter 调用 OpenAI-compatible / DeepSeek Chat Completions。
- `echomind-memory`：会话记忆、Milvus 向量检索、Agent 知识库。
- `echomind-mcp`：基于 Spring AI MCP 的外部 MCP 客户端、stdio/SSE/Streamable HTTP 传输和工具适配。
- `echomind-agent`：单 Agent 执行、Pipeline、能力注册表。
- `echomind-agent/src/main/java/com/echomind/agent/tool`：工具注册、能力来源适配和统一工具视图。
- `echomind-agent-team`：多 Agent 团队协作编排；当前由 `TaskExecutor` 推进 MySQL 黑板状态，不使用 RabbitMQ。
- `echomind-console`：REST Controller、Application Service。
- `echomind-boot`：Spring Boot 自动装配。
- `echomind-app`：应用入口和配置文件。
- `echomind-web`：Vue 3 前端。
- `skills/*`：内置 Skill 示例。

## 当前架构边界

```text
Controller
  -> Application Service
  -> Runtime Orchestrator / Pipeline / CapabilityRegistry
  -> Repository / Memory / LLM Provider / Skill / MCP
```

关键规则：

- Controller 不直接拼业务流程。
- Application Service 负责校验、持久化顺序和运行时同步。
- `AgentFactory`、`SkillRegistry`、`CapabilityRegistry` 只是运行时索引。
- MySQL 保存业务事实和完整会话历史；Redis 保存短期上下文和用户画像快照；Milvus 保存用户长期事实向量以及 Agent 知识库切片正文和向量。
- RabbitMQ 只用于 `echomind.chat.requests` 异步聊天请求、`echomind.chat-memory.persist.exchange` 普通聊天记忆分片写入和 `echomind.user-memory.requests` 用户长期记忆事件；聊天 token、tool、result、failure 事件由消费端直接交给 SSE 推送服务。核心队列重试耗尽后进入 DLQ，由主后端归档到 MySQL `echomind_rabbitmq_dead_letters`，聊天请求死信会释放入队前 reservation 并推送 SSE `failure`，管理端支持按 dead-letter id 受控重放。
- `ToolRouter` 只作为运行时工具注册表；普通聊天直接暴露所有已启用 Skill 和已挂载外部 MCP 工具，参数由模型正式 tool call 生成并按 schema 校验。
- LLM Provider 只处理模型协议；Spring AI 只放在 Provider adapter 边界内，不接管 Agent、Skill、MCP 或 Memory。Provider 不按具体 Skill 名称硬编码参数、工具选择或最终答案策略；工具输出统一回到 LLM 生成最终答复。
- 主项目只接入外部 MCP Server，不暴露自身 MCP Server。
- 普通聊天记忆按 `userId + sessionId` 隔离；Agent、Skill、MCP、Team 仍是全局资源。
- AI Infra 是现有 Agent 项目的项目三管理端，不新增独立网关或 OpenAI `/v1` 入口；脱敏、告警、Trace、Token 和配额治理都挂在现有 `/api/chat/*` 链路和 `/api/admin/*` 管理端。

## 常用命令

```powershell
cd D:\claudeWorkSpace\ai-agent
mvn.cmd -q -DskipTests compile
mvn.cmd -q test
mvn.cmd -q -pl echomind-agent,echomind-llm,echomind-boot -am "-Dtest=ToolRouterTest,OpenAICompatibleProviderTest,DeepSeekProviderTest,ResultAggregationStageProviderRequestTest,AgentRuntimeBootstrapperTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

```powershell
cd D:\claudeWorkSpace\ai-agent\echomind-web
npm.cmd run build
```

```powershell
cd D:\claudeWorkSpace\ai-agent
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\deploy-runtime.ps1
```

部署后：

```powershell
Invoke-RestMethod http://localhost:8080/actuator/health
Invoke-RestMethod http://localhost:8080/api/models
Invoke-RestMethod http://localhost:8080/api/mcp/servers
Invoke-RestMethod http://localhost:8080/api/mcp/tools
```

本地 Compose 默认带 OpenTelemetry Collector 和 Jaeger，管理端 Trace 页面查询新产生的聊天链路；要临时关闭导出可设置 `OTEL_TRACES_EXPORTER=none`。

## 维护提醒

- 本文件只放 Claude 工具入口信息，不再维护长篇架构细节。
- 架构和调用链路变化优先更新 `docs/harness`，必要时再同步 `README.md`。
- 如果发现本文件和 `AGENTS.md`、`docs/harness` 冲突，以 `AGENTS.md` 和 `docs/harness` 为准。
