package com.echomind.boot.autoconfigure;

import com.echomind.agent.AgentConfig;
import com.echomind.agent.AgentFactory;
import com.echomind.agent.memory.ChatMemoryPersistPublisher;
import com.echomind.agent.memory.NoopChatMemoryPersistPublisher;
import com.echomind.agent.memory.RabbitChatMemoryPersistPublisher;
import com.echomind.agent.orchestration.AgentOrchestrator;
import com.echomind.agent.pipeline.ExecutionPipeline;
import com.echomind.agent.pipeline.PipelineStage;
import com.echomind.agent.pipeline.PromptBudget;
import com.echomind.agent.pipeline.stages.*;
import com.echomind.agent.store.AgentPersistenceService;
import com.echomind.agent.store.AgentRepository;
import com.echomind.agent.tool.CapabilityRegistry;
import com.echomind.agent.tool.ExternalMcpRuntimeService;
import com.echomind.agent.tool.ExternalMcpServerConfig;
import com.echomind.agent.tool.SkillCapabilityService;
import com.echomind.agent.usermemory.NoopUserMemoryPersistPublisher;
import com.echomind.agent.usermemory.RabbitUserMemoryPersistPublisher;
import com.echomind.agent.usermemory.UserMemoryPersistPublisher;
import com.echomind.boot.properties.EchoMindProperties;
import com.echomind.llm.provider.DeepSeekProvider;
import com.echomind.llm.provider.MockModelProvider;
import com.echomind.llm.provider.OpenAICompatibleProvider;
import com.echomind.llm.router.*;
import com.echomind.memory.MemoryManager;
import com.echomind.memory.cache.InMemoryRecentMemoryCache;
import com.echomind.memory.cache.RecentMemoryCache;
import com.echomind.memory.cache.RedisRecentMemoryCache;
import com.echomind.memory.embedding.DashScopeEmbeddingClient;
import com.echomind.memory.embedding.DisabledEmbeddingClient;
import com.echomind.memory.embedding.EmbeddingClient;
import com.echomind.memory.embedding.MemoryEmbeddingService;
import com.echomind.memory.embedding.MemoryVectorStore;
import com.echomind.memory.embedding.NoopMemoryVectorStore;
import com.echomind.memory.embedding.RedisStackMemoryVectorStore;
import com.echomind.memory.persistence.ChatMessageRepository;
import com.echomind.memory.persistence.ChatSessionRepository;
import com.echomind.memory.persistence.PersistentChatMemoryStore;
import com.echomind.memory.knowledge.AgentKnowledgeChunkRepository;
import com.echomind.memory.knowledge.AgentKnowledgeDocumentRepository;
import com.echomind.memory.knowledge.AgentKnowledgeService;
import com.echomind.memory.usermemory.NoopUserMemoryStore;
import com.echomind.memory.usermemory.UserMemoryStore;
import com.echomind.memory.usermemory.UserMemoryVectorStore;
import com.echomind.memory.shortterm.WindowConfig;
import com.echomind.memory.summary.MemorySummaryService;
import com.echomind.skill.loader.SkillDirectoryWatcher;
import com.echomind.skill.loader.SkillJarLoader;
import com.echomind.skill.marketplace.MarketplaceService;
import com.echomind.skill.marketplace.SkillEntityRepository;
import com.echomind.skill.registry.SkillRegistry;
import com.echomind.skill.storage.AliyunOssObjectStorageService;
import com.echomind.skill.storage.LocalObjectStorageService;
import com.echomind.skill.storage.ObjectStorageService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Paths;
import java.util.*;

/**
 * EchoMind 的 Spring Boot 自动装配入口。
 *
 * <p>这里把 LLM、Memory、Skill、Tool、Agent、Team 和外部 MCP 接入串成运行时 Bean 图。
 * 复杂逻辑应下沉到各模块，本类只负责装配和少量配置分支。</p>
 */
@Configuration
@EnableConfigurationProperties(EchoMindProperties.class)
@Slf4j
public class EchoMindAutoConfiguration {

    private static final String MARKDOWN_CODE_SKILL = "markdown-code";
    private static final String DATE_QUERY_SKILL = "date-query";

    // --- 大模型装配 ---

    @Bean
    public ModelProviderRegistry modelProviderRegistry() {
        return new ModelProviderRegistry();
    }

