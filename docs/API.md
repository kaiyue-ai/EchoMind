# EchoMind API 接口文档

> Base URL: `http://localhost:8080/api`
> Content-Type: `application/json`
> 所有API返回标准JSON格式，错误时附带 `error` 字段

---

## 1. 聊天接口 `/api/chat`

### POST `/api/chat` — 提交异步聊天
```json
// 请求
{
  "agentId": "default",
  "sessionId": "uuid-optional",
  "message": "北京今天天气怎么样？",
  "modelId": "deepseek:deepseek-v4-flash"
}

// 响应
{
  "sessionId": "a1b2c3d4-...",
  "requestId": "7f8e9d10-...",
  "traceId": "1f2e3d4c..."
}
```

`POST /api/chat` 只负责入队并立即返回。前端或调用方拿到 `requestId` 后，通过
`GET /api/chat/stream/{requestId}` 订阅 `meta`、`token`、`tool_start`、`tool_end`、`result`
或 `failure` SSE 事件。
后端实际链路是 `ChatRabbitProducer -> echomind.chat.requests -> ChatRabbitConsumer`；
消费者执行流式 Agent 后，再把事件发布到 `echomind.chat.stream-events`，由 `SsePushService`
消费并转发给 SSE 订阅方。

### GET `/api/chat/stream/{requestId}` — 订阅异步聊天事件
```text
event: meta
data: {"sessionId":"a1b2c3d4-...","traceId":"1f2e3d4c..."}

event: token
data: {"token":"北京"}

event: tool_start
data: {"toolName":"calculator"}

event: tool_end
data: {"toolName":"calculator","durationMs":128}

event: result
data: {"requestId":"7f8e9d10-...","sessionId":"a1b2c3d4-...","agentId":"default","modelId":"deepseek:deepseek-v4-flash","response":"北京今天..."}

event: failure
data: {"error":"模型调用失败","traceId":"1f2e3d4c..."}
```

### POST `/api/chat/sync` — 同步执行聊天
```json
// 请求
{
  "agentId": "default",
  "sessionId": "uuid-optional",
  "message": "北京今天天气怎么样？",
  "modelId": "deepseek:deepseek-v4-flash"
}

// 响应
{
  "sessionId": "a1b2c3d4-...",
  "agentId": "default",
  "modelId": "deepseek:deepseek-v4-flash",
  "traceId": "1f2e3d4c...",
  "response": "北京今天晴天，22°C...",
  "skillResults": ["[weather-query]: Weather for Beijing: Sunny, 22C"],
  "tokenUsage": {
    "promptTokens": 120,
    "completionTokens": 60,
    "totalTokens": 180
  }
}
```

### GET `/api/chat/sessions` — 获取会话摘要列表
```json
// 响应
[
  {
    "sessionId": "a1b2c3d4-...",
    "title": "北京天气",
    "lastMessage": "北京今天晴天...",
    "updatedAt": "2026-05-06T08:00:02Z"
  }
]
```

### GET `/api/chat/{sessionId}/history` — 获取会话历史
```json
// 响应
[
  { "role": "user", "content": "你好", "timestamp": "2026-05-06T08:00:00Z" },
  { "role": "assistant", "content": "你好！有什么可以帮助你的？", "timestamp": "2026-05-06T08:00:02Z" }
]
```

### DELETE `/api/chat/{sessionId}` — 删除单条会话历史
```json
// 响应
{
  "status": "deleted",
  "sessionId": "a1b2c3d4-..."
}
```

---

## 2. 模型接口 `/api/models`

### GET `/api/models` — 列出所有模型
```json
// 响应
[
  {
    "providerId": "deepseek",
    "modelName": "deepseek-v4-flash",
    "capabilities": ["TEXT", "FUNCTION"],
    "default": true
  },
  {
    "providerId": "aliyun-bailian",
    "modelName": "qwen3.7-max",
    "capabilities": ["TEXT", "FUNCTION"],
    "default": true
  },
  {
    "providerId": "mock",
    "modelName": "mock-model",
    "capabilities": ["TEXT", "FUNCTION"],
    "default": false
  }
]
```

### PUT `/api/models/switch` — 切换模型
```json
// 请求
{
  "providerId": "deepseek",
  "modelName": "deepseek-v4-flash"
}

// 响应
{
  "status": "switched",
  "providerId": "deepseek",
  "modelName": "deepseek-v4-flash"
}
```

