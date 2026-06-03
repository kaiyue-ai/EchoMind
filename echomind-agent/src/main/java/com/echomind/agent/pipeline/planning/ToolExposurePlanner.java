package com.echomind.agent.pipeline.planning;

import com.echomind.agent.pipeline.PipelineContext;
import com.echomind.agent.tool.router.ToolRouter;
import com.echomind.agent.tool.core.Tool;
import com.echomind.common.model.AgentMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具暴露规划器。
 *
 * <p>负责根据用户消息和会话上下文，智能选择需要暴露给模型的工具。
 * 采用多级匹配策略：关键词匹配优先，其次是上下文匹配，最后回退到模型自主决策。</p>
 */
@Slf4j
@RequiredArgsConstructor
public class ToolExposurePlanner {

    private static final int CONTEXT_LOOKBACK_TURNS = 6; 

    private final ToolRouter toolRouter;

    /**
     * 规划本轮对话需要暴露的工具。
     *
     * @param ctx 管线上下文
     * @return 工具暴露结果
     */
    public ToolExposure plan(PipelineContext ctx) {
        List<Tool> tools = toolsFor(ctx);
        return new ToolExposure(tools, null);
    }

    /**
     * 将工具名称规范化为有效的函数名。
     * 移除点号和连字符，确保符合函数命名规范。
     *
     * @param tool 工具
     * @return 规范化后的函数名
     */
    public String functionName(Tool tool) {
        String normalized = tool.name().replaceAll("[.\\-]", "_");
        return normalized.matches("[A-Za-z_][A-Za-z0-9_]*") ? normalized : "tool_" + normalized;
    }

    /**
     * 根据上下文选择需要暴露的工具。
     *
     * <p>匹配策略优先级（逐级回退）：
     * <ol>
     *   <li><strong>关键词匹配</strong> - 通过用户消息中的关键词匹配工具（优先级最高）</li>
     *   <li><strong>上下文匹配</strong> - 通过历史消息匹配相关工具（追问场景）</li>
     *   <li><strong>模型决策</strong> - 暴露所有允许的工具由模型自主选择（兜底）</li>
     * </ol>
     *
     * @param ctx 管线上下文
     * @return 匹配到的工具列表
     */
    private List<Tool> toolsFor(PipelineContext ctx) {
        // 1. 检查是否禁用工具暴露（内部控制平面调用时禁用）
        if (Boolean.TRUE.equals(ctx.getAttributes().get(PipelineContext.ATTR_TOOL_EXPOSURE_DISABLED))) {
            ctx.getAttributes().put(PipelineContext.ATTR_TOOL_MATCH_MODE, PipelineContext.TOOL_MATCH_DISABLED);
            log.debug("Tool exposure disabled for internal control-plane invocation");
            return List.of();
        }

        // 2. 获取允许的工具列表和关键词匹配结果
        Object allowedObj = ctx.getAttributes().get(PipelineContext.ATTR_AGENT_SKILL_IDS);
        List<Tool> allowedTools;      // 当前Agent允许使用的工具
        List<Tool> keywordMatched;    // 通过关键词匹配到的工具

        if (allowedObj instanceof List<?> allowed) {
            // 如果指定了Skill ID列表，则只从这些Skill中获取工具
            allowedTools = toolRouter.listForAgentSkillIds(allowed);
            keywordMatched = toolRouter.matchForAgentSkillIds(ctx.getUserMessage(), allowed);
        } else {
            // 未指定Skill ID，使用全局工具池
            allowedTools = toolRouter.listForAgentSkillIds(List.of());
            keywordMatched = toolRouter.matchForAgentSkillIds(ctx.getUserMessage(), List.of());
        }

        // 3. 策略一：关键词匹配优先
        if (!keywordMatched.isEmpty()) {
            // 同时尝试上下文匹配作为补充（处理追问场景）
            List<Tool> contextMatched = contextMatchedTools(ctx, allowedTools, keywordMatched);
            List<Tool> exposedTools = merge(keywordMatched, contextMatched);

            // 记录匹配模式和结果到上下文属性
            ctx.getAttributes().put(PipelineContext.ATTR_TOOL_MATCH_MODE, PipelineContext.TOOL_MATCH_KEYWORD);
            ctx.getAttributes().put(PipelineContext.ATTR_KEYWORD_MATCHED_TOOLS,
                keywordMatched.stream().map(Tool::name).toList());
            if (!contextMatched.isEmpty()) {
                ctx.getAttributes().put(PipelineContext.ATTR_CONTEXT_MATCHED_TOOLS,
                    contextMatched.stream().map(Tool::name).toList());
            }

            log.debug("Keyword matched {} tool(s): {}, context matched {} tool(s): {}",
                keywordMatched.size(),
                keywordMatched.stream().map(Tool::name).toList(),
                contextMatched.size(),
                contextMatched.stream().map(Tool::name).toList());
            return exposedTools;
        }

        // 4. 策略二：上下文匹配（关键词未匹配到时）
        List<Tool> contextMatched = contextMatchedTools(ctx, allowedTools, List.of());
        if (!contextMatched.isEmpty()) {
            ctx.getAttributes().put(PipelineContext.ATTR_TOOL_MATCH_MODE, PipelineContext.TOOL_MATCH_KEYWORD);
            ctx.getAttributes().put(PipelineContext.ATTR_CONTEXT_MATCHED_TOOLS,
                contextMatched.stream().map(Tool::name).toList());
            log.debug("Follow-up context matched {} tool(s): {}", contextMatched.size(),
                contextMatched.stream().map(Tool::name).toList());
            return contextMatched;
        }

        // 5. 策略三：模型自主决策（兜底策略）
        ctx.getAttributes().put(PipelineContext.ATTR_TOOL_MATCH_MODE, PipelineContext.TOOL_MATCH_MODEL);
        log.debug("No keyword matched tools, exposing {} allowed tool(s) for model decision", allowedTools.size());
        return allowedTools;
    }

