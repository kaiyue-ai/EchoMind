package com.echomind.agent.team.visualization;

import java.util.List;

/**
 * Mermaid 图生成器 —— 将 Agent Team 协作流程转换为 Mermaid.js 序列图源码。
 *
 * <p>生成的 Mermaid 序列图展示团队协作的标准流程：
 * <pre>{@code
 * sequenceDiagram
 *     title TeamName - Agent Team Collaboration
 *     participant User
 *     participant Planner
 *     participant Executor
 *     participant Reviewer
 *
 *     User->>Planner: Task assigned
 *     Planner->>Planner: Analyze and decompose
 *     Planner->>Executor: Subtask 1: "xxx"
 *     Executor->>Executor: Execute subtask 1
 *     Executor->>Planner: Result 1
 *     ...
 *     Planner->>Reviewer: Submit results for review
 *     Reviewer->>Reviewer: Evaluate and consolidate
 *     Reviewer->>User: Final consolidated response
 * }</pre>
 *
 * <p>设计要点：
 * <ul>
 *   <li>子任务描述超过 40 字符时自动截断并附加 "..."，防止图表过宽。</li>
 *   <li>引导号在子任务文本中被转义为单引号，避免破坏 Mermaid 语法。</li>
 *   <li>当前版本固定使用 4 个参与者（User/Planner/Executor/Reviewer）。</li>
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
        sb.append("    title ").append(teamName).append(" - Agent Team Collaboration\n\n");

        // 定义参与者
        sb.append("    participant User\n");
        sb.append("    participant Planner\n");
        sb.append("    participant Executor\n");
        sb.append("    participant Reviewer\n\n");

        // 任务分配
        sb.append("    User->>Planner: Task assigned\n");

        // 规划阶段
        sb.append("    Planner->>Planner: Analyze and decompose\n");

        // 子任务执行步骤
        for (int i = 0; i < subtasks.size(); i++) {
            String task = subtasks.get(i).replace("\"", "'");
            sb.append("    Planner->>Executor: Subtask ").append(i + 1)
                .append(": \"").append(task.length() > 40 ? task.substring(0, 40) + "..." : task)
                .append("\"\n");
            sb.append("    Executor->>Executor: Execute subtask ").append(i + 1).append("\n");
            sb.append("    Executor->>Planner: Result ").append(i + 1).append("\n");
        }

        // 审阅阶段
        sb.append("    Planner->>Reviewer: Submit results for review\n");
        sb.append("    Reviewer->>Reviewer: Evaluate and consolidate\n");
        sb.append("    Reviewer->>User: Final consolidated response\n");

        return sb.toString();
    }
}
