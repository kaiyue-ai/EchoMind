package com.echomind.mcp;

import java.util.List;

/**
 * 工具提供者接口 —— 外部 MCP 工具在平台内部的统一 SPI。
 *
 * <p>任何希望把远端 MCP 工具接入 Agent 工具体系的适配器都实现此接口。
 * 每个 ToolProvider 管理一个或多个相关的远端工具，例如：
 * <ul>
 *   <li>WeatherToolProvider —— 提供天气查询相关工具</li>
 *   <li>SearchToolProvider —— 提供网络搜索相关工具</li>
 *   <li>DatabaseToolProvider —— 提供数据库查询相关工具</li>
 * </ul>
 *
 * <h3>实现要点</h3>
 * <ul>
 *   <li>{@link #getTools()} —— 返回此提供者管理的所有工具的规格信息，
 *       用于平台展示和模型函数定义。</li>
 *   <li>{@link #call(String, Map)} —— 根据工具名称和参数执行工具调用，
 *       返回执行结果。实现应处理工具不存在（返回 error 结果）和参数非法的情况。</li>
 * </ul>
 *
 * <p>设计决策：
 * <ul>
 *   <li>接口式设计 —— 将工具定义与 MCP 传输层解耦，便于单元测试和运行时挂载。</li>
 *   <li>批量注册 —— 一个 ToolProvider 可提供多个工具，
 *       减少注册代码量和 Provider 实例数量。</li>
 *   <li>与 Skill 接口互补 —— Skill 和外部 MCP 工具最终都会进入 Agent 的统一工具视图。</li>
 * </ul>
 *
 * @author EchoMind Team
 * @see ToolSpec
 * @see ToolResult
 * @since 1.0
 */
public interface ToolProvider {

    /**
     * 获取此提供者管理的全部工具列表。
     *
     * <p>返回的列表将被注册到能力注册中心的外部MCP工具视图中。
     * 实现应保证每次调用返回的工具列表一致（除非有动态注册/注销的需求）。
     *
     * @return 工具规格列表（不可为 null，可为空列表）
     */
    List<ToolSpec> getTools(); // 获取工具

    /**
     * 调用指定工具并返回执行结果。
     *
     * <p>实现应处理以下情况：
     * <ul>
     *   <li>工具不存在 —— 返回 {@link ToolResult#error(String)} 含错误描述。</li>
     *   <li>参数非法 —— 返回错误结果含参数验证错误信息。</li>
     *   <li>执行成功 —— 返回 {@link ToolResult#success(String)}。
     *       如果工具产生复杂输出，可使用 JSON 字符串或结构化文本。</li>
     * </ul>
     *
     * @param toolName  工具名称（与 {@link ToolSpec#name()} 对应）
     * @param arguments 工具调用参数，Key 为参数名，Value 为参数值
     * @return 工具执行结果（成功或错误）
     */
    ToolResult call(String toolName, java.util.Map<String, Object> arguments); // 调用工具
}
