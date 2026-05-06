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
  "modelId": "anthropic:claude-sonnet-4-20250514",
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

---

## 2. 模型接口 `/api/models`

### GET `/api/models` — 列出所有模型
```json
// 响应
[
  {
    "providerId": "anthropic",
    "modelName": "claude-sonnet-4-20250514",
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
  "providerId": "anthropic",
  "modelName": "claude-opus-4-7"
}

// 响应
{
  "status": "switched",
  "providerId": "anthropic",
  "modelName": "claude-opus-4-7"
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
    "defaultModelId": "anthropic:claude-sonnet-4-20250514",
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
  "modelId": "anthropic:claude-sonnet-4-20250514",
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

### GET `/api/mcp/server` — 服务器信息
```json
{
  "name": "EchoMind-MCP",
  "version": "1.0.0",
  "toolCount": 4
}
```

### GET `/api/mcp/tools` — 列出MCP工具
```json
// 响应
[
  {
    "name": "read_file",
    "description": "Read content of a file",
    "inputSchema": {
      "type": "object",
      "properties": {
        "path": { "type": "string", "description": "File path" }
      },
      "required": ["path"]
    }
  }
]
```

### POST `/api/mcp/tools/{toolName}/call` — 调用MCP工具
```json
// 请求
{
  "arguments": {
    "path": "/tmp/test.txt"
  }
}

// 响应: ToolResult
{
  "content": [{ "type": "text", "text": "File contents here..." }],
  "isError": false
}
```

### POST `/api/mcp/register-skill/{skillId}` — 注册Skill为MCP工具
```json
// 响应
{
  "status": "registered",
  "skillId": "filesystem@1.0.0",
  "toolCount": "4"
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
    "roles": ["PLANNER", "EXECUTOR", "REVIEWER"]
  }
]
```

### POST `/api/teams` — 创建团队
```json
// 请求
{
  "name": "活动策划团队",
  "plannerId": "default",
  "executorId": "default",
  "reviewerId": "default"
}

// 响应
{
  "teamId": "uuid",
  "name": "活动策划团队",
  "roles": ["PLANNER", "EXECUTOR", "REVIEWER"]
}
```

### POST `/api/teams/{teamId}/execute` — 执行团队任务
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
| `ANTHROPIC_API_KEY` | 是 | Anthropic API 密钥 |
| `ANTHROPIC_BASE_URL` | 是 | Anthropic API 地址 |
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

# 列出MCP工具
curl http://localhost:8080/api/mcp/tools

# 执行Agent Team任务
curl -X POST http://localhost:8080/api/teams \
  -H "Content-Type: application/json" \
  -d '{"name":"测试团队","plannerId":"default","executorId":"default"}'

curl -X POST http://localhost:8080/api/teams/{teamId}/execute \
  -H "Content-Type: application/json" \
  -d '{"task":"策划一场60人户外团建活动"}'
```
