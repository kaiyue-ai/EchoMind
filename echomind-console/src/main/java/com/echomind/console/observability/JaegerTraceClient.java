package com.echomind.console.observability;

import com.echomind.console.observability.TraceDtos.TraceBackend;
import com.echomind.console.observability.TraceDtos.TraceConfigResponse;
import com.echomind.console.observability.TraceDtos.TraceDetail;
import com.echomind.console.observability.TraceDtos.TraceDetailResponse;
import com.echomind.console.observability.TraceDtos.TraceExporter;
import com.echomind.console.observability.TraceDtos.TraceFields;
import com.echomind.console.observability.TraceDtos.TraceListResponse;
import com.echomind.console.observability.TraceDtos.TraceLog;
import com.echomind.console.observability.TraceDtos.TraceProcess;
import com.echomind.console.observability.TraceDtos.TraceSpan;
import com.echomind.console.observability.TraceDtos.TraceSummary;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class JaegerTraceClient {

    private static final String TRACE_SCOPE_ALL = "all";
    private static final String TRACE_SCOPE_BUSINESS = "business";
    private static final List<String> BUSINESS_OPERATIONS = List.of(
        "echomind.chat.sync",
        "echomind.chat.submit",
        "echomind.chat.stream.consume",
        "echomind.team.planner",
        "echomind.team.reviewer",
        "echomind.team.executor",
        "echomind.team.sub_reviewer",
        "echomind.team.merge",
        "echomind.team.conflict_detector",
        "echomind.team.arbitration",
        "echomind.team.repair"
    );

    private final ObservabilityProperties properties;
    private final ObjectMapper objectMapper;

    public TraceConfigResponse config() {
        TraceExporter exporter = exporter();
        TraceBackend backend = backend();
        String message;
        if (!exporter.enabled()) {
            message = "Trace 导出未开启，设置 OTEL_TRACES_EXPORTER=otlp 并把 OTEL_EXPORTER_OTLP_ENDPOINT 指向 OpenTelemetry Collector";
        } else if (!backend.enabled()) {
            message = "Trace 已导出到 OpenTelemetry Collector，但未配置查询后端，管理端暂时不能检索 Span";
        } else {
            message = "Trace 导出和查询后端已接入";
        }
        return new TraceConfigResponse(exporter, backend, message);
    }

    public TraceListResponse search(Integer limit, String lookback, String scope, String userId) {
        TraceBackend backend = backend();
        ensureEnabled(backend);
        int safeLimit = sanitizeLimit(limit);
        String safeLookback = sanitizeLookback(lookback);
        String safeScope = sanitizeScope(scope);
        Map<String, String> tags = traceTags(userId);
        List<TraceSummary> traces = TRACE_SCOPE_ALL.equals(safeScope)
            ? querySummaries(backend, safeLimit, safeLookback, null, tags)
            : queryBusinessSummaries(backend, safeLimit, safeLookback, tags);
        traces.sort(Comparator.comparingLong(TraceSummary::startTimeMicros).reversed());
        if (traces.size() > safeLimit) {
            traces = new ArrayList<>(traces.subList(0, safeLimit));
        }
        return new TraceListResponse(backend, traces);
    }

    private List<TraceSummary> queryBusinessSummaries(TraceBackend backend, int safeLimit, String safeLookback,
                                                      Map<String, String> tags) {
        Map<String, TraceSummary> byTraceId = new LinkedHashMap<>();
        for (String operation : BUSINESS_OPERATIONS) {
            for (TraceSummary trace : querySummaries(backend, safeLimit, safeLookback, operation, tags)) {
                byTraceId.putIfAbsent(trace.traceId(), trace);
            }
        }
        return new ArrayList<>(byTraceId.values());
    }

    private List<TraceSummary> querySummaries(TraceBackend backend, int safeLimit, String safeLookback, String operation,
                                             Map<String, String> tags) {
        HttpUrl url = baseUrl(backend.queryUrl()).newBuilder()
            .addPathSegments("api/traces")
            .addQueryParameter("service", backend.serviceName())
            .addQueryParameter("limit", String.valueOf(safeLimit))
            .addQueryParameter("lookback", safeLookback)
            .build();
        if (operation != null && !operation.isBlank()) {
            url = url.newBuilder()
                .addQueryParameter("operation", operation)
                .build();
        }
        if (tags != null && !tags.isEmpty()) {
            url = url.newBuilder()
                .addQueryParameter("tags", tagsJson(tags))
                .build();
        }

        JsonNode root = execute(url);
        List<TraceSummary> traces = new ArrayList<>();
        for (JsonNode traceNode : root.path("data")) {
            toDetail(traceNode, backend).ifPresent(detail -> traces.add(summary(detail)));
        }
        return traces;
    }

    public TraceDetailResponse getTrace(String traceId) {
        String safeTraceId = normalizeTraceId(traceId);
        TraceBackend backend = backend();
        ensureEnabled(backend);
        HttpUrl url = baseUrl(backend.queryUrl()).newBuilder()
            .addPathSegments("api/traces")
            .addPathSegment(safeTraceId)
            .build();

        JsonNode root = execute(url);
        JsonNode data = root.path("data");
        if (!data.isArray() || data.isEmpty()) {
            throw new TraceNotFoundException(safeTraceId);
        }
        TraceDetail trace = toDetail(data.get(0), backend).orElseThrow(() -> new TraceNotFoundException(safeTraceId));
        return new TraceDetailResponse(backend, trace);
    }

    private TraceBackend backend() {
        ObservabilityProperties.Jaeger jaeger = properties.getJaeger();
        String queryUrl = trimTrailingSlash(jaeger.getQueryUrl());
        String publicUrl = trimTrailingSlash(firstNonBlank(jaeger.getPublicUrl(), queryUrl));
        boolean enabled = jaeger.isEnabled() && !queryUrl.isBlank();
        return new TraceBackend("jaeger", enabled, nonBlank(jaeger.getServiceName(), "echomind"), queryUrl, publicUrl);
    }

    private TraceExporter exporter() {
        ObservabilityProperties.Exporter exporter = properties.getExporter();
        String type = nonBlank(exporter.getTracesExporter(), "none").trim().toLowerCase();
        boolean enabled = !"none".equals(type);
        return new TraceExporter(
            type,
            enabled,
            trimTrailingSlash(exporter.getOtlpEndpoint()),
            nonBlank(exporter.getOtlpProtocol(), "http/protobuf"),
            nonBlank(exporter.getServiceName(), "echomind")
        );
    }

    private Optional<TraceDetail> toDetail(JsonNode traceNode, TraceBackend backend) {
        String traceId = traceNode.path("traceID").asText("");
        if (traceId.isBlank()) {
            return Optional.empty();
        }
        Map<String, String> processServices = new LinkedHashMap<>();
        List<TraceProcess> processes = new ArrayList<>();
        traceNode.path("processes").fields().forEachRemaining(entry -> {
            JsonNode process = entry.getValue();
            String serviceName = process.path("serviceName").asText("");
            processServices.put(entry.getKey(), serviceName);
            processes.add(new TraceProcess(entry.getKey(), serviceName, tags(process.path("tags"))));
        });

        List<TraceSpan> spans = new ArrayList<>();
        for (JsonNode spanNode : traceNode.path("spans")) {
            spans.add(toSpan(spanNode, processServices));
        }
        spans.sort(Comparator.comparingLong(TraceSpan::startTimeMicros));

        long start = spans.stream().mapToLong(TraceSpan::startTimeMicros).min().orElse(0L);
        long end = spans.stream()
            .mapToLong(span -> span.startTimeMicros() + span.durationMicros())
            .max()
            .orElse(start);
        TraceSpan rootSpan = spans.stream()
            .filter(span -> span.parentSpanId() == null || span.parentSpanId().isBlank())
            .findFirst()
            .orElse(spans.isEmpty() ? null : spans.get(0));
        TraceSpan displaySpan = spans.stream()
            .filter(span -> isBusinessOperation(span.operationName()))
            .findFirst()
            .orElse(rootSpan);
        String operationName = displaySpan == null ? "" : displaySpan.operationName();
        String serviceName = displaySpan == null ? backend.serviceName() : displaySpan.serviceName();
        boolean hasError = spans.stream().anyMatch(TraceSpan::hasError);
        TraceFields fields = traceFields(spans, displaySpan);
        return Optional.of(new TraceDetail(
            traceId,
            operationName,
            serviceName,
            start,
            Math.max(0L, end - start),
            hasError,
            externalTraceUrl(backend.publicUrl(), traceId),
            fields,
            spans,
            processes
        ));
    }

    private TraceSummary summary(TraceDetail detail) {
        return new TraceSummary(
            detail.traceId(),
            detail.operationName(),
            detail.serviceName(),
            detail.startTimeMicros(),
            detail.durationMicros(),
            detail.spans().size(),
            detail.hasError(),
            detail.externalUrl(),
            detail.fields()
        );
    }

    private TraceSpan toSpan(JsonNode spanNode, Map<String, String> processServices) {
        Map<String, Object> spanTags = tags(spanNode.path("tags"));
        String processId = spanNode.path("processID").asText("");
        String parentSpanId = "";
        for (JsonNode ref : spanNode.path("references")) {
            String refType = ref.path("refType").asText("");
            if ("CHILD_OF".equals(refType)) {
                parentSpanId = ref.path("spanID").asText("");
                break;
            }
        }
        boolean hasError = Boolean.TRUE.equals(spanTags.get("error"))
            || "ERROR".equalsIgnoreCase(String.valueOf(spanTags.getOrDefault("otel.status_code", "")));
        String status = hasError ? "ERROR" : String.valueOf(spanTags.getOrDefault("otel.status_code", "OK"));
        return new TraceSpan(
            spanNode.path("spanID").asText(""),
            parentSpanId,
            spanNode.path("operationName").asText(""),
            processServices.getOrDefault(processId, processId),
            spanNode.path("startTime").asLong(0L),
            spanNode.path("duration").asLong(0L),
            status,
            hasError,
            fields(spanTags),
            spanTags,
            logs(spanNode.path("logs"))
        );
    }

    private TraceFields traceFields(List<TraceSpan> spans, TraceSpan displaySpan) {
        TraceFields displayFields = displaySpan == null ? emptyFields() : displaySpan.fields();
        TraceFields businessUsage = spans.stream()
            .filter(span -> isBusinessOperation(span.operationName()))
            .map(TraceSpan::fields)
            .filter(fields -> fields != null && fields.totalTokens() != null)
            .findFirst()
            .orElse(null);
        if (businessUsage != null) {
            return mergeFields(businessUsage, displayFields);
        }
        TraceFields firstUsage = spans.stream()
            .map(TraceSpan::fields)
            .filter(fields -> fields != null && fields.totalTokens() != null)
            .findFirst()
            .orElse(null);
        if (firstUsage != null) {
            return mergeFields(firstUsage, displayFields);
        }
        return displayFields == null ? emptyFields() : displayFields;
    }

    private TraceFields fields(Map<String, Object> tags) {
        if (tags == null || tags.isEmpty()) {
            return emptyFields();
        }
        return new TraceFields(
            string(tags, "echomind.user_id"),
            string(tags, "echomind.username"),
            string(tags, "echomind.account_type"),
            string(tags, "echomind.agent_id"),
            string(tags, "echomind.session_id"),
            firstString(tags, "echomind.model_id", "llm.model"),
            number(tags, "echomind.prompt_tokens"),
            number(tags, "echomind.completion_tokens"),
            number(tags, "echomind.total_tokens"),
            string(tags, "echomind.usage_source")
        );
    }

    private TraceFields mergeFields(TraceFields primary, TraceFields fallback) {
        if (primary == null) {
            return fallback == null ? emptyFields() : fallback;
        }
        if (fallback == null) {
            return primary;
        }
        return new TraceFields(
            firstNonBlank(primary.userId(), fallback.userId()),
            firstNonBlank(primary.username(), fallback.username()),
            firstNonBlank(primary.accountType(), fallback.accountType()),
            firstNonBlank(primary.agentId(), fallback.agentId()),
            firstNonBlank(primary.sessionId(), fallback.sessionId()),
            firstNonBlank(primary.modelId(), fallback.modelId()),
            primary.promptTokens() == null ? fallback.promptTokens() : primary.promptTokens(),
            primary.completionTokens() == null ? fallback.completionTokens() : primary.completionTokens(),
            primary.totalTokens() == null ? fallback.totalTokens() : primary.totalTokens(),
            firstNonBlank(primary.usageSource(), fallback.usageSource())
        );
    }

    private TraceFields emptyFields() {
        return new TraceFields(null, null, null, null, null, null, null, null, null, null);
    }

    private Map<String, Object> tags(JsonNode nodes) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (!nodes.isArray()) {
            return result;
        }
        for (JsonNode node : nodes) {
            String key = node.path("key").asText("");
            if (!key.isBlank()) {
                result.put(key, value(node));
            }
        }
        return result;
    }

    private List<TraceLog> logs(JsonNode nodes) {
        List<TraceLog> result = new ArrayList<>();
        if (!nodes.isArray()) {
            return result;
        }
        for (JsonNode node : nodes) {
            result.add(new TraceLog(node.path("timestamp").asLong(0L), tags(node.path("fields"))));
        }
        return result;
    }

    private Object value(JsonNode node) {
        JsonNode value = node.path("value");
        if (value.isBoolean()) {
            return value.asBoolean();
        }
        if (value.isIntegralNumber()) {
            return value.asLong();
        }
        if (value.isFloatingPointNumber()) {
            return value.asDouble();
        }
        return value.asText("");
    }

    private String firstString(Map<String, Object> tags, String first, String second) {
        return firstNonBlank(string(tags, first), string(tags, second));
    }

    private String string(Map<String, Object> tags, String key) {
        Object value = tags.get(key);
        if (value == null) {
            return "";
        }
        String text = String.valueOf(value);
        return "null".equals(text) ? "" : text;
    }

    private Long number(Map<String, Object> tags, String key) {
        Object value = tags.get(key);
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private JsonNode execute(HttpUrl url) {
        OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(Duration.ofSeconds(timeoutSeconds()))
            .readTimeout(Duration.ofSeconds(timeoutSeconds()))
            .callTimeout(Duration.ofSeconds(Math.max(1, timeoutSeconds() + 1)))
            .build();
        Request request = new Request.Builder().url(url).get().build();
        try (Response response = client.newCall(request).execute()) {
            String body = response.body() == null ? "" : response.body().string();
            if (!response.isSuccessful()) {
                if (response.code() == 404) {
                    throw new TraceNotFoundException(traceIdFromUrl(url));
                }
                throw new TraceBackendException("Trace 查询后端请求失败: HTTP " + response.code());
            }
            return objectMapper.readTree(body);
        } catch (IOException e) {
            throw new TraceBackendException("Trace 查询后端请求失败: " + e.getMessage(), e);
        }
    }

    private HttpUrl baseUrl(String url) {
        HttpUrl parsed = HttpUrl.parse(url);
        if (parsed == null) {
            throw new TraceBackendException("Trace 查询后端 URL 不合法");
        }
        return parsed;
    }

    private void ensureEnabled(TraceBackend backend) {
        if (!backend.enabled()) {
            throw new TraceBackendException("Trace 查询后端未配置");
        }
    }

    private String normalizeTraceId(String traceId) {
        String normalized = traceId == null ? "" : traceId.trim().toLowerCase();
        if (!normalized.matches("[0-9a-f]{16}|[0-9a-f]{32}")) {
            throw new IllegalArgumentException("TraceID 格式不合法");
        }
        return normalized;
    }

    private String traceIdFromUrl(HttpUrl url) {
        List<String> segments = url.pathSegments();
        if (segments.isEmpty()) {
            return "";
        }
        String candidate = segments.get(segments.size() - 1);
        return candidate.matches("[0-9a-fA-F]{16}|[0-9a-fA-F]{32}") ? candidate.toLowerCase() : "";
    }

    private int sanitizeLimit(Integer limit) {
        int value = limit == null ? properties.getJaeger().getDefaultLimit() : limit;
        return Math.max(1, Math.min(100, value));
    }

    private String sanitizeLookback(String lookback) {
        if (lookback == null || lookback.isBlank()) {
            return "1h";
        }
        return lookback.matches("\\d+[hdm]") ? lookback : "1h";
    }

    private String sanitizeScope(String scope) {
        if (scope == null || scope.isBlank()) {
            return TRACE_SCOPE_BUSINESS;
        }
        String normalized = scope.trim().toLowerCase();
        return TRACE_SCOPE_ALL.equals(normalized) ? TRACE_SCOPE_ALL : TRACE_SCOPE_BUSINESS;
    }

    private boolean isBusinessOperation(String operationName) {
        return BUSINESS_OPERATIONS.contains(operationName);
    }

    private Map<String, String> traceTags(String userId) {
        if (userId == null || userId.isBlank()) {
            return Map.of();
        }
        return Map.of("echomind.user_id", userId.trim());
    }

    private String tagsJson(Map<String, String> tags) {
        try {
            return objectMapper.writeValueAsString(tags);
        } catch (Exception e) {
            throw new TraceBackendException("Trace 标签参数序列化失败", e);
        }
    }

    private int timeoutSeconds() {
        return Math.max(1, Math.min(30, properties.getJaeger().getTimeoutSeconds()));
    }

    private String externalTraceUrl(String publicUrl, String traceId) {
        if (publicUrl == null || publicUrl.isBlank()) {
            return "";
        }
        return trimTrailingSlash(publicUrl) + "/trace/" + traceId;
    }

    private String trimTrailingSlash(String value) {
        String normalized = value == null ? "" : value.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : second;
    }

    private String nonBlank(String value, String fallback) {
        return value != null && !value.isBlank() ? value : fallback;
    }

    public static class TraceBackendException extends RuntimeException {
        public TraceBackendException(String message) {
            super(message);
        }

        public TraceBackendException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public static class TraceNotFoundException extends RuntimeException {
        public TraceNotFoundException(String traceId) {
            super("Trace 不存在: " + traceId);
        }
    }
}
