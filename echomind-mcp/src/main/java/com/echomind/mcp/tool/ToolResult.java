package com.echomind.mcp.tool;

import java.util.List;

/**
 * 工具执行结果记录 —— 封装 MCP 工具调用后的返回数据。
 *
 * <p>根据 MCP 规范，工具调用的响应由一组内容项（ContentItem）和一个错误标志组成。
 * 内容项可以是文本、图片、文件等多种类型，当前实现以文本为主。
 *
 * <h3>结果结构</h3>
 * <ul>
 *   <li><b>content</b> —— 内容项列表，每个 {@link ContentItem} 包含 type 和 text 两个字段。</li>
 *   <li><b>isError</b> —— 错误标志。为 true 表示工具执行过程中发生错误（尽管 HTTP 层面可能返回 200）。</li>
 * </ul>
 *
 * <h3>工厂方法</h3>
 * <ul>
 *   <li>{@link #success(String)} —— 创建成功结果，isError 为 false。</li>
 *   <li>{@link #error(String)} —— 创建错误结果，isError 为 true。</li>
 * </ul>
 *
 * <p>设计决策：
 * <ul>
 *   <li>使用 Java Record —— 不可变，避免结果在传递过程中被意外修改。</li>
 *   <li>ContentItem 作为嵌套 Record —— 轻量级内容封装，便于 JSON 序列化。</li>
 *   <li>isError 标志而非 HTTP 状态码 —— MCP 协议层面区分业务错误和传输错误，
 *       业务错误用 isError=true，传输错误用 JSON-RPC error。</li>
 * </ul>
 *
 * @author EchoMind Team
 * @see ToolSpec
 * @see ToolProvider
 * @since 1.0
 */
public record ToolResult(
    /**
     * 内容项列表 —— 工具返回的数据内容。
     * 每个元素是一个 ContentItem（包含类型和文本）。
     */
    List<ContentItem> content,

    /**
     * 错误标志 —— 指示此结果是否为错误。
     * true 表示工具执行失败；false 表示执行成功。
     */
    boolean isError
) {
    /**
     * 内容项 —— 工具结果中的单个内容单元。
     *
     * @param type 内容类型，通常为 "text"（也可为 "image"、"resource" 等扩展类型）
     * @param text 文本内容（或资源的数据字符串表示）
     */
    public record ContentItem(
        String type,
        String text
    ) {}

    /**
     * 创建成功的工具执行结果。
     *
     * @param text 工具返回的成功文本
     * @return isError=false 的 ToolResult 实例
     */
    public static ToolResult success(String text) {
        return new ToolResult(List.of(new ContentItem("text", text)), false);
    }

    /**
     * 创建失败的工具执行结果。
     *
     * @param text 错误描述文本
     * @return isError=true 的 ToolResult 实例
     */
    public static ToolResult error(String text) {
        return new ToolResult(List.of(new ContentItem("text", text)), true);
    }
}
