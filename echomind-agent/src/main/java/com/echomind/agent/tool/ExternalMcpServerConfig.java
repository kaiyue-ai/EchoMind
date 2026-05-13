package com.echomind.agent.tool;

import java.util.List;

/**
 * 外部 MCP Server 挂载配置。
 *
 * <p>这里描述的是“主项目去连接别人的 MCP Server”，不是把 EchoMind 自己暴露成 MCP Server。
 * 当前运行时只实现 stdio：后端启动一个本地子进程，再通过标准输入/输出按 MCP 协议通信。</p>
 *
 * @param id               服务唯一标识，用于前端管理、日志追踪和工具来源标记
 * @param transport        传输方式，当前支持 stdio
 * @param command          启动命令，例如 {@code ["java", "-jar", "/app/mcp/demo.jar"]}
 * @param workingDirectory 子进程工作目录，可为空
 */
public record ExternalMcpServerConfig(
    String id,
    String transport,
    List<String> command,
    String workingDirectory
) {
}
