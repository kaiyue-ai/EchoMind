package com.echomind.agent.team.visualization;

import com.echomind.agent.team.runtime.TeamEventSnapshot;
import com.echomind.agent.team.runtime.TeamStepSnapshot;

import java.util.List;

/**
 * Mermaid 图生成器 —— 将 Agent Team 协作流程转换为 Mermaid.js 序列图源码。
 *
 * <p>生成的 Mermaid 序列图展示团队协作的标准流程：
 * <pre>{@code
 * sequenceDiagram
 *     title 团队名称 - Agent Team 协作
 *     participant 用户
 *     participant Planner
 *     participant Executor
 *     participant Reviewer
 *
 *     用户->>Planner: 分配任务
 *     Planner->>Planner: 分析并拆解
 *     Planner->>Executor: 子任务 1: "xxx"
 *     Executor->>Executor: 执行子任务 1
 *     Executor->>Planner: 返回结果 1
 *     ...
 *     Planner->>Reviewer: 提交结果审核
 *     Reviewer->>Reviewer: 评估并整合
 *     Reviewer->>用户: 最终整合回复
 * }</pre>
 *
 * <p>设计要点：
 * <ul>
 *   <li>子任务描述超过 40 字符时自动截断并附加 "..."，防止图表过宽。</li>
 *   <li>引导号在子任务文本中被转义为单引号，避免破坏 Mermaid 语法。</li>
 *   <li>当前版本固定使用 4 个参与者（用户/Planner/Executor/Reviewer）。</li>
 * </ul>
 */
public class MermaidGenerator {

    /**
     * 生成 Mermaid 序列图源码。
     *
     * <p>根据团队名称、子任务列表和执行结果生成标准序列图。
     * 注意：当前版本的 events 参数预留用于未来基于实际事件
     * 动态生成更精确的图表（当前未使用）。
     *
     * @param teamName 团队显示名称（用作图表标题）
     * @param subtasks 子任务列表（用于生成 Executor 执行步骤）
     * @param results  执行结果列表（当前版本未直接使用，预留扩展）
     * @param events   跟踪事件列表（当前版本未直接使用，预留扩展）
     * @return Mermaid.js 格式的序列图源码字符串
     */
    public static String generate(String teamName, List<String> subtasks,
                                   List<String> results, List<TeamTraceRecorder.TraceEvent> events) {
        StringBuilder sb = new StringBuilder();
        sb.append("sequenceDiagram\n");
        sb.append("    title ").append(teamName).append(" - Agent Team 协作\n\n");

        // 定义参与者
        sb.append("    participant 用户\n");
        sb.append("    participant Planner\n");
        sb.append("    participant Executor\n");
        sb.append("    participant Reviewer\n\n");

        // 任务分配
        sb.append("    用户->>Planner: 分配任务\n");

        // 规划阶段
        sb.append("    Planner->>Planner: 分析并拆解\n");

        // 子任务执行步骤
        for (int i = 0; i < subtasks.size(); i++) {
            String task = subtasks.get(i).replace("\"", "'");
            sb.append("    Planner->>Executor: 子任务 ").append(i + 1)
                .append(": \"").append(task.length() > 40 ? task.substring(0, 40) + "..." : task)
                .append("\"\n");
            sb.append("    Executor->>Executor: 执行子任务 ").append(i + 1).append("\n");
            sb.append("    Executor->>Planner: 返回结果 ").append(i + 1).append("\n");
        }

        // 审阅阶段
        sb.append("    Planner->>Reviewer: 提交结果审核\n");
        sb.append("    Reviewer->>Reviewer: 评估并整合\n");
        sb.append("    Reviewer->>用户: 最终整合回复\n");

        return sb.toString();
    }

    /**
     * 根据黑板中的 Step/Event 生成更贴近真实执行过程的 Mermaid 序列图。
     */
    public static String generateFromSnapshots(String teamName, List<TeamStepSnapshot> steps,
                                               List<TeamEventSnapshot> events) {
        StringBuilder sb = new StringBuilder();
        sb.append("sequenceDiagram\n");
        sb.append("    title ").append(escape(teamName)).append(" - Agent Team 黑板协作\n\n");
        sb.append("    participant User as 用户\n");
        sb.append("    participant Planner\n");
        sb.append("    participant Reviewer\n");

        List<String> executors = steps.stream()
            .map(TeamStepSnapshot::assignedAgentId)
            .filter(id -> id != null && !id.isBlank())
            .distinct()
            .toList();
        for (String executor : executors) {
            sb.append("    participant ").append(alias(executor)).append(" as ")
                .append(escape(executor)).append("\n");
        }
        sb.append("\n");

        sb.append("    User->>Planner: 提交初始需求\n");
        sb.append("    Planner->>Planner: 拆解结构化 Step\n");
        sb.append("    Planner->>Reviewer: 提交计划审查\n");
        sb.append("    Reviewer-->>Planner: 计划审查通过/修改意见\n");

        for (TeamStepSnapshot step : steps) {
            String executor = step.assignedAgentId() == null || step.assignedAgentId().isBlank()
                ? "Executor"
                : alias(step.assignedAgentId());
            String title = truncate(step.title());
            sb.append("    Planner->>").append(executor).append(": ")
                .append(escape(title)).append("\n");
            sb.append("    ").append(executor).append("->>").append(executor)
                .append(": 执行 ").append(escape(step.status().name())).append("\n");
            if (step.retryCount() > 0) {
                sb.append("    Reviewer-->>").append(executor).append(": 重试意见 x")
                    .append(step.retryCount()).append("\n");
            }
            sb.append("    ").append(executor).append("-->>Reviewer: 返回原始结果\n");
        }

        boolean askedClarification = events.stream()
            .anyMatch(event -> event.type() != null && "CLARIFICATION_REQUESTED".equals(event.type().name()));
        if (askedClarification) {
            sb.append("    Reviewer-->>User: 请求澄清\n");
            sb.append("    User-->>Reviewer: 补充信息\n");
        }
        sb.append("    Reviewer->>Reviewer: 对照初始需求审查结果\n");
        sb.append("    Reviewer-->>User: 输出最终报告\n");
        return sb.toString();
    }

    private static String alias(String value) {
        String cleaned = value == null ? "Executor" : value.replaceAll("[^A-Za-z0-9_]", "_");
        if (cleaned.isBlank() || Character.isDigit(cleaned.charAt(0))) {
            cleaned = "Agent_" + cleaned;
        }
        return cleaned;
    }

    private static String truncate(String value) {
        String text = value == null || value.isBlank() ? "子任务" : value.replace("\"", "'");
        return text.length() > 40 ? text.substring(0, 40) + "..." : text;
    }

    private static String escape(String value) {
        return (value == null ? "" : value).replace("\"", "'");
    }
}
