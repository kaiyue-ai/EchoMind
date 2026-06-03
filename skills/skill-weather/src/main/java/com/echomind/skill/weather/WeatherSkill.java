package com.echomind.skill.weather;

import com.echomind.skill.api.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * 天气查询技能 —— 通过 wttr.in 免费 API 查询城市天气。
 *
 * <p>本技能实现 EchoMind 平台的 {@link Skill} 接口，提供天气查询能力。
 * 支持中文城市名称作为输入（如 "北京"、"上海"），内部通过
 * {@link #CITY_MAP} 映射表将中文城市名转换为英文名称后调用 wttr.in API。
 *
 * <p>设计要点：
 * <ul>
 *   <li><b>API 源</b>：使用 wttr.in 的格式化接口（{@code ?format=%C+%t+%h+%w}），
 *       返回纯文本格式的天气数据（天气状况 + 温度 + 湿度 + 风速）。</li>
 *   <li><b>中文城市名支持</b>：内置 18 个主要中国城市的英文名映射，
 *       通过 {@link #extractCity} 方法从输入文本中识别并转换。</li>
 *   <li><b>网络超时</b>：连接和读取超时均设置为 10 秒，防止长时间阻塞。</li>
 *   <li><b>优雅降级</b>：当 API 调用失败时（网络异常、超时等），
 *       自动返回模拟天气数据。</li>
 *   <li><b>城市缺失</b>：无法识别城市名时返回澄清提示，避免模型反复调用工具。</li>
 *   <li><b>异步执行</b>：通过 {@link CompletableFuture#supplyAsync} 在
 *       公共线程池中异步执行，避免阻塞调用方线程。</li>
 * </ul>
 *
 * <p>输入参数：
 * <ul>
 *   <li>{@code city}（string，必填）—— 城市名称，支持中文（"北京"、"上海"）和英文（"London"）</li>
 * </ul>
 *
 * <p>技能标签：weather, forecast, temperature, 天气, 气温, 温度, 预报
 */
public class WeatherSkill implements Skill {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * HTTP 客户端实例（静态共享）——
     * 配置 10 秒连接超时和 10 秒读取超时。
     */
    private static final OkHttpClient HTTP = new OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build();

    private final String baseUrl;
    private final OkHttpClient http;

    public WeatherSkill() {
        this("https://wttr.in", HTTP);
    }

    WeatherSkill(String baseUrl, OkHttpClient http) {
        this.baseUrl = trimTrailingSlash(baseUrl);
        this.http = http;
    }

    /**
     * 返回技能元数据。
     *
     * <p>定义技能的标识、版本、描述、输入参数 Schema、
     * MCP 依赖、作者和搜索标签（含中英文）。
     *
     * @return 天气查询技能的完整元数据
     */
    @Override
    public SkillMetadata metadata() {
        return new SkillMetadata(
            "weather-query",
            "1.0.0",
            "查询指定城市的实时天气和天气预报，支持常见中文城市名，数据来自 wttr.in；网络不可用时返回明确的降级结果。",
            Map.of(
                "properties", Map.of(
                    "city", Map.of("type", "string", "description", "City name, optional when the user asks a follow-up with city in context")
                )
            ),
            List.of(),
            "EchoMind",
            List.of("weather", "forecast", "temperature", "天气", "气温", "温度", "预报"),
            List.of("天气", "查天气", "天气预报", "气温", "温度", "weather", "forecast"),
            Map.of(
                "weather", List.of("天气", "天气预报", "气象"),
                "temperature", List.of("气温", "温度", "多少度", "最高温度", "最低温度", "最高气温", "最低气温")
            )
        );
    }

    /**
     * 中文城市名到英文名的映射表。
     *
     * <p>支持 18 个主要中国城市的中文名自动转换。
     * 通过 {@link #extractCity} 方法在输入文本中扫描匹配。
     */
    private static final Map<String, String> CITY_MAP = Map.ofEntries(
        Map.entry("北京", "Beijing"), Map.entry("上海", "Shanghai"),
        Map.entry("广州", "Guangzhou"), Map.entry("深圳", "Shenzhen"),
        Map.entry("杭州", "Hangzhou"), Map.entry("成都", "Chengdu"),
        Map.entry("武汉", "Wuhan"), Map.entry("南京", "Nanjing"),
        Map.entry("西安", "Xian"), Map.entry("重庆", "Chongqing"),
        Map.entry("天津", "Tianjin"), Map.entry("苏州", "Suzhou"),
        Map.entry("厦门", "Xiamen"), Map.entry("青岛", "Qingdao"),
        Map.entry("大连", "Dalian"), Map.entry("沈阳", "Shenyang"),
        Map.entry("长沙", "Changsha"), Map.entry("郑州", "Zhengzhou")
    );

    /**
     * 执行天气查询。
     *
     * <p>异步调用 wttr.in API 获取指定城市的天气信息。
     * 首先通过 {@link #extractCity} 将输入转为英文城市名，
     * 然后发起 HTTP 请求。API 失败时返回模拟天气数据。
     *
     * @param request 技能请求，包含城市名称参数
     * @return 包含天气信息或模拟数据的异步结果
     */
    @Override
    public CompletableFuture<SkillResult> execute(SkillRequest request) {
        return CompletableFuture.supplyAsync(() -> {
            long start = System.currentTimeMillis();
            String city = "Beijing";
            try {
                city = resolveCity(request);
                if (city == null || city.isBlank()) {
                    return SkillResult.success("请告诉我要查询哪个城市的天气。", System.currentTimeMillis() - start);
                }
                String url = baseUrl + "/" + encodePath(city) + "?format=j1&lang=zh";
                Request httpReq = new Request.Builder().url(url).build();
                try (Response resp = http.newCall(httpReq).execute()) {
                    String body = resp.body() != null ? resp.body().string() : "";
                    if (!resp.isSuccessful() || body.isBlank()) {
                        return fallback(city, start);
                    }
                    return SkillResult.success(formatWeather(city, MAPPER.readTree(body)),
                        System.currentTimeMillis() - start);
                }
            } catch (Exception e) {
                return fallback(city, start);
            }
        });
    }

    private SkillResult fallback(String city, long start) {
        return SkillResult.success("""
            ## 天气查询

            - 城市：%s
            - 当前天气：Sunny
            - 当前温度：22C
            - 今日最低/最高：暂无实时接口数据
            - 湿度：45%%
            - 风速：5m/s

            数据源暂不可用，以上为降级示例数据。
            """.formatted(city).strip(), System.currentTimeMillis() - start);
    }

    private String formatWeather(String city, JsonNode root) {
        JsonNode current = root.path("current_condition").isArray() && !root.path("current_condition").isEmpty()
            ? root.path("current_condition").get(0)
            : MAPPER.createObjectNode();
        JsonNode today = root.path("weather").isArray() && !root.path("weather").isEmpty()
            ? root.path("weather").get(0)
            : MAPPER.createObjectNode();
        String description = weatherDescription(current);
        return """
            ## 天气查询

            - 城市：%s
            - 当前天气：%s
            - 当前温度：%sC
            - 体感温度：%sC
            - 今日最低/最高：%sC / %sC
            - 湿度：%s%%
            - 风速：%s km/h

            数据来自 wttr.in，实时天气以数据源返回为准。
            """.formatted(
            city,
            description,
            text(current.path("temp_C"), "--"),
            text(current.path("FeelsLikeC"), "--"),
            text(today.path("mintempC"), "--"),
            text(today.path("maxtempC"), "--"),
            text(current.path("humidity"), "--"),
            text(current.path("windspeedKmph"), "--")
        ).strip();
    }

    private String weatherDescription(JsonNode current) {
        JsonNode desc = current.path("lang_zh").isArray() && !current.path("lang_zh").isEmpty()
            ? current.path("lang_zh").get(0).path("value")
            : current.path("weatherDesc").isArray() && !current.path("weatherDesc").isEmpty()
                ? current.path("weatherDesc").get(0).path("value")
                : null;
        return text(desc, "--");
    }

    private String text(JsonNode node, String fallback) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return fallback;
        }
        String value = node.asText("");
        return value == null || value.isBlank() ? fallback : value;
    }

    /**
     * 从输入文本中提取城市英文名。
     *
     * <p>遍历 {@link #CITY_MAP} 中的所有中文城市名，检查输入文本是否包含该名称。
     * 若匹配，返回对应的英文名；若输入为英文城市名，则直接返回；若无可用城市返回空。
     *
     * @param input 用户输入的城市名称文本（可能包含中文）
     * @return 对应的英文城市名；无法识别时默认返回 "Beijing"
     */
    private String extractCity(String input) {
        if (input == null || input.isBlank()) {
            return "";
        }
        for (var entry : CITY_MAP.entrySet()) {
            if (input.contains(entry.getKey())) {
                return entry.getValue();
            }
        }
        String normalized = input.trim();
        if (normalized.matches("[A-Za-z][A-Za-z .'-]{1,60}")) {
            return normalized;
        }
        return "";
    }

    private String resolveCity(SkillRequest request) {
        String city = extractCity(String.valueOf(request.parameters().getOrDefault("city", "")));
        if (!city.isBlank()) {
            return city;
        }
        return extractCity(rawUserMessage(request));
    }

    private String rawUserMessage(SkillRequest request) {
        if (request == null || request.context() == null || request.context().sessionAttributes() == null) {
            return "";
        }
        Object raw = request.context().sessionAttributes().get("rawUserMessage");
        return raw == null ? "" : String.valueOf(raw);
    }

    private String encodePath(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private String trimTrailingSlash(String value) {
        String normalized = value == null || value.isBlank() ? "https://wttr.in" : value.trim();
        while (normalized.endsWith("/") && normalized.length() > 1) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized.toLowerCase(Locale.ROOT).startsWith("http") ? normalized : "https://wttr.in";
    }
}
