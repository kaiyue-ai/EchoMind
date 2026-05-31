package com.echomind.agent.team.runtime;

import com.echomind.agent.team.model.PlannedStep;
import com.echomind.agent.team.model.TeamMember;
import com.echomind.agent.team.state.TeamRole;
import com.echomind.agent.team.store.TeamRunEntity;
import com.echomind.agent.team.store.TeamStepEntity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Team 运行时的 Prompt 生成器。
 *
 * <p>这里不访问数据库、不调用模型，只负责把当前 Run/Step 快照整理成稳定提示词。</p>
 */
final class TeamPromptFactory {

    private TeamPromptFactory() {
    }

    static String planner(TeamRunEntity run, TeamSnapshot team, List<PlannedStep> previousPlan,
                          String plannerRevisionInstructions, List<TeamStepSnapshot> previousExecutionResults,
                          TeamJsonSupport json) {
        return """
            You are the Planner in an Agent Team. For SIMPLE tasks create exactly 1 executable subtask; for COMPLEX tasks decompose into 2-7 executable subtasks.
            HARD LIMIT: maximum 7 steps. Plans with more than 7 steps will be rejected. Combine related items into one step rather than creating many small steps.
            Write all user-visible text in Simplified Chinese.
            Plan only information-gathering or analysis subtasks for Executors.
            Do not create a final integration, final report, final方案整合, 汇总输出, or 可执行方案撰写 step. The Reviewer is responsible for the final report.
            Decide taskLevel as SIMPLE only when one direct worker result is enough; otherwise use COMPLEX.
            Build a DAG: clientStepId is stable within this plan, dependsOn references previous clientStepId values, and dependency cycles are forbidden.
            Set riskLevel to HIGH only when the Step needs reviewer validation before downstream work can trust it; otherwise LOW.
            Respond only with JSON:
            {
              "taskLevel": "SIMPLE | COMPLEX",
              "steps": [
                {
                  "clientStepId": "stable-id",
                  "title": "short title",
                  "description": "specific executor task",
                  "dependsOn": ["clientStepId"],
                  "riskLevel": "LOW | HIGH",
                  "riskReason": "why high risk, empty if low",
                  "requiredCapabilities": ["search", "weather", "coordination"],
                  "acceptanceCriteria": "what a good result must contain"
                }
              ]
            }

            User task:
            %s

            User clarification, if any:
            %s

            Previous plan, if Reviewer requested replanning:
            %s

            Reviewer revision instructions, if any:
            %s

            Previous executor results, if replanning after result review:
            %s

            Available executor capabilities:
            %s
            """.formatted(
            run.getTask(),
            nullToBlank(run.getClarificationAnswer()),
            json.toJson(previousPlan == null ? List.of() : previousPlan),
            nullToBlank(plannerRevisionInstructions),
            json.toJson(previousExecutionResults == null ? List.of() : previousExecutionResults),
            executorCapabilities(team, json)
        );
    }

    static String planReviewer(TeamRunEntity run, List<PlannedStep> planned, TeamJsonSupport json) {
        return """
            You are the Reviewer and quality gate for an Agent Team.
            Review whether the Planner's subtasks cover the original user need, are actionable, and have clear capabilities.
            Write reason, questions, revisionInstructions, and finalReport in Simplified Chinese.
            Respond as one single-line valid JSON object only.
            Text fields must not contain raw line breaks. Encode line breaks as \\n only when needed.
            Do not use ASCII double quotes inside text values; use Chinese corner quotes 「」 instead.
            Respond only with JSON:
            {
              "action": "CONTINUE | RETRY | ASK_CLARIFICATION",
              "reason": "why",
              "questions": ["only if clarification is needed"],
              "retryStepIds": [],
              "revisionInstructions": "instructions for Planner if retry is needed",
              "finalReport": ""
            }

            Original task:
            %s

            User clarification, if any:
            %s

            Planned steps:
            %s
            """.formatted(run.getTask(), nullToBlank(run.getClarificationAnswer()), json.toJson(planned));
    }

