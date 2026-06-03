# 项目包结构优化计划

## 一、现状分析

### 1.1 当前包结构问题

根据对项目结构的分析，主要问题集中在以下几个方面：

#### (1) `echomind-agent/pipeline/stages` 包过于臃肿

```
stages/
├── ContextEnrichStage.java      # 阶段类
├── MemoryPersistStage.java      # 阶段类
├── PromptComposer.java          # Prompt组合器（非阶段）
├── ToolExposure.java            # 工具暴露数据类（非阶段）
├── ToolExposurePlanner.java     # 工具暴露规划器（非阶段）
├── KnowledgeRetrievalStage.java # 知识检索阶段
├── UserMemoryRetrievalStage.java# 用户记忆检索阶段
├── ModelResolutionStage.java    # 模型解析阶段
├── AttachmentPreparationStage.java # 附件准备阶段
├── MultimodalGuardStage.java    # 多模态校验阶段
├── ResultAggregationStage.java  # 结果聚合阶段（核心）
├── ProviderRequestFactory.java  # 请求工厂（非阶段）
└── ProviderRequest.java         # 请求DTO（非阶段）
```

**问题**：阶段类、工厂类、数据类混合在一起，职责不清。

#### (2) `echomind-agent/tool` 包职责混杂

```
tool/
├── CapabilityRegistry.java      # 能力注册中心
├── Tool.java                    # 工具接口
├── ToolInvoker.java             # 工具执行器
├── ToolMatchScorer.java         # 工具匹配评分器
├── ToolResult.java              # 工具结果
├── ToolRouter.java              # 工具路由器
├── ToolRoutingMetadata.java     # 工具路由元数据
├── mcp/                         # MCP相关
│   └── ...
└── skill/                       # Skill相关
    └── ...
```

**问题**：工具执行器、匹配器、路由器职责分散。

### 1.2 模块划分问题

| 模块 | 当前状态 | 问题 |
|------|----------|------|
| `echomind-agent` | Agent核心逻辑 | pipeline/stages 职责混杂 |
| `echomind-llm` | LLM提供层 | 相对清晰 |
| `echomind-mcp` | MCP协议层 | 相对清晰 |
| `echomind-skill` | Skill管理 | 相对清晰 |
| `echomind-console` | REST API层 | 相对清晰 |

---

## 二、优化目标

1. **单一职责**：每个包只负责一类职责
2. **清晰分层**：按功能模块划分
3. **易于扩展**：新功能能方便地添加到合适位置
4. **符合直觉**：类的位置应该符合开发者直觉

---

## 三、优化方案

### 3.1 `echomind-agent/pipeline` 重构

**重构前**：
```
pipeline/
├── stages/                      # 所有阶段和辅助类混合
│   ├── *.java
```

**重构后**：
```
pipeline/
├── stages/                      # 纯阶段类
│   ├── ContextEnrichStage.java
│   ├── MemoryPersistStage.java
│   ├── ModelResolutionStage.java
│   ├── MultimodalGuardStage.java
│   ├── AttachmentPreparationStage.java
│   ├── ResultAggregationStage.java
│   ├── KnowledgeRetrievalStage.java
│   └── UserMemoryRetrievalStage.java
├── planning/                    # 规划类（非阶段）
│   ├── ToolExposure.java
│   ├── ToolExposurePlanner.java
│   └── MemoryDecisionParser.java
├── composing/                   # 组合类
│   ├── PromptComposer.java
│   └── PromptBudget.java
└── request/                     # 请求相关
    ├── ProviderRequestFactory.java
    └── ProviderRequest.java
```

### 3.2 `echomind-agent/tool` 重构

**重构前**：
```
tool/
├── *.java                       # 工具类混合
├── mcp/
└── skill/
```

