package com.echomind.agent.tool;

import java.util.Collection;
import java.util.List;
import java.util.Locale;

/**
 * Removes tools that are known to be misleading after keyword matching.
 */
final class ToolDisambiguationPolicy {

    List<Tool> apply(String userMessage, Collection<Tool> allowedTools) {
        if (allowedTools == null || allowedTools.isEmpty()) {
            return List.of();
        }
        String lowerMsg = userMessage == null ? "" : userMessage.toLowerCase(Locale.ROOT);
        List<Tool> tools = List.copyOf(allowedTools);
        tools = suppressRailwayQuestionWithoutLookupIntent(lowerMsg, tools);
        return suppressAmbiguousDateTool(lowerMsg, tools);
    }

    private List<Tool> suppressAmbiguousDateTool(String lowerMsg, List<Tool> allowedTools) {
        if (!hasTrafficTicketIntent(lowerMsg) || allowedTools.stream().noneMatch(this::isTrafficTicketTool)) {
            return allowedTools;
        }
        return allowedTools.stream()
            .filter(tool -> !isRelativeDateTool(tool))
            .toList();
    }

    private List<Tool> suppressRailwayQuestionWithoutLookupIntent(String lowerMsg, List<Tool> allowedTools) {
        if (allowedTools.stream().noneMatch(this::isTrafficTicketTool)
            || !hasRailwayDomainTerm(lowerMsg)
            || hasTrafficTicketIntent(lowerMsg)) {
            return allowedTools;
        }
        if (!ToolRoutingMetadata.containsAny(lowerMsg, List.of(
            "吗", "是不是", "还是", "属于", "算", "区别", "什么意思", "是什么", "怎么分", "高铁还是火车"
        ))) {
            return allowedTools;
        }
        return allowedTools.stream()
            .filter(tool -> !isTrafficTicketTool(tool))
            .toList();
    }

    private boolean isTrafficTicketTool(Tool tool) {
        String metadata = ToolRoutingMetadata.searchableText(tool);
        return ToolRoutingMetadata.containsAny(metadata, List.of(
            "12306", "railway", "train", "ticket", "火车票", "高铁票", "动车票", "余票", "列车", "铁路"
        ));
    }

    private boolean isRelativeDateTool(Tool tool) {
        String metadata = ToolRoutingMetadata.searchableText(tool);
        return ToolRoutingMetadata.containsAny(metadata, List.of(
            "date-query", "date", "time", "weekday", "日期", "时间", "今天", "明天", "昨天", "星期"
        ));
    }

    private boolean hasTrafficTicketIntent(String lowerMsg) {
        return hasRailwayDomainTerm(lowerMsg)
            && ToolRoutingMetadata.containsAny(lowerMsg, List.of("查询", "查", "票", "时刻", "班次", "路线", "到", "去", "从"));
    }

    private boolean hasRailwayDomainTerm(String lowerMsg) {
        return ToolRoutingMetadata.containsAny(lowerMsg, List.of(
            "12306", "火车票", "高铁票", "动车票", "余票", "车次", "经停", "列车", "高铁", "动车", "火车"
        ));
    }
}