    static String agentSelector(TeamRunEntity run, TeamStepEntity step, List<String> requiredCapabilities,
                                String dependencyOutputs, List<TeamAgentSelection> candidates,
                                TeamJsonSupport json) {
        return """
            你是 Agent Team 的 AgentSelector，由 Planner 负责为当前 Step 自主选择一个 Executor 成员。
            候选列表里的 memberKey 是唯一标识；如果多个候选使用同一个 agentId，必须用 memberKey 区分。
            能力匹配分只是参考，不要机械照抄；请结合用户任务、Step 描述、能力标签、依赖输出摘要和验收标准决策。
            只能从 candidates 中选择一个 memberKey，不要编造候选。
            请只返回单行 JSON：
            {
              "memberKey": "候选 memberKey",
              "agentId": "候选 agentId",
              "reason": "中文说明为什么这个 Executor 最合适"
            }

            原始用户任务：
            %s

            当前 Step：
            %s

            Step 依赖输出摘要：
            %s

            Executor 候选：
            %s
            """.formatted(
            run.getTask(),
            json.toJson(Map.of(
                "stepId", step.getStepId(),
                "title", nullToBlank(step.getTitle()),
                "description", nullToBlank(step.getDescription()),
                "requiredCapabilities", requiredCapabilities,
                "acceptanceCriteria", nullToBlank(step.getAcceptanceCriteria()),
                "riskLevel", step.getRiskLevel() == null ? "" : step.getRiskLevel().name()
            )),
            abbreviate(dependencyOutputs, 1200),
            json.toJson(candidates)
        );
    }

    static String subReviewer(TeamRunEntity run, TeamStepEntity step) {
        return """
            你是 Agent Team 的 SubReviewer，负责审查单个高风险 Step 的原始输出。
            请对照原始任务、Step 描述和验收标准，判断该 Step 是否可被下游依赖使用。
            如果需要重试，必须给出可执行的 Reflexion 修改意见。
            请只返回 JSON：
            {
              "action": "CONTINUE | RETRY | ASK_CLARIFICATION",
              "reason": "中文审查原因",
              "questions": ["需要用户补充时填写"],
              "retryStepIds": [],
              "revisionInstructions": "重试修改意见",
              "stepReflections": {
                "%s": {
                  "failedCriteria": ["未满足的验收标准"],
                  "reviewReason": "失败原因",
                  "revisionInstructions": "具体修正建议",
                  "previousOutputSummary": "上一轮输出摘要",
                  "avoidRepeating": ["避免重复的问题"]
                }
              },
              "finalReport": ""
            }

            原始用户任务：
            %s

            Step 标题：
            %s

            Step 描述：
            %s

            验收标准：
            %s

            风险原因：
            %s

            Executor 原始输出：
            %s
            """.formatted(
            step.getStepId(),
            run.getTask(),
            step.getTitle(),
            nullToBlank(step.getDescription()),
            nullToBlank(step.getAcceptanceCriteria()),
            nullToBlank(step.getRiskReason()),
            nullToBlank(step.getRawOutput())
        );
    }

    static String executor(TeamRunEntity run, TeamStepEntity step, String dependencyOutputs) {
        return """
            You are an Executor in an Agent Team. Complete only the assigned step.
            Use available tools when useful. Return raw findings clearly, not the final report.
            Your answer must be the actual deliverable for this step. Do not return raw tool call markup, DSML tags, JSON tool calls, or a list of search queries.
            When using search tools, call them only for distinct missing facts, then synthesize from the returned evidence.
            Do not keep searching for the same fact. If search evidence is incomplete, state the limitation and still produce the best step deliverable.
            Write the deliverable in Simplified Chinese.

            Original user task:
            %s

            User clarification, if any:
            %s

            Step title:
            %s

            Step description:
            %s

            Acceptance criteria:
            %s

            Dependency outputs, if any:
            %s

            Previous output, if retrying:
            %s

            Reviewer revision instructions, if any:
            %s

            Reflexion context, if retrying:
            %s
            """.formatted(
            run.getTask(),
            nullToBlank(run.getClarificationAnswer()),
            step.getTitle(),
            nullToBlank(step.getDescription()),
            nullToBlank(step.getAcceptanceCriteria()),
            dependencyOutputs,
            nullToBlank(step.getPreviousOutputsJson()),
            nullToBlank(step.getRevisionInstructions()),
            nullToBlank(step.getReflectionJson())
        );
    }

