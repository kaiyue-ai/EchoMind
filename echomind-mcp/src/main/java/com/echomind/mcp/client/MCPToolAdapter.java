package com.echomind.mcp.client;

import com.echomind.mcp.server.ToolProvider;
import com.echomind.mcp.server.ToolResult;
import com.echomind.mcp.server.ToolSpec;

import java.util.List;
import java.util.Map;

/**
 * MCP 工具适配器 —— 将远程 MCP 客户端适配为本地 ToolProvider 接口。
 *
 * <p>此适配器是 MCP 生态与 EchoMind Skill 生态之间的桥梁：
 * <ul>
 *   <li>实现 {@link ToolProvider} 接口，使远程 MCP 工具能够被
 *       EchoMind 的 Agent 流水线作为本地工具统一调用。</li>
 *   <li>内部持有 {@link MCPClient} 实例，将 {@link ToolProvider} 的方法调用
 *       转发为 HTTP JSON-RPC 请求到远程 MCP 服务器。</li>
 * </ul>
 *
 * <h3>缓存策略</h3>
 * {@link #getTools()} 首次调用时从远程 MCP 服务器获取工具列表并缓存。
 * 后续调用直接返回缓存结果，避免每次工具发现都发起网络请求。
 * 调用 {@link #refresh()} 可清除缓存，强制下一次 {@code getTools()} 重新获取。
 *
 * <h3>使用场景</h3>
 * <pre>{@code
 * MCPClient client = new MCPClient("http://remote:8080", "my-agent");
 * client.initialize();
 * MCPToolAdapter adapter = new MCPToolAdapter(client);
 * // 将 adapter 注册到 MCPServer 或 SkillRegistry
 * server.registerToolProvider(adapter);
 * }</pre>
 *
 * <p>设计决策：
 * <ul>
 *   <li>适配器模式 —— 将外部 MCP 接口适配为平台内部 SPI，
 *       避免平台代码直接依赖 MCPClient 的具体实现。</li>
 *   <li>懒加载工具列表 —— 首次调用 {@code getTools()} 时才发起远程请求，
 *       而非构造时立即请求，减少不必要的网络开销。</li>
 *   <li>手动刷新 —— 不自动刷新缓存，由调用方根据需要调用 {@link #refresh()}，
 *       避免定时任务增加复杂度。</li>
 * </ul>
 *
 * @author EchoMind Team
 * @see MCPClient
 * @see ToolProvider
 * @since 1.0
 */
public class MCPToolAdapter implements ToolProvider {

    /** 远程 MCP 客户端 —— 所有实际操作都委托给此客户端 */
    private final MCPClient client;

    /**
     * 缓存的工具列表 —— 首次调用 getTools() 时从远程服务器获取并缓存。
     * 为 null 表示缓存未初始化或已被清除，需要重新获取。
     */
    private List<ToolSpec> cachedTools;

    /**
     * 构造 MCP 工具适配器。
     *
     * @param client 已构造的 MCP 客户端实例（建议在构造适配器前先调用
     *               {@link MCPClient#initialize()} 完成握手）
     */
    public MCPToolAdapter(MCPClient client) {
        this.client = client;
    }

    /**
     * 获取工具列表 —— 首次调用从远程获取并缓存，后续调用返回缓存结果。
     *
     * <p>线程安全说明：当前实现仅适用于单线程或低并发场景。
     * 在高并发场景下，可能出现多个线程同时检测到 cache 为 null
     * 并发起重复请求的情况，但不会产生数据不一致问题。
     *
     * @return 远程 MCP 服务器注册的工具规格列表
     */
    @Override
    public List<ToolSpec> getTools() {
        if (cachedTools == null) {
            cachedTools = client.listTools();
        }
        return cachedTools;
    }

    /**
     * 调用远程工具 —— 直接转发给 MCPClient，无缓存。
     *
     * @param toolName  要调用的工具名称
     * @param arguments 工具调用参数
     * @return 远程工具执行结果
     */
    @Override
    public ToolResult call(String toolName, Map<String, Object> arguments) {
        return client.callTool(toolName, arguments);
    }

    /**
     * 清除工具列表缓存，强制下次 {@link #getTools()} 重新从远程获取。
     *
     * <p>使用场景：
     * <ul>
     *   <li>远程服务器重启或更新了工具列表后。</li>
     *   <li>检测到工具调用失败（可能工具已被删除）。</li>
     *   <li>定期刷新以保持工具列表同步。</li>
     * </ul>
     */
    public void refresh() {
        cachedTools = null;
    }
}
