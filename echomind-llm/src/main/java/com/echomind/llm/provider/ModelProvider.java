package com.echomind.llm.provider;

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
    String chat(ProviderRequest request);

    /** 流式返回 token/文本片段。 */
    Flux<String> stream(ProviderRequest request);
}
