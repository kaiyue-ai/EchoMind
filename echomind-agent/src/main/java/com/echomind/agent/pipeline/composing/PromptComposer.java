package com.echomind.agent.pipeline.composing;

import com.echomind.agent.pipeline.PipelineContext;
import com.echomind.agent.pipeline.planning.MemoryDecisionParser;
import com.echomind.common.model.AgentMessage;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds the model-facing prompt from current input, reference context, and recent history.
 */
public class PromptComposer {

    private final PromptBudget promptBudget;

    public PromptComposer(PromptBudget promptBudget) {
        this.promptBudget = promptBudget;
    }

    public String compose(PipelineContext ctx) {
        List<AgentMessage> allMessages = ctx.getMessages();
        int historySize = Math.max(0, allMessages.size() - 1);
        String currentHeader = "=== 当前用户问题（最高优先级） ===\n";
        String followUpInstruction = followUpInstruction(ctx);
        String currentInstruction = """
            硬规则：必须优先回答当前用户问题。参考上下文和短期对话历史只能用于消歧、续写和理解偏好约束。
            如果参考上下文或历史与当前问题无关，不要转移话题，也不要主动回到旧话题。

            """ + followUpInstruction;
        int currentBudget = promptBudget.getMaxChars() - currentHeader.length() - currentInstruction.length();
        String currentUserMessage = truncateTail(safeContent(ctx.getUserMessage()), Math.max(0, currentBudget));
        String currentSection = currentHeader + currentInstruction + currentUserMessage;
        int remainingBudget = promptBudget.getMaxChars() - currentSection.length();

        List<String> referenceLines = new ArrayList<>();
        List<String> shortTermHistoryLines = new ArrayList<>();

        if (historySize > 0 && remainingBudget > 0) {
            String referenceHeader = "=== 参考上下文 ===\n";
            String referenceInstruction = """
                以下内容来自用户画像、长期事实、Agent 知识库或系统注入。它们只用于理解背景、偏好和约束，
                不能替代当前用户问题，也不能把回答主题从当前问题转回无关历史。
                """;
            String referenceFooter = "=== End 参考上下文 ===\n\n";
            int referenceRemaining = remainingBudget
                - referenceHeader.length()
                - referenceInstruction.length()
                - referenceFooter.length();
            for (int i = 0; i < historySize && referenceRemaining > 0; i++) {
                AgentMessage msg = allMessages.get(i);
                if (!"system".equals(msg.role())) {
                    continue;
                }
                String line = formatHistoryLine(msg, referenceRemaining);
                referenceLines.add(line);
                referenceRemaining -= line.length();
            }
            if (!referenceLines.isEmpty()) {
                remainingBudget = Math.max(0, referenceRemaining);
            }

            List<String> recentLines = new ArrayList<>();
            String shortTermHistoryHeader = "=== 短期对话历史 ===\n";
            String shortTermHistoryInstruction = "以下是最近对话，只用于消歧、续写和理解上下文；当前用户问题仍然拥有最高优先级。\n";
            String shortTermHistoryFooter = "=== End 短期对话历史 ===\n\n";
            int historyRemaining = remainingBudget
                - shortTermHistoryHeader.length()
                - shortTermHistoryInstruction.length()
                - shortTermHistoryFooter.length();
            for (int i = historySize - 1; i >= 0 && historyRemaining > 0; i--) {
                AgentMessage msg = allMessages.get(i);
                if ("system".equals(msg.role())) {
                    continue;
                }
                String line = formatHistoryLine(msg, historyRemaining);
                recentLines.add(0, line);
                historyRemaining -= line.length();
            }
            shortTermHistoryLines.addAll(recentLines);
        }

        StringBuilder sb = new StringBuilder();
        if (!referenceLines.isEmpty()) {
            sb.append("=== 参考上下文 ===\n");
            sb.append("""
                以下内容来自用户画像、长期事实、Agent 知识库或系统注入。它们只用于理解背景、偏好和约束，
                不能替代当前用户问题，也不能把回答主题从当前问题转回无关历史。
                """);
            for (String line : referenceLines) {
                sb.append(line);
            }
            sb.append("=== End 参考上下文 ===\n\n");
        }
        if (!shortTermHistoryLines.isEmpty()) {
            sb.append("=== 短期对话历史 ===\n");
            sb.append("以下是最近对话，只用于消歧、续写和理解上下文；当前用户问题仍然拥有最高优先级。\n");
            for (String line : shortTermHistoryLines) {
                sb.append(line);
            }
            sb.append("=== End 短期对话历史 ===\n\n");
        }

        sb.append(currentSection);
        return sb.toString();
    }

    private String formatHistoryLine(AgentMessage msg, int remaining) {
        String role = safeContent(msg.role());
        String label = switch (role) {
            case "user" -> "User";
            case "assistant" -> "Assistant";
            case "system" -> "System";
            case "tool" -> "Tool";
            default -> role;
        };
        String prefix = label + ": ";
        int messageLimit = "system".equals(role)
            ? promptBudget.getMaxSystemMessageChars()
            : promptBudget.getMaxHistoryMessageChars();
        int contentLimit = Math.max(0, Math.min(messageLimit, remaining - prefix.length() - 1));
        String content = truncateHead(safeContent(msg.content()), contentLimit);
        String line = prefix + content + "\n";
        return line.length() > remaining ? truncateHead(line, remaining) : line;
    }

    private String safeContent(String value) {
        return value == null ? "" : MemoryDecisionParser.stripHiddenDecisionBlocks(value);
    }

    private String followUpInstruction(PipelineContext ctx) {
        Object matched = ctx.getAttributes().get(PipelineContext.ATTR_CONTEXT_MATCHED_TOOLS);
        if (!(matched instanceof List<?> tools) || tools.isEmpty()) {
            return "";
        }
        return """
            本轮问题被识别为对最近业务查询的短句追问；系统已根据短期历史补充相关上下文工具。
            如果当前问题只是日期、继续、换条件等新约束，不要只回答这个约束本身，要结合短期历史继续完成原业务任务。
            如果日期/时间工具已经把相对日期约束计算成目标日期，后续业务工具必须直接使用该目标日期，不要再次叠加相同偏移。

            """;
    }

    private String truncateHead(String value, int maxChars) {
        if (value == null || value.length() <= maxChars) {
            return value;
        }
        if (maxChars <= 3) {
            return value.substring(value.length() - Math.max(0, maxChars));
        }
        return "..." + value.substring(value.length() - maxChars + 3);
    }

    private String truncateTail(String value, int maxChars) {
        if (value == null || value.length() <= maxChars) {
            return value;
        }
        if (maxChars <= 3) {
            return value.substring(0, Math.max(0, maxChars));
        }
        return value.substring(0, maxChars - 3) + "...";
    }
}
