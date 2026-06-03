package com.echomind.agent.tool.core;

import java.util.Collection;
import java.util.List;
import java.util.Map;

public final class ToolRoutingMetadata {

    private ToolRoutingMetadata() {
    }

    public static Collection<String> terms(Collection<String> values) {
        return values == null ? List.of() : values;
    }

    public static Map<String, List<String>> aliases(Tool tool) {
        if (tool == null || tool.aliases() == null) {
            return Map.of();
        }
        return tool.aliases();
    }
}
