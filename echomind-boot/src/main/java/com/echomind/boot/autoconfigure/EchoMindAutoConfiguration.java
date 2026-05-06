package com.echomind.boot.autoconfigure;

import com.echomind.agent.AgentConfig;
import com.echomind.agent.AgentFactory;
import com.echomind.agent.orchestration.AgentOrchestrator;
import com.echomind.agent.orchestration.TaskRouter;
import com.echomind.agent.pipeline.ExecutionPipeline;
import com.echomind.agent.pipeline.PipelineStage;
import com.echomind.agent.pipeline.stages.*;
import com.echomind.boot.properties.EchoMindProperties;
import com.echomind.llm.provider.AnthropicProvider;
import com.echomind.llm.provider.MockModelProvider;
import com.echomind.llm.provider.ModelProvider;
import com.echomind.llm.provider.OpenAiProvider;
import com.echomind.llm.router.*;
import com.echomind.common.model.SkillState;
import com.echomind.mcp.server.MCPServer;
import com.echomind.mcp.server.SkillToolProvider;
import com.echomind.memory.MemoryManager;
import com.echomind.memory.longterm.FileLongTermStore;
import com.echomind.memory.longterm.LongTermMemoryStore;
import com.echomind.memory.longterm.RedisLongTermStore;
import com.echomind.memory.session.SessionConfig;
import com.echomind.memory.session.SessionManager;
import com.echomind.memory.shortterm.WindowConfig;
import com.echomind.skill.loader.SkillDirectoryWatcher;
import com.echomind.skill.loader.SkillJarLoader;
import com.echomind.skill.marketplace.MarketplaceService;
import com.echomind.skill.marketplace.SkillEntityRepository;
import com.echomind.skill.orchestrator.SkillOrchestrator;
import com.echomind.skill.registry.SkillRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;

import java.nio.file.Paths;
import java.util.*;

/**
 * EchoMind 自动配置类 —— Spring Boot 自动装配的核心入口。
 *
 * <p>本配置类负责创建 EchoMind 平台所有核心 Bean，按模块分为以下几组：
 * <ol>
 *   <li><b>配置属性</b>：{@link EchoMindProperties} 作为统一配置持有者</li>
 *   <li><b>LLM 层</b>：模型提供商（Anthropic、OpenAI、Mock）、注册中心、动态路由器</li>
 *   <li><b>记忆层</b>：窗口配置、长期存储、会话管理、记忆管理器</li>
 *   <li><b>技能层</b>：注册中心、JAR 加载器、编排器、目录监视器、市场服务</li>
 *   <li><b>Agent 层</b>：执行管线（5 阶段）、Agent 工厂、编排器、任务路由器</li>
 *   <li><b>Agent Team 层</b>：消息总线、跟踪记录器、团队协调器</li>
 *   <li><b>MCP 层</b>：MCP 服务端（条件启用）</li>
 * </ol>
 *
 * <p>设计要点：
 * <ul>
 *   <li>使用 {@link ConditionalOnProperty} 实现可选模块的条件装配（如 OpenAI、MCP）。</li>
 *   <li>所有 LLM 模型注册集中在 {@code llmInitializer} Bean 中完成，
 *       确保启动时模型配置已就绪。</li>
 *   <li>Agent 自动注册：从配置文件读取 Agent 定义并批量创建；
 *       若无配置则自动创建默认 Agent。</li>
 *   <li>技能热重载：通过 {@link SkillDirectoryWatcher#start()} 按需启动目录监听。</li>
 * </ul>
 */
@Configuration
@EnableConfigurationProperties(EchoMindProperties.class)
public class EchoMindAutoConfiguration {

    /** 日志记录器 */
    private static final Logger log = LoggerFactory.getLogger(EchoMindAutoConfiguration.class);

    // --- LLM ---

    /**
     * 创建模型提供商注册中心。
     *
     * <p>管理所有已注册的 LLM 提供商及其模型列表。
     *
     * @return 模型提供商注册中心实例
     */
    @Bean
    public ModelProviderRegistry modelProviderRegistry() {
        return new ModelProviderRegistry();
    }

    /**
     * 创建动态模型路由器。
     *
     * <p>根据会话上下文中的模型偏好，动态选择最合适的模型。
     *
     * @param registry 模型提供商注册中心
     * @return 动态模型路由器实例
     */
    @Bean
    public DynamicModelRouter dynamicModelRouter(ModelProviderRegistry registry) {
        return new DynamicModelRouter(registry);
    }

