package com.echomind.console.alerts;

import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class FeishuWebhookClientTest {

    @Test
    void usesWebhookEnvironmentPropertyAsDefaultUrl() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setResponseCode(200).setBody("{\"code\":0}"));
            String webhookUrl = server.url("/open-apis/bot/v2/hook/default").toString();

            new ApplicationContextRunner()
                .withBean(ObjectMapper.class)
                .withBean(FeishuWebhookClient.class)
                .withPropertyValues("Webhook=" + webhookUrl)
                .run(context -> {
                    FeishuWebhookClient.SendResult result = context.getBean(FeishuWebhookClient.class)
                        .send(event());

                    assertThat(result.status()).isEqualTo(AlertStatus.SENT);
                    assertThat(result.providerResponse()).contains("code");
                });

            var request = server.takeRequest();
            assertThat(request.getPath()).isEqualTo("/open-apis/bot/v2/hook/default");
            assertThat(request.getBody().readUtf8())
                .contains("\"msg_type\":\"text\"")
                .contains("TraceID")
                .contains("trace-a");
        }
    }

    @Test
    void reportsDefaultWebhookConfigurationState() {
        assertThat(new FeishuWebhookClient(new ObjectMapper(), "https://open.feishu.cn/hook")
            .hasDefaultWebhookUrl()).isTrue();
        assertThat(new FeishuWebhookClient(new ObjectMapper(), "")
            .hasDefaultWebhookUrl()).isFalse();
    }

    @Test
    void returnsNotConfiguredWhenWebhookIsMissing() {
        FeishuWebhookClient client = new FeishuWebhookClient(new ObjectMapper(), "");

        FeishuWebhookClient.SendResult result = client.send(event());

        assertThat(result.status()).isEqualTo(AlertStatus.NOT_CONFIGURED);
        assertThat(result.failureReason()).contains("not configured");
    }

    @Test
    void returnsFailedWhenFeishuRespondsWithHttpError() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setResponseCode(500).setBody("bad gateway"));
            FeishuWebhookClient client = new FeishuWebhookClient(new ObjectMapper(),
                server.url("/hook").toString());

            FeishuWebhookClient.SendResult result = client.send(event());

            assertThat(result.status()).isEqualTo(AlertStatus.FAILED);
            assertThat(result.failureReason()).contains("HTTP 500");
            assertThat(result.providerResponse()).contains("bad gateway");
        }
    }

    private AlertEventEntity event() {
        AlertEventEntity event = new AlertEventEntity();
        event.setAlertType(AlertType.CALL_ERROR);
        event.setSeverity(AlertSeverity.WARNING);
        event.setTraceId("trace-a");
        event.setUserId("user-a");
        event.setUsername("alice");
        event.setAgentId("default");
        event.setSessionId("session-a");
        event.setTitle("AI 调用失败");
        event.setMessage("provider timeout");
        event.setSuggestion("按 TraceID 查看失败 Span");
        return event;
    }
}
