package com.echomind.agent.pipeline.stages;

import com.echomind.agent.pipeline.PipelineContext;
import com.echomind.common.model.AgentMessage;
import com.echomind.memory.MemoryManager;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class MemoryPersistStageTest {

    @Test
    void skipsPersistenceForInternalPipelineContext() {
        MemoryManager memoryManager = mock(MemoryManager.class);
        MemoryPersistStage stage = new MemoryPersistStage(memoryManager);
        PipelineContext ctx = new PipelineContext();
        ctx.setSessionId("team-run-1-reviewer");
        ctx.setAgentId("reviewer");
        ctx.setUserMessage("{\"action\":\"CONTINUE\"}");
        ctx.setFinalResponse("final report");
        ctx.setMemoryPersistenceEnabled(false);

        stage.process(ctx);

        verify(memoryManager, never()).addMessage(any(), any(), any(AgentMessage.class));
    }

    @Test
    void persistsNormalUserChat() {
        MemoryManager memoryManager = mock(MemoryManager.class);
        MemoryPersistStage stage = new MemoryPersistStage(memoryManager);
        PipelineContext ctx = new PipelineContext();
        ctx.setSessionId("session-1");
        ctx.setAgentId("default");
        ctx.setUserMessage("你好");
        ctx.setFinalResponse("你好，有什么可以帮你？");

        stage.process(ctx);

        verify(memoryManager, times(2)).addMessage(eq("session-1"), eq("default"), any(AgentMessage.class));
    }
}
