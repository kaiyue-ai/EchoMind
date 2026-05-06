package com.echomind.boot.properties;

import java.util.List;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * EchoMind 平台全局配置属性 —— 映射 {@code echomind.*} 命名空间的 YAML/Properties 配置。
 *
 * <p>本类通过嵌套静态内部类的方式组织各模块的配置，对应如下配置结构：
 * <pre>{@code
 * echomind:
 *   models:
 *     default-provider: anthropic
 *     providers:
 *       anthropic:
 *         api-key: ...
 *         base-url: ...
 *         models: [...]
 *   memory:
 *     short-term-window: 20
 *     long-term-type: file
 *     file-path: ./data/memory/
 *   skill:
 *     auto-load-path: ./skills/
 *     hot-reload: true
 *     marketplace-dir: ./data/marketplace/
 *   mcp:
 *     server-enabled: true
 *     transport: stdio
 *   agents:
 *     - agent-id: default
 *       name: EchoMind Assistant
 *       system-prompt: ...
 *       model-id: ...
 *       skill-ids: [...]
 * }</pre>
 *
 * <p>设计要点：
 * <ul>
 *   <li>所有属性均提供合理的默认值，确保开箱即用。</li>
 *   <li>嵌套类均声明为 {@code public static}，支持 Spring Boot 的
 *       {@code @ConfigurationProperties} 宽松绑定。</li>
 *   <li>Agent 定义列表 {@code agents} 允许在配置文件中声明式定义多 Agent。</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "echomind")
public class EchoMindProperties {

    /** 模型配置（提供商、默认模型等） */
    private Models models = new Models();
    /** 记忆系统配置（短期窗口、长期存储等） */
    private Memory memory = new Memory();
    /** 技能系统配置（自动加载路径、热重载等） */
    private Skill skill = new Skill();
    /** MCP 协议配置（服务端开关、传输方式等） */
    private Mcp mcp = new Mcp();
    /** 预定义 Agent 列表 */
    private List<AgentDef> agents = List.of();

    public Models getModels() { return models; }
    public void setModels(Models models) { this.models = models; }
    public Memory getMemory() { return memory; }
    public void setMemory(Memory memory) { this.memory = memory; }
    public Skill getSkill() { return skill; }
    public void setSkill(Skill skill) { this.skill = skill; }
    public Mcp getMcp() { return mcp; }
    public void setMcp(Mcp mcp) { this.mcp = mcp; }
    public List<AgentDef> getAgents() { return agents; }
    public void setAgents(List<AgentDef> agents) { this.agents = agents; }

    /**
     * 模型子系统配置 —— 管理 LLM 提供商及其模型定义。
     *
     * <p>支持配置多个提供商（如 anthropic、openai），
     * 每个提供商可以绑定多个模型并设置默认模型。
     */
    public static class Models {
        /** 默认提供商 ID，未指定时使用 "anthropic" */
        private String defaultProvider = "anthropic";
        /** 提供商配置映射，key 为提供商 ID（如 "anthropic"），value 为提供商配置 */
        private Map<String, ProviderConfig> providers = Map.of();

        public String getDefaultProvider() { return defaultProvider; }
        public void setDefaultProvider(String v) { this.defaultProvider = v; }
        public Map<String, ProviderConfig> getProviders() { return providers; }
        public void setProviders(Map<String, ProviderConfig> v) { this.providers = v; }
    }

    /**
     * 单个 LLM 提供商的配置。
     *
     * <p>包含 API 密钥、基础 URL 以及该提供商下可用的模型列表。
     * API 密钥也可以通过环境变量（如 {@code ANTHROPIC_API_KEY}）提供，
     * 配置文件和环境变量二者的优先级由 {@link com.echomind.boot.autoconfigure.EchoMindAutoConfiguration}
     * 中的 Bean 创建逻辑决定。
     */
    public static class ProviderConfig {
        /** API 密钥（可被环境变量覆盖） */
        private String apiKey;
        /** API 基础 URL（可被环境变量覆盖） */
        private String baseUrl;
        /** 该提供商下的模型列表 */
        private List<ModelConfig> models = List.of();