    @Bean
    public DynamicModelRouter dynamicModelRouter(ModelProviderRegistry registry) {
        return new DynamicModelRouter(registry);
    }

    /** DeepSeek Provider，优先读取 DEEPSEEK_*，并兼容旧环境里误填的 ANTHROPIC_*。 */
    @Bean
    public DeepSeekProvider deepSeekProvider(EchoMindProperties props) {
        var providers = props.getModels().getProviders();
        var config = providers.getOrDefault("deepseek", new EchoMindProperties.ProviderConfig());
        String key = firstNonBlank(config.getApiKey(), System.getenv("DEEPSEEK_API_KEY"), System.getenv("ANTHROPIC_API_KEY"));
        String url = firstNonBlank(config.getBaseUrl(), System.getenv("DEEPSEEK_BASE_URL"), System.getenv("ANTHROPIC_BASE_URL"));
        if (isBlank(url)) {
            url = "https://api.deepseek.com/anthropic";
        }
        if (!url.contains("/anthropic")) {
            url = url.endsWith("/") ? url + "anthropic" : url + "/anthropic";
        }
        return new DeepSeekProvider(url, key);
    }

    /**
     * 所有 OpenAI 兼容协议的 Provider。
     *
     * <p>遍历 echomind.models.providers 中除 anthropic 以外的所有 Provider 配置，
     * 为一个配置创建 OpenAICompatibleProvider。无 API key 的项自动跳过。</p>
     */
    @Bean
    public List<OpenAICompatibleProvider> openAiCompatibleProviders(EchoMindProperties props) {
        List<OpenAICompatibleProvider> result = new ArrayList<>();
        var providers = props.getModels().getProviders();
        if (providers == null) return result;
        for (var entry : providers.entrySet()) {
            String pid = entry.getKey();
            if ("anthropic".equals(pid) || "deepseek".equals(pid)) continue;
            var config = entry.getValue();
            String key = config.getApiKey();
            if (key == null || key.isBlank()) {
                log.info("Skipping OpenAI-compatible provider '{}': no API key configured", pid);
                continue;
            }
            String url = config.getBaseUrl() != null ? config.getBaseUrl() : "https://api.openai.com";
            result.add(new OpenAICompatibleProvider(pid, url, key));
            log.info("Created OpenAI-compatible provider: {} (url={})", pid, url);
        }
        return result;
    }

    @Bean
    public MockModelProvider mockModelProvider() {
        return new MockModelProvider();
    }

    /**
     * 注册模型列表。
     *
     * <p>使用 Object 作为初始化哨兵，保证 Provider Bean 创建后立刻进入注册中心。</p>
     */
    @Bean
    public Object llmInitializer(ModelProviderRegistry registry, DeepSeekProvider deepSeek,
                                 List<OpenAICompatibleProvider> compatibleProviders,
                                 MockModelProvider mock, EchoMindProperties props) {
        // DeepSeek 是当前默认文本/工具模型；兼容此前误放在 ANTHROPIC_* 中的配置。
        var deepSeekConfig = props.getModels().getProviders().get("deepseek");
        List<ModelSpec> deepSeekModels = configuredModels("deepseek", deepSeekConfig);
        if (deepSeekModels.isEmpty()) {
            deepSeekModels.add(new ModelSpec("deepseek", "deepseek-v4-flash",
                Set.of(ModelCapability.TEXT, ModelCapability.FUNCTION), true));
        }
        registry.registerProvider(deepSeek, deepSeekModels);

        // 动态注册所有 OpenAI 兼容协议 Provider，一个 YAML 配置驱动，无需新增 Java 类。
        var providerConfigs = props.getModels().getProviders();
        for (var provider : compatibleProviders) {
            String pid = provider.providerId();
            var pConfig = providerConfigs != null ? providerConfigs.get(pid) : null;
            List<ModelSpec> models = new ArrayList<>();
            if (pConfig != null && pConfig.getModels() != null) {
                for (var mc : pConfig.getModels()) {
                    models.add(new ModelSpec(pid, mc.getName(),
                        parseCapabilities(mc.getCapabilities()), mc.isDefault()));
                }
            }
            if (!models.isEmpty()) {
                registry.registerProvider(provider, models);
            }
        }

        // Mock Provider 始终可用，便于本地开发和兜底测试。
        registry.registerProvider(mock, List.of(
            new ModelSpec("mock", "mock-model", Set.of(ModelCapability.TEXT, ModelCapability.FUNCTION), false)
        ));

        log.info("LLM providers registered: {}", registry.providerIds());
        return new Object();
    }

