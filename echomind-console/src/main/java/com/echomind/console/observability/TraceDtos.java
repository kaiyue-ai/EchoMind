package com.echomind.console.observability;

import java.util.List;
import java.util.Map;

public final class TraceDtos {
    private TraceDtos() {
    }

    public record TraceExporter(
        String type,
        boolean enabled,
        String endpoint,
        String protocol,
        String serviceName
    ) {
    }

    public record TraceBackend(
        String type,
        boolean enabled,
        String serviceName,
        String queryUrl,
        String publicUrl
    ) {
    }

    public record TraceConfigResponse(
        TraceExporter exporter,
        TraceBackend backend,
        String message
    ) {
    }

    public record TraceListResponse(
        TraceBackend backend,
        List<TraceSummary> traces
    ) {
    }

    public record TraceDetailResponse(
        TraceBackend backend,
        TraceDetail trace
    ) {
    }

    public record TraceSummary(
        String traceId,
        String operationName,
        String serviceName,
        long startTimeMicros,
        long durationMicros,
        int spanCount,
        boolean hasError,
        String externalUrl,
        TraceFields fields
    ) {
    }

    public record TraceDetail(
        String traceId,
        String operationName,
        String serviceName,
        long startTimeMicros,
        long durationMicros,
        boolean hasError,
        String externalUrl,
        TraceFields fields,
        List<TraceSpan> spans,
        List<TraceProcess> processes
    ) {
    }

    public record TraceSpan(
        String spanId,
        String parentSpanId,
        String operationName,
        String serviceName,
        long startTimeMicros,
        long durationMicros,
        String status,
        boolean hasError,
        TraceFields fields,
        Map<String, Object> tags,
        List<TraceLog> logs
    ) {
    }

    public record TraceFields(
        String userId,
        String username,
        String accountType,
        String agentId,
        String sessionId,
        String modelId,
        Long promptTokens,
        Long completionTokens,
        Long totalTokens,
        String usageSource
    ) {
        public boolean hasAny() {
            return hasText(userId)
                || hasText(username)
                || hasText(accountType)
                || hasText(agentId)
                || hasText(sessionId)
                || hasText(modelId)
                || promptTokens != null
                || completionTokens != null
                || totalTokens != null
                || hasText(usageSource);
        }

        private boolean hasText(String value) {
            return value != null && !value.isBlank();
        }
    }

    public record TraceLog(
        long timestampMicros,
        Map<String, Object> fields
    ) {
    }

    public record TraceProcess(
        String processId,
        String serviceName,
        Map<String, Object> tags
    ) {
    }
}
