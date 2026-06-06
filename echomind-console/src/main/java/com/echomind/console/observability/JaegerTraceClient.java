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

/**
 * Jaeger Trace 查询客户端
 *
 * <p>负责与 Jaeger 后端交互，检索分布式追踪数据并提取业务关键字段。
 * 支持按用户、时间范围、业务范围等条件搜索 Trace，为管理端提供可观测性支持。</p>
 */
@Service
@RequiredArgsConstructor
public class JaegerTraceClient {

    /** 搜索范围：全部 Trace */
    private static final String TRACE_SCOPE_ALL = "all";

    /** 搜索范围：仅业务操作 */
    private static final String TRACE_SCOPE_BUSINESS = "business";

    /**
     * 业务操作列表
     *
     * <p>这些操作被视为"业务级"操作，在搜索和展示时优先考虑。
     * 非业务操作包括框架层面的 HTTP 请求处理、数据库查询等基础设施操作。</p>
     */
    private static final List<String> BUSINESS_OPERATIONS = List.of(
        "echomind.chat.submit",           // 聊天请求提交
        "echomind.chat.stream.consume",    // 流式聊天消息消费
        "echomind.team.planner",          // 团队规划
        "echomind.team.reviewer",         // 团队审查
        "echomind.team.executor",         // 团队执行
        "echomind.team.sub_reviewer",     // 团队子审查
        "echomind.team.merge",            // 团队合并
        "echomind.team.conflict_detector", // 团队冲突检测
        "echomind.team.arbitration",      // 团队仲裁
        "echomind.team.repair"            // 团队修复
    );

    /** 可观测性配置属性 */
    private final ObservabilityProperties properties;

    /** JSON 对象映射器 */
    private final ObjectMapper objectMapper;

    /**
     * 获取 Trace 配置状态
     *
     * <p>检查 Trace 导出和查询后端的配置状态，返回友好的提示信息。</p>
     *
     * @return 配置响应，包含导出器和后端信息
     */
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

    /**
     * 搜索 Trace 列表
     *
     * @param limit    返回数量限制（1-100）
     * @param lookback 时间回溯范围（如 "1h", "30m", "1d"）
     * @param scope    搜索范围："all" 全部 / "business" 仅业务操作
     * @param userId   用户ID过滤（可选）
     * @return Trace 列表响应
     */
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

    /**
     * 查询业务操作的 Trace 摘要列表
     *
     * <p>遍历所有业务操作，查询对应的 Trace，去重后返回。</p>
     *
     * @param backend    后端配置
     * @param safeLimit  安全限制数量
     * @param safeLookback 安全时间范围
     * @param tags       过滤标签
     * @return Trace 摘要列表
     */
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

    /**
     * 查询 Trace 摘要列表（底层方法）
     *
     * @param backend    后端配置
     * @param safeLimit  安全限制数量
     * @param safeLookback 安全时间范围
     * @param operation  操作名称过滤（可选）
     * @param tags       过滤标签
     * @return Trace 摘要列表
     */
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

    /**
     * 根据 TraceID 获取 Trace 详情
     *
     * @param traceId TraceID
     * @return Trace 详情响应
     * @throws TraceNotFoundException 当 Trace 不存在时抛出
     */
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

    /**
     * 获取 Jaeger 后端配置
     *
     * @return 后端配置
     */
    private TraceBackend backend() {
        ObservabilityProperties.Jaeger jaeger = properties.getJaeger();
        String queryUrl = trimTrailingSlash(jaeger.getQueryUrl());
        String publicUrl = trimTrailingSlash(firstNonBlank(jaeger.getPublicUrl(), queryUrl));
        boolean enabled = jaeger.isEnabled() && !queryUrl.isBlank();
        return new TraceBackend("jaeger", enabled, nonBlank(jaeger.getServiceName(), "echomind"), queryUrl, publicUrl);
    }

    /**
     * 获取 Trace 导出器配置
     *
     * @return 导出器配置
     */
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

