package com.echomind.agent.tool;

import com.echomind.agent.pipeline.PipelineContext;
import com.fasterxml.jackson.databind.ObjectMapper;
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
    private final PipelineContext context;

    public String call(String argumentsJson) {
        try {
            log.info("Model selected tool {} with input {}", tool.name(), argumentsJson);
            @SuppressWarnings("unchecked")
            Map<String, Object> params = MAPPER.readValue(
                argumentsJson == null || argumentsJson.isBlank() ? "{}" : argumentsJson,
                Map.class
            );
            ToolResult result = tool.execute(params, context).join();
            if (context != null) {
                context.getSkillResults().add(tool.name());
            }
            return result.success() ? result.output() : "Error: " + result.error();
        } catch (Exception e) {
            log.error("Tool invocation failed for {}: {}", tool.name(), e.getMessage());
            return "Error executing " + tool.name() + ": " + e.getMessage();
        }
    }
}
