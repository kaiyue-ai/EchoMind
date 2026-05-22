package com.echomind.common.observability;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.context.propagation.TextMapSetter;

import java.security.SecureRandom;
import java.util.Map;
import java.util.concurrent.Callable;
// TODO 还没读
/** OpenTelemetry helper for EchoMind business spans. */
public final class EchoMindTrace {

    public static final String INSTRUMENTATION_NAME = "com.echomind";

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final ThreadLocal<String> FALLBACK_TRACE_ID = new ThreadLocal<>();
    private static volatile OpenTelemetry openTelemetry = OpenTelemetry.noop();

    private EchoMindTrace() {
    }

    public static Tracer tracer() {
        return openTelemetry.getTracer(INSTRUMENTATION_NAME);
    }

    public static void setOpenTelemetry(OpenTelemetry openTelemetry) {
        if (openTelemetry != null) {
            EchoMindTrace.openTelemetry = openTelemetry;
        }
    }

    public static Span startSpan(String name) {
        return tracer().spanBuilder(name).startSpan();
    }

    public static Span startSpan(String name, Context parent) {
        if (parent == null) {
            return startSpan(name);
        }
        return tracer().spanBuilder(name).setParent(parent).startSpan();
    }

    public static Span currentSpan() {
        return Span.current();
    }

    public static String currentTraceId() {
        Span span = currentSpan();
        if (span.getSpanContext().isValid()) {
            return span.getSpanContext().getTraceId();
        }
        String fallback = FALLBACK_TRACE_ID.get();
        return fallback == null ? "" : fallback;
    }

    public static String traceId(Span span) {
        if (span != null && span.getSpanContext().isValid()) {
            return span.getSpanContext().getTraceId();
        }
        String fallback = FALLBACK_TRACE_ID.get();
        if (fallback == null || fallback.isBlank()) {
            fallback = newTraceId();
            FALLBACK_TRACE_ID.set(fallback);
        }
        return fallback;
    }

    public static void clearFallbackTraceId() {
        FALLBACK_TRACE_ID.remove();
    }

    public static Map<String, String> injectContext() {
        Map<String, String> carrier = new java.util.LinkedHashMap<>();
        ContextPropagators propagators = openTelemetry.getPropagators();
        propagators.getTextMapPropagator().inject(Context.current(), carrier, MapSetter.INSTANCE);
        if (!carrier.containsKey("traceparent")) {
            carrier.put("traceparent", fallbackTraceparent());
        }
        return carrier;
    }

    public static Context extractContext(Map<String, String> carrier) {
        if (carrier == null || carrier.isEmpty()) {
            return Context.current();
        }
        ContextPropagators propagators = openTelemetry.getPropagators();
        return propagators.getTextMapPropagator().extract(Context.current(), carrier, MapGetter.INSTANCE);
    }

    public static void end(Span span) {
        if (span != null) {
            span.end();
        }
    }

    public static void recordException(Span span, Throwable throwable) {
        if (span == null || throwable == null) {
            return;
        }
        span.recordException(throwable);
        span.setStatus(StatusCode.ERROR, throwable.getMessage() == null ? throwable.getClass().getSimpleName() : throwable.getMessage());
    }

    public static <T> T inSpan(String name, Callable<T> action) {
        Span span = startSpan(name);
        try (Scope ignored = span.makeCurrent()) {
            return action.call();
        } catch (RuntimeException e) {
            recordException(span, e);
            throw e;
        } catch (Exception e) {
            recordException(span, e);
            throw new IllegalStateException(e);
        } finally {
            span.end();
        }
    }

    public static void inSpan(String name, Runnable action) {
        Span span = startSpan(name);
        try (Scope ignored = span.makeCurrent()) {
            action.run();
        } catch (RuntimeException e) {
            recordException(span, e);
            throw e;
        } finally {
            span.end();
        }
    }

    private static String newTraceId() {
        return randomHex(16);
    }

    private static String newSpanId() {
        return randomHex(8);
    }

    private static String randomHex(int byteCount) {
        byte[] bytes = new byte[byteCount];
        RANDOM.nextBytes(bytes);
        char[] chars = new char[byteCount * 2];
        char[] hex = "0123456789abcdef".toCharArray();
        for (int i = 0; i < bytes.length; i++) {
            int value = bytes[i] & 0xff;
            chars[i * 2] = hex[value >>> 4];
            chars[i * 2 + 1] = hex[value & 0x0f];
        }
        return new String(chars);
    }

    private static String fallbackTraceparent() {
        String traceId = traceId(currentSpan());
        return "00-" + traceId + "-" + newSpanId() + "-00";
    }

    private enum MapSetter implements TextMapSetter<Map<String, String>> {
        INSTANCE;

        @Override
        public void set(Map<String, String> carrier, String key, String value) {
            if (carrier != null && key != null && value != null) {
                carrier.put(key, value);
            }
        }
    }

    private enum MapGetter implements TextMapGetter<Map<String, String>> {
        INSTANCE;

        @Override
        public Iterable<String> keys(Map<String, String> carrier) {
            return carrier == null ? java.util.List.of() : carrier.keySet();
        }

        @Override
        public String get(Map<String, String> carrier, String key) {
            return carrier == null || key == null ? null : carrier.get(key);
        }
    }
}
