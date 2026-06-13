package com.echomind.agent.team.visualization;

import com.echomind.agent.team.runtime.TeamEventSnapshot;
import com.echomind.agent.team.runtime.TeamStepSnapshot;
import com.echomind.agent.team.state.TeamEventType;
import com.echomind.agent.team.state.TeamRunStatus;
import com.echomind.agent.team.state.TeamStepQualityStatus;
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
        boolean rawHasMerge = occurred.contains(TeamEventType.MERGE_STARTED);
        boolean rawHasConflict = occurred.contains(TeamEventType.CONFLICT_DETECTED);
        boolean rawHasArbitration = occurred.contains(TeamEventType.ARBITRATION_STARTED);
        boolean rawHasGlobalReview = occurred.contains(TeamEventType.GLOBAL_REVIEW_STARTED);
        boolean stepsComplete = stepsComplete(safeSteps);
        boolean inconsistentPostStep = !stepsComplete
            && (rawHasMerge || rawHasConflict || rawHasArbitration || rawHasGlobalReview
                || status == TeamRunStatus.COMPLETED);
        boolean hasMerge = rawHasMerge && !inconsistentPostStep;
        boolean hasConflict = rawHasConflict && !inconsistentPostStep;
        boolean hasArbitration = rawHasArbitration && !inconsistentPostStep;
        boolean hasGlobalReview = rawHasGlobalReview && !inconsistentPostStep;

        StringBuilder sb = new StringBuilder();
        sb.append("flowchart TD\n");
        appendClasses(sb);

        sb.append("    START[\"").append(escapeLabel(header(teamName, status, taskLevel, planRetryCount))).append("\"]\n");

        if (safeSteps.isEmpty()) {
            sb.append("    PLAN_PHASE[\"").append(escapeLabel(emptyPlanLabel(status))).append("\"]\n");
            sb.append("    START --> PLAN_PHASE\n");
            sb.append("    class START start;\n");
            sb.append("    class PLAN_PHASE ").append(emptyPlanClass(status)).append(";\n");
            return sb.toString();
        }

        Map<String, String> nodeIds = new LinkedHashMap<>();
        Map<String, String> subReviewNodeIds = new LinkedHashMap<>();
        for (int i = 0; i < safeSteps.size(); i++) {
            TeamStepSnapshot step = safeSteps.get(i);
            String nodeId = "S" + (i + 1);
            nodeIds.put(step.stepId(), nodeId);
            nodeIds.put(step.clientStepId(), nodeId);
            sb.append("    ").append(nodeId).append("[\"").append(escapeLabel(stepLabel(step))).append("\"]\n");
            // Add SubReview node for each step if SubReview events occurred or qualityStatus indicates review
            if (hasSubReview(step, occurred)) {
                String subNodeId = "SUB_S" + (i + 1);
                subReviewNodeIds.put(step.stepId(), subNodeId);
                sb.append("    ").append(subNodeId).append("{{\"").append(escapeLabel(subReviewLabel(step, occurred))).append("\"}}\n");
            }
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
        if (inconsistentPostStep) {
            sb.append("    INCONSISTENT[\"")
                .append(escapeLabel("状态不一致<br/>Step 未完成，后续阶段已阻断"))
                .append("\"]\n");
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
            } else {
                for (String dependency : dependencies) {
                    String dependencyNode = nodeIds.get(dependency);
                    if (dependencyNode == null || dependencyNode.equals(nodeId)) {
                        edges.add("    " + stepSource + " -->|" + "未解析依赖" + "| " + nodeId);
                        continue;
                    }
                    // Route through SubReview node if dependency has one
                    String sourceNode = subReviewNodeIds.getOrDefault(dependency, dependencyNode);
                    edges.add("    " + sourceNode + " --> " + nodeId);
                    int dependencyIndex = indexOfNode(safeSteps, nodeIds, dependencyNode);
                    if (dependencyIndex >= 0) {
                        hasOutgoing[dependencyIndex] = true;
                    }
                }
            }
            // Connect step to its SubReview node
            if (subReviewNodeIds.containsKey(step.stepId())) {
                edges.add("    " + nodeId + " --> " + subReviewNodeIds.get(step.stepId()));
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
        // If the last step has a SubReview node, route through it
        String lastEffectiveNode = lastStepNode;
        for (int i = safeSteps.size() - 1; i >= 0; i--) {
            String nodeId = "S" + (i + 1);
            if (nodeId.equals(lastStepNode) && subReviewNodeIds.containsKey(safeSteps.get(i).stepId())) {
                lastEffectiveNode = subReviewNodeIds.get(safeSteps.get(i).stepId());
                break;
            }
        }

        if (inconsistentPostStep) {
            edges.add("    " + lastEffectiveNode + " --> INCONSISTENT");
        } else if (hasMerge) {
            edges.add("    " + lastEffectiveNode + " --> MERGE");
        }

        String mergeOrLastStep = hasMerge ? "MERGE" : lastEffectiveNode;

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

        String terminalNode = inconsistentPostStep ? null : appendTerminal(sb, status, safeSteps, safeEvents, hasOutgoing);
        if (terminalNode != null) {
            if (status == TeamRunStatus.FAILED) {
                List<Integer> failed = failedStepIndexes(safeSteps);
                if (!failed.isEmpty()) {
                    for (Integer index : failed) {
                        String stepNodeId = "S" + (index + 1);
                        // Route through SubReview if the failed step has one
                        String source = subReviewNodeIds.getOrDefault(safeSteps.get(index).stepId(), stepNodeId);
                        edges.add("    " + source + " --> " + terminalNode);
                    }
                } else {
                    edges.add("    " + postGlobalReview + " --> " + terminalNode);
                }
            } else {
                edges.add("    " + postGlobalReview + " --> " + terminalNode);
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
        if (inconsistentPostStep) sb.append("    class INCONSISTENT state_warning;\n");
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
        sb.append("    classDef phase_plan_retry fill:#4a2500,stroke:#fb923c,color:#fed7aa,stroke-width:2px;\n");
        sb.append("    classDef phase_sub_review fill:#1e3a5f,stroke:#60a5fa,color:#dbeafe,stroke-width:2px;\n");
        sb.append("    classDef state_warning fill:#4a3000,stroke:#fbbf24,color:#fef3c7,stroke-width:2px;\n");
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
        sb.append("    INPROGRESS[\"进行中...\"]\n");
        sb.append("    class INPROGRESS running;\n");
        return "INPROGRESS";
    }

    private static void appendStepClasses(StringBuilder sb, List<TeamStepSnapshot> steps) {
        for (int i = 0; i < steps.size(); i++) {
            sb.append("    class S").append(i + 1).append(" ").append(className(steps.get(i).status())).append(";\n");
            TeamStepQualityStatus qs = steps.get(i).qualityStatus();
            if (qs != null && qs != TeamStepQualityStatus.PENDING) {
                sb.append("    class SUB_S").append(i + 1).append(" phase_sub_review;\n");
            }
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
        String base = "#" + step.stepIndex() + " " + blankToDefault(step.title(), "未命名 Step")
            + "<br/>状态：" + stepStatus(step.status())
            + "<br/>Executor：" + blankToDefault(step.assignedAgentId(), "未分配")
            + "<br/>重试：" + step.retryCount();
        TeamStepQualityStatus qs = step.qualityStatus();
        if (qs != null && qs != TeamStepQualityStatus.PENDING) {
            base += "<br/>审查：" + qualityStatusLabel(qs);
        }
        return base;
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

    private static boolean stepsComplete(List<TeamStepSnapshot> steps) {
        boolean hasActiveStep = false;
        for (TeamStepSnapshot step : steps) {
            if (step.status() == TeamStepStatus.SUPERSEDED) {
                continue;
            }
            hasActiveStep = true;
            if (step.status() != TeamStepStatus.COMPLETED) {
                return false;
            }
        }
        return hasActiveStep;
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

    private static String qualityStatusLabel(TeamStepQualityStatus qualityStatus) {
        if (qualityStatus == null) {
            return "-";
        }
        return switch (qualityStatus) {
            case PENDING -> "待审查";
            case PASSED -> "通过";
            case FLAWED_ACCEPTED -> "有风险放行";
            case REVIEW_SKIPPED -> "跳过";
            case RETRY_REQUESTED -> "重试中";
            case FAILED -> "失败";
        };
    }

    private static String emptyPlanLabel(TeamRunStatus status) {
        if (status == TeamRunStatus.PLANNING) {
            return "规划执行中<br/>Planner 正在生成本次 DAG";
        }
        if (status == TeamRunStatus.PENDING) {
            return "等待调度<br/>Planner 尚未开始";
        }
        if (status == TeamRunStatus.FAILED) {
            return "Run 失败<br/>未生成 Step";
        }
        return "等待 Planner 输出<br/>暂无 Step";
    }

    private static String emptyPlanClass(TeamRunStatus status) {
        if (status == TeamRunStatus.PLANNING) {
            return "running";
        }
        if (status == TeamRunStatus.FAILED) {
            return "failed";
        }
        return "waiting";
    }

    private static String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static boolean hasSubReview(TeamStepSnapshot step, Set<TeamEventType> occurred) {
        TeamStepQualityStatus qs = step.qualityStatus();
        // Only show SubReview when it actually happened (not PENDING/null)
        return qs != null && qs != TeamStepQualityStatus.PENDING;
    }

    private static String subReviewLabel(TeamStepSnapshot step, Set<TeamEventType> occurred) {
        String reviewOutcome;
        if (step.qualityStatus() == null) {
            reviewOutcome = "待审查";
        } else {
            reviewOutcome = switch (step.qualityStatus()) {
                case PENDING -> "待审查";
                case PASSED -> "通过";
                case FLAWED_ACCEPTED -> "有风险放行";
                case REVIEW_SKIPPED -> "跳过审查";
                case RETRY_REQUESTED -> "重试中";
                case FAILED -> "审查失败";
            };
        }
        String label = "SubReview #" + step.stepIndex() + "<br/>" + reviewOutcome;
        if (step.lastReviewReason() != null && !step.lastReviewReason().isBlank()) {
            String reason = step.lastReviewReason().length() > 30
                ? step.lastReviewReason().substring(0, 30) + "..." : step.lastReviewReason();
            label += "<br/>" + reason;
        }
        return label;
    }

    private static String escapeLabel(String value) {
        return blankToDefault(value, "-")
            .replace("\\", "\\\\")
            .replace("\"", "＂")
            .replace("\r", " ")
            .replace("\n", "<br/>");
    }
}
