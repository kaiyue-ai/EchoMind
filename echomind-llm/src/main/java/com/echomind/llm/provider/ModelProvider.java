package com.echomind.llm.provider;

import com.echomind.common.model.MessageAttachment;
import com.echomind.llm.router.ModelSpec;
import org.springframework.ai.chat.model.ChatModel;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * LLM Provider 的统一契约。
 *
 * <p>上层只关心 providerId、模型支持判断、同步/流式调用，以及可选的 Spring AI
 * {@link ChatModel} 暴露能力。</p>
 */
public interface ModelProvider {

    /** 提供商唯一标识，如 deepseek、openai、mock。 */
    String providerId();

    /** 检查模型规格是否属于当前提供商。 */
    boolean supports(ModelSpec model);

    /** 同步返回完整回复。 */
    String chat(ModelSpec model, String systemPrompt, String userMessage);

    /** 同步多模态回复；默认退化为纯文本调用，真正支持图片的 Provider 会覆盖它。 */
    default String chat(ModelSpec model, String systemPrompt, String userMessage,
                        List<MessageAttachment> attachments) {
        return chat(model, systemPrompt, userMessage);
    }

    /** 流式返回 token/文本片段。 */
    Flux<String> stream(ModelSpec model, String systemPrompt, String userMessage);

    /** 多模态流式返回；默认把完整回复包装成单段流。 */
    default Flux<String> stream(ModelSpec model, String systemPrompt, String userMessage,
                                List<MessageAttachment> attachments) {
        return Flux.just(chat(model, systemPrompt, userMessage, attachments));
    }

    /** 兼容旧工具调用接口；新链路优先使用 Spring AI function callbacks。 */
    String chatWithTools(ModelSpec model, String systemPrompt, String userMessage, String toolsJson);

    /** 使用中立工具描述调用模型；默认忽略工具，兼容不支持函数调用的 Provider。 */
    default String chatWithTools(ModelSpec model, String systemPrompt, String userMessage,
                                 List<LlmTool> tools) {
        return chat(model, systemPrompt, userMessage);
    }

    /** 流式工具调用；默认把完整回复包装成单段流。 */
    default Flux<String> streamWithTools(ModelSpec model, String systemPrompt, String userMessage,
                                         List<LlmTool> tools) {
        return Flux.just(chatWithTools(model, systemPrompt, userMessage, tools));
    }

    /** 暴露底层 Spring AI ChatModel；不支持时返回 null。 */
    default ChatModel getChatModel() { return null; }
}
