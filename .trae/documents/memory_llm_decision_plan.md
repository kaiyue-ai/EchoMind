# LLM 自主决策记忆生成 + 降级机制 + 向量合并 实现计划

## 一、需求分析

### 1.1 核心需求

| 需求点 | 描述 |
|--------|------|
| **LLM 自主决策** | 主 LLM 在返回回答时顺便返回是否存储记忆的决策 |
| **降级机制** | 主 LLM 输出异常时，自动调用异步存储 |
| **时间戳** | 用户事实必须加时间戳，让 LLM 能判断事实重要性 |
| **向量合并** | 相近向量数据自动合并，轻量级 LLM 判断，最新数据优先 |

### 1.2 现有问题分析

| 问题 | 当前状态 | 需求 |
|------|---------|------|
| 记忆决策 | 规则驱动 | LLM 驱动 |
| 降级机制 | 无 | 主 LLM 异常时降级到异步 |
| 时间戳 | UserMemoryEntry 无时间戳字段 | 必须添加 |
| 向量合并 | 无 | 相近向量自动合并 |

---

## 二、架构设计

### 2.1 核心流程

```
┌─────────────────────────────────────────────────────────────────────────┐
│                        主对话流程                                     │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                     │
│  用户消息 ──→ 主 LLM ──→ 解析决策                                    │
│                             │                                        │
│              ┌──────────────┼──────────────┐                         │
│              ▼              ▼              ▼                         │
│         决策=TRUE      决策=解析失败      决策=FALSE                   │
│              │              │              │                         │
│              │              │              ▼                         │
│              │              │         直接返回回答                     │
│              │              │                                        │
│              │              ▼                                        │
│              │         异步存储流程                                   │
│              │         (轻量级 LLM 提取事实)                          │
│              ▼                                        │             │
│         同步存储到 Milvus                              │             │
│              │                                        │             │
│              └───────────────────┬────────────────────┘             │
│                                  ▼                                 │
│                         向量合并检测                                 │
│                         (轻量级 LLM 判断)                            │
│                                                                     │
└─────────────────────────────────────────────────────────────────────────┘
```

### 2.2 关键组件

| 组件 | 职责 |
|------|------|
| `MemoryDecisionParser` | 解析主 LLM 返回的决策信息 |
| `UserMemoryEntry` | 数据结构，新增时间戳字段 |
| `MemoryMergeService` | 向量相似度检测与合并 |
| `AsyncMemoryWorker` | 异步存储降级处理 |

---

## 三、实施步骤

### 阶段一：修改数据结构（1天）

1. **修改 `UserMemoryEntry.java`** - 新增 `timestamp` 和 `source` 字段
2. **修改 Milvus 集合定义** - 添加时间戳字段

### 阶段二：实现 LLM 决策解析（1天）

1. **创建 `MemoryDecisionParser.java`** - 解析 `<memory_decision>` 标签
2. **修改提示词模板** - 添加记忆决策要求
3. **修改 `AgentOrchestrator.java`** - 集成决策解析

### 阶段三：实现降级机制（1天）

1. **修改 `AsyncMemoryWorker.java`** - 处理降级存储
2. **修改 `UserMemoryService.java`** - 删除自动冲刷，保留异步入口

### 阶段四：实现向量合并（1.5天）

1. **创建 `MemoryMergeService.java`** - 向量相似度检测
2. **创建 `MemoryMergePrompt.java`** - 轻量级 LLM 判断提示词
3. **修改 `MilvusUserMemoryStore.java`** - 集成合并逻辑

### 阶段五：删除冲刷机制（0.5天）

1. **删除 `UserMemoryIdleFlushScheduler.java`**
2. **修改 `MemoryManager.java`** - 删除自动摘要刷新

### 阶段六：测试与验证（1天）

---

## 四、关键代码设计

### 4.1 UserMemoryEntry 数据结构

```java
public record UserMemoryEntry(
    String sessionId,
    String entryId,
    UserMemoryCategory category,
    String content,
    String evidence,
    double confidence,
    double[] embedding,
    Instant timestamp,      // 新增：时间戳
    String source           // 新增：来源（sync/async）
) {}
```

### 4.2 MemoryDecisionParser

```java
public class MemoryDecisionParser {
    private static final Pattern PATTERN = 
        Pattern.compile("<memory_decision>(.*?)</memory_decision>", Pattern.DOTALL);
    
    public MemoryDecision parse(String llmResponse) {
        Matcher matcher = PATTERN.matcher(llmResponse);
        
        if (matcher.find()) {
            try {
                return objectMapper.readValue(matcher.group(1), MemoryDecision.class);
            } catch (Exception e) {
                return MemoryDecision.FALLBACK;  // 解析失败，返回降级标记
            }
        }
        
        return MemoryDecision.FALLBACK;
    }
}

public record MemoryDecision(
    boolean shouldSave,
    String reasoning,
    boolean isFallback  // 是否是降级模式
) {
    public static final MemoryDecision FALLBACK = new MemoryDecision(false, "解析失败", true);
}
```

### 4.3 向量合并逻辑

```java
public class MemoryMergeService {
    public void mergeIfSimilar(String sessionId, UserMemoryEntry newEntry) {
        // 1. 检索相似向量
        List<UserMemoryHit> similarHits = userMemoryStore.search(
            sessionId, newEntry.embedding(), 5, 0.85);
        
        // 2. 对相似向量进行合并判断
        for (UserMemoryHit hit : similarHits) {
            if (shouldMerge(newEntry, hit)) {
                // 3. 合并：保留最新数据，更新内容
                mergeEntries(sessionId, hit.entryId(), newEntry);
                return;
            }
        }
        
        // 4. 不相似则新增
        userMemoryStore.save(newEntry);
    }
    
    private boolean shouldMerge(UserMemoryEntry newEntry, UserMemoryHit existing) {
        // 调用轻量级 LLM 判断是否应该合并
        String prompt = buildMergePrompt(newEntry, existing);
        String decision = lightLlm.generate(prompt);
        return "YES".equalsIgnoreCase(decision.trim());
    }
}
```

### 4.4 提示词模板

```
【记忆决策】
分析以上对话，判断是否需要保存用户记忆：

规则：
1. 事实性陈述、长期偏好、重要背景 → 保存
2. 临时性问题、闲聊、一次性指令 → 不保存

输出格式：
<memory_decision>
{"shouldSave": true/false, "reasoning": "理由"}
</memory_decision>
```

---

## 五、风险评估

| 风险 | 概率 | 影响 | 缓解措施 |
|------|------|------|---------|
| LLM 决策不稳定 | 中 | 记忆质量波动 | 降级到异步存储作为兜底 |
| 向量合并错误 | 低 | 信息丢失 | 保留原始数据，只合并重复项 |
| 性能下降 | 低 | 响应延迟 | 向量合并异步执行 |
| 存储膨胀 | 中 | 存储空间增长 | 时间戳+相似度双重去重 |

---

## 六、验收标准

1. ✅ 主 LLM 能在回答时附带记忆决策
2. ✅ 决策解析失败时自动降级到异步存储
3. ✅ UserMemoryEntry 包含时间戳字段
4. ✅ 相近向量能自动合并（轻量级 LLM 判断）
5. ✅ 自动冲刷机制已移除
6. ✅ 测试覆盖率 >= 80%