    /**
     * 创建 Anthropic 模型提供商。
     *
     * <p>API 密钥优先从配置文件读取，其次从环境变量 {@code ANTHROPIC_API_KEY} 获取。
     * Base URL 同理，默认使用 {@code https://api.anthropic.com}。
     *
     * @param props EchoMind 配置属性
     * @return Anthropic 提供商实例
     */
    @Bean
    public AnthropicProvider anthropicProvider(EchoMindProperties props) {
        var providers = props.getModels().getProviders();
        var config = providers.getOrDefault("anthropic", new EchoMindProperties.ProviderConfig());
        String key = config.getApiKey() != null ? config.getApiKey() : System.getenv("ANTHROPIC_API_KEY");
        String url = config.getBaseUrl() != null ? config.getBaseUrl() : System.getenv("ANTHROPIC_BASE_URL");
        if (url == null) url = "https://api.anthropic.com";
        return new AnthropicProvider(key, url);
    }

    /**
     * 创建 OpenAI 模型提供商（条件装配）。
     *
     * <p>仅当配置属性 {@code echomind.models.providers.openai.api-key} 存在时才创建此 Bean。
     *
     * @param props EchoMind 配置属性
     * @return OpenAI 提供商实例；若条件不满足则不创建
     */
    @Bean
    @ConditionalOnProperty(name = "echomind.models.providers.openai.api-key")
    public OpenAiProvider openAiProvider(EchoMindProperties props) {
        var config = props.getModels().getProviders().get("openai");
        String key = config.getApiKey();
        String url = config.getBaseUrl() != null ? config.getBaseUrl() : "https://api.openai.com";
        return new OpenAiProvider(key, url);
    }

    /**
     * 创建 Mock 模型提供商 —— 用于开发和测试环境。
     *
     * <p>无需真实 API 密钥即可响应请求，返回模拟的 LLM 输出。
     *
     * @return Mock 提供商实例
     */
    @Bean
    public MockModelProvider mockModelProvider() {
        return new MockModelProvider();
    }

    /**
     * LLM 初始化器 —— 注册所有模型提供商及其模型列表。
     *
     * <p>此方法在启动时执行以下操作：
     * <ol>
     *   <li>注册 Anthropic 模型（从配置或默认使用 claude-sonnet-4-20250514）</li>
     *   <li>如果 OpenAI 可用，注册其模型列表</li>
     *   <li>注册 Mock 模型（mock-model）</li>
     * </ol>
     *
     * <p>返回一个 Object 哨兵值以确保 Spring 容器中注册了此初始化 Bean。
     *
     * @param registry  模型提供商注册中心
     * @param anthropic Anthropic 提供商
     * @param openAi    OpenAI 提供商（可选）
     * @param mock      Mock 提供商
     * @param props     EchoMind 配置属性
     * @return 初始化哨兵对象（仅用于确保 Bean 被创建）
     */
    @Bean
    public Object llmInitializer(ModelProviderRegistry registry, AnthropicProvider anthropic,
                                 Optional<OpenAiProvider> openAi, MockModelProvider mock,
                                 EchoMindProperties props) {
        // 注册 Anthropic 模型
        var anthropicConfig = props.getModels().getProviders().get("anthropic");
        List<ModelSpec> anthropicModels = new ArrayList<>();
        if (anthropicConfig != null && anthropicConfig.getModels() != null) {
            for (var mc : anthropicConfig.getModels()) {
                anthropicModels.add(new ModelSpec("anthropic", mc.getName(),
                    parseCapabilities(mc.getCapabilities()), mc.isDefault()));
            }
        }
        if (anthropicModels.isEmpty()) {
            anthropicModels.add(new ModelSpec("anthropic", "claude-sonnet-4-20250514",
                Set.of(ModelCapability.TEXT, ModelCapability.FUNCTION), true));
        }
        registry.registerProvider(anthropic, anthropicModels);

        // 注册 OpenAI 模型
        openAi.ifPresent(oa -> {
            var oaConfig = props.getModels().getProviders().get("openai");
            List<ModelSpec> oaModels = new ArrayList<>();
            if (oaConfig != null && oaConfig.getModels() != null) {
                for (var mc : oaConfig.getModels()) {
                    oaModels.add(new ModelSpec("openai", mc.getName(),
                        parseCapabilities(mc.getCapabilities()), mc.isDefault()));
                }
            }
            if (!oaModels.isEmpty()) {
                registry.registerProvider(oa, oaModels);
            }
        });

        // 注册 Mock 提供商
        registry.registerProvider(mock, List.of(
            new ModelSpec("mock", "mock-model", Set.of(ModelCapability.TEXT, ModelCapability.FUNCTION), false)
        ));

        log.info("LLM providers registered: {}", registry.providerIds());
        return new Object();
    }