---

## 3. Skill 接口 `/api/skills`

### GET `/api/skills` — 列出所有Skill
```json
// 响应
[
  {
    "skillId": "weather-query@1.0.0",
    "metadata": {
      "name": "weather-query",
      "version": "1.0.0",
      "description": "Get weather and forecast for a city",
      "tags": ["weather", "forecast"],
      "author": "EchoMind"
    },
    "state": "ENABLED"
  }
]
```

### POST `/api/skills/upload` — 上传Skill JAR
- Content-Type: `multipart/form-data`
- 字段: `file` — Skill JAR文件
```json
// 响应: SkillEntity 实体对象
{
  "id": "uuid",
  "name": "my-skill",
  "version": "1.0.0",
  "description": "My custom skill",
  "state": "LOADED",
  "createdAt": "2026-05-06T08:00:00Z"
}
```

### POST `/api/skills/{skillId}/enable` — 启用Skill
```json
{ "status": "enabled", "skillId": "weather-query@1.0.0" }
```

### POST `/api/skills/{skillId}/disable` — 禁用Skill
```json
{ "status": "disabled", "skillId": "weather-query@1.0.0" }
```

### DELETE `/api/skills/{skillId}` — 删除Skill
- 状态码: `204 No Content`

---

## 4. Agent 接口 `/api/agents`

### GET `/api/agents` — 列出所有Agent
```json
// 响应
[
  {
    "agentId": "default",
    "name": "EchoMind Assistant",
    "systemPrompt": "You are a helpful AI assistant...",
    "defaultModelId": "deepseek:deepseek-v4-flash",
    "skillIds": ["weather-query", "calculator", "date-query"]
  }
]
```

### POST `/api/agents` — 创建Agent
```json
// 请求
{
  "agentId": "my-agent",
  "name": "自定义助手",
  "systemPrompt": "你是一个Java开发专家...",
  "modelId": "deepseek:deepseek-v4-flash",
  "skillIds": ["calculator"]
}

// 响应: Agent 实体
```

### POST `/api/agents/{agentId}/execute` — 执行Agent
```json
// 请求
{
  "message": "1+1等于几？",
  "sessionId": "optional-uuid"
}

// 响应
{
  "sessionId": "uuid",
  "response": "1+1等于2"
}
```

---

## 5. MCP 接口 `/api/mcp`

### GET `/api/mcp/servers` — 已挂载外部 MCP 服务
```json
[
  {
    "id": "nowcoder-java-interview",
    "transport": "stdio",
    "command": ["java", "-jar", "/app/mcp/nowcoder-java-interview-mcp-server-1.0.0.jar"],
    "workingDirectory": "/app/mcp",
    "url": null,
    "endpoint": null,
    "running": true,
    "toolCount": 1,
    "tools": [],
    "mountedAt": "2026-05-10T12:00:00Z",
    "error": null
  }
]
```

### POST `/api/mcp/servers` — 动态挂载外部 MCP 服务
```json
// 请求
{
  "id": "demo",
  "transport": "stdio",
  "command": ["java", "-jar", "/app/mcp/demo.jar"],
  "workingDirectory": "/app/mcp"
}
```

远程 MCP 可使用 SSE 或 Streamable HTTP：

```json
{
  "id": "remote-docs",
  "transport": "streamable-http",
  "url": "https://mcp.example.com",
  "endpoint": "/mcp",
  "headers": {
    "Authorization": "Bearer token"
  }
}
```

### POST `/api/mcp/servers/{serverId}/refresh` — 刷新工具列表
```json
// 响应: ExternalMcpServerStatus
```

### DELETE `/api/mcp/servers/{serverId}` — 卸载外部 MCP 服务
```json
// 响应: 卸载前的 ExternalMcpServerStatus
```

