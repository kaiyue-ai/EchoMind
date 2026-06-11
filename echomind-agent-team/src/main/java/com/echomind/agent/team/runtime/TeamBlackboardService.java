package com.echomind.agent.team.runtime;

import com.echomind.agent.Agent;
import com.echomind.agent.AgentFactory;
import com.echomind.agent.orchestration.AgentOrchestrator;
import com.echomind.agent.pipeline.PipelineContext;
import com.echomind.agent.team.model.PlannedStep;
import com.echomind.agent.team.model.ReviewerDecision;
import com.echomind.agent.team.model.StepReflection;
import com.echomind.agent.team.model.TeamMember;
import com.echomind.agent.team.state.ReviewerAction;
import com.echomind.agent.team.state.TeamClarificationStage;
import com.echomind.agent.team.state.TeamEventType;
import com.echomind.agent.team.state.TeamRiskLevel;
import com.echomind.agent.team.state.TeamRole;
import com.echomind.agent.team.state.TeamRunStatus;
import com.echomind.agent.team.state.TeamStepQualityStatus;
import com.echomind.agent.team.state.TeamStepStatus;
import com.echomind.agent.team.state.TeamTaskLevel;
import com.echomind.agent.team.store.TeamEntity;
import com.echomind.agent.team.store.TeamEventEntity;
import com.echomind.agent.team.store.TeamEventMapper;
import com.echomind.agent.team.store.TeamMemberEntity;
import com.echomind.agent.team.store.TeamMemberMapper;
import com.echomind.agent.team.store.TeamMapper;
import com.echomind.agent.team.store.TeamRunEntity;
import com.echomind.agent.team.store.TeamRunMapper;
import com.echomind.agent.team.store.TeamStepEntity;
import com.echomind.agent.team.store.TeamStepMapper;
import com.echomind.agent.team.visualization.MermaidGenerator;
import com.echomind.common.exception.ModelInvocationRejectedException;
import com.echomind.common.model.ErrorDetail;
import com.echomind.common.observability.EchoMindTrace;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Agent Team 的 MySQL 黑板和异步状态机服务。
 *
 * <p>这里保留 Team Run 的事务性编排：创建 Run、推进 DAG、写 Step/Event、生成快照。
 * Prompt 文本放在 {@link TeamPromptFactory}，Executor 选择放在 {@link TeamAgentSelector}，
 * 本类尽量只处理状态流转和持久化。</p>
 */
@Slf4j
@Service
public class TeamBlackboardService {

    private static final String OP_PLANNER = "echomind.team.planner";
    private static final String OP_REVIEWER = "echomind.team.reviewer";
    private static final String OP_EXECUTOR = "echomind.team.executor";
    private static final String OP_SUB_REVIEWER = "echomind.team.sub_reviewer";
    private static final String OP_MERGE = "echomind.team.merge";
    private static final String OP_CONFLICT_DETECTOR = "echomind.team.conflict_detector";
    private static final String OP_ARBITRATION = "echomind.team.arbitration";
    private static final String OP_REPAIR = "echomind.team.repair";

    private enum ReviewerDecisionScope {
        PLAN,
        STEP,
        GLOBAL
    }

    // 数据库操作
    private final TeamMapper teamMapper;
    // 团队成员操作
    private final TeamMemberMapper memberMapper;
    // 团队运行操作
    private final TeamRunMapper runMapper;
    // 团队步骤操作
    private final TeamStepMapper stepMapper;
    // 团队时间操作
    private final TeamEventMapper eventMapper;
    // agent创建工厂
    private final AgentFactory agentFactory;
    // agent 编排器
    private final AgentOrchestrator orchestrator;
    // 任务执行器
    private final TeamExecutionEngine executionEngine;
    // 运行时属性
    private final TeamRuntimeProperties runtimeProperties;
    // Team 内部模型调用的用量与配额治理端口，由 console 层实现。
    private TeamUsageRecorder usageRecorder = TeamUsageRecorder.NOOP;

    // JSON 支持类
    private final TeamJsonSupport json = new TeamJsonSupport();
    // agent选择器(根据tool来进行挑选)
    private final TeamAgentSelector agentSelector = new TeamAgentSelector();
    // 风险策略
    private final TeamRiskPolicy riskPolicy = new TeamRiskPolicy();

    // 删除 Team/Run 后，后台异步任务可能仍在收尾；用它阻止继续写事件。
    private final Set<String> deletedRunIds = ConcurrentHashMap.newKeySet();

    public TeamBlackboardService(TeamMapper teamMapper,
                                 TeamMemberMapper memberMapper,
                                 TeamRunMapper runMapper,
                                 TeamStepMapper stepMapper,
                                 TeamEventMapper eventMapper,
                                 AgentFactory agentFactory,
                                 AgentOrchestrator orchestrator) {
        this(teamMapper, memberMapper, runMapper, stepMapper, eventMapper,
            agentFactory, orchestrator, null, new TeamRuntimeProperties());
    }

    @Autowired
    public TeamBlackboardService(TeamMapper teamMapper,
                                 TeamMemberMapper memberMapper,
                                 TeamRunMapper runMapper,
                                 TeamStepMapper stepMapper,
                                 TeamEventMapper eventMapper,
                                 AgentFactory agentFactory,
                                 AgentOrchestrator orchestrator,
                                 TeamExecutionEngine executionEngine,
                                 TeamRuntimeProperties runtimeProperties) {
        this.teamMapper = teamMapper;
        this.memberMapper = memberMapper;
        this.runMapper = runMapper;
        this.stepMapper = stepMapper;
        this.eventMapper = eventMapper;
        this.agentFactory = agentFactory;
        this.orchestrator = orchestrator;
        this.executionEngine = executionEngine;
        this.runtimeProperties = runtimeProperties == null ? new TeamRuntimeProperties() : runtimeProperties;
    }

    @Autowired(required = false)
    void setUsageRecorder(TeamUsageRecorder usageRecorder) {
        this.usageRecorder = usageRecorder == null ? TeamUsageRecorder.NOOP : usageRecorder;
    }

    @Transactional(readOnly = true)
    public List<TeamSnapshot> listTeams(String userId) {
        return teamMapper.selectByOwnerUserIdOrderByCreatedAtAsc(userId).stream()
            .map(this::toTeamSnapshot)
            .toList();
    }

    @Transactional(readOnly = true)
    // 根据团队id获取团队快照
    public TeamSnapshot getTeam(String teamId) {
        return teamMapper.selectOptionalById(teamId)
            .map(this::toTeamSnapshot)
            .orElseThrow(() -> new IllegalArgumentException("Team not found: " + teamId));
    }

    @Transactional
    public TeamSnapshot createTeam(String userId, String requestedName, List<TeamMemberSpec> specs) {
        List<TeamMemberSpec> normalized = normalizeSpecs(specs);
        validateMembers(normalized);

        TeamEntity team = new TeamEntity();
        team.setTeamId(UUID.randomUUID().toString());
        team.setOwnerUserId(userId);
        team.setName(requestedName == null || requestedName.isBlank()
            ? "Team-" + UUID.randomUUID().toString().substring(0, 8)
            : requestedName.trim());
        teamMapper.upsertById(team);

        // 创建团队成员实体
        for (TeamMemberSpec spec : normalized) {
            TeamMemberEntity member = new TeamMemberEntity();
            member.setTeamId(team.getTeamId());
            member.setAgentId(spec.agentId());
            member.setRole(spec.role());
            member.setCapabilityTagsJson(json.toJson(spec.capabilityTags() == null ? List.of() : spec.capabilityTags()));
            member.setSortOrder(spec.sortOrder());
            memberMapper.upsertById(member);
        }

        return getTeam(team.getTeamId());
    }

    @Transactional
    public void deleteTeam(String teamId, String userId) {
        if (teamId == null || teamId.isBlank()) {
            throw new IllegalArgumentException("teamId is required");
        }
        TeamEntity team = loadTeamForOwner(teamId, userId);
        List<String> runIds = runMapper.selectByTeamIdOrderByCreatedAtDesc(team.getTeamId()).stream()
            .map(TeamRunEntity::getRunId)
            .toList();
        deletedRunIds.addAll(runIds);
        if (!runIds.isEmpty()) {
            eventMapper.deleteByRunIdIn(runIds);
            stepMapper.deleteByRunIdIn(runIds);
        }
        runMapper.deleteByTeamId(team.getTeamId());
        memberMapper.deleteByTeamId(team.getTeamId());
        teamMapper.deleteEntity(team);
    }

    @Transactional
    // 创建团队协作任务 参数1团队id 参数2任务描述
    public TeamRunSnapshot createRun(String teamId, String task) {
        return createRun(teamId, "default", task, TeamReviewOptions.QUALITY_FIRST);
    }

    @Transactional
    // 创建团队协作任务 参数1团队id 参数2用户id 参数3任务描述
    public TeamRunSnapshot createRun(String teamId, String userId, String task) {
        return createRun(teamId, userId, task, TeamReviewOptions.QUALITY_FIRST);
    }

    @Transactional
    public TeamRunSnapshot createRun(String teamId, String userId, String task, TeamReviewOptions reviewOptions) {
        if (task == null || task.isBlank()) {
            throw new IllegalArgumentException("task is required");
        }
        TeamReviewOptions normalizedOptions = TeamReviewOptions.normalize(reviewOptions);
        // 根据团队id获取团队实体
        loadTeamForOwner(teamId, userId);
        // 创建团队协作任务实体
        TeamRunEntity run = new TeamRunEntity();
        run.setRunId(UUID.randomUUID().toString());
        run.setTeamId(teamId);
        run.setUserId(normalizeUserId(userId));
        run.setTask(task.trim());
        run.setPlanReviewEnabled(normalizedOptions.planReviewEnabled());
        run.setSubReviewEnabled(normalizedOptions.subReviewEnabled());
        run.setGlobalReviewEnabled(normalizedOptions.globalReviewEnabled());
        run.setSimpleFastPathEnabled(normalizedOptions.simpleFastPathEnabled());
        run.setStatus(TeamRunStatus.PENDING);
        runMapper.upsertById(run);
        recordEvent(run.getRunId(), null, TeamEventType.RUN_CREATED, null, null, "Run created", null);
        // Run 先落库，再在事务提交后异步执行，避免后台线程读到未提交数据。
        scheduleRun(run.getRunId());
        return getRun(teamId, userId, run.getRunId());
    }

    @Transactional(readOnly = true)
    // 根据团队id获取团队协作任务快照列表
    // 参数1团队id 参数2用户id
    // 返回值团队协作任务快照列表
    // 如果用户id为空或为空字符串，则返回团队id默认值
    public List<TeamRunSnapshot> listRuns(String teamId) {
        return listRuns(teamId, "default");
    }

    @Transactional(readOnly = true)
    // 根据团队id获取团队协作任务快照列表
    // 参数1团队id 参数2用户id
    // 返回值团队协作任务快照列表
    // 如果用户id为空或为空字符串，则返回团队id默认值
    public List<TeamRunSnapshot> listRuns(String teamId, String userId) {
        loadTeamForOwner(teamId, userId);
        return runMapper.selectByTeamIdAndUserIdOrderByCreatedAtDesc(teamId, normalizeUserId(userId)).stream()
            .map(run -> toRunSnapshot(run, true, false))
            .toList();
    }

    @Transactional(readOnly = true)
    // 根据用户id获取团队协作任务快照列表
    // 参数1用户id
    // 返回值团队协作任务快照列表
    // 如果用户id为空或为空字符串，则返回团队id默认值
    public List<TeamRunSnapshot> listRunsForUser(String userId) {
        return runMapper.selectByUserIdOrderByCreatedAtDesc(normalizeUserId(userId)).stream()
            .map(run -> toRunSnapshot(run, true, false))
            .toList();
    }

    @Transactional(readOnly = true)
    // 根据团队id获取团队协作任务快照
    // 参数1团队id 参数2用户id 参数3任务id
    // 返回值团队协作任务快照
    // 如果用户id为空或为空字符串，则返回团队id默认值
    public TeamRunSnapshot getRun(String teamId, String runId) {
        return getRun(teamId, "default", runId);
    }

