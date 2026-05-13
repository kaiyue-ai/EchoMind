package com.echomind.agent.tool;

import com.echomind.agent.pipeline.PipelineContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.model.function.FunctionCallback;

import java.util.HashMap;
import java.util.Map;

/**
 * 将统一工具 {@link Tool} 包装成 Spring AI 的 {@link FunctionCallback}。
 *
 * <p>这样模型做函数调用时，不需要知道背后到底是本地 Skill 还是 MCP 工具。</p>
 */
@Slf4j
@Getter
@RequiredArgsConstructor
public class ToolFunctionCallback implements FunctionCallback {

    private static final ObjectMapper mapper = new ObjectMapper();

    private final Tool tool;
    private final PipelineContext context;

    @Override
    public String getName() {
        // 函数名不能包含点号和短横线，统一替换为下划线。
        return tool.name().replaceAll("[.\\-]", "_");
    }

    @Override
    public String getDescription() {
        return tool.description();
    }

    @Override
    @SuppressWarnings("unchecked")
    public String getInputTypeSchema() {
        try {
            Map<String, Object> schema = tool.parameterSchema();
            if (schema == null) {
                schema = Map.of(
                    "type", "object",
                    "properties", Map.of("query",
                        Map.of("type", "string", "description", "The query or input for this tool"))
                );
            } else if (!"object".equals(schema.get("type"))) {
                // Anthropic/OpenAI 的函数参数要求根节点是 object，这里做一层兜底。
                Map<String, Object> wrapped = new HashMap<>(schema);
                wrapped.putIfAbsent("type", "object");
                schema = wrapped;
            }
            String json = mapper.writeValueAsString(schema);
            log.info("Tool function schema for {}: {}", tool.name(), json);
            return json;
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize parameter schema for {}", tool.name(), e);
            return "{\"type\":\"object\",\"properties\":{}}";
        }
    }

    @Override
    public String call(String functionInput) {
        try {
            log.info("Model selected tool {} with input {}", tool.name(), functionInput);
            @SuppressWarnings("unchecked")
            Map<String, Object> params = mapper.readValue(functionInput, Map.class);
            ToolResult result = tool.execute(params, context).join();
            if (context != null) {
                context.getSkillResults().add(tool.name());
            }
            if (result.success()) {
                return result.output();
            }
            return "Error: " + result.error();
        } catch (Exception e) {
            log.error("Function callback failed for {}: {}", tool.name(), e.getMessage());
            return "Error executing " + tool.name() + ": " + e.getMessage();
        }
    }
}
