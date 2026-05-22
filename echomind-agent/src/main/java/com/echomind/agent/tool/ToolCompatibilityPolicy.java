package com.echomind.agent.tool;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Filters tools whose declared URL/domain scope conflicts with the user message.
 */
final class ToolCompatibilityPolicy {

    private static final Pattern HTTP_URL_PATTERN = Pattern.compile(
        "https?://[^\\s\\p{Z}<>\"'）)】]+",
        Pattern.CASE_INSENSITIVE
    );

    List<Tool> filter(String userMessage, Collection<Tool> candidateTools) {
        if (candidateTools == null || candidateTools.isEmpty()) {
            return List.of();
        }
        List<URI> urls = extractHttpUrls(userMessage);
        if (urls.isEmpty()) {
            return List.copyOf(candidateTools);
        }
        return candidateTools.stream()
            .filter(tool -> isCompatibleWithUrls(tool, urls))
            .toList();
    }

    int scoreUrlIntent(List<URI> urls, Tool tool) {
        if (urls == null || urls.isEmpty()) {
            return 0;
        }
        int score = 0;
        if (isGenericWebTool(tool)) {
            score += 12;
        }
        Set<String> supportedHosts = supportedUrlHosts(tool);
        if (!supportedHosts.isEmpty() && urls.stream().anyMatch(url -> matchesSupportedHost(url, supportedHosts))) {
            score += 14;
        }
        return score;
    }

    boolean isCompatibleWithUrls(Tool tool, List<URI> urls) {
        if (urls == null || urls.isEmpty()) {
            return true;
        }
        Set<String> supportedHosts = supportedUrlHosts(tool);
        if (!supportedHosts.isEmpty()) {
            return urls.stream().anyMatch(url -> matchesSupportedHost(url, supportedHosts));
        }
        return true;
    }

    List<URI> extractHttpUrls(String message) {
        if (message == null || message.isBlank()) {
            return List.of();
        }
        List<URI> urls = new ArrayList<>();
        Matcher matcher = HTTP_URL_PATTERN.matcher(message);
        while (matcher.find()) {
            String raw = trimUrlSuffix(matcher.group());
            try {
                URI uri = URI.create(raw);
                String scheme = uri.getScheme();
                if (scheme != null && ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                    && uri.getHost() != null) {
                    urls.add(uri);
                }
            } catch (IllegalArgumentException ignored) {
                // Invalid URLs simply do not participate in domain routing.
            }
        }
        return urls;
    }

    private boolean isGenericWebTool(Tool tool) {
        if (tool == null) {
            return false;
        }
        String name = tool.name() == null ? "" : tool.name().toLowerCase(Locale.ROOT);
        if (name.contains("web-search") || name.contains("web_search") || name.equals("websearch")) {
            return true;
        }
        Set<String> tags = new HashSet<>();
        for (String tag : ToolRoutingMetadata.terms(tool.tags())) {
            if (tag != null) {
                tags.add(tag.toLowerCase(Locale.ROOT));
            }
        }
        return tags.contains("web") && (tags.contains("search") || tags.contains("lookup") || tags.contains("find"));
    }

    private Set<String> supportedUrlHosts(Tool tool) {
        Set<String> hosts = new LinkedHashSet<>();
        if (tool == null) {
            return hosts;
        }
        addHostHints(hosts, tool.tags());
        addHostHints(hosts, tool.keywords());
        for (var entry : ToolRoutingMetadata.aliases(tool).entrySet()) {
            addHostHint(hosts, entry.getKey());
            addHostHints(hosts, entry.getValue());
        }
        addSchemaHostHints(hosts, tool.parameterSchema());
        if (hosts.isEmpty() && looksLikeNowcoderTool(tool)) {
            hosts.add("nowcoder.com");
        }
        return hosts;
    }

    private boolean looksLikeNowcoderTool(Tool tool) {
        if (tool == null) {
            return false;
        }
        String fingerprint = (
            ToolRoutingMetadata.safe(tool.name()) + " " +
            ToolRoutingMetadata.safe(tool.sourceId()) + " " +
            ToolRoutingMetadata.safe(tool.description()) + " " +
            ToolRoutingMetadata.schemaText(tool.parameterSchema())
        ).toLowerCase(Locale.ROOT);
        return fingerprint.contains("nowcoder") || fingerprint.contains("牛客");
    }

    private boolean matchesSupportedHost(URI uri, Set<String> supportedHosts) {
        String host = uri == null ? null : uri.getHost();
        if (host == null || supportedHosts == null || supportedHosts.isEmpty()) {
            return false;
        }
        String lowerHost = host.toLowerCase(Locale.ROOT);
        return supportedHosts.stream()
            .anyMatch(supportedHost -> lowerHost.equals(supportedHost) || lowerHost.endsWith("." + supportedHost));
    }

    private void addSchemaHostHints(Set<String> hosts, Map<String, Object> schema) {
        if (schema == null || schema.isEmpty()) {
            return;
        }
        addHostHintValue(hosts, schema.get("x-supported-hosts"));
        addHostHintValue(hosts, schema.get("supportedHosts"));
        addHostHintValue(hosts, schema.get("supported_domains"));
    }

    private void addHostHints(Set<String> hosts, Collection<String> values) {
        if (values == null) {
            return;
        }
        for (String value : values) {
            addHostHint(hosts, value);
        }
    }

    private void addHostHintValue(Set<String> hosts, Object value) {
        if (value instanceof Collection<?> collection) {
            for (Object item : collection) {
                addHostHint(hosts, String.valueOf(item));
            }
            return;
        }
        addHostHint(hosts, value == null ? null : String.valueOf(value));
    }

    private void addHostHint(Set<String> hosts, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (normalized.startsWith("host:")) {
            normalized = normalized.substring("host:".length()).trim();
        } else if (normalized.startsWith("domain:")) {
            normalized = normalized.substring("domain:".length()).trim();
        } else if (normalized.startsWith("url-host:")) {
            normalized = normalized.substring("url-host:".length()).trim();
        } else {
            return;
        }
        if (normalized.startsWith("http://") || normalized.startsWith("https://")) {
            try {
                normalized = URI.create(normalized).getHost();
            } catch (IllegalArgumentException ignored) {
                return;
            }
        }
        if (normalized != null && normalized.contains(".") && !normalized.contains("/") && !normalized.contains(" ")) {
            hosts.add(normalized);
        }
    }

    private String trimUrlSuffix(String url) {
        String trimmed = url == null ? "" : url.trim();
        while (!trimmed.isEmpty() && "，。！？；;,.!?".indexOf(trimmed.charAt(trimmed.length() - 1)) >= 0) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }
}
