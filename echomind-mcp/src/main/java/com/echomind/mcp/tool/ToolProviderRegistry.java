package com.echomind.mcp.tool;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * MCP工具提供者注册表。
 *
 * <p>外部MCP工具不应该散落在各调用方手里。
 * 此接口把“工具存在哪里、如何注册和调用”抽出来，便于由统一能力注册中心托管。</p>
 */
public interface ToolProviderRegistry {

    /**
     * 注册工具提供者。
     *
     * @param provider 要注册的工具提供者
     */
    void registerToolProvider(ToolProvider provider);

    /**
     * 注销工具提供者。
     *
     * @param provider 要注销的工具提供者
     */
    void unregisterToolProvider(ToolProvider provider);

    /**
     * 按工具名注销工具。
     *
     * @param toolName 工具名称
     */
    void unregisterTool(String toolName);

    /**
     * 列出当前已接入的MCP工具。
     *
     * @return 工具规格列表
     */
    List<ToolSpec> listTools();

    /**
     * 调用指定MCP工具。
     *
     * @param toolName  工具名称
     * @param arguments 工具参数
     * @return 调用结果
     */
    ToolResult callTool(String toolName, Map<String, Object> arguments);

    /**
     * 查找提供指定工具的提供者。
     *
     * @param toolName 工具名称
     * @return 工具提供者
     */
    Optional<ToolProvider> getToolProvider(String toolName);
}
