# 当前进度

最后更新：2026-05-11

## 已完成

- 项目已形成多模块结构：LLM、Memory、MCP、Skill、Agent、Agent Team、Console、Boot、Web 分开维护。
- 主项目已从“暴露 MCP Server”调整为“接入外部 MCP Server”。
- Skill 和外部 MCP 工具统一进入 `CapabilityRegistry`，供 Agent 对话时使用。
- 对话记忆已改回按 `sessionId` 隔离，不再按 Agent 共享记忆。
- MySQL 保存完整会话历史，Redis Stack 承担近期上下文、向量检索和用户长期画像。
- 已新增用户长期画像方案：主应用按 `sessionId` 从 Redis Stack 召回画像；`echomind-user-memory` 通过 RabbitMQ 异步提取画像并写 Redis Stack。
- Agent 知识库已支持文档上传、切片、向量化、检索和前端展示。
- 前端聊天状态已通过 Pinia 持有，避免普通页面跳转导致智能对话重置。
- 前端已支持 Markdown 渲染和代码块一键复制。
- 已新增 `markdown-code` Skill，用于约束代码输出使用 Markdown fenced code block。
- 阿里云 OSS 已接入上传文件和图片历史存储路径。
- 阿里云百炼多模态模型已纳入模型配置，模型能力中用 `vision` 区分是否支持图片。
- Docker Compose 已集成 OpenTelemetry Java Agent、OpenTelemetry Collector 和 SkyWalking，本地可直接查看后端 Trace。

## 进行中

- Agent Team 已进入异步黑板协作实现：Team、Run、Step、Event 落 MySQL，Reviewer 驱动规划审查、结果审查、重试和澄清。
- 持续压缩架构边界：Controller 做 HTTP 适配，Application Service 做用例编排，Runtime 做执行。
- 清理无用代码和过时注释，保持中文注释说明关键业务边界。
- 持续验证 SkyWalking 的 OTLP 兼容展示链路，仅覆盖后端 Trace，不扩展到指标和日志。

## 已知问题

- Agent Team 已支持 TaskExecutor 异步 Run 和 Step 级看板；后续可把后台执行替换为 RabbitMQ Consumer。
- Team 协作已支持基于能力标签的 Executor 分配；复杂依赖图、取消和单 Step 手动重试仍未实现。
- 部分历史功能经过多轮修改，仍需要继续清理过时文档、死代码和重复 DTO。
- Docker 后端部署依赖宿主机环境变量，重新部署前必须确认 LLM、OSS、百炼相关变量已加载。
- SkyWalking 当前为本地演示型部署，使用 Compose 内 MySQL 存储，不适合作为长期留存方案。

## 下一步建议

优先级从高到低：

1. Team Run 取消和手动 Step 重试。
2. Team 执行由 TaskExecutor 演进为 RabbitMQ Consumer。
3. Planner 输出增加依赖字段，支持有向无环图执行。
4. 多 Executor 能力匹配继续纳入 MCP 工具标签、知识库范围和模型能力。
5. 继续清理历史冗余代码，尤其是已废弃的 MCP Server 暴露逻辑和重复能力注册路径。

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
