# CLAUDE.md - EchoMind Claude 入口

本文件是 Claude / Claude Code 进入 EchoMind 项目时的轻量上下文。详细工程规则不要在这里重复维护，统一看 `AGENTS.md` 和 `docs/harness`。

## 必读文件

1. `AGENTS.md`：最高优先级规则和常用命令。
2. `docs/harness/AGENTS.md`：项目阅读 harness。
3. `docs/harness/src/api/ARCHITECTURE.md`：API 和应用服务边界。
4. `docs/harness/src/db/CONSTRAINTS.md`：数据库和存储硬约束。
5. `docs/harness/PROGRESS.md`：当前进度、已知问题和下一步建议。

## 项目速览

EchoMind 是 Java 17 / Spring Boot 3.3 + Vue 3 的 AI Agent 平台。

核心模块：

- `echomind-common`：公共模型、异常、Schema 校验。
- `echomind-skill-api`：Skill SPI。
- `echomind-skill`：Skill 加载、注册、热加载、市场状态。
- `echomind-llm`：模型 Provider 和模型路由。
- `echomind-memory`：会话记忆、Redis Stack 向量检索、Agent 知识库。
- `echomind-mcp`：外部 MCP 客户端和 stdio 通信。
- `echomind-agent`：单 Agent 执行、Pipeline、能力注册表。
- `echomind-agent-team`：多 Agent 团队协作编排。
- `echomind-console`：REST Controller、Application Service、CLI。
- `echomind-boot`：Spring Boot 自动装配。
- `echomind-app`：应用入口和配置文件。
- `echomind-web`：Vue 3 前端。
- `skills/*`：内置 Skill 示例。

## 当前架构边界

```text
Controller / CLI
  -> Application Service
  -> Runtime Orchestrator / Pipeline / CapabilityRegistry
  -> Repository / Memory / LLM Provider / Skill / MCP
```

关键规则：

- Controller 不直接拼业务流程。
- Application Service 负责校验、持久化顺序和运行时同步。
- `AgentFactory`、`SkillRegistry`、`CapabilityRegistry` 只是运行时索引。
- MySQL 保存业务事实；Redis Stack 保存缓存和可重建向量索引。
- 主项目只接入外部 MCP Server，不暴露自身 MCP Server。
- 对话记忆按 `sessionId` 隔离。

## 常用命令

```powershell
cd D:\claudeWorkSpace\ai-agent
mvn.cmd -q -DskipTests compile
mvn.cmd -q test
```

```powershell
cd D:\claudeWorkSpace\ai-agent\echomind-web
npm.cmd run build
```

```powershell
cd D:\claudeWorkSpace\ai-agent
mvn.cmd -q clean package "-Dmaven.test.skip=true"
docker build -f Dockerfile.runtime -t ai-agent-backend:latest .
docker build -t ai-agent-frontend:latest .\echomind-web
docker compose up -d backend frontend
```

部署后：

```powershell
Invoke-RestMethod http://localhost:8080/actuator/health
Invoke-RestMethod http://localhost:8080/api/models
Invoke-RestMethod http://localhost:8080/api/mcp/servers
Invoke-RestMethod http://localhost:8080/api/mcp/tools
```

## 维护提醒

- 本文件只放 Claude 工具入口信息，不再维护长篇架构细节。
- 架构和调用链路变化优先更新 `docs/harness`，必要时再同步 `README.md`。
- 如果发现本文件和 `AGENTS.md`、`docs/harness` 冲突，以 `AGENTS.md` 和 `docs/harness` 为准。

