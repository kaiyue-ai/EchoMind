package com.echomind.llm.provider;

import com.echomind.llm.router.ModelSpec;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Anthropic（Claude）模型提供商实现 —— 封装 Anthropic Messages API 的调用逻辑。
 *
 * <p>通过 HTTP 客户端直接调用 Anthropic API（{@code /v1/messages} 端点），
 * 支持同步对话和带工具定义的对话。流式输出当前标记为未实现。
 *
 * <p><b>API 版本：</b> 使用 Anthropic API 版本 {@code 2023-06-01}，
 * 认证方式为 {@code x-api-key} 请求头。
 *
 * <p><b>设计决策：</b>
 * <ul>
 *   <li>使用 Java 11+ 内置的 {@link java.net.http.HttpClient}，避免引入额外的 HTTP 库依赖。</li>
 *   <li>连接超时设为 30 秒，请求超时设为 2 分钟，适应 LLM 推理的较长响应时间。</li>
 *   <li>错误不向上抛出异常，而是返回 {@code "[Error] ..."} 格式的字符串，
 *       确保上层调用链路的稳定性。</li>
 *   <li>系统提示通过 Anthropic 特有的 {@code system} 顶层字段传递（非 messages 数组内）。</li>
 * </ul>
 *
 * @see ModelProvider
 * @see OpenAiProvider
 */
public class AnthropicProvider implements ModelProvider {

    /** 日志记录器，用于记录 API 调用错误 */
    private static final Logger log = LoggerFactory.getLogger(AnthropicProvider.class);

    /** JSON 序列化/反序列化器，线程安全 */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Anthropic API 密钥，从环境变量 ANTHROPIC_API_KEY 注入 */
    private final String apiKey;

    /** Anthropic API 基础 URL，默认为 https://api.anthropic.com */
    private final String baseUrl;

    /** HTTP 客户端，连接超时 30 秒 */
    private final HttpClient httpClient;

    /**
     * 构造 AnthropicProvider 实例。
     *
     * @param apiKey  Anthropic API 密钥，不能为 {@code null}
     * @param baseUrl API 基础 URL，为 {@code null} 时使用默认值 {@code https://api.anthropic.com}
     */
    public AnthropicProvider(String apiKey, String baseUrl) {
        this.apiKey = apiKey;
        this.baseUrl = baseUrl != null ? baseUrl : "https://api.anthropic.com";
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();
    }

    /**
     * 返回提供商标识符。
     *
     * @return 固定返回 "anthropic"
     */
    @Override
    public String providerId() {
        return "anthropic";
    }

    /**
     * 检查是否支持指定的模型规格。
     *
     * @param model 模型规格
     * @return 当 model.providerId() 为 "anthropic" 时返回 {@code true}
     */
    @Override
    public boolean supports(ModelSpec model) {
        return "anthropic".equals(model.providerId());
    }

    /**
     * 执行与 Claude 模型的同步对话。
     *
     * <p>调用 Anthropic Messages API，构建包含 system 提示和单条 user 消息的请求体。
     * 解析响应 JSON 中 {@code content[0].text} 字段作为回复内容。
     *
     * <p><b>请求参数：</b>
     * <ul>
     *   <li>max_tokens 固定为 4096</li>
     *   <li>system 提示通过顶层字段传递（Anthropic 特有约定）</li>
     *   <li>messages 仅包含单条 user 角色的消息</li>
     * </ul>
     *
     * @param model        目标 Claude 模型（如 claude-sonnet-4-20250514）
     * @param systemPrompt 系统提示词，可为 {@code null}
     * @param userMessage  用户消息内容
     * @return 模型的文本回复，API 调用失败时返回 {@code "[Error] ..."} 格式字符串
     */
    @Override
    public String chat(ModelSpec model, String systemPrompt, String userMessage) {
        try {
            Map<String, Object> body = Map.of(
                "model", model.modelName(),
                "max_tokens", 4096,
                "system", systemPrompt != null ? systemPrompt : "",
                "messages", List.of(
                    Map.of("role", "user", "content", userMessage)
                )
            );

            String json = MAPPER.writeValueAsString(body);
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/v1/messages"))
                .header("x-api-key", apiKey)
                .header("anthropic-version", "2023-06-01")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .timeout(Duration.ofMinutes(2))
                .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.error("Anthropic API error: {} - {}", response.statusCode(), response.body());
                return "[Error] Anthropic API returned status " + response.statusCode();
            }

            JsonNode root = MAPPER.readTree(response.body());
            return root.path("content").get(0).path("text").asText();

        } catch (Exception e) {
            log.error("Anthropic API call failed", e);
            return "[Error] " + e.getMessage();
        }
    }

    /**
     * 流式对话 —— 当前未实现。
     *
     * @param model        目标模型规格
     * @param systemPrompt 系统提示词
     * @param userMessage  用户消息
     * @return 包含 {@link UnsupportedOperationException} 的错误 Flux
     */
    @Override
    public Flux<String> stream(ModelSpec model, String systemPrompt, String userMessage) {
        return Flux.error(new UnsupportedOperationException("Streaming not yet implemented for Anthropic"));
    }

    /**
     * 执行带工具定义的对话 —— 模型可请求调用外部工具。
     *
     * <p>与 {@link #chat} 的区别在于请求体中额外包含 {@code tools} 字段，
     * 并以原始 JSON 格式返回完整响应（而非仅提取文本），以便上层解析
     * tool_use 内容块。
     *
     * <p><b>请求参数：</b> 在 chat 参数基础上增加了 tools 定义。
     *
     * @param model        目标 Claude 模型
     * @param systemPrompt 系统提示词，可为 {@code null}
     * @param userMessage  用户消息内容
     * @param toolsJson    工具定义的 JSON 字符串
     * @return 原始 API 响应 JSON 字符串，可能包含 tool_use 内容块
     */
    @Override
    public String chatWithTools(ModelSpec model, String systemPrompt, String userMessage, String toolsJson) {
        try {
            JsonNode toolsNode = MAPPER.readTree(toolsJson);
            Map<String, Object> body = Map.of(
                "model", model.modelName(),
                "max_tokens", 4096,
                "system", systemPrompt != null ? systemPrompt : "",
                "messages", List.of(
                    Map.of("role", "user", "content", userMessage)
                ),
                "tools", toolsNode
            );

            String json = MAPPER.writeValueAsString(body);
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/v1/messages"))
                .header("x-api-key", apiKey)
                .header("anthropic-version", "2023-06-01")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .timeout(Duration.ofMinutes(2))
                .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                return "[Error] API status " + response.statusCode();
            }
            return MAPPER.readTree(response.body()).toString();

        } catch (Exception e) {
            log.error("Anthropic tool call failed", e);
            return "[Error] " + e.getMessage();
        }
    }
}
