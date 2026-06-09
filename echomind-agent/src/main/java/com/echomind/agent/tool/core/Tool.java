package com.echomind.agent.tool.core;

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

    String SOURCE_SKILL = "skill";
    String SOURCE_MCP = "mcp";

    /** 工具唯一名称，例如 weather-query、calculator、open_web_search。 */
    String name();

    /** 人类可读的描述，用于模型函数说明。 */
    String description();

    /** 输入参数的 JSON Schema。 */
    Map<String, Object> parameterSchema();

    /** 工具标签，例如 weather、temperature；用于展示、治理和后续能力分析。 */
    List<String> tags();

    /**
     * 工具作者显式声明的能力关键词。
     *
     * <p>普通聊天不再用关键词预筛选工具；这些词仍可用于 Skill 市场、管理界面和
     * 后续能力分析。默认返回空列表，保证旧工具和旧适配器不需要立即改造。</p>
     */
    default List<String> keywords() {
        return List.of();
    }

    /**
     * 工具作者显式声明的能力别名表。
     *
     * <p>key 是规范能力词，value 是用户可能说出的别名。默认空表。</p>
     */
    default Map<String, List<String>> aliases() {
        return Map.of();
    }

    /** 来源类型，用于追踪调用来源：{@link #SOURCE_SKILL} 或 {@link #SOURCE_MCP}。 */
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
