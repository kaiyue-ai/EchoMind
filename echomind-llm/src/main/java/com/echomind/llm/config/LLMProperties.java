package com.echomind.llm.config;

import java.util.List;
import java.util.Map;

/**
 * LLM 模块配置属性 —— Spring Boot 配置绑定类。
 *
 * <p>映射 {@code application.yml} 中 {@code echomind.llm.*} 命名空间下的
 * 所有 LLM 相关配置。包含全局默认提供商设置以及各提供商的 API 密钥、
 * 端点 URL 和模型列表配置。
 *
 * <p><b>典型 YAML 配置示例：</b>
 * <pre>{@code
 * echomind:
 *   llm:
 *     default-provider: anthropic
 *     providers:
 *       anthropic:
 *         api-key: ${ANTHROPIC_API_KEY}
 *         base-url: https://api.anthropic.com
 *         models:
 *           - name: claude-sonnet-4-20250514
 *             capabilities: [TEXT, FUNCTION, VISION, STREAM]
 *             is-default: true
 *       openai:
 *         api-key: ${OPENAI_API_KEY}
 *         base-url: https://api.openai.com
 *         models:
 *           - name: gpt-4o
 *             capabilities: [TEXT, FUNCTION, VISION]
 *             is-default: false
 * }</pre>
 *
 * <p><b>类结构：</b>
 * <ul>
 *   <li>{@link LLMProperties} — 顶级配置，包含默认提供商和多提供商 Map</li>
 *   <li>{@link ProviderConfig} — 单个提供商的配置（密钥、URL、模型列表）</li>
 *   <li>{@link ModelConfig} — 单个模型的配置（名称、能力、是否默认）</li>
 * </ul>
 *
 * <p><b>设计决策：</b>
 * <ul>
 *   <li>使用标准 JavaBean 命名，兼容 Spring Boot 的 {@code @ConfigurationProperties} 绑定。</li>
 *   <li>默认提供商设为 "anthropic"，可在配置文件中覆盖。</li>
 *   <li>能力字段在配置层为字符串列表，在运行时由 {@code LLMAutoConfiguration}
 *       转换为 {@link com.echomind.llm.router.ModelCapability} 枚举。</li>
 * </ul>
 *
 * @see com.echomind.llm.router.ModelCapability
 * @see com.echomind.llm.router.ModelSpec
 */
public class LLMProperties {

    /** 全局默认提供商 ID，默认为 "anthropic" */
    private String defaultProvider = "anthropic";

    /** 提供商配置映射 —— providerId → ProviderConfig */
    private Map<String, ProviderConfig> providers = Map.of();

    /** @return 全局默认提供商 ID */
    public String getDefaultProvider() { return defaultProvider; }
    /** @param defaultProvider 全局默认提供商 ID */
    public void setDefaultProvider(String defaultProvider) { this.defaultProvider = defaultProvider; }
    /** @return 提供商配置映射 */
    public Map<String, ProviderConfig> getProviders() { return providers; }
    /** @param providers 提供商配置映射 */
    public void setProviders(Map<String, ProviderConfig> providers) { this.providers = providers; }

    /**
     * 单个模型提供商的配置。
     *
     * <p>包含该提供商的 API 认证信息、服务端点和可用模型列表。
     * API 密钥通常从环境变量注入，避免硬编码到配置文件中。
     */
    public static class ProviderConfig {
        /** API 密钥（如 sk-ant-..., sk-...），从环境变量注入 */
        private String apiKey;
        /** API 基础 URL，为 {@code null} 时各 Provider 使用内置默认值 */
        private String baseUrl;
        /** 该提供商下的可用模型配置列表 */
        private List<ModelConfig> models = List.of();

        /** @return API 密钥 */
        public String getApiKey() { return apiKey; }
        /** @param apiKey API 密钥 */
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        /** @return API 基础 URL */
        public String getBaseUrl() { return baseUrl; }
        /** @param baseUrl API 基础 URL */
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        /** @return 模型配置列表 */
        public List<ModelConfig> getModels() { return models; }
        /** @param models 模型配置列表 */
        public void setModels(List<ModelConfig> models) { this.models = models; }
    }

    /**
     * 单个模型的配置。
     *
     * <p>定义模型的名称、能力列表和是否为该提供商的默认模型。
     * 能力字段在 YAML 中为字符串列表，在运行时会被转换为
     * {@link com.echomind.llm.router.ModelCapability} 枚举值。
     */
    public static class ModelConfig {
        /** 模型名称（如 claude-sonnet-4-20250514, gpt-4o） */
        private String name;
        /** 模型能力列表（字符串形式，运行时转换为 ModelCapability 枚举） */
        private List<String> capabilities = List.of();
        /** 是否为该提供商下的默认模型 */
        private boolean isDefault;

        /** @return 模型名称 */
        public String getName() { return name; }
        /** @param name 模型名称 */
        public void setName(String name) { this.name = name; }
        /** @return 能力列表（字符串形式） */
        public List<String> getCapabilities() { return capabilities; }
        /** @param capabilities 能力列表（字符串形式） */
        public void setCapabilities(List<String> capabilities) { this.capabilities = capabilities; }
        /** @return 是否为默认模型 */
        public boolean isDefault() { return isDefault; }
        /** @param isDefault 是否为默认模型 */
        public void setDefault(boolean isDefault) { this.isDefault = isDefault; }
    }
}