    @Transactional(readOnly = true)
    // 根据团队id获取团队协作任务快照
    // 参数1团队id 参数2用户id 参数3任务id
    // 返回值团队协作任务快照
    // 如果用户id为空或为空字符串，则返回团队id默认值
    public TeamRunSnapshot getRun(String teamId, String userId, String runId) {
        TeamRunEntity run = loadRun(teamId, normalizeUserId(userId), runId);
        return toRunSnapshot(run, true, true);
    }

    @Transactional
    public TeamRunSnapshot resumeRun(String teamId, String runId, String clarificationAnswer) {
        return resumeRun(teamId, "default", runId, clarificationAnswer, null);
    }

    @Transactional
    public TeamRunSnapshot resumeRun(String teamId, String userId, String runId, String clarificationAnswer) {
        return resumeRun(teamId, userId, runId, clarificationAnswer, null);
    }

    @Transactional
    public TeamRunSnapshot resumeRun(String teamId, String userId, String runId, String clarificationAnswer,
                                     Map<String, String> stepClarificationAnswers) {
        TeamRunEntity run = loadRun(teamId, normalizeUserId(userId), runId);
        if (run.getStatus() != TeamRunStatus.NEEDS_CLARIFICATION) {
            throw new IllegalArgumentException("Run is not waiting for clarification: " + runId);
        }
        boolean hasRunAnswer = clarificationAnswer != null && !clarificationAnswer.isBlank();
        boolean hasStepAnswers = stepClarificationAnswers != null
            && stepClarificationAnswers.values().stream().anyMatch(v -> v != null && !v.isBlank());
        if (!hasRunAnswer && !hasStepAnswers) {
            throw new IllegalArgumentException("clarificationAnswer or stepClarificationAnswers is required");
        }
        List<TeamStepEntity> steps = stepMapper.selectByRunIdOrderByStepIndexAsc(runId);
        boolean stepLevelClarification = steps.stream().anyMatch(this::isWaitingForStepClarification);
        if (stepLevelClarification && !hasStepAnswers) {
            throw new IllegalArgumentException("stepClarificationAnswers is required");
        }
        if (!stepLevelClarification && !hasRunAnswer) {
            throw new IllegalArgumentException("clarificationAnswer is required");
        }
        if (run.getClarificationStage() == TeamClarificationStage.PLAN_REVIEW) {
            stepMapper.deleteByRunId(runId);
            run.setClarificationAnswer(clarificationAnswer.trim());
            run.setPlanReviewJson(null);
            run.setResultReviewJson(null);
            run.setFinalOutput(null);
            run.setMermaidDiagram(null);
            run.setPlanRetryCount(0);
            run.setStatus(TeamRunStatus.PENDING);
        } else if (run.getClarificationStage() == TeamClarificationStage.RESULT_REVIEW) {
            if (stepLevelClarification) {
                run.setClarificationAnswer(null);
                clearResultArtifacts(run);
                boolean hasActiveSibling = false;
                TeamRedisDagStore dagStore = getDagStore();
                for (TeamStepEntity step : steps) {
                    if (isWaitingForStepClarification(step)) {
                        applyStepClarificationAnswer(run, step, stepClarificationAnswers);
                        // Sync to Redis so coordinator can re-dispatch
                        if (dagStore != null) {
                            dagStore.setStepRetrying(runId, step.getStepId(), step.getRetryCount());
                            dagStore.markStepReady(runId, step.getStepId());
                        }
                    } else if (step.getStatus() == TeamStepStatus.RUNNING
                        || step.getStatus() == TeamStepStatus.ASSIGNED) {
                        hasActiveSibling = true;
                    }
                }
                run.setStatus(hasActiveSibling ? TeamRunStatus.EXECUTING : TeamRunStatus.PENDING);
            } else {
                run.setClarificationAnswer(clarificationAnswer.trim());
                clearResultArtifacts(run);
                run.setResultReplanCount(0);
                run.setPartialReplanCount(0);
                run.setStatus(TeamRunStatus.PENDING);
            }
        }
        run.setClarificationQuestion(null);
        run.setClarificationStage(null);
        runMapper.upsertById(run);

        TeamRedisDagStore store = getDagStore();
        if (store != null) {
            store.clearControlFlag(runId, "stopping");
        }

        recordEvent(runId, null, TeamEventType.RUN_RESUMED, null, null, "User clarification received", null);
        if (run.getStatus() == TeamRunStatus.PENDING) {
            scheduleRun(runId);
        }
        return getRun(teamId, userId, runId);
    }

    private boolean isWaitingForStepClarification(TeamStepEntity step) {
        return step != null && "ASK_CLARIFICATION".equals(step.getReviewStatus());
    }

    private void applyStepClarificationAnswer(TeamRunEntity run, TeamStepEntity step,
                                              Map<String, String> stepClarificationAnswers) {
        String answer = stepClarificationAnswers == null ? null : stepClarificationAnswers.get(step.getStepId());
        if ((answer == null || answer.isBlank()) && step.getClientStepId() != null) {
            answer = stepClarificationAnswers.get(step.getClientStepId());
        }
        if (answer == null || answer.isBlank()) {
            throw new IllegalArgumentException("stepClarificationAnswers is required for step: " + step.getStepId());
        }
        List<String> previous = new ArrayList<>(json.stringList(step.getPreviousOutputsJson()));
        if (step.getRawOutput() != null && !step.getRawOutput().isBlank()) {
            previous.add(step.getRawOutput());
        }
        String trimmed = answer.trim();
        String existing = nullToBlank(step.getRevisionInstructions()).trim();
        step.setPreviousOutputsJson(json.toJson(previous));
        step.setRevisionInstructions((existing.isBlank() ? "" : existing + "\n") + "用户澄清: " + trimmed);
        step.setLastReviewReason("用户澄清已补充");
        step.setReflectionJson(json.toJson(new StepReflection(
            List.of(),
            "用户澄清已补充",
            trimmed,
            summarize(step.getRawOutput()),
            List.of("按用户澄清重新执行该 Step")
        )));
        step.setRetryCount(step.getRetryCount() + 1);
        step.setStatus(TeamStepStatus.RETRYING);
        step.setQualityStatus(TeamStepQualityStatus.RETRY_REQUESTED);
        step.setReviewStatus("RETRY_REQUESTED");
        step.setStartedAt(null);
        step.setCompletedAt(null);
        stepMapper.upsertById(step);
        recordEvent(run.getRunId(), step.getStepId(), TeamEventType.RETRY_REQUESTED, TeamRole.REVIEWER,
            null, "用户澄清已补充，Step 准备重试", Map.of("answer", trimmed));
    }

    private void clearResultArtifacts(TeamRunEntity run) {
        run.setResultReviewJson(null);
        run.setGlobalReviewJson(null);
        run.setMergeOutput(null);
        run.setConflictReportJson(null);
        run.setArbitrationJson(null);
        run.setFinalOutput(null);
        run.setMermaidDiagram(null);
    }

    private void planAndReview(TeamRunEntity run, TeamSnapshot team) {
        planAndReview(run, team, List.of(), "", List.of());
    }

    private void planAndReview(TeamRunEntity run, TeamSnapshot team,
                               List<PlannedStep> initialPreviousPlan,
                               String initialRevisionInstructions,
                               List<TeamStepSnapshot> previousExecutionResults) {
        planAndReview(run, team, initialPreviousPlan, initialRevisionInstructions, previousExecutionResults, false);
    }

    private void planAndReview(TeamRunEntity run, TeamSnapshot team,
                               List<PlannedStep> initialPreviousPlan,
                               String initialRevisionInstructions,
                               List<TeamStepSnapshot> previousExecutionResults,
                               boolean preserveExistingSteps) {
        // Planner 和 Reviewer 的闭环：Reviewer 不通过时，把上一轮计划和修改意见带回 Planner。
        // 标记计划是否通过审核
        boolean approved = false;
        // 初始化计划：如果传入的 previousPlan 为 null，则使用空列表
        List<PlannedStep> previousPlan = initialPreviousPlan == null ? List.of() : initialPreviousPlan;
        // 初始化 Planner 的修改指令
        String plannerRevisionInstructions = nullToBlank(initialRevisionInstructions);

        // 循环直到计划通过审核或达到最大重试次数
        while (!approved && run.getPlanRetryCount() <= runtimeProperties.getMaxPlanRetries()) {
            // 1. 设置状态为 PLANNING，开始规划阶段
            run.setStatus(TeamRunStatus.PLANNING);
            runMapper.upsertById(run);
            // 记录规划开始事件
            recordEvent(run.getRunId(), null, TeamEventType.PLAN_STARTED, TeamRole.PLANNER,
                planner(team).agentId(), "Planner started task decomposition", null);

            // 2. 调用 Planner Agent 生成步骤计划
            // 参数：当前计划、上次修改意见、上次执行结果
            List<PlannedStep> planned = callPlanner(run, team, previousPlan, plannerRevisionInstructions,
                previousExecutionResults);
            requireRunWritable(run.getRunId());

            // 3. 用新计划替换现有步骤
            replaceSteps(run, planned, preserveExistingSteps);
            // 记录计划创建事件
            recordEvent(run.getRunId(), null, TeamEventType.PLAN_CREATED, TeamRole.PLANNER,
                planner(team).agentId(), "Planner created " + planned.size() + " step(s)", planned);

            if (shouldUseSimpleFastPath(run)) {
                executeSimpleFastPath(run, team);
                return;
            }

            if (!reviewOptions(run).planReviewEnabled()) {
                ReviewerDecision skipped = skippedReviewDecision("用户跳过 PlanReview，使用 Planner 计划继续执行", "");
                run.setPlanReviewJson(json.toJson(skipped));
                runMapper.upsertById(run);
                recordEvent(run.getRunId(), null, TeamEventType.PLAN_REVIEWED, TeamRole.REVIEWER,
                    reviewer(team).agentId(), skipped.reason(), skipped);
                approved = true;
                continue;
            }

            // 4. 设置状态为 PLAN_REVIEWING，开始审核阶段
            run.setStatus(TeamRunStatus.PLAN_REVIEWING);
            runMapper.upsertById(run);
            // 记录审核开始事件
            recordEvent(run.getRunId(), null, TeamEventType.PLAN_REVIEW_STARTED, TeamRole.REVIEWER,
                reviewer(team).agentId(), "Reviewer started plan review", null);

            // 5. 调用 Reviewer Agent 审核计划
            ReviewerDecision decision = callReviewerForPlan(run, team, planned);
            // 确保数据库可写入
            requireRunWritable(run.getRunId());
            // 保存审核结果到数据库
            run.setPlanReviewJson(json.toJson(decision));
            runMapper.upsertById(run);
            // 记录审核完成事件
            recordEvent(run.getRunId(), null, TeamEventType.PLAN_REVIEWED, TeamRole.REVIEWER,
                reviewer(team).agentId(), decision.reason(), decision);

            // 6. 根据审核结果处理
            if (decision.action() == ReviewerAction.CONTINUE) {
                // 计划通过审核，退出循环
                approved = true;
            } else if (decision.action() == ReviewerAction.ASK_CLARIFICATION) {
                // 需要用户澄清，暂停任务
                pauseForClarification(run, decision, TeamClarificationStage.PLAN_REVIEW);
                return;
            } else if (run.getPlanRetryCount() >= runtimeProperties.getMaxPlanRetries()) {
                // 达到最大重试次数，任务失败
                failRun(run.getRunId(), "Planner retry limit reached: " + decision.reason());
                return;
            } else {
                // 计划需要修改，准备下一轮规划
                // 保存当前计划作为下一轮的参考
                previousPlan = planned;
                // 保存审核意见作为下一轮的修改指令
                plannerRevisionInstructions = decision.revisionInstructions();
                // 增加重试计数
                run.setPlanRetryCount(run.getPlanRetryCount() + 1);
                runMapper.upsertById(run);
                // 记录重试请求事件
                recordEvent(run.getRunId(), null, TeamEventType.RETRY_REQUESTED, TeamRole.REVIEWER,
                    reviewer(team).agentId(), "Reviewer requested planner retry: " + decision.reason(), decision);
            }
        }
    }

