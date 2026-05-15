package com.echomind.llm.router;

import com.echomind.llm.provider.ModelProvider;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 模型提供商注册表 —— LLM 层的中央注册中心。
 * @see ModelProvider
 * @see ModelSpec
 * @see DynamicModelRouter
 */
public class ModelProviderRegistry {

    /**
     * 提供商映射表 —— providerId → ModelProvider 实例。
     * 使用 ConcurrentHashMap 保证线程安全。
     */
    private final Map<String, ModelProvider> providers = new ConcurrentHashMap<>();

    /**
     * 提供商模型映射表 —— providerId → 该提供商下的模型规格列表。
     * 使用 ConcurrentHashMap 保证线程安全。
     */
    private final Map<String, List<ModelSpec>> providerModels = new ConcurrentHashMap<>();

    /** 全局默认提供商 ID，使用 volatile 保证多线程可见性 */
    private volatile String defaultProviderId;

    /** 全局默认模型名称，使用 volatile 保证多线程可见性 */
    private volatile String defaultModelId;

    /**
     * 注册一个新的模型提供商及其支持的模型列表。
     *
     * <p>若当前尚未设置全局默认值，则自动将首个注册的提供商设为其默认提供商，
     * 并将其首个标记为默认的模型设为全局默认模型。这确保了系统在未显式配置
     * 默认值时也能正常运作。
     *
     * @param provider 模型提供商实例，不能为 {@code null}
     * @param models   该提供商支持的模型规格列表
     */
    public void registerProvider(ModelProvider provider, List<ModelSpec> models) {
        providers.put(provider.providerId(), provider);
        providerModels.put(provider.providerId(), List.copyOf(models));
        if (defaultProviderId == null) {
            defaultProviderId = provider.providerId();
            models.stream().filter(ModelSpec::isDefault).findFirst()
                .ifPresent(m -> defaultModelId = m.modelName());
        }
    }

    /**
     * 根据提供商 ID 获取对应的 {@link ModelProvider} 实例。
     *
     * @param providerId 提供商唯一标识（如 "deepseek", "openai"）
     * @return 包装在 {@link Optional} 中的 ModelProvider，未找到时为空
     */
    public Optional<ModelProvider> getProvider(String providerId) {
        return Optional.ofNullable(providers.get(providerId));
    }

    /**
     * 精确查找指定提供商下的指定模型。
     *
     * @param providerId 提供商唯一标识
     * @param modelName  模型名称
     * @return 包装在 {@link Optional} 中的 ModelSpec，未找到时为空
     */
    public Optional<ModelSpec> find(String providerId, String modelName) {
        return listByProvider(providerId).stream()
            .filter(m -> m.modelName().equals(modelName))
            .findFirst();
    }

    /**
     * 在指定提供商下查找首个具备指定能力的模型。
     *
     * <p>遍历该提供商的全部模型，返回第一个能力集合中包含目标能力的模型。
     * 该方法不关心模型的其他属性（如是否为默认），仅按能力匹配。
     *
     * @param providerId 提供商唯一标识
     * @param capability 目标能力（如 {@link ModelCapability#VISION}）
     * @return 包装在 {@link Optional} 中的 ModelSpec，未找到时为空
     */
    public Optional<ModelSpec> findByCapability(String providerId, ModelCapability capability) {
        return listByProvider(providerId).stream()
            .filter(m -> m.capabilities().contains(capability))
            .findFirst();
    }

    /**
     * 列出指定提供商下的所有模型。
     *
     * @param providerId 提供商唯一标识
     * @return 该提供商的模型列表，提供商不存在时返回空列表
     */
    public List<ModelSpec> listByProvider(String providerId) {
        return providerModels.getOrDefault(providerId, List.of());
    }

    /**
     * 列出所有已注册提供商的全部模型。
     *
     * @return 包含所有模型规格的不可修改列表
     */
    public List<ModelSpec> listAll() {
        List<ModelSpec> all = new ArrayList<>();
        providerModels.values().forEach(all::addAll);
        return Collections.unmodifiableList(all);
    }

    /**
     * 获取全局默认模型规格。
     *
     * @return 包装在 {@link Optional} 中的默认 ModelSpec，未配置时为空
     */
    public Optional<ModelSpec> defaultModel() {
        if (defaultProviderId == null || defaultModelId == null) return Optional.empty();
        return find(defaultProviderId, defaultModelId);
    }

    /**
     * 获取所有已注册的提供商 ID 集合。
     *
     * @return 不可修改的提供商 ID 集合
     */
    public Set<String> providerIds() {
        return Collections.unmodifiableSet(providers.keySet());
    }

    /**
     * 显式设置全局默认提供商和默认模型。
     *
     * <p>调用此方法会覆盖自动设置的默认值。必须在目标提供商已注册后才能调用，
     * 否则抛出异常。
     *
     * @param providerId 要设为默认的提供商 ID
     * @param modelName  要设为默认的模型名称
     * @throws IllegalArgumentException 如果 providerId 未注册
     */
    public void setDefault(String providerId, String modelName) {
        if (!providers.containsKey(providerId)) {
            throw new IllegalArgumentException("Unknown provider: " + providerId);
        }
        this.defaultProviderId = providerId;
        this.defaultModelId = modelName;
    }
}
