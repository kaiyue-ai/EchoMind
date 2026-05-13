package com.echomind.agent.team;

import com.echomind.agent.orchestration.AgentOrchestrator;
import com.echomind.agent.pipeline.PipelineContext;
import com.echomind.agent.team.messaging.TeamMessage;
import com.echomind.agent.team.messaging.TeamMessageBus;
import com.echomind.agent.team.messaging.TeamMessageType;
import com.echomind.agent.team.visualization.TeamTraceRecorder;
import com.echomind.agent.team.visualization.MermaidGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * 团队协调器 —— Agent Team 协作流程的核心驱动引擎。
 *
 * <p>协调器按照 Planner → Executor → Reviewer 的三阶段范式驱动团队协作：
 * <ol>
 *   <li><b>第一阶段：规划（Planner）</b>：
 *       向 PLANNER 角色发送任务描述，由 LLM 分解为 2-4 个子任务。
 *       子任务通过 {@link #parseSubtasks} 从 LLM 响应中解析提取。</li>
 *   <li><b>第二阶段：执行（Executor）</b>：
 *       遍历每个子任务，分别委托 EXECUTOR 角色执行。
 *       若无 EXECUTOR，回退使用 PLANNER。</li>
 *   <li><b>第三阶段：审阅（Reviewer）</b>：
 *       REVIEWER 角色评估所有执行结果并生成整合的最终输出。
 *       若无 REVIEWER，直接拼接所有步骤结果。</li>
 * </ol>
 *
 * <p>设计要点：
 * <ul>
 *   <li>全程通过 {@link TeamTraceRecorder} 记录事件，用于调试和可视化。</li>
 *   <li>关键节点通过 {@link TeamMessageBus} 发布消息，支持团队内部通信。</li>
 *   <li>最终输出包含 {@link MermaidGenerator} 生成的序列图。</li>
 *   <li>maxRounds 参数预留用于未来多轮迭代场景（当前版本未启用）。</li>
 * </ul>
 */
@Slf4j
@RequiredArgsConstructor
public class TeamCoordinator {

    /** Agent 编排器，每个角色 Agent 通过它执行任务 */
    private final AgentOrchestrator orchestrator;
    /** 团队消息总线，协调器通过它广播任务和接收结果 */
    private final TeamMessageBus messageBus;
    /** 团队跟踪记录器，记录全流程事件 */
    private final TeamTraceRecorder traceRecorder;
    /** 最大协作轮次（当前版本预留） */
    private final int maxRounds;

    /**
     * 执行团队协作任务。
     *
     * <p>这是团队协调器的核心方法，驱动完整的 Planner→Executor→Reviewer 流程。
     *
     * @param team 执行任务的 Agent 团队
     * @param task 用户任务的文本描述
     * @return 团队协作结果，包含最终输出、步骤结果、状态和 Mermaid 图
     */
    public TeamResult execute(AgentTeam team, String task) {
        String sessionId = UUID.randomUUID().toString();
        traceRecorder.startSession(sessionId, team.getTeamId(), task);
        traceRecorder.recordEvent("task_start", "Task assigned to team " + team.getName());

        // 第一阶段：Planner 分解任务
        AgentTeam.TeamRole plannerRole = AgentTeam.TeamRole.PLANNER;
        var planner = team.getAgent(plannerRole);
        if (planner == null) {
            return new TeamResult("No planner configured", List.of(), "ERROR", "");
        }

        String planPrompt = "You are a Planner. Decompose the following task into 2-4 subtasks. "
            + "List each subtask on a new line prefixed with '- [ ]'.\n\nTask: " + task;

        PipelineContext planResult = orchestrator.execute(planner.getAgentId(), sessionId, planPrompt);
        List<String> subtasks = parseSubtasks(planResult.getFinalResponse());
        traceRecorder.recordEvent("plan_created", "Planner created " + subtasks.size() + " subtasks");

        // 通过消息总线发布计划
        messageBus.publish(new TeamMessage("team-coordinator", "all",
            TeamMessageType.TASK_ASSIGN, "Task decomposed into subtasks"));

        // 第二阶段：Executor 逐一处理子任务
        List<String> stepResults = new ArrayList<>();
        var executor = team.getAgent(AgentTeam.TeamRole.EXECUTOR);
        if (executor == null) {
            executor = planner; // 回退：使用 Planner 替代
        }

        for (int i = 0; i < subtasks.size(); i++) {
            String subtask = subtasks.get(i);
            traceRecorder.recordEvent("step_start", "Executing subtask " + (i + 1) + ": " + subtask);

            messageBus.publish(new TeamMessage("team-coordinator", executor.getAgentId(),
                TeamMessageType.TASK_ASSIGN, subtask));

            PipelineContext execResult = orchestrator.execute(executor.getAgentId(), sessionId, subtask);
            stepResults.add(execResult.getFinalResponse());

            messageBus.publish(new TeamMessage(executor.getAgentId(), "team-coordinator",
                TeamMessageType.RESULT_REPORT, "Completed: " + subtask));

            traceRecorder.recordEvent("step_complete", "Completed subtask " + (i + 1));
        }

        // 第三阶段：Reviewer 评估结果
        var reviewer = team.getAgent(AgentTeam.TeamRole.REVIEWER);
        if (reviewer != null) {
            traceRecorder.recordEvent("review_start", "Reviewer evaluating results");

            messageBus.publish(new TeamMessage("team-coordinator", reviewer.getAgentId(),
                TeamMessageType.TASK_ASSIGN, "Review the execution results"));

            String reviewPrompt = "You are a Reviewer. Review the execution results below "
                + "and provide a final consolidated response.\n\nOriginal Task: " + task
                + "\n\nResults:\n" + String.join("\n---\n", stepResults);

            PipelineContext reviewResult = orchestrator.execute(reviewer.getAgentId(), sessionId, reviewPrompt);

            messageBus.publish(new TeamMessage(reviewer.getAgentId(), "team-coordinator",
                TeamMessageType.CONSENSUS, "Review complete"));

            traceRecorder.recordEvent("review_complete", "Reviewer finished evaluation");

            String mermaid = MermaidGenerator.generate(
                team.getName(), subtasks, stepResults, traceRecorder.getEvents());

            return new TeamResult(reviewResult.getFinalResponse(), stepResults, "COMPLETED", mermaid);
        }

        // 无 Reviewer：直接拼接所有步骤结果
        String mermaid = MermaidGenerator.generate(
            team.getName(), subtasks, stepResults, traceRecorder.getEvents());

        return new TeamResult(
            String.join("\n\n", stepResults), stepResults, "COMPLETED", mermaid);
    }

    /**
     * 从 Planner 的 LLM 响应中解析子任务列表。
     *
     * <p>支持三种格式的子任务标记：
     * <ol>
     *   <li><b>Markdown 复选框</b>：以 {@code - [ ]} 或 {@code - [x]} 开头</li>
     *   <li><b>Markdown 列表</b>：以 {@code - } 开头（非复选框）</li>
     *   <li><b>编号列表</b>：以数字 + 点号/括号开头（如 {@code 1.}, {@code 1)}）</li>
     * </ol>
     *
     * <p>若无法解析出任何子任务，则将整个输出作为单一任务返回。
     *
     * @param planOutput Planner 的原始文本输出
     * @return 解析出的子任务列表；若为空输出则返回空列表
     */
    private List<String> parseSubtasks(String planOutput) {
        if (planOutput == null) return List.of();
        List<String> subtasks = new ArrayList<>();
        for (String line : planOutput.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("- [ ]") || trimmed.startsWith("- ")) {
                String task = trimmed.replaceFirst("- \\[.]\\s*", "").trim();
                if (!task.isEmpty()) subtasks.add(task);
            }
        }
        if (subtasks.isEmpty()) {
            // 尝试解析编号列表
            for (String line : planOutput.split("\n")) {
                String trimmed = line.trim();
                if (trimmed.matches("^\\d+[.)]\\s+.*")) {
                    subtasks.add(trimmed.replaceFirst("^\\d+[.)]\\s+", "").trim());
                }
            }
        }
        if (subtasks.isEmpty()) {
            subtasks.add(planOutput); // 将整个输出作为单个任务
        }
        return subtasks;
    }
}
