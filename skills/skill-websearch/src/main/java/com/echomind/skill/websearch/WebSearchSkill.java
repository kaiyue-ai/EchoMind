package com.echomind.skill.websearch;

import com.echomind.skill.api.*;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * 网页搜索技能 —— 通过 DuckDuckGo Instant Answer API 进行网络搜索。
 *
 * <p>本技能使用 DuckDuckGo 的免费即时回答 API（无需 API 密钥），
 * 返回与查询相关的摘要信息。适用于快速知识查询和事实检索。
 *
 * <p>设计要点：
 * <ul>
 *   <li><b>API 选择</b>：DuckDuckGo 即时回答 API ——
 *       免费、无需认证、返回 JSON 格式的结构化数据。</li>
 *   <li><b>结果提取策略</b>：优先级顺序为
 *       AbstractText → Answer → 默认提示文本。
 *       AbstractText 提供最丰富的搜索结果摘要。</li>
 *   <li><b>编码处理</b>：查询字符串通过 {@link java.net.URLEncoder}
 *       进行 UTF-8 编码，确保特殊字符和中文等非 ASCII 查询正常传递。</li>
 *   <li><b>优雅降级</b>：API 不可用时返回带查询内容的模拟结果提示。</li>
 *   <li><b>网络超时</b>：连接和读取超时均为 10 秒。</li>
 * </ul>
 *
 * <p>输入参数：
 * <ul>
 *   <li>{@code query}（string，必填）—— 搜索查询关键词</li>
 * </ul>
 *
 * <p>技能标签：search, web, find, lookup, internet, 搜索, 查询, 搜索网络, 网络搜索
 */
public class WebSearchSkill implements Skill {

    /**
     * HTTP 客户端实例（静态共享）——
     * 配置 10 秒连接超时和 10 秒读取超时。
     */
    private static final OkHttpClient HTTP = new OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build();

    /**
     * 返回技能元数据。
     *
     * <p>定义输入参数 Schema：一个名为 "query" 的必填字符串参数。
     *
     * @return 网页搜索技能的完整元数据
     */
    @Override
    public SkillMetadata metadata() {
        return new SkillMetadata(
            "web-search",
            "1.0.0",
            "Search the internet for real-time information, news, and current events. Use this whenever you need up-to-date information beyond your knowledge cutoff.",
            Map.of(
                "properties", Map.of(
                    "query", Map.of("type", "string", "description", "What to search for on the web")
                ),
                "required", List.of("query")
            ),
            List.of(),
            "EchoMind",
            List.of("search", "web", "find", "lookup", "internet", "搜索", "查询", "搜索网络", "网络搜索"),
            List.of("搜索", "查询", "查一下", "联网搜索", "网络搜索", "最新", "实时信息", "search web"),
            Map.of(
                "search", List.of("搜索", "查找", "查一下", "查询"),
                "web", List.of("联网", "网络", "网页", "互联网"),
                "current", List.of("最新", "实时", "现在")
            )
        );
    }

    /**
     * 执行网页搜索。
     *
     * <p>将查询字符串编码后调用 DuckDuckGo 即时回答 API，
     * 从 JSON 响应中提取摘要信息。API 失败时返回模拟结果。
     *
     * @param request 技能请求，包含 "query" 参数
     * @return 包含搜索摘要或模拟结果的异步结果
     */
    @Override
    public CompletableFuture<SkillResult> execute(SkillRequest request) {
        return CompletableFuture.supplyAsync(() -> {
            long start = System.currentTimeMillis();
            String query = String.valueOf(request.parameters().getOrDefault("query", ""));
            try {
                String encoded = java.net.URLEncoder.encode(query, "UTF-8");

                // Try Instant Answer API first (good for facts, calculations, definitions)
                String instantUrl = "https://api.duckduckgo.com/?q=" + encoded
                                  + "&format=json&no_html=1&skip_disambig=1";
                Request httpReq = new Request.Builder()
                    .url(instantUrl)
                    .header("User-Agent", "EchoMind/1.0")
                    .build();
                try (Response resp = HTTP.newCall(httpReq).execute()) {
                    String body = resp.body() != null ? resp.body().string() : "";
                    String result = extractAbstract(body);
                    if (!result.isEmpty() && !result.startsWith("No relevant")) {
                        return SkillResult.success(result, System.currentTimeMillis() - start);
                    }
                }

                // Fallback: scrape DuckDuckGo Lite for real web results
                String liteUrl = "https://lite.duckduckgo.com/lite/?q=" + encoded;
                Request liteReq = new Request.Builder()
                    .url(liteUrl)
                    .header("User-Agent", "EchoMind/1.0")
                    .build();
                try (Response resp = HTTP.newCall(liteReq).execute()) {
                    String body = resp.body() != null ? resp.body().string() : "";
                    String results = extractLiteResults(body, query);
                    return SkillResult.success(results, System.currentTimeMillis() - start);
                }
            } catch (Exception e) {
                return SkillResult.failure(
                    "Search failed: " + e.getMessage(),
                    System.currentTimeMillis() - start);
            }
        });
    }

    /**
     * Extract search result snippets from DuckDuckGo Lite HTML.
     * Lite version has minimal markup: results are in &lt;a&gt; tags with class="result-link"
     * and snippets in &lt;td&gt; elements with class="result-snippet".
     */
    private String extractLiteResults(String html, String query) {
        StringBuilder sb = new StringBuilder();
        sb.append("Web search results for \"").append(query).append("\":\n");
        // Parse result-snippet td elements
        int idx = 0;
        int count = 0;
        while ((idx = html.indexOf("result-snippet", idx)) != -1 && count < 5) {
            int start = html.indexOf('>', idx) + 1;
            int end = html.indexOf("</td>", start);
            if (start > 0 && end > start) {
                String snippet = html.substring(start, end).trim();
                // Strip HTML tags
                snippet = snippet.replaceAll("<[^>]+>", "").replaceAll("&amp;", "&")
                    .replaceAll("&lt;", "<").replaceAll("&gt;", ">")
                    .replaceAll("&quot;", "\"").replaceAll("&#39;", "'");
                if (!snippet.isEmpty()) {
                    count++;
                    sb.append(count).append(". ").append(snippet).append("\n");
                }
            }
            idx = end + 5;
        }
        if (count == 0) {
            sb.append("(No search results found — DuckDuckGo returned empty results)");
        }
        return sb.toString();
    }

    /**
     * Extract AbstractText or Answer from DuckDuckGo Instant Answer JSON.
     * Returns empty string when neither field has content.
     */
    private String extractAbstract(String json) {
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            var node = mapper.readTree(json);
            String abs = node.path("AbstractText").asText();
            if (!abs.isEmpty()) return abs;
            return node.path("Answer").asText();
        } catch (Exception e) {
            return "";
        }
    }
}
