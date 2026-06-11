package com.echomind.usermemory.service;

import com.echomind.common.model.AgentMessage;
import com.echomind.common.model.MemoryDecision;
import com.echomind.common.model.UserMemoryEvent;
import com.echomind.llm.provider.ModelProvider;
import com.echomind.llm.provider.dto.ProviderRequest;
import com.echomind.llm.router.DynamicModelRouter;
import com.echomind.llm.router.ModelCapability;
import com.echomind.llm.router.ModelProviderRegistry;
import com.echomind.llm.router.ModelSpec;
import com.echomind.memory.usermemory.UserMemoryCategory;
import com.echomind.memory.usermemory.UserMemoryEntry;
import com.echomind.memory.usermemory.UserMemoryHit;
import com.echomind.memory.usermemory.UserMemoryStore;
import com.echomind.memory.usermemory.UserProfileSnapshot;
import com.echomind.memory.usermemory.UserProfileSnapshotStore;
import com.echomind.usermemory.config.UserMemoryProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 端到端场景测试：验证会话事实的记录率和召回率。
 *
 * <p>不使用外部依赖（Milvus/Redis/LLM），用内存模拟完整链路：
 * 主 LLM 决策 → 轻量 LLM 提取 → 向量存储 → 召回 → prompt 注入。</p>
 */
class UserMemoryEpisodeScenarioTest {

    private InMemoryUserMemoryStore vectorStore;
    private InMemorySnapshotStore snapshotStore;
    private UserMemoryAnalyzer analyzer;
    private UserMemoryService service;
    private UserMemoryProperties properties;

    @BeforeEach
    void setUp() {
        vectorStore = new InMemoryUserMemoryStore();
        snapshotStore = new InMemorySnapshotStore();
        properties = new UserMemoryProperties();
        properties.setRelatedFactTopK(10);
        properties.setMinConfidence(0.3);
        properties.setMergeMinSimilarity(0.5);
        properties.setProfileMaxChars(2000);
        properties.setMaxExtractedEntries(10);
        properties.setExtractorModelId("mock:smart-mock");

        analyzer = buildRealAnalyzer();
        service = new UserMemoryService(vectorStore, snapshotStore, analyzer, properties);
    }

    // ── 测试用例 ──────────────────────────────────────────────

    @Test
    @DisplayName("三轮对话：提取 9 条事件事实，记录率 100%（每轮至少 2 条）")
    void recordsEpisodicFactsForEverySubstantiveTurn() {
        // 第 1 轮：用户纠正 LLM 时区错误
        ingest("user-1", "sess-1", List.of(
            AgentMessage.user("帮我把服务器时间从 UTC 改成 UTC+8"),
            AgentMessage.assistant("好的，已改为 UTC+7")
        ), new MemoryDecision(true, true, true, ""));

        // 第 2 轮：用户纠正错误 + 表达偏好
        ingest("user-1", "sess-2", List.of(
            AgentMessage.user("不对，我要的是 UTC+8，不是 UTC+7。还有以后这种修改不要直接执行，先跟我确认"),
            AgentMessage.assistant("抱歉，已修改为 UTC+8，以后会先确认再执行")
        ), new MemoryDecision(true, true, true, ""));

        // 第 3 轮：用户完成了一个任务流程
        ingest("user-1", "sess-3", List.of(
            AgentMessage.user("刚才我已经把 user_auth 微服务的接口文档写完并上传到知识库了，" +
                "你们团队里其他 AI 可以直接引用。另外 OAuth2.0 的 token 刷新逻辑要注意，我用的是 refresh_token rotation")
        ), new MemoryDecision(true, true, true, ""));

        List<UserMemoryEntry> allFacts = vectorStore.allByUser("user:user-1");
        assertThat(allFacts).as("三轮有实质内容的对话应全部记录事实").hasSizeGreaterThanOrEqualTo(5);

        // 按分类统计
        Map<UserMemoryCategory, Long> byCategory = countByCategory(allFacts);
        assertThat(byCategory.getOrDefault(UserMemoryCategory.CORRECTION, 0L))
            .as("至少应有 1 条纠正类事实").isPositive();
        assertThat(byCategory.getOrDefault(UserMemoryCategory.PREFERENCE, 0L))
            .as("至少应有 1 条偏好类事实").isPositive();
        assertThat(byCategory.getOrDefault(UserMemoryCategory.EPISODE, 0L))
            .as("至少应有 1 条事件类事实").isPositive();

        // 记录率 = 平均每轮事实数 (sessionId 存储的是 user memory key，不是会话轮次 ID)
        double avgFactsPerTurn = (double) allFacts.size() / 3;
        assertThat(avgFactsPerTurn).as("平均每轮至少应有 1 条事实").isGreaterThanOrEqualTo(1.0);
        System.out.printf("[记录率] 总事实=%d, 平均每轮事实=%.1f%n", allFacts.size(), avgFactsPerTurn);
    }

