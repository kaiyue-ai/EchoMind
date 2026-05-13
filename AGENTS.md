# AGENTS.md - EchoMind 协作入口

本文档是给在本仓库工作的 AI Agent 和开发者看的第一入口。它只保留最高优先级规则和读项目路线；详细架构、API 边界、数据库约束、当前进度请读 `docs/harness`。

## 先读哪里

按这个顺序读项目：

1. `README.md`：项目能力、运行方式和主调用链路。
2. `docs/harness/AGENTS.md`：项目阅读 harness，包含模块地图和硬约束。
3. `docs/harness/src/api/ARCHITECTURE.md`：API 层、Application Service、运行时边界。
4. `docs/harness/src/db/CONSTRAINTS.md`：MySQL、Redis Stack、OSS 和运行时索引的职责边界。
5. `docs/harness/PROGRESS.md`：当前进度、已知问题、下一步建议。

## 项目定位

EchoMind 是一个 Java 17 / Spring Boot 3.3 + Vue 3 的 AI Agent 平台。

当前架构是：

- 管理面：MVC Controller + Application Service。
- 智能执行：`AgentOrchestrator -> Agent -> ExecutionPipeline`。
- 工具能力：已启用 Skill 和已挂载外部 MCP 工具统一进入 `CapabilityRegistry`。
- 记忆和知识库：MySQL 保存事实，Redis Stack 做近期上下文和向量检索。
- 前端：Vue 3 + Pinia，跨页面状态必须放 store，不要只放组件局部变量。

## 最高优先级规则

- 不要把 `AgentFactory`、`SkillRegistry`、`CapabilityRegistry`、`ConcurrentHashMap` 当持久化来源。
- MySQL 是 Agent、Skill 状态、完整会话历史、知识库元数据的事实来源。
- Redis Stack 是缓存和向量索引，不是完整业务数据的唯一来源。
- 对话记忆按 `sessionId` 隔离，不要改回“每个 Agent 一份记忆”。
- 主项目只接入外部 MCP Server，不恢复“主项目暴露 MCP Server”的旧功能。
- Skill 只进入 Agent 工具视图，不要自动暴露成 MCP Server。
- 禁用 Skill 或卸载 MCP 后，必须同步移除 `CapabilityRegistry` 中的工具。
- Controller 只做 HTTP 适配；业务流程放 Application Service；执行能力放运行时模块。
- 单 Agent 执行逻辑放 `echomind-agent`；团队协作编排放 `echomind-agent-team`。
- 不要回滚不属于当前任务的改动。工作区可能本来就是脏的。
- 改接口、调用链路、架构边界时，同步更新 `README.md`、`CLAUDE.md` 或 `docs/harness`。

## 常用命令

后端：

```powershell
mvn.cmd -q test
mvn.cmd -q -DskipTests compile
mvn.cmd -q clean package "-Dmaven.test.skip=true"
```

前端：

```powershell
cd D:\claudeWorkSpace\ai-agent\echomind-web
npm.cmd run build
```

部署：

```powershell
cd D:\claudeWorkSpace\ai-agent
mvn.cmd -q clean package "-Dmaven.test.skip=true"
docker build -f Dockerfile.runtime -t ai-agent-backend:latest .
docker build -t ai-agent-frontend:latest .\echomind-web
docker compose up -d backend frontend
```

部署后验证：

```powershell
Invoke-RestMethod http://localhost:8080/actuator/health
Invoke-RestMethod http://localhost:8080/api/models
Invoke-RestMethod http://localhost:8080/api/mcp/servers
Invoke-RestMethod http://localhost:8080/api/mcp/tools
```

也可以使用 harness 命令入口：

```powershell
cd D:\claudeWorkSpace\ai-agent\docs\harness
make check
make test
make deploy
```

当前 Windows 环境不一定安装 `make`；没有时直接复制 `docs/harness/Makefile` 里的命令执行。

## 修改完成前检查

- 代码是否符合模块边界。
- 是否误改无关文件。
- 是否把事实数据落到了 MySQL。
- 是否清理或同步了运行时索引。
- 是否更新了对应文档。
- 是否跑了必要测试。
- 是否需要重建前端或后端镜像。

