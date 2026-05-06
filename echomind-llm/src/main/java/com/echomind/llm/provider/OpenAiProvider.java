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
 * OpenAI 模型提供商实现 —— 封装 OpenAI Chat Completions API 的调用逻辑。
 *
 * <p>通过 HTTP 客户端直接调用 OpenAI API（{@code /v1/chat/completions} 端点），
 * 支持同步对话和带工具定义的对话（Function Calling）。流式输出当前标记为未实现。
 *
 * <p><b>API 差异（与 Anthropic 对比）：</b>
 * <ul>
 *   <li>认证方式使用 {@code Authorization: Bearer <key>} 而非 {@code x-api-key}。</li>
 *   <li>系统提示作为独立的 {@code system} 角色消息置于 messages 数组内（非顶层字段）。</li>
 *   <li>响应文本路径为 {@code choices[0].message.content}。</li>
 *   <li>工具定义通过 {@code tools} 字段传递，遵循 OpenAI Function Calling 规范。</li>
 * </ul>
 *
 * <p><b>设计决策：</b>
 * <ul>
 *   <li>使用 Java 11+ 内置的 {@link java.net.http.HttpClient}，与 AnthropicProvider 保持一致。</li>
 *   <li>当 systemPrompt 为空或 {@code null} 时不添加 system 消息，避免某些模型拒收空 system 消息。</li>
 *   <li>错误处理策略与 AnthropicProvider 一致：捕获异常返回 {@code "[Error] ..."} 字符串。</li>
 * </ul>
 *
 * @see ModelProvider
 * @see AnthropicProvider
 */
public class OpenAiProvider implements ModelProvider {

    /** 日志记录器，用于记录 API 调用错误 */
    private static final Logger log = LoggerFactory.getLogger(OpenAiProvider.class);

    /** JSON 序列化/反序列化器，线程安全 */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** OpenAI API 密钥，从环境变量 OPENAI_API_KEY 注入 */
    private final String apiKey;

    /** OpenAI API 基础 URL，默认为 https://api.openai.com */
    private final String baseUrl;

    /** HTTP 客户端，连接超时 30 秒 */
    private final HttpClient httpClient;

    /**
     * 构造 OpenAiProvider 实例。
     *
     * @param apiKey  OpenAI API 密钥，不能为 {@code null}
     * @param baseUrl API 基础 URL，为 {@code null} 时使用默认值 {@code https://api.openai.com}
     */
    public OpenAiProvider(String apiKey, String baseUrl) {
        this.apiKey = apiKey;
        this.baseUrl = baseUrl != null ? baseUrl : "https://api.openai.com";
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();
    }

    /**
     * 返回提供商标识符。
     *
     * @return 固定返回 "openai"
     */
    @Override
    public String providerId() {
        return "openai";
    }

    /**
     * 检查是否支持指定的模型规格。
     *
     * @param model 模型规格
     * @return 当 model.providerId() 为 "openai" 时返回 {@code true}
     */
    @Override
    public boolean supports(ModelSpec model) {
        return "openai".equals(model.providerId());
    }

    /**
     * 执行与 OpenAI 模型的同步对话。
     *
     * <p>调用 OpenAI Chat Completions API。与 Anthropic API 的关键区别在于：
     * 系统提示以独立的 {@code system} 角色消息形式包含在 messages 数组中，
     * 而非作为顶层字段。当 systemPrompt 为 {@code null} 或空字符串时，
     * 不添加 system 消息以避免 API 错误。
     *
     * <p><b>请求参数：</b>
     * <ul>
     *   <li>max_tokens 固定为 4096</li>
     *   <li>messages 为 role=system + role=user 的结构化消息数组</li>
     * </ul>
     *
     * @param model        目标 OpenAI 模型（如 gpt-4o, gpt-4o-mini）
     * @param systemPrompt 系统提示词，可为 {@code null}
     * @param userMessage  用户消息内容
     * @return 模型的文本回复，从 {@code choices[0].message.content} 中提取；
     *         API 调用失败时返回 {@code "[Error] ..."} 格式字符串
     */
    @Override
    public String chat(ModelSpec model, String systemPrompt, String userMessage) {
        try {
            List<Map<String, String>> messages = new java.util.ArrayList<>();
            if (systemPrompt != null && !systemPrompt.isEmpty()) {
                messages.add(Map.of("role", "system", "content", systemPrompt));
            }
            messages.add(Map.of("role", "user", "content", userMessage));

            Map<String, Object> body = Map.of(
                "model", model.modelName(),
                "messages", messages,
                "max_tokens", 4096
            );

            String json = MAPPER.writeValueAsString(body);
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/v1/chat/completions"))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .timeout(Duration.ofMinutes(2))
                .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.error("OpenAI API error: {} - {}", response.statusCode(), response.body());
                return "[Error] OpenAI API returned status " + response.statusCode();
            }

            JsonNode root = MAPPER.readTree(response.body());
            return root.path("choices").get(0).path("message").path("content").asText();

        } catch (Exception e) {
            log.error("OpenAI API call failed", e);
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
        return Flux.error(new UnsupportedOperationException("Streaming not yet implemented for OpenAI"));
    }

    /**
     * 执行带工具定义（Function Calling）的对话。
     *
     * <p>在标准对话请求的基础上增加 {@code tools} 字段，使模型能够
     * 自主决策是否以及如何调用外部函数。以原始 JSON 格式返回完整响应
     * 供上层解析 tool_calls 内容。
     *
     * @param model        目标 OpenAI 模型
     * @param systemPrompt 系统提示词，可为 {@code null}
     * @param userMessage  用户消息内容
     * @param toolsJson    工具定义的 JSON 字符串（OpenAI Function Calling 格式）
     * @return 原始 API 响应 JSON 字符串，可能包含 tool_calls 数组
     */
    @Override
    public String chatWithTools(ModelSpec model, String systemPrompt, String userMessage, String toolsJson) {
        try {
            List<Map<String, String>> messages = new java.util.ArrayList<>();
            if (systemPrompt != null && !systemPrompt.isEmpty()) {
                messages.add(Map.of("role", "system", "content", systemPrompt));
            }
            messages.add(Map.of("role", "user", "content", userMessage));

            JsonNode toolsNode = MAPPER.readTree(toolsJson);
            Map<String, Object> body = Map.of(
                "model", model.modelName(),
                "messages", messages,
                "max_tokens", 4096,
                "tools", toolsNode
            );

            String json = MAPPER.writeValueAsString(body);
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/v1/chat/completions"))
                .header("Authorization", "Bearer " + apiKey)
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
            log.error("OpenAI tool call failed", e);
            return "[Error] " + e.getMessage();
        }
    }
}