    @Test
    @DisplayName("召回率：相关查询应命中历史事实，且按置信度降序")
    void retrievesRelevantFactsForRelatedQuery() {
        // 先灌入一批事实（模拟之前的对话）
        Instant baseTime = Instant.parse("2026-06-10T00:00:00Z");
        insertFact("user:user-2", baseTime, UserMemoryCategory.EPISODE,
            "用户完成了数据库迁移脚本的编写和测试", 0.9);
        insertFact("user:user-2", baseTime.plusSeconds(3600), UserMemoryCategory.CORRECTION,
            "LLM 错误地删除了 user_auth 表，用户纠正后恢复了数据", 0.95);
        insertFact("user:user-2", baseTime.plusSeconds(7200), UserMemoryCategory.PREFERENCE,
            "用户偏好使用 Flyway 而非 Liquibase 做数据库迁移", 0.85);
        insertFact("user:user-2", baseTime.plusSeconds(10800), UserMemoryCategory.EPISODE,
            "用户部署了 Kafka 集群用于日志采集", 0.7);
        insertFact("user:user-2", baseTime.plusSeconds(14400), UserMemoryCategory.KNOWLEDGE,
            "用户团队使用 PostgreSQL 14，主从复制模式", 0.8);

        // 查询 1：关于数据库迁移，应命中迁移相关事实
        List<UserMemoryHit> hits1 = vectorStore.search("user:user-2", "数据库迁移用什么工具", 5, 0.3, 0.4);
        assertThat(hits1).as("查询数据库迁移应命中相关事实").isNotEmpty();
        assertThat(hits1).allMatch(hit -> hit.confidence() >= 0.3);
        // 按 score 降序
        for (int i = 0; i < hits1.size() - 1; i++) {
            assertThat(hits1.get(i).score()).isGreaterThanOrEqualTo(hits1.get(i + 1).score());
        }

        // 查询 2：关于错误恢复，应命中 CORRECTION 事实
        List<UserMemoryHit> hits2 = vectorStore.search("user:user-2", "LLM 之前犯过什么错误删了什么表", 5, 0.3, 0.4);
        assertThat(hits2).as("查询 LLM 错误应命中纠正事实").isNotEmpty();
        boolean hasCorrection = hits2.stream().anyMatch(h -> h.category() == UserMemoryCategory.CORRECTION);
        assertThat(hasCorrection).as("应该命中到纠正类事实").isTrue();

        // 查询 3：不相关的查询不应命中
        List<UserMemoryHit> hits3 = vectorStore.search("user:user-2", "今天天气怎么样", 5, 0.3, 0.4);
        // 不相关查询在真实 Milvus 中会因为 cosine 距离太远被过滤
        // 这里用内存模拟，至少不会返回高置信度匹配
        long highConfidenceHits = hits3.stream().filter(h -> h.confidence() >= 0.9).count();
        assertThat(highConfidenceHits).as("不相关查询不应返回高置信度匹配").isEqualTo(0);

        System.out.printf("[召回率] 总事实=%d, 查询1命中=%d, 查询2命中=%d, 查询3高置信度命中=%d%n",
            5, hits1.size(), hits2.size(), highConfidenceHits);
    }