    /**
     * 根据历史会话上下文匹配工具。
     *
     * <p>用于处理追问场景：当用户消息是对前序对话的延续时，
     * 自动匹配相关工具而无需用户再次提及关键词。
     *
     * @param ctx           管线上下文
     * @param allowedTools  允许的工具列表
     * @param keywordMatched 已通过关键词匹配的工具
     * @return 上下文匹配到的工具列表
     */
    private List<Tool> contextMatchedTools(PipelineContext ctx, List<Tool> allowedTools, List<Tool> keywordMatched) {
        if (!isLikelyFollowUp(ctx.getUserMessage())) {
            return List.of();
        }

        if (!keywordMatched.isEmpty() && keywordMatched.stream().anyMatch(tool -> !isConstraintTool(tool))) {
            return List.of();
        }

        List<Tool> remainingTools = allowedTools.stream()
            .filter(tool -> keywordMatched.stream().noneMatch(matched -> matched.name().equals(tool.name())))
            .toList();

        if (remainingTools.isEmpty()) {
            return List.of();
        }

        List<String> recentUserMessages = recentUserMessages(ctx);
        List<Tool> matched = new ArrayList<>();
        for (String message : recentUserMessages) {
            for (Tool tool : toolRouter.matchTools(message, remainingTools)) {
                if (matched.stream().noneMatch(existing -> existing.name().equals(tool.name()))) {
                    matched.add(tool);
                }
            }
        }
        return matched;
    }

    /**
     * 判断消息是否为追问场景。
     *
     * <p>识别以下追问模式：
     * - 意图变更词：继续、再查、换、改成、按时间
     * - 简短日期约束：明天、后天、今天、昨天、前天、日期、哪天、几号（且长度<=8）
     *
     * @param message 用户消息
     * @return true 如果是追问场景
     */
    private boolean isLikelyFollowUp(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        String normalized = message.replaceAll("[\\s，。！？,.!?;；:：、~-]", "");
        boolean changeIntent = normalized.contains("继续")
            || normalized.contains("再查")
            || normalized.contains("换")
            || normalized.contains("改成")
            || normalized.contains("按时间");
        boolean shortDateConstraint = normalized.length() <= 8
            && (normalized.contains("明天")
                || normalized.contains("后天")
                || normalized.contains("今天")
                || normalized.contains("昨天")
                || normalized.contains("前天")
                || normalized.contains("日期")
                || normalized.contains("哪天")
                || normalized.contains("几号"));
        return changeIntent || shortDateConstraint;
    }

    /**
     * 判断工具是否为约束型工具（日期/时间相关）。
     *
     * @param tool 工具
     * @return true 如果是约束型工具
     */
    private boolean isConstraintTool(Tool tool) {
        return containsTerm(tool.name(), "date", "time")
            || containsAny(tool.tags(), "date", "time", "calendar", "weekday", "日期", "时间")
            || containsAny(tool.keywords(), "日期", "时间", "今天", "明天", "后天", "昨天", "前天");
    }

    /**
     * 判断字符串是否包含任意指定术语（不区分大小写）。
     *
     * @param value  待检查字符串
     * @param terms  术语列表
     * @return true 如果包含任一术语
     */
    private boolean containsTerm(String value, String... terms) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String normalized = value.toLowerCase();
        for (String term : terms) {
            if (normalized.contains(term)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 判断字符串列表中是否有任意元素包含指定术语。
     *
     * @param values 字符串列表
     * @param terms  术语列表
     * @return true 如果任一元素包含任一术语
     */
    private boolean containsAny(List<String> values, String... terms) {
        if (values == null || values.isEmpty()) {
            return false;
        }
        for (String value : values) {
            if (containsTerm(value, terms)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 获取最近的用户消息（排除当前消息）。
     *
     * @param ctx 管线上下文
     * @return 最近最多6条用户消息
     */
    private List<String> recentUserMessages(PipelineContext ctx) {
        List<AgentMessage> messages = ctx.getMessages();
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }
        List<String> recent = new ArrayList<>();
        for (int i = messages.size() - 1; i >= 0 && recent.size() < CONTEXT_LOOKBACK_TURNS; i--) {
            AgentMessage message = messages.get(i);
            if (message == null || !"user".equals(message.role())) {
                continue;
            }
            String content = message.content();
            if (content == null || content.isBlank() || content.equals(ctx.getUserMessage())) {
                continue;
            }
            recent.add(content);
        }
        return recent;
    }

    /**
     * 合并两个工具列表，保持顺序且去重。
     *
     * @param first  第一个列表（优先）
     * @param second 第二个列表（补充）
     * @return 合并后的列表
     */
    private List<Tool> merge(List<Tool> first, List<Tool> second) {
        Map<String, Tool> merged = new LinkedHashMap<>();
        first.forEach(tool -> merged.put(tool.name(), tool));
        second.forEach(tool -> merged.putIfAbsent(tool.name(), tool));
        return List.copyOf(merged.values());
    }
}
