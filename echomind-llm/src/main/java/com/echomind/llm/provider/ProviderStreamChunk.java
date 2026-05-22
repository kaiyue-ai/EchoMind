package com.echomind.llm.provider;

import com.echomind.common.model.TokenUsage;
import com.echomind.llm.provider.dto.ProviderResponse;

/** 流式 Provider 的文本片段、最终 usage 片段或结构化失败状态。 */
// 简单来说,这个类就是用来包装流式输出的每一个分片的,content就是文本内容,usage是token用量,failed是否失败,failureReason是失败的原因,modelInvoked是是否调用了模型
public record ProviderStreamChunk(String content, TokenUsage usage, boolean failed, String failureReason,
                                  boolean modelInvoked) {

    // 构造函数
    public ProviderStreamChunk {
        content = content == null ? "" : content;
        failureReason = failureReason == null ? null : failureReason.trim();
    }

    // 重载构造函数
    public ProviderStreamChunk(String content, TokenUsage usage, boolean failed, String failureReason) {
        this(content, usage, failed, failureReason, true);
    }

    // 重载构造函数
    public ProviderStreamChunk(String content, TokenUsage usage) {
        this(content, usage, false, null, true);
    }

    // 获取文本对象
    public static ProviderStreamChunk text(String content) {
        return new ProviderStreamChunk(content, null);
    }

    // 获取token用量
    public static ProviderStreamChunk usage(TokenUsage usage) {
        return new ProviderStreamChunk("", usage);
    }

    // 获取失败对象
    public static ProviderStreamChunk failure(String reason) {
        String normalized = ProviderResponse.normalizeReason(reason);
        return new ProviderStreamChunk("[Error] " + normalized, null, true, normalized);
    }

    // 判断是否有内容
    public boolean hasContent() {
        return !content.isEmpty();
    }

    // 判断是否有token用量
    public boolean hasUsage() {
        return usage != null && usage.hasTokens();
    }
}