    private List<ModelSpec> configuredModels(String providerId, EchoMindProperties.ProviderConfig config) {
        List<ModelSpec> models = new ArrayList<>();
        if (config != null && config.getModels() != null) {
            for (var mc : config.getModels()) {
                models.add(new ModelSpec(providerId, mc.getName(),
                    parseCapabilities(mc.getCapabilities()), mc.isDefault()));
            }
        }
        return models;
    }

    /** 解析模型能力标签，非法值会被忽略。 */
    private Set<ModelCapability> parseCapabilities(List<String> caps) {
        if (caps == null || caps.isEmpty()) return Set.of(ModelCapability.TEXT);
        Set<ModelCapability> result = EnumSet.noneOf(ModelCapability.class);
        for (String c : caps) {
            try { result.add(ModelCapability.valueOf(c.toUpperCase())); } catch (IllegalArgumentException ignored) {}
        }
        return result.isEmpty() ? Set.of(ModelCapability.TEXT) : result;
    }

    // --- 记忆装配 ---

    @Bean
    public WindowConfig windowConfig(EchoMindProperties props) {
        return new WindowConfig(props.getMemory().getShortTermWindow());
    }

    /** Redis 近期缓存；Redis 不可用时回退到进程内缓存。 */
    @Bean
    public RecentMemoryCache recentMemoryCache(EchoMindProperties props,
                                               ObjectProvider<RedisConnectionFactory> redisConnectionFactory) {
        RedisConnectionFactory factory = redisConnectionFactory.getIfAvailable();
        if (factory != null) {
            log.info("Using RedisRecentMemoryCache with window={} TTL={}s",
                props.getMemory().getShortTermWindow(), props.getMemory().getRedisTtlSeconds());
            return new RedisRecentMemoryCache(
                factory,
                props.getMemory().getShortTermWindow(),
                props.getMemory().getRedisTtlSeconds()
            );
        }
        log.warn("No RedisConnectionFactory available, falling back to in-memory recent cache");
        return new InMemoryRecentMemoryCache(props.getMemory().getShortTermWindow());
    }

    @Bean
    public PersistentChatMemoryStore persistentChatMemoryStore(ChatSessionRepository sessionRepository,
                                                               ChatMessageRepository messageRepository,
                                                               ObjectMapper mapper) {
        return new PersistentChatMemoryStore(sessionRepository, messageRepository, mapper);
    }

    @Bean
    public EmbeddingClient embeddingClient(EchoMindProperties props, ObjectMapper mapper) {
        var memory = props.getMemory();
        String apiKey = firstNonBlank(
            memory.getEmbeddingApiKey(),
            System.getenv("ALIYUN_BAILIAN_API_KEY"),
            System.getenv("DASHSCOPE_API_KEY")
        );
        if (!memory.isEmbeddingEnabled() || isBlank(apiKey)) {
            log.warn("Memory embedding disabled: no ALIYUN_BAILIAN_API_KEY/DASHSCOPE_API_KEY configured");
            return new DisabledEmbeddingClient();
        }
        log.info("Using DashScope embedding model: {}", memory.getEmbeddingModel());
        return new DashScopeEmbeddingClient(
            memory.getEmbeddingBaseUrl(),
            apiKey,
            memory.getEmbeddingModel(),
            mapper
        );
    }

    @Bean
    @Primary
    public MemoryVectorStore memoryVectorStore(EchoMindProperties props,
                                               ObjectProvider<RedisConnectionFactory> redisConnectionFactory) {
        String type = props.getMemory().getVectorStore();
        if ("mysql-linear".equalsIgnoreCase(type)) {
            log.warn("mysql-linear memory vector store is deprecated for chat memory; using no-op vector store");
            return new NoopMemoryVectorStore();
        }
        RedisConnectionFactory factory = redisConnectionFactory.getIfAvailable();
        if (factory == null) {
            log.warn("Redis Stack vector store requested but RedisConnectionFactory is unavailable; using no-op vector store");
            return new NoopMemoryVectorStore();
        }
        log.info("Using Redis Stack memory vector store");
        return new RedisStackMemoryVectorStore(
            factory,
            props.getMemory().getVectorIndexName(),
            props.getMemory().getVectorKeyPrefix()
        );
    }

