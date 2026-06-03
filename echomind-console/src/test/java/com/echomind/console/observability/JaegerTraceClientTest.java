package com.echomind.console.observability;

import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JaegerTraceClientTest {

    @Test
    void returnsDisabledConfigWhenJaegerIsNotConfigured() {
        ObservabilityProperties properties = new ObservabilityProperties();
        JaegerTraceClient client = new JaegerTraceClient(properties, new ObjectMapper());

        var config = client.config();

        assertThat(config.exporter().enabled()).isFalse();
        assertThat(config.exporter().type()).isEqualTo("none");
        assertThat(config.backend().enabled()).isFalse();
        assertThat(config.backend().type()).isEqualTo("jaeger");
        assertThat(config.message()).contains("Trace 导出未开启");
    }

    @Test
    void normalizesJaegerTraceDetail() throws IOException, InterruptedException {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(json("""
                    {
                      "data": [{
                        "traceID": "0123456789abcdef0123456789abcdef",
                        "spans": [
                          {
                            "spanID": "root",
                            "operationName": "POST /api/chat/sync",
                            "references": [],
                            "startTime": 900,
                            "duration": 400,
                            "processID": "p1",
                            "tags": [{"key":"otel.status_code","type":"string","value":"OK"}],
                            "logs": []
                          },
                          {
                            "spanID": "aaaa",
                            "operationName": "echomind.chat.sync",
                            "references": [{"refType":"CHILD_OF","traceID":"0123456789abcdef0123456789abcdef","spanID":"root"}],
                            "startTime": 1000,
                            "duration": 200,
                            "processID": "p1",
                            "tags": [
                              {"key":"echomind.user_id","type":"string","value":"user-a"},
                              {"key":"echomind.username","type":"string","value":"alice"},
                              {"key":"echomind.agent_id","type":"string","value":"agent-a"},
                              {"key":"echomind.session_id","type":"string","value":"session-a"},
                              {"key":"echomind.model_id","type":"string","value":"deepseek:deepseek-v4-flash"},
                              {"key":"echomind.prompt_tokens","type":"int64","value":12},
                              {"key":"echomind.completion_tokens","type":"int64","value":4},
                              {"key":"echomind.total_tokens","type":"int64","value":16},
                              {"key":"echomind.usage_source","type":"string","value":"PROVIDER"}
                            ],
                            "logs": []
                          },
                          {
                            "spanID": "bbbb",
                            "operationName": "echomind.llm.invoke",
                            "references": [{"refType":"CHILD_OF","traceID":"0123456789abcdef0123456789abcdef","spanID":"aaaa"}],
                            "startTime": 1100,
                            "duration": 50,
                            "processID": "p1",
                            "tags": [{"key":"llm.model","type":"string","value":"mock-model"}],
                            "logs": []
                          }
                        ],
                        "processes": {
                          "p1": {
                            "serviceName": "echomind",
                            "tags": [{"key":"deployment.environment","type":"string","value":"test"}]
                          }
                        }
                      }]
                    }
                    """));

            ObservabilityProperties properties = new ObservabilityProperties();
            properties.getJaeger().setEnabled(true);
            properties.getJaeger().setQueryUrl(server.url("/").toString());
            properties.getJaeger().setPublicUrl("http://jaeger.example");
            JaegerTraceClient client = new JaegerTraceClient(properties, new ObjectMapper());

            var response = client.getTrace("0123456789abcdef0123456789abcdef");

            assertThat(response.trace().traceId()).isEqualTo("0123456789abcdef0123456789abcdef");
            assertThat(response.trace().operationName()).isEqualTo("echomind.chat.sync");
            assertThat(response.trace().spans()).hasSize(3);
            assertThat(response.trace().durationMicros()).isEqualTo(400);
            assertThat(response.trace().externalUrl()).isEqualTo("http://jaeger.example/trace/0123456789abcdef0123456789abcdef");
            assertThat(response.trace().fields().userId()).isEqualTo("user-a");
            assertThat(response.trace().fields().modelId()).isEqualTo("deepseek:deepseek-v4-flash");
            assertThat(response.trace().fields().totalTokens()).isEqualTo(16);
            assertThat(response.trace().spans().get(1).fields().promptTokens()).isEqualTo(12);
            assertThat(response.trace().spans().get(2).parentSpanId()).isEqualTo("aaaa");
            assertThat(server.takeRequest().getPath()).isEqualTo("/api/traces/0123456789abcdef0123456789abcdef");
        }
    }

    @Test
    void searchDefaultsToBusinessTraceScope() throws IOException, InterruptedException {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(json(traceSearchResponse("11111111111111111111111111111111", "echomind.chat.sync", 1_000)));
            enqueueEmptyBusinessResponses(server, 1);

            JaegerTraceClient client = new JaegerTraceClient(enabledProperties(server), new ObjectMapper());

            var response = client.search(10, "24h", null, null);

            assertThat(response.traces()).hasSize(1);
            assertThat(response.traces().get(0).operationName()).isEqualTo("echomind.chat.sync");
            assertThat(response.traces().get(0).spanCount()).isEqualTo(2);
            assertThat(server.takeRequest().getPath()).contains("operation=echomind.chat.sync");
            assertThat(server.takeRequest().getPath()).contains("operation=echomind.chat.submit");
            assertThat(server.takeRequest().getPath()).contains("operation=echomind.chat.stream.consume");
        }
    }

    @Test
    void searchAllTracesLeavesOperationUnset() throws IOException, InterruptedException {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(json(traceSearchResponse("22222222222222222222222222222222", "GET /api/observability/traces", 2_000)));

            JaegerTraceClient client = new JaegerTraceClient(enabledProperties(server), new ObjectMapper());

            var response = client.search(5, "1h", "all", null);

            assertThat(response.traces()).hasSize(1);
            assertThat(response.traces().get(0).operationName()).isEqualTo("GET /api/observability/traces");
            assertThat(server.takeRequest().getPath()).doesNotContain("operation=");
        }
    }

    @Test
    void searchAddsUserIdTagsWhenProvided() throws IOException, InterruptedException {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(json(traceSearchResponse("33333333333333333333333333333333", "echomind.chat.sync", 3_000)));
            enqueueEmptyBusinessResponses(server, 1);

            JaegerTraceClient client = new JaegerTraceClient(enabledProperties(server), new ObjectMapper());

            var response = client.search(5, "24h", "business", "user-a");

            assertThat(response.traces()).hasSize(1);
            assertThat(server.takeRequest().getPath()).contains("tags=").contains("echomind.user_id").contains("user-a");
        }
    }

    @Test
    void mapsJaegerNotFoundToTraceNotFound() throws IOException {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse()
                .setResponseCode(404)
                .setHeader("Content-Type", "application/json")
                .setBody("""
                    {"data":null,"errors":[{"code":404,"msg":"trace not found"}]}
                    """));

            JaegerTraceClient client = new JaegerTraceClient(enabledProperties(server), new ObjectMapper());

            assertThatThrownBy(() -> client.getTrace("44444444444444444444444444444444"))
                .isInstanceOf(JaegerTraceClient.TraceNotFoundException.class)
                .hasMessageContaining("44444444444444444444444444444444");
        }
    }

    private ObservabilityProperties enabledProperties(MockWebServer server) {
        ObservabilityProperties properties = new ObservabilityProperties();
        properties.getJaeger().setEnabled(true);
        properties.getJaeger().setQueryUrl(server.url("/").toString());
        properties.getJaeger().setPublicUrl("http://jaeger.example");
        return properties;
    }

    private MockResponse json(String body) {
        return new MockResponse()
            .setHeader("Content-Type", "application/json")
            .setBody(body);
    }

    private void enqueueEmptyBusinessResponses(MockWebServer server, int alreadyQueued) {
        for (int i = alreadyQueued; i < 11; i++) {
            server.enqueue(json(emptyTraceSearchResponse()));
        }
    }

    private String emptyTraceSearchResponse() {
        return "{\"data\":[]}";
    }

    private String traceSearchResponse(String traceId, String operationName, long startTime) {
        return """
            {
              "data": [{
                "traceID": "%s",
                "spans": [
                  {
                    "spanID": "root",
                    "operationName": "%s",
                    "references": [],
                    "startTime": %d,
                    "duration": 300,
                    "processID": "p1",
                    "tags": [],
                    "logs": []
                  },
                  {
                    "spanID": "child",
                    "operationName": "echomind.llm.invoke",
                    "references": [{"refType":"CHILD_OF","traceID":"%s","spanID":"root"}],
                    "startTime": %d,
                    "duration": 100,
                    "processID": "p1",
                    "tags": [],
                    "logs": []
                  }
                ],
                "processes": {
                  "p1": {
                    "serviceName": "echomind",
                    "tags": []
                  }
                }
              }]
            }
            """.formatted(traceId, operationName, startTime, traceId, startTime + 100);
    }
}