    static String executorToolBudgetFallback(TeamRunEntity run, TeamStepEntity step, String dependencyOutputs,
                                             String failureReason) {
        return """
            你是 Agent Team 的 Executor。上一轮执行已经达到工具调用上限，本轮禁止再调用任何工具。
            你的任务是基于当前可见上下文、依赖结果、验收标准和已有推理，直接生成该 Step 的降级总结。
            不要输出工具调用、搜索关键词或 JSON。不要说“我需要继续搜索”。
            如果信息不足，明确标注限制和不确定项，然后仍然给出当前最好的 Step 交付物。
            使用简体中文。

            工具上限触发原因：
            %s

            Original user task:
            %s

            User clarification, if any:
            %s

            Step title:
            %s

            Step description:
            %s

            Acceptance criteria:
            %s

            Dependency outputs, if any:
            %s

            Previous output, if retrying:
            %s

            Reviewer revision instructions, if any:
            %s

            Reflexion context, if retrying:
            %s
            """.formatted(
            nullToBlank(failureReason),
            run.getTask(),
            nullToBlank(run.getClarificationAnswer()),
            step.getTitle(),
            nullToBlank(step.getDescription()),
            nullToBlank(step.getAcceptanceCriteria()),
            dependencyOutputs,
            nullToBlank(step.getPreviousOutputsJson()),
            nullToBlank(step.getRevisionInstructions()),
            nullToBlank(step.getReflectionJson())
        );
    }

    static String merger(TeamRunEntity run, List<TeamStepSnapshot> steps, TeamJsonSupport json) {
        return """
            你是 Agent Team 的 MergeAgent，负责把多个 Step 的原始结果聚合成一致、可读、可审查的中文草稿。
            请统一口径、指出冲突或瑕疵，不要隐藏 FLAWED_ACCEPTED 的问题。
            忽略状态为 SUPERSEDED 的旧 Step，它们只作为重规划历史。
            如果存在 Planner 仲裁结果，请按仲裁口径统一数字、日期、地点和执行方案。
            只输出聚合后的 Markdown 草稿，不要输出 JSON。

            原始用户任务：
            %s

            User clarification, if any:
            %s

            Step 结果：
            %s

            ConflictDetector 报告：
            %s

            Planner 仲裁结果：
            %s
            """.formatted(
            run.getTask(),
            nullToBlank(run.getClarificationAnswer()),
            json.toJson(steps),
            nullToBlank(run.getConflictReportJson()),
            nullToBlank(run.getArbitrationJson())
        );
    }

    static String conflictDetector(TeamRunEntity run, List<TeamStepSnapshot> steps, String mergeOutput,
                                   TeamJsonSupport json) {
        return """
            你是 Agent Team 的 ConflictDetector，负责在 MergeAgent 聚合后检测事实冲突和口径不一致。
            只判断已经完成且未被 SUPERSEDED 的 Step 与 Merge 草稿之间是否存在冲突。
            重点检查：预算金额、日期时间、地点、人数、天气风险、责任人、前后步骤依赖口径。
            请只返回单行 JSON：
            {
              "hasConflict": true,
              "conflictFields": ["冲突字段"],
              "affectedStepIds": ["涉及 Step ID"],
              "reason": "中文冲突说明",
              "normalizationAdvice": "建议统一口径"
            }

            原始用户任务：
            %s

            Step 结果：
            %s

            MergeAgent 草稿：
            %s
            """.formatted(run.getTask(), json.toJson(steps), nullToBlank(mergeOutput));
    }

    static String arbitration(TeamRunEntity run, TeamConflictReport conflictReport, List<TeamStepSnapshot> steps,
                              TeamJsonSupport json) {
        return """
            你是 PlannerArbitration，负责根据冲突报告给出唯一执行口径。
            请不要重写最终报告，只输出仲裁意见和统一规范。
            输出中文 Markdown，包含：冲突结论、统一口径、MergeAgent 二次聚合注意事项。

            原始用户任务：
            %s

            ConflictDetector 报告：
            %s

            Step 结果：
            %s

            当前 Merge 草稿：
            %s
            """.formatted(
            run.getTask(),
            json.toJson(conflictReport),
            json.toJson(steps),
            nullToBlank(run.getMergeOutput())
        );
    }

