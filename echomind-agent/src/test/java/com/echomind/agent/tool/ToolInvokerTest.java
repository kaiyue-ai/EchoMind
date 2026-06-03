package com.echomind.agent.tool;

import com.echomind.agent.pipeline.PipelineContext;
import com.echomind.agent.tool.core.Tool;
import com.echomind.agent.tool.core.ToolResult;
import com.echomind.agent.tool.invoker.ToolInvoker;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

class ToolInvokerTest {

    @Test
    void emitsToolProgressAroundSuccessfulToolCall() {
        PipelineContext context = new PipelineContext();
        List<PipelineContext.ToolProgressEvent> events = new ArrayList<>();
        context.setToolProgressSink(events::add);

        String output = new ToolInvoker(new TestTool(false), context).call("{\"value\":\"ok\"}");

        assertThat(output).isEqualTo("done");
        assertThat(events).extracting(PipelineContext.ToolProgressEvent::type)
            .containsExactly(
                PipelineContext.ToolProgressEvent.TYPE_START,
                PipelineContext.ToolProgressEvent.TYPE_END
            );
        assertThat(events).extracting(PipelineContext.ToolProgressEvent::toolName)
            .containsExactly("test-tool", "test-tool");
    }

    @Test
    void emitsToolEndWhenToolThrows() {
        PipelineContext context = new PipelineContext();
        List<PipelineContext.ToolProgressEvent> events = new ArrayList<>();
        context.setToolProgressSink(events::add);

        String output = new ToolInvoker(new TestTool(true), context).call("{}");

        assertThat(output).contains("Error executing test-tool");
        assertThat(events).extracting(PipelineContext.ToolProgressEvent::type)
            .containsExactly(
                PipelineContext.ToolProgressEvent.TYPE_START,
                PipelineContext.ToolProgressEvent.TYPE_END
            );
    }

    private record TestTool(boolean fail) implements Tool {

        @Override
        public String name() {
            return "test-tool";
        }

        @Override
        public String description() {
            return "Test tool";
        }

        @Override
        public Map<String, Object> parameterSchema() {
            return Map.of("type", "object");
        }

        @Override
        public List<String> tags() {
            return List.of();
        }

        @Override
        public String sourceType() {
            return SOURCE_SKILL;
        }

        @Override
        public String sourceId() {
            return "test-tool@1.0.0";
        }

        @Override
        public CompletableFuture<ToolResult> execute(Map<String, Object> parameters) {
            if (fail) {
                throw new IllegalStateException("boom");
            }
            return CompletableFuture.completedFuture(ToolResult.success("done", 7));
        }
    }
}
