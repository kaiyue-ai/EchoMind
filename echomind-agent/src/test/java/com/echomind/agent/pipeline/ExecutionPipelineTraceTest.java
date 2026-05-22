package com.echomind.agent.pipeline;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ExecutionPipelineTraceTest {

    @Test
    void pipelineAssignsTraceIdWhenStageRuns() {
        ExecutionPipeline pipeline = new ExecutionPipeline(List.of(new PipelineStage() {
            @Override
            public int order() {
                return 10;
            }

            @Override
            public PipelineContext process(PipelineContext ctx) {
                ctx.setFinalResponse("ok");
                return ctx;
            }
        }));
        PipelineContext ctx = new PipelineContext();
        ctx.setUserId("user-a");
        ctx.setAgentId("default");
        ctx.setSessionId("session-a");

        PipelineContext result = pipeline.execute(ctx);

        assertThat(result.getTraceId()).isNotBlank();
        assertThat(result.getFinalResponse()).isEqualTo("ok");
    }
}
