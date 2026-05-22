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
        String externalUrl
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
        Map<String, Object> tags,
        List<TraceLog> logs
    ) {
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
