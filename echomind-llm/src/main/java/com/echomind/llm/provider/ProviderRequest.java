package com.echomind.llm.provider;

import com.echomind.common.model.MessageAttachment;
import com.echomind.llm.router.ModelSpec;

import java.util.List;

/**
 * Provider 的统一输入。
 *
 * <p>上层只负责把模型、提示词、附件和可用工具组装好；具体请求体如何编码、
 * 如何流式解析、如何做工具二轮调用，都由各个 provider 自己决定。</p>
 */
public record ProviderRequest(
    ModelSpec model,
    String systemPrompt,
    String userMessage,
    List<MessageAttachment> attachments,
    List<LlmTool> tools
) {

    public ProviderRequest {
        attachments = attachments == null ? List.of() : List.copyOf(attachments);
        tools = tools == null ? List.of() : List.copyOf(tools);
    }

    public boolean hasAttachments() {
        return !attachments.isEmpty();
    }

    public boolean hasTools() {
        return !tools.isEmpty();
    }
}
