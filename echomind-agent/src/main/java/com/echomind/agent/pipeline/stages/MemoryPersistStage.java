package com.echomind.agent.pipeline.stages;

import com.echomind.agent.pipeline.PipelineContext;
import com.echomind.agent.pipeline.PipelineStage;
import com.echomind.agent.memory.ChatMemoryPersistPublisher;
import com.echomind.agent.memory.NoopChatMemoryPersistPublisher;
import com.echomind.common.model.AgentMessage;

import java.util.ArrayList;
import java.util.List;

/** 将本轮用户消息、工具调用记录和最终回复发布到异步记忆写入队列。 */
public class MemoryPersistStage implements PipelineStage {

    private final ChatMemoryPersistPublisher chatMemoryPublisher;

    public MemoryPersistStage(ChatMemoryPersistPublisher chatMemoryPublisher) {
        this.chatMemoryPublisher = chatMemoryPublisher == null
            ? new NoopChatMemoryPersistPublisher()
            : chatMemoryPublisher;
    }

    @Override
    public int order() { return 50; }

    @Override
    public PipelineContext process(PipelineContext ctx) {
        if (!ctx.isMemoryPersistenceEnabled()) {
            return ctx;
        }
        List<AgentMessage> persisted = new ArrayList<>();

        // 按真实对话顺序发布：用户输入 -> 工具结果 -> 模型回复。
        AgentMessage userMessage = AgentMessage.user(ctx.getUserMessage(), ctx.getAttachments());
        persisted.add(userMessage);

        if (!ctx.getSkillResults().isEmpty()) {
            for (String skillResult : ctx.getSkillResults()) {
                String toolCallId = skillResult.startsWith("[") && skillResult.contains("]:")
                    ? skillResult.substring(1, skillResult.indexOf("]:"))
                    : "skill";
                AgentMessage toolMessage = AgentMessage.tool(toolCallId, skillResult);
                persisted.add(toolMessage);
            }
        }

        if (ctx.getFinalResponse() != null) {
            AgentMessage assistantMessage = AgentMessage.assistant(ctx.getFinalResponse());
            persisted.add(assistantMessage);
        }
        chatMemoryPublisher.publish(ctx.getUserId(), ctx.getSessionId(), ctx.getAgentId(),
            List.copyOf(persisted), ctx.getMemorySignal());
        return ctx;
    }
}