    private List<PlannedStep> callPlanner(TeamRunEntity run, TeamSnapshot team,
                                          List<PlannedStep> previousPlan,
                                          String plannerRevisionInstructions,
                                          List<TeamStepSnapshot> previousExecutionResults) {
        // 获取Planner成员
        TeamMember planner = planner(team);
        // 生成提示词
        String prompt = TeamPromptFactory.planner(run, team, previousPlan, plannerRevisionInstructions,
            previousExecutionResults, json);
        // 调用Planner Agent 生成步骤计划
        PipelineContext result = executeTeamControlInternal(run, OP_PLANNER, planner.agentId(), plannerSession(run),
            prompt);
        String raw = result.getFinalResponse();
        // 解析步骤
        List<PlannedStep> steps = json.parsePlan(raw);
        if (steps.isEmpty()) {
            throw new IllegalStateException("Planner did not produce any steps");
        }
        run.setTaskLevel(json.parseTaskLevel(raw, steps.size()));
        runMapper.upsertById(run);
        return steps;
    }

    private ReviewerDecision callReviewerForPlan(TeamRunEntity run, TeamSnapshot team, List<PlannedStep> planned) {
        TeamMember reviewer = reviewer(team);
        return executeReviewerDecision(reviewer, run, TeamPromptFactory.planReviewer(run, planned, json),
            ReviewerDecisionScope.PLAN);
    }

    private boolean shouldUseSimpleFastPath(TeamRunEntity run) {
        if (!reviewOptions(run).simpleFastPathEnabled() || run.getTaskLevel() != TeamTaskLevel.SIMPLE) {
            return false;
        }
        return activeSteps(run.getRunId()).size() == 1;
    }

    private List<TeamStepEntity> activeSteps(String runId) {
        return stepMapper.selectByRunIdOrderByStepIndexAsc(runId).stream()
            .filter(step -> step.getStatus() != TeamStepStatus.SUPERSEDED)
            .toList();
    }

    private void executeSimpleFastPath(TeamRunEntity run, TeamSnapshot team) {
        List<TeamStepEntity> steps = activeSteps(run.getRunId());
        if (steps.size() != 1) {
            return;
        }
        TeamStepEntity step = steps.get(0);
        run.setStatus(TeamRunStatus.EXECUTING);
        runMapper.upsertById(run);
        recordEvent(run.getRunId(), null, TeamEventType.TEAM_CONTROL_STARTED, null, null,
            "SIMPLE 快路径启动：单 Executor 执行后直接返回结果", Map.of(
                "simpleFastPath", true,
                "reviewOptions", reviewOptions(run)
            ));

        List<String> requiredCapabilities = json.stringList(step.getRequiredCapabilitiesJson());
        TeamAgentSelection selection = agentSelector.selectExecutor(team.members(), requiredCapabilities);
        TeamMember executor = executorBySelection(team, selection);
        if (executor == null) {
            throw new IllegalStateException("No executor configured");
        }
        TeamAgentSelection auditedSelection = selection == null ? null : selection.withDecision("RULE_FAST_PATH",
            "SIMPLE 快路径使用能力匹配规则选择 Executor；" + selection.reason());
        recordEvent(run.getRunId(), step.getStepId(), TeamEventType.AGENT_SELECTED, TeamRole.PLANNER,
            planner(team).agentId(), "SIMPLE 快路径选择 Executor " + executor.agentName(), auditedSelection);

        step.setAssignedAgentId(executor.agentId());
        step.setStatus(TeamStepStatus.ASSIGNED);
        step.setStartedAt(Instant.now());
        stepMapper.upsertById(step);
        recordEvent(run.getRunId(), step.getStepId(), TeamEventType.STEP_ASSIGNED, TeamRole.EXECUTOR,
            executor.agentId(), "Step assigned to " + executor.agentName(), Map.of(
                "requiredCapabilities", requiredCapabilities,
                "selection", auditedSelection
            ));

        step.setStatus(TeamStepStatus.RUNNING);
        stepMapper.upsertById(step);
        recordEvent(run.getRunId(), step.getStepId(), TeamEventType.STEP_STARTED, TeamRole.EXECUTOR,
            executor.agentId(), "Executor started SIMPLE fast-path step", null);

        String prompt = TeamPromptFactory.executor(run, step, "");
        step.setInputJson(json.toJson(Map.of("prompt", prompt, "selection", auditedSelection)));
        stepMapper.upsertById(step);

        PipelineContext result = executeTeamInternal(run, OP_EXECUTOR, executor.agentId(), stepSession(run, step),
            prompt);
        String output = result == null ? null : result.getFinalResponse();
        if (output != null && output.startsWith("[Error]")) {
            if (isToolBudgetLimit(output)) {
                output = executorToolBudgetFallback(run, step, executor, output);
            } else {
                throw new IllegalStateException(output);
            }
        }
        requireRunWritable(run.getRunId());
        if (isStepTerminalNow(step.getStepId())) {
            log.info("Skip SIMPLE fast-path completion for step {} because it is already terminal",
                step.getStepId());
            return;
        }
        step.setRawOutput(output);
        if (!completeStep(run.getRunId(), step, executor.agentId(), false, TeamStepQualityStatus.PASSED)) {
            return;
        }
        completeRunWithFinalOutput(run, team, nullToBlank(output), TeamRole.EXECUTOR, executor.agentId(),
            "SIMPLE 快路径完成，直接返回 Executor 输出");
    }

    /**
     * 执行单个 Step 的完整生命周期。
     * <p>这是 Team 任务执行的核心方法，负责协调单个步骤的完整执行流程：
     * <ol>
     *   <li><strong>加载实体</strong>：从数据库获取 Run、Team、Step 实体</li>
     *   <li><strong>选择 Executor</strong>：基于能力匹配 + 模型智能选择合适的执行 Agent</li>
     *   <li><strong>状态管理</strong>：更新步骤状态为 ASSIGNED → RUNNING</li>
     *   <li><strong>构建提示词</strong>：生成包含依赖步骤输出的执行提示词</li>
     *   <li><strong>执行 Agent</strong>：调用选定的 Executor Agent 执行步骤</li>
     *   <li><strong>结果处理</strong>：保存执行结果，并按 Run 配置执行每步 Review</li>
     * </ol>
     *
     * <p><strong>状态流转：</strong>
     * <br/>PENDING/BLOCKED/RETRYING → ASSIGNED → RUNNING → COMPLETED/FAILED
     *
     * <p><strong>异常处理：</strong>
     * <br/>执行过程中发生异常时，会将步骤状态设置为 FAILED，确保 DAG 调度能正确识别并处理失败。
     *
     * @param runId  任务运行 ID，用于关联到具体的 Team 任务
     * @param stepId 步骤 ID，标识要执行的具体步骤
     */
    @Transactional
    protected void executeStep(String runId, String stepId) {
        // ============================================
        // 阶段1：加载实体
        // ============================================
        // 从数据库获取任务运行实体，如果不存在则抛出异常
        TeamRunEntity run = runMapper.selectOptionalById(runId)
            .orElseThrow(() -> new IllegalArgumentException("Run not found: " + runId));
        
        // 获取团队快照，包含团队成员和配置信息
        TeamSnapshot team = getTeam(run.getTeamId());
        
        // 获取步骤实体，如果不存在则抛出异常
        TeamStepEntity step = stepMapper.selectOptionalById(stepId)
            .orElseThrow(() -> new IllegalArgumentException("Step not found: " + stepId));
        if (!isStepExecutable(step.getStatus())) {
            log.info("Skip step {} for run {} because status is {}", stepId, runId, step.getStatus());
            return;
        }

        // ============================================
        // 阶段2：选择 Executor Agent
        // ============================================
        // 解析步骤所需的能力列表
        List<String> requiredCapabilities = json.stringList(step.getRequiredCapabilitiesJson());
        
        // 调用选择器选择 Executor（模型智能选择 + 规则兜底）
        // selectExecutorWithModel 会先通过规则引擎筛选候选，再让 Planner 模型做最终选择
        TeamAgentSelection selection = selectExecutorWithModel(run, team, step, requiredCapabilities);
        
        // 根据选择结果获取对应的 Agent 成员
        TeamMember executor = selection == null ? null : executorBySelection(team, selection);
        
        // 如果没有找到合适的 Executor，抛出异常终止执行
        if (executor == null) {
            throw new IllegalStateException("No executor configured");
        }
        
        // 记录 Agent 选择事件（selection 可能为 null，需做空值保护）
        recordEvent(runId, stepId, TeamEventType.AGENT_SELECTED, TeamRole.PLANNER, planner(team).agentId(),
            "模型选择 Executor " + executor.agentName() + "：" + (selection != null ? selection.reason() : "规则兜底"), selection);

        // ============================================
        // 阶段3：设置步骤状态（区分重试和首次执行）
        // ============================================
        // 判断是否为重试：重试次数 > 0 或当前状态为 RETRYING
        boolean retrying = step.getRetryCount() > 0 || step.getStatus() == TeamStepStatus.RETRYING;
        
        // 设置分配的 Agent ID
        step.setAssignedAgentId(executor.agentId());
        step.setStatus(retrying ? TeamStepStatus.RETRYING : TeamStepStatus.ASSIGNED);
        step.setStartedAt(Instant.now());
        step.setSubReviewJson(null);
        stepMapper.upsertById(step);
        
        // 记录步骤分配事件
        recordEvent(runId, stepId, TeamEventType.STEP_ASSIGNED, TeamRole.EXECUTOR, executor.agentId(),
            "Step assigned to " + executor.agentName(), Map.of(
                "requiredCapabilities", requiredCapabilities,
                "selection", selection
            ));

        // ============================================
        // 阶段4：标记步骤开始执行
        // ============================================
        // 更新状态为 RUNNING（重试时仍为 RETRYING，便于区分）
        step.setStatus(retrying ? TeamStepStatus.RETRYING : TeamStepStatus.RUNNING);
        stepMapper.upsertById(step);
        
        // 记录步骤开始事件（区分首次执行和重试）
        recordEvent(runId, stepId, retrying ? TeamEventType.STEP_RETRY_STARTED : TeamEventType.STEP_STARTED,
            TeamRole.EXECUTOR, executor.agentId(), "Executor started step", null);

        // ============================================
        // 阶段5：构建执行提示词
        // ============================================
        // 使用 TeamPromptFactory 生成 Executor 的执行提示词
        // 提示词包含：用户任务、步骤描述、依赖步骤的输出
        String prompt = TeamPromptFactory.executor(run, step, dependencyOutputs(step));
        
        // 保存输入信息到步骤实体（便于追踪和调试）
        step.setInputJson(json.toJson(Map.of("prompt", prompt, "selection", selection)));
        stepMapper.upsertById(step);

        // ============================================
        // 阶段6：调用 Agent 执行
        // ============================================
        String output;
        try {
            // 调用 Agent 执行步骤
            // executeTeamInternal 会执行完整的 Agent Pipeline（记忆加载、工具调用等）
            PipelineContext result = executeTeamInternal(run, OP_EXECUTOR, executor.agentId(), stepSession(run, step),
                prompt);
            
            // 处理可能的 null 返回值（防止 NPE）
            output = result != null ? result.getFinalResponse() : null;
            
            // 如果 Agent 返回错误标记，抛出异常
            if (output != null && output.startsWith("[Error]")) {
                if (isToolBudgetLimit(output)) {
                    output = executorToolBudgetFallback(run, step, executor, output);
                    requireRunWritable(runId);
                    if (isStepTerminalNow(stepId)) {
                        log.info("Skip tool-budget fallback completion for step {} because it is already terminal",
                            stepId);
                        return;
                    }
                    step.setRawOutput(output);
                    completeStep(runId, step, executor.agentId(), retrying, TeamStepQualityStatus.FLAWED_ACCEPTED);
                    return;
                }
                throw new IllegalStateException(output);
            }
        } catch (Exception e) {
            if (isStepTerminalNow(stepId)) {
                log.info("Skip failing step {} for run {} because it is already terminal", stepId, runId);
                return;
            }
            // 执行失败时设置失败状态，避免步骤停留在 RUNNING 导致 DAG 卡住
            step.setStatus(TeamStepStatus.FAILED);
            step.setRawOutput("Step failed: " + e.getMessage());
            stepMapper.upsertById(step);
            throw e;  // 继续向上抛出异常，让上层处理
        }

        // ============================================
        // 阶段7：保存执行结果
        // ============================================
        // 检查任务是否可写（用于并发控制）
        requireRunWritable(runId); // 这个是防止用户在正在跑的时候直接删除team就浪费资源了
        if (isStepTerminalNow(stepId)) {
            log.info("Skip writing output for step {} because it is already terminal", stepId);
            return;
        }
        
        // 保存 Agent 的输出结果
        step.setRawOutput(output);

        // ============================================
        // 阶段8：处理结果（可选每步 Review）
        // ============================================
        reviewStepOutput(run, team, step, retrying);
    }

