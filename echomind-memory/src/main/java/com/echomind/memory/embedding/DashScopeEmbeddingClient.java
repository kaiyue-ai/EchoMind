package com.echomind.memory.embedding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 阿里云百炼多模态向量客户端。
 *
 * <p>使用 DashScope 原生 multimodal-embedding 接口，而不是 OpenAI compatible 接口。
 * 用户指定的模型为 {@code tongyi-embedding-vision-plus}，当前记忆检索先用文本输入；
 * 后续图片语义检索可以在同一接口里补 image 字段。</p>
 */
@Slf4j
public class DashScopeEmbeddingClient implements EmbeddingClient {

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final OkHttpClient httpClient;
    private final ObjectMapper mapper;
    private final String baseUrl;
    private final String apiKey;
    private final String model;

    public DashScopeEmbeddingClient(String baseUrl, String apiKey, String model, ObjectMapper mapper) {
        this.httpClient = new OkHttpClient.Builder()
            .connectTimeout(Duration.ofSeconds(10))
            .readTimeout(Duration.ofSeconds(30))
            .writeTimeout(Duration.ofSeconds(10))
            .build();
        this.mapper = mapper;
        this.baseUrl = normalizeBaseUrl(baseUrl);
        this.apiKey = apiKey;
        this.model = model;
    }

    @Override
    public Optional<double[]> embed(String text) {
        if (apiKey == null || apiKey.isBlank() || text == null || text.isBlank()) {
            return Optional.empty();
        }
        try {
            Map<String, Object> payload = Map.of(
                "model", model,
                "input", Map.of(
                    "contents", List.of(Map.of("text", text))
                )
            );
            Request request = new Request.Builder()
                .url(baseUrl + "/api/v1/services/embeddings/multimodal-embedding/multimodal-embedding")
                .addHeader("Authorization", "Bearer " + apiKey)
                .post(RequestBody.create(mapper.writeValueAsString(payload), JSON))
                .build();

            try (Response response = httpClient.newCall(request).execute()) {
                String body = response.body() == null ? "" : response.body().string();
                if (!response.isSuccessful()) {
                    log.warn("DashScope embedding failed: status={} body={}", response.code(), body);
                    return Optional.empty();
                }
                return parseEmbedding(body);
            }
        } catch (Exception e) {
            log.warn("DashScope embedding request failed: {}", e.getMessage());
            return Optional.empty();
        }
    }

    private Optional<double[]> parseEmbedding(String body) throws Exception {
        JsonNode embeddings = mapper.readTree(body).path("output").path("embeddings");
        if (!embeddings.isArray() || embeddings.isEmpty()) {
            return Optional.empty();
        }
        JsonNode values = embeddings.get(0).path("embedding");
        if (!values.isArray() || values.isEmpty()) {
            return Optional.empty();
        }
        List<Double> list = new ArrayList<>(values.size());
        for (JsonNode value : values) {
            list.add(value.asDouble());
        }
        double[] vector = new double[list.size()];
        for (int i = 0; i < list.size(); i++) {
            vector[i] = list.get(i);
        }
        return Optional.of(vector);
    }

    private String normalizeBaseUrl(String value) {
        String url = value == null || value.isBlank()
            ? "https://dashscope.aliyuncs.com"
            : value.trim();
        if (url.endsWith("/compatible-mode/v1")) {
            url = url.substring(0, url.length() - "/compatible-mode/v1".length());
        }
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        return url;
    }
}
