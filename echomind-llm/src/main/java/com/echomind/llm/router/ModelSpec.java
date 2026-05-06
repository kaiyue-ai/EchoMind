package com.echomind.llm.router;

import java.util.Set;

/**
 * 模型规格定义 —— 描述一个 LLM 模型的完整元数据。
 *
 * <p>这是一个不可变的 {@code record}，用于在路由过程中标识和匹配具体的模型实例。
 * 每个模型规格包含所属提供商、模型名称、能力集合以及是否为默认模型的标记，
 * 被 {@link DynamicModelRouter} 和 {@link ModelProviderRegistry} 共同使用。
 *
 * <p><b>设计决策：</b>
 * <ul>
 *   <li>使用 Java {@code record} 保证不可变性，避免并发场景下的状态不一致问题。</li>
 *   <li>能力集合使用 {@link Set} 而非固定字段，支持未来扩展新的模型能力（如多模态、推理等）。</li>
 *   <li>{@code isDefault} 标志允许在未明确指定模型时自动回退到默认模型。</li>
 * </ul>
 *
 * @param providerId   模型所属提供商的唯一标识（如 "anthropic", "openai", "mock"）
 * @param modelName    模型名称（如 "claude-sonnet-4-20250514", "gpt-4o"）
 * @param capabilities 该模型支持的能力集合，参见 {@link ModelCapability}
 * @param isDefault    是否为该提供商的默认模型（当用户未指定具体模型时使用）
 *
 * @see ModelCapability
 * @see DynamicModelRouter
 * @see ModelProviderRegistry
 */
public record ModelSpec(
    String providerId,
    String modelName,
    Set<ModelCapability> capabilities,
    boolean isDefault
) {}