    private String executorToolBudgetFallback(TeamRunEntity run, TeamStepEntity step, TeamMember executor,
                                              String failureReason) {
        String prompt = TeamPromptFactory.executorToolBudgetFallback(run, step, dependencyOutputs(step), failureReason);
        PipelineContext fallback = executeTeamControlInternal(run, OP_EXECUTOR, executor.agentId(),
            stepSession(run, step) + "-tool-budget-fallback-" + UUID.randomUUID(), prompt);
        String summary = fallback == null ? "" : fallback.getFinalResponse();
        if (summary == null || summary.isBlank() || summary.startsWith("[Error]")) {
            throw new IllegalStateException("Executor tool budget fallback failed: " + nullToBlank(summary));
        }
        return "工具调用达到上限，已降级总结：\n\n" + summary;
    }

    private boolean isToolBudgetLimit(String output) {
        return output != null && output.contains("工具调用次数超过上限");
    }

    /**
     * 选择执行步骤的 Executor Agent。
     * <p>采用"模型主选 + 规则兜底"策略：
     * <ol>
     *   <li>首先通过规则引擎获取候选 Executor 列表（按能力匹配度排序）</li>
     *   <li>调用 Planner 模型进行智能选择（考虑步骤语义、依赖输出等）</li>
     *   <li>模型选择有效时返回模型选择结果</li>
     *   <li>模型选择无效或异常时，使用规则兜底（选择候选列表首位）</li>
     * </ol>
     *
     * @param run                  任务运行实体
     * @param team                 团队快照
     * @param step                 步骤实体
     * @param requiredCapabilities 步骤需要的能力列表
     * @return 选中的 Executor Agent，若无候选则返回 null
     */
    private TeamAgentSelection selectExecutorWithModel(TeamRunEntity run, TeamSnapshot team, TeamStepEntity step,
                                                       List<String> requiredCapabilities) {
        // 1. 获取候选 Executor 列表（按能力匹配度排序）
        List<TeamAgentSelection> candidates = agentSelector.rankExecutors(team.members(), requiredCapabilities);
        if (candidates.isEmpty()) {
            return null;  // 无候选 Executor
        }

        // 2. 创建规则兜底选择（候选列表首位）
        TeamAgentSelection fallback = candidates.get(0).withDecision("RULE_FALLBACK",
            "模型选择不可用时的规则兜底：" + candidates.get(0).reason());

        // 3. 调用 Planner 模型进行智能选择
        // 主选择权交给模型；模型输出无效或异常时，退回能力匹配分 + sortOrder 的稳定兜底
        TeamMember planner = planner(team);
        String prompt = TeamPromptFactory.agentSelector(run, step, requiredCapabilities, dependencyOutputs(step),
            candidates, json);

        try {
            // 4. 执行模型选择
            PipelineContext result = executeTeamControlInternal(run, OP_PLANNER, planner.agentId(),
                "team-run-" + run.getRunId() + "-agent-selector-" + step.getStepId() + "-" + UUID.randomUUID(),
                prompt);

            // 5. 解析模型决策并匹配候选列表
            TeamExecutorSelectionDecision decision = json.parseExecutorSelectionDecision(
                result == null ? "" : result.getFinalResponse());
            TeamAgentSelection selected = agentSelector.matchModelDecision(candidates, decision.memberKey(),
                decision.agentId());

            // 6. 返回模型选择或兜底选择
            if (selected != null) {
                return selected.withDecision("MODEL",
                    "模型自主选择：" + blankToDefault(decision.reason(), "匹配当前 Step 的语义和能力要求")
                        + "；" + selected.reason());
            }
            // 模型返回的 Executor 不在候选列表中，使用规则兜底
            return fallback.withDecision("RULE_FALLBACK",
                "模型返回的 Executor 不在候选列表中，使用规则兜底；模型原始选择 memberKey="
                    + decision.memberKey() + "，agentId=" + decision.agentId() + "；" + fallback.reason());
        } catch (Exception e) {
            // 模型选择失败，使用规则兜底
            log.warn("AgentSelector model decision failed for run {} step {}: {}", run.getRunId(), step.getStepId(),
                e.getMessage());
            return fallback.withDecision("RULE_FALLBACK",
                "模型选择失败，使用规则兜底：" + e.getMessage() + "；" + fallback.reason());
        }
    }

    private void reviewStepOutput(TeamRunEntity run, TeamSnapshot team, TeamStepEntity step, boolean retrying) {
        if (isStepTerminalNow(step.getStepId())) {
            log.info("Skip reviewing step {} for run {} because it is already terminal", step.getStepId(),
                run.getRunId());
            return;
        }
        if (!reviewOptions(run).subReviewEnabled()) {
            ReviewerDecision skipped = skippedReviewDecision("用户跳过每步 Review，Step 输出直接放行", "");
            step.setSubReviewJson(json.toJson(skipped));
            step.setLastReviewReason(skipped.reason());
            recordEvent(run.getRunId(), step.getStepId(), TeamEventType.STEP_SUB_REVIEWED, TeamRole.SUB_REVIEWER,
                subReviewer(team).agentId(), skipped.reason(), skipped);
            completeStep(run.getRunId(), step, step.getAssignedAgentId(), retrying, TeamStepQualityStatus.REVIEW_SKIPPED);
            return;
        }
        // 获取reviewer成员
        TeamMember reviewer = subReviewer(team);
        // 事件
        recordEvent(run.getRunId(), step.getStepId(), TeamEventType.STEP_SUB_REVIEW_STARTED, TeamRole.SUB_REVIEWER,
            reviewer.agentId(), "StepReviewer 开始校验 Step 输出", Map.of(
                "riskLevel", step.getRiskLevel() == null ? "" : step.getRiskLevel().name()
            ));

        // 调用 StepReviewer 审查 Step 输出；字段仍复用 subReview* 以兼容现有 DTO/数据库。
        ReviewerDecision decision = callStepReviewer(run, reviewer, step);
        if (isStepTerminalNow(step.getStepId())) {
            log.info("Skip applying StepReviewer decision for step {} because it is already terminal",
                step.getStepId());
            return;
        }
        step.setSubReviewJson(json.toJson(decision));
        step.setLastReviewReason(decision.reason());
        recordEvent(run.getRunId(), step.getStepId(), TeamEventType.STEP_SUB_REVIEWED, TeamRole.SUB_REVIEWER,
            reviewer.agentId(), decision.reason(), decision);

        if (isStepPass(decision)) {
            // 审查通过，标记完成
            completeStep(run.getRunId(), step, step.getAssignedAgentId(), retrying, TeamStepQualityStatus.PASSED);
            return;
        }
        if (decision.action() == ReviewerAction.ACCEPT_WITH_RISK) {
            step.setReflectionJson(json.toJson(reflectionForStep(step, decision)));
            completeStep(run.getRunId(), step, step.getAssignedAgentId(), retrying, TeamStepQualityStatus.FLAWED_ACCEPTED);
            return;
        }
        if (decision.action() == ReviewerAction.FAILED) {
            step.setQualityStatus(TeamStepQualityStatus.FAILED);
            step.setReviewStatus("FAILED");
            step.setStatus(TeamStepStatus.FAILED);
            step.setCompletedAt(Instant.now());
            stepMapper.upsertById(step);
            recordEvent(run.getRunId(), step.getStepId(), TeamEventType.STEP_FAILED, TeamRole.SUB_REVIEWER,
                reviewer.agentId(), "StepReviewer 判定 Step 失败: " + decision.reason(), decision);
            failRun(run.getRunId(), "StepReviewer 判定 Step 失败: " + decision.reason());
            return;
        }
        if (step.getRetryCount() >= runtimeProperties.getMaxStepRetries()) {
            failRun(run.getRunId(), "StepReview rework limit reached for step " + step.getStepId()
                + ": " + decision.reason());
            return;
        }
        // 重试次数未耗尽，写入 Reflexion 上下文后重新执行
        prepareStepRetry(run, step, decision, reviewer);
    }

    private ReviewerDecision callStepReviewer(TeamRunEntity run, TeamMember reviewer, TeamStepEntity step) {
        return executeReviewerDecision(reviewer, run, TeamPromptFactory.subReviewer(run, step),
            ReviewerDecisionScope.STEP);
    }

    private boolean completeStep(String runId, TeamStepEntity step, String actorAgentId, boolean retrying,
                              TeamStepQualityStatus qualityStatus) {
        if (isRunDeleted(runId)) {
            return false;
        }
        if (isStepTerminalNow(step.getStepId())) {
            log.info("Skip completing step {} for run {} because it is already terminal", step.getStepId(), runId);
            return false;
        }
        step.setQualityStatus(qualityStatus);
        step.setReviewStatus(qualityStatus.name());
        step.setStatus(TeamStepStatus.COMPLETED);
        step.setCompletedAt(Instant.now());
        stepMapper.upsertById(step);
        recordEvent(runId, step.getStepId(), retrying ? TeamEventType.STEP_RETRY_COMPLETED : TeamEventType.STEP_COMPLETED,
            TeamRole.EXECUTOR, actorAgentId, "Executor 完成 Step", Map.of("qualityStatus", qualityStatus.name()));
        return true;
    }

    private boolean isStepExecutable(TeamStepStatus status) {
        return status == TeamStepStatus.READY || status == TeamStepStatus.RETRYING;
    }

    private boolean isStepTerminalNow(String stepId) {
        return stepMapper.selectOptionalById(stepId)
            .map(TeamStepEntity::getStatus)
            .map(this::isStepTerminal)
            .orElse(true);
    }

    private boolean isStepTerminal(TeamStepStatus status) {
        return status == TeamStepStatus.COMPLETED
            || status == TeamStepStatus.FAILED
            || status == TeamStepStatus.SUPERSEDED;
    }

    private void mergeAndReviewResults(TeamRunEntity run, TeamSnapshot team) {
        // 固定结果链路：MergeAgent 聚合 -> ConflictDetector 查冲突 -> 必要时 Planner 仲裁 -> GlobalReviewer 终审。
        run.setStatus(TeamRunStatus.MERGING);
        runMapper.upsertById(run);
        TeamMember merger = merger(team);
        recordEvent(run.getRunId(), null, TeamEventType.MERGE_STARTED, TeamRole.MERGER,
            merger.agentId(), "MergeAgent 开始聚合 Step 输出", null);
        String mergeOutput = callMerger(run, merger);
        run.setMergeOutput(mergeOutput);
        runMapper.upsertById(run);
        recordEvent(run.getRunId(), null, TeamEventType.MERGE_COMPLETED, TeamRole.MERGER,
            merger.agentId(), "MergeAgent 完成聚合", Map.of("mergeOutput", mergeOutput));
        TeamConflictReport conflictReport = detectConflicts(run, team, mergeOutput);
        run.setConflictReportJson(json.toJson(conflictReport));
        runMapper.upsertById(run);
        recordEvent(run.getRunId(), null, TeamEventType.CONFLICT_DETECTED, null, null,
            conflictReport.hasConflict() ? "ConflictDetector 发现冲突" : "ConflictDetector 未发现冲突", conflictReport);
        if (conflictReport.hasConflict()
            && run.getArbitrationCount() < runtimeProperties.getMaxArbitrations()) {
            String arbitration = arbitrateConflicts(run, team, conflictReport);
            run.setArbitrationCount(run.getArbitrationCount() + 1);
            run.setArbitrationJson(json.toJson(Map.of(
                "arbitration", arbitration,
                "conflictReport", conflictReport,
                "arbitrationCount", run.getArbitrationCount()
            )));
            runMapper.upsertById(run);
            recordEvent(run.getRunId(), null, TeamEventType.ARBITRATION_COMPLETED, TeamRole.PLANNER,
                planner(team).agentId(), "Planner 完成冲突仲裁", run.getArbitrationJson());
            recordEvent(run.getRunId(), null, TeamEventType.MERGE_STARTED, TeamRole.MERGER,
                merger.agentId(), "MergeAgent 带 Planner 仲裁结果二次聚合", null);
            mergeOutput = callMerger(run, merger);
            run.setMergeOutput(mergeOutput);
            TeamConflictReport secondReport = detectConflicts(run, team, mergeOutput);
            run.setConflictReportJson(json.toJson(secondReport));
            runMapper.upsertById(run);
            recordEvent(run.getRunId(), null, TeamEventType.MERGE_COMPLETED, TeamRole.MERGER,
                merger.agentId(), "MergeAgent 完成仲裁后二次聚合", Map.of("mergeOutput", mergeOutput));
            recordEvent(run.getRunId(), null, TeamEventType.CONFLICT_DETECTED, null, null,
                secondReport.hasConflict() ? "ConflictDetector 二次检测仍有冲突" : "ConflictDetector 二次检测无冲突", secondReport);
        }
        if (!reviewOptions(run).globalReviewEnabled()) {
            ReviewerDecision skipped = skippedReviewDecision("用户跳过 GlobalReview，直接使用 MergeAgent 输出作为最终结果",
                mergeOutput);
            run.setResultReviewJson(json.toJson(skipped));
            run.setGlobalReviewJson(json.toJson(skipped));
            runMapper.upsertById(run);
            recordEvent(run.getRunId(), null, TeamEventType.GLOBAL_REVIEWED, TeamRole.REVIEWER,
                reviewer(team).agentId(), skipped.reason(), skipped);
            completeRunWithFinalOutput(run, team, mergeOutput, TeamRole.MERGER, merger.agentId(),
                "GlobalReview 已跳过，Run 使用 MergeAgent 输出完成");
            return;
        }
        reviewResults(run, team);
    }

