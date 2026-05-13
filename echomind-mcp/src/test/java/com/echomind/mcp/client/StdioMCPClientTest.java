package com.echomind.mcp.client;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StdioMCPClientTest {

    @Test
    void initializeListAndCallNowcoderMcpServer() {
        Path jar = Path.of("D:/claudeWorkSpace/nowcoder-java-interview-mcp-server/target/nowcoder-java-interview-mcp-server-1.0.0.jar");
        StdioMCPClient client = new StdioMCPClient(
                "nowcoder-java-interview-test",
                List.of("java", "-jar", jar.toString()),
                jar.getParent().getParent()
        );

        try {
            assertTrue(client.initialize());
            var tools = client.listTools();
            assertTrue(tools.stream().anyMatch(tool -> "fetch_nowcoder_java_interview_article".equals(tool.name())));
            var result = client.callTool("fetch_nowcoder_java_interview_article",
                    Map.of("url", "https://example.com/test"));
            assertFalse(result.content().isEmpty());
            assertTrue(result.content().get(0).text().contains("只允许抓取 www.nowcoder.com"));
        } finally {
            client.close();
        }
    }
}
