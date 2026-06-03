package com.echomind.llm.router;

import java.util.Set;

/**
 * 模型规格定义 —— 描述一个 LLM 模型的完整元数据。
 * @param providerId   模型所属提供商的唯一标识（如 "deepseek", "openai", "mock"）
 * @param modelName    模型名称（如 "deepseek-chat", "gpt-4o"）
 * @param capabilities 该模型支持的能力集合，参见 {@link ModelCapability}
 * @param isDefault    是否为该提供商的默认模型（当用户未指定具体模型时使用）
 *
 * @see ModelCapability
 * @see DynamicModelRouter
 * @see ModelProviderRegistry
 */
public record ModelSpec(
    // 模型id
    String providerId,
    // 模型名称
    String modelName,
    // 模型能力
    Set<ModelCapability> capabilities,
    // 是否为默认模型
    boolean isDefault
) {}