    @Bean
    public MemoryEmbeddingService memoryEmbeddingService(EmbeddingClient embeddingClient,
                                                         MemoryVectorStore vectorStore,
                                                         EchoMindProperties props) {
        return new MemoryEmbeddingService(
            embeddingClient,
            vectorStore,
            props.getMemory().isEmbeddingEnabled()
        );
    }

    @Bean
    public MemorySummaryService memorySummaryService(EchoMindProperties props) {
        return new MemorySummaryService(
            props.getMemory().getShortTermWindow(),
            props.getMemory().getSummaryMaxChars()
        );
    }

    @Bean
    public MemoryManager memoryManager(WindowConfig wc,
                                       PersistentChatMemoryStore chatStore,
                                       RecentMemoryCache recentCache,
                                       MemorySummaryService summaryService,
                                       MemoryEmbeddingService embeddingService,
                                       EchoMindProperties props) {
        return new MemoryManager(
            wc,
            chatStore,
            recentCache,
            summaryService,
            props.getMemory().getSummaryRefreshInterval(),
            embeddingService
        );
    }

    @Bean
    public AgentKnowledgeService agentKnowledgeService(AgentKnowledgeDocumentRepository documentRepository,
                                                       AgentKnowledgeChunkRepository chunkRepository,
                                                       EmbeddingClient embeddingClient,
                                                       ObjectMapper mapper,
                                                       EchoMindProperties props,
                                                       ObjectProvider<RedisConnectionFactory> redisConnectionFactory) {
        RedisConnectionFactory factory = redisConnectionFactory.getIfAvailable();
        return new AgentKnowledgeService(
            documentRepository,
            chunkRepository,
            embeddingClient,
            mapper,
            factory,
            props.getMemory().isEmbeddingEnabled(),
            props.getMemory().getKnowledgeVectorIndexName(),
            props.getMemory().getKnowledgeVectorKeyPrefix(),
            props.getMemory().getKnowledgeChunkSize(),
            props.getMemory().getKnowledgeChunkOverlap(),
            props.getMemory().getKnowledgeMinVectorSimilarity(),
            props.getMemory().getKnowledgeVectorWeight(),
            props.getMemory().getKnowledgeKeywordWeight(),
            props.getMemory().getKnowledgeKeywordCandidateLimit(),
            props.getMemory().isKnowledgeOcrEnabled(),
            props.getMemory().getKnowledgeOcrLanguage(),
            props.getMemory().getKnowledgeOcrDpi(),
            props.getMemory().getKnowledgeOcrMinTextChars(),
            props.getMemory().getKnowledgeOcrMaxPages(),
            props.getMemory().getKnowledgeOcrTesseractCommand()
        );
    }

    @Bean
    public PromptBudget promptBudget(EchoMindProperties props) {
        return new PromptBudget(
            props.getMemory().getPromptMaxChars(),
            props.getMemory().getPromptMaxSystemMessageChars(),
            props.getMemory().getPromptMaxHistoryMessageChars()
        );
    }

    @Bean
    public UserMemoryStore userMemoryStore(EchoMindProperties props,
                                           ObjectProvider<RedisConnectionFactory> redisConnectionFactory) {
        RedisConnectionFactory factory = redisConnectionFactory.getIfAvailable();
        if (factory == null) {
            log.warn("RedisConnectionFactory unavailable; user memory retrieval disabled in main pipeline");
            return new NoopUserMemoryStore();
        }
        return new UserMemoryVectorStore(
            factory,
            props.getUserMemory().getVectorIndexName(),
            props.getUserMemory().getVectorKeyPrefix()
        );
    }

    @Bean
    public Queue userMemoryQueue(EchoMindProperties props) {
        return new Queue(props.getUserMemory().getQueueName(), true);
    }

    @Bean
    public Queue chatMemoryPersistQueue(EchoMindProperties props) {
        return new Queue(props.getMemory().getPersistQueueName(), true);
    }