    /**
     * 将 Jaeger JSON 响应解析为 TraceDetail 对象
     *
     * @param traceNode JSON 节点
     * @param backend   后端配置
     * @return TraceDetail 对象（可选）
     */
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

    /**
     * 从 TraceDetail 创建 TraceSummary
     *
     * @param detail Trace详情
     * @return Trace摘要
     */
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

    /**
     * 将 Jaeger Span JSON 解析为 TraceSpan 对象
     *
     * @param spanNode        Span JSON 节点
     * @param processServices 进程服务映射
     * @return TraceSpan 对象
     */
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

    /**
     * 从 Span 列表中提取业务字段
     *
     * <p>优先从业务操作 Span 中提取字段，其次从第一个有 Token 用量的 Span 中提取。</p>
     *
     * @param spans        Span 列表
     * @param displaySpan  展示用 Span
     * @return 业务字段
     */
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

    /**
     * 从 Span 标签中提取业务字段
     *
     * @param tags Span 标签
     * @return 业务字段
     */
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

    /**
     * 合并两个业务字段对象
     *
     * <p>primary 字段优先，fallback 作为后备。</p>
     *
     * @param primary   主字段
     * @param fallback  后备字段
     * @return 合并后的字段
     */
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

    /**
     * 创建空的业务字段对象
     *
     * @return 空字段对象
     */
    private TraceFields emptyFields() {
        return new TraceFields(null, null, null, null, null, null, null, null, null, null);
    }

    /**
     * 将 JSON 标签数组转换为 Map
     *
     * @param nodes JSON 节点数组
     * @return 标签 Map
     */
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

    /**
     * 将 JSON 日志数组转换为 TraceLog 列表
     *
     * @param nodes JSON 节点数组
     * @return 日志列表
     */
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

    /**
     * 解析 JSON 值节点
     *
     * @param node JSON 节点
     * @return 解析后的值
     */
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

    /**
     * 获取两个标签值中的第一个非空值
     *
     * @param tags   标签 Map
     * @param first  第一个键
     * @param second 第二个键
     * @return 非空值
     */
    private String firstString(Map<String, Object> tags, String first, String second) {
        return firstNonBlank(string(tags, first), string(tags, second));
    }

    /**
     * 从标签中获取字符串值
     *
     * @param tags 标签 Map
     * @param key  键
     * @return 字符串值
     */
    private String string(Map<String, Object> tags, String key) {
        Object value = tags.get(key);
        if (value == null) {
            return "";
        }
        String text = String.valueOf(value);
        return "null".equals(text) ? "" : text;
    }

    /**
     * 从标签中获取长整数值
     *
     * @param tags 标签 Map
     * @param key  键
     * @return 长整数值
     */
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

    /**
     * 执行 HTTP 请求
     *
     * @param url 请求 URL
     * @return JSON 响应
     */
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

    /**
     * 解析基础 URL
     *
     * @param url URL 字符串
     * @return HttpUrl 对象
     */
    private HttpUrl baseUrl(String url) {
        HttpUrl parsed = HttpUrl.parse(url);
        if (parsed == null) {
            throw new TraceBackendException("Trace 查询后端 URL 不合法");
        }
        return parsed;
    }

    /**
     * 确保后端已启用
     *
     * @param backend 后端配置
     */
    private void ensureEnabled(TraceBackend backend) {
        if (!backend.enabled()) {
            throw new TraceBackendException("Trace 查询后端未配置");
        }
    }

    /**
     * 规范化 TraceID
     *
     * @param traceId TraceID
     * @return 规范化后的 TraceID
     */
    private String normalizeTraceId(String traceId) {
        String normalized = traceId == null ? "" : traceId.trim().toLowerCase();
        if (!normalized.matches("[0-9a-f]{16}|[0-9a-f]{32}")) {
            throw new IllegalArgumentException("TraceID 格式不合法");
        }
        return normalized;
    }

