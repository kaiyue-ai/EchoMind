package com.echomind.skill.websearch;

import com.echomind.skill.api.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * 网页搜索技能 —— 通过 SearXNG 元搜索引擎进行网络搜索。
 *
 * <p>SearXNG 聚合 Google、Bing、DuckDuckGo、Baidu、Wikipedia 等多个搜索引擎，
 * 返回结构化 JSON 结果，无需 API 密钥，比单一引擎抓取更稳定可靠。
 *
 * <p>设计要点：
 * <ul>
 *   <li><b>SearXNG JSON API</b>：一次请求聚合 5+ 引擎，格式稳定，无 HTML 抓取风险。</li>
 *   <li><b>超时控制</b>：15 秒连接/读取超时，SearXNG 聚合多引擎需要稍长等待。</li>
 *   <li><b>结果提取</b>：优先展示 answers/infoboxes 直接答案，再列举搜索结果摘要。</li>
 *   <li><b>降级处理</b>：SearXNG 不可用时返回明确错误信息，不伪造结果。</li>
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

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final OkHttpClient HTTP = new OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build();

    private final String searxngUrl;

    public WebSearchSkill() {
        this(defaultSearxngUrl());
    }

    WebSearchSkill(String searxngUrl) {
        this.searxngUrl = searxngUrl.endsWith("/") ? searxngUrl.substring(0, searxngUrl.length() - 1) : searxngUrl;
    }

    private static String defaultSearxngUrl() {
        String url = System.getenv("SEARXNG_URL");
        if (url != null && !url.isBlank()) return url;
        url = System.getProperty("searxng.url");
        if (url != null && !url.isBlank()) return url;
        return "http://localhost:8082";
    }

    @Override
    public SkillMetadata metadata() {
        return new SkillMetadata(
            "web-search",
            "1.1.0",
            "Search the internet via SearXNG metasearch engine. Aggregates Google, Bing, DuckDuckGo, Baidu, and Wikipedia for real-time results. Use this when you need up-to-date information, news, or facts beyond your knowledge cutoff.",
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

    @Override
    public CompletableFuture<SkillResult> execute(SkillRequest request) {
        return CompletableFuture.supplyAsync(() -> {
            long start = System.currentTimeMillis();
            String query = String.valueOf(request.parameters().getOrDefault("query", ""));
            if (query.isBlank()) {
                return SkillResult.failure("query is required", System.currentTimeMillis() - start);
            }
            try {
                String encoded = java.net.URLEncoder.encode(query, "UTF-8");
                String url = searxngUrl + "/search?q=" + encoded + "&format=json&categories=general";
                Request httpReq = new Request.Builder()
                    .url(url)
                    .header("User-Agent", "EchoMind/1.0")
                    .build();

                try (Response resp = HTTP.newCall(httpReq).execute()) {
                    if (!resp.isSuccessful()) {
                        return SkillResult.failure(
                            "SearXNG returned HTTP " + resp.code(),
                            System.currentTimeMillis() - start);
                    }
                    String body = resp.body() != null ? resp.body().string() : "";
                    String result = formatResults(MAPPER.readTree(body), query);
                    return SkillResult.success(result, System.currentTimeMillis() - start);
                }
            } catch (java.net.UnknownHostException e) {
                return SkillResult.failure(
                    "SearXNG service not available at " + searxngUrl + ". Start it with: docker compose up -d searxng",
                    System.currentTimeMillis() - start);
            } catch (Exception e) {
                return SkillResult.failure(
                    "Search failed: " + e.getMessage(),
                    System.currentTimeMillis() - start);
            }
        });
    }

    private String formatResults(JsonNode root, String query) {
        StringBuilder sb = new StringBuilder();
        sb.append("Web search results for \"").append(query).append("\":\n\n");

        // 1. Direct answers take priority
        JsonNode answers = root.path("answers");
        if (answers.isArray() && !answers.isEmpty()) {
            sb.append("Answer: ");
            sb.append(stripHtml(answers.get(0).path("answer").asText("")));
            sb.append("\n\n");
        }

        // 2. Infoboxes (Wikipedia structured data)
        JsonNode infoboxes = root.path("infoboxes");
        if (infoboxes.isArray() && !infoboxes.isEmpty()) {
            JsonNode infobox = infoboxes.get(0);
            String infoboxContent = infobox.path("content").asText("");
            if (!infoboxContent.isBlank()) {
                sb.append("Info: ").append(stripHtml(infoboxContent)).append("\n\n");
            }
        }

        // 3. Search result snippets
        JsonNode results = root.path("results");
        if (results.isArray()) {
            int count = 0;
            for (JsonNode r : results) {
                if (count >= 5) break;
                String title = stripHtml(r.path("title").asText(""));
                String content = stripHtml(r.path("content").asText(""));
                String url = r.path("url").asText("");
                if (content.isBlank() && title.isBlank()) continue;
                count++;
                sb.append(count).append(". ");
                if (!title.isBlank()) {
                    sb.append("**").append(title).append("**\n");
                }
                if (!content.isBlank()) {
                    sb.append("   ").append(content).append("\n");
                }
                if (!url.isBlank()) {
                    sb.append("   ").append(url).append("\n");
                }
                sb.append("\n");
            }
            if (count == 0) {
                sb.append("(No relevant results found)\n");
            }
        } else {
            sb.append("(No results returned)\n");
        }

        // Append warnings about unresponsive engines
        JsonNode unresponsive = root.path("unresponsive_engines");
        if (unresponsive.isArray() && !unresponsive.isEmpty()) {
            sb.append("Note: some search engines were unavailable.\n");
        }

        return sb.toString().trim();
    }

    private static String stripHtml(String text) {
        if (text == null || text.isBlank()) return "";
        return text.replaceAll("<[^>]+>", "")
            .replaceAll("&amp;", "&")
            .replaceAll("&lt;", "<")
            .replaceAll("&gt;", ">")
            .replaceAll("&quot;", "\"")
            .replaceAll("&#39;", "'")
            .replaceAll("\\s+", " ")
            .trim();
    }
}