    @Bean
    public UserMemoryPersistPublisher userMemoryPersistPublisher(EchoMindProperties props,
                                                                 ObjectProvider<RabbitTemplate> rabbitTemplate) {
        RabbitTemplate template = rabbitTemplate.getIfAvailable();
        if (template == null) {
            log.warn("RabbitTemplate unavailable; user memory async persistence disabled");
            return new NoopUserMemoryPersistPublisher();
        }
        return new RabbitUserMemoryPersistPublisher(
            template,
            props.getUserMemory().getQueueName(),
            props.getUserMemory().isEnabled()
        );
    }

    @Bean
    public ChatMemoryPersistPublisher chatMemoryPersistPublisher(EchoMindProperties props,
                                                                 ObjectProvider<RabbitTemplate> rabbitTemplate) {
        RabbitTemplate template = rabbitTemplate.getIfAvailable();
        if (template == null) {
            log.warn("RabbitTemplate unavailable; chat memory async persistence disabled");
            return new NoopChatMemoryPersistPublisher();
        }
        return new RabbitChatMemoryPersistPublisher(
            template,
            props.getMemory().getPersistQueueName(),
            props.getMemory().isAsyncPersistEnabled()
        );
    }

    // --- Skill 装配 ---

    @Bean
    public SkillRegistry skillRegistry() {
        return new SkillRegistry();
    }

    @Bean
    public SkillJarLoader skillJarLoader() {
        return new SkillJarLoader();
    }

    /**
     * 对象存储服务。
     *
     * <p>配置为 OSS 且凭证完整时写入阿里云 OSS；配置缺失时退回本地目录，
     * 这样本地开发、自动测试和 Docker 首次启动不会被云凭证卡死。</p>
     */
    @Bean
    public ObjectStorageService objectStorageService(EchoMindProperties props) {
        EchoMindProperties.Storage storage = props.getStorage();
        LocalObjectStorageService local = new LocalObjectStorageService(Paths.get(storage.getLocalDir()));
        if (!"oss".equalsIgnoreCase(storage.getMode())) {
            log.info("Using local object storage at {}", storage.getLocalDir());
            return local;
        }
        if (isBlank(storage.getEndpoint()) || isBlank(storage.getBucket())
            || isBlank(storage.getAccessKeyId()) || isBlank(storage.getAccessKeySecret())) {
            log.warn("OSS storage requested but endpoint/bucket/accessKey is incomplete; falling back to local storage");
            return local;
        }
        log.info("Using Aliyun OSS object storage bucket={} endpoint={}", storage.getBucket(), storage.getEndpoint());
        return new AliyunOssObjectStorageService(
            storage.getEndpoint(),
            storage.getBucket(),
            storage.getAccessKeyId(),
            storage.getAccessKeySecret()
        );
    }

    /** 监听 Skill 目录并按需启动热加载。 */
    @Bean
    public SkillDirectoryWatcher skillDirectoryWatcher(EchoMindProperties props,
                                                        SkillJarLoader loader, SkillRegistry registry) {
        var watcher = new SkillDirectoryWatcher(
            Paths.get(props.getSkill().getAutoLoadPath()), loader, registry);
        if (props.getSkill().isHotReload()) {
            watcher.start();
        } else {
            watcher.scanExistingJars();
        }
        return watcher;
    }

    /** 占位给 Spring Data JPA 仓库代理，避免调用方缺 Bean。 */
    @Bean
    @ConditionalOnMissingBean
    public SkillEntityRepository skillEntityRepository() {
        return null; // 将由 Spring Data JPA 自动配置提供
    }

    @Bean
    public MarketplaceService marketplaceService(SkillEntityRepository repository,
                                                  SkillJarLoader loader, SkillRegistry registry,
                                                  EchoMindProperties props,
                                                  ObjectStorageService storageService) {
        return new MarketplaceService(repository, loader, registry,
            props.getSkill().getMarketplaceDir(), storageService);
    }

    // --- Agent 装配 ---

    /** 统一能力注册中心，同时承载Agent工具和外部MCP工具。 */
    @Bean
    public CapabilityRegistry capabilityRegistry() {
        return new CapabilityRegistry();
    }

    @Bean
    public SkillCapabilityService skillCapabilityService(SkillRegistry registry,
                                                          CapabilityRegistry capabilityRegistry) {
        return new SkillCapabilityService(registry, capabilityRegistry);
    }

