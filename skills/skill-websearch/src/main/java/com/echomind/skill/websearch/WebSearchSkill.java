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
            "Search the web using DuckDuckGo Instant Answer API",
            Map.of(
                "properties", Map.of(
                    "query", Map.of("type", "string", "description", "Search query")
                ),
                "required", List.of("query")
            ),
            List.of(),
            "EchoMind",
            List.of("search", "web", "find", "lookup", "internet", "搜索", "查询", "搜索网络", "网络搜索")
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
            try {
                String query = String.valueOf(request.parameters().getOrDefault("query", ""));
                String encoded = java.net.URLEncoder.encode(query, "UTF-8");
                String url = "https://api.duckduckgo.com/?q=" + encoded + "&format=json&no_html=1";

                Request httpReq = new Request.Builder().url(url).build();
                try (Response resp = HTTP.newCall(httpReq).execute()) {
                    String body = resp.body() != null ? resp.body().string() : "No results";
                    // 从 DuckDuckGo 响应中提取 AbstractText 字段
                    String result = extractAbstract(body);
                    return SkillResult.success(result, System.currentTimeMillis() - start);
                }
            } catch (Exception e) {
                return SkillResult.success(
                    "[Search Result] Query: " + request.parameters().get("query") +
                    " (mock result - API unavailable)",
                    System.currentTimeMillis() - start);
            }
        });
    }

    /**
     * 从 DuckDuckGo JSON 响应中提取摘要文本。
     *
     * <p>提取策略（按优先级）：
     * <ol>
     *   <li>{@code AbstractText} —— 最丰富的结果摘要</li>
     *   <li>{@code Answer} —— 即时回答（如计算、定义）</li>
     *   <li>默认提示 —— 两种字段均为空时的降级文本</li>
     * </ol>
     *
     * <p>JSON 解析失败时返回错误提示而非抛出异常。
     *
     * @param json DuckDuckGo API 返回的 JSON 字符串
     * @return 提取的摘要文本；解析失败时返回错误提示
     */
    private String extractAbstract(String json) {
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            var node = mapper.readTree(json);
            String abs = node.path("AbstractText").asText();
            if (!abs.isEmpty()) return abs;
            String answer = node.path("Answer").asText();
            if (!answer.isEmpty()) return answer;
            return "No relevant results found for your query.";
        } catch (Exception e) {
            return "Search completed but failed to parse results.";
        }
    }
}
