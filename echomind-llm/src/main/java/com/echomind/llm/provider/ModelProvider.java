package com.echomind.llm.provider;

import com.echomind.llm.provider.dto.ProviderRequest;
import com.echomind.llm.provider.dto.ProviderResponse;
import com.echomind.llm.router.ModelSpec;
import reactor.core.publisher.Flux;

/**
 * LLM Provider 的统一契约。
 *
 * <p>上层只关心 providerId、模型支持判断，以及统一的同步/流式调用入口。
 * 多模态、工具调用和协议细节都下沉到 provider 内部处理。</p>
 */
public interface ModelProvider {

    /** 提供商唯一标识，如 deepseek、openai、mock。 */
    String providerId();

    /** 检查模型规格是否属于当前提供商。 */
    boolean supports(ModelSpec model);

    /** 同步返回完整回复。 */
    default String chat(ProviderRequest request) {
        return chatWithUsage(request).content();
    }

    /** 同步返回完整回复和模型原生 token 用量。 */
    ProviderResponse chatWithUsage(ProviderRequest request);

    /** 流式返回 token/文本片段。 */
    default Flux<String> stream(ProviderRequest request) {
        return streamWithUsage(request)
            .filter(ProviderStreamChunk::hasContent)
            .map(ProviderStreamChunk::content);
    }

    /** 流式返回 token/文本片段，并在模型服务返回时附带原生 token 用量。 */
    Flux<ProviderStreamChunk> streamWithUsage(ProviderRequest request);
}