    static String resultReviewer(TeamRunEntity run, List<TeamStepSnapshot> steps, TeamJsonSupport json) {
        return """
            You are the Reviewer, quality gate, and final report writer for an Agent Team.
            You are acting as GlobalReviewer. Compare the original user request, MergeAgent draft, and every Executor result.
            Write reason, questions, revisionInstructions, and finalReport in Simplified Chinese.
            If the planned Step structure is still correct but some results are insufficient, return RETRY with the exact retryStepIds and revision instructions.
            If only a local DAG branch needs replanning, return PARTIAL_REPLAN with affectedStepIds and revisionInstructions.
            If the Step structure itself is wrong or missing required work globally, return REPLAN with revisionInstructions for the Planner.
            If user input is ambiguous, return ASK_CLARIFICATION with questions.
            If the result is unrecoverable within the current limits, return FAILED.
            If acceptable, return CONTINUE and write finalReport as a complete user-facing report.
            Any RETRY or PARTIAL_REPLAN must include Reflexion in stepReflections for affected Step IDs.
            Respond as one single-line valid JSON object only.
            Text fields must not contain raw line breaks. Encode line breaks as \\n only when needed.
            Do not use ASCII double quotes inside text values; use Chinese corner quotes 「」 instead.
            Respond only with JSON:
            {
              "action": "CONTINUE | RETRY | PARTIAL_REPLAN | REPLAN | ASK_CLARIFICATION | FAILED",
              "reason": "why",
              "questions": ["only if clarification is needed"],
              "retryStepIds": ["step-id only when action is RETRY"],
              "affectedStepIds": ["step-id only when action is PARTIAL_REPLAN"],
              "revisionInstructions": "instructions for Executor retry or Planner replan",
              "stepReflections": {
                "step-id": {
                  "failedCriteria": ["unmet criteria"],
                  "reviewReason": "why this step failed",
                  "revisionInstructions": "how to fix it",
                  "previousOutputSummary": "summary of bad output",
                  "avoidRepeating": ["mistakes to avoid"]
                }
              },
              "finalReport": "complete final report when action is CONTINUE"
            }

            Original task:
            %s

            User clarification, if any:
            %s

            MergeAgent draft:
            %s

            ConflictDetector report:
            %s

            Planner arbitration, if any:
            %s

            Steps and raw executor outputs:
            %s
            """.formatted(
            run.getTask(),
            nullToBlank(run.getClarificationAnswer()),
            nullToBlank(run.getMergeOutput()),
            nullToBlank(run.getConflictReportJson()),
            nullToBlank(run.getArbitrationJson()),
            json.toJson(steps)
        );
    }

    static String reviewerRepair(String originalPrompt, String invalidResponse, String parseError) {
        return """
            Your previous Reviewer response was rejected because it was not valid JSON.
            Re-evaluate the original Reviewer instruction and return a corrected decision as JSON only.
            Do not copy the invalid response text.
            Return one single-line valid JSON object only.
            Do not add markdown, comments, or prose.
            Text fields must not contain raw line breaks. Encode line breaks as \\n only when needed.
            Do not use ASCII double quotes inside text values; use Chinese corner quotes 「」 instead.
            Required schema:
            {
              "action": "CONTINUE | RETRY | PARTIAL_REPLAN | REPLAN | ASK_CLARIFICATION | FAILED",
              "reason": "why",
              "questions": [],
              "retryStepIds": [],
              "affectedStepIds": [],
              "revisionInstructions": "",
              "stepReflections": {},
              "finalReport": ""
            }

            Parse error:
            %s

            Original Reviewer instruction:
            %s

            Invalid response excerpt, do not copy:
            %s
            """.formatted(nullToBlank(parseError), originalPrompt, abbreviate(nullToBlank(invalidResponse), 1200));
    }

    private static String executorCapabilities(TeamSnapshot team, TeamJsonSupport json) {
        List<Map<String, Object>> capabilities = new ArrayList<>();
        for (TeamMember member : team.members()) {
            if (member.role() == TeamRole.EXECUTOR) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("memberKey", member.agentId() + "#" + member.sortOrder());
                item.put("agentId", member.agentId());
                item.put("agentName", member.agentName());
                item.put("capabilityTags", member.capabilityTags());
                item.put("sortOrder", member.sortOrder());
                capabilities.add(item);
            }
        }
        return json.toJson(capabilities);
    }

    private static String nullToBlank(String value) {
        return value == null ? "" : value;
    }

    private static String abbreviate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value == null ? "" : value;
        }
        return value.substring(0, maxLength) + "...";
    }
}