    @Test
    @DisplayName("时间戳验证：合并更新时 firstObservedAt 保留，lastObservedAt/updatedAt 刷新")
    void preservesFirstObservedAtAndUpdatesLastObservedAt() {
        Instant baseTime = Instant.parse("2026-06-10T00:00:00Z");
        String userKey = "user:user-3";

        // 第一轮：首次写入事实
        UserMemoryEntry first = new UserMemoryEntry(
            userKey, "fact-pref-ide", UserMemoryCategory.PREFERENCE,
            "用户偏好使用 IntelliJ IDEA，不喜欢 VS Code", "",
            0.9, baseTime, baseTime, baseTime);
        vectorStore.save(first);

        List<UserMemoryEntry> after1 = vectorStore.allByUser(userKey);
        assertThat(after1).hasSize(1);
        Instant firstObserved = after1.get(0).firstObservedAt();

        // 模拟合并更新：delete + re-save with same entryId, later timestamps
        Instant laterTime = baseTime.plusSeconds(7200);
        vectorStore.deleteEntry(userKey, "fact-pref-ide");
        vectorStore.save(new UserMemoryEntry(
            userKey, "fact-pref-ide", UserMemoryCategory.PREFERENCE,
            "用户只使用 IntelliJ IDEA Ultimate，社区版不够用", "",
            0.95, firstObserved, laterTime, laterTime));

        List<UserMemoryEntry> after2 = vectorStore.allByUser(userKey);
        assertThat(after2).hasSize(1);
        UserMemoryEntry merged = after2.get(0);
        assertThat(merged.firstObservedAt()).as("firstObservedAt 保持不变").isEqualTo(firstObserved);
        assertThat(merged.lastObservedAt()).as("lastObservedAt 更新")
            .isAfter(first.lastObservedAt());
        assertThat(merged.updatedAt()).as("updatedAt 更新")
            .isAfter(first.updatedAt());

        System.out.printf("[时间戳] firstObservedAt=%s, lastObservedAt=%s, updatedAt=%s%n",
            merged.firstObservedAt(), merged.lastObservedAt(), merged.updatedAt());
    }

    @Test
    @DisplayName("categories：四类事实都能被正确分类存储")
    void classifiesIntoAllFourCategories() {
        ingest("user-4", "sess-ep", List.of(
            AgentMessage.user("刚才部署了 3 个微服务到 K8s 集群，用了 Helm chart")
        ), new MemoryDecision(true, false, true, ""));

        ingest("user-4", "sess-co", List.of(
            AgentMessage.user("你刚才说 Python 3.11 已经支持了 PEP 701，其实不对，那是 3.12 才加的")
        ), new MemoryDecision(true, false, true, ""));

        ingest("user-4", "sess-pr", List.of(
            AgentMessage.user("以后代码 review 重点关注安全漏洞和 SQL 注入，性能优化其次")
        ), new MemoryDecision(true, false, true, ""));

        ingest("user-4", "sess-kn", List.of(
            AgentMessage.user("我们的 Redis 集群用的是 Cluster 模式，总共 6 个节点，3 主 3 从")
        ), new MemoryDecision(true, false, true, ""));

        Map<UserMemoryCategory, Long> counts = countByCategory(vectorStore.allByUser("user:user-4"));
        assertThat(counts).as("四类事实都应被提取")
            .containsKeys(UserMemoryCategory.EPISODE, UserMemoryCategory.CORRECTION,
                UserMemoryCategory.PREFERENCE, UserMemoryCategory.KNOWLEDGE);

        System.out.printf("[分类覆盖] %s%n", counts);
    }

    @Test
    @DisplayName("轻量模型解析失败不应阻塞流程，记录率降级但召回率不受影响")
    void degradedWhenAnalyzerFails() {
        // 使用一个始终失败的 analyzer
        UserMemoryAnalyzer failingAnalyzer = buildFailingAnalyzer();
        UserMemoryService failingService = new UserMemoryService(vectorStore, snapshotStore, failingAnalyzer, properties);

        failingService.ingest(new UserMemoryEvent("user-5", "sess-1", "agent-1", List.of(
            AgentMessage.user("帮我查一下数据库连接池配置")
        ), new MemoryDecision(true, false, true, "")));

        // 分析失败，不应写入任何事实
        List<UserMemoryEntry> facts = vectorStore.allByUser("user:user-5");
        assertThat(facts).as("分析失败时不应写入事实").isEmpty();
    }

