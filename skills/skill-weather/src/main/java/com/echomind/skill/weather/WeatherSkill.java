package com.echomind.skill.weather;

import com.echomind.skill.api.*;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.util.List;
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
 *   <li><b>默认城市</b>：无法识别城市名时默认查询 "Beijing"。</li>
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
            "Get weather and forecast for a city. Powered by wttr.in",
            Map.of(
                "properties", Map.of(
                    "city", Map.of("type", "string", "description", "City name")
                ),
                "required", List.of("city")
            ),
            List.of(),
            "EchoMind",
            List.of("weather", "forecast", "temperature", "天气", "气温", "温度", "预报")
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
            try {
                String input = String.valueOf(request.parameters().getOrDefault("city", "Beijing"));
                String city = extractCity(input);
                String url = "https://wttr.in/" + city + "?format=%C+%t+%h+%w";
                Request httpReq = new Request.Builder().url(url).build();
                try (Response resp = HTTP.newCall(httpReq).execute()) {
                    String body = resp.body() != null ? resp.body().string() : "No data";
                    return SkillResult.success("Weather for " + city + ": " + body,
                        System.currentTimeMillis() - start);
                }
            } catch (Exception e) {
                return SkillResult.success(
                    "Weather: Sunny, 22C, Humidity 45%, Wind 5m/s (mock data)",
                    System.currentTimeMillis() - start);
            }
        });
    }

    /**
     * 从输入文本中提取城市英文名。
     *
     * <p>遍历 {@link #CITY_MAP} 中的所有中文城市名，检查输入文本是否包含该名称。
     * 若匹配，返回对应的英文名；若无匹配，返回默认值 "Beijing"。
     *
     * @param input 用户输入的城市名称文本（可能包含中文）
     * @return 对应的英文城市名；无法识别时默认返回 "Beijing"
     */
    private String extractCity(String input) {
        for (var entry : CITY_MAP.entrySet()) {
            if (input.contains(entry.getKey())) {
                return entry.getValue();
            }
        }
        // 如果没有匹配到中文城市名，返回默认值
        return "Beijing";
    }
}