    private String callMerger(TeamRunEntity run, TeamMember merger) {
        return callMerger(run, merger, "");
    }

    private String callMerger(TeamRunEntity run, TeamMember merger, String mergeInstructions) {
        List<TeamStepSnapshot> steps = stepMapper.selectByRunIdOrderByStepIndexAsc(run.getRunId()).stream()
            .map(this::toStepSnapshot)
            .toList();
        String prompt = TeamPromptFactory.merger(run, steps, json, mergeInstructions);
        PipelineContext result = executeTeamControlInternal(run, OP_MERGE, merger.agentId(),
            "team-run-" + run.getRunId() + "-merger-" + UUID.randomUUID(), prompt);
        String output = result == null ? "" : result.getFinalResponse();
        return output == null || output.isBlank() ? synthesizeFallbackReport(run) : output;
    }

    private TeamConflictReport detectConflicts(TeamRunEntity run, TeamSnapshot team, String mergeOutput) {
        TeamMember reviewer = reviewer(team);
        List<TeamStepSnapshot> steps = stepMapper.selectByRunIdOrderByStepIndexAsc(run.getRunId()).stream()
            .map(this::toStepSnapshot)
            .toList();
        String prompt = TeamPromptFactory.conflictDetector(run, steps, mergeOutput, json);
        try {
            PipelineContext result = executeTeamControlInternal(run, OP_CONFLICT_DETECTOR, reviewer.agentId(),
                "team-run-" + run.getRunId() + "-conflict-detector-" + UUID.randomUUID(), prompt);
            TeamConflictReport report = json.parseConflictReport(result == null ? "" : result.getFinalResponse());
            if (!report.hasConflict()) {
                return TeamConflictReport.none(blankToDefault(report.reason(), "未发现明显冲突"));
            }
            return report;
        } catch (Exception e) {
            log.warn("ConflictDetector failed for run {}: {}", run.getRunId(), e.getMessage());
            return TeamConflictReport.none("ConflictDetector 未能解析结构化结果，交由 GlobalReviewer 兜底终审");
        }
    }

    private String arbitrateConflicts(TeamRunEntity run, TeamSnapshot team, TeamConflictReport conflictReport) {
        TeamMember planner = planner(team);
        recordEvent(run.getRunId(), null, TeamEventType.ARBITRATION_STARTED, TeamRole.PLANNER,
            planner.agentId(), "Planner 开始冲突仲裁", conflictReport);
        List<TeamStepSnapshot> steps = stepMapper.selectByRunIdOrderByStepIndexAsc(run.getRunId()).stream()
            .map(this::toStepSnapshot)
            .toList();
        String prompt = TeamPromptFactory.arbitration(run, conflictReport, steps, json);
        PipelineContext result = executeTeamControlInternal(run, OP_ARBITRATION, planner.agentId(),
            "team-run-" + run.getRunId() + "-planner-arbitration-" + UUID.randomUUID(), prompt);
        String output = result == null ? "" : result.getFinalResponse();
        return output == null || output.isBlank() ? "未生成仲裁意见，GlobalReviewer 继续兜底终审。" : output;
    }

    private void reviewResults(TeamRunEntity run, TeamSnapshot team) {
        // GlobalReviewer 只审最终合并稿：成功、失败，或带意见重新合并；不再触碰 Step/DAG。
        int remergeAttempts = 0;
        while (true) {
            run.setStatus(TeamRunStatus.GLOBAL_REVIEWING);
            runMapper.upsertById(run);
            recordEvent(run.getRunId(), null, TeamEventType.GLOBAL_REVIEW_STARTED, TeamRole.REVIEWER,
                reviewer(team).agentId(), "GlobalReviewer 开始终审", Map.of(
                    "remergeAttempts", remergeAttempts,
                    "maxMergeAttempts", runtimeProperties.getMaxMergeAttempts()
                ));

            ReviewerDecision decision = callReviewerForResults(run, team);
            requireRunWritable(run.getRunId());
            run.setResultReviewJson(json.toJson(decision));
            run.setGlobalReviewJson(json.toJson(decision));
            runMapper.upsertById(run);
            recordEvent(run.getRunId(), null, TeamEventType.GLOBAL_REVIEWED, TeamRole.REVIEWER,
                reviewer(team).agentId(), decision.reason(), decision);

            if (isGlobalSuccess(decision)) {
                completeRunWithFinalOutput(run, team,
                    decision.finalReport().isBlank() ? synthesizeFallbackReport(run) : decision.finalReport(),
                    TeamRole.REVIEWER, reviewer(team).agentId(), "GlobalReviewer completed final report");
                return;
            }

            if (decision.action() == ReviewerAction.FAILED) {
                failRun(run.getRunId(), "GlobalReviewer 判定失败: " + decision.reason());
                return;
            }

            if (decision.action() == ReviewerAction.REMERGE) {
                if (remergeAttempts >= runtimeProperties.getMaxMergeAttempts()) {
                    failRun(run.getRunId(), "GlobalReviewer requested remerge but merge attempt limit reached: "
                        + decision.reason());
                    return;
                }
                remergeAttempts++;
                run.setStatus(TeamRunStatus.MERGING);
                runMapper.upsertById(run);
                TeamMember merger = merger(team);
                String instructions = blankToDefault(decision.revisionInstructions(), decision.reason());
                recordEvent(run.getRunId(), null, TeamEventType.MERGE_STARTED, TeamRole.MERGER,
                    merger.agentId(), "MergeAgent 根据 GlobalReviewer 意见重新聚合", Map.of(
                        "mergeAttempt", remergeAttempts,
                        "mergeInstructions", instructions
                    ));
                String mergeOutput = callMerger(run, merger, instructions);
                run.setMergeOutput(mergeOutput);
                runMapper.upsertById(run);
                recordEvent(run.getRunId(), null, TeamEventType.MERGE_COMPLETED, TeamRole.MERGER,
                    merger.agentId(), "MergeAgent 完成 GlobalReviewer 要求的重新聚合", Map.of(
                        "mergeAttempt", remergeAttempts,
                        "mergeOutput", mergeOutput
                    ));
                continue;
            }

            failRun(run.getRunId(), "GlobalReviewer returned unsupported action " + decision.action()
                + ": " + decision.reason());
            return;
        }
    }

    private ReviewerDecision callReviewerForResults(TeamRunEntity run, TeamSnapshot team) {
        TeamMember reviewer = reviewer(team);
        List<TeamStepSnapshot> steps = stepMapper.selectByRunIdOrderByStepIndexAsc(run.getRunId()).stream()
            .map(this::toStepSnapshot)
            .toList();
        return executeReviewerDecision(reviewer, run, TeamPromptFactory.resultReviewer(run, steps, json),
            ReviewerDecisionScope.GLOBAL);
    }

    ReviewerDecision executeReviewerDecision(TeamMember reviewer, TeamRunEntity run,
                                             String prompt, boolean requireFinalReport) {
        return executeReviewerDecision(reviewer, run, prompt,
            requireFinalReport ? ReviewerDecisionScope.GLOBAL : ReviewerDecisionScope.PLAN);
    }

    ReviewerDecision executeReviewerDecision(TeamMember reviewer, TeamRunEntity run,
                                             String prompt, ReviewerDecisionScope scope) {
        // 1. 调用 Reviewer Agent 获取原始响应
        PipelineContext initial = executeTeamControlInternal(run, reviewerOperation(reviewer), reviewer.agentId(),
            reviewerSession(run), prompt);
        String raw = initial == null ? "" : initial.getFinalResponse();
        
        // 2. Reviewer 必须返回结构化 JSON；格式坏了就用同一 Reviewer 做有限次数修复。
        for (int attempt = 0; attempt <= runtimeProperties.getMaxReviewerFormatRepairs(); attempt++) {
            try {
                // 3. 尝试解析结构化决策
                ReviewerDecision decision = json.parseDecision(raw);
                // 4. 验证决策的有效性
                validateReviewerDecision(decision, scope);
                return decision;
            } catch (IllegalArgumentException e) {
                // 5. 解析失败，判断是否还有修复机会
                if (attempt >= runtimeProperties.getMaxReviewerFormatRepairs()) {
                    // 修复次数用完，抛出异常
                    throw e;
                }
                // 6. 还有修复机会，让 Reviewer 修复 JSON 格式
                raw = repairReviewerDecisionJson(reviewer, run, prompt, raw, e.getMessage());
            }
        }
        throw new IllegalStateException("Reviewer decision repair exhausted");
    }

    private String repairReviewerDecisionJson(TeamMember reviewer, TeamRunEntity run,
                                              String originalPrompt, String invalidResponse, String parseError) {
        String repairPrompt = TeamPromptFactory.reviewerRepair(originalPrompt, invalidResponse, parseError);
        PipelineContext repaired = executeTeamControlInternal(run, OP_REPAIR, reviewer.agentId(),
            reviewerSession(run) + "-repair-" + UUID.randomUUID(), repairPrompt);
        return repaired == null ? "" : repaired.getFinalResponse();
    }

    private String reviewerOperation(TeamMember reviewer) {
        return reviewer != null && reviewer.role() == TeamRole.SUB_REVIEWER ? OP_SUB_REVIEWER : OP_REVIEWER;
    }

    private PipelineContext executeTeamInternal(TeamRunEntity run, String operation, String agentId, String sessionId,
                                                String prompt) {
        return executeTeamCall(run, operation, agentId, sessionId, prompt, true);
    }

    private PipelineContext executeTeamControlInternal(TeamRunEntity run, String operation, String agentId,
                                                       String sessionId, String prompt) {
        return executeTeamCall(run, operation, agentId, sessionId, prompt, false);
    }

    private PipelineContext executeTeamCall(TeamRunEntity run, String operation, String agentId, String sessionId,
                                            String prompt, boolean toolExposureEnabled) {
        String userId = normalizeUserId(run == null ? null : run.getUserId());
        TeamUsageRecorder recorder = usageRecorder == null ? TeamUsageRecorder.NOOP : usageRecorder;
        PipelineContext result = null;
        TeamUsageReservation reservation = TeamUsageReservation.EMPTY;
        long startedNanos = 0L;
        Span span = EchoMindTrace.startSpan(operation);
        span.setAttribute("echomind.user_id", userId);
        span.setAttribute("echomind.agent_id", nullToBlank(agentId));
        span.setAttribute("echomind.session_id", nullToBlank(sessionId));
        try (Scope ignored = span.makeCurrent()) {
            try {
                recorder.assertAllowed(userId, agentId, sessionId);
                reservation = recorder.reserveUsage(userId, agentId, sessionId, prompt);
                startedNanos = System.nanoTime();
                result = executeInternalForUser(userId, agentId, sessionId, prompt, toolExposureEnabled,
                    reservation.pipelineAttributes());
                fillTeamContext(result, userId, agentId, sessionId);
                reservation.attachTo(result);
                if (result != null) {
                    span.setAttribute("echomind.model_id", nullToBlank(result.getModelId()));
                }
                boolean failed = result != null && result.hasFailed();
                recorder.record(operation, userId, agentId, sessionId, result, startedNanos, failed,
                    failed ? result.effectiveFailureReason() : null);
                return result;
            } catch (RuntimeException e) {
                EchoMindTrace.recordException(span, e);
                reservation.attachTo(result);
                recorder.record(operation, userId, agentId, sessionId, result, startedNanos, true, e.getMessage());
                if (result == null) {
                    recorder.releaseReservations(reservation.allReservationIds());
                }
                throw translateGovernanceRejection(e);
            }
        } finally {
            span.end();
        }
    }