    /** 启动时恢复市场上传的Skill，然后同步到统一能力注册中心。 */
    @Bean
    public Object skillCapabilityInitializer(SkillCapabilityService capabilityService,
                                             SkillDirectoryWatcher watcher,
                                             MarketplaceService marketplaceService) {
        marketplaceService.restoreFromDatabase();
        capabilityService.syncEnabledSkills();
        return new Object();
    }

    /** Agent 请求管线：上下文 → 模型路由 → LLM 聚合（模型自主工具调用）→ 记忆保存。 */
    @Bean
    public ExecutionPipeline executionPipeline(MemoryManager memory,
                                                 AgentKnowledgeService knowledgeService,
                                                 EmbeddingClient embeddingClient,
                                                 ObjectProvider<UserMemoryStore> userMemoryStore,
                                                 ChatMemoryPersistPublisher chatMemoryPersistPublisher,
                                                CapabilityRegistry capabilityRegistry,
                                                DynamicModelRouter router, ModelProviderRegistry providerReg,
                                                ObjectStorageService storageService,
                                                PromptBudget promptBudget,
                                                EchoMindProperties props) {
        List<PipelineStage> stages = List.of(
            new ContextEnrichStage(memory),
            new UserMemoryRetrievalStage(
                embeddingClient,
                userMemoryStore.getIfAvailable(NoopUserMemoryStore::new),
                props.getUserMemory().isEnabled(),
                props.getUserMemory().getTopK(),
                props.getUserMemory().getMinConfidence()
            ),
            new KnowledgeRetrievalStage(knowledgeService, embeddingClient, props.getMemory().getKnowledgeTopK()),
            new ToolResolutionStage(router),
            new MultimodalGuardStage(providerReg),
            new AttachmentPreparationStage(storageService),
            new ResultAggregationStage(router, providerReg, capabilityRegistry, promptBudget),
            new MemoryPersistStage(chatMemoryPersistPublisher)
        );
        return new ExecutionPipeline(stages);
    }

    /**
     * 外部 MCP 运行时管理服务。
     *
     * <p>该服务只负责“接入别人的 MCP Server”，不再把本项目暴露成 MCP Server。</p>
     */
    @Bean
    public ExternalMcpRuntimeService externalMcpRuntimeService(CapabilityRegistry capabilityRegistry) {
        return new ExternalMcpRuntimeService(capabilityRegistry);
    }

    /**
     * 启动并挂载配置里的外部 stdio MCP Server。
     */
    @Bean
    public Object externalMcpInitializer(EchoMindProperties props, ExternalMcpRuntimeService mcpRuntimeService) {
        for (EchoMindProperties.ExternalMcpServer server : props.getMcp().getExternalServers()) {
            if (!server.isEnabled()) {
                continue;
            }
            try {
                mcpRuntimeService.mount(new ExternalMcpServerConfig(
                    server.getId(),
                    server.getTransport(),
                    server.getCommand(),
                    server.getWorkingDirectory()
                ));
            } catch (Exception ex) {
                log.warn("External MCP server {} skipped: {}", server.getId(), ex.getMessage());
            }
        }
        return new Object();
    }

    /** Agent持久化服务，负责把用户创建的Agent配置写入MySQL。 */
    @Bean
    public AgentPersistenceService agentPersistenceService(AgentRepository repository) {
        return new AgentPersistenceService(repository);
    }

    /**
     * 从MySQL恢复Agent，再补齐配置文件里的默认Agent。
     *
     * <p>Agent是用户配置，数据库是事实来源；内存中的AgentFactory只是运行时索引。</p>
     */
    @Bean
    public AgentFactory agentFactory(ExecutionPipeline pipeline, EchoMindProperties props,
                                     AgentPersistenceService persistenceService) {
        AgentFactory factory = new AgentFactory(pipeline);
        for (AgentConfig persisted : persistenceService.loadAll()) {
            if (mergeDefaultAgentSkills(persisted, props)) {
                persistenceService.save(persisted);
            }
            factory.create(persisted);
        }
        for (var def : props.getAgents()) {
            if (persistenceService.exists(def.getAgentId())) {
                continue;
            }
            AgentConfig config = new AgentConfig();
            config.setAgentId(def.getAgentId());
            config.setName(def.getName());
            config.setSystemPrompt(def.getSystemPrompt());
            config.setModelId(def.getModelId());
            config.setSkillIds(def.getSkillIds());
            persistenceService.save(config);
            factory.create(config);
        }
        // 没有配置任何 Agent 时，补一个可直接使用的默认 Agent。
        if (factory.allAgents().isEmpty()) {
            AgentConfig config = new AgentConfig();
            config.setAgentId("default");
            config.setName("EchoMind Assistant");
            config.setSystemPrompt("You are a helpful AI assistant. You have access to tools for web search, weather, calculations, and date/time queries. Always use these tools when the user asks for real-time information, current data, weather, math, dates, times, or weekdays — never guess when a tool can give a better answer.");
            config.setModelId("deepseek:deepseek-v4-flash");
            config.setSkillIds(List.of("weather-query", "calculator", "web-search", "markdown-code", "date-query"));
            persistenceService.save(config);
            factory.create(config);
        }
        return factory;
    }

