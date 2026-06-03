package com.echomind.agent.tool.invoker;

import com.echomind.agent.pipeline.PipelineContext;
import com.echomind.agent.tool.core.Tool;
import com.echomind.agent.tool.core.ToolResult;
import com.echomind.common.observability.EchoMindTrace;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;

/**
 * 统一工具执行器。
 *
 * <p>Provider 原生工具调用最终都会走到这里，把模型给出的 JSON 参数解析成 Map，
 * 再调用平台自己的 Tool 抽象执行。这样 LLM 模块不需要知道 Skill 或 MCP 的细节。</p>
 */
@Slf4j
@RequiredArgsConstructor
public class ToolInvoker {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Tool tool;
    /** 当前管线上下文，用于工具读取用户、Agent、会话和附件信息。 */
    private final PipelineContext context;

    public String call(String argumentsJson) {
        Span span = EchoMindTrace.startSpan("echomind.tool.invoke");
        long toolStartedAt = -1L;
        span.setAttribute("echomind.tool_name", safe(tool.name()));
        span.setAttribute("echomind.tool_source_type", safe(tool.sourceType()));
        span.setAttribute("echomind.tool_source_id", safe(tool.sourceId()));
        if (context != null) {
            span.setAttribute("echomind.user_id", safe(context.getUserId()));
            span.setAttribute("echomind.agent_id", safe(context.getAgentId()));
            span.setAttribute("echomind.session_id", safe(context.getSessionId()));
        }
        try {
            try (Scope ignored = span.makeCurrent()) {
                log.info("Model selected tool {} with input {}", tool.name(), argumentsJson);
                @SuppressWarnings("unchecked")
                Map<String, Object> params = MAPPER.readValue(
                    argumentsJson == null || argumentsJson.isBlank() ? "{}" : argumentsJson,
                    Map.class
                );
                emitToolStart();
                toolStartedAt = System.currentTimeMillis();
                ToolResult result = tool.execute(params, context).join();
                span.setAttribute("echomind.tool_success", result.success());
                span.setAttribute("echomind.tool_duration_ms", result.durationMs());
                if (context != null) {
                    emitToolEnd(result.durationMs());
                    context.getSkillResults().add(tool.name());
                }
                return result.success() ? result.output() : "Error: " + result.error();
            }
        } catch (Exception e) {
            EchoMindTrace.recordException(span, e);
            log.error("Tool invocation failed for {}: {}", tool.name(), e.getMessage());
            if (toolStartedAt >= 0) {
                emitToolEnd(System.currentTimeMillis() - toolStartedAt);
            }
            return "Error executing " + tool.name() + ": " + e.getMessage();
        } finally {
            span.end();
        }
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private void emitToolStart() {
        if (context == null) {
            return;
        }
        try {
            context.emitToolStart(tool.name());
        } catch (Exception e) {
            log.warn("Failed to emit tool start for {}: {}", tool.name(), e.getMessage());
        }
    }

    private void emitToolEnd(long durationMs) {
        if (context == null) {
            return;
        }
        try {
            context.emitToolEnd(tool.name(), durationMs);
        } catch (Exception e) {
            log.warn("Failed to emit tool end for {}: {}", tool.name(), e.getMessage());
        }
    }
}