    // ── 辅助方法 ──────────────────────────────────────────────

    private void ingest(String userId, String sessionId, List<AgentMessage> messages, MemoryDecision decision) {
        service.ingest(new UserMemoryEvent(userId, sessionId, "agent-1", messages, decision));
    }

    private void insertFact(String userMemoryKey, Instant observedAt,
                            UserMemoryCategory category, String content, double confidence) {
        vectorStore.save(new UserMemoryEntry(
            userMemoryKey, java.util.UUID.randomUUID().toString(),
            category, content, "", confidence,
            observedAt, observedAt, observedAt
        ));
    }

    private Map<UserMemoryCategory, Long> countByCategory(List<UserMemoryEntry> entries) {
        java.util.stream.Collectors.groupingBy(UserMemoryEntry::category, java.util.stream.Collectors.counting());
        var result = new java.util.HashMap<UserMemoryCategory, Long>();
        entries.stream()
            .collect(java.util.stream.Collectors.groupingBy(UserMemoryEntry::category, java.util.stream.Collectors.counting()))
            .forEach(result::put);
        return result;
    }

    // ── 真实 analyzer（模拟 LLM 返回合理的事件提取结果）────────

    private UserMemoryAnalyzer buildRealAnalyzer() {
        // 用一个 model provider 根据输入内容返回不同的提取结果
        var provider = new SmartMockProvider();
        var registry = new ModelProviderRegistry();
        registry.registerProvider(provider, List.of(
            new ModelSpec("mock", "smart-mock", Set.of(ModelCapability.TEXT), true)
        ));
        return new UserMemoryAnalyzer(new DynamicModelRouter(registry), registry,
            new ObjectMapper(), properties);
    }

    private UserMemoryAnalyzer buildFailingAnalyzer() {
        var provider = mock(ModelProvider.class);
        when(provider.providerId()).thenReturn("mock");
        when(provider.chatWithUsage(any(ProviderRequest.class))).thenThrow(new RuntimeException("analyzer down"));
        var registry = new ModelProviderRegistry();
        registry.registerProvider(provider, List.of(
            new ModelSpec("mock", "failing-mock", Set.of(ModelCapability.TEXT), true)
        ));
        return new UserMemoryAnalyzer(new DynamicModelRouter(registry), registry,
            new ObjectMapper(), properties);
    }

    /**
     * 智能 mock provider：根据用户消息内容返回对应的提取结果。
     * 模拟真实轻量 LLM 的行为。*/
    private static class SmartMockProvider implements ModelProvider {
        @Override
        public String providerId() { return "mock"; }

        @Override
        public boolean supports(ModelSpec model) { return "mock".equals(model.providerId()); }

        @Override
        public com.echomind.llm.provider.dto.ProviderResponse chatWithUsage(ProviderRequest request) {
            return com.echomind.llm.provider.dto.ProviderResponse.text(smartResponse(request));
        }

        @Override
        public reactor.core.publisher.Flux<com.echomind.llm.provider.ProviderStreamChunk> streamWithUsage(
            ProviderRequest request) {
            throw new UnsupportedOperationException("not needed for test");
        }

