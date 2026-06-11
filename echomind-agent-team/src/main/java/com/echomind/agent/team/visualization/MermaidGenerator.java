package com.echomind.agent.team.visualization;

import com.echomind.agent.team.runtime.TeamEventSnapshot;
import com.echomind.agent.team.runtime.TeamStepSnapshot;
import com.echomind.agent.team.state.TeamEventType;
import com.echomind.agent.team.state.TeamRunStatus;
import com.echomind.agent.team.state.TeamStepStatus;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class MermaidGenerator {

    public static String generateFromSnapshots(String teamName, List<TeamStepSnapshot> steps,
                                               List<TeamEventSnapshot> events) {
        return generateFromSnapshots(teamName, TeamRunStatus.PENDING, "COMPLEX", steps, events);
    }

    public static String generateFromSnapshots(String teamName, TeamRunStatus status, String taskLevel,
                                               List<TeamStepSnapshot> steps,
                                               List<TeamEventSnapshot> events) {
        List<TeamStepSnapshot> safeSteps = steps == null ? List.of() : steps;
        List<TeamEventSnapshot> safeEvents = events == null ? List.of() : events;

        Set<TeamEventType> occurred = occurredEvents(safeEvents);
        int planRetryCount = countPlanRetries(safeEvents);
        boolean hasPlanReview = occurred.contains(TeamEventType.PLAN_REVIEW_STARTED);
        boolean hasMerge = occurred.contains(TeamEventType.MERGE_STARTED);
        boolean hasConflict = occurred.contains(TeamEventType.CONFLICT_DETECTED);
        boolean hasArbitration = occurred.contains(TeamEventType.ARBITRATION_STARTED);
        boolean hasGlobalReview = occurred.contains(TeamEventType.GLOBAL_REVIEW_STARTED);
        boolean hasClarification = occurred.contains(TeamEventType.CLARIFICATION_REQUESTED);

        StringBuilder sb = new StringBuilder();
        sb.append("flowchart TD\n");
        appendClasses(sb);

        sb.append("    START[\"").append(escapeLabel(header(teamName, status, taskLevel, planRetryCount))).append("\"]\n");

        if (safeSteps.isEmpty()) {
            sb.append("    EMPTY[\"暂无 Step<br/>等待 Planner 生成本次 DAG\"]\n");
            sb.append("    START --> EMPTY\n");
            sb.append("    class START start;\n");
            sb.append("    class EMPTY waiting;\n");
            return sb.toString();
        }

        Map<String, String> nodeIds = new LinkedHashMap<>();
        for (int i = 0; i < safeSteps.size(); i++) {
            TeamStepSnapshot step = safeSteps.get(i);
            String nodeId = "S" + (i + 1);
            nodeIds.put(step.stepId(), nodeId);
            nodeIds.put(step.clientStepId(), nodeId);
            sb.append("    ").append(nodeId).append("[\"").append(escapeLabel(stepLabel(step))).append("\"]\n");
        }

        if (planRetryCount > 0) {
            sb.append("    PLAN_RETRY{{\"").append(escapeLabel("规划重试<br/>次数：" + planRetryCount)).append("\"}}\n");
        }
        if (hasPlanReview) {
            sb.append("    PLAN_REVIEW{{\"").append(escapeLabel(phaseLabel("规划审查", occurred, TeamEventType.PLAN_REVIEW_STARTED, TeamEventType.PLAN_REVIEWED))).append("\"}}\n");
        }
        if (hasMerge) {
            sb.append("    MERGE{{\"").append(escapeLabel(phaseLabel("结果聚合", occurred, TeamEventType.MERGE_STARTED, TeamEventType.MERGE_COMPLETED))).append("\"}}\n");
        }
        if (hasConflict) {
            sb.append("    CONFLICT{{\"").append(escapeLabel("冲突检测<br/>" + conflictSummary(safeEvents))).append("\"}}\n");
        }
        if (hasArbitration) {
            sb.append("    ARBITRATION{{\"").append(escapeLabel(phaseLabel("冲突仲裁", occurred, TeamEventType.ARBITRATION_STARTED, TeamEventType.ARBITRATION_COMPLETED))).append("\"}}\n");
        }
        if (hasGlobalReview) {
            sb.append("    GLOBAL_REVIEW{{\"").append(escapeLabel(phaseLabel("全局终审", occurred, TeamEventType.GLOBAL_REVIEW_STARTED, TeamEventType.GLOBAL_REVIEWED))).append("\"}}\n");
        }
        if (hasClarification) {
            String clarificationMsg = clarificationQuestion(safeEvents);
            sb.append("    CLARIFICATION{{\"").append(escapeLabel("等待澄清<br/>" + blankToDefault(clarificationMsg, "用户需回答问题"))).append("\"}}\n");
        }

        boolean[] hasOutgoing = new boolean[safeSteps.size()];
        List<String> edges = new ArrayList<>();

        String stepSource;
        if (hasPlanReview) {
            stepSource = "PLAN_REVIEW";
        } else if (planRetryCount > 0) {
            stepSource = "PLAN_RETRY";
        } else {
            stepSource = "START";
        }
        for (int i = 0; i < safeSteps.size(); i++) {
            TeamStepSnapshot step = safeSteps.get(i);
            String nodeId = "S" + (i + 1);
            List<String> dependencies = step.dependsOnStepIds() == null ? List.of() : step.dependsOnStepIds();
            if (dependencies.isEmpty()) {
                edges.add("    " + stepSource + " --> " + nodeId);
                continue;
            }
            for (String dependency : dependencies) {
                String dependencyNode = nodeIds.get(dependency);
                if (dependencyNode == null || dependencyNode.equals(nodeId)) {
                    edges.add("    " + stepSource + " -->|" + "未解析依赖" + "| " + nodeId);
                    continue;
                }
                edges.add("    " + dependencyNode + " --> " + nodeId);
                int dependencyIndex = indexOfNode(safeSteps, nodeIds, dependencyNode);
                if (dependencyIndex >= 0) {
                    hasOutgoing[dependencyIndex] = true;
                }
            }
        }

        if (planRetryCount > 0) {
            edges.add("    START --> PLAN_RETRY");
            if (hasPlanReview) {
                edges.add("    PLAN_RETRY --> PLAN_REVIEW");
            }
        }
        if (hasPlanReview && planRetryCount == 0) {
            edges.add("    START --> PLAN_REVIEW");
        }

        String lastStepNode = findLastStepNode(safeSteps, hasOutgoing);

        if (hasMerge) {
            edges.add("    " + lastStepNode + " --> MERGE");
        }

        String mergeOrLastStep = hasMerge ? "MERGE" : lastStepNode;

        if (hasConflict) {
            edges.add("    " + mergeOrLastStep + " --> CONFLICT");
            if (hasArbitration) {
                edges.add("    CONFLICT --> ARBITRATION");
            }
        }

        String postConflict = hasConflict ? (hasArbitration ? "ARBITRATION" : "CONFLICT") : mergeOrLastStep;

        if (hasGlobalReview) {
            edges.add("    " + postConflict + " --> GLOBAL_REVIEW");
        }

        String postGlobalReview = hasGlobalReview ? "GLOBAL_REVIEW" : postConflict;

        if (hasClarification) {
            edges.add("    " + postGlobalReview + " --> CLARIFICATION");
        }

        String terminalNode = appendTerminal(sb, status, safeSteps, safeEvents, hasOutgoing);
        if (terminalNode != null) {
            String terminalSource = hasClarification ? "CLARIFICATION" : postGlobalReview;
            if (status == TeamRunStatus.FAILED) {
                List<Integer> failed = failedStepIndexes(safeSteps);
                if (!failed.isEmpty()) {
                    for (Integer index : failed) {
                        edges.add("    S" + (index + 1) + " --> " + terminalNode);
                    }
                } else {
                    edges.add("    " + terminalSource + " --> " + terminalNode);
                }
            } else {
                edges.add("    " + terminalSource + " --> " + terminalNode);
            }
        }

        for (String edge : edges) {
            sb.append(edge).append("\n");
        }

        appendStepClasses(sb, safeSteps);
        if (planRetryCount > 0) sb.append("    class PLAN_RETRY phase_plan_retry;\n");
        if (hasPlanReview) sb.append("    class PLAN_REVIEW phase_review;\n");
        if (hasMerge) sb.append("    class MERGE phase_merge;\n");
        if (hasConflict) sb.append("    class CONFLICT phase_conflict;\n");
        if (hasArbitration) sb.append("    class ARBITRATION phase_arbitration;\n");
        if (hasGlobalReview) sb.append("    class GLOBAL_REVIEW phase_review;\n");
        if (hasClarification) sb.append("    class CLARIFICATION phase_clarification;\n");
        sb.append("    class START start;\n");
        return sb.toString();
    }

    private static Set<TeamEventType> occurredEvents(List<TeamEventSnapshot> events) {
        Set<TeamEventType> set = EnumSet.noneOf(TeamEventType.class);
        for (TeamEventSnapshot e : events) {
            if (e.type() != null) {
                set.add(e.type());
            }
        }
        return set;
    }

    private static int countPlanRetries(List<TeamEventSnapshot> events) {
        return (int) events.stream()
            .filter(e -> e.type() == TeamEventType.RETRY_REQUESTED && e.stepId() == null)
            .count();
    }

    private static String phaseLabel(String phaseName, Set<TeamEventType> occurred,
                                     TeamEventType startEvent, TeamEventType endEvent) {
        boolean started = occurred.contains(startEvent);
        boolean ended = occurred.contains(endEvent);
        String state;
        if (ended) {
            state = "已完成";
        } else if (started) {
            state = "进行中";
        } else {
            state = "待执行";
        }
        return phaseName + "<br/>状态：" + state;
    }

    private static String conflictSummary(List<TeamEventSnapshot> events) {
        for (int i = events.size() - 1; i >= 0; i--) {
            TeamEventSnapshot e = events.get(i);
            if (e.type() == TeamEventType.CONFLICT_DETECTED && e.message() != null && !e.message().isBlank()) {
                return e.message().length() > 40 ? e.message().substring(0, 40) + "..." : e.message();
            }
        }
        return "已检测";
    }

    private static String clarificationQuestion(List<TeamEventSnapshot> events) {
        for (int i = events.size() - 1; i >= 0; i--) {
            TeamEventSnapshot e = events.get(i);
            if (e.type() == TeamEventType.CLARIFICATION_REQUESTED && e.message() != null && !e.message().isBlank()) {
                return e.message().length() > 40 ? e.message().substring(0, 40) + "..." : e.message();
            }
        }
        return "";
    }

    private static String findLastStepNode(List<TeamStepSnapshot> steps, boolean[] hasOutgoing) {
        for (int i = steps.size() - 1; i >= 0; i--) {
            if (!hasOutgoing[i]) {
                return "S" + (i + 1);
            }
        }
        return "START";
    }

    private static void appendClasses(StringBuilder sb) {
        sb.append("    classDef start fill:#111827,stroke:#38bdf8,color:#f8fafc,stroke-width:2px;\n");
        sb.append("    classDef waiting fill:#1f2937,stroke:#64748b,color:#cbd5e1;\n");
        sb.append("    classDef ready fill:#17324a,stroke:#60a5fa,color:#eff6ff;\n");
        sb.append("    classDef running fill:#123c69,stroke:#38bdf8,color:#ffffff,stroke-width:2px;\n");
        sb.append("    classDef completed fill:#14532d,stroke:#34d399,color:#f0fdf4,stroke-width:2px;\n");
        sb.append("    classDef failed fill:#5c1b1b,stroke:#fb7185,color:#fff1f2,stroke-width:2px;\n");
        sb.append("    classDef superseded fill:#3b2a57,stroke:#c4b5fd,color:#f5f3ff;\n");
        sb.append("    classDef terminal fill:#0f172a,stroke:#94a3b8,color:#f8fafc,stroke-width:2px;\n");
        sb.append("    classDef phase_review fill:#1e3a5f,stroke:#60a5fa,color:#dbeafe,stroke-width:2px;\n");
        sb.append("    classDef phase_merge fill:#1a3c34,stroke:#34d399,color:#d1fae5,stroke-width:2px;\n");
        sb.append("    classDef phase_conflict fill:#4a3000,stroke:#fbbf24,color:#fef3c7,stroke-width:2px;\n");
        sb.append("    classDef phase_arbitration fill:#4a1d1d,stroke:#f87171,color:#fee2e2,stroke-width:2px;\n");
        sb.append("    classDef phase_clarification fill:#3b2a57,stroke:#c4b5fd,color:#f5f3ff,stroke-width:2px;\n");
        sb.append("    classDef phase_plan_retry fill:#4a2500,stroke:#fb923c,color:#fed7aa,stroke-width:2px;\n");
    }

    private static String appendTerminal(StringBuilder sb, TeamRunStatus status, List<TeamStepSnapshot> steps,
                                         List<TeamEventSnapshot> events, boolean[] hasOutgoing) {
        if (status == TeamRunStatus.FAILED) {
            String reason = failureReason(events);
            sb.append("    FAIL[\"").append(escapeLabel("Run 失败<br/>" + reason)).append("\"]\n");
            sb.append("    class FAIL failed;\n");
            return "FAIL";
        }
        if (status == TeamRunStatus.COMPLETED) {
            sb.append("    END[\"Run 完成<br/>最终报告已生成\"]\n");
            sb.append("    class END completed;\n");
            return "END";
        }
        if (status == TeamRunStatus.NEEDS_CLARIFICATION) {
            sb.append("    WAIT[\"等待用户澄清\"]\n");
            sb.append("    class WAIT terminal;\n");
            return "WAIT";
        }
        sb.append("    INPROGRESS[\"进行中...\"]\n");
        sb.append("    class INPROGRESS running;\n");
        return "INPROGRESS";
    }

    private static void appendStepClasses(StringBuilder sb, List<TeamStepSnapshot> steps) {
        for (int i = 0; i < steps.size(); i++) {
            sb.append("    class S").append(i + 1).append(" ").append(className(steps.get(i).status())).append(";\n");
        }
    }

    private static String className(TeamStepStatus status) {
        if (status == null) {
            return "waiting";
        }
        return switch (status) {
            case COMPLETED -> "completed";
            case FAILED -> "failed";
            case RUNNING, ASSIGNED, RETRYING -> "running";
            case READY -> "ready";
            case SUPERSEDED -> "superseded";
            case PENDING, BLOCKED -> "waiting";
        };
    }

    private static String header(String teamName, TeamRunStatus status, String taskLevel, int planRetryCount) {
        String base = "本次协作流程<br/>团队：" + blankToDefault(teamName, "-")
            + "<br/>状态：" + runStatus(status)
            + "<br/>任务等级：" + blankToDefault(taskLevel, "-");
        if (planRetryCount > 0) {
            base += "<br/>规划重试：" + planRetryCount;
        }
        return base;
    }

    private static String stepLabel(TeamStepSnapshot step) {
        return "#" + step.stepIndex() + " " + blankToDefault(step.title(), "未命名 Step")
            + "<br/>状态：" + stepStatus(step.status())
            + "<br/>Executor：" + blankToDefault(step.assignedAgentId(), "未分配")
            + "<br/>重试：" + step.retryCount();
    }

    private static String failureReason(List<TeamEventSnapshot> events) {
        for (int i = events.size() - 1; i >= 0; i--) {
            TeamEventSnapshot event = events.get(i);
            if (event.type() == TeamEventType.RUN_FAILED && event.message() != null && !event.message().isBlank()) {
                return event.message().replaceFirst("^Run failed:\\s*", "");
            }
        }
        return "未记录失败原因";
    }

    private static List<Integer> failedStepIndexes(List<TeamStepSnapshot> steps) {
        List<Integer> indexes = new ArrayList<>();
        for (int i = 0; i < steps.size(); i++) {
            if (steps.get(i).status() == TeamStepStatus.FAILED) {
                indexes.add(i);
            }
        }
        return indexes;
    }

    private static int indexOfNode(List<TeamStepSnapshot> steps, Map<String, String> nodeIds, String nodeId) {
        for (int i = 0; i < steps.size(); i++) {
            TeamStepSnapshot step = steps.get(i);
            if (nodeId.equals(nodeIds.get(step.stepId())) || nodeId.equals(nodeIds.get(step.clientStepId()))) {
                return i;
            }
        }
        return -1;
    }

    private static String runStatus(TeamRunStatus status) {
        if (status == null) {
            return "-";
        }
        return switch (status) {
            case PENDING -> "待调度";
            case PLANNING -> "规划中";
            case PLAN_REVIEWING -> "规划审查中";
            case EXECUTING -> "执行中";
            case MERGING -> "聚合中";
            case GLOBAL_REVIEWING -> "全局终审中";
            case NEEDS_CLARIFICATION -> "等待用户澄清";
            case COMPLETED -> "已完成";
            case FAILED -> "失败";
        };
    }

    private static String stepStatus(TeamStepStatus status) {
        if (status == null) {
            return "-";
        }
        return switch (status) {
            case PENDING -> "待开始";
            case BLOCKED -> "等待依赖";
            case READY -> "可执行";
            case ASSIGNED -> "已分配";
            case RUNNING -> "执行中";
            case RETRYING -> "重试中";
            case COMPLETED -> "已完成";
            case FAILED -> "失败";
            case SUPERSEDED -> "已替换";
        };
    }

    private static String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String escapeLabel(String value) {
        return blankToDefault(value, "-")
            .replace("\\", "\\\\")
            .replace("\"", "＂")
            .replace("\r", " ")
            .replace("\n", "<br/>");
    }
}
