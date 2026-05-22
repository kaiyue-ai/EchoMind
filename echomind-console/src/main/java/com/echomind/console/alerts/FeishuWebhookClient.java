package com.echomind.console.alerts;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Component
@Slf4j
public class FeishuWebhookClient {

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final ObjectMapper mapper;
    private final String defaultWebhookUrl;
    private final OkHttpClient client = new OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .build();

    public FeishuWebhookClient(
        ObjectMapper mapper,
        @Value("${echomind.alerts.feishu.webhook-url:${Webhook:}}") String defaultWebhookUrl
    ) {
        this.mapper = mapper;
        this.defaultWebhookUrl = defaultWebhookUrl;
    }

    public SendResult send(AlertEventEntity event) {
        if (!hasDefaultWebhookUrl()) {
            return new SendResult(AlertStatus.NOT_CONFIGURED, "Feishu webhook is not configured");
        }
        try {
            String content = """
                【EchoMind 告警】%s
                级别：%s
                类型：%s
                TraceID：%s
                用户：%s
                Agent：%s
                会话：%s
                详情：%s
                建议：%s
                升级：%s
                """.formatted(
                event.getTitle(),
                event.getSeverity(),
                event.getAlertType(),
                value(event.getTraceId()),
                value(event.getUsername()),
                value(event.getAgentId()),
                value(event.getSessionId()),
                value(event.getMessage()),
                value(event.getSuggestion()),
                event.isEscalated() ? "是，静默累计 " + event.getSuppressedCount() + " 次" : "否"
            );
            Map<String, Object> body = Map.of(
                "msg_type", "text",
                "content", Map.of("text", content)
            );
            Request request = new Request.Builder()
                .url(defaultWebhookUrl)
                .post(RequestBody.create(mapper.writeValueAsBytes(body), JSON))
                .build();
            try (var response = client.newCall(request).execute()) {
                String responseBody = response.body() == null ? "" : response.body().string();
                String providerResponse = truncate(responseBody, 1000);
                if (!response.isSuccessful()) {
                    String failure = "HTTP " + response.code() + ": " + providerResponse;
                    return new SendResult(AlertStatus.FAILED, failure, providerResponse);
                }
                return new SendResult(AlertStatus.SENT, null, providerResponse);
            }
        } catch (IOException | RuntimeException e) {
            log.warn("Failed to send Feishu alert type={} trace={}: {}",
                event.getAlertType(), event.getTraceId(), e.getMessage());
            return new SendResult(AlertStatus.FAILED, e.getMessage(), e.getMessage());
        }
    }

    public boolean hasDefaultWebhookUrl() {
        return defaultWebhookUrl != null && !defaultWebhookUrl.isBlank();
    }

    private String value(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    public record SendResult(AlertStatus status, String failureReason, String providerResponse) {
        public SendResult(AlertStatus status, String failureReason) {
            this(status, failureReason, failureReason);
        }
    }
}