    private RuntimeException translateGovernanceRejection(RuntimeException e) {
        if (!(e instanceof ModelInvocationRejectedException rejected)) {
            return e;
        }
        ErrorDetail detail = rejected.errorDetail();
        String code = detail == null ? "" : nullToBlank(detail.code());
        String message = detail == null || detail.message() == null || detail.message().isBlank()
            ? e.getMessage()
            : detail.message();
        if ("USER_TOKEN_QUOTA_EXCEEDED".equals(code)) {
            return new TeamUsageQuotaExceededException(message, e);
        }
        if ("PROVIDER_TOKEN_BUDGET_EXCEEDED".equals(code)
            || "TOKEN_RESERVATION_UNAVAILABLE".equals(code)) {
            return new TeamProviderBudgetExceededException(message, e);
        }
        return e;
    }

    private void fillTeamContext(PipelineContext ctx, String userId, String agentId, String sessionId) {
        if (ctx == null) {
            return;
        }
        ctx.setUserId(nonBlank(ctx.getUserId(), userId));
        ctx.setAgentId(nonBlank(ctx.getAgentId(), agentId));
        ctx.setSessionId(nonBlank(ctx.getSessionId(), sessionId));
    }

    private PipelineContext executeInternalForUser(String userId, String agentId, String sessionId, String prompt,
                                                   boolean toolExposureEnabled, Map<String, Object> initialAttributes) {
        if (initialAttributes == null || initialAttributes.isEmpty()) {
            return executeInternalForUser(userId, agentId, sessionId, prompt, toolExposureEnabled);
        }
        if ("default".equals(userId)) {
            return toolExposureEnabled
                ? orchestrator.executeInternal(agentId, sessionId, prompt, true, initialAttributes)
                : orchestrator.executeInternal(agentId, sessionId, prompt, false, initialAttributes);
        }
        return orchestrator.executeInternal(userId, agentId, sessionId, prompt, toolExposureEnabled, initialAttributes);
    }

    private PipelineContext executeInternalForUser(String userId, String agentId, String sessionId, String prompt,
                                                   boolean toolExposureEnabled) {
        if ("default".equals(userId)) {
            return toolExposureEnabled
                ? orchestrator.executeInternal(agentId, sessionId, prompt)
                : orchestrator.executeInternal(agentId, sessionId, prompt, false);
        }
        return orchestrator.executeInternal(userId, agentId, sessionId, prompt, toolExposureEnabled);
    }

    private void requireRunWritable(String runId) {
        if (isRunDeleted(runId)) {
            throw new IllegalStateException("Run was deleted: " + runId);
        }
    }

    private boolean isRunDeleted(String runId) {
        return deletedRunIds.contains(runId) || !runMapper.existsById(runId);
    }

    private void prepareStepRetry(TeamRunEntity run, TeamStepEntity step, ReviewerDecision decision,
                                  TeamMember reviewer) {
        // 重试前把上一轮输出、审查原因和修正建议沉淀到 Step，下一轮 Executor prompt 会带上。
        List<String> previous = new ArrayList<>(json.stringList(step.getPreviousOutputsJson()));
        if (step.getRawOutput() != null && !step.getRawOutput().isBlank()) {
            previous.add(step.getRawOutput());
        }
        StepReflection reflection = reflectionForStep(step, decision);
        step.setPreviousOutputsJson(json.toJson(previous));
        step.setRevisionInstructions(reflection.revisionInstructions().isBlank()
            ? decision.revisionInstructions()
            : reflection.revisionInstructions());
        step.setLastReviewReason(reflection.reviewReason().isBlank() ? decision.reason() : reflection.reviewReason());
        step.setReflectionJson(json.toJson(reflection));
        step.setRetryCount(step.getRetryCount() + 1);
        step.setStatus(TeamStepStatus.RETRYING);
        step.setQualityStatus(TeamStepQualityStatus.RETRY_REQUESTED);
        step.setReviewStatus("RETRY_REQUESTED");
        step.setStartedAt(null);
        step.setCompletedAt(null);
        stepMapper.upsertById(step);
        recordEvent(run.getRunId(), step.getStepId(), TeamEventType.STEP_REFLECTION_RECORDED, reviewer.role(),
            reviewer.agentId(), "Reviewer 写入 Reflexion 重试上下文", reflection);
        recordEvent(run.getRunId(), step.getStepId(), TeamEventType.RETRY_REQUESTED, reviewer.role(),
            reviewer.agentId(), decision.reason(), decision);
    }

    private StepReflection reflectionForStep(TeamStepEntity step, ReviewerDecision decision) {
        StepReflection reflection = decision.stepReflections().get(step.getStepId());
        if (reflection == null && step.getClientStepId() != null) {
            reflection = decision.stepReflections().get(step.getClientStepId());
        }
        if (reflection != null) {
            return reflection;
        }
        return new StepReflection(
            step.getAcceptanceCriteria() == null || step.getAcceptanceCriteria().isBlank()
                ? List.of()
                : List.of(step.getAcceptanceCriteria()),
            decision.reason(),
            decision.revisionInstructions(),
            summarize(step.getRawOutput()),
            List.of("不要重复上一轮不满足验收标准的输出", "不要只输出工具调用或搜索关键词")
        );
    }

    private String dependencyOutputs(TeamStepEntity step) {
        List<String> dependencyIds = json.stringList(step.getDependsOnStepIdsJson());
        if (dependencyIds.isEmpty()) {
            return "";
        }
        List<Map<String, String>> outputs = new ArrayList<>();
        for (TeamStepEntity dependency : stepMapper.selectByRunIdOrderByStepIndexAsc(step.getRunId())) {
            if (dependencyIds.contains(dependency.getStepId())) {
                outputs.add(Map.of(
                    "stepId", dependency.getStepId(),
                    "title", dependency.getTitle(),
                    "output", nullToBlank(dependency.getRawOutput())
                ));
            }
        }
        return json.toJson(outputs);
    }

    private String summarize(String value) {
        String text = nullToBlank(value).trim();
        return text.length() <= 500 ? text : text.substring(0, 500) + "...";
    }

    /**
     * 暂停任务等待用户澄清
     * 
     * @param run 任务实体
     * @param decision Reviewer 的审核决策
     * @param stage 澄清阶段（计划审核/结果审核）
     */
    private void pauseForClarification(TeamRunEntity run, ReviewerDecision decision, TeamClarificationStage stage) {
        TeamRedisDagStore store = getDagStore();
        if (store != null) {
            store.setControlFlag(run.getRunId(), "stopping", stage.name());
        }

        String question = decision.questions().isEmpty() ? decision.reason() : String.join("\n", decision.questions());
        run.setClarificationQuestion(question);
        run.setClarificationStage(stage);
        run.setStatus(TeamRunStatus.NEEDS_CLARIFICATION);
        runMapper.upsertById(run);
        recordEvent(run.getRunId(), null, TeamEventType.CLARIFICATION_REQUESTED, TeamRole.REVIEWER,
            null, question, decision);
    }

    private void validateReviewerDecision(ReviewerDecision decision, ReviewerDecisionScope scope) {
        ReviewerAction action = decision.action();
        if (action == null) {
            throw new IllegalArgumentException("Reviewer action is required");
        }
        if (!isActionAllowed(action, scope)) {
            throw new IllegalArgumentException("Reviewer action " + action + " is not allowed for " + scope);
        }
        if (decision.action() == ReviewerAction.ASK_CLARIFICATION
            && decision.questions().isEmpty()
            && (decision.reason() == null || decision.reason().isBlank())) {
            throw new IllegalStateException("Reviewer ASK_CLARIFICATION requires a reason or questions");
        }
        if ((decision.action() == ReviewerAction.REWORK || decision.action() == ReviewerAction.RETRY)
            && scope == ReviewerDecisionScope.STEP
            && decision.revisionInstructions().isBlank()
            && (decision.reason() == null || decision.reason().isBlank())) {
            throw new IllegalStateException("StepReviewer rework requires a reason or revisionInstructions");
        }
        if (decision.action() == ReviewerAction.REMERGE
            && decision.revisionInstructions().isBlank()
            && (decision.reason() == null || decision.reason().isBlank())) {
            throw new IllegalStateException("GlobalReviewer REMERGE requires a reason or revisionInstructions");
        }
        if (decision.action() == ReviewerAction.FAILED
            && (decision.reason() == null || decision.reason().isBlank())) {
            throw new IllegalStateException("Reviewer FAILED requires a reason");
        }
        if (isGlobalSuccess(decision)
            && scope == ReviewerDecisionScope.GLOBAL
            && (decision.finalReport() == null || decision.finalReport().isBlank())) {
            throw new IllegalStateException("GlobalReviewer success requires finalReport");
        }
    }

    private boolean isActionAllowed(ReviewerAction action, ReviewerDecisionScope scope) {
        return switch (scope) {
            case PLAN -> action == ReviewerAction.CONTINUE
                || action == ReviewerAction.RETRY
                || action == ReviewerAction.ASK_CLARIFICATION
                || action == ReviewerAction.FAILED;
            case STEP -> action == ReviewerAction.PASS
                || action == ReviewerAction.CONTINUE
                || action == ReviewerAction.REWORK
                || action == ReviewerAction.RETRY
                || action == ReviewerAction.ACCEPT_WITH_RISK
                || action == ReviewerAction.FAILED;
            case GLOBAL -> action == ReviewerAction.SUCCESS
                || action == ReviewerAction.CONTINUE
                || action == ReviewerAction.REMERGE
                || action == ReviewerAction.FAILED;
        };
    }

    private boolean isStepPass(ReviewerDecision decision) {
        return decision.action() == ReviewerAction.PASS || decision.action() == ReviewerAction.CONTINUE;
    }

    private boolean isGlobalSuccess(ReviewerDecision decision) {
        return decision.action() == ReviewerAction.SUCCESS || decision.action() == ReviewerAction.CONTINUE;
    }

    @Transactional
    protected void markStepFailed(String runId, String stepId, String message) {
        if (isRunDeleted(runId)) {
            return;
        }
        TeamStepEntity step = stepMapper.selectOptionalById(stepId).orElse(null);
        if (step != null) {
            if (isStepTerminal(step.getStatus())) {
                log.info("Skip failing step {} for run {} because it is already terminal", stepId, runId);
                return;
            }
            step.setStatus(TeamStepStatus.FAILED);
            step.setRawOutput("[Error] " + nullToBlank(message));
            step.setCompletedAt(Instant.now());
            stepMapper.upsertById(step);
        }
        recordEvent(runId, stepId, TeamEventType.STEP_FAILED, TeamRole.EXECUTOR, null,
            "Step failed: " + nullToBlank(message), null);
    }

    @Transactional
    protected void markStepTimedOut(String runId, String stepId) {
        if (isRunDeleted(runId)) {
            return;
        }
        String message = "Step execution timed out after " + runtimeProperties.getStepTimeoutSeconds() + " seconds";
        TeamStepEntity step = stepMapper.selectOptionalById(stepId).orElse(null);
        if (step != null && !isStepTerminal(step.getStatus())) {
            step.setStatus(TeamStepStatus.FAILED);
            step.setRawOutput("[Error] " + message);
            step.setCompletedAt(Instant.now());
            stepMapper.upsertById(step);
        } else if (step != null) {
            log.info("Skip timing out step {} for run {} because it is already terminal", stepId, runId);
            return;
        }
        recordEvent(runId, stepId, TeamEventType.STEP_TIMEOUT, TeamRole.EXECUTOR, null,
            "Step 超时熔断: " + message, Map.of("stepTimeoutSeconds", runtimeProperties.getStepTimeoutSeconds()));
    }

