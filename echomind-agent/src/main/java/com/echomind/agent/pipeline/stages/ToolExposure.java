package com.echomind.agent.pipeline.stages;

import com.echomind.agent.tool.Tool;

import java.util.List;

record ToolExposure(List<Tool> tools, String requiredToolName) {

    ToolExposure {
        tools = tools == null ? List.of() : List.copyOf(tools);
    }
}
