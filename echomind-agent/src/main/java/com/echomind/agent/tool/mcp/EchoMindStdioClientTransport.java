package com.echomind.agent.tool.mcp;

import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.McpJsonMapper;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 给 Spring AI MCP stdio transport 补上 EchoMind 需要的运行时约束。
 *
 * <p>Java SDK 的 stdio transport 已负责协议通信；这里仅设置子进程工作目录，并清理从主
 * 应用继承的 OpenTelemetry javaagent 环境，避免外部 MCP 子进程把 tracing 输出写进 stdio 协议流。</p>
 *
 * <p>问题背景：EchoMind 主应用通过 -javaagent 启动 OpenTelemetry，子进程会继承 JAVA_TOOL_OPTIONS
 * 环境变量，导致 MCP 子进程也尝试输出 tracing 数据到 stdout，污染 MCP 协议流。</p>
 */
public final class EchoMindStdioClientTransport extends StdioClientTransport {

    /**
     * 需要从子进程环境变量中移除的 OpenTelemetry 环境变量前缀。
     */
    private static final List<String> OTEL_ENV_PREFIXES = List.of("OTEL_");

    /**
     * 需要从 JAVA_TOOL_OPTIONS 中移除的 javaagent 相关 token。
     */
    private static final List<String> STRIP_JAVA_TOOL_OPTIONS_TOKENS = List.of(
        "-javaagent:/app/opentelemetry-javaagent.jar",
        "opentelemetry-javaagent.jar"
    );

    /**
     * 子进程工作目录，为 null 时使用默认目录。
     */
    private final Path workingDirectory;

    /**
     * 构造 EchoMind stdio 传输客户端。
     *
     * @param params          MCP Server 启动参数（可执行文件、参数、环境变量）
     * @param jsonMapper      JSON 序列化器
     * @param workingDirectory 子进程工作目录
     */
    public EchoMindStdioClientTransport(ServerParameters params, McpJsonMapper jsonMapper, Path workingDirectory) {
        super(params, jsonMapper);
        this.workingDirectory = workingDirectory;
    }

    /**
     * 重写 ProcessBuilder 创建逻辑，设置工作目录并清理 tracing 环境变量。
     *
     * @return 配置好的 ProcessBuilder
     */
    @Override
    protected ProcessBuilder getProcessBuilder() {
        // ProcessBuider能创建Process,通过Process对象能够和子线程进行线程之间的通信
        ProcessBuilder builder = super.getProcessBuilder();
        if (workingDirectory != null) {
            builder.directory(workingDirectory.toFile());
        }
        sanitizeTracingEnvironment(builder.environment());
        return builder;
    }

    /**
     * 清理子进程环境变量中的 OpenTelemetry 相关配置。
     *
     * <p>步骤：
     * <ol>
     *   <li>移除所有以 "OTEL_" 开头的环境变量</li>
     *   <li>清理 JAVA_TOOL_OPTIONS 中的 javaagent token</li>
     * </ol>
     *
     * @param environment 子进程环境变量 Map
     */
    private void sanitizeTracingEnvironment(Map<String, String> environment) {
        if (environment == null || environment.isEmpty()) {
            return;
        }

        // 步骤 1：移除 OTEL_ 前缀的环境变量
        List<String> keysToRemove = new ArrayList<>();
        for (String key : environment.keySet()) {
            for (String prefix : OTEL_ENV_PREFIXES) {
                if (key.startsWith(prefix)) {
                    keysToRemove.add(key);
                    break;
                }
            }
        }
        keysToRemove.forEach(environment::remove);

        // 步骤 2：清理 JAVA_TOOL_OPTIONS 中的 javaagent token
        String javaToolOptions = environment.get("JAVA_TOOL_OPTIONS");
        if (javaToolOptions == null || javaToolOptions.isBlank()) {
            return;
        }
        String sanitized = javaToolOptions;
        for (String token : STRIP_JAVA_TOOL_OPTIONS_TOKENS) {
            sanitized = sanitized.replace(token, "");
        }
        sanitized = sanitized.trim().replaceAll("\\s{2,}", " ");
        if (sanitized.isBlank()) {
            environment.remove("JAVA_TOOL_OPTIONS");
        } else {
            environment.put("JAVA_TOOL_OPTIONS", sanitized);
        }
    }
}