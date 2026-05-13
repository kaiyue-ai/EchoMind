package com.echomind.agent.pipeline.stages;

import com.echomind.agent.pipeline.PipelineContext;
import com.echomind.agent.pipeline.PipelineStage;
import com.echomind.common.model.AgentMessage;
import com.echomind.memory.MemoryManager;
import lombok.RequiredArgsConstructor;

/** 将本轮用户消息、工具调用记录和最终回复写入当前对话的记忆。 */
@RequiredArgsConstructor
public class MemoryPersistStage implements PipelineStage {

    private final MemoryManager memoryManager;

    @Override
    public int order() { return 50; }

    @Override
    public PipelineContext process(PipelineContext ctx) {
        if (!ctx.isMemoryPersistenceEnabled()) {
            return ctx;
        }
        String memoryKey = ctx.getMemoryKey();

        // 按真实对话顺序写入记忆：用户输入 -> 工具结果 -> 模型回复。
        memoryManager.addMessage(memoryKey, ctx.getAgentId(),
            AgentMessage.user(ctx.getUserMessage(), ctx.getAttachments()));

        if (!ctx.getSkillResults().isEmpty()) {
            for (String skillResult : ctx.getSkillResults()) {
                String toolCallId = skillResult.startsWith("[") && skillResult.contains("]:")
                    ? skillResult.substring(1, skillResult.indexOf("]:"))
                    : "skill";
                memoryManager.addMessage(memoryKey, ctx.getAgentId(),
                    AgentMessage.tool(toolCallId, skillResult));
            }
        }

        if (ctx.getFinalResponse() != null) {
            memoryManager.addMessage(memoryKey, ctx.getAgentId(),
                AgentMessage.assistant(ctx.getFinalResponse()));
        }
        return ctx;
    }
}
