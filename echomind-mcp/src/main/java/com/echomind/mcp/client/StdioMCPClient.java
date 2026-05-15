package com.echomind.mcp.client;

import com.echomind.common.exception.MCPTransportException;
import com.echomind.mcp.tool.ToolResult;
import com.echomind.mcp.tool.ToolSpec;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 外部 stdio MCP 客户端。
 *
 * <p>它负责启动一个本地 MCP Server 子进程，通过标准输入/输出发送 JSON-RPC 消息，
 * 再把远端工具列表和工具调用结果转换成 EchoMind 内部的 {@link ToolSpec}/{@link ToolResult}。</p>
 */
@Slf4j
public class StdioMCPClient implements Closeable {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);
    private static final List<String> OTEL_ENV_PREFIXES = List.of("OTEL_");
    private static final List<String> STRIP_JAVA_TOOL_OPTIONS_TOKENS = List.of(
        "-javaagent:/app/opentelemetry-javaagent.jar",
        "opentelemetry-javaagent.jar"
    );

    private final String serverId;
    private final List<String> command;
    private final Path workingDirectory;
    private final Duration timeout;
    private final AtomicInteger requestId = new AtomicInteger(1);

    private Process process;
    private BufferedWriter writer;
    private BufferedReader reader;

    public StdioMCPClient(String serverId, List<String> command, Path workingDirectory) {
        this(serverId, command, workingDirectory, DEFAULT_TIMEOUT);
    }

    public StdioMCPClient(String serverId, List<String> command, Path workingDirectory, Duration timeout) {
        this.serverId = serverId;
        this.command = List.copyOf(command);
        this.workingDirectory = workingDirectory;
        this.timeout = timeout == null ? DEFAULT_TIMEOUT : timeout;
    }

    /**
     * 启动子进程并完成 MCP initialize 握手。
     */
    public synchronized boolean initialize() {
        try {
            ensureStarted();
            ObjectNode params = MAPPER.createObjectNode();
            params.put("protocolVersion", "2024-11-05");
            params.set("capabilities", MAPPER.createObjectNode());
            ObjectNode clientInfo = MAPPER.createObjectNode();
            clientInfo.put("name", "echomind");
            clientInfo.put("version", "1.0.0");
            params.set("clientInfo", clientInfo);

            JsonNode response = sendRequest("initialize", params);
            notifyInitialized();
            String protocolVersion = response.path("result").path("protocolVersion").asText("");
            return !protocolVersion.isBlank();
        } catch (Exception ex) {
            log.warn("Stdio MCP server {} initialize failed: {}", serverId, ex.getMessage());
            closeQuietly();
            return false;
        }
    }

    /**
     * 当前子进程是否仍在运行。
     */
    public synchronized boolean isRunning() {
        return process != null && process.isAlive();
    }

    public synchronized List<ToolSpec> listTools() {
        try {
            ensureStarted();
            JsonNode response = sendRequest("tools/list", MAPPER.createObjectNode());
            JsonNode tools = response.path("result").path("tools");
            return MAPPER.convertValue(tools,
                    MAPPER.getTypeFactory().constructCollectionType(List.class, ToolSpec.class));
        } catch (Exception ex) {
            throw new MCPTransportException("Failed to list stdio MCP tools from " + serverId, ex);
        }
    }

    public synchronized ToolResult callTool(String toolName, Map<String, Object> arguments) {
        try {
            ensureStarted();
            ObjectNode params = MAPPER.createObjectNode();
            params.put("name", toolName);
            params.set("arguments", MAPPER.valueToTree(arguments == null ? Map.of() : arguments));
            JsonNode response = sendRequest("tools/call", params);
            return MAPPER.treeToValue(response.path("result"), ToolResult.class);
        } catch (Exception ex) {
            throw new MCPTransportException("Failed to call stdio MCP tool " + toolName + " from " + serverId, ex);
        }
    }

    private void ensureStarted() throws IOException {
        if (process != null && process.isAlive()) {
            return;
        }
        if (command.isEmpty()) {
            throw new IOException("Stdio MCP command is empty: " + serverId);
        }
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectError(ProcessBuilder.Redirect.INHERIT);
        if (workingDirectory != null) {
            builder.directory(workingDirectory.toFile());
        }
        sanitizeTracingEnvironment(builder.environment());
        process = builder.start();
        writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
    }

    private void sanitizeTracingEnvironment(Map<String, String> environment) {
        if (environment == null || environment.isEmpty()) {
            return;
        }
        List<String> keysToRemove = new ArrayList<>();
        for (String key : environment.keySet()) {
            for (String prefix : OTEL_ENV_PREFIXES) {
                if (key.startsWith(prefix)) {
                    keysToRemove.add(key);
                    break;
                }
            }
        }
        for (String key : keysToRemove) {
            environment.remove(key);
        }
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

    // 发送 JSON-RPC 请求
    private JsonNode sendRequest(String method, JsonNode params) throws IOException {
        int id = requestId.getAndIncrement();
        ObjectNode request = MAPPER.createObjectNode();
        request.put("jsonrpc", "2.0");
        request.put("id", id);
        request.put("method", method);
        request.set("params", params);

        writer.write(MAPPER.writeValueAsString(request));
        writer.newLine();
        writer.flush();

        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            String line = reader.readLine();
            if (line == null) {
                throw new IOException("Stdio MCP server exited: " + serverId);
            }
            if (line.isBlank()) {
                continue;
            }
            JsonNode response = MAPPER.readTree(line);
            if (response.path("id").asInt(-1) == id) {
                if (response.hasNonNull("error")) {
                    throw new IOException(response.path("error").path("message").asText("MCP error"));
                }
                return response;
            }
        }
        throw new IOException("Stdio MCP request timeout: " + method);
    }

    private void notifyInitialized() throws IOException {
        ObjectNode notification = MAPPER.createObjectNode();
        notification.put("jsonrpc", "2.0");
        notification.put("method", "notifications/initialized");
        writer.write(MAPPER.writeValueAsString(notification));
        writer.newLine();
        writer.flush();
    }

    @Override
    public synchronized void close() {
        closeQuietly();
    }

    private void closeQuietly() {
        try {
            if (writer != null) {
                writer.close();
            }
        } catch (IOException ignored) {
        }
        try {
            if (reader != null) {
                reader.close();
            }
        } catch (IOException ignored) {
        }
        if (process != null && process.isAlive()) {
            process.destroy();
            try {
                if (!process.waitFor(3, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
        }
        process = null;
        writer = null;
        reader = null;
    }
}
