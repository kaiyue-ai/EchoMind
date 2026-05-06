package com.echomind.llm.router;

/**
 * 模型能力枚举 —— 描述 LLM 模型具备的功能特性。
 *
 * <p>每个模型可以具备一种或多种能力。当用户请求需要特定能力时
 * （例如需要视觉理解图片，或需要流式输出），路由器会使用此枚举
 * 来匹配具备相应能力的模型。
 *
 * <p><b>设计决策：</b>
 * <ul>
 *   <li>使用枚举而非字符串常量，提供编译时类型安全和 IDE 自动补全。</li>
 *   <li>枚举值可组合使用（通过 {@code Set<ModelCapability>}），灵活表达模型的多能力特性。</li>
 *   <li>能力定义独立于具体提供商，保持路由逻辑的提供商无关性。</li>
 * </ul>
 *
 * @see ModelSpec
 * @see DynamicModelResolver#resolveForCapability
 */
public enum ModelCapability {
    /** 纯文本对话能力 —— 最基本的 LLM 能力，支持文本输入和输出 */
    TEXT,
    /** 函数/工具调用能力 —— 模型可以调用外部函数或工具（Function Calling / Tool Use） */
    FUNCTION,
    /** 视觉理解能力 —— 模型可以接收并理解图片输入（多模态） */
    VISION,
    /** 流式输出能力 —— 模型支持 SSE (Server-Sent Events) 逐字返回响应 */
    STREAM
}