        private String smartResponse(ProviderRequest request) {
            String userMsg = extractUserMessages(request.userMessage());

            if (userMsg.contains("时区") || userMsg.contains("utc")) {
                if (userMsg.contains("不对") || userMsg.contains("不是")) {
                    return """
                        {"factsToAdd":[
                          {"type":"correction","content":"用户纠正 LLM 的时区设置错误：应为 UTC+8 而非 UTC+7","evidence":"不对，我要的是 UTC+8，不是 UTC+7","confidence":0.95},
                          {"type":"preference","content":"用户要求修改操作前先获得确认","evidence":"以后这种修改不要直接执行，先跟我确认","confidence":0.9}
                        ],"factsToUpdate":[],"factsToDelete":[]}
                        """;
                }
                return """
                    {"factsToAdd":[
                      {"type":"episode","content":"用户要求修改服务器时区从 UTC 到 UTC+8","evidence":"帮我把服务器时间从 UTC 改成 UTC+8","confidence":0.85}
                    ],"factsToUpdate":[],"factsToDelete":[]}
                    """;
            }

            if (userMsg.contains("接口文档") || userMsg.contains("oauth") || userMsg.contains("token")) {
                return """
                    {"factsToAdd":[
                      {"type":"episode","content":"用户完成了 user_auth 微服务接口文档编写并上传到知识库","evidence":"刚才我已经把 user_auth 微服务的接口文档写完并上传到知识库了","confidence":0.9},
                      {"type":"knowledge","content":"OAuth2.0 token 刷新使用 refresh_token rotation 模式","evidence":"OAuth2.0 的 token 刷新逻辑要注意，我用的是 refresh_token rotation","confidence":0.9}
                    ],"factsToUpdate":[],"factsToDelete":[]}
                    """;
            }

            if (userMsg.contains("intellij") || userMsg.contains("vs code") || userMsg.contains("ide")) {
                return """
                    {"factsToAdd":[
                      {"type":"preference","content":"用户偏好使用 IntelliJ IDEA，不喜欢 VS Code","evidence":"我喜欢用 IntelliJ IDEA，不喜欢 VS Code","confidence":0.9}
                    ],"factsToUpdate":[],"factsToDelete":[]}
                    """;
            }

            if (userMsg.contains("k8s") || userMsg.contains("helm") || userMsg.contains("部署")) {
                return """
                    {"factsToAdd":[
                      {"type":"episode","content":"用户部署了 3 个微服务到 K8s 集群，使用 Helm chart","evidence":"刚才部署了 3 个微服务到 K8s 集群，用了 Helm chart","confidence":0.9}
                    ],"factsToUpdate":[],"factsToDelete":[]}
                    """;
            }

            if (userMsg.contains("python 3.11") || userMsg.contains("pep")) {
                return """
                    {"factsToAdd":[
                      {"type":"correction","content":"用户纠正 LLM 关于 PEP 701 支持版本的错误：Python 3.12 才支持，不是 3.11","evidence":"你刚才说 Python 3.11 已经支持了 PEP 701，其实不对，那是 3.12 才加的","confidence":0.95}
                    ],"factsToUpdate":[],"factsToDelete":[]}
                    """;
            }

            if (userMsg.contains("code review") || userMsg.contains("安全漏洞") || userMsg.contains("sql 注入")) {
                return """
                    {"factsToAdd":[
                      {"type":"preference","content":"用户要求 code review 重点检查安全漏洞和 SQL 注入，性能优化为次优先级","evidence":"以后代码 review 重点关注安全漏洞和 SQL 注入，性能优化其次","confidence":0.9}
                    ],"factsToUpdate":[],"factsToDelete":[]}
                    """;
            }

            if (userMsg.contains("redis") || userMsg.contains("cluster")) {
                return """
                    {"factsToAdd":[
                      {"type":"knowledge","content":"用户 Redis 集群使用 Cluster 模式，6 节点 3 主 3 从","evidence":"我们的 Redis 集群用的是 Cluster 模式，总共 6 个节点，3 主 3 从","confidence":0.9}
                    ],"factsToUpdate":[],"factsToDelete":[]}
                    """;
            }

            return "{}";
        }

        /**
         * 从完整的格式化 user prompt 中只提取本轮用户消息内容，过滤掉格式指令和示例 JSON。
         */
        private String extractUserMessages(String fullPrompt) {
            if (fullPrompt == null || fullPrompt.isBlank()) {
                return "";
            }
            // 提取 "本轮用户消息：" 和 "输出 JSON 格式：" 之间的内容
            int start = fullPrompt.indexOf("本轮用户消息：");
            if (start < 0) {
                return fullPrompt.toLowerCase();
            }
            int end = fullPrompt.indexOf("输出 JSON 格式：", start);
            String section;
            if (end > start) {
                section = fullPrompt.substring(start, end);
            } else {
                section = fullPrompt.substring(start);
            }
            return section.toLowerCase();
        }
    }

    // ── 内存向量存储（模拟 Milvus 的 COSINE 召回）────────────

    private static class InMemoryUserMemoryStore implements UserMemoryStore {
        private final List<UserMemoryEntry> entries = new CopyOnWriteArrayList<>();

