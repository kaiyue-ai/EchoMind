package com.echomind.agent.team.visualization;

import com.echomind.agent.team.runtime.TeamEventSnapshot;
import com.echomind.agent.team.runtime.TeamStepSnapshot;
import com.echomind.agent.team.state.TeamRunStatus;

import java.util.List;

/**
 * Mermaid 图生成器：把 Team v2 管控状态转换为完整中文流程图。
 */
public class MermaidGenerator {

    public static String generateFromSnapshots(String teamName, List<TeamStepSnapshot> steps,
                                               List<TeamEventSnapshot> events) {
        return generateFromSnapshots(teamName, TeamRunStatus.PENDING, "COMPLEX", steps, events);
    }

    public static String generateFromSnapshots(String teamName, TeamRunStatus status, String taskLevel,
                                               List<TeamStepSnapshot> steps,
                                               List<TeamEventSnapshot> events) {
        String active = activeNode(status, taskLevel, events == null ? List.of() : events);
        StringBuilder sb = new StringBuilder();
        sb.append("flowchart LR\n");
        sb.append("    A[\"外部提交任务\"] --> B[\"TaskPlanner 规划器\"]\n");
        sb.append("    B --> C{\"任务等级判断\"}\n");
        sb.append("    C -->|简易任务| D[\"直接生成初稿\"]\n");
        sb.append("    D --> O[\"GlobalReviewer 简易终审\"]\n");
        sb.append("    C -->|复杂任务| E[\"拆解 Step 并生成 DAG 依赖\"]\n");
        sb.append("    E --> PR[\"PlannerReviewer 规划审查\"]\n");
        sb.append("    PR -->|通过| F[\"TeamControlCenter 管控中心\"]\n");
        sb.append("    PR -->|要求修改| B\n");
        sb.append("    PR -->|需要澄清| W[\"暂停等待用户补充\"]\n");
        sb.append("    F --> X{\"查找可执行 Step\"}\n");
        sb.append("    X -->|依赖已完成| G[\"线程池并发分发 WorkerAgent\"]\n");
        sb.append("    X -->|依赖未完成| Y[\"等待前置 Step 完成\"]\n");
        sb.append("    G --> AS[\"AgentSelector 按能力/负载/健康度选择 Agent\"]\n");
        sb.append("    AS --> H{\"RiskPolicy 风险裁决\"}\n");
        sb.append("    H -->|低风险| I[\"WorkerAgent 输出 Step 结果\"]\n");
        sb.append("    H -->|高风险| J[\"SubReviewer 子评审\"]\n");
        sb.append("    J --> K{\"子评审通过?\"}\n");
        sb.append("    K -->|通过| I\n");
        sb.append("    K -->|需修改且未超限| G\n");
        sb.append("    K -->|迭代超限| L[\"标记瑕疵放行\"]\n");
        sb.append("    I --> Z{\"DAG 是否全部完成?\"}\n");
        sb.append("    L --> Z\n");
        sb.append("    Z -->|否| X\n");
        sb.append("    Z -->|是| M[\"MergeAgent 聚合对齐\"]\n");
        sb.append("    M --> N[\"冲突检测与统一规范\"]\n");
        sb.append("    N --> U{\"存在冲突?\"}\n");
        sb.append("    U -->|是| V[\"Planner 仲裁分歧\"]\n");
        sb.append("    V --> M\n");
        sb.append("    U -->|否| O[\"GlobalReviewer 全局终审\"]\n");
        sb.append("    O --> P{\"终审结果\"}\n");
        sb.append("    P -->|通过| Q[\"标准化最终报告\"]\n");
        sb.append("    P -->|局部瑕疵| PART[\"局部子图重规划\"]\n");
        sb.append("    PART --> F\n");
        sb.append("    P -->|彻底不合格| R[\"任务回溯重规划\"]\n");
        sb.append("    R --> B\n");
        sb.append("    P -->|需求不清| W\n");
        sb.append("    Q --> SAVE[\"写入 Team Run 最终结果\"]\n");
        sb.append("    F --> S[\"超时熔断/限流/异常兜底\"]\n");
        sb.append("    F --> T[\"深度与迭代次数拦截\"]\n");
        sb.append("    classDef active fill:#116b5b,stroke:#26d7a5,color:#ffffff,stroke-width:2px;\n");
        sb.append("    classDef failed fill:#5c1b1b,stroke:#ff6b6b,color:#ffffff,stroke-width:2px;\n");
        sb.append("    classDef guard fill:#2b234d,stroke:#9d8cff,color:#ffffff;\n");
        sb.append("    class S,T guard;\n");
        if (status == TeamRunStatus.FAILED) {
            sb.append("    class R failed;\n");
        } else if (status == TeamRunStatus.COMPLETED) {
            sb.append("    class Q,SAVE active;\n");
        } else if (!active.isBlank()) {
            sb.append("    class ").append(active).append(" active;\n");
        }
        return sb.toString();
    }

    private static String activeNode(TeamRunStatus status, String taskLevel, List<TeamEventSnapshot> events) {
        if (status == null) {
            return "A";
        }
        if ("SIMPLE".equalsIgnoreCase(taskLevel) && status == TeamRunStatus.EXECUTING) {
            return "D";
        }
        return switch (status) {
            case PENDING -> "A";
            case PLANNING -> "B";
            case PLAN_REVIEWING -> "PR";
            case EXECUTING -> executingNode(events);
            case MERGING -> mergingNode(events);
            case GLOBAL_REVIEWING, RESULT_REVIEWING -> "O";
            case NEEDS_CLARIFICATION -> "W";
            case COMPLETED -> "SAVE";
            case FAILED -> "R";
        };
    }

    private static String executingNode(List<TeamEventSnapshot> events) {
        if (hasEvent(events, "STEP_SUB_REVIEW_STARTED")) {
            return "J";
        }
        if (hasEvent(events, "RISK_DECIDED")) {
            return "H";
        }
        if (hasEvent(events, "AGENT_SELECTED")) {
            return "AS";
        }
        if (hasEvent(events, "TEAM_CONTROL_STARTED")) {
            return "F";
        }
        return "G";
    }

    private static String mergingNode(List<TeamEventSnapshot> events) {
        if (hasEvent(events, "ARBITRATION_STARTED") || hasEvent(events, "ARBITRATION_COMPLETED")) {
            return "V";
        }
        if (hasEvent(events, "CONFLICT_DETECTED")) {
            return "N";
        }
        return "M";
    }

    private static boolean hasEvent(List<TeamEventSnapshot> events, String type) {
        return events.stream()
            .anyMatch(event -> event.type() != null && type.equals(event.type().name()));
    }
}
