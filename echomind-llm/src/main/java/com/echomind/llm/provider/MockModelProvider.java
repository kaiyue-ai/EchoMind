package com.echomind.llm.provider;

import com.echomind.common.model.TokenUsage;
import com.echomind.llm.provider.dto.ProviderRequest;
import com.echomind.llm.provider.dto.ProviderResponse;
import com.echomind.llm.router.ModelSpec;
import reactor.core.publisher.Flux;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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
    public ProviderResponse chatWithUsage(ProviderRequest request) {
        String userMessage = request.userMessage();
        if (request.hasTools()) {
            String response = "[Mock Tools] Would call tools for: " + userMessage;
            return new ProviderResponse(response, mockUsage(request, response));
        }
        for (var entry : responses.entrySet()) {
            if (userMessage.contains(entry.getKey())) {
                return new ProviderResponse(entry.getValue(), mockUsage(request, entry.getValue()));
            }
        }
        String response = "[Mock] Echo: " + userMessage;
        return new ProviderResponse(response, mockUsage(request, response));
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
    public Flux<ProviderStreamChunk> streamWithUsage(ProviderRequest request) {
        if (request.hasTools()) {
            String response = "[Mock Tools Stream] Would call tools for: " + request.userMessage();
            return Flux.just(ProviderStreamChunk.text(response), ProviderStreamChunk.usage(mockUsage(request, response)));
        }
        String response = "[Mock Stream] " + request.userMessage();
        return Flux.just(ProviderStreamChunk.text(response), ProviderStreamChunk.usage(mockUsage(request, response)));
    }

    private TokenUsage mockUsage(ProviderRequest request, String response) {
        long promptTokens = roughTokens(request.systemPrompt()) + roughTokens(request.userMessage());
        long completionTokens = roughTokens(response);
        return new TokenUsage(promptTokens, completionTokens, promptTokens + completionTokens);
    }

    private long roughTokens(String text) {
        if (text == null || text.isBlank()) {
            return 1;
        }
        return Math.max(1, (text.length() + 3L) / 4L);
    }
}
