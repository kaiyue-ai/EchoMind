package com.echomind.agent.tool.mcp;

import com.echomind.mcp.ToolSpec;

import java.time.Instant;
import java.util.List;

/**
 * 外部 MCP Server 的运行态快照。
 *
 * <p>该对象只反映当前 JVM 内的挂载状态：应用重启后会重新按配置文件挂载默认服务，
 * 页面上临时挂载的服务不会自动持久化。</p>
 *
 * @param id               服务唯一标识
 * @param transport        传输方式
 * @param command          启动命令
 * @param workingDirectory 子进程工作目录
 * @param url              远程 MCP 服务 base URL
 * @param endpoint         远程 MCP endpoint
 * @param running          MCP client 是否已初始化且未关闭
 * @param toolCount        当前缓存的工具数量
 * @param tools            当前缓存的工具规格
 * @param mountedAt        挂载时间
 * @param error            最近一次刷新或调用时记录的错误信息
 */
public record ExternalMcpServerStatus(
    String id,
    String transport,
    List<String> command,
    String workingDirectory,
    String url,
    String endpoint,
    boolean running,
    int toolCount,
    List<ToolSpec> tools,
    Instant mountedAt,
    String error
) {
    public ExternalMcpServerStatus(String id, String transport, List<String> command, String workingDirectory,
                                   boolean running, int toolCount, List<ToolSpec> tools,
                                   Instant mountedAt, String error) {
        this(id, transport, command, workingDirectory, null, null, running, toolCount, tools, mountedAt, error);
    }
}