### GET `/api/mcp/tools` — 列出外部 MCP 工具
```json
// 响应
[
  {
    "name": "fetch_nowcoder_java_interview_article",
    "description": "抓取牛客网 Java 面经文章。传 url 时抓指定文章；不传 url 时随机抓取公开 Java/后端面经。",
    "inputSchema": {
      "type": "object",
      "properties": {
        "url": { "type": "string", "description": "牛客网文章地址，必须是 www.nowcoder.com 下的链接" },
        "random": { "type": "boolean", "description": "是否随机抓取；url 为空时自动随机抓取" },
        "keyword": { "type": "string", "description": "随机抓取筛选关键词，例如 Java、后端、Spring、美团" },
        "maxAttempts": { "type": "integer", "description": "随机候选详情页最大尝试次数，默认 3，最大 8" },
        "includeHtml": { "type": "boolean", "description": "是否额外返回简单 HTML 版本" }
      }
    }
  }
]
```

### POST `/api/mcp/tools/{toolName}/call` — 调用外部 MCP 工具
```json
// 请求
{
  "arguments": {
    "random": true,
    "keyword": "Java",
    "maxAttempts": 3
  }
}

// 响应: ToolResult
{
  "content": [{ "type": "text", "text": "# 腾讯云智后台开发一面，base武汉\n\n- 来源：https://www.nowcoder.com/feed/main/detail/..." }],
  "isError": false
}
```

---

## 7. Agent Team 接口 `/api/teams`

Team 定义和每一次 Run 都按当前登录用户写入 MySQL 黑板，不进入普通聊天会话历史。
用户只能列出、执行和删除自己拥有的 Team；旧无 token 请求归 `default` 用户。
状态推进由 `TaskExecutor` 后台异步执行，前端 Team 看板用 0.25 秒轮询读取 Run/Step/Event。

### GET `/api/teams` — 列出当前用户的团队
```json
// 响应
[
  {
    "teamId": "uuid",
    "ownerUserId": "user-a",
    "name": "活动策划团队",
    "roles": ["PLANNER", "EXECUTOR", "REVIEWER"],
    "members": [
      {
        "agentId": "default",
        "agentName": "EchoMind Assistant",
        "role": "PLANNER",
        "capabilityTags": ["planning"],
        "sortOrder": 10
      }
    ]
  }
]
```

### POST `/api/teams` — 为当前用户创建团队
```json
// 请求
{
  "name": "活动策划团队",
  "members": [
    { "agentId": "default", "role": "PLANNER", "capabilityTags": ["planning"], "sortOrder": 10 },
    { "agentId": "default", "role": "EXECUTOR", "capabilityTags": ["search", "venue"], "sortOrder": 20 },
    { "agentId": "default", "role": "EXECUTOR", "capabilityTags": ["weather"], "sortOrder": 30 },
    { "agentId": "default", "role": "REVIEWER", "capabilityTags": ["review", "report"], "sortOrder": 40 }
  ]
}

// 响应
{
  "teamId": "uuid",
  "name": "活动策划团队",
  "roles": ["PLANNER", "EXECUTOR", "REVIEWER"],
  "members": []
}
```

### DELETE `/api/teams/{teamId}` — 硬删除团队
直接删除团队定义及其成员、Run、Step、Event 黑板记录，不做逻辑删除。

```http
204 No Content
```

### POST `/api/teams/{teamId}/runs` — 创建异步团队 Run
```json
// 请求
{ "task": "策划一场60人户外团建活动" }

// 响应
{
  "runId": "uuid",
  "teamId": "uuid",
  "userId": "current-user-id",
  "task": "策划一场60人户外团建活动",
  "status": "PENDING",
  "taskLevel": "COMPLEX",
  "steps": [],
  "events": []
}
```

### GET `/api/teams/{teamId}/runs` — 查询当前用户在该团队下的 Run
```json
[
  {
    "runId": "uuid",
    "teamId": "uuid",
    "userId": "current-user-id",
    "status": "COMPLETED",
    "taskLevel": "COMPLEX"
  }
]
```

### GET `/api/team-runs` — 查询当前用户所有 Team Run 历史
该接口与 `/api/chat/sessions` 分离，用于前端单独展示团队协作历史。

