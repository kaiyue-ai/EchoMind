package com.echomind.llm.provider;

import com.echomind.llm.router.ModelSpec;
import reactor.core.publisher.Flux;

/**
 * 模型提供商接口 —— EchoMind LLM 层的核心 SPI（服务提供者接口）。
 *
 * <p>定义与 LLM 服务提供商交互的统一契约。所有第三方模型提供商
 * （如 Anthropic、OpenAI）或 Mock 实现都必须实现此接口。
 * 该接口抽象了同步对话、流式对话和工具调用三种核心交互模式。
 *
 * <p><b>架构定位：</b>
 * 此接口位于 LLM 模块的提供商抽象层，与 {@link com.echomind.llm.router.DynamicModelRouter}
 * 和 {@link com.echomind.llm.router.ModelProviderRegistry} 协作，
 * 为上层 Agent 提供透明的模型访问能力。
 *
 * <p><b>实现要求：</b>
 * <ul>
 *   <li>{@link #providerId()} 必须返回全局唯一的标识符，用于路由匹配。</li>
 *   <li>{@link #supports(ModelSpec)} 用于运行时验证模型规格是否属于本提供商。</li>
 *   <li>所有 chat/stream 方法的错误应捕获并以 {@code "[Error] ..."} 格式返回，
 *       不应向上抛出未检查异常以影响调用链路。</li>
 *   <li>流式方法应返回 Project Reactor {@link Flux}，支持背压和取消操作。</li>
 * </ul>
 *
 * @see AnthropicProvider
 * @see OpenAiProvider
 * @see MockModelProvider
 * @see com.echomind.llm.router.ModelSpec
 */
public interface ModelProvider {

    /**
     * 返回此提供商的全局唯一标识符。
     *
     * <p>该标识符将被 {@link com.echomind.llm.router.ModelProviderRegistry}
     * 用作键值存储提供商实例，也被 {@link com.echomind.llm.router.DynamicModelRouter}
     * 用于匹配会话上下文中的首选提供商。
     *
     * @return 提供商唯一标识（如 "anthropic", "openai", "mock"）
     */
    String providerId();

    /**
     * 检查此提供商是否支持指定的模型规格。
     *
     * <p>通常通过比对模型规格中的 providerId 与自身的 providerId 来判断。
     * 用于在路由过程中验证模型分配的正确性。
     *
     * @param model 待验证的模型规格
     * @return {@code true} 如果该模型属于本提供商
     */
    boolean supports(ModelSpec model);

    /**
     * 执行同步对话 —— 发送消息并阻塞等待完整响应。
     *
     * <p>这是最常用的调用模式，适用于需要完整回复后再进行下一步处理的场景。
     * 对于需要逐步展示输出的场景，请使用 {@link #stream(ModelSpec, String, String)}。
     *
     * @param model        目标模型规格（包含模型名称等元数据）
     * @param systemPrompt 系统提示词，定义 AI 的角色和行为规范，可为 {@code null}
     * @param userMessage  用户输入消息，不能为 {@code null}
     * @return 模型返回的完整文本响应，错误时返回 {@code "[Error] ..."} 格式的字符串
     */
    String chat(ModelSpec model, String systemPrompt, String userMessage);

    /**
     * 执行流式对话 —— 以 SSE 方式逐步返回响应内容。
     *
     * <p>返回一个 Project Reactor {@link Flux} 流，每个元素是一段增量文本。
     * 适用于需要实时展示 AI 回复的用户界面场景。
     *
     * @param model        目标模型规格
     * @param systemPrompt 系统提示词，可为 {@code null}
     * @param userMessage  用户输入消息
     * @return 包含增量文本片段的响应式流，错误时返回包含错误信息的 {@link Flux#error}
     */
    Flux<String> stream(ModelSpec model, String systemPrompt, String userMessage);

    /**
     * 执行带工具调用的对话 —— 模型可以请求调用外部工具/函数。
     *
     * <p>将工具定义以 JSON 格式传递给模型，模型可根据用户意图决定是否以及如何
     * 调用这些工具。返回值包含模型的工具调用决策（而非仅文本回复）。
     *
     * <p>典型使用场景：
     * <ul>
     *   <li>天气查询 Skill → 模型判断需要调用 getWeather 工具</li>
     *   <li>计算器 Skill → 模型判断需要调用 calculate 工具</li>
     *   <li>文件系统 Skill → 模型判断需要调用 readFile/writeFile 工具</li>
     * </ul>
     *
     * @param model        目标模型规格
     * @param systemPrompt 系统提示词，可为 {@code null}
     * @param userMessage  用户输入消息
     * @param toolsJson    工具定义的 JSON 字符串，遵循对应 API 的工具格式规范
     * @return 模型返回的原始 JSON 响应（可能包含 tool_use 内容块），错误时返回 {@code "[Error] ..."} 格式
     */
    String chatWithTools(ModelSpec model, String systemPrompt, String userMessage, String toolsJson);
}