        public String getApiKey() { return apiKey; }
        public void setApiKey(String v) { this.apiKey = v; }
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String v) { this.baseUrl = v; }
        public List<ModelConfig> getModels() { return models; }
        public void setModels(List<ModelConfig> v) { this.models = v; }
    }

    /**
     * 单个 LLM 模型的配置。
     *
     * <p>定义模型名称、能力集合（如 TEXT、FUNCTION、VISION 等）
     * 以及是否作为该提供商的默认模型。
     */
    public static class ModelConfig {
        /** 模型名称，如 "claude-sonnet-4-20250514" */
        private String name;
        /** 模型能力标签列表，如 ["TEXT", "FUNCTION"] */
        private List<String> capabilities = List.of();
        /** 是否为该提供商的默认模型 */
        private boolean isDefault;

        public String getName() { return name; }
        public void setName(String v) { this.name = v; }
        public List<String> getCapabilities() { return capabilities; }
        public void setCapabilities(List<String> v) { this.capabilities = v; }
        public boolean isDefault() { return isDefault; }
        public void setDefault(boolean v) { this.isDefault = v; }
    }

    /**
     * 记忆子系统配置 —— 控制短期窗口和长期持久化行为。
     */
    public static class Memory {
        /** 短期窗口最大消息数，默认 20 条 */
        private int shortTermWindow = 20;
        /** 长期存储类型：file 或 redis，默认 "file" */
        private String longTermType = "file";
        /** 文件模式下的持久化目录，默认 "./data/memory/" */
        private String filePath = "./data/memory/";
        /** Redis 模式下会话记忆的 TTL（秒），默认 604800（7 天）；0 或负数表示永不过期 */
        private long redisTtlSeconds = 604800;

        public int getShortTermWindow() { return shortTermWindow; }
        public void setShortTermWindow(int v) { this.shortTermWindow = v; }
        public String getLongTermType() { return longTermType; }
        public void setLongTermType(String v) { this.longTermType = v; }
        public String getFilePath() { return filePath; }
        public void setFilePath(String v) { this.filePath = v; }
        public long getRedisTtlSeconds() { return redisTtlSeconds; }
        public void setRedisTtlSeconds(long v) { this.redisTtlSeconds = v; }
    }

    /**
     * 技能子系统配置 —— 控制技能自动加载和热重载行为。
     */
    public static class Skill {
        /** 技能 JAR 自动加载目录，默认 "./skills/" */
        private String autoLoadPath = "./skills/";
        /** 是否启用热重载（监听目录变化自动重新加载），默认 true */
        private boolean hotReload = true;
        /** 技能市场持久化目录，默认 "./data/marketplace/" */
        private String marketplaceDir = "./data/marketplace/";

        public String getAutoLoadPath() { return autoLoadPath; }
        public void setAutoLoadPath(String v) { this.autoLoadPath = v; }
        public boolean isHotReload() { return hotReload; }
        public void setHotReload(boolean v) { this.hotReload = v; }
        public String getMarketplaceDir() { return marketplaceDir; }
        public void setMarketplaceDir(String v) { this.marketplaceDir = v; }
    }

    /**
     * MCP 协议子系统配置 —— 控制 MCP 服务端行为。
     */
    public static class Mcp {
        /** 是否启用 MCP 服务端，默认 true */
        private boolean serverEnabled = true;
        /** 传输方式：stdio 或 http，默认 "stdio" */
        private String transport = "stdio";

        public boolean isServerEnabled() { return serverEnabled; }
        public void setServerEnabled(boolean v) { this.serverEnabled = v; }
        public String getTransport() { return transport; }
        public void setTransport(String v) { this.transport = v; }
    }

    /**
     * 单个 Agent 的配置定义 —— 用于在配置文件中声明式定义 Agent。
     *
     * <p>每个 Agent 定义包括：唯一标识、显示名称、系统提示词、
     * 绑定的模型 ID 以及可调用的技能 ID 列表。
     */
    public static class AgentDef {
        /** Agent 唯一标识 */
        private String agentId;
        /** Agent 显示名称 */
        private String name;
        /** 系统提示词，定义 Agent 的角色和行为 */
        private String systemPrompt;
        /** 绑定的模型 ID，格式为 "providerId:modelName" */
        private String modelId;
        /** 可调用的技能 ID 列表 */
        private List<String> skillIds = List.of();

        public String getAgentId() { return agentId; }
        public void setAgentId(String v) { this.agentId = v; }
        public String getName() { return name; }
        public void setName(String v) { this.name = v; }
        public String getSystemPrompt() { return systemPrompt; }
        public void setSystemPrompt(String v) { this.systemPrompt = v; }
        public String getModelId() { return modelId; }
        public void setModelId(String v) { this.modelId = v; }
        public List<String> getSkillIds() { return skillIds; }
        public void setSkillIds(List<String> v) { this.skillIds = v; }
    }
}