    /**
     * 将字符串形式的能力标签解析为 {@link ModelCapability} 枚举集合。
     *
     * <p>忽略无效的能力标签（不影响其他有效标签的解析）。
     * 若所有标签均无效或列表为空，默认返回 {@link ModelCapability#TEXT}。
     *
     * @param caps 能力标签字符串列表（如 ["TEXT", "FUNCTION"]）
     * @return 模型能力枚举集合，至少包含 TEXT
     */
    private Set<ModelCapability> parseCapabilities(List<String> caps) {
        if (caps == null || caps.isEmpty()) return Set.of(ModelCapability.TEXT);
        Set<ModelCapability> result = EnumSet.noneOf(ModelCapability.class);
        for (String c : caps) {
            try { result.add(ModelCapability.valueOf(c.toUpperCase())); } catch (IllegalArgumentException ignored) {}
        }
        return result.isEmpty() ? Set.of(ModelCapability.TEXT) : result;
    }

    // --- Memory ---

    /**
     * 创建短期窗口配置。
     *
     * <p>控制对话短期记忆窗口的最大消息数。
     *
     * @param props EchoMind 配置属性
     * @return 窗口配置实例
     */
    @Bean
    public WindowConfig windowConfig(EchoMindProperties props) {
        return new WindowConfig(props.getMemory().getShortTermWindow(), 10);
    }

    /**
     * 创建长期记忆存储 —— 根据配置选择文件或 Redis 实现。
     *
     * <p>当 {@code echomind.memory.long-term-type=redis} 且 Redis 可用时，
     * 创建 {@link RedisLongTermStore}；否则默认使用 {@link FileLongTermStore}。
     *
     * @param props EchoMind 配置属性
     * @param redisConnectionFactory Redis 连接工厂（由 Spring Boot 自动配置提供，可为 null）
     * @return 长期记忆存储实例
     */
    @Bean
    public LongTermMemoryStore longTermMemoryStore(EchoMindProperties props,
                                                   ObjectProvider<RedisConnectionFactory> redisConnectionFactory) {
        if ("redis".equalsIgnoreCase(props.getMemory().getLongTermType())) {
            RedisConnectionFactory factory = redisConnectionFactory.getIfAvailable();
            if (factory != null) {
                log.info("Using RedisLongTermStore with TTL={}s", props.getMemory().getRedisTtlSeconds());
                return new RedisLongTermStore(factory, props.getMemory().getRedisTtlSeconds());
            }
            log.warn("long-term-type=redis but no RedisConnectionFactory available, falling back to FileLongTermStore");
        }
        return new FileLongTermStore(props.getMemory().getFilePath());
    }

    /**
     * 创建会话管理器。
     *
     * <p>管理多会话的生命周期和状态。
     *
     * @return 会话管理器实例
     */
    @Bean
    public SessionManager sessionManager() {
        return new SessionManager(new SessionConfig());
    }

    /**
     * 创建记忆管理器 —— 统一短期窗口和长期存储的访问层。
     *
     * @param wc 短期窗口配置
     * @param lts 长期记忆存储
     * @param sm 会话管理器
     * @return 记忆管理器实例
     */
    @Bean
    public MemoryManager memoryManager(WindowConfig wc, LongTermMemoryStore lts, SessionManager sm) {
        return new MemoryManager(wc, lts, sm);
    }

    // --- Skill ---

    /**
     * 创建技能注册中心。
     *
     * <p>维护所有已加载技能实例的映射。
     *
     * @return 技能注册中心实例
     */
    @Bean
    public SkillRegistry skillRegistry() {
        return new SkillRegistry();
    }

    /**
     * 创建技能 JAR 加载器。
     *
     * <p>负责从 JAR 文件加载技能类，支持类加载器隔离。
     *
     * @return 技能 JAR 加载器实例
     */
    @Bean
    public SkillJarLoader skillJarLoader() {
        return new SkillJarLoader();
    }

    /**
     * 创建技能编排器。
     *
     * <p>协调多个技能的并发调用和结果聚合。
     *
     * @param registry 技能注册中心
     * @return 技能编排器实例
     */
    @Bean
    public SkillOrchestrator skillOrchestrator(SkillRegistry registry) {
        return new SkillOrchestrator(registry);
    }