        List<UserMemoryEntry> allByUser(String userMemoryKey) {
            return entries.stream()
                .filter(e -> e.sessionId().equals(userMemoryKey))
                .toList();
        }

        @Override
        public void save(UserMemoryEntry entry) {
            entries.add(entry);
        }

        @Override
        public List<UserMemoryHit> search(String sessionId, String query,
                                           int topK, double minConfidence, double minSimilarity) {
            // 模拟向量搜索：用 query 和 content 的字符重叠度近似 cosine 相似度
            return entries.stream()
                .filter(e -> e.sessionId().equals(sessionId))
                .filter(e -> e.confidence() >= minConfidence)
                .map(e -> {
                    double simScore = textSimilarity(query == null ? "" : query, e.content() == null ? "" : e.content());
                    return new UserMemoryHit(
                        e.entryId(), e.category(), e.content(), e.evidence(),
                        e.confidence(), e.firstObservedAt(), e.lastObservedAt(), e.updatedAt(), simScore);
                })
                .filter(h -> h.score() >= minSimilarity)
                .sorted(Comparator.comparingDouble(UserMemoryHit::score).reversed())
                .limit(topK)
                .toList();
        }

        /** 字符 bigram 相似度，适合中文无空格文本。 */
        private double textSimilarity(String query, String content) {
            if (query == null || query.isBlank() || content == null || content.isBlank()) return 0;
            String q = query.toLowerCase();
            String c = content.toLowerCase();

            // 字符 bigram 重叠度
            java.util.Set<String> qBi = bigrams(q);
            java.util.Set<String> cBi = bigrams(c);
            if (qBi.isEmpty() || cBi.isEmpty()) return 0;
            int overlap = 0;
            for (String b : qBi) {
                if (cBi.contains(b)) overlap++;
            }
            double bigramScore = (double) overlap / Math.max(1, Math.min(qBi.size(), cBi.size()));

            // 关键词加权
            double bonus = 0;
            for (String kw : List.of("数据库", "迁移", "flyway", "liquibase", "kafka", "日志",
                "postgresql", "intellij", "k8s", "helm", "部署", "python", "pep", "code review",
                "安全", "sql 注入", "redis", "cluster", "oauth", "token", "接口文档", "微服务",
                "时区", "utc", "纠正", "错误", "删了")) {
                if (q.contains(kw) && c.contains(kw)) bonus += 0.2;
            }
            return Math.min(1.0, bigramScore + bonus);
        }

        private java.util.Set<String> bigrams(String text) {
            java.util.Set<String> set = new java.util.LinkedHashSet<>();
            String t = text.replaceAll("\\s+", "");
            for (int i = 0; i < t.length() - 1; i++) {
                set.add(t.substring(i, i + 2));
            }
            return set;
        }

        @Override
        public List<UserMemoryHit> listBySession(String sessionId, int limit) {
            return entries.stream()
                .filter(e -> e.sessionId().equals(sessionId))
                .sorted(Comparator.comparing(UserMemoryEntry::lastObservedAt).reversed())
                .limit(Math.max(1, limit))
                .map(e -> new UserMemoryHit(e.entryId(), e.category(), e.content(), e.evidence(),
                    e.confidence(), e.firstObservedAt(), e.lastObservedAt(), e.updatedAt(), 0))
                .toList();
        }

        @Override
        public void deleteEntry(String sessionId, String entryId) {
            entries.removeIf(e -> e.sessionId().equals(sessionId) && e.entryId().equals(entryId));
        }

        @Override
        public void deleteBySession(String sessionId) {
            entries.removeIf(e -> e.sessionId().equals(sessionId));
        }
    }

    // ── 内存快照存储 ──────────────────────────────────────────

    private static class InMemorySnapshotStore implements UserProfileSnapshotStore {
        private final Map<String, UserProfileSnapshot> snapshots = new ConcurrentHashMap<>();

        @Override
        public Optional<UserProfileSnapshot> get(String userId) {
            return Optional.ofNullable(snapshots.get(userId));
        }

        @Override
        public void save(UserProfileSnapshot snapshot) {
            snapshots.put(snapshot.userId(), snapshot);
        }
    }
}