    /**
     * 从 URL 中提取 TraceID
     *
     * @param url URL
     * @return TraceID
     */
    private String traceIdFromUrl(HttpUrl url) {
        List<String> segments = url.pathSegments();
        if (segments.isEmpty()) {
            return "";
        }
        String candidate = segments.get(segments.size() - 1);
        return candidate.matches("[0-9a-fA-F]{16}|[0-9a-fA-F]{32}") ? candidate.toLowerCase() : "";
    }

    /**
     * 安全化限制数量
     *
     * @param limit 限制数量
     * @return 安全化后的数量（1-100）
     */
    private int sanitizeLimit(Integer limit) {
        int value = limit == null ? properties.getJaeger().getDefaultLimit() : limit;
        return Math.max(1, Math.min(100, value));
    }

    /**
     * 安全化时间回溯范围
     *
     * @param lookback 时间范围
     * @return 安全化后的时间范围
     */
    private String sanitizeLookback(String lookback) {
        if (lookback == null || lookback.isBlank()) {
            return "1h";
        }
        return lookback.matches("\\d+[hdm]") ? lookback : "1h";
    }

    /**
     * 安全化搜索范围
     *
     * @param scope 搜索范围
     * @return 安全化后的搜索范围
     */
    private String sanitizeScope(String scope) {
        if (scope == null || scope.isBlank()) {
            return TRACE_SCOPE_BUSINESS;
        }
        String normalized = scope.trim().toLowerCase();
        return TRACE_SCOPE_ALL.equals(normalized) ? TRACE_SCOPE_ALL : TRACE_SCOPE_BUSINESS;
    }

    /**
     * 判断是否为业务操作
     *
     * @param operationName 操作名称
     * @return 是否为业务操作
     */
    private boolean isBusinessOperation(String operationName) {
        return BUSINESS_OPERATIONS.contains(operationName);
    }

    /**
     * 构建 Trace 过滤标签
     *
     * @param userId 用户ID
     * @return 标签 Map
     */
    private Map<String, String> traceTags(String userId) {
        if (userId == null || userId.isBlank()) {
            return Map.of();
        }
        return Map.of("echomind.user_id", userId.trim());
    }

    /**
     * 将标签转换为 JSON 字符串
     *
     * @param tags 标签 Map
     * @return JSON 字符串
     */
    private String tagsJson(Map<String, String> tags) {
        try {
            return objectMapper.writeValueAsString(tags);
        } catch (Exception e) {
            throw new TraceBackendException("Trace 标签参数序列化失败", e);
        }
    }

    /**
     * 获取超时时间（秒）
     *
     * @return 超时时间
     */
    private int timeoutSeconds() {
        return Math.max(1, Math.min(30, properties.getJaeger().getTimeoutSeconds()));
    }

    /**
     * 构建外部 Trace URL
     *
     * @param publicUrl 公开 URL
     * @param traceId   TraceID
     * @return 外部 URL
     */
    private String externalTraceUrl(String publicUrl, String traceId) {
        if (publicUrl == null || publicUrl.isBlank()) {
            return "";
        }
        return trimTrailingSlash(publicUrl) + "/trace/" + traceId;
    }

    /**
     * 去除 URL 末尾的斜杠
     *
     * @param value URL
     * @return 去除斜杠后的 URL
     */
    private String trimTrailingSlash(String value) {
        String normalized = value == null ? "" : value.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    /**
     * 获取第一个非空字符串
     *
     * @param first  第一个字符串
     * @param second 第二个字符串
     * @return 非空字符串
     */
    private String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : second;
    }

    /**
     * 获取非空值或后备值
     *
     * @param value    值
     * @param fallback 后备值
     * @return 非空值
     */
    private String nonBlank(String value, String fallback) {
        return value != null && !value.isBlank() ? value : fallback;
    }

    /**
     * Trace 后端异常
     */
    public static class TraceBackendException extends RuntimeException {
        public TraceBackendException(String message) {
            super(message);
        }

        public TraceBackendException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * Trace 未找到异常
     */
    public static class TraceNotFoundException extends RuntimeException {
        public TraceNotFoundException(String traceId) {
            super("Trace 不存在: " + traceId);
        }
    }
}
