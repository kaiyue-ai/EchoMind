package com.echomind.common.exception;

/**
 * <h2>MCP 传输异常</h2>
 * 当 MCP（Model Context Protocol）客户端与远程 MCP 服务器之间的通信发生失败时抛出。
 *
 * <h3>MCP 2024-11-05 协议</h3>
 * 本异常覆盖 MCP 协议定义的所有传输层面的错误，包括：
 * <ul>
 *   <li><b>stdio 模式</b> —— 子进程启动失败、管道断裂、标准输入/输出流异常</li>
 *   <li><b>HTTP 模式</b> —— 连接超时、TLS 握手失败、HTTP 状态码非 2xx</li>
 *   <li><b>JSON-RPC 层</b> —— 消息帧格式错误、JSON 解析失败、请求 ID 不匹配</li>
 *   <li><b>协议层</b> —— 不支持的方法、无效参数、服务器能力不匹配</li>
 * </ul>
 *
 * <h3>处理策略</h3>
 * MCP 客户端在捕获此异常后会尝试重连和重试（指数退避），超过最大重试次数后
 * 将异常向上传播，由 {@code AgentOrchestrator} 决定是否使用本地后备工具替代远程工具。
 *
 * @see com.echomind.mcp.MCPClient MCP 客户端
 * @see com.echomind.mcp.MCPServer MCP 服务端
 * @see com.echomind.mcp.JsonRpcMessage JSON-RPC 消息定义
 */
public class MCPTransportException extends EchoMindException {
    /**
     * 使用错误描述和原始异常构造 MCP 传输异常。
     * 必须提供原始异常以保留完整的传输层故障信息（例如 I/O 异常或 HTTP 异常）。
     *
     * @param message 人类可读的错误描述，说明通信失败的上下文（如目标 URL、命令等）
     * @param cause   底层传输机制抛出的原始异常
     */
    public MCPTransportException(String message, Throwable cause) {
        super(message, cause);
    }
}
