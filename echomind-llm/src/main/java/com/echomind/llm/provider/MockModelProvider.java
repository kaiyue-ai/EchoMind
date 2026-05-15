package com.echomind.llm.provider;

import com.echomind.llm.router.ModelSpec;
import reactor.core.publisher.Flux;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Mock 模型提供商 —— 用于开发和测试环境的模拟 LLM 实现。
 *
 * <p>不进行任何实际的 API 调用，而是根据预配置的关键词匹配规则返回预设响应，
 * 或简单地回显用户输入。适用于以下场景：
 *
 * <ul>
 *   <li><b>单元测试：</b> 无需 API 密钥即可验证 Agent 流水线的完整链路。</li>
 *   <li><b>本地开发：</b> 快速原型验证，避免依赖外部网络和 API 配额。</li>
 *   <li><b>演示环境：</b> 提供可预测的响应，便于 UI 调试和功能演示。</li>
 * </ul>
 *
 * <p><b>工作机制：</b>
 * <ol>
 *   <li>检查用户消息是否包含已注册的关键词</li>
 *   <li>若匹配 → 返回该关键词对应的预设响应</li>
 *   <li>若无匹配 → 回显用户消息（前缀 "[Mock] Echo: "）</li>
 * </ol>
 *
 * <p><b>设计决策：</b>
 * <ul>
 *   <li>使用 {@link ConcurrentHashMap} 存储关键词-响应映射，支持运行时动态修改。</li>
 *   <li>{@link #setResponse(String, String)} 方法允许测试代码实时注入预期响应。</li>
 *   <li>所有方法均为同步返回（无网络 I/O），确保测试执行速度。</li>
 * </ul>
 *
 * @see ModelProvider
 * @see OpenAICompatibleProvider
 */
public class MockModelProvider implements ModelProvider {

    /**
     * 关键词 → 响应 的映射表。
     * 线程安全，支持运行时动态添加预设响应。
     */
    private final Map<String, String> responses = new ConcurrentHashMap<>();

    /**
     * 注册一个关键词-响应对。
     *
     * <p>当后续的 {@link #chat} 调用中用户消息包含指定关键词时，
     * 将返回对应的预设响应。可用于测试特定对话场景。
     *
     * @param keyword  触发关键词（大小写敏感，包含匹配）
     * @param response 对应的预设响应文本
     */
    public void setResponse(String keyword, String response) {
        responses.put(keyword, response);
    }

    /**
     * 返回 Mock 提供商的标识符。
     *
     * @return 固定返回 "mock"
     */
    @Override
    public String providerId() {
        return "mock";
    }

    /**
     * 检查是否支持指定的模型规格。
     *
     * @param model 模型规格
     * @return 当 model.providerId() 为 "mock" 时返回 {@code true}
     */
    @Override
    public boolean supports(ModelSpec model) {
        return "mock".equals(model.providerId());
    }

    /**
     * 执行模拟对话 —— 按关键词匹配返回预设响应或回显用户输入。
     *
     * <p>遍历所有已注册的关键词，若用户消息包含某关键词则返回对应预设响应；
     * 若无匹配则回显用户消息。
     *
     * @param model        模型规格（Mock 模式下可忽略）
     * @param systemPrompt 系统提示词（Mock 模式下不使用）
     * @param userMessage  用户消息，用于关键词匹配
     * @return 匹配的预设响应，或 "[Mock] Echo: " + 用户消息
     */
    @Override
    public String chat(ProviderRequest request) {
        String userMessage = request.userMessage();
        if (request.hasTools()) {
            return "[Mock Tools] Would call tools for: " + userMessage;
        }
        for (var entry : responses.entrySet()) {
            if (userMessage.contains(entry.getKey())) {
                return entry.getValue();
            }
        }
        return "[Mock] Echo: " + userMessage;
    }

    /**
     * 模拟流式对话 —— 返回包含用户消息的单元素流。
     *
     * @param model        模型规格（Mock 模式下可忽略）
     * @param systemPrompt 系统提示词（Mock 模式下不使用）
     * @param userMessage  用户消息
     * @return 包含 "[Mock Stream] " + 用户消息 的单元素 Flux
     */
    @Override
    public Flux<String> stream(ProviderRequest request) {
        if (request.hasTools()) {
            return Flux.just("[Mock Tools Stream] Would call tools for: " + request.userMessage());
        }
        return Flux.just("[Mock Stream] " + request.userMessage());
    }
}
