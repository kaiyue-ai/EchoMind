# EchoMind API 接口文档

> Base URL: `http://localhost:8080/api`  
> Content-Type: `application/json`  
> 所有API返回标准JSON格式，错误时附带 `error` 字段

---

## 1. 聊天接口 `/api/chat`

### POST `/api/chat` — 发送消息
```json
// 请求
{
  "agentId": "default",
  "sessionId": "uuid-optional",
  "message": "北京今天天气怎么样？"
}

// 响应
{
  "sessionId": "a1b2c3d4-...",
  "agentId": "default",
  "modelId": "deepseek:deepseek-v4-flash",
  "response": "北京今天晴天，22°C...",
  "skillResults": ["[weather-query]: Weather for Beijing: Sunny, 22C"]
}
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
// 响应: SkillRepository 实体对象
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
    "skillIds": ["weather-query", "calculator", "web-search"]
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

## 5. 记忆接口 `/api/memory`

### GET `/api/memory/{sessionId}` — 获取会话记忆
```json
// 响应: AgentMessage[]
[
  { "role": "user", "content": "...", "timestamp": "..." },
  { "role": "assistant", "content": "...", "timestamp": "..." }
]
```

### DELETE `/api/memory/{sessionId}` — 清除会话记忆
```json
{ "status": "cleared", "sessionId": "uuid" }
```

---

## 6. MCP 接口 `/api/mcp`

### GET `/api/mcp/servers` — 已挂载外部 MCP 服务
```json
[
  {
    "id": "nowcoder-java-interview",
    "transport": "stdio",
    "command": ["java", "-jar", "/app/mcp/nowcoder-java-interview-mcp-server-1.0.0.jar"],
    "workingDirectory": "/app/mcp",
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

### GET `/api/teams` — 列出所有团队
```json
// 响应
[
  {
    "teamId": "uuid",
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

### POST `/api/teams` — 创建团队
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
  "task": "策划一场60人户外团建活动",
  "status": "PENDING",
  "steps": [],
  "events": []
}
```

### GET `/api/teams/{teamId}/runs/{runId}` — 查询 Run 黑板
```json
{
  "runId": "uuid",
  "status": "EXECUTING",
  "clarificationStage": null,
  "planReviewJson": "{\"action\":\"CONTINUE\"}",
  "resultReviewJson": null,
  "finalOutput": null,
  "mermaidDiagram": null,
  "steps": [
    {
      "stepId": "step-1-abcd",
      "title": "查询活动场地",
      "assignedAgentId": "default",
      "status": "RUNNING",
      "retryCount": 0
    }
  ],
  "events": [
    { "type": "STEP_STARTED", "actorRole": "EXECUTOR", "message": "Executor started step" }
  ]
}
```

### POST `/api/teams/{teamId}/runs/{runId}/resume` — 提交澄清并继续
```json
// 请求
{ "clarificationAnswer": "活动日期是下周五，预算每人300元以内。" }
```

### POST `/api/teams/{teamId}/execute` — 执行团队任务
兼容旧同步入口；新版前端使用 `/runs` 创建异步任务并轮询查询。

```json
// 请求
{
  "task": "策划一场60人户外团建活动"
}

// 响应
{
  "teamId": "uuid",
  "status": "COMPLETED",
  "finalOutput": "完整的活动方案...",
  "stepResults": [
    "场地查询结果...",
    "天气查询结果...",
    "预算估算..."
  ],
  "mermaidDiagram": "sequenceDiagram\n    title ...\n    ..."
}
```

### GET `/api/teams/message-bus/pending` — 消息总线状态
```json
{ "pendingCount": 0 }
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
| `DEEPSEEK_BASE_URL` | 是 | DeepSeek 兼容 API 地址，默认 `https://api.deepseek.com/anthropic` |
| `OPENAI_API_KEY` | 否 | OpenAI API 密钥（可选） |

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
curl http://localhost:8080/api/memory/{sessionId}

# 列出外部MCP工具
curl http://localhost:8080/api/mcp/tools

# 执行Agent Team任务
curl -X POST http://localhost:8080/api/teams \
  -H "Content-Type: application/json" \
  -d '{"name":"测试团队","plannerId":"default","executorId":"default"}'

curl -X POST http://localhost:8080/api/teams/{teamId}/execute \
  -H "Content-Type: application/json" \
  -d '{"task":"策划一场60人户外团建活动"}'
```
