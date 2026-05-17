package com.echomind.agent.pipeline.stages;

import com.echomind.agent.pipeline.PipelineContext;
import com.echomind.agent.pipeline.PipelineStage;
import com.echomind.common.model.AgentMessage;
import com.echomind.memory.MemoryManager;
import lombok.RequiredArgsConstructor;

/** 加载当前对话的模型上下文，并把本轮用户消息追加到上下文末尾。 */
@RequiredArgsConstructor
public class ContextEnrichStage implements PipelineStage {

    private final MemoryManager memoryManager;

    @Override
    public int order() { return 10; }

    @Override
    public PipelineContext process(PipelineContext ctx) {
        if (!ctx.isMemoryPersistenceEnabled()) {
            ctx.getMessages().add(AgentMessage.user(ctx.getUserMessage(), ctx.getAttachments()));
            return ctx;
        }
        var history = memoryManager.getPromptContext(ctx.getMemoryKey());
        ctx.getMessages().addAll(history);
        ctx.getMessages().add(AgentMessage.user(ctx.getUserMessage(), ctx.getAttachments()));
        return ctx;
    }
}
