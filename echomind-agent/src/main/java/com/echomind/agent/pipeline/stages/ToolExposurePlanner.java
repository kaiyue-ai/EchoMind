package com.echomind.agent.pipeline.stages;

import com.echomind.agent.pipeline.PipelineContext;
import com.echomind.agent.tool.Tool;
import com.echomind.agent.tool.ToolRouter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Locale;

@Slf4j
@RequiredArgsConstructor
class ToolExposurePlanner {

    private final ToolRouter toolRouter;

    ToolExposure plan(PipelineContext ctx) {
        List<Tool> tools = toolsFor(ctx);
        return new ToolExposure(tools, requiredToolName(tools, ctx));
    }

    String functionName(Tool tool) {
        String normalized = tool.name().replaceAll("[.\\-]", "_");
        return normalized.matches("[A-Za-z_][A-Za-z0-9_]*") ? normalized : "tool_" + normalized;
    }

    boolean directResult(Tool tool) {
        if (tool == null || tool.tags() == null) {
            return false;
        }
        return tool.tags().stream()
            .filter(tag -> tag != null && !tag.isBlank())
            .map(tag -> tag.toLowerCase(Locale.ROOT))
            .anyMatch(tag -> tag.equals("direct-result") || tag.equals("final-answer"));
    }

    private String requiredToolName(List<Tool> tools, PipelineContext ctx) {
        if (tools == null || tools.size() != 1) {
            return null;
        }
        Object mode = ctx.getAttributes().get("toolMatchMode");
        return "keyword".equals(mode) ? functionName(tools.get(0)) : null;
    }

    private List<Tool> toolsFor(PipelineContext ctx) {
        Object allowedObj = ctx.getAttributes().get("agentSkillIds");
        List<Tool> allowedTools;
        List<Tool> keywordMatched;
        if (allowedObj instanceof List<?> allowed) {
            allowedTools = toolRouter.filterCompatibleTools(ctx.getUserMessage(), toolRouter.listForAgentSkillIds(allowed));
            keywordMatched = toolRouter.matchForAgentSkillIds(ctx.getUserMessage(), allowed);
        } else {
            allowedTools = toolRouter.filterCompatibleTools(ctx.getUserMessage(), toolRouter.listForAgentSkillIds(List.of()));
            keywordMatched = toolRouter.matchForAgentSkillIds(ctx.getUserMessage(), List.of());
        }
        if (!keywordMatched.isEmpty()) {
            ctx.getAttributes().put("toolMatchMode", "keyword");
            ctx.getAttributes().put("keywordMatchedTools", keywordMatched.stream().map(Tool::name).toList());
            log.debug("Keyword matched {} tool(s): {}", keywordMatched.size(),
                keywordMatched.stream().map(Tool::name).toList());
            return keywordMatched;
        }

        ctx.getAttributes().put("toolMatchMode", "model");
        log.debug("No keyword matched tools, exposing {} allowed tool(s) for model decision", allowedTools.size());
        return allowedTools;
    }
}
