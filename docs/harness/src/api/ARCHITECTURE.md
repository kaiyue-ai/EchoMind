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
| `AgentController` | Agent 创建、更新、删除、执行和知识库管理入口 |
| `ChatController` | 同步聊天、异步聊天、流式聊天、图片上传聊天 |
| `SkillController` | Skill 列表、上传、启停、删除 |
| `MCPController` | 外部 MCP Server 挂载、卸载、刷新和工具直调 |
| `MemoryController` | 会话历史、记忆查询、记忆删除 |
| `ModelController` | 模型列表、默认模型切换 |
| `StorageController` | 对象上传，当前支持本地和阿里云 OSS |
| `TeamController` | Agent Team 创建、执行和协作状态查询 |

## 当前 Application Service

| Service | 责任 |
|---|---|
| `AgentApplicationService` | Agent 配置校验、MySQL 持久化、运行时 AgentFactory 同步 |
| `ChatApplicationService` | 归一化聊天请求，选择同步、异步或流式路径 |
| `SkillApplicationService` | Skill 上传、启停、删除，并同步能力注册表 |
| `McpApplicationService` | 外部 MCP 服务挂载、卸载、刷新和工具调用 |
| `MemoryApplicationService` | 会话维度的记忆查询和删除 |
| `ModelApplicationService` | 模型列表和默认模型切换 |
| `StorageApplicationService` | 文件上传、OSS 或本地存储策略选择 |
| `TeamApplicationService` | Team 创建、执行和消息状态查询 |

## 聊天接口链路

同步聊天：

```text
POST /api/chat/sync
  -> ChatController
  -> ChatApplicationService.executeSync
  -> AgentOrchestrator.execute
  -> Agent.chat
  -> ExecutionPipeline
  -> ContextEnrichStage
  -> ToolResolutionStage
  -> ResultAggregationStage
  -> MemoryPersistStage
```

`MemoryPersistStage` 只发布普通聊天记忆事件，不在主线程写 MySQL、Redis 或向量库。
事件进入 `echomind.chat-memory.persist.exchange`，按 `sessionId` hash 到
`echomind.chat-memory.persist.requests.shard.N`。每个分片队列只能单消费者，整体并发靠分片数扩展；
不要把单个聊天记忆分片改成多消费者，否则同一会话的历史可能乱序。

异步聊天：

```text
POST /api/chat
  -> ChatController
  -> ChatApplicationService.submitAsync
  -> RabbitMQ chat.requests
  -> ChatRabbitConsumer
  -> AgentOrchestrator.execute
  -> SSE 推送最终结果
```

直接流式聊天：

```text
POST /api/chat/stream
  -> ChatController
  -> ChatApplicationService.prepareStream
  -> AgentOrchestrator.executeStream
  -> Agent.chatStream
  -> SSE token
  -> MemoryPersistStage 在流结束后写回
```

Provider 层会尽量使用厂商真流式能力：OpenAI 兼容协议（包括阿里云百炼/Qwen）使用
Chat Completions `stream: true` 并解析 `choices[].delta.content`；DeepSeek 使用 Anthropic-compatible
Messages `stream: true` 并解析 `content_block_delta` / `text_delta`。Mock Provider 仅用于开发和测试，
仍是单段模拟输出。

删除单条会话：

```text
DELETE /api/chat/{sessionId}
  -> ChatController
  -> ChatApplicationService.deleteSession
  -> MemoryManager.getFullContext
  -> MemoryManager.clearSession
  -> ObjectStorageService.deleteObject (尽力回收聊天附件)
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
- URL 请求先走确定性工具过滤：通用网页读取/搜索交给 `web-search`，域名专用 MCP
  只在链接域名匹配时暴露，避免模型把 CSDN、知乎等链接误交给牛客工具。

## Agent Team 接口演进方向

Team 已演进为 MySQL 黑板驱动的异步协作：

- `POST /api/teams`：创建 Team，Reviewer 必填；成员包含角色、Agent 和能力标签。
- `DELETE /api/teams/{teamId}`：硬删除 Team、Member、Run、Step、Event 黑板记录。
- `POST /api/teams/{teamId}/runs`：创建异步 Run，写 MySQL 后由 `TaskExecutor` 后台执行。
- `GET /api/teams/{teamId}/runs/{runId}`：轮询 Run、Step、Event、Reviewer 决策、Mermaid 和最终报告。
- `POST /api/teams/{teamId}/runs/{runId}/resume`：Reviewer 要求澄清后提交用户补充信息并继续 Run。

Reviewer 是状态机质量闸门：规划后审查 Planner 的 Step，执行后对照初始需求审查 Executor 原始结果。
Reviewer 可返回 `CONTINUE`、`RETRY` 或 `ASK_CLARIFICATION`；`RETRY` 只重跑指定 Step。

不要让 Agent 之间直接互调。Team 协作上下文通过 Run / Step / Event 黑板传递，统一由 Team 运行时服务编排。
Team 内部的 Planner / Executor / Reviewer LLM 调用走 `AgentOrchestrator.executeInternal`，不读取或写入普通聊天会话历史；前端只通过 Team Run 看板展示黑板数据和最终报告。

## 新增 API 检查表

新增接口前检查：

- 是否已有对应 Application Service；没有就先补一个，不要把流程写进 Controller。
- 请求体和响应体是否在 DTO 包中定义清楚。
- 是否涉及持久化；涉及就确认 MySQL 表或 Repository。
- 是否涉及运行时索引；涉及就确认写库成功后再同步运行时。
- 是否涉及前端跨页面状态；涉及就同步设计 Pinia store。
- 是否需要更新 `docs/API.md`、根 `README.md` 或本 harness。
