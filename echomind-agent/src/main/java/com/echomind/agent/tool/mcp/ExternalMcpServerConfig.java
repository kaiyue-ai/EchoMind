package com.echomind.agent.tool.mcp;

import java.util.List;
import java.util.Map;

/**
 * 外部 MCP Server 挂载配置。
 *
 * <p>这里描述的是“主项目去连接别人的 MCP Server”，不是把 EchoMind 自己暴露成 MCP Server。
 * 当前运行时支持 stdio、sse 和 streamable-http。stdio 会启动本地子进程；
 * 远程传输通过 Spring AI MCP / Java MCP SDK 连接远端 MCP 服务。</p>
 *
 * @param id               服务唯一标识，用于前端管理、日志追踪和工具来源标记
 * @param transport        传输方式：stdio、sse、streamable-http
 * @param command          启动命令，例如 {@code ["java", "-jar", "/app/mcp/demo.jar"]}
 * @param workingDirectory 子进程工作目录，可为空
 * @param environment      stdio 子进程环境变量
 * @param url              远程 MCP 服务 base URL
 * @param endpoint         远程 MCP endpoint，例如 /sse 或 /mcp
 * @param headers          远程 MCP 请求头，例如 Authorization
 * @param toolMetadata     EchoMind 侧补充的工具路由 metadata，key 为 MCP tool name
 */
public record ExternalMcpServerConfig(
    String id, // 服务的唯一标识
    String transport, // 协议
    List<String> command, // 启动命令
    String workingDirectory, // 子进程的工作目录
    Map<String, String> environment, // stdio 子进程环境变量
    String url, // 远程 MCP 服务 base URL
    String endpoint, // 远程 MCP endpoint，例如 /sse 或 /mcp
    Map<String, String> headers, // 远程 MCP 请求头，例如 Authorization
    Map<String, ExternalMcpToolMetadata> toolMetadata // EchoMind 侧补充的工具路由 metadata，key 为 MCP tool name
) {
    public ExternalMcpServerConfig(String id, String transport, List<String> command, String workingDirectory) {
        this(id, transport, command, workingDirectory, Map.of(), null, null, Map.of(), Map.of());
    }

    public ExternalMcpServerConfig(String id, String transport, List<String> command, String workingDirectory,
                                   Map<String, String> environment, String url, String endpoint,
                                   Map<String, String> headers) {
        this(id, transport, command, workingDirectory, environment, url, endpoint, headers, Map.of());
    }
}