    @Transactional
    protected void failRun(String runId, String message) {
        TeamRunEntity run = runMapper.selectOptionalById(runId).orElse(null);
        if (run != null) {
            run.setStatus(TeamRunStatus.FAILED);
            run.setFinalOutput("[Error] " + nullToBlank(message));
            runMapper.upsertById(run);
        }
        recordEvent(runId, null, TeamEventType.RUN_FAILED, null, null,
            "Run failed: " + nullToBlank(message), null);
    }

    private void replaceSteps(TeamRunEntity run, List<PlannedStep> planned) {
        replaceSteps(run, planned, false);
    }

    /**
     * 将 Planner 生成的计划步骤转换为数据库中的 Step 记录
     * 
     * @param run 任务实体
     * @param planned Planner 生成的计划步骤列表
     * @param preserveExisting 是否保留现有步骤（标记为 SUPERSEDED）
     */
    private void replaceSteps(TeamRunEntity run, List<PlannedStep> planned, boolean preserveExisting) {
        // 将 Planner 的 clientStepId DAG 落成数据库 Step；dependsOn 统一转换为真实 stepId。
        int baseIndex = 0;
        
        // 1. 处理现有步骤
        if (preserveExisting) {
            // 保留模式：将现有步骤标记为 SUPERSEDED
            for (TeamStepEntity existing : stepMapper.selectByRunIdOrderByStepIndexAsc(run.getRunId())) {
                if (existing.getStatus() != TeamStepStatus.SUPERSEDED) {
                    existing.setStatus(TeamStepStatus.SUPERSEDED);
                    stepMapper.upsertById(existing);
                }
                // 记录最大的步骤索引，新步骤从这个索引之后开始
                baseIndex = Math.max(baseIndex, existing.getStepIndex());
            }
        } else {
            // 不保留模式：直接删除所有现有步骤
            stepMapper.deleteByRunId(run.getRunId());
        }
        
        // 2. 过滤出可执行的步骤（排除最终集成步骤）
        List<PlannedStep> executable = planned.stream()
            .filter(item -> !isFinalIntegrationStep(item))
            .toList();
        
        // 3. 验证计划的 DAG 结构
        validatePlannedDag(executable);
        
        // 4. 判断是否为简单草稿模式（简单任务且只有一个步骤）
        boolean simpleDraft = run.getTaskLevel() == TeamTaskLevel.SIMPLE && executable.size() == 1;

        // 5. 构建 clientStepId 到真实 stepId 的映射
        Map<String, String> clientToStepId = new LinkedHashMap<>();
        int index = 1;
        for (PlannedStep item : executable) {
            String clientStepId = simpleDraft ? "simple-draft" : clientStepId(item, index);
            // 生成唯一的 stepId：step-{index}-{uuid前8位}
            clientToStepId.put(clientStepId, "step-" + index + "-" + UUID.randomUUID().toString().substring(0, 8));
            index++;
        }

        // 6. 创建数据库 Step 记录
        index = 1;
        for (PlannedStep item : executable) {
            String clientStepId = simpleDraft ? "simple-draft" : clientStepId(item, index);
            
            // 将依赖的 clientStepId 转换为真实的 stepId
            List<String> dependencyStepIds = item.dependsOn().stream()
                .map(clientToStepId::get)
                .filter(id -> id != null && !id.isBlank())
                .toList();
            
            // 创建 Step 实体
            TeamStepEntity step = new TeamStepEntity();
            step.setStepId(clientToStepId.get(clientStepId));
            step.setRunId(run.getRunId());
            step.setStepIndex(baseIndex + index);  // 从 baseIndex 之后开始编号
            step.setClientStepId(clientStepId);
            step.setTitle(item.title());
            step.setDescription(item.description());
            step.setRequiredCapabilitiesJson(json.toJson(item.requiredCapabilities()));
            step.setDependsOnStepIdsJson(json.toJson(dependencyStepIds));
            
            // 风险评估
            TeamRiskDecision riskDecision = riskPolicy.decide(item);
            step.setRiskLevel(riskDecision.level());
            step.setRiskReason(riskDecision.reason());
            
            step.setAcceptanceCriteria(item.acceptanceCriteria());
            step.setStatus(TeamStepStatus.PENDING);           // 初始状态为待执行
            step.setQualityStatus(TeamStepQualityStatus.PENDING);  // 质量状态待审核
            step.setPlanIteration(run.getFullReplanCount() + run.getPartialReplanCount() + run.getResultReplanCount());
            
            // 保存到数据库
            stepMapper.upsertById(step);
            
            // 记录风险判定事件
            recordEvent(run.getRunId(), step.getStepId(), TeamEventType.RISK_DECIDED, null, null,
                "RiskPolicy 判定为" + (riskDecision.level() == TeamRiskLevel.HIGH ? "高风险" : "低风险")
                    + "：" + riskDecision.reason(), riskDecision);
            
            index++;
        }
    }

    private String clientStepId(PlannedStep item, int index) {
        return item.clientStepId() == null || item.clientStepId().isBlank()
            ? "step-" + index
            : item.clientStepId().trim();
    }