### GET `/api/teams/{teamId}/runs/{runId}` — 查询 Run 黑板
```json
{
  "runId": "uuid",
  "status": "EXECUTING",
  "taskLevel": "COMPLEX",
  "clarificationStage": null,
  "planReviewJson": "{\"action\":\"CONTINUE\"}",
  "resultReviewJson": null,
  "mergeOutput": null,
  "globalReviewJson": null,
  "conflictReportJson": null,
  "arbitrationJson": null,
  "finalOutput": null,
  "mermaidDiagram": null,
  "planRetryCount": 0,
  "resultReplanCount": 0,
  "partialReplanCount": 0,
  "fullReplanCount": 0,
  "arbitrationCount": 0,
  "steps": [
    {
      "stepId": "step-1-abcd",
      "clientStepId": "venue",
      "title": "查询活动场地",
      "dependsOnStepIds": [],
      "riskLevel": "LOW",
      "qualityStatus": "PENDING",
      "assignedAgentId": "default",
      "status": "RUNNING",
      "reflectionJson": null,
      "retryCount": 0
    }
  ],
  "events": [
    { "type": "STEP_STARTED", "actorRole": "EXECUTOR", "message": "Executor started step" }
  ]
}
```

Planner 输出 DAG Step：`clientStepId` 是计划内稳定 ID，`dependsOn` 表示依赖关系；后端保存为
`dependsOnStepIds` 后，只调度依赖已完成的 Step，能并发的 Step 会并发执行。
Planner 不在计划里硬指定 Agent；执行前 `AgentSelector` 会把候选 Executor、能力标签和能力匹配分交给模型自主选择，
模型选择失败或返回无效候选时才按能力匹配分与 `sortOrder` 稳定兜底。
`RiskPolicy` 是唯一风险裁决入口，裁决结果会写入 `RISK_DECIDED` 事件。

Reviewer 决策支持 `CONTINUE`、`RETRY`、`PARTIAL_REPLAN`、`REPLAN`、`ASK_CLARIFICATION`、`FAILED`：
`RETRY` 重跑指定 Step，`PARTIAL_REPLAN` 重跑局部 DAG 分支，`REPLAN` 回到 Planner 做整体重规划。
每次重试都会把 Reviewer 错误原因、修改意见、上一轮输出摘要写入 `reflectionJson`，再带给 Executor。
MergeAgent 后会写入 `conflictReportJson`；存在冲突时 Planner 仲裁结果写入 `arbitrationJson`，MergeAgent 带仲裁结果二次聚合。

### POST `/api/teams/{teamId}/runs/{runId}/resume` — 提交澄清并继续
```json
// 请求
{ "clarificationAnswer": "活动日期是下周五，预算每人300元以内。" }
```

---

## 错误响应格式

所有API在发生错误时返回统一的JSON格式：
```json
{
  "error": "错误描述信息"
}
```

HTTP 状态码：
- `200` — 成功
- `204` — 成功（无内容，如DELETE）
- `400` — 请求参数错误
- `500` — 服务器内部错误

---

## 环境变量

| 变量 | 必填 | 说明 |
|---|---|---|
| `DEEPSEEK_API_KEY` | 是 | DeepSeek API 密钥 |
| `DEEPSEEK_BASE_URL` | 是 | DeepSeek Chat Completions API 地址，默认 `https://api.deepseek.com` |
| `ALIYUN_BAILIAN_API_KEY` | 否 | 阿里云百炼 API Key，启用 `aliyun-bailian` 模型列表 |
| `ALIYUN_BAILIAN_BASE_URL` | 否 | 阿里云百炼 OpenAI-compatible 地址，默认 `https://dashscope.aliyuncs.com/compatible-mode/v1` |

---

## 快速测试

```bash
# 发送聊天消息
curl -X POST http://localhost:8080/api/chat \
  -H "Content-Type: application/json" \
  -d '{"agentId":"default","message":"北京今天天气怎么样？"}'

# 列出Skill
curl http://localhost:8080/api/skills

# 列出模型
curl http://localhost:8080/api/models

# 查询记忆

# 列出外部MCP工具
curl http://localhost:8080/api/mcp/tools

# 执行Agent Team任务
curl -X POST http://localhost:8080/api/teams \
  -H "Content-Type: application/json" \
  -d '{"name":"测试团队","members":[{"agentId":"default","role":"PLANNER","capabilityTags":["planning"],"sortOrder":10},{"agentId":"default","role":"EXECUTOR","capabilityTags":["general"],"sortOrder":20},{"agentId":"default","role":"REVIEWER","capabilityTags":["review"],"sortOrder":30}]}'

curl -X POST http://localhost:8080/api/teams/{teamId}/runs \
  -H "Content-Type: application/json" \
  -d '{"task":"策划一场60人户外团建活动"}'
```