    /**
     * 对已存在于 MySQL 的默认 Agent 做轻量迁移。
     *
     * <p>数据库是事实来源，因此不会用 YAML 覆盖用户编辑过的名称、提示词、模型，
     * 也不会把用户曾经移除的旧 Skill 强行加回来。这里只补平台默认配置中新加入的 Skill。</p>
     */
    private boolean mergeDefaultAgentSkills(AgentConfig persisted, EchoMindProperties props) {
        if (persisted == null || props.getAgents() == null) {
            return false;
        }
        for (var def : props.getAgents()) {
            if (!Objects.equals(def.getAgentId(), persisted.getAgentId())) {
                continue;
            }
            List<String> configuredSkillIds = def.getSkillIds();
            if (configuredSkillIds == null || configuredSkillIds.isEmpty()) {
                return false;
            }
            LinkedHashSet<String> merged = new LinkedHashSet<>(
                persisted.getSkillIds() == null ? List.of() : persisted.getSkillIds());
            boolean changed = false;
            for (String skillId : defaultSkillsToMerge(configuredSkillIds)) {
                if (merged.add(skillId)) {
                    changed = true;
                    log.info("Merged default skill {} into persisted agent {}", skillId, persisted.getAgentId());
                }
            }
            if (changed) {
                persisted.setSkillIds(List.copyOf(merged));
            }
            if (isLegacyAnthropicDeepSeekModel(persisted.getModelId())) {
                persisted.setModelId(def.getModelId());
                log.info("Migrated persisted agent {} model to {}", persisted.getAgentId(), def.getModelId());
                changed = true;
            }
            return changed;
        }
        return false;
    }

    private List<String> defaultSkillsToMerge(List<String> configuredSkillIds) {
        List<String> mergeCandidates = List.of(MARKDOWN_CODE_SKILL, DATE_QUERY_SKILL);
        return mergeCandidates.stream()
            .filter(configuredSkillIds::contains)
            .toList();
    }

    private boolean isLegacyAnthropicDeepSeekModel(String modelId) {
        return modelId != null && modelId.startsWith("anthropic:claude-");
    }

    @Bean
    public AgentOrchestrator agentOrchestrator(AgentFactory factory) {
        return new AgentOrchestrator(factory);
    }

    // --- Agent Team 装配 ---

    @Bean
    public com.echomind.agent.team.messaging.TeamMessageBus teamMessageBus() {
        return new com.echomind.agent.team.messaging.TeamMessageBus();
    }

    @Bean
    public com.echomind.agent.team.visualization.TeamTraceRecorder teamTraceRecorder() {
        return new com.echomind.agent.team.visualization.TeamTraceRecorder();
    }

    @Bean
    public org.springframework.core.task.TaskExecutor teamTaskExecutor() {
        org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor executor =
            new org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("team-run-");
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(100);
        executor.initialize();
        return executor;
    }

    /** Team 默认最多协作 3 轮，防止任务循环失控。 */
    @Bean
    public com.echomind.agent.team.TeamCoordinator teamCoordinator(AgentOrchestrator orchestrator,
                                                                     com.echomind.agent.team.messaging.TeamMessageBus bus,
                                                                     com.echomind.agent.team.visualization.TeamTraceRecorder recorder) {
        return new com.echomind.agent.team.TeamCoordinator(orchestrator, bus, recorder, 3);
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (!isBlank(value)) {
                return value;
            }
        }
        return null;
    }
}