    /**
     * 创建技能目录监视器。
     *
     * <p>监听技能自动加载目录的变更，实现技能 JAR 的热重载。
     * 若配置启用热重载（{@code echomind.skill.hot-reload=true}），则立即启动监听。
     *
     * @param props    EchoMind 配置属性
     * @param loader   技能 JAR 加载器
     * @param registry 技能注册中心
     * @return 技能目录监视器实例（根据配置决定是否已启动）
     */
    @Bean
    public SkillDirectoryWatcher skillDirectoryWatcher(EchoMindProperties props,
                                                        SkillJarLoader loader, SkillRegistry registry) {
        var watcher = new SkillDirectoryWatcher(
            Paths.get(props.getSkill().getAutoLoadPath()), loader, registry);
        if (props.getSkill().isHotReload()) {
            watcher.start();
        }
        return watcher;
    }

    /**
     * 创建技能实体仓库（条件装配）。
     *
     * <p>若容器中尚无此类型的 Bean，则返回 null，由 Spring Data JPA 自动配置提供。
     *
     * @return null（委托 Spring Data JPA 自动配置）
     */
    @Bean
    @ConditionalOnMissingBean
    public SkillEntityRepository skillEntityRepository() {
        return null; // 将由 Spring Data JPA 自动配置提供
    }

    /**
     * 创建技能市场服务。
     *
     * <p>管理技能的持久化、上传/下载以及技能元数据的 CRUD。
     *
     * @param repository JPA 仓库
     * @param loader     技能 JAR 加载器
     * @param registry   技能注册中心
     * @param props      EchoMind 配置属性
     * @return 技能市场服务实例
     */
    @Bean
    public MarketplaceService marketplaceService(SkillEntityRepository repository,
                                                  SkillJarLoader loader, SkillRegistry registry,
                                                  EchoMindProperties props) {
        return new MarketplaceService(repository, loader, registry, props.getSkill().getMarketplaceDir());
    }

    // --- Agent ---

    /**
     * 创建执行管线 —— 由 5 个阶段组成的处理链。
     *
     * <p>管线阶段按顺序执行：
     * <ol>
     *   <li>{@link ContextEnrichStage} —— 上下文增强（加载记忆、会话信息）</li>
     *   <li>{@link ToolResolutionStage} —— 工具解析（确定需要调用的技能）</li>
     *   <li>{@link SkillInvocationStage} —— 技能调用（并发执行选中的技能）</li>
     *   <li>{@link ResultAggregationStage} —— 结果聚合（LLM 整合技能返回）</li>
     *   <li>{@link MemoryPersistStage} —— 记忆持久化（保存对话记录）</li>
     * </ol>
     *
     * @param memory      记忆管理器
     * @param skillOrch   技能编排器
     * @param router      动态模型路由器
     * @param providerReg 模型提供商注册中心
     * @return 执行管线实例
     */
    @Bean
    public ExecutionPipeline executionPipeline(MemoryManager memory, SkillOrchestrator skillOrch,
                                                DynamicModelRouter router, ModelProviderRegistry providerReg) {
        List<PipelineStage> stages = List.of(
            new ContextEnrichStage(memory),
            new ToolResolutionStage(router),
            new SkillInvocationStage(skillOrch),
            new ResultAggregationStage(router, providerReg),
            new MemoryPersistStage(memory)
        );
        return new ExecutionPipeline(stages);
    }

    /**
     * 创建 Agent 工厂 —— 负责 Agent 实例的创建和生命周期管理。
     *
     * <p>启动时从配置文件读取 Agent 定义并批量创建；若未配置任何 Agent，
     * 则自动创建一个默认的 "EchoMind Assistant" Agent。
     *
     * @param pipeline 执行管线
     * @param props    EchoMind 配置属性
     * @return Agent 工厂实例（含所有预配置的 Agent）
     */
    @Bean
    public AgentFactory agentFactory(ExecutionPipeline pipeline, EchoMindProperties props) {
        AgentFactory factory = new AgentFactory(pipeline);
        for (var def : props.getAgents()) {
            AgentConfig config = new AgentConfig();
            config.setAgentId(def.getAgentId());
            config.setName(def.getName());
            config.setSystemPrompt(def.getSystemPrompt());
            config.setModelId(def.getModelId());
            config.setSkillIds(def.getSkillIds());
            factory.create(config);
        }
        // 若无配置则创建默认 Agent
        if (factory.allAgents().isEmpty()) {
            AgentConfig config = new AgentConfig();
            config.setAgentId("default");
            config.setName("EchoMind Assistant");
            config.setSystemPrompt("You are a helpful AI assistant with access to various skills.");
            config.setModelId("anthropic:claude-sonnet-4-20250514");
            config.setSkillIds(List.of("weather-query", "calculator", "web-search", "filesystem"));
            factory.create(config);
        }
        return factory;
    }

