package com.echomind.agent.pipeline.planning;

import com.echomind.agent.tool.core.Tool;

import java.util.List;

public record ToolExposure(List<Tool> tools, String requiredToolName) {

    public ToolExposure {
        tools = tools == null ? List.of() : List.copyOf(tools);
    }
}