    private void validatePlannedDag(List<PlannedStep> planned) {
        // Planner 输出进入数据库前必须先过结构校验，避免运行期才发现断边或环。
        if (planned == null || planned.isEmpty()) {
            throw new IllegalStateException("Planner did not produce executable steps");
        }
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < planned.size(); i++) {
            PlannedStep step = planned.get(i);
            String clientStepId = step.clientStepId() == null || step.clientStepId().isBlank()
                ? "step-" + (i + 1)
                : step.clientStepId().trim();
            if (!ids.add(clientStepId)) {
                throw new IllegalStateException("Planner produced duplicate clientStepId: " + clientStepId);
            }
        }
        for (int i = 0; i < planned.size(); i++) {
            PlannedStep step = planned.get(i);
            String clientStepId = step.clientStepId() == null || step.clientStepId().isBlank()
                ? "step-" + (i + 1)
                : step.clientStepId().trim();
            for (String dependency : step.dependsOn()) {
                if (!ids.contains(dependency)) {
                    throw new IllegalStateException("Planner produced unknown dependency: " + dependency);
                }
                if (clientStepId.equals(dependency)) {
                    throw new IllegalStateException("Planner produced self dependency: " + clientStepId);
                }
            }
        }
        if (hasCycle(planned)) {
            throw new IllegalStateException("Planner produced dependency cycle");
        }
    }

    private boolean hasCycle(List<PlannedStep> planned) {
        Map<String, List<String>> graph = new LinkedHashMap<>();
        for (int i = 0; i < planned.size(); i++) {
            PlannedStep step = planned.get(i);
            String clientStepId = step.clientStepId() == null || step.clientStepId().isBlank()
                ? "step-" + (i + 1)
                : step.clientStepId().trim();
            graph.put(clientStepId, step.dependsOn());
        }
        Set<String> visiting = new HashSet<>();
        Set<String> visited = new HashSet<>();
        for (String id : graph.keySet()) {
            if (hasCycle(id, graph, visiting, visited)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasCycle(String id, Map<String, List<String>> graph, Set<String> visiting, Set<String> visited) {
        if (visited.contains(id)) {
            return false;
        }
        if (!visiting.add(id)) {
            return true;
        }
        for (String dependency : graph.getOrDefault(id, List.of())) {
            if (hasCycle(dependency, graph, visiting, visited)) {
                return true;
            }
        }
        visiting.remove(id);
        visited.add(id);
        return false;
    }

    private boolean isFinalIntegrationStep(PlannedStep item) {
        if (item == null) {
            return false;
        }
        // 最终报告由 Reviewer 生成；Planner 如果误产出“汇总/整合”Step，这里过滤掉。
        String text = (nullToBlank(item.title()) + " " + nullToBlank(item.description()) + " "
            + nullToBlank(item.acceptanceCriteria())).toLowerCase();
        return containsAny(text,
            "最终报告",
            "最终方案",
            "可执行方案",
            "整合输出",
            "综合方案",
            "汇总输出",
            "汇总成",
            "整合成",
            "撰写完整",
            "final report",
            "final deliverable",
            "integration",
            "integrated report"
        );
    }

    private boolean containsAny(String text, String... values) {
        if (text == null || text.isBlank()) {
            return false;
        }
        for (String value : values) {
            if (text.contains(value)) {
                return true;
            }
        }
        return false;
    }

    @Transactional
    protected void recordEvent(String runId, String stepId, TeamEventType type, TeamRole actorRole,
                               String actorAgentId, String message, Object payload) {
        // Event 是 Team 看板和 Mermaid 的审计来源，只追加，不参与状态判断。
        if (isRunDeleted(runId)) {
            log.debug("Skip team event for deleted run: {}", runId);
            return;
        }
        TeamEventEntity event = new TeamEventEntity();
        event.setRunId(runId);
        event.setStepId(stepId);
        event.setType(type);
        event.setActorRole(actorRole);
        event.setActorAgentId(actorAgentId);
        event.setMessage(message);
        event.setPayloadJson(payload == null ? null : json.toJson(payload));
        eventMapper.upsertById(event);
    }

    private List<TeamMemberSpec> normalizeSpecs(List<TeamMemberSpec> specs) {
        if (specs == null) {
            return List.of();
        }
        int fallbackOrder = 10;
        List<TeamMemberSpec> normalized = new ArrayList<>();
        for (TeamMemberSpec spec : specs) {
            if (spec == null) {
                continue;
            }
            normalized.add(new TeamMemberSpec(
                spec.agentId(),
                spec.role(),
                spec.capabilityTags() == null ? List.of() : spec.capabilityTags(),
                spec.sortOrder() == 0 ? fallbackOrder : spec.sortOrder()
            ));
            fallbackOrder += 10;
        }
        return normalized;
    }

    private void validateMembers(List<TeamMemberSpec> specs) {
        long planners = specs.stream().filter(spec -> spec.role() == TeamRole.PLANNER).count();
        long executors = specs.stream().filter(spec -> spec.role() == TeamRole.EXECUTOR).count();
        long reviewers = specs.stream().filter(spec -> spec.role() == TeamRole.REVIEWER).count();
        if (planners != 1 || executors < 1 || reviewers != 1) {
            throw new IllegalArgumentException("Team requires exactly one Planner, at least one Executor, and exactly one Reviewer");
        }
        for (TeamMemberSpec spec : specs) {
            if (spec.agentId() == null || spec.agentId().isBlank()) {
                throw new IllegalArgumentException("member agentId is required");
            }
            if (agentFactory.get(spec.agentId()) == null) {
                throw new IllegalArgumentException("Agent not found: " + spec.agentId());
            }
        }
    }

    private TeamRunEntity loadRun(String teamId, String userId, String runId) {
        // 加载团队协作任务实体
        TeamRunEntity run = runMapper.selectOptionalById(runId)
            .orElseThrow(() -> new IllegalArgumentException("Run not found: " + runId));
        if (!run.getTeamId().equals(teamId)) {
            throw new IllegalArgumentException("Run does not belong to team: " + runId);
        }
        if (!normalizeUserId(run.getUserId()).equals(normalizeUserId(userId))) {
            throw new IllegalArgumentException("Run does not belong to current user: " + runId);
        }
        loadTeamForOwner(teamId, userId);
        return run;
    }

    private TeamEntity loadTeamForOwner(String teamId, String userId) {
        return teamMapper.selectOptionalByTeamIdAndOwnerUserId(teamId, normalizeUserId(userId))
            .orElseThrow(() -> new IllegalArgumentException("Team not found: " + teamId));
    }

    private TeamSnapshot toTeamSnapshot(TeamEntity team) {
        List<TeamMember> members = memberMapper.selectByTeamIdOrderBySortOrderAscIdAsc(team.getTeamId()).stream()
            .map(this::toMember)
            .toList();
        return new TeamSnapshot(team.getTeamId(), team.getOwnerUserId(), team.getName(), members, team.getCreatedAt(), team.getUpdatedAt());
    }

    private TeamMember toMember(TeamMemberEntity entity) {
        Agent agent = agentFactory.get(entity.getAgentId());
        return new TeamMember(
            entity.getAgentId(),
            agent == null ? entity.getAgentId() : agent.getName(),
            entity.getRole(),
            json.stringList(entity.getCapabilityTagsJson()),
            entity.getSortOrder(),
            agent
        );
    }

    private TeamRunSnapshot toRunSnapshot(TeamRunEntity run, boolean includeSteps, boolean includeEvents) {
        // 前端 Team 看板只读 Snapshot；这里集中完成 Entity -> DTO 和动态图生成。
        List<TeamStepSnapshot> steps = includeSteps
            ? stepMapper.selectByRunIdOrderByStepIndexAsc(run.getRunId()).stream().map(this::toStepSnapshot).toList()
            : List.of();
        List<TeamEventSnapshot> events = includeEvents
            ? eventMapper.selectByRunIdOrderByCreatedAtAscIdAsc(run.getRunId()).stream().map(this::toEventSnapshot).toList()
            : List.of();
        return new TeamRunSnapshot(
            run.getRunId(),
            run.getTeamId(),
            run.getUserId(),
            run.getTask(),
            run.getStatus(),
            run.getTaskLevel() == null ? TeamTaskLevel.COMPLEX.name() : run.getTaskLevel().name(),
            reviewOptions(run),
            run.getClarificationQuestion(),
            run.getClarificationAnswer(),
            run.getClarificationStage() == null ? null : run.getClarificationStage().name(),
            run.getPlanReviewJson(),
            run.getResultReviewJson(),
            run.getMergeOutput(),
            run.getGlobalReviewJson(),
            run.getConflictReportJson(),
            run.getArbitrationJson(),
            run.getFinalOutput(),
            includeSteps ? dynamicMermaid(run, steps, events) : run.getMermaidDiagram(),
            run.getPlanRetryCount(),
            run.getResultReplanCount(),
            run.getPartialReplanCount(),
            run.getFullReplanCount(),
            run.getArbitrationCount(),
            steps,
            events,
            run.getCreatedAt(),
            run.getUpdatedAt()
        );
    }

    private TeamStepSnapshot toStepSnapshot(TeamStepEntity step) {
        return new TeamStepSnapshot(
            step.getStepId(),
            step.getStepIndex(),
            step.getClientStepId(),
            step.getTitle(),
            step.getDescription(),
            json.stringList(step.getRequiredCapabilitiesJson()),
            json.stringList(step.getDependsOnStepIdsJson()),
            step.getRiskLevel(),
            step.getRiskReason(),
            step.getAcceptanceCriteria(),
            step.getAssignedAgentId(),
            step.getStatus(),
            step.getRawOutput(),
            step.getRevisionInstructions(),
            step.getReviewStatus(),
            step.getQualityStatus(),
            step.getSubReviewJson(),
            step.getLastReviewReason(),
            step.getReflectionJson(),
            step.getPlanIteration(),
            step.getRetryCount(),
            step.getStartedAt(),
            step.getCompletedAt()
        );
    }

    private String dynamicMermaid(TeamRunEntity run, List<TeamStepSnapshot> steps, List<TeamEventSnapshot> events) {
        if (steps == null || steps.isEmpty()) {
            return run.getMermaidDiagram();
        }
        TeamEntity team = loadTeamForOwner(run.getTeamId(), run.getUserId());
        return MermaidGenerator.generateFromSnapshots(
            team.getName(),
            run.getStatus(),
            run.getTaskLevel() == null ? TeamTaskLevel.COMPLEX.name() : run.getTaskLevel().name(),
            steps,
            events == null ? List.of() : events
        );
    }

    private TeamEventSnapshot toEventSnapshot(TeamEventEntity event) {
        return new TeamEventSnapshot(
            event.getId(),
            event.getRunId(),
            event.getStepId(),
            event.getType(),
            event.getActorRole(),
            event.getActorAgentId(),
            event.getMessage(),
            event.getPayloadJson(),
            event.getCreatedAt()
        );
    }

    private TeamMember planner(TeamSnapshot team) {
        return member(team, TeamRole.PLANNER);
    }

    private TeamMember reviewer(TeamSnapshot team) {
        return member(team, TeamRole.REVIEWER);
    }

    private TeamMember subReviewer(TeamSnapshot team) {
        return optionalMember(team, TeamRole.SUB_REVIEWER);
    }

    private TeamMember merger(TeamSnapshot team) {
        return optionalMember(team, TeamRole.MERGER);
    }

    private TeamMember member(TeamSnapshot team, TeamRole role) {
        return team.members().stream()
            .filter(item -> item.role() == role)
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("Missing team role: " + role));
    }

    private TeamMember executorById(TeamSnapshot team, String agentId) {
        return team.members().stream()
            .filter(item -> item.role() == TeamRole.EXECUTOR)
            .filter(item -> item.agentId().equals(agentId))
            .findFirst()
            .orElse(null);
    }

    private TeamMember executorBySelection(TeamSnapshot team, TeamAgentSelection selection) {
        if (selection == null) {
            return null;
        }
        String memberKey = selection.memberKey();
        if (memberKey != null && !memberKey.isBlank()) {
            TeamMember byKey = team.members().stream()
                .filter(item -> item.role() == TeamRole.EXECUTOR)
                .filter(item -> (item.agentId() + "#" + item.sortOrder()).equalsIgnoreCase(memberKey))
                .findFirst()
                .orElse(null);
            if (byKey != null) {
                return byKey;
            }
        }
        return executorById(team, selection.agentId());
    }

    private TeamMember optionalMember(TeamSnapshot team, TeamRole role) {
        return team.members().stream()
            .filter(item -> item.role() == role)
            .findFirst()
            .orElseGet(() -> reviewer(team));
    }

    private TeamReviewOptions reviewOptions(TeamRunEntity run) {
        if (run == null) {
            return TeamReviewOptions.QUALITY_FIRST;
        }
        return new TeamReviewOptions(
            run.isPlanReviewEnabled(),
            run.isSubReviewEnabled(),
            run.isGlobalReviewEnabled(),
            run.isSimpleFastPathEnabled()
        );
    }

    private ReviewerDecision skippedReviewDecision(String reason, String finalReport) {
        return ReviewerDecision.continueWith(reason, finalReport);
    }

    private void completeRunWithFinalOutput(TeamRunEntity run, TeamSnapshot team, String finalOutput,
                                            TeamRole actorRole, String actorAgentId, String eventMessage) {
        run.setFinalOutput(finalOutput == null || finalOutput.isBlank() ? synthesizeFallbackReport(run) : finalOutput);
        run.setStatus(TeamRunStatus.COMPLETED);
        run.setMermaidDiagram(MermaidGenerator.generateFromSnapshots(
            team.name(),
            TeamRunStatus.COMPLETED,
            run.getTaskLevel() == null ? TeamTaskLevel.COMPLEX.name() : run.getTaskLevel().name(),
            toRunSnapshot(run, true, true).steps(),
            toRunSnapshot(run, true, true).events()
        ));
        runMapper.upsertById(run);
        recordEvent(run.getRunId(), null, TeamEventType.RUN_COMPLETED, actorRole, actorAgentId, eventMessage, null);
    }

    private String synthesizeFallbackReport(TeamRunEntity run) {
        StringBuilder output = new StringBuilder();
        output.append("任务完成。\n\n");
        for (TeamStepEntity step : stepMapper.selectByRunIdOrderByStepIndexAsc(run.getRunId())) {
            output.append("## ").append(step.getTitle()).append("\n")
                .append(nullToBlank(step.getRawOutput())).append("\n\n");
        }
        return output.toString().trim();
    }

    private boolean isTerminal(TeamRunStatus status) {
        return status == TeamRunStatus.COMPLETED
            || status == TeamRunStatus.FAILED
            || status == TeamRunStatus.NEEDS_CLARIFICATION;
    }

    private void scheduleRun(String runId) {
        if (executionEngine == null) {
            return;
        }
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager
                .registerSynchronization(new org.springframework.transaction.support.TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        executionEngine.scheduleRun(runId, id -> {}, (id, error) -> failRun(id, error.getMessage()));
                    }
                });
        } else {
            executionEngine.scheduleRun(runId, id -> {}, (id, error) -> failRun(id, error.getMessage()));
        }
    }

    private String plannerSession(TeamRunEntity run) {
        return "team-run-" + run.getRunId() + "-planner";
    }

    private String reviewerSession(TeamRunEntity run) {
        return "team-run-" + run.getRunId() + "-reviewer";
    }

    private String stepSession(TeamRunEntity run, TeamStepEntity step) {
        return "team-run-" + run.getRunId() + "-step-" + step.getStepId();
    }

    private String nullToBlank(String value) {
        return value == null ? "" : value;
    }

    private String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private String nonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private String normalizeUserId(String userId) {
        return userId == null || userId.isBlank() ? "default" : userId;
    }

    // ============================================================
    // Event-Driven Engine Integration Methods
    // ============================================================

    /**
     * Entry point for the DAG coordinator: plan, review, and initialize Redis DAG.
     * Called by {@link TeamDagCoordinator#onRunStarted}.
     */
    @Transactional
    public void planAndReviewForCoordinator(String runId) {
        TeamRunEntity run = runMapper.selectOptionalById(runId)
            .orElseThrow(() -> new IllegalArgumentException("Run not found: " + runId));
        if (run.getStatus() == TeamRunStatus.COMPLETED || run.getStatus() == TeamRunStatus.FAILED) {
            log.info("Run {} already terminal, skip planAndReviewForCoordinator", runId);
            return;
        }

        // Try to acquire execute lock (handles both new PENDING and resumed PENDING)
        boolean acquired = runMapper.tryAcquireExecuteLock(runId);
        if (!acquired && run.getStatus() == TeamRunStatus.EXECUTING) {
            log.info("Run {} already executing by another thread, skip", runId);
            return;
        }

        TeamSnapshot team = getTeam(run.getTeamId());

        // If no steps exist, do plan + review; otherwise skip to dispatch (resume case)
        List<TeamStepEntity> steps = stepMapper.selectByRunIdOrderByStepIndexAsc(runId);
        if (steps.isEmpty()) {
            planAndReview(run, team);
            steps = stepMapper.selectByRunIdOrderByStepIndexAsc(runId);
        }

        var dagStore = getDagStore();
        if (dagStore != null && !steps.isEmpty()) {
            dagStore.initializeDag(runId, steps, runtimeProperties.getMaxConcurrentSteps());
            // Also persist DAG status in Redis
            dagStore.setDagStatus(runId, "EXECUTING");
        }
    }

    /**
     * Called by the DAG coordinator when all steps are complete and running count is zero.
     */
    @Transactional
    public void onDagCompleteInCoordinator(String runId) {
        TeamRunEntity run = runMapper.selectOptionalById(runId)
            .orElse(null);
        if (run == null || run.getStatus() == TeamRunStatus.COMPLETED
            || run.getStatus() == TeamRunStatus.FAILED) {
            return;
        }
        TeamSnapshot team = getTeam(run.getTeamId());
        mergeAndReviewResults(run, team);

        // Cleanup Redis DAG
        var dagStore = getDagStore();
        if (dagStore != null) {
            dagStore.destroyDag(runId);
        }
    }

    /**
     * Fail a run from the coordinator thread.
     */
    @Transactional
    public void failRunFromCoordinator(String runId, String message) {
        failRun(runId, message);
        var dagStore = getDagStore();
        if (dagStore != null) {
            dagStore.destroyDag(runId);
        }
    }

    /**
     * Mark a step as FAILED (used by DLQ compensator).
     */
    @Transactional
    public void markStepFailedFromCompensator(String runId, String stepId, String message) {
        if (isRunDeleted(runId)) return;
        TeamStepEntity step = stepMapper.selectById(stepId);
        if (step == null) return;
        step.setStatus(TeamStepStatus.FAILED);
        step.setRawOutput("[Error] " + message);
        step.setCompletedAt(Instant.now());
        stepMapper.upsertById(step);
        recordEvent(runId, stepId, TeamEventType.STEP_FAILED, null, null, message, null);
    }

    /**
     * Public entry point for step execution (called by {@link TeamStepExecutionConsumer}).
     */
    @Transactional
    public void executeStepPublic(String runId, String stepId) {
        executeStep(runId, stepId);
    }

    /**
     * Expose max step retries for the coordinator.
     */
    public int getMaxStepRetries() {
        return runtimeProperties.getMaxStepRetries();
    }

    private TeamRedisDagStore dagStoreInstance;

    public void setDagStore(TeamRedisDagStore dagStore) {
        this.dagStoreInstance = dagStore;
    }

    private TeamRedisDagStore getDagStore() {
        return dagStoreInstance;
    }

}