**重构后**：
```
tool/
├── core/                        # 核心工具接口和结果
│   ├── Tool.java
│   ├── ToolResult.java
│   └── ToolRoutingMetadata.java
├── router/                      # 路由和匹配
│   ├── ToolRouter.java
│   ├── CapabilityRegistry.java
│   └── ToolMatchScorer.java
├── invoker/                     # 执行器
│   └── ToolInvoker.java
├── mcp/                         # MCP工具适配
│   └── ...
└── skill/                       # Skill工具适配
    └── ...
```

### 3.3 新增跨模块聚合层

**新增**：
```
agent/
├── orchestration/               # 编排层（不变）
├── pipeline/                    # 管道层（重构）
├── tool/                        # 工具层（重构）
├── memory/                      # 记忆层
│   ├── ChatMemoryPersistPublisher.java
│   └── ...
└── store/                       # 持久化层（不变）
```

---

## 四、实施步骤

### 步骤1：创建新目录结构

```bash
# 在 echomind-agent 模块下创建新目录
mkdir -p src/main/java/com/echomind/agent/pipeline/planning
mkdir -p src/main/java/com/echomind/agent/pipeline/composing
mkdir -p src/main/java/com/echomind/agent/pipeline/request
mkdir -p src/main/java/com/echomind/agent/tool/core
mkdir -p src/main/java/com/echomind/agent/tool/router
mkdir -p src/main/java/com/echomind/agent/tool/invoker
```

### 步骤2：移动文件并更新包声明

| 原路径 | 新路径 |
|--------|--------|
| `stages/PromptComposer.java` | `pipeline/composing/PromptComposer.java` |
| `stages/PromptBudget.java` | `pipeline/composing/PromptBudget.java` |
| `stages/ToolExposure.java` | `pipeline/planning/ToolExposure.java` |
| `stages/ToolExposurePlanner.java` | `pipeline/planning/ToolExposurePlanner.java` |
| `stages/MemoryDecisionParser.java` | `pipeline/planning/MemoryDecisionParser.java` |
| `stages/ProviderRequestFactory.java` | `pipeline/request/ProviderRequestFactory.java` |
| `Tool.java` | `tool/core/Tool.java` |
| `ToolResult.java` | `tool/core/ToolResult.java` |
| `ToolRoutingMetadata.java` | `tool/core/ToolRoutingMetadata.java` |
| `ToolRouter.java` | `tool/router/ToolRouter.java` |
| `CapabilityRegistry.java` | `tool/router/CapabilityRegistry.java` |
| `ToolMatchScorer.java` | `tool/router/ToolMatchScorer.java` |
| `ToolInvoker.java` | `tool/invoker/ToolInvoker.java` |

### 步骤3：更新所有导入引用

需要更新以下文件中的导入语句：
- `ResultAggregationStage.java`
- `AgentOrchestrator.java`
- `AgentFactory.java`
- `CapabilityRegistry.java`
- `SkillCapabilityService.java`
- `ExternalMcpRuntimeService.java`
- 所有测试文件

### 步骤4：更新配置文件

检查并更新 Spring Boot 自动配置类中的包扫描路径。

### 步骤5：运行测试验证

```powershell
mvn.cmd -q test
```

---

## 五、风险评估

| 风险 | 描述 | 应对措施 |
|------|------|----------|
| **编译错误** | 导入语句未更新 | 仔细检查所有导入 |
| **运行时错误** | Spring 组件扫描失败 | 更新包扫描配置 |
| **测试失败** | 测试类导入未更新 | 运行测试后修复 |
| **依赖问题** | 跨模块依赖路径变更 | 检查 pom.xml 依赖声明 |

---

## 六、预期效果

1. **职责清晰**：每个包只负责一类职责
2. **易于查找**：开发者能快速定位到目标类
3. **便于扩展**：新功能能按职责添加到对应包
4. **符合约定**：遵循"按功能分层"的通用约定

---

## 七、代码参考

当前结构：[echomind-agent](file:///d:\claudeWorkSpace\ai-agent\echomind-agent)

---

**计划版本**: v1.0  
**创建时间**: 2026-06-02