    /**
     * 创建 Agent 编排器。
     *
     * <p>接收用户请求，选择合适的 Agent，驱动执行管线完成全流程处理。
     *
     * @param factory Agent 工厂
     * @return Agent 编排器实例
     */
    @Bean
    public AgentOrchestrator agentOrchestrator(AgentFactory factory) {
        return new AgentOrchestrator(factory);
    }

    /**
     * 创建任务路由器。
     *
     * <p>根据用户输入意图匹配最合适的技能或 Agent。
     *
     * @param orchestrator 技能编排器
     * @return 任务路由器实例
     */
    @Bean
    public TaskRouter taskRouter(SkillOrchestrator orchestrator) {
        return new TaskRouter(orchestrator);
    }

    // --- Agent Team ---

    /**
     * 创建团队消息总线 —— Agent Team 内部通信通道。
     *
     * <p>基于内存阻塞队列实现，支持发布/订阅模式。
     *
     * @return 团队消息总线实例
     */
    @Bean
    public com.echomind.agent.team.messaging.TeamMessageBus teamMessageBus() {
        return new com.echomind.agent.team.messaging.TeamMessageBus();
    }

    /**
     * 创建团队跟踪记录器 —— 记录 Team 协作流程事件。
     *
     * <p>用于生成 Mermaid 流程图和调试分析。
     *
     * @return 团队跟踪记录器实例
     */
    @Bean
    public com.echomind.agent.team.visualization.TeamTraceRecorder teamTraceRecorder() {
        return new com.echomind.agent.team.visualization.TeamTraceRecorder();
    }

    /**
     * 创建团队协调器 —— 驱动 Planner→Executor→Reviewer 协作流程。
     *
     * <p>设置最大轮次为 3，防止无限循环。
     *
     * @param orchestrator Agent 编排器
     * @param bus          团队消息总线
     * @param recorder     团队跟踪记录器
     * @return 团队协调器实例
     */
    @Bean
    public com.echomind.agent.team.TeamCoordinator teamCoordinator(AgentOrchestrator orchestrator,
                                                                     com.echomind.agent.team.messaging.TeamMessageBus bus,
                                                                     com.echomind.agent.team.visualization.TeamTraceRecorder recorder) {
        return new com.echomind.agent.team.TeamCoordinator(orchestrator, bus, recorder, 3);
    }

    // --- MCP ---

    /**
     * 创建 MCP 服务端（条件装配）。
     *
     * <p>仅当 {@code echomind.mcp.server-enabled=true}（默认为 true）时启用。
     *
     * @return MCP 服务端实例
     */
    @Bean
    @ConditionalOnProperty(name = "echomind.mcp.server-enabled", havingValue = "true", matchIfMissing = true)
    public MCPServer mcpServer() {
        return new MCPServer("EchoMind-MCP", "1.0.0");
    }

    /**
     * 将所有已注册的 Skill 自动包装为 MCP ToolProvider 并注册到 MCPServer。
     *
     * <p>此 Bean 依赖 {@code skillDirectoryWatcher}，确保在 Skill 目录自动加载完成后再注册 MCP 工具。
     * 每个 Skill 通过 {@link SkillToolProvider} 适配器包装为 MCP 工具提供者。
     *
     * @param mcpServer MCP 服务端
     * @param registry  技能注册中心（包含所有已加载的 Skill）
     * @param watcher   技能目录监视器（仅用于声明依赖顺序）
     * @param props     EchoMind 配置属性
     * @return 初始化哨兵对象
     */
    @Bean
    public Object mcpSkillRegistrar(MCPServer mcpServer, SkillRegistry registry,
                                    SkillDirectoryWatcher watcher, EchoMindProperties props) {
        if (!props.getMcp().isServerEnabled()) {
            return new Object();
        }
        int count = 0;
        for (var reg : registry.listAll()) {
            if (reg.getState() == SkillState.ENABLED) {
                SkillToolProvider adapter = new SkillToolProvider(reg.getSkill(), reg.getSkillId());
                mcpServer.registerToolProvider(adapter);
                count++;
                log.info("Registered Skill as MCP tool: {}", reg.getSkillId());
            }
        }
        log.info("MCP auto-registration complete: {} skills registered as MCP tools", count);
        return new Object();
    }
}
