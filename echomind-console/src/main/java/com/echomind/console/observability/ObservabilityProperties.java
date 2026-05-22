package com.echomind.console.observability;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Console-facing observability settings.
 */
@ConfigurationProperties(prefix = "echomind.observability")
public class ObservabilityProperties {

    private final Exporter exporter = new Exporter();
    private final Jaeger jaeger = new Jaeger();

    public Exporter getExporter() {
        return exporter;
    }

    public Jaeger getJaeger() {
        return jaeger;
    }

    public static class Exporter {
        private String tracesExporter = "none";
        private String otlpEndpoint = "";
        private String otlpProtocol = "http/protobuf";
        private String serviceName = "echomind";

        public String getTracesExporter() {
            return tracesExporter;
        }

        public void setTracesExporter(String tracesExporter) {
            this.tracesExporter = tracesExporter;
        }

        public String getOtlpEndpoint() {
            return otlpEndpoint;
        }

        public void setOtlpEndpoint(String otlpEndpoint) {
            this.otlpEndpoint = otlpEndpoint;
        }

        public String getOtlpProtocol() {
            return otlpProtocol;
        }

        public void setOtlpProtocol(String otlpProtocol) {
            this.otlpProtocol = otlpProtocol;
        }

        public String getServiceName() {
            return serviceName;
        }

        public void setServiceName(String serviceName) {
            this.serviceName = serviceName;
        }
    }

    public static class Jaeger {
        private boolean enabled = false;
        private String queryUrl = "";
        private String publicUrl = "";
        private String serviceName = "echomind";
        private int timeoutSeconds = 5;
        private int defaultLimit = 20;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getQueryUrl() {
            return queryUrl;
        }

        public void setQueryUrl(String queryUrl) {
            this.queryUrl = queryUrl;
        }

        public String getPublicUrl() {
            return publicUrl;
        }

        public void setPublicUrl(String publicUrl) {
            this.publicUrl = publicUrl;
        }

        public String getServiceName() {
            return serviceName;
        }

        public void setServiceName(String serviceName) {
            this.serviceName = serviceName;
        }

        public int getTimeoutSeconds() {
            return timeoutSeconds;
        }

        public void setTimeoutSeconds(int timeoutSeconds) {
            this.timeoutSeconds = timeoutSeconds;
        }

        public int getDefaultLimit() {
            return defaultLimit;
        }

        public void setDefaultLimit(int defaultLimit) {
            this.defaultLimit = defaultLimit;
        }
    }
}
