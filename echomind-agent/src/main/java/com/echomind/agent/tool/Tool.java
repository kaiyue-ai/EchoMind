package com.echomind.agent.tool;

import com.echomind.agent.pipeline.PipelineContext;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 统一工具抽象。
 *
 * <p>Agent 管线不直接区分本地 Skill 和 MCP 工具，二者都会被包装成这个接口。
 * 这样模型函数调用不需要关心背后到底是本地 Skill 还是 MCP 工具。</p>
 */
public interface Tool {

    /** 工具唯一名称，例如 weather-query、calculator、web-search。 */
    String name();

    /** 人类可读的描述，用于关键词匹配和模型函数说明。 */
    String description();

    /** 输入参数的 JSON Schema。 */
    Map<String, Object> parameterSchema();

    /** 用于关键词匹配的标签，例如 weather、temperature。 */
    List<String> tags();

    /**
     * 工具作者显式声明的触发关键词。
     *
     * <p>新 Skill JAR 可以通过 {@code SkillMetadata.keywords()} 提供这些词。
     * 默认返回空列表，保证旧工具和旧适配器不需要立即改造。</p>
     */
    default List<String> keywords() {
        return List.of();
    }

    /**
     * 工具作者显式声明的关键词别名表。
     *
     * <p>key 是规范能力词，value 是用户可能说出的别名。默认空表。</p>
     */
    default Map<String, List<String>> aliases() {
        return Map.of();
    }

    /** 来源类型，用于追踪调用来源：skill 或 mcp。 */
    String sourceType();

    /** 来源标识，例如 Skill 版本号，或 MCP 服务加工具名。 */
    String sourceId();

    /** 使用给定参数异步执行工具。 */
    CompletableFuture<ToolResult> execute(Map<String, Object> parameters);

    /** 使用给定参数和当前管线上下文异步执行工具。 */
    default CompletableFuture<ToolResult> execute(Map<String, Object> parameters, PipelineContext context) {
        return execute(parameters);
    }
}
