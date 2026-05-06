package com.echomind.mcp.server.transport;

import com.echomind.mcp.jsonrpc.JsonRpcMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.function.Function;

/**
 * 标准输入/输出传输层 —— MCP 子进程协议的传输实现。
 *
 * <p>MCP 规范定义了两种传输模式：
 * <ul>
 *   <li><b>stdio 模式（子进程协议）：</b>父进程通过子进程的标准输入/输出进行 JSON-RPC 通信。
 *       每行一个完整的 JSON-RPC 消息，以换行符分隔。</li>
 *   <li><b>HTTP 模式：</b>通过 HTTP POST 发送 JSON-RPC 请求（待实现）。</li>
 * </ul>
 *
 * <p>本类实现 stdio 模式：
 * <ol>
 *   <li>启动时创建一个守护线程读取 {@code System.in}，每行解析为一个 {@link JsonRpcMessage}。</li>
 *   <li>将解析后的消息传递给注入的 handler 函数处理。</li>
 *   <li>将 handler 的响应写入 {@code System.out}，每行一个 JSON 字符串。</li>
 * </ol>
 *
 * <p>传输协议细节：
 * <ul>
 *   <li>使用 UTF-8 编码读写。</li>
 *   <li>请求和响应均为单行 JSON（不允许换行）。</li>
 *   <li>空行被忽略。</li>
 *   <li>读取线程为守护线程（daemon），不会阻止 JVM 退出。</li>
 * </ul>
 *
 * <p>设计决策：
 * <ul>
 *   <li>函数式 handler 注入 —— 通过 {@link Function} 接口注入消息处理器，
 *       使传输层与业务逻辑完全解耦，便于单元测试。</li>
 *   <li>volatile running 标志 —— 使用轻量级 volatile 布尔变量控制线程生命周期，
 *       避免使用重量级锁。</li>
 *   <li>优雅停止 —— {@link #stop()} 通过设置标志位和线程中断配合的方式停止读取线程。</li>
 * </ul>
 *
 * @author EchoMind Team
 * @see MCPServer
 * @see com.echomind.mcp.client.MCPClient
 * @since 1.0
 */
public class StdioTransport {

    private static final Logger log = LoggerFactory.getLogger(StdioTransport.class);

    /** 共享的 Jackson ObjectMapper —— 用于 JSON-RPC 消息的序列化与反序列化 */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 消息处理器 —— 接收 JsonRpcMessage，返回序列化后的响应字符串。
     * 由 MCPServer 在构造时注入。
     */
    private final Function<JsonRpcMessage, String> handler;

    /**
     * 运行状态标志 —— 使用 volatile 确保多线程可见性。
     * true = 正在运行，false = 已停止或未启动。
     */
    private volatile boolean running;

    /**
     * 读取线程 —— 运行在主线程之外，持续从 System.in 读取 JSON-RPC 消息。
     * 设置为守护线程以避免阻止 JVM 退出。
     */
    private Thread readerThread;

    /**
     * 构造 stdio 传输层实例。
     *
     * @param handler 消息处理函数，输入为 JSON-RPC 请求，输出为序列化后的响应字符串
     */
    public StdioTransport(Function<JsonRpcMessage, String> handler) {
        this.handler = handler;
    }

    /**
     * 启动传输层 —— 创建守护线程开始监听 System.in。
     *
     * <p>读取循环：
     * <ol>
     *   <li>从 {@code System.in} 逐行读取（BufferedReader）。</li>
     *   <li>将每行 JSON 解析为 {@link JsonRpcMessage}。</li>
     *   <li>调用 handler 函数处理消息并获得响应。</li>
     *   <li>如果响应非空，写入 {@code System.out} 并刷新。</li>
     * </ol>
     *
     * <p>线程名称设置为 "mcp-stdio-reader"，便于调试和监控。
     */
    public void start() {
        running = true;
        readerThread = new Thread(() -> {
            BufferedReader reader = new BufferedReader(
                new InputStreamReader(System.in, StandardCharsets.UTF_8));
            BufferedWriter writer = new BufferedWriter(
                new OutputStreamWriter(System.out, StandardCharsets.UTF_8));

            while (running) {
                try {
                    String line = reader.readLine();
                    if (line == null) break;

                    JsonRpcMessage request = MAPPER.readValue(line, JsonRpcMessage.class);
                    String response = handler.apply(request);

                    if (response != null && !response.isEmpty()) {
                        writer.write(response);
                        writer.newLine();
                        writer.flush();
                    }
                } catch (IOException e) {
                    if (running) {
                        log.error("Stdio transport read error", e);
                    }
                }
            }
        }, "mcp-stdio-reader");
        readerThread.setDaemon(true);
        readerThread.start();
        log.info("MCP stdio transport started");
    }

    /**
     * 停止传输层 —— 设置停止标志并中断读取线程。
     *
     * <p>机制：
     * <ol>
     *   <li>将 {@code running} 设为 false，使读取循环退出。</li>
     *   <li>中断读取线程（打断可能在 {@code readLine()} 上的阻塞）。</li>
     * </ol>
     *
     * <p>注意：由于读取线程是守护线程，即使不调用 stop，JVM 退出时也会自动终止。
     */
    public void stop() {
        running = false;
        if (readerThread != null) {
            readerThread.interrupt();
        }
    }
}
